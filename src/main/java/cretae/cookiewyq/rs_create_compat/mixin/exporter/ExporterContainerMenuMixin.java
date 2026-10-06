package cretae.cookiewyq.rs_create_compat.mixin.exporter;

import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.exporter.AbstractExporterBlockEntity;
import com.refinedmods.refinedstorage.common.exporter.ExporterContainerMenu;
import com.refinedmods.refinedstorage.common.support.exportingindicator.ExportingIndicators;
import com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.SyncBusInterferencePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncExporterExecutorModePacket;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccBusInterference;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterMenuBridge;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyStrategy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 输出总线容器菜单的服务端桥接（界面同步链路）。
 * <p><b>为什么需要它</b>：客户端菜单实例不认识方块实体（RS 只把 {@code ExporterData} 发给客户端），
 * 因此「本台输出总线是否处于延长型输出模式、当前有哪些类别 / 已选哪些 / 各自几台共享」只能由服务端
 * 主动推送。这里在服务端构造函数末尾抓住方块实体，再在 {@code broadcastChanges}（RS 每 tick 调一次）
 * 末尾比较状态并推送 {@link SyncExporterExecutorModePacket} —— 与 RS 自己的数据槽同节奏，不新增轮询。</p>
 * <p>只推「变化时」（首次 / 切换模式 / 改选择 / 共享台数变化），不会产生包风暴。</p>
 */
@Mixin(ExporterContainerMenu.class)
public abstract class ExporterContainerMenuMixin implements RsccExporterMenuBridge {
    /** 服务端侧的输出总线方块实体（客户端侧为 null）。 */
    @Unique
    @Nullable
    private AbstractExporterBlockEntity rscc$exporter;

    /** 上次已同步给客户端的状态。 */
    @Unique
    private boolean rscc$lastMode;
    @Unique
    private boolean rscc$lastAutoCrafting;
    @Unique
    private String rscc$lastLinkedChamber = "";
    @Unique
    private boolean rscc$lastTargetStrategy;
    /** 上次已同步的「强制普通总线」开关（本轮新增；只在变化时发一次包）。 */
    @Unique
    private boolean rscc$lastForceNormal;
    /** 上次已同步的「可转换（原始布局判定）」——它决定「普通 / 总线」按钮是否常驻（用户第 2 条）。 */
    @Unique
    private boolean rscc$lastLinkedLayout;
    @Unique
    private List<RsccBusCategory> rscc$lastCategories = List.of();

    // ---------- 归属判定（原「被干扰」，批 3 任务 B）：服务端权威 + 变化才下发 ----------
    /**
     * 判定结果直接取方块实体侧的 {@link RsccExporterExecutorMode#rscc$linkReport()}（20 tick 缓存 + 邻块
     * 事件即时作废），因此这里<b>不再自己跑第二趟搜链</b>：界面看到的与搬运用的是同一份判定。
     */
    /** 上次已下发的状态（变化才发包）。 */
    @Unique
    private boolean rscc$lastDisabled;
    @Unique
    private boolean rscc$lastTruncated;
    @Unique
    private int rscc$lastReachable;
    @Unique
    private List<BlockPos> rscc$lastChambers = List.of();
    @Unique
    private List<BlockPos> rscc$lastCluster = List.of();

    /** 服务端构造函数（带方块实体）末尾抓住方块实体。 */
    @Inject(
        method = "<init>(ILnet/minecraft/world/entity/player/Player;"
            + "Lcom/refinedmods/refinedstorage/common/exporter/AbstractExporterBlockEntity;"
            + "Lcom/refinedmods/refinedstorage/common/api/support/resource/ResourceContainer;"
            + "Lcom/refinedmods/refinedstorage/common/upgrade/UpgradeContainer;"
            + "Lcom/refinedmods/refinedstorage/common/support/exportingindicator/ExportingIndicators;)V",
        at = @At("TAIL"))
    private void rscc$captureExporter(final int syncId,
                                      final Player player,
                                      final AbstractExporterBlockEntity exporter,
                                      final ResourceContainer resourceContainer,
                                      final UpgradeContainer upgradeContainer,
                                      final ExportingIndicators indicators,
                                      final CallbackInfo ci) {
        this.rscc$exporter = exporter;
        // 玩家打开界面时立刻按当前邻块状态重算一次归属（否则可能显示退避窗口内的过期结果）。
        // 只在服务端构造路径执行；界面打开属极低频操作，这一次同步搜索可忽略。
        // <b>只读路径</b>：只作废 + 重算归属缓存，绝不重装节点策略 —— 打开界面不影响任务推进。
        if (exporter instanceof RsccExporterExecutorMode mode) {
            mode.rscc$refreshLinkForUi();
        }
    }

    @Override
    @Nullable
    public AbstractExporterBlockEntity rscc$getExporter() {
        return rscc$exporter;
    }

    /** 状态变化时把「延长模式 + 类别快照（含共享台数）」推给打开本菜单的玩家。 */
    @Inject(method = "broadcastChanges", at = @At("TAIL"))
    private void rscc$pushExecutorMode(final CallbackInfo ci) {
        if (rscc$exporter == null) {
            return;
        }
        final RsccExporterExecutorMode executorMode = rscc$exporter instanceof RsccExporterExecutorMode mode
            ? mode : null;
        final boolean mode = executorMode != null && executorMode.rscc$isExecutorMode();
        // 「强制普通总线」开关（本轮新增）：同步给客户端，界面据此在普通界面上仍显示「恢复为总线界面」按钮
        final boolean forceNormal = executorMode != null && executorMode.rscc$isForceNormalBus();
        // 「自动合成」门控状态也一并同步：界面据此在锁定条内圈画暗红描边 + tooltip 提示
        final boolean autoCrafting = executorMode != null && executorMode.rscc$isAutoCrafting();
        // 归属执行舱名字：用户要求界面能看出「这条总线此刻认的是哪台执行舱」
        final SequenceExecutionChamberBlockEntity linked = executorMode == null
            ? null : executorMode.rscc$getLinkedExecutor();
        final String linkedChamber = linked == null ? "" : linked.getChamberDisplayName();
        // 供应策略（服务端权威）：为«目标产物达标»时界面显示预估需求 + 概率提示
        final boolean targetStrategy = RsccSupplyPolicy.strategy(rscc$exporter.getLevel())
            == RsccSupplyStrategy.TARGET;
        // 快照读取时会顺带跑一次归属归一（服务端权威），因此不能在比较「之后」才调用
        final List<RsccBusCategory> categories = mode ? executorMode.rscc$getCategorySnapshot() : List.of();
        // 归属判定（原「被干扰」）：与类别快照同一节奏检查，但只在自己变化时发包。
        // <b>用「原始布局判定」而不是 mode</b>：玩家把总线强制成普通界面后，
        // 「已停用红条 + 显示可达区域按钮」必须照旧显示（用户硬要求，不得因改界面归属而丢状态信息）。
        // 同一个「原始布局判定」也决定「普通 / 总线」切换按钮是否<b>常驻</b>（用户第 2 条）：
        // 只要线缆真的够得到总线输出执行仓，界面无论用哪套都画那颗按钮。
        rscc$pushInterference(executorMode != null && executorMode.rscc$isLinkedLayout());
        final boolean linkedLayout = executorMode != null && executorMode.rscc$isLinkedLayout();
        if (mode == rscc$lastMode && autoCrafting == rscc$lastAutoCrafting
            && linkedChamber.equals(rscc$lastLinkedChamber)
            && targetStrategy == rscc$lastTargetStrategy
            && forceNormal == rscc$lastForceNormal
            && linkedLayout == rscc$lastLinkedLayout
            && categories.equals(rscc$lastCategories)) {
            return;
        }
        final Player player = rscc$findPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        rscc$lastMode = mode;
        rscc$lastAutoCrafting = autoCrafting;
        rscc$lastLinkedChamber = linkedChamber;
        rscc$lastTargetStrategy = targetStrategy;
        rscc$lastForceNormal = forceNormal;
        rscc$lastLinkedLayout = linkedLayout;
        rscc$lastCategories = List.copyOf(categories);
        PacketDistributor.sendToPlayer(serverPlayer, new SyncExporterExecutorModePacket(
            ((AbstractContainerMenu) (Object) this).containerId,
            SyncExporterExecutorModePacket.modeOf(mode, forceNormal, linkedLayout), autoCrafting,
            linkedChamber, targetStrategy, rscc$lastCategories));
    }

    /** 按「containerMenu == this」反查打开本菜单的服务端玩家（无则 null）。 */
    @Unique
    @Nullable
    private Player rscc$findPlayer() {
        if (rscc$exporter == null) {
            return null;
        }
        final Level level = rscc$exporter.getLevel();
        if (level == null) {
            return null;
        }
        final AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        for (final Player player : level.players()) {
            if (player.containerMenu == self) {
                return player;
            }
        }
        return null;
    }

    // ==================== 归属判定（原「被干扰」） ====================

    /**
     * 把「归属未确定（延长型已停用）」状态推给打开本界面的玩家（只在真正变化时发包）。
     * <p>关键点：判定结果<b>直接取方块实体侧那份缓存报告</b>（{@link RsccExporterExecutorMode#rscc$linkReport()}），
     * 因此这里既不会每 tick 跑搜链，也不会与搬运路径的判定产生任何分歧。</p>
     * <p>未处于延长模式（{@code executorModeActive == false}，即线缆够不到任何执行舱）时一律按
     * {@link RsccBusInterference.Report#CLEAR} 下发一次收口，于是脱绑后客户端界面上的红条与叠加层立刻消失。</p>
     */
    @Unique
    private void rscc$pushInterference(final boolean executorModeActive) {
        if (rscc$exporter == null) {
            return;
        }
        final RsccBusInterference.Report report = executorModeActive
            && rscc$exporter instanceof RsccExporterExecutorMode mode
            ? mode.rscc$linkReport() : RsccBusInterference.Report.CLEAR;
        if (report.disabled() == rscc$lastDisabled
            && report.truncated() == rscc$lastTruncated
            && report.reachableCount() == rscc$lastReachable
            && report.chambers().equals(rscc$lastChambers)
            && report.cluster().equals(rscc$lastCluster)) {
            return; // 状态没变：不发包（避免每 tick 推送坐标表）
        }
        final Player player = rscc$findPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        rscc$lastDisabled = report.disabled();
        rscc$lastTruncated = report.truncated();
        rscc$lastReachable = report.reachableCount();
        rscc$lastChambers = List.copyOf(report.chambers());
        rscc$lastCluster = List.copyOf(report.cluster());
        PacketDistributor.sendToPlayer(serverPlayer, new SyncBusInterferencePacket(
            ((AbstractContainerMenu) (Object) this).containerId,
            rscc$lastDisabled, rscc$lastTruncated, rscc$lastReachable, rscc$lastCluster, rscc$lastChambers));
    }
}
