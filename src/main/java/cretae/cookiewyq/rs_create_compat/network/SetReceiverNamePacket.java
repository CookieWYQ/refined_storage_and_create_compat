package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：界面上的「改名 + 确定」。
 *
 * <h2>这个包<b>不</b>相信客户端</h2>
 * <p>{@code name} 只是「玩家想改成什么」，服务端唯一入口是
 * {@link NetworkResourceReceiverMenu#applyNameFromUi(String)}：空名 / 超长 / 非法字符 / 同网络重名
 * 全部在那里被拒（判据见该方法的 javadoc）。客户端即使被改包，也只可能提交一个「更离谱的名字」，
 * 不可能绕过校验直接写 NBT —— 包里根本没有目标方块坐标，只有 {@code containerId}，
 * 而服务端会再核对「当前打开的菜单就是这个 id、且仍然是本机菜单、且玩家还在有效距离内」。</p>
 *
 * <h2>dist 安全</h2>
 * <p>本类只引用双端都存在的类型（{@code ServerPlayer} / 菜单 / 载荷 record）；
 * 客户端专属类一律不出现 —— 与 {@code network/ClientPayloadHooks} 的类文档同一条硬规则。</p>
 */
public record SetReceiverNamePacket(int containerId, String name) implements CustomPacketPayload {

    public static final Type<SetReceiverNamePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_receiver_name"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetReceiverNamePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetReceiverNamePacket::containerId,
            ByteBufCodecs.STRING_UTF8, SetReceiverNamePacket::name,
            SetReceiverNamePacket::new
        );

    public static void handle(final SetReceiverNamePacket packet, final ServerPlayer player) {
        // 三道归属校验（全部在服务端做）：① 菜单 id 对得上；② 类型对得上；③ 玩家仍然够得着方块
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof NetworkResourceReceiverMenu menu)) {
            return;
        }
        if (!menu.stillValid(player)) {
            return;
        }
        menu.applyNameFromUi(packet.name());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
