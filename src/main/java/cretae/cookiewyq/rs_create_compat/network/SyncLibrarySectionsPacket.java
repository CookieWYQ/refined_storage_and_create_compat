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
 * S2C：序列装配样板终端「单元样板库」的<b>分区元数据</b>。
 * <p>每区对应一台执行仓（或末尾的「未匹配」区）：{@link Entry#pos()} 用于「存入」交互，
 * {@link Entry#name()} / {@link Entry#recipeType()} 是分区标题，{@link Entry#count()} 是该区
 * 在库中的样板张数（可见格只有 3 个，多于此数时标题显示总数）。</p>
 * <p>格子内容本身由真槽位的原版同步推送；本包只负责标题与「存入」目标，客户端仅镜像缓存。</p>
 */
public record SyncLibrarySectionsPacket(List<Entry> sections) implements CustomPacketPayload {
    /** 单个分区。{@code matched == false} 表示「未匹配执行仓」区（此时 pos 无意义）。 */
    public record Entry(boolean matched, BlockPos pos, String name, String recipeType, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, Entry::matched,
                BlockPos.STREAM_CODEC, Entry::pos,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                ByteBufCodecs.STRING_UTF8, Entry::recipeType,
                ByteBufCodecs.VAR_INT, Entry::count,
                Entry::new
            );
    }

    public static final Type<SyncLibrarySectionsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_library_sections"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncLibrarySectionsPacket> STREAM_CODEC =
        StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncLibrarySectionsPacket::sections,
            SyncLibrarySectionsPacket::new
        );

    /** 客户端最近一次收到的分区元数据（不可变；永不为 null）。 */
    private static volatile List<Entry> lastReceived = List.of();

    public static List<Entry> getLastReceived() {
        return lastReceived;
    }

    /** 由终端的分区视图构造同步包（只带元数据，不带物品）。 */
    public static SyncLibrarySectionsPacket from(
        final List<SequencePatternTerminalBlockEntity.LibrarySection> sections) {
        final List<Entry> entries = new ArrayList<>(sections.size());
        for (final SequencePatternTerminalBlockEntity.LibrarySection section : sections) {
            entries.add(new Entry(section.pos() != null, section.pos() == null ? BlockPos.ZERO : section.pos(),
                section.name() == null ? "" : section.name(),
                section.recipeType() == null ? "" : section.recipeType(),
                section.libraryIndices().size()));
        }
        return new SyncLibrarySectionsPacket(entries);
    }

    public static void handle(final SyncLibrarySectionsPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> lastReceived = List.copyOf(packet.sections()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
