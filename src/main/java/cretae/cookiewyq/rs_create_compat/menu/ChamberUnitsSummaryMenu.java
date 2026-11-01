package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.SequenceUnitPatternItem;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * 「执行仓单元样板汇总」菜单（<b>照精致存储（Refined Storage）自动合成管理器的设计理念重做</b>）。
 *
 * <p><b>参考的 RS 类</b>（理念来源，见 {@code local_src/external/RefinedStorage/refinedstorage-common/}）：</p>
 * <ul>
 *     <li>{@code common/autocrafting/monitor/AutocraftingMonitorScreen.java}：<b>可滚动条目列表 + 选中项 +
 *     底部「全部处理」按钮</b>的整体骨架（条目区给 {@code enableScissor}、选中项高亮、
 *     底部按钮只对当前选中项生效、所有提示手动 {@code setDeferredTooltip}/{@code renderTooltip}）。</li>
 *     <li>{@code common/autocrafting/monitor/AbstractAutocraftingMonitorContainerMenu.java}：<b>容器菜单持有
 *     动态数据 + 数据变化即回推</b>（{@code taskStatusChanged} / {@code removed} 解绑监听）。</li>
 *     <li>{@code common/autocrafting/patterngrid/PatternGridScreen.java} 与
 *     {@code common/grid/screen/AbstractGridScreen.java}：<b>真 {@link Slot} 网格</b> +
 *     点击取整叠 / Shift 快速移动 / 拖拽放置 + 手动 tooltip（{@code renderTooltip} 里逐个
 *     {@code Platform.renderTooltip}）的槽位交互范式。</li>
 * </ul>
 *
 * <p><b>本菜单如何把理念落到「跨方块的单元样板」上</b>：每台执行仓占一行，行内 4 个<b>真槽位</b>是
 * 该仓 54 格单元样板槽的一个<b>滑动窗口</b>（样板多于 4 张时按 {@link #POLL_TICKS} 轮询前移）；
 * 分页切换只是把各行的目标执行仓重新指过去，槽位下标保持不变，因此原版槽位同步（
 * {@code broadcastChanges} 的逐格 diff）会自动把新内容推给客户端，无需重发菜单。</p>
 *
 * <p><b>滑动窗口的点击安全（第 120 轮 F3）</b>：轮转只在「手上没拿东西 + 本行最近没被点过
 * （{@link #INTERACT_QUIET_TICKS}）+ 本行不在刚轮转的安全窗内（{@link #ROTATE_SETTLE_TICKS}）」
 * 时才发生；安全窗内该行窗口格上的点击一律不执行并立刻全量重同步，因此<b>不会出现「看着 A 点到 B」</b>。
 * 一次点击的取用与写回共用同一份映射快照（{@link #beginClickSnapshot()}），不会因为中途重建映射
 * 把「放回去」的样板挪到别的槽。</p>
 *
 * <p><b>服务端权威</b>：单元格的「放」统一走
 * {@link SequenceExecutionChamberBlockEntity#acceptsUnit(ItemStack)}（必须是单元样板 && 执行仓已绑定 &&
 * 配方类型匹配），与执行仓界面 / 手持右键放入<b>完全同一判定，不存在旁路</b>；
 * 批量「取回 / 存入」仍走既有的 {@code pullUnitsFrom} / {@code pushUnitsTo}。</p>
 */
public class ChamberUnitsSummaryMenu extends AbstractContainerMenu {
    // ==================== 几何（Menu 坐标；背景精灵坐标 = Menu 坐标 - 1） ====================
    /** 面板尺寸（同时作为界面 {@code imageWidth/Height}）。 */
    public static final int PANEL_W = 244;
    public static final int PANEL_H = 250;

    /** 行高 / 首行 y / 可见行数 / 每行格数。 */
    public static final int ROW_H = 22;
    public static final int ROW_Y0 = 20;
    public static final int VISIBLE_ROWS = 5;
    public static final int ROW_COLS = 4;
    /** 行内单元样板格：x 起点、相对行顶的 y 偏移、步距。 */
    public static final int ROW_SLOT_X = 150;
    public static final int ROW_SLOT_DY = 2;
    public static final int ROW_SLOT_GAP = 18;
    /** 行内机器图标 / 名字 / 配方 id 的 x 与相对行顶的 y 偏移。 */
    public static final int ROW_ICON_X = 8;
    public static final int ROW_ICON_DY = 2;
    public static final int ROW_TEXT_X = 28;
    public static final int ROW_NAME_DY = 2;
    public static final int ROW_ID_DY = 12;
    /** 行选中高亮带（整行，画在格子之下）。 */
    public static final int ROW_BAND_X = 6;
    public static final int ROW_BAND_W = 232;
    public static final int ROW_BAND_H = 20;

    /** 玩家背包 / 快捷栏（Menu 坐标）。 */
    public static final int PLAYER_SLOT_X = 41;
    public static final int PLAYER_SLOT_Y = 144;
    public static final int HOTBAR_SLOT_X = 41;
    public static final int HOTBAR_SLOT_Y = 204;

    /** 窗口格数（= 可见行 × 每行格数）；也是玩家背包段的起始下标。 */
    public static final int WINDOW_SLOTS = VISIBLE_ROWS * ROW_COLS;
    public static final int PLAYER_START = WINDOW_SLOTS;
    public static final int PLAYER_END = PLAYER_START + 36;

    /** 底部操作条（Menu 坐标）：「取回 / 存入」（作用于选中行，走既有 C2S 包）、翻页、关闭。 */
    public static final int BTN_PULL_X = 8;
    public static final int BTN_PUSH_X = 56;
    public static final int BTN_W = 46;
    public static final int BTN_Y = 228;
    public static final int BTN_H = 16;
    public static final int PAGE_PREV_X = 110;
    public static final int PAGE_NEXT_X = 130;
    public static final int PAGE_BTN_W = 18;
    public static final int PAGE_TEXT_X = 152;
    public static final int PAGE_TEXT_Y = 232;
    public static final int CLOSE_X = 188;
    public static final int CLOSE_W = 48;

    /** 提示行（网络为空 / 轮询说明 / 选中断言）的 y。 */
    public static final int HINT_Y = 132;

    /**
     * 轮询周期（<b>游戏时间</b> tick）：每 20 游戏 tick 把每行的样板窗口前移一格。
     * <p><b>为什么按游戏时间而不是 {@code broadcastChanges} 的调用次数（第 121 轮 ②）</b>：
     * 服务端每个 tick 会调一次 {@code broadcastChanges}，但<b>每次点击之后还会再调一次</b>
     * （{@code ServerGamePacketListenerImpl} 处理完容器点击就补一次同步）。旧实现把
     * {@code pollCounter} 当「调用次数」累加 ⇒ 调用次数 ≠ tick 数：玩家频繁点击时 20 次调用
     * 可能只跨了几 tick，轮转周期远短于 1 秒，界面比设计上「更晃」，也把
     * {@link #ROTATE_SETTLE_TICKS} 安全窗的相对占比放大（同样的操作更容易撞上被丢弃的点击）。
     * 改成按 {@link #nowTick()}（{@code level.getGameTime()}）计后，轮转节奏只由游戏时间决定，
     * 与点击频率彻底解耦 —— 玩家点得再快，窗口也是每 20 tick 动一格。</p>
     */
    public static final int POLL_TICKS = 20;

    /**
     * 「交互安静期」（服务端 tick）：玩家最近一次点过本行窗口格的时间落在这么短的窗口内时，
     * 本行<b>先不自动轮转</b>——把「取出来 / 放回去」这类连续操作留在同一份映射上。
     * <p>取值理由：本菜单最小的一次往返（左键拿 → 左键放回）通常在 250 ms 内，8 tick = 400 ms
     * 足够覆盖；轮转周期本身是 20 tick，安静期只推迟、不会让轮转看起来「停了」。</p>
     */
    public static final int INTERACT_QUIET_TICKS = 8;

    /**
     * 「轮转安全窗」（服务端 tick）：一行<b>刚轮转完</b>的这么多个 tick 内，该行窗口格上的点击
     * <b>一律不执行</b>（改为立即全量重同步）——这是「看着 A 点到 B」的根治闸。
     *
     * <p><b>为什么必须挡、而不是「改按轮转前的映射执行」</b>：客户端画出来的内容比服务端晚一个
     * 单程网络延迟，点击包跨在轮转那一拍两端时，服务端<b>无法区分</b>玩家看到的是轮转前还是轮转后
     * 的内容；按哪一份执行都有一半是错的。两害相权：<b>忽略一次点击</b>（紧随一次全量重同步，
     * 玩家立刻看到真值、可以再点）远好于<b>换给玩家另一张样板</b>（正是本轮要修的 bug）。</p>
     *
     * <p>4 tick = 200 ms 覆盖同机 / 局域网 / 一般联机的单程延迟；更差的网络下仍有极小残余窗口
     * （见 {@code docs/ROUND120_SUMMARY_WINDOW.md} 的未验证清单）。</p>
     */
    public static final int ROTATE_SETTLE_TICKS = 4;

    /** 界面标题语言键（菜单提供者与界面共用）。 */
    public static final String TITLE_KEY = "gui.rs_create_compat.sequence_pattern_terminal.summary.title";

    // ==================== 状态 ====================
    /** 服务端持有的终端方块实体；客户端重建菜单时为 null（内容由原版槽位同步写入本地镜像）。 */
    @Nullable
    private final SequencePatternTerminalBlockEntity terminal;
    /** 本页首台执行仓的全局下标 = {@code page * VISIBLE_ROWS}（<b>仅在服务端有意义</b>）。 */
    private int page;
    /**
     * 上一次「真正轮转」的游戏时刻（{@link #nowTick()} 口径；{@code -1} = 还没有基准）。
     * <p>第 121 轮 ②：这里原先是一个按 {@code broadcastChanges} <b>调用次数</b>累加的
     * {@code pollCounter}，而原版每次点击后会额外调一次 {@code broadcastChanges} ⇒
     * 点击越频繁轮转越快（周期短于设计值）。现在只记游戏时刻，判据是
     * {@code now - lastPollTick >= POLL_TICKS}，与调用次数无关。</p>
     */
    private long lastPollTick = -1L;
    /** 每行滑动窗口起点（服务端权威）。 */
    private final int[] rowStart = new int[VISIBLE_ROWS];
    /** 每行「非空单元样板槽」的压缩下标表与条目数（服务端每 tick 重建，映射真实槽位）。 */
    private final int[][] rowSlotMap = new int[VISIBLE_ROWS][SequenceExecutionChamberBlockEntity.UNIT_SLOTS];
    /** 压缩表条目数（含「洞」，用于窗口映射的下标取模）。 */
    private final int[] rowSlotCount = new int[VISIBLE_ROWS];
    /** 本行真实样板张数（不含「洞」，用于轮转判据：只有「样板多于 4 张」才值得轮转）。 */
    private final int[] rowPatternCount = new int[VISIBLE_ROWS];
    /**
     * 每行「最近一次被玩家点击」的 tick（{@link #nowTick()} 口径；{@code -1} = 从未）。
     * <p>用途见 {@link #shouldAdvanceRow}：最近交互过的行先不轮转（本轮 F3 的「方案 A」）。</p>
     */
    private final long[] rowLastClickTick = new long[VISIBLE_ROWS];
    /** 每行「最近一次自动轮转」的 tick（{@code -1} = 从未）；用于 {@link #isRotateSettling} 安全窗。 */
    private final long[] rowLastRotateTick = new long[VISIBLE_ROWS];

    /**
     * 每行「洞」：玩家刚刚从这个真实样板槽里把样板拿走、还没放回（本轮转周期内有效）。
     * <p><b>为什么需要</b>：窗口是「非空槽压缩后的表」，玩家拿走一格后压缩表会整体前移一位，
     * 于是同一格立刻显示成<b>相邻那张样板</b>；玩家「放回去」就会和相邻那张<b>交换</b>，
     * 样板落到同一台仓的另一个槽（第 119 轮复核 F3 明确点到的现象）。把被拿空的槽在本轮转周期内
     * 当作「空位」保留在压缩表里，其它格<b>一个都不动</b>，放回去自然落回原槽。</p>
     * <p>生命周期：下一次该行轮转 / 换页 / 该行的执行仓换了 ⇒ 清空（洞不跨周期，避免永久留空位）。</p>
     */
    private final BitSet[] rowHoles = new BitSet[VISIBLE_ROWS];

    /**
     * 一次点击内的映射快照（{@link #beginClickSnapshot()} / {@link #endClickSnapshot()} 维护）。
     * <p><b>为什么需要</b>：旧实现里 {@code removeItem} / {@code setItem} 都会当场
     * {@link #rebuildWindow()}，于是同一次点击的「取用」与「写回」可能落在<b>两份不同映射</b>上
     * ——「取出来又放回去」就会落到同一台仓的另一个槽。快照让一次点击从头到尾只看同一份
     * {@code rowStart / rowSlotCount / rowSlotMap}。</p>
     * <p>{@link #clickDepth} 处理嵌套（原版 Shift 点击 = {@code clicked} → {@code quickMoveStack}）。</p>
     */
    private int clickDepth;
    private boolean clickSnapshotActive;
    private final int[] snapStart = new int[VISIBLE_ROWS];
    private final int[] snapCount = new int[VISIBLE_ROWS];
    private final int[][] snapMap = new int[VISIBLE_ROWS][SequenceExecutionChamberBlockEntity.UNIT_SLOTS];
    /** 下一次 {@link #broadcastChanges()} 要做一次全量重同步（丢弃过一次「跨轮转的点击」）。 */
    private boolean pendingFullResync;
    /** 20 个窗口格共用的容器（服务端解析到真实执行仓，客户端落到本地镜像）。 */
    private final WindowContainer windowContainer;
    /**
     * 网络内执行仓的<b>缓存快照</b>（服务端每 tick 重建）。
     * <p>读取路径（{@code getItem} / {@code isEmpty}）每 tick 会被调用几十次，不能每次都遍历 RS 网络节点表，
     * 因此只在 {@link #rebuildWindow()}（构造 / 每 tick / 每次写入后）刷新。</p>
     */
    private List<SequenceExecutionChamberBlockEntity> chamberCache = List.of();

    public ChamberUnitsSummaryMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public ChamberUnitsSummaryMenu(final int id, final Inventory inventory,
                                   @Nullable final SequencePatternTerminalBlockEntity terminal) {
        super(RS_Create_Compat.CHAMBER_UNITS_SUMMARY_MENU.get(), id);
        this.terminal = terminal;
        this.windowContainer = new WindowContainer();
        // 「从未交互 / 从未轮转」用 -1 表示（0 是合法游戏时刻，不能用 0 当哨兵）
        Arrays.fill(this.rowLastClickTick, -1L);
        Arrays.fill(this.rowLastRotateTick, -1L);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            this.rowHoles[row] = new BitSet(SequenceExecutionChamberBlockEntity.UNIT_SLOTS);
        }

        // ① 执行仓单元样板窗口：5 行 × 4 格，全是真槽位（可点击取 / Shift 快速移动 / 拖拽放置）
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < ROW_COLS; col++) {
                final int indexInWindow = row * ROW_COLS + col;
                addSlot(new WindowSlot(windowContainer, indexInWindow,
                    ROW_SLOT_X + col * ROW_SLOT_GAP, ROW_Y0 + row * ROW_H + ROW_SLOT_DY));
            }
        }
        // ② 玩家背包 3×9 + 快捷栏 1×9
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9,
                    PLAYER_SLOT_X + col * 18, PLAYER_SLOT_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, HOTBAR_SLOT_X + col * 18, HOTBAR_SLOT_Y));
        }
        if (terminal != null) {
            rebuildWindow();
        }
    }

    public static ChamberUnitsSummaryMenu create(final int id, final Inventory inventory,
                                                 final SequencePatternTerminalBlockEntity terminal) {
        return new ChamberUnitsSummaryMenu(id, inventory, terminal);
    }

    /** 纯 getter：暴露本菜单持有的终端方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public SequencePatternTerminalBlockEntity getTerminal() {
        return terminal;
    }

    /**
     * 从玩家当前打开的容器解析「汇总所依附的终端」：终端菜单与汇总菜单都支持（其余返回 null）。
     * <p>汇总子界面是<b>独立容器</b>，打开后 {@code player.containerMenu} 已不再是终端菜单，
     * 因此既有的请求 / 取回 / 存入包必须经此统一解析，避免出现「子界面里所有操作都失效」。</p>
     */
    @Nullable
    public static SequencePatternTerminalBlockEntity terminalOf(@Nullable final AbstractContainerMenu menu) {
        if (menu instanceof SequencePatternTerminalMenu terminalMenu) {
            return terminalMenu.getTerminal();
        }
        if (menu instanceof ChamberUnitsSummaryMenu summaryMenu) {
            return summaryMenu.getTerminal();
        }
        return null;
    }

    /** 当前容器的分页（非汇总菜单返回 0）——供 S2C 回包带上权威页码。 */
    public static int pageOf(@Nullable final AbstractContainerMenu menu) {
        return menu instanceof ChamberUnitsSummaryMenu summaryMenu ? summaryMenu.getPage() : 0;
    }

    /** 当前页（服务端权威；客户端请用 S2C 回包里的页码）。 */
    public int getPage() {
        return Math.max(0, Math.min(page, maxPage()));
    }

    /** 服务端：切换分页（越界自动收敛）。 */
    public void setPage(final int newPage) {
        page = Math.max(0, Math.min(newPage, maxPage()));
        // 换页 = 每行的目标执行仓全变了：上一页留下的「洞」下标不再有意义
        clearAllHoles();
        rebuildWindow();
    }

    /** 最大页下标（按网络内执行仓台数计算；客户端 chambers 为空因此恒为 0，不会被使用）。 */
    public int maxPage() {
        return Math.max(0, (chambers().size() - 1) / VISIBLE_ROWS);
    }

    /** 网络内全部执行仓（服务端缓存的快照，见 {@link #refreshChambers()}；客户端为空列表）。 */
    public List<SequenceExecutionChamberBlockEntity> chambers() {
        return chamberCache;
    }

    /** 第 row 行对应的执行仓（越界 / 客户端返回 null）。 */
    @Nullable
    SequenceExecutionChamberBlockEntity rowChamber(final int row) {
        if (row < 0 || row >= VISIBLE_ROWS) {
            return null;
        }
        final List<SequenceExecutionChamberBlockEntity> chambers = chambers();
        final int index = getPage() * VISIBLE_ROWS + row;
        return index >= 0 && index < chambers.size() ? chambers.get(index) : null;
    }

    /**
     * 第 row 行第 col 格映射到的<b>真实单元样板槽下标</b>；
     * 无仓 / 该仓一张样板都没有 / 该格超出本行样板张数（不足 4 张时的空位）时返回 -1（表示该格为空）。
     * <p>窗口只压缩「有样板的槽」，因此样板稀疏（例如只用了第 1、第 30 格）轮询才有意义。</p>
     * <p><b>映射只用一次快照</b>：点击处理期间（{@link #clickSnapshotActive}）一律读
     * {@link #snapStart} / {@link #snapCount} / {@link #snapMap}，保证同一次点击的「取用」与
     * 「写回」指向同一张样板（旧实现在 {@code setItem} 里先 {@code rebuildWindow()} 再按新映射写，
     * 会把放回去的样板挪到另一个槽）。</p>
     */
    int mapToUnitSlot(final int row, final int col) {
        if (row < 0 || row >= VISIBLE_ROWS || col < 0 || col >= ROW_COLS) {
            return -1;
        }
        final boolean snapshot = clickSnapshotActive;
        final int count = snapshot ? snapCount[row] : rowSlotCount[row];
        final int index = windowIndex(snapshot ? snapStart[row] : rowStart[row], col, count);
        if (index < 0) {
            return -1;
        }
        final int[] map = snapshot ? snapMap[row] : rowSlotMap[row];
        return index < map.length ? map[index] : -1;
    }

    // ==================== 纯函数：窗口轮转 / 映射（自检可直接复刻） ====================

    /**
     * 窗口格 → 压缩下标表里的第几项（<b>纯函数</b>）。
     * <p>本行样板不足 {@link #ROW_COLS} 张时，超出的格返回 {@code -1}（空位）——
     * 旧实现用 {@code floorMod(start + col, count)} 直接取模，会导致「只有 1 张样板时 4 格显示同一张、
     * 只有 2 张时两两重复」；而 {@code WindowContainer#setItem} 里「空格子 ⇒ 放进第一个空样板槽」
     * 那条分支正是按「不足 4 张 ⇒ 有空位」写的，两者口径现在一致了。</p>
     */
    static int windowIndex(final int start, final int col, final int count) {
        if (count <= 0 || col < 0 || col >= count || col >= ROW_COLS) {
            return -1;
        }
        return Math.floorMod(start + col, count);
    }

    /**
     * 一行窗口本轮该不该前移一格（<b>纯函数</b>，把「暂停条件」集中成一条可复刻的判据）。
     * <ul>
     *     <li>本行样板不比 4 格多 ⇒ 不轮转（全看得见，轮转只是无意义地晃动，见设计文档 §7.3）；</li>
     *     <li>玩家手上拿着东西 ⇒ 不轮转（原有限制，保留）；</li>
     *     <li>玩家最近 {@link #INTERACT_QUIET_TICKS} 内点过本行 ⇒ 不轮转（本轮新增）；</li>
     *     <li>本行上一次轮转还在 {@link #ROTATE_SETTLE_TICKS} 安全窗内 ⇒ 不轮转（本轮新增）。</li>
     * </ul>
     */
    static boolean shouldAdvanceRow(final int slotCount, final boolean carriedEmpty, final long now,
                                    final long lastClickTick, final long lastRotateTick) {
        if (slotCount <= ROW_COLS || !carriedEmpty) {
            return false;
        }
        if (lastClickTick >= 0 && now - lastClickTick < INTERACT_QUIET_TICKS) {
            return false;
        }
        return !isRotateSettling(now, lastRotateTick);
    }

    /** 前移一格（<b>纯函数</b>）：起点始终留在 {@code 0..count-1}，不会溢出成负数。 */
    static int advanceStart(final int start, final int count) {
        return count <= 0 ? 0 : Math.floorMod(start + 1, count);
    }

    /**
     * 该行是否处在「刚轮转完、客户端可能还没收到新映射」的安全窗内（<b>纯函数</b>）。
     * <p>安全窗内该行窗口格上的点击一律不执行（见 {@link #ROTATE_SETTLE_TICKS}）。</p>
     */
    static boolean isRotateSettling(final long now, final long lastRotateTick) {
        return lastRotateTick >= 0 && now - lastRotateTick <= ROTATE_SETTLE_TICKS;
    }

    /** 窗口格下标属于哪一行；不是本菜单窗口格（或越界）返回 -1。 */
    static int windowRowOf(final int slotId) {
        return slotId >= 0 && slotId < WINDOW_SLOTS ? slotId / ROW_COLS : -1;
    }

    /**
     * 服务端当前游戏时刻（tick）。
     * <p>客户端菜单没有方块实体引用 / 方块实体不在任何维度里时返回 {@code -1}（= 时刻未知）。
     * 用哨兵而不是 0，是为了避免「时钟从 0 开始」与「未知」混淆：未知时所有时间窗口判据一律
     * 按「不满足」处理（宁可这拍不轮转，也不能拿 {-1} 去算出诡异的「刚交互过」）。</p>
     */
    private long nowTick() {
        return terminal == null || terminal.getLevel() == null
            ? -1L : terminal.getLevel().getGameTime();
    }

    /** 开始一次点击的映射快照（可嵌套：{@code clicked} → {@code quickMoveStack} 只快照一次）。 */
    private void beginClickSnapshot() {
        if (terminal == null || clickDepth++ > 0) {
            return;
        }
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            snapStart[row] = rowStart[row];
            snapCount[row] = rowSlotCount[row];
            System.arraycopy(rowSlotMap[row], 0, snapMap[row], 0, snapMap[row].length);
        }
        clickSnapshotActive = true;
    }

    /** 结束一次点击的映射快照；最外层结束时恢复「按最新映射读」。 */
    private void endClickSnapshot() {
        if (terminal == null || clickDepth <= 0) {
            return;
        }
        if (--clickDepth == 0) {
            clickSnapshotActive = false;
        }
    }

    /** 该执行仓单元样板槽的第一个空格（全满返回 -1）。 */
    static int firstEmptyUnitSlot(final SequenceExecutionChamberBlockEntity chamber) {
        for (int i = 0; i < chamber.unitSlots.getContainerSize(); i++) {
            if (chamber.unitSlots.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 刷新执行仓缓存快照（客户端 / 未接入网络 → 空列表）；某一行换了执行仓时该行的洞作废。 */
    private void refreshChambers() {
        final List<SequenceExecutionChamberBlockEntity> next =
            terminal == null ? List.of() : terminal.networkChambers();
        if (!chamberCache.equals(next)) {
            // 分页边界 / 网络内增删执行仓会改变「第 row 行是谁」，旧洞的下标就失去意义了
            clearAllHoles();
        }
        chamberCache = next;
    }

    /** 清空所有行的「洞」（轮转 / 换页 / 行换了执行仓时调用）。 */
    private void clearAllHoles() {
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            rowHoles[row].clear();
        }
    }

    /** 重建每行的压缩窗口映射（服务端：每 tick + 任何写入后调用，外部改动最多落后 1 tick）。 */
    void rebuildWindow() {
        refreshChambers();
        final List<SequenceExecutionChamberBlockEntity> chambers = chamberCache;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            rowSlotCount[row] = 0;
            rowPatternCount[row] = 0;
            final int index = getPage() * VISIBLE_ROWS + row;
            if (index < 0 || index >= chambers.size()) {
                rowHoles[row].clear();
                continue;
            }
            final Container unitSlots = chambers.get(index).unitSlots;
            final BitSet holes = rowHoles[row];
            final int[] map = rowSlotMap[row];
            int count = 0;
            int patterns = 0;
            for (int i = 0; i < unitSlots.getContainerSize() && count < map.length; i++) {
                // 只把「单元样板」放进窗口：序列装配总样板不属于任何单元样板界面
                // （与「单元样板管理舱不显示总样板」同一口径、同一类型判定；旧存档里可能有，读档不做校验）。
                // 窗口是每 tick 由服务端重建的派生视图（映射表而非固定下标契约），因此过滤不涉及两端下标对应问题。
                final boolean pattern = SequenceUnitPatternItem.isUnitPattern(unitSlots.getItem(i));
                if (pattern) {
                    // 洞被重新填上（玩家放回 / 别的来源写入）⇒ 洞作废，按普通样板进表
                    holes.clear(i);
                    map[count++] = i;
                    patterns++;
                } else if (holes.get(i)) {
                    // 「拿走还没放回」的槽在本轮转周期内仍占一个位置 ⇒ 该格显示为空，
                    // 其余格一个都不动（旧实现会整体前移，导致「放回去」落到相邻槽）
                    map[count++] = i;
                }
            }
            rowSlotCount[row] = count;
            rowPatternCount[row] = patterns;
        }
    }

    /**
     * 服务端每 tick 的槽位同步（原版机制调用）：<b>先按闸轮转窗口、再重建映射、最后交父类做逐格 diff</b>。
     * <p>轮转的闸门集中在纯函数 {@link #shouldAdvanceRow}：手上拿着东西、本行刚被点过、本行刚轮转过，
     * 三种情况都不前移，避免「看着 A 却点到 B」。</p>
     * <p><b>周期按游戏时间计（第 121 轮 ②）</b>：本方法每 tick 被调一次、<b>每次点击后还会被补调一次</b>
     * （原版容器点击处理完会立刻同步），所以「调用次数」不等于「tick 数」。轮转只由
     * {@code now - lastPollTick >= POLL_TICKS} 决定，点击频率不再影响节奏。</p>
     */
    @Override
    public void broadcastChanges() {
        if (terminal != null) {
            final long now = nowTick();
            // now < 0 = 时刻未知（拿不到方块实体的 level）：这一拍不轮转，也绝不用 -1 去更新
            // lastPollTick / rowLastRotateTick —— 否则行状态会被写成「从未轮转」的假值，
            // 之后所有时间窗判据都按「没有上一次轮转 / 没有交互」处理，安全窗会凭空失效。
            if (now >= 0) {
                if (lastPollTick < 0) {
                    // 第 121 轮 ②：首次拿到有效时刻只对齐基准、**不**轮转 ——
                    // 与旧实现「第 20 次调用才第一次轮转」等价，界面刚打开的那一拍不会先跳一格。
                    lastPollTick = now;
                } else if (now - lastPollTick >= POLL_TICKS) {
                    lastPollTick = now;
                    final boolean carriedEmpty = getCarried().isEmpty();
                    for (int row = 0; row < VISIBLE_ROWS; row++) {
                        // 轮转判据用「真实样板张数」：留了一个洞不该让只有 4 张样板的行开始晃
                        if (!shouldAdvanceRow(rowPatternCount[row], carriedEmpty, now,
                            rowLastClickTick[row], rowLastRotateTick[row])) {
                            continue;
                        }
                        rowStart[row] = advanceStart(rowStart[row], rowPatternCount[row]);
                        rowLastRotateTick[row] = now;
                        // 一轮转就是「洞」周期的边界：不再保留「拿走没放回」的空位
                        rowHoles[row].clear();
                    }
                }
            }
            rebuildWindow();
            if (pendingFullResync) {
                // 上一次点击因为「跨在轮转那一拍」被丢弃：这里把真值一次性推给客户端
                // （内容 + 光标），客户端随即看到正确内容，且不会把这次点击的结果记在本地。
                pendingFullResync = false;
                broadcastFullState();
                return;
            }
        }
        super.broadcastChanges();
    }

    /**
     * 原版点击入口：<b>先记「本行被点过」的时间戳，再判断该行是否处在轮转安全窗，最后才真正执行</b>。
     * <p>三条新语义（第 120 轮 F3，见 {@code docs/ROUND120_SUMMARY_WINDOW.md}）：</p>
     * <ol>
     *     <li>点击本行窗口格 ⇒ 记时间戳 ⇒ 该行随后的自动轮转被推迟
     *     {@link #INTERACT_QUIET_TICKS} tick（{@link #shouldAdvanceRow}）；</li>
     *     <li>该行刚轮转完（{@link #ROTATE_SETTLE_TICKS} 内）⇒ <b>本次点击不执行</b>，改为立刻全量重同步。
     *     宁可不响应，也绝不把「A 格」点成相邻那张样板；</li>
     *     <li>其余情况：整次点击（含原版内部间接调用的 {@link #quickMoveStack}）共用一份映射快照，
     *     「取用」与「写回」永远指向同一张样板。</li>
     * </ol>
     * <p>客户端菜单（{@code terminal == null}）没有轮转、也没有方块实体，原样放行。</p>
     */
    @Override
    public void clicked(final int slotId, final int button, final ClickType clickType, final Player player) {
        if (terminal == null) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        final long now = nowTick();
        final int row = windowRowOf(slotId);
        if (row >= 0) {
            rowLastClickTick[row] = now;
            if (now >= 0 && isRotateSettling(now, rowLastRotateTick[row])) {
                pendingFullResync = true;
                if (clickType == ClickType.QUICK_CRAFT) {
                    // 拖拽的中间态存在菜单里，跳过一整步会让它卡在「拖拽中」：显式复位
                    resetQuickCraft();
                }
                return;
            }
        }
        beginClickSnapshot();
        try {
            super.clicked(slotId, button, clickType, player);
        } finally {
            endClickSnapshot();
        }
    }

    /** Shift 快速移动：窗口 → 玩家背包；玩家背包 → 各执行仓窗口（放不放得下由槽位权威判定）。 */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        // 从窗口格拿走样板同样是「一次交互」：轮转要因此让路（安全窗由 clicked 统一拦）
        final int row = windowRowOf(index);
        if (row >= 0 && terminal != null) {
            rowLastClickTick[row] = nowTick();
        }
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stackInSlot = slot.getItem();
        final ItemStack result = stackInSlot.copy();
        if (index < PLAYER_START) {
            if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stackInSlot, 0, PLAYER_START, false)) {
            // 放不进任何执行仓（不是单元样板 / 配方类型不匹配 / 全满）：保持原样
            return ItemStack.EMPTY;
        }
        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(final Player player) {
        if (terminal == null || terminal.getLevel() == null) {
            return true;
        }
        // 与终端菜单同一套有效性判定（含「高级远程终端」的虚拟方块实体特例）：
        // 判据是唯一的哨兵坐标（纯常量比较，不读世界 / 不查区块 / 不看玩家位置）
        if (cretae.cookiewyq.rs_create_compat.support.RsccVirtualTerminalBe.isVirtual(terminal)) {
            return true;
        }
        return terminal.getLevel().getBlockEntity(terminal.getBlockPos()) == terminal
            && player.distanceToSqr(terminal.getBlockPos().getX() + 0.5,
            terminal.getBlockPos().getY() + 0.5,
            terminal.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    // ==================== 窗口容器（服务端解析真实仓 / 客户端本地镜像） ====================

    /**
     * 20 个窗口格共用的容器。
     * <p><b>为什么用「真 {@link Container}」而不是逐槽覆写</b>：原版 {@link Slot} 对容器的写入只有
     * {@code container.setItem(index, stack)} 一条路径（{@code Slot#set} / {@code safeInsert} /
     * 客户端同步都汇聚到它），把映射放在容器里就<b>天然覆盖点击、Shift、拖拽、同步</b>四种路径，
     * 不会因为某个覆写点漏掉而丢内容。</p>
     */
    private final class WindowContainer implements Container {
        /** 客户端镜像（客户端菜单没有方块实体引用，内容由原版槽位同步写入）。 */
        private final SimpleContainer mirror = new SimpleContainer(WINDOW_SLOTS);

        private boolean isMirror() {
            return terminal == null;
        }

        @Override
        public int getContainerSize() {
            return WINDOW_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < WINDOW_SLOTS; i++) {
                if (!getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(final int index) {
            if (isMirror()) {
                return mirror.getItem(index);
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            final int unitSlot = mapToUnitSlot(index / ROW_COLS, index % ROW_COLS);
            return chamber == null || unitSlot < 0 ? ItemStack.EMPTY : chamber.unitSlots.getItem(unitSlot);
        }

        @Override
        public ItemStack removeItem(final int index, final int amount) {
            if (isMirror()) {
                return mirror.removeItem(index, amount);
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            final int unitSlot = mapToUnitSlot(index / ROW_COLS, index % ROW_COLS);
            if (chamber == null || unitSlot < 0) {
                return ItemStack.EMPTY;
            }
            final ItemStack removed = chamber.unitSlots.removeItem(unitSlot, amount);
            if (!removed.isEmpty()) {
                if (chamber.unitSlots.getItem(unitSlot).isEmpty()) {
                    // 这一格被拿空了：在本轮转周期内留作「洞」，让「拿起又放回」落回原槽
                    // （否则压缩表前移一位，放回去会和相邻那张样板交换位置）
                    rowHoles[index / ROW_COLS].set(unitSlot);
                }
                chamber.setChanged();
                rebuildWindow();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(final int index) {
            return removeItem(index, Integer.MAX_VALUE);
        }

        @Override
        public void setItem(final int index, final ItemStack stack) {
            final ItemStack value = stack == null ? ItemStack.EMPTY : stack;
            if (isMirror()) {
                mirror.setItem(index, value);
                return;
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            if (chamber == null) {
                return;
            }
            int unitSlot = mapToUnitSlot(index / ROW_COLS, index % ROW_COLS);
            if (unitSlot < 0) {
                if (value.isEmpty()) {
                    return;
                }
                // 空格子（该行样板不足 4 张时的空位）：放进该仓第一个空样板槽
                unitSlot = firstEmptyUnitSlot(chamber);
                if (unitSlot < 0) {
                    return; // 仓满：绝不覆盖已有样板
                }
            }
            chamber.unitSlots.setItem(unitSlot, value);
            chamber.setChanged();
            rebuildWindow();
        }

        /** 内容变化由 {@link #setItem} / {@link #removeItem} 自行落盘并重建映射，这里无需再做。 */
        @Override
        public void setChanged() {
        }

        /**
         * <b>刻意实现为空操作</b>：{@link Container#clearContent()} 是「清空容器」的通用路径
         * （例如玩家死亡掉落物品时会对容器逐个调用）。若照做，会把执行仓里的单元样板直接抹掉 ——
         * 违反本工程「绝不允许销毁玩家资源」的硬规则。真正的内容移除只允许经
         * {@link #removeItem}（返回被取出的物品，由原版交还玩家 / 光标）发生。
         */
        @Override
        public void clearContent() {
        }

        @Override
        public boolean stillValid(final Player player) {
            return true;
        }

        /** 单元样板一格一张（窗口格一律不叠加）。 */
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        /**
         * 能不能放进第 index 格 —— <b>统一硬约束</b>：必须是本模组的单元样板，且该仓
         * {@link SequenceExecutionChamberBlockEntity#acceptsUnit(ItemStack)} 通过。
         * <p>客户端没有方块实体引用，只做「必须是单元样板」的前置拦截，权威判定始终在服务端。</p>
         */
        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            // 类型判定走「单元样板」这一条共享口径（不看名字 / 文案；总样板在这里同样进不来）
            if (!SequenceUnitPatternItem.isUnitPattern(stack)) {
                return false;
            }
            if (isMirror()) {
                return true;
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            return chamber != null && chamber.acceptsUnit(stack);
        }
    }

    /** 窗口格的 {@link Slot}：只收紧「可放 / 可拿 / 是否有效 / 叠加上限」，取放逻辑全部落在 {@link WindowContainer}。 */
    private final class WindowSlot extends Slot {
        private WindowSlot(final Container container, final int indexInWindow, final int x, final int y) {
            super(container, indexInWindow, x, y);
        }

        /** 服务端：本行没有执行仓时该格不可交互（不可悬停 / 不可点）；客户端一律可见（内容由同步写入）。 */
        @Override
        public boolean isActive() {
            return terminal == null || rowChamber(getContainerSlot() / ROW_COLS) != null;
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return this.container.canPlaceItem(this.getContainerSlot(), stack);
        }

        @Override
        public boolean mayPickup(final Player player) {
            return true;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(final ItemStack stack) {
            return 1;
        }
    }
}
