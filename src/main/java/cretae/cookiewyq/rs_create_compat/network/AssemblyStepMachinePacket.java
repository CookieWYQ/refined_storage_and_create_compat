package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * C2S：自动合成管理器里「更换机器」的两步走。
 *
 * <ul>
 *     <li>{@code 0 = 请求候选}：服务端按该步的配方类型收集同网络内的序列执行仓，用
 *     {@link AssemblyMachineCandidatesPacket} 回传；</li>
 *     <li>{@code 1 = 写入指派}：服务端先校验该坐标是「该步配方类型下真实存在的执行仓」，再把总样板上
 *     这一步的指派改成它（原地改写同一张样板，不复制不销毁）。</li>
 * </ul>
 *
 * <p><b>必须在服务端主线程上执行</b>：两个动作都会读改样板库的容器与 {@code AssemblyWatchdog} 的记录表；
 * 注册处（{@code RS_Create_Compat.registerPayloads}）用 {@code ctx.enqueueWork} 投递到主线程。
 * 在网络线程上直接写容器会与服务端 tick 抢同一份库存，表现为「偶发点了没反应 / 状态损坏」。</p>
 */
public record AssemblyStepMachinePacket(UUID taskId, int action, int stepIndex, BlockPos pos, String name)
    implements CustomPacketPayload {
    /** 请求候选机器。 */
    public static final int ACTION_REQUEST = 0;
    /** 写入机器指派。 */
    public static final int ACTION_SET = 1;

    public static final Type<AssemblyStepMachinePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "assembly_step_machine"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyStepMachinePacket> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, AssemblyStepMachinePacket::taskId,
            ByteBufCodecs.VAR_INT, AssemblyStepMachinePacket::action,
            ByteBufCodecs.VAR_INT, AssemblyStepMachinePacket::stepIndex,
            BlockPos.STREAM_CODEC, AssemblyStepMachinePacket::pos,
            ByteBufCodecs.STRING_UTF8, AssemblyStepMachinePacket::name,
            AssemblyStepMachinePacket::new
        );

    public static void handle(final AssemblyStepMachinePacket packet, final ServerPlayer player) {
        if (packet.action() == ACTION_REQUEST) {
            final List<SyncChamberListPacket.Entry> candidates =
                AssemblyWatchdog.machineCandidates(player, packet.taskId(), packet.stepIndex());
            PacketDistributor.sendToPlayer(player, new AssemblyMachineCandidatesPacket(
                packet.taskId(), packet.stepIndex(),
                AssemblyWatchdog.stepRecipeType(player, packet.taskId(), packet.stepIndex()),
                AssemblyWatchdog.currentMachineName(player, packet.taskId(), packet.stepIndex()),
                candidates));
            return;
        }
        if (packet.action() != ACTION_SET) {
            return;
        }
        // 服务端权威：只接受「该步配方类型下真实存在的执行仓」，拒绝伪造坐标
        final boolean known = AssemblyWatchdog.machineCandidates(player, packet.taskId(), packet.stepIndex())
            .stream().anyMatch(entry -> entry.pos().equals(packet.pos()));
        if (!known) {
            player.displayClientMessage(
                Component.translatable("message.rs_create_compat.assembly.action_failed"), true);
            return;
        }
        final boolean ok = AssemblyWatchdog.changeStepMachine(player, packet.taskId(), packet.stepIndex(),
            packet.pos(), packet.name());
        player.displayClientMessage(Component.translatable(ok
            ? "message.rs_create_compat.assembly.machine_changed"
            : "message.rs_create_compat.assembly.action_failed"), true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
