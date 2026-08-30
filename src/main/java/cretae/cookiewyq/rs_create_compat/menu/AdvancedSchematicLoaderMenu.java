package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 高级蓝图加农炮装填器菜单：队列 27 格 + 库存 108 格 + 插件槽 6 格 + 玩家背包。
 */
public class AdvancedSchematicLoaderMenu extends AbstractContainerMenu {
    /** 按钮 id：0/1/2/3 = 开关，4 = 队列开始/停止。 */
    private final AdvancedSchematicLoaderBlockEntity loader;
    private final ContainerData data;

    public AdvancedSchematicLoaderMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public AdvancedSchematicLoaderMenu(final int id,
                                       final Inventory inventory,
                                       @Nullable final AdvancedSchematicLoaderBlockEntity loader) {
        super(RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_MENU.get(), id);
        this.loader = loader;
        this.data = loader != null ? loader.getContainerData() : new SimpleContainerData(5);
        addDataSlots(data);

        // 客户端重建时使用空容器，保证槽位数与服务端一致（内容由数据包同步）
        final ItemStackHandler upgrades = loader != null ? loader.getUpgradeContainer() : new ItemStackHandler(6);
        final ItemStackHandler queue = loader != null ? loader.getQueue() : new ItemStackHandler(27);
        final ItemStackHandler loaderInv = loader != null ? loader.getInventory() : new ItemStackHandler(108);
        // 插件槽（6 格，2 列 × 3 行，右上角）
        for (int i = 0; i < 6; i++) {
            addSlot(new SlotItemHandler(upgrades, i, 124 + (i % 2) * 18, 8 + (i / 2) * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 8 + col * 18, 30 + row * 18));
            }
        }
        // 主库存 108 格（12 行 × 9 列）
        for (int row = 0; row < 12; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(loaderInv, col + row * 9, 8 + col * 18, 98 + row * 18));
            }
        }
        // 玩家主物品栏
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 332 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 390));
        }
    }

    public static AdvancedSchematicLoaderMenu create(final int id,
                                                     final Inventory inventory,
                                                     final AdvancedSchematicLoaderBlockEntity loader) {
        return new AdvancedSchematicLoaderMenu(id, inventory, loader);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (loader != null && !loader.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> loader.setAutoPrint(!loader.isAutoPrint());
                case 1 -> loader.setAutoDeploy(!loader.isAutoDeploy());
                case 2 -> loader.setAutoRecycle(!loader.isAutoRecycle());
                case 3 -> loader.setAutoFillGunpowder(!loader.isAutoFillGunpowder());
                case 4 -> loader.toggleQueue();
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

    public boolean isQueueRunning() {
        return data.get(4) == 1;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < 141) {
                if (!moveItemStackTo(stackInSlot, 141, 141 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stackInSlot, 0, 141, false)) {
                return ItemStack.EMPTY;
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
