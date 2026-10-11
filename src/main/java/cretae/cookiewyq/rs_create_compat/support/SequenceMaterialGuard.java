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

    // ==================== 在途自动合成任务对「网络存量」的占用（预留） ====================

    /**
     * <b>「留在网络里、但已被在途自动合成任务等着抽取」而暂停取用的原因标识</b>
     * （等待 / 上报缺料用；<b>绝不是</b>「硬抽」的许可）。
     * <p>与既有原因串（{@code already_enough} / {@code storage_full} / {@code extract_zero}）同一套命名，
     * 只进模组自己的诊断日志，<b>不是</b>给玩家看的语言键。</p>
     */
    public static final String HOLD_INFLIGHT_TASK_NEED = "inflight_task_need";

    /**
     * <b>RS 2.0 的「预留」到底存在哪里（这一节的全部依据，逐条给出类:行号）</b>
     *
     * <h2>① 网络根存储<b>只有插入</b>有拦截钩子，抽取<b>一个钩子都没有</b></h2>
     * <p>{@code RootStorageListener}（RS 源码 {@code api/storage/root/RootStorageListener.java:18,30}）
     * 只声明 {@code beforeInsert} 与 {@code afterInsert}；{@code RootStorageImpl#insert}
     * （{@code RootStorageImpl.java:90-126}）逐个回调它们，而 {@code RootStorageImpl#extract}
     * （{@code :85-87}）是<b>直接转发</b>给 {@code CompositeStorageImpl#extract} 的裸委托。</p>
     *
     * <h2>② 任务「还没拿到」的量只体现在任务自己的账上，不在网络存储里</h2>
     * <p>{@code TaskImpl#extractInitialResourcesAndTryStartRunningTask}
     * （{@code autocrafting/task/TaskImpl.java:199-222}）每步尝试
     * {@code rootStorage.extract(initialRequirement, needed, EXECUTE, Actor.EMPTY)}，抽到多少就
     * {@code initialRequirements.remove(...)}；而 {@code TaskImpl#getStatus}（{@code :146-166}）把
     * <b>剩下的 {@code initialRequirements} 原样报成 {@code TaskStatus.Item#extracting}</b>
     * （{@code autocrafting/status} 包里那个状态构造器的 {@code extracting(...)}，
     * 2.0.0 源码 {@code status} 包 :30-33）。</p>
     *
     * <p>于是 RS 里真正被「为某条在途任务留着」的量只有三种载体，<b>没有一种在网络存储里</b>：
     * ①{@code TaskImpl.internalStorage}（{@code :36}，已经从网络抽走的实体）；
     * ②{@code ExternalTaskPattern.expectedOutputs}（{@code ExternalTaskPattern.java:24}，还没做出来的产出）；
     * ③机器 / 外部接收端手里的在制件。网络里那一份<b>既没有被扣减、也没有任何标记</b> ——
     * 谁先 {@code extract} 谁拿走。这就是「外部存储 / 缓存节点在 {@code extract} 时 RS 不会先扣预留量」
     * 的确切答案：<b>RS 根本没有这个量</b>，因此本模组必须自己算。</p>
     *
     * <h2>③ 为什么只认 {@code extracting}，不认 {@code scheduled} / {@code processing}</h2>
     * <ul>
     *     <li>{@code scheduled}（{@code ExternalTaskPattern.java:144-150}）与
     *     {@code processing}（{@code :151-161}）描述的是<b>外部样板每轮迭代的投入物</b>；
     *     本模组的执行器 {@code SequenceAssemblyExecutorBlockEntity#accept} <b>把这些投入物原样插回网络</b>
     *     （{@code accept} 里 {@code storage.insert(resource, count, action, Actor.EMPTY)}），
     *     正是为了让各台执行仓能按步骤把它们领走。把它们也算成「别人的预留」会把本模组自己的
     *     供料链锁死（投料口就在网络上、却被判成不许取），既漏又死锁。</li>
     *     <li>{@code extracting} 相反：它<b>不在网络里</b>（任务还没抽到），所以「从网络里少取这么多」
     *     恰好等于「把网络里那 N 件留给那条任务去抽」，语义精确、不重复计算。</li>
     * </ul>
     *
     * <h2>④ 为什么这样最不容易漏、也不会永久死锁</h2>
     * <p>{@code initialRequirements} 只会因为它自己被抽走而<b>单调减少</b>
     * （{@code TaskImpl.java:214} 的 {@code remove}；没有任何一处把它加回去），因此「等」必然收敛。
     * 唯一的永久占用风险来自<b>不再推进的任务</b>（玩家取消后停在
     * {@code RETURNING_INTERNAL_STORAGE}、网络塞满导致收尾永远完不成）：那种任务再也不会
     * {@code extract} 了，若还算它的预留就是死锁。因此 {@link #isInFlight} 把这种状态<b>排除</b>，
     * 它的读数当 tick 作废。</p>
     *
     * @param state 任务状态（{@code null} 视为不在途）
     * @return {@code true} = 这条任务<b>还会</b>从网络抽料（它的 {@code extracting} 必须被尊重）
     */
    public static boolean isInFlight(@Nullable final com.refinedmods.refinedstorage.api.autocrafting.task.TaskState state) {
        if (state == null) {
            return false;
        }
        return switch (state) {
            case READY, EXTRACTING_INITIAL_RESOURCES, RUNNING -> true;
            // 取消 / 自然收尾：任务只会把 internalStorage 还回网络，绝不会再抽料。
            // 它的 initialRequirements 可能非空（取消时没人清），那是<b>过期读数</b>：
            // 继续算成预留会永久占住资源（本模组一件都取不到），因此必须当场作废。
            case RETURNING_INTERNAL_STORAGE, COMPLETED -> false;
        };
    }

    /** {@link #pendingExtraction(java.util.List, java.util.Set)} 的简写：不排除任何任务。 */
    public static java.util.Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long> pendingExtraction(
        @Nullable final java.util.List<com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus> statuses) {
        return pendingExtraction(statuses, java.util.Set.of());
    }

    /**
     * 汇总「每个资源此刻还被在途任务等着抽取多少」= 按资源求和的 {@code TaskStatus.Item#extracting}
     * （{@code TaskStatus.java:18-28} 的字段，来源见 {@link #isInFlight} 的 ②）。
     *
     * <p><b>只读纯函数</b>：不搬运、不修改任何资源与任务状态；调用方拿到的是一份新表。</p>
     *
     * @param statuses   RS 的 {@code AutocraftingNetworkComponent#getStatuses()} 结果
     * @param ownTaskIds <b>本产线自己的任务 id</b>（{@code TaskId#id()} 的字符串形式）：
     *                   它们对网络存量的需求<b>不算</b>「别人的预留」，原因见方法名注释与
     *                   {@link #HOLD_INFLIGHT_TASK_NEED} 的用法说明 —— 本模组的执行器会把每轮投入物
     *                   原样插回网络，如果连自己那条任务的需求也算成「不许取」，
     *                   供料链会自己把自己锁死。
     * @return 资源 → 「别人还等着从网络抽取的量」（只含 &gt; 0 的项）
     */
    public static java.util.Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long> pendingExtraction(
        @Nullable final java.util.List<com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus> statuses,
        final java.util.Set<String> ownTaskIds) {
        final java.util.Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long> claims =
            new java.util.LinkedHashMap<>();
        if (statuses == null || statuses.isEmpty()) {
            return claims;
        }
        for (final com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus status : statuses) {
            if (status == null || !isInFlight(status.state())) {
                continue;
            }
            if (status.info() != null && status.info().id() != null && ownTaskIds != null
                && ownTaskIds.contains(status.info().id().id().toString())) {
                continue;
            }
            if (status.items() == null) {
                continue;
            }
            for (final com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus.Item item : status.items()) {
                if (item == null || item.resource() == null || item.extracting() <= 0L) {
                    continue;
                }
                claims.merge(item.resource(), item.extracting(), Long::sum);
            }
        }
        return claims;
    }

    /**
     * <b>「可用量」的唯一算法</b>：从网络<b>存量</b>里扣掉「已被在途任务预留的量」。
     * <p>本模组此前各处一律用网络存量当可用量，于是会把别人在途任务等着的那一份也抽走
     * （RS 的 {@code extract} 不扣预留，见 {@link #isInFlight} 的 ①）。</p>
     *
     * @param storedAmount   网络存量（{@code RootStorage#get} / 资源清单里的量 = <b>存量</b>）
     * @param reservedAmount 该资源被在途任务预留的量（{@link #pendingExtraction} 的结果）
     * @return 本模组此刻最多可以取走的量（永不为负）
     */
    public static long availableForTake(final long storedAmount, final long reservedAmount) {
        if (storedAmount <= 0L) {
            return 0L;
        }
        if (reservedAmount <= 0L) {
            return storedAmount;
        }
        return Math.max(0L, storedAmount - reservedAmount);
    }

    /**
     * <b>多台仓 / 多条总线在同一 tick 内的共享取用预算</b>（必须共享，否则每台仓都以为「还有全部」）。
     *
     * <p>判据本身（{@link #availableForTake}）是「这一刻网络里还有多少不被预留」的<b>静态</b>读数：
     * 三台仓各自去读都会得到同一个数，于是三台合计能取走 3 倍。预算账本把「本轮已经承诺出去的量」
     * 记在<b>同一 tick 的同一个网络</b>上，因此合计恒 ≤ 可用量。</p>
     *
     * <p>账本只记「承诺量」：{@code claim} 返回多少就代表调用方<b>获准</b>取多少，调用方必须按返回值
     * 夹自己的实际取用量（取不到就按既有规则等待 / 上报缺料，绝不因为「账本说可以」就硬抽）。</p>
     */
    public static final class TakeLedger {
        /** 本轮已承诺出去的资源量（按资源汇总）。 */
        private final java.util.Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long> granted =
            new java.util.LinkedHashMap<>();
        /** 本账本所属的作用域（网络对象身份；换网络即作废）。 */
        private Object scope;
        /** 本账本所属的游戏刻（换 tick 即作废）。 */
        private long tick = Long.MIN_VALUE;

        /** 本轮该资源已承诺出去多少。 */
        public long granted(final com.refinedmods.refinedstorage.api.resource.ResourceKey resource) {
            return granted.getOrDefault(resource, 0L);
        }

        /**
         * 换网络 / 换 tick 就作废并重开一轮（<b>同 tick 同网络内不清空</b>，这正是「合计不超可用量」的
         * 依据）。
         *
         * @param scopeKey  作用域标识（用<b>对象身份</b>比较，刻意不用 {@code equals}：
         *                  两个内容相同的网络也是两个网络）
         * @param gameTime  游戏刻
         */
        public void beginTick(final Object scopeKey, final long gameTime) {
            if (scopeKey != scope || gameTime != tick) {
                scope = scopeKey;
                tick = gameTime;
                granted.clear();
            }
        }

        /** 该资源在「可用量」里还剩多少没被本轮承诺出去。 */
        public long remaining(final com.refinedmods.refinedstorage.api.resource.ResourceKey resource,
                              final long availableTotal) {
            return Math.max(0L, availableTotal - granted(resource));
        }

        /**
         * 申请本轮取用 {@code want}（调用方自己已经按需要量夹过）。
         *
         * @param want           想取多少
         * @param availableTotal 该资源本轮的可用量（{@link #availableForTake} 的结果）
         * @return 获准取用的量（{@code 0} = 本轮别人已经把它占满了 ⇒ 调用方等待 / 上报缺料）
         */
        public long claim(final com.refinedmods.refinedstorage.api.resource.ResourceKey resource,
                          final long want, final long availableTotal) {
            if (resource == null || want <= 0L) {
                return 0L;
            }
            final long give = Math.min(want, remaining(resource, availableTotal));
            if (give > 0L) {
                granted.merge(resource, give, Long::sum);
            }
            return give;
        }

        /** 本轮已经承诺出去的资源种数（诊断用）。 */
        public int size() {
            return granted.size();
        }

        /** 立刻作废（拆网络 / 换维度等；正常路径不需要调用）。 */
        public void clear() {
            granted.clear();
            scope = null;
            tick = Long.MIN_VALUE;
        }
    }

    /**
     * 进程内<b>唯一一个</b>共享预算槽位：同一（网络 + 游戏刻）内所有取用者共用一本账。
     * <p>为什么单一槽位：网络对象随加载 / 卸载持续产生，用 {@code Map} 缓存会随存档无界增长；
     * 而「同一刻只有一张网络在被服务」在服务端恒成立，换网络就重开一轮即可（重置只会让本轮的
     * 约束更紧，绝不会让谁多取）。</p>
     */
    private static final TakeLedger SHARED_LEDGER = new TakeLedger();

    /**
     * 取「本轮共享预算账本」（见 {@link #SHARED_LEDGER}）。
     *
     * @param scopeKey  作用域标识（<b>用网络对象本身</b>；按对象身份比较）
     * @param gameTime  游戏刻
     */
    public static TakeLedger sharedLedger(final Object scopeKey, final long gameTime) {
        SHARED_LEDGER.beginTick(scopeKey, gameTime);
        return SHARED_LEDGER;
    }

    /** 取物品 {@code CustomData} 的底层 tag（空 / 无数据返回 null；只读，故用非弃用的 {@code copyTag}）。 */
    @Nullable
    private static CompoundTag customDataOf(final ItemStack stack) {
        final CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.isEmpty() ? null : data;
    }
}
