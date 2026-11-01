package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AreaSelectorItem;
import cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket;
import cretae.cookiewyq.rs_create_compat.network.ShowCollectionRangePacket;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaScanHook;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * <b>高级归流缓存仓</b>方块实体：完全沿用基础版 {@link CollectionCacheBlockEntity} 的结构
 * （三轴半径字段、扫描节奏、删除模式、升级槽、阻塞名单、经验形态、物流能力、耗电公式、
 * 机器集群），只做两件事：
 *
 * <h2>① 容量更大（吞吐不变）</h2>
 * <ul>
 *     <li>缓存区：{@link #CACHE_SLOTS_ADVANCED} = <b>512 格</b>（基础版 256 格），
 *     每格上限仍由堆叠升级决定（默认 64）⇒ 总容量 <b>32768</b>（基础版 16384）；</li>
 *     <li>流体 / 气体缓存：{@link #FLUID_CACHE_CAPACITY_ADVANCED} = <b>1 024 000 mB</b>
 *     （基础版 512 000 mB）；</li>
 *     <li><b>吞吐不动</b>：单次搬运量仍是 {@code 64 × (1 + 堆叠升级数)}、每 tick 处理组数仍是
 *     {@code 1 + 速度升级数}（用户拍板「吞吐不要改，仍由升级槽决定」）。</li>
 * </ul>
 *
 * <h2>② 吸收范围 = 只按选定器的区域（取代 XYZ 半径）</h2>
 * <p>多一个专用槽（普通 {@code ItemStackHandler(1)} + {@code SlotItemHandler}，<b>不是升级槽</b>）：
 * 放入「区域选定器」后，本机的掉落物 / 经验球 / 世界流体吸收<b>只</b>发生在那张选定器的区域里；
 * 三轴半径字段仍然存在，但只影响扫描节奏与耗电，<b>不再参与范围过滤</b>（用户拍板：取代，不是交集）。
 * 没有选定器 / 区域为空 / 选定器维度与当前维度不符 ⇒ <b>整段不吸收</b>
 * （见 {@link #areaAbsorptionBlocked()}），绝不悄悄退回半径。</p>
 *
 * <h2>性能（区域模式的硬上限）</h2>
 * <ul>
 *     <li>实体：区域按<b>簇</b>分组，每簇一个 AABB 各查一次（区域全相邻时仍然只有 1 个盒），
 *     再按绝对坐标 {@code contains} 过滤（不逐区域查，也不拿跨 1000 格的总并集去扫空区块）；</li>
 *     <li>方块：{@link #AREA_QUERY_BUDGET_PER_TICK} = <b>2048 次查询 / tick</b>，跨 tick 游标续扫，
 *     扫完所有区域才回到起点；同一 tick 的第二次调用复用同一批结果；</li>
 *     <li>区域改动（换选定器 / 编辑区域）⇒ 游标归零（不拿旧区域的位次扫新区域）。</li>
 * </ul>
 */
public class AdvancedCollectionCacheBlockEntity extends CollectionCacheBlockEntity {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 高级版缓存区格数（基础版 {@code CACHE_SLOTS} = 256）。 */
    public static final int CACHE_SLOTS_ADVANCED = 512;
    /** 高级版流体 / 气体缓存容量（mB；基础版 512000）。 */
    public static final long FLUID_CACHE_CAPACITY_ADVANCED = 1_024_000L;
    /** 区域模式每 tick 最多的方块查询次数（硬上限，跨 tick 游标）。 */
    public static final int AREA_QUERY_BUDGET_PER_TICK = 2048;
    /**
     * 区域簇划分阈值 1：单簇并集盒<b>任一轴</b>的跨度上限（格）。
     * <p>= 2 × {@link Area#MAX_SIDE}（单区域单边硬上限 128）。理由：玩家最常见的「区域全相邻」画法
     * 必须仍然只产生 <b>1 个</b>查询盒（行为与第 121 轮之前逐字一致）；两块并排画满的最大区域
     * （各 128 格）合起来跨度正好 256，所以阈值不能低于 256。再大（例如 4×MAX_SIDE = 512）就会让
     * 「隔了几十格的两块地」白扫中间空区块，失去拆簇意义。超过阈值就开新簇。</p>
     */
    public static final double AREA_CLUSTER_MAX_SPAN = 2.0D * Area.MAX_SIDE;
    /**
     * 区域簇划分阈值 2：单簇并集盒的<b>体积</b>上限（格³）。
     * <p>= {@link Area#MAX_SIDE}³（128³ = 2,097,152）。跨度上限只挡「细长横跨」，挡不住
     * 「三轴都中等、但角对角」的空心大盒（例如两块 1 格区域分别落在 200×200×200 盒的两个对角：
     * 跨度 200 &lt; 256 能过第一关，体积却有 800 万格）；体积上限把这类盒也拆开，
     * 保证单次 {@code getEntitiesOfClass} 的区块段遍历量有硬上界。</p>
     */
    public static final long AREA_CLUSTER_MAX_VOLUME =
        (long) Area.MAX_SIDE * Area.MAX_SIDE * Area.MAX_SIDE;
    /** 选定器专用槽数量（一个选定器 = 一份区域集合，多放无意义）。 */
    public static final int AREA_SLOT_COUNT = 1;
    private static final String TAG_AREA_SLOT = "AreaSelector";

    /** 界面状态（经菜单数据槽同步）：没装 / 生效中 / 区域为空 / 维度不符。 */
    public static final int AREA_STATUS_NONE = 0;
    public static final int AREA_STATUS_ACTIVE = 1;
    public static final int AREA_STATUS_EMPTY = 2;
    public static final int AREA_STATUS_DIMENSION_MISMATCH = 3;

    /**
     * 「区域选定器」专用槽：普通物品处理器（不是升级槽 —— 升级槽只收 RS 原版升级物品，
     * 见 {@code menu/UpgradeSlot}）。取出即失效：本机<b>不缓存</b>区域副本，
     * 每次扫描都重新读这里的 {@link ItemStack}（避免「取出后还在吸」的幽灵状态）。
     */
    private final ItemStackHandler areaSlot = new ItemStackHandler(AREA_SLOT_COUNT) {
        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return stack.isEmpty() || stack.getItem() instanceof AreaSelectorItem;
        }

        @Override
        protected void onContentsChanged(final int slot) {
            // 换选定器 ⇒ 区域集合变了 ⇒ 游标必须归零（下一次扫描从头覆盖新区域）
            cursor.reset();
            // 第 112 轮：过滤集合跟着选定器走 ⇒ 缓存失效 + 试一次旧档迁移（新选定器可能带着标记）
            invalidateMarksCache();
            legacyFiltersMigrated = false;
            migrateLegacyAreaFilters();
            // 第 106 轮：区域列表快照同步失效（换选定器 / 取出 / 放入都要让界面重发一次列表）
            invalidateAreaSnapshot();
            setChanged();
        }
    };

    private final AreaCursor cursor = new AreaCursor();
    /** 本 tick 解析出来的区域集合（同一 tick 内多处调用只解析一次；null = 当前无效）。 */
    private long selectionTick = Long.MIN_VALUE;
    @Nullable
    private AreaSelection cachedSelection;
    /** 维度不符的提醒节流（每 10 秒最多一条，避免刷屏）。 */
    private int dimensionWarnCooldown;

    public AdvancedCollectionCacheBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_BLOCK_ENTITY.get(), pos, state,
            CACHE_SLOTS_ADVANCED, FLUID_CACHE_CAPACITY_ADVANCED);
    }

    /** 区域选定器槽（菜单 / 掉落结算 / 诊断共用）。 */
    public ItemStackHandler getAreaSlot() {
        return areaSlot;
    }

    /** 当前槽里的选定器物品（空槽返回 {@link ItemStack#EMPTY}）。 */
    public ItemStack getAreaSelectorStack() {
        return areaSlot.getStackInSlot(0);
    }

    /** 当前绑定的区域数量（0 = 没有生效区域）。 */
    public int getActiveAreaCount() {
        final AreaSelection selection = selectionForTick();
        return selection == null ? 0 : selection.areaCount();
    }

    /** 当前生效区域总体积（格）。 */
    public long getActiveAreaVolume() {
        final AreaSelection selection = selectionForTick();
        return selection == null ? 0L : selection.totalVolume();
    }

    /**
     * 界面状态（0 没装 / 1 生效 / 2 空 / 3 维度不符）。
     * <p>与 {@link #getActiveAreaCount()} 分开：玩家最需要知道的是「为什么没在吸」，
     * 而不是一个 0。</p>
     */
    public int getAreaStatus() {
        if (level == null || level.isClientSide()) {
            return AREA_STATUS_NONE;
        }
        final ItemStack stack = areaSlot.getStackInSlot(0);
        if (stack.isEmpty() || !(stack.getItem() instanceof AreaSelectorItem)) {
            return AREA_STATUS_NONE;
        }
        final AreaSelection raw = AreaSelection.get(stack);
        if (AreaSelection.sanitize(raw).isEmpty()) {
            return AREA_STATUS_EMPTY;
        }
        // 「有区域但没绑定维度」：正常 gameplay 不可达（服务端第一次落笔必定绑定当前维度，见
        // AreaSelectorEditPacket），但手工改 NBT / 未来的数据迁移可以造出来。这种选定器无法证明
        // 区域属于哪个维度 ⇒ 本机不吸收，状态按「维度不符」显示（与行为同源，绝不在任意维度生效）。
        if (raw.dimension() == null) {
            return AREA_STATUS_DIMENSION_MISMATCH;
        }
        final ResourceLocation dimension = level.dimension().location();
        if (!raw.matchesDimension(dimension)) {
            return AREA_STATUS_DIMENSION_MISMATCH;
        }
        return AREA_STATUS_ACTIVE;
    }

    /** 菜单数据槽：0 = 生效区域数、1 = 总体积、2 = 状态、3/4/5 = 方块坐标 x/y/z。 */
    public int areaPanelData(final int index) {
        return switch (index) {
            case 0 -> getActiveAreaCount();
            case 1 -> (int) Math.min(Integer.MAX_VALUE, getActiveAreaVolume());
            case 2 -> getAreaStatus();
            case 3 -> worldPosition.getX();
            case 4 -> worldPosition.getY();
            case 5 -> worldPosition.getZ();
            default -> 0;
        };
    }

    /** 菜单数据槽数量。 */
    public static int areaPanelDataCount() {
        return 6;
    }

    /**
     * 区域选定器的数据被服务端写回（增 / 改 / 删 / 选中 / 改绑）后由菜单调用：
     * <b>游标归零 + 本 tick 的区域集合缓存失效 + 标脏</b>。
     * <p>为什么要显式调：写回走的是 {@code ItemStack#set(数据组件)}，不会触发
     * {@code areaSlot} 的 {@code onContentsChanged}。少了这一步会有两个可观察的问题：
     * ① 这次编辑可能不落盘（没有区块脏标记）；② 同一 tick 内后续扫描仍用旧区域集合 /
     * 旧游标位次（区域改了却要等下一 tick 才生效）。</p>
     */
    public void areaSelectorEdited() {
        cursor.reset();
        selectionTick = Long.MIN_VALUE;
        cachedSelection = null;
        // 第 112 轮：过滤集合存在物品上，区域集合 / 选中 / 维度一变就要重读（缓存按 tick 建的）
        invalidateMarksCache();
        invalidateAreaSnapshot();
        setChanged();
    }

    // ==================== 每区域过滤器（第 101 轮 P1；第 112 轮改成「随身带」） ====================

    /** 旧档 NBT：每区域过滤器表（ListTag：{@code {Index, Entries:[标记...]}}）。只用于一次性迁移。 */
    private static final String TAG_AREA_FILTERS = "AreaFilters";
    private static final String TAG_AREA_FILTER_INDEX = "Index";
    private static final String TAG_AREA_FILTER_ENTRIES = "Entries";

    /** 空过滤集合（共享常量：吸收侧用引用相等分桶，空集合必须是同一个对象）。 */
    private static final List<MarkerEntry> NO_FILTERS = List.of();

    /** 旧档遗留的「区域序号 → 过滤集合」（第 101–111 轮存在方块实体上）；迁到选定器后清空已映射条目。 */
    private final Map<Integer, List<MarkerEntry>> legacyAreaFilters = new java.util.LinkedHashMap<>();
    /** 旧档迁移是否已经做过（选定器不在槽里时不置位：放进选定器后会再试一次）。 */
    private boolean legacyFiltersMigrated;
    /** 「有旧条目映射不上、先保留」的 WARN 是否打过（每次读档重置，不随重试刷屏）。 */
    private boolean legacyMigrationWarned;
    /** 「区域位次对不上 ⇒ 冻结、不迁」的 WARN 是否打过（第 114 轮；每次读档重置）。 */
    private boolean legacyMigrationPinnedWarned;

    /**
     * 本 tick 的「区域 → 过滤集合」缓存。
     * <p><b>为什么必须缓存</b>：基类吸收侧按列表<b>引用相等</b>给条目分桶（「凑够 N 件」的门槛
     * 是跨实体累计的），同一 tick 内同一个区域必须拿到<b>同一个</b>列表对象；每次都现读物品会造出
     * 新对象 ⇒ 门槛退化成「按单件统计」。</p>
     */
    private final Map<Area, List<MarkerEntry>> marksCache = new java.util.HashMap<>();
    private long marksCacheTick = Long.MIN_VALUE;

    /**
     * <b>每个区域的 ghost 过滤集合</b>（第 101 轮 P1 的需求；第 112 轮改成存在选定器物品上）。
     *
     * <p>用户原话（第 101 轮）：「每一个区域内可以吸收的东西都是不一样的：某个范围只吸物品 A，
     * 另一个范围只吸物品 B。否则某台机器紧挨着、某物品是原料但又在另一步骤里作为输出，就会重叠——
     * 重复地吸收-放出-吸收-放出」。</p>
     *
     * <h2>第 112 轮为什么搬家（用户原话：「无法正常的单独给某一个区域标记，就非得把这个标记
     * 放在方块中进行吗这样子感觉很麻烦，切换的时候更麻烦」）</h2>
     * <ul>
     *     <li><b>旧实现</b>：过滤集合存在<b>方块实体</b>上，键 = 区域序号（sanitize 后的下标）。
     *     后果 ①：想给某个区域配标记必须站到机器前、把选定器插进槽、再进机器界面切行 ——
     *     手里拿着选定器时什么都做不了；②：区域序号一变（删 / 重排）过滤器会套到别的区域上；
     *     ③：换一台机器 / 拆走选定器，过滤全丢；</li>
     *     <li><b>新实现</b>：过滤集合存在<b>选定器物品</b>上（{@link AreaMarks}，键 = 区域几何六坐标），
     *     因此手持选定器就能在「区域列表 → 某区域的标记」里直接配（新界面
     *     {@code client/screen/AreaMarkScreen}），<b>不需要机器</b>；改名 / 重排不会串位；
     *     选定器拆下来换机器，标记跟着走；</li>
     *     <li><b>机器里的 48 格 ghost 窗口行为不变</b>，只是读写落到物品上（本类的
     *     {@link #getAreaFilter} / {@link #withAreaFilter} 等）。</li>
     * </ul>
     *
     * <h2>语义（与第 101 轮逐字相同）</h2>
     * <ul>
     *     <li><b>空集 = 该区域不吸任何东西</b>（安全默认）：不会出现「没配过滤却把范围内全吸走」；</li>
     *     <li><b>不在任何区域内 ⇒ 不吸</b>（区域取代半径，见 {@link #areaAbsorptionBlocked()}）；</li>
     *     <li>高级版<b>不使用</b>全局匹配区（{@code markers}）：区域模式下「吸什么」只由本表决定；</li>
     *     <li>区域被删除后，它的标记会随选定器一起被剪掉（{@code AreaSelectorEditPacket} 的 DELETE）。</li>
     * </ul>
     */

    /** 当前槽里的选定器（空 = 没有）。 */
    private ItemStack selectorStack() {
        return areaSlot.getStackInSlot(0);
    }

    /** 槽里是不是真的选定器物品。 */
    private boolean hasSelector() {
        final ItemStack stack = selectorStack();
        return !stack.isEmpty() && stack.getItem() instanceof AreaSelectorItem;
    }

    /** 当前有效区域列表（与快照 / 吸收同口径：{@link AreaSelection#sanitize} 之后）。 */
    private List<Area> usableAreas() {
        final AreaSelection selection = selectionForTick();
        return selection == null ? List.of() : selection.areas();
    }

    /**
     * 「可用区域位次」（= 区域列表界面的行 / 快照 entries 的下标 / 菜单的编辑上下文）→ 区域。
     * <p>越界 / 没有选定器 / 维度不符一律 {@code null}。</p>
     */
    @Nullable
    private Area areaAtUsableIndex(final int usableIndex) {
        final List<Area> areas = usableAreas();
        return usableIndex < 0 || usableIndex >= areas.size() ? null : areas.get(usableIndex);
    }

    /**
     * 该区域当前的过滤集合（<b>本 tick 内的稳定对象</b>；没有 = 共享常量 {@link #NO_FILTERS}）。
     * <p>读的是选定器物品上的 {@link AreaMarks} —— 这就是「标记随身带」的落点。</p>
     */
    private List<MarkerEntry> marksFor(@Nullable final Area area) {
        if (area == null || !hasSelector()) {
            return NO_FILTERS;
        }
        final ItemStack stack = selectorStack();
        if (level == null) {
            final List<MarkerEntry> entries = AreaMarks.get(stack, area, null);
            return entries.isEmpty() ? NO_FILTERS : entries;
        }
        final long tick = level.getGameTime();
        if (tick != marksCacheTick) {
            marksCache.clear();
            marksCacheTick = tick;
        }
        return marksCache.computeIfAbsent(area, key -> {
            final List<MarkerEntry> entries = AreaMarks.get(stack, key, level.registryAccess());
            return entries.isEmpty() ? NO_FILTERS : entries;
        });
    }

    /** 让本 tick 的过滤缓存失效（写标记 / 换选定器 / 收到新数据后必须调）。 */
    private void invalidateMarksCache() {
        marksCache.clear();
        marksCacheTick = Long.MIN_VALUE;
    }

    /**
     * 写回某区域的过滤集合（机器侧菜单的唯一写入口：48 格 ghost 窗口 / 清空 / 数量门槛 / 标签）。
     * <p>写到<b>选定器物品</b>上，并立刻让快照失效 + 标脏 —— 与第 101 轮一样的落盘/刷新语义。</p>
     */
    private boolean writeMarks(@Nullable final Area area, @Nullable final List<MarkerEntry> entries) {
        if (area == null || !hasSelector()) {
            return false;
        }
        AreaMarks.put(selectorStack(), area, entries);
        invalidateMarksCache();
        invalidateAreaSnapshot();
        setChanged();
        return true;
    }

    /**
     * 第 112 轮：把<b>旧档</b>里存在方块实体上的每区域过滤（键 = 区域序号）并到选定器物品上。
     * <ul>
     *     <li>只在「槽里真有选定器」时迁移；选定器不在 ⇒ <b>不置位</b>，以后放进来再迁；</li>
     *     <li><b>逐区域合并</b>（第 113 轮修正）：物品上<b>该几何已有标记</b> ⇒ 以物品为准、跳过并 WARN；
     *     没有标记 ⇒ 把旧表条目并进来。旧写法是「物品上有任意一条新标记就整张丢弃」，升级首日
     *     「先给区域 B 配了标记、再把选定器插回旧机器」会把区域 A 的旧标记丢光；</li>
     *     <li>旧表序号超出<b>当前可用</b>区域表（旧版删过区域 / Config 上限变小）⇒ 本轮不迁，
     *     绝不夹取（夹取会把标记套到别的区域上）；</li>
     *     <li><b>第 114 轮复核修（H2 / M1 / M2）</b>：
     *     <b>位次必须对得上</b>（sanitize 只砍尾巴，否则整体冻结 + WARN）；
     *     <b>谁消费谁删</b>（单条过大 / 搬进会超整表体积的区域<b>冻结在旧表</b>，不再被「index &lt; areaCount
     *     一律删」静默删掉）；<b>没有真正进展时不动快照 / 不标脏</b>（否则每 tick 重发快照包）；</li>
     *     <li>旧表在全部条目被消费后自然变空 —— 存档里那个键随之变成空表（旧版本回退读到「没配过滤」）。</li>
     * </ul>
     */
    private void migrateLegacyAreaFilters() {
        if (legacyFiltersMigrated) {
            return;
        }
        if (!hasSelector()) {
            return; // 选定器不在：保留旧表，等放进来再迁（不置位）
        }
        if (legacyAreaFilters.isEmpty()) {
            legacyFiltersMigrated = true;
            return;
        }
        final ItemStack stack = selectorStack();
        final AreaSelection raw = AreaSelection.get(stack);
        final AreaSelection usable = AreaSelection.sanitize(raw);
        // 第 114 轮复核 M1：旧 Index 是「当时的可用区域位次」，只有 sanitize「只砍尾巴」时位次才不变。
        // 一旦它在中间丢掉某个区域（序号整体前移），按位次迁移会把标记<b>套到别的区域上</b>——那比丢掉更糟。
        // 位次对不上 ⇒ 本轮整体冻结（保留旧表、不消费、不改快照），等区域表恢复后再迁。
        if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {
            if (!legacyMigrationPinnedWarned) {
                legacyMigrationPinnedWarned = true;
                LOGGER.warn("[rs_create_compat] 高级归流缓存仓：选定器的区域表里有区域没通过校验"
                        + "（体积 / 单边 / 个数上限）⇒ 可用区域位次会整体前移，旧档每区域过滤的序号已对不上。"
                        + "为免把标记套到别的区域上，本轮不迁移、旧表 {} 条原样保留（位置 {}）",
                    legacyAreaFilters.size(), worldPosition);
            }
            return;
        }
        final int before = legacyAreaFilters.size();
        final AreaMarks.MergeResult result = AreaMarks.migrateByIndex(stack, usable, legacyAreaFilters,
            level == null ? null : level.registryAccess());
        // 第 114 轮复核 H2：<b>谁消费谁删</b>——migrateByIndex 只移除「真写进物品」/「物品已有标记」的条目，
        // 超大条目 / 会超预算的区域冻结在旧表里。以前这里是「index < areaCount 一律删」，
        // 于是「在范围内但没迁成」的条目静默蒸发，且整表一旦超预算，该选定器永久不能再加标记。
        final boolean consumed = legacyAreaFilters.size() != before;
        final boolean changed = consumed || result.migrated() > 0;
        if (legacyAreaFilters.isEmpty()) {
            legacyFiltersMigrated = true; // 全部消费完：不再重试
        }
        // 第 114 轮复核 M2：没有真正进展时<b>不要</b>每 tick invalidate + setChanged ——
        // 那会让区块每 tick 标脏、快照版本每 tick +1（菜单每 tick 重发 AdvancedAreaSnapshotPacket）。
        if (changed) {
            invalidateMarksCache();
            invalidateAreaSnapshot();
            setChanged();
        }
        if (result.migrated() > 0) {
            LOGGER.info("[rs_create_compat] 高级归流缓存仓：旧档每区域过滤已迁到选定器物品（{} 个区域，位置 {}）",
                result.migrated(), worldPosition);
        }
        if (result.skippedExisting() > 0) {
            LOGGER.warn("[rs_create_compat] 高级归流缓存仓：旧档每区域过滤迁移时，{} 个区域在选定器上已有新标记"
                + " ⇒ 以物品为准、旧条目作废（位置 {}）", result.skippedExisting(), worldPosition);
        }
        if (!legacyAreaFilters.isEmpty() && !legacyMigrationWarned) {
            legacyMigrationWarned = true;
            LOGGER.warn("[rs_create_compat] 高级归流缓存仓：还有 {} 条旧区域过滤本轮没迁"
                    + "（序号越界 {} / 单条标记超过 {} B {} / 搬进会超过整表 {} B {}）⇒ 已原样保留、不删；"
                    + "区域表恢复或删掉那个过大标记后会自动再试（位置 {}）",
                legacyAreaFilters.size(), result.droppedOutOfRange(),
                cretae.cookiewyq.rs_create_compat.support.MarkerBudget.MAX_ENTRY_WIRE_BYTES,
                result.skippedTooLarge(),
                cretae.cookiewyq.rs_create_compat.support.MarkerBudget.MAX_TOTAL_WIRE_BYTES,
                result.skippedOverBudget(), worldPosition);
        }
    }

    // ==================== 第 106 轮：区域列表权威快照 ====================

    /**
     * 区域快照的版本号（只增不减）。区域几何 / 每区域过滤 / 选定器槽 / 维度解析任何一处变化都 +1。
     * <p>菜单每 tick 比较它决定是否重发 {@code AdvancedAreaSnapshotPacket}：没有版本号的话要么
     * 每 tick 发包（玩家开界面时白刷流量），要么永远不刷新（编辑完区域列表还是旧的）。</p>
     */
    private int areaSnapshotVersion;

    public int getAreaSnapshotVersion() {
        return areaSnapshotVersion;
    }

    /** 让快照失效（下一次 {@code broadcastChanges} 重发）。 */
    private void invalidateAreaSnapshot() {
        areaSnapshotVersion++;
    }

    /**
     * 服务端权威的「区域列表快照」。
     *
     * <p><b>为什么要有它</b>：区域列表 / 过滤图标此前是界面直接读选定器物品的数据组件画出来的，
     * 而「这个区域能不能被这台机器用」只有服务端说得准（维度、Config 上限、是否参与扫描）。
     * 客户端照列不误 ⇒ 玩家点了一个「列表里有、实际不参与」的区域，看到的就是「标记失败还不说为什么」。</p>
     *
     * <p>可用判据与吸收侧<b>逐字同源</b>（{@link #resolveSelection}）：
     * 没有选定器 / 区域为空 / 未绑维度 / 维度不符 ⇒ 一条都不给；否则按
     * {@link AreaSelection#sanitize} 的口径保留（单边 / 体积 / 个数上限），<b>顺序不变</b>，
     * 并给每项附带前 {@link AdvancedAreaSnapshotPacket#SNAPSHOT_FILTER_ICONS} 条过滤图标。</p>
     */
    public AdvancedAreaSnapshotPacket buildAreaSnapshot(final int containerId) {
        migrateLegacyAreaFilters();
        final int status = getAreaStatus();
        final String dimension = level == null ? "" : level.dimension().location().toString();
        final ItemStack stack = areaSlot.getStackInSlot(0);
        final AreaSelection raw = stack.isEmpty() || !(stack.getItem() instanceof AreaSelectorItem)
            ? AreaSelection.EMPTY : AreaSelection.get(stack);
        final List<AdvancedAreaSnapshotPacket.AreaEntry> entries = new java.util.ArrayList<>();
        int unusable = 0;
        // 只有「维度/绑定」这一层通过时才逐个列区域；否则整份列表就是空（状态码已经说明原因）。
        final boolean dimensionOk = raw.dimension() != null && level != null
            && raw.matchesDimension(level.dimension().location());
        if (dimensionOk) {
            final long maxVolume = AreaSelection.maxVolume();
            final int maxAreas = AreaSelection.maxAreas();
            for (int i = 0; i < raw.areaCount(); i++) {
                final Area area = raw.areas().get(i);
                final boolean usable = area != null && area.invalidReason(maxVolume) == null
                    && entries.size() < maxAreas;
                if (!usable) {
                    unusable++;
                    continue;
                }
                entries.add(new AdvancedAreaSnapshotPacket.AreaEntry(i, area, snapshotFilterIcons(area)));
            }
        } else if (!raw.isEmpty()) {
            unusable = raw.areaCount();
        }
        return new AdvancedAreaSnapshotPacket(containerId, status, dimension, entries,
            raw.selectedIndex(), -1, unusable, 0, 0);
    }

    /**
     * 某区域的过滤图标（只取非空、非流体的物品条目 —— 与「有效过滤条目数」同一口径）。
     * <p><b>第 112 轮修正</b>：以前传的是<b>原始下标</b>去查「按可用位次存」的表，两者在
     * 「有区域因为超限 / 几何非法被排除」时会错位（图标显示的其实是别的区域的标记）。
     * 现在直接按<b>区域几何</b>查，位次口径不再参与。</p>
     */
    private List<MarkerEntry> snapshotFilterIcons(@Nullable final Area area) {
        final List<MarkerEntry> entries = marksFor(area);
        if (entries.isEmpty()) {
            return List.of();
        }
        final List<MarkerEntry> icons = new java.util.ArrayList<>(
            Math.min(entries.size(), AdvancedAreaSnapshotPacket.SNAPSHOT_FILTER_ICONS));
        for (final MarkerEntry entry : entries) {
            if (icons.size() >= AdvancedAreaSnapshotPacket.SNAPSHOT_FILTER_ICONS) {
                break;
            }
            if (entry != null && !entry.isEmpty() && !entry.fluid()) {
                icons.add(entry);
            }
        }
        return List.copyOf(icons);
    }

    /**
     * 某区域的过滤条目（只读；返回<b>本 tick 内的稳定对象</b>，吸收侧按引用相等分桶）。
     *
     * @param areaIndex 「可用区域位次」（区域列表行号 / 快照 entries 下标），不是物品上的原始下标
     */
    public List<MarkerEntry> getAreaFilter(final int areaIndex) {
        migrateLegacyAreaFilters();
        return marksFor(areaAtUsableIndex(areaIndex));
    }

    /** 某区域的有效过滤条目数（非空、非流体；界面显示「只吸 N 种」）。 */
    public int getAreaFilterCount(final int areaIndex) {
        int count = 0;
        for (final MarkerEntry entry : getAreaFilter(areaIndex)) {
            if (entry != null && !entry.isEmpty() && !entry.fluid()) {
                count++;
            }
        }
        return count;
    }

    /** 某区域第 {@code markerIndex} 条过滤条目（界面 / 配置界面读）。 */
    public MarkerEntry getAreaMarker(final int areaIndex, final int markerIndex) {
        final List<MarkerEntry> entries = getAreaFilter(areaIndex);
        return markerIndex < 0 || markerIndex >= entries.size() || entries.get(markerIndex) == null
            ? MarkerEntry.EMPTY : entries.get(markerIndex);
    }

    /**
     * 机器侧菜单：把某区域的过滤集合整体替换（内部用，调用方保证 {@code entries} 已校验）。
     * <p>第 112 轮新增：写入口统一收敛到这里，避免每个动作各写一遍「改列表 + 挪键」。</p>
     */
    private void withAreaFilter(final int areaIndex, final java.util.function.Consumer<List<MarkerEntry>> mutator) {
        final Area area = areaAtUsableIndex(areaIndex);
        if (area == null) {
            return;
        }
        final List<MarkerEntry> next = new ArrayList<>(marksFor(area));
        mutator.accept(next);
        writeMarks(area, next);
    }

    /** 把一堆物品写成某区域的一条 ghost 过滤标记（不消耗物品；数量默认 1 = 有就收）。 */
    public MarkerAddResult addAreaMarker(final int areaIndex, final ItemStack stack) {
        if (stack.isEmpty()) {
            return MarkerAddResult.INVALID;
        }
        final Area area = areaAtUsableIndex(areaIndex);
        if (area == null) {
            return MarkerAddResult.INVALID;
        }
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return MarkerAddResult.INVALID;
        }
        final List<MarkerEntry> entries = marksFor(area);
        for (final MarkerEntry entry : entries) {
            if (entry != null && !entry.isEmpty() && !entry.fluid() && id.equals(entry.id())) {
                return MarkerAddResult.DUPLICATE;
            }
        }
        if (entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {
            return MarkerAddResult.FULL;
        }
        final Level currentLevel = getLevel();
        final MarkerEntry marker = MarkerEntry.item(stack, MIN_UI_MARK_AMOUNT, false,
            currentLevel == null ? null : currentLevel.registryAccess());
        // 第 114 轮：机器侧 48 格 ghost 窗口写的是<b>同一份数据</b>（选定器物品的 CUSTOM_DATA），
        // 所以与 AreaMarkEditPacket / AreaMarkScreen 必须同一套体积预算：
        // ① 单条上限；② <b>整件选定器</b>（所有区域）的整表上限 —— 用 AreaMarks.isOverBudget 按整件算。
        if (cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isEntryTooBig(marker)) {
            return MarkerAddResult.TOO_COMPLEX;
        }
        final List<MarkerEntry> next = new ArrayList<>(entries);
        next.add(marker);
        if (AreaMarks.isOverBudget(selectorStack(), area, next)) {
            return MarkerAddResult.TOO_COMPLEX;
        }
        withAreaFilter(areaIndex, list -> list.add(marker));
        return MarkerAddResult.OK;
    }

    /** 清除某区域的第 {@code markerIndex} 条过滤条目。 */
    public boolean removeAreaMarker(final int areaIndex, final int markerIndex) {
        final Area area = areaAtUsableIndex(areaIndex);
        if (area == null) {
            return false;
        }
        final List<MarkerEntry> entries = marksFor(area);
        if (markerIndex < 0 || markerIndex >= entries.size()) {
            return false;
        }
        withAreaFilter(areaIndex, list -> {
            if (markerIndex < list.size()) {
                list.remove(markerIndex);
            }
        });
        return true;
    }

    /** 设置某区域某条过滤条目的数量门槛 / NBT 严格匹配 / 标签集合（其余字段保留）。 */
    public boolean setAreaMarkerConfig(final int areaIndex, final int markerIndex, final long amount,
                                       final boolean matchNbt, @Nullable final List<ResourceLocation> tags) {
        final Area area = areaAtUsableIndex(areaIndex);
        if (area == null) {
            return false;
        }
        final List<MarkerEntry> entries = marksFor(area);
        if (markerIndex < 0 || markerIndex >= entries.size()) {
            return false;
        }
        final MarkerEntry existing = entries.get(markerIndex);
        if (existing == null || existing.isEmpty()) {
            return false;
        }
        withAreaFilter(areaIndex, list -> {
            if (markerIndex < list.size()) {
                list.set(markerIndex, existing.withRules(Math.max(1L, amount), matchNbt,
                    tags == null ? List.of() : tags));
            }
        });
        return true;
    }

    /** 清空某区域的过滤集合（= 该区域从此不吸任何东西）。 */
    public void clearAreaFilter(final int areaIndex) {
        final Area area = areaAtUsableIndex(areaIndex);
        if (area == null) {
            return;
        }
        writeMarks(area, List.of());
    }

    /**
     * 吸收侧钩子：该实体所在区域的过滤集合。
     * <p>返回 {@code null} / 空集 ⇒ 这个实体不吸（不在任何区域 / 所在区域没配过滤）。
     * 返回的是区域过滤器<b>本 tick 的稳定对象</b>（见 {@link #marksFor}）：基础版吸收侧按引用相等分桶，
     * 同一区域的实体必须拿到同一个列表对象，否则「凑够 N 件」的门槛会退化成按单件统计。</p>
     */
    @Override
    @Nullable
    protected List<MarkerEntry> filtersForEntity(final Entity entity, final HolderLookup.Provider registries) {
        final AreaSelection selection = selectionForTick();
        if (selection == null || entity == null) {
            return null;
        }
        migrateLegacyAreaFilters();
        final BlockPos pos = entity.blockPosition();
        final List<Area> areas = selection.areas();
        for (int i = 0; i < areas.size(); i++) {
            final Area area = areas.get(i);
            // 与 AreaScanHook#containsEntity 同一口径（占用的格 / 所站的格 / 被卡的上方格）
            if (area.contains(pos) || area.contains(pos.below()) || area.contains(pos.above())) {
                return marksFor(area);
            }
        }
        return null; // 不在任何区域：不吸
    }

    /** 是否有任何区域配了有效过滤条目（状态行用）。 */
    @Override
    protected boolean hasAnyEffectiveFilter() {
        migrateLegacyAreaFilters();
        for (final Area area : usableAreas()) {
            for (final MarkerEntry entry : marksFor(area)) {
                if (entry != null && !entry.isEmpty() && !entry.fluid()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 区域模式的状态码：没装选定器 / 区域为空 / 维度不符分别给不同文案（服务端算，界面只显示）。 */
    @Override
    protected int absorbStatusOverride() {
        return switch (getAreaStatus()) {
            case AREA_STATUS_NONE -> ABSORB_STATUS_AREA_NO_SELECTOR;
            case AREA_STATUS_EMPTY -> ABSORB_STATUS_AREA_EMPTY;
            case AREA_STATUS_DIMENSION_MISMATCH -> ABSORB_STATUS_AREA_DIMENSION;
            default -> ABSORB_STATUS_OK;
        };
    }

    // ==================== 区域模式钩子（基础版调用） ====================

    @Override
    @Nullable
    protected AreaScanHook areaScanHook() {
        final AreaSelection selection = selectionForTick();
        if (selection == null) {
            return null;
        }
        cursor.update(selection.areas());
        return cursor;
    }

    /**
     * 高级版<b>永远</b>不退回半径吸收：只要有任何一个条件不满足，本机就一点不吸。
     * <p>用户拍板「区域与 XYZ = 只用区域（取代）」+「没有选定器 / 区域为空 ⇒ 不吸收」——
     * 两条合起来就是本方法：{@link #selectionForTick()} 返回 null 即整段跳过。</p>
     */
    @Override
    protected boolean areaAbsorptionBlocked() {
        if (level != null && !level.isClientSide() && getAreaStatus() == AREA_STATUS_DIMENSION_MISMATCH
            && --dimensionWarnCooldown <= 0) {
            dimensionWarnCooldown = 200;
            LOGGER.info(
                "[rs_create_compat] 高级归流缓存仓：区域选定器绑定的维度与当前维度不符 ⇒ 本机不吸收"
                    + "（位置 {}，选定器维度见物品提示）", worldPosition);
        }
        return selectionForTick() == null;
    }

    /**
     * 「查看吸取范围」的<b>口径</b>（覆写基类的半径口径）。
     *
     * <p><b>永不返回 {@code MODE_RADIUS}</b> —— 这是本类最重要的一条事实：</p>
     * <ul>
     *     <li>区域模式<b>生效</b>（选定器在、区域非空、维度一致）⇒ {@code MODE_AREAS}，
     *     画选定区域（区域<b>取代</b>半径，绝不叠加）；</li>
     *     <li>区域模式<b>开着但不可用</b>（区域为空 / 维度不符）⇒ {@code MODE_NO_AREA}，
     *     不画框（{@link #areaAbsorptionBlocked()} 为真 ⇒ {@code absorbAll} 整段跳过）；</li>
     *     <li><b>没装区域选定器</b> ⇒ {@code MODE_NO_SELECTOR}，<b>同样不画框</b>：本类的
     *     {@link #areaAbsorptionBlocked()} 是 {@code selectionForTick() == null}，没有选定器时
     *     它<b>也为真</b> ⇒ 本机一点不吸。<b>所以「没装选定器就画半径框」在本机是谎报</b>
     *     （这一点与任务书字面不同，已向 Lead 报备；界面状态行 {@code info.none = 未装选定器 · 不吸收}
     *     是同一事实的另一处表达）。</li>
     * </ul>
     */
    @Override
    protected int collectionRangeMode() {
        if (selectionForTick() != null) {
            return ShowCollectionRangePacket.MODE_AREAS;
        }
        return getAreaStatus() == AREA_STATUS_NONE
            ? ShowCollectionRangePacket.MODE_NO_SELECTOR
            : ShowCollectionRangePacket.MODE_NO_AREA;
    }

    /**
     * 区域模式生效时要画的区域：<b>已去重</b>的选定区域（{@link AreaSelection#distinctAreas()}）。
     * <p>去重放在服务端：同一块地喂两个 Outliner slot 会叠画成「框变粗 / 闪烁」，
     * 而且没必要占两次网络（round96 的「三处世界描边都必须去重」纪律的延续）。</p>
     */
    @Override
    protected List<Area> collectionRangeAreas() {
        final AreaSelection selection = selectionForTick();
        return selection == null ? List.of() : selection.distinctAreas();
    }

    /**
     * 解析本 tick 生效的区域集合：选定器存在 + 区域非空 + 维度一致（未绑定视为一致）才返回；
     * 否则返回 null。结果按游戏 tick 缓存，避免一次扫描里多处调用重复解析 / 校验。
     */
    @Nullable
    private AreaSelection selectionForTick() {
        final Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide()) {
            return null;
        }
        final long tick = currentLevel.getGameTime();
        if (tick == selectionTick) {
            return cachedSelection;
        }
        selectionTick = tick;
        cachedSelection = resolveSelection(currentLevel);
        return cachedSelection;
    }

    @Nullable
    private AreaSelection resolveSelection(final Level currentLevel) {
        final ItemStack stack = areaSlot.getStackInSlot(0);
        if (stack.isEmpty() || !(stack.getItem() instanceof AreaSelectorItem)) {
            return null;
        }
        final AreaSelection selection = AreaSelection.sanitize(AreaSelection.get(stack));
        if (selection.isEmpty()) {
            return null;
        }
        // 与 getAreaStatus() 同一条判据：没绑定维度 = 无法证明区域属于当前维度 ⇒ 不吸收。
        if (selection.dimension() == null || !selection.matchesDimension(currentLevel.dimension().location())) {
            return null;
        }
        return selection;
    }

    // ==================== 存档 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(registries));
        // 第 112 轮：每区域过滤已经搬到选定器物品上（AreaMarks）。这里只写「旧档遗留表」，
        // 正常存档里迁移完就是空表 —— 保留这个键是为了让迁移只发生一次且可回退观察。
        final ListTag filters = new ListTag();
        for (final Map.Entry<Integer, List<MarkerEntry>> area : legacyAreaFilters.entrySet()) {
            final ListTag entries = new ListTag();
            for (int i = 0; i < area.getValue().size(); i++) {
                final MarkerEntry entry = area.getValue().get(i);
                if (entry == null || entry.isEmpty()) {
                    continue;
                }
                entries.add(encodeMarkerTag(entry, i));
            }
            if (entries.isEmpty()) {
                continue;
            }
            final CompoundTag areaTag = new CompoundTag();
            areaTag.putInt(TAG_AREA_FILTER_INDEX, area.getKey());
            areaTag.put(TAG_AREA_FILTER_ENTRIES, entries);
            filters.add(areaTag);
        }
        tag.put(TAG_AREA_FILTERS, filters);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_AREA_SLOT)) {
            areaSlot.deserializeNBT(registries, tag.getCompound(TAG_AREA_SLOT));
        }
        legacyAreaFilters.clear();
        final ListTag filters = tag.getList(TAG_AREA_FILTERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < filters.size(); i++) {
            final CompoundTag areaTag = filters.getCompound(i);
            final int areaIndex = areaTag.getInt(TAG_AREA_FILTER_INDEX);
            if (areaIndex < 0) {
                continue;
            }
            final ListTag entries = areaTag.getList(TAG_AREA_FILTER_ENTRIES, Tag.TAG_COMPOUND);
            final List<MarkerEntry> decoded = new java.util.ArrayList<>(entries.size());
            for (int k = 0; k < entries.size(); k++) {
                final MarkerEntry entry = decodeMarkerTag(entries.getCompound(k), registries);
                if (entry != null && !entry.isEmpty()) {
                    decoded.add(entry);
                }
            }
            if (!decoded.isEmpty()) {
                legacyAreaFilters.put(areaIndex, decoded);
            }
        }
        legacyFiltersMigrated = false;
        // 每次读档都允许把「旧条目映射不上」的 WARN 再说一次（否则同一 BE 实例被重复 load 时永久沉默）
        legacyMigrationWarned = false;
        legacyMigrationPinnedWarned = false;
        migrateLegacyAreaFilters();
        cursor.reset();
        invalidateAreaSnapshot();
    }

    /**
     * 与基础版同形的「方块物品状态」钩子：除基础版内容外，**再带上区域选定器槽**。
     *
     * <p><b>现状（如实说明）</b>：本钩子与基础版一样，目前<b>没有任何调用点</b> —— 破坏结算走的是
     * {@code support/BlockContentReleaser}：需要「存方块」时它用
     * {@code BlockEntity#saveWithoutMetadata}（即 {@link #saveAdditional}，其中已含区域槽）写进
     * 方块物品的 {@code BLOCK_ENTITY_DATA}；不需要存时区域选定器作为真物品由
     * {@code BlockContentReleaser.collectHandler} 爆出 / 回网。放置时原版 {@code BlockItem} 会把
     * {@code BLOCK_ENTITY_DATA} 交给 {@code loadWithComponents} → {@link #loadAdditional}，区域槽照常恢复。
     * 保留这份覆写只是为了与基础版家族保持同一形状（将来若真的接了战利品表 NBT 路径也不会漏掉区域槽）。</p>
     */
    @Override
    public CompoundTag saveStateForItem() {
        final CompoundTag tag = super.saveStateForItem();
        if (!areaSlot.getStackInSlot(0).isEmpty()) {
            tag.put(TAG_AREA_SLOT, areaSlot.serializeNBT(
                level == null ? net.minecraft.core.RegistryAccess.EMPTY : level.registryAccess()));
        }
        return tag;
    }

    // ==================== 能力注册 ====================

    /** 网络节点容器 + 物品 / 流体物流能力（与基础版完全同一套实现，只是挂到高级版的类型上）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.ADVANCED_COLLECTION_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.ADVANCED_COLLECTION_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.itemLogisticsView(direction)
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.ADVANCED_COLLECTION_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.fluidLogisticsView(direction)
        );
    }

    // ==================== 区域游标（每 tick ≤ 2048 次查询） ====================

    private static long saturatingAdd(final long first, final long second) {
        final long result = first + second;
        return result < 0L ? Long.MAX_VALUE : result;
    }

    /**
     * 把区域列表贪心切成「簇」：每簇一个并集 AABB（第 121 轮 C-7 的实体查询单位）。
     *
     * <p>算法（O(n)；区域数 ≤ 64，且只在区域集合变化时重建一次）：按 {@code areas} 的<b>列表顺序</b>
     * （= 玩家创建顺序，天然近似空间顺序）逐个并入当前簇；并集盒的跨度 / 体积任一超阈值就封口、
     * 该区域另起新簇。为什么不排序 / 做真正的最近邻聚类：聚类结果只影响「少扫几个空区块」，
     * 不影响正确性（实体最终还要过 {@link AreaScanHook#containsEntity(Entity)} 精确过滤），
     * 而 O(n²) 在主线程上没有收益；反过来，最重要的退化性质「区域全相邻 ⇒ 1 个簇」与顺序无关 ——
     * 相邻区域的并集盒只会随并入缓慢长大，一块一块挨着画出来的区域只要总跨度 / 体积在阈值内，
     * 永远并进同一个簇。</p>
     *
     * <p><b>契约</b>：空列表 ⇒ 空结果；非空列表 ⇒ 至少 1 个盒；区域全相邻（默认 1 格高、正常尺寸）
     * ⇒ 恰好 1 个盒（旧行为不变）。</p>
     */
    static List<AABB> clusterAreas(final List<Area> areas) {
        if (areas == null || areas.isEmpty()) {
            return List.of();
        }
        final List<AABB> out = new ArrayList<>();
        AABB current = null;
        for (final Area area : areas) {
            if (area == null) {
                continue;
            }
            final AABB box = area.toAabb();
            if (current == null) {
                current = box;
                continue;
            }
            final AABB merged = unionBox(current, box);
            if (fitsCluster(merged)) {
                current = merged;
            } else {
                out.add(current); // 当前簇封口
                current = box;    // 该区域开新簇
            }
        }
        if (current != null) {
            out.add(current);
        }
        return out;
    }

    /** 两盒并集（AABB 没有静态并集方法：按两角取 min / max，与 Area#toAabb 同口径）。 */
    private static AABB unionBox(final AABB first, final AABB second) {
        return new AABB(
            Math.min(first.minX, second.minX), Math.min(first.minY, second.minY),
            Math.min(first.minZ, second.minZ),
            Math.max(first.maxX, second.maxX), Math.max(first.maxY, second.maxY),
            Math.max(first.maxZ, second.maxZ));
    }

    /** 单簇并集盒是否仍在阈值内（三轴跨度 + 体积；见两个 AREA_CLUSTER_* 常量的注释）。 */
    private static boolean fitsCluster(final AABB box) {
        if (box.getXsize() > AREA_CLUSTER_MAX_SPAN || box.getYsize() > AREA_CLUSTER_MAX_SPAN
            || box.getZsize() > AREA_CLUSTER_MAX_SPAN) {
            return false;
        }
        return box.getXsize() * box.getYsize() * box.getZsize() <= (double) AREA_CLUSTER_MAX_VOLUME;
    }

    /**
     * 「所有区域展平成一维位次」的跨 tick 游标扫描器。
     *
     * <h2>为什么展平成一维</h2>
     * <p>{@link BlockPos#betweenClosed} 每次迭代都从最小角开始、且区域之间没有统一顺序；
     * 展平后每个区域占用 {@code [prefix[i], prefix[i+1])} 一段连续位次，游标只前进不后退，
     * 于是「预算 2048 时，总会被扫到的是全部区域」而不是「每个区域各自的头部」。
     * 区域增删改（{@link #update(List)}）会重建前缀和并把游标归零 ——
     * 绝不能拿旧区域的位次去扫新区域（那会让新区域的尾部永远轮不到）。</p>
     */
    private final class AreaCursor implements AreaScanHook {
        private List<Area> areas = List.of();
        private long[] prefix = new long[1];
        private long total;
        private long cursorIndex;
        @Nullable
        private AABB union;
        /** 第 121 轮 C-7：每簇一个实体查询盒（已向上扩 1 格；空 = 没有区域）。 */
        private List<AABB> queryBoxes = List.of();
        private long windowTick = Long.MIN_VALUE;
        private List<BlockPos> window = List.of();
        private long budgetTick = Long.MIN_VALUE;
        private int queriesThisTick;

        /** 区域集合变化 ⇒ 重建前缀和、并集、归零游标（同一份列表则不做事）。 */
        void update(final List<Area> next) {
            if (next.equals(areas)) {
                return;
            }
            areas = List.copyOf(next);
            prefix = new long[areas.size() + 1];
            long sum = 0L;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < areas.size(); i++) {
                prefix[i] = sum;
                final Area area = areas.get(i);
                sum = saturatingAdd(sum, area.volume());
                final AABB box = area.toAabb();
                minX = Math.min(minX, box.minX);
                minY = Math.min(minY, box.minY);
                minZ = Math.min(minZ, box.minZ);
                maxX = Math.max(maxX, box.maxX);
                maxY = Math.max(maxY, box.maxY);
                maxZ = Math.max(maxZ, box.maxZ);
            }
            prefix[areas.size()] = sum;
            total = sum;
            union = areas.isEmpty() ? null : new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            // 第 121 轮 C-7：实体查询盒按簇拆分，每簇一个盒、向上扩 1 格（P0 修复那条：
            // 实体 AABB 与区域盒「相切不算相交」，方向必须向上，见 AreaScanHook#entityQueryArea 的注释）。
            // 区域全相邻时 clusterAreas 只出 1 个盒 ⇒ 与旧版「总并集查一次」逐字等价。
            final List<AABB> clusters = clusterAreas(areas);
            final List<AABB> expanded = new ArrayList<>(clusters.size());
            for (final AABB box : clusters) {
                expanded.add(box.expandTowards(0.0D, 1.0D, 0.0D));
            }
            queryBoxes = List.copyOf(expanded);
            reset();
        }

        void reset() {
            cursorIndex = 0L;
            windowTick = Long.MIN_VALUE;
            window = List.of();
        }

        @Override
        @Nullable
        public AABB entityArea() {
            return union;
        }

        /** 第 121 轮 C-7：每簇一个盒（单簇时就是旧的「总并集向上扩 1 格」那一个盒）。 */
        @Override
        public List<AABB> entityQueryAreas() {
            return queryBoxes;
        }

        @Override
        public boolean contains(final BlockPos pos) {
            for (int i = 0; i < areas.size(); i++) {
                if (areas.get(i).contains(pos)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<BlockPos> scanFluidWindow(final Level level, final int budget,
                                              final Predicate<BlockPos> isSource) {
            final long tick = level.getGameTime();
            if (windowTick == tick) {
                return window; // 同一 tick 的第二次调用（流体路径 + 经验液态路径）复用同一批结果
            }
            windowTick = tick;
            if (budgetTick != tick) {
                budgetTick = tick;
                queriesThisTick = 0;
            }
            final int remaining = AREA_QUERY_BUDGET_PER_TICK - queriesThisTick;
            final int allowed = Math.min(remaining, Math.max(0, budget));
            if (allowed <= 0 || total <= 0L || areas.isEmpty()) {
                window = List.of();
                return window;
            }
            if (cursorIndex < 0L || cursorIndex >= total) {
                cursorIndex = 0L;
            }
            int areaIndex = locate(cursorIndex);
            long offset = cursorIndex - prefix[areaIndex];
            final List<BlockPos> found = new ArrayList<>();
            for (int i = 0; i < allowed; i++) {
                final Area area = areas.get(areaIndex);
                final long sizeX = area.sizeX();
                final long sizeY = area.sizeY();
                final long dx = offset % sizeX;
                final long dy = (offset / sizeX) % sizeY;
                final long dz = offset / (sizeX * sizeY);
                final BlockPos pos = new BlockPos(
                    (int) (area.minX() + dx), (int) (area.minY() + dy), (int) (area.minZ() + dz));
                if (isSource.test(pos)) {
                    found.add(pos);
                }
                offset++;
                if (offset >= area.volume()) {
                    offset = 0L;
                    areaIndex++;
                    if (areaIndex >= areas.size()) {
                        areaIndex = 0; // 扫完所有区域 ⇒ 回到第一区（下一轮重新覆盖）
                    }
                }
            }
            queriesThisTick += allowed;
            long next = cursorIndex + allowed;
            cursorIndex = next >= total || next < cursorIndex ? 0L : next;
            window = List.copyOf(found);
            return window;
        }

        /** 位次 → 区域下标（线性扫描，区域数 ≤ 64，一次窗口只做一次）。 */
        private int locate(final long index) {
            for (int i = areas.size() - 1; i >= 0; i--) {
                if (index >= prefix[i]) {
                    return i;
                }
            }
            return 0;
        }

        @Override
        public int areaCount() {
            return areas.size();
        }

        @Override
        public long totalVolume() {
            return total;
        }
    }
}
