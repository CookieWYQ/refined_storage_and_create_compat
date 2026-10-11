package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 序列装配样板终端菜单（v10：底部左侧 <b>1 格总样板槽</b>（只出不进）+ <b>6 格单元样板槽窗口</b>
 * （滚动条 / 滚轮翻页），整排水平居中）。
 * <p><b>理念</b>：单元样板主要由导入 / 生成产生，流程编排只读；终端保留一个 <b>6 格单元样板窗口</b>
 * （{@link SequencePatternTerminalBlockEntity#unitLibrary} 的一页），让玩家能在终端里直接取用
 * 生成出来的单元样板（见 {@code SPT_GUI_TECH_DOC_V7.md} §3.6/§5.5）。
 * <b>旧数据不丢</b>：终端方块实体自带的单元样板库（NBT 键 {@code UnitLibrary}）<b>原样保留</b>，
 * 并作为「终端单元样板库」分组出现在单元样板管理舱里，玩家可直接取出并搬进执行舱。</p>
 * <p><b>导入 vs 生成（v8 核心、v10 不变）</b>：导入配方只写终端里的<b>展示数据</b>（流程 / 原料 / 产物 / 废料照常显示，
 * 不产出任何真实物品）；点「生成样板」才真正产出：总样板 1 张（落到<b>唯一</b>那格总样板槽）
 * + 每一列（相邻相同步骤已合并）1 张单元样板（进单元样板库），并按产出张数消耗 {@code refinedstorage:pattern}
 * （不足则什么都不做，按钮在界面上直接禁用并给出「缺几样」的 tooltip）。</p>
 * <p><b>槽位下标分配（连续、无占位、无复用）</b>：
 * <pre>
 *  0         输入原料标记槽（ghost，不可取出）
 *  1 .. 8    流程编排滚动窗口（8 行，行槽框由界面自绘）
 *  9 .. 20   产物（4 × 3 = 12 可见，只读 ghost 标记）
 *  21 .. 32  废料（4 × 3 = 12 可见，只读 ghost 标记）
 *  33 .. 38  单元样板槽窗口（6 格；映射到终端单元样板库的第「页 × 6 + 格」格）
 *  39 .. 65  玩家背包（3 × 9）
 *  66 .. 74  快捷栏（1 × 9）
 *  75        总样板槽（<b>恰好 1 格</b>；只出不进，几何在底部左侧 Menu (37, 213)，槽框烘焙在背景里）
 * </pre>
 * <p><b>单元样板槽 = 单元样板库的窗口（v7）</b>：单元样板库无上限，界面一次只看 6 格，
 * 由底部那条小滚动条 / 在槽区滚轮翻页。第 k 页第 i 格 ↔ 库下标 {@code k*6+i} 的换算<b>只有一份</b>
 * （{@link SequencePatternTerminalBlockEntity#unitLibraryIndex}），服务端槽位与界面判定共用同一份，
 * 避免「界面显示的格」与「服务端取走的格」错位。翻页页码经 ContainerData 由服务端下发，
 * 客户端不自行换算库下标，因此两端的槽位语义始终一致。
 * <p>按钮 id：42 = 生成样板，43/44 = 单元样板窗口上一页 / 下一页（滚轮），
 * {@link #BTN_UNIT_PAGE_SET} + N = 直接把页码设到第 N 页（滚动条拖动；服务端权威并夹到合法区间）。
 * 流程完全由配方生成，行次数 ± / 删除该步 / 上移下移等按钮 id 一律不再处理。</p>
 */
public class SequencePatternTerminalMenu extends AbstractContainerMenu {
    /** 流程编排滚动窗口高度（可见行数）。 */
    public static final int ARRANGEMENT_WINDOW = SequencePatternTerminalBlockEntity.ARRANGEMENT_WINDOW;
    /** 产物/废料可见窗口（4 列 × 3 行）。 */
    public static final int RESULT_WINDOW = 12;
    public static final int SCRAP_WINDOW = 12;

    // ========== 槽位下标常量 ==========
    /** 输入原料标记槽（唯一操作口）。 */
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_ARRANGEMENT_START = SLOT_INPUT + 1;
    public static final int SLOT_ARRANGEMENT_END = SLOT_ARRANGEMENT_START + ARRANGEMENT_WINDOW;
    public static final int SLOT_RESULT_START = SLOT_ARRANGEMENT_END;
    public static final int SLOT_RESULT_END = SLOT_RESULT_START + RESULT_WINDOW;
    public static final int SLOT_SCRAP_START = SLOT_RESULT_END;
    public static final int SLOT_SCRAP_END = SLOT_SCRAP_START + SCRAP_WINDOW;
    /**
     * 界面暴露的总样板格数（v10 = <b>1</b>：底部左侧<b>唯一</b>一格总样板槽）。
     * <p><b>为什么底层容器仍是 9 格</b>（{@code patternSlots}，NBT 键不变、掉落清单不变、物流能力照旧）：
     * 老存档第 2~9 格的样板必须照常登记、照常掉落，否则就是销毁玩家物品。界面只暴露第 1 格，
     * 该格被取空时后面的样板会自动前移（见 {@code compactPatternSlots()}），玩家能一张张全部取回。</p>
     */
    public static final int PATTERN_WINDOW = SequencePatternTerminalBlockEntity.PATTERN_VISIBLE;
    /** 单元样板槽窗口（v9 扩到 6 格）：一次可见 {@code UNIT_WINDOW} 格，映射单元样板库的翻页窗口。 */
    public static final int UNIT_WINDOW = SequencePatternTerminalBlockEntity.UNIT_WINDOW;
    public static final int SLOT_UNIT_PATTERN_START = SLOT_SCRAP_END;
    public static final int SLOT_UNIT_PATTERN_END = SLOT_UNIT_PATTERN_START + UNIT_WINDOW;
    public static final int SLOT_PLAYER_START = SLOT_UNIT_PATTERN_END;
    /**
     * 玩家<b>主背包</b>段结束（27 格 = 3×9）。
     * <p>注意：这里必须是 27 而不是 36 —— 快捷栏 9 格是紧跟其后单独 addSlot 的。</p>
     */
    public static final int SLOT_PLAYER_END = SLOT_PLAYER_START + 27;
    public static final int SLOT_HOTBAR_START = SLOT_PLAYER_END;
    public static final int SLOT_HOTBAR_END = SLOT_HOTBAR_START + 9;
    /**
     * 总样板槽（{@link #PATTERN_WINDOW} = 1 格，也是界面唯一的总样板格）：Menu (37, 213)，
     * 槽框<b>烘焙在背景里</b>。
     * <p><b>只出不进</b>：点「生成样板」产出的总样板落在这一格；玩家只能取走，
     * 不能手动放入（用户要求「不可手动放入（不能显示为『可以使用 / 可放入』）」，
     * 由 {@link TotalPatternSlot#mayPlace} 恒 false 拦住所有放入路径）。
     * 它们刻意排在最后 addSlot：既有槽位下标全部保持不变，只有它们自己占新的下标。</p>
     */
    public static final int SLOT_TOTAL_PATTERN_START = SLOT_HOTBAR_END;
    public static final int SLOT_TOTAL_PATTERN_END = SLOT_TOTAL_PATTERN_START + PATTERN_WINDOW;
    /**
     * 终端自有的<b>样板输入槽</b>（3 格，只收 {@code refinedstorage:pattern}）：生成样板时的耗材来源。
     * <p><b>为什么单独一组</b>：用户要求「样板应放进该终端自己的槽位并持久化，玩家不需要把样板背在身上」。
     * 这 3 格是<b>耗材入口</b>，与底部「总样板槽」（产出 / 登记总样板）职责分离，避免一格里既当输入又当输出。
     * 它们刻意排在最后 addSlot：既有槽位下标全部保持不变。几何上占用删除「导入」按钮后腾出的位置。
     */
    public static final int SLOT_RS_PATTERN_START = SLOT_TOTAL_PATTERN_END;
    public static final int SLOT_RS_PATTERN_END =
        SLOT_RS_PATTERN_START + SequencePatternTerminalBlockEntity.RS_PATTERN_SLOT_SIZE;
    /** 本菜单的槽位总数（含 1 格总样板槽与 3 格样板输入槽）。 */
    public static final int SLOT_COUNT = SLOT_RS_PATTERN_END;

    // ========== 按钮 id ==========
    /** 生成样板（生成装配样板并写入装配样板槽，随后由菜单转交玩家）。 */
    public static final int BTN_GENERATE = 42;
    /** 循环 -/+ 的旧按钮 id：循环次数改为「配方给定、只读」，界面上已无对应控件（保留常量避免 id 复用）。 */
    public static final int BTN_LOOPS_MINUS = 40;
    public static final int BTN_LOOPS_PLUS = 41;
    /**
     * 单元样板窗口翻页（v7）：◀ / ▶（滚轮用）。
     * <p><b>为什么翻页走原版按钮通道</b>：界面一次只显示 {@link #UNIT_WINDOW} 格，而槽位映射是<b>服务端权威</b>的
     * （服务端要按「页」把点击解析到正确的库下标，否则会搬错格子 = 动到玩家的另一张样板）。
     * 这里复用既有的 {@code ServerboundContainerButtonClickPacket}（与「生成样板」同一条通路），
     * 不新增任何自定义包；页数 / 页码再由 ContainerData 回传，客户端据此显示。</p>
     */
    public static final int BTN_UNIT_PAGE_PREV = 43;
    public static final int BTN_UNIT_PAGE_NEXT = 44;
    /**
     * 滚动条拖动：把单元样板窗口页码<b>直接设到第 N 页</b>（按钮 id = 本基址 + N）。
     * <p><b>为什么不用一串 ±1</b>：滚动条的「点轨道 / 拖滑块」是<b>绝对定位</b>语义（一步到位跳到某一页），
     * 用 ±1 需要连点很多次、且无法表达「跳到最后一页」。这里把目标页码编进按钮 id，
     * 服务端仍然只信自己的页数上限（{@link #setUnitPage} 会夹到 {@code 0..最大页}），
     * 因此客户端无法越权指定不存在的页。</p>
     */
    public static final int BTN_UNIT_PAGE_SET = 500;
    /** 行次数 - / +（+24）、右键删除该步（+60）的 id 基址。 */
    private static final int BTN_ROW_COUNT_MINUS = 0;
    private static final int BTN_ROW_COUNT_PLUS = 20;
    private static final int BTN_ROW_DELETE = 60;
    /** Shift+左键上移该步（+0）、Shift+右键下移该步（+20）的 id 基址。 */
    public static final int BTN_ROW_MOVE_UP = 100;
    public static final int BTN_ROW_MOVE_DOWN = 120;

    /** 容器数据槽总数：0..WINDOW-1=各可见行次数，WINDOW=循环，WINDOW+1=总步数，
     *  WINDOW+2=（保留）自动合成仓索引，WINDOW+3=样板张数，WINDOW+4=编排滚动偏移，
     *  WINDOW+5=产物偏移，+6=产物最大偏移，+7=废料偏移，+8=废料最大偏移，
     *  之后 12 格产物概率 + 12 格废料概率（均按可见窗口逐格同步），
     *  末尾 6 个：单元样板窗口当前页 / 最大页 / 库条目数 / 总样板隐藏张数 / 终端内样板张数 /
     *  <b>生成所需样板张数</b>（-1 = 当前不可生成；界面据此禁用按钮并显示「缺几样」）。 */
    public static final int DATA_SLOT_COUNT = ARRANGEMENT_WINDOW + 5 + 4 + RESULT_WINDOW + SCRAP_WINDOW + 7;
    private static final int IDX_LOOPS = ARRANGEMENT_WINDOW;
    private static final int IDX_SIZE = ARRANGEMENT_WINDOW + 1;
    private static final int IDX_AUTOCRAFTER = ARRANGEMENT_WINDOW + 2;
    private static final int IDX_PATTERN_COUNT = ARRANGEMENT_WINDOW + 3;
    private static final int IDX_OFFSET = ARRANGEMENT_WINDOW + 4;
    private static final int IDX_RESULT_OFFSET = ARRANGEMENT_WINDOW + 5;
    private static final int IDX_RESULT_MAX = ARRANGEMENT_WINDOW + 6;
    private static final int IDX_SCRAP_OFFSET = ARRANGEMENT_WINDOW + 7;
    private static final int IDX_SCRAP_MAX = ARRANGEMENT_WINDOW + 8;
    private static final int IDX_RESULT_CHANCE = ARRANGEMENT_WINDOW + 9;
    private static final int IDX_SCRAP_CHANCE = IDX_RESULT_CHANCE + RESULT_WINDOW;
    /** 单元样板窗口：当前页下标（服务端权威，客户端只读）。 */
    private static final int IDX_UNIT_PAGE = IDX_SCRAP_CHANCE + SCRAP_WINDOW;
    /** 单元样板窗口：最大页下标（页数 = ceil(库条目数 / 窗口宽)，空库恒为第 1 页）。 */
    private static final int IDX_UNIT_MAX_PAGE = IDX_UNIT_PAGE + 1;
    /** 单元样板窗口：库条目数（最后一个非空槽 +1），用于「1-3 / 共 N」与「该格是否可交互」。 */
    private static final int IDX_UNIT_ENTRIES = IDX_UNIT_PAGE + 2;
    /** 总样板槽里藏在内部格位（第 2~9 格）的张数，界面据此提示「还有样板会自动前移」。 */
    private static final int IDX_PATTERN_HIDDEN = IDX_UNIT_PAGE + 3;
    /** 终端自己的样板输入槽里的 {@code refinedstorage:pattern} 总张数（生成耗材；界面据此显示 tooltip）。 */
    private static final int IDX_RS_PATTERN_COUNT = IDX_UNIT_PAGE + 4;
    /**
     * 生成本次样板需要消耗的 RS 样板张数（{@code -1} = 当前不可生成）。
     * <p>界面据此直接算出「缺几样」并把「生成样板」按钮置灰（用户要求：缺料时不要发聊天栏消息，
     * 改成禁用按钮 + tooltip 显示缺几张）。数值来自服务端唯一权威
     * {@link SequencePatternTerminalBlockEntity#generationPatternCost()}。</p>
     */
    private static final int IDX_GENERATE_NEED = IDX_UNIT_PAGE + 5;
    /**
     * 「优先复用网络中的中间产物」开关（服务端权威，见 {@code RsccIntermediateReusePolicy}）。
     * <p><b>为什么必须下发到客户端</b>：界面顶部「输入原料」那一格在开关打开时要改画<b>该配方的过渡件</b>
     * （中间产物），而开关是服务端 {@code SavedData}，客户端读不到它。走既有的 ContainerData 通道
     * （本菜单已经用它下发页码 / 概率 / 缺几张）最省事：开关一变，界面下一帧就跟着变，
     * 不需要新增任何自定义包，也不需要客户端自持任何权威状态。</p>
     */
    private static final int IDX_REUSE_INTERMEDIATES = IDX_GENERATE_NEED + 1;

    /** 界面坐标（Menu 坐标）：所有槽位位置集中在此，界面与菜单共用同一批常量。 */
    public static final int ARR_SLOT_X = 9;
    public static final int ARR_SLOT_Y = 33;
    /** 输入原料槽（右列，唯一操作口）。 */
    public static final int SLOT_INPUT_X = 184;
    public static final int OP_SLOT_Y = 33;
    public static final int RESULT_SLOT_X = 166;
    public static final int RESULT_SLOT_Y = 66;
    public static final int SCRAP_SLOT_X = 166;
    public static final int SCRAP_SLOT_Y = 131;
    /**
     * 底部样板区（v10：<b>左侧 1 格总样板槽</b> ｜ 断开区（单元样板窗口的小滚动条）
     * ｜ <b>右侧 6 格单元样板窗口</b>），整排水平居中、左右各留 4px 白。
     * <h2>这一排是怎么排出来的（250px 可用宽度硬约束）</h2>
     * <p>窗口 256px、容器边框 3px → 可用 250px（Menu 4..253）。这一排要放：
     * 1 格总样板槽（17px）＋ 6 格单元样板槽（107px）＝ 124px 槽位；剩 126px 给标签与滚动条。
     * 正常字号（CJK 9px/字）下「总样板」27px、「单元」18px、小滚动条 7px：
     * 27 + 17 + 7 + 107 + 18 = 176px，另有 60px 用作排间距，整排 241px 仍严格居中。</p>
     * <p>实际排布（精灵坐标）：总样板槽框 36，小滚动条 84..90（Menu 85，7×16），
     * 单元样板槽框 122..228（6 格），两端标签各留 4px 白 → 整排 7..248，
     * 几何中心 127.5 = 窗口中心，严格居中且不压 3px 边框。</p>
     */
    public static final int PATTERN_SLOT_X = 37;
    public static final int PATTERN_SLOT_Y = 213;
    /**
     * 样板输入槽（3 格，{@code refinedstorage:pattern}）：Menu (70/88/106, 191)。
     * <p><b>几何来源</b>：删除「导入」按钮后腾出的按钮行留白（原按钮 Menu 69..125）。
     * 槽位 16×16 垂直居中于 18px 的按钮行（Menu y 190..208），槽框由界面用
     * {@code McGui.slotFrame} 在运行时绘制（<b>不</b>改背景贴图，故背景留白校验不受影响）。
     */
    public static final int RS_PATTERN_SLOT_X = 70;
    public static final int RS_PATTERN_SLOT_Y = 191;
    public static final int UNIT_SLOT_X = 123;
    public static final int UNIT_SLOT_Y = 213;
    public static final int PLAYER_SLOT_X = 49;
    public static final int PLAYER_SLOT_Y = 247;
    public static final int HOTBAR_SLOT_X = 49;
    public static final int HOTBAR_SLOT_Y = 303;

    private final SequencePatternTerminalBlockEntity terminal;
    private final ContainerData data;
    /** 流程编排滚动窗口偏移（行数，0..arrangementSize-WINDOW）。 */
    private int arrangementOffset;
    /** 产物 / 废料滚动窗口偏移（页，0..可见窗口之外还有条目时 >0）。 */
    private int resultOffset;
    private int scrapOffset;
    /**
     * 单元样板窗口当前页（v7）。
     * <p><b>为什么放在菜单而不是界面</b>：窗口格 → 单元样板库下标的映射必须由<b>服务端</b>解析
     * （客户端点的是「第 i 格」，服务端要按「第几页」换算成真实库下标）。若页码只存在客户端，
     * 服务端会按自己的页去取格子 —— 玩家看到的是一张样板、拿到的却是另一张。因此页码随
     * {@link #BTN_UNIT_PAGE_PREV} / {@link #BTN_UNIT_PAGE_NEXT} 由服务端保存并下发。</p>
     */
    private int unitPage;
    /**
     * 「关闭界面时把终端内容写回」的钩子（<b>仅</b>终端由<b>物品</b>打开时注册；方块终端为 null）。
     * <p><b>为什么需要它</b>：物品路径（{@code AdvancedRemoteTerminalItem} 的 MODE_SEQUENCE）用的是
     * 只活在内存里的<b>虚拟方块实体</b>，它不在世界里、自然也不会被存档。若关闭界面时不把内容写回
     * 物品 NBT，玩家放进终端自己槽位（3 格样板输入槽 / 总样板槽 / 单元样板库）的东西会随对象一起消失
     * —— 那就是<b>销毁物品</b>。方块终端不注册该钩子（它自己有区块存档）。</p>
     */
    @Nullable
    private Runnable terminalCloseSaver;

    /** 注册「关闭界面时写回物品 NBT」的钩子（仅虚拟方块实体 / 终端物品路径调用）。 */
    public void setTerminalCloseSaver(final Runnable saver) {
        this.terminalCloseSaver = saver;
    }

    public SequencePatternTerminalMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public SequencePatternTerminalMenu(final int id,
                                       final Inventory inventory,
                                       @Nullable final SequencePatternTerminalBlockEntity terminal) {
        super(RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_MENU.get(), id);
        this.terminal = terminal;
        this.data = terminal != null ? new WindowedData(this, terminal) : new SimpleContainerData(DATA_SLOT_COUNT);
        addDataSlots(data);

        // 流程编排的数据源：服务端每次都现取 terminal.arrangementView()。
        // 为什么用 supplier 而不是固定一个 handler：导入会在运行期把「展示数据」切成当前数据源，
        // 若菜单在构造时把它固定下来，导入后流程编排仍会读老容器 —— 界面直接变空白。
        // 客户端菜单（terminal == null）走本地镜像槽（原版槽位同步写入），不需要数据源。
        final java.util.function.Supplier<IItemHandlerModifiable> arrangementSource =
            terminal != null ? terminal::arrangementView : null;
        final IItemHandler ingredient = terminal != null ? terminal.ingredientSlot : new ItemStackHandler(1);
        final IItemHandlerModifiable results = terminal != null ? terminal.resultSlots : new ItemStackHandler(
            SequencePatternTerminalBlockEntity.RESULT_SIZE);
        final IItemHandlerModifiable scraps = terminal != null ? terminal.scrapSlots : new ItemStackHandler(
            SequencePatternTerminalBlockEntity.SCRAP_SIZE);

        // 输入原料标记槽（ghost，不可取出）：Menu (184,33)
        addSlot(new MarkerInputSlot(ingredient, SLOT_INPUT_X, OP_SLOT_Y));
        // 流程编排：动态列表的滚动窗口（Menu (9, 33+i*18) 18×18，行槽框由界面自绘）
        for (int i = 0; i < ARRANGEMENT_WINDOW; i++) {
            addSlot(new ArrangementRowSlot(arrangementSource,
                i, ARR_SLOT_X, ARR_SLOT_Y + i * 18, this));
        }
        // 产物 / 废料：各 4 列 × 3 行可见，只读 ghost 标记（不可取放，仅终端/jei 写入）
        for (int i = 0; i < RESULT_WINDOW; i++) {
            addSlot(new WindowedSectionSlot(results, i, RESULT_WINDOW, () -> resultOffset,
                RESULT_SLOT_X + (i % 4) * 18, RESULT_SLOT_Y + (i / 4) * 18));
        }
        for (int i = 0; i < SCRAP_WINDOW; i++) {
            addSlot(new WindowedSectionSlot(scraps, i, SCRAP_WINDOW, () -> scrapOffset,
                SCRAP_SLOT_X + (i % 4) * 18, SCRAP_SLOT_Y + (i / 4) * 18));
        }
        // 唯一那格总样板槽（Menu (37,213)，槽框烘焙在背景里）。
        // 底层仍是 9 格的 patternSlots：老存档放在靠后格位的样板会在「可见格被取空」时自动前移，
        // 因此玩家能一格一格地把 9 张全部取回（见 SequencePatternTerminalBlockEntity#compactPatternSlots）。
        // 客户端菜单没有方块实体引用，因此这里必须用与方块实体同源的判定建立本地镜像，
        // 否则客户端会「假装收下」其它物品、点击后又被服务端退回（三处判定必须一致）。
        final IItemHandler patterns = terminal != null ? terminal.patternSlots
            : new ItemStackHandler(SequencePatternTerminalBlockEntity.PATTERN_SLOT_SIZE) {
                @Override
                public boolean isItemValid(final int slot, final ItemStack stack) {
                    return SequencePatternTerminalBlockEntity.acceptsPattern(stack);
                }

                @Override
                public int getSlotLimit(final int slot) {
                    return 1;
                }
            };
        // 单元样板槽窗口（Menu (123,213) 起，6 格）：展示 / 读写终端单元样板库的当前页。
        // 服务端直连 unitLibrary（可增长、无上限），客户端用本地 6 格镜像承接原版槽位同步。
        for (int i = 0; i < UNIT_WINDOW; i++) {
            addSlot(new UnitWindowSlot(terminal, i, UNIT_SLOT_X + i * 18, UNIT_SLOT_Y, this));
        }
        // 玩家背包 3×9 与快捷栏 1×9
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9,
                    PLAYER_SLOT_X + col * 18, PLAYER_SLOT_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, HOTBAR_SLOT_X + col * 18, HOTBAR_SLOT_Y));
        }
        // 唯一那格总样板槽最后追加（保证既有槽位下标全部不变）：它是「生成」按钮的产出落点，
        // 只出不进（不能手动放入）—— 底层容器仍是 9 格的 patternSlots，这里只暴露第 1 格（见 PATTERN_WINDOW）。
        for (int i = 0; i < PATTERN_WINDOW; i++) {
            addSlot(new TotalPatternSlot(patterns, i, PATTERN_SLOT_X + i * 18, PATTERN_SLOT_Y));
        }
        // 样板输入槽（最后追加，见 SLOT_RS_PATTERN_START）：3 格，只收 refinedstorage:pattern。
        // 生成时的耗材只从这里扣，玩家不再需要把样板背在身上（旧实现扣玩家背包 + 光标）。
        final IItemHandler rsPatterns = terminal != null ? terminal.rsPatternSlots
            : new ItemStackHandler(SequencePatternTerminalBlockEntity.RS_PATTERN_SLOT_SIZE) {
                @Override
                public boolean isItemValid(final int slot, final ItemStack stack) {
                    return SequencePatternTerminalBlockEntity.isRefinedStoragePattern(stack);
                }
            };
        for (int i = 0; i < SequencePatternTerminalBlockEntity.RS_PATTERN_SLOT_SIZE; i++) {
            addSlot(new net.neoforged.neoforge.items.SlotItemHandler(rsPatterns, i,
                RS_PATTERN_SLOT_X + i * 18, RS_PATTERN_SLOT_Y));
        }
    }

    public static SequencePatternTerminalMenu create(final int id,
                                                     final Inventory inventory,
                                                     final SequencePatternTerminalBlockEntity terminal) {
        return new SequencePatternTerminalMenu(id, inventory, terminal);
    }

    /** 纯 getter：暴露本菜单持有的终端方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public SequencePatternTerminalBlockEntity getTerminal() {
        return terminal;
    }

    // ========== 流程编排滚动窗口 ==========

    /** 当前滚动偏移（首行全局步下标）；越界时自动收敛到合法区间。 */
    public int getArrangementOffset() {
        return Math.max(0, Math.min(arrangementOffset, getMaxArrangementOffset()));
    }

    /** 最大滚动偏移（= 总步数 - 窗口）。 */
    public int getMaxArrangementOffset() {
        return Math.max(0, getArrangementSize() - ARRANGEMENT_WINDOW);
    }

    public void setArrangementOffset(final int offset) {
        arrangementOffset = Math.max(0, Math.min(offset, getMaxArrangementOffset()));
    }

    /** 服务端权威偏移（数据槽同步回传）：本地交互结束后据此对齐窗口。 */
    public int getSyncedArrangementOffset() {
        return Math.max(0, data.get(IDX_OFFSET));
    }

    /** 总步数变化后把偏移收敛回合法区间。 */
    public void syncArrangementOffsetToBounds() {
        setArrangementOffset(arrangementOffset);
    }

    /** 窗口内某行（0..WINDOW-1）的重复次数（读数据槽，服务端已按偏移换算好）。 */
    public int getArrangementCount(final int index) {
        return data.get(index);
    }

    // ========== 产物 / 废料滚动窗口 ==========

    public int getResultOffset() {
        return Math.max(0, Math.min(resultOffset, getMaxResultOffset()));
    }

    public int getMaxResultOffset() {
        return Math.max(0, data.get(IDX_RESULT_MAX));
    }

    public void setResultOffset(final int offset) {
        resultOffset = Math.max(0, Math.min(offset, getMaxResultOffset()));
    }

    public int getSyncedResultOffset() {
        return Math.max(0, data.get(IDX_RESULT_OFFSET));
    }

    public int getScrapOffset() {
        return Math.max(0, Math.min(scrapOffset, getMaxScrapOffset()));
    }

    public int getMaxScrapOffset() {
        return Math.max(0, data.get(IDX_SCRAP_MAX));
    }

    public void setScrapOffset(final int offset) {
        scrapOffset = Math.max(0, Math.min(offset, getMaxScrapOffset()));
    }

    public int getSyncedScrapOffset() {
        return Math.max(0, data.get(IDX_SCRAP_OFFSET));
    }

    // ========== 单元样板窗口（v7 起：单元样板库的 6 格翻页窗口） ==========

    /** 当前页下标。两端取同一来源：服务端读自己的权威字段，客户端读 ContainerData 下发值。 */
    public int getUnitPage() {
        final int page = terminal != null ? unitPage : data.get(IDX_UNIT_PAGE);
        return Math.max(0, Math.min(page, getUnitMaxPage()));
    }

    /** 最大页下标（客户端读 ContainerData）。 */
    public int getUnitMaxPage() {
        return Math.max(0, data.get(IDX_UNIT_MAX_PAGE));
    }

    /** 单元样板库条目数（客户端读 ContainerData），用于「1-3 / 共 N」与越界格判定。 */
    public int getUnitEntries() {
        return Math.max(0, data.get(IDX_UNIT_ENTRIES));
    }

    /** 总样板槽藏在内部格位（第 2~9 格）的张数（客户端读 ContainerData）。 */
    public int getHiddenPatternCount() {
        return Math.max(0, data.get(IDX_PATTERN_HIDDEN));
    }

    /** 终端「样板输入槽」里的 {@code refinedstorage:pattern} 总张数（生成耗材；客户端读 ContainerData）。 */
    public int getRsPatternCount() {
        return Math.max(0, data.get(IDX_RS_PATTERN_COUNT));
    }

    /**
     * 本次生成所需的 RS 样板张数（{@code -1} = 当前不可生成）；客户端读 ContainerData。
     * <p>界面用它把「生成样板」按钮置灰并算出「缺几样」（用户要求：缺料时不发聊天栏消息，
     * 改成禁用按钮 + tooltip 显示缺少几张）。</p>
     */
    public int getGenerateNeed() {
        return data.get(IDX_GENERATE_NEED);
    }

    /**
     * 「优先复用网络中的中间产物」是否打开（客户端镜像，服务端权威）。
     * <p>界面据此把顶部「输入原料」那一格改画成<b>中间产物（过渡件）</b>，并换掉它的标题 ——
     * 开关为关时本方法恒为 {@code false}，界面走的是与改动前<b>逐字一致</b>的老路径。</p>
     */
    public boolean isReuseIntermediates() {
        return data.get(IDX_REUSE_INTERMEDIATES) != 0;
    }

    /**
     * 服务端：把单元样板窗口页码<b>直接设到目标页</b>（滚动条拖动 / 点轨道）。
     * <p>目标页由客户端提供（按钮 id = {@link #BTN_UNIT_PAGE_SET} + N），但上限始终由<b>服务端</b>
     * 按当前库条目数计算并夹紧，因此客户端无法指定一个不存在的页。</p>
     */
    public void setUnitPage(final int page) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        final int maxPage = SequencePatternTerminalBlockEntity.unitMaxPage(
            SequencePatternTerminalBlockEntity.unitLibraryEntries(terminal.unitLibrary));
        unitPage = Math.max(0, Math.min(page, maxPage));
    }

    /**
     * 服务端：翻页（{@code delta} = ±1，滚轮用）。页码只会收敛到 {@code 0..最大页}，越界不动；
     * 上界由<b>服务端</b>按库条目数计算（滚动条拖动走的 {@link #setUnitPage(int)} 同样如此夹紧）。
     */
    public void cycleUnitPage(final int delta) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        final int maxPage = SequencePatternTerminalBlockEntity.unitMaxPage(
            SequencePatternTerminalBlockEntity.unitLibraryEntries(terminal.unitLibrary));
        unitPage = Math.max(0, Math.min(unitPage + delta, maxPage));
    }

    /** 可见窗口内第 i 格产物的概率（数据槽按服务端偏移逐格同步，0..1）。 */
    public float getResultChance(final int windowIndex) {
        return Math.max(0, Math.min(100, data.get(IDX_RESULT_CHANCE + windowIndex))) / 100F;
    }

    /** 可见窗口内第 i 格废料的概率。 */
    public float getScrapChance(final int windowIndex) {
        return Math.max(0, Math.min(100, data.get(IDX_SCRAP_CHANCE + windowIndex))) / 100F;
    }

    /** 服务端：按可见窗口下标调整产物/废料概率（deltaPercent 为 ±10 之类的增量；客户端只发请求）。 */
    public void adjustChance(final boolean scrap, final int windowIndex, final int deltaPercent) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        final int window = scrap ? SCRAP_WINDOW : RESULT_WINDOW;
        if (windowIndex < 0 || windowIndex >= window) {
            return;
        }
        final int global = (scrap ? getScrapOffset() : getResultOffset()) * window + windowIndex;
        final float[] chances = scrap ? terminal.scrapChances : terminal.resultChances;
        if (global < 0 || global >= chances.length) {
            return;
        }
        terminal.setChance(scrap, global, (int) (chances[global] * 100F) + deltaPercent);
    }

    /**
     * 服务端：一次性设置某产物 / 废料的概率与产出数量（子窗口确认时调用）。
     * <p>窗口下标会换算成全局下标；越界 / 客户端一律拒绝。</p>
     */
    public void setResultConfig(final boolean scrap, final int windowIndex, final int percent, final int amount) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        final int window = scrap ? SCRAP_WINDOW : RESULT_WINDOW;
        final int global = (scrap ? getScrapOffset() : getResultOffset()) * window + windowIndex;
        final int size = scrap ? SequencePatternTerminalBlockEntity.SCRAP_SIZE
            : SequencePatternTerminalBlockEntity.RESULT_SIZE;
        if (global < 0 || global >= size) {
            return;
        }
        terminal.setResultConfig(scrap, global, percent, amount);
    }

    /**
     * 服务端：清空某产物 / 废料格（界面 Shift+左键触发）。
     * <p>窗口下标会换算成全局下标；越界 / 客户端一律拒绝。</p>
     */
    public void clearResultConfig(final boolean scrap, final int windowIndex) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        final int window = scrap ? SCRAP_WINDOW : RESULT_WINDOW;
        final int global = (scrap ? getScrapOffset() : getResultOffset()) * window + windowIndex;
        final int size = scrap ? SequencePatternTerminalBlockEntity.SCRAP_SIZE
            : SequencePatternTerminalBlockEntity.RESULT_SIZE;
        if (global < 0 || global >= size) {
            return;
        }
        terminal.clearResultConfig(scrap, global);
    }

    /**
     * JEI 配方转移（服务端）：整条流程写入编排（不限步数）+ 原料槽 + 产物池（含概率）+ 循环次数。
     * <p><b>导入不产出、不消耗</b>：写入的是终端的<b>展示数据</b>（流程 / 原料 / 产物 / 废料照常显示），
     * 不生成单元样板、不写样板槽、也不扣玩家的 {@code refinedstorage:pattern} ——
     * 真正产出与消耗统一发生在点「生成样板」时（见 {@link #generateAndHandOver(Player)}）。</p>
     * <p><b>相邻的完全相同步骤在 {@link SequencePatternTerminalBlockEntity#applyRecipeToArrangement}
     * 里自动合并成一列并记录重复次数 N</b>（用户硬要求「完全一样的自动合并」）；
     * 合并后的那一列保留这段<b>第一级</b>的配方序列下标，执行仓的按类型归属判定照样认下重复的各级。</p>
     */
    public void importSequencedRecipe(final List<String> machines,
                                      final List<ItemStack> stepInputs,
                                      final List<Integer> stepCounts,
                                      final int loops,
                                      final ItemStack ingredient,
                                      final List<ItemStack> results,
                                      final List<Integer> resultChances) {
        importSequencedRecipe(machines, stepInputs, stepCounts, loops, ingredient,
            results, resultChances, List.of(), List.of(), null, List.of(), List.of(), List.of());
    }

    /**
     * JEI 配方转移（服务端）：同上一重载，并额外写入<b>废料</b>（概率 &lt; 100% 的副产物）、
     * 所属 Create 配方 id（供服务端按配方数据判断该步是否存在某类输入）、<b>每步的输入流体</b>
     * 与<b>每步输入原料组的全部候选</b>（标签型 ingredient，如列车轨道机械手步的「铁粒 或 锌粒」）。
     * <p>物品数量照抄 {@link ItemStack#getCount()}（产物 / 废料各自的「产出数量」）。</p>
     * <p>{@code stepCandidates} 与 {@code stepInputs} <b>逐下标平行</b>（客户端在 JEI 转移时按配方数据算好）；
     * 缺项 / 空表 = 该步只有代表物一件。</p>
     */
    public void importSequencedRecipe(final List<String> machines,
                                      final List<ItemStack> stepInputs,
                                      final List<Integer> stepCounts,
                                      final int loops,
                                      final ItemStack ingredient,
                                      final List<ItemStack> results,
                                      final List<Integer> resultChances,
                                      final List<ItemStack> scraps,
                                      final List<Integer> scrapChances,
                                      @Nullable final net.minecraft.resources.ResourceLocation recipeId,
                                      final List<net.neoforged.neoforge.fluids.FluidStack> stepFluids,
                                      final List<List<ItemStack>> stepCandidates,
                                      /** 起步原料的全部候选（「任意台阶」一类）。2026-10-05 新增。 */
                                      final List<ItemStack> ingredientCandidates) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        final Player player = getPlayer();
        final var registries = terminal.getLevel().registryAccess();
        // 回到窗口顶部，保证下面按窗口下标写入的槽位落在全局第 0 格
        arrangementOffset = 0;
        resultOffset = 0;
        scrapOffset = 0;
        // 原料 / 产物直接写方块实体（不经过共享槽，避免页面向错）
        if (ingredient != null && !ingredient.isEmpty()) {
            terminal.ingredientSlot.setStackInSlot(0, ingredient.copyWithCount(1));
        }
        // 起步原料的全部候选（标签型 ingredient）：交给终端保存，生成总样板时才写进 RS 样板与 NBT。
        // 没有它，「任意台阶」这一格永远只能显示代表物（用户实测：「还是只写是个石头台阶」）。
        terminal.setIngredientCandidates(ingredientCandidates);
        // 产物池 / 废料：先清空，再按配方逐格写入（概率照抄 Create，缺省 100%）
        for (int i = 0; i < SequencePatternTerminalBlockEntity.RESULT_SIZE; i++) {
            terminal.resultSlots.setStackInSlot(i, ItemStack.EMPTY);
            terminal.resultChances[i] = 1.0F;
        }
        for (int i = 0; i < SequencePatternTerminalBlockEntity.SCRAP_SIZE; i++) {
            terminal.scrapSlots.setStackInSlot(i, ItemStack.EMPTY);
            terminal.scrapChances[i] = 1.0F;
        }
        writeOutputs(terminal.resultSlots, terminal.resultChances, SequencePatternTerminalBlockEntity.RESULT_SIZE,
            results, resultChances);
        writeOutputs(terminal.scrapSlots, terminal.scrapChances, SequencePatternTerminalBlockEntity.SCRAP_SIZE,
            scraps, scrapChances);
        terminal.setLoops(Math.max(1, loops));
        // 流程编排：每步生成一张单元样板
        final int steps = Math.max(1, machines.size());
        final List<ItemStack> units = new java.util.ArrayList<>(steps);
        final List<Integer> counts = new java.util.ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            final String machine = i < machines.size() ? machines.get(i)
                : SequencePatternTerminalBlockEntity.MACHINE_TYPES.get(0);
            final ItemStack stepInput = i < stepInputs.size() ? stepInputs.get(i) : ItemStack.EMPTY;
            // 每步的输入流体（来自 Create 配方的流体 ingredient；无则空栈）——
            // 导入即自动标注，界面上该行的输入标记随之显示流体（用户实测问题）。
            final net.neoforged.neoforge.fluids.FluidStack stepFluid =
                stepFluids != null && i < stepFluids.size() && stepFluids.get(i) != null
                    ? stepFluids.get(i) : net.neoforged.neoforge.fluids.FluidStack.EMPTY;
            final int count = i < stepCounts.size() ? Math.max(1, stepCounts.get(i)) : 1;
            // 该步输入原料组的全部候选（标签型 ingredient，如列车轨道机械手步的「铁粒 或 锌粒」）：
            // 客户端按配方数据算好、逐下标平行下发；缺项 / 空表 ⇒ 只有代表物一件（与既有行为一致）。
            // 它必须落进单元样板 NBT：① tooltip 与执行仓能显示整组候选；
            // ② 总样板的 RS EXTERNAL ingredient 才会把全部候选登记进去（否则锌粒永远不被认）。
            List<ItemStack> stepCandidateList = List.of();
            if (stepCandidates != null && i < stepCandidates.size() && stepCandidates.get(i) != null) {
                stepCandidateList = cretae.cookiewyq.rs_create_compat.data.SequencePatternData
                    .normalizeCandidates(stepCandidates.get(i));
            }
            final ItemStack unit = new ItemStack(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get());
            // 单元样板内容 = 配方类型 + 可能的输入原料；
            // 这里的 machine 由调用方（JEI）传入的是<b>真实配方类型注册 id</b>（create:deploying 等），
            // 因此直接作为单元样板的 recipeType —— 否则执行仓按配方类型匹配时会永远显示「无可用机器」。
            // 同时写入所属配方 id，服务端据此「从配方数据」判断该步是否存在某类输入。
            cretae.cookiewyq.rs_create_compat.data.SequencePatternData.writeUnit(
                unit, new cretae.cookiewyq.rs_create_compat.data.SequencePatternData.UnitData(
                    machine, stepInput.copy(), "", recipeId == null ? "" : recipeId.toString(), i,
                    !stepInput.isEmpty(), "", machine, stepCandidateList),
                registries);
            cretae.cookiewyq.rs_create_compat.data.SequencePatternData.writeUnitFluid(
                unit, stepFluid, registries);
            units.add(unit);
            counts.add(count);
        }
        terminal.applyRecipeToArrangement(units, counts);
        if (player != null) {
            syncStepMachines(player);
            // 可见反馈：JEI 转移与按钮导入共用同一套重绑定逻辑（applyRecipeToArrangement），
            // 因此提示文案也共用同一个方法，两条路径的表现完全一致。
            displayImportFeedback(player);
        }
    }

    /**
     * 导入 / 转移后的动作栏反馈（两条导入路径共用）。
     * <p>先报「已导入」，再在**有步骤匹配不到机器**时补一条可见提示 —— 用户要求「匹配不到要留空并
     * 给出可见提示，而不是沿用旧值」。步骤留空在界面上表现为该行红字「无可用机器」，这里再补一条
     * 汇总提示，玩家开界面之前就知道有几步没绑定。
     */
    private void displayImportFeedback(final Player player) {
        player.displayClientMessage(Component.translatable(
            "gui.rs_create_compat.sequence_pattern_terminal.import.done"), true);
        final int unbound = terminal.lastUnboundStepCount();
        if (unbound > 0) {
            player.displayClientMessage(Component.translatable(
                "gui.rs_create_compat.sequence_pattern_terminal.import.no_machine", unbound), false);
        }
    }

    /**
     * 把「产物 / 废料」列表写入目标槽（数量照抄 {@link ItemStack#getCount()}），概率照抄百分比。
     * <p>产物与废料共用同一套写法，避免两处逻辑走偏（此前废料根本没有数据来源）。</p>
     */
    private static void writeOutputs(final net.neoforged.neoforge.items.ItemStackHandler slots,
                                     final float[] chances, final int maxSize,
                                     final List<ItemStack> stacks, final List<Integer> percents) {
        final List<ItemStack> safeStacks = stacks == null ? List.of() : stacks;
        final boolean hasChances = percents != null && percents.size() == safeStacks.size();
        for (int i = 0; i < safeStacks.size() && i < maxSize; i++) {
            final ItemStack out = safeStacks.get(i);
            if (out == null || out.isEmpty()) {
                continue;
            }
            slots.setStackInSlot(i, out.copy());
            chances[i] = hasChances
                ? Math.max(0F, Math.min(1F, percents.get(i) / 100F)) : 1.0F;
        }
    }

    /** 获取此菜单对应的玩家（服务端），供生成/导入失败的提示。 */
    @Nullable
    private Player getPlayer() {
        if (terminal == null || terminal.getLevel() == null) {
            return null;
        }
        for (final Player p : terminal.getLevel().players()) {
            if (p.containerMenu == this) {
                return p;
            }
        }
        return null;
    }

    // ========== 数据读取 ==========

    public int getLoops() {
        return data.get(IDX_LOOPS);
    }

    /** 当前启用的流程总步数（可以是 0，表示空编排）。 */
    public int getArrangementSize() {
        return Math.max(0, data.get(IDX_SIZE));
    }

    /** 当前选中的自动合成仓索引（单元样板制作）。 */
    public int getAutocrafterIndex() {
        return data.get(IDX_AUTOCRAFTER);
    }

    /** 样板槽中原版样板总数（由服务端 ContainerData 同步，供客户端禁用按钮 / JEI 校验）。 */
    public int getPatternCount() {
        return data.get(IDX_PATTERN_COUNT);
    }

    /** 服务端设置次数绝对值（客户端输入框发送；index = 窗口内行 0..WINDOW-1，-1 = 整体循环）。 */
    public void setCount(final int index, final int value) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        if (index >= 0 && index < ARRANGEMENT_WINDOW) {
            terminal.setArrangementCountAt(getArrangementOffset() + index, value);
        } else if (index == -1) {
            terminal.setLoops(value);
        }
    }

    // ========== 步骤机器指派的 S2C 同步 ==========

    /**
     * 服务端：把「每一步的机器名 + recipeType + 是否已指派」快照发给该玩家（客户端只缓存）。
     * <p><b>第 49 轮：这里只发「轻快照」，全量扫描延后</b>。发送本身只做「逐步骤读一次样板 NBT
     * + 读两个既有缓存」，不遍历网络、不查配方，因此拿快捷键开界面那一拍不会再被
     * 「网络里有多少执行舱 / 多少张单元样板 / 多少条序列装配配方」拖住（单人游戏里那会直接卡住
     * 客户端主线程，正是玩家说的「打开太慢、有时候很卡」）。两个昂贵事实（判重 / 已就位）改由
     * {@link #scheduleHeavySync()} 排到 {@value #HEAVY_SYNC_DELAY_TICKS} 拍之后扫一次，
     * 它们只用于显示，权威判定在生成侧（{@code generateAssemblyPattern} 自己重扫）。</p>
     */
    public void sendStepMachines(final Player player) {
        if (terminal == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
            cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket.fromCheap(terminal));
        scheduleHeavySync();
    }

    // ========== 第 49 轮：轻快照 + 延后的权威快照 ==========

    /** 权威快照的延后拍数：先让界面用轻快照开出来，再在几拍之后补上两个昂贵事实。 */
    private static final int HEAVY_SYNC_DELAY_TICKS = 3;

    /** 权威快照是否已排队（连续改动只排一次，避免把全量扫描排成一串）。 */
    private boolean heavySyncPending;
    /** 距权威快照还有几拍（仅 {@link #heavySyncPending} 为真时有意义）。 */
    private int heavySyncCountdown;

    /** 把一次「权威快照」（含全量网络扫描）排到 {@value #HEAVY_SYNC_DELAY_TICKS} 拍之后。 */
    private void scheduleHeavySync() {
        if (!heavySyncPending) {
            heavySyncPending = true;
            heavySyncCountdown = HEAVY_SYNC_DELAY_TICKS;
        }
    }

    /**
     * 发一次权威快照（含判重缓存重扫 + 已就位判定 + 节流过的老样板自愈）。
     * <p>缓存还新鲜（距上次权威扫描不足 {@code SyncStepMachinesPacket.DISPLAY_CACHE_TICKS} 拍）
     * 时直接跳过：连续开关终端只付第一次的代价，这是「打开速度稳定」的关键 ——
     * 旧实现每次开界面都从零全量扫一遍，耗时随存档规模剧烈波动。</p>
     */
    private void sendAuthoritativeSnapshot(final ServerPlayer serverPlayer) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket.displayCacheFresh(
            terminal, terminal.getLevel().getGameTime())) {
            return; // 显示缓存还新鲜：一拍都不扫，界面沿用上一次的权威值
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
            cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket.from(terminal));
    }

    // ========== JEI / ghost ==========

    /**
     * JEI ghost 放置（服务端）：<b>v6 起流程编排 / 产物 / 废料全部只读</b>，唯一允许 ghost 的目标
     * 是顶部的「输入原料」标记槽（其余落点直接忽略，与服务端/界面上的只读判定同源）。
     */
    public void placeGhost(final int slotId, final ItemStack stack) {
        if (stack.isEmpty() || slotId != SLOT_INPUT) {
            return;
        }
        final Slot slot = slots.get(slotId);
        final ItemStack copy = stack.copyWithCount(1);
        if (slot.mayPlace(copy)) {
            slot.set(copy);
        }
    }

    /** 只读展示槽（输入原料这一格）：点击不改变内容，Shift 移动也必须整体拒绝。 */
    private boolean isMarkerSlot(final int slotId) {
        return slotId == SLOT_INPUT;
    }

    /**
     * 只读展示（标记）槽：流程编排窗口 / 产物 / 废料 —— 玩家不可取走也不可放入，仅终端自身与 JEI ghost 可写。
     * <p>流程编排自 v8 起展示的是「导入产生的展示数据」（幽灵容器），必须一并拦在这里，
     * 否则 Shift+左键会把它当一个普通物品搬进玩家背包（凭空造物）。</p>
     */
    private static boolean isReadOnlyDisplaySlot(final int slotId) {
        return (slotId >= SLOT_ARRANGEMENT_START && slotId < SLOT_ARRANGEMENT_END)
            || (slotId >= SLOT_RESULT_START && slotId < SLOT_SCRAP_END);
    }

    /**
     * 输入原料槽点击：<b>只读展示槽，任何点击都不改变它的内容</b>。
     * <p>值由导入 / 生成逻辑直接写进终端的 {@code ingredientSlot}；此前这里允许「手持物品点击复制标记 /
     * 空手点击清除」，但该槽的值本来就是配方自动生成的，玩家手动标记没有意义（用户要求取消标记），
     * 因此这里显式拦截：既不复制标记，也不清除（避免原版点击逻辑走到 {@code Slot#set} 把这个显示值抹掉）。</p>
     * <p>流程编排行 / 产物 / 废料格的只读由它们各自的槽位判定（{@code mayPlace/mayPickup/remove}）负责。</p>
     */
    @Override
    public void clicked(final int slotId, final int button, final net.minecraft.world.inventory.ClickType clickType,
                        final Player player) {
        if (isMarkerSlot(slotId)) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    /**
     * 按钮 id 分发。
     * <p><b>v6 收口</b>：流程完全由配方自动生成，因此<b>行级别的修改入口全部取消</b>
     * （行次数 ±、删除该步、上移 / 下移）。这里只保留：生成样板、
     * 以及 v7 新增的「单元样板窗口翻页」（滚轮 ±1 与滚动条绝对定位两种）。</p>
     * <p><b>循环 ± 已取消</b>：循环次数是配方固定值、只读（用户要求「不要让用户修改」），
     * 界面上不再有对应控件，因此 {@link #BTN_LOOPS_MINUS} / {@link #BTN_LOOPS_PLUS} 不再被分发。</p>
     */
    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (terminal == null || terminal.getLevel() == null || terminal.getLevel().isClientSide()) {
            return true;
        }
        if (id == BTN_GENERATE) {
            generateAndHandOver(player);
        } else if (id == BTN_UNIT_PAGE_PREV) {
            cycleUnitPage(-1);
        } else if (id == BTN_UNIT_PAGE_NEXT) {
            cycleUnitPage(1);
        } else if (id >= BTN_UNIT_PAGE_SET) {
            setUnitPage(id - BTN_UNIT_PAGE_SET); // 滚动条：绝对定位到第 N 页（服务端夹到合法区间）
        }
        // 其它 id（行次数 / 删除 / 上移 / 下移 / 循环 ±）一律忽略：流程由配方准确生成，玩家不可改
        return true;
    }

    /**
     * 「生成样板」按钮：一次性产出总样板 1 张 + 流程每一列的单元样板各 1 张，
     * 并按产出张数消耗 {@code refinedstorage:pattern}（总样板 1 张 + 每张单元样板 1 张）。
     * <h2>材料从哪里来（用户要求：不要从玩家身上扣）</h2>
     * <p>只从<b>终端自己的样板输入槽</b>（{@link SequencePatternTerminalBlockEntity#rsPatternSlots}，
     * 界面左侧 3 格）扣除。玩家把样板放进这 3 格即归终端所有（随方块 NBT 持久化、破坏方块照常掉落），
     * <b>不需要</b>把样板背在身上；玩家背包与光标一格物品都不会被动用。</p>
     * <h2>缺料时不再发聊天栏（用户要求）</h2>
     * <p>界面已经用服务端下发的 {@code generationPatternCost()} 把按钮置灰并显示「缺少 N 个样板」，
     * 因此这里对「不够 / 不可生成」一律<b>静默返回</b>（不发任何聊天栏消息，也不产出、不消耗）。
     * 只有<b>真的生成成功</b>时才回报一条动作栏消息（{@code generate.done}）。</p>
     * <h2>为什么先算、先查、再产出、最后才扣</h2>
     * <p>顺序是「算需要 N 张 → 数<b>终端内</b>共 M 张 → M &lt; N 就什么都不做 → 够了才产出并扣掉 N 张」。
     * 校验与扣除之间没有任何可失败分支（同一 tick 内的服务端逻辑），因此：</p>
     * <ul>
     *     <li>不够时<b>不产出、不消耗</b>（用户要求的原子性）；</li>
     *     <li>产出张数恒等于扣除张数（S 列 → S 张单元样板 + 1 张总样板 → 扣 S+1 张）；</li>
     *     <li>产出失败（流程/产物无效、已有总样板未取走）时根本走不到扣材料那一步 —— 不会「扣了没给」。</li>
     * </ul>
     * <p>产出的总样板落在底部左侧<b>唯一</b>那格总样板槽里，玩家直接取走即可；
     * 取走之前「生成」一直是禁用态（必须先把现有的全部拿走）。</p>
     */
    private void generateAndHandOver(final Player player) {
        // 生成前先按网络实况重扫一次判重缓存，再算「要消耗几张」。
        // 为什么必须在这里补这一句：界面上「需要 N 张」读的是同一份缓存，而第 49 轮起打开界面
        // 那一拍只发轻快照（全量扫描延后到几拍之后，见 sendStepMachines）。若不在这里重扫，
        // 玩家在延后窗口内点「生成」就可能按<b>偏旧</b>的缓存算出 N，
        // 而随后 generateAssemblyPattern() 内部按<b>最新</b>实况少产出几张单元样板 ⇒ 多扣了
        // 玩家的 refinedstorage:pattern（扣了却没产出对应张数）。补这一句让「算出来的张数」
        // 与紧接着那次生成看到的是同一份实况，比改动前的口径更严。
        terminal.refreshStepDuplicateCache();
        final int need = terminal.generationPatternCost();
        if (need < 0) {
            // 不可生成：界面已用 tooltip 说明（已有总样板未取走 / 流程或产物无效），这里静默。
            // <b>用户第 4 条</b>：「每一步的相同单元样板都已存在」<b>不再</b>属于不可生成 ——
            // 那种情况仍然生成总样板（单元样板 0 张），因此不会走到这里，也不会再报「流程无效」。
            return;
        }
        final int have = terminal.countRsPatterns();
        if (have < need) {
            return; // 原子性：一张都不生成、一张都不消耗；缺几张由界面 tooltip 说明
        }
        final boolean ok = terminal.generateAssemblyPattern();
        if (!ok) {
            // 理论上到不了这里（generationPatternCost 已做同样校验）；万一发生也绝不扣材料
            if (terminal.getLastExportError() != null) {
                player.displayClientMessage(terminal.getLastExportError(), false);
            }
            return;
        }
        // 产出已落地，按张数从终端自己的样板输入槽扣除（不足张数的情况上面已拦下，这里必然扣满）
        terminal.consumeRsPatterns(need);
        // 单元样板张数 = 总消耗 - 总样板那 1 张
        player.displayClientMessage(Component.translatable(
            "gui.rs_create_compat.sequence_pattern_terminal.generate.done",
            need - 1, need), true);
        if (terminal.allStepsDuplicate()) {
            // <b>用户第 4 条：总样板始终生成</b> —— 这一次单元样板因「跳过重复 / 均已存在」而 0 张产出。
            // 补一句<b>说明</b>（不是错误提示），让玩家知道「总样板已经拿到手了」，
            // 而不是以为点了没反应 / 流程坏了。
            player.displayClientMessage(Component.translatable(
                "gui.rs_create_compat.sequence_pattern_terminal.generate.all_duplicate"), true);
        }
        if (terminal.getLastExportError() != null) {
            player.displayClientMessage(terminal.getLastExportError(), false);
        }
    }

    /**
     * 旧实现从这里开始是「从玩家背包 + 光标直接扣 {@code refinedstorage:pattern}」（用户明确反对：
     * 「你是直接从玩家身上扣是吧？」）。现已整体删除，改用终端自己的样板输入槽
     * （{@link SequencePatternTerminalBlockEntity#countRsPatterns()} /
     * {@link SequencePatternTerminalBlockEntity#consumeRsPatterns(int)}），
     * 因此本类不再有任何触碰玩家背包的扣料逻辑。
     */

    /** 结构变化后给该玩家重发一次步骤机器快照（客户端无权威写入权，只能镜像）。 */
    private void syncStepMachines(final Player player) {
        sendStepMachines(player);
    }

    /** 首次同步是否已发送（避免每 tick 重发）。 */
    private boolean initialStepSyncSent;
    /** 本菜单当前对应的服务端玩家（缓存一次，避免每 tick 遍历玩家列表）。 */
    @Nullable
    private ServerPlayer cachedOwner;

    /** 反查并缓存当前打开本菜单的服务端玩家（无则 null）。 */
    @Nullable
    private ServerPlayer owner() {
        if (cachedOwner != null && cachedOwner.containerMenu == this) {
            return cachedOwner;
        }
        final Player player = getPlayer();
        cachedOwner = player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        return cachedOwner;
    }

    /**
     * 每 tick 的槽位同步：交父类做逐格 diff；首次同步时补发一次「步骤机器快照」，
     * 并按需在几拍之后补发一次「权威快照」（第 49 轮：把重活移出打开界面那一拍）。
     * <p><b>为什么不用 {@code setSynchronizer}</b>：synchronizer 是 {@code ServerPlayer.initMenu} 内部创建的
     * {@code ContainerSynchronizer} 匿名实现，<b>不是</b> ServerPlayer，故
     * {@code synchronizer instanceof ServerPlayer} 恒为 false、同步永远不会发生。这里改为在
     * {@code broadcastChanges}（服务端每 tick 调用）时按「containerMenu == this」反查玩家再发送。</p>
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        final ServerPlayer serverPlayer = owner();
        if (serverPlayer == null) {
            return;
        }
        if (!initialStepSyncSent) {
            initialStepSyncSent = true;
            // 打开界面那一拍：只发轻快照（见 sendStepMachines），全量扫描排到几拍之后
            sendStepMachines(serverPlayer);
            return;
        }
        if (heavySyncPending && --heavySyncCountdown <= 0) {
            heavySyncPending = false;
            sendAuthoritativeSnapshot(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        // 输入原料标记槽：值由导入逻辑写入，Shift 移动会把它搬进背包 = 凭空造物
        if (isMarkerSlot(index)) {
            return ItemStack.EMPTY;
        }
        // 只读展示槽（流程编排窗口 / 产物 / 废料）：绝不允许被 Shift 移动。
        // 流程编排现在展示的是「导入产生的展示数据」（永不掉落的幽灵物品），一旦被 Shift 移进背包
        // 就等于凭空造物；产物 / 废料同理。
        if (isReadOnlyDisplaySlot(index)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            // 总样板槽（唯一 1 格）与样板输入槽（3 格）：Shift+左键 = 取进玩家背包
            // （它们都不在玩家背包段里，需单独处理；取样板输入槽时即把样板带回自己身上）
            if ((index >= SLOT_TOTAL_PATTERN_START && index < SLOT_TOTAL_PATTERN_END)
                || (index >= SLOT_RS_PATTERN_START && index < SLOT_RS_PATTERN_END)) {
                stack = stackInSlot.copy();
                if (!moveItemStackTo(stackInSlot, SLOT_PLAYER_START, Math.min(SLOT_HOTBAR_END, slots.size()),
                    true)) {
                    return ItemStack.EMPTY;
                }
                if (stackInSlot.isEmpty()) {
                    slot.setByPlayer(ItemStack.EMPTY);
                } else {
                    slot.setChanged();
                }
                return stack;
            }
            stack = stackInSlot.copy();
            if (index < SLOT_PLAYER_START) {
                // 终端槽位 → 玩家背包 / 快捷栏（上界做防御性收敛，避免常量与真实槽数不一致时越界崩溃）
                if (!moveItemStackTo(stackInSlot, SLOT_PLAYER_START,
                    Math.min(SLOT_HOTBAR_END, slots.size()), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 样板输入槽（仅 refinedstorage:pattern）或单元样板窗口（仅单元样板，
                // 且只落在当前页在范围内的格）。
                // <b>总样板槽不在此列</b>：它是「只出不进」的产出格（用户要求不可手动放入），
                // 其 mayPlace 恒 false，因此这里不再尝试把总样板塞进去。
                // 标记槽不可快速放入，流程编排行 / 产物 / 废料为只读展示槽。
                final boolean moved = (SequencePatternTerminalBlockEntity.isRefinedStoragePattern(stackInSlot)
                    && moveItemStackTo(stackInSlot, SLOT_RS_PATTERN_START, SLOT_RS_PATTERN_END, false))
                    || (stackInSlot.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())
                    && moveItemStackTo(stackInSlot, SLOT_UNIT_PATTERN_START, SLOT_UNIT_PATTERN_END, false));
                if (!moved) {
                    return ItemStack.EMPTY;
                }
            }
            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return stack;
    }

    /**
     * 关闭界面：把虚拟终端的内容写回<b>物品</b> NBT（由物品打开时才有钩子）。
     * <p>只在<b>服务端</b>执行一次；客户端重建的菜单没有终端引用、也就没有钩子，因此不会误写。
     * 这是「终端自己的槽位持久化」在物品路径上的落点 —— 保证「放入 / 取出不复制、不销毁」。</p>
     */
    @Override
    public void removed(final Player player) {
        super.removed(player);
        if (terminal != null && terminalCloseSaver != null && player != null
            && !player.level().isClientSide()) {
            terminalCloseSaver.run();
        }
    }

    @Override
    public boolean stillValid(final Player player) {
        if (terminal == null) {
            return true; // 客户端重建菜单：无方块实体引用，保持打开（有效性由服务端校验）
        }
        if (terminal.getLevel() == null) {
            return true;
        }
        // 终端物品（高级远程终端）打开时使用虚拟方块实体（位置 BlockPos.ZERO，未放置在世界中）
        final boolean isVirtual = terminal.getBlockPos().equals(net.minecraft.core.BlockPos.ZERO)
            && terminal.getLevel().getBlockEntity(net.minecraft.core.BlockPos.ZERO) != terminal;
        if (isVirtual) {
            return true;
        }
        return terminal.getLevel().getBlockEntity(terminal.getBlockPos()) == terminal
            && player.distanceToSqr(terminal.getBlockPos().getX() + 0.5,
            terminal.getBlockPos().getY() + 0.5,
            terminal.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /** 服务端容器数据：可见行次数按滚动偏移映射到全局步下标；偏移本身也是数据槽之一。 */
    private static final class WindowedData implements ContainerData {
        private final SequencePatternTerminalMenu menu;
        private final SequencePatternTerminalBlockEntity terminal;

        private WindowedData(final SequencePatternTerminalMenu menu,
                             final SequencePatternTerminalBlockEntity terminal) {
            this.menu = menu;
            this.terminal = terminal;
        }

        @Override
        public int get(final int index) {
            if (index < ARRANGEMENT_WINDOW) {
                return terminal.getArrangementCountAt(menu.getArrangementOffset() + index);
            }
            if (index == IDX_LOOPS) {
                return terminal.loops;
            }
            if (index == IDX_SIZE) {
                return terminal.arrangementSize;
            }
            if (index == IDX_AUTOCRAFTER) {
                return terminal.autocrafterIndex;
            }
            if (index == IDX_PATTERN_COUNT) {
                return terminal.countPatterns();
            }
            if (index == IDX_OFFSET) {
                return menu.getArrangementOffset();
            }
            if (index == IDX_RESULT_OFFSET) {
                return menu.getResultOffset();
            }
            if (index == IDX_RESULT_MAX) {
                return maxPageOffset(terminal.resultSlots, RESULT_WINDOW);
            }
            if (index == IDX_SCRAP_OFFSET) {
                return menu.getScrapOffset();
            }
            if (index == IDX_SCRAP_MAX) {
                return maxPageOffset(terminal.scrapSlots, SCRAP_WINDOW);
            }
            if (index >= IDX_RESULT_CHANCE && index < IDX_RESULT_CHANCE + RESULT_WINDOW) {
                return chanceAt(terminal.resultChances, menu.getResultOffset(), index - IDX_RESULT_CHANCE,
                    RESULT_WINDOW);
            }
            if (index >= IDX_SCRAP_CHANCE && index < IDX_SCRAP_CHANCE + SCRAP_WINDOW) {
                return chanceAt(terminal.scrapChances, menu.getScrapOffset(), index - IDX_SCRAP_CHANCE,
                    SCRAP_WINDOW);
            }
            if (index == IDX_UNIT_PAGE) {
                // 下发「收敛后的页码」：库缩小（取空导致卷尾收缩）时页码会自动回到合法区间，
                // 否则客户端会停在一个已经没有内容的页上（窗口全灰、什么都拿不到）。
                return menu.getUnitPage();
            }
            if (index == IDX_UNIT_MAX_PAGE) {
                return SequencePatternTerminalBlockEntity.unitMaxPage(
                    SequencePatternTerminalBlockEntity.unitLibraryEntries(terminal.unitLibrary));
            }
            if (index == IDX_UNIT_ENTRIES) {
                return SequencePatternTerminalBlockEntity.unitLibraryEntries(terminal.unitLibrary);
            }
            if (index == IDX_PATTERN_HIDDEN) {
                return terminal.hiddenPatternCount();
            }
            if (index == IDX_RS_PATTERN_COUNT) {
                return terminal.countRsPatterns();
            }
            if (index == IDX_GENERATE_NEED) {
                // 服务端唯一权威：-1 = 当前不可生成；界面据此置灰按钮并算出「缺几样」
                return terminal.generationPatternCost();
            }
            if (index == IDX_REUSE_INTERMEDIATES) {
                // 服务端唯一权威：开关（存档级 SavedData）。取不到 Level 时读方法自身会回落到默认档（关）。
                return cretae.cookiewyq.rs_create_compat.support.RsccIntermediateReusePolicy
                    .reuseIntermediates(terminal.getLevel()) ? 1 : 0;
            }
            return 0;
        }

        /** 最后一个非空格的页号：条目未超出一页时返回 0（滚动条无滚动需求）。 */
        private static int maxPageOffset(final ItemStackHandler handler, final int window) {
            return Math.max(0, SequencePatternTerminalBlockEntity.highestUsedIndex(handler) / window);
        }

        /** 可见窗口内第 i 格的概率（% 整数），全局下标 = 偏移 × 窗口 + i。 */
        private static int chanceAt(final float[] chances, final int offset, final int indexInWindow,
                                    final int window) {
            final int global = offset * window + indexInWindow;
            if (global < 0 || global >= chances.length) {
                return 100;
            }
            return (int) (Math.max(0F, Math.min(1F, chances[global])) * 100F);
        }

        @Override
        public void set(final int index, final int value) {
            // 全部修改都由服务端按钮逻辑/包处理执行
        }

        @Override
        public int getCount() {
            return DATA_SLOT_COUNT;
        }
    }

    /**
     * 输入原料槽：<b>只读展示</b>槽 —— 值由导入 / 生成逻辑直接写进终端的 {@code ingredientSlot}
     * （本槽的 {@link #getItem()} 只是转发那个 handler），玩家<b>不可放入、不可取出、不可 JEI 拖入</b>。
     * <p><b>为什么取消标记</b>：这个值是配方自动生成的（「这一步吃什么」），玩家手动标记没有任何意义，
     * 只会让自动生成的内容与手改内容互相打架。槽框仍由界面自绘（它同时是流程行槽框的取样图块），
     * 因此这里只改交互与判定，不动任何背景贴图 / 几何。</p>
     */
    private static final class MarkerInputSlot extends Slot {
        private static final net.minecraft.world.Container EMPTY = new net.minecraft.world.SimpleContainer(0);
        private final IItemHandler ingredient;

        private MarkerInputSlot(final IItemHandler ingredient, final int x, final int y) {
            super(EMPTY, 0, x, y);
            this.ingredient = ingredient;
        }

        @Override
        public ItemStack getItem() {
            return ingredient.getSlots() > 0 ? ingredient.getStackInSlot(0) : ItemStack.EMPTY;
        }

        @Override
        public void set(final ItemStack stack) {
            // 仍保留写入口：原版「服务端槽位 → 客户端槽位」的同步就是走 Slot#set，
            // 只读指的是玩家交互，不影响服务端权威值同步到客户端显示。
            final ItemStack value = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
            if (ingredient instanceof IItemHandlerModifiable modifiable && modifiable.getSlots() > 0) {
                modifiable.setStackInSlot(0, value);
            }
            this.setChanged();
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return false; // 只读展示：不可放入（同时挡掉 JEI ghost 拖入，见 SequencePatternTerminalMenu#placeGhost）
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(final ItemStack stack) {
            return 1;
        }

        @Override
        public boolean mayPickup(final Player player) {
            return false; // 只读展示：不可取出
        }

        @Override
        public ItemStack remove(final int amount) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 总样板槽（界面<b>唯一</b> 1 格）：<b>只出不进</b>的真实槽位。
     * <ul>
     *     <li>{@link #mayPlace} 恒 {@code false}：用户要求「不可手动放入（不能显示为『可以使用 / 可放入』）」，
     *     于是手动拖入、Shift 移入、JEI 拖入、漏斗塞入全部被这一处挡住
     *     （底层 handler 的 {@code isItemValid} 仍只收总样板，物流侧行为不变）；</li>
     *     <li>{@link #mayPickup} 为 true、{@link #getMaxStackSize()} = 1：产出的总样板一格一张，
     *     玩家可以取走（取走即腾出位置，才能再次生成）。</li>
     * </ul>
     */
    private static final class TotalPatternSlot extends net.neoforged.neoforge.items.SlotItemHandler {
        private TotalPatternSlot(final IItemHandler handler, final int index, final int x, final int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return false; // 只出不进：生成产出落在这里，玩家不可手动放入
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

    /**
     * 产物 / 废料槽：可见 4×3 的<b>只读展示（标记）槽</b>——玩家既不能取走也不能放入；
     * 内容由终端自身在生成样板 / 导入配方时写入，或由 JEI ghost 拖入标记。
     * 实际读写的是 {@code 偏移 × 窗口 + indexInWindow} 的全局格（偏移由数据槽同步，服务端权威）。
     */
    private static final class WindowedSectionSlot extends Slot {
        private static final net.minecraft.world.Container EMPTY = new net.minecraft.world.SimpleContainer(0);
        private final IItemHandlerModifiable handler;
        private final int indexInWindow;
        private final int windowSize;
        private final java.util.function.IntSupplier offsetSupplier;

        private WindowedSectionSlot(final IItemHandlerModifiable handler,
                                    final int indexInWindow,
                                    final int windowSize,
                                    final java.util.function.IntSupplier offsetSupplier,
                                    final int x,
                                    final int y) {
            super(EMPTY, indexInWindow, x, y);
            this.handler = handler;
            this.indexInWindow = indexInWindow;
            this.windowSize = windowSize;
            this.offsetSupplier = offsetSupplier;
        }

        /** 该可见格对应的全局下标（越界时收敛到最后一格，避免抛异常）。 */
        private int globalIndex() {
            final int global = offsetSupplier.getAsInt() * windowSize + indexInWindow;
            return Math.max(0, Math.min(global, handler.getSlots() - 1));
        }

        @Override
        public ItemStack getItem() {
            return handler.getStackInSlot(globalIndex());
        }

        @Override
        public void set(final ItemStack stack) {
            handler.setStackInSlot(globalIndex(), stack);
            this.setChanged();
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return false; // 只读展示：玩家不可放入（终端自身直接写 handler）
        }

        @Override
        public boolean mayPickup(final Player player) {
            return false; // 只读展示：玩家不可取走
        }

        @Override
        public ItemStack remove(final int amount) {
            return ItemStack.EMPTY; // 只读展示：任何取走路径都返回空
        }
    }

    /**
     * 单元样板槽窗口（v7）：界面一次可见 {@code UNIT_WINDOW} 格，是终端<b>单元样板库</b>的翻页窗口。
     * <ul>
     *     <li><b>映射只有一份</b>：第 {@code page} 页第 {@code cell} 格 → 库下标
     *     {@link SequencePatternTerminalBlockEntity#unitLibraryIndex}(page, cell)；
     *     页的来源两端一致（服务端字段 / 客户端 ContainerData 下发值），
     *     因此「界面上显示的格」与「服务端取走的格」永远是同一格；</li>
     *     <li><b>越界格不可交互</b>：映射下标超出库的条目数时 {@link #isActive()} 为 false，
     *     既不能放也不能取（可交互边界 = 库条目数，见
     *     {@link SequencePatternTerminalBlockEntity#unitCellActive}）；</li>
     *     <li><b>槽位本身是服务端权威的真实容器</b>：服务端直接读写 {@code unitLibrary}（放入自动扩容、
     *     取出自动收缩），客户端只用本地 {@code UNIT_WINDOW} 格镜像承接原版槽位同步的内容。</li>
     * </ul>
     */
    private static final class UnitWindowSlot extends Slot {
        private static final net.minecraft.world.Container EMPTY = new net.minecraft.world.SimpleContainer(0);
        /** 服务端：终端（取单元样板库 + 标脏）；客户端为 null。 */
        @Nullable
        private final SequencePatternTerminalBlockEntity terminal;
        /** 窗口内下标（0..UNIT_WINDOW-1）。 */
        private final int cell;
        private final SequencePatternTerminalMenu menu;
        /** 客户端镜像（按「窗口内下标」存内容）。 */
        private final ItemStackHandler mirror = new ItemStackHandler(UNIT_WINDOW);

        private UnitWindowSlot(@Nullable final SequencePatternTerminalBlockEntity terminal,
                               final int cell,
                               final int x,
                               final int y,
                               final SequencePatternTerminalMenu menu) {
            super(EMPTY, cell, x, y);
            this.terminal = terminal;
            this.cell = cell;
            this.menu = menu;
        }

        /** 该格映射到的单元样板库下标（两端唯一算式）。 */
        private int libraryIndex() {
            return SequencePatternTerminalBlockEntity.unitLibraryIndex(menu.getUnitPage(), cell);
        }

        /** 该格是否落在库的可写边界内（越界 = 不可交互空位）。 */
        private boolean inRange() {
            return SequencePatternTerminalBlockEntity.unitCellActive(
                menu.getUnitPage(), cell, menu.getUnitEntries());
        }

        @Override
        public boolean isActive() {
            return super.isActive() && inRange();
        }

        @Override
        public ItemStack getItem() {
            if (terminal == null) {
                // 客户端：内容由原版槽位同步写进镜像；越界格一律显示空，
                // 避免「上一页的残留内容」停在一个已经不可交互的格上。
                return inRange() ? mirror.getStackInSlot(cell) : ItemStack.EMPTY;
            }
            return inRange() ? terminal.unitLibrary.getStackInSlot(libraryIndex()) : ItemStack.EMPTY;
        }

        @Override
        public void set(final ItemStack stack) {
            final ItemStack value = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            if (terminal == null) {
                mirror.setStackInSlot(cell, value);
            } else if (inRange()) {
                terminal.unitLibrary.setStackInSlot(libraryIndex(), value);
                terminal.setChanged();
            }
            this.setChanged();
        }

        @Override
        public ItemStack remove(final int amount) {
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            if (terminal == null) {
                return mirror.extractItem(cell, amount, false);
            }
            if (!inRange()) {
                return ItemStack.EMPTY;
            }
            final ItemStack removed = terminal.unitLibrary.extractItem(libraryIndex(), amount, false);
            if (!removed.isEmpty()) {
                terminal.setChanged();
            }
            return removed;
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return inRange() && !stack.isEmpty()
                && stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get());
        }

        @Override
        public boolean mayPickup(final Player player) {
            return inRange();
        }

        @Override
        public int getMaxStackSize() {
            // 一格一张（与页码「第 x/y 页」按格计数一致）：Shift 一次只放 1 张，
            // 不会把一整叠样板挤进同一格；取走时也按 1 张一次，任何情况下都不吞物品。
            return 1;
        }
    }

    /**
     * 流程编排滚动窗口槽：local（0..WINDOW-1）经偏移映射到全局步下标，映射无上限的动态列表。
     * <p>服务端每次访问都通过 {@code source} 现取 {@link SequencePatternTerminalBlockEntity#arrangementView()}
     * （导入会在运行期把数据源从「老存档真实容器」切到「展示数据」）；客户端（source == null）为本地镜像槽。
     * 窗口外的行 {@link #isActive()} 为 false，因此既不渲染也不可悬浮/点击。</p>
     * <p><b>只读</b>：流程完全由配方导入生成，玩家既不能放入（{@link #mayPlace} 恒 false，
     * 否则会把真实样板塞进永不掉落的展示数据 = 吞物品），也不能取走。写入只由方块实体自己做。</p>
     */
    private static final class ArrangementRowSlot extends Slot {
        private static final net.minecraft.world.Container EMPTY = new net.minecraft.world.SimpleContainer(0);
        /** 服务端：流程编排数据源（每次现取，见类注释）；客户端为 null。 */
        @Nullable
        private final java.util.function.Supplier<IItemHandlerModifiable> source;
        private final int indexInWindow;
        private final SequencePatternTerminalMenu menu;
        private ItemStack localStack = ItemStack.EMPTY;

        private ArrangementRowSlot(@Nullable final java.util.function.Supplier<IItemHandlerModifiable> source,
                                   final int indexInWindow,
                                   final int x,
                                   final int y,
                                   final SequencePatternTerminalMenu menu) {
            super(EMPTY, indexInWindow, x, y);
            this.source = source;
            this.indexInWindow = indexInWindow;
            this.menu = menu;
        }

        /** 当前数据源（服务端）；客户端为 null。 */
        @Nullable
        private IItemHandlerModifiable backing() {
            return source == null ? null : source.get();
        }

        private int globalIndex() {
            return menu.getArrangementOffset() + indexInWindow;
        }

        private boolean valid() {
            return globalIndex() < menu.getArrangementSize();
        }

        @Override
        public boolean isActive() {
            return super.isActive() && valid();
        }

        @Override
        public ItemStack getItem() {
            final IItemHandlerModifiable backing = backing();
            if (backing == null) {
                return localStack;
            }
            if (!valid() || globalIndex() >= backing.getSlots()) {
                return ItemStack.EMPTY;
            }
            return backing.getStackInSlot(globalIndex());
        }

        @Override
        public void set(final ItemStack stack) {
            final ItemStack copy = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
            final IItemHandlerModifiable backing = backing();
            if (backing != null) {
                if (!valid() || globalIndex() >= backing.getSlots()) {
                    return;
                }
                backing.setStackInSlot(globalIndex(), copy);
            } else {
                localStack = copy;
            }
            this.setChanged();
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return false; // 只读展示：真实样板一旦放进来就进了「永不掉落」的展示数据 = 吞物品
        }

        @Override
        public boolean mayPickup(final Player player) {
            return false; // 标记不可直接拿起（空手持点击清除）
        }

        @Override
        public ItemStack remove(final int amount) {
            return ItemStack.EMPTY;
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
