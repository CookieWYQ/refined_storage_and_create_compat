package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.common.importer.ImporterScreen;
import com.refinedmods.refinedstorage.common.storage.FilterModeSideButtonWidget;
import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;
import com.refinedmods.refinedstorage.common.support.widget.AbstractSideButtonWidget;
import com.refinedmods.refinedstorage.common.support.widget.FuzzyModeSideButtonWidget;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterBarHost;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterBarHost;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「输出总线锁定条」的点击提前到 RS 槽位逻辑之前（用户需求：界面里的按钮点了没反应）。
 *
 * <p><b>为什么注入到 {@link AbstractBaseScreen} 的 {@code mouseClicked}</b>：
 * {@code mouseClicked} <b>声明在 {@code AbstractBaseScreen} 自身</b>（不是更上层的
 * {@code AbstractContainerScreen}），因此这里可以合法注入 —— 本项目硬规则是
 * 「{@code @Inject/@Shadow} 只能定位目标类自身的成员」，而 {@code ExporterScreen} 自己并不声明
 * {@code mouseClicked}（早期在它上面注入会直接抛 {@code InvalidInjectionException} 崩客户端）。</p>
 *
 * <p><b>为什么必须抢在它最前面</b>：{@code AbstractBaseScreen#mouseClicked} 的第一段逻辑是
 * 「鼠标下是资源槽（过滤器槽）→ 处理并直接 {@code return true}」，它在 {@code super.mouseClicked}
 * 之前就返回了；而锁定条正盖在这些过滤器槽上，于是点击永远到不了挂在 {@code children} 里的自绘控件。
 * 这里在 HEAD 处先问一句「当前界面是不是输出总线锁定条的宿主」，是就把点击交给它。</p>
 *
 * <p><b>影响面</b>：只有实现了 {@link RsccExporterBarHost} 的界面（即输出总线界面）才有额外开销，
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

    /**
     * 「输入总线」上两个语义不再适用的侧边按钮：<b>不再挂上</b>「过滤器模式（白 / 黑名单）」与
     * 「模糊模式」。
     *
     * <p><b>为什么删（用户要求）</b>：本模组的输入总线在延长模式下要的是<b>精准匹配</b> ——
     * 该收回什么由「非输入类」判定（全自动）或玩家勾选的类别决定（手动），
     * 白 / 黑名单与模糊匹配在这里既帮不上忙、又会让玩家误以为要配。用户原话：删掉这两个按钮。</p>
     *
     * <p><b>为什么注入 {@code addSideButton}</b>：它<b>声明在 {@link AbstractBaseScreen} 自身</b>
     * （因此可以合法注入），而且是「挂按钮」的唯一入口 —— 在这里<b>不挂</b>比挂上去再移除更干净：
     * 原生实现会顺手登记一个排除区（{@code exclusionZones}）并推进 {@code sideButtonY}，
     * 若先挂后删会留下坐标空洞 / 幽灵排除区。不再挂 = 不占位、不留空洞、
     * 后续的「红石模式」按钮照旧紧跟其后（按钮画在面板左侧外面，因此面板内不可能有空位）。</p>
     *
     * <p><b>影响面</b>：只有 {@link ImporterScreen} 受影响，且只挡这两个类；其余 RS 界面
     * （含输出总线）只多一次 {@code instanceof} 判断。</p>
     */
    @Inject(method = "addSideButton", at = @At("HEAD"), cancellable = true)
    private void rscc$hideImporterFilterButtons(final AbstractSideButtonWidget button,
                                                final CallbackInfo ci) {
        if ((Object) this instanceof ImporterScreen
            && (button instanceof FilterModeSideButtonWidget || button instanceof FuzzyModeSideButtonWidget)) {
            ci.cancel();
        }
    }
}
