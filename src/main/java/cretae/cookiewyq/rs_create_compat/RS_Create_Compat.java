package cretae.cookiewyq.rs_create_compat;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.content.BlockEntities;
import cretae.cookiewyq.rs_create_compat.block.AdvancedSchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.QuantityKeeperBlock;
import cretae.cookiewyq.rs_create_compat.block.RangeChargerBlock;
import cretae.cookiewyq.rs_create_compat.block.SchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.item.UniversalStorageDiskItem;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedRemoteTerminalMenu;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.mixin.AutocrafterAccessor;
import cretae.cookiewyq.rs_create_compat.storage.UniversalStorageType;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
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
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

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

    // ========== 物品 ==========
    // 通用储存磁盘：可同时存入物品、流体、气体任意类型
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK =
        ITEMS.register("universal_storage_disk", UniversalStorageDiskItem::new);

    // 高级远程多功能终端：带能量，可切换多种界面
    public static final DeferredItem<AdvancedRemoteTerminalItem> ADVANCED_REMOTE_TERMINAL =
        ITEMS.register("advanced_remote_terminal", AdvancedRemoteTerminalItem::new);
    public static final DeferredHolder<MenuType<?>, MenuType<AdvancedRemoteTerminalMenu>> ADVANCED_REMOTE_TERMINAL_MENU =
        MENUS.register("advanced_remote_terminal",
            () -> new MenuType<>((id, inventory) -> new AdvancedRemoteTerminalMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // ========== 方块 ==========
    // 范围充电器
    public static final DeferredBlock<RangeChargerBlock> RANGE_CHARGER_BLOCK =
        BLOCKS.register("range_charger", () -> new RangeChargerBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(3.5F)
        ));
    public static final DeferredItem<BlockItem> RANGE_CHARGER_ITEM =
        ITEMS.registerSimpleBlockItem("range_charger", RANGE_CHARGER_BLOCK);
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
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(3.5F)
        ));
    public static final DeferredItem<BlockItem> QUANTITY_KEEPER_ITEM =
        ITEMS.registerSimpleBlockItem("quantity_keeper", QUANTITY_KEEPER_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuantityKeeperBlockEntity>> QUANTITY_KEEPER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("quantity_keeper",
            () -> BlockEntityType.Builder.of(QuantityKeeperBlockEntity::new, QUANTITY_KEEPER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<QuantityKeeperMenu>> QUANTITY_KEEPER_MENU =
        MENUS.register("quantity_keeper",
            () -> new MenuType<>((id, inventory) -> new QuantityKeeperMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 蓝图加农炮装填器（基础版）
    public static final DeferredBlock<SchematicLoaderBlock> SCHEMATIC_LOADER_BLOCK =
        BLOCKS.register("schematic_loader", () -> new SchematicLoaderBlock(
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(3.5F)
        ));
    public static final DeferredItem<BlockItem> SCHEMATIC_LOADER_ITEM =
        ITEMS.registerSimpleBlockItem("schematic_loader", SCHEMATIC_LOADER_BLOCK);
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
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(4.0F)
        ));
    public static final DeferredItem<BlockItem> ADVANCED_SCHEMATIC_LOADER_ITEM =
        ITEMS.registerSimpleBlockItem("advanced_schematic_loader", ADVANCED_SCHEMATIC_LOADER_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AdvancedSchematicLoaderBlockEntity>> ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("advanced_schematic_loader",
            () -> BlockEntityType.Builder.of(AdvancedSchematicLoaderBlockEntity::new,
                ADVANCED_SCHEMATIC_LOADER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<AdvancedSchematicLoaderMenu>> ADVANCED_SCHEMATIC_LOADER_MENU =
        MENUS.register("advanced_schematic_loader",
            () -> new MenuType<>((id, inventory) -> new AdvancedSchematicLoaderMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // 创造模式标签页
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
        CREATIVE_MODE_TABS.register("rs_create_compat", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.rs_create_compat"))
            .icon(() -> new ItemStack(UNIVERSAL_STORAGE_DISK.get()))
            .displayItems((parameters, output) -> {
                output.accept(UNIVERSAL_STORAGE_DISK.get());
                output.accept(ADVANCED_REMOTE_TERMINAL.get());
                output.accept(RANGE_CHARGER_ITEM.get());
                output.accept(QUANTITY_KEEPER_ITEM.get());
                output.accept(SCHEMATIC_LOADER_ITEM.get());
                output.accept(ADVANCED_SCHEMATIC_LOADER_ITEM.get());
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

        // 注册方块能力（能量输出 / 物品处理器 / 网络节点容器）
        modEventBus.addListener(RangeChargerBlockEntity::registerCapabilities);
        modEventBus.addListener(QuantityKeeperBlockEntity::registerCapabilities);
        modEventBus.addListener(SchematicLoaderBlockEntity::registerCapabilities);
        modEventBus.addListener(AdvancedSchematicLoaderBlockEntity::registerCapabilities);
        // 调整一：自动合成仓内部输出存储（物品 + 流体能力）
        modEventBus.addListener(RS_Create_Compat::registerAutocrafterCapabilities);

        // 配置加载
        modEventBus.addListener(Config::onLoad);

        // Register ourselves for server and other game events we are interested in.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 注册通用存储类型（必须在任何磁盘数据序列化之前注册，供 RS 存档编解码使用）
        RefinedStorageApi.INSTANCE.getStorageTypeRegistry().register(
            ResourceLocation.fromNamespaceAndPath(MODID, "universal"),
            UniversalStorageType.INSTANCE
        );

        LOGGER.info("RS & Create Compat common setup complete");
    }

    /** 调整一：为 RS 自动合成仓注册内部输出存储能力（物品 + 流体，可开关）。 */
    private static void registerAutocrafterCapabilities(final RegisterCapabilitiesEvent event) {
        if (!Config.autocrafterStorageEnabled) {
            return;
        }
        final var autocrafterType = BlockEntities.INSTANCE.getAutocrafter();
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            autocrafterType,
            (blockEntity, direction) -> blockEntity instanceof AutocrafterAccessor accessor
                ? accessor.rscc$getOutputStorage()
                : null
        );
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            autocrafterType,
            (blockEntity, direction) -> blockEntity instanceof AutocrafterAccessor accessor
                ? accessor.rscc$getOutputTank()
                : null
        );
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("RS & Create Compat server starting");
    }
}
