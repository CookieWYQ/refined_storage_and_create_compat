package cretae.cookiewyq.rs_create_compat.client.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 输出总线界面顶部的「<b>工位归属</b>」只读徽标（第 104 轮 B-4）。
 *
 * <h2>它显示什么</h2>
 * <pre>
 *   本总线朝向：动力冲压机 @(-16, -60, 12)      ← 第 1 行
 *   该工位推导：冲压 · 第 1、2 步                ← 第 2 行（不一致时整行黄字）
 * </pre>
 * <p>两行文字由调用方（界面）用<b>语言键</b>构造后传进来：本类<b>刻意不引用任何语言键</b>，
 * 因此它既不会制造孤儿键，也不会把「文案」和「排版」耦合在一起（同一套排版可以被输出总线 /
 * 输入总线 / 未来的流程编排界面复用）。</p>
 *
 * <h2>为什么黄字而不是拦</h2>
 * <p>用户第 104 轮的口径：推导出的默认集<b>立即生效</b>，玩家的手动勾选降级成<b>覆盖项</b>。
 * 玩家覆盖成一个「与朝向的工位不一致」的集合是<b>合法</b>的（例如故意让一条总线跨用途），
 * 因此这里只提示、<b>绝不拦</b>：判定层（{@code stationAcceptsPush} / 类别归属）仍然只按数据说话。</p>
 *
 * <h2>硬规则</h2>
 * <ul>
 *     <li>文字<b>一律</b> {@code dropShadow = false}（与 RS 界面其余文字一致，见既有界面类注释）；</li>
 *     <li><b>绝不截断</b>：本类不做任何裁剪（用户硬规则见 {@code docs/TEXT_NO_ELLIPSIS_RULES.md}）；
 *     调用方用 {@link #width(Font, Component, Component)} 预留足够宽度，放不下就换行 / 加宽面板；</li>
 *     <li>纯只读：不读世界、不发包、不改任何状态 —— 数据由 {@code RsccBusStationSync} 的客户端镜像提供，
 *     文案由界面提供，本类只负责「在哪儿、什么颜色地画出来」。</li>
 * </ul>
 */
public final class RsccStationBadge {
    /** 单行文字高度（MC 字体行高）。 */
    public static final int LINE_HEIGHT = 9;
    /** 两行之间的间距。 */
    public static final int LINE_GAP = 2;
    /** 第 1 行（「本总线朝向」）颜色：次要信息，灰白。 */
    private static final int COLOR_FACING = 0xFFC0C0C0;
    /** 第 2 行正常颜色：与界面正文一致。 */
    private static final int COLOR_STATION = 0xFFE0E0E0;
    /** 第 2 行「不一致」颜色：<b>黄字</b>（只提示、不拦）。 */
    private static final int COLOR_MISMATCH = 0xFFFFF055;

    private RsccStationBadge() {
    }

    /** 徽标整体高度（两行 + 行距）；调用方据此排版（顶部留白 / 与下方分节标题的间距）。 */
    public static int height() {
        return LINE_HEIGHT * 2 + LINE_GAP;
    }

    /** 徽标需要的最小宽度（两行里较宽的那一行；放不下时调用方换行 / 加宽，<b>不要截断</b>）。 */
    public static int width(final Font font,
                            @Nullable final Component facingLine,
                            @Nullable final Component stationLine) {
        int width = 0;
        if (facingLine != null) {
            width = Math.max(width, font.width(facingLine));
        }
        if (stationLine != null) {
            width = Math.max(width, font.width(stationLine));
        }
        return width;
    }

    /**
     * 画两行徽标。
     *
     * @param graphics    绘制上下文
     * @param font        字体（宽度 / 行高都取自它，不写死像素）
     * @param x           左起点（Screen 坐标）
     * @param y           第 1 行的顶（Screen 坐标）
     * @param facingLine  第 1 行：本总线朝向的方块 + 坐标（{@code null} = 不画这一行）
     * @param stationLine 第 2 行：该工位推导 / 不一致提示（{@code null} = 不画这一行）
     * @param mismatch    第 2 行是否「与朝向的工位不一致」⇒ 黄字（<b>只提示、不拦</b>）
     */
    public static void render(final GuiGraphics graphics, final Font font,
                              final int x, final int y,
                              @Nullable final Component facingLine,
                              @Nullable final Component stationLine,
                              final boolean mismatch) {
        int lineY = y;
        if (facingLine != null) {
            graphics.drawString(font, facingLine, x, lineY, COLOR_FACING, false);
            lineY += LINE_HEIGHT + LINE_GAP;
        }
        if (stationLine != null) {
            graphics.drawString(font, stationLine, x, lineY,
                mismatch ? COLOR_MISMATCH : COLOR_STATION, false);
        }
    }

    /**
     * 没有工位数据时的「判不出」提示行绘制（同样只提示、不拦）。
     * <p>为什么单独一个入口：调用方需要在「服务端还没推来 / 朝向格判不出」时也能画出<b>一句</b>
     * 说明，而这句说明的文案同样是语言键（由调用方给），排版与 {@link #render} 完全一致。</p>
     */
    public static void renderSingleLine(final GuiGraphics graphics, final Font font,
                                        final int x, final int y, final Component line) {
        graphics.drawString(font, line, x, y, COLOR_FACING, false);
    }

    /**
     * 画两行徽标，并在超出 {@code maxWidth} 时<b>整体缩字号</b>（{@link RsccTextFit}，下限
     * {@link RsccTextFit#MIN_SCALE} 倍）完整显示，<b>绝不截断</b>。
     *
     * <p><b>为什么需要这个入口</b>：本屏面板宽度固定（内容宽 236px），而「朝向方块名」可能来自
     * 任意第三方模组（超长 id / 超长译名）。调用方只需说「这条线可用多宽」，排版与兜底都留在这里，
     * 工程里就不会再出现第二套「按像素裁字」的做法（用户第 104 轮口径，见
     * {@code docs/TEXT_NO_ELLIPSIS_RULES.md}）。窄到 0.6 倍仍放不下的极端名字，
     * 完整原文由方块 tooltip / 界面其它位置给出，本方法仍完整绘制、不做任何省略。</p>
     */
    public static void renderFit(final GuiGraphics graphics, final Font font,
                                 final int x, final int y, final int maxWidth,
                                 @Nullable final Component facingLine,
                                 @Nullable final Component stationLine,
                                 final boolean mismatch) {
        int lineY = y;
        if (facingLine != null) {
            RsccTextFit.drawFitLine(graphics, font, facingLine, x, lineY, maxWidth, COLOR_FACING);
            lineY += LINE_HEIGHT + LINE_GAP;
        }
        if (stationLine != null) {
            RsccTextFit.drawFitLine(graphics, font, stationLine, x, lineY, maxWidth,
                mismatch ? COLOR_MISMATCH : COLOR_STATION);
        }
    }
}
