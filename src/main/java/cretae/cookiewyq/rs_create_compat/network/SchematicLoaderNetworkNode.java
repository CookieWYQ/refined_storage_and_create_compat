package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;

import javax.annotation.Nullable;

/**
 * 蓝图加农炮装填器的 RS 网络节点：每 tick 驱动装填器从 RS 网络
 * 为相邻的 Create 蓝图加农炮（Schematicannon）补充资源。
 */
public class SchematicLoaderNetworkNode extends AbstractNetworkNode {
    @Nullable
    private SchematicLoaderBlockEntity blockEntity;

    public void setBlockEntity(final SchematicLoaderBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    /** 方块被破坏时回流物品用：可能为 null（未接入网络）。 */
    @Nullable
    public Network getNetworkOrNull() {
        return network;
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
