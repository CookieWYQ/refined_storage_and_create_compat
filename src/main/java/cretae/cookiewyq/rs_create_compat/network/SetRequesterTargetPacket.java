package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RequesterSenderBackend;
import cretae.cookiewyq.rs_create_compat.support.SenderBackend;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：界面上的「从本网络接收器列表里挑一台 + 选定」。
 *
 * <p>包里带的仍然只是<b>名字</b>：界面从 S2C 配置快照的候选列表（名字 + 坐标）里挑，
 * 提交时把那一台的 {@code name} 发回来 —— 存储层一个字都没改（请求器 NBT 里存的就是 targetName），
 * 因此「候选过期 / 客户端自说自话」都不会影响正确性。</p>
 *
 * <h2>服务端才是判据</h2>
 * <p>{@code name} 只是「玩家想指定哪一台」。真正的校验在
 * {@code RequesterSenderBackend#applyTarget}：空名 / 超长 / 非法字符 /
 * <b>找不到这个名字的接收器</b> / 这个名字被多台接收器占用，全部在那里被拒。客户端本地的提示只用于体验，
 * 不参与任何写入决策（客户端菜单里连方块实体都没有）。</p>
 */
public record SetRequesterTargetPacket(int containerId, String name) implements CustomPacketPayload {

    public static final Type<SetRequesterTargetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_requester_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetRequesterTargetPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetRequesterTargetPacket::containerId,
            ByteBufCodecs.STRING_UTF8, SetRequesterTargetPacket::name,
            SetRequesterTargetPacket::new
        );

    public static void handle(final SetRequesterTargetPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof SenderBackend.Holder holder)) {
            return;
        }
        final SenderBackend backend = holder.senderBackend();
        if (backend == null || !backend.stillValid(player)) {
            return;
        }
        // 判据全在 applyTarget（空名 / 超长 / 非法字符 / 找不到 / 重名），界面只提交名字。
        // 快照由 applyTarget 自己在「校验通过 → 写 → 回快照」的末尾发（成功与拒绝都发），
        // 这里**不再**重复发一份 —— 同一份内容发两遍只会让界面闪一下。
        RequesterSenderBackend.applyTarget(player, backend, packet.name());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
