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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 高级蓝图加农炮装填器菜单：队列 27 格 + 库存 108 格（集群合并，翻页显示）
 * + 插件槽 6 格 + 玩家背包。
 * 多个同类型高级装填器并排时库存合并（内存叠加），通过滚动条翻页查看；
 * 高级装填器不与基础装填器合并。
 */
public class AdvancedSchematicLoaderMenu extends AbstractContainerMenu {
    public static final int COLS = 9;
    public static final int ROW_SIZE = 18;

    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 71;
    /** 升级槽起始 index。 */
    public static final int UPGRADE_START = 0;
    public static final int UPGRADE_COUNT = 6;
    /** 蓝图队列起始 index。 */
    public static final int QUEUE_START = 6;
    public static final int QUEUE_COUNT = 27;
    /** 主库存起始 index（每装填器 108 格 = 12 行 × 9；界面可见 6 行 = 54 格）。 */
    public static final int STORAGE_START = 33;
    public static final int STORAGE_COUNT = 108;
    /** 界面中可见的库存槽位数（6 行）。 */
    public static final int STORAGE_VISIBLE = 6 * COLS;
    /** 玩家背包起始 index。 */
    public static final int PLAYER_START = STORAGE_START + STORAGE_VISIBLE;

    /** 按钮 id：0/1/2/3 = 开关，4 = 队列开始/停止。 */
    private final AdvancedSchematicLoaderBlockEntity loader;
    private final ContainerData data;
    /** 集群内所有装填器的库存（含自身）；客户端重建时仅自身一个空库存。 */
    private final List<IItemHandler> clusterInventories = new ArrayList<>();
    /** 当前行偏移（0 = 从第一个装填器第一行开始）。 */
    private int rowOffset;

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

        // 集群库存：同类型（高级）装填器并排时合并显示（内存叠加），滚动条按行滚动浏览
        if (loader != null) {
            for (final var ldr : loader.collectCluster()) {
                clusterInventories.add(ldr.getInventory());
            }
        } else {
            clusterInventories.add(new ItemStackHandler(STORAGE_COUNT));
        }

        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (187,6+i*18) → Menu +1）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18));
        }
        // 蓝图队列 27 格（3 行 × 9 列，背景精灵 (8,8) → Menu (9,9)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new SlotItemHandler(queue, col + row * 9, 9 + col * 18, 9 + row * 18));
            }
        }
        // 主库存 108 格：每个装填器 12 行，可见 6 行，Screen 滚动条按行偏移浏览整个集群
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < COLS; col++) {
                addSlot(new ClusterSlot(clusterInventories, () -> rowOffset, COLS, STORAGE_COUNT,
                    col + row * COLS, 9 + col * 18, STORAGE_BASE_Y + row * ROW_SIZE));
            }
        }
        // 玩家主物品栏（3 行 × 9，背景精灵 (8,240) → Menu (9,241)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 241 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,312) → Menu (9,313)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 313));
        }
    }

    public static AdvancedSchematicLoaderMenu create(final int id,
                                                     final Inventory inventory,
                                                     final AdvancedSchematicLoaderBlockEntity loader) {
        return new AdvancedSchematicLoaderMenu(id, inventory, loader);
    }

    /** @return 集群内装填器数量（内存叠加后需要翻页的数量）。 */
    public int getClusterSize() {
        return clusterInventories.size();
    }

    /** @return 当前行偏移。 */
    public int getRowOffset() {
        return rowOffset;
    }

    public void setRowOffset(final int rowOffset) {
        // 每个装填器 12 行，可见 6 行：最大偏移 = 集群×12 - 6
        this.rowOffset = Math.max(0, Math.min(rowOffset, clusterInventories.size() * 12 - 6));
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
