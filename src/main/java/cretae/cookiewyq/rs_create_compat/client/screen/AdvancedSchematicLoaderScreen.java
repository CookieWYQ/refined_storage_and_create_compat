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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 高级蓝图加农炮装填器界面：
 *   - 右栏独立升级槽（空槽悬停提示可放入的升级种类）
 *   - 主库存 12 行（逻辑）；GUI 只显示 6 行，使用 RS 自带 ScrollbarWidget 滚动
 *   - 开关：左标签文字 + 右勾叉小按钮（y 在快捷栏下方）
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
    private static final int BG_H = 364;
    private static final int STORAGE_X = 7;
    private static final int STORAGE_TOP = AdvancedSchematicLoaderMenu.STORAGE_BASE_Y; // 97
    private static final int STORAGE_W_PX = AdvancedSchematicLoaderMenu.COLS
        * AdvancedSchematicLoaderMenu.ROW_SIZE; // 9*18
    private static final int STORAGE_H_PX = AdvancedSchematicLoaderMenu.VISIBLE_ROWS
        * AdvancedSchematicLoaderMenu.ROW_SIZE; // 6*18
    private static final int SCROLLBAR_X = 170;
    private static final int SCROLLBAR_H = STORAGE_H_PX - 2;

    // 开关：y=296..338 (4 行 × 14)，按钮 x=112, 标签 x=10
    private static final int LABEL_X = 10;
    private static final int BTN_X = 112;
    private static final int ROW0_Y = 296;
    private static final int ROW_H = 14;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;
    // 队列运行按钮：底部
    private static final int QUEUE_BTN_Y = 342;
    private static final int QUEUE_BTN_W = 158;
    private static final int QUEUE_BTN_H = 16;

    private ScrollbarWidget scrollbar;

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

        scrollbar = new ScrollbarWidget(
            leftPos + SCROLLBAR_X,
            topPos + STORAGE_TOP + 1,
            ScrollbarWidget.Type.SMALL,
            SCROLLBAR_H
        );
        scrollbar.setListener(offset -> applyScrollOffset((int) Math.round(offset)));
        addWidget(scrollbar);
        updateScrollbarState();
        applyScrollOffset(0);

        for (int i = 0; i < TOGGLE_IDS.length; i++) {
            final int id = TOGGLE_IDS[i];
            final int y = ROW0_Y + i * ROW_H;
            addRenderableWidget(new Button.Builder(toggleState(id), btn -> sendButton(id))
                .bounds(leftPos + BTN_X, topPos + y, BTN_W, BTN_H)
                .build());
        }
        addRenderableWidget(new Button.Builder(
            Component.translatable("gui.rs_create_compat.advanced_schematic_loader.start"),
            btn -> sendButton(4))
            .bounds(leftPos + 8, topPos + QUEUE_BTN_Y, QUEUE_BTN_W, QUEUE_BTN_H).build());
    }

    private void updateScrollbarState() {
        final int rowsExcludingVisible = AdvancedSchematicLoaderMenu.TOTAL_ROWS
            - AdvancedSchematicLoaderMenu.VISIBLE_ROWS;
        final int maxOffset = scrollbar.isSmoothScrolling()
            ? rowsExcludingVisible * AdvancedSchematicLoaderMenu.ROW_SIZE
            : rowsExcludingVisible;
        scrollbar.setEnabled(rowsExcludingVisible > 0);
        scrollbar.setMaxOffset(maxOffset);
    }

    private void applyScrollOffset(final int offset) {
        final int pxOffset = scrollbar.isSmoothScrolling()
            ? offset
            : offset * AdvancedSchematicLoaderMenu.ROW_SIZE;
        final int count = menu.getStorageSlotCount();
        for (int i = 0; i < count; i++) {
            final Slot slot = menu.getStorageSlot(i);
            slot.y = menu.getStorageBaseY(i) - pxOffset;
        }
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
            if (children().get(i) instanceof Button btn) {
                btn.setMessage(toggleState(TOGGLE_IDS[i]));
            }
        }
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 滚动条单独渲染（super.render 里已渲染 widgets，这里补一次滚动条绘制，因它用 addWidget 注册）
        if (scrollbar != null) {
            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, BG_W, BG_H);
    }

    /** 单个 slot 渲染：只有 storage slot 需要裁剪到可见区域内。 */
    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final Slot slot) {
        final boolean isStorage =
            slot.index >= AdvancedSchematicLoaderMenu.STORAGE_START
                && slot.index < AdvancedSchematicLoaderMenu.STORAGE_START
                    + AdvancedSchematicLoaderMenu.STORAGE_COUNT;
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
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.queue"),
            8, 18, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.storage"),
            8, 86, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.upgrades"),
            176, 5, 0xA0A0A0, false);
        final String state = menu.isQueueRunning() ? "RUNNING" : "STOPPED";
        guiGraphics.drawString(font, Component.literal(state),
            120, 6, menu.isQueueRunning() ? 0x55FF55 : 0xFF5555, false);
        for (int i = 0; i < TOGGLE_LABEL_KEYS.length; i++) {
            guiGraphics.drawString(font, Component.translatable(TOGGLE_LABEL_KEYS[i]),
                LABEL_X, ROW0_Y + i * ROW_H + 2, 0xD0D0D0, false);
        }
        guiGraphics.drawString(font, playerInventoryTitle, 8, 204, 0xA0A0A0, false);
    }

    private boolean isStorageSlotVisible(final Slot slot) {
        return slot.y >= STORAGE_TOP && slot.y < STORAGE_TOP + STORAGE_H_PX;
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

    /** 不可见的 storage slot 直接跳过（当作空槽：不能点击、不能拖动取物）。 */
    @Override
    protected boolean isHovering(final Slot slot, final double sx, final double sy) {
        if (super.isHovering(slot, sx, sy)) {
            final boolean isStorage =
                slot.index >= AdvancedSchematicLoaderMenu.STORAGE_START
                    && slot.index < AdvancedSchematicLoaderMenu.STORAGE_START
                        + AdvancedSchematicLoaderMenu.STORAGE_COUNT;
            return !isStorage || isStorageSlotVisible(slot);
        }
        return false;
    }

    /** 阻止玩家从逻辑上取出"隐藏的 storage slot"里的物品（拖动取物兜底）。 */
    @Override
    protected boolean slotClicked(final Slot slot, final int slotId, final int mouseButton,
                                  final net.minecraft.world.inventory.ClickType clickType,
                                  final Player player) {
        if (slot != null) {
            final boolean isStorage =
                slot.index >= AdvancedSchematicLoaderMenu.STORAGE_START
                    && slot.index < AdvancedSchematicLoaderMenu.STORAGE_START
                        + AdvancedSchematicLoaderMenu.STORAGE_COUNT;
            if (isStorage && !isStorageSlotVisible(slot)) {
                return false;
            }
        }
        return super.slotClicked(slot, slotId, mouseButton, clickType, player);
    }

    /** 防止 hovering 计算把看不见的 storage slot 判定为 hovered（tooltip 用）。 */
    @Override
    public Slot findSlot(final double mx, final double my) {
        final Slot s = super.findSlot(mx, my);
        if (s != null
            && s.index >= AdvancedSchematicLoaderMenu.STORAGE_START
            && s.index < AdvancedSchematicLoaderMenu.STORAGE_START + AdvancedSchematicLoaderMenu.STORAGE_COUNT
            && !isStorageSlotVisible(s)) {
            return null;
        }
        return s;
    }

    /** 被拖动的 ghostItem / carried 也不要落入不可见 storage 槽。 */
    @Override
    protected void renderFloatingItem(final GuiGraphics guiGraphics, final int mx, final int my, final float partialTick) {
        final ItemStack carried = menu.getCarried();
        // 先让父类正常渲染
        super.renderFloatingItem(guiGraphics, mx, my, partialTick);
        // 这里无需额外处理：放置逻辑在 tryItemPlace 等中走 slotClicked
    }

    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 父类 findSlot / hoveredSlot 已经跳过不可见 storage，这里直接绘制
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
