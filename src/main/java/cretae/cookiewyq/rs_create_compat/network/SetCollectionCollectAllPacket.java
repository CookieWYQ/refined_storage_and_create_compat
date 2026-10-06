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
 * C2S：切换归流缓存仓的「吸取所有物品」开关。
 * <p>用户需求：开启后<b>匹配区直接失效（不过滤，全收）</b>，关闭后恢复按匹配区过滤；
 * 与「反转匹配」同时开启时本开关<b>优先</b>。</p>
 * <p>服务端权威：只在「当前打开的菜单就是该容器」时写回方块实体（状态随方块 NBT 落盘），
 * 客户端只发意图、不做本地权威修改 —— 显示值经 {@code CollectionCacheMenu} 的 ContainerData 回传。</p>
 */
public record SetCollectionCollectAllPacket(int containerId, boolean enabled) implements CustomPacketPayload {

    public static final Type<SetCollectionCollectAllPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_collection_collect_all"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionCollectAllPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionCollectAllPacket::containerId,
            ByteBufCodecs.BOOL, SetCollectionCollectAllPacket::enabled,
            SetCollectionCollectAllPacket::new
        );

    public static void handle(final SetCollectionCollectAllPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setCollectAll(packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
