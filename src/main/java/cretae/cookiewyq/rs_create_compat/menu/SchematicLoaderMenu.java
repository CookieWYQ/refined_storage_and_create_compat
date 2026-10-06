package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
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
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 蓝图加农炮装填器菜单：54 格库存（支持集群合并内存 + 滚动条翻页）+ 蓝图槽 + 6 插件槽 + 玩家背包。
 * 当同一网络中存在高级装填器时，方块会改为打开高级装填器菜单（基础并入高级当内存），
 * 本菜单仅在纯基础集群中使用。
 */
public class SchematicLoaderMenu extends AbstractContainerMenu
    implements cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder {
    /** 按钮 id：0/1/2 = 自动打印/回收/火药 开关（红石模式 = 900）。 */
    private final SchematicLoaderBlockEntity loader;
    /** 升级槽区域：蓝图槽（index 0）之后的 6 格，与界面右侧 6 格插件栏一一对应。 */
    public static final int UPGRADE_START = 1;
    public static final int UPGRADE_COUNT = 6;
    private final ContainerData data;
    /** 集群内所有装填器的库存（含自身）；客户端重建时仅自身一个空库存。 */
    private final List<IItemHandler> clusterInventories = new ArrayList<>();
    /** 服务端计算出的集群内存总行数。 */
    private final int serverTotalRows;
    /** 当前行偏移（0 = 从第一个装填器第一行开始）。 */
    private int rowOffset;

    public SchematicLoaderMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public SchematicLoaderMenu(final int id,
                               final Inventory inventory,
                               @Nullable final SchematicLoaderBlockEntity loader) {
        super(RS_Create_Compat.SCHEMATIC_LOADER_MENU.get(), id);
        this.loader = loader;
        this.data = loader != null ? loader.getContainerData() : new SimpleContainerData(6);
        addDataSlots(data);

        final net.neoforged.neoforge.items.IItemHandlerModifiable blueprintStorage =
            loader != null ? loader.blueprintSlotView() : new ItemStackHandler(1);
        final ItemStackHandler upgrades = loader != null ? loader.getUpgradeContainer() : new ItemStackHandler(6);

        // 集群库存：同类型装填器并排时合并显示（内存叠加），滚动条按行滚动浏览。
        // 顺序与方块实体补料插入顺序一致（紧贴 cannon 的装填器在前），保证新拉的料出现在默认视野内
        int totalSlots = 0;
        if (loader != null) {
            for (final SchematicLoaderBlockEntity ldr : loader.getClusterInGuiOrder()) {
                // 用「实时视图」而不是当时的那个内存对象：同族装填器增台 / 拆台会把内存换成新的那一份，
                // 界面必须跟着走（抓旧对象 = 玩家会往不再落盘的孤儿内存里放东西）
                final IItemHandler inv = ldr.memoryView();
                clusterInventories.add(inv);
                totalSlots += inv.getSlots();
            }
        } else {
            // 客户端占位容器：格数固定，但「每格上限」必须同样跟本机堆叠升级走 ——
            // 否则本地预测会按 64 处理，与权威服务端（按本机上限 256 之类）算出的结果不一致，
            // 表现为点击 / Shift 移动时数量抖动一次。这里直接复用本菜单的本机上限口径。
            clusterInventories.add(new ItemStackHandler(54) {
                @Override
                public int getSlotLimit(final int slot) {
                    return getStorageSlotCapacity();
                }
            });
            totalSlots = 54;
        }
        this.serverTotalRows = totalSlots / 9;
        final boolean dynamic = loader != null; // 客户端空容器按固定 index 由数据包同步

        // 蓝图槽（Menu 槽位 x/y = 背景精灵坐标 +1；仅允许蓝图物品）。
        // 绑定的是<b>共享视图</b>（见 SchematicLoaderBlockEntity#blueprintSlotView）：有可共享的加农炮时
        // 它就是加农炮的蓝图槽本身 —— 放入立刻可打印、打印消耗后界面同步变空，两端内容天然一致；
        // 没有加农炮（独立模式）时才退回本机蓝图槽。
        // 与高级装填器同集群时锁定：不可放入，只能查看 / 取出原有内容（蓝图维度由高级接管）。
        final net.neoforged.neoforge.items.IItemHandler blueprintHandler = blueprintStorage;
        addSlot(new SlotItemHandler(blueprintHandler, 0, 9, 19) {
            @Override
            public boolean mayPlace(final ItemStack stack) {
                return !isBlueprintSlotLocked()
                    && cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity
                        .isValidBlueprintStack(stack);
            }
        });
        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 x=187 y=6+i*18 → Menu +1）。
        // 装填器放行真正生效的升级：速度升级（提高材料搬运量，满级瞬间取出）+
        // 堆叠升级（提高每格容量上限，见 getStorageSlotCapacity）+ 自动合成升级（资源不足时自动合成）。
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18,
                java.util.List.of("speed_upgrade", "stack_upgrade", "autocrafting_upgrade")));
        }
        // 主库存 54 格（6 行 × 9 列，背景精灵 (8,36) 起 → Menu (9,37) 起）
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new ClusterSlot(clusterInventories, () -> rowOffset, dynamic, 9,
                    col + row * 9, 9 + col * 18, 37 + row * 18));
            }
        }
        // 玩家主物品栏（背景精灵 (8,182) 起 → Menu (9,183) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 183 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,240) → Menu (9,241)，与背包间隔 4px）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 241));
        }
    }

    public static SchematicLoaderMenu create(final int id,
                                             final Inventory inventory,
                                             final SchematicLoaderBlockEntity loader) {
        return new SchematicLoaderMenu(id, inventory, loader);
    }

    /** @return 集群内存总行数（客户端经数据槽同步）。 */
    public int getTotalRows() {
        return Math.max(1, loader != null ? serverTotalRows : data.get(3));
    }

    /** @return 当前行偏移。 */
    public int getRowOffset() {
        return rowOffset;
    }

    public void setRowOffset(final int rowOffset) {
        this.rowOffset = Math.max(0, Math.min(rowOffset, getTotalRows() - 6));
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (loader != null && !loader.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> loader.setAutoPrint(!loader.isAutoPrint());
                case 1 -> loader.setAutoRecycle(!loader.isAutoRecycle());
                case 2 -> loader.setAutoFillGunpowder(!loader.isAutoFillGunpowder());
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
        return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings.getRedstoneMode(data.get(4));
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

    /**
     * 蓝图槽是否被锁定（与高级装填器同集群时锁定）：服务端读方块实体权威判定，客户端读同步值。
     * <p>锁定只限制「放入」，原有蓝图仍可取出 —— 绝不做任何搬运 / 覆盖，避免丢物或复制。
     */
    public boolean isBlueprintSlotLocked() {
        return loader != null ? loader.isBlueprintSlotLocked() : data.get(5) == 1;
    }

    /** 蓝图槽在菜单里的槽位下标（恒为第一个加入的槽位），供界面绘制锁定覆盖层与 tooltip。 */
    public static int getBlueprintSlotIndex() {
        return 0;
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
