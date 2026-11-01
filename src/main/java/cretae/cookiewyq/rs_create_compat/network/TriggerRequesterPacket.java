package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：「立即发送」按钮 —— 界面上的手动触发路径。
 *
 * <h2>只做一件事</h2>
 * <p>服务端入口 {@link NetworkResourceRequesterMenu#triggerRequestFromUi()}：先按与红石路径
 * <b>同一套判据</b>确认「目标已配置且唯一存在」，然后调用方块实体既有的
 * {@code triggerRequest()}。搬运逻辑（取料 / 写入接收器 / 回滚守恒）完全在方块实体里，
 * 本包与菜单都<b>不碰</b>它 —— 红石上升沿与手动按钮因此仍然是「两条触发路径、同一个方法」。</p>
 *
 * <h2>为什么不是「客户端直接触发」</h2>
 * <p>客户端没有网络存储、没有接收器方块实体，任何本地触发都只是动画；真正的取料必须在服务端
 * 主线程上按升级节拍做。因此本包只带 {@code containerId}，服务端靠自己的菜单反查方块实体。</p>
 */
public record TriggerRequesterPacket(int containerId) implements CustomPacketPayload {

    public static final Type<TriggerRequesterPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "trigger_requester"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TriggerRequesterPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TriggerRequesterPacket::containerId,
            TriggerRequesterPacket::new
        );

    public static void handle(final TriggerRequesterPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        // 请求器界面与网格界面共用：归属与「方块还在」判据都在菜单那一处
        final cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity
            requester = NetworkResourceRequesterMenu.requesterOf(player.containerMenu);
        if (!NetworkResourceRequesterMenu.stillValidFor(player, requester)) {
            return;
        }
        NetworkResourceRequesterMenu.triggerFor(player, packet.containerId());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
