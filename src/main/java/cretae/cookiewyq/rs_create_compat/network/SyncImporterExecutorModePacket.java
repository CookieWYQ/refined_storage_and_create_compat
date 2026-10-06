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
 * S2C：把「输入总线界面该用哪一套 + 类别快照」同步给客户端
 * —— {@link SyncExporterExecutorModePacket} 的对称版。
 * <p>发送时机：容器打开后由服务端在 {@code AbstractResourceContainerMenu#broadcastChanges} 里发现变化时
 * 推送（与 RS 自己的数据槽同节奏），因此客户端界面渲染时总是拿到当前打开的这台输入总线的权威状态。</p>
 *
 * <h2>{@code mode}：与输出总线侧同一套三态整数（理由见对侧注释：composite 最多 6 组键值对）</h2>
 * <ul>
 *     <li>{@link #MODE_PLAIN}：普通输入总线（没接到执行舱；界面完全等同 RS 原版）；</li>
 *     <li>{@link #MODE_EXECUTOR}：延长型界面（显示类别条 —— {@code rscc$isExecutorMode()} 为真）；</li>
 *     <li>{@link #MODE_FORCE_NORMAL}：被玩家强制为普通总线（本轮新增；界面回到普通总线 +
 *     多一个「恢复为总线界面」按钮）。</li>
 * </ul>
 *
 * <ul>
 *     <li>{@code categories}：该执行舱当前给出的<b>有序类别列表</b>，每项带「本机是否已勾选收回」；
 *     收回方向没有「多台均分」语义，因此共享台数恒为 1；</li>
 *     <li>{@code linkedChamber}：本输入总线当前<b>归属的那台执行舱的显示名</b>；</li>
 *     <li>{@code autoCollect}：是否处于<b>全自动收回</b>模式（默认是）；</li>
 *     <li>{@code manualSelected}：本机<b>存下来的那份勾选表</b>（手动模式真正生效的那一份 ——
 *     {@code categories} 里的 {@code selected} 是展示态，不等于玩家勾的那份）。</li>
 * </ul>
 * <p>客户端只镜像缓存最近一次结果，并用 {@link #isExecutorMode(int)} / {@link #getCategories(int)}
 * 以<b>容器 id 校验</b>归属，避免把「上一台输入总线的状态」误用到新打开的那台上。</p>
 */
public record SyncImporterExecutorModePacket(int containerId, int mode, String linkedChamber,
                                             boolean autoCollect, List<RsccBusCategory> categories,
                                             List<String> manualSelected)
    implements CustomPacketPayload {
    /** 普通输入总线（没接到执行舱）。 */
    public static final int MODE_PLAIN = 0;
    /** 延长型界面（显示类别条）。 */
    public static final int MODE_EXECUTOR = 1;
    /** 被玩家强制为普通总线（本轮新增）。 */
    public static final int MODE_FORCE_NORMAL = 2;
    /**
     * <b>可转换、但此刻用的是普通界面</b>（用户第 2 条）—— 线缆真的够得到「总线输出」执行仓，
     * 但归属未确定（被干扰 → 延长型被停用）因而界面仍是普通输入总线。此时「普通 / 总线」切换按钮
     * 仍必须<b>默认常驻</b>。语义与输出总线侧完全对称。
     */
    public static final int MODE_LINKED_PLAIN = 3;

    public static final Type<SyncImporterExecutorModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_importer_executor_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncImporterExecutorModePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncImporterExecutorModePacket::containerId,
            ByteBufCodecs.VAR_INT, SyncImporterExecutorModePacket::mode,
            ByteBufCodecs.STRING_UTF8, SyncImporterExecutorModePacket::linkedChamber,
            ByteBufCodecs.BOOL, SyncImporterExecutorModePacket::autoCollect,
            RsccBusCategory.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncImporterExecutorModePacket::categories,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
            SyncImporterExecutorModePacket::manualSelected,
            SyncImporterExecutorModePacket::new
        );

    /** 便捷构造：按「是否延长型界面 + 是否强制普通 + 是否可转换」算出四态 {@code mode}（与输出总线侧同一口径）。 */
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

    /** 本包描述的界面是否处于延长型输入模式。 */
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
    private static volatile String lastLinkedChamber = "";
    private static volatile boolean lastAutoCollect = true;
    private static volatile boolean lastForceNormalBus;
    private static volatile List<RsccBusCategory> lastCategories = List.of();
    private static volatile List<String> lastManualSelected = List.of();
    /** 客户端：本容器「可转换」（线缆真的够得到总线输出执行仓）—— 决定「普通 / 总线」按钮是否常驻。 */
    private static volatile boolean lastConvertible;

    /** 客户端：该容器当前是否处于延长型输入模式。 */
    public static boolean isExecutorMode(final int containerId) {
        return containerId >= 0 && containerId == lastContainerId && lastExecutorMode;
    }

    /** 客户端：该容器当前是否处于「全自动收回」模式（不属于该容器 / 未同步时按默认值 {@code true}）。 */
    public static boolean isAutoCollect(final int containerId) {
        return containerId != lastContainerId || lastAutoCollect;
    }

    /** 客户端：该容器归属的执行舱显示名（不属于该容器 / 未同步时返回空串）。 */
    public static String getLinkedChamber(final int containerId) {
        return containerId == lastContainerId ? lastLinkedChamber : "";
    }

    /** 客户端：该容器是否被玩家「强制为普通总线」（不属于该容器 / 未同步时按 false）。 */
    public static boolean isForceNormalBus(final int containerId) {
        return containerId == lastContainerId && lastForceNormalBus;
    }

    /**
     * 客户端：本容器是否<b>可转换</b>（线缆真的够得到处于「总线输出」的执行仓）。
     * <p>为真 ⇒ 「普通 / 总线」切换按钮<b>默认常驻</b>（用户第 2 条）；为假 ⇒ 界面完全等同 RS 原版。</p>
     */
    public static boolean isConvertible(final int containerId) {
        return containerId >= 0 && containerId == lastContainerId && lastConvertible;
    }

    /** 客户端：本机的类别快照（不属于该容器时返回空列表）。 */
    public static List<RsccBusCategory> getCategories(final int containerId) {
        return containerId == lastContainerId ? lastCategories : List.of();
    }

    /**
     * 客户端：本机<b>存下来的那份勾选表</b>（不属于该容器时返回空列表）。
     * <p>供「详细配置」子界面当编辑起点 —— 自动模式下 {@link #getCategories(int)} 里的
     * {@code selected} 是展示态，不等于玩家勾的那份（见类注释）。</p>
     */
    public static List<String> getManualSelection(final int containerId) {
        return containerId == lastContainerId ? lastManualSelected : List.of();
    }

    public static void handle(final SyncImporterExecutorModePacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            lastContainerId = packet.containerId();
            lastExecutorMode = packet.executorMode();
            lastLinkedChamber = packet.linkedChamber() == null ? "" : packet.linkedChamber();
            lastAutoCollect = packet.autoCollect();
            lastForceNormalBus = packet.forceNormalBus();
            lastConvertible = packet.mode != MODE_PLAIN;
            lastCategories = List.copyOf(packet.categories());
            lastManualSelected = List.copyOf(packet.manualSelected() == null
                ? List.of() : packet.manualSelected());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
