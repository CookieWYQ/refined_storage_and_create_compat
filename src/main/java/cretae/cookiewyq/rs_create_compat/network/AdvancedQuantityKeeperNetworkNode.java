package cretae.cookiewyq.rs_create_compat.network;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.TimeoutableCancellationToken;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.KeeperCluster;
import cretae.cookiewyq.rs_create_compat.support.KeeperOverflow;
import cretae.cookiewyq.rs_create_compat.support.KeeperTarget;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 高级资源定量保持器的 RS 网络节点（资源定量保持器网络节点的「多资源版本」）：
 * <ul>
 *     <li>每 tick 消耗网络能量（{@link Config#quantityKeeperEnergyUsage}）；</li>
 *     <li><b>遍历 4 个配置槽</b>，对每个已标记槽位独立评估：网络存量不足时触发 RS 自动合成
 *     （该槽开关开启且装有自动合成升级），存量超出目标<b>且该槽「过量销毁」开关开启</b>时
 *     按销毁速率销毁超出目标的那部分；</li>
 *     <li>空槽（form=3）不参与任何约束，顺序无关；</li>
 *     <li>同网络多台共存：物品键与流体键天然分属两个维度，同一个键由 {@link KeeperCluster}
 *     选出的<b>权威</b>保持器负责（合成 / 销毁），其余同键保持器只搬运、不抢占。</li>
 * </ul>
 */
public class AdvancedQuantityKeeperNetworkNode extends AbstractNetworkNode implements KeeperCluster.Node {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 自动合成请求节流（tick）：每 2 秒评估一次即可。 */
    private static final int AUTO_CRAFT_COOLDOWN_TICKS = 40;
    /** 高级版在仲裁里的机器类型优先级（数值小的优先 ⇒ 同坐标不可能同放两台，这里只是兜底）。 */
    private static final int ARBITRATION_RANK = 1;

    @Nullable
    private AdvancedQuantityKeeperBlockEntity blockEntity;

    /** 每槽销毁冷却（tick）。 */
    private final int[] destroyCooldown = new int[AdvancedQuantityKeeperBlockEntity.SLOT_COUNT];
    /** 每槽自动合成请求节流。 */
    private final int[] autoCraftCooldown = new int[AdvancedQuantityKeeperBlockEntity.SLOT_COUNT];
    /** 上一 tick 让位的资源键（只在变化时打一条日志，不刷屏）。 */
    private final Set<ResourceKey> lastYielded = new HashSet<>();
    /** 每槽「生效目标被钳制」的观测器（只打日志，不做搬运）。 */
    private final KeeperTarget.ClampObservation[] clampObservations =
        new KeeperTarget.ClampObservation[AdvancedQuantityKeeperBlockEntity.SLOT_COUNT];

    public AdvancedQuantityKeeperNetworkNode() {
        for (int slot = 0; slot < clampObservations.length; slot++) {
            clampObservations[slot] = new KeeperTarget.ClampObservation();
        }
    }

    public void setBlockEntity(final AdvancedQuantityKeeperBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }

    // ==================== 同网络多台保持器的仲裁 ====================

    @Override
    public List<ResourceKey> claimedResources() {
        return blockEntity == null ? List.of() : blockEntity.claimedResources();
    }

    @Override
    public BlockPos arbitrationPos() {
        return blockEntity == null ? BlockPos.ZERO : blockEntity.getBlockPos();
    }

    @Override
    public int arbitrationRank() {
        return ARBITRATION_RANK;
    }

    @Override
    public long getEnergyUsage() {
        return Config.quantityKeeperEnergyUsage;
    }

    @Override
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());
        blockEntity.enforceUpgradeCaps(); // 只读校验插件槽（异常只记日志，绝不搬移 / 删除任何物品）

        // 同网络多台共存：先算出「应当让位」的资源键（同键时只有一台权威）
        final Set<ResourceKey> yielded = KeeperCluster.yieldedResources(network, this);
        logYieldChange(yielded);
        // 同类存储 → 网络回流（堵塞槽跳过，不销毁任何内容）；开启过量销毁的槽在本机侧销毁超出目标的余量
        blockEntity.tickStorage(network, yielded);

        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        for (int slot = 0; slot < AdvancedQuantityKeeperBlockEntity.SLOT_COUNT; slot++) {
            final KeeperOverflow.Episode log = blockEntity.overflowLog(slot);
            if (!blockEntity.hasMarker(slot)) {
                log.idle(); // 空槽：不应用任何约束
                continue;
            }
            final ResourceKey resource = blockEntity.networkResourceKey(slot);
            if (resource == null || yielded.contains(resource)) {
                // 无法解析 / 让位给同网络更权威的保持器：本机不合成也不销毁
                log.idle();
                continue;
            }
            final long setTarget = blockEntity.getTarget(slot);
            if (!KeeperTarget.isMarked(setTarget)) {
                // 下限 0：设定值 ≤ 0 视为「未标记」⇒ 该槽不维持 / 不合成 / 不销毁（也不计入缺料）
                log.idle();
                continue;
            }
            final long stored = storage.get(resource);
            // 生效目标 = 设定值与「可达上限」的钳制（唯一实现见 KeeperTarget，与基础版同一处口径）：
            // 可自动合成 ⇒ 不设上限；不可自动合成 ⇒ 上限 = 网络当前持有量。
            final long target = KeeperTarget.effectiveTarget(setTarget, stored,
                KeeperTarget.isCraftable(autocrafting, resource));
            clampObservations[slot].observe(blockEntity.diagnosticLabel(slot), setTarget, stored, target);
            if (stored < target) {
                maintainByAutocraft(slot, resource, target - stored, autocrafting);
                log.idle();
                continue;
            }
            final long excess = KeeperOverflow.excess(stored, target);
            if (excess <= 0L || !blockEntity.isDestroyOverflow(slot)) {
                // 已达标，或本槽「过量销毁」开关关闭 ⇒ 一个都不销毁（超出部分原样保留）
                log.idle();
                continue;
            }
            // 数量超出且开关打开：按销毁速率删除「超出目标」的部分（绝不低于目标、绝不碰其它槽的资源）
            final int ratePerSecond = Math.max(1, blockEntity.getDestroyRate());
            if (destroyCooldown[slot]-- > 0) {
                continue; // 冷却中：本 tick 不销毁（事件未结束，故不调 idle 收尾）
            }
            destroyCooldown[slot] = KeeperOverflow.cooldownTicks(ratePerSecond);
            final long destroyed = KeeperOverflow.destroyFromNetwork(storage, resource, excess,
                KeeperOverflow.batchSize(ratePerSecond));
            log.record(blockEntity.diagnosticLabel(slot), resource, target, destroyed,
                KeeperOverflow.excess(storage.get(resource), target));
        }
    }

    /** 数量不足：该槽自动合成开关开启且装有自动合成升级时，按目标缺口触发 RS 自动合成。 */
    private void maintainByAutocraft(final int slot, final ResourceKey resource, final long missing,
                                     @Nullable final AutocraftingNetworkComponent autocrafting) {
        if (!blockEntity.shouldAutoCraft(slot) || missing <= 0L) {
            return;
        }
        // 没有对应输出样板就不请求，避免空转；ensureTask 必须携带非空取消令牌，否则 RS 内部 NPE
        if (autocrafting != null && !autocrafting.getPatternsByOutput(resource).isEmpty()
            && autoCraftCooldown[slot]-- <= 0) {
            autocrafting.ensureTask(resource, missing, Actor.EMPTY, new TimeoutableCancellationToken());
            autoCraftCooldown[slot] = AUTO_CRAFT_COOLDOWN_TICKS;
            // 成就触发点：高级保持器首次请求自动合成补货（服务端网络节点；重复 fire 由原版去重）
            cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onAdvancedAutocraftRequested(blockEntity);
        }
    }

    /** 让位状态发生变化时各打一条 INFO（用户可据此知道某台为何「不动作」）。 */
    private void logYieldChange(final Set<ResourceKey> yielded) {
        if (yielded.equals(lastYielded)) {
            return;
        }
        final String label = blockEntity == null ? "advanced_quantity_keeper" : blockEntity.diagnosticLabel();
        if (!lastYielded.isEmpty()) {
            LOGGER.info("{} {} 恢复自主控制：{}", KeeperOverflow.LOG_PREFIX, label, lastYielded);
        }
        if (!yielded.isEmpty()) {
            LOGGER.info("{} {} 让位给同网络更权威的保持器：{}（本机只搬运，不销毁 / 不合成）",
                KeeperOverflow.LOG_PREFIX, label, yielded);
        }
        lastYielded.clear();
        lastYielded.addAll(yielded);
    }
}
