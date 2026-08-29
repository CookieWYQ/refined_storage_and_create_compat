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
 * 范围充电器界面：显示能量条，用按钮调整三轴充电范围。
 */
public class RangeChargerScreen extends AbstractContainerScreen<RangeChargerMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/range_charger.png");

    public RangeChargerScreen(final RangeChargerMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        // 三行范围调整按钮：每行 [-] 数值 [+]（X 轴 id 0/1，Y 轴 2/3，Z 轴 4/5）
        addRangeButtons(31, 0, 1);
        addRangeButtons(50, 2, 3);
        addRangeButtons(69, 4, 5);
    }

    private void addRangeButtons(final int yOffset, final int minusId, final int plusId) {
        addRenderableWidget(new Button.Builder(Component.literal("-"), btn -> sendButton(minusId))
            .bounds(leftPos + 118, topPos + yOffset, 18, 14)
            .build());
        addRenderableWidget(new Button.Builder(Component.literal("+"), btn -> sendButton(plusId))
            .bounds(leftPos + 152, topPos + yOffset, 18, 14)
            .build());
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 176, 166);
        // 能量条（宽 14，高 40）
        final int energy = menu.getEnergy();
        final int maxEnergy = Math.max(1, menu.getMaxEnergy());
        final int barHeight = (int) (40.0 * energy / maxEnergy);
        guiGraphics.fill(leftPos + 9, topPos + 21 + (40 - barHeight), leftPos + 22, topPos + 21 + 40, 0xFFD03000);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderLabels(guiGraphics, mouseX, mouseY);
        // 范围数值
        drawRangeValue(guiGraphics, 31, menu.getRangeX());
        drawRangeValue(guiGraphics, 50, menu.getRangeY());
        drawRangeValue(guiGraphics, 69, menu.getRangeZ());
        // 能量数值
        guiGraphics.drawString(font, Component.literal(menu.getEnergy() + " / " + menu.getMaxEnergy() + " FE"),
            30, 100, 0x404040, false);
    }

    private void drawRangeValue(final GuiGraphics guiGraphics, final int y, final int value) {
        guiGraphics.drawString(font, Component.literal(Integer.toString(value)), 137, y + 3, 0x404040, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
