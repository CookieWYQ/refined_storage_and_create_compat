package cretae.cookiewyq.rs_create_compat.client.widget;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 支持"按住持续触发"的按钮。
 * MC 原版按钮按住鼠标只触发一次点击；此按钮在按住不放时按固定间隔重复触发，
 * 配合每次点击即时生效的服务端通信，实现"连续快速调整数值"的效果。
 * 由所属 Screen 的 tick() 调用 {@link #tickRepeat()} 驱动。
 */
public class RepeatButton extends Button {
    /** 按住多少 tick 后开始重复（约 0.2 秒）。 */
    private static final int INITIAL_DELAY_TICKS = 4;
    /** 开始重复后每多少 tick 触发一次（每帧触发，反应迅速）。 */
    private static final int REPEAT_INTERVAL_TICKS = 1;

    private boolean pressed;
    private int holdTicks;

    public RepeatButton(final int x, final int y, final int width, final int height,
                        final Component message, final OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void onClick(final double mouseX, final double mouseY) {
        super.onClick(mouseX, mouseY);
        this.pressed = true;
        this.holdTicks = 0;
    }

    @Override
    public void onRelease(final double mouseX, final double mouseY) {
        super.onRelease(mouseX, mouseY);
        this.pressed = false;
    }

    /** 由 Screen.tick() 每 tick 调用：按住时按固定间隔重复触发 onPress。 */
    public void tickRepeat() {
        if (!pressed) {
            return;
        }
        // 鼠标已移出按钮（视觉上应视为松开）
        if (!isHovered()) {
            pressed = false;
            return;
        }
        holdTicks++;
        if (holdTicks > INITIAL_DELAY_TICKS && holdTicks % REPEAT_INTERVAL_TICKS == 0) {
            super.onClick(getX() + 1, getY() + 1);
        }
    }
}
