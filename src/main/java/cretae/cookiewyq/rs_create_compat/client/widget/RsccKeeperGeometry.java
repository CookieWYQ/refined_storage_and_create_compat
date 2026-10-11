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
 *   旧：− (30,+4,10×12) / 框 (42,+4,40×12) / + (84,+4,10×12) / 状态 x=97 / 开关列 134 / 162
 *   新：− (26,+4,10×12) / 框 (38,+4,68×12) / + (108,+4,10×12) / 状态 x=122 / 开关列 148 / 170
 *       桶换算提示右端对齐到 [+] 左沿（x=106），挤在「本行下沿 → 下一行控件」之间
 * </pre>
 * <p>关键数字：输入框文本区 = {@link RsccNumberField#textWindow(int)} = 宽 − 9。
 * {@code 100,000,000} 共 11 字符 = 58px，故基础版 86px（文本区 77px）与高级版 68px（文本区 59px）
 * 都能把「一百万桶的 mB 原值」<b>连千分位一起</b>一眼看全。</p>
 */
public final class RsccKeeperGeometry {
    /** 输入框高度（原版 {@code EditBox} 的 8px 文字 + 上下各 3px 内边距）。 */
    public static final int BOX_H = 14;
    /** 高级版输入框高度：与原有的 12px 行高一致（行距只有 24px，放不下 14px）。 */
    public static final int ROW_BOX_H = 12;
    /** ± 按钮高度（与输入框同高，视觉上是一根控制条）。 */
    public static final int BTN_H = 14;
    /** 高级版 ± 按钮高度。 */
    public static final int ROW_BTN_H = 12;

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
    /** [−] 按钮左沿（槽框右沿 25 之后留 28px 给单位文字：单位占 26..50）。 */
    public static final int ROW_MINUS_X = 54;
    /** 输入框左沿（[−] 右缘 64 + 2px）。 */
    public static final int ROW_BOX_X = 66;
    /** 输入框宽度：文本区 {@link RsccNumberField#textWindow(int)} = 61px。
     * <p>放得下 {@code 100,000,000}（58px）；连 int 上限 {@code 2,147,483,647}（66px）也能退成
     * 纯数字 {@code 2147483647}（60px）完整显示。</p> */
    public static final int ROW_BOX_W = 70;
    /** 输入框相对行顶的 y 偏移。 */
    public static final int ROW_BOX_DY = 4;
    /** [+] 按钮左沿（输入框右缘 136 + 2px）。 */
    public static final int ROW_PLUS_X = 138;
    /** ± 按钮宽度（与旧值一致）。 */
    public static final int ROW_BTN_W = 10;
    /** 单位 / 状态文字左沿（画在 [−] 左侧：槽框右沿 25 之后、[−] 左沿 54 之前）。 */
    public static final int ROW_UNIT_X = 26;
    /** 单位 / 状态文字的最右界（= [−] 左沿 - 4px；en 的 items 有 24px，26+24=50 仍在界内）。 */
    public static final int ROW_UNIT_MAX_RIGHT = 50;
    /** 「自动合成」列左沿（本轮从 134 右移，给输入框腾宽度）。 */
    public static final int AUTOCRAFT_BTN_X = 148;
    /** 「过量销毁」列左沿（本轮从 162 右移，与自动合成列留 6px 缝）。 */
    public static final int OVERFLOW_BTN_X = 170;
    /** 两列开关按钮宽度（与旧值一致）。 */
    public static final int TOGGLE_BTN_W = 16;
    /**
     * 两列标题的 hover / 重叠判定半宽。
     * <p>两列列心相距 22px，标题按 0.75 缩放后实际只占约 14px，因此半宽取 10px：
     * 既覆盖实际文字，又保证「列心 - 10」与「另一列心 + 10」不重叠（22 &gt; 20）。</p>
     */
    public static final int COL_HEADER_HALF_W = 10;

    private RsccKeeperGeometry() {
    }
}
