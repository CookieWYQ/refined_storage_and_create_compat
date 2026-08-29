package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 定量保持器菜单：标记槽 + 6 插件槽 + 玩家背包。
 */
public class QuantityKeeperMenu extends AbstractContainerMenu {
    /** 按钮 id：0/1 = 目标数量 -/+，2 = 销毁开关切换。 */
    private final QuantityKeeperBlockEntity keeper;
    private final ContainerData data;

    public QuantityKeeperMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public QuantityKeeperMenu(final int id,
                              final Inventory inventory,
                              @Nullable final QuantityKeeperBlockEntity keeper) {
        super(RS_Create_Compat.QUANTITY_KEEPER_MENU.get(), id);
        this.keeper = keeper;
        this.data = keeper != null ? keeper.getContainerData() : new SimpleContainerData(4);
        addDataSlots(data);

        if (keeper != null) {
            final Container container = keeper.getInventory();
            // 标记槽（第 0 格）
            addSlot(new Slot(container, 0, 8, 20));
            // 插件槽（6 格，2 列 × 3 行）
            for (int i = 0; i < 6; i++) {
                addSlot(new Slot(container, 1 + i, 62 + (i % 2) * 18, 20 + (i / 2) * 18));
            }
        }
        // 玩家主物品栏（3 行 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }
    }

    public static QuantityKeeperMenu create(final int id,
                                            final Inventory inventory,
                                            final QuantityKeeperBlockEntity keeper) {
        return new QuantityKeeperMenu(id, inventory, keeper);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (keeper != null && !keeper.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> keeper.setTargetAmount(keeper.getTargetAmount() - 1);
                case 1 -> keeper.setTargetAmount(keeper.getTargetAmount() + 1);
                case 2 -> keeper.setDestroyOverflow(!keeper.isDestroyOverflow());
                default -> {
                }
            }
        }
        return true;
    }

    public int getTargetAmount() {
        return data.get(0);
    }

    public boolean isDestroyOverflow() {
        return data.get(1) == 1;
    }

    public int getSpeedUpgradeCount() {
        return data.get(2);
    }

    public boolean hasAutocraftingUpgrade() {
        return data.get(3) == 1;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < 7) {
                // 标记/插件槽 → 玩家背包
                if (!moveItemStackTo(stackInSlot, 7, 7 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 标记槽（0）或插件槽（1-6）
                if (!moveItemStackTo(stackInSlot, 0, 7, false)) {
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
        return keeper != null
            && keeper.getLevel().getBlockEntity(keeper.getBlockPos()) == keeper
            && player.distanceToSqr(keeper.getBlockPos().getX() + 0.5,
            keeper.getBlockPos().getY() + 0.5,
            keeper.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
