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
 *     对非 {@code minecraft:crafting} 的配方类型一律忽略，避免把工作台误当成任意机器的机器。</li>
 * </ol>
 * <p><b>兜底</b>：两条来源都拿不到机器时返回空列表，调用方回退显示配方类型本身的名称
 * （<b>绝不</b>退化成显示原料 / 产物）。</p>
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
        final List<Machine> machines = new ArrayList<>();
        if (recipeTypeId == null || recipeTypeId.isEmpty()) {
            return machines;
        }
        final Set<Item> seen = new LinkedHashSet<>();
        // ① 显式机器表（Create 官方 JEI 催化剂 + 原版机器）
        for (final ItemLike machine : explicitTable().getOrDefault(recipeTypeId, List.of())) {
            addMachine(machines, seen, new ItemStack(machine));
        }
        // ② 配方自带 getToastSymbol：第三方模组的机器（如更高级的熔炉）会在这里被自动收进来
        for (final ItemStack symbol : symbolsFromRecipes(recipeTypeId)) {
            addMachine(machines, seen, symbol);
        }
        return machines;
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
                final ItemStack symbol;
                try {
                    symbol = holder.value().getToastSymbol();
                } catch (final Throwable ignored) {
                    continue; // 个别配方缺少上下文时会抛异常：跳过这一条
                }
                if (symbol == null || symbol.isEmpty()) {
                    continue;
                }
                // 未覆写 getToastSymbol 时返回的是原版默认工作台：对非合成配方类型视为“无信息”
                if (!craftableSymbolAllowed && symbol.is(DEFAULT_SYMBOL)) {
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

    /** 定长机器列表（显式声明 {@code List<ItemLike>}，避免 {@code List.of} 的元素类型推断歧义）。 */
    private static List<ItemLike> machines(final ItemLike... machines) {
        return List.of(machines);
    }
}
