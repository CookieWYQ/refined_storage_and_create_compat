package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.content.Blocks;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.network.SwitchTerminalModePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 高级远程多功能终端：在打开的 RS 原版界面（合成终端 / 样板终端 / 合成仓管理 / 合成仓监视）
 * 右下角叠加方块图标模式切换 Tab（参照 Universal-Grid 的方块 Tab 设计）。
 * 点击 Tab → 发送 C2S 包 → 服务端把模式写回物品并重开对应界面。
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"})
public final class TerminalModeTabOverlay {
    private static final int TAB_W = 24;
    private static final int TAB_H = 22;
    private static final String[] MODE_KEYS = {
        "gui.rs_create_compat.advanced_remote_terminal.mode.grid",
        "gui.rs_create_compat.advanced_remote_terminal.mode.patterns",
        "gui.rs_create_compat.advanced_remote_terminal.mode.manager",
        "gui.rs_create_compat.advanced_remote_terminal.mode.monitor",
        "gui.rs_create_compat.advanced_remote_terminal.mode.sequence"
    };

    private TerminalModeTabOverlay() {
    }

    @SubscribeEvent
    public static void onScreenInit(final ScreenEvent.Init.Post event) {
        final Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        // 仅当打开的是我们终端对应的 RS 原版界面时叠加 Tab。
        // 注意：不能调用 getMenu().getType() —— RS 的 mixin 会让它在非 RS 菜单（如原版背包）上抛异常。
        // 改为直接 instanceof RS 的 Screen 类型。
        if (!isRsScreen(screen)) {
            return;
        }
        final Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        final InteractionHand hand = findTerminalHand(player);
        if (hand == null) {
            return;
        }
        final SlotReference slotReference = RefinedStorageApi.INSTANCE
            .createInventorySlotReference(player, hand);
        final int currentMode = AdvancedRemoteTerminalItem.getMode(player.getItemInHand(hand));
        final int count = AdvancedRemoteTerminalItem.MODE_COUNT;
        // imageWidth / imageHeight 为 AbstractContainerScreen 的 protected 字段，外部无法直接访问，用反射取值
        final int guiW = guiSize(containerScreen, "imageWidth", 176);
        final int guiH = guiSize(containerScreen, "imageHeight", 166);
        for (int i = 0; i < count; i++) {
            final int mode = i;
            final int x = containerScreen.getGuiLeft() + guiW - 21;
            final int y = containerScreen.getGuiTop() + guiH
                - (TAB_H * count) + (TAB_H * i);
            final boolean supported = AdvancedRemoteTerminalItem.isModeSupported(mode);
            event.addListener(new TerminalModeTabButton(
                x, y, mode, supported, currentMode == mode,
                Component.translatable(MODE_KEYS[mode]),
                () -> {
                    // 不可用模式：本地提示即可，不发 packet（避免界面重开/鼠标跳中心）
                    if (!supported) {
                        player.displayClientMessage(
                            Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_unavailable"),
                            true);
                        return;
                    }
                    sendSwitch(player, slotReference, mode);
                }));
        }
    }

    /** 判断是否为 RS 的网格 / 样板 / 合成仓管理 / 合成仓监视界面（仅客户端存在这些类）。 */
    private static boolean isRsScreen(final Screen screen) {
        return screen instanceof com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen<?>
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridScreen
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerScreen
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.monitor.AutocraftingMonitorScreen;
    }

    /** 反射读取 AbstractContainerScreen 的 protected 尺寸字段，失败时返回默认值。 */
    private static int guiSize(final Object screen, final String fieldName, final int fallback) {
        try {
            final java.lang.reflect.Field field =
                net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class
                    .getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (final ReflectiveOperationException e) {
            return fallback;
        }
    }

    private static InteractionHand findTerminalHand(final Player player) {
        if (player.getMainHandItem().getItem() instanceof AdvancedRemoteTerminalItem) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().getItem() instanceof AdvancedRemoteTerminalItem) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private static void sendSwitch(final Player player, final SlotReference slotReference, final int mode) {
        if (!player.level().isClientSide()) {
            return;
        }
        player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
            SoundEvents.UI_BUTTON_CLICK, SoundSource.MASTER, 0.5F, 1.0F);
        PacketDistributor.sendToServer(new SwitchTerminalModePacket(slotReference, mode));
    }

    /** 方块图标 Tab 按钮：选中高亮，悬停显示模式名；不可用模式置灰。 */
    private static final class TerminalModeTabButton extends AbstractWidget {
        private final int mode;
        private final boolean supported;
        private final boolean selected;
        private final Runnable onClick;

        TerminalModeTabButton(final int x,
                              final int y,
                              final int mode,
                              final boolean supported,
                              final boolean selected,
                              final Component tooltip,
                              final Runnable onClick) {
            super(x, y, TAB_W, TAB_H, tooltip);
            this.mode = mode;
            this.supported = supported;
            this.selected = selected;
            this.onClick = onClick;
        }

        @Override
        public void onClick(final double mouseX, final double mouseY) {
            onClick.run();
        }

        /** 懒加载图标物品（客户端注册完成后才安全）。 */
        private static ItemStack iconFor(final int mode) {
            return switch (mode) {
                case 0 -> new ItemStack(Items.CHEST);                 // 合成终端
                case 1 -> new ItemStack(Items.CRAFTING_TABLE);        // 样板终端
                case 2 -> new ItemStack(Blocks.INSTANCE.getAutocrafterManager().getDefault().asItem());
                case 3 -> new ItemStack(Blocks.INSTANCE.getAutocraftingMonitor().getDefault().asItem());
                default -> new ItemStack(com.refinedmods.refinedstorage.common.content.Items.INSTANCE.getPattern());
            };
        }

        @Override
        protected void renderWidget(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
            final int x = getX();
            final int y = getY();
            // 背景：不可用 = 深灰；选中 = 白色边框 + 亮底；未选中 = 深色底
            if (!supported) {
                graphics.fill(x, y, x + TAB_W, y + TAB_H, 0x40202020);
            } else {
                graphics.fill(x, y, x + TAB_W, y + TAB_H, selected ? 0x80303030 : 0x60101010);
            }
            if (selected && supported) {
                graphics.fill(x, y, x + TAB_W, y + 1, 0xFFFFFFFF);
                graphics.fill(x, y + TAB_H - 1, x + TAB_W, y + TAB_H, 0xFFFFFFFF);
                graphics.fill(x, y, x + 1, y + TAB_H, 0xFFFFFFFF);
                graphics.fill(x + TAB_W - 1, y, x + TAB_W, y + TAB_H, 0xFFFFFFFF);
            }
            // 方块图标（居中 16x16）
            final ItemStack icon = iconFor(mode);
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x + (TAB_W - 16) / 2, y + (TAB_H - 16) / 2);
            }
            // 悬停提示
            if (isHoveredOrFocused()) {
                graphics.renderTooltip(Minecraft.getInstance().font, getMessage(), mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(final NarrationElementOutput narrationElementOutput) {
        }
    }
}
