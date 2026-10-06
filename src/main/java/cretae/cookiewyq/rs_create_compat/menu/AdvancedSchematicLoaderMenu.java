package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
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
import org.jetbrains.annotations.Nullable;

/**
 * 高级蓝图加农炮装填器菜单：队列 27 格 + 库存（集群合并、基础 54 格可并入当内存、翻页显示）
 * + 插件槽 6 格 + 玩家背包。
 * 集群中存在高级装填器时，基础装填器并入本菜单当作额外内存（容量叠加）；滚动条按行浏览整个集群。
 */
public class AdvancedSchematicLoaderMenu extends AbstractContainerMenu
    implements cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder {
    public static final int COLS = 9;
    public static final int ROW_SIZE = 18;

    /** 库存区域左上 y（背景图上的位置，与 Menu 库存槽初始 y 对应）。 */
    public static final int STORAGE_BASE_Y = 83;
    /** 升级槽起始 index。 */
    public static final int UPGRADE_START = 0;
    public static final int UPGRADE_COUNT = 6;
    /** 蓝图队列起始 index。 */
    public static final int QUEUE_START = 6;
    public static final int QUEUE_COUNT = 27;
    /** 队列区可见行数（3 行 = 27 格）；高级↔高级共享后队列会叠加，靠队列滚动条翻页看全。 */
    public static final int QUEUE_VISIBLE_ROWS = 3;
    public static final int QUEUE_VISIBLE = QUEUE_VISIBLE_ROWS * 9;
    /** 主库存起始 index（集群内存总行数动态，菜单固定提供可见 6 行 = 54 格槽位）。 */
    public static final int STORAGE_START = 33;
    /** 单个高级装填器内存（108 格），客户端空容器占位用。 */
    public static final int STORAGE_COUNT = 108;
    /** 界面中可见的库存槽位数（6 行）。 */
    public static final int STORAGE_VISIBLE = 6 * COLS;
    /** 玩家背包起始 index。 */
    public static final int PLAYER_START = STORAGE_START + STORAGE_VISIBLE;

    /** 按钮 id：0/1/2 = 自动打印/回收/火药 开关，3 = 队列开始/停止（红石模式 = 900）。 */
    private final AdvancedSchematicLoaderBlockEntity loader;
    private final ContainerData data;
    /** 集群内所有装填器的库存（含自身）；客户端重建时仅自身一个空库存。 */
    private final List<IItemHandler> clusterInventories = new ArrayList<>();
    /** 服务端计算出的集群内存总行数。 */
    private final int serverTotalRows;
    /** 服务端计算出的集群队列总行数（共享队列格数 / 9）。 */
    private final int serverQueueRows;
    /** 当前行偏移（0 = 从集群最前面装填器第一行开始）。 */
    private int rowOffset;
    /** 队列行偏移（0 = 从共享队列第一行开始）。 */
    private int queueRowOffset;

    public AdvancedSchematicLoaderMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public AdvancedSchematicLoaderMenu(final int id,
                                       final Inventory inventory,
                                       @Nullable final AdvancedSchematicLoaderBlockEntity loader) {
        super(RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_MENU.get(), id);
        this.loader = loader;
        this.data = loader != null ? loader.getContainerData() : new SimpleContainerData(7);
        addDataSlots(data);

        final ItemStackHandler upgrades = loader != null ? loader.getUpgradeContainer() : new ItemStackHandler(6);
        // 队列：服务端用「实时视图」（集群换共享队列后界面自动跟上）；客户端用同规格空占位
        // （内容由原版槽位同步按 index 下发，与库存 ClusterSlot 同一套机制）。
        final IItemHandler queueHandler = loader != null
            ? loader.queueView()
            : new cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity
                .SchematicLoaderQueue(QUEUE_COUNT);
        this.serverQueueRows = loader != null ? loader.getQueueSlotTotal() / COLS : QUEUE_VISIBLE_ROWS;

        // 集群内存：基础 + 高级（存在高级时基础并入）混合，滚动条按行浏览。
        // 顺序与方块实体补料插入顺序一致（紧贴 cannon 的装填器在前），保证新拉的料出现在默认视野内
        int totalSlots = 0;
        if (loader != null) {
            for (final var ldr : loader.getClusterInGuiOrder()) {
                // 实时视图（见 SchematicLoaderMenu）：同族增台 / 拆台换内存对象后界面自动跟上
                final net.neoforged.neoforge.items.IItemHandler inv = ldr.memoryView();
                clusterInventories.add(inv);
                totalSlots += inv.getSlots();
            }
        } else {
            // 客户端占位容器：格数固定，但「每格上限」必须同样跟本机堆叠升级走 ——
            // 否则本地预测会按 64 处理，与权威服务端（按本机上限 256 之类）算出的结果不一致，
            // 表现为点击 / Shift 移动时数量抖动一次。这里直接复用本菜单的本机上限口径。
            clusterInventories.add(new ItemStackHandler(STORAGE_COUNT) {
                @Override
                public int getSlotLimit(final int slot) {
                    return getStorageSlotCapacity();
                }
            });
            totalSlots = STORAGE_COUNT;
        }
        this.serverTotalRows = totalSlots / COLS;
        final boolean dynamic = loader != null; // 客户端空容器按固定 index 由数据包同步

        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (187,6+i*18) → Menu +1）。
        // 装填器放行真正生效的升级：速度升级（提高材料搬运量，满级瞬间取出）+
        // 堆叠升级（提高每格容量上限，见 getStorageSlotCapacity）+ 自动合成升级（资源不足时自动合成）。
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18,
                java.util.List.of("speed_upgrade", "stack_upgrade", "autocrafting_upgrade")));
        }
        // 蓝图队列：可见 3 行（27 格，背景精灵 (8,18) → Menu (9,19)）。
        // 高级↔高级同集群时队列叠加（N 台 = 27×N 格），复用与库存同一套 ClusterSlot 动态映射 +
        // 队列行偏移翻页 —— 于是「每一台高级界面都能滚到 / 看到全部队列」，只允许放 Create 蓝图。
        for (int row = 0; row < QUEUE_VISIBLE_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                addSlot(new ClusterSlot(
                    List.of(queueHandler), this::getQueueRowOffset, dynamic, COLS,
                    col + row * COLS, 9 + col * 18, 19 + row * 18));
            }
        }
        // 主库存可见 54 格（6 行）：按动态偏移映射到集群真实内存（各装填器容量可不同）
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < COLS; col++) {
                addSlot(new ClusterSlot(clusterInventories, () -> rowOffset, dynamic, COLS,
                    col + row * COLS, 9 + col * 18, STORAGE_BASE_Y + row * ROW_SIZE));
            }
        }
        // 玩家主物品栏（3 行 × 9，背景精灵 (8,229) → Menu (9,230)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 230 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,287) → Menu (9,288)，与背包间隔 4px）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 288));
        }
    }

    public static AdvancedSchematicLoaderMenu create(final int id,
                                                     final Inventory inventory,
                                                     final AdvancedSchematicLoaderBlockEntity loader) {
        return new AdvancedSchematicLoaderMenu(id, inventory, loader);
    }

    /** @return 集群内存总行数（客户端经数据槽同步）。 */
    public int getTotalRows() {
        return Math.max(1, loader != null ? serverTotalRows : data.get(4));
    }

    /** @return 当前行偏移。 */
    public int getRowOffset() {
        return rowOffset;
    }

    public void setRowOffset(final int rowOffset) {
        this.rowOffset = Math.max(0, Math.min(rowOffset, getTotalRows() - 6));
    }

    /** @return 集群队列总行数（客户端经数据槽同步；单机高级 = 3 行）。 */
    public int getQueueTotalRows() {
        return Math.max(QUEUE_VISIBLE_ROWS, loader != null ? serverQueueRows : data.get(6));
    }

    /** @return 当前队列行偏移。 */
    public int getQueueRowOffset() {
        return queueRowOffset;
    }

    /**
     * 本机插件槽里的堆叠升级数量（直接数菜单槽位）。
     * <p>为什么数槽位而不是问方块实体：界面 tooltip 在客户端也要给出同一个数，而槽位内容由原版
     * 槽位同步下发，服务端 / 客户端口径天然一致（方块实体在客户端是 null）。
     */
    public int getStackUpgradeCount() {
        int count = 0;
        for (int i = UPGRADE_START; i < UPGRADE_START + UPGRADE_COUNT; i++) {
            final ItemStack stack = slots.get(i).getItem();
            if (SchematicLoaderBlockEntity.isStackUpgrade(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 本机每格上限（与方块实体同一实现、同一公式），供界面 tooltip 显示「本机实际生效」的数值。 */
    public int getStorageSlotCapacity() {
        return SchematicLoaderBlockEntity.storageSlotCapacity(getStackUpgradeCount());
    }

    public void setQueueRowOffset(final int rowOffset) {
        this.queueRowOffset = Math.max(0, Math.min(rowOffset, getQueueTotalRows() - QUEUE_VISIBLE_ROWS));
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (loader != null && !loader.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> loader.setAutoPrint(!loader.isAutoPrint());
                case 1 -> loader.setAutoRecycle(!loader.isAutoRecycle());
                case 2 -> loader.setAutoFillGunpowder(!loader.isAutoFillGunpowder());
                case 3 -> loader.toggleQueue();
                // 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）
                case cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode.BUTTON_ID ->
                    loader.setRedstoneMode(loader.getRedstoneMode().toggle());
                default -> {
                }
            }
        }
        return true;
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public com.refinedmods.refinedstorage.common.support.RedstoneMode rscc$getRedstoneMode() {
        return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings.getRedstoneMode(data.get(5));
    }

    public boolean isAutoPrint() {
        return data.get(0) == 1;
    }

    public boolean isAutoRecycle() {
        return data.get(1) == 1;
    }

    public boolean isAutoFillGunpowder() {
        return data.get(2) == 1;
    }

    public boolean isQueueRunning() {
        return data.get(3) == 1;
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
