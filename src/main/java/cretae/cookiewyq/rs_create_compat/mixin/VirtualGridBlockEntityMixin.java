package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.grid.AbstractGridBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccVirtualTerminalBe;
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
 *
 * <h2>判据为什么不再看世界（用户实机反馈：「站着不动打不开、动一下就打开」）</h2>
 * <p>旧判据是「位置等于 {@code BlockPos.ZERO} <b>且</b> {@code level.getBlockEntity(BlockPos.ZERO) != 本 BE}」：
 * 它把结论押在<b>世界原点那一格</b>上 —— 而「原点有没有方块实体」与「原点所在区块加载了没有」
 * 都会随玩家走动（区块票据随位置变化）而改变，于是同一个界面会在「开着」与「1 tick 被关掉」之间跳变。
 * 现在统一走 {@link RsccVirtualTerminalBe#isVirtual}：只比一个立在建筑高度之外的哨兵坐标，
 * <b>不读世界、不查区块、不看玩家位置</b>，界面能不能保持打开因此与玩家是否移动结构上无关。
 */
@Mixin(AbstractGridBlockEntity.class)
public abstract class VirtualGridBlockEntityMixin {

    @Inject(method = "canMenuStayOpen", at = @At("HEAD"), cancellable = true)
    private void rscc$allowVirtualGridMenu(final Player player, final CallbackInfoReturnable<Boolean> cir) {
        final AbstractGridBlockEntity be = (AbstractGridBlockEntity) (Object) this;
        if (RsccVirtualTerminalBe.isVirtual(be)) {
            cir.setReturnValue(true);
        }
    }
}
