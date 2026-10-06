package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：归流缓存仓界面滚动条翻页时发送。
 * <p>匹配区与缓存区各自维护独立偏移：{@code match=true} 改匹配区、{@code match=false} 改缓存区；
 * 服务端菜单据此把各自的可见窗口（9×3）映射到全局格，并把权威偏移经数据槽回传客户端。</p>
 */
public record SetCollectionScrollPacket(int containerId, boolean match, int pageOffset)
    implements CustomPacketPayload {
    public static final Type<SetCollectionScrollPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_scroll"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionScrollPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionScrollPacket::containerId,
            ByteBufCodecs.BOOL, SetCollectionScrollPacket::match,
            ByteBufCodecs.VAR_INT, SetCollectionScrollPacket::pageOffset,
            SetCollectionScrollPacket::new
        );

    public static void handle(final SetCollectionScrollPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        if (packet.match()) {
            cacheMenu.setMatchOffset(packet.pageOffset());
            // 翻页后立即回传新窗口的标记条目（匹配区无上限，只同步当前 48 格可见窗口）
            cacheMenu.syncMarkers();
        } else {
            cacheMenu.setCacheOffset(packet.pageOffset());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
