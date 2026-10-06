package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：把「物品」设置为高级定量保持器第 {@code slotIndex} 个配置槽的标记（数量固定 1，不消耗）。
 */
public record SetAdvKeeperMarkerPacket(int containerId, int slotIndex, ItemStack stack)
    implements CustomPacketPayload {

    public static final Type<SetAdvKeeperMarkerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_adv_keeper_marker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetAdvKeeperMarkerPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetAdvKeeperMarkerPacket::containerId,
            ByteBufCodecs.VAR_INT, SetAdvKeeperMarkerPacket::slotIndex,
            ItemStack.OPTIONAL_STREAM_CODEC, SetAdvKeeperMarkerPacket::stack,
            SetAdvKeeperMarkerPacket::new
        );

    public static void handle(final SetAdvKeeperMarkerPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof AdvancedQuantityKeeperMenu menu)) {
            return; // 容器校验不通过：直接忽略
        }
        menu.setMarkerFromJei(packet.slotIndex(), packet.stack());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
