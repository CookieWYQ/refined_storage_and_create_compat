package cretae.cookiewyq.rs_create_compat;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import io.netty.buffer.ByteBuf;
import com.refinedmods.refinedstorage.common.content.BlockEntities;
import cretae.cookiewyq.rs_create_compat.block.AdvancedSchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.CollectionCacheBlock;
import cretae.cookiewyq.rs_create_compat.block.QuantityKeeperBlock;
import cretae.cookiewyq.rs_create_compat.block.RangeChargerBlock;
import cretae.cookiewyq.rs_create_compat.block.SchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.item.UniversalStorageDiskItem;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccAutocrafterStorage;
import cretae.cookiewyq.rs_create_compat.support.SequenceMaterialGuard.RawMaterialMark;
import cretae.cookiewyq.rs_create_compat.network.SetClusterRowPacket;
import cretae.cookiewyq.rs_create_compat.network.RestoreCursorPacket;
import cretae.cookiewyq.rs_create_compat.network.SetQuantityTargetPacket;
import cretae.cookiewyq.rs_create_compat.network.SetRangePacket;
import cretae.cookiewyq.rs_create_compat.network.SwitchTerminalModePacket;
import cretae.cookiewyq.rs_create_compat.storage.UniversalStorageType;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.List;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(RS_Create_Compat.MODID)
public class RS_Create_Compat {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "rs_create_compat";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "rs_create_compat" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "rs_create_compat" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold BlockEntityTypes
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    // Create a Deferred Register to hold MenuTypes
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MODID);
    // Create a Deferred Register to hold CreativeModeTabs
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    // 数据组件（Tag 过滤器）
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    /** 附加在物品上的 Tag 过滤标记，值为如 "#minecraft:stone"。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> TAG_FILTER =
        DATA_COMPONENTS.registerComponentType("tag_filter",
            builder -> builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /**
     * 「原料」标记（方案 2：任务第一步 / 初始原料被输出时打在原料上，不修改 Create、不需要 Mixin）。
     * <p>值 = 配方 id + 输出该原料的步骤序；用途是在回流时区分「原料」与「未完成中间产物」。
     * 载体是注册版数据组件，随 {@code DataComponentPatch} 走，同标记的物品照常堆叠、RS 照常存取。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RawMaterialMark>>
        RAW_MATERIAL = DATA_COMPONENTS.registerComponentType("raw_material",
            builder -> builder.persistent(RawMaterialMark.CODEC)
                .networkSynchronized(RawMaterialMark.STREAM_CODEC));

    // ========== 物品 ==========
    // 通用储存磁盘：可同时存入物品、流体、气体任意类型。
    // 容量口径 = 物品位（1 bucket = 1 物品位），生存可得最大一档为 64M = 64 × 1024 × 1024 = 67108864（65536k），另有无限档（创造）。
    // 1k..64k 由原版对应 storage part 直造；256k..64M 一律由「4 个低一档的通用盘」无序合成（沿用 4 合 1 惯例）。
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_1K =
        ITEMS.register("universal_storage_disk_1k", () -> new UniversalStorageDiskItem(1024L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_4K =
        ITEMS.register("universal_storage_disk_4k", () -> new UniversalStorageDiskItem(4096L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_16K =
        ITEMS.register("universal_storage_disk_16k", () -> new UniversalStorageDiskItem(16384L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_64K =
        ITEMS.register("universal_storage_disk_64k", () -> new UniversalStorageDiskItem(65536L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_256K =
        ITEMS.register("universal_storage_disk_256k", () -> new UniversalStorageDiskItem(262144L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_1M =
        ITEMS.register("universal_storage_disk_1m", () -> new UniversalStorageDiskItem(1048576L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_4M =
        ITEMS.register("universal_storage_disk_4m", () -> new UniversalStorageDiskItem(4194304L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_16M =
        ITEMS.register("universal_storage_disk_16m", () -> new UniversalStorageDiskItem(16777216L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_64M =
        ITEMS.register("universal_storage_disk_64m", () -> new UniversalStorageDiskItem(67108864L));
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK_CREATIVE =
        ITEMS.register("universal_storage_disk_creative", () -> new UniversalStorageDiskItem(0L));

    // 序列装配单元样板 / 序列装配样板（无合成配方，由序列装配样板终端生成）
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.SequenceUnitPatternItem> SEQUENCE_UNIT_PATTERN =
        ITEMS.register("sequence_unit_pattern",
            () -> new cretae.cookiewyq.rs_create_compat.item.SequenceUnitPatternItem(
                new net.minecraft.world.item.Item.Properties().stacksTo(64)));
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem> SEQUENCE_ASSEMBLY_PATTERN =
        ITEMS.register("sequence_assembly_pattern",
            () -> new cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem(
                new net.minecraft.world.item.Item.Properties().stacksTo(1)));

    // 高级远程多功能终端（普通 / 满电 / 创造三版本）
    public static final DeferredItem<AdvancedRemoteTerminalItem> ADVANCED_REMOTE_TERMINAL =
        ITEMS.register("advanced_remote_terminal",
            () -> new AdvancedRemoteTerminalItem(AdvancedRemoteTerminalItem.Type.NORMAL));
    public static final DeferredItem<AdvancedRemoteTerminalItem> ADVANCED_REMOTE_TERMINAL_CHARGED =
        ITEMS.register("advanced_remote_terminal_charged",
            () -> new AdvancedRemoteTerminalItem(AdvancedRemoteTerminalItem.Type.CHARGED));
    public static final DeferredItem<AdvancedRemoteTerminalItem> CREATIVE_ADVANCED_REMOTE_TERMINAL =
        ITEMS.register("creative_advanced_remote_terminal",
            () -> new AdvancedRemoteTerminalItem(AdvancedRemoteTerminalItem.Type.CREATIVE));

    // 关于「终端的 Curios 饰品槽」：本模组**不再**注册自己的饰品槽（用户最终要求：「前置本身已经有了，
    // 我们没必要再搞一个」）。槽位本体、槽位背景精灵与语言键全部由前置模组
    // 「Refined Storage - Curios Integration」（modId refinedstorage_curios_integration）提供；
    // 本模组只保留一份「把三件终端登记进前置槽位的物品标签」：
    //   data/curios/tags/item/refinedstorage_curios_integration.json（replace:false 只做追加）
    // 已删除的本模组自有槽位资源（slots / entities / 自己的 item tag / 语言键
    // curios.identifier.rs_create_compat_curios_integration）见 tools/apply_curios_slot.py。
    // 为什么槽位背景必须用前置的：Curios 只把 assets/<ns>/textures/slot/** stitch 进方块图集，
    // 自绘的 item/ 贴图根本进不了图集，setBackground 只会拿到缺失精灵。

    // ========== 方块 ==========
    // 范围充电器
    public static final DeferredBlock<RangeChargerBlock> RANGE_CHARGER_BLOCK =
        BLOCKS.register("range_charger", () -> new RangeChargerBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> RANGE_CHARGER_ITEM =
        ITEMS.register("range_charger", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            RANGE_CHARGER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.range_charger.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RangeChargerBlockEntity>> RANGE_CHARGER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("range_charger",
            () -> BlockEntityType.Builder.of(RangeChargerBlockEntity::new, RANGE_CHARGER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<RangeChargerMenu>> RANGE_CHARGER_MENU =
        MENUS.register("range_charger",
            () -> new MenuType<>((id, inventory) -> new RangeChargerMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 定量保持器
    public static final DeferredBlock<QuantityKeeperBlock> QUANTITY_KEEPER_BLOCK =
        BLOCKS.register("quantity_keeper", () -> new QuantityKeeperBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> QUANTITY_KEEPER_ITEM =
        ITEMS.register("quantity_keeper", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            QUANTITY_KEEPER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.quantity_keeper.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuantityKeeperBlockEntity>> QUANTITY_KEEPER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("quantity_keeper",
            () -> BlockEntityType.Builder.of(QuantityKeeperBlockEntity::new, QUANTITY_KEEPER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<QuantityKeeperMenu>> QUANTITY_KEEPER_MENU =
        MENUS.register("quantity_keeper",
            () -> new MenuType<>((id, inventory) -> new QuantityKeeperMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 高级物品定量保持器（4 个独立配置槽：标记资源 / 目标数量 / 自动合成开关）
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.AdvancedQuantityKeeperBlock> ADVANCED_QUANTITY_KEEPER_BLOCK =
        BLOCKS.register("advanced_quantity_keeper", () -> new cretae.cookiewyq.rs_create_compat.block.AdvancedQuantityKeeperBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> ADVANCED_QUANTITY_KEEPER_ITEM =
        ITEMS.register("advanced_quantity_keeper", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            ADVANCED_QUANTITY_KEEPER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.advanced_quantity_keeper.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AdvancedQuantityKeeperBlockEntity>> ADVANCED_QUANTITY_KEEPER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("advanced_quantity_keeper",
            () -> BlockEntityType.Builder.of(AdvancedQuantityKeeperBlockEntity::new,
                ADVANCED_QUANTITY_KEEPER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<AdvancedQuantityKeeperMenu>> ADVANCED_QUANTITY_KEEPER_MENU =
        MENUS.register("advanced_quantity_keeper",
            () -> new MenuType<>((id, inventory) -> new AdvancedQuantityKeeperMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 蓝图加农炮装填器（基础版）
    public static final DeferredBlock<SchematicLoaderBlock> SCHEMATIC_LOADER_BLOCK =
        BLOCKS.register("schematic_loader", () -> new SchematicLoaderBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> SCHEMATIC_LOADER_ITEM =
        ITEMS.register("schematic_loader", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            SCHEMATIC_LOADER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.schematic_loader.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SchematicLoaderBlockEntity>> SCHEMATIC_LOADER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("schematic_loader",
            () -> BlockEntityType.Builder.of(SchematicLoaderBlockEntity::new, SCHEMATIC_LOADER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<SchematicLoaderMenu>> SCHEMATIC_LOADER_MENU =
        MENUS.register("schematic_loader",
            () -> new MenuType<>((id, inventory) -> new SchematicLoaderMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 高级蓝图加农炮装填器
    public static final DeferredBlock<AdvancedSchematicLoaderBlock> ADVANCED_SCHEMATIC_LOADER_BLOCK =
        BLOCKS.register("advanced_schematic_loader", () -> new AdvancedSchematicLoaderBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> ADVANCED_SCHEMATIC_LOADER_ITEM =
        ITEMS.register("advanced_schematic_loader", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            ADVANCED_SCHEMATIC_LOADER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.advanced_schematic_loader.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AdvancedSchematicLoaderBlockEntity>> ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("advanced_schematic_loader",
            () -> BlockEntityType.Builder.of(AdvancedSchematicLoaderBlockEntity::new,
                ADVANCED_SCHEMATIC_LOADER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<AdvancedSchematicLoaderMenu>> ADVANCED_SCHEMATIC_LOADER_MENU =
        MENUS.register("advanced_schematic_loader",
            () -> new MenuType<>((id, inventory) -> new AdvancedSchematicLoaderMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 序列装配样板终端
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.SequencePatternTerminalBlock> SEQUENCE_PATTERN_TERMINAL_BLOCK =
        BLOCKS.register("sequence_pattern_terminal", () -> new cretae.cookiewyq.rs_create_compat.block.SequencePatternTerminalBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> SEQUENCE_PATTERN_TERMINAL_ITEM =
        ITEMS.register("sequence_pattern_terminal", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            SEQUENCE_PATTERN_TERMINAL_BLOCK.get(),
            Component.translatable("block.rs_create_compat.sequence_pattern_terminal.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity>> SEQUENCE_PATTERN_TERMINAL_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("sequence_pattern_terminal",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity::new,
                SEQUENCE_PATTERN_TERMINAL_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu>> SEQUENCE_PATTERN_TERMINAL_MENU =
        MENUS.register("sequence_pattern_terminal",
            () -> new MenuType<>((id, inventory) -> new cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));
    /**
     * 「执行仓单元样板汇总」容器菜单（终端界面里点「单元样板库」标题打开）。
     * <p>独立菜单类型而非子 Screen：汇总要呈现跨多台执行仓的真槽位，必须走服务端权威容器 +
     * 原版槽位同步（点击取整叠 / Shift 快速移动 / 拖拽放置全部由原版负责）。</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu>> CHAMBER_UNITS_SUMMARY_MENU =
        MENUS.register("chamber_units_summary",
            () -> new MenuType<>((id, inventory) -> new cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 序列装配执行器
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.SequenceAssemblyExecutorBlock> SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK =
        BLOCKS.register("sequence_assembly_executor",
            () -> new cretae.cookiewyq.rs_create_compat.block.SequenceAssemblyExecutorBlock(
                BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> SEQUENCE_ASSEMBLY_EXECUTOR_ITEM =
        ITEMS.register("sequence_assembly_executor",
            () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
                SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK.get(),
                Component.translatable("block.rs_create_compat.sequence_assembly_executor.help")));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity>> SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("sequence_assembly_executor",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity::new,
                SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.SequenceAssemblyExecutorMenu>> SEQUENCE_ASSEMBLY_EXECUTOR_MENU =
        MENUS.register("sequence_assembly_executor",
            () -> new MenuType<>((id, inventory) -> new cretae.cookiewyq.rs_create_compat.menu.SequenceAssemblyExecutorMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 序列执行仓（分布式序列装配执行：无 GUI，认领本机步骤的过渡件喂给相邻 Create 机）
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock> SEQUENCE_EXECUTION_CHAMBER_BLOCK =
        BLOCKS.register("sequence_execution_chamber",
            () -> new cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock(
                BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> SEQUENCE_EXECUTION_CHAMBER_ITEM =
        ITEMS.register("sequence_execution_chamber",
            () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
                SEQUENCE_EXECUTION_CHAMBER_BLOCK.get(),
                Component.translatable("block.rs_create_compat.sequence_execution_chamber.help")));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity>> SEQUENCE_EXECUTION_CHAMBER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("sequence_execution_chamber",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity::new,
                SEQUENCE_EXECUTION_CHAMBER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu>> SEQUENCE_EXECUTION_CHAMBER_MENU =
        MENUS.register("sequence_execution_chamber",
            () -> new MenuType<>((id, inventory) -> new cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 单元样板管理舱（管理网络内各执行舱的单元样板；界面照抄 RS 自动合成管理器，按「执行舱」分组）
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.UnitPatternManagerBlock> UNIT_PATTERN_MANAGER_BLOCK =
        BLOCKS.register("unit_pattern_manager", () -> new cretae.cookiewyq.rs_create_compat.block.UnitPatternManagerBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> UNIT_PATTERN_MANAGER_ITEM =
        ITEMS.register("unit_pattern_manager", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            UNIT_PATTERN_MANAGER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.unit_pattern_manager.help")));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity>> UNIT_PATTERN_MANAGER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("unit_pattern_manager",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity::new,
                UNIT_PATTERN_MANAGER_BLOCK.get()).build(null));
    /**
     * 「单元样板管理舱」容器菜单。
     * <p>槽位数取决于网络里执行舱的台数，客户端无法自行推导，因此这里用 NeoForge 的
     * {@code IMenuTypeExtension}（带额外数据的菜单类型），由服务端在 openMenu 时把
     * 「分组标题 + 各组槽位数」写进额外数据，客户端据此重建同下标顺序的镜像槽位。</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.UnitPatternManagerMenu>> UNIT_PATTERN_MANAGER_MENU =
        MENUS.register("unit_pattern_manager",
            () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                (id, inventory, data) -> cretae.cookiewyq.rs_create_compat.menu.UnitPatternManagerMenu.read(
                    id, inventory, data)));

    // 归流缓存仓（吸取世界中的掉落物 → 按 ghost 标记凑数收集 → 流入 RS 网络）
    public static final DeferredBlock<CollectionCacheBlock> COLLECTION_CACHE_BLOCK =
        BLOCKS.register("collection_cache",
            () -> new CollectionCacheBlock(
                BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> COLLECTION_CACHE_ITEM =
        ITEMS.register("collection_cache",
            () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
                COLLECTION_CACHE_BLOCK.get(),
                Component.translatable("block.rs_create_compat.collection_cache.help")));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CollectionCacheBlockEntity>> COLLECTION_CACHE_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("collection_cache",
            () -> BlockEntityType.Builder.of(CollectionCacheBlockEntity::new,
                COLLECTION_CACHE_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<CollectionCacheMenu>> COLLECTION_CACHE_MENU =
        MENUS.register("collection_cache",
            () -> new MenuType<>((id, inventory) -> new CollectionCacheMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 中间产物缓存仓（网络内所有序列装配执行舱共享的中间产物临时储存点）
    // 用户决定：「那个缓存的磁盘应该是通用的，他只做一个所有这种的临时储存点而已」——
    // 磁盘从执行舱搬到本仓，网络内所有执行舱共用本仓的盘（见 support/RsccSharedCache）。
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.IntermediateCacheBlock>
        INTERMEDIATE_CACHE_BLOCK =
        BLOCKS.register("intermediate_cache",
            () -> new cretae.cookiewyq.rs_create_compat.block.IntermediateCacheBlock(
                BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> INTERMEDIATE_CACHE_ITEM =
        ITEMS.register("intermediate_cache",
            () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
                INTERMEDIATE_CACHE_BLOCK.get(),
                Component.translatable("block.rs_create_compat.intermediate_cache.help")));
    public static final DeferredHolder<BlockEntityType<?>,
        BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity>>
        INTERMEDIATE_CACHE_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("intermediate_cache",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity::new,
                INTERMEDIATE_CACHE_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.IntermediateCacheMenu>>
        INTERMEDIATE_CACHE_MENU =
        MENUS.register("intermediate_cache",
            () -> new MenuType<>((id, inventory) ->
                new cretae.cookiewyq.rs_create_compat.menu.IntermediateCacheMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // ========== 分隔框架（批 4a 起，批 4b 改为「套壳」语义） ==========
    // 用户要求「不要做一个单独的方块存在，而是真正的套在线缆 / 管道那一格上」：
    // 套壳状态落在 support/RsccSheaths（服务端存档 + S2C 镜像），那一格仍然是原来那根管道，
    // 因此它的能力 / 网络节点 / 流体内容一个字节都不会变（无需委托，也不可能丢内容）。
    // 物品不再放置下面的方块：右键线缆 / 管道就是「套壳」，潜行右键是「取下」（见 item/SeparationFrameItem）。
    // 下面的方块保留注册（旧存档 / 指令放置出的仍是同一个 ID，注册表与存档完全兼容），
    // 它自身已不再影响任何连接，只提供「按帧显隐」的独立框架外观与整格选中框。
    // 分隔框架：普通版（套上消耗 1 个、取下归还 1 个）。
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.SeparationFrameBlock>
        SEPARATION_FRAME_BLOCK =
        BLOCKS.register("separation_frame", () -> new cretae.cookiewyq.rs_create_compat.block.SeparationFrameBlock(
            false,
            BlockBehaviour.Properties.of().strength(0.5F).sound(net.minecraft.world.level.block.SoundType.METAL)
                .noCollission().noOcclusion()));
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem>
        SEPARATION_FRAME_ITEM =
        ITEMS.register("separation_frame", () -> new cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem(
            SEPARATION_FRAME_BLOCK.get(),
            Component.translatable("block.rs_create_compat.separation_frame.help"),
            false));
    // 无限分隔框架：套上不消耗、取下不归还、外面那层壳叠加附魔光泽（client/SheathRenderer 的 glint 层）。
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.SeparationFrameBlock>
        INFINITE_SEPARATION_FRAME_BLOCK =
        BLOCKS.register("infinite_separation_frame",
            () -> new cretae.cookiewyq.rs_create_compat.block.SeparationFrameBlock(
                true,
                BlockBehaviour.Properties.of().strength(0.5F).sound(net.minecraft.world.level.block.SoundType.METAL)
                    .noCollission().noOcclusion()));
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem>
        INFINITE_SEPARATION_FRAME_ITEM =
        ITEMS.register("infinite_separation_frame",
            () -> new cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem(
                INFINITE_SEPARATION_FRAME_BLOCK.get(),
                Component.translatable("block.rs_create_compat.infinite_separation_frame.help"),
                true));
    /**
     * 两种框架<b>共用一个</b>方块实体类型：方块实体不存任何数据、不 tick，
     * 只为「按帧判断显隐」的客户端渲染器提供宿主（区块网格做不到按帧变化，见 SeparationFrameBlock）。
     */
    public static final DeferredHolder<BlockEntityType<?>,
        BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.SeparationFrameBlockEntity>>
        SEPARATION_FRAME_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("separation_frame",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.SeparationFrameBlockEntity::new,
                SEPARATION_FRAME_BLOCK.get(), INFINITE_SEPARATION_FRAME_BLOCK.get()).build(null));

    // 伪装框架（套壳装饰：手持完整方块右键应用为外壳；只允许装在管道 / 线缆上，见 item/CamouflageFrameItem）
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.CamouflageFrameBlock> CAMOUFLAGE_FRAME_BLOCK =
        BLOCKS.register("camouflage_frame", () -> new cretae.cookiewyq.rs_create_compat.block.CamouflageFrameBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> CAMOUFLAGE_FRAME_ITEM =
        ITEMS.register("camouflage_frame", () -> new cretae.cookiewyq.rs_create_compat.item.CamouflageFrameItem(
            CAMOUFLAGE_FRAME_BLOCK.get(),
            Component.translatable("block.rs_create_compat.camouflage_frame.help")
        ));
    /**
     * 伪装框架的方块实体：直接复用 Create 的 {@code CopycatBlockEntity}（存外壳材质 + 存被消耗的物品 +
     * 按材质刷新模型与光源），这里只是给它注册一个「属于本模组方块」的方块实体类型。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.simibubi.create.content.decoration.copycat.CopycatBlockEntity>> CAMOUFLAGE_FRAME_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("camouflage_frame",
            () -> BlockEntityType.Builder.<com.simibubi.create.content.decoration.copycat.CopycatBlockEntity>of(
                RS_Create_Compat::newCamouflageFrameBlockEntity,
                CAMOUFLAGE_FRAME_BLOCK.get()).build(null));

    /**
     * 伪装框架方块实体的构造器。
     * <p>为什么抽成一个方法：方块实体要拿到「自己的方块实体类型」，而如果把这段写法直接放进
     * {@link #CAMOUFLAGE_FRAME_BLOCK_ENTITY} 字段自己的 lambda 里，javac 会把「在字段自身的初始化表达式里
     * 引用该字段」判为初始化程序的错误，因此用方法引用把这段引用挪出初始化表达式。</p>
     */
    private static com.simibubi.create.content.decoration.copycat.CopycatBlockEntity newCamouflageFrameBlockEntity(
        final net.minecraft.core.BlockPos pos, final net.minecraft.world.level.block.state.BlockState state) {
        return new com.simibubi.create.content.decoration.copycat.CopycatBlockEntity(
            CAMOUFLAGE_FRAME_BLOCK_ENTITY.get(), pos, state);
    }

    // 创造模式标签页
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
        CREATIVE_MODE_TABS.register("rs_create_compat", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.rs_create_compat"))
            .icon(() -> new ItemStack(UNIVERSAL_STORAGE_DISK_1K.get()))
            .displayItems((parameters, output) -> {
                output.accept(UNIVERSAL_STORAGE_DISK_1K.get());
                output.accept(UNIVERSAL_STORAGE_DISK_4K.get());
                output.accept(UNIVERSAL_STORAGE_DISK_16K.get());
                output.accept(UNIVERSAL_STORAGE_DISK_64K.get());
                output.accept(UNIVERSAL_STORAGE_DISK_256K.get());
                output.accept(UNIVERSAL_STORAGE_DISK_1M.get());
                output.accept(UNIVERSAL_STORAGE_DISK_4M.get());
                output.accept(UNIVERSAL_STORAGE_DISK_16M.get());
                output.accept(UNIVERSAL_STORAGE_DISK_64M.get());
                output.accept(UNIVERSAL_STORAGE_DISK_CREATIVE.get());
                output.accept(ADVANCED_REMOTE_TERMINAL.get()); // 普通版：无电
                // 满电版：复用 RS 原版 createAtEnergyCapacity() 生成满电实例（与普通版不混淆）
                output.accept(ADVANCED_REMOTE_TERMINAL_CHARGED.get().createAtEnergyCapacity());
                output.accept(CREATIVE_ADVANCED_REMOTE_TERMINAL.get()); // 创造版：无视电量
                output.accept(RANGE_CHARGER_ITEM.get());
                output.accept(QUANTITY_KEEPER_ITEM.get());
                output.accept(ADVANCED_QUANTITY_KEEPER_ITEM.get());
                output.accept(SCHEMATIC_LOADER_ITEM.get());
                output.accept(ADVANCED_SCHEMATIC_LOADER_ITEM.get());
                output.accept(SEQUENCE_PATTERN_TERMINAL_ITEM.get());
                output.accept(SEQUENCE_UNIT_PATTERN.get());
                output.accept(SEQUENCE_ASSEMBLY_PATTERN.get());
                output.accept(SEQUENCE_ASSEMBLY_EXECUTOR_ITEM.get()); // 序列装配执行器
                output.accept(SEQUENCE_EXECUTION_CHAMBER_ITEM.get()); // 序列执行仓
                output.accept(COLLECTION_CACHE_ITEM.get()); // 归流缓存仓
                output.accept(INTERMEDIATE_CACHE_ITEM.get()); // 中间产物缓存仓（执行舱共享）
                output.accept(UNIT_PATTERN_MANAGER_ITEM.get()); // 单元样板管理舱
                output.accept(SEPARATION_FRAME_ITEM.get()); // 分隔框架（可消耗 / 可回收）
                output.accept(INFINITE_SEPARATION_FRAME_ITEM.get()); // 无限分隔框架（不消耗 / 不回收 / 带光泽）
                output.accept(CAMOUFLAGE_FRAME_ITEM.get()); // 伪装框架
            })
            .build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public RS_Create_Compat(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Registers to the mod event bus
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        // 伪装外壳的载体：挂在「被裹方块自己的方块实体」上的数据附件
        // （随方块实体 NBT 一起序列化 → 存档 / 装置搬运都带着走；由 NeoForge 自动同步给观察者）
        cretae.cookiewyq.rs_create_compat.support.RsccCamouflageAttachment.register(modEventBus);

        // 注册方块能力（能量输出 / 物品处理器 / 网络节点容器）
        modEventBus.addListener(RangeChargerBlockEntity::registerCapabilities);
        modEventBus.addListener(QuantityKeeperBlockEntity::registerCapabilities);
        modEventBus.addListener(AdvancedQuantityKeeperBlockEntity::registerCapabilities);
        modEventBus.addListener(SchematicLoaderBlockEntity::registerCapabilities);
        modEventBus.addListener(AdvancedSchematicLoaderBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity::registerCapabilities);
        modEventBus.addListener(CollectionCacheBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity::registerCapabilities);
        // 调整一：自动合成仓内部输出存储（物品 + 流体能力）
        modEventBus.addListener(RS_Create_Compat::registerAutocrafterCapabilities);
        // 终端物品能量能力（普通/满电/创造）
        modEventBus.addListener(RS_Create_Compat::registerTerminalEnergyCapabilities);

        // 网络包注册
        modEventBus.addListener(RS_Create_Compat::registerPayloads);

        // 配置加载
        modEventBus.addListener(Config::onLoad);

        // 成就的自定义 criterion 触发器（原版 registry minecraft:trigger_type）：
        // 玩法型成就（装填器自动打印 / 归流缓存仓吸物 / 定量保持器自动补货）靠它在正确时机 fire
        cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        NeoForge.EVENT_BUS.register(this);

        // 输出总线连接缓存的「事件驱动失效」：邻块放置 / 破坏 / 被替换时作废缓存 + 取消退避
        // （把「每 20 tick 无条件重跑 BFS」的定时轮询换成事件驱动，见 support/RsccBusLinkInvalidation）
        cretae.cookiewyq.rs_create_compat.support.RsccBusLinkInvalidation.register();

        // 共享缓存池解析缓存的「事件驱动失效」：中间产物缓存仓被放置 / 破坏 / 被替换时作废，
        // 下一次访问立刻重算（见 support/RsccSharedCache 与 support/RsccCacheInvalidation）
        cretae.cookiewyq.rs_create_compat.support.RsccCacheInvalidation.register();

        // 机器集群（同类型机器相邻 = 一个整体：容量叠加 + 内容共享）的「事件驱动失效」：
        // 邻块放置 / 破坏 / 被替换时作废相关集群缓存，下一次方块实体 tick 立即重算
        // （把「每 tick BFS」换成「缓存 + 事件驱动」，见 support/RsccMachineCluster）
        cretae.cookiewyq.rs_create_compat.support.RsccClusterInvalidation.register();

        // 右键交互的三个挂点（都是 PlayerInteractEvent.RightClickBlock，同为 HIGH 优先级）。
        // 同一优先级内按<b>注册顺序</b>调用，因此顺序本身就是语义：
        //   ① 伪装外壳（最外面那一层）：选外壳 / 潜行取下；也不允许别人抢先处理已裹住的格子；
        //   ② 分隔框架：潜行右键取下并回收框架本体（必须抢在 RS 自己的扳手处理「潜行 = 拆掉整根线缆」之前）；
        //   ③ 扳手断缝：最后结算，只处理「不是伪装/分隔框架那一层」的右键，断开 / 恢复这一道缝。
        // 三者各自只处理自己那一层，靠 event.setCanceled + 上面这条注册顺序串成一条确定的链。
        cretae.cookiewyq.rs_create_compat.support.RsccCamouflageInteraction.register();
        cretae.cookiewyq.rs_create_compat.support.RsccSheathInteraction.register();
        cretae.cookiewyq.rs_create_compat.support.RsccWrenchCableInteraction.register();

        // 两种框架的「连锁套壳」：挂 FTB Ultimine 的右键连锁 API（可选依赖，全程反射；
        // 没装 FTB Ultimine 时 register() 内部第一句就返回，行为 = 默认的单格套壳）。
        // 普通右键（不按连锁键）永远只作用于命中那一格，不经过这条链路。
        cretae.cookiewyq.rs_create_compat.support.RsccUltimineIntegration.register();

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 注册通用存储类型（必须在任何磁盘数据序列化之前注册，供 RS 存档编解码使用）
        RefinedStorageApi.INSTANCE.getStorageTypeRegistry().register(
            ResourceLocation.fromNamespaceAndPath(MODID, "universal"),
            UniversalStorageType.INSTANCE
        );
        // 把本模组机器注册为 RS 升级物品的目的地：升级 tooltip 由 RS 原生渲染（方块图标 + 名称）
        RegisterUpgradeDestinations.registerAll();
        // 终端快捷键要能认出「戴在（前置）Curios 饰品槽里的终端」：向 RS 注册饰品槽引用来源
        cretae.cookiewyq.rs_create_compat.support.RsccCuriosTerminalSlot.register();
        // 「背包任意格」的引用类型：RS 只提供主手 / 副手的引用，客户端要定位「不手持的终端」需自带一种
        cretae.cookiewyq.rs_create_compat.support.RsccTerminalReferences.register();

        LOGGER.info("RS & Create Compat common setup complete");
    }

    /** 调整一：为 RS 自动合成仓注册内部输出存储能力（物品 + 流体，可开关）。 */
    private static void registerAutocrafterCapabilities(final RegisterCapabilitiesEvent event) {
        if (!Config.autocrafterStorageEnabled) {
            return;
        }
        final var autocrafterType = BlockEntities.INSTANCE.getAutocrafter();
        // 用户要求（B18）：自动合成仓的内部存储只允许被物流「抽取」，不允许往里塞（避免与合成流程互相打架）。
        // 因此这里包一层只出能力（fill/insertItem 一律拒绝），内部逻辑仍直接操作真实存储，不受影响。
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            autocrafterType,
            (blockEntity, direction) -> blockEntity instanceof RsccAutocrafterStorage accessor
                ? new cretae.cookiewyq.rs_create_compat.support.ExtractOnlyHandlers.Item(
                    accessor.rscc$getOutputStorage())
                : null
        );
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            autocrafterType,
            (blockEntity, direction) -> blockEntity instanceof RsccAutocrafterStorage accessor
                ? new cretae.cookiewyq.rs_create_compat.support.ExtractOnlyHandlers.Fluid(
                    accessor.rscc$getOutputTank())
                : null
        );
    }

    /** 为高级远程多功能终端（普通/满电/创造）注册物品能量能力，仿 RS 无线终端。 */
    private static void registerTerminalEnergyCapabilities(final RegisterCapabilitiesEvent event) {
        for (final AdvancedRemoteTerminalItem item : List.of(
            ADVANCED_REMOTE_TERMINAL.get(),
            ADVANCED_REMOTE_TERMINAL_CHARGED.get(),
            CREATIVE_ADVANCED_REMOTE_TERMINAL.get())) {
            event.registerItem(
                Capabilities.EnergyStorage.ITEM,
                (stack, context) -> new com.refinedmods.refinedstorage.neoforge.support.energy.EnergyStorageAdapter(
                    item.createEnergyStorage(stack)),
                item
            );
        }
    }

    /** 网络包：终端模式切换（C2S）+ 定量保持器目标数量输入（C2S）。 */
    private static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MODID);
        registrar.playToServer(
            SwitchTerminalModePacket.TYPE,
            SwitchTerminalModePacket.STREAM_CODEC,
            (packet, ctx) -> SwitchTerminalModePacket.handle(packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 终端快捷键（C2S，空负载）：服务端按固定优先级（主手 → 副手 → 背包 0..35 → Curios）自己找第一台
        // 高级远程多功能终端并打开；不再走 RS 的引用唯一性判定（身上有多台就谁都打不开）。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.OpenAdvancedRemoteTerminalPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.OpenAdvancedRemoteTerminalPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.OpenAdvancedRemoteTerminalPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToClient(
            RestoreCursorPacket.TYPE,
            RestoreCursorPacket.STREAM_CODEC,
            RestoreCursorPacket::handle
        );
        // 单元样板管理舱：「存回网络」按钮已按用户要求删除（对应的 C2S 包与菜单按钮一并移除）
        registrar.playToServer(
            SetQuantityTargetPacket.TYPE,
            SetQuantityTargetPacket.STREAM_CODEC,
            (packet, ctx) -> SetQuantityTargetPacket.handle(packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetQuantityMarkerPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetQuantityMarkerPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetQuantityMarkerPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 定量保持器：直接标记流体/气体（C2S，服务端校验 containerId 后写入方块实体）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetQuantityFluidMarkerPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetQuantityFluidMarkerPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetQuantityFluidMarkerPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 定量保持器：S2C 回传直接标记的流体/气体（ContainerData 传不了资源 id）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncQuantityFluidMarkerPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncQuantityFluidMarkerPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncQuantityFluidMarkerPacket::handle
        );
        // 高级物品定量保持器：物品标记 / 流体·气体标记（均带 slotIndex）+ 目标数量 + 自动合成开关（C2S）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperMarkerPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperMarkerPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperMarkerPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperFluidMarkerPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperFluidMarkerPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperFluidMarkerPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperTargetPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperTargetPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperTargetPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperAutocraftPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperAutocraftPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperAutocraftPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 高级物品定量保持器：S2C 4 槽配置快照（id/NBT 无法走 ContainerData）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncAdvKeeperConfigPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncAdvKeeperConfigPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncAdvKeeperConfigPacket::handle
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperOverflowPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperOverflowPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperOverflowPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 序列装配样板终端：单元样板库的分区元数据（标题 + 存入目标 + 张数）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncLibrarySectionsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncLibrarySectionsPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncLibrarySectionsPacket::handle
        );
        registrar.playToServer(
            SetRangePacket.TYPE,
            SetRangePacket.STREAM_CODEC,
            (packet, ctx) -> SetRangePacket.handle(packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            SetClusterRowPacket.TYPE,
            SetClusterRowPacket.STREAM_CODEC,
            (packet, ctx) -> SetClusterRowPacket.handle(packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetArrangementScrollPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetArrangementScrollPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetArrangementScrollPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetSequenceCountPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetSequenceCountPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetSequenceCountPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetSequenceGhostPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetSequenceGhostPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetSequenceGhostPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetSequenceImportPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetSequenceImportPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetSequenceImportPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetResultScrollPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetResultScrollPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetResultScrollPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetSequenceChancePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetSequenceChancePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetSequenceChancePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 产物 / 废料子窗口：概率 + 产出数量一次性写入（取代滚轮改概率）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetSequenceResultPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetSequenceResultPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetSequenceResultPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 产物 / 废料格：Shift+左键清空该格（只清标记引用，不销毁任何真实物品）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.ClearSequenceResultPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.ClearSequenceResultPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.ClearSequenceResultPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetExecutorTargetPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetExecutorTargetPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetExecutorTargetPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncAutocrafterNamesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncAutocrafterNamesPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncAutocrafterNamesPacket::handle
        );
        // 归流缓存仓：匹配设置（C2S）、滚动偏移（C2S）、匹配区同步（S2C）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerConfigPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerConfigPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerConfigPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：匹配区「标签过滤器」已合并进 SetCollectionMarkerConfigPacket 的 tags 字段，不再单独发包。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionScrollPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionScrollPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionScrollPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：「阻塞」名单切换（C2S，逐个资源单独配置；阻塞后不再写回网络）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionBlockedPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionBlockedPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionBlockedPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 序列执行仓：面配置（C2S，逐面设置原料输入 / 产物输出 / 中间产物输出）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetChamberFacePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetChamberFacePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetChamberFacePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 序列执行仓：输出模式切换（C2S，面输出 / 总线输出「延长型输出」）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetChamberOutputModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetChamberOutputModePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetChamberOutputModePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 终端：执行仓单元样板汇总（C2S 请求 + S2C 回传 + C2S 取回/存入）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequestChamberUnitsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequestChamberUnitsPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.RequestChamberUnitsPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncChamberUnitsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncChamberUnitsPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncChamberUnitsPacket::handle
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.TransferChamberUnitPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.TransferChamberUnitPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.TransferChamberUnitPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 终端：打开 / 关闭「执行仓单元样板汇总」真槽位容器菜单 + 分页切换（均 C2S，服务端权威）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.OpenChamberUnitsSummaryPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.OpenChamberUnitsSummaryPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.OpenChamberUnitsSummaryPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.CloseChamberUnitsSummaryPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.CloseChamberUnitsSummaryPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.CloseChamberUnitsSummaryPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetChamberUnitsPagePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetChamberUnitsPagePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetChamberUnitsPagePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncCollectionMarkersPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncCollectionMarkersPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncCollectionMarkersPacket::handle
        );
        // 归流缓存仓：4 个吸取开关切换（C2S，服务端校验容器后写入方块实体）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionAbsorbTogglePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionAbsorbTogglePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionAbsorbTogglePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：收集范围设置（C2S，服务端权威写入并回传数据槽）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionRadiusPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionRadiusPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionRadiusPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：「多个输入面」逐面开关（C2S，服务端权威写入并经数据槽回传）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionInputFacePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionInputFacePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionInputFacePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：经验收集形态切换（自动 / 颗粒 / 液态；C2S，服务端权威写入 NBT 并经数据槽回传）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionXpFormPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionXpFormPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionXpFormPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：「吸取所有物品」开关（C2S，服务端权威写入 NBT 并经数据槽回传；
        // 开启后匹配区整体失效，与「反转匹配」同时开启时本开关优先）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionCollectAllPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionCollectAllPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionCollectAllPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：「反转匹配」开关（C2S，服务端权威写入 NBT 并经数据槽回传；匹配区白名单 ↔ 黑名单）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionInvertMatchPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionInvertMatchPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionInvertMatchPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：销毁缓存区内容（C2S，带确认位；未确认服务端零销毁）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionDestroyPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionDestroyPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionDestroyPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：匹配槽「直接销毁」开关（C2S，带确认位；开启未确认服务端拒绝）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerDestroyPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerDestroyPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerDestroyPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 归流缓存仓：**删除模式总闸**（C2S，带确认位；2026-10-05 用户要求）
        // 语义：只有这个总闸开启时，「标记为删除」的命中资源才会被销毁；
        // 销毁速率 = 处理组数 × 单次吞吐（与回流同源，受速度 / 堆叠升级影响）。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 完成横幅（执行器/装填器/加农炮完成汇报）：S2C 广播 → 客户端 Toast
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload.TYPE,
            cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload::handle
        );
        // 扳手分离 RS 线缆：断开记录整份快照（S2C；客户端据此重算连接臂，见 support/RsccCableCuts）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncCableDisconnectsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncCableDisconnectsPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncCableDisconnectsPacket::handle
        );
        // 分隔框架套壳：被套住的坐标整份快照（S2C；客户端据此重算连接 + 在管道外面画外套，
        // 见 support/RsccSheaths。同样必须先于方块更新下发）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncSheathPositionsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncSheathPositionsPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncSheathPositionsPacket::handle
        );
        // 伪装框架：被裹方块的方块实体上多了一个附件（附件变了 → 客户端重烘那一段网格的极轻信号；
        // 材质本身走 NeoForge 的附件同步，见 support/RsccCamouflageAttachment 与
        // network/RefreshCamouflagePacket）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket::handle
        );
        // 伪装框架：戴护目镜按 K → 翻转「隐藏已填充方块」的<b>全局</b>开关（C2S 空负载；服务端权威地
        // 翻一个布尔位并立刻单播回该玩家，见 support/RsccCamouflage#toggleMaterialHidden）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.ToggleCamouflageRevealPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.ToggleCamouflageRevealPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.ToggleCamouflageRevealPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 伪装框架：把该玩家自己的全局显示开关下发给他（S2C 单包；登录 / 换维度 / 重生 / 每次切换后）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncCamouflageRevealPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncCamouflageRevealPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncCamouflageRevealPacket::handle
        );
        // 归流缓存仓：点击流体格用空容器换出 1 桶（C2S；服务端先模拟后执行，无容器则静默不动作）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.ExtractCollectionFluidPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.ExtractCollectionFluidPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.ExtractCollectionFluidPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 自动合成仓内部存储开关：已由「界面按钮」改为无权限指令
        // /rs_create_compat autocrafter storage <on|off>（见 command/CompatCommands.java），
        // 因此这里不再需要 C2S / S2C 两个网络包。
        // ===== 输出总线「延长型输出」模式（序列执行仓） =====
        // C2S：切换导出类别（输入原料 / 中间产物）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetExporterExecutorCategoriesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetExporterExecutorCategoriesPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetExporterExecutorCategoriesPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // C2S：切换「强制为普通总线」开关（用户原话：「赶紧做我要的按钮」）——
        // 关掉「被自动判定成序列装配总线」，界面回到普通输出总线（服务端权威 + 回传权威状态）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetExporterForceNormalPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetExporterForceNormalPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetExporterForceNormalPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // S2C：把「是否延长模式 + 当前导出类别」同步给打开该输出总线界面的客户端
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncExporterExecutorModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncExporterExecutorModePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncExporterExecutorModePacket::handle
        );
        // ===== 输入总线「延长型输入」模式（序列执行仓回流；上面那组的对称版） =====
        // C2S：切换「要收回的类别」（空表 = 什么都不收回，这也是默认值）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetImporterExecutorCategoriesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetImporterExecutorCategoriesPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetImporterExecutorCategoriesPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // C2S：切换「强制为普通总线」开关（输入总线侧；与输出总线侧同一条规则）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetImporterForceNormalPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetImporterForceNormalPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetImporterForceNormalPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // S2C：把「是否延长模式 + 当前收回类别」同步给打开该输入总线界面的客户端
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncImporterExecutorModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncImporterExecutorModePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncImporterExecutorModePacket::handle
        );
        // S2C（批 3 任务 B）：把「这条总线是否因归属未确定而停用 + 可达执行舱 + 延长段线缆簇」同步给客户端；
        // 只在状态变化时下发（复用方块实体侧那份 20 tick 缓存判定），供界面警示条与半透明叠加层使用。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncBusInterferencePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncBusInterferencePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncBusInterferencePacket::handle
        );
        // C2S：切换「全自动 / 手动」收回开关（默认全自动；只动开关，不改写手动勾选表）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetImporterAutoCollectPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetImporterAutoCollectPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetImporterAutoCollectPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // ===== 新语义（v4）：执行仓绑定 / 机器列表 / 步骤机器指派 / 单元样板创建 =====
        // C2S：设置某台执行仓的配方类型 + 显示名（服务端校验 BE 与距离）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetChamberBindingPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetChamberBindingPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetChamberBindingPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：回传某台执行仓当前绑定的「坐标 + 配方类型 + 名字」（客户端镜像缓存，供界面回填/展示）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket::handle
        );
        // C2S：请求当前终端网络内的执行仓列表（可选按配方类型过滤）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequestChamberListPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequestChamberListPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.RequestChamberListPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：回传机器列表给客户端（客户端侧缓存，供屏幕重做后读取）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket::handle
        );
        // C2S：把某一装配步骤指派到某台机器（或解绑）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetStepMachinePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetStepMachinePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetStepMachinePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：给流程编排的某一步标记 / 清除「输入流体」（注液器一类步骤）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetStepFluidPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetStepFluidPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetStepFluidPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：切换流程编排某一步「生成时是否跳过已存在的相同单元样板」（行内开关；服务端权威）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetStepSkipDuplicatePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetStepSkipDuplicatePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetStepSkipDuplicatePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：按新语义创建/覆盖一张单元样板（recipeType + name + requiresInput + 可选输入）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.CreateUnitPatternPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.CreateUnitPatternPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.CreateUnitPatternPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：每一步「已指派机器名 + recipeType + 是否已指派」的全量快照
        // （容器打开时 / 设置机器后 / 增删步后发送；客户端只缓存用于渲染「◀ 机器名 ▶」）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket::handle
        );
        // C2S：请求「配方类型候选列表」（全部已注册类型，已绑定的排前面）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequestRecipeTypesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequestRecipeTypesPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.RequestRecipeTypesPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：回传配方类型候选列表（客户端镜像缓存，供滚轮选择控件使用）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncRecipeTypesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncRecipeTypesPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncRecipeTypesPacket::handle
        );
        // S2C：序列装配「停滞 / 掉线」告警快照（自动合成管理器上的按钮 + 横幅都用它）。
        // 必须在这里注册：漏注册时发包会抛
        // UnsupportedOperationException: Payload rs_create_compat:sync_assembly_alerts may not be sent to the client!
        // 而该包在玩家进世界时就会发 → 直接把连接打断（「无法进入单人存档 / 与服务器断开连接」）。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket::handle
        );
        // C2S：自动合成监视器上的「继续」处置（取消不再由本模组提供 —— 用监视器原生的取消）。
        //
        // 必须走 ctx.enqueueWork：C2S 的处理器默认跑在 **网络线程** 上，而 handle() 会读改
        // AssemblyWatchdog 的记录表（服务端主线程每 tick 都在写它）并调用 ServerLevel / Player 的
        // 主线程方法。跨线程读改一份 HashMap 会偶发读不到（可见性）或抛异常后丢包 ——
        // 玩家看到的就是「点了没反应 / 点几下才反应」。下发到服务端主线程后，一次点击 = 一次生效。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.AssemblyTaskActionPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AssemblyTaskActionPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.AssemblyTaskActionPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：监视器界面刚打开时请求补发一份告警快照。
        // 必须在这里注册：漏注册时发包会抛
        // UnsupportedOperationException: Payload rs_create_compat:request_assembly_alerts may not be sent to the server!
        // 本包在每次打开监视器时都会发 → 会直接把连接打断。
        // 同样必须 enqueueWork：只读读取记录表 + 向玩家发包都要求主线程。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequestAssemblyAlertsPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequestAssemblyAlertsPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.RequestAssemblyAlertsPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：「更换机器」——请求候选列表（ACTION_REQUEST）与指定步骤的机器（ACTION_SET）。
        // ACTION_SET 会原地改写样板库里的物品栈，**绝不能**在网络线程上做（跨线程写 Container
        // 会与主线程的服务端 tick 抢同一份库存 → 偶发失效 / 物品状态损坏）。统一 enqueueWork。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.AssemblyStepMachinePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AssemblyStepMachinePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.AssemblyStepMachinePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：上一条的答案——候选机器列表（客户端据此弹出「选择机器」子界面）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.AssemblyMachineCandidatesPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AssemblyMachineCandidatesPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.AssemblyMachineCandidatesPacket::handle
        );
        // S2C：「总样板改绑机器」界面的权威快照（手持总样板右键执行舱时发；写入成功后原样重发一份）。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket::handle
        );
        // C2S：把总样板的某一步改绑到某台执行舱。必须 enqueueWork —— 处理体会写玩家手上的物品栈
        // 与执行舱的单元样板容器（跨线程写 Container 会与主线程的服务端 tick 抢同一份库存）。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.AssemblyPatternStepMachinePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AssemblyPatternStepMachinePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.AssemblyPatternStepMachinePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：监视器界面上的「缺料处置」开关（挂起 / 等待）—— 服务端权威，只有正开着监视器时可改。
        // 必须 enqueueWork：处理体会读改主世界里那份 SavedData 并向玩家发包，都要求服务端主线程
        //（C2S 处理器默认跑在网络线程上）。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetShortageModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetShortageModePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetShortageModePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：监视器界面刚打开时请求补发一份「缺料处置策略」快照（只读，不改服务端状态）。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequestShortageModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequestShortageModePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.RequestShortageModePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：上面两条的答案——权威档位（客户端只读镜像，界面据此渲染开关）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncShortageModePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncShortageModePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncShortageModePacket::handle
        );
        // C2S：RS「自动合成预览」界面刚打开时请求补发一份「优先复用中间产物」开关快照
        //（只读、无参数、不改任何服务端状态）。那个界面没有服务端菜单（纯客户端开界面），
        // 搭不上 ContainerData，只能这样拿权威值；为什么要 enqueueWork 见上面 SetShortageModePacket。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequestIntermediateReusePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequestIntermediateReusePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.RequestIntermediateReusePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：上面那条的答案——开关权威值（客户端只读镜像，预览界面据此多显示一行中间产物提示）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncIntermediateReusePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncIntermediateReusePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncIntermediateReusePacket::handle
        );
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("RS & Create Compat server starting");
        // 诊断设施锚点：打一行「默认就在采集」的凭据（用户无需敲任何开关指令）。
        cretae.cookiewyq.rs_create_compat.support.RsccDiag.onServerStarted(event.getServer());
    }

    /**
     * 注册模组指令（指令根 = 模组 id）。目前是自动合成仓内部存储开关
     * {@code /rs_create_compat autocrafter storage <on|off>}（无权限要求，替代原界面按钮）。
     */
    @SubscribeEvent
    public void onRegisterCommands(final net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        cretae.cookiewyq.rs_create_compat.command.CompatCommands.register(event.getDispatcher());
    }
}
