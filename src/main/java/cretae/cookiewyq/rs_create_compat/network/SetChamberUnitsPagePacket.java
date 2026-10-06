package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：切换「执行仓单元样板汇总」的分页（每页 {@code VISIBLE_ROWS} 台执行仓）。
 *
 * <p>分页是<b>服务端权威</b>：服务端把各行的滑动窗口重新指向新一页的执行仓，槽位下标保持不变，
 * 原版逐格 diff 会把新区内容推给客户端；同时用 {@link SyncChamberUnitsPacket} 回传收敛后的页码，
 * 客户端据此绘制行标题（避免「标签翻页了、格子还没翻」的错位）。</p>
 */
public record SetChamberUnitsPagePacket(int page) implements CustomPacketPayload {
    public static final Type<SetChamberUnitsPagePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_chamber_units_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetChamberUnitsPagePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetChamberUnitsPagePacket::page,
            SetChamberUnitsPagePacket::new
        );

    public static void handle(final SetChamberUnitsPagePacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof ChamberUnitsSummaryMenu menu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null) {
            return;
        }
        menu.setPage(packet.page());
        PacketDistributor.sendToPlayer(player,
            SyncChamberUnitsPacket.from(terminal.listChamberUnits(), menu.getPage()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
