package cretae.cookiewyq.rs_create_compat.client.tooltip;

import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * 单元样板 tooltip 的「图标区」：最多三行 ——
 * <ol>
 *     <li><b>机器行</b>：能执行该配方类型的机器方块图标（轮播）+ 机器名（与执行舱那边同一口径）；</li>
 *     <li><b>输入原料行</b>：该步输入物的<b>物品图标</b> + 「输入原料：&lt;物品名&gt;」标注
 *     （与「机械手使用配方」那种展示方式一致）；物品的原始 id 由物品 tooltip 的文本行给出
 *     （见 {@code SequenceUnitPatternItem#appendHoverText}），二者合起来 = 图标 + 名字 + id。</li>
 *     <li><b>输入流体行</b>：该步真正消耗的流体（注液 / 灌注一类步骤，例如「注液 → 岩浆」）的
 *     <b>流体贴图</b> + 「输入流体：&lt;流体名&gt; &lt;数量&gt; mB」。用户实测问题：单元样板此前
 *     只显示了物品输入，注液步骤看不出要注的是什么流体。</li>
 * </ol>
 * <p>无机器 / 无输入 / 无流体时对应行不占位（绝不显示空行）；老样板没有流体标记时
 * {@link FluidStack#EMPTY} 直接跳过（优雅降级）。所有文字无阴影（硬规则）。</p>
 */
public class UnitPatternTooltipComponent implements ClientTooltipComponent {
    private static final int ICON_SIZE = ItemCycleTooltipComponent.ICON_SIZE;
    private static final int NAME_GAP = 4;
    private static final String LANG = "item.rs_create_compat.sequence_unit_pattern.";

    private final List<ItemStack> machines;
    private final List<ItemStack> inputCandidates;
    private final ItemStack input;
    private final FluidStack inputFluid;

    public UnitPatternTooltipComponent(final List<ItemStack> machines, final ItemStack input,
                                       final FluidStack inputFluid) {
        this(machines, input, inputFluid, List.of());
    }

    /**
     * @param inputCandidates 该步输入原料组的<b>全部候选</b>（标签型 ingredient，如列车轨道机械手步的
     *                        「铁粒 或 锌粒」）。为空时退回只有代表物一件（老样板）。
     *                        <p>为什么要它（列车轨道：铁粒/锌粒候选未显示）：本组件的接口
     *                        {@code getTooltipImage(ItemStack)} 拿不到 {@code Level}，<b>结构上无法</b>
     *                        回查配方，因此候选组必须随单元样板 NBT 一起带出来
     *                        （见 {@code SequencePatternData#TAG_INPUT_CANDIDATES}）。</p>
     */
    public UnitPatternTooltipComponent(final List<ItemStack> machines, final ItemStack input,
                                       final FluidStack inputFluid,
                                       final List<ItemStack> inputCandidates) {
        this.machines = machines == null ? List.of() : List.copyOf(machines);
        this.input = input == null ? ItemStack.EMPTY : input;
        this.inputFluid = inputFluid == null ? FluidStack.EMPTY : inputFluid;
        final List<ItemStack> normalized = SequencePatternData.normalizeCandidates(inputCandidates);
        this.inputCandidates = normalized.isEmpty() && !this.input.isEmpty()
            ? List.of(this.input) : normalized;
    }

    private ItemStack currentMachine() {
        if (machines.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return machines.get(ItemCycleTooltipComponent.currentIndex(machines.size()));
    }

    /**
     * 当前该显示的输入物（多候选时与机器行同一套轮播节拍 —— 每 {@code CYCLE_TICKS} tick 换一件）。
     * <p>玩家因此能一眼看出「这一步吃的是铁粒或锌粒」，而不是以为只吃铁粒。</p>
     */
    private ItemStack currentInput() {
        if (inputCandidates.isEmpty()) {
            return input;
        }
        if (inputCandidates.size() == 1) {
            return inputCandidates.get(0);
        }
        return inputCandidates.get(ItemCycleTooltipComponent.currentIndex(inputCandidates.size()));
    }

    private Component inputLine() {
        final ItemStack shown = currentInput();
        if (inputCandidates.size() > 1) {
            // 多候选：<b>只写当前轮播到的那一件</b>，另标注本组候选总数。
            // 绝不把候选枚举成「铁粒 或 锌粒 / 铜粒」——用户明确禁止把多种原料拼成一行文字
            // （原话：「禁止出现什么就是把多种原料合并在一起什么什么或什么或什么括号什么这样子的形式」）。
            // 组内到底有哪几种，由图标自己一件一件滚出来，这比文字更直观。
            return Component.translatable(LANG + "input_any",
                shown.getHoverName(), inputCandidates.size());
        }
        return Component.translatable(LANG + "input", shown.getHoverName());
    }

    /** 输入流体行：「输入流体：<流体名> <数量> mB」（数量与总样板 tooltip 同一口径 = mB）。 */
    private Component fluidLine() {
        return Component.translatable(LANG + "input_fluid", inputFluid.getHoverName(),
            inputFluid.getAmount());
    }

    // ==================== 逐行可见性：本模组附加信息默认收起，按修饰键展开 ====================
    // 组件行不走 Component，无法经 RsccTooltipLayers#append 过滤，因此可见性统一问
    // RsccTooltipLayers#componentVisible —— 按键状态依然只在唯一实现里读一次（不在这里自己读 Shift/Ctrl）。
    // 分层归属（信息层面）：机器行 / 输入原料行 = Shift（「这一步由什么机器、吃什么原料跑」= 机制）；
    // 输入流体行 = Ctrl（带数量 mB，属于数值层面）。

    /** 机器行是否显示：有候选机器 且 按住 Shift。 */
    private boolean machineVisible() {
        return !machines.isEmpty()
            && RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT);
    }

    /** 输入原料行是否显示：有输入物（代表物或候选）且 按住 Shift。 */
    private boolean inputVisible() {
        return !input.isEmpty() || !inputCandidates.isEmpty()
            ? RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT)
            : false;
    }

    /** 输入流体行是否显示：有输入流体 且 按住 Ctrl（该行含数量 mB）。 */
    private boolean fluidVisible() {
        return !inputFluid.isEmpty()
            && RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.CTRL);
    }

    @Override
    public int getHeight() {
        return (machineVisible() ? ICON_SIZE : 0) + (inputVisible() ? ICON_SIZE : 0)
            + (fluidVisible() ? ICON_SIZE : 0);
    }

    @Override
    public int getWidth(final Font font) {
        int width = 0;
        if (machineVisible()) {
            width = Math.max(width,
                ICON_SIZE + NAME_GAP + font.width(currentMachine().getHoverName()));
        }
        if (inputVisible()) {
            width = Math.max(width, ICON_SIZE + NAME_GAP + font.width(inputLine()));
        }
        if (fluidVisible()) {
            width = Math.max(width, ICON_SIZE + NAME_GAP + font.width(fluidLine()));
        }
        return width;
    }

    @Override
    public void renderImage(final Font font, final int x, final int y, final GuiGraphics graphics) {
        // 高度为 0 时原版仍会调用本方法，因此这里必须用与 getHeight 完全相同的三个条件逐行再挡一次
        // （否则「没按住键」时图标会照画）；行序恒为 机器 → 输入原料 → 输入流体（按可见行紧凑排布）。
        int row = 0;
        if (machineVisible()) {
            graphics.renderItem(currentMachine(), x, y);
            graphics.drawString(font, currentMachine().getHoverName(), x + ICON_SIZE + NAME_GAP, y + 5,
                0xFFFFFFFF, false);
            row++;
        }
        if (inputVisible()) {
            final int rowY = y + row * ICON_SIZE;
            // 多候选时画「当前轮播到的那一件」（每 CYCLE_TICKS 换一件）—— 与 inputLine() 的文案同源，
            // 因此图标与名字永远指向同一件东西。
            graphics.renderItem(currentInput(), x, rowY);
            graphics.drawString(font, inputLine(), x + ICON_SIZE + NAME_GAP, rowY + 5,
                0xFFFFFFFF, false);
            row++;
        }
        if (fluidVisible()) {
            final int rowY = y + row * ICON_SIZE;
            // 流体图标与归流缓存仓 / 流程编排行同一套绘制（贴图 + 顶点着色）
            GhostMarkerRenderer.renderFluid(graphics, x, rowY, inputFluid);
            graphics.drawString(font, fluidLine(), x + ICON_SIZE + NAME_GAP, rowY + 5,
                0xFFFFFFFF, false);
        }
    }
}
