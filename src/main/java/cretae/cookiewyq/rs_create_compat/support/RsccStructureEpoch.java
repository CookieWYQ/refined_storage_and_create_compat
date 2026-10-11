package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * <b>结构版本号（epoch）</b>：凡「成链 / 绑定 / 连接」这类<b>纯结构</b>可能变化的时刻，把版本号 +1。
 *
 * <h2>为什么需要它（而不是在每个读取处猜）</h2>
 * <p>执行舱与输出总线之间有大量「结构性」推导（链成员、归属表、朝向推出来的供料目标）。它们只在
 * <b>方块被放 / 被拆、绑定变更、扳手剪线 / 套壳、成链成员变化、网络节点增删</b>时才会变；在这些事件上
 * 打一个<b>单调递增的版本号</b>，读取侧只要记下「我这份结果是用哪个版本算的」即可 ——
 * 版本对不上就重建，因此<b>漏掉某个显式钩子也不会读到旧值</b>（显式清理做不到这一点：
 * 漏一个调用点就等于永久冻结，正是 RS 自己那份「弱键 + 永不失效」的样板缓存 {@code patternCache} 的坑）。</p>
 *
 * <h2>为什么只 +1、不在这里做任何计算</h2>
 * <p>本类刻意<b>不持有任何世界数据</b>、不做搜索、不取方块实体：事件回调发生在世界正被修改的过程中，
 * 在这里做重活会踩「搜索期加载邻块 → 递归触发事件」那类崩溃（工程已被该问题坑过，见
 * {@code RsccSearchGuard}）。真正的重建留给使用者下一次读取时的安全时机。</p>
 *
 * <h2>挂点选择（覆盖 vs 便宜的取舍）</h2>
 * <p>用 {@link BlockEvent.NeighborNotifyEvent}（由 {@code Level#updateNeighborsAt} 触发）作为<b>粗粒度</b>失效源：
 * 方块放置 / 破坏 / 被替换都会走它，扳手拆线（{@code RsccWrenchCableInteraction} 那条「不触发 BreakEvent」的
 * 拆除路径）与套壳 / 断缝的记录变更最终也都会经过世界更新。代价是「与结构无关的方块变化」（例如远处放一块
 * 石头）也会 +1 —— 那只会让下一次读取重建一次结果，而重建上限是 {@code MAX_CHAIN_LENGTH}（8）台以内的小推导，
 * 因此「宁可多失效、绝不漏失效」这个方向是划算的。</p>
 *
 * <p><b>不泄漏</b>：本类<b>不缓存任何东西</b>，只有一个 {@code long} 计数器，因此不存在按世界 / 按坐标增长的记录。</p>
 */
public final class RsccStructureEpoch {
    /**
     * 全局结构版本号（单调递增）。
     * <p>用<b>一个</b>全局计数器而不是「每个世界一个」：跨世界串号只会让另一维度的缓存多重建一次
     * （多失效是安全方向），而我们因此不必读写任何按世界分表的可变状态，也就没有卸载清理的问题。</p>
     */
    private static long epoch = 1L;

    private RsccStructureEpoch() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccStructureEpoch());
    }

    /** 当前结构版本号。使用者在缓存里记下它，下次读取时比对。 */
    public static long current() {
        return epoch;
    }

    /**
     * 结构可能变化 ⇒ 版本号 +1。
     * <p>做成「一个自增」，因此可以在方块事件回调里安全地逐次调用（不搜索、不取方块实体）。
     * 客户端不推版本（客户端不做结构推导，状态一律以 S2C 同步为准）。</p>
     */
    public static void bump(@org.jetbrains.annotations.Nullable final Level level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        epoch++;
    }

    /** 服务端结构变化（不带世界时的入口，例如方块实体在 tick 中自行判定「绑定变了」）。 */
    public static void bump() {
        epoch++;
    }

    /**
     * 邻块变化 ⇒ 结构版本 +1。
     * <p>只读事件字段，<b>不看方块状态、不取方块实体</b>：这里是最热的方块事件路径，
     * 判定成本必须与方块种类无关。</p>
     */
    @SubscribeEvent
    public void onNeighborNotify(final BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        bump(serverLevel);
    }

    /**
     * 区块卸载 ⇒ 结构版本 +1。
     *
     * <h2>为什么卸载也算结构变化（这条不能省）</h2>
     * <p>区块卸载时方块<b>没有</b>被拆掉，因此 {@link BlockEvent.NeighborNotifyEvent} <b>不会</b>触发 ——
     * 可是「链成员能不能被推到」恰恰依赖区块是否加载（{@code chainHead()} / {@code collectUpstream}
     * 都是逐次解析方块实体，未加载的那一段推不出来）。少了这一条，一条链的成员集合可能在
     * <b>一次区块卸载之后</b>仍然按旧结果被复用，表现为「链上有一台已经被卸载了，别人还以为它在」。</p>
     */
    @SubscribeEvent
    public void onChunkUnload(final ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        bump(serverLevel);
    }

    /**
     * 区块加载 ⇒ 结构版本 +1。
     * <p>与 {@link #onChunkUnload} 对称：那一段链重新变得可达了，推导结果必须重算，
     * 否则执行舱会「看不见刚加载回来的那台仓」，直到别的方块事件碰巧把它刷新一次。</p>
     */
    @SubscribeEvent
    public void onChunkLoad(final ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        bump(serverLevel);
    }

    /** 诊断：当前结构版本号（供日志 / 自检查看「这一轮到底取的是哪个版本」）。 */
    public static String describe() {
        return "epoch=" + epoch;
    }
}
