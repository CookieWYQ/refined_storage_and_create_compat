package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：序列装配样板终端里用 Shift+滚轮 调整某格产物 / 废料的概率。
 * <p>只发“可见窗口下标”（服务端按自己的权威偏移换算成全局格），增量通常为 ±10（%）。
 * 调整后的概率由 ContainerData 同步回客户端，供槽位 tooltip 显示。</p>
 */
public record SetSequenceChancePacket(int containerId, int windowIndex, boolean scrap, int deltaPercent)
    implements CustomPacketPayload {
    public static final Type<SetSequenceChancePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_chance"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceChancePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSequenceChancePacket::containerId,
            ByteBufCodecs.VAR_INT, SetSequenceChancePacket::windowIndex,
            ByteBufCodecs.BOOL, SetSequenceChancePacket::scrap,
            ByteBufCodecs.VAR_INT, SetSequenceChancePacket::deltaPercent,
            SetSequenceChancePacket::new
        );

    public static void handle(final SetSequenceChancePacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminal)) {
            return;
        }
        terminal.adjustChance(packet.scrap(), packet.windowIndex(), packet.deltaPercent());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
