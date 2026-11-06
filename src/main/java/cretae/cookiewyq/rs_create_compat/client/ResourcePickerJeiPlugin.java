package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * JEI 集成：<b>发送器界面不需要 JEI</b>（第 132 轮用户原话：「这个界面不需要 JEI，先隐藏」）。
 *
 * <p>做法不是去改 JEI 的配置，而是把「整个屏幕」报成这个 GUI 的额外占用区：
 * JEI 的 {@code contents.hasRoom()} 会因此变为 false，物品列表整体收起 ——
 * 这与 {@link CollectionCacheJeiPlugin} 给侧栏让位的机制是同一个
 * {@link IGuiContainerHandler#getGuiExtraAreas}，只是这里故意占满全屏。</p>
 *
 * <p>只注册 {@link ResourcePickerScreen}：其它界面（缓存仓 / RS 网格等）的 JEI 行为不变。</p>
 */
@JeiPlugin
public class ResourcePickerJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(
            RS_Create_Compat.MODID, "resource_picker_jei");
    }

    @Override
    public void registerGuiHandlers(final IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(ResourcePickerScreen.class,
            new IGuiContainerHandler<ResourcePickerScreen>() {
                @Override
                public List<Rect2i> getGuiExtraAreas(final ResourcePickerScreen screen) {
                    // 整个屏幕都算「GUI 占用」⇒ JEI 没有可放列表的空间，自动收起。
                    return List.of(new Rect2i(0, 0, screen.width, screen.height));
                }
            });
    }
}
