package cretae.cookiewyq.rs_create_compat.client.widget;

import java.util.ArrayList;
import java.util.List;

import cretae.cookiewyq.rs_create_compat.client.screen.BusCategoryConfigScreen;
import cretae.cookiewyq.rs_create_compat.network.SetExporterExecutorCategoriesPacket;
import cretae.cookiewyq.rs_create_compat.network.SetExporterForceNormalPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncExporterExecutorModePacket;
import cretae.cookiewyq.rs_create_compat.client.BusInterferenceOverlay;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 总线上「盖住 9 格过滤器槽位」的类别条控件（自绘，延长型输出 / 延长型输入两种界面共用）。
 *
 * <p><b>本轮布局：条上只读展示 + 一个「详细配置…」入口（用户要求）</b>：</p>
 * <pre>
 *   ┌────────────────────────────────────────────────────────────┐  ← 类别条（盖住 9 个过滤器槽）
 *   │ 类别单元格（20×23，间距 2px，一行最多 7 格，图标 16×16）  │
 *   └────────────────────────────────────────────────────────────┘
 *     ◀   ▶   3/5          [自动/手动]   [ 详细配置… ]
 *        （条下方控制带：Menu 40..53，原版此处只有「Inventory」留白）
 * </pre>
 *
 * <p><b>为什么条上不再能勾选（用户原话）</b>：「现在你这个输出总线一个一个物品地翻页其实还是不太好」
 * —— 逐格点击在 20px 的格子里既难瞄又容易误触。因此条上改成<b>只读展示</b>（已选 = 绿底 + 勾选框、
 * 未选 = 灰底；中间产物再带一个橙色「第几步」角标），并把<b>全部勾选动作挪进子界面</b>
 * {@link BusCategoryConfigScreen}（搜索 + 分组 + 一类整选），由「详细配置…」按钮打开。
 * 条上只保留三类安全交互：<b>◀ ▶ / 滚轮翻看</b>、<b>自动 ⇄ 手动</b>（只有输入总线有）、<b>打开子界面</b>。</p>
 *
 * <ul>
 *     <li><b>单元格</b>恒为 {@value #CELL}×{@value #CELL_H}（间距 {@value #PITCH}），一行最多完整显示
 *     {@value #VISIBLE_MAX} 个（{@code 7 × 22 - 2 = 152 ≤ 162} 的条内宽），类别再多靠
 *     <b>◀ ▶ 翻页 + 鼠标滚轮</b>翻看，任何地方都没有「只取前 N 个」的截断；</li>
 *     <li><b>翻页按钮</b>在条下方的控制带里，它们<b>常驻显示</b>：只有一页时呈灰色禁用态
 *     （一眼可辨「现在没得翻」），有得翻时才可点；</li>
 *     <li><b>自动 / 手动开关</b>（只有输入总线提供，见 {@link Source#hasAutoToggle()}）：
 *     自动模式下类别条整条加蓝色内框 + 格内蓝色「自动」标记，并在 tooltip 里<b>列出正在回收的类别</b>；</li>
 *     <li><b>「详细配置…」按钮</b>：控制带最右端、48×14（本界面上唯一能改勾选的入口），
 *     与左侧控件保持 3px 缝、不侵入右侧插件槽列；停用态整条控制带让位给警示条，因此按钮不存在。</li>
 * </ul>
 *
 * <p><b>「已选类别摘要」在哪看</b>：格子只有 20px 宽，放不下名字。因此条上给「图标 + 绿/灰状态」，
 * 悬停任意位置（格子 / 条空白 / 翻页按钮）的 tooltip 都会把<b>已选集完整列出来</b>（不受翻页限制），
 * 子界面则给出可搜索、可分组的完整清单 —— 三处看到的是同一份服务端权威数据。</p>
 *
 * <p><b>自动合成门控</b>：执行舱的「自动合成」未开启（所在网络没有进行中的自动合成任务）时，
 * 服务端不会下发给本总线任何导出过滤项，界面在类别条内圈加一道暗红描边并在 tooltip 里说明
 * —— 让「界面在，但当前不导出」一眼可见。</p>
 *
 * <p><b>为什么做成控件 + 为什么点击要另接一路</b>：本模组曾用 {@code @Inject(method = "mouseClicked")}
 * 直接注入 RS 的父类 {@code AbstractBaseScreen}，但目标类 {@code ExporterScreen} 自身并不声明
 * {@code mouseClicked}，于是打开界面即抛 {@code InvalidInjectionException} 崩客户端。
 * 改成自绘控件后，<b>绘制</b>由容器界面的标准流程自动接管；但<b>点击</b>只靠
 * {@code Screen#mouseClicked → children} 还不够 —— 类别条盖着的 9 格全是「过滤器资源槽」，
 * {@code AbstractBaseScreen#mouseClicked} 会在最前面把这类点击抢走并直接返回。因此再由
 * {@code AbstractBaseScreenMixin} 先把点击交给本控件：命中类别条或它的控制带就取消原版逻辑，
 * 非延长模式 / 点在两者之外一律放行。<b>滚轮</b>没有这个竞争，因此走
 * {@link AbstractWidget#mouseScrolled} 的标准分发。</p>
 *
 * <p><b>为什么要补绘</b>：容器的槽位（连同悬停高亮）是在「控件渲染之后」的槽位循环里绘制的，
 * 会盖在类别条上；因此 {@link #rscc$renderTooltip} 会补绘一次类别条（含控制带）。</p>
 *
 * <p><b>非延长模式</b>：{@link #renderWidget} 直接返回（一帧不画）、{@link #mouseClicked} 返回
 * {@code false}（一点不拦）、{@link #rscc$renderTooltip} 返回 {@code false}，界面完全等同 RS 原版。</p>
 *
 * <p><b>渲染硬规则</b>：文字一律 {@code dropShadow = false}；文字 / 箭头 / 色块全部由 Java 运行时绘制
 * （不新增任何贴图）；绘制范围与 hover / 点击判定共用同一批几何常量与同一个
 * {@link #hitCell(double, double)}，必然同源；同一帧只有一份 tooltip。</p>
 */
public class ExporterExecutorRowWidget extends AbstractWidget {
    private static final String LANG = "gui.rs_create_compat.exporter_executor.";
    /**
     * 与两个宿主语义无关的共用语言键前缀（翻页控件两个界面都用，因此不能挂在任一宿主的前缀上）。
     * 与「延长型已停用」提示的 {@link #WARN_LANG} 同一做法。
     */
    private static final String PAGE_LANG = "gui.rs_create_compat.bus_bar.";

    /**
     * 类别条的数据来源：以「同一个控件 + 可替换数据源」实现复用 ——
     * 绘制 / 命中 / 翻页逻辑只有一份，来源由 {@link Source} 注入。
     */
    public interface Source {
        /** 本界面此刻是否应显示类别条（false = 一帧不画、一点不拦）。 */
        boolean visible();

        /**
         * 当前类别快照（<b>全量候选</b>，有序）。
         * <p><b>本界面条上并不直接画它</b>（H 组：条上只列已勾选，见 {@code visibleCategories()}）；
         * 全量只用于「详细配置」子界面的编辑列表与它打开时的编辑起点。</p>
         */
        List<RsccBusCategory> categories();

        /** 归属执行舱的「自动合成」是否已开启（仅用于状态提示）。 */
        boolean autoCraftingEnabled();

        /** 本总线当前归属的执行舱显示名（空串 = 未绑定）。 */
        String linkedChamber();

        /** 是否处于«直到目标产物达标»供应策略（为真时 tooltip 显示预估需求）。 */
        boolean targetStrategy();

        /** 提交新的勾选集合（全量；服务端权威）。 */
        void toggleSelection(List<String> selectedCategoryIds);

        /**
         * 子界面（详细配置）打开时要显示的「玩家显式选择」集合（有序；默认 = 当前快照里已选的类别 id）。
         *
         * <p><b>为什么需要单独一路</b>：类别快照里的 {@code selected} 是<b>展示态</b> ——
         * 输入总线在全自动模式下把「非输入类」标成已选、输出总线在「没显式选过」时把输入类标成已选，
         * 两者都不等于玩家存下来的那份勾选表。子界面编辑的是<b>玩家存下来的那份</b>（提交后由服务端写入），
         * 若拿展示态当初始值，在自动模式 / 默认态下点一次「确定」就会把玩家的原勾选悄悄覆盖掉。
         * 因此这里默认给出展示态（对输出总线与手动模式恰好等价），输入总线在自动模式下覆写为权威勾选表。</p>
         */
        default List<String> configSelection() {
            final List<String> ids = new ArrayList<>();
            for (final RsccBusCategory category : categories()) {
                if (category.selected()) {
                    ids.add(category.id());
                }
            }
            return ids;
        }

        /** 本总线此刻是否处于「全自动收回」（只有输入总线会返回 {@code true}）。 */
        default boolean autoCollect() {
            return false;
        }

        /** 类别条是否提供「自动 / 手动」开关（只有输入总线提供；输出总线没有这个概念）。 */
        default boolean hasAutoToggle() {
            return false;
        }

        /** 提交「自动 / 手动」开关（服务端权威；与勾选表互不覆盖）。 */
        default void toggleAuto(final boolean auto) {
        }

        /**
         * tooltip 文案的语言键前缀。
         * <p><b>为什么做成可替换</b>：本控件被输出总线与输入总线<b>两处复用</b>，而两条总线的语义是
         * 相反的（一个是「导出给机器」，一个是「收回网络」）。前缀由数据源注入后，绘制 / 命中 / 翻页
         * 逻辑仍然只有一份，文案却各说各的话；默认值就是输出总线自己的前缀，因此既有界面零改动。</p>
         */
        default String langPrefix() {
            return LANG;
        }

        /** 类别条自身的标题（未指向具体格子时的 tooltip 首行）。 */
        default String barTitle() {
            return LANG + "locked";
        }

        /** 类别条标题下的说明行。 */
        default String barTitleTip() {
            return LANG + "locked.tip";
        }

        /** 类别条内「一个类别都没有」时的提示语言键。 */
        default String emptyTextKey() {
            return LANG + "no_categories";
        }

        /**
         * 类别条内「有候选类别、但玩家一个都没勾」时的提示语言键（H 组：条上<b>只显示已勾选</b>的）。
         *
         * <p><b>为什么与 {@link #emptyTextKey()} 分开</b>：两者的处置完全不同 —— 「本来就没有候选」
         * 玩家无处可点；「有候选但没勾」玩家点「详细配置…」就能勾上。混成一句话会让玩家以为这台机器
         * 没东西可选，而不是「自己还没选」。</p>
         */
        default String selectedEmptyTextKey() {
            return LANG + "no_selected_categories";
        }

        /**
         * 空条提示的<b>第二行</b>（「去哪改」）：条上有候选、但玩家一个都没勾时的语言键。
         *
         * <p><b>为什么与 {@link #selectedEmptyTextKey()} 分成两行两个键</b>：条内可用带只有约 128px
         * （条内层 162px 减去右端那颗固定几何的「普通」按钮与 2px 缝），而「状态 + 去哪改」一整句
         * 中文约 190px、英文更宽 —— 挤在一行里只能靠截断，截出来的残句读不通；分成两行后每行都塞得下
         * （见 {@code drawEmptyHint} 的几何说明），且两行垂直区间不相交、永不重叠。</p>
         */
        default String selectedEmptyActionTextKey() {
            return LANG + "no_selected_action";
        }

        // ---------- 「归属未确定 → 延长型已停用」提示（默认「没有」，既有宿主零改动） ----------

        /**
         * 本总线此刻是否因<b>归属未确定</b>而停用（服务端权威判定，见 {@code RsccBusInterference}）。
         * <p>为真时类别条下方会亮出警示条 + 一个「显示 / 隐藏可达区域」按钮（此时控制带整条让位，
         * 翻页 / 开关都不画也不可点），且类别条表现为<b>不可操作</b>（点格子 / 切开关都不生效）；
         * 为假时那一带一个像素都不画。</p>
         */
        default boolean disabled() {
            return false;
        }

        /** 本总线线缆可达几台执行舱（提示文案里的 N；服务端权威）。 */
        default int disabledReachableCount() {
            return 0;
        }

        /** 可达执行舱坐标（供提示文案与半透明叠加层标红）。 */
        default List<net.minecraft.core.BlockPos> disabledChambers() {
            return List.of();
        }

        /** 探查未穷尽（可达执行舱可能不全，提示文案需单独说明）。 */
        default boolean disabledTruncated() {
            return false;
        }

        /** 半透明叠加层此刻是否显示（按钮据此显示「隐藏 / 显示」）。 */
        default boolean overlayShown() {
            return false;
        }

        /** 按钮：切换「显示可达区域」的半透明叠加层。 */
        default void toggleOverlay() {
        }

        /**
         * 本总线此刻是否被玩家<b>强制为普通总线</b>（用户原话：「赶紧做我要的按钮」）。
         * <p>为真时本界面<b>不再是</b>延长型界面（类别条整条不画、过滤器槽可正常编辑），
         * 并在条下方的留白带上画出「恢复为总线界面」按钮；未强制普通、但可转换时，
         * 同一处画的是「普通」按钮 —— <b>两者共用同一颗按钮</b>（用户第 2 条：按钮始终保留、可来回切换）。
         * 普通界面里<b>不再</b>显示「延长型已停用」那类说明（用户第 2 条），那套说明只在类别条界面里出现。</p>
         */
        default boolean forceNormal() {
            return false;
        }

        /** 提交「强制普通总线」开关（服务端权威；false = 恢复成自动判定）。 */
        default void toggleForceNormal(final boolean force) {
        }

        /** 「强制普通 / 恢复为总线界面」按钮的语言键前缀（两种宿主共用）。 */
        default String forceNormalLang() {
            return FORCE_LANG;
        }

        /**
         * 本总线是否<b>可转换</b>（线缆真的够得到处于「总线输出」的执行仓）。
         * <p><b>用户第 2 条</b>：「这种可以被转换成延长输入输出总线的输入输出总线旁边<b>都必须</b>有一个
         * 普通 / 总线的切换按钮」—— 为真时那颗按钮<b>默认常驻</b>（无论界面当前用的是类别条还是普通界面）；
         * 为假（纯普通总线）时一个像素都不画。默认实现退回 {@link #visible()}，保证既有宿主行为不变。</p>
         */
        default boolean convertible() {
            return visible();
        }

        /** 某个类别的「类型说明」语言键（输入 = 导出说明；过渡件 = 中间件说明）。 */
        default String typeTipKey(final RsccBusCategory category) {
            if (category.isFluidInput()) {
                return LANG + "fluids.tip";
            }
            return LANG + (category.isInput() ? "inputs.tip" : "intermediates.tip");
        }
    }

    /** 单元格尺寸与间距（固定，不再随类别数压缩）。 */
    private static final int CELL = 20;    /**
     * 单元格高度：上边距 1 + 图标 16 + 「勾选框 / 共享角标」行 5 + 下边距 1 = 23。
     * <p>加高是为了满足「勾选框与共享角标不要压到图标」：图标独占顶部 16px，两个标记统一放到底部那一行。</p>
     */
    private static final int CELL_H = 23;
    private static final int PITCH = 22;
    /**
     * 一条最多完整显示的单元格数。
     * <p><b>本轮由 7 降为 6</b>：条内右端腾出的 30px 给了「强制普通总线」按钮
     * （{@link #FORCE_X}，用户原话：「赶紧做我要的按钮」）。
     * {@code 6 × 22 - 2 = 130}，单元格占 Menu x 8..138，按钮占 140..170，
     * 两者之间留 2px 缝、且都在条内层（Menu 8..170）之内；类别再多靠 ◀ ▶ 翻页 + 滚轮翻看。</p>
     */
    private static final int VISIBLE_MAX = 6;

    // ---------- 条下方控制带（局部 y 24 → Menu y 40；高 14 → Menu y 40..53） ----------
    /**
     * 控制带的垂直基准。
     * <p><b>为什么放在条下方</b>：类别条自身（Menu y 16..39）已被 {@value #VISIBLE_MAX} 个加大后的格子
     * 占满，而 Menu y 40..53 是原版界面里唯一的留白带（过滤器槽到 y 38 为止，玩家背包槽自 y 55 起，
     * 中间只有一行「Inventory」文字），既不压槽位也不越面板。停用警示条用的也是这一带 ——
     * 两者互斥：停用时控制带整条不画。</p>
     * <p><b>本轮控件变多（用户要求加「详细配置…」按钮）</b>：控制带可用宽度恒为 162px
     * （Menu x 8..170，右侧 x≥187 是 RS 的插件槽列，绝不能侵入），因此把四个控件一起收窄重排，
     * 每个控件之间保留 3px 缝（不贴在一起），总宽 159px 仍有余量：
     * <pre>
     *   ◀ 15px | 3 | ▶ 15px | 3 | 页码 30px | 3 | 自动/手动 39px | 3 | 详细配置… 48px
     *   Menu: 8..22  26..40      44..73       77..115        119..166
     * </pre></p>
     */
    private static final int BAND_Y = 24;
    private static final int BAND_H = 14;
    /** 翻页按钮宽（15×14：比原来那条 2px 滚动条好按得多，且给「详细配置…」腾出位置）。 */
    private static final int BTN_W = 15;
    private static final int BTN_H = BAND_H;
    private static final int PREV_X = 0;
    /** 下一个按钮：局部 (18,24) → Menu (26,40)；与上一个按钮留 3px 缝，不贴在一起。 */
    private static final int NEXT_X = PREV_X + BTN_W + 3;
    /** 页码文本区：局部 (36,27) 30×8 → Menu (44,43)（不可点，仅显示「n/m」）。 */
    private static final int PAGE_TEXT_X = NEXT_X + BTN_W + 3;
    private static final int PAGE_TEXT_W = 30;
    private static final int PAGE_TEXT_Y = BAND_Y + 3;
    /** 自动 / 手动开关：局部 (69,24) 39×14 → Menu (77,40)（只有输入总线提供开关）。 */
    private static final int TOGGLE_X = PAGE_TEXT_X + PAGE_TEXT_W + 3;
    private static final int TOGGLE_Y = BAND_Y;
    private static final int TOGGLE_W = 39;
    private static final int TOGGLE_H = BAND_H;
    /**
     * 「详细配置…」按钮：局部 (111,24) 48×14 → Menu (119,40)..(166,53)。
     * <p><b>它是本界面上唯一能改「收回 / 导出哪些类别」的入口</b>（用户要求：条上只读展示，
     * 勾选动作统一挪进子界面）。因此它被刻意放大到 48×14 并独占控制带右端，与左侧控件留 3px 缝；
     * 标签在中文下是「详细配置…」（45px）、英文是「Details…」（37px），两种语言都留有余量不压边框。</p>
     */
    private static final int CONFIG_X = TOGGLE_X + TOGGLE_W + 3;
    private static final int CONFIG_Y = BAND_Y;
    private static final int CONFIG_W = 48;
    private static final int CONFIG_H = BAND_H;

    /** 类别条配色（与 RS 面板的灰阶同族，避免「一块突兀的黑板」）。 */
    private static final int COLOR_BAR_BG = 0xFFC6C6C6;
    private static final int COLOR_BAR_BORDER = 0xFF2B2B2B;
    private static final int COLOR_BTN_OFF = 0xFF9E9E9E;
    private static final int COLOR_BTN_OFF_HOVER = 0xFFB8B8B8;
    private static final int COLOR_BTN_ON = 0xFF4CAF50;
    private static final int COLOR_BTN_HOVER = 0xFF6FBF73;
    /** 禁用态按钮底色（比「未选」更浅，一眼看出「现在按不动」）。 */
    private static final int COLOR_BTN_DISABLED = 0xFFDCDCDC;
    /** 「详细配置…」按钮：比翻页按钮更深的灰（一眼看出它是「主入口」），悬停再亮一档。 */
    private static final int COLOR_BTN_CONFIG = 0xFF6E6E6E;
    private static final int COLOR_BTN_CONFIG_HOVER = 0xFF8A8A8A;
    /** 共享（多台均分）的角标色：青色，与「绿 = 已选 / 灰 = 未选」不冲突。 */
    private static final int COLOR_SHARED_MARK = 0xFF29B6F6;
    /** 勾选框：未勾选 = 浅灰内胆 / 已勾选 = 深绿内胆（外框恒为深色描边）。 */
    private static final int COLOR_CHECKBOX_OFF = 0xFFE0E0E0;
    private static final int COLOR_CHECKBOX_ON = 0xFF2E7D32;
    private static final int COLOR_TEXT = 0xFF1F1F1F;
    /** 灰一档的文字（禁用态页码 / 箭头）。 */
    private static final int COLOR_TEXT_DIM = 0xFF8A8A8A;
    /** 「自动收回中」的开关底色：蓝灰，与「绿 = 已选」区分开。 */
    private static final int COLOR_TOGGLE_AUTO = 0xFF3F6FB5;
    private static final int COLOR_TOGGLE_AUTO_HOVER = 0xFF5A8AC9;
    /** 自动模式下格内的「自动」标记（与开关同色，表达「这一格由自动模式决定」）。 */
    private static final int COLOR_AUTO_MARK = 0xFF3F6FB5;
    /** 「自动合成未开启」时类别条内圈的暗红描边（不做禁用态，只做状态提示）。 */
    private static final int COLOR_GATE_OFF = 0xFFB00020;

    // ---------- 「归属未确定 → 延长型已停用」的警示条：控制带所在的同一条留白带 ----------
    /**
     * 警示条 / 按钮的局部坐标 = Menu 坐标 − (8, 16)（本控件的原点）。
     * <p>与 {@link #BAND_Y} 同一条带：<b>停用时控制带整条不画</b>，因此两者永不重叠。
     * 警示条 Menu (8, 42) 99×12，按钮 Menu (108, 42) 62×12（与自动开关同一列对齐）。</p>
     * <p>代价（刻意的）：停用时警示条会盖住 RS 自己画的「Inventory」行标签 —— 该标签在本界面上
     * 并无信息量（背包就在下面），而「延长型已停用」是必须一眼看见的状态；不停用时一个像素都不画。</p>
     */
    private static final int WARN_Y = 26;
    private static final int WARN_H = 12;
    private static final int WARN_BANNER_X = 0;
    private static final int WARN_BANNER_W = 99;
    private static final int WARN_BTN_X = 100;
    private static final int WARN_BTN_W = 62;
    /** 警示条配色：暗红底 + 极浅文字（与既有的「门控关闭」内圈描边同色系）。 */
    private static final int COLOR_WARN_BG = 0xFFB00020;
    private static final int COLOR_WARN_BORDER = 0xFF6B0013;
    private static final int COLOR_WARN_TEXT = 0xFFFFF3E0;
    /** 按钮配色：未按下 = 灰底（点击即打开叠加层），已按下 = 绿底（点击即关闭）。 */
    private static final int COLOR_WARN_BTN_OFF = 0xFF9E9E9E;
    private static final int COLOR_WARN_BTN_ON = 0xFF4CAF50;
    /** 停用提示的语言键前缀（两种宿主共用，与各自的类别条前缀无关）。 */
    private static final String WARN_LANG = "gui.rs_create_compat.bus_interference.";
    /**
     * 「详细配置」子界面的语言键前缀（两种宿主、主界面按钮与子界面本身共用同一份文案）。
     * <p>刻意公开：子界面 {@code BusCategoryConfigScreen} 直接引用它，避免两处各写一份前缀字符串
     * 而在改名时漏掉一处。</p>
     */
    public static final String CONFIG_LANG = "gui.rs_create_compat.bus_config.";

    /**
     * 「强制普通总线 / 恢复为总线界面」按钮的语言键前缀（两种宿主共用；用户原话：「赶紧做我要的按钮」）。
     */
    public static final String FORCE_LANG = "gui.rs_create_compat.bus_force_normal.";

    // ---------- 「强制普通总线」按钮（本轮新增） ----------
    /**
     * 条右侧的「普通」按钮（局部坐标 = Menu 坐标 − (8,16)）：Menu (140,16) 30×23。
     * <p><b>为什么放条右侧</b>：类别条一行 6 个单元格占 Menu x 8..138（{@link #VISIBLE_MAX}），
     * 条内右端 Menu x 140..170 恰好还剩 30px —— 把它做成第 7 个「单元格」的大小，
     * 与格子同高同排、既不压格子也不出条（条内层右缘 Menu 171）。
     * 单元格数因此由 7 降为 6（多出来的那一格换成这个按钮），翻页 / 滚轮照旧。</p>
     */
    private static final int FORCE_X = 132;
    private static final int FORCE_Y = 0;
    private static final int FORCE_W = 30;
    private static final int FORCE_H = 23;

    // ---------- 空条提示（条上一个类别都没有时的文字） ----------
    /**
     * 空条提示两行的垂直位置（局部坐标）与行高。
     * <p>行高 9px（字体高度），两行分别落在 2..11 与 12..21：<b>垂直区间不相交</b>，
     * 因此两行几何上永不重叠（与文字多宽无关）；两行也都在 23px 的条内层里（上下各留 2px）。</p>
     * <p><b>为什么敢用两行</b>：提示只在「空条」这一帧画 —— 此时条内一个单元格都没有，
     * 这两行不与任何图标 / 勾选框抢位置，面板高度 / 按钮 Y / 控制带几何一个都不用动。</p>
     */
    private static final int HINT_LINE_H = 9;
    private static final int HINT_LINE1_Y = 2;
    private static final int HINT_LINE2_Y = 12;
    /**
     * 提示行可用带的左右留缝（局部 px）。
     * <p>可用带 = 条内层左缘 + {@value #HINT_INSET} … <b>固定段</b>（条内右端那颗 30×23 的「普通」按钮）
     * 左缘 − {@value #HINT_GAP}。固定段的几何是死的（{@link #FORCE_X} / {@link #FORCE_W}），
     * 提示文字的可用宽度因此由它推出，而不是反过来。</p>
     */
    private static final int HINT_INSET = 2;
    private static final int HINT_GAP = 2;
    /** 截断时补在尾部的省略号（与「类别详细配置」子界面的 {@code trim} 同一做法）。 */
    private static final String HINT_ELLIPSIS = "...";

    /**
     * <b>普通界面（非延长型）下常驻的那颗「普通 / 总线」切换按钮</b>：局部 (62,24) 100×14
     * → Menu (70,40)..(170,54)，也就是「条下方那条留白带」的<b>右端</b>（面板的固定角落）。
     *
     * <h2>为什么换到这个位置（用户第 3 次提出「你换个位置」）</h2>
     * <p>延长界面里条右侧那块（{@link #FORCE_X} = 局部 (132,0) 30×23 → Menu (140,16)）在
     * <b>普通界面</b>里正压在<b>第 8、9 个过滤器槽</b>上 —— 而普通界面里过滤器槽是<b>可编辑</b>的，
     * 按钮放在那儿既挡住槽位、又会在玩家点槽位时误触发切换（这才是用户说的「冲突」）。
     * 换到条下方留白带的右端后：<b>不压任何槽位</b>（槽位带到 Menu y38 为止，玩家背包自 y55 起）、
     * 不与控制带同帧出现（普通界面里那个控件不存在），且是面板的固定角落。</p>
     */
    private static final int RESTORE_X = 62;
    private static final int RESTORE_Y = BAND_Y;
    private static final int RESTORE_W = 100;
    private static final int RESTORE_H = BAND_H;
    /** 「普通 / 总线」切换按钮的配色（比翻页按钮更深的灰，一眼看出它是可点的主入口）。 */
    private static final int COLOR_BTN_RESTORE = 0xFF6E6E6E;
    private static final int COLOR_BTN_RESTORE_HOVER = 0xFF8A8A8A;

    /** 数据来源（见 {@link Source}）。 */
    private final Source source;

    /**
     * 当前横向翻到的第一个类别下标（单位 = 单元格数）。
     * <p>{@link #clampScroll} 每次使用前夹紧，因此「类别数变小」也不会越界；
     * ◀ ▶ 一次翻一页（{@value #VISIBLE_MAX} 格），滚轮一次翻一格。</p>
     */
    private int scroll;

    /** 输出总线（RS 原生界面）用：按容器 id 读取 S2C 同步值并回传「导出类别」包。 */
    public ExporterExecutorRowWidget(final int x, final int y, final int width, final int height,
                                     final int containerId) {
        this(x, y, width, height, new Source() {
            @Override
            public boolean visible() {
                return SyncExporterExecutorModePacket.isExecutorMode(containerId);
            }

            @Override
            public List<RsccBusCategory> categories() {
                return SyncExporterExecutorModePacket.getCategories(containerId);
            }

            @Override
            public boolean autoCraftingEnabled() {
                return SyncExporterExecutorModePacket.isAutoCrafting(containerId);
            }

            @Override
            public String linkedChamber() {
                return SyncExporterExecutorModePacket.getLinkedChamber(containerId);
            }

            @Override
            public boolean targetStrategy() {
                return SyncExporterExecutorModePacket.isTargetStrategy(containerId);
            }

            @Override
            public void toggleSelection(final List<String> selectedCategoryIds) {
                PacketDistributor.sendToServer(
                    new SetExporterExecutorCategoriesPacket(containerId, selectedCategoryIds));
            }

            // ---- 「归属未确定 → 已停用」提示：数据来自服务端的 SyncBusInterferencePacket 镜像 ----

            @Override
            public boolean disabled() {
                return BusInterferenceOverlay.isDisabled(containerId);
            }

            @Override
            public int disabledReachableCount() {
                return BusInterferenceOverlay.reachableCount(containerId);
            }

            @Override
            public List<net.minecraft.core.BlockPos> disabledChambers() {
                return BusInterferenceOverlay.chambers(containerId);
            }

            @Override
            public boolean disabledTruncated() {
                return BusInterferenceOverlay.isTruncated(containerId);
            }

            @Override
            public boolean overlayShown() {
                return BusInterferenceOverlay.isShown(containerId);
            }

            @Override
            public void toggleOverlay() {
                BusInterferenceOverlay.toggle(containerId);
            }

            // ---- 「强制普通总线」开关（本轮新增；服务端权威） ----

            @Override
            public boolean convertible() {
                // 用户第 2 条：只要线缆真的够得到总线输出执行仓，就常驻「普通 / 总线」按钮。
                return SyncExporterExecutorModePacket.isConvertible(containerId);
            }

            @Override
            public boolean forceNormal() {
                return SyncExporterExecutorModePacket.isForceNormalBus(containerId);
            }

            @Override
            public void toggleForceNormal(final boolean force) {
                PacketDistributor.sendToServer(new SetExporterForceNormalPacket(containerId, force));
            }
        });
    }

    /** 通用构造：其它界面可传入自己的数据源（绘制 / 命中 / 翻页逻辑完全共用）。 */
    public ExporterExecutorRowWidget(final int x, final int y, final int width, final int height,
                                     final Source source) {
        super(x, y, width, height, Component.translatable(LANG + "locked"));
        this.source = source;
    }

    // ==================== 状态（与绘制 / 命中同源） ====================

    /** 本界面此刻是否应显示类别条。 */
    private boolean executorMode() {
        return source.visible();
    }

    /** 当前类别<b>全量快照</b>（有序；服务端下发的全部候选类别 —— 详细配置子界面用它）。 */
    private List<RsccBusCategory> categories() {
        return source.categories();
    }

    /**
     * 类别条上<b>实际显示</b>的类别 = <b>只列已启用（已勾选）的那些</b>（用户 H 组原话：
     * 「更改一下这个输出总线界面：只显示……就直接打开这个界面，并不是那个详细配置界面，
     * 就是只显示已经勾选了的界面。就是那种勾选了要输出的才显示」）。
     *
     * <p><b>为什么不做成两份数据源</b>：全量候选与已选集本来就是同一份快照上的
     * {@link RsccBusCategory#selected()} 投影，因此这里只做一次过滤，
     * 绘制 / 命中 / 翻页 / tooltip 全部走这一条路径 —— 不会有第二套口径。
     * 全自动收回模式下服务端把「非输入类」标成已选，因此它自动等价于「正在收回哪些」。</p>
     *
     * <p><b>切到完整列表的入口</b>：<b>条下方的「详细配置…」按钮</b>（{@link #openConfigScreen()}，
     * 绘制见 {@link #drawConfigButton}）。它传给子界面的仍是<b>全量快照</b> {@link #categories()}，
     * 因此「看全部候选（含未勾选的）并勾选 / 取消」这条路始终存在、且位置一个像素都没动
     * （它本来就是控制带上最显眼的主入口）。为了让这条切换<b>可发现</b>，条本身的 tooltip 与
     * 该按钮的 tooltip 都写了「只显示已勾选 / 到这里看全部」这一句（见 {@code filtered.tip}）。</p>
     *
     * <p><b>条上真的空了时</b>：有候选但一个都没勾 ⇒ 空条 + {@link Source#selectedEmptyTextKey()}
     * （「还没有勾选任何类别」），与「根本没有候选」的 {@link Source#emptyTextKey()} 分开说。</p>
     */
    private List<RsccBusCategory> visibleCategories() {
        final List<RsccBusCategory> all = categories();
        final List<RsccBusCategory> shown = new ArrayList<>(all.size());
        for (final RsccBusCategory category : all) {
            if (category.selected()) {
                shown.add(category);
            }
        }
        return shown;
    }

    /** 执行舱的「自动合成」是否已开启（服务端权威值；未同步时按「未开启」处理，界面只做提示）。 */
    private boolean autoCraftingEnabled() {
        return source.autoCraftingEnabled();
    }

    /**
     * 本总线是否处于全自动收回模式（真 = 类别条只读展示 + 格子禁用 + 列出正在回收的类别）。
     * <p>输出总线没有这个概念（{@link Source#hasAutoToggle()} 为假），因此它恒为 {@code false}，
     * 界面与改造前逐像素一致。</p>
     */
    private boolean autoCollect() {
        return source.hasAutoToggle() && source.autoCollect();
    }

    /** 本总线当前归属的执行舱显示名（未同步 / 未绑定时为空串）。 */
    private String linkedChamber() {
        return source.linkedChamber();
    }

    /** 当前宿主界面的语言键前缀（见 {@link Source#langPrefix()}；输出总线 / 输入总线各说各的话）。 */
    private String lang() {
        return source.langPrefix();
    }

    /** 供应策略是否为«直到目标产物达标»（为真时显示预估需求 + 概率提示）。 */
    private boolean targetStrategy() {
        return source.targetStrategy();
    }

    /** 把翻页偏移夹紧到合法区间（类别数变化后可能越界）。 */
    private static int clampScroll(final int scroll, final int count) {
        return Math.max(0, Math.min(scroll, Math.max(0, count - VISIBLE_MAX)));
    }

    /** 类别数 &gt; {@value #VISIBLE_MAX} 时才有得翻。 */
    private static boolean needsScroll(final int count) {
        return count > VISIBLE_MAX;
    }

    /** 当前页码（1-based；翻页偏移可能不是整页，因此用整除）。 */
    private static int pageOf(final int scrolled) {
        return scrolled / VISIBLE_MAX + 1;
    }

    /** 总页数（至少 1 页）。 */
    private static int pageCount(final int count) {
        return Math.max(1, (count + VISIBLE_MAX - 1) / VISIBLE_MAX);
    }

    /** 第 0 个单元格的左边界：单元格区固定靠左（条内右侧不再有搜索栏，因此不需要居中）。 */
    private int startX() {
        return getX();
    }

    /** 第 {@code index} 个单元格的左边界（与绘制 / hover 判定共用，保证同源）。 */
    private int cellX(final int index, final int scrolled) {
        return startX() + (index - scrolled) * PITCH;
    }

    /** 鼠标是否落在类别条范围内（左闭右开；与绘制范围完全一致）。 */
    private boolean contains(final double mouseX, final double mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth()
            && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    /** 鼠标是否压在上一个（◀）按钮上（停用时控制带不存在，因此直接不可命中）。 */
    private boolean inPrev(final double mouseX, final double mouseY) {
        return !disabled() && containsIn(mouseX, mouseY, PREV_X, BAND_Y, BTN_W, BTN_H);
    }

    /** 鼠标是否压在上一个（▶）按钮上。 */
    private boolean inNext(final double mouseX, final double mouseY) {
        return !disabled() && containsIn(mouseX, mouseY, NEXT_X, BAND_Y, BTN_W, BTN_H);
    }

    /** 鼠标是否压在页码文本上（只用于 tooltip，不参与点击）。 */
    private boolean inPageText(final double mouseX, final double mouseY) {
        return !disabled()
            && containsIn(mouseX, mouseY, PAGE_TEXT_X, BAND_Y, PAGE_TEXT_W, BAND_H);
    }

    /** 鼠标是否落在「自动 / 手动」开关上（仅提供开关、且未停用的界面可命中）。 */
    private boolean inToggle(final double mouseX, final double mouseY) {
        return source.hasAutoToggle() && !disabled()
            && containsIn(mouseX, mouseY, TOGGLE_X, TOGGLE_Y, TOGGLE_W, TOGGLE_H);
    }

    /**
     * 鼠标是否压在「详细配置…」按钮上（停用时控制带整条不画 → 按钮不存在，因此直接不可命中）。
     */
    private boolean inConfig(final double mouseX, final double mouseY) {
        return !disabled() && containsIn(mouseX, mouseY, CONFIG_X, CONFIG_Y, CONFIG_W, CONFIG_H);
    }

    /** 本总线此刻是否被玩家「强制为普通总线」（服务端权威同步值）。 */
    private boolean forceNormal() {
        return source.forceNormal();
    }

    /**
     * 本总线是否<b>可转换</b>（线缆真的够得到总线输出执行仓；用户第 2 条）。
     * <p>为真 ⇒ 「普通 / 总线」按钮<b>默认常驻</b>：类别条界面里画在条右侧，普通界面里也照旧画出来
     * （哪怕归属未确定而被停用），玩家因此<b>任何时候</b>都能主动把这条总线按「普通总线」用。</p>
     */
    private boolean convertible() {
        return source.convertible();
    }

    /**
     * 鼠标是否压在<b>延长界面</b>里条右侧的「普通」按钮上（<b>可转换</b>即存在，用户第 2 条：默认常驻）。
     * <p>命中判定与绘制共用同一批常量（{@link #FORCE_X} / {@link #FORCE_W}），必然同源。
     * 只在类别条界面里成立 —— 普通界面里那一块是过滤器槽，绝不能拦。</p>
     */
    private boolean inBarForce(final double mouseX, final double mouseY) {
        return executorMode() && convertible() && !forceNormal()
            && containsIn(mouseX, mouseY, FORCE_X, FORCE_Y, FORCE_W, FORCE_H);
    }

    /**
     * 鼠标是否压在<b>普通界面</b>里那颗常驻的「普通 / 总线」切换按钮上。
     *
     * <p><b>用户第 2 条：按钮必须始终保留、可来回切换</b>——因此这里<b>不再</b>看
     * {@link #forceNormal()}（强制普通时它就是「恢复为总线界面」，未强制时就是「普通」）、
     * 也<b>不再</b>看 {@link #disabled()}（归属未确定也得能点，否则玩家没有出口）。</p>
     */
    private boolean inNormalToggle(final double mouseX, final double mouseY) {
        return !executorMode() && convertible()
            && containsIn(mouseX, mouseY, RESTORE_X, RESTORE_Y, RESTORE_W, RESTORE_H);
    }

    /**
     * 鼠标压在哪个类别单元格上（返回<b>条上当前显示的那一份</b>的下标，-1 = 没有）。
     * <p>下标必须与 {@link #drawLockedBar} / tooltip 用的是<b>同一个列表</b>（{@link #visibleCategories()}），
     * 否则「画的是第 3 格、点中的是另 1 格」—— H 组把条上这份收窄成「只列已勾选」后，这里跟着收窄。</p>
     */
    private int hitCell(final double mouseX, final double mouseY) {
        final int count = visibleCategories().size();
        if (count <= 0 || mouseY < getY() || mouseY >= getY() + CELL_H) {
            return -1;
        }
        final int scrolled = clampScroll(scroll, count);
        for (int i = scrolled; i < count && i < scrolled + VISIBLE_MAX; i++) {
            final int bx = cellX(i, scrolled);
            if (mouseX >= bx && mouseX < bx + CELL) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 该类别<b>组代表物</b>（= 服务端下发的 {@code category.iconItem()}）；非物品类别 / 解析不到时返回空栈。
     *
     * <p>它是这个类别的<b>持久身份</b>（类别 id 的物品段、勾选集合都按它走），因此
     * <b>不</b>随时间变化。有候选可轮播时界面不直接画它，而是画
     * {@code BusCategoryConfigScreen#barStackOf}（条上）/
     * {@code BusCategoryConfigScreen#displayStackOf}（详细配置行内）—— 两处共用同一份候选与同一套相位。
     * 本方法仍是「没有候选」时的兜底显示。</p>
     */
    public static ItemStack iconOf(final RsccBusCategory category) {
        if (category == null || category.iconItem() == null || category.iconItem().isEmpty()) {
            return ItemStack.EMPTY;
        }
        final ResourceLocation id = ResourceLocation.tryParse(category.iconItem());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        final Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** 该类别代表流体（图标）；非流体类别 / 解析不到时返回空栈（数量仅在绘制时占位，不参与渲染）。 */
    public static FluidStack fluidIconOf(final RsccBusCategory category) {
        if (category == null || !category.isFluidInput()) {
            return FluidStack.EMPTY;
        }
        final ResourceLocation id = ResourceLocation.tryParse(category.fluidInputId());
        return id == null ? FluidStack.EMPTY : GhostMarkerRenderer.toFluidStack(id, 1L);
    }

    /**
     * 该类别在界面上的<b>静态</b>显示名：优先用语言键，其次用图标资源的真实名字，最后退回 id 原文。
     * <p>即「组代表物」的名字 —— 它是类别的持久身份，因此<b>不</b>随时间变化。多候选类别在格子 /
     * 行内 / 悬浮 tooltip 上显示的<b>当前那一件</b>的名字由
     * {@code BusCategoryConfigScreen#barNameOf} 给出（同一套轮播相位）；本方法则是那里的兜底，
     * 并被「已选集摘要」这类<b>整表</b>文本使用（摘要说的是「选了哪些类别」，不该随时轮换）。</p>
     */
    public static Component displayName(final RsccBusCategory category) {
        if (category.labelKey() != null && !category.labelKey().isEmpty()) {
            return Component.translatable(category.labelKey());
        }
        if (category.isFluidInput()) {
            final ResourceLocation id = ResourceLocation.tryParse(category.fluidInputId());
            if (id != null) {
                return GhostMarkerRenderer.fluidName(id);
            }
        }
        final ItemStack icon = iconOf(category);
        if (!icon.isEmpty()) {
            return icon.getHoverName();
        }
        return Component.literal(category.id());
    }

    /** tooltip 的数量行文本：流体按 mB/B 格式化，物品直接给件数。 */
    private static String amountText(final RsccBusCategory category) {
        return category.isFluidInput()
            ? GhostMarkerRenderer.fluidAmount(category.amount())
            : String.valueOf(category.amount());
    }

    /** 「预估需求」的显示文本（口径与 {@link #amountText} 一致，仅数值不同）。 */
    private static String estimatedText(final RsccBusCategory category) {
        return category.isFluidInput()
            ? GhostMarkerRenderer.fluidAmount(category.estimated())
            : String.valueOf(category.estimated());
    }

    /**
     * 「本条总线当前已选的类别」名称串（空串 = 一个都没选，调用方据此不显示该行）。
     * <p>自动模式下服务端把「非输入类」标成已选，因此这个串就是「正在回收哪些类别」；
     * 手动模式 / 输出总线侧它就是玩家勾选的那一份 —— 一处实现服务两种语义。</p>
     */
    private String selectedNames() {
        final StringBuilder sb = new StringBuilder(48);
        for (final RsccBusCategory category : categories()) {
            if (!category.selected()) {
                continue; // 自动模式下服务端把「非输入类」标成已收回，因此 selected 就是「正在回收」
            }
            if (sb.length() > 0) {
                sb.append(Component.translatable(PAGE_LANG + "sep").getString());
            }
            sb.append(displayName(category).getString());
        }
        return sb.toString();
    }

    // ==================== 绘制 ====================

    @Override
    protected void renderWidget(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                final float partialTick) {
        if (!executorMode()) {
            // 非延长型界面：只画「强制普通」这条状态带（恢复按钮 + 可能的停用红条），
            // 类别条与它的控制带一个像素都不画 —— 界面完全等同 RS 原版的普通总线。
            drawNormalModeUi(guiGraphics, mouseX, mouseY);
            return;
        }
        drawLockedBar(guiGraphics, mouseX, mouseY);
    }

    /**
     * 非延长型界面（= 玩家把总线<b>强制为普通总线</b>，或本机压根没接执行舱、或归属未确定）下的自绘内容：
     * <b>只剩一颗常驻的「普通 / 总线」切换按钮</b>。
     *
     * <h2>用户第 2 条（本轮）为什么把这里改成「只画按钮」</h2>
     * <ul>
     *     <li><b>「不要在普通模式显示已停用原因那类说明」</b>：普通界面就是普通界面，
     *     因此这里<b>不再</b>画「延长型已停用」红条，也不画它的「显示可达区域」按钮
     *     （那套说明在<b>类别条界面</b>里照旧一字未改）；</li>
     *     <li><b>「按钮必须始终保留、可来回切换」</b>：只要线缆够得到执行仓（{@link #convertible()}），
     *     这一帧就<b>一定</b>画出按钮 —— 强制普通时它是「恢复为总线界面」，未强制时它是「普通」，
     *     因此玩家在普通界面里随时能切回去（旧实现在「归属未确定 + 强制普通」时把按钮整颗吞掉，
     *     玩家就再也回不去了）。</li>
     * </ul>
     */
    private void drawNormalModeUi(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (convertible()) {
            drawNormalToggle(guiGraphics, mouseX, mouseY);
        }
    }

    /**
     * 画普通界面里那颗常驻的「普通 / 总线」切换按钮（文字无阴影；命中 + 绘制共用同一批常量）。
     *
     * <p>标签随当前状态变化：{@link #forceNormal()} 为真 ⇒ 「恢复为总线界面」；
     * 否则 ⇒ 「普通」（= 主动把这条可转换总线固定成普通总线）。</p>
     */
    private void drawNormalToggle(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final Font font = Minecraft.getInstance().font;
        final boolean hovered = inNormalToggle(mouseX, mouseY);
        final int bx = getX() + RESTORE_X;
        final int by = getY() + RESTORE_Y;
        guiGraphics.fill(bx, by, bx + RESTORE_W, by + RESTORE_H, COLOR_BAR_BORDER);
        guiGraphics.fill(bx + 1, by + 1, bx + RESTORE_W - 1, by + RESTORE_H - 1,
            hovered ? COLOR_BTN_RESTORE_HOVER : COLOR_BTN_RESTORE);
        final Component label = Component.translatable(
            source.forceNormalLang() + (forceNormal() ? "restore" : "button"));
        guiGraphics.drawString(font, label, bx + (RESTORE_W - font.width(label)) / 2, by + 3,
            0xFFFFFFFF, false);
    }

    /**
     * 绘制类别条本体（盖住 9 个过滤器槽位，让「槽位不可编辑」一眼可见）、条下方的控制带
     * （◀ ▶ / 页码 / 自动开关）与停用提示。
     * <p>{@link #renderWidget} 与 {@link #rscc$renderTooltip} 共用它，因此外观与 hover 判定必然同源。</p>
     */
    private void drawLockedBar(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int x = getX();
        final int y = getY();
        final int w = getWidth();
        final int h = getHeight();
        // H 组：条上只列「已勾选」的那一份（visibleCategories）——绘制 / 命中 / 翻页 / tooltip
        // 全部走这同一个列表，因此「看到的」与「点得中的」必然一致。
        final List<RsccBusCategory> list = visibleCategories();
        final int count = list.size();
        final int scrolled = clampScroll(scroll, count);
        // 描边刻意画在控件范围外 1px：几何与改造前完全一致
        guiGraphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, COLOR_BAR_BORDER);
        guiGraphics.fill(x, y, x + w, y + h, COLOR_BAR_BG);
        if (autoCollect()) {
            // 自动模式：整条加一道蓝色内框（与开关同色）—— 玩家一眼看出「这条现在是自动、只读」
            drawInnerBorder(guiGraphics, COLOR_TOGGLE_AUTO);
        } else if (!autoCraftingEnabled() && !disabled()) {
            // 自动合成未开启：内圈加一道暗红描边（纯状态提示；不改勾选态、不做禁用）
            // 停用时不再画它：那圈描边的 tooltip 说的是「自动合成没开」，在停用态下会误导。
            drawInnerBorder(guiGraphics, COLOR_GATE_OFF);
        }
        if (!disabled()) {
            drawControlBand(guiGraphics, mouseX, mouseY, count, scrolled);
        }
        // 「普通」按钮（用户第 2 条：<b>默认常驻</b>）—— 停用态也照旧画：此时玩家最需要一条
        // 「干脆把这条总线固定成普通总线」的出口。它与停用红条 / 显示可达区域按钮不在同一横带，几何上永不重叠。
        if (convertible() && !forceNormal()) {
            drawForceButton(guiGraphics, mouseX, mouseY);
        }
        drawDisabledNotice(guiGraphics, mouseX, mouseY);
        if (count == 0) {
            // 延长模式但当前没有任何类别：只给「空条提示」两行，不留空条。
            // 几何（可用宽由固定段推出 + 逐行截断）见 drawEmptyHint —— 与「类别详细配置」子界面
            // 的 drawHint 同一套策略，这里是<b>条本身那一条</b>的同一类缺陷的第二处。
            drawEmptyHint(guiGraphics);
            return;
        }
        final boolean auto = autoCollect();
        for (int i = scrolled; i < count && i < scrolled + VISIBLE_MAX; i++) {
            drawCell(guiGraphics, list.get(i), cellX(i, scrolled), y, auto);
        }
    }

    /** 在类别条内圈画一道 1px 描边（自动模式 = 蓝，门控关闭 = 暗红）。 */
    private void drawInnerBorder(final GuiGraphics guiGraphics, final int color) {
        final int x = getX();
        final int y = getY();
        final int w = getWidth();
        final int h = getHeight();
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 画「条上一个类别都没有」时的提示 —— <b>几何上不可能重叠、不可能越界</b>。
     *
     * <h2>修的是什么（用户截图：条上提示「两段字叠在一起、看不清楚」）</h2>
     * <p>旧实现把整句提示（输入总线侧 = 「还没勾选任何要收回的类别（点「详细配置…」开始勾选）」）
     * 按 {@code x + (w - 文字宽) / 2} <b>居中画在 162px 的条上、一个字都不截断</b>：
     * 这句话中文就约 225px，于是左右各溢出约 32px —— 右半截正好压进条内右端那颗几何固定的
     * 「普通」按钮（{@link #FORCE_X} = 局部 132..162，按钮文字在 y 3..12，与提示文字的 y 7..16
     * 相交），两段字因此叠在同一片像素上；左半截还越出了条内层甚至面板左缘。
     * 这是「类别详细配置」子界面 {@code drawHint} 那处缺陷的<b>第二处</b>（同一类几何错误，
     * 上一轮只修了子界面）。</p>
     *
     * <h2>修法（与子界面 {@code drawHint} 同一套策略）</h2>
     * <ol>
     *     <li><b>固定段先算死</b>：条内右端那颗「普通」按钮的几何是常量（30×23 @ {@link #FORCE_X}），
     *     提示行的可用带 = 条内层左缘 + {@value #HINT_INSET} … 按钮左缘 − {@value #HINT_GAP}
     *     （子界面里「固定段」是右对齐的尾句，这里就是这颗按钮；两者都是「先算死、再让文字去适配」）；</li>
     *     <li><b>逐行按可用带宽截断</b>（{@link #trimToWidth}，尾部补省略号），再在带内居中：
     *     因为 {@code 0 ≤ 文字宽 ≤ 带宽}，居中的左界恒 ≥ 带左缘、右界恒 ≤ 带右缘 ⇒
     *     <b>永不压到按钮上、永不出条</b>，任意语言 / 任意字体都成立，不靠「文案够短」这条假设；</li>
     *     <li><b>分成两行</b>（状态一行、去哪改一行）：可用带只有约 128px，而「状态 + 去哪改」整句
     *     中文约 190px、英文更宽；若照子界面那样做「一行两段」（左右各占半幅），两段都会被截成
     *     「点「详细配…」」这种读不通的残句。两行的代价为零：提示只在空条这一帧画，
     *     此时条里没有任何单元格，两行 9px 落在 23px 的条内层里绰绰有余 ——
     *     <b>面板高度、按钮 Y、控制带几何、既有布局校验（tmp_textures/verify_gui_layout.py）一个都不动</b>。</li>
     * </ol>
     * <p>两行本身也永不互相重叠：它们的垂直区间分别是 {@value #HINT_LINE1_Y}..11 与
     * {@value #HINT_LINE2_Y}..21（行高 {@value #HINT_LINE_H}），<b>不相交</b>，与文字多宽无关。</p>
     */
    private void drawEmptyHint(final GuiGraphics guiGraphics) {
        final Font font = Minecraft.getInstance().font;
        // ① 固定段（条内右端那颗「普通」按钮）先算死：它画不画由与绘制同源的同一个条件决定，
        //    因此可用带的右界始终 = 它的左缘 − HINT_GAP（不画时退回条内层右缘 − HINT_INSET）。
        final int left = getX() + HINT_INSET;
        final int right = (convertible() && !forceNormal())
            ? getX() + FORCE_X - HINT_GAP
            : getX() + getWidth() - HINT_INSET;
        final int bandW = Math.max(0, right - left);
        // ② 两种「空」分开说（H 组）：本来就没有候选 ⇒ 只有一行状态；有候选但没勾 ⇒ 状态 + 「去哪改」。
        final boolean hasCandidates = !categories().isEmpty();
        final int headY = getY() + (hasCandidates ? HINT_LINE1_Y : (getHeight() - HINT_LINE_H) / 2);
        drawHintLine(guiGraphics, font, Component.translatable(hasCandidates
            ? source.selectedEmptyTextKey() : source.emptyTextKey()).getString(), left, bandW, headY);
        if (hasCandidates) {
            final int actionY = getY() + HINT_LINE2_Y;
            drawHintLine(guiGraphics, font,
                Component.translatable(source.selectedEmptyActionTextKey()).getString(),
                left, bandW, actionY);
        }
    }

    /**
     * 画空条提示的一行：先按可用带宽截断（尾部补 {@value #HINT_ELLIPSIS}），再在带内居中。
     * <p>居中不破坏「不可能越界」这一条：截断后 {@code 0 ≤ 文字宽 ≤ bandW}，因此
     * 左界 = {@code left + (bandW - 文字宽) / 2} ≥ {@code left}、右界 ≤ {@code left + bandW} = 可用带右缘。</p>
     */
    private void drawHintLine(final GuiGraphics guiGraphics, final Font font, final String raw,
                              final int left, final int bandW, final int y) {
        final String text = trimToWidth(font, raw, bandW);
        if (text.isEmpty()) {
            return;
        }
        guiGraphics.drawString(font, text, left + (bandW - font.width(text)) / 2, y, COLOR_TEXT, false);
    }

    /**
     * 按像素宽截断（尾部补省略号）：放不下时逐字符收，直到「正文 + 省略号」塞得进给定宽度。
     * <p>与「类别详细配置」子界面 {@code BusCategoryConfigScreen#trim} 同一做法 ——
     * 截断是几何兜底：任意语言、任意字体下都不会溢出，最多变成省略号。</p>
     * <p><b>返回值恒满足 {@code 0 ≤ width(返回串) ≤ maxWidth}</b>（放不下就连省略号都不画，
     * 返回空串）—— 这是「居中画」不会越界的<b>唯一前提</b>，因此这里把边角情形也堵死，
     * 而不是依赖「可用带总是够宽」（可用带现在确实是 128px，但几何证明不该依赖它）。</p>
     */
    private static String trimToWidth(final Font font, final String text, final int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        final int ellipsisWidth = font.width(HINT_ELLIPSIS);
        if (maxWidth <= ellipsisWidth) {
            return ""; // 连省略号都塞不下：宁可不画，也不画出比可用宽度更宽的一行
        }
        String trimmed = text;
        while (trimmed.length() > 1 && font.width(trimmed + HINT_ELLIPSIS) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        final String candidate = trimmed + HINT_ELLIPSIS;
        return font.width(candidate) <= maxWidth ? candidate : "";
    }

    /**
     * 绘制条下方的控制带：◀ / ▶ 翻页按钮 + 页码 + 「自动 / 手动」开关（只有输入总线有开关）
     * + 「详细配置…」按钮（两种宿主都有）。
     *
     * <p>按钮<b>常驻显示</b>：仅一页时画成灰底禁用态（不是不画）—— 玩家一眼就知道「翻不动」，
     * 而不必去猜「按钮去哪了」。</p>
     */
    private void drawControlBand(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                 final int count, final int scrolled) {
        final boolean canPrev = scrolled > 0;
        final boolean canNext = scrolled + VISIBLE_MAX < count;
        drawArrowButton(guiGraphics, PREV_X, canPrev, inPrev(mouseX, mouseY), true);
        drawArrowButton(guiGraphics, NEXT_X, canNext, inNext(mouseX, mouseY), false);
        final Font font = Minecraft.getInstance().font;
        // 页码：n/m（不可点，只有 tooltip）。刻意不写空格：宽度必须塞进 PAGE_TEXT_W，
        // 两位数页码（例如 12/34）也不能溢出压到右边的开关。
        final Component page = Component.literal(pageOf(scrolled) + "/" + pageCount(count));
        final int textX = getX() + PAGE_TEXT_X + (PAGE_TEXT_W - font.width(page)) / 2;
        guiGraphics.drawString(font, page, textX, getY() + PAGE_TEXT_Y,
            pageCount(count) > 1 ? COLOR_TEXT : COLOR_TEXT_DIM, false);
        if (source.hasAutoToggle()) {
            final boolean auto = autoCollect();
            final boolean hovered = inToggle(mouseX, mouseY);
            final int fill = auto
                ? (hovered ? COLOR_TOGGLE_AUTO_HOVER : COLOR_TOGGLE_AUTO)
                : (hovered ? COLOR_BTN_OFF_HOVER : COLOR_BTN_OFF);
            final int tx = getX() + TOGGLE_X;
            final int ty = getY() + TOGGLE_Y;
            guiGraphics.fill(tx, ty, tx + TOGGLE_W, ty + TOGGLE_H, COLOR_BAR_BORDER);
            guiGraphics.fill(tx + 1, ty + 1, tx + TOGGLE_W - 1, ty + TOGGLE_H - 1, fill);
            final Component label = Component.translatable(lang() + "auto." + (auto ? "on" : "off"));
            guiGraphics.drawString(font, label, tx + (TOGGLE_W - font.width(label)) / 2, ty + 3,
                auto ? 0xFFFFFFFF : COLOR_TEXT, false);
        }
        drawConfigButton(guiGraphics, mouseX, mouseY);
    }

    /**
     * 画「详细配置…」按钮（本界面上唯一的类别选择入口：点了打开子界面）。
     * <p>文字无阴影；命中判定与绘制用同一批常量（{@link #CONFIG_X} / {@link #CONFIG_W}）。</p>
     */
    private void drawConfigButton(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final Font font = Minecraft.getInstance().font;
        final boolean hovered = inConfig(mouseX, mouseY);
        final int bx = getX() + CONFIG_X;
        final int by = getY() + CONFIG_Y;
        guiGraphics.fill(bx, by, bx + CONFIG_W, by + CONFIG_H, COLOR_BAR_BORDER);
        guiGraphics.fill(bx + 1, by + 1, bx + CONFIG_W - 1, by + CONFIG_H - 1,
            hovered ? COLOR_BTN_CONFIG_HOVER : COLOR_BTN_CONFIG);
        final Component label = Component.translatable(CONFIG_LANG + "button");
        guiGraphics.drawString(font, label, bx + (CONFIG_W - font.width(label)) / 2, by + 3,
            0xFFFFFFFF, false);
    }

    /**
     * 画条右侧的「普通」按钮（用户原话：「赶紧做我要的按钮」）—— 点它 = <b>强制为普通总线</b>：
     * 关掉「被自动判定成序列装配总线」，之后打开就是普通输入输出总线界面。
     * <p>文字无阴影；命中判定与绘制共用同一批常量（{@link #FORCE_X} / {@link #FORCE_W}）。
     * 标签刻意取最短的中文「普通」/ 英文「Plain」（按钮只有 30px 宽），完整说明在 tooltip 里。</p>
     */
    private void drawForceButton(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final Font font = Minecraft.getInstance().font;
        final boolean hovered = inBarForce(mouseX, mouseY);
        final int bx = getX() + FORCE_X;
        final int by = getY() + FORCE_Y;
        guiGraphics.fill(bx, by, bx + FORCE_W, by + FORCE_H, COLOR_BAR_BORDER);
        guiGraphics.fill(bx + 1, by + 1, bx + FORCE_W - 1, by + FORCE_H - 1,
            hovered ? COLOR_BTN_RESTORE_HOVER : COLOR_BTN_RESTORE);
        final Component label = Component.translatable(source.forceNormalLang() + "button");
        guiGraphics.drawString(font, label, bx + (FORCE_W - font.width(label)) / 2, by + 7,
            0xFFFFFFFF, false);
    }

    /** 画一个 ◀ / ▶ 按钮（箭头用 fill 现画，不依赖任何贴图 / 字体字形）。 */
    private void drawArrowButton(final GuiGraphics guiGraphics, final int localX, final boolean enabled,
                                 final boolean hovered, final boolean left) {
        final int bx = getX() + localX;
        final int by = getY() + BAND_Y;
        guiGraphics.fill(bx, by, bx + BTN_W, by + BTN_H, COLOR_BAR_BORDER);
        final int fill = !enabled ? COLOR_BTN_DISABLED : (hovered ? COLOR_BTN_OFF_HOVER : COLOR_BTN_OFF);
        guiGraphics.fill(bx + 1, by + 1, bx + BTN_W - 1, by + BTN_H - 1, fill);
        drawArrow(guiGraphics, bx + 7, by + BTN_H / 2, left,
            enabled ? COLOR_TEXT : COLOR_TEXT_DIM);
    }

    /**
     * 用 5 列 fill 画一个三角箭头（宽 5px、高 9px）。
     * <p>为什么不用字体里的「◀ / ▶」：那要依赖 unifont 回退字形，观感与稳定性都不如直接画；
     * 也用不到新增贴图（本界面约定「全部 Java 运行时绘制」）。</p>
     *
     * @param x    箭头最左侧一列的 x（左箭头 = 尖端，右箭头 = 底边）
     * @param cy   箭头垂直中心
     */
    private static void drawArrow(final GuiGraphics guiGraphics, final int x, final int cy,
                                  final boolean left, final int color) {
        for (int i = 0; i < 5; i++) {
            final int columnHeight = left ? 1 + i * 2 : 9 - i * 2;
            final int top = cy - columnHeight / 2;
            guiGraphics.fill(x + i, top, x + i + 1, top + columnHeight, color);
        }
    }

    // ==================== 「归属未确定 → 延长型已停用」提示 ====================

    /** 本总线此刻是否因归属未确定而停用（服务端权威同步值；未同步到一律按「没有」处理，界面不画任何东西）。 */
    private boolean disabled() {
        return source.disabled();
    }

    /** 警示条 / 按钮的几何（局部坐标；绘制与命中判定共用，必然同源）。 */
    private boolean inWarnBanner(final double mouseX, final double mouseY) {
        return containsIn(mouseX, mouseY, WARN_BANNER_X, WARN_Y, WARN_BANNER_W, WARN_H);
    }

    private boolean inWarnButton(final double mouseX, final double mouseY) {
        return containsIn(mouseX, mouseY, WARN_BTN_X, WARN_Y, WARN_BTN_W, WARN_H);
    }

    /** 局部坐标矩形命中（左闭右开），与 {@link #contains} 同一套口径。 */
    private boolean containsIn(final double mouseX, final double mouseY,
                               final int x, final int y, final int w, final int h) {
        return mouseX >= getX() + x && mouseX < getX() + x + w
            && mouseY >= getY() + y && mouseY < getY() + y + h;
    }

    /**
     * 画「延长型已停用：归属未确定」警示条 + 「显示 / 隐藏可达区域」按钮（停用时才画；否则一个像素都不画）。
     * <p>由 {@link #drawLockedBar} 调用，因此正常渲染与 tooltip 阶段的补绘都会带上它 ——
     * 这一带虽然在控件范围之外，但正是在最后阶段绘制才能盖住 RS 自己画的背包行标签。</p>
     */
    private void drawDisabledNotice(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (!disabled()) {
            return;
        }
        final Font font = Minecraft.getInstance().font;
        // 警示条：暗红底 + 1px 深色描边 + 一行文字（文字走语言键，不带阴影，符合本模组硬规则）
        final int bx = getX() + WARN_BANNER_X;
        final int by = getY() + WARN_Y;
        guiGraphics.fill(bx, by, bx + WARN_BANNER_W, by + WARN_H, COLOR_WARN_BORDER);
        guiGraphics.fill(bx + 1, by + 1, bx + WARN_BANNER_W - 1, by + WARN_H - 1, COLOR_WARN_BG);
        final Component banner = Component.translatable(WARN_LANG + "banner");
        guiGraphics.drawString(font, banner, bx + 3, by + 2, COLOR_WARN_TEXT, false);
        // 按钮：未按下 = 灰（点开叠加层），已按下 = 绿（点掉叠加层）
        final boolean shown = source.overlayShown();
        final boolean hovered = inWarnButton(mouseX, mouseY);
        final int tx = getX() + WARN_BTN_X;
        final int ty = getY() + WARN_Y;
        guiGraphics.fill(tx, ty, tx + WARN_BTN_W, ty + WARN_H, COLOR_BAR_BORDER);
        guiGraphics.fill(tx + 1, ty + 1, tx + WARN_BTN_W - 1, ty + WARN_H - 1,
            shown ? (hovered ? COLOR_BTN_HOVER : COLOR_WARN_BTN_ON)
                : (hovered ? COLOR_BTN_OFF_HOVER : COLOR_WARN_BTN_OFF));
        final Component label = Component.translatable(WARN_LANG + (shown ? "hide" : "show"));
        guiGraphics.drawString(font, label, tx + (WARN_BTN_W - font.width(label)) / 2, ty + 2,
            shown ? 0xFFFFFFFF : COLOR_TEXT, false);
    }

    /**
     * 停用提示的 tooltip（本模组 GUI 不会自动渲染 tooltip，必须手动调用）：
     * <b>说清已停用</b>、<b>说清可达几台执行舱</b>、<b>说清怎么恢复</b>（分隔框架 / 把执行舱分开）、
     * 以及按钮的用途。
     */
    private List<FormattedCharSequence> disabledTooltipLines() {
        final List<FormattedCharSequence> lines = new ArrayList<>(10);
        lines.add(Component.translatable(WARN_LANG + "title")
            .withStyle(net.minecraft.ChatFormatting.RED).getVisualOrderText());
        lines.add(Component.translatable(WARN_LANG + "reason", source.disabledReachableCount())
            .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
        lines.add(Component.translatable(WARN_LANG + "hint")
            .withStyle(net.minecraft.ChatFormatting.YELLOW).getVisualOrderText());
        if (!linkedChamber().isEmpty()) {
            lines.add(Component.translatable(lang() + "linked", linkedChamber())
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY).getVisualOrderText());
        }
        for (final net.minecraft.core.BlockPos pos : source.disabledChambers()) {
            lines.add(chamberLine(pos).getVisualOrderText());
        }
        if (source.disabledTruncated()) {
            lines.add(Component.translatable(WARN_LANG + "truncated")
                .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
        }
        lines.add(Component.translatable(WARN_LANG + (source.overlayShown() ? "hide.tip" : "show.tip"))
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC)
            .getVisualOrderText());
        return lines;
    }

    /** 单个可达执行舱的描述行：{@code 可达执行舱 (x, y, z)}（坐标走语言键，可翻译）。 */
    private static Component chamberLine(final net.minecraft.core.BlockPos pos) {
        return Component.translatable(WARN_LANG + "target.chamber",
            pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * 画一个类别单元格（<b>只读展示</b>）：底色（已选 / 未选）+ 1px 描边 + 图标（物品 / 流体）
     * + 标记行（勾选态 / 自动态 / 步骤角标）+ 共享角标。
     *
     * <p><b>为什么不再有 hover 高亮</b>（本轮用户要求）：条上不再接受任何勾选动作，
     * 高亮会暗示「这里能点」。状态仍靠底色表达（绿 = 已选、灰 = 未选），
     * 详细勾选一律去「详细配置…」子界面。</p>
     *
     * <p><b>标记行（图标下方那一行，高 5px）</b>：</p>
     * <ul>
     *     <li>自动模式（{@code auto} 为真）→ 蓝色「自动」标记（与开关同色，表示这一格由自动模式决定）；</li>
     *     <li>手动模式 → 5×5 勾选框（已选 = 深绿内胆）；</li>
     * </ul>
     * <p><b>中间产物的「第几步」角标</b>：中间产物类别归属某一步时，在勾选框右边再画一个
     * 与「步序 + 1」关联的橙色小方块（步序越靠后越深），并在 tooltip / 子界面里写明「第 N 步」——
     * 用户明确要求「显示时要标注这是第几步的东西」；格子只有 20px 宽，放不下文字，
     * 因此格子用角标、文字标注放在 tooltip 与子界面列表里（那里空间足够）。</p>
     */
    private static void drawCell(final GuiGraphics guiGraphics, final RsccBusCategory category,
                                 final int bx, final int y, final boolean auto) {
        // 底色只表示状态（已选 / 未选），不再表示「可点 / 悬停」
        guiGraphics.fill(bx, y, bx + CELL, y + CELL_H,
            category.selected() ? COLOR_BTN_ON : COLOR_BTN_OFF);
        // 1px 描边（四边）
        guiGraphics.fill(bx, y, bx + CELL, y + 1, COLOR_BAR_BORDER);
        guiGraphics.fill(bx, y + CELL_H - 1, bx + CELL, y + CELL_H, COLOR_BAR_BORDER);
        guiGraphics.fill(bx, y, bx + 1, y + CELL_H, COLOR_BAR_BORDER);
        guiGraphics.fill(bx + CELL - 1, y, bx + CELL, y + CELL_H, COLOR_BAR_BORDER);
        // 图标：格内 +1、16×16（与「精灵坐标 → Menu 槽位坐标 +1」口径一致；与下方标记行零重叠）
        if (category.isFluidInput()) {
            GhostMarkerRenderer.renderFluid(guiGraphics, bx + 1, y + 1, fluidIconOf(category));
        } else {
            // <b>2026-10-06 用户实测</b>：<i>「第二张图中显示为两个铁粒……它一直显示成铁粒，没有轮换。
            // 这个界面应该也出现轮换」</i>。原先这里直读 {@link #iconOf}（= 组代表物，恒定铁粒）。
            // 现在与详细配置界面走<b>同一个</b>入口（{@code BusCategoryConfigScreen#barStackOf} →
            // 同一份候选 + 同一套 {@link GhostMarkerRenderer#cycleCandidate} 相位）：
            // 多候选 ⇒ 随时间轮换；单候选 / 无候选 ⇒ 代表物（行为不变）。一个类别仍只占一格。
            final ItemStack icon = BusCategoryConfigScreen.barStackOf(category);
            if (!icon.isEmpty()) {
                guiGraphics.renderItem(icon, bx + 1, y + 1);
            }
        }
        // 标记行（图标下方那一行，高 5px）
        if (auto) {
            // 自动模式：蓝框 + 蓝底「自动」标记（这一格由自动模式决定，玩家改不了）
            drawAutoMark(guiGraphics, bx + 1, y + CELL_H - 6);
        } else {
            // 勾选框：图标下方那一行的左下角 5×5（只读展示勾选态；改勾选去「详细配置…」）
            drawCheckbox(guiGraphics, bx + 1, y + CELL_H - 6, category.selected());
        }
        // 中间产物：再画一个「第几步」角标（橙色小方块，见方法说明）
        final int step = category.intermediateStep();
        if (step >= 0) {
            drawStepMark(guiGraphics, bx + CELL - 6, y + CELL_H - 6, step);
        } else if (category.selected() && category.isShared()) {
            // 共享标记：图标下方那一行的右下角青色小方块（tooltip 会写明「N 台共享，均分」）
            guiGraphics.fill(bx + CELL - 5, y + CELL_H - 5, bx + CELL - 1, y + CELL_H - 1,
                COLOR_SHARED_MARK);
        }
    }

    /**
     * 5×5「第 N 步」角标：深色外框 + 内胆颜色随步序加深（越靠后的步骤越深），
     * 中线上再点一竖条像素当作「步」的提示。
     * <p>为什么不写字：单元格只有 20px 宽、标记行只有 5px 高，塞不下可读文字（字体高 9px）。
     * 文字标注由 tooltip（「第 N 步中间产物」）与子界面列表承担。</p>
     */
    private static void drawStepMark(final GuiGraphics guiGraphics, final int x, final int y, final int step) {
        final int shade = Math.max(0, 3 - Math.min(3, step / 2)); // 0..3，步序大 → 更深的橙
        final int[] palette = {0xFFFFB74D, 0xFFFB8C00, 0xFFEF6C00, 0xFFE65100};
        guiGraphics.fill(x, y, x + 5, y + 5, COLOR_BAR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, palette[shade]);
    }

    /** 5×5 勾选框：1px 深色外框 + 3×3 内胆（实心深绿 = 已勾选）。 */
    private static void drawCheckbox(final GuiGraphics guiGraphics, final int x, final int y, final boolean checked) {
        guiGraphics.fill(x, y, x + 5, y + 5, COLOR_BAR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, checked ? COLOR_CHECKBOX_ON : COLOR_CHECKBOX_OFF);
    }

    /** 5×5「自动」标记：深色外框 + 3×3 蓝色内胆（与勾选框同尺寸，颜色即语义）。 */
    private static void drawAutoMark(final GuiGraphics guiGraphics, final int x, final int y) {
        guiGraphics.fill(x, y, x + 5, y + 5, COLOR_BAR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, COLOR_AUTO_MARK);
    }

    /**
     * 手动渲染 tooltip（本模组 GUI 不会自动渲染 tooltip），由界面的 {@code renderTooltip} 阶段调用。
     *
     * <p>这里<b>同时补绘一次类别条与它的控制带</b>：容器的槽位（连同悬停高亮）是在「控件渲染之后」的
     * 槽位循环里绘制的，会盖在类别条上。在最后阶段补绘一次，即可让外观稳定。</p>
     *
     * @return {@code true} 表示已接管（调用方应取消 RS 原版 tooltip）
     */
    public boolean rscc$renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (!executorMode()) {
            // 普通界面（含「强制普通」/ 归属未确定）：补绘常驻的「普通 / 总线」切换按钮并手动渲染 tooltip
            // （本模组 GUI 不自动渲染 tooltip）。普通界面里<b>不再</b>画「已停用原因」那套说明（用户第 2 条）。
            if (!convertible()) {
                return false;
            }
            drawNormalModeUi(guiGraphics, mouseX, mouseY);
            if (inNormalToggle(mouseX, mouseY)) {
                final Font normalFont = Minecraft.getInstance().font;
                final List<FormattedCharSequence> lines = new ArrayList<>(3);
                lines.add(Component.translatable(source.forceNormalLang()
                    + (forceNormal() ? "restore.tip" : "button.tip")).getVisualOrderText());
                lines.add(Component.translatable(source.forceNormalLang()
                    + (forceNormal() ? "restore.hint" : "button.hint")).getVisualOrderText());
                guiGraphics.renderTooltip(normalFont, lines, mouseX, mouseY);
                return true;
            }
            return false;
        }
        drawLockedBar(guiGraphics, mouseX, mouseY);
        final Font font = Minecraft.getInstance().font;
        // 停用提示：按钮 / 警示条都画在类别条<b>下方</b>，因此要在这里先判（否则会被下面的「条外」分支漏掉）
        if (disabled() && inWarnButton(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, disabledTooltipLines(), mouseX, mouseY);
            return true;
        }
        if (disabled() && inWarnBanner(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, disabledTooltipLines(), mouseX, mouseY);
            return true;
        }
        // 与 drawLockedBar / hitCell 同一个列表（H 组：只列已勾选），否则悬停到的那一格会对不上
        final List<RsccBusCategory> list = visibleCategories();
        final int count = list.size();
        final int hovered = hitCell(mouseX, mouseY);
        if (hovered >= 0 && hovered < count) {
            final RsccBusCategory category = list.get(hovered);
            final List<FormattedCharSequence> lines = new ArrayList<>(10);
            // 标题必须与格子里<b>这一刻画的那一件</b>同源（{@code barNameOf} 内部就是格子的
            // {@code barStackOf}）：条上轮播到锌粒时这里也必须是锌粒 —— 上一轮在详细配置界面修过
            // 同类错配（「显示锌粒、tooltip 铁粒」），这里用同一个口径，不得各算一份相位。
            lines.add(BusCategoryConfigScreen.barNameOf(category).getVisualOrderText());
            // 中间产物：必须一眼看出「这是第几步的东西」（用户硬要求）
            final int step = category.intermediateStep();
            if (category.isIntermediate()) {
                lines.add(Component.translatable(step >= 0 ? CONFIG_LANG + "step" : CONFIG_LANG + "step.unknown",
                    step >= 0 ? step + 1 : 0)
                    .withStyle(net.minecraft.ChatFormatting.GOLD).getVisualOrderText());
            }
            if (!linkedChamber().isEmpty()) {
                lines.add(Component.translatable(lang() + "linked", linkedChamber()).getVisualOrderText());
            }
            if (category.amount() > 0) {
                lines.add(Component.translatable("gui.rs_create_compat.marker.amount",
                    amountText(category)).getVisualOrderText());
            }
            // 预估需求（正常情况需要多少）+ 概率提示：仅«直到目标产物达标»策略下显示
            if (targetStrategy() && category.estimated() > 0) {
                lines.add(Component.translatable(lang() + "estimated", estimatedText(category))
                    .getVisualOrderText());
                lines.add(Component.translatable(lang() + "estimated.note").getVisualOrderText());
            }
            lines.add(Component.translatable(source.typeTipKey(category)).getVisualOrderText());
            lines.add(Component.translatable(lang() + (category.selected() ? "state.on" : "state.off"))
                .getVisualOrderText());
            if (category.selected() && !autoCollect()) {
                lines.add(category.isShared()
                    ? Component.translatable(lang() + "shared.tip", category.sharedCount()).getVisualOrderText()
                    : Component.translatable(lang() + "exclusive.tip").getVisualOrderText());
            }
            if (!autoCraftingEnabled() && !disabled()) {
                lines.add(Component.translatable(lang() + "gate.off.tip").getVisualOrderText());
            }
            if (disabled()) {
                // 停用：格子仍画出来（让玩家看见这条总线认过哪些类别），但说明为什么点不动
                lines.add(Component.translatable(WARN_LANG + "title")
                    .withStyle(net.minecraft.ChatFormatting.RED).getVisualOrderText());
                lines.add(Component.translatable(WARN_LANG + "hint").getVisualOrderText());
                lines.add(Component.translatable(CONFIG_LANG + "disabled.tip").getVisualOrderText());
            } else if (autoCollect()) {
                lines.add(Component.translatable(lang() + "auto.locked.tip").getVisualOrderText());
            } else {
                // 条上只读展示：明确告诉玩家「去哪改勾选」
                lines.add(Component.translatable(lang() + "click").getVisualOrderText());
            }
            if (!disabled()) {
                lines.add(Component.translatable(lang() + "wheel").getVisualOrderText());
            }
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        // 「详细配置…」按钮：说清它是干什么的（本模组 GUI 不会自动渲染 tooltip）
        // H 组：它同时是「从「只看已勾选」切到完整列表」的唯一入口，因此这里必须把这件事说出来。
        if (inConfig(mouseX, mouseY)) {
            final List<FormattedCharSequence> lines = new ArrayList<>(5);
            lines.add(Component.translatable(CONFIG_LANG + "button.tip").getVisualOrderText());
            lines.add(Component.translatable(CONFIG_LANG + "button.hint").getVisualOrderText());
            lines.add(Component.translatable(lang() + "filtered.tip").getVisualOrderText());
            if (autoCollect()) {
                lines.add(Component.translatable(CONFIG_LANG + "auto.hint").getVisualOrderText());
            }
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        // 条右侧的「普通」按钮：说清「点了会发生什么、之后怎么回来」
        if (inBarForce(mouseX, mouseY)) {
            final List<FormattedCharSequence> lines = new ArrayList<>(4);
            lines.add(Component.translatable(source.forceNormalLang() + "button.tip")
                .withStyle(net.minecraft.ChatFormatting.WHITE).getVisualOrderText());
            lines.add(Component.translatable(source.forceNormalLang() + "button.hint")
                .withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
            if (disabled()) {
                lines.add(Component.translatable(WARN_LANG + "title")
                    .withStyle(net.minecraft.ChatFormatting.RED).getVisualOrderText());
            }
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        if (inToggle(mouseX, mouseY)) {
            final List<FormattedCharSequence> lines = new ArrayList<>(5);
            lines.add(Component.translatable(lang() + "auto." + (autoCollect() ? "on" : "off"))
                .getVisualOrderText());
            lines.add(Component.translatable(lang() + "auto.toggle.tip").getVisualOrderText());
            if (autoCollect()) {
                addReclaimedList(lines);
            }
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        if (inPrev(mouseX, mouseY) || inNext(mouseX, mouseY) || inPageText(mouseX, mouseY)) {
            final List<FormattedCharSequence> lines = new ArrayList<>(3);
            lines.add(Component.translatable(PAGE_LANG + "title",
                pageOf(clampScroll(scroll, count)), pageCount(count)).getVisualOrderText());
            lines.add(Component.translatable(needsScroll(count) ? PAGE_LANG + "tip" : PAGE_LANG + "single")
                .getVisualOrderText());
            if (needsScroll(count)) {
                lines.add(Component.translatable(lang() + "wheel").getVisualOrderText());
            }
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        if (contains(mouseX, mouseY)) {
            final List<FormattedCharSequence> lines = new ArrayList<>(10);
            lines.add(Component.translatable(autoCollect() ? lang() + "auto.title" : source.barTitle())
                .getVisualOrderText());
            lines.add(Component.translatable(autoCollect() ? lang() + "auto.tip" : source.barTitleTip())
                .getVisualOrderText());
            // H 组：把「条上只列已勾选 + 去哪看完整列表」明说出来（本模组 GUI 不会自动渲染 tooltip，
            // 因此这条说明是玩家唯一的线索：看不到的类别不是不存在，而是还没勾）。
            lines.add(Component.translatable(lang() + "filtered.tip").getVisualOrderText());
            if (autoCollect()) {
                // 用户要求：自动模式下必须「显示一下是回收哪些东西」
                addReclaimedList(lines);
            } else {
                // 手动模式 / 输出总线：把「当前已选的类别摘要」完整列出（条上只放得下图标）
                addSelectedSummary(lines);
            }
            if (!linkedChamber().isEmpty()) {
                lines.add(Component.translatable(lang() + "linked", linkedChamber()).getVisualOrderText());
            }
            lines.add(Component.translatable(lang() + (targetStrategy() ? "strategy.target" : "strategy.materials"))
                .getVisualOrderText());
            lines.add(Component.translatable(lang() + "source.tip").getVisualOrderText());
            if (needsScroll(count)) {
                final int scrolled = clampScroll(scroll, count);
                lines.add(Component.translatable(lang() + "scroll.tip",
                    scrolled + 1, Math.min(count, scrolled + VISIBLE_MAX), count).getVisualOrderText());
            }
            if (!autoCraftingEnabled() && !disabled()) {
                lines.add(Component.translatable(lang() + "gate.off.tip").getVisualOrderText());
            }
            if (disabled()) {
                lines.add(Component.translatable(WARN_LANG + "title")
                    .withStyle(net.minecraft.ChatFormatting.RED).getVisualOrderText());
                lines.add(Component.translatable(WARN_LANG + "reason", source.disabledReachableCount())
                    .getVisualOrderText());
                lines.add(Component.translatable(WARN_LANG + "hint").getVisualOrderText());
            } else {
                lines.add(Component.translatable(lang() + "wheel").getVisualOrderText());
            }
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        return false;
    }

    /**
     * 往 tooltip 里追加「自动回收正在回收哪些类别」这一行（只在自动模式、且真的有类别时加）。
     * <p>用户要求：自动模式下类别按钮禁用，因此必须用文字说清「那现在到底在收什么」。</p>
     */
    private void addReclaimedList(final List<FormattedCharSequence> lines) {
        final String names = selectedNames();
        if (names.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(lang() + "auto.list", names)
            .withStyle(net.minecraft.ChatFormatting.AQUA).getVisualOrderText());
    }

    /**
     * 往 tooltip 里追加「本条总线当前已选的类别摘要」这一行（只在真的有已选类别时加）。
     * <p>用户要求主界面「只读展示当前已选的类别摘要」：格子只有 20px 宽放不下名字，
     * 因此每条 tooltip 都把已选集完整列出来（不受翻页可见范围限制）。</p>
     */
    private void addSelectedSummary(final List<FormattedCharSequence> lines) {
        final String names = selectedNames();
        if (names.isEmpty()) {
            return;
        }
        lines.add(Component.translatable(lang() + "summary.selected", names)
            .withStyle(net.minecraft.ChatFormatting.GREEN).getVisualOrderText());
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // 停用提示按钮：只在<b>类别条界面</b>里存在（普通界面按用户第 2 条不再显示「已停用原因」那套说明）。
        if (executorMode() && disabled() && inWarnButton(mouseX, mouseY)) {
            if (button == 0) {
                source.toggleOverlay(); // 半透明叠加层的显隐（纯客户端；判定结果仍由服务端权威同步）
            }
            return true;
        }
        if (!executorMode()) {
            // 普通界面：只剩那颗常驻的「普通 / 总线」切换按钮可拦，其余一律放行 —— 9 个过滤器槽照常可编辑
            //（这就是普通总线界面）。按钮按同一批几何常量命中（条下方留白带的右端），不会误吞别的点击。
            if (inNormalToggle(mouseX, mouseY)) {
                if (button == 0) {
                    // 用户第 2 条：同一颗按钮在普通界面里「来回切换」（强制普通 ⇄ 恢复为自动判定的总线界面）
                    source.toggleForceNormal(!forceNormal());
                }
                return true;
            }
            return false; // 一点不拦，界面完全等同 RS 原版
        }
        // 翻页按钮（停用时控制带不存在，inPrev / inNext 直接为假）
        if (inPrev(mouseX, mouseY)) {
            if (button == 0) {
                // 翻页上限 = 条上真实显示的那一份（H 组：只列已勾选），与 ▶ 按钮同一口径
                scroll = clampScroll(scroll - VISIBLE_MAX, visibleCategories().size());
            }
            return true; // 无论能不能翻都吞掉：这一段本来就属于本控件，不该漏给原版
        }
        if (inNext(mouseX, mouseY)) {
            if (button == 0) {
                scroll = clampScroll(scroll + VISIBLE_MAX, visibleCategories().size());
            }
            return true;
        }
        // 自动 / 手动开关：只在输入总线侧提供
        if (inToggle(mouseX, mouseY)) {
            if (button == 0) {
                source.toggleAuto(!source.autoCollect());
            }
            return true;
        }
        // 「详细配置…」：本界面上<b>唯一</b>的勾选入口（用户要求：条上只读展示）
        if (inConfig(mouseX, mouseY)) {
            if (button == 0) {
                openConfigScreen();
            }
            return true;
        }
        // 条右侧的「普通」按钮：点了 = 强制为普通总线（服务端权威，回传后界面切换）
        if (inBarForce(mouseX, mouseY)) {
            if (button == 0) {
                source.toggleForceNormal(true);
            }
            return true;
        }
        if (!contains(mouseX, mouseY)) {
            return false; // 点在类别条外：放行原版逻辑
        }
        // 类别条上不再接受任何勾选 / 切换（勾选动作统一在子界面）：
        // 但依旧吞掉点击 —— 条正盖着 9 个过滤器资源槽，不能把它们漏给原版槽位逻辑
        return true;
    }

    /**
     * 打开「详细配置」子界面（{@link BusCategoryConfigScreen}）。
     *
     * <p><b>只带三样东西过去</b>：类别快照（画列表）、玩家当前的显式勾选（编辑起点）、
     * 提交回调（{@link Source#toggleSelection}，即既有服务端包）。子界面自己不碰网络、
     * 不缓存服务端状态，因此「服务端权威」这条线只有这一处出口。</p>
     *
     * <p><b>停用态一律不放行</b>：归属未确定 / 被干扰时不得通过子界面绕过（按钮在停用时也不画、不命中，
     * 这里再判一次是双保险，避免任何调用路径绕过它）。</p>
     */
    private void openConfigScreen() {
        if (disabled()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new BusCategoryConfigScreen(minecraft.screen, categories(),
            source.configSelection(), source.hasAutoToggle() && source.autoCollect(),
            source::toggleSelection));
    }

    /**
     * 鼠标滚轮：鼠标落在「类别条内」时生效 —— <b>只翻看，不改勾选</b>（用户要求：勾选动作统一挪进子界面）。
     * <p>翻看不跳整页（整页交给 ◀ ▶ 按钮）；全都看得见时不消费滚轮，避免抢走其它潜在用途。</p>
     * <p>滚轮走 {@code ContainerEventHandler#mouseScrolled} 的默认分发（按 {@code children} 逐个询问），
     * 因此本控件必须真的挂在界面的 children 里（宿主 Mixin 用 {@code addRenderableWidget} 做到）。</p>
     */
    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (!executorMode()) {
            return false;
        }
        if (!contains(mouseX, mouseY) || scrollY == 0) {
            return false; // 鼠标不在类别条上 / 没有垂直滚动量：不消费，别抢别的用途
        }
        final int count = visibleCategories().size();
        if (!needsScroll(count)) {
            return false; // 全都看得见：不消费滚轮，避免抢走其它潜在用途
        }
        final int delta = scrollY > 0 ? -1 : 1;
        final int next = clampScroll(clampScroll(scroll, count) + delta, count);
        if (next == scroll) {
            return false;
        }
        scroll = next;
        return true;
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput narrationElementOutput) {
        // 类别条不做播报
    }
}
