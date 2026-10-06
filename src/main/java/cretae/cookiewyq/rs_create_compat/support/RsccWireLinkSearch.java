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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>碰到过的所有</b>执行舱都记下来 —— 两道闸门（扳手断开 / 分隔框架）、可穿行集合、上限常量
 * 全部与旧实现逐字同源，因此不存在「归属判定一套、可达数判定另一套」。</p>
 *
 * <p><b>本轮改造（「按链去重」）</b>：把上一步记下来的执行舱再按<b>所属链</b>合并 ——
 * 同一条链上的若干台是<b>同一个逻辑执行仓</b>（用户布局：同配方 4 台沿箭头排成一条链 = 一台，扩容），
 * 因此「可达数」按<b>链</b>计而不是按台计（见 {@link #collapseByChain}）。
 * 链身份复用唯一事实源 {@code SequenceExecutionChamberBlockEntity#chainIdentity()}（链首坐标），
 * 不在这里另写一套链判定。语义目标因此变成：<b>唯一归属 = 恰好一条链够得到</b>；
 * <b>≥2 条不同的链</b>才叫「归属未确定」。{@code reachable=0}（够不到任何执行舱）的语义一字未动。</p>
 */
public final class RsccWireLinkSearch {
    /** 搜索步数上限（输出总线侧与执行舱侧同一数值，保证两侧判定一致）。 */
    public static final int LINK_MAX_STEPS = 64;
    /** 线缆簇格子数上限：到顶即认为「延长段已扎进整张线缆网」，探查未能穷尽。 */
    public static final int LINK_MAX_CELLS = 128;

    private RsccWireLinkSearch() {
    }

    /**
     * 一次「线缆簇展开」的结果。
     *
     * @param owner      最近的那台「总线输出」执行舱（无则 {@code null}）；
     *                   距离最近优先、同距离按 {@link #lexicographic} 裁决 —— 与改造前的语义完全一致
     * @param reachable  整条线缆簇够得到的执行舱，<b>已按链去重</b>：每一条链只留<b>它自己最近的</b>那台成员
     *                   （规则与理由见 {@link #collapseByChain}），因此本表长度 = 可达的
     *                   <b>逻辑执行仓（链）</b>数，而不是方块台数；
     *                   排序仍是「先距离近、再坐标字典序」，因此第 0 个就是 {@code owner} 的坐标
     * @param cluster    线缆簇本体（含起点总线；按 BFS 顺序，供半透明叠加层标出「延长段」）
     * @param exhaustive 探查是否穷尽（未走满 {@link #LINK_MAX_STEPS}、也未到 {@link #LINK_MAX_CELLS}）
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
         * 归属是否<b>未确定</b>（新语义的「被干扰」）：
         * <ul>
         *     <li>可达 <b>≥2 条不同的链</b>（不论距离是否相等）→ 未确定；</li>
         *     <li>可达<b>恰好 1 条链但探查未能穷尽</b> → 未确定（宁可保护：再远的地方可能还有一条链）。</li>
         * </ul>
         * <p><b>同一条链上够到多台不算未确定</b>：那是「4 台 = 一台」的扩容，本来就是一个逻辑执行仓
         * （链成员由 {@code SequenceExecutionChamberBlockEntity#chainMembers()} 定义，本表已按链去重）。</p>
         * <p>「一台都够不到」不算未确定 —— 那时本来就没有归属可言，总线只是普通总线
         * （否则任何接了大线网却还没接执行舱的总线都会亮红条）。</p>
         */
        public boolean ambiguous() {
            return reachable.size() >= 2 || (reachable.size() == 1 && !exhaustive);
        }

        /** 归属是否<b>唯一确定</b>（可达恰好 1 条链且探查穷尽）= 延长型可以照常工作。 */
        public boolean unique() {
            return reachable.size() == 1 && exhaustive;
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
            final List<BlockPos> cluster = new ArrayList<>();
            final Set<BlockPos> visited = new HashSet<>();
            // 够得到的执行舱（保持首次发现顺序，最后再按「距离 → 字典序」排序）+ 各自被发现时所在的层号
            final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers = new LinkedHashMap<>();
            final Map<BlockPos, Integer> depths = new HashMap<>();
            visited.add(origin);
            cluster.add(origin);
            List<BlockPos> frontier = new ArrayList<>(1);
            frontier.add(origin);
            int steps = 0;
            boolean exhaustive = true;
            while (!frontier.isEmpty()) {
                if (steps++ >= LINK_MAX_STEPS || cluster.size() >= LINK_MAX_CELLS) {
                    // 还有没走完的部分：探查未能穷尽（调用方按「可能还有更多可达执行舱」处理）
                    exhaustive = false;
                    break;
                }
                final List<BlockPos> next = new ArrayList<>();
                for (final BlockPos cell : frontier) {
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
                                depths.putIfAbsent(neighbor, steps);
                            }
                            continue; // 执行舱不是导线：不穿过
                        }
                        if (RsccWireBlocks.isWire(state)) {
                            cluster.add(neighbor);
                            next.add(neighbor);
                        }
                    }
                }
                frontier = next;
            }
            if (chambers.isEmpty()) {
                return new LinkWalk(null, List.of(), List.copyOf(cluster), exhaustive);
            }
            final List<BlockPos> reachable = new ArrayList<>(chambers.keySet());
            // 「先距离近、再坐标字典序」：与改造前「同距离按字典序裁决」完全同源，且第 0 个就是旧语义的归属
            reachable.sort((a, b) -> {
                final int byDepth = Integer.compare(depths.getOrDefault(a, 0), depths.getOrDefault(b, 0));
                return byDepth != 0 ? byDepth : lexicographic(a, b);
            });
            // 按链去重：同一条链上的多台仓是同一个逻辑执行仓 ⇒ 「可达数」按链计（见 collapseByChain）
            final List<BlockPos> distinct = collapseByChain(reachable, chambers);
            return new LinkWalk(chambers.get(distinct.get(0)), List.copyOf(distinct),
                List.copyOf(cluster), exhaustive);
        } finally {
            RsccSearchGuard.exit();
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
