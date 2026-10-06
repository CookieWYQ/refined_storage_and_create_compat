package cretae.cookiewyq.rs_create_compat.advancement;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组「玩法型成就」的自定义 criterion 注册点与触发点。
 *
 * <p><b>为什么需要它</b>：绝大多数成就用原版 {@code minecraft:inventory_changed}（拿到某台机器）
 * 就够了，纯 JSON、零代码；但用户还要「装填器喂满料并自动打印一次」「用扳手断开再接通一道线缆缝」
 * 这类**复合事件 / 机器组事件**成就，原版触发器表达不了。因此这里注册自定义触发器，
 * 并在机器真正做完那件事的那一行顺手 fire。</p>
 *
 * <p><b>注册点</b>：{@link #TRIGGERS} → {@link #register(IEventBus)}，由 {@code RS_Create_Compat}
 * 构造器在 mod 事件总线上调用；registry 是原版的 {@code minecraft:trigger_type}。
 * 同一个实现类 {@link RsccCriterionTrigger} 注册多次是<b>刻意的</b>（原版 {@code PlayerTrigger}
 * 也是这么复用成 LOCATION / SLEPT_IN_BED / TICK 等多个触发器的）：registry 名 = JSON 里的
 * {@code trigger} 值，决定「哪些成就在监听哪一类事件」。</p>
 *
 * <p><b>服务端 / 客户端边界</b>：成就判定只存在于服务端（{@link ServerPlayer} 才有
 * {@code PlayerAdvancements}），因此两族 fire 都只在服务端调用：
 * <ul>
 *     <li>{@code fireNearby}：机器自己干活、没有「操作者」语义 → 按原版结构生成一类事件的惯例，
 *     记给动作发生那一刻就在旁边的玩家（半径与完成横幅同口径 64 格）；</li>
 *     <li>{@code fire}：事件天然属于某一个玩家（按快捷键开终端 / 切模式）→ 只发给本人。</li>
 * </ul>
 * 重复 fire 不需要自己去重：原版 {@code PlayerAdvancements.award} 对已完成的 criterion 直接返回
 * false，toast 不会再弹一次。</p>
 */
public final class RsccAdvancements {
    /** 自定义 criterion 注册表（原版 registry id = minecraft:trigger_type）。 */
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
        DeferredRegister.create(Registries.TRIGGER_TYPE, RS_Create_Compat.MODID);

    // ---------- 蓝图与自动打印 ----------
    /** 装填器「供料完毕 + 自动打印完成一张蓝图」（基础版 / 高级版都会 fire）。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> BLUEPRINT_PRINTED =
        TRIGGERS.register("blueprint_printed", RsccCriterionTrigger::new);
    /** 高级装填器「队列流水线自动打印完一张蓝图」（只有高级版 fire）。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> BLUEPRINT_QUEUE_PRINTED =
        TRIGGERS.register("blueprint_queue_printed", RsccCriterionTrigger::new);

    // ---------- 归流与缓存 ----------
    /** 归流缓存仓「从世界里吸进掉落物」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> COLLECTION_ABSORBED =
        TRIGGERS.register("collection_absorbed", RsccCriterionTrigger::new);
    /** 归流缓存仓「抽走世界里的源流体」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> COLLECTION_FLUID =
        TRIGGERS.register("collection_fluid", RsccCriterionTrigger::new);
    /** 归流缓存仓「收进经验球 / 液态经验」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> COLLECTION_XP =
        TRIGGERS.register("collection_xp", RsccCriterionTrigger::new);
    /** 中间产物缓存仓「盘位发生变化」（插盘 = 投入使用）。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> CACHE_IN_USE =
        TRIGGERS.register("cache_in_use", RsccCriterionTrigger::new);

    // ---------- 线缆与框架 ----------
    /** 任一种框架「套上一格」。（无限版会再多 fire 一次 {@link #SHEATH_APPLIED_INFINITE}。） */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> SHEATH_APPLIED =
        TRIGGERS.register("sheath_applied", RsccCriterionTrigger::new);
    /** 无限分隔框架「套上一格」（不消耗的那个版本）。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> SHEATH_APPLIED_INFINITE =
        TRIGGERS.register("sheath_applied_infinite", RsccCriterionTrigger::new);
    /** 批量套壳「一次套住一整段」（连锁 / 批量入口，新增 ≥2 格才算）。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> SHEATH_CHAIN =
        TRIGGERS.register("sheath_chain", RsccCriterionTrigger::new);
    /** 扳手「断开一道线缆缝」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> SEAM_CUT =
        TRIGGERS.register("seam_cut", RsccCriterionTrigger::new);
    /** 扳手「把断开的那道缝接回去」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> SEAM_RESTORED =
        TRIGGERS.register("seam_restored", RsccCriterionTrigger::new);

    // ---------- 序列装配 ----------
    /** 序列装配样板库「放入一张可解析的总样板」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> ASSEMBLY_PATTERN_LOADED =
        TRIGGERS.register("assembly_pattern_loaded", RsccCriterionTrigger::new);

    // ---------- 定量保持器 ----------
    /** 定量保持器「自动合成补货一次」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> KEEPER_AUTOCRAFTED =
        TRIGGERS.register("keeper_autocrafted", RsccCriterionTrigger::new);
    /** 高级定量保持器「某个配置槽自动合成补货一次」。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> ADVANCED_KEEPER_AUTOCRAFTED =
        TRIGGERS.register("advanced_keeper_autocrafted", RsccCriterionTrigger::new);

    // ---------- 终端 ----------
    /** 用快捷键打开高级远程多功能终端。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> TERMINAL_HOTKEY =
        TRIGGERS.register("terminal_hotkey", RsccCriterionTrigger::new);
    /** 终端打开 / 切到「合成终端」模式。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> TERMINAL_MODE_GRID =
        TRIGGERS.register("terminal_mode_grid", RsccCriterionTrigger::new);
    /** 终端打开 / 切到「样板终端」模式。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> TERMINAL_MODE_PATTERNS =
        TRIGGERS.register("terminal_mode_patterns", RsccCriterionTrigger::new);
    /** 终端打开 / 切到「合成仓管理」模式。 */
    public static final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> TERMINAL_MODE_MANAGER =
        TRIGGERS.register("terminal_mode_manager", RsccCriterionTrigger::new);

    /**
     * 与「完成横幅」同一口径的半径（{@code sendCompletionBanner} 用 64 格）。
     */
    private static final int NEARBY_RADIUS = 64;

    private RsccAdvancements() {
    }

    public static void register(final IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }

    // ==================== 语义化触发点：机器事件（记给附近玩家） ====================

    /**
     * 蓝图加农炮装填器刚把一张蓝图喂饱并打印完成（由 {@code SchematicLoaderBlockEntity} 的
     * 「加农炮输出槽出现新的空白蓝图」判定调用，服务端 tick 内）。
     */
    public static void onBlueprintPrinted(final SchematicLoaderBlockEntity loader) {
        fireNearby(loader.getLevel(), loader.getBlockPos(), BLUEPRINT_PRINTED);
        // 高级版独有的队列流水线：同一时刻再发一次「队列自动打印」事件
        if (loader instanceof AdvancedSchematicLoaderBlockEntity) {
            fireNearby(loader.getLevel(), loader.getBlockPos(), BLUEPRINT_QUEUE_PRINTED);
        }
    }

    /** 归流缓存仓刚从世界里吸进（至少一堆）掉落物（服务端吸收流程末尾调用）。 */
    public static void onCollectedFromWorld(final CollectionCacheBlockEntity cache) {
        fireNearby(cache.getLevel(), cache.getBlockPos(), COLLECTION_ABSORBED);
    }

    /** 归流缓存仓刚抽走格子源流体（服务端流体吸收流程末尾调用）。 */
    public static void onCollectedFluid(final CollectionCacheBlockEntity cache) {
        fireNearby(cache.getLevel(), cache.getBlockPos(), COLLECTION_FLUID);
    }

    /** 归流缓存仓刚收进经验（经验球 / 液态经验，服务端经验吸收流程末尾调用）。 */
    public static void onCollectedExperience(final CollectionCacheBlockEntity cache) {
        fireNearby(cache.getLevel(), cache.getBlockPos(), COLLECTION_XP);
    }

    /** 中间产物缓存仓盘位发生变化（放入 / 取出 / 替换磁盘）。 */
    public static void onSharedCacheDiskChanged(final IntermediateCacheBlockEntity cache) {
        fireNearby(cache.getLevel(), cache.getBlockPos(), CACHE_IN_USE);
    }

    /** 序列装配样板库放入了一张能解析成 RS Pattern 的总样板。 */
    public static void onAssemblyPatternLoaded(final SequenceAssemblyExecutorBlockEntity executor) {
        fireNearby(executor.getLevel(), executor.getBlockPos(), ASSEMBLY_PATTERN_LOADED);
    }

    /** 定量保持器刚向 RS 网络请求了一次自动合成补货（服务端网络节点 {@code doWork} 内）。 */
    public static void onAutocraftRequested(final QuantityKeeperBlockEntity keeper) {
        fireNearby(keeper.getLevel(), keeper.getBlockPos(), KEEPER_AUTOCRAFTED);
    }

    /** 高级定量保持器的某个配置槽刚请求了一次自动合成补货（服务端网络节点内）。 */
    public static void onAdvancedAutocraftRequested(final AdvancedQuantityKeeperBlockEntity keeper) {
        fireNearby(keeper.getLevel(), keeper.getBlockPos(), ADVANCED_KEEPER_AUTOCRAFTED);
    }

    /**
     * 一格（或一整段的第一格）刚被套上框架外壳。
     *
     * <p>为什么由 {@code RsccSheaths} 调用：单格（{@code add}）与批量（{@code addAll}）两条路径都在这里落记录，
     * 是「套上」这件事唯一不会漏的 choke point（含 FTB 连锁）。</p>
     *
     * @param infinite 是否为无限分隔框架（不消耗、取下不归还）那一位
     */
    public static void onSheathApplied(final ServerLevel level, final BlockPos pos, final boolean infinite) {
        fireNearby(level, pos, SHEATH_APPLIED);
        if (infinite) {
            fireNearby(level, pos, SHEATH_APPLIED_INFINITE);
        }
    }

    /**
     * 一次批量套壳真正新增了多格（≥2 才算「一整段」；用户要求「首次给整段线缆套壳」）。
     */
    public static void onSheathChained(final ServerLevel level, final BlockPos pos) {
        fireNearby(level, pos, SHEATH_CHAIN);
    }

    /**
     * 刚切换了一道线缆缝的接通状态。
     *
     * @param wasCut {@code true} = 这一次是「把断掉的接回去」；{@code false} = 这一次是「断开」
     */
    public static void onSeamToggled(final ServerLevel level, final BlockPos pos, final boolean wasCut) {
        fireNearby(level, pos, wasCut ? SEAM_RESTORED : SEAM_CUT);
    }

    // ==================== 语义化触发点：玩家事件（只记给本人） ====================

    /** 玩家刚按下快捷键打开了高级远程多功能终端（C2S 包服务端处理成功之后）。 */
    public static void onTerminalOpenedByHotkey(final ServerPlayer player) {
        fire(player, TERMINAL_HOTKEY);
    }

    /**
     * 玩家刚打开 / 切换到终端的某个模式。
     *
     * <p>为什么「打开」也要发：默认模式就是合成终端，玩家若从不切 Tab，
     * 「打开三种模式」这个成就会永远差一个条件（打开时补发一次才自洽）。</p>
     */
    public static void onTerminalMode(final ServerPlayer player, final int mode) {
        switch (mode) {
            case AdvancedRemoteTerminalItem.MODE_GRID -> fire(player, TERMINAL_MODE_GRID);
            case AdvancedRemoteTerminalItem.MODE_PATTERNS -> fire(player, TERMINAL_MODE_PATTERNS);
            case AdvancedRemoteTerminalItem.MODE_MANAGER -> fire(player, TERMINAL_MODE_MANAGER);
            default -> {
                // 其余模式（监视 / 序列 / 单元样板）不在「三模全开」成就的判定范围内
            }
        }
    }

    // ==================== 底层出口 ====================

    /**
     * 把触发器发给中心点半径内的所有玩家。
     * <p>客户端调用（或方块实体已脱离世界、level 为 null）时直接返回：成就判定只存在于服务端，
     * 客户端根本没有 {@code PlayerAdvancements}。</p>
     */
    private static void fireNearby(final Level level, final BlockPos pos,
                                   final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> trigger) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final RsccCriterionTrigger instance = trigger.get();
        final double radiusSq = (double) NEARBY_RADIUS * (double) NEARBY_RADIUS;
        for (final ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= radiusSq) {
                instance.fire(player);
            }
        }
    }

    /** 把触发器发给这一个玩家（事件天然属于某个玩家时用）。 */
    private static void fire(final ServerPlayer player,
                             final DeferredHolder<CriterionTrigger<?>, RsccCriterionTrigger> trigger) {
        trigger.get().fire(player);
    }
}
