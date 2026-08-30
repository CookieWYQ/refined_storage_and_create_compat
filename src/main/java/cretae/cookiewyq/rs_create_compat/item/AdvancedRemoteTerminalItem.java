package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.impl.energy.EnergyStorageImpl;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.energy.AbstractNetworkEnergyItem;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedRemoteTerminalMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

/**
 * 高级远程多功能终端：手持设备，带能量（默认 10,000,000 FE），
 * 可切换打开合成终端 / 样板终端 / 自动合成仓管理器 / 自动合成仓监视器 / 序列装配样板终端界面。
 * 模式保存在物品 NBT 中。
 */
public class AdvancedRemoteTerminalItem extends AbstractNetworkEnergyItem {
    public static final String TAG_MODE = "Mode";
    public static final int MODE_GRID = 0;
    public static final int MODE_PATTERNS = 1;
    public static final int MODE_MANAGER = 2;
    public static final int MODE_MONITOR = 3;
    public static final int MODE_SEQUENCE = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedRemoteTerminalItem.class);
    private static final String RS_MONITOR_CLASS =
        "com.refinedmods.refinedstorage.common.autocrafting.monitor.WirelessAutocraftingMonitor";
    private static final String RS_MONITOR_IFACE =
        "com.refinedmods.refinedstorage.common.autocrafting.monitor.AutocraftingMonitor";
    private static final String RS_MONITOR_PROVIDER_CLASS =
        "com.refinedmods.refinedstorage.common.autocrafting.monitor.WirelessAutocraftingMonitorExtendedMenuProvider";

    public AdvancedRemoteTerminalItem() {
        super(
            new Item.Properties().stacksTo(1).fireResistant(),
            RefinedStorageApi.INSTANCE.getEnergyItemHelper(),
            RefinedStorageApi.INSTANCE.getNetworkItemHelper()
        );
    }

    public static int getMode(final ItemStack stack) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe().getInt(TAG_MODE);
    }

    public static void setMode(final ItemStack stack, final int mode) {
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> tag.putInt(TAG_MODE, mode)));
    }

    public EnergyStorage createEnergyStorage(final ItemStack stack) {
        return RefinedStorageApi.INSTANCE.asItemEnergyStorage(
            new EnergyStorageImpl(Config.advancedRemoteTerminalEnergyCapacity),
            stack
        );
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.rs_create_compat.advanced_remote_terminal.bind_hint"));
        tooltip.add(Component.translatable("item.rs_create_compat.advanced_remote_terminal.usage"));
    }

    @Override
    protected void use(@Nullable final Component name,
                       final ServerPlayer player,
                       final SlotReference slotReference,
                       final NetworkItemContext context) {
        final Optional<ItemStack> stack = slotReference.resolve(player);
        if (stack.isEmpty()) {
            return;
        }
        final EnergyStorage energy = createEnergyStorage(stack.get());
        if (energy.getStored() <= 0) {
            player.displayClientMessage(Component.translatable("item.rs_create_compat.advanced_remote_terminal.no_energy"), true);
            return;
        }
        final Optional<Network> network = context.resolveNetwork();
        if (network.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.not_bound"),
                true);
            return;
        }
        context.drainEnergy(10);

        // 监视器模式：普通右键直接打开 RS 原版自动合成仓监视器（可观察任务进度并取消）。
        // Shift+右键强制打开终端界面以切换模式。
        final int mode = AdvancedRemoteTerminalItem.getMode(stack.get());
        if (!player.isShiftKeyDown() && mode == MODE_MONITOR) {
            openAutocraftingMonitor(name, player, slotReference, context);
            return;
        }

        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new AdvancedRemoteTerminalMenu(id, inventory, network.get(), stack.get()),
            name != null ? name : Component.translatable("item.rs_create_compat.advanced_remote_terminal")
        ));
    }

    /**
     * 通过反射打开 RS 原版自动合成仓监视器界面。
     * RS 的 WirelessAutocraftingMonitor 及其界面提供者为包内私有，无法直接引用，
     * 因此使用反射构造（若 RS 类名变更会优雅降级为提示信息）。
     */
    private static void openAutocraftingMonitor(@Nullable final Component name,
                                                final ServerPlayer player,
                                                final SlotReference slotReference,
                                                final NetworkItemContext context) {
        try {
            final Class<?> monitorClass = Class.forName(RS_MONITOR_CLASS);
            final Constructor<?> monitorCtor = monitorClass.getDeclaredConstructor(NetworkItemContext.class);
            monitorCtor.setAccessible(true);
            final Object monitor = monitorCtor.newInstance(context);

            final Class<?> monitorIface = Class.forName(RS_MONITOR_IFACE);
            final Class<?> providerClass = Class.forName(RS_MONITOR_PROVIDER_CLASS);
            final Constructor<?> providerCtor = providerClass.getDeclaredConstructor(
                Component.class, monitorIface, SlotReference.class);
            providerCtor.setAccessible(true);
            final Component monitorName = name != null ? name
                : Component.translatable("item.rs_create_compat.advanced_remote_terminal");
            final Object provider = providerCtor.newInstance(monitorName, monitor, slotReference);
            Platform.INSTANCE.getMenuOpener().openMenu(player, (MenuProvider) provider);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to open RS autocrafting monitor", e);
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.monitor_failed"),
                true);
        }
    }
}
