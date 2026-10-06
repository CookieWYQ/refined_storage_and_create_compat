package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * S2C：「更换机器」的候选执行仓（响应 {@link AssemblyStepMachinePacket} 的请求动作）。
 *
 * <p>客户端收到后<b>复用既有的机器选择子界面</b>（{@code client/screen/StepMachineSelectScreen}）
 * 弹出选择器 —— 不新写一套选择器；确认时通过该界面的「确认回调」发
 * {@link AssemblyStepMachinePacket}（写入动作），而不是样板终端的既有包。</p>
 */
public record AssemblyMachineCandidatesPacket(UUID taskId, int stepIndex, String recipeType,
                                              String currentMachineName,
                                              List<SyncChamberListPacket.Entry> candidates)
    implements CustomPacketPayload {
    public static final Type<AssemblyMachineCandidatesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "assembly_machine_candidates"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyMachineCandidatesPacket> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, AssemblyMachineCandidatesPacket::taskId,
            ByteBufCodecs.VAR_INT, AssemblyMachineCandidatesPacket::stepIndex,
            ByteBufCodecs.STRING_UTF8, AssemblyMachineCandidatesPacket::recipeType,
            ByteBufCodecs.STRING_UTF8, AssemblyMachineCandidatesPacket::currentMachineName,
            SyncChamberListPacket.Entry.STREAM_CODEC.apply(ByteBufCodecs.list()),
            AssemblyMachineCandidatesPacket::candidates,
            AssemblyMachineCandidatesPacket::new
        );

    /** 客户端：只在「当前打开的还是自动合成管理器」时弹选择器（避免玩家已离开界面时被强行切屏）。 */
    public static void handle(final AssemblyMachineCandidatesPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHooks.get().openMachineSelect(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
