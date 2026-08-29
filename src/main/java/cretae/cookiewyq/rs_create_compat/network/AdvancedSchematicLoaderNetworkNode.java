package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;

import javax.annotation.Nullable;

/**
 * 高级蓝图加农炮装填器的 RS 网络节点。
 */
public class AdvancedSchematicLoaderNetworkNode extends AbstractNetworkNode {
    @Nullable
    private AdvancedSchematicLoaderBlockEntity blockEntity;

    public void setBlockEntity(final AdvancedSchematicLoaderBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public long getEnergyUsage() {
        return blockEntity == null ? 1 : blockEntity.getEnergyUsage();
    }

    @Override
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        blockEntity.doLoaderWork(network);
    }
}
