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
 * <p><b>{@code name} 只是客户端回显用的冗余字段（第 120 轮 F5）</b>：服务端<b>从不信任</b>它 ——
 * 目标坐标校验通过后，显示名直接取该执行仓自己的服务端显示名
 * （{@code SequenceExecutionChamberBlockEntity#getChamberDisplayName()}，即
 * {@code SequencePatternTerminalBlockEntity#listChambers()} 里下发给界面的同一个值）。
 * 客户端的 {@code name} 只在「按 pos 临时取不到显示名」时兜底，且一律按 256 字符夹紧
 * （口径同第 116 轮 {@code MAX_IMPORT_STRING_LENGTH}）。因此伪造 {@code name} 既不能把
 * 64KB 字符串写进存档，也不能在界面上冒充别的机器。</p>
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
            // 服务端权威：① 只接受「网络中真实存在的执行仓」坐标，拒绝伪造指派；
            // ② 名字也不用客户端给的 —— 直接读该仓的服务端显示名（与下发给界面的同一来源）。
            //    客户端的 name 只在「按 pos 临时取不到显示名」时兜底，且由 setStepMachine 统一夹到 256 字符。
            final String serverName = terminal.listChambers().stream()
                .filter(chamber -> chamber.pos().equals(packet.pos()))
                .map(SequencePatternTerminalBlockEntity.ChamberInfo::name)
                .findFirst()
                .orElse(null);
            if (serverName == null) {
                return; // 坐标不在本网络（或该仓尚未完成绑定）：维持原有的「不接受伪造指派」语义
            }
            terminal.setStepMachine(packet.stepIndex(), packet.pos(),
                serverName.isEmpty() ? packet.name() : serverName);
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
