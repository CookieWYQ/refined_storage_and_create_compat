package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 归流缓存仓「<b>开启删除模式</b>」的二次确认子窗口（2026-10-05，对应用户的复选框要求）。
 *
 * <h2>为什么开启也要确认（与「销毁缓存区内容」同一套纪律）</h2>
 * <p>用户要的是「一个复选框，选择之后开启清除资源功能」。但一旦开启，凡命中「标记为删除」的
 * 匹配槽的资源都会<b>不可逆地</b>按速率消失 —— 因此这里的交互是：
 * 主界面复选框<b>不直接发开启包</b>，而是打开本窗口，把「当前标记了哪些槽 / 会以多快的速度删」
 * 列清楚，玩家点「确认开启」后才发送带确认位的
 * {@link SetCollectionDeleteModePacket}。服务端再对该确认位做硬校验，
 * 因此「未确认就开启」在任何路径上都不可能发生。关闭则无需确认（恢复安全状态）。</p>
 *
 * <p>本窗口<b>只读</b>：所有数字都取自菜单的数据槽（服务端同步），不写任何状态。</p>
 */
public class CollectionDeleteModeConfirmScreen extends ChildConfigScreen {
    private static final int PANEL_W = 208;
    private static final int PANEL_H = 104;
    private static final String LANG = "gui.rs_create_compat.collection_cache.delete_mode.";

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_WARN = 0xFFC02020;
    private static final int COLOR_TEXT = 0xFF404040;

    private final CollectionCacheMenu menu;
    private Button confirmButton;
    private Button cancelButton;

    public CollectionDeleteModeConfirmScreen(final Screen parent, final CollectionCacheMenu menu) {
        super(Component.translatable(LANG + "confirm.title"), parent, PANEL_W, PANEL_H);
        this.menu = menu;
    }

    @Override
    protected void init() {
        super.init();
        confirmButton = new Button.Builder(Component.translatable(LANG + "confirm.yes"),
            button -> confirmEnable())
            .bounds(px + 8, py + 78, 92, 18).build();
        addRenderableWidget(confirmButton);
        cancelButton = new Button.Builder(Component.translatable(LANG + "confirm.no"),
            button -> returnToParent())
            .bounds(px + 108, py + 78, 92, 18).build();
        addRenderableWidget(cancelButton);
    }

    /**
     * 确认开启：发送<b>带确认位</b>的 C2S 包（服务端校验后才真正开启），随后返回主界面。
     * <p>这是整条链路上唯一能打开删除模式的入口。</p>
     */
    private void confirmEnable() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetCollectionDeleteModePacket(menu.containerId, true, true));
        returnToParent();
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + 8, py + 8, COLOR_TITLE, false);
        // 2026-10-05 夜间按用户要求砍文案：原来 5 行长句会溢出背景，现在 3 行短句，
        // 每行都按面板宽度截断（宁短不溢出）。
        guiGraphics.drawString(font, trim(Component.translatable(LANG + "confirm.warning").getString()),
            px + 8, py + 26, COLOR_WARN, false);
        guiGraphics.drawString(font, trim(Component.translatable(LANG + "confirm.rule").getString()),
            px + 8, py + 44, COLOR_TEXT, false);
        // 速率行已按用户要求去掉（原话：「不要告诉也不需要说明删除的速率」）——速率随速度 / 堆叠升级
        // 变化这件事由本模组的其它说明承担，确认窗只讲「会发生什么」。
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 按面板可用宽度截断（尾部省略号）：**宁短不溢出**。
     * <p>2026-10-05 用户明确反馈过「文字超出背景了非常的丑」——因此这里不再依赖「文案写得短」，
     * 而是在绘制前按像素宽度硬截断，任何语言（英文长词 / 未来改文案）都不会再越界。</p>
     */
    private String trim(final String text) {
        final int max = PANEL_W - 16;
        if (font.width(text) <= max) {
            return text;
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final String candidate = out.toString() + text.charAt(i) + "…";
            if (font.width(candidate) > max) {
                break;
            }
            out.append(text.charAt(i));
        }
        return out + "…";
    }

    /** 按钮 tooltip（本模组 GUI 不会自动渲染，必须手动调用；hover 判定与控件范围同源）。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (confirmButton != null && confirmButton.isMouseOver(mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "confirm.yes.tip"), mouseX, mouseY);
            return;
        }
        if (cancelButton != null && cancelButton.isMouseOver(mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "confirm.no.tip"), mouseX, mouseY);
        }
    }
}
