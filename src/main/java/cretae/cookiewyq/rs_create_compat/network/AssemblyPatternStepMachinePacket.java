package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * C2S：把总样板的某一步改绑到某台执行舱（由「总样板改绑机器」界面确认时发出）。
 *
 * <p><b>客户端只发请求</b>：目标坐标、名字都不被信任 —— 服务端会重新校验「这台机器在该网络里、
 * 类型与该步的配方类型完全相等、该步的单元样板搬得动」，然后<b>原地改写</b>玩家手里那一件物品，
 * 并把该步的单元样板从旧属主搬进目标机（见 {@code AssemblyPatternRebind#apply}）。</p>
 *
 * <p><b>必须在服务端主线程上执行</b>：处理体会写执行舱的 {@code unitSlots} 容器与玩家手上的物品栈，
 * 注册处（{@code RS_Create_Compat.registerPayloads}）用 {@code ctx.enqueueWork} 投递到主线程。</p>
 *
 * <p><b>刻意不带「机器名」</b>：写入 {@code MachineName} 时服务端一律用目标执行舱<b>自己的</b>
 * 显示名（{@code getChamberDisplayName()}），客户端提交的字符串一个字节都不采信。</p>
 *
 * @param handOrdinal 样板所在的手（{@code InteractionHand} 序号）
 * @param patternId   样板指纹（服务端据此确认「还是刚才那张样板」）
 * @param stepIndex   要改绑的步下标（0 起）
 * @param pos         目标执行舱坐标
 */
public record AssemblyPatternStepMachinePacket(int handOrdinal, UUID patternId, int stepIndex,
                                               BlockPos pos)
    implements CustomPacketPayload {

    public static final Type<AssemblyPatternStepMachinePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "assembly_pattern_step_machine"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyPatternStepMachinePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AssemblyPatternStepMachinePacket::handOrdinal,
            UUIDUtil.STREAM_CODEC, AssemblyPatternStepMachinePacket::patternId,
            ByteBufCodecs.VAR_INT, AssemblyPatternStepMachinePacket::stepIndex,
            BlockPos.STREAM_CODEC, AssemblyPatternStepMachinePacket::pos,
            AssemblyPatternStepMachinePacket::new
        );

    public static void handle(final AssemblyPatternStepMachinePacket packet, final ServerPlayer player) {
        cretae.cookiewyq.rs_create_compat.item.AssemblyPatternRebind.apply(player, packet);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
