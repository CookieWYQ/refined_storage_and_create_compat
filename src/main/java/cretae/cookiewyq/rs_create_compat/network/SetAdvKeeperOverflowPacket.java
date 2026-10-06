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
 * C2S：切换高级定量保持器第 {@code slotIndex} 个配置槽的「过量销毁」开关。
 * <p>「过量销毁」与「自动合成」是两件独立的事：自动合成由<b>是否装了自动合成升级</b>全局决定，
 * 而本开关是<b>每槽独立</b>的（默认关闭，关闭时绝不销毁任何资源）。</p>
 */
public record SetAdvKeeperOverflowPacket(int containerId, int slotIndex, boolean enabled)
    implements CustomPacketPayload {

    public static final Type<SetAdvKeeperOverflowPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_adv_keeper_overflow"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetAdvKeeperOverflowPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetAdvKeeperOverflowPacket::containerId,
            ByteBufCodecs.VAR_INT, SetAdvKeeperOverflowPacket::slotIndex,
            ByteBufCodecs.BOOL, SetAdvKeeperOverflowPacket::enabled,
            SetAdvKeeperOverflowPacket::new
        );

    public static void handle(final SetAdvKeeperOverflowPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof AdvancedQuantityKeeperMenu menu)) {
            return;
        }
        menu.setDestroyOverflow(packet.slotIndex(), packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
