package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：把「<b>本总线朝向的工位</b>」的只读快照同步给输出总线界面（第 104 轮 B-4）。
 *
 * <h2>为什么需要一条独立的最小同步（而不是改既有载荷）</h2>
 * <p>类别快照 {@link RsccBusCategory} 是既有载荷（{@code SyncExporterExecutorModePacket} 的元素类型），
 * 而第 39 / 41 轮的自检把「类别快照的字段数不变、不新增协议字段」写成了<b>硬断言</b>
 * （{@code bus_category.count("ByteBufCodecs.STRING_UTF8.encode") == 7}）—— 那是刻意的保护：
 * 类别记录是搬运判定的镜像，不该被界面需求拖着长字段。因此工位展示走<b>这一条独立的最小包</b>：
 * 它只服务界面、不参与任何判定，也不碰类别记录的编解码。</p>
 *
 * <h2>内容（全部只读）</h2>
 * <ul>
 *     <li>{@link #containerId()}：容器 id（客户端按它校验归属，避免把上一台总线的状态用到新打开的那台）；</li>
 *     <li>{@link #known()}：是否判出了「本总线朝向的是一台可识别的加工机器」；</li>
 *     <li>{@link #blockId()} / {@link #targetPos()}：本总线<b>朝向的那一格</b>的方块注册 id + 坐标
 *     （客户端用方块自己的名字本地化，不需要另外传显示名）；</li>
 *     <li>{@link #stepTypeId()}：该工位负责的步类型 id（{@code create:pressing} 这类）；</li>
 *     <li>{@link #stepsCsv()}：该工位负责的单循环步序（{@code ,} 分隔，升序）；</li>
 *     <li>{@link #derivedCsv()}：该工位推导出的默认导出类别 id（B-1 的结果，空 = 退回旧默认集）；</li>
 *     <li>{@link #mismatch()}：玩家显式勾选与本工位推导集<b>不一致</b>（界面只黄字提示、不拦）。</li>
 * </ul>
 *
 * <p>发送侧由执行舱在 {@code BUS_SCHEDULE_INTERVAL_TICKS} 节拍里按「状态变化才发」推送
 * （见 {@code SequenceExecutionChamberBlockEntity#pushStationViewsToViewers()}），
 * 因此稳态零发包、打开界面 1 秒内一定收到。</p>
 */
public record RsccBusStationSync(int containerId, boolean known, String blockId, long targetPos,
                                 String stepTypeId, String stepsCsv, String derivedCsv,
                                 boolean mismatch) implements CustomPacketPayload {
    public static final Type<RsccBusStationSync> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_bus_station"));

    /**
     * 手写编解码（逐字段写，字段顺序 = 记录构造参数顺序；两端一致）。
     * <p>刻意不复用 {@link StreamCodec#composite}：记录已有 8 个字段，composite 最多 6 组。</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RsccBusStationSync> STREAM_CODEC =
        StreamCodec.of(RsccBusStationSync::encode, RsccBusStationSync::decode);

    private static void encode(final RegistryFriendlyByteBuf buf, final RsccBusStationSync value) {
        ByteBufCodecs.VAR_INT.encode(buf, value.containerId());
        ByteBufCodecs.BOOL.encode(buf, value.known());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.blockId() == null ? "" : value.blockId());
        ByteBufCodecs.VAR_LONG.encode(buf, value.targetPos());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.stepTypeId() == null ? "" : value.stepTypeId());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.stepsCsv() == null ? "" : value.stepsCsv());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.derivedCsv() == null ? "" : value.derivedCsv());
        ByteBufCodecs.BOOL.encode(buf, value.mismatch());
    }

    private static RsccBusStationSync decode(final RegistryFriendlyByteBuf buf) {
        return new RsccBusStationSync(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.BOOL.decode(buf)
        );
    }

    /** 该工位负责的步序（解析失败 ⇒ 空表；界面**不截断**、按可用宽度排版）。 */
    public List<Integer> steps() {
        final List<Integer> values = new ArrayList<>();
        if (stepsCsv == null || stepsCsv.isEmpty()) {
            return values;
        }
        for (final String part : stepsCsv.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(part.trim()));
            } catch (final NumberFormatException ignored) {
                // 坏字段忽略（界面按「判不出」显示），绝不抛
            }
        }
        return values;
    }

    /** 该工位推导出的默认导出类别 id（空 = 退回旧默认集）。 */
    public List<String> derivedIds() {        if (derivedCsv == null || derivedCsv.isEmpty()) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (final String part : derivedCsv.split(",")) {
            if (!part.isEmpty()) {
                values.add(part);
            }
        }
        return values;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ==================== 客户端缓存（只镜像「最近一次」；按 containerId 校验归属） ====================
    /** 客户端：最近一次同步的容器 id（-1 = 尚未收到）。 */
    private static volatile int lastContainerId = -1;
    /** 客户端：最近一次同步的快照（{@code null} = 尚未收到）。 */
    @Nullable
    private static volatile RsccBusStationSync last;

    /**
     * 客户端 handler（由 {@code RS_Create_Compat#registerPayloadHandlers} 注册）。
     * <p><b>不在本类里引用任何客户端专用类型</b>：包类在服务端也会被加载（注册时），
     * 因此这里只写数据，渲染交给 {@code client/widget/RsccStationBadge}。</p>
     */
    public static void handle(final RsccBusStationSync payload,
                              final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            lastContainerId = payload.containerId();
            last = payload;
        });
    }

    /** 客户端：该容器最近一次的工位快照（{@code null} = 尚未收到 / 属于别的容器）。 */
    @Nullable
    public static RsccBusStationSync forContainer(final int containerId) {
        final RsccBusStationSync snapshot = last;
        return snapshot != null && snapshot.containerId() == containerId ? snapshot : null;
    }
}
