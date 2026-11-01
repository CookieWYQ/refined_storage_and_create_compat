package cretae.cookiewyq.rs_create_compat.client.widget;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 可复用的「滚轮选择」控件（交互照抄 Create 的 {@code SelectionScrollInput}，但不引用其任何类）。
 * <p><b>交互</b>：滚轮下滚 = 下一项 / 上滚 = 上一项；左键点左半边 = 上一项、右半边 = 下一项
 * （Create 原版只支持滚轮，这里额外照顾鼠标玩家）。每次选择变化都会回调 {@code onSelect}。</p>
 * <p><b>外观</b>：自绘 1px 深色描边 + 更深内填充的凹槽框（{@code guiGraphics.fill}，不新增贴图），
 * 当前项文字水平 / 垂直居中，两端用 {@code ◀} / {@code ▶} 文本符提示可切换
 * （第 104 轮起宽度不足时<b>整体缩字号</b>而不是省略，见 {@link #drawCenteredFitLine}）。</p>
 * <p><b>tooltip</b>：本控件不参与原版自动 tooltip，必须由所属界面手动调用
 * {@link #renderTooltip(GuiGraphics, int, int)}；窗口最多 16 行，当前项写 {@code -> 选项}（白），
 * 其余 {@code > 选项}（灰），窗口外还有项时补一行「上面 / 下面还有 N 项（共 M 项）」，
 * 与 hover 判定同源（{@link #inBounds}）。</p>
 */
public class ScrollSelectWidget<T> extends AbstractWidget {
    private static final String LANG = "gui.rs_create_compat.widget.scroll_select.";
    /** tooltip 最多显示的行数（与 Create 的 SelectionScrollInput 一致）。 */
    private static final int MAX_TOOLTIP_LINES = 16;

    private static final String ARROW_PREV = "◀";
    private static final String ARROW_NEXT = "▶";

    private static final int COLOR_BORDER = 0xFF15151D;
    private static final int COLOR_FILL = 0xFF0A0A10;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_TEXT_HOVER = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DISABLED = 0xFF808080;
    private static final int COLOR_ARROW = 0xFF9AA4B0;
    /** 选项图标（16×16）占用的横向宽度（含右侧 2px 间距）。 */
    private static final int ICON_RESERVE = 18;

    private final Font font;
    private List<T> options = List.of();
    /** 当前选中下标；options 为空时恒为 0。 */
    private int state;
    private Component title = Component.empty();
    private Function<T, Component> display = value -> Component.literal(String.valueOf(value));
    private Consumer<Integer> onSelect;
    private Component hint;
    /** 可选的额外 tooltip 行提供者（作用于当前选中项，追加到 tooltip 最底部）；null 时不显示。 */
    private Function<T, List<Component>> extraTooltipLines;
    /** 是否可交互（false 时灰显且不响应滚轮 / 点击）。 */
    private boolean selectable = true;
    /**
     * 可选的「选项图标」提供者（当前选中项左侧的 16×16 物品图标）。
     * <p>为 {@code null} 时不画图标、也不占位，<b>渲染结果与加这个特性之前逐像素一致</b>
     * （其它复用本控件的界面因此零回归）。</p>
     */
    private Function<T, net.minecraft.world.item.ItemStack> iconFor;

    public ScrollSelectWidget(final Font font, final int x, final int y, final int width, final int height) {
        super(x, y, width, height, Component.empty());
        this.font = font;
    }

    // ==================== 链式配置 ====================

    public ScrollSelectWidget<T> forOptions(final List<T> options) {
        setOptions(options);
        return this;
    }

    public ScrollSelectWidget<T> titled(final Component title) {
        this.title = title == null ? Component.empty() : title;
        return this;
    }

    public ScrollSelectWidget<T> displaying(final Function<T, Component> display) {
        if (display != null) {
            this.display = display;
        }
        return this;
    }

    /** 选择变化回调（参数为新的下标）；初始化 / 服务端回填请用 {@link #setState(int)}（不回调）。 */
    public ScrollSelectWidget<T> writingTo(final Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
        return this;
    }

    public ScrollSelectWidget<T> hinting(final Component hint) {
        this.hint = hint;
        return this;
    }

    /**
     * 追加「当前选中项」的额外 tooltip 行（渲染在最底部、低调不抢眼），例如原始配方类型 id。
     * <p>tooltip 每次调用 {@link #renderTooltip} 时按当前选中项实时生成，因此切换选择后自动同步刷新。</p>
     */
    public ScrollSelectWidget<T> showingExtraTooltip(final Function<T, List<Component>> extraTooltipLines) {
        this.extraTooltipLines = extraTooltipLines;
        return this;
    }

    public ScrollSelectWidget<T> selectable(final boolean selectable) {
        this.selectable = selectable;
        return this;
    }

    /**
     * 当前选中项左侧的 16×16 图标（例：配方类型 → 能执行它的机器方块）。
     * <p><b>只影响绘制，不改几何</b>：图标占用控件内部左侧 {@value #ICON_RESERVE}px，
     * 文字区与 ◀/▶ 箭头整体右移同样的宽度，控件本身的 x/y/w/h 一个像素都不变。
     * 传 {@code null} 恢复「纯文字」外观（与旧版完全一致）。</p>
     */
    public ScrollSelectWidget<T> withIcon(final Function<T, net.minecraft.world.item.ItemStack> iconFor) {
        this.iconFor = iconFor;
        return this;
    }

    // ==================== 状态 ====================

    /** 替换候选项；下标越界时收敛到范围末尾（不触发回调）。 */
    public void setOptions(final List<T> options) {
        this.options = options == null ? List.of() : List.copyOf(options);
        this.state = this.options.isEmpty() ? 0 : Math.max(0, Math.min(this.state, this.options.size() - 1));
    }

    /** 静默设置当前下标（不触发 onSelect）：用于初始化与服务端回填。 */
    public void setState(final int state) {
        if (options.isEmpty()) {
            this.state = 0;
            return;
        }
        this.state = Math.max(0, Math.min(options.size() - 1, state));
    }

    public int getState() {
        return state;
    }

    /** 当前选中项（无候选返回 null）。 */
    @Nullable
    public T getSelected() {
        return options.isEmpty() ? null : options.get(state);
    }

    public List<T> getOptions() {
        return options;
    }

    public boolean isSelectable() {
        return selectable;
    }

    // ==================== 事件 ====================

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (!canInteract() || scrollY == 0) {
            return false;
        }
        // 列表语义：下滚 = 下一项
        move(scrollY > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button != 0 || !canInteract() || !inBounds(mouseX, mouseY)) {
            return false;
        }
        // 左半边 = 上一项，右半边 = 下一项（Create 原版没有的鼠标支持）
        move(mouseX - getX() < getWidth() / 2.0 ? -1 : 1);
        return true;
    }

    /** 仅在「可用 + 候选多于 1 项」时接管滚轮 / 点击，否则把事件让给所属界面。 */
    private boolean canInteract() {
        return active && visible && selectable && options.size() > 1;
    }

    private void move(final int delta) {
        final int next = Math.max(0, Math.min(options.size() - 1, state + delta));
        if (next == state) {
            return;
        }
        state = next;
        if (onSelect != null) {
            onSelect.accept(state);
        }
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderWidget(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                final float partialTick) {
        final int x = getX();
        final int y = getY();
        final int w = getWidth();
        final int h = getHeight();
        // 凹槽框：1px 深色描边 + 更深内填充
        guiGraphics.fill(x, y, x + w, y + h, COLOR_BORDER);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, COLOR_FILL);

        final int lineY = y + (h - 8) / 2;
        if (options.isEmpty() || !active || !visible) {
            final Component empty = Component.translatable(options.isEmpty() ? LANG + "none" : LANG + "disabled");
            guiGraphics.drawString(font, empty, x + (w - font.width(empty)) / 2, lineY,
                COLOR_TEXT_DISABLED, false);
            return;
        }

        final int arrowW = font.width(ARROW_PREV);
        // 选项图标：只在装了 iconFor 时占位（否则文字 / 箭头的坐标与旧版完全一致）
        final int iconReserve = iconFor == null ? 0 : ICON_RESERVE;
        if (iconFor != null) {
            final net.minecraft.world.item.ItemStack icon = iconFor.apply(options.get(state));
            if (icon != null && !icon.isEmpty()) {
                guiGraphics.renderItem(icon, x + 2, y + (h - 16) / 2);
            }
        }
        // 不可交互时仍显示当前项（灰显、无箭头）：让玩家看得到被锁定的当前值，而不是只看到「不可用」
        final boolean showArrows = selectable && options.size() > 1
            && w >= 2 * arrowW + 8 + 8 + iconReserve;
        int textX = x + 2 + iconReserve;
        int textW = w - 4 - iconReserve;
        final int color = !selectable ? COLOR_TEXT_DISABLED
            : (inBounds(mouseX, mouseY) ? COLOR_TEXT_HOVER : COLOR_TEXT);
        if (showArrows) {
            guiGraphics.drawString(font, Component.literal(ARROW_PREV), x + 2 + iconReserve, lineY,
                COLOR_ARROW, false);
            guiGraphics.drawString(font, Component.literal(ARROW_NEXT), x + w - 2 - arrowW, lineY,
                COLOR_ARROW, false);
            textX = x + 2 + iconReserve + arrowW + 2;
            textW = w - 4 - iconReserve - 2 * (arrowW + 2);
        }
        final String text = display.apply(options.get(state)).getString();
        // 第 104 轮：选中项一律**完整显示**。控件宽度不够时按 RsccTextFit 整体缩字号（下限 0.6 倍），
        // 绝不切成「前半段…」；缩到下限仍放不下的超长自由文本（第三方模组的配方类型名 / 玩家命名）
        // 的完整原文在 tooltip 里（tooltipLines 的「-> 当前项」+ 上下溢出计数）。
        drawCenteredFitLine(guiGraphics, text, textX, textW, lineY, color);
    }

    /**
     * 在 {@code [textX, textX + textW)} 里<b>水平居中</b>画一行完整文本。
     * <p>仅用于本控件内部的居中文字：先按 {@link RsccTextFit#requiredScaleForLine} 取缩放（不低于
     * {@link RsccTextFit#MIN_SCALE}），再把缩放后的宽度居中，最后交给
     * {@link RsccTextFit#drawFitLine} 实画 —— 因此「量出来的宽度」和「画出来的宽度」是同一个值，
     * 居中不会偏。<b>不做任何截断。</b></p>
     */
    private void drawCenteredFitLine(final GuiGraphics guiGraphics, final String text,
                                     final int textX, final int textW, final int lineY,
                                     final int color) {
        final Component component = Component.literal(text);
        final int rawWidth = font.width(component);
        final float scale = Math.max(RsccTextFit.MIN_SCALE,
            RsccTextFit.requiredScaleForLine(font, component, textW));
        final int drawnWidth = Math.round(rawWidth * scale);
        final int drawX = textX + Math.max(0, (textW - drawnWidth) / 2);
        RsccTextFit.drawFitLine(guiGraphics, font, component, drawX, lineY, textW, color);
    }

    /** 手动渲染 tooltip：判定范围与绘制范围完全一致（左闭右开）。 */
    public void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (!visible || !inBounds(mouseX, mouseY)) {
            return;
        }
        final List<Component> lines = tooltipLines();
        final List<FormattedCharSequence> wrapped = new ArrayList<>(lines.size());
        for (final Component line : lines) {
            wrapped.add(line.getVisualOrderText());
        }
        guiGraphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    /**
     * tooltip 内容：标题 + 16 行窗口（当前项 {@code ->}，其它 {@code >}）+ 上下溢出计数 + 提示。
     * <p><b>第 104 轮</b>：窗口外的项不再画成 {@code > ...} 这种省略号占位，而是换成一整句
     * 「上面 / 下面还有 N 项（共 M 项）」—— 同样一眼看出「还有多少没列出来」，但界面里
     * 不再出现任何省略号；当前项本身永远整串显示（见 {@link #drawCenteredFitLine}）。</p>
     */
    private List<Component> tooltipLines() {
        final List<Component> lines = new ArrayList<>();
        if (!title.getString().isEmpty()) {
            lines.add(title.copy().withStyle(ChatFormatting.GOLD));
        }
        if (options.isEmpty()) {
            lines.add(Component.translatable(LANG + "none").withStyle(ChatFormatting.GRAY));
        } else {
            final int min = Math.max(0,
                Math.min(options.size() - MAX_TOOLTIP_LINES, state - MAX_TOOLTIP_LINES / 2));
            final int max = Math.min(options.size(), min + MAX_TOOLTIP_LINES);
            if (min > 0) {
                lines.add(Component.translatable(LANG + "more_above", min, options.size())
                    .withStyle(ChatFormatting.GRAY));
            }
            for (int i = min; i < max; i++) {
                final Component option = display.apply(options.get(i));
                if (i == state) {
                    lines.add(Component.empty().append("-> ").append(option).withStyle(ChatFormatting.WHITE));
                } else {
                    lines.add(Component.empty().append("> ").append(option).withStyle(ChatFormatting.GRAY));
                }
            }
            if (max < options.size()) {
                lines.add(Component.translatable(LANG + "more_below", options.size() - max, options.size())
                    .withStyle(ChatFormatting.GRAY));
            }
        }
        if (hint != null) {
            lines.add(hint.copy().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
        lines.add(Component.translatable(LANG + "hint").withStyle(ChatFormatting.DARK_GRAY,
            ChatFormatting.ITALIC));
        // 附加信息：当前选中项的额外行（如原始配方类型 id），置于最底部保持低调
        if (extraTooltipLines != null) {
            final T selected = getSelected();
            if (selected != null) {
                final List<Component> extra = extraTooltipLines.apply(selected);
                if (extra != null) {
                    lines.addAll(extra);
                }
            }
        }
        return lines;
    }

    /** 鼠标是否落在控件绘制范围内（左闭右开）。 */
    public boolean inBounds(final double mouseX, final double mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth()
            && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput narration) {
        final T selected = getSelected();
        narration.add(NarratedElementType.TITLE, selected == null
            ? Component.translatable(LANG + "none")
            : display.apply(selected));
    }
}
