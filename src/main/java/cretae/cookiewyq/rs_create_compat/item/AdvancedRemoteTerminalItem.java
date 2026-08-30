package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.impl.energy.EnergyStorageImpl;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.HelpTooltipComponent;
import com.refinedmods.refinedstorage.common.api.support.energy.AbstractNetworkEnergyItem;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedRemoteTerminalMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
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
 * 高级远程多功能终端（仿 RS 原版无线终端实现）：
 * <ul>
 *     <li><b>普通版</b>：需要电量。没电时仍会打开界面，但所有操作按钮禁用（与 RS 原版行为一致）。</li>
 *     <li><b>满电版</b>：默认满电，可正常消耗与充能。</li>
 *     <li><b>创造版</b>：无视电量（容量极大 + 默认满电）。</li>
 * </ul>
 * 绑定目标为无线信号发射器 / 任意 RS 网络节点方块（含第三方跨维度增强无线访问点）。
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

    public enum Type {
        /** 普通版：需要电量，没电时界面禁用操作。 */
        NORMAL,
        /** 满电版：默认满电。 */
        CHARGED,
        /** 创造版：无视电量。 */
        CREATIVE
    }

    private final Type type;

    public AdvancedRemoteTerminalItem(final Type type) {
        super(
            new Item.Properties().stacksTo(1).fireResistant(),
            RefinedStorageApi.INSTANCE.getEnergyItemHelper(),
            RefinedStorageApi.INSTANCE.getNetworkItemHelper()
        );
        this.type = type;
    }

    public boolean isCreative() {
        return type == Type.CREATIVE;
    }

    public static int getMode(final ItemStack stack) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe().getInt(TAG_MODE);
    }

    public static void setMode(final ItemStack stack, final int mode) {
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> tag.putInt(TAG_MODE, mode)));
    }

    @Override
    public ItemStack getDefaultInstance() {
        // 满电版 / 创造版默认满电；普通版默认无电（用于演示没电时的禁用行为）
        if (type != Type.NORMAL) {
            return createAtEnergyCapacity();
        }
        return super.getDefaultInstance();
    }

    public EnergyStorage createEnergyStorage(final ItemStack stack) {
        final long capacity = type == Type.CREATIVE
            ? Integer.MAX_VALUE
            : Config.advancedRemoteTerminalEnergyCapacity;
        return RefinedStorageApi.INSTANCE.asItemEnergyStorage(new EnergyStorageImpl(capacity), stack);
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        // 仿 RS 原版：super 会追加能量信息与绑定状态（未绑定红色 / 已绑定灰色坐标）
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(final ItemStack stack) {
        // 仿 RS 原版：描述文本放在帮助组件中（帮助图标 + 蓝色小字 + Shift 展开）
        // 未绑定时提示如何绑定；已绑定时说明用法。
        return Optional.of(new HelpTooltipComponent(
            isBound(stack)
                ? Component.translatable("item.rs_create_compat.advanced_remote_terminal.usage")
                : Component.translatable("item.rs_create_compat.advanced_remote_terminal.bind_hint")
        ));
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
        final Optional<Network> network = context.resolveNetwork();
        if (network.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.not_bound"),
                true);
            return;
        }
        // 仿 RS 原版：不检查电量，总是打开界面；没电时界面内操作禁用（由 GUI 状态控制）。
        // 创造版不消耗电量。
        if (!isCreative()) {
            context.drainEnergy(10);
        }

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
