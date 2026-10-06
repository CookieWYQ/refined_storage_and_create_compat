package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.IntermediateCacheBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 「中间产物共享缓存池」解析缓存的事件驱动失效监听器。
 *
 * <h2>为什么需要它</h2>
 * 执行舱读「池子里有什么」时走的是 {@link RsccSharedCache} 的缓存（枚举整张网络图是 O(节点数)，
 * 而按槽位遍历物品存储会反复问这个问题）。缓存仓被<b>放置 / 破坏 / 被替换</b>是「池子成员变了」
 * 的唯一来源，它们都会触发 {@link BlockEvent.NeighborNotifyEvent}（由
 * {@code Level#updateNeighborsAt} 触发）—— 在这里把池子缓存整体作废，
 * 下一次访问立刻重算，最多延迟 1 tick。
 *
 * <p><b>只读方块状态</b>：判定用 {@link IntermediateCacheBlock} 类型比较，
 * 绝不触碰任何方块实体，因此不会因为一个邻块事件就强制加载 / 初始化无关的邻块。</p>
 *
 * <p><b>不在事件回调里搜索</b>：事件发生在世界正被修改的过程中，这里只调
 * {@link RsccSharedCache#poolChanged()} 清缓存；真正的重算留给下一 tick 使用者的安全时机。</p>
 *
 * <p><b>为什么还要看邻块</b>：把缓存仓<b>贴着</b>线缆放置时，「变化点」是缓存仓自己；
 * 而拆掉 / 接上它旁边那根线缆时，「变化点」是线缆 —— 那时缓存仓只是被通知的邻居之一。
 * 两条都要覆盖，否则会出现「线缆接上了但执行舱一分钟都看不见池子」。</p>
 */
public final class RsccCacheInvalidation {
    private RsccCacheInvalidation() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccCacheInvalidation());
    }

    /** 邻块变化：作废共享缓存池的解析缓存（只在服务端生效，客户端不做池子解析）。 */
    @SubscribeEvent
    public void onNeighborNotify(final BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final BlockPos changed = event.getPos();
        if (isWarehouse(level, changed)) {
            RsccSharedCache.poolChanged();
            return;
        }
        for (final Direction direction : event.getNotifiedSides()) {
            if (isWarehouse(level, changed.relative(direction))) {
                RsccSharedCache.poolChanged();
                return;
            }
        }
    }

    /** 该坐标上是否是「中间产物缓存仓」（只读方块状态，绝不触达方块实体）。 */
    private static boolean isWarehouse(final ServerLevel level, final BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof IntermediateCacheBlock;
    }
}
