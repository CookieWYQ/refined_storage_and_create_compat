package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RequesterSenderBackend;
import cretae.cookiewyq.rs_create_compat.support.SenderBackend;
import cretae.cookiewyq.rs_create_compat.support.SenderShortage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：**对自动合成询问的回答**（第 131 轮 F 组）。
 *
 * <p>整个包只有两个字段：{@code containerId}（是哪张发送界面问的）与 {@code craft}
 * （{@code true} =「合成 X 并发送 Y」；{@code false} =「只发送现有」）。</p>
 *
 * <h2>为什么只回「按哪个按钮」</h2>
 * <p>数量、缺口、可合成量全部由服务端在询问时算好并存在
 * {@link SenderShortage#takeAwaiting(java.util.UUID)} 里；客户端如果把「要合成多少」再传回来，
 * 就等于让客户端决定资源流向（伪造包可以凭空多要）。因此这里只表达玩家的<b>意愿</b>，
 * 服务端从自己的记录里取回「当时问的是什么」，并重新解析发送源与归属 —— 与
 * {@code SetRequesterTargetPacket} 只带名字是同一套纪律。</p>
 */
public record SenderShortageReplyPacket(int containerId, boolean craft)
    implements CustomPacketPayload {

    public static final Type<SenderShortageReplyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "sender_shortage_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SenderShortageReplyPacket>
        STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SenderShortageReplyPacket::containerId,
            ByteBufCodecs.BOOL, SenderShortageReplyPacket::craft,
            SenderShortageReplyPacket::new
        );

    /** 服务端处理：归属校验 → 取走「等回答」的询问 → 按玩家选择投现有 / 合成 + 补投。 */
    public static void handle(final SenderShortageReplyPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof SenderBackend.Holder holder)) {
            return;
        }
        final SenderBackend backend = holder.senderBackend();
        if (backend == null || !backend.stillValid(player)) {
            return;
        }
        if (!SenderShortage.reply(player, backend, packet.craft())) {
            // 没有待回答的询问（已过期 / 重复点）：必须给一句，否则玩家会以为按钮失效
            RequesterSenderBackend.syncTo(player, backend, MSG_SHORTAGE_EXPIRED, -1);
        }
    }

    /** 「询问已过期（超过 60 秒）/ 已经回答过」的提示。 */
    public static final String MSG_SHORTAGE_EXPIRED =
        "gui.rs_create_compat.network_resource_requester.msg.shortage_expired";

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
