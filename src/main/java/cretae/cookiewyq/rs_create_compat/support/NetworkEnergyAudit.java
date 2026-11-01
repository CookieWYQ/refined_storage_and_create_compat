package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyProvider;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditNode;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditRequestPacket;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditType;
import cretae.cookiewyq.rs_create_compat.network.SyncEnergyAuditPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「RS 网络能耗监视」的<b>服务端权威</b>：枚举网络里所有耗电方块、聚合、截断、组快照。
 *
 * <h2>为什么不用自己维护「网络 → 容器表」（即上一版计划里的 NetworkImpl mixin）</h2>
 * <p>RS 2.0.9 的 {@code Network#addContainer / removeContainer} 确实只把调用转发给组件
 * （{@code api/network/impl/NetworkImpl.java}），但组件里有一个 <b>public API</b>
 * {@link GraphNetworkComponent#getContainers()}（{@code @API(status = STABLE, since = "2.0.0-milestone.3.2")}）——
 * 它就是 RS 自己的容器集合，而且 <b>RS 自己就拿它当权威</b>：{@code NetworkBuilderImpl} 的
 * {@code mergeNetworksOfNodes / remove / update} 都先取 {@code getComponent(GraphNetworkComponent.class).getContainers()}
 * 再判 {@code contains}，merge 时是「旧网络 removeContainer + 主网络 addContainer」逐个迁移，
 * split 时是新网络 {@code initialize} 重建。</p>
 * <p>⇒ 用官方 API 的收益：<b>零自建状态</b>（不存在 WeakHashMap 泄漏）、<b>零自愈代码</b>
 * （merge / split / remove 由 RS 维护）、<b>零 mixin</b>（少一个可能随 RS 版本错位的注入点）。
 * 代价：容器集合的形状由 RS 决定 —— 但我们要的正是它。</p>
 *
 * <h2>位置 / 维度 / 方块从哪来</h2>
 * <p>{@link InWorldNetworkNodeContainer}（public API）自带 {@code getPosition(): GlobalPos}（坐标 + 维度一起给）、
 * {@code getBlockState()}、{@code isRemoved()}。RS 世界里所有节点容器都由
 * {@code InWorldNetworkNodeContainerBuilder} 构造成 {@code InWorldNetworkNodeContainerImpl}（线缆 / 总线 /
 * 接口 / 检测器 / 自动合成仓 / 中继器 / 无线发射器…全部如此）。<b>拿不到位置的容器跳过并计数</b>，
 * 绝不让一个异常容器毁掉整份快照。</p>
 * <h2>耗电读数（current / nominal）</h2>
 * <p>{@code NetworkNode} 接口本身没有耗电方法，判 {@code instanceof AbstractNetworkNode} 才有
 * {@code getEnergyUsage()}。而 RS 真正每 tick 抽取的量是
 * {@code AbstractNetworkNode#doWork()} 里的 {@code if (network == null || !active) return; ... extract(getEnergyUsage())}
 * ⇒ <b>当前耗电 = {@code isActive() ? getEnergyUsage() : 0}</b>。本类同时给出标称值
 * （{@code getEnergyUsage()}），界面主数字用当前值、待机行标灰 —— <b>不自己乘任何系数</b>。
 * 作为「能量池提供方」的方块天然被排除：{@code ControllerNetworkNode#getEnergyUsage()} 返回 0，
 * 本模组 {@code FEEnergyCubeNetworkNode} 也显式返回 0，而本类只收 {@code > 0} 的。</p>
 *
 * <h2>跨维度（如实说明，不假装）</h2>
 * <p>一张 {@code Network} 的容器只可能来自<b>同一个维度</b>（连接在同一 level 的相邻方块间建立）；
 * RS 的跨维度是 transmitter / receiver <b>两张独立网络</b>互联。因此本类只列「终端绑定的这一张网络」，
 * 不会把对端维度的方块并进来。每条记录仍带维度（{@code GlobalPos.dimension()}），
 * 界面顶部也有对应提示语。</p>
 *
 * <h2>线程 / 生命周期</h2>
 * <p>只被 C2S 处理体（服务端主线程）调用；每玩家一份<b>瞬时</b>会话（返回模式 + 选中坐标 + 节流时间戳），
 * 下线即清（{@link #onLoggedOut}），界面关掉后客户端不再请求 ⇒ 不会常驻。
 * 本类不引用任何 {@code net.minecraft.client.*} 类型，专用服务端安全。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class NetworkEnergyAudit {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkEnergyAudit.class);

    /**
     * 两次快照之间的<b>服务端最小间隔</b>（tick）。
     * <p>只防「客户端狂发请求把服务端枚举网络变成 DoS」；正常路径是客户端每
     * {@code Config.energyAuditRefreshTicks}（默认 20）tick 一次，远大于它。
     * 选中 / 取消选中<b>不受</b>此限制（否则点一下要等两 tick 才看到框，手感很差）。</p>
     */
    private static final int MIN_SEND_INTERVAL_TICKS = 2;

    /** 坏容器告警的节流窗口（tick）：10 秒内最多一条，避免每秒一条刷屏。 */
    private static final int FAILURE_LOG_INTERVAL_TICKS = 200;
    /** 上次打「容器读取失败」告警的 gameTime（只在服务端主线程读写）。 */
    private static long lastFailureLogTick = Long.MIN_VALUE / 2L;

    /** 每玩家一份瞬时会话（只在服务端主线程读写）。 */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private NetworkEnergyAudit() {
    }

    /** 一局「能耗页开着」的服务端侧记录：返回模式 + 已校验的选中坐标 + 上次发包时刻。 */
    private static final class Session {
        private final int returnMode;
        @Nullable
        private ResourceLocation selectedDimension;
        @Nullable
        private BlockPos selectedPos;
        private long lastSentTick = Long.MIN_VALUE / 2L;

        private Session(final int returnMode) {
            this.returnMode = returnMode;
        }

        private boolean hasSelection() {
            return selectedDimension != null && selectedPos != null;
        }

        private void clearSelection() {
            this.selectedDimension = null;
            this.selectedPos = null;
        }
    }

    // ==================== 入口：切进能耗页 / 请求 / 清理 ====================

    /**
     * 打开能耗页（由 {@link AdvancedRemoteTerminalItem#openModeScreen} 的 {@code MODE_ENERGY} 分支调用）。
     *
     * <p>做三件事：① 记下「返回时要切回哪个模式」（{@code returnMode} 由切模式那一刻的调用方给出，
     * 不能在这里读物品 —— 此刻物品上的模式已经是 {@code MODE_ENERGY} 了）；② 关掉原来的容器菜单
     * （能耗页是纯 Screen、没有菜单，若不关，服务端会一直以为玩家还坐在旧菜单里）；
     * ③ 立刻下发第一份快照，客户端据此开界面（不用等客户端第一个 20 tick 请求）。</p>
     *
     * @param returnMode 「返回终端」应切回的模式号
     */
    public static void open(final ServerPlayer player, final int returnMode) {
        if (player == null || player.connection == null) {
            return;
        }
        final Session session = new Session(returnMode);
        SESSIONS.put(player.getUUID(), session);
        // 先关旧容器菜单：能耗页没有 Menu，服务端不该继续认为玩家在旧菜单里。
        // 关包（ClientboundContainerClosePacket）→ 本包的快照（S2C）在同一条有序连接上，客户端先关后开。
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        send(player, session, true);
    }

    /** 结束会话（下线 / 主动返回 / 终端没了）。重复调用是空操作。 */
    public static void stop(@Nullable final ServerPlayer player) {
        if (player != null) {
            SESSIONS.remove(player.getUUID());
        }
    }

    /** 客户端请求：刷新 / 选中 / 取消选中（服务端<b>重新枚举并校验</b>后回快照）。 */
    public static void handleRequest(final ServerPlayer player, final EnergyAuditRequestPacket packet) {
        if (player == null || player.connection == null) {
            return;
        }
        final Session session = SESSIONS.computeIfAbsent(player.getUUID(),
            uuid -> new Session(AdvancedRemoteTerminalItem.MODE_GRID));
        final boolean force = switch (packet.action()) {
            case EnergyAuditRequestPacket.ACTION_SELECT -> {
                // 只登记「客户端想选哪里」；是否真的存在于**这一份下发的快照**里，由 buildPacket 校验
                session.selectedDimension = packet.dimension();
                session.selectedPos = packet.pos();
                yield true;
            }
            case EnergyAuditRequestPacket.ACTION_CLEAR -> {
                session.clearSelection();
                yield true;
            }
            default -> false;
        };
        send(player, session, force);
    }

    /** 下线清理：会话不跨连接 / 不跨存档存活。 */
    @SubscribeEvent
    public static void onLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SESSIONS.remove(player.getUUID());
        }
    }

    // ==================== 组包（唯一权威口径） ====================

    /**
     * 生成一份快照并发给玩家。
     *
     * @param force true = 跳过最小间隔节流（选中 / 取消 / 首次打开）
     */
    private static void send(final ServerPlayer player, final Session session, final boolean force) {
        final long gameTime = player.level().getGameTime();
        if (!force && gameTime - session.lastSentTick < MIN_SEND_INTERVAL_TICKS) {
            return;
        }
        session.lastSentTick = gameTime;
        PacketDistributor.sendToPlayer(player, snapshot(player, session));
    }

    /**
     * 组一份权威快照（<b>唯一实现</b>；界面看到的每个数字都来自这里）。
     *
     * <p>顺序：解析终端绑定的网络 → 枚举容器 → 逐容器取「位置 + 方块 + 当前/标称耗电」→
     * 按坐标合并（同一坐标的多个节点求和，RS 的中继器就是这样）→ 按当前耗电降序 →
     * 按 Config 的节点 / 类型上限截断（<b>不留半条类型</b>）→ 按方块 id 聚合成类型行。</p>
     */
    private static SyncEnergyAuditPacket snapshot(final ServerPlayer player, final Session session) {
        final int returnMode = session.returnMode;
        final Optional<RsccTerminalLocator.Location> located = RsccTerminalLocator.locate(player);
        if (located.isEmpty()) {
            // 终端被搬走 / 被销毁：没有网络可谈，客户端显示「未连接」空状态（不崩、不糊旧数据）
            session.clearSelection();
            return SyncEnergyAuditPacket.empty(returnMode);
        }
        final ItemStack stack = located.get().stack();
        // 「终端 → 网络」的唯一解析口径（与打开模式界面共用；未绑定 / 区块未加载 / 未成形 ⇒ empty）
        final Optional<Network> networkOpt = AdvancedRemoteTerminalItem.resolveBoundNetwork(player, stack);
        if (networkOpt.isEmpty()) {
            session.clearSelection();
            return SyncEnergyAuditPacket.empty(returnMode);
        }
        final Network network = networkOpt.get();
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        if (graph == null) {
            // 网络存在但没有图组件（未成形 / 异常实现）⇒ 按空处理，绝不抛异常
            session.clearSelection();
            return SyncEnergyAuditPacket.empty(returnMode);
        }
        final EnergyNetworkComponent energy = network.getComponent(EnergyNetworkComponent.class);
        final long stored = energy == null ? 0L : Math.max(0L, energy.getStored());
        final long capacity = energy == null ? 0L : Math.max(0L, energy.getCapacity());

        final Aggregation aggregation = aggregate(graph, player.level().getGameTime());
        return buildPacket(aggregation, session, stored, capacity, returnMode);
    }

    /** 聚合结果（中间模型；只在服务端存在，不上网络）。 */
    private static final class Aggregation {
        /** 按坐标合并后的<b>全量</b>方块（已按当前耗电降序）。 */
        private final List<BlockEntry> blocks = new ArrayList<>();
        /** 因为「拿不到位置」被跳过的容器数（诊断用，不上界面）。 */
        private int skippedWithoutPosition;
    }

    /** 一个世界坐标上的合计（同一坐标可能有多个节点，例如 RS 中继器 input / output）。 */
    private static final class BlockEntry {
        private final ResourceLocation dimension;
        private final BlockPos pos;
        private final ResourceLocation blockId;
        @Nullable
        private final BlockState state;
        private long currentUsage;
        private long nominalUsage;
        private boolean active;

        private BlockEntry(final ResourceLocation dimension, final BlockPos pos,
                           final ResourceLocation blockId, @Nullable final BlockState state) {
            this.dimension = dimension;
            this.pos = pos;
            this.blockId = blockId;
            this.state = state;
        }

        private String key() {
            return EnergyAuditNode.keyOf(dimension, pos);
        }
    }

    /**
     * 枚举 + 合并 + 排序（截断前的全量）。
     * <p>每个容器单独 try/catch：某个容器实现抛异常时只跳过它并计数，绝不让整份快照失败。</p>
     */
    private static Aggregation aggregate(final GraphNetworkComponent graph, final long gameTime) {
        final Aggregation result = new Aggregation();
        final Map<String, BlockEntry> merged = new LinkedHashMap<>();
        int failed = 0;
        for (final NetworkNodeContainer container : graph.getContainers()) {
            try {
                // 位置 / 维度 / 方块只能从 InWorld 容器拿；拿不到就跳过并计数（绝不猜坐标）
                if (!(container instanceof InWorldNetworkNodeContainer world)) {
                    result.skippedWithoutPosition++;
                    continue;
                }
                if (world.isRemoved()) {
                    continue;
                }
                final NetworkNode node = container.getNode();
                // provider 侧（控制器 / 能量立方 / 其它会往网络能量池供电的方块）不是耗电方：
                // 它们只「供」不「耗」。这里用 RS 的公开接口 {@link EnergyProvider} 作结构判据，
                // 而不是依赖「它的 getEnergyUsage() 碰巧是 0」—— 后者一旦某个 provider 实现改了读数
                // （例如未来版本为了显示自身损耗而返回非 0），它就会莫名其妙出现在耗电榜上。
                if (node instanceof EnergyProvider) {
                    continue;
                }
                // 只有 AbstractNetworkNode 才有耗电读数；其它实现（理论上不存在于 RS 本体）
                // 一律跳过 —— 不猜、不用反射兜底。
                if (!(node instanceof AbstractNetworkNode networkNode)) {
                    continue;
                }
                final long nominal = networkNode.getEnergyUsage();
                if (nominal <= 0L) {
                    // 不耗电的节点（线缆 / 控制器 / 能量池提供方）不进列表。
                    // 控制器与能量立方都靠 getEnergyUsage() == 0 天然落在这一支，不需要额外判据。
                    continue;
                }
                final GlobalPos position = world.getPosition();
                final BlockState state = world.getBlockState();
                if (position == null || state == null) {
                    result.skippedWithoutPosition++;
                    continue;
                }
                final ResourceLocation dimension = position.dimension().location();
                final ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                final BlockEntry entry = merged.computeIfAbsent(
                    EnergyAuditNode.keyOf(dimension, position.pos()),
                    key -> new BlockEntry(dimension, position.pos(), blockId, state));
                // 同坐标多节点：逐节点求和（每个节点各自 doWork 各抽一份，这是真实抽取量）
                entry.nominalUsage += nominal;
                if (networkNode.isActive()) {
                    entry.currentUsage += nominal;
                    entry.active = true;
                }
            } catch (final Throwable failure) {
                failed++;
            }
        }
        if (failed > 0 && gameTime - lastFailureLogTick >= FAILURE_LOG_INTERVAL_TICKS) {
            // 节流：客户端每秒要一份快照，一个坏容器不该把日志刷成一秒一条。
            lastFailureLogTick = gameTime;
            LOGGER.warn("[rscc-energy-audit] {} network node container(s) failed to read, skipped"
                + " (unpositioned: {})", failed, result.skippedWithoutPosition);
        }
        result.blocks.addAll(merged.values());
        // 全局排序：当前耗电降序 → 标称降序 → 坐标键（保证同分时顺序稳定，界面不会每秒乱跳）
        result.blocks.sort(Comparator
            .comparingLong((BlockEntry entry) -> entry.currentUsage).reversed()
            .thenComparing(Comparator.comparingLong((BlockEntry entry) -> entry.nominalUsage).reversed())
            .thenComparing(BlockEntry::key));
        return result;
    }

    /** 按 Config 上限截断 + 按方块 id 聚合 + 填充协议字段。 */
    private static SyncEnergyAuditPacket buildPacket(final Aggregation aggregation,
                                                     final Session session,
                                                     final long stored,
                                                     final long capacity,
                                                     final int returnMode) {
        final List<BlockEntry> all = aggregation.blocks;
        final int totalBlocks = all.size();
        final long totalCurrent = sumCurrent(all);
        final long totalNominal = sumNominal(all);

        // 去重后的类型数（全量）
        final Map<ResourceLocation, Long> totalsByType = new LinkedHashMap<>();
        for (final BlockEntry entry : all) {
            totalsByType.merge(entry.blockId, 1L, Long::sum);
        }
        final int totalTypes = totalsByType.size();

        final int maxNodes = Math.max(8, Config.energyAuditMaxNodes);
        final int maxTypes = Math.max(8, Config.energyAuditMaxTypes);

        // ① 先按「节点上限」截取（列表已按当前耗电降序 ⇒ 留下的一定是最耗电的那些）
        final List<BlockEntry> selectedBlocks = all.subList(0, Math.min(totalBlocks, maxNodes));
        // ② 再按方块 id 聚合（保持「最耗电类型在前」：按该类型当前合计降序重排）
        final Map<ResourceLocation, List<BlockEntry>> grouped = new LinkedHashMap<>();
        for (final BlockEntry entry : selectedBlocks) {
            grouped.computeIfAbsent(entry.blockId, key -> new ArrayList<>()).add(entry);
        }
        final List<EnergyAuditType> types = new ArrayList<>(grouped.size());
        for (final Map.Entry<ResourceLocation, List<BlockEntry>> group : grouped.entrySet()) {
            final List<BlockEntry> members = group.getValue();
            final List<EnergyAuditNode> nodes = new ArrayList<>(members.size());
            long typeCurrent = 0L;
            long typeNominal = 0L;
            for (final BlockEntry member : members) {
                nodes.add(new EnergyAuditNode(member.dimension, member.pos,
                    member.currentUsage, member.nominalUsage, member.active));
                typeCurrent += member.currentUsage;
                typeNominal += member.nominalUsage;
            }
            types.add(new EnergyAuditType(group.getKey(), iconOf(members.get(0)),
                members.size(), typeCurrent, typeNominal, nodes));
        }
        types.sort(Comparator
            .comparingLong(EnergyAuditType::currentUsage).reversed()
            .thenComparing(Comparator.comparingLong(EnergyAuditType::nominalUsage).reversed())
            .thenComparing(type -> type.blockId().toString()));

        // ③ 类型上限：整类丢弃（绝不发一条 0 个子项 / 只有一半的「半条」）
        int hiddenTypes = totalTypes - types.size();
        int includedTypes = types.size();
        List<EnergyAuditType> sentTypes = types;
        if (types.size() > maxTypes) {
            sentTypes = new ArrayList<>(types.subList(0, maxTypes));
            hiddenTypes = totalTypes - maxTypes;
            includedTypes = maxTypes;
        }
        int includedBlocks = 0;
        for (final EnergyAuditType type : sentTypes) {
            includedBlocks += type.nodes().size();
        }
        final int hiddenBlocks = Math.max(0, totalBlocks - includedBlocks);

        // 选中校验（放在这里而不是 aggregate 之后：判据是「**这一份快照真的下发了这个坐标**」，
        // 不是「网络里此刻有这台方块」—— 否则截断之外的节点会得到「已选中但没有对应行」的鬼状态）。
        if (session.hasSelection()) {
            final String key = EnergyAuditNode.keyOf(session.selectedDimension, session.selectedPos);
            boolean present = false;
            for (final EnergyAuditType type : sentTypes) {
                for (final EnergyAuditNode node : type.nodes()) {
                    if (node.key().equals(key)) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    break;
                }
            }
            if (!present) {
                session.clearSelection();
            }
        }

        final boolean hasSelection = session.hasSelection();
        return new SyncEnergyAuditPacket(true, stored, capacity, totalCurrent, totalNominal,
            totalBlocks, totalTypes, hiddenBlocks, hiddenTypes, returnMode,
            hasSelection, session.selectedDimension, session.selectedPos, sentTypes);
    }

    private static long sumCurrent(final List<BlockEntry> blocks) {
        long total = 0L;
        for (final BlockEntry entry : blocks) {
            total += entry.currentUsage;
        }
        return total;
    }

    private static long sumNominal(final List<BlockEntry> blocks) {
        long total = 0L;
        for (final BlockEntry entry : blocks) {
            total += entry.nominalUsage;
        }
        return total;
    }

    /** 类型行的图标：方块对应物品的 {@link ItemStack}（方块没有对应物品时为 {@link ItemStack#EMPTY}）。 */
    private static ItemStack iconOf(final BlockEntry entry) {
        try {
            final BlockState state = entry.state;
            if (state == null) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(state.getBlock().asItem());
        } catch (final Throwable failure) {
            return ItemStack.EMPTY;
        }
    }

    // ==================== 诊断（给自检 / 日志用，不参与界面） ====================

    /** 该玩家当前是否有能耗页会话（诊断用；界面显示不依赖它）。 */
    public static boolean hasSession(final UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }
}
