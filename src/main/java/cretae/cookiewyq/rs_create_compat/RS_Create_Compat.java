package cretae.cookiewyq.rs_create_compat;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import io.netty.buffer.ByteBuf;
import com.refinedmods.refinedstorage.common.content.BlockEntities;
import cretae.cookiewyq.rs_create_compat.block.AdvancedSchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.AdvancedCollectionCacheBlock;
import cretae.cookiewyq.rs_create_compat.block.CollectionCacheBlock;
import cretae.cookiewyq.rs_create_compat.block.InventoryProxyBlock;
import cretae.cookiewyq.rs_create_compat.block.NetworkResourceReceiverBlock;
import cretae.cookiewyq.rs_create_compat.block.NetworkResourceRequesterBlock;
import cretae.cookiewyq.rs_create_compat.block.QuantityKeeperBlock;
import cretae.cookiewyq.rs_create_compat.block.RangeChargerBlock;
import cretae.cookiewyq.rs_create_compat.block.SchematicLoaderBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedSchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedCollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.item.UniversalStorageDiskItem;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.menu.InventoryProxyMenu;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccAutocrafterStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccDiskItemCapabilities;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.SequenceMaterialGuard.RawMaterialMark;
import cretae.cookiewyq.rs_create_compat.network.SetClusterRowPacket;
import cretae.cookiewyq.rs_create_compat.network.RestoreCursorPacket;
import cretae.cookiewyq.rs_create_compat.network.SetQuantityTargetPacket;
import cretae.cookiewyq.rs_create_compat.network.SetRangePacket;
import cretae.cookiewyq.rs_create_compat.network.ShowChargerRangePacket;
import cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket;
import cretae.cookiewyq.rs_create_compat.network.OpenChargerFilterPacket;
import cretae.cookiewyq.rs_create_compat.network.SetChargerFilterPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChargerFilterPacket;
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

    /**
     * 「区域选定器」的区域数据（{@code List<Area> + 绑定维度 + 选中下标}）。
     * <p>照 {@link #RAW_MATERIAL} 的同一套先例：注册版数据组件 + record 双 Codec
     * （{@code persistent} 管存档 / 掉落物，{@code networkSynchronized} 管客户端画框与界面列表）。
     * 上限不写死在这里：<b>消费侧</b>（扫描 / 界面计数 / 高级仓解析区域）一律过
     * {@code AreaSelection.sanitize}（Config 两个 int）；<b>写入侧刻意不 sanitize</b> ——
     * ADD / REPLACE 时按 Config 校验新几何（超限即拒绝），但绝不对物品上已有的区域做截断，
     * 于是「配置调小」不会把旧存档里超限的区域永久删掉（放回大值即恢复）。</p>
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<AreaSelection>> AREA_SELECTION =
        DATA_COMPONENTS.registerComponentType("area_selection",
            builder -> builder.persistent(AreaSelection.CODEC)
                .networkSynchronized(AreaSelection.STREAM_CODEC));

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

    /**
     * <b>区域选定器</b>（{@code rs_create_compat:area_selector}）。
     * <p>数据放注册版数据组件 {@link #AREA_SELECTION}（{@code List<Area> + 绑定维度 + 选中下标}）；
     * 一个选定器 = 一份区域集合，区域不同就是不同物品实例 ⇒ 强制不可堆叠（{@code stacksTo(1)}）。
     * 世界里的框选手势见 {@code client/AreaSelectorHandler}，列表界面见 {@code client/screen/AreaSelectorScreen}。</p>
     */
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.AreaSelectorItem> AREA_SELECTOR =
        ITEMS.register("area_selector",
            () -> new cretae.cookiewyq.rs_create_compat.item.AreaSelectorItem(
                new net.minecraft.world.item.Item.Properties().stacksTo(1)));

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

    /**
     * <b>电动飞行背包</b>（{@code rs_create_compat:electric_jetpack}，第 99 轮新增）。
     * <p>无耐久、无护甲值；电量 5000 万 FE（见 {@code Config.electricJetpackCapacity}）。
     * 可装备在 Curios 的 back / chest 槽（物品标签在 {@code data/curios/tags/item/}）或原版胸甲槽
     * （{@code DataComponents.EQUIPPABLE} 由物品类自己写进 components，这里只给基础属性）。
     * 飞行、耗电、HUD、低电提示都在 {@code item/ElectricJetpackItem} 与 {@code support/Jetpack*} 里。</p>
     */
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem> ELECTRIC_JETPACK =
        ITEMS.register("electric_jetpack", () -> new cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem(
            new net.minecraft.world.item.Item.Properties().stacksTo(1)));

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

    // ========== FE 能量立方（第 99 轮新增） ==========
    // 定位：优先级极低的能量缓存方块（备用电池）。单块 5000 万 FE，相邻六面容量共享，
    // 作为 RS 网络节点进入网络能量池但排在控制器之后（天然低优先级）。
    // 方块本身只做「存电 + 六个面的 FE 输入输出 + 一个电量条界面」，不提供任何物流操作。
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.FEEnergyCubeBlock> FE_ENERGY_CUBE_BLOCK =
        BLOCKS.register("fe_energy_cube", () -> new cretae.cookiewyq.rs_create_compat.block.FEEnergyCubeBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    // 物品形态（第 110 轮）：换成专用类 FEEnergyCubeItem —— 它在方块物品的基础上多三件事：
    //   ① 物品级 FE 能力（Capabilities.EnergyStorage.ITEM，拿在手上能被范围充电器充 / 被别的机器抽）；
    //   ② 物品栏「耐久样式」电量条（未满才显示）；
    //   ③ tooltip 常显精确电量（不按 Shift 也显示）。
    // 电量键与方块共用 FEEnergyCubeBlockEntity#TAG_ENERGY，所以「手上充的电 = 放下后的方块的电」。
    public static final DeferredItem<cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem> FE_ENERGY_CUBE_ITEM =
        ITEMS.register("fe_energy_cube", () -> new cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem(
            FE_ENERGY_CUBE_BLOCK.get(),
            Component.translatable("block.rs_create_compat.fe_energy_cube.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity>> FE_ENERGY_CUBE_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("fe_energy_cube",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity::new,
                FE_ENERGY_CUBE_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.FEEnergyCubeMenu>> FE_ENERGY_CUBE_MENU =
        MENUS.register("fe_energy_cube",
            () -> new MenuType<>((id, inventory) -> new cretae.cookiewyq.rs_create_compat.menu.FEEnergyCubeMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // ========== 降解室发电机（第 101 轮新增） ==========
    // 植物降解发电：16×16 物品槽只接受「可降解表」里的植物（数据包可扩展，木头/原木显式排除）；
    // 像熔炉一样走进度条，降解完成发一次电；内部缓存只输出、不接受输入；
    // 既是 RS 网络节点（为网络供能），也能直接给相邻方块供电。
    public static final DeferredBlock<cretae.cookiewyq.rs_create_compat.block.DecompositionGeneratorBlock> DECOMPOSITION_GENERATOR_BLOCK =
        BLOCKS.register("decomposition_generator", () -> new cretae.cookiewyq.rs_create_compat.block.DecompositionGeneratorBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> DECOMPOSITION_GENERATOR_ITEM =
        ITEMS.register("decomposition_generator", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            DECOMPOSITION_GENERATOR_BLOCK.get(),
            Component.translatable("block.rs_create_compat.decomposition_generator.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<cretae.cookiewyq.rs_create_compat.block.entity.DecompositionGeneratorBlockEntity>> DECOMPOSITION_GENERATOR_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("decomposition_generator",
            () -> BlockEntityType.Builder.of(cretae.cookiewyq.rs_create_compat.block.entity.DecompositionGeneratorBlockEntity::new,
                DECOMPOSITION_GENERATOR_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.menu.DecompositionGeneratorMenu>> DECOMPOSITION_GENERATOR_MENU =
        MENUS.register("decomposition_generator",
            () -> new MenuType<>((id, inventory) -> new cretae.cookiewyq.rs_create_compat.menu.DecompositionGeneratorMenu(id, inventory),
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

    // ========== 高级归流缓存仓（advanced_collection_cache） ==========
    // 用户拍板：「硅流缓存器」= 归流缓存仓 ⇒ 高级版 = 它的高级版；缓存容量更大、吞吐不变；
    // 吸收范围 = 只按「区域选定器」里的区域（取代 XYZ 半径，而不是交集）。
    // blockstate 属性名与基础版逐字一致（active），模型 / 贴图基于基础版改色加深（见 tools/gen_advanced_collection_cache_assets.py）。
    public static final DeferredBlock<AdvancedCollectionCacheBlock> ADVANCED_COLLECTION_CACHE_BLOCK =
        BLOCKS.register("advanced_collection_cache",
            () -> new AdvancedCollectionCacheBlock(
                BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> ADVANCED_COLLECTION_CACHE_ITEM =
        ITEMS.register("advanced_collection_cache",
            () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
                ADVANCED_COLLECTION_CACHE_BLOCK.get(),
                Component.translatable("block.rs_create_compat.advanced_collection_cache.help")));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AdvancedCollectionCacheBlockEntity>> ADVANCED_COLLECTION_CACHE_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("advanced_collection_cache",
            () -> BlockEntityType.Builder.of(AdvancedCollectionCacheBlockEntity::new,
                ADVANCED_COLLECTION_CACHE_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<AdvancedCollectionCacheMenu>> ADVANCED_COLLECTION_CACHE_MENU =
        MENUS.register("advanced_collection_cache",
            () -> new MenuType<>((id, inventory) -> new AdvancedCollectionCacheMenu(id, inventory),
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

    // ========== 网络资源接收器 / 请求器（已实现：注册 + 能力 + 投递 + 名字唯一性 + Jade + 界面） ==========
    // 用户拍板的设计（docs/NETWORK_RESOURCE_RECEIVER_DESIGN.md / docs/REQUESTER_ORDER_AND_PACER_DESIGN.md）：
    //   接收器 = 「带名字的落地点 + 缓存」：6 向 facing、必须有名字才可用、同网络唯一、6 个升级槽（只收速度 + 叠加）；
    //            facing 只用于成链（缓存 → 朝向的相邻容器）与气体/其它第三类资源的直通，与「谁能在哪一面
    //            看见这份 inventory」无关 —— 用户原话「作为 inventory 提供者时不应该有方向限制」；
    //   请求器 = 「按名字投递的搬运工」：无朝向（不输出到相邻容器）、配置存自己 NBT、同样 6 个升级槽（只收速度 + 叠加），
    //            红石上升沿 / 界面按钮触发一次投递：把订单量排进计划并按「每 tick 批数」逐步投进接收器缓存。
    // blockstate 的属性名必须与 Java 逐字一致，见各自方块类的类注释（接收器 12 变体 / 请求器 2 变体）。
    public static final DeferredBlock<NetworkResourceReceiverBlock> NETWORK_RESOURCE_RECEIVER_BLOCK =
        BLOCKS.register("network_resource_receiver", () -> new NetworkResourceReceiverBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> NETWORK_RESOURCE_RECEIVER_ITEM =
        ITEMS.register("network_resource_receiver", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            NETWORK_RESOURCE_RECEIVER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.network_resource_receiver.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkResourceReceiverBlockEntity>> NETWORK_RESOURCE_RECEIVER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("network_resource_receiver",
            () -> BlockEntityType.Builder.of(NetworkResourceReceiverBlockEntity::new,
                NETWORK_RESOURCE_RECEIVER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<NetworkResourceReceiverMenu>> NETWORK_RESOURCE_RECEIVER_MENU =
        MENUS.register("network_resource_receiver",
            () -> new MenuType<>((id, inventory) -> new NetworkResourceReceiverMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    public static final DeferredBlock<NetworkResourceRequesterBlock> NETWORK_RESOURCE_REQUESTER_BLOCK =
        BLOCKS.register("network_resource_requester", () -> new NetworkResourceRequesterBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> NETWORK_RESOURCE_REQUESTER_ITEM =
        ITEMS.register("network_resource_requester", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            NETWORK_RESOURCE_REQUESTER_BLOCK.get(),
            Component.translatable("block.rs_create_compat.network_resource_requester.help")
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkResourceRequesterBlockEntity>> NETWORK_RESOURCE_REQUESTER_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("network_resource_requester",
            () -> BlockEntityType.Builder.of(NetworkResourceRequesterBlockEntity::new,
                NETWORK_RESOURCE_REQUESTER_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<NetworkResourceRequesterMenu>> NETWORK_RESOURCE_REQUESTER_MENU =
        MENUS.register("network_resource_requester",
            () -> new MenuType<>((id, inventory) -> new NetworkResourceRequesterMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    // ========== 容器代理器（inventory_proxy） ==========
    // 用户已定（docs/CONTAINER_PROXY_DESIGN.md + docs/RS_DISK_ADAPTER_DESIGN.md）：
    //   单个代理对象槽；六面各一个角色（代理对象 / 内容物 / 禁用），逐面存 NBT（扳手右键 / 界面切换）；
    //   **不做 RS 网络节点**（capability-only）：若同时是网络节点且又暴露内容物能力，
    //   同一批物品会被「节点存储」与「外部存储」各计一次 = 真正的自指环（设计文档 §4.4.4 / R7）；
    //   内容物面把被代理物品自己的 ItemHandler / FluidHandler 暴露出去；RS 磁盘由
    //   support/RsccDiskItemCapabilities 统一补注册物品能力 ⇒ 代理器里没有按物品类型的特例。
    public static final DeferredBlock<InventoryProxyBlock> INVENTORY_PROXY_BLOCK =
        BLOCKS.register("inventory_proxy", () -> new InventoryProxyBlock(
            BlockBehaviour.Properties.of().strength(2.0F, 6.0F)
                .sound(net.minecraft.world.level.block.SoundType.STONE)
        ));
    // 物品的悬停 help 文本：**刻意不传**（第 95 轮用户要求「这个提示还是这么长一坨……你就把它去除不行吗」）。
    // 之前传的是 block.rs_create_compat.inventory_proxy.help（三句话，RS 的 BaseBlockItem 会常显），
    // 用户明确要求去掉；六面角色与「别贴两个外部存储」的信息现在只在界面里用一行短句表达，不再进 tooltip。
    // 语言键 inventory_proxy.help 也一并删除（第 95 轮）：没有任何引用点，留着只会变成孤儿键
    //（tools/selfcheck_round74_localization.py 会因此判负）。
    public static final DeferredItem<com.refinedmods.refinedstorage.common.support.BaseBlockItem> INVENTORY_PROXY_ITEM =
        ITEMS.register("inventory_proxy", () -> new cretae.cookiewyq.rs_create_compat.item.RsccHelpBlockItem(
            INVENTORY_PROXY_BLOCK.get(),
            null
        ));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InventoryProxyBlockEntity>> INVENTORY_PROXY_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("inventory_proxy",
            () -> BlockEntityType.Builder.of(InventoryProxyBlockEntity::new,
                INVENTORY_PROXY_BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<InventoryProxyMenu>> INVENTORY_PROXY_MENU =
        MENUS.register("inventory_proxy",
            () -> new MenuType<>((id, inventory) -> new InventoryProxyMenu(id, inventory),
                FeatureFlags.DEFAULT_FLAGS));

    /**
     * 「网格选资源」菜单（{@code client/grid/ResourcePickerMenu}，继承 RS 的 {@code AbstractGridContainerMenu}）。
     *
     * <h2>为什么是我们自己的 {@code MenuType}</h2>
     * <p>客户端开哪个界面<b>只由包里带的 {@code MenuType} 决定</b>（{@code ClientboundOpenScreenPacket} /
     * {@code AdvancedOpenScreenPayload} → {@code MenuScreens.getScreenFactory(menuType)}）；
     * 而 RS 的 {@code GridContainerMenu} 构造器把类型硬编码成 {@code Menus.INSTANCE.getGrid()}
     * ⇒ 必须自建子类 + 自建类型。<b>不需要 RS 的任何许可</b>：注册走 NeoForge 的
     * {@code RegisterMenuScreensEvent}（RS 自己也用它，见 {@code client/ClientInit}）。</p>
     *
     * <h2>为什么用 {@code IMenuTypeExtension}</h2>
     * <p>网格数据（{@code GridData}）与「已选清单镜像」必须随开屏一起下发，客户端据此重建同内容的网格 ——
     * 这正是 {@code IMenuTypeExtension.create(...)}（带额外数据的菜单类型）的用途，与本工程
     * {@code UNIT_PATTERN_MANAGER_MENU} 同一写法。</p>
     */
    public static final DeferredHolder<MenuType<?>, MenuType<cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu>>
        RESOURCE_PICKER_MENU =
        MENUS.register("resource_picker",
            () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                (id, inventory, data) -> cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
                    .read(id, inventory, data)));

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
                output.accept(FE_ENERGY_CUBE_ITEM.get()); // FE 能量立方（5000 万 FE / 相邻容量共享 / 网络备用电池）
                output.accept(ELECTRIC_JETPACK.get()); // 电动飞行背包（创造式飞行，按 FE 耗电）
                output.accept(DECOMPOSITION_GENERATOR_ITEM.get()); // 降解室发电机（植物降解发电 / 数据包可扩展）
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
                output.accept(ADVANCED_COLLECTION_CACHE_ITEM.get()); // 高级归流缓存仓（区域取代 XYZ 半径）
                output.accept(AREA_SELECTOR.get()); // 区域选定器（高级仓的专用槽物品）
                output.accept(INTERMEDIATE_CACHE_ITEM.get()); // 中间产物缓存仓（执行舱共享）
                output.accept(UNIT_PATTERN_MANAGER_ITEM.get()); // 单元样板管理舱
                output.accept(NETWORK_RESOURCE_RECEIVER_ITEM.get()); // 网络资源接收器
                output.accept(NETWORK_RESOURCE_REQUESTER_ITEM.get()); // 网络资源请求器
                output.accept(INVENTORY_PROXY_ITEM.get()); // 容器代理器（内容物面 / 代理对象面按面暴露）
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
        // FE 能量立方：六面 FE（Capabilities.EnergyStorage.BLOCK）+ RS 网络节点（在 BE 里注册网络节点容器）
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity::registerCapabilities);
        // 电动飞行背包：物品级 FE 存储（Capabilities.EnergyStorage.ITEM）
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem::registerCapabilities);
        // FE 能量立方（物品形态）：物品级 FE 存储（Capabilities.EnergyStorage.ITEM）—— 第 110 轮
        // 与方块能力（上面那行 EnergyStorage.BLOCK）是两回事，注册项不同、互不覆盖，各自只注册一次。
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem::registerCapabilities);
        // 降解室发电机：物品 handler（可进可出）+ FE（只出）+ RS 网络节点容器
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.DecompositionGeneratorBlockEntity::registerCapabilities);
        modEventBus.addListener(QuantityKeeperBlockEntity::registerCapabilities);
        modEventBus.addListener(AdvancedQuantityKeeperBlockEntity::registerCapabilities);
        modEventBus.addListener(SchematicLoaderBlockEntity::registerCapabilities);
        modEventBus.addListener(AdvancedSchematicLoaderBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity::registerCapabilities);
        modEventBus.addListener(CollectionCacheBlockEntity::registerCapabilities);
        modEventBus.addListener(AdvancedCollectionCacheBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity::registerCapabilities);
        modEventBus.addListener(cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity::registerCapabilities);
        // 网络资源接收器 / 请求器：接进 RS 网络图（「必须插线缆」与「同网络唯一」都建立在网络图之上）
        modEventBus.addListener(NetworkResourceReceiverBlockEntity::registerCapabilities);
        modEventBus.addListener(NetworkResourceRequesterBlockEntity::registerCapabilities);
        // 容器代理器：只注册方块能力（按面），**不**注册网络节点容器能力
        modEventBus.addListener(InventoryProxyBlockEntity::registerCapabilities);
        // RS 存储磁盘（含本模组通用盘）：补注册物品物品能力，供代理器零特例代理
        modEventBus.addListener(RsccDiskItemCapabilities::register);
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

        // 结构版本号（epoch）：方块放置 / 破坏 / 被替换时 +1，供「链成员」这类纯结构推导的
        // 每 tick 备忘做失效判定（见 support/RsccStructureEpoch 与
        // SequenceExecutionChamberBlockEntity#chainMembers）。刻意只做一次自增，不搜索、不取方块实体。
        cretae.cookiewyq.rs_create_compat.support.RsccStructureEpoch.register();

        // 右键交互的三个挂点（都是 PlayerInteractEvent.RightClickBlock，同为 HIGH 优先级）。
        // 同一优先级内按<b>注册顺序</b>调用，因此顺序本身就是语义：
        //   ① 伪装外壳（最外面那一层）：选外壳 / 潜行取下；也不允许别人抢先处理已裹住的格子；
        //   ② 分隔框架：潜行右键取下并回收框架本体（必须抢在 RS 自己的扳手处理「潜行 = 拆掉整根线缆」之前）；
        //   ③ 扳手断缝：最后结算，只处理「不是伪装/分隔框架那一层」的右键，断开 / 恢复这一道缝。
        // 三者各自只处理自己那一层，靠 event.setCanceled + 上面这条注册顺序串成一条确定的链。
        cretae.cookiewyq.rs_create_compat.support.RsccCamouflageInteraction.register();
        cretae.cookiewyq.rs_create_compat.support.RsccSheathInteraction.register();
        cretae.cookiewyq.rs_create_compat.support.RsccWrenchCableInteraction.register();
        // 容器代理器的「扳手右键切换命中面」：同一条挂点链、同优先级里排在快速拆卸之后
        //（潜行+扳手仍归快速拆卸：它先取消，本类在 isCanceled 处直接返回）。
        cretae.cookiewyq.rs_create_compat.support.RsccInventoryProxyWrench.register();

        // 两种框架的「连锁套壳」：挂 FTB Ultimine 的右键连锁 API（可选依赖，全程反射；
        // 没装 FTB Ultimine 时 register() 内部第一句就返回，行为 = 默认的单格套壳）。
        // 普通右键（不按连锁键）永远只作用于命中那一格，不经过这条链路。
        cretae.cookiewyq.rs_create_compat.support.RsccUltimineIntegration.register();

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 第 99 轮：老存档 / 老配置文件的「范围充电器缓存」迁移。
        // NeoForge 的 ModConfigSpec 只在「键不存在」时写默认值，已有键原样保留 —— 于是把默认值从
        // 1000 万改成 5000 万，对一个已经装着本模组的实例完全不起作用（本机 run/config 里甚至还是
        // 更早的 100 万）。用户明确要求「范围充电器的缓存也改到 5000 万」，所以这里做一次性迁移：
        // 只改「恰好等于历史默认值」的实例，玩家自己调过的数值一律不动（绝不覆盖玩家配置）。
        Config.migrateLegacyRangeChargerCapacity();
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
        // 范围充电器：「查看充电范围」的服务端响应（S2C 坐标 + 三轴范围；C2S 请求走原版菜单按钮通道）
        registrar.playToClient(
            ShowChargerRangePacket.TYPE,
            ShowChargerRangePacket.STREAM_CODEC,
            ShowChargerRangePacket::handle
        );
        // 归流缓存仓（基础 + 高级）：「查看吸取范围」的服务端权威几何快照（S2C）。
        // 几何以「方块区域」下发（半径口径 = 一个扫描盒；区域口径 = 选定器里去重后的若干区域），
        // 客户端只做 Area#toAabb() 一个转换，不读方块实体 / 不读半径（客户端菜单里 BE 恒为 null）。
        // C2S 请求走原版菜单按钮通道：CollectionCacheMenu.BUTTON_SHOW_RANGE。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.ShowCollectionRangePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.ShowCollectionRangePacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.ShowCollectionRangePacket::handle
        );
        // 第 106 轮 高级归流缓存仓：区域列表权威快照（S2C）。
        // 服务端算「哪些区域真的可用 + 每区域的过滤图标 + 当前编辑上下文 + 拒绝原因」，客户端只画；
        // 无 C2S 请求包（菜单在 broadcastChanges 里按版本号变化补发），无新增数据槽（round91 钉死 54 个不改）。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket::handle
        );
        // 电动飞行背包（第 99 轮）：HUD 权威快照（服务端每 5 tick 一条；装备状态变化时立即补一条）。
        // 只写双端安全的 JetpackHudState，客户端据此画电量条与「范围内 / 充电中」，客户端自己不算电量。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.JetpackStatusPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.JetpackStatusPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.JetpackStatusPacket::handle
        );
        // 范围充电器：充电目标高亮（S2C「本 tick 真充了谁」；Config.rangeChargerHighlightTargets=false 时
        // 服务端一个包都不发 —— 客户端因此天然不会残留旧高亮，不需要「关闭时再发一次清空包」）
        registrar.playToClient(
            ChargerHighlightPacket.TYPE,
            ChargerHighlightPacket.STREAM_CODEC,
            ChargerHighlightPacket::handle
        );
        // 降解室发电机（第 129 轮）：JEI「降解发电」分类页的数据源（S2C 整表快照）。
        // 为什么必须下发：降解表是数据包驱动的、加载器只在服务端跑（AddReloadListenerEvent），
        // 专用服务器上客户端那张表恒空 ⇒ JEI 分类页会静默变成空的。发送时机见
        // network/DecompositionDisplaySync（OnDatapackSyncEvent：进服 + /reload）。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncDecompositionDisplayPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncDecompositionDisplayPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncDecompositionDisplayPacket::handle
        );
        // 范围充电器：充电过滤（黑/白名单 + 掉落物 / 玩家身上物品 / 玩家 三类名单）。
        // 打开链路：界面按钮 → C2S「请给我权威快照」 → BE 回 S2C 快照 → 客户端据此打开子界面。
        // 必须 enqueueWork：处理体会读方块实体、写存档级已知玩家池并向玩家发包，都要求服务端主线程。
        registrar.playToServer(
            OpenChargerFilterPacket.TYPE,
            OpenChargerFilterPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() -> OpenChargerFilterPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // 范围充电器：过滤配置整体覆盖（界面每次勾选 / 添加 / 删除都发<b>全量</b>；服务端整体写入并落盘，
        // 不回 ack —— 界面是乐观更新，服务端权威，客户端下一次打开时会拿到最新快照）。
        registrar.playToServer(
            SetChargerFilterPacket.TYPE,
            SetChargerFilterPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() -> SetChargerFilterPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // 范围充电器：过滤配置 + 已知玩家候选池的权威快照（S2C）。处理体只经 ClientPayloadHooks
        // 间接调客户端，专用服务端加载本类时不会链接 net.minecraft.client.* 类型。
        registrar.playToClient(
            SyncChargerFilterPacket.TYPE,
            SyncChargerFilterPacket.STREAM_CODEC,
            SyncChargerFilterPacket::handle
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
        // 区域选定器：增 / 改 / 删 / 选中 / 改绑维度（C2S，服务端校验后写入物品数据组件）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // 区域选定器（第 112 轮）：给「某一个区域」配 ghost 过滤标记（C2S）。
        // 与上面的区域包共用 AreaSelectorTargets 的落点判据；标记存在选定器物品上（support/AreaMarks），
        // 因此拿着选定器就能配，不必先把选定器插进机器。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.AreaMarkEditPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.AreaMarkEditPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.AreaMarkEditPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
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
        // 归流缓存仓（高级版）：「当前正在编辑哪个区域的过滤」上下文（C2S，第 101 轮）。
        // 每区域独立过滤是用户明确要的能力（相邻机器重复吸放的解法）：界面用 < > 切换编辑对象时
        // 把区域序号发给服务端，服务端 sanitize 后校验序号、只改那一份过滤器。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetAreaFilterContextPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetAreaFilterContextPacket.STREAM_CODEC,
            (packet, ctx) -> cretae.cookiewyq.rs_create_compat.network.SetAreaFilterContextPacket.handle(
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
        // ===== 网络资源接收器 / 请求器：配置 UI 与数据包（服务端权威） =====
        // C2S：接收器改名（空名 / 超长 / 非法字符 / 同网络重名全部在菜单里拒绝）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetReceiverNamePacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetReceiverNamePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetReceiverNamePacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：接收器资源列表的「取出 / 翻页」（第 96 轮：资源身份 + 全部标志）。
        // 服务端在 NetworkResourceReceiverMenu#handleCacheAction 里校验 containerId / 菜单类型 / 距离，
        // 再从真实缓存扣减：物品按身份跨格取、放不下回填；流体 1000 mB/桶（背包必须有空桶，没有就一点不扣）。
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.ReceiverCacheActionPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.ReceiverCacheActionPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.ReceiverCacheActionPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：接收器权威配置快照（名字 + 本网络已占用名字集合 + 操作结果语言键）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket::handle
        );
        // C2S：请求器设定目标名（必须能在本网络里唯一解析到一台接收器）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.SetRequesterTargetPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SetRequesterTargetPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.SetRequesterTargetPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：订单清单增 / 删 / 改数量（一次只做一个动作；服务端逐字段校验后回发权威清单）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.RequesterOrderPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.RequesterOrderPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.RequesterOrderPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：请求器权威配置快照（目标 + 订单清单 + 本网络接收器名字 + 操作结果语言键）
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket::handle
        );
        // C2S：界面上的「立即发送」→ 服务端调用方块实体既有的 triggerRequest()（搬运逻辑不在网络层）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.TriggerRequesterPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.TriggerRequesterPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.TriggerRequesterPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // C2S：请求器界面上的「网格」按钮 → 服务端打开我们自己的网格选资源菜单
        //（客户端开哪个界面只看服务端发出的 MenuType；见 RESOURCE_PICKER_MENU 的类注释）
        registrar.playToServer(
            cretae.cookiewyq.rs_create_compat.network.OpenRequesterPickerPacket.TYPE,
            cretae.cookiewyq.rs_create_compat.network.OpenRequesterPickerPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() ->
                cretae.cookiewyq.rs_create_compat.network.OpenRequesterPickerPacket.handle(
                    packet, (net.minecraft.server.level.ServerPlayer) ctx.player()))
        );
        // S2C：序列装配「总线 → 工位」只读快照（第 104 轮 B-4）。
        // 只发给「打开的正是这条总线类别详细配置界面」的玩家，变化才发；
        // 界面据此在页顶画两行只读徽标（朝向方块 + 该工位推导出的步），不一致时黄字，只展示不拦。
        registrar.playToClient(
            cretae.cookiewyq.rs_create_compat.support.RsccBusStationSync.TYPE,
            cretae.cookiewyq.rs_create_compat.support.RsccBusStationSync.STREAM_CODEC,
            cretae.cookiewyq.rs_create_compat.support.RsccBusStationSync::handle
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
