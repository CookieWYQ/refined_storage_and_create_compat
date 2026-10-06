package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换归流缓存仓「某一个面是否允许物流输入」（用户需求：可以配置多个的输入）。
 * <p>服务端按容器 id 解析当前打开的 {@link CollectionCacheMenu}，写入方块实体；
 * 新值经菜单数据槽（索引 19）回传客户端，子界面与主界面据此刷新——服务端权威，客户端只做即时反馈。</p>
 * <p>关掉某个面只是让该面不再对外暴露物品/流体能力（外面塞不进来），
 * <b>不影响已进入仓内的资源</b>，也不影响收集与回流网络。</p>
 */
public record SetCollectionInputFacePacket(int containerId, int face, boolean enabled)
    implements CustomPacketPayload {
    public static final Type<SetCollectionInputFacePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_input_face"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionInputFacePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionInputFacePacket::containerId,
            ByteBufCodecs.VAR_INT, SetCollectionInputFacePacket::face,
            ByteBufCodecs.BOOL, SetCollectionInputFacePacket::enabled,
            SetCollectionInputFacePacket::new
        );

    public static void handle(final SetCollectionInputFacePacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof CollectionCacheMenu menu)) {
            return;
        }
        if (packet.face() < 0 || packet.face() >= Direction.values().length) {
            return;
        }
        menu.setInputFace(Direction.values()[packet.face()], packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
