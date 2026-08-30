package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedRemoteTerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 高级远程多功能终端界面：模式切换按钮 + 当前模式信息展示。
 * 监视器模式为特殊入口：普通右键打开 RS 原版自动合成仓监视器（见物品 use 逻辑）。
 */
public class AdvancedRemoteTerminalScreen extends AbstractContainerScreen<AdvancedRemoteTerminalMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/advanced_remote_terminal.png");

    private static final String[] MODE_KEYS = {
        "gui.rs_create_compat.advanced_remote_terminal.mode.grid",
        "gui.rs_create_compat.advanced_remote_terminal.mode.patterns",
        "gui.rs_create_compat.advanced_remote_terminal.mode.manager",
        "gui.rs_create_compat.advanced_remote_terminal.mode.monitor",
        "gui.rs_create_compat.advanced_remote_terminal.mode.sequence"
    };

    private final List<Button> modeButtons = new java.util.ArrayList<>();

    public AdvancedRemoteTerminalScreen(final AdvancedRemoteTerminalMenu menu,
                                        final Inventory inventory,
                                        final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 120;
        this.inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        modeButtons.clear();
        for (int i = 0; i < 5; i++) {
            final int index = i;
            final int x = 8 + (index % 3) * 54;
            final int y = 24 + (index / 3) * 18;
            final Button button = new Button.Builder(Component.translatable(MODE_KEYS[index]),
                btn -> sendButton(index))
                .bounds(leftPos + x, topPos + y, 50, 16)
                .build();
            modeButtons.add(button);
            addRenderableWidget(button);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // 仿 RS 原版：没电时界面打开但操作禁用（创造版无视电量）
        final boolean noEnergy = menu.getEnergy() <= 0 && !menu.isCreative();
        for (final Button button : modeButtons) {
            button.active = !noEnergy;
        }
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 176, 120);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 能量
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.advanced_remote_terminal.energy",
                menu.getEnergy(), menu.getMaxEnergy()),
            8, 62, 0xA0A0A0, false);
        // 网络状态
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.advanced_remote_terminal.online",
                menu.isOnline() ? "✓" : "✗"),
            8, 74, menu.isOnline() ? 0x55FF55 : 0xFF5555, false);
        // 没电提示：仿 RS 原版，界面照常打开但操作被禁用
        final boolean noEnergy = menu.getEnergy() <= 0 && !menu.isCreative();
        if (noEnergy) {
            guiGraphics.drawString(font,
                Component.translatable("gui.rs_create_compat.advanced_remote_terminal.no_energy_hint"),
                8, 86, 0xFF5555, false);
        }
        // 当前模式信息
        final int mode = menu.getMode();
        guiGraphics.drawString(font, Component.translatable(MODE_KEYS[mode]), 8, 96, 0xFFFFFF, false);
        switch (mode) {
            case AdvancedRemoteTerminalItem.MODE_GRID ->
                guiGraphics.drawString(font,
                    Component.translatable("gui.rs_create_compat.advanced_remote_terminal.grid.stored",
                        menu.getStored()),
                    8, 108, 0xA0A0A0, false);
            case AdvancedRemoteTerminalItem.MODE_PATTERNS ->
                guiGraphics.drawString(font,
                    Component.translatable("gui.rs_create_compat.advanced_remote_terminal.patterns.count",
                        menu.getPatternCount()),
                    8, 108, 0xA0A0A0, false);
            case AdvancedRemoteTerminalItem.MODE_MANAGER ->
                guiGraphics.drawString(font,
                    Component.translatable("gui.rs_create_compat.advanced_remote_terminal.tasks.count",
                        menu.getTaskCount()),
                    8, 108, 0xA0A0A0, false);
            case AdvancedRemoteTerminalItem.MODE_MONITOR ->
                guiGraphics.drawString(font,
                    Component.translatable("gui.rs_create_compat.advanced_remote_terminal.monitor.hint"),
                    8, 108, 0xA0A0A0, false);
            default ->
                guiGraphics.drawString(font,
                    Component.translatable("gui.rs_create_compat.advanced_remote_terminal.sequence.pending"),
                    8, 108, 0xA0A0A0, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
