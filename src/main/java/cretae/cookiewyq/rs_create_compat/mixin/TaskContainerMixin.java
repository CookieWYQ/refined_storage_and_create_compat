package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSinkProvider;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.Task;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.TaskContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「挂起 = 不再占用执行器」的<b>唯一实现点</b>：被本模组挂起的自动合成任务不再被 step。
 *
 * <p><b>RS 没有暂停 / 恢复 API</b>：一条自动合成任务唯一的「推进」入口就是
 * {@code TaskContainer} 每个 tick 对容器里的任务逐个调用
 * {@code Task#step(RootStorage, ExternalPatternSinkProvider, StepBehavior, TaskListener)} ——
 * 无论任务的提供者是 RS 自动合成器（{@code PatternProviderNetworkNode#doWork}）还是中继
 * （{@code RelayOutputPatternProvider#doWork}），都会走这里。因此在 {@code TaskContainer} 的
 * <b>单任务 step</b> 上注入并取消，就等价于把这条任务「暂停」：</p>
 * <ul>
 *     <li><b>不再抽料</b>：{@code TaskImpl} 不会再去 {@code rootStorage.extract(...)}；</li>
 *     <li><b>不再投料</b>：不会再去向机器 / 中继模拟与执行插入；</li>
 *     <li><b>不再驱动执行器</b>：这台仓的 step 循环里直接跳过它，后面的任务照常推进；</li>
 *     <li><b>物品 / 流体守恒</b>：任务已抽出的中间产物留在它<b>自己的</b>
 *     {@code TaskImpl.internalStorage} 里原地冻结 —— 本注入<b>只做跳过</b>，不搬运、不清空、
 *     不回流，因此前后总量必然一致（「不复制、不销毁」）。</li>
 * </ul>
 *
 * <p><b>为什么注入私有重载</b>：公开的 {@code step(Network, StepBehavior, TaskListener)} 是对
 * {@code tasks} 列表做 {@code removeIf} 循环，在这里取消会跳过整批任务；只有私有重载
 * {@code step(Task, StorageNetworkComponent, ExternalPatternSinkProvider, StepBehavior, TaskListener)}
 * 是「单个任务」，取消它才能只暂停指定的那一条。该私有方法声明在 {@code TaskContainer} 自身，
 * 符合本工程「只注入目标类自身声明的方法」的硬规则；两个同名重载必须写全描述符。</p>
 *
 * <p><b>挂起只作用于「仍在合成途中」的状态（本轮修正）</b>：判据取自 RS 自己的
 * {@link TaskState}（{@code TaskImpl#step} 的 {@code switch (state)}，见 RS 源码
 * {@code TaskImpl.java:126-137}）：{@code READY} / {@code EXTRACTING_INITIAL_RESOURCES} /
 * {@code RUNNING} 才是「合成进行中」，可以被挂起跳过；{@code RETURNING_INTERNAL_STORAGE} 与
 * {@code COMPLETED} 是「收尾 / 已结束」，<b>必须放行</b>。</p>
 *
 * <p><b>为什么必须放行 RETURNING_INTERNAL_STORAGE（用户硬要求）</b>：玩家在监视器上按 RS
 * <b>原生「取消」</b>时，{@code AutocraftingNetworkComponent#cancel} → {@code TaskContainer#cancel}
 * → {@code TaskImpl#cancel()}（{@code TaskImpl.java:140-143}）当刻就把状态置为
 * {@code RETURNING_INTERNAL_STORAGE}；RS 随后靠自己这个 state 分支
 * （{@code TaskImpl.java:134} → {@code returnInternalStorageAndTryCompleteTask}）把内部暂存
 * （已抽出的中间件 / 原料）insert 回网络。旧实现「挂起就一律跳过 step」，于是这条任务的回收分支
 * <b>永远不会被调用</b> —— 用户的东西既不在网络里、又不再被推进，只能等本模组记录过期
 * （默认约 10 分钟）才回收。用户原话：「<b>取消时，不管是怎么样的，你要立刻返回。</b>」
 * 因此这里按状态放行：取消后 RS 自己的回收逻辑当 tick 就跑完，物品立刻回网。</p>
 *
 * <p><b>客户端安全</b>：挂起表只由服务端扫描（{@code ServerTickEvent}）填充，客户端恒为空，
 * {@link AssemblyWatchdog#isSuspended} 直接返回 false，不会影响客户端 / 单人局内的本地网络。</p>
 */
@Mixin(TaskContainer.class)
public abstract class TaskContainerMixin {
    @Inject(
        method = "step(Lcom/refinedmods/refinedstorage/api/autocrafting/task/Task;"
            + "Lcom/refinedmods/refinedstorage/api/network/storage/StorageNetworkComponent;"
            + "Lcom/refinedmods/refinedstorage/api/autocrafting/task/ExternalPatternSinkProvider;"
            + "Lcom/refinedmods/refinedstorage/api/autocrafting/task/StepBehavior;"
            + "Lcom/refinedmods/refinedstorage/api/autocrafting/task/TaskListener;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void rscc$skipSuspendedTask(final Task task,
                                        final StorageNetworkComponent storage,
                                        final ExternalPatternSinkProvider sinkProvider,
                                        final StepBehavior stepBehavior,
                                        final TaskListener listener,
                                        final CallbackInfoReturnable<Boolean> cir) {
        if (task == null) {
            return;
        }
        if (rscc$isCrafting(task.getState())) {
            // 仍在合成途中：被挂起的任务跳过 step（返回 false = 未完成 → TaskContainer 把它保留在列表里）。
            if (AssemblyWatchdog.isSuspended(task.getId().id())) {
                cir.setReturnValue(false);
            }
            return;
        }
        // 任务已进入收尾 / 结束态（玩家按原生「取消」→ TaskImpl.cancel() 当刻把状态置为
        // RETURNING_INTERNAL_STORAGE，或任务自然收尾）：<b>放行 RS 自己的回收逻辑</b>，
        // 让它当 tick 把内部暂存还回网络 —— 用户要求「取消必须立刻返回」，绝不等记录过期。
        // 同时把我方的挂起记录 / 监视器告警同步移除（任务已在收尾，不留幽灵「已挂起」行与按钮）。
        AssemblyWatchdog.onTaskTerminated(task.getId().id());
    }

    /**
     * 该状态是否属于「仍在合成途中」（可以被挂起跳过）。
     *
     * <p>判据与 RS 的 {@code TaskImpl#step} 的 {@code switch (state)} 一一对应：只有
     * {@code READY} / {@code EXTRACTING_INITIAL_RESOURCES} / {@code RUNNING} 才是合成进行中；
     * {@code RETURNING_INTERNAL_STORAGE}（取消 / 收尾回收）与 {@code COMPLETED}（已结束）
     * 必须放行 RS 自己的逻辑。写成穷举的 switch（无 {@code default}）是为了让 RS 日后新增状态时
     * 编译期就报错，避免再出现「新状态被静默跳过、回收被冻住」的同类缺陷。</p>
     */
    private static boolean rscc$isCrafting(final TaskState state) {
        return switch (state) {
            case READY, EXTRACTING_INITIAL_RESOURCES, RUNNING -> true;
            case RETURNING_INTERNAL_STORAGE, COMPLETED -> false;
        };
    }
}
