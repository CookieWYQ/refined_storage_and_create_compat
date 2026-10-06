package cretae.cookiewyq.rs_create_compat.mixin.importer;

import com.refinedmods.refinedstorage.api.network.impl.node.importer.CompositeImporterTransferStrategy;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.node.importer.ImporterTransferStrategy;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.importer.ImporterTransferStrategyFactory;
import com.refinedmods.refinedstorage.common.importer.AbstractImporterBlockEntity;
import com.refinedmods.refinedstorage.common.support.AbstractDirectionalBlock;
import com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.MainNetworkNodeAccessor;
import cretae.cookiewyq.rs_create_compat.report.RsccBusDisabledBanner;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccBusInterference;
import cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccSearchGuard;
import cretae.cookiewyq.rs_create_compat.support.RsccWireLinkSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 输入总线（RS {@code Importer}）的「延长型输入（序列执行仓回流）」模式
 * —— {@code AbstractExporterBlockEntityMixin} 的对称实现。
 *
 * <p><b>连接判定</b>：与输出总线侧<b>同一份代码</b>（{@link RsccWireLinkSearch} 的唯一一趟展开）——
 * 从本机出发只经过「可穿行集合」（RS 线缆 / 输出总线 / 输入总线，见
 * {@code support/RsccWireBlocks}）、不穿过任何机器，上限 64 步；六向相邻是「0 步线缆」的特例；
 * 同距离多台执行仓按坐标字典序裁决；尊重扳手断开的接缝。命中的执行舱还必须处于
 * <b>「总线输出（延长型输出）」模式</b>（{@link SequenceExecutionChamberBlockEntity#isBusOutput()}）
 * —— 因为「收回」正是「延长型输出」的对称动作，只有那种布局才谈得上回流。</p>
 *
 * <p><b>归属必须唯一才成立（与输出总线侧同一条规则）</b>：可达<b>恰好 1 台</b>且探查穷尽才认归属；
 * 可达 <b>≥2 台</b>（不论距离是否相等）或探查未能穷尽 → <b>归属未确定</b>，本输入总线<b>停用延长型</b>
 * （{@link #rscc$linkedExecutor()} 返回 {@code null} → {@link RsccChamberImportStrategy} 委托 RS 原版
 * 「相邻容器 → 网络」策略），并显示红条 + 发一次横幅。判定见 {@link RsccBusInterference#inspect}。</p>
 *
 * <p><b>性能（沿用既有做法，不引入每 tick BFS）</b>：</p>
 * <ul>
 *     <li><b>结果缓存</b> {@value #RSCC_LINK_CACHE_TICKS} tick，且缓存命中时只做一次「缓存坐标是否仍是
 *     总线输出模式执行仓」的廉价校验（读方块状态 + {@code CHECK} 取方块实体）；</li>
 *     <li><b>廉价预检</b>：搜索前先只读六个邻块的方块状态，一个都不是「导线 / 执行舱」就直接跳过整个 BFS，
 *     并把检查间隔指数退避（{@value #RSCC_SEARCH_MIN_INTERVAL} → {@value #RSCC_SEARCH_MAX_INTERVAL} tick）；</li>
 *     <li><b>事件驱动失效</b>：邻块放置 / 破坏 / 被替换 → {@code support/RsccBusLinkInvalidation}
 *     调 {@link #rscc$invalidateNeighborLink()} 作废缓存与退避（下一 tick 立刻重算）；区块加载
 *     （{@code setLevel} → {@code initialize}）→ {@link #rscc$refreshAfterInitialize}；
 *     玩家打开本总线界面 → {@code ImporterContainerMenuMixin}。</li>
 * </ul>
 * <p><b>已知差异（与输出总线侧对比，刻意为之）</b>：执行舱切换输出模式时<b>只即时通知输出总线</b>
 * （{@code notifyAdjacentExporters}），不即时通知输入总线；输入总线靠上面那套「20 tick 缓存校验 +
 * 事件驱动失效」在 ≤20 tick（1 秒）内自行进入 / 退出延长模式。这样两边不共享一条通知路径，
 * 也就不必改动输出总线侧已经跑稳的代码。</p>
 *
 * <p><b>取料策略</b>：绑定后把节点的 transfer strategy 换成
 * {@link RsccChamberImportStrategy}（从执行仓内部存储按勾选类别收回网络）；未绑定时保留 RS 原版
 * 「相邻容器 → 网络」策略，因此未连线的输入总线与 RS 原生<b>逐字节一致</b>（脱绑后由策略内部的
 * 回退分支恢复原版行为，不会变成哑巴总线）。</p>
 *
 * <p><b>与输出总线侧一样：初始化阶段绝不探测邻块</b>。{@code initialize} 注入只作废缓存 + 标记
 * 「策略待（懒）安装」；真正的归属解析与安装推迟到服务端首个 tick（{@link #rscc$serverTick()} →
 * {@link #rscc$ensureChamberStrategy()}），从而彻底避开「A 初始化探测 B、B 又探测 A」的递归崩溃。</p>
 */
@Mixin(AbstractImporterBlockEntity.class)
public abstract class AbstractImporterBlockEntityMixin implements RsccImporterExecutorMode {
    /** 持久化键：已选（要收回的）类别 id 列表（字符串列表）。 */
    private static final String RSCC_TAG_CATEGORIES = "rscc_import_categories";
    /** 持久化键：是否「全自动收回」（缺省 = 缺 key 时按旧存档推导，见 {@link #rscc$loadCategories}）。 */
    private static final String RSCC_TAG_AUTO = "rscc_import_auto";
    /**
     * 持久化键：玩家是否把本条总线<b>强制为普通输入总线</b>（用户原话：「赶紧做我要的按钮」）。
     * <p>为真时 {@link #rscc$isExecutorMode()} 一律返回 false ⇒ 界面回到普通输入总线
     * （走 RS 原版「相邻容器 → 网络」策略）；{@link #rscc$isLinkedLayout()} 不受影响 ⇒
     * 「已停用红条 + 显示可达区域按钮」照旧显示。</p>
     */
    private static final String RSCC_TAG_FORCE_NORMAL = "rscc_force_normal_bus";
    /** 绑定结果缓存时长（tick）。 */
    private static final int RSCC_LINK_CACHE_TICKS = 20;
    /** 全量 BFS 的最快间隔（tick）：既是最小节流，也是「预检命中 / 失效事件后」恢复到的基准间隔。 */
    private static final int RSCC_SEARCH_MIN_INTERVAL = 20;
    /** 退避上限（tick）：预检连续未命中时，检查间隔指数退避最多延长到该值。 */
    private static final int RSCC_SEARCH_MAX_INTERVAL = 200;

    /**
     * 插件容器（声明在目标类 {@link AbstractImporterBlockEntity} 自身，故 {@code @Shadow} 可正确定位）：
     * 脱绑回退策略需要它，才能按「堆叠升级 / 调节升级」还原 RS 原版的每批搬运量。
     */
    @Shadow
    @Final
    private UpgradeContainer upgradeContainer;

    /** 本机选中的类别 id（有序、去重；空表 = 什么都不收回，这是默认值）。 */
    @Unique
    private final List<String> rscc$importCategoryIds = new ArrayList<>();

    /**
     * 是否处于「全自动收回」模式（默认 {@code true}，见 {@link RsccImporterExecutorMode#rscc$isAutoCollect()}）。
     * <p>全自动下类别选择完全不参与判定：服务端按「非输入类」自动决定收什么，界面只读展示。</p>
     */
    @Unique
    private boolean rscc$autoCollect = true;

    /**
     * 玩家是否把本条总线<b>强制为普通输入总线</b>（落盘、读档保留；服务端权威）。
     * <p>只影响界面归属判定与搬运策略（{@link #rscc$linkedExecutor()} 在强制普通时恒为 {@code null}）；
     * 不影响 {@link #rscc$isLinkedLayout()} ⇒ 红条 / 按钮照旧显示。</p>
     */
    @Unique
    private boolean rscc$forceNormalBus;

    /** 绑定结果缓存：命中的执行仓坐标（{@code null} = 上次搜索未命中，或归属未确定）。 */
    @Unique
    @Nullable
    private BlockPos rscc$linkedPosCache;
    /** 绑定结果缓存是否已填（未填时首次调用即搜一次）。 */
    @Unique
    private boolean rscc$linkedCacheSet;
    /** 绑定结果缓存到期时刻（{@link Level#getGameTime()}）。 */
    @Unique
    private long rscc$linkedCacheExpireAt;

    /** 最近一次归属判定的完整报告（与 {@link #rscc$linkedPosCache} 同一缓存窗口，供界面同步链路取用）。 */
    @Unique
    private RsccBusInterference.Report rscc$linkReport = RsccBusInterference.Report.CLEAR;
    /** 是否已记录「上一次的归属是否未确定」基线（首个解析只记基线、不弹横幅）。 */
    @Unique
    private boolean rscc$linkBaselineRecorded;
    /** 上一次已就「归属未确定」弹过横幅的状态（用于「同一状态只弹一次」的节流）。 */
    @Unique
    private boolean rscc$announcedAmbiguous;
    /**
     * 「曾经连到执行舱 → 现在<b>一台都够不到</b>」的边沿检测器（缺口 2；与输出总线侧共用同一份实现）。
     * <p>判据是<b>本实例亲眼见过可达台数 ≥ 1</b> 这条历史事实，因此「从落地起就没接过执行舱」的
     * 普通总线永远不会提示；提示只在 {@code ≥1 → 0} 的那一次翻转上报一次。</p>
     */
    @Unique
    private final RsccBusInterference.LinkLostLatch rscc$linkLostLatch = new RsccBusInterference.LinkLostLatch();

    /** 下一次允许做「全量 BFS」的 {@link Level#getGameTime()}（兼作节流与退避窗口）。 */
    @Unique
    private long rscc$nextSearchAt;

    /** 当前搜索间隔（tick）：预检未命中时指数退避，预检命中或失效事件后回到最快间隔。 */
    @Unique
    private int rscc$searchInterval = RSCC_SEARCH_MIN_INTERVAL;

    /**
     * 延长模式取料策略是否已（懒）安装。
     * <p>{@code initialize} 阶段只把它复位（不探测邻块）；服务端首个 tick 解析到归属后安装并置真，
     * 装好后不再重复；RS 重新 {@code initialize}（旋转 / 升级等）时会再次复位。</p>
     */
    @Unique
    private boolean rscc$chamberStrategyInstalled;

    // ==================== 桥接接口实现 ====================

    @Override
    public boolean rscc$isExecutorMode() {
        // 语义：与输出总线侧逐字同一条规则 —— 线缆够得到<b>至少一台</b>「总线输出」执行舱
        // 即处在「延长型」布局（归属未确定时由 rscc$linkedExecutor() 退回 RS 原版策略）。
        // <b>「强制普通总线」开关（本轮新增）在这里收口</b>：玩家关掉自动判定后一律回到普通界面。
        return rscc$isLinkedLayout() && !rscc$forceNormalBus;
    }

    @Override
    public boolean rscc$isLinkedLayout() {
        // 不含「强制普通」开关的原始判定 —— 「已停用红条 + 显示可达区域按钮」据此显示
        rscc$resolveLink();
        return rscc$linkReport.reachableCount() >= 1;
    }

    @Override
    public boolean rscc$isForceNormalBus() {
        return rscc$forceNormalBus;
    }

    @Override
    public void rscc$setForceNormalBus(final boolean force) {
        if (rscc$forceNormalBus == force) {
            return;
        }
        rscc$forceNormalBus = force;
        ((BlockEntity) (Object) this).setChanged(); // 落盘：开关本身也是玩家配置
        rscc$invalidateLinkCache();               // 归属缓存按新开关重算
        rscc$chamberStrategyInstalled = false;    // 策略按新归属重装（强制普通 = 回到 RS 原版「相邻容器→网络」）
        rscc$ensureChamberStrategy();
    }

    @Override
    public RsccBusInterference.Report rscc$linkReport() {
        rscc$resolveLink();
        return rscc$linkReport;
    }

    @Override
    public List<String> rscc$getImportCategoryIds() {
        return List.copyOf(rscc$importCategoryIds);
    }

    @Override
    public void rscc$setImportCategoryIds(@Nullable final List<String> categoryIds) {
        final List<String> sanitized = rscc$sanitizeCategoryIds(categoryIds);
        if (sanitized.equals(rscc$importCategoryIds)) {
            return;
        }
        rscc$importCategoryIds.clear();
        rscc$importCategoryIds.addAll(sanitized);
        ((BlockEntity) (Object) this).setChanged();
    }

    @Override
    public boolean rscc$isAutoCollect() {
        return rscc$autoCollect;
    }

    @Override
    public void rscc$setAutoCollect(final boolean auto) {
        if (rscc$autoCollect == auto) {
            return;
        }
        rscc$autoCollect = auto;
        ((BlockEntity) (Object) this).setChanged(); // 落盘：开关本身也是玩家配置
    }

    @Override
    public List<RsccBusCategory> rscc$getCategorySnapshot() {
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        if (executor == null) {
            return List.of();
        }
        // 全自动下由执行舱把「非输入类」标成已收回（界面只读展示），手动模式才用本机的勾选表
        return executor.busImportCategorySnapshot(rscc$importCategoryIds, rscc$autoCollect);
    }

    @Override
    public void rscc$refreshExecutorMode() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        rscc$invalidateLinkCache(); // 邻块 / 模式可能变了：下次重新搜索
        // 不调用 initialize()（那是 RS 的重建流程、会重装 RS 原版策略）；这里改为作废安装标记，
        // 由懒安装路径按新归属重建 chamber 策略（未绑定时保留 RS 原版策略）。
        rscc$chamberStrategyInstalled = false;
        rscc$ensureChamberStrategy();
    }

    @Override
    public void rscc$refreshLinkForUi() {
        final BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return; // 客户端 / 尚未进入世界：归属一律以 S2C 同步为准
        }
        // <b>只读</b>：作废 20 tick 归属缓存并立刻重算一次（结果与下一 tick 的常规重算逐字相同），
        // 刻意<b>不</b>重装节点策略、<b>不</b>改任何搬运状态 —— 打开界面绝不能影响任务推进。
        rscc$invalidateLinkCache();
        rscc$resolveLink();
    }

    @Override
    public void rscc$serverTick() {
        // 「安装策略」从 initialize 挪到安全的 tick 时机：首次 tick 解析归属并安装，装好后不再重复。
        rscc$resolveLink();       // 归属判定（20 tick 缓存 + 廉价预检 + 退避）
        rscc$announceLinkState(); // 归属翻转为「未确定」→ 发一次既有横幅（同一状态只弹一次）
        rscc$ensureChamberStrategy();
    }

    @Override
    public void rscc$invalidateNeighborLink() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return; // 只在服务端重算（客户端状态一律以 S2C 同步为准）
        }
        // 事件回调里只作废：真正的搜索 / 安装交给下一 tick 的 rscc$serverTick()（世界已离开「正在修改」状态）
        rscc$chamberStrategyInstalled = false;
        rscc$invalidateLinkCache();
    }

    @Override
    @Nullable
    public BlockPos rscc$linkedExecutorPos() {
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        return executor == null ? null : executor.getBlockPos();
    }

    @Override
    @Nullable
    public SequenceExecutionChamberBlockEntity rscc$getLinkedExecutor() {
        return rscc$linkedExecutor();
    }

    // ==================== 连接检测（线缆 BFS；实现全部委托给 support 层的唯一实现） ====================

    /**
     * 最终绑定的执行仓；没有则 {@code null}。客户端恒为 {@code null}（界面状态一律以 S2C 同步为准）。
     * <p><b>归属未确定时这里恒为 {@code null}</b> —— 这就是「停用延长型」的真实落点：
     * {@link RsccChamberImportStrategy#transfer} 拿到 {@code null} 即走它的脱绑分支，
     * 把搬运完全委托给 RS 原版「相邻容器 → 网络」策略（= 普通输入总线行为，逐字节同源、不丢不复制）。</p>
     * <p>判定分三段（缓存 / 节流退避 / 廉价预检 + 全量搜链），与输出总线侧逐行同构。</p>
     */
    @Unique
    @Nullable
    private SequenceExecutionChamberBlockEntity rscc$linkedExecutor() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        rscc$resolveLink();
        return RsccWireLinkSearch.chamberAt(level, rscc$linkedPosCache);
    }

    /**
     * 归属判定（服务端，只读）：算出「可达台数 / 是否穷尽 / 是否未确定」，并据此维护
     * {@link #rscc$linkedPosCache}（只有<b>唯一确定</b>的归属才非 {@code null}）。
     * <p>与输出总线侧同一套 20 tick 缓存 + 廉价预检 + 指数退避；邻块事件由
     * {@code support/RsccBusLinkInvalidation} 立刻作废，因此接线 / 拆线后 ≤1 秒翻转。</p>
     */
    @Unique
    private void rscc$resolveLink() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (RsccSearchGuard.isSearching()) {
            // 本判定是被<b>别人的搜索</b>间接触发的（重入）：不嵌套搜索，也不要拿这次的空结果污染
            // 归属报告 —— inspect 在重入时按既有约定返回 CLEAR(可达 0 台)，照单全收会把「刚好正在搜索」
            // 误当成「够不到执行舱」，凭空点亮「归属未确定 / 延长型已断开」的边沿提示。
            return;
        }
        final long now = level.getGameTime();
        if (rscc$linkedCacheSet && now < rscc$linkedCacheExpireAt) {
            if (rscc$linkedPosCache == null) {
                return; // 未归属 / 归属未确定：本缓存窗口内保持（≤20 tick 后重探，邻块事件会立刻作废）
            }
            if (RsccWireLinkSearch.chamberAt(level, rscc$linkedPosCache) != null) {
                return; // 唯一归属仍然有效
            }
            // 缓存里的执行仓已失效：作废节流并立刻重搜一次
            rscc$linkedCacheSet = false;
            rscc$nextSearchAt = 0L;
            rscc$searchInterval = RSCC_SEARCH_MIN_INTERVAL;
        }
        if (now < rscc$nextSearchAt) {
            return; // 节流 / 退避窗口内：不做全量搜链
        }
        final BlockPos origin = self.getBlockPos();
        if (!RsccWireLinkSearch.hasRelevantNeighbor(level, origin)) {
            // 廉价预检未命中：不可能连通 → 判「未归属」，并按退避时长把缓存与下次检查一并推后
            rscc$linkReport = RsccBusInterference.Report.CLEAR;
            rscc$linkedPosCache = null;
            rscc$linkedCacheSet = true;
            rscc$linkedCacheExpireAt = now + rscc$searchInterval;
            rscc$nextSearchAt = now + rscc$searchInterval;
            rscc$searchInterval = Math.min(rscc$searchInterval * 2, RSCC_SEARCH_MAX_INTERVAL);
            return;
        }
        // 预检命中：回到最快间隔，并按最小节流登记下次搜索时刻
        rscc$searchInterval = RSCC_SEARCH_MIN_INTERVAL;
        rscc$nextSearchAt = now + RSCC_SEARCH_MIN_INTERVAL;
        final RsccBusInterference.Report report = RsccBusInterference.inspect(level, origin);
        rscc$linkReport = report;
        // 只有「可达恰好 1 台且探查穷尽」才认归属；否则（含未确定）保持未归属 → 停用延长型
        rscc$linkedPosCache = rscc$forceNormalBus || report.disabled() || report.reachableCount() != 1
            ? null : report.chambers().get(0);
        rscc$linkedCacheSet = true;
        rscc$linkedCacheExpireAt = now + RSCC_LINK_CACHE_TICKS;
    }

    /**
     * 归属状态翻转时给附近玩家弹一次既有横幅（与输出总线侧共用同一个出口与同一套节流规则：
     * 首个解析只记基线、不弹；之后只在状态真的翻转时弹；状态不变一个包都不发）：
     * <ol>
     *     <li><b>「归属未确定」</b>（可达 ≥2 台或探查不穷尽 → 延长型停用）：语义与文案逐字未变；</li>
     *     <li><b>「延长型已断开」</b>（缺口 2：曾经连到过执行舱、现在一台都够不到）：原本完全静默，
     *     现在按边沿补一次明确提示（判定与去重见 {@link RsccBusInterference.LinkLostLatch}）。</li>
     * </ol>
     */
    @Unique
    private void rscc$announceLinkState() {
        final boolean ambiguous = rscc$linkReport.disabled();
        if (!rscc$linkBaselineRecorded) {
            rscc$linkBaselineRecorded = true;
            rscc$announcedAmbiguous = ambiguous; // 基线：载入 / 放置本身不算「翻转」
            rscc$announceLinkLost();
            return;
        }
        if (ambiguous != rscc$announcedAmbiguous) {
            rscc$announcedAmbiguous = ambiguous;
            if (ambiguous) {
                final BlockEntity self = (BlockEntity) (Object) this;
                final Level level = self.getLevel();
                if (level instanceof ServerLevel serverLevel) {
                    RsccBusDisabledBanner.send(serverLevel, self.getBlockPos(), false,
                        rscc$linkReport.reachableCount());
                }
            }
            // 恢复为唯一归属：不弹（玩家立刻能看到它重新工作），因此没有「已恢复」文案
        }
        rscc$announceLinkLost();
    }

    /** 「曾经连到执行舱 → 现在一台都够不到」这一条边沿的出口（去重全在 {@link RsccBusInterference.LinkLostLatch} 里）。 */
    @Unique
    private void rscc$announceLinkLost() {
        if (!rscc$linkLostLatch.onResolve(rscc$linkReport.reachableCount())) {
            return;
        }
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            RsccBusInterference.notifyLinkLost(serverLevel, self.getBlockPos(), false);
        }
    }

    /** 作废绑定缓存并取消节流 / 退避（邻块变化、模式切换、区块加载等失效事件时调用）。 */
    @Unique
    private void rscc$invalidateLinkCache() {
        rscc$linkedCacheSet = false;
        rscc$linkedPosCache = null;
        rscc$nextSearchAt = 0L;
        rscc$searchInterval = RSCC_SEARCH_MIN_INTERVAL;
    }

    // ==================== 取料策略（懒安装） ====================

    /**
     * 懒安装延长模式取料策略（在安全的 tick / 模式切换时机调用）。
     * <p>只有<b>确实绑定到「总线输出」执行舱</b>时才替换 RS 原版策略；未绑定时什么都不做，
     * 让 {@code initialize} 装好的 RS 原版「相邻容器 → 网络」策略继续生效
     * （因此从不会让普通输入总线变哑）。装好后置位 {@link #rscc$chamberStrategyInstalled}，
     * 不再重复解析；RS 重新 {@code initialize} 时会复位该标记以便按新状态重装。</p>
     */
    @Unique
    private void rscc$ensureChamberStrategy() {
        if (rscc$chamberStrategyInstalled) {
            return;
        }
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return; // 客户端 / 尚未进入世界：不做
        }
        final Direction direction = AbstractDirectionalBlock.tryExtractDirection(self.getBlockState());
        if (direction == null) {
            return;
        }
        if (rscc$linkedExecutor() == null) {
            return; // 未绑定：保留 RS 原版策略，下次 tick 再试（内部 20 tick 缓存，不会疯狂重搜）
        }
        rscc$installChamberStrategy(serverLevel, direction);
        rscc$chamberStrategyInstalled = true;
    }

    /**
     * 把节点的传输策略换成「<b>从执行舱内部存储按类别收回网络</b>」的自定义策略（仅延长模式）。
     * <p>RS 自己在 {@code initialize} 里装的策略写死了「从相邻容器抽取」，必须换掉。</p>
     * <p><b>回退策略按 RS 自己的工厂链构建</b>（与 {@code AbstractImporterBlockEntity#createStrategy}
     * 同一套参数：源坐标 = 朝向那一面、incoming 方向 = 朝向的反面、升级状态 = 本机插件容器），
     * 这样策略脱绑时能逐字节回到原版行为，不需要新加 accessor 去读节点里的私有字段。</p>
     */
    @Unique
    private void rscc$installChamberStrategy(final ServerLevel level, final Direction direction) {
        final NetworkNode node = ((MainNetworkNodeAccessor) (Object) this).rscc$mainNetworkNode();
        if (!(node instanceof final com.refinedmods.refinedstorage.api.network.impl.node.importer.ImporterNetworkNode
            importerNode)) {
            return;
        }
        final BlockPos selfPos = ((BlockEntity) (Object) this).getBlockPos();
        final List<ImporterTransferStrategyFactory> factories =
            RefinedStorageApi.INSTANCE.getImporterTransferStrategyRegistry().getAll();
        final ImporterTransferStrategy delegate = new CompositeImporterTransferStrategy(factories
            .stream()
            .map(factory -> factory.create(level, selfPos.relative(direction), direction.getOpposite(),
                upgradeContainer))
            .toList());
        importerNode.setTransferStrategy(new RsccChamberImportStrategy(this, selfPos, delegate));
    }

    // ==================== 注入点 ====================

    /**
     * 重建后（放置 / 旋转 / 升级）刷新取料策略与连接缓存，避免残留旧状态。
     * <p>这里属于「方块实体正在初始化」的阶段，<b>绝不能探测邻块</b>（会强制初始化邻块 →
     * A→B→A 递归崩溃）。因此只做两件只读自身状态的事：标记「策略待（懒）安装」+ 作废连接缓存；
     * 真正的邻块解析与安装推迟到服务端首个 tick（{@link #rscc$serverTick()}）。</p>
     */
    @Inject(
        method = "initialize(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/Direction;)V",
        at = @At("TAIL"))
    private void rscc$refreshAfterInitialize(final ServerLevel level, final Direction direction,
                                             final CallbackInfo ci) {
        if (RsccSearchGuard.isSearching()) {
            // 正在做邻块搜索（说明本 BE 是被搜索间接强制加载的）：不递归、不改状态。
            return;
        }
        rscc$chamberStrategyInstalled = false;
        rscc$invalidateLinkCache();
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void rscc$saveCategories(final CompoundTag tag, final HolderLookup.Provider provider,
                                     final CallbackInfo ci) {
        final ListTag list = new ListTag();
        for (final String id : rscc$importCategoryIds) {
            list.add(StringTag.valueOf(id));
        }
        tag.put(RSCC_TAG_CATEGORIES, list);
        tag.putBoolean(RSCC_TAG_AUTO, rscc$autoCollect);
        tag.putBoolean(RSCC_TAG_FORCE_NORMAL, rscc$forceNormalBus);
    }

    /**
     * 读档：勾选表 + 「全自动」开关。
     * <p><b>旧存档兼容（用户要求「不要报错」）</b>：本轮之前的存档里<b>没有</b> {@link #RSCC_TAG_AUTO}
     * 这个键。此时按「显式勾选过任何类别 → 手动模式；一个都没勾过 → 全自动」推导 ——
     * 于是旧存档里玩家辛苦勾好的类别不会被静默忽略（它继续按手动模式生效），
     * 而没勾过的总线自动升级成全自动（无需玩家再设置）。有键时一律以键为准（幂等）。</p>
     */
    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void rscc$loadCategories(final CompoundTag tag, final HolderLookup.Provider provider,
                                     final CallbackInfo ci) {
        rscc$importCategoryIds.clear();
        final ListTag list = tag.getList(RSCC_TAG_CATEGORIES, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            rscc$importCategoryIds.add(list.getString(i));
        }
        rscc$autoCollect = tag.contains(RSCC_TAG_AUTO)
            ? tag.getBoolean(RSCC_TAG_AUTO)
            : rscc$importCategoryIds.isEmpty();
        // 旧存档无该键 → false（行为与改动前一致）
        rscc$forceNormalBus = tag.getBoolean(RSCC_TAG_FORCE_NORMAL);
        rscc$invalidateLinkCache();
    }

    /** 清洗客户端 / 存档来的类别 id：去 null / 空串、去重（顺序保留，供界面稳定显示）。 */
    @Unique
    private static List<String> rscc$sanitizeCategoryIds(@Nullable final List<String> ids) {
        final LinkedHashSet<String> set = new LinkedHashSet<>();
        if (ids != null) {
            for (final String id : ids) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                set.add(id);
            }
        }
        return new ArrayList<>(set);
    }
}
