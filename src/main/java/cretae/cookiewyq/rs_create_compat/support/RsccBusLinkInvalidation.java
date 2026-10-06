package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 输出总线「连接缓存」的事件驱动失效监听器。
 *
 * <p><b>为什么需要它（2026-09-13 性能优化）</b>：输出总线的归属解析曾靠「每 20 tick 无条件重跑
 * 一次 BFS」兜底 —— 未绑定时（旁边什么都没有）这纯属浪费。优化后改为「事件驱动 + 廉价预检 +
 * 退避」，因此退避到 200 tick 的空闲总线需要一个<b>可靠的失效信号</b>来立刻回到最快间隔。</p>
 *
 * <p><b>挂点（已在 {@code local_src} 核对真实存在）</b>：NeoForge 的
 * {@link BlockEvent.NeighborNotifyEvent}（{@code net.neoforged.neoforge.event.level.BlockEvent}）——
 * 由 {@code Level#updateNeighborsAt} 触发，字段 {@code getLevel() / getPos() / getNotifiedSides()}。
 * 方块放置 / 破坏 / 被替换都会走这条路径，覆盖「邻块变化」的全部来源。</p>
 *
 * <p><b>只做廉价判定，绝不触发方块实体加载</b>：先按 {@code event.getNotifiedSides()} 找到被通知的
 * 邻块，先读方块状态确认它是不是「输出总线方块」（{@link RsccWireBlocks#isExporterBus}），
 * 只有确认是才用 {@code LevelChunk#getBlockEntity(pos, CHECK)}（<b>已存在才返回，绝不创建</b>）
 * 拿它的方块实体并作废缓存。判定用的是 {@code BlockState}，因此绝不会因为监听一个方块事件就强制
 * 加载 / 初始化无关的邻块（这正是上一轮 A→B→A 递归崩溃的成因）。</p>
 *
 * <p><b>不在事件回调里搜索</b>：事件发生在世界正被修改的过程中，监听器只调
 * {@link RsccExporterExecutorMode#rscc$invalidateNeighborLink()} 作废缓存与退避窗口，
 * 真正的重算留给下一 tick 的安全时机（{@code rscc$serverTick()}）。</p>
 */
public final class RsccBusLinkInvalidation {
    private RsccBusLinkInvalidation() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccBusLinkInvalidation());
    }

    /**
     * 邻块变化：把「被通知方向上的输出总线 / 输入总线」连接缓存作废，令其下一 tick 重新解析归属。
     * <p>只在服务端生效（客户端不做邻块搜索，状态一律以 S2C 同步为准）。</p>
     */
    @SubscribeEvent
    public void onNeighborNotify(final BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final BlockPos changed = event.getPos();
        for (final Direction direction : event.getNotifiedSides()) {
            final BlockPos neighbor = changed.relative(direction);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            // 先按方块状态确认「是输出总线 / 输入总线方块」再取方块实体（CHECK：只取已存在的）
            final BlockState state = level.getBlockState(neighbor);
            final boolean exporterBus = RsccWireBlocks.isExporterBus(state);
            final boolean importerBus = RsccWireBlocks.isImporterBus(state);
            if (!exporterBus && !importerBus) {
                continue;
            }
            final BlockEntity blockEntity = level.getChunkAt(neighbor)
                .getBlockEntity(neighbor, LevelChunk.EntityCreationType.CHECK);
            // 输出总线（延长型输出）与输入总线（延长型输入）共用同一条「邻块失效」信号：
            // 两者都靠「缓存 + 邻块事件驱动失效」避免每 tick BFS（见 RsccWireLinkSearch）。
            if (exporterBus && blockEntity instanceof RsccExporterExecutorMode exporter) {
                exporter.rscc$invalidateNeighborLink();
            }
            if (importerBus && blockEntity instanceof RsccImporterExecutorMode importer) {
                importer.rscc$invalidateNeighborLink();
            }
        }
    }
}
