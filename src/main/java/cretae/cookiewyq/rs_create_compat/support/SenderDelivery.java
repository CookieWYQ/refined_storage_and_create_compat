package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.NetworkResourceReceiverNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第 131 轮：**发送（取网络里的资源 → 写进目标接收器缓存）的唯一实现**。
 *
 * <h2>为什么从方块实体里抽出来</h2>
 * <p>第 131 轮用户要求「终端也支持网络资源发送器的功能」。投递这一段（SIMULATE 能取多少 →
 * SIMULATE 能吃多少 → EXECUTE 取「能取 ∩ 能吃」→ 写不进的余量回滚网络 → 网络也拒收的进缓冲）
 * 是整套守恒语义的核心，<b>绝不能有第二份实现</b>：两份写法只要对「回滚 / 计数 / shortfall」
 * 的理解差一行，网络里的资源就会凭空多或少一件，而这种 bug 在游戏里几乎不可能被玩家复现定位。</p>
 * <p>因此这里只保留一个 {@link #deliver}：请求器方块与终端（以及以后任何发送源）都调它。</p>
 *
 * <h2>硬规则（调用方不许绕过）</h2>
 * <ol>
 *     <li>取料只走 {@link StorageNetworkComponent#extract}，并且<b>先夹再取</b>
 *     （{@code min(能取, 能吃)}）—— 从源头上避免「取出来放不下」；</li>
 *     <li>计划量只按「真的写进接收器」的量递减：回滚掉的部分没有投进去，所以
 *     {@code remaining} 不会被多减，下批会重新 SIMULATE 再看能不能取；</li>
 *     <li>守恒自证：{@code 取出量 == 写入量 + 回滚量 + 暂存量}，任何一项写错立刻打 error 日志；</li>
 *     <li>「本 tick 批数用完」是唯一允许跨 tick 续投的情形（{@link Result#carry()}）；
 *     「网络没料 / 接收器吃不下」属于缺口，本轮就地结束并提示，绝不无限空转。</li>
 * </ol>
 *
 * <h2>节拍为什么是常量</h2>
 * <p>用户第 131 轮拍板：「发送器跟插件槽没关系，<b>红石激活的速度决定发送速度</b>」。
 * 所以每 tick 批数 / 单批量不再来自升级，而是这里的两个常量：
 * {@link #FIXED_BATCHES_PER_TICK} / {@link #FIXED_BATCH_SIZE}。红石脉冲多快、界面按钮点多快，
 * 就是发送多快；两个实现（请求器 / 终端）共用同一组数值，不会出现「终端比方块快」这种偏差。</p>
 */
public final class SenderDelivery {
    private SenderDelivery() {
    }

    /**
     * 每 tick 允许的批数（第 131 轮起固定，不再吃速度升级）。
     *
     * <p>取 8：一个红石脉冲（最小间隔 1 tick）最多搬 8 批，乘 {@link #FIXED_BATCH_SIZE}=64
     * ⇒ 512 件/tick 的吞吐，足够覆盖「一次脉冲把手里的订单投完」的常见规模；
     * 同时把单 tick 成本钉在一个可预算的上界里（第 131 轮之前满级也只有 64 批）。</p>
     */
    public static final int FIXED_BATCHES_PER_TICK = 8;

    /**
     * 单批搬运量（物品 = 件，流体 = mB；固定，不再吃叠加升级）。
     *
     * <p>取 64：与 Minecraft 的一整组对齐 —— 玩家对「一批 = 一组」有肌肉记忆，
     * 界面上的「每 tick 8 批 · 每批 64」也最容易读成「一秒 640 件」。</p>
     */
    public static final long FIXED_BATCH_SIZE = 64L;

    /**
     * 单 tick 批数的<b>硬上限</b>（防单 tick 卡死的兜底护栏）。
     *
     * <p>固定节拍只有 {@link #FIXED_BATCHES_PER_TICK}=8，永远碰不到这个值；
     * 保留它是因为 {@link #deliver} 是公共入口，将来若有人把节拍调大，这里仍然能兜住主线程。</p>
     */
    public static final int MAX_BATCHES_PER_TICK = 512;

    /**
     * 投递目标（接收器侧）的最小接口：把「往哪儿写」与「写多少」交给实现。
     *
     * <p>请求器 / 终端都只认 {@code NetworkResourceReceiverBlockEntity}，但这里仍然走接口：
     * 一个是为了让「投递引擎」不依赖具体方块，另一个是为了让自检脚本能用假 sink 复算守恒账。</p>
     */
    public interface ReceiverSink {
        /** 这个资源能收多少（{@code simulate = true} 时只问、不写）。 */
        long apply(ResourceKey resource, long amount, boolean simulate);

        /** 这个资源有没有接收通道（分辨「不支持这种资源」与「通道满了」）。 */
        boolean canHandle(ResourceKey resource);

        /** 目标名字（提示文案用；空串表示未命名）。 */
        String displayName();
    }

    /** 投递过程中的两类副作用：玩家可见提示、回滚失败的暂存。 */
    public interface Feedback {
        void hint(String reason, Object... args);

        void buffer(ResourceKey resource, long amount);
    }

    /**
     * 一次 {@link #deliver} 的账。
     *
     * @param moved       真正写进接收器的总量
     * @param shortfall   本轮是否出现缺口（取不到 / 吃不下 / 只投出一部分）
     * @param carry       「本 tick 批数用完」还没投完的部分（调用方留到下一 tick 续投）
     * @param batchesUsed 本 tick 累计已花的批数（调用方按 gameTime 记账）
     */
    public record Result(long moved, boolean shortfall, List<ResourceAmount> carry, int batchesUsed) {
    }

    /**
     * 逐条投递 {@code plan}（「本次请求还差多少」），返回真正落进接收器的总件数与缺口状态。
     *
     * @param network        发送源所在网络（{@code null} ⇒ 什么都不做，记缺口）
     * @param sink           目标接收器
     * @param plan           待投计划（快照；本方法不修改入参）
     * @param perTickBatches 每 tick 批数（由调用方的 {@code batchesPerTick()} 给出）
     * @param batchSize      单批量（由调用方的 {@code batchSize()} 给出）
     * @param batchesUsed    本 tick 已花的批数（同一 tick 内多次调用合计不超预算）
     */
    public static Result deliver(@Nullable final Network network,
                                 @Nullable final ReceiverSink sink,
                                 final List<ResourceAmount> plan,
                                 final int perTickBatches,
                                 final long batchSize,
                                 final int batchesUsed,
                                 final Feedback feedback) {
        if (sink == null) {
            feedback.hint("pending_lost", "");
            return new Result(0L, true, List.of(), batchesUsed);
        }
        if (network == null) {
            feedback.hint("no_network");
            return new Result(0L, true, List.of(), batchesUsed);
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            feedback.hint("no_storage");
            return new Result(0L, true, List.of(), batchesUsed);
        }
        final long batch = Math.max(1L, batchSize);
        final int budget = Math.min(MAX_BATCHES_PER_TICK, Math.max(1, perTickBatches));
        int batches = Math.max(0, batchesUsed);
        long moved = 0L;
        boolean shortfall = false;
        // 「本 tick 之后还差多少」：只有「批数用完」造成的没投完才带进下一 tick
        final List<ResourceAmount> carry = new ArrayList<>();
        for (final ResourceAmount order : List.copyOf(plan)) {
            if (order == null || order.amount() <= 0L) {
                continue;
            }
            final ResourceKey resource = order.resource();
            long remaining = order.amount();
            // 资源身份只认 RS 的 ResourceKey：物品 / 流体 / 气体（装了联动后）一律走同一条路径。
            // 唯一的拒绝条件是「这个键不是 PlatformResourceKey」—— 那样它既进不了存档也同步不了，
            // 所以明确提示「不支持」，而不是悄悄跳过。
            if (!(resource instanceof PlatformResourceKey)) {
                feedback.hint("unsupported", describeResource(resource));
                shortfall = true;
                continue;
            }
            boolean stalled = false;
            while (remaining > 0L && batches < budget) {
                // ⚠️ 每批只请求 min(还差多少, 单批量)：绝不因为「计划还有」就超量
                final long wanted = Math.min(remaining, batch);
                // ① 先模拟：网络里能取多少
                final long available = storage.extract(resource, wanted, Action.SIMULATE, Actor.EMPTY);
                if (available <= 0L) {
                    feedback.hint("no_stock", describeResource(resource));
                    shortfall = true;
                    stalled = true;
                    break;
                }
                // ② 再模拟：接收器能吃多少（物品 / 流体进缓存，其它类型直通它朝向的容器）。
                // 第三方容器可能抛异常：异常发生在「还没取出任何东西」之前，因此直接结束这一条，
                // 守恒账不受影响（一件资源都没动）。
                final long acceptable;
                try {
                    acceptable = sink.apply(resource, available, true);
                } catch (final RuntimeException e) {
                    feedback.hint("deliver_exception", describeResource(resource));
                    shortfall = true;
                    stalled = true;
                    break;
                }
                if (acceptable <= 0L) {
                    // 分清两种情况：通道根本不存在（不支持这种资源）vs 通道存在但满了。
                    // canHandle 本身也会问第三方容器，同样要防它抛异常。
                    boolean channelExists;
                    try {
                        channelExists = sink.canHandle(resource);
                    } catch (final RuntimeException e) {
                        channelExists = false;
                    }
                    if (channelExists) {
                        feedback.hint("receiver_full", sink.displayName(),
                            describeResource(resource));
                    } else {
                        feedback.hint("unsupported", describeResource(resource));
                    }
                    shortfall = true;
                    stalled = true;
                    break;
                }
                // ③ 真正取：只取「能取 ∩ 能吃」的量
                final long got = storage.extract(resource, Math.min(available, acceptable),
                    Action.EXECUTE, Actor.EMPTY);
                if (got <= 0L) {
                    // 理论不可达（SIMULATE 刚放行）；真出现也绝不假装成功
                    feedback.hint("extract_failed", describeResource(resource));
                    shortfall = true;
                    stalled = true;
                    break;
                }
                batches++; // 只有真的取到资源才消耗本 tick 的批量预算（空探测不计数）
                // ④ 写入接收器缓存。异常路径同样守恒：按「一件都没写进去」结算，
                // 让下面的 remainder 逻辑把 got 原样回滚网络 / 进缓冲。
                long inserted;
                try {
                    inserted = sink.apply(resource, got, false);
                } catch (final RuntimeException e) {
                    inserted = 0L;
                    feedback.hint("deliver_exception", describeResource(resource));
                }
                final long remainder = got - inserted;
                long rolledBack = 0L;
                long kept = 0L;
                if (remainder > 0L) {
                    // 守恒：先原样写回 RS 网络；网络也拒收的那部分进发送源自己的缓冲
                    rolledBack = storage.insert(resource, remainder, Action.EXECUTE, Actor.EMPTY);
                    kept = remainder - rolledBack;
                    if (kept > 0L) {
                        feedback.buffer(resource, kept);
                        feedback.hint("buffer_buffered", describeResource(resource), kept);
                    }
                    if (rolledBack > 0L) {
                        feedback.hint("partial", sink.displayName(), describeResource(resource),
                            inserted, rolledBack, got);
                    }
                }
                // 守恒自证：取出量 == 写入量 + 回滚量 + 暂存量。任何一项写错都在这里立刻暴露。
                if (got != inserted + rolledBack + kept) {
                    feedback.hint("conservation_broken", got, inserted, rolledBack, kept);
                }
                moved += inserted;
                // 计划量只按「真的写进接收器」的量递减 ⇒ 不会重复投、不会过量
                remaining -= inserted;
                if (inserted <= 0L) {
                    // 一点都没写进去：本 tick 不再对这条空转（回滚 / 暂存已经记过账）
                    shortfall = true;
                    stalled = true;
                    break;
                }
                if (available < wanted) {
                    // 网络里就这么多：投完能投的，剩下的不再指望本 tick
                    feedback.hint("short_stock", describeResource(resource), order.amount(),
                        order.amount() - remaining);
                    shortfall = true;
                    stalled = true;
                    break;
                }
            }
            if (remaining > 0L && !stalled) {
                // 唯一的跨 tick 情形：本 tick 的批数用完了（订单比一个 tick 能投的更多）
                carry.add(new ResourceAmount(resource, remaining));
            }
        }
        return new Result(moved, shortfall, List.copyOf(carry), batches);
    }

    // ==================== 目标解析（现解析，不做注册表快照） ====================

    /**
     * 在网络内按名字解析接收器。
     *
     * <p><b>为什么每次现解析、不做快照</b>（设计文档 §4.4 的核心决议）：名字是玩家随时可改的，
     * 快照就必须有失效通知机制，而任何「需要同步的缓存副本」都是一个可能说谎的状态源。
     * 现解析的代价是 O(网络节点数)，调用时机只有「一轮请求」，完全可以接受；副作用是
     * <b>目标改名 / 拆除自动跟随</b>，不需要任何通知。</p>
     *
     * <p><b>区块未加载 / 未接入网络</b>：{@link GraphNetworkComponent#getContainers()} 只含已加载的节点，
     * 因此这两类情形自然表现为「找不到」，而不是报错。</p>
     *
     * @param name 目标名字（空名 ⇒ 直接返回空列表：空名的接收器根本不是合法的投递目标）
     * @return 命中的接收器（0 = 未找到；1 = 命中；≥2 = 重名冲突，调用方必须拒绝而不是挑一个）
     */
    public static List<NetworkResourceReceiverBlockEntity> resolveReceiversByName(
        @Nullable final Network network, @Nullable final String name) {
        if (network == null || name == null || name.isEmpty()) {
            return List.of();
        }
        final List<NetworkResourceReceiverBlockEntity> hits = new ArrayList<>();
        for (final NetworkNodeContainer container : graphContainers(network)) {
            final NetworkResourceReceiverBlockEntity receiver = receiverOf(container);
            if (receiver != null && name.equals(receiver.getReceiverName())
                && receiver.isProperlyConfigured()) {
                hits.add(receiver);
            }
        }
        return hits;
    }

    /**
     * 本网络里「可以用作目标」的接收器名字（已命名 + 已配置；顺序稳定、去重）。
     *
     * <p>这是目标选择子界面的唯一数据源：判据与 {@link #resolveReceiversByName} <b>同一套</b>
     * （{@code isProperlyConfigured()} —— 空名的接收器选了也投递不了，那只会变成一条注定失败的配置）。</p>
     */
    public static List<String> receiverNames(@Nullable final Network network) {
        final List<String> names = new ArrayList<>();
        for (final SyncRequesterConfigPacket.ReceiverView view : receiverViews(network)) {
            if (!names.contains(view.name())) {
                names.add(view.name());
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    /**
     * 已命名接收器的只读视图（名字 + 坐标），界面列表 / 子界面搜索的唯一数据源。
     *
     * <p>顺序稳定（名字 → 坐标）：界面翻页 / 过滤时条目不会因为网络图遍历顺序变化而乱跳。</p>
     */
    public static List<SyncRequesterConfigPacket.ReceiverView> receiverViews(
        @Nullable final Network network) {
        if (network == null) {
            return List.of();
        }
        final List<SyncRequesterConfigPacket.ReceiverView> views = new ArrayList<>();
        for (final NetworkNodeContainer container : graphContainers(network)) {
            final NetworkResourceReceiverBlockEntity found = receiverOf(container);
            if (found == null || !found.isProperlyConfigured()) {
                continue;
            }
            views.add(new SyncRequesterConfigPacket.ReceiverView(found.getReceiverName(),
                found.getBlockPos().getX(), found.getBlockPos().getY(),
                found.getBlockPos().getZ()));
        }
        views.sort(Comparator
            .comparing(SyncRequesterConfigPacket.ReceiverView::name)
            .thenComparingInt(SyncRequesterConfigPacket.ReceiverView::x)
            .thenComparingInt(SyncRequesterConfigPacket.ReceiverView::y)
            .thenComparingInt(SyncRequesterConfigPacket.ReceiverView::z));
        return List.copyOf(views);
    }

    private static List<NetworkNodeContainer> graphContainers(final Network network) {
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        return graph == null ? List.of() : List.copyOf(graph.getContainers());
    }

    @Nullable
    private static NetworkResourceReceiverBlockEntity receiverOf(
        @Nullable final NetworkNodeContainer container) {
        if (container == null || !(container.getNode() instanceof NetworkResourceReceiverNetworkNode node)) {
            return null;
        }
        return node.getBlockEntity();
    }

    // ==================== 文案 / 显示 ====================

    /** 资源显示名（物品 / 流体各取自己的 hover name；其它类型退回 {@code toString}）。 */
    public static Component describeResource(@Nullable final ResourceKey resource) {
        if (resource instanceof ItemResource item) {
            return item.toItemStack(1).getHoverName();
        }
        if (resource instanceof FluidResource fluid) {
            final Fluid found = fluid.fluid();
            if (found != null) {
                return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(found), 1,
                    fluid.components()).getHoverName();
            }
        }
        return Component.literal(String.valueOf(resource));
    }
}
