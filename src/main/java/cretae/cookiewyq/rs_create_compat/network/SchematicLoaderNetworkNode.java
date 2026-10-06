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

    @Nullable
    public SchematicLoaderBlockEntity getBlockEntity() {
        return blockEntity;
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
        // 只要求已接入网络即可工作：蓝图同步不依赖能量；
        // 网络能量不足时 storage 自然为空，补货无副作用。
        // 红石模式：RS 基类已把「红石条件 + 已接入网络 + 能量」折算成 active，
        // 条件不满足（含红石模式选了高/低电平但信号不符）→ 停机，且不销毁任何资源。
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        blockEntity.doLoaderWork(network);
    }
}
