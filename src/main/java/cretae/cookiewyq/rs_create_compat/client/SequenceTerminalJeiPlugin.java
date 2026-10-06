package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.SequencePatternTerminalScreen;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI 集成：为序列装配样板终端注册 ghost 输入（拖拽物品放入槽位）与
 * 配方转移（在 Create 序列装配配方上点击"+"直接把配方填入终端）。
 */
@JeiPlugin
public class SequenceTerminalJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sequence_terminal_jei");
    }

    @Override
    public void registerGuiHandlers(final IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(SequencePatternTerminalScreen.class, new GhostHandler());
    }

    /**
     * Create 序列装配类别在 JEI 里的<b>配方类型</b>（不是容器、不是配方类）：
     * <ul>
     *   <li>uid = {@code create:sequenced_assembly}，直接取自 {@link AllRecipeTypes#SEQUENCED_ASSEMBLY} 的注册 id；</li>
     *   <li>recipeClass = {@link RecipeHolder}：Create 的 {@code SequencedAssemblyCategory} 由
     *       {@code createRecipeHolderType} 构造，承载的配方对象就是 {@code RecipeHolder<SequencedAssemblyRecipe>}。</li>
     * </ul>
     * JEI 查表用 {@code RecipeType.equals}（uid + recipeClass 都比），所以这里构造出来的类型
     * 与 Create 注册的类别类型完全相等，才能命中。
     */
    private static final RecipeType<RecipeHolder<SequencedAssemblyRecipe>> SEQUENCED_ASSEMBLY_TYPE =
        RecipeType.createRecipeHolderType(AllRecipeTypes.SEQUENCED_ASSEMBLY.getId());

    @Override
    public void registerRecipeTransferHandlers(final IRecipeTransferRegistration registration) {
        // 只把「+」挂到 Create 序列装配这一个配方类别上。
        // JEI 的查找是 (容器类, 配方类型) 精确匹配，查不到才退回 universal 键；
        // 旧实现用 addUniversalRecipeTransferHandler → 注册键是 jei:universal_recipe_transfer_handler，
        // 于是终端打开时【所有】配方类别（普通合成 / 加工 / 机械手使用，乃至纯展示页）都长出可点的「+」，
        // 点了却什么也不做（预览阶段返回 null = 无错误）。这里改成精确 RecipeType 后，
        // 非序列装配类别根本查不到我们的 handler，JEI 只会给 INTERNAL 错误 →「+」隐藏。
        registration.addRecipeTransferHandler(new SequencedAssemblyTransferHandler(), SEQUENCED_ASSEMBLY_TYPE);
    }

    /**
     * 取该处理配方所属的<b>配方类型注册 id</b>（如 {@code create:pressing}）。
     * <p><b>为什么必须是注册 id</b>：执行仓的「配方类型」是从已注册配方类型里选的
     * （{@code SyncRecipeTypesPacket} 下发的是注册 id），步骤与执行仓必须<b>用同一套键</b>才能匹配。
     * 早期实现返回的是 {@code "Pressing"} / {@code "DeployerApplication"} 这类手写短名，
     * 与执行仓存的 {@code create:pressing} / {@code create:deploying} 永远不相等，
     * 于是玩家明明连了机器、也选了配方类型，步骤详细配置里仍然显示「(不可用) / 无可用机器」。</p>
     */
    private static String machineKeyFor(final ProcessingRecipe<?, ?> recipe) {
        // ① 首选：注册表里的真实配方类型 id —— 与执行仓的选择列表同源，保证能匹配上
        try {
            final net.minecraft.world.item.crafting.RecipeType<?> type = recipe.getType();
            final net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (key != null) {
                return key.toString();
            }
        } catch (final RuntimeException ignored) {
            // 类型未注册（版本差异）：落到下面的短名兜底
        }
        // ② 兜底：按类名映射到 Create 的注册 id（旧存档/异常情况下仍尽量给出可匹配的键）
        final String simple = recipe.getClass().getSimpleName();
        if (simple.contains("Pressing")) {
            return "create:pressing";
        }
        if (simple.contains("Cutting")) {
            return "create:cutting";
        }
        if (simple.contains("Deployer")) {
            return "create:deploying";
        }
        if (simple.contains("Filling")) {
            return "create:filling";
        }
        if (simple.contains("Spouting")) {
            return "create:filling";
        }
        if (simple.contains("Emptying")) {
            return "create:emptying";
        }
        return simple.replace("Recipe", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 配方转移：把 Create 序列装配配方填入序列终端（原料槽 + 流程 + 产物槽）。 */
    private static final class SequencedAssemblyTransferHandler
        implements IRecipeTransferHandler<SequencePatternTerminalMenu, RecipeHolder<SequencedAssemblyRecipe>> {

        @Override
        public Class<SequencePatternTerminalMenu> getContainerClass() {
            return SequencePatternTerminalMenu.class;
        }

        @Override
        public Optional<MenuType<SequencePatternTerminalMenu>> getMenuType() {
            return Optional.of(RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_MENU.get());
        }

        @Override
        public RecipeType<RecipeHolder<SequencedAssemblyRecipe>> getRecipeType() {
            return SEQUENCED_ASSEMBLY_TYPE;
        }

        @Override
        public IRecipeTransferError transferRecipe(final SequencePatternTerminalMenu container,
                                                   final RecipeHolder<SequencedAssemblyRecipe> holder,
                                                   final IRecipeSlotsView recipeSlots,
                                                   final Player player,
                                                   final boolean maxTransfer,
                                                   final boolean doTransfer) {
            // 类型已由注册键（容器类 + create:sequenced_assembly）钉死，这里无需再做 instanceof 兜底判断
            if (!doTransfer) {
                return null; // 预览阶段无错误
            }
            final SequencedAssemblyRecipe seq = holder.value();
            // 展开配方序列：一步一行地发给服务端，**合并交给服务端**（服务端唯一权威）。
            // 为什么不在客户端合并：合并出来的「重复次数」要写进终端的展示数据（幽灵容器）与
            // 生成时的单元样板步序，必须由服务端一处决定，否则「界面显示的 ×N」与「服务端记的 N」会分叉。
            // 服务端在 SequencePatternTerminalBlockEntity#applyRecipeToArrangement 里把
            // **相邻且完全相同**（配方 id / 配方类型 / 输入物 / 输入流体 / 绑定仓全同）的步骤合并成一列
            // 并累加计数；该列保留这段的第一级步序，因此配方序列下标不丢，
            // 执行仓「按配方类型推步」的归属判定照样认下重复出来的那几级（坚固板第 2、3 步都是冲压 →
            // 都归那台冲压仓），加工链路一路做到底、不会出现 step_not_mine 卡死。
            final List<String> machines = new ArrayList<>();
            final List<ItemStack> stepInputs = new ArrayList<>();
            // 每步输入原料的「整组候选」（标签型 ingredient 的全部候选，如列车轨道机械手步的铁粒 / 锌粒）。
            // 与 stepInputs（代表物一件）平行下发，服务端落进单元样板 / 总样板的 InputCandidates。
            final List<java.util.List<ItemStack>> stepCandidates = new ArrayList<>();
            // 每步的输入流体（来自 Create 配方的流体 ingredient）：导入即自动标注，无需玩家手拖
            final List<net.neoforged.neoforge.fluids.FluidStack> stepFluids = new ArrayList<>();
            final List<Integer> stepCounts = new ArrayList<>();
            for (final SequencedRecipe<?> step : seq.getSequence()) {
                final ProcessingRecipe<?, ?> proc = step.getRecipe();
                final String machine = machineKeyFor(proc);
                // 输入物必须跳过被 Create 覆盖成「过渡件」的第 0 个 ingredient（见 SequencedRecipeProbe）
                final ItemStack stepInput = cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                    .stepInput(proc);
                final net.neoforged.neoforge.fluids.FluidStack stepFluid =
                    cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.firstFluid(proc);
                machines.add(machine);
                stepInputs.add(stepInput);
                // 候选组：把代表物所属那一组整组带下去（单候选的步 = 只有代表物一件，与既有行为一字不差）
                stepCandidates.add(cretae.cookiewyq.rs_create_compat.data.SequencePatternData.candidatesOfItems(
                    cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                        .applicationCandidatesOf(proc, stepInput)));
                stepFluids.add(stepFluid);
                stepCounts.add(1); // 一步一行；相邻完全相同的步骤由服务端合并成「一列 + 重复次数 N」
            }
            final Ingredient input = seq.getIngredient();
            final ItemStack ingredient = input.getItems().length > 0
                ? input.getItems()[0].copy() : ItemStack.EMPTY;
            final int loops = Math.max(1, seq.getLoops()); // Create 配方整体循环次数
            // 产物池：Create 的 resultPool 里 chance 是<b>权重</b>（如 120 / 8 / 5），不是概率。
            // 统一走 SequencedRecipeProbe：主产物 = 第一项、其余 = 废料；概率一律 = 权重 / 权重和
            // （与 Create JEI 的 getOutputChance 完全同口径）；物品数量照抄 getStack().getCount()。
            final List<ItemStack> results = new ArrayList<>();
            final List<Integer> resultChances = new ArrayList<>();
            final List<ItemStack> scraps = new ArrayList<>();
            final List<Integer> scrapChances = new ArrayList<>();
            try {
                final cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.Split split =
                    cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                        .splitResultPool(seq.resultPool);
                for (final cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.Output output
                    : split.results()) {
                    results.add(output.stack());
                    resultChances.add(Math.round(output.chance() * 100F));
                }
                for (final cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.Output output
                    : split.scraps()) {
                    scraps.add(output.stack());
                    scrapChances.add(Math.round(output.chance() * 100F));
                }
            } catch (final RuntimeException ignored) {
                // 配方结构异常（版本差异）：退化为只导入主产物
            }
            if (results.isEmpty()) {
                final ItemStack main = seq.getResultItem(player.level().registryAccess()).copy();
                if (!main.isEmpty()) {
                    results.add(main);
                    resultChances.add(100);
                }
            }
            // 起步原料的全部候选（「任意台阶」一类）：代表物在首位，整组随导入下发。
            // 没有它，顶部「输入原料」格与 RS 样板都只能看到一件（用户实测：「还是只写是个石头台阶」）。
            final List<ItemStack> ingredientCandidates =
                cretae.cookiewyq.rs_create_compat.data.SequencePatternData.candidatesOfItems(
                    cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.candidates(input));
            PacketDistributor.sendToServer(new cretae.cookiewyq.rs_create_compat.network.SetSequenceImportPacket(
                container.containerId, holder.id(), machines, stepInputs, stepCandidates, stepFluids, stepCounts,
                loops, ingredient, ingredientCandidates, results, resultChances, scraps, scrapChances));
            return null;
        }
    }

    /** ghost 放置目标：槽位。 */
    private static final class GhostHandler
        implements mezz.jei.api.gui.handlers.IGhostIngredientHandler<SequencePatternTerminalScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(final SequencePatternTerminalScreen screen,
                                                   final ITypedIngredient<I> typedIngredient,
                                                   final boolean doStart) {
            final I ingredient = typedIngredient.getIngredient();
            final boolean isItem = ingredient instanceof ItemStack;
            final boolean isFluid = ingredient instanceof net.neoforged.neoforge.fluids.FluidStack;
            if (!isItem && !isFluid) {
                return List.of(); // 只支持物品与流体（流体 = 注液器一类步骤的输入原料）
            }
            final List<Target<I>> targets = new ArrayList<>();
            // v6 起流程编排 / 产物 / 废料全部是「配方自动生成」的只读展示；
            // 「输入原料」槽也改为只读（值由导入 / 生成逻辑写入，玩家手动标记没有意义），
            // 因此本界面<b>不再提供任何 JEI ghost 放置目标</b>。
            return targets;
        }

        /** 物品 ghost 目标：按菜单槽位下标写入（流程行 / 输入槽 / 产物废料）。 */
        private <I> void addTarget(final List<Target<I>> targets,
                                   final SequencePatternTerminalScreen screen,
                                   final SequencePatternTerminalMenu menu,
                                   final Slot slot,
                                   final int slotId,
                                   final I ingredient) {
            targets.add(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return new Rect2i(
                        screen.getGuiLeft() + slot.x - 1,
                        screen.getGuiTop() + slot.y - 1,
                        18, 18);
                }

                @Override
                public void accept(final I accepted) {
                    if (accepted instanceof ItemStack stack) {
                        PacketDistributor.sendToServer(
                            new cretae.cookiewyq.rs_create_compat.network.SetSequenceGhostPacket(
                                menu.containerId, slotId, stack));
                    }
                }
            });
        }

        /** 流体 ghost 目标：按<b>全局步骤下标</b>写入该步的输入流体标记。 */
        private <I> void addFluidTarget(final List<Target<I>> targets,
                                        final SequencePatternTerminalScreen screen,
                                        final Slot slot,
                                        final int globalStep,
                                        final I ingredient) {
            targets.add(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return new Rect2i(
                        screen.getGuiLeft() + slot.x - 1,
                        screen.getGuiTop() + slot.y - 1,
                        18, 18);
                }

                @Override
                public void accept(final I accepted) {
                    if (accepted instanceof net.neoforged.neoforge.fluids.FluidStack fluid) {
                        PacketDistributor.sendToServer(
                            new cretae.cookiewyq.rs_create_compat.network.SetStepFluidPacket(
                                globalStep, fluid.copyWithAmount(Math.max(1, fluid.getAmount()))));
                    }
                }
            });
        }

        @Override
        public void onComplete() {
            // 无需额外处理
        }
    }
}
