package cretae.cookiewyq.rs_create_compat.mixin.createcraft;

import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import cretae.cookiewyq.rs_create_compat.compat.crafter.OutputReclaim;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自动合成仓每 tick 的产物采集入口（动力搅拌机 / 盆方向）。
 *
 * <p>{@code AutocrafterBlockEntity#doWork()} 由方块实体 ticker 每 tick 调用一次
 * （{@code AbstractBaseNetworkNodeContainerBlockEntity#doWork} → {@code ticker.tick(...)}，
 * build/rs_src_full/.../support/network/AbstractBaseNetworkNodeContainerBlockEntity.java:132-134），
 * 是「自动合成仓自己的 tick」里最稳的挂载点：不依赖网络是否激活、也不会被 Create 的 tick 顺序影响。</p>
 *
 * <p>注入 TAIL：先让 RS 原逻辑完成它的工作（含 {@code updateLocked()}），再搬产物，
 * 这样采集失败也不会影响 RS 自己的状态机。</p>
 *
 * <p><b>服务端限定</b>：内部先判 {@code getLevel() instanceof ServerLevel}，
 * 客户端永远不会走到采集逻辑（{@code doWork} 在客户端由同一 ticker 调用）。</p>
 */
@Mixin(AutocrafterBlockEntity.class)
public abstract class AutocrafterCreateCollectMixin {
    @Inject(method = "doWork", at = @At("TAIL"))
    private void rscc$collectCreateOutputs(final CallbackInfo ci) {
        final AutocrafterBlockEntity self = (AutocrafterBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel level) {
            OutputReclaim.tick(level, self);
        }
    }
}
