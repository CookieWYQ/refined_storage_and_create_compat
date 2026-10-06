package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读访问器：暴露 RS <b>外部样板任务</b>的 {@code iterationsReceived}
 * （RS 2.0.0 源码 {@code api/autocrafting/task/ExternalTaskPattern.java:28}）。
 *
 * <h2>为什么它就是「已交付量」（本轮修复选定的权威读数）</h2>
 * <ol>
 *     <li><b>来源权威</b>：它只由 {@code updateIterationsReceived()} 写
 *     （同文件 127-141 行），而那个方法只在 {@code trySatisfy()} 里被调用
 *     （同文件 112-125 行）；{@code trySatisfy} 正是「外部产线把成品送回网络、RS 认领」的唯一路径
 *     —— root 样板走 {@code afterInsert}（104-110 行）、非 root 样板走 {@code beforeInsert}（96-102 行）。</li>
 *     <li><b>对本模组样板成立</b>：本模组的样板是 EXTERNAL 型（{@code SequenceAssemblyPatternItem} 用
 *     {@code PatternBuilder.pattern(PatternType.EXTERNAL)}），而 root EXTERNAL 样板的
 *     {@code afterInsert} 会被 {@code RootStorageImpl#notifyAfterInsertListeners} 调用
 *     （RS 源码 {@code api/storage/root/RootStorageImpl.java:107-126}），因此每交付一件都会 ++。</li>
 *     <li><b>口径正好是「件」</b>：{@code updateIterationsReceived} 按每个 output 的
 *     {@code (expected - stillNeeded) / output.amount()} 取最小值，即「已收到的完整迭代数」；
 *     本模组样板每次迭代产出 {@code results[0]}（序列装配主产物，件数 1）⇒ 它就等于已交付件数。</li>
 *     <li><b>存档 / 重载不丢</b>：它随任务快照读写（{@code ExternalTaskPattern.java:58} 读、
 *     {@code :252} 写，落盘见 {@code TaskSnapshotPersistence}），重载后由同一个注入重新读出，
 *     因此本模组<b>不需要自己记账、也不需要落盘</b>。</li>
 * </ol>
 *
 * <h2>为什么用 {@code targets=} 字符串 + 为什么挂在声明类上</h2>
 * <p>{@code ExternalTaskPattern} 是包私有类，源码里写不出 {@code ExternalTaskPattern.class}；
 * 字段声明在该类自身，因此按本工程硬规则（{@code tools/verify_mixin_shadows.py}）也必须挂在它自己身上
 * （挂到父类 {@code AbstractTaskPattern} 上会找不到字段 → 加载期崩游戏）。
 * 字段名与描述符已用 {@code javap -p -s} 对运行时真实 jar 复核：{@code private long iterationsReceived → J}。</p>
 *
 * <p>只读：本访问器只提供 getter，绝无 setter，绝不改任何任务状态。</p>
 */
@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.ExternalTaskPattern")
public interface RsccExternalTaskPatternAccessor {
    /** 只读：这条任务<b>已经从外部产线收到</b>的完整迭代数（= 已交付件数，见类注释的证据链）。 */
    @Accessor("iterationsReceived")
    long rscc$receivedIterations();
}
