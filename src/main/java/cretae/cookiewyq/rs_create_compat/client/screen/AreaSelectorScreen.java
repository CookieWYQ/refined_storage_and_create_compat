package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.AreaOutline;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket;
import cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket;
import cretae.cookiewyq.rs_create_compat.network.SetAreaFilterContextPacket;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「区域列表」界面（第 96 轮重做，第 106 轮按用户第 5 条改成<b>只列服务端可用区域 + 每行过滤图标</b>）：
 * <b>左侧一屏可读的区域列表 + 右侧精确编辑 6 个数字 + 名称</b>。
 *
 * <h2>第 106 轮改掉的三件事（用户原话）</h2>
 * <ol>
 *     <li><b>「有的时候还会选那个根本不存在的区域」</b>：列表<b>不再</b>读选定器物品上的区域原样铺开，
 *     而是只画 {@link AdvancedAreaSnapshotPacket} 里服务端判定为<b>可用</b>的那些行
 *     （维度不符 / 超 Config 上限 / 空选定器 ⇒ 一条都不列，并说明有几个被排除）。
 *     点行之后服务端还会再校验一次，拒绝原因（区域不存在 / 维度不符 / 上限 …）随快照回传，
 *     界面立刻显示一句明确的话并<b>按服务端最新列表刷新</b> —— 不会再出现「标记失败还不说为什么」；</li>
 *     <li><b>「不能显示区域名称也不能显示区域的位置」</b>：每行第一行是「序号. 名字」（没起名就是
 *     「区域 N」），第二 / 三行是两个角坐标（放不下就拆成「从 …」「到 …」，第 96 轮口径不变）；
 *     悬停 tooltip 给完整坐标 + 尺寸 + 维度；</li>
 *     <li><b>「过滤器在方块里面设置，不直观」</b>：每行右侧直接画该区域过滤标记的<b>物品图标摘要</b>
 *     （服务端快照带过来的），点这排图标 = 选中该区域并把过滤上下文交给机器界面
 *     （见 {@link #editFilter}）；右侧面板底部也有一个「编辑过滤」按钮，两条路等价。</li>
 * </ol>
 *
 * <h2>能从哪里打开（第 125 轮：<b>只剩主手路径</b>）</h2>
 * <ul>
 *     <li>主手拿区域选定器，<b>潜行 + 右键空气</b>（{@code client/AreaSelectorHandler}）—— 本界面
 *     <b>唯一</b>还在用的入口，自带完整能力（新建 / 复制 / 删除 / 改名 / 精确坐标 / 每区域标记）；</li>
 *     <li><b>机器路径已退役</b>：高级归流缓存仓界面里那颗「区域」按钮不再打开本界面，改为展开
 *     GUI 右边的常驻侧栏 {@link AreaStripPanel}（用户原话：「不要再用现在这个界面……直接在旁边出现
 *     一条这个什么选择的，然后直接在里面选择那个，然后直接进行编辑」）。
 *     因此 {@code menu != null} 那套代码现在<b>没有调用方</b>，保留是为了「同一份能力只实现一次」，
 *     不再新增任何依赖它的入口（契约 §5「退役范围，不许一刀切删能力」）。</li>
 * </ul>
 *
 * <h2>数据流（服务端权威）</h2>
 * <p>所有修改都只发 {@link AreaSelectorEditPacket}：服务端校验「维度 / 上限 / 越界 / 去重」后写物品
 * 数据组件，再由菜单 / 背包同步回来。本界面在发送前也做一次同样的校验（{@link #errorKey} 提示），
 * 所以玩家看到的是「立刻变红、不会写进去」，而不是「写完被服务端悄悄丢掉」。</p>
 *
 * <h2>世界描边（单显 / 全显）</h2>
 * <p>选中区域常亮 = <b>单显</b>；「显示全部」开关 = <b>全显</b>。两者都真的画框
 * （{@link AreaOutline}：橙 + 棋盘面 + 1/16 线宽）。关界面时<b>不立刻删框</b>，而是按 5 秒重新武装一次
 * —— 否则「点完就关界面」看起来就是「显示根本没生效」（用户第 6 条）。</p>
 *
 * <h2>硬规则遵守</h2>
 * <ul>
 *     <li>不新增 GUI PNG：面板走 {@link ChildConfigScreen} 的原版九宫格精灵，滚动条走工程既有的
 *     {@link SptScrollbarWidget}（原版精灵）；</li>
 *     <li>所有文本都先 {@code font.width} 量宽再画，没有「写死像素硬塞长字符串」，也没有省略号
 *     （第 104 轮口径：放不下就缩字号 / 折行 / 进 tooltip）；</li>
 *     <li>tooltip 全部手动渲染（原版 {@code Screen} 不会给自绘元素画 tooltip）；</li>
 *     <li>客户端不写世界、不查能力：只改自己手里那份物品副本做「乐观显示」。</li>
 * </ul>
 */
public class AreaSelectorScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.area_selector.";
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 232;

    private static final int TITLE_Y = 8;

    // ---------- 左侧列表 ----------
    private static final int LIST_X = 10;
    private static final int LIST_Y = 24;
    private static final int LIST_W = 176;
    private static final int LIST_H = 168;
    /** 2 行版行高（索引/名字 + 坐标一行）。 */
    private static final int ROW_2H = 20;
    /** 3 行版行高（索引/名字 + 「从」 + 「到」）。 */
    private static final int ROW_3H = 28;
    /** 一页最多尝试多少行（行高可变，实际行数由 {@link #layoutRows(List)} 现算）。 */
    private static final int MAX_ROWS = 8;
    /** 最少可见行数（= 全 3 行版能放下的行数）：滚动条最大偏移按它算，保证任何行高下都能滚到底。 */
    private static final int MIN_VISIBLE_ROWS = LIST_H / ROW_3H;
    private static final int SCROLL_W = 10;
    private static final int SCROLL_X = LIST_X + LIST_W + 2;

    // ---------- 行内过滤图标（第 106 轮） ----------
    /** 每行右侧最多画几个过滤图标。 */
    private static final int ROW_ICON_MAX = 3;
    /** 行内图标尺寸 / 间距（12px 图标 + 13px 间距：一排 3 个占 38px）。 */
    private static final int ROW_ICON_SIZE = 12;
    private static final int ROW_ICON_PITCH = 13;
    /** 图标区宽度（没有过滤条目时为 0，行文字可用宽度随之变化）。 */
    private static final int ROW_ICON_BAND = ROW_ICON_MAX * ROW_ICON_PITCH + 2;

    // ---------- 右侧编辑 ----------
    private static final int AXIS_LABEL_X = 204;
    private static final int F1_X = 216;
    private static final int F2_X = 268;
    private static final int FIELD_W = 46;
    private static final int FIELD_H = 16;
    private static final int FIELDS_Y = 62;
    private static final int FIELD_STEP = 20;
    private static final int NAME_LABEL_Y = 24;
    private static final int NAME_BOX_Y = 34;
    /** 名称框宽度（第 110 轮收窄 106 → 78，给右侧「确定」按钮腾位置）。 */
    private static final int NAME_BOX_W = 78;
    private static final int NAME_BOX_H = 14;
    /** 「确定名称」按钮：紧贴名称框右侧（204+78+2 = 284，宽 26 ⇒ 右缘 310，面板内 320）。 */
    private static final int NAME_APPLY_X = AXIS_LABEL_X + NAME_BOX_W + 2;
    private static final int NAME_APPLY_W = 26;
    private static final int NAME_APPLY_H = 14;
    private static final int HEADER_Y = 52;
    /**
     * 错误提示块：起点紧贴最后一个输入框下缘（字段 102..118），行距 8px、最多 3 行
     * ⇒ 占 118..141，正好停在信息列（{@link #INFO_Y} = 142）之前。
     */
    private static final int ERROR_Y = 118;
    private static final int ERROR_MAX_LINES = 3;
    private static final int ERROR_LINE_H = 8;
    private static final int INFO_Y = 142;
    private static final int INFO_STEP = 10;

    private static final int BUTTON_Y = 200;
    private static final int REBIND_Y = 178;
    /** 「编辑过滤」按钮：与「改绑维度」同一行（两者互斥：维度不符时才可能是改绑）。 */
    private static final int EDIT_FILTER_Y = 178;
    private static final int EDIT_FILTER_W = 106;
    private static final int EDIT_FILTER_H = 16;
    private static final long DELETE_CONFIRM_MS = 5000L;
    private static final long APPLY_DEBOUNCE_MS = 250L;
    private static final long RENAME_DEBOUNCE_MS = 500L;
    /** 关界面后描边继续显示的时长（tick）：与「查看范围 / 单显 / 全显」同一个 5 秒口径。 */
    private static final int CLOSE_OUTLINE_TTL = AreaOutline.VIEW_TTL;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF6A6A6A;
    private static final int COLOR_ERROR = 0xFFB00020;
    private static final int COLOR_SELECTED = 0x30FF9F1C;
    private static final int COLOR_OUTLINE = 0xFF9F1C;
    private static final int COLOR_FIELD_BORDER = 0xFF555555;
    private static final int COLOR_FIELD_BORDER_ERROR = 0xFFB00020;

    /** 来自高级归流缓存仓菜单（null = 从主手物品打开）。 */
    @Nullable
    private final AdvancedCollectionCacheMenu menu;
    /** 六个数字框：0..2 = 起点 X/Y/Z，3..5 = 终点 X/Y/Z（与 {@link #readAreaFromFields()} 一致）。 */
    private final EditBox[] boxes = new EditBox[6];
    /** 名称框（重命名入口；空白 = 清除名字）。 */
    @Nullable
    private EditBox nameBox;
    /**
     * 「确定名称」按钮（第 110 轮用户反馈：「并没有一个按钮让我点一下确定」）。
     * <p>旧实现只有 500ms 防抖提交：玩家敲完名字按回车（EditBox 不处理回车）就关界面，
     * 如果关得比防抖还快，重命名包根本没发出去 —— 界面上显示改了（客户端乐观副本），
     * 再进来又是旧名字。现在有显式按钮 + 回车 + 关界面强制 flush 三个入口。</p>
     */
    @Nullable
    private Button nameApplyButton;
    @Nullable
    private Button newButton;
    @Nullable
    private Button copyButton;
    @Nullable
    private Button deleteButton;
    @Nullable
    private Button showAllButton;
    @Nullable
    private Button rebindButton;
    @Nullable
    private Button editFilterButton;
    @Nullable
    private Button closeButton;
    /** 自绘滚动条（工程既有控件；只画在列表右侧，事件在下面的 mouse* 里显式转发）。 */
    @Nullable
    private SptScrollbarWidget scrollbar;

    private int listOffset;
    private boolean showAll;
    private long deleteArmedAt = Long.MIN_VALUE;
    /** 已排期的字段提交（防抖：每次按键都发包会把服务端刷成中间值）。 */
    @Nullable
    private Area pendingApply;
    private long applyAtMs;
    /**
     * 这份待提交坐标属于<b>哪一行</b>（在数字框里打字时捕获的原始下标；{@code -1} = 未知）。
     * <p>第 124 轮：以前 {@link #flushApply()} 现读 {@code selection.selectedIndex()}，于是
     * 「改完坐标、250ms 内点了下一行」会把上一行的坐标写进新行（与用户 2-2 同一类 bug，只是更隐蔽）。
     */
    private int pendingApplyIndex = -1;
    /** 已排期的重命名（防抖：逐字发包没意义）。 */
    private String pendingRename = "";
    /**
     * 这条待提交的名字属于<b>哪一行</b>（在名称框里打字时捕获的原始下标；{@code -1} = 没有待提交）。
     * <p>第 124 轮用户 2-2（「输入鼓风机 → 点确定 → 点下一个区域，区域二名字直接变成鼓风机」）的落点：
     * 旧实现提交时现读 {@code selection.selectedIndex()}，切行后 = 新行 ⇒ 旧文字被写进新行。
     */
    private int pendingRenameIndex = -1;
    /**
     * 名称框<b>此刻显示的是哪一行</b>的名字（原始下标；{@code -1} = 还没有选中行）。
     * <p>它是 {@link #pendingRenameIndex} 的捕获来源，也是「选中行变了要强制刷新名称框」的判据。
     */
    private int nameBoxIndex = -1;
    /**
     * 是否<b>真有</b>一条待提交的重命名。
     * <p>旧实现靠 {@code renameAtMs} 时间戳猜：字段初值 {@code 0} + 提交后<b>不复位</b>
     * ⇒ {@code render()} 里每帧都进 {@link #flushRename()}。这既是 2-2 的放大器（切行后旧文字每帧重投），
     * 也让「打开界面」这件事本身就成了「把选中区域改名成空串」的提交（{@code pendingRename} 初值 {@code ""}）——
     * 用户 2-1「点确定了、看着改了，重开还是没改」的另一条真实成因。
     */
    private boolean renamePending;
    private long renameAtMs;
    @Nullable
    private String errorKey;
    /** 出错的那个输入框下标（{@code -1} = 与具体字段无关，例如维度不符 / 重复区域）。 */
    private int errorField = -1;
    private String lastFieldKey = "";
    private int shownAll;
    /** 正在把服务端值写回输入框：此时 responder 触发的「提交」必须忽略（否则一打开界面就发一个空包）。 */
    private boolean syncingFields;

    /** 本帧可见行的行顶 / 行高（{@link #layoutRows(List)} 每帧重算；命中测试与绘制同源）。 */
    private final int[] rowTop = new int[MAX_ROWS];
    private final int[] rowHeight = new int[MAX_ROWS];
    private int visibleRows;

    // ---------- 第 106 轮：本帧的显示行（服务端快照 → 行） ----------
    /** 本帧可见区域（服务端可用区域；主手路径为物品自身区域）。 */
    private final List<Area> displayAreas = new ArrayList<>();
    /** displayAreas 对应的<b>原始</b>下标（编辑动作用；主手路径 = 自身顺序）。 */
    private final List<Integer> displayRaw = new ArrayList<>();
    /** displayAreas 对应的<b>可用位次</b>（过滤上下文用；{@code -1} = 该行没有过滤上下文）。 */
    private final List<Integer> displayUsable = new ArrayList<>();
    /** displayAreas 对应的过滤图标摘要。 */
    private final List<List<MarkerEntry>> displayFilters = new ArrayList<>();
    /** 上一次已提示过的「服务端拒绝序号」（只在序号变大时提示一次）。 */
    private int lastRejectSeq = -1;

    public AreaSelectorScreen(@Nullable final Screen parent) {
        this(parent, null);
    }

    public AreaSelectorScreen(@Nullable final Screen parent,
                              @Nullable final AdvancedCollectionCacheMenu menu) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.menu = menu;
    }

    // ==================== 目标物品 ====================

    /**
     * 目标物品（{@code null} = 没有目标）。
     * <p><b>菜单路径只认菜单的专用槽：槽空 / 不是选定器 ⇒ 返回 {@code null}，绝不回退主手。</b>
     * 服务端 {@code AreaSelectorTargets#resolve} 对 {@code containerId >= 0} 只认菜单槽，槽空直接丢包；
     * 客户端若回退主手，就成了「界面读主手那支选定器、写包却带菜单 id」—— 所有编辑都被服务端静默丢弃，
     * 连 sync 都不会有（第 119 轮 C-2）。口径与 {@code AreaMarkScreen#targetStack()}（第 113 轮 D10-f）
     * 逐字一致：只有 {@code menu == null}（手持路径）才读主手。</p>
     */
    @Nullable
    private ItemStack targetStack() {
        if (menu != null) {
            final Minecraft minecraft = Minecraft.getInstance();
            // 服务端 menuOf 还会校验「这正是玩家当前打开的菜单」；客户端也照同一条判据，
            // 菜单已经换掉 / 关掉时不给一个注定被丢的写包（更不退回主手）。
            if (minecraft.player == null || minecraft.player.containerMenu != menu) {
                return null;
            }
            final ItemStack inSlot = menu.getAreaSelectorStack();
            return inSlot.is(RS_Create_Compat.AREA_SELECTOR.get()) ? inSlot : null;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        final ItemStack mainHand = minecraft.player.getMainHandItem();
        return mainHand.is(RS_Create_Compat.AREA_SELECTOR.get()) ? mainHand : null;
    }

    private AreaSelection currentSelection() {
        final ItemStack stack = targetStack();
        return stack == null ? AreaSelection.EMPTY : AreaSelection.get(stack);
    }

    private int containerId() {
        return menu != null ? menu.containerId : -1;
    }

    /** 本菜单对应的服务端区域快照（{@code null} = 主手路径 / 还没收到）。 */
    @Nullable
    private AdvancedAreaSnapshotPacket snapshot() {
        return menu == null ? null : AdvancedAreaSnapshotPacket.lastFor(menu.containerId);
    }

    /**
     * 当前维度能不能改这份选定器。
     * <p>服务端对「已绑定到别的维度」的选定器只接受 {@code ACTION_REBIND}（其余动作静默拒绝，
     * 见 {@link AreaSelectorEditPacket}）。如果界面不先拦一层，玩家改完 6 个数字会看到「本地变了」、
     * 但服务端根本没写 —— 典型的静默失效。这里用与服务器同一条判据把编辑闸住，
     * 并把唯一的逃生口（改绑维度）留在右侧信息区。</p>
     */
    private boolean editableHere() {
        final AreaSelection selection = currentSelection();
        if (selection.dimension() == null) {
            return true; // 未绑定：第一次落笔由服务端绑定到当前维度
        }
        final Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
            && selection.dimension().equals(minecraft.level.dimension().location());
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();
        // 六个数字框：两列（从 / 到）× 三行（X / Y / Z）
        for (int i = 0; i < 6; i++) {
            final int row = i % 3;
            final int column = i / 3;
            final EditBox box = new EditBox(font,
                px + (column == 0 ? F1_X : F2_X),
                py + FIELDS_Y + row * FIELD_STEP, FIELD_W, FIELD_H,
                Component.empty());
            box.setTextShadow(false);
            box.setMaxLength(RsccNumberField.MAX_LENGTH);
            // 唯一解析口径（千分位 / 后缀 / 非法输入回退）：照工程既有 RsccNumberField.wire
            RsccNumberField.wire(box, value -> scheduleApply());
            boxes[i] = box;
            addRenderableWidget(box);
        }
        // 名称框：既是「重命名」入口，也是列表第一行的显示来源（回车 / 失焦 / 500ms 防抖后提交）
        nameBox = new EditBox(font, px + AXIS_LABEL_X, py + NAME_BOX_Y, NAME_BOX_W, NAME_BOX_H,
            Component.translatable(LANG + "name"));
        nameBox.setTextShadow(false);
        nameBox.setMaxLength(Area.MAX_NAME_LENGTH);
        nameBox.setHint(Component.translatable(LANG + "name"));
        nameBox.setResponder(text -> scheduleRename());
        addRenderableWidget(nameBox);
        // 「确定」按钮（第 110 轮）：点一下立即提交名称，不必等防抖，也不依赖回车
        nameApplyButton = addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "name.apply"),
            button -> applyNameNow())
            .bounds(px + NAME_APPLY_X, py + NAME_BOX_Y, NAME_APPLY_W, NAME_APPLY_H)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable(LANG + "name.apply.tip")))
            .build());

        newButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "new"),
            button -> createArea()).bounds(px + 10, py + BUTTON_Y, 48, 18).build());
        copyButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "copy"),
            button -> copySelected()).bounds(px + 60, py + BUTTON_Y, 48, 18).build());
        deleteButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "delete"),
            button -> deleteSelected()).bounds(px + 110, py + BUTTON_Y, 56, 18).build());
        showAllButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "show_all.off"),
            button -> {
                showAll = !showAll;
                refreshButtonLabels();
            }).bounds(px + 168, py + BUTTON_Y, 80, 18).build());
        closeButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "close"),
            button -> returnToParent()).bounds(px + 250, py + BUTTON_Y, 48, 18).build());
        rebindButton = addRenderableWidget(new Button.Builder(Component.translatable(LANG + "rebind"),
            button -> rebind()).bounds(px + AXIS_LABEL_X, py + REBIND_Y, EDIT_FILTER_W, EDIT_FILTER_H)
            .build());
        // 「编辑过滤」（第 106 轮）：选中一行后点它 = 回机器界面，匹配区 ghost 窗口即该区域的过滤
        // （= 玩家不用再记住「过滤器在方块里面一层层点」；见类注释第 3 条）
        editFilterButton = addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "filter.edit"),
            button -> editSelectedFilter())
            .bounds(px + AXIS_LABEL_X, py + EDIT_FILTER_Y, EDIT_FILTER_W, EDIT_FILTER_H)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable(LANG + "filter.edit.tip")))
            .build());

        scrollbar = new SptScrollbarWidget(px + SCROLL_X, py + LIST_Y,
            SptScrollbarWidget.Type.NORMAL, LIST_H, SCROLL_W);
        scrollbar.setListener(value -> listOffset = (int) Math.round(value));
        refreshButtonLabels();
    }

    private void refreshButtonLabels() {
        if (deleteButton != null) {
            deleteButton.setMessage(Component.translatable(LANG + (isDeleteArmed() ? "delete.confirm" : "delete")));
        }
        if (showAllButton != null) {
            showAllButton.setMessage(Component.translatable(LANG + (showAll ? "show_all.on" : "show_all.off")));
        }
        final AreaSelection selection = currentSelection();
        final boolean hasSelected = selection.selected() != null;
        if (newButton != null) {
            // 没有目标（菜单路径槽空 / 槽里不是选定器）⇒「新建」也不可点：
            // 服务端 resolve 为空会直接丢包，让按钮可点等于纵容「点了没反应」（第 119 轮 C-2）。
            newButton.active = targetStack() != null
                && editableHere() && selection.areaCount() < AreaSelection.maxAreas();
        }
        if (copyButton != null) {
            // 复制要求：有选中、未超个数上限、且当前维度可编辑（复制出来的几何是服务端算的）
            copyButton.active = editableHere() && hasSelected
                && selection.areaCount() < AreaSelection.maxAreas();
        }
        if (deleteButton != null) {
            deleteButton.active = editableHere() && hasSelected;
        }
        if (rebindButton != null) {
            // 只有「选定器绑定的维度 ≠ 当前维度」时才是逃生口（改绑会清空区域，必须显式点）
            rebindButton.visible = selection.dimension() != null && minecraft != null
                && minecraft.level != null
                && !selection.dimension().equals(minecraft.level.dimension().location());
        }
        if (editFilterButton != null) {
            // 第 112 轮：标记存在选定器物品上（support/AreaMarks），因此<b>手持也能编辑</b>
            // —— 用户原话「无法正常的单独给某一个区域标记，就非得把这个标记放在方块中进行吗」。
            editFilterButton.visible = !rebindButtonVisible(selection);
            editFilterButton.active = hasSelected && editableHere();
            editFilterButton.setMessage(Component.translatable(LANG
                + (menu != null ? "filter.edit" : "filter.edit.hand")));
        }
        if (nameBox != null) {
            nameBox.active = hasSelected && editableHere();
        }
        if (nameApplyButton != null) {
            nameApplyButton.active = hasSelected && editableHere();
        }
    }

    private boolean rebindButtonVisible(final AreaSelection selection) {
        return selection.dimension() != null && minecraft != null && minecraft.level != null
            && !selection.dimension().equals(minecraft.level.dimension().location());
    }

    /** 选中区域在「可用区域」里的位次（服务端过滤上下文用的就是它）；{@code -1} = 不在可用列表里。 */
    private int usableIndexOfSelected() {
        final AreaSelection selection = currentSelection();
        final int raw = selection.selectedIndex();
        final int row = displayRaw.indexOf(raw);
        return row < 0 ? -1 : displayUsable.get(row);
    }

    // ==================== 编辑 ====================

    /** 任何数字框变化 → 250ms 后统一提交（避免每敲一个字符发一次包）。 */
    private void scheduleApply() {
        if (syncingFields) {
            return; // 程序化回填（打开界面 / 服务端同步）不是玩家编辑
        }
        if (!editableHere()) {
            pendingApply = null;
            errorKey = LANG + "err.dimension";
            errorField = -1;
            return;
        }
        final int[] failed = new int[1];
        pendingApply = readAreaFromFields(failed);
        // 第 124 轮：连「这 6 个数字属于哪一行」一起记下来（切行后不许写到新行上）。
        pendingApplyIndex = currentSelection().selectedIndex();
        applyAtMs = System.currentTimeMillis() + APPLY_DEBOUNCE_MS;
        if (pendingApply == null) {
            // 「哪个框错了」要说清楚：把出错的那个框描红（errorField），提示就画在字段旁边
            errorKey = LANG + "err.number";
            errorField = failed[0];
        } else {
            errorKey = null;
            errorField = -1;
        }
    }

    /**
     * 读六个数字框。
     *
     * @param failed 输出参数：出错的框下标（{@code -1} = 没有具体框，例如全部为空）
     */
    @Nullable
    private Area readAreaFromFields(final int[] failed) {
        failed[0] = -1;
        final int[] values = new int[6];
        for (int i = 0; i < 6; i++) {
            final RsccNumberField.Parsed parsed = RsccNumberField.parse(boxes[i].getValue());
            if (!parsed.ok() || parsed.value() > Area.WORLD_LIMIT || parsed.value() < -Area.WORLD_LIMIT) {
                failed[0] = i;
                return null;
            }
            values[i] = (int) parsed.value();
        }
        return new Area(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /** 名称框变化 → 500ms 后提交（避免逐字发包）。 */
    private void scheduleRename() {
        if (syncingFields || nameBox == null) {
            return;
        }
        if (!editableHere()) {
            return;
        }
        final AreaSelection selection = currentSelection();
        if (selection.selected() == null) {
            return; // 没选中任何行：名称框只是展示位
        }
        pendingRename = nameBox.getValue();
        // 第 124 轮：打字当场捕获「这串文字属于哪一行」，而不是等到提交时再读当前选中行。
        pendingRenameIndex = nameBoxIndex >= 0 ? nameBoxIndex : selection.selectedIndex();
        renamePending = true;
        renameAtMs = System.currentTimeMillis() + RENAME_DEBOUNCE_MS;
    }

    /** 防抖到期后真正提交几何修改（在 render 里驱动；不依赖 tick，界面暂停时也照常工作）。 */
    private void flushApply() {
        if (pendingApply == null || System.currentTimeMillis() < applyAtMs) {
            return;
        }
        final Area area = pendingApply;
        final int capturedIndex = pendingApplyIndex;
        pendingApply = null;
        pendingApplyIndex = -1;
        if (!editableHere()) {
            errorKey = LANG + "err.dimension"; // 服务端只会拒收：不给「看起来改了」的假象
            errorField = -1;
            return;
        }
        final AreaSelection selection = currentSelection();
        // 第 124 轮：提交对象 = 打字时捕获的那一行；只有捕获值未知时才退回「当前选中行」。
        final int index = capturedIndex >= 0 ? capturedIndex : selection.selectedIndex();
        if (index < 0) {
            return;
        }
        if (index >= selection.areaCount()) {
            // 那一行已经被删掉了：丢弃这次坐标（绝不改投到别的行）并说出来
            errorKey = LANG + "err.reject.no_such_area";
            errorField = -1;
            return;
        }
        final String invalid = area.invalidReason(AreaSelection.maxVolume());
        if (invalid != null) {
            errorKey = switch (invalid) {
                case "side_too_long" -> LANG + "err.side";
                case "volume_too_large" -> LANG + "err.volume";
                default -> LANG + "err.world";
            };
            errorField = -1;
            return;
        }
        // 去重：改成「已有另一块地」的几何 → 不发包，提示就画在字段旁边（用户第 2 条第一句）
        if (selection.hasGeometry(area, index)) {
            errorKey = LANG + "err.duplicate";
            errorField = -1;
            return;
        }
        errorKey = null;
        errorField = -1;
        // 乐观显示：先改客户端副本（服务端同步回来时会覆盖成权威值）。
        // **不 sanitize**：sanitize 会按当前 Config 截断，本地副本先被截掉会让玩家以为区域被删了
        // （服务端其实原样保留，见 AreaSelectorEditPacket 类注释）。真正的「不参与扫描」由机器侧判定。
        // 第 124 轮：名字取「被替换这一行自己的名字」（以前取 selected() 的名字 —— 切行后 index 与
        // selectedIndex 可能不同，会把别的区域的名字搬过来）；服务端 ACTION_REPLACE 同样保留旧名字。
        final Area replaced = selection.areas().get(index);
        final ItemStack stack = targetStack();
        if (stack != null) {
            AreaSelection.set(stack, selection.replace(index, area.withName(replaced.name())));
            lastFieldKey = fieldKey(AreaSelection.get(stack));
        }
        send(AreaSelectorEditPacket.ACTION_REPLACE, index, area);
    }

    /**
     * 防抖到期后真正提交重命名。
     *
     * <h2>第 124 轮：提交对象 = 「打字时捕获的那一行」</h2>
     * <p>旧实现这里有三个叠在一起的问题（用户 2-1 / 2-2）：</p>
     * <ol>
     *     <li>提交下标现读 {@code selection.selectedIndex()}：玩家点下一行之后，<b>上一行</b>打的字
     *     被写到<b>刚选中的那一行</b>（2-2「区域二直接变成鼓风机」）；</li>
     *     <li>提交后不清「有没有待提交」的状态（只留一个 {@code renameAtMs} 时间戳，且提交后不复位）：
     *     {@code render()} 每帧都会进来重新评估一次，于是上面那条错位<b>每帧都在发生</b>；</li>
     *     <li>字段初值是 {@code pendingRename = ""} / {@code renameAtMs = 0}：<b>打开界面后第一帧</b>就满足
     *     「有话说 = 空串 ≠ 当前名字」，于是直接发一个「把选中区域改名成空串」的包
     *     （2-1「点确定了、看着改了，重开还是没改」的另一条成因 —— 名字是被「再打开一次」抹掉的）。</li>
     * </ol>
     * <p>现在：只有 {@link #scheduleRename()} / {@link #applyNameNow()} 真的排期过才会提交；提交对象是
     * {@link #pendingRenameIndex}；提交过一次之后不管发没发出去都<b>状态归零</b>，不会每帧重投。</p>
     */
    private void flushRename() {
        if (nameBox == null || !renamePending || System.currentTimeMillis() < renameAtMs) {
            return;
        }
        final int index = pendingRenameIndex;
        // 先归零再判：这条 pending 只可能被提交一次（旧实现每帧重投是 2-2 的放大器）
        renamePending = false;
        pendingRenameIndex = -1;
        renameAtMs = Long.MAX_VALUE;
        if (index < 0) {
            return; // 连「属于哪一行」都没捕获到：宁可丢弃，也绝不改投到别的行
        }
        final AreaSelection selection = currentSelection();
        if (index >= selection.areaCount()) {
            // 那一行已经被删掉了：丢弃（不改投）并让玩家看见
            errorKey = LANG + "err.reject.no_such_area";
            errorField = -1;
            return;
        }
        final Area selected = selection.areas().get(index);
        final String wanted = pendingRename == null ? "" : pendingRename;
        final String current = selected.name() == null ? "" : selected.name();
        if (wanted.equals(current)) {
            return;
        }
        final ItemStack stack = targetStack();
        if (stack != null) {
            final AreaSelection next = selection.rename(index, wanted);
            AreaSelection.set(stack, next);
            // 乐观写之后同步「字段缓存 key」：否则同一帧的 syncFields 会把名称框打回旧值
            lastFieldKey = fieldKey(next);
        }
        send(AreaSelectorEditPacket.ACTION_RENAME, index, selected, wanted);
    }

    /**
     * 丢弃「还没提交的名字」（第 124 轮）：选中行变了以后，{@link #pendingRenameIndex} 指向的行已经不是
     * 名称框代表的行，绝不能再提交（不管提交给新行还是旧行都可能违背玩家意图 —— 旧行的那次提交在切行
     * 之前已经由 {@link #applyNameNow()} 做过了）。
     */
    private void discardRename() {
        renamePending = false;
        pendingRenameIndex = -1;
        pendingRename = "";
        renameAtMs = Long.MAX_VALUE;
    }

    /**
     * <b>立即</b>提交名称（第 110 轮）：供「确定」按钮 / 回车键 / 关界面 / 切换选中行四个入口共用。
     *
     * <h2>为什么必须有一个「不等防抖」的入口</h2>
     * <p>旧实现只有 {@link #scheduleRename()} 的 500ms 防抖。玩家的真实操作是「改名字 → 回车 → ESC/关界面」，
     * 而原版 {@code EditBox} <b>不处理回车</b>、{@code removed()} 也不 flush —— 只要关界面比防抖快，
     * 重命名包就根本没发出去；界面上的列表却已经被客户端乐观副本改成新名字，于是
     * 「显示改了、再进来还是旧的」。用户原话：「并没有一个按钮让我点一下确定，回车键它显示改了但是
     * 再次进入还是没有改」。这里把防抖时间清零后直接走 {@link #flushRename()}，发包与乐观显示同源。</p>
     */
    private void applyNameNow() {
        if (nameBox == null) {
            return;
        }
        final AreaSelection selection = currentSelection();
        if (selection.selected() == null || !editableHere()) {
            return;
        }
        pendingRename = nameBox.getValue();
        // 第 124 轮：名称框里这段文字属于**它上一步显示的那一行**（{@code nameBoxIndex}），
        // 不是「此刻选中的那一行」—— 切行时这两个值正好不同，旧实现在这里写错了行（用户 2-2）。
        pendingRenameIndex = nameBoxIndex >= 0 ? nameBoxIndex : selection.selectedIndex();
        renamePending = true;
        renameAtMs = 0L;
        flushRename();
    }

    /** 回车 / 小键盘回车 = 立即提交名称（原版 EditBox 不会自己处理回车）。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (nameBox != null && nameBox.isFocused()
            && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            applyNameNow();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void createArea() {
        // 第 124 轮：新建会让服务端把「选中项」换成新区域 ⇒ 先把名称框里还没提交的名字交给**旧**行
        // （与 selectRow 切行同口径；否则这一行的名字会在选中项跳走后滞留成悬空 pending）。
        applyNameNow();
        // 没有目标（菜单路径槽空 / 主手不是选定器）：界面上按钮已置灰、底部也有「请把区域选定器
        // 放进专用槽」提示；这里再兜一层，绝不给服务端发一个注定被 resolve 丢掉的包（C-2）。
        if (targetStack() == null) {
            return;
        }
        if (!editableHere()) {
            errorKey = LANG + "err.dimension";
            errorField = -1;
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        BlockPos seed = null;
        if (minecraft.player != null) {
            if (minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.BLOCK) {
                seed = ((BlockHitResult) minecraft.hitResult).getBlockPos();
            } else {
                seed = minecraft.player.blockPosition();
            }
        }
        if (seed == null) {
            return;
        }
        final AreaSelection selection = currentSelection();
        if (selection.areaCount() >= AreaSelection.maxAreas()) {
            errorKey = LANG + "err.limit";
            errorField = -1;
            return;
        }
        final Area candidate = Area.single(seed);
        // 去重（第 96 轮第一件事）：同维度 + 同坐标 ⇒ 直接拒绝并提示，绝不叠一份完全一样的区域
        if (selection.hasGeometry(candidate, -1)) {
            errorKey = LANG + "err.duplicate";
            errorField = -1;
            return;
        }
        errorKey = null;
        errorField = -1;
        send(AreaSelectorEditPacket.ACTION_ADD, -1, candidate);
    }

    /** 复制选中区域：服务端算「同尺寸 + 沿 +X 平移直到不撞」的新几何（客户端做一次预演提示）。 */
    private void copySelected() {
        // 第 124 轮：复制同样会让服务端把选中项换成新区域 ⇒ 先把旧行的名字提交掉
        applyNameNow();
        if (!editableHere()) {
            errorKey = LANG + "err.dimension";
            errorField = -1;
            return;
        }
        final AreaSelection selection = currentSelection();
        final int index = selection.selectedIndex();
        if (index < 0) {
            errorKey = LANG + "err.limit";
            return;
        }
        if (selection.duplicate(index) == null) {
            errorKey = LANG + "err.copy";
            errorField = -1;
            return;
        }
        errorKey = null;
        errorField = -1;
        send(AreaSelectorEditPacket.ACTION_COPY, index, selection.selected());
    }

    private void deleteSelected() {
        // 第 124 轮：删除会让服务端把选中项顺移 ⇒ 先把名称框里还没提交的名字交给**旧**行
        applyNameNow();
        if (!editableHere()) {
            errorKey = LANG + "err.dimension";
            errorField = -1;
            return;
        }
        final AreaSelection selection = currentSelection();
        final int index = selection.selectedIndex();
        if (index < 0) {
            return;
        }
        if (!isDeleteArmed()) {
            deleteArmedAt = System.currentTimeMillis();
            refreshButtonLabels();
            return;
        }
        deleteArmedAt = Long.MIN_VALUE;
        send(AreaSelectorEditPacket.ACTION_DELETE, index, AreaSelectorEditPacket.PLACEHOLDER);
        refreshButtonLabels();
    }

    private boolean isDeleteArmed() {
        return deleteArmedAt != Long.MIN_VALUE
            && System.currentTimeMillis() - deleteArmedAt <= DELETE_CONFIRM_MS;
    }

    private void rebind() {
        send(AreaSelectorEditPacket.ACTION_REBIND, -1, AreaSelectorEditPacket.PLACEHOLDER);
    }

    /**
     * 「编辑过滤」：<b>直接打开该区域的标记界面</b>（{@link AreaMarkScreen}，第 112 轮）。
     *
     * <h2>为什么不再「回机器界面 + 切 48 格窗口」</h2>
     * <p>第 106 轮的做法是把过滤上下文交给机器主界面，然后退回主界面 —— 玩家仍然要<b>先有机器</b>、
     * 先在机器界面里，切换区域还得靠 {@code < >}。用户第 110 轮原话：「无法正常的单独给某一个区域标记，
     * 就非得把这个标记放在方块中进行吗这样子感觉很麻烦，切换的时候更麻烦」。第 112 轮把每区域标记
     * 搬到选定器物品上后，这里就变成：<b>点哪一行就配哪一行，新界面里左边是这个区域已有的标记、
     * 右边是自己的背包，点一下就加 / 删</b>，与机器无关。</p>
     *
     * <p>机器里那 48 格 ghost 窗口仍然保留（数量门槛 / NBT / 标签细调在那里），两边读写的是
     * <b>同一份数据</b>（选定器物品上的 {@code AreaMarks}），不会出现「两个界面说法不同」。</p>
     */
    private void editSelectedFilter() {
        final AreaSelection selection = currentSelection();
        final Area area = selection.selected();
        if (area == null) {
            errorKey = LANG + "err.limit";
            return;
        }
        if (!editableHere()) {
            errorKey = LANG + "err.dimension";
            errorField = -1;
            return;
        }
        errorKey = null;
        errorField = -1;
        // 先选中该行（服务端 ACTION_SELECT）：区域列表与机器状态保持一致
        final int raw = selection.selectedIndex();
        if (raw >= 0) {
            send(AreaSelectorEditPacket.ACTION_SELECT, raw, AreaSelectorEditPacket.PLACEHOLDER);
            final int usable = usableIndexOfSelected();
            if (menu != null && usable >= 0) {
                // 机器界面里的 < > 上下文也跟过去（从机器打开时两处状态不再打架）
                sendFilterContext(usable);
            }
        }
        if (minecraft != null) {
            minecraft.setScreen(new AreaMarkScreen(this, containerId(), area));
        }
    }

    /**
     * 当前目标选定器物品（{@code EMPTY} = 没有目标；{@link #targetStack()} 的非 null 版本）。
     * <p>与 {@link #targetStack()} 同源：菜单路径不回退主手。这里<b>不再</b>自己写一份
     * 「菜单槽优先、否则读主手」的逻辑 —— 两份判据一旦分叉，就会出现「列表用主手数据、
     * 写包用菜单 id」的静默失效（第 119 轮 C-2）。</p>
     */
    private ItemStack selectionStack() {
        final ItemStack stack = targetStack();
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** C2S：切换机器的「正在编辑过滤器的区域」（服务端校验菜单 / 距离 / 越界）。 */
    private void sendFilterContext(final int usableIndex) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new SetAreaFilterContextPacket(containerId(), usableIndex));
        } catch (RuntimeException error) {
            // 防御性降级（同高级界面）：包未注册时只记一条 WARN，绝不让一次点击把客户端崩掉
            com.mojang.logging.LogUtils.getLogger().warn(
                "[rs_create_compat] 区域过滤切换包未注册（主类缺 SetAreaFilterContextPacket 注册）⇒ "
                    + "本次点击忽略。", error);
        }
    }

    private void send(final int action, final int index, final Area area) {
        send(action, index, area, "");
    }

    private void send(final int action, final int index, final Area area, final String name) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new AreaSelectorEditPacket(containerId(), action, index, area, name));
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        flushApply();
        flushRename();
        final AreaSelection selection = currentSelection();
        // 顺序很重要：先按服务端快照重建行（displayRaw / displayUsable），再刷新按钮可用性
        // （「编辑过滤」按钮要知道当前行在可用区域里的位次），最后回填输入框。
        rebuildRows(selection);
        syncFields(selection);
        refreshButtonLabels();
        renderWorldOutlines(selection);
        handleServerReject();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        final List<Area> areas = displayAreas;
        layoutRows(areas);
        drawTexts(guiGraphics, selection, areas);
        renderScrollbar(guiGraphics, mouseX, mouseY, partialTick, areas);
        renderRowTooltip(guiGraphics, selection, areas, mouseX, mouseY);
        if (rowAt(mouseX, mouseY) < 0) {
            renderInfoTooltip(guiGraphics, selection, mouseX, mouseY);
        }
    }

    /**
     * 重建本帧的显示行：有服务端快照就用它（<b>只列可用区域</b>），否则退回物品自身列表。
     * <p>这就是「列表里只出现真实存在的区域」的落点：快照由服务端算（见
     * {@code AdvancedCollectionCacheBlockEntity#buildAreaSnapshot}），客户端<b>不做任何取舍</b>。</p>
     */
    private void rebuildRows(final AreaSelection selection) {
        displayAreas.clear();
        displayRaw.clear();
        displayUsable.clear();
        displayFilters.clear();
        final AdvancedAreaSnapshotPacket snapshot = snapshot();
        if (snapshot != null) {
            for (int i = 0; i < snapshot.entries().size(); i++) {
                final AdvancedAreaSnapshotPacket.AreaEntry entry = snapshot.entries().get(i);
                if (entry == null || entry.area() == null) {
                    continue;
                }
                displayAreas.add(entry.area());
                displayRaw.add(entry.rawIndex());
                displayUsable.add(i);
                displayFilters.add(entry.filters() == null ? List.of() : entry.filters());
            }
            return;
        }
        final List<Area> local = selection.areas();
        for (int i = 0; i < local.size(); i++) {
            final Area area = local.get(i);
            if (area == null) {
                continue;
            }
            displayAreas.add(area);
            displayRaw.add(i);
            // 主手路径没有机器上下文：可用位次未知（-1 = 机器侧没有过滤上下文）
            displayUsable.add(-1);
            // 第 112 轮：每区域标记存在<b>选定器物品</b>上（support/AreaMarks），所以主手路径
            // 同样能显示 / 编辑每区域标记 —— 这正是「不必把标记放进方块」的落点。
            displayFilters.add(AreaMarks.get(selectionStack(), area));
        }
    }

    /**
     * 服务端拒绝原因 → 一句话（第 106 轮，用户第 5 条）。
     * <p>只在拒绝序号变大时提示一次（不刷屏）；列表本身每帧都从最新快照重建，
     * 因此「自动刷新列表」是自然结果，不需要额外发刷新请求。</p>
     */
    private void handleServerReject() {
        final AdvancedAreaSnapshotPacket snapshot = snapshot();
        if (snapshot == null || snapshot.rejectSeq() <= lastRejectSeq) {
            return;
        }
        lastRejectSeq = snapshot.rejectSeq();
        errorKey = switch (snapshot.rejectCode()) {
            case AreaSelectorEditPacket.REJECT_NO_SUCH_AREA -> LANG + "err.reject.no_such_area";
            case AreaSelectorEditPacket.REJECT_DIMENSION -> LANG + "err.dimension";
            case AreaSelectorEditPacket.REJECT_LIMIT -> LANG + "err.limit";
            case AreaSelectorEditPacket.REJECT_DUPLICATE -> LANG + "err.duplicate";
            case AreaSelectorEditPacket.REJECT_INVALID_AREA -> LANG + "err.reject.invalid";
            default -> null;
        };
        errorField = -1;
        // 服务端拒了 ⇒ 本地乐观显示可能与服务端不一致，下一次服务端同步会覆盖回来；
        // 这里清掉「字段缓存 key」强制下一帧重新回填，避免输入框还停在被拒的值上。
        lastFieldKey = "";
        // 第 124 轮：名称框如果在编辑态，focused 早退会让它继续显示「被拒的那个名字」——
        // 退出编辑态，下一帧 syncFields 才会用服务端权威值回填（原因已经由 errorKey 说明了）。
        if (nameBox != null && nameBox.isFocused()) {
            nameBox.setFocused(false);
        }
    }

    /**
     * 世界描边：选中区域常亮（= 单显）；「显示全部」时全部常亮（= 全显），统一橙、同一套面贴图。
     * <p><b>去重</b>：用 {@link AreaSelection#distinctAreas()} 而不是原始列表 —— 旧存档里可能存在
     * 完全重复的区域（第 96 轮之前没有拦截），同一块地喂两个 Outliner slot 会叠画两份
     * （看起来就是框变粗 / 闪烁）。</p>
     */
    private void renderWorldOutlines(final AreaSelection selection) {
        final Area selected = selection.selected();
        if (selected != null) {
            AreaOutline.chase(AreaOutline.slot("screen/selected"), selected);
        } else {
            AreaOutline.remove(AreaOutline.slot("screen/selected"));
        }
        final List<Area> distinct = snapshot() != null ? List.copyOf(displayAreas)
            : selection.distinctAreas();
        final int count = showAll ? Math.min(distinct.size(), AreaSelection.ABSOLUTE_MAX_AREAS) : 0;
        for (int i = 0; i < count; i++) {
            AreaOutline.chase(AreaOutline.slot("screen/all/" + i), distinct.get(i));
        }
        for (int i = count; i < shownAll; i++) {
            AreaOutline.remove(AreaOutline.slot("screen/all/" + i));
        }
        shownAll = count;
    }

    /**
     * 每一行的行高：先按「索引/名字 + 一行坐标」量宽，放不下就改成 3 行（从 / 到 各一行）。
     * <p>这样<b>终点永远画得出来</b>；只有极端坐标（3e7 级）连单行都超宽时才会缩字号，
     * 而那种情况的下悬停 tooltip 里一定给完整文本。</p>
     */
    private int rowHeightFor(final Area area, final int index) {
        final String one = coordinateLine(area);
        final int icons = rowIconCount(index);
        final int textWidth = LIST_W - 8 - (icons <= 0 ? 0 : ROW_ICON_BAND);
        return font.width(one) <= textWidth ? ROW_2H : ROW_3H;
    }

    /** 该行要画几个过滤图标（没有过滤上下文时为 0）。 */
    private int rowIconCount(final int index) {
        if (index < 0 || index >= displayFilters.size()) {
            return 0;
        }
        int count = 0;
        for (final MarkerEntry entry : displayFilters.get(index)) {
            if (entry != null && !entry.isEmpty()) {
                count++;
            }
        }
        return Math.min(count, ROW_ICON_MAX);
    }

    /** 2 行版的坐标行：「从 a,b,c 到 x,y,z」。 */
    private static String coordinateLine(final Area area) {
        return "从 " + area.describeMin() + "  到 " + area.describeMax();
    }

    /** 按当前偏移与本帧可见高度排出每一行的位置（绘制与命中测试共用同一份结果）。 */
    private void layoutRows(final List<Area> areas) {
        visibleRows = 0;
        int y = LIST_Y;
        for (int row = 0; row < MAX_ROWS; row++) {
            final int index = listOffset + row;
            if (index >= areas.size()) {
                break;
            }
            final int height = rowHeightFor(areas.get(index), index);
            if (y + height > LIST_Y + LIST_H) {
                break;
            }
            rowTop[row] = y;
            rowHeight[row] = height;
            visibleRows = row + 1;
            y += height;
        }
    }

    private void drawTexts(final GuiGraphics guiGraphics, final AreaSelection selection,
                           final List<Area> areas) {
        guiGraphics.drawString(font, title, px + LIST_X, py + TITLE_Y, COLOR_TITLE, false);
        final String pageInfo = areas.isEmpty() ? "0 / 0"
            : (listOffset + 1) + "-" + Math.min(areas.size(), listOffset + Math.max(1, visibleRows))
                + " / " + areas.size();
        guiGraphics.drawString(font, pageInfo, px + LIST_X + LIST_W - font.width(pageInfo),
            py + TITLE_Y, COLOR_DIM, false);

        // 列头（从 / 到）与三行轴标签
        guiGraphics.drawString(font, Component.translatable(LANG + "col.from"),
            px + F1_X, py + HEADER_Y, COLOR_DIM, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "col.to"),
            px + F2_X, py + HEADER_Y, COLOR_DIM, false);
        final String[] axes = {"X", "Y", "Z"};
        for (int row = 0; row < 3; row++) {
            guiGraphics.drawString(font, axes[row], px + AXIS_LABEL_X,
                py + FIELDS_Y + row * FIELD_STEP + 4, COLOR_TEXT, false);
        }
        // 输入框边框（原版 EditBox 自带边框；出错的那个框额外描一圈红，错误提示就画在字段旁）
        if (errorField >= 0 && errorField < boxes.length) {
            final EditBox box = boxes[errorField];
            guiGraphics.fill(box.getX() - 1, box.getY() - 1, box.getX() + box.getWidth() + 1, box.getY(), COLOR_FIELD_BORDER_ERROR);
            guiGraphics.fill(box.getX() - 1, box.getY() + box.getHeight(), box.getX() + box.getWidth() + 1, box.getY() + box.getHeight() + 1, COLOR_FIELD_BORDER_ERROR);
            guiGraphics.fill(box.getX() - 1, box.getY() - 1, box.getX(), box.getY() + box.getHeight() + 1, COLOR_FIELD_BORDER_ERROR);
            guiGraphics.fill(box.getX() + box.getWidth(), box.getY() - 1, box.getX() + box.getWidth() + 1, box.getY() + box.getHeight() + 1, COLOR_FIELD_BORDER_ERROR);
        }

        // 列表行（两行 / 三行两态，坐标绝不被截成「看不出终点」）
        for (int row = 0; row < visibleRows; row++) {
            final int index = listOffset + row;
            if (index >= areas.size()) {
                break;
            }
            final Area area = areas.get(index);
            final int y = py + rowTop[row];
            final boolean selected = index < displayRaw.size()
                && displayRaw.get(index) == selection.selectedIndex();
            if (selected) {
                guiGraphics.fill(px + LIST_X - 2, y - 1, px + LIST_X + LIST_W, y + rowHeight[row] - 2, COLOR_SELECTED);
                guiGraphics.fill(px + LIST_X - 2, y - 1, px + LIST_X - 1, y + rowHeight[row] - 2, COLOR_OUTLINE);
            }
            final int color = selected ? COLOR_TITLE : COLOR_TEXT;
            final String head = (index + 1) + ". "
                + area.displayName(Component.translatable(LANG + "region", rawIndexOfRow(index) + 1).getString());
            // 第 104 轮（去省略号）：列表行原来是 clipToWidth（按像素裁断 + 补「…」）。
            // 现在一律完整显示：放不下就整体缩字号（下限 0.6V），一个字都不省；
            // 完整坐标 / 名字本来就在本行的 hover tooltip（renderRowTooltip）里，极端坐标也不丢信息。
            final int icons = rowIconCount(index);
            final int textWidth = LIST_W - 4 - (icons <= 0 ? 0 : ROW_ICON_BAND);
            RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(head),
                px + LIST_X, y, textWidth, color);
            if (rowHeight[row] == ROW_2H) {
                RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(coordinateLine(area)),
                    px + LIST_X, y + 9, textWidth, COLOR_DIM);
            } else {
                RsccTextFit.drawFitLine(guiGraphics, font,
                    Component.literal("从 " + area.describeMin()),
                    px + LIST_X, y + 9, textWidth, COLOR_DIM);
                RsccTextFit.drawFitLine(guiGraphics, font,
                    Component.literal("到 " + area.describeMax()),
                    px + LIST_X, y + 17, textWidth, COLOR_DIM);
            }
            // 第 106 轮：行右侧直接画该区域的过滤图标摘要（点它 = 编辑该区域过滤）
            if (icons > 0) {
                drawRowFilters(guiGraphics, index, y);
            }
        }

        // 右侧信息列：维度 / 体积 / 尺寸 / 数量（第 104 轮：完整显示，放不下只缩字号不省略）
        final Area selected = selection.selected();
        drawInfoLine(guiGraphics, Component.translatable(LANG + "dimension",
            selection.dimension() == null
                ? Component.translatable(LANG + "unbound")
                : Component.literal(selection.dimension().toString())),
            py + INFO_Y, COLOR_TEXT);
        drawInfoLine(guiGraphics, Component.translatable(LANG + "volume",
            selected == null ? 0L : selected.volume(), selection.totalVolume()),
            py + INFO_Y + INFO_STEP, COLOR_TEXT);
        drawInfoLine(guiGraphics, Component.translatable(LANG + "size",
            selected == null ? "—" : selected.describeSize()),
            py + INFO_Y + INFO_STEP * 2, COLOR_DIM);
        drawInfoLine(guiGraphics, Component.translatable(LANG + "count",
            selection.areaCount(), AreaSelection.maxAreas()),
            py + INFO_Y + INFO_STEP * 3,
            selection.areaCount() > AreaSelection.maxAreas() ? COLOR_ERROR : COLOR_TEXT);

        // 错误提示：就画在六个输入框下面（字段旁边），而不是界面底部。
        // 第 104 轮：折行完整显示（最多 3 行、行距 8px ⇒ 118..141，正好停在信息列之前）。
        if (errorKey != null) {
            drawErrorBlock(guiGraphics, Component.translatable(errorKey).getString(),
                px + AXIS_LABEL_X, py + ERROR_Y, PANEL_W - AXIS_LABEL_X - 12, COLOR_ERROR);
        } else if (selection.areaCount() > AreaSelection.maxAreas()) {
            drawErrorBlock(guiGraphics, Component.translatable(LANG + "hint.over_limit",
                    selection.areaCount() - AreaSelection.maxAreas()).getString(),
                px + AXIS_LABEL_X, py + ERROR_Y, PANEL_W - AXIS_LABEL_X - 12, COLOR_ERROR);
        }
        // 列表底部那一句小字（192..200，正好在按钮行之前）。四种情况互斥，且**都不静默**：
        //   ① 还没收到服务端快照 ⇒ 读取中；② 菜单专用槽里没有选定器 ⇒ 明确告诉玩家「放进哪个槽」
        //   （第 119 轮 C-2：菜单路径不回退主手，槽空时界面就是空的，必须说清为什么）；
        //   ③ 服务端排除了 N 个区域 ⇒ 说个数；④ 列表真的为空 ⇒ 怎么建区域。
        final AdvancedAreaSnapshotPacket snapshot = snapshot();
        if (menu != null && snapshot == null) {
            drawListNote(guiGraphics, Component.translatable(LANG + "status.loading").getString());
        } else if (snapshot != null && snapshot.status() == 0) {
            drawListNote(guiGraphics, Component.translatable(LANG + "status.no_slot").getString());
        } else if (snapshot != null && snapshot.status() == 3) {
            drawListNote(guiGraphics, Component.translatable(LANG + "status.dimension").getString());
        } else if (snapshot != null && snapshot.status() == 2) {
            drawListNote(guiGraphics, Component.translatable(LANG + "status.empty").getString());
        } else if (snapshot != null && snapshot.unusableCount() > 0) {
            drawListNote(guiGraphics,
                Component.translatable(LANG + "unusable", snapshot.unusableCount()).getString());
        } else if (areas.isEmpty()) {
            drawListNote(guiGraphics, Component.translatable(LANG + "hint.empty").getString());
        }
    }

    /** 行编号 → 原始下标（摘要第一行显示「区域 N」时用原始下标，和机器侧的「区域 N/M」同口径）。 */
    private int rawIndexOfRow(final int index) {
        return index >= 0 && index < displayRaw.size() ? displayRaw.get(index) : index;
    }

    /** 行右侧的过滤图标（第 106 轮；点它 = 编辑该区域过滤）。 */
    private void drawRowFilters(final GuiGraphics guiGraphics, final int index, final int rowY) {
        final int count = rowIconCount(index);
        if (count <= 0) {
            return;
        }
        final int startX = px + LIST_X + LIST_W - 4 - count * ROW_ICON_PITCH;
        final int startY = rowY + 2;
        int drawn = 0;
        for (final MarkerEntry entry : displayFilters.get(index)) {
            if (drawn >= ROW_ICON_MAX) {
                break;
            }
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            AdvancedCollectionCacheScreen.drawFilterIcons(guiGraphics,
                startX + drawn * ROW_ICON_PITCH, startY, List.of(entry), 1);
            drawn++;
        }
    }

    /** 列表底部的一行小字（状态 / 排除个数 / 读取中）：完整显示，放不下只缩字号，绝不省略。 */
    private void drawListNote(final GuiGraphics guiGraphics, final String text) {
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(text),
            px + LIST_X, py + LIST_Y + LIST_H + 2, LIST_W - 4, COLOR_DIM);
    }

    /** 右侧信息列的一行：宽度固定（标签列到面板右缘），放不下整体缩字号，绝不省略。 */
    private void drawInfoLine(final GuiGraphics guiGraphics, final Component text, final int y,
                              final int color) {
        RsccTextFit.drawFitLine(guiGraphics, font, text, px + AXIS_LABEL_X, y,
            PANEL_W - AXIS_LABEL_X - 14, color);
    }

    /** 错误 / 超限提示整块：折行完整显示，行数超限时缩字号（≥0.6），绝不静默裁字。 */
    private void drawErrorBlock(final GuiGraphics guiGraphics, final String text, final int x,
                                final int y, final int maxWidth, final int color) {
        RsccTextFit.drawFitBlock(guiGraphics, font, Component.literal(text), x, y, maxWidth,
            ERROR_MAX_LINES, ERROR_LINE_H, color);
    }

    /** 自绘滚动条（工程既有控件；没有可滚内容时置灰 —— 玩家看得出「不是坏了」）。 */
    private void renderScrollbar(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                 final float partialTick, final List<Area> areas) {
        if (scrollbar == null) {
            return;
        }
        final int max = maxListOffset(areas);
        scrollbar.setMaxOffset(max);
        scrollbar.setEnabled(max > 0);
        if (Math.abs(scrollbar.getOffset() - listOffset) > 0.001) {
            scrollbar.setOffset(listOffset);
        }
        scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /** 滚动条允许的最大偏移（保证滚到底时最后一行可见）。 */
    private int maxListOffset(final List<Area> areas) {
        return Math.max(0, areas.size() - MIN_VISIBLE_ROWS);
    }

    /**
     * 行的悬停 tooltip：<b>完整坐标</b>（两角各自的原始文本，不裁剪）+ 名称 + 体积 / 尺寸。
     * <p>这是「坐标被挡住」投诉的最终兜底：即使列表行因为极端坐标被缩字号，
     * 悬停也能看到一个字符都不少的 {@link Area#describe()}。</p>
     */
    private void renderRowTooltip(final GuiGraphics guiGraphics, final AreaSelection selection,
                                  final List<Area> areas, final int mouseX, final int mouseY) {
        final int row = rowAt(mouseX, mouseY);
        if (row < 0) {
            return;
        }
        final int index = listOffset + row;
        if (index < 0 || index >= areas.size()) {
            return;
        }
        final Area area = areas.get(index);
        final List<Component> lines = new ArrayList<>(6);
        lines.add(Component.literal("#" + (rawIndexOfRow(index) + 1) + "  "
            + area.displayName(Component.translatable(LANG + "region",
                rawIndexOfRow(index) + 1).getString())));
        lines.add(Component.literal("从 (" + area.describeMin() + ")"));
        lines.add(Component.literal("到 (" + area.describeMax() + ")"));
        lines.add(Component.translatable(LANG + "size", area.describeSize()));
        lines.add(Component.translatable(LANG + "dimension", selection.dimension() == null
            ? Component.translatable(LANG + "unbound")
            : Component.literal(selection.dimension().toString())));
        // 过滤摘要（第 106 / 107 轮）：物品名一行、完整注册 id 一行 —— 行内只画图标，不印 id
        final List<MarkerEntry> filters = index < displayFilters.size()
            ? displayFilters.get(index) : List.of();
        if (!filters.isEmpty()) {
            lines.add(Component.translatable(LANG + "filter.summary", filters.size()));
            for (final MarkerEntry entry : filters) {
                lines.addAll(AdvancedCollectionCacheScreen.filterIconTooltipLines(entry));
            }
            if (displayUsable.size() > index && displayUsable.get(index) >= 0) {
                lines.add(Component.translatable(LANG + "filter.edit.tip"));
            }
        }
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 右侧信息列（维度 / 体积 / 尺寸 / 数量）的悬停 tooltip：<b>1.0 倍完整原文</b>。
     */
    private void renderInfoTooltip(final GuiGraphics guiGraphics, final AreaSelection selection,
                                   final int mouseX, final int mouseY) {
        if (mouseX < px + AXIS_LABEL_X || mouseX >= px + PANEL_W - 8
            || mouseY < py + INFO_Y || mouseY >= py + INFO_Y + INFO_STEP * 4) {
            return;
        }
        final Area selected = selection.selected();
        final List<Component> lines = new ArrayList<>(4);
        lines.add(Component.translatable(LANG + "dimension", selection.dimension() == null
            ? Component.translatable(LANG + "unbound")
            : Component.literal(selection.dimension().toString())));
        lines.add(Component.translatable(LANG + "volume",
            selected == null ? 0L : selected.volume(), selection.totalVolume()));
        lines.add(Component.translatable(LANG + "size",
            selected == null ? "—" : selected.describeSize()));
        lines.add(Component.translatable(LANG + "count", selection.areaCount(),
            AreaSelection.maxAreas()));
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    private void syncFields(final AreaSelection selection) {
        final Area selected = selection.selected();
        final boolean has = selected != null;
        // 维度不符时输入框灰掉：服务端只会拒收这些编辑（唯一的逃生口是「改绑维度」按钮）。
        final boolean editable = has && editableHere();
        final boolean over = selection.areaCount() > AreaSelection.maxAreas();
        // 超出个数上限时也灰掉（服务端 ADD 会拒；REPLACE 仍允许 —— 玩家得能把超限区域改回合法值）
        for (final EditBox box : boxes) {
            box.active = editable && (over || selection.areaCount() <= AreaSelection.maxAreas());
        }
        if (nameBox != null) {
            nameBox.active = editable;
        }
        if (!has) {
            nameBoxIndex = -1;
            if (!lastFieldKey.equals("empty")) {
                lastFieldKey = "empty";
                syncingFields = true;
                try {
                    for (final EditBox box : boxes) {
                        box.setValue("");
                    }
                    if (nameBox != null) {
                        nameBox.setValue("");
                    }
                } finally {
                    syncingFields = false;
                }
            }
            discardRename(); // 没有选中行 ⇒ 没有任何名字可提交
            return;
        }
        // 第 124 轮：**选中行变了就必须刷新**，哪怕输入框正拿着焦点。
        // 旧实现在这里对「任何输入框有焦点」一律 return ⇒ 玩家点下一行时名称框还显示上一行的名字，
        // 而这时 pendingRename 也还是上一行的字符串 ⇒ 两帧后旧文字被写进新行（用户 2-2）。
        // 选择变了以后：
        //   ① 先丢掉属于旧行的 pending（旧行的那份在 selectRow 里已经提交过了）；
        //   ② 强制用新行的值重填全部输入框（含名称框）；
        //   ③ **保持焦点**：玩家的动作是「点一行、接着改这一行」，如果这里 unfocus，
        //      他还得再用鼠标点一次名称框；重填只会重置光标位置，不会丢任何已提交/待提交的数据。
        final int selectedIndex = selection.selectedIndex();
        final boolean indexChanged = selectedIndex != nameBoxIndex;
        if (indexChanged) {
            // 只丢「名字」的 pending（旧行那份已在 selectRow 里提交过）。
            // **坐标的 pending 不丢**：flushApply 用的是打字时捕获的 pendingApplyIndex，
            // 下一帧它照样会把这次坐标提交给**旧行**（玩家确实改的是那一行），不会写到新行上。
            discardRename();
        }
        // 名称框里是玩家还没提交的字（打字后 500ms 防抖内）：绝不覆盖，否则他刚打的字会被服务端旧值顶掉。
        final boolean typingName = renamePending;
        boolean focused = nameBox != null && nameBox.isFocused();
        for (final EditBox box : boxes) {
            focused |= box.isFocused();
        }
        if (focused && !indexChanged) {
            return; // 同一行、正在打字：不覆盖，避免打断玩家的光标
        }
        final String key = fieldKey(selection);
        if (key.equals(lastFieldKey) && !indexChanged) {
            return;
        }
        lastFieldKey = key;
        nameBoxIndex = selectedIndex;
        syncingFields = true;
        try {
            boxes[0].setValue(Integer.toString(selected.minX()));
            boxes[1].setValue(Integer.toString(selected.minY()));
            boxes[2].setValue(Integer.toString(selected.minZ()));
            boxes[3].setValue(Integer.toString(selected.maxX()));
            boxes[4].setValue(Integer.toString(selected.maxY()));
            boxes[5].setValue(Integer.toString(selected.maxZ()));
            if (nameBox != null && (indexChanged || !typingName)) {
                nameBox.setValue(selected.name() == null ? "" : selected.name());
                // 名称框回到「什么都没在打」的状态：pendingRename 跟着它，保持"没有待提交"
                if (!typingName) {
                    pendingRename = nameBox.getValue();
                }
            }
        } finally {
            syncingFields = false;
        }
    }

    private static String fieldKey(final AreaSelection selection) {
        final Area area = selection.selected();
        return area == null ? "empty"
            : selection.selectedIndex() + ":" + area.describe() + ":" + area.name();
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (scrollbar != null && scrollbar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            final int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                final int index = listOffset + row;
                if (index >= 0 && index < displayRaw.size()) {
                    // 点「行右侧的过滤图标区」= 编辑该区域过滤；点行其余部分 = 选中（并单显该区域）
                    if (rowIconCount(index) > 0 && mouseX >= filterZoneStartX(index)) {
                        selectRow(index, false);
                        editSelectedFilter();
                        return true;
                    }
                    selectRow(index, true);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 选中某一行（第 106 轮：<b>用原始下标发 SELECT</b>，并把过滤上下文一起切过去）。
     *
     * @param announce 是否需要把「为什么不能编辑」的原因写出来（点图标区时由 editSelectedFilter 负责）
     */
    private void selectRow(final int index, final boolean announce) {
        if (index < 0 || index >= displayRaw.size()) {
            return;
        }
        // 第 124 轮（用户 2-2）三层兜底：
        //  ① applyNameNow() 提交给 nameBoxIndex（= 名称框上一帧显示的那一行 = 旧行），不再用即时 selectedIndex；
        //  ② 提交后**重新读**物品上的选中项 —— applyNameNow 里有乐观改名，拿切换前那份 selection 去
        //     select(raw) 会把刚提交的名字又从客户端副本里抹回去；
        //  ③ 末尾 lastFieldKey="" + syncFields 的「选中行变了必须刷新」⇒ 名称框下一帧就是新行的名字，
        //     旧文字不再有机会被提交（旧实现的 focused 早退让旧文字一直留在框里）。
        AreaSelection selection = currentSelection();
        final int raw = displayRaw.get(index);
        // 换了行 ⇒ 先把上一行还没提交的名字 flush 掉（否则切换会把它吞掉）
        if (selection.selectedIndex() != raw) {
            applyNameNow();
            selection = currentSelection();
        }
        if (!editableHere() && announce) {
            // 维度不符时也允许「选中」（只是不能改），但要说清楚为什么改不了
            errorKey = LANG + "err.dimension";
            errorField = -1;
        }
        final ItemStack stack = targetStack();
        if (stack != null) {
            AreaSelection.set(stack, selection.select(raw));
        }
        send(AreaSelectorEditPacket.ACTION_SELECT, raw, AreaSelectorEditPacket.PLACEHOLDER);
        // 过滤上下文跟着走：选中哪一行，机器的 ghost 窗口就编辑哪个区域的过滤（服务端会再校验）
        final int usable = index < displayUsable.size() ? displayUsable.get(index) : -1;
        if (menu != null && usable >= 0) {
            sendFilterContext(usable);
        }
        deleteArmedAt = Long.MIN_VALUE;
        // 选中变化 ⇒ 丢掉属于旧行的 pending，并立刻把该行的值回填到输入框（不必等下一帧 key 比较）
        if (nameBoxIndex != raw) {
            discardRename();
        }
        lastFieldKey = "";
    }

    /** 某行过滤图标区的左边界（Menu 坐标；命中测试与绘制同源）。 */
    private int filterZoneStartX(final int index) {
        final int count = rowIconCount(index);
        return px + LIST_X + LIST_W - 4 - count * ROW_ICON_PITCH;
    }

    /** 光标所在的列表行（{@code -1} = 不在任何一行上）；与 {@link #layoutRows(List)} 同源。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (mouseX < px + LIST_X || mouseX > px + LIST_X + LIST_W) {
            return -1;
        }
        for (int row = 0; row < visibleRows; row++) {
            final int top = py + rowTop[row];
            final int bottom = top + rowHeight[row] - 2;
            if (mouseY >= top - 1 && mouseY <= bottom) {
                return row;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        if (scrollbar != null && scrollbar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (scrollbar != null && scrollbar.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        // 鼠标在列表（或滚动条）上：滚轮滚列表
        if (mouseX >= px + LIST_X && mouseX <= px + LIST_X + LIST_W + SCROLL_W + 2
            && mouseY >= py + LIST_Y && mouseY <= py + LIST_Y + LIST_H) {
            final int max = maxListOffset(displayAreas);
            listOffset = Math.max(0, Math.min(max, listOffset + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 关界面：<b>不立刻删框</b>，按 5 秒重新武装一次（第 106 轮修「显示区域没生效」）。
     * <p>玩家的真实操作是「选中 / 显示全部 → 看一眼 → 关界面」。旧实现在这一刻直接
     * {@code AreaOutline.remove}，于是关掉 GUI 后世界干干净净 —— 看起来就是「显示根本没生效」。</p>
     */
    @Override
    public void removed() {
        // 关界面前强制提交名称：否则「改名字 → 立刻关界面」会丢掉重命名包（用户第 110 条实机反馈）
        applyNameNow();
        final AreaSelection selection = currentSelection();
        final Area selected = selection.selected();
        if (selected != null) {
            AreaOutline.showForView(AreaOutline.slot("screen/selected"), selected, CLOSE_OUTLINE_TTL);
        }
        final List<Area> distinct = snapshot() != null ? List.copyOf(displayAreas)
            : selection.distinctAreas();
        if (showAll) {
            final int count = Math.min(distinct.size(), AreaSelection.ABSOLUTE_MAX_AREAS);
            for (int i = 0; i < count; i++) {
                AreaOutline.showForView(AreaOutline.slot("screen/all/" + i), distinct.get(i),
                    CLOSE_OUTLINE_TTL);
            }
        }
        shownAll = 0;
        super.removed();
    }
}
