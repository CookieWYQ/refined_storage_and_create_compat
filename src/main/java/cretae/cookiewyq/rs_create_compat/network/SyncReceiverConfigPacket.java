package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：网络资源接收器的<b>权威快照</b>
 * （名字 + 本网络已占用名字集合 + 操作结果 + 缓存摘要 + <b>资源列表的当前一页</b> + 输出面）。
 *
 * <h2>为什么必须有它</h2>
 * <p>客户端重建菜单时方块实体是 {@code null}（见 {@code NetworkResourceReceiverMenu} 两参构造器），
 * 因此名字、占用集合、以及「刚才那次改名 / 取出到底成功没有」都不能从客户端本地读。
 * 快照只在<b>变更时</b>下发（首次 {@code broadcastChanges}、改名被接受 / 被拒绝、取出动作之后、
 * 翻页、以及缓存指纹变化时每 {@code CACHE_SYNC_INTERVAL} tick 至多一次），绝不做「每帧拼一份」。</p>
 *
 * <h2>为什么物品内容不再走原版槽位、而是走这里</h2>
 * <p>第 96 轮把界面从「16 个固定镜像物品格」改成<b>可滚动资源列表</b>：缓存是
 * {@code RsccUnboundedItemStorage} / {@code RsccUnboundedFluidStorage}（种类无上限），
 * 固定格子必然漏掉第 17 种物品 / 第 5 种流体（用户实测：看不见也取不出）。
 * 现在列表<b>分页</b>下发（{@link #entries} 只带当前一页，每页 12 条），并带
 * 页码（{@link #page} / {@link #pageCount}）与总种类数（{@link #totalTypes}），
 * 因此界面不需要、也不允许自己遍历缓存。</p>
 *
 * <h2>为什么要带 {@code amount} 而不是直接用 {@code ItemStack#getCount}</h2>
 * <p>同一「物品 + 数据组件」在缓存里可能横跨多个内部槽位（每格 64、总容量 1728），
 * 界面要显示的是<b>这一种资源的总量</b>。聚合口径见 {@link ReceiverCacheEntry}。</p>
 *
 * <h2>dist 安全</h2>
 * <p>处理体只调 {@link ClientPayloadHooks}（双端都存在的间接层），
 * 包里不出现任何 {@code net.minecraft.client.*}。</p>
 */
public record SyncReceiverConfigPacket(int containerId, String name, List<String> occupiedNames,
                                       String messageKey, int itemTypes, long itemTotal,
                                       int fluidTypes, int fluidTotal, Direction facing,
                                       int page, int pageCount, int totalTypes,
                                       List<ReceiverCacheEntry> entries)
    implements CustomPacketPayload {

    public static final Type<SyncReceiverConfigPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_receiver_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncReceiverConfigPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.name() == null ? "" : packet.name());
                ByteBufCodecs.VAR_INT.encode(buf, packet.occupiedNames().size());
                for (final String occupied : packet.occupiedNames()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, occupied == null ? "" : occupied);
                }
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.messageKey() == null ? "" : packet.messageKey());
                ByteBufCodecs.VAR_INT.encode(buf, packet.itemTypes());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.itemTotal());
                ByteBufCodecs.VAR_INT.encode(buf, packet.fluidTypes());
                ByteBufCodecs.VAR_INT.encode(buf, packet.fluidTotal());
                // 6 向朝向上限极小：用序号编码，客户端按 Direction.values() 还原（顺序是枚举定义顺序）
                ByteBufCodecs.VAR_INT.encode(buf, packet.facing().ordinal());
                ByteBufCodecs.VAR_INT.encode(buf, packet.page());
                ByteBufCodecs.VAR_INT.encode(buf, packet.pageCount());
                ByteBufCodecs.VAR_INT.encode(buf, packet.totalTypes());
                ByteBufCodecs.VAR_INT.encode(buf, packet.entries().size());
                for (final ReceiverCacheEntry entry : packet.entries()) {
                    ReceiverCacheEntry.STREAM_CODEC.encode(buf, entry);
                }
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int size = ByteBufCodecs.VAR_INT.decode(buf);
                final List<String> occupied = new ArrayList<>(Math.max(0, size));
                for (int i = 0; i < size; i++) {
                    occupied.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                final String messageKey = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int itemTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final long itemTotal = ByteBufCodecs.VAR_LONG.decode(buf);
                final int fluidTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final int fluidTotal = ByteBufCodecs.VAR_INT.decode(buf);
                final int facingOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
                final Direction[] directions = Direction.values();
                final Direction facing = facingOrdinal >= 0 && facingOrdinal < directions.length
                    ? directions[facingOrdinal] : Direction.NORTH;
                final int page = ByteBufCodecs.VAR_INT.decode(buf);
                final int pageCount = Math.max(1, ByteBufCodecs.VAR_INT.decode(buf));
                final int totalTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final int entryCount = ByteBufCodecs.VAR_INT.decode(buf);
                final List<ReceiverCacheEntry> entries = new ArrayList<>(Math.max(0, entryCount));
                for (int i = 0; i < entryCount; i++) {
                    entries.add(ReceiverCacheEntry.STREAM_CODEC.decode(buf));
                }
                return new SyncReceiverConfigPacket(containerId, name, List.copyOf(occupied),
                    messageKey, itemTypes, itemTotal, fluidTypes, fluidTotal, facing,
                    page, pageCount, totalTypes, List.copyOf(entries));
            }
        );

    /**
     * 服务端工厂：把接收器的权威状态压成可下发的快照（<b>唯一</b>构造入口）。
     *
     * @param occupiedNames 本网络已占用的名字（由菜单缓存，只在改名时重算 —— 不在这里扫网络图）
     * @param page          当前页码（0 基；由菜单夹在 {@code [0, pageCount - 1]} 内）
     * @param pageCount     总页数（至少 1：空缓存也显示「第 1/1 页」）
     * @param totalTypes    <b>聚合后</b>的资源种类总数（物品种类 + 流体种类）
     * @param entries       <b>当前一页</b>的行（每页 12 条，见 {@code NetworkResourceReceiverMenu#PAGE_SIZE}）
     */
    public static SyncReceiverConfigPacket of(final int containerId,
                                              final NetworkResourceReceiverBlockEntity receiver,
                                              final List<String> occupiedNames,
                                              final String messageKey,
                                              final int page, final int pageCount,
                                              final int totalTypes,
                                              final List<ReceiverCacheEntry> entries) {
        return new SyncReceiverConfigPacket(containerId, receiver.getReceiverName(),
            List.copyOf(occupiedNames), messageKey == null ? "" : messageKey,
            receiver.getItemTypeCount(), receiver.getItemTotalCount(),
            receiver.getFluidTypeCount(), receiver.getFluidTotalAmount(), receiver.getFacing(),
            page, Math.max(1, pageCount), totalTypes, List.copyOf(entries));
    }

    public static void handle(final SyncReceiverConfigPacket packet, final IPayloadContext ctx) {
        // 先把包解码出来的不可变快照塞进双端共享的桥，再由 client/ClientPayloadSink 转给界面
        ctx.enqueueWork(() -> ClientPayloadHooks.get().syncNetworkResourceReceiver(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
