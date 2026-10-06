package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 「只出不进」的物流能力包装（用户需求 B18 的公共实现）。
 * <p>自动合成仓与序列执行仓的内部存储都<b>只允许被物流抽取</b>：漏斗 / 管道可以往外拿，
 * 但 {@code insertItem} / {@code fill} 一律被拒绝（原样退回，绝不吞掉输入物）。</p>
 * <p>包装只作用于<b>对外能力</b>；方块实体内部的合成 / 收集逻辑直接操作真实存储，不受影响。</p>
 */
public final class ExtractOnlyHandlers {
    private ExtractOnlyHandlers() {
    }

    /** 物品：插入一律拒绝（返回原栈），其余委托给真实存储。 */
    public static final class Item implements IItemHandler {
        private final IItemHandler delegate;

        public Item(final IItemHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getSlots() {
            return delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            return delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            return delegate.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(final int slot) {
            return delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return false;
        }
    }

    /** 流体：{@code fill} 一律拒绝，只允许 {@code drain}。 */
    public static final class Fluid implements IFluidHandler {
        private final IFluidHandler delegate;

        public Fluid(final IFluidHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getTanks() {
            return delegate.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            return delegate.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(final int tank) {
            return delegate.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return false;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            return delegate.drain(resource, action);
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return delegate.drain(maxDrain, action);
        }
    }
}
