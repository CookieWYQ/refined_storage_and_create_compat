package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;

import javax.annotation.Nullable;

/**
 * 范围充电器的 RS 网络节点：作为 RS 网络的能量消费者，
 * 每 tick 从网络能量池抽取能量到本地 FE 缓存。
 */
public class RangeChargerNetworkNode extends AbstractNetworkNode {
    @Nullable
    private RangeChargerBlockEntity blockEntity;

    public void setBlockEntity(final RangeChargerBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public com.refinedmods.refinedstorage.api.network.Network getNetworkOrNull() {
        return network;
    }

    @Override
    public long getEnergyUsage() {
        // 网络能量消耗按需从 doWork 抽取，这里不设定固定消耗
        return 0;
    }

    @Override
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        final net.neoforged.neoforge.energy.IEnergyStorage storage = blockEntity.getEnergyStorage();
        final long space = storage.getMaxEnergyStored() - storage.getEnergyStored();
        if (space <= 0) {
            return;
        }
        // 从 RS 网络能量池抽取并存入本地缓存（RS 能量单位即 FE）
        final long extracted = network.getComponent(EnergyNetworkComponent.class).extract(space);
        if (extracted > 0) {
            storage.receiveEnergy((int) extracted, false);
        }
    }
}
