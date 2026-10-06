package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * S2C：模式切换后把切换前的鼠标<b>原生窗口坐标</b>回发客户端恢复游标
 * （仿照 Universal-Grid 的 SetCursorPosWindowPacket，客户端直接 glfwSetCursorPos）。
 */
public record RestoreCursorPacket(int cursorX, int cursorY) implements CustomPacketPayload {
    public static final Type<RestoreCursorPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "restore_cursor"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RestoreCursorPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.INT, RestoreCursorPacket::cursorX,
            ByteBufCodecs.INT, RestoreCursorPacket::cursorY,
            RestoreCursorPacket::new
        );

    public static void handle(final RestoreCursorPacket packet, final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            GLFW.glfwSetCursorPos(Minecraft.getInstance().getWindow().getWindow(),
                packet.cursorX(), packet.cursorY()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
