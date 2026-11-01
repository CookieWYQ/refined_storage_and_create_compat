package cretae.cookiewyq.rs_create_compat.block.entity;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.RangeChargerNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.ChargerFilterLimits;
import cretae.cookiewyq.rs_create_compat.support.ChargerKnownPlayers;
import cretae.cookiewyq.rs_create_compat.support.ChargingFilter;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 范围充电器：接入 RS 网络（能量线缆/控制器供能），
 * 为周围指定范围内的可充电方块与掉落物物品充电。
 * <p>
 * 范围在 GUI 中调整（三轴独立，默认 50×50×50，最小 1、最大 100）。
 * 为控制性能，范围方块按"分片"逐步扫描，每 tick 只检查一部分。
 */
/** 本类专用日志器（诊断充电器电量持久化用）。 */
public class RangeChargerBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<RangeChargerNetworkNode> {
    /** 本类专用日志器（诊断充电器电量持久化用）。 */
    private static final org.slf4j.Logger ORG_SLF4J =
        org.slf4j.LoggerFactory.getLogger("rs_create_compat/range-charger");

    protected static final ResourceLocation SPEED_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    protected static final ResourceLocation STACK_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "stack_upgrade");
    protected static final ResourceLocation RANGE_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "range_upgrade");
    protected static final ResourceLocation CREATIVE_RANGE_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "creative_range_upgrade");
    /** 无范围升级时的单轴上限。 */
    private static final int BASE_MAX_RANGE = 100;
    /** 允许的升级种类数：0=speed，1=stack，2=range，3=creative_range（见 {@link #upgradeKind}）。 */
    private static final int UPGRADE_KINDS = 4;
    /** 创造范围升级的种类号（全容器最多 1 个，与其它每种 6 个不同）。 */
    private static final int KIND_CREATIVE_RANGE = 3;
    /** 无限范围（creative）时，扫描覆盖每个在线玩家已加载的区块半径（chunk 数）。 */
    private static final int INFINITE_CHUNK_RADIUS = 16;
    /**
     * 充电目标高亮的同步节拍（tick）：每 5 tick（0.25 秒）发一次「本 tick 真充了谁」的快照。
     * <p>只在<b>非空</b>时按节拍重发（让客户端续命）；从有到无时补发一条空快照清屏；
     * 本来就是空则一个包都不发（不产生空包风暴）。</p>
     */
    private static final int HIGHLIGHT_SYNC_INTERVAL_TICKS = 5;
    /**
     * 充电目标高亮的接收半径（格）：只发给<b>同维度</b>、以充电器为圆心 64 格内的玩家
     * （比充电范围本身大，保证「站在机器旁边看界面」和「站远一点看效果」都能收到）。
     */
    private static final double HIGHLIGHT_NOTIFY_RADIUS = 64.0;

    private final RestorableEnergyStorage energyStorage;
    /** 对外（{@code Capabilities.EnergyStorage.BLOCK}）的能量视图：输入 / 输出都夹到
     *  {@link Config#rangeChargerMaxTransfer}，配置语义不变（见 {@link CappedEnergyStorage}）。 */
    private final CappedEnergyStorage externalEnergyStorage;
    /** 插件槽（6 格）：速度/堆叠升级提升充电速率，范围升级突破单轴 100 格上限。 */
    private final ItemStackHandler upgradeContainer;
    private int rangeX = 50;
    private int rangeY = 50;
    private int rangeZ = 50;
    /** 上次校验时是否已放入无限范围升级（用于取下后回落数值）。 */
    private boolean hadInfiniteRangeUpgrade;
    /** 上一次充电扫描实际充电的对象数量（供 GUI 显示）。 */
    private int lastChargedTargets;
    /**
     * 上一次充电扫描中<b>因为已经充满而被直接跳过</b>的目标数（供 GUI 显示）。
     *
     * <h2>语义（用户原话：「某个物品它的电量如果一直是满的，那么就不给他充电、就跳过这个」）</h2>
     * <p>计数单位 = <b>单个候选目标</b>：一个方块 / 一个掉落物 / 一件携带物品（背包 41 格 + 饰品槽）
     * 各算 1。玩家档按<b>每件物品</b>判：某人身上 5 件满、1 件没满 ⇒ 这里 +5，且只有那件没满的
     * 物品会被充、这个人才会被登记进高亮集合；全都满 ⇒ 该玩家整体不会进高亮（因为一件都没充）。</p>
     *
     * <p>判定点必须早于「真取电」（见 {@link #chargeItemStack} / {@link #chargeBlock} /
     * {@link #scanItems}）：满电目标因此不消耗预算、不扣电量、不登记高亮、不产生任何高亮包
     * —— 「跳过」是真的跳过，不是「算了但还是走一遍」。</p>
     *
     * <p><b>第 124 轮 R-1</b>：{@link #chargeBlock} 的判满改成
     * {@code receiveEnergy(transfer, true) <= 0}（simulate），因此那里会先算一次
     * {@link #transferBudget()} 拿到 {@code transfer}；但 {@code transferBudget()} 是纯函数、
     * 不扣任何电量，所以「满电目标不消耗预算」这条口径<b>仍然成立</b>。</p>
     */
    private int lastSkippedFullTargets;
    /** 防止校验在 drop 触发 onContentsChanged 时重入。 */
    private boolean validatingUpgrades;
    /** 「落盘取证日志」的节流（每台每 1200 tick 至多一条）。 */
    private long lastEnergySaveLogAt = Long.MIN_VALUE / 2;
    /**
     * 饰品槽（Curios）扫描的节流计数（tick）：减到 0 才扫一次。
     *
     * <p><b>为什么要有它</b>：玩家的<b>背包 / 快捷栏 / 盔甲 / 副手</b>都在 {@code Inventory} 里
     * （NeoForge 的 {@code getContainerSize()} = 36 + 4 + 1 = 41 格），每 tick 顺着既有路径扫一遍既便宜
     * 也早就在做；而<b>饰品槽是另一套容器</b>，读它要走 Curios 的反射链路（每人 4~6 次调用）。
     * 若每 tick 对「所有玩家 × 所有饰品槽」都走一遍，在无限范围（creative 升级）多人大服上就是纯浪费。</p>
     *
     * <p>节流周期 = {@link Config#rangeChargerCuriosScanInterval}（默认 5 tick = 0.25 秒）：
     * 端到端延迟上限 0.25 秒，肉眼看不出差别，额外开销最大只有既有背包扫描的 1/5。</p>
     */
    private int curiosScanCooldown;

    // ==================== 充电过滤（黑/白名单 + 三类名单，服务端权威） ====================
    /**
     * 本机的充电过滤配置（<b>唯一权威实例</b>）。
     *
     * <p><b>为什么是 final</b>：菜单 / 网络包 / 存档加载都要「整体覆盖」它。字段 final 就意味着
     * 覆盖只能是 {@code copyFrom(...)} 改内容 —— 任何持有本机引用的地方（例如已经打开的界面快照）
     * 永远是同一份配置，不存在「谁拿到的是旧对象」这种分叉。判定语义全部在
     * {@link ChargingFilter}，本类不写任何黑/白名单判断。</p>
     */
    private final ChargingFilter chargingFilter = new ChargingFilter();
    /**
     * 本机<b>范围内检测到的、有 FE 能力的方块</b>的注册名（第 110 轮，供过滤界面「方块」页的候选池）。
     *
     * <h2>为什么要有这份集合（而不是让客户端自己扫）</h2>
     * <p>物品的两页候选池能在客户端构建，是因为「哪些物品能充电」只取决于注册表 + 能力系统，
     * 两端完全一致；而「本机充电范围内有哪些方块」是<b>服务端世界的事实</b>（区块是否加载、
     * 方块实体是否还在、距离多远），客户端无从自查。因此由服务端在扫描时顺手收集、随快照下发。</p>
     *
     * <h2>口径（与「目标」严格区分）</h2>
     * <ul>
     *     <li>只收<b>真的能收电</b>的方块实体：{@code Capabilities.EnergyStorage.BLOCK} 非空
     *     <b>且</b> {@code canReceive()} <b>且</b> {@code getMaxEnergyStored() > 0}
     *     （判据单点在 {@link #isChargeableBlock}）—— 「能充电的方块」与「能充电的物品」
     *     用同一类判据，不是「所有方块」、也不是「所有有 FE 能力的方块」。
     *     <b>第 122 轮 3-1/3-2</b>：旧口径只看能力非空，于是「只出不进」的发电机
     *     （用户原话「应力发电机它是发电的，它本身是不能存电的」）与 RS 控制器
     *     （能力是真的、<b>能收电</b>，但用户明确说它不该出现）全被列进来；</li>
     *     <li><b>满电的、被过滤掉的、不在白名单内的都算</b>：它们是「候选」而不是「目标」。
     *     这一点很关键 —— 若只收「本 tick 真被充的方块」，玩家把某类方块拉黑之后就再也看不到它，
     *     也就<b>无法取消勾选</b>（行会从列表里消失），那是死锁；</li>
     *     <li><b>本机自己也算</b>（第 122 轮 3-3）：范围充电器自己就是「能收电的自家方块」，
     *     但扫描循环必须跳过自身（绝不给自己充电）。因此自身登记放在扫描<b>之前</b>单独做一次
     *     （{@link #recordSeenSelf}），与「跳过自身充电」彻底分离 —— 旧写法把登记写在
     *     {@code chargeBlock} 里、而 {@code scanBlocks} 先 {@code continue} 跳过自身，
     *     自家方块因此永远进不了候选池（用户原话「我们模组中添加的范围充电器嘛，你这里面怎么都没有」）；</li>
     *     <li>容量上界 {@link ChargerFilterLimits#MAX_BLOCK_ENTRIES}：大范围 + 密集机器区里
     *     方块实体数可能上千，收集集合必须有界（否则范围升级能把内存吃掉）；</li>
     *     <li>只在 {@code Config.rangeChargerChargeBlocks} 打开的 tick 重建（见 {@link #doCharging}）：
     *     方块充电总闸关掉时这个池子没有意义，保留上一次的内容比清空更不干扰玩家
     *     （玩家可能先关总闸、再进界面取消勾选）。</li>
     * </ul>
     */
    private final Set<ResourceLocation> seenBlocks = new LinkedHashSet<>();
    /**
     * 本机「高亮正在充电的对象」开关（<b>落盘</b>、默认开）。
     *
     * <p><b>为什么还要它，而不是只留 {@link Config#rangeChargerHighlightTargets}</b>：
     * 那个配置项是<b>总闸</b>（关掉 = 全服所有充电器都不高亮、一个包都不发）；
     * 玩家真正需要的是「这台机器我看腻了，别亮」，而又不想改配置文件 / 影响别的机器 ——
     * 所以再叠一个每台机器自己的开关。两层都与「发不发包」有关，判定集中在
     * {@link #syncHighlightTargets(Level)} 开头一处。</p>
     */
    private boolean highlightEnabled = true;

    // ==================== 充电目标高亮：本 tick「真充了谁」 ====================
    // 只在 accepted > 0 的分支登记（见 chargeBlock / scanItems / scanItemsInfinite / chargePlayer），
    // 每 tick 在 doWork 开头清空 ⇒ 集合内容 = 这一 tick 实际发生能量转移的目标。
    private final Set<BlockPos> tickChargedBlocks = new HashSet<>();
    private final Set<Integer> tickChargedItemIds = new HashSet<>();
    private final Set<UUID> tickChargedPlayers = new HashSet<>();
    /** 下一次高亮同步的倒计时（每 {@link #HIGHLIGHT_SYNC_INTERVAL_TICKS} tick 发一次）。 */
    private int highlightSyncCooldown;
    /**
     * 上一次同步的内容是否非空 —— 只记这一个布尔值，用来决定「从有到无」时要不要补发一条空快照。
     *
     * <p>第 95 轮交付前复核：这里原本还放了三个 `lastHighlightBlocks / ItemIds / Players` 列表，
     * 但**全类没有任何读取点**（只在写入处和清空处出现），注释却声称它们用于「集合变化才重建」——
     * 那是假的（「变化才重建」在客户端 {@code ChargerTargetHighlight#accept} 里做，服务端只发心跳）。
     * 三个死字段已删除，注释同步改成事实。</p>
     */
    private boolean highlightSyncedNonEmpty;

    public RangeChargerBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(), pos, state, new RangeChargerNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        // <b>用可「直接设值」的子类</b>（2026-10-05 修「重进存档后重新充电」）：
        //
        // 实测日志：{@code load-done savedEnergy=1000000 loadedEnergy=5000 maxReceive=5000}
        // —— 存档里确实是满电 100 万，但恢复后只剩 5000，因为 NeoForge 的
        // {@link EnergyStorage#receiveEnergy} 会被<b>自身 maxReceive（= 每 tick 充电/传输速率）</b>
        // 夹住。加载是「<b>设定值</b>」语义、不是「接收能量」语义，因此必须绕过这个夹量。
        // 2026-10-17 修「范围充电器再也充不到无线终端、界面上只剩 1 个充电对象」：
        // 旧写法是两参构造 new EnergyStorage(capacity, maxTransfer)，NeoForge 会把 maxReceive 与
        // maxExtract 同时设成 maxTransfer。而 Config.rangeChargerMaxTransfer 的语义只是「接收外部
        // 能量的最大速率」，被顺手套在输出上之后，充电器每 tick 对外最多只能送出 5000 FE（用户配置值）。
        // 于是「谁先被扫到谁就把这 5000 全吃掉」——见 doCharging 里的顺序说明。
        // 现在三条夹量显式分开：容量 / 输入 / 输出。内部存储不再对「自己从网络里抽出来、又自己存进去」
        // 的那条路做截断（截断掉的部分是从网络扣掉却没人收到的，纯损耗，见 CappedEnergyStorage 的注释）；
        // 对外部机器的输入仍由 CappedEnergyStorage 按 Config.rangeChargerMaxTransfer 夹住，配置语义不变。
        this.energyStorage = new RestorableEnergyStorage(Config.rangeChargerEnergyCapacity);
        this.externalEnergyStorage = new CappedEnergyStorage(
            energyStorage, Config.rangeChargerMaxTransfer);
        // 任何写入（点击 / Shift 移动 / 漏斗 / NBT 载入）都会立刻校验：非法升级弹出、超上限弹出。
        this.upgradeContainer = new ItemStackHandler(6) {
            /**
             * <b>每格最多 1 个升级</b>（与界面 {@link cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot}
             * 的 {@code getMaxStackSize() = 1} 同一口径，见 {@code UpgradeSlot.java:104-112}）。
             *
             * <p><b>为什么必须覆写（R1 的第一道防线）</b>：{@code ItemStackHandler} 的默认实现放行
             * {@code getSlotLimit(slot)}（本版 NeoForge 是 99），于是漏斗 / 管道可以往一格塞几十个升级，
             * 而速率侧 {@code countUpgrades} 是按个数累加的 ⇒ 升级上限被直接绕过
             * （旧写法还会因 {@code 500 << 64} 的掩码效应反而变慢）。夹到 1 之后「一格多个」在
             * <b>写入时</b>就不可能发生；{@link #validateUpgradeSlots()} 里的按个数计数只是针对
             * 旧存档 / 直接 NBT 写入的第二道防线。</p>
             */
            @Override
            protected int getStackLimit(final int slot, final ItemStack stack) {
                return Math.min(1, stack.getMaxStackSize());
            }

            /** 同上：对外暴露的槽上限也必须是 1（菜单 / 任何读 {@code IItemHandler} 的调用方都看得到）。 */
            @Override
            public int getSlotLimit(final int slot) {
                return 1;
            }

            @Override
            protected void onContentsChanged(final int slot) {
                super.onContentsChanged(slot);
                if (level != null && !level.isClientSide()) {
                    validateUpgradeSlots();
                }
            }
        };
    }

    /**
     * <b>能「直接设值」的能量存储</b>：只给 {@code loadAdditional} 恢复存档电量用。
     *
     * <h2>为什么必须有它（2026-10-05 实测日志）</h2>
     * <p>{@code RangeChargerBlockEntity} 出厂用的 {@link net.neoforged.neoforge.energy.EnergyStorage}
     * 只有 {@code receiveEnergy} 一条写入路径，而它会把自己的 {@code maxReceive}（本机 = 充电 / 传输速率，
     * 用户配置里是 5000）当成上限：
     * <pre>accepted = min(maxReceive, capacity - energy)</pre>
     * 于是「把存档里的 1,000,000 恢复进去」只会写进 5000，剩下的 99.5% 被静默丢掉 ——
     * 玩家看到的就是「存了满电，重进又要重新充」。</p>
     *
     * <p>{@link #restoreEnergy(int)} 直接设值（只夹到 [0, capacity]），因此与速率无关。
     * 这是<b>加载语义</b>，不是「接收能量」，所以不受 {@code maxReceive} 约束在语义上也是正确的。</p>
     *
     * <p>对外仍是一个普通 {@link EnergyStorage}（对外那一层见 {@link CappedEnergyStorage}）：
     * 充电消耗走父类 {@code extractEnergy}、外部机器灌电走父类 {@code receiveEnergy}。</p>
     *
     * <p><b>2026-10-17：三条夹量必须分开</b>。父类的 {@code (capacity, maxReceive, maxExtract)}
     * 三参构造才是这层的真实语义。旧代码走两参构造 ⇒ {@code maxExtract == maxReceive == maxTransfer}
     * ⇒ 整台充电器每 tick 对外只能送 {@code rangeChargerMaxTransfer}（用户配置 5000 FE），
     * 第一个被扫到的目标（RS 控制器，永远充不满）就把这一份全吃掉，后面的物品 / 玩家一格电都拿不到。
     * 现在内部 {@code maxReceive = maxExtract = capacity}：
     * 上游 {@code RangeChargerNetworkNode} 已经用 {@code extract(space)} 从网络里把电抽出来了，
     * 这里再按 5000 截断的话，差额（最多 capacity − maxTransfer ≈ 99.5 万 FE/tick）
     * <b>已经从网络扣掉却没人收到</b>，是纯损耗；放开后「抽多少收多少」，且缓存充满后
     * {@code space = 0}，节点自然停止抽取（对外部机器的限速仍由 {@link CappedEnergyStorage} 保证）。</p>
     */
    private static final class RestorableEnergyStorage extends EnergyStorage {
        RestorableEnergyStorage(final int capacity) {
            // 输入 / 输出都放到容量：真正该夹住「给每个目标多少」的是 transferBudget()，
            // 真正该夹住「外部机器能灌多快」的是 CappedEnergyStorage。
            super(capacity, capacity, capacity);
        }

        /** 把存储量<b>直接设为</b> {@code amount}（夹到 [0, capacity]）；与 maxReceive 无关。 */
        void restoreEnergy(final int amount) {
            this.energy = Math.clamp(amount, 0, this.capacity);
        }
    }

    /**
     * 对外暴露的能量视图：把<b>输入与输出</b>都夹到 {@link Config#rangeChargerMaxTransfer}。
     *
     * <p><b>为什么要这一层</b>：{@link RestorableEnergyStorage} 的内部 maxReceive 必须是 capacity
     * （见它的注释：否则 RS 网络节点抽出来的电会被静默截断，用户整张 RS 网络的电量都被烧掉）。
     * 但 {@link Config#rangeChargerMaxTransfer} 的文档语义是「<b>接收外部能量</b>的最大速率」，
     * 不能让任意机器借内部放开后一次灌 100 万。因此：内部存储放开、<b>对外视图夹紧</b>——
     * 两条路各自守住自己的契约，互不污染。</p>
     *
     * <p>纯委托：不缓存、不记账、不改变任何返回值语义（{@code simulate} 原样透传）。</p>
     */
    private static final class CappedEnergyStorage implements IEnergyStorage {
        private final IEnergyStorage delegate;
        private final int maxTransfer;

        CappedEnergyStorage(final IEnergyStorage delegate, final int maxTransfer) {
            this.delegate = delegate;
            this.maxTransfer = Math.max(1, maxTransfer);
        }

        @Override
        public int receiveEnergy(final int toReceive, final boolean simulate) {
            return delegate.receiveEnergy(Math.min(Math.max(0, toReceive), maxTransfer), simulate);
        }

        @Override
        public int extractEnergy(final int toExtract, final boolean simulate) {
            return delegate.extractEnergy(Math.min(Math.max(0, toExtract), maxTransfer), simulate);
        }

        @Override
        public int getEnergyStored() {
            return delegate.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return delegate.getMaxEnergyStored();
        }

        @Override
        public boolean canExtract() {
            return delegate.canExtract();
        }

        @Override
        public boolean canReceive() {
            return delegate.canReceive();
        }
    }

    public EnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    /** 对外（能力）视图：输入 / 输出按 {@link Config#rangeChargerMaxTransfer} 夹住。 */
    public IEnergyStorage getExternalEnergyStorage() {
        return externalEnergyStorage;
    }

    public ItemStackHandler getUpgradeContainer() {
        return upgradeContainer;
    }

    private int countUpgrades(final ResourceLocation upgradeId) {
        final net.minecraft.world.item.Item upgradeItem =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(upgradeId);
        int count = 0;
        for (int i = 0; i < upgradeContainer.getSlots(); i++) {
            final ItemStack stack = upgradeContainer.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(upgradeItem)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 单目标充电速率：基础值按等效升级级数逐级翻倍。速度升级每个翻倍；堆叠升级是高级版
     * （1 个 = 3 个速度升级，×8），与界面显示同源。
     * <p>走 {@link RsccRateLimiter#rateSaturating}（饱和移位）：
     * {@code Config.rangeChargerChargeRate} 允许到 1,000,000，而 6 堆叠 = power 18 ⇒
     * {@code 1e6 << 18} 在旧写法里溢出成<b>负数</b>，而返回值在上游一律被
     * {@code Math.min(getChargeRate(), 电量)} 夹住 ⇒ {@code min(负数, 正数) = 负数} ⇒ <b>完全不充电</b>。
     * 现在越界即封顶到 {@link Integer#MAX_VALUE}（I3），并且升级个数被 {@code level()} 夹到 6（I4）。</p>
     */
    public int getChargeRate() {
        return RsccRateLimiter.rateSaturating(Config.rangeChargerChargeRate,
            countUpgrades(SPEED_UPGRADE), countUpgrades(STACK_UPGRADE));
    }

    /**
     * 速度+堆叠升级的<b>等效级数</b>（堆叠按 3 个速度折算；供 GUI 显示充电速率）。
     * <p>收敛到 {@code [0, MAX_POWER=24]}：合法玩法下与旧的
     * {@code 速度数 + 堆叠数*3} 逐格相同，但漏斗往一格塞 64 个时不会再把界面上的
     * {@code <<} 推到溢出区间（与 {@link #getChargeRate()} 同源，I4 上限单点）。</p>
     */
    public int getUpgradeCount() {
        return RsccRateLimiter.power(countUpgrades(SPEED_UPGRADE), countUpgrades(STACK_UPGRADE));
    }

    /** 当前已放入的范围升级数量。 */
    public int getRangeUpgradeCount() {
        return countUpgrades(RANGE_UPGRADE);
    }

    /** 是否已放入 creative_range_upgrade（无限范围：不限制距离，任何已加载/在线玩家物品都充电）。 */
    public boolean hasInfiniteRange() {
        return countUpgrades(CREATIVE_RANGE_UPGRADE) > 0;
    }

    /** 单轴范围上限：默认 100；每个范围升级 +{@link Config#rangeChargerRangePerUpgrade}；
     *  放入 creative_range_upgrade 后视为无限（返回一个极大值供输入框兜底）。 */
    public int getMaxRange() {
        return hasInfiniteRange() ? Integer.MAX_VALUE - 1
            : BASE_MAX_RANGE + getRangeUpgradeCount() * Config.rangeChargerRangePerUpgrade;
    }

    public RangeChargerNetworkNode getNode() {
        return mainNetworkNode;
    }

    /**
     * 供菜单同步的实时数据：0/1/2 = 三轴范围，3/4 = 能量/上限，5 = 当前充电对象数，6 = 速度/堆叠升级总数，
     * 7 = 范围升级数，8 = 是否无限范围（creative_range_upgrade），9 = 红石模式（0/1/2，服务端权威），
     * 10/11 = <b>原始</b>速度升级个数 / 堆叠升级个数（界面算速率时与方块实体传同一对参数，见
     * {@code RangeChargerMenu#getChargeRate()}），12 = 本机「高亮充电目标」开关（0/1），
     * 13 = <b>上一次扫描因已满被跳过的目标数</b>（第 102 轮：让玩家看见「跳过」真的在发生）。
     *
     * <p><b>为什么 10/11 要传「原始个数」而不是「等效档数」</b>：界面上一轮曾经把已经折算好的
     * {@code power(...)}（0..24）当成 {@code speedUpgrades} 再喂回 {@code rateSaturating}，
     * 而后者会用 {@code level()} 把它再夹到 6 ⇒ 界面最多显示 {@code base << 6}，
     * 方块实体却能到 {@code base << 24}。现在两边传的是<b>同一对原始计数</b>，
     * 调用的也是<b>同一个函数</b>，逐格相等不是「看起来一样」，而是同一个纯函数的同一个输入。</p>
     */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> rangeX;
                    case 1 -> rangeY;
                    case 2 -> rangeZ;
                    case 3 -> energyStorage.getEnergyStored();
                    case 4 -> energyStorage.getMaxEnergyStored();
                    case 5 -> lastChargedTargets;
                    case 6 -> getUpgradeCount();
                    case 7 -> getRangeUpgradeCount();
                    case 8 -> hasInfiniteRange() ? 1 : 0;
                    // 红石模式：走 RS 的 RedstoneModeSettings 映射（与 RS 原版机器同一套 0/1/2 编码）
                    case 9 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    // 原始计数（不折算、不夹）：界面与方块实体共用同一组输入
                    case 10 -> countUpgrades(SPEED_UPGRADE);
                    case 11 -> countUpgrades(STACK_UPGRADE);
                    // 本机高亮开关（0/1）：界面画「高亮」按钮的开关态，绝不自己猜
                    case 12 -> highlightEnabled ? 1 : 0;
                    // 第 102 轮：上一次扫描因「已经充满」被跳过的目标数（界面上「已满跳过 N」那一行）
                    case 13 -> lastSkippedFullTargets;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 范围与能量只能由服务端逻辑修改
            }

            @Override
            public int getCount() {
                return 14;
            }
        };
    }

    public int getRangeX() {
        return rangeX;
    }

    public int getRangeY() {
        return rangeY;
    }

    public int getRangeZ() {
        return rangeZ;
    }

    /**
     * 客户端点击「查看充电范围」时，把<b>当前</b>坐标与三轴范围回发给该玩家。
     *
     * <h2>为什么在方块实体里做（而不是客户端自己算）</h2>
     * <p>客户端菜单由注册期的二参工厂重建（普通 {@code MenuType}，不走 {@code IMenuTypeExtension}），
     * 客户端侧的 {@code RangeChargerMenu#getChargerPos()} <b>恒为 null</b> —— 坐标必须由服务端下发。
     * 三轴范围虽然已由 {@code ContainerData} 0/1/2 同步，这里仍与坐标打包成同一份快照回传，
     * 避免「坐标是新的、范围是上一 tick 的」两处拼出来的框。</p>
     *
     * <p><b>只读</b>：不修改范围 / 能量 / 扫描状态，也不触发任何充电逻辑；调用方（菜单）
     * 已先校验 {@code stillValid(player)}（维度、距离、方块实体仍是它自己）。</p>
     */
    public void sendRangeOutline(final net.minecraft.server.level.ServerPlayer player, final int containerId) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new cretae.cookiewyq.rs_create_compat.network.ShowChargerRangePacket(
                containerId, worldPosition, rangeX, rangeY, rangeZ));
    }

    // ==================== 充电过滤 / 高亮开关：给菜单与网络包的公开入口 ====================

    /**
     * 当前过滤配置的<b>副本</b>（只读出口）。
     * <p>为什么回副本而不是内部实例：这份配置是服务端权威，调用方（网络包组快照 / 界面）
     * 只需要读；把内部集合交出去等于把「谁都能改权威配置」这条后门留在外面 ——
     * 改配置必须走 {@link #setChargingFilter}，那里才有 {@code setChanged()} 与整体覆盖语义。</p>
     */
    public ChargingFilter getChargingFilter() {
        return chargingFilter.copy();
    }

    /**
     * 「本机范围内检测到的可充方块」候选池的一份<b>有序副本</b>（第 110 轮，过滤界面用）。
     *
     * <p>排序理由与 {@code ChargingFilter#sortedBlocks} 一样：同一份世界状态每次组包得到同一串字节，
     * 便于对照与排障。拷贝理由同样：内部集合是服务端权威，交出去等于开后门。</p>
     */
    public List<ResourceLocation> getSeenBlocks() {
        final List<ResourceLocation> ids = new ArrayList<>(seenBlocks);
        ids.sort(ResourceLocation::compareTo);
        return ids;
    }

    /**
     * 整体覆盖过滤配置（过滤界面的全量快照 / 存档加载共用）。
     * <p>只动过滤配置：能量、三轴范围、升级槽、红石模式、高亮开关一个都不碰。
     * 覆盖后立刻清空「本 tick 已登记的高亮集合」没有必要 —— 下一 tick {@code doWork} 开头本来就会重建，
     * 而清空反而会让刚改完配置的那一帧少一次高亮。</p>
     */
    public void setChargingFilter(final ChargingFilter filter) {
        if (filter == null) {
            return;
        }
        chargingFilter.copyFrom(filter);
        setChanged();
    }

    /** 本机「高亮充电目标」开关当前是否打开（界面按钮读它）。 */
    public boolean isHighlightEnabled() {
        return highlightEnabled;
    }

    /**
     * 切换本机高亮开关（界面按钮 id 7）。
     * <p>关掉后下一次 {@link #syncHighlightTargets} 会把「上次非空」记忆清掉并<b>不再发包</b>，
     * 客户端因此收不到任何新快照、旧高亮自然过期 —— 不需要额外发一条「清空包」。</p>
     */
    public void toggleHighlightEnabled() {
        highlightEnabled = !highlightEnabled;
        setChanged();
    }

    /**
     * 组一份过滤配置快照发给该玩家（界面点「过滤」时用）。
     *
     * <p><b>为什么 containerId 从 {@code player.containerMenu} 现取</b>：服务端菜单是有方块实体引用的
     * 那一侧，但「当前打开的是哪张菜单」只有 {@code player.containerMenu} 是权威；
     * 取不到时发 {@code -1}（客户端只拿它判断「这是不是我这次请求的回应」，不作为安全依据）。
     * 安全校验仍在调用方（{@code OpenChargerFilterPacket} 的三道校验）与菜单 {@code stillValid}。</p>
     *
     * <p><b>为什么这里顺手 record 玩家</b>：专用服务端刚启动时登录事件可能还没分发，
     * 而过滤界面的「已知玩家」候选池必须至少包含<b>正在操作的那个人</b> ——
     * 否则第一眼看到的是一个空列表。record 是幂等的，不会造成额外落盘。</p>
     */
    public void sendFilterSnapshot(final net.minecraft.server.level.ServerPlayer player) {
        if (player == null || level == null || level.isClientSide()) {
            return;
        }
        ChargerKnownPlayers.record(player);
        final int containerId = player.containerMenu == null ? -1 : player.containerMenu.containerId;
        final List<cretae.cookiewyq.rs_create_compat.network.SyncChargerFilterPacket.KnownPlayer>
            known = new ArrayList<>();
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (final ChargerKnownPlayers.Entry entry : ChargerKnownPlayers.snapshot(serverLevel)) {
                // 在线状态是「服务端此刻的事实」：客户端无从自查，必须随快照算好下发
                known.add(new cretae.cookiewyq.rs_create_compat.network.SyncChargerFilterPacket
                    .KnownPlayer(entry.name(), entry.id(),
                    serverLevel.getPlayerByUUID(entry.id()) != null));
            }
        }
        // 组包口径只有 SyncChargerFilterPacket.of 一处：本类只交「权威配置 + 候选池」，
        // 不参与「快照里放哪些字段 / 黑名单还是白名单」这类协议细节（判定全在 ChargingFilter）。
        // 第 110 轮多交一份「范围内检测到的可充方块」候选池（见 {@link #seenBlocks}）。
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            cretae.cookiewyq.rs_create_compat.network.SyncChargerFilterPacket.of(
                containerId, chargingFilter, known, getSeenBlocks()));
    }

    public void adjustRangeX(final int delta) {
        rangeX = Math.clamp(rangeX + delta, 1, getMaxRange());
        setChanged();
    }

    public void adjustRangeY(final int delta) {
        rangeY = Math.clamp(rangeY + delta, 1, getMaxRange());
        setChanged();
    }

    public void adjustRangeZ(final int delta) {
        rangeZ = Math.clamp(rangeZ + delta, 1, getMaxRange());
        setChanged();
    }

    /** 升级变化后把各轴范围收敛到当前上限内（取下范围升级后超限部分回落）。 */
    private void clampRangesToMax() {
        final int max = getMaxRange();
        final boolean changed = rangeX > max || rangeY > max || rangeZ > max;
        if (changed) {
            rangeX = Math.clamp(rangeX, 1, max);
            rangeY = Math.clamp(rangeY, 1, max);
            rangeZ = Math.clamp(rangeZ, 1, max);
            setChanged();
        }
    }

    /** 无限升级取下后把数值回到非无限时的上限（如无范围升级 → 100），避免保留无限期间任意值。 */
    private void applyRangeFallbackAfterInfinite() {
        final boolean infinite = hasInfiniteRange();
        if (hadInfiniteRangeUpgrade && !infinite) {
            // 无限升级刚被取下：数值回落到当前（非无限）上限
            final int max = getMaxRange();
            rangeX = Math.clamp(rangeX, 1, max);
            rangeY = Math.clamp(rangeY, 1, max);
            rangeZ = Math.clamp(rangeZ, 1, max);
            // 若此前调得过大超出上限 → 收敛；否则保持。用户要求“取下后自动回 100”，
            // 而一般输入框当前值 < 上限时 clamp 不会回升，故此处直接归到上限。
            rangeX = max;
            rangeY = max;
            rangeZ = max;
            setChanged();
        }
        hadInfiniteRangeUpgrade = infinite;
    }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    /**
     * 由网络节点 ticker 每 tick 调用：先抽取网络能量，再执行充电扫描。
     * <p><b>红石模式</b>：RS 基类的 {@code calculateActive()} 已把「红石条件 + 已接入网络 + 能量充足」
     * 折算成节点 active；条件不满足（例如选了「高电平工作」但没给信号）时直接停机
     * ——充电只是停止，<b>不会销毁任何资源</b>。</p>
     */
    @Override
    public void doWork() {
        super.doWork(); // 节点 doWork：从 RS 网络抽取能量到缓存
        if (level == null || level.isClientSide()) {
            return;
        }
        // 本 tick 的目标集合每 tick 重建：只在真正充进去能量时登记，停机 / 能量耗尽时为空。
        tickChargedBlocks.clear();
        tickChargedItemIds.clear();
        tickChargedPlayers.clear();
        if (mainNetworkNode.isActive()) {
            validateUpgradeSlots(); // 清除历史版本遗留的非法升级（如自动合成）
            doCharging(level);
        }
        // 充电目标高亮：按固定节拍把「本 tick 真充了谁」同步给附近玩家（配置关闭时一个包都不发）
        if (--highlightSyncCooldown <= 0) {
            highlightSyncCooldown = HIGHLIGHT_SYNC_INTERVAL_TICKS;
            syncHighlightTargets(level);
        }
    }

    /**
     * 充电目标高亮：把<b>本 tick 真充了谁</b>同步给同维度、64 格内的玩家。
     *
     * <h2>什么算「真充了」</h2>
     * <p>只有 {@code accepted > 0}（真的发生了能量转移）的目标才会进集合：候选但充满、
     * 没有能量能力、能量耗尽被跳过的一律不算 —— 客户端因此不会出现「高亮一个根本没充的方块」。</p>
     *
     * <h2>什么时候发包（低开销）</h2>
     * <ul>
     *     <li>集合<b>非空</b>：每 {@link #HIGHLIGHT_SYNC_INTERVAL_TICKS} tick 发一次（心跳，客户端据此续命）；</li>
     *     <li>集合从<b>有到无</b>：补发一条空快照，让客户端立刻清掉发光 / 框；</li>
     *     <li>本来就是空：<b>不发</b>（不产生空包风暴）。</li>
     * </ul>
     *
     * <h2>配置关闭 / 本机开关关闭</h2>
     * <p>{@link Config#rangeChargerHighlightTargets}（<b>总闸</b>）为 {@code false}，或本机
     * {@link #highlightEnabled} 为 {@code false}（每台机器自己的开关）时，这里<b>直接返回、一个包都不发</b>；
     * 同时把「上次是非空」这个判定清掉，让重新打开后的第一条快照不会被当成「没有变化」而吞掉。</p>
     */
    private void syncHighlightTargets(final Level level) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        // 总闸（配置项）：关掉 = 全服所有充电器都不高亮、一个包都不发
        if (!Config.rangeChargerHighlightTargets) {
            resetHighlightSyncMemory();
            return;
        }
        // 本机开关：只关这一台。两个原因分开写，是因为「为什么没高亮」对玩家 / 排障是两种事实
        //（前者要改配置文件，后者点一下界面按钮就行）。
        if (!highlightEnabled) {
            resetHighlightSyncMemory();
            return;
        }
        final List<BlockPos> blocks = sortedChargedBlocks();
        final List<Integer> itemIds = sortedChargedItemIds();
        final List<UUID> players = sortedChargedPlayers();
        final boolean empty = blocks.isEmpty() && itemIds.isEmpty() && players.isEmpty();
        if (empty && !highlightSyncedNonEmpty) {
            return; // 本来就是空：不发空包
        }
        final cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket packet =
            new cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket(blocks, itemIds, players);
        for (final net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
            // serverLevel.players() 保证同维度；这里再按「以充电器为圆心 64 格」过滤
            if (player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) > HIGHLIGHT_NOTIFY_RADIUS * HIGHLIGHT_NOTIFY_RADIUS) {
                continue;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        }
        highlightSyncedNonEmpty = !empty;
    }

    /** 本 tick 充电过的方块（按 x/y/z 排序，保证同一集合每次序列化顺序一致）。 */
    private List<BlockPos> sortedChargedBlocks() {
        final List<BlockPos> blocks = new ArrayList<>(tickChargedBlocks);
        blocks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ));
        return blocks;
    }

    /** 本 tick 真正充进去能量的掉落物实体 id（升序）。 */
    private List<Integer> sortedChargedItemIds() {
        final List<Integer> ids = new ArrayList<>(tickChargedItemIds);
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** 本 tick 身上有物品真正被充电的玩家 UUID（升序）。 */
    private List<UUID> sortedChargedPlayers() {
        final List<UUID> ids = new ArrayList<>(tickChargedPlayers);
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** 配置关闭 / 未发送时忘掉「上次非空」这个判定（重新打开配置后的第一份空快照不会被吞掉）。 */
    private void resetHighlightSyncMemory() {
        highlightSyncedNonEmpty = false;
    }

    /**
     * 范围充电器只接受 速度/堆叠/范围 升级且每种最多 6 个（与 6 格插件槽一致）；
     * 发现槽内存在非法升级（例如自动合成）或超上限时，把物品弹出到世界，避免无效果的升级占用槽位误导玩家。
     * 校验完成后把三轴范围收敛到当前范围升级允许的上限。
     *
     * <p><b>R1 修复（2026-10，漏斗可以绕过升级上限的真实缺陷）</b>：旧实现按<b>槽位数</b>计数
     * （{@code counts[kind]++}，一格算一个），而 {@link #countUpgrades} 是按<b>物品个数</b>累加的
     * （{@code count += stack.getCount()}）。两个口径不一致，于是漏斗 / 管道往一格塞 64 个速度升级时，
     * 校验认为「只有 1 个」直接放行，速率侧却拿到 64 ⇒ {@code 500 << 64} 被 Java 移位掩码成
     * {@code 500 << (64 & 31) = 500}（<b>比插 6 个还慢</b>）。现在两处统一按<b>个数</b>计数，
     * 超出的部分<b>逐个弹出到世界</b>（绝不销毁）—— 与工程既有约定一致
     * （{@code CollectionCacheBlockEntity#enforceUpgradeCaps} 的「冗余弹出」、
     * {@code SchematicLoaderBlockEntity#dropBeyondKindCap} 的「保留排在前面的 N 个」）。</p>
     */
    private void validateUpgradeSlots() {
        // 与本类其它校验同一前置条件：没有服务端世界就没法「弹出到世界」，此时绝不删东西。
        if (validatingUpgrades || level == null || level.isClientSide()) {
            return;
        }
        validatingUpgrades = true;
        try {
            // 1) 非法升级：整格弹出（一格可能被塞了多个，逐个抽空；不做任何销毁）
            //    循环次数由「当前个数」界定（不用 while(!isEmpty())：万一同格数量不再减少也不会死循环）
            for (int i = 0; i < upgradeContainer.getSlots(); i++) {
                final ItemStack inSlot = upgradeContainer.getStackInSlot(i);
                if (inSlot.isEmpty() || upgradeKind(inSlot) >= 0) {
                    continue;
                }
                for (int n = inSlot.getCount(); n > 0; n--) {
                    dropOne(i);
                }
            }
            // 2) 单种上限：按「物品个数」计（与 countUpgrades 的 += stack.getCount() 同一口径）
            for (int kind = 0; kind < UPGRADE_KINDS; kind++) {
                final int max = kind == KIND_CREATIVE_RANGE
                    ? 1 : cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE;
                int kept = 0;
                for (int i = 0; i < upgradeContainer.getSlots(); i++) {
                    final ItemStack inSlot = upgradeContainer.getStackInSlot(i);
                    if (inSlot.isEmpty() || upgradeKind(inSlot) != kind) {
                        continue;
                    }
                    // 该格还能留几个：先到先得（保留排在前面的），余量逐个弹出
                    final int inSlotCount = inSlot.getCount();
                    final int allowed = Math.max(0, max - kept);
                    for (int excess = inSlotCount - allowed; excess > 0; excess--) {
                        dropOne(i);
                    }
                    kept += Math.min(inSlotCount, allowed);
                }
            }
            clampRangesToMax();
            applyRangeFallbackAfterInfinite();
        } finally {
            validatingUpgrades = false;
        }
    }

    /** 0=speed，1=stack，2=range，3=creative_range，其余返回 -1。 */
    private static int upgradeKind(final ItemStack stack) {
        final net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return -1;
        }
        return switch (id.getPath()) {
            case "speed_upgrade" -> 0;
            case "stack_upgrade" -> 1;
            case "range_upgrade" -> 2;
            case "creative_range_upgrade" -> KIND_CREATIVE_RANGE;
            default -> -1;
        };
    }

    /**
     * 从 {@code slot} 抽出 <b>1 个</b>物品并弹出到世界（名字如实：不叫 dropAndClear，因为它绝不清空整格）。
     * <p>调用方负责循环（一格可能有多个）。{@link #validateUpgradeSlots()} 已保证此时
     * {@code level != null} 且为服务端，因此这里绝不会把物品凭空销毁。</p>
     */
    private void dropOne(final int slot) {
        final ItemStack removed = upgradeContainer.extractItem(slot, 1, false);
        if (!removed.isEmpty() && level != null) {
            net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
                level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, removed);
            entity.setDeltaMovement(0, 0.1, 0);
            level.addFreshEntity(entity);
        }
        setChanged();
    }

    private void doCharging(final Level level) {
        lastChargedTargets = 0;
        // 与 lastChargedTargets 同一生命周期：只统计「本次扫描」的跳过数，界面每 tick 读到的
        // 就是这一 tick 的事实（不做跨 tick 累计，否则数字只会越来越大、越看越不明白）。
        lastSkippedFullTargets = 0;
        if (energyStorage.getEnergyStored() <= 0) {
            return;
        }
        // 饰品槽（Curios）这一档按节流周期走一次（背包族仍然每 tick，见 nextCuriosPass 的说明）。
        final boolean curiosPass = nextCuriosPass();
        // 第 110 轮：方块候选池每 tick 重建（只在方块充电总闸打开时）。
        // 放在「电量 > 0」的守卫之后：机器没电时本 tick 根本不会扫方块，
        // 此时清空会把界面上次看到的那批方块也抹掉（玩家会以为「范围内的方块忽然没了」）。
        if (Config.rangeChargerChargeBlocks) {
            seenBlocks.clear();
            // 第 122 轮 3-3：本机自身先登记一次。
            // <b>为什么必须在扫描之前、单独做</b>：两条扫描都有一个 `pos.equals(worldPosition)`
            // 的 `continue`（绝不给自己充电），而候选登记原来只发生在 `chargeBlock` 里
            // ⇒ 自家范围充电器永远进不了「世界中的方块」页（用户原话「我们模组中添加的
            // 范围充电器嘛，你这里面怎么都没有」）。把「登记」放在「跳过充电」之前，
            // 两个动作从此分离：自身 = 候选但非目标；别的方块 = 既候选又可能是目标。
            recordSeenSelf(level);
        }
        // <b>2026-10-17 修「充电对象只剩 1 个、而且正好是 RS 控制器」</b>：
        // 三档共用同一罐电量，旧顺序是「方块 → 掉落物 → 玩家」。而 RS 控制器这类方块是
        // <b>永远充不满</b>的：它的能量存储就是整张 RS 网络的能量池，而本机节点
        // {@code RangeChargerNetworkNode#doWork} 每 tick 又用 {@code extract(space)} 把这张网络池抽到
        // 自己缓存里（改前抽到的才存得下、抽多了还是纯损耗，本次一并修掉，见 RestorableEnergyStorage）
        // ——于是控制器的 stored 永远是 0 / 远低于容量，每 tick 都会被判成「可充电」并排在队伍最前面。
        // 只要单目标速率 ≥ 本 tick 可用电量，它就把电全吃掉；后面的掉落物 / 玩家物品只看到
        // {@code transfer = min(rate, 0) = 0}，一件都不充（{@code chargeItemStack} 里
        // {@code transfer <= 0} 直接 return false），界面上的 {@code lastChargedTargets}
        // 因此恒等于 1，且那一个就是控制器。
        //
        // 修法有两层，缺一不可：
        //   ① 顺序改成「玩家 → 掉落物 → 方块」：玩家的无线终端（本功能的主用途）最先拿到电；
        //   ② 每个目标的取电量走 {@link #transferBudget()}：按最大目标数均分，任何单个目标
        //      （无论它多大、多空）都不可能一次吃光整罐电量。
        if (hasInfiniteRange()) {
            // 无限范围：不再受 rangeX/Y/Z 限制 —— 全维度在线玩家与掉落物，以及玩家所在已加载区块内的方块
            if (Config.rangeChargerChargePlayerItems) {
                lastChargedTargets += scanPlayersInfinite(level, curiosPass);
            }
            if (Config.rangeChargerChargeItems) {
                lastChargedTargets += scanItemsInfinite(level);
            }
            if (Config.rangeChargerChargeBlocks) {
                lastChargedTargets += scanBlocksInfinite(level);
            }
            return;
        }
        // 每 tick 都执行充电；扫描耗电很小，不再因能量不足而跳过
        if (Config.rangeChargerChargePlayerItems) {
            lastChargedTargets += scanPlayers(level, curiosPass);
        }
        if (Config.rangeChargerChargeItems) {
            lastChargedTargets += scanItems(level);
        }
        if (Config.rangeChargerChargeBlocks) {
            lastChargedTargets += scanBlocks(level);
        }
    }

    /**
     * 本 tick <b>单个目标</b>最多能拿多少 FE —— 按「最大目标数」均分，绝不让第一个目标吃光整罐电量。
     *
     * <h2>为什么需要（用户实测：范围充电器完全不充无线终端，充电目标只剩 RS 控制器）</h2>
     * <p>旧实现在四个充电点（玩家物品 / 掉落物 / 方块 / 无限范围的掉落物）都写
     * {@code transfer = Math.min(getChargeRate(), energyStorage.getEnergyStored())}：第一个被扫到的目标
     * 只要自己的速率 ≥ 当前电量，就会把<b>整罐</b>电一次抽干。RS 控制器正是这种目标（永远充不满，
     * 见 {@link #doCharging} 的链路说明），于是它每 tick 吃掉全部，物品 / 玩家一档拿到 0。</p>
     *
     * <p>均分口径：{@code min(升级速率, 当前电量 / 最大目标数)}，并夹到 ≥ 1（电量 > 0 时至少能送出 1 FE）。
     * 合法玩法下（速率 ≪ 电量 / 目标数）结果与旧写法<b>逐格相同</b>（例如电量 100 万、目标数 50、
     * 速率 500 ⇒ 仍旧是 500）；只有在「速率会吃光整罐」的退化区间里才真正起作用。
     * 目标数少时没被拿走的电留在缓存里，不会丢。</p>
     *
     * <h2>「已经充满的目标」要不要从分母里扣掉（第 102 轮明确：不扣，且不需要扣）</h2>
     * <p>满电目标在三档扫描里都<b>排在 {@code transferBudget()} 之前的满电判定</b>处被直接
     * {@code continue} / {@code return} 掉（见 {@link #chargeItemStack} / {@link #chargeBlock} /
     * {@link #scanItems}），所以它们：<b>不分摊预算、不消耗电量、不计入目标数、不登记高亮</b>。
     * 换句话说「满目标拖慢没满目标」这件事在<b>本方法里根本不存在</b> —— 本方法的分母是配置上界
     * {@link Config#rangeChargerMaxTargets}（一个与「现场有几个满目标」无关的常数）：
     * <ul>
     *     <li>分母不会因为满目标多而变大 ⇒ 没满目标的单份预算不会变小；</li>
     *     <li>真正的稀释只发生在「<b>所有</b>候选目标数远小于 maxTargets」时（机器旁边只有 1 个
     *     没满目标、上界却是 50 ⇒ 它每 tick 只拿到 电量/50）。这与满不满无关，改它等于改既有
     *     速率语义（{@code tools/selfcheck_round52_charger_curios.py} 的 8.4 也锚定了这一行的
     *     字面量），而且「本 tick 实际参与数」只有<b>先扫一遍全部候选</b>才知道（正是本方法要避免的
     *     那次遍历）—— 因此这里<b>刻意不按实际参与数重分摊</b>：省下来的预算留在缓存里给下一 tick，
     *     总量不变，只是「电量接近见底」时单个目标的到账节奏慢一档。</li>
     * </ul>
     * <p><b>第 127 轮：立方不走这里</b> —— FE 能量立方是「电池」，按
     * {@link #cubeTransferBudget()} 的<b>电池档</b>（{@link Config#feEnergyCubeMaxTransfer}）结算；
     * 本方法只服务玩家物品 / 掉落物 / 非立方的方块目标。</p>
     */
    private int transferBudget() {
        final int stored = energyStorage.getEnergyStored();
        if (stored <= 0) {
            return 0;
        }
        final int share = stored / Math.max(1, Config.rangeChargerMaxTargets);
        return Math.max(1, Math.min(getChargeRate(), share));
    }

    /**
     * <b>立方目标</b>本 tick 的预算：<b>电池档</b>（第 127 轮）。
     *
     * <p>用户口径（两次口述，见 {@link #chargeBlock} 里的注释）：「一面墙的立方应该瞬间被充满」、
     * 「立方本身就是导线，范围充电器接上去吸的是整个集群的电量」。立方在能力层早就是
     * <b>整簇视图</b>（{@link FEEnergyCubeBlockEntity.ClusterEnergyCapability}），
     * 所以剩下的唯一瓶颈是「每个目标只分到 500 FE/t」——那本是给<b>工具 / 玩家物品</b>的
     * 一档（{@link #transferBudget()} 按 {@link Config#rangeChargerMaxTargets} 均分）。
     * 电池不该按工具档充：它的「面速率」{@link Config#feEnergyCubeMaxTransfer}（默认 10 万 FE/t）
     * 才是它的输入上限，这里直接用这一档。
     *
     * <p><b>语义与 {@link #transferBudget()} 保持一致</b>（调用方不区分两条分支）：
     * <ul>
     *     <li><b>纯函数</b>：只读本机缓存，不写世界、不扣电、不登记高亮 —— 「先算预算再 simulate 判满」
     *     的前提（{@link #chargeBlock} 里那段顺序说明）；</li>
     *     <li><b>缓存见底返回 0</b>：调用方 {@code transfer <= 0} 早退的语义不变；</li>
     *     <li><b>夹到本机缓存</b>：一次最多把缓存里现有的电给出去，绝不透支。</li>
     * </ul>
     *
     * <p><b>为什么「一块墙 N 块」可以每块各拿 10 万</b>：本方法是<b>每个目标</b>的预算，
     * 充电器每 tick 会把它覆盖到的每一块立方各喂一次（{@code scanBlocks} 上限
     * {@link Config#rangeChargerMaxTargets}）。这些能量都进同一个集群池（能力层
     * {@code receiveEnergy} 先填接收方、再按字典序填其它成员），所以观感就是「整面墙一起涨」。
     * 总额仍受两道上限约束：本机缓存（一次最多给光）+ 网络抽取速率
     * {@link Config#rangeChargerNetworkPullPerTick}（补回来的速度）——<b>不产生额外能量</b>。
     *
     * <p><b>满电判定不变</b>：本方法只决定「offer 多少」，是否真的收得下仍由
     * {@code storage.receiveEnergy(transfer, true)} 回答（立方视图内部按 long 算空间，
     * 43 块以上 int 饱和时也不会误判满，见 {@link #chargeBlock} 的 R-1 说明）。
     */
    private int cubeTransferBudget() {
        final int stored = energyStorage.getEnergyStored();
        if (stored <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(stored, Math.max(1, Config.feEnergyCubeMaxTransfer)));
    }

    /**
     * 饰品槽扫描的节流闸门：返回「本 tick 要不要扫饰品槽」，并推进计数。
     *
     * <p>周期语义 = {@link Config#rangeChargerCuriosScanInterval}（默认 5）—— 连续两次扫描之间正好隔这么多 tick
     * （间隔为 1 时退化成「每 tick 都扫」，与背包族一致）。</p>
     *
     * <p><b>为什么不改写成 {@code RsccRateLimiter.intervalTicks(...)}</b>：这一档是<b>固定节拍</b>
     * （不吃任何升级，{@code >> 0} 恒等，数值本来就与改前逐格相同，也没有溢出 / 除零风险），
     * 而 {@code tools/selfcheck_round52_charger_curios.py} 锚定了下面这行赋值语句的字面量。
     * 设计文档 §4.6 的结论也是「这一行不动」（本次的硬要求是<b>零自检改动</b>）。
     * 因此这里保留原写法，仅把「为什么保留」记在注释里。</p>
     */
    private boolean nextCuriosPass() {
        if (curiosScanCooldown > 0) {
            curiosScanCooldown--;
            return false;
        }
        curiosScanCooldown = Math.max(0, Config.rangeChargerCuriosScanInterval - 1);
        return true;
    }

    /** 无限范围：遍历当前维度<b>所有在线玩家</b>，给其身上（背包族 + 饰品槽）的可充电物品充电（无距离限制）。 */
    private int scanPlayersInfinite(final Level level, final boolean curiosPass) {
        int targets = 0;
        for (final net.minecraft.world.entity.player.Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            targets = chargePlayer(player, targets, curiosPass);
        }
        return targets;
    }

    /** 无限范围：遍历当前维度已加载的所有掉落物物品充电。 */
    private int scanItemsInfinite(final Level level) {
        // 使用世界边界的大包围盒，覆盖全部已加载区块中的实体
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            -30000000.0, -64.0, -30000000.0, 30000000.0, 1000000.0, 30000000.0);
        final List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
        int targets = 0;
        for (final ItemEntity itemEntity : items) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                break;
            }
            final ItemStack stack = itemEntity.getItem();
            // 掉落物过滤（droppedItems）：不许充的 item 连「目标」都不算，更不登记高亮
            //（高亮只登记 accepted > 0 的真充电目标，见 tickChargedItemIds 的写入点）
            if (!chargingFilter.allowsDroppedItem(stack.getItem())) {
                continue;
            }
            final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null) {
                continue; // 不是可充电物品：连「目标」都不算
            }
            if (storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
                // 满电跳过（第 102 轮）：判定在 transferBudget() 之前 ⇒ 不分摊预算、不扣电量、
                // 不计入目标数、不登记高亮；只累计一个给界面看的跳过数。
                lastSkippedFullTargets++;
                continue;
            }
            final int transfer = transferBudget();
            final int accepted = storage.receiveEnergy(transfer, false);
            if (accepted > 0) {
                energyStorage.extractEnergy(accepted, false);
                tickChargedItemIds.add(itemEntity.getId()); // 高亮：本 tick 真的给这个掉落物充进去了能量
                targets++;
            }
        }
        return targets;
    }

    /** 无限范围：遍历所有在线玩家所在区块（及其四周已加载区块）内的方块实体充电。 */
    private int scanBlocksInfinite(final Level level) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return 0;
        }
        final java.util.Set<Long> visited = new java.util.HashSet<>();
        int targets = 0;
        for (final net.minecraft.world.entity.player.Player player : serverLevel.players()) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                break;
            }
            final net.minecraft.core.SectionPos center = net.minecraft.core.SectionPos.of(player.blockPosition());
            for (int dx = -INFINITE_CHUNK_RADIUS; dx <= INFINITE_CHUNK_RADIUS; dx++) {
                for (int dz = -INFINITE_CHUNK_RADIUS; dz <= INFINITE_CHUNK_RADIUS; dz++) {
                    if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                        return targets;
                    }
                    final int cx = center.x() + dx;
                    final int cz = center.z() + dz;
                    final long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                    if (!visited.add(key)) {
                        continue;
                    }
                    // 仅处理已加载区块，绝不主动加载新区块
                    if (!serverLevel.isLoaded(new BlockPos(cx * 16, 0, cz * 16))) {
                        continue;
                    }
                    final net.minecraft.world.level.chunk.LevelChunk chunk = serverLevel.getChunk(cx, cz);
                    for (final java.util.Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                            return targets;
                        }
                        final BlockPos pos = entry.getKey();
                        if (pos.equals(worldPosition)) {
                            // 绝不给自己充电。自身进候选池是另一件事，已在扫描之前由
                            // recordSeenSelf 单独登记（第 122 轮 3-3，见 doCharging）。
                            continue;
                        }
                        if (chargeBlock(level, pos, entry.getValue())) {
                            targets++;
                        }
                    }
                }
            }
        }
        return targets;
    }

    /** 扫描范围内玩家，给其身上（背包族 + 饰品槽）的可充电物品供电（如无线终端）。 */
    private int scanPlayers(final Level level, final boolean curiosPass) {
        final int halfX = rangeX / 2;
        final int halfY = rangeY / 2;
        final int halfZ = rangeZ / 2;
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            worldPosition.getX() - halfX, worldPosition.getY() - halfY, worldPosition.getZ() - halfZ,
            worldPosition.getX() + halfX + 1, worldPosition.getY() + halfY + 1, worldPosition.getZ() + halfZ + 1
        );
        final List<net.minecraft.world.entity.player.Player> players =
            level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box, p -> !p.isSpectator());
        int targets = 0;
        for (final net.minecraft.world.entity.player.Player player : players) {
            targets = chargePlayer(player, targets, curiosPass);
        }
        return targets;
    }

    /**
     * 给单个玩家<b>身上所有能充电的地方</b>充电，返回新增充电目标数。
     *
     * <h2>覆盖范围（用户第 6 条：「放在任何地方的任何物品都要给它充电」）</h2>
     * <ol>
     *     <li><b>主背包 + 快捷栏 + 盔甲 + 副手</b>：都在 {@link net.minecraft.world.entity.player.Inventory} 里
     *     （NeoForge 的 {@code getContainerSize()} = 36 + 4 + 1 = 41），下面这一圈 0..40 <b>本来就全覆盖</b>，
     *     也是「手持」那一路（快捷栏当前选中格就在其中）；</li>
     *     <li><b>饰品槽（Curios）</b>：饰品槽<b>不是</b> {@code Inventory} 的一部分，是 Curios 自己的容器，
     *     因此必须另外取（{@link cretae.cookiewyq.rs_create_compat.support.RsccCuriosTerminalSlot#allStacks}）；
     *     这就是「无限终端放饰品槽里充不到电」的原因。取不到（没装 Curios / 反射失败）时是空表，
     *     整条链路静默跳过、不报错。</li>
     * </ol>
     *
     * <p><b>刻意不递归「容器内的容器」</b>（精致背包之类）：用户明确说没必要；递进容器还会把
     * 「一件物品被两个视图同时看到」这种重复充电风险带进来。</p>
     *
     * <p><b>去重与守恒</b>：本次扫描用一份「按实例判等」的集合登记已经处理过的存活栈
     * （{@code ItemStack} 不重写 {@code equals}，这里再用 {@code IdentityHashMap} 上双保险），
     * 同一个栈哪怕同时出现在两个视图里也只会被充一次；每件物品只在自己没充满时才充，
     * 且只从本机缓存里扣掉<b>对方实际接受</b>的那部分能量（{@code accepted}），因此既不重复、
     * 也不可能超容（{@code receiveEnergy} 自己会按容量与自身速率夹住）。</p>
     *
     * <p><b>高亮联动</b>：每一处「{@link #chargeItemStack} 返回 {@code true}」的分支都会把玩家登记进
     * {@link #tickChargedPlayers} —— 充满的、不可充的、本机没电的一个都不登记，所以客户端不会高亮
     * 「其实没在充」的玩家。这里用 {@link java.util.Set} 去重，同一 tick 登记多次也只发一次。</p>
     *
     * @param curiosPass 本 tick 是否顺带扫饰品槽（由 {@link #nextCuriosPass()} 节流决定）
     */
    private int chargePlayer(final net.minecraft.world.entity.player.Player player,
                             final int currentTargets, final boolean curiosPass) {
        int targets = currentTargets;
        // 充电过滤（玩家这一类）：整名跳过 —— 连他的饰品槽也不扫。
        // 为什么是「整名」而不是只跳过背包：界面上「玩家」这一类表达的是「这个人不许被充」，
        // 若只跳背包、饰品槽还在充，玩家看到的就与配置不符，而且这种半生效最难解释。
        if (!chargingFilter.allowsPlayer(player)) {
            return targets;
        }
        // 只在真的要扫饰品槽的那一 tick 才分配去重集合：普通 tick 一个对象都不多建。
        final java.util.Set<ItemStack> seen = curiosPass
            ? java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())
            : null;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                return targets;
            }
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || (seen != null && !seen.add(stack))) {
                continue;
            }
            // 玩家身上物品过滤（carriedItems）：不充的物品连「目标」都不算，也不登记高亮
            if (!chargingFilter.allowsCarriedItem(stack.getItem())) {
                continue;
            }
            if (chargeItemStack(stack)) {
                targets++;
                tickChargedPlayers.add(player.getUUID()); // 高亮：本 tick 真的给这个玩家充进去了能量
            }
        }
        if (seen == null) {
            return targets;
        }
        // 饰品槽：与背包互不重叠，正常情况下这里一件也不会重复；去重集合只用于「同一份存活栈被两个
        // 视图同时看到」这种极端情况（宁可少充一次，也绝不在一次扫描里对同一件物品充两次）。
        for (final ItemStack stack : cretae.cookiewyq.rs_create_compat.support.RsccCuriosTerminalSlot
            .allStacks(player)) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                return targets;
            }
            if (!seen.add(stack)) {
                continue;
            }
            // 同上：饰品槽里的物品也按「玩家身上物品」这一类过滤（携带 != 背包 41 格）
            if (!chargingFilter.allowsCarriedItem(stack.getItem())) {
                continue;
            }
            if (chargeItemStack(stack)) {
                targets++;
                tickChargedPlayers.add(player.getUUID()); // 高亮：本 tick 真的给这个玩家充进去了能量
            }
        }
        return targets;
    }

    /**
     * 给<b>一件物品</b>充电：返回「这次是否真的充进去了能量」。
     *
     * <p>三条件缺一不可：物品必须真的带 {@code Capabilities.EnergyStorage.ITEM} 能力、
     * 必须<b>还没充满</b>（充满即停，既不浪费扫描也不会有任何超容写入）、
     * 本机缓存里必须还有电。扣能只扣对方<b>实际接受</b>的数量，能量守恒。</p>
     */
    private boolean chargeItemStack(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        if (storage == null) {
            return false; // 不是可充电物品：连「目标」都不算
        }
        if (storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
            // <b>满电跳过</b>（第 102 轮，用户原话「电量一直是满的就跳过、减少无意义的次数」）：
            // 判定在 transferBudget() 之前 ⇒ 该目标不分摊预算、不扣电量、不计入 lastChargedTargets、
            // 不登记高亮（调用方只在返回 true 时才 add(tickChargedPlayers)），也不产生任何包。
            // 只做一次 O(1) 的 stored/max 读，没有额外遍历。
            lastSkippedFullTargets++;
            return false;
        }
        final int transfer = transferBudget();
        if (transfer <= 0) {
            return false;
        }
        final int accepted = storage.receiveEnergy(transfer, false);
        if (accepted <= 0) {
            return false;
        }
        energyStorage.extractEnergy(accepted, false);
        return true;
    }

    /**
     * 扫描范围内所有已加载方块实体并充电。
     * 遍历范围内已加载的 chunk（LevelChunk.getBlockEntities() 返回 Map<BlockPos, BlockEntity>），
     * 每 tick 全量给最多 maxTargets 个目标充电 —— 解决分片扫描导致方块充电过慢的问题。
     */
    private int scanBlocks(final Level level) {
        final int halfX = rangeX / 2;
        final int halfY = rangeY / 2;
        final int halfZ = rangeZ / 2;
        final int minX = worldPosition.getX() - halfX;
        final int maxX = worldPosition.getX() + halfX;
        final int minY = worldPosition.getY() - halfY;
        final int maxY = worldPosition.getY() + halfY;
        final int minZ = worldPosition.getZ() - halfZ;
        final int maxZ = worldPosition.getZ() + halfZ;

        final int minChunkX = minX >> 4;
        final int maxChunkX = maxX >> 4;
        final int minChunkZ = minZ >> 4;
        final int maxChunkZ = maxZ >> 4;

        int targets = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                    return targets;
                }
                // 仅处理已加载的 chunk，避免触发区块加载
                if (!level.isLoaded(new BlockPos(chunkX * 16, worldPosition.getY(), chunkZ * 16))) {
                    continue;
                }
                final var chunk = level.getChunk(chunkX, chunkZ);
                for (final java.util.Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                        return targets;
                    }
                    final BlockPos pos = entry.getKey();
                    if (pos.getX() < minX || pos.getX() > maxX
                        || pos.getY() < minY || pos.getY() > maxY
                        || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    if (pos.equals(worldPosition)) {
                        // 跳过自身：绝不给自己充电。自身进候选池由 doCharging 里的
                        // recordSeenSelf 单独登记（第 122 轮 3-3）—— 登记与充电必须分离，
                        // 否则「先跳过自身」会让自家方块永远进不了「世界中的方块」页。
                        continue;
                    }
                    if (chargeBlock(level, pos, entry.getValue())) {
                        targets++;
                    }
                }
            }
        }
        return targets;
    }

    private boolean chargeBlock(final Level level, final BlockPos pos, final BlockEntity blockEntity) {
        final IEnergyStorage storage = level.getCapability(
            Capabilities.EnergyStorage.BLOCK, pos, blockEntity.getBlockState(), blockEntity, null
        );
        // <b>第 122 轮 3-1/3-2：判据从「能力非空」收紧到「真的能收电」</b>（见 {@link #isChargeableBlock}）。
        // 旧写法只判 {@code storage == null}，于是「只出不进」的发电机（用户原话「应力发电机它是
        // 发电的，它本身是不能存电的」）与零容量占位能力全被登记进候选池；真充电那一路虽然会被
        // {@code receiveEnergy} 返回 0 挡住，但界面一直在说谎。
        // 顺带把「白调 receiveEnergy」也省掉：判据不过 ⇒ 它必然返回 0，直接 return 的语义相同
        // （不扣电、不计目标、不登记高亮、不累计 lastSkippedFullTargets）。
        if (!isChargeableBlock(storage)) {
            return false;
        }
        // 第 110 轮：把「能收电的方块」登记进候选池（供过滤界面「方块」页列出来）。
        // 注意登记点在过滤判定**之前**：候选 ≠ 目标。若只登记「真充了的」，玩家把某类方块
        // 拉黑后它就再也不会出现在界面列表里 ⇒ 无法取消勾选（死锁）。
        // 第 122 轮 3-2：登记收口到 {@link #recordSeenCandidate}（唯一调用 recordSeenBlock 的地方），
        // 后者再加一道「控制器类」黑名单（RS 控制器能收电但用户明确不要它出现在列表里）。
        recordSeenCandidate(blockEntity.getBlockState(), storage);
        // 方块过滤（第 110 轮，blocks）：不许充的方块连「目标」都不算、不登记高亮、
        // 不计入 lastChargedTargets、不消耗电量 —— 与掉落物 / 玩家物品两条扫描的既有口径逐字一致。
        if (!chargingFilter.allowsBlock(blockEntity.getBlockState().getBlock())) {
            return false;
        }
        // ---------- 满电跳过（第 102 轮口径；第 124 轮 R-1 改判据） ----------
        // 语义（一直没变）：满电目标<b>不分摊预算、不扣电量、不登记高亮 / 不发包</b>，
        // 只累计一个给界面看的跳过数。判定必须在「真取电」之前。
        //
        // <b>第 124 轮 R-1：判满不再比较两个 int，改用 simulate 问能力「还收得下吗」</b>。
        // 旧写法是 {@code storage.getEnergyStored() >= storage.getMaxEnergyStored()}，而 FE 能力
        // 视图是 <b>int 口径</b>：FE 能量立方的集群视图（{@code FEEnergyCubeBlockEntity} 的
        // ClusterEnergyCapability，内部空间是 long 精确值）在 43 块 × 50M = 2.15B > INT_MAX 时，
        // 存量与容量会<b>同时饱和到 {@code Integer.MAX_VALUE}</b> ⇒ 旧判据在饱和区间恒为「满」，
        // 明明真实还剩 2,516,352 FE 也永远充不进去（124-D 的 M-3c 模型复现）。
        // {@code receiveEnergy(transfer, true)} 是 simulate（<b>不写世界</b>）：它返回 0 才是真满；
        // 对立方视图它内部按 long 算空间，因此饱和区间也能正确给出「还能收 N」。
        //
        // <b>顺序为什么可以先取预算再判满</b>：两个预算方法（{@link #transferBudget()} /
        // {@link #cubeTransferBudget()}）都是<b>纯函数</b>（只读升级速率与本机电量，无任何副作用），
        // 先算它再 simulate，与旧写法「先判满 → 再取预算」<b>完全等价</b>：判满失败时同样没有
        // 消耗预算、没有扣电、没有登记高亮。
        //
        // ---------- 第 127 轮：立方走「电池档」，工具 / 玩家走「工具档」 ----------
        // FE 能量立方是<b>电池</b>，不是「要充电的工具」：它按自己的面速率
        // {@link Config#feEnergyCubeMaxTransfer}（默认 10 万 FE/t）吃电，<b>不</b>参与
        // {@link #transferBudget()} 的「按最大目标数均分」（默认 500 FE/t）。
        // 用户两次口述（beta.22「摆一面墙应该瞬间被充满」、beta.26「立方本身相当于导线，
        // 范围充电器接上去吸的是整个集群的电量」）的瓶颈都不是容量口径（那部分见
        // {@link FEEnergyCubeBlockEntity} 的 ClusterEnergyCapability，早就是整簇视图），
        // 而是「每个目标只给 500/t」：12 块墙 ≈ 6,000 FE/t，20M 缺口要 2.8 分钟，
        // 玩家看到的就是「只有贴着的那一块在慢慢动」。改成电池档后，被覆盖到的每块立方
        // 各按 10 万/t 往整簇里灌，整面墙是「一次灌进去」的观感；总流量仍受本机缓存
        // （{@link #energyStorage}）与 {@link Config#rangeChargerNetworkPullPerTick} 约束，
        // 不产生任何额外能量。
        final int transfer = blockEntity instanceof FEEnergyCubeBlockEntity
            ? cubeTransferBudget()
            : transferBudget();
        if (transfer <= 0) {
            return false; // 电量已耗尽（或预算夹到 0）：本 tick 不再往任何目标写
        }
        if (storage.receiveEnergy(transfer, true) <= 0) {
            // simulate 都收不下 ⇒ 真满（不依赖 stored/max 两个 int 的饱和关系）
            lastSkippedFullTargets++;
            return false;
        }
        final int accepted = storage.receiveEnergy(transfer, false);
        if (accepted > 0) {
            energyStorage.extractEnergy(accepted, false);
            tickChargedBlocks.add(pos); // 高亮：本 tick 真的给这个方块充进去了能量
            return true;
        }
        return false;
    }

    /**
     * <b>「这个方块能不能被充进电」——候选登记与真充电路径共用的唯一判据</b>（第 122 轮 3-1/3-2）。
     *
     * <p><b>为什么不能只看 {@code storage != null}</b>：FE 能力非空只说明「这个方块暴露了一个能量接口」，
     * 不代表能量<b>进得去</b>。旧判据（只看非空）把两类根本不该出现在界面上的方块也登记成了候选：</p>
     * <ul>
     *     <li>{@code canReceive() == false} ⇒ <b>只出不进</b>（发电机 / 只放电的电源）。用户原话
     *     「为什么会有这个应力发电机呢？应力发电机它是发电的，它本身是不能存电的」；</li>
     *     <li>{@code getMaxEnergyStored() == 0} ⇒ 没有缓冲，{@code receiveEnergy} 永远返回 0
     *     （能力存在只是占位），同样不该出现。</li>
     * </ul>
     *
     * <p><b>与物品侧逐字同源</b>：客户端物品候选池 {@code ChargerFilterCandidates#all} 早就是
     * 「{@code storage != null && canReceive() && getMaxEnergyStored() > 0}」这套口径
     * （见那里的 javadoc「判据（与「能不能充进去」逐字同源）」）。方块侧一直漏判，本方法把它补齐；
     * 两处必须保持同一个表达式 —— 否则会出现「物品页不列发电机、方块页却列」的分裂。</p>
     *
     * <p><b>真充电路径也走它</b>：判据不过 ⇒ {@code receiveEnergy} 必然返回 0（IEnergyStorage 契约），
     * 提前 return 与「调用后返回 0」语义逐格相同，只是不再白调能力方法。</p>
     */
    private static boolean isChargeableBlock(@Nullable final IEnergyStorage storage) {
        return storage != null && storage.getMaxEnergyStored() > 0 && storage.canReceive();
    }

    /**
     * 候选池的<b>注册名黑名单</b>（第 122 轮 3-2）—— 只影响「世界中的方块」页<b>展示</b>，
     * 不影响真充电，也不影响玩家名单过滤。
     *
     * <p><b>为什么需要它：控制器确实能收电（反编译证据，不是猜）</b>。RS 2.0.9 的
     * {@code neoforge/ModInitializer#registerCapabilities} 字节码里
     * {@code registerBlockEntity(Capabilities.EnergyStorage.BLOCK, getController(), ...)} 是真实存在的一行，
     * 提供的 {@code neoforge/support/energy/EnergyStorageAdapter} 的 {@code canReceive()} 是
     * <b>硬编码 {@code return true}</b>（{@code canExtract()} 反而硬编码 {@code return false}），
     * 容量 = 配置的控制器能量缓冲（{@code > 0}）。因此 {@link #isChargeableBlock} 拦不住控制器，
     * 只能按注册名排除 —— 用户原话「它里面不应该会有这个什么比如说控制器以及那些……」。</p>
     *
     * <p><b>为什么是「命名空间 + 路径规则」而不是列 32 个 id</b>：RS 控制器有 16 种颜色 ×
     * （普通 / 创造）共 32 个方块注册名，逐个写死既难维护又容易漏；RS 的命名规约统一是
     * {@code controller} / {@code <颜色>_controller} / {@code <颜色>_creative_controller}，
     * 因此只认「{@code refinedstorage} 命名空间 + 路径以 {@code _controller} 结尾（或恰为
     * {@code controller}）」。别的模组的方块（哪怕名字里也有 controller）不在此规则内 ——
     * 黑名单刻意只收「已由反编译证实的这一族」。</p>
     */
    private static final String CANDIDATE_DENY_NAMESPACE = "refinedstorage";
    /** 见 {@link #CANDIDATE_DENY_NAMESPACE}：无颜色前缀的控制器就叫 {@code controller}。 */
    private static final String CANDIDATE_DENY_PATH_CONTROLLER = "controller";
    /** 见 {@link #CANDIDATE_DENY_NAMESPACE}：{@code <颜色>_controller} / {@code <颜色>_creative_controller}。 */
    private static final String CANDIDATE_DENY_PATH_SUFFIX = "_controller";

    /** 该方块是否在候选池黑名单里（只影响展示；判据见 {@link #CANDIDATE_DENY_NAMESPACE} 的注释）。 */
    private static boolean isCandidateDenied(final BlockState state) {
        if (state == null) {
            return false;
        }
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(state.getBlock());
        if (id == null || !CANDIDATE_DENY_NAMESPACE.equals(id.getNamespace())) {
            return false;
        }
        final String path = id.getPath();
        return CANDIDATE_DENY_PATH_CONTROLLER.equals(path)
            || path.endsWith(CANDIDATE_DENY_PATH_SUFFIX);
    }

    /**
     * 候选池的<b>唯一登记入口</b>（第 122 轮 3-1/3-2）：判据不过 / 命中黑名单就什么都不做。
     * <p>{@link #recordSeenBlock} 因此<b>只有一个调用点</b>（本方法里）——
     * 「谁有资格进候选池」这件事只有一处可改，不会出现两条扫描各写一套判据的分裂。</p>
     *
     * @param storage 调用方已经取到的能量能力（省掉一次 capability 查询；{@code null} 也合法 = 不合格）
     */
    private void recordSeenCandidate(final BlockState state, @Nullable final IEnergyStorage storage) {
        if (!isChargeableBlock(storage)) {
            return;
        }
        if (isCandidateDenied(state)) {
            return;
        }
        recordSeenBlock(state);
    }

    /**
     * 把<b>本机自己</b>登记进候选池（第 122 轮 3-3；调用点在 {@link #doCharging} 清空候选池之后）。
     *
     * <p>为什么单独做一次：两条方块扫描都有 {@code pos.equals(worldPosition) → continue}
     * （<b>绝不给自己充电</b>，语义必须保持），而登记只发生在扫描到目标之后 ⇒ 自身永远进不了池子。
     * 这里在扫描<b>之前</b>按同一条判据（能力视图 = 对外的 {@code CappedEnergyStorage}）登记自身，
     * 于是「自身是候选」与「自身不是目标」同时成立。</p>
     */
    private void recordSeenSelf(final Level level) {
        if (level == null) {
            return;
        }
        final IEnergyStorage storage = level.getCapability(
            Capabilities.EnergyStorage.BLOCK, worldPosition, getBlockState(), this, null);
        recordSeenCandidate(getBlockState(), storage);
    }

    /**
     * 把「本机范围内检测到的、能收电的方块」登记进 {@link #seenBlocks}（第 110 轮）。
     *
     * <p><b>唯一调用点是 {@link #recordSeenCandidate}</b>（第 122 轮：判据与黑名单都在那里，
     * 本方法只负责「按方块状态取注册名 + 有界放入」，不再自己判有没有能力）。</p>
     *
     * <p><b>为什么按方块状态取注册名</b>：{@code state.getBlock()} 就是方块注册表里的那一项，
     * 与 {@code ChargingFilter#allowsBlock} 判定时用的是<b>同一个</b> key —— 候选池里显示的 id
     * 与勾选后存进名单的 id 因此逐字相同（若这里改用「方块物品」的注册名，就会出现
     * 「界面显示 {@code x:y}、存进去却是 {@code x:y_item}」这种对不上的情况）。</p>
     *
     * <p>容量由 {@link ChargerFilterLimits#MAX_BLOCK_ENTRIES} 兜住；超限时
     * {@code addBounded} 只是不再放入，不报错、不打断扫描（候选池少几项只是「界面里勾不到」，
     * 手动拉黑仍然可用）。</p>
     */
    private void recordSeenBlock(final BlockState state) {
        if (state == null) {
            return;
        }
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(state.getBlock());
        if (id != null) {
            ChargerFilterLimits.addBounded(seenBlocks, id,
                ChargerFilterLimits.MAX_BLOCK_ENTRIES);
        }
    }

    private int scanItems(final Level level) {
        if (energyStorage.getEnergyStored() <= 0) {
            return 0;
        }
        final int halfX = rangeX / 2;
        final int halfY = rangeY / 2;
        final int halfZ = rangeZ / 2;
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            worldPosition.getX() - halfX, worldPosition.getY() - halfY, worldPosition.getZ() - halfZ,
            worldPosition.getX() + halfX + 1, worldPosition.getY() + halfY + 1, worldPosition.getZ() + halfZ + 1
        );
        final List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
        int targets = 0;
        for (final ItemEntity itemEntity : items) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                break;
            }
            final ItemStack stack = itemEntity.getItem();
            // 掉落物过滤（droppedItems）：不许充的 item 连「目标」都不算，更不登记高亮
            //（高亮只登记 accepted > 0 的真充电目标，见 tickChargedItemIds 的写入点）
            if (!chargingFilter.allowsDroppedItem(stack.getItem())) {
                continue;
            }
            final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null) {
                continue; // 不是可充电物品：连「目标」都不算
            }
            if (storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
                // 满电跳过（第 102 轮）：判定在 transferBudget() 之前 ⇒ 不分摊预算、不扣电量、
                // 不计入目标数、不登记高亮；只累计一个给界面看的跳过数。
                lastSkippedFullTargets++;
                continue;
            }
            final int transfer = transferBudget();
            final int accepted = storage.receiveEnergy(transfer, false);
            if (accepted > 0) {
                energyStorage.extractEnergy(accepted, false);
                tickChargedItemIds.add(itemEntity.getId()); // 高亮：本 tick 真的给这个掉落物充进去了能量
                targets++;
            }
        }
        return targets;
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RangeX", rangeX);
        tag.putInt("RangeY", rangeY);
        tag.putInt("RangeZ", rangeZ);
        tag.putInt("Energy", energyStorage.getEnergyStored());
        tag.putBoolean("HadInfiniteRangeUpgrade", hadInfiniteRangeUpgrade);
        tag.put("Upgrades", upgradeContainer.serializeNBT(registries));
        // 充电过滤 + 本机高亮开关：玩家配置，必须随方块走存档（拆掉再放下、区块卸载再加载都保持）。
        // 过滤配置整体挂在 chargingFilter 这个 compound 下，内部结构见 ChargingFilter#save。
        final CompoundTag filterTag = new CompoundTag();
        chargingFilter.save(filterTag);
        tag.put("chargingFilter", filterTag);
        tag.putBoolean("highlightEnabled", highlightEnabled);
        // <b>2026-10-05 用户实测「进存档后还是重新充电」</b>：写入侧也留一行取证。
        //
        // 与 {@code loadAdditional} 里那行配对使用：只看 load 会让「存档里本来就是 0」
        // 与「存了但没读回来」两种情况长得一模一样，无法区分。两行一起看才闭环：
        //   save: Energy=&lt;写进去的值&gt;
        //   load: savedEnergy=&lt;从存档读到的值&gt; loadedEnergy=&lt;恢复后的值&gt;
        // 若 save 侧就是 0 ⇒ 电量在<b>落盘之前</b>就被抽走了（不是持久化 bug）；
        // 若 save 有值而 load 读不到 ⇒ 才是持久化 / 加载顺序 bug。
        // 节流：每台每 1200 tick（1 分钟）至多一条，避免刷屏。
        final long nowTick = level == null ? 0L : level.getGameTime();
        if (level != null && !level.isClientSide() && nowTick - lastEnergySaveLogAt >= 1200L) {
            lastEnergySaveLogAt = nowTick;
            // 开发日志（电量持久化取证）：每台每 60 秒一条，受 devLogs 总开关控制。
            if (RsccAssemblyDebug.isEnabled()) {
                org.slf4j.LoggerFactory.getLogger("rs_create_compat/range-charger").info(
                    "[rscc-range-charger] save @{},{},{} Energy={} capacity={}",
                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                    energyStorage.getEnergyStored(), energyStorage.getMaxEnergyStored());
            }
        }
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        // <b>2026-10-05 诊断改进：本方法第一行就无条件留痕。</b>
        //
        // 上一版把日志放在 {@code level != null} 与 {@code !level.isClientSide()} 两个条件之后 ——
        // 而方块实体<b>加载时 {@code level} 可能尚未赋值</b>，于是日志一条都不打，
        // 看起来像「loadAdditional 根本没被调用」，把真正该查的方向（读取侧）掩盖成了「没执行」。
        // 现在：进入本方法就留一行（含 {@code hasEnergyTag}），到方法末尾再留一行（含恢复结果），
        // 两行一定成对出现 ⇒ 能直接区分「没进这个方法」与「进了但没读到」。
        final boolean hasEnergyTag = tag.contains("Energy");
        final int savedEnergy = Math.max(0, tag.getInt("Energy"));
        // 开发日志（每次区块 / 世界加载一对）：受 devLogs 总开关控制。
        if (RsccAssemblyDebug.isEnabled()) {
            ORG_SLF4J.info("[rscc-range-charger] load-enter @{},{},{} hasEnergyTag={} savedEnergy={}",
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                hasEnergyTag, savedEnergy);
        }

        super.loadAdditional(tag, registries);

        final int rawX = tag.getInt("RangeX");
        final int rawY = tag.getInt("RangeY");
        final int rawZ = tag.getInt("RangeZ");
        hadInfiniteRangeUpgrade = tag.getBoolean("HadInfiniteRangeUpgrade");
        // 先载入升级再按当前上限收敛范围（否则有范围升级保存的 115 会被错误裁到 100）
        if (tag.contains("Upgrades")) {
            upgradeContainer.deserializeNBT(registries, tag.getCompound("Upgrades"));
        }
        // 过滤配置：缺字段 = 默认（黑名单 + 空名单）= 不过滤。
        // 这正是旧存档升级到本功能时最不惊讶的行为：机器照旧全充，玩家再自己加名单。
        if (tag.contains("chargingFilter")) {
            chargingFilter.copyFrom(ChargingFilter.load(tag.getCompound("chargingFilter")));
        }
        // 本机高亮开关默认「开」：旧存档没有这个键 ⇒ 保持 true（与升级前「配置默认开」一致），
        // 绝不因为读不到键就悄悄把玩家已经看到的高亮关掉。
        highlightEnabled = !tag.contains("highlightEnabled") || tag.getBoolean("highlightEnabled");
        final int max = getMaxRange();
        rangeX = Math.clamp(rawX, 1, max);
        rangeY = Math.clamp(rawY, 1, max);
        rangeZ = Math.clamp(rawZ, 1, max);
        // <b>电量恢复放在最后一步</b>：这样无论 {@code super.loadAdditional} 或升级载入做了什么，
        // 都不可能覆盖掉存档里的电量（上一版把它放在中间，无法排除被后续步骤重置）。
        //
        // 语义是「设定值」而不是「接收能量」：{@code EnergyStorage#receiveEnergy} 只在
        // 「当前存储量 + 请求量 ≤ 上限」时才接受，否则原样返回 0 —— 它一次字节都不写。
        // 因此先把内部存储抽干（只抽当前存量，不依赖上限），再把存档值收进去，此时必然成功。
        energyStorage.restoreEnergy(savedEnergy);
        if (RsccAssemblyDebug.isEnabled()) {
            ORG_SLF4J.info("[rscc-range-charger] load-done @{},{},{} savedEnergy={} loadedEnergy={} "
                    + "capacity={} maxReceive={}",
                worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                savedEnergy, energyStorage.getEnergyStored(), energyStorage.getMaxEnergyStored(),
                Config.rangeChargerMaxTransfer);
        }
    }

    /** 向 NeoForge 注册能量与网络节点容器能力（MOD 总线事件）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(),
            // 对外走「限速视图」而不是内部存储：内部存储的 maxReceive 已放开到容量
            // （否则 RS 网络节点抽出来的电会被静默截断，见 RestorableEnergyStorage 的注释），
            // 而 Config.rangeChargerMaxTransfer 的语义是「接收外部能量的最大速率」，
            // 两者必须分处两层。改回 getEnergyStorage() 就等于取消这条配置。
            (blockEntity, direction) -> blockEntity.getExternalEnergyStorage()
        );
        // 注册为 RS 网络节点容器，使 RS 线缆/控制器可以连接并为节点供能
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
