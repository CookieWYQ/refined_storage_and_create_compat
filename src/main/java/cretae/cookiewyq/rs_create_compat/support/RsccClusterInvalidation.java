package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 机器集群「解析缓存」的事件驱动失效监听器。
 *
 * <p><b>为什么需要它</b>：集群解析按用户要求必须「缓存 + 事件驱动失效」，不能每 tick BFS。
 * 放置 / 破坏 / 替换方块是唯一会改变连通分量的来源，而它们都会触发
 * {@link BlockEvent.NeighborNotifyEvent}（由 {@code Level#updateNeighborsAt} 触发）——
 * 因此在这里把「被改变方块自身 + 它的 6 个邻块」所属的集群缓存作废，下一次
 * {@link RsccMachineCluster#ensure} 立即重算，最多延迟 1 tick（远小于肉眼可感）。</p>
 *
 * <p><b>只读方块状态，绝不触发方块实体加载</b>：判定用的是 {@link BlockState}
 * （{@link RsccMachineCluster#isClusterBlock(BlockState)}），因此绝不会因为一个邻块事件就
 * 强制加载 / 初始化无关的邻块，也不会出现 A→B→A 的递归初始化。</p>
 *
 * <p><b>不在事件回调里搜索</b>：事件发生在世界正被修改的过程中，这里只作废缓存；
 * 真正的重算留给下一 tick 方块实体自己的安全时机（{@code doWork} → {@code ensure}）。</p>
 */
public final class RsccClusterInvalidation {
    private RsccClusterInvalidation() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccClusterInvalidation());
    }

    /** 邻块变化：作废「被改变方块及其邻块」所属集群的解析缓存。只在服务端生效（客户端不做集群解析）。 */
    @SubscribeEvent
    public void onNeighborNotify(final BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final BlockPos changed = event.getPos();
        // 只在「变化点或它的某个邻块确实是我们可成集群的机器」时才动缓存，避免全服方块事件都进 Map 查询
        boolean relevant = RsccMachineCluster.isClusterBlock(level, changed);
        if (!relevant) {
            for (final Direction direction : event.getNotifiedSides()) {
                if (RsccMachineCluster.isClusterBlock(level, changed.relative(direction))) {
                    relevant = true;
                    break;
                }
            }
        }
        if (relevant) {
            RsccMachineCluster.invalidateAround(level, changed);
        }
    }
}
