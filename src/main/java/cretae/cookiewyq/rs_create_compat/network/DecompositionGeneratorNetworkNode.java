package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.energy.EnergyProvider;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.DecompositionGeneratorBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 降解室发电机的 RS 网络节点：把自己当成网络能量池里的一个 {@link EnergyProvider}（<b>发电方</b>）。
 *
 * <h2>只报「本块」，绝不自报集群</h2>
 * <p>{@code EnergyNetworkComponentImpl} 会把网络里所有 provider 的 {@code getStored/getCapacity}
 * <b>逐个求和</b>，所以节点必须只报本块自己的存量/容量；报「整张网」或「本机 × N」都会重复计数。
 * 本方块没有集群概念，因此这条天然满足。</p>
 *
 * <h2>「为网络供能」在 RS 里到底意味着什么（如实说明）</h2>
 * <p>RS 2.0.9 的能量池是一个 {@code LinkedHashSet<EnergyProvider>}：{@code getStored} 求和、
 * {@code extract} 从<b>加入顺序最早</b>的 provider 开始抽。控制器是最早加入的，本节点按正常玩法排在它后面
 * ⇒ 网络耗能时<b>先抽控制器，控制器空了才轮到本机</b>。也就是说：</p>
 * <ul>
 *     <li><b>本机不会给控制器「回充」</b>（RS 没有「provider 之间转账」这种操作，控制器也不是可被写入的容器）；
 *     它做的是「把电放进网络能量池」；</li>
 *     <li>玩家看到的效果是：控制器电量见底时网络还能继续跑，跑的电来自本机 —— 这就是 RS 语义下的
 *     「为网络供能」；</li>
 *     <li>想让控制器自己充上电，用<b>相邻直供</b>那条路：控制器方块确实注册了
 *     {@code Capabilities.EnergyStorage.BLOCK}（refinedstorage 的 {@code ModInitializer} 里
 *     {@code BlockEntities.INSTANCE.getController()} → {@code EnergyStorageAdapter}），
 *     本机每 tick 往 6 面邻居推电时就会充进去。</li>
 * </ul>
 *
 * <h2>三条供能路径互不重复扣电</h2>
 * <ol>
 *     <li>RS 网络：本类 {@link #extract(long)} → 方块实体扣<b>本机缓存</b>（唯一记账点）；</li>
 *     <li>相邻六面：方块实体每 tick 推送，同样从<b>本机缓存</b>扣；</li>
 *     <li>自身缓存落盘：只是持久化，不产生也不销毁电。</li>
 * </ol>
 * <p>前两条都走同一份 {@code energyStorage.extractEnergy(...)}，不存在「记两次账」。</p>
 *
 * <p>本节点<b>不消耗</b>网络能量（{@link #getEnergyUsage()} 恒 0），所以也不会反向从网络抽电。</p>
 */
public class DecompositionGeneratorNetworkNode extends AbstractNetworkNode implements EnergyProvider {
    @Nullable
    private DecompositionGeneratorBlockEntity blockEntity;

    /** 由方块实体构造期绑定（唯一的反向引用）。 */
    public void setBlockEntity(final DecompositionGeneratorBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public DecompositionGeneratorBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public long getStored() {
        return blockEntity == null ? 0L : blockEntity.getEnergyStored();
    }

    @Override
    public long getCapacity() {
        return blockEntity == null ? 0L : blockEntity.getEnergyCapacity();
    }

    /**
     * 网络耗能时被 {@code EnergyNetworkComponentImpl#extract} 调用：从本机缓存扣电。
     * <p>区块卸载 / 客户端侧 / 方块实体已移除时返回 0（<b>绝不写别人的世界</b>）。</p>
     */
    @Override
    public long extract(final long amount) {
        if (blockEntity == null || amount <= 0L) {
            return 0L;
        }
        return blockEntity.extractForNetwork(amount);
    }

    @Override
    public long getEnergyUsage() {
        // 发电机不耗电：它只输出。父类 AbstractNetworkNode#doWork 会 extract(getEnergyUsage())，
        // 所以下面必须显式覆写 doWork（否则每 tick 都是 extract(0) 的空转）。
        return 0L;
    }

    @Override
    public void doWork() {
        // 不调用父类：本节点不是「消费者」，没有固定能耗要扣。
        // 机器的每 tick 逻辑（降解 + 相邻推送 + 状态刷新）统一由方块实体承担，
        // 这样「有没有接进网络」不影响它独自发电（没接网络时也照常工作）。
        if (blockEntity == null) {
            return;
        }
        blockEntity.serverTick();
    }
}
