package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCableCuts;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * S2C：把「扳手断线」的整份快照下发给客户端（{@link RsccCableCuts} 的客户端镜像）。
 *
 * <p><b>为什么必须同步</b>：断线状态存在服务端的 {@code SavedData} 里，客户端看不到；
 * 但客户端在收到方块更新时会自行重算一次连接臂
 * （{@code CableBlock#updateShape → AbstractCableLikeBlockEntity#updateConnections}，
 * 内部同样经过 {@code InWorldNetworkNodeContainerImpl#canAcceptIncomingConnection}）。
 * 若客户端不知道断开记录，重算就会把臂「长回来」。因此客户端保留一份只读镜像。</p>
 *
 * <p><b>时序</b>：服务端在每次改动后<b>先发本包、再发方块更新</b>（见
 * {@code RsccCableCuts#toggleSeam}），两者走同一条有序连接，客户端重算时拿到的已是新数据。</p>
 *
 * <p><b>字段</b>：{@code mode} = 服务端当前档位（OFF / SEAM / FACE 的 ordinal，客户端以此为准，
 * 避免客户端自己的配置与服务端不一致）；{@code seam} / {@code face} = 两套「坐标 → 方向掩码」记录
 * （位 = {@code Direction#ordinal()}）。数据量本来就小（每条记录 8 + 1 字节），一律整份替换。</p>
 */
public record SyncCableDisconnectsPacket(int mode, List<Entry> seam, List<Entry> face)
    implements CustomPacketPayload {
    public static final Type<SyncCableDisconnectsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_cable_disconnects"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCableDisconnectsPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncCableDisconnectsPacket::mode,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncCableDisconnectsPacket::seam,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncCableDisconnectsPacket::face,
            SyncCableDisconnectsPacket::new
        );

    /** 一条记录：{@code pos} = {@code BlockPos#asLong()}，{@code mask} = 该方块上的方向掩码。 */
    public record Entry(long pos, byte mask) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Entry::pos,
            ByteBufCodecs.BYTE, Entry::mask,
            Entry::new
        );
    }

    public static void handle(final SyncCableDisconnectsPacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> RsccCableCuts.applyClientSnapshot(packet.mode(), packet.seam(), packet.face()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
