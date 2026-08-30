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
 * 开关布局：左侧文字标签 + 右侧小按钮（开=绿✓ / 关=红✗）。
 */
public class SchematicLoaderScreen extends AbstractContainerScreen<SchematicLoaderMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/schematic_loader.png");

    private static final int[] BTN_IDS = {0, 1, 2, 3};
    private static final String[] BTN_LABEL_KEYS = {
        "gui.rs_create_compat.schematic_loader.print",
        "gui.rs_create_compat.schematic_loader.deploy",
        "gui.rs_create_compat.schematic_loader.recycle",
        "gui.rs_create_compat.schematic_loader.gunpowder"
    };

    /** 每个 toggle 的位置：(labelX, labelY, btnX, btnY) —— 主区域下方，4 行 × 2 列，右侧独立栏上方。 */
    private static final int LABEL_X = 10;
    private static final int BTN_X = 108;
    private static final int ROW0_Y = 120;
    private static final int ROW_H = 16;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;

    public SchematicLoaderScreen(final SchematicLoaderMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 222;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < BTN_IDS.length; i++) {
            final int id = BTN_IDS[i];
            final int y = ROW0_Y + i * ROW_H;
            addRenderableWidget(new Button.Builder(getStateFor(id), btn -> sendButton(id))
                .bounds(leftPos + BTN_X, topPos + y, BTN_W, BTN_H)
                .build());
        }
    }

    private Component getStateFor(final int id) {
        final boolean on = switch (id) {
            case 0 -> menu.isAutoPrint();
            case 1 -> menu.isAutoDeploy();
            case 2 -> menu.isAutoRecycle();
            default -> menu.isAutoFillGunpowder();
        };
        return Component.literal(on ? "✓" : "✗");
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        // 刷新按钮上的 ✓/✗ 文字（状态改变后同步）
        for (int i = 0; i < BTN_IDS.length; i++) {
            if (children().get(i) instanceof Button btn) {
                btn.setMessage(getStateFor(BTN_IDS[i]));
            }
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        // 背景已包含全部槽位（由 make_gui_bg.py 生成，与 Menu 槽位一一对应）
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 222);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 升级栏标签（右侧独立栏，仿 RS 原版）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.schematic_loader.upgrades"),
            176, 5, 0xA0A0A0, false);
        // 开关标签（左侧文字）
        for (int i = 0; i < BTN_LABEL_KEYS.length; i++) {
            guiGraphics.drawString(font, Component.translatable(BTN_LABEL_KEYS[i]),
                LABEL_X, ROW0_Y + i * ROW_H + 2, 0xD0D0D0, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 空升级槽悬停提示：升级槽（slot 1..6）为空时显示可放入的升级种类。 */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot != null
            && hoveredSlot.index >= 1 && hoveredSlot.index <= 6
            && hoveredSlot.getItem().isEmpty()) {
            guiGraphics.renderComponentTooltip(font,
                cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.getEmptyTooltip(),
                mouseX, mouseY);
        }
    }
}
