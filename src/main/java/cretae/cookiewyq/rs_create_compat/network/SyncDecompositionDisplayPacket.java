package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.DecompositionDisplayEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：「JEI 展示用的降解条目」整表快照 —— 让 JEI 的「降解发电」分类页在<b>专用服务器</b>上也有数据。
 *
 * <h2>为什么必须下发（而不是让客户端自己加载数据包）</h2>
 * <p>降解表由 {@code data/<namespace>/decomposition/*.json</code> 驱动，加载点是
 * {@link cretae.cookiewyq.rs_create_compat.support.DecompositionDataLoader}（
 * {@code AddReloadListenerEvent}）—— <b>只有服务端会跑</b>。专用服务器上客户端既拿不到服务端的
 * 数据包文件、也没有任何接口去问，于是客户端那张表是空的：JEI 分类页会<b>一条配方都没有</b>，
 * 而且不报错（最难查的那种「静默失效」）。</p>
 * <p>本包就是这条数据通道：服务端在 {@code OnDatapackSyncEvent}（进服 + 每次 {@code /reload}）
 * 把 {@link cretae.cookiewyq.rs_create_compat.support.DecompositionTable#displayEntries()} 展开好的
 * 条目发下来，客户端写进
 * {@link cretae.cookiewyq.rs_create_compat.support.DecompositionDisplayCache}，JEI 只读那份镜像。</p>
 *
 * <h2>为什么载荷里是「字符串 id」而不是物品 / 配方对象</h2>
 * <p>注册表对象不能直接过网。条目里只放物品<b>注册 id 字符串</b>与数值，客户端用
 * {@code BuiltInRegistries.ITEM.getOptional(id)} 重新解析 —— 于是两端看到的永远是同一套 id，
 * 不会出现「服务端能显示、客户端解析失败」的隐性不一致。
 * 另外 JEI 在<b>客户端</b>是可选依赖：本包属于 {@code network} 包，<b>不得</b>出现任何 JEI 类型
 * （载荷类在注册期就会被专用服务端加载并链接，见 {@link ClientPayloadHooks} 的类文档）。</p>
 *
 * <h2>形状与上限</h2>
 * <p>{@code entries[×N]}，每条 =
 * {@code key(utf8) + items(列表) + time(varint) + energy(varint) + results(列表)}，
 * 每个 result = {@code item(utf8) + count(varint) + chance(float)}。
 * 解码侧对「保留的条目数」有上限（防一个坏包把客户端内存撑爆）；超出的条目<b>仍会被完整读出</b>
 * （否则流会错位），只是不进入镜像。</p>
 */
public record SyncDecompositionDisplayPacket(List<DecompositionDisplayEntry> entries)
    implements CustomPacketPayload {

    public static final Type<SyncDecompositionDisplayPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_decomposition_display"));

    /** 单包最多保留多少条（数据包正常只有几十条；上限只是防御）。 */
    private static final int MAX_ENTRIES = 4096;
    /** 单条最多保留多少个物品 id（tag 展开后的组可能很大，例如 {@code minecraft:leaves}）。 */
    private static final int MAX_ITEMS = 4096;
    /** 单条最多保留多少个副产物（与 JEI 页面能放的格子数无关，这里只防坏包）。 */
    private static final int MAX_RESULTS = 64;

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncDecompositionDisplayPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> write(buf, packet.entries()),
            buf -> new SyncDecompositionDisplayPacket(read(buf))
        );

    public SyncDecompositionDisplayPacket {
        entries = entries == null || entries.isEmpty()
            ? List.of()
            : List.copyOf(entries.stream().filter(java.util.Objects::nonNull).toList());
    }

    public static void handle(final SyncDecompositionDisplayPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        // 只写客户端只读镜像，任何界面动作都在客户端主线程做（与其它 S2C 数据包一致）。
        ctx.enqueueWork(() -> ClientPayloadHooks.get().syncDecompositionDisplay(packet));
    }

    // ==================== 编解码（手写，便于逐字段加界，且不引入 composite 的泛型噪音） ====================

    private static void write(final RegistryFriendlyByteBuf buf, final List<DecompositionDisplayEntry> entries) {
        ByteBufCodecs.VAR_INT.encode(buf, entries.size());
        for (final DecompositionDisplayEntry entry : entries) {
            ByteBufCodecs.STRING_UTF8.encode(buf, entry.key());
            ByteBufCodecs.VAR_INT.encode(buf, entry.items().size());
            for (final String item : entry.items()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, item);
            }
            ByteBufCodecs.VAR_INT.encode(buf, entry.time());
            ByteBufCodecs.VAR_INT.encode(buf, entry.energy());
            ByteBufCodecs.VAR_INT.encode(buf, entry.results().size());
            for (final DecompositionDisplayEntry.Result result : entry.results()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, result.item());
                ByteBufCodecs.VAR_INT.encode(buf, result.count());
                ByteBufCodecs.FLOAT.encode(buf, result.chance());
            }
        }
    }

    private static List<DecompositionDisplayEntry> read(final RegistryFriendlyByteBuf buf) {
        final int entryCount = Math.max(0, ByteBufCodecs.VAR_INT.decode(buf));
        final List<DecompositionDisplayEntry> entries = new ArrayList<>(Math.min(entryCount, MAX_ENTRIES));
        for (int i = 0; i < entryCount; i++) {
            final String key = ByteBufCodecs.STRING_UTF8.decode(buf);
            final int itemCount = Math.max(0, ByteBufCodecs.VAR_INT.decode(buf));
            final List<String> items = new ArrayList<>(Math.min(itemCount, MAX_ITEMS));
            for (int j = 0; j < itemCount; j++) {
                final String item = ByteBufCodecs.STRING_UTF8.decode(buf);
                if (j < MAX_ITEMS) {
                    items.add(item);
                }
            }
            final int time = ByteBufCodecs.VAR_INT.decode(buf);
            final int energy = ByteBufCodecs.VAR_INT.decode(buf);
            final int resultCount = Math.max(0, ByteBufCodecs.VAR_INT.decode(buf));
            final List<DecompositionDisplayEntry.Result> results =
                new ArrayList<>(Math.min(resultCount, MAX_RESULTS));
            for (int j = 0; j < resultCount; j++) {
                final String item = ByteBufCodecs.STRING_UTF8.decode(buf);
                final int count = ByteBufCodecs.VAR_INT.decode(buf);
                final float chance = ByteBufCodecs.FLOAT.decode(buf);
                if (j < MAX_RESULTS) {
                    results.add(new DecompositionDisplayEntry.Result(item, count, chance));
                }
            }
            if (i < MAX_ENTRIES) {
                entries.add(new DecompositionDisplayEntry(key, items, time, energy, results));
            }
        }
        return entries;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
