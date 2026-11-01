package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccVirtualTerminalBe;
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
 *
 * <h2>判据为什么不再看世界</h2>
 * <p>与 {@link VirtualGridBlockEntityMixin} 同一个理由（并共用同一个唯一判据
 * {@link RsccVirtualTerminalBe#isVirtual}）：旧写法把「是不是虚拟的」押在世界原点那一格上，
 * 而那一格的方块实体与区块加载状态<b>会随玩家走动变化</b>，表现为「站着不动打不开、动一下就打开」。
 * 现在只比一个立在建筑高度之外的哨兵坐标，零世界访问、零玩家状态。
 */
@Mixin(AutocrafterManagerContainerMenu.class)
public abstract class VirtualAutocrafterManagerMenuMixin {

    @Shadow
    private AutocrafterManagerBlockEntity autocrafterManager;

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void rscc$allowVirtualManagerMenu(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        if (RsccVirtualTerminalBe.isVirtual(autocrafterManager)) {
            cir.setReturnValue(true);
        }
    }
}
