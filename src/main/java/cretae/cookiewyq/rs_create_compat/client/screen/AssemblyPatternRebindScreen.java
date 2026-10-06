package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.ScrollSelectWidget;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket;
import cretae.cookiewyq.rs_create_compat.network.AssemblyPatternStepMachinePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「总样板改绑机器」子界面：列出这张总样板的每一步（配方类型 + 当前机器），
 * 选中一步后点「改绑机器」→ 复用既有的 {@link StepMachineSelectScreen}（可搜索）挑一台执行舱。
 *
 * <h2>为什么要有这个界面（用户原话）</h2>
 * <p><i>「每一次都在做一个样板太麻烦了……我想着能不能在制作好的样板上进行更改」</i> ——
 * 总样板绑定机器（同一种配方类型可能有多台机器持有，必须明确是哪一台），
 * 但一条样板里可能有<b>多个同类步骤</b>（两步冲压分给两台冲压机），
 * 所以「右键即改绑」这种一次点击表达不了「改哪一步」。因此：右键<b>空气</b>只负责打开本界面，
 * 步与机器全部由玩家在界面里明确指定，写入由服务端复核后生效。机器候选来自服务端按玩家身边
 * 执行舱解析出的网络（已按链去重），因此不再需要目标方块。</p>
 *
 * <h2>界面只镜像服务端</h2>
 * <p>界面数据全部来自 {@link AssemblyPatternRebindOpenPacket}；每次写入成功后服务端会<b>重发</b>同一份
 * 快照，本界面就地 {@link #update}（不重开界面，避免把玩家正开着的「选择机器」子界面顶掉）。
 * 确认时只发一次 C2S 请求，客户端不写任何权威状态。</p>
 */
public class AssemblyPatternRebindScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.assembly_pattern_rebind.";
    private static final int PANEL_W = 232;
    private static final int PANEL_H = 124;
    private static final int PAD = 10;
    private static final int BOX_H = 16;
    private static final int TITLE_Y = 10;
    private static final int LABEL_Y = 30;
    private static final int PICKER_Y = 42;
    private static final int CURRENT_Y = 66;
    private static final int HINT_Y = 80;
    private static final int ACTION_Y = 98;
    private static final int ACTION_W = 100;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_VALUE = 0xFF2E5C8A;
    private static final int COLOR_WARN = 0xFF8A2E2E;

    /** 样板所在的手（写入时必须回到同一只手，见 {@link AssemblyPatternStepMachinePacket}）。 */
    private final int handOrdinal;
    /** 样板指纹（服务端复核用；每次权威刷新都会更新成改写后的新值）。 */
    private UUID patternId;
    private List<AssemblyPatternRebindOpenPacket.Step> steps;
    private List<SyncChamberListPacket.Entry> chambers;
    /** 当前选中的步下标（刷新数据后保持不变；步表空了就归零）。 */
    private int selectedIndex;

    @Nullable
    private ScrollSelectWidget<AssemblyPatternRebindOpenPacket.Step> picker;
    @Nullable
    private Button changeButton;

    public AssemblyPatternRebindScreen(final AssemblyPatternRebindOpenPacket packet,
                                       @Nullable final Screen parent) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.handOrdinal = packet.handOrdinal();
        this.patternId = packet.patternId();
        this.steps = List.copyOf(packet.steps());
        this.chambers = List.copyOf(packet.chambers());
    }

    @Override
    protected void init() {
        super.init();
        picker = new ScrollSelectWidget<AssemblyPatternRebindOpenPacket.Step>(
            font, px + PAD, py + PICKER_Y, PANEL_W - 2 * PAD, BOX_H)
            .forOptions(steps)
            .titled(Component.translatable(LANG + "picker"))
            .displaying(step -> Component.translatable(LANG + "step",
                step.stepIndex() + 1, RecipeTypeNames.display(step.recipeType())))
            .hinting(Component.translatable(LANG + "picker.hint"))
            // 内部 id 归 Alt 层（与工程其它界面同一套附加信息分层）。
            // 本控件的附加行接口只收 Component（它自己不认「层面」），因此这里经唯一入口
            // componentVisible 问「Alt 是否按住」——按键状态照旧只在 RsccTooltipLayers 里读一次，
            // 界面不得自行判断修饰键（工程硬约束）。
            .showingExtraTooltip(step -> RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.ALT)
                ? List.of(RecipeTypeNames.idLine(step.recipeType()))
                : List.<Component>of())
            .writingTo(index -> selectedIndex = index)
            .selectable(!steps.isEmpty());
        picker.setState(selectedIndex);
        addRenderableWidget(picker);

        changeButton = new Button.Builder(Component.translatable(LANG + "change"), button -> openMachineSelect())
            .bounds(px + PAD, py + ACTION_Y, ACTION_W, BOX_H).build();
        addRenderableWidget(changeButton);
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "close"), button -> returnToParent())
            .bounds(px + PAD + ACTION_W + 8, py + ACTION_Y,
                PANEL_W - 2 * PAD - ACTION_W - 8, BOX_H).build());
        refreshButtons();
    }

    /**
     * 就地刷新（服务端每次写入成功后重发权威快照时调用）。
     *
     * <p>手不一致 ⇒ 忽略（理论上不可能：同一只手才会走到本界面）；
     * 刷新时保留「玩家选中的那一步」，否则改完一步界面就跳回第 1 步，连改多步会很难受。</p>
     */
    public void update(final AssemblyPatternRebindOpenPacket packet) {
        if (packet.handOrdinal() != handOrdinal) {
            return;
        }
        this.patternId = packet.patternId();
        this.steps = List.copyOf(packet.steps());
        this.chambers = List.copyOf(packet.chambers());
        if (picker != null) {
            picker.setOptions(steps);
            picker.setState(selectedIndex);
            picker.selectable(!steps.isEmpty());
        }
        refreshButtons();
    }

    /** 当前选中的那一步（没有步骤 / 表为空时返回 null）。 */
    @Nullable
    private AssemblyPatternRebindOpenPacket.Step selectedStep() {
        if (picker == null || picker.getOptions().isEmpty()) {
            return null;
        }
        final AssemblyPatternRebindOpenPacket.Step step = picker.getSelected();
        selectedIndex = picker.getState();
        return step;
    }

    /** 该配方类型下可用的执行舱（服务端已按「已配置」筛过一遍；这里按类型再筛一次）。 */
    private List<SyncChamberListPacket.Entry> candidatesFor(@Nullable final String recipeType) {
        final List<SyncChamberListPacket.Entry> result = new ArrayList<>();
        if (recipeType == null || recipeType.isEmpty()) {
            return result;
        }
        for (final SyncChamberListPacket.Entry chamber : chambers) {
            if (recipeType.equals(chamber.recipeType())) {
                result.add(chamber);
            }
        }
        return result;
    }

    /** 「改绑机器」按钮的可用性：选中了步骤 + 该步有配方类型 + 有候选机器（三者缺一不可）。 */
    private void refreshButtons() {
        if (changeButton == null) {
            return;
        }
        final AssemblyPatternRebindOpenPacket.Step step = selectedStep();
        changeButton.active = step != null && !candidatesFor(step.recipeType()).isEmpty();
    }

    /** 打开既有的机器选择子界面（可搜索）；确认时发一次 C2S 请求，服务端复核后才真正改写。 */
    private void openMachineSelect() {
        final AssemblyPatternRebindOpenPacket.Step step = selectedStep();
        if (step == null || minecraft == null) {
            return;
        }
        final List<SyncChamberListPacket.Entry> candidates = candidatesFor(step.recipeType());
        if (candidates.isEmpty()) {
            return;
        }
        final int stepIndex = step.stepIndex();
        minecraft.setScreen(new StepMachineSelectScreen(this, stepIndex, step.recipeType(), candidates,
            step.hasMachine() ? step.machineName() : "",
            selected -> {
                if (selected != null) {
                    // 只发坐标与步号：机器名由服务端按目标执行舱自己的显示名写入（客户端提交的名字不被采信）
                    PacketDistributor.sendToServer(new AssemblyPatternStepMachinePacket(
                        handOrdinal, patternId, stepIndex, selected.pos()));
                }
            }));
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        refreshButtons(); // 选中项可能刚被滚轮 / 点击改过：每帧同步一次按钮可用性（只改 active）
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "steps"),
            px + PAD, py + LABEL_Y, COLOR_TEXT, false);
        renderCurrentLine(guiGraphics);
        renderHintLine(guiGraphics);
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 「当前机器：X / 未指派」一行（颜色区分：已指派为强调色，未指派为普通灰）。 */
    private void renderCurrentLine(final GuiGraphics guiGraphics) {
        final AssemblyPatternRebindOpenPacket.Step step = selectedStep();
        final Component line;
        final int color;
        if (step == null) {
            line = Component.translatable(LANG + "current.none");
            color = COLOR_TEXT;
        } else if (step.hasMachine() && !step.machineName().isEmpty()) {
            line = Component.translatable(LANG + "current", step.machineName());
            color = COLOR_VALUE;
        } else {
            line = Component.translatable(LANG + "current.none");
            color = COLOR_TEXT;
        }
        guiGraphics.drawString(font, line, px + PAD, py + CURRENT_Y, color, false);
    }

    /**
     * 提示行：把「为什么现在点不了」直接写出来（空样板 / 该步没有配方类型 / 该类型没有机器），
     * 否则玩家只会看到一颗灰按钮而不知道下一步该做什么。
     */
    private void renderHintLine(final GuiGraphics guiGraphics) {
        final AssemblyPatternRebindOpenPacket.Step step = selectedStep();
        Component line;
        int color = COLOR_TEXT;
        if (steps.isEmpty()) {
            line = Component.translatable(LANG + "empty");
        } else if (step == null || step.recipeType().isEmpty()) {
            line = Component.translatable(LANG + "no_recipe_type");
        } else if (candidatesFor(step.recipeType()).isEmpty()) {
            line = Component.translatable(LANG + "no_candidate");
            color = COLOR_WARN;
        } else {
            line = Component.translatable(LANG + "hint");
        }
        guiGraphics.drawString(font, line, px + PAD, py + HINT_Y, color, false);
    }

    /** 手动 tooltip：判定范围与控件绘制范围同源（picker / 两颗按钮 / 当前机器那一行）。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (picker != null && picker.inBounds(mouseX, mouseY)) {
            picker.renderTooltip(guiGraphics, mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + LABEL_Y, PANEL_W - 2 * PAD, PICKER_Y - LABEL_Y, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "steps.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + CURRENT_Y, PANEL_W - 2 * PAD, HINT_Y - CURRENT_Y, mouseX, mouseY)
            || isHovering(px + PAD, py + HINT_Y, PANEL_W - 2 * PAD, ACTION_Y - HINT_Y, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "current.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "change.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD + ACTION_W + 8, py + ACTION_Y,
            PANEL_W - 2 * PAD - ACTION_W - 8, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "close.tip"), mouseX, mouseY);
        }
    }

    private static boolean isHovering(final int x, final int y, final int w, final int h,
                                      final double mouseX, final double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
