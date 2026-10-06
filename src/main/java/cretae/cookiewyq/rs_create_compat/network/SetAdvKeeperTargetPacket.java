package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：客户端在输入框中编辑数字后发送，设置高级定量保持器第 {@code slotIndex} 个配置槽的目标数量。
 */
public record SetAdvKeeperTargetPacket(int containerId, int slotIndex, int value)
    implements CustomPacketPayload {

    public static final Type<SetAdvKeeperTargetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_adv_keeper_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetAdvKeeperTargetPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetAdvKeeperTargetPacket::containerId,
            ByteBufCodecs.VAR_INT, SetAdvKeeperTargetPacket::slotIndex,
            ByteBufCodecs.INT, SetAdvKeeperTargetPacket::value,
            SetAdvKeeperTargetPacket::new
        );

    public static void handle(final SetAdvKeeperTargetPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof AdvancedQuantityKeeperMenu menu)) {
            return;
        }
        menu.setTargetValue(packet.slotIndex(), packet.value());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
