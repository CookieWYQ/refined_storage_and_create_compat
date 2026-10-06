package cretae.cookiewyq.rs_create_compat.item;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 序列装配单元样板：代表序列装配中的单个操作步骤。
 * <p><b>新语义（v4）</b>：只记录三项基础信息 —— 名字（{@code DisplayName}）、配方类型
 * （{@code RecipeType}，如 {@code create:pressing}）、是否需要输入原料（{@code RequiresInput}），
 * 外加可选的输入物标记；<b>不再绑定具体机器</b>。数据以 NBT 存于 CustomData，
 * 通过 {@link SequencePatternData} 读写。</p>
 * <p>无工作台合成配方，仅由序列装配样板终端生成。</p>
 */
public class SequenceUnitPatternItem extends Item {
    private static final String LANG = "item.rs_create_compat.sequence_unit_pattern.";

    public SequenceUnitPatternItem(final Properties properties) {
        super(properties);
    }

    /**
     * 类型判定：这张物品是不是「序列装配单元样板」。
     *
     * <p><b>为什么用 {@code instanceof}（物品类型）而不是注册名 / 显示名 / 语言键</b>：
     * 注册名会随改名漂移、显示名会随语言包与玩家自定义命名漂移，只有物品类型是
     * <b>稳定且不误伤</b>的判据。本判定与 {@link SequenceAssemblyPatternItem#isAssemblyPattern}
     * 互斥，两者共同构成「哪一格只收哪一类样板」的唯一口径。</p>
     *
     * <p><b>语义边界（用户要求，切勿修反）</b>：单元样板<b>不绑定执行仓 / 机器</b>，因此
     * 它<b>可以自由放置</b>（背包、执行舱单元槽、终端单元样板库、箱子……）；
     * 被本判定挡住的<b>只有</b>「放进总样板专用容器」这一条（见
     * {@code SequenceAssemblyExecutorBlockEntity#acceptsPattern}）。</p>
     */
    public static boolean isUnitPattern(final ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SequenceUnitPatternItem;
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        final HolderLookup.Provider registries = context.level() != null
            ? context.level().registryAccess() : RegistryAccess.EMPTY;
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, registries);
        if (unit == null) {
            // 常显：空白样板必须一眼看得出「它没内容」，否则玩家会以为是界面没加载
            tooltip.add(Component.translatable(LANG + "empty").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        // 本模组附加信息<b>默认收起</b>，按住修饰键才展开（唯一实现 RsccTooltipLayers，物品级与屏幕级同一套）：
        //   Shift = 这是什么（配方类型的本地化名）；
        //   Alt   = 内部标识（配方类型 id、输入物 id）；
        //   数量维度由 tooltip 图标区的「输入流体」行承担（按 Ctrl 显示，见 UnitPatternTooltipComponent）。
        final List<RsccTooltipLayers.Line> layered = new java.util.ArrayList<>(4);
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "recipe_type",
            unit.recipeType() == null || unit.recipeType().isEmpty()
                ? Component.translatable(LANG + "unset")
                : Component.literal(recipeTypeName(unit.recipeType())))
            .withStyle(ChatFormatting.AQUA)));
        // 原始配方类型 id（暗灰+斜体）：用户要求 id 与名字都要显示，不能只显示名字
        if (unit.recipeType() != null && !unit.recipeType().isEmpty()) {
            layered.add(RsccTooltipLayers.alt(RecipeTypeNames.idLine(unit.recipeType())));
        }
        // 输入原料的物品 id（名字由 tooltip 图标区给出，这里补 id）
        if (unit.input() != null && !unit.input().isEmpty()) {
            layered.add(RsccTooltipLayers.alt(RecipeTypeNames.idLine(itemId(unit.input()))));
        }
        RsccTooltipLayers.append(tooltip, layered);
    }

    /** 输入物的注册 id（{@code namespace:path}）；查不到时返回空串（idLine 会显示「未设置」灰字）。 */
    private static String itemId(final ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * tooltip 的「代表物品」图标轮播（JEI 风格）：由配方类型 id + 本单元输入物 + 本单元的输入流体决定，
     * 客户端工厂（{@code ClientInit#onRegisterTooltipFactories}）把它映射为
     * {@code UnitPatternTooltipComponent}。三者都为空时不显示图标行。
     * <p><b>输入原料组的全部候选</b>（标签型 ingredient，如列车轨道机械手步的「铁粒 或 锌粒」）一并带出去：
     * 候选组随单元样板 NBT 落盘（见 {@code SequencePatternData#TAG_INPUT_CANDIDATES}），
     * 因此单元样板一旦脱离终端 / 配方，tooltip 依旧能把整组候选轮播出来（老样板只有代表物一件）。</p>
     */
    @Override
    public java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> getTooltipImage(
        final ItemStack stack) {
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, RegistryAccess.EMPTY);
        if (unit == null) {
            return java.util.Optional.empty();
        }
        final String recipeType = unit.recipeType() == null ? "" : unit.recipeType();
        final ItemStack input = unit.input() == null ? ItemStack.EMPTY : unit.input();
        // 输入原料组的候选（缺候选时 = 只有代表物一件；与 input 同源，绝不出现「有候选但代表物为空」）
        final java.util.List<ItemStack> candidates = unit.candidatesOrRepresentative();
        // 输入流体（注液 / 灌注一类步骤）：与物品输入同一行渲染方式（图标 + 「输入流体：<名> <数量>」）
        final net.neoforged.neoforge.fluids.FluidStack inputFluid =
            SequencePatternData.readUnitFluid(stack, RegistryAccess.EMPTY);
        if (recipeType.isEmpty() && input.isEmpty() && inputFluid.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new IconTooltip(recipeType, input.copy(), inputFluid, candidates));
    }

    /** 图标轮播 tooltip 的数据载体（仅承载数据，渲染交给客户端组件工厂）。 */
    public record IconTooltip(String recipeType, ItemStack input,
                              net.neoforged.neoforge.fluids.FluidStack inputFluid,
                              /** 该步输入原料组的全部候选（≥1 项；首个 = {@link #input} 的代表物）。 */
                              java.util.List<ItemStack> inputCandidates)
        implements net.minecraft.world.inventory.tooltip.TooltipComponent {
        /** 归一化：{@code null} ⇒ 空表（渲染方据此退回代表物一件）。 */
        public IconTooltip {
            inputCandidates = inputCandidates == null ? java.util.List.of() : java.util.List.copyOf(inputCandidates);
        }

        /** 兼容旧 3 参构造（无候选信息）：等价于只有代表物一件。 */
        public IconTooltip(final String recipeType, final ItemStack input,
                           final net.neoforged.neoforge.fluids.FluidStack inputFluid) {
            this(recipeType, input, inputFluid,
                input == null || input.isEmpty() ? java.util.List.of() : java.util.List.of(input));
        }
    }

    /**
     * 配方类型的中文显示名：① 旧机器类型键 {@code machine.rs_create_compat.Pressing}；
     * ② 模组语言文件的配方类型键（{@code create.recipe.pressing} / {@code recipe.create.pressing}）；
     * 都没有则回退原串（服务端安全：{@link net.minecraft.locale.Language} 为通用类，非客户端专属）。
     */
    private static String recipeTypeName(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return "";
        }
        final int colon = recipeType.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : recipeType.substring(0, colon);
        final String path = colon < 0 ? recipeType : recipeType.substring(colon + 1);
        final net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
        for (final String key : new String[] {
            "machine.rs_create_compat." + capitalize(path),
            namespace + ".recipe." + path,
            "recipe." + namespace + "." + path}) {
            if (language.has(key)) {
                return language.getOrDefault(key, recipeType);
            }
        }
        return recipeType;
    }

    /** 首字母大写（旧机器类型键使用 {@code Pressing} / {@code Cutting} 这类形式）。 */
    private static String capitalize(final String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // ---------- 还原为空白 RS 样板（Shift + 右键，无提示，行为对齐 RS 原版） ----------

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
        final net.minecraft.world.level.Level level,
        final net.minecraft.world.entity.player.Player player,
        final net.minecraft.world.InteractionHand hand) {
        return SampleItemConversions.use(level, player, hand);
    }

    @Override
    public net.minecraft.world.InteractionResult useOn(final net.minecraft.world.item.context.UseOnContext context) {
        final net.minecraft.world.entity.player.Player player = context.getPlayer();
        if (player == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        return SampleItemConversions.useOn(context.getLevel(), player, context.getHand());
    }
}
