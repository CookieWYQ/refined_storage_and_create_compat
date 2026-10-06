package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：监视器界面刚打开时请求补发一份「缺料处置策略」快照（无参数）。
 *
 * <p><b>为什么需要它</b>：那份策略只在<b>改动时</b>回发（见 {@link SetShortageModePacket}），
 * 而玩家可能在上一次改动之后才打开监视器（甚至换过维度 / 重登）—— 客户端手里就没有任何快照。
 * 界面一打开主动拉一次，服务端按自身权威数据回一份 {@link SyncShortageModePacket}。</p>
 *
 * <p><b>服务端权威</b>：本包不携带任何数据，也不会改任何服务端状态（只读回一份快照）。</p>
 */
public record RequestShortageModePacket() implements CustomPacketPayload {
    public static final Type<RequestShortageModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "request_shortage_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestShortageModePacket> STREAM_CODEC =
        StreamCodec.unit(new RequestShortageModePacket());

    public static void handle(final RequestShortageModePacket packet, final ServerPlayer player) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
            new SyncShortageModePacket(RsccShortagePolicy.get(server).getMode().ordinal()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
