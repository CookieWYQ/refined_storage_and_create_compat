package cretae.cookiewyq.rs_create_compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 配置类：所有数值/开关类配置均在此（GUI 调整项除外）。
public class Config {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.comment("Whether to log the dirt block on common setup").define("logDirtBlock", true);

    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER.comment("A magic number").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER.comment("What you want the introduction message to be for the magic number").define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER.comment("A list of items to log on common setup.").defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // ========== 通用储存磁盘 ==========
    private static final ModConfigSpec.BooleanValue UNIVERSAL_DISK_ALLOW_MIXED_TYPES = BUILDER
        .comment("是否允许通用储存磁盘同时混存不同类型（物品/流体/气体）。关闭后每个磁盘只能存放一种类型。")
        .define("universalDiskAllowMixedTypes", true);

    // ========== 范围充电器 ==========
    private static final ModConfigSpec.IntValue RANGE_CHARGER_CHARGE_RATE = BUILDER
        .comment("范围充电器单个目标的基准充电速率（FE/tick）。默认较慢，可放入速度升级成倍加快。")
        .defineInRange("rangeChargerChargeRate", 500, 1, 1000000);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_ENERGY_CAPACITY = BUILDER
        .comment("范围充电器的能量缓存上限（FE）。")
        .defineInRange("rangeChargerEnergyCapacity", 10000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_MAX_TRANSFER = BUILDER
        .comment("范围充电器接收外部能量的最大速率（FE/tick）。")
        .defineInRange("rangeChargerMaxTransfer", 100000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_MAX_TARGETS = BUILDER
        .comment("范围充电器每 tick 最多充电的对象数量（方块+物品+玩家物品合计）。")
        .defineInRange("rangeChargerMaxTargets", 50, 1, 1000);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_RANGE_PER_UPGRADE = BUILDER
        .comment("每个范围升级为范围充电器三轴各增加的最大范围（格）。无范围升级时三轴上限为 100。")
        .defineInRange("rangeChargerRangePerUpgrade", 25, 1, 1000000);

    private static final ModConfigSpec.BooleanValue RANGE_CHARGER_CHARGE_BLOCKS = BUILDER
        .comment("是否给范围内可充电方块供电。")
        .define("rangeChargerChargeBlocks", true);

    private static final ModConfigSpec.BooleanValue RANGE_CHARGER_CHARGE_ITEMS = BUILDER
        .comment("是否给范围内掉落物中的可充电物品供电。")
        .define("rangeChargerChargeItems", true);

    private static final ModConfigSpec.BooleanValue RANGE_CHARGER_CHARGE_PLAYER_ITEMS = BUILDER
        .comment("是否给范围内玩家手持/背包中的可充电物品供电（如无线终端）。")
        .define("rangeChargerChargePlayerItems", true);

    // ========== 定量保持器 ==========
    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_ENERGY_USAGE = BUILDER
        .comment("定量保持器每 tick 的网络能量消耗（FE）。")
        .defineInRange("quantityKeeperEnergyUsage", 10, 1, 100000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DEFAULT_TARGET = BUILDER
        .comment("定量保持器的默认目标数量。")
        .defineInRange("quantityKeeperDefaultTarget", 64, 1, 1000000000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DESTROY_RATE = BUILDER
        .comment("定量保持器每 tick 基础销毁的过量数量（无升级时，默认逐个销毁；每个速度/堆叠升级翻倍）。")
        .defineInRange("quantityKeeperDestroyRate", 1, 1, 1000000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DESTROY_RATE_PER_SPEED = BUILDER
        .comment("每个速度升级额外增加的每 tick 销毁数量。")
        .defineInRange("quantityKeeperDestroyRatePerSpeed", 16, 0, 1000000);

    // ========== 单元样板管理舱 ==========
    private static final ModConfigSpec.IntValue UNIT_PATTERN_MANAGER_ENERGY_USAGE = BUILDER
        .comment("单元样板管理舱每 tick 的网络能量消耗（FE）。")
        .defineInRange("unitPatternManagerEnergyUsage", 4, 1, 100000);

    // ========== 归流缓存仓 ==========
    private static final ModConfigSpec.IntValue COLLECTION_CACHE_SCAN_INTERVAL = BUILDER
        .comment("归流缓存仓扫描周围掉落物的冷却间隔（tick）。默认 20（1 秒扫描一次）。")
        .defineInRange("collectionCacheScanInterval", 20, 1, 12000);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_SCAN_RADIUS = BUILDER
        .comment("归流缓存仓扫描周围掉落物的半径（格，以自身方块为中心的三轴范围）。默认 5。")
        .defineInRange("collectionCacheScanRadius", 5, 1, 64);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_IDLE_ENERGY_USAGE = BUILDER
        .comment("归流缓存仓空闲时每 tick 的网络能量消耗（FE）。默认 1。")
        .defineInRange("collectionCacheIdleEnergyUsage", 1, 1, 1000000);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_WORK_ENERGY_USAGE = BUILDER
        .comment("归流缓存仓工作（收集掉落物 / 向网络写入缓存）时每 tick 的网络能量消耗（FE）。默认 8。")
        .defineInRange("collectionCacheWorkEnergyUsage", 8, 1, 1000000);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_ENERGY_PER_SPEED_UPGRADE = BUILDER
        .comment("归流缓存仓每个速度升级额外增加的能量消耗（FE/tick）。默认 4。")
        .defineInRange("collectionCacheEnergyPerSpeedUpgrade", 4, 0, 1000000);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_ENERGY_PER_RADIUS = BUILDER
        .comment("归流缓存仓收集范围每超出最小范围（1 格）1 格所增加的能量消耗（FE/tick）。"
            + "该消耗与是否正在收集无关：范围调大就一直更耗电。默认 2。")
        .defineInRange("collectionCacheEnergyPerRadius", 2, 0, 1000000);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_SLOT_CAPACITY = BUILDER
        .comment("归流缓存仓缓存区每格的物品上限（无堆叠升级时的基础值）。默认 64。")
        .defineInRange("collectionCacheSlotCapacity", 64, 1, 1000000);

    private static final ModConfigSpec.IntValue COLLECTION_CACHE_SLOT_CAPACITY_PER_STACK_UPGRADE = BUILDER
        .comment("归流缓存仓每个堆叠升级额外增加的缓存每格上限。默认 64。")
        .defineInRange("collectionCacheSlotCapacityPerStackUpgrade", 64, 0, 1000000);

    // ========== 高级远程多功能终端 ==========
    private static final ModConfigSpec.IntValue ADVANCED_REMOTE_TERMINAL_ENERGY_CAPACITY = BUILDER
        .comment("高级远程多功能终端的电量容量（FE）。")
        .defineInRange("advancedRemoteTerminalEnergyCapacity", 10000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.BooleanValue ADVANCED_REMOTE_TERMINAL_GRID = BUILDER
        .comment("启用高级远程多功能终端的合成终端界面。")
        .define("advancedRemoteTerminalEnableGrid", true);

    private static final ModConfigSpec.BooleanValue ADVANCED_REMOTE_TERMINAL_PATTERNS = BUILDER
        .comment("启用高级远程多功能终端的样板终端界面。")
        .define("advancedRemoteTerminalEnablePatterns", true);

    private static final ModConfigSpec.BooleanValue ADVANCED_REMOTE_TERMINAL_MANAGER = BUILDER
        .comment("启用高级远程多功能终端的自动合成仓管理器界面。")
        .define("advancedRemoteTerminalEnableManager", true);

    private static final ModConfigSpec.BooleanValue ADVANCED_REMOTE_TERMINAL_MONITOR = BUILDER
        .comment("启用高级远程多功能终端的自动合成仓监视器界面。")
        .define("advancedRemoteTerminalEnableMonitor", true);

    private static final ModConfigSpec.BooleanValue ADVANCED_REMOTE_TERMINAL_SEQUENCE = BUILDER
        .comment("启用高级远程多功能终端的序列装配样板终端界面。")
        .define("advancedRemoteTerminalEnableSequence", true);

    // ========== 调整一：自动合成仓存储改进 ==========
    private static final ModConfigSpec.BooleanValue AUTOCRAFTER_STORAGE_ENABLED = BUILDER
        .comment("改进自动合成仓：内部增加存储空间（物品/流体），确保单次合成产物能全部存入，玩家可单独提取。")
        .define("autocrafterStorageEnabled", true);

    private static final ModConfigSpec.IntValue AUTOCRAFTER_OUTPUT_SLOTS = BUILDER
        .comment("自动合成仓内部物品存储槽位数量。")
        .defineInRange("autocrafterOutputSlots", 256, 1, 256);

    private static final ModConfigSpec.IntValue AUTOCRAFTER_FLUID_CAPACITY = BUILDER
        .comment("自动合成仓内部流体/气体存储容量（mB）。")
        .defineInRange("autocrafterFluidCapacity", 256000, 1000, 100000000);

    // ========== 调整二：输入/输出总线 Tag 过滤 ==========
    private static final ModConfigSpec.BooleanValue TAG_FILTER_ENABLED = BUILDER
        .comment("允许输入/输出总线的过滤器使用 Tag 过滤（在过滤槽中放入带 tag_filter 数据组件的物品，值如 #minecraft:stone）。")
        .define("tagFilterEnabled", true);

    // ========== 开发日志总开关（唯一的开关，默认关闭） ==========
    private static final ModConfigSpec.BooleanValue DEV_LOGS = BUILDER
        .comment("开发日志总开关（默认 false = 发布版安静）。这是给开发/排查用的：开启后会输出大量逐件搬运、"
            + "计数、门控、账本、蓝图加载器等诊断行（前缀 [rscc-assembly] / [rscc-trace] / [rscc-ledger] /"
            + "[loader] / [rscc-range-charger] 等），用于定位「最终产物不回流 / 中间产物不回收 / 料在哪一段停住」"
            + "这类问题；日常游玩建议保持关闭，否则日志会明显变多。",
            "关闭时保留的日志（一条都不会少）：启动那一行版本指纹 [rscc-build]、会话锚点 [rscc]、"
                + "全部 WARN / ERROR、以及真正需要玩家知道的失败（例如读档丢弃条目、终端定位失败）。",
            "运行时切换：游戏内执行 /rs_create_compat devlogs on|off（等价旧写法 /rs_create_compat assemblydebug"
                + " on|off 与 /rs_create_compat debug assembly on|off）立即生效，不需要重启。",
            "本项与旧的 rsccAssemblyDebug 是同一个开关（已合并，不再保留两个语义重叠的配置项）；"
                + "改动配置文件后由配置重载事件自动同步，但重载以配置文件为准，会覆盖指令设置。")
        .define("devLogs", false);

    // ========== 序列装配：停滞 / 掉线检测（定时器） ==========
    private static final ModConfigSpec.IntValue ASSEMBLY_STALL_TIMEOUT_TICKS = BUILDER
        .comment("序列装配任务「停滞」判定的阈值（tick）：某条序列装配自动合成任务在连续这么多 tick 内既没有"
            + "相关子自动合成在跑、也没有中间产物回流、也没有任何进度变化时，视为「原料缺少至无法继续」，"
            + "触发一次处置横幅（只触发一次，处置由玩家在自动合成管理器里决定）。默认 100 tick = 5 秒。")
        .defineInRange("assemblyStallTimeoutTicks", 100, 1, 12000);

    private static final ModConfigSpec.IntValue ASSEMBLY_RECORD_EXPIRY_TICKS = BUILDER
        .comment("序列装配任务记录的过期 tick 数（上限保护）：记录在最长时间内没有被扫描到（任务已消失、"
            + "所在维度卸载等）即被回收。任务完成 / 取消 / 任务对象消失时本来就会立即移除，本项只是兜底，"
            + "防止任何异常情况下记录残留。默认 12000 tick = 10 分钟。")
        .defineInRange("assemblyRecordExpiryTicks", 12000, 100, 240000);

    // ========== 挂起 / 恢复（序列装配任务 + RS 原版自动合成任务共用同一套） ==========
    private static final ModConfigSpec.IntValue ASSEMBLY_OFFLINE_PERSIST_TICKS = BUILDER
        .comment("「执行器 / 机器掉线」判定的持续时长（tick）：RS 原版任务的状态里，机器拒绝接收（REJECTED）、"
            + "被锁定（LOCKED）、找不到可用接收端（NONE_FOUND）都可能是**瞬时**的（例如机器输入口刚好满了一瞬），"
            + "所以必须连续这么久都还是这个状态才算「掉线」，避免把健康任务误挂起。默认 40 tick = 2 秒。"
            + "（序列装配任务用的是「样板里指派的执行仓坐标不在网络里」这个**确定性**信号，不适用本项。）")
        .defineInRange("assemblyOfflinePersistTicks", 40, 1, 12000);

    private static final ModConfigSpec.IntValue ASSEMBLY_NO_PROGRESS_TIMEOUT_TICKS = BUILDER
        .comment("「无进展」判定的阈值（tick）：任务连续这么多 tick 完成度与各项计数都没有任何变化（既没在抽料、"
            + "也没在加工、也没在投料），视为卡住。这是兜底判据（前两项都不成立时才会用到），"
            + "所以默认给得比较宽：600 tick = 30 秒。")
        .defineInRange("assemblyNoProgressTimeoutTicks", 600, 40, 240000);

    private static final ModConfigSpec.IntValue ASSEMBLY_SUSPEND_LIMIT_TICKS = BUILDER
        .comment("单条任务的「连续挂起上限」（tick）：一条任务连续挂起超过这么久仍然恢复不了，就按"
            + "assemblySuspendOverflowReclaim 决定最终处置，避免无限静默堆积。默认 12000 tick = 10 分钟。")
        .defineInRange("assemblySuspendLimitTicks", 12000, 200, 2400000);

    private static final ModConfigSpec.BooleanValue ASSEMBLY_SUSPEND_OVERFLOW_RECLAIM = BUILDER
        .comment("挂起超限后的最终处置（默认 false = 继续挂起）：",
            "false = HOLD：继续挂起（永不自动取消），只在监视器里给出「已超限」提示；",
            "true  = RECLAIM：标记失败并**安全回收** —— 调用 RS 自己的取消路径"
                + "（AutocraftingNetworkComponent#cancel），该路径会把任务已抽出的中间产物原样还回网络，"
                + "不会销毁任何物品 / 流体。")
        .define("assemblySuspendOverflowReclaim", false);

    // ========== 框架：任意完整方块也可套壳（可选功能，默认关闭） ==========
    private static final ModConfigSpec.BooleanValue FRAME_ARBITRARY_BLOCKS = BUILDER
        .comment("是否允许分隔框架 / 伪装框架套在「任意完整方块」上（机器 / 容器 / 单元样板管理仓等）。",
            "false（默认）= 只允许 RS 线缆族与 Create 流体管道族，点其它方块一律不生效并给出简短提示；",
            "true = 任意整格完整方块（碰撞形状恰好是整格、非空气 / 非流体 / 不可替换）也能套壳。",
            "套壳从头到尾不替换方块、不碰方块实体，被套方块的能力 / 网络 / 内容一个字节都不变。",
            "本项在配置加载事件里读取一次，改动后需要重启客户端 / 服务端才生效。")
        .define("frameArbitraryBlocks", false);

    // ========== 扳手分离 RS 线缆 ==========
    private static final ModConfigSpec.ConfigValue<String> WRENCH_CABLE_DISCONNECT_MODE = BUILDER
        .comment("机械动力扳手（c:tools/wrench 标签）右键 RS 线缆时断开连接的生效方式，取值三选一：",
            "seam = 右键「接缝」（线缆伸向邻块的那一段）断开 / 恢复这一处的自动连接；",
            "face = 右键线缆的某个面，切换「该面是否自动连接」（六个面各自独立）；",
            "off  = 关闭：扳手完全不接管 RS 线缆，保持 Create / 原版原有行为。",
            "注意：只有当前生效档位记录下来的断开才会起作用（切档相当于换一套记录）；",
            "本项在配置加载事件里读取一次，改动后需要重启客户端 / 服务端才生效。")
        .define("wrenchCableDisconnectMode", "seam");

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;
    public static int universalDiskBaseCapacity;
    public static boolean universalDiskAllowMixedTypes;
    public static int rangeChargerChargeRate;
    public static int rangeChargerEnergyCapacity;
    public static int rangeChargerMaxTransfer;
    public static int rangeChargerMaxTargets;
    public static int rangeChargerRangePerUpgrade;
    public static boolean rangeChargerChargeBlocks;
    public static boolean rangeChargerChargeItems;
    public static boolean rangeChargerChargePlayerItems;
    public static int quantityKeeperEnergyUsage;
    public static int quantityKeeperDefaultTarget;
    public static int quantityKeeperDestroyRate;
    public static int quantityKeeperDestroyRatePerSpeed;
    public static int unitPatternManagerEnergyUsage;
    public static int collectionCacheScanInterval;
    public static int collectionCacheScanRadius;
    public static int collectionCacheIdleEnergyUsage;
    public static int collectionCacheWorkEnergyUsage;
    public static int collectionCacheEnergyPerSpeedUpgrade;
    public static int collectionCacheEnergyPerRadius;
    public static int collectionCacheSlotCapacity;
    public static int collectionCacheSlotCapacityPerStackUpgrade;
    public static int advancedRemoteTerminalEnergyCapacity;
    public static boolean advancedRemoteTerminalEnableGrid;
    public static boolean advancedRemoteTerminalEnablePatterns;
    public static boolean advancedRemoteTerminalEnableManager;
    public static boolean advancedRemoteTerminalEnableMonitor;
    public static boolean advancedRemoteTerminalEnableSequence;
    public static boolean tagFilterEnabled;
    /** 「开发日志总开关」的当前值（配置为初值，运行时可被指令覆盖；运行时实现见 {@link cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug#isEnabled()}）。 */
    public static boolean devLogs;
    /** 序列装配任务停滞判定的阈值（tick），默认 100 = 5 秒。 */
    public static int assemblyStallTimeoutTicks;
    /** 序列装配任务记录的过期 tick 数（兜底回收上限），默认 12000 = 10 分钟。 */
    public static int assemblyRecordExpiryTicks;
    /** 「执行器 / 机器掉线」判定的持续时长（tick），默认 40 = 2 秒。 */
    public static int assemblyOfflinePersistTicks;
    /** 「无进展」判定的阈值（tick），默认 600 = 30 秒。 */
    public static int assemblyNoProgressTimeoutTicks;
    /** 单条任务连续挂起的上限（tick），默认 12000 = 10 分钟。 */
    public static int assemblySuspendLimitTicks;
    /** 挂起超限后是否「安全回收」（走 RS 自己的取消路径），默认 false = 继续挂起。 */
    public static boolean assemblySuspendOverflowReclaim;
    public static boolean autocrafterStorageEnabled;
    public static int autocrafterOutputSlots;
    public static int autocrafterFluidCapacity;
    /** 是否允许两个框架套在「任意完整方块」上（默认 false = 只认线缆族 + 流体管道族，见 {@link #FRAME_ARBITRARY_BLOCKS}）。 */
    public static boolean frameArbitraryBlocks;
    /** 扳手分离 RS 线缆的生效档位（seam / face / off，见 {@link #WRENCH_CABLE_DISCONNECT_MODE}）。 */
    public static cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode wrenchCableDisconnectMode =
        cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode.SEAM;

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    public static void onLoad(final ModConfigEvent event) {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        universalDiskAllowMixedTypes = UNIVERSAL_DISK_ALLOW_MIXED_TYPES.get();

        rangeChargerChargeRate = RANGE_CHARGER_CHARGE_RATE.get();
        rangeChargerEnergyCapacity = RANGE_CHARGER_ENERGY_CAPACITY.get();
        rangeChargerMaxTransfer = RANGE_CHARGER_MAX_TRANSFER.get();
        rangeChargerMaxTargets = RANGE_CHARGER_MAX_TARGETS.get();
        rangeChargerRangePerUpgrade = RANGE_CHARGER_RANGE_PER_UPGRADE.get();
        rangeChargerChargeBlocks = RANGE_CHARGER_CHARGE_BLOCKS.get();
        rangeChargerChargeItems = RANGE_CHARGER_CHARGE_ITEMS.get();
        rangeChargerChargePlayerItems = RANGE_CHARGER_CHARGE_PLAYER_ITEMS.get();

        quantityKeeperEnergyUsage = QUANTITY_KEEPER_ENERGY_USAGE.get();
        quantityKeeperDefaultTarget = QUANTITY_KEEPER_DEFAULT_TARGET.get();
        quantityKeeperDestroyRate = QUANTITY_KEEPER_DESTROY_RATE.get();
        quantityKeeperDestroyRatePerSpeed = QUANTITY_KEEPER_DESTROY_RATE_PER_SPEED.get();
        unitPatternManagerEnergyUsage = UNIT_PATTERN_MANAGER_ENERGY_USAGE.get();

        collectionCacheScanInterval = COLLECTION_CACHE_SCAN_INTERVAL.get();
        collectionCacheScanRadius = COLLECTION_CACHE_SCAN_RADIUS.get();
        collectionCacheIdleEnergyUsage = COLLECTION_CACHE_IDLE_ENERGY_USAGE.get();
        collectionCacheWorkEnergyUsage = COLLECTION_CACHE_WORK_ENERGY_USAGE.get();
        collectionCacheEnergyPerSpeedUpgrade = COLLECTION_CACHE_ENERGY_PER_SPEED_UPGRADE.get();
        collectionCacheEnergyPerRadius = COLLECTION_CACHE_ENERGY_PER_RADIUS.get();
        collectionCacheSlotCapacity = COLLECTION_CACHE_SLOT_CAPACITY.get();
        collectionCacheSlotCapacityPerStackUpgrade = COLLECTION_CACHE_SLOT_CAPACITY_PER_STACK_UPGRADE.get();

        advancedRemoteTerminalEnergyCapacity = ADVANCED_REMOTE_TERMINAL_ENERGY_CAPACITY.get();
        advancedRemoteTerminalEnableGrid = ADVANCED_REMOTE_TERMINAL_GRID.get();
        advancedRemoteTerminalEnablePatterns = ADVANCED_REMOTE_TERMINAL_PATTERNS.get();
        advancedRemoteTerminalEnableManager = ADVANCED_REMOTE_TERMINAL_MANAGER.get();
        advancedRemoteTerminalEnableMonitor = ADVANCED_REMOTE_TERMINAL_MONITOR.get();
        advancedRemoteTerminalEnableSequence = ADVANCED_REMOTE_TERMINAL_SEQUENCE.get();

        tagFilterEnabled = TAG_FILTER_ENABLED.get();
        devLogs = DEV_LOGS.get();
        // 开发日志的运行时开关以配置为初值（之后可由指令覆盖；配置重载会再次以文件为准同步）
        cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug.initFromConfig(devLogs);
        assemblyStallTimeoutTicks = ASSEMBLY_STALL_TIMEOUT_TICKS.get();
        assemblyRecordExpiryTicks = ASSEMBLY_RECORD_EXPIRY_TICKS.get();
        assemblyOfflinePersistTicks = ASSEMBLY_OFFLINE_PERSIST_TICKS.get();
        assemblyNoProgressTimeoutTicks = ASSEMBLY_NO_PROGRESS_TIMEOUT_TICKS.get();
        assemblySuspendLimitTicks = ASSEMBLY_SUSPEND_LIMIT_TICKS.get();
        assemblySuspendOverflowReclaim = ASSEMBLY_SUSPEND_OVERFLOW_RECLAIM.get();
        autocrafterStorageEnabled = AUTOCRAFTER_STORAGE_ENABLED.get();
        autocrafterOutputSlots = AUTOCRAFTER_OUTPUT_SLOTS.get();
        autocrafterFluidCapacity = AUTOCRAFTER_FLUID_CAPACITY.get();

        // 「任意完整方块也可套壳」的开关（默认 false）：判定侧每次读这个静态字段，因此改动后要重启才生效
        frameArbitraryBlocks = FRAME_ARBITRARY_BLOCKS.get();

        // 扳手断线档位：非法值一律回落到默认档 seam（配置改动需重启才生效）
        final String rawDisconnectMode = WRENCH_CABLE_DISCONNECT_MODE.get();
        wrenchCableDisconnectMode = cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode.byName(rawDisconnectMode);
        if (wrenchCableDisconnectMode == null) {
            wrenchCableDisconnectMode = cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode.SEAM;
            LOGGER.warn("未知的 wrenchCableDisconnectMode 取值「{}」，已回落到 seam（可选 seam / face / off）",
                rawDisconnectMode);
        }

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}
