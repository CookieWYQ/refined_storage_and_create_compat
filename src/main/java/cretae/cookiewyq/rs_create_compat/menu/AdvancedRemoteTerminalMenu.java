package cretae.cookiewyq.rs_create_compat.menu;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 高级远程多功能终端菜单：显示当前模式与网络信息，支持模式切换。
 * 监视器模式通过 RS 原版自动合成仓监视器界面实现（右键打开，见物品 use 逻辑）。
 */
public class AdvancedRemoteTerminalMenu extends AbstractContainerMenu {
    private static final int DATA_COUNT = 9;

    private final ContainerData data;
    @Nullable
    private final Network network;
    @Nullable
    private final ItemStack stack;

    public AdvancedRemoteTerminalMenu(final int id, final Inventory inventory) {
        this(id, inventory, null, null);
    }

    public AdvancedRemoteTerminalMenu(final int id,
                                      final Inventory inventory,
                                      @Nullable final Network network,
                                      @Nullable final ItemStack stack) {
        super(RS_Create_Compat.ADVANCED_REMOTE_TERMINAL_MENU.get(), id);
        this.network = network;
        this.stack = stack;
        this.data = new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
        refresh();
    }

    private void refresh() {
        if (network == null || stack == null) {
            return;
        }
        final int mode = AdvancedRemoteTerminalItem.getMode(stack);
        data.set(0, mode);
        // 物品自身电量（仿 RS 原版无线终端：界面显示物品电量，没电则操作禁用）
        if (stack.getItem() instanceof AdvancedRemoteTerminalItem item) {
            final com.refinedmods.refinedstorage.api.network.energy.EnergyStorage energy = item.createEnergyStorage(stack);
            data.set(1, (int) energy.getStored());
            data.set(2, (int) energy.getCapacity());
        }
        data.set(3, 1);
        final long stored = network.getComponent(StorageNetworkComponent.class).getStored();
        data.set(4, (int) (stored & 0xFFFFFFFFL));
        data.set(5, (int) (stored >>> 32));
        final AutocraftingNetworkComponent auto = network.getComponent(AutocraftingNetworkComponent.class);
        data.set(6, auto.getOutputs().size());
        data.set(7, auto.getStatuses().size());
        // 创造版标志（无视电量）
        data.set(8, stack.getItem() instanceof AdvancedRemoteTerminalItem item && item.isCreative() ? 1 : 0);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (network != null && stack != null && !player.level().isClientSide()) {
            final int mode = Math.clamp(id, 0, 4);
            AdvancedRemoteTerminalItem.setMode(stack, mode);
            refresh();
        }
        return true;
    }

    public int getMode() {
        return data.get(0);
    }

    public int getEnergy() {
        return data.get(1);
    }

    public int getMaxEnergy() {
        return data.get(2);
    }

    public boolean isOnline() {
        return data.get(3) == 1;
    }

    public long getStored() {
        return (data.get(4) & 0xFFFFFFFFL) | ((long) data.get(5) << 32);
    }

    public int getPatternCount() {
        return data.get(6);
    }

    public int getTaskCount() {
        return data.get(7);
    }

    /** 创造版无视电量。 */
    public boolean isCreative() {
        return data.get(8) == 1;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(final Player player) {
        return true;
    }
}
