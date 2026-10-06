package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：JEI ghost 输入 —— 把 JEI 面板拖拽的物品放入序列装配终端槽位。
 * 标记槽只记录类型（数量 1），普通槽直接放入。
 */
public record SetSequenceGhostPacket(int containerId, int slotId, ItemStack stack) implements CustomPacketPayload {
    public static final Type<SetSequenceGhostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_ghost"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceGhostPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSequenceGhostPacket::containerId,
            ByteBufCodecs.VAR_INT, SetSequenceGhostPacket::slotId,
            ItemStack.OPTIONAL_STREAM_CODEC, SetSequenceGhostPacket::stack,
            SetSequenceGhostPacket::new
        );

    public static void handle(final SetSequenceGhostPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        terminalMenu.placeGhost(packet.slotId(), packet.stack());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
