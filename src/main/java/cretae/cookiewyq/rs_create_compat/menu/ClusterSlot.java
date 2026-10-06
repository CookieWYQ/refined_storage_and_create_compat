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
 * 集群库存槽：多个装填器（基础 54 格 / 高级 108 格，可混阶）的库存合并显示（内存叠加）。
 * <p>
 * 槽位通过"滚动偏移"动态映射到集群中某个装填器库存的某个格：
 * 全局槽位 = 滚动行偏移 × 每行格数 + 槽位在页内的位置；再按各装填器<b>自身的格数</b>（可变）
 * 拆出 (装填器索引, 装填器内槽位)。单个装填器时同样支持滚动浏览其全部内存。
 */
public class ClusterSlot extends Slot {
    private static final Container EMPTY = new SimpleContainer(0);

    private final List<IItemHandler> handlers;
    private final IntSupplier rowOffsetSupplier;
    /** 每行格数（= 9）。 */
    private final int cols;
    /** 该槽在"可见页"内的索引（0..可见格数-1）。 */
    private final int indexInPage;
    /** 是否按滚动偏移做全局映射（服务端真内存）；客户端空容器由数据包按固定 index 同步，恒为 false。 */
    private final boolean dynamic;
    /** 每个装填器库存的起始全局槽位（前缀和）。 */
    private final int[] starts;
    /** 集群全部装填器的总格数。 */
    private final int totalSlots;

    public ClusterSlot(final List<IItemHandler> handlers,
                       final IntSupplier rowOffsetSupplier,
                       final boolean dynamic,
                       final int cols,
                       final int indexInPage,
                       final int x,
                       final int y) {
        super(EMPTY, indexInPage, x, y);
        this.handlers = handlers;
        this.rowOffsetSupplier = rowOffsetSupplier;
        this.dynamic = dynamic;
        this.cols = cols;
        this.indexInPage = indexInPage;
        this.starts = new int[handlers.size()];
        int total = 0;
        for (int i = 0; i < handlers.size(); i++) {
            starts[i] = total;
            total += handlers.get(i).getSlots();
        }
        this.totalSlots = Math.max(total, 1);
    }

    /** 全局槽位索引（跨所有装填器连续，钳制在总格数内）。非动态槽位恒等于页内索引。 */
    private int globalSlot() {
        if (!dynamic) {
            return Math.min(indexInPage, totalSlots - 1);
        }
        final int raw = Math.max(0, rowOffsetSupplier.getAsInt()) * cols + indexInPage;
        return Math.max(0, Math.min(raw, totalSlots - 1));
    }

    /** 定位 (装填器索引, 装填器内槽位)。 */
    private Location locate() {
        final int g = globalSlot();
        int handler = 0;
        for (int i = 1; i < handlers.size(); i++) {
            if (starts[i] <= g) {
                handler = i;
            } else {
                break;
            }
        }
        return new Location(handler, g - starts[handler]);
    }

    private IItemHandler current() {
        return handlers.isEmpty() ? null : handlers.get(locate().handler());
    }

    private record Location(int handler, int slot) {
    }

    @Override
    public boolean mayPlace(final ItemStack stack) {
        final IItemHandler h = current();
        if (h == null || stack.isEmpty()) {
            return false;
        }
        return h.isItemValid(locate().slot(), stack);
    }

    @Override
    public ItemStack getItem() {
        final IItemHandler h = current();
        return h == null ? ItemStack.EMPTY : h.getStackInSlot(locate().slot());
    }

    @Override
    public void set(final ItemStack stack) {
        final IItemHandler h = current();
        if (h instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(locate().slot(), stack);
        }
        this.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        final IItemHandler h = current();
        return h == null ? 64 : h.getSlotLimit(locate().slot());
    }

    @Override
    public int getMaxStackSize(final ItemStack stack) {
        final IItemHandler h = current();
        if (h == null) {
            return Math.min(stack.getMaxStackSize(), 64);
        }
        return Math.min(stack.getMaxStackSize(), h.getSlotLimit(locate().slot()));
    }

    @Override
    public boolean mayPickup(final Player playerIn) {
        final IItemHandler h = current();
        if (h == null) {
            return false;
        }
        return !h.extractItem(locate().slot(), 1, true).isEmpty();
    }

    @Override
    public ItemStack remove(final int amount) {
        final IItemHandler h = current();
        return h == null ? ItemStack.EMPTY : h.extractItem(locate().slot(), amount, false);
    }
}
