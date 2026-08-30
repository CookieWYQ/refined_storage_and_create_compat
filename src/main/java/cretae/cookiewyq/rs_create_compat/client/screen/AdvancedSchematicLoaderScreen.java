package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 高级蓝图加农炮装填器界面：队列 + 大库存 + 开关 + 开始/停止按钮。
 */
public class AdvancedSchematicLoaderScreen extends AbstractContainerScreen<AdvancedSchematicLoaderMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/advanced_schematic_loader.png");

    public AdvancedSchematicLoaderScreen(final AdvancedSchematicLoaderMenu menu,
                                         final Inventory inventory,
                                         final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 446;
        this.inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Button.Builder(Component.literal("A"), btn -> sendButton(0))
            .bounds(leftPos + 8, topPos + 428, 18, 14).build());
        addRenderableWidget(new Button.Builder(Component.literal("D"), btn -> sendButton(1))
            .bounds(leftPos + 28, topPos + 428, 18, 14).build());
        addRenderableWidget(new Button.Builder(Component.literal("R"), btn -> sendButton(2))
            .bounds(leftPos + 48, topPos + 428, 18, 14).build());
        addRenderableWidget(new Button.Builder(Component.literal("G"), btn -> sendButton(3))
            .bounds(leftPos + 68, topPos + 428, 18, 14).build());
        addRenderableWidget(new Button.Builder(Component.translatable("gui.rs_create_compat.advanced_schematic_loader.start"),
            btn -> sendButton(4))
            .bounds(leftPos + 100, topPos + 428, 68, 14).build());
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 446);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 队列 / 存储标签（背景已绘制槽位，标签仅作提示）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.queue"),
            8, 20, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.storage"),
            8, 88, 0xA0A0A0, false);
        // 升级栏标签（右侧独立栏，仿 RS 原版）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.upgrades"),
            176, 100, 0xA0A0A0, false);
        // 状态文字（标题行右侧）
        final String state = menu.isQueueRunning() ? "RUNNING" : "STOPPED";
        guiGraphics.drawString(font, Component.literal(state),
            118, 6, menu.isQueueRunning() ? 0x55FF55 : 0xFF5555, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
