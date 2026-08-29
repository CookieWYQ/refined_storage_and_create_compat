package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.common.api.RefinedStorageClientApi;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.AdvancedSchematicLoaderScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.QuantityKeeperScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.RangeChargerScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.SchematicLoaderScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端初始化：向 RS 注册通用储存磁盘的 3D 模型，并注册各 GUI 屏幕。
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public class ClientInit {

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> RefinedStorageClientApi.INSTANCE.registerDiskModel(
            RS_Create_Compat.UNIVERSAL_STORAGE_DISK.get(),
            ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "block/disk/disk")
        ));
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(final RegisterMenuScreensEvent event) {
        event.register(RS_Create_Compat.RANGE_CHARGER_MENU.get(), RangeChargerScreen::new);
        event.register(RS_Create_Compat.QUANTITY_KEEPER_MENU.get(), QuantityKeeperScreen::new);
        event.register(RS_Create_Compat.SCHEMATIC_LOADER_MENU.get(), SchematicLoaderScreen::new);
        event.register(RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_MENU.get(), AdvancedSchematicLoaderScreen::new);
    }

    private ClientInit() {
    }
}
