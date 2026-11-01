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
 *
 * <h2>第 75 轮：本包是「每次都现算」的，<b>不</b>承载任何缓存列表</h2>
 * <p>服务端在 {@code AssemblyStepMachinePacket} 的 ACTION_REQUEST 分支里当场调用
 * {@code AssemblyWatchdog#machineCandidates}（枚举该网络当前的执行仓 + 按该步配方类型过滤），
 * 客户端收到就直接弹选择器、<b>不缓存</b>本包内容。因此「执行仓配置完成后候选人里没有它」不可能
 * 是本包的陈旧值：要么网络里确实没有<b>已配置完成</b>的执行仓，要么该步的类型与执行仓绑定的类型不相等。
 * 请求是逐次点击触发的（不是开界面时的一次性请求），因此这里不需要额外的事件推送。</p>
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
