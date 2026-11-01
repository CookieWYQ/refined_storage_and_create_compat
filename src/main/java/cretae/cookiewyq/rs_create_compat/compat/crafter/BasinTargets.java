package cretae.cookiewyq.rs_create_compat.compat.crafter;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.item.SmartInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 动力搅拌机（Mechanical Mixer）+ 搅拌盆（Basin）侧的输入派发。
 *
 * <h2>为什么盆的输入不需要「按位置」</h2>
 * <p>Create 的 mixing 配方是<b>无序</b>的：{@code BasinRecipe.apply} 只是对
 * {@code recipe.getIngredients()} 逐个去盆里「找到就抽 1 个」
 * （build/createsrc/.../basin/BasinRecipe.java:80-120），盆的输入背包 9 格、每种物品独占一格
 * （build/createsrc/.../basin/BasinBlockEntity.java:111-116 + BasinInventory.java:25-41）。
 * 因此只要把物品/流体塞进盆的输入侧即可，位置无关。</p>
 *
 * <h2>为什么仍然要自己塞（而不是让 RS 原版 sink 干）</h2>
 * <p>自动合成仓的「内部存储」mixin（{@code AutocrafterStorageMixin}）在默认配置下是<b>开启</b>的，
 * 它会在 {@code AutocrafterBlockEntity#accept} 的 HEAD 处把整批输入截走存进仓内，于是 RS 原版
 * item/fluid sink 根本收不到调用。为了让「对着搅拌机的自动合成仓」在默认配置下也能真正驱动机器，
 * 本类在<b>更外层</b>（网络节点的 {@code accept}）就完成派发；一旦派发成功，内层入口不会再被调用，
 * 因此不存在同一批料写两处的可能。</p>
 *
 * <h2>全有或全无</h2>
 * <p>{@link #canAcceptAll} 只做 SIMULATE；只有它返回 true 才允许 {@link #acceptAll} 执行。
 * 这样「盆满了 / 输入罐不够」时不会出现「物品进去了、流体没进去」的半截状态。</p>
 */
public final class BasinTargets {
    /** 一次 drain 的询问量（盆的输出罐单罐容量 1000 mB，取 4000 只为一次问出可用量）。 */
    private static final int DRAIN_PROBE = 4000;

    private BasinTargets() {
    }

    /** 资源是否全部是本类认识的类型（物品 / 流体）；不含未知类型才允许本类接管。 */
    public static boolean isApplicable(final Collection<ResourceAmount> resources) {
        if (resources.isEmpty()) {
            return false;
        }
        for (final ResourceAmount resourceAmount : resources) {
            if (!(resourceAmount.resource() instanceof ItemResource)
                && !(resourceAmount.resource() instanceof FluidResource)) {
                return false;
            }
        }
        return true;
    }

    /** 只读模拟：盆的输入侧能否整批收下。 */
    public static boolean canAcceptAll(final BasinBlockEntity basin,
                                      final Collection<ResourceAmount> resources) {
        return dispatch(basin, resources, true);
    }

    /** 执行派发；调用方必须先用 {@link #canAcceptAll} 模拟通过。 */
    public static boolean acceptAll(final BasinBlockEntity basin,
                                    final Collection<ResourceAmount> resources) {
        return dispatch(basin, resources, false);
    }

    private static boolean dispatch(final BasinBlockEntity basin,
                                    final Collection<ResourceAmount> resources,
                                    final boolean simulate) {
        final List<ItemStack> items = new ArrayList<>();
        final List<FluidStack> fluids = new ArrayList<>();
        for (final ResourceAmount resourceAmount : resources) {
            if (resourceAmount.resource() instanceof ItemResource itemResource) {
                items.add(itemResource.toItemStack(resourceAmount.amount()));
            } else if (resourceAmount.resource() instanceof FluidResource fluidResource) {
                fluids.add(VariantUtil.toFluidStack(fluidResource, resourceAmount.amount()));
            } else {
                return false; // 未知资源类型：交回 RS 原语义
            }
        }
        final Level level = basin.getLevel();
        if (level == null) {
            return false;
        }
        final IItemHandler itemHandler = level.getCapability(
            Capabilities.ItemHandler.BLOCK, basin.getBlockPos(), null);
        final IFluidHandler fluidHandler = level.getCapability(
            Capabilities.FluidHandler.BLOCK, basin.getBlockPos(), null);
        if (!items.isEmpty() && itemHandler == null) {
            return false;
        }
        if (!fluids.isEmpty() && fluidHandler == null) {
            return false;
        }
        if (itemHandler != null && !insertItems(itemHandler, items, simulate)) {
            return false;
        }
        return fluidHandler == null || insertFluids(fluidHandler, fluids, simulate);
    }

    /**
     * 物品插入：与 RS 通用 item sink 的「按第一个能收的槽位顺序塞」保持一致
     * （build/rssrc/.../ItemHandlerExternalPatternProviderSink.java:96-123），
     * 只是这里先 SIMULATE 整批、再 EXECUTE 整批。
     */
    private static boolean insertItems(final IItemHandler handler,
                                       final List<ItemStack> stacks,
                                       final boolean simulate) {
        if (stacks.isEmpty()) {
            return true;
        }
        final List<Integer> available = new ArrayList<>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            available.add(slot);
        }
        for (final ItemStack stack : stacks) {
            ItemStack current = stack.copy();
            while (!current.isEmpty()) {
                boolean progressed = false;
                for (int i = 0; i < available.size(); i++) {
                    final int slot = available.get(i);
                    final ItemStack remainder = handler.insertItem(slot, current.copy(), simulate);
                    if (remainder.getCount() != current.getCount()) {
                        available.remove(i);
                        current = remainder;
                        progressed = true;
                        break;
                    }
                }
                if (!progressed) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 流体插入：与 RS 通用 fluid sink 一致（整份 fill 成功才算接受）。 */
    private static boolean insertFluids(final IFluidHandler handler,
                                        final List<FluidStack> stacks,
                                        final boolean simulate) {
        final IFluidHandler.FluidAction action = simulate
            ? IFluidHandler.FluidAction.SIMULATE
            : IFluidHandler.FluidAction.EXECUTE;
        for (final FluidStack stack : stacks) {
            final int filled = handler.fill(stack.copy(), action);
            if (filled != stack.getAmount()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 产物采集
    // ------------------------------------------------------------------

    /**
     * 把盆的<b>输出侧</b>（产物背包 + 输出罐）搬进 RS 网络。
     *
     * <p>只读输出侧：输入背包/输入罐完全不碰（{@code BasinBlockEntity#getOutputInventory}
     * 与 {@code getTanks().getSecond()} 都是 public），因此不会抢走还没反应的输入料。</p>
     *
     * @return 本次是否有任何内容进网（仅供日志判断）
     */
    public static boolean collectOutputs(final BasinBlockEntity basin, final Network network) {
        boolean moved = false;
        moved |= collectItems(basin, network);
        moved |= collectFluids(basin, network);
        return moved;
    }

    private static boolean collectItems(final BasinBlockEntity basin, final Network network) {
        final SmartInventory output = basin.getOutputInventory();
        if (output == null) {
            return false;
        }
        boolean moved = false;
        for (int slot = 0; slot < output.getSlots(); slot++) {
            final ItemStack stack = output.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 顺序很重要：先 SIMULATE 问网络、再真正从盆里抽出、最后 EXECUTE 写网络。
            // 反过来（先写网络再抽）一旦抽出量不足就会「复制」。
            final ItemStack probe = output.extractItem(slot, stack.getCount(), true);
            if (probe.isEmpty()) {
                continue;
            }
            final ItemResource resource = ItemResource.ofItemStack(probe);
            if (NetworkIo.simulate(network, resource, probe.getCount()) < probe.getCount()) {
                continue; // 网络收不下：留在盆里，绝不销毁
            }
            final ItemStack extracted = output.extractItem(slot, probe.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }
            if (NetworkIo.execute(network, resource, extracted.getCount()) < extracted.getCount()) {
                // 理论上不会发生（SIMULATE 已通过）；真发生也只打日志，不假装成功
                CreateCraftLog.warn("搅拌盆产物写网不完整：%s x%d（余量已随盆内抽取丢失，与 RS 原版 sink 同风险）",
                    extracted.getItem(), extracted.getCount());
            }
            moved = true;
        }
        return moved;
    }

    private static boolean collectFluids(final BasinBlockEntity basin, final Network network) {
        // 防御：tanks 这对引用在构造器里创建，但两个 behaviour 直到 addBehaviours 才赋值，
        // 极端情况下（方块实体刚建、behaviour 未就绪）可能取到 null。
        final SmartFluidTankBehaviour outputBehaviour =
            basin.getTanks() == null ? null : basin.getTanks().getSecond();
        final IFluidHandler outputTank = outputBehaviour == null ? null : outputBehaviour.getCapability();
        if (outputTank == null) {
            return false;
        }
        boolean moved = false;
        for (int guard = 0; guard < 32; guard++) {
            final FluidStack probe = outputTank.drain(DRAIN_PROBE, IFluidHandler.FluidAction.SIMULATE);
            if (probe.isEmpty()) {
                break;
            }
            final FluidResource resource = new FluidResource(probe.getFluid(), probe.getComponentsPatch());
            if (NetworkIo.simulate(network, resource, probe.getAmount()) < probe.getAmount()) {
                break; // 网络收不下：留在罐里
            }
            final FluidStack drained = outputTank.drain(probe.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                break;
            }
            if (NetworkIo.execute(network, resource, drained.getAmount()) < drained.getAmount()) {
                CreateCraftLog.warn("搅拌盆输出流体写网不完整：%s %dmB（余量已随罐内抽取丢失）",
                    drained.getFluid(), drained.getAmount());
            }
            moved = true;
            if (drained.getAmount() < probe.getAmount()) {
                break;
            }
        }
        return moved;
    }
}
