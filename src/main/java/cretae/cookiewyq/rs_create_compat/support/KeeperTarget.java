package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 定量保持器「生效目标量」的<b>唯一实现</b>（基础版与高级版共用，保证两边口径逐字一致）。
 *
 * <h2>为什么需要它（用户要求）</h2>
 * <p>玩家标注的数量只是「期望上限」，真正要维持的数量必须被网络可达量钳制，否则会出现
 * 「标 500 个齿轮、网络里只有 400 个、又造不出更多」时仍按 500 去要货 —— 表现为反复报缺 /
 * 空转。本类把钳制规则收敛到一处：</p>
 * <ul>
 *     <li><b>下限 = 0</b>：设定值 ≤ 0 视为「未标记」，该项不参与维持 / 合成 / 销毁 / 缺料统计；</li>
 *     <li><b>上限 = min(设定值, 可达上限)</b>：
 *         资源<b>可自动合成</b>（终端里有输出样板能产出它）⇒ 不设上限（可以靠合成补到设定值）；
 *         资源<b>不可自动合成</b> ⇒ 可达上限 = 网络当前持有量（最多就是现在这么多）。</li>
 * </ul>
 *
 * <p>于是「标 500、网络 400、不可合成」⇒ 生效目标 = 400：存量恰好等于目标，既不报缺也不销毁。
 * 与「过量销毁」开关、「多台共存仲裁」、「按缺口补合成」均不冲突 —— 它们都只看这里算出的生效目标。</p>
 */
public final class KeeperTarget {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 钳制诊断日志的节流（tick，约 10 秒）：网络存量在设定值附近抖动时也不刷屏。 */
    private static final int CLAMP_LOG_COOLDOWN_TICKS = 200;

    private KeeperTarget() {
    }

    /** 是否「已标记」：设定值 &gt; 0 才算标记（0 / 负数 = 未标记，零维持 / 零合成 / 零销毁 / 不计入缺料）。 */
    public static boolean isMarked(final long setTarget) {
        return setTarget > 0L;
    }

    /** 该资源是否「可自动合成」：无终端 / 无资源键 / 没有输出样板一律按不可合成处理（保守，绝不乐观放行）。 */
    public static boolean isCraftable(@Nullable final AutocraftingNetworkComponent autocrafting,
                                      @Nullable final ResourceKey resource) {
        return autocrafting != null && resource != null && !autocrafting.getPatternsByOutput(resource).isEmpty();
    }

    /**
     * 生效目标量的<b>唯一实现</b>：下限 0 + 上限钳制。
     *
     * @param setTarget 玩家设定值（≤ 0 = 未标记）
     * @param stored    网络当前持有量
     * @param craftable 该资源是否可自动合成
     * @return 生效目标量（≤ 0 = 未标记；不可自动合成时恒 ≤ {@code stored}，即「最多就是现在这么多」）
     */
    public static long effectiveTarget(final long setTarget, final long stored, final boolean craftable) {
        if (setTarget <= 0L) {
            return 0L; // 下限 0：≤ 0 视为未标记
        }
        // 可合成 ⇒ 不设上限（靠合成补到设定值）；不可合成 ⇒ 上限 = 网络当前持有量
        final long reachableCap = craftable ? Long.MAX_VALUE : Math.max(0L, stored);
        return Math.min(setTarget, reachableCap);
    }

    /**
     * 一次「生效目标被钳制」的观测器（基础版一份 / 高级版每槽一份）。
     * <p>它只负责「在钳制状态真正需要提醒时打一条 INFO」，不做任何搬运与判定：
     * 生效目标本身由 {@link #effectiveTarget} 给出。之所以要节流 + 只记进入钳制，
     * 是因为网络存量在设定值附近抖动时钳制会在开关之间来回跳，逐 tick 打日志会刷屏。</p>
     */
    public static final class ClampObservation {
        /** 上一次已提醒的「被钳制的设定值」（-1 = 当前未处于钳制）。 */
        private long lastSet = -1L;
        private int cooldown;

        /**
         * 观测本 tick 的生效目标。
         *
         * @param who       诊断标签（机器名 + 坐标 [+ 槽位]）
         * @param setTarget 玩家设定值
         * @param stored    网络当前持有量
         * @param effective 生效目标量（{@link #effectiveTarget} 的结果）
         */
        public void observe(final String who, final long setTarget, final long stored, final long effective) {
            if (cooldown > 0) {
                cooldown--;
            }
            // 只有「已标记但生效目标 < 设定值」才是钳制（等价于：不可自动合成且网络存量不足）
            if (!isMarked(setTarget) || effective >= setTarget) {
                lastSet = -1L;
                return;
            }
            if (setTarget == lastSet || cooldown > 0) {
                return; // 已经提醒过或仍在节流窗口内
            }
            lastSet = setTarget;
            cooldown = CLAMP_LOG_COOLDOWN_TICKS;
            LOGGER.info("{} {} 生效目标被钳制：设定 {}，网络现有 {}，不可自动合成 ⇒ 当前维持 {}（设定值未被改写）",
                KeeperOverflow.LOG_PREFIX, who, setTarget, stored, effective);
        }
    }
}
