package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：从 JEI 拖入物品 → 把该物品设为定量保持器的标记（数量固定 1，不消耗）。
 */
public record SetQuantityMarkerPacket(int containerId, ItemStack stack) implements CustomPacketPayload {
    public static final Type<SetQuantityMarkerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_quantity_marker"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetQuantityMarkerPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetQuantityMarkerPacket::containerId,
            ItemStack.OPTIONAL_STREAM_CODEC, SetQuantityMarkerPacket::stack,
            SetQuantityMarkerPacket::new
        );

    public static void handle(final SetQuantityMarkerPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof QuantityKeeperMenu menu)) {
            return;
        }
        menu.setMarkerFromJei(packet.stack());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
