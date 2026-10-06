package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 自动合成仓内部物品存储：<b>种类无上限</b>，但总容量仍受上限约束
 * （语义与旧实现一致：旧 {@code ItemStackHandler(slots)} 每格最多 64 个 → 总容量 = slots × 64）。
 * <p>
 * 实现要点：
 * <ul>
 *     <li>每种物品占一个「槽位」，新种类自动开槽，因此种类不设上限；</li>
 *     <li>始终保留一个末尾空槽位，保证外部按 {@code getSlots()} 遍历的调用方（漏斗 / 管道）在空仓时
 *     也能正常插入（若返回 0 个槽位，这类调用方会直接放弃）；</li>
 *     <li>{@link #insertItem(int, ItemStack, boolean)} 采用「任意位置」语义：忽略传入的槽位下标，
 *     先补齐同类堆叠、再为新种类开槽。外部 for 循环逐个槽位调用同样得到正确结果（多次调用幂等，
 *     第二次起只剩「放不下」的部分原样返回）；</li>
 *     <li>{@link #extractItem(int, int, boolean)} 仍是按槽位（= 按种类）语义，与旧实现一致；</li>
 *     <li>NBT 沿用 {@link ItemStackHandler} 的 {@code Items}/{@code Size} 结构，旧存档可直接读取。</li>
 * </ul>
 */
public class RsccUnboundedItemStorage extends ItemStackHandler {
    /** 单个种类（槽位）的最大堆叠数，与旧实现的 64 一致。 */
    public static final int STACK_LIMIT = 64;

    /** 总容量（物品个数）= 旧配置槽位数 × 64。 */
    private final int capacity;

    public RsccUnboundedItemStorage(final int capacity) {
        super(1);
        this.capacity = Math.max(STACK_LIMIT, capacity);
    }

    /** 总容量（物品个数）。 */
    public int getCapacity() {
        return capacity;
    }

    /** 当前存储的物品总数（种类数少，线性求和足够快）。 */
    public int getTotalCount() {
        int total = 0;
        for (int i = 0; i < stacks.size(); i++) {
            total += stacks.get(i).getCount();
        }
        return total;
    }

    /** 剩余容量（还能装多少个物品）。 */
    public int getRemainingCapacity() {
        return Math.max(0, capacity - getTotalCount());
    }

    @Override
    public int getSlotLimit(final int slot) {
        return STACK_LIMIT;
    }

    @Override
    protected int getStackLimit(final int slot, final ItemStack stack) {
        return Math.min(STACK_LIMIT, stack.getMaxStackSize());
    }

    /**
     * 插入任意位置：先补齐同类堆叠，再为新种类开槽；总量不超过 {@link #getCapacity()}。
     * 传入的 {@code slot} 仅用于兼容 {@code IItemHandler} 契约，实际忽略（见类注释）。
     */
    @Override
    public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final int insertable = Math.min(stack.getCount(), getRemainingCapacity());
        if (insertable <= 0) {
            return stack;
        }
        if (!simulate) {
            rscc$insert(stack, insertable);
        }
        final int left = stack.getCount() - insertable;
        return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
    }

    private void rscc$insert(final ItemStack stack, final int amount) {
        int remaining = amount;
        final int limit = getStackLimit(0, stack);
        // 1) 先补同类型堆叠，尽量不增加种类数
        for (int i = 0; i < stacks.size() && remaining > 0; i++) {
            final ItemStack existing = stacks.get(i);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            final int moved = Math.min(limit - existing.getCount(), remaining);
            if (moved <= 0) {
                continue;
            }
            existing.grow(moved);
            remaining -= moved;
            onContentsChanged(i);
        }
        // 2) 余量开新槽位（种类无上限）
        while (remaining > 0) {
            final ItemStack created = stack.copyWithCount(Math.min(limit, remaining));
            remaining -= created.getCount();
            final int empty = rscc$firstEmptySlot();
            if (empty < 0) {
                final int grown = stacks.size();
                rscc$ensureSize(grown + 1);
                stacks.set(grown, created);
                onContentsChanged(grown);
            } else {
                stacks.set(empty, created);
                onContentsChanged(empty);
            }
        }
        rscc$ensureTrailingEmptySlot();
    }

    /**
     * 扩容到指定容量。
     * <p><b>不能用 {@code stacks.add/remove}</b>：{@code ItemStackHandler} 内部的 {@code NonNullList}
     * 继承自 {@code AbstractList}，{@code add(int,E)} / {@code remove(int)} 都会抛
     * {@code UnsupportedOperationException}（表现为「方块实体写入状态失败，不会持久化」）。只能整体重建。</p>
     */
    private void rscc$ensureSize(final int size) {
        if (size <= stacks.size()) {
            return;
        }
        final net.minecraft.core.NonNullList<ItemStack> next =
            net.minecraft.core.NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < stacks.size(); i++) {
            next.set(i, stacks.get(i));
        }
        stacks = next;
    }

    /** 第一个空槽位；没有则 -1。 */
    private int rscc$firstEmptySlot() {
        for (int i = 0; i < stacks.size(); i++) {
            if (stacks.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 保证末尾始终存在一个空槽位（外部按 getSlots() 遍历时的插入落点）。 */
    private void rscc$ensureTrailingEmptySlot() {
        if (stacks.isEmpty() || !stacks.get(stacks.size() - 1).isEmpty()) {
            rscc$ensureSize(stacks.size() + 1);
        }
    }

    /** 读档后同样保证「末尾有空槽位」不变式（旧存档 Size 可能不含空槽）。 */
    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        rscc$ensureTrailingEmptySlot();
    }
}
