package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 允许"虚拟方块实体"（高级远程终端用反射在内存中构造、未放置在世界中的
 * AutocrafterManagerBlockEntity）的合成仓管理器界面保持打开。
 * <p>
 * RS 原版 {@code AutocrafterManagerContainerMenu.stillValid} 走
 * {@code Container.stillValidBlockEntity(autocrafterManager, player)}，
 * 虚拟 BE 永远无法满足，导致界面 1 tick 内被服务端关闭。
 * 这里约定：位置为原点且世界中不存在该 BE 的方块实体视为虚拟 BE，允许其界面保持打开。
 */
@Mixin(AutocrafterManagerContainerMenu.class)
public abstract class VirtualAutocrafterManagerMenuMixin {

    @Shadow
    private AutocrafterManagerBlockEntity autocrafterManager;

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void rscc$allowVirtualManagerMenu(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (autocrafterManager != null
            && autocrafterManager.getBlockPos().equals(BlockPos.ZERO)
            && autocrafterManager.getLevel() != null
            && autocrafterManager.getLevel().getBlockEntity(BlockPos.ZERO) != autocrafterManager) {
            cir.setReturnValue(true);
        }
    }
}
