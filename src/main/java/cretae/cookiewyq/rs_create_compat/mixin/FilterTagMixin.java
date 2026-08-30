package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.filter.Filter;
import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
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
 * 调整二：让 RS 过滤器支持两类扩展匹配：
 * <ol>
 *     <li><b>Tag 过滤</b>：过滤槽物品带 {@code rs_create_compat:tag_filter} 组件（如 "#minecraft:stone"）时，
 *     匹配所有属于该 Tag 的资源。</li>
 *     <li><b>Create 过滤器展开</b>：过滤槽中放入机械动力的列表过滤器 / 属性过滤器时，
 *     按过滤器内配置的内容匹配（而非过滤该过滤器物品本身）；若过滤器为空则仍过滤该物品本身。</li>
 * </ol>
 * 输入/输出总线等所有使用 Filter 的方块全部生效。
 */
@Mixin(Filter.class)
public abstract class FilterTagMixin {
    @Shadow
    @Final
    private Set<ResourceKey> filters;

    @Shadow
    private FilterMode mode;

    @Inject(method = "isAllowed", at = @At("HEAD"), cancellable = true)
    private void rscc$checkExpandedFilters(final ResourceKey resource, final CallbackInfoReturnable<Boolean> cir) {
        if (!Config.tagFilterEnabled || !(resource instanceof ItemResource itemResource)) {
            return;
        }
        final ItemStack resourceStack = itemResource.toItemStack();
        boolean matched = false;
        for (final ResourceKey filter : filters) {
            if (!(filter instanceof ItemResource itemFilter)) {
                continue;
            }
            final ItemStack filterStack = itemFilter.toItemStack();

            // 1) Create 过滤器物品：展开其配置内容
            if (filterStack.getItem() instanceof FilterItem && !filterStack.isEmpty()) {
                final FilterItemStack wrapper = FilterItemStack.of(filterStack);
                final boolean isEmptyListFilter = wrapper instanceof FilterItemStack.ListFilterItemStack listFilter
                    && listFilter.containedItems.isEmpty();
                if (!isEmptyListFilter && wrapper.test(null, resourceStack)) {
                    matched = true;
                    break;
                }
                continue; // 空列表过滤器：不展开，按原逻辑过滤物品本身
            }

            // 2) Tag 过滤组件
            final String tag = filterStack.get(RS_Create_Compat.TAG_FILTER);
            if (tag != null && tag.startsWith("#")) {
                final TagKey<Item> tagKey = TagKey.create(Registries.ITEM, ResourceLocation.parse(tag.substring(1)));
                if (itemResource.item().is(tagKey)) {
                    matched = true;
                    break;
                }
            }
        }
        if (matched) {
            cir.setReturnValue(mode == FilterMode.ALLOW);
        }
    }
}
