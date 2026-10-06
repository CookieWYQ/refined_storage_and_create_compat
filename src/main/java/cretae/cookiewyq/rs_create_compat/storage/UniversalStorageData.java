package cretae.cookiewyq.rs_create_compat.storage;

import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;

import java.util.List;
import java.util.Optional;

/**
 * 通用储存磁盘的序列化数据结构：容量 + 资源列表。
 * <p>
 * 结构对齐 RS 原版 {@code StorageCodecs.StorageData/StorageResource}（package-private 无法直接复用，
 * 故本地复制一份）；每条资源带 {@code changed}（最后修改者/时间），反序列化时恢复
 * TrackedResource，保证网格 GUI 的"最后修改"信息不丢失。
 */
record UniversalStorageData(Optional<Long> capacity, List<UniversalStorageResource> resources) {

    record UniversalStorageResource(PlatformResourceKey resource, long amount,
                                    Optional<StorageChangedByAt> changed) {
    }

    record StorageChangedByAt(String changedBy, long changedAt) {
    }
}
