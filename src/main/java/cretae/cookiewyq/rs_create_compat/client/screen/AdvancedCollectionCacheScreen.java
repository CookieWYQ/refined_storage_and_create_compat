package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.AreaOutline;
import cretae.cookiewyq.rs_create_compat.client.AreaStripBounds;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 高级归流缓存仓界面：<b>整个基础版界面</b>（匹配区 / 缓存区 / 升级 / 输入面 / 范围 / 经验形态 /
 * 删除模式……）原样继承，再追加高级版专属的东西。
 *
 * <h2>第 125 轮：区域功能从「另一个全屏界面」变成「GUI 右边的一条侧栏」</h2>
 * <p>用户原话：「那个标记……不要再用现在这个界面。这个界面首先丑，其次没有 JEI，然后不符合我们现在
 * 模组的设计风格。就是把这一个工具放进这个方块里面之后，可以直接在旁边出现一条这个什么选择的，
 * 然后直接在里面选择那个，然后直接进行编辑……你要考虑显示区域的东西应该放在哪里 ——
 * JEI 是占据屏幕左右两侧的，要是乱放的话，会跟 JEI 重叠。」</p>
 * <p>因此本界面不再 {@code setScreen(new AreaSelectorScreen(...))}。区域的一切（选中 / 改名 /
 * 改坐标 / 看过滤图标 / 配标记）都在 {@link AreaStripPanel} 里做 —— 它贴在 GUI 右侧、始终可见，
 * 点行即选中，<b>当前正在干活的区域</b>用「橙竖条 + 行底 + ▶ + 标题 + 世界常驻框」同时标出。
 * 旧的两颗「单显 / 全显」按钮（{@code (186,219)} / {@code (218,219)}）删除，作用并入侧栏
 * （点行 = 单显，底部「显示全部」= 全显）—— 用户嫌「按钮重复 / 箭头不直观」，这里不再重复一份。</p>
 * <p>JEI 避让：侧栏每帧把自己的屏幕矩形写进 {@link AreaStripBounds}，
 * {@code CollectionCacheJeiPlugin} 通过 {@code IGuiContainerHandler#getGuiExtraAreas} 报给 JEI，
 * 由 JEI 自己把物品列表挪开（契约 §3.3）。</p>
 *
 * <h2>第 106 轮按用户原话做的三件事（保留）</h2>
 * <ol>
 *     <li><b>清掉无用红字</b>（用户第 3 条）：「没匹配到 / 匹配到了但数量没达到」那一族状态句
 *     不再上屏（判据集中在基类 {@code CollectionCacheScreen#isStatusVisible}），
 *     只保留「网络未连接 / 未激活 / 区域不可用 / 缓存满」这类<b>真会让机器停摆</b>的原因；</li>
 *     <li><b>过滤器图标直显</b>（用户第 1 / 2 条）：状态带右端直接画「当前编辑区域吸什么」的物品图标
 *     （数据来自匹配窗口那份 S2C 快照，与 ghost 窗口同源），不再只有一句「只吸 4 种」；</li>
 *     <li><b>去掉叠在升级槽上的两颗按钮</b>：第 101 轮加的「◀ ▶」按钮在 {@code (78,219)} / {@code (92,219)}，
 *     而升级槽从 {@code x=76} 起 6 格 —— 那两颗按钮其实<b>压在升级槽 0 / 1 上</b>（玩家的升级物品显示不全）。
 *     区域切换改走侧栏（点行 = 选中 + 编辑该区域过滤），那两颗按钮删除。</li>
 * </ol>
 *
 * <h2>区域列表 / 过滤器为什么都要经过服务端</h2>
 * <p>界面只读 {@link AdvancedAreaSnapshotPacket#lastFor(int)} 这一份<b>服务端算好的快照</b>
 * （区域列表、每区域过滤图标、当前编辑上下文、拒绝原因）。客户端<b>不判断</b>区域是否可用、
 * <b>不读</b>方块实体、<b>不猜</b>服务端行为 —— 列表里出现的区域 = 服务端真的在用的区域。</p>
 */
public class AdvancedCollectionCacheScreen extends CollectionCacheScreen {
    private static final String LANG = "gui.rs_create_compat.advanced_collection_cache.";

    // ---------- 按钮几何（Menu 坐标；全部避让升级槽 x=76..184 与玩家背包 y=242 起） ----------
    /** 「区域」按钮（侧栏开关；区域选定器槽右侧）。 */
    private static final int AREA_BTN_X = 28;
    private static final int AREA_BTN_Y = 219;
    private static final int AREA_BTN_W = 22;
    private static final int AREA_BTN_H = 16;

    // ---------- 状态带右端的过滤图标 ----------
    /** 一屏最多画几个过滤图标（再多就画「+N」）。 */
    private static final int FILTER_ICON_MAX = 5;
    /** 图标绘制尺寸（像素）与格子间距：8px 图标 + 9px 间距 = 5 个共 45px。 */
    private static final int FILTER_ICON_SIZE = 8;
    private static final int FILTER_ICON_PITCH = 9;
    /** 图标区与状态文字之间的空隙。 */
    private static final int FILTER_ICON_GAP = 4;

    private static final int COLOR_SCOPE = 0xFFFF9F1C;
    private static final int COLOR_TEXT = 0xFF333333;

    @Nullable
    private Button areaButton;
    /** 区域条（侧栏）。{@code init()} 重建；{@code render / mouse* / keyPressed / removed} 六处转发。 */
    @Nullable
    private AreaStripPanel strip;

    /** 本帧图标区实际画了几个、从哪个 x 开始（命中测试与绘制同源）。 */
    private int iconsDrawn;
    private int iconsStartX;
    /** 本帧要画的过滤图标（每帧重算；tooltip 与命中测试共用）。 */
    private final List<MarkerEntry> frameIcons = new ArrayList<>();

    public AdvancedCollectionCacheScreen(final CollectionCacheMenu menu,
                                         final Inventory inventory,
                                         final Component title) {
        // 构造参数取基类菜单类型：RegisterMenuScreensEvent 的泛型把 M 推断成 CollectionCacheMenu
        //（本类继承的是 AbstractContainerScreen<CollectionCacheMenu>），运行时拿到的始终是
        // AdvancedCollectionCacheMenu，业务方法里做一次受控强转。
        super(menu, inventory, title);
    }

    private AdvancedCollectionCacheMenu advancedMenu() {
        return (AdvancedCollectionCacheMenu) menu;
    }

    /**
     * 区域条用的菜单。
     * <p>面板与自检都通过这一个入口拿 {@link AdvancedCollectionCacheMenu}（运行时类型恒定；
     * 构造参数受泛型擦除所限只能声明成基类类型）—— 两处各写一次强转迟早会分叉。</p>
     */
    AdvancedCollectionCacheMenu stripMenu() {
        return advancedMenu();
    }

    /** 区域条（JEI 插件 / 自检读它；{@code init()} 之前为 {@code null}）。 */
    @Nullable
    public AreaStripPanel strip() {
        return strip;
    }

    @Override
    protected void init() {
        super.init();
        areaButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "area_button"),
            button -> toggleStrip())
            .bounds(leftPos + AREA_BTN_X, topPos + AREA_BTN_Y, AREA_BTN_W, AREA_BTN_H)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable(LANG + "area_button.tip")))
            .build());
        // 侧栏每帧按 leftPos/topPos 重新摆位（不是原版子控件）：init 里只造一次实例
        strip = new AreaStripPanel(this);
    }

    /**
     * 高级版有自己那颗「区域」按钮占着 {@code (28,219)}，因此不让基类再在 {@code (8,219)} 造一颗
     * 「查看范围」—— 它和「区域」按钮、区域选定器槽会挤在一起（第 106 轮口径），第 125 轮起
     * 「显示全部 / 单显」全部并入侧栏，GUI 上不再需要任何范围显示按钮。
     */
    @Override
    protected boolean usesOwnRangeButton() {
        return true;
    }

    // ==================== 区域条（第 125 轮） ====================

    /**
     * 侧栏是否展开（跨界面记忆）。
     * <p>唯一一份状态在这里；{@link AreaStripPanel#isOpen()}/{@link AreaStripPanel#setOpen(boolean)}
     * 只是转发，避免「按钮改了一份、面板读另一份」。</p>
     * <p>默认 {@code true}：用户原话是「把这一个工具放进这个方块里面之后，可以直接在旁边出现一条」——
     * 插了选定器就该看见它，不该再点一次按钮才出现。</p>
     */
    private static boolean stripOpen = true;

    /** 侧栏当前是否展开（{@code AreaStripPanel#isOpen()} 转发到此）。 */
    static boolean isStripOpen() {
        return stripOpen;
    }

    /** 设置侧栏展开状态（{@code AreaStripPanel#setOpen(boolean)} 转发到此）。 */
    static void setStripOpen(final boolean open) {
        stripOpen = open;
    }

    /**
     * 「区域」按钮 = 侧栏开关（契约 §5）。
     * <p>侧栏隐藏时展开、可见时收起；状态存在 {@link AreaStripPanel} 的 {@code static} 里，
     * 关掉界面再打开还是同一个状态（玩家不用每次重新展开）。</p>
     * <p>槽里没有选定器时<b>不展开</b>，只给一句明确的话 —— 空侧栏（0 行、什么都不能做）
     * 对玩家没有任何价值。</p>
     */
    void toggleStrip() {
        if (!hasSelectorInSlot()) {
            hint(LANG + "area_button.no_slot");
            return;
        }
        AreaStripPanel.setOpen(!AreaStripPanel.isOpen());
    }

    /** 专用槽里是不是真的有区域选定器（菜单路径只认槽，绝不回退主手 —— 第 119 轮 C-2 同口径）。 */
    private boolean hasSelectorInSlot() {
        return advancedMenu().getAreaSelectorStack().is(RS_Create_Compat.AREA_SELECTOR.get());
    }

    /** 侧栏里的一句行动栏提示（面板不自己 {@code displayClientMessage}，统一从这里出）。 */
    void stripHint(final String langKey) {
        hint(langKey);
    }

    /**
     * 侧栏底部「显示全部」：把快照里的<b>全部可用</b>区域一次性常亮 5 秒（= 旧「全显」）。
     * <p>生命周期（5 秒窗口 / 每 tick 重喂 / 关界面按剩余时长重新武装 / 每盒子独立 slot）
     * 全部复用基类 {@link CollectionCacheScreen#showAreas}，高级版不自己维护第二份 TTL。</p>
     */
    void stripShowAllAreas() {
        final AdvancedAreaSnapshotPacket snapshot = snapshot();
        if (snapshot == null) {
            hint(LANG + "show.waiting");
            return;
        }
        if (snapshot.status() == 0) {
            hint(LANG + "info.none");
            return;
        }
        if (snapshot.status() != 1) {
            // 区域为空 / 维度不符：本机一点不吸 ⇒ 一个框都不画（画框会谎报「正在吸」）
            hint(snapshot.status() == 3 ? LANG + "info.dimension" : LANG + "info.empty");
            return;
        }
        final List<Area> areas = new ArrayList<>(snapshot.usableCount());
        for (final AdvancedAreaSnapshotPacket.AreaEntry entry : snapshot.entries()) {
            if (entry.area() != null) {
                areas.add(entry.area());
            }
        }
        if (areas.isEmpty()) {
            hint(LANG + "info.empty");
            return;
        }
        if (AreaOutline.isDisabled()) {
            // 真话：描边渲染出口不可用（Ponder / Outliner 被禁用或替换）⇒ 画不出来，别再让玩家猜
            hint(LANG + "show.failed");
            return;
        }
        showAreas(areas);
    }

    // ==================== 当前快照 ====================

    /** 本菜单对应的最新区域快照（{@code null} = 还没收到；界面此时不显示任何区域相关结论）。 */
    @Nullable
    private AdvancedAreaSnapshotPacket snapshot() {
        return AdvancedAreaSnapshotPacket.lastFor(menu.containerId);
    }

    /** 一条行动栏提示（每次点击 / S2C 到达时最多一条，不每 tick 刷）。 */
    private void hint(final String langKey) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(langKey), true);
        }
    }

    // ==================== 渲染 ====================

    /**
     * 每帧：先让侧栏按本帧 GUI 位置摆好（并写 JEI 矩形），再走基类整套渲染，
     * 最后把侧栏画在 GUI <b>右边</b>（不与 GUI 重叠，也就不需要任何层级技巧）。
     */
    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        if (strip == null) {
            strip = new AreaStripPanel(this); // init 之前被渲染一次的极端情况（防御）
        }
        strip.layout(leftPos, topPos, width, height);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        strip.render(guiGraphics, mouseX, mouseY, partialTick);
        strip.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 槽框 + 橙色描边画在物品<b>之前</b>（renderBg），避免盖住物品图标。 */
    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY);
        final int frameX = leftPos + AdvancedCollectionCacheMenu.AREA_SLOT_X - 1;
        final int frameY = topPos + AdvancedCollectionCacheMenu.AREA_SLOT_Y - 1;
        McGui.slotFrame(guiGraphics, frameX, frameY);
        // 统一橙的 1px 外描边：把「这个槽是区域功能」与其它槽区分开
        guiGraphics.fill(frameX - 1, frameY - 1, frameX + 19, frameY, AreaOutline.COLOR);
        guiGraphics.fill(frameX - 1, frameY + 18, frameX + 19, frameY + 19, AreaOutline.COLOR);
        guiGraphics.fill(frameX - 1, frameY - 1, frameX, frameY + 19, AreaOutline.COLOR);
        guiGraphics.fill(frameX + 18, frameY - 1, frameX + 19, frameY + 19, AreaOutline.COLOR);
    }

    /**
     * 状态带（与基础版同一条 Menu y210 留白）。
     * <p><b>优先级</b>：区域不可用（没装选定器 / 区域为空 / 维度不符）⇒ 先说「为什么一点都不吸」
     * （这句由服务端状态码决定，与区域槽 tooltip 同源）；否则这条带就是「当前编辑区域的过滤摘要」，
     * 右端直接画过滤图标 —— 用户第 1 条要的「直观方式直接显示」。</p>
     * <p>第 104 轮口径照旧：<b>绝不做像素截断</b>，放不下就缩字号（≥0.6），完整原文在 hover tooltip。</p>
     */
    @Override
    protected void drawStatusLine(final GuiGraphics guiGraphics) {
        final Component stall = absorbStatusText();
        if (stall != null) {
            super.drawStatusLine(guiGraphics); // 停摆原因独占整条带（不画图标，信息优先）
            iconsDrawn = 0;
            frameIcons.clear();
            return;
        }
        frameIcons.clear();
        collectFrameIcons();
        final int iconsWidth = frameIcons.isEmpty()
            ? 0 : frameIcons.size() * FILTER_ICON_PITCH + FILTER_ICON_GAP;
        final int textWidth = STATUS_LINE_W - iconsWidth;
        final Component info = editAreaSummary();
        drawStatusBand(guiGraphics, info, COLOR_SCOPE, textWidth);
        if (!frameIcons.isEmpty()) {
            iconsStartX = STATUS_LINE_X + STATUS_LINE_W - frameIcons.size() * FILTER_ICON_PITCH;
            iconsDrawn = drawFilterIcons(guiGraphics, iconsStartX, STATUS_LINE_Y, frameIcons, FILTER_ICON_MAX);
        } else {
            iconsStartX = 0;
            iconsDrawn = 0;
        }
    }

    /** 本帧要画的过滤图标（= 当前编辑区域的第一页过滤条目；与 ghost 窗口同源）。 */
    private void collectFrameIcons() {
        final int editing = advancedMenu().getEditingAreaIndex();
        if (editing < 0) {
            return;
        }
        for (int i = 0; i < visibleMarkerCount() && frameIcons.size() < FILTER_ICON_MAX; i++) {
            final MarkerEntry entry = visibleMarkerEntry(i);
            if (entry == null || entry.isEmpty() || entry.fluid()) {
                continue;
            }
            frameIcons.add(entry);
        }
    }

    /**
     * 画一组过滤图标（第 106 轮，用户第 1 / 2 条；第 107 条口径：<b>图标 + 名字</b>，
     * 完整注册 id 只进 tooltip）。
     * <p>单实现两处用：高级主界面的状态带（8px）与区域条每行右侧 / 标记网格（8px）。
     * 不复制一份到另一个类，避免两边图标大小 / 缩放口径漂移。</p>
     *
     * @return 实际画出的图标数
     */
    public static int drawFilterIcons(final GuiGraphics guiGraphics, final int startX, final int y,
                                      final List<MarkerEntry> filters, final int max) {
        int drawn = 0;
        for (final MarkerEntry entry : filters) {
            if (drawn >= max) {
                break;
            }
            if (entry == null || entry.isEmpty() || entry.fluid()) {
                continue;
            }
            final ItemStack stack = entry.displayStack();
            if (stack.isEmpty()) {
                continue;
            }
            drawScaledItem(guiGraphics, stack, startX + drawn * FILTER_ICON_PITCH, y,
                FILTER_ICON_SIZE);
            drawn++;
        }
        return drawn;
    }

    /** 把物品图标缩到指定像素绘制（GUI 里的物品图标原始 16×16）。 */
    private static void drawScaledItem(final GuiGraphics guiGraphics, final ItemStack stack,
                                       final int x, final int y, final int size) {
        final float scale = size / 16.0F;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.renderItem(stack, 0, 0);
        guiGraphics.pose().popPose();
    }

    /** 过滤图标的 tooltip：<b>本地化物品名 + 完整注册 id</b>（第 107 条：id 只进 tooltip）。 */
    private void renderFilterIconTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                         final int mouseY) {
        if (iconsDrawn <= 0 || frameIcons.isEmpty()) {
            return;
        }
        final int baseY = topPos + STATUS_LINE_Y - 2;
        final int baseX = leftPos + iconsStartX;
        for (int i = 0; i < iconsDrawn && i < frameIcons.size(); i++) {
            final int x = baseX + i * FILTER_ICON_PITCH;
            if (mouseX < x || mouseX >= x + FILTER_ICON_PITCH
                || mouseY < baseY || mouseY > baseY + FILTER_ICON_SIZE + 6) {
                continue;
            }
            final List<Component> lines = filterIconTooltipLines(frameIcons.get(i));
            guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
    }

    /**
     * 一条过滤图标的 tooltip 正文（<b>与区域条共用</b>，保证两处说同一件事）：
     * 第一行本地化物品名，第二行完整注册 id（用户第 107 条），第三行需求数量。
     */
    public static List<Component> filterIconTooltipLines(final MarkerEntry entry) {
        final List<Component> lines = new ArrayList<>(3);
        if (entry == null || entry.isEmpty()) {
            return lines;
        }
        final ItemStack stack = entry.displayStack();
        // 第 107 条：行内 / 摘要只显示物品名；id 只在 tooltip 这一行
        lines.add(stack.isEmpty() ? Component.literal("?") : stack.getHoverName());
        if (entry.id() != null) {
            lines.add(Component.translatable("gui.rs_create_compat.common.item_id",
                entry.id().toString()));
        }
        lines.add(Component.translatable("gui.rs_create_compat.common.amount", entry.amount()));
        return lines;
    }

    /**
     * 状态带的悬停 tooltip：完整（1.0 倍）的句子 + 「当前编辑区域吸什么」的文字版（物品名列表）。
     * <p>图标只有 8px，认不出的物品靠这里看名字；注册 id 也在每条的名字下一行（第 107 条口径）。</p>
     */
    @Override
    protected void renderStatusBandTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                           final int mouseY) {
        renderFilterIconTooltip(guiGraphics, mouseX, mouseY);
        if (!isOverStatusBand(mouseX, mouseY)) {
            return;
        }
        final List<Component> lines = new ArrayList<>(4);
        final Component areaStatus = absorbStatusText();
        if (areaStatus != null) {
            lines.add(areaStatus);
        }
        lines.add(statusLine(advancedMenu().getAreaStatus()));
        lines.add(editAreaSummary());
        if (advancedMenu().getEditingAreaIndex() < 0) {
            lines.add(Component.translatable(LANG + "filter.howto"));
        } else if (frameIcons.isEmpty()) {
            lines.add(Component.translatable(LANG + "filter.empty.tip"));
        } else {
            for (final MarkerEntry entry : frameIcons) {
                lines.addAll(filterIconTooltipLines(entry));
            }
        }
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 点击状态带右端的过滤图标 = 展开区域条（在那里可以「配标记」）。
     * 单独给这条命中，是因为图标区只有 45px，落在 {@link #isOverStatusBand} 里但语义不同。
     */
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // ① 侧栏先吃（它在 GUI 右边，与 GUI 的槽位 / 按钮零重叠，顺序只是「谁在谁上面」）
        if (strip != null && strip.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && iconsDrawn > 0) {
            final int baseY = topPos + STATUS_LINE_Y - 2;
            if (mouseY >= baseY && mouseY <= baseY + FILTER_ICON_SIZE + 6
                && mouseX >= leftPos + iconsStartX
                && mouseX < leftPos + iconsStartX + iconsDrawn * FILTER_ICON_PITCH) {
                toggleStrip();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (strip != null && strip.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        if (strip != null && strip.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        // 面板的契约签名是 3 参（鼠标 x/y + 滚轮方向）：Screen 这里把纵向滚动量传过去
        if (strip != null && strip.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 键盘：侧栏正在编辑（有 EditBox 聚焦）时<b>必须先给它</b>，否则玩家打字会触发界面的快捷键
     * （按 E 关界面、按 Esc 直接退出，正在改的名字就丢了）。
     */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (strip != null && strip.isEditing() && strip.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 字符输入：原版只把 {@code charTyped} 路由给 {@code children}，而侧栏的 EditBox 是自绘的，
     * 所以这里必须显式转发（契约的六处转发之外多这一条，是「就地改名」能真的打得出字的前提）。
     */
    @Override
    public boolean charTyped(final char codePoint, final int modifiers) {
        if (strip != null && strip.isEditing() && strip.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /**
     * 关界面（契约 §4「世界框生命周期」）：先让侧栏提交未决的改名 / 坐标、清掉常驻世界框与 JEI 矩形，
     * 再走基类（基类负责「查看范围 / 显示全部」按剩余 TTL 重新武装，让框在关掉 GUI 后继续画完）。
     */
    @Override
    public void removed() {
        if (strip != null) {
            // 第 130 轮：标记网格整页删除，这里不再有 closeMarkGrid()。
            // removed() 内部会把「当前在干活」的世界框按 5 秒重新武装（用户第 4 条「显示边框没用」的正解）。
            strip.removed();
        }
        strip = null;
        AreaStripBounds.clear();
        super.removed();
    }

    // ==================== 匹配区 = 区域过滤的唯一编辑入口（第 130 轮） ====================

    /**
     * 匹配区标题带：有正在编辑的区域时直接写「区域 3 · 铁场」。
     * <p>用户原话：「让这一个编辑的时候在匹配区中编辑」—— 那就必须让人一眼看出<b>这块 48 格现在是谁的</b>。
     * 第 106 轮用的是状态带上那句「区域 3/7 · 只吸 4 种」（在 GUI 内 y210 那条带），离匹配区隔了一整块缓存区；
     * 标题带就在匹配区头顶，挪到这里才是「上下文与其编辑对象挨着」。</p>
     */
    @Override
    protected Component matchRegionLabel() {
        final int editing = advancedMenu().getEditingAreaIndex();
        if (editing < 0) {
            return Component.translatable(LANG + "match_label.none");
        }
        return Component.translatable(LANG + "match_label", editing + 1, editingAreaName(editing));
    }

    /** 正在编辑的区域名（无名区域退回「区域 N」；快照还没到时也退回序号）。 */
    private String editingAreaName(final int editingIndex) {
        final AdvancedAreaSnapshotPacket snapshot = AdvancedAreaSnapshotPacket.lastFor(menu.containerId);
        if (snapshot != null && editingIndex >= 0 && editingIndex < snapshot.entries().size()) {
            final AdvancedAreaSnapshotPacket.AreaEntry entry = snapshot.entries().get(editingIndex);
            if (entry != null && entry.area() != null) {
                return entry.area().displayName(Component.translatable(
                    "gui.rs_create_compat.area_selector.region", editingIndex + 1).getString());
            }
        }
        return Component.translatable("gui.rs_create_compat.area_selector.region",
            editingIndex + 1).getString();
    }

    /**
     * 没选区域时匹配区里的那句提示（否则那 48 格显示的是<b>全局匹配区</b>，
     * 玩家会以为自己在配「这个区域」的过滤）。
     * <p>几何用字面量 7/32..251/105：那两对常量在基类是 {@code private}
     * （{@code MATCH_PANEL_X1/Y1/X2/Y2}），这里是同一块面板的镜像；自检脚本钉住两边相等。</p>
     */
    @Override
    protected void drawMatchAreaHint(final GuiGraphics guiGraphics) {
        if (advancedMenu().getEditingAreaIndex() >= 0) {
            return;
        }
        final int panelX = leftPos + 7;
        final int panelY = topPos + 32;
        final int panelW = 244;
        final int panelH = 73;
        // 半透明底：文字压在第 1 行 ghost 格子（16×16 图标）上仍然读得清
        guiGraphics.fill(panelX, panelY + panelH / 2 - 10, panelX + panelW, panelY + panelH / 2 + 10,
            0xB0000000);
        final Component hint = Component.translatable(
            "gui.rs_create_compat.collection_cache.match_area.pick_hint");
        final int textW = Math.min(panelW - 8, font.width(hint));
        RsccTextFit.drawFitLine(guiGraphics, font, hint,
            panelX + (panelW - textW) / 2, panelY + panelH / 2 - 4, textW, 0xFFFFE08A);
    }

    /**
     * 「正在编辑哪个区域的过滤器」摘要（第 101 轮加，第 106 轮保留语义、换了入口）：
     * <ul>
     *     <li>未选区域 ⇒ 灰字提示（用「区域」→ 侧栏里点一行）；</li>
     *     <li>已选区域且<b>没有过滤条目</b> ⇒ 橙字「区域 N：不吸任何东西」——
     *     让玩家一眼知道这个区域现在是<b>故意什么都不吸</b>（安全默认，解决「两台机器互相吸放」）；</li>
     *     <li>已选区域有过滤 ⇒ 橙字「区域 N/M · 只吸 K 种」（右侧图标就是这 K 种）。</li>
     * </ul>
     */
    private Component editAreaSummary() {
        final AdvancedCollectionCacheMenu advanced = advancedMenu();
        final int count = advanced.getAreaCount();
        final int current = advanced.getEditingAreaIndex();
        if (current < 0) {
            return Component.translatable(LANG + "filter.none", count);
        }
        final int filters = advanced.getAreaFilterCount(current);
        return filters <= 0
            ? Component.translatable(LANG + "filter.row.empty", current + 1, count)
            : Component.translatable(LANG + "filter.row", current + 1, count, filters);
    }

    /**
     * 区域槽的 tooltip：空槽给出「放什么、为什么」，有物品时把选定器的<b>状态</b>追加在物品 tooltip 之后。
     *
     * <h2>为什么用「菜单槽位下标」而不是 {@code hoveredSlot.index}</h2>
     * <p>{@code Slot#index} 是<b>容器内</b>下标：本槽是 {@code SlotItemHandler(areaSlot, 0, …)}，
     * 所以它的 {@code index} 恒为 <b>0</b>（不是 138）。旧写法 {@code hoveredSlot.index == AREA_SLOT_INDEX}
     * 永远为假 ⇒ 这段 tooltip 是死代码，而且 base 界面会把 index=0 当成匹配区第 0 格，
     * 鼠标指着区域槽反而弹出「标记 0」的 tooltip。这里改用 {@code menu.slots.indexOf(hoveredSlot)}
     * 取<b>菜单内</b>下标，才和 {@link AdvancedCollectionCacheMenu#AREA_SLOT_INDEX} 同一口径。</p>
     */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (hoveredSlot != null && menu.getCarried().isEmpty()
            && menu.slots.indexOf(hoveredSlot) == AdvancedCollectionCacheMenu.AREA_SLOT_INDEX) {
            final ItemStack stack = hoveredSlot.getItem();
            final List<Component> lines = new ArrayList<>();
            if (stack.isEmpty()) {
                lines.add(Component.translatable(LANG + "area_slot.empty"));
                lines.add(Component.translatable(LANG + "area_slot.empty.tip"));
            } else {
                if (minecraft != null && minecraft.player != null) {
                    lines.addAll(stack.getTooltipLines(
                        net.minecraft.world.item.Item.TooltipContext.of(minecraft.level),
                        minecraft.player,
                        minecraft.options.advancedItemTooltips
                            ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL));
                }
                // 状态行：与界面状态行同一个 switch、同一组 info.* 键 ⇒ 两处永远说同一句话。
                lines.add(statusLine(advancedMenu().getAreaStatus()));
            }
            guiGraphics.renderTooltip(font, lines, stack.getTooltipImage(), mouseX, mouseY);
            return;
        }
        super.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 四态状态文案（0 没装 / 1 生效 / 2 区域为空 / 3 维度不符），界面状态行与 tooltip 共用。 */
    private Component statusLine(final int status) {
        final AdvancedCollectionCacheMenu advanced = advancedMenu();
        return switch (status) {
            case 1 -> Component.translatable(LANG + "info.active",
                advanced.getAreaCount(), advanced.getAreaVolume());
            case 2 -> Component.translatable(LANG + "info.empty");
            case 3 -> Component.translatable(LANG + "info.dimension");
            default -> Component.translatable(LANG + "info.none");
        };
    }

    /** 供自检 / 诊断：本界面用的是模组自己的菜单类型。 */
    public static Class<?> menuType() {
        return AdvancedCollectionCacheMenu.class;
    }

    /** 创建界面（ClientInit 的屏幕工厂直接引用本方法，避免 lambda 泛型推断问题）。 */
    public static AdvancedCollectionCacheScreen create(final CollectionCacheMenu menu,
                                                       final Inventory inventory,
                                                       final Component title) {
        return new AdvancedCollectionCacheScreen(menu, inventory, title);
    }

    /** 模组 id（诊断用）。 */
    public static String modId() {
        return RS_Create_Compat.MODID;
    }

    /** 供自检 / 诊断：本界面的「区域」按钮（侧栏开关）。 */
    @Nullable
    public Button areaButton() {
        return areaButton;
    }
}
