package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.RsccExternalTaskPatternAccessor;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.RsccTaskPatternAccessor;
import cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * <b>只读注入</b>：把 RS 任务的「已交付件数」镜像到本模组（本轮根因修复的数据来源，见
 * {@link AssemblyWatchdog#deliveredAmount} 的完整证据链）。
 *
 * <h2>为什么必须注入（而不是用公开 API）</h2>
 * <p>RS 2.0.0 的公开 API 里<b>没有任何一处</b>暴露「外部产线已交付多少」：</p>
 * <ul>
 *     <li>{@code TaskStatus.Item#stored} 只由任务 {@code internalStorage} 填（{@code TaskImpl.java:162-164}），
 *     而 root EXTERNAL 样板的 {@code beforeInsert} 恒返回 0（{@code ExternalTaskPattern.java:96-102}）、
 *     认领只发生在 {@code afterInsert}（104-110 → 112-125），<b>只减 expectedOutputs、不写 internalStorage</b>
 *     ⇒ 对本模组样板恒为 0；</li>
 *     <li>{@code TaskStatus.Item#crafting} 只由 {@code InternalTaskPattern#appendStatus} 填
 *     （{@code InternalTaskPattern.java:74-81}）⇒ 对本模组样板恒为 0；</li>
 *     <li>{@code TaskStatus#percentageCompleted}：外部样板的权重是剩余派发次数
 *     （{@code ExternalTaskPattern.java:174-177}），派发一空权重即为 0 ⇒ 加权完成度恒为 0
 *     （{@code TaskImpl.java:151-165}）；</li>
 *     <li>{@code TaskStatus.Item#scheduled} 是「还没派发给 sink 的迭代数」，不是已交付量
 *     （本模组的执行器把每轮原料原样放回网络，派发会瞬间跑完）；</li>
 *     <li>{@code TaskStatus.Item#processing} 描述的是<b>输入</b>在制，且要按每轮投入量换算，
 *     既不是交付量也需要重算一遍样板原料表。</li>
 * </ul>
 * <p>唯一正确维护的量是 {@code ExternalTaskPattern#iterationsReceived}，因此只能由 Mixin 读取。</p>
 *
 * <h2>注入点为什么选 {@code TaskImpl#getStatus} 的 RETURN</h2>
 * <p>{@code getStatus()} 是 RS 为「任务进度」重建 {@link TaskStatus} 的唯一入口，</p>
 * <ul>
 *     <li>本模组的执行舱每 tick 都会读任务状态（份额 / 在制名额 / 收尾判据），因此镜像<b>天然保鲜</b>；
 *     </li>
 *     <li>不需要自建 tick 钩子、不需要落盘：RS 自己把 {@code iterationsReceived} 随任务快照持久化
 *     （{@code ExternalTaskPattern.java:58} / {@code :252}），重载后同一个注入重新读出真值。</li>
 * </ul>
 * <p>注入的是 {@code TaskImpl} <b>自身声明</b>的方法（不是父类 / 接口方法），符合本工程硬规则
 * （{@code tools/verify_mixin_shadows.py} 会校验）。</p>
 *
 * <h2>只写什么</h2>
 * <p>只调用 {@link AssemblyWatchdog#publishTaskDelivered}（纯内存只读镜像），
 * <b>绝不</b>改任务状态、绝不搬运资源、绝不取消任务 —— 返回 {@code 0} 给 RS 的拦截语义
 * （本注入在 RETURN 处，没有 {@code cancellable}，改不了任何返回值）。</p>
 *
 * <h2>边界（写清楚，不假装它更聪明）</h2>
 * <ul>
 *     <li>只镜像 <b>root</b> 样板（{@code plan.root()}）的读数：订单的直接产出才是「订单剩余量」
 *     要减掉的那一份。本模组的样板正常情况下就是 root 样板（用户点的是它自己的产物）；
 *     若某条订单把本模组的样板当<b>子</b>样板用（例如更大的配方把序列装配件当原料），
 *     这里不发布，调用方退回 RS 既有读数（与修复前逐字一致，绝不因此变差）。</li>
 *     <li>镜像按 (任务 uuid, 产出资源) 取 <b>max</b>：交付量物理上单调，读到的先后不影响结论。</li>
 * </ul>
 */
@Mixin(TaskImpl.class)
public abstract class RsccTaskDeliveredMixin {
    /** RS 任务的全部未完成样板（{@code TaskImpl.java:33}，声明在 TaskImpl 自身 → 可 shadow）。 */
    @Shadow
    @Final
    private Map<?, ?> patterns;

    /** {@code TaskImpl#getStatus} 返回时：把 root 外部样板的 {@code iterationsReceived} 镜像出去。 */
    @Inject(method = "getStatus", at = @At("RETURN"))
    private void rscc$publishDelivered(final CallbackInfoReturnable<TaskStatus> cir) {
        final TaskStatus status = cir.getReturnValue();
        if (status == null || status.info() == null || status.info().id() == null) {
            return;
        }
        final Map<?, ?> live = this.patterns;
        if (live == null || live.isEmpty()) {
            // 全部样板都已完成（expectedOutputs 收满 → 移入 completedPatterns）：此刻已交付量
            // 必然等于计划迭代数，而镜像里最后一次非零读数就是这个值（单调 max，绝不回退）。
            return;
        }
        for (final Object candidate : live.values()) {
            if (candidate == null
                || !(candidate instanceof RsccExternalTaskPatternAccessor external)
                || !(candidate instanceof RsccTaskPatternAccessor meta)) {
                continue; // INTERNAL 样板没有 iterationsReceived（RS 源码：只声明在 ExternalTaskPattern 上）
            }
            if (!meta.rscc$isRootPattern()) {
                continue; // 只认「订单的直接产出样板」，见类注释的边界说明
            }
            final long received = external.rscc$receivedIterations();
            if (received <= 0L) {
                continue; // 还没交付过：不发布（镜像里没有记录 == 0，与"发了 0"等价）
            }
            final Pattern pattern = meta.rscc$pattern();
            if (pattern == null || pattern.layout() == null) {
                continue;
            }
            for (final ResourceAmount output : pattern.layout().outputs()) {
                if (output == null || output.resource() == null) {
                    continue;
                }
                // 每次迭代产出 output.amount() 件 ⇒ 已交付件数 = 已收迭代数 × 每迭代件数。
                // 本模组的序列装配样板每迭代产 1 件（PatternBuilder 登记 results[0] 的 stack count），
                // 这里仍按 layout 的诚实值换算，绝不当成"永远是 1"。
                AssemblyWatchdog.publishTaskDelivered(status.info().id().id(), output.resource(),
                    received * Math.max(1L, output.amount()));
            }
        }
    }
}
