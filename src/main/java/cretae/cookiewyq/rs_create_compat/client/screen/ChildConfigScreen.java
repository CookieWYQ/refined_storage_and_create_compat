package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 「配置子窗口」基类（Refined Storage 风格）。
 * <p><b>与 RS 一致的行为</b>：只持有父 {@link Screen} 引用（字段 {@code parent}），关闭 / 确认时用
 * {@code Minecraft.getInstance().setScreen(parent)} 返回父界面；**背后不渲染父界面**，只由原版
 * {@code renderBackground} 把世界暗化（RS 的 {@code AbstractAmountScreen} 同样如此）。</p>
 * <p><b>渲染顺序</b>：原版暗化背景 → 居中子面板 → 子类自己的文字 / 控件（由 {@link Screen#render} 负责）。</p>
 * <p><b>子面板外观</b>：默认 {@code blitSprite} <b>原版九宫格精灵</b> {@link #VANILLA_PANEL}
 * （{@code minecraft:recipe_book/overlay_recipe}，border=4）—— 即「MC 自带的那种背景」，
 * 不再手工 {@code fill} 拼面板。某个子界面若另行交付了自绘 PNG，覆写 {@link #customPanel()}
 * 返回其 {@link ResourceLocation} 即可整图 {@code blit}（其余坐标 / 控件 / 逻辑一律不动）。</p>
 * <p><b>硬规则</b>：所有文字无阴影（子类保证）；自绘元素的 tooltip 全部由子类手动渲染，hover 判定与
 * 绘制范围同源。</p>
 */
public abstract class ChildConfigScreen extends Screen {
    /** 子面板 3px 边框颜色（与主窗口容器边框同族）。 */
    protected static final int COLOR_BORDER = 0xFF2B2B2B;
    /** 凹陷面板：底 / 上左 1px 亮边 / 下右 1px 暗边。 */
    protected static final int COLOR_PANEL = 0xFF9B9B9B;
    protected static final int COLOR_PANEL_LIGHT = 0xFFAFAFAF;
    protected static final int COLOR_PANEL_DARK = 0xFF787878;
    /** 父界面之上的暗化遮罩（如需自绘遮罩可在子类使用；默认渲染路径已由原版背景暗化）。 */
    protected static final int COLOR_DIM = 0xA0000000;

    /**
     * 子面板默认背景：原版九宫格精灵 {@code minecraft:recipe_book/overlay_recipe}
     * （32×32，{@code .mcmeta} 声明 {@code nine_slice border = 4}）。
     * <p>圆角 / 1px 黑描边 / 白高光 / 右下暗边 / {@code #C6C6C6} 底全部来自 MC 自带贴图，
     * 这就是「MC 自带风格的面板」，不存在手搓边框。工程内所有子窗口统一走这里，
     * 避免出现各窗口画风不一致。</p>
     */
    protected static final ResourceLocation VANILLA_PANEL = McGui.VANILLA_PANEL;

    /** 父界面（null 时退化为原版背景；正常都非 null）。 */
    @Nullable
    protected final Screen parent;
    protected final int panelW;
    protected final int panelH;
    /** 子面板左上角（屏幕居中）。 */
    protected int px;
    protected int py;

    protected ChildConfigScreen(final Component title, @Nullable final Screen parent,
                                final int panelW, final int panelH) {
        super(title);
        this.parent = parent;
        this.panelW = panelW;
        this.panelH = panelH;
    }

    @Override
    protected void init() {
        this.px = (this.width - panelW) / 2;
        this.py = (this.height - panelH) / 2;
    }

    @Override
    public void renderBackground(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                                 final float partialTick) {
        // 与 RS 保持一致：背后**不渲染父界面**，只用原版背景把世界暗化
        //（RS 的 AbstractAmountScreen / ResourceAmountScreen 同样只是持有 parent 引用用于「返回」）。
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        // 居中子面板
        renderPanel(guiGraphics);
    }

    /**
     * 自绘面板 PNG 的<b>替换点</b>：返回非 null 时 {@link #renderPanel} 改为整图 {@code blit}，
     * 其余坐标 / 控件 / 逻辑一律不动。等 {@code tmp_textures/*.md} 约定的 PNG 交付后，
     * 子类覆写本方法返回对应 {@link ResourceLocation} 即可。
     */
    @Nullable
    protected ResourceLocation customPanel() {
        return null;
    }

    /**
     * 子面板外观：默认 {@code blitSprite} 原版九宫格（{@link #VANILLA_PANEL}），
     * 即「MC 自带的那种背景」；子类交付了自绘 PNG 时覆写 {@link #customPanel()} 即可切换。
     *
     * <p><b>尺寸防护</b>：只有当 PNG 的实际尺寸与当前面板尺寸<b>完全一致</b>时才整图 {@code blit}
     * （见 {@link McGui#textureMatches}）。这样「面板尺寸改大、新尺寸 PNG 还没交付」时不会把旧贴图
     * 纵向拉伸 / 边框错位，而是自动退回原版九宫格；新 PNG 一放进去即生效，<b>无需改任何代码</b>。</p>
     */
    protected void renderPanel(final GuiGraphics guiGraphics) {
        final ResourceLocation custom = customPanel();
        if (custom != null && McGui.textureMatches(custom, panelW, panelH)) {
            guiGraphics.blit(custom, px, py, 0.0F, 0.0F, panelW, panelH, panelW, panelH);
            return;
        }
        guiGraphics.blitSprite(VANILLA_PANEL, px, py, panelW, panelH);
    }

    /** 返回父界面（Esc / 取消 / 确认统一走这里）。 */
    protected void returnToParent() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        returnToParent();
    }
}
