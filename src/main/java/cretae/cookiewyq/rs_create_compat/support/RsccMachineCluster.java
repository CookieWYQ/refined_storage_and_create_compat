package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import cretae.cookiewyq.rs_create_compat.block.AdvancedSchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.CollectionCacheBlock;
import cretae.cookiewyq.rs_create_compat.block.SchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 「同类型机器相邻 = 一个整体」的集群解析器（<b>唯一实现</b>）。
 *
 * <h2>模型</h2>
 * <ul>
 *     <li><b>集群</b> = 同类型方块的 6 向连通分量（只统计<b>已加载</b>的坐标）；唯一的「同族」例外是
 *     装填器家族（基础 / 高级相邻也合并成一个整体，见 {@link #sameFamily}）；</li>
 *     <li><b>主控（master）</b> = 成员里坐标字典序最小者（x → y → z，确定性，与遍历顺序无关）；</li>
 *     <li><b>唯一一份存储</b>：主控建出「容量 = 台数 × 单机容量」的共享载荷，全部成员采纳同一对象
 *     —— 于是「容量叠加」与「内容共享」同时成立，且不需要任何同步逻辑；</li>
 *     <li><b>落盘边界</b>：一个集群只有主控保存内容（{@link RsccClusterable#rscc$clusterOwnsPayload()}），
 *     其余成员写空载荷 —— 从根上排除「同一份内容被写两份、读档合并后翻倍」。</li>
 * </ul>
 *
 * <h2>性能模型（不每 tick BFS）</h2>
 * <ol>
 *     <li><b>事件驱动失效</b>：邻块变化（放置 / 破坏 / 替换）由 {@link RsccClusterInvalidation} 作废
 *     相关缓存，下一次 {@link #ensure} 立即重算；</li>
 *     <li><b>缓存命中即返回</b>：{@link #ensure} 先查「坐标 → 集群」缓存，命中时只做
 *     「20 tick 一次」的成员存活校验（每个成员一次 {@code isLoaded} + 一次方块类型比较），
 *     未到校验周期时只花一次 Map 查询；</li>
 *     <li><b>搜索只读方块状态</b>：BFS 穿行阶段绝不触达方块实体（避免强制加载 / 递归初始化），
 *     只有确认是同类型方块后才用 {@code getBlockEntity(pos, CHECK)} 取已存在的方块实体；</li>
 *     <li><b>规模上限</b> {@link #MAX_MEMBERS}，防止超大结构把单次重算拖慢。</li>
 * </ol>
 *
 * <h2>跨区块策略（本轮选定）</h2>
 * <b>允许跨区块合并，但只以「已加载部分」为准，且内容跟着主控走</b>：
 * 集群永远只在<b>已加载</b>的连通分量上成立；当成员离开加载范围（区块卸载 / 被破坏）时：
 * <ul>
 *     <li>离场的是<b>主控</b>：内容<b>跟着主控走</b>（主控缩容到自己单机的容量），仍在加载的成员拿到一份
 *     「等容量但为空」的共享存储继续工作；</li>
 *     <li>离场的是<b>普通成员</b>：内容留在集群，离场者拿到一份空的独立存储；缩容装不下的余量
 *     交给离场者（随后由它的破坏结算 / 区块落盘带走）。</li>
 * </ul>
 * <b>理由与权衡</b>：区块卸载时「先落盘再 {@code setRemoved}，还是先 {@code setRemoved} 再落盘」
 * 在平台侧没有对调用方可见的保证，任何「卸载时把内容交给仍在加载的 master」的写法都可能与
 * 已经落盘的那一份重复，读档合并后资源翻倍（最严重的复制事故）。而「内容永远只跟着当时持有它的
 * 那一方走」在两种时序下都成立：卸载方带走并落盘，幸存方从空开始，重新加载时再合并（两份是
 * 不相交的，合并只会得到正确总量）。<b>代价</b>：跨区块的集群在部分区块未加载时，未加载部分的
 * 内容暂时不可见（但绝不丢失），容量也只按已加载台数计算。
 */
public final class RsccMachineCluster {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单个集群的最大机器数（超过部分不再并入，避免超大结构拖慢重算）。 */
    public static final int MAX_MEMBERS = 64;
    /** 缓存命中后重新做「成员存活校验」的周期（tick）。 */
    private static final int VALIDATE_INTERVAL_TICKS = 20;

    /** 世界 → （成员坐标 → 集群）。用弱键避免世界卸载后泄漏。 */
    private static final Map<Level, Map<Long, Cluster>> CACHE = new WeakHashMap<>();

    /** 一个集群：成员（坐标字典序）、主控、共享载荷，以及用于存活校验的同类型方块。 */
    public static final class Cluster {
        private final List<BlockPos> members;
        private final BlockPos master;
        private final Object payload;
        private final Block block;
        private long lastValidated;

        private Cluster(final List<BlockPos> members, final BlockPos master, final Object payload,
                        final Block block) {
            this.members = List.copyOf(members);
            this.master = master;
            this.payload = payload;
            this.block = block;
        }

        /** 成员坐标（字典序，只读）。 */
        public List<BlockPos> members() {
            return members;
        }

        /** 主控坐标。 */
        public BlockPos master() {
            return master;
        }

        /** 集群共用的那一份存储载荷。 */
        public Object payload() {
            return payload;
        }

        public int size() {
            return members.size();
        }

        /** 缓存是否仍然可信：成员都还在、方块类型未变（同族即可）、且校验周期未到。 */
        private boolean isFresh(final ServerLevel level) {
            final long now = level.getGameTime();
            if (now - lastValidated < VALIDATE_INTERVAL_TICKS) {
                return true;
            }
            for (final BlockPos pos : members) {
                if (!level.isLoaded(pos) || !sameFamily(level.getBlockState(pos).getBlock(), block)) {
                    return false;
                }
            }
            lastValidated = now;
            return true;
        }
    }

    private RsccMachineCluster() {
    }

    /** 该方块状态是否属于「可成集群的机器」（本模组自己的机器；RS 原版方块不在范围内）。 */
    public static boolean isClusterBlock(final BlockState state) {
        return state.getBlock() instanceof CollectionCacheBlock
            || state.getBlock() instanceof SequenceExecutionChamberBlock
            || isLoaderBlock(state.getBlock());
    }

    /** 是否属于「装填器家族」（基础装填器 / 高级装填器）。 */
    private static boolean isLoaderBlock(final Block block) {
        return block instanceof SchematicLoaderBlock || block instanceof AdvancedSchematicLoaderBlock;
    }

    /**
     * 两个方块是否属于<b>同一个可成集群家族</b>（决定 BFS 能否穿过、存活校验是否算同一族）。
     * <p>默认 = 同一种方块（用户规则「同类型机器相邻」）。唯一例外是<b>装填器家族</b>：
     * 基础与高级的「从网络拉取进来暂存的那一份物品存储」语义完全相同（都是一个个物品格），
     * 因此相邻时同样合并成一个整体 —— 容量按各台自己的单机格数求和
     * （见 {@link RsccClusterable#rscc$clusterNewPayloadFor}），绝不等同于「按台数 × 某一种容量」。
     */
    private static boolean sameFamily(final Block first, final Block second) {
        if (first == second) {
            return true;
        }
        return isLoaderBlock(first) && isLoaderBlock(second);
    }

    /** 该坐标上是否是「可成集群的机器方块」（只读方块状态，绝不触达方块实体）。 */
    public static boolean isClusterBlock(final Level level, final BlockPos pos) {
        return level.isLoaded(pos) && isClusterBlock(level.getBlockState(pos));
    }

    /**
     * 只读查询：该坐标<b>当前所在集群</b>的成员坐标（含自己）。缓存里还没有这台机器的解析结果、
     * 或它本就是单机时，返回只含自己的单元素列表 —— 调用方拿到的永远是「一份可用的归属范围」，
     * 绝不会因为「还没解析过」而漏掉自己。
     *
     * <p><b>为什么不触发重建</b>：调用方是每台执行仓的周期节拍（判断「这一步是不是别的仓认领的」），
     * 触发 BFS 重算会让 N 台机器各扫一遍世界。缓存由方块实体的每 tick {@code ensure} 与放置 / 破坏
     * 事件维护，这里读到的一定是最新一次的结果或它的空缺省。</p>
     */
    public static List<BlockPos> clusterMembers(final Level level, final BlockPos pos) {
        if (level == null || pos == null) {
            return List.of();
        }
        final Map<Long, Cluster> map = CACHE.get(level);
        final Cluster cluster = map == null ? null : map.get(pos.asLong());
        return cluster == null ? List.of(pos) : cluster.members();
    }

    // ==================== 缓存失效 ====================

    /** 作废「坐标所在集群」的解析缓存（坐标本身与它的 6 个邻块都要看：新放的机器可能刚贴上旧集群）。 */
    public static void invalidateAround(final Level level, final BlockPos pos) {
        final Map<Long, Cluster> map = CACHE.get(level);
        if (map == null || map.isEmpty()) {
            return;
        }
        invalidateAt(map, pos);
        for (final Direction direction : Direction.values()) {
            invalidateAt(map, pos.relative(direction));
        }
    }

    private static void invalidateAt(final Map<Long, Cluster> map, final BlockPos pos) {
        final Cluster cluster = map.get(pos.asLong());
        if (cluster == null) {
            return;
        }
        for (final BlockPos member : cluster.members()) {
            map.remove(member.asLong());
        }
    }

    // ==================== 解析入口 ====================

    /**
     * 集群解析 + 重建（由成员方块实体每 tick 调用；内部有缓存、周期校验与事件驱动失效，不做无条件 BFS）。
     */
    public static void ensure(final RsccClusterable member) {
        final Level level = member.rscc$clusterLevel();
        if (!(level instanceof ServerLevel server) || level.isClientSide()) {
            return;
        }
        final Map<Long, Cluster> map = CACHE.computeIfAbsent(level, key -> new HashMap<>());
        final Cluster cached = map.get(member.rscc$clusterPos().asLong());
        if (cached != null && cached.isFresh(server)) {
            return;
        }
        rebuild(server, map, member.rscc$clusterPos(), null);
    }

    /**
     * 成员被移除（方块破坏 / 区块卸载 / 被替换）时的拆集群处理 —— 在方块实体的
     * {@code setRemoved()} 里调用，此时方块实体自身仍然可用、集群缓存仍然完整。
     * <p>规则见类注释「跨区块策略」：内容跟着当时持有它的一方走，绝不复制。
     */
    public static void onMemberRemoved(final Level level, final RsccClusterable member) {
        if (!(level instanceof ServerLevel server) || level.isClientSide()) {
            return;
        }
        final BlockPos pos = member.rscc$clusterPos();
        final Map<Long, Cluster> map = CACHE.get(server);
        final Cluster old = map == null ? null : map.get(pos.asLong());
        if (old == null || old.size() <= 1) {
            // 单机（或从未参与集群）：保持独立存储，内容照旧落自己头上
            member.rscc$clusterStandalone();
            member.rscc$clusterSetOwnsPayload(true);
            if (map != null && old != null) {
                for (final BlockPos p : old.members()) {
                    map.remove(p.asLong());
                }
            }
            mapRemove(map, pos);
            return;
        }
        if (pos.equals(old.master())) {
            // 主控离场：内容跟着主控走（缩容到单机容量），幸存者稍后重建为「等容量但为空」
            final Object standalone = member.rscc$clusterNewPayload(1);
            final long leftover = member.rscc$clusterMerge(old.payload(), standalone);
            member.rscc$clusterAdopt(standalone);
            member.rscc$clusterSetOwnsPayload(true);
            if (leftover > 0L) {
                // 极端情况（单机容量装不下）：余量仍在旧载荷里，落到世界，绝不销毁
                member.rscc$clusterSpill(old.payload());
            }
        } else {
            // 普通成员离场：内容留在集群，本机拿一份空的独立存储
            member.rscc$clusterStandalone();
            member.rscc$clusterSetOwnsPayload(true);
        }
        // 让仍在加载的幸存者各自重建：内容归属按上面的规则自然落在正确的一方
        for (final BlockPos p : old.members()) {
            if (p.equals(pos) || !isClusterBlock(server, p)) {
                continue;
            }
            final Cluster current = map.get(p.asLong());
            if (current != null && current != old) {
                continue; // 这个分量已经重建过了
            }
            rebuild(server, map, p, member);
        }
        mapRemove(map, pos);
    }

    /**
     * 方块被破坏时把「本机持有的共享内容」移交给仍在加载的幸存集群。
     * <p>在 {@link BlockContentReleaser#release} 的最前面调用：破坏方块会走
     * 「先 {@code getDrops} → 再 {@code onRemove}」的顺序，因此必须在结算前把内容交出去，
     * 否则整份集群内容会被写进方块物品 NBT（重新放下时再合并 = 资源翻倍）。
     */
    public static void handOverToSurvivors(final Level level, final RsccClusterable member) {
        if (!(level instanceof ServerLevel server) || level.isClientSide()) {
            return;
        }
        final Map<Long, Cluster> map = CACHE.get(server);
        if (map == null) {
            return;
        }
        final Object mine = member.rscc$clusterPayload();
        for (final Direction direction : Direction.values()) {
            final BlockPos neighbor = member.rscc$clusterPos().relative(direction);
            if (!isClusterBlock(server, neighbor)) {
                continue;
            }
            final BlockEntity be = server.getChunkAt(neighbor)
                .getBlockEntity(neighbor, LevelChunk.EntityCreationType.CHECK);
            if (!(be instanceof RsccClusterable survivor) || survivor == member
                || !survivor.rscc$clusterCompatible(member)) {
                continue;
            }
            ensure(survivor); // 幸存者可能还没来得及重建（缓存为 null）
            final Cluster cluster = map.get(survivor.rscc$clusterPos().asLong());
            if (cluster == null || cluster.payload() == null || cluster.payload() == mine) {
                continue;
            }
            final long leftover = member.rscc$clusterMerge(mine, cluster.payload());
            if (leftover > 0L) {
                member.rscc$clusterSpill(mine);
            }
            return; // 交给一个幸存集群即可（同一分量内共享同一份载荷）
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 重算以 {@code originPos} 为起点的集群，并把「容量叠加 + 内容共享」落到各成员身上。
     *
     * @param departing 触发本次重算的离场成员（可为 null）；仅用于「缩容余量」的兜底落点与日志
     */
    private static void rebuild(final ServerLevel level, final Map<Long, Cluster> map,
                                final BlockPos originPos, @Nullable final RsccClusterable departing) {
        final Cluster old = map.get(originPos.asLong());
        final List<RsccClusterable> members = collect(level, originPos);
        if (members.isEmpty()) {
            if (old != null) {
                for (final BlockPos p : old.members()) {
                    map.remove(p.asLong());
                }
            }
            return;
        }
        final List<BlockPos> positions = new ArrayList<>(members.size());
        for (final RsccClusterable m : members) {
            positions.add(m.rscc$clusterPos());
        }
        final RsccClusterable master = members.get(0);
        final Object oldPayload = old == null ? null : old.payload();
        // 拓扑与内容归属都没变 → 不重建存储对象（存储对象身份稳定是界面与逻辑的前提）
        if (old != null && old.members().equals(positions)
            && old.master().equals(master.rscc$clusterPos())
            && master.rscc$clusterPayload() == oldPayload) {
            for (final BlockPos p : positions) {
                map.put(p.asLong(), old);
            }
            return;
        }
        // 1) 新共享存储：容量 = 台数 × 单机容量（容量叠加；同族但单机容量不同时按各台容量求和）
        final Object shared = master.rscc$clusterNewPayloadFor(members);
        // 2) 旧共享内容搬进新共享（成员都指向同一份旧载荷，第二次搬是空操作）
        if (oldPayload != null && oldPayload != shared) {
            drain(master, oldPayload, shared, spillPos(departing, originPos));
        }
        // 3) 新加入的成员：把自己的存储内容并入
        for (final RsccClusterable m : members) {
            final Object own = m.rscc$clusterPayload();
            if (own == shared || own == oldPayload || own == null) {
                continue;
            }
            drain(m, own, shared, spillPos(departing, m.rscc$clusterPos()));
        }
        // 4) 全部成员采纳同一份共享存储；只有主控承担落盘
        for (final RsccClusterable m : members) {
            m.rscc$clusterAdopt(shared);
            m.rscc$clusterSetOwnsPayload(m == master);
            m.rscc$clusterChanged(members.size(), master.rscc$clusterPos());
        }
        if (old != null) {
            for (final BlockPos p : old.members()) {
                if (!positions.contains(p)) {
                    map.remove(p.asLong());
                }
            }
        }
        final Cluster next = new Cluster(positions, master.rscc$clusterPos(), shared,
            level.getBlockState(originPos).getBlock());
        for (final BlockPos p : positions) {
            map.put(p.asLong(), next);
        }
        LOGGER.debug("[rs_create_compat] 机器集群成型：{} 台，主控 {}（{}）",
            members.size(), master.rscc$clusterPos(), level.dimension().location());
    }

    /** 把一份载荷搬进目标载荷；搬不走的余量交给兜底归宿（落回世界），绝不销毁。 */
    private static void drain(final RsccClusterable host, final Object from, final Object to,
                              final BlockPos spillPos) {
        if (from == null || to == null || from == to) {
            return;
        }
        final long leftover = host.rscc$clusterMerge(from, to);
        if (leftover <= 0L) {
            return;
        }
        LOGGER.warn("[rs_create_compat] 集群容量不足以容纳合并内容，余量 {} 已在 {} 处落回世界",
            leftover, spillPos);
        host.rscc$clusterSpill(from);
    }

    /** 缩容余量的落点：优先离场成员（随后由它的破坏结算 / 区块落盘带走），否则用自身坐标。 */
    private static BlockPos spillPos(@Nullable final RsccClusterable departing, final BlockPos fallback) {
        return departing == null ? fallback : departing.rscc$clusterPos();
    }

    private static void mapRemove(@Nullable final Map<Long, Cluster> map, final BlockPos pos) {
        if (map != null) {
            map.remove(pos.asLong());
        }
    }

    /**
     * 以 {@code origin} 为起点做 6 向 BFS，收集同类型、已加载、且方块实体已存在的成员。
     * <p>穿行只读方块状态（绝不触发方块实体加载）；结果按坐标字典序排序，故 {@code members.get(0)}
     * 就是确定性的主控。
     */
    private static List<RsccClusterable> collect(final ServerLevel level, final BlockPos origin) {
        final Block originBlock = level.getBlockState(origin).getBlock();
        final List<BlockPos> found = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < MAX_MEMBERS) {
            final BlockPos cell = queue.poll();
            final BlockEntity be = level.getChunkAt(cell)
                .getBlockEntity(cell, LevelChunk.EntityCreationType.CHECK);
            if (!(be instanceof RsccClusterable)) {
                continue;
            }
            found.add(cell);
            for (final Direction direction : Direction.values()) {
                final BlockPos neighbor = cell.relative(direction);
                if (!visited.add(neighbor) || !level.isLoaded(neighbor)) {
                    continue;
                }
                if (!sameFamily(level.getBlockState(neighbor).getBlock(), originBlock)) {
                    continue; // 只认「同族方块」相邻，不经过线缆
                }
                queue.add(neighbor);
            }
        }
        found.sort((a, b) -> RsccWireLinkSearch.lexicographic(a, b));
        final List<RsccClusterable> members = new ArrayList<>(found.size());
        for (final BlockPos pos : found) {
            final BlockEntity be = level.getChunkAt(pos)
                .getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (be instanceof RsccClusterable member) {
                members.add(member);
            }
        }
        return members;
    }
}
