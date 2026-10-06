package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：新语义（v4）—— 把某一装配步骤指派到某台执行仓。
 * <p>{@code stepIndex} 为<b>全局步骤下标</b>（与终端方块实体的 arrangement 下标一致）。
 * {@code hasPos == false} 表示解除该步的机器指派（此时 {@code pos} 会被忽略，可传 {@link BlockPos#ZERO}）。</p>
 */
public record SetStepMachinePacket(int stepIndex, boolean hasPos, BlockPos pos, String name)
    implements CustomPacketPayload {
    public static final Type<SetStepMachinePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_step_machine"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetStepMachinePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetStepMachinePacket::stepIndex,
            ByteBufCodecs.BOOL, SetStepMachinePacket::hasPos,
            BlockPos.STREAM_CODEC, SetStepMachinePacket::pos,
            ByteBufCodecs.STRING_UTF8, SetStepMachinePacket::name,
            SetStepMachinePacket::new
        );

    public static void handle(final SetStepMachinePacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof SequencePatternTerminalMenu menu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null) {
            return;
        }
        if (packet.hasPos()) {
            // 服务端权威：只接受「网络中真实存在的执行仓」坐标，拒绝伪造指派
            final boolean known = terminal.listChambers().stream()
                .anyMatch(chamber -> chamber.pos().equals(packet.pos()));
            if (!known) {
                return;
            }
            terminal.setStepMachine(packet.stepIndex(), packet.pos(), packet.name());
        } else {
            terminal.clearStepMachine(packet.stepIndex());
        }
        // 指派已变更：立刻回传全量快照，客户端据此刷新「◀ 机器名 ▶」
        menu.sendStepMachines(player);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
