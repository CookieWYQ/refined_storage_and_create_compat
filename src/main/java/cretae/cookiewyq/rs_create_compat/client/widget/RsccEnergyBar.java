package cretae.cookiewyq.rs_create_compat.client.widget;

import com.refinedmods.refinedstorage.common.support.widget.ProgressWidget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * <b>全模组唯一的「纯电」能量条实现</b>：内部就是 <b>RS 原版</b>
 * {@link ProgressWidget}（sprite {@code refinedstorage:widget/progress_bar}），外面套 1px 黑框 +
 * {@code #2C2C2C} 槽底。
 *
 * <h2>为什么是这张 sprite / 为什么「直接复用他们的代码」成立</h2>
 * <p>用户第 108 条原话：「所有那种纯电的能量条……应该保持和那个范围充电器、以及那个控制器一样，
 * 你应该直接复用他们的代码，而不是自己画一坨」。翻 RS 2.0.9 的 sources jar 后确认：</p>
 * <ul>
 *     <li>{@code com.refinedmods.refinedstorage.common.support.widget.ProgressWidget}
 *     （{@code common/support/widget/ProgressWidget.java:17-49}）只做三件事：按比例算亮起的像素高度、
 *     用 sprite {@code refinedstorage:widget/progress_bar}（16×70 的 PNG，
 *     {@code assets/refinedstorage/textures/gui/sprites/widget/progress_bar.png}）从底部切片 blit、
 *     hover 时自己渲染 tooltip。<b>它不画槽底/外框</b>（RS 控制器那圈灰底是它自己
 *     {@code controller.png} 里的像素，我们引用不到也不需要）。</li>
 *     <li>本模组的「参考实现」{@code RangeChargerScreen} 早就在用它：
 *     {@code RangeChargerScreen#init} 里 {@code new ProgressWidget(leftPos+26, topPos+20, 16, 51, ...)}，
 *     外框/槽底是 {@code RangeChargerScreen#renderBg} 里 2 行 {@code fill}（18×53 纯黑 + 16×51 #2C2C2C）。</li>
 *     <li>因此本类 = <b>把那 2 行 fill 与 widget 构造抽成一份</b>，三家（范围充电器 / FE 能量立方 /
 *     分解发电机）都调它。<b>不新增任何 PNG、不复制 RS 的贴图</b>：sprite 由 RS 的 widget 自己解析；
 *     我们这边只有「黑框 + 深灰槽底」两个原版色值（与范围充电器逐像素同源）。</li>
 * </ul>
 *
 * <h2>几何契约（改之前先看调用方）</h2>
 * <ul>
 *     <li>宽 = {@link #WIDTH} = 16（= RS sprite 宽度，<b>不许改</b>：sprite 横向按 16 列分 3 段
 *     ——{@code x=0..1} 亮边 {@code #18B6D1/#0FA9C3}、{@code x=2..3} 过渡 {@code #0C9DB6/#078DA5}、
 *     {@code x=4..15} 主体 {@code #0D8FA5/#087E92}，另有 1px 纵向交替纹理。旧的自绘实现只取了主体色，
 *     把这层纹理丢了，实测就是「一坨纯色」）；</li>
 *     <li>高 = 调用方给（范围充电器 {@link #DEFAULT_HEIGHT}=51；FE 能量立方 58；分解发电机 51）。
 *     高度只决定「亮起像素」的分子，sprite 的切窗口是 {@code [height - 亮起, height]}（RS 内部算法），
 *     所以高 ≤ 70 都不会采样越界；</li>
 *     <li>{@link #drawTrack} 画的外框 = 内容区四周各扩 1px（18×height+2）。</li>
 * </ul>
 *
 * <h2>为什么背包 HUD 的横向条不在本类（第 108 轮 Lead 裁决 ⑤）</h2>
 * <p>{@code client/JetpackHudOverlay} 的那条是 <b>HUD 里的横向 96×8 条</b>：不是 GUI 屏幕、没有
 * hover/tooltip 语义，而 RS sprite 是 <b>竖向 16×70</b>，硬套会横向拉伸变形。故不纳入统一。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // renderBg 里（先背景贴图/面板，再这道框；必须在 widget 之前画，否则槽底会盖住进度）
 * RsccEnergyBar.drawTrack(guiGraphics, leftPos + BAR_X, topPos + BAR_Y, BAR_H);
 * // init 里（addRenderableWidget 是 protected，只能调用方自己挂）
 * addRenderableWidget(RsccEnergyBar.create(leftPos + BAR_X, topPos + BAR_Y, BAR_H,
 *     menu::getEnergy, menu::getMaxEnergy,
 *     () -> List.of(RsccEnergyBar.valueLine(menu.getEnergy(), menu.getMaxEnergy()))));
 * }</pre>
 */
public final class RsccEnergyBar {
    /**
     * 条的内容区宽度：<b>必须 16</b>。
     * <p>= RS sprite 宽度；{@link ProgressWidget} 的 blit 用 {@code (sprite, 16, 70, u, v, x, y, width, height)}，
     * 内容区一旦不是 16 就会横向裁掉 sprite 的列（左侧亮边最先丢），观感立刻不像 RS 的条。</p>
     */
    public static final int WIDTH = 16;

    /**
     * 参考实现（范围充电器）用的高度：16×51。
     * <p>充电器面板那三行 XYZ 的行距是 17（20 / 37 / 54），51 正好与它们齐平；这是这套条在本工程里
     * 「原本的样子」，其它界面若面板纵向空间不同可以传别的值（FE 立方传 58，理由见该界面常量）。</p>
     */
    public static final int DEFAULT_HEIGHT = 51;

    /**
     * 外框颜色：纯黑 1px（{@code RangeChargerScreen#renderBg} 第 1 行 fill 的原值 {@code 0xFF000000}）。
     */
    public static final int COLOR_FRAME = 0xFF000000;

    /**
     * 槽底颜色：{@code #2C2C2C}（{@code RangeChargerScreen#renderBg} 第 2 行 fill 的原值
     * {@code 0xFF2C2C2C}）。空条区 + 未亮起的部分都是它。
     */
    public static final int COLOR_TRACK = 0xFF2C2C2C;

    private RsccEnergyBar() {
    }

    /**
     * 画外框（1px 纯黑）+ 槽底（{@code #2C2C2C}）。
     *
     * <p><b>必须在 {@code renderBg} 里、且在当前界面的背景贴图/面板之后调用</b>：进度条 widget 只在
     * {@code renderWidget} 阶段画「亮起的部分」，槽底得由背景提供；顺序反了会被面板盖掉。</p>
     *
     * <p>像素与范围充电器第 98 轮以来的实现逐像素相同：
     * {@code fill(x-1, y-1, x+17, y+52, 0xFF000000)} + {@code fill(x, y, x+16, y+51, 0xFF2C2C2C)}；
     * 这里写成 {@code x + WIDTH + 1} / {@code y + height + 1} 只是把 16/51 换成参数。</p>
     *
     * @param x      内容区左上角 x（不含 1px 外框）
     * @param y      内容区左上角 y（不含 1px 外框）
     * @param height 内容区高度（{@link #DEFAULT_HEIGHT} 或各界面自己的高度）
     */
    public static void drawTrack(final GuiGraphics guiGraphics, final int x, final int y, final int height) {
        guiGraphics.fill(x - 1, y - 1, x + WIDTH + 1, y + height + 1, COLOR_FRAME);
        guiGraphics.fill(x, y, x + WIDTH, y + height, COLOR_TRACK);
    }

    /**
     * 建一条能量条（<b>不自动挂到界面上</b>：{@code Screen#addRenderableWidget} 是 {@code protected}，
     * 外部类调不了，调用方拿到 widget 自己 {@code addRenderableWidget(...)}）。
     *
     * <p>比例、0 除、超 100% 夹紧都在 {@link #ratio} 里做：调用方只管给「存量 / 容量」两个 getter，
     * 不要自己再算一遍比例（否则界面与条形两套算法，改一处另一处不跟——本工程范围充电器就踩过
     * 「界面自己算速率算溢出成负数」的坑）。</p>
     *
     * @param x        内容区左上角 x
     * @param y        内容区左上角 y
     * @param height   内容区高度
     * @param stored   存量（每帧现取；可返回 int，调用方用 lambda 包一层即可）
     * @param capacity 容量（同上；{@code <= 0} 时按 0% 画）
     * @param tooltip  悬停提示（sprite 自己渲染；建议直接用 {@link #valueLine}）
     */
    public static ProgressWidget create(final int x, final int y, final int height,
                                        final LongSupplier stored, final LongSupplier capacity,
                                        final Supplier<List<Component>> tooltip) {
        return new ProgressWidget(x, y, WIDTH, height,
            () -> ratio(stored.getAsLong(), capacity.getAsLong()),
            tooltip);
    }

    /**
     * 存量 / 容量 → {@code [0, 1]} 的比例（唯一的比例算法）。
     * <p>容量 {@code <= 0}（还没算出容量 / 未接网络）或存量 {@code <= 0} ⇒ 0；
     * 存量大于容量（配置被改小、集群数量变化）⇒ 夹到 1，绝不画到条外面去。</p>
     */
    public static double ratio(final long stored, final long capacity) {
        if (stored <= 0L || capacity <= 0L) {
            return 0D;
        }
        final double raw = (double) stored / (double) capacity;
        return raw >= 1D ? 1D : raw;
    }

    /**
     * 统一的悬浮数值句：{@code 1,234 / 5,000,000 FE}（千分位走工程既有的
     * {@link RsccNumberField#grouped(long)}）。
     *
     * <p><b>为什么带 {@code FE} 后缀而不是缩写成 M/k</b>：用户口径是「数字完整可读」；界面里空间不够时
     * 各界面自己用 {@code RsccTextFit} 缩字号，不在这里做缩写（缩写会丢失精度，而且中文界面里
     * {@code M/k} 是英文单位）。</p>
     */
    public static Component valueLine(final long stored, final long capacity) {
        return Component.literal(
            RsccNumberField.grouped(stored) + " / " + RsccNumberField.grouped(capacity) + " FE");
    }
}
