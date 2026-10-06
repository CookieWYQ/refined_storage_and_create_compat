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
 * C2S：序列装配样板终端「产物 / 废料」条目配置（子窗口确认时发送）。
 * <p>一次性写入两个信息：<b>产出概率</b>（百分比 0..100）与<b>产出数量</b>（1..64），
 * 取代此前只能用滚轮按 10% 一档调整概率、且完全无法设置数量的做法。</p>
 * <p>{@code windowIndex} 为当前可见窗口内的下标，服务端按滚动偏移换算成全局下标。</p>
 */
public record SetSequenceResultPacket(int containerId, boolean scrap, int windowIndex,
                                      int percent, int amount) implements CustomPacketPayload {
    public static final Type<SetSequenceResultPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceResultPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSequenceResultPacket::containerId,
            ByteBufCodecs.BOOL, SetSequenceResultPacket::scrap,
            ByteBufCodecs.VAR_INT, SetSequenceResultPacket::windowIndex,
            ByteBufCodecs.VAR_INT, SetSequenceResultPacket::percent,
            ByteBufCodecs.VAR_INT, SetSequenceResultPacket::amount,
            SetSequenceResultPacket::new
        );

    public static void handle(final SetSequenceResultPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId()
            || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        terminalMenu.setResultConfig(packet.scrap(), packet.windowIndex(),
            packet.percent(), packet.amount());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
