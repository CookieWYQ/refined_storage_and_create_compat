package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.IntermediateCacheNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.RsccSharedCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.List;

/**
 * 「中间产物缓存仓」方块实体：提供 {@value #DISK_SLOTS} 格磁盘槽，作为<b>网络内所有序列装配执行舱共享</b>的
 * 中间产物临时储存点（用户决定：「那个缓存的磁盘应该是通用的，他只做一个所有这种的临时储存点而已」）。
 *
 * <h2>它做什么 / 不做什么</h2>
 * <ul>
 *     <li><b>只有盘位，没有自带容量</b>（第 7 轮按用户要求改回）：用户原话是
 *     「我的意思<b>不是说它自带容量多少多少</b>，我的意思是说<b>有一个小箱子容量可以用来存放磁盘</b>」，
 *     所以本仓<b>不再</b>自带 1728 件物品容量，而是给一份「<b>一个小箱子大小的磁盘存放空间</b>」=
 *     {@value #DISK_SLOTS} 格盘位（= 一个小箱子的槽数，3 行 × 9 列）。真正的物品容量仍然
 *     <b>完全由插进来的磁盘提供</b>：盘里的东西存在磁盘自己的 RS 存储里
 *     （{@code StorageRepository} 按磁盘物品携带的 UUID 指认），因此「取盘即带走盘内物品」天然成立，
 *     本仓永远不需要搬动、拆解或复制盘里的内容；</li>
 *     <li><b>不做网络存储</b>：本仓盘位里的盘<b>不会</b>被注册成 RS 网络存储 ——
 *     否则网络里凭空多出一份存储，会与执行舱「中间产物先进缓存、再按需取用」的语义打架；
 *     池子只通过 {@link RsccSharedCache} 供执行舱使用；</li>
 *     <li><b>不参与机器集群</b>：多台缓存仓本来就是合成<b>同一个池子</b>（见 {@link RsccSharedCache}
 *     的合并顺序），再让它们做「相邻容量叠加」只会重复计算同一批盘。</li>
 * </ul>
 *
 * <h2>为什么是 27 格盘位（{@value #DISK_SLOTS}）</h2>
 * 用户明确要求「一个小箱子容量可以用来存放磁盘」，而一个小箱子（单箱）就是 27 格 ——
 * 于是盘位数取 27（3 行 × 9 列，正好与配置界面的三行盘位同构）。这样：
 * <ul>
 *     <li><b>够用</b>：一块主盘（64M 级）应付绝大多数订单，余下的格子留给「按配方类型分盘」
 *     / 「多台机器各自一块」/「临时扩容」这些真实用法；</li>
 *     <li><b>语义与用户口径一致</b>：既不是「方块自带容量」，也不是「只有一个盘位」，
 *     而是「有一个小箱子那么大的地方专门放磁盘」；</li>
 *     <li><b>数据面不再翻倍</b>：池子是网络级共享的，第二个玩家想「多放点盘」直接贴第二台仓即可
 *     （多台自动合成一个池子）。</li>
 * </ul>
 *
 * <h2>守恒</h2>
 * 磁盘物品本身随方块 NBT 持久化；破坏方块时按本模组统一的「内容物去向」策略结算
 * （见 {@code IntermediateCacheBlock#onRemove}）：磁盘（连同盘内物品）随盘一起走 —— 绝不复制 / 销毁。
 */
public class IntermediateCacheBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<IntermediateCacheNetworkNode> {
    /**
     * 盘位数 = <b>一个小箱子（单箱）的槽数</b>（3 行 × 9 列 = 27）。
     * <p>用户要求：「有一个小箱子容量可以用来存放磁盘」—— 本仓提供的就是这么一份
     * <b>磁盘存放空间</b>，而不是方块自带的物品容量（容量由盘提供）。
     * 界面上按 3 行 × 9 列排布（见 {@code IntermediateCacheMenu}），文案见
     * {@code block.rs_create_compat.intermediate_cache.help} 与
     * {@code gui.rs_create_compat.intermediate_cache.tip.slots}。</p>
     */
    public static final int DISK_SLOTS = 27;

    /** 盘位容器：只接受 RS 存储磁盘（判定见 {@link RsccSharedCache#isStorageDisk}）。 */
    public final SimpleContainer diskSlots = new SimpleContainer(DISK_SLOTS);

    /** 「主动搬运中间产物」的节流：每 {@link #FLOW_INTERVAL_TICKS} tick 跑一轮。 */
    private static final int FLOW_INTERVAL_TICKS = 20;
    private int flowCooldown;

    public IntermediateCacheBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.INTERMEDIATE_CACHE_BLOCK_ENTITY.get(), pos, state,
            new IntermediateCacheNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        // 玩家换盘 / 取出盘 → 立刻作废共享池缓存（网络内所有执行舱下一次访问就看到新的一组盘）
        this.diskSlots.addListener(container -> onDiskSlotsChanged());
    }

    /**
     * <b>主动把网络里的中间产物搬进本仓（及全网络缓存仓）的磁盘</b> —— 用户原话：
     * <i>「不管什么时候也不管哪里来的中间产物都是这样子……我应该过一会可以看到磁盘中它占有的容量正在逐渐上涨」</i>。
     *
     * <p>为什么挂在缓存仓自己的 tick 上：搬运的「目标容器」就是缓存仓的磁盘，
     * 由它自己驱动最自然，也避免给执行舱再添一份每 tick 逻辑。
     * 任意一台缓存仓在跑就够（{@link RsccIntermediateFlow} 处理的是<b>整个网络</b>，
     * 而共享池本来就是多台缓存仓合并而成），因此不需要「选出一台主仓」的额外协调。</p>
     *
     * <p>开销：每 {@value #FLOW_INTERVAL_TICKS} tick 一次、单次至多
     * {@link RsccIntermediateFlow#BATCH} 件；池子不可用（没插盘）时会立刻返回 0。</p>
     */
    /**
     * <b>每 tick 钩子</b>（RS 的 {@code doWork()} 就是节点被 tick 的入口，见
     * {@code AbstractBaseNetworkNodeContainerBlockEntity#doWork()} ⇒ {@code ticker.tick(node)}）。
     *
     * <p>这里在跑完父类既有逻辑之后，按 {@value #FLOW_INTERVAL_TICKS} tick 的节流做一轮
     * 「把网络里的中间产物搬进缓存仓磁盘」。开销：每 20 tick 一次、单次至多
     * {@link cretae.cookiewyq.rs_create_compat.support.RsccIntermediateFlow#BATCH} 件；
     * 池子不可用（没插盘 / 没有缓存仓）时立即返回 0。</p>
     */
    @Override
    public void doWork() {
        super.doWork();
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (--flowCooldown > 0) {
            return;
        }
        flowCooldown = FLOW_INTERVAL_TICKS;
        // <b>存量迁移</b>（用户原话：「就算没有新产生的中间产物，网络中自带的这种中间产物
        // 也应该自己到那一个地方去」）。
        //
        // 为什么需要它：RS 的插入优先级<b>只作用于「新插入」</b> —— 一件中间产物如果已经在
        // 磁盘驱动器 / 外部存储里，RS 不会把它挪到缓存盘。因此必须由本模组做一轮迁移。
        //
        // 为什么现在安全（前两版失败的原因都已消除）：
        //   * 缓存盘已是<b>真正的网络源</b>（{@code IntermediateCacheNetworkNode} 暴露
        //     {@code RsccCacheExposedStorage implements CompositeAwareChild}，RS 会递归进它）；
        //   * {@code RsccChamberItemStorage} 的「缓存盘伪装成执行舱槽位」已撤除，
        //     反向回流通道不存在了；
        //   * 缓存盘抽出优先级为负 ⇒ 刚写进去的不会被同一次迁移抽出来。
        cretae.cookiewyq.rs_create_compat.support.RsccIntermediateFlow.flowOnce(
            level, getNode().getNetworkOrNull());
    }

    /** 本仓的网络节点（供能力注册与调试使用）。 */
    public IntermediateCacheNetworkNode getNode() {
        return mainNetworkNode;
    }

    /** 本仓耗电（FE/t）：纯盘位宿主，给一个与执行舱相比明显更低的值。 */
    public long getEnergyUsage() {
        return 4;
    }

    /** 本仓没有红石模式（与序列执行舱同一口径：不参与红石控制）。 */
    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /**
     * 盘位内容变化（放入 / 取出 / 替换）：
     * <ul>
     *     <li>{@link RsccSharedCache#poolChanged()} → 共享池的解析缓存与「伪槽位清单」缓存一起作废，
     *     于是网络内每一台执行舱下一次读物品存储时立刻用上新的一组盘；</li>
     *     <li>{@code setChanged()} → 让磁盘物品本身随方块 NBT 落盘（物品不能丢，也不能凭空多出来）。</li>
     * </ul>
     */
    public void onDiskSlotsChanged() {
        RsccSharedCache.poolChanged();
        // <b>2026-10-05：盘位变了 ⇒ 重建本节点暴露给网络的存储源。</b>
        // 这是「缓存盘成为真正的网络存储源」之后必须做的事 —— 否则新插的盘不会被 RS 看见，
        // 拔掉的盘仍留在网络里（幽灵源）。与 RS 自己的磁盘驱动器同一套时机。
        refreshNetworkSources();
        setChanged();
        // 成就触发点：本仓刚被「投入使用」（插盘 / 换盘；服务端容器回调，重复 fire 由原版去重）
        cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onSharedCacheDiskChanged(this);
    }

    /**
     * 把本仓<b>盘位里的磁盘</b>按槽位下标顺序追加进池子
     * （供 {@link RsccSharedCache} 合并多台仓时调用）。
     * <p>只追加「能存物品」的盘：流体 / 气体盘对「中间产物（物品）」没有意义，
     * 混进去只会让容量口径变得难以解释（本仓只服务物品中间产物）。</p>
     * <p>没插盘时池子为空 —— 这正是用户要的语义：容量来自磁盘，方块只提供「放磁盘的地方」。</p>
     */
    /**
     * <b>把盘位里的盘重新暴露给 RS 网络</b>（{@link #onDiskSlotsChanged()} 与区块加载完成时调用）。
     *
     * <p>为什么必须由本仓主动做：{@code IntermediateCacheNetworkNode} 只在<b>容器加入网络</b>时
     * 被 RS 调用一次 {@code getStorage()}。之后玩家插 / 拔盘不会触发 RS 重新拉取，
     * 所以必须由盘位的宿主（本类）把新的源列表推给节点。</p>
     */
    public void refreshNetworkSources() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        final List<SerializableStorage> disks = new java.util.ArrayList<>();
        appendPoolStorages(level, disks);
        getNode().refreshSources(disks);
    }

    /** 区块加载完成：盘位内容刚被反序列化，需要把它们重新暴露给网络。 */
    @Override
    public void onLoad() {
        super.onLoad();
        refreshNetworkSources();
    }

    public void appendPoolStorages(final Level level, final List<SerializableStorage> out) {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (int slot = 0; slot < diskSlots.getContainerSize(); slot++) {
            final SerializableStorage storage = RsccSharedCache.resolveDiskStorage(level, diskSlots.getItem(slot));
            if (RsccSharedCache.acceptsItems(storage)) {
                out.add(storage);
            }
        }
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 盘位（物品本身必须落盘；盘内的物品在 RS 存储仓库里，由磁盘的 UUID 指认）
        // 逐格带 "Slot"（原版 SimpleContainer#createTag/fromTag 会丢槽位、把多个盘挤成一格）
        tag.put("DiskSlots", cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(diskSlots, registries));
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("DiskSlots")) {
            cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                tag.getList("DiskSlots", net.minecraft.nbt.Tag.TAG_COMPOUND), diskSlots, registries);
        }
    }

    /**
     * 网络节点容器能力：让缓存仓成为 RS 网络成员（线缆接得上、能枚举到）。
     * <p>与其它机器完全同一套注册方式，因此「尊重扳手断开与分隔框架的阻断」这件事由 RS 的网络图
     * 全权负责，本模组不需要、也不允许再写第二套连接判定。</p>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.INTERMEDIATE_CACHE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
