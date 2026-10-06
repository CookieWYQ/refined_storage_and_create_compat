package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.ItemCycleTooltipComponent;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RecipeTypeIcons;
import cretae.cookiewyq.rs_create_compat.client.widget.ScrollSelectWidget;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.network.CreateUnitPatternPacket;
import cretae.cookiewyq.rs_create_compat.network.RequestRecipeTypesPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncRecipeTypesPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成单元样板的「配置」子界面：收集新语义（v4）的三项基础信息后再发 C2S 创建包。
 * <ol>
 *     <li><b>名字</b>：{@link EditBox}，非空校验；</li>
 *     <li><b>配方类型</b>：{@link ScrollSelectWidget} 滚轮选择控件，候选 = 服务端
 *     {@code SyncRecipeTypesPacket} 下发的全部已注册配方类型（已绑定的排前面），避免手打错 id；</li>
 *     <li><b>是否需要输入原料</b>：按钮切换 是/否；选「是」时取终端「输入」槽物品作为
 *     {@code input}（为空则不可确定，界面给出提示）。</li>
 * </ol>
 * <p>以 <b>父窗口之上的子窗口</b> 形态弹出（见 {@link ChildConfigScreen}）：父界面保持可见并变暗。
 * 确认时发 {@link CreateUnitPatternPacket}，取消 / Esc 返回终端界面。</p>
 * <p>硬规则：所有 {@code drawString} 无阴影、EditBox {@code setTextShadow(false)}；
 * 自绘元素（滚轮控件、按钮、输入物预览）的 tooltip 全部手动渲染，hover 判定与绘制范围同源。</p>
 */
public class UnitPatternConfigScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.unit_pattern_config.";

    private static final int PANEL_W = 240;
    /** 面板高 180：给按钮下方的「缺项提示」留出位置，避免压到 3px 边框。 */
    private static final int PANEL_H = 180;
    private static final int BOX_H = 16;

    private static final int TITLE_X = 10;
    private static final int TITLE_Y = 9;
    private static final int NAME_LABEL_Y = 28;
    private static final int NAME_BOX_Y = 40;
    private static final int NAME_BOX_W = 220;
    private static final int RECIPE_LABEL_Y = 64;
    private static final int RECIPE_BOX_Y = 76;
    private static final int RECIPE_BOX_W = 220;
    /** 配方类型选择框下方的「代表物品」图标轮播行（18×18，与 tooltip 判定范围同源）。 */
    private static final int ICON_ROW_Y = 96;
    private static final int ICON_SIZE = 18;
    private static final int TOGGLE_Y = 118;
    private static final int TOGGLE_W = 130;
    private static final int INPUT_ICON_X = 150;
    private static final int INPUT_ICON_Y = 118;
    private static final int INPUT_TEXT_X = 170;
    private static final int INPUT_TEXT_Y = 122;
    private static final int ACTION_Y = 144;
    private static final int ACTION_W = 118;

    /** 文字颜色（无阴影；浅灰凹陷面板上用深色字，警示用红）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_WARN = 0xFFB03030;

    /** 打开界面时终端「输入」槽的物品快照（选「需要输入原料」时作为单元样板 input）。 */
    private final ItemStack inputStack;
    /** 被编辑的已有单元样板数据（null = 新建）。 */
    @Nullable
    private final SequencePatternData.UnitData editing;
    /** 写入目标：{@link CreateUnitPatternPacket#TARGET_FIRST_EMPTY_LIBRARY}（新建）或单元样板库全局下标（编辑写回原格）。 */
    private final int targetSlot;
    /** 初始（被编辑样板的）配方类型：候选到达后据此回填，玩家手动改过就不再覆盖。 */
    private final String initialRecipeType;
    /** 玩家是否手动改过配方类型。 */
    private boolean recipeTouched;

    /** 配方类型滚轮选择控件（候选 = 服务端下发的全部已注册配方类型，已绑定的排前面）。 */
    private ScrollSelectWidget<String> recipePicker;
    /** 上一帧的候选快照：变化时才回填控件。 */
    private List<String> lastCandidates = List.of();
    private EditBox nameBox;
    private Button toggleButton;
    private Button confirmButton;
    private boolean requiresInput;
    /** 当前配方类型的「代表物品」图标轮播列表（每帧刷新；空列表 = 不绘制图标行）。 */
    private List<ItemStack> currentIcons = List.of();

    public UnitPatternConfigScreen(final Screen parent, final ItemStack inputStack) {
        this(parent, inputStack, ItemStack.EMPTY, CreateUnitPatternPacket.TARGET_FIRST_EMPTY_LIBRARY);
    }

    /**
     * @param inputStack 终端「输入」槽快照（需要输入原料时作为单元样板 input）
     * @param editingStack 被编辑的已有单元样板物品（空 = 新建）
     * @param targetSlot 写入目标：{@code TARGET_FIRST_EMPTY_LIBRARY} = 库第一个空位；{@code >= 0} = 库该全局下标（编辑写回原格）
     */
    public UnitPatternConfigScreen(final Screen parent,
                                   final ItemStack inputStack,
                                   final ItemStack editingStack,
                                   final int targetSlot) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.targetSlot = targetSlot;
        final ItemStack source = editingStack == null ? ItemStack.EMPTY : editingStack;
        this.editing = source.isEmpty() ? null : SequencePatternData.readUnit(source, RegistryAccess.EMPTY);
        // 需要输入原料时的输入物：优先用被编辑样板里记录的输入物，否则回退到终端输入槽快照
        final ItemStack fromUnit = this.editing != null && this.editing.input() != null
            ? this.editing.input() : ItemStack.EMPTY;
        final ItemStack fallback = inputStack == null ? ItemStack.EMPTY : inputStack;
        this.inputStack = fromUnit.isEmpty() ? fallback.copy() : fromUnit.copy();
        this.requiresInput = this.editing != null && this.editing.requiresInput();
        this.initialRecipeType = this.editing == null || this.editing.recipeType() == null
            ? "" : this.editing.recipeType();
        // 配方类型候选不在这里构造：由服务端 SyncRecipeTypesPacket 下发（含全部已注册类型）
    }

    @Override
    protected void init() {
        super.init();

        nameBox = new EditBox(font, px + TITLE_X, py + NAME_BOX_Y, NAME_BOX_W, BOX_H,
            Component.translatable(LANG + "name.hint"));
        nameBox.setMaxLength(64);
        nameBox.setTextShadow(false);
        nameBox.setValue(editing == null || editing.displayName() == null ? "" : editing.displayName());
        addRenderableWidget(nameBox);
        setFocused(nameBox);

        // 配方类型：滚轮选择控件（候选由服务端下发；打开界面时请求）
        lastCandidates = SyncRecipeTypesPacket.getLastReceived();
        recipeTouched = false;
        recipePicker = new ScrollSelectWidget<String>(font,
            px + TITLE_X, py + RECIPE_BOX_Y, RECIPE_BOX_W, BOX_H)
            .forOptions(lastCandidates)
            .titled(Component.translatable(LANG + "recipe_type"))
            .displaying(RecipeTypeNames::display)
            .hinting(Component.translatable(LANG + "recipe_type.hint"))
            .writingTo(index -> recipeTouched = true)
            // 滚轮 tooltip 最底部追加当前选中项的原始配方类型 id（暗灰+斜体，低调）
            .showingExtraTooltip(id -> List.of(RecipeTypeNames.idLine(id)));
        recipePicker.setState(indexOfOption(initialRecipeType));
        addRenderableWidget(recipePicker);
        PacketDistributor.sendToServer(new RequestRecipeTypesPacket());

        // 是否需要输入原料：按钮切换 是/否
        toggleButton = new Button.Builder(requiresInputLabel(), button -> {
            requiresInput = !requiresInput;
            button.setMessage(requiresInputLabel());
        }).bounds(px + TITLE_X, py + TOGGLE_Y, TOGGLE_W, BOX_H).build();
        addRenderableWidget(toggleButton);

        confirmButton = new Button.Builder(Component.translatable(LANG + "confirm"),
            button -> confirmAndReturn())
            .bounds(px + TITLE_X, py + ACTION_Y, ACTION_W, BOX_H).build();
        addRenderableWidget(confirmButton);
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "cancel"),
            button -> returnToParent())
            .bounds(px + TITLE_X + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H).build());
    }

    private Component requiresInputLabel() {
        return Component.translatable(LANG + "requires_input",
            Component.translatable(LANG + (requiresInput ? "yes" : "no")));
    }

    /** 服务端候选到达后回填控件（保留原选择；候选未到达时控件显示「无可用选项」）。 */
    private void syncRecipeCandidates() {
        if (recipePicker == null) {
            return;
        }
        final List<String> candidates = SyncRecipeTypesPacket.getLastReceived();
        if (candidates.equals(lastCandidates)) {
            return;
        }
        final String previous = recipePicker.getSelected();
        lastCandidates = candidates;
        recipePicker.setOptions(candidates);
        if (!recipeTouched && !initialRecipeType.isEmpty()) {
            // 玩家还没动过控件：优先对齐被编辑样板的配方类型（避免沿用上一次的陈旧选择）
            recipePicker.setState(indexOfOption(initialRecipeType));
            return;
        }
        recipePicker.setState(previous == null ? 0 : indexOfOption(previous));
    }

    /** 在候选列表里定位某配方类型（找不到回退 0）。 */
    private int indexOfOption(final String recipeType) {
        final List<String> options = recipePicker.getOptions();
        if (recipeType != null && !recipeType.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).equals(recipeType)) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** 三项信息是否齐备（名字非空 + 已选配方类型 + 需要输入时输入槽非空）。 */
    private boolean canConfirm() {
        return missingHint() == null;
    }

    /** 缺失项提示（全部齐备返回 null）：让玩家知道「确定」为什么点不动，而不是以为界面坏了。 */
    @Nullable
    private Component missingHint() {
        final List<Component> missing = new ArrayList<>();
        if (nameBox == null || nameBox.getValue().trim().isEmpty()) {
            missing.add(Component.translatable(LANG + "missing.name"));
        }
        if (recipePicker == null || recipePicker.getSelected() == null) {
            missing.add(Component.translatable(LANG + "missing.recipe_type"));
        }
        if (requiresInput && inputStack.isEmpty()) {
            missing.add(Component.translatable(LANG + "missing.input"));
        }
        if (missing.isEmpty()) {
            return null;
        }
        Component joined = missing.get(0);
        for (int i = 1; i < missing.size(); i++) {
            joined = Component.empty().append(joined).append(" / ").append(missing.get(i));
        }
        return Component.translatable(LANG + "missing", joined);
    }

    private void confirmAndReturn() {
        if (!canConfirm()) {
            return;
        }
        final String name = nameBox.getValue().trim();
        final String recipeType = recipePicker.getSelected();
        final ItemStack input = requiresInput ? inputStack : ItemStack.EMPTY;
        // 目标槽：新建 = 单元样板库第一个空位；编辑 = 原格全局下标（服务端校验并写入）
        PacketDistributor.sendToServer(
            new CreateUnitPatternPacket(recipeType, name, requiresInput, input, targetSlot));
        returnToParent();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        syncRecipeCandidates();
        confirmButton.active = canConfirm();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 标题 / 字段标签 / 输入物（全部无阴影，深色字配浅灰凹陷面板）；编辑已有样板时标题明确标注是「编辑」
        guiGraphics.drawString(font, editing == null ? title : Component.translatable(LANG + "title.edit"),
            px + TITLE_X, py + TITLE_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "name"),
            px + TITLE_X, py + NAME_LABEL_Y, COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "recipe_type"),
            px + TITLE_X, py + RECIPE_LABEL_Y, COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "input"),
            px + TITLE_X, py + INPUT_TEXT_Y, COLOR_TEXT, false);
        // 配方类型「代表物品」图标轮播：紧贴配方类型选择框下方（JEI 风格，多个时按墙钟时间轮播）
        currentIcons = recipePicker == null || recipePicker.getSelected() == null
            ? List.of()
            : RecipeTypeIcons.forRecipeType(recipePicker.getSelected(), inputStack);
        ItemCycleTooltipComponent.renderCycleIcon(guiGraphics, currentIcons, px + TITLE_X, py + ICON_ROW_Y);
        // 输入物预览（16×16 与 tooltip 判定范围一致）
        if (!inputStack.isEmpty()) {
            guiGraphics.renderItem(inputStack, px + INPUT_ICON_X, py + INPUT_ICON_Y);
        }
        // 「需要输入原料」但输入槽为空：给出不可确定的即时提示
        if (requiresInput && inputStack.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable(LANG + "input.missing"),
                px + TITLE_X, py + INPUT_TEXT_Y + 12, COLOR_WARN, false);
        }
        // 缺项提示（红字，紧贴按钮下方）：说明「确定」为何不可用
        final Component hint = missingHint();
        if (hint != null) {
            guiGraphics.drawString(font, hint, px + TITLE_X, py + ACTION_Y + BOX_H + 2, COLOR_WARN, false);
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 手动渲染 tooltip：判定范围与控件绘制范围完全一致（左闭右开）。
     *  <p>本模组附加说明统一走唯一实现 {@link RsccTooltipLayers}（默认收起，按住 Shift 才展开）；
     *  物品自身 tooltip 与选择器的候选列表保持常显。</p> */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (isHovering(px + INPUT_ICON_X, py + INPUT_ICON_Y, 16, 16, mouseX, mouseY)) {
            if (!inputStack.isEmpty()) {
                // 物品自身 tooltip：原版信息，常显
                guiGraphics.renderTooltip(font, inputStack, mouseX, mouseY);
            } else {
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                    guiGraphics, font, Component.translatable(LANG + "input.missing"), mouseX, mouseY);
            }
            return;
        }
        if (isHovering(px + TITLE_X, py + NAME_LABEL_Y, NAME_BOX_W,
            NAME_BOX_Y + BOX_H - NAME_LABEL_Y, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(LANG + "name.tip"), mouseX, mouseY);
            return;
        }
        // 配方类型滚轮控件：标题 + 16 行候选窗口（控件自绘，必须手动渲染 tooltip）
        if (recipePicker != null && recipePicker.inBounds(mouseX, mouseY)) {
            recipePicker.renderTooltip(guiGraphics, mouseX, mouseY);
            return;
        }
        // 配方类型代表物品图标（自绘，必须手动渲染 tooltip；判定范围与绘制范围一致）
        if (!currentIcons.isEmpty()
            && isHovering(px + TITLE_X, py + ICON_ROW_Y, ICON_SIZE, ICON_SIZE, mouseX, mouseY)) {
            final ItemStack shown = currentIcons.get(
                ItemCycleTooltipComponent.currentIndex(currentIcons.size()));
            // 代表物自身 tooltip：原版信息，常显
            guiGraphics.renderTooltip(font, shown, mouseX, mouseY);
            return;
        }
        if (isHovering(px + TITLE_X, py + RECIPE_LABEL_Y, RECIPE_BOX_W,
            RECIPE_BOX_Y - RECIPE_LABEL_Y, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(LANG + "recipe_type.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + TITLE_X, py + TOGGLE_Y, TOGGLE_W, BOX_H, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(LANG + "requires_input.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + TITLE_X, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    canConfirm() ? LANG + "confirm.tip" : LANG + "confirm.disabled"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + TITLE_X + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(LANG + "cancel.tip"), mouseX, mouseY);
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
