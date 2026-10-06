package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.grid.AbstractGridBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 允许"虚拟方块实体"（高级远程终端用反射在内存中构造、未放置在世界中的
 * PatternGridBlockEntity 等网格方块实体）的界面保持打开。
 * <p>
 * RS 原版 {@code AbstractGridContainerMenu.stillValid} 走 {@code grid.canMenuStayOpen(player)}，
 * 而方块网格实现为 {@code Container.stillValidBlockEntity(this, player)}，要求
 * {@code level.getBlockEntity(pos) == this}，虚拟 BE 永远无法满足，导致界面 1 tick 内被服务端关闭。
 * 这里约定：位置为原点且世界中不存在该 BE 的方块实体视为虚拟 BE，允许其界面保持打开。
 */
@Mixin(AbstractGridBlockEntity.class)
public abstract class VirtualGridBlockEntityMixin {

    @Inject(method = "canMenuStayOpen", at = @At("HEAD"), cancellable = true)
    private void rscc$allowVirtualGridMenu(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        final AbstractGridBlockEntity be = (AbstractGridBlockEntity) (Object) this;
        if (be.getBlockPos().equals(BlockPos.ZERO)
            && be.getLevel() != null
            && be.getLevel().getBlockEntity(BlockPos.ZERO) != be) {
            cir.setReturnValue(true);
        }
    }
}
