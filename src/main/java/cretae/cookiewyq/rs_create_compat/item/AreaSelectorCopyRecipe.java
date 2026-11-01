package cretae.cookiewyq.rs_create_compat.item;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * <b>区域选定器复制</b>（第 131 轮，E 组）：一个「已经画好区域（或配过过滤）」的选定器 +
 * 一个空白选定器 ⇒ 得到<b>两个内容完全相同</b>的选定器。
 *
 * <h2>用户原话</h2>
 * <p>「再加一个小功能：<b>区域选定器可以复制</b>（不是物品复制，是里面的 NBT 内容复制）：拿一个
 * 已经标记好的 + 一个空白的，<b>在工作台或玩家背包里合成</b> ⇒ 得到<b>两个包含相同 NBT 的标记物品</b>；
 * 如果两个都带 NBT ⇒ <b>合成不出东西</b>。」</p>
 *
 * <h2>为什么是「无形状 + 恰好两个」而不是普通 shapeless 配方</h2>
 * <p>普通 {@code crafting_shapeless} 的产物是<b>写死在 JSON 里的固定物品</b>（最多带固定组件），
 * 而这里产物必须<b>逐字节复制输入那一份</b>的物品数据（区域几何 + 每区域过滤标记
 * {@code RsccAreaMarks}）。这类「看输入决定输出」的配方在原版就是 {@link CustomRecipe} 家族
 * （地图复制 {@code crafting_special_mapcloning}、旗帜复制、书复制……），本类照同一模式实现。</p>
 *
 * <h2>为什么 getType() 不覆写（继承 {@code RecipeType.CRAFTING}）</h2>
 * <p><b>这是本轮最容易写错的一行</b>：{@code RecipeManager} 按 {@code recipe.getType()} 把配方分桶
 * （{@code builder.put(recipe.getType(), holder)}），而工作台与玩家 2×2 都只查
 * {@code RecipeType.CRAFTING}（{@code CraftingMenu#slotChangedCraftingGrid}，玩家背包界面直接复用
 * 同一个方法）。若这里返回一个自定义 {@code RecipeType}，配方会被分到那个桶里，
 * <b>两个合成入口都永远找不到它</b>（玩家看到的就是「放了两个选定器什么也不出」）。
 * 原版那批 special 配方同样如此：JSON 的 {@code type} 指向自己的序列化器，但 {@code getType()} 仍是
 * {@code crafting}。因此 <b>JSON 里的 {@code type} 只决定用哪个序列化器</b>，本类不覆写 {@code getType()}。</p>
 *
 * <h2>判据（契约 §3.E，逐条对应）</h2>
 * <ul>
 *     <li>非空格子<b>恰好 2 个</b>，且两个都是 {@code rs_create_compat:area_selector}；</li>
 *     <li>其中<b>恰好一个</b>「带区域数据」—— {@link AreaSelection} 非空 <b>或</b>
 *     {@link AreaMarks} 非空（只配过过滤、还没画区域也算「带数据」，否则复制会丢掉过滤）；</li>
 *     <li>两个都带 / 两个都空 / 混入别的物品 / 出现第 3 个选定器 ⇒ {@code matches} 为假，
 *     {@code assemble} 也返回空（双保险：不会「合出两个空白的」）。</li>
 * </ul>
 *
 * <h2>输出为什么是「一个数量 2 的堆」（同原版地图复制）</h2>
 * <p>合成结果槽只有一个格子，原版 {@code crafting_special_mapcloning} 也是把 N 份产物塞进一个
 * {@code copyWithCount(N + 1)} 的堆里，玩家取出后自然拆成 N 份。这里同样用
 * {@link ItemStack#copyWithCount(int)} —— <b>它复制的是整份物品数据</b>（含所有数据组件），
 * 所以两个产物与「那个带区域数据的选定器」内容一模一样，符合用户要的「复制 NBT 内容」；
 * 而 {@code stacksTo(1)} 的选定器最终仍是两个<b>独立</b>实例（玩家放进两个槽位即分开），
 * 与 {@code stacksTo(1)} 不冲突。</p>
 */
public class AreaSelectorCopyRecipe extends CustomRecipe {
    /** 一次合成恰好消耗 / 产出多少个选定器（用户拍板：1 个标记 + 1 个空白 ⇒ 2 个相同标记）。 */
    public static final int SELECTOR_COUNT = 2;

    /**
     * 本配方的序列化器（<b>静态单例</b>）。
     * <p>注册由 {@code RS_Create_Compat} 做（契约 §2：注册文件归 Lead）：
     * {@code RECIPE_SERIALIZERS.register("area_selector_copy", () -> AreaSelectorCopyRecipe.SERIALIZER)}。
     * 放在本类里而不是让注册处 new 一个，是为了「序列化器 ↔ 配方类」这份对应关系只有一处，
     * 否则 {@code getSerializer()} 与注册处各写一个实例，两边漂移时 {@code assemble} 会被当成
     * 另一种配方处理（原版对序列化器采取的是「一个类型一个实例」的约定）。</p>
     */
    public static final RecipeSerializer<AreaSelectorCopyRecipe> SERIALIZER =
        new SimpleCraftingRecipeSerializer<>(AreaSelectorCopyRecipe::new);

    public AreaSelectorCopyRecipe(final CraftingBookCategory category) {
        super(category);
    }

    /**
     * 「这一格能不能合成」：见类注释的判据。
     * <p>{@code level} 只用来拿 {@code registryAccess()} —— {@link AreaMarks#all} 读旧档里
     * 「整份 ItemStack 编码」的标记时<b>需要</b>注册表，否则会把那几条当成读不出而跳过
     * （结果就是「明明配过过滤却算空白的」，见 {@code AreaMarks#all(ItemStack, HolderLookup.Provider)}）。
     * 合成格本就在服务端 / 客户端都有 level，不会为空。</p>
     */
    @Override
    public boolean matches(final CraftingInput input, final Level level) {
        return markedSelector(input, level.registryAccess()) != null;
    }

    /**
     * 产物 = 「那个带区域数据的选定器」整份复制两份。
     * <p>返回 {@code EMPTY} 而不抛异常：原版在「配方被匹配到但装配失败」时一律按空处理，
     * 界面不会因此崩（且 {@link #matches} 已经先挡一遍，这里是第二道保险）。</p>
     */
    @Override
    public ItemStack assemble(final CraftingInput input, final HolderLookup.Provider registries) {
        final ItemStack marked = markedSelector(input, registries);
        if (marked == null) {
            return ItemStack.EMPTY;
        }
        // copyWithCount 复制整份物品数据（数据组件 = 区域几何 + 每区域过滤），只改数量
        return marked.copyWithCount(SELECTOR_COUNT);
    }

    /**
     * 合成格尺寸要求：<b>至少能放下 2 个物品</b>。
     * <p>玩家背包 2×2（{@code width*height = 4}）与工作台 3×3（= 9）都通过；1×1 不通过。
     * 不写死 {@code >= 2 && >= 1} 这种具体形状 —— 背包 2×2 的「两个格子对角线」也应生效，
     * 用户要的是「在工作台或玩家背包里合成」，不是某个特定摆法。</p>
     */
    @Override
    public boolean canCraftInDimensions(final int width, final int height) {
        return width * height >= SELECTOR_COUNT;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    // ==================== 判据实现 ====================

    /**
     * 找出「那个带区域数据的选定器」；判据不成立时返回 {@code null}。
     *
     * <p>一次遍历同时校验四件事（顺序即优先级，任何一条不满足立刻返回 {@code null}）：
     * ① 非空格子只能是选定器（混入任意其它物品 ⇒ 不匹配）；
     * ② 选定器不超过 {@link #SELECTOR_COUNT} 个（第 3 个 ⇒ 不匹配，否则会「吞掉」玩家多放的选定器）；
     * ③ 带区域数据的最多 1 个（两个都带 ⇒ 用户明确要求「合成不出东西」）；
     * ④ 最后必须<b>恰好</b> 2 个选定器（1 个或 0 个都不匹配）。</p>
     *
     * <p>{@code registries} 可空：为空只会漏掉旧档 {@code Item} 编码的标记（那属于读取降级，
     * 不会把「两个都带」误判成「一个带」—— 最坏情况是少认一个标记、给出空产物）。</p>
     */
    @Nullable
    private static ItemStack markedSelector(final CraftingInput input,
                                            @Nullable final HolderLookup.Provider registries) {
        ItemStack marked = null;
        int selectors = 0;
        for (int i = 0; i < input.size(); i++) {
            final ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!stack.is(RS_Create_Compat.AREA_SELECTOR.get())) {
                return null; // ① 混入别的物品：不是本配方的合成格
            }
            selectors++;
            if (selectors > SELECTOR_COUNT) {
                return null; // ② 第 3 个选定器：不能悄悄吞掉
            }
            if (hasAreaData(stack, registries)) {
                if (marked != null) {
                    return null; // ③ 两个都带区域数据 ⇒ 按用户拍板「合成不出东西」
                }
                marked = stack;
            }
        }
        return selectors == SELECTOR_COUNT ? marked : null; // ④ 恰好 2 个 + 恰好 1 个带数据
    }

    /**
     * 「这个选定器带区域数据吗」= 画过区域 <b>或</b> 配过过滤。
     * <p>为什么两条都要看：只画了区域没配过滤的（最常见的「空白过滤」安全默认）当然要算带数据；
     * 反过来「只配了过滤、区域已被删光」的也<b>必须</b>算 —— 那份 {@code RsccAreaMarks} 是玩家手配的
     * 配置，复制时丢掉它等于静默删配置。两个来源与 tooltip（{@code AreaSelectorItem}）同一口径。</p>
     */
    private static boolean hasAreaData(final ItemStack stack,
                                      @Nullable final HolderLookup.Provider registries) {
        return !AreaSelection.get(stack).isEmpty()
            || !AreaMarks.all(stack, registries).isEmpty();
    }
}
