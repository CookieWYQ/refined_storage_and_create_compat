package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 「单元样板查重」的唯一判据与扫描入口（服务端）。
 *
 * <h2>什么叫「完全相同」（本功能的核心定义）</h2>
 * 只比<b>语义</b>，不比「叫什么名字 / 放在哪个舱的哪一格」：
 * <ol>
 *     <li><b>操作类型相同</b>：即该步的配方类型（{@code create:pressing} 这类 id）。
 *     老样板把类型写在 {@code Machine} 里、新样板写在 {@code RecipeType} 里，取「非空优先」归一化后再比 ——
 *     否则「JEI 导入的冲压」与「手写的冲压」会被误判成两种东西（用户要求：冲压这种没有输入式原料的操作，
 *     样板一定完全一样）；</li>
 *     <li><b>是否需要输入原料相同</b>（{@code RequiresInput}）；</li>
 *     <li><b>输入式原料完全相同</b>：输入物品按「物品 + 数据组件」比对（<b>不</b>比数量：数量是终端标记槽里的
 *     残留值，属于「用多少」而不是「用什么」，用户举的「机械手使用」例子关心的正是「原料是不是同一种」）；</li>
 *     <li><b>输入流体完全相同</b>：流体种类 + 数据组件 + 数量（mB）都要一致（灌注 / 注液一类步骤）。</li>
 * </ol>
 * <p><b>必须参与比对的字段</b>：{@code Recipe}（这条步骤服务哪条序列装配配方）与 {@code Step}
 * （它是该配方的第几步）。原因见 {@link #sameSemantics}：没有输入式原料的操作（冲压 / 切割）
 * 「看起来一样」，把它们判成同一张会让整条排线的单元样板被全部跳过 ⇒ 绑定 NOBODY ⇒ 下单毫无反应。
 * 因此「同一条配方的同一个步骤」才叫重复；老样板缺这两个字段时<b>不判重</b>。</p>
 * <p><b>明确不参与比对的字段</b>：显示名（{@code DisplayName}，名字不同也算相同）、所在的执行舱 / 槽位
 * （位置无关）、以及旧的定位字段 {@code Crafter}。
 * <b>注意（2026-10-04 第二轮起已过时）</b>：本段原先写「单元样板的输入只存具体物品栈，<b>没有</b>标签型
 * 多选输入字段」。自新增 {@code SequencePatternData#TAG_INPUT_CANDIDATES}（标签型 ingredient 的整组候选
 * 随样板落盘）后这句话不再成立 —— 候选组现在<b>确实</b>参与比对，见 {@link #sameCandidates} 与
 * {@link #candidateSetOf}：双方都记了候选才比，任一方没有（老样板）视为「未知」不算差异。
 * 标签型整组候选的展开实现仍在 {@code SequencedRecipeProbe}。</p>
 *
 * <h2>扫描范围（= 单元样板管理舱界面里展示的全部样板）</h2>
 * <ul>
 *     <li><b>每一台执行舱</b>的单元样板槽（{@code unitSlots}，按坐标升序，与界面分组顺序一致）；</li>
 *     <li><b>每一台序列装配样板终端的「单元样板库」</b>（旧数据，界面里那个「只出不进」的分组）。</li>
 * </ul>
 * <p>两者合起来正好等于玩家在单元样板管理舱里能看到的全部样板，因此「界面上看不到重复」成立时，
 * 就不会再生成重复样板。本类只读：不写任何容器、不复制也不销毁任何样板物品。</p>
 */
public final class UnitPatternDedupe {
    private UnitPatternDedupe() {
    }

    /**
     * 一次命中（用于给玩家「复用哪一个」的明确反馈）。
     *
     * @param source 命中来源（执行舱名 + 坐标 / 终端旧库，已做成可直接展示的组件）
     * @param slot   该来源里的槽位下标（0 起）
     */
    public record Match(Component source, int slot) {
    }

    /**
     * 一次<b>可落日志</b>的查重结论：候选 + 命中来源 + 槽位 + 判据摘要。
     *
     * <h2>为什么必须在扫描时就把它造出来（§7.3 审计的取证缺口）</h2>
     * <p>旧实现里 {@link #findDuplicate} <b>已经算出了</b> {@link Match}（来源 + 槽位），
     * 但调用方 {@code SequencePatternTerminalBlockEntity} 只留下一个布尔值：
     * {@code stepDuplicateExists} 把 {@code Match} 丢掉，只在「<b>全部</b>步骤都被跳过」时补一条
     * {@code all N unit step(s) skipped as duplicates}。于是实机日志里那三次
     * {@code terminal generate: all 2 unit step(s) skipped as duplicates} <b>无法审计</b> ——
     * 事后翻存档，当时网络里已经没有任何一条列车轨道的 deploying 单元样板，
     * 因此「它到底跟哪一张判成了重复」永久不可复现（审计原文：「命中来源不可复现，
     * 缺的正是 Match 日志」）。</p>
     *
     * <p>本记录让每一次判定都带上「跟谁重复」：{@link #source()} / {@link #slot()} 直接说清
     * 命中位置，{@link #basis()} 是判据摘要（配方 + 步序 + 操作类型 + 代表物 + 候选组），
     * 于是「板子被判重跳过」这件事在日志里自证，不再依赖事后翻存档猜。</p>
     *
     * @param candidate 被判定的一方（终端流程里那一步的单元样板）
     * @param match     命中的既有样板（{@code null} = 没有重复）
     * @param basis     判据摘要（人类可读的一行；无重复时也给出，便于核对「为什么没判重」）
     */
    public record Decision(ItemStack candidate, @Nullable Match match, String basis) {
        /** 是否判定为重复。 */
        public boolean duplicate() {
            return match != null;
        }
    }

    /** 这张物品是不是本模组的单元样板（非单元样板一律不参与比对）。 */
    public static boolean isUnitPattern(@Nullable final ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get());
    }

    /**
     * 归一化后的「操作类型」：新语义的 {@code RecipeType} 优先，为空时回退到旧的 {@code Machine}。
     * <p>trim + 小写归一，避免大小写 / 空格差异被误判成不同操作。</p>
     */
    public static String operationOf(final ItemStack unit, final HolderLookup.Provider registries) {
        if (!isUnitPattern(unit)) {
            return "";
        }
        final SequencePatternData.UnitMeta meta = SequencePatternData.readUnitNew(unit, registries);
        final String recipeType = meta.recipeType() == null ? "" : meta.recipeType();
        if (!recipeType.trim().isEmpty()) {
            return recipeType.trim().toLowerCase(Locale.ROOT);
        }
        final SequencePatternData.UnitData data = SequencePatternData.readUnit(unit, registries);
        final String machine = data == null || data.machine() == null ? "" : data.machine();
        return machine.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 两张单元样板是否「语义完全相同」（判据见类注释）。
     * <p>任一张不是单元样板 → 一律 {@code false}（绝不去猜）。</p>
     *
     * <h2>为什么判重必须纳入「所服务的配方 + 该步在配方中的步序」（第 21 轮最高优先修复）</h2>
     * <p>旧口径只比「操作类型 + requiresInput + 输入式原料 + 输入流体」，并按旧注释把
     * {@code Recipe}/{@code Step} 当成「只是溯源信息、不参与比对」。但<b>没有输入式原料</b>的操作
     * （冲压 / 切割一类）本来就长得一模一样 —— 于是「精密构件的冲压步」与「列车轨道的冲压步」
     * 被判成同一张，整条列车轨道排线的单元样板被全部跳过（实机日志
     * {@code terminal generate: all 2 unit step(s) skipped as duplicates -> total pattern only}）。
     * 后果是连锁的：排线里那两格没有列车轨道自己的单元样板 ⇒ 执行舱的 {@code chamberSteps} 只剩
     * 另一条配方的步 ⇒ 该步 {@code stepOwner=NOBODY} ⇒ 拉料被拒（{@code no_machine_owns_step}）
     * ⇒ <b>下单 1 个或 64 个都毫无反应</b>；同时列车轨道的 {@code track:0} 类别被混进了另一条线的仓。</p>
     * <p>用户自己的心智模型就是「显示该步骤<b>是否已包含相同</b>的单元样板存在于序列层中」——
     * 即「<b>同一条配方的同一个步骤</b>」，而不是「操作看起来一样」。因此这里要求
     * {@code Recipe} 相同<b>且</b> {@code Step} 相同，才是重复。</p>
     * <p><b>老样板兼容</b>：老样板缺 {@code Recipe}/{@code Step}（读出来是空串 / {@code -1}）时
     * <b>一律不判重</b>（返回 {@code false}）—— 宁可不判重（多生成一张），也绝不漏生成
     * （那会让整条排线绑定不上、下单毫无反应）。</p>
     * <p>「JEI 导入 vs 手写」的归一化（{@code RecipeType} 非空优先、回退 {@code Machine}）保持不变。</p>
     */
    public static boolean sameSemantics(@Nullable final ItemStack first,
                                        @Nullable final ItemStack second,
                                        final HolderLookup.Provider registries) {
        if (!isUnitPattern(first) || !isUnitPattern(second)) {
            return false;
        }
        if (!operationOf(first, registries).equals(operationOf(second, registries))) {
            return false;
        }
        // ① 必须能读出「服务配方 + 步序」：读不出（老样板）⇒ 不判重（宁可多生成，绝不漏生成）
        final SequencePatternData.UnitData dataA = SequencePatternData.readUnit(first, registries);
        final SequencePatternData.UnitData dataB = SequencePatternData.readUnit(second, registries);
        if (dataA == null || dataB == null) {
            return false;
        }
        final String recipeA = dataA.recipe() == null ? "" : dataA.recipe().trim();
        final String recipeB = dataB.recipe() == null ? "" : dataB.recipe().trim();
        if (recipeA.isEmpty() || recipeB.isEmpty()) {
            return false;
        }
        // ② 同一条配方（大小写不敏感，避免「导入 / 手写」的大小写差异被当成两条配方）
        if (!recipeA.equalsIgnoreCase(recipeB)) {
            return false;
        }
        // ③ 同一配方里的同一步序
        if (dataA.step() < 0 || dataB.step() < 0 || dataA.step() != dataB.step()) {
            return false;
        }
        final SequencePatternData.UnitMeta metaA = SequencePatternData.readUnitNew(first, registries);
        final SequencePatternData.UnitMeta metaB = SequencePatternData.readUnitNew(second, registries);
        if (metaA.requiresInput() != metaB.requiresInput()) {
            return false;
        }
        if (!sameInput(inputOf(first, registries), inputOf(second, registries))) {
            return false;
        }
        // ⓪ 「输入原料组候选」必须参与比对（列车轨道：铁粒 / 锌粒候选未显示）。
        // 为什么：查重口径是「同一条配方的同一步骤 ⇒ 一定完全一样」，而候选组<b>也</b>是该步的语义
        // （「铁粒或锌粒」与「只要铁粒」不是同一步）。旧样板（没有候选 tag）与升级前生成的样板
        // 一律视为「候选未知」⇒ 按既有行为只比代表物，绝不因此漏生成（否则整条排线又绑定不上）。
        if (!sameCandidates(candidateSetOf(first, registries), candidateSetOf(second, registries))) {
            return false;
        }
        return sameFluid(SequencePatternData.readUnitFluid(first, registries),
            SequencePatternData.readUnitFluid(second, registries));
    }

    /**
     * 判据摘要（人类可读一行；只读）：配方 + 步序 + 操作类型 + 代表物 + 候选组。
     *
     * <h2>为什么给日志单独做一个摘要</h2>
     * <p>用户与下一位接手要回答的是「<b>为什么</b>它被判成重复 / 为什么不判重复」，而
     * {@link #sameSemantics} 只给出一个布尔值。把判据的<b>每一个分量</b>打进同一行，才能事后核对
     * 是哪一个分量起了作用（例如：候选组一字之差让两条本该不同的步骤被当成同一条，或反过来）。</p>
     */
    public static String basisOf(@Nullable final ItemStack unit, final HolderLookup.Provider registries) {
        if (!isUnitPattern(unit)) {
            return "candidate=not_a_unit_pattern";
        }
        final SequencePatternData.UnitData data = SequencePatternData.readUnit(unit, registries);
        final SequencePatternData.UnitMeta meta = SequencePatternData.readUnitNew(unit, registries);
        final StringBuilder out = new StringBuilder(96);
        out.append("recipe=").append(data == null || data.recipe() == null || data.recipe().isBlank()
            ? "?" : data.recipe().trim());
        out.append(" step=").append(data == null ? -1 : data.step());
        out.append(" op=").append(operationOf(unit, registries));
        out.append(" input=").append(itemIdOf(data == null ? ItemStack.EMPTY : data.input()));
        out.append(" candidates=").append(candidateIdsOf(candidateSetOf(unit, registries)));
        out.append(" requiresInput=").append(meta.requiresInput());
        return out.toString();
    }

    /** 命中来源的可读文本（{@link Match#source()} 是本地化组件，日志里取字面量即可）。 */
    public static String describe(@Nullable final Match match) {
        return match == null ? "-" : match.source().getString();
    }

    /** 物品注册名（取不到 {@code -}）。 */
    private static String itemIdOf(@Nullable final ItemStack stack) {
        return stack == null || stack.isEmpty() ? "-" : RsccAssemblyDebug.itemId(stack.getItem());
    }

    /** 候选组 → {@code [铁粒,锌粒]} 形式的注册名列表（空 = 老样板 / 未知）。 */
    private static String candidateIdsOf(final List<ItemStack> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "-";
        }
        final StringBuilder out = new StringBuilder(48);
        out.append('[');
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(itemIdOf(candidates.get(i)));
        }
        return out.append(']').toString();
    }

    /** 该单元样板记录的「输入原料组候选」（老样板无此字段 ⇒ 空表 = 未知）。 */
    public static List<ItemStack> candidateSetOf(final ItemStack unit, final HolderLookup.Provider registries) {
        final SequencePatternData.UnitData data = SequencePatternData.readUnit(unit, registries);
        return data == null || data.inputCandidates() == null ? List.of() : data.inputCandidates();
    }

    /**
     * 候选组比对：双方都记了候选才比（按物品种类集合，顺序无关）；任一方没记 ⇒ 视为「未知」不算差异。
     * <p>这样「旧存档里的列车轨道样板（无候选）」不会被新生成的那张判成重复而被跳过，
     * 玩家重新生成一次即可把锌粒补进 RS 样板。</p>
     */
    private static boolean sameCandidates(final List<ItemStack> first, final List<ItemStack> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return true;
        }
        if (first.size() != second.size()) {
            return false;
        }
        for (final ItemStack candidate : first) {
            boolean found = false;
            for (final ItemStack other : second) {
                if (candidate.is(other.getItem())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** 该单元样板记录的输入物（旧样板无记录时为空栈）。 */
    public static ItemStack inputOf(final ItemStack unit, final HolderLookup.Provider registries) {
        final SequencePatternData.UnitData data = SequencePatternData.readUnit(unit, registries);
        return data == null || data.input() == null ? ItemStack.EMPTY : data.input();
    }

    /** 输入物比对：物品 + 数据组件（不比数量，理由见类注释）。 */
    private static boolean sameInput(final ItemStack first, final ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return ItemStack.isSameItemSameComponents(first, second);
    }

    /** 输入流体比对：种类 + 数据组件 + 数量（mB）。 */
    private static boolean sameFluid(final FluidStack first, final FluidStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return FluidStack.isSameFluidSameComponents(first, second) && first.getAmount() == second.getAmount();
    }

    /**
     * 在指定网络的<b>全部单元样板来源</b>里找第一张与 {@code candidate} 语义完全相同的样板。
     * <p>顺序确定（执行舱按坐标升序 → 各自槽位升序 → 终端旧库同理），因此反馈里指向的来源稳定可复现。
     * 只读扫描，不修改任何容器。</p>
     *
     * @return 命中（含来源与槽位）；没有任何重复时返回 {@code null}
     */
    @Nullable
    public static Match findDuplicate(@Nullable final Network network,
                                      @Nullable final ItemStack candidate,
                                      final HolderLookup.Provider registries) {
        if (network == null || !isUnitPattern(candidate)) {
            return null;
        }
        for (final SequenceExecutionChamberBlockEntity chamber : UnitManagerSources.chambers(network)) {
            final Component source = Component.literal(chamber.getChamberDisplayName() + " @"
                + chamber.getBlockPos().getX() + "," + chamber.getBlockPos().getY()
                + "," + chamber.getBlockPos().getZ());
            for (int slot = 0; slot < chamber.unitSlots.getContainerSize(); slot++) {
                if (sameSemantics(candidate, chamber.unitSlots.getItem(slot), registries)) {
                    return new Match(source, slot);
                }
            }
        }
        for (final SequencePatternTerminalBlockEntity terminal : UnitManagerSources.terminals(network)) {
            final Component source =
                Component.translatable(UnitPatternManagerBlockEntity.TERMINAL_GROUP_KEY);
            final int slots = terminal.unitLibrary.getSlots();
            for (int slot = 0; slot < slots; slot++) {
                if (sameSemantics(candidate, terminal.unitLibrary.getStackInSlot(slot), registries)) {
                    return new Match(source, slot);
                }
            }
        }
        return null;
    }

    /**
     * 任一方块实体所属的 RS 网络（未接网络 / 不是网络节点方块实体时返回 {@code null}）。
     * <p>用 {@link RsccNodeContainerAccess} 读基类受保护字段 {@code mainNetworkNode}
     * （见 {@code mixin/accessor/AbstractNetworkNodeContainerBlockEntityAccessor}），
     * 再取节点自身的网络 —— 与 {@code AssemblyWatchdog} 同一口径。</p>
     */
    @Nullable
    public static Network networkOf(@Nullable final BlockEntity blockEntity) {
        if (!(blockEntity instanceof RsccNodeContainerAccess access)) {
            return null;
        }
        final NetworkNode node = access.rscc$mainNetworkNode();
        return node == null ? null : node.getNetwork();
    }
}
