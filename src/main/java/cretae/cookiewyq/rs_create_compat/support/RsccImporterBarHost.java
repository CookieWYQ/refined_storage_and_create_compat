package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 桥接接口：由客户端 Mixin（注入 RS 输入总线界面 {@code ImporterScreen}）实现，
 * 让「输入总线类别条」这一自绘控件能在 RS 的 tooltip 阶段<b>手动渲染 tooltip</b>。
 *
 * <p><b>为什么需要它</b>：本模组的硬规则是「GUI 功能一律要有 tooltip，且 tooltip 不会自动渲染」。
 * 输出总线界面自己声明了 {@code renderTooltip}，所以那边可以直接注入；而 {@code ImporterScreen}
 * <b>没有声明</b> {@code renderTooltip}（它只声明了 {@code init}），本项目硬规则是「只能注入目标类自身
 * 声明的方法」——因此输入总线这边改为在<b>声明处</b> {@code AbstractBaseScreen#renderTooltip} 上注入，
 * 再按本接口把 tooltip 交给类别条控件（见 {@code AbstractBaseScreenMixin}）。</p>
 *
 * <p>与 {@link RsccImporterMenuBridge} 同理，本接口刻意放在 support 包：mixin 包被 mixins.json
 * 声明为 Mixin 专用包，普通类放在其中被外部直接引用会抛 {@code IllegalClassLoadError}。</p>
 */
public interface RsccImporterBarHost {
    /**
     * 处理「输入总线类别条」的 tooltip（含在最后阶段补绘一次类别条，盖住原版槽位渲染）。
     *
     * @return {@code true} 表示已接管（调用方应取消 RS 原版 tooltip）
     */
    boolean rscc$renderImporterBarTooltip(GuiGraphics graphics, int mouseX, int mouseY);
}
