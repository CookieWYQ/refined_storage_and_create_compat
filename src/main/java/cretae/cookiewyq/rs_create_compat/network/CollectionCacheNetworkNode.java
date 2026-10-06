package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;

import javax.annotation.Nullable;

/**
 * 归流缓存仓的 RS 网络节点：
 * <ul>
 *     <li>每 tick 消耗网络能量（空闲 {@code Config.collectionCacheIdleEnergyUsage}，
 *     工作时 {@code Config.collectionCacheWorkEnergyUsage}，并按速度升级加成）。</li>
 *     <li>驱动方块实体按配置间隔扫描周围掉落物、凑够匹配区数量后收集，并把缓存区内容写入网络。</li>
 * </ul>
 */
public class CollectionCacheNetworkNode extends AbstractNetworkNode {
    @Nullable
    private CollectionCacheBlockEntity blockEntity;

    public void setBlockEntity(final CollectionCacheBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public CollectionCacheBlockEntity getBlockEntity() {
        return blockEntity;
    }

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
        // 按 RS 能量机制抽取本机耗电
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());
        blockEntity.tickCache(network);
    }
}
