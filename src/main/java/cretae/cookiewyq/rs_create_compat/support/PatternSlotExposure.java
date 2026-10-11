package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 样板终端「样板槽」的对外视图：把终端内部几段样板容器拼成<b>一个</b> {@link IItemHandler}。
 *
 * <h2>为什么必须拼成一个（照 RS 原版的做法，而不是各暴露一个）</h2>
 * <p>NeoForge 的方块能力是「一个方块实体类型 + 一个能力 ⇒ 一个 provider」：对同一个
 * (capability, blockEntityType) 再注册一次只会覆盖前一次。RS 原版的样板机（样板网格 = 玩家口中的
 * 「样板终端」）正是这么做的 —— {@code ModInitializer#registerCapabilities} 里
 * {@code Capabilities.ItemHandler.BLOCK + BlockEntities#getPatternGrid ⇒ new InvWrapper(be.getPatternInput())}，
 * 即「唯一一个 provider，把内部那个带准入判据的容器包成 {@code IItemHandler}」。</p>
 * <p>本模组的序列装配样板终端有<b>两段</b>样板槽（9 格总样板槽 + 3 格终端自有 RS 样板输入槽），
 * 因此这唯一一个 provider 必须返回一个<b>分段委托</b>视图：对外下标 0..8 → 总样板槽，
 * 9..11 → RS 样板输入槽。这样 RS 输出总线（{@code Exporter}）与 Jade 各自的<b>那一次</b>能力查询里
 * 两段样板槽都在，而每一段的准入判据仍由<b>它自己那个 handler</b> 决定 —— 本类一个判据都不重写。</p>
 *
 * <h2>为什么只实现 {@link IItemHandler}，不实现 {@code IItemHandlerModifiable}</h2>
 * <p>RS 的 {@code InvWrapper} 是可改写的，但本模组<b>刻意不</b>把可改写接口交出去：
 * 准入判据（总样板槽只收本模组总样板 / RS 样板槽只收 {@code refinedstorage:pattern}）的唯一落点是
 * 各段 handler 的 {@code isItemValid}；{@code insertItem} 会问它，而
 * {@code setStackInSlot} <b>不会</b>（NeoForge 的 {@code ItemStackHandler#setStackInSlot} 不做校验）。
 * 一旦交出可改写接口，任何第三方（或将来的本模组代码）都能绕开判据往样板槽里塞东西 ——
 * 那正是本轮要避免的「因为暴露了就什么都收」。因此这里与工程既有的
 * {@link ExtractOnlyHandlers.Item} 同一套写法：只给物流 / 提示真正需要的 {@link IItemHandler}，
 * 写入一律走 {@code insertItem} ⇒ 判据<b>没有旁路</b>。</p>
 *
 * <h2>不动内部语义</h2>
 * <p>本视图<b>只</b>是委托：读写最终都落到终端真实的 handler 上，因此
 * {@code onContentsChanged}（总样板槽「可见格被取空 ⇒ 隐藏格前移」）、NBT 序列化、掉落清单、
 * 界面槽位一律不受影响。方块实体自己的逻辑（生成 / 消耗 / 判重）也依旧直接读真实 handler，
 * 不经本视图。段容量用 {@link IItemHandler#getSlots()} <b>现算</b>，不缓存 ⇒ 将来某段真的改了容量，
 * 这里也不会算错下标。</p>
 */
public final class PatternSlotExposure {
    private PatternSlotExposure() {
    }

    /**
     * 按顺序把若干段拼成一个对外 handler（对外下标 = 数组顺序，段间不留空隙）。
     *
     * @param segments 各段真实容器（本模组为 总样板槽 → RS 样板输入槽），null 元素按 0 格处理
     */
    public static IItemHandler of(final IItemHandler... segments) {
        return new Item(segments);
    }

    /** 分段委托视图：对外下标换算成 (段, 段内下标)，其余一律原样委托。 */
    public static final class Item implements IItemHandler {
        private final IItemHandler[] segments;

        private Item(final IItemHandler[] segments) {
            this.segments = segments.clone();
            for (int i = 0; i < this.segments.length; i++) {
                if (this.segments[i] == null) {
                    // null 段会让下面每一处都 NPE；换成 0 格视图，语义 = 「这一段不存在」
                    this.segments[i] = new Empty();
                }
            }
        }

        /** 第 {@code segment} 段的起始对外下标（前若干段容量之和）。 */
        private int startOf(final int segment) {
            int cursor = 0;
            for (int i = 0; i < segment && i < segments.length; i++) {
                cursor += segments[i].getSlots();
            }
            return cursor;
        }

        /** 对外下标落在第几段；越界返回 -1（调用方据此「原样退回 / 返回空」）。 */
        private int segmentOf(final int slot) {
            if (slot < 0) {
                return -1;
            }
            for (int i = 0; i < segments.length; i++) {
                if (slot < startOf(i + 1)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getSlots() {
            int total = 0;
            for (final IItemHandler segment : segments) {
                total += segment.getSlots();
            }
            return total;
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            final int segment = segmentOf(slot);
            return segment < 0
                ? ItemStack.EMPTY
                : segments[segment].getStackInSlot(slot - startOf(segment));
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            final int segment = segmentOf(slot);
            // 越界：原样退回（既不吞物品，也不假装收下）
            return segment < 0
                ? stack
                : segments[segment].insertItem(slot - startOf(segment), stack, simulate);
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            final int segment = segmentOf(slot);
            return segment < 0
                ? ItemStack.EMPTY
                : segments[segment].extractItem(slot - startOf(segment), amount, simulate);
        }

        @Override
        public int getSlotLimit(final int slot) {
            final int segment = segmentOf(slot);
            return segment < 0 ? 0 : segments[segment].getSlotLimit(slot - startOf(segment));
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            final int segment = segmentOf(slot);
            return segment >= 0 && segments[segment].isItemValid(slot - startOf(segment), stack);
        }

        /** null 段的替身：0 格、什么都不收。 */
        private static final class Empty implements IItemHandler {
            @Override
            public int getSlots() {
                return 0;
            }

            @Override
            public ItemStack getStackInSlot(final int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
                return stack;
            }

            @Override
            public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
                return ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(final int slot) {
                return 0;
            }

            @Override
            public boolean isItemValid(final int slot, final ItemStack stack) {
                return false;
            }
        }
    }
}
