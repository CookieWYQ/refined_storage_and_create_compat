package cretae.cookiewyq.rs_create_compat.compat.crafter;

import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Create 特化路径的日志出口：统一前缀 {@code [rscc-createcraft]}，<b>只在状态变化时记录</b>，
 * 且同类事件按 (level, 位置, 事件) 做节流，避免「每次迭代两条日志」刷屏。
 *
 * <p>反例说明：这个类存在的意义就是「不刷屏」。RS 的 {@code ExternalTaskPattern} 每次迭代
 * 至少调用两次 sink（SIMULATE + EXECUTE），如果一个正常的合成过程在本模组里每迭代打日志，
 * 那么用户的日志文件会被成百上千行重复内容淹没——这属于不可接受的交付。</p>
 */
public final class CreateCraftLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("rscc-createcraft");
    /** 同类事件的最小间隔（tick）。 */
    private static final long THROTTLE_TICKS = 200L;

    /** 节流表：key = level + 位置 + 事件名，value = 上次记录的 gameTime。 */
    private static final Map<String, Long> LAST_LOGGED = new HashMap<>();

    private CreateCraftLog() {
    }

    /** 节流记录（状态变化才调用）。 */
    public static void throttled(final Level level,
                                 final BlockPos pos,
                                 final String event,
                                 final String message,
                                 final Object... args) {
        if (level == null) {
            return;
        }
        // 第 122 轮（用户实机反馈）：开发日志总开关（Config.devLogs 镜像 → 运行时唯一判据
        // RsccAssemblyDebug.isEnabled()）关掉时，这条**周期性**节流日志也必须静默。
        // 否则「关掉开发日志选项」对 Create 特化路径无效 —— 用户实测到的「日志关不掉」里
        // 就包含这一族。一次性 warn() 不在此列（那是「不该出现的情况」，必须留证据）。
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final long now = level.getGameTime();
        final ResourceKey<Level> dimension = level.dimension();
        final String key = dimension.location() + "|" + pos.asLong() + "|" + event;
        final Long last = LAST_LOGGED.get(key);
        if (last != null && now - last < THROTTLE_TICKS) {
            return;
        }
        LAST_LOGGED.put(key, now);
        LOGGER.info("[rscc-createcraft] " + message, args);
    }

    /** 一次性（不节流）的重要事件，例如「配方匹配失败但没有退回原语义」这类不该出现的情况。 */
    public static void warn(final String message, final Object... args) {
        LOGGER.warn("[rscc-createcraft] " + message, args);
    }
}
