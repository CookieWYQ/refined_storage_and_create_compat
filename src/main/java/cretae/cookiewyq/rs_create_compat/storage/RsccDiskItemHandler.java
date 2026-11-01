package cretae.cookiewyq.rs_create_compat.storage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.api.storage.StorageRepository;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RS 磁盘（{@code StorageContainerItem}）的只读视图 {@link IItemHandler}：
 * <b>固定 N 个虚拟槽的「资源种类窗口」 + 粘性分配</b>，{@code getStackInSlot} 返回该种类的<b>真实总量</b>。
 *
 * <h2>为什么不是「单槽聚合」也不是「每种资源一个槽」</h2>
 * <p>RS 外部存储抽取时是<b>按槽匹配资源</b>的（{@code ItemHandlerExtractableStorage#extract} 遍历槽、
 * {@code isSameItemSameComponents} 命中才抽）。单槽永远只能匹配到「当前显示的那一种」，
 * 其它资源抽不出来；每种资源一个槽则槽数随内容变化（IItemHandler 没有槽数变更通知），
 * 1k 盘最多 1024 种、64M 通用盘更夸张 ⇒ RS 每次刷新遍历全部槽会卡死。
 * 固定 N 窗口同时满足「每个可见资源能被精确定位」与「槽数稳定」。
 * 证据与取舍见 {@code docs/RS_DISK_ADAPTER_DESIGN.md} §3.3 / §3.5。</p>
 *
 * <h2>契约细节（必须照做）</h2>
 * <ul>
 *     <li>{@code getStackInSlot} 允许返回 <b>count &gt; 物品最大堆叠</b>（NeoForge {@code IItemHandler}
 *     契约明确允许；RS 2.0.9 还专门写了「槽内超堆叠」的 SIMULATE 分支）⇒ 这里返回真实总量，
 *     并按 {@code Integer.MAX_VALUE} 夹断（{@code ItemStack} 的 count 是 int）。</li>
 *     <li>{@code extractItem} 返回量必须 {@code <= amount} 且 {@code <= 最大堆叠} ⇒ 每次最多 64。</li>
 *     <li>插入永远写「整张盘」，不要求与目标槽绑定的种类一致（磁盘本来就没有槽语义）。</li>
 *     <li>{@code Actor} 不能为 null（{@code TrackedStorageImpl} 会抛）⇒ 一律 {@code Actor.EMPTY}。</li>
 *     <li>容量由存储实现自己裁剪，{@code insert} 的返回值就是真正接受的数量（不要自己算容量）。</li>
 *     <li>只暴露 {@link ItemResource}：通用盘可能存了流体 / 气体，{@code toItemStack} 对它们会
 *     类型崩溃 ⇒ 过滤掉。</li>
 * </ul>
 *
 * <h2>缓存</h2>
 * <p>{@code getAll()} 每次调用都会新建 list（{@code MutableResourceListImpl#copyState}），
 * 而 RS 会按「每槽每次刷新」调用 {@code getStackInSlot}。因此本类在<b>实例内</b>缓存
 * 「种类 → 数量」快照，TTL 500ms 兜底外部改动；自己的写立即失效；槽位绑定放在
 * {@link RsccDiskSlotCache}（按存储对象身份，跨 handler 实例粘性）。</p>
 */
public class RsccDiskItemHandler implements IItemHandler {
    /** 快照兜底 TTL（毫秒）：别的代理器 / RS 网络改了同一张盘时，最多看到 0.5 秒的旧数量。 */
    private static final long SNAPSHOT_TTL_MS = 500L;
    /**
     * 同一 TTL 的纳秒表示（配合 {@link System#nanoTime()}）。
     * <p><b>为什么不用 {@code System.currentTimeMillis()}</b>：墙上时钟会被 NTP 同步 / 玩家手动改时间
     * 跳变，而这里的判据是 {@code now - builtAt <= TTL} —— 时钟<b>往回调</b>时差值是负数（永远 ≤ TTL），
     * 快照于是<b>长期不过期</b>：磁盘内容变了，这里还显示旧数量与旧槽位绑定。
     * {@code nanoTime()} 是单调时钟，只受进程启动时间影响，改系统时间不会影响过期判断
     * （本工程其它缓存用的 {@code level.getGameTime()} 也是同一个思路：单调、不受墙钟影响）。</p>
     */
    private static final long SNAPSHOT_TTL_NANOS = SNAPSHOT_TTL_MS * 1_000_000L;
    /** 单次抽取 / 单槽上限（与 {@code getSlotLimit} 同一口径）。 */
    private static final int MAX_EXTRACT = 64;

    private static final Comparator<ItemResource> ORDER =
        Comparator.comparing((ItemResource resource) -> BuiltInRegistries.ITEM.getKey(resource.item()).toString())
            .thenComparing(resource -> resource.components().toString());

    private final SerializableStorage storage;
    private final StorageRepository repository;

    @Nullable
    private Map<ItemResource, Long> amounts;
    private ResourceKey[] slots = new ResourceKey[RsccDiskSlotCache.WINDOW_SLOTS];
    private long builtAt;

    public RsccDiskItemHandler(final SerializableStorage storage, final StorageRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    /** 供 GUI / 调试：这张盘真实的已用量（通用盘是「物品位」，可能包含流体）。 */
    public long stored() {
        return storage.getStored();
    }

    public StorageRepository repository() {
        return repository;
    }

    // ==================== 快照 ====================

    private void refresh() {
        // 单调时钟：改系统时间不会让快照「永不过期」（见 SNAPSHOT_TTL_NANOS 的说明）
        final long now = System.nanoTime();
        if (amounts != null && now - builtAt <= SNAPSHOT_TTL_NANOS) {
            return;
        }
        final Map<ItemResource, Long> next = new HashMap<>();
        final List<ItemResource> present = new ArrayList<>();
        for (final ResourceAmount amount : storage.getAll()) {
            if (amount.resource() instanceof ItemResource resource) {
                next.merge(resource, amount.amount(), Long::sum);
                present.add(resource);
            }
        }
        present.sort(ORDER);
        this.amounts = next;
        this.slots = RsccDiskSlotCache.bindings(storage, present);
        this.builtAt = now;
    }

    private void invalidateSnapshot() {
        this.amounts = null;
        this.builtAt = 0L;
    }

    // ==================== IItemHandler ====================

    @Override
    public int getSlots() {
        return RsccDiskSlotCache.WINDOW_SLOTS;
    }

    @Override
    public ItemStack getStackInSlot(final int slot) {
        refresh();
        final ResourceKey key = bindingAt(slot);
        if (key == null) {
            return ItemStack.EMPTY;
        }
        final long amount = amounts == null ? 0L : amounts.getOrDefault(key, 0L);
        if (amount <= 0L) {
            return ItemStack.EMPTY;
        }
        return ((ItemResource) key).toItemStack(Math.min(amount, Integer.MAX_VALUE));
    }

    @Override
    public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final ItemResource resource = ItemResource.ofItemStack(stack);
        if (!storage.getType().isAllowed(resource)) {
            // 流体盘 / 关闭混装的通用盘：拒收，原栈退回（remainder 是正常结果，不是错误）。
            return stack;
        }
        if (!simulate) {
            // 先确保本实例拿着的是「共享的」粘性槽位表，否则下面写回的绑定会丢在副本里。
            refresh();
        }
        final long inserted = storage.insert(resource, stack.getCount(),
            simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
        if (simulate) {
            return remainder(stack, inserted);
        }
        invalidateSnapshot();
        if (inserted > 0L && slot >= 0 && slot < slots.length && slots[slot] == null) {
            // 插入到空槽时把这一种资源绑上去（槽里已有别的种类则不改绑定：磁盘级插入仍然成功）。
            slots[slot] = resource;
        }
        return remainder(stack, inserted);
    }

    @Override
    public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        refresh();
        final ResourceKey key = bindingAt(slot);
        if (!(key instanceof ItemResource resource)) {
            return ItemStack.EMPTY;
        }
        final int perOperation = Math.min(MAX_EXTRACT, Math.max(1, resource.item().getDefaultMaxStackSize()));
        final int wanted = Math.min(amount, perOperation);
        final long extracted = storage.extract(resource, wanted,
            simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
        if (extracted <= 0L) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            invalidateSnapshot();
        }
        return resource.toItemStack(extracted);
    }

    @Override
    public int getSlotLimit(final int slot) {
        refresh();
        final ResourceKey key = bindingAt(slot);
        if (key instanceof ItemResource resource) {
            return Math.min(MAX_EXTRACT, Math.max(1, resource.item().getDefaultMaxStackSize()));
        }
        return MAX_EXTRACT;
    }

    @Override
    public boolean isItemValid(final int slot, final ItemStack stack) {
        return !stack.isEmpty() && storage.getType().isAllowed(ItemResource.ofItemStack(stack));
    }

    // ==================== 内部 ====================

    @Nullable
    private ResourceKey bindingAt(final int slot) {
        return slot >= 0 && slot < slots.length ? slots[slot] : null;
    }

    private static ItemStack remainder(final ItemStack stack, final long inserted) {
        if (inserted <= 0L) {
            return stack;
        }
        if (inserted >= stack.getCount()) {
            return ItemStack.EMPTY;
        }
        return stack.copyWithCount(stack.getCount() - (int) inserted);
    }
}
