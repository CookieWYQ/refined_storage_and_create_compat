package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import cretae.cookiewyq.rs_create_compat.support.MarkerBudget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * C2S：给流程编排的某一步（全局下标）标记 / 清除「输入流体」。
 * <p>注液器（灌注）一类步骤消耗的是流体而不是物品，流程编排行因此必须支持流体标记；
 * 标记存在该步单元样板的 CustomData 里，会随步骤一起移动 / 删除。传入空流体 = 清除标记。</p>
 * <p>服务端权威：只接受「该步确实存在单元样板」的写入，且绝不消耗任何玩家资源。</p>
 */
public record SetStepFluidPacket(int stepIndex, FluidStack fluid) implements CustomPacketPayload {
    public static final Type<SetStepFluidPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_step_fluid"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetStepFluidPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetStepFluidPacket::stepIndex,
            FluidStack.OPTIONAL_STREAM_CODEC, SetStepFluidPacket::fluid,
            SetStepFluidPacket::new
        );

    public static void handle(final SetStepFluidPacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof SequencePatternTerminalMenu menu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null) {
            return;
        }
        // 第 116 轮：流体标记是<b>客户端给的 FluidStack</b>（1.21.1 的 FluidStack 带 DataComponentPatch，
        // 可含任意数据组件），会被写进单元样板的 CustomData 并永久落盘、随槽位同步回发 ——
        // 与物品标记同一类风险，同一把单条尺（≈2 KB 线上）。
        if (terminal.getLevel() != null
            && cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isFluidTooBig(
                packet.fluid(), terminal.getLevel().registryAccess())) {
            warnTooLarge();
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.rs_create_compat.sequence.marker_too_large"), true);
            return;
        }
        terminal.setStepFluidMarker(packet.stepIndex(),
            packet.fluid() == null ? FluidStack.EMPTY : packet.fluid().copyWithAmount(
                Math.max(1, packet.fluid().getAmount())));
        // 标记写进了单元样板本身（原版槽位同步会把它带给客户端），这里无需额外回包
    }

    /** 「流体数据过大已拒绝」的 WARN（第 116 轮）：每进程只打一条。 */
    private static boolean tooLargeWarned;

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("rs_create_compat/SetStepFluidPacket");

    private static void warnTooLarge() {
        if (!tooLargeWarned) {
            tooLargeWarned = true;
            LOGGER.warn("[rs_create_compat] 序列终端：拒绝了一次「步骤输入流体」写入 —— 流体自带的数据组件超过单条上限"
                + "（MarkerBudget.MAX_ENTRY_BYTES=" + MarkerBudget.MAX_ENTRY_BYTES + " 堆估算，≈2 KB 线上意图）；正常界面操作不会触发，若是改包，本条即证据");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
