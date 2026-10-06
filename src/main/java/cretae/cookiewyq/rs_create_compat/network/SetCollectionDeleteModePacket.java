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
 * C2S：切换归流缓存仓的<b>「删除模式」总闸</b>（界面上那个复选框）。
 *
 * <h2>语义（用户原话）</h2>
 * <p><i>「有一个状态就是一个复选框……选择之后可以开启清除资源功能……标记要删除的物品在<b>开启了删除模式</b>下
 * 才会被删除。」</i> 因此本开关是**唯一**的总闸：</p>
 * <ul>
 *     <li>关闭（默认）：匹配槽上的「标记为删除」只记录意图，<b>绝不销毁任何资源</b>；</li>
 *     <li>开启：命中「标记为删除」的缓存资源按 {@code 处理组数 × 单次吞吐}（= 速度升级 / 堆叠升级
 *     决定的速率，与回流进网络同源）逐 tick 销毁。</li>
 * </ul>
 *
 * <h2>为什么「开启」必须带确认位</h2>
 * <p>开启之后，凡是命中已勾选删除的槽的资源都会<b>不可逆地消失</b>。因此本包携带 {@code confirmed}：
 * 界面在开启前必须先弹出确认（列出「当前将被销毁的资源种类 / 数量」），玩家确认后才置 true。
 * <b>服务端对开启做硬校验</b> —— {@code enabled == true && confirmed == false} 时直接返回、绝不开启；
 * <b>关闭</b>是恢复安全状态，无需确认。</p>
 *
 * <h2>服务端权威</h2>
 * <p>只接受「当前打开的菜单就是该容器」的请求（{@code containerId} 必须匹配），
 * 且只作用于本仓自身。</p>
 */
public record SetCollectionDeleteModePacket(int containerId, boolean enabled,
                                            boolean confirmed) implements CustomPacketPayload {

    public static final Type<SetCollectionDeleteModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_collection_delete_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionDeleteModePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionDeleteModePacket::containerId,
            ByteBufCodecs.BOOL, SetCollectionDeleteModePacket::enabled,
            ByteBufCodecs.BOOL, SetCollectionDeleteModePacket::confirmed,
            SetCollectionDeleteModePacket::new
        );

    public static void handle(final SetCollectionDeleteModePacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        if (packet.enabled() && !packet.confirmed()) {
            // 开启删除模式必须经过确认：未确认直接忽略（服务端权威，客户端绕不过去）
            return;
        }
        cacheMenu.setDeleteMode(packet.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
