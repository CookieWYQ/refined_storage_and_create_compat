package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>每个区域的 ghost 过滤标记「随身带」存储</b>（第 112 轮，用户原话：「无法正常的单独给某一个区域标记，
 * 就非得把这个标记放在方块中进行吗这样子感觉很麻烦，切换的时候更麻烦」）。
 *
 * <h2>为什么从「方块实体」搬到「选定器物品」</h2>
 * <p>第 101 轮把每区域过滤存在高级归流缓存仓的方块实体上（键 = 区域序号），于是：
 * ① <b>想给某个区域配标记就必须站到那台机器前、把选定器插进槽、再进机器界面「区域」里切到那一行</b>——
 * 在手里拿着选定器时什么都做不了；② 区域序号一变（删 / 重排）过滤器就会套到别的区域上；
 * ③ 换一台机器（或拆走选定器）过滤全丢。用户说的「非得把这个标记放在方块里」就是 ①。</p>
 * <p>现在过滤标记存在<b>区域选定器物品本身</b>（自定义数据 {@code RsccAreaMarks}），
 * 以「区域几何」为键（六个坐标，<b>不含名字 / 不含序号</b>），因此：</p>
 * <ul>
 *     <li>手持选定器、在「区域列表」里点某个区域就能直接配标记（新增界面
 *     {@code client/screen/AreaMarkScreen}），<b>不需要机器、不需要进方块界面</b>；</li>
 *     <li>区域改名 / 重排 / 复制不会把标记串到别的区域上；</li>
 *     <li>选定器拆下来换一台机器，标记跟着走（「切换」不再麻烦）；</li>
 *     <li>机器侧的 48 格 ghost 窗口<b>行为不变</b>，只是读写落到物品上（见
 *     {@code AdvancedCollectionCacheBlockEntity} 的每区域过滤实现）。</li>
 * </ul>
 *
 * <h2>为什么走 {@code CUSTOM_DATA}（而不是再开一个注册版数据组件）</h2>
 * <p>这里的标记是<b>纯服务端语义</b>：机器吸收 / 匹配只在服务端做，客户端界面要显示图标时也总是
 * 从服务端快照（{@code AdvancedAreaSnapshotPacket}）或新建界面自己的服务端回包拿，<b>不需要</b>
 * 物品数据组件的 {@code networkSynchronized} 契约。物品本身（主手 / 槽位）的 {@code CUSTOM_DATA}
 * 会随物品一起同步，tooltip 之类读到的也是同一份，因此 {@code CUSTOM_DATA} 足够，
 * 且不用改动 {@link AreaSelection} 的存档 / 网络编码（零迁移风险）。</p>
 *
 * <h2>编码（冻结契约，改动必须同步 {@code AdvancedCollectionCacheBlockEntity} 的旧档迁移）</h2>
 * <pre>
 * RsccAreaMarks: {
 *   Marks: [ { Area: [minX,minY,minZ,maxX,maxY,maxZ], Entries: [ &lt;ghost 标记&gt;... ] }, ... ]
 * }
 * </pre>
 * <p>&lt;ghost 标记&gt; 与机器「匹配区」<b>完全同一份编码</b>（{@code CollectionCacheBlockEntity#encodeMarkerTag}），
 * 因此物品、流体、数据组件、数量门槛、标签匹配全部沿用同一套语义，不会出现第二套匹配规则。</p>
 *
 * <h2>安全边界</h2>
 * <ul>
 *     <li>所有写入口都<b>只由服务端</b>调用（{@code AreaMarkEditPacket} / 机器侧菜单），客户端不直接改自己的物品；</li>
 *     <li>读档时逐条校验：坐标必须是合法 6 元组、标记解码失败就跳过（绝不抛异常）；</li>
 *     <li>单个区域最多 {@link CollectionCacheBlockEntity#MATCH_CAPACITY} 条、最多
 *     {@link AreaSelection#ABSOLUTE_MAX_AREAS} 个区域。<b>读取时只截断、不回写</b>（超限的幽灵记录
 *     留在 NBT 里，直到下一次 {@link #put} 才被覆盖 —— 这里不再声称「读档时丢弃」）；</li>
 *     <li>新界面加标记时单条编码超过 {@link #MAX_MARKER_BYTES} 就拒绝（见 {@link #isOversized}），
 *     避免「成书 / 带内容物的容器」把选定器组件撑到随物品同步时超包；</li>
 *     <li><b>第 114 轮</b>：单条上限挡不住「288 条 × 2 KB ≈ 576 KB」，因此再加<b>整表上限</b>
 *     {@link #MAX_TOTAL_BYTES}，按<b>整件选定器</b>的原始 NBT 字节数（所有区域记录）估算，
 *     见 {@link #isOverBudget}。机器侧的 48 格 ghost 窗口写的是同一份数据，
 *     也走同一套预算（{@code AdvancedCollectionCacheBlockEntity#addAreaMarker}）；</li>
 *     <li><b>只拦新增 / 放大</b>：读档、旧档迁移、换区域时搬运既有标记<b>不删数据</b>
 *     （宁可超限也不静默毁玩家配置，见 {@link #isOverBudget} 的注释）。</li>
 * </ul>
 */
public final class AreaMarks {
    /** 自定义数据根键（CUSTOM_DATA 里的一级键）。 */
    private static final String TAG_ROOT = "RsccAreaMarks";
    private static final String TAG_MARKS = "Marks";
    private static final String TAG_AREA = "Area";
    private static final String TAG_ENTRIES = "Entries";

    private AreaMarks() {
    }

    // ==================== 读 ====================

    /**
     * 某区域的过滤集合（<b>空列表 = 该区域什么都不吸</b>，与机器侧第 101 轮的安全默认一致）。
     * <p>键只比几何（六坐标），名字不参与 —— 改名不会丢标记。</p>
     *
     * <p><b>不要在客户端界面上用这个重载</b>（第 119 轮 C-5 / 第 120 轮）：它等价于
     * {@code get(stack, area, null)}，而 {@code registries == null} 时
     * {@code CollectionCacheBlockEntity#decodeMarkerTag} <b>读不出旧档的 {@code Item}（整份 ItemStack）条目、
     * 直接丢弃</b>；服务端读的是 {@code player.registryAccess()} ⇒ 两边列表长度 / 顺序不同。
     * 界面若按下标发 {@code REMOVE}，就会删到服务端列表里同下标的<b>另一条</b>。
     * 客户端请改走 {@code AreaMarkScreen#clientMarks(...)}（带注册表；拿不到时返回空表而不是回退到这里）。
     * 服务端 / 只读诊断 / 自检模型里传 {@code null} 仍然是合法的 —— 那时确实没有注册表可用，
     * 且这些路径不按下标回写。</p>
     */
    public static List<MarkerEntry> get(final ItemStack stack, @Nullable final Area area) {
        return get(stack, area, null);
    }

    /**
     * 同 {@link #get(ItemStack, Area)}，但允许带注册表上下文。
     * <p>{@code registries} 只影响<b>旧档</b>里「整份 ItemStack 编码」的标记（{@code Item} 键）；
     * 新格式（{@code Id + Nbt}）不依赖它，传 {@code null} 也能读。</p>
     */
    public static List<MarkerEntry> get(final ItemStack stack, @Nullable final Area area,
                                        @Nullable final HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || area == null) {
            return List.of();
        }
        final CompoundTag root = root(stack);
        if (root == null) {
            return List.of();
        }
        final Area key = geometryKey(area);
        final ListTag marks = root.getList(TAG_MARKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < marks.size(); i++) {
            final CompoundTag entry = marks.getCompound(i);
            final Area decoded = readArea(entry);
            if (decoded != null && decoded.equals(key)) {
                return readEntries(entry, registries);
            }
        }
        return List.of();
    }

    /**
     * 整份「区域 → 标记」表（只读副本；顺序 = 写入顺序，界面按它画）。
     * <p>与 {@link #get(ItemStack, Area)} 同样的口径警告：{@code null} 注册表会
     * <b>跳过</b>旧档的 {@code Item} 条目，因此这张表可能比服务端少几个区域。
     * 只用于「有没有标记 / 有几个区域」这类<b>不按下标回写</b>的展示（如 tooltip 计数）。</p>
     */
    public static Map<Area, List<MarkerEntry>> all(final ItemStack stack) {
        return all(stack, null);
    }

    /** 同 {@link #all(ItemStack)}，允许带注册表上下文（只影响旧档整份 ItemStack 编码的标记）。 */
    public static Map<Area, List<MarkerEntry>> all(final ItemStack stack,
                                                   @Nullable final HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return Map.of();
        }
        final CompoundTag root = root(stack);
        if (root == null) {
            return Map.of();
        }
        final Map<Area, List<MarkerEntry>> out = new LinkedHashMap<>();
        final ListTag marks = root.getList(TAG_MARKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < marks.size() && out.size() < AreaSelection.ABSOLUTE_MAX_AREAS; i++) {
            final CompoundTag entry = marks.getCompound(i);
            final Area area = readArea(entry);
            if (area == null || out.containsKey(area)) {
                continue;
            }
            final List<MarkerEntry> entries = readEntries(entry, registries);
            if (!entries.isEmpty()) {
                out.put(area, entries);
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 该选定器上是否<b>任何</b>区域配了标记（界面 / 自检用的只读判据）。 */
    public static boolean hasAny(final ItemStack stack) {
        return !all(stack).isEmpty();
    }

    /**
     * 单条标记编码后的字节上限（第 113 轮复核 D6；权威定义在 {@link MarkerBudget#MAX_ENTRY_BYTES}）。
     * <p>标记会连同「物品的组件补丁」一起写进选定器的 {@code CUSTOM_DATA}，而 {@code CUSTOM_DATA}
     * 随物品一起<b>网络同步</b>（背包 / 菜单槽 / 手持）。成书、带内容物的容器（潜影盒 / 背包）、
     * 大 NBT 的模组物品能一条就上 KB，{@code MATCH_CAPACITY = 288} 条足以把组件撑到 MB 级，
     * 很可能超过一个网络包的上限（把自己踢下线）。因此新界面加标记时单条超过这个字节数就拒绝。</p>
     * <p><b>第 114 轮起不再「只管新入口」</b>：机器侧 48 格 ghost 窗口
     * （{@code AdvancedCollectionCacheBlockEntity#addAreaMarker}）与基础版匹配区
     * （{@code CollectionCacheBlockEntity#tryAddMarker} / {@code setMarkerConfig}）已接同一套预算。
     * 仍然<b>不拦</b>的是「已经存在的旧数据」（读档 / 迁移 / 换区域搬运）：那些保留原样不删。</p>
     */
    public static final int MAX_MARKER_BYTES = MarkerBudget.MAX_ENTRY_BYTES;

    /**
     * <b>整件选定器</b>所有区域标记合起来的字节上限（第 114 轮；权威定义
     * {@link MarkerBudget#MAX_TOTAL_BYTES}）。
     * <p>为什么必须按整件物品而不是按单个区域算：{@code CUSTOM_DATA} 是<b>随物品整体同步</b>的，
     * 服务端 / 客户端拿到的就是整棵树，不存在「只同步某个区域」的路径。</p>
     * <p><b>口径注意</b>：这是 {@link net.minecraft.nbt.Tag#sizeInBytes()} 的堆估算，约为线上字节的
     * {@link MarkerBudget#WIRE_RATIO} 倍；给玩家看的数字请用 {@link #MAX_TOTAL_WIRE_BYTES}。</p>
     */
    public static final int MAX_TOTAL_BYTES = MarkerBudget.MAX_TOTAL_BYTES;

    /** 整件选定器标记表的<b>线上字节</b>上限（文案 / 日志用；= {@link MarkerBudget#MAX_TOTAL_WIRE_BYTES}）。 */
    public static final int MAX_TOTAL_WIRE_BYTES = MarkerBudget.MAX_TOTAL_WIRE_BYTES;

    /** 这条标记编码后是否超过 {@link #MAX_MARKER_BYTES}（{@code null} / 空条目 = 不超）。 */
    public static boolean isOversized(@Nullable final MarkerEntry entry) {
        return MarkerBudget.isEntryTooBig(entry);
    }

    /**
     * 整件选定器的标记表<b>原始 NBT 字节数</b>（诊断 / 自检用）。
     * <p>直接取 {@code RsccAreaMarks} 子树的 {@link CompoundTag#sizeInBytes()}：不需要解码条目，
     * 因此<b>旧格式（{@code Item} 整份 ItemStack）条目也算得进去</b>——这正是「用原始大小而不是
     * 逐条重编码」的理由（重编码需要注册表上下文，客户端 / 只读路径拿不到）。
     */
    public static int itemTableBytes(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final CompoundTag root = root(stack);
        return root == null ? 0 : Math.max(0, root.sizeInBytes());
    }

    /**
     * 把 {@code area} 的标记替换成 {@code next} 之后，<b>整件选定器</b>会不会超过
     * {@link #MAX_TOTAL_BYTES}。
     *
     * <p>算法：以现有整棵子树的原始字节数为基数，<b>减去该区域的旧记录</b>、<b>加上新记录的编码大小</b>
     * （{@link #writeArea} 的产物）。这样不依赖解码旧条目，旧格式记录也照算。</p>
     *
     * <p><b>只用于「新增 / 放大」的拦截</b>：{@code next} 为空（删除该区域）永远返回 {@code false}
     * —— 删除必须永远允许，否则超限的物品连自救都做不到。</p>
     * <p>旧数据（读档 / 旧档迁移 / 换区域搬运）不调用这里做拒绝，只在写入后打 WARN：
     * 见 {@link #put} 的注释与 {@code AdvancedCollectionCacheBlockEntity} 的迁移实现。</p>
     *
     * @param stack 选定器物品（空堆 = 没有预算可言，返回 {@code false}）
     * @param area  正在改写的区域（{@code null} = 只看当前总量，不做替换）
     * @param next  替换后的标记集合（可为 {@code null} = 只查询「现在超没超」）
     */
    public static boolean isOverBudget(final ItemStack stack, @Nullable final Area area,
                                       @Nullable final List<MarkerEntry> next) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final CompoundTag root = root(stack);
        final long total = root == null ? 0L : Math.max(0, root.sizeInBytes());
        if (next == null) {
            // 纯查询：只看现在超没超（不涉及替换）
            return MarkerBudget.isTotalTooBig(total);
        }
        final Area key = area == null ? null : geometryKey(area);
        final List<MarkerEntry> clean = sanitizeEntries(next);
        if (clean.isEmpty()) {
            // 删除该区域（next = 空）= 只会让数据变小 ⇒ **永远允许**（第 114 轮复核 M4：
            // 这里以前误写成「超预算就返回 true」，等于超限物品连删都删不掉 —— 与 javadoc 相反）
            return false;
        }
        if (key == null) {
            // 防御：没有区域几何就没法算「替换后」，按「追加」估一个上界（调用方目前只会传真实区域）
            return MarkerBudget.isTotalTooBig(total + MarkerBudget.entriesBytes(clean) + 64L);
        }
        long after = total - recordBytes(root, key);
        after += writeArea(key, clean).sizeInBytes();
        return MarkerBudget.isTotalTooBig(after);
    }

    /** 现有子树里某区域那条记录的原始字节数（没有该记录 = 0）。 */
    private static int recordBytes(@Nullable final CompoundTag root, final Area key) {
        if (root == null || key == null) {
            return 0;
        }
        final ListTag marks = root.getList(TAG_MARKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < marks.size(); i++) {
            final CompoundTag record = marks.getCompound(i);
            if (key.equals(readArea(record))) {
                return Math.max(0, record.sizeInBytes());
            }
        }
        return 0;
    }

    /** {@link MarkerBudget#isTotalTooBig} 的转调（避免调用方再 import 一个类）。 */
    private static boolean isTotalTooBig(final long bytes) {
        return MarkerBudget.isTotalTooBig(bytes);
    }

    /**
     * {@link AreaSelection} 的可用区域列表是不是拿原始列表「只砍尾巴」得到的（逐项几何相等 + 顺序不变）。
     * <p>迁移用：旧档的 {@code Index} 是<b>当时</b>的「可用区域位次」。如果 sanitize 只是把尾部不可用的区域
     * 截掉，那么前 N 个位次仍然对得上；一旦 sanitize 在中间丢掉了某个区域（序号会整体前移），
     * 按位次迁移就会把标记<b>套到别的区域上</b>——那比丢掉更糟。判定不成立时调用方应「冻结、不迁」。</p>
     */
    public static boolean isIndexPrefix(final List<Area> rawAreas, final List<Area> usableAreas) {
        if (rawAreas == null || usableAreas == null || usableAreas.size() > rawAreas.size()) {
            return false;
        }
        for (int i = 0; i < usableAreas.size(); i++) {
            final Area raw = rawAreas.get(i);
            final Area usable = usableAreas.get(i);
            if (raw == null || usable == null || !geometryKey(raw).equals(geometryKey(usable))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 写（只由服务端调用） ====================

    /**
     * 覆盖某区域的过滤集合（{@code entries} 为空 = 删除这条记录，该区域回到「什么都不吸」）。
     * <p><b>调用方负责</b>：条数上限、标记合法性、体积预算（{@link #isOverBudget}）、
     * 以及写完之后 {@code setChanged()} + 让机器快照失效。</p>
     * <p><b>本方法不做预算拒绝</b>：它是「数据层」的写入原语，旧档迁移 / 换区域搬运 / 合并都要经过它，
     * 而那些路径的原则是<b>不删玩家既有数据</b>。新增 / 放大标记的四个入口各自先查
     * {@link #isOverBudget}（见 {@link #MAX_TOTAL_BYTES} 的注释）。</p>
     */
    public static void put(final ItemStack stack, @Nullable final Area area, @Nullable final List<MarkerEntry> entries) {
        if (stack == null || stack.isEmpty() || area == null) {
            return;
        }
        final Area key = geometryKey(area);
        final List<MarkerEntry> clean = sanitizeEntries(entries);
        update(stack, root -> {
            final ListTag marks = root.getList(TAG_MARKS, Tag.TAG_COMPOUND).copy();
            marks.removeIf(tag -> {
                final Area decoded = readArea(tag instanceof CompoundTag compound ? compound : new CompoundTag());
                return decoded == null || decoded.equals(key);
            });
            if (!clean.isEmpty()) {
                marks.add(writeArea(key, clean));
            }
            root.put(TAG_MARKS, marks);
        });
    }

    /** 删除某区域的过滤记录（区域被删 / 玩家点「清空」）。 */
    public static void remove(final ItemStack stack, @Nullable final Area area) {
        put(stack, area, List.of());
    }

    /**
     * 剪掉「已经不在区域列表里」的记录（区域被删掉之后调用）。
     * <p>只删真正不存在的几何；名字变了（几何没变）的记录<b>保留</b> —— 那还是同一块地。</p>
     * <p><b>{@code selection == null} 什么都不剪</b>：null 表示「没有区域列表的信息」，
     * 按「全部都不存在」处理会把整件物品的标记一次清空 —— 这是 public API 上的清空陷阱，
     * 第 113 轮复核发现当前实现正是那样（javadoc 却写「只删真正不存在的几何」），已改成早退。</p>
     * <p>遍历用<b>原始记录列表</b>（不再走 {@link #all}）：{@code all} 按区域数上限截断，
     * 超上限的幽灵记录会永远剪不掉。</p>
     */
    public static void prune(final ItemStack stack, @Nullable final AreaSelection selection) {
        if (stack == null || stack.isEmpty() || selection == null) {
            return;
        }
        final List<Area> keep = new ArrayList<>();
        for (final Area area : selection.areas()) {
            if (area != null) {
                keep.add(geometryKey(area));
            }
        }
        final CompoundTag root = root(stack);
        if (root == null) {
            return;
        }
        final ListTag marks = root.getList(TAG_MARKS, Tag.TAG_COMPOUND);
        int doomed = 0;
        for (int i = 0; i < marks.size(); i++) {
            final Area existing = readArea(marks.getCompound(i));
            if (existing != null && !keep.contains(existing)) {
                doomed++;
            }
        }
        if (doomed == 0) {
            return; // 绝大多数调用都是「没有要剪的」：一次 copy 都不做
        }
        // 第 114 轮复核 L2：以前是「每剪一个区域调一次 remove()」，每次 remove 都整树 copy
        // （CustomData.update 内部 copyTag）⇒ 上千条幽灵记录是 O(n²) 的主线程开销。改成一次批量重建。
        update(stack, updatedRoot -> {
            final ListTag current = updatedRoot.getList(TAG_MARKS, Tag.TAG_COMPOUND);
            final ListTag kept = new ListTag();
            for (int i = 0; i < current.size(); i++) {
                final CompoundTag record = current.getCompound(i);
                final Area existing = readArea(record);
                if (existing != null && !keep.contains(existing)) {
                    continue; // 区域已不在列表里：剪掉
                }
                kept.add(record);
            }
            if (kept.isEmpty()) {
                updatedRoot.remove(TAG_MARKS);
            } else {
                updatedRoot.put(TAG_MARKS, kept);
            }
        });
    }

    /**
     * 旧档迁移结果（第 114 轮复核后扩充）。
     *
     * @param migrated            真正并进选定器的区域数
     * @param skippedExisting     物品上该几何<b>已有标记</b>、以物品为准而跳过的区域数（旧条目<b>已消费</b>：
     *                            否则每次 tick 都会重试同一条）
     * @param droppedOutOfRange   旧表序号超出<b>当前可用</b>区域表（旧版删过区域 / Config 上限变小 /
     *                            选定器暂时没区域）<b>本轮</b>没映射上的区域数（旧条目<b>保留</b>，等恢复后再迁）
     * @param skippedTooLarge     该区域旧条目里有单条超过 {@link MarkerBudget#MAX_ENTRY_BYTES} 的标记 ⇒
     *                            <b>整条区域冻结在旧表</b>（不搬进物品，不消费）
     * @param skippedOverBudget   搬进来会让选定器整表超过 {@link #MAX_TOTAL_BYTES} ⇒ <b>冻结在旧表</b>（不消费）
     * @param pinned              区域表位次对不上（sanitize 不是「只砍尾巴」）⇒ <b>本轮整体不迁</b>，
     *                            全部保留（见 {@link #isIndexPrefix}）。第 115 轮起这个位由
     *                            {@link #migrateByIndex} <b>自己</b>判（纵深防御），不再依赖调用方记得先查；
     *                            调用方仍可自行先判（那样能打出带坐标的 WARN）。
     */
    public record MergeResult(int migrated, int skippedExisting, int droppedOutOfRange,
                              int skippedTooLarge, int skippedOverBudget, boolean pinned) {
        /** 兼容旧调用 / 自检的三参形态。 */
        public MergeResult(final int migrated, final int skippedExisting, final int droppedOutOfRange) {
            this(migrated, skippedExisting, droppedOutOfRange, 0, 0, false);
        }

        /**
         * 本轮是否真的动了旧表（有消费或写入）。
         * <p><b>第 115 轮修正</b>：{@code pinned} 不算「动过」—— 冻结的含义是「一个字节都没改」，
         * 把它算进 {@code touched()} 会让调用方对一张根本没变化的表标脏 / 失效快照
         * （第 114 轮它还恒 {@code false} 所以看不出来，现在它是可达状态，必须改对）。</p>
         */
        public boolean touched() {
            return migrated > 0 || skippedExisting > 0;
        }
    }

    /**
     * 把旧档里的「区域序号 → 标记」表<b>按序号并</b>到几何键上（服务端迁移用）。
     *
     * <h2>为什么是「逐区域合并」而不是「物品有标记就整张丢弃」</h2>
     * <p>第 112 轮最初的写法是「物品上只要有<b>任意</b>一条新标记 ⇒ 旧表整张丢弃」。升级首日最容易踩的
     * 顺序是：先拿着选定器用新界面给区域 B 配了标记，再把选定器插回还没迁过的旧机器 —— 区域 A 的旧标记
     * 就此<b>永久丢失</b>。现在逐区域判断：<b>物品上该几何已有标记 ⇒ 以物品为准、跳过（并计数）</b>；
     * 该几何还没有标记 ⇒ 把旧表条目并进来。<b>任何路径都不会覆盖已有新标记</b>。</p>
     *
     * <h2>旧档表没有几何，只能按序号</h2>
     * <p>旧数据是 {@code {Index, Entries}}，不含坐标；{@code Index} 是<b>当时</b>的「可用区域位次」。
     * 若玩家在旧版本里删过区域、{@code Config} 上限变小、或 sanitize 在中间丢了区域，
     * 序号就会漂移：<b>宁可冻结也不夹取</b>——夹取会把标记套到别的区域上（比丢掉更糟）。
     * 调用方用 {@link #isIndexPrefix} 判断「只砍尾巴」，不成立时整体不迁（{@code pinned}）。</p>
     *
     * <h2>消费语义（第 114 轮复核修）</h2>
     * <p>以前调用方是「按 index &lt; areaCount 一律删旧条目」，于是<b>在范围内但没迁成</b>的条目
     * （超大 / 会超预算）也被删掉 ⇒ 数据静默蒸发。现在<b>谁消费谁自己删</b>：
     * 只有「真的写进物品」或「物品已有标记、旧条目作废」的条目才从 {@code legacyByIndex} 里移除；
     * 冻结的条目原样留着（旧表已不参与吸收，等于冻结而非删除）。</p>
     */
    public static MergeResult migrateByIndex(final ItemStack stack, @Nullable final AreaSelection selection,
                                             final Map<Integer, List<MarkerEntry>> legacyByIndex,
                                             @Nullable final HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || selection == null || legacyByIndex == null
            || legacyByIndex.isEmpty()) {
            return new MergeResult(0, 0, 0);
        }
        // 第 115 轮：位次校验下移到数据层（纵深防御）。
        // 第 114 轮这一条只在调用方（高级归流缓存仓）里做，于是 `pinned` 字段恒 false、
        // 任何别的调用方（或将来重构掉那行判断）都会在没有校验的情况下按序号乱搬标记。
        // 现在 raw 与 usable 都在手边（raw 从物品上读，usable 就是入参），这里自己判：
        // 不成立 ⇒ 整体冻结（0 迁移、0 消费、pinned = true）。
        final AreaSelection rawSelection = AreaSelection.get(stack);
        if (rawSelection == null || !isIndexPrefix(rawSelection.areas(), selection.areas())) {
            return new MergeResult(0, 0, 0, 0, 0, true);
        }
        final List<Area> areas = selection.areas();
        int migrated = 0;
        int skipped = 0;
        int dropped = 0;
        int tooLarge = 0;
        int overBudget = 0;
        for (final java.util.Iterator<Map.Entry<Integer, List<MarkerEntry>>> it =
                legacyByIndex.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Integer, List<MarkerEntry>> entry = it.next();
            final int index = entry.getKey() == null ? -1 : entry.getKey();
            if (index < 0 || index >= areas.size()) {
                dropped++; // 越界：保留，等区域表恢复后再迁
                continue;
            }
            final Area area = areas.get(index);
            if (!get(stack, area, registries).isEmpty()) {
                // 物品是新的权威：这个几何已经有标记了，绝不拿旧序号表覆盖它；旧条目作废（消费）
                skipped++;
                it.remove();
                continue;
            }
            final List<MarkerEntry> clean = sanitizeEntries(entry.getValue());
            if (clean.isEmpty()) {
                it.remove(); // 空条目：没有数据可迁，消费掉（否则每次重试都白跑）
                continue;
            }
            boolean oversized = false;
            for (final MarkerEntry candidate : clean) {
                if (MarkerBudget.isEntryTooBig(candidate)) {
                    oversized = true;
                    break;
                }
            }
            if (oversized) {
                tooLarge++; // 冻结：不把超大标记搬进选定器（否则整件物品永久超预算、连标记都加不了）
                continue;
            }
            if (isOverBudget(stack, area, clean)) {
                overBudget++; // 冻结：搬进来会撑爆整表
                continue;
            }
            put(stack, area, clean);
            it.remove();
            migrated++;
        }
        return new MergeResult(migrated, skipped, dropped, tooLarge, overBudget, false);
    }

    // ==================== 内部：编码 / 解码 ====================

    /** 只比六个坐标的键（名字置空 ⇒ {@link Area#equals} 等价于「同一块地」）。 */
    private static Area geometryKey(final Area area) {
        return area.withName(null);
    }

    @Nullable
    private static CompoundTag root(final ItemStack stack) {
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(TAG_ROOT)) {
            return null;
        }
        // 只读：直接拿内层 Tag（getUnsafe 是「只读视图」入口），不做 copyTag ——
        // 界面每帧都会读，整棵树复制在高标记数下是纯浪费。这里绝不修改返回的 tag。
        final Tag tag = data.getUnsafe().get(TAG_ROOT);
        return tag instanceof CompoundTag compound ? compound : null;
    }

    /**
     * 读-改-写 {@code CUSTOM_DATA} 里的 {@code RsccAreaMarks} 子树。
     * <p>空的整棵树会被删掉（不留 {@code RsccAreaMarks: {}} 这种噪声，也不会让「没标记」的物品
     * 因为一次误写就带上一个空组件）。</p>
     */
    private static void update(final ItemStack stack, final java.util.function.Consumer<CompoundTag> mutator) {
        final CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        stack.set(DataComponents.CUSTOM_DATA, data.update(outer -> {
            final CompoundTag root = outer.contains(TAG_ROOT) ? outer.getCompound(TAG_ROOT) : new CompoundTag();
            mutator.accept(root);
            if (root.isEmpty()) {
                outer.remove(TAG_ROOT);
            } else {
                outer.put(TAG_ROOT, root);
            }
        }));
        // 空组件不留在物品上：与「从来没配过标记」的物品保持完全一致（便于装备比对 / 复制）
        if (stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        }
    }

    private static CompoundTag writeArea(final Area area, final List<MarkerEntry> entries) {
        final CompoundTag tag = new CompoundTag();
        tag.putIntArray(TAG_AREA, new int[] {
            area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()});
        final ListTag list = new ListTag();
        for (int i = 0; i < entries.size(); i++) {
            final MarkerEntry entry = entries.get(i);
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            list.add(CollectionCacheBlockEntity.encodeMarkerTag(entry, i));
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    @Nullable
    private static Area readArea(final CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_AREA, Tag.TAG_INT_ARRAY)) {
            return null;
        }
        final int[] values = tag.getIntArray(TAG_AREA);
        if (values.length != 6) {
            return null;
        }
        return new Area(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static List<MarkerEntry> readEntries(final CompoundTag tag,
                                                 @Nullable final HolderLookup.Provider registries) {
        final ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return List.of();
        }
        final List<MarkerEntry> out = new ArrayList<>(Math.min(list.size(),
            CollectionCacheBlockEntity.MATCH_CAPACITY));
        for (int i = 0; i < list.size() && out.size() < CollectionCacheBlockEntity.MATCH_CAPACITY; i++) {
            final MarkerEntry entry = CollectionCacheBlockEntity.decodeMarkerTag(list.getCompound(i), registries);
            if (entry != null && !entry.isEmpty()) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /** 去掉空条目、截到上限（写入口统一归一，避免「空槽占了容量」）。 */
    private static List<MarkerEntry> sanitizeEntries(@Nullable final List<MarkerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        final List<MarkerEntry> out = new ArrayList<>(Math.min(entries.size(),
            CollectionCacheBlockEntity.MATCH_CAPACITY));
        for (final MarkerEntry entry : entries) {
            if (out.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {
                break;
            }
            if (entry != null && !entry.isEmpty()) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }
}
