package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccResourceKeys;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：网络资源请求器的<b>权威配置快照</b>（目标名 + 已选资源 + 本网络接收器列表 + 连通性 + 操作结果）。
 *
 * <h2>只在变更时下发</h2>
 * <p>菜单首次 {@code broadcastChanges}、目标被接受 / 拒绝、已选资源每次增删改之后、
 * 以及「请求器接入 / 断开网络」时各发一次；界面每帧只读这份快照，绝不在渲染循环里拼数据（性能硬要求）。</p>
 *
 * <h2>网格界面也读它</h2>
 * <p>网格（{@code client/grid/ResourcePickerScreen}）在 {@code containerTick} 里通过
 * {@link #takeFor(int)} 取走「给自己这一份」的最近快照 —— 因此「已选资源」永远显示服务端真值，
 * 客户端不保存任何可写副本。</p>
 *
 * <h2>接收器列表（界面从「手打名字」改成「从列表里挑」）</h2>
 * <p>{@link ReceiverView} 是「本网络内所有已命名接收器的<b>只读</b>视图」：名字 + 坐标。
 * 它只是让玩家<b>看得见、点得到</b>候选，提交给服务端的仍然只有<b>名字</b>（存储层不变：
 * 请求器 NBT 里存的就是 {@code targetName}）。因此本类的列表字段<b>不</b>携带任何方块实体 /
 * 网络对象引用，客户端也无法凭它改写配置。</p>
 *
 * <h2>为什么订单条目要拆成 {@link OrderView}</h2>
 * <p>服务端存的是 RS 的 {@link ResourceAmount}（资源键 + long 数量），容器同步通道
 * （{@code ContainerData}）只能传 int，传不了资源键。这里把每条压成
 * 「显示用字符串 + 是否流体 + 数量 + <b>完整资源键</b>」—— 客户端只需要<b>显示</b>；
 * 所有写入都走 {@link RequesterOrderPacket} 的「动作 + 下标 + 资源键」，客户端永远不能凭这份快照
 * 直接改任何配置。</p>
 *
 * <h2>资源身份：完整的 {@link PlatformResourceKey}</h2>
 * <p>{@link OrderView#key()} 用 RS 自己的 {@link ResourceCodecs#STREAM_CODEC} 编解码，
 * 因此订单里可以出现任意第三方资源（如装了官方 Mekanism 联动后的气体）；
 * 旧界面只读 {@code id} / {@code fluid} / {@code amount}，行为不变。
 * 第三方类型没有「注册名」可显示，{@code id} 给的是 {@link RsccResourceKeys} 生成的稳定令牌
 * （可原样传回服务端做二次确认）。</p>
 *
 * <h2>为什么还带「最近一轮投递的账」</h2>
 * <p>第 95 轮用户要求请求器界面真的显示「已投 / 剩余 / 缺口」。这三个数是<b>服务端投递事实</b>
 * （{@code NetworkResourceRequesterBlockEntity} 的 round 账），客户端重建菜单时没有方块实体，
 * 自算必然分叉；而 {@code ContainerData} 只能传 int，订单量是 long ⇒ 只能随本快照下发。
 * 客户端拿到的只是<b>显示值</b>，不参与任何写入 / 校验。</p>
 *
 * <p>{@code roundRemaining} 由服务端算好（{@code planned - delivered - shortfall}，已夹 ≥ 0），
 * 不让客户端做算术：免得两边对「一轮」的定义漂移。{@code roundActive} = 还在跨 tick 续投。</p>
 *
 * <h2>为什么还带节流数值</h2>
 * <p>{@code pacingBatchesPerTick} / {@code pacingBatch} 是<b>网格主界面</b>顶部工具栏要显示的
 * 「每 tick 批数 / 单批量」。网格菜单（{@code AbstractGridContainerMenu}）与请求器菜单是两张
 * 不同的菜单：网格那边没有本模组的 {@code ContainerData}（它只有 RS 的 {@code GridData}），
 * 因此这两个数只能随本快照下发。它们与请求器界面的 {@code ContainerData} 索引 0 / 1 是
 * <b>同一个服务端来源</b>（{@code RsccSpeedStackUpgradeContainer} 经方块实体 getter），
 * 客户端一个字都不算。数值一律走既有节流工具，本类不引入任何公式。</p>
 *
 * <h2>dist 安全</h2>
 * <p>本类的工厂 {@link #of} 在<b>服务端</b>调用（读方块实体），处理体只调 {@link ClientPayloadHooks}；
 * 全类没有 {@code net.minecraft.client.*} 引用。</p>
 */
public record SyncRequesterConfigPacket(int containerId, String target, List<OrderView> orders,
                                        List<ReceiverView> receivers, String messageKey,
                                        boolean lastSuccess, int focusOrder,
                                        boolean networkConnected,
                                        int pacingBatchesPerTick, long pacingBatch,
                                        long roundPlanned, long roundDelivered,
                                        long roundRemaining, long roundShortfall,
                                        boolean roundActive)
    implements CustomPacketPayload {

    /**
     * 单台接收器的只读显示视图（<b>不是</b>数据模型，也不含任何方块实体引用）。
     *
     * <p>为什么带坐标：同名 / 改过名的历史存档里，光看名字分不清「哪一台」，
     * 而玩家在机器旁边也只认坐标。视图因此 = <b>名字 + 坐标</b>。</p>
     *
     * <p>为什么不带维度：RS 的网络图<b>按维度隔离</b>（一个 {@code Network} 只含同一
     * {@code ServerLevel} 的节点），候选必定与本请求器同维度 ⇒ 坐标足以唯一定位。</p>
     *
     * <p>提交语义：客户端选中后用 {@link SetRequesterTargetPacket} 只把 {@link #name()}
     * 发回服务端（存储层不变），坐标<b>不</b>参与写入 —— 服务端仍然按名字在本网络里现解析，
     * 因此「选中时看到的那台」与「投递时命中的那台」由服务端的存在性 / 重名校验保证一致。</p>
     */
    public record ReceiverView(String name, int x, int y, int z) {
    }

    /**
     * 单条订单的显示视图（不是数据模型本身）。
     *
     * @param id     旧界面的资源标识（物品 / 流体 = 注册名；第三方类型 = 稳定令牌）
     * @param fluid  true = 流体（数量单位 mB）；第三方类型一律 false
     * @param amount 原始数量（物品=个 / 流体=mB / 第三方类型由该类型自己定义）
     * @param key    完整资源键（<b>权威标识</b>）
     */
    public record OrderView(String id, boolean fluid, long amount,
                            @Nullable PlatformResourceKey key) {
        /** 兼容旧调用：没有完整键（只有显示层用得到）。 */
        public OrderView(final String id, final boolean fluid, final long amount) {
            this(id, fluid, amount, null);
        }
    }

    public static final Type<SyncRequesterConfigPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_requester_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequesterConfigPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.target() == null ? "" : packet.target());
                ByteBufCodecs.VAR_INT.encode(buf, packet.orders().size());
                for (final OrderView view : packet.orders()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, view.id() == null ? "" : view.id());
                    ByteBufCodecs.BOOL.encode(buf, view.fluid());
                    ByteBufCodecs.VAR_LONG.encode(buf, view.amount());
                    final PlatformResourceKey key = view.key();
                    ByteBufCodecs.BOOL.encode(buf, key != null);
                    if (key != null) {
                        ResourceCodecs.STREAM_CODEC.encode(buf, key);
                    }
                }
                ByteBufCodecs.VAR_INT.encode(buf, packet.receivers().size());
                for (final ReceiverView view : packet.receivers()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, view.name() == null ? "" : view.name());
                    // 坐标用 VAR_INT（负数坐标是常态；MC 的 VarInt 编码本身支持负数）
                    ByteBufCodecs.VAR_INT.encode(buf, view.x());
                    ByteBufCodecs.VAR_INT.encode(buf, view.y());
                    ByteBufCodecs.VAR_INT.encode(buf, view.z());
                }
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.messageKey() == null ? "" : packet.messageKey());
                ByteBufCodecs.BOOL.encode(buf, packet.lastSuccess());
                // 焦点条目下标（-1 = 不指定）：网格里刚加入的那一条在已选清单中的位置
                ByteBufCodecs.VAR_INT.encode(buf, packet.focusOrder());
                // 是否真的接在可用的 RS 网络上（客户端因此能直接把「打开网格」按钮变灰）
                ByteBufCodecs.BOOL.encode(buf, packet.networkConnected());
                // 节流数值（网格工具栏的「每 tick 批数 / 单批量」只读这里：
                // 网格菜单与请求器菜单是两张不同的菜单，GridData 不带 ContainerData）
                ByteBufCodecs.VAR_INT.encode(buf, packet.pacingBatchesPerTick());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.pacingBatch());
                // 最近一轮投递的账（显示用；long 因为订单量是 long，ContainerData 传不了）
                ByteBufCodecs.VAR_LONG.encode(buf, packet.roundPlanned());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.roundDelivered());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.roundRemaining());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.roundShortfall());
                ByteBufCodecs.BOOL.encode(buf, packet.roundActive());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final String target = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int orderCount = ByteBufCodecs.VAR_INT.decode(buf);
                final List<OrderView> orders = new ArrayList<>(Math.max(0, orderCount));
                for (int i = 0; i < orderCount; i++) {
                    final String id = ByteBufCodecs.STRING_UTF8.decode(buf);
                    final boolean fluid = ByteBufCodecs.BOOL.decode(buf);
                    final long amount = ByteBufCodecs.VAR_LONG.decode(buf);
                    final PlatformResourceKey key = ByteBufCodecs.BOOL.decode(buf)
                        ? ResourceCodecs.STREAM_CODEC.decode(buf)
                        : null;
                    orders.add(new OrderView(id, fluid, amount, key));
                }
                final int receiverCount = ByteBufCodecs.VAR_INT.decode(buf);
                final List<ReceiverView> receivers = new ArrayList<>(Math.max(0, receiverCount));
                for (int i = 0; i < receiverCount; i++) {
                    final String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    final int x = ByteBufCodecs.VAR_INT.decode(buf);
                    final int y = ByteBufCodecs.VAR_INT.decode(buf);
                    final int z = ByteBufCodecs.VAR_INT.decode(buf);
                    receivers.add(new ReceiverView(name, x, y, z));
                }
                final String messageKey = ByteBufCodecs.STRING_UTF8.decode(buf);
                final boolean lastSuccess = ByteBufCodecs.BOOL.decode(buf);
                final int focusOrder = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean networkConnected = ByteBufCodecs.BOOL.decode(buf);
                final int pacingBatchesPerTick = ByteBufCodecs.VAR_INT.decode(buf);
                final long pacingBatch = ByteBufCodecs.VAR_LONG.decode(buf);
                final long roundPlanned = ByteBufCodecs.VAR_LONG.decode(buf);
                final long roundDelivered = ByteBufCodecs.VAR_LONG.decode(buf);
                final long roundRemaining = ByteBufCodecs.VAR_LONG.decode(buf);
                final long roundShortfall = ByteBufCodecs.VAR_LONG.decode(buf);
                final boolean roundActive = ByteBufCodecs.BOOL.decode(buf);
                return new SyncRequesterConfigPacket(containerId, target, List.copyOf(orders),
                    List.copyOf(receivers), messageKey, lastSuccess, focusOrder, networkConnected,
                    pacingBatchesPerTick, pacingBatch,
                    roundPlanned, roundDelivered, roundRemaining, roundShortfall, roundActive);
            }
        );

    /**
     * 服务端工厂：把方块实体里的权威配置压成可下发的快照。
     *
     * <p>每一条都带上完整资源键（物品 / 流体 / 第三方类型一视同仁）。第三方类型的 {@code id}
     * 用稳定令牌（{@link RsccResourceKeys#token}），因此旧界面也能显示、也能原样传回来做增删改的
     * 二次确认；令牌编不出来时退回 {@code String.valueOf(resource)} —— 但<b>绝不让显示视图比真实清单短一条</b>
     * （客户端用下标做增删改，少一条就会让后面的所有下标错位）。</p>
     *
     * @param receivers 本网络已命名接收器的只读视图（名字 + 坐标），由菜单的既有网络枚举给出；
     *                  <b>本类不自己去扫网络</b>（枚举口径只能有一处）
     * @param networkConnected 请求器此刻是否真的接在可用的 RS 网络上（由<b>菜单</b>算好传进来：
     *                  {@code network/**} 不许引用 client 包里的连通性判据）
     */
    public static SyncRequesterConfigPacket of(final int containerId,
                                              final NetworkResourceRequesterBlockEntity requester,
                                              final List<ReceiverView> receivers,
                                              final String messageKey,
                                              final boolean lastSuccess,
                                              final int focusOrder,
                                              final boolean networkConnected) {
        final HolderLookup.Provider registries = requester.getLevel() == null
            ? null : requester.getLevel().registryAccess();
        final List<OrderView> views = new ArrayList<>();
        for (final ResourceAmount entry : requester.getOrders()) {
            final ResourceKey resource = entry.resource();
            if (!(resource instanceof PlatformResourceKey key)) {
                // 保底：绝不让显示视图比真实清单短一条（这种键 setOrders 已经过滤，正常不会出现）
                views.add(new OrderView(String.valueOf(resource), false, entry.amount(), null));
                continue;
            }
            final String id;
            final boolean fluid;
            if (key instanceof ItemResource item) {
                id = idOf(BuiltInRegistries.ITEM.getKey(item.item()));
                fluid = false;
            } else if (key instanceof FluidResource fluidKey) {
                id = idOf(BuiltInRegistries.FLUID.getKey(fluidKey.fluid()));
                fluid = true;
            } else {
                // 第三方类型（气体等）：没有注册名可显示 ⇒ 用稳定令牌（可原样传回）
                final String token = RsccResourceKeys.token(key, registries);
                id = token == null ? String.valueOf(resource) : token;
                fluid = false;
            }
            views.add(new OrderView(id, fluid, entry.amount(), key));
        }
        return new SyncRequesterConfigPacket(containerId, requester.getTargetName(),
            List.copyOf(views), List.copyOf(receivers),
            messageKey == null ? "" : messageKey, lastSuccess, focusOrder, networkConnected,
            requester.getThrottleBatchesPerTick(), requester.getThrottleBatch(),
            requester.getRoundPlanned(), requester.getRoundDelivered(),
            requester.getRoundRemaining(), requester.getRoundShortfall(),
            requester.isRoundActive());
    }

    private static String idOf(final ResourceLocation id) {
        return id == null ? "" : id.toString();
    }

    /**
     * 客户端「最近收到的一份快照」信箱（<b>只在客户端主线程读写</b>）。
     *
     * <p>为什么需要它：S2C 的处理器统一走 {@code ClientPayloadHooks}（专用服务端安全的硬约束），
     * 而那个间接层只把快照交给请求器界面。网格界面同样需要「服务端权威的已选清单」，
     * 又不能改那个间接层（越界）。因此这里在 {@code handle} 里多存一份最近快照，
     * 由网格界面每 tick 用 {@link #takeFor(int)} 取走 —— 与工程里既有的
     * {@code SyncChamberUnitsPacket#getLastReceived()} 是同一套「最近快照信箱」写法。</p>
     *
     * <p>快照是完整覆盖（不是增量），所以「两次编辑之间只保留最后一份」在语义上完全等价。</p>
     */
    @Nullable
    private static volatile SyncRequesterConfigPacket lastReceived;

    /**
     * 取走「给 {@code containerId} 这一份」的最近快照（没有 ⇒ {@code null}；取走后清空）。
     * <p>只由客户端主线程调用（{@code containerTick}），因此不需要锁。</p>
     */
    @Nullable
    public static SyncRequesterConfigPacket takeFor(final int containerId) {
        final SyncRequesterConfigPacket packet = lastReceived;
        if (packet == null || packet.containerId() != containerId) {
            return null;
        }
        lastReceived = null;
        return packet;
    }

    public static void handle(final SyncRequesterConfigPacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 先投信箱，再走既有分发：两件事都只写客户端只读镜像，不会形成回环
            lastReceived = packet;
            ClientPayloadHooks.get().syncNetworkResourceRequester(packet);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
