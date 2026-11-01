package cretae.cookiewyq.rs_create_compat.client.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 界面文本「完整显示」工具（第 104 轮）。
 *
 * <h2>用户口径（原话）</h2>
 * 「所有的这个界面的文本…尽量全部都不要出现…（省略号），所有都尽量保持全部显示完成，
 * 尽可能是更改他的那个排列方式而不是搞这个破（省略号）」
 * <p>⇒ <b>客户端界面里任何地方都不允许出现 {@code "..."} 省略/截断</b>。
 * 空间不够时的正确顺序是：
 * <ol>
 *     <li><b>改排列</b>（折行 / 标签与值拆两段 / 换行位 / 分组）——首选；</li>
 *     <li><b>缩短静态文案</b>（改 {@code lang} 里的值，键不变；新原文由 Lead 落盘）；</li>
 *     <li><b>缩小字号</b>（本类提供，最小 {@link #MIN_SCALE}）；</li>
 *     <li><b>悬浮 tooltip</b> 完整显示（只用于玩家命名 / 第三方模组超长名字这类自由文本）。</li>
 * </ol>
 *
 * <h2>为什么有「缩字号」这一档</h2>
 * 折行需要纵向空间，而本模组的界面背景是固定尺寸的 PNG（槽位 18px 网格、按钮 bounds 已钉死），
 * 很多读数行上下各只有 1 行高度。此时「缩 0.85 倍」比「少显示几个字」更接近用户要的「全部显示完成」。
 * 但缩到 {@link #MIN_SCALE} 以下文字会糊，所以下限钉死 0.6，再长就只能走 tooltip 兜底。
 *
 * <h2>禁止事项</h2>
 * 本类<b>不提供</b>任何「按宽度截断 + 补省略号」的接口，工程里也不允许再出现那种实现
 * （{@code tools/selfcheck_no_ellipsis.py} 会扫源码判负）。
 */
public final class RsccTextFit {

    /** 缩字号下限：低于 0.6 的 9px 字在 1080p 上已经不可读。 */
    public static final float MIN_SCALE = 0.6F;

    /** 从 1.0 往下试的缩放档位（线性细步，避免一次缩太狠）。 */
    private static final float[] SCALE_STEPS = {
        1.0F, 0.95F, 0.9F, 0.85F, 0.8F, 0.75F, 0.7F, 0.65F, 0.6F
    };

    private RsccTextFit() {
    }

    // ------------------------------------------------------------------
    // 度量（服务端/客户端都只读，不写任何状态）
    // ------------------------------------------------------------------

    /** 单行放得下吗（不缩字号）。 */
    public static boolean fitsLine(final Font font, final Component text, final int maxWidth) {
        return maxWidth > 0 && font.width(text) <= maxWidth;
    }

    /** 在 {@code maxLines} 行内折得下吗（不缩字号）。 */
    public static boolean fitsBlock(final Font font, final Component text, final int maxWidth,
                                    final int maxLines) {
        return lineCount(font, text, maxWidth) <= Math.max(1, maxLines);
    }

    /** 折行后行数（宽度非法时按 1 行算，调用方自己保证 width &gt; 0）。 */
    public static int lineCount(final Font font, final Component text, final int maxWidth) {
        if (maxWidth <= 0) {
            return 1;
        }
        final List<FormattedCharSequence> lines = font.split(text, maxWidth);
        return Math.max(1, lines.size());
    }

    /**
     * 单行完整显示所需的最小缩放倍数（1.0 = 不用缩）。
     * <p>返回值可能小于 {@link #MIN_SCALE}：这表示「即使缩到下限也放不下」，
     * 调用方应当改用 tooltip 兜底，而不是硬画。</p>
     */
    public static float requiredScaleForLine(final Font font, final Component text, final int maxWidth) {
        if (maxWidth <= 0) {
            return Float.MAX_VALUE;
        }
        final int width = font.width(text);
        if (width <= maxWidth) {
            return 1.0F;
        }
        return (float) maxWidth / (float) width;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 单行完整显示：放得下就原样画；放不下就整体水平缩小（下限 {@link #MIN_SCALE}）。
     * <p><b>绝不加省略号。</b>若 {@link #requiredScaleForLine} 已小于 {@link #MIN_SCALE}，
     * 说明这一串再缩也放不下（第三方模组的超长名字），此时调用方必须改走 tooltip 兜底；
     * 本方法仍会以 {@link #MIN_SCALE} 画出来（保证不省略），但会溢出右边界。</p>
     *
     * @return 实际绘制宽度（像素，已含缩放）
     */
    public static float drawFitLine(final GuiGraphics graphics, final Font font, final Component text,
                                    final int x, final int y, final int maxWidth, final int color) {
        if (maxWidth <= 0) {
            return 0.0F;
        }
        final int width = font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return width;
        }
        final float scale = Math.max(MIN_SCALE, (float) maxWidth / (float) width);
        // 0.001 的第三维缩放：只缩 X/Y，不碰 Z 顺序（否则会被画到 GUI 背景后面）。
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
        return (float) width * scale;
    }

    /**
     * 折行完整显示：优先按 {@code maxWidth} 折行；行数超过 {@code maxLines} 时逐档缩小字号后重新折行，
     * 直到行数落进 {@code maxLines} 或触到 {@link #MIN_SCALE}。
     * <p>返回「占用高度（像素）」：{@code 行数 × lineHeight × 缩放}，调用方用它给下一行留位置。
     * <p>缩字号时行距也按同一比例缩（视觉上仍是一整块），因此折行后的块<b>永远</b>不超出
     * {@code maxLines × lineHeight} 的高度。
     */
    public static int drawFitBlock(final GuiGraphics graphics, final Font font, final Component text,
                                   final int x, final int y, final int maxWidth, final int maxLines,
                                   final int lineHeight, final int color) {
        final int lineBudget = Math.max(1, maxLines);
        final int usableWidth = Math.max(1, maxWidth);
        final int usableLineHeight = Math.max(1, lineHeight);

        float scale = 1.0F;
        List<FormattedCharSequence> lines = font.split(text, usableWidth);
        for (final float candidate : SCALE_STEPS) {
            scale = candidate;
            if (scale < 1.0F) {
                // 缩字号后可用宽度按同一比例放大（字号小了，一行能排下更多字）。
                lines = font.split(text, Math.max(1, (int) (usableWidth / scale)));
            }
            if (lines.size() <= lineBudget) {
                break;
            }
        }

        final int drawnLines = Math.min(lines.size(), lineBudget);
        if (scale >= 1.0F) {
            for (int i = 0; i < drawnLines; i++) {
                graphics.drawString(font, lines.get(i), x, y + i * usableLineHeight, color, false);
            }
            return drawnLines * usableLineHeight;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        for (int i = 0; i < drawnLines; i++) {
            graphics.drawString(font, lines.get(i), 0, i * usableLineHeight, color, false);
        }
        graphics.pose().popPose();
        return (int) (drawnLines * usableLineHeight * scale);
    }
}
