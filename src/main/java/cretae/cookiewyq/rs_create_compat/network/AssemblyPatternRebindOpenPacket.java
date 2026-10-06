package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * S2C：「总样板改绑机器」界面的权威数据（由 {@code AssemblyPatternRebind#openFor} 发出）。
 *
 * <p><b>为什么把步骤表也发过来</b>：客户端当然能读手上那件物品的 NBT，但那样界面就是
 * 「客户端自己算的」；每次写入成功后服务端都会<b>重发本包</b>，界面据此把「当前机器」
 * 刷新成服务端真值 —— 与工程里其它界面「服务端权威、客户端只镜像」的口径一致。</p>
 *
 * @param handOrdinal 样板所在的手（{@code InteractionHand} 的序号；写入时必须回到同一只手）
 * @param patternId   样板指纹（写入前校验，防止界面开着时玩家把样板换掉）
 * @param steps       每一步：步号 + 配方类型 + 当前机器（无指派时 {@code hasMachine = false}）
 * @param chambers    本网络里的候选执行仓（界面按每一步的配方类型过滤；服务端已按链去重）
 */
public record AssemblyPatternRebindOpenPacket(int handOrdinal, UUID patternId, List<Step> steps,
                                              List<SyncChamberListPacket.Entry> chambers)
    implements CustomPacketPayload {

    /**
     * 一步的展示快照。
     *
     * @param stepIndex   该步在总样板里的下标（0 起）
     * @param recipeType  该步的配方类型 id（空串 = 这张样板没记类型，改绑会被服务端拒绝）
     * @param machineName 当前指派机器的显示名（无指派 = 空串）
     * @param hasMachine  是否已指派（避免用坐标 0,0,0 当哨兵）
     * @param machinePos  当前指派坐标（无指派时为零坐标，仅用于展示兜底）
     */
    public record Step(int stepIndex, String recipeType, String machineName,
                       boolean hasMachine, BlockPos machinePos) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Step> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Step::stepIndex,
            ByteBufCodecs.STRING_UTF8, Step::recipeType,
            ByteBufCodecs.STRING_UTF8, Step::machineName,
            ByteBufCodecs.BOOL, Step::hasMachine,
            BlockPos.STREAM_CODEC, Step::machinePos,
            Step::new
        );
    }

    public static final Type<AssemblyPatternRebindOpenPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "assembly_pattern_rebind_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyPatternRebindOpenPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AssemblyPatternRebindOpenPacket::handOrdinal,
            UUIDUtil.STREAM_CODEC, AssemblyPatternRebindOpenPacket::patternId,
            Step.STREAM_CODEC.apply(ByteBufCodecs.list()), AssemblyPatternRebindOpenPacket::steps,
            SyncChamberListPacket.Entry.STREAM_CODEC.apply(ByteBufCodecs.list()),
            AssemblyPatternRebindOpenPacket::chambers,
            AssemblyPatternRebindOpenPacket::new
        );

    /**
     * 客户端：弹出（或就地刷新）改绑界面。已经在改绑界面上时<b>只刷新数据</b> ——
     * 界面上可能正开着「选择机器」的子界面，重开一次父界面会把玩家的子界面顶掉。
     */
    public static void handle(final AssemblyPatternRebindOpenPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHooks.get().openPatternRebind(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
