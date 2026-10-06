package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：把「执行仓单元样板汇总」的行元数据回传给客户端（终端的汇总子界面）。
 * <p>每台执行仓一项：坐标 + 显示名 + 配方类型 id + 该仓单元样板槽内的实际样板物品
 * （前端据此绘制机器图标、名字、配方 id 与行 tooltip 的样板清单；<b>格子内容本身</b>
 * 由真槽位的原版同步推送）。</p>
 * <p>{@code page} 是服务端收敛后的权威页码（客户端不自行改页，避免标签与格子错位一帧）。</p>
 * <p>客户端只镜像缓存最近一次结果（{@link #getLastReceived()}），不参与任何权威写入。</p>
 */
public record SyncChamberUnitsPacket(List<Entry> chambers, int page) implements CustomPacketPayload {
    /** 单台执行仓的汇总项。 */
    public record Entry(BlockPos pos, String name, String recipeType, List<ItemStack> units) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, Entry::pos,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                ByteBufCodecs.STRING_UTF8, Entry::recipeType,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::units,
                Entry::new
            );
    }

    public static final Type<SyncChamberUnitsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_chamber_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncChamberUnitsPacket> STREAM_CODEC =
        StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncChamberUnitsPacket::chambers,
            ByteBufCodecs.VAR_INT, SyncChamberUnitsPacket::page,
            SyncChamberUnitsPacket::new
        );

    /** 客户端最近一次收到的汇总（不可变；未收到时为空列表）。 */
    private static volatile List<Entry> lastReceived = List.of();
    /** 客户端最近一次收到的权威页码。 */
    private static volatile int lastReceivedPage;

    /** 客户端最近一次收到的汇总（可能为空列表，永不为 null）。 */
    public static List<Entry> getLastReceived() {
        return lastReceived;
    }

    /** 客户端最近一次收到的权威页码。 */
    public static int getLastReceivedPage() {
        return lastReceivedPage;
    }

    /** 由终端方块实体的汇总视图列表构造同步包。 */
    public static SyncChamberUnitsPacket from(
        final List<SequencePatternTerminalBlockEntity.ChamberUnits> chambers, final int page) {
        final List<Entry> entries = new ArrayList<>(chambers.size());
        for (final SequencePatternTerminalBlockEntity.ChamberUnits units : chambers) {
            entries.add(new Entry(units.pos(), units.name(), units.recipeType(), List.copyOf(units.units())));
        }
        return new SyncChamberUnitsPacket(entries, Math.max(0, page));
    }

    public static void handle(final SyncChamberUnitsPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            lastReceived = List.copyOf(packet.chambers());
            lastReceivedPage = packet.page();
            // 汇总子界面打开时，把最新快照写回界面展示（客户端实现在 client 包）
            ClientPayloadHooks.get().syncChamberUnits(packet);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
