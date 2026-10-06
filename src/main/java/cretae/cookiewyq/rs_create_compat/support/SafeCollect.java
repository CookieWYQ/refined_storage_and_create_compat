package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * 收集 / 搬运资源的<b>统一安全语义</b>（服务端权威，唯一一份实现）。
 *
 * <h2>铁律：绝不销毁玩家资源</h2>
 * 任何「把资源从 A 搬到 B」的动作都必须满足下面三条，顺序不能颠倒：
 * <ol>
 *     <li><b>先算能收多少</b>：对目标侧做 {@code SIMULATE}（网络用 {@link Action#SIMULATE}，
 *     {@code IItemHandler} / {@code IFluidHandler} 用 {@code simulate = true}），拿到「实际能收下的量」；</li>
 *     <li><b>再按实际能收的量抽取</b>：目标收不下就<b>一点都不抽</b>（缓存满 = 停手，不是先抽了再说）；</li>
 *     <li><b>执行不足要回滚</b>：{@code EXECUTE} 阶段若少于模拟量，差额必须还回来源（还回不去则交给
 *     调用方给的兜底出口，绝不静默吞掉）。</li>
 * </ol>
 *
 * <h2>为什么需要这一步</h2>
 * 旧的写法普遍是「先把源删掉 / 抽走，再插入自身或网络，插入失败也不看返回值」——
 * 目标一满，被抽走的那份就凭空消失（玩家视角：东西被机器吃了）。本类把这些位置统一成
 * 「先 SIMULATE → 只搬能搬的 → 差额回滚」，于是「缓存满」只会表现为<b>什么都不做</b>。
 *
 * <h2>兜底出口（overflow）</h2>
 * 回滚也可能失败（例如来源格在同一 tick 被别的逻辑占满）。此时绝不丢：调用方通过
 * {@code overflow} 把残料接到「落回世界 / 落回自己存储」等兜底归宿。
 */
public final class SafeCollect {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SafeCollect() {
    }

    /** 网络对某资源的可接纳量（SIMULATE，绝对不改动网络状态；{@code wanted <= 0} 或未接网络返回 0）。 */
    public static long networkAcceptable(final StorageNetworkComponent net, final ResourceKey resource,
                                         final long wanted) {
        if (net == null || resource == null || wanted <= 0L) {
            return 0L;
        }
        return Math.max(0L, Math.min(wanted, net.insert(resource, wanted, Action.SIMULATE, Actor.EMPTY)));
    }

    /**
     * 把「来源容器某格」的物品写回 RS 网络：<b>先算网络容量 → 只抽能收下的量 → 执行 → 差额回滚</b>。
     *
     * @return 网络实际收下的数量；{@code 0} = 网络已满，来源<b>一个也没动</b>
     */
    public static int pushItemsToNetwork(final StorageNetworkComponent net, final IItemHandler source,
                                         final int slot, final int amount,
                                         final Consumer<ItemStack> overflow) {
        if (net == null || source == null || amount <= 0 || slot < 0) {
            return 0;
        }
        final ItemStack inSlot = source.getStackInSlot(slot);
        if (inSlot.isEmpty()) {
            return 0;
        }
        final int want = Math.min(amount, inSlot.getCount());
        final ResourceKey resource = new ItemResource(inSlot.getItem(), inSlot.getComponentsPatch());
        final long acceptable = networkAcceptable(net, resource, want);
        if (acceptable <= 0L) {
            return 0; // 网络满：不抽（缓存满时立即停手）
        }
        final ItemStack taken = source.extractItem(slot, (int) acceptable, false);
        if (taken.isEmpty()) {
            return 0;
        }
        final long inserted = net.insert(ItemResource.ofItemStack(taken), taken.getCount(),
            Action.EXECUTE, Actor.EMPTY);
        if (inserted < taken.getCount()) {
            // 执行量少于模拟量（极端）：差额回滚回来源格；来源也放不回则交给兜底出口，绝不静默吞掉
            returnItems(source, slot, taken.copyWithCount((int) (taken.getCount() - inserted)), overflow);
        }
        return (int) Math.max(0L, inserted);
    }

    /**
     * 把「外部容器某格」的物品搬进自身存储：<b>先模拟自身容量 → 只抽能收下的量 → 插入 → 差额回滚回来源</b>。
     *
     * @return 实际搬进自身存储的数量；{@code 0} = 自身已满 / 来源为空，两边都没动
     */
    public static int pullItems(final IItemHandler source, final int slot, final IItemHandler store,
                                final int limit, final Consumer<ItemStack> overflow) {
        if (source == null || store == null || limit <= 0 || slot < 0) {
            return 0;
        }
        final ItemStack probe = source.extractItem(slot, limit, true);
        if (probe.isEmpty()) {
            return 0;
        }
        final ItemStack notStorable = insertAll(store, probe, true);
        final int acceptable = probe.getCount() - notStorable.getCount();
        if (acceptable <= 0) {
            return 0; // 自身缓存满：不抽
        }
        final ItemStack taken = source.extractItem(slot, acceptable, false);
        if (taken.isEmpty()) {
            return 0;
        }
        final ItemStack leftover = insertAll(store, taken, false);
        final int moved = taken.getCount() - leftover.getCount();
        if (!leftover.isEmpty()) {
            // 执行量少于模拟量（极端）：差额回滚回来源格，绝不销毁
            returnItems(source, slot, leftover, overflow);
        }
        return moved;
    }

    /**
     * 把「外部流体容器」的流体搬进自身的流体容器：<b>先模拟自身剩余容量 → 只抽能收下的量 → 灌入 → 差额回滚</b>。
     *
     * @return 实际搬进自身容器的 mB；{@code 0} = 自身已满 / 来源为空，两边都没动
     */
    public static int pullFluid(final IFluidHandler source, final IFluidHandler store, final int limit,
                                final LongConsumer overflow) {
        if (source == null || store == null || limit <= 0) {
            return 0;
        }
        final FluidStack probe = source.drain(limit, IFluidHandler.FluidAction.SIMULATE);
        if (probe.isEmpty()) {
            return 0;
        }
        final int acceptable = store.fill(probe, IFluidHandler.FluidAction.SIMULATE);
        if (acceptable <= 0) {
            return 0; // 自身缓存满：不抽
        }
        final FluidStack drained = source.drain(probe.copyWithAmount(acceptable),
            IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            return 0;
        }
        final int filled = store.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            final FluidStack rest = drained.copyWithAmount(drained.getAmount() - filled);
            final int returned = source.fill(rest, IFluidHandler.FluidAction.EXECUTE);
            if (returned < rest.getAmount() && overflow != null) {
                overflow.accept(rest.getAmount() - (long) returned); // 交接给调用方的兜底归宿
            }
        }
        return Math.max(0, filled);
    }

    /** 依次尝试把物品插入目标所有槽位（对「任意位置」语义的存储同样正确），返回放不下的剩余。 */
    private static ItemStack insertAll(final IItemHandler store, final ItemStack stack,
                                       final boolean simulate) {
        ItemStack remainder = stack;
        for (int i = 0; i < store.getSlots() && !remainder.isEmpty(); i++) {
            remainder = store.insertItem(i, remainder, simulate);
        }
        return remainder;
    }

    /** 把回滚残料还回来源格；来源放不下则走兜底出口（拿不准时宁可记一条日志也不静默丢弃）。 */
    private static void returnItems(final IItemHandler source, final int slot, final ItemStack rest,
                                    final Consumer<ItemStack> overflow) {
        if (rest.isEmpty()) {
            return;
        }
        final ItemStack rejected = source.insertItem(slot, rest, false);
        if (rejected.isEmpty()) {
            return;
        }
        if (overflow != null) {
            overflow.accept(rejected);
            return;
        }
        LOGGER.warn("[rs_create_compat] 收集回滚失败且没有兜底出口，物品留在来源容器外将无法交付：{} x{}",
            rejected.getItem(), rejected.getCount());
    }
}
