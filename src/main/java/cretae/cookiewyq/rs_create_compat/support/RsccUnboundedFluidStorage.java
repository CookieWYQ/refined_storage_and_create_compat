package cretae.cookiewyq.rs_create_compat.support;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * 自动合成仓内部流体存储：<b>种类无上限</b>，总容量仍受 {@link #getCapacity()} 约束
 * （与旧的单罐 {@code FluidTank} 的总容量一致，即配置的 autocrafterFluidCapacity）。
 * <p>
 * 为什么继承 {@link FluidTank}：对外能力注册（{@code Capabilities.FluidHandler.BLOCK}）与
 * {@link RsccAutocrafterStorage} 的既有签名都用 FluidTank 类型，继承可保持调用方零改动；
 * 内部把「单罐字段」替换成「同流体一罐、多罐并存」的列表，因此所有 IFluidHandler 方法都被覆写：
 * <ul>
 *     <li>{@link #getTanks()} / {@link #getFluidInTank(int)} 呈多个罐（同流体只保留一个罐，自动合并）；</li>
 *     <li>{@link #fill(FluidStack, IFluidHandler.FluidAction)} 先并入同流体罐，新品种追加新罐；</li>
 *     <li>{@link #drain(int, IFluidHandler.FluidAction)} 排空第一个非空罐；</li>
 *     <li>{@link #drain(FluidStack, IFluidHandler.FluidAction)} 按流体 + 组件精确匹配对应罐，
 *     关闭开关时的「回流不丢物」逻辑依赖它按罐扣减。</li>
 * </ul>
 * 旧存档的 {@code rscc_output_fluid}（单流体）由 Mixin 的读档分支兜底处理。
 */
public class RsccUnboundedFluidStorage extends FluidTank {
    /** 每种流体一个罐（同流体同组件自动合并）。 */
    private final List<FluidStack> tanks = new ArrayList<>();

    public RsccUnboundedFluidStorage(final int capacity) {
        super(capacity);
    }

    // ===== 本类自己的聚合 API（容量判断 / 关闭回流用） =====

    /** 当前存储的流体总量（mB，跨全部种类求和）。 */
    public int getTotalAmount() {
        int total = 0;
        for (final FluidStack tank : tanks) {
            total += tank.getAmount();
        }
        return total;
    }

    /** 剩余容量（mB）。 */
    public int getRemainingCapacity() {
        return Math.max(0, getCapacity() - getTotalAmount());
    }

    /** 各流体罐的只读快照（副本）：回流逻辑边遍历边扣减内部列表，必须先取快照。 */
    public List<FluidStack> getTanksSnapshot() {
        final List<FluidStack> snapshot = new ArrayList<>(tanks.size());
        for (final FluidStack tank : tanks) {
            snapshot.add(tank.copy());
        }
        return snapshot;
    }

    // ===== IFluidHandler =====

    @Override
    public int getTanks() {
        return tanks.size();
    }

    @Override
    public FluidStack getFluidInTank(final int tank) {
        return tank >= 0 && tank < tanks.size() ? tanks.get(tank) : FluidStack.EMPTY;
    }

    /** 多罐共享同一份总容量，故每个罐对外报「总容量」（容量语义仍由本存储统一约束）。 */
    @Override
    public int getTankCapacity(final int tank) {
        return getCapacity();
    }

    @Override
    public boolean isFluidValid(final FluidStack stack) {
        return true;
    }

    @Override
    public boolean isFluidValid(final int tank, final FluidStack stack) {
        return true;
    }

    /** 兼容单罐视角（旧调用 / 展示用）：返回第一个罐。 */
    @Override
    public FluidStack getFluid() {
        return tanks.isEmpty() ? FluidStack.EMPTY : tanks.get(0);
    }

    @Override
    public int getFluidAmount() {
        return getTotalAmount();
    }

    /** 是否完全为空（FluidTank 的单罐语义在多罐下的正确扩展）。 */
    public boolean isEmpty() {
        return getTotalAmount() == 0;
    }

    /** 剩余容量（FluidTank#getSpace 的多罐语义）。 */
    public int getSpace() {
        return getRemainingCapacity();
    }

    /** 旧路径（单流体读档 / 测试）用：清空后放入一个罐。 */
    public void setFluid(final FluidStack stack) {
        tanks.clear();
        if (!stack.isEmpty()) {
            tanks.add(stack.copy());
        }
        onContentsChanged();
    }

    @Override
    public int fill(final FluidStack resource, final FluidAction action) {
        if (resource.isEmpty()) {
            return 0;
        }
        final int toFill = Math.min(resource.getAmount(), getRemainingCapacity());
        if (toFill <= 0) {
            return 0;
        }
        if (action.execute()) {
            for (final FluidStack tank : tanks) {
                if (!FluidStack.isSameFluidSameComponents(tank, resource)) {
                    continue;
                }
                tank.grow(toFill);
                onContentsChanged();
                return toFill;
            }
            tanks.add(resource.copyWithAmount(toFill));
            onContentsChanged();
        }
        return toFill;
    }

    /** 排空第一个非空罐（IFluidHandler 约定：一次调用只返回一种流体）。 */
    @Override
    public FluidStack drain(final int maxDrain, final FluidAction action) {
        if (maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        final int index = rscc$firstNonEmptyTank();
        if (index < 0) {
            return FluidStack.EMPTY;
        }
        return rscc$drainTank(index, Math.min(maxDrain, tanks.get(index).getAmount()), action);
    }

    /** 按流体 + 组件精确匹配罐并扣减（回流逻辑用，不会误扣其它种类）。 */
    @Override
    public FluidStack drain(final FluidStack resource, final FluidAction action) {
        if (resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        for (int i = 0; i < tanks.size(); i++) {
            if (!FluidStack.isSameFluidSameComponents(tanks.get(i), resource)) {
                continue;
            }
            return rscc$drainTank(i, Math.min(resource.getAmount(), tanks.get(i).getAmount()), action);
        }
        return FluidStack.EMPTY;
    }

    private FluidStack rscc$drainTank(final int index, final int amount, final FluidAction action) {
        final FluidStack tank = tanks.get(index);
        final FluidStack drained = tank.copyWithAmount(amount);
        if (action.execute() && amount > 0) {
            tank.shrink(amount);
            if (tank.isEmpty()) {
                tanks.remove(index);
            }
            onContentsChanged();
        }
        return drained;
    }

    private int rscc$firstNonEmptyTank() {
        for (int i = 0; i < tanks.size(); i++) {
            if (!tanks.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ===== NBT（多罐；FluidTank 的单罐键 "Fluid" 不再使用） =====

    /** 读多罐列表（键 {@code Fluids}）。 */
    public RsccUnboundedFluidStorage readFromNBT(final HolderLookup.Provider lookupProvider, final CompoundTag nbt) {
        tanks.clear();
        final ListTag list = nbt.getList("Fluids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final FluidStack parsed = FluidStack.parseOptional(lookupProvider, list.getCompound(i));
            if (!parsed.isEmpty()) {
                tanks.add(parsed);
            }
        }
        onContentsChanged();
        return this;
    }

    /** 写多罐列表（键 {@code Fluids}）。 */
    public CompoundTag writeToNBT(final HolderLookup.Provider lookupProvider, final CompoundTag nbt) {
        final ListTag list = new ListTag();
        for (final FluidStack tank : tanks) {
            if (!tank.isEmpty()) {
                list.add(tank.saveOptional(lookupProvider));
            }
        }
        nbt.put("Fluids", list);
        return nbt;
    }
}
