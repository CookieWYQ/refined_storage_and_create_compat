package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 「{@link MultiFluidCache} 里<b>某一个条目</b>」的 {@link IFluidHandler} 适配器：单罐、只读 + 可抽取。
 * <p><b>为什么需要它</b>：把缓存里的流体灌进玩家的容器必须走 NeoForge 的
 * {@code FluidUtil.tryFillContainer(容器, 流体源, ...)}，而它要的是一个 {@code IFluidHandler}；
 * 缓存本体不是能力对象，缓存的<b>整表</b>物流视图也不能拿来当流体源 ——
 * 整表视图按「第一条条目」应答 {@code drain(maxDrain)}，玩家点的是哪种流体它并不关心，
 * 结果就是「点岩浆、灌出水」（甚至在流体之间张冠李戴地来回换）。本适配器只认被点击的
 * 这一个条目（流体注册名 + 数据组件 NBT），因此灌进去的必然是玩家点的那种流体。</p>
 * <p><b>守恒</b>：{@code fill} 一律拒绝（返回 0，缓存不会因为「灌装」凭空多出流体）；
 * {@code drain} 的返回值恒等于缓存真实扣减量（直接采用 {@link MultiFluidCache#extract} 的返回值），
 * {@code SIMULATE} 动作完全不碰缓存 —— 任何调用路径都不会出现「报告取出的量多于实际扣减的量」。</p>
 */
public final class FluidCacheEntryHandler implements IFluidHandler {
    private final MultiFluidCache cache;
    private final ResourceLocation id;
    private final CompoundTag nbt;
    @Nullable
    private final HolderLookup.Provider registries;
    /** 本罐的「一桶」容量（mB）：既是 {@code getTankCapacity}，也是单次可抽取上限。 */
    private final int maxAmount;

    /**
     * @param cache      条目所在的缓存（非空）
     * @param id         条目流体注册名
     * @param nbt        条目数据组件补丁（与缓存内部键一致，可为 null/空 = 无组件）
     * @param registries 数据组件编解码用的注册表上下文（可为 null，退化为无注册表上下文）
     * @param maxAmount  单次最多取出的 mB（取流体给玩家时 = 一桶 = 1000）
     */
    public FluidCacheEntryHandler(final MultiFluidCache cache, final ResourceLocation id,
                                  @Nullable final CompoundTag nbt,
                                  @Nullable final HolderLookup.Provider registries,
                                  final int maxAmount) {
        this.cache = cache;
        this.id = id;
        this.nbt = nbt == null ? new CompoundTag() : nbt;
        this.registries = registries;
        this.maxAmount = Math.max(0, maxAmount);
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(final int tank) {
        final long available = available();
        if (tank != 0 || available <= 0L) {
            return FluidStack.EMPTY;
        }
        return entryStack((int) Math.min(available, maxAmount));
    }

    @Override
    public int getTankCapacity(final int tank) {
        return maxAmount;
    }

    @Override
    public boolean isFluidValid(final int tank, final FluidStack stack) {
        return false; // 只出不进：本适配器只用于把缓存里的流体灌进容器，永不接受灌入
    }

    @Override
    public int fill(final FluidStack resource, final FluidAction action) {
        return 0;
    }

    @Override
    public FluidStack drain(final FluidStack resource, final FluidAction action) {
        final FluidStack inCache = getFluidInTank(0);
        if (resource.isEmpty() || inCache.isEmpty()
            || !FluidStack.isSameFluidSameComponents(inCache, resource)) {
            return FluidStack.EMPTY; // 只要被点的那种流体：其它流体一律拿不到
        }
        return drain(Math.min(resource.getAmount(), inCache.getAmount()), action);
    }

    @Override
    public FluidStack drain(final int maxDrain, final FluidAction action) {
        final int take = (int) Math.min(Math.min(maxAmount, Math.max(0, maxDrain)), available());
        if (take <= 0) {
            return FluidStack.EMPTY;
        }
        if (action.simulate()) {
            return entryStack(take); // 模拟：只报「能取出多少」，一丝一毫都不动缓存
        }
        final long taken = cache.extract(id, nbt, take);
        return taken <= 0L ? FluidStack.EMPTY : entryStack((int) taken);
    }

    /** 该条目当前存量（mB，非负）。 */
    private long available() {
        return Math.max(0L, cache.getAmount(id, nbt));
    }

    /** 条目 → {@link FluidStack}（流体无效 / 数量非正返回 {@link FluidStack#EMPTY}）。 */
    private FluidStack entryStack(final int amount) {
        final Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY || amount <= 0) {
            return FluidStack.EMPTY;
        }
        return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid), amount,
            MarkerEntry.decodeComponents(nbt, registries));
    }
}
