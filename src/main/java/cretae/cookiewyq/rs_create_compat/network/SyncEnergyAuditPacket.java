package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：RS 网络<b>能耗监视快照</b>（服务端权威，一次性下发整份）。
 *
 * <h2>为什么整份下发、而不是增量</h2>
 * <p>界面模型是「只读列表 + 展开」：每次刷新都按<b>当前</b>读数重新排序、重新聚合，增量同步会让
 * 「排序」这件事在半路出现两种真相（客户端按旧序、服务端按新序）。整份快照每秒一次、几百条的上限
 * （Config {@code energyAuditMaxNodes} 默认 512）在网络上是可忽略的量级，换来的是「界面显示的数字
 * 永远等于服务端那一刻算出来的数字」。</p>
 *
 * <h2>字段口径</h2>
 * <ul>
 *     <li>{@link #bound()}：终端是否绑到一张<b>此刻可达</b>的网络（未绑定 / 坐标未加载 / 未成形 = false）；</li>
 *     <li>{@link #stored()} / {@link #capacity()}：绑定网络能量池的存量 / 容量（FE），只用于顶部摘要；</li>
 *     <li>{@link #totalCurrent()} / {@link #totalNominal()}：本网络所有耗电方块（<b>截断前</b>全量）的
 *     当前 / 标称合计。合计<b>永远按全量算</b>，不按「界面上看得到的那几条」算 —— 否则截断会让合计变小，
 *     玩家会以为「耗电变少了」；</li>
 *     <li>{@link #totalBlocks()} / {@link #totalTypes()}：截断前的台数 / 类型数；</li>
 *     <li>{@link #hiddenBlocks()} / {@link #hiddenTypes()}：因快照上限被丢掉的台数 / 类型数
 *     （&gt; 0 时界面必须显式显示「已截断」）；</li>
 *     <li>{@link #returnMode()}：玩家点「返回终端」时应切回的模式号（服务端在切进能耗页那一刻记下）；</li>
 *     <li>{@code hasSelection} / {@link #selectedDimension()} / {@link #selectedPos()}：服务端<b>校验通过</b>的
 *     高亮坐标。客户端只在这三个字段的基础上画框（绝不自己按本地列表猜坐标）。</li>
 * </ul>
 *
 * <h2>跨维度：如实说明（不假装）</h2>
 * <p>RS 2.0.9 的一{{@code Network}} 的容器只可能来自<b>同一个维度</b>（连接是
 * {@code ConnectionProviderImpl} 在同一 level 的相邻方块之间建立的）；跨维度是
 * transmitter / receiver <b>两张独立网络</b>互联，而不是一张网络挂两个维度的方块。
 * 因此本快照里的维度字段永远只有一个取值 —— 它存在是为了「如实显示这条方块在哪个维度」，
 * <b>不是</b>为了暗示「远端维度也在列表里」。界面顶部有对应提示语，不能让玩家以为漏了东西。</p>
 *
 * <h2>专用服务端安全</h2>
 * <p>处理体只经 {@link EnergyAuditClientBridge} 的 default 方法间接触达客户端，本类自身不出现任何
 * {@code net.minecraft.client.*} 类型（见该类的类注释）。</p>
 *
 * @param bound             终端是否绑到一张此刻可达的网络
 * @param stored            绑定网络能量池存量（FE）
 * @param capacity          绑定网络能量池容量（FE）
 * @param totalCurrent      截断前全量「当前」耗电合计（FE/t）
 * @param totalNominal      截断前全量「标称」耗电合计（FE/t）
 * @param totalBlocks       截断前方块台数
 * @param totalTypes        截断前方块类型数
 * @param hiddenBlocks      被截断丢弃的台数
 * @param hiddenTypes       被截断丢弃的类型数
 * @param returnMode        「返回终端」应切回的模式号
 * @param hasSelection      是否有服务端校验通过的选中方块
 * @param selectedDimension 选中方块所在维度（无选中时为 null）
 * @param selectedPos       选中方块坐标（无选中时为 {@link BlockPos#ZERO}）
 * @param types             按当前耗电合计降序的类型行
 */
public record SyncEnergyAuditPacket(boolean bound, long stored, long capacity,
                                    long totalCurrent, long totalNominal,
                                    int totalBlocks, int totalTypes,
                                    int hiddenBlocks, int hiddenTypes,
                                    int returnMode,
                                    boolean hasSelection,
                                    @Nullable ResourceLocation selectedDimension,
                                    BlockPos selectedPos,
                                    List<EnergyAuditType> types)
    implements CustomPacketPayload {

    /** 解码侧类型行硬上限（与 Config {@code energyAuditMaxTypes} 的上界 2048 一致）。 */
    public static final int MAX_TYPES_ON_WIRE = 2048;

    public static final Type<SyncEnergyAuditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_energy_audit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncEnergyAuditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.BOOL.encode(buf, packet.bound());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.stored());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.capacity());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.totalCurrent());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.totalNominal());
                ByteBufCodecs.VAR_INT.encode(buf, packet.totalBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, packet.totalTypes());
                ByteBufCodecs.VAR_INT.encode(buf, packet.hiddenBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, packet.hiddenTypes());
                ByteBufCodecs.VAR_INT.encode(buf, packet.returnMode());
                ByteBufCodecs.BOOL.encode(buf, packet.hasSelection());
                if (packet.hasSelection() && packet.selectedDimension() != null) {
                    ResourceLocation.STREAM_CODEC.encode(buf, packet.selectedDimension());
                    ByteBufCodecs.INT.encode(buf, packet.selectedPos().getX());
                    ByteBufCodecs.INT.encode(buf, packet.selectedPos().getY());
                    ByteBufCodecs.INT.encode(buf, packet.selectedPos().getZ());
                }
                ByteBufCodecs.VAR_INT.encode(buf, packet.types().size());
                for (final EnergyAuditType type : packet.types()) {
                    EnergyAuditType.STREAM_CODEC.encode(buf, type);
                }
            },
            buf -> {
                final boolean bound = ByteBufCodecs.BOOL.decode(buf);
                final long stored = ByteBufCodecs.VAR_LONG.decode(buf);
                final long capacity = ByteBufCodecs.VAR_LONG.decode(buf);
                final long totalCurrent = ByteBufCodecs.VAR_LONG.decode(buf);
                final long totalNominal = ByteBufCodecs.VAR_LONG.decode(buf);
                final int totalBlocks = ByteBufCodecs.VAR_INT.decode(buf);
                final int totalTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final int hiddenBlocks = ByteBufCodecs.VAR_INT.decode(buf);
                final int hiddenTypes = ByteBufCodecs.VAR_INT.decode(buf);
                final int returnMode = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean hasSelection = ByteBufCodecs.BOOL.decode(buf);
                ResourceLocation selectedDimension = null;
                BlockPos selectedPos = BlockPos.ZERO;
                if (hasSelection) {
                    selectedDimension = ResourceLocation.STREAM_CODEC.decode(buf);
                    selectedPos = new BlockPos(ByteBufCodecs.INT.decode(buf),
                        ByteBufCodecs.INT.decode(buf),
                        ByteBufCodecs.INT.decode(buf));
                }
                final int declared = ByteBufCodecs.VAR_INT.decode(buf);
                // 解码纪律：读完全部声明条目（否则字段错位），只保留前 MAX_TYPES_ON_WIRE 条。
                final List<EnergyAuditType> types = new ArrayList<>();
                for (int i = 0; i < declared; i++) {
                    final EnergyAuditType type = EnergyAuditType.STREAM_CODEC.decode(buf);
                    if (types.size() < MAX_TYPES_ON_WIRE) {
                        types.add(type);
                    }
                }
                return new SyncEnergyAuditPacket(bound, stored, capacity, totalCurrent, totalNominal,
                    totalBlocks, totalTypes, hiddenBlocks, hiddenTypes, returnMode,
                    hasSelection && selectedDimension != null, selectedDimension, selectedPos,
                    List.copyOf(types));
            }
        );

    public SyncEnergyAuditPacket {
        types = types == null ? List.of() : List.copyOf(types);
        selectedPos = selectedPos == null ? BlockPos.ZERO : selectedPos;
    }

    /** 「没有网络」的空快照（未绑定 / 坐标未加载 / 网络未成形时统一走这里，客户端显示空状态不崩）。 */
    public static SyncEnergyAuditPacket empty(final int returnMode) {
        return new SyncEnergyAuditPacket(false, 0L, 0L, 0L, 0L, 0, 0, 0, 0,
            returnMode, false, null, BlockPos.ZERO, List.of());
    }

    public static void handle(final SyncEnergyAuditPacket packet, final IPayloadContext ctx) {
        // 客户端专属落地（开界面 / 刷新界面 / 喂高亮）全部在 client 包，经双端安全的桥间接调用；
        // 写客户端状态必须回主线程 ⇒ enqueueWork。
        ctx.enqueueWork(() -> EnergyAuditClientBridge.get().onSnapshot(packet));
    }

    /** 是否因为快照上限被截断（界面必须显式提示，否则玩家会以为网络里就这么多）。 */
    public boolean truncated() {
        return hiddenBlocks > 0 || hiddenTypes > 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
