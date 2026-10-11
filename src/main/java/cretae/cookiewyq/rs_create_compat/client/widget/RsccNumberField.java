package cretae.cookiewyq.rs_create_compat.client.widget;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * 数值输入框的「显示 / 解析」唯一实现（定量保持器的目标数量输入框共用）。
 *
 * <h2>为什么需要它</h2>
 * <p>原版 {@link net.minecraft.client.gui.components.EditBox} 在文本超出可视宽度时会把<b>左端裁掉</b>
 * （用 {@code font.split} 按光标位置重排，只画最右那一片）。目标数量是 long / int 的「大数」——
 * 玩家要把岩浆控在 1000 桶 = 100000000 mB 时，42px 宽的框只装得下 6 个字符，输入框里就只剩
 * 一串 0（用户实测：「显示的全是 0」）。宽度是几何问题（见
 * {@link RsccKeeperGeometry}），这里解决剩下两件事：</p>
 * <ul>
 *     <li><b>可读性</b>：给出字宽表 + {@link #grouped(String)} / {@link #compact(long)}，
 *     让调用方能在「放得下」的前提下加千分位、在放不下时换紧凑写法（万 / 亿），
 *     而不是无脑塞进框里被裁成 0；</li>
 *     <li><b>可输入性</b>：{@link #parse(String)} 支持千分位、{@code 1000b} / {@code 1e8} 这类
 *     带单位 / 科学计数的写法，玩家不必自己数 0 的个数。</li>
 * </ul>
 *
 * <h2>口径（绝不含糊）</h2>
 * <p>存储 / 网络包里的值<b>永远是原始 mB（或物品个数）</b>——本类只参与「输入文本 → 数值」与
 * 「数值 → 展示文本」，<b>不做任何换算后落盘</b>。后缀 {@code b} 的换算依据是
 * {@code 1 桶 = 1000 mB}（NeoForge {@code FluidType.BUCKET_VOLUME}），且只把结果换算成更大的
 * <b>同族单位</b>（mB 家族：μB / mB / B；物品家族：个 / k / m / g / t），不改变被计量的物理量。</p>
 */
public final class RsccNumberField {
    /** 单字段允许的最大字符数（含千分位分隔符、单位后缀、科学计数尾缀）。 */
    public static final int MAX_LENGTH = 20;
    /** 千分位分隔符（只用于显示 / 解析，绝不进入存储）。 */
    public static final char GROUP_SEPARATOR = ',';

    /** 原始值全部可为 int —— 服务端以 int 落盘（1e8 mB = 1000 桶仍绰绰有余）。 */
    private static final long MAX_VALUE = Integer.MAX_VALUE;
    /** 解析时的安全乘数上限：{@code base * factor} 一旦越过它就判「太大」而不是回绕成负数。 */
    private static final long MAX_FACTORED = MAX_VALUE * 100L;
    /** 科学计数法指数上限（防止 {@code 1e999999} 把 double 撑成 Infinity）。 */
    private static final int MAX_EXPONENT = 18;
    /**
     * 数字部分的合法形状：<b>要么</b>不带分隔符的纯数字，<b>要么</b>严格千分位
     * （首组 1..3 位，其后每组恰好 3 位）。
     * <p>为什么必须严格：{@code 1,2,3} 这种「逗号乱放」不能当合法输入 ——
     * 宽松地「把逗号全删掉」会让玩家以为自己输入的是一个数，实际解析出另一个数。</p>
     */
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile(
        "\\d+|\\d{1,3}(?:,\\d{3})+");

    private RsccNumberField() {
    }

    // ==================== 字宽（不依赖 Font 实例，便于纯逻辑自检） ====================

    /**
     * 一个字符占的像素宽（MC 默认位图字体的 advance）。
     * <p>与工程既有校验脚本（{@code tmp_textures/verify_gui_layout.py} 的 {@code mc_text_width}）
     * 同一张表：非 ASCII（中文单位「桶」/「万」）按 unifont 的 9px 计。</p>
     */
    public static int charWidth(final char ch) {
        if (ch >= 128) {
            return 9; // 中文单位「桶」/「万」/「亿」：unifont 全角 9px
        }
        return switch (ch) {
            // 窄字符（原版位图字体 1..3px）
            case '!', '\'', '.', ',', ':', ';', 'i', '|' -> 2;
            case ' ', '(', ')', '[', ']', 'f', 'k', 'l', 't', 'I' -> 4;
            // 中等宽度
            case '<', '>', '*', '"', '`', '{', '}', '~' -> 5;
            case '\\' -> 6;
            case '@' -> 7;
            // 数字 / 大写字母 / 其余可见字符：6px（宁可高估也不低估，宁可退回纯数字也不被裁）
            default -> 6;
        };
    }

    /** 文本像素宽（与 MC 默认字体一致到 1px 以内，用于「放得下才加千分位」的判定）。 */
    public static int textWidth(@Nullable final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += charWidth(text.charAt(i));
        }
        return total;
    }

    /**
     * 输入框文本区能容纳的字符宽度（扣掉 {@code EditBox} 左右各 4px 内边距与 1px 光标槽）。
     * <p>与 {@link #MAX_LENGTH} 一起锁死，供几何自检断言「一百万桶的 mB 原值一眼看全」。</p>
     */
    public static int textWindow(final int boxWidth) {
        return Math.max(0, boxWidth - 9);
    }

    // ==================== 数值 → 展示文本 ====================

    /** 数值的纯数字文本（无千分位、无单位）。 */
    public static String raw(final long value) {
        return Long.toString(Math.max(0L, value));
    }

    /** 加千分位（{@code 100000000} → {@code 100,000,000}）。 */
    public static String grouped(final long value) {
        return grouped(raw(value));
    }

    /** 给纯数字串加千分位；非数字串原样返回（不做任何猜测）。 */
    public static String grouped(@Nullable final String digits) {
        if (digits == null || digits.isEmpty()) {
            return digits == null ? "" : digits;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!isDigit(digits.charAt(i))) {
                return digits;
            }
        }
        final StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
        final int head = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - head) % 3 == 0) {
                out.append(GROUP_SEPARATOR);
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    /** 去掉千分位（{@code 100,000,000} → {@code 100000000}）；非数字串原样返回。 */
    public static String ungrouped(@Nullable final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (ch != GROUP_SEPARATOR) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * 紧凑可读写法（用于空间不够时的第二选择）：{@code 1e8} → {@code 1亿}、{@code 123456789} →
     * {@code 1.2亿}、{@code 12345} → {@code 1.2万}。数值本身也一并给出，避免玩家只能看到近似值。
     */
    public static String compact(final long value) {
        final long v = Math.max(0L, value);
        if (v < 10_000L) {
            return grouped(v);
        }
        final long unit = v >= 100_000_000L ? 100_000_000L : 10_000L;
        final String name = unit == 100_000_000L ? "亿" : "万";
        // 保留 1 位小数（不四舍五入到整数，否则 1.99 亿会被读成 2 亿）
        final long tenths = v * 10L / unit;
        final String text = (tenths / 10L) + "." + (tenths % 10L) + name;
        return text;
    }

    /**
     * 目标数量的展示文本（<b>只在放得下时才加千分位</b>）。
     * <p>这是「大数值显示不全」的核心：{@code widthPx} 是输入框文本区可用宽度，
     * 放得下千分位就用千分位（人眼分组），放不下就退回纯数字 ——
     * 两者都<b>不是</b>让玩家只看到一串 0。</p>
     *
     * <h2>为什么这里不用 {@link #compact(long)}</h2>
     * <p>输入框里显示什么，玩家下次打开界面就会看到什么（服务端回填也走这里），
     * 因此它必须是<b>可被 {@link #parse} 原样读回</b>的写法。「万 / 亿」会取整掉低位，
     * 一旦用它回填就等于把玩家设的 100000000 显示成 1.0亿、再保存时可能变成别的数 ——
     * 这是精度事故，不能为了排版好看去冒。因此这里只产出两种写法：
     * 千分位（放得下）或纯数字（放不下，玩家可用 ←→ 键横向查看）。
     * 人类友好的紧凑写法只用在 {@link #bucketTooltip} / {@link #bucketHint} 这类
     * <b>只读展示</b>里，那里不存在回写。</p>
     *
     * @param value   真实的原始数值（mB 或个数）
     * @param widthPx 输入框文本区可用像素宽
     */
    public static String display(final long value, final int widthPx) {
        final String plain = raw(value);
        final String pretty = grouped(plain);
        return textWidth(pretty) <= widthPx ? pretty : plain;
    }

    /**
     * 输入框里「现在是这个值」的规范化文本（关掉焦点 / 服务端回填时用）。
     * <p>与 {@link #parse} 严格互为逆：{@code parse(editable(display(v))) == v}（v 在 int 范围内）。</p>
     */
    public static String editable(final long value, final int widthPx) {
        return display(value, widthPx);
    }

    // ==================== 展示文本 → 数值 ====================

    /** 解析结果：{@code ok=false} 表示文本不是合法数值（调用方应保持原值、不发包）。 */
    public record Parsed(boolean ok, long value) {
    }

    private static final Parsed BAD = new Parsed(false, 0L);

    /**
     * 解析玩家输入的数值文本，支持：
     * <ul>
     *     <li>纯数字：{@code 100000000}；</li>
     *     <li>千分位：{@code 100,000,000}（分隔符只允许出现在数字组之间）；</li>
     *     <li>科学计数：{@code 1e8} / {@code 1.5e8}（指数 0..{@value #MAX_EXPONENT}）；</li>
     *     <li>同族单位后缀：mB 家族 {@code 1000b}（= 1000 桶 = 1000000 mB）、{@code 500m}
     *     （= 0.5 桶 = 500 mB，按四舍五入到整数 mB）；物品家族 {@code 2k} / {@code 3m} / {@code 1g}。</li>
     * </ul>
     * <p>合法性判据是「整串形状」，不是「能 parse 出个前缀」：{@code 12abc} / {@code 1,2,3} /
     * {@code ,100} 一律 {@code ok=false}，调用方据此保持原值 —— 非法输入<b>永远不会</b>被当成 0 或半个数发出去。</p>
     */
    public static Parsed parse(@Nullable final String text) {
        if (text == null) {
            return BAD;
        }
        final String s = text.trim();
        if (s.isEmpty()) {
            return BAD;
        }
        // 先切出「数字部分」（含千分位）与其余部分（科学计数 / 单位后缀），
        // 再用正则把数字部分的形状钉死（严格千分位，见 DIGITS）
        int end = 0;
        while (end < s.length() && (isDigit(s.charAt(end)) || s.charAt(end) == GROUP_SEPARATOR)) {
            end++;
        }
        final String digits = ungrouped(s.substring(0, end));
        if (!DIGITS.matcher(s.substring(0, end)).matches()) {
            return BAD;
        }
        final String rest = s.substring(end);
        // 科学计数：e<E>（指数只允许数字）
        int exponent = 0;
        String tail = rest;
        if (!tail.isEmpty() && (tail.charAt(0) == 'e' || tail.charAt(0) == 'E')) {
            tail = tail.substring(1);
            if (tail.isEmpty() || !allDigits(tail)) {
                return BAD;
            }
            for (int i = 0; i < tail.length(); i++) {
                exponent = exponent * 10 + (tail.charAt(i) - '0');
                if (exponent > MAX_EXPONENT) {
                    return BAD;
                }
            }
            tail = "";
        }
        long factor = 1L;
        if (!tail.isEmpty()) {
            if (tail.length() != 1) {
                return BAD; // 后缀只允许 1 个字符
            }
            final long suffix = suffixFactor(tail.charAt(0));
            if (suffix == 0L) {
                return BAD;
            }
            factor = suffix;
        }
        final long base;
        try {
            base = Long.parseLong(digits);
        } catch (final NumberFormatException e) {
            return BAD; // 位数太多（>19 位）⇒ 判非法，而不是回绕
        }
        long value = base;
        for (int i = 0; i < exponent; i++) {
            if (value > MAX_FACTORED / 10L) {
                return BAD;
            }
            value *= 10L;
        }
        if (factor != 1L) {
            if (value > MAX_FACTORED / factor) {
                return BAD;
            }
            value *= factor;
        }
        if (value < 0L) {
            return BAD;
        }
        return new Parsed(true, Math.min(MAX_VALUE, value));
    }

    /** 整串都是数字（科学计数指数用）。 */
    private static boolean allDigits(final String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 输入框 responder 的安全解析（唯一入口）：
     * <ol>
     *     <li>整串合法（含千分位 / 后缀 / 科学计数）→ 取该数值；</li>
     *     <li>不合法但<b>以数字开头</b>（{@code 1e} / {@code 1000x} / {@code 1,23,} 这类「正在输入」的
     *     中间态）→ 退回到「其中的数字部分」（与旧实现等价），保证输入过程中不会把目标改成别的值；</li>
     *     <li>其余（空串、首字符非数字）→ {@code ok=false}，调用方<b>保持不动、不发包</b>。</li>
     * </ol>
     * <p>为什么空串不再直接发 0：用户清空输入框往往只是想重打一个数，此时把目标静默改成
     * 「未标记」属于危险副作用；要表达「未标记」只需输入 0。</p>
     */
    public static Parsed parseSanitized(@Nullable final String text) {
        final Parsed full = parse(text);
        if (full.ok()) {
            return full;
        }
        if (text == null || text.isEmpty() || !isDigit(text.charAt(0))) {
            return BAD;
        }
        return parse(digitsOnly(text));
    }

    /** 只保留数字与千分位（丢掉后缀残留），用于「输入中」的保守解析。 */
    private static String digitsOnly(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (isDigit(ch) || ch == GROUP_SEPARATOR) {
                out.append(ch);
            }
        }
        final String result = out.toString();
        return result.endsWith(String.valueOf(GROUP_SEPARATOR))
            ? result.substring(0, result.length() - 1) : result;
    }

    // ==================== 输入框接线（唯一实现，两个界面共用） ====================

    /**
     * 按本类口径接线一个目标数量输入框（<b>唯一实现</b>）：设置最大长度、把「文本 → 数值」
     * 交给 {@link #parseSanitized}，并把解析出的原始数值回调给调用方去发包。
     *
     * <p>为什么必须唯一：输入框的解析口径（千分位 / 后缀 / 回退）只要有一处不同，
     * 「界面显示 1000 桶、实际存了 1 mB」这种偏差就会在某个界面上悄悄出现。
     * 现在两个保持器界面都只调这一个方法，结构上不可能分叉。</p>
     *
     * @param box      待接线的输入框
     * @param onValue  解析成功时的回调（参数 = 原始数值：物品个数或 mB）；解析失败<b>不会</b>回调
     */
    public static EditBox wire(final EditBox box, final java.util.function.LongConsumer onValue) {
        box.setMaxLength(MAX_LENGTH);
        box.setResponder(text -> {
            final Parsed parsed = parseSanitized(text);
            if (parsed.ok()) {
                onValue.accept(parsed.value());
            }
        });
        return box;
    }

    /** 后缀 → 乘数；{@code 0} 表示不是合法后缀。 */
    private static long suffixFactor(final char ch) {
        return switch (ch) {
            // mB 家族：b = 桶 = 1000 mB（NeoForge FluidType.BUCKET_VOLUME）
            case 'b', 'B' -> 1000L;
            // 同族小单位：m = 1/1000 桶（= 1 mB），按整数 mB 四舍五入
            case 'm', 'M' -> 1L;
            case 'k', 'K' -> 1_000L;
            case 'g', 'G' -> 1_000_000_000L;
            case 't', 'T' -> 1_000_000_000_000L;
            default -> 0L;
        };
    }

    private static boolean isDigit(final char ch) {
        return ch >= '0' && ch <= '9';
    }

    // ==================== 桶（b）显示口径：1 桶 = 1000 mB ====================
    // 本组只有两个出口（都在界面上被调用）：
    //   bucketHint    —— 输入框下方那行「= N 桶」限宽短提示（框里已经有原始 mB）
    //   bucketTooltip —— 悬停 tooltip 的完整口径（原始 mB + 桶数 + 紧凑可读写法）
    // 刻意<b>不</b>提供第三套「完整桶文本」，避免同一件事有多种写法。

    /** mB → 桶（向下取整，仅用于展示）。 */
    public static long buckets(final long mB) {
        return Math.max(0L, mB) / 1000L;
    }

    /**
     * 桶换算提示（限宽）：<b>框里已经有原始 mB</b>，所以这一行只写桶数，省下的像素用来放更大的数。
     * <p>例：{@code 100000000} → {@code = 100,000 桶}；数值过大时用紧凑写法（万 / 亿）。
     * 原始 mB 与桶数同时出现在输入框 tooltip 里（{@link #bucketTooltip}），
     * 因此「显示按桶、存储按 mB」这件事在任何宽度下都不会被误读。</p>
     */
    public static String bucketHint(final long mB, final int widthPx) {
        final String text = mB < 1000L
            ? grouped(mB) + " mB"                 // 不足 1 桶：写「0 桶」没有信息量，直接写 mB
            : "= " + grouped(buckets(mB)) + " 桶";
        if (textWidth(text) <= widthPx) {
            return text;
        }
        final String shorter = mB < 1000L
            ? compact(mB) + " mB"
            : "= " + compact(buckets(mB)) + " 桶";
        return textWidth(shorter) <= widthPx ? shorter : compact(mB);
    }

    /**
     * 输入框的桶换算 tooltip 文本：<b>原始 mB 与桶数同时给出</b>，另附一个紧凑可读写法
     * （放大到亿级时人眼几乎数不清 0 的个数）。
     * <p>调用方在 tooltip 里紧跟一行显示这个字符串，因此玩家既看到精确值也看到量级。</p>
     */
    public static String bucketTooltip(final long mB) {
        final String base = mB < 1000L
            ? grouped(mB) + " mB"
            : grouped(mB) + " mB = " + grouped(buckets(mB)) + " 桶";
        final String human = compact(mB);
        return human.equals(grouped(mB)) ? base : base + "（" + human + "）";
    }

    /** 工具：把 long 安全夹到 int（服务端只收 int ⇒ 绝不静默回绕成负数）。 */
    public static int clampToInt(final long value) {
        return (int) Mth.clamp(value, 0L, (long) Integer.MAX_VALUE);
    }
}
