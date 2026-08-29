package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;

/**
 * 定量保持器的 RS 网络节点：
 * <ul>
 *     <li>每 tick 消耗网络能量（Config.quantityKeeperEnergyUsage）。</li>
 *     <li>检测网络中被标记对象的数量：不足时触发 RS 自动合成（需自动合成升级），
 *     超出时按销毁开关与速度升级销毁过量部分。</li>
 * </ul>
 */
public class QuantityKeeperNetworkNode extends AbstractNetworkNode {
    @Nullable
    private QuantityKeeperBlockEntity blockEntity;

    public void setBlockEntity(final QuantityKeeperBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
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
        // 消耗网络能量
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());

        // 未标记对象则保持休眠（不亮）
        final ItemStack marker = blockEntity.getMarkerStack();
        if (marker.isEmpty()) {
            return;
        }
        final ResourceKey resource = new ItemResource(marker.getItem(), marker.getComponentsPatch());
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final long stored = storage.get(resource);
        final long target = blockEntity.getTargetAmount();

        if (stored < target) {
            // 数量不足：需要自动合成升级才能触发合成
            if (blockEntity.hasAutocraftingUpgrade()) {
                final AutocraftingNetworkComponent autocrafting =
                    network.getComponent(AutocraftingNetworkComponent.class);
                autocrafting.ensureTask(resource, target - stored, Actor.EMPTY, null);
            }
        } else if (stored > target && blockEntity.isDestroyOverflow()) {
            // 数量超出：销毁过量部分（速度升级加快销毁速率）
            final long excess = stored - target;
            final long toDestroy = Math.min(excess, blockEntity.getDestroyRate());
            if (toDestroy > 0) {
                storage.extract(resource, toDestroy, Action.EXECUTE, Actor.EMPTY);
            }
        }
    }
}
