package cretae.cookiewyq.rs_create_compat.support;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「可降解表」—— 降解室发电机唯一的数据来源（<b>整张表由数据包驱动</b>）。
 *
 * <h2>数据来源与文件格式</h2>
 * <p>扫描 {@code data/<namespace>/decomposition/*.json}（见 {@link DecompositionDataLoader}），每个文件：</p>
 * <pre>
 * {
 *   "replace": false,                 // true = 先清空已累积的表（默认 false = 追加）
 *   "mod_loaded": "farmersdelight",   // 可选：该 mod 没装就整份跳过（缺失的 tag/id 不会变成报错噪音）
 *   "remove": ["minecraft:oak_log", "#minecraft:planks"],  // 可选：删掉某些条目（item 或 #tag）
 *   "entries": [
 *     {"item": "minecraft:oak_sapling", "time": 20, "energy": 50},   // 只有电、没有副产物
 *     {
 *       "tag": "minecraft:leaves", "time": 10, "energy": 25,
 *       "results": [                  // 可选：到点后独立掷骰的副产物列表
 *         {"item": "minecraft:stick", "count": 1},                  // chance 省略 = 1.0（必定）
 *         {"item": "minecraft:oak_sapling", "count": 1, "chance": 0.05}
 *       ]
 *     },
 *     {
 *       "item": "minecraft:sugar_cane", "time": 20, "energy": 50,
 *       "result": {"item": "minecraft:sugar", "count": 1}          // 「只有一项」的语法糖
 *     }
 *   ]
 * }
 * </pre>
 * <p><b>{@code results} / {@code result}（第 123 轮新增）</b>：两者<b>二选一</b>，同时出现 = 该条作废。
 * {@code results} 是数组，每一项是 {@code {"item": 物品 id（必填）, "count": 1..64（默认 1）,
 * "chance": 0..1（默认 1.0）}}；{@code result} 是「只有一项」时把那个对象直接写上的语法糖。
 * 每一项用服务端 {@code level.random} <b>独立</b>判定，命中才产出；空列表 / 不写 = 只有电没有副产物。</p>
 *
 * <h2>叠加顺序（「后写覆盖先写」的准确含义）</h2>
 * <ol>
 *     <li>文件按<b>命名空间</b>排序：本模组 {@code rs_create_compat} 的<b>内置默认表永远最先</b>，
 *     其余命名空间按字母序跟在后面。这样「玩家数据包覆盖内置表」不依赖文件名运气；</li>
 *     <li>同一命名空间内按路径字母序；</li>
 *     <li>同一物品被多次声明时<b>后出现的赢</b>（{@link LinkedHashMap#put}）；{@code item} 优先于
 *     {@code tag}（先查精确匹配，再按 tag 顺序查）；</li>
 *     <li>文件里的 {@code "replace": true} 会<b>清空</b>此前累积的全部条目（含内置表），
 *     于是「一个只含木头的数据包」可以真正把默认表掀掉；</li>
 *     <li>{@code remove} 在 {@code replace} 之后、{@code entries} 之前生效。</li>
 * </ol>
 *
 * <h2>容错（重要：别人的数据包写错不许炸服务器）</h2>
 * <p>解析分两层：{@link #apply} 逐文件、{@link #parseEntry} 逐条目，<b>坏一条只跳过它自己</b>：</p>
 * <ul>
 *     <li>条目主键（{@code item}）不存在 ⇒ 跳过该条 + 计数；</li>
 *     <li>副产物（{@code results} 里的某一项）的 {@code item} 不存在 ⇒ <b>只跳过那一项</b>，
 *     该条其余数值 / 其它产物照常生效（「只少一个产物」），并计入 {@code itemErrors}；</li>
 *     <li>不存在的 tag ⇒ <b>不解析</b>（tag 是懒匹配，见下），永远不会报错；</li>
 *     <li>{@code time} / {@code energy} <b>缺失</b> ⇒ 该条作废 + 计数；<b>越界</b> ⇒ 夹到合法区间 + 计数
 *     （第 123 轮起不抛异常，见 {@link DecompositionProfile} 类注释）；</li>
 *     <li>{@code results} / {@code result} 同时出现、{@code results} 不是数组、{@code result} 不是对象
 *     ⇒ 该条作废 + 计数（结构错误没法猜作者意图，但只影响这一条）；</li>
 *     <li>副产物 {@code count} / {@code chance} 越界 ⇒ 裁剪 + 计数；</li>
 *     <li>整个文件不是 JSON 对象 / 不是合法 JSON ⇒ 跳过 + 日志一条。</li>
 * </ul>
 * <p>重载结束后只打<b>一条</b>汇总日志（成功/跳过/错误计数/裁剪次数 + 耗时），绝不逐条刷屏
 * （逐条 {@code WARN} 只给「引用了不存在的 item / tag」这类需要作者动手改的情况）。</p>
 *
 * <h2>为什么 tag 是「懒匹配」而不是在重载时展开成 Item 集合</h2>
 * <p>数据包重载时各 listener 的 apply 顺序不保证，本监听器可能<b>先于</b> {@code TagManager} 执行；
 * 那时展开 tag 只会拿到空集合（= 农夫乐事 / 原版花全部失效，而且看起来「莫名其妙」）。
 * 因此 tag 条目存 {@link TagKey}，在<b>查询时</b>用 {@link ItemStack#is(TagKey)} 现查 —— 那一刻标签一定已就绪。</p>
 *
 * <h2>客户端为什么「放行」</h2>
 * <p>专用服务器上客户端拿不到服务端的 {@code data/} 数据包，因此客户端的这张表是空的。
 * 若在客户端把「查不到」当成「不可降解」，玩家连鼠标都放不进槽里（客户端预测直接拒绝），
 * 而真正的判定本来就在服务端。因此 <b>表没被重载过时 {@link #isDecomposable} 返回 true</b>：
 * 客户端放行、服务端权威。单人游戏两端同 JVM，静态表是有数据的，走的仍是精确判定。</p>
 */
public final class DecompositionTable {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 本模组命名空间（内置默认表所在，排序时永远最先）。 */
    private static final String BUILTIN_NAMESPACE = RS_Create_Compat.MODID;
    /** 「JSON 里没有这个键」的哨兵：与「写了但越界」区分开（INT_MIN 不可能是合法 time/energy）。 */
    private static final int MISSING_INT = Integer.MIN_VALUE;

    /** 空表（重载前的初始状态）。 */
    private static final State EMPTY = new State(Map.of(), List.of());
    /** 当前生效的表（整体替换，绝不就地修改 —— 读方可能正拿着旧引用）。 */
    private static volatile State state = EMPTY;
    /** 是否至少完成过一次数据包重载（false = 专用服务器的客户端，见类注释）。 */
    private static volatile boolean applied;
    /** 重载版本号（每完成一次 +1；方块实体用它判断「要不要重算槽里的无效物品数」）。 */
    private static volatile long revision;
    /** 最近一次重载的汇总（自检 / 调试 / 日志共用）。 */
    private static volatile LoadReport lastReport = new LoadReport(0, 0, 0, 0, 0, 0, 0L);

    private DecompositionTable() {
    }

    /**
     * 一次重载的结果汇总。
     *
     * <p>{@code clamped} 是第 123 轮新增的「裁剪次数」：{@code time} / {@code energy} / 副产物
     * {@code count} / {@code chance} 里有多少个值越界被夹回过。它<b>不是</b>错误（条目照常生效），
     * 但 {@link #clean()} 会把「有过裁剪」也算作不干净 —— 内置表正常时应该恒为 0，
     * 非 0 就说明有数据包作者把数值写超了，值得让他看见。</p>
     */
    public record LoadReport(int files, int applied, int skipped, int itemErrors, int entryErrors,
                             int clamped, long elapsedMs) {
        /** 是否一切正常（没有解析错误、没有跳过、没有裁剪）。 */
        public boolean clean() {
            return itemErrors == 0 && entryErrors == 0 && skipped == 0 && clamped == 0;
        }
    }

    /** tag 条目：一个标签 + 一套数值（查询时按顺序懒匹配）。 */
    public record TagBinding(TagKey<Item> tag, DecompositionProfile profile) {
    }

    /** 表快照：精确 item 表 + tag 列表（全部不可变）。 */
    public record State(Map<Item, DecompositionProfile> byItem, List<TagBinding> byTag) {
        /**
         * <b>显式规范构造器</b>（不用紧凑构造器）：紧凑构造器里只能给<b>参数</b>赋值、
         * 不能写 {@code this.byItem = ...}，而这里需要把入参换成不可变快照，
         * 因此必须写显式构造器形式。
         * <p>用 {@code Collections.unmodifiableMap(new LinkedHashMap<>(...))} 而不是 {@link Map#copyOf}：
         * 前者<b>保持迭代顺序</b>（精确 item 的覆盖顺序在调试 / 自检里有意义），且类型推断稳定。</p>
         */
        public State(final Map<Item, DecompositionProfile> byItem, final List<TagBinding> byTag) {
            this.byItem = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byItem));
            this.byTag = java.util.Collections.unmodifiableList(new ArrayList<>(byTag));
        }
    }

    // ==================== 查询 ====================

    /**
     * 查这个物品的降解数值（{@code null} = 表里没有 = 无法降解）。
     * <p>先查精确 item（O(1)），再按文件顺序查 tag（tag 条目通常个位数）。</p>
     */
    @Nullable
    public static DecompositionProfile profile(@Nullable final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final State snapshot = state;
        final DecompositionProfile exact = snapshot.byItem().get(stack.getItem());
        if (exact != null) {
            return exact;
        }
        for (final TagBinding binding : snapshot.byTag()) {
            if (stack.is(binding.tag())) {
                return binding.profile();
            }
        }
        return null;
    }

    /**
     * 这个物品能不能进槽 / 能不能降解。
     * <p>表未被重载过（专用服务器的客户端）⇒ 放行 true（服务端权威，见类注释）。</p>
     */
    public static boolean isDecomposable(@Nullable final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!applied) {
            return true;
        }
        return profile(stack) != null;
    }

    /** 精确 item 条目数（调试 / 自检）。 */
    public static int itemCount() {
        return state.byItem().size();
    }

    /** tag 条目数（调试 / 自检）。 */
    public static int tagCount() {
        return state.byTag().size();
    }

    /** 重载版本号（每完成一次重载 +1，方块实体据此重算无效槽位数）。 */
    public static long revision() {
        return revision;
    }

    /** 最近一次重载汇总（方块实体首次 tick 时也靠它打一条日志）。 */
    public static LoadReport lastReport() {
        return lastReport;
    }

    /** 表里显式包含的物品（自检用；不含 tag 展开的结果）。 */
    public static List<Item> explicitItems() {
        return List.copyOf(state.byItem().keySet());
    }

    /** tag 条目（自检用）。 */
    public static List<TagBinding> tagBindings() {
        return state.byTag();
    }

    /**
     * JEI 展示用：把当前表<b>展开</b>成「唯一键 + 适用物品 id 列表 + 数值 + 副产物」的扁平列表。
     *
     * <h2>为什么需要这一步（而不是让 JEI 直接读 {@link State}）</h2>
     * <p>JEI 的分类页要的是「一条配方 = 一组可显示物品 + 一行行文字」，而 {@link State} 里存的是
     * {@link Item} 与 {@link TagKey}。tag 条目在 {@link #profile(ItemStack)} 查询时是<b>懒匹配</b>的（见类注释），
     * 所以只有「真正要展示 / 真要过网」的这一刻才把它展开成具体物品。</p>
     *
     * <h2>展开口径</h2>
     * <ul>
     *     <li>精确物品条目：{@code items} 只放它自己；</li>
     *     <li>tag 条目：用 {@link net.minecraft.core.Registry#getTagOrEmpty} 现查<b>整组</b>
     *     （客户端进服时原版会同步全部物品标签，因此专用服务器上也能展开）；tag 不存在 / 一个物品
     *     都没有 ⇒ {@code items} 为空列表，条目<b>仍然保留</b>（展示侧会跳过它，但数据不丢，方便排查
     *     「数据包写了但客户端看不到」）；</li>
     *     <li>顺序：先精确物品（保持数据包写入顺序），再 tag（保持文件顺序）；</li>
     *     <li>副产物里引用了「注册表里已不存在」的物品 ⇒ 只跳过那一项（与
     *     {@link #parseResult} 的容错口径一致）。</li>
     * </ul>
     *
     * <p><b>只读</b>：本方法不写任何字段，可在服务端主线程与客户端主线程任意调用。</p>
     */
    public static List<DecompositionDisplayEntry> displayEntries() {
        final State snapshot = state;
        final List<DecompositionDisplayEntry> entries =
            new ArrayList<>(snapshot.byItem().size() + snapshot.byTag().size());
        for (final Map.Entry<Item, DecompositionProfile> entry : snapshot.byItem().entrySet()) {
            final String id = registryId(entry.getKey());
            if (id == null) {
                continue; // 物品已不在注册表（理论上不可达）：没有 id 就没法在 JEI / 网络上表示
            }
            entries.add(toDisplayEntry(id, List.of(id), entry.getValue()));
        }
        for (final TagBinding binding : snapshot.byTag()) {
            final List<String> ids = new ArrayList<>();
            for (final Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(binding.tag())) {
                final String id = registryId(holder.value());
                if (id != null) {
                    ids.add(id);
                }
            }
            entries.add(toDisplayEntry("#" + binding.tag().location(), ids, binding.profile()));
        }
        return List.copyOf(entries);
    }

    /** 物品的注册 id（{@code null} = 不在注册表里）。 */
    @Nullable
    private static String registryId(final Item item) {
        final ResourceLocation id = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    /** 一条 profile → 一条展示条目（副产物逐项换 id，解析不出来的只跳过那一项）。 */
    private static DecompositionDisplayEntry toDisplayEntry(final String key, final List<String> items,
                                                           final DecompositionProfile profile) {
        final List<DecompositionDisplayEntry.Result> results = new ArrayList<>(profile.results().size());
        for (final DecompositionProfile.DecompositionResult result : profile.results()) {
            final String id = registryId(result.item());
            if (id != null) {
                results.add(new DecompositionDisplayEntry.Result(id, result.count(), result.chance()));
            }
        }
        return new DecompositionDisplayEntry(key, items, profile.time(), profile.energy(), results);
    }

    // ==================== 重载（唯一写路径） ====================

    /**
     * 用一次数据包重载的结果<b>整体替换</b>表。
     * <p>只在服务端数据重载主线程上被调用（{@link DecompositionDataLoader#apply}）。
     * 任何单条解析失败都不会抛出 —— 见类注释的容错清单。</p>
     */
    public static synchronized void apply(final Map<ResourceLocation, JsonElement> files, final Gson gson) {
        final long start = System.currentTimeMillis();
        final LinkedHashMap<Item, DecompositionProfile> byItem = new LinkedHashMap<>();
        final List<TagBinding> byTag = new ArrayList<>();
        final List<ResourceLocation> ordered = new ArrayList<>(files.keySet());
        // 内置表最先，其余命名空间按字母序（见类注释的叠加顺序）
        ordered.sort(Comparator
            .comparingInt((ResourceLocation rl) -> BUILTIN_NAMESPACE.equals(rl.getNamespace()) ? 0 : 1)
            .thenComparing(ResourceLocation::getNamespace)
            .thenComparing(ResourceLocation::getPath));

        int appliedFiles = 0;
        int skippedFiles = 0;
        int itemErrors = 0;
        int entryErrors = 0;
        int clampedValues = 0;
        for (final ResourceLocation fileId : ordered) {
            final JsonElement root = files.get(fileId);
            if (root == null || !root.isJsonObject()) {
                entryErrors++;
                LOGGER.warn("[rs_create_compat] 降解表 {} 的根节点不是 JSON 对象，已跳过", fileId);
                continue;
            }
            final JsonObject object = root.getAsJsonObject();
            // ① mod_loaded 门：没装这个 mod 就整份跳过（缺失的 id/tag 因此不会变成报错噪音）
            final String modLoaded = optionalString(object, "mod_loaded");
            if (modLoaded != null && !modLoaded.isBlank() && !ModList.get().isLoaded(modLoaded)) {
                skippedFiles++;
                continue;
            }
            // ② replace：清空此前累积的一切（含内置表）
            if (optionalBoolean(object, "replace", false)) {
                byItem.clear();
                byTag.clear();
            }
            // ③ remove：先删条目，再追加（顺序固定，避免「同一文件里删了又加」的歧义）
            itemErrors += applyRemove(object, byItem, byTag);
            // ④ entries：逐条解析，任何一条坏掉都只跳过它自己
            final JsonElement entriesElement = object.get("entries");
            if (entriesElement == null || !entriesElement.isJsonArray()) {
                // replace-only / remove-only 的文件是合法的
                if (entriesElement != null) {
                    entryErrors++;
                    LOGGER.warn("[rs_create_compat] 降解表 {} 的 entries 不是数组，已忽略", fileId);
                    continue;
                }
                appliedFiles++;
                continue;
            }
            final JsonArray entries = entriesElement.getAsJsonArray();
            for (final JsonElement element : entries) {
                final Parsed parsed = parseEntry(element);
                if (parsed == null) {
                    entryErrors++;
                    continue;
                }
                // 副产物里「只有这一项引用了不存在的 item」不算整条作废：条目照留，只少一个产物。
                itemErrors += parsed.itemErrors();
                clampedValues += parsed.clamped();
                if (parsed.item() != null) {
                    byItem.put(parsed.item(), parsed.profile());
                } else {
                    byTag.add(new TagBinding(parsed.tag(), parsed.profile()));
                }
            }
            appliedFiles++;
        }

        state = new State(byItem, byTag);
        applied = true;
        revision++;
        final long elapsed = System.currentTimeMillis() - start;
        lastReport = new LoadReport(files.size(), appliedFiles, skippedFiles, itemErrors, entryErrors,
            clampedValues, elapsed);
        // 只打一条汇总：条目数、tag 数、错误数、裁剪次数、耗时（逐条 WARN 只给必须改的数据包问题）
        LOGGER.info("[rs_create_compat] 降解表已重载：files={} applied={} skipped={} itemErrors={} entryErrors={} "
                + "clamped={} items={} tags={} 用时={}ms",
            files.size(), appliedFiles, skippedFiles, itemErrors, entryErrors, clampedValues,
            byItem.size(), byTag.size(), elapsed);
    }

    /** {@code remove} 数组：{@code "minecraft:oak_log"} 删精确 item，{@code "#minecraft:planks"} 删 tag 条目。 */
    private static int applyRemove(final JsonObject object, final Map<Item, DecompositionProfile> byItem,
                                   final List<TagBinding> byTag) {
        final JsonElement removeElement = object.get("remove");
        if (removeElement == null || !removeElement.isJsonArray()) {
            return 0;
        }
        int errors = 0;
        for (final JsonElement element : removeElement.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                errors++;
                continue;
            }
            final String raw = element.getAsString().trim();
            if (raw.startsWith("#")) {
                final ResourceLocation tagId = ResourceLocation.tryParse(raw.substring(1).trim());
                if (tagId == null) {
                    errors++;
                    continue;
                }
                final TagKey<Item> key = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
                byTag.removeIf(binding -> binding.tag().equals(key));
            } else {
                final ResourceLocation id = ResourceLocation.tryParse(raw);
                final Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null) {
                    errors++;
                    continue;
                }
                byItem.remove(item);
            }
        }
        return errors;
    }

    /**
     * 解析一条 entries：{@code item} / {@code tag} 二选一；{@code time} / {@code energy} 必需
     * （<b>缺失</b> = 该条作废）；{@code results} / {@code result} 可选、二选一。
     *
     * <p>返回 {@code null} = <b>整条</b>作废（调用方计 {@code entryErrors}）。返回非 null 时，
     * 「副产物里某一项的 item 不存在」已经计在 {@link Parsed#itemErrors()}、「值越界被夹回」
     * 的数量在 {@link Parsed#clamped()} —— 这两种都<b>不作废</b>整条，条目照常生效。</p>
     */
    @Nullable
    private static Parsed parseEntry(final JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        final String itemId = optionalString(object, "item");
        final String tagId = optionalString(object, "tag");
        if ((itemId == null || itemId.isBlank()) && (tagId == null || tagId.isBlank())) {
            return null;
        }
        // 用哨兵区分「没写」（作废）与「写了但越界」（裁剪后照常生效，见 DecompositionProfile 类注释）
        final int time = optionalInt(object, "time", MISSING_INT);
        final int energy = optionalInt(object, "energy", MISSING_INT);
        if (time == MISSING_INT || energy == MISSING_INT) {
            return null;
        }
        int clamped = 0;
        if (time < DecompositionProfile.MIN_TIME || time > DecompositionProfile.MAX_TIME) {
            clamped++;
        }
        if (energy < 1 || energy > DecompositionProfile.MAX_ENERGY) {
            clamped++;
        }
        // 副产物：results（数组）/ result（单对象语法糖）二选一；同时出现 = 结构歧义 ⇒ 整条作废
        final boolean hasResults = hasNonNull(object, "results");
        final boolean hasResult = hasNonNull(object, "result");
        if (hasResults && hasResult) {
            LOGGER.warn("[rs_create_compat] 降解表条目同时写了 results 与 result（已跳过该条）");
            return null;
        }
        final List<DecompositionProfile.DecompositionResult> results = new ArrayList<>();
        int resultItemErrors = 0;
        if (hasResults) {
            final JsonElement resultsElement = object.get("results");
            if (!resultsElement.isJsonArray()) {
                LOGGER.warn("[rs_create_compat] 降解表条目的 results 不是数组（已跳过该条）");
                return null;
            }
            for (final JsonElement resultElement : resultsElement.getAsJsonArray()) {
                final ParsedResult parsedResult = parseResult(resultElement);
                if (parsedResult == null) {
                    // 「只少一个产物」：坏的那项跳过，条目与其它产物继续
                    resultItemErrors++;
                    continue;
                }
                clamped += parsedResult.clamped();
                results.add(parsedResult.result());
            }
        } else if (hasResult) {
            final JsonElement resultElement = object.get("result");
            if (!resultElement.isJsonObject()) {
                LOGGER.warn("[rs_create_compat] 降解表条目的 result 不是对象（已跳过该条）");
                return null;
            }
            final ParsedResult parsedResult = parseResult(resultElement);
            if (parsedResult == null) {
                resultItemErrors++;
            } else {
                clamped += parsedResult.clamped();
                results.add(parsedResult.result());
            }
        }
        // 越界值由构造器夹回合法区间（单一落点），这里只统计「夹过几个」
        final DecompositionProfile profile = new DecompositionProfile(time, energy, results);
        if (itemId != null && !itemId.isBlank()) {
            final ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
            final Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null) {
                LOGGER.warn("[rs_create_compat] 降解表引用了不存在的物品 {}（已跳过该条）", itemId);
                return null;
            }
            return new Parsed(item, null, profile, resultItemErrors, clamped);
        }
        final ResourceLocation id = ResourceLocation.tryParse(tagId.trim());
        if (id == null) {
            LOGGER.warn("[rs_create_compat] 降解表引用了非法的 tag {}（已跳过该条）", tagId);
            return null;
        }
        // tag 不在这里解析（懒匹配，见类注释）
        return new Parsed(null, TagKey.create(net.minecraft.core.registries.Registries.ITEM, id), profile,
            resultItemErrors, clamped);
    }

    /**
     * 解析一条副产物：{@code item} 必填、{@code count} 默认 1、{@code chance} 默认 1.0。
     *
     * <p>返回 {@code null} = <b>这一项</b>作废（item 缺失 / 不是对象 / id 不存在），调用方只跳过它，
     * 不牵连整条；返回非 null 时 {@code clamped} 是 count/chance 里越界被夹的个数。</p>
     */
    @Nullable
    private static ParsedResult parseResult(@Nullable final JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        final String itemId = optionalString(object, "item");
        if (itemId == null || itemId.isBlank()) {
            LOGGER.warn("[rs_create_compat] 降解表副产物缺少 item（已跳过该项）");
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
        final Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            LOGGER.warn("[rs_create_compat] 降解表副产物引用了不存在的物品 {}（已跳过该项）", itemId);
            return null;
        }
        int clamped = 0;
        final int count = optionalInt(object, "count", DecompositionProfile.MIN_COUNT);
        if (count < DecompositionProfile.MIN_COUNT || count > DecompositionProfile.MAX_COUNT) {
            clamped++;
        }
        final float chance = optionalFloat(object, "chance", DecompositionProfile.MAX_CHANCE);
        if (Float.isNaN(chance) || chance < DecompositionProfile.MIN_CHANCE
            || chance > DecompositionProfile.MAX_CHANCE) {
            clamped++;
        }
        // 裁剪交给 DecompositionResult 的构造器（单一落点），这里只数越界值个数
        return new ParsedResult(
            new DecompositionProfile.DecompositionResult(item, count, chance), clamped);
    }

    /** 解析结果：item 与 tag 恰好一个非空；后两个是「坏副产物项」与「越界裁剪」计数。 */
    private record Parsed(@Nullable Item item, @Nullable TagKey<Item> tag, DecompositionProfile profile,
                          int itemErrors, int clamped) {
    }

    /** 一条副产物的解析结果 + 它自己贡献的裁剪次数。 */
    private record ParsedResult(DecompositionProfile.DecompositionResult result, int clamped) {
    }

    @Nullable
    private static String optionalString(final JsonObject object, final String key) {
        final JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    /** 键存在且不是 JSON {@code null}（{@code "results": null} 按「没写」处理，宽容手写数据包）。 */
    private static boolean hasNonNull(final JsonObject object, final String key) {
        final JsonElement element = object.get(key);
        return element != null && !element.isJsonNull();
    }

    private static boolean optionalBoolean(final JsonObject object, final String key, final boolean fallback) {
        final JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }

    private static int optionalInt(final JsonObject object, final String key, final int fallback) {
        final JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    private static float optionalFloat(final JsonObject object, final String key, final float fallback) {
        final JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsFloat();
    }
}
