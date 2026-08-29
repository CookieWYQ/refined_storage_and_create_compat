package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问自动合成仓 mixin 注入的内部输出存储。
 */
@Mixin(AutocrafterBlockEntity.class)
public interface AutocrafterAccessor {
    @Accessor("rscc$outputStorage")
    ItemStackHandler rscc$getOutputStorage();

    @Accessor("rscc$outputTank")
    FluidTank rscc$getOutputTank();
}
