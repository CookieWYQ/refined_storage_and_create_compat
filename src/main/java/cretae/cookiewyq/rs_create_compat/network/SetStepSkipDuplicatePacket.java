package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换流程编排<b>某一步</b>「生成时是否跳过已存在的相同单元样板」。
 *
 * <h2>为什么是「每一步」的包</h2>
 * <p>单元样板本身<b>不绑定机器</b>（判重只看语义，见
 * {@link cretae.cookiewyq.rs_create_compat.support.UnitPatternDedupe}）；真正绑定机器、且逐台机器
 * 执行的是<b>流程编排的每一步</b>。因此「这一步要不要跳过重复生成」必须挂到<b>步骤下标</b>上，
 * 而不是终端 / 机器上 —— 这与 {@link SetStepMachinePacket} 的「按步骤下标写回」完全同构。</p>
 *
 * <h2>服务端权威</h2>
 * <p>{@link #handle} 先校验「打开的是流程编排终端菜单」且「该终端真实存在」，再校验步骤下标
 * 落在 {@code [0, arrangementSize)}，通过后才写回 {@link SequencePatternTerminalBlockEntity#setStepSkipDuplicate}
 * 并立刻回传全量步骤快照（{@link SyncStepMachinesPacket}）。客户端只发目标值，不写任何权威状态。</p>
 *
 * @param stepIndex 全局步骤下标（与终端 arrangement 下标一致）
 * @param skip      目标状态：{@code true} = 生成时跳过重复（该步已有相同样板则不生成）
 */
public record SetStepSkipDuplicatePacket(int stepIndex, boolean skip) implements CustomPacketPayload {
    public static final Type<SetStepSkipDuplicatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_step_skip_duplicate"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetStepSkipDuplicatePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetStepSkipDuplicatePacket::stepIndex,
            ByteBufCodecs.BOOL, SetStepSkipDuplicatePacket::skip,
            SetStepSkipDuplicatePacket::new
        );

    public static void handle(final SetStepSkipDuplicatePacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof SequencePatternTerminalMenu menu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null || terminal.getLevel() == null) {
            return;
        }
        // 服务端校验：下标必须落在当前步数区间内（拒绝伪造 / 越界下标）
        if (packet.stepIndex() < 0 || packet.stepIndex() >= terminal.arrangementSize) {
            return;
        }
        terminal.setStepSkipDuplicate(packet.stepIndex(), packet.skip());
        // 写回成功：立刻回传全量快照，客户端据此刷新行内开关与「是否已有相同样板」的显示
        menu.sendStepMachines(player);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
