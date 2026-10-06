package cretae.cookiewyq.rs_create_compat.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列装配样板的数据模型（以 NBT 序列化，存储于物品的 CustomData 组件）。
 * <ul>
 *     <li>单元样板（sequence_unit_pattern）：单步操作（机器类型 + 可选加工输入）。</li>
 *     <li>装配样板（sequence_assembly_pattern）：原料 + 有序单元步骤（各带重复次数）+
 *     循环次数 + 概率产物池 + 概率废料池。</li>
 * </ul>
 * 所有 ItemStack 序列化均需传入 {@link HolderLookup.Provider}（1.21.1 API）。
 */
public final class SequencePatternData {
    public static final String TAG_MACHINE = "Machine";
    public static final String TAG_INPUT = "Input";
    public static final String TAG_COUNT = "Count";
    public static final String TAG_CRAFTER = "Crafter"; // 执行该步的自动合成仓名称（每单元绑定一台）
    public static final String TAG_RECIPE = "Recipe";   // 所属 Create 序列装配配方 id（ResourceLocation 字符串）
    public static final String TAG_STEP = "Step";       // 该单元在配方“展开总步”里的下一步下标（0-based）
    public static final String TAG_UNITS = "Units";
    public static final String TAG_INGREDIENT = "Ingredient";
    public static final String TAG_LOOPS = "Loops";
    public static final String TAG_RESULTS = "Results";
    public static final String TAG_SCRAPS = "Scraps";
    public static final String TAG_CHANCE = "Chance";
    // ===== 新语义（序列装配 v4）追加 tag：单元样板三项基础信息 + 装配步骤的机器指派 =====
    /** 新语义：该单元是否需要输入原料（boolean）。 */
    public static final String TAG_REQUIRES_INPUT = "RequiresInput";
    /** 新语义：用户自定义的单元显示名（可空）。 */
    public static final String TAG_DISPLAY_NAME = "DisplayName";
    /** 新语义：配方类型 id（如 {@code create:pressing} / {@code minecraft:smelting}）。 */
    public static final String TAG_RECIPE_TYPE = "RecipeType";
    /** 装配步骤指派的机器坐标（以 {@code BlockPos#asLong()} 存储；缺省 = 无指派）。 */
    public static final String TAG_MACHINE_POS = "MachinePos";
    /** 装配步骤指派的机器名（可空；缺省 = 无指派）。 */
    public static final String TAG_MACHINE_NAME = "MachineName";
    /** 单元样板标记的「输入流体」（流体序列化后的 CompoundTag；缺省 = 无标记）。 */
    public static final String TAG_INPUT_FLUID = "InputFluid";
    /**
     * 该步「输入原料组」的<b>全部候选</b>（标签 / 多值 ingredient 展开；首个 = {@link #TAG_INPUT} 的代表物）。
     *
     * <h2>为什么必须新增它（列车轨道单元样板：铁粒 / 锌粒候选未显示）</h2>
     * <p>Create 的 {@code create:sequenced_assembly/track} 机械手步投入物是标签
     * {@code [c:nuggets/iron, c:nuggets/zinc]}（铁粒<b>或</b>锌粒）。此前整条链只有
     * {@link #TAG_INPUT} 这一个单值 {@link ItemStack}（并且由 {@code SequencedRecipeProbe#stepInput}
     * 取「首个候选」写入），于是<b>候选组在三处被压成一件</b>：
     * ① 单元样板物品自身（tooltip 只见铁粒）；
     * ② 总样板 → {@code SequenceAssemblyPatternItem#buildPattern} 生成的 RS EXTERNAL 样板
     * （实机日志里 {@code create:track} 样板的 ingredients 只有 {@code minecraft:iron_nugget}，
     * 锌粒根本没被登记 ⇒ RS 自动合成预览/任务永远不知道锌粒也能用）；
     * ③ 单元样板 NBT 一旦脱离配方（拆下的样板物品）就再也回推不出候选组。</p>
     * <p>因此把整组候选随单元样板 / 总样板一起落盘：{@link ItemStack} 列表（数量恒为 1），
     * 空列表 = 老样板（读不出来，调用方按「只有代表物」优雅降级）。</p>
     */
    public static final String TAG_INPUT_CANDIDATES = "InputCandidates";

    private SequencePatternData() {
    }

    // ========== 单元样板 ==========

    /**
     * 单元样板数据。
     * <p><b>新语义（v4）</b>：只记录三项基础信息 —— {@link #requiresInput()}（是否需要输入原料）、
     * {@link #recipeType()}（配方类型 id）、{@link #displayName()}（用户自定义名）。
     * 不再绑定具体机器。</p>
     * <p>旧的 {@code machine}/{@code crafter}/{@code recipe}/{@code step} 分量保留仅为旧存档与既有引擎兼容：
     * {@code crafter} 是旧的「绑定机器名」，新逻辑不再使用。</p>
     */
    public record UnitData(String machine, ItemStack input,
                           @Deprecated String crafter, String recipe, int step,
                           boolean requiresInput, String displayName, String recipeType,
                           /** 该步输入原料组的全部候选（空 = 老样板 / 单件；调用方用 {@link #candidatesOf} 兜底）。 */
                           List<ItemStack> inputCandidates) {
        /** 归一化：{@code candidates} 为 {@code null} 时视为空（老样板语义），并只保留「非空 + 按物品种类去重」的项。 */
        public UnitData {
            inputCandidates = normalizeCandidates(inputCandidates);
        }

        public UnitData(final String machine, final ItemStack input) {
            this(machine, input, "", "", -1, false, "", "", List.of());
        }

        public UnitData(final String machine, final ItemStack input, final String crafter) {
            this(machine, input, crafter, "", -1, false, "", "", List.of());
        }

        /** 旧 5 参构造器（保留，供既有代码编译）；新语义字段取缺省值。 */
        public UnitData(final String machine, final ItemStack input, final String crafter,
                        final String recipe, final int step) {
            this(machine, input, crafter, recipe, step, false, "", "", List.of());
        }

        /** 旧 8 参构造器（无候选组）：等价于只有代表物一件。 */
        public UnitData(final String machine, final ItemStack input, final String crafter,
                        final String recipe, final int step, final boolean requiresInput,
                        final String displayName, final String recipeType) {
            this(machine, input, crafter, recipe, step, requiresInput, displayName, recipeType, List.of());
        }

        /** 该步输入原料组的候选（永不为空列表以外的 {@code null}；缺候选时退回代表物一件）。 */
        public List<ItemStack> candidatesOrRepresentative() {
            if (!inputCandidates.isEmpty()) {
                return inputCandidates;
            }
            return input == null || input.isEmpty() ? List.of() : List.of(input);
        }
    }

    /**
     * 新语义下单元样板的三项基础信息（{@link #requiresInput()} / {@link #displayName()} / {@link #recipeType()}）。
     * 与 {@link UnitData} 的关系：UnitData 是含旧字段的完整容器，UnitMeta 是独立读写这三项的轻量视图。
     */
    public record UnitMeta(boolean requiresInput, String displayName, String recipeType) {
        public static final UnitMeta EMPTY = new UnitMeta(false, "", "");
    }

    /** 新语义：读取单元样板的「是否需要输入 / 显示名 / 配方类型」三项（缺 tag 取缺省）。 */
    public static UnitMeta readUnitNew(final ItemStack stack, final HolderLookup.Provider registries) {
        return readUnitMeta(customDataOf(stack));
    }

    /** 新语义：写入单元样板的「是否需要输入 / 显示名 / 配方类型」三项（不动其它 tag）。 */
    public static void writeUnitNew(final ItemStack stack, final UnitMeta meta, final HolderLookup.Provider registries) {
        writeUnitMeta(stack, meta);
    }

    /** 从 CompoundTag 读取新语义三项（缺 tag 取缺省）。 */
    public static UnitMeta readUnitMeta(final CompoundTag data) {
        if (data == null) {
            return UnitMeta.EMPTY;
        }
        return new UnitMeta(
            data.getBoolean(TAG_REQUIRES_INPUT),
            data.contains(TAG_DISPLAY_NAME) ? data.getString(TAG_DISPLAY_NAME) : "",
            data.contains(TAG_RECIPE_TYPE) ? data.getString(TAG_RECIPE_TYPE) : ""
        );
    }

    /** 把新语义三项写入物品的 CustomData（不动其它 tag）。 */
    public static void writeUnitMeta(final ItemStack stack, final UnitMeta meta) {
        if (stack.isEmpty()) {
            return;
        }
        final UnitMeta value = meta == null ? UnitMeta.EMPTY : meta;
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> {
                tag.putBoolean(TAG_REQUIRES_INPUT, value.requiresInput());
                tag.putString(TAG_DISPLAY_NAME, value.displayName() == null ? "" : value.displayName());
                tag.putString(TAG_RECIPE_TYPE, value.recipeType() == null ? "" : value.recipeType());
            }));
    }

    /**
     * 归一化「输入原料组候选」：剔除空栈、按<b>物品种类</b>去重（保序 —— 首个就是优先消耗的候选）、
     * 每件数量一律归一为 1（数量属于「用多少」，由 loops × 步重复次数决定，见
     * {@code SequenceAssemblyPatternItem#collectInputs}）。{@code null} 视为空表。
     */
    public static List<ItemStack> normalizeCandidates(final List<ItemStack> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        final List<ItemStack> result = new ArrayList<>(candidates.size());
        for (final ItemStack stack : candidates) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (final ItemStack existing : result) {
                if (existing.is(stack.getItem())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(stack.copyWithCount(1));
            }
        }
        return List.copyOf(result);
    }

    /** {@code List<Item>} → 候选 {@link ItemStack} 列表（数量 1；供只有物品种类的地方构造候选组）。 */
    public static List<ItemStack> candidatesOfItems(final List<Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        final List<ItemStack> result = new ArrayList<>(items.size());
        for (final Item item : items) {
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                result.add(new ItemStack(item));
            }
        }
        return normalizeCandidates(result);
    }

    /**
     * 读「输入原料组候选」（tag 不存在 / 全部解析失败 ⇒ 空表，调用方按「只有代表物」降级）。
     * <p>兼容 {@link ItemStack} 的完整 NBT 形态（{@code ItemStack#saveOptional}），
     * 因此带数据组件的候选（附魔书一类）也能原样保留。</p>
     */
    public static List<ItemStack> readCandidates(final CompoundTag data,
                                                 final HolderLookup.Provider registries) {
        if (data == null || !data.contains(TAG_INPUT_CANDIDATES, Tag.TAG_LIST)) {
            return List.of();
        }
        final ListTag list = data.getList(TAG_INPUT_CANDIDATES, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return List.of();
        }
        final List<ItemStack> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            result.add(ItemStack.parseOptional(registries, list.getCompound(i)));
        }
        return normalizeCandidates(result);
    }

    /**
     * 写「输入原料组候选」：<b>只在候选数 ≥ 2 时</b>写 tag —— 单件 / 老样板保持原格式（tag 不存在），
     * 因此读回来时 {@code UnitData#candidatesOrRepresentative()} 会优雅退回代表物一件。
     */
    public static void writeCandidates(final CompoundTag data, final List<ItemStack> candidates,
                                       final HolderLookup.Provider registries) {
        if (data == null) {
            return;
        }
        final List<ItemStack> normalized = normalizeCandidates(candidates);
        if (normalized.size() < 2) {
            return;
        }
        final ListTag list = new ListTag();
        for (final ItemStack stack : normalized) {
            list.add(stack.saveOptional(registries));
        }
        data.put(TAG_INPUT_CANDIDATES, list);
    }

    /** 取物品 CustomData 的底层 CompoundTag（空/无数据返回 {@code null}）。 */
    @Nullable
    private static CompoundTag customDataOf(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final CompoundTag data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe();
        return data.isEmpty() ? null : data;
    }

    /** 从物品的 CustomData 中读取单元数据（无则返回 null）。 */
    public static UnitData readUnit(final ItemStack stack, final HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return null;
        }
        final CompoundTag data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe();
        if (!data.contains(TAG_MACHINE)) {
            return null;
        }
        final String machine = data.getString(TAG_MACHINE);
        final ItemStack input = data.contains(TAG_INPUT)
            ? ItemStack.parseOptional(registries, data.getCompound(TAG_INPUT))
            : ItemStack.EMPTY;
        final String crafter = data.contains(TAG_CRAFTER) ? data.getString(TAG_CRAFTER) : "";
        final String recipe = data.contains(TAG_RECIPE) ? data.getString(TAG_RECIPE) : "";
        final int step = data.contains(TAG_STEP) ? data.getInt(TAG_STEP) : -1;
        final UnitMeta meta = readUnitMeta(data); // 新语义三项（旧样板无这些 tag 时取缺省）
        // 输入原料组的候选（老样板无此 tag ⇒ 空表；调用方 UnitData#candidatesOrRepresentative 退回代表物）
        final List<ItemStack> candidates = readCandidates(data, registries);
        return new UnitData(machine, input, crafter, recipe, step,
            meta.requiresInput(), meta.displayName(), meta.recipeType(), candidates);
    }

    /** 写入单元数据到物品。 */
    public static void writeUnit(final ItemStack stack, final UnitData unit, final HolderLookup.Provider registries) {
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> {
                tag.putString(TAG_MACHINE, unit.machine());
                if (unit.input() != null && !unit.input().isEmpty()) {
                    tag.put(TAG_INPUT, unit.input().saveOptional(registries));
                }
                // 输入原料组的全部候选（标签型 ingredient，如列车轨道的「铁粒 或 锌粒」）：
                // 只登记「候选数 ≥ 2」时的那一份 —— 单件与老样板完全同格式（不写这个 tag），
                // 因此老存档读回来依旧只有代表物，绝不会因为新增字段而改变既有语义。
                writeCandidates(tag, unit.inputCandidates(), registries);
                if (unit.crafter() != null && !unit.crafter().isEmpty()) {
                    tag.putString(TAG_CRAFTER, unit.crafter());
                }
                if (unit.recipe() != null && !unit.recipe().isEmpty()) {
                    tag.putString(TAG_RECIPE, unit.recipe());
                }
                if (unit.step() >= 0) {
                    tag.putInt(TAG_STEP, unit.step());
                }
                // 新语义三项（追加写；旧代码传入的 UnitData 里这三项为缺省则等同不写）
                if (unit.requiresInput()) {
                    tag.putBoolean(TAG_REQUIRES_INPUT, true);
                }
                if (unit.displayName() != null && !unit.displayName().isEmpty()) {
                    tag.putString(TAG_DISPLAY_NAME, unit.displayName());
                }
                if (unit.recipeType() != null && !unit.recipeType().isEmpty()) {
                    tag.putString(TAG_RECIPE_TYPE, unit.recipeType());
                }
            }));
    }

    /**
     * 读取单元样板标记的「输入流体」（注液器 / 灌注一类步骤需要输入流体；无标记返回
     * {@link net.neoforged.neoforge.fluids.FluidStack#EMPTY}）。
     */
    public static net.neoforged.neoforge.fluids.FluidStack readUnitFluid(
        final ItemStack stack, final HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        final CompoundTag data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe();
        if (!data.contains(TAG_INPUT_FLUID, Tag.TAG_COMPOUND)) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        return net.neoforged.neoforge.fluids.FluidStack.parseOptional(
            registries, data.getCompound(TAG_INPUT_FLUID));
    }

    /**
     * 写入 / 清除单元样板标记的「输入流体」（传入空栈即删除该 tag）。
     * <p>只写这一项标记数据，不触碰单元样板的其余内容。</p>
     */
    public static void writeUnitFluid(final ItemStack stack,
                                      final net.neoforged.neoforge.fluids.FluidStack fluid,
                                      final HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return;
        }
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> {
                if (fluid == null || fluid.isEmpty()) {
                    tag.remove(TAG_INPUT_FLUID);
                } else {
                    tag.put(TAG_INPUT_FLUID, fluid.saveOptional(registries));
                }
            }));
    }

    // ========== 装配样板 ==========

    /**
     * 装配样板中的一步：操作 + 重复次数 + 可选加工输入 + 绑定自动合成仓 + 配方定位（recipeId/step）。
     * <p><b>新语义（v4）追加</b>：该步指派的机器 —— {@link #machinePos()}（可为空 = 无指派）、
     * {@link #machineName()}（可空）、{@link #recipeType()}（该步的配方类型 id）。</p>
     * <p><b>输入流体</b>（{@link #inputFluid()}）：灌注 / 注液一类步骤真正消耗的流体（按 mB）。
     * 它必须随总样板一起落到物品上，否则 RS 自动合成计算器不知道这条样板还需要流体，
     * 「自动合成预览」就不会把缺岩浆报出来（用户实测问题）。缺省 = {@code FluidStack.EMPTY}。</p>
     */
    public record UnitEntry(String machine, int count, ItemStack input,
                            @Deprecated String crafter, String recipe, int step,
                            @Nullable BlockPos machinePos, String machineName, String recipeType,
                            net.neoforged.neoforge.fluids.FluidStack inputFluid,
                            /** 该步输入原料组的全部候选（空 = 老样板 / 单件；见 {@link UnitData#inputCandidates()}）。 */
                            List<ItemStack> inputCandidates) {
        /** 归一化：{@code null} 视为空表（老样板语义）。 */
        public UnitEntry {
            inputCandidates = normalizeCandidates(inputCandidates);
        }

        public UnitEntry(final String machine, final int count, final ItemStack input) {
            this(machine, count, input, "", "", -1, null, "", "",
                net.neoforged.neoforge.fluids.FluidStack.EMPTY, List.of());
        }

        public UnitEntry(final String machine, final int count, final ItemStack input, final String crafter) {
            this(machine, count, input, crafter, "", -1, null, "", "",
                net.neoforged.neoforge.fluids.FluidStack.EMPTY, List.of());
        }

        /** 旧 6 参构造器（保留，供既有代码编译）；新语义字段取缺省值（无机器指派）。 */
        public UnitEntry(final String machine, final int count, final ItemStack input, final String crafter,
                         final String recipe, final int step) {
            this(machine, count, input, crafter, recipe, step, null, "", "",
                net.neoforged.neoforge.fluids.FluidStack.EMPTY, List.of());
        }

        /** 旧 9 参构造器（v4 机器指派）：输入流体缺省为空（旧样板无流体标记）。 */
        public UnitEntry(final String machine, final int count, final ItemStack input, final String crafter,
                         final String recipe, final int step, @Nullable final BlockPos machinePos,
                         final String machineName, final String recipeType) {
            this(machine, count, input, crafter, recipe, step, machinePos, machineName, recipeType,
                net.neoforged.neoforge.fluids.FluidStack.EMPTY, List.of());
        }

        /** 10 参构造器（v4 + 输入流体）：候选组缺省（= 只有代表物一件）。 */
        public UnitEntry(final String machine, final int count, final ItemStack input, final String crafter,
                         final String recipe, final int step, @Nullable final BlockPos machinePos,
                         final String machineName, final String recipeType,
                         final net.neoforged.neoforge.fluids.FluidStack inputFluid) {
            this(machine, count, input, crafter, recipe, step, machinePos, machineName, recipeType,
                inputFluid, List.of());
        }

        /** 该步输入原料组的候选（缺候选时退回代表物一件）。 */
        public List<ItemStack> candidatesOrRepresentative() {
            if (!inputCandidates.isEmpty()) {
                return inputCandidates;
            }
            return input == null || input.isEmpty() ? List.of() : List.of(input);
        }
    }

    /** 概率产物/废料。 */
    public record PatternOutput(ItemStack stack, float chance) {
    }

    /**
     * 完整装配样板。
     *
     * <p><b>2026-10-05 新增 {@link #ingredientCandidates()}</b>：主原料（起步原料）也可能是一个
     * <b>标签型 ingredient 的多候选组</b> —— 列车轨道的起步原料是
     * {@code create:sleepers}（石头台阶 / 平滑石台阶 / 安山岩台阶）。在这之前主原料<b>只有一个
     * ItemStack 分量</b>，整组候选在 NBT 里<b>无处可存</b> ⇒ 终端顶部「输入原料」那一格永远只能显示
     * 代表物一件（用户实测：<i>「还是只写是个石头台阶」</i>）。现在与单元样板同样的做法落盘，
     * 老 NBT 缺键 ⇒ 空表 ⇒ 调用方退回代表物（与改动前一字不差）。</p>
     */
    public record AssemblyData(ItemStack ingredient, int loops,
                               List<UnitEntry> units,
                               List<PatternOutput> results,
                               List<PatternOutput> scraps,
                               /** 起步原料的全部候选（空 = 老样板 / 单件；首个 = {@link #ingredient()} 的代表物）。 */
                               List<ItemStack> ingredientCandidates) {
        /** 归一化：{@code null} 视为空表（老样板语义）。 */
        public AssemblyData {
            ingredientCandidates = normalizeCandidates(ingredientCandidates);
        }

        /*
         * 2026-10-06 删除旧 5 参便捷构造器（主原料候选缺省为空）。
         * 为什么删而不是留着：它是「静默吞掉候选组」这个坑的唯一入口 —— 监视器的「更换机器」
         * (AssemblyWatchdog#changeStepMachine) 就是用它重建总样板，结果起步原料候选组被
         * candidatesOrRepresentative() 悄悄退化成代表物一件（石头台阶那组候选消失）。
         * 与 inputCandidates 那条已修的路径同类；既然用户已否定「5 参可接受」，就让它<b>编译期不可达</b>：
         * 重建总样板只能显式给出第 6 参。老 NBT 不受影响（readAssembly 本来就显式读候选、
         * 缺键 ⇒ 空表 ⇒ candidatesOrRepresentative() 照样优雅退回代表物）。
         */

        /** 起步原料候选（缺候选时退回代表物一件）。 */
        public List<ItemStack> candidatesOrRepresentative() {
            if (!ingredientCandidates.isEmpty()) {
                return ingredientCandidates;
            }
            return ingredient == null || ingredient.isEmpty() ? List.of() : List.of(ingredient);
        }
    }

    /** 从物品读取装配样板数据（无则返回 null）。 */
    public static AssemblyData readAssembly(final ItemStack stack, final HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return null;
        }
        final CompoundTag data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe();
        if (!data.contains(TAG_UNITS)) {
            return null;
        }
        final ItemStack ingredient = data.contains(TAG_INGREDIENT)
            ? ItemStack.parseOptional(registries, data.getCompound(TAG_INGREDIENT))
            : ItemStack.EMPTY;
        final int loops = data.getInt(TAG_LOOPS);

        final List<UnitEntry> units = new ArrayList<>();
        final ListTag unitList = data.getList(TAG_UNITS, Tag.TAG_COMPOUND);
        for (int i = 0; i < unitList.size(); i++) {
            final CompoundTag unitTag = unitList.getCompound(i);
            final String machine = unitTag.getString(TAG_MACHINE);
            final int count = unitTag.getInt(TAG_COUNT);
            final ItemStack input = unitTag.contains(TAG_INPUT)
                ? ItemStack.parseOptional(registries, unitTag.getCompound(TAG_INPUT))
                : ItemStack.EMPTY;
            final String crafter = unitTag.contains(TAG_CRAFTER) ? unitTag.getString(TAG_CRAFTER) : "";
            final String recipe = unitTag.contains(TAG_RECIPE) ? unitTag.getString(TAG_RECIPE) : "";
            final int step = unitTag.contains(TAG_STEP) ? unitTag.getInt(TAG_STEP) : -1;
            // 新语义：机器指派（旧样板无这些 tag = 无指派/无配方类型）
            final BlockPos machinePos = unitTag.contains(TAG_MACHINE_POS)
                ? BlockPos.of(unitTag.getLong(TAG_MACHINE_POS)) : null;
            final String machineName = unitTag.contains(TAG_MACHINE_NAME) ? unitTag.getString(TAG_MACHINE_NAME) : "";
            final String recipeType = unitTag.contains(TAG_RECIPE_TYPE) ? unitTag.getString(TAG_RECIPE_TYPE) : "";
            // 输入流体（旧样板无此 tag = 无流体标记）
            final net.neoforged.neoforge.fluids.FluidStack inputFluid =
                unitTag.contains(TAG_INPUT_FLUID, Tag.TAG_COMPOUND)
                    ? net.neoforged.neoforge.fluids.FluidStack.parseOptional(
                        registries, unitTag.getCompound(TAG_INPUT_FLUID))
                    : net.neoforged.neoforge.fluids.FluidStack.EMPTY;
            units.add(new UnitEntry(machine, count, input, crafter, recipe, step,
                machinePos, machineName, recipeType, inputFluid,
                readCandidates(unitTag, registries)));
        }

        return new AssemblyData(ingredient, Math.max(1, loops), units,
            readOutputs(data, TAG_RESULTS, registries), readOutputs(data, TAG_SCRAPS, registries),
            readCandidates(data, registries));
    }

    private static List<PatternOutput> readOutputs(final CompoundTag data,
                                                   final String key,
                                                   final HolderLookup.Provider registries) {
        final List<PatternOutput> outputs = new ArrayList<>();
        final ListTag list = data.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag tag = list.getCompound(i);
            final ItemStack stack = ItemStack.parseOptional(registries, tag.getCompound("Item"));
            final float chance = tag.getFloat(TAG_CHANCE);
            outputs.add(new PatternOutput(stack, chance));
        }
        return outputs;
    }

    /** 写入装配样板数据到物品。 */
    public static void writeAssembly(final ItemStack stack,
                                     final AssemblyData assembly,
                                     final HolderLookup.Provider registries) {
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> {
                if (!assembly.ingredient().isEmpty()) {
                    tag.put(TAG_INGREDIENT, assembly.ingredient().saveOptional(registries));
                }
                // 起步原料的全部候选（标签型 ingredient，如「任意台阶」；候选数 < 2 时不写 = 老格式不变）
                writeCandidates(tag, assembly.ingredientCandidates(), registries);
                tag.putInt(TAG_LOOPS, Math.max(1, assembly.loops()));
                final ListTag unitList = new ListTag();
                for (final UnitEntry unit : assembly.units()) {
                    final CompoundTag unitTag = new CompoundTag();
                    unitTag.putString(TAG_MACHINE, unit.machine());
                    unitTag.putInt(TAG_COUNT, Math.max(1, unit.count()));
                    if (unit.input() != null && !unit.input().isEmpty()) {
                        unitTag.put(TAG_INPUT, unit.input().saveOptional(registries));
                    }
                    // 输入原料组的全部候选（标签型 ingredient；候选数 < 2 时不写 tag = 与老格式完全一致）
                    writeCandidates(unitTag, unit.inputCandidates(), registries);
                    if (unit.crafter() != null && !unit.crafter().isEmpty()) {
                        unitTag.putString(TAG_CRAFTER, unit.crafter());
                    }
                    if (unit.recipe() != null && !unit.recipe().isEmpty()) {
                        unitTag.putString(TAG_RECIPE, unit.recipe());
                    }
                    if (unit.step() >= 0) {
                        unitTag.putInt(TAG_STEP, unit.step());
                    }
                    // 新语义：机器指派（machinePos 以 long 存储，读写保持一致）
                    if (unit.machinePos() != null) {
                        unitTag.putLong(TAG_MACHINE_POS, unit.machinePos().asLong());
                    }
                    if (unit.machineName() != null && !unit.machineName().isEmpty()) {
                        unitTag.putString(TAG_MACHINE_NAME, unit.machineName());
                    }
                    if (unit.recipeType() != null && !unit.recipeType().isEmpty()) {
                        unitTag.putString(TAG_RECIPE_TYPE, unit.recipeType());
                    }
                    // 输入流体（按 mB 存；空栈不写 tag，保持旧格式可读）
                    if (unit.inputFluid() != null && !unit.inputFluid().isEmpty()) {
                        unitTag.put(TAG_INPUT_FLUID, unit.inputFluid().saveOptional(registries));
                    }
                    unitList.add(unitTag);
                }
                tag.put(TAG_UNITS, unitList);
                tag.put(TAG_RESULTS, writeOutputs(assembly.results(), registries));
                tag.put(TAG_SCRAPS, writeOutputs(assembly.scraps(), registries));
            }));
    }

    private static ListTag writeOutputs(final List<PatternOutput> outputs,
                                        final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final PatternOutput output : outputs) {
            final CompoundTag tag = new CompoundTag();
            tag.put("Item", output.stack().saveOptional(registries));
            tag.putFloat(TAG_CHANCE, output.chance());
            list.add(tag);
        }
        return list;
    }
}
