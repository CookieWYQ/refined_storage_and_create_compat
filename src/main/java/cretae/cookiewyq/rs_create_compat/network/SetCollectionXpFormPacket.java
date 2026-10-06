package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.support.XpForm;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换归流缓存仓的经验收集形态（经验球实体 / 液态经验；「自动」模式已删除）。
 * <p>服务端只在「当前打开的菜单就是该容器」时写入方块实体（服务端权威 + 容器校验），
 * 并落 NBT 键 {@code XpForm}；新值经 {@code CollectionCacheMenu} 的数据槽 21 回传客户端，
 * 重开界面时按 NBT 读回，显示始终正确。</p>
 * <p><b>缺前置的形态会被服务端拒绝</b>：{@code CollectionCacheMenu#setXpForm} 内部会做可选性判定，
 * 例如没装「机械动力：覆膜工艺」时液态会被退化为经验球（界面已置灰，这里是第二道拦截）。</p>
 * <p>注意：分量名不能叫 {@code type} —— 会与 {@link CustomPacketPayload#type()} 同名不同返回类型，
 * 导致 record 访问器无效（编译期报错），故用 {@code xpForm}。</p>
 */
public record SetCollectionXpFormPacket(int containerId, XpForm xpForm) implements CustomPacketPayload {
    public static final Type<SetCollectionXpFormPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_xp_form"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionXpFormPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionXpFormPacket::containerId,
            XpForm.STREAM_CODEC, SetCollectionXpFormPacket::xpForm,
            SetCollectionXpFormPacket::new
        );

    public static void handle(final SetCollectionXpFormPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setXpForm(packet.xpForm());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
