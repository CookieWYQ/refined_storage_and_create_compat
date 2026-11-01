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
        .comment("范围充电器单个目标的基准充电速率（FE/tick）。默认较慢，可放入速度升级成倍加快。",
            "第 127 轮：本项只作用于「工具档」——玩家物品 / 掉落物 / 非立方的 FE 方块。",
            "FE 能量立方是「电池」，按它自己的面速率 feEnergyCubeMaxTransfer（默认 10 万 FE/t）结算，",
            "既不吃本项、也不参与 rangeChargerMaxTargets 的均分（用户口径：「一面墙的立方应该瞬间被充满」",
            "「立方本身是导线，充电器接上去吸的是整个集群的电量」）。要整体调慢立方充电请改",
            "feEnergyCubeMaxTransfer（那同时是立方自己六面的输入/输出上限）。")
        .defineInRange("rangeChargerChargeRate", 500, 1, 1000000);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_ENERGY_CAPACITY = BUILDER
        .comment("范围充电器的能量缓存上限（FE）。",
            "第 99 轮：默认值从 1000 万改成 5000 万，与新增的「FE 能量立方」单块容量保持一致",
            "（用户要求：范围充电器的缓存也改到 5000 万 FE，与 FE 能量立方保证一致）。")
        .defineInRange("rangeChargerEnergyCapacity", 50000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_MAX_TRANSFER = BUILDER
        .comment("范围充电器接收外部能量的最大速率（FE/tick）。")
        .defineInRange("rangeChargerMaxTransfer", 100000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_NETWORK_PULL = BUILDER
        .comment("范围充电器每 tick 最多从 RS 网络能量池抽取多少 FE 存进自己的缓存（默认 100000）。",
            "第 99 轮新增：缓存从 1000 万提到 5000 万后，若仍按「缓存缺多少就抽多少」的旧口径，",
            "空缓存时一个 tick 就能从网络抽走 5000 万，把同一网络里其它机器当 tick 饿死。",
            "本项就是那道闸：抽电速度由它决定，缓存多大只影响「能存多少」，不影响「一次能抢多少」。",
            "抽到的电只用于给范围内的目标充电，不会被浪费（回写的口径见 RangeChargerNetworkNode）。")
        .defineInRange("rangeChargerNetworkPullPerTick", 100000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_MAX_TARGETS = BUILDER
        .comment("范围充电器每 tick 最多充电的对象数量。",
            "这是【每一类】的上限：方块 / 掉落物 / 玩家物品各自最多这么多个（三类互不挤占）。",
            "同一个数还用来把本 tick 的电量均分给各个目标：单个目标一次最多拿「当前电量 / 本值」，",
            "因此任何一个大电容目标（如永远充不满的 RS 控制器）都不可能一次吃光整罐电、",
            "把后面的无线终端饿死。调大 = 覆盖更多目标但每个更慢。")
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

    private static final ModConfigSpec.IntValue RANGE_CHARGER_CURIOS_SCAN_INTERVAL = BUILDER
        .comment("范围充电器扫描玩家饰品槽（Curios）的间隔（tick）。默认 5（每 0.25 秒一次）。",
            "背包 / 快捷栏 / 盔甲 / 副手仍然每 tick 扫描（既有行为不变），饰品槽按本间隔节流，",
            "避免「每 tick 遍历所有玩家所有饰品槽」这种纯浪费；调大 = 更省，调小 = 充电更快。",
            "未安装 Curios 时这一项没有任何效果（扫描直接跳过，不报错）。")
        .defineInRange("rangeChargerCuriosScanInterval", 5, 1, 1200);

    private static final ModConfigSpec.BooleanValue RANGE_CHARGER_HIGHLIGHT_TARGETS = BUILDER
        .comment("是否把范围充电器「此刻正在充电的目标」高亮出来（默认 true）。",
            "方块目标：画一层高亮框；掉落物目标：给掉落物发光；玩家（身上有正在充电的物品）：给玩家发光。",
            "关闭后不再高亮，只会在玩家打开充电器界面 / 瞄准它时提示一句「该功能已在配置中关闭」——",
            "提醒本身是「简单提一句」，不是每次充电都刷屏。",
            "本项是纯客户端表现：服务端只负责把「本 tick 充了谁」同步下来，专服读不到也用不到它；",
            "但它决定「要不要同步」—— 关闭时服务端一个包都不发（省带宽）。要实时生效请重启（服务端读一次）。")
        .define("rangeChargerHighlightTargets", true);

    // ========== 网格搜索框：裸文本也搜 tooltip ==========
    private static final ModConfigSpec.BooleanValue GRID_SEARCH_MATCHES_TOOLTIP = BUILDER
        .comment("网格搜索框里的「裸文本」是否也匹配物品 tooltip（默认 true）。",
            "true  = 输入「水下呼吸」能搜到写着「水下呼吸 III」的附魔书（名字没命中时才生成 tooltip，按会话缓存）；",
            "false = 退回 RS 原版行为：裸文本只比名字，`$水下呼吸` / `#tag` / `@mod` 三种前缀语法完全不受影响。",
            "代价（如实写在这里，因为它会影响手感）：开着时，第一次搜索要为网络里「名字没命中」的物品",
            "各生成一次 tooltip（在客户端主线程上），几千种物品的大网络首搜可能顿一下；关掉就完全不生成。",
            "本项是纯客户端表现：只影响客户端解析搜索词，不影响服务端、不影响 JEI、不影响存档。",
            "运行时切换：下一次敲键就生效（解析每次搜索都读这一项），不需要重启。")
        .define("gridSearchMatchesTooltip", true);

    // ========== 定量保持器 ==========
    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_ENERGY_USAGE = BUILDER
        .comment("定量保持器每 tick 的网络能量消耗（FE）。")
        .defineInRange("quantityKeeperEnergyUsage", 10, 1, 100000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DEFAULT_TARGET = BUILDER
        .comment("定量保持器的默认目标数量。")
        .defineInRange("quantityKeeperDefaultTarget", 64, 1, 1000000000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DESTROY_RATE = BUILDER
        .comment("定量保持器每秒基础销毁的过量数量（无升级时，默认逐个销毁；每个速度/堆叠升级翻倍）。",
            "单位为「每秒」而不是「每 tick」：消费端 KeeperOverflow 按 20 tick = 1 秒换算",
            "（见 QuantityKeeperBlockEntity#getDestroyRate），改这一项会按 20 倍影响手感。")
        .defineInRange("quantityKeeperDestroyRate", 1, 1, 1000000);

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

    // ========== 区域选定器（高级归流缓存仓：区域取代 XYZ 半径）==========
    // 用户拍板：区域上限「每区域 32×32×32、最多 16 个」并做成可配置 —— 就这两项。
    // 绝对硬上限（support/AreaSelection.ABSOLUTE_MAX_AREAS = 64、Area.MAX_SIDE = 128）不随配置放大，
    // 避免一个恶意包 / 手改配置把服务端拖进超大扫描。
    private static final ModConfigSpec.IntValue AREA_SELECTOR_MAX_AREAS = BUILDER
        .comment("一个「区域选定器」最多能保存多少个区域（默认 16）。",
            "上限同时约束：客户端提交（超过即拒绝并提示）、服务端在 ADD / REPLACE 时校验新几何",
            "（超限即拒绝，但绝不对物品上已有的区域做截断）、以及高级归流缓存仓每轮扫描的区域个数。",
            "绝对硬上限 64（配置写得再大也按 64 处理）。",
            "改小后旧存档里超出的区域不会丢：物品上的数据保留，只是不参与扫描（放回大值即恢复）。")
        .defineInRange("areaSelectorMaxAreas", 16, 1, 64);

    private static final ModConfigSpec.IntValue AREA_SELECTOR_MAX_VOLUME = BUILDER
        .comment("单个区域的最大体积（格，默认 32768 = 32×32×32）。",
            "32³ 在「每 tick 2048 次方块查询」的预算下 16 tick 扫完一遍，正好落在归流缓存仓",
            "默认 20 tick 的扫描节奏内（即每秒至少完整扫一遍）。超出的区域在写入时被拒绝、",
            "扫描时被忽略（不会把机器拖到每 tick 几十万次 getBlockState）。")
        .defineInRange("areaSelectorMaxVolume", 32768, 1, 1048576);

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
                + " on|off 与 /rs_create_compat debug assembly on|off）立即生效，不需要重启；"
                + "/rs_create_compat diag run 只在导出那一下临时打开，导出完立刻恢复，不会让开关一直开着。",
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

    private static final ModConfigSpec.BooleanValue SEQUENCE_STATION_DERIVED_CATEGORIES = BUILDER
        .comment("序列装配输出总线的「默认导出类别」是否按『总线朝向那台机器能处理哪些步』推导（第 104 轮 B-1）。",
            "true（默认）= 玩家从没手动勾过类别的总线，默认集由工位推导 —— 零配置即正确；",
            "false = 退回旧行为：未显式配置的总线 = 全输入 + 全中间产物。",
            "手动画过勾的总线（categories_explicit = true）不受本项影响，永远以玩家勾选为准。",
            "推导不出唯一答案时一律退回旧默认集，绝不因为本项而少喂。")
        .define("sequenceStationDerivedCategories", true);

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

    // ========== Jade 总线提示（用户第 8 项：默认一直显示，可切成「按 Shift 显示」） ==========
    private static final ModConfigSpec.ConfigValue<String> JADE_BUS_TOOLTIP_MODE = BUILDER
        .comment("序列装配总线（输入总线 / 输出总线）在 Jade 瞄准提示里显示「归属执行仓 + 负责的类别」的方式，"
            + "取值二选一：",
            "always = 一直显示（默认）；",
            "shift  = 按住 Shift 才显示（没按住时只留一行「按住 Shift 查看总线状态」）。",
            "本项是纯客户端表现：服务端读不到也用不到它，专服与单人世界的行为不会因此分叉。",
            "运行时切换：这一项每次画提示时都重新读，配置被重载（游戏内改配置 / 重载配置）后"
                + "立刻生效，不需要重启客户端。")
        .define("jadeBusTooltipMode", "always");

    // ========== FE 能量立方（第 99 轮新增） ==========
    private static final ModConfigSpec.IntValue FE_ENERGY_CUBE_CAPACITY = BUILDER
        .comment("「FE 能量立方」单块的缓存上限（FE）。默认 5000 万。",
            "相邻（六面接触）的多个能量立方容量共享：N 块 = N × 本值；",
            "但每块只按自身实际存储记账 —— 破坏某一块时它只带走自己的电量，不做平均分配。")
        .defineInRange("feEnergyCubeCapacity", 50000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue FE_ENERGY_CUBE_MAX_TRANSFER = BUILDER
        .comment("FE 能量立方每个面的最大输入/输出速率（FE/tick）。六面都按本值限速。")
        .defineInRange("feEnergyCubeMaxTransfer", 100000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.ConfigValue<String> FE_ENERGY_CUBE_CHARGE_MODE = BUILDER
        .comment("能量立方的「允许充能」判据模式，二选一：",
            "percent  = 控制器电量百分比 ≥ feEnergyCubeChargeThresholdPercent 才允许立方吸收能量（默认）；",
            "absolute = 控制器电量（FE）≥ feEnergyCubeChargeThresholdAbsolute 才允许立方吸收能量。",
            "为什么要有这道闸：能量立方是备用电池，不能和网络抢电 —— 网络还健康（控制器电量高）时",
            "才把富余能量存进立方；控制器电量掉到阈值以下后立方只放电、不再充电。")
        .define("feEnergyCubeChargeMode", "percent");

    private static final ModConfigSpec.IntValue FE_ENERGY_CUBE_CHARGE_THRESHOLD_PERCENT = BUILDER
        .comment("percent 模式下允许充能的控制器电量百分比阈值（默认 90）。取值 1~100。")
        .defineInRange("feEnergyCubeChargeThresholdPercent", 90, 1, 100);

    private static final ModConfigSpec.IntValue FE_ENERGY_CUBE_CHARGE_THRESHOLD_ABSOLUTE = BUILDER
        .comment("absolute 模式下允许充能的控制器电量阈值（FE，默认 0 = 只要控制器还有一点电就允许充能）。")
        .defineInRange("feEnergyCubeChargeThresholdAbsolute", 0, 0, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue FE_ENERGY_CUBE_NETWORK_PULL_PER_TICK = BUILDER
        .comment("每 tick 最多从 RS 网络能量池往能量立方里搬多少 FE（默认 10000）。",
            "这道限速是「别让一个立方把整个网络瞬间抽空」的兜底，不是充能闸；真正的闸是上面的阈值模式。")
        .defineInRange("feEnergyCubeNetworkPullPerTick", 10000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue FE_ENERGY_CUBE_PUSH_PER_TICK = BUILDER
        .comment("能量立方每 tick 主动向相邻方块（六面）推送的最大 FE（默认 10000）。",
            "这是「立方 = 传递介质 / 缓存」的主动供能速率：贴着范围充电器、其它 FE 机器时，",
            "立方会把电推过去让它们先充满（立方自己只是最后一级缓存）。",
            "推送前先对收方 simulate，能收多少推多少；**不推给别的能量立方**（集群内部已有容量共享，",
            "避免相邻立方之间来回倒电）。")
        .defineInRange("feEnergyCubePushPerTick", 10000, 1, Integer.MAX_VALUE);

    // ========== 电动飞行背包（第 99 轮新增） ==========
    private static final ModConfigSpec.IntValue ELECTRIC_JETPACK_CAPACITY = BUILDER
        .comment("电动飞行背包的电量上限（FE）。默认 5000 万。")
        .defineInRange("electricJetpackCapacity", 50000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue ELECTRIC_JETPACK_MAX_TRANSFER = BUILDER
        .comment("电动飞行背包的最大充放电速率（FE/tick）。")
        .defineInRange("electricJetpackMaxTransfer", 100000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue ELECTRIC_JETPACK_FLIGHT_COST_PER_TICK = BUILDER
        .comment("电动飞行背包在「飞行 / 悬停 / 下降」时每 tick 消耗的 FE（默认 8）。",
            "口径统一：只要玩家处于飞行状态就按本值扣电，不区分上升/悬停/下降；",
            "电量不够扣一次时就立刻取消飞行（玩家会掉下去），不会给出「免费飞行」。")
        .defineInRange("electricJetpackFlightCostPerTick", 8, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue ELECTRIC_JETPACK_LOW_POWER_MINUTES = BUILDER
        .comment("电动飞行背包的低电提示阈值：**剩余可飞行时间（分钟）**，默认 30。",
            "算法：剩余分钟 = 当前电量 / (electricJetpackFlightCostPerTick × 20 tick/s × 60 s)；",
            "只有「剩余可飞分钟 < 本值」才提示（等于本值不提示，严格小于）；范围 1..100000。",
            "为什么不用百分比（第 109 轮用户实机反馈）：容量默认 5000 万 FE，旧口径 10% = 500 万 FE，",
            "充了 200 万（才 4%）明明还能飞好几个小时却一直被判「电量不足」——按「还能飞多久」判定才符合直觉。")
        .defineInRange("electricJetpackLowPowerMinutes", 30, 1, 100000);

    // ========== 终端「RS 网络能耗监视」页（第 99 轮新增） ==========
    private static final ModConfigSpec.IntValue ENERGY_AUDIT_MAX_NODES = BUILDER
        .comment("能耗监视页单份快照最多列出多少个网络节点（默认 512）。",
            "超出后按「耗电从高到低」截断，界面会明确标注「已截断」——不静默丢数据。",
            "上限存在的理由：一次快照要塞进一个 S2C 包，节点成千上万时不分批会把包撑爆。")
        .defineInRange("energyAuditMaxNodes", 512, 8, 4096);

    private static final ModConfigSpec.IntValue ENERGY_AUDIT_MAX_TYPES = BUILDER
        .comment("能耗监视页最多显示多少种方块类型（默认 256）。同类型方块会合并成一行。")
        .defineInRange("energyAuditMaxTypes", 256, 8, 2048);

    private static final ModConfigSpec.IntValue ENERGY_AUDIT_REFRESH_TICKS = BUILDER
        .comment("能耗监视页打开时的刷新间隔（tick，默认 20 = 1 秒）。",
            "界面每过一个间隔发一次 C2S 请求、服务端回一份完整快照；界面关掉就自然停止。",
            "调小 = 更实时但更费带宽；调大 = 更省但不易看出波动。")
        .defineInRange("energyAuditRefreshTicks", 20, 10, 200);

    private static final ModConfigSpec.IntValue ENERGY_AUDIT_HIGHLIGHT_TTL = BUILDER
        .comment("能耗监视页里「高亮某台方块」在世界中的显示时长（tick，默认 60 = 3 秒）。",
            "每收到一份新快照会重新续期，因此选中期间它一直在；取消选中后最多再亮这么久。")
        .defineInRange("energyAuditHighlightTtlTicks", 60, 5, 1200);

    // ========== 降解室发电机（第 101 轮新增） ==========
    private static final ModConfigSpec.IntValue DECOMPOSITION_GENERATOR_CAPACITY = BUILDER
        .comment("「降解室发电机」的内部能量缓存上限（FE，默认 100 万）。",
            "它只能输出、不能输入：缓存只由降解植物产生，再由相邻供电 / RS 网络取走。")
        .defineInRange("decompositionGeneratorCapacity", 1000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue DECOMPOSITION_GENERATOR_PUSH = BUILDER
        .comment("降解室发电机每 tick 向相邻方块推送的最大能量（FE/t，默认 1000）。",
            "这是「贴着控制器也能给它充电」这条路径的限速；RS 网络自身取电走网络能量池，不受本项限制。")
        .defineInRange("decompositionGeneratorPushPerTick", 1000, 1, Integer.MAX_VALUE);

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
    /** 每 tick 最多从 RS 网络抽多少 FE 进充电器缓存（第 99 轮新增，见 {@link #RANGE_CHARGER_NETWORK_PULL}）。 */
    public static int rangeChargerNetworkPullPerTick;
    public static int rangeChargerMaxTargets;
    /** 是否高亮「此刻正在充电的目标」（方块框 / 掉落物发光 / 玩家发光），默认 true，见 {@link #RANGE_CHARGER_HIGHLIGHT_TARGETS}。 */
    public static boolean rangeChargerHighlightTargets;
    /** 网格搜索框的裸文本是否也匹配 tooltip（默认 true），见 {@link #GRID_SEARCH_MATCHES_TOOLTIP}。 */
    public static boolean gridSearchMatchesTooltip;
    public static int rangeChargerRangePerUpgrade;
    public static boolean rangeChargerChargeBlocks;
    public static boolean rangeChargerChargeItems;
    public static boolean rangeChargerChargePlayerItems;
    /** 饰品槽（Curios）扫描间隔（tick）：背包族每 tick 扫，饰品槽按这个周期扫，见 {@link #RANGE_CHARGER_CURIOS_SCAN_INTERVAL}。 */
    public static int rangeChargerCuriosScanInterval;
    // ========== FE 能量立方 / 电动飞行背包（第 99 轮） ==========
    /** 单块能量立方的容量（FE），见 {@link #FE_ENERGY_CUBE_CAPACITY}。 */
    public static int feEnergyCubeCapacity;
    /** 能量立方每个面的输入/输出限速（FE/tick）。 */
    public static int feEnergyCubeMaxTransfer;
    /** 充能判据原始字符串（percent / absolute），解析结果见 {@link #feEnergyCubeChargeModeAbsolute}。 */
    public static String feEnergyCubeChargeMode = "percent";
    /** true = 绝对 FE 阈值；false = 控制器电量百分比阈值（默认）。 */
    public static boolean feEnergyCubeChargeModeAbsolute;
    /** percent 模式的阈值（1~100）。 */
    public static int feEnergyCubeChargeThresholdPercent;
    /** absolute 模式的阈值（FE）。 */
    public static int feEnergyCubeChargeThresholdAbsolute;
    /** 每 tick 最多从网络能量池搬进立方的 FE。 */
    public static int feEnergyCubeNetworkPullPerTick;
    /** 每 tick 主动向相邻方块推送的 FE 上限（立方作为「传递介质」）。 */
    public static int feEnergyCubePushPerTick;
    /** 电动飞行背包电量上限（FE）。 */
    public static int electricJetpackCapacity;
    /** 电动飞行背包充放电限速（FE/tick）。 */
    public static int electricJetpackMaxTransfer;
    /** 飞行/悬停/下降每 tick 的耗电（FE）。 */
    public static int electricJetpackFlightCostPerTick;
    /** 低电提示阈值（剩余可飞分钟；第 109 轮由「剩余百分比」改成这个，默认 30）。 */
    public static int electricJetpackLowPowerMinutes;
    /** 能耗监视：单份快照的最大节点数。 */
    public static int energyAuditMaxNodes;
    /** 能耗监视：最大方块类型数。 */
    public static int energyAuditMaxTypes;
    /** 能耗监视：界面刷新间隔（tick）。 */
    public static int energyAuditRefreshTicks;
    /** 能耗监视：高亮显示时长（tick）。 */
    public static int energyAuditHighlightTtlTicks;
    /** 降解室发电机内部能量缓存上限（FE）。 */
    public static int decompositionGeneratorCapacity;
    /** 降解室发电机每 tick 向相邻方块推送的上限（FE/t）。 */
    public static int decompositionGeneratorPushPerTick;
    public static int quantityKeeperEnergyUsage;
    public static int quantityKeeperDefaultTarget;
    public static int quantityKeeperDestroyRate;
    public static int unitPatternManagerEnergyUsage;
    public static int collectionCacheScanInterval;
    public static int collectionCacheScanRadius;
    public static int collectionCacheIdleEnergyUsage;
    public static int collectionCacheWorkEnergyUsage;
    public static int collectionCacheEnergyPerSpeedUpgrade;
    public static int collectionCacheEnergyPerRadius;
    public static int collectionCacheSlotCapacity;
    public static int collectionCacheSlotCapacityPerStackUpgrade;
    /** 一个区域选定器最多保存的区域个数（默认 16；绝对硬上限见 {@code AreaSelection.ABSOLUTE_MAX_AREAS}）。 */
    public static int areaSelectorMaxAreas;
    /** 单个区域的最大体积（格，默认 32768 = 32³）。 */
    public static int areaSelectorMaxVolume;
    public static int advancedRemoteTerminalEnergyCapacity;
    public static boolean advancedRemoteTerminalEnableGrid;
    public static boolean advancedRemoteTerminalEnablePatterns;
    public static boolean advancedRemoteTerminalEnableManager;
    public static boolean advancedRemoteTerminalEnableMonitor;
    public static boolean advancedRemoteTerminalEnableSequence;
    public static boolean tagFilterEnabled;
    /**
     * 「开发日志总开关」的配置<b>镜像值</b>（仅供 {@code Config.load} 喂给运行时开关，<b>禁止</b>用作
     * 「是否打印」的判据 —— 判据唯一入口是
     * {@link cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug#isEnabled()}，
     * 因为指令可以临时覆盖它，直接读这个字段会得到与运行时开关不一致的旧值，正是
     * 「关了开关还在刷」这类缺陷的来源；见第 122 轮自检 {@code tools/selfcheck_round122_devlog_gate.py}）。
     */
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
    /**
     * 是否按「总线朝向的工位」推导输出总线的默认导出类别集（默认 true，见第 104 轮 B-1）。
     * <p>字段默认值就是 true：配置加载前就有人读也拿到「新行为」；配置里置 false 则整体退回
     * 「未显式配置 = 全输入 + 全中间产物」的旧行为。判定侧每次读这个静态字段，改动即时生效。</p>
     */
    public static boolean sequenceStationDerivedCategories = true;
    public static boolean autocrafterStorageEnabled;
    public static int autocrafterOutputSlots;
    public static int autocrafterFluidCapacity;
    /** 是否允许两个框架套在「任意完整方块」上（默认 false = 只认线缆族 + 流体管道族，见 {@link #FRAME_ARBITRARY_BLOCKS}）。 */
    public static boolean frameArbitraryBlocks;
    /** 扳手分离 RS 线缆的生效档位（seam / face / off，见 {@link #WRENCH_CABLE_DISCONNECT_MODE}）。 */
    public static cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode wrenchCableDisconnectMode =
        cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode.SEAM;
    /**
     * Jade 总线提示是否<b>只在按住 Shift 时</b>显示（{@code false} = 一直显示，这是默认值）。
     * <p>由 {@link #JADE_BUS_TOOLTIP_MODE} 解析而来（{@code always} / {@code shift}）；
     * 客户端每画一次提示就读一次，因此配置重载后立刻生效。</p>
     */
    public static boolean jadeBusTooltipShiftOnly = false;

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    /** 新默认容量（5000 万 FE，与 FE 能量立方一致）。 */
    public static final int RANGE_CHARGER_CAPACITY_DEFAULT = 50000000;
    /**
     * 历史默认值（1.0.x = 100 万；1.1.0~1.2.0-beta.6 = 1000 万）。
     * <p>迁移只认这两个「恰好等于历史默认值」的实例：这是「玩家从没改过」的唯一可判定证据。
     * 玩家自己调过的任何其它数值（哪怕 999999）一律保留。</p>
     */
    private static final int[] RANGE_CHARGER_LEGACY_DEFAULTS = {1_000_000, 10_000_000};

    /**
     * 范围充电器缓存：老配置文件（值恰好等于历史默认值）一次性迁移到 5000 万。
     *
     * <p><b>为什么必须做</b>：NeoForge 的 {@code ModConfigSpec} 只在键缺失时写默认值；本机
     * {@code run/config/rs_create_compat-common.toml} 里至今还留着 100 万，1.1.0 老玩家是 100 万、
     * 1.2.0-beta 玩家是 1000 万。只改 {@code .defineInRange(..., 50000000, ...)} 对这些实例
     * <b>完全无效</b>（用户会以为「改了他」）。</p>
     *
     * <p><b>为什么只在 commonSetup 调</b>：{@code ConfigValue#set} 会写文件，必须等配置真的加载完
     * （{@code ModConfigEvent} 里调用有重入风险）；commonSetup 在配置加载之后、世界加载之前。</p>
     *
     * <p>任何异常都吞掉并只打一条日志：迁移失败最多是「还是老值」，绝不能因此让游戏起不来。</p>
     */
    public static void migrateLegacyRangeChargerCapacity() {
        try {
            final int current = RANGE_CHARGER_ENERGY_CAPACITY.get();
            if (current == RANGE_CHARGER_CAPACITY_DEFAULT) {
                return;
            }
            boolean legacy = false;
            for (final int old : RANGE_CHARGER_LEGACY_DEFAULTS) {
                if (current == old) {
                    legacy = true;
                    break;
                }
            }
            if (!legacy) {
                return; // 玩家自己调过：不覆盖
            }
            RANGE_CHARGER_ENERGY_CAPACITY.set(RANGE_CHARGER_CAPACITY_DEFAULT);
            rangeChargerEnergyCapacity = RANGE_CHARGER_CAPACITY_DEFAULT;
            org.slf4j.LoggerFactory.getLogger("rs_create_compat").info(
                "[rscc-config] 范围充电器缓存从历史默认值 {} FE 迁移到 {} FE（与 FE 能量立方一致）。"
                    + "如需自定义，请直接改 rangeChargerEnergyCapacity。", current, RANGE_CHARGER_CAPACITY_DEFAULT);
        } catch (final RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("rs_create_compat").warn(
                "[rscc-config] 范围充电器缓存迁移失败（保持原值）：{}", e.toString());
        }
    }

    public static void onLoad(final ModConfigEvent event) {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        universalDiskAllowMixedTypes = UNIVERSAL_DISK_ALLOW_MIXED_TYPES.get();

        rangeChargerChargeRate = RANGE_CHARGER_CHARGE_RATE.get();
        rangeChargerEnergyCapacity = RANGE_CHARGER_ENERGY_CAPACITY.get();
        rangeChargerMaxTransfer = RANGE_CHARGER_MAX_TRANSFER.get();
        rangeChargerNetworkPullPerTick = RANGE_CHARGER_NETWORK_PULL.get();
        rangeChargerMaxTargets = RANGE_CHARGER_MAX_TARGETS.get();
        rangeChargerHighlightTargets = RANGE_CHARGER_HIGHLIGHT_TARGETS.get();
        gridSearchMatchesTooltip = GRID_SEARCH_MATCHES_TOOLTIP.get();
        rangeChargerRangePerUpgrade = RANGE_CHARGER_RANGE_PER_UPGRADE.get();
        rangeChargerChargeBlocks = RANGE_CHARGER_CHARGE_BLOCKS.get();
        rangeChargerChargeItems = RANGE_CHARGER_CHARGE_ITEMS.get();
        rangeChargerChargePlayerItems = RANGE_CHARGER_CHARGE_PLAYER_ITEMS.get();
        rangeChargerCuriosScanInterval = RANGE_CHARGER_CURIOS_SCAN_INTERVAL.get();

        // 第 99 轮：FE 能量立方 / 电动飞行背包
        feEnergyCubeCapacity = FE_ENERGY_CUBE_CAPACITY.get();
        feEnergyCubeMaxTransfer = FE_ENERGY_CUBE_MAX_TRANSFER.get();
        feEnergyCubeChargeMode = FE_ENERGY_CUBE_CHARGE_MODE.get();
        // 解析口径：只有明确写 absolute 才走绝对阈值，其余（含拼错）一律按 percent —— 
        // 「拼错就静默变成另一个语义」比「拼错按默认走」更难排查，故按默认值走并在注释写死。
        feEnergyCubeChargeModeAbsolute = "absolute".equalsIgnoreCase(feEnergyCubeChargeMode);
        feEnergyCubeChargeThresholdPercent = FE_ENERGY_CUBE_CHARGE_THRESHOLD_PERCENT.get();
        feEnergyCubeChargeThresholdAbsolute = FE_ENERGY_CUBE_CHARGE_THRESHOLD_ABSOLUTE.get();
        feEnergyCubeNetworkPullPerTick = FE_ENERGY_CUBE_NETWORK_PULL_PER_TICK.get();
        feEnergyCubePushPerTick = FE_ENERGY_CUBE_PUSH_PER_TICK.get();
        electricJetpackCapacity = ELECTRIC_JETPACK_CAPACITY.get();
        electricJetpackMaxTransfer = ELECTRIC_JETPACK_MAX_TRANSFER.get();
        electricJetpackFlightCostPerTick = ELECTRIC_JETPACK_FLIGHT_COST_PER_TICK.get();
        electricJetpackLowPowerMinutes = ELECTRIC_JETPACK_LOW_POWER_MINUTES.get();
        energyAuditMaxNodes = ENERGY_AUDIT_MAX_NODES.get();
        energyAuditMaxTypes = ENERGY_AUDIT_MAX_TYPES.get();
        energyAuditRefreshTicks = ENERGY_AUDIT_REFRESH_TICKS.get();
        energyAuditHighlightTtlTicks = ENERGY_AUDIT_HIGHLIGHT_TTL.get();
        decompositionGeneratorCapacity = DECOMPOSITION_GENERATOR_CAPACITY.get();
        decompositionGeneratorPushPerTick = DECOMPOSITION_GENERATOR_PUSH.get();

        quantityKeeperEnergyUsage = QUANTITY_KEEPER_ENERGY_USAGE.get();
        quantityKeeperDefaultTarget = QUANTITY_KEEPER_DEFAULT_TARGET.get();
        quantityKeeperDestroyRate = QUANTITY_KEEPER_DESTROY_RATE.get();
        unitPatternManagerEnergyUsage = UNIT_PATTERN_MANAGER_ENERGY_USAGE.get();

        collectionCacheScanInterval = COLLECTION_CACHE_SCAN_INTERVAL.get();
        collectionCacheScanRadius = COLLECTION_CACHE_SCAN_RADIUS.get();
        collectionCacheIdleEnergyUsage = COLLECTION_CACHE_IDLE_ENERGY_USAGE.get();
        collectionCacheWorkEnergyUsage = COLLECTION_CACHE_WORK_ENERGY_USAGE.get();
        collectionCacheEnergyPerSpeedUpgrade = COLLECTION_CACHE_ENERGY_PER_SPEED_UPGRADE.get();
        collectionCacheEnergyPerRadius = COLLECTION_CACHE_ENERGY_PER_RADIUS.get();
        collectionCacheSlotCapacity = COLLECTION_CACHE_SLOT_CAPACITY.get();
        collectionCacheSlotCapacityPerStackUpgrade = COLLECTION_CACHE_SLOT_CAPACITY_PER_STACK_UPGRADE.get();
        // 区域选定器：两个 int（用户拍板）—— 区域个数上限 + 单区域体积上限
        areaSelectorMaxAreas = AREA_SELECTOR_MAX_AREAS.get();
        areaSelectorMaxVolume = AREA_SELECTOR_MAX_VOLUME.get();

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
        sequenceStationDerivedCategories = SEQUENCE_STATION_DERIVED_CATEGORIES.get();
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

        // Jade 总线提示档位：只认 always / shift，写错一律回落到默认档 always（一直显示 —— 默认档必须最安全）
        final String jadeBusMode = JADE_BUS_TOOLTIP_MODE.get();
        jadeBusTooltipShiftOnly = "shift".equalsIgnoreCase(jadeBusMode);
        if (!jadeBusTooltipShiftOnly && !"always".equalsIgnoreCase(jadeBusMode)) {
            LOGGER.warn("未知的 jadeBusTooltipMode 取值「{}」，已回落到 always（可选 always / shift）", jadeBusMode);
        }

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}
