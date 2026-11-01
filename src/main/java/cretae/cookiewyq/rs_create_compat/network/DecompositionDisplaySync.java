package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.DecompositionDisplayEntry;
import cretae.cookiewyq.rs_create_compat.support.DecompositionTable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 服务端：把「降解表」展开成 JEI 展示条目，在数据包同步时推给客户端
 * （{@link SyncDecompositionDisplayPacket}）。
 *
 * <h2>为什么挂在 {@link OnDatapackSyncEvent} 上</h2>
 * <p>降解表是<b>数据包驱动</b>的（{@code data/<namespace>/decomposition/*.json}），
 * 服务端在 {@code /reload} 与开服时重新加载。{@link OnDatapackSyncEvent} 是 NeoForge 提供的
 * 「数据包已经加载完、该把派生数据同步给玩家」的唯一时机，两种触发天然覆盖：</p>
 * <ul>
 *     <li><b>玩家进服</b>（{@code getRelevantPlayers()} 只含该玩家）—— 客户端立刻拿到当前表；</li>
 *     <li><b>{@code /reload} 或开服</b>（{@code getRelevantPlayers()} 是全体在线玩家）——
 *     表变了，所有人的 JEI 分类页跟着变，不需要重进世界。</li>
 * </ul>
 * <p>这也是 NeoForge 同步原版配方（{@code SyncRecipesEvent}）用的同一时机：JEI 重建配方列表
 * 发生在「客户端收完配方」之后，因此这里先发的条目一定能赶上同一次 JEI 重建。</p>
 *
 * <h2>为什么事件总线是 game bus</h2>
 * <p>{@link OnDatapackSyncEvent} 在 {@code NeoForge.EVENT_BUS} 上分发（与
 * {@code AddReloadListenerEvent} 相同，见 {@code DecompositionDataLoader} 的类注释）；
 * 本类的 {@code @EventBusSubscriber(modid = ...)} 默认就是 game bus，因此主类不需要额外一行。</p>
 *
 * <h2>专用服务器安全性</h2>
 * <p>本类只用服务端/双端通用类型（{@link DecompositionTable} / {@link PacketDistributor}）。
 * 客户端动作全部在 {@link ClientPayloadHooks} 那层间接里，本类<b>不</b>触碰
 * {@code net.minecraft.client.*}（{@code selfcheck_round47_dedicated_server_safety} 会盯）。</p>
 *
 * <h2>为什么不在这里打日志</h2>
 * <p>这是每次 {@code /reload} / 每次进服都会走的路径，本工程对「周期性打印」有硬闸
 * （{@code selfcheck_round122_devlog_gate}）。同步成功与否对玩家不产生歧义，
 * 表本身的重载汇总已由 {@link DecompositionTable} 打一条，这里保持安静。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class DecompositionDisplaySync {
    private DecompositionDisplaySync() {
    }

    /** 数据包已加载（进服 / {@code /reload}）→ 把当前表展开后推给相关玩家。 */
    @SubscribeEvent
    public static void onDatapackSync(final OnDatapackSyncEvent event) {
        final List<DecompositionDisplayEntry> entries = DecompositionTable.displayEntries();
        final SyncDecompositionDisplayPacket packet = new SyncDecompositionDisplayPacket(entries);
        // getRelevantPlayers()：进服时只含该玩家，/reload 时是全体在线玩家（见类注释）。
        // 空表也照发 —— 「服务端表是空的」本身就是一条要如实同步的结论（客户端据此清掉旧镜像）。
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, packet));
    }
}
