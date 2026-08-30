package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 定量保持器界面：标记槽 + 插件槽 + 目标数量/销毁开关控制。
 */
public class QuantityKeeperScreen extends AbstractContainerScreen<QuantityKeeperMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/quantity_keeper.png");

    public QuantityKeeperScreen(final QuantityKeeperMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 166;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
    }

    @Override
    protected void init() {
        super.init();
        // 目标数量 +/- 按钮
        addRenderableWidget(new Button.Builder(Component.literal("-"), btn -> sendButton(0))
            .bounds(leftPos + 128, topPos + 20, 20, 14)
            .build());
        addRenderableWidget(new Button.Builder(Component.literal("+"), btn -> sendButton(1))
            .bounds(leftPos + 150, topPos + 20, 20, 14)
            .build());
        // 销毁开关按钮
        addRenderableWidget(new Button.Builder(Component.translatable("gui.rs_create_compat.quantity_keeper.destroy"),
            btn -> sendButton(2))
            .bounds(leftPos + 110, topPos + 42, 60, 16)
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
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 166);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 标记标签
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.marker"),
            8, 8, 0xA0A0A0, false);
        // 目标数量
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.target",
            Integer.toString(menu.getTargetAmount())), 100, 24, 0xFFFFFF, false);
        // 升级栏标签（右侧独立栏，仿 RS 原版）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.upgrades"),
            176, 100, 0xA0A0A0, false);
        // 升级信息
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.quantity_keeper.speed", menu.getSpeedUpgradeCount()),
            100, 62, 0xA0A0A0, false);
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.quantity_keeper.autocrafting",
                menu.hasAutocraftingUpgrade() ? "✓" : "✗"),
            100, 72, 0xA0A0A0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
