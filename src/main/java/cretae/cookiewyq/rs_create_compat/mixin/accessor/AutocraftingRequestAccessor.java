package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.autocrafting.preview.AutocraftingRequest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 访问器：暴露 RS 预览请求包私有的三个只读 getter ——
 * 「这次要合成的那一项」（{@code getResource}）、「算好的列表预览」（{@code getPreview}）、
 * 「算好的树预览」（{@code getTreePreview}）。
 *
 * <p>三个方法都<b>声明在 {@link AutocraftingRequest} 自身</b>（本工程硬规则），
 * 且都是包私有，因此必须走 {@code @Invoker}（Mixin 在目标类内部生成调用）——
 * 不能 {@code @Shadow} 到别处，也不能直接调。</p>
 *
 * <p>全部<b>只读</b>：只用来判断「这条预览是不是本模组的序列装配配方」并取过渡件，
 * 不写任何字段、不改服务端状态。</p>
 */
@Mixin(AutocraftingRequest.class)
public interface AutocraftingRequestAccessor {
    @Invoker("getResource")
    ResourceKey rscc$getResource();

    @Invoker("getPreview")
    Preview rscc$getPreview();

    @Invoker("getTreePreview")
    TreePreview rscc$getTreePreview();
}
