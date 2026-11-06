package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.common.grid.GridData;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：网络资源接收器的<b>权威快照</b>（第 132 轮：资源列表改由 {@link GridData} 承载）。
 *
 * <h2>为什么还需要这个包</h2>
 * <p>界面已换成 RS 自己的 {@code GridScreen}：资源网格、搜索、排序、滚动、数量角标、tooltip、
 * 点击取出全部由 RS 负责。但 RS 的网格只认识「资源」——<b>名字 / 已占用名字集合 / 改名结果 /
 * 输出朝向 / 状态</b>这些接收器 extras 它不认识，必须继续由本包下发。</p>
 *
 * <h2>为什么带 {@link GridData}</h2>
 * <p>客户端菜单在 {@code MenuType} 的普通构造器里创建时拿不到服务端的 {@code Grid}；
 * 本包把 {@link GridData#of} 生成的完整网格镜像（active + 资源 + 可合成集合）随第一次同步发下去，
 * 客户端 {@code NetworkResourceReceiverMenu#applyClientGridSnapshot(GridData)} 用
 * {@code AbstractGridContainerMenu#onResourceUpdate} 逐条灌进同一个 RS 资源仓库。
 * 之后的增量变化走 RS 自己的 {@code GridUpdatePacket}（菜单 {@code onChanged}），本包只在
 * 「打开 / 改名 / active 变化」时补全量，避免每秒重复发整张表。</p>
 *
 * <h2>线格式</h2>
 * <p>字段全部是双端都有的类型（{@code GridData} 的 codec 在 RS common 包里），
 * 处理体只经 {@link ClientPayloadHooks} 间接层，专用服务端不会触碰客户端类。</p>
 */
public record SyncReceiverConfigPacket(int containerId,
                                       String name,
                                       List<String> occupiedNames,
                                       String messageKey,
                                       boolean configured,
                                       boolean replaceGrid,
                                       int itemTypes,
                                       long itemTotal,
                                       int fluidTypes,
                                       int fluidTotal,
                                       int energyUsage,
                                       Direction facing,
                                       GridData gridData)
    implements CustomPacketPayload {

    public static final Type<SyncReceiverConfigPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_receiver_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncReceiverConfigPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.name() == null ? "" : packet.name());
                ByteBufCodecs.VAR_INT.encode(buf, packet.occupiedNames().size());
                for (final String occupied : packet.occupiedNames()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, occupied == null ? "" : occupied);
                }
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.messageKey() == null ? "" : packet.messageKey());
                ByteBufCodecs.BOOL.encode(buf, packet.configured());
                ByteBufCodecs.BOOL.encode(buf, packet.replaceGrid());
                ByteBufCodecs.VAR_INT.encode(buf, packet.itemTypes());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.itemTotal());
                ByteBufCodecs.VAR_INT.encode(buf, packet.fluidTypes());
                ByteBufCodecs.VAR_INT.encode(buf, packet.fluidTotal());
                ByteBufCodecs.VAR_INT.encode(buf, packet.energyUsage());
                // 6 向朝向上限极小：用序号编码，客户端按 Direction.values() 还原（顺序是枚举定义顺序）
                ByteBufCodecs.VAR_INT.encode(buf, packet.facing().ordinal());
                GridData.STREAM_CODEC.encode(buf, packet.gridData());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int size = ByteBufCodecs.VAR_INT.decode(buf);
                final List<String> occupied = new ArrayList<>(Math.max(0, size));
                for (int i = 0; i < size; i++) {
                    occupied.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                final String messageKey = ByteBufCodecs.STRING_UTF8.decode(buf);
                final boolean configured = ByteBufCodecs.BOOL.decode(buf);
                final boolean replaceGrid = ByteBufCodecs.BOOL.decode(buf);
                final int itemTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final long itemTotal = ByteBufCodecs.VAR_LONG.decode(buf);
                final int fluidTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final int fluidTotal = ByteBufCodecs.VAR_INT.decode(buf);
                final int energyUsage = ByteBufCodecs.VAR_INT.decode(buf);
                final int facingOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
                final Direction[] directions = Direction.values();
                final Direction facing = facingOrdinal >= 0 && facingOrdinal < directions.length
                    ? directions[facingOrdinal] : Direction.NORTH;
                final GridData gridData = GridData.STREAM_CODEC.decode(buf);
                return new SyncReceiverConfigPacket(containerId, name, List.copyOf(occupied),
                    messageKey, configured, replaceGrid, itemTypes, itemTotal, fluidTypes, fluidTotal,
                    energyUsage, facing, gridData);
            }
        );

    /**
     * 服务端工厂：把接收器的权威状态 + 网格镜像压成可下发的快照。
     *
     * @param occupiedNames 本网络已占用的名字（由菜单缓存，只在改名时重算 —— 不在这里扫网络图）
     * @param gridData      {@code GridData.of(ReceiverGrid)} 的结果（active + 全部资源）
     * @param replaceGrid   {@code true} = 客户端清空资源仓库后按 {@code gridData} 重建；
     *                      {@code false} = 只更新 extras / active，资源继续走 RS 的增量包
     *                      （避免「改名时重发全量」与「已经排队的增量」在客户端叠两次）
     */
    public static SyncReceiverConfigPacket of(final int containerId,
                                              final NetworkResourceReceiverBlockEntity receiver,
                                              final List<String> occupiedNames,
                                              final String messageKey,
                                              final GridData gridData,
                                              final boolean replaceGrid) {
        return new SyncReceiverConfigPacket(containerId, receiver.getReceiverName(),
            List.copyOf(occupiedNames), messageKey == null ? "" : messageKey,
            receiver.isProperlyConfigured(), replaceGrid,
            receiver.getItemTypeCount(), receiver.getItemTotalCount(),
            receiver.getFluidTypeCount(), receiver.getFluidTotalAmount(),
            (int) Math.min(receiver.getEnergyUsage(), Integer.MAX_VALUE), receiver.getFacing(), gridData);
    }

    public static void handle(final SyncReceiverConfigPacket packet, final IPayloadContext ctx) {
        // 先把包解码出来的不可变快照塞进双端共享的桥，再由 client/ClientPayloadSink 转给界面
        ctx.enqueueWork(() -> ClientPayloadHooks.get().syncNetworkResourceReceiver(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
