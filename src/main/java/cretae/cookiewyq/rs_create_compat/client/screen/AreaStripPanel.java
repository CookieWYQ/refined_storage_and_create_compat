package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.AreaOutline;
import cretae.cookiewyq.rs_create_compat.client.AreaStripBounds;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket;
import cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket;
import cretae.cookiewyq.rs_create_compat.network.SetAreaFilterContextPacket;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「区域条」：<b>方块 GUI 右边的常驻侧栏</b>（第 125 轮，取代旧的独立全屏区域列表界面）。
 *
 * <h2>用户原话（为什么要有这个类）</h2>
 * <p>「那个标记……不要再用现在这个界面。这个界面首先丑，其次没有 JEI，然后不符合我们现在模组的设计风格。
 * 就是把这一个工具放进这个方块里面之后，可以直接在旁边出现一条这个什么选择的，然后直接在里面选择那个，
 * 然后直接进行编辑。之前那种箭头的实在是不直观，也不能明显地标注出当前是哪一个在干活。
 * 还要考虑显示区域的东西应该放在哪里 —— JEI 占据屏幕左右两侧，乱放会跟 JEI 重叠。」</p>
 * <p>⇒ 本类 = 那一「条」：行列表 + 就地改名 / 改坐标 / 配标记，<b>不弹任何新界面</b>；
 * 当前在干活的那个区域用<b>四条同源证据</b>同时标出：左侧 3px 亮橙竖条 + 行底淡橙底 + 行首 ▶ +
 * 标题「当前：xxx」+ 世界里常驻的橙色框（{@link AreaOutline#chase}，slot 前缀 {@code strip/focus}）。
 * JEI 避让不在这里做 —— 本类只把矩形写进 {@link AreaStripBounds}，由 JEI 自己挪（契约 §3.3）。</p>
 *
 * <h2>为什么是「自绘组件」而不是 {@code AbstractWidget}</h2>
 * <p>与 {@link SptScrollbarWidget} 同风格：本类<b>不继承</b> {@code AbstractWidget}，也不进
 * {@code children} 列表；{@code render / mouseClicked / mouseScrolled / keyPressed} 由
 * {@code AdvancedCollectionCacheScreen} 显式转发（契约 §3.2 的「六处转发」）。
 * 这样做的好处：侧栏<b>每帧</b>按 GUI 的 {@code leftPos/topPos} 重新摆放（窗口一缩一放、GUI 一居中，
 * 侧栏跟着走），不必维护一堆会漂移的子控件坐标；坏处是输入必须自己转发 —— 这正是契约把六处转发
 * 列成「缺一条都算没交付」的原因。</p>
 *
 * <h2>第 124 轮「改名两连」的三个缺陷（本类必须一个都不复现）</h2>
 * <ol>
 *     <li><b>打字时捕获行号</b>：{@link #beginRename(int)} 当场把「这串文字属于哪个 rawIndex」
 *     存进 {@link #pendingRenameIndex}；提交时<b>只认这个捕获值</b>，绝不再现读「当前选中行」
 *     （否则玩家点下一行时，上一行打的字会被写进新行 —— 用户 2-2）；</li>
 *     <li><b>提交后立刻复位</b>：{@link #finishRename(boolean)} 先把待提交状态整组清空再发包，
 *     不存在「每帧重投」的时间戳窗口（那是 2-2 的放大器）；</li>
 *     <li><b>打开界面不许发空名字包</b>：名称框只在玩家<b>主动</b>点「改名」/双击时才创建，
 *     创建时的 {@code setValue} 被 {@link #syncingName} 挡住，不会触发 responder ⇒
 *     「一进界面就把名字抹掉」（用户 2-1 的一条真实成因）在结构上不可能发生。</li>
 * </ol>
 *
 * <h2>第 130 轮：标记网格整页删除，区域过滤改在「匹配区」里编辑</h2>
 * <p>用户原话：「我选择某一个区域，我在匹配器中匹配就应该是可以直接拖进去，而不是说在这一个界面中
 * 单独出现一个标记什么的东西，把那一部分删除」。因此本类<b>不再</b>有任何 ghost 标记格子 /
 * 清空 / 返回那一层子页面（那 12 格的读写与「匹配区」的 48 格读写本来就是同一份
 * {@code AreaMarks}，留两份只会让玩家在两处之间来回跑）。点行 = 把「匹配区」的编辑上下文切到这个区域
 * （{@code SetAreaFilterContextPacket}），拖入物品 / 流体 / JEI 条目全部走机器界面那一套既有通道。</p>
 * <p>同时按用户第 5 条（「有些字很小、有些没必要的废话」）把面板加宽到 {@code W = 176}
 * （128 宽必然把坐标行缩到 0.6 倍字），坐标行改成紧凑写法学 {@code 100,64,100→120,80,120}，
 * 顶部加一个常驻搜索框（名字 / 坐标都能搜），tooltip 从 6 行砍到 3 行。</p>
 *
 * <h2>数据源（契约 §4「数据源」一行）</h2>
 * <p>行列表、过滤图标摘要、当前行、可用位次<b>全部</b>只读
 * {@link AdvancedAreaSnapshotPacket#lastFor(int)}；客户端<b>不判断</b>区域可用性、<b>不读</b>方块实体。
 * 本类<b>不再</b>读选定器物品上的标记表（第 130 轮起那份数据的唯一界面是匹配区）。</p>
 */
public final class AreaStripPanel {
    private static final String LANG = "gui.rs_create_compat.area_strip.";
    /** 无名区域的显示名（复用旧区域列表的键，两处说得一样）。 */
    private static final String LANG_REGION = "gui.rs_create_compat.area_selector.region";

    /**
     * 「当前在干活」常驻世界框的 Outliner slot。
     * <p>必须是 <b>static final 常量</b>：Outliner 用它当键，原来每次 {@code AreaOutline.slot("strip/focus")}
     * 都 new 一个 String，等于每帧给 Outliner 递一个全新键（纯浪费，也是「框偶尔不更新」的嫌疑源）。</p>
     */
    private static final Object FOCUS_SLOT = AreaOutline.slot("strip/focus");

    // ==================== 契约 §2：几何（字面量不得改） ====================

    /**
     * 侧栏理想宽（第 130 轮 128 → 176）。
     * <p><b>为什么必须加宽</b>：128 宽时可用文字宽 = 128-8-6 = 114px，坐标串
     * {@code (100,64,100)→(120,80,120)} 要 ~150px，{@link RsccTextFit#drawFitLine} 只好缩到
     * 0.6 倍 —— 这就是用户说的「有一些字很小」。176 宽下紧凑写法
     * {@code 100,64,100→120,80,120}（≈120px）1.0 倍放得下。</p>
     */
    public static final int W = 176;
    /** 屏幕挤时的最小宽。 */
    public static final int MIN_W = 140;
    /** 低于此宽：不画侧栏，chat 提示一次。 */
    public static final int HARD_MIN_W = 108;
    /** 与 GUI 右边缘的间隙。 */
    public static final int GAP = 6;
    /** 距屏幕右边缘至少留这么多。 */
    public static final int SCREEN_MARGIN = 4;
    /** 行高（未展开时）。 */
    public static final int ROW_H = 22;
    /** 侧栏内边距。 */
    public static final int PAD = 4;

    /**
     * GUI 本体宽 / 高。
     * <p>与基类 {@code CollectionCacheScreen.BG_W / BG_H} <b>同一组字面量</b>：那两个字段是
     * {@code private}，面板读不到；契约 §2 也把 256 / 324 写成了自检锚点（本文件里的
     * {@code leftPos + BG_W + GAP} 与 {@code BG_H - 8} 就是那两个锚点本身）。
     * 面板需要它算「贴在 GUI 右缘」的位置。</p>
     */
    private static final int BG_W = 256;
    private static final int BG_H = 324;

    // ==================== 内部几何 ====================
    /** 标题行高（一行文字 + 1px 间隔）。 */
    private static final int TITLE_H = 12;
    /** 搜索框高（第 130 轮：常驻，不是点了才出现）。 */
    private static final int SEARCH_H = 12;
    /** 标题下沿 → 搜索框的缝。 */
    private static final int SEARCH_GAP = 3;
    /** 搜索框底 → 行列表起点的缝。 */
    private static final int SEARCH_ROWS_GAP = 3;
    /** 底部按钮行高 / 两钮之间的缝。 */
    private static final int FOOTER_H = 16;
    private static final int FOOTER_GAP = 3;
    /** 行内最多画几个过滤图标（与坐标行争宽度，2 个刚好）。 */
    private static final int ROW_ICON_MAX = 2;
    private static final int ROW_ICON_PITCH = 9;
    /**
     * 行内动作区：改名（✎）/ 删除（✕）两个 8px 图标，从右往左排。
     * <p>第 130 轮把原计划的第三个「看（◎）」撤掉了：{@link #peekArea(int)} 已经绑在
     * <b>右键行</b>上（同样的 5 秒世界框），而动作区少一个图标正好多出 10px 给坐标行 ——
     * 常见三位数坐标串（141px）因此能 1.0 倍放下，不再被缩到 0.93。用户要的「字别太小」优先。</p>
     */
    private static final int ACT_ICON = 8;
    private static final int ACT_PITCH = 9;
    private static final int ACT_COUNT = 2;
    /** 名称行高 / 坐标行高（相对行顶）。 */
    private static final int NAME_LINE_H = 11;
    private static final int COORD_LINE_H = 10;
    /** 展开坐标编辑的额外高（两行数字框 + 一行错误提示）。 */
    private static final int EXPAND_H = 40;
    /** 滚动条宽（契约：右侧 6px）。 */
    private static final int SCROLL_W = 6;
    /** 当前行左侧亮条宽（契约 3px）。 */
    private static final int HIGHLIGHT_W = 3;
    /** 一屏最多几行（面板最高 316 / 最小行高 22 ⇒ 14；留余量）。 */
    private static final int MAX_VISIBLE_ROWS = 16;
    /** 行内同时可见的编辑框（6 个坐标）行距。 */
    private static final int FIELD_H = 12;
    /** 删除 / 清空 的二次确认窗口（契约：3 秒）。 */
    private static final long CONFIRM_MS = 3000L;
    /** 双击判定窗口。 */
    private static final long DOUBLE_CLICK_MS = 320L;

    // ==================== 颜色（与全工程同一套橙） ====================
    /**
     * 契约字面量 {@code 0xFF9F1C}：当前行竖条 / ▶ / 世界框。
     * <p>与 {@link AreaOutline#COLOR} <b>必须相等</b>（同一个橙；改一处另一处必须同步）。
     * 这里刻意写字面量而不是引用常量：契约 §4「当前行标记」把 {@code 0xFF9F1C} 写成了自检锚点。</p>
     */
    private static final int COLOR_ACCENT = 0xFF9F1C;
    /** 当前行行底（半透明橙，压在面板底上仍能看清）。 */
    private static final int COLOR_ROW_CURRENT = 0x30FF9F1C;
    private static final int COLOR_TITLE = 0xFF2B2B2B;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF6A6A6A;
    private static final int COLOR_ERROR = 0xFFB00020;
    private static final int COLOR_HOVER = 0x22FFFFFF;
    /** 自绘按钮：底 / 上左暗边 / 下右亮边（与原版按钮同族）。 */
    private static final int COLOR_BTN = 0xFF8B8B8B;
    private static final int COLOR_BTN_HOVER = 0xFF9E9E9E;
    private static final int COLOR_BTN_DARK = 0xFF373737;
    private static final int COLOR_BTN_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_BTN_ARMED = 0xFFE0A0A0;

    // ==================== 静态开关状态（契约 §5：跨界面记忆） ====================
    /**
     * 侧栏是否展开 —— <b>真正的状态存在 owner 的 static 字段里</b>
     * （{@code AdvancedCollectionCacheScreen#stripOpen}）。
     * <p>为什么不是本类自己存：契约 §5 说「状态存 static，跨界面记忆」，而「区域」按钮属于屏幕；
     * 让屏幕持有唯一一份状态，面板只做转发，就不会出现「按钮改了一份、面板读的是另一份」。</p>
     */

    /** 侧栏当前是否展开（跨界面记忆）。 */
    public static boolean isOpen() {
        return AdvancedCollectionCacheScreen.isStripOpen();
    }

    /** 设置展开状态（「区域」按钮 / 底部「关框」用）。 */
    public static void setOpen(final boolean open) {
        AdvancedCollectionCacheScreen.setStripOpen(open);
    }

    // ==================== 实例状态 ====================

    private final AdvancedCollectionCacheScreen owner;
    private final Minecraft minecraft = Minecraft.getInstance();

    /** 本帧几何（屏幕坐标；{@link #layout} 每帧写）。 */
    private int x;
    private int y;
    private int w;
    private int h;
    private boolean laidOut;
    /** 本帧是否可见（槽里有选定器 + 展开 + 屏幕够宽）。 */
    private boolean visible;
    /** 「屏幕太窄」只提示一次（重新可见后复位）。 */
    private boolean narrowWarned;

    /** 本帧从服务端快照重建出来的<b>全部</b>行（搜索过滤前）。 */
    private final List<Row> allRows = new ArrayList<>();
    /** 本帧要显示的行（= {@link #allRows} 经搜索过滤后的结果）。 */
    private final List<Row> rows = new ArrayList<>();
    /** 行滚动偏移（行单位）。 */
    private int scrollRows;

    // ---------- 搜索（第 130 轮） ----------
    /**
     * 常驻搜索框（名字 / 坐标都能搜）。
     * <p>为什么是 EditBox 而不是自己画 + 自己收字符：工程里「就地改名」已经证明自绘 EditBox +
     * owner 转发 {@code charTyped} 这条路是通的（见 {@code beginRename}），不再造第二套输入法。</p>
     */
    @Nullable
    private EditBox searchBox;
    /** 上一次收到焦点的搜索框状态（用于「失焦即过滤」这类边界，避免每帧重算）。 */
    private boolean searchFocused;
    /** 本帧的过滤串（小写、已去首尾空白；空串 = 不过滤）。 */
    private String query = "";

    /** 本帧可见行的布局结果（绘制与命中测试同源）。 */
    private final int[] vrTop = new int[MAX_VISIBLE_ROWS];
    private final int[] vrHeight = new int[MAX_VISIBLE_ROWS];
    private final int[] vrRaw = new int[MAX_VISIBLE_ROWS];
    private final int[] vrUsable = new int[MAX_VISIBLE_ROWS];
    private final int[] vrListIndex = new int[MAX_VISIBLE_ROWS];
    /** 本帧每行动作图标的左边界（屏幕坐标）。 */
    private final int[] vrActionX = new int[MAX_VISIBLE_ROWS];
    private int vrCount;
    /** 本帧行区顶 / 底（绝对 y；命中测试用）。 */
    private int rowsTopAbs;
    private int rowsBottomAbs;

    /** 服务端「正在编辑过滤」的原始下标（快照里的权威值）；点行时先乐观写这里。 */
    private int focusedRaw = -1;
    /** 上一次点击（双击判定）。 */
    private int lastClickRaw = -1;
    private boolean lastClickOnName;
    private long lastClickMs;

    // ---------- 改名（就地 EditBox） ----------
    @Nullable
    private EditBox nameBox;
    /** 名称框此刻代表哪一行（rawIndex；{@code -1} = 没有打开）。 */
    private int nameBoxRaw = -1;
    /** 待提交的重命名属于哪一行（打字时捕获；{@code -1} = 没有待提交）。 */
    private int pendingRenameIndex = -1;
    private String pendingRename = "";
    private boolean renameDirty;
    /** 程序化回填名称框：此时 responder 触发的「变化」不是玩家编辑。 */
    private boolean syncingName;

    // ---------- 坐标编辑（行展开 6 个数字框） ----------
    private final EditBox[] coordBoxes = new EditBox[6];
    /** 展开的是哪一行（rawIndex；{@code -1} = 没有展开）。 */
    private int expandedRaw = -1;
    /** 待提交的坐标属于哪一行（在框里打字时捕获）。 */
    private int pendingApplyIndex = -1;
    @Nullable
    private Area pendingApply;
    private boolean applyDirty;
    private boolean syncingCoords;
    /** 出错的展开行（描红边框 + 一行说明）。 */
    private int errorRaw = -1;
    @Nullable
    private String errorKey;

    // ---------- 删除二次确认 ----------
    private int deleteArmedRaw = -1;
    private long deleteArmedAt = Long.MIN_VALUE;
    /** 本帧 armed 行上「✓ / ✗」两颗确认按钮的屏幕矩形（{@code null} = 本帧没有 armed 行）。 */
    @Nullable
    private Rect2i confirmYesRect;
    @Nullable
    private Rect2i confirmNoRect;

    /** 上一次「显示全部」喂过几个盒子（关界面 / 再点一次时按它清 slot）。 */
    private int shownAll;
    /** 上一次已提示过的「服务端拒绝序号」（只在序号变大时提示一次，不刷屏）。 */
    private int lastRejectSeq = -1;

    // ---------- 底部按钮 / 滚动条 ----------
    @Nullable
    private Rect2i showAllRect;
    @Nullable
    private Rect2i closeRect;
    @Nullable
    private SptScrollbarWidget scrollbar;
    /** 滚动条上次的几何（变了就重建，避免每帧 new）。 */
    private int sbHeight = -1;
    private int sbX = -1;

    private final AdvancedCollectionCacheMenu menu;

    public AreaStripPanel(final AdvancedCollectionCacheScreen owner) {
        this.owner = owner;
        this.menu = owner.stripMenu();
    }

    /** 本帧自适应后的宽（{@link #layout} 之后有效；不可见时返回 0）。 */
    public int width() {
        return visible ? w : 0;
    }

    /** 本帧是否可见（JEI / 自检可读）。 */
    public boolean isVisible() {
        return visible;
    }

    /** 侧栏正在编辑（有 EditBox 打开 / 搜索框聚焦）⇒ owner 必须把 keyPressed / charTyped 先给本面板。 */
    public boolean isEditing() {
        return nameBox != null || coordBoxes[0] != null
            || (searchBox != null && searchBox.isFocused());
    }

    /** 搜索框是否聚焦（自检 / 诊断可读）。 */
    public boolean isSearchFocused() {
        return searchFocused;
    }

    /** 当前过滤串（小写；空串 = 不过滤）。 */
    public String query() {
        return query;
    }

    // ==================== 搜索（第 130 轮） ====================

    /**
     * 搜索框的几何（与 {@link #drawTitle} 同源计算，命中与绘制不会漂）。
     * <p>放在标题行下面第二行、占满整条内容宽 —— 常驻可见，玩家一打开就知道能搜。</p>
     */
    private int searchY() {
        return y + PAD + TITLE_H + 1 + SEARCH_GAP;
    }

    private int searchW() {
        return contentW();
    }

    /** 懒创建搜索框（第一次可见时才造：{@code init()} 阶段拿不到本类的最终宽）。 */
    private void ensureSearchBox() {
        if (searchBox != null) {
            return;
        }
        final EditBox box = new EditBox(font(), contentX(), searchY(), searchW(), SEARCH_H,
            Component.translatable(LANG + "search.hint"));
        box.setMaxLength(64);
        box.setTextShadow(false);
        box.setBordered(true);
        // hint 文案里带「/」与中文：EditBox 的提示只在空值且未聚焦时画，正合适
        box.setHint(Component.translatable(LANG + "search.hint"));
        box.setResponder(text -> query = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT));
        searchBox = box;
    }

    /**
     * 一行是否命中当前过滤串。
     * <p>口径（契约 §3.1）：输入按空格切成 token，<b>全部</b> token 都要命中；单 token 命中 =
     * 名字（不区分大小写）{@code contains} <b>或</b> 坐标串 {@code contains}。
     * 坐标串把 6 个数字用逗号拼起来、去掉所有空格 —— 于是 {@code "100"}、
     * {@code "100,64"}、{@code "64100"} 都能命中 {@code 100,64,100}，玩家不用记格式。</p>
     */
    private boolean matchesQuery(final Row row) {
        if (query.isEmpty()) {
            return true;
        }
        final Area area = row.area();
        final String fallback = Component.translatable(LANG_REGION, row.rawIndex() + 1).getString();
        final String name = area.displayName(fallback).toLowerCase(java.util.Locale.ROOT);
        final String coords = coordSearchText(area);
        for (final String token : query.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!name.contains(token) && !coords.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** 坐标的「可搜索文本」：6 个数字用逗号连起来（无空格、无括号），见 {@link #matchesQuery}。 */
    private static String coordSearchText(final Area area) {
        return area.minX() + "," + area.minY() + "," + area.minZ() + ","
            + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    // ==================== 布局（每帧） ====================

    /**
     * 本帧几何：{@code x = leftPos + BG_W + GAP}、{@code y = topPos + 4}、{@code h = BG_H - 8}，
     * 宽度按契约 §2 的口径 {@code clamp(avail, MIN_W, W)} 自适应，并<b>每帧</b>写
     * {@link AreaStripBounds}（不可见时写 {@code null}）。
     *
     * <p>可见条件三条，缺一不可：① 侧栏展开（static 记忆）；② 专用槽里真的有
     * {@code AreaSelectorItem}（槽空 ⇒ 不画，GUI 里那颗「区域」按钮会给一句「请放进专用槽」）；
     * ③ {@code avail >= HARD_MIN_W}（屏幕再挤就只能放弃侧栏 —— 宁可没有，也不画一条压在 JEI 上、
     * 或者被屏幕右缘切掉一半的侧栏）。</p>
     */
    public void layout(final int leftPos, final int topPos, final int screenW, final int screenH) {
        laidOut = true;
        final int nextX = leftPos + BG_W + GAP;
        final int avail = screenW - SCREEN_MARGIN - nextX;
        // 契约 §4「常驻条件」：专用槽里有 AreaSelectorItem 才画侧栏（槽空 ⇒ 不画；
        // 客户端绝不回退主手 —— 与 AreaSelectorTargets#resolve 同一条口径）。
        final ItemStack selector = menu.getAreaSelectorStack();
        final boolean hasSelector = !selector.isEmpty()
            && selector.getItem() instanceof cretae.cookiewyq.rs_create_compat.item.AreaSelectorItem;
        if (!AdvancedCollectionCacheScreen.isStripOpen() || !hasSelector || avail < HARD_MIN_W) {
            visible = false;
            AreaStripBounds.set(null);
            // 侧栏不可见 ⇒ 「当前在干活」的常驻世界框也必须立刻消失（否则收起侧栏后框还留在世界里）
            AreaOutline.remove(FOCUS_SLOT);
            // 第 130 轮：搜索框的焦点也要放掉 —— 否则「聚焦搜索框 → 收起侧栏」之后 owner 仍会把
            // keyPressed / charTyped 转发给一个看不见的面板（玩家按 E 关不掉界面，像卡死）。
            if (searchBox != null && searchBox.isFocused()) {
                searchBox.setFocused(false);
                searchFocused = false;
            }
            if (AdvancedCollectionCacheScreen.isStripOpen() && hasSelector
                && avail < HARD_MIN_W && !narrowWarned) {
                narrowWarned = true; // 「一帧内只提示一次」（这里做得更强：一次可见期内只一次）
                owner.stripHint(LANG + "narrow");
            }
            return;
        }
        narrowWarned = false;
        x = nextX;
        // 契约 §2 的口径：avail >= W ⇒ W；MIN_W <= avail < W ⇒ avail；HARD_MIN_W <= avail < MIN_W ⇒ MIN_W。
        w = avail >= W ? W : Math.max(MIN_W, avail);
        y = topPos + 4;
        // 契约：h = BG_H - 8；再夹一次屏幕（GUI 比屏幕还高时不让侧栏溢出到屏幕外）
        final int maxH = screenH - y - 1;
        h = Math.max(64, Math.min(BG_H - 8, maxH));
        visible = true;
        AreaStripBounds.set(new Rect2i(x, y, w, h));
    }

    private AdvancedAreaSnapshotPacket snapshotOrNull() {
        return AdvancedAreaSnapshotPacket.lastFor(menu.containerId);
    }

    private int contentW() {
        return Math.max(24, w - 2 * PAD - SCROLL_W);
    }

    private int contentX() {
        return x + PAD;
    }

    private int contentRight() {
        return contentX() + contentW();
    }

    private boolean isInside(final double mx, final double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int rowsAreaTop() {
        return searchY() + SEARCH_H + SEARCH_ROWS_GAP;
    }

    private int rowsAreaBottom() {
        return y + h - PAD - FOOTER_H - 2;
    }

    // ==================== 渲染 ====================

    /** 每帧：提交到期输入 → 重建行 → 画面板 / 标题 / 搜索框 / 行 / 底部 → 喂世界框。 */
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        if (!visible) {
            return;
        }
        // 二次确认到期就自动解除武装（3 秒后必须自己回到安全态，不能一直「确认？」挂着）
        expireArming();
        ensureSearchBox();
        rebuildRows();
        // 服务端对最近一次区域写入的拒绝原因（改名 / 改坐标 / 删除被拒）⇒ 明说一句，绝不静默
        handleServerReject();
        drawPanelBackground(guiGraphics);
        drawTitle(guiGraphics);
        confirmYesRect = null;
        confirmNoRect = null;
        layoutRows();
        drawRows(guiGraphics, mouseX, mouseY);
        drawFooter(guiGraphics, mouseX, mouseY);
        renderScrollbar(guiGraphics, mouseX, mouseY, partialTick);
        renderSearchBox(guiGraphics, mouseX, mouseY, partialTick);
        renderInputs(guiGraphics, mouseX, mouseY, partialTick);
        feedWorldOutline();
    }

    /** 搜索框：每帧跟面板摆位（与 {@link #searchY()} / {@link #searchW()} 同源）。 */
    private void renderSearchBox(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                 final float partialTick) {
        final EditBox box = searchBox;
        if (box == null) {
            return;
        }
        box.setX(contentX());
        box.setY(searchY());
        box.setWidth(searchW());
        box.render(guiGraphics, mouseX, mouseY, partialTick);
        searchFocused = box.isFocused();
    }

    /**
     * 服务端拒绝原因 → 一句话。
     * <p>客户端<b>不判断</b>区域合法性（越界 / 超单边 / 超体积 / 维度不符全部由服务端权威判定），
     * 因此「被拒」必须有一条可见通道：{@code AdvancedAreaSnapshotPacket} 带回
     * {@code rejectCode + rejectSeq}，这里只在序号变大时提示一次（不刷屏）；行列表本身每帧从最新
     * 快照重建，所以「被拒后自动回到服务端真值」是自然结果，不需要额外刷新请求。</p>
     */
    private void handleServerReject() {
        final AdvancedAreaSnapshotPacket snapshot = snapshotOrNull();
        if (snapshot == null || snapshot.rejectSeq() <= lastRejectSeq) {
            return;
        }
        lastRejectSeq = snapshot.rejectSeq();
        final String key = switch (snapshot.rejectCode()) {
            case AreaSelectorEditPacket.REJECT_NO_SUCH_AREA ->
                "gui.rs_create_compat.area_selector.err.reject.no_such_area";
            case AreaSelectorEditPacket.REJECT_DIMENSION ->
                "gui.rs_create_compat.area_selector.err.dimension";
            case AreaSelectorEditPacket.REJECT_LIMIT ->
                "gui.rs_create_compat.area_selector.err.limit";
            case AreaSelectorEditPacket.REJECT_DUPLICATE ->
                "gui.rs_create_compat.area_selector.err.duplicate";
            case AreaSelectorEditPacket.REJECT_INVALID_AREA ->
                "gui.rs_create_compat.area_selector.err.reject.invalid";
            default -> null;
        };
        if (key != null) {
            owner.stripHint(key);
        }
    }

    /** 本帧仍然可见的行必须能滚动到（把越界偏移收敛，避免「滚过头一行都看不见」）。 */
    private void clampScroll() {
        final int max = maxScroll();
        if (scrollRows > max) {
            scrollRows = max;
        }
        if (scrollRows < 0) {
            scrollRows = 0;
        }
    }

    private int maxScroll() {
        final int viewH = Math.max(0, rowsAreaBottom() - rowsAreaTop());
        final int fit = Math.max(1, viewH / ROW_H);
        return Math.max(0, rows.size() - fit);
    }

    private void drawPanelBackground(final GuiGraphics guiGraphics) {
        // 不新增任何贴图：面板走原版九宫格精灵（与工程里所有子窗口同一套外观）
        McGui.panel(guiGraphics, x, y, w, h);
        // 面板内侧 1px 深色描边：让「侧栏」与 GUI 本体在色调上分得开（原版精灵自带外描边，这里只补内侧）
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + 2, 0x40FFFFFF);
    }

    private void drawTitle(final GuiGraphics guiGraphics) {
        final AdvancedAreaSnapshotPacket snapshot = snapshotOrNull();
        final int total = snapshot == null ? 0 : snapshot.usableCount();
        final int ordinal = currentOrdinal();
        final Component title;
        if (snapshot == null) {
            title = Component.translatable(LANG + "waiting.short");
        } else if (!query.isEmpty()) {
            // 过滤中：标题第二段直接说「命中几行」——玩家不用自己数（用户要的「搜索」闭环）
            title = Component.translatable(LANG + "title.filtered",
                ordinal < 0 ? 0 : ordinal + 1, total, rows.size());
        } else if (ordinal < 0) {
            title = Component.translatable(LANG + "title.none", total);
        } else {
            title = Component.translatable(LANG + "title", ordinal + 1, total, currentName());
        }
        RsccTextFit.drawFitLine(guiGraphics, font(), title, contentX(), y + PAD, contentW(),
            COLOR_TITLE);
        // 标题下的 1px 橙线：与「当前行」同一套视觉语言（一眼看出这条带子是区域功能）
        guiGraphics.fill(contentX(), y + PAD + 10, contentRight(), y + PAD + 11, 0x60FF9F1C);
    }

    /** 当前行在可见列表里的序号（{@code -1} = 没有当前行）。 */
    private int currentOrdinal() {
        final int raw = currentRaw();
        if (raw < 0) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).rawIndex() == raw) {
                return i;
            }
        }
        return -1;
    }

    private Component currentName() {
        final int raw = currentRaw();
        for (final Row row : allRows) {
            if (row.rawIndex() == raw) {
                return Component.literal(row.area().displayName(
                    Component.translatable(LANG_REGION, raw + 1).getString()));
            }
        }
        return Component.translatable(LANG + "current.none");
    }

    /** 当前正在干活的区域（原始下标）：优先服务端快照，其次点行的乐观值。 */
    private int currentRaw() {
        final AdvancedAreaSnapshotPacket snapshot = snapshotOrNull();
        if (snapshot != null && snapshot.editingRawIndex() >= 0) {
            return snapshot.editingRawIndex();
        }
        return focusedRaw;
    }

    @Nullable
    private Area currentArea() {
        final int raw = currentRaw();
        for (final Row row : allRows) {
            if (row.rawIndex() == raw) {
                return row.area();
            }
        }
        return null;
    }

    // ---------- 行 ----------

    /**
     * 从服务端快照重建本帧的行（客户端不做任何取舍），再按搜索框过滤。
     * <p>{@link #allRows} 是权威全集（改名 / 坐标 / 当前行 / 去重判定都读它），{@link #rows} 只是
     * 「这一屏画哪些」—— 搜索只影响显示，<b>绝不影响</b>服务端的编辑上下文与操作。</p>
     */
    private void rebuildRows() {
        allRows.clear();
        rows.clear();
        final AdvancedAreaSnapshotPacket snapshot = snapshotOrNull();
        if (snapshot == null) {
            return;
        }
        for (int i = 0; i < snapshot.entries().size(); i++) {
            final AdvancedAreaSnapshotPacket.AreaEntry entry = snapshot.entries().get(i);
            if (entry == null || entry.area() == null) {
                continue;
            }
            allRows.add(new Row(entry.rawIndex(), i, entry.area(),
                entry.filters() == null ? List.of() : entry.filters()));
        }
        for (final Row row : allRows) {
            if (matchesQuery(row)) {
                rows.add(row);
            }
        }
    }

    /** 一行（本帧的快照切片；{@code usableIndex} = 快照 entries 的下标，过滤上下文用）。 */
    private record Row(int rawIndex, int usableIndex, Area area, List<MarkerEntry> filters) {
    }

    private int rowHeight(final Row row) {
        return row.rawIndex() == expandedRaw ? ROW_H + EXPAND_H : ROW_H;
    }

    /** 按滚动偏移排出本帧可见行（绘制与命中同源）。 */
    private void layoutRows() {
        clampScroll();
        rowsTopAbs = rowsAreaTop();
        rowsBottomAbs = rowsAreaBottom();
        vrCount = 0;
        if (rows.isEmpty()) {
            return;
        }
        int top = rowsTopAbs;
        for (int i = scrollRows; i < rows.size() && vrCount < MAX_VISIBLE_ROWS; i++) {
            final Row row = rows.get(i);
            final int height = rowHeight(row);
            if (top + height > rowsBottomAbs) {
                break;
            }
            vrTop[vrCount] = top;
            vrHeight[vrCount] = height;
            vrRaw[vrCount] = row.rawIndex();
            vrUsable[vrCount] = row.usableIndex();
            vrListIndex[vrCount] = i;
            // 三个动作图标贴右缘：标记 / 改名 / 删除
            vrActionX[vrCount] = contentRight() - ACT_COUNT * ACT_PITCH - (ACT_COUNT - 1) + 1;
            vrCount++;
            top += height;
        }
        // 展开行被滚出可视区 ⇒ 提交并收起（否则「看不见的输入框」会吞掉键盘）
        if (expandedRaw >= 0 && !isRowVisible(expandedRaw)) {
            finishCoords(true);
        }
    }

    private boolean isRowVisible(final int raw) {
        for (int i = 0; i < vrCount; i++) {
            if (vrRaw[i] == raw) {
                return true;
            }
        }
        return false;
    }

    private void drawRows(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (rows.isEmpty()) {
            // 三种空态说三句不同的话（第 130 轮：搜索无命中和「一条区域都没有」是两回事）
            final String note;
            if (snapshotOrNull() == null) {
                note = Component.translatable(LANG + "waiting").getString();
            } else if (!allRows.isEmpty()) {
                note = Component.translatable(LANG + "search.none").getString();
            } else {
                note = Component.translatable(LANG + "empty").getString();
            }
            RsccTextFit.drawFitBlock(guiGraphics, font(), Component.literal(note),
                contentX(), rowsTopAbs + 2, contentW(), 3, 10, COLOR_DIM);
            return;
        }
        final int current = currentRaw();
        for (int i = 0; i < vrCount; i++) {
            drawRow(guiGraphics, mouseX, mouseY, i, vrRaw[i] == current);
        }
    }

    private void drawRow(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                         final int slotIndex, final boolean current) {
        final Row row = rows.get(vrListIndex[slotIndex]);
        final int top = vrTop[slotIndex];
        final int height = vrHeight[slotIndex];
        final boolean hovered = !current && mouseX >= x && mouseX < x + w
            && mouseY >= top && mouseY < top + height;
        if (current) {
            // 契约「当前行标记」：行底淡橙 + 左侧 3px 亮橙竖条 + 行首 ▶
            guiGraphics.fill(contentX() - PAD + 1, top + 1,
                x + w - 1, top + height - 1, COLOR_ROW_CURRENT);
            guiGraphics.fill(contentX() - PAD + 1, top + 1,
                contentX() - PAD + 1 + HIGHLIGHT_W, top + height - 1, COLOR_ACCENT);
        } else if (hovered) {
            guiGraphics.fill(contentX() - PAD + 1, top + 1, x + w - 1, top + height - 1, COLOR_HOVER);
        }
        final int textX = contentX() + (current ? 8 : 2);
        final int actW = ACT_COUNT * ACT_PITCH + (ACT_COUNT - 1);
        final int nameW = Math.max(12, contentRight() - textX - actW - 2);
        if (current) {
            guiGraphics.drawString(font(), "▶", contentX(), top + 2, COLOR_ACCENT, false);
        }
        final boolean armed = deleteArmedRaw == row.rawIndex() && isArmed(deleteArmedAt);
        // 第一行：N. 名字（删除二次确认期间换成红字「确认删除 #N？」）
        final String name = (row.rawIndex() + 1) + ". " + row.area().displayName(
            Component.translatable(LANG_REGION, row.rawIndex() + 1).getString());
        if (armed) {
            RsccTextFit.drawFitLine(guiGraphics, font(),
                Component.translatable(LANG + "delete.confirm.this", row.rawIndex() + 1),
                textX, top + 2, nameW + actW, COLOR_ERROR);
            // 确认态：动作区换成 ✓ / ✗ 两颗按钮（命中区与本帧矩形同源，见 onRowClicked）
            final int yesX = contentRight() - 2 * ACT_PITCH - 1;
            confirmYesRect = new Rect2i(yesX, top + 1, ACT_PITCH, 10);
            confirmNoRect = new Rect2i(yesX + ACT_PITCH, top + 1, ACT_PITCH, 10);
            drawActionIcon(guiGraphics, yesX, top + 2,
                Component.translatable(LANG + "confirm.glyph"), mouseX, mouseY);
            drawActionIcon(guiGraphics, yesX + ACT_PITCH, top + 2,
                Component.translatable(LANG + "cancel.glyph"), mouseX, mouseY);
        } else {
            RsccTextFit.drawFitLine(guiGraphics, font(), Component.literal(name),
                textX, top + 2, nameW, current ? COLOR_TITLE : COLOR_TEXT);
            // 两个动作图标：改名（✎）/ 删除（✕，二次确认）；「看」= 右键行（见 onRowClicked）
            final int ax = vrActionX[slotIndex];
            drawActionIcon(guiGraphics, ax, top + 2,
                Component.translatable(LANG + "rename.glyph"), mouseX, mouseY);
            drawActionIcon(guiGraphics, ax + ACT_PITCH, top + 2,
                Component.translatable(LANG + "delete.glyph"), mouseX, mouseY);
        }
        // 第二行：坐标紧凑串 (+ 右侧过滤图标摘要)
        // 起点固定 contentX()+2（不跟名字行的 ▶ 缩进）：坐标行多这 6px 就能让常见串 1.0 倍放下
        drawRowCoords(guiGraphics, row, top + NAME_LINE_H, contentX() + 2, actW);
        // 展开：6 个数字框（框本身由 renderInputs 画；这里画「从 / 到」标签与出错描红）
        if (row.rawIndex() == expandedRaw) {
            drawCoordLabels(guiGraphics, top + ROW_H);
        }
    }

    /**
     * 第二行：坐标。
     *
     * <h2>为什么要「三段式择优」而不是一句 {@code drawFitLine}</h2>
     * <p>用户第 130 轮原话「有一些字很小」的算术根因：128 宽 + 括号坐标串（≈150px）必然被缩到 0.6 倍。
     * 但现在即便缩到 0.6 也不该再出现 —— 常见坐标串（含负号的三位数）要 132~153px，
     * 而一行可用宽只有 131px（176 宽 - 动作图标 29 - 左边距 2）。于是按优先级择优：</p>
     * <ol>
     *     <li><b>最优</b>：{@code min→max} 一整串放得下 ⇒ 1.0 倍画；</li>
     *     <li><b>次优</b>：放不下 ⇒ 拆成「左下角…右上角两个端点」，<b>去掉箭头</b>分别贴左右画
     *     （省 9px，视觉上仍是一行坐标，玩家不会误解）；</li>
     *     <li><b>兜底</b>：两个端点加起来还放不下（极值 / 超长负数）⇒ 才交给 {@link RsccTextFit}
     *     缩字号（<b>缩之前先让位给文字</b>：过滤图标摘要在这条路上直接不画 —— 图标是摘要，文字才是信息）。</li>
     * </ol>
     * <p>过滤图标只在「画完文字还剩 ≥ 图标宽 + 2px」时才画：它是锦上添花，绝不能挤占坐标。</p>
     */
    private void drawRowCoords(final GuiGraphics guiGraphics, final Row row, final int lineTop,
                               final int textX, final int actW) {
        final int fullW = Math.max(12, contentRight() - textX - actW);
        final Area area = row.area();
        final String joined = compactCoords(area);
        final String low = cornerText(area.minX(), area.minY(), area.minZ());
        final String high = cornerText(area.maxX(), area.maxY(), area.maxZ());
        final int joinedW = font().width(joined);
        final int lowW = font().width(low);
        final int highW = font().width(high);
        // 端点之间的最小呼吸位（0：两个端点紧贴也算「放得下」—— 它们之间没有分隔符就不会读错）
        final int gap = 2;

        int icons = 0;
        boolean drewJoined = joinedW <= fullW;
        boolean drewSplit = !drewJoined && lowW + gap + highW <= fullW;
        final int usedByText = drewJoined ? joinedW : (drewSplit ? lowW + gap + highW : joinedW);
        if (usedByText + 2 * ROW_ICON_PITCH + 2 <= fullW) {
            for (final MarkerEntry entry : row.filters()) {
                if (icons >= ROW_ICON_MAX) {
                    break;
                }
                if (entry != null && !entry.isEmpty() && !entry.fluid()) {
                    icons++;
                }
            }
        }
        if (icons > 0 && !drewSplit && joinedW + icons * ROW_ICON_PITCH + 2 > fullW) {
            // 画了图标就放不下整串 ⇒ 退回端点式（图标优先给「有图标的行」保留空间）
            drewJoined = false;
            drewSplit = lowW + gap + highW <= fullW;
        }
        final int iconW = icons <= 0 ? 0 : icons * ROW_ICON_PITCH + 2;
        final int textW = Math.max(12, fullW - iconW);

        if (drewJoined) {
            guiGraphics.drawString(font(), joined, textX, lineTop, COLOR_DIM, false);
        } else if (drewSplit) {
            guiGraphics.drawString(font(), low, textX, lineTop, COLOR_DIM, false);
            guiGraphics.drawString(font(), high, textX + textW - highW, lineTop, COLOR_DIM, false);
        } else {
            // 兜底：真的放不下（极值）⇒ 缩字号，且**不画图标**（文字优先）
            RsccTextFit.drawFitLine(guiGraphics, font(), Component.literal(joined), textX, lineTop,
                fullW, COLOR_DIM);
            return;
        }
        if (icons > 0) {
            // 图标从右往左排，最后留 2px 呼吸位（与坐标文本的可用宽同源计算）
            final int startX = contentRight() - icons * ROW_ICON_PITCH;
            AdvancedCollectionCacheScreen.drawFilterIcons(guiGraphics, startX, lineTop,
                visibleItemFilters(row.filters()), ROW_ICON_MAX);
        }
    }

    /** 一个角的紧凑串：{@code x,y,z}（无空格无括号）。 */
    private static String cornerText(final int x, final int y, final int z) {
        return x + "," + y + "," + z;
    }

    /** 一行的坐标紧凑串：{@code minX,minY,minZ→maxX,maxY,maxZ}（无空格无括号，见契约 §2）。 */
    private static String compactCoords(final Area area) {
        return cornerText(area.minX(), area.minY(), area.minZ())
            + "→" + cornerText(area.maxX(), area.maxY(), area.maxZ());
    }

    private static List<MarkerEntry> visibleItemFilters(final List<MarkerEntry> filters) {
        final List<MarkerEntry> out = new ArrayList<>(filters.size());
        for (final MarkerEntry entry : filters) {
            if (entry != null && !entry.isEmpty() && !entry.fluid()) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 一个 8px 宽的动作图标（文字行内小按钮；命中区由 {@link #actionAt} 与本方法同源计算）。 */
    private void drawActionIcon(final GuiGraphics guiGraphics, final int left, final int top,
                                final Component glyph, final int mouseX, final int mouseY) {
        final boolean hovered = mouseX >= left && mouseX < left + ACT_ICON
            && mouseY >= top - 1 && mouseY < top + 9;
        if (hovered) {
            guiGraphics.fill(left - 1, top - 1, left + ACT_ICON, top + 9, 0x30FF9F1C);
        }
        RsccTextFit.drawFitLine(guiGraphics, font(), glyph, left, top, ACT_ICON,
            hovered ? COLOR_ACCENT : COLOR_DIM);
    }

    private void drawCoordLabels(final GuiGraphics guiGraphics, final int fieldLineTop) {
        RsccTextFit.drawFitLine(guiGraphics, font(),
            Component.translatable(LANG + "from"), contentX(), fieldLineTop + 3,
            fieldLabelW(), COLOR_DIM);
        RsccTextFit.drawFitLine(guiGraphics, font(),
            Component.translatable(LANG + "to"), contentX(), fieldLineTop + 3 + FIELD_H + 2,
            fieldLabelW(), COLOR_DIM);
        if (errorRaw == expandedRaw && errorKey != null) {
            RsccTextFit.drawFitLine(guiGraphics, font(), Component.translatable(errorKey),
                contentX(), fieldLineTop + 3 + (FIELD_H + 2) * 2, contentW(), COLOR_ERROR);
        }
    }

    private int fieldLabelW() {
        return 14;
    }

    private int fieldW() {
        final int avail = contentW() - fieldLabelW() - 2 * 2;
        return Math.max(18, avail / 3);
    }

    /** 6 个数字框在本帧的左上角（0..2 = 从，3..5 = 到；与 {@link #readAreaFromFields} 同序）。 */
    private int fieldX(final int index) {
        return contentX() + fieldLabelW() + (index % 3) * (fieldW() + 2);
    }

    private int fieldY(final int rowTop, final int index) {
        return rowTop + ROW_H + (index / 3) * (FIELD_H + 2);
    }

    // ---------- 底部 / 滚动条 ----------

    private void drawFooter(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int footerY = y + h - PAD - FOOTER_H;
        final int btnW = Math.max(24, (contentW() - FOOTER_GAP) / 2);
        showAllRect = new Rect2i(contentX(), footerY, btnW, FOOTER_H);
        closeRect = new Rect2i(contentX() + btnW + FOOTER_GAP, footerY, btnW, FOOTER_H);
        drawMidiButton(guiGraphics, showAllRect, Component.translatable(LANG + "show_all"),
            false, mouseX, mouseY);
        drawMidiButton(guiGraphics, closeRect, Component.translatable(LANG + "close"),
            false, mouseX, mouseY);
    }

    /** 底部中号按钮（自绘；命中区 = 绘制区，两者同源）。 */
    private void drawMidiButton(final GuiGraphics guiGraphics, final Rect2i rect,
                                final Component label, final boolean armed,
                                final int mouseX, final int mouseY) {
        final boolean hovered = rect.contains((int) mouseX, (int) mouseY);
        final int left = rect.getX();
        final int top = rect.getY();
        final int right = left + rect.getWidth();
        final int bottom = top + rect.getHeight();
        guiGraphics.fill(left, top, right, bottom,
            armed ? COLOR_BTN_ARMED : (hovered ? COLOR_BTN_HOVER : COLOR_BTN));
        guiGraphics.fill(left, top, right, top + 1, COLOR_BTN_DARK);
        guiGraphics.fill(left, top, left + 1, bottom, COLOR_BTN_DARK);
        guiGraphics.fill(left, bottom - 1, right, bottom, COLOR_BTN_LIGHT);
        guiGraphics.fill(right - 1, top, right, bottom, COLOR_BTN_LIGHT);
        RsccTextFit.drawFitLine(guiGraphics, font(), label, left + 2, top + 4,
            rect.getWidth() - 4, COLOR_TITLE);
    }

    private void renderScrollbar(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                 final float partialTick) {
        final int height = Math.max(0, rowsAreaBottom() - rowsAreaTop());
        if (height < 8) {
            scrollbar = null;
            return;
        }
        if (scrollbar == null || sbHeight != height || sbX != contentRight() + 1) {
            final SptScrollbarWidget created = new SptScrollbarWidget(contentRight() + 1,
                rowsAreaTop(), SptScrollbarWidget.Type.SMALL, height, SCROLL_W);
            created.setListener(value -> scrollRows = (int) Math.round(value));
            scrollbar = created;
            sbHeight = height;
            sbX = contentRight() + 1;
        }
        final int max = maxScroll();
        scrollbar.setMaxOffset(max);
        scrollbar.setEnabled(max > 0);
        if (Math.abs(scrollbar.getOffset() - scrollRows) > 0.001) {
            scrollbar.setOffset(scrollRows);
        }
        scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ---------- 输入框（名称 / 6 个坐标） ----------

    /** 名称框与 6 个坐标框都是自绘（不进 children）：位置每帧跟行，画在本帧最上面。 */
    private void renderInputs(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                              final float partialTick) {
        if (nameBox != null && isRowVisible(nameBoxRaw)) {
            placeNameBox();
            nameBox.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (coordBoxes[0] != null && expandedRaw >= 0 && isRowVisible(expandedRaw)) {
            placeCoordBoxes();
            for (final EditBox box : coordBoxes) {
                box.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void placeNameBox() {
        if (nameBox == null) {
            return;
        }
        final int slot = slotOfRaw(nameBoxRaw);
        if (slot < 0) {
            return;
        }
        nameBox.setX(contentX() + 2);
        nameBox.setY(vrTop[slot] + 1);
        nameBox.setWidth(Math.max(20, contentW() - 2));
    }

    private void placeCoordBoxes() {
        final int slot = slotOfRaw(expandedRaw);
        if (slot < 0) {
            return;
        }
        for (int i = 0; i < coordBoxes.length; i++) {
            if (coordBoxes[i] == null) {
                continue;
            }
            coordBoxes[i].setX(fieldX(i));
            coordBoxes[i].setY(fieldY(vrTop[slot], i));
            coordBoxes[i].setWidth(fieldW());
        }
    }

    private int slotOfRaw(final int raw) {
        for (int i = 0; i < vrCount; i++) {
            if (vrRaw[i] == raw) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 世界框（「当前在干活」的第四条证据） ====================

    /**
     * 当前区域的世界框常亮。
     * <p>契约 §4：「点行 ⇒ 常驻世界框（{@link AreaOutline#chase} + 每帧重喂，slot 前缀
     * {@code strip/focus}）」。<b>每帧重喂</b>是刻意的：{@code chaseAABB} 的语义是
     * 「一直喂就一直显示」，只要玩家还在这个界面里，框就必须一直亮着 ——
     * 这正是用户要的「明显地标注出当前是哪一个在干活」。</p>
     */
    private void feedWorldOutline() {
        final Area area = currentArea();
        if (area == null) {
            AreaOutline.remove(FOCUS_SLOT);
            return;
        }
        AreaOutline.chase(FOCUS_SLOT, area);
    }

    // ==================== 提示 / 工具提示 ====================

    /**
     * 悬停 tooltip（由 owner 在 {@code strip.render} 之后调；自绘元素没有自动 tooltip）。
     * <p>第 130 轮按用户「有些没必要的废话」砍到 <b>3 行</b>：名字 / 从角 / 尺寸（有过滤才多带过滤数）。
     * 原来那行 howto（「点行选中 / 双击改名 / 点坐标行改坐标」）删掉 —— 图标本身就是说明书。</p>
     */
    public void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (!visible || !isInside(mouseX, mouseY)) {
            return;
        }
        final List<Component> lines = new ArrayList<>(3);
        final int slot = rowAt(mouseX, mouseY);
        if (slot >= 0) {
            final Row row = rows.get(vrListIndex[slot]);
            lines.add(Component.literal("#" + (row.rawIndex() + 1) + "  " + row.area().displayName(
                Component.translatable(LANG_REGION, row.rawIndex() + 1).getString())));
            lines.add(Component.translatable(LANG + "tip.from", row.area().describeMin()));
            if (row.filters().isEmpty()) {
                lines.add(Component.translatable(LANG + "tip.size", row.area().describeSize()));
            } else {
                lines.add(Component.translatable(LANG + "tip.size.filters",
                    row.area().describeSize(), row.filters().size()));
            }
        } else if (showAllRect != null && showAllRect.contains((int) mouseX, (int) mouseY)) {
            lines.add(Component.translatable(LANG + "show_all.tip"));
        } else if (closeRect != null && closeRect.contains((int) mouseX, (int) mouseY)) {
            lines.add(Component.translatable(LANG + "close.tip"));
        }
        if (!lines.isEmpty()) {
            guiGraphics.renderTooltip(font(), lines, java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    // ==================== 输入：鼠标 ====================

    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (!visible || !laidOut) {
            return false;
        }
        // ① 点到别处 ⇒ 先把输入框里的东西提交掉（这就是契约要求的「失焦提交」；
        //    不这样做，「改完名字点下一行」会丢掉这次改名 —— 第 110/124 轮的老毛病）
        if (nameBox != null && !isOverNameBox(mouseX, mouseY)) {
            finishRename(true);
        }
        if (coordBoxes[0] != null && !isOverCoordBoxes(mouseX, mouseY)) {
            finishCoords(true);
        }
        if (!isInside(mouseX, mouseY)) {
            return false;
        }
        // ② 搜索框（常驻，占标题下方一整行）：先吃点击；点在别处 = 取消聚焦（否则打字会一直进搜索框）
        if (searchBox != null) {
            if (searchBox.mouseClicked(mouseX, mouseY, button)) {
                searchFocused = true;
                return true;
            }
            if (searchBox.isFocused()) {
                searchBox.setFocused(false);
                searchFocused = false;
            }
        }
        // ②b 输入框自己先拿点击（光标定位）
        if (nameBox != null && nameBox.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (coordBoxes[0] != null) {
            for (final EditBox box : coordBoxes) {
                if (box.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
        }
        // ③ 滚动条
        if (scrollbar != null && scrollbar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 && button != 1) {
            return true; // 侧栏吞掉其它键点击（中键等），不让它掉进背后的 GUI
        }
        // ④ 底部按钮
        if (button == 0 && showAllRect != null && showAllRect.contains((int) mouseX, (int) mouseY)) {
            showAllAreas();
            return true;
        }
        if (button == 0 && closeRect != null && closeRect.contains((int) mouseX, (int) mouseY)) {
            setOpen(false);
            return true;
        }
        // ⑥ 行
        if (button == 0) {
            final int slot = rowAt(mouseX, mouseY);
            if (slot >= 0) {
                onRowClicked(slot, mouseX, mouseY);
            }
            return true; // 点在面板里就算消费掉（面板区域不允许穿透到 GUI）
        }
        if (button == 1) {
            // 右键一行 = 「看这块地」（5 秒世界框，关界面也继续画完）
            final int slot = rowAt(mouseX, mouseY);
            if (slot >= 0) {
                peekArea(vrRaw[slot]);
            }
            return true;
        }
        return true;
    }

    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (!visible) {
            return false;
        }
        return scrollbar != null && scrollbar.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        if (!visible) {
            return false;
        }
        return scrollbar != null && scrollbar.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean mouseScrolled(final double mouseX, final double mouseY, final double delta) {
        if (!visible || !isInside(mouseX, mouseY)) {
            return false;
        }
        final int direction = delta > 0 ? -1 : 1;
        // 行列表滚动：一行 = ROW_H 高（可视高 / ROW_H = 一屏能放几行；maxScroll 用同一条口径）
        final int fitRows = Math.max(1, (rowsAreaBottom() - rowsAreaTop()) / ROW_H);
        final int max = Math.max(0, rows.size() - fitRows);
        if (max <= 0) {
            return true; // 内容不满一屏：滚轮在侧栏上就当作已消费（否则背后的 GUI 会滚）
        }
        scrollRows = Math.max(0, Math.min(max, scrollRows + direction));
        if (scrollbar != null) {
            scrollbar.setOffset(scrollRows);
        }
        return true;
    }

    private void onRowClicked(final int slot, final double mouseX, final double mouseY) {
        final int raw = vrRaw[slot];
        final int usable = vrUsable[slot];
        final int top = vrTop[slot];
        final boolean onName = mouseY < top + NAME_LINE_H;
        final long now = System.currentTimeMillis();
        final boolean doubleClick = raw == lastClickRaw && onName == lastClickOnName
            && now - lastClickMs <= DOUBLE_CLICK_MS;
        lastClickRaw = raw;
        lastClickOnName = onName;
        lastClickMs = now;

        // armed 态：只认 ✓ / ✗ 两颗确认按钮（第二次点 ✓ 才真的删；✗ / 3 秒到期 = 回退）
        if (deleteArmedRaw == raw && isArmed(deleteArmedAt)) {
            if (confirmYesRect != null && confirmYesRect.contains((int) mouseX, (int) mouseY)) {
                armOrDelete(raw);
                return;
            }
            if (confirmNoRect != null && confirmNoRect.contains((int) mouseX, (int) mouseY)) {
                deleteArmedRaw = -1;
                deleteArmedAt = Long.MIN_VALUE;
                return;
            }
            return;
        }
        // 两个动作图标（命中区与 drawRow 同源）
        final int ax = vrActionX[slot];
        if (mouseY < top + 10) {
            if (within(mouseX, ax, ACT_ICON)) {
                selectRow(slot);
                beginRename(raw);
                return;
            }
            if (within(mouseX, ax + ACT_PITCH, ACT_ICON)) {
                armOrDelete(raw);
                return;
            }
        }
        // 坐标行：单击展开 / 收起 6 个数字框（契约「坐标编辑」）
        if (!onName) {
            selectRow(slot);
            toggleExpand(raw);
            return;
        }
        selectRow(slot);
        if (doubleClick) {
            // 双击名称行 = 就地改名（§4）；双击坐标行 = 标记网格（§4.5，见上面的分支顺序）
            beginRename(raw);
        }
    }

    private static boolean within(final double value, final int left, final int width) {
        return value >= left && value < left + width;
    }

    @Nullable
    private Rect2i nameBoxRect() {
        if (nameBox == null) {
            return null;
        }
        return new Rect2i(nameBox.getX(), nameBox.getY(), nameBox.getWidth(), nameBox.getHeight());
    }

    private boolean isOverNameBox(final double mouseX, final double mouseY) {
        final Rect2i rect = nameBoxRect();
        return rect != null && rect.contains((int) mouseX, (int) mouseY);
    }

    private boolean isOverCoordBoxes(final double mouseX, final double mouseY) {
        for (final EditBox box : coordBoxes) {
            if (box != null && mouseX >= box.getX() && mouseX < box.getX() + box.getWidth()
                && mouseY >= box.getY() && mouseY < box.getY() + box.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /** 可见行命中（{@code -1} = 不在任何一行）。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (!visible || mouseX < x || mouseX >= x + w) {
            return -1;
        }
        for (int i = 0; i < vrCount; i++) {
            if (mouseY >= vrTop[i] && mouseY < vrTop[i] + vrHeight[i]) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 输入：键盘 ====================

    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        // 搜索框聚焦：Esc = 清空并取消聚焦（不关界面），其余键全给它（否则按 E 会把界面关掉）
        if (searchBox != null && searchBox.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setValue("");
                query = "";
                searchBox.setFocused(false);
                searchFocused = false;
                return true;
            }
            searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (nameBox != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                finishRename(true);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                finishRename(false); // Esc = 取消（不提交），且不关界面
                return true;
            }
            nameBox.keyPressed(keyCode, scanCode, modifiers);
            return true; // 编辑态下吞掉全部按键：否则按 E / Esc 会把界面关掉
        }
        if (coordBoxes[0] != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                finishCoords(true);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                finishCoords(false);
                return true;
            }
            for (final EditBox box : coordBoxes) {
                if (box != null && box.isFocused()) {
                    box.keyPressed(keyCode, scanCode, modifiers);
                    break;
                }
            }
            return true;
        }
        return false;
    }

    /** 字符输入（原版只把 charTyped 路由给 children；自绘 EditBox 必须由 owner 转发到这里）。 */
    public boolean charTyped(final char codePoint, final int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(codePoint, modifiers);
        }
        if (nameBox != null) {
            return nameBox.charTyped(codePoint, modifiers);
        }
        if (coordBoxes[0] != null) {
            for (final EditBox box : coordBoxes) {
                if (box != null && box.isFocused()) {
                    return box.charTyped(codePoint, modifiers);
                }
            }
        }
        return false;
    }

    // ==================== 动作：选中 / 改名 / 坐标 / 删除 ====================

    /** 选中一行：发 SELECT（持久化选中项）+ 过滤上下文（哪一行在干活）+ 立即画世界框。 */
    private void selectRow(final int slot) {
        final int raw = vrRaw[slot];
        final int usable = vrUsable[slot];
        focusedRaw = raw;
        deleteArmedRaw = -1;
        deleteArmedAt = Long.MIN_VALUE;
        // 先提交上一行还没提交的输入（第 124 轮 2-2：切行不许把旧文字带进新行）
        finishRename(true);
        finishCoords(true);
        send(new AreaSelectorEditPacket(menu.containerId, AreaSelectorEditPacket.ACTION_SELECT,
            raw, AreaSelectorEditPacket.PLACEHOLDER));
        if (usable >= 0) {
            sendFilterContext(usable);
        }
    }

    /** 与 {@code AreaSelectorScreen#sendFilterContext} 同一条链路（服务端校验菜单 / 距离 / 越界）。 */
    private void sendFilterContext(final int usableIndex) {
        try {
            PacketDistributor.sendToServer(
                new SetAreaFilterContextPacket(menu.containerId, usableIndex));
        } catch (final RuntimeException error) {
            warn("区域过滤切换包未注册 ⇒ 本次点击只切换了界面上的高亮", error);
        }
    }

    /**
     * 「看」某一块地：{@link AreaOutline#showForView} 喂 5 秒 TTL（每个 rawIndex 一个独立 slot）。
     * <p>为什么用 {@code showForView} 而不是 {@code chase}：用户的实际动作是「点一下 → 关界面 → 抬头看」，
     * 所以框必须<b>在关掉 GUI 之后继续画完</b>（这正是「显示边框还是没用」的正解，
     * 与 {@link #removed()} 里同一段处理同源）。</p>
     */
    private void peekArea(final int raw) {
        final Area area = areaOfRaw(raw);
        if (area == null) {
            return;
        }
        if (AreaOutline.isDisabled()) {
            // 真话：描边渲染出口不可用（Ponder / Outliner 被禁用或替换）⇒ 别让玩家猜
            owner.stripHint("gui.rs_create_compat.advanced_collection_cache.show.failed");
            return;
        }
        AreaOutline.showForView(AreaOutline.slot("strip/peek/" + raw), area, AreaOutline.VIEW_TTL);
    }

    // ---------- 改名 ----------

    /**
     * 就地改名（契约「双击 / ✎」）。
     * <p>创建名称框的这一刻就把 {@code rawIndex} 记进 {@link #nameBoxRaw} /
     * {@link #pendingRenameIndex}：后面无论玩家点了哪一行、快照怎么变，这串字都只属于这一行。</p>
     */
    private void beginRename(final int raw) {
        if (!editable()) {
            owner.stripHint(LANG + "err.dimension");
            return;
        }
        Area area = null;
        for (final Row row : allRows) {
            if (row.rawIndex() == raw) {
                area = row.area();
            }
        }
        if (area == null) {
            return;
        }
        finishRename(false); // 丢掉上一条未提交的（不会有：rename 与坐标互斥）
        nameBox = new EditBox(font(), 0, 0, 40, NAME_LINE_H - 1, Component.empty());
        nameBox.setMaxLength(Area.MAX_NAME_LENGTH);
        nameBox.setTextShadow(false);
        nameBox.setBordered(true);
        nameBoxRaw = raw;
        pendingRenameIndex = raw;
        renameDirty = false;
        syncingName = true;
        try {
            nameBox.setValue(area.name() == null ? "" : area.name());
        } finally {
            syncingName = false;
        }
        nameBox.setResponder(text -> {
            if (syncingName) {
                return;
            }
            // 打字当场把「这串文字属于哪一行」再钉一次（捕获来源 = 名称框此刻代表的那一行，
            // 不是「当前选中行」）；之后无论玩家点了哪一行，提交都只认 pendingRenameIndex。
            pendingRenameIndex = nameBoxRaw;
            pendingRename = text == null ? "" : text;
            renameDirty = true;
        });
        nameBox.setFocused(true);
        placeNameBox();
    }

    /**
     * 结束改名。
     *
     * @param commit {@code true} = 提交（Enter / 失焦 / 关界面）；{@code false} = 取消（Esc）
     */
    private void finishRename(final boolean commit) {
        if (nameBox == null) {
            return;
        }
        final int raw = pendingRenameIndex;
        final String wanted = pendingRename;
        final boolean dirty = renameDirty;
        // **先整组复位再发包**：不存在「提交后还被下一帧重投」的窗口（第 124 轮 2-2 的放大器）
        nameBox = null;
        nameBoxRaw = -1;
        pendingRenameIndex = -1;
        pendingRename = "";
        renameDirty = false;
        if (!commit || !dirty || raw < 0) {
            return;
        }
        final Area current = areaOfRaw(raw);
        if (current == null) {
            return; // 那一行已经被删了：丢弃，绝不改投到别的行
        }
        final String oldName = current.name() == null ? "" : current.name();
        if (wanted.equals(oldName)) {
            return; // 没改：不发包（第 124 轮 2-1 的「空名字包」在结构上不可能发生）
        }
        send(new AreaSelectorEditPacket(menu.containerId, AreaSelectorEditPacket.ACTION_RENAME,
            raw, AreaSelectorEditPacket.PLACEHOLDER, wanted));
    }

    // ---------- 坐标 ----------

    /** 展开 / 收起某一行（展开 = 创建 6 个数字框；收起 = 先提交）。 */
    private void toggleExpand(final int raw) {
        if (expandedRaw == raw) {
            finishCoords(true);
            return;
        }
        finishCoords(true);
        final Area area = areaOfRaw(raw);
        if (area == null) {
            return;
        }
        if (!editable()) {
            owner.stripHint(LANG + "err.dimension");
            return;
        }
        expandedRaw = raw;
        pendingApplyIndex = -1;
        pendingApply = null;
        applyDirty = false;
        errorRaw = -1;
        errorKey = null;
        final int[] values = {area.minX(), area.minY(), area.minZ(),
            area.maxX(), area.maxY(), area.maxZ()};
        syncingCoords = true;
        try {
            for (int i = 0; i < coordBoxes.length; i++) {
                final EditBox box = new EditBox(font(), 0, 0, 24, FIELD_H, Component.empty());
                box.setMaxLength(RsccNumberField.MAX_LENGTH);
                box.setTextShadow(false);
                box.setBordered(true);
                box.setValue(Integer.toString(values[i]));
                // 解析口径只走 RsccNumberField.parse（与全工程同一个千分位 / 后缀 / 非法输入口径）。
                // 这里刻意**不用** RsccNumberField.wire：wire 只在「解析成功」时回调，而玩家把某个框
                // 删空（解析失败）时必须让「当前这 6 个数已经不合法」这件事传出去（否则回车会提交上一份
                // 合法值 —— 又一个「看着改了、其实写的是旧数」）。因此这里每个字符都回调一次，
                // 由 readAreaFromFields() 统一判合法。
                box.setResponder(text -> onCoordChanged());
                coordBoxes[i] = box;
            }
        } finally {
            syncingCoords = false;
        }
        coordBoxes[0].setFocused(true);
        placeCoordBoxes();
    }

    private void onCoordChanged() {
        if (syncingCoords) {
            return;
        }
        // 打字当场捕获「这 6 个数字属于哪一行」；提交时只认这个值
        pendingApplyIndex = expandedRaw;
        pendingApply = readAreaFromFields();
        applyDirty = true;
        if (pendingApply == null) {
            errorRaw = expandedRaw;
            errorKey = LANG + "err.number";
        } else {
            errorRaw = -1;
            errorKey = null;
        }
    }

    @Nullable
    private Area readAreaFromFields() {
        final int[] values = new int[6];
        for (int i = 0; i < 6; i++) {
            if (coordBoxes[i] == null) {
                return null;
            }
            final RsccNumberField.Parsed parsed = RsccNumberField.parse(coordBoxes[i].getValue());
            if (!parsed.ok() || parsed.value() > Area.WORLD_LIMIT
                || parsed.value() < -Area.WORLD_LIMIT) {
                return null;
            }
            values[i] = (int) parsed.value();
        }
        return new Area(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /** 结束坐标编辑（提交 / 取消）。 */
    private void finishCoords(final boolean commit) {
        if (coordBoxes[0] == null) {
            return;
        }
        final int raw = pendingApplyIndex >= 0 ? pendingApplyIndex : expandedRaw;
        // 提交这一刻**重新读一遍 6 个框**（readAreaFromFields 是唯一解析口径）：
        // 玩家可能把某个框删空后直接按回车（这类非法输入不一定进过 onCoordChanged），
        // 只认缓存值就会「回车没反应」或提交上一份值。
        final Area area = commit ? readAreaFromFields() : pendingApply;
        final boolean dirty = applyDirty;
        final String pendingError = errorKey;
        for (int i = 0; i < coordBoxes.length; i++) {
            coordBoxes[i] = null;
        }
        expandedRaw = -1;
        applyDirty = false;
        errorRaw = -1;
        errorKey = null;
        if (!commit || !dirty || raw < 0) {
            return;
        }
        if (area == null) {
            // 六个框里有不合法的数：收起编辑区，但**必须说出来**（否则回车「没反应」又是一次静默失效）
            owner.stripHint(pendingError != null ? pendingError : LANG + "err.number");
            return;
        }
        final Area current = areaOfRaw(raw);
        if (current == null) {
            return; // 那一行已不存在：丢弃（不改投）
        }
        // 客户端**不判断**区域可用性 / 合法性（契约 §4「数据源」）：越界、超单边、超体积一律交给
        // 服务端判（它才是权威），拒绝原因随 AdvancedAreaSnapshotPacket 回来，界面由
        // handleServerReject() 明说一句。这里只拦一种「肯定是玩家手滑」的情况：与已有区域几何重复。
        if (area.sameGeometry(current)) {
            return; // 一个数字都没改：不发包
        }
        for (final Row row : allRows) {
            if (row.rawIndex() != raw && row.area().sameGeometry(area)) {
                owner.stripHint(LANG + "err.duplicate");
                return;
            }
        }
        // REPLACE **必须带名字**（服务端保留旧名；契约 §4「坐标编辑」一行）
        send(new AreaSelectorEditPacket(menu.containerId, AreaSelectorEditPacket.ACTION_REPLACE,
            raw, area.withName(current.name()), ""));
    }

    // ---------- 删除 ----------

    /** ✕：第一次点武装（变成「确认删？」3 秒），第二次点才真的删。 */
    private void armOrDelete(final int raw) {
        if (deleteArmedRaw == raw && isArmed(deleteArmedAt)) {
            deleteArmedRaw = -1;
            deleteArmedAt = Long.MIN_VALUE;
            send(new AreaSelectorEditPacket(menu.containerId, AreaSelectorEditPacket.ACTION_DELETE,
                raw, AreaSelectorEditPacket.PLACEHOLDER));
            return;
        }
        deleteArmedRaw = raw;
        deleteArmedAt = System.currentTimeMillis();
    }

    private boolean isArmed(final long armedAt) {
        return armedAt != Long.MIN_VALUE && System.currentTimeMillis() - armedAt <= CONFIRM_MS;
    }

    private void expireArming() {
        if (deleteArmedRaw >= 0 && !isArmed(deleteArmedAt)) {
            deleteArmedRaw = -1;
            deleteArmedAt = Long.MIN_VALUE;
        }
    }

    // ==================== 关界面 ====================

    /**
     * 关界面（契约 §4「世界框生命周期」）：提交未决输入 → 清常驻世界框 → 关网格 → 清 JEI 矩形。
     * <p>注意与旧 {@code AreaSelectorScreen#removed()} 的差别：那边是「按剩余 TTL 重新武装」让框在
     * 关界面后继续画 5 秒；侧栏这边契约明确要求<b>清掉</b>
     * （「避免『关掉界面框还在』」），因为侧栏的框是「当前在干活」的常驻标记，
     * 界面一关它就不该再亮。</p>
     */
    public void removed() {
        finishRename(true);
        finishCoords(true);
        // 第 130 轮：**不再**直接 remove —— 玩家的实际动作是「点行 → 关界面 → 转头看那块地」，
        // 一关界面框就消失，看起来就是「显示边框根本没用」（用户第 4 条）。改成按 5 秒重新武装。
        final Area last = currentArea();
        if (last != null) {
            AreaOutline.showForView(FOCUS_SLOT, last, AreaOutline.VIEW_TTL);
        } else {
            AreaOutline.remove(FOCUS_SLOT);
        }
        clearShownAllOutlines();
        AreaStripBounds.clear();
        visible = false;
        scrollbar = null;
        sbHeight = -1;
        sbX = -1;
    }

    // ==================== 底部「显示全部」 ====================

    /**
     * 底部「显示全部」：把快照里<b>全部可用</b>区域一次性常亮 5 秒（= 旧「全显」）。
     * <p>契约 §4 明写「走 {@code showForView} + 5 秒 TTL」：这里直接对每个区域喂一次
     * {@link AreaOutline#showForView}（TTL = {@link AreaOutline#VIEW_TTL}），每个盒子一个独立 slot
     * （{@code strip/all/N}），关界面时按已喂过的个数清掉多余 slot。不自己维护第二套计时器：
     * Outliner 的 TTL 就是唯一口径。</p>
     */
    private void showAllAreas() {
        final AdvancedAreaSnapshotPacket snapshot = snapshotOrNull();
        if (snapshot == null) {
            owner.stripHint(LANG + "waiting");
            return;
        }
        if (snapshot.status() != 1) {
            // 区域为空 / 维度不符：本机一点不吸 ⇒ 一个框都不画（画框会谎报「正在吸」）
            owner.stripHint(snapshot.status() == 3
                ? "gui.rs_create_compat.advanced_collection_cache.info.dimension"
                : "gui.rs_create_compat.advanced_collection_cache.info.empty");
            return;
        }
        final List<Area> areas = new ArrayList<>(snapshot.usableCount());
        for (final AdvancedAreaSnapshotPacket.AreaEntry entry : snapshot.entries()) {
            if (entry.area() != null) {
                areas.add(entry.area());
            }
        }
        if (areas.isEmpty()) {
            owner.stripHint(LANG + "empty");
            return;
        }
        if (AreaOutline.isDisabled()) {
            owner.stripHint("gui.rs_create_compat.advanced_collection_cache.show.failed");
            return;
        }
        clearShownAllOutlines();
        for (int i = 0; i < areas.size(); i++) {
            AreaOutline.showForView(AreaOutline.slot("strip/all/" + i), areas.get(i),
                AreaOutline.VIEW_TTL);
        }
        shownAll = areas.size();
    }

    /** 清掉上一次「显示全部」喂过的所有 slot（对不存在的 slot 是空操作）。 */
    private void clearShownAllOutlines() {
        for (int i = 0; i < shownAll; i++) {
            AreaOutline.remove(AreaOutline.slot("strip/all/" + i));
        }
        shownAll = 0;
    }

    // ==================== 工具 ====================

    private Font font() {
        return minecraft.font;
    }

    private boolean editable() {
        final AreaSelectionView view = selectionView();
        return view.dimension == null
            || (minecraft.level != null && view.dimension.equals(minecraft.level.dimension().location()));
    }

    /**
     * 维度闸门用的只读视图。
     * <p>快照已经告诉界面「维度不符（{@code status == 3}）」与绑定维度字符串，这里不再自己算一套判据：
     * 客户端<b>只读快照</b>，服务端才是维度是否生效的权威。</p>
     */
    private record AreaSelectionView(@Nullable net.minecraft.resources.ResourceLocation dimension) {
    }

    private AreaSelectionView selectionView() {
        final AdvancedAreaSnapshotPacket snapshot = snapshotOrNull();
        final String text = snapshot == null ? "" : snapshot.dimension();
        final net.minecraft.resources.ResourceLocation id =
            text == null || text.isEmpty() ? null
                : net.minecraft.resources.ResourceLocation.tryParse(text);
        return new AreaSelectionView(id);
    }

    @Nullable
    private Area areaOfRaw(final int raw) {
        for (final Row row : allRows) {
            if (row.rawIndex() == raw) {
                return row.area();
            }
        }
        return null;
    }

    private void send(final net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        try {
            PacketDistributor.sendToServer(payload);
        } catch (final RuntimeException error) {
            warn("C2S 包未注册（主类缺 registerPayloadHandlers 条目）⇒ 本次操作只在客户端生效", error);
        }
    }

    private void warn(final String message, final RuntimeException error) {
        com.mojang.logging.LogUtils.getLogger().warn("[rs_create_compat] " + message, error);
    }
}
