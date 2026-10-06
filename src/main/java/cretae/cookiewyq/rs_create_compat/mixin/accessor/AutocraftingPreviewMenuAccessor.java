package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import com.refinedmods.refinedstorage.common.autocrafting.preview.AutocraftingPreviewContainerMenu;
import com.refinedmods.refinedstorage.common.autocrafting.preview.AutocraftingRequest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 访问器：暴露 RS 预览菜单包私有的 {@code getCurrentRequest()}（返回「当前正在预览的那条请求」）。
 *
 * <p>为什么必须是 {@code @Invoker}：该方法<b>声明在 {@link AutocraftingPreviewContainerMenu} 自身</b>
 * （本工程硬规则：只能访问 / 注入目标类自身声明的成员），但它是包私有
 * （{@code com.refinedmods.refinedstorage.common.autocrafting.preview}），外部包既不能直接调，
 * 也不是 {@code protected} 能靠继承拿到。{@code @Invoker} 让 Mixin 在目标类内部生成调用，
 * 于是包私有可见性天然满足（同 {@code ScreenAccessor} 的做法）。</p>
 */
@Mixin(AutocraftingPreviewContainerMenu.class)
public interface AutocraftingPreviewMenuAccessor {
    @Invoker("getCurrentRequest")
    AutocraftingRequest rscc$getCurrentRequest();
}
