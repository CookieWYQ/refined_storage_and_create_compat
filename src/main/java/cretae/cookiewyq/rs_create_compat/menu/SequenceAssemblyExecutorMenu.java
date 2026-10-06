package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 序列装配样板库菜单：54 格总样板槽（6×9，大型双箱规格）+ 玩家背包，坐标按原版双箱公式。
 * 真实存放（一格一张，放入/拿走正常消耗），不是标记复制；
 * 放入并接入网络后每一格都作为 EXTERNAL 样板注册，任意终端可对其产物发起原生自动合成。
 *
 * <p><b>这 54 格只收「序列装配总样板」</b>（判据 = 物品类型，见
 * {@link SequenceAssemblyExecutorBlockEntity#acceptsPattern}）。为什么必须由槽位自己判定：
 * 原版 {@link Slot#mayPlace} 默认<b>恒 true</b>，它<b>不</b>读容器的 {@code canPlaceItem}
 * （后者只被漏斗 / 管道的 {@code InvWrapper} 走），因此把判定只写在容器里等于没写 ——
 * 界面点击与 Shift 快速移动照样能把单元样板塞进总样板库（用户实测的缺陷）。</p>
 */
public class SequenceAssemblyExecutorMenu extends AbstractContainerMenu
    implements cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder {
    public static final int PATTERN_START = 0;
    public static final int PATTERN_ROWS = 6;
    public static final int PATTERN_SLOTS = SequenceAssemblyExecutorBlockEntity.PATTERN_SLOTS;
    public static final int PLAYER_START = PATTERN_SLOTS;

    private final SequenceAssemblyExecutorBlockEntity executor;
    /** 数据槽：0 = 红石模式（0/1/2）。 */
    private final net.minecraft.world.inventory.ContainerData data;

    public SequenceAssemblyExecutorMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public SequenceAssemblyExecutorMenu(final int id,
                                        final Inventory inventory,
                                        @Nullable final SequenceAssemblyExecutorBlockEntity executor) {
        super(RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_MENU.get(), id);
        this.executor = executor;
        this.data = executor != null ? executor.getContainerData()
            : new net.minecraft.world.inventory.SimpleContainerData(1);
        addDataSlots(data);

        final net.minecraft.world.Container inv = executor != null ? executor.getInventory()
            // 客户端镜像容器：内容由原版槽位同步写入，但「能不能放」必须与服务端同源
            // （否则客户端会先假装收下、点击后才被服务端退回）
            : new net.minecraft.world.SimpleContainer(PATTERN_SLOTS) {
                @Override
                public boolean canPlaceItem(final int index, final ItemStack stack) {
                    return SequenceAssemblyExecutorBlockEntity.acceptsPattern(stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            };
        // 总样板 54 格（9 列 × 6 行，双箱布局：首行 y=18）
        for (int row = 0; row < PATTERN_ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                final int index = row * 9 + col;
                addSlot(new PatternSlot(inv, index, 8 + col * 18, 18 + row * 18));
            }
        }
        // 玩家主物品栏（双箱：行内偏移 i = (rows-4)*18 = 36）
        // y 基准用 104 / 162（不是 103 / 161）：原版 generic_54.png 里玩家背包槽框的「内部」
        // 从精灵 y=140 起（槽框原点 139），按工程硬规则 Menu = 精灵 + 1 必须是 140。
        // 旧值会让整个玩家背包与快捷栏比背景槽框高 1px（用户实测「序列装配样板库的背包/快捷栏上移了 1 像素」），
        // 与执行舱（SequenceExecutionChamberMenu）已修正过的口径保持一致。
        final int i = (PATTERN_ROWS - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 104 + row * 18 + i));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 162 + i));
        }
    }

    public static SequenceAssemblyExecutorMenu create(final int id,
                                                      final Inventory inventory,
                                                      final SequenceAssemblyExecutorBlockEntity executor) {
        return new SequenceAssemblyExecutorMenu(id, inventory, executor);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (id == cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode.BUTTON_ID) {
            // 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）
            if (executor != null && !executor.getLevel().isClientSide()) {
                executor.setRedstoneMode(executor.getRedstoneMode().toggle());
            }
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public com.refinedmods.refinedstorage.common.support.RedstoneMode rscc$getRedstoneMode() {
        return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings.getRedstoneMode(data.get(0));
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < PLAYER_START) {
                if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_START + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!moveItemStackTo(stackInSlot, PATTERN_START, PLAYER_START, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return stack;
    }

    @Override
    public boolean stillValid(final Player player) {
        if (executor == null) {
            return true;
        }
        if (executor.getLevel() == null) {
            return true;
        }
        return executor.getLevel().getBlockEntity(executor.getBlockPos()) == executor
            && player.distanceToSqr(executor.getBlockPos().getX() + 0.5,
            executor.getBlockPos().getY() + 0.5,
            executor.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /**
     * 总样板格：唯一判定 = 「是不是序列装配总样板」（{@link SequenceAssemblyExecutorBlockEntity#acceptsPattern}）。
     *
     * <p>两端用<b>同一个</b> {@link Slot} 实现，因此「客户端以为自己能放 / 服务端拒收」的错位不存在；
     * Shift 快速移动（{@code moveItemStackTo} → {@code safeInsert}）与点击 / 拖拽全部经
     * {@link #mayPlace} 收口，不存在旁路。</p>
     */
    private static final class PatternSlot extends Slot {
        private PatternSlot(final net.minecraft.world.Container container, final int index,
                            final int x, final int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return SequenceAssemblyExecutorBlockEntity.acceptsPattern(stack);
        }

        /** 总样板一格一张（与容器同源；客户端镜像容器也照此收口）。 */
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(final ItemStack stack) {
            return 1;
        }
    }
}
