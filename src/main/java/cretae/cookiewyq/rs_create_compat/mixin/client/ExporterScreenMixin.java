package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.common.exporter.ExporterScreen;
import cretae.cookiewyq.rs_create_compat.client.widget.ExporterExecutorRowWidget;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.ScreenAccessor;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterBarHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 输出总线界面在「延长型输出」模式下的改版（用户需求之二：要用 Mixin 改界面，并且要好看一点）。
 * <p><b>改了什么</b>：RS 输出总线原本是「9 格过滤器槽 + 右侧插件槽」的布局。当这台输出总线相邻着
 * （或用 RS 线缆连到）处于「总线输出」模式的序列执行仓时：
 * <ol>
 *     <li>把整条过滤器槽位带（x 8..170 / y 20..38）<b>用一块与 RS 面板同风格的深色锁定条盖住</b>，
 *     条内放<b>动态类别单元格</b>（每种输入性产物一个、中间产物一个），放不下时横向滚动；</li>
 *     <li>左键点单元格切换该类别是否导出（<b>支持多选</b>）；鼠标滚轮左右翻看更多类别；</li>
 *     <li>整条槽位带的点击被吞掉：<b>原来的过滤器槽位锁定、不可编辑</b>；</li>
 *     <li>悬停单元格 / 锁定区时手动渲染 tooltip（本模组的 GUI 不会自动渲染 tooltip）。</li>
 * </ol>
 *
 * <p><b>为什么不在 {@link ExporterScreen} 上注入 {@code mouseClicked}</b>：{@code mouseClicked} 不声明在
 * {@link ExporterScreen} 自身（它由父类 {@code AbstractBaseScreen} 声明），而 Mixin 只能注入「目标类自身
 * 声明的方法」，早期这么写会抛
 * {@code InvalidInjectionException: @Inject ... could not find any targets matching 'mouseClicked'}。
 * 因此本 Mixin 只注入 {@link ExporterScreen} <b>自身声明</b>的两个方法：
 * <ul>
 *     <li>{@code init()}：把控件挂进本界面的 widgets 列表（TAIL，此时 {@code leftPos/topPos} 已算好）；</li>
 *     <li>{@code renderTooltip(GuiGraphics, int, int)}：本界面自身声明（第 28 行），
 *     在「槽位 / 标签之后、物品 tooltip 之前」的最后一刻把 tooltip 交给控件；
 *     控件接管时取消原版 tooltip（被盖住的过滤器槽位不再弹出可编辑提示）。</li>
 * </ul>
 *
 * <p><b>点击为什么还要 {@code AbstractBaseScreenMixin}</b>：只把控件挂进 {@code children} 是<b>不够</b>的 ——
 * 锁定条正盖在 9 个「过滤器资源槽」上，而 {@code AbstractBaseScreen#mouseClicked} 会在<b>最前面</b>
 * 判定「鼠标下是资源槽」并直接 {@code return true}（轮不到 {@code super.mouseClicked} 分发给 children），
 * 表现就是「条画出来了，但按钮点不动」。所以本类实现
 * {@link RsccExporterBarHost}，由
 * {@code AbstractBaseScreenMixin} 在 HEAD 处把点击先交给锁定条（见其说明）。</p>
 *
 * <p><b>渲染硬规则</b>：文字一律 {@code dropShadow = false}；绘制范围与 hover / 点击判定
 * 共用同一批几何常量（都在控件内），必然同源。
 */
@Mixin(ExporterScreen.class)
public abstract class ExporterScreenMixin implements RsccExporterBarHost {
    /**
     * 锁定条内层底色矩形（Menu 坐标；与 {@code AbstractSimpleFilterContainerMenu} 的 9 槽 8+18i / y=20 同一横带）。
     * <p>本轮把 y 由 20 上移、高由 18 加高到 23：单元格需要「16px 图标 + 下方一行勾选框 / 共享角标」，
     * 加高后的内层覆盖 Menu y 16..38（外描边 y 15..39），仍然完整盖住 9 个过滤器槽（y 20..37）。</p>
     */
    private static final int RSCC_ROW_X = 8;
    private static final int RSCC_ROW_Y = 16;
    private static final int RSCC_ROW_W = 162;
    private static final int RSCC_ROW_H = 23;

    /** 覆盖过滤器槽位带的锁定条控件（非延长模式下它一帧不画、一点不拦）。 */
    @Unique
    private ExporterExecutorRowWidget rscc$executorRow;

    /** 界面初始化（{@link ExporterScreen} 自身声明）末尾挂上锁定条控件。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void rscc$addExecutorRowWidget(final CallbackInfo ci) {
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        final ExporterExecutorRowWidget widget = new ExporterExecutorRowWidget(
            screen.getGuiLeft() + RSCC_ROW_X,
            screen.getGuiTop() + RSCC_ROW_Y,
            RSCC_ROW_W,
            RSCC_ROW_H,
            screen.getMenu().containerId);
        ((ScreenAccessor) (Object) this).rscc$addRenderableWidget(widget);
        rscc$executorRow = widget;
    }

    /**
     * {@link RsccExporterBarHost} 实现：把点击转交锁定条。
     * <p>由 {@code AbstractBaseScreenMixin} 在 RS 槽位逻辑之前调用（见该 Mixin 的说明）——
     * 这是本轮修掉「按钮点不动」的关键：锁定条压着过滤器资源槽，RS 会把点击抢走并直接返回，
     * 所以不能只靠「控件挂在 children 里」这一条路。</p>
     */
    @Override
    public boolean rscc$handleExecutorBarClick(final double mouseX, final double mouseY, final int button) {
        return rscc$executorRow != null && rscc$executorRow.mouseClicked(mouseX, mouseY, button);
    }

    /** 原版 tooltip 阶段的最后一刻：交给控件决定是否接管（渲染锁定条补绘 + tooltip）。 */
    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void rscc$renderExecutorRowTooltip(final GuiGraphics graphics, final int mouseX, final int mouseY,
                                               final CallbackInfo ci) {
        if (rscc$executorRow != null && rscc$executorRow.rscc$renderTooltip(graphics, mouseX, mouseY)) {
            ci.cancel();
        }
    }
}
