package cretae.cookiewyq.rs_create_compat.support;

/**
 * 「我方形状处理器」的<b>可重入（re-entrancy）保护</b>：给 {@code mixin/block/CamouflageShapeMixin}
 * 的四条注入路径共用的一面「我正在里面」的线程旗标。
 *
 * <h2>没有它会怎样：启动即崩的无限递归</h2>
 * <p>2026-09-26 的 client 崩溃（{@code Bootstrap} / {@code StackOverflowError}）是一条精确的环：</p>
 * <ol>
 *     <li>{@code BlockBehaviour.BlockStateBase#getCollisionShape(BlockGetter, BlockPos)}（两参重载）
 *     —— 被 {@code CamouflageShapeMixin} 注入；</li>
 *     <li>注入处理器 → {@link RsccCamouflage#overridesShape} → {@code SeparationFrameGuard.isSheathable}；</li>
 *     <li>{@code isSheathable} 的「整格完整方块」判定调用
 *     {@code BlockState#isCollisionShapeFullBlock(BlockGetter, BlockPos)}；</li>
 *     <li>方块状态的烘焙缓存（{@code BlockBehaviour.BlockStateBase#cache}）此刻尚为 {@code null}
 *     （方块状态烘焙期，或动态外形方块），于是原版走
 *     {@code BlockBehaviour.isCollisionShapeFullBlock(state, level, pos)} →
 *     {@code state.getCollisionShape(level, pos)}；</li>
 *     <li>回到第 1 帧 —— 每绕一圈压一层栈，直到 {@link StackOverflowError}。</li>
 * </ol>
 *
 * <h2>旗标语义</h2>
 * <p>最外层进入时置位；<b>任何</b>嵌套的形状查询看到它是 {@code true} 就<b>不 cancel</b>
 * （HEAD 注入不设返回值 = 原版实现照常执行），因此环在第 2 层就被切断。复位放在
 * {@code finally} 里 —— 形状查询可能在方块状态烘焙、区块网格烘焙（多线程）等任何路径上抛异常，
 * 漏复位会把那一整条线程永远锁在「重入」状态。</p>
 *
 * <p><b>为什么是 {@link ThreadLocal}</b>：形状查询既发生在服务端主线程，也发生在客户端区块烘焙的
 * 工作线程上；旗标必须按线程隔离，否则两条互不相干的烘焙线程会互相看到对方的旗标而误判重入。</p>
 *
 * <p><b>为什么单独一个类而不是混入类的静态字段</b>：静态字段需要 Mixin 合并进目标类，
 * 语义与版本相关性都比「一个普通工具类」更脆；把状态放在一个普通类里，
 * 注入处理器只负责 {@code begin()} / {@code end()} 两句话，最容易审。</p>
 */
public final class RsccShapeQueryGuard {
    /** 本线程是否已经在我方形状处理器内部（{@code Boolean.FALSE} 而不是 null，省掉拆箱判空分支）。 */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RsccShapeQueryGuard() {
    }

    /**
     * 尝试进入我方形状处理器。
     *
     * @return {@code true} = 这是最外层进入，调用方应正常做「是否套壳」判定，并在 {@code finally}
     *     里调用 {@link #end()}；{@code false} = <b>重入</b>，调用方必须<b>立刻返回且不 cancel</b>
     *     （放行原版实现），并且<b>不得</b>调用 {@link #end()}（旗标归最外层所有）
     */
    public static boolean begin() {
        if (ACTIVE.get()) {
            return false;
        }
        ACTIVE.set(Boolean.TRUE);
        return true;
    }

    /** 复位旗标（必须在 {@code finally} 里调用，且只由拿到 {@code begin() == true} 的那一层调用）。 */
    public static void end() {
        ACTIVE.set(Boolean.FALSE);
    }

    /** 自检 / 诊断用：当前线程是否在我方形状处理器内部。 */
    public static boolean isActive() {
        return ACTIVE.get();
    }
}
