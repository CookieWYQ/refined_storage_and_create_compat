package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 蓝图加农炮装填器界面：54 格库存 + 蓝图槽 + 插件槽 + 四个自动开关。
 */
public class SchematicLoaderScreen extends AbstractContainerScreen<SchematicLoaderMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/schematic_loader.png");

    public SchematicLoaderScreen(final SchematicLoaderMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 208;
        this.imageHeight = 222;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
    }

    @Override
    protected void init() {
        super.init();
        addToggleButton(0, 172, 110, "gui.rs_create_compat.schematic_loader.print");
        addToggleButton(1, 172, 126, "gui.rs_create_compat.schematic_loader.deploy");
        addToggleButton(2, 172, 142, "gui.rs_create_compat.schematic_loader.recycle");
        addToggleButton(3, 172, 158, "gui.rs_create_compat.schematic_loader.gunpowder");
    }

    private void addToggleButton(final int id, final int x, final int y, final String labelKey) {
        addRenderableWidget(new Button.Builder(Component.translatable(labelKey), btn -> sendButton(id))
            .bounds(leftPos + x, topPos + y, 30, 14)
            .build());
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        // 背景已包含全部槽位（由 make_gui_bg.py 生成，与 Menu 槽位一一对应）
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 208, 222);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 蓝图槽 / 插件标签
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.schematic_loader.blueprint"),
            170, 5, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.schematic_loader.upgrades"),
            170, 28, 0xA0A0A0, false);
        // 开关状态
        drawToggleState(guiGraphics, 110, 0);
        drawToggleState(guiGraphics, 126, 1);
        drawToggleState(guiGraphics, 142, 2);
        drawToggleState(guiGraphics, 158, 3);
    }

    private void drawToggleState(final GuiGraphics guiGraphics, final int y, final int index) {
        final boolean on = switch (index) {
            case 0 -> menu.isAutoPrint();
            case 1 -> menu.isAutoDeploy();
            case 2 -> menu.isAutoRecycle();
            default -> menu.isAutoFillGunpowder();
        };
        guiGraphics.drawString(font, Component.literal(on ? "ON" : "OFF"), 204, y + 3, on ? 0x55FF55 : 0xFF5555, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
