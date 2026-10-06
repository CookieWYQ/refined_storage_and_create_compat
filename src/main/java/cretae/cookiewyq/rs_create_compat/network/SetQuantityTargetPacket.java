package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：客户端在输入框中直接编辑数字后发送，服务端设置定量保持器的目标数量。
 * 通过 containerId 定位当前打开的菜单（客户端重建菜单也能发送）。
 */
public record SetQuantityTargetPacket(int containerId, int value) implements CustomPacketPayload {
    public static final Type<SetQuantityTargetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_quantity_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetQuantityTargetPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetQuantityTargetPacket::containerId,
            ByteBufCodecs.INT, SetQuantityTargetPacket::value,
            SetQuantityTargetPacket::new
        );

    public static void handle(final SetQuantityTargetPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof QuantityKeeperMenu keeperMenu)) {
            return;
        }
        keeperMenu.setTargetValue(packet.value());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
