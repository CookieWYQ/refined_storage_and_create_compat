package cretae.cookiewyq.rs_create_compat.support;

/**
 * 桥接接口：由客户端 Mixin（注入 RS 输出总线界面 {@code ExporterScreen}）实现，
 * 让「输出总线锁定条」这一自绘控件能在 RS 的槽位逻辑<b>之前</b>拿到点击。
 *
 * <p><b>为什么需要它</b>：锁定条正好盖住 9 个「过滤器」资源槽（{@code ResourceSlot}，
 * 类型 {@code FILTER}）。RS 的 {@code AbstractBaseScreen#mouseClicked} 在<b>第一行</b>就判断
 * 「鼠标下是资源槽 → 处理它并 {@code return true}」，该分支在 {@code super.mouseClicked}
 * <b>之前</b>返回，因此原版「先分发给子控件」的流程根本走不到锁定条控件 ——
 * 表现就是「界面画出来了，但条上的两个按钮点不动、无法切换」。
 * <p>修法：在 {@code AbstractBaseScreen#mouseClicked} 的 HEAD 处，若当前界面是本接口的实现者
 * （即输出总线界面），先把点击交给锁定条；锁定条吞掉才取消原版逻辑。非延长模式 / 点击在条外时
 * 一律返回 {@code false}，界面与 RS 原版逐字节一致。
 *
 * <p>与 {@link RsccExporterMenuBridge} / {@link RsccExporterExecutorMode} 同理，本接口刻意放在
 * support 包：mixin 包被 mixes.json 声明为 Mixin 专用包，普通类放在其中被外部直接引用会抛
 * IllegalClassLoadError。
 */
public interface RsccExporterBarHost {
    /**
     * 处理「输出总线锁定条」范围内的点击。
     *
     * @return {@code true} 表示点击已被锁定条接管（调用方应取消 RS 原版的槽位处理）
     */
    boolean rscc$handleExecutorBarClick(double mouseX, double mouseY, int button);
}
