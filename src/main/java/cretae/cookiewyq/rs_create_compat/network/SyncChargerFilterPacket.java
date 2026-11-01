package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.ChargerFilterLimits;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C：范围充电器「充电过滤」的<b>权威快照</b>（黑/白名单 + 四类名单 + 已知玩家候选池 + 方块候选池）。
 *
 * <h2>为什么整份下发、而不是增量同步</h2>
 * <p>过滤界面（{@code ChargerFilterScreen}）是<b>全量覆盖</b>的编辑模型：玩家每次勾选 / 添加 /
 * 删除都会把整份配置发回服务端（{@link SetChargerFilterPacket}）。既然写是全量，读也就必须
 * 是全量 —— 否则「界面基于旧快照做的下一次全量覆盖」会把别人刚改的条目悄无声息地抹掉。
 * 一份快照同时把「当前配置」和「可勾选的候选（已知玩家）」给到客户端，界面因此不需要
 * 再问第二次。</p>
 *
 * <h2>knownPlayers 为什么要带 online</h2>
 * <p>玩家名单里既有「本存档见过的人」（落盘、可能离线），也有手动按名字/UUID 加进来的条目。
 * 界面必须能把两者区分开显示（离线的人不能用「在线」绿点误导玩家），而在线状态是
 * <b>服务端此刻的事实</b>，客户端无从自查 —— 因此随快照一起算好下发。</p>
 *
 * <h2>第 110 轮：为什么还要多一个 seenBlocks</h2>
 * <p>物品两页的候选池客户端能自己扫（{@code ChargerFilterCandidates}：谁能充电只取决于注册表 +
 * 能力系统，两端一致）；但「<b>本机范围内有哪些可充方块</b>」是服务端世界的事实
 * （区块加载状态 / 方块实体是否存在 / 距离），客户端无从自查。因此由方块实体在扫描时收集、
 * 随快照下发（有界，见 {@code ChargerFilterLimits.MAX_BLOCK_ENTRIES}）。</p>
 *
 * <h2>线程 / 专用服务端安全</h2>
 * <p>本类只引用双端都存在的类型（{@code ResourceLocation} / {@code UUID} / 载荷 record），
 * 处理体只把包转交给 {@link ClientPayloadHooks}；真正的界面代码在
 * {@code client/ClientPayloadSink} 里，专用服务端加载本类时不会链接任何
 * {@code net.minecraft.client.*} 类型。</p>
 *
 * @param containerId   发起请求的那张菜单的 id（客户端只刷新 id 对得上的界面）
 * @param whitelist     false = 黑名单（名单内不充）；true = 白名单（只充名单内）
 * @param droppedItems  掉落物名单（物品注册名）
 * @param carriedItems  玩家身上物品名单（物品注册名）
 * @param blocks        「世界中的方块」名单（方块注册名，第 110 轮）
 * @param seenBlocks    本机范围内检测到的、有 FE 能力的方块（候选池，第 110 轮）
 * @param playerNames   玩家名名单（已小写；服务端是权威清洗点）
 * @param playerIds     玩家 UUID 名单
 * @param knownPlayers  本存档曾经进过服的玩家（名字 + UUID + 此刻是否在线）
 */
public record SyncChargerFilterPacket(int containerId, boolean whitelist,
                                     List<ResourceLocation> droppedItems,
                                     List<ResourceLocation> carriedItems,
                                     List<ResourceLocation> blocks,
                                     List<ResourceLocation> seenBlocks,
                                     List<String> playerNames,
                                     List<UUID> playerIds,
                                     List<KnownPlayer> knownPlayers)
    implements CustomPacketPayload {

    /** 已知玩家的一条只读视图（在线状态是服务端组包那一刻的事实，不落盘）。 */
    public record KnownPlayer(String name, UUID id, boolean online) {
        public static final StreamCodec<RegistryFriendlyByteBuf, KnownPlayer> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, KnownPlayer::name,
                UUIDUtil.STREAM_CODEC, KnownPlayer::id,
                ByteBufCodecs.BOOL, KnownPlayer::online,
                KnownPlayer::new
            );
    }

    public static final Type<SyncChargerFilterPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "sync_charger_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncChargerFilterPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.BOOL.encode(buf, packet.whitelist());
                ResourceCodecList.encode(buf, packet.droppedItems());
                ResourceCodecList.encode(buf, packet.carriedItems());
                // 第 110 轮：方块名单与方块候选池（两者都按 MAX_BLOCK_ENTRIES 截断）
                encodeBlocks(buf, packet.blocks());
                encodeBlocks(buf, packet.seenBlocks());
                encodeNames(buf, packet.playerNames());
                encodeUuids(buf, packet.playerIds());
                encodeKnownPlayers(buf, packet.knownPlayers());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean whitelist = ByteBufCodecs.BOOL.decode(buf);
                final List<ResourceLocation> dropped = ResourceCodecList.decode(buf);
                final List<ResourceLocation> carried = ResourceCodecList.decode(buf);
                final List<ResourceLocation> blocks = decodeBlocks(buf);
                final List<ResourceLocation> seenBlocks = decodeBlocks(buf);
                final List<String> names = decodeNames(buf);
                final List<UUID> ids = decodeUuids(buf);
                final List<KnownPlayer> known = decodeKnownPlayers(buf);
                return new SyncChargerFilterPacket(containerId, whitelist, dropped, carried,
                    blocks, seenBlocks, names, ids, known);
            }
        );

    // ==================== 名单编解码（C2S 的 SetChargerFilterPacket 与本包共用） ====================
    //
    // 第 99 轮（用户反馈「第三个改了他」）在这里补上**硬上限**，两侧写法完全对称：
    //
    //   编码：先把名单截到 ChargerFilterLimits 给的上限，再写条数 —— 于是「客户端能发多大」
    //         在本工程自己的客户端上是恒定的，不依赖界面是否有 bug。
    //   解码：**照声明的条数把字节全读完，但只保留前 N 条** —— 不能「读够 N 条就 break」，
    //         那会让后续字段错位（名字被当成 UUID 去读，整包解析成随机数据）；
    //         也不能 `new ArrayList<>(declared)` —— declared 是对端给的，`2^31-1` 直接 OOM。
    //         两者结合：内存上界恒为前端列表上限，工作量上界由「包里真的有那么多字节」兜住。
    //
    // 为什么内存上界重要：这些包在**网络线程**解码（NeoForge payload 解码早于 enqueueWork），
    // 在这里 OOM 是整个服务端陪葬，不是「某个玩家界面出错」。

    /** 编码玩家名名单（截断到 {@link ChargerFilterLimits#MAX_PLAYER_ENTRIES}）。 */
    static void encodeNames(final RegistryFriendlyByteBuf buf, final List<String> rawNames) {
        final List<String> names = ChargerFilterLimits.clamp(rawNames,
            ChargerFilterLimits.MAX_PLAYER_ENTRIES);
        ByteBufCodecs.VAR_INT.encode(buf, names.size());
        for (final String name : names) {
            ByteBufCodecs.STRING_UTF8.encode(buf, name);
        }
    }

    /** 解码玩家名名单（读完全部声明条目、只保留前 {@link ChargerFilterLimits#MAX_PLAYER_ENTRIES} 条）。 */
    static List<String> decodeNames(final RegistryFriendlyByteBuf buf) {
        final int declared = ByteBufCodecs.VAR_INT.decode(buf);
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < declared; i++) {
            final String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            if (names.size() < ChargerFilterLimits.MAX_PLAYER_ENTRIES) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /** 编码玩家 UUID 名单（截断到 {@link ChargerFilterLimits#MAX_PLAYER_ID_ENTRIES}）。 */
    static void encodeUuids(final RegistryFriendlyByteBuf buf, final List<UUID> rawIds) {
        final List<UUID> ids = ChargerFilterLimits.clamp(rawIds,
            ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES);
        ByteBufCodecs.VAR_INT.encode(buf, ids.size());
        for (final UUID id : ids) {
            UUIDUtil.STREAM_CODEC.encode(buf, id);
        }
    }

    /** 解码玩家 UUID 名单（读完全部声明条目、只保留前 {@link ChargerFilterLimits#MAX_PLAYER_ID_ENTRIES} 条）。 */
    static List<UUID> decodeUuids(final RegistryFriendlyByteBuf buf) {
        final int declared = ByteBufCodecs.VAR_INT.decode(buf);
        final List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < declared; i++) {
            final UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
            if (ids.size() < ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 编码「已知玩家」候选池（截断到 {@link ChargerFilterLimits#MAX_KNOWN_PLAYERS}）。
     * <p>这份数据是<b>服务端自己</b>从 SavedData 里组的，随存档无限增长（来一个新玩家 +1）。
     * 截断只影响「界面能一键勾选到谁」——手动输入名字 / UUID 仍然完全可用，
     * 所以这里不会让任何玩家「配置不到」。</p>
     */
    static void encodeKnownPlayers(final RegistryFriendlyByteBuf buf,
                                   final List<KnownPlayer> rawKnown) {
        final List<KnownPlayer> known = ChargerFilterLimits.clamp(rawKnown,
            ChargerFilterLimits.MAX_KNOWN_PLAYERS);
        ByteBufCodecs.VAR_INT.encode(buf, known.size());
        for (final KnownPlayer entry : known) {
            KnownPlayer.STREAM_CODEC.encode(buf, entry);
        }
    }

    /** 解码「已知玩家」候选池（读完全部声明条目、只保留前 {@link ChargerFilterLimits#MAX_KNOWN_PLAYERS} 条）。 */
    static List<KnownPlayer> decodeKnownPlayers(final RegistryFriendlyByteBuf buf) {
        final int declared = ByteBufCodecs.VAR_INT.decode(buf);
        final List<KnownPlayer> known = new ArrayList<>();
        for (int i = 0; i < declared; i++) {
            final KnownPlayer entry = KnownPlayer.STREAM_CODEC.decode(buf);
            if (known.size() < ChargerFilterLimits.MAX_KNOWN_PLAYERS) {
                known.add(entry);
            }
        }
        return List.copyOf(known);
    }

    /**
     * 编码方块注册名列表（<b>名单与候选池共用</b>，第 110 轮）。
     *
     * <p>为什么不直接复用 {@link ResourceCodecList#encode}：那个方法的闸门是
     * {@code MAX_ITEM_ENTRIES}，而方块这一类有自己的常量（{@code MAX_BLOCK_ENTRIES}）——
     * 两个数字现在相同，但它们是<b>两件事</b>（物品名单 / 方块名单 + 候选池）。共用方法会让
     * 「以后单独调整方块上限」变成一次静默的行为变更；这里复制的是<b>三段短代码</b>，
     * 换来的是「每个上限写在自己那条路上」。</p>
     *
     * <p>null / 空一律编码成 0 条：编码器是最后一道防线，绝不因为调用方给 null 就抛 NPE
     * （那会让一个界面 bug 变成「发包时崩客户端」）。</p>
     */
    static void encodeBlocks(final RegistryFriendlyByteBuf buf, final List<ResourceLocation> rawIds) {
        final List<ResourceLocation> ids = ChargerFilterLimits.clamp(rawIds,
            ChargerFilterLimits.MAX_BLOCK_ENTRIES);
        ByteBufCodecs.VAR_INT.encode(buf, ids.size());
        for (final ResourceLocation id : ids) {
            ResourceLocation.STREAM_CODEC.encode(buf, id);
        }
    }

    /**
     * 解码方块注册名列表（读完全部声明条目、只保留前 {@link ChargerFilterLimits#MAX_BLOCK_ENTRIES} 条）。
     *
     * <p>与 {@code ResourceCodecList#decode} 同样两条硬规则：<b>不预分配</b>（条数由对端给，
     * {@code new ArrayList<>(declared)} 会被 {@code 2^31-1} 的恶意包直接 OOM）、
     * <b>不提前 break</b>（读一半就停会让后续字段错位，整包解析成随机数据）。</p>
     */
    static List<ResourceLocation> decodeBlocks(final RegistryFriendlyByteBuf buf) {
        final int declared = ByteBufCodecs.VAR_INT.decode(buf);
        // 变量名刻意不叫 ids：第 99 轮自检用「把 `new ArrayList<>()` 改成
        // `new ArrayList<>(declared)`」做反例自证，那段 `replace(…, 1)` 命中文件里**第一处**
        // 同一行文本。这里若与 ResourceCodecList.decode 同名，反例会改成替换这一段，
        // 于是「按对端条数预分配」那个 OOM 洞的反例就测不到真正的目标方法了。
        final List<ResourceLocation> kept = new ArrayList<>();
        for (int i = 0; i < declared; i++) {
            final ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
            if (kept.size() < ChargerFilterLimits.MAX_BLOCK_ENTRIES) {
                kept.add(id);
            }
        }
        return List.copyOf(kept);
    }

    public static void handle(final SyncChargerFilterPacket packet, final IPayloadContext ctx) {
        // 客户端专属处理在 client/ClientPayloadSink（本类保持只依赖双端类型，专用服务端安全）
        ctx.enqueueWork(() -> ClientPayloadHooks.get().openChargerFilter(packet));
    }

    /**
     * 从方块实体的过滤配置组一份快照（<b>组包口径的唯一实现</b>）。
     *
     * <p>为什么要有这个工厂、而不是让方块实体自己拼
     * {@code new SyncChargerFilterPacket(..., filter.whitelist(), ...)}：</p>
     * <ol>
     *     <li>「快照里放哪些字段、什么顺序、要不要排序」是<b>协议</b>的事，只该有一个出处
     *     （客户端解码端也只能有一种形状）；</li>
     *     <li>方块实体只负责「把权威配置交给协议层」，因此它的源码里不会出现黑/白名单字段名 ——
     *     「名单怎么判定 / 怎么读」全部留在 {@code support/ChargingFilter}，
     *     方块实体不再参与这个话题（本轮自检会盯「BE 里不得出现 whitelist 字面量」）。</li>
     * </ol>
     *
     * @param knownPlayers 已知玩家候选池（名字 + UUID + 在线状态；在线状态由服务端按当时世界算好）
     * @param seenBlocks   本机范围内检测到的可充方块 id（第 110 轮；服务端世界的事实，客户端无从自查）
     */
    public static SyncChargerFilterPacket of(final int containerId,
                                             final cretae.cookiewyq.rs_create_compat.support.ChargingFilter filter,
                                             final List<KnownPlayer> knownPlayers,
                                             final List<ResourceLocation> seenBlocks) {
        return new SyncChargerFilterPacket(containerId, filter.whitelist(),
            filter.sortedDroppedItems(), filter.sortedCarriedItems(), filter.sortedBlocks(),
            seenBlocks == null ? List.of() : List.copyOf(seenBlocks),
            filter.sortedPlayerNames(), filter.sortedPlayerIds(),
            knownPlayers == null ? List.of() : knownPlayers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 「资源注册名列表」的编解码小工具。
     * <p>为什么单独抽出来而不是内联两遍：掉落物与玩家身上物品是<b>两个独立的名单</b>，
     * 但它们的线格式必须逐字一致 —— 复制粘贴两段几乎一样的循环，下一次改动就只会改一处。</p>
     */
    static final class ResourceCodecList {
        private ResourceCodecList() {
        }

        /**
         * 编码物品注册名名单：<b>先截到硬上限、再写条数</b>。
         * <p>null / 空一律编码成 0 条：编码器是最后一道防线，绝不因为调用方给 null 就抛 NPE
         * （那会让一个界面 bug 变成「发包时崩客户端」）。</p>
         */
        static void encode(final RegistryFriendlyByteBuf buf, final List<ResourceLocation> rawIds) {
            final List<ResourceLocation> ids = ChargerFilterLimits.clamp(rawIds,
                ChargerFilterLimits.MAX_ITEM_ENTRIES);
            ByteBufCodecs.VAR_INT.encode(buf, ids.size());
            for (final ResourceLocation id : ids) {
                ResourceLocation.STREAM_CODEC.encode(buf, id);
            }
        }

        /**
         * 解码物品注册名名单：<b>按声明条数读完、只保留前 {@link ChargerFilterLimits#MAX_ITEM_ENTRIES} 条</b>。
         *
         * <p>两个都不能省：</p>
         * <ul>
         *     <li>不预分配（第 98 轮已修）：{@code count} 由客户端给，{@code new ArrayList<>(count)}
         *     会被 {@code count = 2^31-1} 的恶意包直接 OOM；</li>
         *     <li>不提前 break（第 99 轮补）：只是不预分配的话，一包 20 万条仍会<b>全部</b>解析进
         *     列表并一路进到方块实体落盘。现在解析照做（否则后续字段错位），但只留前 N 条，
         *     内存上界与包里写了什么无关。</li>
         * </ul>
         */
        static List<ResourceLocation> decode(final RegistryFriendlyByteBuf buf) {
            final int declared = ByteBufCodecs.VAR_INT.decode(buf);
            final List<ResourceLocation> ids = new ArrayList<>();
            for (int i = 0; i < declared; i++) {
                final ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
                if (ids.size() < ChargerFilterLimits.MAX_ITEM_ENTRIES) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        }
    }
}
