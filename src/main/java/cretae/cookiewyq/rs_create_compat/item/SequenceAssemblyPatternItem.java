package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder;
import com.refinedmods.refinedstorage.api.autocrafting.PatternType;
import com.refinedmods.refinedstorage.common.api.autocrafting.PatternProviderItem;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 序列装配样板：描述一条完整的序列装配流程（原料 + 有序单元步骤 + 循环次数 +
 * 概率产物池 + 概率废料池）。数据存储于 CustomData，由序列装配样板终端生成。
 * <p>同时实现 RS 的 {@link PatternProviderItem}：把数据翻译成一张 <b>EXTERNAL 型样板</b>。
 * 放进 RS 原版自动合成仓后，自动合成仓会像“外部执行仓”一样把原料按轮次吐到它正面的
 * 机器/容器；产物由玩家自己的产线产出并（经输入总线等）送回网络存储，RS 据此判定完成。
 * 只有<b>主产物</b>（{@code results} 里的第一项，与 Create 的 {@code getResultItem()} =
 * {@code resultPool.getFirst()} 同口径）会作为“输出”参与判定，概率产物与废料不入样板
 * （避免任务永远等不到）。主产物的显示概率照抄 Create JEI（权重 / 权重和），不再恒为 100%。
 */
public class SequenceAssemblyPatternItem extends Item implements PatternProviderItem {
    public SequenceAssemblyPatternItem(final Properties properties) {
        super(properties);
    }

    /**
     * 类型判定：这张物品是不是「序列装配总样板」。
     *
     * <p><b>为什么用 {@code instanceof}（物品类型）而不是注册名 / 显示名 / 语言键</b>：
     * 名字与文案会随语言包、改名与词条编辑漂移，物品类型是唯一稳定且不误伤的判据
     * （与 {@link SequenceUnitPatternItem#isUnitPattern} 互斥）。</p>
     *
     * <p><b>用在哪里</b>：①「单元样板管理舱」不显示总样板（服务端按本判定算出隐藏下标，
     * 见 {@code UnitManagerSources#hiddenMasterPatternSlots}）；② 总样板专用容器
     * （序列装配样板库）只收总样板（见 {@code SequenceAssemblyExecutorBlockEntity#acceptsPattern}）。</p>
     */
    public static boolean isAssemblyPattern(final ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SequenceAssemblyPatternItem;
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        final net.minecraft.core.HolderLookup.Provider registries = context.level() != null
            ? context.level().registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final SequencePatternData.AssemblyData assembly = SequencePatternData.readAssembly(stack, registries);
        if (assembly == null) {
            // 常显：空白样板必须一眼看得出「它没内容」
            tooltip.add(Component.translatable("item.rs_create_compat.sequence_assembly_pattern.empty")
                .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        // 本模组附加信息<b>默认收起</b>，按住修饰键才展开（唯一实现 RsccTooltipLayers，物品级与屏幕级同一套）：
        //   Shift = 用途（要消耗什么：主原料 + 整份所需输入，含流体）；
        //   Ctrl  = 数值（步数与循环、产物 / 废料的概率）。
        final List<RsccTooltipLayers.Line> layered = new java.util.ArrayList<>(8);
        // ==================== 常显文字（2026-10-05 用户明确格式） ====================
        // 用户原话：<i>「如果步骤有重复的话您应该这么写：xx 名字 x n，前面的叉叉是他的图标名字就是他的
        // 名字，然后乘以 n 就是循环多少次就重复了多少个。然后不要显示也不要写出什么图标轮换之类的，
        // 更不要写出等多少个这样子的」</i>，以及
        // <i>「你这样的话并没有标注出哪些是原料哪些是输入式原料哪些是废料之类的，
        // 而且你现在废料也不显示」</i>。
        //
        // 因此常显层改成「<b>分段 + 每段一行一个：名字 ×N</b>」：
        //   原料：<主原料>          （多候选时名字跟着轮播一起换，不写「等 N 种」）
        //   输入原料：<每个中间投入> ×N
        //   产物：<产物> ×N
        //   废料：<废料> ×N        （原本漏了这一段）
        // 图标区（{@link #getTooltipImage()}）已按用户要求<b>关闭</b>（「这个图标貌似太长了……把这个图标砍掉吧，
        // 只留下原本那样子，但是要保留文字的轮换」）。
        final List<ItemStack> ingredientCandidates = assembly.candidatesOrRepresentative();
        final InputTotals required = collectInputs(assembly);
        if (!ingredientCandidates.isEmpty()) {
            // 多候选 ⇒ 名字与轮播同一口径（每帧重算 tooltip，因此名字真的会换），但不写「等 N 种」
            final ItemStack shownIngredient = ingredientCandidates.size() > 1
                ? ingredientCandidates.get(
                    cretae.cookiewyq.rs_create_compat.client.tooltip.ItemCycleTooltipComponent
                        .currentIndex(ingredientCandidates.size()))
                : ingredientCandidates.get(0);
            tooltip.add(Component.translatable(
                "item.rs_create_compat.sequence_assembly_pattern.ingredient",
                shownIngredient.getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        if (!required.items().isEmpty()) {
            // <b>两段必须互斥</b>（2026-10-05 用户反馈：「你原料和输入时原料重复了，
            // 就是原料在原料里面有一个、在输入时原料里面又出现了一次」）。
            // 主原料在上面的「原料：」已经单独写过一行，而 collectInputs 又把主原料并进 items，
            // 于是同一件东西出现两次。这里把它剔掉 —— 「输入原料」段只列<b>各步骤消耗的中间投入</b>。
            final Set<Item> mainIngredientItems = new LinkedHashSet<>();
            for (final ItemStack candidate : ingredientCandidates) {
                if (!candidate.isEmpty()) {
                    mainIngredientItems.add(candidate.getItem());
                }
            }
            boolean headerWritten = false;
            for (final Map.Entry<InputGroupKey, Long> entry : required.items().entrySet()) {
                if (mainIngredientItems.contains(entry.getKey().representative().item())) {
                    continue; // 主原料已在「原料：」那一行写过，不在此重复
                }
                if (!headerWritten) {
                    tooltip.add(Component.translatable(
                        "item.rs_create_compat.sequence_assembly_pattern.inputs_header")
                        .withStyle(ChatFormatting.GRAY));
                    headerWritten = true;
                }
                tooltip.add(stepLine(entry.getKey(), entry.getValue(), ChatFormatting.DARK_GRAY));
            }
        }
        if (!assembly.results().isEmpty()) {
            tooltip.add(Component.translatable(
                "item.rs_create_compat.sequence_assembly_pattern.results_header")
                .withStyle(ChatFormatting.GRAY));
            for (final SequencePatternData.PatternOutput result : assembly.results()) {
                tooltip.add(Component.literal(result.stack().getHoverName().getString()
                        + " x" + result.stack().getCount())
                    .withStyle(ChatFormatting.AQUA));
            }
        }
        if (!assembly.scraps().isEmpty()) {
            tooltip.add(Component.translatable(
                "item.rs_create_compat.sequence_assembly_pattern.scraps_header")
                .withStyle(ChatFormatting.GRAY));
            for (final SequencePatternData.PatternOutput scrap : assembly.scraps()) {
                tooltip.add(Component.literal(scrap.stack().getHoverName().getString()
                        + " x" + scrap.stack().getCount())
                    .withStyle(ChatFormatting.DARK_AQUA));
            }
        }
        layered.add(RsccTooltipLayers.ctrl(Component.translatable(
            "item.rs_create_compat.sequence_assembly_pattern.units",
            assembly.units().size(), assembly.loops()).withStyle(ChatFormatting.GRAY)));
        for (final SequencePatternData.PatternOutput result : assembly.results()) {
            final int pct = (int) (result.chance() * 100);
            layered.add(RsccTooltipLayers.ctrl(Component.translatable(
                "item.rs_create_compat.sequence_assembly_pattern.result",
                result.stack().getHoverName(), result.stack().getCount(), pct)
                .withStyle(ChatFormatting.AQUA)));
        }
        for (final SequencePatternData.PatternOutput scrap : assembly.scraps()) {
            final int pct = (int) (scrap.chance() * 100);
            layered.add(RsccTooltipLayers.ctrl(Component.translatable(
                "item.rs_create_compat.sequence_assembly_pattern.scrap",
                scrap.stack().getHoverName(), scrap.stack().getCount(), pct)
                .withStyle(ChatFormatting.DARK_AQUA)));
        }
        RsccTooltipLayers.append(tooltip, layered);
    }

    /**
     * tooltip 的「所需原料」图标区：<b>每一条可互换原料一行，图标与名称一起轮播</b>。
     *
     * <p>由客户端工厂（{@code ClientInit}）映射为
     * {@link cretae.cookiewyq.rs_create_compat.client.tooltip.AssemblyInputsTooltipComponent}。</p>
     *
     * <p><b>为什么走图标组件而不是文字（用户明确要求）</b>：用户原话是「禁止出现什么就是把多种原料
     * 合并在一起什么什么或什么或什么括号什么这样子的形式……为什么不能吃这里面的文本来进行轮换显示呢」。
     * 文字行做不到轮播（{@code Component} 一旦构造就固定），而原版 tooltip 的图标区
     * （{@link net.minecraft.world.inventory.tooltip.TooltipComponent}）本来就是每帧重绘，
     * 正是为这种「会动的内容」准备的 —— 单元样板 tooltip 早就这么做了，这里把总样板统一到同一套。</p>
     */
    @Override
    public java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> getTooltipImage(
        final ItemStack stack) {
        // <b>2026-10-05 用户要求关掉图标区</b>（原话：「我突然觉得这个图标貌似太长了，加上之后……
        // 你还是把这个图标砍掉吧，只留下原本那样子，但是要保留文字的轮换」）。
        // 因此总样板 tooltip 不再挂任何图标组件：轮换改由上面的<b>文字行</b>承担
        // （tooltip 每帧重绘 ⇒ 名字跟着换），布局与改动前的纯文字版一致。
        if (true) {
            return java.util.Optional.empty();
        }
        final SequencePatternData.AssemblyData assembly =
            SequencePatternData.readAssembly(stack, net.minecraft.core.RegistryAccess.EMPTY);
        if (assembly == null) {
            return java.util.Optional.empty();
        }
        final List<ItemStack> ingredient = assembly.candidatesOrRepresentative();
        final InputTotals required = collectInputs(assembly);
        final List<cretae.cookiewyq.rs_create_compat.client.tooltip.AssemblyInputsTooltipComponent.Group>
            groups = new java.util.ArrayList<>(required.items().size());
        for (final Map.Entry<InputGroupKey, Long> entry : required.items().entrySet()) {
            final List<ItemStack> candidates = new java.util.ArrayList<>();
            candidates.add(new ItemStack(entry.getKey().representative().item()));
            for (final Item alternative : entry.getKey().alternatives()) {
                candidates.add(new ItemStack(alternative));
            }
            groups.add(new cretae.cookiewyq.rs_create_compat.client.tooltip
                .AssemblyInputsTooltipComponent.Group(candidates, entry.getValue()));
        }
        final List<net.neoforged.neoforge.fluids.FluidStack> fluids =
            new java.util.ArrayList<>(required.fluids().size());
        for (final Map.Entry<FluidResource, Long> entry : required.fluids().entrySet()) {
            fluids.add(new net.neoforged.neoforge.fluids.FluidStack(
                entry.getKey().fluid(), (int) Math.min(Integer.MAX_VALUE, entry.getValue())));
        }
        if (ingredient.isEmpty() && groups.isEmpty() && fluids.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new InputsImage(ingredient, groups, fluids));
    }

    /**
     * 图标区数据载体（仅承载数据，渲染交给客户端组件工厂）。
     * <p>记录分量即「主原料候选 / 各步输入原料组 + 数量 / 输入流体」——
     * 渲染端不再需要任何配方或 Level，因此脱离终端也能轮播。</p>
     */
    public record InputsImage(
        List<ItemStack> ingredient,
        List<cretae.cookiewyq.rs_create_compat.client.tooltip.AssemblyInputsTooltipComponent.Group> items,
        List<net.neoforged.neoforge.fluids.FluidStack> fluids)
        implements net.minecraft.world.inventory.tooltip.TooltipComponent {
        public InputsImage {
            ingredient = ingredient == null ? List.of() : List.copyOf(ingredient);
            items = items == null ? List.of() : List.copyOf(items);
            fluids = fluids == null ? List.of() : List.copyOf(fluids);
        }
    }

    // ---------- 右键：Shift 还原空白样板 / 非 Shift 打开「总样板机器绑定」界面 ----------

    /**
     * 右键<b>空气</b>：① Shift 保留既有语义（还原成空白 RS 样板，无提示）；
     * ② 非 Shift ⇒ 打开「总样板机器绑定」界面（见 {@link AssemblyPatternRebind}）。
     *
     * <h2>为什么触发从「右键执行舱」换成「右键空气」（2026-10-06 用户拍板）</h2>
     * <p>上一版把入口放在 {@code useOn}（手持总样板右键执行舱），但那条路在 1.21.1 里
     * <b>永远走不到</b>：{@code ServerPlayerGameMode#useItemOn} 的顺序是
     * {@code BlockState#useItemOn} → <b>{@code BlockState#useWithoutItem}</b> → {@code ItemStack#useOn}，
     * 而 {@code SequenceExecutionChamberBlock} 没有重写 {@code useItemOn}，默认实现直接进
     * {@code useWithoutItem} —— 那里<b>不看手持物</b>，开完菜单就返回 {@code sidedSuccess}
     * （＝这次交互已被消费），于是 {@code ItemStack#useOn} <b>从不被调用</b>，改绑分支是死代码。</p>
     * <p>右键空气完全不经过方块交互（没有那个吞动作的问题），同时不抢 Shift + 右键（还原空白样板）、
     * 不抢空手右键（开执行舱界面）。机器候选也不再需要目标方块 —— 服务端改为从
     * <b>玩家身边最近的执行舱</b>取它所在网络，再把该网络里的执行舱作为候选（见
     * {@code AssemblyPatternRebind#openFor}）。</p>
     * <p><b>只有服务端发界面包</b>：客户端返回 SUCCESS 只是让手挥一下（有反馈），
     * 界面由服务端发来的 {@code AssemblyPatternRebindOpenPacket} 打开 —— 与工程其它界面同一口径
     * （客户端不自己计算任何权威状态）。</p>
     */
    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
        final net.minecraft.world.level.Level level,
        final net.minecraft.world.entity.player.Player player,
        final net.minecraft.world.InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            // 既有语义一字不动：Shift + 右键（空气）＝ 还原成空白 RS 样板，无提示
            return SampleItemConversions.use(level, player, hand);
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            // 只发权威快照（打开界面）；真正写入要等界面里明确指定「哪一步 → 哪台机器」
            AssemblyPatternRebind.openFor(serverPlayer, hand);
        }
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(
            player.getItemInHand(hand), level.isClientSide());
    }

    /**
     * 右键方块：<b>只保留 Shift 的既有语义</b>（还原成空白 RS 样板），其余一律 PASS。
     *
     * <h2>为什么这里不再有「改绑机器」分支（2026-10-06 用户拍板）</h2>
     * <p>① 它在 1.21.1 里是死代码（原因见 {@link #use} 的说明）；
     * ② 用户明确「不必保留这个入口」，改绑入口已移到 {@link #use}（右键空气）。</p>
     * <p><b>Shift 语义逐字不变</b>：Shift 分支与调用一字未改；非 Shift 时
     * {@code SampleItemConversions.useOn} 自己也会返回 PASS，因此把「其余分支」直接删成
     * {@code return PASS} 与改造前对两种输入给出<b>完全相同</b>的结果（无方块条件、无副作用）。</p>
     */
    @Override
    public net.minecraft.world.InteractionResult useOn(final net.minecraft.world.item.context.UseOnContext context) {
        final net.minecraft.world.entity.player.Player player = context.getPlayer();
        if (player == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            return SampleItemConversions.useOn(context.getLevel(), player, context.getHand());
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    // ---------- RS PatternProviderItem：把它当作一张“外部执行样板” ----------

    @Override
    @javax.annotation.Nullable
    public UUID getId(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        // 同一份装配数据 → 同一 UUID（RS 任务缓存/防环检测用）
        return UUID.nameUUIDFromBytes(stack.getComponentsPatch().toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<Pattern> getPattern(final ItemStack stack, final Level level) {
        return buildPattern(stack, level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY);
    }

    @Override
    public Optional<ItemStack> getOutput(final ItemStack stack, final Level level) {
        final net.minecraft.core.HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final SequencePatternData.AssemblyData assembly = SequencePatternData.readAssembly(stack, registries);
        if (assembly == null) {
            return Optional.empty();
        }
        // 主产物 = results 的第一项（与 Create 的 getResultItem() = resultPool.getFirst() 同口径）。
        // 不再要求 chance == 1：自本轮起显示概率照抄 Create JEI（权重 / 权重和），主产物可能 < 100%。
        if (!assembly.results().isEmpty()) {
            final ItemStack main = assembly.results().get(0).stack();
            if (!main.isEmpty()) {
                return Optional.of(main.copy());
            }
        }
        return Optional.empty();
    }

    private Optional<Pattern> buildPattern(final ItemStack stack,
                                           final net.minecraft.core.HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        final SequencePatternData.AssemblyData data = SequencePatternData.readAssembly(stack, registries);
        if (data == null) {
            return Optional.empty();
        }
        final PatternBuilder builder = PatternBuilder.pattern(PatternType.EXTERNAL);
        // 输入原料：以「一次迭代（一个 loop）」为单位 —— 主原料 + 各步骤消耗的中间输入原料。
        // <b>标签型 ingredient 必须作为「同一项 ingredient 的多个候选」登记</b>（见 InputTotals 的说明）。
        final InputTotals totals = collectInputs(data);
        for (final Map.Entry<InputGroupKey, Long> entry : totals.items().entrySet()) {
            // RS 的 ingredient = 「一个需求量 + 可选输入列表」：同一项 ingredient 的多个候选就是
            // 「这件原料可以是铁粒或锌粒」（见 CraftingTree / AbstractTaskPattern#calculateIterationInputs，
            // 它们按输入顺序取候选直到凑够需求量）。因此这里必须先在 IngredientBuilder 上收集完候选再 end()。
            final PatternBuilder.IngredientBuilder ingredient = builder.ingredient(entry.getValue());
            ingredient.input(entry.getKey().representative());
            for (final Item alternative : entry.getKey().alternatives()) {
                ingredient.input(new ItemResource(alternative, DataComponentPatch.EMPTY));
            }
            ingredient.end();
        }
        // 流体输入同样必须登记：RS「自动合成预览」按样板登记的 ingredient 逐项报告缺料，
        // 旧实现只登记物品 → 需要岩浆的配方在预览里永远不会显示「缺少岩浆」（用户实测问题）。
        // 数量单位 = mB（Create 的 SizedFluidIngredient#amount）。
        for (final Map.Entry<FluidResource, Long> entry : totals.fluids().entrySet()) {
            builder.ingredient(entry.getKey(), entry.getValue());
        }
        // 主产物（results 第一项）作为本样板的输出；概率项与废料均不入样板：
        // RS 明确禁止 EXTERNAL 样板带 byproduct（PatternBuilder 直接抛异常），
        // 废料放进 outputs 又会把它错误标记为“产物”，导致回收方不把它当废料处理。
        // 判定与 chance 无关（与 getOutput() 同口径）：只看 results 的第一项。
        if (data.results().isEmpty() || data.results().get(0).stack().isEmpty()) {
            return Optional.empty(); // 没有主产物，无法作为自动合成样板
        }
        final ItemStack mainOutput = data.results().get(0).stack();
        builder.output(new ItemResource(mainOutput.getItem(),
            net.minecraft.core.component.DataComponentPatch.EMPTY), Math.max(1, mainOutput.getCount()));
        try {
            return Optional.of(builder.build());
        } catch (final RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 一组「可互换的输入原料」的聚合键：代表物（首个候选 = 优先消耗的那一件）+ 其余候选。
     *
     * <h2>为什么按「组」而不是按「物品」聚合（列车轨道：铁粒 / 锌粒候选未显示）</h2>
     * <p>旧实现按<b>单个物品</b>聚合（{@code Map<Item, Long>}），而 {@code UnitEntry#input()} 只有一个
     * 代表物 —— 于是总样板 / RS EXTERNAL 样板里只登记了 {@code minecraft:iron_nugget}，
     * 锌粒<b>根本没被登记</b>（实机日志：{@code create:track} 样板的 ingredients 只有铁粒）。
     * RS 的 {@code Ingredient} 本来就是「一个需求量 + 多个可选输入」（见 {@code CraftingTree} /
     * {@code AbstractTaskPattern#calculateIterationInputs}：按顺序取候选直到凑够需求量），
     * 因此这里把整组作为<b>一项</b> ingredient 登记，锌粒才真正参与计算与搬运。</p>
     * <p>代表物排在首位 ⇒ 与旧行为「优先铁粒」完全一致（RS 按输入顺序消耗）。</p>
     */
    public record InputGroupKey(ItemResource representative, List<Item> alternatives) {
    }

    /** 一张装配样板「一次完整自动合成」所需的全部输入：物品组（按组累加件数）+ 流体（按资源键累加 mB）。 */
    private record InputTotals(Map<InputGroupKey, Long> items, Map<FluidResource, Long> fluids) {
    }

    /** 流体的显示名（供 tooltip 使用；数量另由调用方按 mB 拼接）。 */
    private static Component fluidName(final FluidResource resource) {
        return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(resource.fluid()), 1).getHoverName();
    }

    /**
     * 汇总一张装配样板（= 一次完整自动合成）所需的全部输入原料（供样板与 tooltip 共用）。
     * <p>口径（与 Create 序列装配的真实消耗一致）：</p>
     * <ul>
     *     <li>主原料：只算一次（Create 里被加工物件全程只投入一次），{@code max(1, ingredient.getCount())}；</li>
     *     <li>每步的中间输入原料：{@code loops × 步重复次数 × 该步输入物数量}
     *         —— 中间原料是「每跑一遍序列吃一次」，而序列要跑 {@code loops} 遍才会产出最终产物；
     *         例如每遍吃 1 个铁粒、loops=5，则整份样板需要 5 个铁粒；</li>
     *     <li>每步的输入流体：同一份 {@code loops × 步重复次数} 倍率 × 该步流体 mB
     *         —— 数据来源是单元样板标记的 {@code InputFluid}（由 Create 配方的
     *         {@code SizedFluidIngredient#amount} 一路写入）；</li>
     *     <li>同一物品 / 同一流体在多步中重复出现时累加（{@link Map#merge}），保证各只生成一个输入项；
     *         <b>可互换的候选（标签型 ingredient）合成同一项 ingredient 的多个候选</b>，
     *         数量只算一份（整组算一个原料），且代表物恒在首位。</li>
     * </ul>
     */
    /**
     * 一行「步骤输入」文字：<b>当前轮播到的那一件的名字 × 总需求</b>。
     * <p>格式严格按用户要求：{@code <名字> ×N}，不写「等 N 种」、不写「图标轮换」。</p>
     */
    private static Component stepLine(final InputGroupKey key, final long amount,
                                      final ChatFormatting color) {
        final List<Item> candidates = new ArrayList<>();
        candidates.add(key.representative().item());
        candidates.addAll(key.alternatives());
        final Item shown = candidates.size() > 1
            ? candidates.get(cretae.cookiewyq.rs_create_compat.client.tooltip.ItemCycleTooltipComponent
                .currentIndex(candidates.size()))
            : candidates.get(0);
        return Component.literal(new ItemStack(shown).getHoverName().getString() + " x" + amount)
            .withStyle(color);
    }

    private static InputTotals collectInputs(final SequencePatternData.AssemblyData data) {
        final Map<InputGroupKey, Long> items = new LinkedHashMap<>();
        final Map<FluidResource, Long> fluids = new LinkedHashMap<>();
        if (!data.ingredient().isEmpty()) {
            // 主原料：整条装配只投入一次，不随 loops 放大。
            // <b>2026-10-05 修复</b>：这里原来写死 {@code List.of(data.ingredient())} ——
            // 只登记代表物一件。而主原料<b>本身也可能是标签型 ingredient 的多候选组</b>
            // （列车轨道的起步原料 = 石头台阶 / 平滑石台阶 / 安山岩台阶）。只登记一件的后果是
            // RS 合成树只认那一种台阶，其余两种被判成「不是这条配方的料」——用户实测
            // 「还是只写是个石头台阶」。现在与单元样板同一口径：整组候选一起登记，
            // 代表物在首位（{@code candidatesOrRepresentative()} 缺候选时优雅退回单件）。
            mergeInput(items, data.candidatesOrRepresentative(),
                (long) Math.max(1, data.ingredient().getCount()));
        }
        final long loops = Math.max(1, data.loops());
        for (final SequencePatternData.UnitEntry unit : data.units()) {
            // loops × 每遍重复次数
            final long repeats = loops * Math.max(1, unit.count());
            // 代表物数量 = 该单元记的件数（候选组里每件数量恒为 1，见 SequencePatternData#normalizeCandidates）
            final ItemStack stepInput = unit.input();
            final long perIteration = repeats * (stepInput == null || stepInput.isEmpty()
                ? 1L : Math.max(1, stepInput.getCount()));
            // 整组候选（老样板 / 单件步 = 只有代表物一件）。用 candidatesOrRepresentative()：
            // 候选组为空时退回代表物，绝不产生「空 ingredient」。
            final List<ItemStack> group = unit.candidatesOrRepresentative();
            if (!group.isEmpty()) {
                mergeInput(items, group, perIteration);
            }
            final FluidStack stepFluid = unit.inputFluid();
            if (stepFluid != null && !stepFluid.isEmpty()) {
                fluids.merge(new FluidResource(stepFluid.getFluid(), stepFluid.getComponentsPatch()),
                    repeats * Math.max(1, stepFluid.getAmount()), Long::sum);
            }
        }
        return new InputTotals(items, fluids);
    }

    /**
     * 把「一组可互换的输入原料」并入聚合表：代表物（首个候选）在首位、其余候选按序跟在后面，
     * 数量只记一份（整组算一个原料）。组键 = 「代表物 + 其余候选集合」，因此
     * 同一件东西在别处以<b>不同组</b>出现时不会被错误合并（例如「铁粒|锌粒」与「只要铁粒」是两组）。
     */
    private static void mergeInput(final Map<InputGroupKey, Long> items, final List<ItemStack> group,
                                   final long amount) {
        final List<ItemStack> normalized = SequencePatternData.normalizeCandidates(group);
        if (normalized.isEmpty() || amount <= 0L) {
            return;
        }
        final Item representative = normalized.get(0).getItem();
        final List<Item> alternatives = new ArrayList<>(Math.max(0, normalized.size() - 1));
        for (int i = 1; i < normalized.size(); i++) {
            alternatives.add(normalized.get(i).getItem());
        }
        final InputGroupKey key = new InputGroupKey(
            new ItemResource(representative, DataComponentPatch.EMPTY), List.copyOf(alternatives));
        items.merge(key, amount, Long::sum);
    }
}
