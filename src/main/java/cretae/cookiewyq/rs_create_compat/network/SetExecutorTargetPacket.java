package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequenceAssemblyExecutorMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：客户端在输入框中直接编辑目标数量后发送，服务端设置序列装配执行器的目标数量。
 */
public record SetExecutorTargetPacket(int containerId, int value) implements CustomPacketPayload {
    public static final Type<SetExecutorTargetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_executor_target"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetExecutorTargetPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetExecutorTargetPacket::containerId,
            ByteBufCodecs.INT, SetExecutorTargetPacket::value,
            SetExecutorTargetPacket::new
        );

    public static void handle(final SetExecutorTargetPacket packet, final ServerPlayer player) {
        // 样板库不再提供“目标数量”配置：请求数量由终端原生自动合成决定。
        // 该包保留仅为兼容旧客户端；服务端忽略。
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
