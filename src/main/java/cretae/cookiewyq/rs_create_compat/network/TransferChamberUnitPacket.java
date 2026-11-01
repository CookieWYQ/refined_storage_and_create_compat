package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：在「执行仓单元样板汇总」里对<b>选中行</b>整批取回 / 存入单元样板（服务端权威）。
 * <ul>
 *     <li>{@link #ACTION_PULL} 取回：把该执行仓里的单元样板<b>逐张</b>搬回终端的单元样板库
 *     （库内一格一张；库满 / 体积预算拒绝时剩下的原样留在执行仓，绝不销毁 —— 第 119 轮）；</li>
 *     <li>{@link #ACTION_PUSH} 存入：把终端库里<b>配方类型匹配</b>的单元样板<b>逐张</b>搬进该执行仓
 *     （类型不匹配的不动，仓满则留在库里；旧档同格多张也能逐张取出 —— 第 119 轮）。</li>
 * </ul>
 * 两条路径都会二次校验「该坐标确实是本终端网络内的执行仓」（{@code chamberAt}），
 * 完成后再回传一次 {@link SyncChamberUnitsPacket}（含权威页码），界面即时刷新。
 */
public record TransferChamberUnitPacket(BlockPos pos, int action) implements CustomPacketPayload {
    /** 取回：执行仓 → 终端单元样板库。 */
    public static final int ACTION_PULL = 0;
    /** 存入：终端单元样板库 → 执行仓（按配方类型匹配）。 */
    public static final int ACTION_PUSH = 1;

    public static final Type<TransferChamberUnitPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "transfer_chamber_unit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransferChamberUnitPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, TransferChamberUnitPacket::pos,
            ByteBufCodecs.VAR_INT, TransferChamberUnitPacket::action,
            TransferChamberUnitPacket::new
        );

    public static void handle(final TransferChamberUnitPacket packet, final ServerPlayer player) {
        final SequencePatternTerminalBlockEntity terminal =
            ChamberUnitsSummaryMenu.terminalOf(player.containerMenu);
        if (terminal == null) {
            return;
        }
        // 只接受网络内真实存在的执行仓坐标（terminal 内部按网络容器列表校验）
        final boolean push = packet.action() == ACTION_PUSH;
        final int moved = push
            ? terminal.pushUnitsTo(packet.pos())
            : terminal.pullUnitsFrom(packet.pos());
        // 第 119 轮：库满（格数 / 合计体积）拒绝时，旧实现只在「一张都没搬」时提示
        // 「该仓没有样板 / 仓已满」，部分搬运被拒完全静默。现在补一条可见提示：
        // 未搬走的单元样板一定原样留在原处（pull / push 都是「先确认能写、再写、最后清空」）。
        if (terminal.lastUnitTransferBlockedByLibrary()) {
            player.displayClientMessage(Component.translatable(
                "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.blocked", moved), true);
        } else if (moved <= 0) {
            player.displayClientMessage(Component.translatable(push
                ? "gui.rs_create_compat.sequence_pattern_terminal.summary.push.none"
                : "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.none"), true);
        }
        PacketDistributor.sendToPlayer(player, SyncChamberUnitsPacket.from(terminal.listChamberUnits(),
            ChamberUnitsSummaryMenu.pageOf(player.containerMenu)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
