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
 * C2S：<b>销毁归流缓存仓缓存区的全部内容</b>（物品 + 流体 / 气体）。
 *
 * <h2>为什么必须带确认位（用户原话：「因为这个功能很危险，是直接销毁的，所以添加确认提醒」）</h2>
 * <p>销毁不可逆，一旦执行任何回滚都拿不回来。因此本包携带 {@code confirmed} 字段：
 * <b>只有界面弹出确认子窗口、玩家点了「确认销毁」之后才置 true</b>。服务端在 {@link #handle} 里
 * 对该字段做硬校验 —— {@code confirmed == false} 时<b>直接返回、零销毁</b>。这样即便有人手搓包，
 * 也只能「先确认再销毁」，不会出现「一点就没了」。
 *
 * <h2>服务端权威</h2>
 * <p>只接受「当前打开的菜单就是该容器」且菜单类型正确的请求；真正的销毁由
 * {@link CollectionCacheMenu#destroyCacheContent()} → 方块实体执行，并逐资源写入
 * {@code RsccFlowLedger} 的销毁账目（可审计）。客户端不做任何权威修改。</p>
 */
public record SetCollectionDestroyPacket(int containerId, boolean confirmed) implements CustomPacketPayload {

    public static final Type<SetCollectionDestroyPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_destroy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionDestroyPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionDestroyPacket::containerId,
            ByteBufCodecs.BOOL, SetCollectionDestroyPacket::confirmed,
            SetCollectionDestroyPacket::new
        );

    public static void handle(final SetCollectionDestroyPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        if (!packet.confirmed()) {
            // 未确认 = 服务端零销毁（反例防线：直达销毁的包在这里被拒）
            return;
        }
        cacheMenu.destroyCacheContent();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
