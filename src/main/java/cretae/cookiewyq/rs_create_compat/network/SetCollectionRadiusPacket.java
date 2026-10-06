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
 * C2S：归流缓存仓界面设置某一轴的「收集范围（格）」（{@code axis}：0=X / 1=Y / 2=Z）。
 * <p>服务端权威写入方块实体（并夹到 1..上限）：范围越大，扫描/吸收越快，同时耗电越高，
 * 且耗电与当前是否正在收集无关。上限 = 基础 16 + 每个范围升级 25；放入创造范围升级后不再受限。
 * 客户端显示的数值由 ContainerData 数据槽回传。</p>
 */
public record SetCollectionRadiusPacket(int containerId, int axis, int radius) implements CustomPacketPayload {
    public static final Type<SetCollectionRadiusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_radius"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionRadiusPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionRadiusPacket::containerId,
            ByteBufCodecs.VAR_INT, SetCollectionRadiusPacket::axis,
            ByteBufCodecs.VAR_INT, SetCollectionRadiusPacket::radius,
            SetCollectionRadiusPacket::new
        );

    public static void handle(final SetCollectionRadiusPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setCollectRadius(packet.axis(), packet.radius());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
