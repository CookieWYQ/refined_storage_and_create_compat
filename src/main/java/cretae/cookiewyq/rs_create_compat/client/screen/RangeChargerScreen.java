package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 范围充电器界面：能量条 + 三轴范围按钮，无玩家背包。
 */
public class RangeChargerScreen extends AbstractContainerScreen<RangeChargerMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/range_charger.png");

    public RangeChargerScreen(final RangeChargerMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 84;
        this.inventoryLabelY = 10000; // 无背包，隐藏默认玩家标签
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // 三行范围调整按钮：每行 [-] 数值 [+]
        addRangeRow(16, 0, 1, Component.literal("X"));
        addRangeRow(33, 2, 3, Component.literal("Y"));
        addRangeRow(50, 4, 5, Component.literal("Z"));
    }

    private void addRangeRow(final int yOffset, final int minusId, final int plusId, final Component label) {
        addRenderableWidget(new Button.Builder(Component.literal("-"), btn -> sendButton(minusId))
            .bounds(leftPos + 63, topPos + yOffset, 20, 14)
            .build());
        addRenderableWidget(new Button.Builder(Component.literal("+"), btn -> sendButton(plusId))
            .bounds(leftPos + 118, topPos + yOffset, 20, 14)
            .build());
    }

    private void drawLabel(final GuiGraphics guiGraphics, final int y, final String text) {
        guiGraphics.drawString(font, Component.literal(text), 30, y + 3, 0xFFFFFF, false);
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 176, 84);
        // 能量条（宽 14，高 40）
        final int energy = menu.getEnergy();
        final int maxEnergy = Math.max(1, menu.getMaxEnergy());
        final int barHeight = (int) (40.0 * energy / maxEnergy);
        guiGraphics.fill(leftPos + 8, topPos + 20 + (40 - barHeight), leftPos + 22, topPos + 20 + 40, 0xFFD03000);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 标题
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 轴标签与范围数值
        drawLabel(guiGraphics, 16, "X");
        drawLabel(guiGraphics, 33, "Y");
        drawLabel(guiGraphics, 50, "Z");
        drawRangeValue(guiGraphics, 16, menu.getRangeX());
        drawRangeValue(guiGraphics, 33, menu.getRangeY());
        drawRangeValue(guiGraphics, 50, menu.getRangeZ());
        // 能量数值与说明
        guiGraphics.drawString(font, Component.literal(menu.getEnergy() + " / " + menu.getMaxEnergy() + " FE"),
            30, 68, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.range_charger.hint"),
            30, 77, 0x606060, false);
    }

    private void drawRangeValue(final GuiGraphics guiGraphics, final int y, final int value) {
        guiGraphics.drawString(font, Component.literal(Integer.toString(value)), 92, y + 3, 0xFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
