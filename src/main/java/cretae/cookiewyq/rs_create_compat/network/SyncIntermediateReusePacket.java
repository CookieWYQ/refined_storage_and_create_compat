package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：把「优先复用网络中的中间产物」开关下发给客户端。
 *
 * <p>负载只有一个布尔；客户端把它写进只读镜像（{@code client/IntermediateReuseClient}），
 * RS 的「自动合成预览」界面据此决定要不要多显示一行「中间产物（优先复用）」——
 * 客户端<b>从不</b>自己改这个值。</p>
 */
public record SyncIntermediateReusePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<SyncIntermediateReusePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_intermediate_reuse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncIntermediateReusePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncIntermediateReusePacket::enabled,
            SyncIntermediateReusePacket::new
        );

    public static void handle(final SyncIntermediateReusePacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHooks.get().syncIntermediateReuse(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
