package cretae.cookiewyq.rs_create_compat.client.tooltip;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * tooltip 中的一行「代表物品」图标<b>轮播</b>（JEI 风格）：一个配方类型可能对应多个物品，
 * 每 {@link #CYCLE_MILLIS} 毫秒自动切换下一个。
 * <p><b>时间来源</b>：由墙钟时间 {@link Util#getMillis()} 推导轮播下标，因此 tooltip 每帧重绘
 * 就会自动切换，不依赖 tick / 不依赖屏幕是否暂停。</p>
 * <p>图标行同时用于：单元样板物品 tooltip（经 {@code RegisterClientTooltipComponentFactoriesEvent}
 * 注册的工厂构造）与两个配置界面内联显示（{@link #renderCycleIcon}）。</p>
 */
public class ItemCycleTooltipComponent implements ClientTooltipComponent {
    /** 轮播周期（毫秒）：每 1 秒切换下一个物品。 */
    public static final long CYCLE_MILLIS = 1000L;
    /** 图标尺寸（与原版槽位一致）。 */
    public static final int ICON_SIZE = 18;
    /** 图标与右侧名称之间的间距。 */
    private static final int NAME_GAP = 4;

    private final List<ItemStack> stacks;

    public ItemCycleTooltipComponent(final List<ItemStack> stacks) {
        this.stacks = stacks == null ? List.of() : List.copyOf(stacks);
    }

    /** 当前应显示的物品（列表为空时返回 {@link ItemStack#EMPTY}）。 */
    public ItemStack current() {
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(currentIndex(stacks.size()));
    }

    /** 轮播下标（由墙钟时间推导；size &lt;= 1 时恒为 0）。 */
    public static int currentIndex(final int size) {
        if (size <= 1) {
            return 0;
        }
        return (int) ((Util.getMillis() / CYCLE_MILLIS) % size);
    }

    /** 在 (x, y) 处画当前轮播图标（供 tooltip 组件与界面内联显示共用，二者轮播同步）。 */
    public static void renderCycleIcon(final GuiGraphics graphics, final List<ItemStack> stacks,
                                       final int x, final int y) {
        if (stacks == null || stacks.isEmpty()) {
            return;
        }
        graphics.renderItem(stacks.get(currentIndex(stacks.size())), x, y);
    }

    @Override
    public int getHeight() {
        // 无物品时不占位（避免 tooltip 里出现一条 18px 空白）
        return stacks.isEmpty() ? 0 : ICON_SIZE;
    }

    @Override
    public int getWidth(final Font font) {
        final ItemStack stack = current();
        if (stack.isEmpty()) {
            return 0;
        }
        return ICON_SIZE + NAME_GAP + font.width(stack.getHoverName());
    }

    @Override
    public void renderImage(final Font font, final int x, final int y, final GuiGraphics graphics) {
        final ItemStack stack = current();
        if (stack.isEmpty()) {
            return;
        }
        graphics.renderItem(stack, x, y);
        // 名称跟随图标一起轮播；无阴影（硬规则）
        graphics.drawString(font, stack.getHoverName(), x + ICON_SIZE + NAME_GAP, y + 5, 0xFFFFFFFF, false);
    }
}
