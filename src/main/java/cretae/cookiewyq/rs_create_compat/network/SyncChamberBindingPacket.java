package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * S2C：新语义（v4）—— 把某台序列执行仓当前绑定的「坐标 + 配方类型 + 名字 + 六个面的模式 + 输出模式
 * + 链状态」同步到客户端。
 * <p>发送时机：① 容器打开时（{@code SequenceExecutionChamberMenu#broadcastChanges}）；
 * ② 玩家在绑定配置子界面确认后（{@code SetChamberBindingPacket} 服务端处理完回发权威值）；
 * ③ 在面配置子界面改面模式 / 输出模式后（各自的服务端处理完回发一次）；
 * ④ 链上任意一台改了「整链共享」的名字 / 配方类型后（{@code broadcastChainBinding} 推给链上
 * 每一台正在被查看的执行仓），这样客户端界面回填 / 展示的始终是服务端权威值。</p>
 * <p><b>链字段</b>（{@link SequenceExecutionChamberBlockEntity.ChainState}）：名字与配方类型在链上
 * <b>只有链首持有唯一那一份</b>，成员一律读链首，因此这里同步的 {@code recipeType / name} 就已经是
 * 整链一致的值；链字段只是让界面能显示「本台属于哪条链 / 链头是哪台 / 该值整链共享」。
 * 其中 {@code linkOrdinal} 是<b>方块朝向</b>（放置时那支箭头）的序号，仅用于只读展示，不可配置。</p>
 * <p><b>{@code occupiedNames}</b>：本台所在网络内「已被别的链占用的名字」集合
 * （服务端 {@code occupiedChamberNamesInNetwork()} 的权威结果）。它存在的唯一理由是让绑定配置界面
 * 能在玩家<b>输入名字时就实时判断重名并把「确定」按钮置灰</b>，而不是等提交后才被服务端拒回一条聊天消息；
 * 界面只做集合成员判断，因此与 {@code isNameTakenInNetwork} 的结论逐字一致。
 * 数据随本包在容器打开 / 每次绑定变更时下发，界面不必为每次输入另发请求。</p>
 * <p>客户端只镜像缓存最近一次结果（{@link #getLastReceived()}），不参与任何权威写入。</p>
 */
public record SyncChamberBindingPacket(BlockPos pos, String recipeType, String name,
                                       List<Integer> faceModes, int outputMode, boolean hasUnits,
                                       int linkOrdinal, int chainSize, boolean chainHead,
                                       BlockPos headPos, String headName, boolean chainUnits,
                                       List<String> occupiedNames)
    implements CustomPacketPayload {
    public static final Type<SyncChamberBindingPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_chamber_binding"));

    /** 字段数超过 {@code StreamCodec.composite} 的 6 个上限，故手写编解码（与工程内其它多字段包一致）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncChamberBindingPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                BlockPos.STREAM_CODEC.encode(buf, packet.pos());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.recipeType());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.name());
                ByteBufCodecs.VAR_INT.encode(buf, packet.faceModes().size());
                for (final Integer mode : packet.faceModes()) {
                    ByteBufCodecs.VAR_INT.encode(buf, mode);
                }
                ByteBufCodecs.VAR_INT.encode(buf, packet.outputMode());
                ByteBufCodecs.BOOL.encode(buf, packet.hasUnits());
                ByteBufCodecs.VAR_INT.encode(buf, packet.linkOrdinal());
                ByteBufCodecs.VAR_INT.encode(buf, packet.chainSize());
                ByteBufCodecs.BOOL.encode(buf, packet.chainHead());
                BlockPos.STREAM_CODEC.encode(buf, packet.headPos());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.headName());
                ByteBufCodecs.BOOL.encode(buf, packet.chainUnits());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, packet.occupiedNames());
            },
            buf -> {
                final BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                final String recipeType = ByteBufCodecs.STRING_UTF8.decode(buf);
                final String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int faceModeCount = ByteBufCodecs.VAR_INT.decode(buf);
                final List<Integer> faceModes = new java.util.ArrayList<>(faceModeCount);
                for (int i = 0; i < faceModeCount; i++) {
                    faceModes.add(ByteBufCodecs.VAR_INT.decode(buf));
                }
                final int outputMode = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean hasUnits = ByteBufCodecs.BOOL.decode(buf);
                final int linkOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
                final int chainSize = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean chainHead = ByteBufCodecs.BOOL.decode(buf);
                final BlockPos headPos = BlockPos.STREAM_CODEC.decode(buf);
                final String headName = ByteBufCodecs.STRING_UTF8.decode(buf);
                final boolean chainUnits = ByteBufCodecs.BOOL.decode(buf);
                final List<String> occupiedNames =
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf);
                return new SyncChamberBindingPacket(pos, recipeType, name, faceModes, outputMode, hasUnits,
                    linkOrdinal, chainSize, chainHead, headPos, headName, chainUnits, occupiedNames);
            }
        );

    /**
     * 服务端权威快照：<b>全部字段都从方块实体现读</b>（配方类型 / 名字经链委托取到的是链头那一份，
     * 「已被占用名字」也现算），因此客户端拿到的永远是权威值，不存在「客户端乐观写入后与服务端不一致」。
     */
    public static SyncChamberBindingPacket of(final SequenceExecutionChamberBlockEntity chamber) {
        final SequenceExecutionChamberBlockEntity.ChainState chain = chamber.chainState();
        return new SyncChamberBindingPacket(chamber.getBlockPos(), chamber.getRecipeType(),
            chamber.getChamberName(), chamber.faceModeOrdinals(), chamber.outputModeOrdinal(),
            chamber.hasAnyUnit(), chain.linkOrdinal(), chain.size(), chain.head(),
            chain.headPos(), chain.headName(), chain.chainUnits(),
            List.copyOf(chamber.occupiedChamberNamesInNetwork()));
    }

    /** 本台是否真的与其它执行仓串成了一条链（台数 &gt; 1）。 */
    public boolean chained() {
        return chainSize > 1;
    }

    /** 客户端最近一次收到的执行仓绑定（未收到时为 null）。 */
    private static volatile SyncChamberBindingPacket lastReceived;

    /** 客户端最近一次收到的执行仓绑定；未收到返回 null。 */
    public static SyncChamberBindingPacket getLastReceived() {
        return lastReceived;
    }

    public static void handle(final SyncChamberBindingPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            lastReceived = packet;
            // 面配置子界面打开时，用它把六个面的权威模式 + 输出模式刷新回界面（客户端实现在 client 包）
            ClientPayloadHooks.get().syncChamberBinding(packet);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
