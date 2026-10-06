package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「拆方块时内容物去向」策略的持久化载体。
 * <p><b>存在哪里 / 为什么</b>：存放在<b>主世界（overworld）的 {@code DimensionDataStorage}</b>
 * 作为 {@link SavedData}（数据名 {@code rs_create_compat_block_content}）。选它而不是
 * ForgeConfig 的原因是：①用户要求「按世界保存、读档后仍生效」，SavedData 与存档同生共死、
 * 随存档读写，天然满足；②这是一个全局（非维度级）行为开关，存主世界即可覆盖所有维度；
 * ③读取无副作用、无网络开销。</p>
 * <p>默认档位为 {@link BlockContentMode#DROP}（与改动前的「掉落」行为一致）。</p>
 */
public final class BlockContentPolicy extends SavedData {
    /** 存档内的数据名。 */
    private static final String DATA_NAME = "rs_create_compat_block_content";
    private static final String TAG_MODE = "mode";

    /** 默认档位：掉落（最小惊讶，保持既有行为）。 */
    private static final BlockContentMode DEFAULT_MODE = BlockContentMode.DROP;

    private BlockContentMode mode = DEFAULT_MODE;

    /** 取（或首次创建）主世界里的策略数据。 */
    public static BlockContentPolicy get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BlockContentPolicy::new, BlockContentPolicy::load),
            DATA_NAME
        );
    }

    private static BlockContentPolicy load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final BlockContentPolicy data = new BlockContentPolicy();
        final BlockContentMode parsed = BlockContentMode.byId(tag.getString(TAG_MODE));
        if (parsed != null) {
            data.mode = parsed;
        }
        return data;
    }

    /**
     * 读取当前档位；只在服务端有效（客户端 / 取不到服务器时回落到默认档）。
     * 绝不在客户端缓存，避免单机 / 联机下客户端读到过期值。
     */
    public static BlockContentMode mode(final Level level) {
        if (level instanceof ServerLevel server) {
            final MinecraftServer mc = server.getServer();
            if (mc != null) {
                return get(mc).mode;
            }
        }
        return DEFAULT_MODE;
    }

    public BlockContentMode getMode() {
        return mode;
    }

    /** 设置档位（幂等；变化时标记脏数据以便落盘）。 */
    public void setMode(final BlockContentMode newMode) {
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
