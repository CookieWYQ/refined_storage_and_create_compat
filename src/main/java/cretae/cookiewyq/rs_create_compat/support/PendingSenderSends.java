package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 131 轮 F 组：**「合成后补投」的待办队列**。
 *
 * <h2>为什么需要它</h2>
 * <p>用户选「合成 25 并发送 75」时，合成是<b>异步</b>的：RS 要真的做出那 25 个，
 * 资源才会出现在网络里。因此「还差的量」必须登记下来，由服务端每 tick 看一次
 * 「网络里现在够不够」—— 够了就投给目标接收器。没有这个队列，玩家就会看到
 * 「按钮说的是发送 75，实际只到了 50，剩下的永远不来」。</p>
 *
 * <h2>两条硬规则（否则就是无限挂起 / 丢资源）</h2>
 * <ol>
 *     <li><b>凑齐即投</b>：每 tick 只投「现在真的有的量」（{@code min(还差, 网络存量)}），
 *     投出去多少就从「还差」里扣多少 —— 与 {@link SenderDelivery} 同一套「只按真的写进去的量递减」；</li>
 *     <li><b>超时放弃</b>：{@link #TIMEOUT_TICKS}（10 分钟）到了还没凑齐 ⇒ 丢掉这项并给一句提示，
 *     <b>绝不无限期挂着</b>。放弃的是「计划」，不是资源：资源还在网络里，一件都不会少。</li>
 * </ol>
 *
 * <h2>为什么不落盘</h2>
 * <p>它描述的是「合成任务还在跑」这一<b>当下的机器状态</b>，而合成任务本身不随方块存档。
 * 把过期的计划写进 NBT，读档后会变成「一个永远等不到货的补投任务」。不落盘的最坏结果是
 * 「重进存档后这次补投不做了」，资源仍在网络里（玩家再点一次发送即可）—— 不丢任何东西。</p>
 */
public final class PendingSenderSends {
    private PendingSenderSends() {
    }

    /** 补投超时：10 分钟（用户要求「超时 10 分钟放弃并提示」，绝不无限挂起）。 */
    public static final long TIMEOUT_TICKS = 20L * 60L * 10L;

    /**
     * 一条补投待办：目标接收器名字 + 还差多少（按资源）。
     * <p>{@code remaining} 是可变字段（每 tick 扣减），因此本类不是 record 而是普通类。</p>
     */
    public static final class PendingSend {
        private final String receiverName;
        private final ResourceKey resource;
        private long remaining;
        private final long deadlineGameTime;

        PendingSend(final String receiverName, final ResourceKey resource, final long remaining,
                    final long deadlineGameTime) {
            this.receiverName = receiverName;
            this.resource = resource;
            this.remaining = remaining;
            this.deadlineGameTime = deadlineGameTime;
        }

        public String receiverName() {
            return receiverName;
        }

        public ResourceKey resource() {
            return resource;
        }

        public long remaining() {
            return remaining;
        }

        public long deadlineGameTime() {
            return deadlineGameTime;
        }

        /** 还差多少 归零（投完 / 放弃时调用）。 */
        void finish() {
            remaining = 0L;
        }
    }

    /**
     * 把「可合成缺口」拆成补投待办。
     *
     * <p>登记的是<b>整个缺口</b>（不是「可合成的那一段」）：RS 的任务万一多做了 / 半路被取消，
     * 只要网络里凑齐就投；凑不齐就等超时。这样「合成多少」与「补投多少」不会互相拆台。</p>
     */
    public static void register(final ServerPlayer player, final SenderBackend backend,
                                final List<SenderShortage.Gap> gaps) {
        if (player == null || backend == null || gaps == null || gaps.isEmpty()) {
            return;
        }
        // 补投驱动器挂在请求器方块的 doWork 上（方块实体每 tick 都会跑，不需要新注册 ticker）。
        // 终端没有「方块 tick」，它的补投由此刻的界面上限兜底 —— 见交付说明里的已知限制。
        if (!(backend instanceof RequesterSenderBackend requesterBackend)) {
            return;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterBackend.blockEntity();
        if (requester.getLevel() == null) {
            return;
        }
        final long now = requester.getLevel().getGameTime();
        // 目标名字取「询问时那台接收器」的名字：补投必须投给同一台，绝不因为玩家中途换目标而改投
        requester.addPendingSends(withReceiver(backend.targetName(), create(gaps, now)));
    }

    /** 纯函数版（自检脚本可以直接复算）：把缺口拆成待办列表。 */
    public static List<PendingSend> create(final List<SenderShortage.Gap> gaps, final long now) {
        final List<PendingSend> out = new ArrayList<>();
        if (gaps == null) {
            return out;
        }
        for (final SenderShortage.Gap gap : gaps) {
            if (gap == null || gap.gap() <= 0L) {
                continue;
            }
            out.add(new PendingSend(null, gap.resource(), gap.gap(), now + TIMEOUT_TICKS));
        }
        return out;
    }

    /** 用目标名字重建待办（{@link #create} 不知道目标名，由调用方补上）。 */
    static List<PendingSend> withReceiver(final String receiverName,
                                          final List<PendingSend> sends) {
        final List<PendingSend> out = new ArrayList<>(sends.size());
        for (final PendingSend send : sends) {
            out.add(new PendingSend(receiverName, send.resource(), send.remaining(),
                send.deadlineGameTime()));
        }
        return out;
    }

    /**
     * 每 tick 的补投：凑齐即投、超时放弃。
     *
     * <p>只在 {@code requester.doWork()} 里调用（服务端、且已经有电量 / 网络判据在前），
     * 提示走方块实体既有的限频 hint（附近玩家可见）。</p>
     */
    public static void tick(final NetworkResourceRequesterBlockEntity requester,
                            final List<PendingSend> pending) {
        if (requester == null || pending == null || pending.isEmpty() || requester.getLevel() == null) {
            return;
        }
        final long now = requester.getLevel().getGameTime();
        final Network network = requester.getNode() == null
            ? null : requester.getNode().getNetworkOrNull();
        final StorageNetworkComponent storage =
            network == null ? null : network.getComponent(StorageNetworkComponent.class);
        pending.removeIf(send -> {
            if (send.receiverName() == null) {
                return true; // 没有目标名字的待办是坏数据（登记时一定带名字）
            }
            if (now >= send.deadlineGameTime() && send.remaining() > 0L) {
                requester.hint("shortage_gave_up", send.receiverName(),
                    SenderDelivery.describeResource(send.resource()));
                return true; // 超时：放弃计划（资源仍在网络里，一件不丢）
            }
            if (storage == null) {
                return false; // 断网 / 没电：先不做，计划原样留着（与 pendingPlan 同一哲学）
            }
            final long have = Math.max(0L, storage.get(send.resource()));
            if (have <= 0L) {
                return false;
            }
            final List<NetworkResourceReceiverBlockEntity> hits =
                SenderDelivery.resolveReceiversByName(network, send.receiverName());
            if (hits.size() != 1) {
                requester.hint(hits.isEmpty() ? "not_found" : "ambiguous", send.receiverName(),
                    send.receiverName());
                return true; // 目标没了 / 重名：计划作废（提示已给，绝不投错机器）
            }
            final NetworkResourceReceiverBlockEntity receiver = hits.get(0);
            final long wanted = Math.min(send.remaining(), have);
            final SenderDelivery.Result result = SenderDelivery.deliver(network,
                new SenderDelivery.ReceiverSink() {
                    @Override
                    public long apply(final ResourceKey resource, final long amount,
                                      final boolean simulate) {
                        return receiver.applyResource(resource, amount, simulate);
                    }

                    @Override
                    public boolean canHandle(final ResourceKey resource) {
                        return receiver.canHandle(resource);
                    }

                    @Override
                    public String displayName() {
                        return receiver.getReceiverDisplayName();
                    }
                },
                List.of(new ResourceAmount(send.resource(), wanted)),
                requester.getThrottleBatchesPerTick(), requester.getThrottleBatch(), 0,
                new SenderDelivery.Feedback() {
                    @Override
                    public void hint(final String reason, final Object... args) {
                        requester.hint(reason, args);
                    }

                    @Override
                    public void buffer(final ResourceKey resource, final long amount) {
                        requester.bufferReturned(resource, amount);
                    }
                });
            if (result.moved() > 0L) {
                send.remaining = Math.max(0L, send.remaining() - result.moved());
                requester.setChanged();
            }
            return send.remaining <= 0L;
        });
    }
}
