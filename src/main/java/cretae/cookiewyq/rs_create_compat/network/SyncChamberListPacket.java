package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：新语义（v4）—— 把当前终端网络内的执行仓列表回传给客户端。
 * <p>每项含：{@link Entry#pos()}（坐标）、{@link Entry#name()}（显示名）、
 * {@link Entry#recipeType()}（绑定的配方类型 id）。</p>
 * <p><b>接口约定（供下一阶段 GUI 使用）</b>：本包不直接驱动任何现有 Screen（前端重做前禁止改动），
 * 客户端处理器只把最近一次结果缓存到 {@link #getLastReceived()}；屏幕重做后可调用该方法读取，
 * 或在屏幕内自行注册处理器覆盖缓存。</p>
 */
public record SyncChamberListPacket(List<Entry> chambers) implements CustomPacketPayload {
    /** 单台执行仓的同步项。 */
    public record Entry(BlockPos pos, String name, String recipeType) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, Entry::pos,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                ByteBufCodecs.STRING_UTF8, Entry::recipeType,
                Entry::new
            );
    }

    public static final Type<SyncChamberListPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_chamber_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncChamberListPacket> STREAM_CODEC =
        StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncChamberListPacket::chambers,
            SyncChamberListPacket::new
        );

    /** 客户端最近一次收到的机器列表（不可变；屏幕重做前由前端读取）。 */
    private static volatile List<Entry> lastReceived = List.of();

    /** 客户端最近一次收到的机器列表（可能为空列表，永不为 null）。 */
    public static List<Entry> getLastReceived() {
        return lastReceived;
    }

    /** 由终端方块实体的 ChamberInfo 列表构造同步包。 */
    public static SyncChamberListPacket from(final List<SequencePatternTerminalBlockEntity.ChamberInfo> chambers) {
        final List<Entry> entries = new ArrayList<>(chambers.size());
        for (final SequencePatternTerminalBlockEntity.ChamberInfo info : chambers) {
            entries.add(new Entry(info.pos(), info.name(), info.recipeType()));
        }
        return new SyncChamberListPacket(entries);
    }

    public static void handle(final SyncChamberListPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> lastReceived = List.copyOf(packet.chambers()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
