package cretae.cookiewyq.rs_create_compat.support;

/**
 * 单元样板库的<b>合计体积预算</b>（第 118 轮，独立只读复核遗留项 M1：「库只有条数上限、
 * 没有体积上限（1024 格理论 ≈2.5 MB 线上）」）。
 *
 * <h2>为什么「条数上限」还不够</h2>
 * <p>第 116 轮给库加了 1024 格硬上限，第 116～117 轮又给所有「客户端给数据 → 落盘」的写入口
 * 接上了单条尺（{@link MarkerBudget#MAX_ENTRY_BYTES}，线上意图约 2 KB / 条）。
 * 两个上限各自成立，但<b>乘起来仍然很大</b>：1024 格 × 每格接近单条上限 ≈ 2 MB 线上。</p>
 * <p>这份数据的三处代价：① 进方块实体 NBT <b>落盘</b>；② 打开终端界面 / 区块同步时
 * <b>整库回发</b>；③ 每次「体积预算」判断都要遍历全库（本类只管数据量，不管判断开销）。
 * 因此需要一道<b>合计</b>预算 —— 与第 114 轮区域标记「整表预算」、第 116 轮 JEI 导入
 * 「整包预算」是同一条推理，只是这一层管的是「库的存量」而不是「一次写入」。</p>
 *
 * <h2>口径：{@code sizeInBytes()} 是 JVM 堆估算，不是线上字节</h2>
 * <p>与 {@link MarkerBudget} 完全同一把尺（{@link net.minecraft.nbt.Tag#sizeInBytes()}），
 * {@link #WIRE_RATIO} 也取同一个经验值 8（自检 {@code selfcheck_round118_unit_library_bytes.py}
 * 锁定「两处倍率必须一致」，防止口径漂移）。</p>
 * <p>⚠ 对<b>字符串主导</b>的负载（单元样板的机器 id / 配方类型 / 自定义名 / 候选组列表）
 * 堆与线上几乎 1:1 ⇒ 本类的 {@link #MAX_LIBRARY_WIRE_BYTES} 在那种负载下是<b>下界</b>而非上界
 * （第 117 轮复核对 {@link MarkerBudget#WIRE_RATIO} 的同一条更正）。真正的保证是
 * 「按堆估算判定的一个确定上限」，不是「线上字节一定 ≤ 1 MB」。</p>
 *
 * <h2>量级（据此选定常量）</h2>
 * <ul>
 *     <li>一张普通单元样板（机器 id + 输入标记 + 候选组 + 产出）线上约 200–800 B
 *     ⇒ 1024 格全满约 <b>200–800 KB 线上</b>（≈1.6–6.4 MB 堆估算）；</li>
 *     <li>{@link #MAX_LIBRARY_WIRE_BYTES} = <b>1 MiB 线上意图</b>（{@link #MAX_LIBRARY_HEAP_BYTES}
 *     = 8 MiB 堆估算）：对「1024 格全是普通样板」留了 1.3～5 倍余量，<b>正常玩法（几页到十几页）远不会碰到</b>；</li>
 *     <li>真正会被挡住的只有「整库平均每格都接近单条上限」的病态配置 ——
 *     那正是本条要挡的（1024 × 2 KB ≈ 2 MB 线上）。</li>
 * </ul>
 *
 * <h2>纪律（与库容量上限、阻塞名单上限完全一致）</h2>
 * <ul>
 *     <li><b>只拦「增长」</b>：读档不判体积（超限旧库原样读回）、取出 / 存入执行仓（变小）永远允许、
 *     删除永远允许；</li>
 *     <li>判不了（空栈 / 无注册表 / 保存异常 ⇒ 字节数 0）一律<b>不拒</b>，绝不误伤正常路径；</li>
 *     <li>拒绝是<b>原子</b>的：一次写入要么整张写进去、要么原样不动，不存在「写了一半」。</li>
 * </ul>
 *
 * <h2>为什么是纯 JDK</h2>
 * <p>本类不 import 任何 Minecraft 类型 ⇒ 自检可以把它 {@code javac} 编译后<b>真跑</b>
 * （含 {@code Long.MAX_VALUE} 溢出反例），而不是只做源码锚点。
 * 与第 117 轮的 {@link CollectionBlockedLimits} 同一套路。</p>
 */
public final class UnitLibraryLimits {
    /**
     * 堆估算 → NBT 线上字节的经验倍率（<b>必须与 {@link MarkerBudget#WIRE_RATIO} 同值</b>）。
     * <p>纯 JDK 类不能引用 {@link MarkerBudget}（那边依赖 MC 类型），因此这里独立定义一份，
     * 由自检锁定两者一致 —— 允许「两份字面量」，不允许「两个口径」。</p>
     */
    public static final int WIRE_RATIO = 8;

    /** 整库合计体积上限的<b>线上字节意图</b> = 1 MiB（文案 / 日志 / 文档用）。 */
    public static final int MAX_LIBRARY_WIRE_BYTES = 1024 * 1024;

    /** 同一个上限换算成 KB（给玩家看的数字；1 MiB = 1024 KB）。 */
    public static final int MAX_LIBRARY_WIRE_KB = MAX_LIBRARY_WIRE_BYTES / 1024;

    /** 判据实际使用的<b>堆估算</b>上限 = {@link #MAX_LIBRARY_WIRE_BYTES} × {@link #WIRE_RATIO}。 */
    public static final long MAX_LIBRARY_HEAP_BYTES = (long) MAX_LIBRARY_WIRE_BYTES * WIRE_RATIO;

    private UnitLibraryLimits() {
    }

    /**
     * 在「已用 {@code usedHeap}」的基础上，还能不能再放进 {@code addHeap}。
     *
     * <p><b>为什么写成减法而不是 {@code used + add <= MAX}</b>：{@code used} 与 {@code add}
     * 都是 long，直接相加在极端值（{@code Long.MAX_VALUE}）会溢出成负数 ⇒ 判据返回 true
     * （「放得下」）反而放行。减法形式下 {@code MAX - add} 不会溢出（{@code add > MAX} 时先被
     * 第一个分支挡掉），{@code used <= MAX - add} 也就不会误判。自检有一条
     * {@code Long.MAX_VALUE} 的反例专门盯这个。</p>
     *
     * @param usedHeap 已用体积（堆估算；{@code <= 0} 视为空库）
     * @param addHeap  本次要新增的体积（堆估算；{@code <= 0} 视为不增长 ⇒ 永远允许）
     */
    public static boolean fits(final long usedHeap, final long addHeap) {
        if (addHeap <= 0) {
            return true; // 不增长（替换 / 取出 / 判不了）永远放行
        }
        if (addHeap > MAX_LIBRARY_HEAP_BYTES) {
            return false; // 单张就超过整库预算：无论库多空都放不下
        }
        if (usedHeap <= 0) {
            return true; // 空库：addHeap 已在上面判过不超过上限
        }
        return usedHeap <= MAX_LIBRARY_HEAP_BYTES - addHeap;
    }

    /** 还剩多少（堆估算）；{@code 0} = 已满。负数 / 空库入参按「空库」处理。 */
    public static long remaining(final long usedHeap) {
        if (usedHeap <= 0) {
            return MAX_LIBRARY_HEAP_BYTES;
        }
        return Math.max(0L, MAX_LIBRARY_HEAP_BYTES - usedHeap);
    }

    /** 已用体积换成「线上字节意图」的 KB（给日志 / 诊断用；向上取整，永远不显示 0 KB 的非空库）。 */
    public static int usedWireKb(final long usedHeap) {
        if (usedHeap <= 0) {
            return 0;
        }
        final long wire = (usedHeap + WIRE_RATIO - 1) / WIRE_RATIO;
        return (int) Math.max(1L, (wire + 1023) / 1024);
    }
}
