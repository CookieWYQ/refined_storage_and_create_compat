package cretae.cookiewyq.rs_create_compat.support;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Create 序列装配配方的<b>只读探针</b>：把配方里真正需要的信息按本模组的口径提取出来，
 * 供三条导入路径（终端「导入配方」按钮 / JEI「+」/ JEI 预览）共用，避免各处各写一份走偏。
 *
 * <p>它解决三个历史问题：</p>
 * <ol>
 *     <li><b>配方类型必须用注册 id</b>：执行仓的「配方类型」是从已注册配方类型里选的
 *     （{@code create:pressing} 这类注册 id）。早期实现返回 {@code "Pressing"} /
 *     {@code "DeployerApplication"} 这类手写短名，与执行仓存的注册 id 永远不相等，
 *     于是玩家明明连了机器仍显示「无可用机器」。这里统一从
 *     {@link BuiltInRegistries#RECIPE_TYPE} 取真实 id。</li>
 *     <li><b>步骤输入原料不是第 0 个 ingredient</b>：Create 的
 *     {@code SequencedRecipe#initFromSequencedAssembly} 会把<b>第 0 个 ingredient 覆盖成
 *     「过渡件」</b>（第一遍还额外并入主原料），真正消耗的应用物在<b>第 1 个</b>起。
 *     旧代码取 {@code getIngredients().get(0)}，于是每个「机械手装配」步读到的都是
 *     “未完成的精密构件”，三步输入完全相同 → 被错误合并成一步，缺料提示也变成
 *     “缺少 15 个未完成的精密构件”。这里跳过第 0 个 ingredient，取其后第一个非空物品。</li>
 *     <li><b>resultPool 的 chance 是「权重」而非概率</b>：Create 的
 *     {@code SequencedAssemblyRecipe#rollResult} 先求权重总和、再按权重轮盘抽取，
 *     所以 {@code chance} 常见值是 120 / 8 / 5 …（远大于 1）。旧代码直接
 *     {@code clamp(chance)} 后判断 {@code >= 1}，等于把所有项都当成 100% 产物。
 *     这里<b>完全照抄 Create 自己 JEI 分类的换算</b>（见
 *     {@code com.simibubi.create.compat.jei.category.SequencedAssemblyCategory} 第 189-194 行
 *     的 {@code getOutputChance()}）：<b>任一项的概率 = 该项权重 / 全部项权重之和</b>，
 *     第一项作为「主产物」、其余作为废料，但概率一律用同一个归一公式（与 JEI 数字一致）。</li>
 * </ol>
 */
public final class SequencedRecipeProbe {
    private SequencedRecipeProbe() {
    }

    /**
     * 「概率 → 显示数字」换算（在 Create JEI 的换算法上做了一处统一）：
     * <pre>
     *   chance &gt;= 1   → "100"（必得产物：与「产物/废料配置」子界面显示的 100% 一致，绝不显示 "&gt;99"）
     *   chance &lt; 0.01 → "&lt;1"
     *   chance &gt; 0.99 → "&gt;99"
     *   否则          → Math.round(chance * 100)
     * </pre>
     * 显示小字与 tooltip 两条路径共用本方法，保证同一格上的百分比完全一致。
     */
    public static String chanceNumber(final float chance) {
        if (chance >= 1.0F) {
            // 必得产物（含用户配置的 100%）：配置界面写的就是 100%，标记处也必须显示 100%
            return "100";
        }
        if (chance < 0.01F) {
            return "<1";
        }
        if (chance > 0.99F) {
            return ">99";
        }
        return String.valueOf(Math.round(chance * 100));
    }

    /**
     * Create JEI 的「概率 → 成品文案」换算：与 JEI tooltip 完全同一串（含 Create 自带的语言键
     * {@code create.recipe.processing.chance} 与金色样式，如 zh_cn 的「%1$s%% 概率」）。
     */
    public static net.minecraft.network.chat.Component chanceComponent(final float chance) {
        return net.minecraft.network.chat.Component.translatable(
                "create.recipe.processing.chance", chanceNumber(chance))
            .withStyle(net.minecraft.ChatFormatting.GOLD);
    }

    /** 归一概率：Create 的 {@code getOutputChance()} 对任意项的推广（无权重时回退 0）。 */
    public static float probability(final float weight, final float totalWeight) {
        if (totalWeight <= 0F) {
            return 0F;
        }
        return Math.min(1F, Math.max(0F, weight) / totalWeight);
    }

    /** 该项处理配方的<b>注册配方类型 id</b>（如 {@code create:deploying}）；取不到时回退可读短名。 */
    public static String recipeTypeId(final ProcessingRecipe<?, ?> recipe) {
        if (recipe == null) {
            return "";
        }
        try {
            final RecipeType<?> type = recipe.getType();
            final ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (key != null) {
                return key.toString();
            }
        } catch (final RuntimeException ignored) {
            // 类型未注册（版本差异）：落到类名兜底
        }
        final String simple = recipe.getClass().getSimpleName().replace("Recipe", "");
        return simple.toLowerCase(Locale.ROOT);
    }

    /**
     * 该步真正消耗的输入原料：跳过第 0 个 ingredient（Create 用它承载过渡件），
     * 取其后第一个「能取到物品」的 ingredient 的首个物品；没有则返回空栈。
     */
    public static ItemStack stepInput(final ProcessingRecipe<?, ?> recipe) {
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        final NonNullList<Ingredient> ingredients = recipe.getIngredients();
        // 从下标 1 开始：下标 0 一定被 initFromSequencedAssembly 覆盖为过渡件（或过渡件+主原料的复合）
        for (int i = 1; i < ingredients.size(); i++) {
            final ItemStack[] items = ingredients.get(i).getItems();
            if (items.length > 0 && !items[0].isEmpty()) {
                return items[0].copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * <b>一个 ingredient 的全部候选物品</b>（标签 / 多值列表展开；按注册顺序去重，绝不含空栈）。
     *
     * <h2>为什么必须有它（用户第 1 条：标签型输入原料）</h2>
     * <p>列车轨道的起步原料是标签 {@code create:sleepers}（{@code minecraft:stone_slab} /
     * {@code smooth_stone_slab} / {@code andesite_slab}），某个机械手步的投入物是
     * {@code [c:nuggets/iron, c:nuggets/zinc]}（铁粒<b>或</b>锌粒）。旧实现一律取
     * {@code getItems()[0]} 当「代表物」，于是服务端只认第一种候选：其它候选到了机器旁会被判成
     * 「不是本步的投入物」而推不进去 / 被收回，备料也只拉代表物 —— 用户原话
     * 「它不止可以使用石头台阶，还可以用平滑石台阶、安山岩台阶……这几种东西都能够正常使用」。
     * 这里把整组候选读出来，服务端的「要什么 / 拉什么 / 认什么」一律按<b>候选集合</b>匹配。</p>
     *
     * <p>只读；{@code getItems()} 异常（自定义 Ingredient 实现不完整）时按空表处理，绝不抛。</p>
     */
    public static List<Item> candidates(@org.jetbrains.annotations.Nullable final Ingredient ingredient) {
        final List<Item> result = new ArrayList<>();
        if (ingredient == null) {
            return result;
        }
        try {
            for (final ItemStack stack : ingredient.getItems()) {
                if (stack.isEmpty()) {
                    continue;
                }
                if (!result.contains(stack.getItem())) {
                    result.add(stack.getItem());
                }
            }
        } catch (final RuntimeException ignored) {
            // 自定义 Ingredient 抛异常：按「读不出来」处理（调用方照旧用代表物兜底）
        }
        return result;
    }

    /**
     * 一个「输入原料组」：{@link #representative()}（首个候选 —— 类别 id / 图标 / 每批所需量都用它）
     * + {@link #candidates()}（该 ingredient 的<b>全部</b>候选物品）。
     *
     * <p>为什么保留「组」这个概念：备料 / 拉取必须把整组当成<b>一个</b>原料（任一候选到位即可，
     * 不能把标签的 3 个候选当成 3 份需求各拉一份），而匹配又必须认整组 ——
     * 因此「组的边界（哪个 ingredient）」与「组内候选」必须同时保留。</p>
     */
    public record InputGroup(ItemStack representative, List<Item> candidates) {
    }

    /**
     * 「输出总线类别」专用的输入原料<b>分组</b>提取：<b>不遗漏第 0 个 ingredient 里的主原料</b>，
     * 且每个 ingredient 各成一组（组内是它的全部候选）。
     *
     * <p>与 {@link #stepInputItems}（SPT 缺料提示用，故意跳过下标 0）不同，这里对下标 0 做「剔除过渡件」处理：
     * Create 的 {@code SequencedRecipe#initFromSequencedAssembly} 会把第 0 个 ingredient 覆盖成
     * 过渡件（<b>第 0 步还会额外并入总样板的主原料</b>，是 {@code CompoundIngredient}），
     * 因此下标 0 的 {@code getItems()} 里除了过渡件往往还带着真正要喂进机器的主原料。
     * 输出总线的「输入原料」类别必须把这份主原料也算上，否则冲压（{@code create:pressing}）
     * 这类只有 1 个 ingredient 的步骤会一个输入类别都没有 —— 用户实测反馈「只看得见中间产物」。</p>
     *
     * <p><b>口径</b>：下标 0 → 候选 = 去掉过渡件之后的全部物品（空则不出组）；
     * 下标 ≥1 → 每个 ingredient 一组，候选 = 该 ingredient 的全部物品（{@code getItems()}）。
     * 代表物 = 该组第一个候选。代表物相同的组<b>合并候选</b>（同一物品被两条 ingredient 要求时只有一组）。
     * 配方异常一律返回空列表，绝不抛异常。</p>
     *
     * @param recipe             该步的处理配方（{@code SequencedRecipe#getRecipe()}）
     * @param transitionalItem   该配方所在序列装配的过渡件物品（{@code null} = 不剔除任何东西）
     */
    public static List<InputGroup> assemblyStepInputGroups(final ProcessingRecipe<?, ?> recipe,
                                                          final Item transitionalItem) {
        if (recipe == null) {
            return List.of();
        }
        final NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return List.of();
        }
        // 代表物 → 候选（LinkedHashMap：组顺序 = ingredient 出现顺序，可复现）
        final java.util.Map<Item, List<Item>> byRepresentative = new java.util.LinkedHashMap<>();
        // 下标 0：跳过过渡件（第 0 步这里是「过渡件 + 总样板主原料」的复合，主原料必须留下）
        final List<Item> zero = new ArrayList<>();
        for (final Item item : candidates(ingredients.get(0))) {
            if (transitionalItem != null && item == transitionalItem) {
                continue;
            }
            zero.add(item);
        }
        addInputGroup(byRepresentative, zero);
        // 下标 ≥1：每个 ingredient 一组（组内是它的全部候选）
        for (int i = 1; i < ingredients.size(); i++) {
            addInputGroup(byRepresentative, candidates(ingredients.get(i)));
        }
        final List<InputGroup> result = new ArrayList<>(byRepresentative.size());
        for (final java.util.Map.Entry<Item, List<Item>> entry : byRepresentative.entrySet()) {
            result.add(new InputGroup(new ItemStack(entry.getKey()), List.copyOf(entry.getValue())));
        }
        return result;
    }

    /**
     * 把一组候选登记到「代表物 → 候选」表：代表物 = 该组首个候选；代表物已存在则<b>只并候选</b>
     * （同一物品被两条 ingredient 要求时只有一组，绝不产生两个重复类别）。
     */
    private static void addInputGroup(final java.util.Map<Item, List<Item>> byRepresentative,
                                      final List<Item> group) {
        if (group.isEmpty()) {
            return;
        }
        final Item representative = group.get(0);
        final List<Item> target = byRepresentative.computeIfAbsent(representative, key -> new ArrayList<>(4));
        for (final Item item : group) {
            if (!target.contains(item)) {
                target.add(item);
            }
        }
    }

    /**
     * 总样板主原料（{@code SequencedAssemblyRecipe#getIngredient()}）的候选组；取不到时返回空组。
     * <p>用于「起步原料」判定：<b>标签型起步原料的任一候选都算起步原料</b>
     * （列车轨道的石头台阶 / 平滑石台阶 / 安山岩台阶都能起件）。</p>
     */
    public static InputGroup mainIngredientGroup(@org.jetbrains.annotations.Nullable final Ingredient ingredient) {
        final List<Item> group = candidates(ingredient);
        if (group.isEmpty()) {
            return new InputGroup(ItemStack.EMPTY, List.of());
        }
        return new InputGroup(new ItemStack(group.get(0)), group);
    }

    /**
     * 该步真正消耗的输入原料：跳过第 0 个 ingredient（Create 用它承载过渡件），
     * 取其后各 ingredient 的<b>全部候选物品</b>（标签 / 多值展开），按物品种类去重。
     *
     * <p><b>本轮修正（用户第 1 条）</b>：旧实现每组只取 {@code items[0]}（代表物），
     * 于是标签型的步骤投入物只有第一种被认；现在返回<b>整组候选</b>，
     * 服务端的「这一步要不要它 / 能不能推 / 备不备料」据此对任一候选都成立。</p>
     */
    public static List<ItemStack> assemblyStepInputs(final ProcessingRecipe<?, ?> recipe,
                                                     final Item transitionalItem) {
        final List<ItemStack> result = new ArrayList<>();
        for (final InputGroup group : assemblyStepInputGroups(recipe, transitionalItem)) {
            for (final Item item : group.candidates()) {
                boolean duplicate = false;
                for (final ItemStack existing : result) {
                    if (existing.is(item)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    result.add(new ItemStack(item));
                }
            }
        }
        return result;
    }

    /**
     * 该步第 0 个 ingredient 的首个物品（<b>不剔除过渡件</b>）。
     * <p>只作为输出总线类别的「最后兜底」：当某一步除了第 0 个 ingredient 之外什么都没有时
     * （例如冲压这类只吃过渡件的步骤），仍然给出一个输入类别，保证「输入原料」在界面上可见
     * —— 用户明确要求「冲压只有 1 个 ingredient 的情况也要显示这一项」。</p>
     */
    public static ItemStack firstIngredientItem(final ProcessingRecipe<?, ?> recipe) {
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        final NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (final ItemStack stack : ingredients.get(0).getItems()) {
            if (!stack.isEmpty()) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 该步真正消耗的<b>全部</b>输入原料（跳过承载过渡件的第 0 个 ingredient）。
     * <p>与 {@link #stepInput} 同一口径，但把一个步骤消耗的<i>多种</i>物品全部返回
     * （每种 ingredient 取首个物品作为代表）。附属模组把输入物扩展到 2 种及以上时，
     * 调用方（输出总线类别）即可为每种物品各生成一个独立类别。</p>
     */
    public static List<ItemStack> stepInputItems(final ProcessingRecipe<?, ?> recipe) {
        if (recipe == null) {
            return List.of();
        }
        final NonNullList<Ingredient> ingredients = recipe.getIngredients();
        final List<ItemStack> result = new ArrayList<>();
        for (int i = 1; i < ingredients.size(); i++) {
            final ItemStack[] items = ingredients.get(i).getItems();
            if (items.length > 0 && !items[0].isEmpty()) {
                final ItemStack copy = items[0].copy();
                boolean duplicate = false;
                for (final ItemStack existing : result) {
                    if (existing.is(copy.getItem())) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    result.add(copy);
                }
            }
        }
        return result;
    }

    /**
     * 取该步的<b>第一个</b>输入流体（无则空栈）—— 供「导入时自动标注流体」使用。
     * <p>数据来源与 {@link #stepInputFluids} 完全一致（Create {@code ProcessingRecipe#getFluidIngredients()}）。</p>
     */
    public static FluidStack firstFluid(final ProcessingRecipe<?, ?> recipe) {
        final List<FluidStack> fluids = stepInputFluids(recipe);
        return fluids.isEmpty() ? FluidStack.EMPTY : fluids.get(0);
    }

    /**
     * 该步真正消耗的<b>全部流体输入</b>（Create 的 {@code ProcessingRecipe#getFluidIngredients()}）。
     * <p>与 {@link #stepInputItems} 同一口径：一个步骤消耗多种流体（附属模组 / 标签流体）时全部返回，
     * 调用方（输出总线类别）即可为每种流体各生成一个独立类别。数量取
     * {@link SizedFluidIngredient#amount()}（每批所需 mB），供界面 tooltip 显示。
     * <p>注意：Create 只在物品 ingredient 上覆盖「过渡件」（{@code initFromSequencedAssembly}），
     * <b>不动流体 ingredient</b>，因此这里无需跳过下标 0。</p>
     */
    public static List<FluidStack> stepInputFluids(final ProcessingRecipe<?, ?> recipe) {
        if (recipe == null) {
            return List.of();
        }
        final NonNullList<SizedFluidIngredient> ingredients = recipe.getFluidIngredients();
        final List<FluidStack> result = new ArrayList<>();
        for (final SizedFluidIngredient sized : ingredients) {
            if (sized == null) {
                continue;
            }
            for (final FluidStack stack : sized.getFluids()) {
                if (stack.isEmpty()) {
                    continue;
                }
                boolean duplicate = false;
                for (final FluidStack existing : result) {
                    if (existing.getFluid() == stack.getFluid()) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    result.add(stack.copy());
                }
            }
        }
        return result;
    }

    /**
     * 该步「应用物」的<b>全部候选物品</b>（下标 ≥ 1 的各 ingredient 展开；下标 0 是过渡件，不参与）。
     *
     * <p><b>用途（用户第 2 条：不同配方但中间步骤相同 ⇒ 可复用）</b>：判「两个步骤在语义上是否相同」
     * 需要「<b>步骤类型 + 输入集合</b>」两个量，而输入集合必须是<b>整组候选</b>——
     * 若只取代表物，列车轨道的「装铁粒（铁粒 <b>或</b> 锌粒）」会与精密构件的「装铁粒（铁粒）」
     * 看起来一样，被错误判成同一步。这里返回整组，调用方拼接成稳定字符串做判等。</p>
     *
     * <h2>与「整组」真正对应的那一个（列车轨道单元样板：铁粒 / 锌粒候选未显示）</h2>
     * <p>一个步骤可能有<b>多个</b>应用物 ingredient（每种各成一组、彼此<b>不能</b>互换）。本方法把它们
     * 合成一张候选表，因此只适合做「步骤语义判等」；要取<b>「{@code declared} 所属的那一组」</b>时，
     * 请用 {@link #applicationCandidatesOf} —— 否则多 ingredient 的步会把不属于同一组的候选混在一起。</p>
     */
    public static List<Item> stepApplicationCandidates(final ProcessingRecipe<?, ?> recipe) {
        final List<Item> result = new ArrayList<>();
        if (recipe == null) {
            return result;
        }
        final NonNullList<Ingredient> ingredients = recipe.getIngredients();
        for (int i = 1; i < ingredients.size(); i++) {
            for (final Item item : candidates(ingredients.get(i))) {
                if (!result.contains(item)) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    /**
     * 该步应用物里 <b>{@code declared} 所属的那一组</b>的全部候选（代表物已排在首位）。
     *
     * <h2>为什么必须有它（列车轨道单元样板：铁粒 / 锌粒候选未显示）</h2>
     * <p>{@link #stepInput} 只取「第一个应用物的第一个候选」，于是单元样板物品与它派生出来的
     * RS 总样板都只认铁粒 —— 锌粒根本没被登记（实机日志里 {@code create:track} 样板的
     * ingredients 只有 {@code minecraft:iron_nugget}）。要让「铁粒 <b>或</b> 锌粒」这条语义活下来，
     * 就必须在<b>生成单元样板的那一刻</b>把整组候选算出来带下去。</p>
     *
     * <p><b>匹配顺序</b>：① 先在下标 ≥ 1 的各 ingredient 里找「包含 {@code declared}」的那一组
     * （与 {@code SequenceExecutionChamberBlockEntity#registerInputCandidates} 同一口径 ——
     * 玩家手填的标记物落在哪一组，就登记哪一组）；② 找不到（例如标记物是「第 0 个 ingredient
     * 复合里的主原料」）时退回 {@link #stepApplicationCandidates}（全部应用物候选的并集）；
     * ③ 仍为空 ⇒ 返回 {@code declared} 自己一件（绝不返回空，调用方无需再兜底）。</p>
     * <p>只读；配方异常一律按「找不到」处理，绝不抛。</p>
     */
    public static List<Item> applicationCandidatesOf(@org.jetbrains.annotations.Nullable final ProcessingRecipe<?, ?> recipe,
                                                     @org.jetbrains.annotations.Nullable final ItemStack declared) {
        final Item wanted = declared == null || declared.isEmpty() ? null : declared.getItem();
        if (recipe == null) {
            return wanted == null ? List.of() : List.of(wanted);
        }
        try {
            final List<InputGroup> groups = assemblyStepInputGroups(recipe, transitionalOf(recipe));
            if (wanted != null) {
                for (final InputGroup group : groups) {
                    if (group.candidates().contains(wanted)) {
                        return group.candidates();
                    }
                }
            }
            if (groups.size() == 1) {
                return groups.get(0).candidates();
            }
            final List<Item> merged = stepApplicationCandidates(recipe);
            if (!merged.isEmpty()) {
                return merged;
            }
        } catch (final RuntimeException ignored) {
            // 配方结构异常（版本差异）：落到「只有标记物一件」
        }
        return wanted == null ? List.of() : List.of(wanted);
    }

    /**
     * 该处理配方所属序列装配的过渡件（工作态下第 0 个 ingredient 必然含它）。
     * <p>用途同 {@link #assemblyStepInputGroups} 的 {@code transitionalItem} 参数：把下标 0 里
     * 被 Create 覆盖进去的过渡件剔除，只留真正的主原料。取不到（自定义配方覆盖得不完整）时返回
     * {@code null}（= 不剔除任何东西，组内容退化为原样，绝不因此少给候选）。</p>
     */
    @org.jetbrains.annotations.Nullable
    private static Item transitionalOf(final ProcessingRecipe<?, ?> recipe) {
        try {
            final NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) {
                return null;
            }
            // 第 0 个 ingredient 的场景：过渡件 + （第 0 步时的）主原料复合。
            // 「主原料」= 序列装配配方自己的 ingredient，从处理配方这一层拿不到，
            // 因此这里只在「下标 0 只有一种物品」时认为它是纯过渡件。
            final ItemStack[] items = ingredients.get(0).getItems();
            return items.length == 1 && !items[0].isEmpty() ? items[0].getItem() : null;
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 一条产出：物品（含数量）+ 概率（0..1）。 */
    public record Output(ItemStack stack, float chance) {
    }
    /** resultPool 的拆分结果：必得主产物 + 概率废料。 */
    public record Split(List<Output> results, List<Output> scraps) {
    }

    /**
     * 把 Create 的 resultPool 拆成「主产物 + 废料」，<b>概率一律与 Create JEI 完全一致</b>：
     * <ul>
     *     <li><b>主产物</b> = 第一个非空项（Create 自己也以 {@code resultPool.getFirst()} 为主产物，
     *     其 JEI 面板展示的就是这一项），概率 = {@code 该项权重 / 全部项权重之和}
     *     —— 完全等价于 Create JEI 第 189-194 行的 {@code getOutputChance()}；</li>
     *     <li><b>废料</b> = 其余非空项，概率同样 = {@code 该项权重 / 全部项权重之和}。</li>
     * </ul>
     * 数量一律照抄 {@link ProcessingOutput#getStack()} 的 {@code getCount()}。
     * <p>注意：主产物「不是必得」——Create 的轮盘对池里每一项都按权重随机；这里只是沿用
     * 「第一项 = 主产物」的展示口径（与 Create 的 {@code getResultItem()} 一致）。</p>
     */
    public static Split splitResultPool(final List<ProcessingOutput> pool) {
        final List<Output> nonEmpty = new ArrayList<>();
        float totalWeight = 0F;
        if (pool != null) {
            for (final ProcessingOutput output : pool) {
                if (output == null) {
                    continue;
                }
                final ItemStack stack = output.getStack();
                if (stack.isEmpty()) {
                    continue;
                }
                final float weight = Math.max(0F, output.getChance());
                nonEmpty.add(new Output(stack.copy(), weight));
                totalWeight += weight;
            }
        }
        final List<Output> results = new ArrayList<>();
        final List<Output> scraps = new ArrayList<>();
        if (nonEmpty.isEmpty()) {
            return new Split(results, scraps);
        }
        results.add(new Output(nonEmpty.get(0).stack(), probability(nonEmpty.get(0).chance(), totalWeight)));
        for (int i = 1; i < nonEmpty.size(); i++) {
            final Output output = nonEmpty.get(i);
            scraps.add(new Output(output.stack(), probability(output.chance(), totalWeight)));
        }
        return new Split(results, scraps);
    }
}
