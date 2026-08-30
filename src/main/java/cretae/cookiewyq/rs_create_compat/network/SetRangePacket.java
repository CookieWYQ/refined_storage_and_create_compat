package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * C2S：客户端在输入框中直接编辑范围数值后发送，服务端设置范围充电器对应轴。
 * axis: 0=X, 1=Y, 2=Z。
 */
public record SetRangePacket(BlockPos pos, int axis, int value) implements CustomPacketPayload {
    public static final Type<SetRangePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_range"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRangePacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetRangePacket::pos,
            ByteBufCodecs.INT, SetRangePacket::axis,
            ByteBufCodecs.INT, SetRangePacket::value,
            SetRangePacket::new
        );

    public static void handle(final SetRangePacket packet, final ServerPlayer player) {
        final BlockEntity be = player.level().getBlockEntity(packet.pos());
        if (be instanceof RangeChargerBlockEntity charger) {
            final int v = Math.max(1, Math.min(packet.value(), 100));
            switch (packet.axis()) {
                case 0 -> charger.adjustRangeX(v - charger.getRangeX());
                case 1 -> charger.adjustRangeY(v - charger.getRangeY());
                case 2 -> charger.adjustRangeZ(v - charger.getRangeZ());
                default -> { }
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
