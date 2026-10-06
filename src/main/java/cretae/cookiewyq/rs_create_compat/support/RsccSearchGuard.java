package cretae.cookiewyq.rs_create_compat.support;

/**
 * 线程级「输出总线邻块搜索」重入守卫。
 *
 * <p><b>为什么需要它（2026-09-13 服务端 StackOverflowError 的兜底）</b>：输出总线的连接判定要求
 * 探测相邻方块。历史实现里通过 {@code Level#getBlockEntity} 探测，会<b>强制加载并初始化</b>相邻方块实体；
 * 而相邻的输出总线实体一旦初始化，又会在自己的初始化流程里继续探测…… 于是
 * {@code A 初始化 → 探测到 B → B 初始化 → 探测到 A → …} 无限递归，区块加载时直接栈溢出。
 * 根因已在搜索阶段改为「只按方块状态判定、绝不取方块实体」消除；本守卫是<b>第二道保险</b>：
 * 只要当前线程已经处于一次搜索中，任何再次进入搜索的调用都立即失败返回（不递归），
 * 从而无论未来哪条调用链意外形成环，都不会再栈溢出。</p>
 *
 * <p><b>线程语义</b>：守卫按线程隔离（{@link ThreadLocal}）—— 搜索只在触发线程内同步进行，
 * 不同线程（区块加载线程 / 主线程）互不干扰，也不会误伤并发。</p>
 *
 * <p><b>复位保证</b>：{@link #enter()} / {@link #exit()} 必须成对出现，且 {@link #exit()} 要放在
 * {@code finally} 中 —— 搜索中途抛异常时守卫也必须复位，否则该线程会永久「卡在搜索中」，
 * 之后所有连接判定都会失效。使用方见 {@code mixin/exporter/AbstractExporterBlockEntityMixin}。</p>
 */
public final class RsccSearchGuard {
    /** 当前线程是否正在做邻块搜索（用显式布尔值而非 remove()，避免后续 get() 反复初始化）。 */
    private static final ThreadLocal<Boolean> IN_SEARCH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RsccSearchGuard() {
    }

    /** 当前线程是否已处于一次搜索中。 */
    public static boolean isSearching() {
        return IN_SEARCH.get();
    }

    /** 进入搜索（必须在 try 之前调用）。 */
    public static void enter() {
        IN_SEARCH.set(Boolean.TRUE);
    }

    /** 退出搜索（必须放在 finally 中，异常路径也要复位）。 */
    public static void exit() {
        IN_SEARCH.set(Boolean.FALSE);
    }
}
