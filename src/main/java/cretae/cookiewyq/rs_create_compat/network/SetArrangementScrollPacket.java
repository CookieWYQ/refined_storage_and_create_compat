package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：序列装配样板终端"流程编排"滚动条翻行时发送，
 * 服务端菜单同步滚动窗口偏移，使窗口槽位/次数数据映射到正确的全局步下标。
 */
public record SetArrangementScrollPacket(int containerId, int rowOffset) implements CustomPacketPayload {
    public static final Type<SetArrangementScrollPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_arrangement_scroll"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetArrangementScrollPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetArrangementScrollPacket::containerId,
            ByteBufCodecs.VAR_INT, SetArrangementScrollPacket::rowOffset,
            SetArrangementScrollPacket::new
        );

    public static void handle(final SetArrangementScrollPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId()) {
            return;
        }
        if (menu instanceof SequencePatternTerminalMenu terminal) {
            terminal.setArrangementOffset(packet.rowOffset());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
