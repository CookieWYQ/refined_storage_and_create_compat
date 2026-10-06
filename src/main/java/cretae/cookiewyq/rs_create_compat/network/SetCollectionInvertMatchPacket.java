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
 * C2S：切换归流缓存仓的「反转匹配」开关。
 * <p>用户需求：把匹配区从<b>白名单</b>变成<b>黑名单</b>（反向过滤）—— 开启后只收集「<b>没有</b>被任何标记命中的」资源。</p>
 * <p>服务端权威：只在「当前打开的菜单就是该容器」时写回方块实体（状态随方块 NBT 落盘）。
 * <p><b>优先级</b>：{@code collectAll}（吸取所有物品）开启时匹配区整体失效，本开关不参与判定，
 * 因此界面把「反转匹配」按钮显示为禁用态（仍在本地保留其值，关闭 collectAll 后即生效）。</p>
 */
public record SetCollectionInvertMatchPacket(int containerId, boolean enabled) implements CustomPacketPayload {

    public static final Type<SetCollectionInvertMatchPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_collection_invert_match"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionInvertMatchPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionInvertMatchPacket::containerId,
            ByteBufCodecs.BOOL, SetCollectionInvertMatchPacket::enabled,
            SetCollectionInvertMatchPacket::new
        );

    public static void handle(final SetCollectionInvertMatchPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setInvertMatch(packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
