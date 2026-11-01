package cretae.cookiewyq.rs_create_compat.mixin.createcraft;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProviderExternalPatternSink;
import com.refinedmods.refinedstorage.api.network.impl.node.patternprovider.PatternProviderNetworkNode;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import cretae.cookiewyq.rs_create_compat.compat.crafter.CreateCraftDispatch;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * 把 RS 自动合成仓的 {@code accept} 接管点提到<b>网络节点</b>这一层，专供 Create 机器使用。
 *
 * <h2>为什么不直接注入 {@code AutocrafterBlockEntity#accept}</h2>
 * <p>同一方法 HEAD 上已经有一个既有注入（{@code AutocrafterStorageMixin} 的内部存储接管），
 * 而且默认配置下它是开启的。两处 HEAD 注入谁先执行取决于 mixin 注册顺序 —— 那是最脆弱的一类
 * 隐式依赖。注入到更外层的 {@link PatternProviderNetworkNode#accept} 后：本模组一旦给出结果，
 * 内层 {@code AutocrafterBlockEntity#accept}（以及其中的内部存储接管、原版 composite sink）
 * <b>根本不会被调用</b>，互斥由调用链结构保证。</p>
 *
 * <h2>为什么可以 @Shadow 到 sink</h2>
 * <p>{@code sink} 是 {@link PatternProviderNetworkNode} 自己的私有字段
 * （.../impl/node/patternprovider/PatternProviderNetworkNode.java:34），字段型 {@code @Shadow}
 * 是 mixin 支持的标准用法（不像私有方法那样需要 accessor/invoker）。{@code sink} 由
 * {@code AutocrafterBlockEntity} 在构造时设为自身（.../AutocrafterBlockEntity.java:123
 * {@code this.mainNetworkNode.setSink(this)}），因此 {@code sink instanceof AutocrafterBlockEntity}
 * 就是「这个 pattern provider 是一台自动合成仓」的判据。</p>
 *
 * <p><b>顺序说明</b>：{@code @Mixin(priority = 1500)} 只是显式写死「本注入晚于默认 1000 的注入
 * 应用」，让工具链上的顺序可读；<b>正确性不依赖它</b>——依赖的是上面的调用链互斥。</p>
 */
@Mixin(value = PatternProviderNetworkNode.class, priority = 1500)
public abstract class AutocrafterCreateCraftMixin {
    @Shadow
    @Nullable
    private PatternProviderExternalPatternSink sink;

    /**
     * 注入描述符与目标方法完全一致：
     * {@code accept(Pattern, Collection, Action) -> ExternalPatternSink.Result}。
     */
    @Inject(
        method = "accept(Lcom/refinedmods/refinedstorage/api/autocrafting/Pattern;"
            + "Ljava/util/Collection;"
            + "Lcom/refinedmods/refinedstorage/api/core/Action;)"
            + "Lcom/refinedmods/refinedstorage/api/autocrafting/task/ExternalPatternSink$Result;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void rscc$createCraftAccept(final Pattern pattern,
                                        final Collection<ResourceAmount> resources,
                                        final Action action,
                                        final CallbackInfoReturnable<ExternalPatternSink.Result> cir) {
        if (!(sink instanceof AutocrafterBlockEntity autocrafter)) {
            return; // 不是自动合成仓（理论上不会发生）：完全放行原逻辑
        }
        if (!(autocrafter.getLevel() instanceof ServerLevel level)) {
            return; // 客户端 / 未加载：只做服务端权威判定
        }
        final ExternalPatternSink.Result result =
            CreateCraftDispatch.tryAccept(level, autocrafter, pattern, resources, action);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
