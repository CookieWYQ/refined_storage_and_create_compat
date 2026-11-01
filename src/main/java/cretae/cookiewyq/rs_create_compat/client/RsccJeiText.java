package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;

import java.util.Locale;

/**
 * JEI 页面上的<b>数字文案</b>（唯一落点）。
 *
 * <h2>为什么单独抽出来</h2>
 * <p>JEI 的「降解发电」页要显示三类数：发电量（可能上亿，必须千分位）、
 * 每秒功率（0.05 ~ 几千，小数位数不能固定）、耗时秒数（20 tick = 1.0 秒）。
 * 这些格式如果散在分类里各写一遍，同一个页面就会出现「50 FE / 50.0 FE / 50FE」三种写法；
 * 而且 {@link String#format(String, Object...)} 不带 {@link Locale} 时在部分区域会输出
 * {@code 1,5} 这种逗号小数点 —— 与 {@code RsccNumberField.grouped} 的千分位撞在一起会歧义。</p>
 *
 * <p><b>只做字符串</b>：不读配置、不读世界、不缓存，纯函数。</p>
 */
final class RsccJeiText {
    /** FE/t 这类「平均功率」最多保留几位小数（再多的位数对玩家没有意义）。 */
    private static final int RATE_DECIMALS = 2;

    private RsccJeiText() {
    }

    /** 整数千分位（与 GUI 的「缓存：x / y FE」同一套格式）。 */
    static String grouped(final int value) {
        return RsccNumberField.grouped((long) value);
    }

    /**
     * 保留 1 位小数（耗时秒数用）：20 tick → {@code 1.0}；25 tick → {@code 1.3}。
     * <p>固定 1 位而不是「去掉 .0」：一列数字对齐更好看，也让「1 秒」与「1.02 秒」不会看起来一样。</p>
     */
    static String oneDecimal(final double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 去掉尾随零的小数（平均功率用）：{@code 2.50 → 2.5}、{@code 50.00 → 50}、{@code 0.05 → 0.05}。
     *
     * <p>为什么功率要去零而耗时不去：功率的量级跨 0.05 ~ 上千，固定小数位会让 {@code 2.0 FE/t}
     * 这种整数看起来像测量值；耗时则天然以「秒」为单位，1 位小数是玩家习惯的读法。</p>
     */
    static String rate(final double value) {
        final String text = String.format(Locale.ROOT, "%." + RATE_DECIMALS + "f", value);
        if (text.indexOf('.') < 0) {
            return text;
        }
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return end <= 0 ? "0" : text.substring(0, end);
    }
}
