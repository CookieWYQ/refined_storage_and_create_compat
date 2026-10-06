package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;

import javax.annotation.Nullable;

/**
 * 序列执行仓的 RS 网络节点：每 tick 驱动方块执行“分布式序列装配认领引擎”
 * （引擎内部自带节流，避免全表扫描刷屏），并委托方块上报能耗。
 */
public class SequenceExecutionChamberNetworkNode extends AbstractNetworkNode {
    @Nullable
    private SequenceExecutionChamberBlockEntity blockEntity;

    public void setBlockEntity(final SequenceExecutionChamberBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public SequenceExecutionChamberBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Nullable
    public com.refinedmods.refinedstorage.api.network.Network getNetworkOrNull() {
        return network;
    }

    @Override
    public long getEnergyUsage() {
        return blockEntity == null ? 8 : blockEntity.getEnergyUsage();
    }

    @Override
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        // 接入网络后每 tick 消耗能量（与定量保持器一致）
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());
        blockEntity.tickEngine(network);
    }
}
