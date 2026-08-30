package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.filter.Filter;
import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 调整二：让 RS 过滤器支持 Tag 过滤。
 * 当过滤槽中的物品带有 {@code rs_create_compat:tag_filter} 数据组件（值为如 "#minecraft:stone"）时，
 * 该过滤器会匹配所有属于该 Tag 的资源（输入/输出总线等使用 Filter 的方块全部生效）。
 */
@Mixin(Filter.class)
public abstract class FilterTagMixin {
    @Shadow
    @Final
    private Set<ResourceKey> filters;

    @Shadow
    private FilterMode mode;

    @Inject(method = "isAllowed", at = @At("HEAD"), cancellable = true)
    private void rscc$checkTagFilters(final ResourceKey resource, final CallbackInfoReturnable<Boolean> cir) {
        if (!Config.tagFilterEnabled) {
            return;
        }
        boolean matchedTag = false;
        for (final ResourceKey filter : filters) {
            if (filter instanceof ItemResource itemFilter) {
                final ItemStack stack = itemFilter.toItemStack();
                final String tag = stack.get(RS_Create_Compat.TAG_FILTER);
                if (tag != null && tag.startsWith("#") && resource instanceof ItemResource itemResource) {
                    final TagKey<Item> tagKey = TagKey.create(
                        Registries.ITEM,
                        ResourceLocation.parse(tag.substring(1))
                    );
                    if (itemResource.item().is(tagKey)) {
                        matchedTag = true;
                        break;
                    }
                }
            }
        }
        if (matchedTag) {
            cir.setReturnValue(mode == FilterMode.ALLOW);
        }
    }
}
