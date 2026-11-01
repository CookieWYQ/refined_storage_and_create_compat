package cretae.cookiewyq.rs_create_compat.mixin.createcraft;

import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import cretae.cookiewyq.rs_create_compat.compat.crafter.OutputReclaim;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 动力合成器产物「掉落」拦截：能写回 RS 网络就不掉到地上。
 *
 * <h2>为什么选 {@code dropItem} 作为挂载点</h2>
 * <p>Create 的动力合成器在装配完成后走 {@code tryInsert()}：前面有容器就塞容器，否则
 * {@code ejectWholeGrid()} → 逐件 {@code dropItem(Vec3, ItemStack)} 掉到地上
 * （build/createsrc/.../crafter/MechanicalCrafterBlockEntity.java:421-484）。{@code dropItem} 是
 * <b>public</b> 方法，且它是「产物真正离开机器」的唯一出口——在这里拦，既能拿到产物堆，
 * 又不碰 Create 的状态机（{@code eject()} 会自己继续把 phase 复位成 IDLE）。</p>
 *
 * <p>判定与写入全部在 {@link OutputReclaim#tryReclaimCraftResult} 里：
 * 只认「本模组登记过的产物」（物品 + 组件精确匹配）且「RS 网络能整堆收下」；
 * 任一条件不满足就<b>不取消</b>，让 Create 原样掉落（今天的默认行为，绝不吞物品）。</p>
 *
 * <p><b>服务端限定</b>：{@code dropItem} 只在服务端 tick 里被调用（客户端 {@code eject()} 不会走
 * 这条路），这里仍然显式判 {@code ServerLevel} 以防被别的分支复用。</p>
 */
@Mixin(MechanicalCrafterBlockEntity.class)
public abstract class MechanicalCrafterOutputMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void rscc$reclaimCraftResult(final Vec3 ejectPos,
                                         final ItemStack stack,
                                         final CallbackInfo ci) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final MechanicalCrafterBlockEntity self = (MechanicalCrafterBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (OutputReclaim.tryReclaimCraftResult(level, self.getBlockPos(), stack)) {
            ci.cancel();
        }
    }
}
