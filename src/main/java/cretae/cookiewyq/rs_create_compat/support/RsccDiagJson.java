package cretae.cookiewyq.rs_create_compat.support;

import java.util.List;
import java.util.Map;

/**
 * 极小、无外部依赖、<b>输出确定性</b> 的 JSON 序列化器（仅服务于 {@link RsccDiag} 的诊断快照）。
 *
 * <h2>为什么要自己写（而不是引第三方）</h2>
 * 诊断快照有一条硬性要求：<b>同一份状态两次导出、除时间戳外必须逐字节一致</b>。
 * 常见 JSON 库默认按「哈希表迭代顺序」输出字段，哈希顺序在不同 JVM 运行间并不保证稳定；
 * 这里改成<b>完全按调用方插入顺序</b>输出 —— 调用方一律使用 {@link java.util.LinkedHashMap}，
 * 并且所有集合在插入前都已显式排序（见 {@link RsccDiag}）。因此同一状态 → 同一字节流。
 *
 * <p>仅支持快照用到的类型：{@code Map / List / String / Number / Boolean / null}。
 * 其它类型一律降级为字符串（绝不抛异常 —— 诊断设施自身不得打断产线）。</p>
 */
public final class RsccDiagJson {
    /** 缩进宽度（2 空格；只为可读，不影响确定性）。 */
    private static final String INDENT = "  ";

    private RsccDiagJson() {
    }

    /** 序列化并追加一个结尾换行（文本文件友好）。 */
    public static String write(final Object root) {
        final StringBuilder sb = new StringBuilder(16384);
        writeValue(sb, root, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(final StringBuilder sb, final Object value, final int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, depth);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, depth);
        } else if (value instanceof String text) {
            writeString(sb, text);
        } else if (value instanceof Boolean flag) {
            sb.append(flag.booleanValue() ? "true" : "false");
        } else if (value instanceof Number number) {
            writeNumber(sb, number);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeObject(final StringBuilder sb, final Map<?, ?> map, final int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int index = 0;
        final int size = map.size();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, depth + 1);
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(": ");
            writeValue(sb, entry.getValue(), depth + 1);
            sb.append(++index < size ? ",\n" : "\n");
        }
        indent(sb, depth);
        sb.append('}');
    }

    private static void writeArray(final StringBuilder sb, final List<?> list, final int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, depth + 1);
            writeValue(sb, list.get(i), depth + 1);
            sb.append(i + 1 < list.size() ? ",\n" : "\n");
        }
        indent(sb, depth);
        sb.append(']');
    }

    private static void indent(final StringBuilder sb, final int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append(INDENT);
        }
    }

    /** 数字：整数原样（确定性），浮点用 {@link Double#toString}（同样是确定性表示）。 */
    private static void writeNumber(final StringBuilder sb, final Number number) {
        if (number instanceof Double || number instanceof Float) {
            final double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append('0');
                return;
            }
            sb.append(Double.toString(d));
            return;
        }
        sb.append(number.longValue());
    }

    private static void writeString(final StringBuilder sb, final String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
