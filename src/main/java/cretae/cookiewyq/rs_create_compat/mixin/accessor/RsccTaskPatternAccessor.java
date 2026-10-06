package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读访问器：暴露 RS 任务样板基类的 {@code root} 与 {@code pattern} 两个字段
 * （{@code AbstractTaskPattern}，RS 2.0.0 源码 {@code api/autocrafting/task/AbstractTaskPattern.java:21-22}）。
 *
 * <h2>为什么需要它（本轮的根因修复）</h2>
 * <p>本模组的序列装配样板是 <b>EXTERNAL 型 root 样板</b>，而 RS 的 {@code TaskStatus} 里
 * 「目标资源那一项」的 {@code stored} / {@code crafting} 对本模组样板<b>恒为 0</b>
 * （证据见 {@code AssemblyWatchdog#deliveredAmount} 的 javadoc）。唯一被正确维护的量是
 * {@code ExternalTaskPattern#iterationsReceived}，但它既不是公开 API，也不在任何 status 里。
 * 因此由 {@code RsccTaskDeliveredMixin} 注入 {@code TaskImpl#getStatus} 的 RETURN，
 * 遍历 {@code TaskImpl#patterns} 取出<b>root</b> 样板并读走 {@code iterationsReceived}。
 * 要判断「哪个是 root」与「它产出哪个资源」，就必须读这两个字段。</p>
 *
 * <h2>为什么用 {@code targets=} 字符串而不是 {@code Target.class}</h2>
 * <p>{@code AbstractTaskPattern} 是<b>包私有</b>类（RS 源码里没有 {@code public} 修饰），
 * 本模组的源码里根本写不出 {@code AbstractTaskPattern.class}。{@code @Mixin(targets = "...")}
 * 用二进制类名定位，是 Mixin 为这类目标提供的标准写法。</p>
 *
 * <h2>为什么必须挂在「声明类」上</h2>
 * <p>本工程硬规则（{@code tools/verify_mixin_shadows.py}）：{@code @Accessor} 只能访问其
 * {@code @Mixin} 目标类<b>自身声明</b>的成员；把 {@code root} / {@code pattern} 挂到子类
 * （{@code ExternalTaskPattern}）上会在模组加载期抛 {@code InvalidMixinException} 直接崩游戏
 * （已有事故：{@code mainNetworkNode} 声明在祖父类上、子类 shadow 必崩，见 2026-09-13 崩溃报告）。
 * 两个字段都声明在 {@code AbstractTaskPattern} 自身，因此这里就挂在它上面。</p>
 *
 * <p>字段名与描述符已用 {@code javap -p -s} 对运行时真实 jar（refinedstorage-neoforge-2.0.0.jar）
 * 复核：{@code protected final boolean root → Z}、{@code protected final Pattern pattern →
 * Lcom/refinedmods/refinedstorage/api/autocrafting/Pattern;}。只读，绝不写入。</p>
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern")
public interface RsccTaskPatternAccessor {
    /** 只读：这个任务样板是不是「订单的直接产出样板」（root），见 RS 源码 {@code plan.root()}。 */
    @Accessor("root")
    boolean rscc$isRootPattern();

    /** 只读：该样板对应的 RS 样板对象（用它读 {@code layout().outputs()} 即产出资源与每迭代件数）。 */
    @Accessor("pattern")
    Pattern rscc$pattern();
}
