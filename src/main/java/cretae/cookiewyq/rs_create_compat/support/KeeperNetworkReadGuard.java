package cretae.cookiewyq.rs_create_compat.support;

/**
 * 保持器「从网络抽取」的<b>重入保护</b>（第 118 轮，独立只读复核 H2）。
 *
 * <h2>为什么需要</h2>
 * <p>定量保持器故意把「网络里与标记同类的资源」通过能力暴露给相邻方块（供漏斗 / 管道取出，
 * 这是设计里的功能，不是 bug）。但 RS 2.0.9 有<b>外部存储</b>方块：它会把相邻方块的
 * {@code IItemHandler} / {@code IFluidHandler} 当成自己的存储源注册进同一个网络。于是形成环：</p>
 * <pre>
 *   外部存储读保持器能力 → 保持器 {@code extract} 从网络抽 → 网络的存储源里又包含那台外部存储
 *   → 外部存储再读保持器能力 → …（无限递归 ⇒ StackOverflowError）
 * </pre>
 *
 * <h2>做了什么</h2>
 * <p>只加一道<b>同线程重入闸</b>：已经在「保持器从网络抽取」的过程中时，内层抽取直接返回 0
 * （本层让位，不递归、不重复抽），抽取结束后立即复位。它不改变任何正常路径的语义
 * （正常单层抽取的进入 / 退出各一次），也<b>不</b>解决「外部存储把网络镜像再算一遍导致存量虚高」——
 * 那是产品口径问题（见 {@code docs/ROUND118_KEEPER_TARGET_SYNC.md} §5），需要用户拍板。</p>
 *
 * <h2>为什么是纯 JDK</h2>
 * <p>不 import 任何 Minecraft 类型 ⇒ 自检可以 javac 编译后真跑（验证「进入/退出/重入」语义）。
 */
public final class KeeperNetworkReadGuard {
    /** 当前线程是否已在「保持器从网络抽取」中（ThreadLocal：服务端单线程也是多线程池环境，必须按线程隔离）。 */
    private static final ThreadLocal<Boolean> IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private KeeperNetworkReadGuard() {
    }

    /**
     * 尝试进入抽取段。
     *
     * @return {@code true} = 成功进入（调用方必须在 {@code finally} 里 {@link #exit()}）；
     *         {@code false} = 已经在内层抽取中（调用方必须直接返回 0，不得再抽）
     */
    public static boolean enter() {
        if (Boolean.TRUE.equals(IN_PROGRESS.get())) {
            return false;
        }
        IN_PROGRESS.set(Boolean.TRUE);
        return true;
    }

    /** 退出抽取段（幂等：重复调用只是再置一次 false）。 */
    public static void exit() {
        IN_PROGRESS.set(Boolean.FALSE);
    }

    /** 当前线程是否处于抽取段（诊断 / 自检用）。 */
    public static boolean inProgress() {
        return Boolean.TRUE.equals(IN_PROGRESS.get());
    }
}
