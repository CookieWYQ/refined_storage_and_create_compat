package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemTargetBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.support.BlockContentMode;
import cretae.cookiewyq.rs_create_compat.support.BlockContentPolicy;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import cretae.cookiewyq.rs_create_compat.support.RsccAutocrafterStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 调整一：为 RS 自动合成仓增加内部输出存储（物品 + 流体），<b>种类无上限</b>（容量上限语义不变）。
 * 合成产物先尝试存入内部存储；玩家/管道可通过物品与流体能力（capability）提取。
 * <p>种类无上限的落地方式：物品用 {@link RsccUnboundedItemStorage}（按需开槽，总容量 =
 * 配置槽位数 × 64），流体用 {@link RsccUnboundedFluidStorage}（同流体合并成一罐、多罐并存，
 * 总容量 = 配置的流体容量）。二者分别仍是 ItemStackHandler / FluidTank 的子类，
 * 因此能力注册（Capabilities.ItemHandler.BLOCK / FluidHandler.BLOCK）与开关逻辑都不需要改动。</p>
 */
@Mixin(AutocrafterBlockEntity.class)
public abstract class AutocrafterStorageMixin implements RsccAutocrafterStorage {
    /** 持久化键：内部存储开关（每台自动合成仓独立）。 */
    private static final String RSCC_TAG_STORAGE_ENABLED = "rscc_storage_enabled";
    /** 持久化键：内部流体（新格式，多罐列表）。 */
    private static final String RSCC_TAG_OUTPUT_FLUIDS = "rscc_output_fluids";
    /** 持久化键：内部流体（旧格式，单罐；仅读档时兜底兼容）。 */
    private static final String RSCC_TAG_OUTPUT_FLUID_LEGACY = "rscc_output_fluid";
    /** 持久化键：内部物品（沿用 ItemStackHandler 的 Items/Size 结构，旧存档可直接读）。 */
    private static final String RSCC_TAG_OUTPUT_ITEMS = "rscc_output";

    /** 内部物品存储：种类无上限，总容量 = 配置槽位数 × 64（旧实现每格 64，故容量语义一致）。 */
    @Unique
    private final RsccUnboundedItemStorage rscc$outputStorage =
        new RsccUnboundedItemStorage(Config.autocrafterOutputSlots * RsccUnboundedItemStorage.STACK_LIMIT);

    /** 内部流体存储：种类无上限，总容量 = 配置的流体容量（与旧单罐一致）。 */
    @Unique
    private final RsccUnboundedFluidStorage rscc$outputTank =
        new RsccUnboundedFluidStorage(Config.autocrafterFluidCapacity);


    /**
     * 每台自动合成仓独立的内部存储开关。
     * 默认值取配置项（旧存档没有 rscc_storage_enabled 键时也回落到配置值，保证兼容）。
     */
    @Unique
    private boolean rscc$storageEnabled = Config.autocrafterStorageEnabled;

    @Override
    public boolean rscc$isStorageEnabled() {
        return rscc$storageEnabled;
    }

    @Override
    public void rscc$setStorageEnabled(final boolean enabled) {
        if (this.rscc$storageEnabled != enabled) {
            this.rscc$storageEnabled = enabled;
            ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).setChanged();
        }
    }

    /**
     * 该仓当前接入的 RS 网络（未接入返回 null）；关闭内部存储时用它安全回写仓内内容。
     * <p>这里改用 RS 的公开 API {@link NetworkItemTargetBlockEntity#getNetworkForItem()}：
     * {@code AbstractBaseNetworkNodeContainerBlockEntity}（本类的父类）已实现它，语义就是
     * {@code mainNetworkNode.getNetwork()}。这样彻底绕开「@Shadow 无法定位父类字段
     * mainNetworkNode」导致的模组加载崩溃，且不依赖任何 RS 内部字段。
     */
    @Override
    @org.jetbrains.annotations.Nullable
    public com.refinedmods.refinedstorage.api.network.Network rscc$getNetwork() {
        return ((NetworkItemTargetBlockEntity) (Object) this).getNetworkForItem();
    }

    @Override
    public RsccUnboundedItemStorage rscc$getOutputStorage() {
        return rscc$outputStorage;
    }

    @Override
    public RsccUnboundedFluidStorage rscc$getOutputTank() {
        return rscc$outputTank;
    }

    /**
     * 拆方块时按全局「内容物去向」策略结算内部输出存储（物品 + 流体）。
     * <p>注入点选 {@code getDrops()}（声明在目标类自身）而不是 {@code onRemove}：RS 的
     * {@code AbstractBaseBlock#onRemove} 会调用本方法收集掉落并在创造 / 生存两种路径下都执行，
     * 因此这里注入即可覆盖「创造模式空手破坏」。</p>
     * <p><b>降级说明</b>：自动合成仓的方块物品没有可挂载「内部存储」的 NBT 挂载点
     * （RS 的方块物品不写入 BLOCK_ENTITY_DATA），因此本块在 {@code BLOCK} 档 / 「回网但无网络」
     * 时<b>退化为掉落</b>（绝不销毁，只是不写回方块物品）。</p>
     */
    @Inject(method = "getDrops", at = @At("RETURN"))
    private void rscc$disposeStoredContentsOnBreak(final CallbackInfoReturnable<NonNullList<ItemStack>> cir) {
        final AutocrafterBlockEntity self = (AutocrafterBlockEntity) (Object) this;
        final Level level = self.getLevel();
        final NonNullList<ItemStack> drops = cir.getReturnValue();
        if (!(level instanceof ServerLevel) || drops == null) {
            return;
        }
        final BlockContentMode policy = BlockContentPolicy.mode(level);
        final com.refinedmods.refinedstorage.api.network.Network network = rscc$getNetwork();

        // 物品：按全局档位；选「回网」但没有网络 → 退化为掉落
        final List<ItemStack> items = new ArrayList<>();
        BlockContentReleaser.collectHandler(rscc$outputStorage, items);
        if (policy == BlockContentMode.NETWORK && network != null) {
            drops.addAll(BlockContentReleaser.insertItems(network, items));
        } else {
            drops.addAll(items);
        }

        // 流体（硬规则）：能回网就回网，否则按桶掉出（绝不把流体喷一地）
        final List<FluidStack> fluids = new ArrayList<>();
        BlockContentReleaser.collectFluidStorage(rscc$outputTank, fluids);
        if (network != null) {
            drops.addAll(BlockContentReleaser.fluidsAsBuckets(
                BlockContentReleaser.insertFluids(network, fluids)));
        } else {
            drops.addAll(BlockContentReleaser.fluidsAsBuckets(fluids));
        }
    }

    @Inject(method = "accept", at = @At("HEAD"), cancellable = true)
    private void rscc$storeInInternalStorage(final Collection<ResourceAmount> resources,
                                             final Action action,
                                             final CallbackInfoReturnable<ExternalPatternSink.Result> cir) {
        // 读本方块实体自己的开关（而非全局配置），实现"每台自动合成仓独立"
        if (!rscc$storageEnabled) {
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
                if (rscc$outputTank.getRemainingCapacity() < amount) {
                    return false;
                }
            } else {
                return false; // 不支持的类型交给原逻辑
            }
        }
        return true;
    }

    /** 模拟插入一次：种类无上限，故单次调用即可判断总量能否全部放下。 */
    @Unique
    private boolean rscc$canInsertItem(final ItemStack stack) {
        return rscc$outputStorage.insertItem(0, stack, true).isEmpty();
    }

    @Unique
    private void rscc$insertAll(final Collection<ResourceAmount> resources) {
        for (final ResourceAmount resourceAmount : resources) {
            final ResourceKey resource = resourceAmount.resource();
            final long amount = resourceAmount.amount();
            if (resource instanceof ItemResource itemResource) {
                // 插入语义是「任意位置、按需开槽」（见 RsccUnboundedItemStorage），单次调用即可
                rscc$outputStorage.insertItem(0, itemResource.toItemStack(amount), false);
            } else if (resource instanceof FluidResource fluidResource) {
                rscc$outputTank.fill(new FluidStack(fluidResource.fluid(), (int) amount),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            }
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void rscc$saveStorage(final CompoundTag tag, final HolderLookup.Provider provider, final CallbackInfo ci) {
        tag.put(RSCC_TAG_OUTPUT_ITEMS, rscc$outputStorage.serializeNBT(provider));
        tag.putBoolean(RSCC_TAG_STORAGE_ENABLED, rscc$storageEnabled);
        if (rscc$outputTank.isEmpty()) {
            tag.remove(RSCC_TAG_OUTPUT_FLUIDS);
        } else {
            tag.put(RSCC_TAG_OUTPUT_FLUIDS, rscc$outputTank.writeToNBT(provider, new CompoundTag()));
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void rscc$loadStorage(final CompoundTag tag, final HolderLookup.Provider provider, final CallbackInfo ci) {
        if (tag.contains(RSCC_TAG_OUTPUT_ITEMS)) {
            rscc$outputStorage.deserializeNBT(provider, tag.getCompound(RSCC_TAG_OUTPUT_ITEMS));
        }
        // 旧存档没有该键：保留字段默认值（= 配置项），保证向后兼容
        if (tag.contains(RSCC_TAG_STORAGE_ENABLED)) {
            rscc$storageEnabled = tag.getBoolean(RSCC_TAG_STORAGE_ENABLED);
        }
        if (tag.contains(RSCC_TAG_OUTPUT_FLUIDS)) {
            rscc$outputTank.readFromNBT(provider, tag.getCompound(RSCC_TAG_OUTPUT_FLUIDS));
        } else if (tag.contains(RSCC_TAG_OUTPUT_FLUID_LEGACY)) {
            // 旧存档：单流体键 → 迁进新格式的唯一罐
            final FluidStack parsed =
                FluidStack.parseOptional(provider, tag.getCompound(RSCC_TAG_OUTPUT_FLUID_LEGACY));
            if (!parsed.isEmpty()) {
                rscc$outputTank.setFluid(parsed);
            }
        }
    }
}
