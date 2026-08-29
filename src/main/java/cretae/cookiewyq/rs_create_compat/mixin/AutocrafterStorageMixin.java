package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * 调整一：为 RS 自动合成仓增加内部输出存储（物品 + 流体）。
 * 合成产物先尝试存入内部存储；玩家/管道可通过物品与流体能力（capability）提取。
 */
@Mixin(AutocrafterBlockEntity.class)
public abstract class AutocrafterStorageMixin {
    @Unique
    private final ItemStackHandler rscc$outputStorage = new ItemStackHandler(Config.autocrafterOutputSlots);

    @Unique
    private final FluidTank rscc$outputTank = new FluidTank(Config.autocrafterFluidCapacity, fluid -> true);

    @Inject(method = "accept", at = @At("HEAD"), cancellable = true)
    private void rscc$storeInInternalStorage(final Collection<ResourceAmount> resources,
                                             final Action action,
                                             final CallbackInfoReturnable<ExternalPatternSink.Result> cir) {
        if (!Config.autocrafterStorageEnabled) {
            return;
        }
        if (!rscc$canAcceptAll(resources)) {
            return; // 内部存储不足，走原逻辑（输出到相邻机器/网络）
        }
        if (action == Action.EXECUTE) {
            rscc$insertAll(resources);
        }
        cir.setReturnValue(ExternalPatternSink.Result.ACCEPTED);
    }

    @Unique
    private boolean rscc$canAcceptAll(final Collection<ResourceAmount> resources) {
        for (final ResourceAmount resourceAmount : resources) {
            final ResourceKey resource = resourceAmount.resource();
            final long amount = resourceAmount.amount();
            if (resource instanceof ItemResource itemResource) {
                if (!rscc$canInsertItem(itemResource.toItemStack(amount))) {
                    return false;
                }
            } else if (resource instanceof FluidResource fluidResource) {
                if (rscc$outputTank.getCapacity() - rscc$outputTank.getFluidAmount() < amount) {
                    return false;
                }
            } else {
                return false; // 不支持的类型交给原逻辑
            }
        }
        return true;
    }

    @Unique
    private boolean rscc$canInsertItem(final ItemStack stack) {
        final ItemStack simulated = stack.copy();
        ItemStack remainder = simulated;
        for (int i = 0; i < rscc$outputStorage.getSlots() && !remainder.isEmpty(); i++) {
            remainder = rscc$outputStorage.insertItem(i, remainder, true);
        }
        return remainder.isEmpty();
    }

    @Unique
    private void rscc$insertAll(final Collection<ResourceAmount> resources) {
        for (final ResourceAmount resourceAmount : resources) {
            final ResourceKey resource = resourceAmount.resource();
            final long amount = resourceAmount.amount();
            if (resource instanceof ItemResource itemResource) {
                ItemStack remainder = itemResource.toItemStack(amount);
                for (int i = 0; i < rscc$outputStorage.getSlots() && !remainder.isEmpty(); i++) {
                    remainder = rscc$outputStorage.insertItem(i, remainder, false);
                }
            } else if (resource instanceof FluidResource fluidResource) {
                rscc$outputTank.fill(new FluidStack(fluidResource.fluid(), (int) amount),
                    net.neoforged.neoforge.fluids.FluidAction.EXECUTE);
            }
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void rscc$saveStorage(final CompoundTag tag, final HolderLookup.Provider provider) {
        tag.put("rscc_output", rscc$outputStorage.serializeNBT(provider));
        final FluidStack fluid = rscc$outputTank.getFluid();
        if (!fluid.isEmpty()) {
            tag.put("rscc_output_fluid", fluid.saveOptional(provider));
        } else {
            tag.remove("rscc_output_fluid");
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void rscc$loadStorage(final CompoundTag tag, final HolderLookup.Provider provider) {
        if (tag.contains("rscc_output")) {
            rscc$outputStorage.deserializeNBT(provider, tag.getCompound("rscc_output"));
        }
        if (tag.contains("rscc_output_fluid")) {
            FluidStack.parseOptional(provider, tag.getCompound("rscc_output_fluid"))
                .ifPresent(fluid -> rscc$outputTank.setFluid(fluid));
        }
    }
}
