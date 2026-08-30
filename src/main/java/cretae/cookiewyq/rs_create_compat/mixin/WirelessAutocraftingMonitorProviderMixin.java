package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.api.autocrafting.AutocraftingMonitor;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 访问 RS 包内私有的无线自动合成仓监视器界面提供者构造器，
 * 用于打开 RS 原版监视器界面。
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting.monitor.WirelessAutocraftingMonitorExtendedMenuProvider")
public interface WirelessAutocraftingMonitorProviderMixin {
    @Invoker("<init>")
    static MenuProvider rscc$create(final Component name,
                                    final AutocraftingMonitor autocraftingMonitor,
                                    final SlotReference slotReference) {
        throw new AssertionError("mixin not applied");
    }
}
