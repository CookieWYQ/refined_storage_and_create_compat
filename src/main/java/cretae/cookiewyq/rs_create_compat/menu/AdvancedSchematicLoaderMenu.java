package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import java.util.ArrayList;
import java.util.List;
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
 * 高级蓝图加农炮装填器菜单：队列 27 格 + 库存 108 格（仅 6 行可见，Screen 通过滚动条改 slot.y）
 * + 插件槽 6 格 + 玩家背包。
 */
public class AdvancedSchematicLoaderMenu extends AbstractContainerMenu {
    public static final int VISIBLE_ROWS = 6;
    public static final int TOTAL_ROWS = 12;
    public static final int COLS = 9;
    public static final int ROW_SIZE = 18;

    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 97;
    /** 升级槽起始 index。 */
    public static final int UPGRADE_START = 0;
    public static final int UPGRADE_COUNT = 6;
    /** 蓝图队列起始 index。 */
    public static final int QUEUE_START = 6;
    public static final int QUEUE_COUNT = 27;
    /** 主库存起始 index（12 行 × 9）。 */
    public static final int STORAGE_START = 33;
    public static final int STORAGE_COUNT = TOTAL_ROWS * COLS;
    /** 玩家背包起始 index。 */
    public static final int PLAYER_START = STORAGE_START + STORAGE_COUNT;

    private final AdvancedSchematicLoaderBlockEntity loader;
    private final ContainerData data;
    /** 主库存槽（按 menu slot 引用，Screen 用滚动条改它们的 y）。 */
    private final List<Slot> storageSlots = new ArrayList<>(STORAGE_COUNT);
    /** 主库存槽的基准 y（每行 18），Screen 用它 + scrollOffset 重算 y。 */
    private final int[] storageBaseY = new int[STORAGE_COUNT];

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

        final ItemStackHandler upgrades = loader != null ? loader.getUpgradeContainer() : new ItemStackHandler(6);
        final ItemStackHandler queue = loader != null ? loader.getQueue() : new ItemStackHandler(27);
        final ItemStackHandler loaderInv = loader != null ? loader.getInventory() : new ItemStackHandler(108);

        // 插件槽（6 格竖排，界面右侧独立栏）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 186, 5 + i * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 7 + col * 18, 29 + row * 18));
            }
        }
        // 主库存 108 格：逻辑上 12 行，初始 y 全部放到前 6 行位置，Screen 会按滚动偏移刷新
        for (int row = 0; row < TOTAL_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                final int idx = col + row * COLS;
                final int baseY = STORAGE_BASE_Y + row * ROW_SIZE;
                storageBaseY[idx] = baseY;
                // 初始 y = 基准 y（Screen 会在 init / 滚动时覆盖为基准 y - scrollOffset）
                final SlotItemHandler slot = new SlotItemHandler(loaderInv, idx, 7 + col * 18, baseY);
                storageSlots.add(slot);
                addSlot(slot);
            }
        }
        // 玩家主物品栏（3 行 × 9）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 7 + col * 18, 216 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 7 + col * 18, 274));
        }
    }

    public static AdvancedSchematicLoaderMenu create(final int id,
                                                     final Inventory inventory,
                                                     final AdvancedSchematicLoaderBlockEntity loader) {
        return new AdvancedSchematicLoaderMenu(id, inventory, loader);
    }

    /** @return 主库存槽数量。 */
    public int getStorageSlotCount() {
        return storageSlots.size();
    }

    /** 按 index 获取主库存槽。 */
    public Slot getStorageSlot(final int idx) {
        return storageSlots.get(idx);
    }

    /** 主库存槽的基准 y（未滚动时的 y）。 */
    public int getStorageBaseY(final int idx) {
        return storageBaseY[idx];
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
            final int playerEnd = PLAYER_START + 36;
            if (index < PLAYER_START) {
                if (!moveItemStackTo(stackInSlot, PLAYER_START, playerEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stackInSlot, 0, PLAYER_START, false)) {
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
