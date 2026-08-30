package cretae.cookiewyq.rs_create_compat.menu;

import java.util.List;
import java.util.function.IntSupplier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 集群库存槽：多个同类型装填器并排时，库存合并显示（内存叠加）。
 * <p>
 * 槽位通过"滚动偏移"动态映射到集群中的某个装填器库存槽：
 * 全局槽位 = 滚动行偏移 × 每行格数 + 槽位在页内的位置；
 * 然后按 每装填器格数 拆分出 (装填器索引, 装填器内槽位)。
 * 因此同一套固定槽位可以浏览整个集群的全部库存（含每个装填器自己的多行）。
 */
public class ClusterSlot extends Slot {
    private static final Container EMPTY = new SimpleContainer(0);

    private final List<IItemHandler> handlers;
    private final IntSupplier rowOffsetSupplier;
    /** 每行格数（= 9）。 */
    private final int cols;
    /** 每个装填器的库存格数。 */
    private final int slotsPerHandler;
    /** 该槽在"可见页"内的索引（0..可见格数-1）。 */
    private final int indexInPage;

    public ClusterSlot(final List<IItemHandler> handlers,
                       final IntSupplier rowOffsetSupplier,
                       final int cols,
                       final int slotsPerHandler,
                       final int indexInPage,
                       final int x,
                       final int y) {
        super(EMPTY, indexInPage, x, y);
        this.handlers = handlers;
        this.rowOffsetSupplier = rowOffsetSupplier;
        this.cols = cols;
        this.slotsPerHandler = slotsPerHandler;
        this.indexInPage = indexInPage;
    }

    /** 全局槽位索引（跨所有装填器连续）。 */
    private int globalSlot() {
        return Math.max(0, rowOffsetSupplier.getAsInt()) * cols + indexInPage;
    }

    private IItemHandler current() {
        if (handlers.isEmpty()) {
            return null;
        }
        final int global = globalSlot();
        final int handlerIndex = global / slotsPerHandler;
        final int clamped = Math.max(0, Math.min(handlerIndex, handlers.size() - 1));
        return handlers.get(clamped);
    }

    /** 当前实际指向的装填器内槽位索引。 */
    private int slotInHandler() {
        final int global = globalSlot();
        return global % slotsPerHandler;
    }

    @Override
    public boolean mayPlace(final ItemStack stack) {
        final IItemHandler h = current();
        return h != null && !stack.isEmpty() && h.isItemValid(slotInHandler(), stack);
    }

    @Override
    public ItemStack getItem() {
        final IItemHandler h = current();
        return h == null ? ItemStack.EMPTY : h.getStackInSlot(slotInHandler());
    }

    @Override
    public void set(final ItemStack stack) {
        final IItemHandler h = current();
        if (h instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(slotInHandler(), stack);
        }
        this.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        final IItemHandler h = current();
        return h == null ? 64 : h.getSlotLimit(slotInHandler());
    }

    @Override
    public int getMaxStackSize(final ItemStack stack) {
        final IItemHandler h = current();
        if (h == null) {
            return Math.min(stack.getMaxStackSize(), 64);
        }
        return Math.min(stack.getMaxStackSize(), h.getSlotLimit(slotInHandler()));
    }

    @Override
    public boolean mayPickup(final Player playerIn) {
        final IItemHandler h = current();
        return h != null && !h.extractItem(slotInHandler(), 1, true).isEmpty();
    }

    @Override
    public ItemStack remove(final int amount) {
        final IItemHandler h = current();
        return h == null ? ItemStack.EMPTY : h.extractItem(slotInHandler(), amount, false);
    }
}
