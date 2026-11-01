package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.FEEnergyCubeBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * FE 能量立方的「相邻容量共享」集群解析器。
 *
 * <h2>为什么<b>不</b>复用 {@link RsccMachineCluster}</h2>
 * <p>那套模型的根基是「<b>唯一一份共享载荷</b>」：主控建出 {@code 容量 = 台数 × 单机容量} 的对象、全部成员
 * 采纳同一份，只有主控落盘。它对物品 / 流体库存是对的（内容必须真的共享），但对<b>电量</b>是错的：
 * 用户的口径是「每块只按自身 stored 记账、拆开各保留自身电量、不做平均」。电量一旦共享成一份载荷，
 * 「拆掉其中一块」就必须做内容移交（把一份电量在两个对象之间搬），而那正是上一轮反复出问题的
 * 「复制 / 丢失」高发区（见 {@link RsccMachineCluster} 的类注释）。
 *
 * <h2>本模型（电量永远留在自己身上）</h2>
 * <ul>
 *     <li><b>集群</b> = 同一种方块（{@link FEEnergyCubeBlock}）的 6 向连通分量，<b>只统计已加载的坐标</b>；</li>
 *     <li><b>每块自己的电量存在自己的方块实体里</b>（{@code EnergyStorage}），上限恒为
 *     {@code Config.feEnergyCubeCapacity}。集群<b>不持有</b>任何电量，也不做任何移交；</li>
 *     <li><b>容量</b> = Σ 各成员自身容量（N 块 = N×50M）；<b>存量</b> = Σ 各成员自身存量；</li>
 *     <li>于是「拆一块」是<b>零迁移</b>的：剩下的块一格不动，容量自然变成 (N−1)×50M；
 *     又因为「每块自身存量 ≤ 自身容量」这条硬约束，剩余存量永远 ≤ 新容量，<b>不可能溢出</b>；</li>
 *     <li><b>取电</b>（{@link Snapshot#extract}）允许跨成员扣：按<b>坐标字典序</b>（x → y → z）从最小的成员
 *     开始，扣到够为止；<b>存入</b>（{@link Snapshot#receive}）先填「接收方自己」，再按字典序填其它有空位的
 *     成员 —— 只报容量不共享空间的话「N 块 = N×50M」就只是界面上的一句假话（单块满了、其它块还空着）。</li>
 * </ul>
 *
 * <h2>一致性 / 性能模型</h2>
 * <ol>
 *     <li><b>快照只存坐标，不缓存方块实体引用</b>：区块卸载后重新加载会造出<b>新的</b>方块实体对象，
 *     缓存引用会永久读到旧对象（电量还是卸载那一刻的）。存坐标则每次都从当前世界的方块实体读，
 *     区块卸载 / 重载对上层完全透明，也没有「引用失效」这个状态；</li>
 *     <li><b>事件驱动失效</b>：放置 / 破坏 / 邻块变化由方块调用 {@link #invalidateAround}，
 *     下一次 {@link #resolve} 立刻重算；</li>
 *     <li><b>周期校验</b>：缓存命中时每 {@link #VALIDATE_INTERVAL_TICKS} tick 才做一次「成员都还在、
 *     方块类型没变」的校验（只读方块状态，绝不触达方块实体），其余 tick 只花一次 Map 查询；</li>
 *     <li><b>规模上限</b> {@link #MAX_MEMBERS}，防止超大结构把单次重算拖慢。</li>
 * </ol>
 *
 * <p><b>线程 / 端侧</b>：解析与所有写操作只在<b>服务端</b>发生（{@code ServerLevel}）。客户端一律只走
 * 「本块」的数值，绝不解析集群、绝不写世界（方块实体的能力视图里有一道显式判断）。</p>
 */
public final class EnergyCubeCluster {
    /** 单个集群的最大成员数（超过部分不再并入，避免超大结构拖慢重算）。 */
    public static final int MAX_MEMBERS = 64;
    /** 缓存命中后重新做「成员存活校验」的周期（tick）。 */
    private static final int VALIDATE_INTERVAL_TICKS = 20;

    /** 成员排序口径：x → y → z（与 {@link RsccMachineCluster} 的主控口径一致，确定性、与遍历顺序无关）。 */
    private static final Comparator<BlockPos> LEXICOGRAPHIC = Comparator
        .<BlockPos>comparingInt(BlockPos::getX)
        .thenComparingInt(BlockPos::getY)
        .thenComparingInt(BlockPos::getZ);

    /** 世界 → （成员坐标 → 集群快照）。用弱键避免世界卸载后泄漏。 */
    private static final Map<Level, Map<Long, Snapshot>> CACHE = new WeakHashMap<>();

    private EnergyCubeCluster() {
    }

    /**
     * 一个集群快照。<b>只读视图 + 三个会改电量的操作</b>，不持有任何电量、不持有方块实体引用。
     */
    public static final class Snapshot {
        private final Level level;
        private final List<BlockPos> members;
        private long lastValidated;

        private Snapshot(final Level level, final List<BlockPos> members) {
            this.level = level;
            this.members = List.copyOf(members);
        }

        /** 成员坐标（按坐标字典序，只读）。 */
        public List<BlockPos> members() {
            return members;
        }

        /** 主控 = 坐标字典序最小的成员。本模型里它只是「确定性代表」，不承担任何落盘 / 内容归属。 */
        public BlockPos master() {
            return members.get(0);
        }

        public int size() {
            return members.size();
        }

        public boolean contains(final BlockPos pos) {
            return members.contains(pos);
        }

        /** 集群存量 = Σ 各成员自身存量（只统计此刻真的取得到的方块实体）。 */
        public long stored() {
            long sum = 0L;
            for (final BlockPos pos : members) {
                final FEEnergyCubeBlockEntity cube = cubeAt(level, pos);
                if (cube != null) {
                    sum += cube.getOwnEnergyStored();
                }
            }
            return sum;
        }

        /** 集群容量 = Σ 各成员自身容量（取不到的成员<b>两边都不计</b>，保证 stored ≤ capacity 恒成立）。 */
        public long capacity() {
            long sum = 0L;
            for (final BlockPos pos : members) {
                final FEEnergyCubeBlockEntity cube = cubeAt(level, pos);
                if (cube != null) {
                    sum += cube.getOwnCapacity();
                }
            }
            return sum;
        }

        /**
         * 从集群取电：按成员坐标字典序逐个扣，扣到 {@code amount} 为止。
         * <p><b>调用方必须保证不再写回别的路径</b>：返回值 = 真实扣掉的量，恒 ≤ {@code amount}，
         * 且恒 ≤ 扣之前的集群存量。
         */
        public long extract(final long amount) {
            long remaining = Math.max(0L, amount);
            long extracted = 0L;
            for (final BlockPos pos : members) {
                if (remaining <= 0L) {
                    break;
                }
                final FEEnergyCubeBlockEntity cube = cubeAt(level, pos);
                if (cube == null) {
                    continue;
                }
                final long got = cube.extractOwn(remaining);
                if (got > 0L) {
                    extracted += got;
                    remaining -= got;
                }
            }
            return extracted;
        }

        /**
         * 往集群灌电：先填 {@code preferred}（发起方自己），再按字典序填其它有空位的成员。
         *
         * @param preferred 优先接收的成员坐标（可为 null；不在本集群内则忽略）
         * @return 真实收下的量（恒 ≤ {@code amount}，且恒 ≤ 灌之前的集群剩余空间）
         */
        public long receive(final long amount, @Nullable final BlockPos preferred) {
            long remaining = Math.max(0L, amount);
            long received = 0L;
            if (preferred != null && members.contains(preferred)) {
                final FEEnergyCubeBlockEntity cube = cubeAt(level, preferred);
                if (cube != null) {
                    final long got = cube.receiveOwn(remaining);
                    received += got;
                    remaining -= got;
                }
            }
            for (final BlockPos pos : members) {
                if (remaining <= 0L) {
                    break;
                }
                if (pos.equals(preferred)) {
                    continue;
                }
                final FEEnergyCubeBlockEntity cube = cubeAt(level, pos);
                if (cube == null) {
                    continue;
                }
                final long got = cube.receiveOwn(remaining);
                if (got > 0L) {
                    received += got;
                    remaining -= got;
                }
            }
            return received;
        }

        /** 缓存是否仍然可信：成员都还在、方块类型未变，且校验周期未到。 */
        private boolean isFresh(final ServerLevel server) {
            final long now = server.getGameTime();
            if (now - lastValidated < VALIDATE_INTERVAL_TICKS) {
                return true;
            }
            for (final BlockPos pos : members) {
                if (!isCubeBlock(server, pos)) {
                    return false;
                }
            }
            lastValidated = now;
            return true;
        }
    }

    // ==================== 缓存失效 ====================

    /**
     * 作废「坐标所在集群」的解析缓存：坐标本身与它的 6 个邻块都要看 ——
     * 新放的立方可能刚贴上旧集群，被拆的立方可能刚从旧集群里离开。
     * <p>由 {@link FEEnergyCubeBlock} 在放置 / 破坏 / 邻块变化时调用；幂等、极便宜（缓存为空时直接返回）。
     */
    public static void invalidateAround(@Nullable final Level level, @Nullable final BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        final Map<Long, Snapshot> map = CACHE.get(level);
        if (map == null || map.isEmpty()) {
            return;
        }
        invalidateAt(map, pos);
        for (final Direction direction : Direction.values()) {
            invalidateAt(map, pos.relative(direction));
        }
    }

    private static void invalidateAt(final Map<Long, Snapshot> map, final BlockPos pos) {
        final Snapshot snapshot = map.get(pos.asLong());
        if (snapshot != null) {
            removeAll(map, snapshot);
        }
    }

    // ==================== 解析入口 ====================

    /**
     * 取「坐标所在的集群快照」（带缓存）。<b>只在服务端用于写路径</b>；客户端 / 非服务端世界返回
     * 只含自己的单元素快照（读也安全，但写绝不发生 —— 调用方另外还有端侧判断）。
     */
    public static Snapshot resolve(@Nullable final Level level, final BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return new Snapshot(level, List.of(pos));
        }
        final Map<Long, Snapshot> map = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        final Snapshot cached = map.get(pos.asLong());
        if (cached != null && cached.isFresh(server)) {
            return cached;
        }
        return rebuild(server, map, pos);
    }

    // ==================== 内部实现 ====================

    private static Snapshot rebuild(final ServerLevel level, final Map<Long, Snapshot> map,
                                    final BlockPos origin) {
        final List<BlockPos> found = collect(level, origin);
        final List<BlockPos> members = found.isEmpty() ? List.of(origin) : found;
        final Snapshot snapshot = new Snapshot(level, members);
        // 先摘掉这些坐标上残留的旧快照（成员的旧集群可能已经换了形状）
        for (final BlockPos pos : members) {
            final Snapshot previous = map.get(pos.asLong());
            if (previous != null && previous != snapshot) {
                removeAll(map, previous);
            }
        }
        for (final BlockPos pos : members) {
            map.put(pos.asLong(), snapshot);
        }
        return snapshot;
    }

    /**
     * 以 {@code origin} 为起点做 6 向 BFS，收集同类型、已加载的成员坐标，并按坐标字典序排序。
     * <p>穿行阶段<b>只读方块状态</b>，绝不触达方块实体（避免强制加载 / 递归初始化）。
     */
    private static List<BlockPos> collect(final ServerLevel level, final BlockPos origin) {
        final List<BlockPos> found = new ArrayList<>();
        if (!isCubeBlock(level, origin)) {
            return found;
        }
        final Set<BlockPos> visited = new HashSet<>();
        final Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < MAX_MEMBERS) {
            final BlockPos cell = queue.poll();
            if (!isCubeBlock(level, cell)) {
                continue;
            }
            found.add(cell);
            for (final Direction direction : Direction.values()) {
                final BlockPos neighbor = cell.relative(direction);
                if (!visited.add(neighbor) || !isCubeBlock(level, neighbor)) {
                    continue;
                }
                queue.add(neighbor);
            }
        }
        found.sort(LEXICOGRAPHIC);
        return found;
    }

    /** 该坐标是否是「已加载的能量立方方块」（只读方块状态）。 */
    private static boolean isCubeBlock(final Level level, final BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof FEEnergyCubeBlock;
    }

    private static void removeAll(final Map<Long, Snapshot> map, final Snapshot snapshot) {
        for (final BlockPos member : snapshot.members()) {
            map.remove(member.asLong());
        }
    }

    /** 取该坐标上<b>已存在</b>的能量立方方块实体（不创建、不加载区块）。 */
    @Nullable
    private static FEEnergyCubeBlockEntity cubeAt(final Level level, final BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        return be instanceof FEEnergyCubeBlockEntity cube ? cube : null;
    }
}
