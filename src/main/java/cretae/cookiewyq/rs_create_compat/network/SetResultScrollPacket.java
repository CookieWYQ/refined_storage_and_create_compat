package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：序列装配样板终端的“产物 / 废料”滚动条翻页。
 * <p>产物与废料各自维护独立偏移：{@code scrap=false} 改产物、{@code scrap=true} 改废料；
 * 服务端菜单据此把可见窗口（4×3）映射到全局格，并把权威偏移经数据槽回传客户端。</p>
 */
public record SetResultScrollPacket(int containerId, boolean scrap, int pageOffset) implements CustomPacketPayload {
    public static final Type<SetResultScrollPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_result_scroll"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetResultScrollPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetResultScrollPacket::containerId,
            ByteBufCodecs.BOOL, SetResultScrollPacket::scrap,
            ByteBufCodecs.VAR_INT, SetResultScrollPacket::pageOffset,
            SetResultScrollPacket::new
        );

    public static void handle(final SetResultScrollPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminal)) {
            return;
        }
        if (packet.scrap()) {
            terminal.setScrapOffset(packet.pageOffset());
        } else {
            terminal.setResultOffset(packet.pageOffset());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
