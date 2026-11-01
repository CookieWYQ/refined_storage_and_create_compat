package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.mojang.logging.LogUtils;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.CollectionCacheNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.ShowCollectionRangePacket;
import cretae.cookiewyq.rs_create_compat.support.AbsorbType;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaScanHook;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import cretae.cookiewyq.rs_create_compat.support.CollectionBlockedLimits;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.MarkerBudget;
import cretae.cookiewyq.rs_create_compat.support.MultiFluidCache;
import cretae.cookiewyq.rs_create_compat.support.OptionalDeps;
import cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger;
import cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster;
import cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter;
import cretae.cookiewyq.rs_create_compat.support.XpForm;
import cretae.cookiewyq.rs_create_compat.support.XpTargetResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 归流缓存仓方块实体：按「匹配区」的 ghost 规则凑够数量后，从世界吸取资源 →
 * <b>先进本机缓存</b>（物品 256 格 × 每格 64 = 16384；流体/气体 512000 mB、种类无上限）→
 * 再由网络节点按速度升级节流 {@code insert} 进 RS 网络 {@code StorageNetworkComponent}。
 * 缓存本身<b>不作为网络存储暴露</b>。
 * 匹配区为 ghost 标记（不是真实存储，放入只做标记、不消耗），共 {@link #MATCH_CAPACITY} 格（每页 48 格 × 6 页）：
 * 每条记录资源（物品或流体/气体）、需求数量 amount 与匹配规则（匹配 NBT/数据组件、按 tag 匹配）。
 * <p>吸取来源 4 开关（{@link AbsorbType}）：掉落物、世界中流体源方块（一格 = 1000 mB）、气体、经验；
 * 开关状态经 {@code CollectionCacheMenu} 的 ContainerData 同步给客户端。
 * <p>升级只保留：速度升级（同时提高「吸取速度（扫描频率）」与「缓存→网络回流速度」）、
 * 堆叠升级（提高缓存每格上限与单次吞吐）。
 */
public class CollectionCacheBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<CollectionCacheNetworkNode>
    implements cretae.cookiewyq.rs_create_compat.support.RsccClusterable,
    cretae.cookiewyq.rs_create_compat.support.RsccDiagnosable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SPEED_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    private static final ResourceLocation STACK_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "stack_upgrade");
    /** 范围升级：每级把三轴的<b>基础上限</b>各提高 {@code Config.rangeChargerRangePerUpgrade}（默认 25）。 */
    private static final ResourceLocation RANGE_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "range_upgrade");
    /** 创造范围升级：三轴范围无限（不再受上限约束）。 */
    private static final ResourceLocation CREATIVE_RANGE_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "creative_range_upgrade");

    // 匹配区「每页可见 48 格」由菜单与贴图决定（见 CollectionCacheMenu.MATCH_WINDOW）。
    /** 匹配区总页数：与界面每页 48 格（12 列 × 4 行）配套，共 6 页 = 288 格。 */
    public static final int MATCH_PAGES = 6;
    /**
     * 匹配区总容量（格）= 每页 48 格 × {@link #MATCH_PAGES} 页。
     * <p><b>为什么必须是「容量」而不是「已用最高下标」</b>：界面每页只映射 48 格，
     * 若最大偏移由「已用到的最高下标」推出，那么 48 格填满前最大偏移恒为 0 ——
     * 玩家既翻不到第二页，也就永远放不下第 49 个标记（现象就是「滚动条滚不动」）。
     * 由容量决定页数后，玩家任何时候都能滚到后面的页并在那里放标记。</p>
     */
    public static final int MATCH_CAPACITY = MATCH_PAGES * 48;
    /** 缓存区（真实存储）格数：256 格 × 每格 64 = 16384 容量上限。 */
    public static final int CACHE_SLOTS = 256;
    /** 插件槽数量。 */
    public static final int UPGRADE_SLOTS = 6;
    /** 流体/气体缓存总容量（mB）。 */
    public static final long FLUID_CACHE_CAPACITY = 512_000L;
    /** 一格世界流体源方块折算的流体量（mB）。 */
    public static final long FLUID_PER_BLOCK = 1000L;
    /** 收集范围下限（格）。 */
    public static final int MIN_COLLECT_RADIUS = 1;
    /** 收集范围<b>基础</b>上限（格）：放入范围升级后每级再 +{@code Config.rangeChargerRangePerUpgrade}。 */
    public static final int MAX_COLLECT_RADIUS = 25;
    /** 三轴范围编号（与界面 / 网络包一致）：0 = X，1 = Y，2 = Z。 */
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;
    /** 每次扫描最多枚举的流体方块数（防半径过大时卡顿）。 */
    private static final int MAX_FLUID_BLOCKS_PER_SCAN = 4096;
    /** 六个面全部允许物流输入（默认值；{@code Direction#ordinal()} 取位）。 */
    public static final int ALL_FACES = 0b111111;

    /** 掉落物/经验球忽略标记（避免反复处理同一堆暂时存不下的东西）。 */
    private static final String TAG_IGNORE_UNTIL = "rs_create_compat:collect_ignore_until";
    /**
     * 失败后的忽略窗口（tick）。第 101 轮从 100 缩短到 20：
     * 旧值 5 秒里即使条件已经恢复（缓存腾出空位 / 数量凑够）也一件都不吸，
     * 用户看到的就是「有时候吸、有时候不吸」。1 秒的退避已足够避免每轮空转刷屏。
     */
    private static final int IGNORE_TICKS = 20;

    private static final String TAG_MARKERS = "Markers";
    /**
     * 「标记数量已按新口径归一」的存档版本标记（第 101 轮）：
     * 旧档（第 95 轮及更早）把 Shift 标记写成的<b>手持堆叠件数</b>当成了「凑够才吸」的硬门槛
     * （常见 64）⇒ 玩家扔 1 件永远不满足门槛、一件都收不进去。载入时若没有这个键，
     * 把 &gt;1 的旧数量统一归 1（并在日志里记一条）；写档时补上这个键，
     * 于是玩家之后在条目标记界面里<b>显式设过</b>的 N 不会被下一次读档抹掉。
     */
    private static final String TAG_MARKER_AMOUNT_V2 = "MarkerAmountV2";
    private static final String TAG_MARKER_SLOT = "Slot";
    private static final String TAG_MARKER_FLUID = "Fluid";
    private static final String TAG_MARKER_ID = "Id";
    private static final String TAG_MARKER_NBT = "Nbt";
    private static final String TAG_MARKER_AMOUNT = "Amount";
    private static final String TAG_MARKER_MATCH_NBT = "MatchNbt";
    /** 匹配标签集合（存 tag 的注册名列表；缺省 = 按具体物品匹配）。 */
    private static final String TAG_MARKER_TAGS = "Tags";
    /** 旧存档字段：单个标签过滤器（读档兼容，升级为单元素列表）。 */
    private static final String TAG_MARKER_TAG_FILTER = "TagFilter";
    // 旧存档字段（仅用于读档兼容；旧的布尔 MatchTag 语义已废弃，不迁移）
    private static final String TAG_MARKER_MATCH_TAG = "MatchTag";
    // 旧存档字段（仅用于读档兼容；旧的 Fuzzy 语义与新的 tag 匹配不同，不迁移）
    private static final String TAG_MARKER_ITEM = "Item";
    private static final String TAG_MARKER_MATCH_COMPONENTS = "MatchComponents";

    private static final String TAG_CACHE = "Cache";
    private static final String TAG_FLUID_CACHE = "FluidCache";
    private static final String TAG_UPGRADES = "Upgrades";
    private static final String TAG_COLLECTED_TOTAL = "CollectedTotal";
    private static final String TAG_INSERTED_TOTAL = "InsertedTotal";
    private static final String TAG_ABSORB = "Absorb";
    private static final String TAG_XP_FORM = "XpForm";
    private static final String TAG_COLLECT_RADIUS = "CollectRadius";
    private static final String TAG_COLLECT_RADIUS_X = "CollectRadiusX";
    private static final String TAG_COLLECT_RADIUS_Y = "CollectRadiusY";
    private static final String TAG_COLLECT_RADIUS_Z = "CollectRadiusZ";
    /** 被阻塞的物品 id 列表（只存注册名；阻塞按「物品种类」判定，不区分 NBT）。 */
    // 「按标签阻塞」名单的持久化键（本轮新增；旧存档没有这两项 → 读档后为空集，行为与改动前一致）
    private static final String TAG_BLOCKED_ITEM_TAGS = "BlockedItemTags";
    private static final String TAG_BLOCKED_FLUID_TAGS = "BlockedFluidTags";
    private static final String TAG_BLOCKED_ITEMS = "BlockedItems";
    /** 被阻塞的流体/气体 id 列表。 */
    private static final String TAG_BLOCKED_FLUIDS = "BlockedFluids";
    /** 「可输入的多个面」开关位（位 = {@link net.minecraft.core.Direction#ordinal()}）。 */
    private static final String TAG_INPUT_FACES = "InputFaces";
    /** 「吸取所有物品」开关（第 7 轮新增：开启后匹配区整体失效，半径内物品全收）。 */
    private static final String TAG_COLLECT_ALL = "CollectAll";
    /** 「反转匹配」开关（第 7 轮新增：匹配区从白名单变黑名单）。 */
    private static final String TAG_INVERT_MATCH = "InvertMatch";
    /**
     * 「直接销毁」匹配槽下标集合（本轮新增，用户原话「在匹配槽那里可以选择是否直接销毁」）。
     * <p>用 int 列表存：命中这些匹配槽的资源在收集后<b>不写回网络、直接销毁</b>。
     * 与匹配条目本身分开存（不改 {@code Markers} 的编码），旧存档没有该键 → 读档后为空集，行为与改动前一致。</p>
     */
    private static final String TAG_DESTROY_MARKERS = "DestroyMarkers";
    /** 删除模式总闸（2026-10-05）。老存档没有这个键 ⇒ {@code false}（绝不在读档时意外开始删东西）。 */
    private static final String TAG_DELETE_MODE = "DeleteMode";

    /**
     * 「不过滤、全收」的阈值口径：{@code <= 0} 在两条收集路径上都表示「不设门槛」——
     * 掉落物路径（{@code absorbMatchingItems}）直接吸、流体路径（{@code absorbMatchingFluidBlocks}）不限每轮上限。
     * <p>「吸取所有物品」与「反转匹配」都没有「某一条标记的数量」可用，因此统一走这个口径，
     * 避免把某条标记的数字误当成门槛。</p>
     */
    private static final long ABSORB_ALL_THRESHOLD = 0L;

    // ==================== 「为什么没在吸」状态码（第 101 轮；界面状态行的唯一来源） ====================

    /** 工作正常（本轮吸到了东西，或条件齐全只是范围内暂时没货）。 */
    public static final int ABSORB_STATUS_OK = 0;
    /** 没接进 RS 网络（节点没有网络）。 */
    public static final int ABSORB_STATUS_NO_NETWORK = 1;
    /** 接进网络了但节点未激活（网络无电 / 红石模式停机）。 */
    public static final int ABSORB_STATUS_INACTIVE = 2;
    /** 「物品」吸取开关被关掉。 */
    public static final int ABSORB_STATUS_TOGGLE_OFF = 3;
    /** 没有任何生效的过滤条目（基础版 = 匹配区空；高级版 = 当前区域没配过滤）。 */
    public static final int ABSORB_STATUS_NO_MARKER = 4;
    /** 有过滤条目、范围内也有东西，但没有一条命中。 */
    public static final int ABSORB_STATUS_NO_TARGET = 5;
    /** 有命中，但没凑够某条标记的「数量门槛」（旧档数量被归一前的典型症状）。 */
    public static final int ABSORB_STATUS_THRESHOLD = 6;
    /** 缓存区已经放不下（会在一秒内自动重试，不丢东西）。 */
    public static final int ABSORB_STATUS_CACHE_FULL = 7;
    /** 高级版：区域模式开着，但槽里没装区域选定器 ⇒ 一点不吸。 */
    public static final int ABSORB_STATUS_AREA_NO_SELECTOR = 8;
    /** 高级版：装了选定器但区域列表为空 ⇒ 一点不吸。 */
    public static final int ABSORB_STATUS_AREA_EMPTY = 9;
    /** 高级版：选定器绑定的维度与当前维度不符 ⇒ 一点不吸。 */
    public static final int ABSORB_STATUS_AREA_DIMENSION = 10;

    /** 本轮状态码 / 附加位（由 {@link #absorbAll} 每轮刷新，界面经数据槽 24 / 25 读取）。 */
    private int absorbStatusReason = ABSORB_STATUS_OK;
    private int absorbStatusArg;
    /** 本轮是否发生过「有命中但没凑够数量门槛」（状态码 6 的判据）。 */
    private boolean thresholdBlockedThisScan;
    /** 本轮实际吸进缓存的物品件数（>0 ⇒ 状态码 0）。 */
    private int absorbedThisScan;
    /**
     * 界面写匹配标记时的默认需求数量（第 96 轮）：<b>1</b> = 「有就收」。
     * <p>为什么不是「手持那一堆的件数」：{@code MarkerEntry#amount} 是「凑够才动手」的硬门槛，
     * 逐件投料 / 农场掉落这类来源永远凑不满一整组 ⇒ 数量少的资源一件都进不来
     * （用户本轮「无法与任何物品交互」的实测原因之一）。需要老口径时在条目标记界面里改数量即可。</p>
     */
    public static final long MIN_UI_MARK_AMOUNT = 1L;

    /** 匹配区：ghost 标记条目（下标即槽位，长度按需增长到 {@link #MATCH_CAPACITY}；null / {@link MarkerEntry#isEmpty()} = 未标记）。 */
    private final List<MarkerEntry> markers = new ArrayList<>();
    /**
     * 缓存区（真实存储）。<b>非 final</b>：参与机器集群时会被换成「整个集群共用的那一份」。
     * <p>单机规模由构造函数注入（{@link #cacheSlots}）：基础版 = {@link #CACHE_SLOTS}，
     * 高级版 = 两倍（见 {@code AdvancedCollectionCacheBlockEntity.CACHE_SLOTS_ADVANCED}）。
     * 容量口径仍然只是「格数 × {@link #getCacheSlotCapacity()}」，吞吐（{@code getTransferBatch}）
     * 与升级槽规则一个字都没改。</p>
     */
    private SimpleContainer cache;
    /** 插件槽（速度 / 堆叠升级）。 */
    private final SimpleContainer upgrades = new SimpleContainer(UPGRADE_SLOTS);
    /**
     * 流体/气体缓存：容量上限、种类无上限。<b>非 final</b>：参与集群时换成集群共用的那一份。
     * <p>单机容量同样由构造函数注入（{@link #fluidCacheCapacity}）。</p>
     */
    private MultiFluidCache fluidCache;
    /** 单机缓存区格数（基础版 {@link #CACHE_SLOTS}；高级版更大）。集群载荷按它 × 台数扩容。 */
    protected final int cacheSlots;
    /** 单机流体 / 气体缓存容量（mB；基础版 {@link #FLUID_CACHE_CAPACITY}；高级版更大）。 */
    protected final long fluidCacheCapacity;
    /** 4 个吸取来源开关（NBT 持久化）。 */
    private final boolean[] absorbEnabled = new boolean[AbsorbType.values().length];
    /**
     * 「阻塞」名单（逐个资源单独配置，NBT 持久化）：
     * 被阻塞的物品 / 流体<b>仍可被本机收集、也可被物流方块输入与玩家取出</b>，
     * 但与本机网络节点之间「只进不出」——绝不会被 {@code insert} 进 RS 网络。
     */
    private final Set<ResourceLocation> blockedItems = new java.util.LinkedHashSet<>();
    private final Set<ResourceLocation> blockedFluids = new java.util.LinkedHashSet<>();
    /**
     * 「按标签阻塞」的物品 / 流体标签集合（用户第 ③ 条：按标签注册的板子族，放一块金板进去
     * 却<b>瞬间回流了</b> —— 根因是这里以前只有「具体资源 id」一份名单，标签过滤器压根没被展开）。
     * <p>语义：只要被阻塞标签集合里的<b>任意一个</b>标签命中该资源，就等同于「该资源被阻塞」。
     * 与 {@link #blockedItems} 并列生效（两个名单都查，命中其一即阻塞）。</p>
     */
    private final Set<ResourceLocation> blockedItemTags = new java.util.LinkedHashSet<>();
    private final Set<ResourceLocation> blockedFluidTags = new java.util.LinkedHashSet<>();
    /**
     * 「多个输入面」开关位（位 = {@link net.minecraft.core.Direction#ordinal()}，默认 0b111111 = 六面全开）。
     * <p>用户需求「序列装配回流器需要可以配置多个的输入」：本仓的物流输入（漏斗 / 管道塞入）
     * <b>可按面逐面开关</b>，与序列执行仓的「面配置」是同一套交互理念（点方块的某个面切换）。
     * 关掉某个面后该面不再对外暴露物品 / 流体能力，但<b>已进入仓内的资源不受影响</b>，
     * 也仍然照常收集世界掉落物、照常回流网络（绝不销毁任何内容）。</p>
     */
    private int inputFaces = ALL_FACES;
    /** 收集范围（三轴，格）：范围越大扫描越快、耗电越高；初始值取配置项（旧档无此字段时也用配置值）。 */
    private int radiusX = Config.collectionCacheScanRadius;
    private int radiusY = Config.collectionCacheScanRadius;
    private int radiusZ = Config.collectionCacheScanRadius;
    /** 经验存储形态（可配置字段；取值点见 {@link #resolveXpFluidId()}）。默认 = 折算成经验颗粒物品。 */
    private XpForm xpForm = XpForm.ORB;
    /** 经验目标解析器（默认按注册表探测：附魔工业液态经验流体 → 机械动力原版经验颗粒，可替换实现）。 */
    private XpTargetResolver xpTargetResolver = XpTargetResolver.DEFAULT;

    /**
     * 「吸取所有物品」开关（服务端权威，NBT 持久化）。
     * <p>用户需求：开启后<b>匹配区直接失效（不过滤，全收）</b>，关闭后恢复按匹配区过滤。
     * 与「反转匹配」同时开启时<b>本开关优先</b>（匹配区整体不参与判定），界面上把匹配区显示为失效态。</p>
     */
    private boolean collectAll;
    /**
     * 「反转匹配」开关（服务端权威，NBT 持久化）。
     * <p>用户需求：把匹配区从<b>白名单</b>变成<b>黑名单</b>（反向过滤）——
     * 勾选模式下「标记的东西收」，反转模式下「<b>没</b>被标记的东西收」。
     * 只在 {@link #collectAll} 关闭时生效（{@link #collectAll} 开启时匹配区整体失效）。</p>
     */
    private boolean invertMatch;

    /**
     * 「直接销毁」匹配槽下标集合（服务端权威，NBT 持久化）。
     * <p>用户需求：匹配槽可以单独设为「直接销毁」—— 凡命中该槽的资源<b>进仓后不写回网络、直接销毁</b>。
     * 这是<b>有意销毁</b>（不是搬运失误），因此每一次销毁都走 {@link RsccFlowLedger#destroyed} 记账，
     * 保证账本「离开网络 + 世界收集 − 进入网络 = 留存 + 销毁」仍然对平、可审计。</p>
     * <p>用 {@link java.util.TreeSet} 保持稳定顺序（落盘 / 同步时下标有序，便于比对）。</p>
     */
    private final java.util.Set<Integer> destroyMarkers = new java.util.TreeSet<>();

    /**
     * <b>删除模式总闸</b>（服务端权威，NBT 持久化；2026-10-05 新增）。
     *
     * <h2>为什么必须有这一层（用户原话）</h2>
     * <p><i>「它现在的逻辑是点击按钮然后清空缓存区，但我想要的是有一个状态——就是一个复选框那样的选择，
     * 选择之后可以开启清除资源功能……首先先要开启那个删除模式，然后这个匹配的资源……找到的都是
     * 要对匹配区的那些标记的物品再次进行标记是否要表达他们删除，然后标记要删除的物品在开启了删除模式下
     * 才会被删除。」</i></p>
     * <p>旧实现只有「每个匹配槽一个直接销毁勾选」，<b>没有任何总闸</b>：勾上一个槽，
     * 命中它的东西下一 tick 就没了，而且不可逆。玩家一旦误勾（或记不清某个槽勾过什么），
     * 就是静默丢料。现在「要删什么」（槽级勾选）与「现在开始删」（本开关）彻底分开：
     * 槽级勾选只<b>记录意图</b>，本开关为 {@code false} 时销毁路径一个字节都不动。</p>
     */
    private boolean deleteMode;

    /**
     * 归流缓存仓的守恒账本（服务端权威；只记数、绝不搬运 / 生成 / 销毁任何资源）。
     * <p><b>为什么缓存仓也需要它</b>：本仓新增了「销毁」这一不可逆行为，而销毁一旦没有独立账目，
     * 出了复制 / 吞物事故就再也查不出「这一份去哪了」。账本把「世界收集 / 进入网络 / 留存 / 销毁」
     * 逐资源记清，销毁量与缓存实际减少量必须一致（自检脚本据此断言）。</p>
     */
    private final RsccFlowLedger flowLedger = new RsccFlowLedger();

    /** 统计：有意销毁的累计数量（物品按件、流体按 mB 合并计数；仅供诊断 / 界面展示）。 */
    private long destroyedTotal;

    /** 距下次扫描的 tick 数。 */
    private int scanCooldown;
    /** 本次读档被归一的旧版「数量门槛」条数（一次性存档迁移，读档结束写一条 INFO）。 */
    private int legacyMarkerAmountsNormalized;

    // ==================== 流体扫描游标（跨扫描续扫；只存内存，不写 NBT） ====================

    /**
     * 流体扫描游标：<b>本轮已经查过的位次</b>，取值 {@code [0, volume)}；{@code 0} = 从最小角开始新一轮。
     * <p><b>为什么需要它</b>：旧实现每次扫描都从 {@code BlockPos.betweenClosed} 的<b>最小角</b>重新开始，
     * 凑够 {@link #MAX_FLUID_BLOCKS_PER_SCAN} 个源方块就 {@code break} —— 体积大于 4096 时尾部区域
     * <b>永远轮不到</b>（半径 25 = 51³ = 132651 格只覆盖前 3.1%；范围升级到 50 = 101³ 只覆盖 0.4%）。
     * 游标把「这一轮扫到哪」跨扫描记住，下一次从断点继续，扫满一整轮才回到 0。</p>
     * <p><b>不落盘</b>：不写进 {@code saveAdditional} —— 重启 / 卸载后从最小角重扫没有任何损失
     * （只是多扫不到一轮的时间），也避免旧存档多出一个无意义的 NBT 键。</p>
     */
    private long fluidScanCursor;
    /** 游标绑定的扫描几何（方块位置 + 三轴半径）的最后一次取值：任一项变化 ⇒ 游标归零（见 {@link #resyncFluidScanCursorIfMoved()}）。 */
    private long fluidScanPos = Long.MIN_VALUE;
    private int fluidScanRadiusX = -1;
    private int fluidScanRadiusY = -1;
    private int fluidScanRadiusZ = -1;
    /** 本 tick 已经花掉的方块查询次数：同一 tick 内多次调用共享预算，上限仍是 {@link #MAX_FLUID_BLOCKS_PER_SCAN}。 */
    private int fluidScanQueriesThisTick;
    /** {@link #fluidScanQueriesThisTick} 归属的服务器 tick（换 tick 自动归零，不依赖 tickCache 的调用顺序）。 */
    private long fluidScanBudgetTick = Long.MIN_VALUE;
    /** 本 tick 游标窗口的扫描结果：同一 tick 的第二次调用直接复用，既不重复查询、也不重复推进游标。 */
    private List<BlockPos> fluidScanWindow = List.of();
    /** {@link #fluidScanWindow} 归属的服务器 tick。 */
    private long fluidScanWindowTick = Long.MIN_VALUE;

    /** 标签过滤器「本轮没吸到东西」日志的节流计数（避免刷屏）。 */
    private int tagDebugCooldown;
    /** 流体标记「本轮没吸到东西」日志的节流计数（避免刷屏）。 */
    private int fluidDebugCooldown;
    /** 流体收集「首次成功」只打一次 INFO（可验证服务端真的在收，之后不再刷屏）。 */
    private boolean fluidCollectLogged;
    /** 经验收集「首次成功」只打一次 INFO（可验证服务端真的在收，之后不再刷屏）。 */
    private boolean experienceCollectLogged;
    /** 经验来源「本轮没吸到东西」日志的节流计数（避免刷屏）。 */
    private int experienceDebugCooldown;
    /**
     * 「经验吸取开关关着、但半径内确实有经验球」这条提醒的节流状态。
     * <p><b>2026-10-25（第 95 轮用户反馈第 3 条）</b>：旧实现是「每个被扫描轮次一条（约 10 秒）」，
     * 用户实机日志里连刷了 <b>数小时</b>（{@code diag/pull_2310/2026-10-24-3.log} 2571 条同类；
     * {@code latest.log} 另有 118 条）。现行口径见 {@link #noteExperienceOffButOrbsNearby(Level)}：
     * <b>状态变化时一条 + 之后每 {@link #EXPERIENCE_OFF_WARN_INTERVAL_TICKS} 至多一条</b>。</p>
     */
    /** 「半径内有没有经验球」这次检查发生在哪个游戏 tick（{@code Long.MIN_VALUE} = 还没查过）。 */
    private long experienceOffCheckedAt = Long.MIN_VALUE;
    /** 上一次「经验开关关着 + 半径内有球」告警发生在哪个游戏 tick（{@code Long.MIN_VALUE} = 从未告警）。 */
    private long experienceOffWarnedAt = Long.MIN_VALUE;
    /** 当前是否处于「经验开关关着 + 半径内有球」这个状态（状态变化才重新告警）。 */
    private boolean experienceOffWarned;

    // ==================== 回写网络（缓存 → 网络）的游标与「为什么写不进去」诊断 ====================

    /**
     * 缓存 → 网络 的<b>轮转游标</b>（本次修复的核心，见 {@link #flushCacheToNetwork(Network)}）。
     * <p>旧实现每 tick 都从第 0 格重新开始、并且<b>只要尝试过就扣处理预算</b>；默认没有速度升级时预算恒为 1，
     * 于是「第 0 格的东西网络拒收（无对应存储 / 存储已满）」会把后面每一格永久饿死 ——
     * 玩家看到的就是「东西明明在缓存里，却永远收不进网络」。游标保证跨 tick 轮转、不重复卡在同一格。</p>
     */
    private int flushCursor;
    /** 流体缓存 → 网络 的轮转游标（按当前条目数取模；条目增删时自动收敛，绝不越界）。 */
    private int fluidFlushCursor;
    /** 连续「缓存里有该回写的内容、但本 tick 一个字节都没写进网络」的 tick 数。 */
    private int flushStallTicks;
    /** 上一次「写不进网络」告警的游戏 tick（{@code -1} = 从未告警）。 */
    private long flushWarnedAt = -1L;
    /** 上一次告警的原因分类（{@link #FLUSH_OK} 等）：用于「状态变化时一条」。 */
    private int flushWarnKind;
    /** 节点未激活但缓存里有待回写内容，连续了多少 tick。 */
    private int inactiveWorkTicks;
    /** 上一次「节点未激活」告警的游戏 tick（{@code -1} = 从未告警）。 */
    private long inactiveWarnedAt = -1L;
    /** 上一次「节点未激活」告警的原因分类。 */
    private int inactiveWarnKind;

    // ==================== 第 96 轮：「哪条路径坏了」的合并诊断 ====================
    //
    // 用户对 beta.3 的原话是「归流缓存期现在还是无法与任何的物品」——这句话本身不指向任何一条具体路径：
    // 界面放入 / 界面取出 / 世界掉落物 / 相邻容器 / 世界流体 / 经验 / 回写网络，任何一条断了看起来都一样。
    // 因此这里按「窗口内这条路径到底有没有真的搬动过资源」记账，每 COLLECT_DIAG_INTERVAL_TICKS
    // （5 秒）合并成**一条** `[rscc-collect]` INFO：只在**路径集合发生变化**时才写
    // （状态变化才记，绝不刷屏）。玩家下次导出日志，一眼就能看出是哪一段没动。

    /** 诊断窗口长度（tick）：100 tick = 5 秒。 */
    private static final int COLLECT_DIAG_INTERVAL_TICKS = 100;
    /** 路径位：界面上把物品/流体放进缓存（普通左键单击或拖入）。 */
    public static final int PATH_UI_PUT = 1;
    /** 路径位：界面上把物品从缓存取出。 */
    public static final int PATH_UI_TAKE = 1 << 1;
    /** 路径位：界面上写匹配标记（第 95 轮起 Shift+左键的默认行为）。 */
    public static final int PATH_UI_MARK = 1 << 2;
    /** 路径位：从世界里的掉落物吸收。 */
    public static final int PATH_DROP = 1 << 3;
    /** 路径位：从相邻方块容器（漏斗 / 溜槽 / 管道）抽入。 */
    public static final int PATH_CONTAINER = 1 << 4;
    /** 路径位：从世界流体源方块吸收。 */
    public static final int PATH_FLUID = 1 << 5;
    /** 路径位：气体来源（目前没有世界来源，见 {@link #collectGas}）。 */
    public static final int PATH_GAS = 1 << 6;
    /** 路径位：吸收经验球 / 液态经验。 */
    public static final int PATH_XP = 1 << 7;
    /** 路径位：缓存 / 流体写回 RS 网络。 */
    public static final int PATH_FLUSH = 1 << 8;
    /** 全部路径位的掩码（用于把「一条都没动过」说清楚）。 */
    private static final int PATH_ALL = (1 << 9) - 1;
    /** 本窗口内动过的路径位。 */
    private int collectDiagFlags;
    /** 上个窗口的路径位（{@code -1} = 还没有基线，第一窗口一定写一条）。 */
    private int collectDiagLastFlags = -1;
    /** 距离下一次合并输出还有多少 tick。 */
    private int collectDiagTicks;

    // ---------- 「写不进网络」告警的口径 ----------
    /** 同一原因下最多每 5 分钟（= 6000 tick）一条：状态变化时立刻一条，之后按这个间隔重复。 */
    private static final int FLUSH_WARN_INTERVAL_TICKS = 6000;
    /** 连续多少 tick 一个字节都写不进去才告警（10 秒）：存储短暂抖动 / 能量瞬时不足不该刷日志。 */
    private static final int FLUSH_STALL_TICKS_BEFORE_WARN = 200;
    /** 未激活（没电 / 红石停机 / 没接网络）持续多少 tick 才告警（10 秒）。 */
    private static final int INACTIVE_WARN_TICKS_BEFORE_WARN = 200;
    /** 告警分类：一切正常（本 tick 有进度或缓存里没有待回写内容）。 */
    private static final int FLUSH_OK = 0;
    /** 告警分类：有内容、网络拒收（insert 返回 0）。 */
    private static final int FLUSH_REJECTED = 1;
    /** 告警分类：网络没有存储组件（未接入 RS 网络 / 网络损坏）。 */
    private static final int FLUSH_NO_NETWORK = 2;
    /** 告警分类：节点未激活 —— 网络能量不足。 */
    private static final int FLUSH_INACTIVE_ENERGY = 3;
    /** 告警分类：节点未激活 —— 红石模式要求当前停机。 */
    private static final int FLUSH_INACTIVE_REDSTONE = 4;

    // ---------- 经验球「开关关着但附近有球」提醒的口径 ----------
    /** 检查半径内有没有经验球的间隔（10 秒）：查实体不便宜，不能每 tick 查。 */
    private static final int EXPERIENCE_OFF_CHECK_INTERVAL_TICKS = 200;
    /** 同一状态下重复提醒的间隔（5 分钟）：状态变化时不受此限，立刻一条。 */
    private static final int EXPERIENCE_OFF_WARN_INTERVAL_TICKS = 6000;
    /** 连续「半径内既没有经验球、也没有液态源」的轮数：用于判定「只是暂时没东西」还是「一直收不到」。 */
    private int experienceEmptyStreak;
    /** 工作状态剩余 tick（用于耗电档位，收集到资源或写入网络后刷新）。 */
    private int workCooldown;
    /** 统计：从掉落物收集的物品总数。 */
    private long collectedTotal;
    /** 统计：写入 RS 网络的物品总数。 */
    private long insertedTotal;
    /** 载入存档 / 校验插件期间跳过副作用。 */
    private boolean loading;
    private boolean enforcing;
    /**
     * 历史兼容字段：旧版由 {@link #saveStateForItem()} 写入并置位。
     * <p><b>现在没有任何读写点</b>：方块被拆时的内容物结算改由 {@code BlockContentReleaser}
     * 直接走 {@code saveAdditional()}（掉落的方块物品带 BLOCK_ENTITY_TAG，放置时由
     * {@code CollectionCacheBlock#setPlacedBy} → {@link #loadPlacedState} 回填）。
     * 字段与 {@link #isStateDropped()} 保留只是不改变旧存档的结构，<b>不参与任何逻辑判定</b>。</p>
     */
    private boolean stateDropped;

    public CollectionCacheBlockEntity(final BlockPos pos, final BlockState state) {
        this(RS_Create_Compat.COLLECTION_CACHE_BLOCK_ENTITY.get(), pos, state,
            CACHE_SLOTS, FLUID_CACHE_CAPACITY);
    }

    /**
     * 供高级版使用的构造器（与 {@code SchematicLoaderBlockEntity} 的「基础版构造函数委托到
     * protected 通用构造器」同一套手法）：方块实体类型与两种容量由子类注入，其余初始化逐字不变。
     *
     * @param blockEntityType  子类自己的方块实体类型（必须，否则 {@code getType()} 会指回基础版）
     * @param cacheSlots       单机缓存区格数
     * @param fluidCacheCapacity 单机流体 / 气体缓存容量（mB）
     */
    protected CollectionCacheBlockEntity(final BlockEntityType<?> blockEntityType,
                                         final BlockPos pos,
                                         final BlockState state,
                                         final int cacheSlots,
                                         final long fluidCacheCapacity) {
        super(blockEntityType, pos, state, new CollectionCacheNetworkNode());
        this.cacheSlots = Math.max(1, cacheSlots);
        this.fluidCacheCapacity = Math.max(0L, fluidCacheCapacity);
        this.cache = new SimpleContainer(this.cacheSlots);
        this.fluidCache = new MultiFluidCache(this.fluidCacheCapacity);
        // 默认只开物品吸取：流体吸取会消耗世界方块，必须由玩家显式开启
        for (int i = 0; i < absorbEnabled.length; i++) {
            absorbEnabled[i] = false;
        }
        absorbEnabled[AbsorbType.ITEM.ordinal()] = true;
        this.mainNetworkNode.setBlockEntity(this);
        // 账本探针：把「此刻舱内真实存量」交给账本取基线（物品走缓存区、流体走流体缓存）
        this.flowLedger.setLiveProbe(this::flowLive);
        cache.addListener(container -> setChanged());
        upgrades.addListener(container -> {
            if (level != null && !level.isClientSide() && !loading) {
                enforceUpgradeCaps();
            }
            setChanged();
        });
    }

    public CollectionCacheNetworkNode getNode() {
        return mainNetworkNode;
    }

    public SimpleContainer getCache() {
        return cache;
    }

    public SimpleContainer getUpgradeContainer() {
        return upgrades;
    }

    /** 流体/气体缓存（容量上限、种类无上限）。 */
    public MultiFluidCache getFluidCache() {
        return fluidCache;
    }

    /** 流体缓存内容快照（供 S2C 同步给客户端展示；{@code fluid=true}、{@code amount} 为 mB）。 */
    public List<MarkerEntry> getFluidCacheEntries() {
        final List<MarkerEntry> entries = new ArrayList<>(fluidCache.getKinds());
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            entries.add(new MarkerEntry(true, entry.id(), entry.nbt(), entry.amount(), false, null));
        }
        return entries;
    }

    public long getCollectedTotal() {
        return collectedTotal;
    }

    public long getInsertedTotal() {
        return insertedTotal;
    }

    /** 收集范围 X 轴（格，1..{@link #getMaxCollectRadius()}）。 */
    public int getCollectRadiusX() {
        return clampRadius(radiusX);
    }

    /** 收集范围 Y 轴（格）。 */
    public int getCollectRadiusY() {
        return clampRadius(radiusY);
    }

    /** 收集范围 Z 轴（格）。 */
    public int getCollectRadiusZ() {
        return clampRadius(radiusZ);
    }

    /** 三轴中的最大范围（耗电 / 扫描频率按它计算）。 */
    public int getCollectRadius() {
        return Math.max(getCollectRadiusX(), Math.max(getCollectRadiusY(), getCollectRadiusZ()));
    }

    private int clampRadius(final int value) {
        return Math.max(MIN_COLLECT_RADIUS, Math.min(getMaxCollectRadius(), value));
    }

    /** 三轴范围上限：基础 {@link #MAX_COLLECT_RADIUS} + 每个范围升级 +{@code Config.rangeChargerRangePerUpgrade}；无限范围升级时为极大值。 */
    public int getMaxCollectRadius() {
        if (hasInfiniteRange()) {
            return Integer.MAX_VALUE - 1;
        }
        final long bonus = (long) countUpgrades(RANGE_UPGRADE) * Config.rangeChargerRangePerUpgrade;
        return (int) Math.min(Integer.MAX_VALUE - 1, MAX_COLLECT_RADIUS + bonus);
    }

    /** 是否装入了创造范围升级（三轴无限范围，与范围充电器保持一致）。 */
    public boolean hasInfiniteRange() {
        return countUpgrades(CREATIVE_RANGE_UPGRADE) > 0;
    }

    /** 某轴当前值（{@code axis}：0=X / 1=Y / 2=Z；越界按 X 处理）。 */
    public int getCollectRadius(final int axis) {
        return switch (axis) {
            case AXIS_Y -> getCollectRadiusY();
            case AXIS_Z -> getCollectRadiusZ();
            default -> getCollectRadiusX();
        };
    }

    /** 设置某一轴的收集范围（服务端权威；来自界面 ± / 输入框的 C2S 包）。 */
    public void setCollectRadius(final int axis, final int radius) {
        final int value = clampRadius(radius);
        switch (axis) {
            case AXIS_Y -> {
                if (value == radiusY) {
                    return;
                }
                radiusY = value;
            }
            case AXIS_Z -> {
                if (value == radiusZ) {
                    return;
                }
                radiusZ = value;
            }
            default -> {
                if (value == radiusX) {
                    return;
                }
                radiusX = value;
            }
        }
        setChanged();
    }

    /** 扫描半径（格）：本机配置的收集范围（三轴取各自的值）。 */
    public int getScanRadiusX() {
        return getCollectRadiusX();
    }

    public int getScanRadiusY() {
        return getCollectRadiusY();
    }

    public int getScanRadiusZ() {
        return getCollectRadiusZ();
    }

    /**
     * 三轴扫描半径对应的<b>世界包围盒</b> —— 本机「到底在扫哪片空间」的<b>唯一几何口径</b>。
     *
     * <h2>为什么必须是一个唯一入口</h2>
     * <p>旧实现里「实体查询」与「流体源游标」各写了一遍
     * {@code new AABB(worldPosition).inflate(getScanRadiusX(), getScanRadiusY(), getScanRadiusZ())}；
     * 本轮新增的「查看吸取范围」框若再写第三遍，任何一处改了另外两处都不会红 ——
     * 玩家看到的就是「框画在这里、实际吸那里」。现在三处共用本方法：</p>
     * <ul>
     *     <li>{@link #itemEntitiesInRadius} / {@link #experienceOrbsInRadius}：实体查询的范围；</li>
     *     <li>{@link #fluidSourcesInRadius}：游标按 {@code 2r+1} 展平，扫的正是本盒子里的每一格；</li>
     *     <li>{@link #sendCollectionRangeOutline}：S2C「查看范围」框的几何。</li>
     * </ul>
     *
     * <p><b>别和范围充电器混用口径</b>：充电器的 {@code rangeX/Y/Z} 是<b>直径</b>（扫描时
     * {@code range / 2}）；归流缓存仓的 {@code getScanRadiusX/Y/Z} 是<b>半径</b>，AABB 直接
     * {@code inflate(rx, ry, rz)}，即 {@code x ∈ [pos.x - rx, pos.x + rx]}（{@code 2r+1} 格）。</p>
     */
    protected AABB collectionRangeBox() {
        return new AABB(worldPosition)
            .inflate(getScanRadiusX(), getScanRadiusY(), getScanRadiusZ());
    }

    /**
     * 客户端点了「查看吸取范围」：把本机<b>也就是现在真正在扫的几何</b>组一份快照回给该玩家。
     *
     * <h2>为什么由服务端算、而不是客户端自己算</h2>
     * <p>客户端菜单是注册期二参工厂重建的（{@code block == null}）⇒ 拿不到方块坐标，更读不到
     * 三轴半径（那三个值虽然在 {@code ContainerData} 里，但「框的几何」若由客户端二次换算，
     * 就又多出一份可能与服务端漂移的公式）。这里直接把
     * {@link #collectionRangeBox()} 换算成方块区域下发，客户端只画不算。</p>
     *
     * <h2>四种口径（由 {@link #collectionRangeMode()} 决定；客户端只按它画）</h2>
     * <ul>
     *     <li>{@code MODE_RADIUS}（<b>只有基础版</b>）：下发<b>一个</b>扫描盒
     *     （{@code collectionRangeBox()}）；</li>
     *     <li>{@code MODE_AREAS}（高级版区域生效）：下发已去重的若干区域（区域<b>取代</b>半径）；</li>
     *     <li>{@code MODE_NO_AREA}（高级版：区域为空 / 维度不符）：<b>空表</b>，客户端一个框都不画
     *     —— 此时 {@code absorbAll} 整段跳过，本机一点不吸；</li>
     *     <li>{@code MODE_NO_SELECTOR}（高级版：没装区域选定器）：同样<b>空表不画框</b>
     *     —— 高级版没有选定器时也不吸（{@link #areaAbsorptionBlocked()} 恒真）。</li>
     * </ul>
     * <p>后两种都<b>绝不</b>降级成半径框：画半径框等于告诉玩家「这片地正在被吸」，而代码里
     * 一个都不吸（用户最反感的「界面说一套、代码做一套」）。</p>
     * <p><b>只读</b>：不改半径 / 不改区域 / 不触发扫描，也不写存档；调用方（菜单）已先校验
     * {@code stillValid(player)}（维度、距离、方块实体仍是它自己）。</p>
     */
    public void sendCollectionRangeOutline(final ServerPlayer player, final int containerId) {
        int mode = collectionRangeMode();
        List<Area> areas = List.of();
        if (mode == ShowCollectionRangePacket.MODE_RADIUS) {
            final Area box = areaOfBox(collectionRangeBox());
            areas = box == null ? List.of() : List.of(box);
        } else if (mode == ShowCollectionRangePacket.MODE_AREAS) {
            areas = collectionRangeAreas();
            if (areas.isEmpty()) {
                // 防御：口径说「画区域」却给不出区域（配置把上限调小 / 区域刚好被清空）
                // ⇒ 按「区域不可用」回，绝不退回半径框（那会谎报「正在吸半径范围」）。
                mode = ShowCollectionRangePacket.MODE_NO_AREA;
            }
        }
        PacketDistributor.sendToPlayer(player,
            new ShowCollectionRangePacket(containerId, mode, areas));
    }

    /**
     * 「查看吸取范围」的<b>口径</b>（服务端算，客户端只按它决定画什么）。
     *
     * <p>基础版恒 {@link ShowCollectionRangePacket#MODE_RADIUS}：它的吸收范围就是
     * {@link #collectionRangeBox()}（三轴半径膨胀盒，与实体 / 流体扫描逐字同源）。</p>
     * <p>高级版覆写为 {@code MODE_AREAS} / {@code MODE_NO_AREA} / {@code MODE_NO_SELECTOR}
     * —— <b>永远不返回 MODE_RADIUS</b>，因为它的 {@link #areaAbsorptionBlocked()} 在任何
     * 「区域不可用」的情形下都返回 {@code true}（{@code absorbAll} 整段跳过）：高级版<b>不存在</b>
     * 「按半径吸」的状态，画半径框就是谎报。</p>
     */
    protected int collectionRangeMode() {
        return ShowCollectionRangePacket.MODE_RADIUS;
    }

    /**
     * {@code MODE_AREAS} 口径下要画的区域（基础版用不到；高级版覆写成已去重的选定区域）。
     * <p>返回空表 ⇒ 发送方会把它降级成 {@code MODE_NO_AREA}（见 {@link #sendCollectionRangeOutline}）。</p>
     */
    protected List<Area> collectionRangeAreas() {
        return List.of();
    }

    /**
     * 包围盒 → 方块闭区间（{@code Area}）：S2C 快照用的坐标形态。
     *
     * <p>{@code Area#toAabb()} 是 {@code [min, max + 1)}，与 {@code new AABB(pos).inflate(...)}
     * 的上下界逐位一致（inflate 之后上下界本来就是整数格边），因此客户端
     * {@code toAabb()} 画出来的盒子 = 服务端扫描用的盒子，一像素不差。</p>
     *
     * <p>坐标先按世界边界 {@code Area.WORLD_LIMIT} 夹一次：创造范围升级可以把半径撑到
     * {@code Integer.MAX_VALUE - 1}，直接写进 {@code Area} 会在 int 上回绕成另一个象限的坐标
     * （<b>框会画到世界的另一边</b>）。夹紧后框仍覆盖玩家能看到的那一段，
     * 而不是画一个坐标错乱的框。</p>
     */
    @Nullable
    private static Area areaOfBox(@Nullable final AABB box) {
        if (box == null) {
            return null;
        }
        return new Area(
            clampWorldCoord(Math.floor(box.minX)),
            clampWorldCoord(Math.floor(box.minY)),
            clampWorldCoord(Math.floor(box.minZ)),
            clampWorldCoord(Math.ceil(box.maxX) - 1.0D),
            clampWorldCoord(Math.ceil(box.maxY) - 1.0D),
            clampWorldCoord(Math.ceil(box.maxZ) - 1.0D)
        );
    }

    /** 世界坐标夹到 {@code [-WORLD_LIMIT, WORLD_LIMIT]}（NaN / 极端值一律不产生回绕坐标）。 */
    private static int clampWorldCoord(final double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        final double limit = (double) Area.WORLD_LIMIT;
        return (int) Math.max(-limit, Math.min(limit, value));
    }

    /**
     * 扫描间隔：速度升级与收集范围都会让它变短（数值越小 = 吸取越快）。
     * <p><b>为什么先按范围降一档、再按速度做移位</b>（用户 1A 拍板：接受归流缓存仓扫描变快）：
     * 旧写法是倒数曲线 {@code base / ((1 + 速度数) * 半径档)}，是全工程唯一一条 {@code base / f(n)} 形状的公式
     * （I2 明确禁止这种形状：一旦分母被改动为 0 就是崩溃 / 除零），而且<b>永远到不了 1 tick</b>
     * （半径 1、满级 6：{@code 20/7 = 2}，「满级了还是每 2 tick 才扫一次」）。
     * 现在改成 {@code max(1, (base / 半径档) >> 速度数)}：无除法、满级正好 1 tick、
     * 与充电器/保持器的 {@code <<} 同一套移位直觉（见 {@link RsccRateLimiter}）。
     * <p>数值对照（半径 1）：改前 {@code 20,10,6,5,4,3,2} → 改后 {@code 20,10,5,2,1,1,1}；
     * 半径 5（档位 3）：改前 {@code 6,3,2,1,1,1,1} → 改后 {@code 6,3,1,1,1,1,1}。
     * 两个写法的公共部分来自恒等式 {@code floor(floor(x/r)/2^n) == floor(x/(r*2^n))}，
     * 因此低速度段（{@code 2^n} 还没超过商的量级时）逐格相同，差异只出现在高速度段
     * —— 那正是「用户 1A 接受的变快」。</p>
     */
    public int getScanInterval() {
        final int base = Math.max(1, Config.collectionCacheScanInterval);
        // 半径档位：范围每 +2 格提升一档。分母显式夹到 >= 1（旧形状遗留下来的除法，这里补上 I2 的保证）。
        final int radiusFactor = Math.max(1, 1 + (getCollectRadius() - 1) / 2);
        return RsccRateLimiter.intervalTicks(Math.max(1, base / radiusFactor), countUpgrades(SPEED_UPGRADE));
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    // ==================== 吸取开关（4 个） ====================

    /** 该来源的吸取是否启用。 */
    public boolean isAbsorbEnabled(final AbsorbType type) {
        return type != null && absorbEnabled[type.ordinal()];
    }

    /** 设置吸取开关（NBT 持久化 + setChanged()）。 */
    public void setAbsorbEnabled(final AbsorbType type, final boolean enabled) {
        if (type == null || absorbEnabled[type.ordinal()] == enabled) {
            return;
        }
        absorbEnabled[type.ordinal()] = enabled;
        setChanged();
    }

    // ==================== 「吸取所有物品」/「反转匹配」（第 7 轮新增） ====================

    /**
     * 「吸取所有物品」是否开启（服务端权威）。
     * <p>开启 = <b>匹配区整体失效</b>：物品来源不再看任何标记，半径内掉落物全收（受缓存容量约束，装不下的留在世界）。</p>
     */
    public boolean isCollectAll() {
        return collectAll;
    }

    /** 设置「吸取所有物品」（NBT 持久化 + setChanged()；由 {@code SetCollectionCollectAllPacket} 调用）。 */
    public void setCollectAll(final boolean enabled) {
        if (collectAll == enabled) {
            return;
        }
        collectAll = enabled;
        setChanged();
    }

    /**
     * 「反转匹配」是否开启（服务端权威）。
     * <p>开启 = 匹配区从<b>白名单</b>变<b>黑名单</b>：物品 / 流体来源改为收集「<b>没有</b>被任何标记命中的」资源。
     * <p><b>优先级</b>：{@link #isCollectAll()} 为真时匹配区整体失效，本开关不参与判定
     * （界面把「反转匹配」按钮显示为禁用态）。</p>
     */
    public boolean isInvertMatch() {
        return invertMatch;
    }

    /** 设置「反转匹配」（NBT 持久化 + setChanged()；由 {@code SetCollectionInvertMatchPacket} 调用）。 */
    public void setInvertMatch(final boolean enabled) {
        if (invertMatch == enabled) {
            return;
        }
        invertMatch = enabled;
        setChanged();
    }

    // ==================== 阻塞名单（逐个资源单独配置） ====================

    /** 该资源是否被阻塞（阻塞后本机收集/接收照旧，但绝不写回 RS 网络）。 */
    public boolean isBlocked(final boolean fluid, @Nullable final ResourceLocation id) {
        if (id == null) {
            return false;
        }
        return fluid ? blockedFluids.contains(id) : blockedItems.contains(id);
    }

    /**
     * 该物品堆是否被阻塞（按物品种类判定，忽略数据组件）。
     * <p><b>本轮修复（用户第 ③ 条）</b>：除「具体物品 id 名单」外，还要查<b>被阻塞的标签集合</b> ——
     * 玩家按标签（如「所有板子」）注册匹配时，阻塞也必须按标签生效，否则同族的其它板子
     * （金板 / 铁板 …）会被判定为「未阻塞」而瞬间回流进网络。判定落点就是这里，
     * 因为 {@code flushCacheToNetwork} 正是用它在决定「这一格要不要写回网络」。</p>
     */
    public boolean isBlockedStack(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final Item item = stack.getItem();
        if (isBlocked(false, BuiltInRegistries.ITEM.getKey(item))) {
            return true;
        }
        return itemMatchesAnyTag(item, blockedItemTags);
    }

    /** 该流体 / 气体是否被阻塞（具体 id 名单 ∪ 被阻塞的标签集合，与物品侧同一套语义）。 */
    public boolean isBlockedFluid(final ResourceLocation id) {
        if (id == null) {
            return false;
        }
        if (blockedFluids.contains(id)) {
            return true;
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return false;
        }
        for (final ResourceLocation tag : blockedFluidTags) {
            if (fluid.builtInRegistryHolder().is(TagKey.create(
                net.minecraft.core.registries.Registries.FLUID, tag))) {
                return true;
            }
        }
        return false;
    }

    /** 该物品是否带上被阻塞标签集合里的任意一个标签。 */
    private static boolean itemMatchesAnyTag(final Item item,
                                             final Set<ResourceLocation> tags) {
        if (item == null || item == Items.AIR || tags.isEmpty()) {
            return false;
        }
        for (final ResourceLocation tag : tags) {
            if (item.builtInRegistryHolder().is(TagKey.create(
                net.minecraft.core.registries.Registries.ITEM, tag))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 阻塞名单一次编辑的结果（第 117 轮）。
     * <p>为什么要有枚举而不是 boolean：上层（{@code SetCollectionBlockedPacket#handle}）必须能区分
     * 「什么都没变」（幂等，不该提示）与「<b>因为名单已满被整体拒绝</b>」（必须提示玩家，否则就是
     * 点了没反应 —— 本工程硬规则）。两种拒绝还要给不同的上限值（id 名单 vs 标签名单）。</p>
     */
    public enum BlockEditResult {
        /** 真的改了（加入 / 移除至少一条）。 */
        CHANGED,
        /** 合法请求但什么都没变（已经处于目标状态 / 空请求）。 */
        UNCHANGED,
        /** 具体 id 名单已满（上限 {@link CollectionBlockedLimits#MAX_BLOCKED_IDS}），<b>整条请求被拒、一格没写</b>。 */
        REJECTED_IDS_FULL,
        /** 按标签名单已满（上限 {@link CollectionBlockedLimits#MAX_BLOCKED_TAGS}），<b>整条请求被拒、一格没写</b>。 */
        REJECTED_TAGS_FULL
    }

    /**
     * 阻塞名单的<b>唯一写入口</b>（第 117 轮）：同时处理「具体资源 id」与「按标签」两个名单，
     * 带条数上限，且<b>整体原子</b> —— 只要有一项放不下，就什么都不写。
     *
     * <p>三条语义（与 {@link CollectionBlockedLimits} 的类注释同一口径）：</p>
     * <ol>
     *     <li><b>只约束增长</b>：{@code blocked = false}（解除阻塞）永远允许，无论名单多大；
     *     旧存档里超过上限的名单靠「删一些」恢复，绝不在读档时被截断。</li>
     *     <li><b>幂等不算超限</b>：已在名单里的条目重复阻塞必须继续成功 —— 否则玩家点第二下会看到
     *     「名单已满」这种莫名其妙的话。</li>
     *     <li><b>拒绝 = 一格不写</b>：id 放得下但标签放不下时，id 也不写。半写状态会让
     *     「界面上显示的和提示说的」对不上。</li>
     * </ol>
     *
     * @param fluid   {@code true} = 流体/气体名单，{@code false} = 物品名单（两个名单各自独立计数）
     * @param id      具体资源注册名；{@code null} = 本次只动标签名单
     * @param blocked {@code true} = 阻塞（增长），{@code false} = 解除（只减不增）
     * @param tags    按标签阻塞的标签集合；{@code null} / 空 = 不动标签名单
     */
    public BlockEditResult applyBlockedEdit(final boolean fluid, @Nullable final ResourceLocation id,
                                            final boolean blocked,
                                            @Nullable final List<ResourceLocation> tags) {
        final List<ResourceLocation> cleanTags = sanitizeBlockedTags(tags);
        if (id == null && cleanTags.isEmpty()) {
            return BlockEditResult.UNCHANGED;
        }
        final Set<ResourceLocation> idSet = fluid ? blockedFluids : blockedItems;
        final Set<ResourceLocation> tagSet = fluid ? blockedFluidTags : blockedItemTags;
        if (blocked) {
            // 先判 id 名单：放不下就整体拒绝，绝不先写 id 再回头判标签
            if (id != null && !CollectionBlockedLimits.fits(
                idSet, List.of(id), CollectionBlockedLimits.MAX_BLOCKED_IDS)) {
                return BlockEditResult.REJECTED_IDS_FULL;
            }
            // 再判标签名单：fits 已把「已存在的条目不占新名额」算进去
            if (!CollectionBlockedLimits.fits(
                tagSet, cleanTags, CollectionBlockedLimits.MAX_BLOCKED_TAGS)) {
                return BlockEditResult.REJECTED_TAGS_FULL;
            }
        }
        boolean changed = false;
        if (id != null) {
            changed |= blocked ? idSet.add(id) : idSet.remove(id);
        }
        for (final ResourceLocation tag : cleanTags) {
            changed |= blocked ? tagSet.add(tag) : tagSet.remove(tag);
        }
        if (changed) {
            setChanged();
            return BlockEditResult.CHANGED;
        }
        return BlockEditResult.UNCHANGED;
    }

    /**
     * 把客户端给的标签集合洗成「可以落盘的那种」：跳过 {@code null}、跳过超长
     * （{@link CollectionBlockedLimits#MAX_TAG_NAME_LENGTH}）、去重、保序。
     * <p>超长标签不落盘的理由：它不可能存在于任何注册表（AI 生成的 {@code tag} 在
     * {@link TagKey#create} 里也永远匹配不到东西），但会按原样进 NBT 并随同步包回发 ——
     * 纯负担。这里是唯一写入口，所以只判这一处即可，解码层不必重复判。</p>
     */
    private static List<ResourceLocation> sanitizeBlockedTags(@Nullable final List<ResourceLocation> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        final List<ResourceLocation> out = new ArrayList<>(Math.min(tags.size(), CollectionBlockedLimits.MAX_TAGS_PER_PACKET));
        for (final ResourceLocation tag : tags) {
            if (tag == null || tag.toString().length() > CollectionBlockedLimits.MAX_TAG_NAME_LENGTH) {
                continue;
            }
            if (!out.contains(tag)) {
                out.add(tag);
            }
            if (out.size() >= CollectionBlockedLimits.MAX_TAGS_PER_PACKET) {
                break;
            }
        }
        return out;
    }

    /**
     * 设置某资源的阻塞开关（服务端权威；界面经
     * {@code SetCollectionBlockedPacket} 调用）。返回是否真的发生了改变。
     * <p>第 117 轮起委托 {@link #applyBlockedEdit}，因此同样受条数上限约束（超限 = 拒绝 + 返回 false）。</p>
     */
    public boolean setBlocked(final boolean fluid, @Nullable final ResourceLocation id, final boolean blocked) {
        return applyBlockedEdit(fluid, id, blocked, null) == BlockEditResult.CHANGED;
    }

    /**
     * 设置一组标签的阻塞开关（服务端权威；供「按标签注册的匹配条目」使用）。
     * <p>与 {@link #setBlocked} 并列：两个名单都参与 {@link #isBlockedStack} / {@link #isBlockedFluid}
     * 的判定。空集合表示「不动标签名单」（保持既有行为）。返回是否真的发生了改变。</p>
     * <p>第 117 轮起委托 {@link #applyBlockedEdit}，因此同样受条数上限约束（超限 = 拒绝 + 返回 false）。</p>
     */
    public boolean setBlockedTags(final boolean fluid, @Nullable final List<ResourceLocation> tags,
                                  final boolean blocked) {
        return applyBlockedEdit(fluid, null, blocked, tags) == BlockEditResult.CHANGED;
    }

    /** 翻转某资源的阻塞开关，返回翻转后的状态。 */
    public boolean toggleBlocked(final boolean fluid, @Nullable final ResourceLocation id) {
        if (id == null) {
            return false;
        }
        final boolean next = !isBlocked(fluid, id);
        setBlocked(fluid, id, next);
        return next;
    }

    /** 被阻塞的物品 id 快照（供 S2C 同步给客户端做红色标识）。 */
    public List<ResourceLocation> getBlockedItems() {
        return new ArrayList<>(blockedItems);
    }

    /** 被阻塞的流体/气体 id 快照（供 S2C 同步）。 */
    public List<ResourceLocation> getBlockedFluids() {
        return new ArrayList<>(blockedFluids);
    }

    /** 被阻塞的<b>物品标签</b>快照（供 S2C 同步给客户端做红色标识）。 */
    public List<ResourceLocation> getBlockedItemTags() {
        return new ArrayList<>(blockedItemTags);
    }

    /** 被阻塞的<b>流体标签</b>快照（供 S2C 同步）。 */
    public List<ResourceLocation> getBlockedFluidTags() {
        return new ArrayList<>(blockedFluidTags);
    }

    private void writeBlocked(final CompoundTag tag) {
        final ListTag items = new ListTag();
        for (final ResourceLocation id : blockedItems) {
            items.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put(TAG_BLOCKED_ITEMS, items);
        final ListTag fluids = new ListTag();
        for (final ResourceLocation id : blockedFluids) {
            fluids.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put(TAG_BLOCKED_FLUIDS, fluids);
        // 两个标签名单同样落盘（旧存档无这两项 → 读档后为空集，行为与改动前一致）
        final ListTag itemTags = new ListTag();
        for (final ResourceLocation t : blockedItemTags) {
            itemTags.add(net.minecraft.nbt.StringTag.valueOf(t.toString()));
        }
        tag.put(TAG_BLOCKED_ITEM_TAGS, itemTags);
        final ListTag fluidTags = new ListTag();
        for (final ResourceLocation t : blockedFluidTags) {
            fluidTags.add(net.minecraft.nbt.StringTag.valueOf(t.toString()));
        }
        tag.put(TAG_BLOCKED_FLUID_TAGS, fluidTags);
    }

    /**
     * 读档：<b>不判上限、原样读回</b>（第 117 轮）。
     * <p>旧存档（或本轮之前被改包撑大的存档）里四个集合可能已经超过
     * {@link CollectionBlockedLimits} 的上限。这里截断就等于「玩家明确说过不要回流的那批资源
     * 突然又开始回流」—— 属于静默改变语义，比「超限的名单暂时不能再增长」严重得多。
     * 恢复路径是让玩家删几条（删除永远允许）；这是第 116 轮单元样板库复核定下的同一条规则
     * （读档不截断、任何增长不许越限）。</p>
     */
    private void loadBlocked(final CompoundTag tag) {
        blockedItems.clear();
        blockedFluids.clear();
        blockedItemTags.clear();
        blockedFluidTags.clear();
        readBlockedList(tag.getList(TAG_BLOCKED_ITEMS, Tag.TAG_STRING), blockedItems);
        readBlockedList(tag.getList(TAG_BLOCKED_FLUIDS, Tag.TAG_STRING), blockedFluids);
        readBlockedList(tag.getList(TAG_BLOCKED_ITEM_TAGS, Tag.TAG_STRING), blockedItemTags);
        readBlockedList(tag.getList(TAG_BLOCKED_FLUID_TAGS, Tag.TAG_STRING), blockedFluidTags);
    }

    private static void readBlockedList(final ListTag list, final Set<ResourceLocation> out) {
        for (int i = 0; i < list.size(); i++) {
            final ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null) {
                out.add(id);
            }
        }
    }

    // ==================== 多个输入面（可配置） ====================

    /** 该方向是否允许物流（漏斗 / 管道）把资源塞进本仓。 */
    public boolean isInputFace(@Nullable final net.minecraft.core.Direction direction) {
        return direction != null && (inputFaces & (1 << direction.ordinal())) != 0;
    }

    /** 六个面开关的快照（位 = {@link net.minecraft.core.Direction#ordinal()}），供界面与同步包使用。 */
    public int getInputFacesMask() {
        return inputFaces & ALL_FACES;
    }

    /** 设置某方向的输入开关（服务端权威，来自 {@code SetCollectionInputFacePacket}）。返回是否真的改变。 */
    public boolean setInputFace(@Nullable final net.minecraft.core.Direction direction, final boolean enabled) {
        if (direction == null) {
            return false;
        }
        final int bit = 1 << direction.ordinal();
        final int next = enabled ? (inputFaces | bit) : (inputFaces & ~bit);
        if ((next & ALL_FACES) == getInputFacesMask()) {
            return false;
        }
        inputFaces = next;
        setChanged();
        rscc$invalidateLogisticsCapabilities();
        return true;
    }

    /** 应用 S2C 同步来的开关位（客户端展示用；不落盘）。 */
    public void applyInputFacesMask(final int mask) {
        inputFaces = mask & ALL_FACES;
        rscc$invalidateLogisticsCapabilities();
    }

    /**
     * 让 Neoforge 的<b>能力缓存</b>作废（本轮修正，用户第 ⑤ 条：
     * 「有个可以选择面然后进行输入的功能，那个并没有做好：我明明全都选了，
     * 但放在旁边的舞台上的东西并没有被正确吸入」）。
     *
     * <p><b>根因</b>：物品 / 流体物流能力是经 {@code RegisterCapabilitiesEvent#registerBlockEntity}
     * 注册的，而 Neoforge 会按「方块实体 + 方向」把<b>上一次查询到的结果缓存起来</b>
     * （包括「这一面没有能力」时的 {@code null}）。{@link #isInputFace} 改的是本机字段、
     * <b>不改方块状态</b>，因此缓存永远不会被自动作废：玩家把某个面关掉（缓存里存了 {@code null}），
     * 再打开时缓存仍然返回 {@code null} —— 漏斗 / 管道依旧塞不进来，表现就是
     * 「面开关点了没反应 / 全都选了也吸不进去」。这里在每次改动后显式作废缓存，开关立刻生效。</p>
     *
     * <p>只读调用方（客户端 {@code loadUpdateTag}）调用也安全：{@code invalidateCapabilities()}
     * 只是清缓存，不改变任何库存；{@code level} 尚未设置（方块实体刚构造）时直接跳过。</p>
     */
    private void rscc$invalidateLogisticsCapabilities() {
        if (level != null) {
            invalidateCapabilities();
        }
    }

    // ==================== 经验目标（可配置字段 + 取值点） ====================

    public XpForm getXpForm() {
        return xpForm;
    }

    /**
     * 设置经验存储形态（可配置字段，服务端权威）。
     * <p><b>不可选的形态一律不收</b>：{@code null} 或「当前缺前置」的形态（如没装「机械动力：附魔工业」
     * 时的液态经验）都会退化为 {@link XpForm#ORB} —— 界面已置灰，服务端再拦一道，避免客户端绕过。</p>
     */
    public void setXpForm(final XpForm form) {
        final XpForm value = form == null || !form.selectable() ? XpForm.ORB : form;
        if (value == this.xpForm) {
            return;
        }
        this.xpForm = value;
        setChanged();
    }

    /** 注入经验目标解析器（可选依赖实现；null 恢复默认实现）。 */
    public void setXpTargetResolver(@Nullable final XpTargetResolver resolver) {
        this.xpTargetResolver = resolver == null ? XpTargetResolver.DEFAULT : resolver;
    }

    /**
     * 经验目标取值点：当前生效的液态经验流体 id（不适用 / 不可用返回 null）。
     * <p>只有 {@link XpForm#LIQUID} 才走<b>流体那条路</b>：收来的经验球按 1 点 = 1 mB 折算成
     * 液态经验存进<b>本仓流体缓存</b>（用户：「液态经验……归流体那一方面管的」）。
     * {@link XpForm#ORB} 不走流体，折算成经验颗粒物品（3 点 / 个），因此这里返回 null。</p>
     */
    @Nullable
    public ResourceLocation resolveXpFluidId() {
        return xpForm == XpForm.LIQUID ? availableXpFluidId() : null;
    }

    /**
     * 经验球折算成物品形态（{@link XpForm#ORB}）时使用的「经验颗粒」物品 id。
     * <p><b>它只是折算后的存储表示，不是收集对象</b>：收集对象永远是经验球实体
     * （用户要求：「我要求你收集的是经验这个实体（经验球）」）。3 经验点 / 个，见 {@code OptionalDeps}。</p>
     */
    @Nullable
    private ResourceLocation xpNuggetItemId() {
        final ResourceLocation id = xpTargetResolver.xpNuggetItem();
        return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id : null;
    }

    @Nullable
    private ResourceLocation availableXpFluidId() {
        final ResourceLocation id = xpTargetResolver.liquidXpFluid();
        return id != null && BuiltInRegistries.FLUID.containsKey(id) ? id : null;
    }

    // ==================== 匹配区（ghost 标记，容量 MATCH_CAPACITY） ====================

    /** 匹配区当前已分配的条目数（≈ 最高使用下标 + 1；随标记增长、清空尾部后收缩，上限 {@link #MATCH_CAPACITY}）。 */
    public int getMarkerCount() {
        return markers.size();
    }

    /** 取指定下标的标记条目（越界/未标记返回 {@link MarkerEntry#EMPTY}）。 */
    public MarkerEntry getMarkerEntry(final int index) {
        return getMarker(index);
    }

    /** 取指定下标的标记条目（越界/未标记返回 {@link MarkerEntry#EMPTY}）。 */
    public MarkerEntry getMarker(final int index) {
        if (index < 0 || index >= markers.size()) {
            return MarkerEntry.EMPTY;
        }
        final MarkerEntry entry = markers.get(index);
        return entry == null ? MarkerEntry.EMPTY : entry;
    }

    /**
     * 写入指定下标的标记条目（下标须落在 {@code [0, }{@link #MATCH_CAPACITY}{@code )} 内）。
     * <p>{@code entry} 为 null 或空条目等价于 {@link #clearMarker(int)}。</p>
     */
    public void setMarker(final int index, @Nullable final MarkerEntry entry) {
        if (index < 0 || index >= MATCH_CAPACITY) {
            return;
        }
        if (entry == null || entry.isEmpty()) {
            clearMarker(index);
            return;
        }
        ensureMarkerCapacity(index);
        markers.set(index, entry);
        setChanged();
    }

    /** 清除指定下标的标记条目（越界忽略）；尾部连续空条目一并收缩，保证 {@link #getMarkerCount()} 紧凑。 */
    public void clearMarker(final int index) {
        if (index < 0 || index >= markers.size()) {
            return;
        }
        markers.set(index, null);
        // 标记被清空后，它的「直接销毁」开关必须一起撤销：否则该下标被再次标记时会「莫名其妙地在销毁」
        destroyMarkers.remove(index);
        trimTrailingEmptyMarkers();
        setChanged();
    }

    /** 扩容到至少能容纳 {@code index}（新槽为未标记）。 */
    private void ensureMarkerCapacity(final int index) {
        while (markers.size() <= index) {
            markers.add(null);
        }
    }

    /** 存档读取专用：扩容后直接写入，不触发尾部收缩，避免读档期间反复 setChanged。 */
    private void putMarkerRaw(final int index, final MarkerEntry entry) {
        if (index < 0 || entry == null || entry.isEmpty()) {
            return;
        }
        ensureMarkerCapacity(index);
        markers.set(index, entry);
    }

    /** 去掉末尾连续的空条目，使 {@link #getMarkerCount()} 紧贴最高使用下标。 */
    private void trimTrailingEmptyMarkers() {
        for (int i = markers.size() - 1; i >= 0; i--) {
            final MarkerEntry entry = markers.get(i);
            if (entry != null && !entry.isEmpty()) {
                return;
            }
            markers.remove(i);
        }
    }

    /** 匹配区镜像槽内容：物品标记返回其展示物品，流体标记/空条目返回空栈。 */
    public ItemStack getMarkerStack(final int index) {
        final MarkerEntry entry = getMarkerEntry(index);
        return entry.fluid() ? ItemStack.EMPTY : entry.displayStack();
    }

    /** 该标记条目的需求量（未标记返回 0）。 */
    public long getMarkerAmount(final int index) {
        return getMarkerEntry(index).amount();
    }

    public boolean isMarkerMatchNbt(final int index) {
        return getMarkerEntry(index).matchNbt();
    }

    /** 该标记条目的匹配标签集合（空 = 不按标签匹配，只看具体物品）。 */
    public List<ResourceLocation> getMarkerTags(final int index) {
        return getMarkerEntry(index).tags();
    }

    /** 该标记是否按标签匹配（标签集合非空）。 */
    public boolean isMarkerTagFilter(final int index) {
        return getMarkerEntry(index).isTagFilter();
    }

    /** 匹配区最后一个已用下标（全空返回 -1）：供菜单计算滚动条最大偏移。 */
    public int highestUsedMarkerIndex() {
        for (int i = markers.size() - 1; i >= 0; i--) {
            final MarkerEntry entry = markers.get(i);
            if (entry != null && !entry.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 缓存区最后一个已用下标（全空返回 -1）：供菜单计算滚动条最大偏移。 */
    public int highestUsedCacheIndex() {
        // 遍历上限取「当前实际格数」：参与集群后缓存区会扩容（台数 × CACHE_SLOTS）
        for (int i = cache.getContainerSize() - 1; i >= 0; i--) {
            if (!cache.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 同一物品是否已被标记（同一物品只允许标记一次）。 */
    public boolean isItemMarked(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && isMarkerPresent(false, id, -1);
    }

    /** 同一流体是否已被标记。 */
    public boolean isFluidMarked(final ResourceLocation fluidId) {
        return fluidId != null && isMarkerPresent(true, fluidId, -1);
    }

    /** 同一资源是否已在其它格子被标记（{@code excludeIndex} 为 -1 时检查全部）。
     *  <p>标签过滤器不占具体物品位：它按 tag 匹配一整类，因此不参与「同一物品只能标记一次」的去重。</p> */
    private boolean isMarkerPresent(final boolean fluid, final ResourceLocation id, final int excludeIndex) {
        for (int i = 0; i < markers.size(); i++) {
            if (i == excludeIndex) {
                continue;
            }
            final MarkerEntry entry = markers.get(i);
            if (entry != null && !entry.isEmpty() && !entry.isTagFilter()
                && entry.fluid() == fluid && id.equals(entry.id())) {
                return true;
            }
        }
        return false;
    }

    /** 该资源是否已被某个「按标签匹配」的条目覆盖（命中物必须同时带上条目标签集合里的每一个标签）。 */
    public boolean isMarkerCoveredByTagFilter(final boolean fluid, final ResourceLocation id) {
        for (final MarkerEntry entry : markers) {
            if (entry == null || entry.isEmpty() || !entry.isTagFilter() || entry.fluid() != fluid) {
                continue;
            }
            if (fluid) {
                final Fluid candidate = BuiltInRegistries.FLUID.get(id);
                if (candidate != null && candidate != Fluids.EMPTY
                    && fluidHasAllTags(candidate, entry.tags())) {
                    return true;
                }
            } else {
                final Item item = BuiltInRegistries.ITEM.get(id);
                if (item != null && item != Items.AIR && itemHasAllTags(item, entry.tags())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 尝试在指定下标标记物品（ghost，不消耗物品）：
     * <ol>
     *     <li>该物品已标记过 → 不新增、不覆盖，直接返回 false；</li>
     *     <li>指定下标已被别的条目占用 → 改放到第一个空位；容量已满（{@link #MATCH_CAPACITY}）则返回 false；</li>
     *     <li>标记数量默认取<b>1</b>（见 {@link #tryAddMarker(int, ItemStack, long)} 的注释）。</li>
     * </ol>
     */
    public boolean tryAddMarker(final int index, final ItemStack stack) {
        return tryAddMarker(index, stack, MIN_UI_MARK_AMOUNT);
    }

    /**
     * 同上，显式指定需求数量（{@code amount}）。
     *
     * <h2>第 96 轮：界面写标记的默认数量从「手持件数」改成 <b>1</b></h2>
     * <p>用户对 beta.3 的原话是「归流缓存期现在还是无法与任何的物品」——查下来这是一条真实原因：
     * 这个方块的 {@code amount} 是<b>「凑够才动手」的硬门槛</b>（见
     * {@link #absorbMatchingItems(List, java.util.function.Predicate, long)}），
     * 而第 95 轮把 Shift+左键改成「写标记」之后，写进去的数量是<b>那一堆的件数</b>。
     * 于是「Shift 点一下手里 64 个鸡蛋」= 要求半径内同时出现 64 个鸡蛋才会吸——
     * 对农场、逐件投料这类来源，数量永远凑不满 ⇒ <b>件数少的资源一件都收不进去</b>，
     * 玩家看到的就是「跟任何物品都无法交互」。</p>
     * <p>现在的口径：界面写标记默认 {@code amount = }{@link MarkerEntry#MIN_ABSORB_DEMAND}（= 1）
     * ⇒「有就收」；需要「凑够 N 件再收」的老口径仍然保留，玩家可以在条目标记界面里把数量改成 N
     * （{@code setMarkerConfig} 会把门槛原样写进条目，吸收侧一个字都没改）。</p>
     */
    public boolean tryAddMarker(final int index, final ItemStack stack, final long amount) {
        if (stack.isEmpty()) {
            return false;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || isMarkerPresent(false, id, -1)) {
            return false;
        }
        if (isMarkerCoveredByTagFilter(false, id)) {
            return false; // 已被某个标签过滤器覆盖，无需再单独标记
        }
        final int target = pickMarkerSlot(index);
        if (target < 0) {
            return false;
        }
        final Level level = getLevel();
        final MarkerEntry candidate = MarkerEntry.item(stack, Math.max(MIN_UI_MARK_AMOUNT, amount),
            false, level == null ? null : level.registryAccess());
        // 第 114 轮：体积预算。单条过大（成书 / 带内容物的容器）或「整表 + 这一条」超过总量上限都不写。
        // 返回 false 而不是只打日志：调用方（界面）要靠它给「为什么没写进去」的提示。
        if (wouldExceedMarkerBudget(candidate)) {
            return false;
        }
        setMarker(target, candidate);
        return true;
    }

    /**
     * 第 114 轮：<b>体积预算</b>（权威常量见 {@link MarkerBudget}）。
     * <p>三层入口都收敛到这一个判定：单条上限 {@link MarkerBudget#MAX_ENTRY_BYTES}
     * + 整表上限 {@link MarkerBudget#MAX_TOTAL_BYTES}（按该机器<b>整个匹配区</b>估算 ——
     * 匹配区是随方块实体存档 / 随菜单同步的一整份数据）。</p>
     * <p><b>只拦新增 / 放大</b>：读档、旧档迁移写既有数据不走这里（那些路径的原则是不删玩家数据）。</p>
     */
    private boolean wouldExceedMarkerBudget(@Nullable final MarkerEntry candidate) {
        if (MarkerBudget.isEntryTooBig(candidate)) {
            return true;
        }
        return MarkerBudget.isTotalTooBig(markerTableBytes() + MarkerBudget.entryBytes(candidate, 0));
    }

    /** 当前匹配区所有标记编码后的字节合计（诊断 / 自检用；{@link MarkerBudget} 的口径）。 */
    public int markerTableBytes() {
        int total = 0;
        for (int i = 0; i < markers.size(); i++) {
            total += MarkerBudget.entryBytes(markers.get(i), i);
        }
        return total;
    }

    /**
     * 菜单专用入口：把一堆物品写成一条 ghost 匹配标记（<b>不消耗、不移动</b>该堆物品），
     * 并把失败原因分类返回 —— 界面据此给玩家不同提示（「点一下没反应」是最糟的反馈）。
     * <p>语义与 {@link #tryAddMarker(int, ItemStack)} 完全一致，只是把「为什么失败」暴露出来。</p>
     * <p><b>数量一律写 {@link #MIN_UI_MARK_AMOUNT}（= 1，有就收）</b>：见
     * {@link #tryAddMarker(int, ItemStack, long)} 的第 96 轮注释 —— 用「手持件数」当门槛会让
     * 逐件投料的资源永远收不进来。</p>
     */
    public MarkerAddResult addMarkerForUi(final ItemStack stack) {
        if (stack.isEmpty()) {
            return MarkerAddResult.INVALID;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return MarkerAddResult.INVALID;
        }
        if (isMarkerPresent(false, id, -1)) {
            return MarkerAddResult.DUPLICATE;
        }
        if (isMarkerCoveredByTagFilter(false, id)) {
            return MarkerAddResult.COVERED_BY_TAG;
        }
        // 第 114 轮：体积预算。界面要能区分「匹配区满」和「这个物品数据太大」，
        // 否则玩家看到的都是「点了没反应」。
        final Level budgetLevel = getLevel();
        final MarkerEntry budgetCandidate = MarkerEntry.item(stack, MIN_UI_MARK_AMOUNT, false,
            budgetLevel == null ? null : budgetLevel.registryAccess());
        if (wouldExceedMarkerBudget(budgetCandidate)) {
            return MarkerAddResult.TOO_COMPLEX;
        }
        return tryAddMarker(-1, stack, MIN_UI_MARK_AMOUNT) ? MarkerAddResult.OK : MarkerAddResult.FULL;
    }

    /** 写入 ghost 匹配标记的结果（供界面提示；{@link #OK} 以外都是「没写进去」）。 */
    public enum MarkerAddResult {
        /** 已写入。 */
        OK,
        /** 这一种物品已经在匹配区里了（同一物品只允许一条标记）。 */
        DUPLICATE,
        /** 已被某条「按标签匹配」的条目覆盖，无需再单独标记。 */
        COVERED_BY_TAG,
        /** 匹配区容量 {@link #MATCH_CAPACITY} 已满。 */
        FULL,
        /** 空堆 / 无法取到注册名（理论上不该发生）。 */
        INVALID,
        /**
         * 第 114 轮：<b>这个物品的数据太大</b>（成书 / 带内容物的容器 / 大 NBT 物品），
         * 或者「整张标记表 + 这一条」会超过 {@link MarkerBudget#MAX_TOTAL_BYTES}。
         * <p>单独一个分类是为了让界面说人话：以前这种情况会落到 {@link #FULL}（「匹配区满」），
         * 玩家会去删标记却永远腾不出空间 —— 明明只标了两三条。</p>
         */
        TOO_COMPLEX
    }

    /** 尝试在指定下标标记流体/气体（ghost，不消耗）。 */
    public boolean tryAddFluidMarker(final int index, final ResourceLocation fluidId) {
        if (fluidId == null || isMarkerPresent(true, fluidId, -1)) {
            return false;
        }
        if (isMarkerCoveredByTagFilter(true, fluidId)) {
            return false;
        }
        final int target = pickMarkerSlot(index);
        if (target < 0) {
            return false;
        }
        setMarker(target, MarkerEntry.fluidEntry(fluidId, 1L, false));
        return true;
    }

    /** 指定下标为空则用它，否则取第一个空位；容量已满返回 -1（写入方按失败处理）。 */
    private int pickMarkerSlot(final int index) {
        // 判空必须用 getMarker（对超出当前列表长度的下标返回 EMPTY）：玩家翻到后面的页点空格时，
        // 标记要落在「他点的那个格子」上，而不是被拉回第一页的第一个空位。
        if (index >= 0 && index < MATCH_CAPACITY && isEmptyMarker(getMarker(index))) {
            return index;
        }
        for (int i = 0; i < MATCH_CAPACITY; i++) {
            if (isEmptyMarker(getMarker(i))) {
                return i;
            }
        }
        return -1; // 容量已满：不再扩容
    }

    private static boolean isEmptyMarker(@Nullable final MarkerEntry entry) {
        return entry == null || entry.isEmpty();
    }

    /** 清除指定下标的标记条目。 */
    public void removeMarker(final int index) {
        clearMarker(index);
    }

    /**
     * 写入/更新指定下标的标记条目（服务端权威入口，来自
     * {@code SetCollectionMarkerConfigPacket} 与菜单编辑；下标越界自动扩容，上限 {@link #MATCH_CAPACITY}）：
     * <ul>
     *     <li>{@code id == null} → 清除该格；</li>
     *     <li>同资源同格 → 只更新数量与匹配规则（保留服务端持有的权威 NBT）；</li>
     *     <li>否则新建/替换（同资源已在别的格子出现过则忽略，避免重复标记）；</li>
     *     <li>{@code tags} 非空 → 该条目<b>按标签匹配一整类资源</b>（命中物须同时带上集合里的每个标签）；
     *     此时 {@code id} 仍是展示代表物（即玩家放入的示例物），且 {@code matchNbt} <b>仍然生效</b>。</li>
     * </ul>
     */
    public void setMarkerConfig(final int index, final boolean fluid, @Nullable final ResourceLocation id,
                               @Nullable final CompoundTag nbt, final long amount,
                               final boolean matchNbt, final List<ResourceLocation> tags) {
        if (index < 0 || index >= MATCH_CAPACITY) {
            return; // 超出匹配区容量：忽略（界面的页数上限就是由该容量决定的）
        }
        if (id == null) {
            clearMarker(index);
            return;
        }
        final List<ResourceLocation> effectiveTags = sanitizeTags(fluid, id, tags);
        final MarkerEntry existing = getMarker(index);
        if (!existing.isEmpty() && existing.fluid() == fluid && id.equals(existing.id())) {
            setMarker(index, new MarkerEntry(fluid, id, existing.nbt(), Math.max(1L, amount),
                matchNbt, effectiveTags));
            return;
        }
        if (isMarkerPresent(fluid, id, index)) {
            return;
        }
        final CompoundTag effectiveNbt = fluid || nbt == null ? new CompoundTag() : nbt;
        final MarkerEntry candidate = new MarkerEntry(fluid, id, effectiveNbt, Math.max(1L, amount),
            matchNbt, effectiveTags);
        // 第 114 轮：这是<b>服务端权威入口</b>，而 {@code nbt} 是客户端原样回传的不透明负载
        // （见 {@code SetCollectionMarkerConfigPacket}）。不校验的话，改包 / 旧客户端回传一条超大 nbt
        // 就能绕过所有界面拦截，把方块实体的同步数据撑到 MB 级。这里与界面入口同一套预算。
        if (wouldExceedMarkerBudget(candidate)) {
            LOGGER.warn("[rs_create_compat] 归流缓存仓 @{}：拒绝写入第 {} 条标记 {}"
                    + "（数据组件 {} B，单条上限 {} B / 整表上限 {} B）",
                worldPosition, index, id,
                MarkerBudget.entryBytes(candidate, 0), MarkerBudget.MAX_ENTRY_BYTES,
                MarkerBudget.MAX_TOTAL_BYTES);
            return;
        }
        setMarker(index, candidate);
    }

    /**
     * 设置某格的「匹配标签集合」：传入非空集合时该格改成按标签匹配一整类资源；
     * 传入空集合时取消标签匹配（回到只看这一个具体物品）。
     * <p>展示代表物保持为原来的 {@code id}（即玩家放入的示例物），因此不会出现「过滤器匹配不到东西」。</p>
     */
    public boolean setMarkerTag(final int index, final List<ResourceLocation> tags) {
        final MarkerEntry existing = getMarker(index);
        if (existing.isEmpty()) {
            return false;
        }
        setMarker(index, existing.withTags(sanitizeTags(existing.fluid(), existing.id(), tags)));
        return true;
    }

    /**
     * 把界面下发的标签集合收敛成「代表物自身确实带有」的标签。
     * <p><b>为什么必须收敛</b>：标签过滤器按 AND 判定（命中物须带上集合里的每一个标签）。
     * 一旦集合里混入代表物（示例物）并不自带的标签，这个集合就<b>永远无法被满足</b>——
     * 连示例物本身都命中不了，表现为「界面看起来是标签匹配，但橡木 / 云杉木板一条都吸不进来」。
     * 收敛后保证「示例物必然命中」，标签匹配不会再出现"死配置"。被丢弃的标签写入 DEBUG 日志便于排查。</p>
     */
    private static List<ResourceLocation> sanitizeTags(final boolean fluid, final ResourceLocation id,
                                                       @Nullable final List<ResourceLocation> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        final List<ResourceLocation> kept = new ArrayList<>(tags.size());
        for (final ResourceLocation tag : tags) {
            final boolean owned = fluid
                ? fluidIdHasTag(id, tag) : itemIdHasTag(id, tag);
            if (owned) {
                kept.add(tag);
            } else {
                LOGGER.debug("[rs_create_compat] 归流缓存仓：丢弃代表物 {} 不自带的匹配标签 {}（否则标签过滤器永远无法命中）",
                    id, tag);
            }
        }
        return List.copyOf(kept);
    }

    /** 注册名对应的物品是否带有该物品标签。 */
    private static boolean itemIdHasTag(final ResourceLocation id, final ResourceLocation tag) {
        final Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        return item != null && item != Items.AIR
            && item.builtInRegistryHolder().is(TagKey.create(net.minecraft.core.registries.Registries.ITEM, tag));
    }

    /** 注册名对应的流体是否带有该流体标签。 */
    private static boolean fluidIdHasTag(final ResourceLocation id, final ResourceLocation tag) {
        final Fluid fluid = id == null ? null : BuiltInRegistries.FLUID.get(id);
        return fluid != null && fluid != Fluids.EMPTY
            && fluid.builtInRegistryHolder().is(TagKey.create(net.minecraft.core.registries.Registries.FLUID, tag));
    }

    /** 该物品是否同时带有全部这些物品标签（客户端展示与服务端收集共用同一判定语义）。 */
    private static boolean itemHasAllTags(final Item item, final List<ResourceLocation> tags) {
        if (item == null || item == Items.AIR) {
            return false;
        }
        for (final ResourceLocation tag : tags) {
            if (!item.builtInRegistryHolder().is(
                TagKey.create(net.minecraft.core.registries.Registries.ITEM, tag))) {
                return false;
            }
        }
        return true;
    }

    /** 该流体是否同时带有全部这些流体标签。 */
    private static boolean fluidHasAllTags(final Fluid fluid, final List<ResourceLocation> tags) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return false;
        }
        for (final ResourceLocation tag : tags) {
            if (!fluid.builtInRegistryHolder().is(
                TagKey.create(net.minecraft.core.registries.Registries.FLUID, tag))) {
                return false;
            }
        }
        return true;
    }

    /** 只更新数量与匹配规则（保留既有资源）。 */
    public void setMarkerConfig(final int index, final long amount,
                               final boolean matchNbt, final List<ResourceLocation> tags) {
        final MarkerEntry existing = getMarkerEntry(index);
        if (existing.isEmpty()) {
            return;
        }
        setMarkerConfig(index, existing.fluid(), existing.id(), existing.nbt(), amount, matchNbt, tags);
    }

    /**
     * 供菜单同步给客户端的「当前可见窗口」标记列表：从 {@code start} 起取 {@code count} 条
     * （不足/越界以 {@link MarkerEntry#EMPTY} 补齐）。
     * <p>匹配区跨多页后不再整表同步，避免条目很多时产生包风暴（见 {@code SyncCollectionMarkersPacket}）。</p>
     */
    public List<MarkerEntry> getMarkerWindowEntries(final int start, final int count) {
        final List<MarkerEntry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            entries.add(getMarker(start + i));
        }
        return entries;
    }

    // ==================== 匹配判定 ====================

    /** 掉落物是否命中该物品标记条目（标签匹配 + NBT 严格匹配 / 同物品）。 */
    private static boolean matchesItem(final MarkerEntry marker, final ItemStack stack,
                                       final HolderLookup.Provider registries) {
        if (marker == null || marker.isEmpty() || marker.fluid() || stack.isEmpty()) {
            return false;
        }
        // ① 匹配标签：命中物必须同时带上标签集合里的每一个标签（AND），因此示例物必然命中
        if (marker.isTagFilter()) {
            if (!itemHasAllTags(stack.getItem(), marker.tags())) {
                return false;
            }
            // 标签与 NBT 规则可同时生效：勾选 NBT 时还要求数据组件与示例物一致
            return !marker.matchNbt()
                || MarkerEntry.decodeComponents(marker.nbt(), registries).equals(stack.getComponentsPatch());
        }
        final Item item = BuiltInRegistries.ITEM.get(marker.id());
        if (item == null || item == Items.AIR) {
            return false;
        }
        if (!stack.is(item)) {
            return false;
        }
        if (marker.matchNbt()) {
            return MarkerEntry.decodeComponents(marker.nbt(), registries).equals(stack.getComponentsPatch());
        }
        return true;
    }

    /** 世界流体方块是否命中该流体标记条目（标签匹配 + NBT 严格匹配 / 同流体）。 */
    private static boolean matchesFluid(final MarkerEntry marker, @Nullable final ResourceLocation candidateId) {
        if (marker == null || marker.isEmpty() || !marker.fluid() || candidateId == null) {
            return false;
        }
        final Fluid candidate = BuiltInRegistries.FLUID.get(candidateId);
        if (candidate == null || candidate == Fluids.EMPTY) {
            return false;
        }
        // ① 匹配标签：命中流体必须同时带上标签集合里的每一个标签
        if (marker.isTagFilter()) {
            return fluidHasAllTags(candidate, marker.tags());
        }
        if (!candidateId.equals(marker.id())) {
            return false;
        }
        // 世界流体方块没有数据组件：勾选 NBT 严格匹配时要求标记的组件为空
        return !marker.matchNbt() || marker.nbt() == null || marker.nbt().isEmpty();
    }

    private static boolean sharesTag(final Set<ResourceLocation> first, final Set<ResourceLocation> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        for (final ResourceLocation id : first) {
            if (second.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ResourceLocation> itemTags(final Item item) {
        final Set<ResourceLocation> tags = new HashSet<>();
        BuiltInRegistries.ITEM.wrapAsHolder(item).tags().forEach((TagKey<Item> tag) -> tags.add(tag.location()));
        return tags;
    }

    private static Set<ResourceLocation> fluidTags(final Fluid fluid) {
        final Set<ResourceLocation> tags = new HashSet<>();
        BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags().forEach((TagKey<Fluid> tag) -> tags.add(tag.location()));
        return tags;
    }

    // ==================== 收集 / 入网 ====================

    /**
     * 每 tick 的网络节点钩子（RS 的 {@code doWork()} 就是节点被 tick 的入口：
     * {@code AbstractBaseNetworkNodeContainerBlockEntity#doWork()} ⇒ {@code ticker.tick(mainNetworkNode)}，
     * 而 {@code NetworkNodeBlockEntityTicker#tick} 会<b>无条件</b>先更新激活态、再调用 {@code doWork()}）。
     *
     * <p><b>为什么必须在这里加诊断（第 95 轮用户反馈第 12 条）</b>：节点<b>未激活</b>时
     * {@link CollectionCacheNetworkNode#doWork()} 直接 return ⇒ {@link #tickCache} 一次都不会跑，
     * 于是既不吸收、也不回写，而且<b>一句日志都没有</b>。玩家看到的就是「机器摆着，东西不动」。
     * 未激活有三种成因（网络没电 / 红石模式停机 / 根本没接进 RS 网络），必须能区分开，
     * 否则「无法吸收」永远只能靠猜。所以这里在 {@code super.doWork()}（内部已经更新完激活态）之后
     * 按状态补一条 INFO：状态变化时立刻一条，之后同一原因每
     * {@link #FLUSH_WARN_INTERVAL_TICKS}（5 分钟）至多一条；重新激活时补一条恢复日志。</p>
     *
     * <p><b>本方法不改任何行为</b>：未激活时该不干活还是不干活（这是 RS 的能量语义，不是缺陷）。</p>
     */
    @Override
    public void doWork() {
        super.doWork();
        // 第 96 轮：`[rscc-collect]` 合并诊断。放在 super 之后、无条件执行 —— 未激活 / 没接网络时
        // 也必须能写出「一条路径都没动过」那一行，否则「机器摆着不动」就只能靠猜。
        flushCollectDiagnostic();
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (getNode().isActive()) {
            if (inactiveWarnKind != FLUSH_OK) {
                LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：节点已重新激活，恢复吸收 / 回写"
                        + "（此前连续 {} tick 未激活，原因：{}）。",
                    worldPosition, inactiveWorkTicks, flushReasonName(inactiveWarnKind));
                inactiveWarnKind = FLUSH_OK;
                inactiveWarnedAt = -1L;
            }
            inactiveWorkTicks = 0;
            return;
        }
        // 未激活：只有「本机本该在干活」（缓存里有待回写内容，或至少开了某个吸取开关）才值得提醒，
        // 否则一片空地上摆着的机器每 5 分钟说一句就是噪声。
        if (!hasFlushableContent() && !anyAbsorbEnabled()) {
            inactiveWorkTicks = 0;
            inactiveWarnKind = FLUSH_OK;
            inactiveWarnedAt = -1L;
            return;
        }
        if (inactiveWorkTicks < Integer.MAX_VALUE) {
            inactiveWorkTicks++;
        }
        if (inactiveWorkTicks < INACTIVE_WARN_TICKS_BEFORE_WARN) {
            return;
        }
        final int kind = classifyInactiveReason(level);
        final long now = level.getGameTime();
        final boolean stateChanged = kind != inactiveWarnKind;
        final boolean cooledDown = inactiveWarnedAt < 0L || now - inactiveWarnedAt >= FLUSH_WARN_INTERVAL_TICKS;
        if (!stateChanged && !cooledDown) {
            return;
        }
        inactiveWarnKind = kind;
        inactiveWarnedAt = now;
        LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：节点连续 {} tick 未激活 ⇒ 既不会吸收世界里的资源、"
                + "也不会把缓存写回网络。原因：{}。缓存里现有 {} 件（{} 堆）物品 / {} mB 流体，"
                + "它们安全留在本机缓存、不会丢失；恢复供电 / 红石信号 / 接回网络后会自动继续。"
                + "（同一原因每 5 分钟最多提醒一次）",
            worldPosition, inactiveWorkTicks, flushReasonName(kind),
            pendingItemTotal(), pendingItemStacks(), pendingFluidTotal());
    }

    /**
     * 记一笔「这条路径在本诊断窗口内<b>真的</b>搬动过资源」。
     * <p>只置位、不写日志 —— 日志由 {@link #flushCollectDiagnostic()} 每 5 秒合并成一条。
     * 调用点全是「资源真的动了」的分支（数量 &gt; 0），因此「日志里没出现某条路径」
     * 等价于「这条路 5 秒内一件都没搬动」，这正是玩家报「什么都收不进去」时最需要的信息。</p>
     */
    public void noteCollectPath(final int path) {
        collectDiagFlags |= path;
    }

    /**
     * 把本窗口的路径位集合合并成<b>一条</b> {@code [rscc-collect]} INFO（状态变化才写）。
     *
     * <h2>为什么是「状态变化才写」而不是每 5 秒一条</h2>
     * <p>一台正常工作的机器路径集合是稳定的（比如一直在 {@code 掉落物 + 回写网络}），
     * 每 5 秒一条就是 17280 条/天，正是用户反复投诉的刷屏。现在只有<b>集合变了</b>
     * （某条路径开始工作 / 停止工作）才写一条；第一次一定写，便于确认诊断本身在跑。</p>
     *
     * <p>行内同时给出「为什么可能一条都没动」的全部前置条件：节点激活态 / 是否接入网络 /
     * 标记条数 / 四个吸取开关 / 缓存里待回写的量 —— 这样玩家把日志贴回来就能定位到
     * 「界面没放进去」「没标记」「没开开关」「没接网络」「网络拒收」中的哪一种。</p>
     */
    private void flushCollectDiagnostic() {
        if (++collectDiagTicks < COLLECT_DIAG_INTERVAL_TICKS) {
            return;
        }
        collectDiagTicks = 0;
        final int flags = collectDiagFlags;
        collectDiagFlags = 0;
        if (flags == collectDiagLastFlags) {
            return; // 路径集合没变：一句都不说
        }
        collectDiagLastFlags = flags;
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        LOGGER.info("[rscc-collect] @{} {} {} 标记={} 开关[物品={} 流体={} 气体={} 经验={}] "
                + "缓存={}件/{}堆 流体={}mB | 本窗口({}t)动过的路径=[{}] 未动过=[{}]",
            worldPosition,
            getNode().isActive() ? "节点=激活" : "节点=未激活",
            getNode().getNetworkOrNull() == null ? "网络=未接入" : "网络=已接入",
            getMarkerCount(),
            isAbsorbEnabled(AbsorbType.ITEM) ? "开" : "关",
            isAbsorbEnabled(AbsorbType.FLUID) ? "开" : "关",
            isAbsorbEnabled(AbsorbType.GAS) ? "开" : "关",
            isAbsorbEnabled(AbsorbType.EXPERIENCE) ? "开" : "关",
            getCacheItemTotal(), getCacheItemStacks(), fluidCache.getStored(),
            COLLECT_DIAG_INTERVAL_TICKS,
            pathNames(flags), pathNames(PATH_ALL & ~flags));
    }

    /** 把路径位渲染成中文短名（空集合返回「无」）。 */
    private static String pathNames(final int flags) {
        final String[] names = {
            "界面放入", "界面取出", "写标记", "掉落物", "相邻容器", "世界流体", "气体", "经验", "回写网络"
        };
        final StringBuilder out = new StringBuilder();
        for (int bit = 0; bit < names.length; bit++) {
            if ((flags & (1 << bit)) == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(names[bit]);
        }
        return out.length() == 0 ? "无" : out.toString();
    }

    /** 本机是否至少开着一个吸取开关（用于决定「未激活是否值得提醒」）。 */
    private boolean anyAbsorbEnabled() {
        for (final AbsorbType type : AbsorbType.values()) {
            if (isAbsorbEnabled(type)) {
                return true;
            }
        }
        return false;
    }

    /** 未激活的成因分类（决定告警文案，也用于「原因一变立刻再告警一条」）。 */
    private int classifyInactiveReason(final Level level) {
        if (getNode().getNetworkOrNull() == null) {
            return FLUSH_NO_NETWORK;
        }
        if (hasRedstoneMode()) {
            final com.refinedmods.refinedstorage.common.support.RedstoneMode mode = getRedstoneMode();
            if (mode != null && mode != com.refinedmods.refinedstorage.common.support.RedstoneMode.IGNORE
                && !mode.isActive(level.hasNeighborSignal(worldPosition))) {
                return FLUSH_INACTIVE_REDSTONE;
            }
        }
        return FLUSH_INACTIVE_ENERGY;
    }

    /** 归流缓存仓每 tick 的网络节点驱动：按间隔扫描吸取 + 缓存写入网络。 */
    public void tickCache(final Network network) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (workCooldown > 0) {
            workCooldown--;
        }
        if (--scanCooldown <= 0) {
            scanCooldown = getScanInterval();
            absorbAll(level);
        }
        // 「直接销毁」匹配槽：在回网之前先把命中的资源销毁（有意销毁，走账本 destroyed 记账）
        destroyMarkedContent();
        flushCacheToNetwork(network);
    }

    /**
     * <b>区域扫描钩子</b>（高级版覆盖；基础版恒 {@code null}）。
     * <p>非 null = 本机处于「只按区域吸收」模式：掉落物 / 经验球的实体查询改用
     * {@link AreaScanHook#entityQueryAreas()} 的<b>每簇一个包围盒</b> + {@link AreaScanHook#containsEntity}
     * 过滤（区域全相邻时只有 1 个盒，与第 121 轮之前的总并集一次查询等价），
     * 流体源遍历改用 {@link AreaScanHook#scanFluidWindow} 的跨 tick 游标。
     * 基础版返回 null ⇒ 三段扫描逐字走原来的半径路径（行为与改动前完全一致）。</p>
     */
    @Nullable
    protected AreaScanHook areaScanHook() {
        return null;
    }

    /**
     * 区域模式是否<b>禁止本机吸收</b>（高级版覆盖）。
     * <p>基础版恒 {@code false}；高级版在「没装选定器 / 区域为空 / 维度不符」时返回 {@code true}，
     * 于是 {@link #absorbAll} 整段跳过 —— 这是「区域取代 XYZ」的硬保证：绝不悄悄退回半径吸收。</p>
     */
    protected boolean areaAbsorptionBlocked() {
        return false;
    }

    /**
     * 某个掉落物实体该用<b>哪一组</b> ghost 过滤集合（第 101 轮 P1 的扩展点）。
     *
     * <p>基础版：恒返回<b>全局匹配区</b> {@link #markers}（同一份列表对象 ⇒ 吸收侧把它们归成一个上下文，
     * 行为与「逐条标记对全体掉落物」逐字一致）。</p>
     * <p>高级版（覆写）：返回该实体<b>所在区域</b>的过滤集合：
     * <ul>
     *     <li>不在任何生效区域 ⇒ 返回 {@code null} / 空列表 ⇒ <b>这个实体不吸</b>（区域模式的安全默认）；</li>
     *     <li>所在区域<b>没配任何过滤条目</b> ⇒ 同样空列表 ⇒ 该区域<b>不吸任何东西</b>
     *     —— 这是解决用户「相邻机器重复吸放」的关键口径（原话：
     *     「某物品是原料但又在另一步骤里作为输出，就会重叠——重复地吸收-放出」）。</li>
     * </ul>
     *
     * <p>返回的列表<b>必须是稳定对象</b>：吸收侧用引用相等（{@code ==}）把实体分桶，
     * 每次调用都新建列表会让分桶退化成「每个实体一个桶」（数量门槛按单件统计，语义就错了）。</p>
     */
    @Nullable
    protected List<MarkerEntry> filtersForEntity(final net.minecraft.world.entity.Entity entity,
                                                 final HolderLookup.Provider registries) {
        return markers;
    }

    /** 4 个来源共用同一入口，各自受开关控制。 */
    private void absorbAll(final Level level) {
        // 每轮重置「本轮为什么没吸」的统计（状态行 / 诊断都读它）
        thresholdBlockedThisScan = false;
        absorbedThisScan = 0;
        // 高级版：区域模式已启用（装了选定器）但当前<b>没有可用区域</b>（区域为空 / 维度不符 / 没装）
        // ⇒ 本机一点不吸。这里刻意是「整段返回」而不是「退回 XYZ 半径」：
        // 用户拍板「区域取代 XYZ」，没有区域就没有吸收范围（设计文档 §4.4 的交集方案已被取代）。
        if (areaAbsorptionBlocked()) {
            absorbStatusReason = absorbStatusOverride();
            absorbStatusArg = 0;
            return;
        }
        if (isAbsorbEnabled(AbsorbType.ITEM)) {
            collectFromDroppedItems(level);
            // <b>用户第 3 条：从相邻方块容器「主动抽取」物品并回网</b>（不只等漏斗 / 管道推入）。
            // 受选面约束：只有被选中的那一面才抽（见 isInputFace）。
            collectFromAdjacentContainers(level);
        }
        if (isAbsorbEnabled(AbsorbType.FLUID)) {
            collectFromWorldFluids(level);
        }
        if (isAbsorbEnabled(AbsorbType.GAS)) {
            collectGas(level);
        }
        if (isAbsorbEnabled(AbsorbType.EXPERIENCE)) {
            collectExperience(level);
            // 开关打开 ⇒ 复位「关着但有球」的告警状态：下次再关掉时立刻重新说一句（状态变化才记）。
            experienceOffWarned = false;
            experienceOffWarnedAt = Long.MIN_VALUE;
        } else {
            noteExperienceOffButOrbsNearby(level);
        }
        absorbStatusReason = computeAbsorbStatus();
        absorbStatusArg = thresholdBlockedThisScan ? 1 : 0;
    }

    /**
     * 本轮「为什么没吸到物品」的状态码（服务端算，界面只显示一行对应的语言键）。
     * <p>用户本轮原话是「界面特别丑、分不清什么是什么」，其中最关键的一格信息是
     * <b>「它到底为什么不吸」</b>。这里把所有能导致「一件都没吸」的前置条件按优先级排成一条链，
     * 任何一条不满足就返回对应的码 —— 玩家不用再猜「是没标记 / 没电 / 缓存满 / 区域没生效」。</p>
     */
    private int computeAbsorbStatus() {
        final CollectionCacheNetworkNode node = getNode();
        if (node.getNetworkOrNull() == null) {
            return ABSORB_STATUS_NO_NETWORK;
        }
        if (!node.isActive()) {
            return ABSORB_STATUS_INACTIVE;
        }
        // 区域模式的状态由高级版覆写给出（未装选定器 / 区域为空 / 维度不符），优先级高于「没标记」
        final int areaReason = absorbStatusOverride();
        if (areaReason != ABSORB_STATUS_OK) {
            return areaReason;
        }
        if (!isAbsorbEnabled(AbsorbType.ITEM)) {
            return ABSORB_STATUS_TOGGLE_OFF;
        }
        if (absorbedThisScan > 0) {
            return ABSORB_STATUS_OK;
        }
        if (thresholdBlockedThisScan) {
            return ABSORB_STATUS_THRESHOLD;
        }
        if (!hasAnyEffectiveFilter()) {
            return ABSORB_STATUS_NO_MARKER;
        }
        if (freeCacheSlots() <= 0L) {
            return ABSORB_STATUS_CACHE_FULL;
        }
        return ABSORB_STATUS_NO_TARGET;
    }

    /** 区域相关的原因码（基础版恒 {@link #ABSORB_STATUS_OK}；高级版覆写为「没装选定器 / 区域为空 / 维度不符」）。 */
    protected int absorbStatusOverride() {
        return ABSORB_STATUS_OK;
    }

    /** 是否<b>至少有一条</b>真正可用的过滤条目（物品类、非空、非流体）。基础版 = 全局匹配区；高级版按区域覆写。 */
    protected boolean hasAnyEffectiveFilter() {
        for (final MarkerEntry marker : markers) {
            if (marker != null && !marker.isEmpty() && !marker.fluid()) {
                return true;
            }
        }
        return false;
    }

    /** 本轮状态码（界面数据槽；见 {@link #computeAbsorbStatus()}）。 */
    public int getAbsorbStatus() {
        return absorbStatusReason;
    }

    /** 本轮状态附加位：1 = 有匹配但没凑够某条标记的数量门槛（界面显示「还差 N 件」用）。 */
    public int getAbsorbStatusArg() {
        return absorbStatusArg;
    }


    /**
     * 「经验」吸取开关关着、但半径内<b>确实有经验球</b>时的提醒。
     *
     * <p><b>2026-10-25（第 95 轮用户反馈第 3 条）</b>：旧实现是「无条件每 200 次扫描一条」，用户实机日志里
     * 从 16:00 连刷到 23:1x（{@code diag/pull_2310/latest.log} 118 条 + {@code 2026-10-24-3.log} 2571 条同类），
     * 把真正有用的日志全淹了。现在的口径：</p>
     * <ol>
     *     <li><b>检查本身</b>仍节流到 {@link #EXPERIENCE_OFF_CHECK_INTERVAL_TICKS}（10 秒）一次 ——
     *         每 tick 查一次半径内实体太贵；</li>
     *     <li><b>半径内没有球 / 开关被打开</b> ⇒ 静默复位，什么都不写；</li>
     *     <li><b>第一次</b>发现「关着但有球」⇒ 立刻一条 INFO（不能一句不说：玩家需要知道为什么不吸）；</li>
     *     <li>同一状态持续 ⇒ 之后每 {@link #EXPERIENCE_OFF_WARN_INTERVAL_TICKS}（5 分钟）至多一条。</li>
     * </ol>
     * <p>也就是说：<b>状态变化时一条 + 之后 5 分钟一条</b>，两者合起来既不会刷屏、也不会静默。</p>
     */
    private void noteExperienceOffButOrbsNearby(final Level level) {
        final long now = level.getGameTime();
        if (experienceOffCheckedAt != Long.MIN_VALUE
            && now - experienceOffCheckedAt < EXPERIENCE_OFF_CHECK_INTERVAL_TICKS) {
            return;
        }
        experienceOffCheckedAt = now;
        if (experienceOrbsInRadius(level).isEmpty()) {
            // 半径内没球了：复位，下次真出现球时立刻说一句
            experienceOffWarned = false;
            experienceOffWarnedAt = Long.MIN_VALUE;
            return;
        }
        if (experienceOffWarned && now - experienceOffWarnedAt < EXPERIENCE_OFF_WARN_INTERVAL_TICKS) {
            return;
        }
        experienceOffWarned = true;
        experienceOffWarnedAt = now;
        LOGGER.info("[rs_create_compat] 归流缓存仓 @{} 检测到半径内有经验球，但「经验」吸取开关是关闭的"
            + " ⇒ 不会收集。请打开该开关（界面里那一排开关，最后一个是「经验」）。"
            + "（状态变化或每 5 分钟最多提醒一次；此前的实现是每 10 秒一条，会把日志刷满）",
            worldPosition);
    }

    /** 掉落物来源：逐条物品标记凑够数量后吸取，优先入缓存（装不下的留在世界，不销毁）。 */
    private void collectFromDroppedItems(final Level level) {
        final List<ItemEntity> entities = itemEntitiesInRadius(level);
        if (entities.isEmpty()) {
            return;
        }
        final HolderLookup.Provider registries = level.registryAccess();
        boolean changed = false;
        if (collectAll) {
            // 「吸取所有物品」优先：匹配区（含反转）整体失效 —— 半径内掉落物全收。
            // 阈值用「不限」而不是某条标记的数量：既然是「不过滤、全收」，就不该再被标记门槛卡住。
            changed |= absorbMatchingItems(entities, stack -> true, ABSORB_ALL_THRESHOLD) > 0;
        } else if (invertMatch) {
            // 「反转匹配」：匹配区变黑名单 —— 只收「没有被任何物品标记命中」的掉落物。
            changed |= absorbMatchingItems(entities, stack -> !matchesAnyItemMarker(stack, registries),
                ABSORB_ALL_THRESHOLD) > 0;
        } else {
            // 逐实体决定「用哪一组 ghost 过滤集合」：
            //   基础版 = 全局匹配区（filtersForEntity 直接返回 markers，全体实体同一组，行为与改动前逐字一致）；
            //   高级版 = 该实体<b>所在区域</b>的过滤集合（第 101 轮 P1：每个区域可以吸不同的东西）。
            // 「凑够 N 件才吸」的数量门槛因此按「同一组过滤集合」内部统计，而不是整半径混算。
            final List<List<MarkerEntry>> contexts = new ArrayList<>(2);
            final List<List<ItemEntity>> buckets = new ArrayList<>(2);
            for (final ItemEntity entity : entities) {
                final List<MarkerEntry> filters = filtersForEntity(entity, registries);
                if (filters == null || filters.isEmpty()) {
                    continue; // 不在任何生效区域 / 所在区域没配过滤 ⇒ 不吸（安全默认，见 filtersForEntity 注释）
                }
                int bucket = -1;
                for (int c = 0; c < contexts.size(); c++) {
                    if (contexts.get(c) == filters) {
                        bucket = c;
                        break;
                    }
                }
                if (bucket < 0) {
                    contexts.add(filters);
                    buckets.add(new ArrayList<>());
                    bucket = buckets.size() - 1;
                }
                buckets.get(bucket).add(entity);
            }
            for (int c = 0; c < contexts.size(); c++) {
                final List<MarkerEntry> filters = contexts.get(c);
                final List<ItemEntity> bucket = buckets.get(c);
                for (int i = 0; i < filters.size(); i++) {
                    final MarkerEntry marker = filters.get(i);
                    if (marker == null || marker.isEmpty() || marker.fluid()) {
                        continue;
                    }
                    final int absorbed = absorbMatchingItems(bucket,
                        stack -> matchesItem(marker, stack, registries), marker.amount());
                    changed |= absorbed > 0;
                    // 标签过滤器「看得见却吸不到」时给一行不刷屏的 DEBUG 日志：把「命中该标签的物品堆 / 件数 / 数量门槛」
                    // 一次列全，用来区分「半径内根本没有这一类物品」与「有这一类物品但没凑够数量门槛」。
                    if (absorbed <= 0 && marker.isTagFilter() && !bucket.isEmpty() && --tagDebugCooldown <= 0) {
                        tagDebugCooldown = 60;
                        long hitCount = 0L;
                        int hitStacks = 0;
                        for (final ItemEntity entity : bucket) {
                            if (entity.isRemoved() || isIgnored(entity)) {
                                continue;
                            }
                            final ItemStack stack = entity.getItem();
                            if (!stack.isEmpty() && matchesItem(marker, stack, registries)) {
                                hitStacks++;
                                hitCount += stack.getCount();
                            }
                        }
                        LOGGER.debug("[rs_create_compat] 归流缓存仓标签过滤器本轮未收集：上下文={} tags={} 数量门槛={} "
                                + "该范围内掉落物={} 堆 / 其中命中该标签={} 堆共 {} 件",
                            c, marker.tags(), marker.amount(), bucket.size(), hitStacks, hitCount);
                    }
                }
            }
        }
        if (changed) {
            noteCollectPath(PATH_DROP);
            setChanged();
            // 成就触发点：本机刚把掉落物吸进缓存（服务端吸收流程；重复 fire 由原版去重，不会重复弹 toast）
            cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onCollectedFromWorld(this);
        }
    }

    /**
     * <b>相邻方块容器来源（用户第 3 条：主动从方块中获取物品并收回网络）</b>。
     *
     * <h2>为什么要「主动抽」而不是等推入</h2>
     * <p>旧实现只接受漏斗 / 管道<b>推</b>进来的物品（那走的是本机对外的 {@code IItemHandler} 能力）。
     * 用户要求的是相反方向：归流缓存仓<b>主动</b>把相邻方块容器里的东西取走、送回网络
     * （原话：「从方块中获取物品并把它收回网络中的能力还是没好」）。</p>
     *
     * <h2>口径（与掉落物来源同源，只多一条「选面」约束）</h2>
     * <ul>
     *     <li><b>只有被选中的面才抽</b>：逐面查 {@link #isInputFace(Direction)}，未选中的面一律不碰
     *     —— 这就是用户说的「只有被选中的面才抽」；</li>
     *     <li>取物品的匹配口径与掉落物完全一致（{@link #acceptsFromContainer}：
     *     「吸取所有」优先 / ⌈反转匹配⌋做黑名单 / 常规按物品标记命中）；</li>
     *     <li><b>守恒（不复制 / 不销毁）</b>：先<b>原子抽取</b>（真实移出源容器）→ 再塞进本机缓存 →
     *     缓存装不下的余量<b>原样还回源容器</b>，源容器也收不下时才掉在世界里（绝不静默销毁）；</li>
     *     <li><b>逐步回网</b>：抽进来的东西只是进入本机缓存，之后由 {@link #flushCacheToNetwork} 按处理速率
     *     （每 tick「处理组数」×「单次吞吐」）逐 tick 写回网络；被阻塞的条目照旧留在缓存里不回网。</li>
     * </ul>
     * <p>每面每轮最多搬 {@link #getTransferBatch()} 件，因此抽取速率与回网速率同阶，不会一次性把容器吸空、
     * 也不会产生与进度不匹配的跳变。只搬运、不生成：本方法从不凭空造物品。</p>
     */
    private void collectFromAdjacentContainers(final Level level) {
        if (inputFaces == 0) {
            return; // 六面一个都没选：不抽任何一面（受选面约束）
        }
        final int transfer = Math.max(1, getTransferBatch());
        final HolderLookup.Provider registries = level.registryAccess();
        boolean changed = false;
        for (final Direction direction : Direction.values()) {
            if (!isInputFace(direction)) {
                continue; // 只有被选中的面才抽
            }
            final BlockPos source = worldPosition.relative(direction);
            if (!level.isLoaded(source)) {
                continue;
            }
            final net.neoforged.neoforge.items.IItemHandler handler =
                RsccChamberImportStrategy.itemHandlerAt(level, source);
            if (handler == null) {
                continue; // 那一侧不是容器：什么都不做（不报错、不刷屏）
            }
            int moved = 0;
            for (int slot = 0; slot < handler.getSlots() && moved < transfer; slot++) {
                final ItemStack inSlot = handler.getStackInSlot(slot);
                if (!acceptsFromContainer(inSlot, registries)) {
                    continue;
                }
                final int want = Math.min(inSlot.getCount(), transfer - moved);
                final ItemStack extracted = handler.extractItem(slot, want, false); // 原子抽取（真实移出）
                if (extracted.isEmpty()) {
                    continue;
                }
                final ItemStack leftover = insertIntoCache(extracted.copy());
                final int accepted = extracted.getCount() - leftover.getCount();
                if (!leftover.isEmpty()) {
                    // 缓存装不下：余量原样还回源容器（刚刚抽取过，通常放得回去）；实在放不回去才掉在容器旁
                    final ItemStack back = handler.insertItem(slot, leftover, false);
                    if (!back.isEmpty()) {
                        Block.popResource(level, source, back);
                    }
                }
                if (accepted <= 0) {
                    continue;
                }
                moved += accepted;
                changed = true;
                // 账本：从相邻方块容器抽进来的也算「从世界收集」（本仓的资源来源不止 RS 网络）
                flowLedger.fromWorld(RsccFlowLedger.itemKey(extracted.getItem()), accepted);
            }
        }
        if (changed) {
            noteCollectPath(PATH_CONTAINER);
            setChanged();
            markWork();
        }
    }

    /**
     * 这一份物品是否属于「本机该主动从相邻容器抽走」的那一类。
     * <p>匹配口径与 {@link #collectFromDroppedItems} 逐条同源（只是容器里的整堆不再需要凑数量门槛）：
     * 「吸取所有」→ 全收；「反转匹配」→ 只收没被任何物品标记命中的；常规 → 收被物品标记命中的。
     * 不在此处排除「阻塞项」——与掉落物一致：阻塞只决定「不写回网络」，内容仍留在本机缓存里。</p>
     */
    private boolean acceptsFromContainer(final ItemStack stack, final HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return false;
        }
        final boolean marked = matchesAnyItemMarker(stack, registries);
        if (collectAll) {
            return true;
        }
        if (invertMatch) {
            return !marked;
        }
        return marked;
    }

    /**
     * 该物品堆是否被<b>任意一条</b>物品标记命中（不含流体标记）。
     * <p>「反转匹配」用它做黑名单判定：命中任意一条 = 被排除，只有「一条都不命中」才收集。</p>
     */
    private boolean matchesAnyItemMarker(final ItemStack stack, final HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return false;
        }
        for (final MarkerEntry marker : markers) {
            if (marker != null && !marker.isEmpty() && !marker.fluid()
                && matchesItem(marker, stack, registries)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该流体是否被<b>任意一条</b>流体标记命中（不含物品标记）。
     * <p>「反转匹配」用它做黑名单判定（与物品侧完全同一套语义）。</p>
     */
    private boolean matchesAnyFluidMarker(final ResourceLocation candidateId) {
        if (candidateId == null) {
            return false;
        }
        for (final MarkerEntry marker : markers) {
            if (marker != null && !marker.isEmpty() && marker.fluid()
                && matchesFluid(marker, candidateId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 世界中流体源方块来源：一格 = 1000 mB（只认 source，flowing / falling 一律不计）。
     * <p><b>本轮修复（真正的根因）</b>：标记里的 {@code amount} 在这里<b>不再是「凑够才动手」的硬门槛</b>，
     * 而是「每轮最多抽走多少 mB」的上限。旧实现先把半径内所有源方块折算之和与 {@code amount} 比较、
     * 不达标就直接返回 0 —— 玩家在匹配设置里把数量填成几万 mB 之后，哪怕机器旁边就是一片水，
     * 也<b>一格都不会被收走</b>（用户现象：流体检测 / 收回完全没反应）。世界流体天生是「一格一格」的，
     * 必须按格收、按上限节流，而不能拿整片水域的总量当门槛。</p>
     */
    private void collectFromWorldFluids(final Level level) {
        final List<BlockPos> sources = fluidSourcesInRadius(level);
        boolean changed = false;
        int fluidMarkers = 0;
        // 「吸取所有物品」/「反转匹配」与物品侧共用同一套优先级：
        //   ① 吸取所有物品（collectAll）→ 匹配区整体失效，半径内所有源流体全抽（每轮不限上限，受流体缓存约束）；
        //   ② 反转匹配（invertMatch）→ 匹配区当黑名单，只抽「没有被任何流体标记命中」的源流体；
        //   ③ 否则 → 老行为：逐条流体标记、按该标记的数量上限节流。
        if (collectAll) {
            changed = absorbMatchingFluidBlocks(level, sources, id -> true, ABSORB_ALL_THRESHOLD) > 0;
        } else if (invertMatch) {
            changed = absorbMatchingFluidBlocks(level, sources, id -> !matchesAnyFluidMarker(id),
                ABSORB_ALL_THRESHOLD) > 0;
        } else {
            for (int i = 0; i < markers.size(); i++) {
                final MarkerEntry marker = markers.get(i);
                if (marker == null || marker.isEmpty() || !marker.fluid()) {
                    continue;
                }
                fluidMarkers++;
                final int collected = absorbMatchingFluidBlocks(level, sources,
                    id -> matchesFluid(marker, id), marker.amount());
                changed |= collected > 0;
                // 首次成功收走源方块：打一条 INFO（可验证「服务端真的在收」，之后不再打，绝不刷屏）
                if (collected > 0 && !fluidCollectLogged) {
                    fluidCollectLogged = true;
                    LOGGER.info("[rs_create_compat] 归流缓存仓流体收集生效：slot={} fluid={} 半径({},{},{}) "
                            + "内可抽源方块={} 本轮收走={}格 · 流体缓存={}mB/{}mB",
                        i, marker.id(), getScanRadiusX(), getScanRadiusY(), getScanRadiusZ(),
                        sources.size(), collected, fluidCache.getStored(), fluidCacheCapacity);
                }
            }
        }
        // 「流体标记了却没有吸到」时给一行不刷屏的 DEBUG：把判定要素一次性列全（半径内可抽源方块数 / 命中数 /
        // 缓存余量 / 开关 / 数量上限），便于区分「范围内没有 source 方块」「缓存已满」「标记没命中」「根本没开流体开关」。
        if (fluidMarkers > 0 && !changed && --fluidDebugCooldown <= 0) {
            fluidDebugCooldown = 60;
            LOGGER.debug("[rs_create_compat] 归流缓存仓流体本轮未收集：流体标记={} 首个标记={} 数量上限={}mB "
                    + "半径({},{},{}) 内可抽源方块={} 其中命中首个标记={} 流体缓存剩余={}mB 吸取开关(流体)={}",
                fluidMarkers, firstFluidMarkerId(), firstFluidMarkerAmount(),
                getScanRadiusX(), getScanRadiusY(), getScanRadiusZ(), sources.size(),
                countMatchingSources(level, sources), fluidCache.getFreeSpace(),
                isAbsorbEnabled(AbsorbType.FLUID));
        }
        if (changed) {
            noteCollectPath(PATH_FLUID);
            setChanged();
            // 成就触发点：本机刚抽走世界里的源流体（服务端吸收流程；重复 fire 由原版去重）
            cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onCollectedFluid(this);
        }
    }

    /** 第一个流体标记的流体注册名（没有则返回 null）：供流体路径日志使用。 */
    @Nullable
    private ResourceLocation firstFluidMarkerId() {
        for (final MarkerEntry marker : markers) {
            if (marker != null && !marker.isEmpty() && marker.fluid()) {
                return marker.id();
            }
        }
        return null;
    }

    /** 第一个流体标记的数量上限（mB；没有则返回 0）：供流体路径日志使用。 */
    private long firstFluidMarkerAmount() {
        for (final MarkerEntry marker : markers) {
            if (marker != null && !marker.isEmpty() && marker.fluid()) {
                return marker.amount();
            }
        }
        return 0L;
    }

    /** 半径内能被「第一个流体标记」命中的源方块数（仅用于日志诊断；每次只在节流后调用）。 */
    private int countMatchingSources(final Level level, final List<BlockPos> sources) {
        for (final MarkerEntry marker : markers) {
            if (marker == null || marker.isEmpty() || !marker.fluid()) {
                continue;
            }
            int hit = 0;
            for (final BlockPos pos : sources) {
                if (matchesFluid(marker, fluidSourceIdAt(level, pos))) {
                    hit++;
                }
            }
            return hit;
        }
        return 0;
    }

    /**
     * 气体来源入口（与其它三种共用一个入口与缓存/回流管线）。
     * <p><b>本机没有可扫描的气体来源</b>，因此这里不产生具体条目（方法体留空是事实，不是「未实现」）：
     * 气体资源类型由第三方集成提供（见 {@code docs/GAS_COMPAT_DESIGN.md} 与
     * {@code support/RsccGenericResourceTarget}；接收器 / 请求器已经能按
     * {@code PlatformResourceKey} 搬运气体），但「世界上哪里有一团能被吸取的气体」本机不扫。</p>
     * <p>将来若接入来源，只需在本方法内塞进同一个入口即可 —— 缓存与回流管线无需改动。</p>
     */
    private void collectGas(final Level level) {
        // 预留入口：见方法注释
    }

    /**
     * 经验来源（服务端权威）。
     * <h2>收集对象 = 经验球实体；形态只决定「折算成颗粒还是流体」（用户要求）</h2>
     * <p>用户原话：「你好像是收集这个<b>经验颗粒</b>，我要求你收集的是<b>经验这个实体</b>（经验球）」，
     * 以及本轮：「勾上之后呢，它是把这个<b>经验球直接转换成这个流体</b>，直接转换成这一个<b>液态经验</b>，
     * 然后<b>储存起来</b>」「它本质上它还是这种<b>流体</b>……所以说他是<b>归流体那一方面管的</b>」。</p>
     * <p>因此两种形态的<b>收集对象完全相同</b>（都是 {@link net.minecraft.world.entity.ExperienceOrb}），
     * 差别只在折算目标：</p>
     * <ul>
     *     <li>{@link XpForm#ORB}：经验点 → 经验颗粒物品（3 点 / 个，机械动力原版倍率）→ 物品缓存 → 网络物品存储；</li>
     *     <li>{@link XpForm#LIQUID}：经验点 → <b>液态经验流体</b>（1 点 = 1 mB，附魔工业的换算）→
     *     <b>本仓流体缓存</b>（见 {@link #FLUID_CACHE_CAPACITY}）→ 由
     *     {@code flushFluidCacheToNetwork} 进网络的流体存储。</li>
     * </ul>
     * <p><b>本轮修正的错误语义</b>：此前 {@code LIQUID} 只扫「世界里的液态经验<b>源方块</b>」，
     * 且只在 {@code LIQUID} 时才收球 —— 于是勾了液态之后经验球一颗都不动（正是用户报告的
     * 「还是经验颗粒没有动」）。现在液态形态的主力路径就是「球 → 流体 → 流体缓存」，
     * 世界源方块那条路保留为<b>附带</b>来源（一格 = 1000 mB，与流体开关同一口径），不再是全部。</p>
     *
     * <h2>范围与权限</h2>
     * <ul>
     *     <li>范围 = 本机配置的三轴扫描半径（{@link #getScanRadiusX()} 等，AABB 三轴分别膨胀），
     *     <b>范围外一律不动</b>；</li>
     *     <li>服务端权威：本方法只由 {@code tickCache}（服务端 tick）调用，客户端不参与；
     *     缓存装不下时留 20 tick 的忽略窗口（{@link #markIgnored}），避免每轮空转刷屏；</li>
     *     <li>守恒：经验球只按「实际进了缓存的点数 / 流体量」扣减（{@code orb.value}），装不下的部分
     *     留在球里（点数归零的球才被移除）——绝不无中生有、也绝不销毁经验。</li>
     * </ul>
     * <p>两种表示都走既有的缓存 → 网络回流管线，因此对玩家来说就是「经验进了网络」。</p>
     */
    private void collectExperience(final Level level) {
        boolean changed = false;
        int fluidCollected = 0;
        int pointsCollected = 0;
        // 液态经验目标（流体那一侧的目标 id）：形态 = LIQUID 且注册表里真有该流体时才非 null。
        final ResourceLocation fluidId = resolveXpFluidId();
        // 世界里的液态经验源方块：LIQUID 形态下的附带来源（一格 = 1000 mB）。
        // 它不再是液态形态的全部 —— 「球 → 液态经验 → 流体缓存」才是勾上之后的主语义。
        final List<BlockPos> sources = fluidId == null ? List.of() : fluidSourcesInRadius(level);
        if (fluidId != null) {
            fluidCollected = absorbMatchingFluidBlocks(level, sources, id -> id.equals(fluidId), 0L);
            changed |= fluidCollected > 0;
        }
        // 经验球实体：两种形态都收（形态只决定折算成颗粒还是流体，不影响「收不收球」）。
        final List<net.minecraft.world.entity.ExperienceOrb> orbs = experienceOrbsInRadius(level);
        if (!orbs.isEmpty()) {
            pointsCollected = absorbExperienceOrbs(orbs, fluidId);
            changed |= pointsCollected > 0;
        }
        if (changed) {
            noteCollectPath(PATH_XP);
            if (!experienceCollectLogged) {
                experienceCollectLogged = true;
                LOGGER.info("[rs_create_compat] 归流缓存仓经验收集生效：形态={} 液态目标={} "
                        + "半径({},{},{}) 本轮收走 经验球点数={}（液态形态下 1 点 = 1 mB，已折算进流体缓存）"
                        + " / 液态源方块={} 格",
                    xpForm, fluidId, getScanRadiusX(), getScanRadiusY(), getScanRadiusZ(),
                    pointsCollected, fluidCollected);
            }
            setChanged();
            // 成就触发点：本机刚收进经验（经验球 / 液态经验；服务端流程，重复 fire 由原版去重）
            cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onCollectedExperience(this);
            return;
        }
        // 「经验开关开着却什么都没收到」时给一行不刷屏的日志：把判定要素一次列全
        // （形态 / 目标 id / 半径内经验球数与命中数 / 可抽液态源方块数与命中数 / 缓存余量），
        // 便于区分「形态选错」「半径内没有该类资源」「缓存放不下」。
        //
        // <b>2026-10-05：节流从 60 tick 放宽到 1200 tick，并且「偶发空转」不再记录。</b>
        // 实测日志里同一句话打了 <b>370 条</b>（全是 {@code 半径内经验球=0 命中=0}）——
        // 用户明确要求「禁止废话」，这条属于典型的无信息量刷屏。
        // 现在只在<b>连续空转</b>（说明确实一直收不到）时每 1 分钟提示一次。
        if (orbs.isEmpty() && sources.isEmpty()) {
            experienceEmptyStreak++;
        } else {
            experienceEmptyStreak = 0;
        }
        if (--experienceDebugCooldown <= 0) {
            experienceDebugCooldown = 1200;
            if (experienceEmptyStreak < 20) {
                return; // 只是这一轮没东西可收（正常）⇒ 不打日志
            }
            int hitOrbs = 0;
            for (final net.minecraft.world.entity.ExperienceOrb orb : orbs) {
                if (!orb.isRemoved() && !isIgnored(orb)) {
                    hitOrbs++;
                }
            }
            int hitSources = 0;
            for (final BlockPos pos : sources) {
                if (fluidId.equals(fluidSourceIdAt(level, pos))) {
                    hitSources++;
                }
            }
            LOGGER.info("[rs_create_compat] 归流缓存仓经验本轮未收集：形态={} 液态目标={} "
                    + "半径内经验球={} 命中={} 可抽液态源方块={} 命中={} 流体缓存剩余={}mB 物品缓存剩余格={}",
                xpForm, fluidId, orbs.size(), hitOrbs, sources.size(), hitSources,
                fluidCache.getFreeSpace(), freeCacheSlots());
        }
    }

    /** 半径内的经验球实体（三轴范围与掉落物来源同源；只取未移除、未被忽略窗口挡住的）。 */
    private List<net.minecraft.world.entity.ExperienceOrb> experienceOrbsInRadius(final Level level) {
        // 区域模式：与掉落物同一条口径（第 121 轮 C-7 起按簇拆成多个盒、每簇一次查询；
        // 每簇盒向上扩 1 格 + containsEntity 过滤。第 101 轮 P0 修复，见 itemEntitiesInRadius 的注释：
        // 只比 blockPosition() 会把「站在区域底板上」的实体整批判到区域外）
        final AreaScanHook areas = areaScanHook();
        if (areas != null) {
            final List<AABB> boxes = areas.entityQueryAreas();
            if (boxes.isEmpty()) {
                return List.of();
            }
            // 簇盒之间可能相交 ⇒ 集合去重；顺序稳定保证吸收顺序不抖。
            final Set<net.minecraft.world.entity.ExperienceOrb> found = new java.util.LinkedHashSet<>();
            for (final AABB box : boxes) {
                found.addAll(level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box,
                    entity -> !entity.isRemoved() && !isIgnored(entity)));
            }
            found.removeIf(entity -> !areas.containsEntity(entity));
            return new ArrayList<>(found);
        }
        // 几何与掉落物 / 流体 / 「查看范围」框同源（collectionRangeBox()），不在这里重写一遍 inflate
        final AABB area = collectionRangeBox();
        return level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, area,
            entity -> !entity.isRemoved() && !isIgnored(entity));
    }

    /**
     * 把经验球折算并入缓存（<b>绝不销毁经验</b>）。
     * <p>每个球按「实际入缓存的经验点数」扣减 {@code orb.value}：点数归零 → 移除该球；装不下 →
     * 留 20 tick 忽略窗口并在球里保留剩余点数（下次再收）。返回本轮入缓存的经验点数。</p>
     * <p>两种折算目标（都走本机缓存 → 网络回流管线）：</p>
     * <ul>
     *     <li>{@code fluidId != null}（形态 = {@link XpForm#LIQUID}）：
     *     <b>1 经验点 = 1 mB 液态经验</b>（{@link OptionalDeps#CEI_MB_PER_EXPERIENCE_POINT}，
     *     出处：附魔工业 {@code ExperienceHelper.java:63-65} 与 {@code CEIDataMaps.java:148}）
     *     ⇒ 直接 {@code fluidCache.insert(...)}，进流体存储；</li>
     *     <li>{@code fluidId == null}（形态 = {@link XpForm#ORB}，或液态流体缺失时的降级）：
     *     <b>3 经验点 / 个</b>经验颗粒（{@link OptionalDeps#CREATE_EXPERIENCE_POINTS_PER_NUGGET}，
     *     出处：{@code CEIDataMaps.java:154} 与 {@code ExperienceNuggetItem.java:37-38}，取整数个），
     *     余数照旧按下面的既有纪律处理（不足一个颗粒时删球，避免同一份经验被结算两次）。</li>
     * </ul>
     * <p>换算守恒：插入量 = 扣减量（颗粒侧按 {@code acceptedNuggets * pointsPerNugget} 扣、
     * 流体侧按「缓存真的接收的 mB」折算回点数再扣），任何一侧都不可能凭空产生或吞掉经验。</p>
     *
     * @param fluidId 可用的液态经验流体 id（null = 走经验颗粒物品折算）
     */
    private int absorbExperienceOrbs(final List<net.minecraft.world.entity.ExperienceOrb> orbs,
                                     @Nullable final ResourceLocation fluidId) {
        // 折算目标：液态经验（1 点 = 1 mB，与附魔工业的换算一致），否则经验颗粒（3 点 / 个）。
        final int pointsPerNugget = OptionalDeps.CREATE_EXPERIENCE_POINTS_PER_NUGGET;
        final int mbPerPoint = OptionalDeps.CEI_MB_PER_EXPERIENCE_POINT;
        final ResourceLocation nuggetId = fluidId != null ? null : xpNuggetItemId();
        final Item nugget = nuggetId == null ? null : BuiltInRegistries.ITEM.get(nuggetId);
        final boolean canStoreItems = nugget != null && nugget != Items.AIR;
        if (fluidId == null && !canStoreItems) {
            return 0; // 两种表示都不可用：静默跳过（不报错、不动任何经验球）
        }
        int pointsIn = 0;
        for (final net.minecraft.world.entity.ExperienceOrb orb : orbs) {
            if (orb.isRemoved() || isIgnored(orb)) {
                continue;
            }
            final int value = orb.getValue();
            if (value <= 0) {
                orb.discard();
                continue;
            }
            if (fluidId != null) {
                // 液态经验：先按换算率算出「这个球能换多少 mB」，再由缓存决定真的收下多少。
                // 不计入 collectedTotal —— 与「流体按格计数」的既有口径一致（collectedTotal 只统计物品件数）。
                final long mbOffered = (long) value * mbPerPoint;
                final long acceptedMb = fluidCache.insert(fluidId, new CompoundTag(), mbOffered);
                if (acceptedMb <= 0L) {
                    markIgnored(orb); // 当前缓存放不下：短暂忽略，避免每轮空转
                    continue;
                }
                // 只扣「真的进了缓存的点数」：收多少扣多少（换算率非 1 时多收的零头退回缓存，绝不凭空产生流体）。
                final int pointsAccepted = (int) Math.min(value, acceptedMb / mbPerPoint);
                if (pointsAccepted <= 0) {
                    markIgnored(orb);
                    continue;
                }
                final long storedMb = (long) pointsAccepted * mbPerPoint;
                if (storedMb < acceptedMb) {
                    fluidCache.extract(fluidId, new CompoundTag(), acceptedMb - storedMb);
                }
                orb.value -= pointsAccepted;
                pointsIn += pointsAccepted;
                // 账本：液态经验来自世界中（的经验球），记「从世界收集」一侧
                flowLedger.fromWorld(RsccFlowLedger.fluidKey(BuiltInRegistries.FLUID.get(fluidId)), storedMb);
            } else {
                // 无液态经验流体：折算成经验颗粒物品（取整数个），余数留在球里下次再收（不销毁）
                final int nuggets = value / pointsPerNugget;
                if (nuggets <= 0) {
                    // <b>不足 1 个颗粒（&lt; {@code pointsPerNugget} 点）</b>：
                    // 2026-10-05 用户实测「转换之后经验球实体没有被删除 ⇒ 玩家又吸一份 ⇒ 刷经验」。
                    //
                    // 旧写法是 {@code continue}（把球留在世界里）。后果有两个：
                    //   ① 那个球<b>永远达不到 1 个颗粒</b>（点数不再增长），于是永久停在原地；
                    //   ② 玩家点一下就能把它吸走 —— 而这一小块点数<b>对应的经验早就转到网络里了</b>
                    //      （前几轮把 3 的倍数收走、余数留着），于是同一块经验被结算两次。
                    // 因此这里改成<b>直接删球</b>：余数不足一个颗粒、无法表示成物品，
                    // 留着只会变成可刷取的残留。账本不受影响（本次实际入网量 = 0，见 pointsIn）。
                    LOGGER.info("[rs_create_compat] 经验球余数不足一个颗粒，已移除（不再滞留可被重复吸取）："
                            + "剩余={} 每颗粒点数={} 位置={}",
                        value, pointsPerNugget, orb.blockPosition().toShortString());
                    orb.discard();
                    continue;
                }
                final ItemStack leftover = insertIntoCache(new ItemStack(nugget, nuggets));
                final int acceptedNuggets = nuggets - leftover.getCount();
                if (acceptedNuggets <= 0) {
                    // <b>经验缓存放不下</b>：这一份经验<b>一个字节都没进网络</b>，
                    // 因此绝不能删球（删了就真的销毁经验）。只做短暂忽略、下次再试。
                    markIgnored(orb);
                    continue;
                }
                final int consumed = acceptedNuggets * pointsPerNugget;
                orb.value -= consumed;
                pointsIn += consumed;
                // 账本：折算出的经验颗粒同样来自世界（的经验球）
                flowLedger.fromWorld(RsccFlowLedger.itemKey(nugget), acceptedNuggets);
                collectedTotal += acceptedNuggets;
            }
            if (orb.getValue() <= 0) {
                orb.discard(); // 点数已全部入网：移除这个球（不是「销毁经验」，经验已进网络）
            } else if (orb.getValue() < value) {
                // <b>2026-10-05 用户实测「转换之后经验球实体没被删除 ⇒ 玩家又吸一份 ⇒ 刷经验」</b>。
                //
                // 走到这里说明：<b>这一轮确实从球里收走了点数</b>，但球里还有余额（3 点/颗粒取整后的余数
                // &lt; {@code pointsPerNugget}），于是球被留下「下次再收」。
                // 而那份已收走的经验<b>已经在缓存里 / 已在回网路上</b> —— 球还在世界 ⇒ 玩家或漏斗
                // 可以再吸一次 ⇒ 同一份经验被算两次。
                //
                // 这条日志只在真的发生「扣了点却没删球」时打，用来区分：
                // ① 点数没被扣（那 bug 在扣减）② 扣了但没删（bug 在删除条件）③ 球已被别处移除。
                LOGGER.info("[rs_create_compat] 经验球未删除（余数留置）：原始值={} 本轮收走={} 剩余={} "
                        + "已移除={} 位置={}",
                    value, value - orb.getValue(), orb.getValue(), orb.isRemoved(),
                    orb.blockPosition().toShortString());
            }
            markWork();
        }
        return pointsIn;
    }

    /** 物品缓存当前还能接收的「格数」估计（仅用于诊断日志）。 */
    private long freeCacheSlots() {
        long free = 0L;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            if (cache.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private List<ItemEntity> itemEntitiesInRadius(final Level level) {
        // 高级版（区域模式）：第 121 轮 C-7 起按「簇」分组 —— 每簇一个 AABB 各查一次实体表，
        // 再按绝对坐标精确过滤。绝不「每个区域查一次」（16 次查询 = 16 倍代价，设计文档 §4.2），
        // 也绝不「拿跨 1000 格的总并集查一次」（中间全是空区块，白扫上千个区块段）。
        // 区域全相邻时簇 = 1 个盒 ⇒ 与旧版「总并集查一次」逐字等价。
        final AreaScanHook areas = areaScanHook();
        if (areas != null) {
            // <b>第 101 轮 P0 修复</b>：查询盒由 {@link AreaScanHook#entityQueryAreas()} 给出（每簇盒向上扩 1 格），
            // 过滤用 {@link AreaScanHook#containsEntity}（占用的格 / 所站的格 / 被卡的上方格）。
            // 旧写法 `getEntitiesOfClass(union)` + `contains(entity.blockPosition())` 会把「落在区域底板上」
            // 的掉落物整批丢掉：实体 AABB 下界恰好等于并集盒上界（相切不算相交），且整数格坐标在底板上一格。
            // 注意方向是<b>向上</b>扩（详见 AreaScanHook#entityQueryArea 的注释）。
            // 玩家最常见的「两角都点在地面」的 1 格高区域因此一件都吸不到（流体却正常，因为流体扫的是方块本身）。
            final List<AABB> boxes = areas.entityQueryAreas();
            if (boxes.isEmpty()) {
                return List.of();
            }
            // 簇盒之间可能相交（贪心聚类不保证互斥）⇒ 用 LinkedHashSet 去重，同时保持遍历顺序稳定。
            final Set<ItemEntity> found = new java.util.LinkedHashSet<>();
            for (final AABB box : boxes) {
                found.addAll(level.getEntitiesOfClass(ItemEntity.class, box,
                    entity -> !entity.isRemoved() && entity.isAlive() && !entity.getItem().isEmpty()));
            }
            found.removeIf(entity -> !areas.containsEntity(entity));
            return new ArrayList<>(found);
        }
        // 三轴各自的范围（贴图 / 界面允许分别调控）——AABB 按三轴分别膨胀。
        // 几何走 collectionRangeBox()：实体查询 / 流体游标 / 「查看范围」框三处共用同一份口径，
        // 绝不在任何一处另写一遍 inflate（否则框画在哪里、实际吸哪里会各说一套）。
        final AABB area = collectionRangeBox();
        return level.getEntitiesOfClass(ItemEntity.class, area,
            entity -> !entity.isRemoved() && entity.isAlive() && !entity.getItem().isEmpty());
    }

    /**
     * 把命中的掉落物尽量塞进缓存区（不销毁）。
     * <p>{@code threshold > 0} 时先统计半径内命中的总数，达到需求量才吸取（避免吸一半）；
     * {@code threshold <= 0} 时表示「直接吸取」（经验来源）。
     * <p>返回本轮实际收进缓存区的<b>件数</b>（0 = 没命中 / 缓存放不下）。
     */
    private int absorbMatchingItems(final List<ItemEntity> entities,
                                    final Predicate<ItemStack> filter, final long threshold) {
        if (threshold > 0L) {
            long total = 0L;
            for (final ItemEntity entity : entities) {
                if (entity.isRemoved() || isIgnored(entity)) {
                    continue;
                }
                final ItemStack stack = entity.getItem();
                if (stack.isEmpty() || !filter.test(stack)) {
                    continue;
                }
                total += stack.getCount();
                if (total >= threshold) {
                    break;
                }
            }
            if (total < threshold) {
                thresholdBlockedThisScan = true;
                return 0;
            }
        }
        int storedTotal = 0;
        for (final ItemEntity entity : entities) {
            if (entity.isRemoved() || isIgnored(entity)) {
                continue;
            }
            final ItemStack stack = entity.getItem();
            if (stack.isEmpty() || !filter.test(stack)) {
                continue;
            }
            final ItemStack leftover = insertIntoCache(stack.copy());
            final int stored = stack.getCount() - leftover.getCount();
            if (stored <= 0) {
                // 当前缓存放不下：短暂忽略该堆，避免每轮空转
                markIgnored(entity);
                continue;
            }
            collectedTotal += stored;
            storedTotal += stored;
            absorbedThisScan += stored;
            // 账本：这些物品来自世界（掉落物），记「从世界收集」一侧；之后若被销毁会另记 destroyed
            flowLedger.fromWorld(RsccFlowLedger.itemKey(stack.getItem()), stored);
            markWork();
            stack.shrink(stored);
            if (stack.isEmpty()) {
                entity.discard();
            } else {
                entity.setItem(stack);
                markIgnored(entity);
            }
        }
        return storedTotal;
    }

    /**
     * 把命中的世界流体<b>源</b>方块抽进流体缓存（一格 = 1000 mB，容量不足即停，绝不丢流体）。
     * <p>{@code demandMb} 是<b>每轮上限</b>（{@code <= 0} = 不限），<b>不是</b>「凑够才动手」的门槛：
     * 世界流体天生一格一格，一旦拿总量当门槛，玩家把数量调大后就一格都收不走。
     * 达到上限后本轮停止，剩余方块留在世界里，下一轮继续。</p>
     * <p>只处理 {@link #fluidSourceIdAt} 认定过的 source 方块：移除的是<b>源头方块本身</b>；
     * 水的「2×2 恒久水源」不做任何特殊处理（不判无限水），照常按格收集。</p>
     * @return 本轮实际抽走并移除的源方块数量（0 = 没有命中 / 缓存已满）
     */
    private int absorbMatchingFluidBlocks(final Level level, final List<BlockPos> sources,
                                          final Predicate<ResourceLocation> filter, final long demandMb) {
        int collected = 0;
        long collectedMb = 0L;
        for (final BlockPos pos : sources) {
            if (demandMb > 0L && collectedMb >= demandMb) {
                break; // 已达本轮上限：本轮到此为止（方块保留在世界，下一轮继续）
            }
            final ResourceLocation id = fluidSourceIdAt(level, pos);
            if (id == null || !filter.test(id)) {
                continue;
            }
            if (fluidCache.getFreeSpace() < FLUID_PER_BLOCK) {
                break; // 缓存已满：本轮到此为至，方块保留在世界
            }
            if (fluidCache.insert(id, new CompoundTag(), FLUID_PER_BLOCK) < FLUID_PER_BLOCK) {
                break;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            collected++;
            collectedMb += FLUID_PER_BLOCK;
            // 账本：一格世界流体源 = 1000 mB，记「从世界收集」一侧
            flowLedger.fromWorld(RsccFlowLedger.fluidKey(BuiltInRegistries.FLUID.get(id)), FLUID_PER_BLOCK);
            markWork();
        }
        return collected;
    }

    /**
     * 三轴范围内的流体<b>源</b>方块坐标 —— <b>跨扫描游标</b>版本：每次调用只枚举
     * {@link #MAX_FLUID_BLOCKS_PER_SCAN} 个位次，下一次从断点继续；扫满一整轮才回到最小角。
     *
     * <h2>为什么这样能保证「整个体积都会被扫到」</h2>
     * <p>位次按 {@code 0..volume-1} 展开（x 最快、z 最慢），游标只前进不回退，
     * 第 k 次扫描只看 {@code [k*4096, (k+1)*4096)}：</p>
     * <pre>
     * 一轮扫描次数 = ceil(volume / MAX_FLUID_BLOCKS_PER_SCAN)
     * 一轮实际 tick = ceil(volume / MAX_FLUID_BLOCKS_PER_SCAN) × getScanInterval()
     * </pre>
     * <p>实例（{@code Config.collectionCacheScanInterval} 默认 20，无速度升级）：</p>
     * <ul>
     *     <li>半径 25：51³ = 132651 格 ⇒ ceil(132651/4096) = <b>33 次</b>；该半径
     *     {@code getScanInterval()} = 20/13 = 1 ⇒ <b>33 tick 扫完一整轮</b>（旧实现永远扫不到尾部）；</li>
     *     <li>半径 50（+25 范围升级）：101³ = 1030301 格 ⇒ ceil(1030301/4096) = <b>252 次</b>；
     *     interval = 20/25 → 1 ⇒ <b>252 tick（≈12.6 秒）扫完一整轮</b>；</li>
     *     <li>默认半径 5：11³ = 1331 格 ⇒ <b>1 次</b>；interval = 20/3 = 6 ⇒ <b>6 tick</b> 扫完（与改前同量级）。</li>
     * </ul>
     *
     * <h2>预算（绝不提高每 tick 上限）</h2>
     * <p>旧实现的 4096 实际是「<b>找到的源方块数</b>」上限、<b>不是查询数</b>上限：空区域要遍历整个体积
     * （51³ = 132651 次 {@code getBlockState}）。新实现把它变成<b>硬的查询上限</b>：同一 tick 内所有调用
     * 共享 4096 次查询（{@link #fluidScanQueriesThisTick}），且同 tick 的第二次调用直接复用窗口
     * （{@link #fluidScanWindow}，流体路径 + 经验液态路径各调一次也只查一遍）⇒ 每 tick 查询数
     * <b>≤ 4096</b>，比改前只减不增。</p>
     */
    private List<BlockPos> fluidSourcesInRadius(final Level level) {
        // 区域模式（高级版）：游标与预算都在 AreaScanHook 的实现里（每 tick ≤ 2048 次查询，
        // 扫完一个区域才推进到下一个；同一 tick 的第二次调用复用同一批结果）。
        final AreaScanHook areas = areaScanHook();
        if (areas != null) {
            return areas.scanFluidWindow(level, MAX_FLUID_BLOCKS_PER_SCAN,
                pos -> fluidSourceIdAt(level, pos) != null);
        }
        // ① 位置 / 半径变了 ⇒ 游标归零（绝不能拿旧区域的位次去扫新区域）
        resyncFluidScanCursorIfMoved();
        // ② 同一 tick 的后续调用（流体路径 + 经验液态路径）复用同一批坐标
        final long tick = level.getGameTime();
        if (fluidScanWindowTick == tick) {
            return fluidScanWindow;
        }
        fluidScanWindowTick = tick;
        if (fluidScanBudgetTick != tick) {
            fluidScanBudgetTick = tick;
            fluidScanQueriesThisTick = 0;
        }
        final int remaining = MAX_FLUID_BLOCKS_PER_SCAN - fluidScanQueriesThisTick;
        if (remaining <= 0) {
            fluidScanWindow = List.of(); // 本 tick 预算已用完：不再查询（正常路径不会走到这里）
            return fluidScanWindow;
        }
        final int rx = getScanRadiusX();
        final int ry = getScanRadiusY();
        final int rz = getScanRadiusZ();
        final long sizeX = 2L * rx + 1L;
        final long sizeY = 2L * ry + 1L;
        final long sizeZ = 2L * rz + 1L;
        final long volume = saturatedMultiply(saturatedMultiply(sizeX, sizeY), sizeZ);
        if (fluidScanCursor < 0L || fluidScanCursor >= volume) {
            fluidScanCursor = 0L; // 防御：游标只允许落在 [0, volume)
        }
        final long planeSize = saturatedMultiply(sizeX, sizeY);
        // ③ 本次只推进「剩余预算」个位次；到达体积末尾则天然不足一次预算
        final int budget = (int) Math.min((long) remaining, volume - fluidScanCursor);
        final List<BlockPos> sources = new ArrayList<>();
        for (int i = 0; i < budget; i++) {
            final long index = fluidScanCursor + i;
            final long dx = index % sizeX;
            final long dy = (index / sizeX) % sizeY;
            final long dz = index / planeSize;
            final int x = clampToInt((long) worldPosition.getX() - rx + dx);
            final int y = clampToInt((long) worldPosition.getY() - ry + dy);
            final int z = clampToInt((long) worldPosition.getZ() - rz + dz);
            final BlockPos pos = new BlockPos(x, y, z);
            if (fluidSourceIdAt(level, pos) != null) {
                sources.add(pos);
            }
        }
        fluidScanQueriesThisTick += budget;
        // ④ 只前进这一个窗口；扫满一整轮才回到 0（下一轮重新覆盖整个体积）
        final long next = fluidScanCursor + budget;
        fluidScanCursor = (next < fluidScanCursor || next >= volume) ? 0L : next;
        fluidScanWindow = List.copyOf(sources);
        return fluidScanWindow;
    }

    /** 乘法饱和（半径可被创造范围升级撑到 {@code Integer.MAX_VALUE-1}，体积必须用 long 且不许溢出）。 */
    private static long saturatedMultiply(final long first, final long second) {
        if (first <= 0L || second <= 0L) {
            return 0L;
        }
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    /** long → int（越界时夹到 int 范围，避免巨大半径下坐标回绕成负数）。 */
    private static int clampToInt(final long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    /**
     * 扫描几何变化时把游标归零。
     * <p><b>为什么必须在 radius / pos 变化时重置</b>：游标是「旧体积里的位次」，半径或方块位置一变，
     * 同一个位次对应的世界坐标就完全不同 —— 不重置的后果是：① 新区域的尾部要等游标走完旧体积
     * 才可能被扫到（相当于又变回「永远轮不到」）；② 旧体积剩下的位次对应的坐标可能已经在新的
     * 扫描盒之外，白查一遍。这里每 tick 只做 4 次基本比较，可忽略。</p>
     */
    private void resyncFluidScanCursorIfMoved() {
        final long pos = worldPosition.asLong();
        final int rx = getScanRadiusX();
        final int ry = getScanRadiusY();
        final int rz = getScanRadiusZ();
        if (pos == fluidScanPos && rx == fluidScanRadiusX
            && ry == fluidScanRadiusY && rz == fluidScanRadiusZ) {
            return;
        }
        fluidScanPos = pos;
        fluidScanRadiusX = rx;
        fluidScanRadiusY = ry;
        fluidScanRadiusZ = rz;
        fluidScanCursor = 0L;
        fluidScanWindowTick = Long.MIN_VALUE; // 同 tick 里几何变了也必须作废旧窗口
        fluidScanWindow = List.of();
    }

    /**
     * 该坐标是否为「可抽取的流体<b>源头</b>方块」（是则返回其流体注册名，否则 null）。
     * <p><b>判定规则（本轮收紧 + 放宽各一处）</b>：
     * <ol>
     *     <li><b>只认「纯流体方块」</b>：方块状态里除 {@link LiquidBlock#LEVEL} 之外<b>没有任何其它属性</b>，
     *         且该方块没有方块实体。原版水 / 岩浆与绝大多数模组流体方块都满足这一条；
     *         含水的楼梯 / 台阶 / 栅栏 / 炼药锅一定还带着 FACING / WATERLOGGED / SHAPE 等属性，一律排除 ——
     *         否则抽取会把玩家建筑一起抹掉（违反「绝不销毁玩家资源」）。</li>
     *     <li>流体状态必须是 <b>{@link FluidState#isSource()}</b>：凡是 flowing / falling 状态一律不计
     *         （原版水的 blockstate LEVEL=1..15 会映射到 flowing 状态、LEVEL=8 是 falling，均非 source）。</li>
     *     <li>移除时移除的正是这个<b>源头方块本身</b>（见 {@link #absorbMatchingFluidBlocks}）；
     *         水不做「无限水」特殊判断，不因旁边还有水源就跳过，直接按格收集。</li>
     * </ol>
     * <p>此前用 {@code instanceof LiquidBlock} 判定，会把「自定义流体方块（不是 LiquidBlock 子类）」整体漏掉；
     * 改为按「属性只有 LEVEL」判定后，既覆盖模组流体，又不会误伤含水方块。</p>
     */
    @Nullable
    protected static ResourceLocation fluidSourceIdAt(final Level level, final BlockPos pos) {
        // 第 119 轮修（审计 C-6）：先判「区块已加载」再 getBlockState。
        // Level#getBlockState 对未加载区块会**同步加载**区块（本工程 support/SeparationFrameGuard
        // 里已有同样的注释警告），而区域扫描每 tick 要问上千个坐标 —— 玩家走远、区块被卸载后
        // 机器还在跑，就会反复触发同步加载，把主线程拖卡。未加载的坐标一律当作「没有流体」。
        if (!level.isLoaded(pos)) {
            return null;
        }
        final BlockState state = level.getBlockState(pos);
        // ① 纯流体方块：只有 LEVEL 一个属性（含水方块必然还带其它属性），且没有方块实体
        if (state.getProperties().size() != 1 || !state.hasProperty(LiquidBlock.LEVEL)
            || state.hasBlockEntity()) {
            return null;
        }
        final FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty() || !fluidState.isSource()) {
            return null;
        }
        return BuiltInRegistries.FLUID.getKey(fluidState.getType());
    }

    /**
     * 把缓存内容按每 tick 处理速率写入网络：
     * 速度升级提高每 tick 的处理组数；堆叠升级提高单次吞吐（每格/每条一次最多搬运的量）。
     *
     * <h2>2026-10-25（第 95 轮用户反馈第 12 条）修掉的三个「部分情况下收不进网络」缺陷</h2>
     * <p>用户原话：「归流缓存器潜在存在严重问题部分情况下无法正常的吸收物品……包括高级和普通的」。
     * 基础版与高级版共用本方法（高级版只多一个区域扫描来源），因此根因必须落在<b>这里</b>。
     * 逐条证伪/证实的结果：</p>
     * <ol>
     *     <li><b>【已证实 · 永久卡死】失败也扣预算</b>：旧实现 {@code budget--} 在 {@code insert} 之前无条件执行，
     *         而默认没有速度升级时 {@code getProcessRate() == 1} ⇒ 每 tick 只<b>尝试</b>一格。一旦第 0 格是
     *         「网络拒收」的东西（存储已满 / 网络里没有能存它的存储 / 该资源被所有存储拒收），这一 tick
     *         就直接结束，后面每一格<b>永远</b>轮不到；而第 0 格自己又是永久被拒的 ⇒ 回写<b>彻底停摆</b>，
     *         缓存逐渐填满后连吸收也停了 —— 玩家看到的就是「东西收不进去」。修法：失败不扣预算 +
     *         {@link #flushCursor} 跨 tick 轮转（保证每一格都会被公平尝试）。</li>
     *     <li><b>【已证实 · 流体永不回网】流体/气体共享物品的剩余预算</b>：旧实现把
     *         {@code flushCacheToNetwork} 用剩的预算传给 {@code flushFluidCacheToNetwork}，而默认配置下
     *         只要缓存里有<b>一件物品</b>，预算就已经是 0 ⇒ 流体/气体<b>永远</b>不会被写回网络。
     *         修法：物品与流体各自独立预算（各 {@code getProcessRate()} 组）+ {@link #fluidFlushCursor} 轮转。</li>
     *     <li><b>【已证实 · 无诊断】</b>「写不进去」没有任何日志，玩家与日志都看不出是「网络拒收 / 没接网络 /
     *         没电 / 红石停机」中的哪一条。修法：{@link #noteFlushStall(int, int, int)} 状态化告警
     *         （状态变化立刻一条，之后同一原因每 5 分钟至多一条，恢复时一条）。</li>
     * </ol>
     * <p><b>语义不变</b>：写不进去的资源一律<b>留在本机缓存</b>（绝不销毁、绝不复制）；
     * 阻塞名单里的资源照旧跳过且不占预算；反转 + 删除模式照旧先销毁再谈回写。</p>
     */
    private void flushCacheToNetwork(final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            noteFlushStall(FLUSH_NO_NETWORK, 0, 0);
            return;
        }
        final HolderLookup.Provider registries = getLevel() == null ? null : getLevel().registryAccess();
        // 物品与流体<b>各自</b>一份预算（见上面的缺陷 ②）：互不抢占，默认配置下两者都能回网。
        final int groups = Math.max(1, getProcessRate());
        final int transfer = Math.max(1, getTransferBatch());
        final int slots = Math.max(1, cache.getContainerSize());
        // 单 tick 最多尝试多少个「非空且未被阻塞」的格子：既不让一整片被拒的格子吃掉整 tick，
        // 又不至于永远轮不到后面的格子。上限恒定（不随处理速率无限放大）。
        final int maxAttempts = Math.max(groups, Math.min(slots, groups * 8 + 8));
        int processed = 0;
        int rejected = 0;
        int attempts = 0;
        int scanned = 0;
        for (int step = 0; step < slots && processed < groups && attempts < maxAttempts; step++) {
            final int i = (flushCursor + step) % slots;
            scanned++;
            final ItemStack inSlot = cache.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (isBlockedStack(inSlot)) {
                continue; // 阻塞：内容仍安全留在本机缓存，只是不写回网络（不占处理预算）
            }
            attempts++;
            // <b>反转 + 删除模式</b>：这一格「没有被匹配到」⇒ <b>直接销毁，不回网络</b>（用户指正的语义）。
            // 预算口径与槽级「直接销毁」完全一致（一个槽算一组、单次吞吐 = transfer）。
            if (registries != null && shouldDestroyUnmatched(inSlot, registries)) {
                final int amount = Math.min(inSlot.getCount(), transfer);
                if (amount > 0) {
                    flowLedger.destroyed(RsccFlowLedger.itemKey(inSlot.getItem()), amount);
                    destroyedTotal += amount;
                    if (amount >= inSlot.getCount()) {
                        cache.setItem(i, ItemStack.EMPTY);
                    } else {
                        cache.setItem(i, inSlot.copyWithCount(inSlot.getCount() - amount));
                    }
                    markWork();
                    setChanged();
                }
                processed++;
                continue;
            }
            final int attempt = Math.min(inSlot.getCount(), transfer);
            final long inserted = storage.insert(ItemResource.ofItemStack(inSlot), attempt, Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0) {
                // <b>缺陷 ① 的修复点</b>：insert 返回 0 = 网络拒收（不是「这一格处理完了」）。
                // 绝不能扣处理预算 —— 否则默认 processRate = 1 时它会把本 tick 用光，后面所有格子永远饿死。
                rejected++;
                continue;
            }
            processed++;
            insertedTotal += inserted;
            // 账本：这一份写回了网络（进入网络一侧）
            flowLedger.toNetwork(RsccFlowLedger.itemKey(inSlot.getItem()), inserted);
            markWork();
            if (inserted >= inSlot.getCount()) {
                cache.setItem(i, ItemStack.EMPTY);
            } else {
                cache.setItem(i, inSlot.copyWithCount((int) (inSlot.getCount() - inserted)));
            }
            setChanged();
        }
        // 游标按「本 tick 实际扫过的格数」前进（空槽 / 阻塞槽也计入）：某一格长期被拒也不会挡住后面的格子。
        flushCursor = (flushCursor + Math.max(1, scanned)) % slots;
        final FluidFlush fluidFlush = flushFluidCacheToNetwork(storage, groups, transfer);
        // ---------- 诊断：把「写不进去」变成可观测的状态，而不是静默 --------
        if (processed > 0 || fluidFlush.processed() > 0) {
            noteCollectPath(PATH_FLUSH);
            noteFlushProgress();
            return;
        }
        if (!hasFlushableContent()) {
            noteFlushIdle();
            return;
        }
        // 本 tick 把所有格都扫过了（或确实有被拒的）⇒ 是真的写不进去，不是「缓存格数超过单 tick 尝试上限」。
        if (rejected > 0 || fluidFlush.rejected() > 0 || scanned >= slots) {
            noteFlushStall(FLUSH_REJECTED, rejected, fluidFlush.rejected());
        } else {
            noteFlushIdle(); // 只是本 tick 预算内没轮到（格数多 / 处理速率低）：不是故障
        }
    }

    /**
     * 流体 / 气体缓存 → 网络（网络没有对应资源存储时留在缓存里，不丢资源）。
     * <p>返回本次的「成功条数 / 被拒条数」；预算<b>独立</b>于物品侧（见 {@link #flushCacheToNetwork} 缺陷 ②）。</p>
     */
    private FluidFlush flushFluidCacheToNetwork(final StorageNetworkComponent storage, final int groups,
                                                final int transfer) {
        if (fluidCache.isEmpty()) {
            fluidFlushCursor = 0;
            return new FluidFlush(0, 0);
        }
        final Level level = getLevel();
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        // 快照：循环体里会 extract（可能把条目从 fluidCache 里删掉），直接遍历活列表会跳过元素。
        final List<MultiFluidCache.Entry> entries = List.copyOf(fluidCache.entries());
        if (entries.isEmpty()) {
            fluidFlushCursor = 0;
            return new FluidFlush(0, 0);
        }
        final int size = entries.size();
        int processed = 0;
        int rejected = 0;
        int scanned = 0;
        for (int step = 0; step < size && processed < groups; step++) {
            scanned++;
            final int index = Math.floorMod(fluidFlushCursor + step, size);
            final MultiFluidCache.Entry entry = entries.get(index);
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                fluidCache.extract(entry.id(), entry.nbt(), entry.amount()); // 无效资源（模组被移除）：直接清理
                continue;
            }
            if (isBlockedFluid(entry.id())) {
                continue; // 阻塞（含按标签阻塞）：流体同样只留在本机缓存，不写回网络
            }
            // 反转 + 删除模式：这条流体「没有被匹配到」⇒ 直接销毁，不回网络（与物品侧同一口径）
            if (shouldDestroyUnmatchedFluid(entry.id())) {
                final long amount = Math.min(entry.amount(), transfer);
                final long removed = amount > 0 ? fluidCache.extract(entry.id(), entry.nbt(), amount) : 0L;
                if (removed > 0L) {
                    flowLedger.destroyed(RsccFlowLedger.fluidKey(fluid), removed);
                    destroyedTotal += removed;
                    markWork();
                    setChanged();
                }
                processed++;
                continue;
            }
            final long attempt = Math.min(entry.amount(), transfer);
            final FluidResource resource = new FluidResource(fluid, MarkerEntry.decodeComponents(entry.nbt(), registries));
            final long inserted = storage.insert(resource, attempt, Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0) {
                rejected++; // 同物品侧：网络拒收不占预算，靠游标轮转保证其它条目不会被饿死
                continue;
            }
            processed++;
            fluidCache.extract(entry.id(), entry.nbt(), inserted);
            // 账本：这一份流体写回了网络（进入网络一侧）
            flowLedger.toNetwork(RsccFlowLedger.fluidKey(fluid), inserted);
            markWork();
            setChanged();
        }
        fluidFlushCursor = Math.floorMod(fluidFlushCursor + Math.max(1, scanned), size);
        return new FluidFlush(processed, rejected);
    }

    /** 一趟流体回写的统计（只用于诊断 / 告警，不参与资源搬运决策）。 */
    private record FluidFlush(int processed, int rejected) {
    }

    // ==================== 「为什么写不进网络」的状态化诊断 ====================

    /** 缓存里是否还有「本应写回网络」的内容（未被阻塞的物品 / 流体 / 气体）。 */
    private boolean hasFlushableContent() {
        for (int i = 0; i < cache.getContainerSize(); i++) {
            final ItemStack stack = cache.getItem(i);
            if (!stack.isEmpty() && !isBlockedStack(stack)) {
                return true;
            }
        }
        return hasUnblockedFluid();
    }

    /** 待回写的物品总件数（只在真的要写日志时调用：每次扫 256 格）。 */
    private int pendingItemTotal() {
        long total = 0L;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            final ItemStack stack = cache.getItem(i);
            if (!stack.isEmpty() && !isBlockedStack(stack)) {
                total += stack.getCount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** 待回写的物品堆数（≈ 资源种类数）。 */
    private int pendingItemStacks() {
        int count = 0;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            final ItemStack stack = cache.getItem(i);
            if (!stack.isEmpty() && !isBlockedStack(stack)) {
                count++;
            }
        }
        return count;
    }

    /** 待回写的流体 / 气体总量（mB）。 */
    private long pendingFluidTotal() {
        long total = 0L;
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            if (!isBlockedFluid(entry.id())) {
                total += Math.max(0L, entry.amount());
            }
        }
        return total;
    }

    /**
     * 本 tick 有进度：清掉「写不进去」的全部状态。
     * <p>只有<b>真的告过警</b>（{@code flushWarnedAt >= 0}）才补一条恢复日志：否则「几 tick 卡住、几 tick 通」
     * 这种抖动会变成「恢复日志刷屏」—— 短于 {@link #FLUSH_STALL_TICKS_BEFORE_WARN} 的抖动本来就不该出声。</p>
     */
    private void noteFlushProgress() {
        if (flushWarnedAt >= 0L) {
            LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：回写网络已恢复（此前连续 {} tick 一个字节都写不进去，"
                + "原因：{}）。", worldPosition, flushStallTicks, flushReasonName(flushWarnKind));
        }
        flushStallTicks = 0;
        flushWarnKind = FLUSH_OK;
        flushWarnedAt = -1L;
    }

    /** 缓存里没有待回写内容（正常空闲）：静默复位，不写日志。 */
    private void noteFlushIdle() {
        flushStallTicks = 0;
        flushWarnKind = FLUSH_OK;
        flushWarnedAt = -1L;
    }

    /**
     * 「缓存里有内容、但写不进网络」：持续 10 秒后告警，同一原因之后每 5 分钟至多一条；
     * 原因类别一变立刻再告警一条（避免「先满存储、后来没电」这种变化被吞掉）。
     */
    private void noteFlushStall(final int kind, final int rejectedItems, final int rejectedFluids) {
        if (flushStallTicks < Integer.MAX_VALUE) {
            flushStallTicks++;
        }
        if (flushStallTicks < FLUSH_STALL_TICKS_BEFORE_WARN) {
            return;
        }
        final long now = getLevel() == null ? 0L : getLevel().getGameTime();
        final boolean stateChanged = kind != flushWarnKind;
        final boolean cooledDown = flushWarnedAt < 0L || now - flushWarnedAt >= FLUSH_WARN_INTERVAL_TICKS;
        if (!stateChanged && !cooledDown) {
            return;
        }
        flushWarnKind = kind;
        flushWarnedAt = now;
        if (kind == FLUSH_NO_NETWORK) {
            LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：已接入网络节点，但网络没有存储组件（未接入 RS 网络？），"
                + "缓存里的 {} 件（{} 堆）物品 / {} mB 流体因此无法回写。内容安全留在本机缓存，不会丢失。",
                worldPosition, pendingItemTotal(), pendingItemStacks(), pendingFluidTotal());
            return;
        }
        LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：缓存里有 {} 件（{} 堆）物品 / {} mB 流体想写回网络，"
                + "但网络一个都没接收（本 tick 物品被拒 {} 次 / 流体被拒 {} 条）。"
                + "成因：网络里没有能接收该资源的存储、存储已满、或该资源被所有存储拒收。"
                + "资源仍安全留在本机缓存、不会丢失；腾出空间或补上存储后会自动继续回写。"
                + "（同一原因每 5 分钟最多提醒一次）",
            worldPosition, pendingItemTotal(), pendingItemStacks(), pendingFluidTotal(),
            rejectedItems, rejectedFluids);
    }

    /** 告警原因的可读名（日志用）。 */
    private static String flushReasonName(final int kind) {
        return switch (kind) {
            case FLUSH_REJECTED -> "网络拒收（存储已满 / 没有对应资源的存储 / 该资源被所有存储拒收）";
            case FLUSH_NO_NETWORK -> "未接入 RS 网络";
            case FLUSH_INACTIVE_ENERGY -> "网络能量不足（节点未激活）";
            case FLUSH_INACTIVE_REDSTONE -> "红石模式要求当前停机";
            default -> "一切正常";
        };
    }

    private void markWork() {
        workCooldown = 20;
    }

    /** 该实体是否处于「装不下」的忽略窗口内（物品实体与经验球共用同一套窗口，按实体 NBT 计时）。 */
    private boolean isIgnored(final net.minecraft.world.entity.Entity entity) {
        final Level level = getLevel();
        return level != null
            && entity.getPersistentData().getLong(TAG_IGNORE_UNTIL) > level.getGameTime();
    }

    private void markIgnored(final net.minecraft.world.entity.Entity entity) {
        final Level level = getLevel();
        if (level != null) {
            entity.getPersistentData().putLong(TAG_IGNORE_UNTIL, level.getGameTime() + IGNORE_TICKS);
        }
    }

    // ==================== 缓存区 ====================

    /** 尝试把物品尽量塞进缓存区，返回塞不下的剩余（不会丢物品）。 */
    public ItemStack insertIntoCache(final ItemStack stack) {
        final ItemStack remainder = stack.copy();
        final int capacity = getCacheSlotCapacity();
        // 第一遍：合并进同类已有堆
        for (int i = 0; i < cache.getContainerSize() && !remainder.isEmpty(); i++) {
            final ItemStack inSlot = cache.getItem(i);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, remainder)) {
                continue;
            }
            final int space = capacity - inSlot.getCount();
            if (space <= 0) {
                continue;
            }
            final int move = Math.min(space, remainder.getCount());
            inSlot.grow(move);
            remainder.shrink(move);
        }
        // 第二遍：放入空槽
        for (int i = 0; i < cache.getContainerSize() && !remainder.isEmpty(); i++) {
            if (!cache.getItem(i).isEmpty()) {
                continue;
            }
            final int move = Math.min(capacity, remainder.getCount());
            cache.setItem(i, remainder.copyWithCount(move));
            remainder.shrink(move);
        }
        setChanged();
        return remainder;
    }

    /** 缓存区物品总件数（销毁确认界面展示用；每 tick 只由数据槽调用一次，256 格开销可忽略）。 */
    public int getCacheItemTotal() {
        long total = 0L;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            total += cache.getItem(i).getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** 缓存区非空堆数（≈ 资源种类数；销毁确认界面展示用）。 */
    public int getCacheItemStacks() {
        int stacks = 0;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            if (!cache.getItem(i).isEmpty()) {
                stacks++;
            }
        }
        return stacks;
    }

    // ==================== 销毁（用户点名的新需求；服务端权威 + 显式记账） ====================

    /**
     * <b>销毁缓存区全部内容</b>（物品 + 流体 / 气体）。
     *
     * <h2>为什么必须由「服务端 + 二次确认」共同守护</h2>
     * <p>销毁是<b>不可逆</b>的：内容一旦清掉，任何权限 / 回滚都拿不回来。因此本方法<b>不</b>在玩家第一次
     * 点击时被调用 —— 界面会先弹出确认子窗口，把「将销毁的数量 / 种类」列清楚，玩家点「确认销毁」后
     * 才发送 C2S 包；服务端再校验「当前打开的菜单就是本容器」且包上带有确认位，才真正执行。
     * 换言之：<b>未确认 ⇒ 本方法根本不会被调用 ⇒ 服务端零销毁</b>。</p>
     *
     * <h2>为什么销毁要显式记账（而不是只把槽位清空）</h2>
     * <p>缓存区里的东西是「世界收集进来的」——账本上记在 {@link RsccFlowLedger#fromWorld} 一侧。
     * 直接清空槽位会让「离开 − 进入 = 留存」凭空多出一笔，事后无从分辨「是玩家有意销毁」还是
     * 「被谁吞了」。因此这里对每一份被销毁的资源调用 {@link RsccFlowLedger#destroyed}，
     * 销毁量与缓存实际减少量<b>逐资源一致</b>，账本依旧对平且可审计。</p>
     *
     * @return 本次销毁的总量（物品件数 + 流体 mB；仅用于日志 / 展示）
     */
    public long destroyAllCacheContent() {
        long destroyed = 0L;
        for (int i = 0; i < cache.getContainerSize(); i++) {
            final ItemStack stack = cache.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final int amount = stack.getCount();
            flowLedger.destroyed(RsccFlowLedger.itemKey(stack.getItem()), amount);
            destroyed += amount;
            cache.setItem(i, ItemStack.EMPTY);
        }
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            flowLedger.destroyed(RsccFlowLedger.fluidKey(BuiltInRegistries.FLUID.get(entry.id())), entry.amount());
            destroyed += entry.amount();
        }
        fluidCache.clear();
        if (destroyed > 0L) {
            destroyedTotal += destroyed;
            setChanged();
            LOGGER.info("[rs_create_compat] 归流缓存仓：玩家确认后销毁缓存区内容，共 {}（物品件 + 流体 mB）",
                destroyed);
        }
        return destroyed;
    }

    /** 「直接销毁」累计数量（物品件 + 流体 mB；诊断 / 展示用）。 */
    public long getDestroyedTotal() {
        return destroyedTotal;
    }

    /** 该匹配槽是否处于「直接销毁」模式（越界返回 false）。 */
    public boolean isMarkerDestroy(final int index) {
        return index >= 0 && destroyMarkers.contains(index);
    }

    /**
     * 设置某匹配槽的「直接销毁」模式（服务端权威）。
     * <p><b>为什么开启要走确认</b>：开启后凡命中该槽的资源都会被销毁，属危险操作；
     * 由 {@code SetCollectionMarkerDestroyPacket} 携带确认位，服务端只接受「已确认的开启」。</p>
     */
    public void setMarkerDestroy(final int index, final boolean destroy) {
        if (index < 0 || index >= MATCH_CAPACITY) {
            return;
        }
        if (destroy) {
            destroyMarkers.add(index);
        } else {
            destroyMarkers.remove(index);
        }
        setChanged();
    }

    /** 当前可见窗口内每条标记是否「直接销毁」（与同步包的条目一一对应）。 */
    public List<Boolean> getMarkerDestroyWindow(final int start, final int count) {
        final List<Boolean> flags = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            flags.add(isMarkerDestroy(start + i));
        }
        return flags;
    }

    /** 该物品是否命中任一「直接销毁」匹配槽（收集 / 回流路径共用同一判据）。 */
    private boolean matchesAnyDestroyItemMarker(final ItemStack stack,
                                                @Nullable final HolderLookup.Provider registries) {
        if (stack.isEmpty() || destroyMarkers.isEmpty()) {
            return false;
        }
        for (final int index : destroyMarkers) {
            final MarkerEntry marker = getMarker(index);
            if (!marker.isEmpty() && !marker.fluid() && matchesItem(marker, stack, registries)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>反转 + 删除模式</b>：该物品是否应当被销毁（2026-10-05 用户指正后的正确语义）。
     *
     * <h2>用户原话</h2>
     * <p><i>「如果匹配区只有一部或者说完全没有标记任何东西，此时反转然后再点击销毁指定资源……
     * 就是这些没有被匹配到东西直接删除……不过因为这个太危险了，所以说打开的时候还是要进行确认，
     * 但此时应该是把扔出来的吸出来的东西直接把它销毁，而并不是又给他回流到终端之中。
     * 危险归危险，但是你的逻辑是错的」</i>。</p>
     *
     * <h2>语义</h2>
     * <p>反转匹配把匹配区当<b>黑名单</b>（见 {@link #collectFromDroppedItems} 的 {@code invertMatch} 分支）：
     * 只收集「没有被任何标记命中」的东西。此时再打开删除模式，玩家的意图是
     * <b>「这些没被标记的东西直接删掉」</b> —— 而不是「先收进来、绕一圈再回流到终端」。
     * 旧实现只看槽级「标记为删除」，因此反转场景下这些东西会被 {@code flushCacheToNetwork}
     * <b>原样写回网络</b>，玩家的删除意图完全落空（这就是用户说的「逻辑是错的」）。</p>
     *
     * <p><b>为什么危险却仍然保留确认</b>：该判据会在<b>每次回流</b>时生效，即持续销毁 ——
     * 所以「开启」仍然必须经确认子窗口（{@code SetCollectionDeleteModePacket} 的确认位）。
     * 关掉删除模式 ⇒ 本判据恒为 false ⇒ 一切回到「正常回流」，与旧行为逐字一致。</p>
     */
    private boolean shouldDestroyUnmatched(final ItemStack stack,
                                           final HolderLookup.Provider registries) {
        if (!deleteMode || !invertMatch || stack.isEmpty()) {
            return false;
        }
        // 「没有被匹配到」= 不命中任何<b>物品</b>标记（与收集侧同一份判据，反向一一对应）
        return !matchesAnyItemMarker(stack, registries);
    }

    /** 流体的对应判据：反转 + 删除模式下，没被任何流体标记命中的流体应当被销毁。 */
    private boolean shouldDestroyUnmatchedFluid(@Nullable final ResourceLocation id) {
        if (!deleteMode || !invertMatch || id == null) {
            return false;
        }
        return !matchesAnyFluidMarker(id);
    }

    /** 该流体是否命中任一「直接销毁」匹配槽。 */
    private boolean matchesAnyDestroyFluidMarker(@Nullable final ResourceLocation id) {
        if (id == null || destroyMarkers.isEmpty()) {
            return false;
        }
        for (final int index : destroyMarkers) {
            final MarkerEntry marker = getMarker(index);
            if (!marker.isEmpty() && marker.fluid() && matchesFluid(marker, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 落地「直接销毁」匹配槽：把缓存里命中这些槽的物品 / 流体取出并销毁（每 tick 在回网之前执行）。
     * <p><b>为什么放在回网之前</b>：命中「直接销毁」的资源按定义不该进网络；先销毁就不会出现
     * 「先进了网络又被扣掉」的中间态。此时它们仍属于「世界收集 → 缓存」这条账的一部分，
     * 销毁直接记 {@link RsccFlowLedger#destroyed} 即可对平。</p>
     *
     * <h2>2026-10-05 起：必须<b>同时</b>满足两个条件才会销毁</h2>
     * <ol>
     *     <li><b>全局「删除模式」已开启</b>（{@link #isDeleteMode()}，界面上的复选框）—— 总闸；</li>
     *     <li>该资源命中某个匹配槽，<b>且那个槽勾选了「标记为删除」</b>（{@link #isMarkerDestroy(int)}）。</li>
     * </ol>
     * <p>用户原话：<i>「首先先要开启那个删除模式，然后这个匹配的资源，然后这个要删除的资源首先要在
     * 匹配资源里面找，找到的都是……要对匹配区的那些标记的物品再次进行标记是否要表达他们删除，
     * 然后标记要删除的物品在开启了删除模式下才会被删除」</i>。旧实现只有条件 ②：只要某个槽勾了
     * 「直接销毁」，命中它的东西<b>立刻</b>消失，没有任何总闸 —— 一次误勾就是不可逆的丢料。</p>
     *
     * <h2>速率：与回流同源，受速度 / 堆叠升级影响</h2>
     * <p>用户原话：<i>「这个删除的速度和流经网络的速度保持一致，所以说这一个删除的速度也会受到
     * 速度升级和堆叠升级的影响」</i>。因此本方法用与 {@link #flushCacheToNetwork} 完全相同的两个量：
     * <ul>
     *     <li><b>每 tick 处理组数</b> = {@link #getProcessRate()}（1 + 速度升级数）；</li>
     *     <li><b>单次吞吐</b> = {@link #getTransferBatch()}（64 × (1 + 堆叠升级数)）。</li>
     * </ul>
     * 于是「销毁速度」与「回网速度」永远同阶，不会出现「回流被升级放大、销毁却还是每 tick 一格」的错配。
     * 未开启删除模式时<b>完全不碰缓存</b>（零开销、零副作用）。</p>
     *
     * <p><b>与界面「一键清空缓存区」的区别</b>：那个按钮（{@link #destroyAllCacheContent()}）是玩家显式确认的
     * <b>一次性全清</b>，按定义不受本速率约束（也不需要删除模式）；本方法才是「持续删除模式」的执行体。</p>
     *
     * @return 本轮销毁量（物品件 + 流体 mB）
     */
    private long destroyMarkedContent() {
        // 总闸：删除模式未开启 ⇒ 一个字节都不销毁（用户要求「标记要删除的物品在开启了删除模式下才会被删除」）
        if (!deleteMode || destroyMarkers.isEmpty()) {
            return 0L;
        }
        final Level level = getLevel();
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        final int itemBudget = Math.max(1, getProcessRate());
        final int transfer = Math.max(1, getTransferBatch());
        long destroyed = 0L;
        int budget = itemBudget;
        for (int i = 0; i < cache.getContainerSize() && budget > 0; i++) {
            final ItemStack stack = cache.getItem(i);
            if (stack.isEmpty() || !matchesAnyDestroyItemMarker(stack, registries)) {
                continue;
            }
            // 预算按「处理组」扣：一个槽算一组（与 flushCacheToNetwork 同一口径）
            budget--;
            final int amount = Math.min(stack.getCount(), transfer);
            if (amount <= 0) {
                continue;
            }
            flowLedger.destroyed(RsccFlowLedger.itemKey(stack.getItem()), amount);
            destroyed += amount;
            if (amount >= stack.getCount()) {
                cache.setItem(i, ItemStack.EMPTY);
            } else {
                cache.setItem(i, stack.copyWithCount(stack.getCount() - amount));
            }
        }
        // 流体/气体：与物品共用剩余预算（同一台机器的吞吐是一份，不按资源类型翻倍）
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            if (budget <= 0) {
                break;
            }
            if (!matchesAnyDestroyFluidMarker(entry.id())) {
                continue;
            }
            budget--;
            final long amount = Math.min(entry.amount(), transfer);
            final long removed = fluidCache.extract(entry.id(), entry.nbt(), amount);
            if (removed > 0L) {
                flowLedger.destroyed(RsccFlowLedger.fluidKey(BuiltInRegistries.FLUID.get(entry.id())), removed);
                destroyed += removed;
            }
        }
        if (destroyed > 0L) {
            destroyedTotal += destroyed;
            setChanged();
        }
        return destroyed;
    }

    /**
     * 本 tick 的删除吞吐上限（诊断 / 界面展示用）：{@code 组数 × 单次吞吐}
     * = {@link #getProcessRate()} × {@link #getTransferBatch()}，与回流速率同源。
     */
    public long getDeleteRatePerTick() {
        return (long) Math.max(1, getProcessRate()) * Math.max(1, getTransferBatch());
    }

    // ==================== 「删除模式」总闸（2026-10-05 新增） ====================

    /**
     * <b>全局删除模式</b>：界面上的那个复选框（用户原话：「有一个状态就是一个复选框……
     * 选择之后可以开启清除资源功能」）。
     *
     * <p>关（默认）时：标记槽里的「标记为删除」只是<b>记录下来</b>，绝不销毁任何东西；
     * 开时才按 {@link #getDeleteRatePerTick()} 的速率逐 tick 删除命中的资源。
     * 这样「标记要删什么」与「现在就开始删」是两件分开的事 —— 危险动作永远需要一次显式开启。</p>
     */
    public boolean isDeleteMode() {
        return deleteMode;
    }

    /** 设置删除模式（服务端权威；<b>开启</b>由 C2S 包携带确认位，见 {@code SetCollectionDeleteModePacket}）。 */
    public void setDeleteMode(final boolean enabled) {
        if (deleteMode == enabled) {
            return;
        }
        deleteMode = enabled;
        setChanged();
        LOGGER.info("[rs_create_compat] 归流缓存仓删除模式 = {}（标记为删除的命中资源{}被销毁；"
                + "速率 {}/tick，来自速度 / 堆叠升级）",
            enabled ? "ON" : "OFF", enabled ? "将" : "不会", getDeleteRatePerTick());
    }

    // ==================== 守恒账本（只读快照） ====================

    /** 账本探针：某资源此刻在舱内的真实存量（{@code item:} 按件、{@code fluid:} 按 mB）。 */
    private long flowLive(final String key) {
        if (key == null) {
            return 0L;
        }
        if (key.startsWith("item:")) {
            final ResourceLocation id = ResourceLocation.tryParse(key.substring(5));
            final Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                return 0L;
            }
            long total = 0L;
            for (int i = 0; i < cache.getContainerSize(); i++) {
                final ItemStack stack = cache.getItem(i);
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }
        if (key.startsWith("fluid:")) {
            final ResourceLocation id = ResourceLocation.tryParse(key.substring(6));
            if (id == null) {
                return 0L;
            }
            long total = 0L;
            for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
                if (entry.id().equals(id)) {
                    total += entry.amount();
                }
            }
            return total;
        }
        return 0L;
    }

    /** 账本只读快照（诊断导出 / 取证；只记数、绝不改状态）。 */
    public java.util.Map<String, Object> flowLedgerReport() {
        return flowLedger.report();
    }

    /** 缓存每格上限：配置基础值 + 每个堆叠升级的加成（256 格 × 64 = 16384 容量上限）。 */
    public int getCacheSlotCapacity() {
        return Math.max(1, Config.collectionCacheSlotCapacity
            + countUpgrades(STACK_UPGRADE) * Config.collectionCacheSlotCapacityPerStackUpgrade);
    }

    // ==================== 升级 / 耗电 ====================

    public int countUpgrades(final ResourceLocation upgradeId) {
        final Item upgradeItem = BuiltInRegistries.ITEM.get(upgradeId);
        int count = 0;
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            final ItemStack stack = upgrades.getItem(i);
            if (!stack.isEmpty() && stack.is(upgradeItem)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 范围升级数量（每级把三轴上限各 +{@code Config.rangeChargerRangePerUpgrade}）。 */
    public int getRangeUpgradeCount() {
        return countUpgrades(RANGE_UPGRADE);
    }

    /**
     * 即时校验插件槽：本机只承认「速度升级 / 堆叠升级 / 范围升级 / 创造范围升级」
     * （前三者各最多 6 个、创造范围升级最多 1 个）；
     * 其余升级（旧存档可能插着）一律弹出到世界，保证界面/逻辑不会因陌生升级出错。
     */
    public void enforceUpgradeCaps() {
        if (level == null || level.isClientSide() || loading || enforcing) {
            return;
        }
        enforcing = true;
        try {
            int speed = 0;
            int stack = 0;
            int range = 0;
            int creative = 0;
            for (int i = 0; i < UPGRADE_SLOTS; i++) {
                final ItemStack inSlot = upgrades.getItem(i);
                if (inSlot.isEmpty()) {
                    continue;
                }
                final ResourceLocation id = BuiltInRegistries.ITEM.getKey(inSlot.getItem());
                final String path = id != null && "refinedstorage".equals(id.getNamespace()) ? id.getPath() : "";
                boolean keep;
                if ("speed_upgrade".equals(path)) {
                    keep = speed < 6;
                    if (keep) {
                        speed++;
                    }
                } else if ("stack_upgrade".equals(path)) {
                    keep = stack < 6;
                    if (keep) {
                        stack++;
                    }
                } else if ("range_upgrade".equals(path)) {
                    keep = range < 6;
                    if (keep) {
                        range++;
                    }
                } else if ("creative_range_upgrade".equals(path)) {
                    keep = creative < 1;
                    if (keep) {
                        creative++;
                    }
                } else {
                    keep = false;
                }
                if (!keep) {
                    ejectUpgrade(i);
                }
            }
        } finally {
            enforcing = false;
        }
    }

    private void ejectUpgrade(final int index) {
        final ItemStack removed = upgrades.removeItemNoUpdate(index);
        if (removed.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        final ItemEntity entity = new ItemEntity(level,
            worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, removed);
        entity.setDeltaMovement(0, 0.1, 0);
        // 本机弹出来的升级不再被自己重新收集
        entity.getPersistentData().putLong(TAG_IGNORE_UNTIL, level.getGameTime() + IGNORE_TICKS);
        level.addFreshEntity(entity);
    }

    /**
     * 每 tick 处理组数 = 基础 1 组 + 每个速度升级 1 组（缓存 → 网络回流速度）。
     * <p>速度升级个数走 {@link RsccRateLimiter#level(int)} 收敛到 {@code [0,6]}：数值与改前逐格相同
     * （{@code 1,2,3,4,5,6,7}），但漏斗往一格塞 64 个也无法把它抬到 65（I4）。</p>
     */
    public int getProcessRate() {
        return 1 + RsccRateLimiter.level(countUpgrades(SPEED_UPGRADE));
    }

    /**
     * 单次吞吐 = 64 × (1 + 堆叠升级数)（堆叠升级影响单次搬运量）。
     * <p>走 {@link RsccRateLimiter#batchSaturating}：数值与改前逐格相同（{@code 64,128,…,448}），
     * 且乘法饱和（I3）—— 堆叠升级个数同样先被夹到 6。</p>
     */
    public int getTransferBatch() {
        return (int) RsccRateLimiter.batchSaturating(64L, countUpgrades(STACK_UPGRADE));
    }

    /** 当前是否处于工作状态（刚收集/写入过，或缓存里还有<b>未被阻塞</b>的待处理资源）。 */
    public boolean isWorking() {
        if (workCooldown > 0 || hasUnblockedFluid()) {
            return true;
        }
        for (int i = 0; i < cache.getContainerSize(); i++) {
            final ItemStack stack = cache.getItem(i);
            if (!stack.isEmpty() && !isBlockedStack(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 流体缓存里是否还有未被阻塞的内容（被阻塞的内容会长期滞留，不应让机器一直按「工作档」耗电）。 */
    private boolean hasUnblockedFluid() {
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            if (!isBlockedFluid(entry.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本机网络耗电：空闲/工作两档基础值 + 速度升级加成 + 收集范围加成。
     * <p><b>范围加成与是否正在收集无关</b>：只要把范围调大，耗电就会变大（扫得广就要一直付电费）。
     * </p>
     */
    public long getEnergyUsage() {
        final long base = isWorking()
            ? Config.collectionCacheWorkEnergyUsage
            : Config.collectionCacheIdleEnergyUsage;
        // 三轴各自计费：只要任一轴调大，耗电立刻变大（待机也耗电）
        final long radiusCost = ((long) getCollectRadiusX() + getCollectRadiusY() + getCollectRadiusZ()
            - 3L * MIN_COLLECT_RADIUS) * Config.collectionCacheEnergyPerRadius;
        return base + radiusCost + (long) countUpgrades(SPEED_UPGRADE)
            * Config.collectionCacheEnergyPerSpeedUpgrade;
    }

    /**
     * 供菜单同步的数据槽（索引与 {@code CollectionCacheMenu} 的 DATA_* 常量一致）：
     * <ol start="0">
     *     <li>速度升级数；1. 堆叠升级数；2. 已收集物品数（截断）；3. 已入网物品数（截断）；</li>
     *     <li>缓存每格上限；5..8. 四个吸取开关（1=开、0=关，顺序同 {@link AbsorbType}）；</li>
     *     <li>流体缓存已用量（mB，截断）。</li>
     *     <li>10..12 = 三轴收集范围（X / Y / Z）；13 = 范围升级数；14 = 是否无限范围（1/0）。</li>
     *     <li>15 = 「多个输入面」开关位（位 = Direction.ordinal()，默认 63 = 六面全开）。</li>
     *     <li>16 = 红石模式（0/1/2）；<b>17 = 吸取所有物品；18 = 反转匹配</b>（第 7 轮新增，1=开 / 0=关）。</li>
     *     <li>19 / 20 / 21 = 销毁确认用的缓存快照（物品总件数 / 非空堆数 / 流体种类数；本轮新增）。</li>
     * </ol>
     */
    public ContainerData getContainerData() {
        return new ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> countUpgrades(SPEED_UPGRADE);
                    case 1 -> countUpgrades(STACK_UPGRADE);
                    case 2 -> (int) Math.min(Integer.MAX_VALUE, collectedTotal);
                    case 3 -> (int) Math.min(Integer.MAX_VALUE, insertedTotal);
                    case 4 -> getCacheSlotCapacity();
                    case 5 -> isAbsorbEnabled(AbsorbType.ITEM) ? 1 : 0;
                    case 6 -> isAbsorbEnabled(AbsorbType.FLUID) ? 1 : 0;
                    case 7 -> isAbsorbEnabled(AbsorbType.GAS) ? 1 : 0;
                    case 8 -> isAbsorbEnabled(AbsorbType.EXPERIENCE) ? 1 : 0;
                    case 9 -> (int) Math.min(Integer.MAX_VALUE, fluidCache.getStored());
                    case 10 -> getCollectRadiusX();
                    case 11 -> getCollectRadiusY();
                    case 12 -> getCollectRadiusZ();
                    case 13 -> countUpgrades(RANGE_UPGRADE);
                    case 14 -> hasInfiniteRange() ? 1 : 0;
                    case 15 -> getInputFacesMask();
                    // 红石模式：走 RS 的 RedstoneModeSettings 映射（与 RS 原版机器同一套 0/1/2 编码）
                    case 16 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    // 17/18 = 第 7 轮新增的两个开关：吸取所有物品 / 反转匹配（1=开 / 0=关）
                    case 17 -> collectAll ? 1 : 0;
                    case 18 -> invertMatch ? 1 : 0;
                    // 19/20/21 = 销毁确认界面用的缓存快照（物品总件数 / 非空堆数 / 流体种类数）
                    case 19 -> getCacheItemTotal();
                    case 20 -> getCacheItemStacks();
                    case 21 -> fluidCache.getKinds();
                    // 22 = 删除模式总闸（2026-10-05）：界面复选框的状态来源
                    case 22 -> deleteMode ? 1 : 0;
                    // 23 = 当前删除吞吐/ tick（组数 × 单次吞吐，随速度 / 堆叠升级变化）——供界面展示
                    case 23 -> (int) Math.min(Integer.MAX_VALUE, getDeleteRatePerTick());
                    // 24 / 25 = 第 101 轮「为什么没在吸」状态行（状态码 + 附加位），服务端算、客户端只显示
                    case 24 -> absorbStatusReason;
                    case 25 -> absorbStatusArg;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端逻辑修改
            }

            @Override
            public int getCount() {
                return 26;
            }
        };
    }

    // ==================== 存档 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeMarkers(tag);
        // 集群持久化边界：一个集群只有主控把「共享的那一份内容」落盘，其余成员写空载荷。
        // 否则同一份内容会被写成多份，读档时合并 → 资源翻倍（最严重的复制事故）。
        if (clusterOwnsPayload) {
            // 内容槽与插件槽都走「逐格带 Slot」的读写（SimpleContainer#createTag/fromTag 会丢槽位、把同类挤成一格）
            tag.put(TAG_CACHE, cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(cache, registries));
            fluidCache.save(tag, TAG_FLUID_CACHE);
        }
        tag.put(TAG_UPGRADES, cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(upgrades, registries));
        tag.putLong(TAG_COLLECTED_TOTAL, collectedTotal);
        tag.putLong(TAG_INSERTED_TOTAL, insertedTotal);
        writeAbsorb(tag);
        tag.putString(TAG_XP_FORM, xpForm.name());
        writeRadii(tag);
        writeBlocked(tag);
        tag.putInt(TAG_INPUT_FACES, getInputFacesMask());
        // 第 7 轮新增的两个开关：与「吸取开关 / 输入面」同为方块级配置，必须落盘（服务端权威状态要能跨读档保持）
        tag.putBoolean(TAG_COLLECT_ALL, collectAll);
        tag.putBoolean(TAG_INVERT_MATCH, invertMatch);
        // 删除模式总闸：落盘（读档后仍是玩家离开时的状态）。老存档无此键 ⇒ 默认关闭，
        // 绝不因为「多了一个开关」而在读档瞬间开始销毁资源。
        tag.putBoolean(TAG_DELETE_MODE, deleteMode);
        // 「直接销毁」匹配槽：匹配区配置的一部分，同样必须跨读档保持（服务端权威）
        tag.putIntArray(TAG_DESTROY_MARKERS, destroyMarkerArray());
    }

    /** 三轴收集范围落盘（旧档只有单值 {@link #TAG_COLLECT_RADIUS}，读档时三轴共用）。 */
    private void writeRadii(final CompoundTag tag) {
        tag.putInt(TAG_COLLECT_RADIUS_X, getCollectRadiusX());
        tag.putInt(TAG_COLLECT_RADIUS_Y, getCollectRadiusY());
        tag.putInt(TAG_COLLECT_RADIUS_Z, getCollectRadiusZ());
    }

    /** 读回三轴收集范围（新字段优先；只有旧单值字段时三轴共用该值）。 */
    private void readRadii(final CompoundTag tag) {
        final int legacy = tag.contains(TAG_COLLECT_RADIUS)
            ? tag.getInt(TAG_COLLECT_RADIUS) : Config.collectionCacheScanRadius;
        radiusX = Math.max(MIN_COLLECT_RADIUS, tag.contains(TAG_COLLECT_RADIUS_X)
            ? tag.getInt(TAG_COLLECT_RADIUS_X) : legacy);
        radiusY = Math.max(MIN_COLLECT_RADIUS, tag.contains(TAG_COLLECT_RADIUS_Y)
            ? tag.getInt(TAG_COLLECT_RADIUS_Y) : legacy);
        radiusZ = Math.max(MIN_COLLECT_RADIUS, tag.contains(TAG_COLLECT_RADIUS_Z)
            ? tag.getInt(TAG_COLLECT_RADIUS_Z) : legacy);
    }

    /**
     * <b>历史兼容方法，当前没有任何调用点</b>（{@code grep -rn saveStateForItem src/main/java} 只有定义与子类覆写）。
     *
     * <p>它当初的用途是「把非实体掉落的状态写进掉落物物品 NBT」，但那条路（战利品表）后来被换掉了：
     * 现在拆方块的内容物结算由 {@code BlockContentReleaser} 负责 —— 它调的是 {@code saveAdditional()}
     * （整份 NBT 随方块物品走），放置时再由 {@code CollectionCacheBlock#setPlacedBy} 交给
     * {@link #loadPlacedState(CompoundTag, HolderLookup.Provider)} 回填。因此这里写的字段
     * <b>已经不是</b>拆方块时真正保存的那份数据。保留方法只为不改动子类覆写与旧存档结构，
     * 下轮可整段删除（连同 {@link #isStateDropped()} / {@code stateDropped} 字段）。</p>
     */
    public CompoundTag saveStateForItem() {
        stateDropped = true;
        final CompoundTag tag = new CompoundTag();
        writeMarkers(tag);
        // 匹配区配置的一部分：随方块物品一起带走（零损耗），放置时由 loadPlacedState 回填
        tag.putIntArray(TAG_DESTROY_MARKERS, destroyMarkerArray());
        // 集群里的机器：共享的那一份流体属于「整体」，不能塞进单个方块物品的 NBT，
        // 否则放下后会被当成自有内容再次并入 → 流体翻倍。内容留在集群里（绝不丢）。
        if (clusterSize <= 1) {
            fluidCache.save(tag, TAG_FLUID_CACHE);
        }
        writeAbsorb(tag);
        tag.putString(TAG_XP_FORM, xpForm.name());
        writeRadii(tag);
        writeBlocked(tag);
        tag.putInt(TAG_INPUT_FACES, getInputFacesMask());
        tag.putLong(TAG_COLLECTED_TOTAL, collectedTotal);
        tag.putLong(TAG_INSERTED_TOTAL, insertedTotal);
        return tag;
    }

    /** 状态是否已随掉落物 NBT 带走（供方块在 onRemove 里判断是否需要补掉方块物品）。 */
    public boolean isStateDropped() {
        return stateDropped;
    }

    /** 放置时从掉落物 NBT 回填「匹配区 / 流体缓存 / 吸取开关 / 经验形态」（零损耗）。 */
    public void loadPlacedState(final CompoundTag tag, final HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        loading = true;
        try {
            markers.clear();
            loadMarkers(tag, registries);
            loadDestroyMarkers(tag);
            if (tag.contains(TAG_FLUID_CACHE)) {
                fluidCache.load(tag, TAG_FLUID_CACHE);
            }
            loadAbsorb(tag);
            loadXpForm(tag);
            readRadii(tag);
            loadBlocked(tag);
            applyInputFacesMask(tag.contains(TAG_INPUT_FACES) ? tag.getInt(TAG_INPUT_FACES) : ALL_FACES);
            if (tag.contains(TAG_COLLECTED_TOTAL)) {
                collectedTotal = tag.getLong(TAG_COLLECTED_TOTAL);
            }
            if (tag.contains(TAG_INSERTED_TOTAL)) {
                insertedTotal = tag.getLong(TAG_INSERTED_TOTAL);
            }
        } finally {
            loading = false;
        }
        setChanged();
    }

    /** 「直接销毁」槽位下标 → int 数组（落盘 / 方块物品状态共用；有序稳定）。 */
    private int[] destroyMarkerArray() {
        final int[] out = new int[destroyMarkers.size()];
        int i = 0;
        for (final int index : destroyMarkers) {
            out[i++] = index;
        }
        return out;
    }

    /** 读取「直接销毁」槽位下标集合（旧存档没有该键 → 空集，行为与改动前一致）。 */
    private void loadDestroyMarkers(final CompoundTag tag) {
        destroyMarkers.clear();
        if (!tag.contains(TAG_DESTROY_MARKERS)) {
            return;
        }
        for (final int index : tag.getIntArray(TAG_DESTROY_MARKERS)) {
            if (index >= 0 && index < MATCH_CAPACITY) {
                destroyMarkers.add(index);
            }
        }
    }

    private void writeMarkers(final CompoundTag tag) {
        final ListTag markerList = new ListTag();
        for (int i = 0; i < markers.size(); i++) {
            final MarkerEntry marker = markers.get(i);
            if (marker == null || marker.isEmpty()) {
                continue;
            }
            markerList.add(encodeMarkerTag(marker, i));
        }
        tag.put(TAG_MARKERS, markerList);
        // 第 101 轮：标记数量的「旧门槛归一」只做一次 —— 有这个键的存档按玩家真实意图读
        // （他在条目标记界面里显式设过的 N 必须保留），没有这个键的才是第 95 轮及更早的旧档。
        tag.putBoolean(TAG_MARKER_AMOUNT_V2, true);
    }

    /**
     * 一条 ghost 标记 → NBT（写档，以及高级版「每区域过滤器」共用；两处必须是同一份编码，
     * 否则区域过滤器读档后匹配规则会静默丢失）。
     */
    public static CompoundTag encodeMarkerTag(final MarkerEntry marker, final int slot) {
        final CompoundTag markerTag = new CompoundTag();
        // 用 int 写槽位：旧存档写的是 byte（TAG_Byte 也是数值型，getInt 可正常读回），
        // 而匹配区无上限后槽位可能超过 255，必须用 int。
        markerTag.putInt(TAG_MARKER_SLOT, slot);
        markerTag.putBoolean(TAG_MARKER_FLUID, marker.fluid());
        markerTag.putString(TAG_MARKER_ID, marker.id().toString());
        markerTag.put(TAG_MARKER_NBT, marker.nbt() == null ? new CompoundTag() : marker.nbt());
        markerTag.putLong(TAG_MARKER_AMOUNT, marker.amount());
        markerTag.putBoolean(TAG_MARKER_MATCH_NBT, marker.matchNbt());
        // 匹配标签集合：存 tag 注册名列表（旧字段 TAG_MARKER_MATCH_TAG 是布尔，语义已变更，不再写入）
        if (marker.isTagFilter()) {
            final ListTag tags = new ListTag();
            for (final ResourceLocation tagId : marker.tags()) {
                tags.add(net.minecraft.nbt.StringTag.valueOf(tagId.toString()));
            }
            markerTag.put(TAG_MARKER_TAGS, tags);
        }
        return markerTag;
    }

    /** NBT → 一条 ghost 标记（{@code null} = 这条读不出来，调用方跳过）。数量不做归一，由调用方决定。 */
    @Nullable
    public static MarkerEntry decodeMarkerTag(final CompoundTag markerTag,
                                                 final HolderLookup.Provider registries) {
        if (markerTag.contains(TAG_MARKER_ID)) {
            final ResourceLocation id = ResourceLocation.tryParse(markerTag.getString(TAG_MARKER_ID));
            if (id == null) {
                return null;
            }
            return new MarkerEntry(
                markerTag.getBoolean(TAG_MARKER_FLUID),
                id,
                markerTag.contains(TAG_MARKER_NBT) ? markerTag.getCompound(TAG_MARKER_NBT) : new CompoundTag(),
                Math.max(1L, markerTag.getLong(TAG_MARKER_AMOUNT)),
                markerTag.getBoolean(TAG_MARKER_MATCH_NBT),
                readMarkerTags(markerTag));
        }
        if (markerTag.contains(TAG_MARKER_ITEM)) {
            // 旧档兼容：物品标记（MatchComponents → matchNbt；旧的布尔 tag 语义已废弃，读档一律按具体物品）
            if (registries == null) {
                // 第 113 轮：{@code ItemStack.parseOptional(null, ...)} 会 NPE。客户端（tooltip / 界面）
                // 有没有注册表上下文是不确定的，这里宁可「这条读不出来」也不能把客户端崩掉。
                return null;
            }
            final ItemStack stack = ItemStack.parseOptional(registries, markerTag.getCompound(TAG_MARKER_ITEM));
            if (stack.isEmpty()) {
                return null;
            }
            return MarkerEntry.item(stack.copyWithCount(1),
                Math.max(1L, markerTag.getInt(TAG_MARKER_AMOUNT)),
                markerTag.getBoolean(TAG_MARKER_MATCH_COMPONENTS), registries);
        }
        return null;
    }

    private void writeAbsorb(final CompoundTag tag) {
        final CompoundTag absorb = new CompoundTag();
        for (final AbsorbType type : AbsorbType.values()) {
            absorb.putBoolean(type.name(), absorbEnabled[type.ordinal()]);
        }
        tag.put(TAG_ABSORB, absorb);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        loading = true;
        try {
            markers.clear();
            loadMarkers(tag, registries);
            if (tag.contains(TAG_CACHE)) {
                cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                    tag.getList(TAG_CACHE, Tag.TAG_COMPOUND), cache, registries);
            }
            if (tag.contains(TAG_FLUID_CACHE)) {
                fluidCache.load(tag, TAG_FLUID_CACHE);
            }
            if (tag.contains(TAG_UPGRADES)) {
                cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                    tag.getList(TAG_UPGRADES, Tag.TAG_COMPOUND), upgrades, registries);
            }
            collectedTotal = tag.getLong(TAG_COLLECTED_TOTAL);
            insertedTotal = tag.getLong(TAG_INSERTED_TOTAL);
            loadAbsorb(tag);
            loadXpForm(tag);
            readRadii(tag);
            loadBlocked(tag);
            // 旧档没有该字段：默认六面全开（保持既有「任何面都能塞」的行为）
            applyInputFacesMask(tag.contains(TAG_INPUT_FACES) ? tag.getInt(TAG_INPUT_FACES) : ALL_FACES);
            // 旧档没有这两个字段：默认关闭 = 与改动前完全一致（按匹配区白名单收集）
            collectAll = tag.getBoolean(TAG_COLLECT_ALL);
            invertMatch = tag.getBoolean(TAG_INVERT_MATCH);
            // 旧档没有该键 → 空集（没有「直接销毁」的槽，行为与改动前一致）
            loadDestroyMarkers(tag);
            // 旧档没有该键 → 关闭。**这是安全方向的默认值**：读档绝不能自己开始销毁资源；
            // 玩家必须在界面上显式打勾（并确认）才会开启。
            deleteMode = tag.getBoolean(TAG_DELETE_MODE);
        } finally {
            loading = false;
        }
    }

    private void loadMarkers(final CompoundTag tag, final HolderLookup.Provider registries) {
        // 旧档（没有 MarkerAmountV2 键）= 第 95 轮及更早写下的数量门槛，需要归一为 1；
        // 新档不做归一（玩家在条目标记界面里显式设过的 N 必须原样保留）。
        final boolean legacyAmounts = !tag.getBoolean(TAG_MARKER_AMOUNT_V2);
        legacyMarkerAmountsNormalized = 0;
        final ListTag markerList = tag.getList(TAG_MARKERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < markerList.size(); i++) {
            final CompoundTag markerTag = markerList.getCompound(i);
            // 兼容旧存档：旧档把槽位写为 byte，数值型 NBT 用 getInt 一样能读回
            final int slot = markerTag.getInt(TAG_MARKER_SLOT);
            if (slot < 0) {
                continue;
            }
            final MarkerEntry decoded = decodeMarkerTag(markerTag, registries);
            if (decoded == null) {
                continue;
            }
            final long amount = normalizeLoadedMarkerAmount(decoded.amount(), legacyAmounts);
            putMarkerRaw(slot, amount == decoded.amount() ? decoded : decoded.withRules(
                amount, decoded.matchNbt(), decoded.tags()));
        }
        if (legacyMarkerAmountsNormalized > 0) {
            // 状态变化才写：这是一次性的存档迁移，一条 INFO 足够，不刷屏。
            LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：检测到 {} 条旧版标记带有「凑够 N 件才吸」"
                    + "的数量门槛（第 95 轮及更早写下的手持件数），已归一为「有就收」（数量 = 1）。"
                    + "需要凑够 N 件再吸的，请在条目标记界面里重新填 N —— 新值会被持久化，不再归一。",
                worldPosition, legacyMarkerAmountsNormalized);
        }
    }

    /**
     * 读档时的标记数量归一（第 101 轮 P0-3 修复）。
     * <p>旧档的 {@code Amount} 是「凑够才吸」的硬门槛（当年 Shift 标记写的是手持堆叠件数，常见 64），
     * 而玩家扔几件（尤其 1 件）永远凑不满 ⇒ {@code absorbMatchingItems} 直接 return 0，
     * 表现为「已经标记的物品扔进去根本吸不出来」。现在按存档版本只归一一次：
     * 旧档 → 1，新档 → 原样保留玩家显式设置的值。</p>
     */
    private long normalizeLoadedMarkerAmount(final long raw, final boolean legacyAmounts) {
        final long amount = Math.max(1L, raw);
        if (legacyAmounts && amount > 1L) {
            legacyMarkerAmountsNormalized++;
            return 1L;
        }
        return amount;
    }

    /** 读取标记的匹配标签集合：新字段（列表）优先；只有旧单值字段时升级为单元素列表。 */
    private static List<ResourceLocation> readMarkerTags(final CompoundTag markerTag) {
        final List<ResourceLocation> tags = new ArrayList<>(4);
        if (markerTag.contains(TAG_MARKER_TAGS)) {
            final ListTag list = markerTag.getList(TAG_MARKER_TAGS, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                final ResourceLocation tag = ResourceLocation.tryParse(list.getString(i));
                if (tag != null) {
                    tags.add(tag);
                }
            }
            return tags;
        }
        if (markerTag.contains(TAG_MARKER_TAG_FILTER)) {
            final ResourceLocation legacy = ResourceLocation.tryParse(markerTag.getString(TAG_MARKER_TAG_FILTER));
            if (legacy != null) {
                tags.add(legacy);
            }
        }
        return tags;
    }

    private void loadAbsorb(final CompoundTag tag) {
        // 旧档没有该键：仅物品吸取开启，其余保持关闭
        for (int i = 0; i < absorbEnabled.length; i++) {
            absorbEnabled[i] = false;
        }
        absorbEnabled[AbsorbType.ITEM.ordinal()] = true;
        if (!tag.contains(TAG_ABSORB)) {
            return;
        }
        final CompoundTag absorb = tag.getCompound(TAG_ABSORB);
        for (final AbsorbType type : AbsorbType.values()) {
            if (absorb.contains(type.name())) {
                absorbEnabled[type.ordinal()] = absorb.getBoolean(type.name());
            }
        }
    }

    private void loadXpForm(final CompoundTag tag) {
        xpForm = XpForm.ORB;
        if (!tag.contains(TAG_XP_FORM)) {
            return;
        }
        try {
            // 旧存档容错（旧值名 "AUTO" / "NUGGET" 早于本版已删除，valueOf 会抛异常 → 退化为经验球）；
            // "LIQUID" 语义未变，照读。
            xpForm = XpForm.valueOf(tag.getString(TAG_XP_FORM));
        } catch (final IllegalArgumentException ignored) {
            xpForm = XpForm.ORB;
        }
        if (!xpForm.selectable()) {
            // 前置缺失（例如没装「机械动力：附魔工业」却存了液态）：退化为经验颗粒，
            // 绝不把「一个当前不可用的形态」留在存档状态里（避免界面置灰却仍在生效）。
            xpForm = XpForm.ORB;
        }
    }

    // ==================== 机器集群（同类型机器相邻 = 一个整体：容量叠加 + 内容共享） ====================

    /** 集群载荷：整个集群<b>共用同一份</b>「物品缓存 + 流体缓存」对象。 */
    public record ClusterPayload(SimpleContainer items, MultiFluidCache fluids) {
    }

    /** 本机载荷的稳定视图（身份稳定是管理器判定「是否已换成共享那一份」的前提）。 */
    private ClusterPayload clusterPayload;
    /** 本机是否承担集群内容的落盘职责（单机 = true；集群里只有主控为 true）。 */
    private boolean clusterOwnsPayload = true;
    /** 当前集群规模（台数，单机 = 1）：用于掉落物 NBT 的防复制判定。 */
    private int clusterSize = 1;

    @Override
    public BlockPos rscc$clusterPos() {
        return worldPosition;
    }

    @Override
    public Level rscc$clusterLevel() {
        return level;
    }

    @Override
    public Object rscc$clusterNewPayload(final int machineCount) {
        final int count = Math.max(1, machineCount);
        // 容量叠加：物品格数 = 台数 × 单机格数；流体容量 = 台数 × 单机容量
        // （单机值走实例字段：基础版 = CACHE_SLOTS / FLUID_CACHE_CAPACITY，高级版是它们的两倍）
        return new ClusterPayload(new SimpleContainer(cacheSlots * count),
            new MultiFluidCache(fluidCacheCapacity * count));
    }

    @Override
    public Object rscc$clusterPayload() {
        if (clusterPayload == null || clusterPayload.items() != cache || clusterPayload.fluids() != fluidCache) {
            clusterPayload = new ClusterPayload(cache, fluidCache);
        }
        return clusterPayload;
    }

    @Override
    public void rscc$clusterAdopt(final Object shared) {
        if (!(shared instanceof ClusterPayload payload) || payload.items() == cache) {
            return;
        }
        cache = payload.items();
        fluidCache = payload.fluids();
        clusterPayload = payload;
        setChanged();
    }

    @Override
    public void rscc$clusterStandalone() {
        if (cache.getContainerSize() == cacheSlots && fluidCache.getCapacity() == fluidCacheCapacity) {
            return; // 已是单机规格：不重建对象，保持载荷身份稳定
        }
        // 调用方保证内容已按拆集群规则移交，这里只换成一份空的单机存储
        cache = new SimpleContainer(cacheSlots);
        fluidCache = new MultiFluidCache(fluidCacheCapacity);
        clusterPayload = null;
        setChanged();
    }

    @Override
    public long rscc$clusterMerge(final Object from, final Object into) {
        if (!(from instanceof ClusterPayload source) || !(into instanceof ClusterPayload target)
            || source == target) {
            return 0L;
        }
        long leftover = 0L;
        final int limit = getCacheSlotCapacity();
        for (int i = 0; i < source.items().getContainerSize(); i++) {
            final ItemStack stack = source.items().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final ItemStack rest = mergeIntoContainer(target.items(), stack, limit);
            final int moved = stack.getCount() - rest.getCount();
            if (moved > 0) {
                source.items().removeItem(i, moved); // 搬运是「移动」不是「复制」
            }
            leftover += rest.getCount();
        }
        for (final MultiFluidCache.Entry entry : source.fluids().entries()) {
            final long moved = target.fluids().insert(entry.id(), entry.nbt(), entry.amount());
            if (moved > 0L) {
                source.fluids().extract(entry.id(), entry.nbt(), moved);
            }
            leftover += entry.amount() - moved;
        }
        return leftover;
    }

    @Override
    public void rscc$clusterSpill(final Object payload) {
        final Level currentLevel = getLevel();
        if (!(payload instanceof ClusterPayload data) || currentLevel == null || currentLevel.isClientSide()) {
            return;
        }
        for (int i = 0; i < data.items().getContainerSize(); i++) {
            final ItemStack stack = data.items().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            data.items().setItem(i, ItemStack.EMPTY);
            Block.popResource(currentLevel, worldPosition, stack);
        }
        final List<FluidStack> fluids = new ArrayList<>();
        BlockContentReleaser.collectFluidCache(data.fluids(), currentLevel.registryAccess(), fluids);
        for (final ItemStack bucket : BlockContentReleaser.fluidsAsBuckets(fluids)) {
            Block.popResource(currentLevel, worldPosition, bucket);
        }
    }

    @Override
    public boolean rscc$clusterOwnsPayload() {
        return clusterOwnsPayload;
    }

    @Override
    public void rscc$clusterSetOwnsPayload(final boolean owns) {
        clusterOwnsPayload = owns;
    }

    @Override
    public void rscc$clusterChanged(final int memberCount, final BlockPos masterPos) {
        clusterSize = Math.max(1, memberCount);
        setChanged();
    }

    /** 当前参与的机器台数（1 = 单机）。 */
    public int getClusterSize() {
        return clusterSize;
    }

    /**
     * 本机被移除（方块破坏 / 区块卸载）时的拆集群钩子：
     * 内容按 {@link RsccMachineCluster} 的规则「跟着当时持有它的一方走」，绝不复制、绝不销毁。
     */
    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            RsccMachineCluster.onMemberRemoved(level, this);
        }
        super.setRemoved();
    }

    /** 把一份物品堆尽量并入目标缓存（先补同类堆叠、再占空槽），返回放不下的余量。 */
    private static ItemStack mergeIntoContainer(final SimpleContainer target, final ItemStack stack,
                                                final int limit) {
        ItemStack remainder = stack;
        for (int i = 0; i < target.getContainerSize() && !remainder.isEmpty(); i++) {
            final ItemStack inSlot = target.getItem(i);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, remainder)) {
                continue;
            }
            final int space = Math.min(limit, inSlot.getMaxStackSize()) - inSlot.getCount();
            if (space <= 0) {
                continue;
            }
            final int move = Math.min(space, remainder.getCount());
            inSlot.grow(move);
            remainder = remainder.copyWithCount(remainder.getCount() - move);
        }
        for (int i = 0; i < target.getContainerSize() && !remainder.isEmpty(); i++) {
            if (!target.getItem(i).isEmpty()) {
                continue;
            }
            final int move = Math.min(Math.min(limit, remainder.getMaxStackSize()), remainder.getCount());
            target.setItem(i, remainder.copyWithCount(move));
            remainder = remainder.copyWithCount(remainder.getCount() - move);
        }
        return remainder;
    }

    /**
     * <b>只读诊断</b>：归流缓存仓的完整设置与内部数据（供 {@code /rs_create_compat diag} 导出）。
     *
     * <h2>为什么必须机器自述而不是靠玩家复述</h2>
     * <p>本仓的设置面最宽（三轴半径 / 四种吸取来源开关 / 全收 / 反向匹配 / 输入面掩码 /
     * 经验形态 / 6×48 个 ghost 标记各自的种类、数量、NBT 匹配、标签过滤、销毁开关 /
     * 阻塞清单），任何一条记错都会把排查带向错误方向 —— 用户原话正是
     * 「有时候我记错了，然后我讲错你也就修错」。这里一次全部列出，且与存档同源。</p>
     * <p>只读：只调用公开只读访问器，不搬运 / 不生成 / 不销毁任何资源。</p>
     */
    @Override
    public java.util.Map<String, Object> rscc$diagReport() {
        final java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pos", getBlockPos().getX() + "," + getBlockPos().getY() + "," + getBlockPos().getZ());
        out.put("kind", "collection_cache");
        out.put("clusterSize", getClusterSize());
        out.put("energyUsage", getEnergyUsage());
        out.put("working", isWorking());
        out.put("processRate", getProcessRate());
        out.put("transferBatch", getTransferBatch());

        final java.util.Map<String, Object> range = new java.util.LinkedHashMap<>();
        range.put("radiusX", getCollectRadiusX());
        range.put("radiusY", getCollectRadiusY());
        range.put("radiusZ", getCollectRadiusZ());
        range.put("maxRadius", getMaxCollectRadius());
        range.put("infiniteRange", hasInfiniteRange());
        range.put("scanX", getScanRadiusX());
        range.put("scanY", getScanRadiusY());
        range.put("scanZ", getScanRadiusZ());
        range.put("scanInterval", getScanInterval());
        range.put("rangeUpgrades", getRangeUpgradeCount());
        out.put("range", range);

        final java.util.Map<String, Object> absorb = new java.util.LinkedHashMap<>();
        for (final AbsorbType type : AbsorbType.values()) {
            absorb.put(type.name(), isAbsorbEnabled(type));
        }
        out.put("absorb", absorb);
        out.put("collectAll", isCollectAll());
        out.put("invertMatch", isInvertMatch());
        // 2026-10-05：删除模式总闸 + 它当前的实际吞吐（随速度 / 堆叠升级变化）。
        // 用户要求「导出某台机子我对它的设置」——「我以为我开了删除 / 其实没开」是最容易记错的一项。
        out.put("deleteMode", isDeleteMode());
        out.put("deleteRatePerTick", getDeleteRatePerTick());
        out.put("destroyMarkers", new java.util.ArrayList<Object>(destroyMarkers));
        out.put("inputFacesMask", getInputFacesMask());
        out.put("xpForm", String.valueOf(getXpForm()));
        out.put("xpFluid", String.valueOf(resolveXpFluidId()));

        out.put("cacheItemStacks", getCacheItemStacks());
        out.put("cacheItemTotal", getCacheItemTotal());
        out.put("cacheSlotCapacity", getCacheSlotCapacity());
        out.put("collectedTotal", getCollectedTotal());
        out.put("insertedTotal", getInsertedTotal());
        out.put("destroyedTotal", getDestroyedTotal());

        // ghost 标记：只列<b>被真正用过的</b>那些（空标记会造成 6×48 行噪声）
        final java.util.List<Object> markers = new java.util.ArrayList<>();
        for (int index = 0; index < getMarkerCount(); index++) {
            final MarkerEntry entry = getMarkerEntry(index);
            if (entry == null) {
                continue;
            }
            final java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("index", index);
            row.put("fluid", entry.fluid());
            row.put("id", String.valueOf(entry.id()));
            row.put("amount", getMarkerAmount(index));
            row.put("matchNbt", isMarkerMatchNbt(index));
            row.put("tags", new java.util.ArrayList<Object>(getMarkerTags(index)));
            row.put("tagFilter", isMarkerTagFilter(index));
            row.put("destroy", isMarkerDestroy(index));
            markers.add(row);
        }
        out.put("markers", markers);

        final java.util.List<Object> blockedItems = new java.util.ArrayList<>();
        for (final net.minecraft.resources.ResourceLocation id : getBlockedItems()) {
            blockedItems.add(id.toString());
        }
        final java.util.List<Object> blockedFluids = new java.util.ArrayList<>();
        for (final net.minecraft.resources.ResourceLocation id : getBlockedFluids()) {
            blockedFluids.add(id.toString());
        }
        out.put("blockedItems", blockedItems);
        out.put("blockedFluids", blockedFluids);
        return out;
    }

    /** 向 NeoForge 注册网络节点容器能力（MOD 总线事件）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.COLLECTION_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        // 物流能力（用户要求：归流缓存仓可以直接被漏斗 / 管道等方块操作）
        // 物品：输入走 insertIntoCache（自动合并、按堆叠升级后的每格上限），输出按槽位取出
        // 注意：按「多少个面允许输入」逐个面开放（关掉的面返回 null = 该面没有能力），
        // 但**取出**始终允许（关掉输入面只是不让外面往里塞，已进入仓内的资源仍可被抽走，绝不困住）
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.COLLECTION_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.itemLogisticsView(direction)
        );
        // 流体 / 气体：直接读写流体缓存（容量守恒，装不下不接收，绝不丢资源）
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.COLLECTION_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.fluidLogisticsView(direction)
        );
    }

    // ==================== 物流视图（漏斗 / 管道） ====================

    /** 物品缓存对外的物流视图（复用 {@link #insertIntoCache}，保证不会丢物品）。 */
    private final java.util.Map<net.minecraft.core.Direction, net.neoforged.neoforge.items.IItemHandler>
        itemLogisticsViews = new java.util.EnumMap<>(net.minecraft.core.Direction.class);
    /** 未指定方向（{@code direction == null}）时使用的物品视图。 */
    private net.neoforged.neoforge.items.IItemHandler itemLogisticsView;

    /**
     * 物品缓存对外的物流视图（按面返回；该面未开启输入时返回 null，表示这一面没有物品能力）。
     * <p>{@code direction == null}（一些工具不指定方向查询）时按「允许输入」处理，避免误伤。</p>
     */
    @org.jetbrains.annotations.Nullable
    public net.neoforged.neoforge.items.IItemHandler itemLogisticsView(
        @Nullable final net.minecraft.core.Direction direction) {
        if (direction != null && !isInputFace(direction)) {
            return null;
        }
        if (direction == null) {
            if (itemLogisticsView == null) {
                itemLogisticsView = createItemLogisticsView();
            }
            return itemLogisticsView;
        }
        return itemLogisticsViews.computeIfAbsent(direction, key -> createItemLogisticsView());
    }

    private net.neoforged.neoforge.items.IItemHandler createItemLogisticsView() {
        return new net.neoforged.neoforge.items.IItemHandler() {
            @Override
            public int getSlots() {
                return cache.getContainerSize();
            }

            @Override
            public ItemStack getStackInSlot(final int slot) {
                return slot >= 0 && slot < cache.getContainerSize() ? cache.getItem(slot) : ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
                if (stack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                return simulate ? simulateCacheInsert(stack) : insertIntoCache(stack);
            }

            @Override
            public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
                if (slot < 0 || slot >= cache.getContainerSize() || amount <= 0) {
                    return ItemStack.EMPTY;
                }
                final ItemStack inSlot = cache.getItem(slot);
                if (inSlot.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                final int take = Math.min(amount, inSlot.getCount());
                if (simulate) {
                    return inSlot.copyWithCount(take);
                }
                final ItemStack extracted = inSlot.copyWithCount(take);
                cache.setItem(slot, inSlot.getCount() - take <= 0
                    ? ItemStack.EMPTY : inSlot.copyWithCount(inSlot.getCount() - take));
                setChanged();
                return extracted;
            }

            @Override
            public int getSlotLimit(final int slot) {
                return getCacheSlotCapacity();
            }

            @Override
            public boolean isItemValid(final int slot, final ItemStack stack) {
                return !stack.isEmpty();
            }
        };
    }

    /** 纯计算「还能塞进多少」（不改动缓存），供物流能力的模拟插入使用。 */
    private ItemStack simulateCacheInsert(final ItemStack stack) {
        final int capacity = getCacheSlotCapacity();
        int remaining = stack.getCount();
        for (int i = 0; i < cache.getContainerSize() && remaining > 0; i++) {
            final ItemStack inSlot = cache.getItem(i);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, stack)) {
                continue;
            }
            remaining -= Math.max(0, capacity - inSlot.getCount());
        }
        for (int i = 0; i < cache.getContainerSize() && remaining > 0; i++) {
            if (!cache.getItem(i).isEmpty()) {
                continue;
            }
            remaining -= capacity;
        }
        final int stored = stack.getCount() - Math.max(0, remaining);
        return stored >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - stored);
    }

    /**
     * 流体缓存对外的物流视图：条目按 {@code 注册名 + 数据组件} 归并（与内部一致），
     * 因此管道取出的流体能被网络正常识别；容量不足时不接收，绝不丢流体。
     */
    private final java.util.Map<net.minecraft.core.Direction,
        net.neoforged.neoforge.fluids.capability.IFluidHandler>
        fluidLogisticsViews = new java.util.EnumMap<>(net.minecraft.core.Direction.class);
    /** 未指定方向（{@code direction == null}）时使用的流体视图。 */
    private net.neoforged.neoforge.fluids.capability.IFluidHandler fluidLogisticsView;

    /**
     * 流体缓存对外的物流视图（按面返回；该面未开启输入时返回 null，表示这一面没有流体能力）。
     * <p>{@code direction == null} 时按「允许输入」处理，避免误伤不指定方向的查询。</p>
     */
    @org.jetbrains.annotations.Nullable
    public net.neoforged.neoforge.fluids.capability.IFluidHandler fluidLogisticsView(
        @Nullable final net.minecraft.core.Direction direction) {
        if (direction != null && !isInputFace(direction)) {
            return null;
        }
        if (direction == null) {
            if (fluidLogisticsView == null) {
                fluidLogisticsView = createFluidLogisticsView();
            }
            return fluidLogisticsView;
        }
        return fluidLogisticsViews.computeIfAbsent(direction, key -> createFluidLogisticsView());
    }

    private net.neoforged.neoforge.fluids.capability.IFluidHandler createFluidLogisticsView() {
        return new net.neoforged.neoforge.fluids.capability.IFluidHandler() {
                @Override
                public int getTanks() {
                    return Math.max(1, fluidCache.getKinds());
                }

                @Override
                public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(final int tank) {
                    final List<MultiFluidCache.Entry> entries = fluidCache.entries();
                    if (tank < 0 || tank >= entries.size()) {
                        return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                    }
                    return toFluidStack(entries.get(tank));
                }

                @Override
                public int getTankCapacity(final int tank) {
                    // 实例字段而不是静态常量：高级版把容量放大到 1_024_000 mB，
                    // 用静态值会让「经物流能力对外报的罐容」与「实际能存多少」口径分叉（高级版少报一半）。
                    return (int) Math.min(Integer.MAX_VALUE, fluidCacheCapacity);
                }

                @Override
                public boolean isFluidValid(final int tank, final net.neoforged.neoforge.fluids.FluidStack stack) {
                    return !stack.isEmpty();
                }

                @Override
                public int fill(final net.neoforged.neoforge.fluids.FluidStack resource,
                                final net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction action) {
                    if (resource.isEmpty()) {
                        return 0;
                    }
                    final ResourceLocation id =
                        BuiltInRegistries.FLUID.getKey(resource.getFluid());
                    final CompoundTag nbt = MarkerEntry.encodeComponents(resource.getComponentsPatch());
                    final long accepted = action.simulate()
                        ? Math.min(fluidCache.getFreeSpace(), resource.getAmount())
                        : fluidCache.insert(id, nbt, resource.getAmount());
                    if (!action.simulate() && accepted > 0) {
                        setChanged();
                    }
                    return (int) accepted;
                }

                @Override
                public net.neoforged.neoforge.fluids.FluidStack drain(
                    final net.neoforged.neoforge.fluids.FluidStack resource,
                    final net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction action) {
                    if (resource.isEmpty()) {
                        return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                    }
                    final ResourceLocation id = BuiltInRegistries.FLUID.getKey(resource.getFluid());
                    final CompoundTag nbt = MarkerEntry.encodeComponents(resource.getComponentsPatch());
                    final long available = fluidCache.getAmount(id, nbt);
                    final long taken = Math.min(available, resource.getAmount());
                    if (taken <= 0) {
                        return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                    }
                    if (!action.simulate()) {
                        fluidCache.extract(id, nbt, taken);
                        setChanged();
                    }
                    return resource.copyWithAmount((int) taken);
                }

                @Override
                public net.neoforged.neoforge.fluids.FluidStack drain(
                    final int maxDrain,
                    final net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction action) {
                    for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
                        final long taken = Math.min(entry.amount(), maxDrain);
                        if (taken <= 0) {
                            continue;
                        }
                        if (!action.simulate()) {
                            fluidCache.extract(entry.id(), entry.nbt(), taken);
                            setChanged();
                        }
                        return toFluidStack(new MultiFluidCache.Entry(entry.id(), entry.nbt(), taken));
                    }
                    return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                }
        };
    }

    /** 内部流体条目 → 用于展示 / 交付的 {@link net.neoforged.neoforge.fluids.FluidStack}。 */
    private net.neoforged.neoforge.fluids.FluidStack toFluidStack(final MultiFluidCache.Entry entry) {
        final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
        if (fluid == null || fluid == Fluids.EMPTY) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        final Level level = getLevel();
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        return new net.neoforged.neoforge.fluids.FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid),
            (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.amount())),
            MarkerEntry.decodeComponents(entry.nbt(), registries));
    }
}
