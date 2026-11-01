package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import cretae.cookiewyq.rs_create_compat.network.SenderShortagePromptPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 第 131 轮 F 组：**「发送量超过网络存量，且这东西能自动合成」时的询问**。
 *
 * <h2>用户原话</h2>
 * <p>「发送物品的时候，如果发送的数量超过网络中所包含的，并且这个东西又可以被自动合成，
 * 那么优先向用户询问是否自动合成」。数量表达（用户拍板 A）：
 * 「目标 100 · 现有 50 · 最多可合成 25 · 还缺 25」，两个按钮
 * 「合成 25 并发送 75」/「只发送 50」。</p>
 *
 * <h2>三个数字从哪来（每一处都必须有出处，不许自己算）</h2>
 * <ul>
 *     <li><b>目标</b> = 订单清单里该资源要的量（{@link SenderBackend#orders()}）；</li>
 *     <li><b>现有</b> = 网络存量 {@link StorageNetworkComponent#get(ResourceKey)}
 *     —— RS 自己的口径，不自己遍历存储；</li>
 *     <li><b>最多可合成</b> = RS 的 {@link AutocraftingNetworkComponent#getMaxAmount(ResourceKey,
 *     CancellationToken)}（它就是个 {@code PreviewProvider}）。<b>绝不</b>自己遍历配方瞎猜：
 *     配方树的递归 / 循环 / 替代配方全在 RS 的 {@code CraftingCalculator} 里算，重写一遍必然算错。</li>
 * </ul>
 *
 * <h2>「可合成」怎么判</h2>
 * <p>{@link AutocraftingNetworkComponent#getPatternsByOutput(ResourceKey)} 非空 =
 * 本网络里有以它为产物的样板。这一条只回答「能不能」，具体「能合成多少」仍然问
 * {@code getMaxAmount}。</p>
 *
 * <h2>为什么 getMaxAmount 只等一小会儿</h2>
 * <p>它是 {@code CompletableFuture.supplyAsync(...)}（RS 在自己的 executor 上算
 * {@code binarySearchMaxAmount}）。玩家的「发送」是一次同步点击、结果要立刻回话，
 * 因此这里做一个<b>有界</b>等待（{@link #MAX_AMOUNT_TIMEOUT_MS}）：超时就把「最多可合成」当 0
 * （= 按「不可合成」的旧路径照常发送），绝不把服务端主线程无限期挂住 ——
 * 挂住比「少问一次」严重得多，而且资源一件都不会丢。</p>
 *
 * <h2>询问期与回答期</h2>
 * <p>询问发出后，本次点击<b>不投递</b>（用户要求「先不发」）。玩家的回答走
 * {@code SenderShortageReplyPacket}；回答时重新从菜单解析发送源（防伪造 / 防过期），
 * 并用 {@link #takeAwaiting(UUID)} 取走「当时问的是什么」。60 秒没回答就作废
 * （{@link #AWAITING_TTL_MS}）—— 一个挂着一小时的询问不该突然把资源投出去。</p>
 */
public final class SenderShortage {
    private SenderShortage() {
    }

    /** 询问过期时间：超过它再回答就不算数（只回一句「已过期，请重新发送」）。 */
    public static final long AWAITING_TTL_MS = 60_000L;

    /** {@code getMaxAmount} 的有界等待（见类注释：宁可少问一次，也不挂住主线程）。 */
    public static final long MAX_AMOUNT_TIMEOUT_MS = 300L;

    /**
     * 一条订单的缺口事实。
     *
     * @param resource  资源
     * @param want      订单要的量（目标）
     * @param have      网络里现有
     * @param gap       {@code want - have}（&gt; 0 才有缺口）
     * @param craftable 本网络是否有以它为产物的样板
     * @param maxCraft  最多可合成（{@code getMaxAmount} 的有界结果；不可合成 ⇒ 0）
     */
    public record Gap(ResourceKey resource, long want, long have, long gap, boolean craftable,
                      long maxCraft) {
        /** 本次询问后能实际补上的量（缺口与可合成量取小）。 */
        public long craftableNow() {
            return Math.max(0L, Math.min(gap, maxCraft));
        }
    }

    /**
     * 一次询问的完整上下文（发出即等于「本次点击不投递」）。
     *
     * @param recipient    问的是谁（回答必须来自同一名玩家）
     * @param existingPlan 现在就能投出的部分（每条 = min(订单量, 现有量)）
     * @param gaps         有缺口的条目（含可合成量）
     * @param createdAt    发出时刻（{@link System#currentTimeMillis()}，用于过期判定）
     */
    public record Awaiting(UUID recipient, List<ResourceAmount> existingPlan, List<Gap> gaps,
                           long createdAt) {
        /** 目标总量（聚合显示用）。 */
        public long targetTotal() {
            long sum = 0L;
            for (final Gap gap : gaps) {
                sum = saturatedAdd(sum, gap.want());
            }
            return sum;
        }

        /** 现有总量。 */
        public long haveTotal() {
            long sum = 0L;
            for (final Gap gap : gaps) {
                sum = saturatedAdd(sum, gap.have());
            }
            return sum;
        }

        /** 最多可合成总量（已夹到缺口：不会出现「能合成 100 但只缺 25」的荒唐数字）。 */
        public long craftableTotal() {
            long sum = 0L;
            for (final Gap gap : gaps) {
                sum = saturatedAdd(sum, gap.craftableNow());
            }
            return sum;
        }

        /** 还缺总量（= 目标 − 现有 − 可合成；夹 ≥ 0）。 */
        public long missingTotal() {
            return Math.max(0L, targetTotal() - haveTotal() - craftableTotal());
        }

        /** 「合成 X 并发送 Y」里的 Y = 现有 + 可合成（夹到目标）。 */
        public long sendAfterCraftTotal() {
            return Math.min(targetTotal(), haveTotal() + craftableTotal());
        }
    }

    /** 「等回答」的询问表：按玩家 UUID 存（同一名玩家重发会覆盖上一份，绝不排队）。 */
    private static final Map<UUID, Awaiting> AWAITING = new ConcurrentHashMap<>();

    // ==================== 扫描 ====================

    /**
     * 逐条订单算 {@code have}，返回<b>有缺口</b>的条目（没有缺口 ⇒ 空列表）。
     *
     * <p>「可合成」只做样板存在性判定（便宜）；「最多可合成」在这里<b>不</b>算 ——
     * 只有确实要弹询问时才去问 RS（{@link #ask}），避免每次发送都付一次配方树搜索。</p>
     */
    public static List<Gap> scan(@Nullable final Network network,
                                 final List<ResourceAmount> orders) {
        if (network == null || orders == null || orders.isEmpty()) {
            return List.of();
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        final List<Gap> gaps = new ArrayList<>();
        for (final ResourceAmount order : orders) {
            if (order == null || order.amount() <= 0L) {
                continue;
            }
            final ResourceKey resource = order.resource();
            final long have = storage == null ? 0L : Math.max(0L, storage.get(resource));
            if (have >= order.amount()) {
                continue;
            }
            final boolean craftable = autocrafting != null
                && !autocrafting.getPatternsByOutput(resource).isEmpty();
            gaps.add(new Gap(resource, order.amount(), have, order.amount() - have, craftable, 0L));
        }
        return List.copyOf(gaps);
    }

    /** 现在就能投出的部分（每条 = min(订单量, 现有量)）；没有任何存量 ⇒ 空列表。 */
    public static List<ResourceAmount> existingPlan(@Nullable final Network network,
                                                    final List<ResourceAmount> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        final StorageNetworkComponent storage =
            network == null ? null : network.getComponent(StorageNetworkComponent.class);
        final List<ResourceAmount> plan = new ArrayList<>();
        for (final ResourceAmount order : orders) {
            if (order == null || order.amount() <= 0L) {
                continue;
            }
            final long have = storage == null ? 0L : Math.max(0L, storage.get(order.resource()));
            final long deliverable = Math.min(order.amount(), have);
            if (deliverable > 0L) {
                plan.add(new ResourceAmount(order.resource(), deliverable));
            }
        }
        return List.copyOf(plan);
    }

    // ==================== 询问（发送入口调用） ====================

    /**
     * 如果「有缺口 且 该资源可自动合成」⇒ 算「最多可合成」并向 {@code player} 发询问包。
     *
     * @return {@code true} = 已经发出询问，<b>调用方本轮不许投递</b>（用户要求「先不发」）；
     *     {@code false} = 没有可合成缺口（或网络/订单不成立），调用方按原路径发送
     */
    public static boolean ask(final ServerPlayer player, @Nullable final SenderBackend backend) {
        if (player == null || backend == null || player.level().isClientSide()) {
            return false;
        }
        final Network network = backend.network();
        if (network == null || backend.orders().isEmpty()) {
            return false;
        }
        final List<Gap> scanned = scan(network, backend.orders());
        if (scanned.isEmpty()) {
            return false; // 无缺口 ⇒ 行为与第 130 轮完全一致（回归判据）
        }
        boolean anyCraftable = false;
        for (final Gap gap : scanned) {
            if (gap.craftable()) {
                anyCraftable = true;
                break;
            }
        }
        if (!anyCraftable) {
            return false; // 不可合成 ⇒ 走旧路径（投现有 + 缺口提示），不打扰玩家
        }
        // 「最多可合成」：只对**可合成且有缺口**的资源问 RS（有界等待，见类注释）
        final List<Gap> gaps = new ArrayList<>(scanned.size());
        for (final Gap gap : scanned) {
            final long maxCraft = gap.craftable() ? maxCraftable(network, gap.resource()) : 0L;
            gaps.add(new Gap(gap.resource(), gap.want(), gap.have(), gap.gap(), gap.craftable(),
                maxCraft));
        }
        boolean craftableNow = false;
        for (final Gap gap : gaps) {
            if (gap.craftableNow() > 0L) {
                craftableNow = true;
                break;
            }
        }
        if (!craftableNow) {
            return false; // 有样板但原料不够 ⇒ 合成不出来，按旧路径发（并提示缺口）
        }
        final Awaiting awaiting = new Awaiting(player.getUUID(),
            existingPlan(network, backend.orders()), List.copyOf(gaps),
            System.currentTimeMillis());
        AWAITING.put(player.getUUID(), awaiting);
        final int containerId = player.containerMenu == null
            ? -1 : player.containerMenu.containerId;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            SenderShortagePromptPacket.of(containerId, awaiting));
        return true;
    }

    /** 取走「等回答」的询问（没有 / 已过期 ⇒ {@code null}；取走即清空）。 */
    @Nullable
    public static Awaiting takeAwaiting(final UUID player) {
        final Awaiting awaiting = AWAITING.remove(player);
        if (awaiting == null) {
            return null;
        }
        if (System.currentTimeMillis() - awaiting.createdAt() > AWAITING_TTL_MS) {
            return null; // 过期作废：一个挂着一分钟的询问不该突然把资源投出去
        }
        return awaiting;
    }

    /** 玩家退出 / 换界面时丢掉询问（不写任何状态，资源仍在网络里）。 */
    public static void forget(final UUID player) {
        AWAITING.remove(player);
    }

    // ==================== 回答 ====================

    /**
     * 处理玩家的回答。
     *
     * @param craft {@code true} = 「合成 X 并发送 Y」；{@code false} = 「只发送现有」
     * @return 是否真的执行了（{@code false} = 没有待回答的询问 / 已过期，调用方不必提示）
     */
    public static boolean reply(final ServerPlayer player, @Nullable final SenderBackend backend,
                                final boolean craft) {
        if (player == null || backend == null || player.level().isClientSide()) {
            return false;
        }
        final Awaiting awaiting = takeAwaiting(player.getUUID());
        if (awaiting == null) {
            return false;
        }
        // ① 先立刻投现有部分（两按钮都投它 —— 用户口径：「合成 25 并发送 75 / 只发送 120」的
        //    前一半就是现在能发的那部分）。计划为空（网络里一件都没有）⇒ 跳过投递，不报假成功。
        if (!awaiting.existingPlan().isEmpty()) {
            backend.triggerWithPlan(player, awaiting.existingPlan());
        }
        if (!craft) {
            // ② 只发送现有：缺口用一条提示说明，不登记补投、不发合成任务
            RequesterSenderBackend.syncTo(player, backend, MSG_SHORTAGE_SENT_ONLY, -1);
            return true;
        }
        // ③ 合成并发送：对缺口启动 RS 合成任务，剩余缺口登记到 PendingSenderSends（服务端 tick 补投）
        startCraft(backend.network(), awaiting.gaps());
        PendingSenderSends.register(player, backend, awaiting.gaps());
        RequesterSenderBackend.syncTo(player, backend, MSG_SHORTAGE_CRAFTING, -1);
        return true;
    }

    /**
     * 对每个可合成缺口启动 RS 合成任务。
     *
     * <h2>为什么用 {@code ensureTask} 而不是 {@code startTask}</h2>
     * <p>① {@code ensureTask} 的语义是「保证网络里<b>至少</b>有这么多在合成」（它先减去网络级
     * {@code currentlyCrafting}），因此玩家重复点「合成并发送」不会重复下单 —— 这正是本场景要的幂等；
     * {@code startTask} 是「再开一个任务」，点两次就会做两份。② 本模组其它所有服务端自动合成
     * （执行舱 / 结构加载器）走的都是 {@code ensureTask} + {@code TimeoutableCancellationToken}，
     * 全仓只有一份写法。③ {@code startTask} 要先算 preview/plan 才能拿到合适的量，
     * 而我们已经有了 {@code getMaxAmount} 的结论，直接「确保这么多在合成」即可。</p>
     */
    public static void startCraft(@Nullable final Network network, final List<Gap> gaps) {
        if (network == null) {
            return;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return;
        }
        for (final Gap gap : gaps) {
            final long amount = gap.craftableNow();
            if (amount <= 0L) {
                continue;
            }
            // ensureTask 必须携带非空取消令牌，否则 RS 内部会直接 NPE（同执行舱 / 结构加载器）
            autocrafting.ensureTask(gap.resource(), amount, Actor.EMPTY,
                new com.refinedmods.refinedstorage.api.network.impl.autocrafting
                    .TimeoutableCancellationToken());
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 「最多可合成」= {@code PreviewProvider#getMaxAmount} 的有界结果（异常 / 超时 ⇒ 0）。
     * <p>0 只意味着「这次问不出来」，调用方据此退回旧路径；绝不自己估算一个数。</p>
     */
    private static long maxCraftable(final Network network, final ResourceKey resource) {
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return 0L;
        }
        try {
            final Long max = autocrafting.getMaxAmount(resource, CancellationToken.NONE)
                .get(MAX_AMOUNT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return max == null ? 0L : Math.max(0L, max);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0L;
        } catch (final ExecutionException | TimeoutException exception) {
            return 0L;
        }
    }

    private static long saturatedAdd(final long left, final long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    // ==================== 语言键 ====================

    /** 「只发送现有」之后的一句说明（缺口另见订单提示）。 */
    public static final String MSG_SHORTAGE_SENT_ONLY =
        "gui.rs_create_compat.network_resource_requester.msg.shortage_sent_only";
    /** 「合成并发送」之后的回执（真正到货由 PendingSenderSends 补投）。 */
    public static final String MSG_SHORTAGE_CRAFTING =
        "gui.rs_create_compat.network_resource_requester.msg.shortage_crafting";
    /** 补投超时（10 分钟）放弃时的提示。 */
    public static final String MSG_SHORTAGE_GAVE_UP =
        "gui.rs_create_compat.network_resource_requester.msg.shortage_gave_up";
}
