package cretae.cookiewyq.rs_create_compat.client.tooltip;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 「配方类型 → 代表物品图标」查询（客户端）：单元样板 tooltip 顶部那个<b>轮播图标</b>的图标来源。
 * <p><b>为什么是机器方块</b>：用户明确要求「显示的是机器的图标，就像执行舱那边一样，而不是显示一堆
 * 输入 / 原料」。因此这里改为复用 {@link RecipeTypeMachines}（Create 官方 JEI 催化剂 + 配方自带
 * toast symbol 反查出的「能执行该配方的机器方块」），例：机械手装配 → 机械手。</p>
 * <p>反查不到机器时返回空列表（tooltip 不再退化成显示原料 / 产物）。</p>
 */
public final class RecipeTypeIcons {
    /** 最多展示多少个代表物品。 */
    public static final int MAX_ICONS = 12;

    private RecipeTypeIcons() {
    }

    /**
     * 查询指定配方类型的代表图标（= 能执行它的机器方块，已去重、最多 {@link #MAX_ICONS} 个）。
     *
     * @param recipeTypeId 配方类型 id（如 {@code create:pressing}）
     * @param fallback     不再使用（保留参数仅为兼容既有调用；原料图标不再展示）
     */
    public static List<ItemStack> forRecipeType(final String recipeTypeId, final ItemStack fallback) {
        final List<ItemStack> icons = new ArrayList<>();
        for (final RecipeTypeMachines.Machine machine : RecipeTypeMachines.forRecipeType(recipeTypeId)) {
            if (icons.size() >= MAX_ICONS) {
                break;
            }
            if (!machine.icon().isEmpty()) {
                icons.add(machine.icon().copyWithCount(1));
            }
        }
        return icons;
    }
}
