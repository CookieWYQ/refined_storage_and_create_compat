package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.network.SenderShortagePromptPacket;
import cretae.cookiewyq.rs_create_compat.network.SenderShortageReplyPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 第 131 轮 F 组：**「发送量超过库存，但可以自动合成」的询问窗口**。
 *
 * <h2>用户拍板的文案（一个数字都不许改口径）</h2>
 * <p>「目标 100 · 现有 50 · 最多可合成 25 · 还缺 25」＋两个按钮
 * 「合成 25 并发送 75」/「只发送 50」。</p>
 * <p>四个数字全部由服务端算好下发（{@code SenderShortagePromptPacket}）；
 * 本界面<b>不做任何算术</b>。按钮上的两个数取「结算后一共送到多少」与「这次合成多少」，
 * 与服务端真正要投的量同源，因此不会出现「按钮说 75、实际到 50」。</p>
 *
 * <h2>为什么是子界面而不是覆盖整个发送界面</h2>
 * <p>询问必须让玩家看到<b>自己原来的订单</b>（目标数从哪来），所以它压在发送界面之上、
 * 关掉就回到发送界面（{@link ChildConfigScreen} 的父子语义）。这也让「Esc = 我再想想」
 * 天然成立：不发、不合成、不登记补投，什么都不改。</p>
 */
public class SenderCraftPromptScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.sender_craft_prompt.";

    private static final int PANEL_W = 224;
    private static final int PANEL_H = 74;
    private static final int PAD = 10;
    private static final int BOX_H = 16;
    private static final int TITLE_Y = 10;
    /** 「目标 / 现有 / 最多可合成 / 还缺」那一行。 */
    private static final int LINE_Y = 28;
    private static final int ACTION_Y = 48;
    private static final int ACTION_W = 100;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_LINE = 0xFF2E5C8A;

    /** 发起询问的那张发送界面菜单 id（回答时随包带回；服务端据此校验归属 + 取回「当时问的是什么」）。 */
    private final int containerId;
    private final long target;
    private final long have;
    private final long craftable;
    private final long missing;
    private final long sendAfterCraft;

    public SenderCraftPromptScreen(final Screen parent,
                                   final SenderShortagePromptPacket packet) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.containerId = packet.containerId();
        this.target = packet.target();
        this.have = packet.have();
        this.craftable = packet.craftable();
        this.missing = packet.missing();
        this.sendAfterCraft = packet.sendAfterCraft();
    }

    @Override
    protected void init() {
        super.init();
        // 左：「合成 X 并发送 Y」（X = 这次能合成多少，Y = 送到多少）
        addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "craft", craftable, sendAfterCraft),
            button -> reply(true))
            .bounds(px + PAD, py + ACTION_Y, ACTION_W, BOX_H).build());
        // 右：「只发送现有」（= 现在能发多少）
        addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "send_only", have), button -> reply(false))
            .bounds(px + PAD + ACTION_W + 4, py + ACTION_Y,
                PANEL_W - 2 * PAD - ACTION_W - 4, BOX_H).build());
    }

    /** 回答并返回发送界面（无论选哪个：先投现有部分，之后由服务端决定是否合成 + 补投）。 */
    private void reply(final boolean craft) {
        PacketDistributor.sendToServer(new SenderShortageReplyPacket(containerId, craft));
        returnToParent();
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        // 四个数一行：目标 / 现有 / 最多可合成 / 还缺（用户拍板的顺序，不重排）
        final Component line = Component.translatable(LANG + "line",
            target, have, craftable, missing);
        cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit.drawFitLine(guiGraphics, font,
            line, px + PAD, py + LINE_Y, PANEL_W - 2 * PAD, COLOR_LINE);
        // 第 126 轮：这一行是运行时拼出来的整句（目标 / 现有 / 最多可合成 / 还缺），
        // 走缩字号工具后可能被压小 ⇒ 悬停时用**同一个 line** 原尺寸再显示一遍。
        // 不在 tooltip 里重拼一份数字：那样一旦改口径，两处会各说各话。
        if (isHovering(px + PAD, py + LINE_Y, PANEL_W - 2 * PAD, 12, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, line, mouseX, mouseY);
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 手动渲染 tooltip（子界面里 MC 不会替我们调）；判定范围与控件绘制范围同源。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (isHovering(px + PAD, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "craft.tip", craftable, sendAfterCraft),
                mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD + ACTION_W + 4, py + ACTION_Y,
            PANEL_W - 2 * PAD - ACTION_W - 4, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "send_only.tip", have, missing), mouseX, mouseY);
        }
    }

    private static boolean isHovering(final int x, final int y, final int w, final int h,
                                      final double mouseX, final double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        // 回车 / 小键盘回车 = 默认「只发送现有」：最保守、绝不额外消耗材料的那个选择。
        // 「合成」需要玩家明确点左键 —— 让「顺手回车」不会替玩家下合成单。
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            reply(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
