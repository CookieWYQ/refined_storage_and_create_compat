package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * S2C：把「输出总线界面该用哪一套 + 类别快照」同步给客户端。
 * <p>发送时机：容器打开后由服务端在 {@code ExporterContainerMenu#broadcastChanges} 里发现变化时推送
 * （与 RS 自己的数据槽同节奏），因此客户端界面渲染时总是拿到当前打开的这台输出总线的权威状态。</p>
 *
 * <h2>{@code mode}：为什么用三态整数而不是三个布尔</h2>
 * <p>三种状态<b>互斥</b>，且 MC 的 {@code StreamCodec.composite} 最多只支持 6 组键值对
 * （本包原本已用满 6 组），因此把「是否延长型界面 + 是否被强制普通」收成<b>一个</b> {@code mode}：</p>
 * <ul>
 *     <li>{@link #MODE_PLAIN}：普通输出总线（没接到执行舱；界面完全等同 RS 原版）；</li>
 *     <li>{@link #MODE_EXECUTOR}：延长型界面（显示类别条 —— {@code rscc$isExecutorMode()} 为真）；</li>
 *     <li>{@link #MODE_FORCE_NORMAL}：<b>被玩家强制为普通总线</b>（用户原话：「赶紧做我要的按钮」）——
 *     界面上仍是<b>普通输出总线</b>（过滤器可编辑），但会多画一个「恢复为总线界面」按钮；</li>
 * </ul>
 * <p>注：{@code MODE_FORCE_NORMAL} 只影响「界面用哪套」。归属未确定时的「已停用红条 +
 * 显示可达区域按钮」由 {@link SyncBusInterferencePacket} 独立下发（服务端按<b>原始布局判定</b>发），
 * 因此强制普通后这些状态信息照旧显示（用户硬要求，不得丢）。</p>
 *
 * <ul>
 *     <li>{@code categories}：该执行仓当前给出的<b>有序类别列表</b>，每项带「本机是否已选 /
 *     正被几台输出总线共享」；</li>
 *     <li>{@code autoCrafting}：关联执行舱的「自动合成」是否已开启（服务端权威）；</li>
 *     <li>{@code linkedChamber}：本输出总线当前<b>归属的那台执行舱的显示名</b>；</li>
 *     <li>{@code targetStrategy}：原料供应策略是否为«直到目标产物达标»（服务端权威）。</li>
 * </ul>
 * <p>客户端只镜像缓存最近一次结果，并用 {@link #isExecutorMode(int)} / {@link #getCategories(int)}
 * 以<b>容器 id 校验</b>归属，避免把「上一台输出总线的状态」误用到新打开的那台上。</p>
 */
public record SyncExporterExecutorModePacket(int containerId, int mode, boolean autoCrafting,
                                             String linkedChamber, boolean targetStrategy,
                                             List<RsccBusCategory> categories)
    implements CustomPacketPayload {
    /** 普通输出总线（没接到执行舱）。 */
    public static final int MODE_PLAIN = 0;
    /** 延长型界面（显示类别条）。 */
    public static final int MODE_EXECUTOR = 1;
    /** 被玩家强制为普通总线（本轮新增；界面回到普通总线 + 多一个「恢复为总线界面」按钮）。 */
    public static final int MODE_FORCE_NORMAL = 2;
    /**
     * <b>可转换、但此刻用的是普通界面</b>（用户第 2 条：「这种可以被转换成延长输入输出总线的
     * 输入输出总线旁边都必须有一个普通 / 总线的切换按钮」）。
     * <p>出现这一态的唯一原因：线缆<b>确实够得到</b>处于「总线输出」的执行仓（{@code isLinkedLayout} 为真），
     * 但归属未确定（被干扰 → 延长型被停用）因而界面仍是普通总线。此时必须<b>照旧</b>把
     * 「普通 / 总线」切换按钮画出来（默认常驻），否则玩家只能等归属自己理清才有按钮可点。</p>
     */
    public static final int MODE_LINKED_PLAIN = 3;

    public static final Type<SyncExporterExecutorModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_exporter_executor_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncExporterExecutorModePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncExporterExecutorModePacket::containerId,
            ByteBufCodecs.VAR_INT, SyncExporterExecutorModePacket::mode,
            ByteBufCodecs.BOOL, SyncExporterExecutorModePacket::autoCrafting,
            ByteBufCodecs.STRING_UTF8, SyncExporterExecutorModePacket::linkedChamber,
            ByteBufCodecs.BOOL, SyncExporterExecutorModePacket::targetStrategy,
            RsccBusCategory.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncExporterExecutorModePacket::categories,
            SyncExporterExecutorModePacket::new
        );

    /**
     * 便捷构造：按「是否延长型界面 + 是否强制普通 + <b>是否可转换</b>」算出四态 {@code mode}。
     * <p>{@code linkedLayout} = 线缆<b>确实够得到</b>「总线输出」执行仓的<b>原始布局判定</b>（不含强制普通开关）。
     * 它只用来回答「这颗按钮该不该常驻」：为真 ⇒ 界面<b>无论当前用哪套</b>都画「普通 / 总线」按钮
     * （用户第 2 条：可以被转换成延长总线的输入输出总线旁都必须有这颗按钮）。</p>
     */
    public static int modeOf(final boolean executorMode, final boolean forceNormalBus,
                             final boolean linkedLayout) {
        if (forceNormalBus) {
            return MODE_FORCE_NORMAL;
        }
        if (executorMode) {
            return MODE_EXECUTOR;
        }
        return linkedLayout ? MODE_LINKED_PLAIN : MODE_PLAIN;
    }

    /** 本包描述的界面是否处于延长型输出模式。 */
    public boolean executorMode() {
        return mode == MODE_EXECUTOR;
    }

    /** 本包描述的总线是否被玩家强制为普通总线（界面据此画「恢复为总线界面」按钮）。 */
    public boolean forceNormalBus() {
        return mode == MODE_FORCE_NORMAL;
    }

    /** 客户端：最近一次同步所属的容器 id（-1 = 尚未收到）。 */
    private static volatile int lastContainerId = -1;
    private static volatile boolean lastExecutorMode;
    private static volatile boolean lastAutoCrafting;
    private static volatile String lastLinkedChamber = "";
    private static volatile boolean lastTargetStrategy;
    private static volatile boolean lastForceNormalBus;
    private static volatile List<RsccBusCategory> lastCategories = List.of();
    /** 客户端：本容器「可转换」（线缆真的够得到总线输出执行仓）—— 决定「普通 / 总线」按钮是否常驻。 */
    private static volatile boolean lastConvertible;

    /** 客户端：该容器当前是否处于延长型输出模式。 */
    public static boolean isExecutorMode(final int containerId) {
        return containerId >= 0 && containerId == lastContainerId && lastExecutorMode;
    }

    /**
     * 客户端：本容器是否<b>可转换</b>（线缆真的够得到处于「总线输出」的执行仓）。
     * <p>为真 ⇒ 界面上「普通 / 总线」切换按钮<b>默认常驻</b>（用户第 2 条），无论当前用的是普通界面
     * 还是类别条界面；为假（纯普通输出总线）⇒ 一个像素都不画，界面完全等同 RS 原版。</p>
     */
    public static boolean isConvertible(final int containerId) {
        return containerId >= 0 && containerId == lastContainerId && lastConvertible;
    }

    /** 客户端：该容器关联的执行舱「自动合成」是否已开启（不属于该容器 / 未同步时按未开启处理）。 */
    public static boolean isAutoCrafting(final int containerId) {
        return containerId == lastContainerId && lastAutoCrafting;
    }

    /** 客户端：该容器归属的执行舱显示名（不属于该容器 / 未同步时返回空串）。 */
    public static String getLinkedChamber(final int containerId) {
        return containerId == lastContainerId ? lastLinkedChamber : "";
    }

    /** 客户端：该容器是否处于«直到目标产物达标»供应策略（不属于该容器 / 未同步时按 false）。 */
    public static boolean isTargetStrategy(final int containerId) {
        return containerId == lastContainerId && lastTargetStrategy;
    }

    /** 客户端：该容器是否被玩家「强制为普通总线」（不属于该容器 / 未同步时按 false）。 */
    public static boolean isForceNormalBus(final int containerId) {
        return containerId == lastContainerId && lastForceNormalBus;
    }

    /** 客户端：该容器的类别快照（不属于该容器时返回空列表）。 */
    public static List<RsccBusCategory> getCategories(final int containerId) {
        return containerId == lastContainerId ? lastCategories : List.of();
    }

    public static void handle(final SyncExporterExecutorModePacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            lastContainerId = packet.containerId();
            lastExecutorMode = packet.executorMode();
            lastAutoCrafting = packet.autoCrafting();
            lastLinkedChamber = packet.linkedChamber() == null ? "" : packet.linkedChamber();
            lastTargetStrategy = packet.targetStrategy();
            lastForceNormalBus = packet.forceNormalBus();
            lastConvertible = packet.mode != MODE_PLAIN;
            lastCategories = List.copyOf(packet.categories());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
