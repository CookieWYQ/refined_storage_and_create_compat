package cretae.cookiewyq.rs_create_compat.client;

import com.simibubi.create.AllSpecialTextures;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket;
import net.createmod.catnip.outliner.Outline;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 范围充电器的<b>充电目标高亮</b>（纯客户端渲染，服务端只下发「本 tick 真充了谁」）。
 *
 * <h2>三类目标各自的画法（第 102 轮按用户实机反馈重做）</h2>
 * <p>用户原话：「显示那个正在给谁充电：比如掉落物需要那个（高亮）边框、玩家就需要高亮玩家、
 * 方块就高亮方块这样子的。」因此三类目标<b>都必须有轮廓</b>，并且<b>颜色各不相同</b>，
 * 与界面图例行（{@code RangeChargerScreen} 底部「色块 + 类型名」）一一对应：</p>
 * <ol>
 *     <li><b>方块</b>：{@link #COLOR_BLOCK} 橙色框（与「查看充电范围」的橙框同色系 —— 两者都是
 *     「橙色 = 这台机器指给你看的位置」，只是一个是区域、一个是具体方块）；</li>
 *     <li><b>掉落物</b>：{@link #COLOR_ITEM} 青色框（框住掉落物自己的 AABB，外扩
 *     {@link #ITEM_BOX_INFLATE} 格，否则 0.25 格的框贴在物品上几乎看不见）+ 发光；</li>
 *     <li><b>玩家</b>：{@link #COLOR_PLAYER} 绿色框（框住玩家碰撞箱，外扩
 *     {@link #PLAYER_BOX_INFLATE} 格）+ 发光。</li>
 * </ol>
 *
 * <h2>「发光」为什么不能用 {@link Entity#setGlowingTag(boolean)}（本轮的核实结论）</h2>
 * <p>旧的实现给掉落物 / 玩家调了 {@code setGlowingTag(true)}，但它在<b>客户端是空操作</b>，
 * 所以用户「似乎没看到」是对的 —— 一个像素都不会亮。源码（1.21.1，NeoForge 打过补丁的
 * {@code Entity.java}）里这两行是决定性的：</p>
 * <pre>
 * public final void setGlowingTag(boolean glowing) {
 *     this.hasGlowingTag = glowing;
 *     this.setSharedFlag(6, this.isCurrentlyGlowing());   // ← 用「还没被写过的旧值」回写共享位
 * }
 * public boolean isCurrentlyGlowing() {
 *     return this.level().isClientSide() ? this.getSharedFlag(6) : this.hasGlowingTag;
 * }
 * </pre>
 * <p>服务端调用时：{@code hasGlowingTag} 刚被置 true、{@code isCurrentlyGlowing()} 在服务端读
 * {@code hasGlowingTag} ⇒ 共享位 6 被正确置上（再同步给客户端）。<b>客户端调用时</b>：
 * {@code isCurrentlyGlowing()} 走的是 {@code getSharedFlag(6)} —— 正是这次要写的那个位，
 * 还是旧值 false ⇒ {@code setSharedFlag(6, false)} ⇒ 等于什么都没做。同理解除发光也是空操作。</p>
 * <p>而渲染侧读的正是共享位：{@code Minecraft#shouldEntityAppearGlowing(entity)} →
 * {@code entity.isCurrentlyGlowing()} →（客户端）{@code getSharedFlag(6)}，
 * {@code LevelRenderer} 只有它为真时才把实体画进 outline buffer。所以本类改成
 * <b>直接写共享标志位 6</b>（{@link #GLOW_SHARED_FLAG}），走一个缓存的反射句柄
 * {@code Entity#setSharedFlag(int, boolean)}（{@code protected}，无公开 API；工程内 Curios
 * 兼容层同样是反射访问外部实现，这里保持同一套做法）。反射失败时**静默退化为「只有框」**
 * （{@link #SET_SHARED_FLAG} 为 null），不会影响框的渲染，也不会抛出到 tick 里。</p>
 *
 * <h2>为什么发光只在客户端做</h2>
 * <p>共享位 6 在客户端只是一个内存里的视觉标记：<b>不写 NBT、不进存档、不发任何包</b>
 * （{@code SynchedEntityData#set} 在客户端只改本地值）；服务端每 tick 同步共享标志时会自然覆盖，
 * 因此这里每 tick 重设一次，并且只对「我们真的点亮过」的实体还原
 * （{@link #glowingItems} / {@link #glowingPlayers} 只记录自己设置成功的），
 * 别的来源（光谱箭、其它模组）的发光不碰。</p>
 *
 * <h2>只画「本 tick 真的充了电」的目标</h2>
 * <p>服务端只在 {@code accepted > 0} 的分支登记目标，因此集合里的每一条都代表实际发生的能量转移：
 * 满电被跳过的、不可充电的、被过滤挡掉的目标一个都不会出现在这里（也就不会亮、不会框）。</p>
 *
 * <h2>开销</h2>
 * <p>服务端每 5 tick 发一次快照；客户端只有<b>集合真的变化</b>时才重建
 * （{@link #accept} 里先比较再重建），每 tick 只做 O(目标数) 的 {@code chaseAABB} 刷新
 * （实体框每 tick 按实体当前 AABB 重新算，因为目标会走动）与发光重设；
 * 颜色 / 面贴图 / 线宽只在第一次喂某个槽位时设置。快照停止到达超过 {@link #TIMEOUT_TICKS}
 * 后整体清理（服务端停机 / 玩家关闭开关 / 离开范围都不会留残影）。</p>
 *
 * <h2>线程 / 专服</h2>
 * <p>本类位于 {@code client} 包且标注 {@link Dist#CLIENT}，专用服务端不会加载它；
 * 数据入口只有 {@code network/ChargerHighlightPacket#handle} → {@code ClientPayloadSink}
 * （客户端主线程 {@code enqueueWork}）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class ChargerTargetHighlight {
    /** 方块目标的高亮色：橙色（与范围框同色系，语义都是「这台机器指给你看的东西」）。 */
    public static final int COLOR_BLOCK = 0xFF9F1C;
    /** 掉落物目标的高亮色：青绿（与方块橙、玩家绿都拉得开）。 */
    public static final int COLOR_ITEM = 0x36D6A5;
    /** 玩家目标的高亮色：绿色。 */
    public static final int COLOR_PLAYER = 0x62D95F;
    /** 高亮框线宽（1/16 格，与蓝图 / 范围框同一口径）。 */
    public static final float LINE_WIDTH = 1 / 16f;
    /** 掉落物框的外扩量（格）：{@code ItemEntity} 的 AABB 只有 0.25 格，不外扩就贴着物品看不出来。 */
    public static final double ITEM_BOX_INFLATE = 0.1D;
    /** 玩家框的外扩量（格）：贴着碰撞箱即可（0.6×1.8 + 0.02 仍明显大于身体）。 */
    public static final double PLAYER_BOX_INFLATE = 0.02D;
    /** 快照超时（tick）：服务端 5 tick 一次，这里留 4 倍余量；超时即清理。 */
    public static final int TIMEOUT_TICKS = 20;
    /** Outliner 槽位前缀（每类目标一个独立槽位，带模组前缀避免和别的 mod 撞车）。 */
    public static final String SLOT_PREFIX = RS_Create_Compat.MODID + ":charger_highlight/";
    /** 掉落物框槽位前缀。 */
    public static final String ITEM_SLOT_PREFIX = SLOT_PREFIX + "item/";
    /** 玩家框槽位前缀。 */
    public static final String PLAYER_SLOT_PREFIX = SLOT_PREFIX + "player/";

    /** 实体「发光」用的共享标志位号（{@code Entity#getSharedFlag(6)}，见类注释的源码取证）。 */
    private static final int GLOW_SHARED_FLAG = 6;
    /**
     * {@code Entity#setSharedFlag(int, boolean)} 的缓存反射句柄；{@code null} = 解析失败
     * （客户端退化为「只有框」）。为什么是反射而不是公开 API：见类注释，1.21.1 唯一的公开入口
     * {@code setGlowingTag} 在客户端是空操作，而这个方法本身是 {@code protected}。
     */
    private static final Method SET_SHARED_FLAG = resolveSetSharedFlag();
    /** 反射失败只打一行日志（避免每 tick 刷屏）。 */
    private static boolean glowFailureLogged;

    /** 一个方块高亮框的不可变渲染数据（重建时一次性生成，避免每帧 new）。 */
    private record BlockOutline(long key, Object slot, AABB box) {
    }

    /** 一个实体高亮框的渲染登记：槽位 + 定位键（掉落物 = 实体 id，玩家 = UUID）+ 是不是玩家。 */
    private record EntityOutline(Object slot, Object key, boolean player) {
    }

    private static ClientLevel trackedLevel;
    private static long activeUntil = Long.MIN_VALUE;
    private static List<BlockOutline> blockOutlines = List.of();
    private static List<EntityOutline> itemOutlines = List.of();
    private static List<EntityOutline> playerOutlines = List.of();
    private static Set<Integer> itemIds = Set.of();
    private static Set<UUID> playerIds = Set.of();
    /** 已给 Outliner 设过颜色 / 线宽的方块（key = BlockPos#asLong）。 */
    private static final Set<Long> styledBlocks = new HashSet<>();
    /** 已给 Outliner 设过颜色 / 线宽的实体槽位（槽位是字符串，按类型各自配色）。 */
    private static final Set<Object> styledEntitySlots = new HashSet<>();
    /** 我们点亮过的掉落物实体 id（只还原自己点亮的）。 */
    private static final Set<Integer> glowingItems = new HashSet<>();
    /** 我们点亮过的玩家 UUID（只还原自己点亮的）。 */
    private static final Set<UUID> glowingPlayers = new HashSet<>();

    private ChargerTargetHighlight() {
    }

    // ==================== 发光：共享标志位 6 的读写 ====================

    /**
     * 解析 {@code Entity#setSharedFlag(int, boolean)}（{@code protected}，无公开替代）。
     *
     * <p>用 {@code getDeclaredMethod} 而不是遍历方法：名字在 NeoForge 运行时是稳定的
     * Mojang 官方名（生产环境用 official mappings，parchment 只影响参数名）。
     * 任何一步失败都返回 {@code null}，调用方退化为「只有框」——绝不把异常抛进 tick 循环。</p>
     */
    private static Method resolveSetSharedFlag() {
        try {
            final Method method = Entity.class.getDeclaredMethod("setSharedFlag", int.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 客户端设置 / 清除某个实体的「发光」共享标志位。
     *
     * @return {@code true} = 真的写进去了（调用方才把实体记进「自己点亮过」的集合，之后才会还原它）
     */
    private static boolean setClientGlow(final Entity entity, final boolean glow) {
        final Method method = SET_SHARED_FLAG;
        if (method == null) {
            logGlowFailureOnce();
            return false;
        }
        try {
            method.invoke(entity, GLOW_SHARED_FLAG, glow);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            logGlowFailureOnce();
            return false;
        }
    }

    /** 反射不可用时只提示一次（客户端日志；不参与任何游戏逻辑）。 */
    private static void logGlowFailureOnce() {
        if (glowFailureLogged) {
            return;
        }
        glowFailureLogged = true;
        org.slf4j.LoggerFactory.getLogger(RS_Create_Compat.MODID)
            .warn("[rscc-charger] 无法通过反射设置实体发光标志位，充电目标高亮退化为「只有轮廓框」"
                + "（不影响可见性：三类目标仍然各有一个不同颜色的框）");
    }

    // ==================== S2C 入口 ====================

    /** 收到服务端快照：集合变化才重建；没变化只续命（这是「集合变化才重建」的唯一入口）。 */
    public static void accept(final ChargerHighlightPacket packet) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (level != trackedLevel) {
            resetState(false); // 换维度 / 重进世界：旧实体已不可用，直接丢弃旧状态，不碰旧世界
            trackedLevel = level;
        }
        activeUntil = level.getGameTime() + TIMEOUT_TICKS;

        final List<BlockPos> incomingBlocks = packet.blocks();
        final Set<Integer> newItems = new HashSet<>(packet.itemIds());
        final Set<UUID> newPlayers = new HashSet<>(packet.playerIds());
        final boolean blocksChanged = !sameBlocks(incomingBlocks);
        final boolean itemsChanged = !newItems.equals(itemIds);
        final boolean playersChanged = !newPlayers.equals(playerIds);
        if (!blocksChanged && !itemsChanged && !playersChanged) {
            return; // 集合没变：不重建任何对象（activeUntil 上面已刷新）
        }
        if (blocksChanged) {
            rebuildBlocks(incomingBlocks);
        }
        if (itemsChanged) {
            itemOutlines = rebuildEntityOutlines(newItems, false);
            itemIds = newItems;
        }
        if (playersChanged) {
            playerOutlines = rebuildEntityOutlines(newPlayers, true);
            playerIds = newPlayers;
        }
        restoreGlow();
    }

    private static boolean sameBlocks(final List<BlockPos> incoming) {
        if (incoming.size() != blockOutlines.size()) {
            return false;
        }
        for (int i = 0; i < incoming.size(); i++) {
            if (incoming.get(i).asLong() != blockOutlines.get(i).key()) {
                return false;
            }
        }
        return true;
    }

    private static void rebuildBlocks(final List<BlockPos> positions) {
        final Set<Long> incoming = new HashSet<>();
        for (final BlockPos pos : positions) {
            incoming.add(pos.asLong());
        }
        // 先移除不再需要的框（Outliner.remove 对不存在的 slot 是空操作）
        for (final BlockOutline old : blockOutlines) {
            if (!incoming.contains(old.key())) {
                Outliner.getInstance().remove(old.slot());
                styledBlocks.remove(old.key());
            }
        }
        final List<BlockOutline> outlines = new ArrayList<>(positions.size());
        for (final BlockPos pos : positions) {
            final long key = pos.asLong();
            outlines.add(new BlockOutline(key, SLOT_PREFIX + key, new AABB(pos).inflate(0.002D)));
        }
        blockOutlines = List.copyOf(outlines);
    }

    /**
     * 重建掉落物 / 玩家的框登记：先移除「不再属于本类」的槽位（连同它们的配色记忆），再生成新登记。
     * <p>这里只登记「槽位 + 定位键」，AABB 每 tick 现算 —— 掉落物会被推动、玩家会走，框必须跟着走。</p>
     */
    private static List<EntityOutline> rebuildEntityOutlines(final Set<?> ids, final boolean player) {
        final List<EntityOutline> old = player ? playerOutlines : itemOutlines;
        final String prefix = player ? PLAYER_SLOT_PREFIX : ITEM_SLOT_PREFIX;
        final List<EntityOutline> next = new ArrayList<>(ids.size());
        final Set<Object> keep = new HashSet<>(ids.size());
        for (final Object id : ids) {
            final Object slot = prefix + id;
            next.add(new EntityOutline(slot, id, player));
            keep.add(slot);
        }
        for (final EntityOutline outline : old) {
            if (!keep.contains(outline.slot())) {
                Outliner.getInstance().remove(outline.slot());
                styledEntitySlots.remove(outline.slot());
            }
        }
        return List.copyOf(next);
    }

    // ==================== 每 tick：喂 Outliner + 重设发光 ====================

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (level == null) {
            if (trackedLevel != null) {
                resetState(false);
                trackedLevel = null;
            }
            return;
        }
        if (level != trackedLevel) {
            resetState(false);
            trackedLevel = level;
            return;
        }
        if (activeUntil == Long.MIN_VALUE) {
            return; // 没有快照
        }
        if (level.getGameTime() > activeUntil) {
            resetState(true); // 快照断流：还原发光 + 清框（不留残影）
            return;
        }
        feedBlockOutlines();
        feedEntityOutlines();
        reassertGlow();
    }

    private static void feedBlockOutlines() {
        if (blockOutlines.isEmpty()) {
            return;
        }
        final Outliner outliner = Outliner.getInstance();
        for (final BlockOutline outline : blockOutlines) {
            final Outline.OutlineParams params = outliner.chaseAABB(outline.slot(), outline.box());
            if (styledBlocks.add(outline.key())) {
                // 只在第一次喂的时候设颜色 / 线宽：之后每 tick 只刷新位置，不重复 new Color
                params.colored(COLOR_BLOCK)
                    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                    .lineWidth(LINE_WIDTH);
            }
        }
    }

    /** 掉落物 / 玩家的框：每 tick 按实体当前 AABB 重算（目标会移动），颜色按类型固定。 */
    private static void feedEntityOutlines() {
        feedEntityOutlines(itemOutlines, COLOR_ITEM, ITEM_BOX_INFLATE);
        feedEntityOutlines(playerOutlines, COLOR_PLAYER, PLAYER_BOX_INFLATE);
    }

    private static void feedEntityOutlines(final List<EntityOutline> outlines, final int color,
                                           final double inflate) {
        final ClientLevel level = trackedLevel;
        if (level == null || outlines.isEmpty()) {
            return;
        }
        final Outliner outliner = Outliner.getInstance();
        for (final EntityOutline outline : outlines) {
            final Entity entity = resolve(level, outline);
            if (entity == null) {
                continue; // 超出客户端跟踪范围（或玩家已离开）：这一 tick 不喂，条目会自然淡出
            }
            final AABB box = entity.getBoundingBox().inflate(inflate);
            final Outline.OutlineParams params = outliner.chaseAABB(outline.slot(), box);
            if (styledEntitySlots.add(outline.slot())) {
                params.colored(color)
                    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                    .lineWidth(LINE_WIDTH);
            }
        }
    }

    /** 解析实体（掉落物按实体 id、玩家按 UUID）。 */
    private static Entity resolve(final ClientLevel level, final EntityOutline outline) {
        if (outline.player()) {
            return findPlayer(level, (UUID) outline.key());
        }
        return outline.key() instanceof Integer id ? level.getEntity(id) : null;
    }

    /** 每 tick 重设发光（服务端同步共享标志时会覆盖，这里补回来；离开集合的实体在 restoreGlow 里还原）。 */
    private static void reassertGlow() {
        final ClientLevel level = trackedLevel;
        if (level == null) {
            return;
        }
        for (final int id : itemIds) {
            final Entity entity = level.getEntity(id);
            if (entity == null) {
                continue; // 超出客户端跟踪范围：等它回来
            }
            if (!entity.isCurrentlyGlowing() && setClientGlow(entity, true)) {
                glowingItems.add(id);
            }
        }
        for (final UUID id : playerIds) {
            final AbstractClientPlayer player = findPlayer(level, id);
            if (player == null) {
                continue;
            }
            if (!player.isCurrentlyGlowing() && setClientGlow(player, true)) {
                glowingPlayers.add(id);
            }
        }
    }

    /** 把离开目标集合的实体还原为不发光（只动我们设置成功过的那些）。 */
    private static void restoreGlow() {
        final ClientLevel level = trackedLevel;
        for (final Iterator<Integer> iterator = glowingItems.iterator(); iterator.hasNext(); ) {
            final int id = iterator.next();
            if (itemIds.contains(id)) {
                continue;
            }
            final Entity entity = level == null ? null : level.getEntity(id);
            if (entity != null && entity.isCurrentlyGlowing()) {
                setClientGlow(entity, false);
            }
            iterator.remove();
        }
        for (final Iterator<UUID> iterator = glowingPlayers.iterator(); iterator.hasNext(); ) {
            final UUID id = iterator.next();
            if (playerIds.contains(id)) {
                continue;
            }
            final AbstractClientPlayer player = level == null ? null : findPlayer(level, id);
            if (player != null && player.isCurrentlyGlowing()) {
                setClientGlow(player, false);
            }
            iterator.remove();
        }
    }

    // ==================== 清理 ====================

    /**
     * 丢弃全部客户端状态（三类框 + 三类发光一次清干净）。
     *
     * @param restoreGlow {@code true} = 还原本进程点亮过的实体（超时 / 主动清理）；
     *                    {@code false} = 只丢状态不碰实体（换维度 / 旧世界实体已不可用）
     */
    private static void resetState(final boolean restoreGlow) {
        final ClientLevel level = trackedLevel;
        for (final BlockOutline outline : blockOutlines) {
            Outliner.getInstance().remove(outline.slot());
        }
        for (final EntityOutline outline : itemOutlines) {
            Outliner.getInstance().remove(outline.slot());
        }
        for (final EntityOutline outline : playerOutlines) {
            Outliner.getInstance().remove(outline.slot());
        }
        blockOutlines = List.of();
        itemOutlines = List.of();
        playerOutlines = List.of();
        styledBlocks.clear();
        styledEntitySlots.clear();
        if (restoreGlow && level != null) {
            for (final int id : glowingItems) {
                final Entity entity = level.getEntity(id);
                if (entity != null && entity.isCurrentlyGlowing()) {
                    setClientGlow(entity, false);
                }
            }
            for (final UUID id : glowingPlayers) {
                final AbstractClientPlayer player = findPlayer(level, id);
                if (player != null && player.isCurrentlyGlowing()) {
                    setClientGlow(player, false);
                }
            }
        }
        glowingItems.clear();
        glowingPlayers.clear();
        itemIds = Set.of();
        playerIds = Set.of();
        activeUntil = Long.MIN_VALUE;
    }

    private static AbstractClientPlayer findPlayer(final ClientLevel level, final UUID id) {
        for (final AbstractClientPlayer player : level.players()) {
            if (player.getUUID().equals(id)) {
                return player;
            }
        }
        return null;
    }
}
