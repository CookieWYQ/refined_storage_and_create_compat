package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.client.EnergyAuditHighlight;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditNode;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditRequestPacket;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditType;
import cretae.cookiewyq.rs_create_compat.network.SwitchTerminalModePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncEnergyAuditPacket;
import cretae.cookiewyq.rs_create_compat.support.RsccTerminalLocator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 「RS 网络能耗监视」页 —— 高级远程多功能终端的第 7 档模式（{@code MODE_ENERGY}）的界面。
 *
 * <h2>用户要求（唯一口径）</h2>
 * <p>原话：「<i>显示当前这个 RS 网络中所有会消耗电力的方块，并且标注出它消耗了多少电力，并且有什么图标
 * 之类的；相同种类的方块合并显示放在一块……也会标注他们的坐标；也可以单独选择某个方块进行高亮，
 * 实时显示某些方块的耗电量，以方便我排除到底哪方块一直在耗电耗那么多；并且也可以标注维度。</i>」</p>
 * <ul>
 *     <li><b>只列耗电方块</b>：服务端只下发 {@code energyUsage > 0} 的节点（线缆 / 控制器 / 能量池提供方天然不进列表）；</li>
 *     <li><b>按类型合并</b>：一行 = 一种方块（方块注册 id）；行上给图标 + 名字 + 台数 + 该类型当前合计 FE/t；</li>
 *     <li><b>坐标 + 维度 + 单体读数</b>：点行展开，逐台列出「维度 + 坐标 + FE/t」，待机（当前 0）的标灰写「待机」；</li>
 *     <li><b>实时</b>：本界面每 {@link Config#energyAuditRefreshTicks} tick 向服务端要一份新快照
 *     （默认 20 tick = 1 秒），所以数字会持续波动 —— 这正是「看谁一直在耗电」需要的东西；</li>
 *     <li><b>高亮</b>：点某一台的子行 → 发 C2S 请求 → 服务端校验「确属本网络且当前耗电 &gt; 0」→
 *     下一份快照带选中回声 → {@link EnergyAuditHighlight} 在世界里画框（与范围充电器同一套橙色框）。</li>
 * </ul>
 *
 * <h2>为什么是「纯 Screen」而不是容器菜单</h2>
 * <p>本页<b>没有物品槽</b>，也就不需要 {@code MenuType} / {@code AbstractContainerMenu} / 客户端界面注册。
 * 服务端在切模式时先 {@code closeContainer()}，再下发第一份快照；本类的
 * {@link #openOrRefresh} 收到就 {@code setScreen(this)}。少一整套菜单注册 = 少一处与 Lead 写域的交叉。</p>
 *
 * <h2>客户端不查任何东西（硬规则，自检会盯）</h2>
 * <ul>
 *     <li>不读方块实体、不查能力、不扫世界、不算耗电 —— 本类里<b>没有</b> {@code level().getBlockEntity} /
 *     {@code getCapability} / {@code for (BlockPos} 之类的世界遍历；</li>
 *     <li>高亮坐标只来自 S2C 快照的 {@code hasSelection/selectedDimension/selectedPos}；</li>
 *     <li>唯一的本地状态是「哪些类型展开着」「列表滚到第几行」这类纯 UI 状态，以及「点了哪一台」（随后由
 *     服务端确认才画框）。</li>
 * </ul>
 *
 * <h2>跨维度：界面上如实写，不假装</h2>
 * <p>RS 的一张网络里的容器只可能在同一维度（跨维度是 transmitter / receiver 两张网络互联），
 * 因此列表顶部固定有一行提示：「只显示终端绑定的这一张网络；跨维度对端不在此列」。
 * 每台方块自己仍带维度（已知维度给中文名，未知直接显示 id）。</p>
 *
 * <h2>渲染硬规则（与工程其它界面一致）</h2>
 * <ul>
 *     <li>面板走基类 {@link ChildConfigScreen} 的原版九宫格；不新增任何贴图；</li>
 *     <li>所有文字 {@code dropShadow = false}；</li>
 *     <li>可交互元素（行 / 滚动条 / 三个按钮）的 tooltip 全部<b>手动渲染</b>，hover 判定与绘制几何同源。</li>
 * </ul>
 */
public class EnergyAuditScreen extends ChildConfigScreen {
    /** 本界面全部语言键前缀（与语言文件逐字一致）。 */
    private static final String LANG = "gui.rs_create_compat.energy_audit.";

    // ==================== 几何（面板内相对坐标；类注释里有对应说明） ====================
    public static final int PANEL_W = 320;
    public static final int PANEL_H = 240;
    private static final int PAD = 10;
    private static final int TITLE_Y = 8;
    /** 「网络电量 x / y FE」行。 */
    private static final int POWER_Y = 21;
    /** 「合计 x FE/t（标称 y）」行。 */
    private static final int TOTAL_Y = 32;
    /** 截断 / 选中 / 计数 提示行（单行截断，完整文本看 tooltip）。 */
    private static final int HINT_Y = 43;
    /** 跨维度提示行（固定在列表上方，浅灰小字）。 */
    private static final int CROSS_DIM_Y = 54;
    /** 列表矩形；行高 16 ⇒ 9 行/页。 */
    private static final int LIST_X = 10;
    private static final int LIST_Y = 66;
    private static final int LIST_W = 300;
    private static final int LIST_H = 144;
    private static final int ROW_H = 16;
    private static final int VISIBLE_ROWS = LIST_H / ROW_H;
    /** 滚动条贴列表内右侧（与 ChargerFilterScreen 同口径：列表矩形包含轨道）。 */
    private static final int SCROLL_X = LIST_X + LIST_W - 10;
    private static final int SCROLL_W = 10;
    /** 行内容右边界（局部）：滚动条左缘 − 2px，文字绝不压在滚动条下。 */
    private static final int ROW_RIGHT = SCROLL_X - 2;
    /** 底部三个按钮：返回终端 / 刷新 / 清除高亮。 */
    private static final int BTN_Y = 216;
    private static final int BTN_H = 18;
    private static final int BACK_X = PAD;
    private static final int BACK_W = 120;
    private static final int REFRESH_X = 134;
    private static final int REFRESH_W = 80;
    private static final int CLEAR_X = 218;
    private static final int CLEAR_W = 92;
    /** 单行文字高（原版字体 9px）。 */
    private static final int LINE_H = 9;

    // ---------- 行内几何（相对行左上角；绘制与命中共用） ----------
    /** 展开箭头（文字字形 ▶ / ▼）局部 x。 */
    private static final int ROW_ARROW_X = 3;
    /** 图标 16×16 的局部 x（方块物品图标）。 */
    private static final int ROW_ICON_X = 13;
    /** 名字起点（图标右缘 29 + 4px）。 */
    private static final int ROW_NAME_X = 33;
    /** 子行（每台）的缩进：与父行名字左缘对齐。 */
    private static final int NODE_INDENT = ROW_NAME_X;
    /** 类型行右侧「×N」列的右缘（相对行右边界）。 */
    private static final int COUNT_RIGHT = ROW_RIGHT - 78;

    // ==================== 配色（深色字压在浅色九宫格面板上，与其它子界面同族） ====================
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF1F1F1F;
    private static final int COLOR_DIM = 0xFF5A5A5A;
    private static final int COLOR_ACCENT = 0xFF2E5C8A;
    private static final int COLOR_WARN = 0xFF9B2020;
    private static final int COLOR_LIST_BG = 0xFFC6C6C6;
    private static final int COLOR_LIST_BORDER = 0xFF2B2B2B;
    private static final int COLOR_ROW_HOVER = 0x30000000;
    private static final int COLOR_ROW_SELECTED = 0x502E5C8A;
    /** 待机（active=false）行的文字色：明显弱于正常行，但不至于看不清。 */
    private static final int COLOR_IDLE = 0xFF8A8A8A;
    /** 表格分隔线（类型行与子行之间、行与行之间的淡线）。 */
    private static final int COLOR_GRID_LINE = 0x22808080;

    // ==================== 状态（全部纯客户端 UI 状态） ====================

    /** 最新一份服务端快照（初值 = 空快照，界面开出来但第一份数据还没到时的兜底）。 */
    private SyncEnergyAuditPacket snapshot =
        SyncEnergyAuditPacket.empty(AdvancedRemoteTerminalItem.MODE_GRID);
    /** 展开着的类型（键 = 方块注册 id 字符串）。 */
    private final Set<String> expanded = new LinkedHashSet<>();
    /** 由快照 + 展开集合算出来的可见行（类型行 / 每台子行）。 */
    private List<Row> rows = List.of();
    /** 列表顶部对应的行下标（滚动位置）。 */
    private int firstRow;
    /** 距下次请求快照还剩多少 tick。 */
    private int refreshTimer;
    /** 本地「点了哪一台」（= 服务端回显的键；回显为空时清空）。用于把该子行画成选中态。 */
    @Nullable
    private String selectedKey;
    /** 滚动条控件（只有需要滚动时才真正可拖）。 */
    @Nullable
    private SptScrollbarWidget scrollbar;
    /**
     * 「返回终端」已经发包、正在等服务端替换界面的剩余 tick（{@code -1} = 没在返回）。
     * <p>兜底时长 40 tick = 2 秒：足够覆盖一次正常往返（含 200ms 级延迟），又不至于让被拒绝的玩家干等。</p>
     */
    private static final int RETURN_FALLBACK_TICKS = 40;
    private int pendingReturnTicks = -1;

    /**
     * 一行：类型行（{@code node == null}）或某台的子行（{@code node != null}，{@code type} 是它的父类型）。
     */
    private record Row(EnergyAuditType type, @Nullable EnergyAuditNode node) {
    }

    private EnergyAuditScreen(final SyncEnergyAuditPacket first) {
        super(Component.translatable(LANG + "title"), null, PANEL_W, PANEL_H);
        accept(first);
    }

    /**
     * 收到一份快照：<b>界面没开就开界面，已经开着就就地刷新</b>（服务端每秒一条，因此这里必须无副作用地可重复调用）。
     *
     * <p>为什么用「查找现有界面」而不是「每次都 new」：每帧重开界面会丢掉展开状态、滚动位置、
     * 还会让鼠标位置不停被重置 —— 玩家看到的会是「列表自己在抖」。就地刷新则保住全部本地 UI 状态。</p>
     */
    public static void openOrRefresh(final SyncEnergyAuditPacket packet) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof EnergyAuditScreen existing) {
            existing.accept(packet);
            return;
        }
        minecraft.setScreen(new EnergyAuditScreen(packet));
    }

    // ==================== 快照 → 本地状态 ====================

    private void accept(final SyncEnergyAuditPacket packet) {
        this.snapshot = packet;
        // 高亮：**只**由服务端回显驱动（客户端不自己猜坐标）。有选中 ⇒ 续期；没有 ⇒ 立刻撤。
        if (packet.hasSelection() && packet.selectedDimension() != null) {
            this.selectedKey = EnergyAuditNode.keyOf(packet.selectedDimension(), packet.selectedPos());
            EnergyAuditHighlight.arm(packet.selectedDimension(), packet.selectedPos());
        } else {
            this.selectedKey = null;
            EnergyAuditHighlight.clear();
        }
        // 展开状态只保留「这一份快照里还存在的类型」（类型消失了就别留下脏键）
        final Set<String> alive = new LinkedHashSet<>();
        for (final EnergyAuditType type : packet.types()) {
            alive.add(type.blockId().toString());
        }
        expanded.retainAll(alive);
        rebuildRows();
        updateScrollRange();
    }

    /** 由快照 + 展开集合重算可见行（类型行按服务端给的降序，子行保持服务端给的单体顺序）。 */
    private void rebuildRows() {
        final List<Row> built = new ArrayList<>();
        for (final EnergyAuditType type : snapshot.types()) {
            built.add(new Row(type, null));
            if (expanded.contains(type.blockId().toString())) {
                for (final EnergyAuditNode node : type.nodes()) {
                    built.add(new Row(type, node));
                }
            }
        }
        this.rows = List.copyOf(built);
        if (scrollbar != null) {
            scrollbar.setMaxOffset(maxScroll());
            if (firstRow > maxScroll()) {
                firstRow = maxScroll();
            }
        }
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - VISIBLE_ROWS);
    }

    private void updateScrollRange() {
        if (scrollbar == null) {
            return;
        }
        scrollbar.setMaxOffset(maxScroll());
        if (scrollbar.getOffset() > maxScroll()) {
            scrollbar.setOffset(maxScroll());
        }
    }

    // ==================== 生命周期 / 控件 ====================

    @Override
    protected void init() {
        super.init();
        firstRow = Math.min(firstRow, maxScroll());
        scrollbar = new SptScrollbarWidget(px + SCROLL_X, py + LIST_Y,
            SptScrollbarWidget.Type.SMALL, LIST_H, SCROLL_W);
        scrollbar.setListener(offset -> firstRow = (int) Math.round(offset));
        scrollbar.setMaxOffset(maxScroll());
        scrollbar.setOffset(firstRow);
        addRenderableWidget(scrollbar);
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "button.back"),
            button -> returnToTerminal()).bounds(px + BACK_X, py + BTN_Y, BACK_W, BTN_H).build());
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "button.refresh"),
            button -> requestRefresh()).bounds(px + REFRESH_X, py + BTN_Y, REFRESH_W, BTN_H).build());
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "button.clear"),
            button -> requestClear()).bounds(px + CLEAR_X, py + BTN_Y, CLEAR_W, BTN_H).build());
        rebuildRows();
        // 立刻要一份（服务端打开时已发过一条，这里补一条保证「切完模式马上有数据」；
        // 服务端有 2 tick 最小间隔节流，多要一次不会放大开销）
        refreshTimer = 0;
    }

    /**
     * 每客户端 tick：按 {@link Config#energyAuditRefreshTicks} 周期向服务端要快照。
     * <p>本方法是「实时」的唯一驱动点：界面一关，MC 不再调用它 ⇒ 请求自然停止，服务端零常驻开销。</p>
     * <p>另外它还承担「返回终端」的<b>兜底关闭</b>：点了返回但服务端没有把界面换掉（切模式被拒，
     * 例如绑定网络没电 —— 见 {@code SwitchTerminalModePacket} 的服务端电量门），
     * 到 {@link #RETURN_FALLBACK_TICKS} 就自己退出，绝不让玩家卡在一个什么都不响应的页面上。</p>
     */
    @Override
    public void tick() {
        super.tick();
        if (pendingReturnTicks > 0) {
            pendingReturnTicks--;
            if (pendingReturnTicks == 0) {
                // 服务端没在时限内换掉我们 ⇒ 兜底退出到世界（玩家可以再按终端快捷键打开终端）
                Minecraft.getInstance().setScreen(null);
            }
            return; // 正在返回：不再发刷新请求（省一次无用往返）
        }
        if (refreshTimer > 0) {
            refreshTimer--;
            return;
        }
        requestRefresh();
    }

    private void requestRefresh() {
        refreshTimer = Math.max(1, Config.energyAuditRefreshTicks);
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(EnergyAuditRequestPacket.refresh());
    }

    private void requestClear() {
        // 本地先撤（点击即响应），服务端回显的下一条快照会把「无选中」再确认一次
        selectedKey = null;
        EnergyAuditHighlight.clear();
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(EnergyAuditRequestPacket.clear());
        }
    }

    /**
     * 「返回终端」/ Esc：切回打开能耗页之前的那一档模式（服务端权威，界面由服务端重开）。
     *
     * <h2>为什么这里<b>不</b>先 {@code setScreen(null)}</h2>
     * <p>服务端切模式前有一道<b>电量门</b>（{@code SwitchTerminalModePacket} 里：绑定网络没电 ⇒ 拒绝切换
     * 并回一条可见提示）。若本方法先把界面关掉再发包，被拒时玩家就会落到「没有界面、也没有任何提示
     * 解释为什么」的状态。因此：<b>只发包，不关屏</b> —— 服务端接受 ⇒ 它把本界面换成目标模式界面
     * （{@code ClientboundOpenScreenPacket} → {@code setScreen}，本类 {@link #removed()} 自然被调用）；
     * 服务端拒绝 ⇒ 玩家仍停在本页，能看到 actionbar 上的提示。
     * 万一服务端既没接受也没回应（掉线 / 包丢失），{@link #tick()} 里的兜底计时会把界面关掉。</p>
     */
    private void returnToTerminal() {
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;
        final int returnMode = snapshot.returnMode();
        // 先撤高亮：离开这个页面后不该继续在世界里留框
        EnergyAuditHighlight.clear();
        if (player == null) {
            minecraft.setScreen(null);
            return;
        }
        final Optional<RsccTerminalLocator.Location> located = RsccTerminalLocator.locate(player);
        if (located.isEmpty()) {
            // 终端不在了（被销毁 / 被移到别处）：没有可切换的对象，直接关界面。
            // 物品上的模式仍是能耗页 —— 下次用终端打开还会回到这里，不会「消失」。
            minecraft.setScreen(null);
            return;
        }
        // 复用既有模式切换包：服务端「已是目标模式则不重开」的保护意味着 returnMode 必须 ≠ 当前值（= 能耗页）
        PacketDistributor.sendToServer(new SwitchTerminalModePacket(
            located.get().slotReference(), returnMode, -1, -1));
        // 兜底：服务端没在 RETURN_FALLBACK_TICKS 内换掉本界面 ⇒ tick() 里自己退出
        pendingReturnTicks = RETURN_FALLBACK_TICKS;
    }

    @Override
    public void removed() {
        // 界面被任何方式移除（Esc / 返回 / 被服务端换掉）都撤框：不让它靠 TTL 自己灭
        EnergyAuditHighlight.clear();
        super.removed();
    }

    @Override
    public void onClose() {
        returnToTerminal();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        // 【第 111 轮修「界面一片空白、只有 tooltip」】渲染顺序必须是：
        //   super.render（背景面板 + 控件）→ 内容（标题 / 摘要 / 列表）→ 滚动条重画 → tooltip。
        // 旧顺序把 renderContent 放在 super.render **之前**：而 ChildConfigScreen#renderBackground
        // 会画满整块子面板（原版九宫格精灵不透明），于是标题 / 摘要 / 列表**全部被面板盖住**；
        // 按钮、滚动条、tooltip 因为画在 super / renderTooltips 之后所以仍然可见 ——
        // 正是用户截图里「一大片空面板、只有 tooltip 和按钮」的样子。
        // 内容画在控件之后会盖住列表右缘的滚动条，所以滚动条要在内容之后**重画一次**。
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderContent(guiGraphics, mouseX, mouseY);
        if (scrollbar != null) {
            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    private void renderContent(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 标题
        guiGraphics.drawString(font, Component.translatable(LANG + "title"),
            px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        // 摘要两行：网络电量 / 合计（截断前的全量合计，服务端算好）
        // 第 104 轮：所有文字放不下时一律整体缩字号完整显示（下限 RsccTextFit.MIN_SCALE），
        // 不再出现省略号，也不会静默越出面板右缘。
        if (snapshot.bound()) {
            RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "power",
                    format(snapshot.stored()), format(snapshot.capacity())),
                px + PAD, py + POWER_Y, LIST_W, COLOR_TEXT);
            RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "total",
                    format(snapshot.totalCurrent()), format(snapshot.totalNominal())),
                px + PAD, py + TOTAL_Y, LIST_W, COLOR_TEXT);
        } else {
            RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "no_network"),
                px + PAD, py + POWER_Y, LIST_W, COLOR_WARN);
        }
        // 提示行：截断 > 选中 > 台数/类型数（优先级：越需要玩家注意的越优先）
        RsccTextFit.drawFitLine(guiGraphics, font, hintText(),
            px + PAD, py + HINT_Y, LIST_W, hintColor());
        // 跨维度提示（固定一行浅灰小字；用户要求「别让人以为漏了」）
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "cross_dim_hint"),
            px + PAD, py + CROSS_DIM_Y, LIST_W, COLOR_DIM);
        // 列表
        renderList(guiGraphics, mouseX, mouseY);
    }

    private Component hintText() {
        if (!snapshot.bound()) {
            return Component.empty();
        }
        if (snapshot.truncated()) {
            return Component.translatable(LANG + "truncated",
                format(snapshot.totalBlocks() - snapshot.hiddenBlocks()), format(snapshot.totalTypes() - snapshot.hiddenTypes()),
                format(snapshot.totalBlocks()), format(snapshot.totalTypes()));
        }
        if (snapshot.hasSelection() && snapshot.selectedDimension() != null) {
            final String key = EnergyAuditNode.keyOf(snapshot.selectedDimension(), snapshot.selectedPos());
            final EnergyAuditNode selected = findSelectedNode(key);
            final String usage = selected == null
                ? "?"
                : format(selected.currentUsage());
            return Component.translatable(LANG + "selected",
                dimensionText(snapshot.selectedDimension()),
                EnergyAuditNode.posText(snapshot.selectedPos()), usage);
        }
        return Component.translatable(LANG + "count",
            format(snapshot.totalTypes()), format(snapshot.totalBlocks()));
    }

    private int hintColor() {
        if (!snapshot.bound()) {
            return COLOR_WARN;
        }
        if (snapshot.truncated()) {
            return COLOR_WARN;
        }
        return snapshot.hasSelection() ? COLOR_ACCENT : COLOR_DIM;
    }

    @Nullable
    private EnergyAuditNode findSelectedNode(final String key) {
        for (final EnergyAuditType type : snapshot.types()) {
            for (final EnergyAuditNode node : type.nodes()) {
                if (node.key().equals(key)) {
                    return node;
                }
            }
        }
        return null;
    }

    private void renderList(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 列表底 + 1px 边框（与 ChargerFilterScreen 同一套 fill 结构色，不新增贴图）
        guiGraphics.fill(px + LIST_X, py + LIST_Y, px + LIST_X + LIST_W, py + LIST_Y + LIST_H,
            COLOR_LIST_BG);
        guiGraphics.fill(px + LIST_X, py + LIST_Y, px + LIST_X + LIST_W, py + LIST_Y + 1,
            COLOR_LIST_BORDER);
        guiGraphics.fill(px + LIST_X, py + LIST_Y + LIST_H - 1, px + LIST_X + LIST_W,
            py + LIST_Y + LIST_H, COLOR_LIST_BORDER);
        guiGraphics.fill(px + LIST_X, py + LIST_Y, px + LIST_X + 1, py + LIST_Y + LIST_H,
            COLOR_LIST_BORDER);
        guiGraphics.fill(px + LIST_X + LIST_W - 1, py + LIST_Y, px + LIST_X + LIST_W,
            py + LIST_Y + LIST_H, COLOR_LIST_BORDER);

        if (!snapshot.bound()) {
            drawEmptyText(guiGraphics, Component.translatable(LANG + "no_network"));
            return;
        }
        if (rows.isEmpty()) {
            drawEmptyText(guiGraphics, Component.translatable(LANG + "empty"));
            return;
        }
        // 裁剪：行绝不画出列表矩形（滚动时不会盖住标题 / 按钮）
        guiGraphics.enableScissor(px + LIST_X + 1, py + LIST_Y + 1,
            px + LIST_X + LIST_W - 1, py + LIST_Y + LIST_H - 1);
        try {
            for (int index = firstRow; index < rows.size(); index++) {
                final int rowY = py + LIST_Y + (index - firstRow) * ROW_H;
                if (rowY + ROW_H > py + LIST_Y + LIST_H) {
                    break;
                }
                final boolean hovered = mouseX >= px + LIST_X && mouseX < px + LIST_X + ROW_RIGHT
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
                renderRow(guiGraphics, rows.get(index), rowY, hovered);
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void drawEmptyText(final GuiGraphics guiGraphics, final Component text) {
        final int width = font.width(text);
        guiGraphics.drawString(font, text, px + LIST_X + (LIST_W - width) / 2,
            py + LIST_Y + LIST_H / 2 - LINE_H / 2, COLOR_DIM, false);
    }

    private void renderRow(final GuiGraphics guiGraphics, final Row row, final int rowY,
                           final boolean hovered) {
        final int rowX = px + LIST_X + 1;
        final int rowW = ROW_RIGHT - 1;
        if (hovered) {
            guiGraphics.fill(rowX, rowY, rowX + rowW, rowY + ROW_H, COLOR_ROW_HOVER);
        }
        if (row.node() == null) {
            renderTypeRow(guiGraphics, row.type(), rowY);
        } else {
            renderNodeRow(guiGraphics, row.type(), row.node(), rowY);
        }
    }

    /** 类型行：▼/▶ + 图标 + 名称 + ×N + 该类型当前合计 FE/t。 */
    private void renderTypeRow(final GuiGraphics guiGraphics, final EnergyAuditType type, final int rowY) {
        final boolean open = expanded.contains(type.blockId().toString());
        final int textY = rowY + (ROW_H - LINE_H) / 2;
        // rscc:no-tooltip 纯装饰：展开/收起箭头只有一个字形，宽度恒定，没有「看不清全文」的问题
        guiGraphics.drawString(font, open ? "\u25BC" : "\u25B6",
            px + LIST_X + 1 + ROW_ARROW_X, textY, COLOR_DIM, false);
        final ItemStack icon = type.icon();
        if (!icon.isEmpty()) {
            guiGraphics.renderItem(icon, px + LIST_X + 1 + ROW_ICON_X, rowY);
        }
        final String count = Component.translatable(LANG + "row.count", format(type.count())).getString();
        final String usage = Component.translatable(LANG + "usage", format(type.currentUsage())).getString();
        final int countX = px + COUNT_RIGHT - font.width(count);
        final int usageX = px + ROW_RIGHT - font.width(usage);
        final int nameMax = countX - (px + LIST_X + 1 + ROW_NAME_X) - 4;
        // 方块名（自由文本：注册名 / 第三方模组名）：放不下整体缩字号完整显示，不截断；
        // 完整方块名与方块 ID 另在行 tooltip 里给出（hover 行即可看全）。
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(typeName(type)),
            px + LIST_X + 1 + ROW_NAME_X, textY, Math.max(8, nameMax), COLOR_TEXT);
        guiGraphics.drawString(font, count, countX, textY, COLOR_DIM, false);
        // 当前耗电为 0（全部待机）时数字也用灰字：一眼能看出「这种现在不耗电」
        guiGraphics.drawString(font, usage, usageX, textY,
            type.currentUsage() > 0L ? COLOR_TEXT : COLOR_IDLE, false);
        guiGraphics.fill(px + LIST_X + 1, rowY + ROW_H - 1, px + ROW_RIGHT, rowY + ROW_H,
            COLOR_GRID_LINE);
    }

    /** 子行（某一台）：维度 + 坐标 + 单体当前 FE/t（待机时灰字 + 「待机」）。 */
    private void renderNodeRow(final GuiGraphics guiGraphics, final EnergyAuditType type,
                               final EnergyAuditNode node, final int rowY) {
        final int textY = rowY + (ROW_H - LINE_H) / 2;
        final String key = node.key();
        final boolean selected = key.equals(selectedKey);
        if (selected) {
            guiGraphics.fill(px + LIST_X + 1, rowY, px + ROW_RIGHT, rowY + ROW_H,
                COLOR_ROW_SELECTED);
        }
        final String where = Component.translatable(LANG + "row.node",
            dimensionText(node.dimension()), EnergyAuditNode.posText(node.pos())).getString();
        final String usage = node.active()
            ? Component.translatable(LANG + "usage", format(node.currentUsage())).getString()
            : Component.translatable(LANG + "idle").getString();
        final int usageX = px + ROW_RIGHT - font.width(usage);
        final int whereMax = usageX - (px + LIST_X + 1 + NODE_INDENT) - 6;
        // 「维度 + 坐标」：放不下整体缩字号完整显示（不截断）；完整串在行 tooltip 里也有。
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(where),
            px + LIST_X + 1 + NODE_INDENT, textY, Math.max(8, whereMax),
            selected ? COLOR_ACCENT : (node.active() ? COLOR_TEXT : COLOR_IDLE));
        guiGraphics.drawString(font, usage, usageX, textY,
            selected ? COLOR_ACCENT : (node.active() ? COLOR_TEXT : COLOR_IDLE), false);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 0) {
            final int index = rowAt(mouseX, mouseY);
            if (index >= 0) {
                onRowClicked(rows.get(index));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onRowClicked(final Row row) {
        if (row.node() == null) {
            // 类型行：展开 / 收起（纯本地 UI 状态，不用问服务端）
            final String key = row.type().blockId().toString();
            if (!expanded.remove(key)) {
                expanded.add(key);
            }
            rebuildRows();
            updateScrollRange();
            return;
        }
        final EnergyAuditNode node = row.node();
        if (node.key().equals(selectedKey)) {
            requestClear(); // 再点一次 = 取消（与「清除高亮」按钮同一条路径）
            return;
        }
        selectedKey = node.key(); // 本地先高亮该子行；世界里的框等服务端回显才画
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(EnergyAuditRequestPacket.select(node.dimension(), node.pos()));
        }
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (rowAt(mouseX, mouseY) >= 0 || inList(mouseX, mouseY)) {
            final int direction = (int) Math.signum(scrollY);
            if (direction != 0 && scrollbar != null) {
                // 走滚动条自己的 offset（单一滚动位置来源），listener 会回写 firstRow
                scrollbar.setOffset(scrollbar.getOffset() - direction);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 鼠标落在列表矩形内时返回行下标（相对 {@link #rows}）；不在 / 落在滚动条上时返回 -1。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (!inList(mouseX, mouseY)) {
            return -1;
        }
        if (mouseX >= px + SCROLL_X && mouseX < px + SCROLL_X + SCROLL_W
            && mouseY >= py + LIST_Y && mouseY < py + LIST_Y + LIST_H) {
            return -1; // 滚动条区域不选中行（滚动条自己处理点击 / 拖动）
        }
        final int local = (int) ((mouseY - (py + LIST_Y)) / ROW_H);
        final int index = firstRow + local;
        if (index < 0 || index >= rows.size()) {
            return -1;
        }
        return index;
    }

    private boolean inList(final double mouseX, final double mouseY) {
        return mouseX >= px + LIST_X && mouseX < px + LIST_X + LIST_W
            && mouseY >= py + LIST_Y && mouseY < py + LIST_Y + LIST_H;
    }

    // ==================== tooltip（全部手动渲染，hover 判定与绘制同源） ====================

    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            final Row row = rows.get(index);
            final List<Component> lines = new ArrayList<>(6);
            if (row.node() == null) {
                final EnergyAuditType type = row.type();
                lines.add(Component.literal(typeName(type)));
                lines.add(Component.translatable(LANG + "tip.type"));
                lines.add(Component.translatable(LANG + "tip.type_usage",
                    format(type.currentUsage()), format(type.nominalUsage())));
                lines.add(Component.translatable(LANG + "row.id", type.blockId().toString()));
            } else {
                final EnergyAuditNode node = row.node();
                lines.add(Component.literal(typeName(row.type())));
                lines.add(Component.translatable(LANG + "row.node",
                    dimensionText(node.dimension()), EnergyAuditNode.posText(node.pos())));
                lines.add(Component.translatable(LANG + "tip.node_usage",
                    format(node.currentUsage()), format(node.nominalUsage())));
                lines.add(Component.translatable(LANG + "tip.node"));
            }
            guiGraphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
            return;
        }
        if (mouseX >= px + SCROLL_X && mouseX < px + SCROLL_X + SCROLL_W
            && mouseY >= py + LIST_Y && mouseY < py + LIST_Y + LIST_H) {
            renderTip(guiGraphics, Component.translatable(LANG + "list.scroll.tip"), mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, BACK_X, BTN_Y, BACK_W, BTN_H)) {
            renderTip(guiGraphics, Component.translatable(LANG + "button.back.tip"), mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, REFRESH_X, BTN_Y, REFRESH_W, BTN_H)) {
            renderTip(guiGraphics, Component.translatable(LANG + "button.refresh.tip"), mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, CLEAR_X, BTN_Y, CLEAR_W, BTN_H)) {
            renderTip(guiGraphics, Component.translatable(LANG + "button.clear.tip"), mouseX, mouseY);
            return;
        }
        // 提示行 / 跨维度提示被截断时，hover 给出完整文本（工程硬规则：放不下的必须能看全）
        if (inBox(mouseX, mouseY, PAD, HINT_Y, LIST_W, LINE_H)) {
            final Component hint = hintText();
            if (!hint.getString().isEmpty()) {
                renderTip(guiGraphics, hint, mouseX, mouseY);
            }
            return;
        }
        if (inBox(mouseX, mouseY, PAD, CROSS_DIM_Y, LIST_W, LINE_H)) {
            renderTip(guiGraphics, Component.translatable(LANG + "cross_dim_hint"), mouseX, mouseY);
        }
    }

    private void renderTip(final GuiGraphics guiGraphics, final Component line,
                           final int mouseX, final int mouseY) {
        guiGraphics.renderTooltip(font, List.of(line), Optional.empty(), mouseX, mouseY);
    }

    private boolean inBox(final double mouseX, final double mouseY,
                          final int x, final int y, final int w, final int h) {
        return mouseX >= px + x && mouseX < px + x + w
            && mouseY >= py + y && mouseY < py + y + h;
    }

    // ==================== 文本 / 数字格式化 ====================

    /** 类型显示名：能拿到图标就用物品名（= 方块名的本地化结果），拿不到就用注册 id。 */
    private static String typeName(final EnergyAuditType type) {
        final ItemStack icon = type.icon();
        if (!icon.isEmpty()) {
            return icon.getHoverName().getString();
        }
        final ResourceLocation id = type.blockId();
        return id == null ? "?" : id.toString();
    }

    /** 维度语言键（已知三维度给中文；未知维度直接显示 id 原文）。 */
    private static String dimensionKey(@Nullable final ResourceLocation dimension) {
        if (dimension == null) {
            return LANG + "dim.unknown";
        }
        return switch (dimension.toString()) {
            case "minecraft:overworld" -> LANG + "dim.overworld";
            case "minecraft:the_nether" -> LANG + "dim.nether";
            case "minecraft:the_end" -> LANG + "dim.end";
            default -> "";
        };
    }

    /**
     * 维度显示文本：已知维度走语言键（可翻译），未知维度原样显示 id（<b>绝不编造名字</b>）。
     * <p>为什么未知维度不在 {@link #dimensionKey} 里返回一个键：那会把「id 本身」当成键去查表，
     * 查不到时原版会把键原文当文本显示（看起来能用），但语言文件里就多了一堆假键；
     * 这里显式分流，语义清楚。</p>
     */
    private Component dimensionText(@Nullable final ResourceLocation dimension) {
        final String key = dimensionKey(dimension);
        if (!key.isEmpty()) {
            return Component.translatable(key);
        }
        return Component.literal(dimension == null ? "?" : dimension.toString());
    }

    /** 带千分位的整数（不依赖默认 Locale，避免某些语言下出现 {@code 1 234}）。 */
    private static String format(final long value) {
        final boolean negative = value < 0;
        final String digits = Long.toString(Math.abs(value));
        final StringBuilder builder = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                builder.append(',');
            }
            builder.append(digits.charAt(i));
        }
        return negative ? "-" + builder : builder.toString();
    }
}
