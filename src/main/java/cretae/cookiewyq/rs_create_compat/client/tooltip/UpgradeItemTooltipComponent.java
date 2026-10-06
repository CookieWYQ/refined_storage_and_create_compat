package cretae.cookiewyq.rs_create_compat.client.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 升级槽 tooltip 中的一行：插件物品图标 + 名称（含可放入数量，仿 RS 原版 UpgradeRegistryImpl 的
 * "名称 (数量)" 格式）。渲染方式照搬 RS 原版 UpgradeItemClientTooltipComponent（18px 高，图标后 4px 间距 + 名称）。
 */
public class UpgradeItemTooltipComponent implements ClientTooltipComponent {
    private final ItemStack stack;
    private final Component displayName;

    public UpgradeItemTooltipComponent(final ItemStack stack, final int maxAmount) {
        this.stack = stack;
        this.displayName = stack.getHoverName().copy()
            .append(" ")
            .append("(")
            .append(String.valueOf(maxAmount))
            .append(")");
    }

    @Override
    public int getHeight() {
        return 18;
    }

    @Override
    public int getWidth(final Font font) {
        return 16 + 4 + font.width(displayName);
    }

    @Override
    public void renderImage(final Font font, final int x, final int y, final GuiGraphics graphics) {
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
        graphics.drawString(font, displayName, x + 16 + 4, y + 4, 0xFFFFFF);
    }
}
