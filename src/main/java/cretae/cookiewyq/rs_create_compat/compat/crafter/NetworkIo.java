package cretae.cookiewyq.rs_create_compat.compat.crafter;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * Create 特化路径向 RS 网络写入资源的<b>唯一出口</b>。
 *
 * <p>为什么要有这一层，而不是各处直接 {@code storage.insert(...)}：</p>
 * <ol>
 *     <li>「先 SIMULATE 问容量、再 EXECUTE 写入」的顺序必须只有一份实现，
 *     否则任何一处漏掉 simulate 都会变成「产物被吞掉」；</li>
 *     <li>写入走 {@link StorageNetworkComponent}（{@code RootStorage}），RS 的合成任务
 *     在 {@code RootStorageImpl} 的 {@code afterInsert} 里<b>认领</b>它等待的产物
 *     （见 build/rs_src_full/.../api/storage/root/RootStorageImpl.java:117），
 *     因此这是「产物回网 + 任务收尾」的唯一正确通道；</li>
 *     <li>网络不存在 / 没有存储时一律返回「收不下」，调用方据此退回原语义（掉落 / 留在机器里），
 *     绝不静默销毁。</li>
 * </ol>
 */
public final class NetworkIo {
    private NetworkIo() {
    }

    /** 取网络的根存储；网络为空或未接入存储返回 {@code null}。 */
    @Nullable
    public static StorageNetworkComponent storageOf(@Nullable final Network network) {
        return network == null ? null : network.getComponent(StorageNetworkComponent.class);
    }

    /** 只问不收：返回网络接受的数量（0 = 收不下 / 没网络）。 */
    public static long simulate(@Nullable final Network network,
                                final ResourceKey resource,
                                final long amount) {
        final StorageNetworkComponent storage = storageOf(network);
        if (storage == null || amount <= 0L) {
            return 0L;
        }
        return storage.insert(resource, amount, Action.SIMULATE, Actor.EMPTY);
    }

    /** 真正写入：返回写入量。调用方必须先 {@link #simulate} 过。 */
    public static long execute(@Nullable final Network network,
                               final ResourceKey resource,
                               final long amount) {
        final StorageNetworkComponent storage = storageOf(network);
        if (storage == null || amount <= 0L) {
            return 0L;
        }
        return storage.insert(resource, amount, Action.EXECUTE, Actor.EMPTY);
    }

    /**
     * 全量写入一个物品堆：先 SIMULATE 确认整堆都收得下，再 EXECUTE。
     *
     * @return 是否「整堆」都进了网络（false = 一件都没写，调用方可以安全地改走掉落）
     */
    public static boolean insertItemFully(@Nullable final Network network, final ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        final ItemResource resource = ItemResource.ofItemStack(stack);
        final long amount = stack.getCount();
        if (simulate(network, resource, amount) < amount) {
            return false;
        }
        return execute(network, resource, amount) >= amount;
    }

    /**
     * 全量写入一份流体：先 SIMULATE 确认整份都收得下，再 EXECUTE。
     *
     * @return 是否「整份」都进了网络（false = 一份都没写）
     */
    public static boolean insertFluidFully(@Nullable final Network network, final FluidStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        final FluidResource resource = new FluidResource(stack.getFluid(), stack.getComponentsPatch());
        final long amount = stack.getAmount();
        if (simulate(network, resource, amount) < amount) {
            return false;
        }
        return execute(network, resource, amount) >= amount;
    }
}
