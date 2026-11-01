package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 范围充电器的「充电过滤」配置：黑名单 / 白名单 + 四类名单
 * （掉落物 / 玩家身上物品 / 世界中的方块 / 玩家）。
 *
 * <h2>为什么黑/白名单的判断只能在这里（而不是散在方块实体里）</h2>
 * <p>「命中名单该放行还是该拦住」只有一条真实语义，但它会被用在<b>四个互不相干的扫描点</b>
 * （{@code chargePlayer} 的背包族、{@code scanItems}、{@code scanItemsInfinite}、
 * {@code chargeBlock}）。只要有一处再写一遍 {@code whitelist ? ... : ...}，就会出现
 * 「黑名单下掉落物被拦、玩家身上物品却放行」这种自相矛盾的机器 —— 而且这种分歧在实机上
 * 极难被玩家说清。因此四个 {@code allows*} 是本类唯一的判定出口，方块实体只调它们。</p>
 *
 * <h2>第 110 轮：为什么多了「世界中的方块」这一类（用户原话）</h2>
 * <p>「<i>你现在有几个类别玩家身上的（物品）以及掉落物，但是还有一个东西你忘了：世界中的方块，
 * 就是放在地上的方块，比如控制器、或者说放在地上的能量立方。这个东西你并没有列出来，
 * 这个类别也是很重要的。</i>」—— 方块充电本来就存在（{@code Config.rangeChargerChargeBlocks}），
 * 但过滤界面只列了三类，{@code chargeBlock} 也没有任何过滤调用，因此「地上的控制器 / 能量立方」
 * 既拦不住也放不回来。本轮补的是<b>第四个判定出口</b> {@link #allowsBlock(Block)}，
 * 语义与物品两条完全一致（同一套 whitelist 开关、同一套「命中即命中」）。</p>
 *
 * <h2>为什么名单里存「物品类型」而不是 ItemStack</h2>
 * <p>用户要的是「哪几种物品不许充」，与 NBT / 耐久 / 数量无关：同一件无线终端无论充到
 * 30% 还是 90%，都还是同一类物品。按 {@link Item} 的注册名存，既能让整套判定保持零分配，
 * 也天然跨存档稳定（NBT 里只写 {@code ResourceLocation}）。方块名单同理，存 {@link Block}
 * 的注册名 —— <b>不是方块物品</b>的注册名：同一个 {@code minecraft:stone} 既是方块也是物品，
 * 但「世界中的方块」这一类的判定对象是方块状态里的 {@code Block}，与手上那件物品无关。</p>
 *
 * <h2>为什么玩家名单同时存名字与 UUID</h2>
 * <p>名字会改、UUID 不会：<b>任一命中即算命中</b>，这样「改名前加进名单的玩家」与
 * 「改名后才加进来的玩家」都能被正确拦住 / 放行。名字一律以<b>小写</b>保存，
 * 避免 {@code Steve} / {@code steve} 被当成两个人（Minecraft 的名字大小写不敏感，
 * 但不同来源拿到的原始大小写并不一致）。</p>
 *
 * <h2>线程 / 侧别</h2>
 * <p>纯数据类，只被服务端方块实体与网络包使用；判定不读 {@code Level}、不查能力、不发包，
 * 因此可以在任意侧构造（客户端只在「乐观显示」时需要它）。</p>
 *
 * <h2>硬上限（第 99 轮补；第 110 轮补方块一类）</h2>
 * <p>四类名单的条数与单条名字的长度都由 {@link ChargerFilterLimits} 统一定义，且本类是
 * <b>最下游的一道闸</b>：{@link #of}（网络包 / 界面全量覆盖）与 {@link #load}（存档 NBT）
 * 都只能通过 {@code addIds} / {@code addBlocks} / {@code addName} / {@code addId} 往里放东西，
 * 超限一律「保留前 N 条、丢弃其余」并<b>每个进程只打一次</b> WARN。三处（网络编解码 + 存档加载 +
 * 本类清洗）共用同一组常量，因此不存在「包里能塞 10 万条、存档却按 1024 条读」这种两套口径。</p>
 */
public final class ChargingFilter {
    /**
     * NBT 结构版本。当前只有 1。
     * <p>写进存档是为了将来能「旧档按旧语义读、新档按新语义读」；现在的读法是
     * 「版本不认识就按缺字段处理」——缺字段 = 默认（黑名单 + 空名单），
     * 这正好等于「什么都没过滤」，是升级到本功能时对旧存档最不惊讶的行为。</p>
     */
    public static final int NBT_VERSION = 1;

    private static final String TAG_VERSION = "v";
    private static final String TAG_WHITELIST = "whitelist";
    private static final String TAG_DROPPED = "dropped";
    private static final String TAG_CARRIED = "carried";
    /**
     * 「世界中的方块」名单（{@link Block} 注册名，第 110 轮新增）。
     * <p><b>缺字段 = 空集 = 什么都不拦</b>：NBT 读取用 {@code tag.getList(TAG_BLOCKS, ...)}，
     * 缺键时它返回空列表，因此旧存档（没有这个键）读出来就是「方块一类不过滤」——
     * 与升级前逐格相同，不会出现「更新后地上的控制器忽然不充了」这种惊吓。</p>
     */
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_NAMES = "names";
    private static final String TAG_IDS = "ids";

    /** false = 黑名单（名单内不充，默认）；true = 白名单（只充名单内）。 */
    private boolean whitelist;
    /** 掉落物名单（物品注册名）。 */
    private final Set<ResourceLocation> droppedItems = new LinkedHashSet<>();
    /** 玩家身上物品名单（物品注册名）。 */
    private final Set<ResourceLocation> carriedItems = new LinkedHashSet<>();
    /**
     * 「世界中的方块」名单（{@link Block} 注册名，第 110 轮）。
     * <p>与物品两条名单是<b>独立</b>的：同一个注册名（例如 {@code minecraft:stone}）在物品名单里
     * 命中不影响方块名单的判定，反之亦然 —— 因为「掉在地上的石头」与「放在地上的石头」
     * 是两个不同类别的目标，用户要的就是按类别分别拦。
     */
    private final Set<ResourceLocation> blocks = new LinkedHashSet<>();
    /** 玩家名名单（一律小写）。 */
    private final Set<String> playerNames = new LinkedHashSet<>();
    /** 玩家 UUID 名单。 */
    private final Set<UUID> playerIds = new LinkedHashSet<>();

    /** 默认档：黑名单 + 空名单 = 什么都不拦（与没有本功能时的行为逐格相同）。 */
    public ChargingFilter() {
    }

    /** 超限告警已打过（见 {@link #warnOverflow()}；静态只是因为它是进程级日志节流）。 */
    private static boolean overflowWarned;

    /**
     * 整体覆盖用的构造入口（网络包 / 存档加载）。
     *
     * <p>为什么不让调用方直接拿 {@link #playerNames()} 这类集合去改：那些集合是
     * <b>只读视图</b>（服务端权威只有方块实体里那一份）。这里集中做一次清洗
     * （去空、去重、名字转小写），「名单里的名字一定是小写」这个不变式就只在一个地方维护。</p>
     *
     * <p><b>第 110 轮</b>：新增方块名单参数。为了让既有调用方（以及第 99 轮自检里
     * 「五参 {@code of}」的真实探针）不被签名变动打断，保留一个五参重载 ——
     * 它<b>不是另一套语义</b>，只是「方块名单为空」的简写，最终仍然落到这个六参实现。</p>
     */
    public static ChargingFilter of(final boolean whitelist,
                                    final Collection<ResourceLocation> droppedItems,
                                    final Collection<ResourceLocation> carriedItems,
                                    final Collection<ResourceLocation> blocks,
                                    final Collection<String> playerNames,
                                    final Collection<UUID> playerIds) {
        final ChargingFilter filter = new ChargingFilter();
        filter.whitelist = whitelist;
        addIds(filter.droppedItems, droppedItems);
        addIds(filter.carriedItems, carriedItems);
        addBlocks(filter.blocks, blocks);
        if (playerNames != null) {
            for (final String name : playerNames) {
                addName(filter.playerNames, name);
            }
        }
        if (playerIds != null) {
            for (final UUID id : playerIds) {
                addId(filter.playerIds, id);
            }
        }
        return filter;
    }

    /**
     * 五参重载：不带方块名单（等价于「方块一类不过滤」）。
     *
     * <p>存在理由：这是第 98～109 轮唯一的构造签名，既有调用方与第 99 轮的真实探针都按五参调用。
     * 让它转调六参实现，而不是在六参里做 {@code null} 分支，是为了让「方块名单怎么清洗」只有
     * 一处代码（{@link #addBlocks}）。</p>
     */
    public static ChargingFilter of(final boolean whitelist,
                                    final Collection<ResourceLocation> droppedItems,
                                    final Collection<ResourceLocation> carriedItems,
                                    final Collection<String> playerNames,
                                    final Collection<UUID> playerIds) {
        return of(whitelist, droppedItems, carriedItems, null, playerNames, playerIds);
    }

    /**
     * 灌入物品注册名名单，<b>硬上限 {@link ChargerFilterLimits#MAX_ITEM_ENTRIES}</b>。
     * <p>上限在这里（而不是只在网络包的解码器里）是第 99 轮的修法：本方法是<b>所有</b>副本来路
     * （C2S 包 / 存档 NBT / 界面快照）的共同出口，把闸门放在最下游，任何绕过网络层的写入口
     * （手改 NBT、别的模组直接调 API、将来新增的包）也都被同一道闸拦住。</p>
     */
    private static void addIds(final Set<ResourceLocation> target,
                               final Collection<ResourceLocation> source) {
        if (source == null) {
            return;
        }
        for (final ResourceLocation id : source) {
            if (!ChargerFilterLimits.addBounded(target, id,
                ChargerFilterLimits.MAX_ITEM_ENTRIES)) {
                warnOverflow();
                return;
            }
        }
    }

    /**
     * 灌入方块注册名名单，<b>硬上限 {@link ChargerFilterLimits#MAX_BLOCK_ENTRIES}</b>。
     *
     * <p>为什么与 {@link #addIds} 分开一个方法而不是给 {@code addIds} 加个上限参数：
     * 两条路径的闸门常量不同（物品 1024 / 方块 1024，但将来任意一侧都可能单独调整），
     * 而第 99 轮自检把「{@code addIds} 用的就是 {@code MAX_ITEM_ENTRIES}」当锚点钉住，
     * 参数化会让那条锚点变成「看不出来到底用的哪个常量」。两份方法各自把常量写在
     * {@code addBounded} 的实参里，读一眼就知道是哪把闸。</p>
     */
    private static void addBlocks(final Set<ResourceLocation> target,
                                  final Collection<ResourceLocation> source) {
        if (source == null) {
            return;
        }
        for (final ResourceLocation id : source) {
            if (!ChargerFilterLimits.addBounded(target, id,
                ChargerFilterLimits.MAX_BLOCK_ENTRIES)) {
                warnOverflow();
                return;
            }
        }
    }

    /**
     * 灌入玩家名：统一小写 + 硬上限 {@link ChargerFilterLimits#MAX_PLAYER_ENTRIES}。
     * <p>「怎么算一个合法名字」也只由 {@link ChargerFilterLimits#normalizeName} 决定
     * （trim / 小写 / 空与超长一律 null）—— 界面侧用的是同一个方法，因此「界面收得下的名字」
     * 与「服务端存得下的名字」永远不会出现两套标准。</p>
     */
    private static void addName(final Set<String> target, final String rawName) {
        final String name = ChargerFilterLimits.normalizeName(rawName);
        if (name == null) {
            return;
        }
        if (!ChargerFilterLimits.addBounded(target, name, ChargerFilterLimits.MAX_PLAYER_ENTRIES)) {
            warnOverflow();
        }
    }

    /** 灌入 UUID 名单，硬上限 {@link ChargerFilterLimits#MAX_PLAYER_ID_ENTRIES}。 */
    private static void addId(final Set<UUID> target, final UUID id) {
        if (!ChargerFilterLimits.addBounded(target, id, ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES)) {
            warnOverflow();
        }
    }

    /**
     * 超限告警（<b>每个 JVM 只打一次</b>）。
     * <p>为什么只打一次：名单是真的超长时，每一个 tick 的界面刷新 / 每次勾选都会走一遍清洗，
     * 逐条打 WARN 会把日志刷爆（本工程刚被「日志淹没」坑过）。一次 INFO 足够让服主知道
     * 「有配置被截断了」，而且截断方向是「丢尾部」，不会静默改变已有条目的语义。</p>
     */
    private static void warnOverflow() {
        if (overflowWarned) {
            return;
        }
        overflowWarned = true;
        com.mojang.logging.LogUtils.getLogger().warn(
            "rs_create_compat: charging filter list exceeded hard caps "
                + "(items<={}, blocks<={}, players<={}, playerIds<={}); extra entries were dropped",
            ChargerFilterLimits.MAX_ITEM_ENTRIES, ChargerFilterLimits.MAX_BLOCK_ENTRIES,
            ChargerFilterLimits.MAX_PLAYER_ENTRIES,
            ChargerFilterLimits.MAX_PLAYER_ID_ENTRIES);
    }

    /** 深拷贝（方块实体对外只读出口 / 界面快照都用它，绝不把内部集合交出去）。 */
    public ChargingFilter copy() {
        final ChargingFilter copy = new ChargingFilter();
        copy.copyFrom(this);
        return copy;
    }

    /**
     * 用 {@code other} 的内容整体覆盖自己。
     * <p>为什么需要它（而不是直接换引用）：方块实体里的字段是 {@code final}（唯一权威实例），
     * 存档加载与菜单/网络包的「整体覆盖」都只能是<b>改内容</b>而不是<b>换对象</b>，
     * 否则任何持有旧引用的地方都会看到一份过期配置。</p>
     */
    public void copyFrom(final ChargingFilter other) {
        if (other == null || other == this) {
            return;
        }
        whitelist = other.whitelist;
        droppedItems.clear();
        droppedItems.addAll(other.droppedItems);
        carriedItems.clear();
        carriedItems.addAll(other.carriedItems);
        blocks.clear();
        blocks.addAll(other.blocks);
        playerNames.clear();
        playerNames.addAll(other.playerNames);
        playerIds.clear();
        playerIds.addAll(other.playerIds);
    }

    public boolean whitelist() {
        return whitelist;
    }

    public void setWhitelist(final boolean value) {
        whitelist = value;
    }

    /** 掉落物名单的只读视图（渲染 / 组包用；改名单请走 {@link #of} 或 {@link #copyFrom}）。 */
    public Set<ResourceLocation> droppedItems() {
        return Collections.unmodifiableSet(droppedItems);
    }

    /** 玩家身上物品名单的只读视图。 */
    public Set<ResourceLocation> carriedItems() {
        return Collections.unmodifiableSet(carriedItems);
    }

    /**
     * 「世界中的方块」名单的只读视图（方块注册名，第 110 轮）。
     * <p>与 {@link #droppedItems()} / {@link #carriedItems()} 一样是只读视图：改名单只能走
     * {@link #of} / {@link #copyFrom}（服务端权威只有方块实体里那一份）。</p>
     */
    public Set<ResourceLocation> blocks() {
        return Collections.unmodifiableSet(blocks);
    }

    /** 玩家名名单的只读视图（小写）。 */
    public Set<String> playerNames() {
        return Collections.unmodifiableSet(playerNames);
    }

    /** 玩家 UUID 名单的只读视图。 */
    public Set<UUID> playerIds() {
        return Collections.unmodifiableSet(playerIds);
    }

    // ==================== 判定：全工程唯一的黑/白名单语义 ====================

    /**
     * 这个<b>掉落物</b>允许被充电吗？
     * <p>黑名单：命中 ⇒ 拒绝；白名单：不命中 ⇒ 拒绝。物品注册名取不到（理论上不该发生）时
     * 按「未命中」处理 —— 黑名单放行、白名单拒绝，绝不会因为一次注册表查询失败就把
     * 白名单玩家名单外的所有东西放进来。</p>
     */
    public boolean allowsDroppedItem(final Item item) {
        return allows(droppedItems, idOf(item));
    }

    /** 这件<b>玩家身上物品</b>允许被充电吗？（语义同 {@link #allowsDroppedItem}） */
    public boolean allowsCarriedItem(final Item item) {
        return allows(carriedItems, idOf(item));
    }

    /**
     * 这个<b>世界中的方块</b>允许被充电吗？（第 110 轮；语义同 {@link #allowsDroppedItem}）
     *
     * <p><b>为什么这里收 {@link Block} 而不是 {@code Item} 或 {@code BlockState}</b>：
     * 判定只看「是哪一种方块」，与朝向 / 水位 / 是否被充能这些 state 属性无关
     * （同一台机器横放竖放是同一类目标）；而方块注册名从 {@code Block} 就能直接取到，
     * 不必先反查它对应的方块物品（有很多方块没有物品，例如活塞头 / 火）。
     * 因此 {@code chargeBlock} 里传的是 {@code state.getBlock()}，
     * <b>方块物品</b>只用于界面渲染图标。</p>
     *
     * <p>取不到注册名（理论上不该发生）时按「未命中」处理 —— 黑名单放行、白名单拒绝，
     * 与物品两条出口同一口径，绝不会因为一次注册表查询失败就把白名单外的方块全放进来。</p>
     */
    public boolean allowsBlock(final Block block) {
        return allows(blocks, idOfBlock(block));
    }

    /**
     * 这个<b>玩家</b>允许被充电吗？（名字小写 或 UUID 任一命中即算命中）
     *
     * <p>玩家级命中是「整名跳过」：连他身上的饰品槽也不扫 —— 与方块实体里的门控位置对应，
     * 这样「玩家被拦」在界面上就等同于「他整台装备都不充」，不会出现「背包拦了、饰品槽还在充」。</p>
     */
    public boolean allowsPlayer(final Player player) {
        if (player == null) {
            return true;
        }
        final UUID id = player.getUUID();
        final boolean hit = (id != null && playerIds.contains(id))
            || (player.getGameProfile() != null
            && playerNames.contains(lowerName(player.getGameProfile().getName())));
        return whitelist ? hit : !hit;
    }

    private boolean allows(final Set<ResourceLocation> list, final ResourceLocation id) {
        final boolean hit = id != null && list.contains(id);
        return whitelist ? hit : !hit;
    }

    private static String lowerName(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static ResourceLocation idOf(final Item item) {
        return item == null ? null : BuiltInRegistries.ITEM.getKey(item);
    }

    /** 方块注册名（{@link #allowsBlock} 专用；与物品注册表是两张表，不能互相代替）。 */
    private static ResourceLocation idOfBlock(final Block block) {
        return block == null ? null : BuiltInRegistries.BLOCK.getKey(block);
    }

    // ==================== NBT ====================

    /**
     * 写入 {@code tag}（调用方负责把它挂到方块实体根 tag 的 {@code chargingFilter} 键下）。
     * <p>只写 {@code ResourceLocation} 与 UUID，不写任何 ItemStack / 世界引用：
     * 这份数据必须能安全随存档落盘、也能整段丢弃重建。</p>
     */
    public void save(final CompoundTag tag) {
        tag.putInt(TAG_VERSION, NBT_VERSION);
        tag.putBoolean(TAG_WHITELIST, whitelist);
        tag.put(TAG_DROPPED, idsToTag(droppedItems));
        tag.put(TAG_CARRIED, idsToTag(carriedItems));
        // 第 110 轮：方块名单。旧版本模组读到这个键会忽略它（它按自己的字段名读），
        // 新版本模组读到旧存档时缺这个键 = 空集，两个方向都不炸。
        tag.put(TAG_BLOCKS, idsToTag(blocks));
        final ListTag names = new ListTag();
        for (final String name : playerNames) {
            names.add(net.minecraft.nbt.StringTag.valueOf(name));
        }
        tag.put(TAG_NAMES, names);
        final ListTag ids = new ListTag();
        for (final UUID id : playerIds) {
            ids.add(NbtUtils.createUUID(id));
        }
        tag.put(TAG_IDS, ids);
    }

    /**
     * 从存档读回。
     * <p><b>破坏性输入一律按「未配置」处理</b>：缺字段 / 版本缺失 / 类型不对都回落到
     * 默认（黑名单 + 空名单）。绝不因为一个坏 tag 就丢方块实体 —— 最坏情况是
     * 「过滤配置没读出来」，表现为不过滤，而不是机器报废。</p>
     *
     * <h2>版本闸（2026-10-25 补；第 98 轮自检方指出「注释与代码不符」）</h2>
     * <p>初版只写 {@link #TAG_VERSION} <b>不读</b>它，注释却写着「版本不认识就按缺字段处理」——
     * 代码与注释不符。现在真的读：<b>只接受 {@code v == NBT_VERSION}</b>；缺失、更小、更大
     * 一律回落默认并打一条 WARN。这样将来若 v2 改了名单语义，旧版模组读到新存档时
     * 是「明确拒绝解析、退回不过滤」，而不是「按 v1 的字段去猜 v2 的数据」。</p>
     */
    public static ChargingFilter load(final CompoundTag tag) {
        final ChargingFilter filter = new ChargingFilter();
        if (tag == null) {
            return filter;
        }
        final int version = tag.getInt(TAG_VERSION);
        if (version != NBT_VERSION) {
            com.mojang.logging.LogUtils.getLogger().warn(
                "rs_create_compat: range charger charging-filter tag version {} != {}; falling back to defaults",
                version, NBT_VERSION);
            return filter;
        }
        filter.whitelist = tag.getBoolean(TAG_WHITELIST);
        readIds(tag.getList(TAG_DROPPED, Tag.TAG_STRING), filter.droppedItems);
        readIds(tag.getList(TAG_CARRIED, Tag.TAG_STRING), filter.carriedItems);
        // 第 110 轮：方块名单。**缺字段不炸**：getList 对不存在的键返回空列表，
        // readBlocks 拿到空列表什么都不做 ⇒ 旧存档读出来就是「方块一类不过滤」。
        // 类型不对（例如被手改成 STRING）同样按空列表处理，绝不整段判非法。
        readBlocks(tag.getList(TAG_BLOCKS, Tag.TAG_STRING), filter.blocks);
        final ListTag names = tag.getList(TAG_NAMES, Tag.TAG_STRING);
        for (int i = 0; i < names.size(); i++) {
            addName(filter.playerNames, names.getString(i));
        }
        final ListTag ids = tag.getList(TAG_IDS, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < ids.size(); i++) {
            try {
                final UUID id = NbtUtils.loadUUID(ids.get(i));
                if (id != null) {
                    addId(filter.playerIds, id);
                }
            } catch (final RuntimeException ignored) {
                // 单条 UUID 坏掉只跳过这一条：其余名单仍然有效（不整段丢弃）
            }
        }
        return filter;
    }

    private static ListTag idsToTag(final Collection<ResourceLocation> ids) {
        final ListTag list = new ListTag();
        for (final ResourceLocation id : ids) {
            list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        return list;
    }

    private static void readIds(final ListTag list, final Set<ResourceLocation> target) {
        for (int i = 0; i < list.size(); i++) {
            final ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (!ChargerFilterLimits.addBounded(target, id,
                ChargerFilterLimits.MAX_ITEM_ENTRIES)) {
                warnOverflow();
                return;
            }
        }
    }

    /** 从存档读方块名单（第 110 轮）：闸门是 {@link ChargerFilterLimits#MAX_BLOCK_ENTRIES}。 */
    private static void readBlocks(final ListTag list, final Set<ResourceLocation> target) {
        for (int i = 0; i < list.size(); i++) {
            final ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (!ChargerFilterLimits.addBounded(target, id,
                ChargerFilterLimits.MAX_BLOCK_ENTRIES)) {
                warnOverflow();
                return;
            }
        }
    }

    /** 掉落物名单的一份有序副本（组包用：保证同配置每次发出去字节序一致）。 */
    public List<ResourceLocation> sortedDroppedItems() {
        return sorted(droppedItems);
    }

    /** 玩家身上物品名单的一份有序副本（同上）。 */
    public List<ResourceLocation> sortedCarriedItems() {
        return sorted(carriedItems);
    }

    /** 「世界中的方块」名单的一份有序副本（同上，第 110 轮）。 */
    public List<ResourceLocation> sortedBlocks() {
        return sorted(blocks);
    }

    /** 玩家名名单的一份有序副本（同上）。 */
    public List<String> sortedPlayerNames() {
        final List<String> names = new ArrayList<>(playerNames);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** 玩家 UUID 名单的一份有序副本（同上）。 */
    public List<UUID> sortedPlayerIds() {
        final List<UUID> ids = new ArrayList<>(playerIds);
        ids.sort(UUID::compareTo);
        return ids;
    }

    private static List<ResourceLocation> sorted(final Collection<ResourceLocation> ids) {
        final List<ResourceLocation> list = new ArrayList<>(ids);
        list.sort(ResourceLocation::compareTo);
        return list;
    }
}
