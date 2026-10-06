package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 完成横幅 Toast：右下角弹出深色横幅。
 * <p>每条 Row 被拆成“资源项”（图标+文字段），每行至多 3 个带图标的资源项，
 * 项间留 8px 间距，放不下就换行；文字统一靠右留边距、内容起点后移，
 * 避免图标/文字/左侧装饰互相重叠，行高与渲染完全一致。</p>
 * <p>本轮追加两点（既有调用方零改动）：</p>
 * <ol>
 *     <li><b>逐段颜色</b>：每段用 {@code Row.colors()} 里的颜色画（同一条横幅行里可以用颜色区分不同原料）；</li>
 *     <li><b>语言键段</b>：段文本以 {@link CompletionBannerPayload#LANG_MARKER} 开头时，按
 *     {@code 键 + 参数} 在<b>客户端</b>解析成 {@link Component}（专服也能让玩家看到自己的语言）。</li>
 * </ol>
 */
public class CompatCompletionToast implements Toast {
    private static final ResourceLocation BACKGROUND =
        ResourceLocation.withDefaultNamespace("toast/system");
    private static final long TIME_VISIBLE = 8000L;
    private static final int WIDTH = 178;
    /** 内容起点 X（右侧保留边距，内容整体右移留出左侧装饰）。 */
    private static final int TEXT_X = 12;
    private static final int RIGHT_MARGIN = 10;
    private static final int TEXT_MAX = WIDTH - TEXT_X - RIGHT_MARGIN;
    private static final int PAD_TOP = 8;
    private static final int PAD_BOTTOM = 8;
    private static final int LINE_H = 18;
    private static final int ICON_SIZE = 16;
    private static final int ICON_GAP = 4;
    /** 相邻资源项之间的间隔（拉大间距，避免拥挤）。 */
    private static final int ITEM_GAP = 8;
    /** 每行最多放几个带图标的资源项。 */
    private static final int MAX_ICONS_PER_LINE = 3;

    /** 一个“视觉行”：若干个资源项（图标+文字）。 */
    private record VisualLine(List<Segment> segments) {
    }

    /** 一个资源项：图标（可为空栈）+ 文本（可能是语言键还原出的 Component）+ 该段颜色。 */
    private record Segment(ItemStack icon, Component text, int color) {
    }

    private final List<VisualLine> lines;
    private final int height;

    public CompatCompletionToast(final List<CompletionBannerPayload.Row> rows) {
        this.lines = buildLines(rows);
        this.height = Math.max(36, PAD_TOP + Math.max(1, lines.size()) * LINE_H + PAD_BOTTOM);
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    /** 段文本 → 渲染用 Component：以语言键标记开头的段按「键 + 参数」解析，其余按普通文本。 */
    private static Component resolve(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.charAt(0) != CompletionBannerPayload.LANG_MARKER) {
            return Component.literal(raw);
        }
        final String[] parts = raw.substring(1)
            .split(String.valueOf(CompletionBannerPayload.LANG_ARG_SEP), -1);
        if (parts.length <= 1) {
            return Component.translatable(parts[0]);
        }
        final Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return Component.translatable(parts[0], args);
    }

    /** 把 Row 拆成视觉行：图标项每行≤3 个、间距拉开、超宽自动换行。 */
    private static List<VisualLine> buildLines(final List<CompletionBannerPayload.Row> rows) {
        final Font font = font();
        final List<VisualLine> result = new ArrayList<>();
        for (final CompletionBannerPayload.Row row : rows) {
            final int segs = Math.min(row.icons().size(), row.texts().size());
            if (segs == 0) {
                continue;
            }
            List<Segment> current = new ArrayList<>();
            int used = 0;
            int iconCount = 0;
            for (int i = 0; i < segs; i++) {
                final ItemStack icon = row.icons().get(i);
                final Component component = resolve(row.texts().get(i));
                String text = component.getString();
                final int segColor = i < row.colors().size() ? row.colors().get(i) : row.color();
                final boolean hasIcon = icon != null && !icon.isEmpty();
                int textW = font.width(text);
                // 单段就超宽的文字（如超长标题）：先按当前剩余宽度截断，剩余换行续写
                while (textW > TEXT_MAX) {
                    final String part = font.plainSubstrByWidth(text, TEXT_MAX - 1);
                    if (part.isEmpty()) {
                        break;
                    }
                    flush(result, current);
                    current = new ArrayList<>();
                    used = 0;
                    iconCount = 0;
                    current.add(new Segment(hasIcon ? icon : ItemStack.EMPTY, Component.literal(part), segColor));
                    used += (hasIcon ? ICON_SIZE + ICON_GAP : 0) + font.width(part);
                    if (hasIcon) {
                        iconCount++;
                    }
                    text = text.substring(part.length());
                    textW = font.width(text);
                }
                final int itemW = (hasIcon ? ICON_SIZE + ICON_GAP : 0) + font.width(text);
                final int addedGap = current.isEmpty() ? 0 : ITEM_GAP;
                final boolean willExceedWidth = used + addedGap + itemW > TEXT_MAX;
                final boolean willExceedIcons = hasIcon && iconCount >= MAX_ICONS_PER_LINE;
                if (!current.isEmpty() && (willExceedWidth || willExceedIcons)) {
                    flush(result, current);
                    current = new ArrayList<>();
                    used = 0;
                    iconCount = 0;
                }
                if (!current.isEmpty()) {
                    used += ITEM_GAP;
                }
                // 只有「本段没被截断」时才保留语言键 Component（截断后的碎片只能是字面量）
                final Component drawn = text.equals(component.getString()) ? component : Component.literal(text);
                current.add(new Segment(hasIcon ? icon : ItemStack.EMPTY, drawn, segColor));
                used += (hasIcon ? ICON_SIZE + ICON_GAP : 0) + font.width(text);
                if (hasIcon) {
                    iconCount++;
                }
            }
            flush(result, current);
        }
        return result;
    }

    private static void flush(final List<VisualLine> result, final List<Segment> segments) {
        if (!segments.isEmpty()) {
            result.add(new VisualLine(List.copyOf(segments)));
        }
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public Visibility render(final GuiGraphics graphics,
                             final ToastComponent toastComponent,
                             final long timeSinceLastVisible) {
        graphics.blitSprite(BACKGROUND, 0, 0, width(), height());
        final Font font = font();
        int y = PAD_TOP;
        for (final VisualLine line : lines) {
            int x = TEXT_X;
            boolean first = true;
            for (final Segment segment : line.segments()) {
                if (!first) {
                    x += ITEM_GAP;
                }
                first = false;
                if (!segment.icon().isEmpty()) {
                    graphics.renderItem(segment.icon(), x, y + (LINE_H - ICON_SIZE) / 2);
                    x += ICON_SIZE + ICON_GAP;
                }
                graphics.drawString(font, segment.text(), x,
                    y + (LINE_H - font.lineHeight) / 2, segment.color(), false);
                x += font.width(segment.text());
            }
            y += LINE_H;
        }
        return timeSinceLastVisible >= TIME_VISIBLE ? Visibility.HIDE : Visibility.SHOW;
    }
}
