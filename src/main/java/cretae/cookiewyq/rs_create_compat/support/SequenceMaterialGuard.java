package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe.SequencedAssembly;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
 * 「原料标记 + 防中间产物错误回流」的统一判定（<b>服务端权威</b>，只读 / 只标记，绝不销毁资源）。
 *
 * <h2>1. 原料标记（方案 2，不依赖 Mixin，不修改 Create）</h2>
 * 任务第一步执行时、初始原料被<b>输出给机器</b>的那一刻，给这份原料打上
 * {@code rs_create_compat:raw_material} 数据组件（值 = 配方 id + 输出该原料的步骤序）。
 * <ul>
 *     <li>载体选择：<b>注册版 DataComponent</b>（挂在 {@code DataComponentPatch} 上），而不是直接写
 *     {@code ItemStack} 的 NBT。原因：1.21.1 里物品附加数据只有组件这一条正路；组件值参与
 *     「同物品同组件 = 同一资源」的合并，因此<b>同种原料仍能正常堆叠</b>，RS 的
 *     {@code ItemResource(item, patch)} 也能正常存取；不污染别的模组用的 {@code CustomData}。</li>
 *     <li>标记只用于「区分这份东西是原料、还是已经加工过的未完成中间产物」；原料回流进网络前会
 *     被去掉标记（{@link #stripRawMaterial}），所以网络里永远只有「原始原料」这一种资源，
 *     <b>不影响正常合成与自动合成匹配</b>。</li>
 * </ul>
 *
 * <h2>2. 禁止回流步骤配置 {@code disallow_inputting_by_step}</h2>
 * 键名 = {@link #TAG_DISALLOW_INPUTTING_BY_STEP}，值为 <b>NBT int 数组</b>
 * （{@code putIntArray} / {@code getIntArray}），挂在<b>样板的 {@code CustomData}</b> 上
 * （样板沿用既有 {@code SequencePatternData} 的 CustomData 约定，不另建组件）。
 * <p>数组元素 = <b>单循环内的步骤序号</b>，与 Create 的 {@code SequencedAssembly#step()} 同基准
 * （0-based，见 {@link #stepInLoop}）。判定取数组<b>最小值</b>作为阈值：</p>
 * <pre>未完成物品的 (配方 id, step % sequenceSize) 与配置的 (配方 id, 阈值) 对齐后，
 * 一旦 step % sequenceSize &gt;= 阈值 → 禁止它作为输入再被投回执行舱。</pre>
 * <p>「配方 id + 步骤序」才能唯一定位一个步骤（精密构件一类物品有多条序列装配配方，
 * 只按步骤序会串配方），配方 id 直接复用 Create 的
 * {@code create:sequenced_assembly} 组件里的 {@code id}，不另建键。</p>
 */
public final class SequenceMaterialGuard {

    /** 「禁止回流步骤」键（NBT int 数组；值 = 单循环内的步骤序号，0-based）。 */
    public static final String TAG_DISALLOW_INPUTTING_BY_STEP = "disallow_inputting_by_step";

    /** 未配置 / 无意义的阈值哨兵值。 */
    public static final int NO_THRESHOLD = Integer.MIN_VALUE;

    /**
     * 原料标记的值：这份原料属于哪条配方、是在第几步被输出给机器的。
     * <p>与 Create 的 {@code SequencedAssembly(id, step, progress)} 同一套坐标（id 为配方 id、
     * step 为步骤序），因此不需要再发明新的定位键。</p>
     */
    public record RawMaterialMark(ResourceLocation recipe, int step) {
        public static final Codec<RawMaterialMark> CODEC = RecordCodecBuilder.create(builder -> builder.group(
            ResourceLocation.CODEC.fieldOf("recipe").forGetter(RawMaterialMark::recipe),
            Codec.INT.fieldOf("step").forGetter(RawMaterialMark::step)
        ).apply(builder, RawMaterialMark::new));

        public static final StreamCodec<ByteBuf, RawMaterialMark> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, RawMaterialMark::recipe,
            ByteBufCodecs.INT, RawMaterialMark::step,
            RawMaterialMark::new);
    }

    private SequenceMaterialGuard() {
    }

    // ==================== 原料标记 ====================

    /** 打原料标记（任务第一步 / 初始原料输出时调用；同值重复调用是幂等的）。 */
    public static void markRawMaterial(final ItemStack stack, final ResourceLocation recipe,
                                       final int step) {
        if (stack.isEmpty() || recipe == null) {
            return;
        }
        stack.set(RS_Create_Compat.RAW_MATERIAL.get(), new RawMaterialMark(recipe, step));
    }

    /** 是否是「已打原料标记」的原料。 */
    public static boolean isRawMaterial(final ItemStack stack) {
        return !stack.isEmpty() && stack.has(RS_Create_Compat.RAW_MATERIAL.get());
    }

    /** 读原料标记（无标记返回 null）。 */
    @Nullable
    public static RawMaterialMark rawMaterialMark(final ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(RS_Create_Compat.RAW_MATERIAL.get());
    }

    /** 去掉原料标记（回流进网络前调用，保证网络里不会出现带标记的原料变体）。 */
    public static void stripRawMaterial(final ItemStack stack) {
        if (!stack.isEmpty()) {
            stack.remove(RS_Create_Compat.RAW_MATERIAL.get());
        }
    }

    // ==================== disallow_inputting_by_step ====================

    /** 读样板上配置的「禁止回流步骤」数组（无配置返回空数组）。 */
    public static int[] readDisallowInputtingByStep(final ItemStack patternStack) {
        if (patternStack.isEmpty()) {
            return new int[0];
        }
        final CompoundTag data = customDataOf(patternStack);
        if (data == null || !data.contains(TAG_DISALLOW_INPUTTING_BY_STEP, Tag.TAG_INT_ARRAY)) {
            return new int[0];
        }
        return data.getIntArray(TAG_DISALLOW_INPUTTING_BY_STEP);
    }

    /**
     * 写样板的「禁止回流步骤」数组（传空数组即删除该键）。
     * <p>只写这一项配置，不触碰样板上的其余 tag / 组件。</p>
     */
    public static void writeDisallowInputtingByStep(final ItemStack patternStack, final int[] steps) {
        if (patternStack.isEmpty()) {
            return;
        }
        patternStack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> data.update(tag -> {
            if (steps == null || steps.length == 0) {
                tag.remove(TAG_DISALLOW_INPUTTING_BY_STEP);
            } else {
                tag.putIntArray(TAG_DISALLOW_INPUTTING_BY_STEP, steps);
            }
        }));
    }

    /** 读样板（单元样板 / 装配样板）记录的配方 id（无则返回空串）。 */
    public static String readPatternRecipeId(final ItemStack patternStack) {
        if (patternStack.isEmpty()) {
            return "";
        }
        final CompoundTag data = customDataOf(patternStack);
        return data == null ? "" : data.getString(SequencePatternData.TAG_RECIPE);
    }

    /** 阈值 = 配置数组的最小值（未配置返回 {@link #NO_THRESHOLD}）。 */
    public static int threshold(final int[] steps) {
        if (steps == null || steps.length == 0) {
            return NO_THRESHOLD;
        }
        int min = steps[0];
        for (final int step : steps) {
            min = Math.min(min, step);
        }
        return min;
    }

    // ==================== 判定 ====================

    /**
     * 未完成物品在<b>单循环</b>内的步骤序（0-based）。
     * <p>语义与 Create 对齐：{@code SequencedAssembly#step()} 记录的是「已经走过的步数」，
     * 也就是「下一个要执行的步下标」（{@code SequencedAssemblyRecipe#getNextRecipe} 用的是
     * {@code sequence.get(step % sequence.size())}）。所以
     * {@code step % sequenceSize == 0} 表示还没开始第一步、{@code == 1} 表示已完成第 1 步。</p>
     */
    public static int stepInLoop(final SequencedAssembly assembly, final int sequenceSize) {
        if (assembly == null || sequenceSize <= 0) {
            return 0;
        }
        return Math.floorMod(assembly.step(), sequenceSize);
    }

    /**
     * 该物品是否禁止作为输入再被投回（服务端权威判定）。
     *
     * @param probe         待判定物品（取自网络资源的样本）
     * @param sequenceSize  该配方的 Create sequence 长度（{@code <= 0} = 未知，放行）
     * @param threshold     该配方配置的「禁止回流起始步骤」（{@link #NO_THRESHOLD} = 未配置，放行）
     * @return {@code true} = 禁止投回（调用方必须「不接收」，留在原处，绝不能销毁）
     */
    public static boolean isInputBlocked(final ItemStack probe, final int sequenceSize,
                                         final int threshold) {
        if (threshold == NO_THRESHOLD) {
            return false; // 未配置 → 放行（对旧存档零影响）
        }
        if (isRawMaterial(probe)) {
            return false; // 打了原料标记 = 任务第一步输出的原料 → 明确放行
        }
        final SequencedAssembly assembly = probe.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (assembly == null) {
            return false; // 起步原料（没有进度组件）→ 放行
        }
        if (sequenceSize <= 0) {
            return false; // 配方暂时查不到（数据包未加载）→ 放行，宁可放过不可误拦
        }
        return stepInLoop(assembly, sequenceSize) >= threshold;
    }

    /**
     * 两份未完成件是否指向<b>同一个加工步骤</b>（= 同一条配方 + 同一个「单循环内的步序」，<b>只读</b>）。
     *
     * <p><b>为什么需要它（「按步过滤」的核心）</b>：同一配方可以把同一个处理器用在多个步骤上
     * （坚固板的第 2、3 步都是冲压，见 {@code create:sturdy_sheet}），此时两份未完成件是
     * <b>同一个物品</b>、只有 {@code create:sequenced_assembly} 组件里的进度步不同 ——
     * {@code ItemResource} 裸物品<b>根本无法区分</b>（输出总线还被强制模糊模式，
     * 组件会被归一化掉），于是「勾第 N 步」与「勾第 N+1 步」会退化成同一个过滤项、互相覆盖。
     * 因此过滤项必须带上<a href="#stepInLoop">进度步</a>，并按
     * <b>步序对总步数取模</b>比较：这样 {@code loops > 1} 时 {@code step=1} 与 {@code step=4}
     * （{@code T = 3}）也算同一步，不会被误判成两个不同的步骤。</p>
     *
     * @param candidate    待判定件（取自仓内 / 机器侧的实际物品）的进度组件；{@code null} = 不带组件
     * @param prototype    过滤项原型的进度组件；{@code null} = 该过滤项<b>不按步过滤</b>（一律匹配）
     * @param sequenceSize 该配方的总步数 T（{@code <= 0} = 查不到 → 退化为「绝对步相等」，保守但不误放）
     */
    public static boolean sameStep(@Nullable final SequencedAssembly candidate,
                                   @Nullable final SequencedAssembly prototype,
                                   final int sequenceSize) {
        if (prototype == null) {
            return true; // 过滤项没有进度步：不按步过滤（原料 / 成品一类的既有行为）
        }
        if (candidate == null) {
            // <b>2026-10-05 修「坚固板卡死」的真根因（用户实测 + 快照双重证据）。</b>
            //
            // 旧写法无条件 `return false`：只要待推送的那一件<b>不带进度组件</b>，就判定
            // 「它不是这一步要的」。而 Create 的序列装配里，有一整类中间产物本身就<b>永远不带</b>
            // {@code SEQUENCED_ASSEMBLY} 组件 —— 典型就是<b>冲压步产出的
            // {@code create:unprocessed_obsidian_sheet}</b>（坚固板第 1/2 步的中间件）。
            //
            // 实测（快照 20261005-190358 / 20261005-190454）：
            //   注液 chamber@-10,-60,10  unprocessed_obsidian_sheet ×4   machine=0
            //   冲压 chamber@-16,-60,10  unprocessed_obsidian_sheet ×5   machine=0
            //   USE  chamber@-5,-60,6   unprocessed_obsidian_sheet ×4   machine=0
            //   —— 三个仓都把它压在内部存储里，<b>一件都没推给机器</b>（machine 恒为 0），
            //   而网络里 net=0（因为它压根没被推出去，也就没回流）。这正是用户说的
            //   「坚固板无法正常合成 / 中间产物堆在仓里不动」。
            //
            // 为什么这里放行是安全的：能走到本分支，说明过滤项（prototype）<b>带</b>进度组件、
            // 而候选件<b>不带</b> —— 那把「不带组件的中间产物」推给本仓这台机器不会有歧义：
            // 它没有步序可供错配，而本仓既然为这一步选了该类别，就说明本仓负责这一步。
            // 真正需要拦的是「<b>带了组件但步序不对</b>」的件，那由下面的 id/step 比较负责。
            //
            // 注意只放宽「候选无组件」这一种情况；候选<b>带</b>组件而步序不符时仍返回 false（见下）。
            return true;
        }
        if (!candidate.id().equals(prototype.id())) {
            return false; // 不同配方（同一个过渡件可能属于多条配方）→ 不是同一步
        }
        if (sequenceSize <= 0) {
            return candidate.step() == prototype.step(); // 总步数查不到 → 只能按绝对步比（保守）
        }
        return Math.floorMod(candidate.step(), sequenceSize)
            == Math.floorMod(prototype.step(), sequenceSize);
    }

    // ==================== 「按步骤」统一判定（s + 1 == m） ====================

    /**
     * 「这份未完成件此刻属于哪一类」的三态结论（<b>只读、不搬运任何资源</b>）。
     *
     * <p>为什么是<b>三态</b>而不是布尔：用户明确要求「<b>判不出来就保守</b>」—— 「不是我的」与
     * 「判不出来」必须分开，否则「配方暂时查不到 / 机器还没配好样板」会被误判成「不是我的」，
     * 于是刚推给机器的料被立刻抽回来，形成「推出 → 收回 → 再推出」的空转（实测日志里的拉锯正是它）。</p>
     */
    public enum StepVerdict {
        /** 这一份<b>正是</b>这台机器下一步要加工的对象（{@code s + 1 == m}）→ 绝对不许收回、不许被别人抢走。 */
        NEXT_FOR_MACHINE,
        /** 这台机器对它已无活可干（{@code s >= m}）、或这台机器与它无关 → 允许收回（交给下一步骤 / 回网）。 */
        NOT_MINE,
        /** 判不出来（配方总步数 T 未知 / 机器没有可用的步序）→ <b>保守</b>：按「机器还要它」处理，绝不收回。 */
        UNKNOWN
    }

    /**
     * <b>「按步骤」口径的唯一一份判据</b>（输入总线的「全自动收回」、输出总线的「推料 / 备料」共用它）。
     *
     * <p><b>为什么按步骤而不是「能不能被某份样板认领」</b>：旧口径只问「本机有没有单元样板认领它这一步」，
     * 于是「配方查不到 / 本机样板恰好没配到这一步」时被判成「无人认领 → 可收回」，
     * 刚喂进机器的料被立刻抽走、机器再要、再推——无限空转，且每一圈都要过一次机器（耗材白烧）。
     * 改成按步骤后，「这台机器还要不要这份料」是<b>由物品自己的进度步与该机器被指派的步直接比较</b>得出的，
     * 任何一侧（收回 / 推料）都不再依赖「样板表里恰好有什么」这种间接推断。</p>
     *
     * <p><b>三个量的来源</b>：</p>
     * <ul>
     *     <li>{@code s} = 物品当前进度步（{@code create:sequenced_assembly} 组件的 {@code step}，
     *     语义 = <b>已经走过的步数</b>、也就是「下一个要执行的步下标」，与 Create 的
     *     {@code SequencedAssemblyRecipe#getNextRecipe} 同一口径）；</li>
     *     <li>{@code m} = 该机器被指派的步（<b>0-based</b>，来自该机器所属执行舱的单元样板：
     *     配方 id + 步序 —— 样板上的 {@code step} 就是它负责的那一步）；</li>
     *     <li>{@code T} = 该配方的总步数（Create {@code SequencedAssemblyRecipe#getSequence().size()}）。</li>
     * </ul>
     * <p>判定（{@code s} 取 {@code s % T}，兼容 loops &gt; 1 的配方）：</p>
     * <pre>
     *   s % T == m   （即 s + 1 == m）→ NEXT_FOR_MACHINE   留给机器加工，绝不收回
     *   s % T &gt; m   （即 s &gt;= m + 1）→ NOT_MINE           该机器该做的已经做完 → 允许收回
     *   s % T &lt; m   （还没轮到这台机器）→ NOT_MINE           与它无关 → 允许收回
     *   T &lt;= 0（配方查不到）/ 机器没有步序 → UNKNOWN       判不出来 → 保守「还要它」
     * </pre>
     *
     * <p><b>只做一份</b>：本方法是「机器还要不要这份料」的唯一实现在，收回侧
     * （{@code isTransitionReclaimAllowed}）与推料 / 备料侧（{@code isNextForMyMachines}）都走它，
     * 因此对同一 (物品, 机器) 两侧的结论<b>严格互补</b> —— 不可能出现「刚推出又立刻收回」。
     * 本方法只读物品与参数，<b>不动任何资源</b>。</p>
     *
     * @param probe         待判定的物品（未完成件；不带进度组件的物品一律 {@link StepVerdict#NOT_MINE}，
     *                      由调用方按「是否输入类」另行处理）
     * @param machineRecipe 该机器被指派的配方 id（单元样板记的；空 = 没记 → {@link StepVerdict#UNKNOWN}）
     * @param machineStep   该机器被指派的步序（0-based；&lt; 0 = 没记 → {@link StepVerdict#UNKNOWN}）
     * @param totalSteps    该配方总步数 T（&lt;= 0 = 查不到 → {@link StepVerdict#UNKNOWN}）
     */
    public static StepVerdict judgeStep(final ItemStack probe, final String machineRecipe,
                                        final int machineStep, final int totalSteps) {
        if (machineRecipe == null || machineRecipe.isEmpty() || machineStep < 0) {
            return StepVerdict.UNKNOWN; // 机器没有可用的步序 → 判不出来（保守）
        }
        final SequencedAssembly assembly = probe.isEmpty()
            ? null : probe.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (assembly == null) {
            return StepVerdict.NOT_MINE; // 原料 / 成品 / 废料：本判定不负责（调用方按「是否输入类」处理）
        }
        if (!machineRecipe.equals(assembly.id().toString())) {
            return StepVerdict.NOT_MINE; // 不是同一条配方 → 这台机器与它无关
        }
        if (totalSteps <= 0) {
            return StepVerdict.UNKNOWN; // 总步数查不到 → 判不出来（保守：宁可留着，也不要空转）
        }
        return Math.floorMod(assembly.step(), totalSteps) == machineStep
            ? StepVerdict.NEXT_FOR_MACHINE // s + 1 == m：正轮到他加工
            : StepVerdict.NOT_MINE;        // 该机器做完了（s >= m）或还没轮到它（s < m）
    }

    /** 取物品 {@code CustomData} 的底层 tag（空 / 无数据返回 null；只读，故用非弃用的 {@code copyTag}）。 */
    @Nullable
    private static CompoundTag customDataOf(final ItemStack stack) {
        final CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.isEmpty() ? null : data;
    }
}
