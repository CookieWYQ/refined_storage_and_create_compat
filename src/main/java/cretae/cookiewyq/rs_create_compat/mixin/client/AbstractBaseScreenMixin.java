package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterBarHost;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterBarHost;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RS 总线界面（输入 / 输出总线共用基类 {@link AbstractBaseScreen}）的<b>两个「点击 / tooltip 接力」注入</b>。
 *
 * <h2>铁律：本模组只追加自绘控件，<b>绝不删除 RS 原生控件</b></h2>
 * <p>本类<b>不</b>拦截「挂控件」的任何入口：既不拦截「挂侧边按钮」那个方法（它声明在
 * {@link AbstractBaseScreen} 自身），也不拦截 {@code addRenderableWidget} / {@code addWidget}，
 * 更不做 {@code clearWidgets()} 之后不重建。原生侧边按钮<b>一律保留</b> ——</p>
 * <ul>
 *     <li>输入总线（{@code ImporterScreen}）：过滤器模式（白 / 黑名单）+ 模糊模式 + 红石模式；</li>
 *     <li>输出总线（{@code ExporterScreen}）：模糊模式 + 调度模式 + 红石模式。</li>
 * </ul>
 * <p>前两组分别由 {@code ImporterScreen#init} / {@code ExporterScreen#init} 自己挂，
 * 红石模式由 {@code AbstractFilterScreen#init} 挂（RS 2.0.9 sources：
 * {@code common/importer/ImporterScreen.java:21,26}、{@code common/exporter/ExporterScreen.java:20,24}、
 * {@code common/support/AbstractFilterScreen.java:39}）。</p>
 *
 * <p><b>为什么这条铁律必须写在这里（历史教训）</b>：本类曾经有一个
 * {@code rscc$hideImporterFilterButtons} 注入，在「挂侧边按钮」那个方法的 {@code HEAD} 处
 * <b>无条件</b> {@code ci.cancel()} 掉「过滤器模式 / 模糊模式」两颗按钮 —— 于是输入总线界面上
 * <b>只剩一颗红石模式按钮</b>，连「普通模式本该与 RS 原版逐像素一致」的场合也一起被删了 1.0.0 起七八个版本。
 * 用户原话：「你貌似把输入总线输出总线……所有这样子的总线，它的黑名单那些按钮什么全都删了，
 * 然后只剩下一个这一个红石开关了。我让你的是把我添加的那一些东西部分改一下，但你却把这都改。
 * 这是个非常严重的问题。」</p>
 * <p>结论：本模组对 RS 界面的所有改动都必须是<b>追加</b>（自绘控件挂在 children 末尾、
 * 或用 {@code @Inject} 在 TAIL 处补一笔），任何「让原生控件不出现」的写法都是回归。
 * 回归由 {@code tools/selfcheck_round97_bus_side_buttons.py} 钉住：全仓 {@code src/main/java}
 * 必须<b>一次都不出现</b>那个方法名（连注释里也不写，这样任何人 grep 一眼就能判）。</p>
 *
 * <h2>本类实际只做两件事（都是「接力」，不改变任何原生控件是否存在）</h2>
 *
 * <p><b>① 把「输出总线锁定条」的点击提前到 RS 槽位逻辑之前</b>（用户需求：界面里的按钮点了没反应）。</p>
 * <p>为什么注入到 {@link AbstractBaseScreen} 的 {@code mouseClicked}：{@code mouseClicked}
 * <b>声明在 {@code AbstractBaseScreen} 自身</b>（不是更上层的 {@code AbstractContainerScreen}），
 * 因此这里可以合法注入 —— 本项目硬规则是「{@code @Inject/@Shadow} 只能定位目标类自身的成员」，
 * 而 {@code ExporterScreen} 自己并不声明 {@code mouseClicked}（早期在它上面注入会直接抛
 * {@code InvalidInjectionException} 崩客户端）。</p>
 * <p>为什么必须抢在它最前面：{@code AbstractBaseScreen#mouseClicked} 的第一段逻辑是
 * 「鼠标下是资源槽（过滤器槽）→ 处理并直接 {@code return true}」，它在 {@code super.mouseClicked}
 * 之前就返回了；而锁定条正盖在这些过滤器槽上，于是点击永远到不了挂在 {@code children} 里的自绘控件。
 * 这里在 HEAD 处先问一句「当前界面是不是输出总线锁定条的宿主」，是就把点击交给它。</p>
 * <p>影响面：只有实现了 {@link RsccExporterBarHost} 的界面（即输出总线界面）才有额外开销，
 * 其余 RS 界面只多一次 {@code instanceof} 判断；非延长模式 / 点在条外一律放行原版逻辑。</p>
 */
@Mixin(AbstractBaseScreen.class)
public abstract class AbstractBaseScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void rscc$exporterBarClickFirst(final double mouseX, final double mouseY, final int button,
                                            final CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof RsccExporterBarHost host
            && host.rscc$handleExecutorBarClick(mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 「输入总线类别条」的 tooltip 钩子（本模组硬规则：GUI 功能一律要有 tooltip，且 tooltip 不会自动渲染）。
     *
     * <p><b>为什么挂在这里</b>：输出总线界面（{@code ExporterScreen}）自己声明了 {@code renderTooltip}，
     * 所以在那边直接注入；而输入总线界面（{@code ImporterScreen}）<b>没有声明</b>它，只声明了
     * {@code init} —— 本项目硬规则是「只能注入目标类自身声明的方法」。{@code renderTooltip} 声明在
     * {@link AbstractBaseScreen} <b>自身</b>，因此在这里注入必然命中，再按 {@link RsccImporterBarHost}
     * 回调给具体界面。</p>
     *
     * <p><b>影响面</b>：只有实现了 {@link RsccImporterBarHost} 的界面（即输入总线界面）才有额外开销，
     * 其余 RS 界面只多一次 {@code instanceof} 判断；非延长模式 / 鼠标不在类别条上一律放行原版 tooltip。</p>
     */
    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void rscc$importerBarTooltipFirst(final GuiGraphics graphics, final int mouseX, final int mouseY,
                                              final CallbackInfo ci) {
        if ((Object) this instanceof RsccImporterBarHost host
            && host.rscc$renderImporterBarTooltip(graphics, mouseX, mouseY)) {
            ci.cancel();
        }
    }
}
