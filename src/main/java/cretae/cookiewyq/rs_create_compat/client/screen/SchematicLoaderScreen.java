package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.support.widget.ScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 蓝图加农炮装填器界面：54 格库存（同类型装填器集群合并内存，滚动条翻页）
 * + 蓝图槽 + 插件槽 + 四个自动开关。
 * 开关布局：左侧文字标签 + 右侧小按钮（开=绿✓ / 关=红✗）。
 */
public class SchematicLoaderScreen extends AbstractContainerScreen<SchematicLoaderMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/schematic_loader.png");

    /** 标题颜色：黑色（浅色面板上白字看不清，全模组机器标题统一口径）。 */
    private static final int COLOR_TITLE = 0xFF333333;

    private static final int[] BTN_IDS = {0, 1, 2};
    private static final String[] BTN_LABEL_KEYS = {
        "gui.rs_create_compat.schematic_loader.print",
        "gui.rs_create_compat.schematic_loader.recycle",
        "gui.rs_create_compat.schematic_loader.gunpowder"
    };
    /** 三个开关各自动作说明的 tooltip 后缀（与 BTN_IDS 一一对应）。 */
    private static final String[] TOGGLE_TIP_KEYS = {"print", "recycle", "gunpowder"};

    // 开关：2 列布局（标签 x=10/100，按钮 x=68/158，行 y=150/166），库存下方、玩家背包上方
    private static final int[] TOGGLE_LABEL_X = {10, 100};
    private static final int[] TOGGLE_BTN_X = {68, 158};
    private static final int ROW0_Y = 150;
    private static final int ROW_H = 16;
    private static final int BTN_W = 14;
    private static final int BTN_H = 12;
    /** 库存区滚动条：库存区 (9..171, 37..145)，滚动条放右侧。 */
    private static final int SCROLLBAR_X = 173;
    private static final int SCROLLBAR_Y = 38;
    private static final int SCROLLBAR_H = 106;

    /** 蓝图槽（Menu (9,19)）的精灵坐标（= Menu 坐标 -1）与悬停范围边长。 */
    private static final int BLUEPRINT_SLOT_X = 8;
    private static final int BLUEPRINT_SLOT_Y = 18;
    private static final int SLOT_HOVER_SIZE = 18;
    /** 锁定覆盖层：暗色 + 简易挂锁图标（不依赖字体 / 贴图）。 */
    private static final int LOCK_OVERLAY_COLOR = 0xA0202020;
    private static final int LOCK_ICON_COLOR = 0xFFE0C040;
    private static final int LOCK_KEYHOLE_COLOR = 0xFF3A2A00;

    private final Button[] toggleButtons = new Button[BTN_IDS.length];
    private ScrollbarWidget clusterScrollbar;
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.RsccRedstoneModeProperty
        rscc$redstoneMode;

    public SchematicLoaderScreen(final SchematicLoaderMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 262;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
    }

    @Override
    protected void init() {
        super.init();

        // 集群滚动条（同类型装填器合并内存时按行滚动浏览）
        clusterScrollbar = new ScrollbarWidget(
            leftPos + SCROLLBAR_X,
            topPos + SCROLLBAR_Y,
            ScrollbarWidget.Type.NORMAL,
            SCROLLBAR_H
        );
        clusterScrollbar.setListener(offset -> {
            final int row = (int) Math.round(offset);
            menu.setRowOffset(row);
            // 通知服务端更新集群行偏移（服务端 ClusterSlot 按此映射翻页内容）
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetClusterRowPacket(menu.containerId, row, 0));
        });
        addWidget(clusterScrollbar);
        // 可见 6 行：最大偏移 = 集群内存总行数 - 6
        final int maxOffset = menu.getTotalRows() - 6;
        clusterScrollbar.setEnabled(maxOffset > 0);
        clusterScrollbar.setMaxOffset(maxOffset);

        for (int i = 0; i < BTN_IDS.length; i++) {
            final int id = BTN_IDS[i];
            final int row = i / 2;
            final int col = i % 2;
            final int y = ROW0_Y + row * ROW_H;
            final Button button = new Button.Builder(getStateFor(id), btn -> sendButton(id))
                .bounds(leftPos + TOGGLE_BTN_X[col], topPos + y, BTN_W, BTN_H)
                .build();
            toggleButtons[i] = button;
            addRenderableWidget(button);
        }
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

    private Component getStateFor(final int id) {
        final boolean on = switch (id) {
            case 0 -> menu.isAutoPrint();
            case 1 -> menu.isAutoRecycle();
            default -> menu.isAutoFillGunpowder();
        };
        // 勾 = 绿色，叉 = 红色
        return Component.literal(on ? "§a✓" : "§c✗");
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
            if (toggleButtons[i] != null) {
                toggleButtons[i].setMessage(getStateFor(BTN_IDS[i]));
            }
        }
        // 每次渲染同步集群滚动条范围（客户端重建后数据包同步集群总行数）
        if (clusterScrollbar != null) {
            final int maxOffset = menu.getTotalRows() - 6;
            clusterScrollbar.setEnabled(maxOffset > 0);
            clusterScrollbar.setMaxOffset(maxOffset);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (clusterScrollbar != null) {
            clusterScrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        // 蓝图槽锁定覆盖层：与高级装填器同集群时，基础装填器不再承担蓝图维度
        renderBlueprintLock(guiGraphics);
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用（槽位高亮已在 super 中处理）
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 在蓝图槽上画「锁定」外观（暗色覆盖 + 简易挂锁）：仅在与高级装填器同集群时出现。 */
    private void renderBlueprintLock(final GuiGraphics guiGraphics) {
        if (!menu.isBlueprintSlotLocked()) {
            return;
        }
        final int x = leftPos + BLUEPRINT_SLOT_X;
        final int y = topPos + BLUEPRINT_SLOT_Y;
        guiGraphics.fill(x, y, x + SLOT_HOVER_SIZE, y + SLOT_HOVER_SIZE, LOCK_OVERLAY_COLOR);
        // 挂锁：锁体 + 锁梁（两侧立柱 + 顶梁） + 锁孔，纯矩形拼出，不依赖字体或额外贴图
        final int cx = x + 9;
        final int cy = y + 9;
        guiGraphics.fill(cx - 4, cy - 3, cx + 4, cy + 4, LOCK_ICON_COLOR);
        guiGraphics.fill(cx - 3, cy - 5, cx - 2, cy - 3, LOCK_ICON_COLOR);
        guiGraphics.fill(cx + 2, cy - 5, cx + 3, cy - 3, LOCK_ICON_COLOR);
        guiGraphics.fill(cx - 3, cy - 6, cx + 3, cy - 5, LOCK_ICON_COLOR);
        guiGraphics.fill(cx, cy - 1, cx + 1, cy + 2, LOCK_KEYHOLE_COLOR);
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        // 背景已包含全部槽位（由 make_gui_bg.py 生成，与 Menu 槽位一一对应）
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 262);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 开关标签（2 列布局，亮白提升可读性）；可用宽度 = 到同行勾叉按钮左侧再留 2px，
        // 英文串较长时截断，保证文字不压按钮
        for (int i = 0; i < BTN_LABEL_KEYS.length; i++) {
            final int row = i / 2;
            final int col = i % 2;
            final int maxWidth = TOGGLE_BTN_X[col] - TOGGLE_LABEL_X[col] - 2;
            final String label = Component.translatable(BTN_LABEL_KEYS[i]).getString();
            guiGraphics.drawString(font, Component.literal(trimToWidth(font, label, maxWidth)),
                TOGGLE_LABEL_X[col], ROW0_Y + row * ROW_H + 2, 0xFFFFFF, false);
        }
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

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int clickedButton) {
        return clusterScrollbar != null && clusterScrollbar.mouseClicked(mouseX, mouseY, clickedButton)
            || super.mouseClicked(mouseX, mouseY, clickedButton);
    }

    @Override
    public void mouseMoved(final double mx, final double my) {
        if (clusterScrollbar != null) {
            clusterScrollbar.mouseMoved(mx, my);
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseReleased(final double mx, final double my, final int button) {
        return clusterScrollbar != null && clusterScrollbar.mouseReleased(mx, my, button)
            || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double z, final double delta) {
        final boolean handled = clusterScrollbar != null
            && !hasShiftDown()
            && !hasControlDown()
            && clusterScrollbar.mouseScrolled(x, y, z, delta);
        return handled || super.mouseScrolled(x, y, z, delta);
    }

    /** 所有槽位 tooltip：物品槽由 super 渲染；空升级槽补充可放升级提示（样式照搬 RS 原版）。 */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 按钮 tooltip：MC 不在 render 中自动渲染控件的 tooltip，必须手动调用（本模组硬规则）
        if (renderButtonTooltips(guiGraphics, mouseX, mouseY)) {
            return;
        }
        // 锁定蓝图槽：显示「已被高级接管」的手动 tooltip（槽位内容不可放入，无需展示交互提示）
        if (menu.isBlueprintSlotLocked() && hoveredSlot != null
            && hoveredSlot.index == SchematicLoaderMenu.getBlueprintSlotIndex()) {
            final java.util.List<Component> always = new java.util.ArrayList<>(1);
            if (!hoveredSlot.getItem().isEmpty()) {
                // 原有蓝图仍可取出：先把名字显示出来（身份信息，常显）
                always.add(hoveredSlot.getItem().getHoverName());
            }
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                guiGraphics, font, always,
                java.util.List.of(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(
                        "gui.rs_create_compat.schematic_loader.blueprint_locked.tip"))),
                mouseX, mouseY);
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

    /** 按钮 tooltip（手动渲染）：优先于槽位 tooltip，命中即返回 true 让调用方跳过后续渲染。
     *  <p>这四个开关的说明是本模组附加信息：默认收起，按住 Shift 才展开（唯一实现 RsccTooltipLayers）。</p> */
    private boolean renderButtonTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
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
