package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageProvider;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceListImpl;
import com.refinedmods.refinedstorage.api.storage.Storage;
import cretae.cookiewyq.rs_create_compat.support.RsccCacheExposedStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccIntermediateOnlyStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccSharedCache;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「中间产物缓存仓」的 RS 网络节点。
 *
 * <h2>它为什么必须是一个网络节点</h2>
 * 缓存仓要接进 RS 的网络图（这样它才需要「用线缆接进网络」才能被看见，而 RS 的网络图连边本身已经过
 * 「机械动力扳手断开线缆」与「分隔框架阻断」两道闸门，见 {@code mixin/network/InWorldNetworkNodeContainerImplMixin}）。
 * <b>这是本工程唯一的连接判定</b>，本模组不再写第二套「谁能连谁」的规则。
 *
 * <h2><b>2026-10-05 重大修正：它现在是一个真正的网络存储源</b></h2>
 *
 * <p><b>修正前</b>：本类只抽电，缓存盘<b>从不注册进网络</b> —— 缓存仓其实是「网络之外的一个孤岛池」。
 * 由此产生三个必然结果，正是用户实测到的现象：</p>
 * <ol>
 *     <li><b>终端里看不见</b>缓存盘里的东西（终端只显示 {@code StorageNetworkComponent} 的内容）；</li>
 *     <li><b>没有任何机制</b>会让中间产物「优先」落进缓存盘（RS 的插入只在源列表内分配）；</li>
 *     <li>只能靠手写的搬运循环 —— 而它又被模组自己的回收路径反向抽干
 *     （缓存盘被 {@code RsccChamberItemStorage} 伪装成执行舱的槽位）。</li>
 * </ol>
 *
 * <p><b>修正后</b>：本类实现 {@link StorageProvider}，把盘位里的每块盘包成
 * {@link RsccIntermediateOnlyStorage}（只收中间产物）再交给
 * {@link RsccCacheExposedStorage}（「插入优先级 +1000 / 抽出优先级 −1000」<b>就挂在这个
 * 注册进网络的对象上</b>，见该类注释：优先级只有在网络根复合的直接子源那一层才有意义）
 * 并暴露给网络。
 * 于是三件事<b>全部由 RS 官方机制自动完成</b>，本模组不再需要任何搬运代码：</p>
 * <ul>
 *     <li>「终端里照样看得见」→ 成为网络源即可（{@code StorageNetworkComponentImpl#onContainerAdded}）；</li>
 *     <li>「优先把所有中间产物自动转移进去」→ {@code PriorityProvider#getInsertPriority()} 大的先插；</li>
 *     <li>「空闲时主动流向缓存仓」→ 同理，新产出 / 回流一进网络就按优先级落进缓存盘。</li>
 * </ul>
 *
 * <p>磁盘驱动器就是这么做的（{@code StorageNetworkNode implements StorageProvider}），本类与它同构，
 * 只是多了一层「按资源的过滤」与优先级。</p>
 */
public class IntermediateCacheNetworkNode extends AbstractNetworkNode implements StorageProvider {
    /**
     * 本节点暴露给网络的存储 = 盘位里各块盘的复合。
     * <p>盘位变化时<b>只更新这个实例的源</b>（{@code addSource}/{@code removeSource}），
     * 不重新注册容器 —— 与 RS 自己的磁盘驱动器同一套做法。</p>
     */
    private final RsccCacheExposedStorage exposed =
        new RsccCacheExposedStorage(MutableResourceListImpl.create());

    @Nullable
    private IntermediateCacheBlockEntity blockEntity;

    public void setBlockEntity(final IntermediateCacheBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public IntermediateCacheBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Nullable
    public Network getNetworkOrNull() {
        return network;
    }

    /** 暴露给网络的存储（RS 在容器加入网络时调用一次，见 {@code StorageNetworkComponentImpl}）。 */
    @Override
    public Storage getStorage() {
        return exposed;
    }

    /**
     * <b>盘位变化时重建源列表</b>（插入 / 取出 / 换盘 / 区块加载完成时调用）。
     *
     * <p>做法：清空后按 {@link RsccSharedCache} 解析出的每块盘重新包一层。
     * 因为 {@code getStorage()} 返回的是<b>同一个</b> {@link #exposed} 实例，
     * RS 那边持有的引用始终有效，因此不需要重新注册容器。</p>
     *
     * @param disks 已解析成功的盘存储（由 {@code IntermediateCacheBlockEntity#appendPoolStorages} 产出）
     */
    public void refreshSources(final List<? extends Storage> disks) {
        exposed.clearDisks();
        int added = 0;
        for (final Storage disk : disks) {
            if (disk != null) {
                exposed.addDisk(disk);
                added++;
            }
        }
        // <b>取证日志（2026-10-05）</b>：这一条是判断「网络源到底注册成功没有」的唯一凭据。
        // 快照只告诉我们「盘在线但存量 0」，到底是 refreshSources 没被调用、
        // 还是被调用了但 added=0、还是注册了却没生效 —— 三者只能靠这行区分。
        //
        // 2026-10-06：改由 devLogs 总开关控制（原条件是「added > 0 就无条件打」，等于开关关掉后
        // 每次网络源刷新仍会刷 —— 那是开发诊断，不是玩家需要知道的失败）。
        if (RsccAssemblyDebug.isEnabled()) {
            org.slf4j.LoggerFactory.getLogger("rs_create_compat/cache-source").info(
                "[rscc-cache-source] refreshSources disks={} added={} exposedStored={}",
                disks.size(), added, exposed.getStored());
        }
    }

    @Override
    public long getEnergyUsage() {
        return blockEntity == null ? 2 : blockEntity.getEnergyUsage();
    }

    @Override
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());
    }
}
