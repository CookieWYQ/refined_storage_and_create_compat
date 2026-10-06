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
 * C2S：切换高级定量保持器第 {@code slotIndex} 个配置槽的「自动合成」开关。
 * 服务端会再次校验是否装有自动合成升级（无升级则忽略）。
 */
public record SetAdvKeeperAutocraftPacket(int containerId, int slotIndex, boolean enabled)
    implements CustomPacketPayload {

    public static final Type<SetAdvKeeperAutocraftPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_adv_keeper_autocraft"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetAdvKeeperAutocraftPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetAdvKeeperAutocraftPacket::containerId,
            ByteBufCodecs.VAR_INT, SetAdvKeeperAutocraftPacket::slotIndex,
            ByteBufCodecs.BOOL, SetAdvKeeperAutocraftPacket::enabled,
            SetAdvKeeperAutocraftPacket::new
        );

    public static void handle(final SetAdvKeeperAutocraftPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof AdvancedQuantityKeeperMenu menu)) {
            return;
        }
        menu.setAutoCraft(packet.slotIndex(), packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
