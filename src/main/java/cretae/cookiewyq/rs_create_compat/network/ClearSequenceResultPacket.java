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
 * C2S：清空序列装配样板终端里某一格「产物 / 废料」（界面 Shift+左键触发）。
 * <p>只清掉该格的标记引用与概率，不影响其它格，也不销毁任何真实物品
 * （产物 / 废料格本身只是标记，没有实物）。</p>
 * <p>{@code windowIndex} 为当前可见窗口内的下标，服务端按滚动偏移换算成全局下标。</p>
 */
public record ClearSequenceResultPacket(int containerId, boolean scrap, int windowIndex)
    implements CustomPacketPayload {
    public static final Type<ClearSequenceResultPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "clear_sequence_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClearSequenceResultPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClearSequenceResultPacket::containerId,
            ByteBufCodecs.BOOL, ClearSequenceResultPacket::scrap,
            ByteBufCodecs.VAR_INT, ClearSequenceResultPacket::windowIndex,
            ClearSequenceResultPacket::new
        );

    public static void handle(final ClearSequenceResultPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId()
            || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        terminalMenu.clearResultConfig(packet.scrap(), packet.windowIndex());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
