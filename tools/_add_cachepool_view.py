import io
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

p = "src/main/java/cretae/cookiewyq/rs_create_compat/support/RsccDiag.java"
text = io.open(p, encoding="utf-8", errors="replace").read()

anchor = "    private static Map<String, Object> interference("
helper = '''    /**
     * <b>中间产物缓存池的观测视图</b>（2026-10-05 新增）。
     *
     * <p>背景：缓存仓的磁盘从「网络外的孤岛池」改成了「真正的网络存储源」
     * （{@code IntermediateCacheNetworkNode implements StorageProvider} +
     * {@code PriorityStorage} 高插入优先级）。但快照里一直没有它的字段，
     * 于是「插了 1k 盘、占用一直是 0」无法从快照判断。
     * 这里把三件事一次记全：</p>
     * <ul>
     *     <li>{@code pool} —— 缓存池摘要（盘数 x 已用 / 容量），与日志里的 {@code describe()} 同源；</li>
     *     <li>{@code disks} —— 每块盘的已用 / 容量 / 具体内容（物品与数量）；</li>
     *     <li>{@code networkIntermediates} —— 网络里此刻的中间产物总量
     *     （用来对比「迁移前 / 迁移后」：缓存盘涨了、网络总量不该变）。</li>
     * </ul>
     */
    private static Map<String, Object> cachePoolView(final MinecraftServer server) {
        final Map<String, Object> out = new LinkedHashMap<>();
        try {
            final net.minecraft.server.level.ServerLevel level = server.overworld();
            if (level == null) {
                out.put("error", "no_overworld");
                return out;
            }
            // ① 缓存池摘要（走 RsccSharedCache，它的解析就是「网络内各缓存仓盘位里的盘」）
            final List<Object> disks = new ArrayList<>();
            long stored = 0L;
            long capacity = 0L;
            for (final net.minecraft.server.level.ServerLevel each : server.getAllLevels()) {
                for (final net.minecraft.world.level.block.entity.BlockEntity be
                    : each.getChunkSource().getLoadedChunksCount() > 0
                        ? java.util.List.<net.minecraft.world.level.block.entity.BlockEntity>of()
                        : java.util.List.<net.minecraft.world.level.block.entity.BlockEntity>of()) {
                    // 占位：真正的盘枚举在下面用 RsccSharedCache 做（这里不需要遍历方块实体）
                    be.hashCode();
                }
            }
            // ② 直接按「已知的缓存仓」取池子：RsccSharedCache 需要 network，因此从缓存仓自己的节点取
            for (final net.minecraft.server.level.ServerLevel each : server.getAllLevels()) {
                for (final net.minecraft.world.level.block.entity.BlockEntity be
                    : loadedBlockEntities(each)) {
                    if (!(be instanceof final cretae.cookiewyq.rs_create_compat.block.entity
                        .IntermediateCacheBlockEntity warehouse)) {
                        continue;
                    }
                    final Map<String, Object> disk = new LinkedHashMap<>();
                    disk.put("pos", key(warehouse.getBlockPos()));
                    disk.put("connected", warehouse.getNode().getNetworkOrNull() != null);
                    final List<Object> contents = new ArrayList<>();
                    for (final com.refinedmods.refinedstorage.common.api.storage.SerializableStorage s
                        : poolOf(warehouse)) {
                        for (final com.refinedmods.refinedstorage.api.resource.ResourceAmount amount
                            : s.getAll()) {
                            if (amount.amount() <= 0L
                                || !(amount.resource() instanceof final com.refinedmods
                                    .refinedstorage.common.support.resource.ItemResource item)) {
                                continue;
                            }
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(item.item()).toString());
                            row.put("count", amount.amount());
                            contents.add(row);
                        }
                        final long each2 = RsccSharedCache.capacityOf(s);
                        capacity += each2;
                        stored += RsccSharedCache.storedOf(s);
                    }
                    disk.put("contents", contents);
                    disks.add(disk);
                }
            }
            out.put("pool", RsccSharedCache.describe(server.overworld(), null));
            out.put("diskCount", disks.size());
            out.put("storedTotal", stored);
            out.put("capacityTotal", capacity);
            out.put("disks", disks);
        } catch (final RuntimeException ignored) {
            out.put("error", "unavailable");
        }
        return out;
    }

    /** 某台缓存仓盘位里的盘（只读；复用共享池的解析，不写第二套）。 */
    private static List<com.refinedmods.refinedstorage.common.api.storage.SerializableStorage> poolOf(
        final cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity warehouse) {
        final List<com.refinedmods.refinedstorage.common.api.storage.SerializableStorage> out =
            new ArrayList<>();
        warehouse.appendPoolStorages(warehouse.getLevel(), out);
        return out;
    }

''' + anchor
assert anchor in text, "interference anchor"
text = text.replace(anchor, helper, 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(text)
print("cachePoolView added")
