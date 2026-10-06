package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：模式切换后把切换前的鼠标<b>原生窗口坐标</b>回发客户端恢复游标
 * （仿照 Universal-Grid 的 SetCursorPosWindowPacket，客户端直接 glfwSetCursorPos）。
 *
 * <p><b>为什么处理体里不出现任何客户端类型</b>：本类被注册进 {@code playToClient}，
 * <b>专用服务端在注册期也会加载并链接它</b>；一旦这里出现 {@code net.minecraft.client.Minecraft} /
 * {@code org.lwjgl.glfw.GLFW} 之类的服务端不存在的类型，就有在链接期被解析而崩溃的风险
 * （根因说明见 {@code ClientPayloadHooks} 的类文档）。真正的 GLFW 调用搬到
 * {@code client/ClientPayloadSink#restoreCursor}。</p>
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
        ctx.enqueueWork(() -> ClientPayloadHooks.get().restoreCursor(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
