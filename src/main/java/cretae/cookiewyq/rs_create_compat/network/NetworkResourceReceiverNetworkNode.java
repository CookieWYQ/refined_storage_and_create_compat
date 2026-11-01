package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;

import javax.annotation.Nullable;

/**
 * 「网络资源接收器」的 RS 网络节点。
 *
 * <h2>为什么必须有它（而不是让方块自己去找网络）</h2>
 * <p>RS 的网络是<b>由网络图（{@code GraphNetworkComponent}）定义</b>的，只有作为
 * {@code NetworkNodeContainer} 接入的方块才会出现在网络图里。因此：
 * <ul>
 *     <li><b>「必须用线缆接进网络」这条语义</b>由 RS 的网络图全权提供 —— 机械动力扳手断开线缆、
 *     分隔框架阻断，两道闸门都已由 RS 网络图自己实现
 *     （见 {@code mixin/network/InWorldNetworkNodeContainerImplMixin}），本模组<b>不再写第二套连接判定</b>；</li>
 *     <li><b>「同网络唯一」的名字作用域</b>必须落在「同一个 {@link Network} 对象」上
 *     （设计文档 §4.1：不同维度 / 不同子网络天然是不同的 {@code Network} 实例），
 *     而拿到 {@code Network} 的唯一途径就是本节点。</li>
 * </ul>
 *
 * <h2>节流与搬运都在方块实体上</h2>
 * <p>本类<b>不覆写 {@link #doWork()}</b>：父类实现已经把「按 {@link #getEnergyUsage()} 从网络抽电」
 * 做完了，足够让方块在网络上「活着」（贴图 {@code active} 亮起、可被枚举）。真正的搬运
 * （缓存 → 朝向容器）在方块实体的 {@code NetworkResourceReceiverBlockEntity#doWork()} 里。</p>
 * <p><b>统一节流入口（{@code support/RsccRateLimiter}）</b>：
 * {@code doWork()} → {@code throttleTick()}（闸门每 tick 放行）→ 升级容器
 * {@code RsccSpeedStackUpgradeContainer#throttleBatchesPerTick()} / {@code #throttleBatch()}：
 * 速度升级 = 每 tick 几批、叠加升级 = 每批多少，两处都经该工具换算，<b>一处都没有自写公式</b>
 * （4 条不变量见 {@link cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter} 的类文档）。</p>
 * <p><b>绝不用 RS 的 {@code UpgradeContainer} 当 ticker</b>：它的 {@code workTickRate = 9 - 2n}
 * 在本工程的 6 个速度升级下算成 {@code -3}（负节拍），因此节拍一律走
 * {@link cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter}。</p>
 */
public class NetworkResourceReceiverNetworkNode extends AbstractNetworkNode {
    @Nullable
    private NetworkResourceReceiverBlockEntity blockEntity;

    public void setBlockEntity(final NetworkResourceReceiverBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public NetworkResourceReceiverBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }

    /**
     * 本机耗电（FE/t）。
     * <p>取一个与「纯缓存 + 转发」这个角色相称的低值，避免玩家为了一个落地点付出与执行舱同级的电费；
     * 具体数值由方块实体统一给出（便于以后按升级调整时只改一处）。</p>
     */
    @Override
    public long getEnergyUsage() {
        return blockEntity == null ? 2 : blockEntity.getEnergyUsage();
    }
}
