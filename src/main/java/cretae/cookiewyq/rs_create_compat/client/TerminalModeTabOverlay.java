package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.content.Blocks;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.network.SwitchTerminalModePacket;
import cretae.cookiewyq.rs_create_compat.support.RsccTerminalLocator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * 高级远程多功能终端：在打开的 RS 原版界面（合成终端 / 样板终端 / 合成仓管理 / 合成仓监视）
 * 右下角叠加方块图标模式切换 Tab（参照 Universal-Grid 的方块 Tab 设计）。
 * 点击 Tab → 发送 C2S 包 → 服务端把模式写回物品并重开对应界面。
 * <p>
 * <b>只有「由本模组终端打开的界面」才有这一排</b>：判据是 {@link TerminalOpenIntent} 的打开意图标记
 * （按键 / 手持右键 / 点击模式按钮重开界面时置位），而<b>不是</b>「玩家身上带着终端」——
 * 后者会让「背包里有终端」时打开任意 RS 界面都多出这一排（用户实测回归）。
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
@SuppressWarnings({"deprecation"})
public final class TerminalModeTabOverlay {
    private static final int TAB_W = 24;
    private static final int TAB_H = 22;
    private static final String[] MODE_KEYS = {
        "gui.rs_create_compat.advanced_remote_terminal.mode.grid",
        "gui.rs_create_compat.advanced_remote_terminal.mode.patterns",
        "gui.rs_create_compat.advanced_remote_terminal.mode.manager",
        "gui.rs_create_compat.advanced_remote_terminal.mode.monitor",
        "gui.rs_create_compat.advanced_remote_terminal.mode.sequence",
        "gui.rs_create_compat.advanced_remote_terminal.mode.unit_manager"
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
        // 回归修复：只有「由本模组终端主动打开」的界面才叠加这一排。之前的判定是「玩家身上带着终端」，
        // 自从查找范围扩到「背包任意格」，只要背包里有终端，打开自动合成管理器等任意 RS 界面都会多出这一排。
        // 意图标记未置位（超时 / 已被别的界面消费）→ 一个按钮都不加。
        if (!TerminalOpenIntent.consume(screen)) {
            return;
        }
        final Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 与服务端权威查找共用同一套优先级（主手 → 副手 → 背包 0..35 → Curios），
        // 保证本地读写模式的栈就是服务端打开的那一台；缺一不可（拿不到栈就没法本地读写模式，Tab 一个都不渲染）。
        final Optional<RsccTerminalLocator.Location> located = RsccTerminalLocator.locate(player);
        if (located.isEmpty()) {
            return;
        }
        final SlotReference slotReference = located.get().slotReference();
        final ItemStack terminalStack = located.get().stack();
        final int currentMode = AdvancedRemoteTerminalItem.getMode(terminalStack);
        final int count = AdvancedRemoteTerminalItem.MODE_COUNT;
        // imageWidth / imageHeight 为 AbstractContainerScreen 的 protected 字段，外部无法直接访问，用反射取值
        final int guiW = guiSize(containerScreen, "imageWidth", 176);
        final int guiH = guiSize(containerScreen, "imageHeight", 166);
        for (int i = 0; i < count; i++) {
            final int mode = i;
            // 位置：GUI 右下角外侧（不遮挡界面内容）
            final int x = containerScreen.getGuiLeft() + guiW + 2;
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
                    if (mode != currentMode) {
                        // 本地立即更新物品模式，保证界面重开后 Tab 高亮正确（服务端物品不同步到客户端）
                        AdvancedRemoteTerminalItem.setMode(terminalStack, mode);
                        // 切换后界面会由服务端重开：重新置位打开意图，否则新界面拿不到这一排按钮
                        TerminalOpenIntent.mark();
                        // 仿照 Universal-Grid：读取鼠标原生窗口坐标随切换包发送，
                        // 服务端重开界面后回发坐标恢复鼠标（避免跳回屏幕中心）
                        final long window = Minecraft.getInstance().getWindow().getWindow();
                        final double[] cursorX = new double[1];
                        final double[] cursorY = new double[1];
                        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, cursorX, cursorY);
                        sendSwitch(player, slotReference, mode,
                            (int) Math.round(cursorX[0]), (int) Math.round(cursorY[0]));
                    }
                }));
        }
    }

    /** 判断是否为 RS 的网格 / 样板 / 合成仓管理 / 合成仓监视 / 本模组序列样板终端界面（仅客户端存在这些类）。 */
    private static boolean isRsScreen(final Screen screen) {
        return screen instanceof com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen<?>
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridScreen
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerScreen
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.monitor.AutocraftingMonitorScreen
            || screen instanceof cretae.cookiewyq.rs_create_compat.client.screen.SequencePatternTerminalScreen
            || screen instanceof cretae.cookiewyq.rs_create_compat.client.screen.UnitPatternManagerScreen;
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

    /** 把模式切换请求（含鼠标原生窗口坐标）发给服务端；服务端写回模式并重开界面。 */
    private static void sendSwitch(final Player player, final SlotReference slotReference,
                                   final int mode, final int cursorX, final int cursorY) {
        if (!player.level().isClientSide()) {
            return;
        }
        player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
            SoundEvents.UI_BUTTON_CLICK, SoundSource.MASTER, 0.5F, 1.0F);
        PacketDistributor.sendToServer(new SwitchTerminalModePacket(slotReference, mode, cursorX, cursorY));
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
                case 4 -> new ItemStack(cretae.cookiewyq.rs_create_compat.RS_Create_Compat
                    .SEQUENCE_PATTERN_TERMINAL_BLOCK.get().asItem()); // 序列装配终端方块
                default -> new ItemStack(cretae.cookiewyq.rs_create_compat.RS_Create_Compat
                    .UNIT_PATTERN_MANAGER_BLOCK.get().asItem()); // 单元样板管理舱方块
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
