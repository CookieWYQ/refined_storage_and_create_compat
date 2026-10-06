package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * S2C：把归流缓存仓「匹配区」<b>当前可见窗口</b>内每条 ghost 标记的完整信息
 * （物品/流体 + 数量 + NBT + tag 规则）同步给客户端，供界面绘制 ghost 物品/流体与叠层数量、
 * 判断重复标记，以及条目配置界面回填初值；同时携带「缓存区流体内容」快照。
 * <p>匹配区无上限后不再整表同步：{@code windowStart} 为该窗口第一条的全局下标，
 * {@code entries} 只含该窗口的条目（下标 {@code i} 对应全局 {@code windowStart + i}，
 * 未标记的格子为 {@link MarkerEntry#EMPTY}）；翻页时服务端再回传新窗口。
 * {@code fluidCache} 为流体缓存当前内容（{@code fluid=true}、{@code amount} 为 mB），可能为空列表。</p>
 * <p>{@code blocked} 为「阻塞名单」快照：客户端据此把被阻塞的匹配槽 / 缓存槽画成红色底
 * （醒目且不使用文字）。它含<b>四个名单</b>：具体物品 / 具体流体 / 物品标签 / 流体标签 ——
 * 后两个是<b>本轮新增</b>的「按标签阻塞」（用户第 ③ 条：按标签注册的条目点阻塞后，
 * 属于该标签的所有资源都必须显示为已阻塞）。</p>
 * <p>{@code destroyFlags} 为「直接销毁」匹配槽快照（本轮新增，与 {@code entries} <b>一一对应</b>）：
 * 客户端据此在匹配槽上画出「销毁模式」标识、并在匹配设置子窗口回填该开关的初值。
 * 它是<b>只读展示</b>数据，真正的销毁判定与执行全在服务端。</p>
 */
public record SyncCollectionMarkersPacket(int windowStart, List<MarkerEntry> entries, List<Boolean> destroyFlags,
                                          List<MarkerEntry> fluidCache,
                                          CollectionBlockedSnapshot blocked)
    implements CustomPacketPayload {

    /** 兼容旧调用点：空条目常量（等价于 {@link MarkerEntry#EMPTY}）。 */
    public static final MarkerEntry EMPTY = MarkerEntry.EMPTY;

    public static final Type<SyncCollectionMarkersPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_collection_markers"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCollectionMarkersPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncCollectionMarkersPacket::windowStart,
            MarkerEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncCollectionMarkersPacket::entries,
            ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), SyncCollectionMarkersPacket::destroyFlags,
            MarkerEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncCollectionMarkersPacket::fluidCache,
            CollectionBlockedSnapshot.STREAM_CODEC, SyncCollectionMarkersPacket::blocked,
            SyncCollectionMarkersPacket::new
        );

    /** 便捷构造：由「直接销毁」标志表 + 四个名单直接打包成一份快照（服务端同步链路用）。 */
    public static SyncCollectionMarkersPacket of(final int windowStart, final List<MarkerEntry> entries,
                                                final List<Boolean> destroyFlags,
                                                final List<MarkerEntry> fluidCache,
                                                final List<ResourceLocation> items,
                                                final List<ResourceLocation> fluids,
                                                final List<ResourceLocation> itemTags,
                                                final List<ResourceLocation> fluidTags) {
        return new SyncCollectionMarkersPacket(windowStart, entries, destroyFlags, fluidCache,
            CollectionBlockedSnapshot.of(items, fluids, itemTags, fluidTags));
    }

    /** 客户端：把条目配置、销毁标志与流体缓存快照写入当前打开的归流缓存仓界面（实现在 client 包）。 */
    public static void handle(final SyncCollectionMarkersPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPayloadHooks.get().syncCollectionMarkers(packet));
    }

    /** 取指定下标的条目（越界或未标记返回空条目）。 */
    public MarkerEntry entryAt(final int index) {
        if (index < 0 || index >= entries.size()) {
            return MarkerEntry.EMPTY;
        }
        final MarkerEntry entry = entries.get(index);
        return entry == null ? MarkerEntry.EMPTY : entry;
    }

    /** 取指定下标是否「直接销毁」（越界或缺失返回 false）。 */
    public boolean destroyAt(final int index) {
        if (destroyFlags == null || index < 0 || index >= destroyFlags.size()) {
            return false;
        }
        return Boolean.TRUE.equals(destroyFlags.get(index));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
