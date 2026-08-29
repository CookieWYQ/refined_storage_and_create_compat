package cretae.cookiewyq.rs_create_compat;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import cretae.cookiewyq.rs_create_compat.item.UniversalStorageDiskItem;
import cretae.cookiewyq.rs_create_compat.storage.UniversalStorageType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
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
    // Create a Deferred Register to hold Items which will all be registered under the "rs_create_compat" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "rs_create_compat" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // ========== 物品 ==========
    // 通用储存磁盘：可同时存入物品、流体、气体任意类型
    public static final DeferredItem<UniversalStorageDiskItem> UNIVERSAL_STORAGE_DISK =
        ITEMS.register("universal_storage_disk", UniversalStorageDiskItem::new);

    // 创造模式标签页
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
        CREATIVE_MODE_TABS.register("rs_create_compat", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.rs_create_compat"))
            .icon(() -> new ItemStack(UNIVERSAL_STORAGE_DISK.get()))
            .displayItems((parameters, output) -> {
                output.accept(UNIVERSAL_STORAGE_DISK.get());
            })
            .build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public RS_Create_Compat(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

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

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("RS & Create Compat server starting");
    }
}
