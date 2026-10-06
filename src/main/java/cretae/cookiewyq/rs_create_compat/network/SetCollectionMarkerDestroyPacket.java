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
 * C2S：切换某<b>匹配槽</b>的「直接销毁」模式（用户原话：「在匹配槽那里可以进行选择是否直接销毁」）。
 *
 * <h2>语义</h2>
 * <p>打开后，凡命中该匹配槽的资源<b>进仓后不写回网络、直接销毁</b>（属于有意销毁，
 * 由方块实体记入 {@code RsccFlowLedger} 的销毁账目）。关闭则恢复为正常回流进网络。</p>
 *
 * <h2>为什么「开启」必须带确认位</h2>
 * <p>开启即意味着后续命中物都会被销毁，是危险操作。因此本包携带 {@code confirmed}：
 * 界面在开启前必须先给出确认提醒，玩家确认后才置 true。<b>服务端对开启做硬校验</b> ——
 * {@code destroy == true && confirmed == false} 时直接返回、绝不开启；<b>关闭</b>是恢复安全状态，
 * 无需确认（{@code confirmed} 不参与判定）。</p>
 *
 * <h2>服务端权威</h2>
 * <p>只接受「当前打开的菜单就是该容器」且下标落在匹配区容量内的请求。</p>
 */
public record SetCollectionMarkerDestroyPacket(int containerId, int markerIndex, boolean destroy,
                                               boolean confirmed) implements CustomPacketPayload {

    public static final Type<SetCollectionMarkerDestroyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_collection_marker_destroy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionMarkerDestroyPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionMarkerDestroyPacket::containerId,
            ByteBufCodecs.VAR_INT, SetCollectionMarkerDestroyPacket::markerIndex,
            ByteBufCodecs.BOOL, SetCollectionMarkerDestroyPacket::destroy,
            ByteBufCodecs.BOOL, SetCollectionMarkerDestroyPacket::confirmed,
            SetCollectionMarkerDestroyPacket::new
        );

    public static void handle(final SetCollectionMarkerDestroyPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        if (packet.destroy() && !packet.confirmed()) {
            // 开启「直接销毁」必须经过确认：未确认直接忽略（服务端权威，绕不过去）
            return;
        }
        cacheMenu.setMarkerDestroy(packet.markerIndex(), packet.destroy());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
