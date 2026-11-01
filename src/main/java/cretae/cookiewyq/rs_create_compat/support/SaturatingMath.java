package cretae.cookiewyq.rs_create_compat.support;

/**
 * <b>饱和（clamp）算术</b>：结果超出 {@code long} 表示范围时停在边界值，而不是回绕成负数。
 *
 * <p>为什么需要它：本模组里有一批「容量 / 剩余量」的读数天然可以极大 ——
 * 例如 RS 的无限容量存储对外报告的剩余量是 {@link RsccSharedCache} 里的
 * {@code UNLIMITED_REMAINING = Long.MAX_VALUE / 4}。把这种读数<b>逐个相加</b>时，
 * 只要池子里有 5 个以上无限盘，和就会溢出成负数，
 * 于是「池子还能收多少件 &gt; 0」这类判断全部翻转成「池子满了」，
 * 本该搬运的中间产物一件都不搬（故障表现为「机器无故不工作」，且不会打印任何异常）。
 *
 * <p>第 119 轮修：{@code RsccSharedCache#poolFreeSpace / #poolStored / #poolContents}
 * 全部改走本类，不再裸 {@code +=}。
 *
 * <p><b>只处理非负输入</b>（容量 / 件数 / mB 在本模组里都是非负量）：
 * 负输入一律按 0 处理 —— 负数在这里只可能来自上游已经出错的读数，
 * 把它当 0 比把它传播进后续判断更安全。
 */
public final class SaturatingMath {

    private SaturatingMath() {
    }

    /** 非负饱和加法：任意 {@code a, b >= 0} 都不会回绕；负输入按 0 计。 */
    public static long add(final long a, final long b) {
        final long left = Math.max(0L, a);
        final long right = Math.max(0L, b);
        // 用减法判溢出（left + right > MAX  <=>  left > MAX - right），
        // 这个形式在 left / right 都是非负时不依赖加法本身，因此不会自己先回绕。
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
