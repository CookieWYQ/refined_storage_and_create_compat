package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「这条预览对应的是不是本模组的序列装配配方？是的话，过渡件（中间产物）是哪一件？」</b>
 * —— 纯只读查询，供 RS「自动合成预览」界面的显示层使用。
 *
 * <h2>为什么在客户端算</h2>
 * <p>预览界面（RS 的 {@code AutocraftingPreviewScreen}）只拿到「这次要合成的那一项」
 * （{@code AutocraftingRequest#getResource}）与算好的所需物列表，<b>拿不到样板 / 配方本体</b>。
 * 但配方表在客户端是齐全的（原版会把配方同步给客户端），所以这里按「配方定位链」里
 * 客户端唯一能用的那一环来定位：<b>② 主产物</b> → 找到产出它的那条序列装配配方。</p>
 *
 * <h2>判据（为什么这样最紧）</h2>
 * <ul>
 *     <li>请求项必须是<b>物品</b>，且资源键<b>不带数据组件</b>：本模组生成的 RS 样板，产物恒为
 *     {@code new ItemResource(item, DataComponentPatch.EMPTY)}（见
 *     {@code SequenceAssemblyPatternItem#buildPattern}）。带组件的请求不可能是本模组样板的产物，
 *     直接不显示（宁可少说，也不把别家样板的预览说成我们的）。</li>
 *     <li>命中的必须是<b>配方主产物</b>（{@code resultPool} 第一项，与 {@code getResultItem} /
 *     样板产物同一口径），不看概率副产物 —— 本模组的样板只按主产物生成。</li>
 *     <li>若<b>两条及以上</b>配方产出同一个主产物，无法确定是哪一条 ⇒ 返回空栈、整行不显示
 *     （显示错的过渡件比不显示更糟）。</li>
 * </ul>
 *
 * <p>过渡件取自 Create 配方自己的 {@code getTransitionalItem()}，与执行仓、样板终端同一口径，
 * 全工程只有一个答案，不靠物品名猜。整个过程<b>不写任何状态</b>。</p>
 */
public final class PreviewIntermediateHint {
    private PreviewIntermediateHint() {
    }

    /**
     * 「这次要合成的这一项」对应的序列装配配方的过渡件（中间产物）；
     * 不是本模组的配方 / 分不清是哪一条 / 配方系统未就绪 ⇒ <b>空栈</b>（调用方什么都不显示）。
     */
    public static ItemStack transitionalFor(@Nullable final Level level, @Nullable final ResourceKey requested) {
        if (level == null || !(requested instanceof ItemResource itemResource)) {
            return ItemStack.EMPTY; // 流体 / 非物品：序列装配只处理物品，没有中间产物这回事
        }
        if (!itemResource.components().isEmpty()) {
            return ItemStack.EMPTY; // 本模组样板的产物恒为裸物品键（见类注释）
        }
        SequencedAssemblyRecipe hit = null;
        try {
            for (final RecipeHolder<?> holder
                : level.getRecipeManager().getAllRecipesFor(AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())) {
                if (!(holder.value() instanceof SequencedAssemblyRecipe assembly)) {
                    continue;
                }
                final List<ProcessingOutput> pool = assembly.resultPool;
                if (pool.isEmpty()) {
                    continue;
                }
                final ItemStack main = pool.getFirst().getStack();
                if (main.isEmpty() || main.getItem() != itemResource.item()) {
                    continue;
                }
                if (hit != null) {
                    return ItemStack.EMPTY; // 多条配方产出同一主产物 ⇒ 分不清，不显示
                }
                hit = assembly;
            }
        } catch (final RuntimeException ignored) {
            // 配方系统未就绪 / 数据包异常：按「不是本模组的配方」处理。
            // 这是每帧渲染路径，绝不往外抛（抛出去就是每帧崩一次客户端）。
            return ItemStack.EMPTY;
        }
        if (hit == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack transitional = hit.getTransitionalItem();
        return transitional == null || transitional.isEmpty() ? ItemStack.EMPTY : transitional.copyWithCount(1);
    }
}
