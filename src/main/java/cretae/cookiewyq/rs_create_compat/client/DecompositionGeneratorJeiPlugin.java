package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.DecompositionGeneratorScreen;
import cretae.cookiewyq.rs_create_compat.support.DecompositionGeneratorSpec;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGuiClickableArea;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;

/**
 * JEI 集成（第 129 轮）：给「降解室发电机」加一个**真正的配方分类**——
 * 「放什么 → 发多少电 → 耗多久 → 掉什么」，并且<b>点机器界面中间那个箭头就跳过来</b>。
 *
 * <h2>为什么这不是「又一个 ghost handler」</h2>
 * <p>本工程此前 4 个 {@code @JeiPlugin} 都只做两件事：拖物品进槽（ghost）、在 Create 配方上放
 * 「+」（transfer）。它们<b>没有注册过任何分类</b>。而用户这次要的是「像熔炉那样，点箭头能看到
 * 这个机器能吃什么、发多少电、要多久」—— 那就必须注册一个 {@link RecipeType} + {@code IRecipeCategory}，
 * 否则 JEI 里根本没有这一页可以"跳过去"。</p>
 *
 * <h2>箭头 → JEI 的链路（与熔炉同语义，但更准一点）</h2>
 * <ol>
 *     <li>在 {@link #registerGuiHandlers} 里给 {@link DecompositionGeneratorScreen} 挂一个
 *     {@link IGuiContainerHandler}，它声明的可点区域 = {@link DecompositionGeneratorSpec} 里那个
 *     熔炉箭头（<b>坐标与机器界面同源</b>，不在这里重写 86/45 这类数字）；</li>
 *     <li>点下去时：机器输入格里<b>当前有东西</b>就把 JEI 直接定位到那一件物品的降解配方
 *     （{@code IRecipesGui#showRecipes}）；输入格空着就打开整个分类
 *     （{@code IRecipesGui#showTypes}）—— 熔炉箭头只能做到后者，这里两种都覆盖；</li>
 *     <li>可点区域<b>不显示 JEI 的默认 tooltip</b>（{@link IGuiClickableArea#isTooltipEnabled()} = false）：
 *     机器界面自己已经在箭头位置画了「进度 + 点击提示」的 tooltip，两个框叠在一起会很难看。
 *     「能不能点」由界面自己的 tooltip 说明。</li>
 * </ol>
 *
 * <h2>数据源</h2>
 * <p>{@link DecompositionJeiRecipe#all()} 读的是服务端下发的镜像
 * （{@code support/DecompositionDisplayCache}）—— 专用服务器上也有数据，不是只有单人能看。
 * 详见 {@code network/SyncDecompositionDisplayPacket} 的类注释。</p>
 *
 * <h2>为什么本类可以直接 import JEI 类型</h2>
 * <p>本类只被 JEI 加载（{@code @JeiPlugin}），没有 JEI 时它根本不会被加载；机器界面 / 主类
 * <b>不</b>引用它。JEI 自始至终是本工程的可选依赖（见 {@code build.gradle}），这条边界不能破
 * （反例：{@code client/tooltip/JeiCatalystMachines} 之所以全用反射，就是因为它会被无 JEI 的主路径调用）。</p>
 */
@JeiPlugin
public final class DecompositionGeneratorJeiPlugin implements IModPlugin {
    /** 分类的配方类型 id（也会出现在书签 / 复制的 id 里，改它等于让旧书签失效）。 */
    public static final ResourceLocation RECIPE_TYPE_ID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "decomposition_generator");

    /** 配方类型：{@code T} 是本工程自己的展示 record（不是原版 Recipe），因此用 {@code RecipeType.create}。 */
    public static final RecipeType<DecompositionJeiRecipe> RECIPE_TYPE =
        RecipeType.create(RECIPE_TYPE_ID.getNamespace(), RECIPE_TYPE_ID.getPath(),
            DecompositionJeiRecipe.class);

    /** 分类标题键（zh_cn / en_us 各一份，见 lang 文件）。 */
    private static final String KEY_TITLE = "jei.rs_create_compat.decomposition_generator.title";
    private static final String KEY_INFO_ENERGY = "jei.rs_create_compat.decomposition_generator.info_energy";
    private static final String KEY_INFO_TIME = "jei.rs_create_compat.decomposition_generator.info_time";
    private static final String KEY_INFO_UPGRADE = "jei.rs_create_compat.decomposition_generator.info_upgrade";

    /**
     * 已注册的分类实例（点击处理体要用它调 {@code showRecipes}）。
     *
     * <p>只由 JEI 在客户端主线程写一次（{@link #registerCategories}），之后只读；
     * 因此不需要任何同步手段。</p>
     */
    private static volatile DecompositionGeneratorJeiCategory category;
    /** 当前这一轮注册的配方列表（点击时按物品找对应配方；同上，只在注册时写一次）。 */
    private static volatile List<DecompositionJeiRecipe> registeredRecipes = List.of();

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "decomposition_generator_jei");
    }

    @Override
    public void registerCategories(final IRecipeCategoryRegistration registration) {
        final DecompositionGeneratorJeiCategory created = new DecompositionGeneratorJeiCategory(
            RECIPE_TYPE,
            Component.translatable(KEY_TITLE),
            registration.getJeiHelpers().getGuiHelper()
                .createDrawableItemStack(new ItemStack(RS_Create_Compat.DECOMPOSITION_GENERATOR_ITEM.get())));
        category = created;
        registration.addRecipeCategories(created);
    }

    @Override
    public void registerRecipes(final IRecipeRegistration registration) {
        final List<DecompositionJeiRecipe> recipes = DecompositionJeiRecipe.all();
        registeredRecipes = recipes;
        registration.addRecipes(RECIPE_TYPE, recipes);
        // 机器物品自己的「信息页」：说明三条最容易误解的规则（发的是电、速度升级不改发电量、缓存满了会暂停）。
        registration.addIngredientInfo(new ItemStack(RS_Create_Compat.DECOMPOSITION_GENERATOR_ITEM.get()),
            VanillaTypes.ITEM_STACK,
            Component.translatable(KEY_INFO_ENERGY),
            Component.translatable(KEY_INFO_TIME),
            Component.translatable(KEY_INFO_UPGRADE));
    }

    @Override
    public void registerRecipeCatalysts(final IRecipeCatalystRegistration registration) {
        // 让「降解室发电机」这一个物品在 JEI 里被认成这一分类的机器：
        // 物品页多一个分类页签，玩家也能从物品反查「它吃什么」。
        registration.addRecipeCatalyst(new ItemStack(RS_Create_Compat.DECOMPOSITION_GENERATOR_ITEM.get()),
            RECIPE_TYPE);
    }

    @Override
    public void registerGuiHandlers(final IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(DecompositionGeneratorScreen.class, new GeneratorClickHandler());
    }

    // ==================== 点击箭头（客户端主线程，JEI 保证） ====================

    /**
     * 「机器界面里可点的配方区域」——只有那个熔炉箭头。
     *
     * <p>本类只属于点击链路，因此放在插件里（只有 JEI 在场时才会被加载）。</p>
     */
    private static final class GeneratorClickHandler
        implements IGuiContainerHandler<DecompositionGeneratorScreen> {

        @Override
        public Collection<IGuiClickableArea> getGuiClickableAreas(final DecompositionGeneratorScreen screen,
                                                                  final double guiMouseX,
                                                                  final double guiMouseY) {
            return List.of(new IGuiClickableArea() {
                @Override
                public Rect2i getArea() {
                    // 与机器界面画箭头用的是同一份常量（Spec），不在这里另写一套数字。
                    return new Rect2i(DecompositionGeneratorSpec.ARROW_X,
                        DecompositionGeneratorSpec.ARROW_Y,
                        DecompositionGeneratorSpec.ARROW_WIDTH,
                        DecompositionGeneratorSpec.ARROW_HEIGHT);
                }

                @Override
                public boolean isTooltipEnabled() {
                    // 界面自己画进度 tooltip（含「点击查看」提示）；这里关掉，免得两个 tooltip 叠一起。
                    return false;
                }

                @Override
                public void onClick(final IFocusFactory focusFactory, final IRecipesGui recipesGui) {
                    openRecipes(screen, focusFactory, recipesGui);
                }
            });
        }
    }

    /**
     * 点箭头之后：机器输入格里有东西 → 直接定位到那一件的配方；否则打开整个分类。
     *
     * <p>「哪一格正在被降解」客户端并不知道（方块实体没把这个下标同步给界面），因此这里取
     * <b>第一个能找到配方的输入格</b> —— 对玩家来说等价于「这台上现在放着的这东西」，
     * 而且比「随便拿第一格」稳：输入格里混着不可降解物时也不会跳到一个空页面。</p>
     */
    private static void openRecipes(final DecompositionGeneratorScreen screen,
                                    final IFocusFactory focusFactory, final IRecipesGui recipesGui) {
        final DecompositionGeneratorJeiCategory currentCategory = category;
        if (currentCategory == null) {
            return; // 分类还没注册（JEI 被禁用 / 尚未初始化）：什么也不做
        }
        final ItemStack target = currentInput(screen);
        final DecompositionJeiRecipe match = findRecipe(target);
        if (match == null) {
            // 输入格空着 / 放的东西不在表里：打开整个分类（与熔炉箭头一致的行为）
            recipesGui.showTypes(List.of(RECIPE_TYPE));
            return;
        }
        recipesGui.showRecipes(currentCategory, List.of(match),
            List.of(focusFactory.createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, target)));
    }

    /** 找出「第一个有对应配方的输入格物品」（找不到返回 {@link ItemStack#EMPTY}）。 */
    private static ItemStack currentInput(final DecompositionGeneratorScreen screen) {
        ItemStack firstNonEmpty = ItemStack.EMPTY;
        for (int index = 0; index < DecompositionGeneratorSpec.INPUT_COUNT; index++) {
            final ItemStack stack = screen.getMenu().getSlot(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (findRecipe(stack) != null) {
                return stack;
            }
            if (firstNonEmpty.isEmpty()) {
                firstNonEmpty = stack;
            }
        }
        // 全都没有配方：仍然把第一件非空物品回报出去（调用方 findRecipe 会得到 null → 打开整个分类）
        return firstNonEmpty;
    }

    /** 在这批已注册配方里找「适用于这个物品」的那一条。 */
    private static DecompositionJeiRecipe findRecipe(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (final DecompositionJeiRecipe recipe : registeredRecipes) {
            if (recipe.matches(stack)) {
                return recipe;
            }
        }
        return null;
    }
}
