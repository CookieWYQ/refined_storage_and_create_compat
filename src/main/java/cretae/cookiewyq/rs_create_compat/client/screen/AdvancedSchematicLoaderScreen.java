package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.support.widget.ScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import net.minecraft.client.gui.Font;
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

    /** 标题颜色：黑色（浅色面板上白字看不清，全模组机器标题统一口径）。 */
    private static final int COLOR_TITLE = 0xFF333333;

    private static final int[] TOGGLE_IDS = {0, 1, 2};
    /** 三个开关各自动作说明的 tooltip 后缀（与 TOGGLE_IDS 一一对应）。 */
    private static final String[] TOGGLE_TIP_KEYS = {"print", "recycle", "gunpowder"};
    private static final String[] TOGGLE_LABEL_KEYS = {
        "gui.rs_create_compat.schematic_loader.print",
        "gui.rs_create_compat.schematic_loader.recycle",
        "gui.rs_create_compat.schematic_loader.gunpowder"
    };
    private static final int BG_W = 210;
    private static final int BG_H = 326;
    private static final int STORAGE_X = 9;
    private static final int STORAGE_TOP = AdvancedSchematicLoaderMenu.STORAGE_BASE_Y; // 83
    private static final int STORAGE_W_PX = AdvancedSchematicLoaderMenu.COLS
        * AdvancedSchematicLoaderMenu.ROW_SIZE; // 9*18 = 162
    private static final int STORAGE_H_PX = 6 * AdvancedSchematicLoaderMenu.ROW_SIZE; // 6 行可见
    /** 库存裁剪区左上角取“精灵坐标”（= Menu 槽位坐标 - 1）：悬停高亮/衬底画在 slot.x-1 起，
     *  若按 Menu 坐标裁剪会切掉上/左各 1px 边框，与“精灵 +1 = Menu”的约定不符。 */
    private static final int STORAGE_CLIP_X = STORAGE_X - 1;   // 8
    private static final int STORAGE_CLIP_Y = STORAGE_TOP - 1; // 82
    private static final int SCROLLBAR_X = 173;
    private static final int SCROLLBAR_Y = STORAGE_TOP + 1;
    private static final int SCROLLBAR_H = STORAGE_H_PX - 2;
    /** 队列区滚动条（高级↔高级共享队列叠加后翻页看全）：与库存滚动条同一列的留白带。 */
    private static final int QUEUE_SCROLLBAR_X = 173;
    private static final int QUEUE_SCROLLBAR_Y = 20;
    private static final int QUEUE_SCROLLBAR_H =
        AdvancedSchematicLoaderMenu.QUEUE_VISIBLE_ROWS * AdvancedSchematicLoaderMenu.ROW_SIZE - 2;
    /** 队列区悬停范围（Menu 坐标）：用于把滚轮事件路由给队列滚动条而不是库存滚动条。 */
    private static final int QUEUE_AREA_X = 9;
    private static final int QUEUE_AREA_Y = 19;
    private static final int QUEUE_AREA_W = 9 * AdvancedSchematicLoaderMenu.ROW_SIZE;
    private static final int QUEUE_AREA_H =
        AdvancedSchematicLoaderMenu.QUEUE_VISIBLE_ROWS * AdvancedSchematicLoaderMenu.ROW_SIZE;
    /** 滚动条命中宽度（无槽位贴图的窄控件，仅用于 tooltip 命中判定）。 */
    private static final int SCROLLBAR_HIT_W = 12;

    // 开关：2 列布局（标签 x=10/100，按钮 x=68/158，行 y=194/210），库存下方、玩家背包上方
    private static final int[] TOGGLE_LABEL_X = {10, 100};
    private static final int[] TOGGLE_BTN_X = {68, 158};
    private static final int ROW0_Y = 194;
    private static final int ROW_H = 16;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;
    // 队列运行按钮：标题右侧（顶部，不与任何槽位重叠；宽 56 不侵入右侧升级栏 x=176 起）
    private static final int QUEUE_BTN_X = 120;
    private static final int QUEUE_BTN_Y = 4;
    private static final int QUEUE_BTN_W = 56;
    private static final int QUEUE_BTN_H = 12;
    /** 标题可占用的最大像素宽（到队列按钮左侧再留 2px），超宽截断，避免压到按钮 / 右侧升级槽。 */
    private static final int TITLE_MAX_W = QUEUE_BTN_X - 8 - 2;

    private ScrollbarWidget scrollbar;
    private ScrollbarWidget queueScrollbar;
    private final Button[] toggleButtons = new Button[TOGGLE_IDS.length];
    private Button queueButton;
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.RsccRedstoneModeProperty
        rscc$redstoneMode;

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
            ScrollbarWidget.Type.NORMAL,
            SCROLLBAR_H
        );
        scrollbar.setListener(offset -> {
            final int row = (int) Math.round(offset);
            menu.setRowOffset(row);
            // 通知服务端更新集群行偏移（服务端 ClusterSlot 按此映射翻页内容）；
            // 队列行偏移一并回传，保证两个滚动条互不覆盖对方的服务端状态。
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetClusterRowPacket(
                    menu.containerId, row, menu.getQueueRowOffset()));
        });
        addWidget(scrollbar);
        // 可见 6 行：最大偏移 = 集群内存总行数 - 6（基础/高级混阶时总行数动态）
        final int maxOffset = menu.getTotalRows() - 6;
        scrollbar.setEnabled(maxOffset > 0);
        scrollbar.setMaxOffset(maxOffset);

        // 队列滚动条（可见 3 行）：高级↔高级同集群后队列叠加，靠它翻页看全
        queueScrollbar = new ScrollbarWidget(
            leftPos + QUEUE_SCROLLBAR_X,
            topPos + QUEUE_SCROLLBAR_Y,
            ScrollbarWidget.Type.NORMAL,
            QUEUE_SCROLLBAR_H
        );
        queueScrollbar.setListener(offset -> {
            final int row = (int) Math.round(offset);
            menu.setQueueRowOffset(row);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetClusterRowPacket(
                    menu.containerId, menu.getRowOffset(), row));
        });
        addWidget(queueScrollbar);
        final int maxQueueOffset = menu.getQueueTotalRows() - AdvancedSchematicLoaderMenu.QUEUE_VISIBLE_ROWS;
        queueScrollbar.setEnabled(maxQueueOffset > 0);
        queueScrollbar.setMaxOffset(maxQueueOffset);

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
        queueButton = new Button.Builder(
            Component.translatable("gui.rs_create_compat.advanced_schematic_loader.start"),
            btn -> sendButton(3))
            .bounds(leftPos + QUEUE_BTN_X, topPos + QUEUE_BTN_Y, QUEUE_BTN_W, QUEUE_BTN_H).build();
        addRenderableWidget(queueButton);
        // 红石模式（忽略 / 高电平 / 低电平）：复用 RS 原版侧边按钮，挂在面板左侧外缘
        rscc$redstoneMode = cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.attach(this);
    }

    /** 红石模式由服务端权威值驱动显示（控件本体复用 RS 原版，hover 时才手动渲染 tooltip）。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (rscc$redstoneMode != null) {
            rscc$redstoneMode.set(menu.rscc$getRedstoneMode().ordinal());
        }
    }

    private Component toggleState(final int id) {
        final boolean on = switch (id) {
            case 0 -> menu.isAutoPrint();
            case 1 -> menu.isAutoRecycle();
            default -> menu.isAutoFillGunpowder();
        };
        // 勾 = 绿色，叉 = 红色
        return Component.literal(on ? "§a✓" : "§c✗");
    }

    private Component queueButtonState() {
        // 运行中 = 红色■停止，停止 = 绿色▶开始
        return menu.isQueueRunning()
            ? Component.literal("§c■ ").append(Component.translatable(
                "gui.rs_create_compat.advanced_schematic_loader.stop"))
            : Component.literal("§a▶ ").append(Component.translatable(
                "gui.rs_create_compat.advanced_schematic_loader.start"));
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
        // 刷新队列运行按钮文字（RUNNING ↔ STOPPED）
        if (queueButton != null) {
            queueButton.setMessage(queueButtonState());
        }
        // 每次渲染同步集群滚动条范围（客户端重建后数据包同步集群总行数）
        if (scrollbar != null) {
            final int maxOffset = menu.getTotalRows() - 6;
            scrollbar.setEnabled(maxOffset > 0);
            scrollbar.setMaxOffset(maxOffset);
        }
        if (queueScrollbar != null) {
            final int maxQueueOffset = menu.getQueueTotalRows() - AdvancedSchematicLoaderMenu.QUEUE_VISIBLE_ROWS;
            queueScrollbar.setEnabled(maxQueueOffset > 0);
            queueScrollbar.setMaxOffset(maxQueueOffset);
        }
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (scrollbar != null) {
            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (queueScrollbar != null) {
            queueScrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用（槽位高亮已在 super 中处理）
        renderTooltip(guiGraphics, mouseX, mouseY);
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
            final int scissorX = leftPos + STORAGE_CLIP_X;
            final int scissorY = topPos + STORAGE_CLIP_Y;
            guiGraphics.enableScissor(scissorX, scissorY, scissorX + STORAGE_W_PX, scissorY + STORAGE_H_PX);
        }
        super.renderSlot(guiGraphics, slot);
        if (isStorage) {
            guiGraphics.disableScissor();
        }
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 标题超宽截断：右侧是队列按钮（x=120），再往右是升级槽列（x=187 起）
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(font, title.getString(), TITLE_MAX_W)),
            titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 资源存储标签：队列(3 行 y=19..73)下方、库存(y=83)上方
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.advanced_schematic_loader.storage"),
            8, 74, 0xFFFFFF, false);
        for (int i = 0; i < TOGGLE_LABEL_KEYS.length; i++) {
            final int row = i / 2;
            final int col = i % 2;
            // 标签可用宽度 = 到同行勾叉按钮左侧再留 2px（英文串较长时截断，保证不压按钮）
            final int maxWidth = TOGGLE_BTN_X[col] - TOGGLE_LABEL_X[col] - 2;
            final String label = Component.translatable(TOGGLE_LABEL_KEYS[i]).getString();
            guiGraphics.drawString(font, Component.literal(trimToWidth(font, label, maxWidth)),
                TOGGLE_LABEL_X[col], ROW0_Y + row * ROW_H + 2, 0xFFFFFF, false);
        }
        // 玩家背包标签已含在背景中，不再绘制
    }

    /** 按像素宽截断字符串（尾部补省略号）：保证文字不越过相邻控件 / 槽位 / 面板边。 */
    private static String trimToWidth(final Font font, final String text, final int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String s = text;
        while (s.length() > 1 && font.width(s + "...") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- 滚动条输入转发 ----

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int clickedButton) {
        return queueScrollbar != null && queueScrollbar.mouseClicked(mouseX, mouseY, clickedButton)
            || scrollbar != null && scrollbar.mouseClicked(mouseX, mouseY, clickedButton)
            || super.mouseClicked(mouseX, mouseY, clickedButton);
    }

    @Override
    public void mouseMoved(final double mx, final double my) {
        if (queueScrollbar != null) {
            queueScrollbar.mouseMoved(mx, my);
        }
        if (scrollbar != null) {
            scrollbar.mouseMoved(mx, my);
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseReleased(final double mx, final double my, final int button) {
        return queueScrollbar != null && queueScrollbar.mouseReleased(mx, my, button)
            || scrollbar != null && scrollbar.mouseReleased(mx, my, button)
            || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double z, final double delta) {
        if (!hasShiftDown() && !hasControlDown()) {
            // 队列区内的滚轮事件优先给队列滚动条（否则玩家在队列上滚动会莫名其妙翻库存）
            final boolean overQueue = x >= leftPos + QUEUE_AREA_X && x < leftPos + QUEUE_AREA_X + QUEUE_AREA_W
                && y >= topPos + QUEUE_AREA_Y && y < topPos + QUEUE_AREA_Y + QUEUE_AREA_H;
            if (overQueue && queueScrollbar != null && queueScrollbar.mouseScrolled(x, y, z, delta)) {
                return true;
            }
            if (scrollbar != null && scrollbar.mouseScrolled(x, y, z, delta)) {
                return true;
            }
        }
        return super.mouseScrolled(x, y, z, delta);
    }

    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 按钮 tooltip：MC 不在 render 中自动渲染控件的 tooltip，必须手动调用（本模组硬规则）
        if (renderButtonTooltips(guiGraphics, mouseX, mouseY)) {
            return;
        }
        // 队列滚动条 tooltip（本模组硬规则：GUI 功能都要有 tooltip，且必须手动渲染）
        final int qsx = leftPos + QUEUE_SCROLLBAR_X;
        final int qsy = topPos + QUEUE_SCROLLBAR_Y;
        if (mouseX >= qsx && mouseX < qsx + SCROLLBAR_HIT_W
            && mouseY >= qsy && mouseY < qsy + QUEUE_SCROLLBAR_H) {
            // 控制说明属于本模组附加信息：默认收起，按住 Shift 才展开（唯一实现 RsccTooltipLayers）
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    "gui.rs_create_compat.advanced_schematic_loader.queue_scroll.tip"), mouseX, mouseY);
            return;
        }
        // 槽里已放入堆叠升级：物品自身 tooltip + 「本机每格上限」效果行。
        // 必须在 super 之前 return —— super 会先画一遍物品 tooltip，之后我们再画一份就会同格叠两份。
        if (hoveredSlot != null && menu.getCarried().isEmpty()
            && cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips
                .renderFilledStackUpgradeEffect(hoveredSlot, menu.getStackUpgradeCount(),
                    menu.getStorageSlotCapacity(), guiGraphics, font, mouseX, mouseY)) {
            return;
        }
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot instanceof cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot upgradeSlot
            && hoveredSlot.getItem().isEmpty()
            && menu.getCarried().isEmpty()) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips.render(
                upgradeSlot, guiGraphics, font, mouseX, mouseY);
        }
    }

    /** 按钮 tooltip（手动渲染）：优先于滚动条 / 槽位 tooltip，命中即返回 true。
     *  <p>按钮说明属于本模组附加信息：默认收起，按住 Shift 才展开（唯一实现 RsccTooltipLayers）。</p> */
    private boolean renderButtonTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (queueButton != null && queueButton.isMouseOver(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    "gui.rs_create_compat.advanced_schematic_loader.tip.start"), mouseX, mouseY);
            return true;
        }
        for (int i = 0; i < toggleButtons.length; i++) {
            if (toggleButtons[i] != null && toggleButtons[i].isMouseOver(mouseX, mouseY)) {
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                    guiGraphics, font, Component.translatable(
                        "gui.rs_create_compat.schematic_loader.tip." + TOGGLE_TIP_KEYS[i]), mouseX, mouseY);
                return true;
            }
        }
        return false;
    }
}
