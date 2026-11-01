package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.DecompositionGeneratorSpec;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.network.chat.Component;

/**
 * JEI「降解发电」分类页（第 129 轮）：<b>放什么 → 发多少电 → 耗多久 → 掉什么</b>。
 *
 * <h2>这一页回答的就是用户那句话</h2>
 * <p>「查看的用什么东西可以发多少电消耗多久」—— 所以布局按这句话排：</p>
 * <pre>
 * +------------------------------------------------------------------+  WIDTH = 200
 * | [输入]  ->  发电：50 FE                                            |  y = 6
 * |         ->  耗时：20 刻（1.0 秒）                                  |  y = 17
 * |         ->  平均：2.5 FE/t                                         |  y = 28
 * | 副产物：                                                           |  y = 42
 * | [槽] [槽] [槽] [槽]                                                 |  y = 52
 * +------------------------------------------------------------------+  HEIGHT = 72
 * </pre>
 * <p>输入只有<b>一格</b>：tag 条目（「任意树叶」）的整组候选由 JEI 在这一格里轮播，不铺成多格 ——
 * 铺开就会撞到右边的箭头与文字列（像素示意图第一版就是这么撞的）。</p>
 *
 * <h2>为什么发电量 / 耗时是「文字」而不是「输出槽」</h2>
 * <p>这台机器的产物是 <b>FE</b>，JEI 没有「能量」这一种 ingredient（原版 / RS 都没有注册过）。
 * 拿一个假物品当"能量图标"会让玩家以为能把它拿出来。因此能量与耗时一律画成文字，
 * 副产物才是真正的输出槽（它确实是物品）。这与多数发电机类模组的 JEI 页做法一致，
 * 也避免往 JEI 注册表里塞自定义 ingredient 类型（那会牵连所有整合包的 JEI 搜索）。</p>
 *
 * <h2>数值口径：只有一份真相</h2>
 * <p>所有数字都来自 {@link DecompositionJeiRecipe}（它又是服务端下发的数据包原值），
 * 本类<b>不做任何换算</b>：不给「装了速度升级之后」的假想耗时 —— 那会变成第二天真相，
 * 数据包一改就对不上。升级对数值的影响写在悬停箭头时的 tooltip 里（纯定性描述）。</p>
 *
 * <h2>为什么有几处 {@code DecompositionGeneratorSpec} 引用</h2>
 * <p>箭头尺寸/位置与机器界面同源（{@link DecompositionGeneratorSpec#ARROW_WIDTH} 等）：
 * JEI 页的箭头与 GUI 里那个熔炉箭头保持同一观感，且以后改 GUI 箭头尺寸时这里不会静默错位。
 * 其余布局（文字列、副产物格）是 JEI 页自己的几何，不硬套 GUI 面板尺寸。</p>
 */
public final class DecompositionGeneratorJeiCategory extends AbstractRecipeCategory<DecompositionJeiRecipe> {
    // ==================== 页面几何（JEI 页自己的坐标，与 GUI 面板无关） ====================
    /**
     * 页面宽。
     *
     * <p>200 是量出来的，不是拍的：本页最长的一行是<b>英文</b>的
     * {@code Time: 180 ticks (9.0 s)}（≈130px）与 {@code Power: 100,000,000 FE}（≈124px）。
     * 文字列从 {@link #TEXT_X} = 56 起算，再留 2px 边距 ⇒ 可用宽度
     * {@code 200 - 56 - 2 = 142}，两者都装得下。<b>不要再缩回 180</b>：那会让英文耗时行
     * 被 JEI 截成省略号（第 129 轮像素示意图第二版就是在 180 宽下发现的）。</p>
     *
     * <p>数据包把 {@code time} 写到上限（12000 刻 = 600 秒）时，英文行仍会超宽 ——
     * 那时 JEI 会截断并在 tooltip 里给全文，属于极端自定义数据的可接受降级。</p>
     */
    public static final int WIDTH = 200;
    /** 页面高：三行文字 + 一行「副产物」标签 + 一行副产物槽（最后一格槽底到 52+18=70）。 */
    public static final int HEIGHT = 72;

    /** 输入槽左上角（JEI 槽背景会自动 -1，因此这里写的就是「物品图标」的位置）。 */
    private static final int INPUT_X = 2;
    private static final int INPUT_Y = 23;

    /** 箭头（复用原版配方箭头的尺寸，与原版 GUI 观感一致）。 */
    private static final int ARROW_X = 26;
    private static final int ARROW_Y = 25;

    /** 文字列左边界（箭头右侧留 6px）。 */
    private static final int TEXT_X = 56;
    /** 三行文字的 y（行距 11 = 9 像素字高 + 2）。 */
    private static final int TEXT_ENERGY_Y = 6;
    private static final int TEXT_TIME_Y = 17;
    private static final int TEXT_RATE_Y = 28;
    /** 文字可用宽度（到页面右边再留 2px）。 */
    private static final int TEXT_WIDTH = WIDTH - TEXT_X - 2;

    /**
     * 「副产物：」标签所在行。
     *
     * <p><b>为什么标签单独占一行、不跟槽挤在同一行</b>（第 129 轮像素示意图当场抓到的）：
     * 中文标签「副产物：」= 4 个全角字 ≈ 36px，英文 {@code Byproducts:} ≈ 60px —— 一旦标签与第一格槽
     * 同排，要么中文留 2px、要么英文直接压到槽上（en_us 是必须支持的，不能只算中文）。
     * 标签独占一行后，最后一行的槽从 {@link #BYPRODUCT_X} 起算，与语言无关。</p>
     */
    private static final int BYPRODUCT_LABEL_X = 2;
    private static final int BYPRODUCT_LABEL_Y = 42;
    /** 副产物格起点 / 间距 / 最多显示几个（再多就用一行文字说「另有 N 项」）。 */
    private static final int BYPRODUCT_X = 2;
    private static final int BYPRODUCT_Y = 52;
    private static final int BYPRODUCT_STEP = 19;
    private static final int BYPRODUCT_MAX = 4;
    /** 「另有 N 项」文字的 x：与标签同一行、放在 4 个槽的宽度右侧（不与槽重叠）。 */
    private static final int BYPRODUCT_MORE_X = BYPRODUCT_X + BYPRODUCT_STEP * BYPRODUCT_MAX + 4;

    /** 正文颜色（与原版容器标题同色系，JEI 默认背景是浅色）。 */
    private static final int TEXT_COLOR = 0xFF404040;

    // ==================== 语言键（全部在 zh_cn / en_us 里成对存在） ====================
    private static final String KEY_ENERGY = "jei.rs_create_compat.decomposition_generator.energy";
    private static final String KEY_TIME = "jei.rs_create_compat.decomposition_generator.time";
    private static final String KEY_RATE = "jei.rs_create_compat.decomposition_generator.rate";
    private static final String KEY_BYPRODUCT = "jei.rs_create_compat.decomposition_generator.byproduct";
    private static final String KEY_NO_BYPRODUCT = "jei.rs_create_compat.decomposition_generator.no_byproduct";
    private static final String KEY_MORE_BYPRODUCT = "jei.rs_create_compat.decomposition_generator.more_byproduct";
    private static final String KEY_CHANCE = "jei.rs_create_compat.decomposition_generator.chance";
    private static final String KEY_CHANCE_ALWAYS =
        "jei.rs_create_compat.decomposition_generator.chance_always";
    private static final String KEY_ARROW_TIP_SPEED =
        "jei.rs_create_compat.decomposition_generator.arrow_tip_speed";
    private static final String KEY_ARROW_TIP_STACK =
        "jei.rs_create_compat.decomposition_generator.arrow_tip_stack";

    public DecompositionGeneratorJeiCategory(final RecipeType<DecompositionJeiRecipe> recipeType,
                                             final Component title, final IDrawable icon) {
        super(recipeType, title, icon, WIDTH, HEIGHT);
    }

    /** 配方 id：JEI 的书签 / 复制功能靠它；同一 key 必须永远得到同一个 id（见 {@link DecompositionJeiRecipe#idOf}）。 */
    @Override
    public net.minecraft.resources.ResourceLocation getRegistryName(final DecompositionJeiRecipe recipe) {
        return recipe.id();
    }

    // ==================== 配方布局（JEI 做「物品 → 配方」反查靠这里） ====================

    @Override
    public void setRecipe(final IRecipeLayoutBuilder builder, final DecompositionJeiRecipe recipe,
                          final IFocusGroup focuses) {
        // ① 输入：这一条适用的全部物品（tag 条目 = 整组候选，JEI 会轮播显示）
        builder.addInputSlot(INPUT_X, INPUT_Y)
            .addItemStacks(recipe.inputs())
            .setStandardSlotBackground();
        // ② 副产物：真正的输出槽 —— 物品确实会掉出来，所以它是 slot 而不是文字
        int index = 0;
        for (final DecompositionJeiRecipe.Byproduct byproduct : recipe.byproducts()) {
            if (index >= BYPRODUCT_MAX) {
                break;
            }
            final int x = BYPRODUCT_X + BYPRODUCT_STEP * index;
            final IRecipeSlotBuilder slot = builder.addOutputSlot(x, BYPRODUCT_Y)
                .addItemStack(byproduct.stack())
                .setStandardSlotBackground();
            // 概率写在槽的 tooltip 上：100% 说「必定掉落」，其余给百分比（独立掷骰，可能一个都不出）
            final Component chanceLine = byproduct.chancePercent() >= 100
                ? Component.translatable(KEY_CHANCE_ALWAYS)
                : Component.translatable(KEY_CHANCE, byproduct.chancePercent());
            slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(chanceLine));
            index++;
        }
    }

    // ==================== 文字 / 箭头（每条配方各自的值） ====================

    @Override
    public void createRecipeExtras(final IRecipeExtrasBuilder builder, final DecompositionJeiRecipe recipe,
                                   final IFocusGroup focuses) {
        // 箭头：原版配方箭头（与机器 GUI 里那个熔炉箭头同观感、同位置关系）
        builder.addRecipeArrow().setPosition(ARROW_X, ARROW_Y);
        builder.addText(Component.translatable(KEY_ENERGY, RsccJeiText.grouped(recipe.energy())),
                TEXT_WIDTH, 10)
            .setPosition(TEXT_X, TEXT_ENERGY_Y)
            .setColor(TEXT_COLOR);
        builder.addText(Component.translatable(KEY_TIME, recipe.time(), recipe.secondsText()),
                TEXT_WIDTH, 10)
            .setPosition(TEXT_X, TEXT_TIME_Y)
            .setColor(TEXT_COLOR);
        builder.addText(Component.translatable(KEY_RATE, RsccJeiText.rate(recipe.energyPerTick())),
                TEXT_WIDTH, 10)
            .setPosition(TEXT_X, TEXT_RATE_Y)
            .setColor(TEXT_COLOR);
        if (recipe.byproducts().isEmpty()) {
            // 这一行是整页最长的候选之一（英文 "No byproducts (power only)" ≈150px），
            // 因此可用宽度按**页面宽**算，不按文字列宽（否则会被 JEI 截成省略号）。
            builder.addText(Component.translatable(KEY_NO_BYPRODUCT), WIDTH - BYPRODUCT_LABEL_X - 2, 10)
                .setPosition(BYPRODUCT_LABEL_X, BYPRODUCT_LABEL_Y)
                .setColor(TEXT_COLOR);
            return;
        }
        // 「副产物：」标签：可用宽度同样按页面宽给（中文 36px / 英文 60px，都不该被截）
        builder.addText(Component.translatable(KEY_BYPRODUCT), WIDTH - BYPRODUCT_LABEL_X - 2, 10)
            .setPosition(BYPRODUCT_LABEL_X, BYPRODUCT_LABEL_Y)
            .setColor(TEXT_COLOR);
        final int hidden = recipe.byproducts().size() - BYPRODUCT_MAX;
        if (hidden > 0) {
            builder.addText(Component.translatable(KEY_MORE_BYPRODUCT, hidden),
                    WIDTH - BYPRODUCT_MORE_X - 2, 10)
                .setPosition(BYPRODUCT_MORE_X, BYPRODUCT_LABEL_Y)
                .setColor(TEXT_COLOR);
        }
    }

    // ==================== 悬停箭头：升级怎么影响数值（纯定性） ====================

    @Override
    public void getTooltip(final ITooltipBuilder tooltip, final DecompositionJeiRecipe recipe,
                           final IRecipeSlotsView recipeSlotsView, final double mouseX, final double mouseY) {
        if (mouseX < ARROW_X || mouseX >= ARROW_X + DecompositionGeneratorSpec.ARROW_WIDTH
            || mouseY < ARROW_Y || mouseY >= ARROW_Y + DecompositionGeneratorSpec.ARROW_HEIGHT) {
            return;
        }
        tooltip.add(Component.translatable(KEY_ARROW_TIP_SPEED));
        tooltip.add(Component.translatable(KEY_ARROW_TIP_STACK));
    }
}
