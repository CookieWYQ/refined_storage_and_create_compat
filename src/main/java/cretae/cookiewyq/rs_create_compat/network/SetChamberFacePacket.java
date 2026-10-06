package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：设置某台序列执行仓「某一个面」的工作模式（玩家在面配置子界面点击方块六个面）。
 * <p>服务端解析目标（与 {@link SetChamberBindingPacket} 同一套规则：优先当前打开的执行仓菜单，
 * 回退按坐标 + ≤64 格距离校验），写入后回发 {@link SyncChamberBindingPacket}
 * （其中带全部 6 个面的权威模式），客户端界面据此刷新。</p>
 */
public record SetChamberFacePacket(BlockPos pos, int direction, int mode) implements CustomPacketPayload {
    /** 占位坐标「由服务端按当前打开的执行仓菜单解析目标」（客户端拿不到坐标时使用）。 */
    public static final BlockPos NO_POS = BlockPos.ZERO;
    public static final Type<SetChamberFacePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_chamber_face"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetChamberFacePacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetChamberFacePacket::pos,
            ByteBufCodecs.VAR_INT, SetChamberFacePacket::direction,
            ByteBufCodecs.VAR_INT, SetChamberFacePacket::mode,
            SetChamberFacePacket::new
        );

    public static void handle(final SetChamberFacePacket packet, final ServerPlayer player) {
        if (packet.direction() < 0 || packet.direction() >= Direction.values().length) {
            return;
        }
        final SequenceExecutionChamberBlockEntity.FaceMode[] modes =
            SequenceExecutionChamberBlockEntity.FaceMode.values();
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
        final Direction direction = Direction.from3DDataValue(packet.direction());
        // 越界方向一律拒绝（防御性；Direction.from3DDataValue 对越界值会抛异常，故前面已先校验）
        if (direction == null) {
            return;
        }
        chamber.setFaceMode(direction, modes[packet.mode()]);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            SyncChamberBindingPacket.of(chamber));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
