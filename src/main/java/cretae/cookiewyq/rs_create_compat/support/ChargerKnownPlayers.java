package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「本存档曾经进过服的所有玩家」——充电过滤的玩家名单候选池。
 *
 * <h2>为什么必须是存档级 SavedData（而不是扫描在线玩家）</h2>
 * <p>用户要的是「列出所有能充电的」，而玩家名单的候选必须包含<b>离线玩家</b>：
 * 一个昨天进过服、今天没上线的人，也要能提前被拉进黑名单 / 白名单。
 * 在线玩家列表在服务端重启后是空的，不能当候选池；因此把「见过的玩家」落盘到
 * 主世界的 {@code DimensionDataStorage}（与 {@link BlockContentPolicy} 同一套做法，
 * 数据名 {@code rscc_charger_known_players}），随存档读写。</p>
 *
 * <h2>为什么要监听登录事件、又要在打开界面时补记一次</h2>
 * <p>登录事件是「见过这个人」的<b>主来源</b>；但它有两个空窗：①专用服务端刚起、
 * 玩家事件还没分发时打开界面；②事件在别的线程 / 别的阶段被取消时。
 * 因此打开过滤界面的那条路径上再 {@link #record(Player)} 一次 —— 同一个人重复记录是
 * <b>幂等</b>的（只在名字变化时 {@code setDirty()}），不会造成无谓的落盘。</p>
 *
 * <h2>线程 / 侧别</h2>
 * <p>只在服务端读写；{@code support/**} 不得引用任何客户端类型（专用服务端安全判据）。
 * 名字只用于显示与「名字命中」，判定仍由 {@link ChargingFilter} 负责。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class ChargerKnownPlayers extends SavedData {
    /** 存档内的数据名（一次性写死：改名等于把玩家的名单历史清空）。 */
    public static final String DATA_NAME = "rscc_charger_known_players";

    private static final String TAG_PLAYERS = "players";
    private static final String ENTRY_ID = "id";
    private static final String ENTRY_NAME = "name";

    /** 名单里的一条只读视图（名字 + UUID；在线状态由调用方按当前世界现查，不落盘）。 */
    public record Entry(String name, UUID id) {
    }

    /** UUID → 最近一次见到的名字。 */
    private final Map<UUID, String> names = new HashMap<>();

    /** 取（或首次创建）主世界里的这份数据。 */
    public static ChargerKnownPlayers get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(ChargerKnownPlayers::new, ChargerKnownPlayers::load),
            DATA_NAME);
    }

    /**
     * 记录一名玩家（登录事件与「打开过滤界面」共用，幂等）。
     * <p>非服务端玩家（理论上不会出现）直接忽略：本类只在服务端有意义。</p>
     */
    public static void record(final Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.getServer() == null) {
            return;
        }
        final var profile = serverPlayer.getGameProfile();
        final String name = profile == null ? "" : profile.getName();
        get(serverPlayer.getServer()).remember(serverPlayer.getUUID(), name);
    }

    /** 主世界存档里「见过谁」的只读快照（按名字不区分大小写排序，其次按 UUID 保证稳定）。 */
    public static List<Entry> snapshot(final ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return List.of();
        }
        return get(level.getServer()).entries();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        record(event.getEntity());
    }

    private void remember(final UUID id, final String rawName) {
        if (id == null) {
            return;
        }
        final String name = rawName == null ? "" : rawName.trim();
        final String previous = names.get(id);
        // 名字没变就不写脏：登录事件在大服上很频繁（重连 / 换维度也算），
        // 每次都 setDirty() 会让存档每几秒就落一次盘，纯属浪费。
        if (previous != null && previous.equals(name)) {
            return;
        }
        names.put(id, name);
        setDirty();
    }

    private List<Entry> entries() {
        final List<Entry> list = new ArrayList<>(names.size());
        for (final Map.Entry<UUID, String> entry : names.entrySet()) {
            list.add(new Entry(entry.getValue() == null ? "" : entry.getValue(), entry.getKey()));
        }
        list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::id));
        return list;
    }

    private static ChargerKnownPlayers load(final CompoundTag tag,
                                            final HolderLookup.Provider registries) {
        final ChargerKnownPlayers data = new ChargerKnownPlayers();
        final ListTag list = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            if (!entry.contains(ENTRY_ID, Tag.TAG_INT_ARRAY)) {
                continue; // 缺 UUID 的条目无法参与「UUID 命中」，直接跳过（不猜一个 id）
            }
            try {
                final UUID id = NbtUtils.loadUUID(entry.get(ENTRY_ID));
                if (id != null) {
                    data.names.put(id, entry.getString(ENTRY_NAME));
                }
            } catch (final RuntimeException ignored) {
                // 单条坏掉只跳过这一条：其余名单仍然有效（宁可少一个候选，也不整段丢弃）
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final Map.Entry<UUID, String> entry : names.entrySet()) {
            final CompoundTag item = new CompoundTag();
            item.put(ENTRY_ID, NbtUtils.createUUID(entry.getKey()));
            item.putString(ENTRY_NAME, entry.getValue() == null ? "" : entry.getValue());
            list.add(item);
        }
        tag.put(TAG_PLAYERS, list);
        return tag;
    }
}
