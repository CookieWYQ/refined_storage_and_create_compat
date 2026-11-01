package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * C2S：归流缓存仓匹配区条目「确认」时发送，写入该条目的资源、需求量、
 * 「匹配 NBT（数据组件）」与「匹配标签集合」规则。
 * <ul>
 *     <li>{@code id == null} → 清除该下标标记；</li>
 *     <li>{@code fluid=true} → 该条目是流体/气体（{@code id} 为流体注册名）；</li>
 *     <li>{@code nbt} 为不透明负载：客户端从 {@link SyncCollectionMarkersPacket} 原样回传；
 *     新建条目时可为空，服务端按 {@code id} 解析；</li>
 *     <li>{@code tags} 为从示例物自身标签里多选出的匹配标签集合（空 = 只看这一个具体物品）。</li>
 * </ul>
 * 服务端在 {@link CollectionCacheMenu} 里做容器校验与越界校验后才落库。
 */
public record SetCollectionMarkerConfigPacket(int containerId,
                                              int markerIndex,
                                              boolean fluid,
                                              @Nullable ResourceLocation id,
                                              CompoundTag nbt,
                                              long amount,
                                              boolean matchNbt,
                                              List<ResourceLocation> tags) implements CustomPacketPayload {

    public static final Type<SetCollectionMarkerConfigPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_marker_config"));

    /**
     * 解码时<b>最多暂存</b>的标签条数（第 114 轮复核 H1）。
     * <p>服务端 {@code sanitizeTags} 还会再收敛成「代表物自带的标签」，正常标签集合远小于这个数
     * （界面是从示例物自身标签里多选的，一般个位数）。这里只是把「客户端谎报的计数」挡住，
     * 不让它变成一次 GB 级预分配。</p>
     */
    public static final int TAG_CAP = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionMarkerConfigPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.markerIndex());
                ByteBufCodecs.BOOL.encode(buf, packet.fluid());
                final boolean hasId = packet.id() != null;
                ByteBufCodecs.BOOL.encode(buf, hasId);
                if (hasId) {
                    ResourceLocation.STREAM_CODEC.encode(buf, packet.id());
                }
                ByteBufCodecs.COMPOUND_TAG.encode(buf, packet.nbt() == null ? new CompoundTag() : packet.nbt());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.amount());
                ByteBufCodecs.BOOL.encode(buf, packet.matchNbt());
                final List<ResourceLocation> tags = packet.tags() == null ? List.of() : packet.tags();
                ByteBufCodecs.VAR_INT.encode(buf, tags.size());
                for (final ResourceLocation tag : tags) {
                    ResourceLocation.STREAM_CODEC.encode(buf, tag);
                }
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int markerIndex = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean fluid = ByteBufCodecs.BOOL.decode(buf);
                final ResourceLocation id = ByteBufCodecs.BOOL.decode(buf)
                    ? ResourceLocation.STREAM_CODEC.decode(buf)
                    : null;
                final CompoundTag nbt = ByteBufCodecs.COMPOUND_TAG.decode(buf);
                final long amount = ByteBufCodecs.VAR_LONG.decode(buf);
                final boolean matchNbt = ByteBufCodecs.BOOL.decode(buf);
                final int tagCount = Math.max(0, ByteBufCodecs.VAR_INT.decode(buf));
                // 第 114 轮复核 H1：<b>不要</b>用客户端给的计数直接预分配 —— 改包发一个
                // Integer.MAX_VALUE 级的 count，就会在解码阶段（任何 menu 校验之前）尝试 GB 级分配 / OOME。
                // 第 98 轮在 SyncChargerFilterPacket 修过同一类缺陷（那里注明「不预分配」），这个包漏了。
                // 现在按「最多只保留 TAG_CAP 条」读：仍然按 count 循环消费字节（否则流会错位），但只暂存前 TAG_CAP 条。
                final int keep = Math.min(tagCount, TAG_CAP);
                final java.util.List<ResourceLocation> tags = new java.util.ArrayList<>(keep);
                for (int i = 0; i < tagCount; i++) {
                    final ResourceLocation tag = ResourceLocation.STREAM_CODEC.decode(buf);
                    if (i < keep) {
                        tags.add(tag);
                    }
                }
                return new SetCollectionMarkerConfigPacket(containerId, markerIndex, fluid, id, nbt,
                    amount, matchNbt, List.copyOf(tags));
            }
        );

    public static void handle(final SetCollectionMarkerConfigPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setMarkerConfig(packet.markerIndex(), packet.fluid(), packet.id(), packet.nbt(),
            packet.amount(), packet.matchNbt(), packet.tags());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
