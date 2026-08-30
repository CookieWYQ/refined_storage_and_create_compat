package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.impl.energy.EnergyStorageImpl;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.HelpTooltipComponent;
import com.refinedmods.refinedstorage.common.api.support.energy.AbstractNetworkEnergyItem;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.content.Items;
import cretae.cookiewyq.rs_create_compat.Config;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 高级远程多功能终端（参照 Universal-Grid 的实现方式）：
 * <ul>
 *     <li>右键使用 = 直接打开 RS 原版无线终端界面（合成终端 / 样板终端 / 合成仓管理 / 合成仓监视 / 序列装配），
 *     物品可正常存取。</li>
 *     <li>模式切换：客户端在打开的 RS 界面上渲染右下角方块图标 Tab，点击发送 C2S 包，
 *     服务端把模式写回物品并重开对应模式界面（旧界面自动关闭）。</li>
 *     <li>普通版需要电量（没电时界面打开但操作禁用，与原版一致）；满电版默认满电；创造版无视电量。</li>
 * </ul>
 */
public class AdvancedRemoteTerminalItem extends AbstractNetworkEnergyItem {
    public static final String TAG_MODE = "Mode";
    public static final int MODE_GRID = 0;
    public static final int MODE_PATTERNS = 1;
    public static final int MODE_MANAGER = 2;
    public static final int MODE_MONITOR = 3;
    public static final int MODE_SEQUENCE = 4;
    public static final int MODE_COUNT = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedRemoteTerminalItem.class);

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
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> tag.putInt(TAG_MODE, mode)));
    }

    @Override
    public ItemStack getDefaultInstance() {
        // 满电版 / 创造版默认满电（直接写入 RS 能量数据组件，最可靠）；
        // 普通版默认无电
        final ItemStack stack = super.getDefaultInstance();
        if (type != Type.NORMAL) {
            final long capacity = type == Type.CREATIVE
                ? Integer.MAX_VALUE
                : Config.advancedRemoteTerminalEnergyCapacity;
            stack.set(com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE.getEnergy(), capacity);
        }
        return stack;
    }

    // —— 创造版：不显示物品栏底部的电量条 ——
    @Override
    public boolean isBarVisible(final ItemStack stack) {
        return !isCreative() && super.isBarVisible(stack);
    }

    @Override
    public int getBarWidth(final ItemStack stack) {
        if (isCreative()) {
            return 0;
        }
        return super.getBarWidth(stack);
    }

    @Override
    public int getBarColor(final ItemStack stack) {
        if (isCreative()) {
            return 0x00000000;
        }
        return super.getBarColor(stack);
    }

    public EnergyStorage createEnergyStorage(final ItemStack stack) {
        final long capacity = type == Type.CREATIVE
            ? Integer.MAX_VALUE
            : Config.advancedRemoteTerminalEnergyCapacity;
        // 初始电量由 getDefaultInstance 写入的能量组件决定（满电/创造版默认满电，普通版无电）
        return RefinedStorageApi.INSTANCE.asItemEnergyStorage(new EnergyStorageImpl(capacity), stack);
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(final ItemStack stack) {
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
        final Optional<ItemStack> stackOpt = slotReference.resolve(player);
        if (stackOpt.isEmpty()) {
            return;
        }
        // 仿 RS 原版：不检查网络绑定与电量，总是打开界面；未绑定/没电时界面内操作禁用（由 RS 原版机制处理）。
        openModeScreen(player, stackOpt.get(), slotReference);
    }

    /**
     * 该模式是否有对应的 RS 原版无线界面可复用。
     * 样板终端 / 合成仓管理器 / 序列装配没有 RS 原版无线版（data 版菜单服务端无法支撑，打开即关闭），
     * 故只保留合成终端与合成仓监视两个可靠模式。
     */
    public static boolean isModeSupported(final int mode) {
        return mode == MODE_GRID || mode == MODE_MONITOR;
    }

    /** 根据物品当前模式打开对应 RS 原版界面。 */
    public void openModeScreen(final ServerPlayer player, final ItemStack stack, final SlotReference slotReference) {
        final int mode = getMode(stack);
        final Component title = Objects.requireNonNullElse(
            stack.getHoverName(),
            Component.translatable("item.rs_create_compat.advanced_remote_terminal")
        );
        try {
            switch (mode) {
                case MODE_MONITOR -> Items.INSTANCE.getWirelessAutocraftingMonitor()
                    .use(player, stack, slotReference);
                case MODE_PATTERNS, MODE_MANAGER, MODE_SEQUENCE ->
                    player.displayClientMessage(
                        Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_unavailable"),
                        true);
                default -> Items.INSTANCE.getWirelessGrid().use(player, stack, slotReference);
            }
        } catch (final Throwable t) {
            LOGGER.error("Failed to open terminal mode {}", mode, t);
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_failed"),
                true);
        }
    }
}
