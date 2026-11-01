package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定位玩家身上「第一个」高级远程多功能终端，返回「用于打开界面的 {@link SlotReference}」+「存活栈」。
 *
 * <p><b>为什么要有这个类</b>：快捷键此前走 RS 的 {@code RefinedStorageApi#useSlotReferencedItem}
 * →{@code CompositeSlotReferenceProvider#findForUse}，它要求<b>只找到唯一一个</b>引用：
 * 找到 0 个报「找不到」，找到 <b>&gt;1 个直接报「重复」并拒绝打开</b>；而提示文案恒用第一个候选
 * 物品（普通版）的名字，于是玩家体感是「生存版打不开、创造版能开」。本类把查找收归自有：
 * 客户端（决定是否叠加模式切换按钮）与服务端（快捷键权威打开）<b>共用同一套优先级</b>，
 * 并且<b>多个也只取第一个、绝不拒绝</b>。</p>
 *
 * <p><b>固定优先级</b>（双端一致，保证客户端读写的模式与服务端打开的是同一台）：
 * 主手 → 副手 → 背包/快捷栏下标 {@code 0..35} → Curios 饰品槽。</p>
 *
 * <p><b>只看类型不看型号</b>：判定是 {@code stack.getItem() instanceof AdvancedRemoteTerminalItem}
 * <b>或</b>物品注册 id 属于 {@link #TERMINAL_IDS}（普通 / 满电 / 创造三件的 id 双保险），
 * 因此三个型号走<b>完全相同</b>的路径，不存在「某个型号能开、另一个不能」的分叉。</p>
 *
 * <p>本类不引用任何客户端专属类型，双端均可调用。</p>
 */
public final class RsccTerminalLocator {
    /** 快捷栏 + 主背包的格数（0..35）。指数 36..40 是盔甲与副手，副手已由上面单独处理，不重复扫描。 */
    private static final int INVENTORY_END = 36;

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccTerminalLocator.class);

    /**
     * 日志节流窗口（秒）：<b>同一名玩家、同一类消息</b>最快每 {@value #THROTTLE_SECONDS} 秒打一条。
     *
     * <p><b>为什么从「每玩家每 JVM 一次」改成时间节流</b>：旧写法（{@code NOT_FOUND_LOGGED} /
     * {@code MULTIPLE_LOGGED} 两个 {@code Set<UUID>}）让玩家<b>第二次复现时日志里什么都没有</b>——
     * 上一轮就是因为「第一次没留住日志」而无法取证。现在允许重复，但必须有窗口，否则按住 G 会刷屏。</p>
     *
     * <p><b>为什么是 5 秒</b>：</p>
     * <ul>
     *     <li>真机复现的按键间隔通常 1~3 秒（关界面 → 再按 G）：5 秒窗口保证「第一次按」立即出现，
     *     窗口过去后的下一次按也一定再出现一条 ⇒ 反复复现都能抓到；</li>
     *     <li>输出频率上界 = 每名玩家每类 1 条 / 5 秒（3 类合计 36 条/分钟），比「每 tick 一条」
     *     低 4 个数量级，既不刷爆日志、也不拖慢服务端线程；</li>
     *     <li>窗口内被压掉的次数会汇总进下一条（{@code (x5 in last 5s)}），信息不丢。</li>
     * </ul>
     */
    private static final int THROTTLE_SECONDS = 5;
    /** 节流窗口的 tick 数（5 秒 × 20 tps；服务端暂停时游戏刻同样暂停，不会凭空走完窗口）。 */
    private static final int THROTTLE_TICKS = THROTTLE_SECONDS * 20;

    /**
     * 节流状态表的软上限（玩家数）：正常服务器远小于它。
     * <p>每条 = 3 个通道 ×（8 字节 tick + 4 字节计数）≈ 数十字节 ⇒ 上限约 50 KB。
     * 超限时先淘汰「所有通道都已过窗口」的闲置玩家；仍超限就整表清空
     * （最坏后果只是多打几条日志，绝不无界增长，也绝不因此漏掉第一次）。</p>
     */
    private static final int MAX_TRACKED_PLAYERS = 512;

    /** 三族日志各走各的节流通道（找不到 / 身上多台 / 打开失败），互不挤占。 */
    private enum LogKind {
        NOT_FOUND,
        MULTIPLE,
        OPEN_FAILED
    }

    /**
     * 一名玩家在某一族消息上的节流状态：<b>上一次真正打日志的游戏刻</b> + <b>之后被压掉的次数</b>。
     * <p>没有「是否已打过」的永久布尔量：{@link Long#MIN_VALUE} 表示「从未输出过」⇒ 第一次必打。</p>
     */
    private static final class LogState {
        /** 上一次真正输出的游戏刻；{@link Long#MIN_VALUE} = 从未输出（⇒ 首次必打）。 */
        volatile long lastLogTick = Long.MIN_VALUE;
        /** 上一次输出之后被节流压掉的次数（下次输出时汇总进 {@code (xN in last 5s)}）。 */
        volatile int suppressed;
    }

    /** 一名玩家的全部节流通道。 */
    private static final class PlayerLog {
        private final LogState[] channels = new LogState[LogKind.values().length];

        private PlayerLog() {
            for (int i = 0; i < channels.length; i++) {
                channels[i] = new LogState();
            }
        }

        private LogState of(final LogKind kind) {
            return channels[kind.ordinal()];
        }
    }

    /**
     * 节流状态表：玩家 UUID → 三通道状态。
     * <p>只在服务端线程读写（客户端在 {@link #logThrottled} 入口就返回，连表都不碰）。</p>
     */
    private static final Map<UUID, PlayerLog> LOG_STATES = new ConcurrentHashMap<>();

    /**
     * 本模组三件终端的物品 id（普通 / 满电 / 创造）。
     * <p>与 {@link #isTerminal} 的类判定组成<b>双保险</b>：三件终端都由本模组注册、id 恒定，
     * 因此即使将来新增型号、或某个型号换成别的实现类，也不会出现「某一台按快捷键提示找不到」。</p>
     */
    private static final Set<ResourceLocation> TERMINAL_IDS = Set.of(
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "advanced_remote_terminal"),
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "advanced_remote_terminal_charged"),
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "creative_advanced_remote_terminal")
    );

    private RsccTerminalLocator() {
    }

    /** 找到的终端位置：服务端打开界面用的引用 + 双端读写模式 NBT 用的存活栈。 */
    public record Location(SlotReference slotReference, ItemStack stack) {
    }

    /**
     * 按固定优先级列出玩家身上所有高级远程多功能终端（顺序即优先级）。
     * <p>返回全部而非短路取第一个，是为了在「多台」时能给出一次日志说明（玩家反馈「打不开」时便于定位），
     * 而选择逻辑本身只取第一个。</p>
     */
    public static List<Location> findAll(final Player player) {
        final List<Location> found = new ArrayList<>(2);
        addHand(found, player, InteractionHand.MAIN_HAND);
        addHand(found, player, InteractionHand.OFF_HAND);
        addInventory(found, player);
        addCurios(found, player);
        return found;
    }

    /**
     * 找第一台终端：一个都没有 → {@link Optional#empty()}；有多个 → 按优先级取第一个（<b>不拒绝</b>），
     * 并在服务端打一条节流日志说明（同一玩家最快每 {@value #THROTTLE_SECONDS} 秒一条）。
     */
    public static Optional<Location> locate(final Player player) {
        final List<Location> found = findAll(player);
        if (found.isEmpty()) {
            logNothingFound(player);
            return Optional.empty();
        }
        final Location chosen = found.getFirst();
        if (found.size() > 1) {
            logMultiple(player, found.size(), chosen.stack());
        }
        return Optional.of(chosen);
    }

    /** 主手 / 副手：用 RS 自带的引用（主手 = 快捷栏当前选中格，副手 = 副手格）。 */
    private static void addHand(final List<Location> found, final Player player, final InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (isTerminal(stack)) {
            found.add(new Location(RefinedStorageApi.INSTANCE.createInventorySlotReference(player, hand), stack));
        }
    }

    /** 背包 / 快捷栏：跳过主手所在下标，避免「拿在手上」被同时算成两台（只影响计数日志，位置本身也更直观）。 */
    private static void addInventory(final List<Location> found, final Player player) {
        final int mainHandIndex = player.getInventory().selected;
        for (int index = 0; index < INVENTORY_END; index++) {
            if (index == mainHandIndex) {
                continue;
            }
            final ItemStack stack = player.getInventory().getItem(index);
            if (isTerminal(stack)) {
                found.add(new Location(RsccTerminalReferences.createSlotReference(index), stack));
            }
        }
    }

    /** Curios 饰品槽：Curios 缺失 / 没插终端 → 什么都不加（反射链路失败也不会抛异常）。 */
    private static void addCurios(final List<Location> found, final Player player) {
        for (final SlotReference reference : RsccCuriosTerminalSlot.findAllTerminalReferences(player)) {
            final Optional<ItemStack> stack = reference.resolve(player);
            if (stack.isPresent() && isTerminal(stack.get())) {
                found.add(new Location(reference, stack.get()));
            }
        }
    }

    /** <b>只看类型</b>（不看 {@code Type}）：三版终端一视同仁；类判定之外再用物品 id 兜一层底。 */
    private static boolean isTerminal(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof AdvancedRemoteTerminalItem) {
            return true;
        }
        return TERMINAL_IDS.contains(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * 一台都没找到：服务端节流日志（{@link LogKind#NOT_FOUND} 通道），记清「翻过哪几处、Curios 是否可用」，
     * 玩家反馈「某台终端打不开」时可以直接对着日志定位。
     * <p>成功找到时完全不产生日志；正文延迟求值，被节流压掉时连 Curios 都不扫。</p>
     */
    private static void logNothingFound(final Player player) {
        logThrottled(LogKind.NOT_FOUND, player, null, () ->
            "No advanced remote terminal found for " + player.getName().getString()
                + ": scanned hands + inventory 0.." + (INVENTORY_END - 1)
                + " (Curios loaded=" + OptionalDeps.isCuriosLoaded() + ", "
                + RsccCuriosTerminalSlot.findAllTerminalReferences(player).size()
                + " terminal(s) visible in Curios)");
    }

    /** 「身上有多台终端 → 用优先级第一台」的说明（{@link LogKind#MULTIPLE} 通道，同样按 5 秒节流）。 */
    private static void logMultiple(final Player player, final int count, final ItemStack chosen) {
        logThrottled(LogKind.MULTIPLE, player, null, () ->
            "Player " + player.getName().getString() + " carries " + count
                + " advanced remote terminals; shortcut opens the first by priority "
                + "(main hand > off hand > inventory 0.." + (INVENTORY_END - 1) + " > Curios): "
                + chosen.getItem());
    }

    /**
     * 打开某个终端模式失败时调用（{@code AdvancedRemoteTerminalItem#openModeScreen} 的 {@code catch}）。
     * <p>与上面两族共用同一套节流：按一次 G 崩一次也不会把日志刷爆，但第一次一定留下完整堆栈。</p>
     *
     * @param error 失败原因（ERROR 级别会带上堆栈）
     */
    public static void logOpenFailure(final Player player, final int mode, final Throwable error) {
        logThrottled(LogKind.OPEN_FAILED, player, error,
            () -> "Failed to open terminal mode " + mode);
    }

    /**
     * 三族日志的<b>唯一出口</b>：同一玩家 + 同类消息最快每 {@link #THROTTLE_TICKS} tick 一条。
     *
     * <p>规则按顺序执行（顺序即优先级）：</p>
     * <ol>
     *     <li><b>首次必打</b>：该通道从未输出过 ⇒ 不看窗口，立刻打（节流绝不能吞掉关键的第一次）；</li>
     *     <li><b>同一 tick 内重复不发</b>：一次按键可能被同批处理多次（快捷键 + 物品使用），只累加计数；</li>
     *     <li><b>窗口内不发</b>：只累加 {@code suppressed}，留给下一条汇总；</li>
     *     <li><b>窗口已过 / 时间倒流</b>（换存档、整合服重开导致游戏刻归零）⇒ 立即打，
     *     并把被压掉的次数汇总成 {@code (xN in last 5s)}（N 含本次）。</li>
     * </ol>
     *
     * <p><b>绝不每 tick 打</b>：输出频率上界 = 每玩家每类 1 条 / {@value #THROTTLE_SECONDS} 秒；
     * 状态表上限见 {@link #MAX_TRACKED_PLAYERS}。</p>
     *
     * @param player 触发者（客户端调用直接返回，不写日志、不碰状态表）
     * @param error  非空 ⇒ 用 ERROR 级别并附堆栈（打开失败）；空 ⇒ INFO
     * @param line   日志正文（延迟求值：被压掉时连字符串都不拼）
     */
    private static void logThrottled(final LogKind kind, final Player player,
                                     final Throwable error, final Supplier<String> line) {
        // 客户端也会调用 locate（叠加层判断要不要加模式切换按钮）——日志只在服务端打
        if (player.level().isClientSide()) {
            return;
        }
        final long nowTick = player.level().getGameTime();
        final LogState state = stateOf(player.getUUID(), kind, nowTick);
        synchronized (state) {
            final long elapsed = nowTick - state.lastLogTick;
            if (state.lastLogTick != Long.MIN_VALUE && elapsed == 0L) {
                // 同一 tick 内重复：不刷屏，但计数（下一条的 xN 会包含它）
                state.suppressed++;
                return;
            }
            if (state.lastLogTick != Long.MIN_VALUE && elapsed > 0L && elapsed < THROTTLE_TICKS) {
                // 窗口内：只计数
                state.suppressed++;
                return;
            }
            final int occurrences = state.suppressed + 1;
            final String message = line.get();
            if (occurrences > 1) {
                if (error == null) {
                    LOGGER.info("{} (x{} in last {}s)", message, occurrences, THROTTLE_SECONDS);
                } else {
                    // SLF4J：最后一个参数是 Throwable、且占位符少一个时，按「格式 + 异常」处理
                    LOGGER.error("{} (x{} in last {}s)", message, occurrences, THROTTLE_SECONDS, error);
                }
            } else if (error == null) {
                LOGGER.info(message);
            } else {
                LOGGER.error(message, error);
            }
            state.lastLogTick = nowTick;
            state.suppressed = 0;
        }
    }

    /** 取（必要时创建）该玩家的某一通道状态；表满时先淘汰闲置玩家，仍满则整表清空。 */
    private static LogState stateOf(final UUID id, final LogKind kind, final long nowTick) {
        PlayerLog log = LOG_STATES.get(id);
        if (log == null) {
            if (LOG_STATES.size() >= MAX_TRACKED_PLAYERS) {
                pruneIdle(nowTick);
            }
            final PlayerLog created = new PlayerLog();
            final PlayerLog existing = LOG_STATES.putIfAbsent(id, created);
            log = existing != null ? existing : created;
        }
        return log.of(kind);
    }

    /**
     * 淘汰「所有通道都已过窗口」的闲置玩家；若仍达到软上限则整表清空。
     * <p>清空的代价只是「下一轮的第一条没有 xN 汇总」，绝不会漏掉日志本身。</p>
     */
    private static void pruneIdle(final long nowTick) {
        LOG_STATES.entrySet().removeIf(entry -> {
            for (final LogState state : entry.getValue().channels) {
                final long elapsed = nowTick - state.lastLogTick;
                if (state.lastLogTick != Long.MIN_VALUE && elapsed >= 0L && elapsed < THROTTLE_TICKS) {
                    return false;
                }
            }
            return true;
        });
        if (LOG_STATES.size() >= MAX_TRACKED_PLAYERS) {
            LOG_STATES.clear();
        }
    }
}
