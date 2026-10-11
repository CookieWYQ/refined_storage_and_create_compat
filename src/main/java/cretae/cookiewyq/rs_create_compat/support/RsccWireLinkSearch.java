package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 「线缆连接搜索」的唯一实现（输出总线 ↔ 执行舱）。
 *
 * <p><b>为什么抽出来</b>：输出总线的延长模式需要一套与线缆网络图、连接臂外形完全一致的连通判定
 * （可穿行集合、最近距离优先、坐标字典序裁决、重入守卫、只读方块状态以避免递归初始化）。
 * 上一轮该实现写在 {@code AbstractExporterBlockEntityMixin} 的 {@code @Unique} 私有方法里，
 * 无法被其它类复用；本类把它提取为普通类（放 {@code support} 包，mixin 包内不允许放被外部引用的普通类），
 * 输出总线侧改为<b>委托调用</b>，从此只有一份判定代码。</p>
 *
 * <p><b>可穿行集合</b>（{@link RsccWireBlocks}）：RS 线缆 + 输出总线 + 输入总线；
 * <b>不穿过</b>任何机器 / 容器 / 外部存储总线 / 执行舱。</p>
 *
 * <p><b>与「扳手分离线缆」的接入点</b>：每一步 {@code cell → cell.relative(direction)} 在展开前都会问一次
 * {@link RsccCableCuts#isDisconnected}（与 RS 网络图连边、连接臂外形<b>同一判定</b>），
 * 被扳手断开的接缝 / 面在搜链里等同「这一侧没有线」，
 * 从而不会出现「看着断开了但仍然串线」。预检 {@link #hasRelevantNeighbor} 用同一句话过滤，
 * 保证「预检未命中 ⇔ BFS 一步都走不出去」这条不变式继续成立。</p>
 *
 * <p><b>与「分隔框架」的接入点</b>：同一处再问一次
 * {@link SeparationFrameGuard#blocksConnection}（同样是网络图连边 / 连接臂外形共用的那一句），
 * 于是「被套壳判定为不通的那一侧」在搜链里等同「这一侧没有线」，
 * 预检与 BFS 的口径与扳手那条完全并列、共用同一套不变式。
 * 注意套壳语义是<b>冻结套上那一刻的连接</b>：套上前就连着的接缝这里照旧能走过去，
 * 断了的是「套上之后新放上来的邻居」。</p>
 *
 * <p><b>搜索阶段绝不触发方块实体加载</b>：穿行 / 目标判定一律只读 {@link BlockState}；
 * 只有确认方块是「本模组的执行舱方块」时才用
 * {@code LevelChunk#getBlockEntity(pos, CHECK)}（<b>已存在才返回，绝不创建</b>）取它的方块实体。</p>
 *
 * <p><b>重入守卫</b>：所有搜索入口都被 {@link RsccSearchGuard} 保护，搜索途中再次进入搜索直接返回
 * 空结果，守卫在 {@code finally} 复位（异常也不会泄漏状态）。</p>
 *
 * <p><b>「归属未确定」那一轮</b>：一次展开除了给出「最近的那台」以外，还顺带给出
 * <b>整条线缆簇一共可达几个「总线输出」执行舱</b>以及<b>是否穷尽</b>（见 {@link LinkWalk}）。
 * 之所以能顺带给出：判定「最近一台」本来就要按层推进，而「可达数」只是把同一次推进里
 * <b>碰到过的所有</b>执行舱都记下来 —— 两道闸门（扳手断开 / 分隔框架）、可穿行集合都与旧实现
 * 逐字同源，因此不存在「归属判定一套、可达数判定另一套」。</p>
 *
 * <p><b>本轮改造（可续扫洪泛：截断不再等于停用）</b>。旧实现把「步数 / 格子数上限」当成
 * <b>到点就静默结束</b>（旧常量只作为历史说明保留在注释里：{@code LINK_MAX_STEPS = 64} /
 * {@code LINK_MAX_CELLS = 128}）：一条长线缆上挂很多总线时，<b>总线自己这一侧的搜链被截断</b> ——
 * 远处的执行舱既没被算进可达集合（总线看着像「没接执行舱」= 退回普通总线），
 * 又因为 {@code exhaustive=false} 被 {@link LinkWalk#ambiguous()} 判成「归属未确定」而<b>停用</b>
 * （红条 + 横幅）。截断点还取决于哪些区块已加载 ⇒ 玩家走动会让结果变化（「有时恢复」），
 * 布局固定时每次截断在同一处（「永远不恢复」）。现在换成三条不变量：</p>
 * <ol>
 *     <li><b>每 tick 预算 + 跨 tick 续扫</b>（{@link #BUS_LINK_TICK_BUDGET}）：一次判定最多推进
 *     {@value #BUS_LINK_TICK_BUDGET} 格，前沿留在队列里，下一次判定接着扫 —— 队列就是进度，
 *     <b>本 tick 扫不完不算放弃</b>；</li>
 *     <li><b>只有整趟扫完才发布</b>：续扫期间对外一律沿用<b>上一次完整结果</b>，绝不把半份集合
 *     当完整结果 —— 因此「暂时没扫完」永远不会让任何一台总线变普通 / 变红；</li>
 *     <li><b>首次（还没有任何完整结果）时一次扫完</b>（有界 {@link #BUS_LINK_HARD_CAP}）：
 *     刚读档 / 刚接线那一刻也不会出现「前几秒总线像没接执行舱」的窗口。</li>
 * </ol>
 * <p>由此得到的两条边界（如实写下）：<b>①</b> 一趟续扫可能跨过世界变化（拆线 / 区块加载），
 * 那一趟发布的结果是两次世界状态的并集；因此发布前会把每个候选执行舱<b>现验一遍</b>
 * （{@link #chamberAt}，拆掉的 / 已切成面输出的不计入可达），下一趟也会紧接着重扫 ——
 * 也就是说最坏情况下多等「一次判定 + 一趟续扫」，而旧实现在长线缆上是<b>永远</b>不对。
 * <b>②</b> 单次判定的成本上界就是 {@value #BUS_LINK_TICK_BUDGET} 格（首次那一次 ≤
 * {@value #BUS_LINK_HARD_CAP}），<b>不存在</b>任何「这次调用一次扫完无上限」的通道。</p>
 * <p>与执行舱侧（{@code SequenceExecutionChamberBlockEntity} 的 {@code BusLinkFlood}）<b>同口径</b>：
 * 同样的每 tick 预算 256、同样的硬上限 4096、同样的「只有扫完才发布」。<b>为什么没有共享同一个实现</b>：
 * 舱侧那两个常量与 {@code BusLinkFlood} 都是那个文件里的 {@code private} 成员，而本轮那个文件正被另一侧
 * 收口（不在本类的写入范围内），抽成中立类必须改它。因此这里实现同构的一份，常量<b>同名同值</b>，
 * 两边必须成对改动（口径漂移会让「总线侧」与「舱侧」对同一条线缆给出不同结论）。</p>
 *
 * <p><b>为什么状态放在本类（静态表）而不是总线方块实体里</b>：总线方块实体的字段在两个 Mixin 里，
 * 本轮不在写入范围内；而本类已经是搜链的唯一实现，它的记忆放在它自己这里最不容易出现「两份状态」。
 * 表按世界分（{@link WeakHashMap}），键是总线坐标，久未访问的记录会被淘汰（见 {@link #prune}），
 * 淘汰只会让下一次判定重扫一遍，不影响语义。状态只在服务端主线程产生与消费
 * （{@link RsccBusInterference#inspect} 与整簇粘贴都会先挡掉客户端）。</p>
 */
public final class RsccWireLinkSearch {
    /**
     * 续扫洪泛<b>每 tick 的格数预算</b>（与舱侧 {@code BUS_LINK_TICK_BUDGET} 同名同值）。
     * <p>旧实现是「单次最多 64 步 / 128 格，到点静默结束」—— 总线一多、线缆一长就用不完整的结果
     * 冒充完整结果。现在到预算就<b>停下留到下一次判定继续</b>，因此单刻成本有界，而结果终究完整。</p>
     */
    private static final int BUS_LINK_TICK_BUDGET = 256;
    /**
     * 单趟洪泛的<b>硬上限</b>（格）。触顶也<b>不是静默截断</b>：仍然发布，但
     * {@link LinkWalk#exhaustive()} 标为 {@code false} 并打一条可核对的锚点日志。
     * 取值远大于任何现实布局（{@value #BUS_LINK_HARD_CAP} 格线缆 ≈ 上千台总线），正常现场走不到这里。
     * <p>触顶<b>不会</b>因此把总线停用（见 {@link LinkWalk#ambiguous()}）：真歧义只由
     * 「够到 ≥2 条不同的链」判定。</p>
     */
    private static final int BUS_LINK_HARD_CAP = 4096;
    /** 定期复扫周期（tick）：新放的线缆 / 新放的执行舱最多这么久之后被这条总线发现。 */
    private static final int BUS_LINK_RESCAN_TICKS = 20;
    /**
     * 状态表软上限：超过就按「最久没被访问」淘汰到一半。
     * <p>记录随总线方块增长，而方块被拆掉时没有任何回调会通知本类，因此必须有一个按时间的淘汰口，
     * 否则长时间运行的服务器上这张表只增不减。淘汰后下一次判定会重扫一遍（正确性不受影响）。</p>
     */
    private static final int BUS_LINK_STATE_SOFT_LIMIT = 1024;
    /** 多久没被访问的完整结果可以被淘汰（tick）：没人在访问它，丢掉只会多扫一次。 */
    private static final int BUS_LINK_IDLE_DROP_TICKS = 400;
    /** 每多少次调用做一次淘汰（摊薄成本：绝大多数调用一次比较都不做）。 */
    private static final int BUS_LINK_PRUNE_EVERY_CALLS = 256;

    /** 按世界分表的可续扫状态（键 = 总线坐标的 {@code asLong()}）。见类注释的说明。 */
    private static final Map<Level, Map<Long, WireFlood>> FLOODS = new WeakHashMap<>();
    /** 调用计数：只用于把淘汰摊薄到每 {@link #BUS_LINK_PRUNE_EVERY_CALLS} 次调用一次。 */
    private static int floodCalls;

    private RsccWireLinkSearch() {
    }

    /**
     * 一次「线缆簇展开」的结果。
     *
     * @param owner      最近的那台「总线输出」执行舱（无则 {@code null}）；
     *                   距离最近优先、同距离按 {@link #lexicographic} 裁决 —— 与改造前的语义完全一致。
     *                   结果取自上一次<b>完整</b>扫描，因此它描述的是「最近一次扫完时的世界」；
     *                   返回时用 {@link #chamberAt} 现解析（那台已不是总线输出模式 → {@code null}，
     *                   调用方自己的廉价校验会据此触发重搜）
     * @param reachable  整条线缆簇够得到的执行舱，<b>已按链去重</b>：每一条链只留<b>它自己最近的</b>那台成员
     *                   （规则与理由见 {@link #collapseByChain}），因此本表长度 = 可达的
     *                   <b>逻辑执行仓（链）</b>数，而不是方块台数；
     *                   排序仍是「先距离近、再坐标字典序」，因此第 0 个就是 {@code owner} 的坐标
     * @param cluster    线缆簇本体（含起点总线；按 BFS 顺序，供半透明叠加层标出「延长段」）
     * @param exhaustive 本次发布的扫描是否<b>扫完</b>（前沿自然排空）。{@code false} 只有一个来源：
     *                   撞到 {@link #BUS_LINK_HARD_CAP} 这个「异常巨大线网」的安全阀（必然伴随锚点日志）。
     *                   <b>续扫期间不会对外出现 {@code false}</b>：没扫完时对外沿用上一次完整结果
     */
    public record LinkWalk(@Nullable SequenceExecutionChamberBlockEntity owner,
                           List<BlockPos> reachable, List<BlockPos> cluster, boolean exhaustive) {
        /** 空结果（重入守卫拦截 / 什么都够不到时复用）。 */
        public static final LinkWalk EMPTY = new LinkWalk(null, List.of(), List.of(), true);

        /** 线缆是否够得到至少一台执行舱（= 这条总线处在「延长型」布局里）。 */
        public boolean linked() {
            return !reachable.isEmpty();
        }

        /** 线缆可达的<b>链（= 逻辑执行仓）数</b>：同一条链上够到多台也只算一个。 */
        public int reachableCount() {
            return reachable.size();
        }

        /**
         * 归属是否<b>未确定</b>（= 该延长型必须停用、退回普通总线 + 红条 + 横幅）：
         * <b>可达 ≥2 条不同的链</b>（不论距离是否相等）—— 这是唯一的判据。
         *
         * <h2>为什么去掉了旧判据里的「恰好 1 条链但探查未能穷尽」</h2>
         * <p>2026-10-11（round60）：旧判据是
         * {@code return reachable.size() >= 2 || (reachable.size() == 1 && !exhaustive);}
         * —— 于是一趟<b>被截断</b>的搜索（旧实现到点静默结束）只要够到过一条链，就会把总线判成
         * 「归属未确定」并停用。线缆一长 / 总线一多，截断几乎必然发生，玩家看到的就是
         * 「总线变红条 / 变普通 / 整个停用」，且截断点取决于区块加载顺序 ⇒ 有时恢复、有时永久不恢复。
         * 根因是把「暂时没扫完」与「确实够到多条链（真歧义）」混为一谈。</p>
         * <p>本轮把两者分开：<b>暂时没扫完</b>已不再是对外可见状态（续扫期间沿用上一次完整结果、
         * 首次一次扫完，见 {@link #searchChamberLink}），因此 {@code exhaustive=false} 只剩
         * 「撞上异常巨大线网的安全阀」这一种来源；而安全阀只影响「可达集合可能不全」这条提示，
         * <b>不足以单独构成停用理由</b>（触顶之前真够到的那条链仍然是真够到的）。
         * 于是停用与否只由「够到几条链」决定 —— <b>真歧义（≥2 条链）的行为一字未变</b>。</p>
         * <p><b>同一条链上够到多台不算未确定</b>：那是「4 台 = 一台」的扩容，本来就是一个逻辑执行仓
         * （链成员由 {@code SequenceExecutionChamberBlockEntity} 的链推导定义，本表已按链去重）。</p>
         * <p>「一台都够不到」不算未确定 —— 那时本来就没有归属可言，总线只是普通总线
         * （否则任何接了大线网却还没接执行舱的总线都会亮红条）。</p>
         */
        public boolean ambiguous() {
            return reachable.size() >= 2;
        }

        /**
         * 归属是否<b>唯一确定</b>（可达恰好 1 条链）= 延长型可以照常工作。
         * <p>与调用方的实际闸门同口径（两个方块实体 Mixin 用的是
         * {@code report.disabled() || report.reachableCount() != 1}）：因此这里<b>不再</b>要求
         * {@code exhaustive} —— 真截断与真歧义已由 {@link #ambiguous()} 收口。</p>
         */
        public boolean unique() {
            return reachable.size() == 1;
        }
    }

    /**
     * 廉价预检：只读六个邻块的 {@link BlockState}（<b>绝不取方块实体</b>），判断其中是否有
     * 「可穿行集合成员」（RS 线缆 / 输出总线 / 输入总线）或「本模组执行舱方块」。
     * <p>BFS 的第一层展开恰好只访问这六格，因此「预检未命中」⇔「BFS 一步都走不出去」，
     * 未命中时调用方可直接跳过整个 BFS。</p>
     */
    public static boolean hasRelevantNeighbor(final Level level, final BlockPos origin) {
        for (final Direction direction : Direction.values()) {
            final BlockPos neighbor = origin.relative(direction);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            // 被扳手断开的接缝 / 面：这一侧算不通（与网络图、连接臂外形同一判定）
            if (RsccCableCuts.isDisconnected(level, origin, direction)) {
                continue;
            }
            // 被分隔框架套住且这一侧不在其快照里：这一步不通（同一判定，见 SeparationFrameGuard）
            if (SeparationFrameGuard.blocksConnection(level, origin, direction)) {
                continue;
            }
            final BlockState state = level.getBlockState(neighbor);
            if (state.getBlock() instanceof SequenceExecutionChamberBlock || RsccWireBlocks.isWire(state)) {
                return true;
            }
        }
        return false;
    }

    /** 该坐标是否仍是「处于总线输出模式的执行仓」（不是则 {@code null}）。 */
    @Nullable
    public static SequenceExecutionChamberBlockEntity chamberAt(final Level level, @Nullable final BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) {
            return null;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof SequenceExecutionChamberBlock)) {
            return null;
        }
        return level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
            instanceof SequenceExecutionChamberBlockEntity chamber && chamber.isBusOutput() ? chamber : null;
    }

    /**
     * 从 {@code origin} 出发沿可穿行集合逐层 BFS，找<b>距离最近</b>的「总线输出」模式执行舱。
     * <p>逐层推进保证最近优先；<b>同一层</b>同时够到多台时按坐标字典序（x → y → z）取最小者。
     * 这里<b>不看歧义</b>（可达多条链时仍返回最近那台）—— 需要「可达链数 / 是否穷尽 / 是否未确定」的
     * 调用方请改用 {@link #searchChamberLink}（同一个 {@link LinkWalk} 的第 0 个可达即本方法的返回值，
     * 因此两条路径永远不会给出互相矛盾的归属）。</p>
     */
    @Nullable
    public static SequenceExecutionChamberBlockEntity searchChamber(final Level level, final BlockPos origin) {
        return searchChamberLink(level, origin).owner();
    }

    /**
     * 一次展开同时给出「最近的那台」「线缆簇够得到的全部执行舱（按链去重）」「是否穷尽」与「线缆簇本体」。
     * <p>本方法是<b>唯一</b>的线缆搜链实现：{@link #searchChamber} 只是取它的 {@link LinkWalk#owner()}；
     * 「被干扰 / 归属未确定」判定（{@link RsccBusInterference}）也只是把它的结果整理成报告 ——
     * 因此「能走到谁」这件事全工程只有一份代码。</p>
     *
     * <h2>可续扫：为什么调用方永远看不到半份集合（前进保证）</h2>
     * <ol>
     *     <li><b>续扫</b>：已有完整结果时，每次判定只推进 {@link #BUS_LINK_TICK_BUDGET} 格，
     *     前沿留在队列里等下一次判定 —— 单刻成本有界，且「线缆很长」不再导致任何东西被丢掉；</li>
     *     <li><b>只有扫完才发布</b>：续扫期间返回的是<b>上一次完整结果</b>（旧结果服务到新结果就绪，
     *     绝不半途换人），因此「暂时没扫完」不会让总线变普通 / 变红；</li>
     *     <li><b>首次一次扫完</b>：还没有任何完整结果时（刚读档 / 刚接线 / 状态被淘汰后重来），
     *     本次判定直接扫到底（有界 {@link #BUS_LINK_HARD_CAP}），于是不存在「刚开始那几秒」的窗口；</li>
     *     <li><b>发布前现验候选执行舱</b>：一趟续扫可能跨过世界变化，发布时对每个候选执行舱再问一次
     *     {@link #chamberAt}（拆掉的 / 已切成面输出的不计入可达）—— 因此「暂时」永远不会凭空造出
     *     第二个「可达链」把总线误判成停用；</li>
     *     <li><b>单刻成本上界就是预算</b>：不存在任何「这次调用无上限一次扫完」的通道
     *     （唯一的例外是首次那一支，它最多 {@link #BUS_LINK_HARD_CAP} 格且每台总线只发生一次）。</li>
     * </ol>
     * <p><b>按链去重也在这唯一的实现里</b>（{@link #collapseByChain}）：同一条链上的多台仓只算一个
     * 逻辑执行仓，因此 {@link LinkWalk#reachableCount()} 是链数，两个方块实体 Mixin 的
     * {@code reachableCount() == 1} 闸门天然按链生效，不存在「搜链按台、判定按链」两套口径。</p>
     */
    public static LinkWalk searchChamberLink(final Level level, final BlockPos origin) {
        if (RsccSearchGuard.isSearching()) {
            return LinkWalk.EMPTY;
        }
        RsccSearchGuard.enter();
        try {
            return floodFor(level, origin).resolve(level, origin);
        } finally {
            RsccSearchGuard.exit();
        }
    }

    // ------------------------------------------------------------------
    // 可续扫洪泛：状态表 + 淘汰
    // ------------------------------------------------------------------

    /** 取（必要时建）某条总线的可续扫状态；顺带按 {@link #BUS_LINK_PRUNE_EVERY_CALLS} 摊薄做淘汰。 */
    private static WireFlood floodFor(final Level level, final BlockPos origin) {
        Map<Long, WireFlood> byPos = FLOODS.get(level);
        if (byPos == null) {
            byPos = new HashMap<>();
            FLOODS.put(level, byPos);
        }
        final long key = origin.asLong();
        WireFlood flood = byPos.get(key);
        if (flood == null) {
            flood = new WireFlood();
            byPos.put(key, flood);
        }
        if (++floodCalls % BUS_LINK_PRUNE_EVERY_CALLS == 0) {
            prune(byPos, level.getGameTime());
        }
        return flood;
    }

    /**
     * 淘汰久未访问的记录（方块被拆掉时没有任何回调会通知本类，因此必须按时间收口）。
     * <p>只丢「已经有完整结果」的记录：正在续扫、又还没有任何结果的记录（理论上不存在，首次必然一次扫完）
     * 不会被丢掉，因此淘汰不可能把「扫到一半」的状态变成空结果。</p>
     * <p>超过软上限时按最近访问时刻保留一半：丢掉一条状态只会让下一次判定重扫一遍，
     * 不影响任何语义（发布口径与归属判定都与状态是否驻留无关）。</p>
     */
    private static void prune(final Map<Long, WireFlood> byPos, final long now) {
        if (byPos.size() <= BUS_LINK_STATE_SOFT_LIMIT) {
            byPos.values().removeIf(flood -> flood.hasSnapshot
                && now - flood.lastAccessTick > BUS_LINK_IDLE_DROP_TICKS);
            return;
        }
        final List<Map.Entry<Long, WireFlood>> entries = new ArrayList<>(byPos.entrySet());
        entries.sort(Comparator.comparingLong(
            (Map.Entry<Long, WireFlood> entry) -> entry.getValue().lastAccessTick));
        final int drop = entries.size() - BUS_LINK_STATE_SOFT_LIMIT / 2;
        for (int index = 0; index < drop; index++) {
            byPos.remove(entries.get(index).getKey());
        }
    }

    /**
     * 一条总线的「线缆簇洪泛」<b>可续扫状态</b>：队列 + 已访问集 + <b>上一次完整结果</b>。
     *
     * <h2>三条不变量</h2>
     * <ol>
     *     <li><b>前沿留在队列里</b>：本趟没扫完不算放弃，下一次判定接着扫 —— 队列就是进度；</li>
     *     <li><b>只有整趟扫完（或撞上硬上限）才写快照</b>：对外可见的集合永远是一份完整结果；</li>
     *     <li><b>发布前现验候选执行舱</b>：一趟可能跨过世界变化，因此写快照前对每个候选再问一次
     *     {@link #chamberAt}（拆掉的 / 已切成面输出的丢掉）—— 「暂时」不会凭空多算一条链。</li>
     * </ol>
     * <p>终止性：一趟总是有限 —— {@code visited} 保证每格至多入队一次，{@code polled} 又被
     * {@link #BUS_LINK_HARD_CAP} 夹住；每次判定要么推进 ≥1 格、要么发布（排空 / 触顶），
     * 因此不存在「既无进展、又无重试点」的冻结态，单次判定的成本上界就是它的预算。</p>
     */
    private static final class WireFlood {
        /** 本趟的前沿（含起点）。 */
        private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        /** 本趟已发现过的格子（含「只看一眼、不是导线」的邻格，避免重复探测）。 */
        private final Set<BlockPos> visited = new HashSet<>();
        /** 本趟发现的线缆格（BFS 顺序；只有发布时才成为对外可见的簇）。 */
        private final List<BlockPos> cluster = new ArrayList<>();
        /** 本趟碰到的执行舱（发现顺序）；只在本趟内使用，发布时立刻清掉（不长期持有方块实体）。 */
        private final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers = new LinkedHashMap<>();
        /** 前沿格各自的层号（用于「先近后远」排序；出队即丢）。 */
        private final Map<BlockPos, Integer> depthOf = new HashMap<>();
        /** 本趟碰到的执行舱各自的层号（只有这几项要留到发布时刻）。 */
        private final Map<BlockPos, Integer> chamberDepth = new HashMap<>();

        /** 上一次<b>完整</b>扫描的结果（对外可见的唯一数据源）。 */
        private List<BlockPos> snapshotReachable = List.of();
        private List<BlockPos> snapshotCluster = List.of();
        private boolean snapshotExhaustive = true;
        /** 是否已经有过一份完整结果（没有 ⇒ 本次判定一次扫完，见 {@link #resolve}）。 */
        private boolean hasSnapshot;

        private boolean running;
        /** 本趟是否撞到硬上限（触顶仍然发布，但显式标 {@code exhaustive=false} 并打锚点日志）。 */
        private boolean capped;
        /** 本趟已出队的格数（预算与硬上限都夹它）。 */
        private int polled;
        /** 上一次发布的时刻 / 下一次定期复扫的时刻。 */
        private long snapshotTickAt;
        private long nextScanAt;
        /** 「每 tick 只推进一次」的护栏（同一 tick 的多个调用方共享同一份进度）。 */
        private long lastAdvanceTick = Long.MIN_VALUE;
        /** 最近一次被访问的时刻（淘汰用）。 */
        private long lastAccessTick;

        /**
         * 推进 / 起一趟洪泛，并返回对外可见结果。
         * <p>三种情形：① 还没有完整结果 ⇒ 本次判定一次扫完；② 已有完整结果且到复扫周期 ⇒ 起新一趟
         * 并推进一小步（对外继续用旧快照）；③ 续扫中 ⇒ 推进一小步。三者的共同点是
         * <b>返回的永远是快照</b>，因此调用方不会看到半份集合。</p>
         */
        private LinkWalk resolve(final Level level, final BlockPos origin) {
            final long now = level.getGameTime();
            lastAccessTick = now;
            if (!running && (!hasSnapshot || now >= nextScanAt)) {
                // 起新一趟：清空前沿与已访问集，但**保留**旧快照直到新结果就绪。
                restart(origin, now);
            }
            if (running) {
                if (!hasSnapshot) {
                    // ① 首次：本次判定一次扫完（有界 HARD_CAP）。于是调用方绝不会看到半份集合，
                    //    也不会出现「刚接上线那几秒总线像没接执行舱 / 归属未确定」的窗口。
                    step(level, Integer.MAX_VALUE, now, origin);
                } else if (lastAdvanceTick != now) {
                    // ②③ 续扫：本次判定只推进预算格；扫完之前对外一律沿用上一次完整结果。
                    lastAdvanceTick = now;
                    step(level, BUS_LINK_TICK_BUDGET, now, origin);
                }
            }
            return toWalk(level);
        }

        /** 起一趟新的洪泛（保留快照）。 */
        private void restart(final BlockPos origin, final long now) {
            queue.clear();
            visited.clear();
            cluster.clear();
            chambers.clear();
            depthOf.clear();
            chamberDepth.clear();
            polled = 0;
            capped = false;
            running = true;
            lastAdvanceTick = now; // 本次判定已经用掉这一次推进机会（避免同一 tick 重复扫）
            nextScanAt = now + BUS_LINK_RESCAN_TICKS;
            visited.add(origin);
            depthOf.put(origin, 0);
            cluster.add(origin);
            queue.add(origin);
        }

        /**
         * 洪泛的一步（至多 {@code budget} 格；{@code Integer.MAX_VALUE} = 一次扫完）。
         * <p>迭代体与旧 BFS 逐字同源：两道闸门 → 已访问 → 只读方块状态判能不能穿行 →
         * 只对确认是本模组执行舱的方块取方块实体（{@code CHECK}，绝不为邻块强制初始化）→
         * 导线继续入队。唯一的区别是<b>只有排空 / 触顶才发布</b>：预算用尽就原样留着前沿，下一次接着扫。</p>
         */
        private void step(final Level level, final int budget, final long now, final BlockPos origin) {
            int left = budget;
            while (running && left > 0) {
                if (queue.isEmpty()) {
                    // 扫完：只有这一刻才发布本次结果（预算用尽时不会走到这里，因此不会发布半份集合）。
                    running = false;
                    publish(level, now);
                    return;
                }
                if (polled >= BUS_LINK_HARD_CAP) {
                    // 触顶：不是静默截断 —— 仍然发布（标 exhaustive=false），并打一条可核对的锚点日志。
                    running = false;
                    capped = true;
                    publish(level, now);
                    reportCap(origin);
                    return;
                }
                final BlockPos cell = queue.poll();
                final Integer cellDepth = depthOf.remove(cell);
                final int nextDepth = (cellDepth == null ? 0 : cellDepth) + 1;
                polled++;
                left--;
                for (final Direction direction : Direction.values()) {
                    final BlockPos neighbor = cell.relative(direction);
                    // 被扳手断开的接缝 / 面：这一步不通（与网络图、连接臂外形同一判定）
                    if (RsccCableCuts.isDisconnected(level, cell, direction)) {
                        continue;
                    }
                    // 被分隔框架套住且这一侧不在其快照里：这一步同样不通（同一判定，见 SeparationFrameGuard）
                    if (SeparationFrameGuard.blocksConnection(level, cell, direction)) {
                        continue;
                    }
                    if (!visited.add(neighbor) || !level.isLoaded(neighbor)) {
                        continue;
                    }
                    final BlockState state = level.getBlockState(neighbor);
                    if (state.getBlock() instanceof SequenceExecutionChamberBlock) {
                        final BlockEntity blockEntity = level.getChunkAt(neighbor)
                            .getBlockEntity(neighbor, LevelChunk.EntityCreationType.CHECK);
                        if (blockEntity instanceof SequenceExecutionChamberBlockEntity chamber
                            && chamber.isBusOutput()) {
                            chambers.putIfAbsent(neighbor, chamber);
                            chamberDepth.putIfAbsent(neighbor, nextDepth);
                        }
                        continue; // 执行舱不是导线：不穿过
                    }
                    if (RsccWireBlocks.isWire(state)) {
                        cluster.add(neighbor);
                        depthOf.put(neighbor, nextDepth);
                        queue.add(neighbor);
                    }
                }
            }
        }

        /**
         * 发布一份<b>完整</b>（或触顶的）结果：<b>先现验候选执行舱</b> → 排序 → 按链去重 → 写快照。
         * <p>为什么发布前还要现验：一趟续扫可能跨过世界变化（拆掉一台执行舱 / 区块加载），
         * 那一刻 {@code chambers} 里可能留着已经不存在、或已切成面输出的坐标。若不丢掉它们，
         * 一台明明只够到 1 条链的总线会因为这一条<b>幽灵</b>链被判成「够到 2 条链 ⇒ 归属未确定 ⇒ 停用」——
         * 那正是本任务要消灭的「暂态导致停用」。现验成本与后面的按链去重同一量级
         * （都只碰「本趟碰到的执行舱」，不是整张线缆图）。</p>
         */
        private void publish(final Level level, final long now) {
            running = false;
            final List<BlockPos> candidates = new ArrayList<>(chambers.size());
            for (final BlockPos pos : chambers.keySet()) {
                if (chamberAt(level, pos) != null) {
                    candidates.add(pos); // 现验：拆掉的 / 已切成面输出的不计入可达
                }
            }
            if (candidates.isEmpty()) {
                snapshotReachable = List.of();
            } else {
                // 「先距离近、再坐标字典序」：与改造前「同距离按字典序裁决」完全同源，第 0 个即旧语义的归属
                candidates.sort((a, b) -> {
                    final int byDepth = Integer.compare(chamberDepth.getOrDefault(a, 0),
                        chamberDepth.getOrDefault(b, 0));
                    return byDepth != 0 ? byDepth : lexicographic(a, b);
                });
                // 按链去重：同一条链上的多台仓是同一个逻辑执行仓 ⇒「可达数」按链计（见 collapseByChain）
                snapshotReachable = List.copyOf(collapseByChain(candidates, chambers));
            }
            snapshotCluster = List.copyOf(cluster);
            snapshotExhaustive = !capped;
            snapshotTickAt = now;
            hasSnapshot = true;
            // 发布后立刻丢掉本趟的临时容器：方块实体引用与已访问集都不再驻留（内存有界）。
            chambers.clear();
            chamberDepth.clear();
            depthOf.clear();
            visited.clear();
            queue.clear();
            cluster.clear();
        }

        /** 快照 → 对外结果。{@code owner} 现解析（那台已不是总线输出模式就成了 {@code null}）。 */
        private LinkWalk toWalk(final Level level) {
            if (!hasSnapshot) {
                // 防御性兜底（走不到：首次那一支必然在本次判定里扫完并发布）。万一走到，「可达 0 台」
                // 在当前判据里等于「这条总线还不是延长型」—— 对一台还没有任何归属记忆的总线来说，
                // 这正是「不改变它现有的类型」，而不是新造一个停用。
                return LinkWalk.EMPTY;
            }
            final BlockPos ownerPos = snapshotReachable.isEmpty() ? null : snapshotReachable.get(0);
            return new LinkWalk(chamberAt(level, ownerPos), snapshotReachable, snapshotCluster,
                snapshotExhaustive);
        }

        /**
         * 触顶时打一条锚点日志（绝不静默截断）。
         * <p>用「上一次发布的可达数 + 本趟出队格数 + 上限」三个数说话，事后可核对
         * 「这条总线的可达集合是被上限截断的、截在多少格」。</p>
         */
        private void reportCap(final BlockPos origin) {
            RsccAssemblyDebug.transition("wirelinkcap@" + RsccAssemblyDebug.at(origin),
                Integer.toString(snapshotReachable.size()),
                RsccAssemblyDebug.machine("wirebus", origin)
                    + " wire_link_flood_cap reached: polled=" + polled
                    + " cap=" + BUS_LINK_HARD_CAP
                    + " reachable=" + snapshotReachable.size()
                    + " publishedAt=" + snapshotTickAt
                    + " (线缆网络异常巨大：可达集合按上限截断并已显式报出，绝不静默)");
        }
    }

    /**
     * 把「可达执行舱」按<b>所属链</b>去重：同一条链上的若干台视为<b>同一个逻辑执行仓</b>
     * （用户布局：同配方 4 台沿箭头排成一条链 = 一台，扩容）。
     *
     * <h2>为什么必须在搜链里做，而不是留给归属判定</h2>
     * <p>{@code RsccBusInterference} 只整理本方法的结果，而「唯一归属」在调用方（两个方块实体 Mixin
     * 的 {@code rscc$linkedPosCache}）是照 {@code reachableCount() == 1} 判的：若这里仍按台计数，
     * 一条总线同时够到同链两台就会被判成「归属未确定」而停用 —— 而这两台实际上是同一个逻辑执行仓。
     * 因此去重必须发生在<b>产出 {@code reachable} 的这一步</b>（它也是 {@code reachableCount} 的唯一来源）。</p>
     *
     * <h2>代表台怎么选（确定性规则）</h2>
     * <p>每条链只留<b>它自己的可达成员里「最近的那台」</b>（入参 {@code sorted} 已是「先距离近、再坐标
     * 字典序」，因此取首次出现的那一台），而<b>不是</b>固定留链首 / 坐标最小者。理由：</p>
     * <ol>
     *     <li><b>「总线接在链上任意一台旁都必须连得上」</b>：这里只按链合并<b>计数</b>，绝不按「代表台」
     *     裁剪候选 —— 一条线缆只要够到链上任意一台，这条链就进表；代表台只是从<b>已经够到的那几台</b>
     *     里挑一个。因此不存在「只有接在代表台旁才行」这种上一轮明确拒绝的形态。</li>
     *     <li><b>把既有语义的变化压到最小</b>：代表台 = 最近的那台，正是改造前 {@code owner} /
     *     {@code Report#chambers()} 第 0 项的含义（{@code rscc$linkedExecutorPos()} 也取自它）。
     *     唯一归属时（可达恰好一台）本方法原样返回，行为逐字不变。</li>
     * </ol>
     *
     * <h2>口径</h2>
     * <p>链身份取 {@code SequenceExecutionChamberBlockEntity#chainIdentity()}（= 链首坐标，链推导是唯一
     * 事实源，这里不另写一套）。<b>不同链</b>的若干台仍各占一条表项，因此「≥2 条不同链可达 ⇒ 归属未确定」
     * 的跨链语义一字不变；{@code reachable} 为空（够不到任何执行舱）的情形根本走不到这里。</p>
     *
     * @param sorted   按「先距离近、再坐标字典序」排好的可达执行舱坐标（第 0 个即旧语义的归属）
     * @param chambers 这些坐标 → 各自的执行仓方块实体（与 {@code sorted} 同源，理论上不会缺项）
     * @return 按链去重后的可达表（保持 {@code sorted} 的相对顺序，因此第 0 个仍是最近的那台）
     */
    private static List<BlockPos> collapseByChain(
        final List<BlockPos> sorted,
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers) {
        if (sorted.size() <= 1) {
            return sorted; // 常见情形（唯一归属 / 一台都够不到）：一次链推导都不做，开销为零
        }
        final Set<Long> chainHeads = new HashSet<>(sorted.size());
        final List<BlockPos> distinct = new ArrayList<>(sorted.size());
        for (final BlockPos pos : sorted) {
            final SequenceExecutionChamberBlockEntity chamber = chambers.get(pos);
            // 方块实体缺失（理论上不会：坐标就取自 chambers 的键）时按「自成一条链」处理：宁可多算一条链
            if (chamber != null && !chainHeads.add(chamber.chainIdentity().asLong())) {
                continue; // 这条链在前面已经有更近的一台被记下了：同链合并，不重复计
            }
            distinct.add(pos);
        }
        return distinct.size() == sorted.size() ? sorted : distinct;
    }

    /** 坐标字典序比较（x → y → z）：供「同距离多台目标」的稳定裁决使用。 */
    public static int lexicographic(final BlockPos a, final BlockPos b) {
        final int byX = Integer.compare(a.getX(), b.getX());
        if (byX != 0) {
            return byX;
        }
        final int byY = Integer.compare(a.getY(), b.getY());
        return byY != 0 ? byY : Integer.compare(a.getZ(), b.getZ());
    }
}
