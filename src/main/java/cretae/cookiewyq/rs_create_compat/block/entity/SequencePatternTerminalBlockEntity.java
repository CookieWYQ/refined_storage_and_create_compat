package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.common.api.autocrafting.Autocrafter;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.item.SequenceUnitPatternItem;
import cretae.cookiewyq.rs_create_compat.network.SequenceExecutionChamberNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.SequencePatternTerminalNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.GhostContent;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.UnitManagerSources;
import cretae.cookiewyq.rs_create_compat.support.UnitPatternDedupe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列装配样板终端方块实体：纯样板编辑终端（无 RS 网络节点）。
 * <ul>
 *     <li>单元样板库（108 格起、可增长）+ 流程编排（无上限，可滚动浏览，各步带重复次数）；</li>
 *     <li>底部样板区（v10）：<b>1 格总样板槽</b>（只出不进；内部仍 9 格，界面只暴露第 1 格）
 *     + <b>6 格单元样板槽窗口</b>（单元样板库的可翻页窗口，越界格不可交互）；</li>
 *     <li>整体循环次数 + 原料 / 预期产物 / 废料区；</li>
 *     <li>一键生成：总样板 1 张 + 流程每一步 1 张单元样板（真实物品），并按产出张数消耗
 *     {@code refinedstorage:pattern}（见 {@link #generationPatternCost()}）。</li>
 * </ul>
 * <h2>导入 = 只展示，生成 = 才产出（v8 核心）</h2>
 * <p>导入配方只把「展开后的流程」写进<b>展示数据</b>（{@link #displayArrangement}）：流程行、原料、
 * 产物、废料照常显示，但<b>不产生任何真实物品</b>（不写样板槽、不写单元样板库、不给玩家东西），
 * 因此拆掉方块只会掉出玩家真正投入过的东西（用户实测：旧版导入后拆方块会掉一堆样板）。</p>
 * <p>展示数据可持久化（NBT 键 {@code DisplayArrangement}），界面重开 / 区块重载后仍在；
 * 老存档里 {@link #arrangement}（真实容器）里已有的样板既照常显示（展示数据为空时回退展示它），
 * 也照常随方块掉落，一格不丢。</p>
 * <p>流程编排不再有固定步数上限：存储（展示数据或老存档的真实编排容器）与次数表随步数动态伸缩，
 * 菜单/界面通过"滚动窗口"（{@link #ARRANGEMENT_WINDOW} 行）浏览任意多的步骤。</p>
 */
public class SequencePatternTerminalBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<SequencePatternTerminalNetworkNode> {
    /** 界面一次可见的流程行数（滚动窗口高度，v3 文档：8）。流程总步数不设上限。 */
    public static final int ARRANGEMENT_WINDOW = 8;
    /**
     * 每一步重复次数范围。
     * <p><b>为什么保留一个防御性上限</b>：重复次数只由「导入时把相邻的完全相同步骤合并」产生
     * （一列 = 一段连续相同步骤，N = 该段长度），真实 Create 配方里 N 恒为个位数；
     * 因此这个上界<b>永远不会拦住任何合法配方</b>，只是防御损坏的 NBT / 异常数据把计数表撑爆。
     * 它<b>不是</b>「最多 64」那类会给玩家造成困扰的隐含限制（用户已明确要求移除 64 上限）。</p>
     */
    public static final int COUNT_MIN = 1;
    public static final int COUNT_MAX = 100_000;
    /** 单元样板制作可选的 Create 机器类型（键见 lang machine.rs_create_compat.*，JEI 配方导入用）。 */
    public static final List<String> MACHINE_TYPES = List.of(
        "Pressing", "Cutting", "DeployerApplication", "Filling", "Spouting");
    /** 产物/废料总容量（v3 文档：可见 4×3=12，容量 2 页共 24，超出可见窗口时靠滚动条浏览）。 */
    public static final int RESULT_SIZE = 24;
    public static final int SCRAP_SIZE = 24;
    /** 样板槽容量：只存放本模组的序列装配总样板（一格一张；生成 / 导入不再消耗材料，v2 文档：9 格横排）。 */
    public static final int PATTERN_SLOT_SIZE = 9;
    /**
     * 界面暴露的总样板格数（v10：<b>恰好 1 格</b>）。
     * <p><b>为什么内部容量仍是 9</b>：容量 / NBT 键一旦改变，老存档里第 2~9 格的样板就会无处安放
     * （等于销毁玩家物品）。所以内部照旧 9 格、登记与掉落继续扫描全部 9 格，只把<b>界面</b>暴露第 1 格；
     * 该格被取空时自动把后面的样板前移，玩家仍能把 9 张一张张全部取回
     * （见 {@link #compactPatternSlots()}）。</p>
     * <p><b>这一格为什么不可手动放入</b>：用户要求「总样板槽只应该有一个、不可手动放入、总样板只生成一个；
     * 要再生成新的，必须先把现有的全部拿走」。因此它是<b>只出不进</b>的产出格：
     * 服务端在生成时写入，玩家只能取出（菜单侧 {@code TotalPatternSlot#mayPlace} 恒 false）。</p>
     */
    public static final int PATTERN_VISIBLE = 1;
    /**
     * 单元样板槽：界面一次可见的格数（= 单元样板库的翻页窗口宽度；第 k 页第 i 格 → 库下标 k*6+i）。
     * v9 由 3 扩到 6：用户要求「紧邻总样板槽右侧的单元样板槽区域扩大，在可用宽度内尽量多放可见格」。
     * 底部那一行只有 250px 可放（256 - 2×3px 容器边框），扣掉 1 格总样板槽（17px）、
     * 两个正常字号标签（「总样板」27px + 「单元」18px）与一条翻页小滚动条（7px）后，
     * 6 格（107px）正好放得下且左右各留 4px 白，滚动条落在两组槽之间的断开区；
     * 7 格会超宽（只能靠删标签或砍掉滚动条），因此取 6 格 —— 已满足「目标 ≥6 格」。</p>
     */
    public static final int UNIT_WINDOW = 6;
    /** 单元样板库<b>初始</b>容量（旧上限 108；现无上限，越界写入自动扩容，仍用于旧存档默认尺寸）。 */
    public static final int UNIT_LIBRARY_SIZE = 108;

    private static final Logger LOGGER = LoggerFactory.getLogger(SequencePatternTerminalBlockEntity.class);

    /** 单元样板库（可增长、无上限；按槽位顺序存放，不按种类合并）。 */
    public final GrowingUnitLibrary unitLibrary = new GrowingUnitLibrary(UNIT_LIBRARY_SIZE);
    /** 流程编排：可增长存储；容量只增不减（手动减步仅"收窄激活行数"，不删除已放单元）。 */
    public final GrowingStackHandler arrangement = new GrowingStackHandler(ARRANGEMENT_WINDOW);
    /**
     * 流程编排的<b>展示数据</b>（v8，导入路径的唯一落点）。
     * <p><b>为什么单独开一个容器</b>：导入只该「让玩家看清这条配方怎么走」，不该产出真实物品。
     * 这个容器实现 {@link GhostContent} —— 即便将来有人误把它加进
     * {@link #getDroppableHandlers()}，{@code BlockContentReleaser} 也会统一跳过，因此
     * 「导入过配方 → 拆方块掉一堆样板」这条 bug 在两道防线上都不可能复现。</p>
     * <p>它照样持久化（NBT 键 {@code DisplayArrangement}），因此界面重开 / 区块重载后展示仍在。</p>
     */
    public final GhostGrowingHandler displayArrangement = new GhostGrowingHandler(ARRANGEMENT_WINDOW);
    /**
     * 流程编排当前展示的是哪一份数据：{@code true} = {@link #displayArrangement}（导入产生的展示数据）；
     * {@code false} = {@link #arrangement}（老存档的真实容器）。
     * <p>判定依据是「展示数据非空」：新存档导入后恒为 true；老存档没有 {@code DisplayArrangement} 标签
     * （或它为空）时为 false，于是老档的流程照常显示、照常掉落 —— 两者都不会丢物品。</p>
     */
    private boolean displayArrangementActive;
    /**
     * 当前启用的流程步数（0 = 空编排；导入配方后按真实步骤数写入）。
     * <p><b>为什么初值是 0</b>：新建（还没导入配方）时流程编排本来就该是空的 ——
     * 此前初值取了窗口高度 8，于是界面一打开就凭空多出 8 个空行 / 序号（用户实测问题）。
     * 步数只由导入 / 生成路径写入，窗口内超出的行由 {@code ArrangementRowSlot#isActive()} 屏蔽。</p>
     */
    public int arrangementSize;
    /** 每一步重复次数（长度 >= arrangementSize，与 arrangement 容量同步增长）。 */
    public final List<Integer> arrangementCounts = new ArrayList<>();
    // ---------- 新语义（v4）：每一步的机器指派 ----------
    /** 每一步（全局下标）指派的执行仓位置；null = 未指派。与 arrangementSize 对齐。 */
    private final List<BlockPos> stepMachinePos = new ArrayList<>();
    /** 每一步（全局下标）指派的执行仓名（"" = 未指派）。与 arrangementSize 对齐。 */
    private final List<String> stepMachineNames = new ArrayList<>();
    /**
     * 每一步（全局下标）生成时是否<b>跳过已存在的相同单元样板</b>。与 arrangementSize 对齐（只增不减）。
     * <h2>为什么这个开关属于「每一步」</h2>
     * <p>单元样板本身<b>不绑定机器</b>（{@link UnitPatternDedupe} 判重只看语义：操作类型 + 是否需要
     * 输入 + 输入物 + 输入流体，名字与所在容器一律不比）；真正绑定机器、且逐台机器执行的是
     * <b>流程编排的每一步</b>。因此「这一步要不要跳过重复生成」是<b>步骤</b>的属性 —— 既不挂在机器上
     * （同一步可能换机器），也不做成终端全局开关（不同步骤的重复情况本就不同）。</p>
     * <p>默认值：新步骤初始固定为「开」（{@link #defaultStepSkipDuplicate()}）；<b>旧存档缺该 NBT 键 ⇒ 默认开</b>
     * （与默认档一致），旧存档里已存的每步值一律按 NBT 原样保留。</p>
     */
    private final List<Boolean> stepSkipDuplicate = new ArrayList<>();
    /**
     * 「每一步当前是否已存在语义完全相同的单元样板」的<b>服务端扫描缓存</b>。
     * <p><b>为什么缓存</b>：判重要遍历网络内全部执行仓的单元槽 + 全部终端的单元样板库；而
     * {@link #generationPatternCost()}（消耗估算）会被 ContainerData <b>每 tick</b> 读取，逐 tick 现扫
     * 明显浪费。缓存只在「快照下发前 / 生成前 / 结构变化时」刷新（见 {@link #refreshStepDuplicateCache()}），
     * 生成前必定重扫 ⇒ 真正的判定永远权威，展示值至多落后一次快照。不持久化。</p>
     */
    private final List<Boolean> stepDuplicateCache = new ArrayList<>();
    /**
     * 与 {@link #stepDuplicateCache} <b>逐下标平行</b>的「命中来源 + 槽位」缓存（{@code null} = 该步没有重复）。
     *
     * <h2>为什么要多存一份（§7.3 审计的取证缺口）</h2>
     * <p>{@link UnitPatternDedupe#findDuplicate} 本来就把「跟哪一张判成了重复」算了出来
     * （来源 + 槽位），但旧实现只留一个布尔值、把来源当场丢掉 —— 于是实机那三次
     * {@code terminal generate: all 2 unit step(s) skipped as duplicates} 事后<b>完全不可复现</b>：
     * 存档里那两条样板已经不在了，谁也说不清当时是跟哪一张判重、又是哪一个判据分量起了作用。</p>
     * <p>这里把来源与槽位一起缓存，供生成路径落 {@code [rscc-dedupe]} 日志（判据摘要由
     * {@link UnitPatternDedupe#basisOf} 现场生成）。与布尔缓存同一生命周期（不持久化）。</p>
     */
    private final List<UnitPatternDedupe.Match> stepDuplicateMatch = new ArrayList<>();
    /** 最近一次导出装配样板时的问题（null = 无问题）；供前端读取展示，不持久化。 */
    @org.jetbrains.annotations.Nullable
    private net.minecraft.network.chat.Component lastExportError;
    /** 单元样板制作：输入标记槽（不消耗）。 */
    public final ItemStackHandler unitInputSlot = new ItemStackHandler(1);
    /** 单元样板制作：输出槽（生成的单元样板）。 */
    public final ItemStackHandler unitOutputSlot = new ItemStackHandler(1);
    /** 单元样板制作：当前选中的自动合成仓索引（网络中第几个自动合成仓）。 */
    public int autocrafterIndex;
    /** 装配样板槽（生成结果）。 */
    public final ItemStackHandler assemblyPatternSlot = new ItemStackHandler(1);
    /** 原料槽（<b>ghost 标记</b>：只作界面展示，值由导入 / 生成逻辑写入，永不参与掉落结算）。 */
    public final ItemStackHandler ingredientSlot = new GhostHandler(1);
    /**
     * 起步原料的<b>全部候选</b>（标签型 ingredient，如列车轨道的「任意台阶」）。
     *
     * <p>2026-10-05 新增。{@link #ingredientSlot} 只能放<b>一件</b>，因此整组候选在这里单独保存：
     * 生成总样板时写进 {@code AssemblyData#ingredientCandidates}（→ NBT + RS 样板的 ingredient 多候选），
     * 界面顶部的输入原料格也读它做轮播。老存档没有这一项 ⇒ 空表 ⇒ 全部退回代表物（与改动前一致）。</p>
     */
    private final List<ItemStack> ingredientCandidates = new ArrayList<>();

    /** 起步原料的全部候选（空表 ⇒ 只有代表物一件）。 */
    public List<ItemStack> ingredientCandidates() {
        return List.copyOf(ingredientCandidates);
    }

    /**
     * <b>老样板自愈：把「输入原料组候选」按配方补算并写回单元样板</b>（2026-10-05 新增）。
     *
     * <h2>为什么必须有它（用户实测「修复毫无成效」的真正原因）</h2>
     * <p>候选组只可能在「JEI 导入配方那一刻」写进样板物。而用户手上的编排 / 总样板是<b>修复之前</b>
     * 生成的 ⇒ 那些物品的 NBT 里<b>根本没有</b> {@code InputCandidates}。
     * 界面回落顺序是「配方回查 → 样板落盘候选 → 代表物一件」，落盘候选那一级对老数据恒为空，
     * 于是「铁粒 或 锌粒」永远只显示铁粒、台阶永远只显示石头台阶 —— 用户看到的现象与修复前完全一样。</p>
     *
     * <p>实测证据：关服存档解压后含 {@code DisplayArrangement} / {@code sequence_unit_pattern}，
     * 但整个存档里 {@code InputCandidates} 出现 <b>0</b> 次（{@code tools/inspect_save_candidates.py}）。</p>
     *
     * <h2>做法（只读配方 + 追加写；不改任何既有 NBT 语义）</h2>
     * <p>逐步骤：当该步样板<b>已经记了配方 id</b>、但候选组 &lt; 2 件时，用
     * {@link cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe#applicationCandidatesOf}
     * 现场按配方重算，重算结果 ≥ 2 件才写回（与 {@code writeCandidates} 口径一致：单件保持老格式）。
     * 判不出来 / 重算仍为单件 ⇒ 一个字都不改。</p>
     *
     * <p><b>只在服务端调用</b>（需要配方管理器）；幂等 —— 已有候选的步直接跳过，反复调用零开销。</p>
     *
     * @return 实际补写的步数（0 = 没有需要补的）
     */
    public int healUnitCandidateTags() {
        final net.minecraft.world.level.Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return 0;
        }
        final HolderLookup.Provider registries = level.registryAccess();
        int healed = 0;
        for (int i = 0; i < arrangementSize; i++) {
            final ItemStack stack = arrangementUnit(i);
            if (stack.isEmpty()) {
                continue;
            }
            final SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, registries);
            if (unit == null || unit.inputCandidates().size() >= 2) {
                continue; // 没有样板 / 已经有候选组：不动
            }
            final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe =
                stepRecipe(level, unit);
            if (recipe == null) {
                continue; // 手工样板 / 配方查不到：绝不猜
            }
            final List<ItemStack> candidates = SequencePatternData.candidatesOfItems(
                cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                    .applicationCandidatesOf(recipe, unit.input()));
            if (candidates.size() < 2) {
                continue; // 该步本来就是单候选：保持老格式（不写 tag）
            }
            final ItemStack updated = stack.copy();
            // 整条透传，只补候选组 —— 别的一字不动（避免重演 bind/unbind 丢字段的老问题）
            SequencePatternData.writeUnit(updated,
                new SequencePatternData.UnitData(unit.machine(), unit.input(), unit.crafter(),
                    unit.recipe(), unit.step(), unit.requiresInput(), unit.displayName(),
                    unit.recipeType(), candidates), registries);
            setArrangementUnit(i, updated);
            healed++;
        }
        // 起步原料候选：老终端没有这一项，而它决定总样板的 ingredient 是否登记整组候选。
        if (ingredientCandidates.size() < 2 && !ingredientSlot.getStackInSlot(0).isEmpty()) {
            final List<ItemStack> byRecipe =
                ingredientCandidatesByRecipe(level, ingredientSlot.getStackInSlot(0));
            if (byRecipe.size() >= 2) {
                setIngredientCandidates(byRecipe);
                healed++;
            }
        }
        if (healed > 0) {
            setChanged();
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.event("terminal candidate-heal: " + healed
                    + " step(s)/ingredient patched from recipes (old patterns had no InputCandidates)");
            }
        }
        return healed;
    }

    /**
     * 按配方重算「起步原料候选」（老终端自愈用）：拿当前原料槽里的代表物反查它属于哪条序列装配配方的
     * 主原料候选组，命中就返回整组。
     * <p>查不到 / 候选仍为单件 ⇒ 空表（调用方保持原样，绝不猜）。</p>
     */
    private static List<ItemStack> ingredientCandidatesByRecipe(
        final net.minecraft.world.level.Level level, final ItemStack representative) {
        if (level == null || representative.isEmpty()) {
            return List.of();
        }
        try {
            final List<net.minecraft.world.item.crafting.RecipeHolder<
                com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe>> all =
                level.getRecipeManager().getAllRecipesFor(
                    com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType());
            for (final var holder : all) {
                final com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe recipe =
                    holder.value();
                final List<ItemStack> candidates = SequencePatternData.candidatesOfItems(
                    cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                        .candidates(recipe.getIngredient()));
                if (candidates.size() < 2) {
                    continue; // 这条配方的主原料本来就是单件
                }
                for (final ItemStack candidate : candidates) {
                    if (candidate.is(representative.getItem())) {
                        return candidates; // 代表物属于这条配方的候选组 ⇒ 就是它
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方系统异常：返回空表，保持原样
        }
        return List.of();
    }

    /**
     * 设置起步原料的全部候选（服务端权威；导入配方时写入，随 NBT 持久化）。
     */
    public void setIngredientCandidates(@org.jetbrains.annotations.Nullable final List<ItemStack> candidates) {
        ingredientCandidates.clear();
        ingredientCandidates.addAll(SequencePatternData.normalizeCandidates(candidates));
        setChanged();
    }
    /** 预期产物（支持概率；<b>ghost 标记</b>：只是「这条配方会出什么」的展示模板，不是真实资源）。 */
    public final ItemStackHandler resultSlots = new GhostHandler(RESULT_SIZE);
    /** 废料（支持概率；<b>ghost 标记</b>，同 {@link #resultSlots}）。 */
    public final ItemStackHandler scrapSlots = new GhostHandler(SCRAP_SIZE);
    /** 每个产物的概率（0..1）。 */
    public final float[] resultChances = new float[RESULT_SIZE];
    /** 每个废料的概率（0..1）。 */
    public final float[] scrapChances = new float[SCRAP_SIZE];
    /** 整体循环次数。 */
    public int loops = 1;
    /**
     * 样板槽（内部 9 格，界面只暴露前 {@link #PATTERN_VISIBLE} 格）：<b>只接受本模组的序列装配总样板</b>
     * （一格一张）。
     * <p>用户要求：这里只能放「总样板」，单元样板与 RS 自带样板（{@code refinedstorage:pattern}）
     * 一律拒收。判定必须与 GUI 槽位（{@code Slot#mayPlace}）以及物流能力（本 handler 的
     * {@code isItemValid} 同时被两者共用）保持一致，不允许任何旁路。</p>
     * <p><b>为什么容量仍是 9 而界面只显示 1 格</b>：改容量 = 改 NBT 尺寸 = 老存档第 2~9 格的样板无家可归
     * （丢物品）。内部保持 9 格后，这些样板依然被登记、依然随方块掉落，只是要靠
     * {@link #compactPatternSlots()} 前移到那唯一一格才拿得出来。</p>
     */
    public final ItemStackHandler patternSlots = new ItemStackHandler(PATTERN_SLOT_SIZE) {
        @Override
        public boolean isItemValid(final int slot, final net.minecraft.world.item.ItemStack stack) {
            return acceptsPattern(stack);
        }

        @Override
        public int getSlotLimit(final int slot) {
            return 1; // 总样板一格一张
        }

        /**
         * 界面只暴露前 {@link #PATTERN_VISIBLE} 格：当某个<b>可见</b>格被取空时，把靠后的样板整体前移，
         * 否则老存档里第 2~9 格的样板会永远「看得见摸不着」（界面越界格不参与交互）。
         * <p><b>为什么钩在 {@code onContentsChanged} 而不是 {@code setStackInSlot}</b>：
         * 原版槽位取物的路径是 {@code SlotItemHandler#remove → IItemHandler#extractItem}，
         * 它内部直接写 {@code stacks} 并只回调 {@code onContentsChanged(slot)}，
         * <b>不会</b>走 {@code setStackInSlot} —— 挂在后者上会导致「玩家取走样板后隐藏格不前移」。</p>
         * <p>只在「取空可见格」时压缩；放入时不压缩（否则玩家刚放进第 2 格的样板会自己跳到第 1 格）。</p>
         */
        @Override
        protected void onContentsChanged(final int slot) {
            if (slot >= 0 && slot < PATTERN_VISIBLE && getStackInSlot(slot).isEmpty()) {
                compactPatternSlots();
            }
        }
    };

    /** 样板槽唯一判定：只接受本模组的序列装配总样板（与执行器总样板槽同源判定）。 */
    public static boolean acceptsPattern(final net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.is(RS_Create_Compat.SEQUENCE_ASSEMBLY_PATTERN.get());
    }

    // ========== 终端自己的「样板输入槽」（生成耗材的唯一来源） ==========

    /**
     * 终端自己的样板输入槽容量（3 格，只收 {@code refinedstorage:pattern}）。
     * <p><b>为什么必须有它（用户原话）</b>：「我原本用来放样板的地方现在放哪？」「总样板槽为什么
     * 可放入可取出？你是直接从玩家身上扣是吧？」—— 旧实现把生成所需的 {@code refinedstorage:pattern}
     * 直接从玩家背包 + 光标扣除，玩家必须把样板背在身上。</p>
     * <p>现在改为：玩家把样板放进<b>终端自己的这 3 格</b>（随方块 NBT 持久化、破坏方块照常掉落），
     * 生成时只从终端内扣除，玩家不再需要随身携带任何样板。这 3 格与底部「总样板槽」（产出 / 登记
     * 总样板）职责分离：一个是耗材入口，一个是产物出口。</p>
     */
    public static final int RS_PATTERN_SLOT_SIZE = 3;

    /** 生成耗材：终端自有的精致存储样板槽（服务端权威，随 NBT 持久化）。 */
    public final ItemStackHandler rsPatternSlots = new ItemStackHandler(RS_PATTERN_SLOT_SIZE) {
        @Override
        public boolean isItemValid(final int slot, final net.minecraft.world.item.ItemStack stack) {
            return isRefinedStoragePattern(stack);
        }
    };

    /**
     * <b>对外（RS 输出总线 / Jade）暴露的样板槽视图</b>：0..{@link #PATTERN_SLOT_SIZE}-1 段是
     * {@link #patternSlots}（总样板槽，只收本模组的序列装配总样板），紧随其后
     * {@link #RS_PATTERN_SLOT_SIZE} 格是 {@link #rsPatternSlots}（只收 {@code refinedstorage:pattern}）。
     *
     * <h2>为什么要有它（用户报告的缺口）</h2>
     * <p>用户原话：「精致存储原版的样板终端可以直接使用输出总线往里面输入那个样板，但是现在并不行，
     * 因为我用 Jade 并不能查看到里面的样板槽。」—— RS 原版样板机（样板网格）对网络暴露的是它那个
     * <b>只收 {@code PatternItem} 的输入容器</b>（见 {@code PatternGridBlockEntity#getPatternInput()}，
     * 判据 {@code isValidPattern}），因此输出总线能把样板送进去、Jade 也能列出来。此前本终端只把
     * {@link #patternSlots} 交出去：输出总线拿 {@code refinedstorage:pattern} 来喂时被
     * {@link #acceptsPattern} 拒收（这是<b>正确</b>的准入判据，不能改），于是「样板塞不进去」；
     * 而 Jade 也只看到那一段（且它常常是空的，Jade 对空容器不渲染提示框）⇒ 用户看到的就是
     * 「样板槽没被暴露」。</p>
     * <p>现在把两段拼成一次能力查询里的同一个 handler（一个方块实体类型只能有一个同能力 provider，
     * 拼接是唯一办法，RS 自己也是「唯一 provider + 包一个容器」）：RS 输出总线按它的过滤项
     * （总样板 / RS 样板）自然落到对应那一段，Jade 则在同一次查询里看到两段的内容。</p>
     *
     * <h2>为什么内部 9 格照旧全部暴露、界面却只显示 1 格</h2>
     * <p>老存档第 2~9 格的总样板必须仍能被物流搬走（否则「界面看不到 + 物流拿不出」= 丢物品，
     * 见 {@link #PATTERN_VISIBLE} 的说明）。这里刻意<b>不</b>缩容。</p>
     *
     * <h2>边界</h2>
     * <p>只暴露这<b>两段样板槽</b>：单元样板库（{@link #unitLibrary}）、流程编排（{@link #arrangement} /
     * {@link #displayArrangement}）、生成中转槽（{@link #assemblyPatternSlot}）与三个幽灵标记容器
     * （{@link #ingredientSlot} / {@link #resultSlots} / {@link #scrapSlots}）都<b>不</b>在此视图里 ——
     * 前者是终端自己的库（由界面 / 判重逻辑管理），后三者根本不是真实资源（暴露出去等于凭空造物）。</p>
     */
    public final net.neoforged.neoforge.items.IItemHandler exposedPatternSlots =
        cretae.cookiewyq.rs_create_compat.support.PatternSlotExposure.of(patternSlots, rsPatternSlots);

    /**
     * 是否为精致存储样板（{@code refinedstorage:pattern}）。
     * <p>按注册名解析（不硬编码物品引用）：精致存储若是可选前置，编译期引用会崩，因此走注册表。
     */
    public static boolean isRefinedStoragePattern(final net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final net.minecraft.world.item.Item pattern = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("refinedstorage", "pattern"));
        return pattern != null && pattern != net.minecraft.world.item.Items.AIR && stack.is(pattern);
    }

    /** 终端自有的精致存储样板总张数（生成耗材的<b>唯一</b>来源；玩家背包不参与）。 */
    public int countRsPatterns() {
        int total = 0;
        for (int i = 0; i < rsPatternSlots.getSlots(); i++) {
            if (isRefinedStoragePattern(rsPatternSlots.getStackInSlot(i))) {
                total += rsPatternSlots.getStackInSlot(i).getCount();
            }
        }
        return total;
    }

    /**
     * 从终端自有的样板输入槽扣除 {@code amount} 张 {@code refinedstorage:pattern}。
     * <p><b>守恒</b>：只动匹配的格，扣空即置空、未扣完的原样留在原格，绝不复制 / 销毁其它物品；
     * 由调用方先校验数量，因此这里按「有多少扣多少」执行并返回实际扣除数（不足时不会伪造）。
     *
     * @return 实际扣掉的张数
     */
    public int consumeRsPatterns(final int amount) {
        if (amount <= 0) {
            return 0;
        }
        int remaining = amount;
        for (int i = 0; i < rsPatternSlots.getSlots() && remaining > 0; i++) {
            final ItemStack stack = rsPatternSlots.getStackInSlot(i);
            if (!isRefinedStoragePattern(stack)) {
                continue;
            }
            final int take = Math.min(remaining, stack.getCount());
            remaining -= take;
            if (stack.getCount() == take) {
                rsPatternSlots.setStackInSlot(i, ItemStack.EMPTY);
            } else {
                final ItemStack rest = stack.copy();
                rest.shrink(take);
                rsPatternSlots.setStackInSlot(i, rest);
            }
        }
        final int consumed = amount - remaining;
        if (consumed > 0) {
            setChanged();
        }
        return consumed;
    }

    // ========== 总样板槽：界面窗口与老存档压缩 ==========

    /** 压缩递归保护（{@link #compactPatternSlots()} 内部会再写槽位）。 */
    private boolean compactingPatterns;

    /**
     * 把总样板<b>同序前移</b>到最靠前的空位（绝不交换顺序、绝不销毁 / 复制任何一张）。
     * <p><b>为什么必须做</b>：v10 起界面只暴露第 1 格，而内部容量仍是 9 格。
     * 老存档若在第 2~9 格放过样板，不压缩就永远看不到、拿不回来。</p>
     * <p>触发时机只有两处：读档（把老档整体前移）与「可见格被取空」（把隐藏格补上来）；
     * 放入物品时<b>不</b>压缩，保证玩家放的位置不跳。</p>
     */
    public void compactPatternSlots() {
        if (compactingPatterns) {
            return;
        }
        compactingPatterns = true;
        boolean moved = false;
        try {
            int write = 0;
            for (int read = 0; read < patternSlots.getSlots(); read++) {
                final ItemStack stack = patternSlots.getStackInSlot(read);
                if (stack.isEmpty()) {
                    continue;
                }
                if (read != write) {
                    patternSlots.setStackInSlot(write, stack.copy());
                    patternSlots.setStackInSlot(read, ItemStack.EMPTY);
                    moved = true;
                }
                write++;
            }
        } finally {
            compactingPatterns = false;
        }
        if (moved) {
            setChanged();
        }
    }

    /** 内部格位（第 2~9 格）里还藏着多少张总样板：界面据此提示玩家「还有样板会自动前移」。 */
    public int hiddenPatternCount() {
        int count = 0;
        for (int i = PATTERN_VISIBLE; i < patternSlots.getSlots(); i++) {
            if (!patternSlots.getStackInSlot(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 总样板槽里<b>第一个空的可见格</b>下标（{@link #PATTERN_VISIBLE} = 1 → 恒为 0 或 -1）。
     * <p>「生成样板」产出的总样板就落在这里；已有总样板时 {@link #generationPatternCost()} 直接拒绝生成，
     * 因此这里不会覆盖玩家已有的样板。</p>
     */
    public int firstEmptyVisiblePatternSlot() {
        for (int i = 0; i < PATTERN_VISIBLE && i < patternSlots.getSlots(); i++) {
            if (patternSlots.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ========== 单元样板窗口（界面 6 格 ↔ 单元样板库） ==========

    /**
     * 单元样板窗口映射：第 {@code page} 页第 {@code cell} 格 → 单元样板库下标。
     * <p><b>两端共用的唯一定义</b>：菜单槽位（服务端权威映射）与界面（页码 / 越界判定）都调这里，
     * 不允许任何地方另写一份 k*3+i 的算式，否则「客户端显示的格」与「服务端取走的格」会错位。</p>
     */
    public static int unitLibraryIndex(final int page, final int cell) {
        return page * UNIT_WINDOW + cell;
    }

    /** 单元样板库的条目数（最后一个非空槽 +1；空库 = 0）。 */
    public static int unitLibraryEntries(final ItemStackHandler library) {
        return Math.max(0, highestUsedIndex(library) + 1);
    }

    /**
     * 最大页下标：页数 = {@code ceil(条目数 / 窗口宽)}，空库也保留 1 页
     * （页码显示「1/1」、翻页按钮恒为禁用态，界面上没有可翻的页）。
     */
    public static int unitMaxPage(final int entries) {
        final int e = Math.max(0, entries);
        return Math.max(0, (e + UNIT_WINDOW - 1) / UNIT_WINDOW - 1);
    }

    /**
     * 该窗口格是否可交互：<b>只有真的装着样板的格才可交互</b>（映射下标 &lt; 条目数）。
     * <p><b>为什么不用「&lt;= 条目数」</b>：单元样板库始终保留一个末尾空槽用于扩容，若把
     * 「首个空格」也算可交互，空库（0 条目）时界面上就会凭空多出一个可放置位
     * （用户实测问题：「它始终会至少有一个槽是可以用的，这什么玩意？」）。</p>
     * <p>改成严格小于之后：0 条目 = 一格都不可交互；1 条目 = 只有第 1 格；窗口宽（6）条目 = 恰好满格。
     * 玩家从满格窗口里取走某张后，该格会变成「可交互但为空」——放置能力因此仍然保留，
     * 只是不会在空库上凭空出现。</p>
     */
    public static boolean unitCellActive(final int page, final int cell, final int entries) {
        return unitLibraryIndex(page, cell) < Math.max(0, entries);
    }

    public SequencePatternTerminalBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_BLOCK_ENTITY.get(), pos, state,
            new SequencePatternTerminalNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        java.util.Arrays.fill(resultChances, 1.0F);
        java.util.Arrays.fill(scrapChances, 1.0F);
        // 新建时流程编排为空（0 步）：导入配方后才按真实步骤数动态生成行
        // （旧存档的步数由 loadAdditional 读回，不受这里影响）。
        syncArrangementLength(0, false);
    }

    // ========== 流程编排：无上限动态列表 ==========

    /**
     * 流程编排的<b>唯一读写入口</b>（展示数据 / 老存档真实容器二选一）。
     * <p><b>为什么必须有这一份</b>：菜单槽位、机器快照包、次数与机器指派都按「步骤下标」读写单元样板，
     * 若各处自行决定读哪个容器，会出现「界面显示 A 容器、服务端改 B 容器」的错位。
     * 这里集中一处，导入前 = 老存档的真实容器（照常显示 / 掉落），导入后 = 展示数据。</p>
     */
    public GrowingStackHandler arrangementView() {
        return displayArrangementActive ? displayArrangement : arrangement;
    }

    /** 某一步（全局下标）的单元样板（展示数据或老存档容器；越界 = 空栈）。 */
    public ItemStack arrangementUnit(final int stepIndex) {
        final GrowingStackHandler store = arrangementView();
        if (stepIndex < 0 || stepIndex >= arrangementSize || stepIndex >= store.getSlots()) {
            return ItemStack.EMPTY;
        }
        return store.getStackInSlot(stepIndex);
    }

    /** 写入某一步（全局下标）的单元样板（只动当前展示的那一份数据）。 */
    private void setArrangementUnit(final int stepIndex, final ItemStack stack) {
        final GrowingStackHandler store = arrangementView();
        if (stepIndex < 0 || stepIndex >= arrangementSize || stepIndex >= store.getSlots()) {
            return;
        }
        store.setStackInSlot(stepIndex, stack);
    }

    /** 增长步骤数（可为 0，表示空编排）：补足存储与次数表；缩减只"收窄激活行数"，尾部单元保留待恢复。 */
    private void syncArrangementLength(final int size, final boolean notify) {
        final int s = Math.max(0, size);
        this.arrangementSize = s;
        if (s > arrangementView().getSlots()) {
            arrangementView().resize(s);
        }
        while (arrangementCounts.size() < s) {
            arrangementCounts.add(COUNT_MIN);
        }
        // 新语义：机器指派表同步补齐（只增不减，与 arrangementCounts 一致）
        while (stepMachinePos.size() < s) {
            stepMachinePos.add(null);
        }
        while (stepMachineNames.size() < s) {
            stepMachineNames.add("");
        }
        // 每步「跳过重复」开关同步补齐（只增不减）：新步骤初始取世界默认档（见 defaultStepSkipDuplicate）
        while (stepSkipDuplicate.size() < s) {
            stepSkipDuplicate.add(defaultStepSkipDuplicate());
        }
        // 判重结果缓存同步补齐（只增不减；真实值由 refreshStepDuplicateCache 写入）
        while (stepDuplicateCache.size() < s) {
            stepDuplicateCache.add(false);
        }
        if (notify) {
            setChanged();
        }
    }

    /**
     * 整体裁剪重建（导入配方/清空用）：丢弃尾部多余内容并把容量与次数表对齐到 s 步（允许 0 步）。
     * <p>只作用于 {@link #arrangementView()}（当前展示的那一份）：导入时会先切到展示数据，
     * 因此这里的 {@code resize} 永远<b>不会</b>收窄老存档的真实编排容器 —— 那会把玩家物品删掉。</p>
     */
    private void truncateArrangementTo(final int size, final boolean notify) {
        final int s = Math.max(0, size);
        arrangementView().resize(s);
        while (arrangementCounts.size() > s) {
            arrangementCounts.remove(arrangementCounts.size() - 1);
        }
        while (arrangementCounts.size() < s) {
            arrangementCounts.add(COUNT_MIN);
        }
        // 新语义：机器指派表对齐到 s 步
        while (stepMachinePos.size() > s) {
            stepMachinePos.remove(stepMachinePos.size() - 1);
        }
        while (stepMachineNames.size() > s) {
            stepMachineNames.remove(stepMachineNames.size() - 1);
        }
        while (stepMachinePos.size() < s) {
            stepMachinePos.add(null);
        }
        while (stepMachineNames.size() < s) {
            stepMachineNames.add("");
        }
        // 每步「跳过重复」开关与判重缓存对齐到 s 步（与机器指派表同一套「截断 + 补齐」）
        while (stepSkipDuplicate.size() > s) {
            stepSkipDuplicate.remove(stepSkipDuplicate.size() - 1);
        }
        while (stepSkipDuplicate.size() < s) {
            stepSkipDuplicate.add(defaultStepSkipDuplicate());
        }
        while (stepDuplicateCache.size() > s) {
            stepDuplicateCache.remove(stepDuplicateCache.size() - 1);
        }
        while (stepDuplicateCache.size() < s) {
            stepDuplicateCache.add(false);
        }
        this.arrangementSize = s;
        if (notify) {
            setChanged();
        }
    }

    /** 动态增删流程步骤（无固定上限；至少保留 1 步）。 */
    public void adjustArrangementSize(final int delta) {
        setArrangementSize(arrangementSize + delta);
    }

    /** 设置流程步骤数绝对值（无固定上限，供 JEI/配方导入/增删按钮）。 */
    public void setArrangementSize(final int size) {
        syncArrangementLength(size, true);
    }

    /**
     * 删除某一步（全局下标，界面上右键卡片行触发）：后面的步骤整体前移一格，总步数 -1。
     * <p>允许删到 0 步（空编排）：空编排仍可正确渲染/保存。</p>
     */
    public void removeArrangementStep(final int index) {
        if (index < 0 || index >= arrangementSize) {
            return;
        }
        if (arrangementSize <= 1) {
            // 删掉最后一步：直接清空（允许 0 步）
            truncateArrangementTo(0, true);
            return;
        }
        ensureStepMachineCapacity();
        for (int i = index; i < arrangementSize - 1; i++) {
            setArrangementUnit(i, arrangementUnit(i + 1));
            arrangementCounts.set(i, arrangementCounts.get(i + 1));
            stepMachinePos.set(i, stepMachinePos.get(i + 1));
            stepMachineNames.set(i, stepMachineNames.get(i + 1));
            // 每步「跳过重复」开关随该步一起前移（与计数 / 机器指派同一套搬运）
            stepSkipDuplicate.set(i, stepSkipDuplicate.get(i + 1));
        }
        truncateArrangementTo(arrangementSize - 1, true);
    }

    /**
     * 全部物品容器（<b>含幽灵标记容器</b>，供界面 / 存档等非掉落用途）。
     * <p><b>禁止把它当成掉落清单</b>：{@link #ingredientSlot} / {@link #resultSlots} / {@link #scrapSlots}
     * 只是界面显示模板（内容由生成逻辑复制进来，玩家从未投入），破坏方块时必须用
     * {@link #getDroppableHandlers()}。</p>
     */
    public List<ItemStackHandler> getAllHandlers() {
        return List.of(unitLibrary, arrangement, displayArrangement, unitInputSlot, unitOutputSlot,
            assemblyPatternSlot, ingredientSlot, resultSlots, scrapSlots, patternSlots, rsPatternSlots);
    }

    /**
     * 供破坏掉落用：<b>只含真实容器</b>（方块里真正属于玩家的资源）。
     * <p><b>幽灵标记容器绝不入库</b>：{@link #ingredientSlot}（输入原料标记）、{@link #resultSlots}
     * （产物标记）、{@link #scrapSlots}（废料标记）都只是界面显示模板，里面的物品从来不是真实资源；
     * {@link #displayArrangement}（导入展开出来的流程展示数据）同理，因此也<b>不在</b>本清单里。
     * 它们同时实现了 {@link GhostContent}，{@code BlockContentReleaser} 的收集入口会再挡一次，
     * 所以以后谁再往 {@link #getAllHandlers()} 里加东西也不会复现此 bug。</p>
     * <p><b>为什么 {@link #arrangement} 仍在清单里</b>：它是老存档里<b>玩家真正拥有</b>的编排容器
     * （旧版本的导入 / 手工摆放都把真实单元样板写在这里）。界面在展示模式下虽然只渲染
     * {@link #displayArrangement}，但这些老物品必须照常随方块掉落 —— 绝不为了「新语义」把它们丢掉。</p>
     */
    public List<ItemStackHandler> getDroppableHandlers() {
        return List.of(unitLibrary, arrangement, unitInputSlot, unitOutputSlot,
            assemblyPatternSlot, patternSlots, rsPatternSlots);
    }

    /**
     * 幽灵（标记）容器：内容只是「这一步吃什么 / 出什么」的展示模板，永不参与掉落 / 回网结算。
     * <p>继承 {@link ItemStackHandler} 以便与既有读写代码完全兼容（读写、NBT 存取一律照旧），
     * 仅多带一个 {@link GhostContent} 标记供收集入口识别。</p>
     */
    public static final class GhostHandler extends ItemStackHandler implements GhostContent {
        public GhostHandler(final int size) {
            super(size);
        }
    }

    /**
     * 调整流程步骤顺序：把 {@code from} 步整体移到 {@code to} 步（含单元样板、次数、机器指派），
     * 其余步骤顺序平移；越界或原地不动直接忽略。
     * <p>用户需求「卡片顺序可调整」：界面用 Shift+左键（上移）/ Shift+右键（下移）触发，
     * 每次只与相邻一步交换，因此实现上等价于相邻交换。</p>
     *
     * @return 是否真的发生了移动
     */
    public boolean moveArrangementStep(final int from, final int to) {
        if (from == to || from < 0 || to < 0 || from >= arrangementSize || to >= arrangementSize) {
            return false;
        }
        ensureStepMachineCapacity();
        final int step = from < to ? 1 : -1;
        for (int i = from; i != to; i += step) {
            final int next = i + step;
            final ItemStack unit = arrangementUnit(i);
            setArrangementUnit(i, arrangementUnit(next));
            setArrangementUnit(next, unit);
            final int count = arrangementCounts.get(i);
            arrangementCounts.set(i, arrangementCounts.get(next));
            arrangementCounts.set(next, count);
            final BlockPos pos = stepMachinePos.get(i);
            stepMachinePos.set(i, stepMachinePos.get(next));
            stepMachinePos.set(next, pos);
            final String name = stepMachineNames.get(i);
            stepMachineNames.set(i, stepMachineNames.get(next));
            stepMachineNames.set(next, name);
            // 每步「跳过重复」开关随该步一起交换（顺序调整后语义不串）
            final boolean skip = stepSkipDuplicate.get(i);
            stepSkipDuplicate.set(i, stepSkipDuplicate.get(next));
            stepSkipDuplicate.set(next, skip);
        }
        setChanged();
        return true;
    }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    // ========== 流程步骤次数（全局下标 API，菜单按滚动窗口换算） ==========

    private static int clampCount(final int value) {
        return Math.max(COUNT_MIN, Math.min(COUNT_MAX, value));
    }

    /** 读取某一步（全局下标）的重复次数；越界返回 1。 */
    public int getArrangementCountAt(final int index) {
        if (index < 0 || index >= arrangementSize) {
            return COUNT_MIN;
        }
        return arrangementCounts.get(index);
    }

    /** 设置某一步（全局下标）的重复次数绝对值。 */
    public void setArrangementCountAt(final int index, final int value) {
        if (index < 0 || index >= arrangementSize) {
            return;
        }
        arrangementCounts.set(index, clampCount(value));
        setChanged();
    }

    /** 调整某一步（全局下标）的重复次数。 */
    public void adjustArrangementCountAt(final int index, final int delta) {
        if (index < 0 || index >= arrangementSize) {
            return;
        }
        setArrangementCountAt(index, getArrangementCountAt(index) + delta);
    }

    /**
     * 设置整体循环次数绝对值。
     * <p><b>为什么不夹 64</b>：循环次数是<b>配方给出的固定值</b>（Create
     * {@code SequencedAssemblyRecipe#getLoops()}，真实配方通常 1~5），玩家既不能编辑、也无需被限制 ——
     * 旧实现的 {@code Math.min(64, ...)} 会把「循环 100 次」这类配方改成 64（用户原话：
     * 「你怎么知道它最多只有 64 次？这显然没有道理」）。现在只保证下界 1（0 / 负数没有意义）。</p>
     */
    public void setLoops(final int value) {
        loops = Math.max(1, value);
        setChanged();
    }

    /** 设置某产物/废料的概率（clamp 0..1，按 % 输入）。 */
    public void setChance(final boolean scrap, final int index, final int percent) {
        if (index < 0 || index >= (scrap ? SCRAP_SIZE : RESULT_SIZE)) {
            return;
        }
        final float v = Math.max(0, Math.min(100, percent)) / 100F;
        if (scrap) {
            scrapChances[index] = v;
        } else {
            resultChances[index] = v;
        }
        setChanged();
    }

    /**
     * 一次性设置某产物 / 废料的<b>概率与产出数量</b>（子窗口确认时调用）。
     * <p>数量写进 ghost 槽的 {@link ItemStack#getCount()}，因此“1 份配方出几个”与“出几个的概率是多少”
     * 两个信息都被保存下来；数量为 0 或负数时按 1 处理（槽内绝不出现 0 数量物品）。</p>
     */
    public void setResultConfig(final boolean scrap, final int index, final int percent, final int amount) {
        final int size = scrap ? SCRAP_SIZE : RESULT_SIZE;
        if (index < 0 || index >= size) {
            return;
        }
        setChance(scrap, index, percent);
        final ItemStackHandler handler = scrap ? scrapSlots : resultSlots;
        final ItemStack current = handler.getStackInSlot(index);
        if (!current.isEmpty()) {
            handler.setStackInSlot(index, current.copyWithCount(Math.max(1, Math.min(64, amount))));
        }
        setChanged();
    }

    /**
     * 清空某产物 / 废料格（界面 Shift+左键触发）：只清掉该格自己的引用与概率，
     * 不触碰其它格、也不销毁任何真实物品（这些格只是「标记」，本就没有实物）。
     */
    public void clearResultConfig(final boolean scrap, final int index) {
        final int size = scrap ? SCRAP_SIZE : RESULT_SIZE;
        if (index < 0 || index >= size) {
            return;
        }
        final ItemStackHandler handler = scrap ? scrapSlots : resultSlots;
        handler.setStackInSlot(index, ItemStack.EMPTY);
        if (scrap) {
            scrapChances[index] = 1.0F;
        } else {
            resultChances[index] = 1.0F;
        }
        setChanged();
    }

    /**
     * 容器中最后一个非空槽的下标（全空返回 -1）。
     * 供产物/废料滚动条计算“最大滚动偏移”：只有条目多到超出一页时才允许滚动。
     */
    public static int highestUsedIndex(final ItemStackHandler handler) {
        for (int i = handler.getSlots() - 1; i >= 0; i--) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ========== 自动合成仓名称（单元样板制作） ==========

    /** 网络中所有自动合成仓的名称（按位置排序）；未接入网络或无线缆连接时为空。 */
    public List<String> getAutocrafterNames() {
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetwork();
        if (network == null) {
            return List.of();
        }
        return network.getComponent(GraphNetworkComponent.class)
            .getContainers(Autocrafter.class)
            .stream()
            .sorted(java.util.Comparator.comparing(Autocrafter::getLocalPosition))
            .map(a -> a.getAutocrafterName().getString())
            .toList();
    }

    /**
     * 网络中所有“序列执行仓”方块的名称（按世界坐标排序）。
     * 通过 {@link GraphNetworkComponent#getContainers()} 遍历网络节点容器，再按节点类型
     * 过滤出 {@link SequenceExecutionChamberNetworkNode}，取其方块实体的显示名；
     * 重名仓用 “名 (2)/(3)…” 后缀消歧，保证轮选/绑定以名称作为键时不会歧义。
     * 未接入网络或无线缆连接时返回空列表。
     */
    public List<String> getExecutionChamberNames() {
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetwork();
        if (network == null) {
            return List.of();
        }
        final List<SequenceExecutionChamberBlockEntity> chambers = new ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer container
            : network.getComponent(GraphNetworkComponent.class).getContainers()) {
            if (container.getNode() instanceof final SequenceExecutionChamberNetworkNode chamberNode) {
                final SequenceExecutionChamberBlockEntity chamber = chamberNode.getBlockEntity();
                if (chamber != null) {
                    chambers.add(chamber);
                }
            }
        }
        chambers.sort(java.util.Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        final List<String> names = new ArrayList<>(chambers.size());
        final java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
            final String raw = chamber.getName().getString();
            final int nth = seen.merge(raw, 1, Integer::sum);
            names.add(nth == 1 ? raw : raw + " (" + nth + ")");
        }
        return names;
    }

    // ========== 新语义（v4）：执行仓列表 / 机器指派 ==========

    /**
     * 新语义：网络内某台执行仓的只读视图。
     * {@code name} 为执行仓显示名（自定义名非空时用之，否则回退坐标字符串）。
     */
    public record ChamberInfo(net.minecraft.core.BlockPos pos, String name, String recipeType) {
    }

    /**
     * 新语义：列出网络内全部序列执行仓（按世界坐标排序、按坐标去重）。
     * <p><b>只列出「已设定类型 + 名字」的执行仓</b>（{@code chamber.isBound()}）：与执行仓自身
     * 「未绑定时不接收单元样板、也不参与引擎」的判定同源，避免 SPT 认为有机器、执行仓却拒收样板。
     * 未接入网络或无线缆连接时返回空列表。</p>
     */
    public List<ChamberInfo> listChambers() {
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetwork();
        if (network == null) {
            return List.of();
        }
        final List<SequenceExecutionChamberBlockEntity> chambers = new ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer container
            : network.getComponent(GraphNetworkComponent.class).getContainers()) {
            if (container.getNode() instanceof final SequenceExecutionChamberNetworkNode chamberNode) {
                final SequenceExecutionChamberBlockEntity chamber = chamberNode.getBlockEntity();
                if (chamber != null && chamber.isBound()) {
                    chambers.add(chamber);
                }
            }
        }
        chambers.sort(java.util.Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        final List<ChamberInfo> result = new ArrayList<>(chambers.size());
        long lastKey = Long.MIN_VALUE;
        for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
            final long key = chamber.getBlockPos().asLong();
            if (key == lastKey) {
                continue; // 同一坐标只列一次
            }
            lastKey = key;
            result.add(new ChamberInfo(chamber.getBlockPos(), chamber.getChamberDisplayName(),
                chamber.getRecipeType() == null ? "" : chamber.getRecipeType()));
        }
        return result;
    }

    /** 新语义：只列出配方类型匹配的执行仓（recipeType 为空则返回空列表）。 */
    public List<ChamberInfo> listChambersFor(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return List.of();
        }
        return listChambers().stream()
            .filter(c -> recipeType.equals(c.recipeType()))
            .toList();
    }

    // ========== 新语义（v5）：执行仓单元样板汇总 ==========

    /**
     * 新语义：一台执行仓的「单元样板汇总」只读视图。
     * {@code units} 为该仓单元样板槽内的实际物品（按槽位顺序），{@code name}/{@code recipeType}
     * 是本仓绑定的显示名与配方类型（前端据此显示「哪台机器、配方 id、机器图标」）。
     */
    public record ChamberUnits(net.minecraft.core.BlockPos pos, String name, String recipeType,
                              List<ItemStack> units) {
    }

    /**
     * 单元样板库的<b>分区视图</b>：一台执行仓对应一区，区内是该库中「配方类型与该仓一致」的样板下标；
     * 未被任何执行仓认领的样板统一归入末尾的「未匹配」区（{@code pos == null}）。
     * <p>用户需求：单元样板库像 RS 自动合成管理器那样「一篮一篮」地按执行仓分段展示，
     * 每篮标注执行仓名字与配方类型，并可直接把该篮的样板存进对应的执行仓。</p>
     */
    public record LibrarySection(@org.jetbrains.annotations.Nullable BlockPos pos, String name,
                                 String recipeType, List<Integer> libraryIndices) {
    }

    /**
     * 构建单元样板库分区（服务端权威）：先让每台执行仓按配方类型认领库内样板，剩余归入「未匹配」区。
     * <p>同一配方类型的多台执行仓时，样板归<b>第一台</b>（够用即可，第二台再放会由引擎按类型继续匹配）。
     * 库为空时只返回各执行仓的空区；没有任何执行仓且库为空时返回空列表。</p>
     */
    public List<LibrarySection> listLibrarySections() {
        final HolderLookup.Provider regs = registries();
        final List<Integer> entryIndices = new ArrayList<>();
        final List<String> entryTypes = new ArrayList<>();
        for (int i = 0; i < unitLibrary.getSlots(); i++) {
            final ItemStack stack = unitLibrary.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            entryIndices.add(i);
            entryTypes.add(unitRecipeType(stack, regs));
        }
        final boolean[] claimed = new boolean[entryIndices.size()];
        final List<LibrarySection> sections = new ArrayList<>();
        for (final ChamberInfo chamber : listChambers()) {
            final String type = chamber.recipeType() == null ? "" : chamber.recipeType();
            final List<Integer> indices = new ArrayList<>();
            for (int k = 0; k < entryIndices.size(); k++) {
                if (!claimed[k] && !type.isEmpty() && type.equals(entryTypes.get(k))) {
                    claimed[k] = true;
                    indices.add(entryIndices.get(k));
                }
            }
            sections.add(new LibrarySection(chamber.pos(), chamber.name(), type, indices));
        }
        final List<Integer> rest = new ArrayList<>();
        for (int k = 0; k < entryIndices.size(); k++) {
            if (!claimed[k]) {
                rest.add(entryIndices.get(k));
            }
        }
        if (!rest.isEmpty()) {
            sections.add(new LibrarySection(null, "", "", rest));
        }
        return sections;
    }

    /** 单元样板物品上的配方类型（新语义三项之一；旧样板取机器名兜底）。 */
    private static String unitRecipeType(final ItemStack stack, final HolderLookup.Provider regs) {
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, regs);
        if (unit == null) {
            return "";
        }
        return unit.recipeType() == null ? "" : unit.recipeType();
    }

    /**
     * 汇总当前网络内全部执行仓的<b>单元样板</b>（未接入网络时返回空列表）。
     *
     * <p><b>只收单元样板</b>（按物品类型，见 {@code SequenceUnitPatternItem#isUnitPattern}）：
     * 序列装配总样板不属于任何「单元样板」界面（它归 RS 原版自动合成管理舱 / 本模组的样板库）。
     * 旧存档的执行舱单元槽里可能真的躺着总样板（读档不做类型校验），这里与
     * {@code ChamberUnitsSummaryMenu#rebuildWindow} 用<b>同一个判定</b>把它挡在行内计数与窗口之外，
     * 否则会出现「行标 3 张、窗口却摆着第 4 件」的不同口径。</p>
     */
    public List<ChamberUnits> listChamberUnits() {
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetwork();
        if (network == null) {
            return List.of();
        }
        final List<SequenceExecutionChamberBlockEntity> chambers = chambersInNetwork(network);
        final List<ChamberUnits> result = new ArrayList<>(chambers.size());
        for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
            final List<ItemStack> units = new ArrayList<>();
            for (int i = 0; i < chamber.unitSlots.getContainerSize(); i++) {
                final ItemStack stack = chamber.unitSlots.getItem(i);
                if (SequenceUnitPatternItem.isUnitPattern(stack)) {
                    units.add(stack.copy());
                }
            }
            result.add(new ChamberUnits(chamber.getBlockPos(), chamber.getChamberDisplayName(),
                chamber.getRecipeType() == null ? "" : chamber.getRecipeType(), units));
        }
        return result;
    }

    /**
     * 网络内的全部执行仓<b>方块实体引用</b>（按坐标排序、去重；未接入网络时为空列表）。
     * <p>「执行仓单元样板汇总」菜单需要直接读写各仓的单元样板槽，因此必须拿到引用而不是只读视图；
     * 顺序与 {@link #listChamberUnits()} 完全一致（同一个 {@link #chambersInNetwork}），
     * 保证「行标题（S2C 元数据）」与「行内真槽位（原版同步）」指向同一台执行仓。</p>
     */
    public List<SequenceExecutionChamberBlockEntity> networkChambers() {
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetwork();
        return network == null ? List.of() : chambersInNetwork(network);
    }

    /** 网络内的全部执行仓（按坐标排序、去重）。 */
    private List<SequenceExecutionChamberBlockEntity> chambersInNetwork(
        final com.refinedmods.refinedstorage.api.network.Network network) {
        final List<SequenceExecutionChamberBlockEntity> chambers = new ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer container
            : network.getComponent(GraphNetworkComponent.class).getContainers()) {
            if (container.getNode() instanceof final SequenceExecutionChamberNetworkNode node
                && node.getBlockEntity() != null) {
                chambers.add(node.getBlockEntity());
            }
        }
        chambers.sort(java.util.Comparator.comparingLong(be -> be.getBlockPos().asLong()));
        final List<SequenceExecutionChamberBlockEntity> unique = new ArrayList<>(chambers.size());
        long last = Long.MIN_VALUE;
        for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
            final long key = chamber.getBlockPos().asLong();
            if (key == last) {
                continue;
            }
            last = key;
            unique.add(chamber);
        }
        return unique;
    }

    /** 按坐标定位网络内的某台执行仓（不存在返回 null）。 */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity chamberAt(final net.minecraft.core.BlockPos pos) {
        if (pos == null) {
            return null;
        }
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetwork();
        if (network == null) {
            return null;
        }
        for (final SequenceExecutionChamberBlockEntity chamber : chambersInNetwork(network)) {
            if (chamber.getBlockPos().equals(pos)) {
                return chamber;
            }
        }
        return null;
    }

    /**
     * 汇总操作①「取回」：把某台执行仓里的单元样板全部搬回本终端的单元样板库
     * （库可自动扩容，装不下时留在原处，绝不销毁）。
     *
     * @return 实际取回的样板张数
     */
    public int pullUnitsFrom(final net.minecraft.core.BlockPos pos) {
        final SequenceExecutionChamberBlockEntity chamber = chamberAt(pos);
        if (chamber == null) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < chamber.unitSlots.getContainerSize(); i++) {
            final ItemStack stack = chamber.unitSlots.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            unitLibrary.setStackInSlot(unitLibrary.firstEmptySlot(), stack.copy());
            chamber.unitSlots.setItem(i, ItemStack.EMPTY);
            moved++;
        }
        if (moved > 0) {
            chamber.setChanged();
            setChanged();
        }
        return moved;
    }

    /**
     * 汇总操作②「存入」：把本终端单元样板库里<b>配方类型匹配</b>的单元样板搬进某台执行仓
     * （类型不匹配的一律不动；仓满则留在库里，绝不销毁）。
     *
     * @return 实际存入的样板张数
     */
    public int pushUnitsTo(final net.minecraft.core.BlockPos pos) {
        final SequenceExecutionChamberBlockEntity chamber = chamberAt(pos);
        if (chamber == null) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < unitLibrary.getSlots(); i++) {
            final ItemStack stack = unitLibrary.getStackInSlot(i);
            if (stack.isEmpty() || !chamber.acceptsUnit(stack)) {
                continue;
            }
            final int empty = rscc$firstEmptyUnitSlot(chamber);
            if (empty < 0) {
                break; // 仓满：剩下的留在库里
            }
            chamber.unitSlots.setItem(empty, stack.copyWithCount(1));
            unitLibrary.setStackInSlot(i, ItemStack.EMPTY);
            moved++;
        }
        if (moved > 0) {
            chamber.setChanged();
            setChanged();
        }
        return moved;
    }

    /** 该执行仓单元样板槽的第一个空格（全满返回 -1）。 */
    private static int rscc$firstEmptyUnitSlot(final SequenceExecutionChamberBlockEntity chamber) {
        for (int i = 0; i < chamber.unitSlots.getContainerSize(); i++) {
            if (chamber.unitSlots.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 新语义：该配方类型下的第一台执行仓（无则 null），供导出时自动指派。 */
    @org.jetbrains.annotations.Nullable
    public ChamberInfo resolveDefaultChamber(final String recipeType) {
        final List<ChamberInfo> matches = listChambersFor(recipeType);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** 确保机器指派表长度至少为 arrangementSize。 */
    private void ensureStepMachineCapacity() {
        while (stepMachinePos.size() < arrangementSize) {
            stepMachinePos.add(null);
        }
        while (stepMachineNames.size() < arrangementSize) {
            stepMachineNames.add("");
        }
        // 每步「跳过重复」开关与机器指派表同一套「只增不减」补齐（避免搬运时代码越界）
        while (stepSkipDuplicate.size() < arrangementSize) {
            stepSkipDuplicate.add(defaultStepSkipDuplicate());
        }
    }

    /** 新语义：把某一步（全局下标）指派到某台机器（pos 为空 = 解绑）。 */
    public void setStepMachine(final int stepIndex, @org.jetbrains.annotations.Nullable final BlockPos pos,
                               final String name) {
        if (stepIndex < 0 || stepIndex >= arrangementSize) {
            return;
        }
        ensureStepMachineCapacity();
        stepMachinePos.set(stepIndex, pos);
        stepMachineNames.set(stepIndex, name == null ? "" : name);
        setChanged();
    }

    /** 新语义：解除某一步（全局下标）的机器指派。 */
    public void clearStepMachine(final int stepIndex) {
        setStepMachine(stepIndex, null, "");
    }

    /** 新语义：某一步（全局下标）指派的机器坐标（未指派返回 null）。 */
    @org.jetbrains.annotations.Nullable
    public BlockPos getStepMachinePos(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= stepMachinePos.size()) {
            return null;
        }
        return stepMachinePos.get(stepIndex);
    }

    /** 新语义：某一步（全局下标）指派的机器名（未指派返回 ""）。 */
    public String getStepMachineName(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= stepMachineNames.size()) {
            return "";
        }
        final String name = stepMachineNames.get(stepIndex);
        return name == null ? "" : name;
    }

    // ========== 每一步「生成时跳过重复单元样板」的开关与判重缓存 ==========

    /**
     * 新步骤的默认开关值：<b>固定「开」</b>（= 与既有默认一致；用户要求删除管理舱的全局开关后不再随它变化）。
     * <p><b>为什么固定</b>：管理舱里的全局开关与「每一步一个跳过重复」重复，已按用户要求删除，
     * 因此新步骤不再从世界存档读取默认档，避免留下一个没有界面入口、却仍能悄悄改变新步骤行为的隐式档位。
     * 每步的开关仍由玩家在终端逐步骤切换，并<b>按 NBT 原样持久化</b>（旧存档已存的每步值一律保留）。</p>
     */
    private boolean defaultStepSkipDuplicate() {
        return true;
    }

    /** 该步生成时是否跳过重复（越界 / 缺值 ⇒ 默认档）。 */
    public boolean getStepSkipDuplicate(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= stepSkipDuplicate.size()) {
            return defaultStepSkipDuplicate();
        }
        final Boolean value = stepSkipDuplicate.get(stepIndex);
        return value == null || value;
    }

    /** 设置该步生成时是否跳过重复（服务端权威入口，由 C2S 包调用）。越界忽略。 */
    public void setStepSkipDuplicate(final int stepIndex, final boolean skip) {
        if (stepIndex < 0 || stepIndex >= arrangementSize) {
            return;
        }
        while (stepSkipDuplicate.size() < arrangementSize) {
            stepSkipDuplicate.add(defaultStepSkipDuplicate());
        }
        stepSkipDuplicate.set(stepIndex, skip);
        setChanged();
    }

    /** 该步当前是否已存在语义完全相同的单元样板（读 {@link #stepDuplicateCache}，不触发扫描）。 */
    public boolean stepDuplicateExists(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= stepDuplicateCache.size()) {
            return false;
        }
        return Boolean.TRUE.equals(stepDuplicateCache.get(stepIndex));
    }

    /**
     * 重新扫描并缓存「每一步当前是否已存在语义完全相同的单元样板」（服务端权威）。
     * <p>判据<b>完全复用</b> {@link UnitPatternDedupe#findDuplicate}（扫描网络内全部执行仓的单元槽 +
     * 全部终端的单元样板库），因此与「单元样板管理舱」显示的口径一致：界面上看不到重复 ⇒ 这里也判不出重复。
     * 只读扫描，不写任何容器。</p>
     * <p>调用时机：步骤机器快照下发前（客户端据此显示「已有 / 没有」）、以及生成样板前（保证判定权威）。</p>
     * <p><b>本轮新增：命中来源一并缓存</b>（审计 §7.3 的取证缺口）。旧实现把
     * {@link UnitPatternDedupe#findDuplicate} 已经算出的「来源 + 槽位」当场丢掉，只留一个布尔值，
     * 于是实机那三次 {@code all 2 unit step(s) skipped as duplicates} 事后<b>不可复现</b>。
     * 现在判定结果里带上来源与槽位（见 {@link #stepDuplicateMatch}），由生成路径落
     * {@code [rscc-dedupe]} 日志（判据摘要见 {@link UnitPatternDedupe#basisOf}）。</p>
     */
    public void refreshStepDuplicateCache() {
        while (stepDuplicateCache.size() < arrangementSize) {
            stepDuplicateCache.add(false);
        }
        while (stepDuplicateCache.size() > arrangementSize) {
            stepDuplicateCache.remove(stepDuplicateCache.size() - 1);
        }
        while (stepDuplicateMatch.size() < arrangementSize) {
            stepDuplicateMatch.add(null);
        }
        while (stepDuplicateMatch.size() > arrangementSize) {
            stepDuplicateMatch.remove(stepDuplicateMatch.size() - 1);
        }
        final HolderLookup.Provider regs = registries();
        final Network network = UnitPatternDedupe.networkOf(this);
        for (int i = 0; i < arrangementSize; i++) {
            final ItemStack unit = arrangementUnit(i);
            final UnitPatternDedupe.Match found =
                unit.isEmpty() ? null : UnitPatternDedupe.findDuplicate(network, unit, regs);
            stepDuplicateCache.set(i, found != null);
            stepDuplicateMatch.set(i, found);
        }
    }

    /** 该步上一次扫描命中的既有样板（{@code null} = 没有重复 / 还没扫过）；只读缓存，不触发扫描。 */
    @org.jetbrains.annotations.Nullable
    public UnitPatternDedupe.Match stepDuplicateMatch(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= stepDuplicateMatch.size()) {
            return null;
        }
        return stepDuplicateMatch.get(stepIndex);
    }

    // ---------- 「该步的机器 / 样板是否已就位」（界面右侧「已有 / 没有」的唯一判据） ----------

    /**
     * 该步的单元样板是否<b>已经在执行舱上备好</b> —— 流程编排那一行右侧「已有 / 没有」的唯一判据
     * （服务端权威；客户端只读 {@code SyncStepMachinesPacket} 里的这一位）。
     *
     * <h2>为什么不拿 {@link #stepDuplicateExists} 当这个指示（用户实测的缺陷）</h2>
     * <p>{@code stepDuplicateExists} 回答的是「<b>生成时会跳过重复吗</b>」，判据是
     * {@link UnitPatternDedupe#sameSemantics}：<b>同一条配方 + 同一个步序</b>且语义完全相同。
     * 拿它当「这一步的机器 / 样板有没有」显示给玩家就会自相矛盾 —— 关服存档
     * （{@code run/saves/test}，终端 @-5,-60,11）里就是这样：</p>
     * <ul>
     *     <li>流程编排是<b>列车轨道</b>（{@code create:sequenced_assembly/track}）第 1 行机械手、第 3 行冲压，
     *     该行单元样板记 {@code Recipe=create:sequenced_assembly/track, Step=2}；</li>
     *     <li>网络里那台动力冲压机（@-16,-60,10，配方类型 {@code create:pressing}）槽里放的是
     *     <b>坚固板</b>第 2 步的冲压样板（{@code Recipe=create:sequenced_assembly/sturdy_sheet, Step=1}）；</li>
     *     <li>操作类型一样（都是 {@code create:pressing}）、配方与步序都不同 ⇒ 判重返回 false
     *     ⇒ 界面把「冲压机上明明放着冲压样板」显示成「没有」。用户原话：
     *     「这一个冲压明明是有的，但是你却显示为没有，是什么意思？」</li>
     * </ul>
     * <p><b>为什么不能直接把判重放宽成「操作类型相同」</b>：那条口径正是第 21 轮修掉的严重回归
     * —— 两条配方的冲压步会被判成同一张，整条排线的单元样板被全部跳过 ⇒ 该步没有任何仓认领 ⇒
     * 下单毫无反应。因此判重（生成侧）一个字不动，本方法只回答<b>显示</b>的那个问题。</p>
     *
     * <h2>本方法回答的问题（= 玩家看那一行时问的问题）</h2>
     * <p>「这一步的机器 / 样板已经就位了吗」= 本网络里是否<b>已经有一条链</b>（链 = 一个逻辑执行仓）：</p>
     * <ol>
     *     <li>它的配方类型<b>就是</b>该步的操作类型 —— 这台机器<b>能</b>承担这一步
     *     （{@link SequenceExecutionChamberBlockEntity#getRecipeType()}，链级唯一值）；</li>
     *     <li>并且它的单元样板槽里<b>确实有一张</b>该操作类型的单元样板
     *     （{@link UnitPatternDedupe#operationOf}）；
     *     <b>链级</b>：链上任一台放着就算整条链（这一个逻辑执行仓）已备好
     *     （{@link SequenceExecutionChamberBlockEntity#chainMembers()}，用户要求「四台 = 一台」）。</li>
     * </ol>
     *
     * <h2>复用既有唯一事实源（绝不新造第二套）</h2>
     * <ul>
     *     <li>扫描范围 = {@link UnitManagerSources#chambers}(与单元样板查重、管理舱同一个网络视图)；</li>
     *     <li>链身份 = {@link SequenceExecutionChamberBlockEntity#chainIdentity()}（链首坐标，全工程唯一的链口径）；</li>
     *     <li>链成员 = {@link SequenceExecutionChamberBlockEntity#chainMembers()}；</li>
     *     <li>「操作类型」= {@link UnitPatternDedupe#operationOf}（{@code RecipeType} 优先、回退 {@code Machine}
     *     的唯一归一化，与判重、管理舱完全同源）。</li>
     * </ul>
     *
     * <p><b>跨链不串味</b>：配方类型与这一步不同的链（例：注液仓的链）永远过不了第 ① 条，
     * 因此它的样板绝不会把「机械手 / 冲压」这类步显示成已具备。</p>
     * <p><b>只读</b>：不写任何容器、不缓存、不参与生成 / 归属 / 备料 / 推料 / 收回的任何闸门
     * （生成侧仍走 {@link #stepDuplicateExists} + {@link #getStepSkipDuplicate}，一个字不动）。</p>
     */
    public boolean stepUnitEquipped(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= arrangementSize || level == null) {
            return false; // 空行 / 越界 / 不在世界里：保守判「没有」（链推导需要世界，不猜）
        }
        final HolderLookup.Provider regs = registries();
        final String operation = UnitPatternDedupe.operationOf(arrangementUnit(stepIndex), regs);
        if (operation.isEmpty()) {
            return false; // 该步读不出操作类型（空行 / 老样板没记类型）：什么都不具备
        }
        final Network network = UnitPatternDedupe.networkOf(this);
        if (network == null) {
            return false;
        }
        // 一条链 = 一个逻辑执行仓：同链多台只算一次（链身份复用既有 chainIdentity，不新造链口径）
        final java.util.Set<Long> seenChains = new java.util.HashSet<>();
        for (final SequenceExecutionChamberBlockEntity chamber : UnitManagerSources.chambers(network)) {
            if (!seenChains.add(chamber.chainIdentity().asLong())) {
                continue;
            }
            final String chainType = chamber.getRecipeType();
            if (chainType == null || !operation.equalsIgnoreCase(chainType.trim())) {
                continue; // 这台机器不认这一步的操作类型（跨链不串味）
            }
            for (final SequenceExecutionChamberBlockEntity member : chamber.chainMembers()) {
                for (int slot = 0; slot < member.unitSlots.getContainerSize(); slot++) {
                    if (operation.equals(
                        UnitPatternDedupe.operationOf(member.unitSlots.getItem(slot), regs))) {
                        return true; // 链上任一台备好 ⇒ 整条链已备好
                    }
                }
            }
        }
        return false;
    }

    // ---------- 流程编排行「输入原料 / 输入流体」标记 ----------

    /**
     * 某一步（全局下标）标记的输入流体（未标记返回 {@link net.neoforged.neoforge.fluids.FluidStack#EMPTY}）。
     * <p>流体标记存在该步单元样板的 CustomData 里（见 {@code SequencePatternData} 的 InputFluid），
     * 因此它会随步骤一起上移 / 下移 / 删除，不需要额外维护平行的步骤表。</p>
     */
    public net.neoforged.neoforge.fluids.FluidStack getStepFluidMarker(final int stepIndex) {
        if (stepIndex < 0 || stepIndex >= arrangementSize) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        final ItemStack unit = arrangementUnit(stepIndex);
        if (unit.isEmpty()) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        return SequencePatternData.readUnitFluid(unit, registries());
    }

    /**
     * 写入 / 清除某一步（全局下标）的输入流体标记：直接改写该步单元样板的 CustomData。
     * <p>服务端权威路径（C2S 包）调用；传入 {@link net.neoforged.neoforge.fluids.FluidStack#EMPTY} 表示清除。
     * 只动标记数据，不消耗任何玩家资源。</p>
     */
    public void setStepFluidMarker(final int stepIndex,
                                   final net.neoforged.neoforge.fluids.FluidStack fluid) {
        if (stepIndex < 0 || stepIndex >= arrangementSize) {
            return;
        }
        final ItemStack unit = arrangementUnit(stepIndex);
        if (unit.isEmpty() || !unit.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())) {
            return; // 该步没有单元样板：无处记录标记
        }
        // 清除标记永远允许；写入前先按该步配方数据确认「这一步确实消耗流体输入」，
        // 避免给动力冲压机一类没有流体输入的步骤误标（服务端权威拦截）。
        if (fluid != null && !fluid.isEmpty() && !stepAllowsFluidInput(level, unit)) {
            return;
        }
        final ItemStack copy = unit.copy();
        SequencePatternData.writeUnitFluid(copy, fluid, registries());
        setArrangementUnit(stepIndex, copy);
        setChanged();
    }

    /**
     * 取该步配方的第一个输入流体（无则空栈）。数据来源 = Create {@code ProcessingRecipe#getFluidIngredients()}
     * （经 {@link cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe#stepInputFluids}）。
     */
    private static net.neoforged.neoforge.fluids.FluidStack firstFluid(
        @org.jetbrains.annotations.Nullable final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe) {
        return cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.firstFluid(recipe);
    }

    /** 当前注册表访问（方块实体不在世界中时返回 EMPTY）。 */
    private HolderLookup.Provider registries() {
        return level != null ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
    }

    // ---------- 该步「是否存在某类输入」的配方数据判据（界面槽位禁用 / 服务端拦截共用） ----------

    /**
     * 该步对应的 Create 处理配方（按单元样板记录的 {@code recipe} + {@code step} 解析）。
     * <p>样板没有配方 id（手工创建 / 旧样板）或配方不是序列装配配方时返回 {@code null}，
     * 调用方据此回退到「样板自带的输入标记」。绝不抛异常。</p>
     */
    @org.jetbrains.annotations.Nullable
    public static com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> stepRecipe(
        @org.jetbrains.annotations.Nullable final net.minecraft.world.level.Level level,
        @org.jetbrains.annotations.Nullable final SequencePatternData.UnitData unit) {
        if (level == null || unit == null) {
            return null;
        }
        final net.minecraft.resources.ResourceLocation recipeId =
            net.minecraft.resources.ResourceLocation.tryParse(unit.recipe() == null ? "" : unit.recipe());
        if (recipeId == null) {
            return null;
        }
        try {
            final java.util.Optional<net.minecraft.world.item.crafting.RecipeHolder<?>> holder =
                level.getRecipeManager().byKey(recipeId);
            if (!(holder.isPresent()
                && holder.get().value() instanceof com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe recipe)) {
                return null;
            }
            final List<com.simibubi.create.content.processing.sequenced.SequencedRecipe<?>> sequence =
                recipe.getSequence();
            if (sequence.isEmpty()) {
                return null;
            }
            final int step = Math.floorMod(unit.step(), sequence.size());
            return sequence.get(step).getRecipe();
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 该步是否<b>真的</b>会消耗物品输入（判据来自配方数据，不硬编码配方名）。
     * <p>例：动力冲压机（{@code create:pressing}）除过渡件外没有额外 ingredient →
     * {@link SequencedRecipeProbe#stepInputItems} 为空 → 返回 false（对应槽位应禁用）；
     * 机械手装配（{@code create:deploying}）等有应用物 → 返回 true（自动启用）。</p>
     * <p>配方无法解析时回退样板自带的输入标记（{@code requiresInput} / {@code input}）。</p>
     */
    public static boolean stepAllowsItemInput(@org.jetbrains.annotations.Nullable final net.minecraft.world.level.Level level,
                                              final ItemStack unitStack) {
        if (unitStack.isEmpty()) {
            return false;
        }
        final HolderLookup.Provider regs = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(unitStack, regs);
        if (unit == null) {
            return false;
        }
        final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe =
            stepRecipe(level, unit);
        if (recipe != null) {
            return !cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                .stepInputItems(recipe).isEmpty();
        }
        return unit.requiresInput() || (unit.input() != null && !unit.input().isEmpty());
    }

    /**
     * 该步是否<b>真的</b>会消耗流体输入（判据来自配方数据；灌注 / 注液一类步骤为 true）。
     * <p>配方无法解析时回退样板自带的流体标记（已标注过流体 = 允许继续标注）。</p>
     */
    public static boolean stepAllowsFluidInput(@org.jetbrains.annotations.Nullable final net.minecraft.world.level.Level level,
                                               final ItemStack unitStack) {
        if (unitStack.isEmpty()) {
            return false;
        }
        final HolderLookup.Provider regs = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(unitStack, regs);
        if (unit == null) {
            return false;
        }
        final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe =
            stepRecipe(level, unit);
        if (recipe != null) {
            return !cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                .stepInputFluids(recipe).isEmpty();
        }
        return !SequencePatternData.readUnitFluid(unitStack, regs).isEmpty();
    }

    /** 新语义：最近一次导出装配样板时的问题（null = 无问题）；供前端读取展示。 */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.network.chat.Component getLastExportError() {
        return lastExportError;
    }

    /** 当前选中的自动合成仓名称（无仓时为"空"）。 */
    public String getSelectedAutocrafterName() {
        final List<String> names = getAutocrafterNames();
        if (names.isEmpty()) {
            return "";
        }
        return names.get(Math.max(0, Math.min(autocrafterIndex, names.size() - 1)));
    }

    /** 滚轮切换选中的自动合成仓。 */
    public void cycleAutocrafter(final int delta) {
        final int size = getAutocrafterNames().size();
        if (size <= 1) {
            autocrafterIndex = 0;
            setChanged();
            return;
        }
        autocrafterIndex = (autocrafterIndex + delta + size) % size;
        setChanged();
    }

    /** 为某一步（全局下标）绑定执行它的自动合成仓（写回该步的单元样板绑定；crafterName 为空 = 解绑）。 */
    public boolean bindStepCrafter(final int index, final String crafterName) {
        if (index < 0 || index >= arrangementSize) {
            return false;
        }
        if (crafterName == null || crafterName.isEmpty()) {
            return unbindStepCrafter(index);
        }
        if (!isKnownCrafterName(crafterName)) {
            return false; // 只允许绑定网络中真实存在的仓（自动合成仓或序列执行仓）
        }
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final ItemStack stack = arrangementUnit(index);
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, registries);
        if (unit == null) {
            return false;
        }
        final ItemStack updated = stack.copy();
        // ⚠ 2026-10-05 修复：这里原来走 5 参构造器 ⇒ 只保留 machine/input/crafter/recipe/step，
        // 把 requiresInput / displayName / recipeType / **inputCandidates** 全部丢成默认值。
        // 后果与用户实测完全一致：「铁粒还是只显示个铁粒」—— 只要这一步被绑定 / 解绑过一次执行仓，
        // 落盘的候选组就被抹掉；而界面回落顺序里「样板落盘候选」那一级因此永远读不到东西。
        // 现在统一<b>整条透传</b>：改哪个字段就只改那个字段，别的一字不动。
        SequencePatternData.writeUnit(updated,
            new SequencePatternData.UnitData(unit.machine(), unit.input(), crafterName,
                unit.recipe(), unit.step(), unit.requiresInput(), unit.displayName(),
                unit.recipeType(), unit.inputCandidates()), registries);
        setArrangementUnit(index, updated);
        setChanged();
        return true;
    }

    /** 解除某步（全局下标）的仓绑定。 */
    public boolean unbindStepCrafter(final int index) {
        if (index < 0 || index >= arrangementSize) {
            return false;
        }
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final ItemStack stack = arrangementUnit(index);
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, registries);
        if (unit == null) {
            return false;
        }
        final ItemStack updated = stack.copy();
        // ⚠ 同 bindStepCrafter：这里原来也走 5 参构造器、把候选组与 requiresInput/displayName 抹掉。
        SequencePatternData.writeUnit(updated,
            new SequencePatternData.UnitData(unit.machine(), unit.input(), "",
                unit.recipe(), unit.step(), unit.requiresInput(), unit.displayName(),
                unit.recipeType(), unit.inputCandidates()), registries);
        setArrangementUnit(index, updated);
        setChanged();
        return true;
    }

    /** 当前某一步（全局下标）绑定的自动合成仓名称（未绑定返回 ""）。 */
    public String getStepCrafter(final int index) {
        if (index < 0 || index >= arrangementSize) {
            return "";
        }
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final SequencePatternData.UnitData unit = SequencePatternData.readUnit(
            arrangementUnit(index), registries);
        return unit != null && unit.crafter() != null ? unit.crafter() : "";
    }

    /** 名称是否可作为绑定目标：必须来自网络中真实存在的自动合成仓或序列执行仓。 */
    private boolean isKnownCrafterName(final String crafterName) {
        return crafterName != null && !crafterName.isEmpty()
            && (getAutocrafterNames().contains(crafterName)
            || getExecutionChamberNames().contains(crafterName));
    }

    /**
     * 轮换某一步（全局下标）绑定的执行仓：候选 = [未绑定, 网络中全部执行仓名]，
     * delta=±1 在候选环上前后移动；网络中没有任何执行仓时保持现状（无物可轮选）。
     */
    public void cycleStepCrafter(final int index, final int delta) {
        final List<String> chamberNames = getExecutionChamberNames();
        final java.util.LinkedHashSet<String> cycle = new java.util.LinkedHashSet<>();
        cycle.add(""); // 未绑定
        cycle.addAll(chamberNames);
        if (cycle.size() <= 1) {
            return;
        }
        final List<String> entries = new ArrayList<>(cycle);
        final String current = getStepCrafter(index);
        int idx = entries.indexOf(current);
        if (idx < 0) {
            idx = 0; // 旧绑定已失效（执行仓被移除/改名）
        }
        idx = Math.floorMod(idx + delta, entries.size());
        final String target = entries.get(idx);
        if (target.isEmpty()) {
            unbindStepCrafter(index);
        } else {
            bindStepCrafter(index, target);
        }
    }

    /**
     * 把「展开后的流程」写入<b>展示数据</b>（导入路径在方块实体侧的唯一写入口）。内部 API；
     * 唯一调用方是菜单的 {@code importSequencedRecipe}（JEI 配方转移），
     * 并在返回前完成 {@link #rebindStepMachinesToRecipeTypes()} 机器重绑定。
     * <h2>为什么只写展示数据</h2>
     * <p>用户要求：导入配方时「只显示它的流程编排、输入原料」，<b>不要先把样板做出来</b>。
     * 旧实现把各步单元样板写进 {@code arrangement}（真实容器）与单元样板库，于是<b>拆掉方块会掉出
     * 一堆玩家从未投入过的样板</b>。现在导入只写 {@link #displayArrangement}（非真实物品、永不掉落），
     * 界面照常按它渲染流程 / 步数 / 机器 / 次数；真实产出统一在点「生成样板」时发生
     * （见 {@link #generateAssemblyPattern()}，会按张数消耗 {@code refinedstorage:pattern}）。</p>
     * <p>老存档的真实编排容器 {@link #arrangement} 在这里<b>一格都不动</b>：它照常显示（展示数据为空时）
     * 并照常随方块掉落。</p>
     * <h2>相邻的完全相同步骤自动合并成一列（用户硬要求）</h2>
     * <p>用户原话：「后面不是有两个一样的吗？它必须得这样子：重复、自动合并、完全一样的自动合并」。
     * 因此写入展示数据前先把<b>相邻且完全相同</b>的步骤合并：合并后的那一列带<b>重复次数 N</b>
     * （见 {@link #sameSteps(ItemStack, ItemStack)} 的判据），界面上就是「一行 + ×N」。</p>
     * <p><b>为什么合并不会把加工链路做断</b>（上一轮去掉合并的根因）：
     * 合并后只剩「这一段的第一级」这一列，但执行仓的「本仓负责的步」是按
     * <b>配方类型</b>从整条配方序列里推出来的（{@code SequenceExecutionChamberBlockEntity#computeOwnedSteps}：
     * 「同一配方里配方类型与本仓样板一致的每一步都归本仓」），因此坚固板的第 2、3 步（都是
     * {@code create:pressing}）<b>仍然全部归那台冲压仓</b>，{@code judgeStep} 会对
     * {@code s % T == 1} 与 {@code s % T == 2} 都判成「下一步就是我的」→ 不会被过早收回，
     * 第 3 步照旧由同一台机器接着做，直到出成品。</p>
     */
    public boolean applyRecipeToArrangement(final List<ItemStack> units, final List<Integer> counts) {
        if (units == null || units.isEmpty()) {
            return false;
        }
        // ① 合并相邻的完全相同步骤（保序；只把「计数」相加，绝不丢任何一列的语义）
        final List<ItemStack> merged = new ArrayList<>(units.size());
        final List<Integer> mergedCounts = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            final ItemStack unit = units.get(i) == null ? ItemStack.EMPTY : units.get(i);
            final int count = counts != null && i < counts.size()
                ? Math.max(COUNT_MIN, counts.get(i)) : COUNT_MIN;
            if (!merged.isEmpty() && sameSteps(merged.get(merged.size() - 1), unit)) {
                final int last = mergedCounts.size() - 1;
                mergedCounts.set(last, mergedCounts.get(last) + count); // 完全一样 → 重复次数累加
                continue;
            }
            // 保留该段的第一张单元样板：它的 {@code step} 就是这一段<b>第一级</b>的真实配方下标，
            // 因此配方序列下标不丢（执行仓按类型推步 + 归属判定都仍能对上）。
            merged.add(unit.copy());
            mergedCounts.add(count);
        }
        // ② 先切到展示模式，再裁剪：否则 truncateArrangementTo 会去 resize 老存档的真实容器（= 删物品）
        displayArrangementActive = true;
        truncateArrangementTo(merged.size(), false);
        for (int i = 0; i < merged.size(); i++) {
            setArrangementUnit(i, merged.get(i));
            arrangementCounts.set(i, clampCount(mergedCounts.get(i)));
        }
        // 每次导入 / 转移都<b>重新解析并自动绑定</b>每一步的机器 —— 绝不沿用上一次配方残留的映射。
        rebindStepMachinesToRecipeTypes();
        setChanged();
        return true;
    }

    /**
     * 两列是否可以合并成一列（「完全一样」的判据，<b>只看步序以外的全部语义</b>）。
     * <p>比较项：配方 id、配方类型、绑定的自动合成仓、输入标记（物品 + 数据组件）、输入流体
     * （流体 + 数据组件）、是否需要物品输入。刻意<b>不</b>比较 {@code step} ——
     * 同一段重复步骤的步序本来就不同，这正是要合并的地方。</p>
     */
    private boolean sameSteps(final ItemStack a, final ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() && b.isEmpty();
        }
        final HolderLookup.Provider regs = registries();
        final SequencePatternData.UnitData ua = SequencePatternData.readUnit(a, regs);
        final SequencePatternData.UnitData ub = SequencePatternData.readUnit(b, regs);
        if (ua == null || ub == null) {
            return ua == null && ub == null;
        }
        if (!java.util.Objects.equals(ua.machine(), ub.machine())
            || !java.util.Objects.equals(ua.recipe(), ub.recipe())
            || !java.util.Objects.equals(ua.recipeType(), ub.recipeType())
            || !java.util.Objects.equals(ua.crafter(), ub.crafter())
            || ua.requiresInput() != ub.requiresInput()) {
            return false;
        }
        final ItemStack ia = ua.input() == null ? ItemStack.EMPTY : ua.input();
        final ItemStack ib = ub.input() == null ? ItemStack.EMPTY : ub.input();
        if (!ItemStack.isSameItemSameComponents(ia, ib)) {
            return false;
        }
        return net.neoforged.neoforge.fluids.FluidStack.isSameFluidSameComponents(
            SequencePatternData.readUnitFluid(a, regs), SequencePatternData.readUnitFluid(b, regs));
    }

    /** 最近一次导入后「匹配不到可用机器」的步数（0 = 全部绑定成功）；供提示文案使用，不持久化。 */
    private int lastUnboundStepCount;

    /** 最近一次导入后有多少步没有匹配到机器（界面 / 动作栏据此给出可见提示）。 */
    public int lastUnboundStepCount() {
        return lastUnboundStepCount;
    }

    /**
     * 按<b>当前编排各步的处理器类型</b>重新解析并自动绑定机器（导入 / 转移的唯一绑定入口）。
     * <h2>为什么必须先清空再绑定</h2>
     * <p>旧实现只写编排数据、不动 {@code stepMachinePos/stepMachineNames}：把「坚固板（冲压 + 注液）」
     * 换成「精密构建」后，各步仍指向旧配方绑定的那几台机器（用户现象：「各步骤仍然指向旧的冲压 /
     * 注液配置」）。因此这里<b>先把映射整体清空</b>（绝不沿用上一次配方的残留），再按每一步的
     * recipeType 重新解析：</p>
     * <ul>
     *     <li>匹配到 → 绑定该类型下的第一台执行仓（{@link #resolveDefaultChamber}，与界面「预览第一台」
     *     同源，因此玩家看到的机器名就是真正生效的那台）；</li>
     *     <li>匹配不到 → 该步<b>留空</b>（不指派），并把步数记进 {@link #lastUnboundStepCount()}；
     *     界面会对这些步骤显示红字「无可用机器」，导入提示也会说明有多少步留空 —— 而不是沿用旧值。</li>
     * </ul>
     * <p><b>导入路径共用本方法</b>：所有导入 / 转移（JEI「+」→ {@code SetSequenceImportPacket}
     * → 菜单 {@code importSequencedRecipe}）最终都走 {@link #applyRecipeToArrangement}，
     * 因此绑定逻辑<b>只有这一份</b>；删除「导入」按钮后不再有第二条导入路径。</p>
     *
     * @return 匹配不到机器的步数（0 = 全部绑定成功）
     */
    public int rebindStepMachinesToRecipeTypes() {
        lastUnboundStepCount = 0;
        ensureStepMachineCapacity();
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        for (int i = 0; i < arrangementSize; i++) {
            // 先清空：上一次配方的映射一律作废（这是「没有自动选中对应机器」的根因）
            stepMachinePos.set(i, null);
            stepMachineNames.set(i, "");
            final String recipeType = SequencePatternData.readUnitNew(arrangementUnit(i), registries).recipeType();
            if (recipeType == null || recipeType.isEmpty()) {
                lastUnboundStepCount++;
                continue;
            }
            final ChamberInfo chamber = resolveDefaultChamber(recipeType);
            if (chamber == null) {
                lastUnboundStepCount++;
                continue;
            }
            stepMachinePos.set(i, chamber.pos());
            stepMachineNames.set(i, chamber.name());
        }
        return lastUnboundStepCount;
    }

    /** 清空并重建流程编排为指定数量空步骤（各次数 1）。 */
    public void resetArrangementRows(final int size) {
        truncateArrangementTo(size, false);
        for (int i = 0; i < arrangementSize; i++) {
            setArrangementUnit(i, ItemStack.EMPTY);
            arrangementCounts.set(i, COUNT_MIN);
        }
        setChanged();
    }

    // ========== 单元样板库 ==========

    /**
     * 将单个 Create 操作步骤写入单元样板库的第一个空槽（不覆盖已占用格）；
     * 库已满时追加到末尾（<b>自动扩容，不设上限</b>），不会丢弃样板。
     */
    public void putUnitIntoLibrary(final ItemStack unitStack) {
        if (unitStack.isEmpty()) {
            return;
        }
        unitLibrary.setStackInSlot(unitLibrary.firstEmptySlot(), unitStack.copy());
        setChanged();
    }

    /** 单元库中是否已存在相同机器类型的单元样板。 */
    private boolean hasMachineType(final String machine) {
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        for (int i = 0; i < unitLibrary.getSlots(); i++) {
            final SequencePatternData.UnitData unit = SequencePatternData.readUnit(unitLibrary.getStackInSlot(i), registries);
            if (unit != null && unit.machine().equals(machine)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描 Create 全部序列装配配方，把每个操作步骤转换为单元样板（按机器类型去重）写入单元库。
     * 为"+"号从序列装配 GUI 导入做基础：已自动建立单元样板库。
     */
    public void importUnitsFromCreateRecipes() {
        if (level == null) {
            return;
        }
        try {
            final HolderLookup.Provider registries = level.registryAccess();
            final var all = level.getRecipeManager().getAllRecipesFor(
                com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType());
            for (final var holder : all) {
                final Object recipeValue = holder.value();
                if (!(recipeValue instanceof com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe recipe)) {
                    continue;
                }
                for (final com.simibubi.create.content.processing.sequenced.SequencedRecipe<?> sr : recipe.getSequence()) {
                    final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> pr = sr.getRecipe();
                    final String machine = cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                        .recipeTypeId(pr);
                    if (hasMachineType(machine)) {
                        continue;
                    }
                    final ItemStack unit = new ItemStack(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get());
                    SequencePatternData.writeUnit(unit, new SequencePatternData.UnitData(
                        machine, ItemStack.EMPTY, "", "", -1, false, "", machine), registries);
                    putUnitIntoLibrary(unit);
                }
            }
        } catch (final Throwable t) {
            // 配方系统异常时静默忽略（如模组未加载完成）
        }
    }

    /*
     * 【已删除】按配方 id 直接导入的旧通道（原「导入」按钮 → 配方选择子界面 →
     * {@code ImportSequenceRecipePacket} → 原 importCreateRecipe(ResourceLocation)）。
     * 用户明确要求删除「导入」按钮（「导入按钮其实没什么用，一旦多起来还不如 JEI 选择」），
     * 因此整条按钮导入链（子界面、C2S 包、按 id 导入的方法）一并移除，只保留 JEI 转移路径：
     * JEI「+」→ SetSequenceImportPacket → 菜单 importSequencedRecipe
     * → {@link #applyRecipeToArrangement(List, List)}（含 {@link #rebindStepMachinesToRecipeTypes()}）。
     */

    // ========== 装配样板 / 单元样板生成 ==========

    /**
     * 生成一次产出的总样板块数（1 张）—— 与每步 1 张单元样板一起构成消耗总量。
     * <p>用户要求「生成样板的时候，再把这些东西都生成出来，同时消耗这个样板」：因此总样板同样
     * 记 1 张消耗，产出与消耗一一对应（S 步 = S 张单元样板 + 1 张总样板 = 消耗 S+1 张）。</p>
     */
    private static final int TOTAL_PATTERN_COST = 1;

    /**
     * 本次「生成样板」需要消耗的 {@code refinedstorage:pattern} 张数（= 有效步骤数 + 1 张总样板）。
     * <p><b>从哪里扣</b>：只从<b>终端自己的样板输入槽</b>（{@link #rsPatternSlots}，界面左侧 3 格）
     * 扣除 —— 玩家不再需要把样板背在身上（旧实现从玩家「背包 + 光标」直接扣，用户明确反对）。
     * 终端里的数量由 {@link #countRsPatterns()} 现取，菜单在生成前校验、生成成功后才扣。</p>
     * <p><b>唯一的总样板格是「只出不进」的</b>：生成出的总样板会占住它，因此在把它取走之前
     * 一律不可再生成（返回 -1）—— 这正是用户要的「要再生成新的总样板，必须先把现有的全部拿走」。
     * 注意判据用的是 {@link #countPatterns()}（内部 9 格全算），因此老存档里藏在靠内格位的样板
     * 同样会拦住重复生成，不会出现「看不见的旧样板被新样板挤掉」。</p>
     *
     * @return 需要消耗的张数；当前不可生成（已有总样板 / 没有可用产出 / 全部步骤都已存在相同样板）时返回 -1
     */
    public int generationPatternCost() {
        if (countPatterns() > 0) {
            return -1; // 已有总样板：必须先取走（绝不覆盖玩家资源、也不并发产出第二张）
        }
        if (resultSlotsEmpty() && scrapSlotsEmpty()) {
            return -1; // 至少需要一个产出，否则没有任何可生成的内容
        }
        // 逐步骤按各自的开关判定：开 且 已有完全相同样板 ⇒ 该步不生成（也不参与消耗）。
        // 判据来自 refreshStepDuplicateCache 的缓存（生成前会再重扫一次，保证权威）。
        int steps = 0;      // 本次真正会产出的单元样板张数（跳过重复的步不计）
        int considered = 0; // 有单元样板的步数
        for (int i = 0; i < arrangementSize; i++) {
            if (arrangementUnit(i).isEmpty()) {
                continue;
            }
            considered++;
            if (getStepSkipDuplicate(i) && stepDuplicateExists(i)) {
                continue; // 该步开 + 已有完全相同样板 ⇒ 该步不生成
            }
            steps++;
        }
        if (considered > 0 && steps == 0) {
            // <b>用户第 4 条：总样板始终生成。</b>所有步骤的相同单元样板都已存在（或「跳过重复」
            // 全开）时，<b>不再</b>整体拒绝生成 —— 仍要产出那张总样板（单元样板 0 张），
            // 因为总样板才是把这条流程接进 RS 自动合成的东西，玩家要的是「流程能用」而不是「有样板可生成」。
            // 旧实现在这里返回 -1 ⇒ 界面把按钮置灰并显示「无法生成：流程或产物无效」，
            // 正是用户抱怨的「你还提示什么流程无效什么的」。
            steps = 0;
        }
        return steps + TOTAL_PATTERN_COST;
    }

    /**
     * 是否「每一步（有单元样板的）都已存在相同样板且开关为开」——即本次生成<b>一个也不会产出</b>。
     * <p>供菜单在 {@link #generationPatternCost()} 返回 -1 时区分「全部已有（该给一次提示）」与
     * 「已有总样板 / 流程无效（界面 tooltip 已说明）」。纯读缓存，不触发扫描。</p>
     */
    public boolean allStepsDuplicate() {
        int considered = 0;
        for (int i = 0; i < arrangementSize; i++) {
            if (arrangementUnit(i).isEmpty()) {
                continue;
            }
            considered++;
            if (!(getStepSkipDuplicate(i) && stepDuplicateExists(i))) {
                return false;
            }
        }
        return considered > 0;
    }

    /** 从当前流程生成「总样板 1 张 + 每一列 1 张单元样板」。
     *  <p><b>产出落在哪</b>：总样板 → 终端<b>唯一</b>的那格总样板槽（{@link #patternSlots} 第 1 格，
     *  已有总样板时本方法直接拒绝，见 {@link #generationPatternCost()}）；
     *  每一列（= 一段完全相同步骤合并出来的列）一张单元样板 → {@link #unitLibrary}
     *  （真实物品，玩家可在单元样板槽窗口取出）。</p>
     *  <p><b>消耗</b>：本方法<b>不</b>碰玩家物品 —— 需要的 {@code refinedstorage:pattern} 张数由
     *  {@link #generationPatternCost()} 算出，菜单在调用前先校验<b>终端内的存量</b>
     *  （{@link #countRsPatterns()}）、调用成功后再从终端自己的样板输入槽扣除
     *  （{@link #consumeRsPatterns(int)}），因此「生成失败却扣了材料」不可能发生，
     *  玩家背包里的物品也一格都不会被动用。</p>
     *  <p><b>新语义（v4）</b>：每一步都会写入其 recipeType 与指派的机器（machinePos/machineName）。
     *  指派优先取界面上显式设置的机器（{@link #setStepMachine}）；未设置则自动选该配方类型下
     *  第一台执行仓（{@link #resolveDefaultChamber}）。若某步无任何可用机器，该步记为「无指派」
     *  （绝不抛异常），并把失败原因写入 {@link #getLastExportError()} 供前端读取。</p>
     *  @return true=已生成；false=编排/产物无效 */
    public boolean generateAssemblyPattern() {
        lastExportError = null;
        // 生成前重扫一次「每一步是否已有相同样板」：判定永远以此刻的网络实况为准（不依赖展示缓存）。
        refreshStepDuplicateCache();
        // 唯一的总样板格是「只出不进」的：已有总样板（含老存档里藏在靠内格位的）时绝不覆盖，
        // 必须先取走 —— 与 generationPatternCost() 同一条判据（服务端唯一权威）。
        if (countPatterns() > 0) {
            lastExportError = net.minecraft.network.chat.Component.translatable(
                "gui.rs_create_compat.sequence_pattern_terminal.generate.slot_full");
            return false;
        }
        // 可见格（第 1 格）仍要确认是空的：防御「内部前 9 格全空但可见格被异常写入」的极端情况
        final int totalSlot = firstEmptyVisiblePatternSlot();
        if (totalSlot < 0) {
            lastExportError = net.minecraft.network.chat.Component.translatable(
                "gui.rs_create_compat.sequence_pattern_terminal.generate.slot_full");
            return false;
        }
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        // 机器列表只扫一次网络，避免逐步重复遍历
        final List<ChamberInfo> chambers = listChambers();
        final List<SequencePatternData.UnitEntry> units = new java.util.ArrayList<>();
        // 逐步骤的「本步是否跳过生成」：开 且 已有完全相同样板 ⇒ 该步不生成新单元样板（用户要求）。
        final boolean[] skipStep = new boolean[arrangementSize];
        int skippedSteps = 0;
        int generatedSteps = 0;
        int missingMachineSteps = 0;
        int firstMissingStep = -1;
        String firstMissingRecipeType = "";
        for (int i = 0; i < arrangementSize; i++) {
            final ItemStack unitStack = arrangementUnit(i);
            final SequencePatternData.UnitData unit = SequencePatternData.readUnit(unitStack, registries);
            if (unit == null) {
                continue;
            }
            // 该步的「跳过重复」判定（判据完全复用 UnitPatternDedupe，不另写一套）
            if (getStepSkipDuplicate(i) && stepDuplicateExists(i)) {
                skipStep[i] = true;
                skippedSteps++;
                // 本轮新增：把「跟哪一张判成了重复」落日志（审计 §7.3 的取证缺口）。
                // 旧实现只留下这个布尔值，命中来源当场丢弃，于是「2 步全跳过」事后不可复现
                // —— 存档里那两条样板已经不在，谁也说不清当时是跟哪一张判重。
                // 这里输出判据的每一个分量（配方 / 步序 / 操作类型 / 代表物 / 候选组），
                // 走 5 秒同因合并闸，因此同一结论稳态最多每 5 秒一条。
                if (RsccAssemblyDebug.isEnabled()) {
                    final UnitPatternDedupe.Match hit = stepDuplicateMatch(i);
                    RsccAssemblyDebug.dedupe("terminal generate", i, true,
                        UnitPatternDedupe.describe(hit), hit == null ? -1 : hit.slot(),
                        UnitPatternDedupe.basisOf(unitStack, registries));
                }
            } else {
                generatedSteps++;
                // 反向证据同样入日志：这一步开了「跳过重复」却<b>没有</b>判成重复
                // （即「明明看到有相同样板却没跳过」这类怀疑的唯一反证）。
                // 只有真的开着开关才打 —— 关着开关时「会生成」是玩家的显式选择，没有诊断价值。
                if (getStepSkipDuplicate(i) && RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.dedupe("terminal generate", i, false, null, -1,
                        UnitPatternDedupe.basisOf(unitStack, registries));
                }
            }
            final String recipeType = unit.recipeType() == null ? "" : unit.recipeType();
            // 机器指派：显式指派（按坐标匹配）优先，否则自动选该配方类型下第一台
            ChamberInfo chamber = null;
            final BlockPos assignedPos = getStepMachinePos(i);
            if (assignedPos != null) {
                for (final ChamberInfo info : chambers) {
                    if (info.pos().equals(assignedPos)) {
                        chamber = info;
                        break;
                    }
                }
            }
            if (chamber == null && !recipeType.isEmpty()) {
                for (final ChamberInfo info : chambers) {
                    if (recipeType.equals(info.recipeType())) {
                        chamber = info;
                        break;
                    }
                }
            }
            final BlockPos machinePos = chamber != null ? chamber.pos() : null;
            final String machineName = chamber != null ? chamber.name() : "";
            if (machinePos == null) {
                missingMachineSteps++;
                if (firstMissingStep < 0) {
                    firstMissingStep = i;
                    firstMissingRecipeType = recipeType;
                }
            }
            units.add(new SequencePatternData.UnitEntry(
                unit.machine(), getArrangementCountAt(i), unit.input(), unit.crafter(),
                unit.recipe(), unit.step(), machinePos, machineName, recipeType,
                // 该步标记的输入流体（灌注 / 注液一类；无标记 = 空栈）。
                // 数据来源 = 单元样板物品上的 TAG_INPUT_FLUID（导入配方 / 详细配置子界面写入）。
                // 它必须随总样板一起落盘：RS 自动合成计算器据此知道还需流体，预览才会报「缺岩浆」。
                SequencePatternData.readUnitFluid(unitStack, registries),
                // 该步输入原料组的全部候选（标签型 ingredient，如列车轨道的「铁粒 或 锌粒」）：
                // 同样必须随总样板落盘 —— SequenceAssemblyPatternItem#collectInputs 据此把它们
                // 登记成 RS 样板里<b>同一个 ingredient 的多个候选</b>（否则锌粒永远不被认）。
                unit.candidatesOrRepresentative()));
        }
        if (skippedSteps > 0 && generatedSteps == 0) {
            // <b>用户第 4 条：总样板始终生成。</b>所有步骤都「跳过重复」（且确实已有相同单元样板）时，
            // 仍然继续往下走 —— 单元样板一张都不产出，但<b>总样板照常生成</b>。
            // 旧实现在这里直接 return false 并报「每一步的相同单元样板都已存在，未生成任何样板」，
            // 于是玩家点了「生成」什么也拿不到，还以为是流程坏了（用户原话：
            // 「总样板始终生成，不要因为我两个都把它取消了，就直接把它关了」）。
            if (RsccAssemblyDebug.isEnabled()) {
                // 本轮新增：汇总行里带上「跳过了哪些步、各自跟哪一张判成重复」。
                // 旧汇总行只有一句 "all 2 unit step(s) skipped as duplicates"，看不出命中的是谁。
                final StringBuilder detail = new StringBuilder(96);
                for (int i = 0; i < arrangementSize; i++) {
                    if (!skipStep[i]) {
                        continue;
                    }
                    if (detail.length() > 0) {
                        detail.append("; ");
                    }
                    final UnitPatternDedupe.Match hit = stepDuplicateMatch(i);
                    detail.append("step=").append(i)
                        .append(" dup_of=").append(UnitPatternDedupe.describe(hit))
                        .append(" slot=").append(hit == null ? -1 : hit.slot());
                }
                RsccAssemblyDebug.event("terminal generate: all " + skippedSteps
                    + " unit step(s) skipped as duplicates -> total pattern only"
                    + (detail.length() > 0 ? " | " + detail : ""));
            }
        }
        if (units.isEmpty() && resultSlotsEmpty() && scrapSlotsEmpty()) {
            return false; // 空编排且无产出：没有任何可生成的内容
        }
        final List<SequencePatternData.PatternOutput> results = new java.util.ArrayList<>();
        for (int i = 0; i < RESULT_SIZE; i++) {
            final ItemStack stack = resultSlots.getStackInSlot(i);
            if (!stack.isEmpty()) {
                results.add(new SequencePatternData.PatternOutput(
                    stack.copy(), resultChances[i]));
            }
        }
        final List<SequencePatternData.PatternOutput> scraps = new java.util.ArrayList<>();
        for (int i = 0; i < SCRAP_SIZE; i++) {
            final ItemStack stack = scrapSlots.getStackInSlot(i);
            if (!stack.isEmpty()) {
                scraps.add(new SequencePatternData.PatternOutput(
                    stack.copy(), scrapChances[i]));
            }
        }
        if (results.isEmpty() && scraps.isEmpty()) {
            return false; // 至少需要一个产出
        }
        final SequencePatternData.AssemblyData assembly = new SequencePatternData.AssemblyData(
            ingredientSlot.getStackInSlot(0).copy(),
            loops,
            units,
            results,
            scraps,
            // 起步原料的全部候选（「任意台阶」一类）：随总样板落盘，顶部的输入原料格与 RS 样板的
            // ingredient 才能显示 / 认下整组候选（2026-10-05）。没有它那一格永远只有代表物。
            ingredientCandidates()
        );
        final ItemStack pattern = new ItemStack(RS_Create_Compat.SEQUENCE_ASSEMBLY_PATTERN.get());
        SequencePatternData.writeAssembly(pattern, assembly, registries);
        // 总样板 → 唯一的那格总样板槽（上方已确认它为空）
        patternSlots.setStackInSlot(totalSlot, pattern);
        // 每一列（合并后的步骤）一张单元样板 → 单元样板库（真实物品，玩家可在单元样板槽窗口取出）。
        // <b>逐步骤去重</b>：该步开关为「开」且已有完全相同样板 ⇒ 该步不再生成（用户要求「不生成重复的」）；
        // 关闭的步照常生成。既有样板一律保留（只追加到库的第一个空位，绝不覆盖 / 清空）。
        for (int i = 0; i < arrangementSize; i++) {
            if (skipStep[i]) {
                continue; // 该步已有完全相同样板：跳过，不生成重复的一张
            }
            final ItemStack unitStack = arrangementUnit(i);
            if (!unitStack.isEmpty()) {
                putUnitIntoLibrary(unitStack);
            }
        }
        setChanged();
        if (missingMachineSteps > 0) {
            // 不抛异常：把「无可用机器」的失败原因暴露给前端
            lastExportError = noMachineError(firstMissingStep + 1, firstMissingRecipeType, missingMachineSteps);
        }
        return true;
    }

    /** 构造「无可用机器」提示（前端可直接展示）；recipeType 为空时用 "-" 占位。 */
    private static net.minecraft.network.chat.Component noMachineError(final int stepNumber,
                                                                       final String recipeType,
                                                                       final int count) {
        final net.minecraft.network.chat.MutableComponent base = net.minecraft.network.chat.Component.translatable(
            "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine",
            stepNumber, recipeType == null || recipeType.isEmpty() ? "-" : recipeType).copy();
        if (count > 1) {
            base.append(net.minecraft.network.chat.Component.translatable(
                "gui.rs_create_compat.sequence_pattern_terminal.export.no_machine.more", count - 1));
        }
        return base;
    }

    /** 从当前单元样板制作区生成单元样板（选中的自动合成仓 + 输入标记）并写入输出槽。
     *  <p>本方法只写自己的输出槽，不碰玩家物品；与总样板生成（{@link #generateAssemblyPattern()}）的
     *  「按张数消耗 {@code refinedstorage:pattern}」无关。</p>
     *  @return true=已生成；false=输入缺失 / 无自动合成仓 */
    public boolean generateUnitPattern() {
        final ItemStack input = unitInputSlot.getStackInSlot(0);
        if (input.isEmpty()) {
            return false;
        }
        final String autocrafterName = getSelectedAutocrafterName();
        if (autocrafterName.isEmpty()) {
            return false; // 网络中没有任何自动合成仓
        }
        final HolderLookup.Provider registries = level != null
            ? level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final ItemStack unit = new ItemStack(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get());
        SequencePatternData.writeUnit(unit, new SequencePatternData.UnitData(autocrafterName, input.copy()), registries);
        unitOutputSlot.setStackInSlot(0, unit);
        setChanged();
        return true;
    }

    /** 样板槽中的总样板张数（现在槽里只会是本模组的序列装配总样板）。 */
    public int countPatterns() {
        int count = 0;
        for (int i = 0; i < patternSlots.getSlots(); i++) {
            final ItemStack stack = patternSlots.getStackInSlot(i);
            if (acceptsPattern(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 产物区是否为空。 */
    public boolean resultSlotsEmpty() {
        return highestUsedIndex(resultSlots) < 0;
    }

    /** 废料区是否为空。 */
    public boolean scrapSlotsEmpty() {
        return highestUsedIndex(scrapSlots) < 0;
    }

    // ========== NBT 持久化 ==========

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("UnitLibrary", unitLibrary.serializeNBT(registries));
        tag.put("Arrangement", arrangement.serializeNBT(registries));
        // 展示数据（导入产生、非真实物品）：非空时落盘，读档后界面照旧显示同一条流程。
        // 只在非空时写标签：老存档（从未导入过）不会因为这个键的存在而被误判成「展示模式」，
        // 从而它的真实编排容器继续被展示、继续随方块掉落。
        if (highestUsedIndex(displayArrangement) >= 0) {
            tag.put("DisplayArrangement", displayArrangement.serializeNBT(registries));
        }
        // 起步原料候选：与 DisplayArrangement 同一层写出。客户端镜像靠同一份 NBT 拿到它，
        // 顶部「输入原料」格才能在没有配方回查结果时仍然轮播整组（「任意台阶」那一格）。
        SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);
        tag.put("UnitInput", unitInputSlot.serializeNBT(registries));
        tag.put("UnitOutput", unitOutputSlot.serializeNBT(registries));
        tag.put("AssemblyPattern", assemblyPatternSlot.serializeNBT(registries));
        tag.put("Ingredient", ingredientSlot.serializeNBT(registries));
        // 起步原料的全部候选（「任意台阶」一类；2026-10-05）。用统一的 InputCandidates 键读写，
        // 与单元样板里的候选组同一格式 ⇒ 老存档缺键读回空表，行为与改动前一字不差。
        SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);
        tag.put("Results", resultSlots.serializeNBT(registries));
        tag.put("Scraps", scrapSlots.serializeNBT(registries));
        tag.putInt("Loops", loops);
        tag.putInt("ArrangementSize", arrangementSize);
        tag.putInt("AutocrafterIndex", autocrafterIndex);
        tag.putIntArray("Counts", toCountArray());
        // 每一步「生成时是否跳过重复样板」开关：与 Counts 同一套平行表（只增不减、随步数对齐）
        tag.putIntArray("StepSkipDuplicate", toSkipArray());
        tag.putIntArray("ResultChances", toPercent(resultChances));
        tag.putIntArray("ScrapChances", toPercent(scrapChances));
        // 样板槽（9 格 refinedstorage:pattern）：必须持久化，否则区块卸载/重载会丢失实物（loadAdditional 已按 "Patterns" 读回）
        tag.put("Patterns", patternSlots.serializeNBT(registries));
        // 终端自有的样板输入槽（生成耗材）：同样必须持久化 —— 玩家放进去的样板归终端所有，
        // 区块卸载 / 存档重载后仍在这里（绝不依赖玩家背包）。
        tag.put("RsPatterns", rsPatternSlots.serializeNBT(registries));
        // 新语义：每一步的机器指派（Pos 以 long 存储；缺省 = 无指派）
        final net.minecraft.nbt.ListTag stepMachines = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < arrangementSize; i++) {
            final CompoundTag entry = new CompoundTag();
            final BlockPos pos = getStepMachinePos(i);
            if (pos != null) {
                entry.putLong(SequencePatternData.TAG_MACHINE_POS, pos.asLong());
            }
            final String machineName = getStepMachineName(i);
            if (!machineName.isEmpty()) {
                entry.putString(SequencePatternData.TAG_MACHINE_NAME, machineName);
            }
            stepMachines.add(entry);
        }
        tag.put("StepMachines", stepMachines);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("UnitLibrary")) {
            unitLibrary.deserializeNBT(registries, tag.getCompound("UnitLibrary"));
        }
        if (tag.contains("Arrangement")) {
            arrangement.deserializeNBT(registries, tag.getCompound("Arrangement"));
        }
        // 展示数据：必须在下面 syncArrangementLength / arrangementHasContent 之前读，
        // 否则「该按哪个容器校准步数」会被判错（会去 resize 老存档的真实容器 = 删物品）。
        if (tag.contains("DisplayArrangement")) {
            displayArrangement.deserializeNBT(registries, tag.getCompound("DisplayArrangement"));
        }
        displayArrangementActive = highestUsedIndex(displayArrangement) >= 0;
        if (tag.contains("UnitInput")) {
            unitInputSlot.deserializeNBT(registries, tag.getCompound("UnitInput"));
        }
        if (tag.contains("UnitOutput")) {
            unitOutputSlot.deserializeNBT(registries, tag.getCompound("UnitOutput"));
        }
        if (tag.contains("AssemblyPattern")) {
            assemblyPatternSlot.deserializeNBT(registries, tag.getCompound("AssemblyPattern"));
        }
        if (tag.contains("Ingredient")) {
            ingredientSlot.deserializeNBT(registries, tag.getCompound("Ingredient"));
        }
        // 起步原料候选（旧档缺键 ⇒ 空表；与 writeCandidates 同一键与格式）
        ingredientCandidates.clear();
        ingredientCandidates.addAll(SequencePatternData.readCandidates(tag, registries));
        if (tag.contains("Results")) {
            resultSlots.deserializeNBT(registries, tag.getCompound("Results"));
        }
        if (tag.contains("Scraps")) {
            scrapSlots.deserializeNBT(registries, tag.getCompound("Scraps"));
        }
        // 终端自有的样板输入槽（旧档没有该键 = 空，容错读回，不抛异常）
        if (tag.contains("RsPatterns")) {
            rsPatternSlots.deserializeNBT(registries, tag.getCompound("RsPatterns"));
        }
        if (tag.contains("Patterns")) {
            patternSlots.deserializeNBT(registries, tag.getCompound("Patterns"));
            // 老存档兼容（v7 起）：老档可能把样板放在靠后的格位，而界面只暴露第 1 格 ——
            // 读档即把样板同序前移（一格不丢、一格不复制），保证玩家能看到并取回；
            // 第 1 格已有样板时后面的一律原地保留（继续生效、破坏方块照常掉落）。
            compactPatternSlots();
        }
        // 老存档兼容（v8）：旧的「生成槽」是独立的 1 格容器（NBT 键 AssemblyPattern），
        // 现在界面只剩总样板槽这一格 —— 把旧生成槽里那张样板并进总样板槽（内部 9 格）的第一个空位，
        // 保证玩家不用拆方块就能取回；总样板槽也满时原地保留（仍在掉落清单里，绝不销毁）。
        if (!assemblyPatternSlot.getStackInSlot(0).isEmpty()) {
            for (int i = 0; i < patternSlots.getSlots(); i++) {
                if (patternSlots.getStackInSlot(i).isEmpty()) {
                    patternSlots.setStackInSlot(i, assemblyPatternSlot.getStackInSlot(0).copy());
                    assemblyPatternSlot.setStackInSlot(0, ItemStack.EMPTY);
                    compactPatternSlots(); // 并入的位置可能不在第 1 格 → 再压一次到可见位置
                    break;
                }
            }
        }
        loops = Math.max(1, tag.getInt("Loops"));
        autocrafterIndex = Math.max(0, tag.getInt("AutocrafterIndex"));
        // 步骤数（可任意大，0 = 空编排）：按它校准 arrangement 容量与次数表长度。
        // 允许 0：老存档里若真的是空编排（或从未导入过配方）必须照读为 0，不能硬抬到 1
        // —— 否则界面上又会凭空出现一行空步骤。旧存档里已有的步骤数照常读回。
        final int savedSize = Math.max(0, tag.getInt("ArrangementSize"));
        syncArrangementLength(savedSize, false);
        // 旧档兼容归一化：历史版本把「新建终端的编排步数」默认写成窗口高度（8），于是老存档里可能
        // 存在「8 行全是空步骤」的假编排（用户实测：打开界面就一大堆空行 / 序号）。
        // 只在「步数恰好等于窗口高度 且 这 8 行全空」时归一化成 0 步；只要有一行有单元样板，
        // 或步数不是 8，就一律按存档原样保留 —— 空行本来就没有任何内容可丢，真实步骤绝不丢。
        if (arrangementSize == ARRANGEMENT_WINDOW && !arrangementHasContent()) {
            syncArrangementLength(0, false);
            LOGGER.debug("[rs_create_compat] 样板终端读档：老档的空编排（{} 行全空）已归一化为 0 步",
                ARRANGEMENT_WINDOW);
        }
        final int[] counts = tag.getIntArray("Counts");
        for (int i = 0; i < arrangementSize && i < counts.length; i++) {
            arrangementCounts.set(i, clampCount(counts[i]));
        }
        final int[] rChances = tag.getIntArray("ResultChances");
        for (int i = 0; i < RESULT_SIZE && i < rChances.length; i++) {
            resultChances[i] = rChances[i] / 100F;
        }
        final int[] sChances = tag.getIntArray("ScrapChances");
        for (int i = 0; i < SCRAP_SIZE && i < sChances.length; i++) {
            scrapChances[i] = sChances[i] / 100F;
        }
        // 新语义：机器指派表（旧档无该 tag = 全部无指派）
        ensureStepMachineCapacity();
        if (tag.contains("StepMachines")) {
            final net.minecraft.nbt.ListTag stepMachines =
                tag.getList("StepMachines", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < arrangementSize && i < stepMachines.size(); i++) {
                final CompoundTag entry = stepMachines.getCompound(i);
                stepMachinePos.set(i, entry.contains(SequencePatternData.TAG_MACHINE_POS)
                    ? BlockPos.of(entry.getLong(SequencePatternData.TAG_MACHINE_POS)) : null);
                stepMachineNames.set(i, entry.contains(SequencePatternData.TAG_MACHINE_NAME)
                    ? entry.getString(SequencePatternData.TAG_MACHINE_NAME) : "");
            }
        }
        // 每一步「跳过重复」开关：与 Counts 同一套平行表读法。
        // 旧存档缺该键（数组为空 / 比步数短）⇒ 缺的步按默认开（用户要求「旧存档缺该键 ⇒ 默认开」）。
        final int[] skips = tag.getIntArray("StepSkipDuplicate");
        for (int i = 0; i < arrangementSize; i++) {
            stepSkipDuplicate.set(i, i >= skips.length || skips[i] != 0);
        }
    }

    /** 当前编排的前 arrangementSize 步里是否放了任何单元样板（读档归一化用，纯读不改状态）。 */
    private boolean arrangementHasContent() {
        for (int i = 0; i < arrangementSize; i++) {
            if (!arrangementUnit(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int[] toCountArray() {
        final int[] result = new int[arrangementSize];
        for (int i = 0; i < arrangementSize; i++) {
            result[i] = getArrangementCountAt(i);
        }
        return result;
    }

    /** 每一步「跳过重复」开关落盘为 int[]（1/0）：与 {@link #toCountArray()} 同一套平行表写法。 */
    private int[] toSkipArray() {
        final int[] result = new int[arrangementSize];
        for (int i = 0; i < arrangementSize; i++) {
            result[i] = getStepSkipDuplicate(i) ? 1 : 0;
        }
        return result;
    }

    private static int[] toPercent(final float[] chances) {
        final int[] result = new int[chances.length];
        for (int i = 0; i < chances.length; i++) {
            result[i] = (int) (chances[i] * 100);
        }
        return result;
    }

    /** 供菜单同步的 ContainerData（在菜单侧按滚动窗口换算各步次数/概率），见 {@code SequencePatternTerminalMenu}。 */
    public int dataSlotCount() {
        // 与 SequencePatternTerminalMenu.DATA_SLOT_COUNT 保持一致：窗口行数 + 5 头 + 4 偏移 + 概率 24
        // + 尾部 8（单元样板窗口 3：当前页 / 最大页 / 库条目数；总样板隐藏张数 1；终端内样板张数 1；
        //         生成所需张数 1；优先复用中间产物开关 1；自动合成仓索引已含在 5 头里
        //         —— 这里刻意与菜单常量逐项对齐）
        return ARRANGEMENT_WINDOW + 5 + 4 + 12 + 12 + 7;
    }

    /** 注册为 RS 网络节点容器（MOD 总线事件），使线缆/控制器可连接。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi.INSTANCE
                .getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        // 样板槽对网络 / Jade 暴露（<b>与 RS 原版样板终端同源</b>：同一个
        // Capabilities.ItemHandler.BLOCK；RS 那边是
        // ModInitializer#registerCapabilities → getPatternGrid ⇒ new InvWrapper(be.getPatternInput())）。
        //
        // 【为什么必须暴露，而不是只让界面能放】RS 输出总线取目标容器走的是它自己的
        // CapabilityCacheImpl（BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, 目标格,
        // 朝向)），Jade 取的是 CommonProxy#findItemHandler（Level#getCapability(同一个能力, ..., side=null)，
        // 见 Jade 的 UniversalPlugin 把 ItemStorageProvider 注册给 Block.class）—— 两个消费者是<b>同一个
        // 能力</b>，所以一次注册同时满足「输出总线能塞进来」与「Jade 能列出来」。
        //
        // 【暴露哪些槽】只暴露两段样板槽，且逐段沿用既有准入判据（本视图不重写任何判据）：
        //   ① 对外 0..8 → patternSlots（总样板槽；isItemValid = acceptsPattern，只收本模组总样板，
        //      9 格全部暴露是因为老存档第 2~9 格的样板必须仍能被物流搬走，否则等于丢物品）；
        //   ② 对外 9..11 → rsPatternSlots（终端自有的精致存储样板输入槽；isItemValid = isRefinedStoragePattern，
        //      只收 refinedstorage:pattern —— 这正是 RS 原版样板机那个 patternInput 的对应物，
        //      也是用户「用输出总线往终端里喂样板」所指的那一格）。
        // 单元样板库 / 流程编排 / 生成中转槽 / 三个幽灵标记容器一律不暴露：前者由终端自己管理，
        // 后三者的内容从来不是真实资源（暴露出去就是凭空造物）。
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.exposedPatternSlots
        );
    }

    /** 可增长/缩小的物品处理器：容量跟随流程步数动态变化；缩小时丢弃尾部内容。 */
    public static class GrowingStackHandler extends ItemStackHandler {
        public GrowingStackHandler(final int size) {
            super(size);
        }

        /** 调整为指定容量：扩大的尾部为空格；缩小丢弃超出部分。 */
        public void resize(final int newSize) {
            if (newSize < 0) {
                return;
            }
            if (newSize == getSlots()) {
                return;
            }
            final NonNullList<ItemStack> next = NonNullList.withSize(newSize, ItemStack.EMPTY);
            final int copy = Math.min(newSize, getSlots());
            for (int i = 0; i < copy; i++) {
                next.set(i, stacks.get(i));
            }
            stacks = next;
        }
    }

    /**
     * 幽灵（标记）版可增长容器：内容只是「导入配方后展开出来的流程」这种展示数据，
     * 玩家从未投入过任何物品，因此实现 {@link GhostContent}（掉落结算时被统一跳过）。
     */
    public static final class GhostGrowingHandler extends GrowingStackHandler implements GhostContent {
        public GhostGrowingHandler(final int size) {
            super(size);
        }
    }

    /**
     * <b>可增长、无上限</b>的单元样板库：保留「按槽位顺序摆放、可取出、可 shift 移入」语义
     * （不像 {@code RsccUnboundedItemStorage} 那样按种类合并），仅在容量不足时自动开槽。
     * <ul>
     *     <li>{@link #setStackInSlot(int, ItemStack)} 越界自动扩容；</li>
     *     <li>始终保留一个末尾空槽，使外部按 {@code getSlots()} 找空位的调用方（导入/生成/首空位写入）
     *     总能成功，不会因为库满而丢样板；</li>
     *     <li>{@link #serializeNBT} 保存前去掉尾部空槽（按实际条目数落盘），
     *     {@link #deserializeNBT} 读档后补回末尾空槽（旧 108 格存档可正常读入）。</li>
     *     <li>落盘格式与 NeoForge 21.1 的 {@code ItemStackHandler} 一致（entry 即 stack 的 NBT，{@code Slot} 同层）；
     *     读档额外兼容历史 {@code {Slot, Item:<stack>}} 旧格式并就地迁移，绝不因格式不匹配而清空整库。</li>
     * </ul>
     */
    public static final class GrowingUnitLibrary extends ItemStackHandler {
        public GrowingUnitLibrary(final int size) {
            super(Math.max(1, size));
        }

        /** 第一个空槽位；全满时返回 {@code getSlots()}（调用方写入该下标即自动扩容追加）。 */
        public int firstEmptySlot() {
            for (int i = 0; i < getSlots(); i++) {
                if (getStackInSlot(i).isEmpty()) {
                    return i;
                }
            }
            return getSlots();
        }

        @Override
        public void setStackInSlot(final int slot, final ItemStack stack) {
            if (slot < 0) {
                return;
            }
            rscc$ensureSlot(slot);
            super.setStackInSlot(slot, stack);
            if (stack.isEmpty()) {
                rscc$trimTrailingEmpty(); // 取空末格后收缩，避免出现多个末尾空槽/多余的空白页
            }
            rscc$ensureTrailingEmpty();
        }

        /**
         * 调整容量：扩大部分为空格，缩小时丢弃尾部。
         * <p><b>不能用 {@code stacks.add/remove}</b>：{@code ItemStackHandler} 内部的 {@code NonNullList}
         * 继承自 {@code AbstractList}，其 {@code remove} 会抛 {@code UnsupportedOperationException}
         * （表现为「方块实体写入状态失败，不会持久化」）。只能整体重建列表。</p>
         */
        private void rscc$resize(final int newSize) {
            if (newSize < 1 || newSize == getSlots()) {
                return;
            }
            final NonNullList<ItemStack> next = NonNullList.withSize(newSize, ItemStack.EMPTY);
            final int copy = Math.min(newSize, getSlots());
            for (int i = 0; i < copy; i++) {
                next.set(i, stacks.get(i));
            }
            stacks = next;
        }

        /** 保证下标可写：容量不足时补齐空槽。 */
        private void rscc$ensureSlot(final int slot) {
            if (slot >= getSlots()) {
                rscc$resize(slot + 1);
            }
        }

        /** 保证末尾始终存在一个空槽位（外部找空位写入时的落点）。 */
        private void rscc$ensureTrailingEmpty() {
            if (getSlots() == 0 || !getStackInSlot(getSlots() - 1).isEmpty()) {
                rscc$resize(getSlots() + 1);
            }
        }

        /** 去掉末尾连续空槽（取空末格后调用）：按实际条目数收缩，避免留下多余的空白页。 */
        private void rscc$trimTrailingEmpty() {
            int size = getSlots();
            while (size > 1 && getStackInSlot(size - 1).isEmpty()) {
                size--;
            }
            rscc$resize(size);
        }

        /**
         * 序列化：<b>绝不改动运行期状态</b>。
         * <p>历史崩溃根因：{@code serializeNBT} 里先 {@code trimTrailingEmpty()} 收缩、保存后再扩容，
         * 而收缩路径曾用 {@code NonNullList.remove}（{@code AbstractList} 不支持）抛
         * {@code UnsupportedOperationException}。这条路径会被 <b>客户端</b>触发
         * （{@code ClientLevel.setBlock} → {@code BlockSnapshot.create} → {@code saveWithFullMetadata}），
         * 于是「破坏方块」时直接崩游戏。</p>
         * <p>现在改为：不修改 {@code stacks}，直接构造落盘标签（尾部保留一个空槽的代价可以忽略），
         * 序列化因此成为纯读操作，任何调用方（保存 / 快照 / 同步）都不可能再触发结构变更。</p>
         */
        @Override
        public CompoundTag serializeNBT(final HolderLookup.Provider provider) {
            final CompoundTag tag = new CompoundTag();
            final net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
            int lastNonEmpty = -1;
            for (int i = 0; i < getSlots(); i++) {
                if (!getStackInSlot(i).isEmpty()) {
                    lastNonEmpty = i;
                }
            }
            for (int i = 0; i <= lastNonEmpty; i++) {
                final ItemStack stack = getStackInSlot(i);
                if (stack.isEmpty()) {
                    continue;
                }
                // NeoForge 21.1 落盘格式：entry 本身就是该 stack 的 NBT（含 id/count），
                // Slot 只能作为同层附加键。绝不能写成 {Slot, Item:<stack>} 的嵌套结构——
                // ItemStackHandler#deserializeNBT 会把整个 entry 交给 ItemStack.parse，
                // 嵌套结构解析失败会导致条目被静默丢弃（历史丢档 bug）。
                // 与 ItemStackHandler#serializeNBT 完全一致：Slot 作为 prefix 传入，
                // save 返回“prefix + stack 数据”合并后的新 CompoundTag（不会改写入参）。
                final CompoundTag prefix = new CompoundTag();
                prefix.putInt("Slot", i);
                final Tag entry = stack.save(provider, prefix);
                items.add(entry);
            }
            tag.putInt("Size", Math.max(1, lastNonEmpty + 1));
            tag.put("Items", items);
            return tag;
        }

        @Override
        public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag nbt) {
            // 不复用 super.deserializeNBT：它只认新格式（entry 即 stack 的 NBT），遇到旧格式
            // {Slot, Item:<stack>} 会解析失败并丢弃整条。这里显式兼容新旧两种格式，迁移优先于丢弃。
            final int size = nbt.contains("Size", Tag.TAG_INT) ? nbt.getInt("Size") : getSlots();
            rscc$resize(Math.max(1, size));
            for (int i = 0; i < getSlots(); i++) {
                stacks.set(i, ItemStack.EMPTY);
            }
            final net.minecraft.nbt.ListTag items = nbt.getList("Items", Tag.TAG_COMPOUND);
            int loaded = 0;
            int migrated = 0;
            int dropped = 0;
            int lastSlot = -1;
            for (int i = 0; i < items.size(); i++) {
                final CompoundTag entry = items.getCompound(i);
                final int slot = entry.getInt("Slot");
                if (slot < 0) {
                    continue;
                }
                final ItemStack stack;
                if (entry.contains("Item", Tag.TAG_COMPOUND)) {
                    // 旧格式：{Slot, Item:<stack>}，从 Item 子标签解析并迁移为新格式
                    stack = ItemStack.parseOptional(provider, entry.getCompound("Item"));
                    if (!stack.isEmpty()) {
                        migrated++;
                    }
                } else {
                    // 新格式：entry 本身即 stack 的 NBT
                    stack = ItemStack.parseOptional(provider, entry);
                    if (!stack.isEmpty()) {
                        loaded++;
                    }
                }
                if (stack.isEmpty()) {
                    dropped++;
                    continue;
                }
                rscc$ensureSlot(slot);
                stacks.set(slot, stack);
                lastSlot = Math.max(lastSlot, slot);
            }
            rscc$ensureTrailingEmpty();
            onLoad();
            if (dropped > 0) {
                LOGGER.warn("[rs_create_compat] 单元样板库读档：成功 {} 条（新格式 {} / 旧格式迁移 {}，最大槽位 {}），丢弃 {} 条无法解析条目",
                    loaded + migrated, loaded, migrated, lastSlot, dropped);
            } else {
                LOGGER.debug("[rs_create_compat] 单元样板库读档：成功 {} 条（新格式 {} / 旧格式迁移 {}，最大槽位 {}）",
                    loaded + migrated, loaded, migrated, lastSlot);
            }
        }
    }
}
