package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.ExporterExecutorRowWidget;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 总线「类别详细配置」子界面（延长型输出 / 延长型输入两条总线共用）。
 *
 * <h2>界面结构：<b>两级</b> —— 配方标签页 + 页内分节（本轮重做，用户原话）</h2>
 * <pre>
 *   ┌─ 类别详细配置 ───────────────────────────────────────────────┐
 *   │ [搜索框]                                                     │
 *   │ [全部清空] [全部折叠]                                         │
 *   │ ┌ 列车轨道 │ 坚固板 │ 精密构件 ┐  ← 第一级：配方标签页（可点/可滚）│
 *   │ ├──────────────────────────────────────────────────────────┤ │
 *   │ │ 原料            1/3   ← 第二级：本页（= 本配方）的分节      │ │
 *   │ │   ☑ 铁砧台阶 …                                             │ │
 *   │ │ 输入时原料      0/2                                        │ │
 *   │ │   ☐ 铁粒 / 锌粒                                            │ │
 *   │ │ 中间产物        1/3                                        │ │
 *   │ │   ☑ 未完成铁轨   第 1 步                                   │ │
 *   │ │ 成品 / 废料 / 流体 …                                        │ │
 *   ├─────────────────────────────────────────────────────────────┤ │
 *   │ 已选 12 / 40 项                            点「确定」提交      │
 *   │ [确定]            [取消]                                     │
 *   └─────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>用户原话：「<b>我是要按照列车轨道和这些不同的产物来分，就是分页懂不懂？就是一页一页这样的，
 * 然后上面有个标签页可以选那种的。每一页都是对应的某一个配方的所有东西</b>」。因此：</p>
 * <ol>
 *     <li><b>第一级 = 配方标签页</b>：一行可点击的标签（{@link #TABS_Y}），<b>一个标签 = 一条配方</b>
 *     （文字用配方结果物的悬浮名；取不到才退注册名 path）；当前页高亮；配方多到放不下时
 *     <b>滚轮 / 左右箭头</b>翻看，<b>绝不溢出</b>；</li>
 *     <li><b>第二级 = 页内分节</b>：每页里列出<b>这套配方自己的</b>全部类别，按固定六节排
 *     （{@link #GROUP_ORDER}：原料 → 输入时原料 → 流体 → 成品 → 废料 → 中间产物），节内是原有的
 *     勾选条目；<b>每页只出现属于该配方的条目</b>；</li>
 *     <li><b>兜底页</b>：没有任何配方认领的条目（判不出归属）落在「{@code tab.other}」页，
 *     <b>不丢、不混进某个配方页</b>；</li>
 *     <li><b>没有样板时的配方页</b>（2026-10-06 用户第 2 条，<b>本轮改口径</b>）：执行舱
 *     <b>整条链一台单元样板都没有</b>时，类别表是空的 ⇒ 配方页无从反推。服务端为此在快照里补
 *     <b>一条只喂标签页的标记类别</b>（{@link RsccBusCategory#RECIPE_TAB_PREFIX} + 处理器类型 id），
 *     界面据此<b>列出「所有使用该处理器类型的序列装配配方」，一配方一页</b>（页名 = 配方结果物名），
 *     页内仍是一条都不显示（没有单元样板就没有「具体配方 + 步序」，没有任何可交接的件）。
 *     <p><b>为什么不再是一个「处理器类型页」</b>：用户原话「你应该列出来的不是「使用」这一栏，
 *     而是所有包含「使用」的那些配方」—— 页名走 {@code RecipeTypeNames} 时，
 *     {@code create:deploying} 会命中 Create 自己的 {@code create.recipe.deploying}（中文「使用」），
 *     于是玩家看到的是一个<b>动词页</b>、既看不出是机器也看不出是哪个配方。
 *     {@link #TAB_RECIPE_TYPE_PREFIX} 只作为「客户端配方管理器还没加载 / 该类型一条配方都没有」
 *     时的兜底页保留。</p>
 *     该标记<b>不画行、不可勾选、不参与任何计数</b>，
 *     所有会产出行 / 计数 / 命中的枚举处都先经 {@link #isTabOnly} 跳过它；</li>
 *     <li><b>共享件</b>（同一件东西被两条及以上配方用到，例如「齿轮」既是精密构件的投入物、
 *     又是它自己的废料）<b>同时出现在它涉及的每一页</b>，行内标注「多配方共用」。
 *     <p><b>本轮收窄（2026-10-06 用户实测）</b>：这条只适用于<b>流体 / 成品 / 废料</b> ——
 *     它们的身份就是「物品本身」，没有配方上下文。<b>物品输入类别不共享</b>：
 *     服务端按配方登记类别（{@code input:<配方id>#<物品>}），因此「铁粒」在精密构件页与
 *     列车轨道页是<b>两个类别</b>，各显示各的候选（精密构件只有铁粒，列车轨道才有铁粒 + 锌粒）
 *     —— 用户原话：「精密构件并不能用到锌粒」。</li>
 * </ol>
 * <p><b>为什么必须这样（而不是「类别 · 配方名」后缀）</b>：后缀式表头把「配方」压成同一页里的一段，
 * 玩家仍要在一条长列表里找「哪一段是列车轨道的」；改成标签页后，「这一页就是这套配方」成为
 * 唯一的组织维度，页与页之间在结构上不可能互相混淆。</p>
 *
 * <h2>标签页 / 分节的数据从哪来（客户端只读，不加服务端协议）</h2>
 * <ul>
 *     <li><b>配方归属</b>：客户端读<b>同一份</b> Create {@code sequenced_assembly} 配方数据
 *     （{@link #collectAssemblyInputs}），把「物品 / 流体 → 所属配方 id（们）」与
 *     「配方 id → 可读标签」记下来；服务端快照一个字节都不用改；</li>
 *     <li><b>中间产物</b>直接用类别 id 自带的配方（{@code intermediate:<配方id>:<步序>}，
 *     服务端已按配方拆开）—— 这是最权威的一路；</li>
 *     <li><b>原料 / 输入时原料</b>的分流 = 「该配方的起步原料 vs 该配方各步的投入物」（同一份 Create 数据，
 *     与服务端的 {@code startIngredients} / {@code stepExtraInputs} 同源），<b>按配方上下文判定</b>：
 *     配方来自类别 id 自带的配方段（{@code input:<配方id>#<物品>}），旧格式 id 退回当前标签页
 *     （{@link #startingItemsByRecipe} / {@link #stepInputItemsByRecipe}；全局同名集合只作判不出配方时的兜底）；</li>
 *     <li>判不出归属 ⇒ {@link #TAB_OTHER} 兜底页，<b>绝不丢项</b>。</li>
 * </ul>
 *
 * <h2>既有行为<b>一律</b>保留（切换标签页只改「看哪一页」，不改任何语义）</h2>
 * <ul>
 *     <li>{@link #selected} 是编辑态的唯一真相：切页 / 折叠 / 搜索都不碰它，提交仍走
 *     {@link #committedIds()}（先按执行舱给的类别顺序，再接上看不到的旧 id）；</li>
 *     <li>「全部清空」批量置位、「全部折叠 / 全部展开」只改显示、表头三态勾选框 = 本节全选；</li>
 *     <li>搜索：物品 / 流体名过滤当前页；配方名 → 「跳到「X」」结果行（点一下切页）；
 *     分节名 / 工序机器名 → 本页内跳转行；其它页有命中时也给出切页行（搜索不会走进死胡同）；</li>
 *     <li>「可复用」标记（同键步骤）与行内「第 N 步」后缀照旧；</li>
 *     <li>服务端权威：本界面不缓存服务端状态、不做乐观更新，出口只有
 *     {@link #commitSink} 一处（取消 / Esc 一律丢弃改动）。</li>
 * </ul>
 *
 * <h2>渲染硬规则</h2>
 * <ul>
 *     <li><b>不新画任何背景贴图</b>：面板走基类 {@link ChildConfigScreen} 的原版九宫格，
 *     标签页 / 列表底 / 勾选框 / 分节表头 / 折叠三角全部由 Java {@code fill} 运行时绘制
 *     （不开启任何手工批次，因此不存在「忘了 endBatch」的风险）；
 *     条目行的图标外框复用 {@link McGui#slotFrame}（原素材 17×17 观感，同样是 Java 绘制）；</li>
 *     <li>文字一律 {@code dropShadow = false}；</li>
 *     <li>所有可交互元素（搜索框 / 全部清空 / 全部折叠 / <b>每个配方标签页</b> / 标签页左右箭头 /
 *     列表行与分节表头 / 折叠三角 / 底部提示 / 确定 / 取消）都有<b>手动渲染</b>的 tooltip
 *     （本模组 GUI 不会自动渲染 tooltip），且 hover 判定与绘制范围同源；</li>
 *     <li>几何常量与 {@code tmp_textures/verify_gui_layout.py} 的 {@code build_bus_category_config}
 *     一一对应（精灵坐标 = 本文件里的 Menu 坐标 − 1），由布局校验保证不重叠 / 不越界 / 在面板内。</li>
 * </ul>
 */
public class BusCategoryConfigScreen extends ChildConfigScreen {
    /** 与主界面按钮共用同一个前缀常量（只有一处定义，改前缀不会漏改一半）。 */
    private static final String LANG = ExporterExecutorRowWidget.CONFIG_LANG;

    // ==================== 几何（Menu 坐标 = 相对面板左上角；精灵坐标 = Menu − 1） ====================
    /**
     * 面板尺寸 252×<b>262</b>：在旧的 244 上<b>加高 18</b>，正好容纳新增的「配方标签页」一行
     * （15px 行高 + 3px 缝隙）。<b>列表高度刻意不变</b>（仍是 162 = 9 行 × 18），
     * 因此「一屏 9 行」的既有容量与滚动模型一个都没改；只是整块往下挪了 18。
     */
    private static final int PANEL_W = 252;
    private static final int PANEL_H = 262;
    /** 标题：(10, 8) 起（正常字号）。 */
    private static final int TITLE_X = 10;
    private static final int TITLE_Y = 8;
    /** 搜索框：(70, 5) 172×16 —— 与标题同一行、右侧占满到面板内沿。 */
    private static final int SEARCH_X = 70;
    private static final int SEARCH_Y = 5;
    private static final int SEARCH_W = 172;
    private static final int SEARCH_H = 16;
    /**
     * 控制带（Menu y 26..39）：<b>只有「全部清空」与「全部折叠 / 全部展开」两个按钮</b>。
     * <p><b>为什么这里从四个按钮减到两个（用户原话）</b>：「那个<b>全部物品 / 全部流体 / 全部中间产物</b>
     * （一键整选）就<b>没必要</b>，但<b>全部清空</b>之类的<b>还是有必要的</b>」——
     * 三个「一类整选」按钮本轮<b>删除</b>；「全部清空」保留；另加一个折叠开关，
     * 让「一页一页」的目录视图一键可达。</p>
     */
    private static final int BAND_Y = 26;
    private static final int BAND_H = 14;
    private static final int CLEAR_X = 8;
    private static final int CLEAR_W = 57;
    /** 折叠开关：与「全部清空」留 4px 缝。 */
    private static final int FOLD_X = CLEAR_X + CLEAR_W + 4;
    private static final int FOLD_W = 71;
    /**
     * <b>配方标签页行</b>：(8, 44) 236×15（Menu 坐标）—— 第一级组织维度。
     *
     * <p><b>为什么它独立占一行、而不是塞进控制带</b>：用户要求「上面有个标签页可以选」，
     * 标签页必须<b>常驻可见</b>（不随列表滚动）；控制带里「全部清空 57 + 全部折叠 71」之后
     * 只剩 104px，放不下 3 个及以上可读标签，因此新开一行并把列表整体下移 18。</p>
     * <p><b>放不下怎么办</b>：标签总宽超过可用宽度时右端出现 ◀ / ▶ 两个 10px 箭头，
     * 行内滚轮也能左右滚（{@link #TAB_ARROW_W} / {@link #mouseScrolled}）—— <b>绝不溢出</b>，
     * 当前页切过去时自动滚到可见处（{@link #ensureActiveTabVisible()}）。</p>
     */
    private static final int TABS_X = 8;
    private static final int TABS_Y = 44;
    private static final int TABS_W = 236;
    private static final int TABS_H = 15;
    /** 标签之间的缝隙 / 标签内左右留白。 */
    private static final int TAB_GAP = 2;
    private static final int TAB_PAD_X = 5;
    /** 单个标签的最大宽度（再长就截断加省略号；名字本身是配方产物名，一般很短）。 */
    private static final int TAB_MAX_W = 76;
    /** 溢出时右端两个翻页箭头的尺寸（◀ / ▶ 各占一个）。 */
    private static final int TAB_ARROW_W = 10;
    /**
     * <b>「全部配方」选择器</b>：常驻在标签页行<b>最右端</b>的固定宽度按钮（{@value #PICK_W}px）。
     *
     * <p><b>为什么在有箭头翻页之外还要它（用户第 2 条）</b>：一行标签放不下 40 个配方，靠 ◀ / ▶
     * 逐个蹭太慢、也看不到全貌。点这个按钮弹出一份<b>可滚动、可键盘（↑↓/回车/Esc）、当前页高亮</b>
     * 的完整配方清单，任何一页都能一步选到；列表与按钮的几何都落在既有的
     * {@code TABS(8,44,236,15)} 矩形内（弹出层是覆盖层，不改任何静态矩形），
     * 因此布局校验与既有行为一个都不受影响。</p>
     */
    private static final int PICK_W = 42;
    /** 弹出选择器的宽 / 行高 / 可见行数（高 = 12 行 × 12px = 144，落在列表区内，不越面板）。 */
    private static final int PICK_POP_W = 150;
    private static final int PICK_ROW_H = 12;
    private static final int PICK_VISIBLE = 12;
    private static final int PICK_POP_H = PICK_ROW_H * PICK_VISIBLE;
    /** 弹出选择器右侧滚动条宽度。 */
    private static final int PICK_BAR_W = 4;
    /**
     * 弹出选择器顶端「搜索配方」输入框占用的高度（2026-10-05 用户要求）。
     * <p>用户原话：<i>「输入输出总线那多个配方点击展开之后的搜索框你还是没有加」</i>。</p>
     */
    private static final int PICK_SEARCH_H = 14;
    /** 可滚动列表：(8, 62) 236×162（9 行 × 18px）—— 由 44 下移 18，给标签页行让位，高度不变。 */
    private static final int LIST_X = 8;
    private static final int LIST_Y = 62;
    private static final int LIST_W = 236;
    private static final int LIST_H = 162;
    private static final int ROW_H = 18;
    private static final int VISIBLE_ROWS = LIST_H / ROW_H;
    /** 滚动条：贴在列表内右侧（列表本体不含它，两者几何互不重叠）。 */
    private static final int SCROLL_X = LIST_X + LIST_W - 10;
    private static final int SCROLL_W = 10;
    /**
     * 底部提示行（左：已选计数 / 自动模式只读；右：怎么改）—— 同样下移 18。
     *
     * <p><b>只有一行、两段</b>：左段左对齐到 {@link #CLEAR_X}，右段<b>右对齐到列表右缘</b>
     * （{@code LIST_X + LIST_W}）。两段之间留 {@value #HINT_GAP}px 硬间隙，且各自按<b>像素</b>截断
     * 到「半行宽」以内 —— 因此无论哪种语言，都不可能重叠、不可能越出面板（见 {@link #drawHint}）。</p>
     */
    private static final int HINT_Y = 228;
    /** 底部提示行左右两段之间的最小硬间隙（像素；保证「糊在一起」不可能再发生）。 */
    private static final int HINT_GAP = 8;
    /** 确定 / 取消：(8, 242) 与 (130, 242)，各 114×16。 */
    private static final int ACTION_Y = 242;
    private static final int ACTION_W = 114;
    private static final int ACTION_H = 16;
    private static final int CANCEL_X = 130;

    // ---------- 列表内一行的几何（相对列表左上角；绘制与命中共用同一批常量） ----------
    /** 组表头左侧的折叠三角：局部 x 4 起，宽 7（点击它 = 折叠 / 展开本组）。 */
    private static final int ROW_TRI_X = 4;
    private static final int ROW_TRI_W = 7;
    /** 三态勾选框（组表头 = 本组全选；条目行 = 单项勾选）。 */
    private static final int ROW_CHECK_X = 14;
    private static final int ROW_CHECK_SIZE = 9;
    /** 条目行的图标：槽框画在 (ROW_ICON_X − 1, y)，物品画在 (ROW_ICON_X, y + 1)。 */
    private static final int ROW_ICON_X = 30;
    /** 条目行名字 / 表头文字的起点（两者对齐，视觉上成列）。 */
    private static final int ROW_TEXT_X = 30;
    private static final int ROW_NAME_X = 52;
    /** 行内文字可用宽度（列表宽 − 右侧滚动条 − 左边距）。 */
    private static final int ROW_TEXT_W = LIST_W - 12 - ROW_NAME_X;
    /** 「第 N 步」后缀右端留白（不让它压到滚动条）。 */
    private static final int ROW_SUFFIX_PAD = 12;

    // ==================== 配色（与主界面类别条同族；全部 Java 绘制） ====================
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF1F1F1F;
    private static final int COLOR_DIM = 0xFF5A5A5A;
    private static final int COLOR_LIST_BG = 0xFFC6C6C6;
    private static final int COLOR_HEADER_BG = 0xFF9E9E9E;
    private static final int COLOR_BORDER = 0xFF2B2B2B;
    private static final int COLOR_CHECK_OFF = 0xFFE0E0E0;
    private static final int COLOR_CHECK_ON = 0xFF2E7D32;
    private static final int COLOR_CHECK_PARTIAL = 0xFF8BC34A;
    private static final int COLOR_ROW_HOVER = 0x30000000;
    /** 中间产物「第 N 步」后缀的颜色（橙，与主界面类别条上的步骤角标同色系）。 */
    private static final int COLOR_STEP_TEXT = 0xFFE65100;
    /** 搜索结果「跳到某一页」行的文字色（蓝，与勾选态区分开）。 */
    private static final int COLOR_JUMP_TEXT = 0xFF1A4E8A;
    private static final int COLOR_JUMP_BG = 0xFFDCE6F2;
    /** 折叠三角的颜色（展开 = 深色实心向下；折叠 = 深色实心向右）。 */
    private static final int COLOR_TRI = 0xFF2B2B2B;
    /**
     * 自动模式（只读）下列表的浅灰蒙版：用「面板同色 + 半透明」把内容整体压淡
     * （与 MC 原版禁用按钮的观感一致），勾选标记仍透出来。
     */
    private static final int COLOR_LOCKED_WASH = 0x55C6C6C6;

    // ---------- 配方标签页行配色（本轮新增；全部 Java 绘制，不新增贴图） ----------
    /** 标签页行的底槽（比列表底略深一点，看得出这是「一排可点的页签」）。 */
    private static final int COLOR_TAB_TRACK_BG = 0xFFB4B4B4;
    /** 页签的 1px 描边色（与列表底框同色，视觉上成一体）。 */
    private static final int COLOR_TAB_BORDER = 0xFF2B2B2B;
    /** 未选中的标签页底色 / 悬停底色 / 选中底色（选中 = 与列表同色的亮底，视觉上「连成一片」）。 */
    private static final int COLOR_TAB_BG = 0xFF9E9E9E;
    private static final int COLOR_TAB_HOVER_BG = 0xFFCFCFCF;
    private static final int COLOR_TAB_ACTIVE_BG = 0xFFC6C6C6;
    /** 未选中 / 选中的文字色。 */
    private static final int COLOR_TAB_TEXT = 0xFF4A4A4A;
    private static final int COLOR_TAB_ACTIVE_TEXT = 0xFF1F1F1F;
    /** 翻页箭头色。 */
    private static final int COLOR_TAB_ARROW = 0xFF3A3A3A;

    // ---------- 「全部配方」弹出选择器配色（覆盖层；全部 Java 绘制，不新增贴图） ----------
    /** 弹出层底色 / 1px 描边 / 行悬停底色 / 键盘光标底色。 */
    private static final int COLOR_PICK_BG = 0xFFE8E8E8;
    private static final int COLOR_PICK_BORDER = 0xFF2B2B2B;
    private static final int COLOR_PICK_HOVER = 0xFFCFD8DC;
    private static final int COLOR_PICK_CURSOR = 0xFFB0BEC5;
    /** 行文字色（当前页用 {@link #COLOR_CHECK_ON}，其余用 {@link #COLOR_TEXT}）。 */
    private static final int COLOR_PICK_TRACK = 0xFFB4B4B4;

    // ==================== 六个固定分类（用户指定的顺序，不可调换） ====================
    /**
     * ① 原料：<b>本标签页那条配方</b>的<b>起步原料</b>
     * （Create {@code sequenced_assembly} 配方顶层 {@code ingredient}，含标签的每个候选）。
     * <p>判据按配方取（{@link #startingItemsByRecipe}），不再用全局物品集合 ——
     * 后者会让「A 配方的起步原料」在 B 配方页里也显示成原料（用户否定的「混在一起」）。</p>
     */
    private static final String GROUP_MATERIALS = "materials";
    /**
     * ② 输入时原料：<b>本配方</b>加工途中投进去的那些
     * （各步下标 ≥1 的投入物，以及样板里玩家手填的额外投入物）。
     * <p>判据同样按配方取（{@link #stepInputItemsByRecipe}）：本配方起步原料之外的物品输入
     * 一律落这里，因此不会与①互相抢条目。</p>
     */
    private static final String GROUP_FEEDSTOCK = "feedstock";
    /** ③ 流体。 */
    private static final String GROUP_FLUIDS = "fluids";
    /** ④ 成品：{@code results} 池里的<b>主产物</b>（{@code result:} 前缀）。 */
    private static final String GROUP_PRODUCTS = "products";
    /**
     * ⑤ 废料：{@code results} 池里除主产物以外的概率产出（{@code scrap:} 前缀）。
     * <p><b>为什么本轮必须单独成组（用户第 3 条）</b>：旧实现把 {@code isProduct()}（成品 ∪ 废料）
     * 一律塞进「成品」，于是精密构件产线的「安山合金」看起来就是成品；用户要求废料要有自己的一组。</p>
     */
    private static final String GROUP_SCRAP = "scrap";
    /** ⑥ 中间产物（<b>不再按步骤细分</b>）。 */
    private static final String GROUP_INTERMEDIATES = "intermediates";

    /** 六个分类的固定顺序（界面从上到下就是这个顺序，也是自检断言的那一份）。 */
    private static final List<String> GROUP_ORDER = List.of(
        GROUP_MATERIALS, GROUP_FEEDSTOCK, GROUP_FLUIDS, GROUP_PRODUCTS, GROUP_SCRAP,
        GROUP_INTERMEDIATES);

    /**
     * <b>兜底页的标签键</b>（没有任何配方认领的条目落在这里）。
     *
     * <p>用 {@code U+0000} 开头是刻意的：<b>任何配方 id 都不可能以 NUL 开头</b>
     * （{@code ResourceLocation} 只允许 {@code [a-z0-9_.-]} 与一个冒号），
     * 因此这个哨兵永远不会与真配方撞车，不需要再加一层「是不是哨兵」的旁路判断。</p>
     */
    private static final String TAB_OTHER = "\u0000other";

    /**
     * <b>「处理器类型」标签页的键前缀</b>（尾部接 {@code recipeType} id，如 {@code create:pressing}）。
     *
     * <h2>为什么也用 {@code U+0000} 开头</h2>
     * <p>与 {@link #TAB_OTHER} 同一手法：{@code ResourceLocation} 只允许 {@code [a-z0-9_.-]} 与一个冒号，
     * <b>任何真配方 id 都不可能以 NUL 开头</b>，因此「配方页」与「处理器类型页」在结构上永远是两页
     * —— 不会因为某条配方的 id 恰好与处理器类型同名而被合并（合并会让空页突然冒出行来）。</p>
     *
     * <p>这一页只可能来自服务端补的那条<b>只喂标签页</b>的标记类别
     * （{@link RsccBusCategory#RECIPE_TAB_PREFIX}）：整链没有单元样板时照建页，页内零行。
     * <b>本轮起它只是兜底</b> —— 只要客户端配方管理器里查得到「含该处理器类型步骤的序列装配配方」，
     * 标签页就改成<b>一配方一页</b>（见 {@link #recipeTabKeysOf}），本前缀不再出现在标签页上。</p>
     */
    private static final String TAB_RECIPE_TYPE_PREFIX = "\u0000type:";

    /**
     * 分节色带（表头左端 3px 竖条）：与主界面类别条上的分组色带同一套颜色，
     * 让「这一节是什么」在两个界面上一眼可辨。索引与 {@link #GROUP_ORDER} 一一对应。
     */
    private static final int[] GROUP_COLORS = {
        0xFF8BC34A, // ① 原料
        0xFF26A69A, // ② 输入时原料
        0xFF29B6F6, // ③ 流体
        0xFF2E7D32, // ④ 成品
        0xFF9E9D24, // ⑤ 废料（与成品同族的橄榄绿，一眼看出是「另一类产出」）
        0xFFFB8C00, // ⑥ 中间产物
    };

    /**
     * 行的种类：<b>配方标签页跳转行</b> / 分节表头 / 普通条目 / <b>搜索结果里的「跳到某一节」</b>。
     * <ul>
     *     <li>{@link #TAB}：搜索结果里代表「另一套配方的那一页」—— 点一下 = <b>切页</b>；</li>
     *     <li>{@link #JUMP}：搜索结果里代表「本页内的某一节」—— 点一下 = 展开并滚到它；</li>
     *     <li>{@link #HEADER}：本页内的分节表头（勾选框 = 本节全选，三角 = 折叠）；</li>
     *     <li>{@link #ITEM}：条目（点一下 = 勾选 / 取消）。</li>
     * </ul>
     */
    private enum Kind { TAB, JUMP, HEADER, ITEM }

    /**
     * 列表里的一行。
     *
     * @param kind     行的种类（见 {@link Kind}）
     * @param groupKey <b>分节键</b>（= 六节之一，{@link #GROUP_ORDER} 的成员）或
     *                 {@link Kind#TAB} 行上的<b>配方标签页键</b>
     * @param text     行内文字（表头带「已选 / 全部」计数；条目行是显示名）
     * @param category 条目行持有的类别对象（表头 / 跳转行为 {@code null}）
     * @param members  该行作用的类别 id 集合（表头 = 本节全部；跳转 / 标签行 = 目标页的全部）
     */
    private record Line(Kind kind, String groupKey, String text,
                        @Nullable RsccBusCategory category, List<String> members) {
    }

    /** 打开本界面时的类别快照（服务端权威；本界面只在内存里读它，不缓存、不更新）。 */
    private final List<RsccBusCategory> categories;
    /** 编辑中的已选类别 id（<b>唯一</b>的真相：清空 / 单项 / 表头全选都改它，提交也只看它）。 */
    private final Set<String> selected = new LinkedHashSet<>();
    /**
     * 被折叠的<b>分节</b>（只影响本界面的显示，不参与提交）。
     * <p>键 = 六节之一（{@link #GROUP_ORDER} 的成员），<b>不区分配方页</b>：玩家点「原料」折叠一次，
     * 切到别的配方页时「原料」也是折叠的 —— 折叠表达的是「这一节我先不看」，
     * 与「正在看哪套配方」正交，因此不按页分别记（也避免了「切页后折叠状态莫名变化」）。</p>
     */
    private final Set<String> collapsed = new LinkedHashSet<>();
    /** 是否处于「全自动收回」模式（只影响提示文案；勾选照旧编辑并提交）。 */
    private final boolean autoMode;
    /** 提交回调（由主界面传入；即既有的服务端包通道）。 */
    private final Consumer<List<String>> commitSink;

    /** 全部类别 id（提交时排序用；顺序 = 执行舱给的类别顺序）。 */
    private final List<String> categoryOrder = new ArrayList<>();
    /**
     * <b>「原料」判据（全局兜底份）</b>：Create {@code sequenced_assembly} 配方的<b>起步原料</b>物品
     * （每条配方顶层 {@code ingredient}；也就是「开一件新的在制件」的那一件）。
     * <p><b>只在判不出配方上下文时使用</b>（旧格式 id + 兜底页 / 本次没扫到配方），
     * 正常路径见 {@link #startingItemsByRecipe} —— 全局集合会把「A 配方的起步原料」与
     * 「B 配方的步内投入物」混为一谈，正是用户否定的那种「混在一起」。</p>
     */
    private final Set<Item> startingItems = new HashSet<>();
    /**
     * <b>「输入时原料」判据</b>：各步真正消耗的<b>步内投入物</b>
     * （{@link SequencedRecipeProbe#stepInputItems} —— 跳过承载过渡件的下标 0，取的正是「加工途中投进去的那一件」）。
     *
     * <h2>为什么不看「是不是产出侧的物品」（本轮修正，用户第 3 条）</h2>
     * <p>旧判据是「该输入物品同时出现在产出侧（{@code result:} / {@code scrap:}）里」。
     * 那与「这一件是不是在加工途中投进去的」毫无关系：精密构件产线里 <b>齿轮既是第 0 步的投入物、
     * 又是同一条配方 {@code results} 池里的废料</b>，而<b>金板是起步原料</b> ——
     * 旧判据会把这两类混起来。现在按「起步原料 vs 步内投入物」分流，与服务端的
     * {@code stepExtraInputs} / {@code startIngredients} 是同一份 Create 配方数据（客户端也读得到）。</p>
     * <p><b>本集合只是全局兜底份</b>（判不出配方上下文时用），而且只收代表物
     * （{@link SequencedRecipeProbe#stepInputItems} 每个 ingredient 取首个物品）；
     * 正常路径见 {@link #stepInputItemsByRecipe}（按配方、含整组候选）。</p>
     */
    private final Set<Item> stepInputItems = new HashSet<>();

    /**
     * <b>「配方 → 起步原料」表</b>（2026-10-06：分组改为<b>按配方上下文</b>判定的唯一判据来源）。
     *
     * <h2>为什么必须按配方分表（用户原话：「原料和输入时原料你把他们两个混在一起、没有区分」）</h2>
     * <p>原来的 {@link #startingItems} / {@link #stepInputItems} 是<b>全局</b>集合：
     * 一件东西只要在<b>任意</b>一条配方里当过起步原料，它在<b>所有</b>配方页里都被判成「原料」。
     * 而 Create 的真实数据里跨配方重叠是常态 —— {@code create:sturdy_sheet} 的起步原料是
     * {@code c:dusts/obsidian}，{@code create:track} 的步内投入物是 {@code c:nuggets/iron} 与
     * {@code c:nuggets/zinc}，而 {@code create:precision_mechanism} 的起步原料是 {@code c:plates/gold}、
     * 第 3 步又投入 {@code c:nuggets/iron}。同一件铁粒在「列车轨道」页是输入时原料、
     * 在别处可能是原料 ⇒ 全局集合判出来的分组与该页看到的配方对不上。</p>
     * <p>现在按「配方 id → 该配方顶层 {@code ingredient} 的物品」分表：界面天然有配方上下文
     * （类别 id 自带配方段，见 {@link RsccBusCategory#inputRecipeId()}；旧格式退回当前标签页），
     * 于是每一页的分组只由<b>这一页那条配方自己的数据</b>决定。</p>
     */
    private final java.util.Map<String, Set<Item>> startingItemsByRecipe = new java.util.LinkedHashMap<>();

    /**
     * <b>「配方 → 各步投入物」表</b>：该配方序列里每个步骤<b>下标 ≥ 1</b> 的 ingredient
     * 的全部候选物品（{@link SequencedRecipeProbe#stepApplicationCandidates}，与
     * {@link #collectAssemblyInputs} 登记配方归属时同一份数据，因此不可能出现两套口径）。
     * <p>取整组候选而不是代表物：列车轨道的机械手步声明的是「铁粒 或 锌粒」这一<b>组</b>
     * （{@code c:nuggets/iron} 与 {@code c:nuggets/zinc} 同属一个 ingredient），
     * 只记代表物会让锌粒那一件判不出分组而掉回「原料」。</p>
     */
    private final java.util.Map<String, Set<Item>> stepInputItemsByRecipe = new java.util.LinkedHashMap<>();

    /**
     * 「输入性产物」类别的<b>整组候选</b>（标签型 ingredient 展开）：
     * 键 = 代表物（类别的 {@code iconItem}），值 = 该 ingredient 的全部候选物品。
     * <p>供 {@link #drawItem} 在「输入性产物」类别上循环显示全部候选
     * （铁粒|锌粒 / 任意台阶）—— 与终端主界面同一套
     * {@link GhostMarkerRenderer#candidateItems} + {@link GhostMarkerRenderer#cycleCandidate}。
     * 中间产物 / 成品 / 废料都是单件，不进这张表，因此不会误轮播。</p>
     * <p>同一代表物出现在多条配方的不同 ingredient 时合并候选（并集），保证任一配方里的
     * 标签型输入都能完整轮播 —— 这与服务端「按整组候选匹配」的口径一致。</p>
     */
    private final java.util.Map<Item, List<ItemStack>> inputCandidatesByItem = new java.util.LinkedHashMap<>();

    /**
     * <b>「物品 → 所属 Create 序列装配配方 id（们）」表</b>（本轮：配方标签页的归属依据）。
     *
     * <h2>为什么用客户端配方数据、不加服务端协议</h2>
     * <p>用户要求按配方分页；「这一件属于哪条配方」这件事在 Create 的配方数据里就有，而本界面<b>已经</b>
     * 在客户端读同一份 {@code RecipeManager}（见 {@link #collectAssemblyInputs}），
     * 因此顺手把「配方归属」记下来 —— 服务端的类别快照一个字节都不用改。</p>
     * <p><b>多对多</b>：同一件东西可能被两条及以上配方用到（例：齿轮既是精密构件的投入物、
     * 又是它自己 {@code results} 池里的废料）。因此这里是<b>集合</b>而不是单个 id ——
     * 该条目会<b>同时出现在它涉及的每一页</b>，行内标注「多配方共用」。
     * 旧实现 {@code putIfAbsent}（先扫到的赢）会让它只出现在一页、且可能归到错误的那一页名下。</p>
     * <p><b>本轮（2026-10-06）起这张表只决定「流体 / 成品 / 废料」的归属</b>：
     * 物品输入类别的页由它自己 id 里的配方段给出（服务端按配方登记），不再查这张表 ——
     * 否则「铁粒这一件还被另一条配方用到」会让列车轨道的锌粒候选混进精密构件页
     * （用户实测：精密构件页里出现了它用不到的锌粒）。</p></p>
     */
    private final java.util.Map<Item, java.util.Set<String>> recipeIdsByItem = new java.util.LinkedHashMap<>();

    /**
     * <b>「流体 → 所属 Create 序列装配配方 id（们）」表</b>：与 {@link #recipeIdsByItem} 同一口径，
     * 只是流体类别没有物品可查（{@link RsccBusCategory#iconItem()} 为空），因此单独记一张表 ——
     * 否则流体类别会整条落进兜底页。
     */
    private final java.util.Map<Fluid, java.util.Set<String>> recipeIdsByFluid = new java.util.LinkedHashMap<>();

    /**
     * <b>配方 id → 可读标签</b>（标签页上显示的文字）。
     * <p>取值 = 该配方结果池首项的<b>物品悬浮名</b>（例如「列车轨道」）；取不到时由
     * {@link #tabDisplayName(String)} 退回注册名的 path（例如 {@code track}）—— 仍然可读，
     * 绝不会出现「一个看不出是什么的 id 页」。</p>
     */
    private final java.util.Map<String, String> recipeLabelById = new java.util.LinkedHashMap<>();

    /**
     * <b>「处理器类型 id → 使用它的序列装配配方 id（们）」表</b>（2026-10-06 用户第 2 条）。
     *
     * <h2>为什么必须有它（用户原话）</h2>
     * <p>「你应该列出来的不是「使用」这一栏，而是所有包含「使用」的那些配方，那序列装配的配方。」
     * 上一版在「执行舱（链）一台单元样板都没有」时只建<b>一个处理器类型页</b>，页名走
     * {@code RecipeTypeNames}：{@code create:deploying} 命中 Create 自己的
     * {@code create.recipe.deploying}（中文恰好是「使用」）⇒ 玩家看到的是一个<b>动词页</b>、
     * 页内零行，完全看不出「哪些配方要用这台机器」。本表把这一层补上：
     * 标记类别据此展开成<b>一配方一页</b>（页名 = 配方结果物名，见 {@link #recipeLabelById}）。</p>
     *
     * <h2>数据从哪来（复用既有那一遍扫描，不新造第二套）</h2>
     * <p>就在 {@link #collectAssemblyInputs} 已有的「遍历配方管理器里全部 {@code sequenced_assembly}
     * 配方」的循环里顺手登记；步骤类型一律用 {@link SequencedRecipeProbe#recipeTypeId} ——
     * 它与服务端判「本仓能不能干这一步」用的是<b>同一个解析器</b>
     * （{@code SequenceExecutionChamberBlockEntity#sequenceMatchesUnit} 里的
     * {@code recipeType.equals(SequencedRecipeProbe.recipeTypeId(step.getRecipe()))}），
     * 因此这里列出的配方与「这台机器真的能执行」逐字同源，不存在第二套口径。</p>
     *
     * <p><b>去重（用户要求「一配方一个页」）</b>：值是 {@link LinkedHashSet} —— 一条配方里有多个
     * 同类步骤（列车轨道有两处机械手装配）、或同一类型被同一台机器的多个步骤用到，都只会留一个
     * 配方 id；顺序 = 配方管理器给的顺序（稳定、可复现，不会每帧跳）。</p>
     */
    private final java.util.Map<String, java.util.LinkedHashSet<String>> recipeIdsByStepType =
        new java.util.LinkedHashMap<>();

    /**
     * <b>「样板库里真的有的配方」集合 —— 标签页的唯一合法来源</b>（用户原话：「这个配方应该从那个总的
     * 那个样板的样板库里面进行查找，而不是把所有序列装配配方中进行查找……写了才显示」）。
     *
     * <h2>取数路径（客户端只读，零服务端协议改动）</h2>
     * <p>本界面拿到的类别快照本身就来自执行舱里<b>玩家真正放入的单元样板</b>
     * （{@code SequenceExecutionChamberBlockEntity#computeBusCategories} 逐样板按 {@code unit.recipe()}
     * 展开）。中间产物类别的 id 是 {@code intermediate:<配方id>:<步序>}，
     * 因此「凡是快照里出现过带配方 id 的中间产物类别」＝「该配方确实有样板」——
     * 这就是「从样板库取配方清单」的落地方式：<b>没有样板的配方在这里根本不会出现</b>。</p>
     *
     * <p>为空（旧档只写了不带配方 id 的 {@code intermediate:<步序>}，或快照为空）时本集合不生效，
     * 退回「按全部配方归属」的旧口径 —— 宁可多显示几页，也绝不让玩家眼前只剩一个兜底页。</p>
     */
    private final Set<String> patternRecipeIds = new LinkedHashSet<>();

    /**
     * <b>标签页键 → 处理器类型 id</b>（只装 {@link #TAB_RECIPE_TYPE_PREFIX} 开头的那一页）。
     *
     * <p>取值 = 快照里那条「只喂标签页」的标记类别（{@link RsccBusCategory#isRecipeTab()}）。
     * 一张空表 = 没有标记类别（= 链上有样板）⇒ 标签页完全由真实类别反推
     * （<b>既有行为逐字不变</b>）。本表只用于三件事：给<b>兜底页</b>起名（{@link #tabDisplayName}）、
     * 判断「列表为空时该说哪句话」（{@link #emptyListText} / {@link #isMarkerFedTab}）、
     * 以及把标记类别展开成「一配方一页」（{@link #recipeTabKeysOf}）；
     * 行 / 计数 / 搜索命中一律不认识它。</p>
     */
    private final java.util.Map<String, String> recipeTypeByTabKey = new java.util.LinkedHashMap<>();

    /**
     * <b>「语义相同的中间步骤可复用」标记</b>（用户第 2 条）：{@link RsccBusCategory#reuseKey()} 非空、
     * 且该键在本仓的类别里被<b>≥ 2 个不同类别</b>（可能来自不同配方）共用的那些类别 id。
     *
     * <p>为什么按「键共用」判：服务端的复用键 = 步骤类型 + 输入候选集合（见
     * {@code SequenceExecutionChamberBlockEntity#stepReuseKey}），同键 ⇒ 这两步语义完全相同 ⇒
     * 玩家可以让同一台机器 / 同一条总线服务它们。这里只做<b>显示标注</b>（行内后缀 + 表头 tooltip），
     * 不改变任何勾选语义 —— 类别本身仍按配方分开（用户第 3 条）。</p>
     */
    private final java.util.Set<String> sharedReuseCategoryIds = new java.util.LinkedHashSet<>();

    /** 「可复用」标记的键是否至少出现过一次（决定表头 tooltip 要不要补那一行说明）。 */
    private boolean anySharedReuse;

    // ==================== 标签页状态（第一级组织维度） ====================

    /**
     * 标签页顺序（元素 = 配方 id；兜底页 {@link #TAB_OTHER} 始终排最后）。
     * <p>顺序 = 类别表里<b>首次出现</b>的顺序（= 执行舱给的类别顺序），因此稳定、可复现，
     * 不会每帧跳；且<b>只有真的拥有类别的配方才建页</b>（不会出现空页）。</p>
     */
    private final List<String> tabOrder = new ArrayList<>();
    /** 当前选中的标签页（= {@link #tabOrder} 的成员；初始为第一页）。 */
    private String activeTab = TAB_OTHER;
    /** 标签页行的水平滚动起点（溢出时才有意义；{@link #ensureActiveTabVisible()} 保证当前页可见）。 */
    private int tabFrom;

    /**
     * 「全部配方」弹出选择器是否展开（覆盖层状态；<b>只影响显示</b>，不碰 {@link #selected}）。
     * <p>展开时不抢搜索框焦点：↑↓ 移动光标、回车切页、Esc 收起；点任意一行也是切页。</p>
     */
    private boolean pickerOpen;
    /** 弹出选择器的滚动起点（行下标）。 */
    private int pickerScroll;
    /** 弹出选择器的「搜索配方」输入框（只过滤显示，不改任何勾选 / 当前页）。 */
    @org.jetbrains.annotations.Nullable
    private EditBox pickerSearchBox;
    /** 弹出选择器的键盘光标（行下标；-1 = 未启用键盘）。 */
    private int pickerCursor = -1;

    /** 当前显示的行（按「当前标签页 + 折叠 + 搜索过滤」重算）。 */
    private List<Line> lines = List.of();
    private int firstVisibleRow;

    private EditBox searchBox;
    private SptScrollbarWidget scrollbar;
    private Button foldButton;

    public BusCategoryConfigScreen(final Screen parent, final List<RsccBusCategory> categories,
                                   final List<String> initialSelection, final boolean autoMode,
                                   final Consumer<List<String>> commitSink) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.categories = categories == null ? List.of() : List.copyOf(categories);
        this.autoMode = autoMode;
        this.commitSink = commitSink;
        if (autoMode) {
            // <b>自动模式：本界面只读，且编辑起点 = 服务端下发的「自动接管集合」</b>
            // （快照里 selected 标记 = 服务端按「输入类不收 / 非输入类自动收」算好的结果）。
            // <p>为什么这里<b>不</b>采用 initialSelection（玩家手动那份）：自动模式下玩家不需要勾选，
            // 界面要显示的是「系统已经帮你选好哪些」；而玩家手动那份<b>既不读也不提交</b>
            // （确定按钮被禁用，见 init()），因此来回切模式绝不会把玩家的手动配置冲掉。</p>
            for (final RsccBusCategory category : this.categories) {
                if (category.selected() && !isTabOnly(category)) {
                    selected.add(category.id());
                }
            }
        } else if (initialSelection != null) {
            // 初始已选：原样保留（含当前快照里看不到的 id —— 那些可能是类别表缓存窗口内的旧 id，
            // 静默丢弃等于替玩家删配置；"全部清空" 是唯一会主动丢掉它们的操作）
            for (final String id : initialSelection) {
                if (id != null && !id.isEmpty()) {
                    selected.add(id);
                }
            }
        }
        for (final RsccBusCategory category : this.categories) {
            // 标记类别不算「一个类别」：不进 categoryOrder ⇒ 底部「已选 N / M 项」的分母与
            // 眼前零行一致（0 / 0），提交时也不会替它排位。
            if (!isTabOnly(category)) {
                categoryOrder.add(category.id());
            }
        }
        // 标签页的配方来源 = 「样板库里真的有的配方」（从类别快照的中间产物 id 反推，见 patternRecipeIds）
        collectPatternRecipeIds();
        // 「处理器类型页」的来源 = 服务端在「整链没有单元样板」时补的那条只喂标签页的标记类别
        collectRecipeTabKeys();
        // 「原料 / 输入时原料」的分流判据：<b>按配方</b>记「起步原料 vs 各步投入物」
        //（同一份 Create 序列装配配方数据；全局同名集合只作判不出配方时的兜底）
        collectAssemblyInputs(Minecraft.getInstance().level);
    }

    /**
     * 从类别快照里收集「真的写了样板的配方 id」：只认<b>中间产物</b>类别 id 自带的配方段
     * （{@code intermediate:<配方id>:<步序>}）—— 这一路是执行舱按玩家放入的单元样板展开的，
     * 最权威（见 {@link #patternRecipeIds} 的说明）。
     *
     * <p>不带配方段的旧写法（{@code intermediate:<步序>}）与兜底页的类别都不贡献配方 ——
     * 判不出归属就不硬塞，交由兜底页承载。</p>
     */
    private void collectPatternRecipeIds() {
        patternRecipeIds.clear();
        for (final RsccBusCategory category : categories) {
            if (!category.isIntermediate() || category.intermediateStep() < 0) {
                continue;
            }
            final String recipe = category.intermediateRecipe();
            if (recipe != null && !recipe.isEmpty()) {
                patternRecipeIds.add(recipe);
            }
        }
    }

    /**
     * 收集「处理器类型页」的键：快照里那条<b>只喂标签页</b>的标记类别
     * （{@link RsccBusCategory#isRecipeTab()}，只可能来自服务端「整链一台单元样板都没有」的补丁）。
     *
     * <p><b>为什么单独一张表而不是直接查类别</b>：标签页的名字要在「还没有任何类别属于这一页」时
     * 也能算出来（这一页<b>天生零行</b>），因此页名必须脱离类别对象存在。
     * 标记一个都没有时本表为空 ⇒ 一切照旧（既有行为逐字不变）。</p>
     */
    private void collectRecipeTabKeys() {
        recipeTypeByTabKey.clear();
        for (final RsccBusCategory category : categories) {
            if (!isTabOnly(category)) {
                continue;
            }
            recipeTypeByTabKey.putIfAbsent(TAB_RECIPE_TYPE_PREFIX + category.recipeTabType(),
                category.recipeTabType());
        }
    }

    /**
     * 是否是<b>只喂标签页</b>的标记类别（服务端在「整链一台单元样板都没有」时补的那一条）。
     *
     * <p><b>唯一的一处判据</b>：所有会产出「行 / 计数 / 搜索命中」的枚举处都先跳过它 ——
     * 于是它不会画行、不会被勾选、不会被算进分节表头与「已选 N / M 项」，
     * 唯一读它的地方是 {@link #tabKeysOf}（喂标签页）、{@link #tabDisplayName}（页名）
     * 与 {@link #emptyListText}（空页说明）。绝不新造第二套「是不是标记」的判断。</p>
     */
    private static boolean isTabOnly(final RsccBusCategory category) {
        return category != null && category.isRecipeTab();
    }

    /**
     * 从<b>已加载的 Create 序列装配配方</b>里取出分组的判据，两份口径：
     * <ul>
     *     <li><b>按配方</b>（{@link #startingItemsByRecipe} / {@link #stepInputItemsByRecipe}）——
     *     界面分组实际用的那一份：配方 id → 起步原料物品 / 该配方各步投入物的整组候选；</li>
     *     <li><b>全局兜底</b>（{@link #startingItems} / {@link #stepInputItems}）—— 只在判不出
     *     配方上下文时用（旧格式 id + 兜底页 / 这次没扫到配方）。</li>
     * </ul>
     *
     * <p><b>为什么客户端直接读配方</b>：这些集合描述的是「Create 配方里这一件是怎么用的」，
     * 配方数据两端都有（{@code RecipeManager} 在客户端同样加载）；服务端的类别快照里只有
     * 「一个输入类别 = 一个 {@code input:<配方>#<物品>}」，没有多余字段可借，因此这里读同一份权威数据、
     * 不新增任何服务端协议。取不到（界面构建时没有世界 / 配方未加载完）时各集合都是空集，
     * 所有输入退化为「原料」组 —— 与旧行为一致，绝不抛异常。</p>
     */
    private void collectAssemblyInputs(@Nullable final Level level) {
        if (level == null) {
            return;
        }
        try {
            for (final RecipeHolder<?> holder : level.getRecipeManager()
                .getAllRecipesFor(com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())) {
                if (!(holder.value()
                    instanceof final com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe recipe)) {
                    continue;
                }
                // 本配方的稳定键（标签页键）与可读标签（结果池首项的悬浮名，例如「列车轨道」）
                final String recipeId = holder.id().toString();
                recipeLabelById.putIfAbsent(recipeId, recipeDisplayLabel(recipe, holder.id()));
                for (final ItemStack stack : recipe.getIngredient().getItems()) {
                    if (!stack.isEmpty()) {
                        startingItems.add(stack.getItem());
                        // 按配方另记一份：这一份才是分组判据（全局那份只留给「配方判不出」的旧格式 id 兜底）
                        startingItemsByRecipe
                            .computeIfAbsent(recipeId, ignored -> new LinkedHashSet<>())
                            .add(stack.getItem());
                        addRecipeId(recipeIdsByItem, stack.getItem(), recipeId);
                    }
                }
                // 主原料候选（标签型 ingredient 整组展开）：供 drawItem 在「输入性产物」类别上循环显示
                registerInputCandidates(GhostMarkerRenderer.candidateItems(List.of(recipe.getIngredient())));
                for (final com.simibubi.create.content.processing.sequenced.SequencedRecipe<?> step
                    : recipe.getSequence()) {
                    // 「这个处理器类型被哪些配方用到」：整条链没有单元样板时，标记类别据此展开成
                    // 「一配方一页」（用户第 2 条）。解析器与服务端判「本仓能不能干这一步」是同一个，
                    // 因此这里列出的配方与「这台机器真的能执行」逐字同源。
                    final String stepType = SequencedRecipeProbe.recipeTypeId(step.getRecipe());
                    if (!stepType.isEmpty()) {
                        recipeIdsByStepType
                            .computeIfAbsent(stepType, ignored -> new LinkedHashSet<>())
                            .add(recipeId); // 集合去重 ⇒ 同一条配方的多个同类步骤只留一个页
                    }
                    for (final ItemStack stack : SequencedRecipeProbe.stepInputItems(step.getRecipe())) {
                        stepInputItems.add(stack.getItem());
                    }
                    // 步内投入物候选（跳过下标 0 = 过渡件，与 stepInputItems 同口径）：每个 ingredient 整组展开
                    final net.minecraft.core.NonNullList<net.minecraft.world.item.crafting.Ingredient> stepIngredients =
                        step.getRecipe().getIngredients();
                    for (int i = 1; i < stepIngredients.size(); i++) {
                        registerInputCandidates(
                            GhostMarkerRenderer.candidateItems(List.of(stepIngredients.get(i))));
                    }
                    // 归属按<b>整组候选</b>登记（用户第 1 条）：标签型输入（铁粒|锌粒）的每一个候选
                    // 都属于这条配方 —— 否则锌粒会落进兜底页，玩家在列车轨道页里看不到它。
                    for (final Item item : SequencedRecipeProbe.stepApplicationCandidates(step.getRecipe())) {
                        addRecipeId(recipeIdsByItem, item, recipeId);
                        // 同一份「下标 ≥1 的全部候选」也按配方记进分组判据表：
                        // 「输入时原料」= 本配方各步的投入物，按配方判才不会与别条配方的起步原料互相错怪
                        stepInputItemsByRecipe
                            .computeIfAbsent(recipeId, ignored -> new LinkedHashSet<>())
                            .add(item);
                    }
                    // 流体输入同样按配方归属（流体类别没有物品可查，故单独记一张表）
                    for (final FluidStack stack : SequencedRecipeProbe.stepInputFluids(step.getRecipe())) {
                        addRecipeId(recipeIdsByFluid, stack.getFluid(), recipeId);
                    }
                }
                // 过渡件 / 成品 / 废料也归到这条配方（同一执行舱挂着多条配方时，三者必须各自成页）。
                final ItemStack transitional = recipe.getTransitionalItem();
                if (!transitional.isEmpty()) {
                    addRecipeId(recipeIdsByItem, transitional.getItem(), recipeId);
                }
                // resultPool 在 Create 里是 public 字段（没有 getter）：直接读它，取全部产出（成品 + 废料）。
                for (final com.simibubi.create.content.processing.recipe.ProcessingOutput out
                    : recipe.resultPool) {
                    final ItemStack produced = out.getStack();
                    if (!produced.isEmpty()) {
                        addRecipeId(recipeIdsByItem, produced.getItem(), recipeId);
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方系统尚未加载完 / 版本差异：退化为「全部输入都算原料 + 全部条目落兜底页」（绝不抛）
        }
    }

    /**
     * 追加一条「物品 / 流体 → 所属配方 id」记录（<b>多对多</b>：同一件东西可能被多条配方使用）。
     *
     * <p><b>为什么要集合</b>：铁粒既是列车轨道装铁粒步的候选、又是精密构件装铁粒步的输入 ——
     * 记成一个集合后，这一件会<b>同时出现在两页里</b>（用户第 3 条要求的正是这种处理），
     * 而不是被「先扫到的那条配方」独占（旧口径：另一页再也看不到它，玩家会以为漏了）。</p>
     */
    private static <K> void addRecipeId(final java.util.Map<K, java.util.Set<String>> idsByKey,
                                        final K key, final String recipeId) {
        if (key == null || recipeId == null || recipeId.isEmpty()) {
            return;
        }
        idsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(recipeId);
    }

    /**
     * 把一组候选登记到 {@link #inputCandidatesByItem}：键 = 首个候选（代表物，与服务端类别 iconItem 同口径），
     * 值 = 该组的全部候选。同一代表物出现在不同 ingredient / 配方时<b>合并候选</b>（并集，去重），
     * 保证任一配方里的标签型输入都能完整轮播。
     * <p>与 {@link GhostMarkerRenderer#candidateItems} 的去重口径一致：按物品种类去重。</p>
     */
    private void registerInputCandidates(final List<ItemStack> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        final Item representative = candidates.get(0).getItem();
        final List<ItemStack> existing = inputCandidatesByItem.get(representative);
        if (existing == null) {
            inputCandidatesByItem.put(representative, new ArrayList<>(candidates));
        } else {
            for (final ItemStack candidate : candidates) {
                boolean duplicate = false;
                for (final ItemStack stack : existing) {
                    if (stack.is(candidate.getItem())) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    existing.add(candidate);
                }
            }
        }
    }

    /**
     * 一条序列装配配方在界面上的<b>可读标签</b>：结果池首项的物品悬浮名（如「精密构件」），
     * 取不到（结果池为空 / 名字为空串）时退回配方注册名的 path（如 {@code precision_mechanism}）。
     *
     * <p><b>为什么不用配方 id 当标签</b>：用户要求分组标签「可读」。{@code precision_mechanism}
     * 这种注册名对玩家没有意义，而「这套配方做出来的东西叫什么」是玩家一眼就能认出的身份 ——
     * 这正是「两个样板挂同一个执行舱」时区分两组的唯一依据。</p>
     */
    private static String recipeDisplayLabel(
        final com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe recipe,
        final ResourceLocation id) {
        for (final com.simibubi.create.content.processing.recipe.ProcessingOutput out : recipe.resultPool) {
            final ItemStack stack = out.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            final String name = stack.getHoverName().getString();
            if (!name.isBlank()) {
                return name;
            }
        }
        return id.getPath();
    }

    /**
     * 某个「输入性产物」类别对应的物品（取不到注册名 / 不是物品输入时返回 {@code null}）。
     *
     * <p><b>这里曾经是「原料 / 输入时原料 混在一起」的根因</b>：类别 id 早已改成
     * {@code input:<配方id>#<物品>}，而 {@link RsccBusCategory#inputItemId()} 当时返回前缀之后的
     * 一整段（{@code create:track#minecraft:iron_nugget}）—— 它解析不成 {@code ResourceLocation}，
     * 于是本方法恒返回 {@code null}，分流判据永远走「保守落原料」那一支，
     * 「输入时原料」组一个条目都没有（玩家看到的就是两组混成一栏）。
     * 现在配方段由 {@link RsccBusCategory#inputRecipeId()} 单独提供，本方法只拿物品段。</p>
     */
    @Nullable
    private static Item inputItemOf(final RsccBusCategory category) {
        final String itemId = category.inputItemId();
        if (itemId.isEmpty()) {
            return null;
        }
        final ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) {
            return null;
        }
        final Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    }

    /**
     * 本界面此刻是否<b>只读</b>（= 总线处于自动模式）。
     *
     * <p><b>为什么自动模式必须只读（用户原话）</b>：「它调成自动模式后，这一个类别详细配置这里，
     * 它应该是这种禁用状态啊，并且自动帮我选上……不然的话我，这个详细配置我看个毛线啊。」
     * 自动模式下勾选由服务端按「输入类不收 / 非输入类自动收」算好并随快照下发，
     * 玩家在这里改勾选既无意义、又会在提交时把<b>手动</b>那份勾选表冲掉。
     * 因此：勾选 / 全选 / 清空 / 确定一律禁用（灰显），只剩「看」（搜索 / 翻页 / 折叠 / 滚轮 / 换配方页）。</p>
     */
    private boolean locked() {
        return autoMode;
    }

    @Override
    protected void init() {
        super.init();

        // 搜索框：按显示名 / 原始 id / 分组（配方工序）名过滤
        searchBox = new EditBox(font, px + SEARCH_X, py + SEARCH_Y, SEARCH_W, SEARCH_H,
            Component.translatable(LANG + "search.hint"));
        searchBox.setMaxLength(48);
        searchBox.setTextShadow(false);
        searchBox.setHint(Component.translatable(LANG + "search.hint"));
        searchBox.setResponder(text -> {
            rebuildLines();
            firstVisibleRow = 0;
        });
        addRenderableWidget(searchBox);
        setFocused(searchBox);

        // 弹出选择器里的「搜索配方」输入框：与主搜索框<b>各自独立</b>（用户要求「点击展开之后的搜索框」）。
        // 平时不可见、也不接收事件；只有 {@link #pickerOpen} 时才显示并聚焦。
        pickerSearchBox = new EditBox(font, 0, 0, PICK_POP_W - 12, PICK_SEARCH_H,
            Component.translatable(LANG + "search.hint"));
        pickerSearchBox.setMaxLength(48);
        pickerSearchBox.setTextShadow(false);
        pickerSearchBox.setHint(Component.translatable(LANG + "search.pick_hint"));
        pickerSearchBox.setResponder(value -> {
            pickerScroll = 0;
            pickerCursor = 0;
            clampPicker();
        });
        pickerSearchBox.visible = false;
        addRenderableWidget(pickerSearchBox);

        // 全部清空（保留：用户明确说「全部清空之类的还是有必要的」）；自动模式下禁用（改了也没意义、还会覆盖手动表）
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "btn.clear"),
            button -> setMembers(categoryOrder, false))
            .bounds(px + CLEAR_X, py + BAND_Y, CLEAR_W, BAND_H).build()).active = !locked();
        // 全部折叠 / 全部展开（「一页一页」的目录视图一键可达；只改显示，不碰勾选）—— 自动模式下仍可用
        foldButton = new Button.Builder(Component.translatable(LANG + "btn.fold"),
            button -> toggleAllCollapsed())
            .bounds(px + FOLD_X, py + BAND_Y, FOLD_W, BAND_H).build();
        addRenderableWidget(foldButton);

        // 滚动条（列表右侧；只有行数超过可见行时才可交互）
        scrollbar = new SptScrollbarWidget(px + SCROLL_X, py + LIST_Y,
            SptScrollbarWidget.Type.NORMAL, LIST_H, SCROLL_W);
        scrollbar.setListener(offset -> firstVisibleRow = (int) Math.round(offset));

        // 确定：自动模式下<b>禁用</b>（不得把「自动接管集合」当作玩家的手动勾选提交上去）
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "confirm"),
            button -> confirmAndReturn())
            .bounds(px + CLEAR_X, py + ACTION_Y, ACTION_W, ACTION_H).build()).active = !locked();
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "cancel"),
            button -> returnToParent())
            .bounds(px + CANCEL_X, py + ACTION_Y, ACTION_W, ACTION_H).build());

        rebuildLines();
    }

    // ==================== 第二级：分节归属（唯一口径，绘制与提交共用） ====================

    /**
     * 一个类别属于哪一<b>节</b>（见类注释的六节；与标签页无关）。判定顺序即优先级：
     * 流体 → 中间产物 → 成品（{@code result:}）→ 废料（{@code scrap:}）→ 物品输入的分流。
     *
     * <p><b>成品与废料必须分开判</b>（用户第 3 条）：{@link RsccBusCategory#isProduct()} 是
     * 「产出侧」的并集（{@code result:} ∪ {@code scrap:}），拿它归组会把废料混进成品组。</p>
     *
     * <p><b>输入类的分流按配方上下文判</b>（2026-10-06 修「原料 / 输入时原料 混在一起」）：
     * 「原料」= <b>该配方的起步原料</b>（顶层 {@code ingredient} 的物品，含标签的每个候选）；
     * 「输入时原料」= <b>该配方</b>各步的投入物（下标 ≥1）与样板声明的额外投入物。判据表见
     * {@link #startingItemsByRecipe} / {@link #stepInputItemsByRecipe}，配方上下文由
     * {@link #recipeContextOf} 给出（类别 id 自带配方段优先，旧格式 id 退回当前标签页）。</p>
     *
     * <p>配方上下文判不出时（旧格式 id + 兜底页 / 配方数据这次没扫到）退回<b>全局</b>判据
     * （下面的那一行），与改动前的行为逐字一致：宁可保守，也绝不把某个类别凭空变成另一组。</p>
     *
     * <p>返回值必是 {@link #GROUP_ORDER} 的成员 ⇒ 六个分节的<b>颜色 / 标题 / 顺序</b>都只有一套口径，
     * 且一个类别<b>恒定落在一节</b>（计数 / 命中 / 滚动因此天然自洽）。</p>
     */
    private String sectionOf(final RsccBusCategory category) {
        if (category.isFluidInput()) {
            return GROUP_FLUIDS;
        }
        if (category.isIntermediate()) {
            return GROUP_INTERMEDIATES;
        }
        if (category.isResult()) {
            return GROUP_PRODUCTS;
        }
        if (category.isScrap()) {
            return GROUP_SCRAP;
        }
        return inputSectionOf(category, inputItemOf(category));
    }

    /**
     * 物品输入类别的分流（「原料」还是「输入时原料」），<b>唯一实现</b>。
     *
     * <h2>判据（按配方上下文，不按全局物品集合）</h2>
     * <ol>
     *     <li>先取 {@link #recipeContextOf} 给出的配方；该配方这次被扫到（{@link #startingItemsByRecipe}
     *     里有它、且起步原料非空）时：<b>物品 ∈ 该配方的起步原料 ⇒ 原料</b>；
     *     否则 ⇒ <b>输入时原料</b>（它只能是这条配方在加工途中要投进去的那一份：
     *     各步投入物，或样板里玩家手填的额外投入物）；</li>
     *     <li>配方判不出 ⇒ 退回全局集合的旧口径（见上）。</li>
     * </ol>
     *
     * <h2>同一件东西同时是「本配方的起步原料」与「某一步的投入物」时怎么办（口径说明）</h2>
     * <p>服务端把「配方 + 物品」合成<b>一个</b>类别 id（{@code input:<配方>#<物品>}，id 里没有「角色」段），
     * 也就是说这两种角色在界面上本来就是<b>同一个勾选框</b>，拆成两行也不可能各自勾选。
     * 因此本实现取<b>「起步原料优先」</b>：它落在「原料」组。理由有两条 ——
     * ① 「原料」= 起步原料是一个<b>完备</b>集合（配方顶层 {@code ingredient} 的全部候选），
     * 起步原料少一项就意味着玩家漏配了开新件的那份料，代价最大；
     * ② 这个物品在「输入时原料」组里本来就没有独立的一行可丢（它已被「原料」组那一行代表），
     * 不会出现「组标题写了 N 项却看不见那一项」。
     * 实测三条 Create 配方（{@code track} / {@code precision_mechanism} / {@code sturdy_sheet}）
     * 都没有「同一条配方里起步原料又被某一步投入」的写法，因此这条优先级目前不会隐藏任何条目。</p>
     *
     * @param item 该类别对应的物品；{@code null}（注册名解析不出）时按「原料」处理（保守口径）
     */
    private String inputSectionOf(final RsccBusCategory category, @Nullable final Item item) {
        if (item == null) {
            return GROUP_MATERIALS;
        }
        final String recipe = recipeContextOf(category);
        if (!recipe.isEmpty()) {
            final Set<Item> starts = startingItemsByRecipe.get(recipe);
            if (starts != null && !starts.isEmpty()) {
                return starts.contains(item) ? GROUP_MATERIALS : GROUP_FEEDSTOCK;
            }
        }
        return !startingItems.contains(item) && stepInputItems.contains(item)
            ? GROUP_FEEDSTOCK : GROUP_MATERIALS;
    }

    /**
     * 一个输入类别的<b>配方上下文</b>（= 分组时该问哪一条配方）。
     *
     * <p><b>为什么优先用类别 id 自带的配方段</b>：那是服务端登记这个类别时用的配方
     * （{@link RsccBusCategory#inputRecipeId()}），这个类别的图标 / 候选 / 预估需求全都属于它 ——
     * 即使这条类别因为「物品也被别的配方用到」而出现在别的配方页上，按它自己的配方判分组
     * 仍然稳定且与它携带的数据一致（同一个类别的分组不会随翻页而变）。</p>
     * <p>旧格式 id（{@code input:<物品>}，服务端样本没记配方 / 老存档）没有配方段，
     * 退回<b>当前标签页</b>；兜底页（{@link #TAB_OTHER}）没有配方可言 ⇒ 返回空串（调用方转全局口径）。</p>
     */
    private String recipeContextOf(final RsccBusCategory category) {
        final String declared = category.inputRecipeId();
        if (declared != null && !declared.isEmpty()) {
            return declared;
        }
        return TAB_OTHER.equals(activeTab) ? "" : activeTab;
    }

    // ==================== 第一级：配方标签页（本轮新增的组织维度） ====================

    /**
     * <b>一个类别属于哪些标签页</b>（配方 id 集合；判不出归属 ⇒ 兜底页 {@link #TAB_OTHER}）。
     *
     * <p><b>取值优先级</b>：</p>
     * <ol>
     *     <li><b>中间产物</b>：直接用类别 id 自带的配方（{@code intermediate:<配方id>:<步序>}）——
     *     服务端已经按配方把中间产物拆成独立类别，这是最权威的一路；</li>
     *     <li><b>物品输入</b>（{@code input:<配方id>#<物品>}）：同样<b>直接用 id 自带的配方段</b>
     *     —— 见下面的「⓪」；只有旧格式 id（没有配方段）才退回按身份物品查；</li>
     *     <li>其余类别（流体输入 / 成品 / 废料）按「身份物品 / 流体」查客户端扫出来的
     *     {@link #recipeIdsByItem} / {@link #recipeIdsByFluid}（多对多）；</li>
     *     <li>一条都查不到 ⇒ 兜底页（<b>绝不丢项、绝不硬塞进某个配方页</b>）。</li>
     * </ol>
     * <p>返回 <b>List</b>（有序、去重）：多个标签时，该条目会在这些页里<b>各出现一次</b>
     * （同一个类别 id ⇒ 勾选状态天然一致）。<b>本轮收窄</b>：这条「共享件两边都在」只适用于
     * <b>流体 / 成品 / 废料</b>（它们本来就按物品身份标识、没有配方上下文）；
     * <b>物品输入类别不再共享</b> —— 每个配方各有自己的那一份类别（见「⓪」）。</p>
     */
    private List<String> tabKeysOf(final RsccBusCategory category) {
        final LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (isTabOnly(category)) {
            // 标记类别只喂标签页：它自己带着「这台机器能干哪一类加工」⇒ 展开成
            // 「所有使用该处理器类型的序列装配配方，一配方一页」（见 recipeTabKeysOf）。
            // 刻意<b>不走</b>下面的「按身份物品查配方」与兜底页：它表达的是「已知能干这类加工」，
            // 而不是「判不出这条条目属于哪条配方」——掉进兜底页会让这一页变成一个空壳配方页。
            keys.addAll(recipeTabKeysOf(category.recipeTabType()));
            return new ArrayList<>(keys);
        }
        if (category.isIntermediate()) {
            final String recipe = category.intermediateRecipe();
            if (recipe != null && !recipe.isEmpty()) {
                keys.add(recipe);
            }
        }
        // ⓪ <b>物品输入类别：按 id 自带的配方段定页，不按代表物查全局表</b>
        //    （2026-10-06 用户实测：<b>「精密构件」页里冒出该配方根本用不到的锌粒</b>）。
        //
        // <p><b>为什么会冒出来</b>：类别 id 是 {@code input:<配方id>#<物品>}（服务端按配方登记，
        // 见 {@code SequenceExecutionChamberBlockEntity#inputCategoryIdForRecipe}），而这里原先走的是
        // 下面那一支「按<b>身份物品</b>（= {@link RsccBusCategory#iconItem()} = 组代表物）查
        // {@link #recipeIdsByItem}」—— 铁粒这一件被列车轨道与精密构件<b>同时</b>用到，
        // 于是 {@code input:create:track#minecraft:iron_nugget}（候选 = 铁粒 + 锌粒）
        // 也拿到了 {@code create:precision_mechanism} 这个页键，混进了精密构件页；
        // 那一行按候选轮播，玩家看到的就是「精密构件页里有锌粒」。</p>
        //
        // <p><b>为什么这样修才对</b>：类别的配方段是<b>服务端登记这个类别时用的那条配方</b>，
        // 它就是这个类别自己的上下文（行内分组 {@link #recipeContextOf} 用的也是同一段），
        // 而「代表物还被哪些配方用到」是<b>物品</b>层面的另一件事，不该决定一个按配方登记的类别
        // 出现在哪一页。修完两条配方各显示各的：精密构件页只有铁粒（单候选，不轮播），
        // 列车轨道页才有「铁粒 + 锌粒」。<b>这里只用 id 自带的配方段，不用物品名 / 语言键启发式。</b></p>
        //
        // <p><b>为什么这里早返回（不再过样板库闸门）</b>：配方段来自本链成员样板所记录的配方，
        // 本身就是「真的写了样板」的证明；若再按 {@link #patternRecipeIds} 过滤一次，
        // 某条配方一旦缺中间产物类别（没有过渡件等），它自己的输入类别就会被打进兜底页
        // —— 那正是本轮要避免的「链上成员定义的类别在自己的页里看不见」。</p>
        if (keys.isEmpty() && category.isInput() && !category.isFluidInput()) {
            final String declared = category.inputRecipeId();
            if (declared != null && !declared.isEmpty()) {
                keys.add(declared);
                return new ArrayList<>(keys);
            }
        }
        if (keys.isEmpty()) {
            // 走到这里只剩三种类别：<b>流体输入</b>、<b>成品 / 废料</b>，以及<b>旧格式的物品输入 id</b>
            // （{@code input:<物品>}，服务端当时没记配方段 / 老存档）—— 它们的身份就是「物品 / 流体本身」，
            // 因此按身份查表（多对多）是唯一可用的口径；物品输入类别带配方段的已在 ⓪ 早返回。
            if (category.isFluidInput()) {
                final ResourceLocation id = ResourceLocation.tryParse(category.fluidInputId());
                final Fluid fluid = id == null ? null : BuiltInRegistries.FLUID.get(id);
                if (fluid != null) {
                    keys.addAll(recipeIdsByFluid.getOrDefault(fluid, Set.of()));
                }
            } else {
                final Item item = iconItemOf(category);
                if (item != null) {
                    keys.addAll(recipeIdsByItem.getOrDefault(item, Set.of()));
                }
            }
        }
        if (keys.isEmpty()) {
            keys.add(TAB_OTHER);
        }
        // 样板库口径（用户第 1 条）：只保留「真的写了样板」的配方 —— 某件产出物即使也被别的配方
        // 写过（客户端扫到的 recipeIdsByItem 会带上那些配方），只要那个配方没有样板，就不得为它建页。
        // 判不出归属的条目随后自然落兜底页（不丢项）。
        // <b>本轮起本闸门只作用于上面那一支（流体 / 成品 / 废料 / 旧格式 id）</b>：
        // 带配方段的物品输入类别已在 ⓪ 早返回（它自己就是「有样板」的证据），
        // 中间产物类别也早在上面定了键 —— 因此这两类绝不会因为闸门而掉进兜底页。
        if (!patternRecipeIds.isEmpty()) {
            keys.removeIf(key -> !patternRecipeIds.contains(key));
        }
        if (keys.isEmpty()) {
            keys.add(TAB_OTHER);
        }
        return new ArrayList<>(keys);
    }

    /**
     * <b>「只喂标签页」的标记类别 → 它真正要显示的标签页键</b>（2026-10-06 用户第 2 条）。
     *
     * <h2>用户要的是什么（原话）</h2>
     * <p>「你应该列出来的不是「使用」这一栏，而是所有包含「使用」的那些配方，那序列装配的配方。」
     * 标记类别携带的是<b>处理器类型</b>（{@code create:deploying}，本仓绑定的那一层），
     * 而玩家要看的是<b>配方</b>：这一步把所有「含该处理器类型步骤的序列装配配方」列出来，
     * <b>一个配方一个标签页</b>。</p>
     *
     * <h2>三种取值（判据只有这一处，界面别处不再解释标记）</h2>
     * <ol>
     *     <li><b>段就是配方 id</b>（标记 id 的另一种写法 {@code recipe:<配方id>}）：它就是那一页，直接返回；
     *     两种写法都认 ⇒ 服务端日后换成配方 id 时界面不用改；</li>
     *     <li><b>段是处理器类型</b>（当前服务端发的那一种）：查 {@link #recipeIdsByStepType}
     *     （= 配方管理器里全部序列装配配方按步骤类型归的档），
     *     {@link LinkedHashSet} 已按配方 id 去重 ⇒ 一配方一页，同一配方多台机器 / 多个成员不会多出页；</li>
     *     <li><b>一条都查不到</b>（客户端配方管理器还没加载完、或该类型确实没有配方）：退回上一版的
     *     「处理器类型页」（{@link #TAB_RECIPE_TYPE_PREFIX}，页内零行）——
     *     宁可保留一个说不出配方名的页，也不让标签页行整排消失（那会让玩家以为界面坏了）。</li>
     * </ol>
     *
     * <p><b>页名</b>由 {@link #tabDisplayName} 的既有解析给出：配方页 = 该配方结果池首项的悬浮名
     * （{@link #recipeLabelById}，与「全部配方」弹出清单 / 样板 tooltip 同一套），
     * 兜底页 = {@code RecipeTypeNames} 的「配方类型」名。因此<b>不再新造第二套配方名解析</b>。</p>
     *
     * @param segment 标记类别 id 里 {@code recipe:} 之后的那一段（处理器类型 id 或配方 id）
     */
    private List<String> recipeTabKeysOf(final String segment) {
        if (segment == null || segment.isEmpty()) {
            return List.of();
        }
        // ① 段就是配方 id（标记 id = recipe:<配方id>）：有页名记录 ⇒ 认得出这是一条真配方。
        if (recipeLabelById.containsKey(segment)) {
            return List.of(segment);
        }
        // ② 段是处理器类型：列出所有含该类型步骤的序列装配配方（按配方 id 去重，一配方一页）。
        final java.util.Set<String> recipeIds = recipeIdsByStepType.get(segment);
        if (recipeIds != null && !recipeIds.isEmpty()) {
            return new ArrayList<>(recipeIds);
        }
        // ③ 一条配方都查不到：退回上一版的处理器类型页（页内零行），绝不出现「一个标签页都没有」。
        return List.of(TAB_RECIPE_TYPE_PREFIX + segment);
    }

    /** 该类别是否出现在<b>当前页</b>（切页的唯一过滤点；不改任何勾选语义）。 */
    private boolean inActiveTab(final RsccBusCategory category) {
        return tabKeysOf(category).contains(activeTab);
    }

    /**
     * 该类别是否是「多配方共用」（决定行内要不要标「多配方共用」）。
     * <p>判据仍是「它属于两页及以上」（{@link #tabKeysOf}）。<b>物品输入类别恒为假</b> ——
     * 它们按 id 自带的配方段定页，每条配方一个类别（见 {@link #tabKeysOf} 的「⓪」），
     * 因此不会再出现「精密构件页里标着『多配方共用』的锌粒」。</p>
     */
    private boolean isSharedAcrossRecipes(final RsccBusCategory category) {
        return tabKeysOf(category).size() > 1;
    }

    /**
     * 标签页的显示名：配方结果物的悬浮名（例如「列车轨道」）；查不到时退回注册名 path
     * （例如 {@code track}）；兜底页用语言键。
     * <p>「必须可读」是用户的硬要求，因此<b>绝不</b>直接显示 {@code create:track} 这种带命名空间的全名。</p>
     */
    private String tabDisplayName(final String tabKey) {
        if (TAB_OTHER.equals(tabKey)) {
            return Component.translatable(LANG + "tab.other").getString();
        }
        final String recipeType = recipeTypeByTabKey.get(tabKey);
        if (recipeType != null) {
            // 兜底的「处理器类型页」（只在客户端一条使用该类型的配方都查不到时才会走到这里）：
            // 页名 = 执行舱那套「配方类型」显示名（{@code create:pressing} → 冲压）。
            // 与执行舱界面（{@code SequenceExecutionChamberScreen#recipeTypeLine}）用同一份解析，
            // 因此同一台机器在两个界面上叫同一个名字；查不到译名时该解析自己会「可读化」兜底
            // （绝不回退成 {@code create:xxx} 这种技术串）。
            return RecipeTypeNames.of(recipeType);
        }
        final String label = recipeLabelById.get(tabKey);
        if (label != null && !label.isEmpty()) {
            return label;
        }
        final ResourceLocation id = ResourceLocation.tryParse(tabKey);
        return id == null ? tabKey : id.getPath();
    }

    /**
     * 重建标签页顺序（{@link #tabOrder}）并修正 {@link #activeTab} / 滚动位置。
     *
     * <p><b>只建有内容的页</b>：扫描类别表，按「首次出现」的顺序收集配方 id，
     * 兜底页固定排最后；一条类别都没有的配方<b>不建页</b>（避免一排点不动的空页）。
     * 每次重建列表时调用（{@link #rebuildLines()}），因此服务端类别表变化后自动跟随。</p>
     * <p><b>切页不改勾选</b>：本方法只动 {@link #activeTab} 与 {@link #tabFrom}。</p>
     */
    private void rebuildTabs() {
        tabOrder.clear();
        boolean needsFallback = false;
        for (final RsccBusCategory category : categories) {
            for (final String key : tabKeysOf(category)) {
                if (TAB_OTHER.equals(key)) {
                    needsFallback = true;
                } else if (!tabOrder.contains(key)) {
                    tabOrder.add(key);
                }
            }
        }
        if (needsFallback) {
            tabOrder.add(TAB_OTHER);
        }
        if (tabOrder.isEmpty()) {
            activeTab = TAB_OTHER;
            tabFrom = 0;
            refreshReuseMarkers();
            return;
        }
        if (!tabOrder.contains(activeTab)) {
            activeTab = tabOrder.get(0); // 页被服务端类别变化吞掉了：回到第一页（绝不留下「空白页」）
        }
        refreshReuseMarkers();
    }

    /**
     * 重算 {@link #sharedReuseCategoryIds}：{@link RsccBusCategory#reuseKey()} 非空、且被
     * <b>≥ 2 个不同类别</b>共用的那些类别 ⇒ 打「可复用」标记（用户第 2 条）。
     *
     * <p>只统计<b>中间产物</b>类别（复用是「步骤」层面的概念：同一台机器可以承担不同配方里语义相同的步骤）；
     * 键为空（步骤类型取不到 / 步序未知）一律不标记 —— 判不出来就不表态，绝不显示可能误导玩家的提示。
     * 一次线性扫描，与 {@link #rebuildTabs()} 同在列表重建时执行。</p>
     */
    private void refreshReuseMarkers() {
        sharedReuseCategoryIds.clear();
        final java.util.Map<String, Integer> keyCounts = new java.util.HashMap<>();
        for (final RsccBusCategory category : categories) {
            if (!category.isIntermediate()) {
                continue;
            }
            final String key = category.reuseKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            keyCounts.merge(key, 1, Integer::sum);
        }
        anySharedReuse = false;
        for (final RsccBusCategory category : categories) {
            if (!category.isIntermediate()) {
                continue;
            }
            final String key = category.reuseKey();
            if (key != null && !key.isEmpty() && keyCounts.getOrDefault(key, 0) > 1) {
                sharedReuseCategoryIds.add(category.id());
                anySharedReuse = true; // 判定依据与上面同一份 keyCounts，不会出现两套口径
            }
        }
    }

    /** 切到某一页（<b>只改显示</b>：不碰 {@link #selected}，也不影响提交结果）。 */
    private void switchTab(final String tabKey) {
        if (tabKey == null || tabKey.equals(activeTab) || !tabOrder.contains(tabKey)) {
            return;
        }
        activeTab = tabKey;
        firstVisibleRow = 0; // 新的一页：列表从头看
        rebuildLines();
        ensureActiveTabVisible();
    }

    /** 某一页里的全部类别 id（顺序沿用执行舱给的类别顺序）。 */
    private List<String> tabMembers(final String tabKey) {
        final List<String> members = new ArrayList<>();
        for (final RsccBusCategory category : categories) {
            // 标记类别不算「这一页的内容」：它只负责让这一页存在，页内一条都不显示
            // （切页行 / 搜索跳转行都拿这份成员，多一个它就等于凭空多一行）。
            if (isTabOnly(category)) {
                continue;
            }
            if (tabKeysOf(category).contains(tabKey)) {
                members.add(category.id());
            }
        }
        return members;
    }

    /** 当前页里某一节的全部类别 id（<b>切页后的第二级过滤</b>；空节不画）。 */
    private List<String> sectionMembers(final String section) {
        final List<String> members = new ArrayList<>();
        for (final RsccBusCategory category : categories) {
            // 标记类别不进任何一节：分节表头 / 三态勾选框 / 滚动都以这份成员为准，
            // 跳过它 ⇒ 零行、零表头、分母 0，点击与命中自然没有落点。
            if (isTabOnly(category)) {
                continue;
            }
            if (inActiveTab(category) && section.equals(sectionOf(category))) {
                members.add(category.id());
            }
        }
        return members;
    }

    /** 节键 → 配色索引（与 {@link #GROUP_ORDER} 一一对应）。 */
    private static int sectionColorOf(final String section) {
        final int index = GROUP_ORDER.indexOf(section);
        return GROUP_COLORS[index < 0 ? 0 : index];
    }

    /** 节键 → 语言键（节名 = 玩家看到的「原料 / 中间产物 …」）。 */
    private static String sectionTitleKey(final String section) {
        return LANG + "group." + section;
    }

    /** 节点显示名（语言键解析后的纯文本；表头 / 搜索 / tooltip 共用一处）。 */
    private static String sectionDisplayName(final String section) {
        return Component.translatable(sectionTitleKey(section)).getString();
    }

    /**
     * 某个类别的<b>身份物品</b>（= 它图标用的那件物品）：输入原料 / 中间产物 / 成品 / 废料都是它，
     * 流体类别没有（返回 {@code null}）。解析不到注册名 / 是 {@code air} 时同样返回 {@code null}。
     */
    @Nullable
    private static Item iconItemOf(final RsccBusCategory category) {
        final String itemId = category.iconItem();
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        final ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) {
            return null;
        }
        final Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    }

    // ==================== 编辑态（唯一真相 = selected） ====================

    /** 批量置位（表头全选 / 全部清空共用；这是本界面唯一会批量改勾选的地方）。 */
    private void setMembers(final List<String> ids, final boolean on) {
        for (final String id : ids) {
            if (on) {
                selected.add(id);
            } else {
                selected.remove(id);
            }
        }
        rebuildLines();
    }

    /** 翻转单个类别（条目行勾选）。 */
    private void toggleMember(final String id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        rebuildLines();
    }

    /**
     * 折叠 / 展开<b>一个分节</b>（<b>只改显示</b>：不碰 {@link #selected}，也不影响提交结果）。
     * <p>键是六节之一、<b>不区分配方页</b>：折叠表达「这一节我先不看」，与「正在看哪套配方」正交。</p>
     */
    private void toggleCollapsed(final String section) {
        if (!collapsed.remove(section)) {
            collapsed.add(section);
        }
        rebuildLines();
    }

    /**
     * 是否<b>当前页里</b>所有「有内容的节」都已折叠（决定那个按钮显示「全部展开」还是「全部折叠」）。
     * <p>只统计当前页的六节：玩家看到的就是这六节，按钮的语义必须与眼前那一页一致
     * （别的页折没折不影响本页的显示）。</p>
     */
    private boolean allCollapsed() {
        for (final String section : GROUP_ORDER) {
            if (!sectionMembers(section).isEmpty() && !collapsed.contains(section)) {
                return false;
            }
        }
        return true;
    }

    /** 一键折叠 / 展开当前页的六节（只改显示；不改勾选、不改提交结果）。 */
    private void toggleAllCollapsed() {
        if (allCollapsed()) {
            collapsed.clear();
        } else {
            for (final String section : GROUP_ORDER) {
                if (!sectionMembers(section).isEmpty()) {
                    collapsed.add(section);
                }
            }
        }
        rebuildLines();
    }

    /** 某一节的勾选框状态：全选 / 部分选中 / 全不选。 */
    private int groupState(final List<String> members) {
        int on = 0;
        for (final String id : members) {
            if (selected.contains(id)) {
                on++;
            }
        }
        if (on == 0) {
            return 0;
        }
        return on == members.size() ? 2 : 1;
    }

    /**
     * 提交用的已选列表：<b>先按执行舱给的类别顺序</b>排（保证服务端看到的顺序稳定），
     * 再接上「当前快照里没有、但玩家之前勾过」的 id（不静默丢弃）。
     *
     * <p><b>与标签页无关</b>：这是「切页不丢勾选」的结构性保证 —— 提交只看 {@link #selected}
     * 这一个集合，页面只是它的一个视图（共享件出现在多页时也只有一个 id，天然不会重复提交）。</p>
     */
    private List<String> committedIds() {
        final List<String> result = new ArrayList<>(selected.size());
        for (final String id : categoryOrder) {
            if (selected.contains(id)) {
                result.add(id);
            }
        }
        for (final String id : selected) {
            if (!categoryOrder.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** 确定：一次性把整份勾选表交给服务端（确定性提交；不做乐观更新）。 */
    private void confirmAndReturn() {
        if (commitSink != null) {
            commitSink.accept(committedIds());
        }
        returnToParent();
    }

    // ==================== 列表（当前标签页 + 六节 + 折叠 + 搜索） ====================

    /**
     * 按「<b>当前标签页</b> + 六节 + 折叠 + 当前搜索词」重算要显示的行。
     *
     * <p><b>层次</b>：先只保留属于当前页的类别（{@link #inActiveTab}），再在页内按 {@link #GROUP_ORDER}
     * 分节 —— 用户要求的「每页只出现属于该配方的条目」就落在这里。</p>
     *
     * <p><b>搜索命中分三种，动作也分三种</b>（用户要求「搜索可以搜到这些配方、点击直接选」）：</p>
     * <ul>
     *     <li><b>配方名命中</b> → 顶部一条「切到「X」」结果行（{@link Kind#TAB}），点一下<b>切到那一页</b>；</li>
     *     <li><b>节点名 / 工序机器名命中</b> → 一条「跳到「X」」结果行（{@link Kind#JUMP}），
     *     点一下展开本节并滚到它，同时本节<b>整体</b>列出（这样「点进去就能直接勾」）；</li>
     *     <li><b>物品 / 流体名命中</b> → 命中的那几行本身，点一下就是勾选 / 取消；</li>
     *     <li>另外：别的页里有物品命中时也补一条切页行 —— 搜索<b>永远不会走进死胡同</b>。</li>
     * </ul>
     * <p><b>节表头的三态勾选框一律作用于整节</b>（不受搜索影响）：这样「全选本节」的语义始终是
     * 「本节全部类别」，也让「开着搜索点表头」不会漏掉被过滤掉的项。</p>
     */
    private void rebuildLines() {
        rebuildTabs();
        final String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        final List<Line> built = new ArrayList<>();
        if (!query.isEmpty()) {
            // ① 配方名命中 ⇒ 「切到「X」」结果行（点一下切页）
            for (final String tab : tabOrder) {
                if (tabDisplayName(tab).toLowerCase(Locale.ROOT).contains(query)) {
                    built.add(new Line(Kind.TAB, tab, jumpText(tab), null, List.copyOf(tabMembers(tab))));
                }
            }
            // ② 节名 / 工序机器名命中 ⇒ 本页内的「跳到「X」」结果行（既有行为）
            for (final String section : GROUP_ORDER) {
                final List<String> members = sectionMembers(section);
                if (members.isEmpty() || !sectionMatches(section, members, query)) {
                    continue;
                }
                built.add(new Line(Kind.JUMP, section,
                    Component.translatable(LANG + "jump.text",
                        Component.literal(sectionDisplayName(section))).getString(),
                    null, List.copyOf(members)));
            }
            // ③ 别的页里有物品命中 ⇒ 也给一条切页行（否则玩家会看到「没有匹配的类别」而不知道去哪找）
            for (final String tab : tabOrder) {
                if (tab.equals(activeTab) || tabDisplayName(tab).toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                if (tabHasItemMatch(tab, query)) {
                    built.add(new Line(Kind.TAB, tab, jumpText(tab), null, List.copyOf(tabMembers(tab))));
                }
            }
        }
        // ④ 当前页的六节（顺序即 GROUP_ORDER），空节不画
        for (final String section : GROUP_ORDER) {
            appendSection(built, section, query);
        }
        lines = built;
        clampScroll();
    }

    /** 「跳到「X」」的文案（切页行与节跳转行共用同一处，避免两处文案不一致）。 */
    private static String jumpText(final String name) {
        return Component.translatable(LANG + "jump.text", Component.literal(name)).getString();
    }

    /**
     * 追加<b>当前页里的一节</b>（节内是原有的勾选条目）。
     *
     * <p>节内一条都不命中时整节不追加（避免空节），但「节点名 / 工序名命中」时整节照列
     * （玩家点了「跳到某一节」就该看见那一节的全部内容）。</p>
     */
    private void appendSection(final List<Line> built, final String section, final String query) {
        final List<String> members = sectionMembers(section);
        if (members.isEmpty()) {
            return;
        }
        final boolean wholeSection = !query.isEmpty() && sectionMatches(section, members, query);
        final List<Line> items = new ArrayList<>(members.size());
        for (final String id : members) {
            final RsccBusCategory category = byId(id);
            if (category == null || (!wholeSection && !matches(category, query))) {
                continue;
            }
            items.add(new Line(Kind.ITEM, section, labelOf(category), category, List.of(id)));
        }
        if (items.isEmpty()) {
            return;
        }
        built.add(new Line(Kind.HEADER, section, headerText(section, members), null,
            List.copyOf(members)));
        if (!collapsed.contains(section)) {
            built.addAll(items);
        }
    }

    /** 某一页里是否有物品 / 流体命中（搜索跨页兜底用；只读）。 */
    private boolean tabHasItemMatch(final String tabKey, final String query) {
        for (final RsccBusCategory category : categories) {
            // 标记类别没有物品可命中：跳过它，搜索永远不会因为它给出「切到某一页」的假结果
            if (isTabOnly(category)) {
                continue;
            }
            if (tabKeysOf(category).contains(tabKey) && matches(category, query)) {
                return true;
            }
        }
        return false;
    }

    /** 类别对象按 id 反查（小列表线性查找足够；类别数受样板与配方限制）。 */
    @Nullable
    private RsccBusCategory byId(final String id) {
        for (final RsccBusCategory category : categories) {
            if (id != null && id.equals(category.id())) {
                return category;
            }
        }
        return null;
    }

    /** 显示名（与主界面完全同源，见 {@link ExporterExecutorRowWidget#displayName}）。 */
    private static String labelOf(final RsccBusCategory category) {
        return ExporterExecutorRowWidget.displayName(category).getString();
    }

    /** 搜索命中（物品 / 流体）：显示名或原始 id 含搜索词（忽略大小写）；空搜索词 = 全部命中。 */
    private static boolean matches(final RsccBusCategory category, final String query) {
        if (query.isEmpty()) {
            return true;
        }
        return labelOf(category).toLowerCase(Locale.ROOT).contains(query)
            || category.id().toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * 搜索命中（<b>节点名 / 工序机器名</b>）：节名含搜索词，或本节里任一<b>工序机器名</b>含搜索词。
     * <p>本界面里「哪套配方」已经由标签页表达，因此这里只判「哪一节 / 哪个工序」；
     * 两者都命中不了时退回物品名过滤（{@link #matches}）。</p>
     */
    private boolean sectionMatches(final String section, final List<String> members, final String query) {
        if (query.isEmpty()) {
            return false;
        }
        if (sectionDisplayName(section).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (final String id : members) {
            final RsccBusCategory category = byId(id);
            if (category == null) {
                continue;
            }
            final String machine = machineNameOf(category);
            if (!machine.isEmpty() && machine.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 节表头文字：<b>节点名 + 「已选 / 全部」</b>。
     * <p><b>不再带配方名</b>（那是被用户否掉的方案）：当前在哪一页已经由高亮的标签页表达，
     * 表头再拼一次「· 列车轨道」既是废话、又会让玩家以为「配方」是分组的一部分。</p>
     */
    private String headerText(final String section, final List<String> members) {
        return sectionDisplayName(section) + "  " + countSelected(members) + "/" + members.size();
    }

    /** 该类别对应步骤所用机器的显示名（注册名 → 物品悬浮名；取不到 = 空串）。 */
    private static String machineNameOf(final RsccBusCategory category) {
        if (category == null) {
            return "";
        }
        final String id = category.stepMachine();
        if (id == null || id.isEmpty()) {
            return "";
        }
        final ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return "";
        }
        final Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? "" : item.getDescription().getString();
    }

    private int countSelected(final List<String> members) {
        int on = 0;
        for (final String id : members) {
            if (selected.contains(id)) {
                on++;
            }
        }
        return on;
    }

    // ==================== 滚动 ====================

    private void clampScroll() {
        final int max = Math.max(0, lines.size() - VISIBLE_ROWS);
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow, max));
    }

    /** 同步滚动条的上限 / 可用性 / 当前位置（每帧一次，跟随搜索与分组变化）。 */
    private void refreshScrollbar() {
        if (scrollbar == null) {
            return;
        }
        final int max = Math.max(0, lines.size() - VISIBLE_ROWS);
        scrollbar.setMaxOffset(max);
        scrollbar.setEnabled(max > 0);
        clampScroll();
        scrollbar.setOffset(firstVisibleRow);
    }

    /** 翻到某一节：展开该节并把它滚到列表顶部（搜索结果「跳到「X」」的动作）。 */
    private void jumpToSection(final String section) {
        collapsed.remove(section);
        rebuildLines();
        for (int i = 0; i < lines.size(); i++) {
            final Line line = lines.get(i);
            if (line.kind() == Kind.HEADER && section.equals(line.groupKey())) {
                firstVisibleRow = Math.max(0, i - 1); // 留一行余量，表头不会贴顶
                break;
            }
        }
        clampScroll();
    }

    // ==================== 标签页行（绘制 / 命中 / 滚动共用的唯一几何口径） ====================

    /** 单个标签的宽度（= 文字宽 + 两侧留白，上限 {@link #TAB_MAX_W}）。 */
    private int tabWidth(final String tabKey) {
        final int text = font.width(trim(tabDisplayName(tabKey), TAB_MAX_W - TAB_PAD_X * 2));
        return text + TAB_PAD_X * 2;
    }

    /** 标签总宽（= 全部标签宽 + 缝隙）；用于判断是否溢出。 */
    private int tabsTotalWidth() {
        int total = 0;
        for (final String tabKey : tabOrder) {
            total += tabWidth(tabKey) + TAB_GAP;
        }
        return total;
    }

    /** 是否溢出（溢出时右端出现 ◀ / ▶ 两个翻页箭头，并给它俩预留宽度）。 */
    private boolean tabsOverflow() {
        return tabsTotalWidth() > TABS_W - PICK_W - TAB_PAD_X * 2;
    }

    /** 标签区可用右边界（先让出右端固定宽度的「全部配方」按钮，溢出时再让出两个箭头的位置）。 */
    private int tabsContentRight() {
        return px + TABS_X + TABS_W - PICK_W - (tabsOverflow() ? TAB_ARROW_W * 2 : 0);
    }

    /** 从 {@code from} 号标签开始，能否把 {@code to} 号标签也排进可见区（含两者之间的缝隙）。 */
    private boolean tabsFit(final int from, final int to) {
        if (from < 0 || to >= tabOrder.size() || from > to) {
            return false;
        }
        int x = px + TABS_X + TAB_PAD_X;
        for (int i = from; i <= to; i++) {
            x += tabWidth(tabOrder.get(i)) + TAB_GAP;
            if (x - TAB_GAP > tabsContentRight() - TAB_PAD_X) {
                return false;
            }
        }
        return true;
    }

    /**
     * 让<b>当前页</b>一定可见（切页时调用；只动 {@link #tabFrom}）。
     *
     * <p><b>为什么只在切页时调、不在每帧的 render 里调</b>：本方法只会把 {@link #tabFrom}
     * <b>往左拉</b>（把当前页拉进可见区）。若每帧都调，玩家用滚轮 / 箭头翻看其它页时会被
     * 立刻拉回当前页所在的位置 —— 翻页功能会「自己弹回去」，等于不可用。因此它只在
     * 「当前页变了」（{@link #switchTab(String)}）时执行一次，其余时刻滚动位置完全由玩家控制。</p>
     */
    private void ensureActiveTabVisible() {
        final int index = tabOrder.indexOf(activeTab);
        if (index < 0) {
            tabFrom = 0;
            return;
        }
        if (tabFrom > index) {
            tabFrom = index;
        }
        while (tabFrom < index && !tabsFit(tabFrom, index)) {
            tabFrom++;
        }
        tabFrom = Math.max(0, Math.min(tabFrom, tabOrder.size() - 1));
    }

    /** 标签页行的水平滚动（滚轮 / 箭头共用；只改显示，不碰勾选与当前页）。 */
    private void scrollTabs(final int delta) {
        if (!tabsOverflow() || delta == 0) {
            return;
        }
        tabFrom = Math.max(0, Math.min(tabFrom + delta, tabOrder.size() - 1));
        if (!tabsFit(tabFrom, tabFrom)) {
            tabFrom = Math.max(0, tabFrom - 1); // 单个都放不下（极窄）：退一格，避免画到箭头底下
        }
    }

    /**
     * 鼠标下的标签页（没压在任何标签上 = {@code null}）。
     * <p>只遍历<b>可见区</b>里的标签，且逐格用 {@link #tabWidth} 累计 —— 与 {@link #drawTabs} 同源，
     * 因此「画得出来的才点得到」。</p>
     */
    @Nullable
    private String tabAt(final double mouseX, final double mouseY) {
        if (!inBox(mouseX, mouseY, TABS_X, TABS_Y, TABS_W, TABS_H)) {
            return null;
        }
        int x = px + TABS_X + TAB_PAD_X;
        for (int i = tabFrom; i < tabOrder.size(); i++) {
            final String tabKey = tabOrder.get(i);
            final int w = tabWidth(tabKey);
            if (x + w > tabsContentRight() - TAB_PAD_X) {
                return null; // 放不下的那一页由箭头翻，不参与命中（画与命中同源）
            }
            if (mouseX >= x && mouseX < x + w) {
                return tabKey;
            }
            x += w + TAB_GAP;
        }
        return null;
    }

    /** 鼠标是否压在标签页行的 ◀ / ▶ 箭头之一上（-1 = 没有；0 = ◀，1 = ▶）。 */
    private int tabArrowAt(final double mouseX, final double mouseY) {
        if (!tabsOverflow() || !inBox(mouseX, mouseY, TABS_X, TABS_Y, TABS_W, TABS_H)) {
            return -1;
        }
        final int left = px + TABS_X + TABS_W - PICK_W - TAB_ARROW_W * 2;
        if (mouseX >= left && mouseX < left + TAB_ARROW_W) {
            return 0;
        }
        if (mouseX >= left + TAB_ARROW_W && mouseX < left + TAB_ARROW_W * 2) {
            return 1;
        }
        return -1;
    }

    // ==================== 「全部配方」弹出选择器（可点击 / 可滚动 / 可键盘 / 可搜索） ====================

    /** 鼠标是否压在标签页行最右端的「全部配方」按钮上（与 {@link #drawPickerButton} 同源几何）。 */
    private boolean inPickerButton(final double mouseX, final double mouseY) {
        return inBox(mouseX, mouseY, TABS_X + TABS_W - PICK_W, TABS_Y, PICK_W, TABS_H);
    }

    /** 鼠标是否压在弹出选择器矩形内（含右侧滚动条；与 {@link #drawPicker} 同源几何）。 */
    private boolean inPickerPopup(final double mouseX, final double mouseY) {
        return pickerOpen
            && mouseX >= pickerPopupX() && mouseX < pickerPopupX() + PICK_POP_W
            && mouseY >= pickerPopupY()
            && mouseY < pickerPopupY() + PICK_SEARCH_H + PICK_POP_H;
    }

    /** 弹出层内某点的行下标（不在弹出层 / 落在滚动条 / 空白处 = -1）。 */
    private int pickerIndexAt(final double mouseX, final double mouseY) {
        if (!pickerOpen || tabOrder.isEmpty()) {
            return -1;
        }
        final int x = pickerPopupX();
        final int y = pickerPopupY() + PICK_SEARCH_H; // 搜索行以下才是配方行
        if (mouseX < x || mouseX >= x + PICK_POP_W - PICK_BAR_W
            || mouseY < y || mouseY >= y + PICK_POP_H) {
            return -1;
        }
        final int row = (int) ((mouseY - y) / PICK_ROW_H);
        final int index = pickerScroll + row;
        return row >= 0 && row < PICK_VISIBLE && index < pickerFiltered().size() ? index : -1;
    }

    /**
     * 弹出选择器里<b>当前可见</b>的配方列表（按 {@link #pickerSearchBox} 的关键词过滤）。
     * <p>匹配口径与主搜索框一致：三套显示名任一命中即可（避免「明明有却搜不到」）。</p>
     */
    private java.util.List<String> pickerFiltered() {
        if (pickerSearchBox == null) {
            return tabOrder;
        }
        final String query = pickerSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return tabOrder;
        }
        final java.util.List<String> out = new java.util.ArrayList<>(tabOrder.size());
        for (final String key : tabOrder) {
            if (tabMatchesQuery(key, query)) {
                out.add(key);
            }
        }
        return out;
    }

    /** 只读：某个配方页签是否命中搜索词（显示名 / 原始 id / 配方内工序机器名）。 */
    private boolean tabMatchesQuery(final String tabKey, final String query) {
        if (tabKey == null) {
            return false;
        }
        if (tabDisplayName(tabKey).toLowerCase(Locale.ROOT).contains(query)
            || tabKey.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        // 这一页里的工序机器名 / 节名也参与匹配（与主搜索框的 sectionMatches 同口径）
        return sectionMatches(tabKey, tabMembers(tabKey), query)
            || tabMembers(tabKey).stream()
                .anyMatch(id -> sectionDisplayName(id).toLowerCase(Locale.ROOT).contains(query));
    }

    /** 夹紧选择器的滚动位置与键盘光标（页数变化后也不会越界）。 */
    private void clampPicker() {
        final int size = pickerFiltered().size();
        final int maxScroll = Math.max(0, size - PICK_VISIBLE);
        pickerScroll = Math.max(0, Math.min(pickerScroll, maxScroll));
        if (pickerCursor >= size) {
            pickerCursor = size - 1;
        }
    }

    /** 展开 / 收起选择器；展开时把滚动位置与键盘光标对齐到当前页（切页零副作用）。 */
    private void togglePicker() {
        pickerOpen = !pickerOpen;
        if (pickerSearchBox != null) {
            // 展开时把选择器搜索框复位并显示 / 聚焦（收起时隐藏且不抢焦点）
            pickerSearchBox.setValue("");
            pickerSearchBox.visible = pickerOpen;
        }
        if (pickerOpen) {
            if (pickerSearchBox != null) {
                setFocused(pickerSearchBox);
            }
            pickerCursor = Math.max(0, tabOrder.indexOf(activeTab));
            pickerScroll = Math.max(0, Math.min(pickerCursor, Math.max(0, tabOrder.size() - PICK_VISIBLE)));
            clampPicker();
        }
    }

    /** 收起选择器（不改任何勾选 / 当前页）。 */
    private void closePicker() {
        pickerOpen = false;
        pickerCursor = -1;
        if (pickerSearchBox != null) {
            pickerSearchBox.visible = false;
        }
        setFocused(searchBox); // 焦点还给主搜索框，键盘操作不留空档
    }

    /** 键盘 ↑ / ↓：移动光标并保证光标行可见（<b>只移动光标</b>，回车才真正切页）。 */
    private void movePickerCursor(final int delta) {
        if (tabOrder.isEmpty()) {
            return;
        }
        if (pickerCursor < 0) {
            pickerCursor = Math.max(0, tabOrder.indexOf(activeTab));
        }
        pickerCursor = Math.max(0, Math.min(pickerCursor + delta, tabOrder.size() - 1));
        if (pickerCursor < pickerScroll) {
            pickerScroll = pickerCursor;
        }
        if (pickerCursor >= pickerScroll + PICK_VISIBLE) {
            pickerScroll = pickerCursor - PICK_VISIBLE + 1;
        }
        clampPicker();
    }

    /** 滚轮 / 拖动的滚动（只改显示）。 */
    private void scrollPicker(final int delta) {
        if (tabOrder.size() <= PICK_VISIBLE) {
            return;
        }
        pickerScroll = Math.max(0, Math.min(pickerScroll + delta, tabOrder.size() - PICK_VISIBLE));
    }

    /** 在弹出选择器里选一页：<b>先收起、再切页</b>（只改显示，勾选与 configSelection 一个都不动）。 */
    private void pickTab(final String tabKey) {
        closePicker();
        if (tabKey != null) {
            switchTab(tabKey);
        }
    }

    /** 鼠标下的行下标（-1 = 没有）。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (mouseX < px + LIST_X || mouseX >= px + LIST_X + LIST_W
            || mouseY < py + LIST_Y || mouseY >= py + LIST_Y + LIST_H) {
            return -1;
        }
        final int row = (int) ((mouseY - (py + LIST_Y)) / ROW_H);
        final int index = firstVisibleRow + row;
        return row >= 0 && row < VISIBLE_ROWS && index < lines.size() ? index : -1;
    }

    /** 鼠标是否压在节表头的「折叠三角」上（只有表头有三角）。 */
    private boolean inCollapseTriangle(final double mouseX) {
        final double local = mouseX - (px + LIST_X);
        return local >= ROW_TRI_X && local < ROW_TRI_X + ROW_TRI_W;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        // 折叠按钮的文案跟随当前状态（每帧回写，服务端类别变化后也不会说反）
        if (foldButton != null) {
            foldButton.setMessage(Component.translatable(
                allCollapsed() ? LANG + "btn.unfold" : LANG + "btn.fold"));
        }
        refreshScrollbar();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + TITLE_X, py + TITLE_Y, COLOR_TITLE, false);
        // 第一级：配方标签页（常驻、不随列表滚动）；第二级：页内六节（在下面的列表里）
        drawTabs(guiGraphics, mouseX, mouseY);
        drawList(guiGraphics, mouseX, mouseY);
        drawHint(guiGraphics);
        // 「全部配方」弹出选择器：覆盖在列表之上（最后画），关闭后列表原样可见
        drawPicker(guiGraphics, mouseX, mouseY);
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * <b>配方标签页行</b>：底槽 + 逐个标签（当前页高亮）+ 溢出时的 ◀ / ▶ 箭头。
     *
     * <h2>为什么不用原版 Tab 控件</h2>
     * <p>原版没有这种「页签」控件，且本模组的子界面一律「不新画贴图、全部 Java 绘制」，
     * 因此用 {@code fill} 堆出页签观感（底色 + 1px 描边 + 选中页与列表同色）；
     * 命中判定与绘制共用 {@link #tabWidth} / {@link #tabsContentRight()} 同一批几何，
     * 不会出现「看着能点、点不到」。</p>
     */
    private void drawTabs(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int left = px + TABS_X;
        final int right = left + TABS_W;
        // 底槽（凹陷：1px 描边 + 略深的底）
        guiGraphics.fill(left - 1, py + TABS_Y - 1, right + 1, py + TABS_Y + TABS_H + 1, COLOR_BORDER);
        guiGraphics.fill(left, py + TABS_Y, right, py + TABS_Y + TABS_H, COLOR_TAB_TRACK_BG);
        final boolean overflow = tabsOverflow();
        int x = left + TAB_PAD_X;
        for (int i = tabFrom; i < tabOrder.size(); i++) {
            final String tabKey = tabOrder.get(i);
            final int w = tabWidth(tabKey);
            if (x + w > tabsContentRight() - TAB_PAD_X) {
                break; // 放不下的页交给箭头翻（与 tabAt 同源）
            }
            final boolean active = tabKey.equals(activeTab);
            final boolean hover = mouseX >= x && mouseX < x + w
                && mouseY >= py + TABS_Y && mouseY < py + TABS_Y + TABS_H;
            drawTab(guiGraphics, x, w, tabKey, active, hover);
            x += w + TAB_GAP;
        }
        if (overflow) {
            drawTabArrow(guiGraphics, right - PICK_W - TAB_ARROW_W * 2, false, tabFrom > 0);
            drawTabArrow(guiGraphics, right - PICK_W - TAB_ARROW_W, true, tabFrom < tabOrder.size() - 1);
        }
        drawPickerButton(guiGraphics, mouseX, mouseY);
    }

    /**
     * 「全部配方」选择器按钮（标签页行最右端的固定按钮）：底色 + 1px 描边 + 居中文字
     * （文字 = 语言键 {@code tab.pick}，宽度不足时按像素截断），展开时画成按下的凹陷观感。
     */
    private void drawPickerButton(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int x = px + TABS_X + TABS_W - PICK_W;
        final int top = py + TABS_Y;
        final boolean hover = inPickerButton(mouseX, mouseY);
        final int bg = pickerOpen ? COLOR_TAB_ACTIVE_BG : (hover ? COLOR_TAB_HOVER_BG : COLOR_TAB_BG);
        guiGraphics.fill(x, top, x + PICK_W, top + TABS_H, COLOR_TAB_BORDER);
        guiGraphics.fill(x + 1, top + 1, x + PICK_W - 1, top + TABS_H - 1, bg);
        final String text = trim(Component.translatable(LANG + "tab.pick").getString(), PICK_W - 4);
        guiGraphics.drawString(font, text, x + (PICK_W - font.width(text)) / 2, top + 4,
            pickerOpen ? COLOR_TAB_ACTIVE_TEXT : COLOR_TAB_TEXT, false);
    }

    /** 弹出选择器的矩形（右对齐到标签页行右缘、对齐在其正下方；覆盖层，不登记几何）。 */
    private int pickerPopupX() {
        return px + TABS_X + TABS_W - PICK_POP_W;
    }

    private int pickerPopupY() {
        return py + TABS_Y + TABS_H + 1;
    }

    /**
     * 弹出选择器：完整配方清单（{@link #tabOrder}），<b>可滚动、可键盘、当前页高亮</b>。
     *
     * <p>行文字用与标签页同一个 {@link #tabDisplayName(String)}（配方结果物名），
     * 并按像素截断到面板内；右侧画一条 4px 滚动条（行数超过可见行时才出现）。
     * 覆盖层压在列表之上，关闭后列表原样可见 —— 不改变任何静态几何。</p>
     */
    private void drawPicker(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (!pickerOpen || tabOrder.isEmpty()) {
            return;
        }
        final int x = pickerPopupX();
        final int y = pickerPopupY();
        clampPicker();
        // <b>2026-10-05 修掉「搜索框看不见但能输入」</b>（用户原话：「点击全部配方之后的输入框并没有出现，
        // 但是好像能够输入，但是我看不见我在哪里输入了」）。
        //
        // 原因：加了搜索行之后，<b>弹出层自己的背景框忘了跟着加高</b> —— 外框画的是
        // {@code y-1 .. y + PICK_POP_H + 1}，而搜索行占据 {@code y .. y + PICK_SEARCH_H}，
        // 于是背景从搜索行<b>下沿</b>才开始，输入框本体（widget，绘制在背景之后）虽然画出来了，
        // 却浮在边框外面 / 与下方内容重叠，看起来「没有输入框」。
        // 现在把外框与底色整体向下扩 {@link #PICK_SEARCH_H}。
        guiGraphics.fill(x - 1, y - 1, x + PICK_POP_W + 1,
            y + PICK_SEARCH_H + PICK_POP_H + 1, COLOR_PICK_BORDER);
        guiGraphics.fill(x, y, x + PICK_POP_W, y + PICK_SEARCH_H + PICK_POP_H, COLOR_PICK_BG);
        final java.util.List<String> rows = pickerFiltered();
        // 顶端「搜索配方」输入行：背景 + 输入框本体（本体是 widget，由 MC 自己画）
        guiGraphics.fill(x, y, x + PICK_POP_W, y + PICK_SEARCH_H, COLOR_TAB_BG);
        if (pickerSearchBox != null) {
            pickerSearchBox.setX(x + 5);
            pickerSearchBox.setY(y + 2);
            pickerSearchBox.setWidth(PICK_POP_W - 12);
        }
        final int hovered = pickerIndexAt(mouseX, mouseY);
        for (int row = 0; row < PICK_VISIBLE; row++) {
            final int index = pickerScroll + row;
            if (index >= rows.size()) {
                break;
            }
            final String tabKey = rows.get(index);
            final int ry = y + PICK_SEARCH_H + row * PICK_ROW_H;
            if (index == pickerCursor) {
                guiGraphics.fill(x, ry, x + PICK_POP_W, ry + PICK_ROW_H, COLOR_PICK_CURSOR);
            } else if (index == hovered) {
                guiGraphics.fill(x, ry, x + PICK_POP_W, ry + PICK_ROW_H, COLOR_PICK_HOVER);
            }
            if (tabKey.equals(activeTab)) {
                guiGraphics.fill(x, ry, x + 2, ry + PICK_ROW_H, COLOR_CHECK_ON);
            }
            final boolean active = tabKey.equals(activeTab);
            guiGraphics.drawString(font, trim(tabDisplayName(tabKey), PICK_POP_W - 8),
                x + 4, ry + 2, active ? COLOR_CHECK_ON : COLOR_TEXT, false);
        }
        if (rows.size() > PICK_VISIBLE) {
            final int barX = x + PICK_POP_W - PICK_BAR_W;
            final int barY = y + PICK_SEARCH_H;
            guiGraphics.fill(barX, barY, barX + PICK_BAR_W, barY + PICK_POP_H, COLOR_PICK_TRACK);
            final int thumbH = Math.max(8, PICK_POP_H * PICK_VISIBLE / rows.size());
            final int maxScroll = rows.size() - PICK_VISIBLE;
            final int thumbY = barY + (PICK_POP_H - thumbH) * pickerScroll / Math.max(1, maxScroll);
            guiGraphics.fill(barX, thumbY, barX + PICK_BAR_W, thumbY + thumbH, COLOR_TAB_ARROW);
        }
    }

    /** 单个标签：底色 + 1px 描边 + 居中文字（选中页底色与列表同色，视觉上与下方连成一片）。 */
    private void drawTab(final GuiGraphics guiGraphics, final int x, final int w, final String tabKey,
                         final boolean active, final boolean hover) {
        final int top = py + TABS_Y;
        final int bg = active ? COLOR_TAB_ACTIVE_BG : (hover ? COLOR_TAB_HOVER_BG : COLOR_TAB_BG);
        guiGraphics.fill(x, top, x + w, top + TABS_H, COLOR_TAB_BORDER);
        guiGraphics.fill(x + 1, top + 1, x + w - 1, top + TABS_H - 1, bg);
        if (active) {
            // 选中页：底边与列表同色（把「页签 → 页面」的从属关系画出来）+ 顶部 2px 强调条
            guiGraphics.fill(x + 1, top + TABS_H - 1, x + w - 1, top + TABS_H, COLOR_TAB_ACTIVE_BG);
            guiGraphics.fill(x + 1, top + 1, x + w - 1, top + 3, COLOR_CHECK_ON);
        }
        final String text = trim(tabDisplayName(tabKey), w - TAB_PAD_X * 2);
        guiGraphics.drawString(font, text, x + TAB_PAD_X, top + 4,
            active ? COLOR_TAB_ACTIVE_TEXT : COLOR_TAB_TEXT, false);
    }

    /** 翻页箭头（◀ / ▶ 用 4 列 fill 现画，不依赖字形、不新增贴图；不可用时画成灰）。 */
    private void drawTabArrow(final GuiGraphics guiGraphics, final int x, final boolean right,
                              final boolean enabled) {
        final int color = enabled ? COLOR_TAB_ARROW : 0xFF8A8A8A;
        final int top = py + TABS_Y;
        guiGraphics.fill(x, top, x + TAB_ARROW_W, top + TABS_H, COLOR_TAB_BG);
        final int cx = x + TAB_ARROW_W / 2 - 2;
        final int cy = top + (TABS_H - 7) / 2;
        for (int i = 0; i < 4; i++) {
            final int col = right ? cx + i : cx + 3 - i;
            guiGraphics.fill(col, cy + i, col + 1, cy + 7 - i, color);
        }
    }

    /**
     * 列表一行都没有时那句说明（{@link #drawList} 的空态）。
     *
     * <p><b>为什么分两句</b>：</p>
     * <ul>
     *     <li><b>当前页来自标记类别</b>（= 整链一台单元样板都没有：页名是配方名或处理器类型名，
     *     但页内必定零行）<b>且搜索框为空</b> ⇒ 说「还没有放入单元样板」：
     *     这一页天生的状态就是零行（没有单元样板 = 没有「具体配方 + 步序」），
     *     复用「没有匹配的类别」会让玩家以为是自己搜错了；</li>
     *     <li><b>其余情形</b>（搜索没命中 / 判不出归属 / 本来就没有类别）⇒ 沿用既有文案，一字不改。
     *     搜索框里有关键字时一律走这一句：那是「没搜到」，不是「没有样板」。</li>
     * </ul>
     */
    private Component emptyListText() {
        final boolean searching = searchBox != null && !searchBox.getValue().trim().isEmpty();
        return Component.translatable(LANG + (isMarkerFedTab(activeTab) && !searching
            ? "empty_recipe_tab" : "empty"));
    }

    /**
     * 该页是否来自「整链一台单元样板都没有」时服务端补的标记类别 —— 含它展开出来的<b>每一个配方页</b>
     * （页名是配方名，但页内照样零行）与兜底的处理器类型页。
     *
     * <p>{@link #recipeTypeByTabKey} 非空 ⇔ 快照里有标记类别；而服务端<b>只在快照为空时</b>补它
     * （见 {@code SequenceExecutionChamberBlockEntity#appendRecipeTabMarker}），
     * 因此那时除兜底页 {@link #TAB_OTHER} 外的每一页都源自标记。{@link #TAB_OTHER} 永远不由标记产生。</p>
     */
    private boolean isMarkerFedTab(final String tabKey) {
        return !recipeTypeByTabKey.isEmpty() && !TAB_OTHER.equals(tabKey);
    }

    /** 列表：凹陷底 + 逐行绘制（表头 / 条目 / 搜索跳转行）+ 滚动条。 */
    private void drawList(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.fill(px + LIST_X - 1, py + LIST_Y - 1,
            px + LIST_X + LIST_W + 1, py + LIST_Y + LIST_H + 1, COLOR_BORDER);
        guiGraphics.fill(px + LIST_X, py + LIST_Y,
            px + LIST_X + LIST_W, py + LIST_Y + LIST_H, COLOR_LIST_BG);
        if (lines.isEmpty()) {
            guiGraphics.drawString(font, emptyListText(),
                px + LIST_X + 4, py + LIST_Y + 4, COLOR_DIM, false);
            return;
        }
        final int hovered = locked() ? -1 : rowAt(mouseX, mouseY);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            final int index = firstVisibleRow + row;
            if (index >= lines.size()) {
                break;
            }
            final int y = py + LIST_Y + row * ROW_H;
            final boolean hover = index == hovered;
            final Line line = lines.get(index);
            switch (line.kind()) {
                case HEADER -> drawHeader(guiGraphics, line, y, hover);
                case JUMP -> drawJump(guiGraphics, line, y, hover);
                case TAB -> drawTabJump(guiGraphics, line, y, hover);
                default -> drawItem(guiGraphics, line, y, hover);
            }
        }
        if (locked()) {
            // 自动模式（只读）：整块列表加一层浅灰蒙版 = 原版「控件已禁用」的观感；
            // 勾选标记仍透出来，因此玩家照样看得见「系统帮他选了哪些」（用户要求：禁用 + 自动选上）。
            guiGraphics.fill(px + LIST_X, py + LIST_Y,
                px + LIST_X + LIST_W, py + LIST_Y + LIST_H, COLOR_LOCKED_WASH);
        }
        if (scrollbar != null && scrollbar.visible) {
            scrollbar.render(guiGraphics, mouseX, mouseY, 0.0F);
        }
    }

    /**
     * 节表头行：左端分节色带 + 折叠三角 + 三态勾选框 + 「节点名 已选/全部」。
     * <p>绘制与命中同源：三角的横向范围就是 {@link #inCollapseTriangle} 用的同一批常量。</p>
     * <p><b>不再出现配方名</b>：当前在哪一页由高亮标签页表达（用户否掉了「类别 · 配方名」后缀方案）。</p>
     */
    private void drawHeader(final GuiGraphics guiGraphics, final Line line, final int y,
                            final boolean hover) {
        final int left = px + LIST_X;
        guiGraphics.fill(left, y, left + LIST_W, y + ROW_H,
            hover ? 0xFF8A8A8A : COLOR_HEADER_BG);
        guiGraphics.fill(left, y, left + 3, y + ROW_H, sectionColorOf(line.groupKey()));
        drawCollapseTriangle(guiGraphics, left + ROW_TRI_X, y + (ROW_H - 7) / 2,
            collapsed.contains(line.groupKey()));
        drawCheckbox(guiGraphics, left + ROW_CHECK_X, y + (ROW_H - ROW_CHECK_SIZE) / 2,
            groupState(line.members()));
        guiGraphics.drawString(font, trim(line.text(), LIST_W - ROW_TEXT_X - 12),
            left + ROW_TEXT_X, y + 5, COLOR_TEXT, false);
    }

    /**
     * 搜索结果里的「跳到某一节」行：➤ 标记 + 文案。
     * <p>点一下 = {@link #jumpToSection(String)}（展开该节并翻到它）。</p>
     */
    private void drawJump(final GuiGraphics guiGraphics, final Line line, final int y,
                          final boolean hover) {
        final int left = px + LIST_X;
        guiGraphics.fill(left, y, left + LIST_W, y + ROW_H, hover ? 0xFFC2D4EA : COLOR_JUMP_BG);
        guiGraphics.fill(left, y, left + 3, y + ROW_H, sectionColorOf(line.groupKey()));
        drawJumpArrow(guiGraphics, left, y);
        guiGraphics.drawString(font, trim(line.text(), LIST_W - ROW_TEXT_X - 12),
            left + ROW_TEXT_X, y + 5, COLOR_JUMP_TEXT, false);
    }

    /**
     * 搜索结果里的「切到某一页」行（{@link Kind#TAB}）：样式与节跳转行同族，但左端色带用<b>绿色</b>
     * （= 整个页签的色，提示「这一条会换页」），文案是「跳到「列车轨道」」。
     * <p>点一下 = {@link #switchTab(String)}（切页；<b>不改任何勾选</b>）。</p>
     */
    private void drawTabJump(final GuiGraphics guiGraphics, final Line line, final int y,
                             final boolean hover) {
        final int left = px + LIST_X;
        guiGraphics.fill(left, y, left + LIST_W, y + ROW_H, hover ? 0xFFD6E7D8 : 0xFFE4F0E6);
        guiGraphics.fill(left, y, left + 3, y + ROW_H, COLOR_CHECK_ON);
        drawJumpArrow(guiGraphics, left, y);
        guiGraphics.drawString(font, trim(line.text(), LIST_W - ROW_TEXT_X - 12),
            left + ROW_TEXT_X, y + 5, COLOR_JUMP_TEXT, false);
    }

    /** 跳转行左端的 ➤ 标记（4 列 fill 现画，不依赖字体字形、不新增贴图）。 */
    private static void drawJumpArrow(final GuiGraphics guiGraphics, final int left, final int y) {
        final int ax = left + ROW_TRI_X;
        final int ay = y + (ROW_H - 7) / 2;
        for (int i = 0; i < 4; i++) {
            guiGraphics.fill(ax + i, ay + i, ax + i + 1, ay + 7 - i, COLOR_JUMP_TEXT);
        }
    }

    /**
     * 折叠三角：展开 = 向下的实心三角，折叠 = 向右的实心三角。
     * <p>用 {@code fill} 逐行堆出来（不新增任何贴图）。</p>
     */
    private static void drawCollapseTriangle(final GuiGraphics guiGraphics, final int x, final int y,
                                             final boolean collapsed) {
        if (collapsed) {
            for (int i = 0; i < 4; i++) {
                guiGraphics.fill(x, y + i, x + (4 - i), y + i + 1, COLOR_TRI);
            }
        } else {
            for (int i = 0; i < 4; i++) {
                guiGraphics.fill(x + i, y + i, x + 7 - i, y + i + 1, COLOR_TRI);
            }
        }
    }

    /**
     * 条目行：勾选框 + 原素材槽框里的图标 + 名字（中间产物再在右侧补一个暗色「第 N 步」后缀）。
     * <p>图标外框复用 {@link McGui#slotFrame}（<b>17×17 原素材画法</b>，Java 绘制、不新增贴图），
     * 槽框画在「图标左上角 − 1」，与「精灵坐标 → Menu 槽位坐标 +1」同一口径。</p>
     * <p>「第 N 步」只作为<b>行内后缀</b>出现，<b>不</b>把中间产物再拆成分组
     * （用户明确要求「中间产物下面不要再细分步骤」），也<b>不</b>塞进物品 tooltip
     * （物品 tooltip 必须与背包里逐行一致）。</p>
     */
    private void drawItem(final GuiGraphics guiGraphics, final Line line, final int y,
                          final boolean hover) {
        final RsccBusCategory category = line.category();
        if (category == null) {
            return;
        }
        final int left = px + LIST_X;
        final boolean on = selected.contains(category.id());
        if (hover) {
            guiGraphics.fill(left, y, left + LIST_W, y + ROW_H, COLOR_ROW_HOVER);
        }
        drawCheckbox(guiGraphics, left + ROW_CHECK_X, y + (ROW_H - ROW_CHECK_SIZE) / 2, on ? 2 : 0);
        // 图标（16×16）：与主界面同一套图标实现，两个界面显示的必然是同一个东西
        if (category.isFluidInput()) {
            final FluidStack fluid = ExporterExecutorRowWidget.fluidIconOf(category);
            if (!fluid.isEmpty()) {
                McGui.slotFrame(guiGraphics, left + ROW_ICON_X - 1, y);
                GhostMarkerRenderer.renderFluid(guiGraphics, left + ROW_ICON_X, y + 1, fluid);
            }
        } else {
            // 图标 = {@link #displayStackOf} 给出的<b>那一件</b>：多候选（铁粒|锌粒 / 任意台阶）时它就是
            // 当前轮播到的那一件 —— 与行内名字、物品 tooltip <b>同一个入口</b>，
            // 因此「画的是锌粒、tooltip 却是铁粒」结构上不可能再发生。
            final ItemStack shown = displayStackOf(category);
            if (!shown.isEmpty()) {
                McGui.slotFrame(guiGraphics, left + ROW_ICON_X - 1, y);
                guiGraphics.renderItem(shown, left + ROW_ICON_X, y + 1);
            }
        }
        // <b>2026-10-05 用户实测</b>：<i>「他那个 tooltip 那里一直显示石头台阶，然后旁边的文字也只是一直
        // 显示石头台阶，并没有轮换显示」</i>。
        // 原因：图标早就按候选轮播了，但<b>名字取的是静态的 {@code line.text()}</b>（= 类别代表物），
        // 于是「图在换、字不换」，看起来像坏了。
        // 现在名字与图标<b>同源同相位</b>：多候选类别显示当前轮播到的那一件的名称。
        final String rowText = rowTextFor(category, line.text());
        guiGraphics.drawString(font, trim(rowText, ROW_TEXT_W),
            left + ROW_NAME_X, y + 5, on ? COLOR_TEXT : COLOR_DIM, false);
        drawRowSuffix(guiGraphics, category, rowText, y);
    }

    /**
     * <b>本行此刻显示的那一件</b>（图标 / 行内名字 / 物品 tooltip 三处共用的<b>唯一</b>入口）。
     *
     * <h2>为什么必须有它（2026-10-06 用户实测：<i>「明明显示的是锌粒，但是 tooltip 还是显示铁粒」</i>）</h2>
     * <p>改这一处之前，三处各自取数：图标与行内名字取
     * {@link GhostMarkerRenderer#cycleCandidate}{@code (candidateStacksOf(category))}（会轮播），
     * 而 tooltip 取 {@link ExporterExecutorRowWidget#iconOf}（= {@code category.iconItem()}，
     * 也就是服务端下发类别的那个<b>组代表物</b>，恒定不轮播）。于是一个「铁粒 + 锌粒」的类别
     * 在轮播到锌粒那一秒，行内是锌粒、tooltip 却仍是铁粒 —— 同一行指了两件东西。</p>
     * <p>现在三处都只读本方法：<b>多候选 → 当前轮播到的那一件；单候选 / 无候选 → 代表物</b>。
     * 轮播相位来自 {@link GhostMarkerRenderer#cycleCandidate}（tick 取模，同一帧内恒定），
     * 因此同一帧里画的、写的、tip 的必然是同一件物品。</p>
     *
     * <p><b>「组代表物」还需要吗</b>：需要 —— 它仍是这个类别的<b>持久身份</b>：类别 id 的物品段
     * （{@code input:<配方>#<组代表物>}，老存档与勾选集合都按它走）与
     * {@link ExporterExecutorRowWidget#iconOf}（= 代表物本身，<b>没有候选可轮播</b>时各界面共用的兜底显示）。
     * 它在本界面上只作为「没有候选可轮播」时的兜底显示，<b>不再</b>当作有候选时的行内身份 ——
     * 这正是原先图标 / 名字与 tooltip 对不上的错配点。</p>
     *
     * <p><b>2026-10-06 起主界面类别条也走这里</b>（见 {@link #barStackOf}）：两处显示同一件，
     * 因此「详细配置里轮播、主条上恒定铁粒」的不一致被结构性消除。</p>
     */
    private static ItemStack displayStackOf(final RsccBusCategory category) {
        if (category != null && category.isInput()) {
            final List<ItemStack> candidates = candidateStacksOf(category);
            if (candidates.size() > 1) {
                return GhostMarkerRenderer.cycleCandidate(candidates);
            }
        }
        return ExporterExecutorRowWidget.iconOf(category);
    }

    /**
     * <b>主界面类别条里那一格此刻该画的那一件</b>（输入总线 / 输出总线两侧共用同一个控件，
     * 因此这一个方法同时服务两侧）。
     *
     * <h2>为什么必须复用本界面的 {@link #displayStackOf}（2026-10-06 用户实测）</h2>
     * <p>用户截图：<i>「显示为两个铁粒……就是它一直显示成铁粒，没有轮换。这个界面应该也出现轮换」</i>。
     * 原因是条上那格直读 {@link ExporterExecutorRowWidget#iconOf}（= {@code category.iconItem()}
     * = 组代表物，恒定铁粒），而同一个类别在详细配置界面里早已按候选轮播 —— 于是同一件事在两个界面
     * 显示成两件不同的东西。</p>
     * <p>本方法<b>不含任何轮播逻辑</b>，只是把主条接到 {@link #displayStackOf} 上：
     * 多候选 ⇒ 当前轮播到的那一件（{@link GhostMarkerRenderer#cycleCandidate}，tick / {@code CYCLE_TICKS}
     * 取模，与详细配置界面<b>同一套相位</b>）；单候选 / 无候选 ⇒ 组代表物（行为与改动前完全一致）。</p>
     * <p>「一个类别一格」的语义不变：候选只是同一个 ingredient 的多种可能，<b>不</b>会因此多出一格。</p>
     */
    public static ItemStack barStackOf(final RsccBusCategory category) {
        return displayStackOf(category);
    }

    /**
     * <b>主界面类别条这一刻该写的名字</b>：与 {@link #barStackOf} 同源同相位 ——
     * 多候选时是当前轮播到那一件的名字，否则是类别的静态显示名
     * （{@link ExporterExecutorRowWidget#displayName}）。
     *
     * <p>取哪一件<b>只</b>由 {@link #displayStackOf} 决定（判据与行内 {@code rowTextFor} 相同，
     * 都是「候选多于一件才轮播」）；这里绝不另算一份相位，否则又会出现
     * 「条上是锌粒、tooltip 是铁粒」这种错配。</p>
     */
    public static Component barNameOf(final RsccBusCategory category) {
        if (category != null && category.isInput() && candidateStacksOf(category).size() > 1) {
            final ItemStack shown = displayStackOf(category);
            if (!shown.isEmpty()) {
                return shown.getHoverName();
            }
        }
        return ExporterExecutorRowWidget.displayName(category);
    }

    /**
     * <b>该类别的候选物品</b>（唯一入口：图标轮播、行内名字、tooltip 三处共用同一份）。
     *
     * <h2>为什么改读服务端下发的候选（2026-10-05 用户实测「精密构件还是铁粒+锌粒」）</h2>
     * <p>原先读的是客户端<b>自己猜</b>的 {@code inputCandidatesByItem}：它扫全部序列装配配方，
     * 按「代表物」合并 —— 而「精密构件」与「列车轨道」的机械手步都声明铁粒，
     * 于是列车轨道那一组 {@code [铁粒, 锌粒]} 也挂到了「铁粒」这个键上，
     * 精密构件的铁粒格因此被显示成「铁粒或锌粒」。客户端无从知道「这一格属于哪条配方」。</p>
     * <p>现在只读 {@link RsccBusCategory#candidateIds()}（服务端按配方限定算好后随类别下发），
     * 从根上消除跨配方污染。服务端没带候选（老快照 / 单件类别）⇒ 退回图标那一件，行为与改动前一致。</p>
     */
    private static List<ItemStack> candidateStacksOf(final RsccBusCategory category) {
        if (category == null || !category.isInput()) {
            return List.of();
        }
        final List<String> ids = category.candidateIds();
        if (ids.isEmpty()) {
            // 服务端没带候选 ⇒ 退回图标那一件（保证至少有一件可显示）
            final ItemStack icon = ExporterExecutorRowWidget.iconOf(category);
            return icon.isEmpty() ? List.of() : List.of(icon);
        }
        final List<ItemStack> out = new ArrayList<>(ids.size());
        for (final String id : ids) {
            final ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) {
                continue;
            }
            final Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                out.add(new ItemStack(item));
            }
        }
        if (out.isEmpty()) {
            final ItemStack icon = ExporterExecutorRowWidget.iconOf(category);
            return icon.isEmpty() ? List.of() : List.of(icon);
        }
        return out;
    }

    /**
     * 该行当前应显示的名称：多候选输入类别 ⇒ <b>当前轮播到的那一件</b>（与图标同一份候选、同一相位）；
     * 其余情况 ⇒ 调用方给的静态文本（= 类别的代表物 / 流体名 / 标签键）。
     * <p>取哪一件一律问 {@link #displayStackOf}（与图标 / tooltip 同一个入口），
     * 这里只负责把「单候选 / 非输入类别」退回静态文本，绝不另算一份相位。</p>
     */
    private String rowTextFor(final RsccBusCategory category, final String fallback) {
        if (!category.isInput()) {
            return fallback;
        }
        final List<ItemStack> candidates = candidateStacksOf(category);
        if (candidates.size() <= 1) {
            return fallback;
        }
        final ItemStack shown = displayStackOf(category);
        return shown.isEmpty() ? fallback : shown.getHoverName().getString();
    }

    /**
     * 条目行右侧的暗色后缀（用 {@code " · "} 拼接，只画放得下的部分）：
     * <ol>
     *     <li><b>「第 N 步」</b>（仅中间产物；步序未知时不画）；</li>
     *     <li><b>「可复用」</b>（用户第 2 条：与其它配方的同类型步骤语义相同）；</li>
     *     <li><b>「多配方共用」</b>（第 44 轮起：只有<b>流体 / 成品 / 废料</b>可能跨页 ——
     *     同一件产出物被两条及以上配方用到时它同时出现在各页里，勾选是同一份；
     *     物品输入类别按配方分开、各自成页，因此不会再带这个标记）。</li>
     * </ol>
     * <p>全是<b>行内标注</b>：不构成分组、不进物品 tooltip（物品 tooltip 必须与背包逐行一致）。</p>
     */
    private void drawRowSuffix(final GuiGraphics guiGraphics, final RsccBusCategory category,
                               final String name, final int y) {
        final List<String> parts = new ArrayList<>(3);
        if (category.isIntermediate() && category.intermediateStep() >= 0) {
            parts.add(Component.translatable(LANG + "row.step",
                category.intermediateStep() + 1).getString());
        }
        if (category.isIntermediate() && sharedReuseCategoryIds.contains(category.id())) {
            parts.add(Component.translatable(LANG + "row.reusable").getString());
        }
        if (isSharedAcrossRecipes(category)) {
            parts.add(Component.translatable(LANG + "row.shared").getString());
        }
        if (parts.isEmpty()) {
            return;
        }
        final String suffix = String.join(" · ", parts);
        final int right = px + LIST_X + LIST_W - ROW_SUFFIX_PAD;
        final int nameEnd = px + LIST_X + ROW_NAME_X + font.width(trim(name, ROW_TEXT_W));
        final int x = Math.max(nameEnd + 3, right - font.width(suffix));
        if (x + font.width(suffix) <= right) {
            guiGraphics.drawString(font, suffix, x, y + 5, COLOR_STEP_TEXT, false);
        }
    }

    /**
     * 底部一行：左边「已选 N / M 项」（自动模式下换成「自动模式：只读」），右边「怎么改」。
     *
     * <h2>本轮重排（用户原话：「字太长了，超了，而且糊在一起了，看不清楚，重新改排版」）</h2>
     * <p>两手一起上：</p>
     * <ol>
     *     <li><b>文案缩短</b>（{@code auto.locked} / {@code mode.auto} / {@code mode.manual} 三个键，
     *     中英同步改短）—— 两段合起来在 236px 的行宽里放得下，<b>根本不会被截</b>；</li>
     *     <li><b>几何兜底</b>（任意语言 / 任意字体都成立，不靠「文案够短」这一条假设）：
     *     右段先按「半行宽 − 间隙」截断，再<b>右对齐到列表右缘</b>；左段的可用宽度 =
     *     右段实际起点 − {@value #HINT_GAP}px − 左端。于是
     *     左段右界 ≤ 右段左界 − 间隙（<b>不可能重叠</b>），右段右界 = 列表右缘（<b>不可能越出面板</b>），
     *     超长文案（其它语言）最多变成省略号，绝不会溢出或压到另一段上。</li>
     * </ol>
     * <p>整行仍是<b>一行</b>：面板高度（{@value #PANEL_H}）刻意不动，因此既有布局校验
     * （{@code tmp_textures/verify_gui_layout.py} 的 {@code hint} 轨道）与上下两个按钮一个像素都不动。</p>
     */
    private void drawHint(final GuiGraphics guiGraphics) {
        // 提示行的硬边界：左端 = 清空按钮那一列，右端 = 列表右缘（越出它就等于越出面板）
        final int left = px + CLEAR_X;
        final int right = px + LIST_X + LIST_W;
        // 右段：「怎么改」——先截断到「半行宽 − 间隙」，再右对齐（右界恒 = right）
        final int tailMax = Math.max(0, (right - left) / 2 - HINT_GAP);
        final String tailText = trim(Component.translatable(
            autoMode ? LANG + "mode.auto" : LANG + "mode.manual").getString(), tailMax);
        final int tailX = right - font.width(tailText);
        // 左段：状态 —— 可用宽度由右段实际起点决定，尾部补省略号，绝不压到右段上
        final Component head = locked()
            ? Component.translatable(LANG + "auto.locked")
            : Component.translatable(LANG + "summary", selected.size(), categoryOrder.size());
        final String headText = trim(head.getString(), Math.max(0, tailX - HINT_GAP - left));
        guiGraphics.drawString(font, headText, left, py + HINT_Y,
            locked() ? 0xFF3F6FB5 : COLOR_TEXT, false);
        guiGraphics.drawString(font, tailText, tailX, py + HINT_Y, autoMode ? 0xFF3F6FB5 : COLOR_DIM,
            false);
    }

    /** 9×9 三态勾选框：0 = 未选 / 1 = 部分选中 / 2 = 全选。 */
    private static void drawCheckbox(final GuiGraphics guiGraphics, final int x, final int y,
                                     final int state) {
        guiGraphics.fill(x, y, x + ROW_CHECK_SIZE, y + ROW_CHECK_SIZE, COLOR_BORDER);
        final int fill = switch (state) {
            case 2 -> COLOR_CHECK_ON;
            case 1 -> COLOR_CHECK_PARTIAL;
            default -> COLOR_CHECK_OFF;
        };
        guiGraphics.fill(x + 1, y + 1, x + ROW_CHECK_SIZE - 1, y + ROW_CHECK_SIZE - 1, fill);
    }

    /** 按像素宽截断（尾部补省略号）。 */
    private String trim(final String text, final int maxWidth) {
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

    // ==================== tooltip（必须手动渲染） ====================

    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // ⓪ 弹出选择器展开时它压在最上层：只给选择器出 tooltip，下方的列表 / 标签一律让位
        if (pickerOpen) {
            if (inPickerButton(mouseX, mouseY) || inPickerPopup(mouseX, mouseY)) {
                renderTip(guiGraphics, LANG + "tab.pick.tip", mouseX, mouseY);
            }
            return;
        }
        // ① 标签页行优先（它压在列表上方；两者几何不重叠，顺序只为可读性）
        if (inBox(mouseX, mouseY, TABS_X, TABS_Y, TABS_W, TABS_H)) {
            if (inPickerButton(mouseX, mouseY)) {
                renderTip(guiGraphics, LANG + "tab.pick.tip", mouseX, mouseY);
                return;
            }
            if (tabArrowAt(mouseX, mouseY) >= 0) {
                renderTip(guiGraphics, LANG + "tab.scroll.tip", mouseX, mouseY);
                return;
            }
            final String tabKey = tabAt(mouseX, mouseY);
            if (tabKey != null) {
                renderTabTooltip(guiGraphics, tabKey, mouseX, mouseY);
                return;
            }
            renderTip(guiGraphics, LANG + "tab.tip", mouseX, mouseY);
            return;
        }
        final int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            final Line line = lines.get(row);
            switch (line.kind()) {
                case JUMP -> renderTip(guiGraphics, LANG + "jump.tip", mouseX, mouseY);
                case TAB -> renderTip(guiGraphics, LANG + "jump.tab.tip", mouseX, mouseY);
                case HEADER -> {
                    // 自动模式：表头「全选」被禁用（三角折叠仍可用），因此只说明「为什么点不动」
                    if (locked() && !inCollapseTriangle(mouseX)) {
                        renderTip(guiGraphics, LANG + "auto.locked.tip", mouseX, mouseY);
                    } else {
                        renderHeaderTooltip(guiGraphics, line.groupKey(), mouseX, mouseY);
                    }
                }
                default -> {
                    if (line.category() != null) {
                        if (locked()) {
                            renderTip(guiGraphics, LANG + "auto.locked.tip", mouseX, mouseY);
                        } else {
                            renderRowTooltip(guiGraphics, line.category(), mouseX, mouseY);
                        }
                    }
                }
            }
            return;
        }
        if (inBox(mouseX, mouseY, SCROLL_X, LIST_Y, SCROLL_W, LIST_H)) {
            renderTip(guiGraphics, LANG + "list.scroll.tip", mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H)) {
            renderTip(guiGraphics, LANG + "search.tip", mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, CLEAR_X, BAND_Y, CLEAR_W, BAND_H)) {
            renderTip(guiGraphics, locked() ? LANG + "auto.locked.tip" : LANG + "btn.clear.tip",
                mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, FOLD_X, BAND_Y, FOLD_W, BAND_H)) {
            renderTip(guiGraphics, allCollapsed() ? LANG + "btn.unfold.tip" : LANG + "btn.fold.tip",
                mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, CLEAR_X, ACTION_Y, ACTION_W, ACTION_H)) {
            renderTip(guiGraphics, locked() ? LANG + "auto.locked.tip" : LANG + "confirm.tip",
                mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, CANCEL_X, ACTION_Y, ACTION_W, ACTION_H)) {
            renderTip(guiGraphics, LANG + "cancel.tip", mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, CLEAR_X, HINT_Y, LIST_X + LIST_W - CLEAR_X, 10)) {
            final String key = locked() ? "auto.locked.tip"
                : (autoMode ? "mode.auto.tip" : "mode.manual.tip");
            renderTip(guiGraphics, LANG + key, mouseX, mouseY);
        }
    }

    /**
     * 配方标签页的 tooltip（<b>手动渲染</b>，本模组 GUI 不会自动渲染）：第一行是页名（配方可读名），
     * 之后是操作说明，最后在本仓有多套配方时补一行「多套配方各占一页」。
     * <p>为什么不塞进物品 tooltip：标签页不是物品；点它<b>只换页</b>，不改任何勾选。</p>
     */
    private void renderTabTooltip(final GuiGraphics guiGraphics, final String tabKey,
                                  final int mouseX, final int mouseY) {
        // 页名 = 身份信息（常显）；操作说明 = 本模组附加信息（Shift 层，唯一实现 RsccTooltipLayers）。
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(2);
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tab.tip")));
        if (tabOrder.size() > 1) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tab.multi.tip")));
        }
        RsccTooltipLayers.render(guiGraphics, font,
            List.of(Component.literal(tabDisplayName(tabKey))), layered, mouseX, mouseY);
    }

    /**
     * 节表头的 tooltip：节点名 + 「已选 / 全部」+ 全选说明 + 折叠说明
     * （「原料 / 输入时原料」两组另补一行各自的定义 —— 这两个名字最容易被看成同一件事）。
     * <p>「哪一套配方」已经由标签页表达（用户也否掉了「类别 · 配方名」后缀方案），因此这里
     * <b>不再</b>重复配方名、也不再补「多套样板各自成组」那一行 —— 单一配方时同样不多说废话。</p>
     */
    private void renderHeaderTooltip(final GuiGraphics guiGraphics, final String section,
                                     final int mouseX, final int mouseY) {
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(4);
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "group.tip")));
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "group.collapse.tip")));
        // 「原料」与「输入时原料」各自的一句话定义：两组都在的话，玩家一眼能看出谁是谁
        if (GROUP_MATERIALS.equals(section)) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "group.materials.tip")));
        } else if (GROUP_FEEDSTOCK.equals(section)) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "group.feedstock.tip")));
        }
        // 用户第 2 条：本页里有「与其它配方的同类型步骤语义相同」的类别时，补一行说明（手动渲染）
        if (anySharedReuse) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "group.reusable.tip")));
        }
        RsccTooltipLayers.render(guiGraphics, font,
            List.of(Component.literal(headerText(section, sectionMembers(section)))),
            layered, mouseX, mouseY);
    }

    /**
     * 条目行的 tooltip。
     *
     * <p><b>物品行 = 原版物品 tooltip</b>（用户硬要求）：直接渲染
     * {@link ItemStack#getTooltipLines} 的结果 —— 与玩家在背包里看到的<b>逐行一致</b>，
     * 这里<b>不</b>再自研文案、<b>不</b>增删任何一行。因此「第几步 · 什么机器」这类信息
     * 一律由行内后缀 / 表头承担，而不是塞进物品 tooltip。</p>
     * <p>流体行不是物品、没有「背包里的原版 tooltip」可言，因此沿用既有的「名字 + 注册名 + 操作提示」。</p>
     */
    private void renderRowTooltip(final GuiGraphics guiGraphics, final RsccBusCategory category,
                                  final int mouseX, final int mouseY) {
        if (category.isFluidInput()) {
            // 显示名 = 身份信息（常显）；内部类别 id → Alt 层；操作说明 → Shift 层。
            RsccTooltipLayers.render(guiGraphics, font,
                List.of(ExporterExecutorRowWidget.displayName(category)),
                List.of(RsccTooltipLayers.alt(Component.literal(category.id())),
                    RsccTooltipLayers.shift(Component.translatable(LANG + "row.tip"))),
                mouseX, mouseY);
            return;
        }
        final List<Component> tip = vanillaItemTooltip(category);
        if (tip.isEmpty()) {
            renderTip(guiGraphics, LANG + "row.tip", mouseX, mouseY);
            return;
        }
        GhostMarkerRenderer.renderLines(guiGraphics, font, tip, mouseX, mouseY);
    }

    /**
     * 原版物品 tooltip（与背包 / RS 网格同源：{@link ItemStack#getTooltipLines}）。
     *
     * <p><b>取的是「本行此刻显示的那一件」</b>（{@link #displayStackOf}），不是类别的组代表物 ——
     * 用户实测的「显示锌粒、tooltip 铁粒」正是这里原先恒定读 {@code iconItem} 造成的
     * （图标 / 名字早已按候选轮播）。现在三处同源，多候选类别轮播到哪一件，
     * tooltip 就是哪一件（与旁边写着的名字逐字一致）。</p>
     */
    private static List<Component> vanillaItemTooltip(final RsccBusCategory category) {
        final ItemStack stack = displayStackOf(category);
        if (stack.isEmpty()) {
            return List.of();
        }
        final Minecraft minecraft = Minecraft.getInstance();
        return stack.getTooltipLines(Item.TooltipContext.EMPTY, minecraft.player,
            minecraft.options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL);
    }

    /** 判定用矩形（Menu 坐标 → 屏幕坐标；与绘制共用同一批常量，hover 必然同源）。 */
    private boolean inBox(final double mouseX, final double mouseY,
                          final int x, final int y, final int w, final int h) {
        return mouseX >= px + x && mouseX < px + x + w
            && mouseY >= py + y && mouseY < py + y + h;
    }

    private void renderTip(final GuiGraphics guiGraphics, final String key,
                           final int mouseX, final int mouseY) {
        GhostMarkerRenderer.renderLines(guiGraphics, font,
            List.of(Component.translatable(key)), mouseX, mouseY);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // 「全部配方」弹出选择器展开时它压在最上层：先处理它（点行 = 切页，点按钮 / 别处 = 收起）
        if (pickerOpen) {
            if (button == 0) {
                final int pickRow = pickerIndexAt(mouseX, mouseY);
                if (pickRow >= 0) {
                    pickTab(tabOrder.get(pickRow));
                } else {
                    closePicker(); // 点覆盖层之外（含按钮）：只收起，不触发下面任何交互
                }
            } else {
                closePicker();
            }
            return true;
        }
        if (scrollbar != null && scrollbar.visible && scrollbar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            // ⓪ 「全部配方」选择器按钮：展开 / 收起完整配方清单（只改显示）
            if (inPickerButton(mouseX, mouseY)) {
                togglePicker();
                return true;
            }
            // ① 标签页行：点标签 = 切页；点 ◀ / ▶ = 左右翻页（两者都不改任何勾选）
            final int arrow = tabArrowAt(mouseX, mouseY);
            if (arrow == 0) {
                scrollTabs(-1);
                return true;
            }
            if (arrow == 1) {
                scrollTabs(1);
                return true;
            }
            final String tabKey = tabAt(mouseX, mouseY);
            if (tabKey != null) {
                switchTab(tabKey);
                return true;
            }
        }
        final int row = rowAt(mouseX, mouseY);
        if (row >= 0 && button == 0) {
            final Line line = lines.get(row);
            switch (line.kind()) {
                case TAB ->
                    // 搜索结果「切到某一页」：切页（只改显示；勾选与 configSelection 一个都不动）
                    switchTab(line.groupKey());
                case JUMP ->
                    // 搜索结果「跳到某一节」：展开该节并翻到它
                    jumpToSection(line.groupKey());
                case HEADER -> {
                    if (inCollapseTriangle(mouseX)) {
                        // 三角 = 折叠 / 展开本节（只改显示，不碰勾选 —— 自动模式下也允许，它只是「看」）
                        toggleCollapsed(line.groupKey());
                    } else if (!locked()) {
                        // 表头其余部分 = 本节全选 / 全不选（三态：未全选 → 全选）；自动模式下禁用
                        setMembers(line.members(), groupState(line.members()) != 2);
                    }
                }
                default -> {
                    // 条目行 = 勾选 / 取消（自动模式下禁用：勾选由服务端自动接管）
                    if (line.category() != null && !locked()) {
                        toggleMember(line.category().id());
                    }
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(final double mouseX, final double mouseY) {
        if (scrollbar != null && scrollbar.visible) {
            scrollbar.mouseMoved(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * 拖动转发：MC 在「按住鼠标移动」时只派发 {@code mouseDragged}（不派发 {@code mouseMoved}），
     * 而滚动条不是原版子控件 —— 不转发就会出现「能点、能滚轮，但按住拖不动」。
     */
    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        if (scrollbar != null && scrollbar.visible
            && scrollbar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (scrollbar != null && scrollbar.visible
            && scrollbar.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        // 「全部配方」弹出选择器展开时：滚轮只滚它（滚它之外的区域也不去滚下面的列表，避免误操作）
        if (pickerOpen && scrollY != 0) {
            if (inPickerPopup(mouseX, mouseY)) {
                scrollPicker(scrollY > 0 ? -1 : 1);
            }
            return true;
        }
        // 标签页行：横向翻页（配方很多时靠它看全；只改显示）
        if (inBox(mouseX, mouseY, TABS_X, TABS_Y, TABS_W, TABS_H) && scrollY != 0) {
            scrollTabs(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (inBox(mouseX, mouseY, LIST_X, LIST_Y, LIST_W, LIST_H) && scrollY != 0
            && lines.size() > VISIBLE_ROWS) {
            firstVisibleRow = Math.max(0, Math.min(firstVisibleRow + (scrollY > 0 ? -1 : 1),
                lines.size() - VISIBLE_ROWS));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 键盘操作（「全部配方」弹出选择器展开时）：<b>↑ / ↓ 移动光标、回车选页、Esc 收起</b>。
     * <p><b>为什么只接管这几个键</b>：搜索框仍然持有焦点，字母 / 数字照旧直接进搜索框（不被打断）；
     * 选择器只在展开期间接管方向键与回车 / Esc，收起后键盘行为与原来完全一致。</p>
     */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (pickerOpen) {
            switch (keyCode) {
                case 256 -> { // Esc
                    closePicker();
                    return true;
                }
                case 265 -> { // ↑
                    movePickerCursor(-1);
                    return true;
                }
                case 264 -> { // ↓
                    movePickerCursor(1);
                    return true;
                }
                case 257, 335 -> { // 回车 / 小键盘回车
                    if (pickerCursor >= 0 && pickerCursor < tabOrder.size()) {
                        pickTab(tabOrder.get(pickerCursor));
                    } else {
                        closePicker();
                    }
                    return true;
                }
                default -> {
                    return super.keyPressed(keyCode, scanCode, modifiers);
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
