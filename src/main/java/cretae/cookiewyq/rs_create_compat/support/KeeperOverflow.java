package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 定量保持器「过量销毁」的<b>唯一语义实现</b>（基础版与高级版共用，保证两边行为逐字一致）。
 *
 * <p><b>精确语义（用户要求）</b>：</p>
 * <ul>
 *     <li>开关<b>关闭</b> ⇒ 一个都不销毁（超出的部分原样留在网络 / 本机，可正常取出）；</li>
 *     <li>开关<b>打开</b> ⇒ 只销毁 {@code max(0, 存量 − 目标数量)} 这一部分，即
 *     <b>超出目标的那部分才销毁，绝不低于目标</b>；</li>
 *     <li>只动<b>被标记的那一种</b>资源键（同物品同组件 / 同流体同组件），绝不碰别的资源。</li>
 * </ul>
 *
 * <p>销毁是<b>有意的</b>（这就是用户要的功能），因此必须留下可诊断的日志；同时为了不刷屏，
 * 每次「过量事件」只在<b>开始</b>与<b>清完</b>时各写一条 INFO，中间的每一步写 DEBUG（含精确数量）。</p>
 */
public final class KeeperOverflow {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 日志前缀：与工程既有的 {@code [rscc-assembly]} 同一套口径，便于日志检索。 */
    public static final String LOG_PREFIX = "[rscc-keeper]";
    /** 销毁冷却的时间基准：游戏按 20 tick = 1 秒。 */
    private static final int TICKS_PER_SECOND = 20;

    private KeeperOverflow() {
    }

    /** 超出目标的数量（<= 0 表示没有过量，绝不销毁）。 */
    public static long excess(final long stored, final long target) {
        return Math.max(0L, stored - target);
    }

    /** 两次销毁之间的间隔（tick）：销毁速率越高间隔越短，至少 1 tick。 */
    public static int cooldownTicks(final int ratePerSecond) {
        return Math.max(1, TICKS_PER_SECOND / Math.max(1, ratePerSecond));
    }

    /** 单次销毁的批量：速率低于 1/秒时也至少逐个销毁，保证「一定会慢慢清完」。 */
    public static long batchSize(final int ratePerSecond) {
        return Math.max(1L, Math.max(1, ratePerSecond) / (long) TICKS_PER_SECOND);
    }

    /**
     * 本机侧「超量销毁」的精确销毁量 —— 唯一入口，基础版与高级版共用。
     *
     * <p>把「网络存量 + 本机缓冲没送进去的部分」当成<b>同一个池</b>：只销毁池里超出目标的那部分，
     * 而且只从本机剩余里扣（网络那边的超量由网络侧销毁负责）。因为销毁量被
     * {@code min(localLeft, max(0, pool − target))} 夹住，所以</p>
     * <ul>
     *     <li>开关关闭时返回 0（一个都不销毁）；</li>
     *     <li>构造上<b>不可能销毁到低于目标</b>：即使网络满了、缺口没填上，
     *     保留下来的本机余量会把池补回目标；</li>
     *     <li>只作用在调用方传进来的那一个资源键上（调用方只会传匹配标记的栈）。</li>
     * </ul>
     *
     * @param networkAfter 把能放的部分推进网络之后的网络存量
     * @param localLeft    本机缓冲里没送进网络的部分
     * @param target       目标数量
     */
    public static long localDestroyAmount(final long networkAfter, final long localLeft, final long target) {
        if (localLeft <= 0L) {
            return 0L;
        }
        final long pool = networkAfter + localLeft;
        return Math.min(localLeft, Math.max(0L, pool - target));
    }

    /**
     * 从 RS 网络精确销毁「超出目标」的部分：至多 {@code batch} 个，且不超过 {@code excess}。
     *
     * @return 实际销毁量（0 = 没销毁；网络里已没有该资源时为 0）
     */
    public static long destroyFromNetwork(@Nullable final StorageNetworkComponent storage,
                                          @Nullable final ResourceKey resource,
                                          final long excess, final long batch) {
        if (storage == null || resource == null || excess <= 0L) {
            return 0L;
        }
        final long want = Math.min(excess, Math.max(1L, batch));
        return storage.extract(resource, want, Action.EXECUTE, Actor.EMPTY);
    }

    /**
     * 一次「过量事件」的记账器（每个槽位一份，服务端权威）。
     * <p>它只负责「记录 + 打日志」，不做任何搬运：销毁数量全部由调用方精确给出，
     * 调用方保证 {@code amount <= excess}（见 {@link #destroyFromNetwork} 与
     * {@code batchSize} 的用法）。</p>
     */
    public static final class Episode {
        private long destroyed;
        private boolean active;
        @Nullable
        private String who;
        @Nullable
        private ResourceKey resource;
        private long target;

        /**
         * 记一笔销毁。
         *
         * @param who             诊断标签（机器名 + 坐标 + 槽位）
         * @param resource        被销毁的资源键（只可能是被标记的那一种）
         * @param target          目标数量
         * @param amount          本次销毁量（&lt;= 0 表示本次没销毁）
         * @param remainingExcess 本次之后仍超出目标的量（&lt;= 0 表示已清完）
         */
        public void record(final String who, final ResourceKey resource, final long target,
                           final long amount, final long remainingExcess) {
            if (amount > 0L) {
                if (!active) {
                    active = true;
                    this.who = who;
                    this.resource = resource;
                    this.target = target;
                    // 开发诊断（INFO）：受 devLogs 总开关控制。
                    // 实测这一族会「开始 / 清完」来回抖动（单会话 1658 条，峰值 12 条/秒），
                    // 属于典型的高频直出，不该在发布版默认刷屏。
                    if (RsccAssemblyDebug.isEnabled()) {
                        LOGGER.info("{} {} 开始销毁过量：{} 保留目标 {}，超出的部分将被销毁",
                            LOG_PREFIX, who, resource, target);
                    }
                }
                destroyed += amount;
                if (RsccAssemblyDebug.isEnabled()) {
                    LOGGER.debug("{} {} 销毁 {} x{}（仍超出 {}）", LOG_PREFIX, who, resource, amount, remainingExcess);
                }
            }
            if (active && remainingExcess <= 0L) {
                if (RsccAssemblyDebug.isEnabled()) {
                    LOGGER.info("{} {} 过量已清完：{} 共销毁 {}（目标 {}）",
                        LOG_PREFIX, who, resource, destroyed, target);
                }
                reset();
            }
        }

        /** 本次 tick 没有销毁（开关关闭 / 已达标 / 让位给同网络更权威的同伴）：把未收尾的事件收尾。 */
        public void idle() {
            if (active) {
                // 开发诊断（INFO）：受 devLogs 总开关控制（与上面两条同一族，见 record 的说明）。
                if (RsccAssemblyDebug.isEnabled()) {
                    LOGGER.info("{} {} 停止销毁过量：{} 本段共销毁 {}（目标 {}）",
                        LOG_PREFIX, who, resource, destroyed, target);
                }
            }
            reset();
        }

        /** 本段（自上次 {@link #reset()} 以来）累计销毁量（只读诊断用；不影响任何行为）。 */
        public long destroyedTotal() {
            return destroyed;
        }

        public void reset() {
            destroyed = 0L;
            active = false;
            who = null;
            resource = null;
            target = 0L;
        }
    }
}
