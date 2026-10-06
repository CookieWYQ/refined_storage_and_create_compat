package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RecipeTypeMachines;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu;
import cretae.cookiewyq.rs_create_compat.network.CloseChamberUnitsSummaryPacket;
import cretae.cookiewyq.rs_create_compat.network.RequestChamberUnitsPacket;
import cretae.cookiewyq.rs_create_compat.network.SetChamberUnitsPagePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberUnitsPacket;
import cretae.cookiewyq.rs_create_compat.network.TransferChamberUnitPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 序列装配样板终端 →「执行仓单元样板汇总」<b>真槽位</b>界面。
 *
 * <p><b>用户需求（B11 / Round4 重做）</b>：完全参照精致存储（RS）<b>自动合成管理器</b>的设计方法与设计理念重做——
 * 不再是「每行两个按钮 + 文本行」，而是<b>真正的 Menu 槽位网格</b>：点击取整叠、Shift 快速移动、拖拽放置、
 * 分页/轮询、悬停高亮、槽位 tooltip 齐备；每行同时标明<b>机器图标 + 执行仓名字 + 配方 id</b>。</p>
 *
 * <p><b>参考的 RS 实现（理念来源）</b>：</p>
 * <ul>
 *     <li>{@code refinedstorage-common/.../autocrafting/monitor/AutocraftingMonitorScreen.java}：条目列表 +
 *     选中项 + 底部动作按钮 + 滚动条 + 手动 tooltip 的骨架；</li>
 *     <li>{@code .../autocrafting/monitor/AbstractAutocraftingMonitorContainerMenu.java}：菜单持动态数据、
 *     变化即回推；</li>
 *     <li>{@code .../autocrafting/patterngrid/PatternGridScreen.java} 与
 *     {@code .../grid/screen/AbstractGridScreen.java}：真 {@link Slot} 网格的取放范式。</li>
 * </ul>
 *
 * <p><b>硬规则</b>：所有 {@code drawString} 一律传 {@code false}（无阴影，含 {@link NoShadowButton}）；
 * tooltip 全部手绘（槽位内物品走手动 {@link #renderTooltip}，行 / 按钮走 {@link #renderCustomTooltips}），
 * 且 hover 判定与绘制范围严格同源。</p>
 */
public class ChamberUnitsSummaryScreen extends AbstractContainerScreen<ChamberUnitsSummaryMenu> {
    private static final String LANG = "gui.rs_create_compat.sequence_pattern_terminal.summary.";

    /**
     * 自绘面板 PNG 替换点（见 {@code CHAMBER_UNITS_SUMMARY_GUI_DOC.md}）：
     * 只要该贴图被交付且尺寸 = {@link ChamberUnitsSummaryMenu#PANEL_W}×{@link ChamberUnitsSummaryMenu#PANEL_H}，
     * 背景自动改用整图 {@code blit}；尺寸不符 / 未交付则自动退回原版九宫格，永不出现拉伸。
     */
    private static final ResourceLocation CUSTOM_PANEL =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "textures/gui/chamber_units_summary.png");

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF6A6A6A;
    /** 奇数行斑马纹（10% 白）。 */
    private static final int COLOR_ROW_ALT = 0x14FFFFFF;
    /** 选中行底纹（18% 白）。 */
    private static final int COLOR_ROW_SELECTED = 0x2EFFFFFF;
    /** 选中行左侧强调条（2px）。 */
    private static final int COLOR_ROW_ACCENT = 0xFF4A78C8;

    /** 名字 / 配方 id 的文字区宽度（到第一个槽位框之前）。 */
    private static final int NAME_MAX_W = 116;

    /** 服务端同步回来的分页（权威值；客户端不自行改页，避免与槽位内容错位一帧）。 */
    private int page;
    /** 当前选中的行（0..VISIBLE_ROWS-1；-1 = 未选中）。 */
    private int selectedRow = -1;
    /** 汇总快照（服务端回包覆盖）。 */
    private List<SyncChamberUnitsPacket.Entry> chambers = List.of();
    /** 配方类型 → 机器图标（懒加载缓存，避免每帧反查配方）。 */
    private final Map<String, ItemStack> machineIcons = new HashMap<>();

    private Button pullButton;
    private Button pushButton;
    private Button prevButton;
    private Button nextButton;

    public ChamberUnitsSummaryScreen(final ChamberUnitsSummaryMenu menu, final Inventory inventory,
                                     final Component title) {
        super(menu, inventory, title);
        this.imageWidth = ChamberUnitsSummaryMenu.PANEL_W;
        this.imageHeight = ChamberUnitsSummaryMenu.PANEL_H;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelY = 10000; // 玩家背包不加标签（RS 风格）
    }

    @Override
    protected void init() {
        super.init();
        pullButton = addRenderableWidget(new NoShadowButton(
            leftPos + ChamberUnitsSummaryMenu.BTN_PULL_X, topPos + ChamberUnitsSummaryMenu.BTN_Y,
            ChamberUnitsSummaryMenu.BTN_W, ChamberUnitsSummaryMenu.BTN_H,
            Component.translatable(LANG + "pull"), button -> transferSelected(TransferChamberUnitPacket.ACTION_PULL)));
        pushButton = addRenderableWidget(new NoShadowButton(
            leftPos + ChamberUnitsSummaryMenu.BTN_PUSH_X, topPos + ChamberUnitsSummaryMenu.BTN_Y,
            ChamberUnitsSummaryMenu.BTN_W, ChamberUnitsSummaryMenu.BTN_H,
            Component.translatable(LANG + "push"), button -> transferSelected(TransferChamberUnitPacket.ACTION_PUSH)));
        prevButton = addRenderableWidget(new NoShadowButton(
            leftPos + ChamberUnitsSummaryMenu.PAGE_PREV_X, topPos + ChamberUnitsSummaryMenu.BTN_Y,
            ChamberUnitsSummaryMenu.PAGE_BTN_W, ChamberUnitsSummaryMenu.BTN_H,
            Component.literal("<"), button -> changePage(-1)));
        nextButton = addRenderableWidget(new NoShadowButton(
            leftPos + ChamberUnitsSummaryMenu.PAGE_NEXT_X, topPos + ChamberUnitsSummaryMenu.BTN_Y,
            ChamberUnitsSummaryMenu.PAGE_BTN_W, ChamberUnitsSummaryMenu.BTN_H,
            Component.literal(">"), button -> changePage(1)));
        addRenderableWidget(new NoShadowButton(
            leftPos + ChamberUnitsSummaryMenu.CLOSE_X, topPos + ChamberUnitsSummaryMenu.BTN_Y,
            ChamberUnitsSummaryMenu.CLOSE_W, ChamberUnitsSummaryMenu.BTN_H,
            Component.translatable(LANG + "close"), button -> onClose()));
        // 打开即拉取一次汇总（行元数据：机器名 / 配方类型 / 样板清单）
        PacketDistributor.sendToServer(new RequestChamberUnitsPacket());
    }

    /**
     * 关闭（Esc 或「关闭」按钮）：<b>先请服务端把终端菜单重新打开</b>，再走原版关闭流程。
     * <p>汇总界面是独立容器，关闭后 {@code containerMenu} 已是它自己；服务端收到本包后 {@code openMenu}
     * 终端菜单，随后客户端发来的「关闭旧容器」包因 containerId 已变化会被忽略，玩家于是回到终端界面。</p>
     */
    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new CloseChamberUnitsSummaryPacket());
        super.onClose();
    }

    /** 整批取回 / 存入（走既有 C2S 包；服务端按「网络内真实存在的执行仓」二次校验）。 */
    private void transferSelected(final int action) {
        final SyncChamberUnitsPacket.Entry entry = rowEntry(selectedRow);
        if (entry != null) {
            PacketDistributor.sendToServer(new TransferChamberUnitPacket(entry.pos(), action));
        }
    }

    private void changePage(final int delta) {
        final int target = Math.max(0, Math.min(page + delta, maxPage()));
        if (target != page) {
            PacketDistributor.sendToServer(new SetChamberUnitsPagePacket(target));
        }
    }

    // ==================== 分页与行的可见性 ====================

    private int maxPage() {
        return Math.max(0, (chambers.size() - 1) / ChamberUnitsSummaryMenu.VISIBLE_ROWS);
    }

    /** 本页有效行数（越界行不绘制、不可选）。 */
    private int visibleRows() {
        return Math.min(ChamberUnitsSummaryMenu.VISIBLE_ROWS,
            Math.max(0, chambers.size() - page * ChamberUnitsSummaryMenu.VISIBLE_ROWS));
    }

    private boolean rowActive(final int row) {
        return row >= 0 && row < visibleRows();
    }

    /** 可见行 row 对应的汇总结论（越界返回 null）。 */
    private SyncChamberUnitsPacket.Entry rowEntry(final int row) {
        final int index = page * ChamberUnitsSummaryMenu.VISIBLE_ROWS + row;
        return index >= 0 && index < chambers.size() ? chambers.get(index) : null;
    }

    /** 行矩形（面板内相对坐标），hover 判定与绘制共用。 */
    private static int[] rowRect(final int row) {
        return new int[] {ChamberUnitsSummaryMenu.ROW_BAND_X,
            ChamberUnitsSummaryMenu.ROW_Y0 + row * ChamberUnitsSummaryMenu.ROW_H,
            ChamberUnitsSummaryMenu.ROW_BAND_W, ChamberUnitsSummaryMenu.ROW_BAND_H};
    }

    // ==================== 背景 ====================

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        renderPanel(guiGraphics);
        // ① 行底纹（斑马纹 + 选中底纹 + 左侧强调条）：先画，压在槽位框之下
        for (int row = 0; row < visibleRows(); row++) {
            final int[] rect = rowRect(row);
            if (row == selectedRow) {
                guiGraphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], COLOR_ROW_SELECTED);
                guiGraphics.fill(rect[0] - 2, rect[1], rect[0], rect[1] + rect[3], COLOR_ROW_ACCENT);
            } else if (row % 2 == 1) {
                guiGraphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], COLOR_ROW_ALT);
            }
        }
        // ② 槽位框：与 Menu 槽位坐标严格同源（精灵坐标 = Menu 坐标 - 1）
        for (final Slot slot : menu.slots) {
            if (slot.index < ChamberUnitsSummaryMenu.WINDOW_SLOTS
                && !rowActive(slot.index / ChamberUnitsSummaryMenu.ROW_COLS)) {
                continue; // 本页没有对应执行仓的行：不画空框
            }
            McGui.slotFrame(guiGraphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    /**
     * 面板：默认走原版九宫格（MC 自带风格）；交付的自绘 PNG 尺寸与本窗口一致时自动改用整图 {@code blit}。
     * <p>尺寸不一致（例如贴图还没按本文件的新尺寸交付）时自动退回九宫格，<b>不会出现拉伸 / 错位</b>。</p>
     */
    private void renderPanel(final GuiGraphics guiGraphics) {
        if (CUSTOM_PANEL != null && McGui.textureMatches(CUSTOM_PANEL, imageWidth, imageHeight)) {
            guiGraphics.blit(CUSTOM_PANEL, leftPos, topPos, 0.0F, 0.0F,
                imageWidth, imageHeight, imageWidth, imageHeight);
            return;
        }
        McGui.panel(guiGraphics, leftPos, topPos, imageWidth, imageHeight);
    }

    // ==================== 文字 ====================

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "count", chambers.size()),
            titleLabelX + font.width(title) + 8, titleLabelY, COLOR_DIM, false);

        for (int row = 0; row < visibleRows(); row++) {
            renderRowLabels(guiGraphics, row);
        }
        // 提示行：空网络 / 未选中 / 已选中的是哪台
        final SyncChamberUnitsPacket.Entry selected = rowEntry(selectedRow);
        if (chambers.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable(LANG + "empty"),
                ChamberUnitsSummaryMenu.ROW_BAND_X + 2, ChamberUnitsSummaryMenu.HINT_Y, COLOR_TEXT, false);
        } else if (selected == null) {
            guiGraphics.drawString(font, Component.translatable(LANG + "select.none"),
                ChamberUnitsSummaryMenu.ROW_BAND_X + 2, ChamberUnitsSummaryMenu.HINT_Y, COLOR_DIM, false);
        } else {
            guiGraphics.drawString(font, Component.translatable(LANG + "selected", selected.name()),
                ChamberUnitsSummaryMenu.ROW_BAND_X + 2, ChamberUnitsSummaryMenu.HINT_Y, COLOR_TEXT, false);
        }
        if (maxPage() > 0) {
            guiGraphics.drawString(font, Component.translatable(LANG + "poll"),
                ChamberUnitsSummaryMenu.ROW_BAND_X + 2 + 118, ChamberUnitsSummaryMenu.HINT_Y, COLOR_DIM, false);
        }
        guiGraphics.drawString(font, Component.translatable(LANG + "page", page + 1, maxPage() + 1),
            ChamberUnitsSummaryMenu.PAGE_TEXT_X, ChamberUnitsSummaryMenu.PAGE_TEXT_Y, COLOR_DIM, false);
    }

    /** 一行：机器图标 + 执行仓名字 + 配方 id（用户明确要求的三样）。 */
    private void renderRowLabels(final GuiGraphics guiGraphics, final int row) {
        final SyncChamberUnitsPacket.Entry entry = rowEntry(row);
        if (entry == null) {
            return;
        }
        final int rowY = ChamberUnitsSummaryMenu.ROW_Y0 + row * ChamberUnitsSummaryMenu.ROW_H;
        guiGraphics.renderItem(machineIcon(entry),
            ChamberUnitsSummaryMenu.ROW_ICON_X, rowY + ChamberUnitsSummaryMenu.ROW_ICON_DY);
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(entry.name(), NAME_MAX_W)),
            ChamberUnitsSummaryMenu.ROW_TEXT_X, rowY + ChamberUnitsSummaryMenu.ROW_NAME_DY, COLOR_TEXT, false);
        drawSmallText(guiGraphics, RecipeTypeNames.display(entry.recipeType()),
            ChamberUnitsSummaryMenu.ROW_TEXT_X, rowY + ChamberUnitsSummaryMenu.ROW_ID_DY, COLOR_DIM);
        if (entry.units().isEmpty()) {
            drawSmallText(guiGraphics, Component.translatable(LANG + "no_unit"),
                ChamberUnitsSummaryMenu.ROW_SLOT_X, rowY + ChamberUnitsSummaryMenu.ROW_SLOT_DY + 5, COLOR_DIM);
        }
    }

    /** 该行的机器图标：按配方类型反查（缓存）；查不到时退回第一个单元样板图标。 */
    private ItemStack machineIcon(final SyncChamberUnitsPacket.Entry entry) {
        final String type = entry.recipeType();
        if (type != null && !type.isEmpty()) {
            final ItemStack icon = machineIcons.computeIfAbsent(type, key -> {
                final List<RecipeTypeMachines.Machine> machines = RecipeTypeMachines.forRecipeType(key);
                return machines.isEmpty() ? ItemStack.EMPTY : machines.get(0).icon();
            });
            if (!icon.isEmpty()) {
                return icon;
            }
        }
        return entry.units().isEmpty() ? ItemStack.EMPTY : entry.units().get(0);
    }

    /** 0.5 缩放小字（配方 id / 空态），全部无阴影。 */
    private void drawSmallText(final GuiGraphics guiGraphics, final Component text,
                               final float x, final float y, final int color) {
        final var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.scale(0.5F, 0.5F, 1.0F);
        guiGraphics.drawString(font, text, 0, 0, color, false);
        pose.popPose();
    }

    private String trimToWidth(final String text, final int maxWidth) {
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

    // ==================== 渲染主流程与 tooltip ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        refreshButtons();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 原版不会自动渲染槽位内物品的 tooltip，必须手动调用
        renderTooltip(guiGraphics, mouseX, mouseY);
        renderCustomTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 每帧刷新按钮可用态（分页 / 选中行由服务端权威值决定）。 */
    private void refreshButtons() {
        final boolean hasSelection = rowEntry(selectedRow) != null;
        if (pullButton != null) {
            pullButton.active = hasSelection;
        }
        if (pushButton != null) {
            pushButton.active = hasSelection;
        }
        if (prevButton != null) {
            prevButton.active = page > 0;
        }
        if (nextButton != null) {
            nextButton.active = page < maxPage();
        }
    }

    /**
     * 自绘元素 tooltip（行 / 底部按钮）。
     * <p>悬停在「有物品的窗口格」上时直接让位给物品 tooltip（二者互斥、不会叠画）。</p>
     */
    private void renderCustomTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            return;
        }
        // 底部按钮：直接用控件自身的命中判定（与 MC 绘制范围同源）
        if (pullButton != null && pullButton.isMouseOver(mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "pull.tip")), mouseX, mouseY);
            return;
        }
        if (pushButton != null && pushButton.isMouseOver(mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "push.tip")), mouseX, mouseY);
            return;
        }
        if (prevButton != null && prevButton.isMouseOver(mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "page.prev.tip")), mouseX, mouseY);
            return;
        }
        if (nextButton != null && nextButton.isMouseOver(mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "page.next.tip")), mouseX, mouseY);
            return;
        }
        // 行区（含槽位之间的空隙）：机器名 / 配方 id / 样板清单
        for (int row = 0; row < visibleRows(); row++) {
            final SyncChamberUnitsPacket.Entry entry = rowEntry(row);
            if (entry == null) {
                continue;
            }
            final int[] rect = rowRect(row);
            if (!isOver(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                continue;
            }
            final List<Component> always = new ArrayList<>();
            final List<cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.Line> layered =
                new ArrayList<>();
            always.add(Component.literal(entry.name()));
            // 内部配方类型 id 与「每张单元样板的机器」属于调试/来源信息 → Alt 层；
            // 张数属于数值 → Ctrl 层；操作说明 → Shift 层（唯一实现 RsccTooltipLayers，默认收起）。
            layered.add(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                Component.translatable(LANG + "slot.tip")));
            layered.add(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                Component.translatable(LANG + "row.tip")));
            layered.add(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.ctrl(
                Component.translatable(LANG + "units", entry.units().size())));
            layered.add(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.alt(
                RecipeTypeNames.idLine(entry.recipeType())));
            for (final ItemStack unit : entry.units()) {
                final SequencePatternData.UnitData data = SequencePatternData.readUnit(unit, RegistryAccess.EMPTY);
                layered.add(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.alt(
                    Component.literal("· " + unit.getHoverName().getString()
                        + (data == null ? "" : "  " + data.machine()))));
            }
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                guiGraphics, font, always, layered, mouseX, mouseY);
            return;
        }
    }

    /** 面板内相对坐标的严格命中判定（左闭右开；不加原版 {@code isHovering} 的 ±1 padding）。 */
    private boolean isOver(final int x, final int y, final int w, final int h,
                           final double mouseX, final double mouseY) {
        final double sx = leftPos + x;
        final double sy = topPos + y;
        return mouseX >= sx && mouseX < sx + w && mouseY >= sy && mouseY < sy + h;
    }

    /** 手动渲染多行 tooltip（本模组的 GUI 不会自动渲染 tooltip）。 */
    private void renderLines(final GuiGraphics guiGraphics, final List<Component> lines,
                             final int mouseX, final int mouseY) {
        final List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>(lines.size());
        for (final Component line : lines) {
            wrapped.add(line.getVisualOrderText());
        }
        guiGraphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // 先让原版处理槽位点击 / Shift 快速移动 / 拖拽（真槽位交互全部由原版负责）
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 行区空白处左键 = 选中该行（底部「取回 / 存入」针对选中行生效）
        if (button == 0) {
            for (int row = 0; row < visibleRows(); row++) {
                final int[] rect = rowRect(row);
                if (isOver(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                    selectedRow = selectedRow == row ? -1 : row;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (maxPage() > 0 && isOver(ChamberUnitsSummaryMenu.ROW_BAND_X, ChamberUnitsSummaryMenu.ROW_Y0 - 2,
            ChamberUnitsSummaryMenu.ROW_BAND_W,
            ChamberUnitsSummaryMenu.VISIBLE_ROWS * ChamberUnitsSummaryMenu.ROW_H + 4, mouseX, mouseY)) {
            changePage(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 服务端回包到达后覆盖快照（由 {@link SyncChamberUnitsPacket} 的客户端处理器转发）。 */
    public void onServerSync(final List<SyncChamberUnitsPacket.Entry> received, final int serverPage) {
        this.chambers = received == null ? List.of() : received;
        this.page = Math.max(0, Math.min(serverPage, maxPage()));
        if (selectedRow >= 0 && !rowActive(selectedRow)) {
            selectedRow = -1;
        }
    }

    /** 无阴影文字的原版按钮（DEFAULT_NARRATION 是 protected static，只能在 Button 子类里引用）。 */
    private static final class NoShadowButton extends Button {
        private NoShadowButton(final int x, final int y, final int width, final int height,
                               final Component message, final OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        public void renderString(final GuiGraphics guiGraphics, final Font font, final int color) {
            guiGraphics.drawString(font, getMessage(),
                getX() + (getWidth() - font.width(getMessage())) / 2,
                getY() + (getHeight() - 8) / 2, color, false);
        }
    }
}
