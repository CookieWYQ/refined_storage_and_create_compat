package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：请求「执行仓单元样板汇总」的行元数据（终端界面 / 汇总子界面都会发）。
 * <p>服务端按玩家当前打开的容器解析终端（终端菜单或汇总菜单都支持，见
 * {@link ChamberUnitsSummaryMenu#terminalOf}），把每台执行仓的显示名、配方类型与样板清单回传。</p>
 */
public record RequestChamberUnitsPacket() implements CustomPacketPayload {
    public static final Type<RequestChamberUnitsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "request_chamber_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestChamberUnitsPacket> STREAM_CODEC =
        StreamCodec.unit(new RequestChamberUnitsPacket());

    public static void handle(final RequestChamberUnitsPacket packet, final ServerPlayer player) {
        final SequencePatternTerminalBlockEntity terminal =
            ChamberUnitsSummaryMenu.terminalOf(player.containerMenu);
        if (terminal == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, SyncChamberUnitsPacket.from(terminal.listChamberUnits(),
            ChamberUnitsSummaryMenu.pageOf(player.containerMenu)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
