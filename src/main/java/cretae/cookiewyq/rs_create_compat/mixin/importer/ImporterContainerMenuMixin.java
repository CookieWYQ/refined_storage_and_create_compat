package cretae.cookiewyq.rs_create_compat.mixin.importer;

import com.refinedmods.refinedstorage.common.api.support.resource.ResourceContainer;
import com.refinedmods.refinedstorage.common.importer.AbstractImporterBlockEntity;
import com.refinedmods.refinedstorage.common.importer.ImporterContainerMenu;
import com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.SyncBusInterferencePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncImporterExecutorModePacket;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccBusInterference;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterMenuBridge;
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
import java.util.function.Predicate;

/**
 * 输入总线容器菜单的服务端桥接（界面同步链路）—— {@code ExporterContainerMenuMixin} 的对称版。
 * <p><b>为什么需要它</b>：客户端菜单实例不认识方块实体（RS 只把 {@code ResourceContainerData} 发给客户端），
 * 因此「本台输入总线是否处于延长型输入模式、当前有哪些类别 / 已勾选哪些」只能由服务端主动推送。
 * 这里在服务端构造函数末尾抓住方块实体，再由 {@link AbstractResourceContainerMenuMixin}
 * 在 RS 每次 {@code broadcastChanges}（与 RS 自己的数据槽同节奏）时比较状态并推送
 * {@link SyncImporterExecutorModePacket} —— 不新增任何轮询。</p>
 * <p>只推「变化时」（首次 / 切换模式 / 改选择 / 类别列表变化），不会产生包风暴。</p>
 * <p><b>为什么不直接注入 {@code broadcastChanges}</b>：{@code ImporterContainerMenu} <b>自身没有声明</b>
 * 该方法（它声明在祖父类 {@code AbstractResourceContainerMenu} 上），而本项目硬规则是
 * 「只能注入目标类自身声明的方法」（否则运行期抛 {@code InvalidInjectionException}）。
 * 因此改在声明处注入，再按本类实现的桥接接口回调（见 {@link #rscc$pushImporterSync()}）。</p>
 */
@Mixin(ImporterContainerMenu.class)
public abstract class ImporterContainerMenuMixin implements RsccImporterMenuBridge {
    /** 服务端侧的输入总线方块实体（客户端侧为 null）。 */
    @Unique
    @Nullable
    private AbstractImporterBlockEntity rscc$importer;

    /** 上次已同步给客户端的状态。 */
    @Unique
    private boolean rscc$lastMode;
    @Unique
    private String rscc$lastLinkedChamber = "";
    /** 上次已同步的「全自动收回」开关（默认 true，与输入总线的默认值一致）。 */
    @Unique
    private boolean rscc$lastAutoCollect = true;
    /** 上次已同步的「强制普通总线」开关（本轮新增；只在变化时发一次包）。 */
    @Unique
    private boolean rscc$lastForceNormal;
    /** 上次已同步的「可转换（原始布局判定）」——它决定「普通 / 总线」按钮是否常驻（用户第 2 条）。 */
    @Unique
    private boolean rscc$lastLinkedLayout;
    @Unique
    private List<RsccBusCategory> rscc$lastCategories = List.of();
    /** 上次已同步的「玩家存下来的勾选表」（「详细配置」子界面的编辑起点，见同步包注释）。 */
    @Unique
    private List<String> rscc$lastManualSelected = List.of();

    // ---------- 归属判定（原「被干扰」，批 3 任务 B）：服务端权威 + 变化才下发 ----------
    /**
     * 判定结果直接取方块实体侧的 {@link RsccImporterExecutorMode#rscc$linkReport()}（20 tick 缓存 + 邻块
     * 事件即时作废），因此这里<b>不再自己跑第二趟搜链</b>。
     */
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
            + "Lcom/refinedmods/refinedstorage/common/importer/AbstractImporterBlockEntity;"
            + "Lcom/refinedmods/refinedstorage/common/api/support/resource/ResourceContainer;"
            + "Lcom/refinedmods/refinedstorage/common/upgrade/UpgradeContainer;"
            + "Ljava/util/function/Predicate;)V",
        at = @At("TAIL"))
    private void rscc$captureImporter(final int syncId,
                                      final Player player,
                                      final AbstractImporterBlockEntity importer,
                                      final ResourceContainer resourceContainer,
                                      final UpgradeContainer upgradeContainer,
                                      final Predicate<Player> stillValid,
                                      final CallbackInfo ci) {
        this.rscc$importer = importer;
        // 玩家打开界面时立刻按当前邻块状态重算一次归属（否则可能显示退避窗口内的过期结果）。
        // 只在服务端构造路径执行；界面打开属极低频操作，这一次同步搜索可忽略。
        // <b>只读路径</b>：只作废 + 重算归属缓存，绝不重装节点策略 —— 打开界面不影响任务推进。
        if (importer instanceof RsccImporterExecutorMode mode) {
            mode.rscc$refreshLinkForUi();
        }
    }

    @Override
    @Nullable
    public AbstractImporterBlockEntity rscc$getImporter() {
        return rscc$importer;
    }

    /**
     * 状态变化时把「延时模式 + 类别快照」推给打开本菜单的玩家。
     * <p>由 {@link AbstractResourceContainerMenuMixin} 在 {@code broadcastChanges} 末尾按本接口回调；
     * 客户端菜单 {@code rscc$importer == null}，因此天然是空操作。</p>
     */
    @Override
    public void rscc$pushImporterSync() {
        if (rscc$importer == null) {
            return;
        }
        final RsccImporterExecutorMode executorMode = rscc$importer instanceof RsccImporterExecutorMode mode
            ? mode : null;
        final boolean mode = executorMode != null && executorMode.rscc$isExecutorMode();
        // 归属执行舱名字：界面据此显示「这条总线此刻认的是哪台执行舱」
        final SequenceExecutionChamberBlockEntity linked = executorMode == null
            ? null : executorMode.rscc$getLinkedExecutor();
        final String linkedChamber = linked == null ? "" : linked.getChamberDisplayName();
        final boolean autoCollect = executorMode != null && executorMode.rscc$isAutoCollect();
        // 「强制普通总线」开关（本轮新增）：同步给客户端，界面据此在普通界面上仍显示「恢复为总线界面」按钮
        final boolean forceNormal = executorMode != null && executorMode.rscc$isForceNormalBus();
        final List<RsccBusCategory> categories = mode ? executorMode.rscc$getCategorySnapshot() : List.of();
        // 「详细配置」子界面的编辑起点 = 玩家存下来的那份勾选表（自动模式下的展示态不能当起点，
        // 否则点一次确定就会把玩家的原勾选覆盖掉）
        final List<String> manualSelected = executorMode == null
            ? List.of() : executorMode.rscc$getImportCategoryIds();
        // 归属判定（原「被干扰」）：与类别快照同一节奏检查，但只在自己变化时发包。
        // <b>用「原始布局判定」而不是 mode</b>：强制普通后「已停用红条 + 显示可达区域按钮」照旧显示。
        rscc$pushInterference(executorMode != null && executorMode.rscc$isLinkedLayout());
        // 同一个「原始布局判定」也决定「普通 / 总线」切换按钮是否<b>常驻</b>（用户第 2 条）。
        final boolean linkedLayout = executorMode != null && executorMode.rscc$isLinkedLayout();
        if (mode == rscc$lastMode && linkedChamber.equals(rscc$lastLinkedChamber)
            && autoCollect == rscc$lastAutoCollect
            && forceNormal == rscc$lastForceNormal
            && linkedLayout == rscc$lastLinkedLayout
            && categories.equals(rscc$lastCategories)
            && manualSelected.equals(rscc$lastManualSelected)) {
            return;
        }
        final Player player = rscc$findPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        rscc$lastMode = mode;
        rscc$lastLinkedChamber = linkedChamber;
        rscc$lastAutoCollect = autoCollect;
        rscc$lastForceNormal = forceNormal;
        rscc$lastLinkedLayout = linkedLayout;
        rscc$lastCategories = List.copyOf(categories);
        rscc$lastManualSelected = List.copyOf(manualSelected);
        PacketDistributor.sendToPlayer(serverPlayer, new SyncImporterExecutorModePacket(
            ((AbstractContainerMenu) (Object) this).containerId,
            SyncImporterExecutorModePacket.modeOf(mode, forceNormal, linkedLayout), linkedChamber, autoCollect,
            rscc$lastCategories, rscc$lastManualSelected));
    }

    /** 按「containerMenu == this」反查打开本菜单的服务端玩家（无则 null）。 */
    @Unique
    @Nullable
    private Player rscc$findPlayer() {
        if (rscc$importer == null) {
            return null;
        }
        final Level level = rscc$importer.getLevel();
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

    // ==================== 归属判定（原「被干扰」；与输出总线侧逐字对称） ====================

    /**
     * 把「归属未确定（延长型已停用）」状态推给打开本界面的玩家（只在真正变化时发包；脱绑时下发一次收口）。
     * <p>判定结果<b>直接取方块实体侧那份缓存报告</b>（{@link RsccImporterExecutorMode#rscc$linkReport()}），
     * 因此既不会每 tick 跑搜链，也不会与搬运路径的判定产生分歧。</p>
     */
    @Unique
    private void rscc$pushInterference(final boolean executorModeActive) {
        if (rscc$importer == null) {
            return;
        }
        final RsccBusInterference.Report report = executorModeActive
            && rscc$importer instanceof RsccImporterExecutorMode mode
            ? mode.rscc$linkReport() : RsccBusInterference.Report.CLEAR;
        if (report.disabled() == rscc$lastDisabled
            && report.truncated() == rscc$lastTruncated
            && report.reachableCount() == rscc$lastReachable
            && report.chambers().equals(rscc$lastChambers)
            && report.cluster().equals(rscc$lastCluster)) {
            return; // 状态没变：不发包
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
