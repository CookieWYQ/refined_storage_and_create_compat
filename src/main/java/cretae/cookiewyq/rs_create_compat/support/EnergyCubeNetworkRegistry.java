package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import cretae.cookiewyq.rs_create_compat.network.FEEnergyCubeNetworkNode;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 「同一张 RS 网络上所有 FE 能量立方节点」的登记表，<b>只服务于充电门控的读数口径</b>。
 *
 * <h2>为什么必须有这张表</h2>
 * <p>门控要读的是「<b>控制器电量</b>」，但 RS 没有公开「只读控制器」的 API：
 * {@link Network} 不暴露 node container 列表，{@code EnergyNetworkComponent} 只给
 * 「所有 provider 的聚合值」。于是唯一可行的等效口径是：
 * <pre>非立方存量 = 网络能量池总量 − 本网络所有能量立方节点的存量之和</pre>
 * 注意是<b>本网络所有立方</b>，不是「本集群」：同一张网络可以挂多组互不相邻的立方簇
 * （例如主基地一组、矿场一组），只减自己那一组的话，其它立方簇的电量会继续污染百分比 ——
 * 网络里立方越多，分母越大、闸越关不上（极端情况下永远充不进电）。
 *
 * <h2>登记 / 注销时机</h2>
 * <ul>
 *     <li><b>登记</b>：{@link FEEnergyCubeNetworkNode#setNetwork} 网络从无到有 / 换成另一张网络时；</li>
 *     <li><b>注销</b>：节点离开网络（{@code setNetwork(null)} / 换网）时自动注销；
 *     方块实体 {@code setRemoved()}（破坏 / 区块卸载）时再兜底注销一次 —— 防「幽灵节点」
 *     把减法做得过大，把闸永久关死；</li>
 *     <li><b>读取时自愈</b>：{@link #totalStored}/{@link #totalCapacity} 顺手剔掉
 *     「已经不在该网络上 / 方块实体已经移除」的节点，即使某条注销路径没被调到也不会长期污染读数。</li>
 * </ul>
 *
 * <h2>为什么用 {@code WeakHashMap} 仍然安全</h2>
 * <p>教科书式的坑是「值强引用键 ⇒ 键永远不被回收」。这里值里的节点<b>持有 Network 引用</b>，
 * 看似构成回路，但节点离开网络时一定先 {@code setNetwork(null)} → 注销 → 集合清空 → 键被移除；
 * 网络正常销毁时 RS 会把所有容器移除，走的正是同一条路径。再加上读取时自愈，残留最多一个
 * 「没人读的空壳」。线程模型：只在服务端主线程读写（节点 tick / 门控判定），无需并发容器。
 */
public final class EnergyCubeNetworkRegistry {
    /** 网络 → 该网络上的能量立方节点（只含主线程正在使用的那些）。 */
    private static final Map<Network, Set<FEEnergyCubeNetworkNode>> BY_NETWORK = new WeakHashMap<>();

    private EnergyCubeNetworkRegistry() {
    }

    /** 登记一个刚接入 {@code network} 的立方节点（幂等）。 */
    public static void register(@Nullable final Network network, @Nullable final FEEnergyCubeNetworkNode node) {
        if (network == null || node == null) {
            return;
        }
        BY_NETWORK.computeIfAbsent(network, ignored -> new java.util.HashSet<>()).add(node);
    }

    /** 注销一个已经离开 {@code network} 的立方节点（幂等）。 */
    public static void unregister(@Nullable final Network network, @Nullable final FEEnergyCubeNetworkNode node) {
        if (network == null || node == null) {
            return;
        }
        final Set<FEEnergyCubeNetworkNode> nodes = BY_NETWORK.get(network);
        if (nodes == null) {
            return;
        }
        nodes.remove(node);
        if (nodes.isEmpty()) {
            BY_NETWORK.remove(network);
        }
    }

    /** 该网络上所有立方节点的存量之和（<b>只统计仍然活着的节点</b>，顺手清幽灵）。 */
    public static long totalStored(@Nullable final Network network) {
        long total = 0L;
        for (final FEEnergyCubeNetworkNode node : liveNodes(network)) {
            total += node.getStored();
        }
        return total;
    }

    /** 该网络上所有立方节点的容量之和（同样只统计活着的节点）。 */
    public static long totalCapacity(@Nullable final Network network) {
        long total = 0L;
        for (final FEEnergyCubeNetworkNode node : liveNodes(network)) {
            total += node.getCapacity();
        }
        return total;
    }

    /** 该网络上目前登记在册的立方节点数（诊断 / 自检用）。 */
    public static int size(@Nullable final Network network) {
        return liveNodes(network).size();
    }

    private static Set<FEEnergyCubeNetworkNode> liveNodes(@Nullable final Network network) {
        if (network == null) {
            return Collections.emptySet();
        }
        final Set<FEEnergyCubeNetworkNode> nodes = BY_NETWORK.get(network);
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptySet();
        }
        // 自愈：节点已经换网 / 离开网络 / 方块实体已被移除 ⇒ 不是本网络的贡献，剔掉
        nodes.removeIf(node -> node.getNetwork() != network || !node.isAlive());
        if (nodes.isEmpty()) {
            BY_NETWORK.remove(network);
            return Collections.emptySet();
        }
        return nodes;
    }

    /** 诊断用：当前有多少张网络登记了立方（自检 / devlog）。 */
    public static int trackedNetworks() {
        return new HashMap<>(BY_NETWORK).size();
    }
}
