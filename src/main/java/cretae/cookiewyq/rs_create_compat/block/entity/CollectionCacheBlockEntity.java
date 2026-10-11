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
import cretae.cookiewyq.rs_create_compat.support.AbsorbType;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.MultiFluidCache;
import cretae.cookiewyq.rs_create_compat.support.OptionalDeps;
import cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger;
import cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster;
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
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
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

    /** 掉落物忽略标记（避免反复处理同一堆暂时存不下的物品）。 */
    private static final String TAG_IGNORE_UNTIL = "rs_create_compat:collect_ignore_until";
    private static final int IGNORE_TICKS = 100;

    private static final String TAG_MARKERS = "Markers";
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

    /** 匹配区：ghost 标记条目（下标即槽位，长度按需增长到 {@link #MATCH_CAPACITY}；null / {@link MarkerEntry#isEmpty()} = 未标记）。 */
    private final List<MarkerEntry> markers = new ArrayList<>();
    /** 缓存区（真实存储）。<b>非 final</b>：参与机器集群时会被换成「整个集群共用的那一份」。 */
    private SimpleContainer cache = new SimpleContainer(CACHE_SLOTS);
    /** 插件槽（速度 / 堆叠升级）。 */
    private final SimpleContainer upgrades = new SimpleContainer(UPGRADE_SLOTS);
    /** 流体/气体缓存：容量上限、种类无上限。<b>非 final</b>：参与集群时换成集群共用的那一份。 */
    private MultiFluidCache fluidCache = new MultiFluidCache(FLUID_CACHE_CAPACITY);
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
    /** 「经验吸取开关关着、但半径内确实有经验球」这条提醒的节流（每 200 tick 至多一条）。 */
    private int experienceOffWarnCooldown;
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
    /** 状态是否已随掉落物 NBT 带走（战利品表已调用 {@link #saveStateForItem()}）：避免创造模式拆除时漏掉。 */
    private boolean stateDropped;

    public CollectionCacheBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.COLLECTION_CACHE_BLOCK_ENTITY.get(), pos, state, new CollectionCacheNetworkNode());
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
     * 扫描间隔：速度升级与收集范围都会让它变短（数值越小 = 吸取越快）。
     * <p>范围每 +2 格提升一档频率，与速度升级相乘（最高仍是 1 tick 一次）。</p>
     */
    public int getScanInterval() {
        final int base = Math.max(1, Config.collectionCacheScanInterval);
        final int speedFactor = 1 + countUpgrades(SPEED_UPGRADE);
        final int radiusFactor = 1 + (getCollectRadius() - 1) / 2;
        return Math.max(1, base / (speedFactor * radiusFactor));
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
     * 设置某资源的阻塞开关（服务端权威；界面经
     * {@code SetCollectionBlockedPacket} 调用）。返回是否真的发生了改变。
     */
    public boolean setBlocked(final boolean fluid, @Nullable final ResourceLocation id, final boolean blocked) {
        if (id == null) {
            return false;
        }
        final Set<ResourceLocation> target = fluid ? blockedFluids : blockedItems;
        final boolean added = blocked ? target.add(id) : target.remove(id);
        if (added) {
            setChanged();
        }
        return added;
    }

    /**
     * 设置一组标签的阻塞开关（服务端权威；供「按标签注册的匹配条目」使用）。
     * <p>与 {@link #setBlocked} 并列：两个名单都参与 {@link #isBlockedStack} / {@link #isBlockedFluid}
     * 的判定。空集合表示「不动标签名单」（保持既有行为）。返回是否真的发生了改变。</p>
     */
    public boolean setBlockedTags(final boolean fluid, @Nullable final List<ResourceLocation> tags,
                                  final boolean blocked) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        final Set<ResourceLocation> target = fluid ? blockedFluidTags : blockedItemTags;
        boolean changed = false;
        for (final ResourceLocation tag : tags) {
            if (tag == null) {
                continue;
            }
            changed |= blocked ? target.add(tag) : target.remove(tag);
        }
        if (changed) {
            setChanged();
        }
        return changed;
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
     *     <li>标记数量默认取手持堆叠数量（至少 1），之后可在条目编辑界面修改。</li>
     * </ol>
     */
    public boolean tryAddMarker(final int index, final ItemStack stack) {
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
        setMarker(target, MarkerEntry.item(stack, Math.max(1, stack.getCount()), false,
            level == null ? null : level.registryAccess()));
        return true;
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
        setMarker(index, new MarkerEntry(fluid, id, effectiveNbt, Math.max(1L, amount),
            matchNbt, effectiveTags));
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

    /** 4 个来源共用同一入口，各自受开关控制。 */
    private void absorbAll(final Level level) {
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
        } else if (--experienceOffWarnCooldown <= 0) {
            // <b>2026-10-05：开关关着时也要留下痕迹</b>。
            //
            // 用户报告「旁边搞了几个经验球，它们根本不消失」。原因排查发现：本机 4 个吸取开关
            // <b>默认只有「物品」是开的</b>（构造器里 {@code absorbEnabled[i] = false} 之后只把 ITEM 置 true），
            // 因此「经验」没打开时 {@link #collectExperience} <b>一次都不会被调用</b> ——
            // 既没有收集，也没有任何日志，玩家只能看到「东西不动、也没报错」。
            // 这条日志每 10 秒提醒一次（只在附近真的有经验球时），把「开关没开」这件事说清楚。
            experienceOffWarnCooldown = 200;
            if (!experienceOrbsInRadius(level).isEmpty()) {
                LOGGER.info("[rs_create_compat] 归流缓存仓检测到半径内有经验球，但「经验」吸取开关是关闭的"
                    + " ⇒ 不会收集。请打开该开关（界面里那一排开关，最后一个是「经验」）。");
            }
        }
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
            for (int i = 0; i < markers.size(); i++) {
                final MarkerEntry marker = markers.get(i);
                if (marker == null || marker.isEmpty() || marker.fluid()) {
                    continue;
                }
                final int absorbed = absorbMatchingItems(entities,
                    stack -> matchesItem(marker, stack, registries), marker.amount());
                changed |= absorbed > 0;
                // 标签过滤器「看得见却吸不到」时给一行不刷屏的 DEBUG 日志：把「命中该标签的物品堆 / 件数 / 数量门槛」
                // 一次列全，用来区分「半径内根本没有这一类物品」与「有这一类物品但没凑够数量门槛」。
                if (absorbed <= 0 && marker.isTagFilter() && !entities.isEmpty() && --tagDebugCooldown <= 0) {
                    tagDebugCooldown = 60;
                    long hitCount = 0L;
                    int hitStacks = 0;
                    for (final ItemEntity entity : entities) {
                        if (entity.isRemoved() || isIgnored(entity)) {
                            continue;
                        }
                        final ItemStack stack = entity.getItem();
                        if (!stack.isEmpty() && matchesItem(marker, stack, registries)) {
                            hitStacks++;
                            hitCount += stack.getCount();
                        }
                    }
                    LOGGER.debug("[rs_create_compat] 归流缓存仓标签过滤器本轮未收集：slot={} tags={} 数量门槛={} "
                            + "半径内掉落物={} 堆 / 其中命中该标签={} 堆共 {} 件",
                        i, marker.tags(), marker.amount(), entities.size(), hitStacks, hitCount);
                }
            }
        }
        if (changed) {
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
                        sources.size(), collected, fluidCache.getStored(), FLUID_CACHE_CAPACITY);
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
     * <p>RS 2.0 的资源体系当前没有气体资源类型（{@code ResourceType}），因此这里暂不产生具体来源；
     * 后续接入气体资源类型后在本方法内实现即可，缓存与回流逻辑无需改动。</p>
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
        final AABB area = new AABB(worldPosition)
            .inflate(getScanRadiusX(), getScanRadiusY(), getScanRadiusZ());
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
        // 三轴各自的范围（贴图 / 界面允许分别调控）——AABB 按三轴分别膨胀
        final AABB area = new AABB(worldPosition)
            .inflate(getScanRadiusX(), getScanRadiusY(), getScanRadiusZ());
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

    /** 三轴范围内的流体<b>源</b>方块坐标（每次扫描有数量上限，防卡顿）。 */
    private List<BlockPos> fluidSourcesInRadius(final Level level) {
        final int rx = getScanRadiusX();
        final int ry = getScanRadiusY();
        final int rz = getScanRadiusZ();
        final List<BlockPos> sources = new ArrayList<>();
        final Iterable<BlockPos> positions = BlockPos.betweenClosed(
            worldPosition.offset(-rx, -ry, -rz),
            worldPosition.offset(rx, ry, rz));
        for (final BlockPos pos : positions) {
            if (sources.size() >= MAX_FLUID_BLOCKS_PER_SCAN) {
                break;
            }
            if (fluidSourceIdAt(level, pos) != null) {
                sources.add(pos.immutable());
            }
        }
        return sources;
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
    private static ResourceLocation fluidSourceIdAt(final Level level, final BlockPos pos) {
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
     */
    private void flushCacheToNetwork(final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        final HolderLookup.Provider registries = getLevel() == null ? null : getLevel().registryAccess();
        int budget = getProcessRate();
        final int transfer = getTransferBatch();
        for (int i = 0; i < cache.getContainerSize() && budget > 0; i++) {
            final ItemStack inSlot = cache.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (isBlockedStack(inSlot)) {
                continue; // 阻塞：内容仍安全留在本机缓存，只是不写回网络（不占处理预算）
            }
            budget--;
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
                continue;
            }
            final int attempt = Math.min(inSlot.getCount(), transfer);
            final long inserted = storage.insert(ItemResource.ofItemStack(inSlot), attempt, Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0) {
                continue;
            }
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
        flushFluidCacheToNetwork(storage, budget, transfer);
    }

    /** 流体/气体缓存 → 网络（网络没有对应资源存储时留在缓存里，不丢资源）。 */
    private void flushFluidCacheToNetwork(final StorageNetworkComponent storage, final int itemBudget,
                                          final int transfer) {
        if (fluidCache.isEmpty()) {
            return;
        }
        final Level level = getLevel();
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        int budget = itemBudget;
        for (final MultiFluidCache.Entry entry : fluidCache.entries()) {
            if (budget <= 0) {
                return;
            }
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                fluidCache.extract(entry.id(), entry.nbt(), entry.amount()); // 无效资源（模组被移除）：直接清理
                continue;
            }
            if (isBlockedFluid(entry.id())) {
                continue; // 阻塞（含按标签阻塞）：流体同样只留在本机缓存，不写回网络
            }
            budget--;
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
                continue;
            }
            final long attempt = Math.min(entry.amount(), transfer);
            final FluidResource resource = new FluidResource(fluid, MarkerEntry.decodeComponents(entry.nbt(), registries));
            final long inserted = storage.insert(resource, attempt, Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0) {
                continue;
            }
            fluidCache.extract(entry.id(), entry.nbt(), inserted);
            // 账本：这一份流体写回了网络（进入网络一侧）
            flowLedger.toNetwork(RsccFlowLedger.fluidKey(fluid), inserted);
            markWork();
            setChanged();
        }
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

    /** 每 tick 处理组数 = 基础 1 组 + 每个速度升级 1 组（缓存 → 网络回流速度）。 */
    public int getProcessRate() {
        return 1 + countUpgrades(SPEED_UPGRADE);
    }

    /** 单次吞吐 = 64 × (1 + 堆叠升级数)（堆叠升级影响单次搬运量）。 */
    public int getTransferBatch() {
        return Math.max(1, 64 * (1 + countUpgrades(STACK_UPGRADE)));
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
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端逻辑修改
            }

            @Override
            public int getCount() {
                return 24;
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
     * 破坏方块时写入掉落物物品 NBT 的「非实体掉落」状态：
     * 匹配区 ghost 标记、<b>流体/气体缓存</b>、4 个吸取开关、经验形态与统计。
     * <p>缓存区（物品）与插件槽由破坏逻辑按实体掉落，因此这里<b>不包含</b>它们，避免重复。
     * <p>放置时由原版 {@code BlockItem} 的 BLOCK_ENTITY_DATA 机制自动写回本方块实体，
     * 从而做到流体（含不足 1 桶的余量）与标记配置的<b>零损耗</b>。
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
            final CompoundTag markerTag = new CompoundTag();
            // 用 int 写槽位：旧存档写的是 byte（TAG_Byte 也是数值型，getInt 可正常读回），
            // 而匹配区无上限后槽位可能超过 255，必须用 int。
            markerTag.putInt(TAG_MARKER_SLOT, i);
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
            markerList.add(markerTag);
        }
        tag.put(TAG_MARKERS, markerList);
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
        final ListTag markerList = tag.getList(TAG_MARKERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < markerList.size(); i++) {
            final CompoundTag markerTag = markerList.getCompound(i);
            // 兼容旧存档：旧档把槽位写为 byte，数值型 NBT 用 getInt 一样能读回
            final int slot = markerTag.getInt(TAG_MARKER_SLOT);
            if (slot < 0) {
                continue;
            }
            if (markerTag.contains(TAG_MARKER_ID)) {
                final ResourceLocation id = ResourceLocation.tryParse(markerTag.getString(TAG_MARKER_ID));
                if (id == null) {
                    continue;
                }
                putMarkerRaw(slot, new MarkerEntry(
                    markerTag.getBoolean(TAG_MARKER_FLUID),
                    id,
                    markerTag.contains(TAG_MARKER_NBT) ? markerTag.getCompound(TAG_MARKER_NBT) : new CompoundTag(),
                    Math.max(1L, markerTag.getLong(TAG_MARKER_AMOUNT)),
                    markerTag.getBoolean(TAG_MARKER_MATCH_NBT),
                    readMarkerTags(markerTag)));
            } else if (markerTag.contains(TAG_MARKER_ITEM)) {
                // 旧档兼容：物品标记（MatchComponents → matchNbt；旧的布尔 tag 语义已废弃，读档一律按具体物品）
                final ItemStack stack = ItemStack.parseOptional(registries, markerTag.getCompound(TAG_MARKER_ITEM));
                if (stack.isEmpty()) {
                    continue;
                }
                putMarkerRaw(slot, MarkerEntry.item(stack.copyWithCount(1),
                    Math.max(1L, markerTag.getInt(TAG_MARKER_AMOUNT)),
                    markerTag.getBoolean(TAG_MARKER_MATCH_COMPONENTS), registries));
            }
        }
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
        // 容量叠加：物品格数 = 台数 × CACHE_SLOTS；流体容量 = 台数 × FLUID_CACHE_CAPACITY
        return new ClusterPayload(new SimpleContainer(CACHE_SLOTS * count),
            new MultiFluidCache(FLUID_CACHE_CAPACITY * count));
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
        if (cache.getContainerSize() == CACHE_SLOTS && fluidCache.getCapacity() == FLUID_CACHE_CAPACITY) {
            return; // 已是单机规格：不重建对象，保持载荷身份稳定
        }
        // 调用方保证内容已按拆集群规则移交，这里只换成一份空的单机存储
        cache = new SimpleContainer(CACHE_SLOTS);
        fluidCache = new MultiFluidCache(FLUID_CACHE_CAPACITY);
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
                    return (int) Math.min(Integer.MAX_VALUE, FLUID_CACHE_CAPACITY);
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
