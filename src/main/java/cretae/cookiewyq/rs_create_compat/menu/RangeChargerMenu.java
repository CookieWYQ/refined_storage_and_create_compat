package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode;
import cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder;
import com.refinedmods.refinedstorage.common.support.RedstoneMode;
import com.refinedmods.refinedstorage.common.support.RedstoneModeSettings;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 范围充电器菜单：无物品槽（不显示玩家背包），通过 ContainerData 同步范围与能量，
 * 按钮消息走原版 clickMenuButton。
 */
public class RangeChargerMenu extends AbstractContainerMenu implements RsccRedstoneModeHolder {
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
        this.data = charger != null ? charger.getContainerData() : new SimpleContainerData(10);
        addDataSlots(data);

        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 x=187 y=6+i*18 → Menu +1）
        final net.neoforged.neoforge.items.ItemStackHandler upgrades =
            charger != null ? charger.getUpgradeContainer() : new net.neoforged.neoforge.items.ItemStackHandler(6);
        for (int i = 0; i < 6; i++) {
            // 范围充电器吃 速度/堆叠（充电速率）、范围升级（突破 100 格上限）与创造范围升级（无限范围）；
            // 自动合成对它无效，禁止放入
            addSlot(new cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot(upgrades, i, 188, 7 + i * 18,
                java.util.List.of("speed_upgrade", "stack_upgrade", "range_upgrade", "creative_range_upgrade")));
        }
        // 玩家主物品栏（3 行 9 列，背景精灵 (8,84) → Menu (9,85)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 85 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,142) → Menu (9,143)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 143));
        }
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
                // 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）
                case RsccRedstoneMode.BUTTON_ID -> charger.setRedstoneMode(charger.getRedstoneMode().toggle());
                default -> {
                }
            }
        }
        return true;
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public RedstoneMode rscc$getRedstoneMode() {
        return RedstoneModeSettings.getRedstoneMode(data.get(9));
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

    /** 当前实际充电的对象数量（方块/掉落物/玩家物品）。 */
    public int getTargetCount() {
        return data.get(5);
    }

    /** 速度+堆叠升级总数（用于 GUI 显示充电速率）。 */
    public int getUpgradeCount() {
        return data.get(6);
    }

    /** 范围升级数（用于 GUI 显示当前上限）。 */
    public int getRangeUpgradeCount() {
        return data.get(7);
    }

    /** 是否已放入创造范围升级（无限范围，隐藏输入框显示 ∞）。 */
    public boolean hasInfiniteRange() {
        return data.get(8) == 1;
    }

    /** 单轴范围上限：默认 100，每个范围升级 +{@link cretae.cookiewyq.rs_create_compat.Config#rangeChargerRangePerUpgrade}；无限时返回极大值。 */
    public int getMaxRange() {
        return hasInfiniteRange() ? Integer.MAX_VALUE - 1
            : 100 + data.get(7) * cretae.cookiewyq.rs_create_compat.Config.rangeChargerRangePerUpgrade;
    }

    /** 服务端设置指定轴的范围绝对值（客户端输入框 / ± 按钮发送）。 */
    public void setRangeValue(final int axis, final int value) {
        if (charger == null || charger.getLevel().isClientSide()) {
            return;
        }
        final int max = charger.getMaxRange();
        final int v = Math.max(1, Math.min(value, max));
        switch (axis) {
            case 0 -> charger.adjustRangeX(v - charger.getRangeX());
            case 1 -> charger.adjustRangeY(v - charger.getRangeY());
            case 2 -> charger.adjustRangeZ(v - charger.getRangeZ());
            default -> {
            }
        }
    }

    /** 供客户端发送范围设置包。 */
    @Nullable
    public net.minecraft.core.BlockPos getChargerPos() {
        return charger != null ? charger.getBlockPos() : null;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < 6) {
                // 插件槽 → 玩家背包
                if (!moveItemStackTo(stackInSlot, 6, 6 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 插件槽
                if (!moveItemStackTo(stackInSlot, 0, 6, false)) {
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
        return charger != null
            && charger.getLevel().getBlockEntity(charger.getBlockPos()) == charger
            && player.distanceToSqr(charger.getBlockPos().getX() + 0.5,
            charger.getBlockPos().getY() + 0.5,
            charger.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
