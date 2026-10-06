package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccSheaths;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * S2C：把「分隔框架套壳」的整份快照下发给客户端（{@link RsccSheaths} 的客户端只读镜像）。
 *
 * <p><b>为什么必须同步</b>：套壳记录存在服务端的 {@code SavedData} 里，客户端看不到；但客户端在收到
 * 方块更新时会自行重算一次连接（RS：{@code CableBlock#updateShape → updateConnections}；
 * Create：{@code FluidPipeBlock#updateBlockState}），这两条路都会经过
 * {@link cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard} 的判定。
 * 客户端若不知道「这一格被套住」，重算就会把连接恢复 —— 于是出现只有服务端认为断开的鬼影。</p>
 *
 * <p><b>时序</b>：服务端每次改动后<b>先发本包、再发方块更新</b>（见 {@code RsccSheaths#add/remove}），
 * 两者走同一条有序连接，客户端重算时拿到的已是新数据。</p>
 *
 * <p><b>字段</b>：{@code entries} = 被套住的坐标（{@code BlockPos#asLong()}）、「是否无限版」，
 * 以及<b>套上那一刻的连接快照</b>（6 位掩码，位 = {@code Direction#ordinal()}）。快照必须一起同步：
 * 客户端重算连接臂 / 管口时用的是快照而不是「套住就断开」，少了它就必然与服务端不一致。
 * 数据量极小（每条 8 + 1 + 1 字节），一律整份替换，不做增量。</p>
 */
public record SyncSheathPositionsPacket(List<Entry> entries) implements CustomPacketPayload {
    public static final Type<SyncSheathPositionsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_sheath_positions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSheathPositionsPacket> STREAM_CODEC =
        StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncSheathPositionsPacket::entries,
            SyncSheathPositionsPacket::new
        );

    /**
     * 一条记录：{@code pos} = {@code BlockPos#asLong()}，{@code infinite} = 是否无限版（渲染光泽 / 归还判定），
     * {@code mask} = 套上那一刻的连接快照（位 = {@code Direction#ordinal()}；置位 = 套上时这一侧连通）。
     */
    public record Entry(long pos, boolean infinite, int mask) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Entry::pos,
            ByteBufCodecs.BOOL, Entry::infinite,
            ByteBufCodecs.VAR_INT, Entry::mask,
            Entry::new
        );
    }

    public static void handle(final SyncSheathPositionsPacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> RsccSheaths.applyClientSnapshot(packet.entries()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
