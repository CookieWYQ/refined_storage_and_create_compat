package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Collection;

/**
 * <b>「只收序列装配中间产物」的存储装饰器</b>。
 *
 * <h2>它解决什么</h2>
 * <p>缓存仓的磁盘此前<b>根本不是 RS 网络存储源</b>
 * （{@code IntermediateCacheNetworkNode} 没有实现 {@code StorageProvider}），
 * 于是：终端看不见它、RS 不会往它里面写、只能靠手写搬运 —— 而手写搬运又被模组自己的
 * 收回路径反向抽干。正确做法是把缓存盘注册成网络源，再用本类做<b>按资源的过滤</b>
 * （RS 的优先级是「按整块存储」一个数，无法只对中间产物生效）。</p>
 *
 * <h2>「返回 0」是 RS 的官方约定</h2>
 * <p>{@code CompositeStorageImpl#insert} 逐个源尝试、累加到请求量才停，
 * 因此某个源返回 0 就等于「我不收，请去下一个源」。这是过滤器成立的关键。</p>
 *
 * <h2>优先级<b>不在这里</b>（2026-10-06 修正）</h2>
 * <p>此前这里还套了一层 {@code PriorityStorage.of(..., 1000, -1000)}。那是错误的一层：
 * 本类只是节点<b>内部私有复合</b>的子源，那里的每一块盘优先级都相同、没有竞争者，
 * 优先级写在那里等于没写。真正的竞争发生在网络的根复合里，
 * 优先级必须由注册进网络的那个对象（{@code RsccCacheExposedStorage}）实现
 * {@code PriorityProvider} 才有效 —— 详见该类注释。</p>
 *
 * <h2>抽出不设限</h2>
 * <p>{@link #extract} 直接转发：网络要取用中间产物时必须取得到，否则缓存盘会变成只进不出的黑洞。
 * 只有<b>写入</b>才按类型过滤。</p>
 */
public final class RsccIntermediateOnlyStorage implements Storage {
    /** 底层盘（玩家插的那块盘的真实存储对象）。 */
    private final Storage delegate;

    private RsccIntermediateOnlyStorage(final Storage delegate) {
        this.delegate = delegate;
    }

    /** 把一块底层盘包成「只收中间产物」的网络源。 */
    public static Storage wrap(final Storage disk) {
        return new RsccIntermediateOnlyStorage(disk);
    }

    /** 只接收 {@code incomplete_*} / {@code unprocessed_*} 物品；其余一律 0（= 换下一个源）。 */
    @Override
    public long insert(final ResourceKey resource, final long amount, final Action action,
                       final Actor actor) {
        if (!(resource instanceof final ItemResource item) || amount <= 0L) {
            return 0L;
        }
        if (!RsccIntermediateFlow.isIntermediateId(
            BuiltInRegistries.ITEM.getKey(item.item()).toString())) {
            return 0L;
        }
        return delegate.insert(resource, amount, action, actor);
    }

    /** 抽出<b>不设限</b>（网络要用就能取）：只有写入才按类型过滤。 */
    @Override
    public long extract(final ResourceKey resource, final long amount, final Action action,
                        final Actor actor) {
        return delegate.extract(resource, amount, action, actor);
    }

    @Override
    public Collection<ResourceAmount> getAll() {
        return delegate.getAll();
    }

    @Override
    public long getStored() {
        return delegate.getStored();
    }
}
