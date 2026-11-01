package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;

/**
 * 全模组<b>唯一</b>的「速度 / 堆叠升级 → 节拍 / 批量」换算工具（用户要求：所有带速度限制的物品，
 * 充电器也包含在内，逻辑必须统一）。
 *
 * <h2>为什么必须有它（而不是各机器各写一套）</h2>
 * <p>改造前的口径分三套、且各机器自己写公式（详见 {@code docs/REQUESTER_ORDER_AND_PACER_DESIGN.md} §4.1）：</p>
 * <ul>
 *     <li><b>指数</b>：{@code Config.base << (速度数 + 堆叠数*3)} —— 范围充电器、定量保持器、高级定量保持器；</li>
 *     <li><b>线性</b>：{@code base * (1 + 堆叠数)} —— 归流缓存仓吞吐 / 装填器每格容量；</li>
 *     <li><b>倒数</b>：{@code base / ((1 + 速度数) * 半径档)} —— 归流缓存仓扫描间隔（全工程唯一一条）。</li>
 * </ul>
 * <p>三套写法各自都藏着同一个坑：<b>升级「个数」没有单点上限</b>。菜单侧 {@link UpgradeSlot} 只保证
 * 「玩家手动插」每槽 1 个，但漏斗 / 管道往一格塞 64 个是能绕过去的，于是：</p>
 * <ul>
 *     <li>{@code 500 << 64} 在 Java 里被移位掩码成 {@code 500 << (64 & 31) = 500 << 0} —— <b>比插 6 个还慢</b>；</li>
 *     <li>{@code 1_000_000 << 18} 溢出成<b>负数</b> —— 充电速率变负 ⇒ {@code Math.min(负数, 电量)} 还是负数 ⇒ <b>完全不充电</b>；</li>
 *     <li>{@code base / (1 + 负数)} 之类的形态可能除零 / 取负。</li>
 * </ul>
 *
 * <h2>4 条不变量（本类的全部契约；改这里之前先看这 4 条）</h2>
 * <table border="1">
 *     <caption>不变量</caption>
 *     <tr><th>编号</th><th>含义</th><th>本类怎么保证</th></tr>
 *     <tr>
 *         <td><b>I1 永不非正</b></td>
 *         <td>任何输入下：间隔 ≥ 1 tick、批量 ≥ 1、速率 ≥ 1</td>
 *         <td>{@link #intervalTicks} 外层再夹一次 {@code Math.max(1, …)}；
 *             {@link #rateSaturating} / {@link #batchSaturating} 先把 base 夹到 ≥ 1</td>
 *     </tr>
 *     <tr>
 *         <td><b>I2 永不除零</b></td>
 *         <td>公式里不出现「除数可能是 0」的除法</td>
 *         <td>本类<b>一条除法都没有</b>：间隔型走右移 {@link #intervalTicks}，批量型走乘法
 *             {@link #batchSaturating}（调用方若确有除法，必须先自己夹分母 ≥ 1）</td>
 *     </tr>
 *     <tr>
 *         <td><b>I3 永不溢出 / 回绕</b></td>
 *         <td>{@code <<} 与 {@code *} 到顶就停，绝不变成负数</td>
 *         <td>{@link #shiftSaturating} 每次移位前先判 {@code result > Integer.MAX_VALUE >> 1}；
 *             {@link #multiplySaturating} 走 {@link Math#multiplyExact}，溢出即返回 {@link Long#MAX_VALUE}</td>
 *     </tr>
 *     <tr>
 *         <td><b>I4 上限单点</b></td>
 *         <td>「最多几级」只有一处定义；任何上游（漏斗 / NBT / 创造模式）塞多少都被夹进 {@code [0, MAX_LEVEL]}</td>
 *         <td>{@link #MAX_LEVEL} 直接取 {@link UpgradeSlot#MAX_PER_UPGRADE}（= 6）；
 *             {@link #level(int)} 是唯一的入口收敛点，其余方法一律先过它</td>
 *     </tr>
 * </table>
 *
 * <h2>RS 原版的 ticker 为什么不能用（用户判断是对的）</h2>
 * <p>RS 2.0.9 的 {@code UpgradeContainer.notifyListener()} 是
 * {@code workTickRate = defaultWorkTickRate - 速度升级个数 * 2}，出厂 {@code defaultWorkTickRate = 9}
 * （字节码 {@code bipush 9}）。本工程允许 6 个速度升级 ⇒ n=5/6 时算出 {@code -1} / {@code -3}，
 * <b>负节拍</b>直接违反 I1。因此节拍一律走本类，绝不复用 RS 的容器。</p>
 *
 * <p><b>注意</b>：{@link #MAX_LEVEL} 是编译期常量（{@code static final int} 字面量），javac 会内联，
 * 因此引用它<b>不会</b>在运行时加载 {@link UpgradeSlot}（专用服务端也安全）。</p>
 */
public final class RsccRateLimiter {
    /**
     * 每级「堆叠升级」折几个速度升级 —— 保留工程既有口径（范围充电器 / 两个定量保持器都是 ×3）。
     * <p>为什么不改成「堆叠只影响批量」：那会把范围充电器的满堆叠速率从 131,072,000 FE/t
     * 砍到 32,000 FE/t（约 4,000 倍削弱），属于灾难性改动（见设计文档 §5.2 第 4 条）。</p>
     */
    public static final int STACK_AS_SPEED_WEIGHT = 3;

    /** I4：级数上限 = 插件槽数（与 {@link UpgradeSlot#MAX_PER_UPGRADE} 同源，绝不各写一份 6）。 */
    public static final int MAX_LEVEL = UpgradeSlot.MAX_PER_UPGRADE;

    /** power 上限（= 6 级速度 + 6 级堆叠 ×3），仅作溢出防线；正常玩法到不了。 */
    public static final int MAX_POWER = MAX_LEVEL + MAX_LEVEL * STACK_AS_SPEED_WEIGHT;

    /**
     * 「满级 = 不限」的哨兵（装填器用）。
     * <p>为什么不删掉这个特判、直接算 {@code 8 << 6 = 512}：那会把装填器满级从「瞬间拉完」
     * 变成「每 tick 512 个」，是语义级削弱（见设计文档 §4.3 C 最后一行）。</p>
     */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private RsccRateLimiter() {
    }

    /** I4：任何来源的升级个数 → {@code [0, MAX_LEVEL]}。负数、64、{@code Integer.MIN_VALUE} 全部收敛到这里。 */
    public static int level(final int rawUpgrades) {
        return Math.clamp(rawUpgrades, 0, MAX_LEVEL);
    }

    /** 等效速度级数：{@code level(速度) + level(堆叠) * 3}，再夹到 {@code [0, MAX_POWER]}。 */
    public static int power(final int speedUpgrades, final int stackUpgrades) {
        return Math.min(level(speedUpgrades) + level(stackUpgrades) * STACK_AS_SPEED_WEIGHT, MAX_POWER);
    }

    /**
     * 形状①「指数速率」：{@code base << power}，饱和到 {@link Integer#MAX_VALUE}（I1 / I3）。
     * <p>用于范围充电器充电速率、定量保持器销毁速率、装填器拉取。</p>
     */
    public static int rateSaturating(final int basePerTick, final int speedUpgrades, final int stackUpgrades) {
        return shiftSaturating(Math.max(1, basePerTick), power(speedUpgrades, stackUpgrades));
    }

    /** 形状②「线性批量」：{@code base * (1 + level(堆叠))}（I1 / I3，与 RS 原版堆叠升级 ×64 语义一致）。 */
    public static long batchSaturating(final long baseBatch, final int stackUpgrades) {
        return multiplySaturating(Math.max(1L, baseBatch), 1L + level(stackUpgrades));
    }

    /**
     * 形状③「指数间隔」：{@code max(1, base >> level(速度))}（I1 / I2）。
     * <p>为什么不用 {@code base / (1 + n)}：那是全工程唯一一条倒数曲线，<b>永远到不了 1 tick</b>
     * （{@code 20/7 = 2}，「满级了还每 2 tick 才扫一次」），而且引入了可能为 0 的除数。
     * 改成右移后既没有除法，满级又正好是 1 tick。</p>
     */
    public static int intervalTicks(final int baseInterval, final int speedUpgrades) {
        return Math.max(1, Math.max(1, baseInterval) >> level(speedUpgrades));
    }

    /**
     * 饱和左移：{@code base << power}，越界即封顶，绝不为负。
     * <p>每次移位前先判 {@code result > Integer.MAX_VALUE >> 1}：只要再加一次移位就会越界，
     * 直接返回 {@link Integer#MAX_VALUE}（若真移下去，{@code MAX_VALUE << 1} 会回绕成 -2）。</p>
     */
    public static int shiftSaturating(final int base, final int power) {
        int result = base;
        for (int i = 0; i < power; i++) {
            if (result > Integer.MAX_VALUE >> 1) {
                return Integer.MAX_VALUE;
            }
            result <<= 1;
        }
        return result;
    }

    /** 饱和乘法（long）：溢出即 {@link Long#MAX_VALUE}，绝不回绕成负数。 */
    public static long multiplySaturating(final long a, final long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 「满级 = 不限」的统一判定（装填器用）：任何 ≥ 6 的输入（含漏斗塞的 64）都算满级。 */
    public static boolean isMaxed(final int rawSpeedUpgrades) {
        return level(rawSpeedUpgrades) >= MAX_LEVEL;
    }
}
