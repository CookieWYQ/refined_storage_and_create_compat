package cretae.cookiewyq.rs_create_compat.client.widget;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 序列装配样板终端 v3 所用的贴图位置与 9-slice 绘制工具。
 * <p>贴图排布（见 SPT_GUI_TECH_DOC_V3.md 第一节）：三态竖向堆叠（默认 / 悬停 / 禁用），
 * Tab 为 2 列 × 2 行共 4 个 60×12；滑块贴图为 20×10，左半 12px 是 NORMAL、右半 7px 是 SMALL。
 * 这里集中管理 uv，避免各控件各写一套偏移。</p>
 */
public final class SptGuiTextures {
    /** 卡片 9-slice 衬底：8×24 = 3 个 8×8 状态（默认 / 悬停 / 选中），1px 边框。 */
    public static final ResourceLocation CARD = id("spt_card_bg.png");
    /** Tab 页签：120×24 = 2 列（未选中 / 选中）× 2 行（单元页 / 装配页），每格 60×12。 */
    public static final ResourceLocation TABS = id("spt_tabs.png");
    /** 小按钮：12×36 = 3 个 12×12 状态。 */
    public static final ResourceLocation BTN_SMALL = id("spt_btn_small.png");
    /** 大按钮：56×36 = 3 个 56×12 状态。 */
    public static final ResourceLocation BTN_LARGE = id("spt_btn_large.png");
    /** 滑块：20×10（左 12 宽 = NORMAL，右 7 宽 = SMALL，中间 1px 透明分隔）。 */
    public static final ResourceLocation SCROLL_THUMB = id("spt_scrollbar_thumb.png");

    /** 卡片贴图：状态格 8×8，整图为 8×24。 */
    public static final int CARD_SRC = 8;
    public static final int CARD_STATES = 3;
    /** 按钮贴图状态格高度（小 12、大 12）。 */
    public static final int BTN_SMALL_SRC_W = 12;
    public static final int BTN_SMALL_STATE_H = 12;
    public static final int BTN_LARGE_SRC_W = 56;
    public static final int BTN_LARGE_STATE_H = 12;

    private SptGuiTextures() {
    }

    private static ResourceLocation id(final String file) {
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/spt/" + file);
    }

    /** 三态下标：0 = 默认，1 = 悬停，2 = 禁用。 */
    public static int state(final boolean active, final boolean hovered) {
        return !active ? 2 : (hovered ? 1 : 0);
    }

    /**
     * 9-slice 绘制：把贴图中 (u,v,srcW,srcH) 的状态格按四边 {@code border} 像素拉伸到
     * 任意目标尺寸（四角原样、四边与中心拉伸），边框不随目标尺寸变粗。
     */
    public static void blitNineSlice(final GuiGraphics guiGraphics,
                                     final ResourceLocation texture,
                                     final int x, final int y, final int width, final int height,
                                     final int u, final int v, final int srcW, final int srcH,
                                     final int border, final int texW, final int texH) {
        final int innerSrcW = srcW - border * 2;
        final int innerSrcH = srcH - border * 2;
        final int innerW = width - border * 2;
        final int innerH = height - border * 2;
        // 四角
        guiGraphics.blit(texture, x, y, u, v, border, border, texW, texH);
        guiGraphics.blit(texture, x + width - border, y,
            u + srcW - border, v, border, border, texW, texH);
        guiGraphics.blit(texture, x, y + height - border,
            u, v + srcH - border, border, border, texW, texH);
        guiGraphics.blit(texture, x + width - border, y + height - border,
            u + srcW - border, v + srcH - border, border, border, texW, texH);
        if (innerW <= 0 || innerH <= 0 || innerSrcW <= 0 || innerSrcH <= 0) {
            return; // 目标比边框还小：只画四角
        }
        // 四边中段（源 1px 宽/高即可，拉伸不影响观感）
        guiGraphics.blit(texture, x + border, y, innerW, border,
            u + border, v, innerSrcW, border, texW, texH);
        guiGraphics.blit(texture, x + border, y + height - border, innerW, border,
            u + border, v + srcH - border, innerSrcW, border, texW, texH);
        guiGraphics.blit(texture, x, y + border, border, innerH,
            u, v + border, border, innerSrcH, texW, texH);
        guiGraphics.blit(texture, x + width - border, y + border, border, innerH,
            u + srcW - border, v + border, border, innerSrcH, texW, texH);
        // 中心
        guiGraphics.blit(texture, x + border, y + border, innerW, innerH,
            u + border, v + border, innerSrcW, innerSrcH, texW, texH);
    }
}
