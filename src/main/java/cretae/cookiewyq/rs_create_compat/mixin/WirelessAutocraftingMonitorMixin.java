package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.api.autocrafting.AutocraftingMonitor;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 访问 RS 包内私有的无线自动合成仓监视器构造器，
 * 以便高级远程多功能终端直接复用 RS 原版监视器界面。
 */
@Mixin(targets = "com.refinedmods.refinedstorage.common.autocrafting.monitor.WirelessAutocraftingMonitor")
public interface WirelessAutocraftingMonitorMixin {
    @Invoker("<init>")
    static AutocraftingMonitor rscc$create(final NetworkItemContext context) {
        throw new AssertionError("mixin not applied");
    }
}
