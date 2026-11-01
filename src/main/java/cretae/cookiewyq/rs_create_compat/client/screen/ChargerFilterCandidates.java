package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.PinyinSearch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 充电过滤界面用的<b>「能充电的物品类型」候选池</b>（纯客户端，懒构建一次并缓存）。
 *
 * <h2>为什么需要它（用户原话的落点）</h2>
 * <p>用户要求过滤界面「会列出……所有能充电的……可以阻止或单独允许这一些对象进行充电」。
 * 服务端只存「被勾选的物品 id 集合」（{@code ChargingFilter} 的 {@code ResourceLocation} 集合），
 * <b>不存全量候选</b> —— 全量候选 = 「所有注册物品里带 FE 容器、且能被充进电的那些」，
 * 这份数据在客户端与服端完全一致（都来自同一个 {@code BuiltInRegistries.ITEM} + 能力系统），
 * 因此<b>不需要任何额外网络包</b>：客户端自己扫一遍即可。</p>
 *
 * <h2>判据（与「能不能充进去」逐字同源）</h2>
 * <p>对每个注册物品造一个 {@code new ItemStack(item)}，取
 * {@code Capabilities.EnergyStorage.ITEM}（NeoForge 能量容器能力），要求
 * <b>{@code canReceive()} 且 {@code getMaxEnergyStored() > 0}</b>：</p>
 * <ul>
 *     <li>{@code canReceive()} 为假 ⇒ 该物品<b>只出不进</b>（例如已经是满电的不可充电池、
 *     或某些只放电的电源），把它列进「掉落物黑名单」是误导；</li>
 *     <li>{@code getMaxEnergyStored() == 0} ⇒ 没有缓冲，永远充不进任何 FE（能力存在只是占位），
 *     同样不该出现；</li>
 *     <li>只判断<b>类型</b>不判断 NBT / 当前电量：用户要的是「阻止这一<类>东西充电」，
 *     与手上那一件恰好多少电无关。</li>
 * </ul>
 *
 * <h2>第 125 轮：多颜色变体的「合并显示」（用户原话的落点）</h2>
 * <p>用户原话：「<i>有线模组比如说精致存储里面它会有很多颜色嘛……这一些东西你就统一的合并显示。
 * 什么叫合并显示呢？就是有个展开，你就搞一个总的比如说什么控制器，然后展开之后呢，
 * 就不同的颜色把它分在一起放在一起，否则的话太难看了。</i>」</p>
 *
 * <h3>为什么必须按「注册名」而不是「显示名」「NBT」分组</h3>
 * <p>多颜色变体在现代模组里几乎是统一约定：<b>非默认色 = 颜色词 + {@code _} + 基础路径</b>
 * （Refined Storage 2.0.9 的 {@code ColorMap#getId} 就是这么拼的：
 * {@code color.getName() + "_" + baseId.getPath()}，默认色直接用基础 id）。这条约定来自
 * RS 自己的源码（已读 {@code common/content/ColorMap.java:34-39}），因此按注册名归一化
 * 比按显示名（各语言不同）或按 NBT（物品根本没有颜色 NBT）都稳。举实测例子：
 * RS 2.0.9 里暴露 {@code EnergyStorage.ITEM} 的是
 * {@code Items#getControllers()}（16 色普通控制器）与 {@code getCreativeControllers()}
 * （16 色创造控制器）等，所以物品两页确实会被「米色控制器 / 橙色控制器 / …」刷满 ——
 * 这正是用户截图抱怨的那一屏。</p>
 *
 * <h3>16 色词表 + 长词优先</h3>
 * <p>颜色词表固定为原版 {@code DyeColor} 的 16 个名字（{@link #COLOR_ORDER}，白→黑）。
 * 判据是「路径以 {@code 颜色 + '_'} 开头且后面还有内容」，<b>不是</b> {@code contains}：</p>
 * <ul>
 *     <li>{@code minecraft:redstone} 含 {@code red}，但 {@code red} 后面是 {@code s} 不是
 *     {@code _} ⇒ 不算红色变体（见自检里的反例）；</li>
 *     <li>匹配顺序按<b>词长降序</b>（{@link #COLOR_PREFIXES_LONGEST_FIRST}）：
 *     {@code light_blue} / {@code light_gray} 先于 {@code blue} / {@code gray} 尝试，
 *     绝不出现「长词被短词先吞」的经典 bug。</li>
 * </ul>
 *
 * <h3>「同家族 ≥ 3 个成员」才折叠</h3>
 * <p>阈值 {@link #MIN_FAMILY_SIZE} = 3，理由见该常量的注释：2 个成员是「正常两个条目」的
 * 概率远高于「被颜色刷屏」，藏进折叠组反而多一次点击、还容易让人以为丢了东西。</p>
 *
 * <h3>排序（组内 / 列表）</h3>
 * <ul>
 *     <li><b>组内</b>：严格固定颜色序（{@link #COLOR_ORDER}，白→黑），<b>绝不按字母序</b> ——
 *     字母序会把 {@code black} 排到 {@code white} 前面，与玩家在原版染色界面里看到的顺序相反；
 *     没有颜色前缀的默认变体（例如 RS 的 {@code refinedstorage:controller}）排<b>最前</b>，
 *     因为它就是「这个物品本身」；同色再按注册 id 兜底，保证任何客户端顺序都一致。</li>
 *     <li><b>列表</b>：组头与普通条目用<b>同一套</b>比较器混排，组头的排序键是「家族基础名」
 *     （{@link Family#sortName()}）而不是某个成员名 —— 这样组头恰好出现在「这个家族只有一个
 *     条目时它会出现的位置」，不会出现「组头全飘到末尾」这种割裂感。排序理由写在
 *     {@code ChargerFilterScreen#rebuildItemRows} 里。</li>
 * </ul>
 *
 * <h2>性能（为什么必须懒构建 + 缓存）</h2>
 * <p>每次打开界面都遍历整张物品表（整合包常见 2000~5000 个物品）并逐个取能力，是毫秒级但
 * 毫无意义的重复工作；候选集合只随「模组列表 / 数据包」变化，进程内不会变 —— 因此
 * <b>懒构建一次、进程内静态缓存</b>。构建只发生在<b>客户端主线程</b>第一次请求时
 * （{@code Robot}/{@code ITEM} 注册表此时早已冻结，不存在并发注册）。</p>
 * <p><b>家族索引也在同一次构建里建好</b>（{@code FAMILY_KEYS} / {@code FAMILIES}）：
 * 界面每次勾选 / 打字都会重建行模型，若那时才按 {@code familyKey} 现算，就是
 * 「每个命中条目 × 全家候选」的 O(N²) 字符串比较；建一次索引后每次只是 map 命中。</p>
 * <p><b>实测耗时：未实测</b>（本机无法进游戏）。上限口径：一次 O(物品数) 的能力查询 +
 * 一次按显示名排序 + 一次家族分桶；搜索结果硬上限 {@link #MAX_SEARCH_RESULTS} 条，
 * 因此界面侧永远不会一次画几千行。</p>
 */
public final class ChargerFilterCandidates {
    /**
     * {@link #search(String)} 的硬上限（条）。
     * <p>为什么要有：搜索框只输入一个字母（例如 {@code e}）时几乎能命中全部候选，
     * 而列表本身是分页/滚动的 —— 512 条已经远超任何「人能翻得完」的量，
     * 同时把「一帧内要构造多少行」这件事从「取决于整合包」变成常数。</p>
     */
    public static final int MAX_SEARCH_RESULTS = 512;

    /**
     * 家族<b>至少这么多成员</b>才折叠（第 125 轮）。
     *
     * <p><b>为什么是 3、而不是 2 或 4</b>：</p>
     * <ol>
     *     <li><b>2 不折</b>：用户抱怨的是「一屏被同一物品的十几种颜色刷满」；只有两个成员时，
     *     平铺两行比「一行组头 + 一次点击」更省事，而且两个条目里很常见一个是「正常物品」、
     *     另一个是「同名的创造/充能版本」，把它们藏起来等于藏了一个正常条目 ——
     *     这正是要避免的「藏正常条目」；</li>
     *     <li><b>3 是开始「显著省地方」的最小值</b>：收起后 3 个成员的家族只占 1 行（平铺 3 行），
     *     省 2 行；而 2 个成员只省 1 行、却要多一次点击；</li>
     *     <li><b>4 太大</b>：会把「3 色小家族」继续留在列表里刷屏，用户的口径是「统一合并」，
     *     阈值抬高只是把问题推给下一个模组。</li>
     * </ol>
     * <p>这个常量只影响<b>展示层</b>：勾选集合语义、网络包、服务端判定一个字都没改
     * （组操作只是批量增删同样的物品 id）。</p>
     */
    public static final int MIN_FAMILY_SIZE = 3;

    /**
     * 固定颜色序（第 125 轮）：<b>原版 16 色，白→黑</b>，与 {@code DyeColor#values()} 同序。
     *
     * <p><b>为什么不用字母序</b>：字母序（black → white）与原版染色界面 / 物品栏里的
     * 颜色排列相反，玩家找一个颜色要重新建立心理映射；这里直接沿用原版顺序，
     * 且颜色词表本身是<b>唯一</b>顺序来源（组内排序、`colorOf` 词表都读它）。</p>
     */
    public static final List<String> COLOR_ORDER = List.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    /**
     * 颜色词表按<b>词长降序</b>（同长按字母序固定顺序，保证确定性）。
     * <p>为什么：{@code light_blue} / {@code light_gray} 必须先于 {@code blue} / {@code gray}
     * 尝试。当前实现用「前缀 + {@code '_'}」已经天然不会误吞，但**顺序依赖是隐式的**：
     * 只要以后有人把判据改成 {@code contains} 或后缀匹配，短词先试就会立刻出错，
     * 因此这里显式按长度降序，把这条约束钉在数据结构上（自检里有反例）。</p>
     */
    private static final List<String> COLOR_PREFIXES_LONGEST_FIRST = longestFirstColorPrefixes();

    /** 本类语言键前缀（与 {@code ChargerFilterScreen} 同一段）。 */
    private static final String LANG = "gui.rs_create_compat.charger_filter.";

    /** 候选池（懒构建；进程内只构建一次）。 */
    @Nullable
    private static List<Item> cachedAll;
    /** 物品 → 图标栈（保存组件为空、数量 1 的栈；避免每帧 new）。 */
    private static final Map<Item, ItemStack> ICONS = new LinkedHashMap<>();
    /** 物品 → 显示名（构建排序时顺便缓存，搜索时不再反复取悬浮名）。 */
    private static final Map<Item, String> NAMES = new LinkedHashMap<>();
    /** 物品 → 注册 id（搜索后缀与 tooltip 共用）。 */
    private static final Map<Item, ResourceLocation> IDS = new LinkedHashMap<>();
    /** 物品 → 家族键（第 125 轮；构建时一次算好，界面重建行模型时只做 map 查询）。 */
    private static final Map<Item, ResourceLocation> FAMILY_KEYS = new LinkedHashMap<>();
    /** 家族键 → 折叠家族（第 125 轮；<b>只装成员数 ≥ {@link #MIN_FAMILY_SIZE} 的家族</b>）。 */
    private static final Map<ResourceLocation, Family> FAMILIES = new LinkedHashMap<>();
    /** 最近一次构建出的候选条数（只用于回报 / 排障，不参与任何判定）。 */
    private static int lastBuildCount;

    private ChargerFilterCandidates() {
    }

    /**
     * 一个「颜色家族」（第 125 轮）：
     * <b>同一物品的多颜色变体合集</b>（例如 RS 的 16 个控制器 / 16 个创造控制器）。
     *
     * @param key         家族键（= 基础注册名：{@code refinedstorage:white_controller} →
     *                    {@code refinedstorage:controller}；无颜色前缀的条目就是它自己的 id）
     * @param members     家族成员（<b>已按固定颜色序排好</b>，默认变体在最前；只读）
     * @param icon        组头行显示的代表图标（基础条目的图标栈；<b>渲染路径直接用，不查注册表</b>）
     * @param displayName 组头文案：「精致存储控制器（16 色）」（可翻译；见 {@code group.mod} /
     *                    {@code group.header} 两个语言键）
     * @param sortName    列表混排用的排序键（基础名，<b>不含</b>「（N 色）」后缀与小写化后的名字）
     */
    public record Family(ResourceLocation key, List<Item> members, ItemStack icon,
                         Component displayName, String sortName) {
        public Family {
            members = List.copyOf(members);
        }

        /** 本家族在候选池里的成员数（界面上的「N 色」就是它）。 */
        public int colorCount() {
            return members.size();
        }
    }

    /** 全部候选（按显示名排序；只读、不可变）。第一次调用时构建并缓存。 */
    public static List<Item> all() {
        if (cachedAll != null) {
            return cachedAll;
        }
        final List<Item> found = new ArrayList<>();
        for (final Item item : BuiltInRegistries.ITEM) {
            if (item == null) {
                continue;
            }
            final ItemStack stack = new ItemStack(item);
            final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null || !storage.canReceive() || storage.getMaxEnergyStored() <= 0) {
                continue;
            }
            found.add(item);
            ICONS.put(item, stack);
            NAMES.put(item, stack.getHoverName().getString());
            final ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            IDS.put(item, id);
            // 第 125 轮：家族键一次算好（这里还在构建期，不能调 familyKey() —— 那会递归回 all()）
            if (id != null) {
                FAMILY_KEYS.put(item, computeFamilyKey(id));
            }
        }
        // 稳定排序：显示名（不区分大小写）优先，同名再按注册 id —— 同一份注册表在任何客户端上
        // 都得到同一个顺序，玩家不会看到「两次打开顺序不一样」。
        found.sort(Comparator
            .comparing((Item item) -> NAMES.getOrDefault(item, "").toLowerCase(Locale.ROOT))
            .thenComparing(item -> {
                final ResourceLocation id = IDS.get(item);
                return id == null ? "" : id.toString();
            }));
        buildFamilies(found);
        lastBuildCount = found.size();
        cachedAll = List.copyOf(found);
        return cachedAll;
    }

    /**
     * 按「显示名 或 注册 id 的<b>子串</b>」过滤，最多 {@link #MAX_SEARCH_RESULTS} 条。
     * <p>空查询 ⇒ 返回 {@link #all()} 本身（不复制：列表只读）。</p>
     * <p><b>第 110 轮：显示名这一路走 {@link PinyinSearch#contains}</b> —— 玩家装了
     * 「通用拼音搜索」（Just Enough Characters）时，敲 {@code kongzhiqi} / {@code kzq} 也能命中
     * 「控制器」；没装模组时退化为原来的不区分大小写子串匹配（行为完全不变）。
     * 注册 id 这一路<b>保持纯子串</b>（id 是 ASCII，没有拼音语义）。</p>
     * <p>为什么不支持 {@code @mod} / {@code #tag} 这类 RS 网格语法：本轮需求是「按名字找一件东西」，
     * 多实现一种语法就多一处与终端网格不一致的行为；注册 id 子串已经能覆盖「按模组前缀找」的用法
     * （{@code mekanism:}）。</p>
     */
    public static List<Item> search(final String query) {
        final List<Item> all = all();
        final String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) {
            return all;
        }
        final String needleLower = needle.toLowerCase(Locale.ROOT);
        final List<Item> out = new ArrayList<>();
        for (final Item item : all) {
            final String name = NAMES.getOrDefault(item, "");
            final ResourceLocation id = IDS.get(item);
            if (PinyinSearch.contains(name, needle)
                || (id != null && id.toString().toLowerCase(Locale.ROOT).contains(needleLower))) {
                out.add(item);
                if (out.size() >= MAX_SEARCH_RESULTS) {
                    break;
                }
            }
        }
        return out;
    }

    /** 该物品的图标栈（16×16 渲染用；构建后才有值，构建前调用会触发构建）。 */
    public static ItemStack icon(final Item item) {
        all();
        return ICONS.getOrDefault(item, new ItemStack(item));
    }

    /** 该物品的显示名（构建后缓存；构建前调用会触发构建）。 */
    public static String nameOf(final Item item) {
        all();
        return NAMES.getOrDefault(item, new ItemStack(item).getHoverName().getString());
    }

    /** 该物品的注册 id（构建后缓存；构建前调用会触发构建）。 */
    @Nullable
    public static ResourceLocation idOf(final Item item) {
        all();
        return IDS.get(item);
    }

    // ==================== 第 125 轮：颜色家族（分组模型） ====================

    /**
     * 该物品的<b>颜色词</b>（16 色之一）；不是任何颜色的变体 ⇒ {@code null}。
     *
     * <p>判据（<b>只有这一处实现</b>）：注册名路径以 {@code 颜色 + '_'} 开头且后面还有内容。
     * 颜色词按 {@link #COLOR_PREFIXES_LONGEST_FIRST} <b>长词优先</b>尝试。
     * 例：{@code refinedstorage:light_blue_controller} → {@code light_blue}（不是 {@code blue}）；
     * {@code minecraft:redstone} → {@code null}（{@code red} 后面是 {@code s}，不是 {@code _}）。</p>
     */
    @Nullable
    public static String colorOf(final Item item) {
        all();
        final ResourceLocation id = IDS.get(item);
        return id == null ? null : colorOfId(id);
    }

    /**
     * 该物品的<b>家族键</b>：有色变体归一化成基础注册名
     * （{@code refinedstorage:red_cable} → {@code refinedstorage:cable}）；
     * <b>没有颜色前缀的条目返回它自己的注册 id</b>（因此它自然成为「基础条目」那一族的键）。
     *
     * <p>注意：{@code ns:cable} 与 {@code ns:red_cable} / {@code ns:blue_cable} 的家族键相同，
     * 所以「基础条目 + 两个颜色」= 3 个成员，会按 {@link #MIN_FAMILY_SIZE} 折起来 ——
     * 基础条目与它的颜色变体本来就是一个家族，这是期望行为。</p>
     */
    public static ResourceLocation familyKey(final Item item) {
        all();
        final ResourceLocation cached = FAMILY_KEYS.get(item);
        if (cached != null) {
            return cached;
        }
        // 非候选物品（例如方块页的方块物品）：现算一次，不进缓存
        return computeFamilyKey(BuiltInRegistries.ITEM.getKey(item));
    }

    /**
     * 该物品所属的<b>折叠家族</b>；不属于任何折叠家族（家族成员 &lt; {@link #MIN_FAMILY_SIZE}）
     * ⇒ {@code null}（调用方直接把它当普通条目画）。
     */
    @Nullable
    public static Family familyOf(final Item item) {
        all();
        final ResourceLocation key = familyKey(item);
        return key == null ? null : FAMILIES.get(key);
    }

    /**
     * 家族展示名（「精致存储线缆（16 色）」）；不属于折叠家族 ⇒ {@code null}。
     *
     * <p>为什么带上模组名：同一件东西在整合包里可能有两个模组各加一份（「控制器」重名很常见），
     * 组头是唯一的身份提示，模组名让玩家一眼分清来源；原版命名空间不加前缀
     * （「Minecraft 石头」这种话没人这么说）。模组名取加载器的显示名
     * （{@link ModList#get()} + {@code getModContainerById(...).getModInfo().getDisplayName()}），
     * 拿不到（自定义命名空间 / 加载器未就绪）就退回「基础名」，不硬编码任何模组名。</p>
     */
    @Nullable
    public static Component familyDisplayName(final Item item) {
        final Family family = familyOf(item);
        return family == null ? null : family.displayName();
    }

    /** 固定颜色序里该颜色的下标（组内排序用；无颜色前缀的默认变体恒为 -1 = 最前）。 */
    private static int colorOrderIndex(@Nullable final String color) {
        if (color == null) {
            return -1;
        }
        final int index = COLOR_ORDER.indexOf(color);
        return index < 0 ? COLOR_ORDER.size() : index;
    }

    /** 构建家族索引：按家族键分桶，只把「成员数 ≥ {@link #MIN_FAMILY_SIZE}」的家族收进 {@link #FAMILIES}。 */
    private static void buildFamilies(final List<Item> sortedCandidates) {
        final Map<ResourceLocation, List<Item>> buckets = new LinkedHashMap<>();
        for (final Item item : sortedCandidates) {
            final ResourceLocation key = FAMILY_KEYS.get(item);
            if (key == null) {
                continue;
            }
            buckets.computeIfAbsent(key, unused -> new ArrayList<>()).add(item);
        }
        for (final Map.Entry<ResourceLocation, List<Item>> entry : buckets.entrySet()) {
            if (entry.getValue().size() < MIN_FAMILY_SIZE) {
                continue; // 2 个成员不折：避免把正常条目藏起来（见 MIN_FAMILY_SIZE 注释）
            }
            final List<Item> members = entry.getValue();
            // 组内严格固定颜色序（白→黑）＋ 默认变体最前；同色再按注册 id 兜底，绝不按字母序
            members.sort(Comparator
                .comparingInt((Item item) -> colorOrderIndex(colorOfId(IDS.get(item))))
                .thenComparing(item -> {
                    final ResourceLocation id = IDS.get(item);
                    return id == null ? "" : id.toString();
                }));
            FAMILIES.put(entry.getKey(), createFamily(entry.getKey(), members));
        }
    }

    /**
     * 造一个家族（名称 / 图标 / 排序键）。
     *
     * <p><b>基础条目可能存在但不在候选池里</b>（例如某家族里只有颜色变体有 FE 能力），
     * 因此名称与图标按「注册表里的基础条目 → 家族第一个成员的显示名去掉颜色词」两级兜底；
     * 第二级只在极少数自定义模组上生效，且<b>不查任何额外注册表</b>（名称与图标早已缓存）。</p>
     */
    private static Family createFamily(final ResourceLocation key, final List<Item> members) {
        final Item base = BuiltInRegistries.ITEM.get(key);
        final ItemStack baseStack = base == null ? ItemStack.EMPTY : new ItemStack(base);
        final Component baseName;
        final ItemStack icon;
        if (!baseStack.isEmpty()) {
            baseName = baseStack.getHoverName();
            icon = baseStack;
        } else {
            final Item first = members.get(0);
            final String stripped = stripColorWord(NAMES.getOrDefault(first, ""),
                colorOfId(IDS.get(first)));
            baseName = Component.literal(stripped);
            icon = ICONS.getOrDefault(first, new ItemStack(first));
        }
        final String modName = modDisplayName(key.getNamespace());
        final Component fullName = modName == null
            ? baseName
            : Component.translatable(LANG + "group.mod", modName, baseName);
        final Component displayName = Component.translatable(LANG + "group.header", fullName,
            members.size());
        // 排序键 = 基础名（不含「（N 色）」与颜色词）—— 组头因此排在「这个家族只有一个条目时
        // 它会出现的位置」，不会被数量后缀或某个成员名带偏
        final String sortName = fullName.getString().toLowerCase(Locale.ROOT);
        return new Family(key, members, icon, displayName, sortName);
    }

    /** 模组显示名（加载器给的；{@code minecraft} 与拿不到时返回 {@code null} = 不加前缀）。 */
    @Nullable
    private static String modDisplayName(final String namespace) {
        if ("minecraft".equals(namespace)) {
            return null;
        }
        final ModList modList = ModList.get();
        if (modList == null) {
            return null; // 加载器未就绪（极早期）：优雅降级，不抛异常
        }
        final String name = modList.getModContainerById(namespace)
            .map(container -> container.getModInfo().getDisplayName())
            .orElse(null);
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * 从显示名里去掉颜色词（基础条目不在注册表时的兜底）。
     * <p>例：{@code 白色线缆} → {@code 线缆}。颜色词走原版 {@code color.minecraft.<name>}
     * （RS 自己也用这一套），因此这里不新增任何语言键、所有语言自动生效。</p>
     */
    private static String stripColorWord(final String displayName, @Nullable final String color) {
        if (color == null || displayName.isEmpty()) {
            return displayName;
        }
        final String colorName = Component.translatable("color.minecraft." + color).getString();
        if (colorName.isEmpty() || !displayName.startsWith(colorName)) {
            return displayName;
        }
        final String rest = displayName.substring(colorName.length()).trim();
        return rest.isEmpty() ? displayName : rest;
    }

    /** 颜色词判据（唯一实现；吃注册 id，构建期也能安全调用）。 */
    @Nullable
    private static String colorOfId(@Nullable final ResourceLocation id) {
        if (id == null) {
            return null;
        }
        final String path = id.getPath();
        for (final String color : COLOR_PREFIXES_LONGEST_FIRST) {
            final int prefixLength = color.length() + 1;
            // 「颜色 + '_'」前缀，且后面还有内容（`white_` 这种空尾巴不算）
            if (path.length() > prefixLength
                && path.startsWith(color)
                && path.charAt(color.length()) == '_') {
                return color;
            }
        }
        return null;
    }

    /** 颜色变体归一化（唯一实现）。 */
    private static ResourceLocation computeFamilyKey(final ResourceLocation id) {
        final String color = colorOfId(id);
        if (color == null) {
            return id;
        }
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
            id.getPath().substring(color.length() + 1));
    }

    /** 颜色词表按长度降序（同长按字母序），保证「长词先试」的确定性。 */
    private static List<String> longestFirstColorPrefixes() {
        final List<String> copying = new ArrayList<>(COLOR_ORDER);
        copying.sort((first, second) -> first.length() != second.length()
            ? second.length() - first.length()
            : first.compareTo(second));
        return List.copyOf(copying);
    }

    /** 候选池是否还是空的（极端情况：整合包里一个可充物都没有 ⇒ 界面要显示「没有找到可充电物品」）。 */
    public static boolean isEmpty() {
        return all().isEmpty();
    }

    /** 最近一次构建出的候选条数（只用于回报 / 排障）。 */
    public static int lastBuildCount() {
        all();
        return lastBuildCount;
    }
}
