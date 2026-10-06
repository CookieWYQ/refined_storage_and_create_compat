package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：设置某台序列执行仓的<b>输出模式</b>（面输出 / 总线输出）。
 * <p>服务端解析目标（与 {@link SetChamberFacePacket} 同一套规则：优先当前打开的执行仓菜单，
 * 回退按坐标 + ≤64 格距离校验），写入后回发 {@link SyncChamberBindingPacket}
 * （其中带权威输出模式），客户端子界面据此刷新。</p>
 */
public record SetChamberOutputModePacket(BlockPos pos, int mode) implements CustomPacketPayload {
    /** 占位坐标「由服务端按当前打开的执行仓菜单解析目标」。 */
    public static final BlockPos NO_POS = BlockPos.ZERO;
    public static final Type<SetChamberOutputModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_chamber_output_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetChamberOutputModePacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetChamberOutputModePacket::pos,
            ByteBufCodecs.VAR_INT, SetChamberOutputModePacket::mode,
            SetChamberOutputModePacket::new
        );

    public static void handle(final SetChamberOutputModePacket packet, final ServerPlayer player) {
        final SequenceExecutionChamberBlockEntity.OutputMode[] modes =
            SequenceExecutionChamberBlockEntity.OutputMode.values();
        if (packet.mode() < 0 || packet.mode() >= modes.length) {
            return;
        }
        SequenceExecutionChamberBlockEntity chamber = null;
        if (player.containerMenu instanceof SequenceExecutionChamberMenu menu && menu.getChamber() != null) {
            chamber = menu.getChamber();
        }
        if (chamber == null) {
            final ServerLevel level = player.serverLevel();
            if (!level.isLoaded(packet.pos())) {
                return;
            }
            if (!(level.getBlockEntity(packet.pos()) instanceof SequenceExecutionChamberBlockEntity byPos)) {
                return;
            }
            if (player.distanceToSqr(packet.pos().getX() + 0.5,
                packet.pos().getY() + 0.5, packet.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            chamber = byPos;
        }
        chamber.setOutputMode(modes[packet.mode()]);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            SyncChamberBindingPacket.of(chamber));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
