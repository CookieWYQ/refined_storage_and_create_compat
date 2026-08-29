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
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER.comment("Whether to log the dirt block on common setup").define("logDirtBlock", true);

    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER.comment("A magic number").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER.comment("What you want the introduction message to be for the magic number").define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER.comment("A list of items to log on common setup.").defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // ========== 通用储存磁盘 ==========
    private static final ModConfigSpec.IntValue UNIVERSAL_DISK_BASE_CAPACITY = BUILDER
        .comment("通用储存磁盘的基础容量（以物品位为单位）。换算：1 个物品 = 1 物品位，1 桶（1000 mB）流体/气体 = 1 物品位。")
        .defineInRange("universalDiskBaseCapacity", 2560000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.BooleanValue UNIVERSAL_DISK_ALLOW_MIXED_TYPES = BUILDER
        .comment("是否允许通用储存磁盘同时混存不同类型（物品/流体/气体）。关闭后每个磁盘只能存放一种类型。")
        .define("universalDiskAllowMixedTypes", true);

    // ========== 范围充电器 ==========
    private static final ModConfigSpec.IntValue RANGE_CHARGER_CHARGE_RATE = BUILDER
        .comment("范围充电器单个目标的充电速率（FE/tick）。")
        .defineInRange("rangeChargerChargeRate", 20, 1, 1000000);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_ENERGY_CAPACITY = BUILDER
        .comment("范围充电器的能量缓存上限（FE）。")
        .defineInRange("rangeChargerEnergyCapacity", 1000000, 1000, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_MAX_TRANSFER = BUILDER
        .comment("范围充电器接收外部能量的最大速率（FE/tick）。")
        .defineInRange("rangeChargerMaxTransfer", 5000, 1, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue RANGE_CHARGER_MAX_TARGETS = BUILDER
        .comment("范围充电器每 tick 最多充电的对象数量（方块+物品合计）。")
        .defineInRange("rangeChargerMaxTargets", 10, 1, 1000);

    private static final ModConfigSpec.BooleanValue RANGE_CHARGER_CHARGE_BLOCKS = BUILDER
        .comment("是否给范围内可充电方块供电。")
        .define("rangeChargerChargeBlocks", true);

    private static final ModConfigSpec.BooleanValue RANGE_CHARGER_CHARGE_ITEMS = BUILDER
        .comment("是否给范围内掉落物中的可充电物品供电。")
        .define("rangeChargerChargeItems", true);

    // ========== 定量保持器 ==========
    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_ENERGY_USAGE = BUILDER
        .comment("定量保持器每 tick 的网络能量消耗（FE）。")
        .defineInRange("quantityKeeperEnergyUsage", 10, 1, 100000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DEFAULT_TARGET = BUILDER
        .comment("定量保持器的默认目标数量。")
        .defineInRange("quantityKeeperDefaultTarget", 64, 1, 1000000000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DESTROY_RATE = BUILDER
        .comment("定量保持器每 tick 最多销毁的过量数量（无速度升级时）。")
        .defineInRange("quantityKeeperDestroyRate", 16, 1, 1000000);

    private static final ModConfigSpec.IntValue QUANTITY_KEEPER_DESTROY_RATE_PER_SPEED = BUILDER
        .comment("每个速度升级额外增加的每 tick 销毁数量。")
        .defineInRange("quantityKeeperDestroyRatePerSpeed", 16, 0, 1000000);

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
    public static boolean rangeChargerChargeBlocks;
    public static boolean rangeChargerChargeItems;
    public static int quantityKeeperEnergyUsage;
    public static int quantityKeeperDefaultTarget;
    public static int quantityKeeperDestroyRate;
    public static int quantityKeeperDestroyRatePerSpeed;
    public static int advancedRemoteTerminalEnergyCapacity;
    public static boolean advancedRemoteTerminalEnableGrid;
    public static boolean advancedRemoteTerminalEnablePatterns;
    public static boolean advancedRemoteTerminalEnableManager;
    public static boolean advancedRemoteTerminalEnableMonitor;
    public static boolean advancedRemoteTerminalEnableSequence;

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    public static void onLoad(final ModConfigEvent event) {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        universalDiskBaseCapacity = UNIVERSAL_DISK_BASE_CAPACITY.get();
        universalDiskAllowMixedTypes = UNIVERSAL_DISK_ALLOW_MIXED_TYPES.get();

        rangeChargerChargeRate = RANGE_CHARGER_CHARGE_RATE.get();
        rangeChargerEnergyCapacity = RANGE_CHARGER_ENERGY_CAPACITY.get();
        rangeChargerMaxTransfer = RANGE_CHARGER_MAX_TRANSFER.get();
        rangeChargerMaxTargets = RANGE_CHARGER_MAX_TARGETS.get();
        rangeChargerChargeBlocks = RANGE_CHARGER_CHARGE_BLOCKS.get();
        rangeChargerChargeItems = RANGE_CHARGER_CHARGE_ITEMS.get();

        quantityKeeperEnergyUsage = QUANTITY_KEEPER_ENERGY_USAGE.get();
        quantityKeeperDefaultTarget = QUANTITY_KEEPER_DEFAULT_TARGET.get();
        quantityKeeperDestroyRate = QUANTITY_KEEPER_DESTROY_RATE.get();
        quantityKeeperDestroyRatePerSpeed = QUANTITY_KEEPER_DESTROY_RATE_PER_SPEED.get();

        advancedRemoteTerminalEnergyCapacity = ADVANCED_REMOTE_TERMINAL_ENERGY_CAPACITY.get();
        advancedRemoteTerminalEnableGrid = ADVANCED_REMOTE_TERMINAL_GRID.get();
        advancedRemoteTerminalEnablePatterns = ADVANCED_REMOTE_TERMINAL_PATTERNS.get();
        advancedRemoteTerminalEnableManager = ADVANCED_REMOTE_TERMINAL_MANAGER.get();
        advancedRemoteTerminalEnableMonitor = ADVANCED_REMOTE_TERMINAL_MONITOR.get();
        advancedRemoteTerminalEnableSequence = ADVANCED_REMOTE_TERMINAL_SEQUENCE.get();

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}
