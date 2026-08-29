package cretae.cookiewyq.rs_create_compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD)
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

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
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

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream().map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName))).collect(Collectors.toSet());
    }
}
