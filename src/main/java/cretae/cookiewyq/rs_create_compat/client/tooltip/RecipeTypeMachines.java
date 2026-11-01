package cretae.cookiewyq.rs_create_compat.client.tooltip;

import com.simibubi.create.AllBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「配方类型 → 能执行它的<b>机器方块</b>」反查（客户端）。
 * <p><b>为什么需要它</b>：JEI 查配方时左侧显示的是机器，而不是配方的原料 / 产物；
 * 配置界面里也应当如此 —— 例如 {@code create:pressing} 要显示「动力冲压机」这个方块，
 * 而不是它输入 / 输出的那堆物品。</p>
 * <p><b>数据来源（按优先级合并、按物品去重）</b>：</p>
 * <ol>
 *     <li><b>显式机器表</b>：Create 的机器取自其自带 JEI 分类的催化剂注册
 *     （{@code CreateJEI#loadCategories} 的 {@code .catalyst(...)}，即官方认定的“能跑该配方类型的机器”）；
 *     原版配方类型补齐到对应方块（工作台 / 熔炉 / 高炉 / 烟熏炉 …）。</li>
 *     <li><b>配方自带的 {@link Recipe#getToastSymbol()}</b>：模组（含“更高级的熔炉”这类机器）通常会覆写它
 *     指向自己的机器方块，因此这里能自动把三方机器纳入候选。未覆写时该方法是原版默认值（工作台），
 *     一般对非 {@code minecraft:crafting} 的配方类型忽略；但若配方本身是 {@link CraftingRecipe}
 *     （含 {@code CustomRecipe} 这种特殊合成），工作台就是它<b>真正</b>的机器，会被正常收下。</li>
 *     <li><b>JEI 催化剂</b>（{@link JeiCatalystMachines}）：模组在
 *     {@code IRecipeCatalystRegistration#addRecipeCatalyst(...)} 里<b>官方声明</b>的“能跑该配方类型的机器”。
 *     这是唯一能覆盖「配方没覆写 {@code getToastSymbol()}、显式表里也没有」的第三方机器的通用来源
 *     （典型：机械动力：火炮 / Create Addition）。</li>
 *     <li><b>同命名空间方块兜底</b>：仅当以上全空时，在本模组自己的命名空间里找「注册名包含该配方类型
 *     全部词」的那个方块（{@code *_deployer} 之后再加的一条窄路，见 {@link #siblingBlockMachines}）。
 *     它是<b>最后</b>一条来源：既不改写任何已有优先级，也不跨命名空间乱猜。</li>
 * </ol>
 * <p><b>为什么 JEI 排在最后</b>：前两条更“具体”（我们手工校准过、或配方自己指定），JEI 是“官方但最泛”
 * 的一份全集 —— 它可能包含非方块物品（由 {@link #addMachine} 过滤），也不该覆盖前两条已经给出的顺序。
 * 放在最后既保证前两条的优先级不被改写，又能把前两条拿不到的机器补齐；去重由 {@link #addMachine}
 * 的 {@link Set} 统一完成。</p>
 * <p><b>命名约定回退</b>：仅当以上来源全空、且 id 以 {@code _deployer} 结尾时，借用
 * {@code create:deploying} 的机器（机械手）。这是给「配方由 Deployer 动态构造、JEI 又没有独立分类」
 * 的 Create 系附加模组留的最后一条窄路（如机械动力：火炮的 5 个 {@code *_deployer}）。</p>
 * <p><b>兜底</b>：所有来源都拿不到机器时返回空列表，调用方回退显示配方类型本身的名称
 * （<b>绝不</b>退化成显示原料 / 产物）。空结果受 {@link #EMPTY_RETRY_NANOS} 的短 TTL 约束，
 * 非空结果按「JEI 代际 + 客户端配方表代际」缓存（见 {@link #CACHE}）—— 界面每帧查询都是 O(1)。</p>
 * <p><b>结果形态</b>：只保留方块物品（{@link BlockItem}）作为“机器方块”；显示名用
 * {@link ItemStack#getHoverName()}，因此跟随玩家语言自动本地化。</p>
 */
public final class RecipeTypeMachines {
    /** 每个配方类型最多返回几个候选机器（多个时由界面轮询切换）。 */
    public static final int MAX_MACHINES = 6;
    /** 探测 {@code getToastSymbol()} 时最多遍历多少条配方（原版合成有上万条，必须封顶）。 */
    private static final int MAX_RECIPE_PROBE = 32;
    /** {@link Recipe#getToastSymbol()} 未覆写时的原版默认值（工作台）。 */
    private static final Item DEFAULT_SYMBOL = Items.CRAFTING_TABLE;
    /** 唯一允许“工作台 = 机器”的配方类型。 */
    private static final String VANILLA_CRAFTING = "minecraft:crafting";
    /** Create 的机械手配方类型（显式表里已有机器；供「命名约定回退」借用）。 */
    private static final String CREATE_DEPLOYING = "create:deploying";
    /** Create 系附加模组对「机械手执行」的配方类型的命名后缀。 */
    private static final String DEPLOYER_SUFFIX = "_deployer";
    /**
     * 机械动力：火炮（Create Big Cannons）的命名空间。
     * <p>它<b>不是</b>本工程的编译期依赖（{@code build.gradle} 里没有它的坐标），
     * 所以机器方块只能按注册名解析，不能写成 {@code CBCBlocks.xxx.get()}。</p>
     */
    private static final String CBC_NAMESPACE = "createbigcannons";
    /** Create Addition 的命名空间（同样不是编译期依赖，只能按注册名解析）。 */
    private static final String CREATE_ADDITION_NAMESPACE = "createaddition";

    /**
     * 空结果的短期负缓存时长（纳秒）。
     *
     * <p><b>为什么要它</b>：JEI 催化剂是<b>运行时</b>才就绪的（JEI 在资源重载 / 进世界时才
     * {@code Internal.setRuntime(...)}），而显式表 / {@code getToastSymbol()} 也可能在客户端配方表
     * 加载完成前查不到。若把空结果永久缓存，界面就再没有第二次查询的机会 —— 用户看到的就是
     * 「列表永远空、必须重进存档才偶尔好」。但反过来，如果完全不缓存，界面每帧都会为每个空类型
     * 重扫最多 {@link #MAX_RECIPE_PROBE} 条配方 + JEI 查询，代价不可接受。</p>
     * <p><b>折中</b>：空结果只记 {@code 1 秒}，且记录里带<b>JEI 代际</b>
     * （{@link JeiCatalystMachines#generation()}）：JEI 第一次就绪 / 资源重载重启 JEI 会让代际立刻变化，
     * 于是下一次调用<b>立即重查</b>（不必等满 1 秒）；JEI 未安装时也只是每秒重试一次，
     * 对 tooltip / 界面路径可以忽略。</p>
     */
    private static final long EMPTY_RETRY_NANOS = 1_000_000_000L;
    /** 缓存上限（防御性封顶：配方类型数量有限，但第三方整合包可能注册很多）。 */
    private static final int CACHE_LIMIT = 512;

    /**
     * 一条查询结果：当时的 JEI 代际 + 客户端配方表代际 + 记录时间。
     * <p><b>为什么要缓存非空结果</b>（2026-10-17 第 84 轮）：配方类型选择器 / 单元样板 tooltip 会
     * <b>每帧</b>问一次「这个类型的机器是谁」。旧实现只对空结果做负缓存，非空结果每次都要重新走
     * 「显式表 → 扫最多 {@link #MAX_RECIPE_PROBE} 条配方的 {@code getToastSymbol()} → JEI 反射」，
     * 其中配方扫描与 JEI 反射都是毫秒级开销 —— 那是每帧都在付的钱。现在非空结果同样进这张表，
     * 命中只需要一次 map 读 + 两次引用比较（代际不变就一直有效）。</p>
     * <p>两个代际任一变化（换世界 / 资源重载 / JEI 重启）都会让整条记录失效，因此不会读到过期内容；
     * 空结果额外受 {@link #EMPTY_RETRY_NANOS} 约束以保持「自愈」。</p>
     */
    private record Cached(List<Machine> machines, Object jeiGeneration, Object recipeManager, long atNanos) {
    }

    /** 结果缓存：键 = 配方类型 id。 */
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * 命名空间 → 该命名空间下全部<b>方块物品</b>的注册路径（小写）；懒构建一次。
     * <p>只给 {@link #siblingBlockMachines} 这条「最后一条兜底」用：第三方配方类型既没有显式表条目、
     * 配方又不覆写 {@code getToastSymbol()}、JEI 里也没有催化剂时，用「同命名空间里名字包含该配方类型
     * 路径的方块」找机器（详见该方法注释）。</p>
     */
    private static volatile Map<String, List<String>> namespaceBlocks;

    /** 一个候选机器：图标（方块物品）+ 显示名。 */
    public record Machine(ItemStack icon, Component name) {
    }

    /** 懒加载的显式机器表（必须在注册表就绪后构建，故不放在静态初始化块里）。 */
    private static Map<String, List<ItemLike>> explicitTable;

    private RecipeTypeMachines() {
    }

    /**
     * 反查能执行指定配方类型的机器方块（已去重、最多 {@link #MAX_MACHINES} 个）。
     * <p>查不到任何已知机器时返回空列表（调用方回退显示配方类型名）。</p>
     *
     * @param recipeTypeId 配方类型 id（如 {@code create:pressing}）
     */
    public static List<Machine> forRecipeType(final String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) {
            return List.of();
        }
        // 两级缓存：非空结果「代际不变就一直有效」，空结果额外受 1 秒 TTL 约束（见 CACHE / EMPTY_RETRY_NANOS）。
        final Object jeiGeneration = JeiCatalystMachines.generation();
        final Object recipeEpoch = recipeEpoch();
        final Cached cached = CACHE.get(recipeTypeId);
        if (cached != null && cached.jeiGeneration() == jeiGeneration
            && cached.recipeManager() == recipeEpoch) {
            if (!cached.machines().isEmpty()
                || System.nanoTime() - cached.atNanos() < EMPTY_RETRY_NANOS) {
                return cached.machines();
            }
        }

        final List<Machine> machines = new ArrayList<>();
        final Set<Item> seen = new LinkedHashSet<>();
        // ① 显式机器表（Create 官方 JEI 催化剂 + 原版机器）
        for (final ItemLike machine : explicitTable().getOrDefault(recipeTypeId, List.of())) {
            addMachine(machines, seen, new ItemStack(machine));
        }
        // ② 配方自带 getToastSymbol：第三方模组的机器（如更高级的熔炉）会在这里被自动收进来
        for (final ItemStack symbol : symbolsFromRecipes(recipeTypeId)) {
            addMachine(machines, seen, symbol);
        }
        // ③ JEI 催化剂（模组官方声明的机器）：放最后，补前两条拿不到的第三方机器；去重由 addMachine 负责。
        //     JEI 未安装 / 未就绪 / 反射失败时它安静返回空列表，前两条照常工作。
        for (final ItemStack catalyst : JeiCatalystMachines.catalystsFor(recipeTypeId)) {
            addMachine(machines, seen, catalyst);
        }
        // ④ 命名约定回退（仅当前三条全空）：Create 系附加模组把「由机械手执行」的配方类型命名为
        //    `<ns>:<动作>_deployer`（机械动力：火炮的 5 个都是这样）。这类配方是模组的 Deployer
        //    动态构造的，既不在 JEI 里单独建分类、也常常没有注册实例 ⇒ 前三条必然全空；
        //    但它的机器客观就是机械手，因此只在这里借用显式表里 `create:deploying` 的机器（机械手）。
        //    放在最后且仅在空结果时执行，不会改写任何已有优先级 / 去重结果。
        if (machines.isEmpty() && recipeTypeId.endsWith(DEPLOYER_SUFFIX)) {
            for (final ItemLike machine : explicitTable().getOrDefault(CREATE_DEPLOYING, List.of())) {
                addMachine(machines, seen, new ItemStack(machine));
            }
        }
        // ⑤ 同命名空间方块兜底（仅当上面四条全空）：见 siblingBlockMachines 的注释。
        //    这是「用户看到的类型一直没有图标」的最后一条窄路，规则刻意保守（宁可不给，也不乱给）。
        if (machines.isEmpty()) {
            for (final ItemStack sibling : siblingBlockMachines(recipeTypeId)) {
                addMachine(machines, seen, sibling);
            }
        }
        final List<Machine> result = machines.isEmpty() ? List.of() : List.copyOf(machines);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(recipeTypeId, new Cached(result, jeiGeneration, recipeEpoch, System.nanoTime()));
        }
        return result;
    }

    /** 收下一个候选机器：必须是方块物品、未重复、未超上限。 */
    private static void addMachine(final List<Machine> out, final Set<Item> seen, final ItemStack stack) {
        if (out.size() >= MAX_MACHINES || stack == null || stack.isEmpty()) {
            return;
        }
        if (!(stack.getItem() instanceof BlockItem) || !seen.add(stack.getItem())) {
            return;
        }
        out.add(new Machine(stack.copyWithCount(1), stack.getHoverName()));
    }

    /**
     * 客户端当前配方表实例（资源重载 / 换存档会换成新实例；不可用时返回 {@code null}）。
     * <p>空结果负缓存把它一起记进代际：客户端配方表换了（F3+T、数据包重载）就立即重查，
     * 不必等满 {@link #EMPTY_RETRY_NANOS}。取不到时只是少一条失效维度，TTL 仍然兜底。</p>
     */
    private static Object recipeEpoch() {
        try {
            final Level level = Minecraft.getInstance().level;
            return level == null ? null : level.getRecipeManager();
        } catch (final RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * 遍历该配方类型下的配方，收集它们 {@link Recipe#getToastSymbol()} 指向的机器方块。
     * <p>任何异常（配方类型未注册 / 配方 value 类型不匹配 / 客户端 level 未就绪）都静默跳过，
     * 绝不崩界面。</p>
     */
    @SuppressWarnings("unchecked")
    private static List<ItemStack> symbolsFromRecipes(final String recipeTypeId) {
        final List<ItemStack> symbols = new ArrayList<>();
        try {
            final ResourceLocation id = ResourceLocation.tryParse(recipeTypeId);
            final Level level = Minecraft.getInstance().level;
            if (id == null || level == null) {
                return symbols;
            }
            final RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(id);
            if (type == null) {
                return symbols;
            }
            final RecipeType<Recipe<RecipeInput>> typed =
                (RecipeType<Recipe<RecipeInput>>) (RecipeType<?>) type;
            final List<RecipeHolder<Recipe<RecipeInput>>> recipes =
                level.getRecipeManager().getAllRecipesFor(typed);
            final boolean craftableSymbolAllowed = VANILLA_CRAFTING.equals(recipeTypeId);
            int probed = 0;
            for (final RecipeHolder<Recipe<RecipeInput>> holder : recipes) {
                if (probed++ >= MAX_RECIPE_PROBE || symbols.size() >= MAX_MACHINES) {
                    break;
                }
                // 先转 Object 再判：Recipe<RecipeInput> 与 CraftingRecipe（Recipe<CraftingInput>）的泛型
                // 不协变，直接写 instanceof 会被 javac 判成「不可能的转换」。
                final boolean craftingRecipe = ((Object) holder.value()) instanceof CraftingRecipe;
                final ItemStack symbol;
                try {
                    symbol = holder.value().getToastSymbol();
                } catch (final Throwable ignored) {
                    continue; // 个别配方缺少上下文时会抛异常：跳过这一条
                }
                if (symbol == null || symbol.isEmpty()) {
                    continue;
                }
                // 未覆写 getToastSymbol() 时返回的是原版默认工作台：
                //   · 一般非合成配方类型 → 视为“无信息”丢掉（旧行为，防止把工作台误当成任意机器的机器）；
                //   · 但若配方本身就是 CraftingRecipe（含 CustomRecipe 这种特殊合成），工作台就是它
                //     <b>真正</b>的执行机器 —— 例如机械动力：火炮的 6 个 MunitionFuzingRecipe /
                //     BigCartridgeFillingRecipe …（都 extends CustomRecipe）：它们没有自己的 JEI 分类、
                //     也没覆写 getToastSymbol，但确实是在工作台里合成的。
                if (!craftableSymbolAllowed && !craftingRecipe && symbol.is(DEFAULT_SYMBOL)) {
                    continue;
                }
                symbols.add(symbol);
            }
        } catch (final Throwable ignored) {
            // 配方系统尚未就绪：退化为只用显式机器表
        }
        return symbols;
    }

    /** 显式机器表（首次调用时才构建，保证注册表已就绪）。 */
    private static Map<String, List<ItemLike>> explicitTable() {
        if (explicitTable == null) {
            explicitTable = buildExplicitTable();
        }
        return explicitTable;
    }

    /** 构建「配方类型 id → 机器方块」表：Create 部分与 {@code CreateJEI} 的催化剂一一对应。 */
    private static Map<String, List<ItemLike>> buildExplicitTable() {
        return Map.ofEntries(
            // ---------- 机械动力：取自 Create 自带 JEI 分类的催化剂 ----------
            Map.entry("create:milling", machines(AllBlocks.MILLSTONE.get())),
            Map.entry("create:crushing", machines(AllBlocks.CRUSHING_WHEEL.get())),
            Map.entry("create:pressing", machines(AllBlocks.MECHANICAL_PRESS.get())),
            Map.entry("create:cutting", machines(AllBlocks.MECHANICAL_SAW.get())),
            Map.entry("create:mixing", machines(AllBlocks.MECHANICAL_MIXER.get(), AllBlocks.BASIN.get())),
            Map.entry("create:basin", machines(AllBlocks.MECHANICAL_MIXER.get(), AllBlocks.BASIN.get())),
            Map.entry("create:compacting", machines(AllBlocks.MECHANICAL_PRESS.get(), AllBlocks.BASIN.get())),
            Map.entry("create:splashing", machines(AllBlocks.ENCASED_FAN.get())),
            Map.entry("create:haunting", machines(AllBlocks.ENCASED_FAN.get())),
            Map.entry("create:deploying", machines(AllBlocks.DEPLOYER.get(), AllBlocks.DEPOT.get())),
            Map.entry("create:item_application", machines(AllBlocks.DEPLOYER.get())),
            Map.entry("create:filling", machines(AllBlocks.SPOUT.get())),
            Map.entry("create:emptying", machines(AllBlocks.ITEM_DRAIN.get())),
            Map.entry("create:mechanical_crafting", machines(AllBlocks.MECHANICAL_CRAFTER.get())),
            // ---------- 机械动力：火炮（Create Big Cannons 5.11.7） ----------
            // 来源：用 javap 反汇编真实 jar 的 compat/jei 类（selfcheck_round83 会复算下列每个方块 id
            // 在该 jar 的 assets/*/blockstates/ 里确实存在）：
            //   · melting：CBCJEI#loadCategories 的催化剂 = CBCBlocks.BASIN_FOUNDRY_LID + AllBlocks.BASIN，
            //     由 CBCBlockRecipeCategory#registerCatalysts 统一登记（唯一的 addRecipeCatalyst 落点）。
            //   · 6 个 CustomRecipe（munition_fuzing / cartridge_assembly / big_cartridge_filling /
            //     tracer_application / fuze_removal / tracer_removal）：CBCJEI#registerRecipes 把它们
            //     挂在 JEI 的 RecipeTypes.CRAFTING 分类下 ⇒ 机器是工作台（配方本身也确实 extends CustomRecipe）。
            //   · 5 个 *_deployer：CBCJEI#onRuntimeAvailable 把它们挂在 create:deploying 分类下
            //     ⇒ 机器与 create:deploying 的催化剂一致（机械手 / 置物台）。
            // 不写 createbigcannons:autocannon_ammo_container_filling（不带 _deployer）：CBCRecipeTypes 的
            // 12 个常量里只有 AUTOCANNON_AMMO_CONTAINER_FILLING_DEPLOYER，该 recipe type 在 5.11.7 不存在，
            // 写进去只会变成永远命中不到的死条目。
            Map.entry("createbigcannons:melting",
                machines(thirdPartyMachine(CBC_NAMESPACE, "basin_foundry_lid"), AllBlocks.BASIN.get())),
            Map.entry("createbigcannons:munition_fuzing", machines(Blocks.CRAFTING_TABLE)),
            Map.entry("createbigcannons:munition_fuzing_deployer",
                machines(AllBlocks.DEPLOYER.get(), AllBlocks.DEPOT.get())),
            Map.entry("createbigcannons:cartridge_assembly", machines(Blocks.CRAFTING_TABLE)),
            Map.entry("createbigcannons:cartridge_assembly_deployer",
                machines(AllBlocks.DEPLOYER.get(), AllBlocks.DEPOT.get())),
            Map.entry("createbigcannons:big_cartridge_filling", machines(Blocks.CRAFTING_TABLE)),
            Map.entry("createbigcannons:big_cartridge_filling_deployer",
                machines(AllBlocks.DEPLOYER.get(), AllBlocks.DEPOT.get())),
            Map.entry("createbigcannons:tracer_application", machines(Blocks.CRAFTING_TABLE)),
            Map.entry("createbigcannons:tracer_application_deployer",
                machines(AllBlocks.DEPLOYER.get(), AllBlocks.DEPOT.get())),
            Map.entry("createbigcannons:fuze_removal", machines(Blocks.CRAFTING_TABLE)),
            Map.entry("createbigcannons:tracer_removal", machines(Blocks.CRAFTING_TABLE)),
            Map.entry("createbigcannons:autocannon_ammo_container_filling_deployer",
                machines(AllBlocks.DEPLOYER.get(), AllBlocks.DEPOT.get())),
            // ---------- Create Addition（1.7.2） ----------
            // 来源：CreateAdditionJEI#registerCategories 的 .catalyst(...)（真实 jar 反汇编）：
            //   charging → CABlocks.TESLA_COIL，rolling → CABlocks.ROLLING_MILL，
            //   liquid_burning → AllBlocks.BLAZE_BURNER。
            Map.entry("createaddition:charging",
                machines(thirdPartyMachine(CREATE_ADDITION_NAMESPACE, "tesla_coil"))),
            Map.entry("createaddition:rolling",
                machines(thirdPartyMachine(CREATE_ADDITION_NAMESPACE, "rolling_mill"))),
            Map.entry("createaddition:liquid_burning", machines(AllBlocks.BLAZE_BURNER.get())),
            // ---------- 原版：机器方块 + 同样支持该配方的 Create 机器（构成多机器轮询） ----------
            Map.entry(VANILLA_CRAFTING,
                machines(Blocks.CRAFTING_TABLE, AllBlocks.MECHANICAL_CRAFTER.get())),
            Map.entry("minecraft:smelting", machines(Blocks.FURNACE)),
            Map.entry("minecraft:blasting", machines(Blocks.BLAST_FURNACE, AllBlocks.ENCASED_FAN.get())),
            Map.entry("minecraft:smoking", machines(Blocks.SMOKER, AllBlocks.ENCASED_FAN.get())),
            Map.entry("minecraft:campfire_cooking", machines(Blocks.CAMPFIRE)),
            Map.entry("minecraft:stonecutting", machines(Blocks.STONECUTTER, AllBlocks.MECHANICAL_SAW.get())),
            Map.entry("minecraft:smithing", machines(Blocks.SMITHING_TABLE))
        );
    }

    /**
     * 按「命名空间 + 注册路径」解析三方模组的机器方块。
     * <p><b>为什么不用 xxxBlocks 常量</b>：机械动力：火炮 / Create Addition 都不是本工程的编译期依赖
     * （{@code build.gradle} 里没有它们的坐标），引用它们的类会直接编译失败，因此只能按注册名解析。</p>
     * <p><b>缺模组时</b>：注册名解析不到就返回 {@link Items#AIR}；{@link #addMachine} 只收
     * {@link BlockItem}，会在那里把空栈丢掉 —— 效果只是“少一台机器”，不会显示缺失材质图标。</p>
     */
    private static ItemLike thirdPartyMachine(final String namespace, final String path) {
        final Item item = BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath(namespace, path));
        return item == null ? Items.AIR : item;
    }

    // ------------------------------------------------------------------
    // ⑤ 同命名空间方块兜底（最后一条窄路）
    // ------------------------------------------------------------------

    /**
     * 「同命名空间方块」兜底：在本模组<b>完全没有数据</b>的第三方配方类型里，猜一台机器。
     *
     * <p><b>为什么需要它</b>（用户 2026-10-17 实机反馈：「也是没有那个机器图标」）：机器图标来自
     * 上面四条来源；四条全空的类型就<b>永远画不出图标</b>。常见于没有 JEI 分类、配方又是
     * {@code CustomRecipe} 的模组（它们连 {@code getToastSymbol()} 都不覆写）。用户的整合包里
     * 这类类型不少，而「配方类型名 → 机器」这件事<b>模组作者自己通常是照着机器名命名的</b>
     * （{@code rolling} → {@code rolling_mill}、{@code freezing} → {@code freezing_chamber}）。</p>
     *
     * <p><b>规则（刻意保守：宁可不给，也不乱给）</b>：</p>
     * <ol>
     *     <li>只在<b>前四条来源全空</b>时执行（绝不覆盖显式表 / toast symbol / JEI 催化剂）；</li>
     *     <li>只在<b>同一个命名空间</b>里找方块物品（跨模组猜没有任何依据）；</li>
     *     <li>配方类型的路径必须作为<b>完整词序列</b>出现在方块路径里（{@code big_cartridge_filling}
     *     命中 {@code big_cartridge_filling_machine}，但 {@code filling} 不会命中
     *     {@code big_cartridge_filling} —— 避免「短词把别人的机器抢过来」）；</li>
     *     <li>多个候选时只取<b>词数最少</b>的那一个（更短的通常才是方块本体，长的往往是它的盖子 / 变体），
     *     仍并列时按注册名排序取第一个 —— <b>结果确定</b>，不随注册表遍历顺序变化；</li>
     *     <li>仍然只收 {@link BlockItem}（{@link #addMachine} 统一过滤），最多一台。</li>
     * </ol>
     *
     * <p><b>代价</b>：命名空间索引懒构建一次（扫一遍 {@code BuiltInRegistries.ITEM}），之后每条兜底
     * 只在自己命名空间的方块列表里线性扫一遍；结果同样进 {@link #CACHE}，因此每个配方类型最多算一次。</p>
     */
    private static List<ItemStack> siblingBlockMachines(final String recipeTypeId) {
        final int colon = recipeTypeId.indexOf(':');
        if (colon <= 0 || colon == recipeTypeId.length() - 1) {
            return List.of();
        }
        final String namespace = recipeTypeId.substring(0, colon);
        final String path = recipeTypeId.substring(colon + 1);
        final List<String> wanted = pathTokens(path);
        if (wanted.isEmpty()) {
            return List.of();
        }
        final List<String> blocks = namespaceBlocks().getOrDefault(namespace, List.of());
        String best = null;
        int bestWordCount = Integer.MAX_VALUE;
        for (final String candidate : blocks) {
            final List<String> words = pathTokens(candidate);
            if (!containsContiguous(words, wanted)) {
                continue;
            }
            // 更短的优先；同长度按注册名排序取第一个（结果确定，不依赖遍历顺序）
            if (words.size() < bestWordCount
                || (words.size() == bestWordCount && best != null && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestWordCount = words.size();
            }
        }
        if (best == null) {
            return List.of();
        }
        final Item item = BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath(namespace, best));
        return item == null ? List.of() : List.of(new ItemStack(item));
    }

    /** 懒构建「命名空间 → 方块物品路径」索引（只在第一次需要兜底时扫一遍注册表）。 */
    private static Map<String, List<String>> namespaceBlocks() {
        Map<String, List<String>> local = namespaceBlocks;
        if (local != null) {
            return local;
        }
        synchronized (RecipeTypeMachines.class) {
            local = namespaceBlocks;
            if (local == null) {
                final Map<String, List<String>> built = new java.util.HashMap<>();
                for (final ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    if (!(BuiltInRegistries.ITEM.get(id) instanceof BlockItem)) {
                        continue;
                    }
                    built.computeIfAbsent(id.getNamespace(), key -> new ArrayList<>()).add(id.getPath());
                }
                local = built;
                namespaceBlocks = local;
            }
        }
        return local;
    }

    /** 注册路径 → 小写词序列（按 {@code _ - .} 切词，丢弃空串）。 */
    private static List<String> pathTokens(final String path) {
        final String[] parts = path.toLowerCase(java.util.Locale.ROOT).split("[_\\-.]+");
        final List<String> words = new ArrayList<>(parts.length);
        for (final String part : parts) {
            if (!part.isEmpty()) {
                words.add(part);
            }
        }
        return words;
    }

    /** {@code wanted} 是否是 {@code words} 里的一段<b>连续</b>词序列。 */
    private static boolean containsContiguous(final List<String> words, final List<String> wanted) {
        if (wanted.isEmpty() || words.size() < wanted.size()) {
            return false;
        }
        for (int start = 0; start + wanted.size() <= words.size(); start++) {
            boolean matched = true;
            for (int i = 0; i < wanted.size(); i++) {
                if (!words.get(start + i).equals(wanted.get(i))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    /** 定长机器列表（显式声明 {@code List<ItemLike>}，避免 {@code List.of} 的元素类型推断歧义）。 */
    private static List<ItemLike> machines(final ItemLike... machines) {
        return List.of(machines);
    }
}
