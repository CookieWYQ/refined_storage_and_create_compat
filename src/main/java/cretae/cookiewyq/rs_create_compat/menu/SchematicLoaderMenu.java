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
 * 蓝图加农炮装填器菜单：54 格库存（支持同类型装填器集群合并内存 + 滚动条翻页）
 * + 蓝图槽 + 6 插件槽 + 玩家背包。
 */
public class SchematicLoaderMenu extends AbstractContainerMenu {
    /** 按钮 id：0/1/2/3 = 自动打印/部署/回收/火药 开关。 */
    private final SchematicLoaderBlockEntity loader;
    private final ContainerData data;
    /** 集群内所有装填器的库存（含自身）；客户端重建时仅自身一个空库存。 */
    private final List<IItemHandler> clusterInventories = new ArrayList<>();
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
        this.data = loader != null ? loader.getContainerData() : new SimpleContainerData(5);
        addDataSlots(data);

        final ItemStackHandler blueprint = loader != null ? loader.getBlueprintSlot() : new ItemStackHandler(1);
        final ItemStackHandler upgrades = loader != null ? loader.getUpgradeContainer() : new ItemStackHandler(6);

        // 集群库存：同类型装填器并排时合并显示（内存叠加），滚动条按行滚动浏览
        if (loader != null) {
            for (final SchematicLoaderBlockEntity ldr : loader.collectCluster()) {
                clusterInventories.add(ldr.getInventory());
            }
        } else {
            clusterInventories.add(new ItemStackHandler(54));
        }

        // 蓝图槽（Menu 槽位 x/y = 背景精灵坐标 +1，与背景 slot 精灵重合）
        addSlot(new SlotItemHandler(blueprint, 0, 9, 9));
        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 x=187 y=6+i*18 → Menu +1）
        for (int i = 0; i < 6; i++) {
            addSlot(new UpgradeSlot(upgrades, i, 188, 7 + i * 18));
        }
        // 主库存 54 格（6 行 × 9 列，背景精灵 (8,26) 起 → Menu (9,27) 起）
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new ClusterSlot(clusterInventories, () -> rowOffset, 9, 54,
                    col + row * 9, 9 + col * 18, 27 + row * 18));
            }
        }
        // 玩家主物品栏（背景精灵 (8,172) 起 → Menu (9,173) 起）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 173 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,244) → Menu (9,245)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 245));
        }
    }

    public static SchematicLoaderMenu create(final int id,
                                             final Inventory inventory,
                                             final SchematicLoaderBlockEntity loader) {
        return new SchematicLoaderMenu(id, inventory, loader);
    }

    /** @return 集群内装填器数量（由服务端 ContainerData 同步，客户端重建后仍正确）。 */
    public int getClusterSize() {
        final int size = data.get(4);
        return Math.max(1, size);
    }

    /** @return 当前行偏移。 */
    public int getRowOffset() {
        return rowOffset;
    }

    public void setRowOffset(final int rowOffset) {
        // 每个装填器 6 行；最大偏移 = 集群总行数 - 6
        this.rowOffset = Math.max(0, Math.min(rowOffset, clusterInventories.size() * 6 - 6));
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
