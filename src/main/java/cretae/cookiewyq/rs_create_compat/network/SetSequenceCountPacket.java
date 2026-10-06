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
 * C2S：序列装配终端输入框直接编辑次数（index = 步骤 0..8，-1 = 整体循环）。
 */
public record SetSequenceCountPacket(int containerId, int index, int value) implements CustomPacketPayload {
    public static final Type<SetSequenceCountPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_count"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceCountPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSequenceCountPacket::containerId,
            ByteBufCodecs.VAR_INT, SetSequenceCountPacket::index,
            ByteBufCodecs.INT, SetSequenceCountPacket::value,
            SetSequenceCountPacket::new
        );

    public static void handle(final SetSequenceCountPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        terminalMenu.setCount(packet.index(), packet.value());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
