package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;

import javax.annotation.Nullable;

/**
 * 「网络资源请求器」的 RS 网络节点。
 *
 * <h2>它存在的唯一理由</h2>
 * <p>请求器要从<b>自己所在的那个网络</b>里取料（设计文档 L8：与 Create 的红石请求器语义一致 ——
 * 「看自己所在网络的库存」），而「自己所在的网络」只有接入网络图的节点才拿得到。
 * 同时「按名字找接收器」也必须枚举<b>同一个 {@link Network} 对象</b>的容器集合，
 * 因此它和接收器一样必须是网络节点。</p>
 *
 * <h2>本类不做每 tick 逻辑；节流与投递都在方块实体上</h2>
 * <p>请求动作是「<b>红石上升沿 + 界面按钮</b>」两种显式触发，不是周期性工作，
 * 因此这里刻意<b>不</b>覆写 {@code doWork()}（父类只负责按 {@link #getEnergyUsage()} 抽电）。
 * 「一次触发多快投完」由方块实体的 {@code NetworkResourceRequesterBlockEntity#triggerRequest()}
 * → {@code throttleTick()}（闸门每 tick 放行）→ 升级容器
 * {@code RsccSpeedStackUpgradeContainer#throttleBatchesPerTick()} / {@code #throttleBatch()}
 * 承担：速度升级 = 每 tick 几批、叠加升级 = 每批多少，两处都经统一工具
 * {@link cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter} 换算
 * （也<b>绝不用</b> RS 的 {@code UpgradeContainer} ticker ——
 * 它的 {@code 9 - 2n} 在 6 个速度升级时是负数）。</p>
 */
public class NetworkResourceRequesterNetworkNode extends AbstractNetworkNode {
    @Nullable
    private NetworkResourceRequesterBlockEntity blockEntity;

    public void setBlockEntity(final NetworkResourceRequesterBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public NetworkResourceRequesterBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }

    /** 本机耗电（FE/t）：与接收器同一口径，由方块实体给出。 */
    @Override
    public long getEnergyUsage() {
        return blockEntity == null ? 2 : blockEntity.getEnergyUsage();
    }
}
