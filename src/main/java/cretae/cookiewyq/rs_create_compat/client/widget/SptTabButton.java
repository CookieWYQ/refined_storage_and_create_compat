package cretae.cookiewyq.rs_create_compat.client.widget;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 顶部页签按钮：用 {@code spt_tabs.png}（120×24）绘制。
 * <p>贴图 2 列 × 2 行，每格 60×12：<b>列</b> = 未选中 / 选中，<b>行</b> = 单元样板库页 / 装配编排页。
 * 控件尺寸 = 单格尺寸（60×12），因此整格原样绘制；文字按硬规则无阴影。</p>
 */
public class SptTabButton extends Button {
    /** 单格贴图宽度。 */
    private static final int TAB_SRC_W = 60;
    private static final int TAB_SRC_H = 12;
    /** 整张贴图尺寸（120×24）。 */
    private static final int TEX_W = TAB_SRC_W * 2;
    private static final int TEX_H = TAB_SRC_H * 2;

    private final int tabRow;
    private final BooleanSupplier selected;

    public SptTabButton(final int x, final int y, final int width, final int height,
                        final Component message, final int tabRow,
                        final BooleanSupplier selected, final OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.tabRow = tabRow;
        this.selected = selected;
    }

    @Override
    protected void renderWidget(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                final float partialTick) {
        final boolean on = selected.getAsBoolean();
        guiGraphics.blit(SptGuiTextures.TABS, getX(), getY(), on ? TAB_SRC_W : 0, tabRow * TAB_SRC_H,
            getWidth(), getHeight(), TEX_W, TEX_H);
        // 选中态底色更亮：用深色字；未选中态底色中等灰：用更深一点的灰字；悬停再提亮
        final int color = on ? 0xFF102A46 : isHovered() ? 0xFF202020 : 0xFF3C3C3C;
        final Font font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, getMessage(),
            getX() + (getWidth() - font.width(getMessage())) / 2, getY() + 2, color, false);
    }
}
