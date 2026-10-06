package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.support.AbsorbType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换归流缓存仓的 4 个吸取开关之一（物品 / 流体 / 气体 / 经验）。
 * <p>服务端只在「当前打开的菜单就是该容器」时写回方块实体（服务端权威 + 容器校验），
 * 开关状态经 {@code CollectionCacheMenu} 的 ContainerData 回传客户端。</p>
 * <p>注意：分量名不能叫 {@code type} —— 会与 {@link CustomPacketPayload#type()} 同名不同返回类型，
 * 导致 record 访问器无效（编译期报错），故用 {@code absorbType}。</p>
 */
public record SetCollectionAbsorbTogglePacket(int containerId, AbsorbType absorbType, boolean enabled)
    implements CustomPacketPayload {

    public static final Type<SetCollectionAbsorbTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_absorb_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionAbsorbTogglePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionAbsorbTogglePacket::containerId,
            AbsorbType.STREAM_CODEC, SetCollectionAbsorbTogglePacket::absorbType,
            ByteBufCodecs.BOOL, SetCollectionAbsorbTogglePacket::enabled,
            SetCollectionAbsorbTogglePacket::new
        );

    public static void handle(final SetCollectionAbsorbTogglePacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setAbsorbEnabled(packet.absorbType(), packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
