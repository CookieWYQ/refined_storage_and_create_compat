package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * S2C：高级归流缓存仓的<b>区域列表权威快照</b>（第 106 轮，用户第 5 / 6 条反馈）。
 *
 * <h2>为什么这张表必须由服务端算、再下发</h2>
 * <p>旧实现里区域列表是界面直接读<b>选定器物品</b>的数据组件画出来的：客户端把物品上<b>所有</b>
 * 区域一字排开，而「哪些区域真的能被这台机器用」是服务端才有资格判定的事 ——
 * 维度不符 / 被 {@link AreaSelection#sanitize} 截掉的超限区域 / 空选定器，在服务端
 * （{@code AdvancedCollectionCacheBlockEntity#resolveSelection}）里都<b>不参与扫描</b>，
 * 客户端却照列不误。于是玩家看到的是「列表里有、点了也高亮、但根本不吸」（用户原话：
 * 「有的时候还会选那个根本不存在的区域，就导致我标记不了，我还以为是坏了」）。</p>
 * <p>现在口径反过来：<b>服务端把「真正可用」的区域算好下发，客户端只画这一份</b>。
 * 客户端不再自己判断维度 / 上限 / 取舍，因此「列表里出现的 = 能吸的」恒成立。</p>
 *
 * <h2>字段口径</h2>
 * <ul>
 *     <li>{@link #status()}：与 {@code AdvancedCollectionCacheBlockEntity.AREA_STATUS_*} 同一套码
 *     （0 没装 / 1 生效 / 2 区域为空 / 3 维度不符）。界面据此给出「不画框 / 不列行」的原因；</li>
 *     <li>{@link #dimension()}：这份选定器绑定的维度（空串 = 未绑定），界面显示「维度：…」；</li>
 *     <li>{@link #entries()}：<b>可用</b>区域（顺序与选定器里的原始顺序一致，每项带
 *     {@link AreaEntry#rawIndex()} —— 客户端执行编辑动作时回传这个<b>原始下标</b>，
 *     因为服务端写入路径（{@code AreaSelectorEditPacket}）永远按原始下标定位）；</li>
 *     <li>{@link #selectedRawIndex()} / {@link #editingRawIndex()}：当前选中项 / 当前正在编辑过滤的区域
 *     （都以原始下标表示；{@code -1} = 没有）；</li>
 *     <li>{@link #unusableCount()}：被服务端过滤掉、<b>不列出</b>的区域个数（超限 / 维度不符），
 *     界面用一句话说明「有 N 个区域不参与扫描」，不静默消失；</li>
 *     <li>{@link #rejectCode()}：服务端对<b>最近一次</b>区域写入动作的拒绝原因
 *     （0 = 没有；其余见 {@code AreaSelectorEditPacket.REJECT_*}）。用户第 5 条
 *     「标记失败还不说为什么」就靠这条回传；{@link #rejectSeq()} 是单调序号，
 *     客户端只在序号变大时提示一次（不刷屏）。</li>
 * </ul>
 *
 * <h2>每个区域为什么还要带过滤图标</h2>
 * <p>用户第 1 / 2 条：「没有一个直观的方式直接显示过滤器」「过滤器在方块里面一层层点，不直观」。
 * 快照里每个区域附带它<b>前若干条</b>过滤条目（{@link #SNAPSHOT_FILTER_ICONS} 条上限，
 * 只带非空 / 非流体的物品条目），区域列表每行右侧因此能直接画出「这个区域吸什么」的图标摘要。</p>
 *
 * <h2>为什么这份快照不经过 {@code ClientPayloadHooks}</h2>
 * <p>它没有「必须通知某个界面」的语义 —— 界面每帧读 {@link #lastFor(int)} 即可（与
 * {@code SyncChamberUnitsPacket#getLastReceived()} 同一先例），因此本类<b>不含任何
 * {@code net.minecraft.client.*} 类型</b>，专用服务端注册期安全。</p>
 */
public record AdvancedAreaSnapshotPacket(int containerId,
                                         int status,
                                         String dimension,
                                         List<AreaEntry> entries,
                                         int selectedRawIndex,
                                         int editingRawIndex,
                                         int unusableCount,
                                         int rejectCode,
                                         int rejectSeq)
    implements CustomPacketPayload {

    /** 每个区域最多带几条过滤图标（界面一屏也只画得下这么多；多出来的用「+N」表示）。 */
    public static final int SNAPSHOT_FILTER_ICONS = 8;

    /** 最近一次收到的快照（客户端只读；服务端不会读它）。 */
    @Nullable
    private static volatile AdvancedAreaSnapshotPacket last;

    /**
     * 单个「可用区域」条目。
     *
     * @param rawIndex 选定器里的<b>原始</b>下标（编辑动作回传它）
     * @param area     区域（含名字，见 {@link Area#name()}）
     * @param filters  该区域的前若干条过滤条目（物品类；用来画图标摘要）
     */
    public record AreaEntry(int rawIndex, Area area, List<MarkerEntry> filters) {
        public static final StreamCodec<RegistryFriendlyByteBuf, AreaEntry> STREAM_CODEC = StreamCodec.of(
            (buf, entry) -> {
                ByteBufCodecs.VAR_INT.encode(buf, entry.rawIndex());
                Area.STREAM_CODEC.encode(buf, entry.area());
                final List<MarkerEntry> filters = entry.filters() == null ? List.of() : entry.filters();
                ByteBufCodecs.VAR_INT.encode(buf, filters.size());
                for (final MarkerEntry filter : filters) {
                    MarkerEntry.STREAM_CODEC.encode(buf, filter == null ? MarkerEntry.EMPTY : filter);
                }
            },
            buf -> {
                final int rawIndex = ByteBufCodecs.VAR_INT.decode(buf);
                final Area area = Area.STREAM_CODEC.decode(buf);
                final int count = Math.max(0, Math.min(SNAPSHOT_FILTER_ICONS, ByteBufCodecs.VAR_INT.decode(buf)));
                final List<MarkerEntry> filters = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    filters.add(MarkerEntry.STREAM_CODEC.decode(buf));
                }
                return new AreaEntry(rawIndex, area, List.copyOf(filters));
            }
        );

        /** 过滤图标条目数（界面画「+N」用）。 */
        public int filterCount() {
            return filters == null ? 0 : filters.size();
        }
    }

    public static final Type<AdvancedAreaSnapshotPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(
            RS_Create_Compat.MODID, "advanced_area_snapshot"));

    /**
     * 手写编解码（不走 {@code composite}）：分量有 9 个，而且 {@link AreaEntry} 内部是变长列表，
     * 手写比拼 8+1 个重载更不容易在版本升级时踩到「参数顺序」错误。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, AdvancedAreaSnapshotPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.status());
                ByteBufCodecs.STRING_UTF8.encode(buf, packet.dimension() == null ? "" : packet.dimension());
                final List<AreaEntry> entries = packet.entries() == null ? List.of() : packet.entries();
                ByteBufCodecs.VAR_INT.encode(buf, entries.size());
                for (final AreaEntry entry : entries) {
                    AreaEntry.STREAM_CODEC.encode(buf, entry);
                }
                ByteBufCodecs.VAR_INT.encode(buf, packet.selectedRawIndex());
                ByteBufCodecs.VAR_INT.encode(buf, packet.editingRawIndex());
                ByteBufCodecs.VAR_INT.encode(buf, Math.max(0, packet.unusableCount()));
                ByteBufCodecs.VAR_INT.encode(buf, packet.rejectCode());
                ByteBufCodecs.VAR_INT.encode(buf, packet.rejectSeq());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int status = ByteBufCodecs.VAR_INT.decode(buf);
                final String dimension = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int count = Math.max(0, Math.min(AreaSelection.ABSOLUTE_MAX_AREAS,
                    ByteBufCodecs.VAR_INT.decode(buf)));
                final List<AreaEntry> entries = new java.util.ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    entries.add(AreaEntry.STREAM_CODEC.decode(buf));
                }
                return new AdvancedAreaSnapshotPacket(containerId, status, dimension,
                    List.copyOf(entries),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf));
            }
        );

    public AdvancedAreaSnapshotPacket {
        // 网络边界的保险：上游塞超量 / null 时就地截断成「客户端能安全绘制」的形态。
        // 服务端自己已经按同一上限夹过，这里只是不信任任何输入。
        if (entries == null) {
            entries = List.of();
        } else if (entries.size() > AreaSelection.ABSOLUTE_MAX_AREAS) {
            entries = List.copyOf(entries.subList(0, AreaSelection.ABSOLUTE_MAX_AREAS));
        } else {
            entries = List.copyOf(entries);
        }
        if (dimension == null) {
            dimension = "";
        }
        if (status < 0) {
            status = 0;
        }
    }

    public static void handle(final AdvancedAreaSnapshotPacket packet, final IPayloadContext ctx) {
        // 客户端专属镜像；本类不碰 net.minecraft.client.* ⇒ 专用服务端安全。
        ctx.enqueueWork(() -> last = packet);
    }

    /** 最近一次快照（任意菜单）。 */
    @Nullable
    public static AdvancedAreaSnapshotPacket last() {
        return last;
    }

    /**
     * 与给定菜单 id 匹配的最近一次快照（{@code null} = 还没收到）。
     * <p>按 containerId 过滤是必须的：同一个客户端可以先后打开两台机器的界面，
     * 旧快照留着会让新界面短暂显示上一台机器的区域。</p>
     */
    @Nullable
    public static AdvancedAreaSnapshotPacket lastFor(final int containerId) {
        final AdvancedAreaSnapshotPacket snapshot = last;
        return snapshot != null && snapshot.containerId() == containerId ? snapshot : null;
    }

    /** 客户端切界面时清掉镜像（避免跨机器串数据；界面 {@code removed()} 调它）。 */
    public static void clear() {
        last = null;
    }

    /** 某原始下标在快照里是否可用（{@code -1} 也返回 false）。 */
    @Nullable
    public AreaEntry entryFor(final int rawIndex) {
        for (final AreaEntry entry : entries) {
            if (entry.rawIndex() == rawIndex) {
                return entry;
            }
        }
        return null;
    }

    /** 快照里的条目数（= 可用区域数）。 */
    public int usableCount() {
        return entries == null ? 0 : entries.size();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
