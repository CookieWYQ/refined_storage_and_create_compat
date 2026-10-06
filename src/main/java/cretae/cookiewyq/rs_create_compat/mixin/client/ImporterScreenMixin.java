package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.common.importer.ImporterScreen;
import cretae.cookiewyq.rs_create_compat.client.widget.ExporterExecutorRowWidget;
import cretae.cookiewyq.rs_create_compat.client.widget.ImporterExecutorBarSource;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.ScreenAccessor;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterBarHost;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterBarHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 输入总线界面在「延长型输入」模式下的改版（{@code ExporterScreenMixin} 的对称版）。
 *
 * <p><b>改了什么</b>：RS 输入总线原本是「9 格过滤器槽 + 右侧插件槽 + 右侧两个侧面按钮
 * （过滤模式 / 模糊模式）」的布局。当这台输入总线相邻着（或用 RS 线缆连到）处于「总线输出」模式的
 * 序列执行仓时：</p>
 * <ol>
 *     <li>把整条过滤器槽位带（Menu 坐标 x 8..170 / y 20..38）用一块与 RS 面板同风格的类别条盖住，
 *     条内放<b>动态类别单元格</b>（与输出总线完全同一套控件 {@link ExporterExecutorRowWidget}），
 *     放不下时横向滚动；</li>
 *     <li>左键点单元格切换该类别的「是否收回」（多选）；滚轮左右翻看；Shift+滚轮切换鼠标下那一格；
 *     条下方是<b>◀ ▶ 翻页按钮 + 页码</b>（本轮取代了原先那个「不能输入、又太挤」的搜索框）；</li>
 *     <li>整条槽位带的点击被吞掉：原来的过滤器槽位锁定、不可编辑；</li>
 *     <li>悬停单元格 / 类别条 / 翻页按钮时<b>手动渲染 tooltip</b>（本模组 GUI 不会自动渲染 tooltip）；</li>
 *     <li>类别条下方还有一个「<b>自动 / 手动</b>」开关（默认全自动 = 只读展示，玩家不必勾选任何东西）；
 *     控件 {@link ExporterExecutorRowWidget} 自己定几何 + 管绘制 + 管命中，本类只负责把它挂进 Screen。</li>
 * </ol>
 *
 * <p><b>本轮同时移除了两个语义不再适用的原生控件</b>：「过滤器模式（白 / 黑名单）」与「模糊模式」
 * 两个侧边按钮 —— 由 {@code AbstractBaseScreenMixin} 在 {@code addSideButton} 处拦截、不再挂上
 * （见那里的说明；不再挂 = 不留空洞、不留重复坐标，因为它们本来画在面板<b>左侧外面</b>）。</p>
 *
 * <p><b>为什么不遮挡 RS 原生控件</b>：类别条占用的正是 9 个「过滤器资源槽」所在的横带
 * （{@code AbstractSimpleFilterContainerMenu} 里 {@code FILTER_SLOT_X = 8} / {@code FILTER_SLOT_Y = 20}
 * / 每格 18px），面板宽 210、插件槽在 x=187、侧面按钮画在面板<b>左侧外面</b>（{@code getSideButtonX()}
 * 返回 {@code leftPos - SIZE - 2}），因此类别条既没有压到插件槽，也没有压到侧面按钮 ——
 * 它只是<b>按照「延长模式下过滤器不再生效」的语义盖住过滤器槽</b>。布局常量与输出总线侧逐字一致，
 * 于是两个界面在延长模式下的观感也一致。</p>
 *
 * <p><b>为什么点击要另接一路</b>：类别条正盖在 9 个「过滤器资源槽」上，而
 * {@code AbstractBaseScreen#mouseClicked} 会在<b>最前面</b>判定「鼠标下是资源槽」并直接 {@code return true}
 * （轮不到 {@code super.mouseClicked} 分发给 children）。所以本类实现
 * {@link RsccExporterBarHost}，由 {@code AbstractBaseScreenMixin} 在 HEAD 处把点击先交给类别条。</p>
 *
 * <p><b>tooltip 为什么走另一个接口</b>：{@link ImporterScreen} <b>自身没有声明</b> {@code renderTooltip}
 * （只声明了 {@code init}），而本项目硬规则是「只能注入目标类自身声明的方法」；因此 tooltip 改由
 * {@code AbstractBaseScreenMixin} 在声明处 {@code AbstractBaseScreen#renderTooltip} 注入，
 * 再按 {@link RsccImporterBarHost} 回调到本类。</p>
 */
@Mixin(ImporterScreen.class)
public abstract class ImporterScreenMixin implements RsccExporterBarHost, RsccImporterBarHost {
    /**
     * 类别条内层底色矩形（Menu 坐标；与 {@code AbstractSimpleFilterContainerMenu} 的 9 槽 8+18i / y=20
     * 同一横带）。常量与 {@code ExporterScreenMixin} 逐字一致，保证两个界面观感相同：
     * 覆盖 Menu y 16..38（外描边 y 15..39），完整盖住 9 个过滤器槽（y 20..37）。
     */
    private static final int RSCC_ROW_X = 8;
    private static final int RSCC_ROW_Y = 16;
    private static final int RSCC_ROW_W = 162;
    private static final int RSCC_ROW_H = 23;

    /** 类别条控件（非延长模式下它一帧不画、一点不拦，界面完全等同 RS 原版）。 */
    @Unique
    private ExporterExecutorRowWidget rscc$importerRow;

    /** 界面初始化（{@link ImporterScreen} 自身声明）末尾挂上类别条控件。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void rscc$addImporterRowWidget(final CallbackInfo ci) {
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        final ExporterExecutorRowWidget widget = new ExporterExecutorRowWidget(
            screen.getGuiLeft() + RSCC_ROW_X,
            screen.getGuiTop() + RSCC_ROW_Y,
            RSCC_ROW_W,
            RSCC_ROW_H,
            new ImporterExecutorBarSource(screen.getMenu().containerId));
        ((ScreenAccessor) (Object) this).rscc$addRenderableWidget(widget);
        rscc$importerRow = widget;
    }

    /**
     * {@link RsccExporterBarHost} 实现：把点击转交类别条。
     * <p>由 {@code AbstractBaseScreenMixin} 在 RS 槽位逻辑之前调用 —— 类别条压着过滤器资源槽，
     * RS 会把点击抢走并直接返回，所以不能只靠「控件挂在 children 里」这一条路。</p>
     */
    @Override
    public boolean rscc$handleExecutorBarClick(final double mouseX, final double mouseY, final int button) {
        return rscc$importerRow != null && rscc$importerRow.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * {@link RsccImporterBarHost} 实现：把 tooltip（含在最后阶段补绘一次类别条）交给控件；
     * 控件接管时取消原版 tooltip（被盖住的过滤器槽位不再弹出可编辑提示）。
     */
    @Override
    public boolean rscc$renderImporterBarTooltip(final GuiGraphics graphics, final int mouseX, final int mouseY) {
        return rscc$importerRow != null && rscc$importerRow.rscc$renderTooltip(graphics, mouseX, mouseY);
    }
}
