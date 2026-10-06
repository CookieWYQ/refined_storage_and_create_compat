package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

/**
 * <b>「优先复用网络中的中间产物」开关</b>（服务端权威，存档级持久化）。
 *
 * <h2>用户原话</h2>
 * <p><i>「再添加一个指令用于开关：当网络中具有某序列装配的中间产物、然后现在又要开始这个序列装配的时候，
 * 是否优先使用它的中间产物，而不是从头开始合成」</i></p>
 *
 * <h2>语义</h2>
 * <p>开启后，某条序列装配要开新件时：若网络里<b>已经有该配方某一步的中间产物</b>
 * （{@code intermediate:<配方>:<步>} 类别里的在网存量），就<b>优先把它取来当这一件的起点</b>，
 * 跳掉前面那几步已经做过的工序，而不是从起步原料重新走一遍。</p>
 *
 * <h2>为什么默认「关」</h2>
 * <p>与 {@link RsccRefillPolicy} 同一套判断标准：默认档必须与<b>变更前的行为逐字一致</b>。
 * 复用中间产物会改变产线的用料曲线（少走前面的步 ⇒ 少耗起步原料、多耗中间产物），
 * 这是玩家应当明确选择的行为，因此做成纯 opt-in：任何旧存档 / 没敲过指令的玩家都不受影响。</p>
 *
 * <h2>存在哪里</h2>
 * <p>与 {@link RsccShortagePolicy} / {@link RsccRefillPolicy} 完全同一套做法：主世界
 * {@code DimensionDataStorage} 里的 {@link SavedData}（数据名
 * {@code rs_create_compat_intermediate_reuse}），与存档同生共死，读档仍生效。</p>
 */
public final class RsccIntermediateReusePolicy extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 与 {@link RsccDiag#ANCHOR_PREFIX} 同款锚点前缀（不敲指令也能在日志里核对开关状态）。 */
    private static final String ANCHOR = "[rscc]";
    private static final String DATA_NAME = "rs_create_compat_intermediate_reuse";
    private static final String TAG_ENABLED = "reuse_intermediates";

    /** 默认档位：关（= 变更前行为逐字一致，纯 opt-in）。 */
    private static final boolean DEFAULT_ENABLED = false;

    private boolean reuseIntermediates = DEFAULT_ENABLED;

    /** 取（或首次创建）主世界里的开关数据。 */
    public static RsccIntermediateReusePolicy get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RsccIntermediateReusePolicy::new, RsccIntermediateReusePolicy::load),
            DATA_NAME
        );
    }

    private static RsccIntermediateReusePolicy load(final CompoundTag tag,
                                                    final HolderLookup.Provider registries) {
        final RsccIntermediateReusePolicy data = new RsccIntermediateReusePolicy();
        // 旧存档没有该键时保持默认档（= 关），因此读档不会「悄悄打开」新行为。
        if (tag.contains(TAG_ENABLED)) {
            data.reuseIntermediates = tag.getBoolean(TAG_ENABLED);
        }
        return data;
    }

    /** 读取当前开关；只在服务端有效（客户端 / 取不到服务器时回落到默认档）。 */
    public static boolean reuseIntermediates(final Level level) {
        if (level instanceof ServerLevel server) {
            final MinecraftServer mc = server.getServer();
            if (mc != null) {
                return get(mc).reuseIntermediates;
            }
        }
        return DEFAULT_ENABLED;
    }

    public boolean isReuseIntermediates() {
        return reuseIntermediates;
    }

    /** 设置开关（幂等；变化时打一条锚点行并标记脏数据以便落盘）。 */
    public void setReuseIntermediates(final boolean enabled) {
        if (enabled != reuseIntermediates) {
            reuseIntermediates = enabled;
            setDirty();
            LOGGER.info("{} intermediate_reuse={} (source=command)", ANCHOR, enabled ? "on" : "off");
        }
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putBoolean(TAG_ENABLED, reuseIntermediates);
        return tag;
    }
}
