package cretae.cookiewyq.rs_create_compat.mixin.exporter;

import com.refinedmods.refinedstorage.api.network.impl.node.exporter.ExporterNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.exporter.AbstractExporterBlockEntity;
import com.refinedmods.refinedstorage.common.support.AbstractDirectionalBlock;
import com.refinedmods.refinedstorage.common.support.FilterWithFuzzyMode;
import com.refinedmods.refinedstorage.common.upgrade.UpgradeContainer;
import cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.MainNetworkNodeAccessor;
import cretae.cookiewyq.rs_create_compat.report.RsccBusDisabledBanner;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccBusConfig;
import cretae.cookiewyq.rs_create_compat.support.RsccBusInterference;
import cretae.cookiewyq.rs_create_compat.support.RsccChamberExportStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccSearchGuard;
import cretae.cookiewyq.rs_create_compat.support.RsccWireBlocks;
import cretae.cookiewyq.rs_create_compat.support.RsccWireLinkSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 输出总线（RS {@code Exporter}）的「延长型输出」模式。
 *
 * <p><b>连接判定（最终规则）</b>：从本输出总线出发，<b>只经过「可穿行集合」、不穿过任何机器 / 容器</b>，
 * 做逐层 BFS（<b>无步数上限</b>，改为「每 tick 预算 + 跨 tick 续扫」，见
 * {@link RsccWireLinkSearch} 里的 {@code BUS_LINK_TICK_BUDGET}）；途中（含起点六向）碰到<b>处于
 * 「总线输出」模式的序列执行仓</b>（{@link SequenceExecutionChamberBlockEntity#isBusOutput()}）即为绑定。
 * 可穿行集合见 {@link RsccWireBlocks}：RS 线缆 + 其它输出总线 / 输入总线（用户要求「输出总线 / 输入总线
 * 本身也是这条路径的一部分」），<b>不含</b>外部存储总线 / 构造器 / 破坏器 / 序列执行仓等机器。
 * <ul>
 *     <li><b>六向相邻</b> = 「0 步线缆」的特例，同样命中；</li>
 *     <li><b>归属必须唯一才成立（本轮改造）</b>：一趟展开同时数出线缆簇<b>一共</b>够得到几台执行舱 ——
 *     可达<b>恰好 1 台</b>且探查穷尽才认归属；可达 <b>≥2 台</b>（<b>不论距离是否相等</b>）或探查未能穷尽
 *     → <b>归属未确定</b>，本总线<b>停用延长型</b>（不再从执行舱取料，退回普通（非延长）输出总线，
 *     玩家自己的过滤器照常可用），界面显示红条 + 横幅提醒。判定见
 *     {@link RsccBusInterference#inspect}（与输入总线共用同一条规则）；</li>
 *     <li>结果<b>缓存</b> {@link #RSCC_LINK_CACHE_TICKS} tick（惰性校验：缓存命中的执行仓若已不再是
 *     总线输出模式则立刻重搜）；执行仓切换模式时会主动调 {@link #rscc$refreshExecutorMode()} 清缓存。</li>
 * </ul>
 *
 * <p><b>2026-09-13 性能优化：定时轮询 → 事件驱动 + 廉价预检 + 退避</b>。此前未绑定的输出总线每
 * 20 tick 也会跑一次全量 BFS（尽管旁边什么都没有）。现在：</p>
 * <ul>
 *     <li><b>廉价预检</b>（{@link #rscc$hasRelevantNeighbor}）：每次搜索前先只读<b>六个邻块的
 *     方块状态</b>（<b>不取方块实体</b>），一个都不属于「导线集合或执行舱方块」→ 直接跳过整个 BFS，
 *     并把检查间隔指数退避（{@link #RSCC_SEARCH_MIN_INTERVAL} → {@link #RSCC_SEARCH_MAX_INTERVAL}）；</li>
 *     <li><b>事件驱动失效</b>：邻块放置 / 破坏 / 被替换 → {@code support/RsccBusLinkInvalidation}
 *     监听 NeoForge 的 {@code BlockEvent.NeighborNotifyEvent}，调
 *     {@link #rscc$invalidateNeighborLink()} 作废缓存与退避（下一 tick 立刻重算）；执行舱切输出模式 →
 *     {@link #rscc$refreshExecutorMode()}；区块加载（{@code setLevel} → {@code initialize}）→
 *     {@link #rscc$refreshAfterInitialize}；玩家打开本总线界面 → 见 {@code ExporterContainerMenuMixin}；</li>
 *     <li><b>节流</b>：{@link #rscc$nextSearchAt} 保证同一 tick / 同一 {@link #RSCC_SEARCH_MIN_INTERVAL}
 *     tick 窗口内最多一次全量 BFS，邻居频繁抖动也不会抖动搜索；</li>
 *     <li><b>预检命中即复位</b>：只要邻块里还有导线（可能是通往执行舱的路径），间隔立刻回到最快的
 *     20 tick，因此「线缆中途被拆 / 远端接上执行舱」的发现延迟仍与旧实现一致（≤20 tick）。</li>
 * </ul>
 *
 * <p><b>类别模型（本轮改造）</b>：不再写死 2 类，而是由执行仓按「单元样板 + Create 配方数据」动态生成
 * 一个<b>有序类别列表</b>（见 {@link RsccBusCategory}）：每一种「输入性产物」各成一类、中间产物一类。
 * 本机保存的是<b>自己选中的类别 id 集合</b>（NBT 字符串列表），同一类别可被多台输出总线同时选中。</p>
 *
 * <p><b>共享均分（本轮新增）</b>：执行仓是共享语义的权威 —— 它把「类别 → 有序输出总线列表」整表重建
 * （坐标升序），并逐 tick 按 {@code gameTime % N} 轮换「本轮由谁导出该类别」。本机只负责在
 * {@link #rscc$refreshBusTurn()} 时向执行仓要「本轮属于自己的过滤项」并下发，因此多台输出总线会
 * <b>按物品 / 按批轮流</b>取走同一类别的产出（总量守恒、除不尽时余数按轮询顺序分配）。</p>
 *
 * <p>绑定后：原过滤器槽位被锁定（{@link #rscc$useExecutorFilters} 接管 {@code setFilters}），导出清单
 * 改为执行仓给出的类别过滤项；并强制走<b>模糊模式</b>（{@link #rscc$forceFuzzyMode}），
 * 使不带数据组件的过滤项也能匹配执行仓内部存储里带 {@code create:sequenced_assembly} 组件的过渡件。</p>
 *
 * <p><b>取货源（本轮修正）</b>：延长模式下 <b>不再从 RS 网络抽取</b>，改为把节点上的传输策略换成
 * {@link RsccChamberExportStrategy}（见 {@link #rscc$installChamberStrategy}）——
 * 原料先由执行舱从网络搬进它自己的内部存储，再由本总线<b>按勾选类别从执行舱内部存储取料</b>，
 * 照旧从本总线被朝向的那一面推给相邻机器。未绑定时保留 RS 原版策略（网络 → 目标），
 * 因此脱绑后输出总线立刻恢复成普通输出总线。</p>
 *
 * <p><b>2026-09-13 修复：区块加载时 StackOverflowError（A→B→A 无限递归）</b>。原实现在
 * {@code initialize}（方块实体初始化阶段）里做邻块搜索，且搜索用 {@code Level#getBlockEntity}
 * 探测相邻方块 —— 这会强制加载并初始化邻块；当两台输出总线串接时，A 初始化探测到 B、B 初始化又探测到 A，
 * 无限递归导致栈溢出。三条修复：</p>
 * <ol>
 *     <li><b>搜索阶段零副作用</b>：穿行判定与目标判定一律只读 {@code BlockState}（见
 *     {@link RsccWireBlocks#isWire(BlockState)}）；只有确认方块是「本模组的执行舱方块」
 *     （{@link SequenceExecutionChamberBlock}）时才取它的方块实体，且用
 *     {@code LevelChunk#getBlockEntity(pos, CHECK)}（<b>只取已存在的，不创建</b>）；</li>
 *     <li><b>线程级重入守卫</b>：{@link RsccSearchGuard} 在搜链入口（{@link RsccWireLinkSearch}）
 *     拦截，若本线程已在搜索中则直接返回空结果，并在 {@code finally} 复位；</li>
 *     <li><b>安装时机后移</b>：{@code initialize} 注入不再探测邻块，只标记「待懒安装 + 作废缓存」；
 *     真正的归属解析与策略安装推迟到<b>服务端首个 tick</b>（见 {@link #rscc$serverTick()} →
 *     {@link #rscc$ensureChamberStrategy()}）。</li>
 * </ol>
 * 因此原始递归环（initialize → search → getBlockEntity → 邻块 initialize）的三条边全部被切断。</p>
 */
@Mixin(AbstractExporterBlockEntity.class)
public abstract class AbstractExporterBlockEntityMixin implements RsccExporterExecutorMode {
    /** 持久化键：已选类别 id 列表（字符串列表）。 */
    private static final String RSCC_TAG_CATEGORIES = "rscc_export_categories";
    /** 持久化键：是否「显式选择过」（区分「全不选」与「从未选过 → 默认全选输入性产物」）。 */
    private static final String RSCC_TAG_EXPLICIT = "rscc_categories_explicit";
    /**
     * 持久化键：玩家是否把本条总线<b>强制为普通输出总线</b>（用户原话：「赶紧做我要的按钮」）。
     * <p>为真时 {@link #rscc$isExecutorMode()} 一律返回 false ⇒ 界面回到普通输出总线（过滤器可编辑、
     * 走 RS 原版「网络 → 目标」策略），不再被自动判定成「序列装配总线」。
     * 但 {@link #rscc$isLinkedLayout()}（红条 / 可达区域按钮的依据）不受它影响 —— 状态信息照旧显示。</p>
     */
    private static final String RSCC_TAG_FORCE_NORMAL = "rscc_force_normal_bus";
    /** 绑定结果缓存时长（tick）。 */
    private static final int RSCC_LINK_CACHE_TICKS = 20;
    /**
     * 全量 BFS 的最快间隔（tick）：既是最小节流（同 tick / 20 tick 内不重复搜索），
     * 也是「预检命中 / 失效事件后」恢复到的基准间隔。
     */
    private static final int RSCC_SEARCH_MIN_INTERVAL = 20;
    /** 退避上限（tick）：预检连续未命中时，检查间隔指数退避最多延长到该值。 */
    private static final int RSCC_SEARCH_MAX_INTERVAL = 200;

    /**
     * 过滤器（含模糊模式开关 + 过滤容器）；非延长模式下用它恢复玩家自己的过滤清单。
     * <p>该字段声明在目标类 {@link AbstractExporterBlockEntity} 自身，故 {@code @Shadow} 可正确定位。
     */
    @Shadow
    @Final
    private FilterWithFuzzyMode filter;

    /**
     * 插件容器（声明在目标类自身，故 {@code @Shadow} 可正确定位）：自定义取料策略需要它来按
     * 「堆叠升级 / 调节升级」算出每个过滤项的每批搬运量（与 RS 工厂同一口径）。
     */
    @Shadow
    @Final
    private UpgradeContainer upgradeContainer;

    /** 本机选中的类别 id（有序、去重；仅本机自己的请求，是否「权威生效」由执行仓归一后决定）。 */
    @Unique
    private final List<String> rscc$exportCategoryIds = new ArrayList<>();
    /** 玩家是否显式选过类别（false = 从未点过 → 默认全选输入性产物类别）。 */
    @Unique
    private boolean rscc$categorySelectionExplicit;

    /**
     * 玩家是否把本条总线<b>强制为普通输出总线</b>（落盘、读档保留；服务端权威）。
     * <p>只影响「界面归属判定」（{@link #rscc$isExecutorMode()}）与搬运策略（{@link #rscc$linkedExecutor()}
     * 在强制普通时恒为 {@code null}）；<b>不影响</b>归属原始判定 {@link #rscc$isLinkedLayout()}，
     * 因此「已停用红条 + 显示可达区域按钮」照旧显示（用户硬要求，见接口注释）。</p>
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

    /**
     * 最近一次归属判定的完整报告（含「可达台数 / 是否穷尽 / 线缆簇 / 可达执行舱坐标」）。
     * <p>与 {@link #rscc$linkedPosCache} 同一个缓存窗口，供界面同步链路直接取用：
     * 界面（容器菜单 Mixin）与搬运（策略）看到的永远是<b>同一份</b>判定结果，不存在两套搜索。
     */
    @Unique
    private RsccBusInterference.Report rscc$linkReport = RsccBusInterference.Report.CLEAR;

    /** 是否已记录「上一次的归属是否未确定」基线（首个解析只记基线、不弹横幅，见 {@link #rscc$announceLinkState()}）。 */
    @Unique
    private boolean rscc$linkBaselineRecorded;
    /** 上一次已就「归属未确定」弹过横幅的状态（用于「同一状态只弹一次、恢复后再翻转可再弹」的节流）。 */
    @Unique
    private boolean rscc$announcedAmbiguous;
    /**
     * 「曾经连到执行舱 → 现在<b>一台都够不到</b>」的边沿检测器（缺口 2）。
     * <p>判据是<b>本实例亲眼见过可达台数 ≥ 1</b> 这条历史事实，因此「从落地起就没接过执行舱」的
     * 普通总线永远不会提示；提示只在 {@code ≥1 → 0} 的那一次翻转上报一次（见
     * {@link RsccBusInterference.LinkLostLatch}）。</p>
     */
    @Unique
    private final RsccBusInterference.LinkLostLatch rscc$linkLostLatch = new RsccBusInterference.LinkLostLatch();
    /** 上一次「已据此下发导出清单」的归属坐标（{@code null} = 未归属 / 未确定）。 */
    @Unique
    @Nullable
    private BlockPos rscc$appliedOwnerPos;
    /** {@link #rscc$appliedOwnerPos} 是否已填（未填时首个 tick 也会下发一次，保证与 RS 原版对齐）。 */
    @Unique
    private boolean rscc$appliedOwnerSet;

    /**
     * 下一次允许做「全量 BFS」的 {@link Level#getGameTime()}。
     * <p>作用有二：① <b>同 tick / 窗口内不重复搜索</b>；② 未绑定时按 {@link #rscc$searchInterval}
     * 指数退避。任何失效事件（邻块变化 / 执行舱切模式 / 区块加载 / 打开界面）都会把它清零以立刻重算。</p>
     */
    @Unique
    private long rscc$nextSearchAt;

    /**
     * 当前搜索间隔（tick）：预检未命中时指数退避（20 → 40 → … → {@link #RSCC_SEARCH_MAX_INTERVAL}），
     * 预检命中或失效事件后回到 {@link #RSCC_SEARCH_MIN_INTERVAL}。
     * <p>之所以能安全退避：只要「六邻块里存在导线 / 执行舱」这个前提被破坏，就一定伴随邻块事件，
     * 会被 {@code RsccBusLinkInvalidation} 立刻清零间隔。</p>
     */
    @Unique
    private int rscc$searchInterval = RSCC_SEARCH_MIN_INTERVAL;

    /**
     * 延长模式取料策略是否已（懒）安装。
     * <p>{@code initialize} 阶段只把它复位（不探测邻块）；服务端首个 tick 解析到归属后安装并置真，
     * 装好后不再重复；RS 重新 {@code initialize}（旋转 / 升级 / 改模糊模式等）时会再次复位。</p>
     */
    @Unique
    private boolean rscc$chamberStrategyInstalled;

    // ==================== 桥接接口实现 ====================

    @Override
    public boolean rscc$isExecutorMode() {
        // 语义：线缆够得到<b>至少一台</b>「总线输出」执行舱 = 本机处在「延长型」布局里
        // （界面要显示类别条 + 归属未确定时显示「已停用」红条与「显示可达区域」按钮）。
        // 此时归属不一定唯一 —— 是否真的能搬运由 rscc$linkedExecutor() 决定
        // （归属未确定 → 退回普通总线）。判定见 RsccBusInterference#inspect。
        // <b>「强制普通总线」开关（本轮新增）在这里收口</b>：玩家关掉自动判定后一律回到普通界面。
        // <b>「过滤槽里有东西 → 优先按普通总线」（2026-10-10 用户第 9 条）也在同一处收口</b>：
        // 见 rscc$hasFilterEntries()。
        return rscc$isLinkedLayout() && !rscc$forceNormalBus && !rscc$hasFilterEntries();
    }

    @Override
    public boolean rscc$isLinkedLayout() {
        // 不含「强制普通」开关的原始判定 —— 「已停用红条 + 显示可达区域按钮」据此显示，
        // 因此强制普通后状态信息不会被吞掉（用户硬要求：改界面归属不得丢掉既有信息）。
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
        // 归属缓存里存的「命中的执行舱坐标」要按新开关重算（强制普通 ⇒ 恒为 null ⇒ 退回 RS 原版路径）
        rscc$invalidateLinkCache();
        rscc$chamberStrategyInstalled = false; // 策略要按新归属重装（强制普通 = 回到 RS 原版「网络→目标」）
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        if (executor != null) {
            executor.normalizeBusOwners(); // 强制普通后把本机的类别归属让出去（服务端权威归一）
        }
        rscc$ensureChamberStrategy();
        rscc$applyExportFilters();
        if (RsccAssemblyDebug.isEnabled()) {
            final BlockPos selfPos = ((BlockEntity) (Object) this).getBlockPos();
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("exporter", selfPos)
                + " forceNormal=" + force
                + " linked=" + rscc$debugLinkedTag()
                + " (界面归属判定已切换)");
        }
    }

    @Override
    public RsccBusInterference.Report rscc$linkReport() {
        rscc$resolveLink();
        return rscc$linkReport;
    }

    @Override
    public List<String> rscc$getExportCategoryIds() {
        return List.copyOf(rscc$exportCategoryIds);
    }

    @Override
    public boolean rscc$isCategorySelectionExplicit() {
        return rscc$categorySelectionExplicit;
    }

    @Override
    public void rscc$setExportCategoryIds(@Nullable final List<String> categoryIds) {
        // 界面上的每一次勾选都算「玩家显式选过」——因此这里固定 explicit = true，
        // 与剪贴板粘贴（要连「是否显式选过」一起还原）共用同一段写入逻辑。
        rscc$applyCategorySelection(categoryIds, true);
    }

    @Override
    public void rscc$applyCategorySelection(@Nullable final List<String> categoryIds, final boolean explicit) {
        final List<String> sanitized = rscc$sanitizeCategoryIds(categoryIds);
        if (rscc$categorySelectionExplicit == explicit && sanitized.equals(rscc$exportCategoryIds)) {
            return;
        }
        rscc$exportCategoryIds.clear();
        rscc$exportCategoryIds.addAll(sanitized);
        rscc$categorySelectionExplicit = explicit;
        ((BlockEntity) (Object) this).setChanged();
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("exporter", ((BlockEntity) (Object) this).getBlockPos())
                + " cats=" + rscc$exportCategoryIds
                + " explicit=" + rscc$categorySelectionExplicit
                + " linked=" + rscc$debugLinkedTag());
        }
        // 立刻按新选择重建导出清单；归属由执行仓整表归一（服务端权威）
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        if (executor != null) {
            executor.normalizeBusOwners();
        }
        rscc$applyExportFilters();
    }

    /** 剪贴板复制：把「玩家眼里这条总线的配置」写进一段 NBT（格式见 {@link RsccBusConfig}）。 */
    @Override
    public void rscc$writeBusConfig(final CompoundTag tag, final HolderLookup.Provider provider) {
        RsccBusConfig.writeHeader(tag, RsccBusConfig.KIND_EXPORTER);
        RsccBusConfig.writeFilter(tag, filter, provider);
        RsccBusConfig.writeCategories(tag, rscc$exportCategoryIds);
        tag.putBoolean(RsccBusConfig.KEY_EXPLICIT, rscc$categorySelectionExplicit);
        tag.putBoolean(RsccBusConfig.KEY_FORCE_NORMAL, rscc$forceNormalBus);
    }

    /**
     * 剪贴板粘贴：校验通过后才写入。
     * <p><b>顺序是刻意的</b>：先写过滤槽（它是「本条总线算不算延长型」的判据，见
     * {@link #rscc$hasFilterEntries()}），再写类别与开关 —— 这样「粘贴后总线处于哪种工作状态」
     * 与源总线逐一对应，不会出现中间态把类别写进一个马上就要退回普通的总线上。</p>
     */
    @Override
    public void rscc$readBusConfig(final CompoundTag tag, final HolderLookup.Provider provider) {
        if (!RsccBusConfig.acceptsKind(tag, RsccBusConfig.KIND_EXPORTER)) {
            return; // 版本不认识 / 种类不符：一个字节都不写
        }
        if (!RsccBusConfig.readFilter(tag, filter, provider)) {
            return;
        }
        rscc$applyCategorySelection(RsccBusConfig.readCategories(tag), tag.getBoolean(RsccBusConfig.KEY_EXPLICIT));
        rscc$setForceNormalBus(tag.getBoolean(RsccBusConfig.KEY_FORCE_NORMAL));
    }

    @Override
    public List<RsccBusCategory> rscc$getCategorySnapshot() {
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        if (executor == null) {
            return List.of();
        }
        final List<RsccBusCategory> snapshot = executor.busCategorySnapshot(
            ((BlockEntity) (Object) this).getBlockPos(),
            rscc$categorySelectionExplicit, rscc$exportCategoryIds);
        return rscc$withoutProducts(snapshot);
    }

    /**
     * 去掉「产出侧」类别（{@code result:} 成品 / {@code scrap:} 废料）—— <b>输出总线不得提供成品</b>。
     *
     * <h2>为什么（用户原话）</h2>
     * <p>「<b>输入总线</b>有的是需要把成品回流的，但你<b>输出总线</b>包含一个可以选择成品的，是什么鬼？」——
     * 输出总线是往机器里<b>推</b>料的，成品 / 废料本来就该由<b>输入总线</b>从机器里<b>收</b>回网络
     * （产出一侧），根本不存在「把成品推给机器」这回事。因此这里的裁剪是<b>服务端权威</b>的：
     * 客户端连这些类别都收不到，历史存档里的选择也在 {@link #rscc$sanitizeCategoryIds(List)} 被丢掉。</p>
     *
     * <p>反向对照：输入总线走 {@code rscc$busImportCategorySnapshot} 的入口，<b>保留</b>成品 / 废料，
     * 因此「输入总线收成品回流」的能力一字未动。</p>
     */
    @Unique
    private static List<RsccBusCategory> rscc$withoutProducts(final List<RsccBusCategory> snapshot) {
        final List<RsccBusCategory> filtered = new ArrayList<>(snapshot.size());
        for (final RsccBusCategory category : snapshot) {
            if (!category.isProduct()) {
                filtered.add(category);
            }
        }
        return filtered;
    }

    @Override
    public void rscc$refreshBusTurn() {
        rscc$ensureChamberStrategy();
        if (rscc$linkedExecutor() != null) {
            rscc$applyExportFilters();
        }
    }

    @Override
    public void rscc$refreshExecutorMode() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        rscc$invalidateLinkCache(); // 邻块 / 模式可能变了：下次重新搜索
        // 不再调用 initialize()（那是 RS 的重建流程、会重装 RS 原版策略）；这里改为作废安装标记，
        // 由懒安装路径按新归属重建 chamber 策略 + 重新下发导出清单（含脱绑时恢复玩家过滤器）。
        rscc$chamberStrategyInstalled = false;
        rscc$ensureChamberStrategy();
        rscc$applyExportFilters();
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
        rscc$resolveLink();          // 归属判定（20 tick 缓存 + 廉价预检 + 退避）
        rscc$applyFiltersOnChange(); // 归属翻转 → 立刻重下发导出清单（含退回玩家自己的过滤器）
        rscc$announceLinkState();    // 归属翻转为「未确定」→ 发一次既有横幅（同一状态只弹一次）
        rscc$ensureChamberStrategy();
    }

    @Override
    public void rscc$invalidateNeighborLink() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return; // 只在服务端重算（客户端状态一律以 S2C 同步为准）
        }
        // 事件回调里只作废：归属可能变了 → 标记策略待重装 + 作废缓存与退避，
        // 真正的搜索/安装交给下一 tick 的 rscc$serverTick()（此时世界已离开「正在修改」状态）。
        rscc$chamberStrategyInstalled = false;
        rscc$invalidateLinkCache();
        // 邻块变了 → 归属可能翻转：让「已下发归属」的记忆失效，下一 tick 必按新状态重下发一次导出清单
        // （唯一归属 → 执行舱给的类别；未确定 / 未归属 → 玩家自己的过滤器）。
        rscc$appliedOwnerSet = false;
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

    /**
     * 供料目标 = 本总线朝向那一面的相邻方块（机器 / 置物台）。
     * <p>实现方式与 {@code rscc$installChamberStrategy} 里算 target 的那一句完全同源
     * （{@link AbstractDirectionalBlock#tryExtractDirection} + {@code relative}），因此
     * 「策略实际往哪推」与「说明书告诉执行舱的供料目标」必然一致，不存在两套朝向判定。</p>
     */
    @Override
    @Nullable
    public BlockPos rscc$supplyTargetPos() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Direction direction = AbstractDirectionalBlock.tryExtractDirection(self.getBlockState());
        return direction == null ? null : self.getBlockPos().relative(direction);
    }

    @Override
    public boolean rscc$isAutoCrafting() {
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        return executor != null && executor.isAutoCraftingEnabled();
    }

    // ==================== 连接检测（线缆 BFS） ====================

    /**
     * 最终绑定的执行仓；没有则 {@code null}。客户端恒为 {@code null}（界面状态一律以 S2C 同步为准）。
     * <p><b>归属未确定时这里恒为 {@code null}</b> —— 这就是「停用延长型」的真实落点：</p>
     * <ul>
     *     <li>{@link RsccChamberExportStrategy#transfer} 拿到 {@code null} → 走它的脱绑分支，
     *     委托 RS 原版「网络 → 目标」策略（= 普通输出总线行为）；</li>
     *     <li>{@link #rscc$applyExportFilters()} 拿到 {@code null} → 把节点过滤项换回<b>玩家自己的过滤器</b>；</li>
     *     <li>{@link #rscc$useExecutorFilters}（接管 {@code setFilters}）看到 {@code null} → 不再拦截，
     *     玩家在界面上的过滤器编辑照常生效；</li>
     *     <li>{@link #rscc$forceFuzzyMode} 看到 {@code null} → 不再强制模糊模式。</li>
     * </ul>
     * 因此「停用」不是界面上的一行字，而是真的走回 RS 原生路径（不丢不复制）。
     */
    @Unique
    @Nullable
    private SequenceExecutionChamberBlockEntity rscc$linkedExecutor() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        if (rscc$hasFilterEntries()) {
            // 过滤槽里有东西 ⇒ 本条总线按<b>普通输出总线</b>处理（用户第 9 条）：不解析、不认归属。
            // 放在 rscc$resolveLink() 之前是刻意的：判定要最便宜，且不允许任何「先绑定再说」的中间态。
            return null;
        }
        rscc$resolveLink();
        return rscc$chamberAt(level, rscc$linkedPosCache);
    }

    /**
     * <b>「过滤槽里有东西」的唯一判据</b>（2026-10-10 用户第 9 条）。
     *
     * <h2>用户原话与规则</h2>
     * <p>「这个输入输出总线他跟这个序列执行力（执行仓）绑定一起之后呢，他原本的（过滤）槽就没有意义了。
     * 所以说如果说一个输入输出总线本身过滤槽中是有东西，那么就优先认为他是普通的（普通总线）。」</p>
     *
     * <h2>判据核实：是 RS 侧那份过滤容器，不是本模组的类别勾选</h2>
     * <p>本条总线界面上有两套「选择」，必须区分清楚（否则规则会跑偏）：</p>
     * <ol>
     *     <li><b>RS 过滤槽</b> —— 方块实体里的 {@code FilterWithFuzzyMode}（字段名 {@code filter}，
     *     声明在 {@link AbstractExporterBlockEntity} 自身，故 {@code @Shadow} 可正确定位）；
     *     它持有界面左侧那几格「物品 / 流体过滤器」（{@code ResourceContainer}），落盘在
     *     配置 NBT 的 {@code rf} 键、模糊开关在 {@code fm} 键
     *     （见 RS 的 {@code FilterWithFuzzyMode#save/load}）。<b>这一份才是玩家说的「过滤槽」</b>；</li>
     *     <li>本模组的<b>类别勾选</b>（{@code rscc$exportCategoryIds}）—— 它本身就是延长型的产物
     *     （「要从执行舱推出去的类别」），只在延长模式下有意义，因此<b>不能</b>当判据：
     *     用它就会变成「自己判自己」。</li>
     * </ol>
     * <p>{@code ResourceContainer#isEmpty()} 是 RS 自己的公开判定（逐格判 null），
     * 不产生任何分配、不触发形状查询、不取方块实体，因此可以每 tick 调。</p>
     *
     * <h2>为什么这条规则不会「打架」</h2>
     * <ul>
     *     <li><b>与「强制普通总线」开关</b>：两者互不干扰，任一为真都退回普通总线。
     *     玩家用开关把总线强制成普通后填了过滤槽，即使之后再关掉开关，过滤槽里的东西仍然让它保持普通
     *     （这正是用户要的「优先认为他是普通的」）；把过滤槽清空即自动恢复延长型，无需重放方块；</li>
     *     <li><b>与延长型的类别选择</b>：退回普通总线时 {@link #rscc$applyExportFilters()} 走的是
     *     {@code linkedExecutor() == null} 那一支 —— 把节点过滤项换回<b>玩家自己的过滤器</b>，
     *     因此「过滤槽真的生效」而不是只改界面；</li>
     *     <li><b>即时切换</b>：本判据每次现算（不缓存），而过滤槽每一次改动都会走 RS 自己的
     *     {@code FilterWithFuzzyMode} 监听 → {@code setFilters(...)}（本类在那里有注入），
     *     因此「有东西 ↔ 没东西」两个方向的翻转都在同一次改动的调用栈里生效。</li>
     * </ul>
     */
    @Unique
    private boolean rscc$hasFilterEntries() {
        return !filter.getFilterContainer().isEmpty();
    }

    /**
     * 归属判定（服务端，只读）：算出「可达台数 / 是否穷尽 / 是否未确定」，并据此维护
     * {@link #rscc$linkedPosCache}（只有<b>唯一确定</b>的归属才非 {@code null}）。
     *
     * <p>分三段，与改造前逐行同构（同一套 20 tick 缓存 + 廉价预检 + 指数退避，邻块变化即时作废）：</p>
     * <ol>
     *     <li><b>缓存命中</b>：唯一归属时只做一次「缓存坐标是否仍是总线输出模式执行仓」的廉价校验
     *     （命中即返回，保证「刚把执行仓切回面输出」能马上退出延长模式）；未归属 / 未确定时本窗口内保持不变；</li>
     *     <li><b>节流 / 退避窗口</b>：{@code gameTime < rscc$nextSearchAt} 时直接返回 ——
     *     同一 tick / 同一窗口内绝不重复跑全量搜链；</li>
     *     <li><b>廉价预检 + 全量搜链</b>：六邻块一个都不是「导线 / 执行舱」→ 直接判「未归属」并按退避时长推后；
     *     命中才真正跑 {@link RsccBusInterference#inspect}（内部就是 {@code RsccWireLinkSearch} 的唯一一趟展开）。</li>
     * </ol>
     */
    @Unique
    private void rscc$resolveLink() {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (RsccSearchGuard.isSearching()) {
            // 本判定是被<b>别人的搜索</b>间接触发的（重入）：既不嵌套搜索，也不要拿这次的空结果
            // 污染归属报告 —— inspect 在重入时按既有约定返回 CLEAR(可达 0 台)，照单全收就会把
            // 「刚好正在搜索」误当成「够不到执行舱」：既可能凭空点亮「归属未确定 / 延长型已断开」
            // 的边沿提示，也会让归属缓存空转一个窗口。什么都不改，等下一 tick 正常判定。
            return;
        }
        final long now = level.getGameTime();
        if (rscc$linkedCacheSet && now < rscc$linkedCacheExpireAt) {
            if (rscc$linkedPosCache == null) {
                return; // 未归属 / 归属未确定：本缓存窗口内保持（≤20 tick 后重探，邻块事件会立刻作废）
            }
            if (rscc$chamberAt(level, rscc$linkedPosCache) != null) {
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
        if (!rscc$hasRelevantNeighbor(level, origin)) {
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
     * 归属翻转时重下发导出清单（在本方块实体自己的 tick 里做，<b>不在搬运路径里做</b> ——
     * RS 正在遍历过滤项时替换列表会破坏它的一致性）。
     * <p>为什么必须做：延长型与普通型的「过滤项来源」不同（执行舱给的类别 vs 玩家自己设的过滤器）。
     * 归属一旦变化（连上 → 用类别；脱绑 / <b>归属未确定</b> → 退回玩家自己的过滤器），
     * 就必须把节点上的过滤项换成新来源，否则会出现「界面看着是普通总线，实际还在按旧类别搬运」。
     *
     * <h2>2026-10-10（用户第 9 条）：有效归属再收一道「过滤槽为空」</h2>
     * <p>{@code rscc$linkedExecutor()} 已经带了这条判据，因此这里的「有效归属」直接用它的结果：
     * 过滤槽里一有东西，有效归属立刻变成 {@code null}（= 普通总线），于是本方法会走「重建导出清单」那一支
     * （节点过滤项换回玩家自己的过滤器），与真正的脱绑逐字同一条路径。</p>
     * <p><b>并且</b>在「从延长型退回普通」的那一刻让执行仓归一一次类别归属表
     * （与 {@link #rscc$setForceNormalBus(boolean)} 调的是同一句 {@code normalizeBusOwners()}）。</p>
     *
     * <h2>已知边界（如实写下来）</h2>
     * <p>执行仓的归属表（{@code chainBusOwners}）是按「各总线<b>存下来的</b>类别勾选」建的，
     * 它<b>不</b>读本机的「是否延长型」判定 —— 因此过滤槽被填上之后，这条总线可能仍在表里占一个位置，
     * 直到下一次归一（本方法这一句、或玩家改勾选 / 拆放方块 / 改配方等既有触发点）。
     * 影响范围<b>仅限</b>「轮询里白占一个轮次」与界面上「共享台数」多算一台这类的统计偏差：
     * <b>搬运语义不受影响</b> —— 执行仓给这类总线下发的过滤项，最终都经
     * {@link #rscc$applyExportFilters()} 收口，而它读的是 {@code rscc$linkedExecutor()}（已含过滤槽判据），
     * 因此过滤槽非空时节点上挂的永远是<b>玩家自己的过滤器</b>。</p>
     */
    @Unique
    private void rscc$applyFiltersOnChange() {
        final BlockPos owner = rscc$linkedExecutor() == null ? null : rscc$linkedPosCache;
        if (rscc$appliedOwnerSet && java.util.Objects.equals(rscc$appliedOwnerPos, owner)) {
            return;
        }
        final BlockPos previous = rscc$appliedOwnerPos;
        rscc$appliedOwnerPos = owner;
        rscc$appliedOwnerSet = true;
        rscc$applyExportFilters();
        if (owner == null && previous != null) {
            // 从延长型退回普通（脱绑、归属未确定、或过滤槽刚被填上）：把类别归属让出去
            final Level level = ((BlockEntity) (Object) this).getLevel();
            final SequenceExecutionChamberBlockEntity chamber = rscc$chamberAt(level, previous);
            if (chamber != null) {
                chamber.normalizeBusOwners();
            }
        }
    }

    /**
     * 归属状态翻转时给附近玩家弹一次既有横幅。两个出口共用同一套节流规则（首个解析只记基线、不弹；
     * 之后只在状态真的翻转时弹；状态不变则一个包都不发）：
     * <ol>
     *     <li><b>「归属未确定」</b>（可达 ≥2 台或探查不穷尽 → 延长型停用）：语义与文案逐字未变；</li>
     *     <li><b>「延长型已断开」</b>（缺口 2：曾经连到过执行舱、现在一台都够不到）：原本完全静默，
     *     玩家只看到总线莫名退回普通界面、根本不知道接缝被断开；现在按边沿补一次明确提示
     *     （判定与去重见 {@link RsccBusInterference.LinkLostLatch}）。</li>
     * </ol>
     * <p>两者互斥（一个要求可达 0 台、一个要求 ≥2 台），因此不会同一次翻转弹两条。</p>
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
                    RsccBusDisabledBanner.send(serverLevel, self.getBlockPos(), true,
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
            RsccBusInterference.notifyLinkLost(serverLevel, self.getBlockPos(), true);
        }
    }

    /**
     * 廉价预检：只读六个邻块的 {@link BlockState}（<b>绝不取方块实体</b>），判断其中是否有导线
     * （{@link RsccWireBlocks}：RS 线缆 / 输出总线 / 输入总线）或本模组执行舱方块。
     * <p>BFS 的第一层展开恰好只访问这六格，因此「预检未命中」⇔「BFS 一步都走不出去」，
     * 判定完全等价；未命中时直接跳过整个 BFS，把「旁边什么都没有」的空转开销降到近乎零。
     * <p>实现已提取到 {@link RsccWireLinkSearch#hasRelevantNeighbor}（与执行舱侧共用同一份判定）。
     */
    @Unique
    private static boolean rscc$hasRelevantNeighbor(final Level level, final BlockPos origin) {
        return RsccWireLinkSearch.hasRelevantNeighbor(level, origin);
    }

    /** 缓存的坐标是否仍是「处于总线输出模式的执行仓」（实现见 {@link RsccWireLinkSearch#chamberAt}）。 */
    @Unique
    @Nullable
    private SequenceExecutionChamberBlockEntity rscc$chamberAt(final Level level,
                                                               final @Nullable BlockPos pos) {
        return RsccWireLinkSearch.chamberAt(level, pos);
    }

    /**
     * 作废绑定缓存并取消节流 / 退避（邻块变化、模式切换、区块加载等失效事件时调用）。
     * <p>把 {@link #rscc$nextSearchAt} 清零后，下一次调用即立刻重算，不会被困在退避窗口里。</p>
     */
    @Unique
    private void rscc$invalidateLinkCache() {
        rscc$linkedCacheSet = false;
        rscc$linkedPosCache = null;
        rscc$nextSearchAt = 0L;
        rscc$searchInterval = RSCC_SEARCH_MIN_INTERVAL;
    }

    /** 诊断用：当前绑定执行舱的标签（未绑定返回 {@code -}）。调用点必须已判 {@link RsccAssemblyDebug#isEnabled()}。 */
    @Unique
    private String rscc$debugLinkedTag() {
        final BlockPos linked = rscc$linkedExecutorPos();
        return linked == null ? "-" : RsccAssemblyDebug.machine("chamber", linked);
    }

    // ==================== 导出清单 ====================

    /**
     * 清洗客户端 / 存档来的类别 id：去 null / 空串、去重，并<b>丢掉「产出侧」类别</b>
     * （成品 {@code result:} / 废料 {@code scrap:}）。
     *
     * <p><b>为什么不设数量上限</b>：类别数完全由执行仓的单元样板 + Create 配方决定（可能远多于旧实现的
     * 64 项截断）；真正「能不能生效」由执行仓归一化时按可见类别过滤。</p>
     * <p><b>为什么在这里丢产出侧</b>：输出总线是往机器里推料的，成品 / 废料属于<b>输入总线</b>的收回范围
     * （用户原话：「输出总线包含一个可以选择成品的是什么鬼」）。历史存档里勾过的成品 / 废料在这里被丢掉，
     * 与 {@link #rscc$getCategorySnapshot()} 的裁剪同一口径 —— 界面看不到、存档也保不住。</p>
     */
    @Unique
    private static List<String> rscc$sanitizeCategoryIds(@Nullable final List<String> ids) {
        final LinkedHashSet<String> set = new LinkedHashSet<>();
        if (ids != null) {
            for (final String id : ids) {
                if (id == null || id.isEmpty() || RsccBusCategory.isProductId(id)) {
                    continue;
                }
                set.add(id);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 按当前模式下发导出清单：延长模式用执行仓给出的类别过滤项（含共享均分的轮次判定），
     * 否则恢复玩家自己的过滤器。
     */
    @Unique
    private void rscc$applyExportFilters() {
        // mainNetworkNode 声明在泛型祖父类上，无法用 @Shadow 定位；改用挂在「声明类」上的
        // @Accessor 读取（本类一定是 AbstractNetworkNodeContainerBlockEntity 的子类，强转安全）。
        final NetworkNode node = ((MainNetworkNodeAccessor) (Object) this).rscc$mainNetworkNode();
        if (!(node instanceof ExporterNetworkNode exporterNode)) {
            return;
        }
        final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();
        if (executor == null) {
            exporterNode.setFilters(filter.getFilterContainer().getResources());
            return;
        }
        exporterNode.setFilters(executor.busExportFilters(((BlockEntity) (Object) this).getBlockPos()));
    }

    // ==================== 注入点 ====================

    /** 过滤器变化（GUI 点击 / 读档 / 管道驱动）时接管：延长模式下槽位内容一律不生效。 */
    @Inject(method = "setFilters", at = @At("HEAD"), cancellable = true)
    private void rscc$useExecutorFilters(final List<ResourceKey> filters, final CallbackInfo ci) {
        if (rscc$linkedExecutor() == null) {
            return;
        }
        rscc$applyExportFilters();
        ci.cancel();
    }

    /**
     * 重建后（放置 / 旋转 / 升级 / 改模糊模式）刷新取料策略与导出清单，避免残留旧状态。
     * <p><b>2026-09-13 修复</b>：这里属于「方块实体正在初始化」的阶段，<b>绝不能探测邻块</b>
     * （会强制初始化邻块 → A→B→A 递归崩溃）。因此本注入只做两件只读自身状态的事：
     * ① 标记「策略待（懒）安装」；② 作废连接缓存。真正的邻块解析与安装推迟到服务端首个 tick
     * （{@link #rscc$serverTick()}）。</p>
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
        // RS 的 initialize 会把策略与过滤项都装回原版；让「已下发归属」的记忆失效，
        // 于是下一个 tick 会按当时的归属（唯一 / 未确定 / 未归属）重新下发一次导出清单。
        rscc$appliedOwnerSet = false;
    }

    /**
     * 懒安装延长模式取料策略（在安全的 tick / 模式切换时机调用）。
     * <p>只有<b>确实绑定到「总线输出」执行舱</b>时才替换 RS 原版策略；未绑定时什么都不做，
     * 让 {@code initialize} 装好的 RS 原版「网络 → 目标」策略继续生效（因此从不会让普通输出总线变哑）。
     * 装好后置位 {@link #rscc$chamberStrategyInstalled}，不再重复解析；RS 重新 {@code initialize}
     * 时会复位该标记以便按新状态重装。</p>
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
            // 诊断：未绑定（或刚从绑定掉下来）时打一条「状态翻转」日志，说明本总线已退回原版输出总线行为。
            // 原因分三种，便于区分「单纯没接执行舱」与「接多了 / 探查不穷尽导致归属未确定被停用」。
            if (RsccAssemblyDebug.isEnabled()) {
                final BlockPos selfPos = self.getBlockPos();
                final String reason = !rscc$linkReport.disabled() ? "no_chamber"
                    : (rscc$linkReport.truncated() && rscc$linkReport.reachableCount() == 1
                        ? "ownership_not_exhaustive" : "ownership_ambiguous");
                RsccAssemblyDebug.transition("exporterlink@" + RsccAssemblyDebug.at(selfPos), "unlinked",
                    RsccAssemblyDebug.machine("exporter", selfPos)
                        + " linked=- reason=" + reason
                        + " reachable=" + rscc$linkReport.reachableCount()
                        + " (退回 RS 原版「网络→目标」策略)");
            }
            return; // 未绑定 / 归属未确定：保留 RS 原版策略，下次 tick 再试（内部 20 tick 缓存，不会疯狂重搜）
        }
        rscc$installChamberStrategy(serverLevel, direction);
        rscc$chamberStrategyInstalled = true;
        rscc$applyExportFilters(); // 首次安装时立刻按执行舱下发导出清单
    }

    /**
     * 把节点的传输策略换成「<b>从执行舱内部存储取料</b>」的自定义策略（仅延长模式）。
     * <p>RS 自己在 {@code initialize} 里装的策略写死了「从网络抽取」，必须换掉 —— 这是本轮
     * 「流向语义修正」的关键一步（原料先由执行舱收进自己内部存储，再由本总线从执行舱推出）。</p>
     * <p><b>只读自身状态 + 构造策略，不探测邻块</b>：调用方 {@link #rscc$ensureChamberStrategy()}
     * 已经确认绑定存在。策略内部对执行舱的引用是逐次解析的，因此拆方块 / 切模式后会自然退化为
     * RS 原版「网络 → 目标」行为，不留哑巴总线、不残留幽灵引用。</p>
     */
    @Unique
    private void rscc$installChamberStrategy(final ServerLevel level, final Direction direction) {
        final NetworkNode node = ((MainNetworkNodeAccessor) (Object) this).rscc$mainNetworkNode();
        if (!(node instanceof final ExporterNetworkNode exporterNode)) {
            return;
        }
        // 目标机器 = 本总线朝向那一面相邻的方块实体（朝向语义与 RS 原版 createStrategy 完全一致）
        final BlockPos target = ((BlockEntity) (Object) this).getBlockPos().relative(direction);
        exporterNode.setTransferStrategy(new RsccChamberExportStrategy(
            this, level, ((BlockEntity) (Object) this).getBlockPos(), target, direction.getOpposite(),
            upgradeContainer));
        if (RsccAssemblyDebug.isEnabled()) {
            final BlockPos selfPos = ((BlockEntity) (Object) this).getBlockPos();
            final BlockPos linked = rscc$linkedExecutorPos();
            RsccAssemblyDebug.transition("exporterlink@" + RsccAssemblyDebug.at(selfPos),
                linked == null ? "unlinked" : RsccAssemblyDebug.at(linked),
                RsccAssemblyDebug.machine("exporter", selfPos)
                    + " linked=" + (linked == null ? "-" : RsccAssemblyDebug.machine("chamber", linked))
                    + " cats=" + rscc$exportCategoryIds
                    + " explicit=" + rscc$categorySelectionExplicit
                    + " target=" + RsccAssemblyDebug.at(target)
                    + " reason=installed");
        }
    }

    /** 延长模式下强制使用模糊模式的扩展器（否则带 NBT 的过渡件匹配不到）。 */
    @Inject(method = "isFuzzyMode", at = @At("HEAD"), cancellable = true)
    private void rscc$forceFuzzyMode(final CallbackInfoReturnable<Boolean> cir) {
        if (rscc$linkedExecutor() != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void rscc$saveCategories(final CompoundTag tag, final HolderLookup.Provider provider,
                                     final CallbackInfo ci) {
        final ListTag list = new ListTag();
        for (final String id : rscc$exportCategoryIds) {
            list.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put(RSCC_TAG_CATEGORIES, list);
        tag.putBoolean(RSCC_TAG_EXPLICIT, rscc$categorySelectionExplicit);
        tag.putBoolean(RSCC_TAG_FORCE_NORMAL, rscc$forceNormalBus);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void rscc$loadCategories(final CompoundTag tag, final HolderLookup.Provider provider,
                                     final CallbackInfo ci) {
        // 旧存档该键是 int（位掩码）→ getList 类型不符时返回空表，自然落到「默认全选输入性产物」
        final ListTag list = tag.getList(RSCC_TAG_CATEGORIES, Tag.TAG_STRING);
        final List<String> loaded = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            loaded.add(list.getString(i));
        }
        // 走与「客户端提交」同一条清洗：历史存档里勾过的成品 / 废料在这里被丢掉（输出总线不提供成品）
        rscc$exportCategoryIds.clear();
        rscc$exportCategoryIds.addAll(rscc$sanitizeCategoryIds(loaded));
        rscc$categorySelectionExplicit = tag.getBoolean(RSCC_TAG_EXPLICIT);
        rscc$forceNormalBus = tag.getBoolean(RSCC_TAG_FORCE_NORMAL); // 旧存档无该键 → false（行为与改动前一致）
        rscc$invalidateLinkCache();
    }
}
