package cretae.cookiewyq.rs_create_compat.block.entity;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.RangeChargerNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
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
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;

import java.util.List;

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
    /** 无限范围（creative）时，扫描覆盖每个在线玩家已加载的区块半径（chunk 数）。 */
    private static final int INFINITE_CHUNK_RADIUS = 16;

    private final RestorableEnergyStorage energyStorage;
    /** 插件槽（6 格）：速度/堆叠升级提升充电速率，范围升级突破单轴 100 格上限。 */
    private final ItemStackHandler upgradeContainer;
    private int rangeX = 50;
    private int rangeY = 50;
    private int rangeZ = 50;
    /** 上次校验时是否已放入无限范围升级（用于取下后回落数值）。 */
    private boolean hadInfiniteRangeUpgrade;
    /** 上一次充电扫描实际充电的对象数量（供 GUI 显示）。 */
    private int lastChargedTargets;
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

    public RangeChargerBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(), pos, state, new RangeChargerNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        // <b>用可「直接设值」的子类</b>（2026-10-05 修「重进存档后重新充电」）：
        //
        // 实测日志：{@code load-done savedEnergy=1000000 loadedEnergy=5000 maxReceive=5000}
        // —— 存档里确实是满电 100 万，但恢复后只剩 5000，因为 NeoForge 的
        // {@link EnergyStorage#receiveEnergy} 会被<b>自身 maxReceive（= 每 tick 充电/传输速率）</b>
        // 夹住。加载是「<b>设定值</b>」语义、不是「接收能量」语义，因此必须绕过这个夹量。
        this.energyStorage = new RestorableEnergyStorage(
            Config.rangeChargerEnergyCapacity,
            Config.rangeChargerMaxTransfer
        );
        // 任何写入（点击 / Shift 移动 / 漏斗 / NBT 载入）都会立刻校验：非法升级弹出、超上限弹出。
        this.upgradeContainer = new ItemStackHandler(6) {
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
     * <p>对外仍是一个普通 {@link EnergyStorage}：充电消耗走父类 {@code extractEnergy}、
     * 外部机器灌电走父类 {@code receiveEnergy}（照旧受速率限制），行为一字未改。</p>
     */
    private static final class RestorableEnergyStorage extends EnergyStorage {
        RestorableEnergyStorage(final int capacity, final int maxTransfer) {
            super(capacity, maxTransfer);
        }

        /** 把存储量<b>直接设为</b> {@code amount}（夹到 [0, capacity]）；与 maxReceive 无关。 */
        void restoreEnergy(final int amount) {
            this.energy = Math.clamp(amount, 0, this.capacity);
        }
    }

    public EnergyStorage getEnergyStorage() {
        return energyStorage;
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

    /** 单目标充电速率：基础值 << 等效升级数。速度升级每个翻倍；堆叠升级是高级版（1 个 = 3 个速度升级，×8）。 */
    public int getChargeRate() {
        return Config.rangeChargerChargeRate << getUpgradeCount();
    }

    /** 速度+堆叠升级总数（供 GUI 显示充电速率；堆叠按 3 个速度折算）。 */
    public int getUpgradeCount() {
        return countUpgrades(SPEED_UPGRADE) + countUpgrades(STACK_UPGRADE) * 3;
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
     * 7 = 范围升级数，8 = 是否无限范围（creative_range_upgrade），9 = 红石模式（0/1/2，服务端权威）。
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
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 范围与能量只能由服务端逻辑修改
            }

            @Override
            public int getCount() {
                return 10;
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
        if (level != null && !level.isClientSide() && mainNetworkNode.isActive()) {
            validateUpgradeSlots(); // 清除历史版本遗留的非法升级（如自动合成）
            doCharging(level);
        }
    }

    /**
     * 范围充电器只接受 速度/堆叠/范围 升级且每种最多 6 个（与 6 格插件槽一致）；
     * 发现槽内存在非法升级（例如自动合成）或超上限时，把物品弹出到世界，避免无效果的升级占用槽位误导玩家。
     * 校验完成后把三轴范围收敛到当前范围升级允许的上限。
     */
    private void validateUpgradeSlots() {
        if (validatingUpgrades) {
            return;
        }
        validatingUpgrades = true;
        try {
            final int[] counts = new int[4]; // 0=speed 1=stack 2=range 3=creative_range
            for (int i = 0; i < upgradeContainer.getSlots(); i++) {
                final ItemStack inSlot = upgradeContainer.getStackInSlot(i);
                if (inSlot.isEmpty()) {
                    continue;
                }
                final int kind = upgradeKind(inSlot); // -1 非法
                if (kind < 0) {
                    dropAndClear(i);
                    continue;
                }
                counts[kind]++;
            }
            // 超上限的冗余升级弹出（先保留前 MAX_PER_UPGRADE 个；creative 最多 1）
            for (int kind = 0; kind < counts.length; kind++) {
                final int max = kind == 3 ? 1 : cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE;
                if (counts[kind] <= max) {
                    continue;
                }
                final int[] keep = new int[1];
                for (int i = 0; i < upgradeContainer.getSlots(); i++) {
                    final ItemStack inSlot = upgradeContainer.getStackInSlot(i);
                    if (inSlot.isEmpty()) {
                        continue;
                    }
                    if (upgradeKind(inSlot) == kind) {
                        if (keep[0] >= max) {
                            dropAndClear(i);
                        } else {
                            keep[0]++;
                        }
                    }
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
            case "creative_range_upgrade" -> 3;
            default -> -1;
        };
    }

    private void dropAndClear(final int slot) {
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
        if (energyStorage.getEnergyStored() <= 0) {
            return;
        }
        // 饰品槽（Curios）这一档按节流周期走一次（背包族仍然每 tick，见 nextCuriosPass 的说明）。
        final boolean curiosPass = nextCuriosPass();
        if (hasInfiniteRange()) {
            // 无限范围：不再受 rangeX/Y/Z 限制 —— 全维度在线玩家与掉落物，以及玩家所在已加载区块内的方块
            if (Config.rangeChargerChargeBlocks) {
                lastChargedTargets += scanBlocksInfinite(level);
            }
            if (Config.rangeChargerChargeItems) {
                lastChargedTargets += scanItemsInfinite(level);
            }
            if (Config.rangeChargerChargePlayerItems) {
                lastChargedTargets += scanPlayersInfinite(level, curiosPass);
            }
            return;
        }
        // 每 tick 都执行充电；扫描耗电很小，不再因能量不足而跳过
        if (Config.rangeChargerChargeBlocks) {
            lastChargedTargets += scanBlocks(level);
        }
        if (Config.rangeChargerChargeItems) {
            lastChargedTargets += scanItems(level);
        }
        if (Config.rangeChargerChargePlayerItems) {
            lastChargedTargets += scanPlayers(level, curiosPass);
        }
    }

    /**
     * 饰品槽扫描的节流闸门：返回「本 tick 要不要扫饰品槽」，并推进计数。
     *
     * <p>周期语义 = {@link Config#rangeChargerCuriosScanInterval}（默认 5）—— 连续两次扫描之间正好隔这么多 tick
     * （间隔为 1 时退化成「每 tick 都扫」，与背包族一致）。</p>
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
            targets += chargePlayer(player, targets, curiosPass);
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
            final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
                continue;
            }
            final int transfer = Math.min(getChargeRate(), energyStorage.getEnergyStored());
            final int accepted = storage.receiveEnergy(transfer, false);
            if (accepted > 0) {
                energyStorage.extractEnergy(accepted, false);
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
            targets += chargePlayer(player, targets, curiosPass);
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
     * @param curiosPass 本 tick 是否顺带扫饰品槽（由 {@link #nextCuriosPass()} 节流决定）
     */
    private int chargePlayer(final net.minecraft.world.entity.player.Player player, final int currentTargets,
                             final boolean curiosPass) {
        int targets = currentTargets;
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
            if (chargeItemStack(stack)) {
                targets++;
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
            if (chargeItemStack(stack)) {
                targets++;
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
        if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
            return false; // 不是可充电物品 / 已经充满：充满即停
        }
        final int transfer = Math.min(getChargeRate(), energyStorage.getEnergyStored());
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
                        continue; // 跳过自身
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
        if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
            return false;
        }
        final int transfer = Math.min(getChargeRate(), energyStorage.getEnergyStored());
        final int accepted = storage.receiveEnergy(transfer, false);
        if (accepted > 0) {
            energyStorage.extractEnergy(accepted, false);
            return true;
        }
        return false;
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
            final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
                continue;
            }
            final int transfer = Math.min(getChargeRate(), energyStorage.getEnergyStored());
            final int accepted = storage.receiveEnergy(transfer, false);
            if (accepted > 0) {
                energyStorage.extractEnergy(accepted, false);
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
            (blockEntity, direction) -> blockEntity.getEnergyStorage()
        );
        // 注册为 RS 网络节点容器，使 RS 线缆/控制器可以连接并为节点供能
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
