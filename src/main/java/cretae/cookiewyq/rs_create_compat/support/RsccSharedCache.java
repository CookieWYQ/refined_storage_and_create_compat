package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.limited.LimitedStorage;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.api.storage.StorageContainerItem;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.IntermediateCacheNetworkNode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 「中间产物共享缓存池」的<b>唯一实现</b>：把一个 RS 网络里<b>所有</b>「中间产物缓存仓」
 * （{@code intermediate_cache}）的磁盘合并成<b>一个池子</b>，供该网络内<b>所有</b>序列装配执行舱共用。
 *
 * <h2>用户决定（这就是本类的存在理由）</h2>
 * 用户原话：「那个缓存的磁盘应该是<b>通用的</b>，他只做一个<b>所有这种的临时储存点</b>而已；
 * 你一个一个执行器放置感觉还没必要，那这样的话我不得制作很多个磁盘吗？」——
 * 因此磁盘不再插在执行舱上，而是插在一个共享的缓存仓里，执行舱只是<b>使用者</b>。
 *
 * <h2>怎么发现（为什么不另写一套连接判定）</h2>
 * 走 RS 自己的网络图：{@code network.getComponent(GraphNetworkComponent.class).getContainers()}
 * 枚举该网络当前<b>全部</b>网络节点容器，挑出本模组的缓存仓节点。
 * 这份枚举天然满足全部既有连接语义，且<b>绝不重复实现连接判定</b>：
 * <ul>
 *     <li>RS 的网络图连边本身就经过「机械动力扳手断开线缆」与「分隔框架阻断」这两道闸门
 *     （{@link cretae.cookiewyq.rs_create_compat.mixin.network.InWorldNetworkNodeContainerImplMixin}
 *     接管 {@code canAcceptIncomingConnection}，与连接臂外形同一句判定），
 *     所以「被扳手断开 / 被框架阻断的那一侧」在枚举结果里本来就不存在；</li>
 *     <li>缓存仓要真的「插进线缆」才会出现在网络图里 —— 它继承 RS 的网络节点方块实体，
 *     与其它 RS 机器一样靠线缆接入，不存在第二套「谁能连谁」的规则；</li>
 *     <li>执行舱侧拿的就是<b>同一个</b> {@code Network} 对象（{@code mainNetworkNode.getNetwork()}），
 *     因此同一网络里的每台执行舱枚举到的是同一批缓存仓 = 同一份池子。</li>
 * </ul>
 *
 * <h2>合并顺序（确定性）</h2>
 * ①缓存仓按<b>坐标字典序</b>（x → y → z，{@link RsccWireLinkSearch#lexicographic}）排序 ——
 * 与「枚举 {@code Set} 的迭代顺序」无关；②同一台缓存仓内部按<b>槽位下标</b>升序。
 * 写入顺序与读取顺序都是「内部存储 → 池子（按上述顺序）」，
 * 因此「谁先装、谁先出」在任何一次枚举 / 任何一台执行舱上都是同一个答案。
 *
 * <h2>缓存与失效</h2>
 * 枚举一次网络图是 O(节点数)，而执行舱按槽位遍历物品存储时会反复问「池子里有什么」，
 * 所以这里缓存解析结果：
 * <ul>
 *     <li><b>事件驱动失效</b>：缓存仓被放置 / 破坏 / 被替换时，
 *     {@link RsccCacheInvalidation} 立刻调 {@link #poolChanged()} 作废全部缓存；</li>
 *     <li><b>内容变化</b>：任何一次对池子的写 / 抽（{@code RsccChamberItemStorage}）以及
 *     任何一次缓存仓换盘都 +1 版本号，让「伪槽位清单」缓存作废；</li>
 *     <li><b>兜底重算</b>：即使上面两条都漏了（例如缓存仓所在区块被卸载，RS 把它的节点
 *     从网络拿掉但不产生方块事件），也只是最多 {@value #REFRESH_TICKS} tick 的延迟 ——
 *     池子只会「晚一点看见」新内容，绝不会复制或销毁任何物品。</li>
 * </ul>
 *
 * <h2>没有缓存仓时</h2>
 * {@link #storages} 返回空表，{@code RsccChamberItemStorage} 退化成「只有执行舱内部存储」，
 * 与「磁盘槽时代插不插盘」之外的既有行为逐字一致：不报错、不打日志、不改变任何判定。
 */
public final class RsccSharedCache {
    /** 解析结果的重算兜底周期（tick）：见类注释「缓存与失效」。 */
    private static final int REFRESH_TICKS = 20;

    /** 判断磁盘存储是否「能存物品」用的探针资源（只看类型，不看具体物品）。 */
    private static final ItemResource ITEM_PROBE = new ItemResource(net.minecraft.world.item.Items.AIR);

    /** 无限容量磁盘（创造级）对外报告的剩余容量：足够大又不会在别处做加法时溢出。 */
    private static final long UNLIMITED_REMAINING = Long.MAX_VALUE / 4;

    /** 池内容的版本号（全局单调）：换盘 / 写入 / 拓扑变化都会 +1，用于作废「伪槽位清单」缓存。 */
    private static long version = 1L;

    /** 世界 → （网络 → 解析结果）。两级都用弱键，避免世界 / 网络卸载后泄漏。 */
    private static final Map<Level, Map<Network, Entry>> CACHE = new WeakHashMap<>();

    private RsccSharedCache() {
    }

    /** 一次解析结果：盘存储清单 + 生成它的版本号与时间戳。 */
    private record Entry(List<SerializableStorage> storages, long version, long stamp) {
    }

    /**
     * 执行舱侧的只读端口：把「本仓能看见的池子」交给物品存储统一视图。
     * <p>做成端口而不是直接传 {@link Network}，是为了让视图类不必知道自己跑在哪个网络上
     * （也就不会有人在这里偷偷写第二套网络查询）。</p>
     */
    public interface PoolSource {
        /**
         * 当前网络内全部缓存仓的存储（<b>每台仓盘位里能存物品的盘</b>，按池子顺序；
         * 无缓存仓 / 未接入网络 / 没插盘 → 空表）。
         * <p>容量<b>完全由磁盘提供</b>：本仓自身不再自带物品容量（第 7 轮按用户要求改回
         * 「一个小箱子大小的磁盘存放空间」，见
         * {@link cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity#DISK_SLOTS}）。</p>
         */
        List<SerializableStorage> storages();

        /** 池的版本号（见 {@link #version()}）；变化即代表「伪槽位清单」需要重建。 */
        long version();
    }

    // ==================== 解析 ====================

    /**
     * 该网络当前的池子（按「缓存仓坐标字典序 → 仓内槽位下标」排序；只含能存物品的盘）。
     * <p><b>只在服务端返回非空</b>：客户端没有服务端存档里的盘存储（{@code StorageRepository} 是两套），
     * 因此这里一律返回空表，界面只做渲染（见界面类的文案）。</p>
     */
    public static List<SerializableStorage> storages(@Nullable final Level level, @Nullable final Network network) {
        if (level == null || level.isClientSide() || network == null) {
            return List.of();
        }
        final Map<Network, Entry> byNetwork = CACHE.computeIfAbsent(level, key -> new WeakHashMap<>());
        final Entry cached = byNetwork.get(network);
        final long now = level.getGameTime();
        if (cached != null && cached.version() == version && now - cached.stamp() < REFRESH_TICKS) {
            return cached.storages();
        }
        final List<SerializableStorage> resolved = resolve(level, network);
        byNetwork.put(network, new Entry(resolved, version, now));
        return resolved;
    }

    /** 真正枚举网络图并合并各缓存仓的磁盘（调用方已持缓存，见 {@link #storages}）。 */
    private static List<SerializableStorage> resolve(final Level level, final Network network) {
        final List<IntermediateCacheBlockEntity> warehouses = new ArrayList<>();
        for (final NetworkNodeContainer container
            : network.getComponent(GraphNetworkComponent.class).getContainers()) {
            if (container.getNode() instanceof IntermediateCacheNetworkNode node) {
                final IntermediateCacheBlockEntity warehouse = node.getBlockEntity();
                if (warehouse != null) {
                    warehouses.add(warehouse);
                }
            }
        }
        if (warehouses.isEmpty()) {
            return List.of();
        }
        // 确定性顺序：坐标字典序（不含枚举 Set 的迭代顺序）→ 仓内槽位下标
        // （坐标字典序直接复用 RsccWireLinkSearch 的那一份判定，不再写第二套比较）
        warehouses.sort((a, b) -> RsccWireLinkSearch.lexicographic(a.getBlockPos(), b.getBlockPos()));
        final List<SerializableStorage> storages = new ArrayList<>();
        for (final IntermediateCacheBlockEntity warehouse : warehouses) {
            warehouse.appendPoolStorages(level, storages);
        }
        return List.copyOf(storages);
    }

    // ==================== 版本号 / 失效 ====================

    /**
     * <b>缓存池当前还能接收多少件</b>（各盘剩余之和；没有缓存仓 / 没插盘 ⇒ 0）。
     *
     * <p>用途：{@link RsccIntermediateFlow} 的「闸门 A」—— 池子满了就一件都不抽，
     * 避免「抽出来又插不进去」的无谓往返。</p>
     */
    public static long poolFreeSpace(final net.minecraft.world.level.Level level,
                                     final com.refinedmods.refinedstorage.api.network.Network network) {
        long free = 0L;
        for (final SerializableStorage each : storages(level, network)) {
            free += remainingOf(each);
        }
        return free;
    }

    /** <b>缓存池当前已存件数</b>（对照读数用：与 {@link #poolFreeSpace} 配对）。 */
    public static long poolStored(final net.minecraft.world.level.Level level,
                                  final com.refinedmods.refinedstorage.api.network.Network network) {
        long stored = 0L;
        for (final SerializableStorage each : storages(level, network)) {
            stored += storedOf(each);
        }
        return stored;
    }

    /**
     * <b>只读快照：池子里每种资源各存了多少件</b>（同一次 {@link #storages} 解析出来的同一批盘）。
     *
     * <p>用途：{@link RsccIntermediateFlow} 的存量迁移只搬「<b>还不在池子里</b>」的那一份。
     * 没有这道闸门时，一件已经完全躺在缓存盘里的中间产物会被反复
     * 「抽出来再插回去」（每一轮都是 0 位移），白白刷日志、白白过一次
     * {@code extract}/{@code insert} 与网络清单更新。</p>
     *
     * <p>本方法<b>只读</b>，不写池、不改任何存储 —— 物品的移动永远只由 RS 的
     * {@code CompositeStorageComponent#insert}（按优先级）完成。</p>
     */
    public static Map<ResourceKey, Long> poolContents(final Level level, final Network network) {
        final Map<ResourceKey, Long> contents = new HashMap<>();
        for (final SerializableStorage each : storages(level, network)) {
            for (final ResourceAmount amount : each.getAll()) {
                if (amount.amount() > 0L) {
                    contents.merge(amount.resource(), amount.amount(), Long::sum);
                }
            }
        }
        return contents;
    }

    /** 池内容版本号：变化即代表「伪槽位清单」缓存应作废。 */
    public static long version() {
        return version;
    }

    /**
     * 池子内容 / 拓扑发生变化：版本号 +1 并清空解析缓存，下一次访问立刻重算。
     * <p>由三处调用：缓存仓换盘（{@code IntermediateCacheBlockEntity}）、
     * 缓存仓方块被放置 / 破坏（{@link RsccCacheInvalidation}）、
     * 以及任何一次真正写进池子 / 从池子取出（{@code RsccChamberItemStorage}）。</p>
     */
    public static void poolChanged() {
        version++;
        CACHE.clear();
    }

    /** 诊断：池子的「盘数 / 已用 / 容量」摘要（无限盘记 {@code INF}）；没有盘时返回 {@code none}。 */
    public static String describe(@Nullable final Level level, @Nullable final Network network) {
        final List<SerializableStorage> storages = storages(level, network);
        if (storages.isEmpty()) {
            return "none";
        }
        long stored = 0L;
        long capacity = 0L;
        boolean unlimited = false;
        for (final SerializableStorage storage : storages) {
            stored += storedOf(storage);
            final long each = capacityOf(storage);
            if (each < 0L) {
                unlimited = true;
            } else {
                capacity += each;
            }
        }
        return storages.size() + "x" + stored + "/" + (unlimited ? "INF" : capacity);
    }

    // ==================== 存储磁盘判定 / 解析（界面与槽位共用的唯一一句） ====================

    /** 该物品是否可作为「中间产物缓存」磁盘（= RS 的存储磁盘类物品 {@code StorageContainerItem}）。 */
    public static boolean isStorageDisk(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof StorageContainerItem;
    }

    /**
     * 解析磁盘物品当前指向的存储（服务端权威；客户端 / 解析不到一律 {@code null}）。
     * <p>与 RS 自己的磁盘驱动器同一口径：只按磁盘物品携带的 UUID 去 {@code StorageRepository} 取，
     * <b>不主动创建</b>存储（RS 也没有这么做）—— 玩家手里的磁盘在其背包 tick 中就会建好存储
     * （{@code AbstractStorageContainerItem#inventoryTick} → {@code loadStorageIfNecessary}）。
     * 因此一块「还没进过玩家背包」的磁盘会短暂显示为「同步中」，等它随磁盘一起被玩家拿过就好。</p>
     */
    @Nullable
    public static SerializableStorage resolveDiskStorage(@Nullable final Level level, final ItemStack stack) {
        if (level == null || level.isClientSide() || !isStorageDisk(stack)) {
            return null;
        }
        return RefinedStorageApi.INSTANCE.getStorageContainerItemHelper()
            .resolveStorage(RefinedStorageApi.INSTANCE.getStorageRepository(level), stack)
            .orElse(null);
    }

    /** 该存储能否存物品（流体 / 气体磁盘一律不计入本池的物品缓存空间）。 */
    public static boolean acceptsItems(@Nullable final SerializableStorage storage) {
        return storage != null && storage.getType().isAllowed(ITEM_PROBE);
    }

    /**
     * 存储的剩余容量（物品件数）：有限容量盘 = 容量 − 已用；无限盘（创造级）= 极大值。
     * <p>只对「能存物品」的存储有意义；其余一律 0。</p>
     */
    public static long remainingOf(@Nullable final SerializableStorage storage) {
        if (!acceptsItems(storage)) {
            return 0L;
        }
        if (storage instanceof LimitedStorage limited) {
            return Math.max(0L, limited.getCapacity() - storage.getStored());
        }
        return UNLIMITED_REMAINING;
    }

    /** 存储的总容量（物品件数）：无限盘返回 {@code -1}（界面显示 ∞）。 */
    public static long capacityOf(@Nullable final SerializableStorage storage) {
        if (!acceptsItems(storage)) {
            return 0L;
        }
        return storage instanceof LimitedStorage limited ? limited.getCapacity() : -1L;
    }

    /** 存储的已用量（物品件数；磁盘自己的记账口径，含该盘一并存放的流体按桶折算的部分）。 */
    public static long storedOf(@Nullable final SerializableStorage storage) {
        return acceptsItems(storage) ? Math.max(0L, storage.getStored()) : 0L;
    }
}
