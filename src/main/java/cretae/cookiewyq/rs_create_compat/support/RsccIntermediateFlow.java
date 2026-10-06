package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.TrackedResourceAmount;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * <b>把网络里「已经在别处」的中间产物迁移进中间产物缓存仓</b>。
 *
 * <h2>用户原话（2026-10-05，用户指出我漏掉了这一条）</h2>
 * <p><i>「你是不是忘了我说的主动回收机制？就算没有新产生的中间产物，
 * 那么网络中自带的这种中间产物也应该自己到那一个地方去啊」</i></p>
 *
 * <h2>为什么必须有它（RS 的优先级解决不了这件事）</h2>
 * <p>缓存盘现在是一个带<b>插入优先级 +1000</b> 的网络存储源，RS 在
 * {@code CompositeStorageImpl#insert} 时会优先往它写。但优先级<b>只作用于「新插入」</b>：
 * 一件中间产物如果<b>已经在</b>磁盘驱动器 / 外部存储里，RS 没有任何机制会把它挪到别处。
 * 因此「存量迁移」必须由本模组做一轮。</p>
 *
 * <h2>为什么这一版是自洽的（与前两版失败的区别）</h2>
 * <ol>
 *     <li>从网络权威存储 {@code extract} 出来；</li>
 *     <li>再 {@code insert} 回<b>同一个</b>网络权威存储 —— RS 按插入优先级把它放进缓存盘；</li>
 *     <li>缓存盘的<b>抽出优先级是负值</b>（最后才被抽），因此刚写进去的不会被同一次搬运抽出来。</li>
 * </ol>
 *
 * <p>前两版失败的根因现在都已消除：</p>
 * <ul>
 *     <li><b>缓存盘不是真正的网络源</b>（缺 {@code CompositeAwareChild} 那一环）⇒ RS 拿不到它，
 *     插入永远落到别处，表现为「占用恒为 0」。现在 {@code IntermediateCacheNetworkNode} 暴露的是
 *     {@link RsccCacheExposedStorage}（implements {@code CompositeAwareChild}），RS 会真正递归进它；</li>
 *     <li><b>反向回流通道</b>：{@code RsccChamberItemStorage} 曾把缓存盘的每一格伪装成执行舱的槽位，
 *     于是模组的收回路径把刚写进去的又抽回网络。该伪装已撤除。</li>
 * </ul>
 *
 * <h2>不丢不复制（硬底线）</h2>
 * <p>按<b>实际抽出量</b>记账（不按请求量）。万一写回量少于抽出量，把差额再插一次；
 * {@code extract} 与 {@code insert} 都作用在<b>同一个权威对象</b>上，
 * 不存在「取出来拿在手上然后丢掉」的窗口。</p>
 */
public final class RsccIntermediateFlow {
    /** 单轮迁移预算（件）：够快，又不会让一次 tick 做太多插入。 */
    public static final int BATCH = 32;

    private RsccIntermediateFlow() {
    }

    /**
     * 该物品注册名是不是「序列装配中间产物」。
     *
     * <p>判据 = Create 的命名规则：{@code incomplete_*} / {@code unprocessed_*}。</p>
     */
    public static boolean isIntermediateId(@Nullable final String itemId) {
        return itemId != null && (itemId.contains("incomplete_") || itemId.contains("unprocessed_"));
    }

    /**
     * 跑一轮存量迁移：把网络里已经在别的存储上的中间产物搬进缓存盘。
     *
     * @return 本轮实际迁移的件数（0 = 没有可迁移的 / 缓存盘不可用 / 已满）
     */
    public static long flowOnce(@Nullable final Level level, @Nullable final Network network) {
        if (level == null || level.isClientSide() || network == null) {
            return 0L;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return 0L;
        }
        // 闸门 A：缓存盘没有空间就一件都不动（满了再抽只会「抽出来又插不进去」）
        final long poolFree = RsccSharedCache.poolFreeSpace(level, network);
        if (poolFree <= 0L) {
            return 0L;
        }
        // 闸门 B（只读）：池子里已经有这一种就只搬「池外的那一份」。
        // 没有它的话，一件已经完全躺在缓存盘里的中间产物会被每轮「抽出来又插回去」——
        // 0 位移、纯空转，还会把日志刷满（实测签名：moved=11 poolStored(after)=11 恒定不变）。
        // 注意：这里只「读」池子；物品真正进盘永远走 RS 的按优先级 insert。
        final Map<ResourceKey, Long> alreadyInPool =
            RsccSharedCache.poolContents(level, network);
        long moved = 0L;
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            if (moved >= BATCH) {
                break;
            }
            final ResourceKey key = tracked.resourceAmount().resource();
            if (!(key instanceof final ItemResource itemResource)) {
                continue;
            }
            if (!isIntermediateId(BuiltInRegistries.ITEM.getKey(itemResource.item()).toString())) {
                continue;
            }
            final long available = tracked.resourceAmount().amount();
            // 网络总存量 − 池内存量 = 还在别处的量；≤0 ⇒ 这一种已经完全在缓存盘里，跳过
            final long outside = available - alreadyInPool.getOrDefault(key, 0L);
            if (outside <= 0L) {
                continue;
            }
            final long want = Math.min(outside, Math.min(BATCH - moved, poolFree - moved));
            moved += moveOne(storage, itemResource, want);
        }
        if (moved > 0L) {
            // <b>对照读数</b>：把「迁移后缓存盘的已用量」直接打出来。
            //
            // 2026-10-05 的故障签名是连续多轮 `moved=11 poolFree(before)=1024 poolStored(after)=0`：
            // 搬了 11 件，池子剩余一点没少、盘里一件没有 —— 因为插回网络时被排在更前面的
            // 存储源收下了（见 {@code RsccCacheExposedStorage} 类注释里的根因分析）。
            //
            // 修好后这一行应当只在「池外还有中间产物」时出现，并显示
            // {@code poolStored(after)} 真的涨了（例如 0 → 11）；等池外那份搬完就不再多打。
            final long poolStoredAfter = RsccSharedCache.poolStored(level, network);
            // 开发诊断（INFO）：受 devLogs 总开关控制（前缀 [rscc-intermediate-flow]）。
            if (RsccAssemblyDebug.isEnabled()) {
                org.slf4j.LoggerFactory.getLogger("rs_create_compat/intermediate-flow").info(
                    "[rscc-intermediate-flow] moved={} poolFree(before)={} poolStored(after)={}"
                        + " networkTotal={}（存量迁移：网络里的中间产物 → 缓存盘）",
                    moved, poolFree, poolStoredAfter, networkIntermediateTotal(storage));
            }
        }
        return moved;
    }

    /**
     * 迁移一种中间产物：<b>从网络权威存储抽出 → 插回同一权威存储</b>
     * （RS 按缓存盘的插入优先级把这一步落到缓存盘上）。
     */
    private static long moveOne(final StorageNetworkComponent storage,
                                final ItemResource resource, final long want) {
        if (want <= 0L) {
            return 0L;
        }
        final long taken = storage.extract(resource, want, Action.EXECUTE, Actor.EMPTY);
        if (taken <= 0L) {
            return 0L;
        }
        // 写回：优先级把这一步落到缓存盘；缓存盘抽出优先级为负 ⇒ 不会把刚写的又抽出来
        final long back = storage.insert(resource, taken, Action.EXECUTE, Actor.EMPTY);
        if (back < taken) {
            // 理论不可达（刚刚才把它抽出来，网络至少能装回同等量）。
            // 兜底：把差额再插一次；仍装不下则它留在网络存储里，绝不销毁。
            storage.insert(resource, taken - back, Action.EXECUTE, Actor.EMPTY);
        }
        return taken;
    }

    /** 只读：网络权威存储里「所有中间产物」的件数之和（对照读数用）。 */
    private static long networkIntermediateTotal(final StorageNetworkComponent storage) {
        long total = 0L;
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            if (tracked.resourceAmount().resource() instanceof final ItemResource itemResource
                && isIntermediateId(
                    BuiltInRegistries.ITEM.getKey(itemResource.item()).toString())) {
                total += Math.max(0L, tracked.resourceAmount().amount());
            }
        }
        return total;
    }
}
