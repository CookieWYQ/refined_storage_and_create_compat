package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「序列装配缺料处置策略」的持久化载体（服务端权威）。
 *
 * <p><b>存在哪里 / 为什么</b>：与 {@link RsccSupplyPolicy} 完全同一套做法 —— 存放在<b>主世界
 * （overworld）的 {@code DimensionDataStorage}</b> 作为 {@link SavedData}（数据名
 * {@code rs_create_compat_shortage_mode}）。理由是用户要求「开关持久化、读档仍生效」，
 * SavedData 与存档同生共死、随存档读写，天然满足；读取无副作用、无网络开销。</p>
 *
 * <p><b>默认档位</b>为 {@link Mode#SUSPEND} —— 与既有行为<b>逐字一致</b>（缺料持续到阈值就挂起，
 * 把执行器让给后面的任务；用户原话「缺少原料的话呢，给我挂起以防堵塞后面的任务」）。
 * 新加的 {@link Mode#WAIT} 是纯 opt-in：旧存档没有该数据时同样落到默认档，
 * 因此「不引入任何行为回归」。</p>
 */
public final class RsccShortagePolicy extends SavedData {
    /** 存档内的数据名。 */
    private static final String DATA_NAME = "rs_create_compat_shortage_mode";
    private static final String TAG_MODE = "mode";

    /**
     * 缺料处置二档。
     *
     * <ul>
     *     <li>{@link #SUSPEND} <b>缺料即挂起</b>（默认）：原因连续超过阈值后把任务挂起，
     *     不再占用执行器与原料，从而<b>不堵塞后面的任务</b>；恢复只由玩家点「继续」触发
     *     （与既有看门狗语义完全一致，本档不引入任何新路径）。</li>
     *     <li>{@link #WAIT} <b>一直等到有料</b>：缺料<b>绝不自动挂起</b>，任务原地保留，
     *     等网络里补上原料后自动继续加工。它只压制「缺料」这一种原因的自动挂起；
     *     执行器离线 / 无进展这两类<b>照旧挂起</b>，否则一个真的掉线的任务会永远卡住。</li>
     * </ul>
     */
    public enum Mode {
        SUSPEND("suspend"),
        WAIT("wait");

        private final String id;

        Mode(final String id) {
            this.id = id;
        }

        /** 指令取值 / 反馈用的稳定 id（小写）。 */
        public String id() {
            return id;
        }

        /** 语言键（message.rs_create_compat.shortage.mode.<id>）。 */
        public String langKey() {
            return "message.rs_create_compat.shortage.mode." + id;
        }

        /** 按指令取值解析；大小写不敏感，无法识别返回 null。 */
        @org.jetbrains.annotations.Nullable
        public static Mode byId(@org.jetbrains.annotations.Nullable final String raw) {
            if (raw == null) {
                return null;
            }
            for (final Mode mode : values()) {
                if (mode.id.equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** 默认档位：缺料即挂起（= 既有行为，用户硬要求「以防堵塞后面的任务」）。 */
    private static final Mode DEFAULT_MODE = Mode.SUSPEND;

    private Mode mode = DEFAULT_MODE;

    /** 取（或首次创建）主世界里的策略数据。 */
    public static RsccShortagePolicy get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RsccShortagePolicy::new, RsccShortagePolicy::load),
            DATA_NAME
        );
    }

    private static RsccShortagePolicy load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final RsccShortagePolicy data = new RsccShortagePolicy();
        final Mode parsed = Mode.byId(tag.getString(TAG_MODE));
        if (parsed != null) {
            data.mode = parsed;
        }
        return data;
    }

    /**
     * 读取当前档位；只在服务端有效（客户端 / 取不到服务器时回落到默认档）。
     * <p>与 {@link RsccSupplyPolicy#strategy(Level)} 同一口径：绝不在客户端缓存，
     * 避免单机 / 联机下客户端读到过期值。</p>
     */
    public static Mode mode(final Level level) {
        if (level instanceof ServerLevel server) {
            final MinecraftServer mc = server.getServer();
            if (mc != null) {
                return get(mc).mode;
            }
        }
        return DEFAULT_MODE;
    }

    public Mode getMode() {
        return mode;
    }

    /** 设置档位（幂等；变化时标记脏数据以便落盘）。 */
    public void setMode(final Mode newMode) {
        if (newMode != null && newMode != mode) {
            mode = newMode;
            setDirty();
        }
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putString(TAG_MODE, mode.id());
        return tag;
    }
}
