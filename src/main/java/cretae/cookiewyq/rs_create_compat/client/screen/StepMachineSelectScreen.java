package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.ScrollSelectWidget;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.network.SetStepMachinePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 终端「某一步的机器选择」小弹窗：用 {@link ScrollSelectWidget} 列出该步配方类型对应的执行仓，
 * 并可<b>按名称搜索</b>（用户要求：选择界面支持搜索）。
 * <p>以 <b>父窗口之上的子窗口</b> 形态弹出（见 {@link ChildConfigScreen}）：父界面（终端）保持可见并变暗。
 * 候选由终端界面按该步 recipeType 过滤后传入（已排除不匹配的机器，名称唯一）；
 * 滚轮或点击左右半边切换，点「确定」发 {@link SetStepMachinePacket} 并返回终端界面。</p>
 * <p><b>同一个类被两处复用</b>（用户明确要求「不要写两套」）：</p>
 * <ol>
 *     <li>序列装配样板终端：①点击行内机器控件横带 ②Ctrl+左键点击该步骤整行（都由
 *     {@code SequencePatternTerminalScreen#openMachineSelect} 打开）；</li>
 *     <li>自动合成监视器的「更换机器」：{@code AssemblyAlertsClient#openMachineSelect} 传入
 *     {@code confirmSink}，确认时发的是它自己的 {@code AssemblyStepMachinePacket}。</li>
 * </ol>
 * <p>硬规则：所有 {@code drawString} 无阴影；自绘元素（滚轮控件 / 搜索框）的 tooltip 全部手动渲染，
 * 且 hover 判定与绘制范围同源。</p>
 */
public class StepMachineSelectScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.step_machine_select.";

    /** 面板尺寸（比无搜索版高 20px：多出一整行搜索框，其余行整体下移，绝不重叠）。 */
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 128;
    private static final int PAD = 10;
    private static final int BOX_H = 16;
    private static final int TITLE_Y = 10;
    private static final int STEP_Y = 26;
    private static final int RECIPE_Y = 38;
    /** 搜索框行（新增）。 */
    private static final int SEARCH_Y = 54;
    private static final int WIDGET_Y = 76;
    private static final int WIDGET_W = PANEL_W - 2 * PAD;
    private static final int ACTION_Y = 102;
    private static final int ACTION_W = 88;

    /** 文字颜色（无阴影；浅灰凹陷面板上用深色字）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_RECIPE = 0xFF2E5C8A;

    /** 全局步骤下标（与终端 arrangement 下标一致）。 */
    private final int globalStep;
    /** 该步的配方类型 id（用于展示）。 */
    private final String recipeType;
    /** 该步可选的机器（已按 recipeType 过滤，按坐标排序）——<b>未经搜索过滤</b>的完整候选。 */
    private final List<SyncChamberListPacket.Entry> candidates;
    /** 该步当前已指派的机器名（用于初始高亮；未指派为空串）。 */
    private final String currentMachineName;
    /**
     * 可选的「确认回调」：非 null 时用它替代样板终端的 {@link SetStepMachinePacket}
     * （自动合成管理器复用本选择器做「更换机器」时走自己的服务端包）。
     */
    @org.jetbrains.annotations.Nullable
    private final java.util.function.Consumer<SyncChamberListPacket.Entry> confirmSink;

    private ScrollSelectWidget<SyncChamberListPacket.Entry> picker;
    /** 搜索框：按机器名做不区分大小写的子串过滤（空查询 = 全部）。 */
    private EditBox searchBox;

    public StepMachineSelectScreen(final Screen parent, final int globalStep, final String recipeType,
                                   final List<SyncChamberListPacket.Entry> candidates,
                                   final String currentMachineName) {
        this(parent, globalStep, recipeType, candidates, currentMachineName, null);
    }

    /**
     * 带确认回调的重载（复用同一套 UI；样板终端仍走上面的 5 参构造器，行为零变化）。
     * {@code confirmSink} 非空时点「确定」调用它（由调用方发自己的服务端包）而不是发终端包。
     */
    public StepMachineSelectScreen(final Screen parent, final int globalStep, final String recipeType,
                                   final List<SyncChamberListPacket.Entry> candidates,
                                   final String currentMachineName,
                                   @org.jetbrains.annotations.Nullable final java.util.function.Consumer<
                                       SyncChamberListPacket.Entry> confirmSink) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.globalStep = globalStep;
        this.recipeType = recipeType == null ? "" : recipeType;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        this.currentMachineName = currentMachineName == null ? "" : currentMachineName;
        this.confirmSink = confirmSink;
    }

    @Override
    protected void init() {
        super.init();

        // 先建候选控件（含完整候选），再建搜索框并立刻套一次过滤（初始查询为空 → 与旧行为一致）
        picker = new ScrollSelectWidget<SyncChamberListPacket.Entry>(font,
            px + PAD, py + WIDGET_Y, WIDGET_W, BOX_H)
            .forOptions(candidates)
            .titled(Component.translatable(LANG + "title"))
            .displaying(entry -> Component.literal(entry.name()))
            .hinting(Component.translatable(LANG + "hint"))
            .selectable(!candidates.isEmpty());
        picker.setState(indexOfCurrent(candidates));
        addRenderableWidget(picker);

        searchBox = new EditBox(font, px + PAD, py + SEARCH_Y, WIDGET_W, BOX_H,
            Component.translatable(LANG + "search_hint"));
        searchBox.setMaxLength(64);
        searchBox.setTextShadow(false);
        searchBox.setResponder(text -> applyFilter());
        addRenderableWidget(searchBox);
        applyFilter();

        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "confirm"),
            button -> confirmAndReturn())
            .bounds(px + PAD, py + ACTION_Y, ACTION_W, BOX_H).build());
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "cancel"),
            button -> returnToParent())
            .bounds(px + PAD + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H).build());
    }

    /**
     * 按搜索框内容重算候选（不区分大小写的子串匹配；空查询 = 全部）。
     * <p>过滤后仍尽量保持「当前已指派的那台」被选中；它被过滤掉时收敛到第一条匹配
     * （玩家搜的就是想选的那台，这是期望行为）。候选为空时控件退回不可交互（灰显），
     * 确定不会把一个假选项写回去 —— {@link #confirmAndReturn} 对 null 选择直接返回。</p>
     */
    private void applyFilter() {
        if (picker == null) {
            return;
        }
        final String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        final List<SyncChamberListPacket.Entry> filtered = new ArrayList<>();
        for (final SyncChamberListPacket.Entry entry : candidates) {
            if (query.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(entry);
            }
        }
        picker.setOptions(filtered);
        picker.setState(indexOfCurrent(filtered));
        picker.selectable(!filtered.isEmpty());
    }

    /** 当前已指派机器在候选里的位置（找不到回退 0）。 */
    private int indexOfCurrent(final List<SyncChamberListPacket.Entry> options) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).name().equals(currentMachineName)) {
                return i;
            }
        }
        return 0;
    }

    /** 确定：把该步指派到所选机器（服务端权威写入）并返回终端界面。 */
    private void confirmAndReturn() {
        final SyncChamberListPacket.Entry selected = picker == null ? null : picker.getSelected();
        if (confirmSink != null) {
            // 复用本选择器的调用方（自动合成管理器「更换机器」）自带服务端包
            if (selected != null) {
                confirmSink.accept(selected);
            }
            returnToParent();
            return;
        }
        if (selected != null) {
            PacketDistributor.sendToServer(
                new SetStepMachinePacket(globalStep, true, selected.pos(), selected.name()));
        }
        returnToParent();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 标题 / 步骤 / 配方类型（全部无阴影，深色字配浅灰凹陷面板）
        guiGraphics.drawString(font, title, px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "step", globalStep + 1),
            px + PAD, py + STEP_Y, COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "recipe_type",
                RecipeTypeNames.display(recipeType)),
            px + PAD, py + RECIPE_Y, COLOR_RECIPE, false);
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 手动渲染 tooltip：判定范围与控件绘制范围完全一致（左闭右开）。
     *  <p>控制说明属于本模组附加信息：默认收起，按住 Shift 才展开；配方类型的<b>内部 id</b> 归 Alt 层
     *  （唯一实现 {@link RsccTooltipLayers}）；选择器本身的候选列表保持常显（它是控件功能）。</p> */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (searchBox != null && isHovering(searchBox.getX(), searchBox.getY(),
            searchBox.getWidth(), searchBox.getHeight(), mouseX, mouseY)) {
            // 搜索框：说明它按「机器名」过滤 + 当前命中几台（自绘/自管的控件都必须手动给提示）
            RsccTooltipLayers.renderAttached(guiGraphics, font, Component.translatable(LANG + "search.tip",
                picker == null ? 0 : picker.getOptions().size(), candidates.size()), mouseX, mouseY);
            return;
        }
        if (picker != null && picker.inBounds(mouseX, mouseY)) {
            picker.renderTooltip(guiGraphics, mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + STEP_Y, WIDGET_W, RECIPE_Y - STEP_Y, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "step.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + RECIPE_Y, WIDGET_W, WIDGET_Y - RECIPE_Y, mouseX, mouseY)) {
            // 配方类型说明（Shift）+ 原始 id（Alt，低调追加在最下方）
            RsccTooltipLayers.render(guiGraphics, font, List.of(),
                List.of(RsccTooltipLayers.shift(Component.translatable(LANG + "recipe_type.tip")),
                    RsccTooltipLayers.alt(RecipeTypeNames.idLine(recipeType))), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "confirm.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "cancel.tip"), mouseX, mouseY);
        }
    }

    private static boolean isHovering(final int x, final int y, final int w, final int h,
                                      final double mouseX, final double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirmAndReturn();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
