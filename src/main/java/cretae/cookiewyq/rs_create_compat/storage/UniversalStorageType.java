package cretae.cookiewyq.rs_create_compat.storage;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.limited.LimitedStorage;
import com.refinedmods.refinedstorage.api.storage.tracked.InMemoryTrackedStorageRepository;
import com.refinedmods.refinedstorage.api.storage.tracked.TrackedStorageImpl;
import com.refinedmods.refinedstorage.api.storage.tracked.TrackedStorageRepository;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.api.storage.StorageType;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * 通用储存磁盘的存储类型：可同时存放物品、流体、气体等任意类型资源。
 * 注册 id：rs_create_compat:universal
 */
public final class UniversalStorageType implements StorageType {
    public static final UniversalStorageType INSTANCE = new UniversalStorageType();

    private static final Codec<PlatformResourceKey> RESOURCE_CODEC = ResourceCodecs.CODEC;

    private UniversalStorageType() {
    }

    @Override
    public SerializableStorage create(@Nullable final Long capacity, final Runnable listener) {
        final TrackedStorageRepository trackingRepository = new InMemoryTrackedStorageRepository();
        final TrackedStorageImpl tracked = new TrackedStorageImpl(
            new StorageImpl(),
            trackingRepository,
            System::currentTimeMillis
        );
        return new UniversalLimitedStorage(
            tracked,
            this,
            capacity == null ? Long.MAX_VALUE : capacity,
            trackingRepository,
            listener
        );
    }

    @Override
    public MapCodec<SerializableStorage> getMapCodec(final Runnable listener) {
        final Codec<UniversalStorageData.UniversalStorageResource> resourceCodec =
            RecordCodecBuilder.create(instance -> instance.group(
                RESOURCE_CODEC.fieldOf("resource").forGetter(UniversalStorageData.UniversalStorageResource::resource),
                Codec.LONG.fieldOf("amount").forGetter(UniversalStorageData.UniversalStorageResource::amount)
            ).apply(instance, UniversalStorageData.UniversalStorageResource::new));

        return RecordCodecBuilder.<UniversalStorageData>mapCodec(instance -> instance.group(
            Codec.optionalField("capacity", Codec.LONG, false).forGetter(UniversalStorageData::capacity),
            new ErrorTolerantListCodec<>(resourceCodec).fieldOf("resources").forGetter(UniversalStorageData::resources)
        ).apply(instance, UniversalStorageData::new)).xmap(
            data -> create(data, listener),
            storage -> toData(storage)
        );
    }

    @Override
    public boolean isAllowed(final ResourceKey resource) {
        return true; // 接受任意类型资源（物品、流体、气体……）
    }

    @Override
    public long getDiskInterfaceTransferQuota(final boolean stackUpgrade) {
        // 与物品盘保持一致（1 单位/次，堆叠升级 64 单位/次）
        return stackUpgrade ? 64 : 1;
    }

    private SerializableStorage create(final UniversalStorageData data, final Runnable listener) {
        final SerializableStorage storage = create(data.capacity().orElse(null), listener);
        if (storage instanceof UniversalLimitedStorage universalStorage) {
            data.resources().forEach(universalStorage::load);
        }
        return storage;
    }

    private UniversalStorageData toData(final SerializableStorage storage) {
        final Optional<Long> capacity = storage instanceof LimitedStorage limitedStorage
            ? Optional.of(limitedStorage.getCapacity())
            : Optional.empty();
        final List<UniversalStorageData.UniversalStorageResource> resources = storage.getAll().stream()
            .map(resourceAmount -> new UniversalStorageData.UniversalStorageResource(
                (PlatformResourceKey) resourceAmount.resource(),
                resourceAmount.amount()
            ))
            .toList();
        return new UniversalStorageData(capacity, resources);
    }

    /**
     * 容错列表编解码：单条资源解码失败时跳过，避免整个磁盘数据丢失。
     */
    private static final class ErrorTolerantListCodec<T> implements Codec<List<T>> {
        private final Codec<T> elementCodec;

        private ErrorTolerantListCodec(final Codec<T> elementCodec) {
            this.elementCodec = elementCodec;
        }

        @Override
        public <X> DataResult<Pair<List<T>, X>> decode(final DynamicOps<X> ops, final X input) {
            return ops.getList(input).map(stream -> {
                final List<T> result = new ArrayList<>();
                // getList 的流是一个 Consumer<Consumer<X>>：逐个接收元素
                stream.accept(value -> {
                    // 单条失败仅跳过，不影响整体
                    elementCodec.parse(ops, value).result().ifPresent(result::add);
                });
                return Pair.of(result, ops.empty());
            });
        }

        @Override
        public <X> DataResult<X> encode(final List<T> input, final DynamicOps<X> ops, final X prefix) {
            final ListBuilder<X> builder = ops.listBuilder();
            for (final T t : input) {
                builder.add(elementCodec.encodeStart(ops, t));
            }
            return builder.build(prefix);
        }
    }
}
