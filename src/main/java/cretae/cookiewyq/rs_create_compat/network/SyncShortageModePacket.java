package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：把「序列装配缺料处置策略」（挂起 / 等待）下发给客户端（用户第 4 条）。
 *
 * <p>负载只有一个 <b>枚举序号</b>（{@code RsccShortagePolicy.Mode} 的声明顺序）；
 * 这里刻意不引用 support 侧的类型，保持网络层只依赖纯数据（与
 * {@code SyncAssemblyAlertsPacket} 用原因序号同一套做法）。</p>
 *
 * <p>客户端把它写进只读镜像（{@code client/ShortageModeClient}），监视器界面上的
 * 「缺料处置」开关据此渲染 —— 客户端<b>从不</b>自己改它。</p>
 */
public record SyncShortageModePacket(int mode) implements CustomPacketPayload {
    public static final Type<SyncShortageModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_shortage_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncShortageModePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncShortageModePacket::mode,
            SyncShortageModePacket::new
        );

    public static void handle(final SyncShortageModePacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> cretae.cookiewyq.rs_create_compat.client.ShortageModeClient.set(packet.mode()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
