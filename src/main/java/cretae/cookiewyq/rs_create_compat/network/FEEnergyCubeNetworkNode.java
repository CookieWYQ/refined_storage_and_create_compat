package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyProvider;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.EnergyCubeNetworkRegistry;

import javax.annotation.Nullable;

/**
 * FE 能量立方的 RS 网络节点：把自己当成网络能量池里的一个 {@link EnergyProvider}（备用电池）。
 *
 * <h2>为什么把它注册成 EnergyProvider 就能「低优先级」—— 以及第 106 轮为什么还要再加一道</h2>
 * <p>RS 2.0.9 的 {@code EnergyNetworkComponentImpl} 用 {@link java.util.LinkedHashSet} 按<b>容器加入网络的
 * 顺序</b>保存 provider：{@code getStored / getCapacity} 逐 provider 求和，{@code extract} 从<b>头</b>开始
 * 逐个抽到够为止。控制器按正常玩法（先有控制器、后放立方）排在它后面 ⇒ 网络耗能时<b>先把控制器抽干，
 * 最后才轮到立方</b>。
 * <p><b>但插入顺序不可靠</b>：{@code NetworkBuilderImpl} 重建网络时按<b>方块坐标</b>遍历
 * （{@code ConnectionProviderImpl#sortDeterministically}），玩家「先放立方、后放控制器」时立方会排在
 * 控制器前面。因此第 106 轮在 {@link #extract(long)} 里主动「让位」：只要非立方 provider 还有电，
 * 本节点只补差额 ⇒ <b>「先控制器、后立方」变成与顺序无关的事实</b>（见该方法注释）。
 * 本模组仍然<b>不实现任何优先级字段</b>（RS 的 {@code EnergyProvider} 也没有这个字段）。
 *
 * <h2>「从网络灌电」到底是从谁身上抽</h2>
 * <p>同一段代码的副作用要写清楚：{@code doWork} 里 {@code extract(request)} 遍历到<b>第一个有电的
 * provider</b> 就抽 —— 正常情况下就是控制器。所以「门控放行时把电存进立方」在实现上
 * <b>就是直接从控制器（网络能量池）里抽</b>，不存在「富余电量」这种单独的对象；门控只决定
 * 「现在允不允许抽」—— 而门控是<b>两条</b>：控制器 ≥ 阈值 <b>且</b> 网络电量正在上涨
 * （见 {@code FEEnergyCubeBlockEntity} 的类注释）。这也是为什么门控读数必须扣掉本网络所有立方
 * （见 {@link EnergyCubeNetworkRegistry} 的类注释）：否则立方自己的电量会把「控制器电量」算高。
 *
 * <h2>口径（与方块实体的能力视图<b>故意不同</b>，改之前先读这段）</h2>
 * <ul>
 *     <li><b>节点只报「本块」</b>：{@link #getStored()} / {@link #getCapacity()} 恒等于本块自己的
 *     存量 / 容量。网络组件把它们<b>逐块求和</b>，于是网络池里的容量 = Σ 本集群各块 = N×50M ——
 *     求和这件事由网络组件做，节点再去报集群值就会<b>平方级重复计数</b>；</li>
 *     <li><b>六面 FE 能力视图报「集群」</b>（{@code FEEnergyCubeBlockEntity#getExternalEnergyStorage}）：
 *     外部机器从任意一面的任意一块抽电，看到的就是整块电池。两套口径各自成立，不要合并。</li>
 * </ul>
 *
 * <h2>放电不按 {@code feEnergyCubeMaxTransfer} 限速</h2>
 * <p>那个配置是「六个面的 FE 输入 / 输出速率」，管的是<b>能力接口</b>那一侧；
 * 网络池放电是 RS 内部的能量扣减（{@code long} 口径、每 tick 多台机器各抽各的），
 * 若在这里再按 10 万截断，备用电池会在网络缺电时变成「只能供 10 万 FE/t 的瓶颈」，
 * 与「容量共享、从整体取电」的语义矛盾。因此 {@link #extract(long)} 只受「集群当前存量」约束。
 */
public class FEEnergyCubeNetworkNode extends AbstractNetworkNode implements EnergyProvider {
    @Nullable
    private FEEnergyCubeBlockEntity blockEntity;

    public void setBlockEntity(final FEEnergyCubeBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    /**
     * 网络变更钩子（RS 的容器在加入 / 离开网络时调用）。<b>这里同时维护门控口径的登记表</b>：
     * 离开旧网络先注销、加入新网络再登记 —— 这样任何一张网络上的「立方贡献」永远等于实际挂着的节点。
     * <p><b>还要作废趋势采样</b>：换网络后旧基线（上一个网络的能量池总量）与新网络无关，沿用它会
     * 把「两个网络之间的差值」当成「这一 tick 在涨/在掉」，门控会在接入的瞬间误判一票。
     */
    @Override
    public void setNetwork(@Nullable final Network network) {
        final Network previous = getNetwork();
        if (previous == network) {
            return;
        }
        EnergyCubeNetworkRegistry.unregister(previous, this);
        super.setNetwork(network);
        EnergyCubeNetworkRegistry.register(network, this);
        if (blockEntity != null) {
            blockEntity.resetChargeTrend();
        }
    }

    /**
     * 节点是否仍然「活着」：方块实体存在、仍在世界里有 level、且没有被移除。
     * <p>给 {@link EnergyCubeNetworkRegistry} 清理幽灵节点用（例如某条注销路径没被调到，
     * 残留节点会让「非立方存量 = 总量 − 立方」减得过头，把门控永久关死）。</p>
     */
    public boolean isAlive() {
        return blockEntity != null && blockEntity.getLevel() != null && !blockEntity.isRemoved();
    }

    /** 方块实体被移除时的兜底注销（幂等；正常路径已经在 {@link #setNetwork} 里注销过）。 */
    public void unregisterFromNetwork() {
        EnergyCubeNetworkRegistry.unregister(getNetwork(), this);
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }

    @Override
    public long getStored() {
        return blockEntity == null ? 0L : blockEntity.getOwnEnergyStored();
    }

    @Override
    public long getCapacity() {
        return blockEntity == null ? 0L : blockEntity.getOwnCapacity();
    }

    /**
     * 网络耗能时被 {@code EnergyNetworkComponentImpl#extract} 调用：从<b>整个集群</b>扣电
     * （按坐标字典序从最小成员开始），返回真实扣掉的量。
     *
     * <h2>第 106 轮：「立方永远最后被抽」（不再依赖 provider 插入顺序）</h2>
     * <p>RS 2.0.9 的 {@code EnergyNetworkComponentImpl} 用 {@code LinkedHashSet<EnergyProvider>}
     * 按<b>容器加入网络的顺序</b>遍历、{@code extract} 从头抽到够为止。而
     * {@code NetworkBuilderImpl} 在重建网络时是按<b>方块坐标</b>（{@code sortDeterministically}）
     * 逐个 {@code addContainer} 的 —— 玩家「先放立方、后放控制器」时，立方会排在控制器<b>前面</b>，
     * 于是网络耗能会先抽立方、把「最低优先级」直接反过来。
     * <p>因此这里主动「让位」：只要同一张网络上<b>非立方 provider 还有存量</b>，本节点只补
     * {@code max(0, amount - 非立方存量)} 的差额，把「先抽谁」变成与插入顺序无关的事实。
     * 差额由 {@code EnergyCubeNetworkRegistry} 的登记表算出（与门控读数同一口径，
     * 见 {@link EnergyCubeNetworkRegistry} 类注释）：{@code 非立方存量 = 网络池总量 − 本网络所有立方}。
     * <p><b>为什么不是「非立方还有 1 FE 就完全不给」</b>：那样在「非立方不够付」时会让下游少拿电
     * （总量够、却只送出非立方那部分）。补差额的写法既保证「非立方先出」，也保证
     * 「非立方付不起的部分立方一定补上」，总送出量不打折。
     *
     * <h2>第 106 轮：自己抽自己 = 0</h2>
     * <p>{@code FEEnergyCubeBlockEntity#onNetworkTick} 从网络池抽电灌进立方时，这个遍历也会走到本节点。
     * 那种情况下返回 0（{@code isPullingFromNetwork()}）：否则「从控制器抽到立方」会退化成
     * 「把立方自己的电搬出来再搬回去」，还会给趋势采样记一笔假的「抽了电」。
     *
     * <p>区块卸载 / 客户端侧 / 方块实体已被移除时返回 0（绝不写别人的世界）。
     */
    @Override
    public long extract(final long amount) {
        if (blockEntity == null || amount <= 0L) {
            return 0L;
        }
        if (blockEntity.isPullingFromNetwork()) {
            return 0L;
        }
        long want = amount;
        if (network != null) {
            final long nonCubeStored = Math.max(0L, network.getComponent(EnergyNetworkComponent.class).getStored()
                - EnergyCubeNetworkRegistry.totalStored(network));
            want = Math.max(0L, amount - nonCubeStored);
        }
        if (want <= 0L) {
            return 0L;
        }
        return blockEntity.dischargeToNetwork(want);
    }

    @Override
    public long getEnergyUsage() {
        // 本节点不消耗能量：它只在 doWork 里按门控「主动从网络池买电」，用量恒为 0。
        // （RS 基类的 AbstractNetworkNode#doWork 会 extract(getEnergyUsage())，所以这里必须显式覆写 doWork。）
        return 0L;
    }

    @Override
    public void doWork() {
        // 不调用父类：父类只会 extract(getEnergyUsage()) = extract(0)，是空操作。
        // ① 第 106 轮：先做「相邻主动供能」——**与是否接入 RS 网络无关**
        //    （没接控制器的立方依然是一块电池，贴着范围充电器就该给它供电）。
        //    注意不能放在 `network == null` 的提前返回之后：那样「没接网络就永远不推电」，
        //    而用户恰恰是在「一大堆放立方 + 紧贴一个范围充电器」的场景里反馈的。
        // ② 再走「从网络池抽电灌进立方」的门控路径（唯一被门控的路径）。
        if (blockEntity == null) {
            return;
        }
        blockEntity.tickNeighbourPush();
        if (network == null) {
            return;
        }
        blockEntity.onNetworkTick(network);
    }
}
