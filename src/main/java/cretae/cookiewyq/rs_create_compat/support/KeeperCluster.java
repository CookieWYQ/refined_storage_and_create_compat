package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 同一 RS 网络内多台定量保持器的「确定性仲裁」。
 *
 * <p><b>为什么需要它</b>：每台保持器只认「网络里该资源的<b>总量</b>」，因此多台都标同一个资源时必然互相打架 ——
 * 目标小的那台不停地销毁，目标大的那台又不停地自动合成补货，形成「销毁 ↔ 合成」的永久拉锯；
 * 到底谁说了算取决于本轮 tick 的先后（典型的不稳定行为，即「后装的赢」）。本类给出一条
 * <b>与运行顺序、放置顺序完全无关</b>的规则：同一个<b>资源键</b>只有一台「权威」保持器可以合成 / 销毁，
 * 其余同键保持器只做搬运（把本机同类存储回流进网络），不销毁也不合成。</p>
 *
 * <p><b>资源键维度</b>：物品（{@code ItemResource}）与流体 / 气体（{@code FluidResource}）
 * 是两种永不相等（{@code equals} 也不同）的键，且都带上各自的数据组件补丁。所以
 * 「一台管物品、一台管流体」天然互不干扰，可以同时生效；只有<b>真正同一个键</b>（同物品同组件 /
 * 同流体同组件）才会进入仲裁。</p>
 *
 * <p><b>权威者判据（全序、可复现）</b>：先比方块坐标（x → y → z 字典序，小的优先），
 * 再比机器类型（基础版优先于高级版），最后比节点类名。三段都相同（同一坐标不可能同放两台）时双方都自认权威。</p>
 *
 * <p><b>键必须同源</b>：仲裁用的资源键与「实际读写用的键」都由方块实体同一个
 * {@code networkResourceKey} 提供，避免「入网用键」与「销毁用键」不一致而销毁落空。</p>
 */
public final class KeeperCluster {
    /** 参与仲裁的保持器网络节点（基础版 / 高级版各实现一次；节点就在 RS 的网络图里）。 */
    public interface Node {
        /** 本机当前声称控制的所有资源键（空槽 / 未标记 / 无法解析一律不计入）。 */
        List<ResourceKey> claimedResources();

        /** 仲裁用坐标（方块坐标；尚未绑定方块实体时返回 {@link BlockPos#ZERO}）。 */
        BlockPos arbitrationPos();

        /** 坐标相同时的次级判据：数值小的优先（0 = 基础版，1 = 高级版）。 */
        int arbitrationRank();
    }

    private KeeperCluster() {
    }

    /**
     * 本机<b>应当让出</b>（= 不合成 / 不销毁，只搬运）的资源键集合。
     * <p>网络图不可枚举时返回空集 —— 退化为「自己负责自己」，绝不因为查不到同伴而整体停工。</p>
     */
    public static Set<ResourceKey> yieldedResources(@Nullable final Network network, final Node self) {
        final List<ResourceKey> mine = self.claimedResources();
        if (mine.isEmpty() || network == null) {
            return Set.of();
        }
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        if (graph == null) {
            return Set.of();
        }
        final Set<ResourceKey> mineSet = new HashSet<>(mine);
        final Set<ResourceKey> yielded = new HashSet<>();
        for (final NetworkNodeContainer container : graph.getContainers()) {
            if (container.getNode() == self || !(container.getNode() instanceof Node peer)) {
                continue;
            }
            if (!outranks(peer, self)) {
                continue;
            }
            for (final ResourceKey key : peer.claimedResources()) {
                if (mineSet.contains(key)) {
                    yielded.add(key);
                }
            }
        }
        return yielded.isEmpty() ? Set.of() : Set.copyOf(yielded);
    }

    /** {@code a} 是否比 {@code b} 更权威（全序：坐标 → 机器类型 → 类名）。 */
    private static boolean outranks(final Node a, final Node b) {
        final int byPosition = comparePosition(a.arbitrationPos(), b.arbitrationPos());
        if (byPosition != 0) {
            return byPosition < 0;
        }
        if (a.arbitrationRank() != b.arbitrationRank()) {
            return a.arbitrationRank() < b.arbitrationRank();
        }
        return a.getClass().getName().compareTo(b.getClass().getName()) < 0;
    }

    /** 方块坐标全序：x → y → z 字典序（与放置顺序无关，读档后也完全一致）。 */
    private static int comparePosition(final BlockPos a, final BlockPos b) {
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }
}
