package cretae.cookiewyq.rs_create_compat.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * RS 磁盘适配器的「固定 N 个虚拟槽 + 粘性分配」槽位表。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code Capabilities.ItemHandler.ITEM} 的 provider <b>每次查询都会重建 handler</b>
 * （{@code ItemCapability#getCapability} 没有缓存，见 {@code docs/RS_DISK_ADAPTER_DESIGN.md} §3.1.1）。
 * 如果槽位表放在 handler 实例里，「第 3 槽是圆石」就会在每次查询后被重新洗牌 ——
 * 按槽匹配资源的 RS 外部存储（{@code ItemHandlerExtractableStorage}）会表现为
 * 「过滤器/管道一会儿看得到一会儿看不到」。所以粘性分配表必须按<b>存储对象</b>共享。</p>
 *
 * <h2>为什么 key 是 {@link SerializableStorage} 而不是 UUID</h2>
 * <p>本模组刻意不碰 RS 的内部组件 {@code refinedstorage:storage_reference}（那是 {@code @API INTERNAL}）。
 * 同一个 UUID 在 {@code StorageRepository} 里只会对应<b>同一个</b> {@code SerializableStorage} 实例
 * （{@code StorageRepositoryImpl#get} 是 map 取值），因此「存储对象身份」与 UUID 一一对应，
 * 用弱引用 key 还能让存档卸载后自动释放。</p>
 *
 * <h2>分配规则（设计文档 §3.4）</h2>
 * <ol>
 *     <li>当前存在的资源按 {@code (注册名, 组件串)} 排序，得到稳定顺序；</li>
 *     <li>仍然存在的资源保留原槽；已消失的资源释放槽；</li>
 *     <li>新资源按排序依次填入最小的空闲槽；</li>
 *     <li>槽位只影响「可见 / 可抽取」，不影响存储本身（插入永远写整张盘）。</li>
 * </ol>
 */
public final class RsccDiskSlotCache {
    /** 虚拟槽窗口 N：固定 64（设计文档 §3.5 的默认值；越大 RS 外部存储每次刷新越贵）。 */
    public static final int WINDOW_SLOTS = 64;

    private static final Map<SerializableStorage, ResourceKey[]> BINDINGS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private RsccDiskSlotCache() {
    }

    /**
     * 取（并按当前内容刷新）该存储的槽位绑定表。
     *
     * @param storage 目标存储（身份即 UUID）
     * @param present 当前存在的物品资源，调用方已排序
     * @return 长度恒为 {@link #WINDOW_SLOTS} 的绑定表（可为 {@code null} 元素）
     */
    public static ResourceKey[] bindings(final SerializableStorage storage,
                                         final List<ItemResource> present) {
        synchronized (BINDINGS) {
            final ResourceKey[] previous = BINDINGS.get(storage);
            final ResourceKey[] next = previous != null ? previous.clone() : new ResourceKey[WINDOW_SLOTS];
            final Set<ResourceKey> alive = new HashSet<>(present);
            for (int slot = 0; slot < next.length; slot++) {
                if (next[slot] != null && !alive.contains(next[slot])) {
                    next[slot] = null;
                }
            }
            for (final ItemResource resource : present) {
                if (indexOf(next, resource) >= 0) {
                    continue;
                }
                final int free = indexOf(next, null);
                if (free < 0) {
                    break;
                }
                next[free] = resource;
            }
            BINDINGS.put(storage, next);
            // 返回<b>共享</b>数组（不是副本）：handler 在「插到空槽」时要把新绑定写回去，
            // 否则下一个 handler 实例又会按排序重新分配，粘性就断了。
            return next;
        }
    }

    private static int indexOf(final ResourceKey[] array, @Nullable final ResourceKey wanted) {
        for (int i = 0; i < array.length; i++) {
            if (wanted == null ? array[i] == null : wanted.equals(array[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 服务器停止 / 换世界：清空所有粘性槽位（避免跨存档的 UUID 撞见旧表）。 */
    public static void clear() {
        BINDINGS.clear();
    }
}
