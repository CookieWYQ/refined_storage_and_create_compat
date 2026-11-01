package cretae.cookiewyq.rs_create_compat.client.widget;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 「配方类型」选择器 = {@link ScrollSelectWidget}（滚轮 + 左右半区点击）+ 搜索框
 * （用户 2026-10-17 原话：「你给这个配方也加上个搜索功能，和机器那个类似，也可以搜 id，
 * 也可以搜名字，也可以搜英文名，就类似于这样子」）。
 *
 * <h2>为什么做成一个类（而不是每个界面各写一遍）</h2>
 * <p>配方类型下拉框目前有 <b>两处</b>入口：{@code UnitPatternConfigScreen}（生成 / 编辑单元样板）与
 * {@code ChamberBindingConfigScreen}（执行仓绑定）。搜索行为必须完全一致 —— 三键命中、大小写不敏感、
 * 派生名可搜、选中项尽量保持 —— 这些规则只写在<b>本类 + {@link RecipeTypeNames#matchesSearch}</b> 里，
 * 界面只负责几何与文案。{@code tools/selfcheck_round78_recipe_type_l10n.py} 会做源码级断言：
 * 两个界面都必须引用本类，且不得再出现第二套 {@code new EditBox} + 过滤循环。</p>
 *
 * <h2>搜索到底匹配什么</h2>
 * <p>见 {@link RecipeTypeNames#searchText(String)}：一条小写串里同时装了原始 id、折叠 id、
 * <b>当前语言显示名</b>（中文客户端即中文名，含我们派生出来的名字）与英文别名。
 * 因此「按 id / 中文名 / 英文名」是同一套比较，不需要三份代码，也不会出现「三种键规则不一致」。</p>
 *
 * <h2>性能（绝不每帧重算）</h2>
 * <ul>
 *     <li>过滤只在<b>两件事</b>发生时执行：搜索框 responder（玩家敲键 / 粘贴）、候选列表变化
 *     （服务端 {@code SyncRecipeTypesPacket} 到达）。{@code render()} 里一个字符都不比较；</li>
 *     <li>每个 id 的搜索串由 {@link RecipeTypeNames} 缓存（与显示名同一份语言表版本），
 *     一次过滤 = N 次 {@code String.contains}（N = 已注册配方类型数，量级几十）；</li>
 *     <li>查询没变时（例如同一帧内重复调用）直接复用上一次结果，不再扫全表。</li>
 * </ul>
 *
 * <h2>选中项保持</h2>
 * <p>过滤前后尽量保持「玩家之前选的那一条」仍被选中：按 <b>id</b>（不是下标）恢复；它被过滤掉时收敛到
 * 第一条命中（玩家搜的就是想选的，这是期望行为）。过滤本身<b>不</b>触发 {@code writingTo} 回调
 * （否则会把「只是打字」误判成「玩家改过选择」）。</p>
 */
public final class RecipeTypeSelect {
    /** 本控件自己的语言键前缀（tooltip / 提示）。 */
    public static final String LANG = "gui.rs_create_compat.recipe_select.";
    /** 搜索框最大长度（配方类型 id 最长也就几十字符）。 */
    private static final int MAX_QUERY_LENGTH = 64;

    private final ScrollSelectWidget<String> picker;
    private final EditBox searchBox;
    private final int searchX;
    private final int searchY;
    private final int searchW;
    private final int searchH;

    /** 全部候选（未过滤）。界面重建消歧文案 / 回填选中值都必须用它，而不是过滤后的视图。 */
    private List<String> all = List.of();
    /** 过滤后的视图（= 选择控件实际的候选）。 */
    private List<String> filtered = List.of();
    /** 上一次过滤用的查询串：没变就不重算。 */
    private String lastQuery = "";
    /** 控件是否可交互（执行仓里还压着单元样板时锁定配方类型）。 */
    private boolean selectable = true;

    /**
     * @param pickerX/pickerY/pickerW/pickerH 滚轮选择控件的位置（与旧版几何一致）
     * @param searchX/searchY/searchW/searchH 搜索框的位置（在控件下方一行）
     */
    public RecipeTypeSelect(final Font font,
                            final int pickerX, final int pickerY, final int pickerW, final int pickerH,
                            final int searchX, final int searchY, final int searchW, final int searchH) {
        this.picker = new ScrollSelectWidget<String>(font, pickerX, pickerY, pickerW, pickerH);
        this.searchX = searchX;
        this.searchY = searchY;
        this.searchW = searchW;
        this.searchH = searchH;
        this.searchBox = new EditBox(font, searchX, searchY, searchW, searchH,
            Component.translatable(LANG + "search_hint"));
        this.searchBox.setMaxLength(MAX_QUERY_LENGTH);
        this.searchBox.setTextShadow(false);
        // 空值时显示灰字提示（EditBox 的 suggestion 只在内容为空时画）
        this.searchBox.setSuggestion(Component.translatable(LANG + "search_hint").getString());
        this.searchBox.setResponder(text -> {
            // 只在玩家真正改动查询时重算（responder 只在 setValue / 输入时触发）
            if (!text.trim().equals(lastQuery)) {
                rebuild();
            }
        });
    }

    // ==================== 链式配置（全部转发给滚轮控件） ====================

    public RecipeTypeSelect titled(final Component title) {
        picker.titled(title);
        return this;
    }

    public RecipeTypeSelect displaying(final Function<String, Component> display) {
        picker.displaying(display);
        return this;
    }

    public RecipeTypeSelect hinting(final Component hint) {
        picker.hinting(hint);
        return this;
    }

    public RecipeTypeSelect showingExtraTooltip(final Function<String, List<Component>> extra) {
        picker.showingExtraTooltip(extra);
        return this;
    }

    public RecipeTypeSelect writingTo(final Consumer<Integer> onSelect) {
        picker.writingTo(onSelect);
        return this;
    }

    public RecipeTypeSelect selectable(final boolean selectable) {
        this.selectable = selectable;
        picker.selectable(selectable && !filtered.isEmpty());
        return this;
    }

    /** 每一项左侧的 16×16 图标（当前这项的机器方块）；返回空栈表示不画。 */
    public RecipeTypeSelect iconFor(final Function<String, ItemStack> iconProvider) {
        picker.withIcon(iconProvider);
        return this;
    }

    // ==================== 供界面加入控件 / 渲染 ====================

    public ScrollSelectWidget<String> picker() {
        return picker;
    }

    public EditBox searchBox() {
        return searchBox;
    }

    /** 搜索框是否被悬停（tooltip 判定与绘制范围同源）。 */
    public boolean isSearchHovered(final double mouseX, final double mouseY) {
        return mouseX >= searchX && mouseX < searchX + searchW
            && mouseY >= searchY && mouseY < searchY + searchH;
    }

    /**
     * 渲染搜索框自己的 tooltip（「命中 N / 共 M」+ 三键说明）。
     * <p>用 {@link RsccTooltipLayers#render} 的<b>常显</b>参数而不是 {@code renderAttached}：
     * 这条是「这个框能搜什么、现在命中几条」的功能性结论，必须一眼可见（不该藏在 Shift 层）。</p>
     *
     * @return true = 本方法已经画了 tooltip（调用方应停止继续判定别的区域）
     */
    public boolean renderTooltip(final GuiGraphics guiGraphics, final Font font,
                                 final int mouseX, final int mouseY) {
        if (!isSearchHovered(mouseX, mouseY)) {
            return false;
        }
        RsccTooltipLayers.render(guiGraphics, font,
            List.of(Component.translatable(LANG + "search.tip", filtered.size(), all.size())),
            List.of(), mouseX, mouseY);
        return true;
    }

    // ==================== 候选 / 选中 ====================

    /** 替换全部候选（服务端下发的列表）；过滤条件保持不变。 */
    public void setOptions(final List<String> options) {
        final List<String> next = options == null ? List.of() : List.copyOf(options);
        if (next.equals(all)) {
            return;
        }
        all = next;
        rebuild();
    }

    /** 全部候选（未过滤）—— 界面做「同名机器消歧」时必须用它。 */
    public List<String> allOptions() {
        return all;
    }

    /** 过滤后的候选（= 选择控件当前的可选项）。 */
    public List<String> filteredOptions() {
        return filtered;
    }

    /** 当前选中项 id（无候选返回 null）。 */
    @Nullable
    public String selected() {
        return picker.getSelected();
    }

    /** 当前选中的下标（相对过滤后的视图）。 */
    public int state() {
        return picker.getState();
    }

    /** 按 id 选中（找不到时收敛到 0：只可能是过滤把它挡掉了）。 */
    public void select(final String recipeTypeId) {
        final int index = recipeTypeId == null ? -1 : filtered.indexOf(recipeTypeId);
        picker.setState(index < 0 ? 0 : index);
    }

    /** 按 id 静默选中；返回是否命中（界面回填时用，避免「找不到也把选择挪到第一条」）。 */
    public boolean selectIfPresent(final String recipeTypeId) {
        final int index = recipeTypeId == null ? -1 : filtered.indexOf(recipeTypeId);
        if (index < 0) {
            return false;
        }
        picker.setState(index);
        return true;
    }

    /** 清空搜索词（重新打开界面时用；不触发过滤回调链）。 */
    public void resetQuery() {
        searchBox.setValue("");
        lastQuery = "";
        rebuild();
    }

    // ==================== 过滤（唯一实现） ====================

    /**
     * 按当前搜索词重建视图。
     * <p><b>只有这里</b>会遍历候选；调用点只有两个（responder / {@link #setOptions}），
     * 渲染路径绝不进入。命中判定本身在 {@link RecipeTypeNames#matchesSearch(String, String)}，
     * 因此「id / 中文名 / 英文名」三键规则与别处共用一份。</p>
     */
    private void rebuild() {
        lastQuery = searchBox.getValue().trim();
        final String previous = picker.getSelected();
        final List<String> next = new ArrayList<>(Math.min(all.size(), 64));
        for (final String candidate : all) {
            if (RecipeTypeNames.matchesSearch(candidate, lastQuery)) {
                next.add(candidate);
            }
        }
        filtered = next;
        picker.setOptions(filtered);
        picker.selectable(selectable && !filtered.isEmpty());
        // 过滤前后尽量保持同一条（按 id 恢复，不是按下标）
        if (!selectIfPresent(previous)) {
            picker.setState(0);
        }
    }

    /** 供自检 / 调试：当前生效的查询串。 */
    public String currentQuery() {
        return lastQuery;
    }
}
