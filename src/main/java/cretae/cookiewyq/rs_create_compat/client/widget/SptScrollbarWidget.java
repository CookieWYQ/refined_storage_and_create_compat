package cretae.cookiewyq.rs_create_compat.client.widget;

import java.util.function.DoubleConsumer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 用 {@code spt_scrollbar_thumb.png} 绘制的滚动条。
 * <p>贴图 20×10：左半 12px 是 NORMAL 型滑块、右半 7px 是 SMALL 型滑块，滑块高 10（比 RS 自带的
 * 15 更矮），故这里自己实现绘制；交互与 RS 的 {@code ScrollbarWidget} 保持一致
 * （点击/拖动定位、滚轮 ±1、偏移经 listener 回传，由 Screen 决定是否发给服务端）。</p>
 * <p>滑块始终绘制（无滚动需求时停在轨道顶端），交互（点击/拖动/滚轮）只在 {@code maxOffset > 0}
 * 时生效；偏移经 listener 回传，由 Screen 决定是否发给服务端。</p>
 */
public class SptScrollbarWidget extends AbstractWidget {
    /** 滑块贴图高（10）。 */
    private static final int THUMB_H = 10;
    private static final int THUMB_SRC_H = 10;
    private static final int THUMB_TEX_W = 20;
    private static final int THUMB_TEX_H = 10;

    /** 类型：贴图中的 u 偏移与滑块宽度。 */
    public enum Type {
        /** 12 宽滑块（库滚动条，文档：w=12）。 */
        NORMAL(0, 12),
        /** 7 宽滑块（编排/产物/废料滚动条，文档：w=7）。 */
        SMALL(13, 7);

        private final int u;
        private final int width;

        Type(final int u, final int width) {
            this.u = u;
            this.width = width;
        }
    }

    private final Type type;
    /** 滑块（= 控件）实际绘制宽度：默认取类型宽度，可被窄轨道覆盖。 */
    private final int thumbWidth;
    private double offset;
    private double maxOffset;
    private boolean enabled = true;
    private boolean clicked;
    private DoubleConsumer listener;

    public SptScrollbarWidget(final int x, final int y, final Type type, final int height) {
        this(x, y, type, height, type.width);
    }

    /**
     * @param thumbWidth 滑块实际宽度（可小于类型贴图宽度，用于 8px 这类窄轨道；
     *                   9-slice 会把中间部分压缩，边框宽度不变）
     */
    public SptScrollbarWidget(final int x, final int y, final Type type, final int height,
                              final int thumbWidth) {
        super(x, y, thumbWidth, height, Component.empty());
        this.type = type;
        this.thumbWidth = thumbWidth;
    }

    public void setListener(final DoubleConsumer listener) {
        this.listener = listener;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public double getOffset() {
        return offset;
    }

    /**
     * 是否正在拖动（按下未松开）。
     * <p>拖动期间所属界面<b>不应</b>把偏移回写成服务端同步值：高延迟下服务端回包还没到，
     * 回写会把刚拖到的位置「拽回」，玩家感受就是「滚动条拖不动」。</p>
     */
    public boolean isDragging() {
        return clicked;
    }

    public void setMaxOffset(final double maxOffset) {
        this.maxOffset = Math.max(0, maxOffset);
        if (this.offset > this.maxOffset) {
            this.offset = this.maxOffset;
            notifyListener();
        }
    }

    public void setOffset(final double offset) {
        this.offset = Math.min(Math.max(0, offset), maxOffset);
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            listener.accept(offset);
        }
    }

    private int thumbY() {
        if (maxOffset <= 0) {
            return getY();
        }
        return getY() + (int) ((float) offset / (float) maxOffset * (getHeight() - THUMB_H));
    }

    @Override
    protected void renderWidget(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                final float partialTick) {
        // 先画轨道：背景贴图里没有烘焙轨道，若只画滑块，玩家会以为「滚动条没做出来」。
        // 颜色刻意避开 slot.png 的 (55,55,55)/(139,139,139) 与界面文字深灰族，
        // 以免被留白区像素校验脚本误判为槽位/文字像素。
        final int x = getX();
        final int y = getY();
        final int w = getWidth();
        final int h = getHeight();
        final boolean usable = enabled && maxOffset > 0;
        guiGraphics.fill(x, y, x + w, y + h, usable ? 0xFF262626 : 0xFF3A3A3A);      // 轨道底
        guiGraphics.fill(x, y, x + 1, y + h, 0xFF151515);                           // 左暗边
        guiGraphics.fill(x + w - 1, y, x + w, y + h, 0xFF3C3C3C);                    // 右亮边
        // 始终绘制滑块：即使当前没有可滚动的内容（maxOffset = 0）也把滑块停在轨道顶端，
        // 这样玩家能看见「滚动条确实存在」，而不是误以为没做出来。
        // <b>禁用态必须一眼可辨</b>：内容不满一屏时滑块恒为灰色（把贴图整体压暗 55%），
        // 玩家看到的就是「灰的 = 现在没得滚」，而不是「拖它却毫无反应」（用户反馈的「滑不动」）。
        if (!usable) {
            RenderSystem.setShaderColor(0.45F, 0.45F, 0.45F, 1.0F);
        }
        SptGuiTextures.blitNineSlice(guiGraphics, SptGuiTextures.SCROLL_THUMB,
            getX(), thumbY(), thumbWidth, THUMB_H,
            type.u, 0, type.width, THUMB_SRC_H, 1, THUMB_TEX_W, THUMB_TEX_H);
        if (!usable) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * 拖动中：<b>必须实现这个方法</b>。
     * <p>MC 在「按住鼠标移动」时派发的是 {@code mouseDragged}，而不是 {@code mouseMoved}
     * （后者只在没有按键按下时派发）。此前本控件把拖动逻辑写在 {@code mouseMoved} 里，
     * 于是拖动期间一个事件都收不到 —— 玩家感受就是「滚动条拖不动，只有点轨道/滚轮能用」。</p>
     * <p>拖动期间<b>不再要求光标仍在轨道内</b>：只要还按着（{@code clicked}），就按光标的纵向位置
     * 定位滑块（{@link #updateOffset} 内部会夹紧到 0..maxOffset），这样拖出轨道也不会中断拖动。</p>
     */
    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        if (!clicked || button != 0) {
            return false;
        }
        updateOffset(mouseY);
        return true;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (!isActive() || !enabled || maxOffset <= 0 || button != 0) {
            return false;
        }
        if (!inBounds(mouseX, mouseY)) {
            return false;
        }
        // 点轨道 = 立即跳到该位置，并进入拖动状态（按住不放可继续拖）
        updateOffset(mouseY);
        clicked = true;
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (clicked) {
            clicked = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (!enabled || maxOffset <= 0) {
            return false;
        }
        final int direction = Math.max(Math.min(-(int) scrollY, 1), -1);
        if (direction == 0) {
            return false;
        }
        setOffset(offset + direction);
        return true;
    }

    /** 鼠标是否落在滚动条区域（与绘制区域一致：宽 = 滑块宽，高 = 控件高；左闭右开）。 */
    public boolean inBounds(final double mouseX, final double mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth()
            && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    private void updateOffset(final double mouseY) {
        setOffset(Math.floor((mouseY - THUMB_H / 2.0 - getY()) / (getHeight() - THUMB_H) * maxOffset));
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput narrationElementOutput) {
        // 滚动条不做播报
    }
}
