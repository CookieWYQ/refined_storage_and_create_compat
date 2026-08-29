package cretae.cookiewyq.rs_create_compat.storage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.AbstractProxyStorage;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.limited.LimitedStorage;
import com.refinedmods.refinedstorage.api.storage.tracked.TrackedResource;
import com.refinedmods.refinedstorage.api.storage.tracked.TrackedStorage;
import com.refinedmods.refinedstorage.api.storage.tracked.TrackedStorageRepository;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.api.storage.StorageType;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.Config;

import java.util.Optional;

/**
 * 通用储存磁盘的存储实现。
 * <p>
 * 容量换算规则（以"物品位"为单位）：
 * <ul>
 *     <li>物品：1 件 = 1 物品位</li>
 *     <li>流体/气体：1 bucket（1000 mB）= 1 物品位（不足 1 桶按 1 桶计）</li>
 * </ul>
 * 可通过配置开关限制"同一磁盘只能存放一种类型"。
 */
public class UniversalLimitedStorage extends AbstractProxyStorage
    implements LimitedStorage, SerializableStorage, TrackedStorage {

    private final UniversalStorageType type;
    private final long capacity;
    private final TrackedStorageRepository trackingRepository;
    private final Runnable listener;

    public UniversalLimitedStorage(final Storage delegate,
                                   final UniversalStorageType type,
                                   final long capacity,
                                   final TrackedStorageRepository trackingRepository,
                                   final Runnable listener) {
        super(delegate);
        this.type = type;
        this.capacity = capacity;
        this.trackingRepository = trackingRepository;
        this.listener = listener;
    }

    @Override
    public StorageType getType() {
        return type;
    }

    @Override
    public long getCapacity() {
        return capacity;
    }

    @Override
    public long getStored() {
        // 实时重算：按"物品位"换算后的占用总量
        long total = 0;
        for (final ResourceAmount resourceAmount : getAll()) {
            total += capacityCost(resourceAmount.resource(), resourceAmount.amount());
        }
        return total;
    }

    @Override
    public long insert(final ResourceKey resource, final long amount, final Action action, final Actor actor) {
        if (!type.isAllowed(resource)) {
            return 0;
        }
        if (!Config.universalDiskAllowMixedTypes && !isSameTypeAsStored(resource)) {
            return 0;
        }
        final long spaceRemaining = capacity - getStored();
        if (spaceRemaining <= 0) {
            return 0;
        }
        // 部分插入：在剩余容量内尽可能多地插入
        final long maxAffordable = affordableAmount(resource, spaceRemaining);
        final long inserted = super.insert(resource, Math.min(amount, maxAffordable), action, actor);
        if (inserted > 0 && action == Action.EXECUTE) {
            listener.run();
        }
        return inserted;
    }

    @Override
    public long extract(final ResourceKey resource, final long amount, final Action action, final Actor actor) {
        final long extracted = super.extract(resource, amount, action, actor);
        if (extracted > 0 && action == Action.EXECUTE) {
            listener.run();
        }
        return extracted;
    }

    @Override
    public Optional<TrackedResource> findTrackedResourceByActorType(final ResourceKey resource,
                                                                    final Class<? extends Actor> actorType) {
        return trackingRepository.findTrackedResourceByActorType(resource, actorType);
    }

    /**
     * 反序列化回填（不触发变更通知）。
     */
    void load(final UniversalStorageData.UniversalStorageResource storageResource) {
        super.insert(storageResource.resource(), storageResource.amount(), Action.EXECUTE, Actor.EMPTY);
    }

    /**
     * 混存开关：若已存在与传入资源类型不同的内容，则拒绝插入。
     */
    private boolean isSameTypeAsStored(final ResourceKey resource) {
        ResourceKey firstStored = null;
        for (final ResourceAmount resourceAmount : getAll()) {
            if (firstStored == null) {
                firstStored = resourceAmount.resource();
            } else if (!isSameCategory(firstStored, resourceAmount.resource())) {
                return false;
            }
        }
        return firstStored == null || isSameCategory(firstStored, resource);
    }

    private static boolean isSameCategory(final ResourceKey a, final ResourceKey b) {
        if (a instanceof PlatformResourceKey platformA && b instanceof PlatformResourceKey platformB) {
            return platformA.getResourceType().equals(platformB.getResourceType());
        }
        return a instanceof ItemResource == b instanceof ItemResource;
    }

    /**
     * 计算插入 amount 单位资源所占用的物品位。
     */
    public static long capacityCost(final ResourceKey resource, final long amount) {
        if (resource instanceof ItemResource) {
            return amount; // 物品：1 件 = 1 物品位
        }
        return Math.max(1, (amount + 999) / 1000); // 流体/气体：1 桶（1000 单位）= 1 物品位
    }

    /**
     * 在剩余 space 个物品位内，最多可插入的资源数量。
     */
    private static long affordableAmount(final ResourceKey resource, final long space) {
        if (resource instanceof ItemResource) {
            return space; // 物品：空间即件数
        }
        return space * 1000; // 流体/气体：1 物品位 = 1000 单位
    }
}
