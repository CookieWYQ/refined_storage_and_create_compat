package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 执行舱的「物品存储」统一视图 = <b>内部存储 + 网络内共享的中间产物缓存池</b>。
 *
 * <h2>为什么做成一个 IItemHandler 视图，而不是「换个地方放东西」</h2>
 * 执行舱里所有读 / 写物品的地方（认领备料、喂料、回网、全自动收回、拆方块掉落、对外物流能力、
 * 诊断统计）都是「按槽位遍历物品存储」这一种写法。若把缓存池当作一套独立的新存储，
 * 就必须逐个调用点改写，且很容易漏掉某一条路径 —— 那正是用户担心的「东西进了缓存池，
 * 但下一步骤取不到」的死角。这里改为让池子<b>表现为内部存储的延伸槽位</b>
 * （下标：内部槽 → 池子伪槽），所有既有路径一律不改判定逻辑，天然看得见池子里的中间产物。
 *
 * <h2>池子从哪来（用户决定：一个通用的临时储存点）</h2>
 * 磁盘不再插在执行舱上，而是插在<b>中间产物缓存仓</b>（{@code intermediate_cache}）里；
 * 该仓通过线缆接入 RS 网络后，网络内<b>所有</b>执行舱共用它的磁盘，见 {@link RsccSharedCache}。
 * <b>没有缓存仓时</b>（或本仓未接入那个网络）池子为空表，本视图退化成「只有内部存储」，
 * 与改造前的行为逐字一致：不报错、不打日志。
 *
 * <h2>槽位语义（与 {@link RsccUnboundedItemStorage} 保持同一口径）</h2>
 * <ul>
 *     <li><b>内部存储</b>：忽略传入下标、任意位置语义（所以 1 格外部循环调用照样正确）；</li>
 *     <li><b>池子</b>：一个「种类」拆成若干最多 {@value #DISK_SLOT_LIMIT} 件的伪槽位（下标 = 内部槽数 +
 *     池子伪槽下标），因此任何一个伪槽位的件数都落在 {@code int} 范围内，不会出现「超大堆叠」
 *     传进机器容器导致溢出；</li>
 *     <li><b>写入顺序</b>：先内部（既有行为，容量语义不变）→ 内部装满后余量按池子顺序进盘；
 *     <b>读出顺序</b>：内部在前、池子在后（与写入顺序一致，先来的先走）。</li>
 * </ul>
 *
 * <h2>守恒（绝不复制 / 销毁）</h2>
 * 磁盘内容存在 RS 的 {@code StorageRepository} 里，通过磁盘物品自带的 UUID 解析；
 * 本类只做「查 / 插 / 抽」，任何一步的失败都原样返回（插入返回放不下的余量、抽取返回空栈），
 * 绝不凭空生成、也绝不丢弃：磁盘被取出时，物品随磁盘一起走（UUID 在磁盘物品上）。
 *
 * <h2>为什么要缓存池子伪槽位清单</h2>
 * 一块磁盘可能存着几百种过渡件（一次订单的中间产物非常多）。每次 {@code getStackInSlot} 都重新
 * {@code getAll()} + 排序的话，按槽位遍历的调用方（喂料 / 收回）会变成「槽数 × 条目数」的平方级开销。
 * 因此这里缓存「排序后的伪槽位清单」，只在<b>池子版本号变化</b>时作废
 * （{@link RsccSharedCache#version()}：任何一次池子写入 / 换盘 / 缓存仓拓扑变化都会 +1）——
 * 缓存只影响「看什么」，真正的插 / 抽永远打到磁盘真实存储上，
 * 所以即使缓存短暂过期也只会表现为「晚一点看见」，绝不产生物品的复制或丢失。
 */
public final class RsccChamberItemStorage implements IItemHandler {
    /** 单个池子伪槽位的最大件数（与 {@link RsccUnboundedItemStorage#STACK_LIMIT} 同值）。 */
    public static final int DISK_SLOT_LIMIT = RsccUnboundedItemStorage.STACK_LIMIT;
    /**
     * 单个「种类」一次最多暴露多少格（{@value #DISK_SLOT_LIMIT} 件 × {@value #CHUNKS_PER_KIND_MAX} 格）。
     * <p><b>为什么要有这个上限</b>：①{@link #DISK_SLOT_LIMIT} 保证任何一个伪槽位的件数都在 {@code int}
     * 且 ≤ 64（既有调用方普遍假定「一格 ≤ 64 件」，例如回网时直接 {@code (int) inserted}）；②再叠一层
     * 「每种最多 N 格」是为了防止「把一块装了几百万件的磁盘插进来」时，按槽位遍历的路径（喂料 / 收回 /
     * 回网）一次要遍历几十万格。超出的部分<b>不会丢</b>：前面的量被取走后，下一次重建会继续暴露出来
     * （每次最多 {@value #CHUNKS_PER_KIND_MAX} 格），因此是「分轮可达」而不是「看不见」。</p>
     */
    private static final int CHUNKS_PER_KIND_MAX = 64;

    /** 内部存储的<b>动态</b>来源（执行舱参与机器集群时会被换成集群共用的那一份，故不能持有实例）。 */
    private final Supplier<RsccUnboundedItemStorage> internal;
    /** 共享缓存池的只读端口（服务端权威；见 {@link RsccSharedCache#PoolSource}）。 */
    private final RsccSharedCache.PoolSource pool;

    // ---------- 池子伪槽位缓存（只影响「看什么」） ----------
    @Nullable
    private List<DiskSlot> cachedSlots;
    @Nullable
    private List<SerializableStorage> cachedStorages;
    private long cachedVersion = Long.MIN_VALUE;

    public RsccChamberItemStorage(final Supplier<RsccUnboundedItemStorage> internal,
                                  final RsccSharedCache.PoolSource pool) {
        this.internal = internal;
        this.pool = pool;
    }

    private RsccUnboundedItemStorage internalStorage() {
        return internal.get();
    }

    /** 当前网络内共享缓存池的全部磁盘存储（无缓存仓 / 未接入网络 → 空表）。 */
    private List<SerializableStorage> poolStorages() {
        return pool.storages();
    }

    /** 池子伪槽位清单（带缓存；见类注释「为什么要缓存」）。 */
    private List<DiskSlot> diskSlots() {
        final List<SerializableStorage> storages = poolStorages();
        final long currentVersion = pool.version();
        if (currentVersion == cachedVersion && storages == cachedStorages && cachedSlots != null) {
            return cachedSlots;
        }
        cachedSlots = storages.isEmpty() ? List.of() : buildSlots(storages);
        cachedStorages = storages;
        cachedVersion = currentVersion;
        return cachedSlots;
    }

    /**
     * 把池子里每一块盘的内容切成「每格 ≤ {@value #DISK_SLOT_LIMIT} 件」的伪槽位。
     * <p>遍历顺序 = {@link RsccSharedCache} 给的池子顺序（缓存仓坐标字典序 → 仓内槽位下标 → 盘内条目按
     * 「物品 id + 数据组件」稳定排序），因此同一份内容每次得到的伪槽位下标完全一致
     * （按槽位遍历 + 随后按槽位抽取的调用方不会错位）。</p>
     */
    private static List<DiskSlot> buildSlots(final List<SerializableStorage> storages) {
        final List<DiskSlot> slots = new ArrayList<>();
        for (final SerializableStorage storage : storages) {
            final List<ResourceAmount> items = new ArrayList<>();
            for (final ResourceAmount amount : storage.getAll()) {
                // 只暴露物品条目：通用磁盘可能同时存着流体 / 气体，那不是「中间产物缓存」的一部分
                if (amount.amount() > 0 && amount.resource() instanceof ItemResource) {
                    items.add(amount);
                }
            }
            // 稳定排序：RS 的存储内部用 HashMap，条目顺序不保证稳定；按「物品 id + 数据组件」定序
            items.sort(Comparator
                .comparing((ResourceAmount amount) ->
                    BuiltInRegistries.ITEM.getKey(((ItemResource) amount.resource()).item()).toString())
                .thenComparing(amount -> String.valueOf(((ItemResource) amount.resource()).components())));
            for (final ResourceAmount amount : items) {
                final ItemResource resource = (ItemResource) amount.resource();
                long left = Math.min(amount.amount(), (long) DISK_SLOT_LIMIT * CHUNKS_PER_KIND_MAX);
                while (left > 0) {
                    final int chunk = (int) Math.min(DISK_SLOT_LIMIT, left);
                    slots.add(new DiskSlot(storage, resource, chunk));
                    left -= chunk;
                }
            }
        }
        return List.copyOf(slots);
    }

    /**
     * <b>只返回执行舱内部存储的槽位数</b>（不再把缓存盘的每一格伪装成槽位）。
     *
     * <h2>2026-10-05 重大修正：为什么必须撤掉这个伪装</h2>
     *
     * <p>旧实现是 {@code internalSlots + diskSlots().size()} —— 把「网络内缓存仓磁盘里的每一格」
     * 当作执行舱自己的槽位暴露出去。后果是一条<b>反向回流</b>的物理通道：模组里所有
     * 「按槽位遍历执行舱、把东西插回网络」的路径
     * （{@code RsccChamberImportStrategy#sweepChamberItems}、
     * {@code SequenceExecutionChamberBlockEntity#flushResidualInputs} /
     * {@code #flushChamberForSuspend}）都会<b>把刚写进缓存盘的中间产物重新抽出来、插回 RS 网络</b>。</p>
     *
     * <p>实测证据（旧版真搬运时期的日志）：连续多轮都是
     * {@code moved=32 poolStored=0->32 poolFree=1024 pool=1x32/1024} ——
     * 一轮内确实写进了盘（0→32），下一轮开头又回到 0。物品没丢，是在
     * <b>缓存盘 ↔ 网络存储</b>之间被这条通道来回搬运，净位移恒为 0。这就是用户看到的
     * 「插了 1k 通用磁盘、占用一直是 0，从来没涨过」。</p>
     *
     * <h2>为什么现在撤掉它是安全的</h2>
     * <p>因为缓存仓的磁盘<b>已经是一个真正的 RS 网络存储源</b>
     * （{@code IntermediateCacheNetworkNode implements StorageProvider}，
     * 且带「只收中间产物 + 高插入优先级 + 负抽出优先级」）：
     * 缓存盘的内容本来就在 {@code StorageNetworkComponent} 里，
     * 终端看得见、网络取得到，<b>根本不需要再经由执行舱的槽位视图</b>。
     * 继续伪装只会制造上面那条往返通道。</p>
     */
    @Override
    public int getSlots() {
        return internalStorage().getSlots();
    }

    @Override
    public ItemStack getStackInSlot(final int slot) {
        final int internalSlots = internalStorage().getSlots();
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        // 缓存盘伪槽位已撤除（见 getSlots 的说明）：越界即「没有这一格」。
        if (slot >= internalSlots) {
            return ItemStack.EMPTY;
        }
        return internalStorage().getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // ① 内部存储优先（既有容量语义一字未改；任意位置语义，忽略下标）
        final ItemStack rest = internalStorage().insertItem(0, stack, simulate);
        if (rest.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // ② 内部装不下的余量按池子顺序进盘（没有缓存仓 / 都是流体盘 → 原样退回，绝不凭空吞下）
        ItemStack left = rest;
        boolean wrote = false;
        for (final SerializableStorage storage : poolStorages()) {
            final long accepted = storage.insert(ItemResource.ofItemStack(left), left.getCount(),
                simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
            if (accepted <= 0) {
                continue;
            }
            wrote = true;
            final long remaining = left.getCount() - accepted;
            if (remaining <= 0) {
                left = ItemStack.EMPTY;
                break;
            }
            left = left.copyWithCount((int) remaining);
        }
        if (wrote && !simulate) {
            // 池子内容变了：版本号 +1，让「伪槽位清单」缓存作废（本类与其它执行舱同时看见新内容）
            RsccSharedCache.poolChanged();
        }
        return left;
    }

    @Override
    public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
        if (amount <= 0 || slot < 0) {
            return ItemStack.EMPTY;
        }
        final int internalSlots = internalStorage().getSlots();
        if (slot < internalSlots) {
            return internalStorage().extractItem(slot, amount, simulate);
        }
        final DiskSlot diskSlot = diskSlotAt(slot - internalSlots);
        if (diskSlot == null) {
            return ItemStack.EMPTY;
        }
        final long got = diskSlot.storage().extract(diskSlot.resource(),
            Math.min(amount, (long) diskSlot.amount()),
            simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
        if (got <= 0) {
            return ItemStack.EMPTY;
        }
        if (!simulate) {
            RsccSharedCache.poolChanged();
        }
        return diskSlot.resource().toItemStack(got);
    }

    @Override
    public int getSlotLimit(final int slot) {
        return slot < internalStorage().getSlots() ? internalStorage().getSlotLimit(slot) : DISK_SLOT_LIMIT;
    }

    @Override
    public boolean isItemValid(final int slot, final ItemStack stack) {
        return stack != null && !stack.isEmpty();
    }

    @Nullable
    private DiskSlot diskSlotAt(final int diskSlotIndex) {
        final List<DiskSlot> slots = diskSlots();
        return diskSlotIndex >= 0 && diskSlotIndex < slots.size() ? slots.get(diskSlotIndex) : null;
    }

    // ==================== 容量 / 统计（＝内部存储 + 共享缓存池） ====================

    /** 物品空间还装得下多少件（内部剩余容量 + 池子里全部盘的剩余容量）。 */
    public long getRemainingCapacity() {
        long remaining = internalStorage().getRemainingCapacity();
        for (final SerializableStorage storage : poolStorages()) {
            remaining += RsccSharedCache.remainingOf(storage);
        }
        return Math.max(0L, remaining);
    }

    /**
     * 某物品在<b>本仓内部存储</b>里的件数（备料判定与诊断用，只读）。
     *
     * <h2>2026-10-06 修复：这里绝不能再把「共享缓存池」算进来</h2>
     * <p>实测快照 {@code 20261006-084256}：</p>
     * <pre>
     *   缓存盘 contents = [{create:unprocessed_obsidian_sheet, 35}]      ← 池子里真有 35 件
     *   chamber@(-16,-60,10) materials = {sheet, target=1, net=0, chamber=35}
     *   chamber@(-16,-60,10) storedItems = []                            ← 仓内其实是空的！
     *   日志：item=unprocessed_obsidian_sheet x16 | event=pull_hold
     *         | to=chamber@(-16,-60,10) | reason=already_enough target=1  ← 它认为自己够了
     * </pre>
     * <p>旧实现把 {@link #diskSlots()}（= 网络里缓存仓磁盘上的每一格）也累加进来，于是
     * {@code countOf(未加工片) = 35} ⇒ 备料闸门判「already_enough」⇒ <b>从不把料拉进本仓</b>
     * ⇒ 输出的件永远推不到机器上（用户实测：「也不过去了，就像是从来没有去冲压舱一样」）。</p>
     *
     * <p><b>为什么口径必须是「只算内部」</b>：{@link #countOf} 回答的是「本仓手上有没有可以直接
     * 推给机器的料」。缓存池是<b>网络存储</b>（{@code getStorage()} 已把盘注册进网络根复合，
     * 见 {@code RsccCacheExposedStorage}），不是本仓的待推缓冲 —— 东西在盘上时，
     * <b>本仓还必须先把它拉进来</b>，那一份拉料动作正是被这个错误计数挡掉的。
     * 这也与 {@link #getSlots()}（已经只返回内部槽位、伪槽位已撤除）保持同一口径。</p>
     *
     * <p>注意与 {@link #getItemCount()} 的区别：那个方法的注释明确要求「把池子算成本仓的缓存」，
     * 供「本仓还压着料吗」的停滞检测使用，语义不同，因此<b>不动</b>。</p>
     */
    public long countOf(final net.minecraft.world.item.Item item) {
        long have = 0;
        final int internalSlots = internalStorage().getSlots();
        for (int i = 0; i < internalSlots; i++) {
            final ItemStack stack = internalStorage().getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) {
                have += stack.getCount();
            }
        }
        return have;
    }

    /** 池子伪槽位（一个种类可能占多格；同一块盘上的一种资源只出现在它自己那一块盘上）。 */
    private record DiskSlot(SerializableStorage storage, ItemResource resource, int amount) {
    }

    /**
     * 内部 + 共享缓存池里现存的<b>物品</b>件数（盘上并存的流体 / 气体按桶折算的部分不计入）。
     * <p>供「本仓还压着料吗」这类只读判定使用（例如序列装配任务的停滞检测）：缓存池既然算本仓的缓存，
     * 就绝不能漏掉它 —— 否则「料都在共享缓存池里」会被误判成「仓里空着」。</p>
     */
    public long getItemCount() {
        final int internalSlots = internalStorage().getSlots();
        long have = 0;
        for (int i = 0; i < internalSlots; i++) {
            have += internalStorage().getStackInSlot(i).getCount();
        }
        for (final DiskSlot diskSlot : diskSlots()) {
            have += diskSlot.amount();
        }
        return have;
    }
}
