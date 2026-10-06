package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.composite.CompositeAwareChild;
import com.refinedmods.refinedstorage.api.storage.composite.CompositeStorageImpl;
import com.refinedmods.refinedstorage.api.storage.composite.ParentComposite;
import com.refinedmods.refinedstorage.api.storage.composite.PriorityProvider;

import java.util.Collection;

/**
 * <b>中间产物缓存仓暴露给 RS 网络的那个「存储源」</b>。
 *
 * <h2>为什么必须实现 {@link CompositeAwareChild}（2026-10-05 找到的关键一环）</h2>
 *
 * <p>缓存盘此前<b>插了、在线、有 1024 容量，却一个字节都写不进去</b>
 * （快照 {@code cachePool: diskCount=1, connected=true, storedTotal=0, contents=[]}）。</p>
 *
 * <p>根因：RS 的根存储（{@code RootStorageImpl} → {@code CompositeStorageImpl}）在
 * {@code addSource}/{@code insert} 时<b>只递归进实现了 {@link CompositeAwareChild} 的子节点</b>
 * （见 {@code CompositeStorageImpl.contains} / {@code onSourceAddedToChild} 的实现）。
 * RS 自己的磁盘驱动器正是为此用了 {@code ExposedStorage}
 * （{@code StorageNetworkNode}：{@code private final ExposedStorage storage}）——
 * 它 implements {@code CompositeAwareChild}，所以父复合会往下递归、真正拿到盘子存储。</p>
 *
 * <p>我第一版直接返回裸 {@code CompositeStorageImpl}（<b>不是</b> {@code CompositeAwareChild}），
 * 于是父复合把它当<b>叶子</b>：既不递归进它的子源，也不会把它当链路上的可插入节点 ⇒
 * 优先级再高也没用。这就是「网络源注册了却不生效」的原因。</p>
 *
 * <h2>实现方式：包一层，把「知道自己在复合里」这件事转发给内部的 CompositeStorageImpl</h2>
 * <p>{@code CompositeStorageImpl} 本身<b>已经实现了</b> {@code onAddedIntoComposite} /
 * {@code onRemovedFromComposite} / {@code compositeInsert} / {@code compositeExtract}
 * （因为它是「有子源的复合存储」，天生需要这些），只是没有声明实现该接口。
 * 因此这里用组合而非继承，把这四个方法原样转发 —— 不重写任何语义。</p>
 *
 * <h2><b>2026-10-06 第二次修正（这才是「盘在线、占用恒为 0」的真正根因）：优先级必须挂在本对象上</b></h2>
 *
 * <p>此前把「插入优先级 +1000 / 抽出优先级 −1000」包在了<b>盘</b>上
 * （{@code RsccIntermediateOnlyStorage.wrap} 里的 {@code PriorityStorage}）。
 * 那是<b>错误的一层</b>：{@code PriorityStorage} 只是本节点<b>内部那个私有复合</b>
 * （{@code inner}）的一个子源，而 {@code inner} 里每一块盘的优先级都一样、没有任何竞争者
 * —— 优先级在那里等于没写。</p>
 *
 * <p>真正的竞争发生在<b>网络的根复合</b>（{@code StorageNetworkComponentImpl} ⇒
 * {@code RootStorageImpl.storage} = 一个 {@code CompositeStorageImpl}）里：它把每个
 * {@code StorageProvider.getStorage()} 的返回值当作<b>直接</b>子源，
 * 用 {@code PrioritizedStorageComparator.INSERT} 按
 * {@link PriorityProvider#getInsertPriority()} 降序排序，
 * 再在 {@code CompositeStorageImpl#insert:124-138} 里<b>从第一个子源开始尝试</b>，
 * 谁先收下就归谁。因此只有<b>本对象</b>（= 注册进网络的那个 {@code Storage}）
 * 实现了 {@code PriorityProvider}，优先级才会被排序看到。</p>
 *
 * <p><b>实测证据</b>（{@code run/logs/debug-1.log.gz}，服务器线程，
 * {@code StorageNetworkComponentImpl} 的 DEBUG 行）：网络建立时源的注册顺序是<br>
 * ①3×外部存储 → ②1×存储方块/磁盘驱动器 → <b>③本节点（RsccCacheExposedStorage）</b>
 * → ④其余 12×存储方块。<br>
 * 所有源默认优先级都是 0，稳定排序 ⇒ 顺序原样保留、本节点排第 5。
 * 于是插入在第一、二批源就被收下（那些 {@code ExposedStorage} 的
 * {@code compositeInsert} 会回报 {@code amountForList=inserted}，
 * 所以日志里 {@code landed=1} 而不是外部存储的 {@code landed=0}），
 * 根本轮不到本节点 —— 这正是 {@code moved=11 poolFree(before)=1024 poolStored(after)=0}
 * 每一轮都复现、而 {@code inNetworkSourceList=true} 的机制。</p>
 */
public final class RsccCacheExposedStorage implements CompositeAwareChild, PriorityProvider {
    /**
     * 网络源插入优先级：远高于其它源（RS 默认 0，玩家可改）⇒ 中间产物先落缓存盘。
     * <p>只有本对象实现 {@link PriorityProvider} 才有意义，见类注释。</p>
     */
    public static final int INSERT_PRIORITY = 1000;

    /**
     * 网络源抽出优先级：负值 ⇒ 网络取用中间产物时<b>最后</b>才动缓存盘
     * （刚按优先级写进去的不会被同一次搬运立刻抽出来）。
     */
    public static final int EXTRACT_PRIORITY = -1000;

    /** 内部真正的复合存储（承载各块盘）。 */
    private final CompositeStorageImpl inner;

    public RsccCacheExposedStorage(final MutableResourceList list) {
        this.inner = new CompositeStorageImpl(list);
    }

    // ==================== 源管理 ====================

    /** 用「只收中间产物」的包装把一块盘加进来。 */
    public void addDisk(final Storage disk) {
        inner.addSource(RsccIntermediateOnlyStorage.wrap(disk));
    }

    public void clearDisks() {
        inner.clearSources();
    }

    // ==================== Storage（转发） ====================

    @Override
    public long insert(final ResourceKey resource, final long amount, final Action action,
                       final Actor actor) {
        return inner.insert(resource, amount, action, actor);
    }

    @Override
    public long extract(final ResourceKey resource, final long amount, final Action action,
                        final Actor actor) {
        return inner.extract(resource, amount, action, actor);
    }

    @Override
    public Collection<ResourceAmount> getAll() {
        return inner.getAll();
    }

    @Override
    public long getStored() {
        return inner.getStored();
    }

    // ==================== CompositeAwareChild ====================

    @Override
    public void onAddedIntoComposite(final ParentComposite parentComposite) {
        inner.onAddedIntoComposite(parentComposite);
    }

    @Override
    public void onRemovedFromComposite(final ParentComposite parentComposite) {
        inner.onRemovedFromComposite(parentComposite);
    }

    /**
     * <b>父复合向本节点抽出时的回调</b>（2026-10-05 修崩溃）。
     *
     * <h2>为什么不能转发给内部的 {@code CompositeStorageImpl}</h2>
     * <p>实测崩溃（crash-2026-10-05_22.33.42-server，玩家只是给输出总线标了一格白名单就崩了）：</p>
     * <pre>
     * RsccCacheExposedStorage.compositeExtract(:108)
     *   → CompositeStorageImpl.compositeExtract(CompositeStorageImpl.java:191)
     *   → java.lang.UnsupportedOperationException
     * </pre>
     * <p>{@code CompositeStorageImpl} 的 {@code compositeInsert} / {@code compositeExtract}
     * <b>是故意抛 {@code UnsupportedOperationException} 的占位实现</b>
     * —— 它们只应由「子节点」实现，子节点自己去操作存储、再回报父复合该更新多少缓存。
     * 我第一版把这两个方法原样转发，等于主动去调那个占位实现 ⇒ 只要 RS 从网络里抽任何东西，
     * 而抽取路径扫到本节点，就必崩。</p>
     *
     * <h2>正确契约（见 {@code CompositeStorageImpl#extract:96-105}）</h2>
     * <p>父复合对子节点调用本方法后：</p>
     * <ul>
     *     <li>{@code remaining -= amount.amount()} —— 用「<b>实际抽出量</b>」扣减剩余需求；</li>
     *     <li>{@code toRemoveFromList += amount.amountForList()} —— 用「<b>该从父缓存里扣多少</b>」
     *     更新父复合自己的资源清单。</li>
     * </ul>
     * <p>因此这里自己执行抽取，并把两个数都填成实际量 —— 父复合的缓存清单与真实内容始终一致。</p>
     */
    @Override
    public Amount compositeInsert(final ResourceKey resource, final long amount, final Action action,
                                  final Actor actor) {
        final long inserted = inner.insert(resource, amount, action, actor);
        return new Amount(inserted, inserted);
    }

    /**
     * 见 {@link #compositeInsert} 对契约的完整说明。
     */
    @Override
    public Amount compositeExtract(final ResourceKey resource, final long amount, final Action action,
                                   final Actor actor) {
        final long extracted = inner.extract(resource, amount, action, actor);
        return new Amount(extracted, extracted);
    }

    /** 让 RS 能判断「某个源是不是在本节点内部」——转发给内部复合（递归判断）。 */
    @Override
    public boolean contains(final Storage storage) {
        return inner.contains(storage);
    }

    // ==================== PriorityProvider（必须在本层，见类注释） ====================

    /** 网络根复合按这个数降序排序子源：本节点因此排到所有默认 0 的源之前。 */
    @Override
    public int getInsertPriority() {
        return INSERT_PRIORITY;
    }

    /** 负值 ⇒ 网络抽取时本节点排在最后（只有别处都没有时才动缓存盘）。 */
    @Override
    public int getExtractPriority() {
        return EXTRACT_PRIORITY;
    }
}
