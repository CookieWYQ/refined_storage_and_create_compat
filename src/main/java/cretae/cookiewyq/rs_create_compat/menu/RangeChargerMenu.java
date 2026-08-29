package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 范围充电器菜单：无物品槽（不显示玩家背包），通过 ContainerData 同步范围与能量，
 * 按钮消息走原版 clickMenuButton。
 */
public class RangeChargerMenu extends AbstractContainerMenu {
    /** 按钮 id：0/1 = X 减/增，2/3 = Y 减/增，4/5 = Z 减/增。 */
    private final RangeChargerBlockEntity charger;
    private final ContainerData data;

    /** 客户端重建菜单用（无方块实体，数据由数据槽同步）。 */
    public RangeChargerMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public RangeChargerMenu(final int id, final Inventory inventory, @Nullable final RangeChargerBlockEntity charger) {
        super(RS_Create_Compat.RANGE_CHARGER_MENU.get(), id);
        this.charger = charger;
        this.data = charger != null ? charger.getContainerData() : new SimpleContainerData(5);
        addDataSlots(data);
        // 范围充电器不需要存放任何物品，因此不添加玩家背包槽位
    }

    public static RangeChargerMenu create(final int id, final Inventory inventory, final RangeChargerBlockEntity charger) {
        return new RangeChargerMenu(id, inventory, charger);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (charger != null && !charger.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> charger.adjustRangeX(-1);
                case 1 -> charger.adjustRangeX(1);
                case 2 -> charger.adjustRangeY(-1);
                case 3 -> charger.adjustRangeY(1);
                case 4 -> charger.adjustRangeZ(-1);
                case 5 -> charger.adjustRangeZ(1);
                default -> {
                }
            }
        }
        return true;
    }

    public int getRangeX() {
        return data.get(0);
    }

    public int getRangeY() {
        return data.get(1);
    }

    public int getRangeZ() {
        return data.get(2);
    }

    public int getEnergy() {
        return data.get(3);
    }

    public int getMaxEnergy() {
        return data.get(4);
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY; // 无物品槽位，无需转移
    }

    @Override
    public boolean stillValid(final Player player) {
        return charger != null
            && charger.getLevel().getBlockEntity(charger.getBlockPos()) == charger
            && player.distanceToSqr(charger.getBlockPos().getX() + 0.5,
            charger.getBlockPos().getY() + 0.5,
            charger.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
