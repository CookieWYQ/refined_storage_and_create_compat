package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.support.widget.ScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 高级蓝图加农炮装填器界面（压缩高度 320）：
 *   - 右栏独立升级槽（空槽悬停提示可放入的升级种类）
 *   - 队列 3 行 + 库存 6 行可见（同类型高级装填器集群合并内存，右侧滚动条翻页）
 *   - 开关：左标签文字 + 右勾叉小按钮（y 在库存下方、玩家背包上方）
 *   - 队列运行：底部 START/STOP 大按钮
 */
public class AdvancedSchematicLoaderScreen extends AbstractContainerScreen<AdvancedSchematicLoaderMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/advanced_schematic_loader.png");

    private static final int[] TOGGLE_IDS = {0, 1, 2, 3};
    private static final String[] TOGGLE_LABEL_KEYS = {
        "gui.rs_create_compat.schematic_loader.print",
        "gui.rs_create_compat.schematic_loader.deploy",
        "gui.rs_create_compat.schematic_loader.recycle",
        "gui.rs_create_compat.schematic_loader.gunpowder"
    };
    private static final int BG_W = 210;
    private static final int BG_H = 329;
    private static final int STORAGE_X = 9;
    private static final int STORAGE_TOP = AdvancedSchematicLoaderMenu.STORAGE_BASE_Y; // 71
    private static final int STORAGE_W_PX = AdvancedSchematicLoaderMenu.COLS
        * AdvancedSchematicLoaderMenu.ROW_SIZE; // 9*18 = 162
    private static final int STORAGE_H_PX = 6 * AdvancedSchematicLoaderMenu.ROW_SIZE; // 6 行可见
    private static final int SCROLLBAR_X = 171;
    private static final int SCROLLBAR_Y = STORAGE_TOP + 1;
    private static final int SCROLLBAR_H = STORAGE_H_PX - 2;

    // 开关：2 列 × 2 行紧凑布局（标签 x=10/100，按钮 x=68/158，行 y=180/196）
    private static final int[] TOGGLE_LABEL_X = {10, 100};
    private static final int[] TOGGLE_BTN_X = {68, 158};
    private static final int ROW0_Y = 180;
    private static final int ROW_H = 16;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;
    // 队列运行按钮：底部（玩家背包下方）
    private static final int QUEUE_BTN_Y = 316;
    private static final int QUEUE_BTN_W = 158;
    private static final int QUEUE_BTN_H = 16;

    private ScrollbarWidget scrollbar;
    private final Button[] toggleButtons = new Button[TOGGLE_IDS.length];

    public AdvancedSchematicLoaderScreen(final AdvancedSchematicLoaderMenu menu,
                                         final Inventory inventory,
                                         final Component title) {
        super(menu, inventory, title);
        this.imageWidth = BG_W;
        this.imageHeight = BG_H;
        this.inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();

        // 集群滚动条（同类型高级装填器合并内存时按行滚动浏览）
        scrollbar = new ScrollbarWidget(
            leftPos + SCROLLBAR_X,
            topPos + SCROLLBAR_Y,
            ScrollbarWidget.Type.SMALL,
            SCROLLBAR_H
        );
        scrollbar.setListener(offset -> menu.setRowOffset((int) Math.round(offset)));
        addWidget(scrollbar);
        // 每个装填器 12 行，可见 6 行：最大偏移 = 集群×12 - 6
        final int maxOffset = menu.getClusterSize() * 12 - 6;
        scrollbar.setEnabled(maxOffset > 0);
        scrollbar.setMaxOffset(maxOffset);

        for (int i = 0; i < TOGGLE_IDS.length; i++) {
            final int id = TOGGLE_IDS[i];
            final int row = i / 2;
            final int col = i % 2;
            final int y = ROW0_Y + row * ROW_H;
            final Button button = new Button.Builder(toggleState(id), btn -> sendButton(id))
                .bounds(leftPos + TOGGLE_BTN_X[col], topPos + y, BTN_W, BTN_H)
                .build();
            toggleButtons[i] = button;
            addRenderableWidget(button);
        }
        addRenderableWidget(new Button.Builder(
            Component.translatable("gui.rs_create_compat.advanced_schematic_loader.start"),
            btn -> sendButton(4))
            .bounds(leftPos + 8, topPos + QUEUE_BTN_Y, QUEUE_BTN_W, QUEUE_BTN_H).build());
    }

    private Component toggleState(final int id) {
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

    // ------- 渲染 -------

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        // 刷新四个 toggle 按钮符号
        for (int i = 0; i < TOGGLE_IDS.length; i++) {
            if (toggleButtons[i] != null) {
                toggleButtons[i].setMessage(toggleState(TOGGLE_IDS[i]));
            }
        }
        // 每次渲染同步集群滚动条范围（客户端重建后数据包同步集群数）
        if (scrollbar != null) {
            final int maxOffset = menu.getClusterSize() * 12 - 6;
            scrollbar.setEnabled(maxOffset > 0);
            scrollbar.setMaxOffset(maxOffset);
        }
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (scrollbar != null) {
            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, BG_W, BG_H);
    }

    /** 单个 slot 渲染：只有 storage slot 需要裁剪到可见区域内（仅显示前 6 行）。 */
    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final net.minecraft.world.inventory.Slot slot) {
        final boolean isStorage =
            slot.index >= AdvancedSchematicLoaderMenu.STORAGE_START
                && slot.index < AdvancedSchematicLoaderMenu.STORAGE_START
                    + AdvancedSchematicLoaderMenu.STORAGE_VISIBLE;
        if (isStorage) {
            final int scissorX = leftPos + STORAGE_X;
            final int scissorY = topPos + STORAGE_TOP;
            guiGraphics.enableScissor(scissorX, scissorY, scissorX + STORAGE_W_PX, scissorY + STORAGE_H_PX);
        }
        super.renderSlot(guiGraphics, slot);
        if (isStorage) {
            guiGraphics.disableScissor();
        }
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.storage"),
            8, 62, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.upgrades"),
            176, 5, 0xA0A0A0, false);
        final String state = menu.isQueueRunning() ? "RUNNING" : "STOPPED";
        guiGraphics.drawString(font, Component.literal(state),
            120, 6, menu.isQueueRunning() ? 0x55FF55 : 0xFF5555, false);
        for (int i = 0; i < TOGGLE_LABEL_KEYS.length; i++) {
            final int row = i / 2;
            final int col = i % 2;
            guiGraphics.drawString(font, Component.translatable(TOGGLE_LABEL_KEYS[i]),
                TOGGLE_LABEL_X[col], ROW0_Y + row * ROW_H + 2, 0xFFFFFF, false);
        }
        // 玩家背包文字（库存下方按钮区之后）
        guiGraphics.drawString(font, playerInventoryTitle, 8, 234, 0xA0A0A0, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- 滚动条输入转发 ----

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int clickedButton) {
        return scrollbar != null && scrollbar.mouseClicked(mouseX, mouseY, clickedButton)
            || super.mouseClicked(mouseX, mouseY, clickedButton);
    }

    @Override
    public void mouseMoved(final double mx, final double my) {
        if (scrollbar != null) {
            scrollbar.mouseMoved(mx, my);
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseReleased(final double mx, final double my, final int button) {
        return scrollbar != null && scrollbar.mouseReleased(mx, my, button)
            || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double z, final double delta) {
        final boolean handled = scrollbar != null
            && !hasShiftDown()
            && !hasControlDown()
            && scrollbar.mouseScrolled(x, y, z, delta);
        return handled || super.mouseScrolled(x, y, z, delta);
    }

    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot != null
            && hoveredSlot.index >= AdvancedSchematicLoaderMenu.UPGRADE_START
            && hoveredSlot.index < AdvancedSchematicLoaderMenu.UPGRADE_START + AdvancedSchematicLoaderMenu.UPGRADE_COUNT
            && hoveredSlot.getItem().isEmpty()) {
            guiGraphics.renderComponentTooltip(font,
                cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.getEmptyTooltip(),
                mouseX, mouseY);
        }
    }
}
