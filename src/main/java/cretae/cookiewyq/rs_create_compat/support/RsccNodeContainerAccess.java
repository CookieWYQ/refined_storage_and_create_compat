package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import org.jetbrains.annotations.Nullable;

/**
 * 桥接接口：由 Mixin 注入到 RS 的
 * {@code AbstractNetworkNodeContainerBlockEntity}（所有 RS 网络节点方块实体的公共基类，**声明
 * {@code mainNetworkNode} 字段的那个类**）上，用于从任意方块实体拿到它的网络节点、进而拿到
 * {@link com.refinedmods.refinedstorage.api.network.Network}。
 *
 * <p><b>为什么需要它</b>：本模组要观察的不再只是自家的序列装配样板库 —— RS 原版自动合成任务
 * （自动合成器的任务）也必须能挂起 / 恢复，而扫描入口原本只认
 * {@code SequenceAssemblyExecutorBlockEntity}，纯 RS 网络根本进不来。有了本接口，任何网络节点
 * 方块实体（自动合成器、线缆、控制器……）都能当扫描入口。</p>
 *
 * <p><b>为什么接口放在 support 包</b>：与本工程 {@link RsccAssemblyMonitorBridge} 同一条硬规则 ——
 * mixin 包里的类不能被普通代码直接引用（否则 IllegalClassLoadError），普通代码只引用本接口；
 * {@code @Accessor} 的实现挂在 mixin/accessor 包。</p>
 */
public interface RsccNodeContainerAccess {
    /** 该方块实体的「主网络节点」（字段 {@code mainNetworkNode}，泛型擦除后为 {@link NetworkNode}）。 */
    @Nullable
    NetworkNode rscc$mainNetworkNode();
}
