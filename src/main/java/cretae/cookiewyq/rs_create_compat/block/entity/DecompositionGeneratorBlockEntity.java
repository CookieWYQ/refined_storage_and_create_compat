package cretae.cookiewyq.rs_create_compat.block.entity;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.DecompositionGeneratorNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.DecompositionGeneratorSpec;
import cretae.cookiewyq.rs_create_compat.support.DecompositionProfile;
import cretae.cookiewyq.rs_create_compat.support.DecompositionTable;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 「降解室发电机」方块实体（第 123 轮重做）：<b>16 格输入 + 4 格副产物 + 6 格升级</b>
 * → 一次降解 1 件 → 产出发电量进本机缓存 + 副产物进 4 格输出 → 缓存<b>只输出</b>
 * （RS 网络能量池 / 六面相邻方块）。
 *
 * <h2>库存布局（数字全部来自 {@link DecompositionGeneratorSpec}，这里只写语义）</h2>
 * <ul>
 *     <li><b>0..15 输入</b>（4 列 × 4 行）：{@link #isItemValid} 走 {@link DecompositionTable}
 *     ⇒ <b>不可降解物在能力层就被拒</b>（漏斗 / 输出总线 / 管道统统塞不进来，用户第 1 条
 *     「不可降解的无法输入」）；每格上限 = {@link DecompositionGeneratorSpec#inputSlotLimit(int)}
 *     （堆叠升级每级 +1 倍，基础 64）；</li>
 *     <li><b>16..19 副产物输出</b>（2 列 × 2 行）：{@link #isItemValid} 恒 {@code false}
 *     （只出不进），上限固定 {@value DecompositionGeneratorSpec#OUTPUT_LIMIT}（不受升级影响）；</li>
 *     <li><b>6 格升级</b>不在物品库存里：由 {@link RsccSpeedStackUpgradeContainer} 单独持有
 *     （只收 speed / stack，一格 1 个，超限/非法弹出到世界）。</li>
 * </ul>
 * <p>物品能力 {@code Capabilities.ItemHandler.BLOCK} 继续把<b>整个 20 格库存</b>交出去
 * （任何方向、可进可出，过滤在 {@code isItemValid}）：玩家 / 输出总线能把植物塞进 16 格输入区，
 * 也能把 4 格副产物区的产物抽走；16..19 的「只出不进」由 {@code isItemValid} 保证。</p>
 *
 * <h2>降解循环（熔炉式：同一时间只处理 1 件）</h2>
 * <ol>
 *     <li>从 {@link #scanCursor} 起环形扫 16 格输入，取第一格「非空且可降解」；</li>
 *     <li>实际耗时 T = {@link DecompositionGeneratorSpec#effectiveTime(int, int)}
 *     （{@code base >> 速度级数}，<b>单件发电量不随速度升级变</b> —— 用户第 6 条）；</li>
 *     <li>每 tick 先判缓存剩余空间够不够本次发电量（不够就<b>暂停：不推进、不消耗</b>）；</li>
 *     <li>到点前再判 4 格副产物能否放下本次掷骰产物（放不下 ⇒ {@code outputFull}，同样不推进、不消耗）；</li>
 *     <li>三判都过：<b>写缓存 → 写产物 → 扣 1 件</b>（顺序固定；绝不「产不出还吃植物」，
 *     也绝不「电丢了还吃植物」——用户第 118 轮定下的硬规则）。</li>
 * </ol>
 *
 * <h2>数据包重载后的安全处理</h2>
 * <ul>
 *     <li>重载后 {@link DecompositionTable#revision()} 变化 ⇒ 重算非空输入格计数（数据槽 11）；</li>
 *     <li>槽里现有的不可降解物<b>原地保留</b>（不自动弹出、不销毁），只是不会被降解；</li>
 *     <li>正在处理的那一格若重载后不可降解 ⇒ 中止进度（物品还在槽里）；耗时/发电量变了 ⇒
 *     重新计时（<b>物品不丢</b>，只是多花时间）。</li>
 * </ul>
 *
 * <h2>旧存档迁移（256 格 → 20 格，一个物品都不许丢）</h2>
 * <p>第 101–122 轮的存档里 {@code Inventory} 是 256 格。{@link ItemStackHandler#deserializeNBT}
 * 对 {@code slot >= size} 的下标会<b>静默丢弃</b>，因此这里不能直接读进 20 格 handler：
 * 见 {@link #readInventoryMigrating} —— 先按旧尺寸建临时 handler 反序列化，再逐格搬运
 * （可降解物→输入格，其余→副产物格，仍放不下→{@link #pendingDump}，首个服务端 tick 掉到方块位置）。
 * 物品用 {@code ItemStack} 原样搬运，不拆不并，<b>绝不销毁、绝不复制</b>。</p>
 *
 * <h2>能量：只出不进，三条输出路径同一份账</h2>
 * <ul>
 *     <li>{@link #energyStorage}：容量 {@link Config#decompositionGeneratorCapacity}，
 *     {@code receiveEnergy} 恒 0、{@code canReceive} 恒 false（<b>任何</b>外部管道都灌不进来）；</li>
 *     <li>RS 网络：网络节点 {@code implements EnergyProvider}，网络耗能时扣<b>本机</b>缓存；</li>
 *     <li>相邻六面：每 tick 查邻居 {@code Capabilities.EnergyStorage.BLOCK}，按
 *     {@link Config#decompositionGeneratorPushPerTick} 推送（贴着控制器放就能给控制器充）；</li>
 *     <li>缓存本身落盘（读档用「设值」语义，绝不走 {@code receiveEnergy}）。</li>
 * </ul>
 */
public class DecompositionGeneratorBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<DecompositionGeneratorNetworkNode> {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ==================== 尺寸（客户端与服务端必须逐字一致） ====================

    /**
     * 数据槽数量（必须与 {@code DecompositionGeneratorMenu.DATA_SLOT_COUNT} 一致；契约第 3 节）。
     * <p>槽位语义见 {@link #getContainerData()} 的 javadoc。</p>
     */
    public static final int DATA_SLOT_COUNT = 14;

    /**
     * 数据槽 5 的位定义：bit0 = 缓存满暂停、bit1 = 正在降解、bit2 = 已接进 RS 网络、
     * bit3 = 4 格副产物放不下本次产物（第 123 轮新增：结算三判里的「输出满」）。
     */
    public static final int FLAG_BUFFER_FULL = 1;
    public static final int FLAG_WORKING = 1 << 1;
    public static final int FLAG_NETWORK = 1 << 2;
    public static final int FLAG_OUTPUT_FULL = 1 << 3;

    // ==================== NBT 键 ====================

    /** 物品库存（新格式 20 格；旧格式 256 格，读档时迁移，见 {@link #readInventoryMigrating}）。 */
    public static final String TAG_INVENTORY = "Inventory";
    /** 6 格升级容器（第 123 轮新增）。 */
    public static final String TAG_UPGRADES = "Upgrades";
    /** 旧存档迁移时「新库存塞不下」的余量（随 NBT 落盘，首个服务端 tick 掉到方块位置）。 */
    public static final String TAG_PENDING_DUMP = "PendingDump";
    /** 本机缓存电量。 */
    public static final String TAG_ENERGY = "Energy";
    /** 当前进度（tick）。 */
    public static final String TAG_PROGRESS = "Progress";
    /** 当前这一件实际需要的总 tick 数（= effectiveTime，已含速度升级）。 */
    public static final String TAG_PROGRESS_TOTAL = "ProgressTotal";
    /** 当前这一件完成时的发电量（单件固定，不随速度升级变）。 */
    public static final String TAG_PROGRESS_ENERGY = "ProgressEnergy";
    /** 当前处理中的输入格下标（0..15，-1 = 空闲）。 */
    public static final String TAG_CURRENT_SLOT = "CurrentSlot";
    /** 环形扫描游标（0..15）。 */
    public static final String TAG_SCAN_CURSOR = "ScanCursor";
    /** 上一次完成时的发电量（界面显示用）。 */
    public static final String TAG_LAST_GENERATED = "LastGenerated";

    /**
     * 旧存档库存尺寸的<b>硬上限</b>（4096）。
     * <p>合法旧档只有 256 格；这个上限只防「被人为改坏 / 损坏的 {@code Size}」让读档时
     * 分配一个天文数字的临时 handler（OOM）。超过它的槽位读不全，会打一条 WARN 说明。</p>
     */
    private static final int MAX_LEGACY_INVENTORY_SLOTS = 4096;

    // ==================== 状态 ====================

    /**
     * 6 格升级容器（只收速度 / 叠加；第 ② 层校验在容器内部）。
     * <p>必须<b>先于</b> {@link #inventory} 声明：输入格的 {@code getSlotLimit} 要读堆叠升级个数，
     * 声明顺序错了就会在极端路径上读到未初始化的字段。</p>
     */
    private final RsccSpeedStackUpgradeContainer upgrades =
        new RsccSpeedStackUpgradeContainer(new RsccSpeedStackUpgradeContainer.Host() {
            @Override
            public void dropStack(final ItemStack stack) {
                RsccSpeedStackUpgradeContainer.dropIntoWorld(getLevel(), worldPosition, stack);
            }

            @Override
            public void onUpgradesChanged() {
                // 升级个数变了：两个缓存计数作废（输入格上限 / 数据槽 6、7 都读它们）
                cachedSpeedUpgrades = -1;
                cachedStackUpgrades = -1;
                setChanged();
            }
        },
            // 「没有服务端世界就不动任何东西」由 lambda 现问：方块实体构造期必然还没有 level，
            // 定死布尔会让第 ② 层校验永远不生效（与 NetworkResourceReceiverBlockEntity 同一手法）。
            () -> getLevel() != null && !getLevel().isClientSide());

    /** 20 格物品库存（唯一权威：0..15 输入、16..19 副产物）。 */
    private final ItemStackHandler inventory = new ItemStackHandler(DecompositionGeneratorSpec.INVENTORY_SIZE) {
        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            // 输入格：只收可降解物（不可降解物在能力层就被拒，用户第 1 条）；
            // 副产物格：恒 false（只出不进，机器内部结算自己写，见 settle / writeOutputs）。
            return DecompositionGeneratorSpec.isInputSlot(slot) && DecompositionTable.isDecomposable(stack);
        }

        @Override
        public int getSlotLimit(final int slot) {
            // 输入格上限随堆叠升级涨（64 → 448）；副产物格固定 64，不受升级影响
            return DecompositionGeneratorSpec.isInputSlot(slot)
                ? DecompositionGeneratorSpec.inputSlotLimit(stackUpgradeCount())
                : DecompositionGeneratorSpec.OUTPUT_LIMIT;
        }

        @Override
        protected void onContentsChanged(final int slot) {
            if (!migratingInventory) {
                // 迁移期间一批 setStackInSlot 不该触发几十次区块标脏；迁移结束统一标一次
                setChanged();
            }
            derivedDirty = true;
        }
    };

    /** 只输出、不可输入的能量缓存。 */
    private final OutputOnlyEnergyStorage energyStorage;

    /** 当前处理中的输入格下标（0..15，-1 = 空闲）。 */
    private int currentSlot = -1;
    /** 当前进度（tick）。 */
    private int progress;
    /** 当前这一件实际需要的总 tick 数（= effectiveTime，0 = 空闲）。 */
    private int progressTotal;
    /** 当前这一件完成时的发电量（FE，单件固定）。 */
    private int progressEnergy;
    /** 环形扫描游标（保证 16 格轮流处理，不会永远饿死后面的槽）。 */
    private int scanCursor;
    /** 上一次完成时的发电量（界面文案用）。 */
    private int lastGenerated;
    /** 本 tick 是否因为「缓存装不下本次发电量」而暂停。 */
    private boolean bufferFull;
    /** 本 tick 是否因为「4 格副产物放不下本次产物」而暂停（第 123 轮新增）。 */
    private boolean outputFull;
    /** 上一次相邻推送实际送出的 FE（界面 / 自检用）。 */
    private int pushedLastTick;

    /** 非空输入格计数缓存（-1 以外的值由 {@link #refreshOccupiedInputs()} 负责维护）。 */
    private int occupiedInputs = -1;
    /** 派生计数是否需要重算（物品变化 / 数据包重载）。 */
    private boolean derivedDirty = true;
    /** 上次重算时用的降解表版本号。 */
    private long derivedRevision = -1L;

    /** 速度升级个数缓存（-1 = 需要重算；升级内容变化时由容器的 host 回调置脏）。 */
    private int cachedSpeedUpgrades = -1;
    /** 堆叠升级个数缓存（同上）。 */
    private int cachedStackUpgrades = -1;

    /** 本机 tick 计数（每 20 tick 标一次脏，让进度落盘但不至于每 tick 都写）。 */
    private long ticks;
    /** 首次 tick 时打一条「降解表已就绪」日志（含条目数 / 错误数）。 */
    private boolean loggedTableState;
    /** 「单次发电量被夹到缓存容量」的警告只打一次（避免每 tick 刷屏）。 */
    private boolean loggedRewardClamp;
    /** 「单条产物 count 被夹到 4 格理论上限」的警告只打一次（数据包笔误防线）。 */
    private boolean loggedProductClamp;

    /**
     * 旧存档迁移（或极端兜底）时<b>塞不进新库存</b>的物品：首个服务端 tick 用
     * {@link Block#popResource} 掉到方块位置。
     * <p>为什么它也要落盘：读档完成到首个 tick 之间若恰好存档 / 崩溃，只在内存里就等于丢失；
     * 落盘后即使被写进区块，下一次读档照样会把它掉出来（不销毁、不复制）。</p>
     */
    private final List<ItemStack> pendingDump = new ArrayList<>();

    /**
     * 迁移期间置 {@code true}：抑制 {@link #inventory} 的 {@code onContentsChanged} 里的
     * {@code setChanged()}（一批 setStackInSlot 只标一次脏），迁移结束后统一标脏。
     */
    private boolean migratingInventory;

    /**
     * 旧存档迁移的<b>日志延迟到首个服务端 tick</b>再打。
     * <p>为什么不在 {@code loadAdditional} 里直接打：客户端也会走同一条读档路径（区块数据包），
     * 在那边打日志等于「每加载一次区块刷一条」；而迁移本身在客户端只是内存态、没有任何副作用。
     * 这里把计数存下来、到服务端 tick 打一次，客户端永远不会打。</p>
     */
    private boolean migratedLegacyInventory;
    private boolean loggedMigration;
    private int migrationLegacySize;
    private int migrationTouchedSlots;
    private int migrationMovedItems;
    private int migrationDumpedItems;

    public DecompositionGeneratorBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.DECOMPOSITION_GENERATOR_BLOCK_ENTITY.get(), pos, state,
            new DecompositionGeneratorNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        this.energyStorage = new OutputOnlyEnergyStorage(Config.decompositionGeneratorCapacity);
    }

    // ==================== 访问器 ====================

    /** 20 格物品库存（能力 / 菜单 / 拆方块结算共用同一份，<b>不是</b>拷贝）。 */
    public ItemStackHandler getInventory() {
        return inventory;
    }

    /** 6 格升级容器（界面与第 ② 层校验共用同一份；<b>不是</b>拷贝）。 */
    public RsccSpeedStackUpgradeContainer getUpgradeContainer() {
        return upgrades;
    }

    /**
     * <b>RS 配置卡（{@code ConfigurationCardTarget}）的「本机装了哪些升级」</b>。
     *
     * <p>为什么必须覆写：基类 {@code AbstractBaseNetworkNodeContainerBlockEntity} 已经吃掉了
     * {@link com.refinedmods.refinedstorage.common.api.configurationcard.ConfigurationCardTarget}
     * 的默认实现（恒返回空表），不覆写就等于「用配置卡复制本机配置时，6 个升级不被带走」。
     * 返回的是<b>拷贝</b>（配置卡只读它做快照，绝不接管真实容器里的实例）。</p>
     *
     * <p><b>为什么不叫 {@code getUpgrades()} 直接返回容器</b>：那是接口方法名、返回类型固定为
     * {@code List<ItemStack>}；容器访问器因此取名 {@link #getUpgradeContainer()}（与本仓
     * {@code CollectionCacheBlockEntity} / {@code SchematicLoaderBlockEntity} 等既有机器同一惯例）。</p>
     */
    @Override
    public List<ItemStack> getUpgrades() {
        final List<ItemStack> out = new ArrayList<>(DecompositionGeneratorSpec.UPGRADE_SLOTS);
        for (int i = 0; i < DecompositionGeneratorSpec.UPGRADE_SLOTS; i++) {
            final ItemStack stack = upgrades.getStackInSlot(i);
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /** 本机缓存电量（FE）。 */
    public int getEnergyStored() {
        return energyStorage.getEnergyStored();
    }

    /** 本机缓存容量（FE，= {@link Config#decompositionGeneratorCapacity}）。 */
    public int getEnergyCapacity() {
        return energyStorage.getMaxEnergyStored();
    }

    /** 六面 / 通用查询共用的能量视图（<b>只出</b>：{@code receiveEnergy} 恒 0）。 */
    public IEnergyStorage getExternalEnergyStorage() {
        return energyStorage;
    }

    /** 当前处理中的输入格下标（0..15，-1 = 空闲；界面 / 自检用）。 */
    public int getCurrentSlot() {
        return currentSlot;
    }

    /** 当前进度（tick）。 */
    public int getProgress() {
        return progress;
    }

    /** 当前这一件实际需要的总 tick 数（= effectiveTime，0 = 空闲）。 */
    public int getProgressTotal() {
        return progressTotal;
    }

    /** 上一次完成时的发电量（FE，0 = 还没完成过）。 */
    public int getLastGenerated() {
        return lastGenerated;
    }

    /** 本 tick 是否因缓存满而暂停。 */
    public boolean isBufferFull() {
        return bufferFull;
    }

    /** 本 tick 是否因 4 格副产物放不下本次产物而暂停（数据槽 5 的 bit3）。 */
    public boolean isOutputFull() {
        return outputFull;
    }

    /** 上一次相邻推送实际送出的 FE。 */
    public int getPushedLastTick() {
        return pushedLastTick;
    }

    /** 速度升级个数（数据槽 6；读缓存，升级内容变化时才重算）。 */
    public int getSpeedUpgradeCount() {
        return speedUpgradeCount();
    }

    /** 堆叠升级个数（数据槽 7；读缓存，升级内容变化时才重算）。 */
    public int getStackUpgradeCount() {
        return stackUpgradeCount();
    }

    /** 非空输入格数（数据槽 11；只数 0..15，副产物区不算）。 */
    public int getOccupiedInputCount() {
        refreshOccupiedInputs();
        return occupiedInputs;
    }

    /** 是否已接进 RS 网络（客户端恒 false；界面用数据槽 5 的 bit2，不读这个）。 */
    public boolean hasNetwork() {
        return mainNetworkNode.getNetwork() != null;
    }

    /** 供自检 / 调试：当前是否有待处理的物品（不做任何写操作）。 */
    public boolean isWorking() {
        return currentSlot >= 0;
    }

    /** 供自检 / 调试：当前这一件的单次发电量（0 = 空闲）。 */
    public int getCurrentEnergyPerOperation() {
        return progressEnergy;
    }

    /** 速度升级个数（带缓存；{@code -1} 表示需要现算）。 */
    private int speedUpgradeCount() {
        if (cachedSpeedUpgrades < 0) {
            cachedSpeedUpgrades = upgrades.speedCount();
        }
        return cachedSpeedUpgrades;
    }

    /** 堆叠升级个数（带缓存；输入格上限每次 insert 都会问它，必须便宜）。 */
    private int stackUpgradeCount() {
        if (cachedStackUpgrades < 0) {
            cachedStackUpgrades = upgrades.stackCount();
        }
        return cachedStackUpgrades;
    }

    // ==================== 能量导出（三条路径共用这一份账） ====================

    /**
     * 由网络节点调用：从本机缓存扣电（网络耗能路径）。
     *
     * @return 真实扣掉的量
     */
    public long extractForNetwork(final long amount) {
        if (level == null || level.isClientSide() || amount <= 0L) {
            return 0L;
        }
        final int got = energyStorage.extractInternal((int) Math.min(amount, Integer.MAX_VALUE));
        if (got > 0) {
            setChanged();
        }
        return got;
    }

    // ==================== 每 tick 逻辑（由网络节点 doWork 调用） ====================

    /**
     * 服务端每 tick：先补掉旧存档迁移遗留的「待掉出」物品 → 推进降解 → 向相邻六面推送电量。
     * <p>由 {@link DecompositionGeneratorNetworkNode#doWork()} 调用；<b>没接网络也会跑</b>
     * （方块本身就能独立发电）。</p>
     */
    public void serverTick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        ticks++;
        // 旧存档迁移的余量：必须在<b>首个服务端 tick</b> 就掉到世界上（不留在 NBT / 内存里等下一次覆盖）
        if (!pendingDump.isEmpty()) {
            flushPendingDump();
        }
        if (!loggedTableState) {
            loggedTableState = true;
            final DecompositionTable.LoadReport report = DecompositionTable.lastReport();
            LOGGER.info("[rs_create_compat] 降解室发电机 @{} 就绪：降解表 items={} tags={} 错误={}",
                worldPosition, DecompositionTable.itemCount(), DecompositionTable.tagCount(),
                report.itemErrors() + report.entryErrors());
        }
        if (migratedLegacyInventory && !loggedMigration && RsccAssemblyDebug.isEnabled()) {
            // 旧存档迁移的汇总只在服务端打一次（读档那刻可能还没有 level / 客户端不该刷日志）。
            // 走 {@link RsccAssemblyDebug#isEnabled()} 闸：第 122 轮硬规则 —— block/entity 下新增的
            // INFO/DEBUG 打印点必须能被「关掉开发日志」选项关掉（迁移是一次性的，但口径一致）。
            loggedMigration = true;
            LOGGER.info("[rs_create_compat] 降解室发电机 @{} 旧存档迁移：{} 格 → {} 格，{} 槽有物，"
                    + "入新库存 {} 件、塞不下的 {} 件已在首个 tick 掉到方块位置",
                worldPosition, migrationLegacySize, DecompositionGeneratorSpec.INVENTORY_SIZE,
                migrationTouchedSlots, migrationMovedItems, migrationDumpedItems);
        }
        tickDecomposition();
        pushedLastTick = pushToNeighbours();
        // 进度也要落盘：每 20 tick（1 秒）标一次脏，而不是每 tick 都触发区块保存
        if (ticks % 20 == 0) {
            setChanged();
        }
    }

    /**
     * 降解主循环（同一时间只处理一格，像熔炉）。
     *
     * <h2>为什么「三判」要在不同的位置判</h2>
     * <ul>
     *     <li><b>缓存空间</b>很便宜（两个 int）且与产物无关 ⇒ 每 tick 都判，缓存满就原地暂停
     *     （不推进、不消耗；与旧实现逐字一致）；</li>
     *     <li><b>副产物空间</b>要掷骰 + 在副本上试放（相对贵）⇒ 只在「这一 tick 就要结算」时判，
     *     放不下就让进度停在上限（{@code outputFull}），下一 tick 再试 —— 用户可以清副产物后自动恢复；</li>
     *     <li><b>顺序固定</b>：写缓存 → 写产物 → 扣 1 件；中途抛异常也不会重复结算（最坏是多发一次电、
     *     少扣一件物品，不会出现「吃了植物却什么都没产出」）。</li>
     * </ul>
     */
    private void tickDecomposition() {
        final int speedUpgrades = speedUpgradeCount();
        DecompositionProfile profile = currentProfile(speedUpgrades);
        // ② 空闲（或刚中止）⇒ 环形找下一格输入
        if (profile == null) {
            currentSlot = findNextInputSlot();
            if (currentSlot < 0) {
                resetIdle();
                return;
            }
            profile = DecompositionTable.profile(
                inventory.getStackInSlot(DecompositionGeneratorSpec.inputSlot(currentSlot)));
            if (profile == null) {
                abortCurrent(); // 不可能（findNext 已过滤），防御：绝不拿不可降解物推进度
                return;
            }
            progress = 0;
            progressTotal = DecompositionGeneratorSpec.effectiveTime(profile.time(), speedUpgrades);
            progressEnergy = rewardFor(profile);
        }
        // ③ 缓存装不下这一次的发电量 ⇒ 暂停（不推进、不消耗物品、不丢电）
        if ((long) getEnergyCapacity() - getEnergyStored() < progressEnergy) {
            bufferFull = true;
            return;
        }
        bufferFull = false;
        // 防御：耗时是「夹到 ≥ 1」的（Spec 走 RsccRateLimiter），理论上不可能为 0；
        // 万一被坏存档 / 未来改动带到 0，宁可中止这一格也绝不在 progressTotal=0 时「每 tick 结算一次」。
        if (progressTotal <= 0) {
            abortCurrent();
            setChanged();
            return;
        }
        // ④ 到点前先判「4 格副产物能否放下本次掷骰产物」；放不下就停在当前进度
        //    （不推进、不消耗物品；用户清掉副产物后自动继续）
        if (progress + 1 >= progressTotal) {
            final List<ItemStack> products = rollProducts(profile);
            if (!productsFit(products)) {
                outputFull = true;
                return;
            }
            outputFull = false;
            settle(currentSlot, progressEnergy, products);
            return;
        }
        // ⑤ 推进
        progress++;
    }

    /**
     * 校验「当前处理中的那一格」还能不能继续，并在数据包 / 升级变化后把耗时与发电量对齐。
     *
     * @return 当前可用的 profile；{@code null} = 空闲（或刚因物品消失 / 不可降解而中止）
     */
    private DecompositionProfile currentProfile(final int speedUpgrades) {
        if (currentSlot < 0) {
            return null;
        }
        final int slot = DecompositionGeneratorSpec.inputSlot(currentSlot);
        final ItemStack stack = inventory.getStackInSlot(slot);
        final DecompositionProfile profile = DecompositionTable.profile(stack);
        if (stack.isEmpty() || profile == null) {
            // 物品被拿走 / 变成不可降解 ⇒ 进度作废（物品原地不动，绝不吞）
            abortCurrent();
            setChanged();
            return null;
        }
        final int expectedTotal = DecompositionGeneratorSpec.effectiveTime(profile.time(), speedUpgrades);
        final int reward = rewardFor(profile);
        if (reward != progressEnergy) {
            // 发电量变了（数据包重载 / 缓存容量配置变化）：从 0 重新计时。
            // 注意比较的是「夹过上限的发电量」——否则单次发电量 > 缓存容量时这条会每 tick 都成立，
            // 进度永远从 0 开始、永远完不成（与旧实现同一个坑，必须保留这个口径）。
            progress = 0;
            progressTotal = expectedTotal;
            progressEnergy = reward;
            setChanged();
        } else if (expectedTotal != progressTotal) {
            // 只是耗时变了（玩家插 / 拔速度升级）：<b>已完成的部分照算</b>，只把上限换掉并夹住 ——
            // 把进度归零等于「插一个升级就白干一半」，而升级只该加速、不该重来。
            progressTotal = expectedTotal;
            progress = Math.min(progress, progressTotal);
            setChanged();
        }
        return profile;
    }

    /**
     * 这一次完成该发多少电：{@code min(配置的单次发电量, 本机缓存容量)}。
     *
     * <p><b>为什么必须夹</b>：数据包允许把单次 {@code energy} 写到 1 亿，而缓存容量默认只有 100 万。
     * 若照原值推进，「缓存装不下」的判定永远成立 ⇒ 机器永远转不完、植物永远不被消耗（看起来像卡死）。
     * 夹到容量之后最坏情况是「一次把缓存充满」，会再打一条警告让作者知道数值不合理。
     * <b>单件发电量不随速度升级变</b>（用户第 6 条）：这里只读 profile.energy，绝不乘速度倍率。</p>
     */
    private int rewardFor(final DecompositionProfile profile) {
        final int capacity = getEnergyCapacity();
        final int reward = Math.min(profile.energy(), capacity);
        if (reward != profile.energy() && !loggedRewardClamp) {
            loggedRewardClamp = true;
            LOGGER.warn("[rs_create_compat] 降解表里有一条单次发电量 {} FE 超过本机缓存容量 {} FE，"
                    + "已按容量结算；请把 decompositionGeneratorCapacity 调大或把该条 energy 调小",
                profile.energy(), capacity);
        }
        return reward;
    }

    /** 从 {@link #scanCursor} 起环形扫第一个「非空且可降解」的输入格；没有返回 -1。 */
    private int findNextInputSlot() {
        for (int offset = 0; offset < DecompositionGeneratorSpec.INPUT_COUNT; offset++) {
            final int index = (scanCursor + offset) % DecompositionGeneratorSpec.INPUT_COUNT;
            final ItemStack stack = inventory.getStackInSlot(DecompositionGeneratorSpec.inputSlot(index));
            if (!stack.isEmpty() && DecompositionTable.profile(stack) != null) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 掷骰算出本次结算的副产物列表。
     *
     * <p>契约第 5 节：{@code results} 是<b>独立掷骰列表</b>，每一项按 {@code chance}（默认 1.0，
     * 夹到 {@code [0,1]}）各自判定，命中就产出 {@code count} 个 {@code item}；空列表 = 只有电没有副产物。
     * 用服务端 {@link net.minecraft.world.level.Level#random} 掷，结果不影响任何记账。</p>
     *
     * <p><b>count 的夹取（数据包笔误防线）</b>：单条产物 count 若大于「4 格副产物满装」
     * （{@value DecompositionGeneratorSpec#OUTPUT_LIMIT} × 4 = 256），这台机器会永远 {@code outputFull}
     * —— 与 {@link #rewardFor} 对 energy 的夹取同一思路：夹到理论上限并只警告一次，宁可少产也绝不卡死。</p>
     */
    private List<ItemStack> rollProducts(final DecompositionProfile profile) {
        // 用 var 承接「配方产出的掷骰列表」：结果类型由 DecompositionProfile.results() 决定，
        // 这里刻意不写死元素类型（嵌套 record / 顶层 record 两种写法都能编译，且不引入第二份契约）。
        final var configured = profile.results();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        final int maxCount = DecompositionGeneratorSpec.OUTPUT_LIMIT
            * DecompositionGeneratorSpec.OUTPUT_COUNT;
        final List<ItemStack> products = new ArrayList<>(configured.size());
        for (final var result : configured) {
            if (result == null || result.item() == null || result.count() <= 0) {
                continue; // 坏条目只跳过自己（与解析侧同口径）
            }
            final float chance = Math.max(0.0f, Math.min(1.0f, result.chance()));
            if (chance <= 0.0f) {
                continue;
            }
            // chance >= 1 时不必消耗随机数（保持随机序列只被「真的掷了」的条目推进）
            if (chance < 1.0f && (level == null || level.random.nextFloat() >= chance)) {
                continue;
            }
            int count = result.count();
            if (count > maxCount) {
                count = maxCount;
                if (!loggedProductClamp) {
                    loggedProductClamp = true;
                    LOGGER.warn("[rs_create_compat] 降解表里有一条副产物 count={} 超过 4 格副产物上限 {}，"
                            + "已夹到 {} 结算（请检查数据包）", result.count(), maxCount, maxCount);
                }
            }
            final ItemStack stack = new ItemStack(result.item(), count);
            if (!stack.isEmpty()) {
                products.add(stack);
            }
        }
        return products;
    }

    /**
     * <b>结算前判据 b</b>：这批复产物能不能全部放进 4 格副产物区。
     * <p>在<b>快照副本</b>上跑与真实投放<b>完全相同</b>的算法（{@link #depositIntoFour}），
     * 因此「判得下」与「放得下」不可能出现两套口径的偏差。</p>
     */
    private boolean productsFit(final List<ItemStack> products) {
        if (products.isEmpty()) {
            return true; // 只有电没有副产物：不需要副产物空间
        }
        final ItemStack[] probe = outputSnapshot();
        for (final ItemStack product : products) {
            if (!depositIntoFour(probe, product).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 4 格副产物区的快照（元素是当前栈引用；{@link #depositIntoFour} 只会替换数组元素，不动这些栈）。 */
    private ItemStack[] outputSnapshot() {
        final ItemStack[] slots = new ItemStack[DecompositionGeneratorSpec.OUTPUT_COUNT];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = inventory.getStackInSlot(DecompositionGeneratorSpec.outputSlot(i));
        }
        return slots;
    }

    /**
     * 副产物投放的<b>纯算法</b>：把 {@code stack} 放进 4 格（同类堆在一起、先到先得），返回放不下的余量。
     *
     * <p><b>为什么不用 {@code ItemStackHandler.insertItem(..., simulate=true)} 直接判</b>：
     * 模拟调用<b>不会把「已占用」记账</b>，同一格会被后面的产物重复算成空位 —— 一批里有两份同物品时
     * 会判「放得下」而真实投放时放不下。这里让「判」与「放」跑同一个函数，只差作用在快照还是真库存上；
     * 每个物品的空间占用是累计的，绝不会出现判据与投放不一致。</p>
     */
    private static ItemStack depositIntoFour(final ItemStack[] slots, final ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = 0; i < slots.length && !remaining.isEmpty(); i++) {
            final ItemStack existing = slots[i];
            if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue; // 不同物品不合并（副产物格不混装）
            }
            final int existingCount = existing.isEmpty() ? 0 : existing.getCount();
            final int room = DecompositionGeneratorSpec.OUTPUT_LIMIT - existingCount;
            if (room <= 0) {
                continue;
            }
            final int moved = Math.min(room, remaining.getCount());
            slots[i] = existing.isEmpty()
                ? remaining.copyWithCount(moved)
                : existing.copyWithCount(existingCount + moved);
            remaining = remaining.copyWithCount(remaining.getCount() - moved);
        }
        return remaining;
    }

    /**
     * 把快照里<b>被改动过</b>的格写回真库存（未改动的格不触发 {@code setStackInSlot}，
     * 避免无意义的标脏 / 区块保存）。
     * <p>识别方式是比较栈对象引用：{@link #depositIntoFour} 只在真的放入时才替换数组元素，
     * 因此「引用相同」⇔「这一格没动」。</p>
     */
    private void writeOutputs(final ItemStack[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) {
            final int slot = DecompositionGeneratorSpec.outputSlot(i);
            if (inventory.getStackInSlot(slot) != snapshot[i]) {
                // 走 setStackInSlot 而不是 insertItem：副产物格的 isItemValid 恒 false（对外只出不进），
                // 机器内部结算必须绕过这一层过滤（这是「只出不进」与「内部可写」唯一的接口）
                inventory.setStackInSlot(slot, snapshot[i]);
            }
        }
    }

    /**
     * 结算（三判已全部通过）：<b>写缓存 → 写产物 → 扣 1 件</b>，然后清进度、游标后移。
     *
     * <p>顺序是硬约束（契约第 4 节）：先写缓存保证「产出了才可能消耗」；产物在快照上已经放好、
     * 这里只是把结果写回真库存；最后才扣物品。中途任何一步抛异常，都不会出现「吃掉了植物却
     * 既没电也没产物」的状态（最坏是多发一次电、少扣一件物品）。</p>
     */
    private void settle(final int inputIndex, final int reward, final List<ItemStack> products) {
        final ItemStack[] settledOutputs = outputSnapshot();
        for (final ItemStack product : products) {
            final ItemStack leftover = depositIntoFour(settledOutputs, product);
            if (!leftover.isEmpty()) {
                // 理论上不可达（同一 tick 的 productsFit 已用同一算法判过）：绝不销毁 ——
                // 走「待掉出」兜底，下一 tick 掉到方块位置
                addToPendingDump(leftover);
            }
        }
        // ① 写缓存（单件发电量固定，不随速度升级变）
        energyStorage.addInternal(reward);
        // ② 写产物（只写改动过的格）
        writeOutputs(settledOutputs);
        // ③ 扣 1 件（物品在这里才真正被消耗；extract 失败也不会回滚前面的电 —— 但那只是「多发一次电」，
        //    比「吃了物品没产出」安全得多；正常情况下不会失败）
        inventory.extractItem(DecompositionGeneratorSpec.inputSlot(inputIndex), 1, false);
        lastGenerated = reward;
        final int finished = inputIndex;
        progress = 0;
        progressTotal = 0;
        progressEnergy = 0;
        currentSlot = -1;
        scanCursor = (finished + 1) % DecompositionGeneratorSpec.INPUT_COUNT;
        setChanged();
    }

    /** 中止当前操作（进度清零；主库存里的物品<b>一格不动</b>）。 */
    private void abortCurrent() {
        currentSlot = -1;
        progress = 0;
        progressTotal = 0;
        progressEnergy = 0;
        bufferFull = false;
        outputFull = false;
    }

    /** 空闲态：清掉进度与两个「暂停」标记（界面据此显示 idle）。 */
    private void resetIdle() {
        progress = 0;
        progressTotal = 0;
        progressEnergy = 0;
        bufferFull = false;
        outputFull = false;
    }

    /**
     * 向六个相邻方块的 {@code Capabilities.EnergyStorage.BLOCK} 推送电量（每 tick 最多
     * {@link Config#decompositionGeneratorPushPerTick} FE，六面<b>分摊</b>这一个预算）。
     *
     * <p><b>为什么「先 simulate 再真收」</b>：先从本机扣、再让对方收，中间若对方只收下一部分，
     * 差额就是「凭空消失的电」。这里先用 {@code receiveEnergy(offer, true)} 问清对方能吃多少，
     * 按这个量从本机扣、再真收；万一实际收下的比预期少（模拟与实际不一致），<b>把差额收回本机</b>，
     * 记账上永远守恒。</p>
     *
     * @return 本 tick 实际送出的 FE 总量
     */
    private int pushToNeighbours() {
        if (getEnergyStored() <= 0) {
            return 0;
        }
        final int limit = Math.max(1, Config.decompositionGeneratorPushPerTick);
        int budget = limit;
        int total = 0;
        for (final Direction direction : Direction.values()) {
            if (budget <= 0 || getEnergyStored() <= 0) {
                break;
            }
            final BlockPos target = worldPosition.relative(direction);
            // 不为了「给邻居充电」去加载区块：未加载的邻居本 tick 跳过
            if (!level.isLoaded(target)) {
                continue;
            }
            final BlockEntity neighbour = level.getBlockEntity(target);
            if (neighbour == null) {
                continue;
            }
            final IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, target,
                neighbour.getBlockState(), neighbour, direction.getOpposite());
            if (storage == null || !storage.canReceive()) {
                continue;
            }
            final int offer = Math.min(budget, getEnergyStored());
            if (offer <= 0) {
                continue;
            }
            final int accepted = storage.receiveEnergy(offer, true);
            if (accepted <= 0) {
                continue;
            }
            final int taken = energyStorage.extractInternal(accepted);
            if (taken <= 0) {
                continue;
            }
            final int actual = storage.receiveEnergy(taken, false);
            if (actual < taken) {
                // 守恒兜底：模拟与实际不一致时把差额收回本机，绝不让电消失
                energyStorage.addInternal(taken - actual);
            }
            final int moved = Math.max(0, actual);
            total += moved;
            budget -= moved;
        }
        if (total > 0) {
            setChanged();
        }
        return total;
    }

    /** 物品变化 / 数据包重载后重算「非空输入格数」（16 次判断，只在必要时跑）。 */
    private void refreshOccupiedInputs() {
        final long revision = DecompositionTable.revision();
        if (!derivedDirty && revision == derivedRevision && occupiedInputs >= 0) {
            return;
        }
        int occupied = 0;
        for (int i = 0; i < DecompositionGeneratorSpec.INPUT_COUNT; i++) {
            if (!inventory.getStackInSlot(DecompositionGeneratorSpec.inputSlot(i)).isEmpty()) {
                occupied++;
            }
        }
        occupiedInputs = occupied;
        derivedDirty = false;
        derivedRevision = revision;
    }

    /** 方块名（基类实现了 {@code Nameable}，默认不会从方块名派生，必须显式覆写）。 */
    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /**
     * 方块 {@code ACTIVE} 状态 = <b>正在降解</b>（工作时亮）。
     * <p>覆写基类是为了让双贴图（inactive / active）跟着「有没有在降解」走，
     * 而不是跟着「网络有没有电」走（发电机不耗电，基类口径对它无意义）。</p>
     */
    @Override
    protected boolean calculateActive() {
        return level != null && !level.isClientSide() && currentSlot >= 0;
    }

    /** 区块加载完成：升级容器再校验一次（历史存档里可能有非法 / 超限升级，读档那一刻就该弹出来）。 */
    @Override
    public void onLoad() {
        super.onLoad();
        upgrades.validate();
    }

    // ==================== 落盘 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_INVENTORY, inventory.serializeNBT(registries));
        tag.put(TAG_UPGRADES, upgrades.serializeNBT(registries));
        tag.putInt(TAG_ENERGY, energyStorage.getEnergyStored());
        tag.putInt(TAG_PROGRESS, progress);
        tag.putInt(TAG_PROGRESS_TOTAL, progressTotal);
        tag.putInt(TAG_PROGRESS_ENERGY, progressEnergy);
        tag.putInt(TAG_CURRENT_SLOT, currentSlot);
        tag.putInt(TAG_SCAN_CURSOR, scanCursor);
        tag.putInt(TAG_LAST_GENERATED, lastGenerated);
        writePendingDump(tag, registries);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 升级容器<b>必须先读</b>：输入格上限 = 64 × (1 + 堆叠升级数)，旧存档迁移时要按读入后的
        // 真实上限决定「每格能搬多少」，先读库存会把上限当成 64 来搬。
        upgrades.beginLoading();
        // 注意不能声明成 final 再在 try 里只赋一次：JLS 的 try-finally 明确赋值规则会认为
        // 「finally 可能在没有完成 try 的情况下执行」⇒ 之后读它报「might not have been initialized」。
        boolean migrated = false;
        try {
            readPendingDump(tag, registries);
            if (tag.contains(TAG_UPGRADES)) {
                upgrades.deserializeNBT(registries, tag.getCompound(TAG_UPGRADES));
            }
            migrated = readInventoryMigrating(tag, registries);
            // 读档是「设定值」语义：直接设值，绝不走 receiveEnergy（那会被 maxReceive 截断）
            energyStorage.setInternal(Math.max(0, tag.getInt(TAG_ENERGY)));
            progress = Math.max(0, tag.getInt(TAG_PROGRESS));
            progressTotal = Math.max(0, tag.getInt(TAG_PROGRESS_TOTAL));
            progressEnergy = Math.max(0, tag.getInt(TAG_PROGRESS_ENERGY));
            currentSlot = tag.getInt(TAG_CURRENT_SLOT);
            scanCursor = Math.floorMod(tag.getInt(TAG_SCAN_CURSOR), DecompositionGeneratorSpec.INPUT_COUNT);
            lastGenerated = Math.max(0, tag.getInt(TAG_LAST_GENERATED));
        } finally {
            upgrades.finishLoading(); // 读档期间不校验中间状态，读完统一校验一次
        }
        // 升级个数缓存作废（容器内容可能是刚反序列化进来的）
        cachedSpeedUpgrades = -1;
        cachedStackUpgrades = -1;
        if (migrated) {
            // 旧格式的 currentSlot 是 256 格下标，新布局里没有对应语义 ⇒ 一律 abort（清进度；
            // 物品已经全部搬进新库存 / pendingDump，绝不因为「进度对不上」丢东西）。
            abortCurrent();
        } else {
            // 新格式：当前格必须重新校验（越界 / 空槽 / 已不可降解 ⇒ 丢掉进度，物品仍留在槽里）
            if (currentSlot < -1 || currentSlot >= DecompositionGeneratorSpec.INPUT_COUNT) {
                abortCurrent();
            } else if (currentSlot >= 0) {
                final ItemStack stack = inventory.getStackInSlot(
                    DecompositionGeneratorSpec.inputSlot(currentSlot));
                if (stack.isEmpty() || DecompositionTable.profile(stack) == null
                    || progressTotal <= 0 || progressEnergy <= 0) {
                    abortCurrent();
                } else {
                    progress = Math.min(progress, Math.max(0, progressTotal - 1));
                }
            }
        }
        derivedDirty = true;
    }

    /**
     * 读物品库存；旧档（256 格）走迁移。返回是否发生过迁移。
     *
     * <h2>为什么不能直接 {@code inventory.deserializeNBT(tag)}</h2>
     * <p>{@link ItemStackHandler#deserializeNBT} 会先 {@code setSize(NBT 里的 Size)}，然后只接受
     * {@code 0 <= slot < size} 的条目，其余<b>静默丢弃</b>（不报错、不日志）。直接读进 20 格 handler
     * 的后果有两个：① handler 会被旧档的 {@code Size=256} <b>撑回 256 格</b>，输入 / 副产物布局
     * （0..15 / 16..19）全部错位；② 若先按 20 格读，则 236 格的东西被静默吞掉。两种都不可接受，
     * 因此旧档必须先按<b>旧尺寸</b>建临时 handler 反序列化，再逐格搬运。</p>
     *
     * <h2>怎么判断「旧档」</h2>
     * <p>{@code Size > 20} 或 {@code Items} 里出现下标 {@code >= 20} 的条目 ⇒ 旧档。
     * 用「最高下标」兜底 {@code Size} 写错的情况（两个判据任一命中都走迁移）。</p>
     */
    private boolean readInventoryMigrating(final CompoundTag tag, final HolderLookup.Provider registries) {
        if (!tag.contains(TAG_INVENTORY)) {
            return false;
        }
        final CompoundTag inventoryTag = tag.getCompound(TAG_INVENTORY);
        final int declaredSize = inventoryTag.contains("Size", Tag.TAG_INT)
            ? inventoryTag.getInt("Size")
            : DecompositionGeneratorSpec.INVENTORY_SIZE;
        final int highestSlot = highestUsedSlot(inventoryTag);
        final boolean legacy = declaredSize > DecompositionGeneratorSpec.INVENTORY_SIZE
            || highestSlot >= DecompositionGeneratorSpec.INVENTORY_SIZE;
        if (!legacy) {
            // 新格式（Size = 20）直读。Size 被改坏成别的值（< 20）时也要先归位到 20，
            // 否则 handler 会被 resize 成那个坏值 ⇒ 输入 / 副产物格全部错位。
            inventory.deserializeNBT(registries, normalizedInventoryTag(inventoryTag, declaredSize,
                DecompositionGeneratorSpec.INVENTORY_SIZE));
            return false;
        }
        // ---- 旧格式（256 格）：先按旧尺寸反序列化，再搬运进 20 格新库存 ----
        int legacySize = Math.max(DecompositionGeneratorSpec.INVENTORY_SIZE,
            Math.max(declaredSize, highestSlot + 1));
        if (legacySize > MAX_LEGACY_INVENTORY_SLOTS) {
            LOGGER.warn("[rs_create_compat] 降解室发电机 @{} 的旧存档 Inventory.Size={} 超过合理上限 {}，"
                    + "按 {} 格读取（超出部分无法解析，可能丢失；请检查存档是否被改坏）",
                worldPosition, declaredSize, MAX_LEGACY_INVENTORY_SLOTS, MAX_LEGACY_INVENTORY_SLOTS);
            legacySize = MAX_LEGACY_INVENTORY_SLOTS;
        }
        final ItemStackHandler legacyHandler = new ItemStackHandler(legacySize);
        // 反序列化前先把 NBT 里的 Size 改成临时 handler 的真实尺寸：{@code deserializeNBT} 会按 NBT 的
        // Size 调 setSize，直接把被改坏的超大 Size 传进去会在读档时分配一个天文数组（OOM）。
        legacyHandler.deserializeNBT(registries, normalizedInventoryTag(inventoryTag, declaredSize, legacySize));
        // 同一实例被反复喂旧 NBT 时不能叠加：先清空新库存与待掉出队列（正常读档时它们本来就是空的）
        clearInventoryForMigration();
        pendingDump.clear();
        migratingInventory = true;
        int movedItems = 0;
        int dumpedItems = 0;
        int touchedSlots = 0;
        try {
            for (int slot = 0; slot < legacyHandler.getSlots(); slot++) {
                final ItemStack stack = legacyHandler.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                touchedSlots++;
                final ItemStack leftover = migrateStack(stack);
                movedItems += stack.getCount() - leftover.getCount();
                if (!leftover.isEmpty()) {
                    dumpedItems += leftover.getCount();
                    addToPendingDump(leftover);
                }
            }
        } finally {
            migratingInventory = false;
        }
        setChanged();
        // 汇总日志延迟到首个服务端 tick（见 {@link #migratedLegacyInventory}）：客户端读档不该刷日志。
        migratedLegacyInventory = true;
        migrationLegacySize = legacySize;
        migrationTouchedSlots = touchedSlots;
        migrationMovedItems = movedItems;
        migrationDumpedItems = dumpedItems;
        return true;
    }

    /** 旧档里出现过的最高槽位下标（-1 = 没有任何物品条目）。 */
    private static int highestUsedSlot(final CompoundTag inventoryTag) {
        final ListTag items = inventoryTag.getList("Items", Tag.TAG_COMPOUND);
        int highest = -1;
        for (int i = 0; i < items.size(); i++) {
            highest = Math.max(highest, items.getCompound(i).getInt("Slot"));
        }
        return highest;
    }

    /**
     * 反序列化前把 NBT 里的 {@code Size} 归位到 {@code targetSize}（只在「该键存在且与目标不同」时复制一份改，
     * 其余情况原样返回，零多余分配）。
     * <p>为什么必须做：{@link ItemStackHandler#deserializeNBT} 会按 NBT 的 {@code Size} 调 {@code setSize}，
     * 于是坏档 / 旧档的 Size 会直接决定 handler 的尺寸 —— 不归位就会被「撑大」或「缩水」。</p>
     */
    private static CompoundTag normalizedInventoryTag(final CompoundTag inventoryTag,
                                                      final int declaredSize, final int targetSize) {
        if (!inventoryTag.contains("Size", Tag.TAG_INT) || declaredSize == targetSize) {
            return inventoryTag;
        }
        final CompoundTag normalized = inventoryTag.copy();
        normalized.putInt("Size", targetSize);
        return normalized;
    }

    /** 迁移前清空 20 格新库存（不触发逐格标脏，见 {@link #migratingInventory}）。 */
    private void clearInventoryForMigration() {
        final boolean previous = migratingInventory;
        migratingInventory = true;
        try {
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        } finally {
            migratingInventory = previous;
        }
    }

    /**
     * 把旧档的一件物品搬进新布局：<b>可降解物 → 输入格优先</b>（遵守当前堆叠上限），
     * 其余（含旧档里已经不可降解的残留 / 别的模组的东西）<b>→ 副产物格</b>（先不判可降解），
     * 仍然放不下 ⇒ 交给调用方塞进 {@link #pendingDump}。
     *
     * <p>返回放不下的余量；<b>不销毁任何东西</b>。为什么非可降解物也进副产物格：旧档里那本来就
     * 是玩家的东西（可能是别的模组 / 数据包改过表之后留下的），新机器至少要让玩家能原样取走。</p>
     */
    private ItemStack migrateStack(final ItemStack stack) {
        ItemStack remaining = stack;
        if (DecompositionTable.isDecomposable(remaining)) {
            for (int i = 0; i < DecompositionGeneratorSpec.INPUT_COUNT && !remaining.isEmpty(); i++) {
                remaining = inventory.insertItem(
                    DecompositionGeneratorSpec.inputSlot(i), remaining, false);
            }
        }
        if (!remaining.isEmpty()) {
            final ItemStack[] outputs = outputSnapshot();
            remaining = depositIntoFour(outputs, remaining);
            writeOutputs(outputs);
        }
        return remaining;
    }

    /** 往「待掉出」队列加一份余量（同一物品尽量并堆，减少首个 tick 的掉落物实体数量）。 */
    private void addToPendingDump(final ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = 0; i < pendingDump.size() && !remaining.isEmpty(); i++) {
            final ItemStack existing = pendingDump.get(i);
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            final int room = existing.getMaxStackSize() - existing.getCount();
            if (room <= 0) {
                continue;
            }
            final int moved = Math.min(room, remaining.getCount());
            pendingDump.set(i, existing.copyWithCount(existing.getCount() + moved));
            remaining = remaining.copyWithCount(remaining.getCount() - moved);
        }
        // 余量按「单堆上限」拆开再入队：迁移里可能出现「一格 64 个不可堆叠物」这种形态，
        // 直接把 count=44 的不可堆叠物塞进一个 ItemEntity 会让玩家捡不起来 / 捡起来丢一部分。
        while (!remaining.isEmpty()) {
            final int perStack = Math.max(1, remaining.getMaxStackSize());
            final int take = Math.min(perStack, remaining.getCount());
            pendingDump.add(remaining.copyWithCount(take));
            remaining = remaining.copyWithCount(remaining.getCount() - take);
        }
    }

    /** 「待掉出」队列落盘（只在非空时写键；读档时按同一键恢复，保证崩溃也不丢）。 */
    private void writePendingDump(final CompoundTag tag, final HolderLookup.Provider registries) {
        if (pendingDump.isEmpty()) {
            return;
        }
        final ListTag list = new ListTag();
        for (final ItemStack stack : pendingDump) {
            if (!stack.isEmpty()) {
                list.add(stack.save(registries));
            }
        }
        if (!list.isEmpty()) {
            tag.put(TAG_PENDING_DUMP, list);
        }
    }

    /** 读回「待掉出」队列（物品在首个服务端 tick 被掉到方块位置）。 */
    private void readPendingDump(final CompoundTag tag, final HolderLookup.Provider registries) {
        pendingDump.clear();
        final ListTag list = tag.getList(TAG_PENDING_DUMP, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final ItemStack stack = ItemStack.parseOptional(registries, list.getCompound(i));
            if (!stack.isEmpty()) {
                pendingDump.add(stack);
            }
        }
    }

    /**
     * 把「待掉出」队列交给拆方块结算（{@code BlockContentReleaser} 的 {@code collectItems} 调用）。
     *
     * <p>为什么需要它：{@link #pendingDump} 里的东西<b>不在</b> 20 格库存里，拆方块时如果只收库存，
     * 这份余量会随方块实体一起消失（真正意义上的丢物品）。反过来，{@code BLOCK} 档（内容随整份 NBT
     * 存进方块物品）根本不会调用 {@code collectItems}，所以不会与 NBT 里的那份重复。</p>
     */
    public void drainPendingDump(final List<ItemStack> out) {
        for (final ItemStack stack : pendingDump) {
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        pendingDump.clear();
    }

    /**
     * 把「待掉出」队列真的掉到方块位置（<b>绝不销毁</b>）。
     * <p>只在服务端跑；在首个服务端 tick 与「结算时出现理论不可达的余量」两种时机调用。</p>
     */
    private void flushPendingDump() {
        if (pendingDump.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        for (final ItemStack stack : pendingDump) {
            if (!stack.isEmpty()) {
                Block.popResource(level, worldPosition, stack.copy());
            }
        }
        pendingDump.clear();
        setChanged();
    }

    // ==================== 界面数据槽 ====================

    /**
     * 供菜单同步的实时数据（<b>14 个槽</b>，契约第 3 节）。服务端权威，客户端只读。
     *
     * <table border="1">
     *     <caption>数据槽索引表</caption>
     *     <tr><th>#</th><th>含义</th></tr>
     *     <tr><td>0 / 1</td><td>本机缓存电量 / 容量（FE）</td></tr>
     *     <tr><td>2 / 3</td><td>当前进度 tick / 当前这一件实际需要 tick（= effectiveTime，0 = 空闲）</td></tr>
     *     <tr><td>4</td><td>当前处理中的输入格下标（0..15，-1 = 空闲）</td></tr>
     *     <tr><td>5</td><td>状态位：bit0 缓存满 / bit1 正在降解 / bit2 已接网络 / bit3 副产物满</td></tr>
     *     <tr><td>6 / 7</td><td>速度升级个数 / 堆叠升级个数</td></tr>
     *     <tr><td>8</td><td>上一次完成时的发电量（FE）</td></tr>
     *     <tr><td>9 / 10</td><td>相邻推送上限（服务端 Config 下发）/ 上一 tick 实际推送</td></tr>
     *     <tr><td>11</td><td>非空输入格数（只数 0..15）</td></tr>
     *     <tr><td>12 / 13</td><td>{@code INPUT_COUNT}(16) / {@code OUTPUT_COUNT}(4)</td></tr>
     * </table>
     *
     * <p><b>每 tick 只算一次</b>：{@code AbstractContainerMenu} 每 tick 会逐槽读 {@code get(i)}（14 次），
     * 这里按 {@code level.getGameTime()} 做一格 tick 级缓存，同一 tick 内所有槽共享一份快照。</p>
     */
    public ContainerData getContainerData() {
        return new ContainerData() {
            private long snapshotTick = Long.MIN_VALUE;
            private int stored;
            private int capacity;
            private int progressTicks;
            private int totalTicks;
            private int slot;
            private int flags;
            private int speedUpgrades;
            private int stackUpgrades;
            private int last;
            private int pushLimit;
            private int pushed;
            private int occupied;

            private void refresh() {
                final long now = level == null ? Long.MIN_VALUE : level.getGameTime();
                if (now == snapshotTick) {
                    return;
                }
                snapshotTick = now;
                stored = getEnergyStored();
                capacity = getEnergyCapacity();
                progressTicks = progress;
                totalTicks = progressTotal;
                slot = currentSlot;
                flags = (bufferFull ? FLAG_BUFFER_FULL : 0)
                    | (currentSlot >= 0 ? FLAG_WORKING : 0)
                    | (hasNetwork() ? FLAG_NETWORK : 0)
                    | (outputFull ? FLAG_OUTPUT_FULL : 0);
                speedUpgrades = speedUpgradeCount();
                stackUpgrades = stackUpgradeCount();
                last = lastGenerated;
                pushLimit = Config.decompositionGeneratorPushPerTick;
                pushed = pushedLastTick;
                occupied = getOccupiedInputCount();
            }

            @Override
            public int get(final int index) {
                refresh();
                return switch (index) {
                    case 0 -> stored;
                    case 1 -> capacity;
                    case 2 -> progressTicks;
                    case 3 -> totalTicks;
                    case 4 -> slot;
                    case 5 -> flags;
                    case 6 -> speedUpgrades;
                    case 7 -> stackUpgrades;
                    case 8 -> last;
                    case 9 -> pushLimit;
                    case 10 -> pushed;
                    case 11 -> occupied;
                    case 12 -> DecompositionGeneratorSpec.INPUT_COUNT;
                    case 13 -> DecompositionGeneratorSpec.OUTPUT_COUNT;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 只读：全部由服务端逻辑决定，界面没有任何可写数据槽
            }

            @Override
            public int getCount() {
                return DATA_SLOT_COUNT;
            }
        };
    }

    // ==================== 能力注册 ====================

    /** 向 NeoForge 注册物品 handler / 能量（只出）/ RS 网络节点容器三条能力。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        // ① 物品 handler：<b>任何方向</b>都返回同一份 20 格库存（可进可出，过滤在 isItemValid）
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.DECOMPOSITION_GENERATOR_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getInventory()
        );
        // ② 能量：只出（receiveEnergy 恒 0、canReceive 恒 false）
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            RS_Create_Compat.DECOMPOSITION_GENERATOR_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getExternalEnergyStorage()
        );
        // ③ RS 网络节点容器：没有这一条，方块永远进不了网络（能量池里没有它 = 供不了电）
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.DECOMPOSITION_GENERATOR_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }

    /**
     * 只输出、不可输入的能量缓存。
     *
     * <p>{@code receiveEnergy} 恒 0 并且 {@code canReceive} 恒 false：管道 / 立方 / 充电器
     * 从任何一面都灌不进来（用户原话「电力只可以输出、不可以输入」）。
     * 内部发电走 {@link #addInternal}（<b>不是</b> receiveEnergy 语义，也不受 maxReceive 夹量）。</p>
     *
     * <p>{@code maxExtract} 给到 {@link Integer#MAX_VALUE}：对外抽取速度不该成为瓶颈
     * （相邻推送有自己的 {@link Config#decompositionGeneratorPushPerTick} 限速）。</p>
     */
    private static final class OutputOnlyEnergyStorage extends EnergyStorage {
        OutputOnlyEnergyStorage(final int capacity) {
            super(Math.max(1, capacity), 0, Integer.MAX_VALUE);
        }

        @Override
        public int receiveEnergy(final int toReceive, final boolean simulate) {
            return 0;
        }

        @Override
        public boolean canReceive() {
            return false;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        /** 内部发电写入口（只夹 [0, capacity]，与 maxReceive 无关）。 */
        void addInternal(final int amount) {
            if (amount <= 0) {
                return;
            }
            final long next = (long) this.energy + amount;
            this.energy = (int) Math.min(this.capacity, next);
        }

        /** 读档「设值」入口（只夹 [0, capacity]）。 */
        void setInternal(final int amount) {
            this.energy = Math.max(0, Math.min(this.capacity, amount));
        }

        /** 内部扣电（相邻推送 / 网络抽取共用；返回真实扣掉的量）。 */
        int extractInternal(final int amount) {
            return extractEnergy(amount, false);
        }
    }
}
