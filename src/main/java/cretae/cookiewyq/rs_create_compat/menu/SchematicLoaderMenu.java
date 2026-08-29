package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 蓝图加农炮装填器菜单：54 格库存 + 蓝图槽 + 6 插件槽 + 玩家背包。
 */
public class SchematicLoaderMenu extends AbstractContainerMenu {
    /** 按钮 id：0/1/2/3 = 自动打印/部署/回收/火药 开关。 */
    private final SchematicLoaderBlockEntity loader;
    private final ContainerData data;

    public SchematicLoaderMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public SchematicLoaderMenu(final int id,
                               final Inventory inventory,
                               @Nullable final SchematicLoaderBlockEntity loader) {
        super(RS_Create_Compat.SCHEMATIC_LOADER_MENU.get(), id);
        this.loader = loader;
        this.data = loader != null ? loader.getContainerData() : new SimpleContainerData(4);
        addDataSlots(data);

        if (loader != null) {
            // 蓝图槽
            addSlot(new SlotItemHandler(loader.getBlueprintSlot(), 0, 8, 8));
            // 插件槽（6 格横排）
            for (int i = 0; i < 6; i++) {
                addSlot(new SlotItemHandler(loader.getUpgradeContainer(), i, 35 + i * 18, 8));
            }
            // 主库存 54 格（6 行 × 9 列）
            for (int row = 0; row < 6; row++) {
                for (int col = 0; col < 9; col++) {
                    addSlot(new SlotItemHandler(loader.getInventory(), col + row * 9, 8 + col * 18, 26 + row * 18));
                }
            }
        }
        // 玩家主物品栏
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        }
    }

    public static SchematicLoaderMenu create(final int id,
                                             final Inventory inventory,
                                             final SchematicLoaderBlockEntity loader) {
        return new SchematicLoaderMenu(id, inventory, loader);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (loader != null && !loader.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> loader.setAutoPrint(!loader.isAutoPrint());
                case 1 -> loader.setAutoDeploy(!loader.isAutoDeploy());
                case 2 -> loader.setAutoRecycle(!loader.isAutoRecycle());
                case 3 -> loader.setAutoFillGunpowder(!loader.isAutoFillGunpowder());
                default -> {
                }
            }
        }
        return true;
    }

    public boolean isAutoPrint() {
        return data.get(0) == 1;
    }

    public boolean isAutoDeploy() {
        return data.get(1) == 1;
    }

    public boolean isAutoRecycle() {
        return data.get(2) == 1;
    }

    public boolean isAutoFillGunpowder() {
        return data.get(3) == 1;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < 61) {
                // 装填器槽位 → 玩家背包
                if (!moveItemStackTo(stackInSlot, 61, 61 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 装填器
                if (!moveItemStackTo(stackInSlot, 0, 61, false)) {
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
        return loader != null
            && loader.getLevel().getBlockEntity(loader.getBlockPos()) == loader
            && player.distanceToSqr(loader.getBlockPos().getX() + 0.5,
            loader.getBlockPos().getY() + 0.5,
            loader.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
