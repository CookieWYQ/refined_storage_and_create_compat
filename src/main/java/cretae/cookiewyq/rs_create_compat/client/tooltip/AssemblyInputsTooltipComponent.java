package cretae.cookiewyq.rs_create_compat.client.tooltip;

import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * 序列装配总样板 tooltip 的「所需原料」图标区：<b>每一条可互换的原料只占一行，图标与名称一起轮播</b>。
 *
 * <h2>为什么要有它（用户明确要求）</h2>
 * <p>用户原话：<i>「所有我要求你进行这种滚动显示这种轮换显示的禁止出现什么，就是把多种原料合并在一起
 * 什么什么或什么或什么括号什么这样子的形式……尤其是这个序列装配样板的 tooltip 他就直接这么一个描述，
 * 为什么不能吃这里面的文本来进行轮换显示呢」</i>。</p>
 * <p>改动前总样板 tooltip 把一组可互换原料写成一整行文字
 * （{@code 铁粒（或 锌粒 / 铜粒） ×4}）—— 既长又难读，而且与单元样板 tooltip 的表现<b>不一致</b>
 * （单元样板那边早就是「图标 + 名称一起滚动」）。本类把总样板也统一到同一套：
 * <b>一个图标 + 当前轮播到的那一件的名字 + 数量</b>，绝不再拼「或 / 括号」。</p>
 *
 * <p>轮播下标与 {@link ItemCycleTooltipComponent} <b>同源</b>（同一墙钟常量 {@code CYCLE_MILLIS}），
 * 因此同一屏里单元样板、总样板、界面内联显示的节拍完全一致（不会一个快一个慢）。</p>
 *
 * <p>逐行可见性与 {@link UnitPatternTooltipComponent} 同一口径：走
 * {@link RsccTooltipLayers#componentVisible}，按键只在唯一实现里读一次。原料行属于「用途」层 = Shift。</p>
 */
public class AssemblyInputsTooltipComponent implements ClientTooltipComponent {
    private static final int ICON_SIZE = ItemCycleTooltipComponent.ICON_SIZE;
    private static final int NAME_GAP = 4;
    private static final String LANG = "item.rs_create_compat.sequence_assembly_pattern.";

    /** 一组可互换的原料 + 整份样板需要的总数（数量恒 ≥ 1）。 */
    public record Group(List<ItemStack> candidates, long amount) {
        public Group {
            candidates = SequencePatternData.normalizeCandidates(candidates);
        }
    }

    private final List<ItemStack> ingredient;
    private final List<Group> items;
    private final List<FluidStack> fluids;

    public AssemblyInputsTooltipComponent(final List<ItemStack> ingredient,
                                          final List<Group> items,
                                          final List<FluidStack> fluids) {
        this.ingredient = ingredient == null ? List.of() : List.copyOf(ingredient);
        this.items = items == null ? List.of() : List.copyOf(items);
        this.fluids = fluids == null ? List.of() : List.copyOf(fluids);
    }

    // ==================== 逐行可见性（与单元样板同一套） ====================

    /** 主原料行：整条配方只有一项，属于「用途」层 = Shift。 */
    private boolean ingredientVisible() {
        return !ingredient.isEmpty()
            && RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT);
    }

    /** 步骤输入原料行：同为「用途」层 = Shift。 */
    private boolean itemsVisible() {
        return !items.isEmpty()
            && RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT);
    }

    /** 输入流体行：带数量 mB，属「数值」层 = Ctrl。 */
    private boolean fluidsVisible() {
        return !fluids.isEmpty()
            && RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.CTRL);
    }

    private int visibleRows() {
        int rows = 0;
        rows += ingredientVisible() ? 1 : 0;
        rows += itemsVisible() ? 1 + items.size() : 0;
        rows += fluidsVisible() ? fluids.size() : 0;
        return rows;
    }

    // ==================== 当前轮播到的那一件 ====================

    private static ItemStack current(final List<ItemStack> candidates) {
        if (candidates.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.get(ItemCycleTooltipComponent.currentIndex(candidates.size()));
    }

    /**
     * 一行的文字：<b>只写「当前轮播到的那一件」的名字</b>，多候选时另外标注组内候选总数
     * （与单元样板 {@code input_any} 同一口径）。绝不再拼「或 / 括号」。
     */
    private Component line(final List<ItemStack> candidates, final long amount) {
        final ItemStack shown = current(candidates);
        if (candidates.size() > 1) {
            // 与 {@link UnitPatternTooltipComponent#inputLine()} 完全同一口径：
            // 「输入原料：<当前轮播到的那一件> 共 N 种」——绝不枚举全部候选、绝不拼「或」。
            return Component.translatable(LANG + "input_any", shown.getHoverName(), candidates.size())
                .append(Component.literal(" "))
                .append(Component.translatable(LANG + "input_count", amount));
        }
        return Component.translatable(LANG + "input_entry", shown.getHoverName(), amount);
    }

    @Override
    public int getHeight() {
        return visibleRows() * ICON_SIZE;
    }

    @Override
    public int getWidth(final Font font) {
        int width = 0;
        if (ingredientVisible()) {
            width = Math.max(width, ICON_SIZE + NAME_GAP + font.width(line(ingredient, 1L)));
        }
        if (itemsVisible()) {
            for (final Group group : items) {
                width = Math.max(width, ICON_SIZE + NAME_GAP + font.width(line(group.candidates(), group.amount())));
            }
        }
        if (fluidsVisible()) {
            for (final FluidStack fluid : fluids) {
                width = Math.max(width, ICON_SIZE + NAME_GAP + font.width(fluidLine(fluid)));
            }
        }
        return width;
    }

    private Component fluidLine(final FluidStack fluid) {
        return Component.translatable(LANG + "input_fluid_entry", fluid.getHoverName(), fluid.getAmount());
    }

    @Override
    public void renderImage(final Font font, final int x, final int y, final GuiGraphics graphics) {
        // 高度为 0 时原版仍会调用本方法 ⇒ 用与 getHeight 完全相同的条件逐行再挡一次
        // （否则「没按住键」时图标会照画）。行序恒为 主原料 → 步骤输入 → 输入流体。
        int row = 0;
        if (ingredientVisible()) {
            final int rowY = y + row * ICON_SIZE;
            graphics.renderItem(current(ingredient), x, rowY);
            graphics.drawString(font, line(ingredient, 1L), x + ICON_SIZE + NAME_GAP, rowY + 5,
                0xFFFFFFFF, false);
            row++;
        }
        if (itemsVisible()) {
            for (final Group group : items) {
                final int rowY = y + row * ICON_SIZE;
                graphics.renderItem(current(group.candidates()), x, rowY);
                graphics.drawString(font, line(group.candidates(), group.amount()),
                    x + ICON_SIZE + NAME_GAP, rowY + 5, 0xFFFFFFFF, false);
                row++;
            }
        }
        if (fluidsVisible()) {
            for (final FluidStack fluid : fluids) {
                final int rowY = y + row * ICON_SIZE;
                cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer
                    .renderFluid(graphics, x, rowY, fluid);
                graphics.drawString(font, fluidLine(fluid), x + ICON_SIZE + NAME_GAP, rowY + 5,
                    0xFFFFFFFF, false);
                row++;
            }
        }
    }
}
