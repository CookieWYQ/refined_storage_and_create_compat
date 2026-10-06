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
        terminal.setStepFluidMarker(packet.stepIndex(),
            packet.fluid() == null ? FluidStack.EMPTY : packet.fluid().copyWithAmount(
                Math.max(1, packet.fluid().getAmount())));
        // 标记写进了单元样板本身（原版槽位同步会把它带给客户端），这里无需额外回包
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
