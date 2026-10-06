package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 「序列执行仓原料供应策略」的持久化载体。
 *
 * <p><b>存在哪里 / 为什么</b>：与 {@link BlockContentPolicy} 同一套做法 —— 存放在<b>主世界
 * （overworld）的 {@code DimensionDataStorage}</b> 作为 {@link SavedData}（数据名
 * {@code rs_create_compat_supply_strategy}）。理由是用户要求「指令切换、读档仍生效」，
 * SavedData 与存档同生共死、随存档读写，天然满足；读取无副作用、无网络开销。</p>
 *
 * <p><b>默认档位</b>为 {@link RsccSupplyStrategy#TARGET}（用户明确要求「默认调到不管怎么样
 * 一定要达到目标产物数量的那一个」）。旧存档没有该数据时同样落到默认档。</p>
 */
public final class RsccSupplyPolicy extends SavedData {
    /** 存档内的数据名。 */
    private static final String DATA_NAME = "rs_create_compat_supply_strategy";
    private static final String TAG_STRATEGY = "strategy";

    /** 默认档位：直到目标产物达标（用户要求）。 */
    private static final RsccSupplyStrategy DEFAULT_STRATEGY = RsccSupplyStrategy.TARGET;

    private RsccSupplyStrategy strategy = DEFAULT_STRATEGY;

    /** 取（或首次创建）主世界里的策略数据。 */
    public static RsccSupplyPolicy get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RsccSupplyPolicy::new, RsccSupplyPolicy::load),
            DATA_NAME
        );
    }

    private static RsccSupplyPolicy load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final RsccSupplyPolicy data = new RsccSupplyPolicy();
        final RsccSupplyStrategy parsed = RsccSupplyStrategy.byId(tag.getString(TAG_STRATEGY));
        if (parsed != null) {
            data.strategy = parsed;
        }
        return data;
    }

    /**
     * 读取当前档位；只在服务端有效（客户端 / 取不到服务器时回落到默认档）。
     * 绝不在客户端缓存，避免单机 / 联机下客户端读到过期值。
     */
    public static RsccSupplyStrategy strategy(final Level level) {
        if (level instanceof ServerLevel server) {
            final MinecraftServer mc = server.getServer();
            if (mc != null) {
                return get(mc).strategy;
            }
        }
        return DEFAULT_STRATEGY;
    }

    public RsccSupplyStrategy getStrategy() {
        return strategy;
    }

    /** 设置档位（幂等；变化时标记脏数据以便落盘）。 */
    public void setStrategy(final RsccSupplyStrategy newStrategy) {
        if (newStrategy != null && newStrategy != strategy) {
            strategy = newStrategy;
            setDirty();
        }
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putString(TAG_STRATEGY, strategy.id());
        return tag;
    }
}
