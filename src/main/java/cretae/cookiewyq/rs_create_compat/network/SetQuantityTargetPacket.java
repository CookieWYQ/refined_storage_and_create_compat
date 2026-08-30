package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * C2S：客户端在输入框中直接编辑数字后发送，服务端设置对应方块实体的数值。
 * 目前用于定量保持器的目标数量。
 */
public record SetQuantityTargetPacket(BlockPos pos, int value) implements CustomPacketPayload {
    public static final Type<SetQuantityTargetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_quantity_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetQuantityTargetPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetQuantityTargetPacket::pos,
            ByteBufCodecs.INT, SetQuantityTargetPacket::value,
            SetQuantityTargetPacket::new
        );

    public static void handle(final SetQuantityTargetPacket packet, final ServerPlayer player) {
        final BlockEntity be = player.level().getBlockEntity(packet.pos());
        if (be instanceof QuantityKeeperBlockEntity keeper) {
            keeper.setTargetAmount(Math.max(1, packet.value()));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
