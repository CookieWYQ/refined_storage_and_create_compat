package cretae.cookiewyq.rs_create_compat.client.widget;

/**
 * 定量保持器「目标数量」控件行的几何唯一实现（基础版与高级版共用）。
 *
 * <h2>为什么单独抽一个常量类</h2>
 * <p>用户反馈：「输入框太小，1 亿 mB（= 1000 桶）在框里只剩一串 0；加大就会和后面的按钮重叠」。
 * 解决办法不是「再挪一点」，而是把这一行的左右顺序定死：<b>标签 → [−] → 输入框 → [+] → 状态</b>，
 * 三者几何由本类给出，界面只引用常量，几何自检（
 * {@code tools/selfcheck_round50_keeper_fluid_display.py}）也按同一批常量穷举断言
 * 「两两不重叠、不越界、且输入框装得下 100000000 的显示文本」——<b>结构上不可能</b>再退回到
 * 「加大输入框就压住按钮」。</p>
 *
 * <h2>坐标口径</h2>
 * <p>全部是「相对 {@code leftPos} / {@code topPos} 的界面坐标」（即 Menu 坐标，
 * 没有精灵 −1 那回事：这些是 Java 运行时控件，不烘焙进背景 PNG）。</p>
 *
 * <h2>本轮几何变更（对照旧值）</h2>
 * <pre>
 * 基础版（面板 210×166）
 *   旧：− (76,19,18×14) / 框 (96,21,42×12) / + (140,19,18×14) / 单位文字 x=162
 *   新：− (50,20,18×14) / 框 (72,20,86×14) / + (142,20,18×14) / 单位文字 x=164
 *       开关上移 2px（销毁 y39→37、自动合成 y59→57），腾出 y36 那条给桶换算提示
 * 高级版（面板 210×210，行 y=22/46/70/94，行距 24 不变 —— 槽位位置烘焙在背景 PNG 里）
 *   上一轮：− (54,+4,10×12) / 框 (66,+4,70×12) / + (138,+4,10×12) / 单位文字 x=26
 *           / 两列 ✓✗ 开关 x=148、170（[+] 右缘 148 与开关列 148 **零间隙**）
 *   本轮：  − (29,+4,12×12) / 框 (45,+4,70×12) / + (119,+4,12×12)
 *           / 自动合成开关 (136,+2,16×14) / 过量销毁开关 (164,+2,16×14)
 *       ① 删掉每行的「个 / mB」单位文字（与基础版同一条口径：单位与桶换算进输入框 tooltip）；
 *       ② 两列开关之间留 12px（视觉上明确是**两个不同功能**），右缘 180 距插件槽列 188 留 8px；
 *       ③ 间距统一 ≥ 4px（上一轮 [+] 与开关列 0px、开关之间 6px）。
 *   第 87 轮（列标签）：两列列标题「自动 / 销毁」（en: Auto / Void）居中于列心 144 / 172，
 *       文字行顶 y=15、行高 9、单标签宽 ≤ 24；纵向：标题 6..14 / 列标签 15..23 / 第一行控件 24 起。
 *   第 90 轮（本次）：
 *       · 列标签改成<b>完整词、两行</b>：「自动 / 合成」「过量 / 销毁」（en: Auto / Craft、Over / flow）；
 *         两行各 9px ⇒ 标签块 18px 高，放在 y={@link #COLUMN_LABEL_Y} 4..21（第一行控件 26 起）
 *       · 因为英文标题 `Advanced Resource Quantity Keeper` 宽 184px、会占满 6..14 整行，
 *         标题先按 {@link #TITLE_BAND_W} = 116px 折行（设计文档 V3 §4 给标题留的就是 8..116），
 *         折行后标题最宽 ≤ 116 < 列标签左沿 130 ⇒ 两行标签与标题<b>横向不相交</b>；
 *       · 输入框 70×12 → <b>74×14</b>（文本区 61 → 65px），[−]/[+] 由 12×12 → 10×14
 *         （点击面积 144 → 140 ≈ 不变），全行控件统一 14px 高；
 *       · 行内几何：− (29,+4,10×14) / 框 (43,+4,74×14) / + (121,+4,10×14)
 *         / 自动合成 (135,+4,16×14) / 过量销毁 (164,+4,16×14)；
 *         两列列心 143 / 172（相距 29 ⇒ 英文两行标签最宽 26/24 时仍有 4px 缝），
 *         右列右缘 180 距插件槽列 188 仍留 8px（与第 84 轮同一口径，未放松）。
 * </pre>
 * <p>关键数字：输入框文本区 = {@link RsccNumberField#textWindow(int)} = 宽 − 9。
 * {@code 100,000,000} 共 11 字符 = 58px，故基础版 86px（文本区 77px）与高级版 74px（文本区 65px）
 * 都能把「一百万桶的 mB 原值」<b>连千分位一起</b>一眼看全，int 上限 {@code 2147483647}（60px）也放得下。</p>
 */
public final class RsccKeeperGeometry {
    /** 输入框高度（原版 {@code EditBox} 的 8px 文字 + 上下各 3px 内边距）。 */
    public static final int BOX_H = 14;
    /**
     * 高级版输入框高度 = <b>14</b>（第 90 轮由 12 抬高）。
     * <p>行距 24 完全放得下：行顶 +4 起 ⇒ 26..40，与 ± / 开关同高。</p>
     */
    public static final int ROW_BOX_H = 14;
    /** ± 按钮高度（与输入框同高，视觉上是一根控制条）。 */
    public static final int BTN_H = 14;
    /** 高级版 ± 按钮高度 = 14（第 90 轮由 12 抬高，与输入框 / 开关统一）。 */
    public static final int ROW_BTN_H = 14;

    // ==================== 基础版（QuantityKeeperScreen）====================
    /** 输入框左沿。 */
    public static final int BASIC_BOX_X = 72;
    /** 输入框宽度：文本区 77px ⇒ 11 字符的「100,000,000」连千分位放得下。 */
    public static final int BASIC_BOX_W = 86;
    /** 输入框上沿（原 21，下移 1px 与 ± 按钮对齐）。 */
    public static final int BASIC_BOX_Y = 20;
    /** [−] 按钮左沿（输入框左侧，两者间隔 4px）。 */
    public static final int BASIC_MINUS_X = 50;
    /** [+] 按钮左沿（输入框右缘 158 + 4px）。 */
    public static final int BASIC_PLUS_X = 162;
    /** [−] / [+] 宽度。 */
    public static final int BASIC_BTN_W = 18;
    /**
     * 单位 / 状态文字左沿（画在 <b>y=15</b> 那一行 = 输入框上方，与输入框 / 按钮纵向完全不重叠）。
     * <p>本轮把输入框加宽到 86px 之后，这一行已经没有「既不压 [+]（162..179）也不压插件槽列（188 起）」
     * 的空位了：en 的单位文字最宽 24px（items），任何位置都会撞上其中之一。因此基础版
     * <b>不再单独画单位</b>——单位与桶换算一起放进输入框 tooltip（见
     * {@link RsccNumberField#bucketTooltip} 与 {@code …quantity_keeper.target_tooltip} 文案），
     * 玩家鼠标停在输入框上就能看到「流体/气体按 mB，1 桶 = 1000 mB」。此常量保留给未来的紧凑单位字形。</p>
     */
    public static final int BASIC_UNIT_X = 177;
    /** 单位 / 状态文字的最右界（超过就会压到插件槽列 188）。 */
    public static final int BASIC_UNIT_MAX_RIGHT = 188;
    /** 开关按钮列 x（销毁 / 自动合成，与旧值一致）。 */
    public static final int BASIC_TOGGLE_X = 76;
    /** 开关按钮上沿（销毁；自动合成 = 此值 + 20）。 */
    public static final int BASIC_TOGGLE_Y = 37;
    /** 开关按钮高度。 */
    public static final int BASIC_TOGGLE_H = 12;
    /**
     * 「目标数量」标签右端对齐到这个 x（左端随字宽动态左移）。
     * <p>标签与单位文字共用 <b>y=15</b> 那一行（输入框上方）：标签占左端（右端 48 ≤ [−] 左沿 50），
     * 单位占右端（172..188），中间全是空的 —— 与下一行的输入框 / 按钮纵向错开，几何上不可能重叠。</p>
     */
    public static final int BASIC_LABEL_RIGHT = 46;

    // ==================== 高级版（AdvancedQuantityKeeperScreen）====================
    /**
     * 行距 / 行首 y。
     * <p><b>必须保持 22 / 24</b>：4 行标记槽的位置是<b>烘焙进背景 PNG</b> 的
     * （{@code advanced_quantity_keeper.png} 的槽框外框实测在 y=24 / 48 / 72 / 96，
     * 即 Menu 槽位 y = 25 / 49 / 73 / 97），本类的几何只负责「槽位右边那排 Java 控件」，
     * 一旦改行距就会与背包贴图错位 —— 因此提示文字只能在 24px 的行内解决，不能靠加行距腾地方。</p>
     */
    public static final int ROW_FIRST_Y = 22;
    public static final int ROW_STEP = 24;
    /** [−] 按钮左沿（槽框右沿 26 之后留 3px；本轮起这里不再画单位文字）。 */
    public static final int ROW_MINUS_X = 29;
    /** 输入框左沿（[−] 右缘 39 + 4px）。 */
    public static final int ROW_BOX_X = 43;
    /**
     * 输入框宽度：文本区 {@link RsccNumberField#textWindow(int)} = 74 − 9 = <b>65px</b>。
     * <p>本轮按用户要求「把输入框拉大一些」：70×12 → <b>74×14</b>（面积 +23%）。
     * 放得下 {@code 100,000,000}（58px）与 int 上限 {@code 2147483647}（60px）——
     * 与基础版一样，最大目标值不会被裁掉最后一位。</p>
     * <p>宽度是有上限的：行内带宽 25..188 被「[−] / 框 / [+] / 两列开关 / 8px 右留白」占满，
     * 再加宽就必须压缩开关列或右留白（会放松既有几何自检）⇒ 74 是本轮在不放松任何
     * 既有断言（含「右列右缘距插件槽列 ≥ 8px」）前提下的最大值，另外用高度 12→14 补足观感。</p>
     */
    public static final int ROW_BOX_W = 74;
    /** 输入框相对行顶的 y 偏移。 */
    public static final int ROW_BOX_DY = 4;
    /** [+] 按钮左沿（输入框右缘 117 + 4px）。 */
    public static final int ROW_PLUS_X = 121;
    /**
     * ± 按钮宽度（12 → <b>10</b>）。
     * <p>为什么可以变窄：同一轮把按钮高度 12 → 14（{@link #ROW_BTN_H}），
     * 点击面积 12×12 = 144 → 10×14 = 140（基本不变），换来输入框 4px 宽度；
     * 整行控件（± / 输入框 / 开关）统一 14px 高。</p>
     */
    public static final int ROW_BTN_W = 10;
    /**
     * 单位 / 状态文字左沿。
     * <p><b>本轮起不再绘制</b>：这一带（x=26..50）已被 [−] 左沿 29 接管，且基础版也早已改成
     * 「单位与桶换算一起进输入框 tooltip」。常量保留，因为工程既有的 GUI 布局校验
     * （{@code tmp_textures/verify_gui_layout.py#verify_adv_keeper_no_stored_region}）仍按字面量读它。</p>
     */
    public static final int ROW_UNIT_X = 26;
    /** 单位 / 状态文字的最右界（同上，保留给旧校验脚本）。 */
    public static final int ROW_UNIT_MAX_RIGHT = 50;
    /**
     * 「自动合成」开关列左沿。
     * <p>与 {@link #OVERFLOW_BTN_X} 之间留 <b>13px</b>：上一轮两列只隔 6px、且都画成红色 ✗，
     * 玩家看到的是「每行两个一模一样的 ✗」（用户实机反馈）。本轮仍保持两列拉开，
     * 并把列标签改成两行的完整词（自动 / 合成、过量 / 销毁），功能区分不再依赖间距。
     * <p>列心 = 135 + 8 = <b>143</b>；与右列列心 172 相距 29px ——
     * 英文两行标签最宽 26px（Craft）/ 24px（Over），缝 = 29 − 25 = 4px（不放松既有 ≥4px 判据）。</p>
     */
    public static final int AUTOCRAFT_BTN_X = 135;
    /** 「过量销毁」开关列左沿（列心 172；右缘 180，距插件槽列 188 仍留 8px）。 */
    public static final int OVERFLOW_BTN_X = 164;
    /** 开关按钮宽度（16：两行列标签的列心间距 + 原 ✓/✗ 按钮的既有宽度）。 */
    public static final int TOGGLE_BTN_W = 16;
    /** 开关按钮高度（14：与 ± / 输入框同高，整行控件是一条 14px 的控制条）。 */
    public static final int TOGGLE_BTN_H = 14;
    /** 开关按钮相对行顶的 y 偏移（= {@link #ROW_BOX_DY}：与输入框 / ± 顶边对齐）。 */
    public static final int TOGGLE_DY = 4;

    // ==================== 高级版：两列列标签（开关列正上方，两行完整词）====================
    /**
     * 列标签第一行的行顶 y（精灵坐标）。
     * <p><b>本轮为什么要两行</b>：用户原话「不能只显示『自动和销毁』，容易误解为『自动销毁』；
     * 应显示『自动合成』和『过量销毁』。一行显示不下、字太小时，应显示为两行。」
     * 两列列心只相距 29px，一行的完整词中文 36px / 英文 40px 必然相交 ⇒ 拆成两行
     * （每行：中文 2 字 18px、英文 ≤26px），两行各 9px ⇒ 标签块高 {@link #COLUMN_LABEL_H} = 18px。</p>
     *
     * <h2>垂直空间从哪来（关键几何事实）</h2>
     * <p>第一行 Java 控件从 {@code ROW_FIRST_Y 22 + ROW_BOX_DY 4 = 26} 起；
     * 标题在 y={@link #TITLE_Y} 6。标题若是一行，6..14 之外只剩 15..23 这 9px，放不下两行。
     * 而英文标题 {@code Advanced Resource Quantity Keeper} 实测 184px 宽，会占满 6..14 整行 ——
     * 因此界面把标题按 {@link #TITLE_BAND_W} = 116px <b>折行</b>（设计文档 V3 §4 给标题留的就是 8..116），
     * 折行后标题最宽 100px < 130（列标签左沿）⇒ 标题与列标签<b>横向永不相交</b>，
     * 于是列标签可以安全地占 <b>4..12 / 13..21</b> 两行（与标题同高、与第一行控件隔 5px）。</p>
     */
    public static final int COLUMN_LABEL_Y = 4;
    /** 列标签单行行高（= 原版字体 {@code font.lineHeight}）。 */
    public static final int COLUMN_LABEL_LINE_H = 9;
    /** 列标签块总高（两行 = 18px）：几何断言用它当矩形高度。 */
    public static final int COLUMN_LABEL_H = 18;
    /**
     * 单个列标签块的最大宽度 = 26px（= 两行里较宽那一行的宽度）。
     * <p>两列列心相距 {@code OVERFLOW_BTN_X - AUTOCRAFT_BTN_X = 29px}；两个标签块都居中于各自列心时，
     * 它们之间的缝 = {@code 29 - (w左 + w右)/2}。最坏情况英文 26（Craft）/ 24（Over）⇒ 缝 4px
     * （与本类其它间距同一口径）；右标签块右缘 = 172 + 12 = 184，距插件槽列 188 仍留 4px。</p>
     */
    public static final int COLUMN_LABEL_MAX_W = 26;
    /**
     * 标题带宽度 = 116px（设计文档 {@code ADV_QUANTITY_KEEPER_GUI_DOC_V3.md} §4.1 的留白区
     * 精灵 (8,4) → (116,18)）。
     * <p>标题超过这个宽度时按单词折行（{@code Font#split}）—— 不是截断、不改文案，
     * 保证它不会压到右边的列标签带（130..184）。中文标题 81px 不会折行，英文才会折成两行。</p>
     */
    public static final int TITLE_BAND_W = 116;
    /** 标题第一行的行顶 y（与列标签第一行同一高度带）。 */
    public static final int TITLE_Y = 6;
    /** 标题行高（折行后第二行 y = {@link #TITLE_Y} + 9 = 15）。 */
    public static final int TITLE_LINE_H = 9;

    /**
     * 列标签左沿：水平居中于对应开关列（列心 = 列左沿 + 开关宽 / 2）。
     * <p>唯一实现：界面只调这一个方法；几何自检
     * （{@code tools/selfcheck_round84_adv_keeper_layout.py}）按同一公式复算「标签列心 == 开关列心」，
     * 并检查标签矩形与资源行 / 插件槽列 / 面板边界都不相交。</p>
     */
    public static int columnLabelX(final int columnX, final int textWidth) {
        return columnX + TOGGLE_BTN_W / 2 - textWidth / 2;
    }

    private RsccKeeperGeometry() {
    }
}
