package cretae.cookiewyq.rs_create_compat.client.tooltip;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 本模组<b>附加</b> tooltip 信息的「按键分层显示」唯一实现。
 *
 * <h2>为什么需要它</h2>
 * <p>本模组在很多物品 / 界面上追加了原版没有的说明（配方类型、输入原料、概率、内部 id、绑定关系…）。
 * 这些信息<b>不该常显</b>：有的和别的界面重复（例如归流缓存仓与匹配设置子窗口都讲同一件事），
 * 常显会把原版 tooltip 挤得看不到重点。用户要求参考机械动力的做法：<b>默认只显示原版 tooltip +
 * 一条「按住 [Shift] 查看…」提示行</b>，按住对应修饰键才展开对应「信息层面」的内容；
 * <b>同时按住多个键 ⇒ 多层一起显示</b>。</p>
 *
 * <h2>分层原则（信息层面，不是 MC 的维度）</h2>
 * <ul>
 *     <li>{@link Layer#SHIFT} —— <b>核心机制 / 用途 / 怎么用</b>（最常看：这一格是干什么的）；</li>
 *     <li>{@link Layer#CTRL} —— <b>数值 / 配方 / 统计细节</b>（数量、概率、产率、次数、上限、耗时）；</li>
 *     <li>{@link Layer#ALT} —— <b>调试 / 进阶 / 来源</b>（内部 id、坐标、绑定关系、中间产物编号）。</li>
 * </ul>
 *
 * <h2>唯一实现（硬约束）</h2>
 * <p><b>所有</b>附加 tooltip 一律经本类过滤后再渲染 / 追加，各处<b>不得</b>自行判断 Shift / Ctrl / Alt。
 * 这样「不按键 = 全隐藏 + 一条提示行」「多键同按 = 多层同显」只可能有一份语义。</p>
 *
 * <h2>视觉</h2>
 * <p>提示行照抄机械动力的观感：<b>灰色正文 + 彩色按键名</b>（正文 {@code DARK_GRAY}、按键名 {@code AQUA}）。
 * 全部文字无阴影（本模组硬规则）。</p>
 */
public final class RsccTooltipLayers {
    /** 提示行 / 键名的语言键前缀。 */
    private static final String LANG = "gui.rs_create_compat.tooltip.";
    /** 提示行正文颜色（灰）。 */
    private static final ChatFormatting HINT_COLOR = ChatFormatting.DARK_GRAY;
    /** 按键名颜色（彩色，参考机械动力的「灵动的颜色」）。 */
    private static final ChatFormatting KEY_COLOR = ChatFormatting.AQUA;

    private RsccTooltipLayers() {
    }

    /** 信息层面（与 MC 的维度无关，指信息的层面）。 */
    public enum Layer {
        /** 核心机制 / 用途 / 怎么用：最常看的一层，对应 <b>Shift</b>。 */
        SHIFT("shift"),
        /** 数值 / 配方 / 统计细节，对应 <b>Ctrl</b>。 */
        CTRL("ctrl"),
        /** 调试 / 进阶 / 来源（内部 id / 坐标 / 绑定），对应 <b>Alt</b>。 */
        ALT("alt");

        private final String key;

        Layer(final String key) {
            this.key = key;
        }

        /** 该层的本地化按键名（Shift / Ctrl / Alt）。 */
        public Component displayName() {
            return Component.translatable(LANG + "layer." + key);
        }
    }

    /** 一行带「层面」归属的附加信息（只有按键按住时才显示）。 */
    public record Line(Layer layer, Component text) {
    }

    // ==================== 按键状态 ====================

    /** 该层对应的修饰键当前是否按住。 */
    public static boolean held(final Layer layer) {
        return switch (layer) {
            case SHIFT -> Screen.hasShiftDown();
            case CTRL -> Screen.hasControlDown();
            case ALT -> Screen.hasAltDown();
        };
    }

    /** 当前按住的全部层面（可能为空集）。 */
    public static EnumSet<Layer> heldLayers() {
        final EnumSet<Layer> held = EnumSet.noneOf(Layer.class);
        for (final Layer layer : Layer.values()) {
            if (held(layer)) {
                held.add(layer);
            }
        }
        return held;
    }

    /**
     * 「图标 / 组件行」是否展开：与 {@link #held} <b>同一判据</b>。
     *
     * <h2>为什么组件行单独给一个入口</h2>
     * <p>{@link ClientTooltipComponent} 的行（图标 + 名字）不走 {@link Component}，
     * 因此无法经 {@link #append} 统一过滤；但它们的可见性语义必须与文字行完全一致。
     * 这里给出唯一入口后，各处仍然<b>不会</b>自己读按键 —— 按键状态依旧只在 {@link #held} 里读一次。</p>
     */
    public static boolean componentVisible(final Layer layer) {
        return layer != null && heldLayers().contains(layer);
    }

    // ==================== 构造行的便捷方法 ====================

    public static Line shift(final Component text) {
        return new Line(Layer.SHIFT, text);
    }

    public static Line ctrl(final Component text) {
        return new Line(Layer.CTRL, text);
    }

    public static Line alt(final Component text) {
        return new Line(Layer.ALT, text);
    }

    public static List<Line> list(final Line... lines) {
        return List.of(lines);
    }

    // ==================== 统一出口 ====================

    /**
     * item 路径（{@code appendHoverText}）：把带层面标记的行按当前按键过滤后<b>追加</b>到 tooltip，
     * 末尾再补一条提示行（列出「还有哪些层可以按出来」）。
     * <p>调用方自己加的「常显行」（原版 tooltip、或确实必须常显的极简一行）直接 add 即可，不走本方法。</p>
     */
    public static void append(final List<Component> tooltip, final List<Line> lines) {
        if (tooltip == null || lines == null || lines.isEmpty()) {
            return;
        }
        final EnumSet<Layer> available = contentLayers(lines);
        if (available.isEmpty()) {
            return;
        }
        final EnumSet<Layer> held = heldLayers();
        for (final Line line : lines) {
            if (held.contains(line.layer())) {
                tooltip.add(line.text());
            }
        }
        final Component hint = hintLine(available, held);
        if (hint != null) {
            tooltip.add(hint);
        }
    }

    /** 便捷重载：单个可变参数。 */
    public static void append(final List<Component> tooltip, final Line... lines) {
        append(tooltip, List.of(lines));
    }

    /**
     * 界面路径（手动渲染 tooltip）：与 {@link #append} 同一套过滤 + 提示行，然后直接渲染。
     * <p><b>为什么必须由本类渲染</b>：本模组 GUI 不会自动渲染 tooltip，各处都有自己的一份
     * 「转 FormattedCharSequence → renderTooltip」样板；集中到这里后，过滤语义与渲染语义不会再分叉。</p>
     */
    public static void render(final GuiGraphics guiGraphics, final Font font, final List<Line> lines,
                              final int mouseX, final int mouseY) {
        render(guiGraphics, font, List.of(), lines, mouseX, mouseY);
    }

    /**
     * 界面路径（手动渲染 tooltip）：{@code always} 是必须常显的行（物品自身 tooltip、槽位的状态结论…），
     * {@code layered} 是按层显示的附加行；两者拼成同一份 tooltip 渲染（同一格只画一份，不叠框）。
     */
    public static void render(final GuiGraphics guiGraphics, final Font font,
                              final List<Component> always, final List<Line> lines,
                              final int mouseX, final int mouseY) {
        final List<Component> filtered = new ArrayList<>();
        if (always != null) {
            filtered.addAll(always);
        }
        append(filtered, lines);
        if (filtered.isEmpty()) {
            return;
        }
        final List<FormattedCharSequence> wrapped = new ArrayList<>(filtered.size());
        for (final Component line : filtered) {
            wrapped.add(line.getVisualOrderText());
        }
        guiGraphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    // ==================== 「整份都是附加信息」的便捷出口 ====================

    /**
     * 便捷入口：<b>整份</b> tooltip 都是本模组附加信息时用它 —— 每一行都归 <b>SHIFT 层</b>。
     *
     * <h2>为什么需要它（并且只能有一个）</h2>
     * <p>工程里有几十处「控制说明 / 操作提示」型 tooltip（「点这里会怎样」「滚轮翻页」…），
     * 它们每一份都只有<b>一个</b>信息层面，逐处展开成 {@link Line} 只会制造噪音。
     * 这里把「全部归 SHIFT」这条规则固定下来，各处只需换一个调用点，语义仍然只有一份。</p>
     * <p>如果某处确实有多个层面（数值 / 内部 id…），就<b>不要</b>用本方法，改用
     * {@link #render} + 显式的 {@link #shift}/{@link #ctrl}/{@link #alt}。</p>
     *
     * @param lines 本模组附加的行（调用方不要把「物品自身 tooltip / 状态结论」混进来）
     */
    public static void renderAttached(final GuiGraphics guiGraphics, final Font font,
                                      final List<Component> lines,
                                      final int mouseX, final int mouseY) {
        render(guiGraphics, font, allShift(lines), mouseX, mouseY);
    }

    /** item 路径的「整份都是附加信息」便捷入口（{@code appendHoverText} 用）。 */
    public static void appendAttached(final List<Component> tooltip, final List<Component> lines) {
        append(tooltip, allShift(lines));
    }

    /** 单行附加信息的便捷入口（控制说明 / 操作提示：整条只有一行时最常见）。 */
    public static void renderAttached(final GuiGraphics guiGraphics, final Font font,
                                      @Nullable final Component line,
                                      final int mouseX, final int mouseY) {
        renderAttached(guiGraphics, font, line == null ? List.of() : List.of(line), mouseX, mouseY);
    }

    /** 把一串普通行全部标记成 SHIFT 层（null / 空表 → 空表）。 */
    public static List<Line> allShift(final List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        final List<Line> layered = new ArrayList<>(lines.size());
        for (final Component line : lines) {
            if (line != null) {
                layered.add(shift(line));
            }
        }
        return layered;
    }

    /** 该组行里出现过内容的全部层面。 */
    public static EnumSet<Layer> contentLayers(final List<Line> lines) {
        final EnumSet<Layer> available = EnumSet.noneOf(Layer.class);
        if (lines != null) {
            for (final Line line : lines) {
                if (line != null && line.text() != null && line.layer() != null) {
                    available.add(line.layer());
                }
            }
        }
        return available;
    }

    /**
     * 提示行：只列出「有内容但当前<b>没按住</b>」的层面 ——
     * 全都没按 = 「按住 Shift/Ctrl 查看更多」；按了一部分 = 提醒还有别的层；
     * 全按住 = 没有更多可看，返回 {@code null}（不加多余的一行）。
     */
    public static Component hintLine(final EnumSet<Layer> available, final EnumSet<Layer> held) {
        final List<Layer> missing = new ArrayList<>(3);
        for (final Layer layer : Layer.values()) {
            if (available.contains(layer) && !held.contains(layer)) {
                missing.add(layer);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        Component keys = Component.empty();
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                keys = keys.copy().append(Component.literal("/").withStyle(HINT_COLOR));
            }
            keys = keys.copy().append(missing.get(i).displayName().copy().withStyle(KEY_COLOR));
        }
        return Component.translatable(LANG + "hold", keys).withStyle(HINT_COLOR);
    }

    // ==================== 「组件行」路径（升级槽那种「图标 + 名称」行） ====================

    /**
     * 一行带层面归属的「客户端组件」（图标行不走 {@link Component}，因此单列一套）。
     * <p>语义与 {@link Line} 完全一致：只有对应层按住时才显示。</p>
     */
    public record ComponentLine(Layer layer, ClientTooltipComponent component) {
    }

    public static ComponentLine shiftComponent(final ClientTooltipComponent component) {
        return new ComponentLine(Layer.SHIFT, component);
    }

    public static ComponentLine ctrlComponent(final ClientTooltipComponent component) {
        return new ComponentLine(Layer.CTRL, component);
    }

    /**
     * 组件路径的统一出口：按当前按键过滤后追加，并在末尾补提示行（与 {@link #append} 同一语义）。
     * <p>提示行必须是「文字组件」，因此这里就地构造一个只含提示文字的
     * {@link ClientTooltipComponent}（本模组 GUI 不会自动渲染 tooltip，提示行也必须显式给出）。</p>
     */
    public static void appendComponents(final List<ClientTooltipComponent> out,
                                        final List<ComponentLine> lines) {
        if (out == null || lines == null || lines.isEmpty()) {
            return;
        }
        final EnumSet<Layer> available = EnumSet.noneOf(Layer.class);
        for (final ComponentLine line : lines) {
            if (line != null && line.component() != null && line.layer() != null) {
                available.add(line.layer());
            }
        }
        if (available.isEmpty()) {
            return;
        }
        final EnumSet<Layer> held = heldLayers();
        for (final ComponentLine line : lines) {
            if (held.contains(line.layer())) {
                out.add(line.component());
            }
        }
        final Component hint = hintLine(available, held);
        if (hint != null) {
            out.add(ClientTooltipComponent.create(hint.getVisualOrderText()));
        }
    }
}
