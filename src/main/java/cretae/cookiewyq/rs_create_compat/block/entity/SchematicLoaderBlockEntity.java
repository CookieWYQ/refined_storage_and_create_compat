package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.simibubi.create.content.schematics.cannon.MaterialChecklist;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.SchematicLoaderNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.RsccClusterable;
import cretae.cookiewyq.rs_create_compat.support.SafeCollect;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * 蓝图加农炮装填器（基础版）：自动从 RS 网络获取相邻 Create 蓝图加农炮（Schematicannon）所需资源，
 * 自身作为加农炮的合法容器（IItemHandler）供其提取。
 * <p>
 * 高级蓝图加农炮装填器继承本类并扩展队列功能（见子类）。
 */
public class SchematicLoaderBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<SchematicLoaderNetworkNode>
    implements cretae.cookiewyq.rs_create_compat.support.RsccClusterable,
    cretae.cookiewyq.rs_create_compat.support.RsccDiagnosable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchematicLoaderBlockEntity.class);
    protected static final ResourceLocation SPEED_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    protected static final ResourceLocation AUTOCRAFTING_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafting_upgrade");
    protected static final ResourceLocation STACK_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "stack_upgrade");
    /** 无堆叠升级时每格上限（= RS 常规堆叠），是 {@link #storageSlotCapacity(int)} 的基础值。 */
    public static final int BASE_SLOT_CAPACITY = 64;

    /** 插件槽里的升级类别编号：与 {@link #upgradeKind(ItemStack)} 一一对应、与槽位下标无关。 */
    private static final int KIND_SPEED = 0;
    private static final int KIND_AUTOCRAFT = 1;
    private static final int KIND_STACK = 2;

    /**
     * 主库存（基础版 54 格，高级版 108 格）—— <b>就是「从网络拉取进来暂存的那份内存」</b>，
     * 参与「同族装填器相邻 = 一个整体」的容量叠加 + 内容共享。
     * <p><b>非 final</b>：参与集群时会被换成「整个集群共用的那一份」（见 {@link #rscc$clusterAdopt}）。
     */
    protected SchematicLoaderInventory inventory;
    /** 本机单机内存格数（基础 54 / 高级 108）：集群载荷、拆集群缩容都按它计算。 */
    protected final int inventorySlotCount;
    /** 蓝图槽（无加农炮时手动放置已部署蓝图）。只允许放 Create 蓝图物品。<b>不参与合并</b>（各机独立）。 */
    protected final ItemStackHandler blueprintSlot = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return stack.isEmpty() || isValidBlueprintStack(stack);
        }
    };
    /** 插件槽：6 格。<b>不参与合并</b>（各机独立，速度/自动合成升级按台生效）。 */
    protected final ItemStackHandler upgradeContainer;
    /**
     * 蓝图队列（仅高级版启用；基础版 0 格占位）。
     * <p><b>非 final</b>：高级↔高级同集群时会被换成「整个集群共用的那一份队列」
     * （见 {@link #rscc$clusterAdopt}）；基础装填器不贡献队列格数（{@link #queueSlotCount} = 0）。
     */
    protected SchematicLoaderQueue queue;
    /** 本机单机队列格数（高级 27 / 基础 0）：集群队列容量与拆集群缩容都按它计算。 */
    protected final int queueSlotCount;
    /** 本机当前持有的集群载荷（库存 + 队列；身份稳定，见 {@link LoaderClusterPayload}）。 */
    private LoaderClusterPayload payload;

    protected boolean autoPrint;
    protected boolean autoRecycle;
    protected boolean autoFillGunpowder = true;

    /** 从 NBT 载入插件槽期间跳过即时校验，避免在 level 尚未就绪时丢物品。 */
    private boolean loadingUpgrades;
    /** 防止 drop 触发 onContentsChanged 导致的递归校验。 */
    private boolean validatingUpgrades;

    /** 上次同步时加农炮输出槽（空白蓝图）数量，用于识别"打印完成"事件（finishedPrinting 会 +1）。 */
    private int lastCannonEmptySchematicCount;
    /**
     * 「从未发生」的 tick 哨兵。
     *
     * <h2>⚠ 为什么不能用 {@code Long.MIN_VALUE}（2026-10-05 的同一类 bug）</h2>
     * <p>限频判定写成 {@code now - stamp >= 间隔}。若 {@code stamp = Long.MIN_VALUE}，
     * 相减会<b>溢出回绕成负数</b>，而负数永远小于正间隔 ⇒ 判定恒为「还在限频窗口内」
     * ⇒ 首条提示被永久抑制（实测同一模式在 `pushStalledOnDestination` 上造成了「每台仓恒定报推不动」
     * 的严重误判）。取 −1_000_000 远早于任何存档的 gameTime，相减不溢出，
     * 且 {@code now - NEVER} 恒大于任何间隔 ⇒ 首次必定放行。</p>
     */
    private static final long NEVER = -1_000_000L;
    /** 上次提示"网络无火药"的游戏时间，避免刷屏。 */
    private long lastGunpowderWarnTick = NEVER;
    /** 上一次「蓝图解析不出材料清单」的玩家提示 tick（限频用；见 {@link #warnParseFailure}）。 */
    private long lastParseWarnTick = NEVER;
    /** 上次是否处于"打印完成"状态（用于完成横幅的一次性触发）。 */
    private boolean lastPrintingDone;
    /** 上次队列运行状态（用于识别队列刚启动，立即部署第一张蓝图）。 */
    private boolean lastQueueRunningSeen;
    /** 本 tick 已解析出的「共享蓝图槽的加农炮」缓存（见 {@link #resolvedCannon()}）：
     *  界面每 tick 会多次读蓝图槽，而解析一次要查邻块（必要时还要 BFS 整个集群），每 tick 做一次足够。 */
    private SchematicannonBlockEntity resolvedCannonCache;
    private long resolvedCannonTick = Long.MIN_VALUE;
    /** 当前正在打印/已部署的蓝图文件名缓存（Create 打印完成会清空加农炮蓝图槽，故完成横幅需用缓存名）。 */
    private String activeBlueprintFileName = "";
    /** 最近一次成功解析的需求快照（打印完成后 cannon.checklist 会被 Create 重置，横幅需用此缓存显示每种材料数量）。
     *  快照始终来自“蓝图文件本身”的完整统计，避免 Create 复位后的残留数值（如 4 个草方块显示成 1 个）。 */
    private java.util.Map<Item, Integer> cachedRequirements = java.util.Map.of();
    /** cachedRequirements 对应的蓝图文件名（蓝图不变则不重复解析）。 */
    private String cachedRequirementsBlueprintKey = "";
    /** 当前需求快照是否来自<b>全量统计</b>（蓝图文件解析 / cannon 打印机内存扫描）。
     *  为 false 表示只是 cannon.checklist 兜底值（会被 shouldPlace 过滤而偏小，如 4 个草方块只报 3），
     *  此时每个 work tick 都会重试全量统计，成功后升级为 true，保证横幅与库存始终按全量走。 */
    private boolean cachedRequirementsFull;
    /** 独立模式"一轮收集"状态：本蓝图是否已收集完成（完成后玩家取走单个不再自动补，直到蓝图槽再次变化）。 */
    private boolean standaloneCollectComplete;
    private String standaloneBlueprintKey = "";
    /** 独立模式：本轮已交付、蓝图已被取走/自动回流后，材料仍保留在库存中等玩家取走（不再自动回收）。 */
    private boolean standaloneHoldingKit;
    private final java.util.Map<Item, Double> standalonePullBudget = new java.util.HashMap<>();
    /**
     * 本轮已向 RS 自动合成请求过的额度（按材料）。
     * <p>{@code ensureTask} 的语义是「保证至少有 N 个正在合成」；每 tick 用同一数量重复调用虽然是幂等的，
     * 但只要库存一边被消耗，请求额度就会随之变大 —— 于是合成任务被反复追加、终端里「需要量」一路上涨。
     * 这里记下本轮已经报过的额度：额度不再变大的话就<b>只报一次</b>，换蓝图 / 重新开始 / 停止时清零。
     */
    private final java.util.Map<Item, Long> autocraftRequested = new java.util.HashMap<>();
    /** 紧贴加农炮模式"一轮收集"状态：同一张蓝图 parked 且已集齐后不再反复补料；
     *  仅当加农炮正在打印（真正消耗材料）或蓝图槽变化（新/重新放入）后才继续。 */
    private ItemStack attachRoundBlueprint = ItemStack.EMPTY;
    private boolean attachRoundComplete;
    /** 日志节流（按 key 独立记录），避免同一状态每 tick 刷屏。 */
    private final java.util.Map<String, String> lastLogMessageByKey = new java.util.HashMap<>();
    private final java.util.Map<String, Long> lastLogTickByKey = new java.util.HashMap<>();
    private static final long LOG_HEARTBEAT = 200; // 同一状态最久每 10 秒重复一条（保活信号）

    /** GUI 集群顺序（已按「共享内存身份」去重）的同 tick 缓存：见 {@link #getClusterInGuiOrder()}。 */
    private java.util.List<SchematicLoaderBlockEntity> guiOrderCache;
    /** guiOrderCache 对应的游戏时间（同一 tick 复用，避免每个槽位都重算一次集群拓扑）。 */
    private long guiOrderCacheTick = Long.MIN_VALUE;

    protected void logState(final String key, final String message) {
        final Level lvl = getLevel();
        if (lvl == null) {
            return;
        }
        final long tick = lvl.getGameTime();
        final String prevMessage = lastLogMessageByKey.get(key);
        final Long prevTick = lastLogTickByKey.get(key);
        final boolean changed = !message.equals(prevMessage);
        final boolean heartbeat = prevMessage == null || prevTick == null || tick - prevTick >= LOG_HEARTBEAT;
        if (changed || heartbeat) {
            LOGGER.info("[loader {}] {}", key, message);
            lastLogMessageByKey.put(key, message);
            lastLogTickByKey.put(key, tick);
        }
    }

    public SchematicLoaderBlockEntity(final BlockPos pos, final BlockState state) {
        this(RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(), pos, state, 54, false);
    }

    protected SchematicLoaderBlockEntity(final BlockEntityType<?> blockEntityType,
                                         final BlockPos pos,
                                         final BlockState state,
                                         final int capacity,
                                         final boolean withQueue) {
        super(blockEntityType, pos, state, new SchematicLoaderNetworkNode());
        this.inventorySlotCount = capacity;
        this.inventory = new SchematicLoaderInventory(capacity);
        this.inventory.bindOwner(this);
        // 队列：高级版 27 格、基础版 0 格。与主库存一样是可被集群换成共享实例的处理器，
        // 因此这里也登记持有者（本机改动要标记到「负责落盘的主控」）。
        this.queueSlotCount = withQueue ? 27 : 0;
        this.queue = new SchematicLoaderQueue(queueSlotCount);
        this.queue.bindOwner(this);
        // 单机时的集群载荷：库存 + 队列一份最小包裹（身份稳定，管理器用 == 判定「是否已换成共享那一份」）
        this.payload = new LoaderClusterPayload(this.inventory, this.queue);
        // 插件槽写入即校验：只允许 速度/自动合成，超上限（速度 6、自动合成 1）或非法升级弹出
        this.upgradeContainer = new ItemStackHandler(6) {
            @Override
            protected void onContentsChanged(final int slot) {
                super.onContentsChanged(slot);
                if (level != null && !level.isClientSide() && !loadingUpgrades) {
                    validateUpgradeSlots();
                }
            }
        };
        this.mainNetworkNode.setBlockEntity(this);
    }

    /** 0=speed，1=autocraft，2=stack，其余 -1。 */
    private static int upgradeKind(final ItemStack stack) {
        final net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return -1;
        }
        return switch (id.getPath()) {
            case "speed_upgrade" -> KIND_SPEED;
            case "autocrafting_upgrade" -> KIND_AUTOCRAFT;
            case "stack_upgrade" -> KIND_STACK;
            default -> -1;
        };
    }

    /** 该物品栈是否是 RS 堆叠升级（供菜单按<本机>插件槽统计，按注册名判定，不依赖升级实例）。 */
    public static boolean isStackUpgrade(final ItemStack stack) {
        return !stack.isEmpty() && upgradeKind(stack) == KIND_STACK;
    }

    /** 蓝图槽/加农炮蓝图槽/队列只接受 Create 的蓝图（schematic）物品（同加农炮 canPlace 规则）。 */
    public static boolean isValidBlueprintStack(final ItemStack stack) {
        return !stack.isEmpty() && stack.is(com.simibubi.create.AllItems.SCHEMATIC.get());
    }

    /** 把物品以掉落物形式弹出到方块外（用于弹出非法蓝图/队列杂物，物品不丢失）。 */
    protected final void dropStack(final ItemStack stack) {
        if (stack.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        final net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
            level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
        entity.setDeltaMovement(0, 0.1, 0);
        level.addFreshEntity(entity);
    }

    /** 每个 work tick 的清理钩子（高级版用于弹出队列里的非蓝图杂物）。 */
    protected void onLoaderWorkTick() {
    }

    /** 蓝图队列/打印源中是否还有下一张待打印蓝图（运行状态是否应保持）。 */
    protected boolean hasPendingBlueprintQueue() {
        return false;
    }

    /** 把"停止/开始"运行状态自动切回"开始"（基础版无该状态，no-op）。 */
    protected void stopQueueRun() {
    }

    /** 校验插件槽：非法升级弹出；速度 / 堆叠升级各最多 6 个、自动合成升级最多 1 个（与空槽 tooltip 一致）。 */
    protected void validateUpgradeSlots() {
        if (loadingUpgrades || validatingUpgrades) {
            return;
        }
        validatingUpgrades = true;
        try {
            for (int i = 0; i < upgradeContainer.getSlots(); i++) {
                final ItemStack inSlot = upgradeContainer.getStackInSlot(i);
                if (!inSlot.isEmpty() && upgradeKind(inSlot) < 0) {
                    dropUpgrade(i); // 非法升级弹出（例如范围升级）
                }
            }
            // 单种上限：自动合成 1 个；速度 / 堆叠与 6 格插件槽一致（防御性保留前 N 个）
            dropBeyondKindCap(KIND_AUTOCRAFT, 1);
            dropBeyondKindCap(KIND_SPEED, cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE);
            dropBeyondKindCap(KIND_STACK, cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE);
        } finally {
            validatingUpgrades = false;
        }
    }

    /** 弹出同一种升级中超出上限的那些（保留排在前面的 {@code max} 个），多余部分落回世界。 */
    private void dropBeyondKindCap(final int kind, final int max) {
        int kept = 0;
        for (int i = 0; i < upgradeContainer.getSlots(); i++) {
            final ItemStack inSlot = upgradeContainer.getStackInSlot(i);
            if (inSlot.isEmpty() || upgradeKind(inSlot) != kind) {
                continue;
            }
            if (kept >= max) {
                dropUpgrade(i);
            } else {
                kept++;
            }
        }
    }

    private void dropUpgrade(final int slot) {
        final ItemStack removed = upgradeContainer.extractItem(slot, 1, false);
        if (!removed.isEmpty() && level != null && !level.isClientSide()) {
            final net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
                level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, removed);
            entity.setDeltaMovement(0, 0.1, 0);
            level.addFreshEntity(entity);
        }
        setChanged();
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    /**
     * 蓝图槽（<b>本机自己的物理存储</b>）：只在本机独立工作（没有可共享的加农炮）时才是蓝图的落脚点。
     * <p>之所以与界面用的 {@link #blueprintSlotView()} 分开：落盘（{@link #saveAdditional}）与拆机器
     * 弹出（{@code BlockContentReleaser}）必须只认「本机真正存着的那一份」——若它们读的是共享视图，
     * 加农炮里那张蓝图会被当成装填器的财产写进存档 / 掉到地上（同一样东西变成两份）。
     */
    public ItemStackHandler getBlueprintSlot() {
        return blueprintSlot;
    }

    /**
     * 界面与作业逻辑使用的蓝图槽<b>实时视图</b>（身份稳定，1 格）。
     * <p><b>为什么需要它</b>：装填器的蓝图槽与它所服务的加农炮蓝图槽是<b>同一份内容</b>
     * （装填器放入 = 加农炮立刻可打印；加农炮打印消耗/换图 = 装填器界面同步反映），
     * 用「各存一份副本 + 每 tick 互相搬运」的旧做法一定会在两个槽之间出现滞后与副本。
     * 这里改成<b>直接共享加农炮那个槽</b>：物品全程只有一件、只存在一个位置，
     * 不存在复制窗口，也不依赖客户端（服务端权威）。
     * <p>没有可共享的加农炮（独立模式）时退回本机蓝图槽，行为与旧版一致。
     */
    public net.neoforged.neoforge.items.IItemHandlerModifiable blueprintSlotView() {
        return blueprintView;
    }

    /**
     * 打开界面时的一次性对齐（服务端调用）：把旧档遗留的本机蓝图副本并入加农炮
     * （见 {@link #adoptLocalBlueprint}）。
     * <p><b>为什么在开界面时也要做一次</b>：装填器未接入网络 / 被红石或能量停机的时段里
     * {@code doLoaderWork} 根本不跑，玩家此时打开界面看到的应当是加农炮那一份真实内容 ——
     * 本机残留副本不该一直「藏着不发」。本方法幂等，与每 tick 的调用重复执行也无副作用。
     */
    public void adoptLocalBlueprintOnDemand() {
        final Level lvl = getLevel();
        if (lvl == null || lvl.isClientSide()) {
            return;
        }
        adoptLocalBlueprint(findClusterCannon(lvl));
    }

    public ItemStackHandler getUpgradeContainer() {
        return upgradeContainer;
    }

    /** 返回网络节点（主要用于方块被破坏时读取 Network 引用以回流物品）。 */
    public SchematicLoaderNetworkNode getNode() {
        return mainNetworkNode;
    }

    /** 蓝图队列（高级版 27 格；基础版为空占位，供菜单统一引用）。 */
    public ItemStackHandler getQueue() {
        return queue;
    }

    public boolean isAutoPrint() {
        return autoPrint;
    }

    public void setAutoPrint(final boolean autoPrint) {
        this.autoPrint = autoPrint;
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader != this) {
                loader.autoPrint = autoPrint;
                loader.setChanged();
            }
        }
        setChanged();
    }

    public boolean isAutoRecycle() {
        return autoRecycle;
    }

    public void setAutoRecycle(final boolean autoRecycle) {
        this.autoRecycle = autoRecycle;
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader != this) {
                loader.autoRecycle = autoRecycle;
                loader.setChanged();
            }
        }
        setChanged();
    }

    public boolean isAutoFillGunpowder() {
        return autoFillGunpowder;
    }

    public void setAutoFillGunpowder(final boolean autoFillGunpowder) {
        this.autoFillGunpowder = autoFillGunpowder;
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader != this) {
                loader.autoFillGunpowder = autoFillGunpowder;
                loader.setChanged();
            }
        }
        setChanged();
    }

    public long getEnergyUsage() {
        return 10;
    }

    protected boolean hasAutocraftingUpgrade() {
        return countUpgrades(AUTOCRAFTING_UPGRADE) > 0;
    }

    /**
     * 每 work tick 最多能从网络拉取的材料数量：
     * 无升级时一批 8 个（避免以前“一次只补 1 个”卡顿）；每个速度升级翻倍；
     * 6 个速度升级（满级）时数量不限，瞬间取出。
     */
    public int getMaterialPullLimit() {
        final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);
        return speed >= 6 ? Integer.MAX_VALUE : (8 << speed);
    }

    /**
     * 每格上限公式（<b>唯一实现</b>）：基础 {@link #BASE_SLOT_CAPACITY} × (1 + 堆叠升级数)，
     * 升级数按插件槽数封顶 —— 与本工程其它机器
     * （{@code CollectionCacheBlockEntity#getCacheSlotCapacity()}、
     * {@code QuantityKeeperBlockEntity} 的 {@code Math.min(countUpgrades(STACK_UPGRADE), 6)}）同一口径。
     */
    public static int storageSlotCapacity(final int stackUpgradeCount) {
        return BASE_SLOT_CAPACITY * (1 + Math.min(Math.max(0, stackUpgradeCount),
            cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE));
    }

    /**
     * <b>本机</b>每格上限：由「本机自己的」堆叠升级数量决定。
     * <p><b>为什么取本机、而不是集群合计</b>：集群共享的只是「那一份内存内容」，每台机器该按什么
     * 上限填入、界面允许叠到多少，都应由正在操作它的那一台自己的插件槽决定 —— 否则 follower 插了
     * 堆叠升级就会改变主控看到的容量口径（主控落盘时并不知道别的成员插了什么），
     * 同一份内容在不同台上还会出现两套互相矛盾的上限。取本机后每台的口径唯一且可解释：
     * 谁在填，就按谁的升级算（见 {@link #insertIntoInventory(ItemStack, boolean)} 与
     * {@link #memoryView()}）。
     */
    public int getStorageSlotCapacity() {
        return storageSlotCapacity(countUpgrades(STACK_UPGRADE));
    }

    protected int countUpgrades(final ResourceLocation upgradeId) {
        final Item upgradeItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(upgradeId);
        int count = 0;
        for (int i = 0; i < upgradeContainer.getSlots(); i++) {
            final ItemStack stack = upgradeContainer.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(upgradeItem)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    /** 取出下一张待打印蓝图（基础版从蓝图槽取，不移动；高级版覆写从队列取）。 */
    protected ItemStack getNextBlueprint() {
        return blueprintSlot.getStackInSlot(0).copy();
    }

    /** 队列流水线取走下一张蓝图（基础版无队列返回空；高级版覆写从队列移除）。 */
    protected ItemStack takeFromQueue() {
        return ItemStack.EMPTY;
    }

    /**
     * 把「刚从队列取出的蓝图」原样放回队列（基础版队列为 0 格 → 直接落回世界）。
     * <p>存在的意义：{@link #takeFromQueue()} 是<b>破坏性</b>的（已把蓝图从队列移除），
     * 若随后因任何原因无法部署（例如本轮不是「刚打完 / 刚启动」），必须把这张蓝图还回去，
     * 否则队列里的蓝图会被逐 tick 吞掉（违反「绝不销毁玩家资源」）。</p>
     *
     * @return 是否全部放回队列（false = 队列放不下，已落回世界，仍然没有销毁）
     */
    protected boolean returnToQueue(final ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        ItemStack remainder = stack;
        for (int i = 0; i < queue.getSlots() && !remainder.isEmpty(); i++) {
            remainder = queue.insertItem(i, remainder, false);
        }
        if (!remainder.isEmpty()) {
            dropIntoWorld(remainder); // 队列也满：落到方块旁等玩家捡回，绝不销毁
            setChanged();
            return false;
        }
        setChanged();
        return true;
    }

    /** 兜底归宿：把物品落到本方块处（任何「放不进任何存储」的残料都走这里，绝不销毁）。 */
    protected void dropIntoWorld(final ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        final Level level = getLevel();
        if (level != null && !level.isClientSide()) {
            Block.popResource(level, worldPosition, stack);
        }
    }

    /** 是否启用队列自动打印（高级版 queueRunning 时 true）。 */
    protected boolean shouldDeployNext() {
        return false;
    }

    /**
     * 由网络节点每 tick 驱动：为相邻蓝图加农炮补充资源。
     * <p>蓝图槽采用<b>共享引用</b>：装填器界面上的蓝图槽直接指向加农炮的蓝图槽（slot 0），
     * 放入 / 拿走都作用于同一个物理蓝图 —— 加农炮与多个装填器天然同步、不需要逐 tick 搬运，
     * 也不存在复制与"拿走又回填"的问题（见 {@link #blueprintSlotView()}）。
     */
    public void doLoaderWork(final Network network) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());
        // 兜底校验插件槽（拉取限额按速度升级在 fetch 时独立计算）
        validateUpgradeSlots();
        // 子类清理钩子（高级版：弹出队列里的非蓝图杂物）
        onLoaderWorkTick();

        final SchematicannonBlockEntity cannon = findClusterCannon(level);
        // P3：蓝图相关槽位只允许 Create 蓝图物品，发现其它物品直接弹出（不写入加农炮、不当作蓝图解析）
        purgeInvalidBlueprintStacks(cannon);
        // 旧档遗留的本机蓝图副本：并入加农炮（幂等；独立模式无加农炮时本机槽就是蓝图本体，不动）
        adoptLocalBlueprint(cannon);
        final boolean cannonAdjacent = cannon != null && isCannonAdjacent(cannon);
        final String mode = cannon == null ? "STANDALONE" : (cannonAdjacent ? "ATTACHED" : "CLUSTER-MIRROR");
        // 状态日志：本 tick 处于哪种模式、蓝图槽内容与文件名缓存（状态不变时按 10s 心跳节流）
        final ItemStack effectiveBlueprint = cannon == null
            ? blueprintSlot.getStackInSlot(0) : cannon.inventory.getStackInSlot(0);
        logState("work", String.format(
            "mode=%s blueprint=%s cache=%s queueRunning=%s autoPrint=%s",
            mode,
            effectiveBlueprint.isEmpty() ? "<empty>" : schematicFileName(effectiveBlueprint),
            activeBlueprintFileName.isEmpty() ? "<none>" : activeBlueprintFileName,
            shouldDeployNext(), autoPrint));

        if (cannon == null) {
            // 无加农炮模式：按当前蓝图（基础版=蓝图槽，高级版=队列第一个非空蓝图）从网络拉取材料；
            // 当前蓝图被玩家取走（蓝图槽空且无队列）→ 库存材料全部回流网络
            // 自动回收：无加农炮时，本机库存里可能有「上一台加农炮被拆除后残留的材料」，
            // 在没有独立蓝图、未持有交付包时回流网络（独立模式的常规回收由 doStandaloneRestock 内部处理）
            if (autoRecycle && !standaloneHoldingKit && getNextBlueprint().isEmpty()) {
                recycleLeftoverMaterials(network, cannon);
            }
            doStandaloneRestock(network);
            // P1：运行状态（高级队列）已无任何蓝图 → 自动退回"开始"等待，不再停在需要手动启动的状态
            if (shouldDeployNext() && getNextBlueprint().isEmpty()) {
                stopQueueRun();
            }
            return;
        }
        if (!cannonAdjacent) {
            // 集群成员（与 cannon 相隔其他装填器）：蓝图槽本来就是 cannon 那一份（共享视图），无需同步；
            // 不参与拉料（非紧贴容器 cannon 取不到料）。
            // <p><b>唯独不能在这里无条件回流内存</b>：集群相邻后整集群共用<b>同一份</b>内存实例
            // （见 {@link #rscc$clusterAdopt}），紧贴 cannon 的那台用的就是这一份 —— 每个 tick
            // 在这里推回网络会把刚拉进来的整套材料当场清空，形成「提示收集成功、装填器里却什么都没有、
            // 加农炮也取不到料」的假成功（用户报告：并非收集不到，而是收进来又被镜像成员倒回网络）。
            // 只有「本机确实拿着一份与紧贴成员不同的内存」这种尚未完成共享的瞬态，才回流自己那一份
            // （这是原先那句注释的本意）。
            if (!sharesMemoryWithCannonAdjacentMember(cannon)) {
                recycleBlueprintMaterialsToNetwork(network);
            }
            return;
        }

        // 识别"打印刚完成"：加农炮输出槽（slot 1）出现新的空白蓝图（finishedPrinting 会 +1）
        final ItemStack outSlot = cannon.inventory.getStackInSlot(1);
        final int emptySchematicCount = outSlot.isEmpty() ? 0 : outSlot.getCount();
        final boolean printJustFinished = emptySchematicCount > lastCannonEmptySchematicCount;
        lastCannonEmptySchematicCount = emptySchematicCount;
        // 打印完成：把全量需求刻录到输出蓝图（带部署标记）物品上——Create 的已部署蓝图离开现场后
        // 往往无法再解析，带上这份数据后独立装填器/横幅都能免解析拿到准确数量
        if (printJustFinished && !outSlot.isEmpty()) {
            stampMaterialCounts(outSlot);
            cannon.sendUpdate = true;
        }
        // 打印完成横幅：在队列部署下一张<b>之前</b>发送——此时 activeBlueprintFileName 与
        // cachedRequirements 仍属于"刚打印完的这张蓝图"，不会被下一张的部署覆盖（文件名错位/数量串图）。
        if (printJustFinished && !lastPrintingDone) {
            // Create 把「打印完成」与「蓝图没能加载」都表现为输出槽 +1 张空白蓝图
            // （finishedPrinting / initializePrinter 的 schematicErrored、schematicExpired 全都是
            // 「清空蓝图槽 + 输出槽 +1」），因此必须按 cannon.statusMsg 区分：
            // 后两条路径实际放了 <b>0 个方块</b>，绝不能报「收集成功」。
            if (cannonPrintPlacedAnyBlock(cannon)) {
                sendCompletionBanner(cannon);
                // 成就触发点：装填器把加农炮喂饱并自动打印完一张蓝图（服务端 tick，客户端无成就判定）
                cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onBlueprintPrinted(this);
            } else {
                notifyPrintAborted(cannon);
            }
        }
        lastPrintingDone = printJustFinished;

        // 蓝图内容刷新（打印完成时 Create 已把加农炮槽清空、玩家从界面换图也走同一条路）：
        // 只更新「正在使用的蓝图文件名」缓存，供完成横幅 / 需求快照使用
        updateBlueprintBaselines(cannon.inventory.getStackInSlot(0));

        // 队列自动打印流水线（仅高级版 queueRunning）：打印完成或队列刚启动时，从队列部署下一张蓝图。
        // 玩家手动从任意蓝图槽拿走蓝图不会触发重新装填（无 printJustFinished 信号）。
        final boolean queueRunning = shouldDeployNext();
        final boolean queueJustStarted = queueRunning && !lastQueueRunningSeen;
        lastQueueRunningSeen = queueRunning;
        if (queueRunning && cannon.inventory.getStackInSlot(0).isEmpty()) {
            final ItemStack next = takeFromQueue();
            if (!next.isEmpty()) {
                if (printJustFinished || queueJustStarted) {
                    cannon.inventory.setStackInSlot(0, next);
                    cannon.printer.resetSchematic();
                    cannon.sendUpdate = true;
                    // 队列部署蓝图后立即刷新基线缓存，便于下一轮打印完成横幅显示正确文件名
                    updateBlueprintBaselines(next);
                    // 「开始」= 进入新一轮收集：清掉上一轮的收集/请求状态（即使还是同一张蓝图也重新收集一遍）
                    resetRoundCollection();
                } else {
                    // 本轮不该部署（既非刚打完、也非刚启动）：takeFromQueue 已经把蓝图从队列移除了，
                    // 必须原样放回 —— 旧实现直接丢弃 next，加农炮槽一提前空出来会被逐 tick 吞掉整条队列。
                    returnToQueue(next);
                    logState("queue", "deploy deferred: blueprint returned to queue (not a print-finished tick)");
                }
            }
        }
        // P1：队列流水线把最后一张蓝图打完（或队列本来就空）→ cannon 槽与队列都无蓝图，
        // 自动把"停止/开始"运行状态切回"开始"，等待玩家放入新蓝图后再开始。
        if (queueRunning && cannon.inventory.getStackInSlot(0).isEmpty() && !hasPendingBlueprintQueue()) {
            stopQueueRun();
        }

        // 追踪本轮"是否已集齐"状态：蓝图槽清空或变化（新/重新放入）后复位
        final ItemStack currentCannonBlueprint = cannon.inventory.getStackInSlot(0);
        if (currentCannonBlueprint.isEmpty()) {
            attachRoundBlueprint = ItemStack.EMPTY;
            resetRoundCollection();
        } else if (!ItemStack.isSameItemSameComponents(currentCannonBlueprint, attachRoundBlueprint)) {
            // 换蓝图：上一张残留的多余材料先回流网络，避免与新蓝图的材料需求混在一起
            recycleBlueprintMaterialsToNetwork(network);
            attachRoundBlueprint = currentCannonBlueprint.copy();
            resetRoundCollection();
            logState("attach", "blueprint changed -> new round: " + schematicFileName(currentCannonBlueprint));
        }

        // 蓝图更换后强制刷新 checklist，避免沿用旧蓝图的材料清单
        cannon.updateChecklist();
        // 需求数量快照：优先按蓝图文件统计，失败时回退拷贝 cannon 打印中刷出的完整 checklist
        cacheRequirementsFromBlueprint(cannon);

        // 收集/补料门控：每张蓝图只拉“一整份”材料（一次性集齐后即停，不再随打印消耗补货），
        // 让加农炮在打印中把这份材料从满额消耗到 0，打印完成/换下一张蓝图时才重新拉下一份。
        // 「收尾」判据（先到者为准）：
        //   1) 本机口径：全量需求（cachedRequirements）已在集群库存里全部就位 —— 与拉取循环同一判据，
        //      不会出现「拉取说没够、库存说够了」的错位；
        //   2) Create 口径：加农炮 checklist 认为已够（required 被 shouldPlace 过滤，可能小于全量需求）。
        // 为什么必须补上 1)：Create 的 checklist 在打印中会随消耗一起回落，只按它判会<b>永远判不出「够了」</b>，
        // 于是每个 tick 继续向网络拉料（用户报告的「资源一直在涨、点了停止还在涨」）。
        if (!attachRoundComplete) {
            if (isCollectionEnabled()) {
                restockFromNetwork(cannon, network);
            }
            if (isRoundCollected(cachedRequirements)
                || (!cannon.checklist.required.isEmpty() && isResourcesReady(cannon))) {
                attachRoundComplete = true;
                logState("attach", "round complete for " + schematicFileName(currentCannonBlueprint));
            }
        }

        if (autoFillGunpowder) {
            restockGunpowderDirect(cannon, network);
        }

        // 自动回收（电平触发）：只要开关是打开的，加农炮输出槽里已经打印出的空白蓝图就送回网络。
        // 旧实现只在「打印完成的那一个 tick」回收 —— 开关打开前 / 装填器停机（红石关、能量不足）期间
        // 堆积在槽里的空白蓝图永远不会被搬走（用户报告的「蓝图堵在加农炮里不回流」）。
        if (autoRecycle) {
            recycleCannonOutput(network, cannon);
            // 蓝图完成 / 被取走时（加农炮槽 0 为空），本机库存里没被加农炮消耗完的剩余材料
            // （如樱花木活板门）回流网络 —— 旧实现只回收输出槽的空白蓝图，库存里的材料会永久滞留
            recycleLeftoverMaterials(network, cannon);
        }

        // 自动打印：由本机持续驱动加农炮 RUNNING（Create 初始化打印机后停在 PAUSED-"ready"，
        // 原版需要玩家在加农炮界面按开始；这里在就绪条件满足时自动代按，形成连续打印流水线）
        autoDriveCannon(cannon);
    }

    /**
     * 自动打印驱动：让 cannon.state 由本机接管为 RUNNING，避免"打完一张就要手动按开始"。
     * <ul>
     *   <li>STOPPED 且蓝图在位：资源就绪即启动（Create 会在 RUNNING 时初始化打印机）；</li>
     *   <li>PAUSED 且打印机已加载、无缺料/未加载目标/火药：Create 只缺"开始"信号，直接置 RUNNING；</li>
     *   <li>PAUSED 且缺料/等区块/缺火药：交给 Create 自身的恢复路径，本机不打扰。</li>
     * </ul>
     */
    private void autoDriveCannon(final SchematicannonBlockEntity cannon) {
        if (!shouldAutoPrint() || cannon.inventory.getStackInSlot(0).isEmpty()) {
            return;
        }
        if (cannon.state == State.RUNNING || cannon.printer.isErrored()) {
            return;
        }
        final boolean startable;
        if (cannon.state == State.STOPPED) {
            startable = true;
        } else if (cannon.state == State.PAUSED) {
            // 具备继续打印条件：已加载打印机且没有等待缺料/区块/火药（remainingFuel>0 表示火药已补上）
            startable = cannon.printer.isLoaded()
                && !cannon.positionNotLoaded
                && cannon.missingItem == null
                && cannon.remainingFuel > 0;
        } else {
            startable = false;
        }
        if (startable && isRoundReadyForPrint(cannon)) {
            cannon.state = State.RUNNING;
            cannon.sendUpdate = true;
            logState("drive", "auto -> RUNNING for " + schematicFileName(cannon.inventory.getStackInSlot(0)));
        }
    }

    /** 本轮是否允许向网络拉取资源。
     *  <p>基础版恒 {@code true}（蓝图在位 = 开始收集）；高级版<b>仅在队列运行中</b>为 true ——
     *  「开始」= 开始收集、「停止」= <b>立即</b>停止收集，两者都是服务端权威状态（见子类覆写）。 */
    protected boolean isCollectionEnabled() {
        return true;
    }

    /** 复位「本轮收集」的全部状态（换蓝图 / 队列开始 / 队列停止时调用）：下一轮从零重算并重新拉取，
     *  同时清掉已报过的自动合成额度（否则新的一轮会因为「报过」而不再请求合成）。 */
    protected void resetRoundCollection() {
        attachRoundComplete = false;
        autocraftRequested.clear();
        standalonePullBudget.clear();
        setChanged();
    }

    /**
     * 本轮全量需求是否已全部就位：<b>与拉取循环同一判据</b>（{@code cachedRequirements} vs
     * {@link #countItemInCluster(Item)}），因此不存在「拉取时按 A 判、收尾时按 B 判」的错位。
     * <p>需求为空（尚未解析出来）一律返回 false：宁可不收尾，也不要凭空调用「够了」。
     */
    private boolean isRoundCollected(final java.util.Map<Item, Integer> needs) {
        if (needs == null || needs.isEmpty()) {
            return false;
        }
        for (final java.util.Map.Entry<Item, Integer> entry : needs.entrySet()) {
            if (countItemInCluster(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否已具备「自动打印」的条件：<b>本轮收集已经收尾</b>（
     * {@link #isRoundCollected} 已集齐，或 Create 口径已够 —— 见 {@code doLoaderWork} 的收尾判据）
     * 且加农炮自己能抓到所需材料（{@code cannon.checklist.gathered >= required}）。
     * <p>注意 {@code required} 为空（打印机尚未加载）时 {@link #isResourcesReady} 视为 true：
     * 这是必要的 —— 只有让加农炮进 RUNNING，Create 才会初始化打印机并报出真实需求 / 报错状态。
     * <p>「本轮是否收尾」由<b>紧贴加农炮的那台</b>维护（只有它跑补料分支）：集群里任意一台都
     * 可能承担自动打印驱动，因此这里按整集群查一次。
     */
    private boolean isRoundReadyForPrint(final SchematicannonBlockEntity cannon) {
        return isRoundCollectedForCannon(cannon)
            && !cannon.printer.isErrored()
            && isResourcesReady(cannon);
    }

    /** 集群口径的本轮收尾状态：本机没收尾时，看紧贴加农炮的成员是否已收尾。 */
    private boolean isRoundCollectedForCannon(final SchematicannonBlockEntity cannon) {
        if (attachRoundComplete) {
            return true;
        }
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader.isCannonAdjacent(cannon) && loader.attachRoundComplete) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自动回收：把加农炮输出槽（slot 1）里的空白蓝图送回 RS 网络。
     * <p>只搬「Create 明确放进去的空白蓝图」——输出槽里任何其它物品一律不碰。
     * <p>安全语义沿用 {@link SafeCollect}：先算网络容量 → 只搬能收下的那部分 →
     * 收不下的原样留在槽里，<b>绝不销毁、绝不清空后再写</b>。
     */
    private void recycleCannonOutput(final Network network, final SchematicannonBlockEntity cannon) {
        final ItemStack out = cannon.inventory.getStackInSlot(1);
        if (out.isEmpty() || !out.is(com.simibubi.create.AllItems.EMPTY_SCHEMATIC.get())) {
            return;
        }
        final long accepted = returnToNetwork(network, out);
        if (accepted <= 0L) {
            logState("recycle:full", "network storage is full -> empty schematic stays in cannon output slot");
            return;
        }
        cannon.inventory.setStackInSlot(1, accepted >= out.getCount()
            ? ItemStack.EMPTY
            : out.copyWithCount((int) (out.getCount() - accepted)));
        cannon.sendUpdate = true;
        logState("recycle", "recycled empty schematic x" + accepted + " back to network");
    }

    /**
     * 自动回收本机库存里的剩余材料：蓝图完成 / 被取走 / 加农炮被拆除时，
     * 没被加农炮消耗完的材料（如樱花木活板门）送回 RS 网络。
     * <p>触发条件：无活动蓝图（加农炮不在 / 加农炮槽 0 为空）—— 有蓝图在位时不回收，
     * 避免把正在收集的材料当场清空。
     * <p>安全语义沿用 {@link #recycleCannonOutput}：先算网络容量 → 只搬能收下的那部分 →
     * 收不下的原样留在槽里，<b>绝不销毁、绝不清空后再写</b>。
     */
    private void recycleLeftoverMaterials(final Network network, final SchematicannonBlockEntity cannon) {
        // 有活动蓝图时不回收（加农炮在位且槽 0 非空 = 正在打印 / 等待打印）
        if (cannon != null && !cannon.inventory.getStackInSlot(0).isEmpty()) {
            return;
        }
        boolean movedAny = false;
        for (int i = 0; i < inventory.getSlots(); i++) {
            final ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            final long accepted = returnToNetwork(network, stack);
            if (accepted <= 0L) {
                // 网络已满：剩余物品继续留在本机库存，绝不销毁
                logState("recycle:leftover:full", "network full -> leftover stays in loader slot " + i);
                continue;
            }
            inventory.setStackInSlot(i, accepted >= stack.getCount()
                ? ItemStack.EMPTY
                : stack.copyWithCount((int) (stack.getCount() - accepted)));
            movedAny = true;
            logState("recycle:leftover", "recycled leftover x" + accepted
                + " from loader slot " + i + " back to network");
        }
        if (movedAny) {
            setChanged();
        }
    }

    /**
     * P3：蓝图槽 / 加农炮蓝图槽只允许放 Create 蓝图物品。
     * 任何途径混入的非蓝图物品都在这里直接弹出（掉落物），防止被当作蓝图解析/部署。
     * <p>本机蓝图槽只在独立模式（没有可共享的加农炮）下才可能有内容；
     * 有加农炮时界面上那个槽就是加农炮槽本身（见 {@link #blueprintSlotView()}）。
     */
    private void purgeInvalidBlueprintStacks(final SchematicannonBlockEntity cannon) {
        boolean purged = false;
        final ItemStack loaderStack = blueprintSlot.getStackInSlot(0);
        if (!loaderStack.isEmpty() && !isValidBlueprintStack(loaderStack)) {
            logState("purge", "ejected non-blueprint from loader blueprint slot: "
                + loaderStack.getHoverName().getString());
            blueprintSlot.setStackInSlot(0, ItemStack.EMPTY);
            setChanged();
            dropStack(loaderStack);
            purged = true;
        }
        if (cannon != null) {
            final ItemStack cannonStack = cannon.inventory.getStackInSlot(0);
            if (!cannonStack.isEmpty() && !isValidBlueprintStack(cannonStack)) {
                logState("purge", "ejected non-blueprint from cannon blueprint slot: "
                    + cannonStack.getHoverName().getString());
                cannon.inventory.setStackInSlot(0, ItemStack.EMPTY);
                cannon.printer.resetSchematic();
                if (cannon.state != State.STOPPED) {
                    cannon.state = State.STOPPED;
                }
                cannon.sendUpdate = true;
                dropStack(cannonStack);
                purged = true;
            }
        }
        // 仅在确实弹出过时才刷新基线：否则会把本 tick 用户刚放入的蓝图误判成“早已同步”
        if (purged) {
            updateBlueprintBaselines(cannon == null
                ? blueprintSlot.getStackInSlot(0) : cannon.inventory.getStackInSlot(0));
        }
    }

    /** 紧贴该加农炮的邻块里是否有高级装填器正处于队列自动打印。 */
    private boolean isQueueRunningOnRig(final SchematicannonBlockEntity cannon) {
        final Level level = getLevel();
        if (level == null) {
            return false;
        }
        for (final Direction direction : Direction.values()) {
            final BlockEntity neighbor = level.getBlockEntity(cannon.getBlockPos().relative(direction));
            if (neighbor instanceof AdvancedSchematicLoaderBlockEntity advanced && advanced.isQueueRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 共享蓝图槽此刻是否由「高级装填器的队列流水线」掌管（掌管期间拒绝从界面改动它）。
     * <p><b>为什么必须拒绝</b>：蓝图槽现在是加农炮那一份物理内容，而队列靠
     * 「加农炮槽里的蓝图被打印消耗掉 → 打印完成」这个信号推进下一张。若此时允许从界面把这张
     * 蓝图拿走（旧实现下装填器另有副本，拿走不影响加农炮），加农炮会停在空槽上，而队列补位只认
     * 「打印完成」信号 → 整条流水线会永久停住。拒绝 = 什么都不做（不搬运、不销毁），
     * 玩家在高级界面按「停止」后即可正常操作。
     * <p>两层判据：紧贴加农炮的高级装填器；以及同集群里任意一台正在跑队列的高级装填器
     * （非紧贴成员同样会把下一张蓝图部署进这台加农炮）。
     */
    private boolean isBlueprintSlotManagedByQueue(final SchematicannonBlockEntity cannon) {
        if (isQueueRunningOnRig(cannon)) {
            return true;
        }
        if (getLevel() == null) {
            return false;
        }
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader instanceof AdvancedSchematicLoaderBlockEntity advanced && advanced.isQueueRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本装填器「正在共享蓝图槽」的那台加农炮（无 = 独立模式）。
     * <p>取整个集群视角（见 {@link #findClusterCannon}）：不相邻的集群成员也应当看到同一张蓝图。
     * <p>每 tick 只解析一次（结果按游戏时间缓存）：界面每 tick 会多次读蓝图槽，
     * 而解析要查邻块（必要时 BFS 整个集群），没必要重复。
     * <p>客户端恒返回 null：客户端界面用的是占位容器，内容由原版槽位同步下发，
     * 服务端才是唯一权威（客户端绝不写加农炮）。
     */
    @Nullable
    private SchematicannonBlockEntity resolvedCannon() {
        final Level lvl = getLevel();
        if (lvl == null || lvl.isClientSide()) {
            return null;
        }
        final long tick = lvl.getGameTime();
        if (tick != resolvedCannonTick) {
            resolvedCannonTick = tick;
            resolvedCannonCache = findClusterCannon(lvl);
        }
        return resolvedCannonCache;
    }

    /**
     * 蓝图槽的共享视图（1 格，身份稳定）：有可共享的加农炮时，它就是<b>加农炮蓝图槽本身</b>；
     * 否则退回本机蓝图槽（独立模式）。见 {@link #blueprintSlotView()}。
     * <p><b>读为什么也返回副本</b>：原版槽位在 shift 点击时会先把 {@code getItem()} 拿到的栈就地
     * 改小（{@code setCount}）再回写；若把加农炮槽里那个真实对象交出去，本视图在回写时拿到的
     * 「改动前内容」已被就地改掉，就会漏判「内容变了」而不去重置加农炮的打印机。
     * <p>所有写入都只落在「当下那一份真实存储」上：不存在两边各存一份的中间态，因此没有复制窗口；
     * 拒收 / 拒绝取出时一律原样返回（物品留在玩家光标上），绝不吞物品。
     */
    private final class BlueprintView implements net.neoforged.neoforge.items.IItemHandlerModifiable {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            if (slot != 0) {
                return ItemStack.EMPTY;
            }
            final SchematicannonBlockEntity cannon = resolvedCannon();
            return cannon == null
                ? blueprintSlot.getStackInSlot(0).copy()
                : cannon.inventory.getStackInSlot(0).copy();
        }

        @Override
        public int getSlotLimit(final int slot) {
            final SchematicannonBlockEntity cannon = resolvedCannon();
            return cannon == null ? blueprintSlot.getSlotLimit(0) : cannon.inventory.getSlotLimit(0);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return stack.isEmpty() || isValidBlueprintStack(stack);
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            if (slot != 0 || stack.isEmpty()) {
                return stack;
            }
            final SchematicannonBlockEntity cannon = resolvedCannon();
            if (cannon == null) {
                return blueprintSlot.insertItem(0, stack, simulate);
            }
            if (!isValidBlueprintStack(stack)) {
                return stack; // 非蓝图：拒收（物品留在玩家光标上）
            }
            if (isBlueprintSlotManagedByQueue(cannon)) {
                logState("blueprint-lock", "queue pipeline owns the cannon blueprint slot -> insert refused");
                return stack;
            }
            final ItemStack before = cannon.inventory.getStackInSlot(0);
            final ItemStack rest = cannon.inventory.insertItem(0, stack, simulate);
            if (!simulate && !ItemStack.isSameItemSameComponents(before, cannon.inventory.getStackInSlot(0))) {
                afterSharedBlueprintWrite(cannon, "insert");
            }
            return rest;
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            if (slot != 0 || amount <= 0) {
                return ItemStack.EMPTY;
            }
            final SchematicannonBlockEntity cannon = resolvedCannon();
            if (cannon == null) {
                return blueprintSlot.extractItem(0, amount, simulate);
            }
            if (isBlueprintSlotManagedByQueue(cannon)) {
                logState("blueprint-lock", "queue pipeline owns the cannon blueprint slot -> extract refused");
                return ItemStack.EMPTY;
            }
            final ItemStack taken = cannon.inventory.extractItem(0, amount, simulate);
            if (!simulate && !taken.isEmpty()) {
                afterSharedBlueprintWrite(cannon, "extract");
            }
            return taken;
        }

        @Override
        public void setStackInSlot(final int slot, final ItemStack stack) {
            if (slot != 0) {
                dropIntoWorld(stack); // 越界写入：落回世界，绝不销毁
                return;
            }
            final SchematicannonBlockEntity cannon = resolvedCannon();
            if (cannon == null) {
                blueprintSlot.setStackInSlot(0, stack);
                return;
            }
            if (!stack.isEmpty() && !isValidBlueprintStack(stack)) {
                dropIntoWorld(stack); // 非蓝图：不写进加农炮，但也不销毁
                return;
            }
            if (isBlueprintSlotManagedByQueue(cannon)) {
                logState("blueprint-lock", "queue pipeline owns the cannon blueprint slot -> set refused");
                return;
            }
            if (ItemStack.isSameItemSameComponents(cannon.inventory.getStackInSlot(0), stack)) {
                return; // 内容相同：不打断正在进行的打印
            }
            cannon.inventory.setStackInSlot(0, stack);
            afterSharedBlueprintWrite(cannon, "set");
        }
    }

    /** 界面 / 作业逻辑使用的蓝图槽共享视图（见 {@link #blueprintSlotView()}）。 */
    private final net.neoforged.neoforge.items.IItemHandlerModifiable blueprintView = new BlueprintView();

    /**
     * 通过共享蓝图槽改动加农炮蓝图后的统一副作用：让加农炮按新内容重新加载打印机。
     * <p>沿用旧「写加农炮蓝图」那条路径的同一套动作（重置打印机 + 回落 STOPPED，
     * 由自动打印重新驱动）；只在内容确实变了时才调用 —— 同一张蓝图不打断正在进行的打印。
     */
    private void afterSharedBlueprintWrite(final SchematicannonBlockEntity cannon, final String cause) {
        cannon.printer.resetSchematic();
        if (cannon.state != State.STOPPED) {
            cannon.state = State.STOPPED;
        }
        cannon.sendUpdate = true;
        setChanged();
        final ItemStack now = cannon.inventory.getStackInSlot(0);
        logState("blueprint-share", cause + " -> cannon blueprint = "
            + (now.isEmpty() ? "<empty>" : schematicFileName(now)));
    }

    /**
     * 把旧档遗留的「本机蓝图副本」并入加农炮（幂等：本机槽清空后自然不再生效）。
     * <p><b>为什么需要</b>：旧实现让装填器自存一份副本、再与加农炮逐 tick 双向同步，
     * 于是同一张蓝图在存档里留下两份（本机 + 加农炮）。蓝图槽现在直接共享加农炮那一份
     * （见 {@link #blueprintSlotView()}），本机那份若不处理，就会变成界面上永远看不见的孤儿物品。
     * <ul>
     *     <li>加农炮槽空 → 把本机副本<b>搬</b>进加农炮（换位置，不是复制）；</li>
     *     <li>加农炮槽已有蓝图 → 本机那份是旧镜像多出来的那一件，弹到方块旁让玩家取回（不销毁）。</li>
     * </ul>
     * 也覆盖「独立模式放入蓝图后又放了加农炮」：蓝图随机器升级搬进加农炮，不会凭空消失。
     */
    private void adoptLocalBlueprint(@Nullable final SchematicannonBlockEntity cannon) {
        if (cannon == null) {
            return; // 独立模式：本机槽就是蓝图本体
        }
        final ItemStack local = blueprintSlot.getStackInSlot(0);
        if (local.isEmpty()) {
            return;
        }
        blueprintSlot.setStackInSlot(0, ItemStack.EMPTY);
        setChanged();
        if (cannon.inventory.getStackInSlot(0).isEmpty()) {
            cannon.inventory.setStackInSlot(0, local.copy());
            afterSharedBlueprintWrite(cannon, "adopt-local");
            logState("blueprint-share", "local blueprint moved into cannon: " + schematicFileName(local));
        } else {
            dropStack(local);
            logState("blueprint-share", "legacy local blueprint copy dropped for pickup: "
                + schematicFileName(local));
        }
    }

    /** 刷新「正在使用的蓝图文件名」缓存（完成横幅 / 需求快照用）：Create 打印完成会清空加农炮
     *  蓝图槽，但横幅还需要这张蓝图的名字，因此只在确实拿到蓝图时才更新（清空后保留上一张）。 */
    private void updateBlueprintBaselines(final ItemStack cannonBlueprint) {
        if (!cannonBlueprint.isEmpty()) {
            this.activeBlueprintFileName = schematicFileName(cannonBlueprint);
        }
    }

    /** 从蓝图栈读文件名（SCHEMATIC_FILE，如 "1.nbt"）；无则回退 hover 名称文本。 */
    private static String schematicFileName(final ItemStack blueprint) {
        if (blueprint.isEmpty()) {
            return "";
        }
        final String file = blueprint.get(com.simibubi.create.AllDataComponents.SCHEMATIC_FILE);
        if (file != null && !file.isBlank()) {
            return file;
        }
        return blueprint.getHoverName().getString();
    }

    /** 是否触发自动打印（高级版队列运行时可覆写）。 */
    protected boolean shouldAutoPrint() {
        return autoPrint;
    }

    /** 无加农炮模式：按当前蓝图从网络"拉一整套材料"，只输出一次。
     *  <p>本轮集齐（或网络彻底无法再供给）后即锁定：之后玩家取走任何材料都不会再自动补，
     *  直到蓝图槽发生变化（取走后再放入同一张 / 放入新蓝图）才重新输出一轮。
     *  <p>蓝图始终保持原样：已部署蓝图不会被清空或变成空白蓝图。若开启自动回收
     *  （autoRecycle），本轮交付后把蓝图送回 RS 网络并清空蓝图槽；已拉取的材料作为
     *  交付成果保留在库存中，不会被空槽触发回流。
     *  <p>拉取速率受速度升级约束（无升级约 2tick/个，满级瞬间取完）。
     */
    private void doStandaloneRestock(final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final AutocraftingNetworkComponent autocrafting = network.getComponent(AutocraftingNetworkComponent.class);
        final ItemStack blueprint = getNextBlueprint();
        final String blueprintKey = schematicFileName(blueprint);
        if (blueprint.isEmpty()) {
            if (standaloneHoldingKit) {
                // 上一轮已交付、蓝图已离开：材料是交付成果，留在库存等玩家取走
                logState("standalone", "holding delivered kit, blueprint empty -> no-op");
                return;
            }
            if (standaloneCollectComplete) {
                // 本轮已集齐、玩家随后取走蓝图：材料是交付成果，保留在库存中不回流
                standaloneHoldingKit = true;
                standaloneCollectComplete = false;
                standaloneBlueprintKey = "";
                standalonePullBudget.clear();
                logState("standalone", "completed round - blueprint removed -> holding kit");
                return;
            }
            // 没有已完成轮次：库存中的多余材料（手动放入的杂物 / 半成品）回流网络并复位状态
            logState("standalone", "blueprint empty (no kit held) -> recycling inventory");
            standaloneBlueprintKey = "";
            standalonePullBudget.clear();
            recycleBlueprintMaterialsToNetwork(network);
            return;
        }
        logState("standalone", "blueprint=" + blueprintKey + " complete=" + standaloneCollectComplete);
        if (standaloneHoldingKit) {
            standaloneHoldingKit = false; // 放入新蓝图：进入新一轮
        }
        // 蓝图变化（含取走后再放入同一张）→ 复位本轮状态，允许再输出一轮；
        // 换成了不同蓝图时，上一轮残留材料先回流，避免污染新蓝图的"已有数量"判定
        if (!blueprintKey.equals(standaloneBlueprintKey)) {
            if (!standaloneBlueprintKey.isEmpty()) {
                recycleBlueprintMaterialsToNetwork(network);
            }
            standaloneBlueprintKey = blueprintKey;
            standaloneCollectComplete = false;
            standalonePullBudget.clear();
            autocraftRequested.clear();
            logState("standalone", "new blueprint -> new round");
        }
        if (standaloneCollectComplete) {
            return; // 本轮已交付：不重复补料
        }
        if (!isCollectionEnabled()) {
            // 「停止」（高级版队列未运行）：本轮立即不再向网络拉取。
            // 已经拉进库存的材料原样留着（绝不回流、绝不销毁），等玩家自己取走或下一次「开始」。
            return;
        }
        // 需求口径：优先复用本机缓存快照（P6 全量统计；可能来自此前紧贴打印/刻录数据），
        // 未命中才解析一次并写入缓存 —— 不再每 tick 重新解析蓝图（P4 也能从网络按清单逐项拉取）
        final java.util.Map<Item, Integer> needs = standaloneNeeds(blueprintKey, blueprint);
        if (needs == null || needs.isEmpty()) {
            logState("standalone:null:" + blueprintKey,
                "CHECKLIST NULL: blueprint " + blueprintKey + " could not be parsed");
            // 失败不得静默：蓝图在位却拿不到需求 → 这一轮不会拉任何材料，玩家必须知道原因
            warnParseFailure(blueprintKey);
            return; // 蓝图无效/无法解析（同一张蓝图按 10s 心跳限频）
        }
        final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);
        final double rate = speed >= 6 ? Double.MAX_VALUE : (0.5 * (1 << speed)); // 无升级0.5/tick，满级瞬间
        final boolean[] allSatisfied = {true};
        final boolean[] pulledAnything = {false};
        logState("standalone", "checklist OK, distinct materials=" + needs.size()
            + " speedUps=" + speed);
        for (final java.util.Map.Entry<Item, Integer> entry : needs.entrySet()) {
            final Item item = entry.getKey();
            final int needed = entry.getValue();
            final int have = countItemInCluster(item);
            final int diff = needed - have;
            if (diff > 0) {
                allSatisfied[0] = false;
                final ResourceKey itemResource = new ItemResource(item, DataComponentPatch.EMPTY);
                if (storage.get(itemResource) <= 0) {
                    continue; // 网络此刻无货：交给下方的"停滞判定"统一处理（可自动合成则等待）
                }
                final double budget = standalonePullBudget.getOrDefault(item, 0D) + rate;
                final long canPull = speed >= 6 ? diff
                    : Math.min(diff, (long) budget); // 速度不足时按累计预算分批，约 2tick/个起步
                if (canPull <= 0) {
                    standalonePullBudget.put(item, budget);
                    continue;
                }
                standalonePullBudget.put(item, budget - canPull);
                pulledAnything[0] = true;
                fetchFromNetwork(item, canPull, storage, autocrafting);
            } else if (diff < 0) {
                // 多余部分：从集群库存回流网络（走统一安全语义：先算网络容量 → 只抽能收下的量 → 差额回滚）
                returnExcessToNetwork(item, -diff, storage);
            }
        }
        if (allSatisfied[0]) {
            standaloneCollectComplete = true;
            logState("standalone", "collection complete for " + blueprintKey);
            // 集齐即把全量需求刻录到蓝图本体，之后移走/回流到网络都能免解析复用
            if (!blueprintSlot.getStackInSlot(0).isEmpty()) {
                stampMaterialCounts(blueprintSlot.getStackInSlot(0));
            }
            sendStandaloneCompletion(blueprint);
            // 自动回流：把这张部署蓝图（保留全部组件）送回网络；材料保留在库存中作为交付成果
            if (isAutoRecycle() && !blueprintSlot.getStackInSlot(0).isEmpty()) {
                final ItemStack inSlot = blueprintSlot.getStackInSlot(0);
                stampMaterialCounts(inSlot);
                // 安全语义：先算网络能收下多少 → 只把能收下的量从槽里拿走。
                // 网络（存储）满时蓝图原样留在槽里等下一轮重试，绝不先清槽后写网导致蓝图消失。
                final int returned = SafeCollect.pushItemsToNetwork(storage, blueprintSlot, 0,
                    inSlot.getCount(), this::dropIntoWorld);
                setChanged();
                if (blueprintSlot.getStackInSlot(0).isEmpty()) {
                    standaloneHoldingKit = true; // 空槽后材料仍保留
                    standaloneCollectComplete = false;
                    standaloneBlueprintKey = "";
                    standalonePullBudget.clear();
                    logState("standalone", "auto-recycle -> deployed blueprint returned to network, kit held");
                } else {
                    logState("standalone:full", "network storage is full -> deployed blueprint kept in slot"
                        + " (returned " + returned + "), will retry");
                }
            }
        } else if (!pulledAnything[0] && isStandaloneStalled(needs, storage, autocrafting)) {
            // 网络完全无法供给（无库存且不可自动合成）：结束本轮并如实报告缺料，不无限空转
            standaloneCollectComplete = true;
            logState("standalone", "network cannot supply remaining materials -> round closed");
            sendStandaloneCompletion(blueprint);
        }
    }

    /** 独立模式需求清单：命中 {@link #cachedRequirements}（同蓝图 key）直接复用；
     *  未命中则解析一次（全量统计优先，写入缓存后本轮及后续 tick 共用）。 */
    @Nullable
    private java.util.Map<Item, Integer> standaloneNeeds(final String blueprintKey, final ItemStack blueprint) {
        if (blueprintKey.equals(cachedRequirementsBlueprintKey) && !cachedRequirements.isEmpty()) {
            return cachedRequirements;
        }
        final MaterialChecklist checklist = computeChecklist(blueprint);
        if (checklist == null || checklist.required.isEmpty()) {
            return null;
        }
        applyCachedSnapshot(checklist, blueprintKey, true);
        logState("standalone:cache:" + blueprintKey,
            "standalone cached requirements for " + blueprintKey + " -> " + cachedRequirements.size()
                + " materials");
        return cachedRequirements;
    }

    /** 本轮是否已无法取得进展：所有仍缺的材料在网络中都无库存、也没有可用的自动合成样板。 */
    private boolean isStandaloneStalled(final java.util.Map<Item, Integer> needs,
                                        final StorageNetworkComponent storage,
                                        final AutocraftingNetworkComponent autocrafting) {
        final boolean canAutoCraft = hasAutocraftingUpgrade();
        for (final java.util.Map.Entry<Item, Integer> entry : needs.entrySet()) {
            final Item item = entry.getKey();
            final int needed = entry.getValue();
            if (countItemInCluster(item) >= needed) {
                continue;
            }
            final ResourceKey resource = new ItemResource(item, DataComponentPatch.EMPTY);
            if (storage.get(resource) > 0) {
                return false; // 网络有货：下个 tick 还能继续拉
            }
            if (canAutoCraft && !autocrafting.getPatternsByOutput(resource).isEmpty()) {
                return false; // 可自动合成：等待合成产出
            }
        }
        return true;
    }

    /**
     * 用临时 SchematicPrinter 在内存中计算蓝图所需的材料清单（不依赖加农炮）。
     * 仅处理已部署蓝图（带 SCHEMATIC_ANCHOR + SCHEMATIC_DEPLOYED 组件）。
     * <p>直接遍历内存中的蓝图方块统计所需材料，<b>不依赖现实世界的区块加载状态</b>
     * （markAllBlockRequirements 会因锚点区块未加载而跳过方块，导致无法正常获取资源）。
     */
    /** 从当前蓝图解析材料清单（独立模式）。
     *  Create 的蓝图在“已部署”状态下会尝试从世界上锚点位置的部署方块读取；
     *  独立装填器没有加农炮/锚点，故在文件模式失败后强制按“未部署”重读内存文件。 */
    @Nullable
    private MaterialChecklist computeChecklist(final ItemStack blueprint) {
        if (getLevel() == null || blueprint.isEmpty()) {
            return null;
        }
        // 蓝图自带全量需求数据（打印完成时刻录）→ 免解析直接返回，不依赖世界锚点/区块加载
        final MaterialChecklist embedded = computeChecklistFromComponents(blueprint);
        if (embedded != null && !embedded.required.isEmpty()) {
            return embedded;
        }
        // 直接解析（若蓝图带已部署标记则走世界读取，可能失败/返回空清单）
        final MaterialChecklist direct = computeChecklistInternal(blueprint, false);
        if (direct != null && !direct.required.isEmpty()) {
            return direct;
        }
        // 强制补全已部署标记（针对用过的蓝图被重置部署状态的情况）——通常也依赖世界锚点，可能失败
        final MaterialChecklist forced = computeChecklistInternal(blueprint, true);
        if (forced != null && !forced.required.isEmpty()) {
            return forced;
        }
        // 独立模式最终兜底：无论原蓝图的部署标记如何，一律按“从文件/内存读取”统计，
        // 不依赖现实世界的部署方块，保证无加农炮也能算出需求。
        return computeChecklistFromFile(blueprint);
    }

    /**
     * 最后兜底：无论蓝图原部署状态如何，都把锚点改指本装填器后再用 SchematicPrinter 从<b>文件</b>读取统计。
     * <p>Create 的 SchematicPrinter.loadSchematic 要求蓝图带 SCHEMATIC_ANCHOR + SCHEMATIC_DEPLOYED
     * 才会真正加载文件；若沿用原部署锚点，加载时会依赖现实世界里部署区块（可能未加载/已拆除）而失败。
     * 这里把锚点改到本装填器（方块所在区块必然已加载），仅用于在虚拟内存里放置蓝图统计，不触碰现实方块。 */
    @Nullable
    private MaterialChecklist computeChecklistFromFile(final ItemStack blueprint) {
        final Level level = getLevel();
        if (level == null || blueprint.isEmpty()) {
            return null;
        }
        final BlockPos[] anchorCandidates = {worldPosition, BlockPos.ZERO};
        for (final BlockPos anchor : anchorCandidates) {
            final ItemStack load = blueprint.copy();
            load.set(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, true);
            load.set(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR, anchor);
            final MaterialChecklist checklist = computeChecklistInternal0(load);
            // 解析成功但没有任何材料需求（如蓝图文件缺失/内存已清空）视为不可用，绝不当作“空蓝图完成一轮”
            if (checklist != null && !checklist.required.isEmpty()) {
                return checklist;
            }
        }
        return null;
    }

    @Nullable
    private MaterialChecklist computeChecklistInternal(final ItemStack blueprint, final boolean forceDeployed) {
        final Level level = getLevel();
        if (level == null || blueprint.isEmpty()) {
            return null;
        }
        ItemStack load = blueprint;
        if (forceDeployed) {
            load = blueprint.copy();
            if (!load.getOrDefault(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, false)) {
                load.set(com.simibubi.create.AllDataComponents.SCHEMATIC_DEPLOYED, true);
            }
            if (!load.has(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR)) {
                load.set(com.simibubi.create.AllDataComponents.SCHEMATIC_ANCHOR, BlockPos.ZERO);
            }
        }
        return computeChecklistInternal0(load);
    }

    /** 用 SchematicPrinter 加载蓝图后，直接遍历其内存方块统计需求材料（不依赖现实世界区块）。 */
    @Nullable
    private MaterialChecklist computeChecklistInternal0(final ItemStack load) {
        final Level level = getLevel();
        if (level == null || load.isEmpty()) {
            return null;
        }
        final com.simibubi.create.content.schematics.SchematicPrinter printer =
            new com.simibubi.create.content.schematics.SchematicPrinter();
        printer.loadSchematic(load, level, true);
        if (!printer.isLoaded() || printer.isErrored()) {
            return null;
        }
        final MaterialChecklist checklist = new MaterialChecklist();
        // 反射读取打印机的内存方块读取器，直接按蓝图内容统计所需材料（不依赖现实区块加载）。
        try {
            final java.lang.reflect.Field readerField =
                com.simibubi.create.content.schematics.SchematicPrinter.class.getDeclaredField("blockReader");
            readerField.setAccessible(true);
            final net.createmod.catnip.levelWrappers.SchematicLevel reader =
                (net.createmod.catnip.levelWrappers.SchematicLevel) readerField.get(printer);
            for (final net.minecraft.core.BlockPos pos : reader.getAllPositions()) {
                try {
                    final net.minecraft.world.level.block.state.BlockState state = reader.getBlockState(pos);
                    final net.minecraft.world.level.block.entity.BlockEntity blockEntity = reader.getBlockEntity(pos);
                    final com.simibubi.create.content.schematics.requirement.ItemRequirement requirement =
                        com.simibubi.create.content.schematics.requirement.ItemRequirement.of(state, blockEntity);
                    if (requirement.isEmpty() || requirement.isInvalid()) {
                        continue;
                    }
                    checklist.require(requirement);
                } catch (final Exception ignored) {
                    // 单个方块解析失败：跳过该方块，不中断整体统计
                }
            }
        } catch (final Exception e) {
            return null; // 无法读取内存方块列表：视为不可解析
        }
        try {
            printer.markAllEntityRequirements(checklist);
        } catch (final Exception ignored) {
            // 实体统计失败不影响已统计的方块
        }
        // 缓存需求快照（打印完成后 cannon.checklist 会被 Create 重置，横幅需以此显示每种材料数量）
        final java.util.Map<Item, Integer> snapshot = new java.util.HashMap<>();
        for (final Object2IntMap.Entry<Item> entry : checklist.required.object2IntEntrySet()) {
            snapshot.put(entry.getKey(), entry.getIntValue());
        }
        this.cachedRequirements = snapshot;
        return checklist;
    }

    private static final String LOADER_MATERIALS_KEY = "rs_create_compat:loader_materials";

    /** 把当前（全量）需求快照刻录到蓝图物品组件里。
     *  <p>Create 的已部署蓝图离开打印现场后往往无法再解析出材料（锚点世界数据读不到），
     *  打印完成瞬间把全量需求写进物品本体，之后无论独立装填器还是横幅都能免解析拿到准确数量。 */
    private void stampMaterialCounts(final ItemStack stack) {
        if (stack.isEmpty() || cachedRequirements.isEmpty()) {
            return;
        }
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (final java.util.Map.Entry<Item, Integer> entry : cachedRequirements.entrySet()) {
            final net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getKey());
            if (id == null) {
                continue;
            }
            final CompoundTag comp = new CompoundTag();
            comp.putString("i", id.toString());
            comp.putInt("n", entry.getValue());
            list.add(comp);
        }
        if (list.isEmpty()) {
            return;
        }
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> tag.put(LOADER_MATERIALS_KEY, list)));
    }

    /** 读取蓝图物品上刻录的全量需求（打印完成时写入）；没有则返回 null。 */
    @Nullable
    private static java.util.Map<Item, Integer> readMaterialCounts(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final net.minecraft.world.item.component.CustomData data =
            stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(LOADER_MATERIALS_KEY)) {
            return null;
        }
        final net.minecraft.nbt.Tag raw = data.copyTag().get(LOADER_MATERIALS_KEY);
        if (!(raw instanceof net.minecraft.nbt.ListTag list)) {
            return null;
        }
        final java.util.Map<Item, Integer> map = new java.util.HashMap<>();
        for (final net.minecraft.nbt.Tag tag : list) {
            if (!(tag instanceof CompoundTag comp) || !comp.contains("i") || !comp.contains("n")) {
                continue;
            }
            final Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(comp.getString("i")));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                map.put(item, comp.getInt("n"));
            }
        }
        return map.isEmpty() ? null : map;
    }

    /** 蓝图物品自带全量需求数据时，直接据此构造清单（免解析、不受锚点/世界加载影响）。 */
    @Nullable
    private static MaterialChecklist computeChecklistFromComponents(final ItemStack blueprint) {
        final java.util.Map<Item, Integer> counts = readMaterialCounts(blueprint);
        if (counts == null) {
            return null;
        }
        final MaterialChecklist checklist = new MaterialChecklist();
        for (final java.util.Map.Entry<Item, Integer> entry : counts.entrySet()) {
            checklist.required.put(entry.getKey(), entry.getValue());
        }
        return checklist;
    }

    /** 从网络拉取指定物品（单种材料独立受速度升级限制，避免多材料互相挤占导致"只拿到几个"；
     *  本次只拉取一次上限额度，剩余的等下一 work tick；不足且装自动合成升级时请求自动合成）。
     *  <p><b>安全语义</b>：先 SIMULATE 集群内存能收下多少 → 只从网络抽这个量 → 插入；
     *  万一插不下（模拟后状态变化）先退回网络、退不回则落回世界，<b>绝不销毁</b>；
     *  集群内存（缓存）已满时<b>一个也不抽</b>。</p> */
    private void fetchFromNetwork(final Item item, final long toFetch,
                                  final StorageNetworkComponent storage,
                                  final AutocraftingNetworkComponent autocrafting) {
        final long perMaterialLimit = getMaterialPullLimit();
        if (perMaterialLimit <= 0) {
            return;
        }
        final ResourceKey resource = new ItemResource(item, DataComponentPatch.EMPTY);
        final long inNetwork = storage.get(resource);
        if (inNetwork >= toFetch) {
            final long canPull = Math.min(toFetch, perMaterialLimit);
            final ItemStack probe = new ItemResource(item, DataComponentPatch.EMPTY).toItemStack((int) canPull);
            final int acceptable = probe.getCount() - simulateInsertIntoCluster(probe).getCount();
            if (acceptable <= 0) {
                logState("cluster-full:" + item,
                    "cluster storage is full -> skip fetching " + item + " (nothing was extracted)");
                return; // 缓存满：不抽（后续 tick 集群腾出空间再拉）
            }
            final long extracted = storage.extract(resource, acceptable, Action.EXECUTE, Actor.EMPTY);
            if (extracted > 0) {
                final ItemStack fetched = new ItemResource(item, DataComponentPatch.EMPTY).toItemStack(extracted);
                final ItemStack remainder = insertIntoCluster(fetched);
                if (!remainder.isEmpty()) {
                    // 理论不可达（前面已按容量模拟过）：先退回网络；网络也满则落回世界，绝不销毁
                    final long returned = storage.insert(ItemResource.ofItemStack(remainder),
                        remainder.getCount(), Action.EXECUTE, Actor.EMPTY);
                    if (returned < remainder.getCount()) {
                        dropIntoWorld(remainder.copyWithCount((int) (remainder.getCount() - returned)));
                    }
                }
            }
        } else if (hasAutocraftingUpgrade() && !autocrafting.getPatternsByOutput(resource).isEmpty()) {
            // ensureTask 的语义是「保证至少有 N 个正在合成」：同一轮里额度不再变大时只报一次，
            // 避免每 tick 重复请求 → 合成任务被反复追加 → 终端里「需要量」一路增长（用户报告的现象）。
            // 报一次被拒（MISSING_RESOURCES：原料不足）不算报过，下个 tick 再试（原料可能刚到位）。
            final long request = toFetch - inNetwork;
            if (request > 0 && request > autocraftRequested.getOrDefault(item, 0L)) {
                // ensureTask 必须携带非空取消令牌，否则 RS 内部会直接 NPE（见崩溃报告 CraftingTree.calculate）
                final AutocraftingNetworkComponent.EnsureResult result = autocrafting.ensureTask(
                    resource, request, Actor.EMPTY,
                    new com.refinedmods.refinedstorage.api.network.impl.autocrafting.TimeoutableCancellationToken());
                if (result != AutocraftingNetworkComponent.EnsureResult.MISSING_RESOURCES) {
                    autocraftRequested.put(item, request);
                }
            }
        }
    }

    protected SchematicannonBlockEntity findAttachedCannon(final Level level) {
        for (final Direction direction : Direction.values()) {
            final BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
            if (blockEntity instanceof SchematicannonBlockEntity cannon) {
                return cannon;
            }
        }
        return null;
    }

    /** 本装填器是否与 cannon 六向紧贴（决定谁真正给 cannon 补料）。 */
    protected boolean isCannonAdjacent(final SchematicannonBlockEntity cannon) {
        final Level level = getLevel();
        if (level == null) {
            return false;
        }
        return level.getBlockEntity(worldPosition) == cannon
            || cannon.getBlockPos().distManhattan(worldPosition) == 1;
    }

    /**
     * 本机的资源内存是否与「紧贴 cannon 的那台集群成员」是<b>同一个实例</b>。
     * <p><b>为什么需要</b>：集群共享内存后（{@link #rscc$clusterAdopt}）必须为 true —— 此时
     * 「本机库存」与「加农炮能取到的那份库存」是同一个对象，任何「把本机库存回流网络」的动作
     * 都会把加农炮要用的料推回网络（见 {@link #doLoaderWork} 的集群镜像分支）。
     * <p>返回 false 只可能出现在「集群拓扑刚变、主控还没把共享内存发给本机」的瞬态，
     * 那时本机拿着的是自己的空内存，回流它是安全的。
     */
    private boolean sharesMemoryWithCannonAdjacentMember(final SchematicannonBlockEntity cannon) {
        for (final SchematicLoaderBlockEntity member : collectCluster()) {
            if (member.isCannonAdjacent(cannon) && member.inventory == this.inventory) {
                return true;
            }
        }
        return false;
    }

    /**
     * 寻找本装填器服务的蓝图加农炮：优先自身六向紧贴的 cannon；
     * 否则在整个同网络集群（collectCluster）内找一个成员紧贴的 cannon（紧贴装填器共享给它）。
     * 返回 null 表示纯独立模式。
     */
    protected SchematicannonBlockEntity findClusterCannon(final Level level) {
        final SchematicannonBlockEntity direct = findAttachedCannon(level);
        if (direct != null) {
            return direct;
        }
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader == this) {
                continue;
            }
            final SchematicannonBlockEntity cannon = loader.findAttachedCannon(level);
            if (cannon != null) {
                return cannon;
            }
        }
        return null;
    }

    /** 供菜单构造共享蓝图槽：返回紧贴的加农炮（无则 null）。 */
    public SchematicannonBlockEntity getAttachedCannon() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        return findAttachedCannon(level);
    }

    /** 把 cannon 打印中实时刷新的 checklist 拷贝成普通 Map（仅当全量快照暂缺时兜底使用）。 */
    private static java.util.Map<Item, Integer> copyChecklistToMap(final MaterialChecklist checklist) {
        final java.util.Map<Item, Integer> map = new java.util.HashMap<>();
        for (final Object2IntMap.Entry<Item> entry : checklist.required.object2IntEntrySet()) {
            map.put(entry.getKey(), entry.getIntValue());
        }
        return map;
    }

    /** 按需求清单给集群库存补足/回收多余（对照“集群内存实际库存数量”，而非 Create 的 gathered 记账）。
     *  补料期间 Create 会从紧贴装填器持续取走材料，本方法每个 open tick 都会把库存重新补回全量，
     *  保证 cannon 总能取到料、且打印完成时库存仍保留整套材料。 */
    private void restockByNeeds(final java.util.Map<Item, Integer> needs,
                                final StorageNetworkComponent storage,
                                final AutocraftingNetworkComponent autocrafting) {
        for (final java.util.Map.Entry<Item, Integer> entry : needs.entrySet()) {
            final Item item = entry.getKey();
            final int needed = entry.getValue();
            final int have = countItemInCluster(item);
            final int diff = needed - have;
            if (diff > 0) {
                fetchFromNetwork(item, diff, storage, autocrafting);
                // 网络无货且不可自动合成时按“物品”记录一条（10s 心跳），避免每 tick 刷屏
                final ResourceKey resource = new ItemResource(item, DataComponentPatch.EMPTY);
                if (storage.get(resource) <= 0
                    && !(hasAutocraftingUpgrade() && !autocrafting.getPatternsByOutput(resource).isEmpty())) {
                    logState("wait:" + item, "waiting material " + item + " have=" + have + " need=" + needed
                        + " (network has none & no autocraft)");
                }
            } else if (diff < 0) {
                returnExcessToNetwork(item, -diff, storage);
            }
        }
    }

    /**
     * 把集群库存中某物品的指定多余数量退回网络。
     * <p><b>安全语义</b>：先由 {@link SafeCollect#pushItemsToNetwork} 算出网络能收下多少，
     * 只抽「能收下的量」；执行量不足时差额回滚回原槽 —— 网络（缓存）满时本方法<b>一个物品也不动</b>。</p>
     */
    private void returnExcessToNetwork(final Item item, int amount,
                                       final StorageNetworkComponent storage) {
        for (final SchematicLoaderBlockEntity loader : getClusterInGuiOrder()) {
            for (int i = 0; i < loader.inventory.getSlots() && amount > 0; i++) {
                final ItemStack inSlot = loader.inventory.getStackInSlot(i);
                if (inSlot.isEmpty() || !inSlot.is(item)) {
                    continue;
                }
                final int pushed = SafeCollect.pushItemsToNetwork(storage, loader.inventory, i,
                    Math.min(amount, inSlot.getCount()), loader::dropIntoWorld);
                if (pushed <= 0) {
                    return; // 网络已满：立即停手（继续循环只会反复空转）
                }
                amount -= pushed;
            }
        }
    }

    protected void restockFromNetwork(final SchematicannonBlockEntity cannon, final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final AutocraftingNetworkComponent autocrafting = network.getComponent(AutocraftingNetworkComponent.class);

        // 没有活动蓝图（加农炮蓝图槽为空且没有下一张蓝图）：库存中的物品全部回流网络
        if (cannon.inventory.getStackInSlot(0).isEmpty() && getNextBlueprint().isEmpty()) {
            logState("restock", "no blueprint anywhere -> recycling inventory to network");
            recycleBlueprintMaterialsToNetwork(network);
            return;
        }
        // 需求口径：全量快照（蓝图文件/打印机内存，如 4 个草方块）优先；
        // 快照尚未取得时退回 cannon.checklist（会被 shouldPlace 过滤偏小，只是临时兜底）
        final java.util.Map<Item, Integer> needs = cachedRequirements.isEmpty()
            ? copyChecklistToMap(cannon.checklist)
            : cachedRequirements;
        if (needs.isEmpty()) {
            logState("restock", "material needs unavailable yet for "
                + schematicFileName(cannon.inventory.getStackInSlot(0)));
            return;
        }
        restockByNeeds(needs, storage, autocrafting);
    }

    /**
     * 把物品送回网络（如打印完成的空白蓝图）。
     * <p><b>安全语义</b>：先 SIMULATE 出网络可接纳量，只写这个量；网络满时<b>一个也不写</b>，
     * 由调用方决定剩下的物品去哪（绝不先删后写）。</p>
     *
     * @return 网络实际收下的数量
     */
    private long returnToNetwork(final Network network, final ItemStack stack) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null || stack.isEmpty()) {
            return 0L;
        }
        final ItemResource resource = new ItemResource(stack.getItem(), stack.getComponentsPatch());
        final long acceptable = SafeCollect.networkAcceptable(storage, resource, stack.getCount());
        if (acceptable <= 0L) {
            return 0L; // 网络（存储）已满：不写、不清槽
        }
        return storage.insert(resource, (int) acceptable, Action.EXECUTE, Actor.EMPTY);
    }

    /**
     * 打印完成 / 加农炮无蓝图：库存中的物品全部回流网络。
     * <p><b>安全语义</b>：逐格「先算网络容量 → 只抽能收下的量 → 差额回滚回原格」；
     * 网络满时物品原样留在装填器库存里，绝不销毁。</p>
     */
    private void recycleBlueprintMaterialsToNetwork(final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        for (int i = 0; i < inventory.getSlots(); i++) {
            final ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            final int pushed = SafeCollect.pushItemsToNetwork(storage, inventory, i,
                stack.getCount(), this::dropIntoWorld);
            if (pushed <= 0) {
                return; // 网络已满：停手，剩余物品继续留在本机库存
            }
        }
    }

    /** 打印完成横幅：发给附近玩家（第一行完成收集文件名，第二行全部收集完毕，随后列出每种资源数量）。 */
    protected void sendCompletionBanner(final SchematicannonBlockEntity cannon) {
        final Level level = getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        final ItemStack blueprintStack = cannon.inventory.getStackInSlot(0);
        // 打印完成瞬间 Create 已清空 cannon 蓝图槽，因此用装填器缓存的"刚打印蓝图"文件名；
        // 缓存为空（如打印被玩家手动中断后直接拆除）时回退到当前槽/未知蓝图。
        final net.minecraft.network.chat.Component blueprintName;
        if (!activeBlueprintFileName.isBlank()) {
            blueprintName = net.minecraft.network.chat.Component.literal(activeBlueprintFileName);
        } else if (!blueprintStack.isEmpty()) {
            final String file = blueprintStack.get(com.simibubi.create.AllDataComponents.SCHEMATIC_FILE);
            blueprintName = file != null && !file.isBlank()
                ? net.minecraft.network.chat.Component.literal(file)
                : blueprintStack.getHoverName();
        } else {
            blueprintName = net.minecraft.network.chat.Component.translatable(
                "block.rs_create_compat.schematic_loader.unknown_blueprint");
        }
        // 打印已完成 => 材料必然齐过。数量一律取“蓝图文件统计”的需求快照；
        // 绝不回退到 cannon.checklist —— Create 完成后会把它复位成残留数值（gathered 清零、required 残留 1），
        // 直接导致“明明 4 个草方块却显示收集 1 个 / 提示资源不够”。
        final net.minecraft.network.chat.Component firstLine = net.minecraft.network.chat.Component.translatable(
            "block.rs_create_compat.schematic_loader.done", blueprintName);
        // 第二行必须基于<b>真实数据</b>，不能无条件说"全部收集完毕"：
        //   * 需求快照为空（蓝图解析不出材料清单）→ 如实说明「不需要材料 / 未能解析出方块」；
        //   * 本轮从未真正集齐（{@link #isRoundCollectedForCannon} 读的是收尾时落下的真值锁存，
        //     而收尾判据本身基于集群共享内存里的真实数量）→ 如实报缺料。
        // 旧实现两处都无条件报成功，正是用户报告的「什么都没收集到却提示成功收集」。
        final net.minecraft.network.chat.Component secondLine;
        if (cachedRequirements.isEmpty()) {
            secondLine = net.minecraft.network.chat.Component.translatable(
                "block.rs_create_compat.schematic_loader.no_materials");
        } else if (!isRoundCollectedForCannon(cannon)) {
            secondLine = net.minecraft.network.chat.Component.translatable(
                "block.rs_create_compat.schematic_loader.missing");
        } else {
            secondLine = net.minecraft.network.chat.Component.translatable(
                "block.rs_create_compat.schematic_loader.all_collected");
        }
        final java.util.List<net.minecraft.network.chat.Component> resourceLines = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<Item, Integer> entry : cachedRequirements.entrySet()) {
            resourceLines.add(net.minecraft.network.chat.Component.empty().append("  ")
                .append(entry.getKey().getDescription())
                .append(" × " + entry.getValue()));
        }
        for (final net.minecraft.world.entity.player.Player player : level.players()) {
            if (player.distanceToSqr(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 4096.0) {
                player.displayClientMessage(firstLine, false);
                player.displayClientMessage(secondLine, false);
                for (final net.minecraft.network.chat.Component line : resourceLines) {
                    player.displayClientMessage(line, false);
                }
            }
        }
        // 同一完成事件再弹一条仿 RS 样式的横幅（带物品图标），与聊天互补
        final java.util.List<cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload.Row> bannerRows =
            new java.util.ArrayList<>();
        bannerRows.add(cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload.Row.text(
            0xFFFFA500, firstLine.getString()));
        final java.util.List<net.minecraft.world.item.ItemStack> resIcons = new java.util.ArrayList<>();
        final java.util.List<String> resTexts = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<Item, Integer> entry : cachedRequirements.entrySet()) {
            resIcons.add(new net.minecraft.world.item.ItemStack(entry.getKey(), 1));
            resTexts.add(entry.getKey().getDescription().getString() + " ×" + entry.getValue());
        }
        if (!resIcons.isEmpty()) {
            bannerRows.add(cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload.Row.segments(
                0xFFFFFFFF, resIcons, resTexts));
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            cretae.cookiewyq.rs_create_compat.report.CompatCompletionSender.sendToNearby(
                serverLevel, worldPosition, 4096, bannerRows);
        }
    }

    /**
     * Create 的打印这一轮是否真的走完了「放置方块」这条路（而不是蓝图加载失败 / 蓝图里没有方块）。
     * <p><b>为什么看 statusMsg</b>：{@code SchematicannonBlockEntity} 只有三处往输出槽放空白蓝图 ——
     * {@code finishedPrinting()}（{@code statusMsg = "finished"}）、{@code initializePrinter()} 的
     * {@code schematicErrored}（把蓝图放进内存世界时抛异常）与 {@code schematicExpired}
     * （{@code printer.isWorldEmpty()}，蓝图里一个方块都没有）。后两者清空蓝图槽、输出槽 +1、
     * 一个方块都没放 —— 对玩家就是"点了打印什么都没打"，绝不能当成完成。
     * <p>{@code schematicInvalid} / {@code schematicNotPlaced} 不经过输出槽，这里一并排除，
     * 保证任何"没有方块被放置"的收尾都不会被误判成成功。
     */
    private static boolean cannonPrintPlacedAnyBlock(final SchematicannonBlockEntity cannon) {
        final String status = cannon.statusMsg;
        return !("schematicErrored".equals(status) || "schematicExpired".equals(status)
            || "schematicInvalid".equals(status) || "schematicNotPlaced".equals(status));
    }

    /** 「加农炮这一轮结束但一个方块都没放」的可见失败：日志（限频）+ 附近玩家行动栏提示。 */
    private void notifyPrintAborted(final SchematicannonBlockEntity cannon) {
        final String name = activeBlueprintFileName.isBlank()
            ? net.minecraft.network.chat.Component.translatable(
                "block.rs_create_compat.schematic_loader.unknown_blueprint").getString()
            : activeBlueprintFileName;
        logState("print:aborted", "cannon ended without placing any block (status="
            + cannon.statusMsg + ") for " + name);
        notifyNearby(net.minecraft.network.chat.Component.translatable(
            "block.rs_create_compat.schematic_loader.not_printed",
            net.minecraft.network.chat.Component.literal(name)), true);
    }

    /**
     * 给装填器附近的玩家发一条消息。
     * <p>{@code overlay = true} 走行动栏（不铺满聊天栏，适合「需要知道但不必留档」的失败提示）。
     * 距离口径与完成横幅一致（4096 = 64 格平方）：看得到机器的人才收得到提示。
     */
    protected void notifyNearby(final net.minecraft.network.chat.Component message, final boolean overlay) {
        final Level lvl = getLevel();
        if (lvl == null || lvl.getServer() == null) {
            return;
        }
        for (final net.minecraft.world.entity.player.Player player : lvl.players()) {
            if (player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 4096.0) {
                player.displayClientMessage(message, overlay);
            }
        }
    }

    /**
     * 「蓝图在位却解析不出任何材料需求」的玩家提示（限频 10 秒，行动栏）。
     * <p><b>为什么要提示</b>：这一轮永远不会去收集、也永远不会打印，只留一条日志玩家是看不到的
     * —— 沉默正是用户报告的「什么都没发生」。
     */
    private void warnParseFailure(final String blueprintKey) {
        final Level lvl = getLevel();
        final long now = lvl == null ? 0L : lvl.getGameTime();
        if (now - lastParseWarnTick < LOG_HEARTBEAT) {
            return;
        }
        lastParseWarnTick = now;
        notifyNearby(net.minecraft.network.chat.Component.translatable(
            "block.rs_create_compat.schematic_loader.no_materials"), true);
    }

    /** 直接从加农炮<b>已加载的内存读取器</b>（SchematicLevel）统计蓝图<b>全量</b>需求。
     *  <p>Create 的 updateChecklist 会用 shouldPlace 过滤（跳过“已有方块/炮旁 2 格”等不需要放置的
     *  位置），导致 required 少于蓝图真实总量（如 1_2 有 4 个草方块却只报 3）。
     *  这里绕开过滤直接遍历整张蓝图，得到稳定的全量需求 —— 打印是否过半都不影响结果。 */
    @Nullable
    private MaterialChecklist snapshotFromCannonPrinter(final SchematicannonBlockEntity cannon) {
        final com.simibubi.create.content.schematics.SchematicPrinter printer = cannon.printer;
        if (printer == null) {
            logState("printer", "cannon printer is null, full snapshot unavailable");
            return null;
        }
        if (!printer.isLoaded()) {
            logState("printer", "cannon printer not loaded yet, full snapshot unavailable");
            return null;
        }
        if (printer.isErrored()) {
            logState("printer", "cannon printer errored, full snapshot unavailable");
            return null;
        }
        final MaterialChecklist checklist = new MaterialChecklist();
        try {
            final java.lang.reflect.Field readerField =
                com.simibubi.create.content.schematics.SchematicPrinter.class.getDeclaredField("blockReader");
            readerField.setAccessible(true);
            final net.createmod.catnip.levelWrappers.SchematicLevel reader =
                (net.createmod.catnip.levelWrappers.SchematicLevel) readerField.get(printer);
            for (final net.minecraft.core.BlockPos pos : reader.getAllPositions()) {
                try {
                    final net.minecraft.world.level.block.state.BlockState state = reader.getBlockState(pos);
                    final net.minecraft.world.level.block.entity.BlockEntity blockEntity = reader.getBlockEntity(pos);
                    final com.simibubi.create.content.schematics.requirement.ItemRequirement requirement =
                        com.simibubi.create.content.schematics.requirement.ItemRequirement.of(state, blockEntity);
                    if (requirement.isEmpty() || requirement.isInvalid()) {
                        continue;
                    }
                    checklist.require(requirement);
                } catch (final Exception ignored) {
                    // 单个方块解析失败：跳过该方块，不中断整体统计
                }
            }
            printer.markAllEntityRequirements(checklist);
        } catch (final Exception e) {
            logState("printer", "full snapshot scan threw " + e);
            return null;
        }
        return checklist;
    }

    /** 把成功解析的清单写入需求快照缓存。 */
    private void applyCachedSnapshot(final MaterialChecklist checklist, final String key, final boolean full) {
        final java.util.Map<Item, Integer> map = new java.util.HashMap<>();
        for (final Object2IntMap.Entry<Item> entry : checklist.required.object2IntEntrySet()) {
            map.put(entry.getKey(), entry.getIntValue());
        }
        if (map.isEmpty()) {
            return;
        }
        this.cachedRequirements = map;
        this.cachedRequirementsBlueprintKey = key;
        this.cachedRequirementsFull = full;
    }

    /** 按加农炮“当前蓝图”抓拍完整需求快照（蓝图不变不重复解析）。
     *  <p>优先级：
     *  1) 直接扫 cannon 已加载内存读取器得到<b>全量</b>需求（已部署蓝图，不受 shouldPlace/进度影响）；
     *  2) 从蓝图文件独立统计（未部署蓝图 / 独立模式可成功）；
     *  3) 最后才回退 cannon.checklist（会被 Create 过滤/进度影响，仅兜底）。
     *  <p>回退值不视为“全量”：之后每个 tick 都会尝试用 1)/2) 升级为全量，
     *  保证 4 个草方块的蓝图不会永远停留在 cannon 过滤出的 3 个。
     *  一旦完成打印，Create 会复位 printer/checklist —— 本方法只在该状态出现前写入，之后保留上一份快照。 */
    private void cacheRequirementsFromBlueprint(final SchematicannonBlockEntity cannon) {
        ItemStack blueprint = cannon.inventory.getStackInSlot(0);
        if (blueprint.isEmpty()) {
            blueprint = getNextBlueprint();
        }
        if (blueprint.isEmpty()) {
            return; // 当前无蓝图：保留上一张的快照，供刚完成的打印横幅使用
        }
        final String key = schematicFileName(blueprint);
        if (key.isEmpty()) {
            return;
        }
        // 蓝图已变化：清除上一张的快照，重新解析
        if (!key.equals(cachedRequirementsBlueprintKey)) {
            cachedRequirements = java.util.Map.of();
            cachedRequirementsBlueprintKey = key;
            cachedRequirementsFull = false;
        }
        if (cachedRequirementsFull && !cachedRequirements.isEmpty()) {
            return; // 已拿到全量需求
        }
        // 0) 蓝图物品自带全量需求数据（打印完成时刻录）→ 免解析直接采用
        final MaterialChecklist embedded = computeChecklistFromComponents(blueprint);
        if (embedded != null && !embedded.required.isEmpty()) {
            applyCachedSnapshot(embedded, key, true);
            logState("cache", "cached FULL requirements from item data for " + key + " -> "
                + cachedRequirements.size() + " materials");
            return;
        }
        // 1) 已部署蓝图的全量需求：扫 cannon 已加载的内存读取器（不受 shouldPlace 过滤）
        final MaterialChecklist full = snapshotFromCannonPrinter(cannon);
        if (full != null && !full.required.isEmpty()) {
            applyCachedSnapshot(full, key, true);
            logState("cache", "cached FULL requirements from cannon printer for " + key + " -> "
                + cachedRequirements.size() + " materials");
            return;
        }
        // 2) 从蓝图文件独立统计（未部署蓝图可成功；空清单按失败处理）。
        //    处于"兜底值"时也继续尝试：一旦世界锚点区块加载/条件满足就能升级为全量
        final MaterialChecklist fromFile = computeChecklist(blueprint);
        if (fromFile != null && !fromFile.required.isEmpty()) {
            applyCachedSnapshot(fromFile, key, true);
            logState("cache", "cached requirements from file for " + key + " -> "
                + cachedRequirements.size() + " materials");
            return;
        }
        // 3) 兜底：拷贝 cannon 打印中刷出的 checklist（可能被过滤/进度影响，仅当上面都失败）。
        //    已存在兜底值则保持不变（map 已有内容），等后续 tick 由 1)/2) 升级为全量
        if (!cachedRequirements.isEmpty()) {
            return;
        }
        final MaterialChecklist fallback = new MaterialChecklist();
        for (final Object2IntMap.Entry<Item> entry : cannon.checklist.required.object2IntEntrySet()) {
            fallback.required.put(entry.getKey(), entry.getIntValue());
        }
        if (fallback.required.isEmpty()) {
            logState("cache", "blueprint requirements unavailable for " + key
                + " (printer not loaded & file parse failed)");
            // 失败不得静默：蓝图在位却拿不到任何需求 = 这一轮永远不会收集、也永远不会打印，
            // 只有日志玩家看不到（限频 10s，行动栏提示，不刷聊天栏）。
            warnParseFailure(key);
            return;
        }
        applyCachedSnapshot(fallback, key, false);
        logState("cache", "cached requirements from cannon checklist for " + key + " -> "
            + cachedRequirements.size() + " materials (degraded, will retry full)");
    }

    /** 独立模式一轮结束后的报告（始终打印蓝图所需资源清单；缺料时逐项列出缺失数量，
     *  只有全部集齐才显示"全部资源都已收集完毕"）。 */
    private void sendStandaloneCompletion(final ItemStack blueprint) {
        final Level level = getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        // 需求快照优先（避免每 tick 重新解析蓝图文件）；与 doStandaloneRestock 共用同一缓存，
        // 只有快照属于<b>当前蓝图</b>时才可用，防止紧贴/独立模式切换时沿用上一张蓝图的清单
        final String key = schematicFileName(blueprint);
        final java.util.Map<Item, Integer> needs = standaloneNeeds(key, blueprint);
        if (needs == null) {
            // 拿不到需求清单就不能说"收集完成"：报缺料文案 + 限频提示原因，绝不静默返回
            notifyNearby(net.minecraft.network.chat.Component.translatable(
                "block.rs_create_compat.schematic_loader.no_materials"), false);
            logState("standalone:null:" + key, "completion report skipped: no requirements for " + key);
            return;
        }
        final net.minecraft.network.chat.Component blueprintName = net.minecraft.network.chat.Component.literal(
            schematicFileName(blueprint));
        final net.minecraft.network.chat.Component firstLine = net.minecraft.network.chat.Component.translatable(
            "block.rs_create_compat.schematic_loader.done", blueprintName);
        final java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        // 逐一核对实际落库数量，统计缺失
        final java.util.List<net.minecraft.network.chat.Component> missingLines = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<Item, Integer> entry : needs.entrySet()) {
            final int needed = entry.getValue();
            final int have = countItemInCluster(entry.getKey());
            lines.add(net.minecraft.network.chat.Component.empty().append("  ")
                .append(entry.getKey().getDescription())
                .append(" × " + needed));
            final int missing = needed - have;
            if (missing > 0) {
                missingLines.add(net.minecraft.network.chat.Component.empty().append("  §c缺 ")
                    .append(entry.getKey().getDescription())
                    .append(" × " + missing));
            }
        }
        for (final net.minecraft.world.entity.player.Player player : level.players()) {
            if (player.distanceToSqr(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 4096.0) {
                player.displayClientMessage(firstLine, false);
                player.displayClientMessage(missingLines.isEmpty()
                    ? net.minecraft.network.chat.Component.translatable(
                        "block.rs_create_compat.schematic_loader.all_collected")
                    : net.minecraft.network.chat.Component.translatable(
                        "block.rs_create_compat.schematic_loader.missing"), false);
                for (final net.minecraft.network.chat.Component line : lines) {
                    player.displayClientMessage(line, false);
                }
                for (final net.minecraft.network.chat.Component line : missingLines) {
                    player.displayClientMessage(line, false);
                }
            }
        }
    }

    /** 火药直装蓝图加农炮的火药槽（slot 4），不回流装填器库存。 */
    protected void restockGunpowderDirect(final SchematicannonBlockEntity cannon, final Network network) {
        final ItemStack inCannon = cannon.inventory.getStackInSlot(4);
        final int current = inCannon.isEmpty() ? 0 : inCannon.getCount();
        if (current >= 64) {
            return;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final ResourceKey resource = new ItemResource(Items.GUNPOWDER, DataComponentPatch.EMPTY);
        final long inNetwork = storage.get(resource);
        if (inNetwork <= 0) {
            // 网络中没有火药且加农炮火药槽为空：提醒玩家（限频，每 200 tick 一次）
            final long gameTime = level == null ? 0 : level.getGameTime();
            if (inCannon.isEmpty() && gameTime - lastGunpowderWarnTick >= 200) {
                lastGunpowderWarnTick = gameTime;
                if (level != null && level.getServer() != null) {
                    level.getServer().getPlayerList().getPlayers().forEach(p ->
                        p.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                "block.rs_create_compat.schematic_loader.no_gunpowder"),
                            true));
                }
            }
            return;
        }
        final long toFetch = Math.min(inNetwork, 64 - current);
        final long extracted = storage.extract(resource, toFetch, Action.EXECUTE, Actor.EMPTY);
        if (extracted > 0) {
            final int total = current + (int) extracted;
            cannon.inventory.setStackInSlot(4, new ItemStack(Items.GUNPOWDER, total));
            cannon.sendUpdate = true;
        }
    }

    protected boolean isResourcesReady(final SchematicannonBlockEntity cannon) {
        for (final Object2IntMap.Entry<Item> entry : cannon.checklist.required.object2IntEntrySet()) {
            final int gathered = cannon.checklist.gathered.getInt(entry.getKey());
            if (gathered < entry.getIntValue()) {
                return false;
            }
        }
        return true;
    }

    /** 把物品插入本装填器库存：从槽 0 起顺序填充（同类堆叠 → 空格），返回放不下的剩余。 */
    protected ItemStack insertIntoInventory(final ItemStack stack) {
        return insertIntoInventory(stack, false);
    }

    /** 同 {@link #insertIntoInventory(ItemStack)}，{@code simulate = true} 时只算不放（用于先算容量）。 */
    protected ItemStack insertIntoInventory(final ItemStack stack, final boolean simulate) {
        // 收集 / 补料是「本机」的动作 → 按本机每格上限填充（集群共享内存时也不会借用别人的堆叠升级）
        return insertIntoHandler(inventory, stack, simulate, getStorageSlotCapacity());
    }

    /** 把物品插入指定库存：从槽 0 起顺序填充（同类堆叠 → 空格），返回放不下的剩余。
     *  {@code simulate = true} 时<b>只算不放</b>（「先算能收多少 → 只按实际能收的量抽取」这条安全语义的基础）。 */
    protected static ItemStack insertIntoHandler(final ItemStackHandler target, final ItemStack stack,
                                                 final boolean simulate) {
        return insertIntoHandler(target, stack, simulate, 0);
    }

    /**
     * 同 {@link #insertIntoHandler(ItemStackHandler, ItemStack, boolean)}，但按<b>显式每格上限</b>填充。
     * <p><b>为什么需要显式上限</b>：集群共享的那一份内存由多台共用，处理器自身的
     * {@code getSlotLimit} 只能取一个「最宽」的值（见
     * {@link SchematicLoaderInventory#getSlotLimit}）；而「这一台能收多少」必须按本机自己的堆叠升级算。
     * <p>{@code simulate = true} 与 {@code false} 严格同构（只算不放），这是本工程
     * 「先算能收多少 → 只抽取能收下的量」安全语义的前提。
     *
     * @param perSlotLimit 每格上限；{@code <= 0} 表示沿用处理器自身的每格上限
     */
    protected static ItemStack insertIntoHandler(final ItemStackHandler target, final ItemStack stack,
                                                 final boolean simulate, final int perSlotLimit) {
        ItemStack remainder = stack;
        for (int i = 0; i < target.getSlots() && !remainder.isEmpty(); i++) {
            remainder = insertIntoSlot(target, i, remainder, simulate, perSlotLimit);
        }
        return remainder;
    }

    /** 按显式上限把物品插入单格（语义与 {@code ItemStackHandler#insertItem} 一致），返回放不下的剩余。 */
    private static ItemStack insertIntoSlot(final ItemStackHandler target, final int slot,
                                            final ItemStack stack, final boolean simulate,
                                            final int perSlotLimit) {
        if (stack.isEmpty() || !target.isItemValid(slot, stack)) {
            return stack;
        }
        final ItemStack existing = target.getStackInSlot(slot);
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) {
            return stack;
        }
        final int held = existing.isEmpty() ? 0 : existing.getCount();
        final int room = slotLimitFor(target, slot, stack, perSlotLimit) - held;
        if (room <= 0) {
            return stack;
        }
        final int moved = Math.min(room, stack.getCount());
        if (!simulate) {
            target.setStackInSlot(slot, existing.isEmpty()
                ? stack.copyWithCount(moved)
                : existing.copyWithCount(held + moved));
        }
        return moved >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    /**
     * 单格对「这件物品」的每格上限。
     * <ul>
     *     <li>{@code perSlotLimit <= 0}：沿用处理器自身的上限（{@link ItemStackHandler} 语义：
     *     再取物品自身一叠大小的较小值）；</li>
     *     <li>{@code perSlotLimit > 0}：直接采用该上限（=「基础值 ×(1 + 堆叠升级数)」这一档），
     *     但<b>不可堆叠物品恒为 1</b> —— 堆叠升级只放大「能叠的东西」，
     *     绝不把床 / 船 / 工具这类塞成一格好几个。</li>
     * </ul>
     */
    private static int slotLimitFor(final ItemStackHandler target, final int slot,
                                    final ItemStack stack, final int perSlotLimit) {
        if (perSlotLimit <= 0) {
            return Math.min(target.getSlotLimit(slot), stack.getMaxStackSize());
        }
        return stack.getMaxStackSize() <= 1 ? 1 : perSlotLimit;
    }

    /** 集群内存槽在 GUI 与补料时使用的统一顺序：紧贴 cannon 的装填器排最前
     *  （cannon 只能从紧贴容器取料），其余成员保持 collectCluster 的 Y 降序。
     *  <p>GUI 集群槽与 {@link #insertIntoCluster} 都按此顺序排列，保证新拉取的料落在
     *  滚动区顶部（默认视野内），不会“滚到底部那一格库存才能看到料”。
     *  <p><b>已按「共享内存的身份」去重</b>：同一个整体只有一份存储实例，重复列出会把同一份内存
     *  在界面上显示两遍、在计数时算两遍（甚至让同一格被映射两次），因此同族合并后只保留 GUI 顺序最前的
     *  那一台（见 {@link #getClusterInventoryView()} 的说明）。
     */
    public java.util.List<SchematicLoaderBlockEntity> getClusterInGuiOrder() {
        final Level lvl = getLevel();
        final long now = lvl == null ? Long.MIN_VALUE : lvl.getGameTime();
        if (guiOrderCache == null || now != guiOrderCacheTick) {
            // 同 tick 复用：菜单一帧 / 一次广播会按槽位反复解析，避免每个槽位都重算一次集群拓扑。
            // 缓存只存「成员」不含容器引用 —— 各台<b>当前</b>的库存字段仍然每次现取，集群重建后不会指向旧对象。
            guiOrderCache = computeClusterInGuiOrder();
            guiOrderCacheTick = now;
        }
        return guiOrderCache;
    }

    /** 计算 GUI 顺序的集群成员（含去重）：见 {@link #getClusterInGuiOrder()}。 */
    private java.util.List<SchematicLoaderBlockEntity> computeClusterInGuiOrder() {
        final java.util.List<SchematicLoaderBlockEntity> order = new java.util.ArrayList<>(collectCluster());
        final Level lvl = getLevel();
        if (lvl != null && !lvl.isClientSide()) {
            final SchematicannonBlockEntity cannon = findClusterCannon(lvl);
            if (cannon != null) {
                // 稳定排序：组内保持 collectCluster 原有的 Y 降序相对顺序
                order.sort(java.util.Comparator.comparingInt(
                    (SchematicLoaderBlockEntity loader) -> loader.isCannonAdjacent(cannon) ? 0 : 1));
            }
        }
        final java.util.Set<ItemStackHandler> seen =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        order.removeIf(loader -> !seen.add(loader.getInventory()));
        return order;
    }

    /** 集群内存视图（GUI 顺序，已按共享内存身份去重）：<b>每次调用都重新解析各台机器当前的内存</b>，
     *  返回的是 {@link #memoryView() 实时视图}而不是当时那个对象 —— 集群成员变化时内存会被换成新的
     *  共享对象，持有本列表的一方也随之指向正确的那一份。
     *  <p>同一份共享内存只出现一次（否则同一份内存会被显示成两份）。 */
    public java.util.List<net.neoforged.neoforge.items.IItemHandler> getClusterInventoryView() {
        final java.util.List<SchematicLoaderBlockEntity> order = getClusterInGuiOrder();
        final java.util.List<net.neoforged.neoforge.items.IItemHandler> handlers =
            new java.util.ArrayList<>(order.size());
        for (final SchematicLoaderBlockEntity loader : order) {
            handlers.add(loader.memoryView());
        }
        return handlers;
    }

    /** 把物品插入集群内存：按 {@link #getClusterInGuiOrder()} 顺序填充（优先 cannon 紧贴装填器），
     *  其余装填器作为后备缓冲；返回放不下的剩余。 */
    protected ItemStack insertIntoCluster(final ItemStack stack) {
        return insertIntoCluster(stack, false);
    }

    /** 纯计算「集群内存还能收下多少」（{@code simulate = true}，不改动任何库存）。
     *  用于「先算容量 → 再按实际能收的量从网络抽取」这条安全收集路径。 */
    protected ItemStack simulateInsertIntoCluster(final ItemStack stack) {
        return insertIntoCluster(stack, true);
    }

    /** 同 {@link #insertIntoCluster(ItemStack)}，{@code simulate = true} 时只算不放。 */
    protected ItemStack insertIntoCluster(final ItemStack stack, final boolean simulate) {
        ItemStack remainder = stack;
        for (final SchematicLoaderBlockEntity loader : getClusterInGuiOrder()) {
            if (remainder.isEmpty()) {
                break;
            }
            remainder = loader.insertIntoInventory(remainder, simulate);
        }
        return remainder;
    }

    protected int countItem(final Item item) {
        int count = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            final ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 统计集群内该物品的总数（避免重复拉取）。
     * <p><b>必须用去重后的视图</b>：同族装填器相邻后<b>共享同一份内存</b>，
     * 若按 {@link #collectCluster()} 逐台累加，同一份内容会被算 N 遍
     * ——补料时就会误判「已经够了」而不去拉取，最终打印缺料。
     */
    protected int countItemInCluster(final Item item) {
        int count = 0;
        for (final SchematicLoaderBlockEntity loader : getClusterInGuiOrder()) {
            count += loader.countItem(item);
        }
        return count;
    }

    /**
     * 收集与自身<b>物理连通（相邻六向 BFS）</b>的全部装填器作为集群（内存共享 + 蓝图联动）。
     * <p>集群<b>只按方块相邻关系</b>判定，与 RS 网络无关：同一网络里相隔很远的装填器是
     * 各自独立的单位，绝不允许跨站点同步蓝图 / 互抢同一台加农炮的资源。
     * <p>合并规则：只要集群中存在高级装填器，基础装填器即并入高级集群当作额外内存（容量叠加）；
     * 纯基础集群维持同类合并。
     *
     * @return 集群内所有装填器（含自身），按 Y 降序（最上面的优先）
     */
    public java.util.List<SchematicLoaderBlockEntity> collectCluster() {
        final Level level = getLevel();
        if (level == null) {
            return java.util.List.of(this);
        }
        final java.util.List<SchematicLoaderBlockEntity> all = new java.util.ArrayList<>();
        final java.util.IdentityHashMap<SchematicLoaderBlockEntity, Boolean> seen =
            new java.util.IdentityHashMap<>();
        final java.util.Queue<SchematicLoaderBlockEntity> queue = new java.util.ArrayDeque<>();
        seen.put(this, Boolean.TRUE);
        queue.add(this);
        while (!queue.isEmpty()) {
            final SchematicLoaderBlockEntity ldr = queue.poll();
            all.add(ldr);
            for (final Direction direction : Direction.values()) {
                final BlockEntity neighbor = level.getBlockEntity(ldr.worldPosition.relative(direction));
                if (neighbor instanceof SchematicLoaderBlockEntity next && !seen.containsKey(next)) {
                    seen.put(next, Boolean.TRUE);
                    queue.add(next);
                }
            }
        }
        return finalizeCluster(all);
    }

    /**
     * 决定合并成员：存在高级装填器 → 全员并入（基础变内存）；否则只保留基础装填器；随后按 Y 降序稳定排序。
     */
    private static java.util.List<SchematicLoaderBlockEntity> finalizeCluster(
        final java.util.List<SchematicLoaderBlockEntity> all) {
        final boolean hasAdvanced = all.stream().anyMatch(l -> l instanceof AdvancedSchematicLoaderBlockEntity);
        final java.util.List<SchematicLoaderBlockEntity> result = new java.util.ArrayList<>();
        for (final SchematicLoaderBlockEntity loader : all) {
            if (hasAdvanced || !(loader instanceof AdvancedSchematicLoaderBlockEntity)) {
                result.add(loader);
            }
        }
        result.sort((a, b) -> {
            final net.minecraft.core.BlockPos pa = a.getBlockPos();
            final net.minecraft.core.BlockPos pb = b.getBlockPos();
            if (pa.getY() != pb.getY()) {
                return Integer.compare(pb.getY(), pa.getY()); // Y 大的（更高）排前
            }
            if (pa.getX() != pb.getX()) {
                return Integer.compare(pa.getX(), pb.getX());
            }
            return Integer.compare(pa.getZ(), pb.getZ());
        });
        return result;
    }

    /** 集群内存总行数（<b>共享内存的格数</b> / 9；同一份共享内存只算一次），供滚动条/数据槽使用。 */
    public int getClusterTotalRows() {
        int slots = 0;
        for (final net.neoforged.neoforge.items.IItemHandler handler : getClusterInventoryView()) {
            slots += handler.getSlots();
        }
        return slots / 9;
    }

    // ==================== 机器集群（同族装填器相邻 = 一个整体：内存叠加 + 资源共享） ====================

    /** 本机是否承担集群内容的落盘职责（单机 = true；集群里只有主控为 true）。 */
    private boolean clusterOwnsPayload = true;
    /** 当前集群规模（台数，单机 = 1）。 */
    private int clusterSize = 1;
    /** 当前集群内是否存在高级装填器（决定基础装填器的蓝图槽是否锁定；只在拓扑变化时刷新）。 */
    private boolean clusterHasAdvanced;

    /**
     * 装填器主内存 —— <b>就是「从网络拉取进来暂存的那一份」</b>。整集群共用同一个实例：
     * 容量 = 各台单机格数之和，内容天然对所有成员可见（不需要任何同步逻辑）。
     * <p>实例上登记若干「持有者」（弱引用）：内容一变就通知所有仍持有它的装填器
     * —— 共享的那一份<b>只由主控落盘</b>，从任意一台改动都必须标记到主控，否则这次改动会丢。
     * <p>蓝图队列（{@link SchematicLoaderQueue}）复用同一套持有者 / 通知机制，
     * 于是「队列被谁改过都要标脏到主控」这条不变式是<b>唯一一份实现</b>。
     */
    public static class SchematicLoaderInventory extends ItemStackHandler {
        private final java.util.List<java.lang.ref.WeakReference<SchematicLoaderBlockEntity>> holders =
            new java.util.ArrayList<>(2);

        public SchematicLoaderInventory(final int slots) {
            super(slots);
        }

        /** 登记一个持有者（同一实例会被同集群的多台共享，故可重复登记、不覆盖）。 */
        public void bindOwner(final SchematicLoaderBlockEntity owner) {
            for (final java.lang.ref.WeakReference<SchematicLoaderBlockEntity> ref : holders) {
                if (ref.get() == owner) {
                    return;
                }
            }
            holders.add(new java.lang.ref.WeakReference<>(owner));
        }

        @Override
        protected void onContentsChanged(final int slot) {
            for (final java.util.Iterator<java.lang.ref.WeakReference<SchematicLoaderBlockEntity>> it =
                     holders.iterator(); it.hasNext(); ) {
                final SchematicLoaderBlockEntity owner = it.next().get();
                if (owner == null) {
                    it.remove(); // 已回收：顺手清理，避免长跑泄漏
                    continue;
                }
                owner.onClusterHandlerChanged(this);
            }
        }

        /** 本类（资源内存）受堆叠升级影响；蓝图队列覆写为 false（蓝图不可堆叠，与升级无关）。 */
        protected boolean stackUpgradeAffectsSlotLimit() {
            return true;
        }

        /**
         * 共享内存的每格上限 =「持有者里最宽的那一档」（单机时就是本机自己的上限）。
         * <p>取最宽而不是某台的具体值：一个物理格只有一份内容，本值会作为
         * {@link net.neoforged.neoforge.items.IItemHandler} 的对外声明（界面自绘控件、外部物流按它
         * 判断「这格还能不能放」）；若报得比某台界面允许的更小，就会出现「界面能放、处理器说满了」。
         * 每台自己那一档由 {@link #memoryView() 每机实时视图} 与 {@code insertIntoInventory} 各自保证
         * （见 {@link #getStorageSlotCapacity()} 的说明）。
         */
        @Override
        public int getSlotLimit(final int slot) {
            if (!stackUpgradeAffectsSlotLimit()) {
                return super.getSlotLimit(slot);
            }
            int limit = BASE_SLOT_CAPACITY;
            for (final java.lang.ref.WeakReference<SchematicLoaderBlockEntity> ref : holders) {
                final SchematicLoaderBlockEntity owner = ref.get();
                if (owner != null) {
                    limit = Math.max(limit, owner.getStorageSlotCapacity());
                }
            }
            return limit;
        }

        /**
         * 取出物品：单次可取出<b>格内全部</b>（{@code min(amount, 格内数量)}）。
         * <p>为什么要覆写：{@link ItemStackHandler} 默认按「物品自身一叠大小」截断单次取出量
         * （{@code min(amount, getMaxStackSize())}），堆叠升级后的格子可能装着 200+ 个，
         * 默认实现会让「回流网络 / 玩家整叠拿起」一次只能拿 64 个，得反复调用才搬得完。
         * 本工程其它同类机器（归流缓存仓）也采用同一口径。
         */
        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            final ItemStack existing = getStackInSlot(slot);
            if (existing.isEmpty()) {
                return ItemStack.EMPTY;
            }
            final int take = Math.min(amount, existing.getCount());
            if (take <= 0) {
                return ItemStack.EMPTY;
            }
            if (!simulate) {
                setStackInSlot(slot, take >= existing.getCount()
                    ? ItemStack.EMPTY : existing.copyWithCount(existing.getCount() - take));
            }
            return existing.copyWithCount(take);
        }
    }

    /**
     * 蓝图队列（高级版 27 格 / 基础版 0 格）。
     * <p>与主内存同源：只允许放 Create 蓝图；集群内高级↔高级共享同一份（见
     * {@link #rscc$clusterNewPayloadFor}），基础不参与也不贡献格数。
     */
    public static class SchematicLoaderQueue extends SchematicLoaderInventory {
        public SchematicLoaderQueue(final int slots) {
            super(slots);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return stack.isEmpty() || isValidBlueprintStack(stack);
        }

        /** 队列只放 Create 蓝图（不可堆叠）：堆叠升级只针对资源内存，队列沿用处理器默认上限。 */
        @Override
        protected boolean stackUpgradeAffectsSlotLimit() {
            return false;
        }
    }

    /**
     * 集群载荷：把「主内存 + 蓝图队列」打包成<b>一个身份稳定</b>的对象，
     * 让 {@link cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster} 继续用 {@code ==}
     * 判定「本机是否已经换成共享那一份」。
     * <p>两个字段都可能是 0 格（基础机的队列恒为 0 格）——「没有这一类内容」用 0 格表达，
     * 而不是 null，省掉调用方所有空判。
     */
    private static final class LoaderClusterPayload {
        private final SchematicLoaderInventory inventory;
        private final SchematicLoaderQueue queue;

        private LoaderClusterPayload(final SchematicLoaderInventory inventory, final SchematicLoaderQueue queue) {
            this.inventory = inventory;
            this.queue = queue;
        }
    }

    /** 内容变化回调：只有「本机当前确实在用这份内存 / 队列」才标记自己（落盘职责在主控，必须标记到）。 */
    private void onClusterHandlerChanged(final SchematicLoaderInventory source) {
        if (source == inventory || source == queue) {
            setChanged();
        }
    }

    /**
     * 「本机当前那一份处理器」的<b>实时视图</b>（身份稳定，每次调用都现解析对应的字段）。
     * <p><b>为什么必须实时</b>：菜单构造时会抓住这个视图（而不是「当时那个对象」），
     * 而装填器增台 / 拆台会把内存（或队列）换成「更大的那一份 / 单机那一份」—— 若菜单抓的是旧对象，
     * 玩家就会往一份<b>已经不再落盘的孤儿存储</b>里放东西（这是真正会丢物品的路径）。
     * <p>越界（拆台后界面仍停在旧页）一律「读 = 空 / 不能放 / 写 = 落回世界」：不抛异常、不吞物品。
     * <p>内存与队列语义完全一致，故共用同一个实现，只是取字段的方式不同。
     * <p><b>每格上限按「本机」算</b>（仅资源内存）：界面读的 {@code getSlotLimit} 直接取
     * {@link #getStorageSlotCapacity()}，于是每台看到的都是自己那一档上限；集群共享同一份内存时，
     * 各台也不会互相借用别人的堆叠升级。
     */
    private final class LiveView implements net.neoforged.neoforge.items.IItemHandlerModifiable {
        private final java.util.function.Supplier<ItemStackHandler> current;
        /** true = 资源内存视图（按本机堆叠升级给上限）；false = 队列视图（蓝图不受升级影响）。 */
        private final boolean machineSlotCapacity;

        private LiveView(final java.util.function.Supplier<ItemStackHandler> current,
                         final boolean machineSlotCapacity) {
            this.current = current;
            this.machineSlotCapacity = machineSlotCapacity;
        }

        @Override
        public int getSlots() {
            return current.get().getSlots();
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            return inRange(slot) ? current.get().getStackInSlot(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            return inRange(slot) ? current.get().insertItem(slot, stack, simulate) : stack;
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            return inRange(slot) ? current.get().extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(final int slot) {
            if (!inRange(slot)) {
                return BASE_SLOT_CAPACITY;
            }
            return machineSlotCapacity ? getStorageSlotCapacity() : current.get().getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return inRange(slot) && current.get().isItemValid(slot, stack);
        }

        @Override
        public void setStackInSlot(final int slot, final ItemStack stack) {
            if (inRange(slot)) {
                current.get().setStackInSlot(slot, stack);
                return;
            }
            dropIntoWorld(stack); // 该格已不存在：落回世界，绝不销毁
        }

        private boolean inRange(final int slot) {
            return slot >= 0 && slot < current.get().getSlots();
        }
    }

    private final net.neoforged.neoforge.items.IItemHandlerModifiable memoryView =
        new LiveView(() -> inventory, true);
    private final net.neoforged.neoforge.items.IItemHandlerModifiable queueView =
        new LiveView(() -> queue, false);

    /** 本机内存的实时视图（身份稳定）：菜单用它，集群换内存对象后界面自动跟上。 */
    public net.neoforged.neoforge.items.IItemHandlerModifiable memoryView() {
        return memoryView;
    }

    /** 本机当前队列的实时视图（身份稳定）：高级界面用它，集群换共享队列后界面自动跟上。 */
    public net.neoforged.neoforge.items.IItemHandlerModifiable queueView() {
        return queueView;
    }

    /** 当前共享队列总格数（单机高级 = 27；N 台高级同集群 = 27 × N）。 */
    public int getQueueSlotTotal() {
        return queue.getSlots();
    }

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
        return new LoaderClusterPayload(
            new SchematicLoaderInventory(inventorySlotCount * count),
            new SchematicLoaderQueue(queueSlotCount * count));
    }

    /**
     * 同族但单机格数不同（基础 54 / 高级 108）时的容量叠加：<b>按各台自己的格数求和</b>。
     * <p>沿用「台数 × 某一种单机格数」会让混阶集群凭空缩小或放大整体容量，两者都不允许。
     * <p><b>队列是单独的共享维度</b>：只有「有队列的成员（高级）」贡献格数，
     * 基础装填器既不贡献、也不参与（其 {@code queueSlotCount} = 0 自然落空）。
     */
    @Override
    public Object rscc$clusterNewPayloadFor(final java.util.List<RsccClusterable> members) {
        if (members == null || members.isEmpty()) {
            return rscc$clusterNewPayload(1);
        }
        int inventorySlots = 0;
        int queueSlots = 0;
        for (final RsccClusterable member : members) {
            if (member instanceof SchematicLoaderBlockEntity loader) {
                inventorySlots += loader.inventorySlotCount;
                queueSlots += loader.queueSlotCount;
            } else {
                inventorySlots += inventorySlotCount;
                queueSlots += queueSlotCount;
            }
        }
        return new LoaderClusterPayload(
            new SchematicLoaderInventory(Math.max(inventorySlotCount, inventorySlots)),
            new SchematicLoaderQueue(Math.max(queueSlotCount, queueSlots)));
    }

    @Override
    public Object rscc$clusterPayload() {
        return payload; // 身份稳定：同一份载荷每次返回同一个实例，管理器据此判定「是否已换成共享那一份」
    }

    @Override
    public void rscc$clusterAdopt(final Object shared) {
        if (!(shared instanceof LoaderClusterPayload next) || next == payload) {
            return;
        }
        inventory = next.inventory;
        inventory.bindOwner(this); // 共享实例可能先被别的成员建出：本机也要登记才能把改动标记到主控
        queue = next.queue;
        queue.bindOwner(this);
        payload = next;
        setChanged();
    }

    @Override
    public void rscc$clusterStandalone() {
        if (payload.inventory.getSlots() == inventorySlotCount
            && payload.queue.getSlots() == queueSlotCount) {
            return; // 已是单机规格：不重建对象，保持载荷身份稳定
        }
        // 调用方保证内容已按拆集群规则移交，这里只换成一份空的单机载荷（内存 + 队列）
        inventory = new SchematicLoaderInventory(inventorySlotCount);
        inventory.bindOwner(this);
        queue = new SchematicLoaderQueue(queueSlotCount);
        queue.bindOwner(this);
        payload = new LoaderClusterPayload(inventory, queue);
        setChanged();
    }

    @Override
    public long rscc$clusterMerge(final Object from, final Object into) {
        if (!(from instanceof LoaderClusterPayload source) || !(into instanceof LoaderClusterPayload target)
            || source == target) {
            return 0L;
        }
        // 内存与队列是两个独立维度：各自「移动」而不是「复制」，余量相加后交调用方兜底
        return mergeHandler(source.inventory, target.inventory)
            + mergeHandler(source.queue, target.queue);
    }

    /** 把 {@code source} 的全部内容搬进 {@code target}；返回搬不走的余量（0 = 全部搬空）。 */
    private static long mergeHandler(final ItemStackHandler source, final ItemStackHandler target) {
        if (source == null || target == null || source == target) {
            return 0L;
        }
        long leftover = 0L;
        for (int i = 0; i < source.getSlots(); i++) {
            final ItemStack stack = source.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            // 搬运是「移动」不是「复制」：插入结果被判定后才从源扣除
            final ItemStack rest = insertIntoHandler(target, stack, false);
            final int moved = stack.getCount() - rest.getCount();
            if (moved > 0) {
                source.extractItem(i, moved, false);
            }
            leftover += rest.getCount();
        }
        return leftover;
    }

    /** 兜底归宿：把载荷里剩下的东西落回世界（本方块处），绝不静默销毁。 */
    @Override
    public void rscc$clusterSpill(final Object payload) {
        // rscc-audit-ok: 这是「兜底归宿」而不是「收集」——每一步都是「先取出、紧接着落回世界」，
        // 世界侧没有容量上限因此不需要容量核对，不存在先删后插的丢失窗口。
        final Level currentLevel = getLevel();
        if (!(payload instanceof LoaderClusterPayload data) || currentLevel == null || currentLevel.isClientSide()) {
            return;
        }
        spillHandler(currentLevel, data.inventory);
        spillHandler(currentLevel, data.queue);
    }

    /** 把单个处理器里剩下的物品逐格落回世界（先取出、紧接着落回，不存在丢失窗口）。 */
    private void spillHandler(final Level currentLevel, final ItemStackHandler data) {
        for (int i = 0; i < data.getSlots(); i++) {
            final ItemStack stack = data.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            data.setStackInSlot(i, ItemStack.EMPTY);
            Block.popResource(currentLevel, worldPosition, stack);
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
        // 拓扑变了：GUI 顺序/行数缓存立即失效；同时刷新「同集群是否有高级」这个蓝图槽锁定判据
        guiOrderCache = null;
        refreshClusterHasAdvanced();
        setChanged();
    }

    /**
     * 蓝图槽是否锁定：<b>基础装填器</b>与高级装填器同集群时锁定（蓝图维度由高级接管）。
     * <p>为什么在 {@link #rscc$clusterChanged}（拓扑变化回调）里算好：本判据每个同步 tick
     * 都会被容器数据读取一次，而重新做一次邻块 BFS 没必要；拓扑不变时结果也不变。
     */
    public boolean isBlueprintSlotLocked() {
        return !(this instanceof AdvancedSchematicLoaderBlockEntity) && clusterHasAdvanced;
    }

    /** 重新判定「本集群是否存在高级装填器」（只在集群拓扑变化时调用）。 */
    private void refreshClusterHasAdvanced() {
        boolean found = false;
        if (level != null && !level.isClientSide()) {
            for (final SchematicLoaderBlockEntity loader : collectCluster()) {
                if (loader instanceof AdvancedSchematicLoaderBlockEntity) {
                    found = true;
                    break;
                }
            }
        }
        this.clusterHasAdvanced = found;
    }

    /**
     * 装填器家族（基础 / 高级）相邻即一个整体：两类的「从网络拉取进来暂存的那一份内存」
     * 语义完全相同（都是一格格物品，容量按各台求和），因此可以互相接管内容。
     */
    @Override
    public boolean rscc$clusterCompatible(final RsccClusterable other) {
        return other instanceof SchematicLoaderBlockEntity;
    }

    /** 当前参与的装填器台数（1 = 单机）。 */
    public int getClusterSize() {
        return clusterSize;
    }

    /**
     * 本机被移除（方块破坏 / 区块卸载）时的拆集群钩子：内容按
     * {@link cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster} 的规则
     * 「跟着当时持有它的一方走」，绝不复制、绝不销毁。
     */
    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster.onMemberRemoved(level, this);
        }
        super.setRemoved();
    }

    /** 供菜单同步：0/1/2 = 自动打印/回收/火药 开关，3 = 集群内存总行数，4 = 红石模式，5 = 蓝图槽是否锁定。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> autoPrint ? 1 : 0;
                    case 1 -> autoRecycle ? 1 : 0;
                    case 2 -> autoFillGunpowder ? 1 : 0;
                    case 3 -> getClusterTotalRows();
                    // 红石模式：走 RS 的 RedstoneModeSettings 映射（与 RS 原版机器同一套 0/1/2 编码）
                    case 4 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    case 5 -> isBlueprintSlotLocked() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 6;
            }
        };
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 集群持久化边界：一个集群只有主控把「共享的那份内容」落盘，其余成员写空载荷 ——
        // 否则同一份内容会被写成多份，读档合并后资源翻倍（复制事故）。
        // 蓝图槽 / 插件槽属于各机自己的作业状态，永远照常保存。
        if (clusterOwnsPayload) {
            tag.put("Inventory", inventory.serializeNBT(registries));
            // 队列也是集群共享内容：只有主控写。主控可能是基础装填器（本机队列 0 格），
            // 但它此刻持有的是整个集群的共享队列（高级贡献 27 格/台），必须一并落盘，
            // 否则集群内容会随主控卸载而消失。
            if (queue.getSlots() > 0) {
                tag.put("Queue", queue.serializeNBT(registries));
            }
        }
        tag.put("Blueprint", blueprintSlot.serializeNBT(registries));
        tag.put("Upgrades", upgradeContainer.serializeNBT(registries));
        tag.putBoolean("AutoPrint", autoPrint);
        tag.putBoolean("AutoRecycle", autoRecycle);
        tag.putBoolean("AutoGunpowder", autoFillGunpowder);
        // 运行期状态：存档/读档保持一致（避免重进存档后“已完成轮次”被重复执行）
        tag.putBoolean("StandaloneComplete", standaloneCollectComplete);
        tag.putString("StandaloneKey", standaloneBlueprintKey);
        tag.putBoolean("StandaloneHoldingKit", standaloneHoldingKit);
        tag.putBoolean("AttachRoundComplete", attachRoundComplete);
        tag.putString("ActiveBlueprintFile", activeBlueprintFileName);
        tag.putString("CacheBlueprintKey", cachedRequirementsBlueprintKey);
        tag.putBoolean("CacheRequirementsFull", cachedRequirementsFull);
        // 需求数量缓存（每种材料:数量）随档保存，重进存档后横幅/补料口径保持一致
        if (!cachedRequirements.isEmpty()) {
            final CompoundTag reqTag = new CompoundTag();
            for (final java.util.Map.Entry<Item, Integer> entry : cachedRequirements.entrySet()) {
                final net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getKey());
                if (id != null) {
                    reqTag.putInt(id.toString(), entry.getValue());
                }
            }
            if (!reqTag.isEmpty()) {
                tag.put("CachedRequirements", reqTag);
            }
        }
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            // 存档里的内存格数可能是「当时集群叠加后」的值（N 台相邻 = 各台格数之和）：
            // ItemStackHandler 按 tag 里的 Size 自适应格数，因此叠加内存的内容能整体读回；
            // 随后集群重建会按「当前已加载的台数」重新定型（容量叠加，见 RsccMachineCluster）。
            inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
        if (tag.contains("Blueprint")) {
            blueprintSlot.deserializeNBT(registries, tag.getCompound("Blueprint"));
        }
        if (tag.contains("Upgrades")) {
            loadingUpgrades = true;
            try {
                upgradeContainer.deserializeNBT(registries, tag.getCompound("Upgrades"));
            } finally {
                loadingUpgrades = false;
            }
        }
        if (tag.contains("Queue")) {
            // 读回时按 tag 里的 Size 自适应格数：主控可能是基础装填器（本机队列 0 格），
            // 但存档里那份队列属于整个集群，必须原样读回，随后由集群重建定型（绝不丢内容）。
            queue.deserializeNBT(registries, tag.getCompound("Queue"));
        }
        autoPrint = tag.getBoolean("AutoPrint");
        autoRecycle = tag.getBoolean("AutoRecycle");
        autoFillGunpowder = tag.getBoolean("AutoGunpowder");
        standaloneCollectComplete = tag.getBoolean("StandaloneComplete");
        standaloneBlueprintKey = tag.getString("StandaloneKey");
        standaloneHoldingKit = tag.getBoolean("StandaloneHoldingKit");
        attachRoundComplete = tag.getBoolean("AttachRoundComplete");
        activeBlueprintFileName = tag.getString("ActiveBlueprintFile");
        cachedRequirementsBlueprintKey = tag.getString("CacheBlueprintKey");
        cachedRequirementsFull = tag.getBoolean("CacheRequirementsFull");
        // 需求数量缓存（每种材料:数量）随档保存，重进存档后横幅/补料口径保持一致
        if (tag.contains("CachedRequirements")) {
            final CompoundTag reqTag = tag.getCompound("CachedRequirements");
            final java.util.Map<Item, Integer> req = new java.util.HashMap<>();
            for (final String itemId : reqTag.getAllKeys()) {
                final Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.parse(itemId));
                if (item != null && item != net.minecraft.world.item.Items.AIR) {
                    req.put(item, reqTag.getInt(itemId));
                }
            }
            if (!req.isEmpty()) {
                cachedRequirements = req;
            }
        }
    }

    /**
     * <b>只读诊断</b>：蓝图加农炮装填器的完整状态（供 {@code /rs_create_compat diag} 导出）。
     *
     * <h2>为什么需要它（用户 2026-10-05 的要求）</h2>
     * <p>用户原话：<i>「导出某台机子我对它的设置什么的，以防我每一次都要跟你讲一下，
     * 或者说有时候我记错了，然后我讲错你也就修错的问题」</i>。
     * 装填器的「设置」尤其多（自动打印 / 自动回收 / 自动填火药 / 材料拉取上限 / 集群行数 /
     * 蓝图槽是否上锁 / 队列里有什么），逐条靠玩家复述极不可靠 —— 这里一次全部列出。</p>
     * <p>只读：只调用公开只读访问器，不搬运 / 不修改任何东西。</p>
     */
    @Override
    public java.util.Map<String, Object> rscc$diagReport() {
        final java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pos", getBlockPos().getX() + "," + getBlockPos().getY() + "," + getBlockPos().getZ());
        out.put("kind", "schematic_loader");
        out.put("autoPrint", isAutoPrint());
        out.put("autoRecycle", isAutoRecycle());
        out.put("autoFillGunpowder", isAutoFillGunpowder());
        out.put("materialPullLimit", getMaterialPullLimit());
        out.put("storageSlotCapacity", getStorageSlotCapacity());
        out.put("queueSlotTotal", getQueueSlotTotal());
        out.put("clusterTotalRows", getClusterTotalRows());
        out.put("clusterSize", getClusterSize());
        out.put("blueprintSlotLocked", isBlueprintSlotLocked());
        out.put("energyUsage", getEnergyUsage());
        out.put("blueprint", stackLabel(getBlueprintSlot().getStackInSlot(0)));
        out.put("queue", slotsOf(getQueue()));
        out.put("upgrades", slotsOf(getUpgradeContainer()));
        out.put("inventoryStacks", countNonEmpty(getInventory()) + "/" + getInventory().getSlots());
        final SchematicannonBlockEntity cannon = getAttachedCannon();
        out.put("attachedCannon", cannon == null ? "-"
            : cannon.getBlockPos().getX() + "," + cannon.getBlockPos().getY() + ","
                + cannon.getBlockPos().getZ());
        return out;
    }

    /** 只读小工具：一个 ItemStackHandler 的非空槽（{@code 槽:物品×数量}）。 */
    private static java.util.List<Object> slotsOf(final net.neoforged.neoforge.items.ItemStackHandler handler) {
        final java.util.List<Object> out = new java.util.ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                out.add(slot + ":" + stackLabel(stack));
            }
        }
        return out;
    }

    private static int countNonEmpty(final net.neoforged.neoforge.items.ItemStackHandler handler) {
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String stackLabel(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "-";
        }
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
            + " x" + stack.getCount();
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getInventory()
        );
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
