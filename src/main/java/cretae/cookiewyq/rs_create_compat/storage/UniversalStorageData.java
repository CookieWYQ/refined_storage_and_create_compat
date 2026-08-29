package cretae.cookiewyq.rs_create_compat.storage;

import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;

import java.util.List;
import java.util.Optional;

/**
 * 通用储存磁盘的序列化数据结构：容量 + 资源列表。
 */
record UniversalStorageData(Optional<Long> capacity, List<UniversalStorageResource> resources) {

    record UniversalStorageResource(PlatformResourceKey resource, long amount) {
    }
}
