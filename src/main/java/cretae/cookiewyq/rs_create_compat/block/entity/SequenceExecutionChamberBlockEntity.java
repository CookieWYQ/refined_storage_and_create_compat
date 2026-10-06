package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.autocrafting.TimeoutableCancellationToken;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.TrackedResourceAmount;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe.SequencedAssembly;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData.UnitData;
import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import cretae.cookiewyq.rs_create_compat.network.SequenceExecutionChamberNetworkNode;
import cretae.cookiewyq.rs_create_compat.report.CompatCompletionSender;
import cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccBusCategory;
import cretae.cookiewyq.rs_create_compat.support.RsccChamberItemStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster;
import cretae.cookiewyq.rs_create_compat.support.RsccRefillPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccWireBlocks;
import cretae.cookiewyq.rs_create_compat.support.SafeCollect;
import cretae.cookiewyq.rs_create_compat.support.RsccIntermediateReusePolicy;
import cretae.cookiewyq.rs_create_compat.support.SequenceMaterialGuard;
import cretae.cookiewyq.rs_create_compat.support.SequenceMaterialGuard.StepVerdict;
import cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 序列执行仓：分布式“序列装配执行”的核心方块实体。
 * <p>
 * 职责：玩家在 6 格单元样板槽里放入“单元样板”（sequence_unit_pattern，代表配方中某一步），
 * 本机接入 RS 网络后周期扫描网络存储：
 * <ul>
 *     <li>认领<b>应交给本机相邻 Create 机</b>的过渡件/原料：带 {@code create:sequenced_assembly}
 *     进度组件的过渡件按（配方 id + step % 序列长度）匹配单元；无组件的起步原料只允许 step 0 且
 *     输入匹配的单元认领；</li>
 *     <li>认领成功后从网络取出（每次 ≤16），尽力塞给机器朝向侧的相邻容器（Create 机），
 *     放不下的部分立即回写网络；产物/中间件的回流由玩家自建的输入总线承担，本机不“收获”机器输出。</li>
 * </ul>
 * 引擎全表扫描自带 20 tick 节流；同一种资源认领后冷却 20 tick，避免多台执行仓互相争抢。
 */
public class SequenceExecutionChamberBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<SequenceExecutionChamberNetworkNode>
    implements cretae.cookiewyq.rs_create_compat.support.RsccClusterable,
    cretae.cookiewyq.rs_create_compat.support.RsccSharedCache.PoolSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SequenceExecutionChamberBlockEntity.class);

    /** 单元样板槽容量（大型双箱规格 6×9 = 54）。 */
    public static final int UNIT_SLOTS = 54;
    /**
     * 引擎全表扫描节流（tick）。
     *
     * <p><b>为什么是 5 而不是 20（本轮加速）</b>：这次节流同时卡住「备料
     * （{@link #fillInternalForBus}）」与「收集（{@link #collectFromFaces}）」这两个<b>逐件流转的关键路径</b>——
     * 一件料从机器 → 网络 → 下一台机器至少要走「收集 + 备料」两跳，每跳都被这个节流按 tick 计入。
     * 原值 20 等于每个 hop 的<b>最小延迟 1 秒</b>：用户实机一条坚固板产线（注液 → 冲压 ×2）单件要过 3 个 hop，
     * 于是「注液一直在注（Create 机器自身连续工作），冲压却过很久才搞一次、中间产物每隔几秒才输出一次」。
     * 降到 5 把每个 hop 的固有延迟压到 0.25 秒（4 倍吞吐），同时保留<b>节流</b>本身：
     * 全表扫描（{@code storage.getResources()}）依旧不是每 tick 都做，机器本身的工作节奏
     * （Create 的冲压 / 注液周期）仍是上限，本仓不会成为「每 tick 全表扫」的性能黑洞。</p>
     */
    private static final int ENGINE_INTERVAL_TICKS = 5;
    /** 同一种资源认领后的冷却（tick）。 */
    private static final int RESOURCE_COOLDOWN_TICKS = 20;
    /** 每次认领取出/送入的最大数量。 */
    private static final long CLAIM_BATCH = 16;
    /**
     * 「输入时原料」一次只送一份的节流单位（<b>用户要求</b>：只有一台机械手 / 一台机器时，
     * 持续把原料喂进去会把机器塞满导致堵塞）。
     * <p>因此同一目标机器<b>一次只送 1 件</b>，等这份被机器消化（机器不再持有它）之后才送下一份；
     * 判据见 {@link #holdsItem}。放宽成 16 会让「机器持有 = 已占用」的判定失去意义
     * （一次就把 16 件压进机器，机器照样被塞满）。</p>
     */
    private static final long INPUT_FEED_UNIT = 1L;
    /** 每次从「产物 / 中间产物输出面」抽取物品的最大数量。 */
    private static final int COLLECT_BATCH = 16;
    /** 每次从「产物输出面」抽取流体的最大数量（mB）。 */
    private static final int FLUID_COLLECT_BATCH = 1000;
    /** 配方 sequence 长度缓存的失效周期（tick，兼容数据包重载）。 */
    private static final int SEQ_CACHE_TICKS = 1200;
    /** 汇总日志节流（tick）。 */
    private static final int LOG_INTERVAL_TICKS = 600;

    /** 单元样板库存：只允许放本模组的单元样板，容量 6。 */
    public final SimpleContainer unitSlots = new SimpleContainer(UNIT_SLOTS);
    /** 机器朝向：机器侧 = 朝向所指方向（方块自带水平朝向 property）；链指向与它同源，见 {@link #chainFace()}。 */
    private Direction machineFace = Direction.NORTH;

    // ---------- 面配置（参考 Mekanism 的机器面「输入 / 输出」思路） ----------
    /**
     * 单个面的工作模式（与 Mekanism 的「面配置」同类思想，但按本仓的语义收敛为 4 档）：
     * <ul>
     *     <li>{@link #NONE} 无：该面不参与自动交互，也不对外暴露物流能力；</li>
     *     <li>{@link #INPUT} 原料输入面：本仓把认领到的原料 / 过渡件从该面推给相邻容器（相邻 Create 机）；</li>
     *     <li>{@link #OUTPUT} 产物输出面：本仓从该面相邻容器<b>抽出产物</b>存入本仓内部存储，
     *     并只在该面开放对外物流能力（且只允许抽取）；</li>
     *     <li>{@link #INTERMEDIATE} 中间产物输出面：本仓从该面相邻容器抽出<b>过渡件</b>，
     *     直接回写 RS 网络供下一个执行仓认领（网络不可用时并入内部存储）。</li>
     * </ul>
     */
    public enum FaceMode {
        NONE("none"),
        INPUT("input"),
        OUTPUT("output"),
        INTERMEDIATE("intermediate");

        private final String key;

        FaceMode(final String key) {
            this.key = key;
        }

        /** 语言键后缀（{@code gui.rs_create_compat.sequence_execution_chamber.face.<key>}）。 */
        public String key() {
            return key;
        }

        public FaceMode next(final int delta) {
            final FaceMode[] values = values();
            return values[Math.floorMod(ordinal() + delta, values.length)];
        }
    }

    /** 6 个面的工作模式（下标 = {@link Direction#get3DDataValue()}）。 */
    private final FaceMode[] faceModes = new FaceMode[Direction.values().length];

    // ---------- 输出模式（本轮新增的第二种输出方式：延长型输出 / 总线输出） ----------
    /**
     * 输出模式：
     * <ul>
     *     <li>{@link #FACE} <b>面输出</b>（默认）：完全按 6 面配置工作 —— 原料从「原料输入面」推给相邻机器，
     *     产物 / 中间产物从对应面收回（既有行为，一字未改）；</li>
     *     <li>{@link #BUS} <b>总线输出（延长型输出）</b>：<b>只把「推料 / 喂料」交给输出总线</b> ——
     *     本机「先把被相连输出总线选中的类别所需的物料从网络搬进本仓内部存储」
     *     （{@link #fillInternalForBus}），再由输出总线<b>从本仓内部存储</b>按类别推给它所面对的机器
     *     —— 取货源是本仓而不是网络，等于把本仓的输出链延长到 Output Bus 的朝向上
     *     （详见 docs/DESIGN_DECISIONS_ROUND4.md 第九轮）。
     *     <p><b>本轮修正：BUS 不再等于「完全不做面 I/O」</b> —— <b>收集方向保留</b>：
     *     {@link FaceMode#OUTPUT} / {@link FaceMode#INTERMEDIATE} 两个面照旧把相邻机器的产出收进来
     *     （见 {@link #collectFromFaces}）。为什么必须保留：机器加工完的产出压在机器 / 置物台上没人收，
     *     输出总线的目的地就永远 {@code machine_full}，链条第一步之后就断（用户实测断点的根因）。
     *     面配置语义一字未改（{@code NONE/INPUT/OUTPUT/INTERMEDIATE} 含义不变），只是 BUS 下
     *     {@link FaceMode#INPUT} 面的「推料」改由输出总线承担。</p></li>
     * </ul>
     */
    public enum OutputMode {
        FACE("face"),
        BUS("bus");

        private final String key;

        OutputMode(final String key) {
            this.key = key;
        }

        /** 语言键后缀（{@code gui.rs_create_compat.sequence_execution_chamber.output.<key>}）。 */
        public String key() {
            return key;
        }

        public OutputMode next(final int delta) {
            final OutputMode[] values = values();
            return values[Math.floorMod(ordinal() + delta, values.length)];
        }
    }

    /** 本仓的输出模式（NBT 持久化；旧存档缺省 = 面输出，行为与旧版完全一致）。 */
    private OutputMode outputMode = OutputMode.FACE;

    // ---------- 输出总线绑定（动态类别 + 多选 + 共享均分；服务端权威） ----------
    /** 连接检测（线缆 BFS）的最大步数（与输出总线侧同一上限，保证两侧判定一致）。 */
    private static final int BUS_LINK_MAX_STEPS = 64;
    /** 连接检测结果缓存时长（tick）：既避免每 tick 反复 BFS，也让「新放的线缆」最多 1s 内被发现。 */
    private static final int BUS_CONNECT_CACHE_TICKS = 20;
    /** 类别列表重建 / 归属归一化的节流周期（tick）。 */
    private static final int BUS_SCHEDULE_INTERVAL_TICKS = 20;

    /**
     * 类别 id → 选择该类别、且<b>属于本仓所在链（分支）</b>的输出总线坐标（<b>按坐标升序</b>，服务端权威）。
     * <p>这是共享均分的<b>唯一权威数据</b>：列表长度 N 就是共享台数，列表顺序就是轮询顺序；
     * 每次归一化都整表重建，因此「拆方块 / 改选择 / 换模式 / 拔样板」都会自愈。</p>
     * <p><b>「属于本仓所在链」的口径（2026-10-06 用户需求）</b>：一条输出总线只要接在链上的任意一台执行仓上，
     * 就等于接在整条链（整个分支）上 —— 集合来自 {@link #connectedExporterPositions()}（唯一事实源
     * {@link #isInMyChain(BlockPos)}），本仓物理上没贴着它也算；没串链时链 = 本仓自己，行为逐字不变。</p>
     */
    private final Map<String, List<BlockPos>> busCategoryOwners = new LinkedHashMap<>();
    /** 连接检测缓存：从本仓沿线缆可达、且自身解析结果指向本仓的全部输出总线坐标（按坐标排序）。 */
    private List<BlockPos> connectedExportersCache;
    /** 连接检测缓存到期时刻（{@link Level#getGameTime()}）。 */
    private long connectedExportersExpireAt;
    /**
     * 连接检测缓存：从本仓沿线缆可达、且自身解析结果指向本仓的全部<b>输入总线</b>坐标（按坐标排序）。
     *
     * <p><b>为什么需要它（用户第 1 条：已配置却报「缺输出总线配置」）</b>：本模组的架构里
     * 「机器的产出 / 废料 / 中间产物」是由<b>输入总线</b>收回网络的（全自动模式默认收全部非输入类），
     * 而不是靠输出总线去勾成品 / 废料类别。因此「这一步的产出有没有总线负责」必须把输入总线也算进来，
     * 否则一套完全正确的配置会被判成「缺输出总线配置」—— 这正是上一轮的假阳性来源之一。</p>
     */
    private List<BlockPos> connectedImportersCache;
    /** 输入总线连接检测缓存的到期时刻（{@link Level#getGameTime()}）。 */
    private long connectedImportersExpireAt;
    /** 动态类别列表缓存（由单元样板 + Create 配方数据推出；定期重建）。 */
    private List<BusCategoryInfo> busCategoriesCache;
    /**
     * 「一次一份」节流用的只读探针缓存：本 tick 内<b>相连输出总线正在供料的机器里还压着哪些物品</b>
     * （见 {@link #occupiedInputMaterials()}）。
     * <p>为什么需要这层缓存：{@link #busExportFilters} 会被每一台输出总线、每一个过滤项反复调用，
     * 没有它就会变成「每次调用都把所有目标机器容器遍历一遍」。</p>
     */
    private long occupiedProbeTick = Long.MIN_VALUE;
    private Set<Item> occupiedProbeItems = Set.of();
    /**
     * 同一探针的「<b>按本总线目标机器</b>」版本缓存（每 tick 每个目标机器只读一遍容器）：
     * 见 {@link #occupiedInputMaterialsAt(BlockPos)}。
     * <p>为什么要按机器分开：一台执行舱往往同时给<b>多台</b>机器供料，而各机器所处的步骤并不相同；
     * 用「全仓并集」去卡「该不该喂这份料」会让 A 机器手里的料把 B 机器正需要的同一份料也一起掐掉
     * （用户实测症状：不是「每种输出各一份」，而是「总体只出一份」）。</p>
     */
    private long occupiedAtProbeTick = Long.MIN_VALUE;
    private final Map<BlockPos, Set<Item>> occupiedAtProbeCache = new HashMap<>();
    /**
     * 「步骤专用投入物」表缓存（配方 id → 单循环步序 → 该步要投入的物品），与类别表同一节拍重建。
     * <p>见 {@link #inputMaterialWantedNow}：机械手每步要拿的东西各不相同，只有按「当前待加工步」
     * 放行其中一件，产线才能一步步走下去（否则手里被塞满第一步的料 → Create 拒收不同物品 → 永久卡住）。</p>
     */
    private Map<String, Map<Integer, Set<Item>>> stepExtraInputsCache;
    /** 「待加工步」探针的 tick 缓存（每 tick 至多读一遍供料目标的容器，见 {@link #pendingStepOnTargets()}）。 */
    private long pendingStepTick = Long.MIN_VALUE;
    private PendingStep pendingStepCache;
    /**
     * 「起步原料」表缓存（本仓负责的每条配方的主原料，见 {@link #startIngredients()}），
     * 与类别表同一节拍重建。
     * <p>见 {@link #isStartIngredient}：只有起步原料会「开一件新在制件」，因此「机器还在加工这一步的件
     * 就不再补料」这条闸门只能压在它身上。</p>
     */
    private Set<Item> startIngredientsCache;
    /**
     * 「<b>某一台</b>目标机器上的待加工件」探针的 tick 缓存（见 {@link #pendingStepOn(BlockPos)}）。
     * <p><b>为什么按「台」而不是「全仓并集」</b>：推料侧必须按「本总线面对的那一台机器」判「该喂哪一件」，
     * 否则多台机器处于不同步骤时（A 机器轮到齿轮、B 机器轮到大齿轮），全仓只能给出靠前那台的答案，
     * 靠后那台永远拿不到自己需要的投入物 —— 用户看到的正是「不是每种输出各一份，而是总体只出一份」。
     * 缓存按 tick 复用（每 tick 每台机器至多读一遍容器），且允许值为 {@code null}（= 这台没有在制件）。</p>
     */
    private long pendingStepAtTick = Long.MIN_VALUE;
    private final Map<BlockPos, PendingStep> pendingStepAtCache = new HashMap<>();
    /**
     * 「工位对这一份投入物的需要」被判为<b>卡住（长时间无进展）</b>所需的连续 tick 数。
     *
     * <h2>为什么「需要」必须再带一道时间闸门（用户第 2 条：齿轮堵在置物台，只能手动拿走）</h2>
     * <p>「本步要的投入物绝不收回」这条保护原本只看<b>静态状态</b>：只要这一步的配方需要它，就判定
     * 「机器正在用它」而不许收回。可是真正卡在工位上、机器<b>根本吃不下</b>的那一份（典型：机械手
     * 手里已经攥着别的一步的件，Create 的 {@code DeployerItemHandler} 对「手里已有不同物品」一律拒收）
     * 同样满足「本步要它」—— 判据因此<b>恒为真 ⇒ 永远不收回 ⇒ 永久堵塞</b>。用户实测：64 件期间
     * 齿轮在同一个工位堵了 3 次，每次都必须手动把它抠出来（系统一次都没收回去）。</p>
     * <h2>阈值为什么从 200 tick 收到 60 tick（本轮修正，用户第 ① 条）</h2>
     * <p>200 tick（10 秒）在实测里「太慢」：用户等了 5 秒就认为系统失灵（原话：「我已经等了很久了，
     * <b>超过 5 秒钟了但他还是没有正常回流</b>」）—— 而 5 秒 = 100 tick &lt; 200 tick，也就是说
     * 旧阈值下「10 秒才回流」属于设计内行为，用户却把它当故障。</p>
     * <p>新阈值取 {@value}（3 秒）的依据是参考产线的真实节奏：单个步骤在工位上的停留远小于 1 秒
     * （机械手 1 秒 / 冲压 0.5 秒 / 注液 1 秒一次动作，每件成品 4 秒以上），而「步序前进」或「这一份
     * 被消耗」都会立刻换 key / 重置计时（见 {@link #stationStuckOn}），所以 3 秒仍是正常节奏的
     * <b>3 倍以上余量</b>：既不会误收正在加工的那一件，又把「永久卡死」压成「最多 3 秒」。
     * 只读常量，与 {@code tools/selfcheck_round15_feedback.py} 的 STUCK_TICKS 必须一致。</p>
     *
     * <p><b>为什么不另做「立即判定」的快速路径</b>：唯一能无条件立即收回的情形是「工位此刻根本没有
     * 待加工件」（{@code pending == null}）—— 那一条早在 {@link #stationStepWantsInput} 里直接返回
     * {@code false}（不保护、立刻收回），根本不进本闸门。其余「这台机器吃不下」的判断都要一次
     * SIMULATE / 容器读取，把它塞进每 tick 的收回侧只会把「收回侧与推料侧同源」这条不变式拆成两套
     * 口径（历次堵塞的成因）。因此本轮只收阈值 + 修等待豁免（见 {@link #hasWaitReason}）。</p>
     */
    private static final int STATION_STUCK_TICKS = 60;
    /**
     * 「观察中断」的判定间隔（tick）：同一 (工位, 物品) 距上一次被观察到超过本值 ⇒ 视为
     * <b>这一份已经不在工位上了</b>（被消耗 / 被搬走 / 工位被清空），卡住计时<b>立即清零重来</b>。
     *
     * <p>取值 {@value #STATION_OBSERVE_GAP_TICKS}（1 秒）的理由：输入总线（本判定的主要调用方）的默认搬运节拍是 9 tick，
     * 装速度升级后更快；1 秒足以覆盖节拍抖动，又短到「一件被吃掉、下一件刚换上」这种换件不会被
     * 误当成「同一件一直在那儿」。<b>为什么必须明显小于 {@value #STATION_STUCK_TICKS}</b>：
     * 间隔一接近阈值，「换件」就会被误判成「同一份卡住」；反过来间隔过大，则「消耗掉、很快又放回
     * 一份新的」会沿用旧计时 ⇒ 新那一份被过早判成卡住。因此固定为阈值的 1/3。</p>
     */
    private static final int STATION_OBSERVE_GAP_TICKS = 20;
    /**
     * 「卡住结论粘住」之后的<b>慢重试</b>间隔（tick）：同一工位在判卡之后，只有隔了这么久才允许
     * <b>重新探测一次</b>（再买/再推一份），而不是每 tick 反复买-推-收。
     *
     * <h2>为什么必须有它（用户「还是堵塞齿轮」的真根因）</h2>
     * <p>现场实证（{@code run/logs/debug.log} 08:28）：同一工位 {@code (-7,-58,5)} 的齿轮被
     * <b>推过去 → 机器吃不下 → 判卡 → 输入总线收回网络 → 下一秒又买一份推过去</b>，形成 1 Hz 往返；
     * 而网络里 {@code create:cogwheel} 的 net 值单调递减（62→61→60→…→50），说明每圈都有齿轮被
     * 永久留在机器上。根因是<b>等待豁免在「刚被收回」之后又立刻成立</b>：
     * 收回后工位上不再压着这一件（{@code heldOnStation == false}）⇒ W3（该资源有在途自动合成量）
     * 又为真（RS 正在把齿轮作为精密构件的子合成在生产）⇒ {@code hasWaitReason} 返回 true ⇒
     * {@link #stationStuckOn} 把「首次观察时刻」刷新到现在 ⇒ 卡住结论被撤销 ⇒ 备料侧立刻再买一份。
     * 于是「判卡 → 收回 → 又当成在等料 → 再买」每 3 秒循环一次，且每一圈都净少一件。</p>
     * <p>取值 {@value #STATION_STUCK_REARM_TICKS}（10 秒）：远大于任何一次正常加工节奏（单步 &lt; 1 秒），
     * 因此稳态下<b>同一工位的往返为 0</b>；同时给「玩家把机器修好后自愈」留一条慢速通道
     * （最多每 10 秒重探一次），不会真的永久放弃某个工位。只读常量。</p>
     */
    private static final int STATION_STUCK_REARM_TICKS = 200;
    /** 卡住观察表的条目上限（超过则清掉长时间未被再次观察到的条目，防无界增长）。 */
    private static final int STATION_STUCK_WATCH_MAX = 64;
    /**
     * 「工位卡住」观察表：键 = 工位坐标 + 物品 + (配方, 步序)，值 = 首见 / 最近观察时刻。
     *
     * <p>签名一变（步序前进 / 换成另一件）或观察间隔太久（这一份被消耗掉 / 被搬走后又放回来）
     * 就重新计时，因此「<b>正在被消耗的投入物</b>」永远不会落入本判定。服务端权威；客户端恒为空。</p>
     */
    private final Map<String, StuckWatch> stationStuckWatch = new HashMap<>();
    /*
     * <b>为什么删掉了 W5「整条产线仍在推进」（本轮修正，用户第 ① 条：等了很久也不回流）</b>
     * <p>旧实现的 W5 用一个<b>全线</b>步序签名（{@code 各供料目标 坐标|配方#步序} 拼起来）在每次判定时
     * 就地刷新「最近推进时刻」，只要这个时刻在阈值以内就认定「本工位只是在等上游」。问题在于它是
     * <b>全局</b>的：一条产线上有 2 台以上机器时（用户现场正是 2 条输出总线 / 2 台机器），
     * 健康那台每 1 秒换一次步序 ⇒ 全局时刻每秒被刷新 ⇒ 卡住那个工位的计时<b>被无限推迟</b>，
     * 永远到不了阈值 ⇒ 齿轮一直躺在那儿，直到玩家手动拿走。这正是「等了 5 秒（甚至更久）也不回流」的
     * 直接机制。
     * <p>而 W5 想覆盖的「合法等待」早已被 W2（缺料等待档）/ W3（该资源有在途合成量）/
     * W4（该资源可自动合成且网络缺它）覆盖 —— 用户举的反例（小齿轮在自动合成、选的是等待模式）
     * 三条全中。因此本轮直接删除 W5，等待豁免只保留 W1~W4（都只看<b>这一份资源</b>，
     * 不看别的机器在不在动）。见 {@link #hasWaitReason(Item, long)}。
     */
    /** 「工位卡住」观察表的值：同一签名的首见 / 最近观察时刻（见 {@link #STATION_STUCK_TICKS}）。 */
    private static final class StuckWatch {
        private long since;
        private long lastSeen;
        /** 是否已经判定过「卡住」（结论粘住，见 {@link #STATION_STUCK_REARM_TICKS}）。 */
        private boolean stuck;
        /** 最近一次判定为「卡住」的时刻（慢重试计时的起点）。 */
        private long stuckSince;

        private StuckWatch(final long now) {
            this.since = now;
            this.lastSeen = now;
        }
    }

    /**
     * 「延长型输入」界面上恒定的共享台数（收回方向没有均分语义，见
     * {@link #busImportCategorySnapshot}）。
     */
    private static final int IMPORT_SNAPSHOT_SHARED_COUNT = 1;

    /** 类别调度节流计数。 */
    private int busScheduleCooldown;
    /** 上次调度时是否存在「被多台共享」的类别（决定是否逐 tick 向输出总线重推轮次）。 */
    private boolean busRoundRobinActive;
    /**
     * 导出清单的<b>有界重算</b>节流（tick）：无论有没有共享类别，最多每
     * {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 就把「此刻真的推得出去」的过滤项清单重发一次。
     * <p>为什么必须有它（用户要求：不要持续性的无效重复）：RS 只在归属变化时让输出总线重建清单，
     * 单归属类别因此会一直拿着很久以前的快照去取料 —— 快照里那些此刻干不了活的类别每 tick 判一次失败。</p>
     */
    private int busExportRefreshCooldown;

    /**
     * 「自动合成」门控检查节流（tick）：网络里有没有进行中的自动合成任务不需要每 tick 查。
     */
    private static final int BUS_GATE_INTERVAL_TICKS = 10;
    private int busGateCooldown;
    /**
     * 本仓此刻<b>有活跃订单</b>的配方 id 集合（见 {@link #orderedRecipes()}）。
     * <p>每次门控复查（{@value #BUS_GATE_INTERVAL_TICKS} tick，或被挂起 / 继续事件提前触发）重建一次；
     * 中间为 {@code null} ⇒ 一律视为「没有任何配方有单」，本仓绝不凭{{@code null}}开工。</p>
     */
    @org.jetbrains.annotations.Nullable
    private Set<String> orderedRecipesCache;

    /**
     * 「任务已结束」需要<b>连续</b>确认的次数（本轮修正）：每次门控复查（{@value #BUS_GATE_INTERVAL_TICKS} tick）
     * 记一次「空闲」，达到本阈值才真正执行「残留回流 + 整条产线停摆」这套收尾动作。
     * <p>3 次 = 1.5 秒。为什么必须有它：RS 的网络图重建、自动合成组件短暂为空、节点瞬时不挂网络
     * 都会让「有没有任务」闪一下；旧实现一观察到翻转就收尾，于是<b>任务还在跑却整条线突然停摆</b>
     * 并且把正在加工的残留原料搬回网络（用户实测症状）。连续确认把这类瞬时抖动全部滤掉，
     * 而真正的取消 / 完成也只晚最多 1.5 秒收尾。</p>
     */
    private static final int BUS_GATE_END_CONFIRM = 3;
    /** 「已结束」的连续空闲计数（见 {@link #BUS_GATE_END_CONFIRM}）。 */
    private int gateIdleStreak;
    /** 本次空闲期是否已经做过收尾（保证「一段空闲只回流一次」，不会每次复查都搬一遍）。 */
    private boolean busTaskEndFired;
    /**
     * 「订单剩余件数」的每 tick 缓存（份额 {@link #exportShare} 的数据源）。
     * <p>同一个 tick 内会被「每个类别 × 每条总线」反复查询，而 {@code getStatuses()} 每次都新建列表，
     * 不缓存就是每 tick 上千次分配 —— 与既有的两处探针缓存（occupiedProbe / occupiedAtProbe）同一手法。</p>
     */
    private long remainingOrderProbeTick = Long.MIN_VALUE;
    private long remainingOrderProbeValue = -1L;
    /**
     * 「在制件数」探针的<b>每 tick 缓存</b>（{@link #inFlightUnitCount()}）。
     * <p><b>为什么需要它</b>：{@link #remainingOrderUnits()} 判不出来（短命任务 / RS 状态瞬时为空）时，
     * 份额不再退回「无人管」的宽松口径，而是退回「本仓此刻真的有几件在制」这个<b>确定性</b>下界 ——
     * 而它会被同一 tick 内的多个类别 / 多条总线反复查询，因此与剩余件数同一节流（每 tick 至多真算一次）。</p>
     */
    private long inFlightProbeTick = Long.MIN_VALUE;
    private long inFlightProbeValue;

    /**
     * 「起步原料 → 它属于本仓哪几条配方」表缓存（与 {@link #startIngredientsCache} 同源、同生命周期）。
     *
     * <p><b>为什么需要它（2026-10-06：区分 100% 与 &lt;100% 配方）</b>：同一台执行舱常常同时挂着两条配方的
     * 样板（现场：列车轨道与精密构件的第 0 步都是 {@code create:deploying}，只能共用一台机械手），
     * 而两条配方的订单量与产出概率完全不同。在制名额必须<b>按「这份起步原料属于哪条配方」</b>分别算，
     * 否则一条配方的订单量会把另一条配方的起步原料也一起放行（详见
     * {@link #startCapacityFor(Item)} / {@link #startCapacityForRecipe(String)}）。</p>
     */
    private Map<Item, Set<String>> startIngredientRecipesCache;

    /**
     * 「某条配方此刻还能再开几件新在制件」探针的<b>每 tick 缓存</b>（见 {@link #startCapacityForRecipe}）。
     *
     * <p><b>为什么需要它</b>：这个名额同时被备料侧（{@link #clampStartIngredientTargets}，每个起步原料一次）
     * 与推料侧（{@link #unitCapacityLeftFor}，每条总线的每个类别一次）查询，而它内部要遍历
     * {@link TaskStatus} 列表并<b>跨执行舱</b>数在制件 —— 不缓存就是每 tick 几十次全网络扫描，
     * 与既有的 {@link #remainingOrderProbeTick} / {@link #inFlightProbeTick} 同一手法（每 tick 每配方至多真算一次）。</p>
     */
    private long startCapacityProbeTick = Long.MIN_VALUE;
    private final Map<String, Long> startCapacityProbeCache = new HashMap<>();

    /**
     * 「本网络全部执行舱」快照的每 tick 缓存（跨执行舱在制件计数用；只读）。
     * <p>{@link #chambersOf(Network)} 要遍历整张网络图（线缆 / 机器 / 存储节点全在内），
     * 而跨舱计数在一个 tick 内会被多个配方问到，因此快照按 tick 复用。</p>
     */
    private long chambersProbeTick = Long.MIN_VALUE;
    private List<SequenceExecutionChamberBlockEntity> chambersProbeCache = List.of();

    /**
     * 「共用同一台机器的多条配方，谁先站这台机器」探针的<b>每 tick 缓存</b>
     * （{@link #machineReservedRecipe()} 用；只读）。
     * <p>为什么必须缓存：同一个 tick 内它会按「每条总线 × 每个类别」被反复问到，而判据要遍历 RS 的
     * {@link TaskStatus} 列表 + 跨执行舱枚举 + 扫共享缓存池 —— 与既有的
     * {@link #startCapacityProbeTick} / {@link #chambersProbeTick} 同一手法，每 tick 至多真算一次。</p>
     * <p>{@code machineTurnProbed=false} = 本 tick 还没算过（与「算过、结论是没有争用」必须区分开，
     * 否则每 tick 第一次询问都会白白重算一遍）。</p>
     */
    private long machineTurnProbeTick = Long.MIN_VALUE;
    private boolean machineTurnProbed;
    @org.jetbrains.annotations.Nullable
    private String machineTurnRecipe;

    /**
     * 「某条配方的下单时刻」探针的每 tick 缓存（{@link #orderStartTimeFor(String)}；值 = 毫秒，
     * {@code -1} = 判不出来）。同一 tick 内每条候选配方各问一次，因此按配方缓存即可。
     */
    private long orderStartProbeTick = Long.MIN_VALUE;
    private final Map<String, Long> orderStartProbeCache = new HashMap<>();

    /**
     * 「网络共享中间产物缓存池里属于某条配方的过渡件」探针的每 tick 缓存
     * （{@link #pooledTransitionalUnits(Level, String)}；值 = {@code [0]} 全网络可推进件数 /
     * {@code [1]} 仅本仓可推进件数）。扫一遍池子不便宜，而它在一个 tick 内会被
     * 「在制件计数」与「排队赢家是否还有活」两处问到，因此按配方复用。
     */
    private long pooledProbeTick = Long.MIN_VALUE;
    private final Map<String, long[]> pooledProbeCache = new HashMap<>();

    /**
     * 策略 B（TARGET）下「按缺口发起自动合成」的评估节流（tick）：每 2 秒一次即可，
     * 避免每 tick 反复调 {@code ensureTask}。
     */
    private static final int BUS_AUTOCRAFT_COOLDOWN_TICKS = 40;
    private int busAutocraftCooldown;
    /**
     * 总线输出模式下的「<b>按需备料</b>」口径（本轮修正：不再维持固定大缓冲，改为只备当前所需的一批）。
     * <ul>
     *     <li>目标量 = <b>配方每批所需量 × {@value #BUS_TARGET_BATCHES}</b>：物品 = 该原料的件数、
     *     流体 = 每批 mB（就是类别 tooltip 里 `marker.amount` 显示的那个数）；</li>
     *     <li>再受安全上限夹紧：物品 {@value #BUS_ITEM_TARGET_MAX} 件 / 流体
     *     {@value #BUS_FLUID_TARGET_MAX} mB，防止「单批需求异常大」时把网络一次吸空；</li>
     *     <li>仓内已有量 ≥ 目标量就<b>不再从网络抽取</b>，超出所需的部分也一样不抽 ——
     *     因此仓内缓冲<b>不累积、不无限增长</b>（用户实测「岩浆一直增加」的直接原因就是旧实现
     *     固定按 1000 mB 备料）。</li>
     * </ul>
     */
    private static final int BUS_TARGET_BATCHES = 1;
    private static final long BUS_ITEM_TARGET_MAX = 64L;
    private static final long BUS_FLUID_TARGET_MAX = 1000L;
    /**
     * <b>「逐步消耗」的抽取批上限</b>（用户本轮第 ③ 条要求：原料要逐步、可预期地减少，
     * 不允许出现与当前加工进度不匹配的一次性大幅跳变 —— 对应现象「金板被一次性预抽，
     * 网络存量瞬间掉一大截再缓慢回落，看起来像少了好几十张」）。
     *
     * <h2>为什么要再夹一道「每轮抽取量」</h2>
     * <p>目标量（{@link #BUS_TARGET_BATCHES} 那一套）表达的是「仓里最终该有多少」，
     * 而<b>一次引擎运行</b>（每 {@value #ENGINE_INTERVAL_TICKS} tick 一次）真的从网络抽多少
     * 是另一件事：旧实现直接把「目标量 − 仓内已有量」一次性抽满，于是只要目标量偏大
     * （策略 B 下步骤输入按「每批 × loops」估、或安全上限 64 / 1000 mB），网络存量就会在
     * <b>一轮之内</b>掉到目标值，玩家看到的就是「瞬间掉一大截」。</p>
     * <p>现在每轮只抽<b>一批</b>（物品 {@value #BUS_PULL_BATCH_ITEMS} 件 / 流体
     * {@value #BUS_PULL_BATCH_FLUID} mB）：目标量不变（总量守恒，最终仍会补到位），
     * 只是把「一步到位」摊成「每 0.25 秒一小步」。引擎本就有 5 tick 节流、RS 输入 / 输出总线
     * 另有自己的搬运节流，而产线的真正瓶颈是 Create 机器自身的加工周期（注液 1 秒 /
     * 冲压 0.5 秒 / 机械手 1 秒），因此本批上限<b>远高于</b>机器消耗速度 ——
     * 不卡料、吞吐不降，只是不再「一次性」。</p>
     */
    private static final long BUS_PULL_BATCH_ITEMS = 4L;
    private static final long BUS_PULL_BATCH_FLUID = 1000L;
    /**
     * 策略 B 下每次 {@code ensureTask} 最多请求的缺口（用户第 ③ 条：不要按任务总量一次性预抽）。
     * <p>{@code ensureTask} 是「确保网络里至少有这么多」，因此分多次小批请求与一次性大请求
     * 在<b>总量上等价</b>（下一次冷却到了会继续补），差别只在「网络存量曲线是否平滑」。
     * 值取 RS 的堆叠上限 64，足以覆盖正常备料需求，又不会把整张订单一次压进网络。</p>
     */
    private static final long BUS_AUTOCRAFT_BATCH = 64L;

    // ---------- 缺料横幅（用户硬要求：因缺料而停必须弹提示，说清缺哪种材料、缺多少；且不刷屏） ----------
    /** 同一份缺口的横幅最小间隔（tick）：5 秒。缺口变化时立即再发一条。 */
    private static final int BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS = 100;
    /** 一条横幅里最多列几种材料（其余用「其他 N 种」概括，避免横幅那一行被撑爆）。 */
    private static final int BUS_SHORTAGE_NOTIFY_MAX = 3;
    /** 缺料横幅的广播半径（格）：只发给本仓附近的玩家（服务端权威）。 */
    private static final int BUS_SHORTAGE_NOTIFY_RADIUS = 64;
    /**
     * 缺料横幅的语言键（中英成对，见 lang/zh_cn.json 与 lang/en_us.json）。
     * <p>列表分隔符复用横幅系统既有的 {@code ...assembly.banner.separator}（与看门狗横幅同款），
     * 因此这里不再单设「join」键。</p>
     */
    private static final String KEY_SHORTAGE_TITLE = "gui.rs_create_compat.assembly.shortage.title";
    /**
     * 缺料条目键<b>按类型分成两条</b>（用户本轮要求：「资源不足提示缺少单位」）。
     * <p>为什么不是「一条 entry + 一个单位参数」，也不是把「个」写死在 Java 里：
     * <ul>
     *     <li>横幅由服务端构造、客户端解析（见 {@link CompletionBannerPayload#localized}），
     *     服务端的 {@code Language} 在专服上只有 en_us，因此<b>单位文案必须由语言键给出</b>；</li>
     *     <li>横幅的参数只能是纯字符串（客户端把参数原样塞进 {@code Component.translatable}），
     *     不能嵌套另一条语言键 —— 所以「物品 / 流体」两种单位只能在<b>键这一级</b>分开，
     *     各自把单位写在译文里（如「%1$s ×%2$s 个」/「%1$s ×%2$s mB」）。</li>
     * </ul></p>
     */
    private static final String KEY_SHORTAGE_ENTRY_ITEM = "gui.rs_create_compat.assembly.shortage.entry.item";
    private static final String KEY_SHORTAGE_ENTRY_FLUID = "gui.rs_create_compat.assembly.shortage.entry.fluid";
    private static final String KEY_SHORTAGE_MORE = "gui.rs_create_compat.assembly.shortage.more";
    /** 列表分隔符（横幅系统既有的「、」/「, 」，见 AssemblyWatchdog 同款用法）。 */
    private static final String KEY_SEPARATOR = "gui.rs_create_compat.assembly.banner.separator";
    /** 横幅行颜色（与看门狗横幅同款）：标题橙 / 正文浅灰。 */
    private static final int COLOR_TITLE = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    /** 逐段颜色调色板：同一行里并排的多种材料用不同颜色区分。 */
    private static final int[] PALETTE = {0xFFFF7F7F, 0xFFFFD479, 0xFF9EE493, 0xFF8ECBFF};

    // ---------- 「这次供的不是最优那一件」横幅（用户 R3/R4：必须取到一件，但要说清不是最优 / 不是全量） ----------

    /**
     * 横幅语言键（中英成对，见 lang/zh_cn.json 与 lang/en_us.json；键集合必须逐字一致）。
     * <p><b>为什么标题与正文分开两条键</b>：横幅由服务端构造、客户端解析（见
     * {@link CompletionBannerPayload#localized}），服务端的 {@code Language} 在专服上只有 en_us，
     * 因此文案只能由语言键给出、不能写死在 Java 里。</p>
     */
    private static final String KEY_HANDOFF_TITLE = "gui.rs_create_compat.assembly.handoff.title";
    private static final String KEY_HANDOFF_ENTRY = "gui.rs_create_compat.assembly.handoff.entry";
    private static final String KEY_HANDOFF_MORE = "gui.rs_create_compat.assembly.handoff.more";

    /**
     * 一条「替代供料」的实际发生记录（键 = 本仓这一刻确实<b>抽进了替代件</b>的那一对）。
     *
     * @param preferred 原本最优的那一件（= 该配方组首个候选，也就是类别图标）
     * @param actual    实际抽进来的替代件
     */
    private record Substitution(Item preferred, Item actual) {
    }

    /**
     * 本轮（每个备料周期）真的发生过的替代供料：一对 → 抽进来的件数。
     * <p>只在 {@code fillInternalForBus} 里重建与播报，因此它天然是「本轮状态」而不是历史累计。</p>
     */
    private final Map<Substitution, Long> handoffUsed = new LinkedHashMap<>();

    /**
     * <b>边沿语义</b>：上一次播报过的替代状态指纹（空串 = 上一次没有替代供料）。
     *
     * <h2>为什么必须用「指纹 + 边沿」而不是「限频」（用户明确反感重复提示）</h2>
     * <p>替代供料一旦发生就可能<b>每 5 秒持续为真</b>（机器一直在吃替代件）。若照缺料横幅那样
     * 「冷却到了再发」，玩家会被同一件事反复打扰 —— 那正是用户点名要修掉的行为。这里改成
     * <b>只认状态翻转</b>：进入替代状态发一次、恢复（替代消失）时清一次，
     * <b>同一状态原样持续期间一条都不再发</b>。</p>
     */
    private String handoffSignature = "";
    /** 允许下一次替代横幅的世界 tick（限频；只在<b>状态真的翻转</b>时才起作用）。 */
    private long handoffNotifyTick;

    /**
     * 「输入原料组耦合」缓存：配方 id → 该配方里<b>每一种物品</b>所属的全部候选组。
     * <p>与 {@link #stepExtraInputsCache} <b>同生命周期</b>（同样在类别表作废处一起置空），
     * 因此读到的候选组与 {@link #busCategories()} 里的候选集合必然同源、不会一个新一个旧。</p>
     */
    private Map<String, Map<Item, List<List<Item>>>> inputCouplingCache;

    /**
     * <b>缺口的「稳定期」</b>（tick）：同一份缺口必须<b>连续存在</b>这么多 tick 才允许播报（2 秒）。
     *
     * <h2>为什么必须有它（用户第 4 条：明明够了却提示少了一个黑曜石粉末）</h2>
     * <p>物料正常地在「网络 → 执行舱 → 机器」之间流动时，本仓内部与网络存量会在单个 tick 内出现
     * <b>瞬时缺口</b>（刚推给机器的那一帧网络与舱内都是 0，下一 tick 备料又补上）。旧实现只要
     * 「缺口指纹与上次不同」就<b>立刻</b>播报，于是这种一两帧的抖动会被当成真缺料弹给玩家 ——
     * 正是用户实测的「刚刚明明还是够了，然后又提示我少了个黑曜石粉末」。</p>
     * <p>现在改成<b>先看不发</b>：缺口指纹一变就把「起点」重置为现在，只有它<b>原样稳定</b>地持续
     * {@value #BUS_SHORTAGE_STABLE_TICKS} tick 才真正播报。真缺料只会晚 2 秒被报告（可接受），
     * 而抖动缺料永远不会被播报 —— 「刚好够」全程零提示。与看门狗的「原因连续超过阈值才挂起」
     * （{@code assemblyStallTimeoutTicks}）同一个思路，两处口径一致。</p>
     */
    private static final int BUS_SHORTAGE_STABLE_TICKS = 40;
    /** 上次提示过的缺口指纹（用于「缺口变了就立刻再发一条」）。 */
    private String busShortageSignature = "";
    /** 当前这一份缺口指纹「连续存在」的起点（世界 tick）；指纹一变即重置。 */
    private String shortageStableSignature = "";
    private long shortageStableSince;
    /** 允许下一次同缺口横幅的世界 tick（限频）。 */
    private long busShortageNotifyTick;
    /**
     * 上一轮引擎运行观察到的「网络里该件的存量」（键 = {@link #pipelineItemsCache} 里的物品）。
     * <p><b>用户第 5 条（本轮）：把「玩家手动拿走」这条外部路径也纳入日志</b> —— 手动拿走是一个
     * <b>外部事件</b>，本仓无从直接感知，它在日志里唯一的表现就是「网络里该件的数量下降」。
     * 这里逐轮比对，只在<b>真的下降</b>时打一条 {@code net_down}（稳态零输出）。只读，不参与任何判定。</p>
     */
    private final Map<Item, Long> lastNetAmount = new HashMap<>();
    /**
     * 流体侧的同款探针（键 = 本仓「流体输入」类别的流体）。
     *
     * <p><b>为什么要给流体也补上（用户第 1 条：岩浆「有的时候消耗、有的时候不消耗」）</b>：
     * 物品侧早就把「网络数量下降」记成 {@code net_down} 了，流体侧没有 —— 于是岩浆凭空少了一截时，
     * 日志里一条证据都没有，玩家只能猜。现在流体与物品<b>逐字段对称</b>：只在真的下降时输出一条，
     * 并标明「同 tick 有 pull fluid = 本仓抽的；没有 = 外部（玩家 / 别的设备）拿走的」，
     * 于是「岩浆到底是怎么少的」可以被逐笔对账。只读，不参与任何判定。</p>
     */
    private final Map<Fluid, Long> lastNetFluidAmount = new HashMap<>();
    /**
     * 执行舱的「自动合成」状态（服务端权威缓存）：本仓所在 RS 网络里存在**进行中的自动合成任务**时为真。
     * <p>未开启时 {@link #busExportFilters} 一律返回空清单 —— 输出总线不会再导出任何东西。</p>
     */
    private boolean busAutoCraftGate;

    /**
     * 本仓「产线相关任务」门控（服务端权威缓存）：网络里存在<b>与本仓产线相关</b>的进行中自动合成任务。
     *
     * <p><b>为什么不能只看 {@link #busAutoCraftGate}（用户实测断点）</b>：那是「整个网络有没有任务」，
     * 只要网络里还有<b>别的</b>任务在跑就恒为真，于是<b>本条序列装配任务结束 / 被取消</b>后，本仓残留的
     * 输入原料永远等不到「网络空闲」那一刻：既回不了网络，又会被继续备料 —— 玩家看到的就是
     * 「多余的输入原料没回流」。</p>
     *
     * <p>本字段把判定收窄到「与本仓配方相关」：本条任务一结束（完成 / 取消 / 停止）就翻转。
     * 备料（{@link #fillInternalForBus}）与回流（{@link #flushResidualInputs}）<b>共用同一个值</b>，
     * 因此不可能出现「一边回流、一边下一 tick 又备回来」的来回搬运。</p>
     */
    private boolean busRelevantTaskGate;
    /**
     * 「本仓真的有单」门控（服务端权威缓存）：网络里存在<b>以本仓产线目标（产物 / 在制件）为合成对象</b>
     * 的活跃任务（见 {@link #hasGoalTask()}）。
     *
     * <p><b>用户第 ⑦ 条的落点</b>：{@link #busRelevantTaskGate} 是「有任务牵涉本仓的料」（超集，含
     * 起始原料 / 输入性产物），而本字段是「有人为本产线下单」。两者必须同时为真本仓才开工，
     * 否则「玩家只是让定量保持器维持金板库存」也会让整条产线开始发料 —— 那是用户看到的
     * 「我明明没有下单，它却发金板让机械手做序列装配」。</p>
     */
    private boolean busGoalTaskGate;
    /**
     * 「挂起冻结」门控（服务端权威缓存）：本仓相关的自动合成任务<b>此刻全部被挂起</b>（玩家手动挂起，
     * 或看门狗自动挂起）。
     *
     * <p><b>为什么需要它（用户实测「挂起之后它好像还是在处理 / 不取消就一直在产」的根因）</b>：
     * RS 的「挂起」只做到「不再 step 那条任务」（见 {@code mixin/TaskContainerMixin}），而本仓是一条
     * <b>独立于任务推进的传送带</b> —— 只要 {@link #busRelevantTaskGate} 还是真（被挂起的任务仍在
     * {@code getStatuses()} 里），本仓就会继续备料 / 推料 / 收集，机器也就继续产，成品被输入总线
     * 插回网络（任务不 step，于是永远不会被任务截收）→ 数量越堆越多、任务却永远不完成。
     * 本门控为真时本仓<b>全面停工</b>：不收集、不备料、不推料、不收回（含备料侧与步骤专用投入物），
     * 也<b>不做</b>「任务结束」那套残留回流（挂起 ≠ 结束，东西原地冻结，等玩家点「继续」）。</p>
     */
    private boolean busFrozenGate;
    /**
     * 最近一次见到的 {@link cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog#suspendEpoch()}
     * （挂起 / 继续 / 终止的全局版本号）。
     *
     * <p><b>为什么需要它（用户要求「挂起当刻即停」）</b>：门控平时每 {@value #BUS_GATE_INTERVAL_TICKS}
     * tick 才复查一次，挂起最多要 0.5 秒才反映到本仓。这里在每 tick 的调度里比对版本号，一旦变化
     * 就把复查冷却清零 → <b>挂起 / 继续的那一个 tick 内</b>门控立即重算，本仓当刻停 / 启。</p>
     */
    private int lastSuspendEpoch = Integer.MIN_VALUE;
    /**
     * 「本 tick 刚检测到本条（与本仓产线相关的）任务结束」的一次性闸门（服务端权威，每 tick 重算）。
     *
     * <p><b>为什么需要它（用户要求：停止后立刻停料 + 立刻回流）</b>：门控翻转这一 tick 里，
     * {@code tickBusScheduler} 会先做完回流（{@link #flushResidualInputs}），随后
     * {@code tickEngine} 还会走一遍同一个 tick 的备料（{@link #fillInternalForBus}）。
     * 虽然两个门控此刻已经是假、备料本来就会早退，但这里再做一道<b>显式</b>的「本 tick 不备料」闸门，
     * 使「先停料、再回流、本 tick 不再补料」成为代码层面可验证的顺序保证 ——
     * 而不是依赖「门控恰好是假」这种间接结论。下一 tick 起由门控本身接管（任务已结束 → 不会备料）。</p>
     */
    private boolean busTaskEndedThisTick;
    /**
     * 「任务刚结束」时发给输入总线的<b>一次性残留回流令牌</b>（服务端权威，非每 tick 字段）。
     *
     * <p><b>解决什么（用户验收标准 #1：500 mB 残留只在「刚结束那一刻」收一次）</b>：
     * 上一轮为了「停任务后残留能回流」，把「任务不在跑」状态下输入类的保护也放开了 —— 副作用是
     * <b>网络空闲时玩家手工放在机器上的输入原料也会被持续收走</b>。本轮改成<b>一次性边沿语义</b>：
     * 只有「本条任务刚结束」的那一个边沿发出<b>一枚令牌</b>，输入总线在<b>紧接着的那一次</b>自动收回
     * 里凭令牌把残留的输入类收一遍；令牌用完即作废，之后输入类照旧受保护。</p>
     *
     * <p><b>为什么不是「本 tick 是边沿」这种单 tick 布尔</b>：输入总线的搬运节拍由 RS 自己的
     * {@code NetworkNodeTicker} 决定（默认<b>每 9 tick 一次</b>，装速度升级后才更快），
     * 与本仓 {@code tickBusScheduler} 的那一 tick 并不同步 —— 单 tick 布尔会被 9 次里漏掉 8 次，
     * 残留就永远收不干净。因此这里用「一次性令牌 + 有效期」：
     * 令牌只可能被消费一次（{@link #claimResidualInputReclaim()}），且超过
     * {@value #BUS_RESIDUAL_FLUSH_WINDOW_TICKS} tick 自动过期，绝不会变成「网络空闲时持续收输入类」。</p>
     */
    private boolean busResidualInputToken;
    /**
     * 一次性残留回流令牌的截止时刻（{@link Level#getGameTime()}）；令牌过期即作废。
     * <p>有效期取 {@value #BUS_RESIDUAL_FLUSH_WINDOW_TICKS} tick（= 2 秒）：既覆盖 RS 输入总线默认
     * 9 tick 的搬运节拍，又短到不可能把「边沿之后玩家手工放上去的输入类」也算进这一轮收尾。</p>
     */
    private long busResidualInputDeadline;
    /**
     * <b>「本流程每一步的总线配置是否齐全」门控</b>（服务端权威，由 {@link #tickBusScheduler()} 每
     * {@value #BUS_GATE_INTERVAL_TICKS} tick 复查）。
     *
     * <h2>为什么需要它（用户第 5 条：配置好机器但忘记配总线 ⇒ 一直卡着还白烧原料）</h2>
     * <p>用户原话：「配置好了机器什么的但是输入输出总线没有正常的配置好……下单的时候需要你检测一遍……
     * 这就是导致白烧了好几个黑曜石粉末」。实测机制：某一<b>步</b>的机器没配输入 / 输出总线时，本仓
     * 仍会按「相关任务在跑」备料、推料，物料被抽进机器却没有任何总线把它导出 / 收回 ⇒ 任务永远推不动，
     * 而网络里的原料被一批批抽走（用户看到的「白烧了 3~4 个黑曜石粉 + 岩浆」）。
     * 因此 {@code false} 表示「本流程存在配置不全的步骤」⇒ 本仓<b>不取料 / 不投料</b>（见
     * {@link #isAutoCraftingEnabled()} 与 {@link #fillInternalForBus}），并用一次横幅明确报出
     * 「第 N 步（机器名）：缺输入 / 缺输出总线配置」，绝不让玩家以为是机器坏了。</p>
     * <p>默认 {@code true}（判不出来 = 放行），绝不因为判不出来而停线。</p>
     */
    private boolean busConfigGate = true;
    /** 最近一次已播报的「配置缺口」签名（内容变了才再播一次，绝不每 0.5 秒刷）。 */
    private String busConfigGapSignature = "";
    /** 挂起边沿的「已把仓内东西退回网络」标记（同一段挂起只退一次；解冻后复位）。 */
    private boolean busSuspendFlushed;
    /**
     * <b>「推不动」的两种持续状态</b>的时间戳（只记时刻，服务端；供装配看门狗把它变成一条明确提示）。
     *
     * <h2>为什么需要（用户第 2/3 条：机器被占用 / 下游满 ⇒ 必须弹提示，不能干等）</h2>
     * <p>实测日志里 {@code bus_skip … reason=DESTINATION_DOES_NOT_ACCEPT/machine_full} 每 5 秒 11~13 次、
     * {@code reject {item=create:incomplete_track step=1} reason=no_machine_owns_step stepOwner=NOBODY}
     * 每 5 秒 8 次，而装配看门狗<b>一次挂起都没发生</b>（那一场日志里没有任何 {@code watchdog suspend}）
     * —— 因为它的判据里「缺料 / 离线 / 无进展」都不成立（推不动 ≠ 缺料），于是 {@code reason=NONE}
     * ⇒ 既不挂起、也不弹横幅 ⇒ 玩家面对的是「东西被抽干了、机器占着、毫无提示地干等」。
     * 现在这两种持续状态各留一个时间戳，看门狗读到就归类成 {@code EXECUTOR_OFFLINE} / {@code OUTPUT_BLOCKED}，
     * 于是既有横幅会明确报出原因。只写时间戳，不搬运资源。</p>
     *
     * <h2>⚠ 为什么初值必须是 {@link #NEVER} 而不是 {@code Long.MIN_VALUE}（2026-10-05 的严重 bug）</h2>
     * <p>旧实现用 {@code Long.MIN_VALUE} 当「从未发生」的哨兵，判定写成
     * {@code now - stamp <= WINDOW}。在 64 位下 {@code now - Long.MIN_VALUE} <b>溢出回绕成负数</b>
     * （实测 {@code 1546120 - (-9223372036854775808) = -9223372036853229688}），而这个负数
     * <b>恒 ≤ 60</b> —— 于是<b>每一台执行舱从加载那一刻起、在从未尝试过任何搬运的情况下</b>都恒定报告
     * 「推不动」，看门狗因此把每一条序列装配任务都判成输出阻塞并挂起（实测 {@code stall=41}，
     * 用户看到的就是「机器满了」，而机械手与置物台全空、日志里一条推料尝试都没有）。</p>
     * <p>{@link #NEVER} 取 −1_000_000（远早于任何存档的 gameTime），相减不会溢出，
     * 且 {@code now - NEVER} 恒大于判定窗口 ⇒ 「从未发生」被正确判成「没推不动」。</p>
     */
    private static final long NEVER = -1_000_000L;
    private long destinationRefusalAt = NEVER;
    /** 某一步「同类型没有任何在线仓能承担」的最近一次观察时刻（同上）。 */
    private long stepOwnerMissingAt = NEVER;
    /** 「推不动」状态的判定窗口（tick）：持续 {@value} tick（3 秒）仍如此 ⇒ 视为持续占用 / 下游满。 */
    private static final int PUSH_STALL_WINDOW_TICKS = 60;
    /**
     * 「被拒收的供料目标」的<b>收尾回收窗口</b>（tick，10 秒）。
     * <p>判定窗口（{@value #PUSH_STALL_WINDOW_TICKS}）只覆盖「故障仍在持续」的情形；
     * 任务结束 / 玩家取消之后输出总线不再尝试，拒收时间戳自然变旧，但那一件<b>还压在置物台上</b>
     * （用户实测：取消后金板回网、中间产物没回去，堵着工位）。
     * 因此这里给一段更长的收尾窗口，让它在任务结束后仍能被收回；超过它则认为坐标可能已被别人复用，不碰。</p>
     */
    private static final int REFUSED_TARGET_RECOVER_TICKS = 200;
    /**
     * <b>每个供料目标各自的最近一次被拒收时刻</b>（2026-10-05 用户实测后新增）。
     *
     * <h2>为什么必须按目标分开（用户原话）</h2>
     * <p><i>「就是堵塞齿轮，并且齿轮只堵一个的时候另外一个也停工了」</i>。</p>
     * <p>旧实现只有<b>一个全仓的</b> {@link #destinationRefusalAt}：
     * 任何<b>一台</b>机器 / 置物台拒收一次，整仓就被判「推不动」⇒
     * {@link #fillInternalForBus} 整段停手（不再备料 / 不再投料），看门狗也把这条任务挂起。
     * 而本仓常常接着<b>多台机器</b>（用户的精密构件仓接两台机械手）：一台手里压着件、
     * 另一台明明空着可以收 —— 却因为「同仓某台拒收」而一起停工。</p>
     *
     * <p>现在按目标分别记；判定「整仓推不动」时用
     * {@link #allKnownTargetsRefusing()}：只有当<b>所有已知目标都在窗口内拒收过</b>
     * （或者一个成功的推送证据都没有）才算真堵。任何一台收下了 ⇒ 整仓继续干活。</p>
     */
    private final Map<BlockPos, Long> refusedTargetAt = new HashMap<>();
    /**
     * <b>最近一次成功推送（投料进机器）的时刻</b> —— 用来证明「还有目标在工作」。
     * <p>为空 / {@code NEVER} = 本仓还从未成功投过料（启动初期），此时不拿它当「有目标可收」的证据。</p>
     */
    private long lastSuccessfulPushAt = NEVER;
    /**
     * <b>最近一次被下游拒收的供料目标坐标</b>（2026-10-05 新增：齿轮 / 废料堵塞的自愈点）。
     *
     * <h2>为什么必须记下来（用户实机：置物台被一件「不该在这一步出现」的件占住）</h2>
     * <p>Create 的置物台一次只放一件。精密构件的机械手步在装配失败时会<b>把废料留在置物台上</b>
     * （用户原话：<i>「如果运气不好装配失败了就是装配出废料了，此时如果说这个废料是小齿轮的话，
     * 那么他就会堵着了既不会把这个小齿轮回流到网络中也不会进行什么其他操作就在那一直卡着」</i>）。
     * 而小齿轮恰好也是后面某一步的投入物 ⇒ 输出总线要把「小齿轮」推上去时，置物台拒收
     * （它手里已经有一件），于是：</p>
     * <ul>
     *     <li>输出总线每 tick 被判 {@code DESTINATION_DOES_NOT_ACCEPT} ⇒ 记下拒收时刻；</li>
     *     <li>看门狗判 {@code OUTPUT_BLOCKED} 并挂起任务，横幅说「机器满了」—— 而机器其实是空的；</li>
     *     <li>玩家点「继续」后照旧推开一次又被拒 ⇒ 每 2 秒再挂一次（用户实测：做到 12 个已挂 4~5 次）。</li>
     * </ul>
     * <p>根治办法只有一个：<b>把那件占住置物台的东西主动收回来</b>（收进本仓 → 回写网络），
     * 而不是只弹横幅。坐标在这里记下，{@link #recoverBlockingTargetItem()} 据此执行回收。</p>
     */
    private BlockPos lastRefusedTarget;
    /** 最近一次被拒收的资源（用于日志与「只回收它、不碰别的」的保守判定）。 */
    @org.jetbrains.annotations.Nullable
    private net.minecraft.world.item.Item lastRefusedItem;

    /**
     * 「推不动」的<b>两种语义</b>（诊断导出 / 看门狗分类用）。
     *
     * <h2>为什么必须分开（2026-10-05 实机取证）</h2>
     * <p>用户下单精密构件 x1，任务在 <b>2 秒内</b>被判成 {@code EXECUTOR_OFFLINE} 并挂起，
     * 横幅说「执行器掉线」，而机械手与置物台<b>完全空着</b>。导出快照证明真相：
     * {@code offlineSteps="-"}（没有任何步骤掉线）、{@code missingMaterials=0}、
     * 三个类别 {@code estimated=5}（料够）—— 唯一成立的判据是
     * {@link #pushStalledOnDestination()}。而它把<b>两种完全不同的原因</b>压进了同一个布尔值：
     * <ul>
     *     <li>{@link #DESTINATION_REFUSED}：下游机器 / 置物台拒收（真正意义上的「机器满」）；</li>
     *     <li>{@link #STEP_OWNER_MISSING}：某一步<b>没有任何在线执行仓认领</b>（缺机器 / 缺样板，
     *     与「满」毫无关系）。</li>
     * </ul>
     * 于是玩家看到的原因文案与实际原因可以完全无关 —— 这正是「我讲错你就修错」的源头。
     * 现在两种原因各自带类型与资源名，看门狗据此给出<b>不同的 reason 与不同的横幅文案</b>。</p>
     */
    public enum StallReason {
        /** 下游机器 / 置物台持续拒收（机器满 / 不接受这一件）。 */
        DESTINATION_REFUSED,
        /** 某一步没有任何在线执行仓认领（缺机器 / 缺样板）。 */
        STEP_OWNER_MISSING;
    }

    /** 最近一次「推不动」的原因与资源名（只读诊断；{@code null} = 从未发生）。 */
    private StallReason stallReason;
    /** 最近一次「推不动」涉及的资源（物品注册名 / {@code fluid:…}；未知为 {@code "-"}）。 */
    private String stallResource = "-";
    /**
     * 最近一次「推不动」的<b>原因签名</b>（{@code 原因|资源}）。用于「原因变化时立刻重报一条」。
     */
    private String stallSignature = "";
    /**
     * 一次性残留回流令牌的有效期（tick）。
     * <p>必须 ≥ 输入总线默认搬运节拍（RS {@code UpgradeContainer.DEFAULT_WORK_TICK_RATE = 9}）并留足余量，
     * 否则边沿令牌会在还没被任何一次搬运消费前就过期 —— 表现就是「停任务后 500 mB 岩浆偶尔不收」。
     */
    private static final int BUS_RESIDUAL_FLUSH_WINDOW_TICKS = 40;
    /** 「禁止回流步骤」阈值表缓存（与类别表同一 {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 重建）。 */
    private Map<String, Integer> disallowThresholdsCache;
    /** 单元样板列表缓存（与类别表同一节拍重建；供输入总线的「本仓还认不认领它」判定使用）。 */
    private List<UnitData> unitsCache;
    /**
     * <b>本仓负责的步</b>缓存（配方 id → 步序集合）：本仓「按步」判定的<b>唯一</b>数据源
     * （认领 / 收 / 推 / 类别拆分 / 备料全走它）。
     *
     * <p><b>为什么不能只看「单元样板自己记的步」</b>：同一个处理器可以被一条配方的多个步骤复用
     * （坚固板的第 2、3 步都是冲压，见 {@code create:sturdy_sheet}）。玩家在那个机器位置上
     * <b>只放一份该类型的单元样板是合法的</b>（用户原话：「我就放了一个样板，但是他两个都是在那里」），
     * 旧实现只认那份样板里记的一个步序，于是「第 3 步做出来的件」在本仓被判成「不是我的」——
     * 先被输入总线过早收回网络，再没有任何机器肯接手，任务永远卡死（用户实测：坚固板做不出来）。</p>
     *
     * <p><b>口径（见 {@link #computeOwnedSteps}）</b>：本仓负责的步 = ① 单元样板自己记的步
     * （旧语义，保留）∪ ② 该样板所属配方里<b>配方类型与本仓样板一致</b>的全部步，
     * 但被<b>另一台（非同集群的）在线执行仓显式放了同样（配方 + 步序）样板</b>的步除外
     * （那种步归那台仓，本仓不越权）。</p>
     */
    private Map<String, Set<Integer>> ownedStepsCache;
    /**
     * 最近被本仓按步判定<b>拒绝</b>过的资源 → 冷却到期 tick。
     *
     * <p>为什么要冷却：被拒绝的件本仓本来就不该收，但若每个节拍都重新判定、重新抱怨，就会出现
     * 「收回 → 立刻再试 → 再拒」的 1 Hz 空转与刷屏（用户实测：{@code step_not_mine} 每秒一条）。
     * <b>只记「拒绝」这一种结论</b>，因此正常流（本仓真正需要的件）绝不会被抑制。</p>
     */
    private final Map<ResourceKey, Long> stepRefusalCooldowns = new HashMap<>();
    /** 按步拒绝的抑制时长（tick，5 秒）：足够打断空转，又短到玩家改配置后能自愈。 */
    private static final int STEP_REFUSAL_COOLDOWN_TICKS = 100;
    /**
     * 上一次备料时「哪台供料目标轮到哪一步」的签名（见 {@link #wantedStepSignature()}）。
     * <p><b>为什么需要它</b>：{@link #stepRefusalCooldowns} 的抑制窗口是 100 tick（5 秒），
     * 而产线的步序大约 0.5~1 秒就切一次 —— 若不在步序变化时作废这份抑制，刚被拒的那份料
     * 会在**它已经被需要**的整个窗口里继续被压住不备料，机械手于是空着手等
     * （用户实测：空手 2~3 秒，以前不到 1 秒）。</p>
     */
    private String lastWantedStepSignature = "";
    /**
     * 与本仓产线相关的关键物品缓存（配方产物 / 过渡件 / 起始原料 ∪ 本仓输入类物品）。
     * <p>{@code null} = <b>判不了</b>（样板没记配方 id，或配方已被数据包移除）—— 此时相关性一律按
     * 「相关」处理，绝不因为「判不出来」而误停本仓的备料、误回流它的残留原料。</p>
     */
    private Set<Item> pipelineItemsCache;

    /**
     * 本仓产线的「目标物品」缓存（各单元样板所属序列装配配方的<b>最终产物 ∪ 过渡件</b>）。
     * <p>{@code null} = <b>判不了</b>（样板没记配方 id / 配方已被移除）—— 此时门控一律放行，
     * 绝不因为判不出来而误停本仓产线。</p>
     * <p><b>为什么要与 {@link #pipelineItemsCache} 分开（用户第 ⑦ 条：「我明明没有下单，
     * 它却莫名发料，发金板让机械手做序列装配」）</b>：{@code pipelineItemsCache} 为了回答
     * 「任务还与本仓相关吗」刻意取<b>超集</b>（把起始原料、输入性产物也算进去，因为 RS 缺料时会为
     * 这些资源起子任务）。但「相关」≠「本仓该开工」：玩家网络里若有一个<b>维持
     * {@code create:golden_sheet} 库存</b>的任务（例如定量保持器），它的目标恰好落在超集里
     * （金板是本仓某步的输入），旧判据于是把整条产线当成「有单在做」→ 备料 → 机械手开始序列装配。
     * 本集合把「该不该开工」收紧到<b>本产线自己的产物 / 在制件</b>，因此
     * <b>没有任何玩家为本产线下单 ⇒ 零取料、零投料、零自动合成</b>。</p>
     */
    private Set<Item> goalItemsCache;

    /**
     * 一个动态类别的内部描述（服务端用）。
     *
     * @param id        稳定 id（见 {@link RsccBusCategory}）
     * @param icon      物品类别的代表性图标物品（非物品类别为 {@code null}）
     * @param fluidIcon 流体类别的代表性图标流体（非流体类别为 {@code null}）
     * @param labelKey  显示名语言键；空串 = 用图标资源的真实名字
     * @param items     该类别的物品过滤项（导出时用它们做模糊匹配）
     * @param fluids    该类别的流体过滤项（导出时用它们做模糊匹配；水量按导出批次轮询分配）
     * @param amount    每批投入量：物品 = 件数，流体 = mB（界面 tooltip 显示用）
     * @param estimated 「预估需求」= 正常情况（不看概率）产出 <b>1 个最终产物</b>所需的量：
     *                  起始原料 = 每批需求量；步骤输入 = 每批需求量 × 配方 loops。
     *                  «TARGET» 策略下界面显示它，并提示「因概率原因实际可能超出」。
     *                  中间产物类别为 0（不显示）。
     * @param filterPrototype <b>「按步过滤」原型</b>（仅中间产物类别非空）：过渡件 + 该步的
     *                  {@code create:sequenced_assembly} 进度组件。为什么必须随类别携带：
     *                  同一配方的不同步骤可以是同一个处理器（坚固板第 2、3 步都是冲压），
     *                  过渡件是<b>同一个物品</b>，只有进度步能区分它们 —— 因此输出总线 / 输入总线的
     *                  过滤项都用这个原型做「按步」匹配（见 {@link #busExportFilters} 与
     *                  {@link #busExportMatches}），而不是裸 {@code ItemResource}。
     *                  非中间产物类别 = {@link ItemStack#EMPTY}（= 按裸物品匹配，既有行为）。
     * @param stepMachine <b>仅中间产物类别</b>非空：该步处理配方的 {@code addRequiredMachines} 给出的第一台机器
     *                  （如「动力冲压器」）。用途是界面「详细配置」的<b>标签行</b>：
     *                  「第 N 步 · 冲压」—— 同一步序下的过渡件往往是同一个物品，只有把机器名写出来
     *                  玩家才能一眼分清身份（用户硬要求：不要靠堆一排物品格来区分）。
     *                  服务端只能取机器物品：{@code getDescriptionForAssembly()} 带 {@code @OnlyIn(CLIENT)}，
     *                  服务端调用会崩，因此这一份「机器名」由客户端用物品悬浮名渲染。
     * @param reuseKey   <b>「语义相同的中间步骤」复用键</b>（空串 = 不适用；见
     *                  {@link RsccBusCategory#reuseKey()} 的说明）。判据 = 步骤类型 + 输入候选集合
     *                  （可能来自不同配方）：同键 ⇒ 用户可以让同一台机器 / 同一条总线服务这两步。
     */
    public record BusCategoryInfo(String id, @org.jetbrains.annotations.Nullable Item icon,
                                  @org.jetbrains.annotations.Nullable Fluid fluidIcon, String labelKey,
                                  List<Item> items, List<Fluid> fluids, long amount, long estimated,
                                  ItemStack filterPrototype,
                                  @org.jetbrains.annotations.Nullable Item stepMachine,
                                  String reuseKey) {
        /** 旧的 10 参构造器（保留，供既有代码编译）：复用键缺省为空串。 */
        public BusCategoryInfo(final String id, @org.jetbrains.annotations.Nullable final Item icon,
                               @org.jetbrains.annotations.Nullable final Fluid fluidIcon,
                               final String labelKey, final List<Item> items, final List<Fluid> fluids,
                               final long amount, final long estimated, final ItemStack filterPrototype,
                               @org.jetbrains.annotations.Nullable final Item stepMachine) {
            this(id, icon, fluidIcon, labelKey, items, fluids, amount, estimated, filterPrototype, stepMachine, "");
        }

        /** 是否是「输入性产物」类别（物品输入或流体输入）。 */
        public boolean isInput() {
            return id != null && (id.startsWith(RsccBusCategory.INPUT_PREFIX)
                || id.startsWith(RsccBusCategory.FLUID_PREFIX));
        }

        /** 是否是「流体输入」类别（与 {@code RsccBusCategory#isFluidInput()} 同一口径）。 */
        public boolean isFluidInput() {
            return id != null && id.startsWith(RsccBusCategory.FLUID_PREFIX);
        }

        /**
         * 是否是「成品 / 废料」类别。
         * <p>本轮新增：成品与废料原先不属于任何类别（界面上看不见、手动模式选不到），
         * 现在由所属配方的 {@code results} 池派生（用户验收标准 #3）。</p>
         */
        public boolean isResult() {
            return id != null && id.startsWith(RsccBusCategory.RESULT_PREFIX);
        }

        /** 是否是「废料」类别（{@code results} 池里除主产物外的概率产出）。 */
        public boolean isScrap() {
            return id != null && id.startsWith(RsccBusCategory.SCRAP_PREFIX);
        }

        /** 是否是「产出侧」类别（成品或废料）；界面上与输入 / 中间产物并列成组。 */
        public boolean isProduct() {
            return isResult() || isScrap();
        }

        /**
         * 是否是「中间产物」类别（含带步序的 {@code intermediate:<step>}）。
         * <p>与 {@code RsccBusCategory#isIntermediate()} 同一口径：本轮把中间产物按归属步骤拆成了
         * 多个独立类别，「步序未知」那一种仍写作不带步序的 {@link RsccBusCategory#INTERMEDIATE}。</p>
         */
        public boolean isIntermediate() {
            return id != null && (id.equals(RsccBusCategory.INTERMEDIATE)
                || id.startsWith(RsccBusCategory.INTERMEDIATE + ":"));
        }

        /**
         * 中间产物类别归属的步骤（0-based；非中间产物 / 步序未知 = -1）。
         * <p>解析规则与 {@link RsccBusCategory#intermediateStep()} <b>完全同一口径</b>：
         * id 现在可能带配方（{@code intermediate:create:track:2}），因此步序取<b>最后</b>一段；
         * 旧的 {@code intermediate:2} 同样落在最后一段上。</p>
         */
        public int step() {
            if (!isIntermediate()) {
                return -1;
            }
            final int last = id.lastIndexOf(':');
            if (last < 0 || last + 1 >= id.length()) {
                return -1;
            }
            try {
                return Integer.parseInt(id.substring(last + 1));
            } catch (NumberFormatException exception) {
                return -1;
            }
        }

        /** 中间产物类别归属的配方 id（{@code create:track}）；不带配方（旧 id / 步序未知）时返回空串。 */
        public String recipe() {
            if (!isIntermediate() || step() < 0) {
                return "";
            }
            final int last = id.lastIndexOf(':');
            final int from = RsccBusCategory.INTERMEDIATE.length() + 1;
            if (last <= from) {
                return ""; // 旧写法 intermediate:<步序>：没有配方段（绝不 substring(begin > end)）
            }
            return id.substring(from, last);
        }

        /** 是否携带「按步过滤」原型（= 勾中它只放行<b>该步</b>的过渡件，而不是同物品的全部步骤）。 */
        public boolean hasStepFilter() {
            return filterPrototype != null && !filterPrototype.isEmpty();
        }
    }

    // ---------- 内部存储（与自动合成仓同一套容量语义） ----------
    /**
     * 内部物品存储：种类无上限、总容量 = 配置槽位数 × 64（与自动合成仓完全一致）。
     * <p><b>非 final</b>：参与机器集群时会被换成「整个集群共用的那一份」（容量 = 台数 × 单机容量）。
     */
    public cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage outputStorage =
        new cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage(
            Config.autocrafterOutputSlots
                * cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage.STACK_LIMIT);
    /**
     * 内部流体存储：种类无上限、总容量 = 配置的流体容量（与自动合成仓完全一致）。
     * <p><b>非 final</b>：参与集群时换成集群共用的那一份。
     */
    public cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage outputTank =
        new cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage(Config.autocrafterFluidCapacity);

    // ---------- 中间产物缓存（网络内共享池：不再有「本仓磁盘槽」） ----------
    /**
     * 物品存储的<b>统一视图</b>：内部存储 + 网络内共享的中间产物缓存池。
     * <p>执行舱里所有「按槽位遍历物品存储」的路径（备料取料的余量回填、喂料、回网、全自动收回、
     * 对外物流能力、诊断统计）都读这一份，因此共享缓存池里的中间产物与内部存储里的没有区别 ——
     * 不会出现「东西进了缓存池，但下一步骤取不到」的死角。</p>
     * <p><b>磁盘槽已移除（用户决定）</b>：磁盘现在插在「中间产物缓存仓」（{@code intermediate_cache}）里，
     * 该仓接入本网络后，网络内<b>所有</b>执行舱共用它的盘（见
     * {@link cretae.cookiewyq.rs_create_compat.support.RsccSharedCache}）。
     * <b>没有缓存仓时</b>池子为空表，本视图 = 只有内部存储，与「插不插盘」之外的既有行为逐字一致。</p>
     */
    private final RsccChamberItemStorage itemStorage = new RsccChamberItemStorage(this::internalItemStorage, this);

    // ---------- 新语义（v4）：执行仓绑定信息 ----------
    /** 本仓绑定的配方类型 id（如 {@code create:pressing}；空 = 未绑定）。 */
    private String recipeType = "";
    /** 本仓的用户自定义名（空 = 显示时回退为坐标）。 */
    private String chamberName = "";

    // ---------- 链：照 Refined Storage 的自动合成仓「串成一条链」 ----------
    /**
     * 链的最大长度（照 RS {@code AutocrafterBlockEntity.MAX_CHAINED_AUTOCRAFTERS = 8}）。
     * 与 RS 一致：走满上限仍未到端点，就<b>退回本台自己</b>（这一段各台分别持有自己的配置），
     * 保证「沿朝向走」必然终止（确定性，绝不递归失控）。
     */
    public static final int MAX_CHAIN_LENGTH = 8;
    /** 链自愈（旧存档配置归位）的节流间隔（tick）。 */
    private static final int CHAIN_SANITIZE_INTERVAL_TICKS = 40;

    /**
     * 链自愈节流计数（仅服务端、仅内存，不进 NBT）。
     * <p>见 {@link #sanitizeRayServer()}：旧存档把「链指向」单独存在 {@code ChainLink} 里，
     * 本轮起链方向改由方块朝向推导，链首可能换人，需要把配置归位到新链首。</p>
     */
    private int chainSanitizeCooldown;

    /** 引擎节流计数（倒计时到 0 才做一次全表扫描）。 */
    private int engineCooldown;
    /** 日志节流计数。 */
    private int logCooldown;
    /** 配方 sequence 长度缓存老化计数。 */
    private int seqCacheAge;
    /** 同种资源的认领冷却（资源 key → 剩余 tick）。 */
    private final Map<ResourceKey, Integer> claimCooldowns = new HashMap<>();
    /** 配方 id → Create 配方 sequence 长度缓存（seqSize）。 */
    private final Map<ResourceLocation, Integer> seqSizeCache = new HashMap<>();

    /**
     * <b>执行舱边界的守恒账本</b>（服务端权威；只记数、绝不搬运 / 生成 / 销毁任何资源）。
     *
     * <h2>为什么需要它（用户第 1 条：坚固板的黑曜石粉与岩浆「又开始乱消耗」）</h2>
     * <p>用户只能看到「网络里某个数字变了」，看不出这一份是谁、往哪个方向搬的。物品侧与流体侧的
     * 备料 / 推料 / 收回是两条代码路径，任一处不对称（取整、批上限、机器侧在飞量）都会表现为
     * 「有时扣有时不扣」。本账本把舱边界上的四类流动逐资源累加，于是守恒不变量
     * {@code 离开网络 − 进入网络 = 留存}（物品按件、流体按 mB 各自独立）可以被逐条核对，
     * 并<b>直接列出不满足的条目</b>（见 {@link RsccFlowLedger#unbalanced()}）。</p>
     * <p><b>只读诊断</b>：任何判定都不读它，因此对既有行为零影响；活量探针走本仓自己的只读访问器。</p>
     */
    private final cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger flowLedger =
        new cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger();

    /** 记账（只读诊断）：本仓把一份物品 / 流体<b>推给了机器</b>（输出总线搬运成功后由总线回调）。 */
    public void recordFlowToMachine(final ResourceKey resource, final long amount) {
        if (resource instanceof final ItemResource item) {
            flowLedger.toMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                .itemKey(item.item()), amount);
        } else if (resource instanceof final FluidResource fluid) {
            flowLedger.toMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                .fluidKey(fluid.fluid()), amount);
        }
    }

    /** 记账（只读诊断）：舱内存量的「活量」探针（物品走统一视图、流体按类型汇总内部罐）。 */
    private long flowLive(final String key) {
        if (key == null) {
            return 0L;
        }
        if (key.startsWith("fluid:")) {
            final String id = key.substring("fluid:".length());
            long total = 0L;
            for (final FluidStack stack : outputTank.getTanksSnapshot()) {
                if (!stack.isEmpty() && id.equals(RsccAssemblyDebug.fluidId(stack.getFluid()))) {
                    total += stack.getAmount();
                }
            }
            return total;
        }
        long total = 0L;
        for (int slot = 0; slot < itemStorage.getSlots(); slot++) {
            final ItemStack stack = itemStorage.getStackInSlot(slot);
            if (!stack.isEmpty() && key.equals(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                .itemKey(stack.getItem()))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 诊断导出用：把守恒账本交出去（只读；{@code /rs_create_compat diag} 用）。 */
    public Map<String, Object> flowLedgerReport() {
        return flowLedger.report();
    }

    // ---------- 运行统计（仅用于限频日志/提示，不持久化） ----------
    private long engineRuns;
    private long fedSinceLog;
    /** 守恒账本审计的节拍间隔（tick）：1 秒一次，与引擎自己的 5 tick 节流解耦。 */
    private static final int LEDGER_AUDIT_INTERVAL_TICKS = 20;
    /** 距离下一次账本审计还剩多少 tick（只用于限频日志，不参与任何判定）。 */
    private int ledgerAuditCooldown = LEDGER_AUDIT_INTERVAL_TICKS;

    // ---------- 旧存档迁移：执行舱的「中间产物缓存磁盘槽」已按用户决定移除 ----------
    /** 迁移标记：本仓是否已处理过老档的 {@code DiskSlot}（写进 NBT，保证「同一份数据只退还一次」）。 */
    private static final String TAG_LEGACY_DISK_MIGRATED = "LegacyDiskMigrated";
    /** 已迁出但尚未退回世界的旧磁盘：随 NBT 落盘，保证「读档后还没落地就被存档带走」也不丢。 */
    private static final String TAG_LEGACY_DISK_PENDING = "LegacyDiskPending";
    /** 是否已迁移过（持久化；见 {@link #readLegacyDiskSlot}）。 */
    private boolean legacyDiskMigrated;
    /** 待退回世界的旧磁盘（服务端 tick 里结算；见 {@link #resolveLegacyDiskServer()}）。 */
    @org.jetbrains.annotations.Nullable
    private ItemStack pendingLegacyDisk;
    /** 全服累计「退回过旧磁盘的执行舱台数」，只用于汇总日志（说明迁移了几台）。 */
    private static long legacyDiskMigratedChambers;

    public SequenceExecutionChamberBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_BLOCK_ENTITY.get(), pos, state,
            new SequenceExecutionChamberNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        if (state.hasProperty(SequenceExecutionChamberBlock.FACING)) {
            this.machineFace = state.getValue(SequenceExecutionChamberBlock.FACING);
        }
        // 默认面配置：机器朝向面 = 原料输入面（保持旧行为），其余面为「无」
        java.util.Arrays.fill(faceModes, FaceMode.NONE);
        faceModes[machineFace.get3DDataValue()] = FaceMode.INPUT;
        // 守恒账本注入「舱内真实存量」探针（只在某资源第一次记账时取一次基线；只读）
        flowLedger.setLiveProbe(this::flowLive);
    }

    public SequenceExecutionChamberNetworkNode getNode() {
        return mainNetworkNode;
    }

    // ---------- 中间产物缓存（网络内共享池）：对外入口 ----------

    /** 本仓的「物品存储统一视图」（内部存储 + 网络内共享缓存池）；所有读写物品的路径都用它。 */
    public RsccChamberItemStorage itemStorage() {
        return itemStorage;
    }

    /** 内部物品存储本体（集群共用对象；容量 / 持久化边界仍以它为准）。 */
    public cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage internalItemStorage() {
        return outputStorage;
    }

    // ---------- RsccSharedCache.PoolSource 实现（只读，绝不改动任何东西） ----------

    /**
     * 本网络内全部「中间产物缓存仓」的磁盘（服务端权威）。
     * <p>只读：本方法只问「池子里现在有什么」，不写、不动任何方块实体。</p>
     */
    @Override
    public List<com.refinedmods.refinedstorage.common.api.storage.SerializableStorage> storages() {
        return cretae.cookiewyq.rs_create_compat.support.RsccSharedCache.storages(
            getLevel(), mainNetworkNode.getNetwork());
    }

    /** 池内容的版本号：换盘 / 写入 / 缓存仓拓扑变化都会变（见 {@link cretae.cookiewyq.rs_create_compat.support.RsccSharedCache}）。 */
    @Override
    public long version() {
        return cretae.cookiewyq.rs_create_compat.support.RsccSharedCache.version();
    }

    public long getEnergyUsage() {
        return 8;
    }

    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /** 方块状态被旋转/替换时同步机器朝向（支持其它模组扳手旋转）。 */
    @Override
    public void setBlockState(final BlockState newBlockState) {
        super.setBlockState(newBlockState);
        if (newBlockState.hasProperty(SequenceExecutionChamberBlock.FACING)) {
            final Direction next = newBlockState.getValue(SequenceExecutionChamberBlock.FACING);
            if (next != machineFace && faceModes[machineFace.get3DDataValue()] == FaceMode.INPUT) {
                // 朝向变了：把「原料输入面」标记一起搬过去，避免旋转后机器侧变成「无」
                faceModes[machineFace.get3DDataValue()] = FaceMode.NONE;
                faceModes[next.get3DDataValue()] = FaceMode.INPUT;
            }
            machineFace = next;
        }
    }

    // ========== 面配置（玩家可逐面设置工作模式） ==========

    /** 该面的工作模式（越界回退 {@link FaceMode#NONE}）。 */
    public FaceMode getFaceMode(final Direction direction) {
        return direction == null ? FaceMode.NONE : faceModes[direction.get3DDataValue()];
    }

    /** 该面的工作模式（按 {@link Direction#get3DDataValue()} 下标）。 */
    public FaceMode getFaceMode(final int ordinal) {
        return ordinal >= 0 && ordinal < faceModes.length ? faceModes[ordinal] : FaceMode.NONE;
    }

    /** 设置某面的工作模式（服务端权威，来自 {@code SetChamberFacePacket}）。 */
    public void setFaceMode(final Direction direction, final FaceMode mode) {
        if (direction == null) {
            return;
        }
        final FaceMode value = mode == null ? FaceMode.NONE : mode;
        if (faceModes[direction.get3DDataValue()] == value) {
            return;
        }
        faceModes[direction.get3DDataValue()] = value;
        markDirtyAndSync();
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " face=" + direction.getName() + "=" + value.key()
                + " faces=[" + faceModeSignature() + "]"
                + " output=" + outputMode.key());
        }
    }

    /** 逐面模式快照（下标 = {@link Direction#get3DDataValue()}，供 S2C 同步）。 */
    public List<Integer> faceModeOrdinals() {
        final List<Integer> modes = new ArrayList<>(faceModes.length);
        for (final FaceMode mode : faceModes) {
            modes.add(mode.ordinal());
        }
        return modes;
    }

    /** 应用 S2C 同步来的面配置（客户端展示用；不落盘）。 */
    public void applyFaceModes(final List<Integer> modes) {
        if (modes == null) {
            return;
        }
        for (int i = 0; i < faceModes.length && i < modes.size(); i++) {
            final Integer ordinal = modes.get(i);
            final FaceMode[] values = FaceMode.values();
            faceModes[i] = ordinal != null && ordinal >= 0 && ordinal < values.length
                ? values[ordinal] : FaceMode.NONE;
        }
    }

    /** 该面是否参与自动交互（非「无」）。 */
    public boolean isFaceActive(final Direction direction) {
        return getFaceMode(direction) != FaceMode.NONE;
    }

    // ========== 输出模式（面输出 / 总线输出） ==========

    /** 本仓的输出模式。 */
    public OutputMode getOutputMode() {
        return outputMode;
    }

    /** 输出模式序号（供 S2C 同步 / NBT）。 */
    public int outputModeOrdinal() {
        return outputMode.ordinal();
    }

    /** 设置输出模式（服务端权威，来自 {@code SetChamberOutputModePacket}）。 */
    public void setOutputMode(final OutputMode mode) {
        final OutputMode value = mode == null ? OutputMode.FACE : mode;
        if (value == outputMode) {
            return;
        }
        outputMode = value;
        markDirtyAndSync();
        // 模式变了：类别可见性 / 归属可能整体变化，立刻归一一次
        busCategoriesCache = null;
        connectedExportersCache = null;
        connectedImportersCache = null;
        normalizeBusOwners();
        // 通知六个相邻的「输出总线」重新检测（它们据此进入 / 退出延长型输出模式）
        notifyAdjacentExporters();
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " output=" + value.key()
                + " faces=[" + faceModeSignature() + "]"
                + " cats=[" + categorySignature() + "]"
                + " cluster=" + clusterSize);
        }
    }

    /**
     * 让「与本仓六向相邻或用 RS 线缆相连」的输出总线立刻重新检测本仓的输出模式。
     * <p><b>本轮收紧了连接判定</b>：不再按「同一张 RS 网络」广播（那会让隔得很远的输出总线也生效），
     * 改为沿线缆 BFS（{@link #reachableExporterPositions()}，只经过线缆、不穿机器、上限
     * {@link #BUS_LINK_MAX_STEPS} 步）逐台通知。线缆连接的输出总线可能离本仓较远、相邻扫描覆盖不到，
     * 但它一定在这条线缆上，因此能被枚举到。</p>
     * <p>只在玩家切换输出模式的瞬间执行（极低频），不做任何周期轮询。</p>
     */
    private void notifyAdjacentExporters() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        for (final BlockPos pos : reachableExporterPositions()) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof RsccExporterExecutorMode exporter) {
                exporter.rscc$refreshExecutorMode();
            }
        }
    }

    /** 应用 S2C 同步来的输出模式（客户端展示用；不落盘）。 */
    public void applyOutputMode(final int ordinal) {
        final OutputMode[] values = OutputMode.values();
        outputMode = ordinal >= 0 && ordinal < values.length ? values[ordinal] : OutputMode.FACE;
    }

    /**
     * 本仓此刻是否算「总线输出（延长型输出）」<b>端点</b>：此时逐面配置整体失效（供输出总线检测用）。
     *
     * <h2>为什么改成链级（用户需求：4 台成链 = 一台，链上任意一处接总线都算数）</h2>
     * <p>用户原话：「它应该是有 4 个执行仓都会被连上 …… 这四台要被识别成【一台】。」
     * 输出总线找归属时只看<b>方块自己那一台</b>（{@code RsccWireLinkSearch} 的两处
     * {@code chamber.isBusOutput()} 判定），而 {@code outputMode} 是<b>逐方块</b>持久化的
     * （{@link #setOutputMode} 只写本台）。于是「链上已经有一台是总线输出」的常见布局
     * （先在单台上设好总线输出，再在旁边扩出几台同配方仓成链）会导致：接在<b>链首以外</b>任何一台上的
     * 输出总线<b>永远找不到归属</b>（{@code linked=- reason=no_chamber reachable=0}），界面上那条总线的
     * 类别条是<b>空的</b> —— 玩家看到的正是「读不到里面所有的单元样板」。</p>
     * <p>因此把「是不是总线输出」按链判定：本台自己设过，<b>或</b>本仓所在链上任意一台设过，
     * 整条链都算总线输出端点 ⇒ 总线接在链上<b>任意一台</b>旁边都能连上，
     * 与「接在链上任意一处 = 属于整条链」（{@link #chainExporterPositions()} /
     * {@link #chainBusOwners()}）口径一致。</p>
     *
     * <h2>为什么不会破坏既有判据 / 不重复计数</h2>
     * <ul>
     *     <li>本方法全工程只有两处消费点，都在<b>线缆搜链</b>里（{@code RsccWireLinkSearch} 的
     *     {@code chamberAt} 与 BFS 计数），<b>不进任何</b>排队 / 名额 / 在制件 / 备料 / 推料判据 ——
     *     因此那些闸门每轮仍只被算一次（口径见 {@link #chainBusOwners()}）。</li>
     *     <li>判定只<b>放宽</b>（原来是「本台是 BUS」的严格子集）：本台自己设过 BUS 时结论一字不变；
     *     不串链时 {@link #chainMembers()} = 本台 ⇒ 与改造前逐字相同（单台仓行为零变化）。</li>
     *     <li>链上成员的仓内存储是同一个对象 / 同一份载荷（相邻 ⇒ 同 {@code RsccMachineCluster}），
     *     因此「连到链上哪一台」取到的料本来就是同一份，不会因为换了归属而丢料或复制。</li>
     * </ul>
     * <p><b>已知代价（明确写出，后续可收口）</b>：放宽后同一台总线若<b>同时</b>够得到同链的两台仓
     * （线缆贴着链走），可达台数会变成 ≥2 ⇒ 按既有规则「归属未确定 ⇒ 停用 + 红条 + 横幅」。
     * 那是 {@code RsccWireLinkSearch} 按「台」计数、不按「链」去重的口径问题（该文件不在本次可改范围），
     * 本次刻意不做「按链代表台」的裁剪 —— 那会让「总线接在非代表台上」直接连不上，反而更差。</p>
     */
    public boolean isBusOutput() {
        return outputMode == OutputMode.BUS || chainHasBusOutputMember();
    }

    /**
     * 只读：本仓所在链（分支，含自身）上是否有<b>任意一台</b>显式设为「总线输出」。
     * <p>唯一事实源是链推导 {@link #chainMembers()}；不串链时链 = 本台，因此单台仓的结论与改造前一致。
     * 本方法不含任何搬运 / 写入，可在线缆搜链里安全调用。</p>
     */
    private boolean chainHasBusOutputMember() {
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            if (member.outputMode == OutputMode.BUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行舱的「自动合成」是否已开启（<b>服务端权威缓存</b>，由 {@link #tickBusScheduler()} 每
     * {@value #BUS_GATE_INTERVAL_TICKS} tick 复查一次）。
     *
     * <h2>为什么必须与「备料」用同一个闸门（用户实测断点的根因）</h2>
     * 「导出侧」（{@link #busExportFilters} 与 {@code RsccChamberExportStrategy}）原先<b>只</b>看
     * {@code busAutoCraftGate}（网络里有没有任何任务），而「进货侧」{@link #fillInternalForBus}
     * 看的是 {@code busAutoCraftGate && busRelevantTaskGate}（有没有<b>与本仓产线相关</b>的任务）。
     * 两者口径不一致时会出现一种<b>结构性死循环</b>：网络里只有<b>别的</b>任务在跑时，
     * 导出侧照旧放行 → 输出总线每 tick 都判一次「仓里没有这种料」（{@code RESOURCE_MISSING/chamber_empty}），
     * 而进货侧被关着 → 这种料<b>永远进不了本仓</b> → 那条导出分支永远判失败、永远重试。
     * 用户实测（latest.log 10:13–10:17）在完全相同的条件下刷出了
     * {@code 2184 次}（未完成黑曜石板）、{@code 4063 次}（未完成精密构件）等纯无效导出尝试，
     * 并且<b>坚固板的第二次冲压因此永远拿不到料</b>（{@code short ... net=0 reason=no_stock}）。
     * <p>因此这里统一为「本仓是否真在跑一条相关任务」：不相关就不动（既不进、也不出），
     * 与 {@link #isBusTaskActive()} 同一个值、同一个语义。</p>
     */
    public boolean isAutoCraftingEnabled() {
        // 第三项是「有人为本产线下单」（用户第 ⑦ 条）：只牵涉本仓的料、却没人下单时，
        // 本仓既不取料也不投料，机械手不会「莫名其妙地」被喂料去做序列装配。
        // 第四项是「本流程每一步的总线配置齐全」（用户第 5 条）：有步骤没配输入 / 输出总线时，
        // 本仓一律不取料 / 不投料 —— 否则物料被抽进机器却推不出去，就是用户说的「白烧原料」。
        return busAutoCraftGate && busRelevantTaskGate && busGoalTaskGate && !busFrozenGate
            && busConfigGate;
    }

    /**
     * 只读：本仓此刻是否处于「<b>挂起冻结</b>」—— 本仓相关的自动合成任务全部被挂起，本仓全面停工
     * （不收集 / 不备料 / 不推料 / 不收回）。
     *
     * <p><b>谁在用</b>：输入总线的传输策略（{@link cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy}）
     * 在入口据此<b>整条短路</b>（否则它每 tick 仍会把机器里的过渡件抄走 / 把输入类收回网络，
     * 那就是「挂起后还在搬料」）；输出总线侧由 {@link #isAutoCraftingEnabled()} 与空清单双重拦截。
     * 服务端权威；客户端恒为 {@code false}。</p>
     */
    public boolean isBusFrozen() {
        return busFrozenGate;
    }

    /**
     * 只读：本仓此刻是否<b>真在跑一条与本仓产线相关的自动合成任务</b>
     * （= {@link #busAutoCraftGate} 且 {@link #busRelevantTaskGate}，即备料 / 推料这一刻是否开闸）。
     *
     * <p><b>谁在用（本轮修正）</b>：它现在只回答「本仓此刻是否真在跑一条相关任务」这个事实
     * （例如诊断与「本仓还在供料吗」的查询）。<b>它不再作为输入总线「要不要收回输入类」的判据</b> ——
     * 那个判据曾是「任务不在跑就放开保护」，副作用是网络空闲时玩家<b>手工</b>放在机器上的输入原料
     * 也会被持续收走（用户指出的回归）。本轮改为一次性边沿令牌，见
     * {@link #claimResidualInputReclaim()}。</p>
     */
    public boolean isBusTaskActive() {
        return busAutoCraftGate && busRelevantTaskGate;
    }

    /**
     * 只读：本仓<b>此刻</b>是否持有一枚「刚结束」的一次性残留回流令牌（供输入总线决定这一轮是否
     * 允许收回「输入类」）。服务端权威；客户端恒为 {@code false}。
     *
     * <p><b>它只回答「有没有」</b>：真正的领取走 {@link #claimResidualInputReclaim()}（会消费令牌），
     * 本方法给诊断 / 日志使用，不改变任何状态。</p>
     */
    public boolean hasResidualInputReclaim() {
        final Level level = getLevel();
        return busResidualInputToken && level != null && level.getGameTime() <= busResidualInputDeadline;
    }

    /**
     * 领取「任务刚结束」的一次性残留回流令牌（<b>消费式</b>：同一枚令牌只会被领取成功一次）。
     *
     * <p><b>用户验收标准 #1</b>：残留输入类（例如已推给注液机、还没被消耗的那 500 mB 岩浆）
     * <b>只在「刚结束那一刻」收一次</b>，之后就恢复正常保护 —— 不允许变成「网络空闲时持续把输入类也收走」。
     * 因此判据不是「任务不在跑」（那是持续条件，会让玩家手工放上去的原料也被收走），而是本节的一次性令牌。</p>
     *
     * <p><b>为什么是「领取」而不是「本 tick 是边沿」</b>：输入总线的搬运由 RS 自己的
     * {@code NetworkNodeTicker} 节流（默认每 9 tick 一次），与本仓调度那一 tick 不同步；
     * 用单 tick 布尔会漏掉几乎全部边沿。令牌在下一次搬运被领取后立即作废，
     * 且超过 {@value #BUS_RESIDUAL_FLUSH_WINDOW_TICKS} tick 自动过期，语义仍是「只收一次」。</p>
     *
     * @return {@code true} = 本次（且仅本次）自动收回允许把「输入类」一起收回网络
     */
    public boolean claimResidualInputReclaim() {
        if (!busResidualInputToken) {
            return false;
        }
        // 无论本次是否在有效期内，令牌都立即作废：它只描述「刚刚结束的那一个边沿」
        busResidualInputToken = false;
        final Level level = getLevel();
        return level != null && level.getGameTime() <= busResidualInputDeadline;
    }

    /**
     * 实时查询本仓所在 RS 网络是否存在进行中的自动合成任务（服务端、逐次查询；调用方请自行节流）。
     * <p>判据：{@code AutocraftingNetworkComponent#getStatuses()} 非空 —— RS 会把已完成的任务从
     * 状态列表里移除，因此列表非空 == 确实有任务在跑。</p>
     */
    private boolean computeAutoCrafting() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return false;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        return autocrafting != null && !autocrafting.getStatuses().isEmpty();
    }

    /** 本仓相关任务状态：没有相关任务。 */
    private static final int RELEVANCE_NONE = 0;
    /** 本仓相关任务状态：至少有一条<b>活跃</b>（未被挂起）的相关任务。 */
    private static final int RELEVANCE_ACTIVE = 1;
    /** 本仓相关任务状态：相关任务存在，但<b>全部被挂起</b>（→ 本仓冻结）。 */
    private static final int RELEVANCE_SUSPENDED_ONLY = 2;

    /**
     * 只读：本仓所在网络里此刻<b>相关任务</b>的状态（活跃 / 全被挂起 / 没有）—— 一次遍历同时回答
     * 「要不要开闸」与「要不要冻结」两件事（本轮新增，服务端、逐次查询，调用方节流）。
     *
     * <p><b>为什么要区分「活跃」与「被挂起」</b>：被挂起的任务<b>仍在</b> {@code getStatuses()} 里，
     * 旧实现因此把它当成「相关任务在跑」→ 本仓继续备料 / 推料 / 收集 → 机器继续产 → 成品堆进网络却
     * 永远不被任务截收（任务不 step）→ 用户实测的「挂起之后它好像还是在处理 / 不取消就一直在产」。
     * 现在：相关任务<b>全被挂起</b> ⇒ {@link #RELEVANCE_SUSPENDED_ONLY} ⇒ {@link #busFrozenGate}
     * 为真 ⇒ 本仓全面停工（含备料侧与步骤专用投入物），且<b>不做</b>「任务结束」的残留回流。</p>
     *
     * <p><b>相关性判据（只读、保守）</b>：与旧实现逐字一致 —— 任务的目标资源或它在处理的任一资源
     * 落在 {@link #pipelineItemsCache}（本仓配方产物 / 过渡件 / 起始原料 ∪ 本仓输入类物品）里即为相关；
     * 缓存为 {@code null}（判不了）时一律按 {@link #RELEVANCE_ACTIVE} 处理，绝不误停 / 误冻结本仓。</p>
     */
    private int relevantTaskState() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return RELEVANCE_NONE; // 客户端不参与（本机是服务端权威）
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return RELEVANCE_NONE;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return RELEVANCE_NONE;
        }
        final List<TaskStatus> statuses = autocrafting.getStatuses();
        if (statuses.isEmpty()) {
            return RELEVANCE_NONE; // 一条任务都没有 → 肯定没有相关任务
        }
        final Set<Item> related = pipelineItemsCache;
        if (related == null) {
            return RELEVANCE_ACTIVE; // 判不了 → 保守「有活跃相关任务」，绝不误停本仓产线
        }
        final Set<Fluid> relatedFluids = inputCategoryFluids();
        boolean sawSuspended = false;
        for (final TaskStatus status : statuses) {
            if (!isRelatedResource(status.info().resource(), related, relatedFluids)
                && !hasRelatedItem(status, related, relatedFluids)) {
                continue;
            }
            if (AssemblyWatchdog.isSuspended(status.info().id().id())) {
                sawSuspended = true; // 相关但被挂起：先记下，继续看有没有活跃的
                continue;
            }
            return RELEVANCE_ACTIVE; // 只要有一条相关的活跃任务，本仓就照常供料
        }
        return sawSuspended ? RELEVANCE_SUSPENDED_ONLY : RELEVANCE_NONE;
    }

    /** 该任务正在处理的资源里是否有与本仓相关的（{@link #relevantTaskState()} 的保守补充判据）。 */
    private static boolean hasRelatedItem(final TaskStatus status, final Set<Item> related,
                                          final Set<Fluid> relatedFluids) {
        for (final TaskStatus.Item item : status.items()) {
            if (isRelatedResource(item.resource(), related, relatedFluids)) {
                return true;
            }
        }
        return false;
    }

    /** 某个 RS 资源是否落在「本仓产线相关物品 / 流体」里（非物品 / 非流体资源一律不算）。 */
    private static boolean isRelatedResource(final ResourceKey resource, final Set<Item> items,
                                             final Set<Fluid> fluids) {
        return (resource instanceof final ItemResource item && items.contains(item.item()))
            || (resource instanceof final FluidResource fluid && fluids.contains(fluid.fluid()));
    }

    /**
     * 只读：拼出「与本仓产线相关的关键物品」集合 ——
     * 本仓每份单元样板所属那条序列装配配方的<b>全部最终产物</b>、它的<b>过渡件</b>、它的<b>起始原料</b>，
     * 再并上本仓的<b>输入类物品</b>。
     *
     * <p><b>为什么是这些</b>：相关性判定（{@link #hasRelevantSequenceTask()}）面对的是 RS 的任务目标资源 ——
     * 启动一条序列装配自动合成时，任务目标就是<b>配方产物</b>；若缺料，RS 还会为<b>起始原料 / 输入原料</b>
     * 起子任务（它们是输入总线上常见的那些资源），因此把这四类都算「相关」才是安全的超集。</p>
     *
     * <p><b>判不了就返回 {@code null}</b>：只有当<b>一份样板都带不出配方</b>（都是 v4 的「配方类型」样板 /
     * 配方被数据包移除）时才算「判不了」—— 此时调用方按「相关」处理（= 保持既有行为），绝不靠猜。
     * 只解不出的那几份样板则<b>跳过</b>：它们本来也参与不了序列装配链（认领过渡件要求样板记了配方 id），
     * 因此不会让集合「缺一块」而误判。</p>
     */
    @org.jetbrains.annotations.Nullable
    private Set<Item> computePipelineItems(final Level level) {
        final Set<Item> items = new LinkedHashSet<>();
        boolean resolvedAny = false;
        for (final UnitData unit : unitsForExport()) {
            final SequencedAssemblyRecipe recipe = directAssemblyOf(level, unit);
            if (recipe == null) {
                continue; // 该样板没记配方 id / 配方已被移除：跳过（它本来也认领不了过渡件）
            }
            resolvedAny = true;
            final ItemStack transitional = recipe.getTransitionalItem();
            if (!transitional.isEmpty()) {
                items.add(transitional.getItem());
            }
            for (final ProcessingOutput output : recipe.resultPool) {
                final ItemStack stack = output.getStack();
                if (!stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
            for (final ItemStack ingredient : recipe.getIngredient().getItems()) {
                if (!ingredient.isEmpty()) {
                    items.add(ingredient.getItem());
                }
            }
        }
        items.addAll(inputCategoryItems());
        return resolvedAny || !items.isEmpty() ? items : null; // 一点线索都没有 = 判不了
    }

    /**
     * 只读：本仓每条单元样板所属序列装配配方的<b>最终产物 ∪ 过渡件</b>（见 {@link #goalItemsCache}）。
     * <p>{@code null} = 判不了（一份样板都带不出配方）⇒ 门控按「有单」放行（保守，绝不误停产线）。</p>
     */
    @org.jetbrains.annotations.Nullable
    private Set<Item> computeGoalItems(final Level level) {
        final Set<Item> items = new LinkedHashSet<>();
        boolean resolvedAny = false;
        for (final UnitData unit : unitsForExport()) {
            final SequencedAssemblyRecipe recipe = directAssemblyOf(level, unit);
            if (recipe == null) {
                continue;
            }
            resolvedAny = true;
            final ItemStack transitional = recipe.getTransitionalItem();
            if (!transitional.isEmpty()) {
                items.add(transitional.getItem());
            }
            for (final ProcessingOutput output : recipe.resultPool) {
                final ItemStack stack = output.getStack();
                if (!stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
        }
        if (!resolvedAny) {
            return null; // 判不了：调用方退回 conservativeGoalItems（保守产物集合，绝不放宽）
        }
        // <b>本轮修正（A/C 组：无单却开工 + 凭空生成金板）</b>：把「本仓产线的投入物」
        // （输入类类别 ∪ 起步原料）从目标集合里<b>剔掉</b>。
        //
        // 为什么必须剔：同一个执行舱的样板库里常常同时放着好几条配方（用户原话「我的样板库里面放了
        // 两个样板，这两个样板用到同一个执行舱对应的机器」），而<b>上一条配方的产物恰恰是下一条配方的
        // 投入物</b>（精密构件 = 金板 → 未完成精密构件 → 精密构件，金板是它的 ingredient）。
        // 旧实现把「金板」也算成本产线的目标（因为它是本仓某条样板的产物），于是只要网络里有<b>任何</b>
        // 一条以金板为目标的任务（典型：定量保持器维持金板库存，用户原话「我后台正在挂着金板」），
        // {@link #hasGoalTask()} 就返回真 ⇒ 整条精密构件产线在<b>无人为本产线下单</b>时开工
        //（用户实测「金板自动触发精密构件装配」）；而开工后 {@link #requestMissingViaAutocraft}
        // 又会向 RS 请求补料，于是网络里「凭空多出一个金板」（其实是 RS 按配方现做，但玩家没下过这条单）。
        // 剔掉投入物之后：以「投入物」为目标的任务只被当作<补给>，绝不再当作「有人为本产线下单」；
        // 而<b>过渡件（在制件）与最终产物绝不在投入物之列</b>，因此真正的在制订单一条都不会被误剔。
        final Set<Item> inputSide = new LinkedHashSet<>(inputCategoryItems());
        inputSide.addAll(startIngredients());
        items.removeAll(inputSide);
        return items;
    }

    /**
     * 只读：{@link #goalItemsCache} 判不出来时的<b>保守产物集合</b>（用户第 ③ 条的兜底）。
     *
     * <p><b>为什么不能「判不出来就放行」</b>：{@link #hasGoalTask()} 是「有人为本产线下单」的唯一判据，
     * 它一旦在判不出来时返回 {@code true}，就等于「网络里任何任务都算有单」—— 别人在做本产线的
     * <b>任意一件相关物品</b>（例如定量保持器维持金板库存）都会让整条产线开工，用户实测的
     * 「金板自动莫名其妙触发精密构件」正是这条路径。</p>
     *
     * <p><b>口径</b>：从「本仓产线相关物品」这个<b>超集</b>（{@link #pipelineItemsCache}：产物 ∪ 过渡件
     * ∪ 起步原料 ∪ 本仓输入类）里剔掉<b>本仓输入类</b>与<b>起步原料</b>，剩下的只能是「产物 / 在制件」。
     * 两者任意一个判不出来（{@code null}）时返回<b>空集</b>：宁可让本条产线不跑，也绝不误跑 ——
     * 而那种「连配方都解不出」的仓本来就产不出东西（类别表 / 认领表同样为空），因此没有任何功能损失。</p>
     *
     * <p>只读，绝不搬运 / 销毁任何资源。</p>
     */
    private Set<Item> conservativeGoalItems() {
        final Set<Item> related = pipelineItemsCache;
        if (related == null) {
            return Set.of();
        }
        final Set<Item> result = new LinkedHashSet<>(related);
        result.removeAll(inputCategoryItems());
        result.removeAll(startIngredients());
        return result;
    }

    /**
     * 只读：网络里此刻是否存在<b>以本仓产线目标（产物 / 在制件）为合成对象</b>的活跃任务。
     *
     * <p><b>用户第 ⑦ 条的落点</b>：这是「本仓该不该开工」的唯一判据 ——
     * 与 {@link #relevantTaskState()} 的<b>相关性</b>（超集，含起始原料 / 输入性产物）分开，
     * 因为「相关」只说明有任务牵涉本仓的料，不说明<b>有人为本产线下单</b>。
     * 被挂起的任务不算「有单」（挂起 ≠ 在做），因此这里逐条排除
     * {@link AssemblyWatchdog#isSuspended}。</p>
     */
    private boolean hasGoalTask() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return false;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return false;
        }
        Set<Item> goals = goalItemsCache;
        if (goals == null) {
            // <b>本轮修正（用户第 ③ 条：没有任何玩家下单时绝不取料 / 投料 / 自动合成）</b>：
            // 旧实现在「产物集合判不出来」时直接 return true —— 那等于「网络里的任何任务都算有单」，
            // 于是只要别人在动【本产线相关】的任何一件东西（例如定量保持器在维持金板库存），
            // 整条精密构件产线就会开工 —— 用户原话「金板自动莫名其妙触发精密构件」正是这条路径。
            // 现在退回<b>保守</b>产物集合（相关物品超集里剔掉输入类与起步原料，见
            // {@link #conservativeGoalItems}）：判不出来也绝不放宽，宁可不跑也不误跑。
            goals = conservativeGoalItems();
        }
        if (goals.isEmpty()) {
            return false;
        }
        for (final TaskStatus status : autocrafting.getStatuses()) {
            if (!(status.info().resource() instanceof final ItemResource item) || !goals.contains(item.item())) {
                continue;
            }
            // <b>用户第 ⑤ 条加固（本轮）：投入物 / 起步原料绝不是「有人为本产线下单」。</b>
            // 它们出现在目标集合里只可能是「别人在为本产线<补给>」（典型：定量保持器在维持金板库存）。
            // 无论 goals 来自 {@link #computeGoalItems}（已剔过一次）还是保守回退
            // {@link #conservativeGoalItems}（其输入来自 {@code pipelineItemsCache}，里面含配方的
            // {@code ingredient}），这里最后再断言一次：只有「产物 / 在制件」才算有单。
            // 这样即使某轮缓存把「起步原料」漏进了目标集合（配方解析失败 / 缓存尚未重建），
            // 「别人维持金板库存 ⇒ 精密构件产线莫名开工」这条路径也不复存在。
            if (isStartIngredient(item.item()) || isInputMaterial(item.item())) {
                continue;
            }
            if (!AssemblyWatchdog.isSuspended(status.info().id().id())) {
                return true; // 有人在为本产线的产物 / 在制件下单，且该任务活跃
            }
        }
        return false; // 没有任何为本产线下达的活跃订单 ⇒ 本仓绝不取料 / 投料 / 自动合成
    }

    /**
     * 本仓当前<b>有活跃订单</b>的那几条配方 id（{@link #orderedRecipes()} 的判据与 {@link #hasGoalTask()} 同源）。
     *
     * <h2>为什么必须按配方分（2026-10-05 用户实测的严重 bug）</h2>
     * <p>用户原话：<i>「我并没有下单列车轨道，他给我推掉了……然后他在那里开始装这个列车轨道」</i>。</p>
     * <p>{@link #hasGoalTask()} 是<b>整个执行舱一把闸</b>：只要「本仓所负责的任意一条配方」有活跃订单，
     * 整仓放行。而用户按自己的需求<b>把列车轨道与精密构件的单元样板放进了同一个执行舱</b>
     * （两条配方都是 {@code create:deploying}，只能共用一台机械手）。于是：
     * <ol>
     *     <li>他为「精密构件」下单 ⇒ 整仓闸门开；</li>
     *     <li>仓里还留着上一次列车轨道任务的在制件（{@code create:incomplete_track}）；</li>
     *     <li>本仓「负责」{@code track#0} / {@code track#1}，于是<b>继续做没人下单的列车轨道</b>；</li>
     *     <li>列车轨道的输入类别被一并申请补料（{@code minecraft:stone_slab} 等），把工位与网络都带偏。</li>
     * </ol>
     *
     * <p>修法不是「禁止一仓多配方」（那是用户的正当用法），而是把闸门从「整仓」细化为<b>按配方</b>：
     * 只有「本仓负责 且 此刻真的有以该配方产物 / 在制件为目标的活跃任务」的配方才准开件 / 继续。</p>
     *
     * <p>无订单 / 判不出来 ⇒ 空集合 ⇒ 本仓对任何配方都不开件（保守，绝不误跑）。</p>
     */
    private Set<String> orderedRecipes() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return Set.of();
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return Set.of();
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return Set.of();
        }
        // 本仓每条配方「有人下单时会被点到的那些物品」= 过渡件 + 产物（与 hasGoalTask 同一口径：
        // 投入物 / 起步原料绝不算「有单」，否则定量保持器维持库存就会被当成订单）。
        final Map<String, Set<Item>> byRecipe = new LinkedHashMap<>();
        for (final UnitData unit : unitsForExport()) {
            final String recipeId = unit.recipe();
            if (recipeId == null || recipeId.isEmpty()) {
                continue;
            }
            final SequencedAssemblyRecipe recipe = directAssemblyOf(level, unit);
            if (recipe == null) {
                continue;
            }
            final Set<Item> targets = byRecipe.computeIfAbsent(recipeId, key -> new LinkedHashSet<>());
            final ItemStack transitional = recipe.getTransitionalItem();
            if (!transitional.isEmpty()) {
                targets.add(transitional.getItem());
            }
            for (final ProcessingOutput output : recipe.resultPool) {
                final ItemStack stack = output.getStack();
                if (!stack.isEmpty()) {
                    targets.add(stack.getItem());
                }
            }
        }
        if (byRecipe.isEmpty()) {
            return Set.of();
        }
        final Set<String> ordered = new LinkedHashSet<>();
        for (final TaskStatus status : autocrafting.getStatuses()) {
            // 挂起 ≠ 在做（与 hasGoalTask 同一口径）
            if (AssemblyWatchdog.isSuspended(status.info().id().id())) {
                continue;
            }
            for (final Map.Entry<String, Set<Item>> entry : byRecipe.entrySet()) {
                if (ordered.contains(entry.getKey())) {
                    continue;
                }
                if (taskTouches(status, entry.getValue())) {
                    ordered.add(entry.getKey());
                }
            }
        }
        return ordered;
    }

    /** 该任务的目标资源、或其正在处理的任一物品，是否落在给定集合里。 */
    private static boolean taskTouches(final TaskStatus status, final Set<Item> targets) {
        if (status.info().resource() instanceof final ItemResource item && targets.contains(item.item())) {
            return true;
        }
        for (final TaskStatus.Item item : status.items()) {
            if (item.resource() instanceof final ItemResource resource && targets.contains(resource.item())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读：这条配方此刻<b>有活跃订单</b>吗（{@link #orderedRecipes()} 的成员判定）。
     * <p>缓存按 tick 复用：{@link #orderedRecipes()} 一次遍历就能回答所有配方，逐步骤反复问时不必重复扫描。</p>
     */
    private boolean recipeOrdered(final String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) {
            return false;
        }
        final Set<String> ordered = orderedRecipesCache;
        if (ordered == null) {
            // 还没算过（门控尚未复查）⇒ <b>不作判断</b>，交给整仓闸门（busGoalTaskGate）去拦。
            // 绝不能在这里返回 false：那会在门控首次复查之前把整仓停掉（首帧无备料的回归）。
            return true;
        }
        return ordered.contains(recipeId);
    }

    // ========== 输出总线绑定（动态类别 + 多选 + 共享均分；服务端权威） ==========

    /**
     * 本仓当前对输出总线可见的<b>动态类别列表</b>（有序）：
     * <ol>
     *     <li>每一种「输入原料」各成一类（id = {@code input:} + 物品注册名），按物品注册名升序；</li>
     *     <li>每一种「流体输入」各成一类（id = {@code fluid:} + 流体注册名），按流体注册名升序；</li>
     *     <li>「中间产物」<b>按归属步骤各成一类</b>（id = {@code intermediate:<步序>}，步序未知时用
     *     {@code intermediate}），步序升序排在最后，<b>名称与图标用过渡件物品自己的</b>。
     *     为什么按步拆：中间产物有步骤区分，界面必须能标注「第几步」并能按步整选（用户硬要求）。</li>
     * </ol>
     * 判定来源是<b>本仓当前绑定的单元样板 + 它所属的那一条 Create 序列装配配方</b>
     * （见 {@link #computeBusCategories()}），因此附属模组把一个步骤消耗的输入物 / 输入流体扩展到
     * 2 种及以上时会自动多出类别，无需改代码。
     * <p><b>类别数量不设上限</b>：列表长度完全由样板与配方决定，界面靠横向滚动翻页容纳。</p>
     * <p>列表每 {@link #BUS_SCHEDULE_INTERVAL_TICKS} tick 重建一次（数据包重载可自愈）。</p>
     */
    public List<BusCategoryInfo> busCategories() {
        if (busCategoriesCache == null) {
            busCategoriesCache = computeBusCategories();
        }
        return busCategoriesCache;
    }

    /** 本仓全部「输入性产物」类别 id（物品输入 + 流体输入；输出总线「没显式选过」时默认全选这些）。 */
    public List<String> inputCategoryIds() {
        final List<String> ids = new ArrayList<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isInput()) {
                ids.add(info.id());
            }
        }
        return ids;
    }

    /**
     * 输出总线「没显式选过」时的默认导出类别 = 全部「输入性产物」+ 中间产物（过渡件）。
     * <p>为什么把中间产物也算进默认：序列装配的链要靠「过渡件继续流向下一步骤的机器」才能走下去，
     * 而总线输出模式下的唯一通路就是本仓先把网络里的过渡件吸进内部存储、再由输出总线推给机器。
     * 少了这一项，默认配置下链在第一步之后必断（用户验收标准 #3）。</p>
     */
    public List<String> defaultExportCategoryIds() {
        final List<String> ids = new ArrayList<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isInput() || info.isIntermediate()) {
                ids.add(info.id());
            }
        }
        return ids;
    }

    /**
     * 某个物品是否属于本仓的「输入性产物」类别（= 用户所说的「输入时原料」）。
     *
     * <p><b>为什么要这个查询（用户验收标准 #2.1）</b>：总线输出模式下，输出总线的单次搬运量取自
     * RS 自己的配额（默认 1，但装了堆叠升级会 &gt; 1）。输入类原料必须<b>一次一份</b>，
     * 否则单台机械手 / 单台机器会被一次塞满而堵塞。因此 {@code RsccChamberExportStrategy} 需要
     * 「这个物品是不是输入类原料」这一判据，把它的单次搬运量夹到 1 件。
     * 判据与 {@link #busCategories()} 同源（同一次缓存的类别表），不会出现两套口径。</p>
     */
    public boolean isInputMaterial(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isInput() && info.items().contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读：某个物品是否属于本仓某条配方的「<b>废料</b>」类别（{@code scrap:} 前缀，
     * 见 {@link RsccBusCategory#SCRAP_PREFIX}）—— 即 {@code create:sequenced_assembly} 配方
     * {@code results} 池里除主产物以外的概率产出（典型 {@code create:cogwheel}）。
     *
     * <p><b>为什么需要它（B 组：自动模式下废料必须统一收回）</b>：一个物品常常<b>既</b>是某步的投入物、
     * <b>又</b>是同一条配方的废料，因此它同时落在「输入类」与「废料」两个类别里。收回侧对「输入类」
     * 平时一律保护（避免与备料侧来回搬运），于是这份废料在仓里<b>永远不会被收回</b> —— 界面上废料类别
     * 明明显示「已收回」，实际却一份都没动（用户原话：「你这显示『收回齿轮』啊，你为什么又不收回那个
     * 齿轮？」）。有了这个判据，收回侧就能把「此刻没有任何工位要它的废料」单独放行，
     * 而「还在等它开/推进某一步」的那一份照旧受保护。判据与 {@link #busCategories()} 同源，只有一套口径。</p>
     */
    public boolean isScrapItem(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (info.id() != null && info.id().startsWith(RsccBusCategory.SCRAP_PREFIX)
                && info.items().contains(item)) {
                return true;
            }
        }
        return false;
    }

    /** 某种流体是否属于本仓的「流体输入」类别（总线输出模式下同样一次一份）。 */
    public boolean isInputFluid(@org.jetbrains.annotations.Nullable final Fluid fluid) {
        if (fluid == null) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isInput() && info.fluids().contains(fluid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：这份物品是不是本仓某一步的「步骤专用投入物」（机械手手里那件）。
     *
     * <h2>为什么把它公开给输出总线（用户第 2 条：齿轮被推到置物台然后没有消费者）</h2>
     * <p>「步骤专用投入物」与「起步原料 / 过渡件 / 流体」最本质的区别是：<b>它只由机械手（Create 的
     * deploying 步）从自己的手里应用到工位上</b>；置物台 / 传输带只是承载在制件的地方，把这份料推到
     * 台面上它<b>永远不会被消耗</b>（既不该被推出去，推出去也只能等卡住闸门收回）。
     * 因此推料侧必须能把「这一份是步骤专用投入物」这件事问出来，并用
     * {@link #consumesStepExtraAt(BlockPos)} 确认目标机器<b>真的是它的消费者</b>。
     * 与内部的「步骤专用投入物」表同源（同一次缓存），不会出现两套口径。</p>
     */
    public boolean isStepExtraInputItem(@org.jetbrains.annotations.Nullable final Item item) {
        return item != null && isStepExtraInput(item);
    }

    /**
     * <b>只读</b>：这一格机器是不是「步骤专用投入物」的<b>真正消费者</b>（= 机械手）。
     *
     * <h2>为什么必须有这道推料侧闸门（用户第 2 条：齿轮被推到置物台 / 工位上然后没有消费者）</h2>
     * <p>Create 的 {@code DeployerItemHandler} 只认<b>机械手自己的手位</b>：只有推进机械手，
     * 它才会在该步生效时把手里那件应用到工位上。而<b>置物台 / 传输带</b>上摆放的「步骤专用投入物」
     * 没有任何消费者 —— 机械手不会去台面上取料（它只用手里的那一件）。旧实现只看
     * 「目标机器此刻这一步要不要它」，而置物台上的在制件让它同样回答「要」，
     * 于是输出总线会把齿轮推到<b>台面</b>上，从此永久卡在那里（用户实测「齿轮堵在置物台、只能手动拿走」）。</p>
     * <p>判据只有一份实现：{@code target} 是 Create 的机械手方块（{@link #isDeployer}）。判不出来
     * （非机械手）一律 {@code false} —— 对「步骤专用投入物」这条闸门而言，「不是机械手」= 没有消费者，
     * 因此不推（这一份留在本仓，等机械手那一侧的总线来取，绝不销毁）。只读。</p>
     */
    public boolean consumesStepExtraAt(@org.jetbrains.annotations.Nullable final BlockPos target) {
        final Level level = getLevel();
        return level != null && !level.isClientSide() && isDeployer(level, target);
    }

    // ---------- 「步骤专用投入物」：机械手手里那件「此刻唯一该拿的东西」 ----------

    /**
     * <b>只读</b>：本仓此刻还该不该把这份「输入性产物」推给机器（{@code true} = 放行，与旧行为一致）。
     *
     * <h2>为什么需要它（用户实测最严重的问题）</h2>
     * Create 的 {@code create:deploying} 步骤要求机械手<b>手里拿着</b>那一步的投入物
     * （未完成精密构件三步分别是齿轮 / 大齿轮 / 铁粒）。这三件东西在本仓是<b>三个彼此独立的
     * `input:` 类别</b>（见 {@link #computeBusCategories()}），互相之间没有顺序信息 —— 于是输出总线
     * 只会按清单顺序把第一件（齿轮）一直往里推。而 Create 的 {@code DeployerItemHandler#insertItem}
     * 对「手里已经有<b>不同</b>物品」的情况<b>一律拒收</b>（源码：{@code if (!ItemStack.isSameItemSameComponents(held, stack)) return stack;}），
     * 因此下一步骤要的大齿轮永远进不去 ⇒ 装配永久停在第 1 步（用户实测：置物台上的未完成精密构件
     * 冻在 {@code progress=1/15, step=1}、「齿轮一直输入并叠加破十」、「第一次装配后停止」）。
     *
     * <h2>判据（只按步骤，不看方块 / 坐标）</h2>
     * <ol>
     *     <li>这份物品<b>不是</b>「步骤专用投入物」（配方主原料、流体输入、成品 / 废料……）
     *     → 一律放行（旧行为逐字不变）；</li>
     *     <li>判得出待加工件的（配方, 单循环步序）<b>且那一步有已知的投入物</b> → <b>只放行那一步要的</b>
     *     （<b>断言</b>：推出的物品必须与这台机器当前这一步所需的投入物完全一致，不一致绝不推）；
     *     其余步骤的投入物一律不推（等步进到它时自然放行）；</li>
     *     <li>判不出待加工件（本机容器与它正在加工的那一格上都没有在制件与起步原料、或判不出配方步数）
     *     → <b>机械手一律不推</b>（它的加工对象就在它朝向的 2 格外的容器里，那里空着 = 它眼下没有任何
     *     东西可加工；提前塞进手里一旦塞错一步，Create 的 {@code DeployerItemHandler} 会永久拒收正确的
     *     那件 → 整条线卡死。用户硬要求「推错 = 绝不发生」）。
     *     其余机器形态则退到<b>全仓并集</b>兜底（{@link #inputMaterialWantedNow(Item)}），保持既有宽松口径
     *     —— 附属模组的自定义步可能把额外投入物收进自己的槽位，不能因为判不出来就把它饿死。
     *     这条严格化<b>自愈</b>：置物台一拿到料（起步原料或上一件加工完），步序立刻可判，供料随即恢复；</li>
     *     <li>那一步<b>没有任何已知投入物</b>（例如冲压只吃过渡件）→ 本判据<b>不表态</b>（放行）：
     *     不该由「没有投入物」推断出「什么都不能喂」，否则会误伤别的产线。</li>
     * </ol>
     * <p><b>待加工件从哪来</b>：本机自己的容器，以及<b>机械手实际在加工的那一格</b>（它朝向的 2 格外，
     * 通常是置物台）上的过渡件进度组件（配方 id + 进度步）—— 进度步就是 Create「下一步要用第几个步骤
     * 处理」的权威值（与 {@link SequenceMaterialGuard#judgeStep} 同一口径，对循环数取模）；
     * 还没有在制件时看那一格上是不是「本仓某条配方的起步原料」（= 正要起第 0 步）。
     * 解析本体只有一处（{@link #pendingStepOn(BlockPos)}），与收回 / 备料侧的按步判定共用同一份
     * 「本仓负责的步」表，因此不会互相打架。</p>
     * <p><b>绝不搬运任何资源</b>：全程只读（能力查询 + 配方查询），不放行只是「这一 tick 不推」。</p>
     */
    public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final BlockPos target,
                                          @org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return true;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return true; // 客户端不参与搬运
        }
        if (!isStepExtraInput(item)) {
            return true; // 不是「步骤专用投入物」：保持既有行为（主原料 / 流体输入照常喂）
        }
        // 按「本总线面对的那一台机器」取待加工件（多台机器各处于不同步骤时，靠后那台也要拿到自己那件）
        final PendingStep pending = target == null ? pendingStepOnTargets() : pendingStepOn(target);
        if (pending == null) {
            if (isDeployer(level, target)) {
                // <b>机械手：判不出 = 它「眼下没有任何东西可加工」</b>（它的加工对象就在它朝向的 2 格外的
                // 容器里，那一格既没有在制件、也没有本仓配方的起步原料，见 {@link #operandStepOf}）。
                // 这时绝不能把步骤专用投入物「先塞进手里」：手里一旦被塞进<b>别的那一步</b>的件，
                // Create 的 {@code DeployerItemHandler} 就会永久拒收正确的那一件 → 整条线卡死，
                // 且只能靠玩家手动抠出来（用户实测原话：「上面那个拿的却是大齿轮，直接就是卡住了」）。
                // 等置物台 / 传送带拿到料（在制件或起步原料），pending 立刻可判，闸门随即自愈 ——
                // 因此这条严格化既不丢料也不会断供，只是「不许提前塞」。
                return false;
            }
            // 其余机器形态：判不出就不表态（保持既有宽松口径，见 {@link #inputMaterialWantedNow(Item)}）——
            // 附属模组的自定义装配步可能把额外投入物收进自己的槽位，那种机器无法用「2 格外的操作对象」
            // 反推步序，不能因为判不出来就把它整条线饿死。
            return inputMaterialWantedNow(item);
        }
        final Set<Item> wanted = stepExtraInputsOfStep(pending);
        if (wanted.isEmpty()) {
            return true; // 这一步没有已知投入物：本判据不表态（绝不据此断供）
        }
        // <b>卡住闸门（用户第 2 条：齿轮堵在置物台）</b>：这一步确实「要它」—— 但若这份「需要」已经连续
        // {@value #STATION_STUCK_TICKS} tick 没有任何推进（步序不动、件也没被消耗），那就不是「正在用它」
        // 而是<b>卡住了</b>：推料侧不再推（推了也进不去），收回侧照常收回（见
        // {@code RsccChamberImportStrategy#autoAcceptsItem} 的例外 ①）。判定的唯一实现见
        // {@link #stationStuckOn(BlockPos, Item, PendingStep)}。
        if (wanted.contains(item) && stationStuckOn(target, item, pending)) {
            return false;
        }
        return wanted.contains(item);
    }

    /** 只读：该坐标上是不是机械手（{@code create:deploying} 的机器，按 Create 自己的方块类型判定）。 */
    private static boolean isDeployer(final Level level,
                                      @org.jetbrains.annotations.Nullable final BlockPos target) {
        return target != null && operatingPosOf(level, target) != null;
    }

    /**
     * <b>只读</b>：目标机器此刻是否被一件「<b>本步不要的</b>步骤专用投入物」占着（= 手里已经有别的东西）。
     *
     * <h2>为什么需要它（用户硬要求：不推错料、<b>且不反复重试</b>）</h2>
     * Create 的 {@code DeployerItemHandler#insertItem} 对「手里已经拿着<b>不同</b>物品」一律拒收
     * （手里那件被消耗掉之前，别的都进不去）。因此当机械手手里压着「上一步的投入物」（例如置物台那件
     * 已经走到要大齿轮、手里却还攥着齿轮）时，我们既不该推错（那是永久卡死），也不该每个 tick 都白试一次
     * ——「试一次必然失败」的推送会把日志与 CPU 都烧掉。这里直接给出「这一格此刻被外来件占着」的结论，
     * 由推料侧放弃这次推送并记一条限频日志；等收回侧把那件抄走（{@code RsccChamberImportStrategy} 的
     * 例外 ① 正是干这个）或步序轮到它，闸门立刻自愈。</p>
     *
     * <p><b>口径</b>：只看该机器容器里的物品是不是「步骤专用投入物 && 本步不要它」；
     * 非步骤专用投入物（原料 / 成品 / 过渡件）不算占位（它们本来就该在那儿）。
     * 判据的输入只有物品与目标坐标，<b>不做任何方块位置比较</b>；只读，绝不搬运 / 销毁任何资源。</p>
     */
    public boolean blockedByForeignStepExtra(@org.jetbrains.annotations.Nullable final BlockPos target,
                                             @org.jetbrains.annotations.Nullable final Item wanted) {
        if (target == null || wanted == null) {
            return false;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false; // 客户端不参与搬运
        }
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, target);
        if (handler == null) {
            return false; // 判不了（不是容器）→ 不拦（保持旧行为）
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            final Item held = inSlot.getItem();
            if (held == wanted || !isStepExtraInput(held)) {
                continue; // 正是要推的这一件 / 不是步骤专用投入物：不算占位
            }
            if (!inputMaterialWantedNow(target, held)) {
                return true; // 手里这件「本步不要」→ 它占着位置，推目标件只会被 Create 原样拒收
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：本工位对 {@code item} 的「本步要它」是否已经<b>长时间没有任何推进、且没有任何
     * 正当理由在等</b>（= 判定为堵塞 / 残留，应当放开收回）。
     *
     * <h2>为什么「需要」不等于「正在被消耗」（用户第 2 条：齿轮堵在置物台，只能手动拿走）</h2>
     * <p>上一轮把「本步仍要它 ⇒ 不收回」当成「保护正在加工的那一件」；但卡在工位上、机器根本吃不下
     * 的那一份同样「本步要它」—— 判据因此恒为真 ⇒ <b>永远不收回 ⇒ 永久堵塞</b>。这里给「需要」加一个
     * <b>时间闸门</b>。</p>
     *
     * <h2>为什么时间闸门还必须带「等待豁免」（用户本轮指出的反例，必须修）</h2>
     * <p>只有「时间」是不够的：本工位长时间不动<b>常常是合法等待</b>——把工位上的投入物拿走、而它又
     * 可以被自动合成时，整条线就是在等那件料做出来（用户原话：「小齿轮又可以是自动合成的……假如我选的是
     * 等待模式，小齿轮假如做得比较久……超过你这个限制（200 tick），这个金板不就自己收回去了？」）。
     * 只看「N tick 没动」会把这种等待误判成卡住并<b>主动收回</b> ⇒ 打断序列、浪费料、引发往返。
     * 因此只有在「<b>东西就在那儿、且没有任何正当理由在等</b>」时才允许累计卡住计时。</p>
     *
     * <h3>累计计时的完整条件表（任一条不满足 ⇒ 计时清零并暂停）</h3>
     * <ol>
     *     <li><b>条件①「东西确实在那儿」由调用方保证</b>：收回侧只对<b>真的从这台工位容器里读到的</b>
     *     堆叠调用本判定，且调用前已经确认「当前进度步把它列为应取的投入物」（
     *     {@code wanted.contains(item)}）；备料侧调用本判定只是「别再买第二份」，与「在不在」无关；</li>
     *     <li><b>条件②「没有任何正当理由在等」= {@link #hasWaitReason(Item, long)} 全为假</b>
     *     （挂起冻结 / 缺料等待档 / 该资源有在途合成量 / 该资源可合成且网络缺它 / 整条产线仍在推进）。</li>
     * </ol>
     * <p>此外，<b>下列任一事件发生即计时清零</b>：步序前进或换件（key 变化）、这一份被消耗 / 工位被清空
     * （观察中断超过 {@value #STATION_OBSERVE_GAP_TICKS} tick）、任一等待理由出现（每次观察都刷新起点）
     * ⇒ 等待理由消失后必须<b>重新</b>累计满 {@value #STATION_STUCK_TICKS} tick 才会判卡住。</p>
     *
     * <p>判定的唯一实现：{@link #inputMaterialWantedNow(BlockPos, Item)}、{@link #inputMaterialWantedNow(Item)}、
     * {@link #stationStepWantsInput(BlockPos, Item)}、{@link #wantingTargetCount(Item)} 四处共用本方法，
     * 因此「推料 / 收回（按本机 + 全仓并集）/ 备料」四个执行点对同一份料给出<b>同一个结论</b>，不会互搏。
     * 只读，只维护一张有界观察表，绝不搬运 / 销毁任何资源。</p>
     */
    private boolean stationStuckOn(@org.jetbrains.annotations.Nullable final BlockPos target,
                                   @org.jetbrains.annotations.Nullable final Item item,
                                   @org.jetbrains.annotations.Nullable final PendingStep pending) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || target == null || item == null || pending == null) {
            return false; // 判不了 → 不判卡住（保持既有「需要」保护）
        }
        final long now = level.getGameTime();
        final String key = target.asLong() + "#"
            + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item) + "#"
            + pending.recipeId() + "#" + pending.step();
        final StuckWatch watch = stationStuckWatch.get(key);
        // <b>等待豁免（用户本轮反例的修复点）</b>：有正当理由在等 ⇒ 计时清零并暂停。
        // 每次观察都把起点刷到「现在」，因此等待结束后必须重新累计满阈值才会判卡住。
        //
        // <b>本轮收窄（用户第 2 条：齿轮「还是堵」的持久化机制）</b>：W3/W4 是
        // 「<b>等这件料被自动合成出来</b>」的豁免 —— 而这一份<b>此刻已经压在工位上</b>时，等它被合成出来
        // 对「它能不能被消耗」毫无帮助（它已经在手上了）。旧实现把这两条豁免无差别地用在两种情形上，
        // 于是只要该资源「可自动合成 + 网络里恰好没有它」（本仓刚把它抽进来时<b>必然</b>成立），
        // 卡住计时就被无限刷新 ⇒ 永远等不到 {@value #STATION_STUCK_TICKS} tick ⇒ 收回侧永远不收回
        // ⇒ 用户实测的「齿轮永久堵在工位上，只能手动拿走」。现在按「这一份在不在工位上」分流：
        // 已经压着 ⇒ 只认 W1/W2（全局停工 / 玩家选的等待档）；还没到 ⇒ W1~W4 全部有效（等料做出来照旧豁免）。
        final boolean heldOnStation = supplyTargetHoldsItem(level, target, item);
        // <b>卡住结论「粘住」（用户「还是堵塞齿轮」的真根因修复）</b>：一旦这个 (工位, 物品, 配方, 步序)
        // 被判定为卡住，就<b>不再</b>因为「等待豁免重新成立」而撤销 —— 否则会出现实测里的 1 Hz 往返：
        // 判卡 → 输入总线把齿轮收回网络 → 工位上不再压着它（heldOnStation=false）⇒ W3（该资源有在途
        // 自动合成量，RS 正在为精密构件生产齿轮）立刻为真 ⇒ 计时被刷新 ⇒ 备料侧又买一份推过去。
        // 现在只有两种事能撤销这条结论：
        //   ① key 变化（步序真的前进了 / 换了件 / 换了配方）—— 自然走到下面的新签名分支；
        //   ② 慢重试窗口到期（{@value #STATION_STUCK_REARM_TICKS} = 10 秒）—— 给「玩家把机器修好了」
        //      留一条慢速自愈通道，但远慢于每秒往返，因此稳态下同一工位的往返为 0。
        if (watch != null && watch.stuck) {
            if (now - watch.stuckSince >= STATION_STUCK_REARM_TICKS) {
                // <b>慢重试（本轮修正：不再无条件撤销）</b>：旧实现到点就无条件清掉结论 ⇒
                // 每 10 秒把同一件东西「再推一次 → 再判卡 → 再收回」，这正是用户说的
                // 「齿轮一直在堵」的持久化形态（实测日志里同一 (工位, 件) 反复
                // {@code stuck … waited=61/120/180/240}）。
                // 现在只有「工位上已经不再压着这同一件」时才重试 —— 那说明东西真的被消耗掉了、
                // 被搬走了、或玩家把机器修好了；只要它还一步不动地压在原地，结论继续粘住。
                if (supplyTargetHoldsItem(level, target, item)) {
                    watch.stuckSince = now; // 结论继续粘住，下一个窗口再看
                    logStationStuck(target, item, pending, now, watch.since);
                    return true;
                }
                watch.stuck = false;
                watch.since = now;
                watch.lastSeen = now;
                return false;
            }
            watch.lastSeen = now;
            logStationStuck(target, item, pending, now, watch.since);
            return true;
        }
        if (hasWaitReason(item, now, heldOnStation)) {
            if (watch == null) {
                stationStuckWatch.put(key, new StuckWatch(now));
                pruneStuckWatch(now);
            } else {
                watch.since = now;
                watch.lastSeen = now;
            }
            return false;
        }
        if (watch == null) {
            stationStuckWatch.put(key, new StuckWatch(now));
            pruneStuckWatch(now);
            return false;
        }
        // 上一次观察已经隔了太久（这一份被消耗掉 / 被搬走 / 工位被清空后又放回来）：
        // 视为一次新的「需要」，计时<b>立即清零</b>（不再沿用旧起点 ⇒ 新那一份不会被过早判成卡住）。
        if (now - watch.lastSeen > STATION_OBSERVE_GAP_TICKS) {
            watch.since = now;
        }
        watch.lastSeen = now;
        if (now - watch.since <= STATION_STUCK_TICKS) {
            return false; // 还在正常节奏内：它确实「正在被用」
        }
        // 到达阈值：判定卡住，并把结论<b>粘住</b>（见上面那段说明）。
        watch.stuck = true;
        watch.stuckSince = now;
        logStationStuck(target, item, pending, now, watch.since);
        return true;
    }

    /**
     * 打一条「工位卡住 → 允许收回」的诊断行（<b>这就是「系统自动收回」的证据</b>；玩家手动拿走不会
     * 产生本行，那只会表现为 {@code net_down} 的存量下降）。
     *
     * <p><b>限频（第 20 轮修正：去重键必须带工位）</b>：上一轮把去重键写成
     * {@code "stuck@<仓>#<件>"}（<b>没带工位</b>）、把去重状态写成「已等待 tick 数的分档」。
     * 同一件东西同时堵在<b>两个工位</b>上时，两次调用分别写入两个<b>不同的分档值</b> ⇒
     * 每次调用看起来都"变了" ⇒ 去重恒不命中。实测 {@code latest.log} 13:41:13.477 这<b>一毫秒</b>里
     * 同一 (仓, 件) 刷出 <b>8 行</b>（两个工位 × 四个调用点），这就是用户看到的「一直在堵」的刷屏。
     * 现在键里带上工位坐标 ⇒ 每个 (仓, 件, 工位) 各占一格、每 3 秒最多一条，明细一字不减。</p>
     * <p>只读：不搬运 / 不销毁任何资源，只写日志。</p>
     */
    private void logStationStuck(final BlockPos target, final Item item, final PendingStep pending,
                                 final long now, final long since) {
        if (RsccAssemblyDebug.changed("stuck@" + RsccAssemblyDebug.at(worldPosition)
                + "#" + RsccAssemblyDebug.itemId(item) + "#" + RsccAssemblyDebug.at(target),
            Long.toString((now - since) / STATION_STUCK_TICKS))) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " stuck {item=" + RsccAssemblyDebug.itemId(item)
                + " station=" + RsccAssemblyDebug.at(target)
                + " recipe=" + pending.recipeId() + " step=" + pending.step()
                + " waited=" + (now - since) + " wait=none}"
                + " -> reclaim_allowed reason=station_no_progress");
        }
    }

    /**
     * <b>只读</b>：本工位此刻是否存在「<b>正当的等待理由</b>」（= 不是卡住，只是在等）。
     *
     * <p>这是本轮用户反例的修复点：只要下面任意一条为真，{@link #stationStuckOn(BlockPos, Item, PendingStep)}
     * 就<b>不计时</b>（计时清零并暂停）。</p>
     *
     * <h3>等待理由表（W1~W4，任一为真即豁免；<b>本轮删掉了旧的 W5</b>）</h3>
     * <ol>
     *     <li><b>W1 挂起冻结</b>：{@link #isBusFrozen()} —— 玩家 / 看门狗已把相关任务挂起，
     *     本仓全面停工，东西原地冻结（挂起期间任何「搬运」都该停）；</li>
     *     <li><b>W2 缺料等待档</b>：{@code RsccShortagePolicy.mode(level) == Mode.WAIT}
     *     —— 玩家明确选了「一直等到有料再继续」⇒ 等待任意长时间都合法；</li>
     *     <li><b>W3 该资源确有在途合成量</b>：{@link #autocraftInFlight(ResourceKey)}（RS 任务的
     *     {@code TaskStatus.Item#crafting} 汇总）{@code > 0} —— 缺口正在被自动合成补齐；</li>
     *     <li><b>W4 该资源可自动合成、且网络此刻缺它</b>：{@link #isAutoCraftable(AutocraftingNetworkComponent, Item)}
     *     且 {@link #networkItemAmount(Item)} {@code <= 0} —— 正是既有缺料判定里「属于可合成 / 在补」
     *     的那一类（用户场景：小齿轮被拿走、它又能自动合成 ⇒ 等着就行）。</li>
     * </ol>
     * <p><b>为什么旧 W5（「整条产线仍在推进」）必须删</b>：它是一个<b>全局</b>信号 ——
     * 只要同一条产线上<b>别的</b>工位换了步序，就把卡住那个工位的计时无限推迟（详见字段处那段说明）。
     * 用户现场是 2 台机器并行，健康那台每秒换步序 ⇒ 卡住的齿轮永远等不到阈值 ⇒ 只能手动拿走，
     * 正是本轮第 ① 条投诉的直接机制。删掉后，等待豁免全部只看<b>这一份资源</b>，不再看别的机器，
     * 而用户举过的「等小齿轮自动合成」反例由 W2/W3/W4 三条覆盖，一字未减。</p>
     * <p>只读；判不出来（无网络 / 无世界）时按「有等待理由」处理更安全的方向？<b>不</b>——判不出来
     * 一律返回 {@code false}（照常计时），因为「永不收回」正是上一轮的漏洞；而 W3/W4 的取值本身在
     * 读不到组件时就是 0/false，与既有保守口径一致。</p>
     *
     * <p><b>重载语义（本轮新增，用户第 2 条「齿轮还是堵」的持久化机制）</b>：W3/W4 只在「这一份<b>还没
     * 到工位上</b>」时才算等待理由（那是「等它做出来」）；一旦这份料已经压在工位上，等它被合成出来
     * 对「它能不能被消化」毫无帮助 —— 那时只认 W1/W2。见
     * {@link #stationStuckOn(BlockPos, Item, PendingStep)} 的说明与调用点。</p>
     */
    private boolean hasWaitReason(@org.jetbrains.annotations.Nullable final Item item, final long now) {
        return hasWaitReason(item, now, false);
    }

    /**
     * {@link #hasWaitReason(Item, long)} 的完整形态（唯一实现）。
     *
     * @param item          待判定的这一份资源（步骤专用投入物）
     * @param now           世界 tick（只为 W3 之类需要它时不读旧值；当前 W1~W4 均为纯函数）
     * @param heldOnStation 这一份此刻是否<b>已经压在工位上</b>（{@code true} ⇒ 只认 W1/W2）
     */
    private boolean hasWaitReason(@org.jetbrains.annotations.Nullable final Item item, final long now,
                                  final boolean heldOnStation) {
        if (isBusFrozen()) {
            return true; // W1：挂起冻结 —— 全面停工等待
        }
        final Level level = getLevel();
        if (level == null) {
            return false;
        }
        if (cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy.mode(level)
            == cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy.Mode.WAIT) {
            return true; // W2：缺料等待档 —— 等待任意久都合法
        }
        if (item == null) {
            return false;
        }
        // <b>W3/W4 的适用面收窄（本轮）</b>：它们表达的是「这份料还在被做出来的路上 ⇒ 值得等」。
        // 这一份已经压在工位上时，「再做一份出来」并不能让它被消化 ⇒ 不算等待理由，照常累计卡住计时。
        if (heldOnStation) {
            return false;
        }
        if (autocraftInFlight(new ItemResource(item)) > 0L) {
            return true; // W3：该资源确有在途量（正在被补）
        }
        final Network network = getNode().getNetworkOrNull();
        final AutocraftingNetworkComponent autocrafting = network == null
            ? null : network.getComponent(AutocraftingNetworkComponent.class);
        if (isAutoCraftable(autocrafting, item) && networkItemAmount(item) <= 0L) {
            return true; // W4：可自动合成 + 网络缺它 ⇒ 缺料判定属于「可合成 / 在补」
        }
        // 旧的 W5（整条产线仍在推进 ⇒ 豁免）已删除：它是全局信号，会让并行产线里卡住的工位永远等不到
        // 阈值。这里判不出来 ⇒ 不豁免（照常计时），与「永不收回正是上一轮的漏洞」同一方向。
        return false;
    }

    /**
     * <b>已删除</b>：旧 W5 的「整条产线最近一次推进」探针与全线步序签名
     * （{@code pipelineProgressedRecently} / {@code pipelineProgressSignature}）。
     *
     * <p>删除理由见 {@link #hasWaitReason(Item, long)} 与字段处那段说明：它们是<b>全局</b>信号，
     * 只要同一条产线上任意别的工位换了步序就会刷新，「卡住」的计时因此被无限推迟（并行产线里
     * 永远等不到阈值）。保留这条注释是为了让下一位读者知道「这里曾经有过什么、为什么不能加回来」——
     * 若将来需要「等上游」的豁免，判据必须<b>只看本工位自己的步序 / 在制件</b>，不得再引入全线信号。</p>
     */

    /** 卡住观察表的有界化：表超过上限时清掉长时间未被再次观察到的条目（幂等、无泄漏）。 */
    private void pruneStuckWatch(final long now) {
        if (stationStuckWatch.size() <= STATION_STUCK_WATCH_MAX) {
            return;
        }
        stationStuckWatch.values().removeIf(watch -> now - watch.lastSeen > STATION_STUCK_TICKS * 4L);
    }

    /**
     * 旧口径（全仓并集、取最小步）：只在调用方拿不到「本总线面对哪一台机器」时使用。
     * <p>推料侧必须用 {@link #inputMaterialWantedNow(BlockPos, Item)} —— 否则多台机器处于不同步骤时，
     * 「靠前那台的步骤」会成为全仓唯一的答案，靠后那台的投入物永远进不去（= 总体只出一份）。</p>
     */
    public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return true;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return true; // 客户端不参与搬运
        }
        if (!isStepExtraInput(item)) {
            return true; // 不是「步骤专用投入物」：保持既有行为（主原料 / 流体输入照常喂）
        }
        // <b>全仓口径 = 「任何一台供料目标此刻要它」的并集</b>（本轮修正）。
        // 旧写法取「全仓并集里的<b>最小</b>步」当一个唯一答案：一条仓同时给多台机器 / 多个步骤供料时，
        // 靠前那台的步骤会把靠后那台要的投入物一并判成「不要」—— 于是仓刚备进来的料被收回侧立刻抄回网络、
        // 下一秒又被备回来（用户实测：pull 与 collect 每秒来回、机器一个件都推不出去）。
        // 多台机器「各要各的一份」时并集必然是放行，因此并集既不会误伤，也不会让任何一台断供。
        boolean undecidable = false;
        for (final BlockPos target : busSupplyTargets()) {
            final PendingStep pending = pendingStepOn(target);
            if (pending == null) {
                // <b>本轮修正（用户第 5 条：齿轮仍堵 —— 「列表视图」与「单物品视图」判据不一致）</b>：
                // 空闲机械手（判不出待加工件 = 它朝向 2 格外的操作对象上空着）是<b>确定</b>的「它此刻不要
                // 任何步骤专用投入物」，而不是「判不出来」。旧实现把它计进 undecidable ⇒ 全仓并集返回
                // true ⇒ 收回侧（autoAcceptsChamberItem 的废料例外）以为「某台还要它」⇒ 仓里那份废料齿轮
                // <b>永远收不回网络</b>（只能玩家手动抠出来 = 用户点名的「齿轮堵」），而推料侧（按本机判）
                // 同样确定「不要」⇒ 也推不出去。两侧对同一件事给出相反答案，正是「备料买进来却永远推不出去」
                // 的残留路径。现在与 {@link #wantingTargetCount} 的「空闲机械手 = 确定不要」口径<b>完全对齐</b>。
                if (!isDeployer(level, target)) {
                    undecidable = true; // 其它机器形态判不出来 → 不因它而断供
                }
                continue;
            }
            final Set<Item> wanted = stepExtraInputsOfStep(pending);
            if (wanted.isEmpty()) {
                return true; // 这一步没有已知投入物：本判据不表态（放行）
            }
            if (wanted.contains(item)) {
                // 这一台此刻正需要它 → 放行；但<b>卡住</b>的工位不算「要它」（用户第 2 条：
                // 齿轮堵在置物台）—— 与按本机判据、备料判据共用同一个 stationStuckOn，三处结论一致，
                // 否则仓内那份废料性齿轮会被这里「某台还要它」永远护住、收不回网络。
                if (!stationStuckOn(target, item, pending)) {
                    return true; // 这一台此刻正需要它 → 放行
                }
                continue; // 卡住：本台不算「要它」，继续看别台（都不缺 ⇒ 收回侧认定没有工位要它）
            }
        }
        // 所有判得出的机器都不要它：只要还有一台判不出来就保守放行（旧口径的「判不出来不拦」）
        return undecidable;
    }

    /**
     * <b>只读</b>：本仓正在供料的这一台机器 / 置物台，<b>此刻这一步是否还需要 {@code item}</b>。
     *
     * <h2>为什么需要它（用户第 ③ 条：齿轮在两台机器间反复推—收—推）</h2>
     * <p>一份齿轮被 A 仓推给共享工位后，朝同一个工位的 B 仓输入总线会来收它。若齿轮不在 B 仓自己的
     * 「输入类」里（B 只负责后面的机械手步骤），旧判据「不是我输入类 ⇒ 是产出 / 废料 ⇒ 收回」就会把这份
     * <b>还没被消耗、正是该工位本步要用的</b>投入物抄回网络，下一秒 A 仓又推一份回来 ⇒ 每秒往返。
     * 判据必须问「<b>这台机器</b>此刻要不要它」，而不是「本仓的输入类里有没有它」。</p>
     *
     * <h2>口径（只按步骤 / 配方；<b>只有「确定它此刻需要」才拦</b>，判不出来一律不拦）</h2>
     * <ol>
     *     <li>取该工位此刻的待加工件（{@link #pendingStepOn(BlockPos)}：机器上在制件的进度步）。
     *     <b>取不到就返回 {@code false}（= 不拦，照旧收回）</b>：工位上没有在制件 ⇒ 它眼下没有
     *     「正在加工的这一步」，此刻压在它上面的东西（成品 / 废料）必须照常被收回 ——
     *     否则机器的成品会永远堆积、再也没人收（那是比「偶尔多收回一份」严重得多的回归）；</li>
     *     <li>按该进度步解析配方的这一步的投入物（{@link SequencedRecipeProbe#assemblyStepInputs}，
     *     与「步骤专用投入物」表同源）；{@code item} 在其中 → {@code true}（<b>拦</b>）；</li>
     *     <li>配方主原料（{@link #mainIngredientOf}）同样算「这一步仍需要它」—— 机器上那份还没被消耗的
     *     起步原料不该被任何输入总线抄走（与 {@code isStartIngredient} 的保护同向），
     *     且它<b>不吃卡住闸门</b>（见下）；</li>
     *     <li><b>本轮收窄（用户第 ① 条 + 硬约束）</b>：卡住闸门（{@link #stationStuckOn(BlockPos, Item, PendingStep)}）
     *     只作用在<b>步骤专用投入物</b>上 —— 同一份「需要」连续 {@value #STATION_STUCK_TICKS} tick
     *     没有任何推进 ⇒ 判定为<b>卡住</b>，返回 {@code false}（不再保护，交给收回侧收回），
     *     于是「<b>需要 ≠ 正在被消耗</b>」这条漏洞被堵上；而配方主原料（起步原料）<b>恒定受保护</b>，
     *     它的回收只走「本条任务刚结束」的那一次边沿令牌（硬约束：任务期间起步原料绝不收回）。</li>
     * </ol>
     * <p>绝不搬运 / 销毁任何资源：只读配方与工位状态。</p>
     */
    public boolean stationStepWantsInput(@org.jetbrains.annotations.Nullable final BlockPos target,
                                         @org.jetbrains.annotations.Nullable final Item item) {
        if (target == null || item == null) {
            return false; // 判不了 → 不拦（保持既有的「照旧收回」）
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false; // 客户端不参与搬运
        }
        final PendingStep pending = pendingStepOn(target);
        if (pending == null) {
            // 工位上没有在制件 ⇒ 没有「正在加工的这一步」要保护；成品 / 废料照旧回收。
            return false;
        }
        final SequencedAssemblyRecipe recipe = assemblyById(level, pending.recipeId());
        if (recipe == null) {
            return false; // 配方查不到 → 判不出来，不拦
        }
        final List<SequencedRecipe<?>> sequence = recipe.getSequence();
        if (sequence.isEmpty()) {
            return false; // 步数未知 → 判不出来，不拦
        }
        final int index = Math.floorMod(pending.step(), sequence.size());
        final Item transitional = recipe.getTransitionalItem().getItem();
        for (final ItemStack stack : SequencedRecipeProbe.assemblyStepInputs(
            sequence.get(index).getRecipe(), transitional)) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                // 这一步此刻正要它 —— 但「<b>需要 ≠ 正在被消耗</b>」：长期无进展的「需要」= 卡住
                //（用户第 2 条：齿轮堵在置物台、只能手动拿走），此时不再保护，交给收回侧收回去。
                return !stationStuckOn(target, item, pending);
            }
        }
        // <b>配方主原料（起步原料）在任务运行期间「绝不收回」（本轮修正，硬约束）</b>：
        // 它只可能是「要开新件的那一份」，不可能是废料；一旦被抽走，正在加工的那一件就断了料。
        // 因此<b>卡住闸门只作用于「步骤专用投入物」</b>（齿轮那类可燃尽、可重取的件），
        // 起步原料的回收只走「本条任务刚结束」的那一次边沿令牌（residualEdge，见
        // {@code RsccChamberImportStrategy#autoAcceptsItem}）。
        // 为什么必须收窄（用户硬要求「挂起期间不回收原料」「起步原料在任务期间绝不收回」）：
        // 阈值从 200 收到 60（3 秒）后，若主原料也吃这道闸门，一次「等料略久」的正常等待就会
        // 把它当成卡住收走 —— 那是比「齿轮多躺一会儿」严重得多的回归。
        // 判据按<b>候选集合</b>：标签型起步原料的任一候选（石头台阶 / 平滑石台阶 / 安山岩台阶）
        // 都受同一条保护（用户第 1 条）。
        return mainIngredientCandidates(recipe).contains(item);
    }

    /**
     * <b>只读</b>：此刻有几个<b>工位</b>（{@link #supplyStations}）处于「<b>缺这份投入物而停</b>」
     * 的状态（= 该工位这一步需要它，且它的容器链上还没有这一件）—— 也就是「该喂几份」。
     *
     * <h2>为什么需要「按台计份」（用户硬要求：多台机器同时各一份）</h2>
     * 「一次一份」的正确口径是<b>每种原料 × 每台要它的机器各一份</b>，而不是「全仓总共一份」，
     * 也不是「每批需求 × 循环数」的大缓冲。旧实现按 {@code 每批需求 × loops} 备料（实测 target=5），
     * 而放行判定只认「当前那一个待加工步」—— 多备出来的几份随即被判定为「本步不要它」，
     * 被收回侧原样抄回网络、又被备料侧再买一遍，形成用户实测的「一直输出 / 推不进去也不转移」。
     * 按工位计份后：要几份就只备几份，喂得进去就喂、喂不进去也不堆料，且多台机器各自有一份。
     *
     * <h2>本轮修正（用户实测「齿轮堵塞 / 多余的原料」的残留路径）</h2>
     * 旧写法直接把相连输出总线朝向的每一格<b>逐格</b>计数，于是同一个物理工位被计入<b>两次</b>
     * （置物台自己一次、对着它的机械手一次 —— 两者解析出的待加工件完全一样，见
     * {@link #operandStepOf}），而且「手上已经握着一件」也照样计入。实测后果：
     * {@code chamber@(-5,-60,6) pull {item=create:cogwheel x1} target=3 chamberHave=3} ——
     * 单台机器只该要 1 份，仓里却囤了 3 份；步序一前进，多出来的那几份立刻被判「本步不要它」，
     * 又被收回侧抄回网络、下一秒再备一遍（日志里 {@code not_wanted_this_step} 与 {@code pull}
     * 交替出现、{@code target} 在 1↔3 之间跳）。现在：
     * <ul>
     *     <li>按 {@link #stationKey} 归并 ⇒ 一个工位只算一次；</li>
     *     <li>{@link #supplyTargetHoldsItem} 为真 ⇒ <b>它不缺料</b>（手上还有未消耗的投入物）→ 不再为它备第二份。</li>
     * </ul>
     *
     * <p>判不出来（该机器上没有在制件 / 配方步数未知）时计 1 份 —— 与
     * {@link #inputMaterialWantedNow(Item)} 的「判不出来一律放行」同一口径，绝不因判不出来而断供。
     * 非「步骤专用投入物」（主原料 / 流体输入 / 成品 / 废料）返回 0，表示「本方法不表态」。
     * 只读，绝不搬运 / 销毁任何资源。</p>
     */
    private int wantingTargetCount(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null || !isStepExtraInput(item)) {
            return 0;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return 0;
        }
        int want = 0;
        final Map<BlockPos, BlockPos> stations = supplyStations(level);
        // 一个供料目标都没有（线缆被拆 / 朝向不对）= 判不出来 → 与旧口径一致（保底 1 份，绝不因判不出来而断供）
        boolean undecidable = stations.isEmpty();
        for (final Map.Entry<BlockPos, BlockPos> station : stations.entrySet()) {
            final BlockPos target = station.getValue();
            // <b>起步原料 + 工位上还压着在制件 ⇒ 这一台开不了新件 ⇒ 不为它备料</b>
            //（用户「定量法」实测：下单 N 只该消耗 N 份主原料，多出的那一份就是从这里进去的）。
            // 注意这里<b>不</b>置 {@code undecidable}：这不是「判不出来」，而是确定「它不缺料」——
            // 否则下面的保底 1 份会把多出来的那一份又拉进仓里。
            if (isStartIngredient(item) && unitInFlightAt(target)) {
                continue;
            }
            final PendingStep pending = pendingStepOn(target);
            if (pending == null) {
                // <b>本轮修正（用户第 ② 条：齿轮堵塞 / 多余中间产物）</b>：机械手（Create 的 deploying 步）
                // 判不出待加工件 = 它朝向的操作对象上空着 = 它<b>眼下没有任何东西可加工</b> —— 这是
                // <b>确定</b>的「不需要这份投入物」，而不是「判不出来」。旧实现把它计进 {@code undecidable}
                // ⇒ 一个工位都不缺它时还会保底备 1 份 ⇒ 仓里多出一份齿轮（等工件来了才用得上）。
                // 现在与推料侧同一口径（{@link #inputMaterialWantedNow(BlockPos, Item)} 对空闲机械手
                // 同样返回 {@code false}），因此「推不动就备一份」这条多余路径在两侧一起消失。
                // 其它机器形态仍然「判不出来就不表态」（附属模组的自定义步可能把投入物收进别的槽位），
                // 绝不因为判不出来而断供 —— 既有宽松口径一字未改。
                if (!isDeployer(level, target)) {
                    undecidable = true;
                }
                continue;
            }
            final Set<Item> wanted = stepExtraInputsOfStep(pending);
            if (wanted.isEmpty()) {
                undecidable = true;
                continue;
            }
            if (!wanted.contains(item)) {
                continue;
            }
            // 这一台手上 / 台上已经握着这件（未被消耗）→ 它不缺料，绝不再为它备第二份
            //（用户硬要求：同一台机器同一时刻最多一份未消耗的投入物）。
            if (supplyTargetHoldsItem(level, target, item)) {
                continue;
            }
            // <b>卡住闸门（用户第 2/3 条：齿轮堵塞 + 多余中间产物）</b>：这一步要它、但已经连续
            // {@value #STATION_STUCK_TICKS} tick 没有任何推进 ⇒ 这台机器「吃不下」它，不是缺料。
            // 不为它备料 ⇒ 不会出现「收回 → 又买回来 → 再收回」的来回搬运，也不会多出中间产物。
            if (stationStuckOn(target, item, pending)) {
                continue;
            }
            want++;
        }
        // <b>份额闸门（本轮新增，对应用户第 ④ 条「一个一个下单时中间产物还是存在额外之类的」）</b>：
        // 备料份数不得超过「订单剩余件数」—— 下单 1 个 + 两个工位都要它时，从前会抽 <b>2 份</b>
        // （实测 21:25:00 一单里 cogwheel / large_cogwheel / iron_nugget / golden_sheet 各 2 份，
        // 多出来的那份在任务结束时以 {@code task_finished_residual} 退回网络），现在只抽 1 份。
        // 判不出来（{@code remaining <= 0}，例如没有与本仓相关的任务）时<b>保持既有行为</b>
        // （= 要几份就备几份），绝不因判不出来而断供 —— 与 {@link #exportShare} 同一口径。
        if (want > 0) {
            final long remaining = remainingOrderUnits();
            if (remaining > 0L) {
                want = (int) Math.min((long) want, Math.max(1L, remaining));
            } else {
                // <b>判不出来时不再「保持既有行为」（本轮修正，用户第 ①④⑤ 条）</b>：旧实现在这里
                // 原样返回 want = 「缺它的工位数」（下单 1 件 + 两个工位 ⇒ 2 份），正是「下单 1 件却
                // 多备一份 / 数量快速增减」的来源。现在退回 {@link #allowedConcurrentUnits()} ——
                // 「本仓当下真在制 / 待补的件数」的保守下界（至少 1），<b>不确定时取小不取大</b>。
                want = (int) Math.min((long) want, allowedConcurrentUnits());
            }
            return want;
        }
        return undecidable ? 1 : 0;
    }

    /**
     * 「步骤专用投入物」的备料量上限（= {@link #wantingTargetCount}）：<b>每种原料 × 每台要它的机器各一份</b>。
     * <p>非「步骤专用投入物」返回 {@link Long#MAX_VALUE}，表示「不受本上限约束」（主原料 / 流体输入
     * 仍走既有的每批需求 × 策略口径，行为逐字不变）。</p>
     * <p><b>为什么不再夹一个「至少 1 份」的下限（本轮修正）</b>：用户硬要求是「<b>只有当这台机器
     * 干不下去（缺料）时，才为它补一份</b>」。若一个工位都不缺它却仍把目标量顶到 1，
     * 备料侧就会每 tick 走一次「要不要拉进来」的判定，并因为「本步不要它」被拒 ——
     * 实测日志里 {@code not_wanted_this_step}（277 次加权）与 {@code pull} 交替出现、
     * {@code target} 在 1↔3 之间跳，正是「齿轮堵塞」的化身。
     * 归零后：<b>不缺料 ⇒ 一条判断都不做</b>（见 {@code fillInternalForBus} 里的 {@code cap <= 0} 短路）；
     * 「判不出来」（没有任何供料目标 / 机器上判不出步序）时 {@link #wantingTargetCount} 仍返回 1，
     * 因此绝不因判不出来而断供 —— 旧口径只在「真的没有机器缺它」这一种情形下被收紧。</p>
     */
    /**
     * <b>只读</b>：网络存储里此刻是否真的有该类别中任一件物品（存量 &gt; 0）。
     *
     * <p>用途：{@code /rs_create_compat reuse on} 时给「中间产物」类别开一道豁免闸门（见
     * {@code fillInternalForBus} 里的 {@code reuseIntermediate}）。硬条件就是本方法 ——
     * 只有网络里真的压着该中间产物时才放行，因此<b>没有存量时与开关关闭完全同行为</b>，
     * 不会退化成「没订单也开工」。</p>
     *
     * <p>判据取 {@code PlayerActor} 视角的网络存量（与备料侧 {@code storage.getResources} 同一来源），
     * 只读、不抽取、不改变任何状态。</p>
     */
    private boolean hasNetworkStock(final BusCategoryInfo info) {
        if (info == null || info.items().isEmpty()) {
            return false;
        }
        final StorageNetworkComponent storage = currentStorage();
        if (storage == null) {
            return false;
        }
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            if (tracked.resourceAmount().amount() <= 0L) {
                continue;
            }
            final ResourceKey key = tracked.resourceAmount().resource();
            if (key instanceof ItemResource itemResource) {
                for (final Item item : info.items()) {
                    if (item != null && itemResource.item() == item) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private long stepExtraStockTarget(final Item item) {
        return isStepExtraInput(item) ? wantingTargetCount(item) : Long.MAX_VALUE;
    }

    /**
     * 某类别是不是「步骤专用投入物」类别（{@link #busExportFilters} 里豁免<b>轮询</b>的唯一判据）。
     * <p><b>只豁免轮询，不豁免归属</b>：归属（「本总线自己勾了它吗」）在任何类别上都必须先成立，
     * 否则会出现「没勾的类别也被推出去」（用户实测：只勾了金板 + 中间产物的置物台总线推出了齿轮）。</p>
     */
    private boolean isStepExtraCategory(final BusCategoryInfo info) {
        if (info == null || info.items().isEmpty()) {
            return false;
        }
        for (final Item item : info.items()) {
            if (item == null || !isStepExtraInput(item)) {
                return false; // 含非步骤专用投入物（主原料 / 过渡件）的类别不适用放宽
            }
        }
        return true;
    }

    /**
     * 只读：这份物品是不是本仓某条配方的「<b>起步原料</b>」（Create 的 {@code ingredient}）。
     *
     * <h2>为什么要把起步原料单独认出来（用户实测「黑曜石粉三份才出一份板」）</h2>
     * 一批输入里只有起步原料会「<b>开一件新的在制件</b>」；其余输入（注液步的岩浆、机械手那件投入物）
     * 都只是把<b>已经开好的那一件</b>往前推。因此「机器还在加工这一步的件就不再补料」这条闸门只能压在
     * 起步原料上 —— 压在别的输入上会把正在加工的那一件直接饿死。
     *
     * <p><b>口径</b>：本仓「负责的步」表里每条配方的主原料（{@link #mainIngredientOf}）。
     * 判据只按配方身份，<b>不做方块坐标比较</b>；只读。</p>
     */
    public boolean isStartIngredient(@org.jetbrains.annotations.Nullable final Item item) {
        return item != null && startIngredients().contains(item);
    }

    /**
     * 只读：该目标机器此刻是否还压着「<b>正等本仓某一步加工</b>」的在制件
     * （= 本仓这一步还没加工完它）→ 本仓不该再往里开新件。
     *
     * <h2>为什么需要它（用户实测「三份粉才出一份板 / 粉末被浪费」的根因）</h2>
     * 供料侧原先只比对「机器里有没有<b>同一种</b>料」（{@code holdsSameItem}）。这条判据对「机器上压着的
     * 已经是<b>过渡件</b>」的情形完全看不见：黑曜石粉一落到注液机就被 Create 换成未完成黑曜石板，
     * 于是仓里那份起步原料每节拍都被判定为「机器没压着同种料」→ 继续补一份。实测（latest.log）：
     * 注液仓每约 1 秒 pull 一份黑曜石粉 + 500 mB 岩浆并立刻 push 进机器，而整条线（注液 + 两次冲压）
     * 每件要 4 秒以上 —— 网络里的粉掉得比板材产出快约三倍，多出来的部分全变成堆在网络里的未完成件。
     *
     * <p><b>判据（只按步骤 / 身份，不做方块坐标比较）</b>：该机器容器里存在一件带
     * {@code create:sequenced_assembly} 的在制件，且它的「下一步」正是<b>本仓负责的某一步</b>
     * （{@link #isOwnedStep}）→ 本仓这一步还在加工它 → 不再开新件。机器一加工完（步序离开本仓的步）
     * 闸门立刻放行，因此它<b>自愈</b>、不需要任何持久化计数，也不会跨机器互相影响。</p>
     *
     * <p>不带进度组件的裸起步原料仍由「同种料」判据负责，本方法不重复表态（两者叠加即「每种输出各一份」）。
     * 只读，绝不搬运 / 销毁任何资源。</p>
     */
    public boolean stepUnitInFlightOn(@org.jetbrains.annotations.Nullable final BlockPos target) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || target == null) {
            return false;
        }
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, target);
        if (handler == null) {
            return false; // 判不了（不是容器）→ 不拦（旧行为）
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            final SequencedAssembly assembly = inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (assembly == null) {
                continue; // 原料 / 成品 / 废料：没有进度步，本判据不表态
            }
            final int step = nextStepOf(level, assembly);
            if (step >= 0 && isOwnedStep(assembly.id().toString(), step)) {
                return true; // 这一步的件还压在这台机器上 → 本仓正在加工它
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：某个供料工位（{@link #stationKey} 归并后的那一格，含机械手自身）此刻是否压着
     * <b>任何一件</b>已经开了工、还没走完序列的在制件（带 {@code create:sequenced_assembly} 进度组件）。
     *
     * <h2>为什么必须按「工位」判、且不要求「下一步归本仓」（用户实测「坚固板多耗一个黑曜石粉」的根因）</h2>
     * {@link #stepUnitInFlightOn} 要求「这份在制件的<b>下一步正由本仓负责</b>」，因此对
     * 「注液机刚把黑曜石粉变成未完成黑曜石板、下一步（冲压）归<b>别的</b>仓」这种跨仓产线看不见 ——
     * 本仓据此认为「机器没压着同种料」，于是又补一份起步原料，<b>一件在制品被喂了两份粉</b>。
     * 用户用「定量法」实测到的正是这一点：下单 N 个坚固板只该消耗 N 份黑曜石粉，实际多耗 1 份。
     * 这里把闸门放宽成「工位上只要有在制件就不开新件」—— Create 的机器一次只能加工一件，
     * 这与「同一台机器同一时刻最多一份未消耗投入物」本就是同一条不变式（推料侧与备料侧共用本判据）。
     *
     * <p>只读，绝不搬运 / 销毁任何资源；没有容器 / 未加载一律 {@code false}（不拦，保持既有行为）。</p>
     */
    public boolean unitInFlightAt(@org.jetbrains.annotations.Nullable final BlockPos target) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || target == null) {
            return false;
        }
        return holdsUnitAt(level, target) || holdsUnitAt(level, stationKey(level, target));
    }

    /** 只读：这一格容器里是否有带进度组件的在制件（{@link #unitInFlightAt} 的逐格实现）。 */
    private static boolean holdsUnitAt(final Level level, final BlockPos pos) {
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, pos);
        if (handler == null) {
            return false; // 判不了（不是容器）→ 不拦（旧行为）
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
                return true;
            }
        }
        return false;
    }

    // ==================== 在制件登记表（2026-10-05 新增，设计文档第 4 步） ====================

    /** 见 {@link InFlightUnit} 的说明。 */
    private final Map<BlockPos, InFlightUnit> inFlightUnits = new java.util.LinkedHashMap<>();

    /** 登记表诊断日志的节流。 */
    private long inFlightLogAt = Long.MIN_VALUE / 2;

    /**
     * <b>一条在制件的显式登记</b>：哪个工位、压着哪条配方的第几步、那件是什么。
     *
     * <h2>为什么要有这张表（它是「修一点坏一点」的止血点）</h2>
     * <p>本模组原先<b>没有</b>任何地方显式记录「在制件」这件事，而是每 tick 从方块世界反推
     * （{@link #unitInFlightAt} / {@link #pendingStepOn} / {@code stationStuckOn} …）。
     * 这些探针各自解释「这一步是不是我的」，于是互相矛盾 ——
     * 典型症状：推料侧认为「机器空着」（于是推）、收回侧认为「工位有件」（于是收），
     * 形成无限推收循环（用户实测「置物台一直有取入取出的声音」）。</p>
     *
     * <p>本表把「在制件」变成<b>显式状态</b>：只由「推料成功」写、由「任务确认结束」清。</p>
     */
    public record InFlightUnit(String recipe, int step, Item item, long sinceTick) {
    }

    /** 记一条「刚把某个在制件推进某工位」；只由推料成功路径调用。 */
    public void rscc$noteUnitPushed(final BlockPos target,
                                    @org.jetbrains.annotations.Nullable final String recipe,
                                    final int step, final Item item) {
        if (target == null) {
            return;
        }
        final Level level = getLevel();
        final long now = level == null ? 0L : level.getGameTime();
        inFlightUnits.put(target.immutable(), new InFlightUnit(
            recipe == null ? "" : recipe, step, item, now));
        setChanged();
        logInFlight(level, "pushed");
    }

    /**
     * <b>只读</b>：该工位上是否登记着「本仓推过去的在制件」。
     *
     * <p>用途：回收侧的「本仓自己的供料目标 ⇒ 绝不抢回」闸门。
     * 为什么不用 {@code busSupplyTargets()}：那个方法<b>要求 {@code outputMode == BUS}</b>，
     * 玩家的仓是 FACE 模式时它返回空表 ⇒ 闸门恒不触发（这正是我上一版改了却没生效的原因）。
     * 而在制件登记表由<b>每一次成功的推料</b>写入（见 {@code RsccChamberExportStrategy}），
     * <b>与输出模式无关</b>，因此是更可靠的事实源。</p>
     */
    public boolean rscc$isRegisteredStation(final BlockPos station) {
        return station != null && inFlightUnits.containsKey(station.immutable());
    }

    /**
     * <b>只读</b>：该工位上「本仓推进去的那件」的登记（{@code null} = 没登记 / 判不出）。
     *
     * <p>装配看门狗的「零进展」判据要用它回答两件事（见
     * {@code AssemblyWatchdog#stallProgressOf}）：</p>
     * <ol>
     *     <li><b>这台目的地上压着的，是不是我们推进去的那件在制件（步序没有倒退，含原地推进过的步）？</b>
     *     是 ⇒ 机器正拿着我们那件在加工它（Create 只在一步<b>做完</b>时才推进 {@code step}，
     *     见 {@code SequencedAssemblyRecipe#advance}）⇒ 既不能算「零进展」，更不能弹「堵了」；</li>
     *     <li><b>本仓向这台目的地的最近一次成功推送是多久以前？</b>（→ {@link #rscc$lastPushOkAge}）。</li>
     * </ol>
     * <p>两件事<b>共用这一份登记</b>（由每一次成功推料写入，见 {@code RsccChamberExportStrategy}），
     * 因此不新增第二套「谁推了什么」的真源。只读，不搬运 / 不修改任何状态。</p>
     */
    @org.jetbrains.annotations.Nullable
    public InFlightUnit rscc$registeredUnitAt(@org.jetbrains.annotations.Nullable final BlockPos station) {
        return station == null ? null : inFlightUnits.get(station.immutable());
    }

    /**
     * <b>只读</b>：本仓向该目的地<b>最近一次成功推送</b>距今多少 tick（{@code -1} = 没有记账 / 判不出）。
     *
     * <p>数据源是 {@link #inFlightUnits} 的 {@code sinceTick} —— 它由<b>每一次成功的物品推送</b>写入
     * （{@code RsccChamberExportStrategy#transfer} → {@link #rscc$noteUnitPushed}），
     * 因此天然是「本仓 → <b>这台</b>目的地」这一条线的成功推送时刻，<b>按工位</b>成立。</p>
     *
     * <p>刻意<b>不</b>用 {@link #lastSuccessfulPushAt}（那是<b>全仓</b>口径）：本仓常常同时喂多台机器，
     * 「别处推成功过」不能证明<b>这一台</b>动过 —— 而那正是看门狗要判的东西。</p>
     * <p>流体推送不写这份登记（流体走另一条分支）⇒ 返回 {@code -1}（判不出来）。</p>
     */
    public long rscc$lastPushOkAge(@org.jetbrains.annotations.Nullable final BlockPos target) {
        final InFlightUnit unit = rscc$registeredUnitAt(target);
        final Level level = getLevel();
        if (unit == null || level == null) {
            return -1L;
        }
        final long age = level.getGameTime() - unit.sinceTick();
        return age < 0L ? -1L : age;
    }

    /** 任务确认结束：整表清空（东西都要还回网络）。 */
    public void rscc$clearAllUnits() {
        if (!inFlightUnits.isEmpty()) {
            inFlightUnits.clear();
            setChanged();
        }
    }

    /** 只读：本仓登记了几件在制件（诊断用）。 */
    public int rscc$inFlightUnitCount() {
        return inFlightUnits.size();
    }

    /** 只读：登记表摘要（诊断对照用）。 */
    public String rscc$inFlightSummary() {
        if (inFlightUnits.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<BlockPos, InFlightUnit> entry : inFlightUnits.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(RsccAssemblyDebug.at(entry.getKey())).append(':').append(entry.getValue().step());
        }
        return sb.toString();
    }

    /** 登记表变化时的诊断日志（节流 200 tick；只取证据，不参与任何判定）。 */
    private void logInFlight(@org.jetbrains.annotations.Nullable final Level level,
                             final String why) {
        if (!RsccAssemblyDebug.isEnabled() || level == null) {
            return;
        }
        final long now = level.getGameTime();
        if (now - inFlightLogAt < 200L) {
            return;
        }
        inFlightLogAt = now;
        RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
            + " infight_units=" + rscc$inFlightUnitCount()
            + " detail=[" + rscc$inFlightSummary() + "]"
            + " probeUnits=" + computeInFlightUnitCount(level)
            + " why=" + why);
    }

    /**
     * 只读：本仓此刻是否<b>真的在加工一件东西</b>（内部存储 / 磁盘缓存里存在带
     * {@code create:sequenced_assembly} 进度组件的在制件）。
     *
     * <p><b>为什么不能拿「仓里有没有东西」当「在加工」（AssemblyWatchdog 的缺料判定必需）</b>：
     * 总线输出模式下本仓会<b>常驻</b>一批备料（原料 / 流体），所以「仓非空」几乎恒为真；
     * 旧判据（{@code itemStorage().getItemCount() > 0 || !outputTank.isEmpty()}）因此让
     * 「可推进」恒成立 → 缺料原因<b>永远判不出来</b> → 任务被卡住也不弹任何提示
     * （用户原话：「缺少材料也需要弹弹窗。但是你现在并没有弹弹窗」）。
     * 在制件（过渡件）才是「这条产线真的在做一件事」的唯一标志，因此缺料判定改用本方法。</p>
     *
     * <p>只读，绝不搬运 / 销毁任何资源。</p>
     */
    public boolean hasInFlightUnit() {
        final net.neoforged.neoforge.items.IItemHandler store = itemStorage();
        for (int slot = 0; slot < store.getSlots(); slot++) {
            final ItemStack stack = store.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：本仓的<b>整条产线</b>（仓内统一视图 + 全部供料目标 = 机器 / 置物台）此刻是否还在
     * 加工任何一件带 {@code create:sequenced_assembly} 进度组件的在制件。
     *
     * <h2>为什么不能只看仓内（用户第 1 条：刚好够却误报缺少黑曜石粉末）</h2>
     * <p>{@link #hasInFlightUnit()} 只看执行舱<b>内部存储</b>；而在制件一旦被输出总线推给机器 / 置物台，
     * 就<b>不再停在仓内</b>了。AssemblyWatchdog 的缺料判定把「仓内有没有在制件」当作「产线还在动吗」
     * 的唯一标志 —— 于是产线明明在正常推进（只是机器上那件暂时不在仓里）也会被判成
     * 「不推进 + 缺料」并弹缺料横幅。本方法把机器 / 置物台上的在制件也算进来，判据才算完整。</p>
     * <p>只读，绝不搬运 / 销毁任何资源；未进入世界 / 客户端一律 {@code false}。</p>
     */
    public boolean pipelineHasInFlightUnit() {
        if (hasInFlightUnit()) {
            return true;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        for (final BlockPos target : busSupplyTargets()) {
            if (unitInFlightAt(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：整条产线（仓内 + 机器 / 置物台）此刻在制的件数
     * （{@link #inFlightUnitCount()} 的公开只读投影，含每 tick 缓存）。
     *
     * <p>供 AssemblyWatchdog 判定「起步原料是否已被在制件顶掉」：只要
     * {@code 订单剩余件数 ≤ 在制件数}，那一份原料就已经消耗在产线里，不是缺料
     * （用户第 1 条：刚好够却报缺的最后一条路径）。只读，不搬运任何资源。</p>
     */
    public long pipelineInFlightCount() {
        return inFlightUnitCount();
    }

    /**
     * <b>只读</b>：本仓内部存储或它正在供料的机器 / 置物台上此刻是否压着这一份物品。
     *
     * <h2>为什么需要它（用户第 1 条：网络里一时没有 ≠ 缺料）</h2>
     * <p>序列装配的原料在任一时刻可能正压在<b>执行舱内部 / 机器 / 置物台</b>上（刚被推进去、
     * 还没被消耗），此时网络里当然是 0。只看网络存量必然把它误报成「缺少材料」。本方法给出
     * 「这份料此刻真的在产线上被用着」的只读结论，供缺料判定扣减。<b>判不出来一律 {@code false}</b>
     * （不武断地把真实缺料吞掉）；非总线输出模式（没有供料目标）时只看仓内。只读。</p>
     */
    public boolean pipelineSuppliesItem(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return false;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        final net.neoforged.neoforge.items.IItemHandler store = itemStorage();
        for (int slot = 0; slot < store.getSlots(); slot++) {
            final ItemStack stack = store.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return true;
            }
        }
        for (final BlockPos target : busSupplyTargets()) {
            if (supplyTargetHoldsItem(level, target, item)) {
                return true;
            }
        }
        return false;
    }

    /** 只读：该输入类别里有没有「起步原料」（Create 配方主原料）；有才算「会开一件新在制件」的类别。 */
    private boolean isStartIngredientCategory(final BusCategoryInfo info) {
        if (info == null) {
            return false;
        }
        for (final Item item : info.items()) {
            if (item != null && isStartIngredient(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读：本仓内部存储（统一视图 = 内部存储 + 磁盘缓存）里此刻是否至少有一件
     * 「本类别的过滤项真的推得出去」的物料。
     *
     * <h2>为什么需要它（用户要求：不要持续性的无效重复）</h2>
     * 输出总线的过滤项清单是一份<b>快照</b>，而搬运策略每 tick 都拿这份快照取料；仓里一件都没有时，
     * 就每 tick 判一次「仓里没有这种料」—— 实测 {@code RESOURCE_MISSING/chamber_empty} 对齿轮 228 次、
     * 对未完成黑曜石板 793 次纯空转。这里让清单只包含「此刻真的推得出去」的类别，
     * 于是这类空转从「每 tick 一次」降到「每次清单重算一次（≤ 1 秒）」。
     * <p>判据与推料侧严格同源（{@link #isNextForMyMachines} + {@link #busExportAcceptsForPush}），
     * 因此「清单里有它 ⇒ 推出去必然不是 chamber_empty」。只读，绝不搬运 / 销毁任何资源。</p>
     *
     * <p><b>2026-10-06：按步过滤项改用推料侧的放宽判据</b> —— 否则「同一台机器连续承担多个步」时，
     * 第 s+1 步的类别会因为仓里只有第 s 步的件而<b>整个类别都不下发</b>，总线连一次 {@code transfer}
     * 都收不到，推料侧再宽也救不回来（用户实测「压一次以后压不了第二次」的这一环就在这里）。</p>
     */
    private boolean hasExportableItem(final BusCategoryInfo info) {
        if (info == null) {
            return false;
        }
        // 流体输入类别：看内部流体罐（与物品侧同一语义；判据同推料侧「罐里有这种流体」）
        for (final FluidStack inTank : outputTank.getTanksSnapshot()) {
            if (!inTank.isEmpty() && info.fluids().contains(inTank.getFluid())) {
                return true;
            }
        }
        final net.neoforged.neoforge.items.IItemHandler chamberStore = itemStorage();
        final ItemResource stepFilter = info.hasStepFilter()
            ? ItemResource.ofItemStack(info.filterPrototype()) : null;
        for (int slot = 0; slot < chamberStore.getSlots(); slot++) {
            final ItemStack inSlot = chamberStore.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (stepFilter != null) {
                if (!busExportAcceptsForPush(inSlot, stepFilter)) {
                    continue; // 按步类别：物品 + 「本仓还要它」（步序不必逐位相同，见该方法的说明）
                }
            } else if (!info.items().contains(inSlot.getItem())) {
                continue;
            }
            if (isNextForMyMachines(inSlot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「起步原料」表的缓存入口（与类别表同一 {@link #BUS_SCHEDULE_INTERVAL_TICKS} tick 节拍重建）。
     * <p><b>为什么缓存</b>：推料侧每个过滤项每 tick 都会问一次；节拍与类别表一致，因此推料侧读到的
     * 口径与「本仓负责的步」表必然一致。</p>
     */
    private Set<Item> startIngredients() {
        Set<Item> cached = startIngredientsCache;
        if (cached == null) {
            final Level level = getLevel();
            cached = level == null ? Set.of() : computeStartIngredients(level);
            startIngredientsCache = cached;
        }
        return cached;
    }

    /**
     * <b>「起步原料」的唯一实现</b>（只读）：本仓负责的每条配方的主原料。
     * <p>推不出配方 / 配方没有主原料的样板<b>不参与</b>本表（保持既有行为，绝不靠猜）。</p>
     */
    private Set<Item> computeStartIngredients(final Level level) {
        final Set<Item> result = new LinkedHashSet<>();
        for (final String recipeId : ownedSteps().keySet()) {
            final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
            if (recipe == null) {
                continue; // 配方查不到（数据包改过 / 未加载完）：不参与本表
            }
            // <b>候选整组入表</b>（用户第 1 条）：标签型起步原料（{@code create:sleepers} 的三种台阶）
            // 的任一候选都算起步原料 —— 只认代表物会让其它候选既不被保护、也推不出归属配方。
            result.addAll(mainIngredientCandidates(recipe));
        }
        return result;
    }

    /** 只读：这份物品是不是本仓某一步的「步骤专用投入物」（= 该步除配方主原料以外的投入物品）。 */
    private boolean isStepExtraInput(final Item item) {
        for (final Map<Integer, Set<Item>> byStep : stepExtraInputs().values()) {
            for (final Set<Item> items : byStep.values()) {
                if (items.contains(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 只读：待加工件那一步要的「步骤专用投入物」集合（取不到 = 空集 = 本判据不表态）。 */
    private Set<Item> stepExtraInputsOfStep(final PendingStep pending) {
        final Map<Integer, Set<Item>> byStep = stepExtraInputs().get(pending.recipeId());
        if (byStep == null) {
            return Set.of();
        }
        final Set<Item> wanted = byStep.get(pending.step());
        return wanted == null ? Set.of() : wanted;
    }

    /**
     * 「步骤专用投入物」表的缓存入口（与类别表同一 {@link #BUS_SCHEDULE_INTERVAL_TICKS} tick 节拍重建）。
     * <p><b>为什么缓存</b>：输出总线每个过滤项每 tick 都会问一次，每次都去解析样板 NBT + 查配方 + 展开
     * ingredient 太浪费；节拍与类别表一致，因此推料侧读到的步序口径与类别表必然一致。</p>
     */
    private Map<String, Map<Integer, Set<Item>>> stepExtraInputs() {
        Map<String, Map<Integer, Set<Item>>> cached = stepExtraInputsCache;
        if (cached == null) {
            final Level level = getLevel();
            cached = level == null ? Map.of() : computeStepExtraInputs(level);
            stepExtraInputsCache = cached;
        }
        return cached;
    }

    /**
     * <b>「步骤专用投入物」的唯一实现</b>（只读）：{@code 配方 id → 单循环步序 → 该步投入的物品集合}。
     *
     * <p>口径：对 {@link #ownedSteps()} 里本仓负责的每一个「配方 + 步序」，取该步
     * {@link SequencedRecipeProbe#assemblyStepInputs}（下标 0 剔除过渡件后的主原料 + 下标 ≥1 的应用物），
     * 再<b>减去配方主原料</b>（{@link #mainIngredientOf}）—— 剩下的才是「必须由机械手拿着 / 由机器额外吃进」
     * 的步骤专用投入物。主原料（如坚固板的黑曜石粉）在任何步骤都可能需要，它是「启动产线的料」，
     * 绝不能按步拦截，因此被排除在本表之外（{@link #inputMaterialWantedNow} 对它会直接放行）。</p>
     * <p>推不出配方 / 配方里没有对应步序的样板<b>不参与</b>本表（保持既有行为，绝不靠猜）。</p>
     */
    private Map<String, Map<Integer, Set<Item>>> computeStepExtraInputs(final Level level) {
        final Map<String, Set<Integer>> owned = ownedSteps();
        if (owned.isEmpty()) {
            return Map.of();
        }
        final Map<String, Map<Integer, Set<Item>>> result = new LinkedHashMap<>();
        for (final Map.Entry<String, Set<Integer>> entry : owned.entrySet()) {
            final SequencedAssemblyRecipe recipe = assemblyById(level, entry.getKey());
            if (recipe == null) {
                continue; // 配方查不到（数据包改过 / 未加载完）：不参与本表
            }
            final List<SequencedRecipe<?>> sequence = recipe.getSequence();
            if (sequence.isEmpty()) {
                continue;
            }
            final Item transitional = recipe.getTransitionalItem().getItem();
            // 主原料按<b>候选集合</b>排除（标签型起步原料的任一候选都不是「步骤专用投入物」）
            final Set<Item> mainItems = new LinkedHashSet<>(mainIngredientCandidates(recipe));
            final Map<Integer, Set<Item>> byStep = new LinkedHashMap<>();
            for (final int step : entry.getValue()) {
                final int index = Math.floorMod(step, sequence.size());
                final Set<Item> extras = new LinkedHashSet<>();
                for (final ItemStack stack : SequencedRecipeProbe.assemblyStepInputs(
                    sequence.get(index).getRecipe(), transitional)) {
                    if (!stack.isEmpty() && !mainItems.contains(stack.getItem())) {
                        extras.add(stack.getItem());
                    }
                }
                if (!extras.isEmpty()) {
                    byStep.put(index, extras);
                }
            }
            if (!byStep.isEmpty()) {
                result.put(entry.getKey(), byStep);
            }
        }
        return result;
    }

    /** 按配方 id 取序列装配配方（只读；查不到 / 不是序列装配配方一律返回 {@code null}）。 */
    @org.jetbrains.annotations.Nullable
    private static SequencedAssemblyRecipe assemblyById(final Level level, final String recipeId) {
        final ResourceLocation key = ResourceLocation.tryParse(recipeId == null ? "" : recipeId);
        if (key == null) {
            return null;
        }
        try {
            return level.getRecipeManager().byKey(key)
                .map(net.minecraft.world.item.crafting.RecipeHolder::value)
                .filter(SequencedAssemblyRecipe.class::isInstance)
                .map(SequencedAssemblyRecipe.class::cast)
                .orElse(null);
        } catch (final RuntimeException ignored) {
            return null; // 配方系统异常（未加载完 / 版本差异）：当作「查不到」，绝不抛
        }
    }

    /**
     * 待加工件：{@code recipeId} = 过渡件所属的序列装配配方 id，{@code step} = <b>对循环数取模后</b>的步序
     * （与 {@link #computeStepExtraInputs} 的键域一致，见 {@link SequenceMaterialGuard#judgeStep}）。
     * <p>取模必须有：{@code loops = 5} 的配方里第 4 件次的进度步是 9…14，直接当键查会查不到，
     * 于是「查不到 = 不表态」会退化成「永远不拦」。</p>
     */
    private record PendingStep(String recipeId, int step) {
    }

    /**
     * 只读：本仓正在供料的机器 / 置物台<b>此刻最靠前的待加工件</b>
     * （= 机器上过渡件自己的 {@code create:sequenced_assembly} 配方 + 步序；判不出来 = {@code null}）。
     * <p>多台机器上都有过渡件时取<b>最小步</b>：那是产线上最靠前、也是下一个投入物要服务的那一件。
     * 每 tick 只真正读一遍（{@code pendingStepTick} 缓存）；客户端 / 未接入网络一律返回 {@code null}。
     * <p><b>只用于「全仓口径」</b>（备料 / 收回侧）。推料侧必须走 {@link #pendingStepOn(BlockPos)}
     * （按本总线面对的那一台机器判），否则多台机器处于不同步骤时，靠后的那台永远拿不到自己需要的投入物
     * —— 那正是「不是每种输出各一份，而是总体只出一份」。</p>
     */
    @org.jetbrains.annotations.Nullable
    private PendingStep pendingStepOnTargets() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        final long now = level.getGameTime();
        if (now == pendingStepTick) {
            return pendingStepCache;
        }
        SequencedAssembly best = null;
        for (final BlockPos target : busSupplyTargets()) {
            final SequencedAssembly candidate = firstAssemblyAt(level, target);
            if (candidate != null && (best == null || candidate.step() < best.step())) {
                best = candidate;
            }
        }
        pendingStepTick = now;
        pendingStepCache = toPendingStep(level, best);
        return pendingStepCache;
    }

    /**
     * 只读：<b>某一台</b>机器 / 置物台此刻的待加工件（{@code null} = 这台机器上没有过渡件 / 判不出来）。
     *
     * <h2>为什么必须按「这一台」判（用户硬要求）</h2>
     * 一条产线上每台机器所处的步骤并不相同（一台机械手在第 1 步、另一台在第 2 步）。推料侧若读「全仓并集
     * 取最小步」，那么「靠前那台要哪件」就成了<b>全仓唯一</b>的答案：靠后那台需要的投入物永远被拦下 ——
     * 用户看到的正是「不是每种输出各一份，而是总体只出一份」。因此本方法只看<b>这一台</b>的容器，
     * 各机器便能各自拿到自己那一步的投入物（不同输入可同时各一份）。
     *
     * <p><b>判据只按步骤</b>：读的是过渡件自己的 {@code create:sequenced_assembly}（配方 id + 步序对循环
     * 取模），<b>不做任何方块坐标比较</b>（{@code target} 只用来定位「哪一台机器」这件事本身）；
     * 每 tick 每台机器只真正读一遍容器（{@code pendingStepAtCache}）。只读，绝不搬运 / 销毁任何资源。</p>
     */
    @org.jetbrains.annotations.Nullable
    private PendingStep pendingStepOn(final BlockPos target) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || target == null) {
            return null;
        }
        final long now = level.getGameTime();
        if (now != pendingStepAtTick) {
            pendingStepAtTick = now;
            pendingStepAtCache.clear();
        }
        if (pendingStepAtCache.containsKey(target)) {
            return pendingStepAtCache.get(target); // 可能为 null（已判过：这台机器上没有在制件）
        }
        PendingStep result = toPendingStep(level, firstAssemblyAt(level, target));
        if (result == null) {
            // 机器<b>自己的</b>容器里没有在制件时，看一眼它「实际在加工的那一格」（见 #operandStepOf）。
            // <b>为什么必须有这一眼（用户实测最严重的问题之一）</b>：机械手（{@code create:deploying}）
            // 的容器里只有「手里拿着的那一件」，在制件根本不在它的容器里 —— 于是机械手永远「判不出
            // 当前步」，推料侧的按步闸门退化成「一律放行」，哪一件先出现在导出清单里就先塞进手里：
            // 用户看到的就是「上面那个拿的却是大齿轮，直接就是卡住了」（Create 的
            // {@code DeployerItemHandler} 对「手里已有不同物品」一律拒收，塞错一件整条线就永久卡死）。
            // 判不出（不是机械手 / 它对着的那一格也没有在制件与起步原料）时仍然返回 null（不表态）。
            result = operandStepOf(level, target);
        }
        pendingStepAtCache.put(target, result);
        return result;
    }

    /**
     * 只读：机器「实际在加工的那一格」此刻的待加工步（判不出返回 {@code null}）。
     *
     * <p><b>为什么不能只看机器自己的容器</b>：序列装配里真正被加工的件常常<b>不在机器里</b> ——
     * 机械手（{@code create:deploying}）把它手里的东西作用在它朝向的 2 格外的方块上
     * （Create {@code DeployerBlockEntity}：{@code worldPosition.relative(facing, 2)}，
     * 见 {@code shouldActivate} / {@code activate}），那一格通常就是置物台。因此这里：
     * <ol>
     *     <li>先看那一格上的<b>在制件</b>（进度组件）→ 它的「下一步」就是这台机器要在哪一步说话；</li>
     *     <li>没有在制件时再看那一格上有没有<b>还没开件的起步原料</b>（本仓某条配方的
     *     {@code ingredient}）→ 那就是「马上要起第 0 步」，该步的投入物（如精密构件的齿轮）
     *     必须先在手里。少了这一条，机械手会「手里空着等」，而置物台已经把起步原料放好了 ——
     *     产线根本起不来（这是「判不出就不推」这条严格判据的配套，缺一不可）。</li>
     * </ol>
     * 全程只读（能力查询 + 配方查询 + 读容器），绝不搬运 / 销毁任何资源。</p>
     */
    @org.jetbrains.annotations.Nullable
    private PendingStep operandStepOf(final Level level, final BlockPos machine) {
        final BlockPos operand = operatingPosOf(level, machine);
        if (operand == null) {
            return null;
        }
        final PendingStep inFlight = toPendingStep(level, firstAssemblyAt(level, operand));
        return inFlight != null ? inFlight : startStepAt(level, operand);
    }

    /**
     * 只读：该方块是不是<b>机械手</b>（{@code create:deploying} 的机器）；是则返回它
     * <b>实际作用的那一格</b>（Create {@code DeployerBlockEntity} 朝向的 2 格外），否则 {@code null}。
     *
     * <p>位移量「2」不是猜的：Create 的 {@code DeployerBlockEntity} 在
     * {@code shouldActivate(...)} / {@code activate()} 里恒用
     * {@code worldPosition.relative(getBlockState().getValue(FACING), 2)}，这里与它同一口径，
     * 因此不会出现「认错了机器正在加工哪一格」的第二套判定。</p>
     */
    @org.jetbrains.annotations.Nullable
    private static BlockPos operatingPosOf(final Level level, final BlockPos machine) {
        if (machine == null || !level.isLoaded(machine)) {
            return null;
        }
        final BlockState state = level.getBlockState(machine);
        if (!(state.getBlock()
            instanceof com.simibubi.create.content.kinetics.deployer.DeployerBlock)) {
            return null; // 不是机械手：它加工的件就在自己的容器里（既有路径已覆盖）
        }
        if (!state.hasProperty(
            com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING)) {
            return null; // 取不到朝向 → 不猜
        }
        return machine.relative(state.getValue(
            com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING), 2);
    }

    /**
     * 只读：这一格上放着「本仓某条配方<b>还没开件</b>的起步原料」时，那条配方的<b>第 0 步</b>。
     *
     * <p><b>为什么需要它</b>：起件那一瞬间，置物台上只有一份裸原料（没有 {@code
     * create:sequenced_assembly} 进度组件），此时「当前步」只能由「这份原料是哪条配方的 ingredient」
     * 反推为第 0 步 —— 否则机械手会因为没有在制件而被判成「判不出来」，手里那件投入物就永远进不去，
     * 产线永远停在「原料摆着、机械手空手」。判据只认<b>本仓负责的配方</b>（{@link #ownedSteps()}）
     * 且第 0 步也在本仓的配方；多条配方的起步原料是同一种物品（判不出是哪一条）时返回 {@code null}
     * （宁可不表态，也绝不猜）。只读，绝不搬运 / 销毁任何资源。</p>
     */
    @org.jetbrains.annotations.Nullable
    private PendingStep startStepAt(final Level level, final BlockPos pos) {
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, pos);
        if (handler == null) {
            return null;
        }
        final Map<String, Set<Integer>> owned = ownedSteps();
        if (owned.isEmpty()) {
            return null;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
                continue; // 在制件由前面的分支负责；这里只看「还没开件的裸起步原料」
            }
            PendingStep found = null;
            for (final Map.Entry<String, Set<Integer>> entry : owned.entrySet()) {
                if (!entry.getValue().contains(0)) {
                    continue; // 本仓不负责这条配方的起件步 → 这条配方与「起件」无关，本判据不表态
                }
                // <b>按配方闸门</b>（2026-10-05）：这条配方此刻没有活跃订单 ⇒ <b>不开件</b>。
                // 用户实测：一仓放两条 deploying 配方时，为「精密构件」下单会把「列车轨道」也带着开工。
                // 没有这一道，仓里只要残留一件别的配方的起步原料，就会被这轮订单顺手开件。
                if (!recipeOrdered(entry.getKey())) {
                    continue;
                }
                final SequencedAssemblyRecipe recipe = assemblyById(level, entry.getKey());
                // 起步原料按<b>候选集合</b>匹配（标签型起步原料的任一候选都算这条配方的起件原料）
                if (recipe == null || !mainIngredientCandidates(recipe).contains(inSlot.getItem())) {
                    continue;
                }
                if (found != null) {
                    return null; // 多条本仓配方的起步原料都是这个物品 → 判不出是哪一条，绝不猜
                }
                found = new PendingStep(entry.getKey(), 0);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 只读：某台机器容器里第一件过渡件的进度组件（没有 = {@code null}）。 */
    @org.jetbrains.annotations.Nullable
    private static SequencedAssembly firstAssemblyAt(final Level level, final BlockPos target) {
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, target);
        if (handler == null) {
            return null;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            final SequencedAssembly assembly = inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (assembly != null) {
                return assembly; // 过渡件：它的进度组件就是「待加工步」的来源
            }
        }
        return null;
    }

    /** 只读：把进度组件折算成「配方 id + 单循环步序」；判不出来（配方总步数未知）返回 {@code null}。 */
    @org.jetbrains.annotations.Nullable
    private PendingStep toPendingStep(final Level level,
                                      final @org.jetbrains.annotations.Nullable SequencedAssembly assembly) {
        if (assembly == null) {
            return null;
        }
        final int total = sequenceSize(level, assembly.id());
        if (total <= 0) {
            return null; // 判不出配方步数 → 不表态（绝不据此断供）
        }
        return new PendingStep(assembly.id().toString(), Math.floorMod(assembly.step(), total));
    }

    /**
     * 只读：把「每台供料目标此刻轮到哪一步」编成一个稳定字符串签名
     * （{@code target|recipe|step} 逐台拼接）。步序一变，签名必变。
     *
     * <p><b>用途</b>：{@code stepRefusalCooldowns} 的抑制窗口（
     * {@value #STEP_REFUSAL_COOLDOWN_TICKS} tick）远长于一次步序切换，若不随步序作废，
     * 「上一步不要它」这个结论会一直压到**下一步已经要它**的时候，机械手只能空手等
     * （用户实测 2~3 秒）。步序一变即清空抑制表，判据便与当前步序严格同步。
     * 每 tick 至多读一遍每台机器的容器（{@link #pendingStepOn(BlockPos)} 已有 tick 级缓存）。</p>
     */
    private String wantedStepSignature() {
        final StringBuilder sb = new StringBuilder(64);
        for (final BlockPos target : busSupplyTargets()) {
            final PendingStep pending = pendingStepOn(target);
            sb.append(Long.toHexString(target.asLong())).append('|');
            if (pending != null) {
                sb.append(pending.recipeId()).append('#').append(pending.step());
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /** 某个物品对应的「输入性产物」类别 id（取不到注册名时返回空串）。 */
    public static String inputCategoryId(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return "";
        }
        final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "" : RsccBusCategory.INPUT_PREFIX + key;
    }

    /** 某种流体对应的「流体输入」类别 id（取不到注册名时返回空串）。 */
    public static String fluidCategoryId(@org.jetbrains.annotations.Nullable final Fluid fluid) {
        if (fluid == null) {
            return "";
        }
        final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        return key == null ? "" : RsccBusCategory.FLUID_PREFIX + key;
    }

    /**
     * 「输入原料」类别的<b>配方限定</b> id = {@code input:<配方id>#<物品注册名>}。
     *
     * <h2>为什么必须带配方（2026-10-05 用户实测）</h2>
     * <p>用户原话：<i>「精密构件那一个地方应该只能显示铁粒，并且只能输出铁粒，只有列车轨道这里
     * 可以用铁粒或者锌粒」</i>。而旧 id 只含物品名（{@code input:minecraft:iron_nugget}）——
     * 「精密构件」与「列车轨道」都在机械手步声明了铁粒，于是注册候选时两条配方被并进<b>同一个</b>类别，
     * 精密构件那一格也被显示 / 过滤成「铁粒或锌粒」。</p>
     *
     * <p>带上配方 id 后，两条配方各有独立类别、独立候选集合，界面与 RS 过滤项都不再互相污染。
     * 类别 id 仍以 {@link RsccBusCategory#INPUT_PREFIX} 开头，因此所有既有判定
     * （{@code startsWith} / 类别数量统计 / 玩家勾选集合）无需改动即可继续工作。</p>
     */
    public static String inputCategoryIdForRecipe(final Item item,
                                                  @org.jetbrains.annotations.Nullable final String recipeId) {
        final String base = inputCategoryId(item);
        if (base.isEmpty() || recipeId == null || recipeId.isEmpty()) {
            return base; // 配方未知：退回旧 id（手工样板 / 老存档，行为与改动前一致）
        }
        return RsccBusCategory.INPUT_PREFIX + recipeId + "#"
            + base.substring(RsccBusCategory.INPUT_PREFIX.length());
    }

    /**
     * 某个物品对应的「产出侧」类别 id = {@code 前缀 + 物品注册名}。
     * <p>前缀取 {@link RsccBusCategory#RESULT_PREFIX}（成品）或 {@link RsccBusCategory#SCRAP_PREFIX}（废料）；
     * 与 {@link #inputCategoryId} 同一套拼法，因此界面上「同一物品的成品 / 废料 / 输入」永远是三个<b>不同</b>的
     * 类别 id，勾选互不影响（用户验收标准 #3：成品与废料要能分别显示与勾选）。</p>
     *
     * @return 拼好的 id；物品为 {@code null} 或取不到注册名时返回空串
     */
    public static String productCategoryId(final String prefix, @org.jetbrains.annotations.Nullable final Item item) {
        if (item == null || prefix == null || prefix.isEmpty()) {
            return "";
        }
        final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "" : prefix + key;
    }

    /**
     * 从单元样板 + Create 配方数据推出类别列表（纯计算）。
     *
     * <p><b>归属严格</b>：每个单元样板只认<b>它自己所属的那一条序列装配配方</b>
     * （{@link #resolveUnitRecipe}）—— 样板记了配方 id 就直接定位；没记 id 时只在
     * 「本仓其它样板记录过的配方 id」范围内、或全局<b>唯一</b>命中时才认，命中不唯一一律<b>不猜</b>。
     * 这样「冲压仓」不会再显示别的配方的过渡件（用户实测 bug 的根因：旧实现按配方类型全表反查，
     * 把「凡是含该步骤类型的配方」的过渡件全部合并进来，于是串到了坚固板配方的「未完成的黑曜石板」）。</p>
     *
     * <p><b>每个类别显示它真实对应的物品 / 流体</b>：输入原料类别用原料自己的图标与名字，
     * 中间产物类别用<b>过渡件物品自己的图标与名字</b>（不再用「中间产物」这类类别标签当名字）。</p>
     *
     * <p><b>冲压这类「除过渡件外没有输入」的步骤</b>：用<b>所属总样板的主原料</b>作输入原料
     * （{@link #mainIngredientOf}），而不是把过渡件本身误当输入。<b>但只允许第 1 步（stepIndex == 0）
     * 这么做</b> —— 起始原料只能在第 1 步对应的那次输出里出现，中间步骤绝不再输出它
     * （用户本轮硬要求：避免同一个起始原料被两台输出总线重复投喂）。</p>
     */
    private List<BusCategoryInfo> computeBusCategories() {
        final Level level = getLevel();
        if (level == null) {
            return List.of();
        }
        final List<UnitData> units = unitsForExport();
        // 本仓全部单元样板里记录过的「所属配方 id」：无 id 的样板只在唯一时借它做严格归属
        final Set<ResourceLocation> chamberRecipeIds = new LinkedHashSet<>();
        for (final UnitData unit : units) {
            final ResourceLocation id = ResourceLocation.tryParse(unit.recipe() == null ? "" : unit.recipe());
            if (id != null) {
                chamberRecipeIds.add(id);
            }
        }
        final java.util.TreeMap<String, ItemStack> inputs = new java.util.TreeMap<>(); // 按注册名升序，界面顺序稳定
        final java.util.TreeMap<String, FluidStack> fluids = new java.util.TreeMap<>(); // 同样按注册名升序
        /**
         * 类别 id → <b>该「输入原料组」的全部候选物品</b>（有序：首个 = 代表物 = 类别图标 / 每批所需量）。
         *
         * <h2>为什么必须整组登记（用户第 1 条：标签型输入原料）</h2>
         * <p>列车轨道的起步原料是标签 {@code create:sleepers}（石头台阶 / 平滑石台阶 / 安山岩台阶），
         * 机械手步的投入物是 {@code [铁粒, 锌粒]}。旧实现只把 {@code getItems()[0]} 登记成类别，
         * 于是输出总线的过滤项只有那一种代表物 —— 备料只拉代表物、其它候选到了机器旁被拒收
         * （用户原话：「它不止可以使用石头台阶，还可以用平滑石台阶、安山岩台阶……这几种东西都能够正常使用」）。
         * 这里把整组候选都放进<b>同一个</b>类别的 {@code items}：RS 的过滤项按候选逐个下发（任一都能拉 / 推），
         * 而「每批所需量」仍只有一份（整组算<b>一个</b>原料，不会把 3 个候选当成 3 份需求各拉一份）。</p>
         */
        final java.util.TreeMap<String, LinkedHashSet<Item>> inputCandidates = new java.util.TreeMap<>();
        /**
         * 过渡件按「<b>配方 + 归属步骤</b>」分桶（键 = {@code %04d|配方id}，按步序 → 配方 id 稳定排序）。
         *
         * <h2>为什么要带配方（用户第 3 条：中间产物必须按配方分开）</h2>
         * <p>旧实现只按步序分桶（{@code intermediate:2}），于是「坚固板的第 3 步（冲压）」与
         * 「列车轨道的第 3 步（冲压）」落进同一个类别：图标与「按步过滤原型」只有一份
         * （先注册的配方赢了），另一条配方的过渡件因此<b>永远导不出去</b> —— 用户实测
         * 「那个冲压的地方……输出的地方压根啥也没检测到」。带上配方后每条配方各有独立类别、
         * 独立图标、独立按步原型，界面上的分组也随之分开（客户端按配方标签复合分组，天然生效）。</p>
         */
        final java.util.TreeMap<String, LinkedHashSet<Item>> transitionalsByKey = new java.util.TreeMap<>();
        /**
         * 「配方 + 步序」→ <b>「按步过滤」原型</b>（过渡件 + 该步的 {@code create:sequenced_assembly} 进度组件）。
         * <p><b>为什么要有它（用户硬要求）</b>：同一配方可以把同一个处理器用在多个步骤上
         * （坚固板第 2、3 步都是冲压），此时两个步骤的过渡件是<b>同一个物品</b>。
         * 只按物品做过滤项时「勾第 N 步」与「勾第 N+1 步」会退化成同一个 {@code ItemResource}、
         * 互相覆盖（用户实测：只能输出一个中间产物）。带上进度步后两个类别才是两个互不覆盖的
         * 过滤项 —— 匹配点见 {@link #busExportMatches} 与 {@link #matchesCategoryStep}。</p>
         */
        final Map<String, ItemStack> stepPrototypes = new HashMap<>();
        /**
         * 「配方 + 步序」→ <b>该步用的机器</b>（如「动力冲压器」）。<b>仅用于界面标签行</b>
         * （「第 N 步 · 冲压」）：同一步序下的过渡件常常是同一个物品，只有把机器名写出来玩家才能
         * 一眼分清「第 2 步的件」与「第 3 步的件」（用户硬要求：不要靠堆一排物品格来区分）。
         * <p>取值见 {@link #recordStepMachine}（服务端只能取机器物品，Create 的描述文案是客户端专用）。</p>
         */
        final Map<String, Item> stepMachines = new HashMap<>();
        /** 「配方 + 步序」→ <b>复用键</b>（步骤类型 + 输入候选集合；同键 = 语义相同 ⇒ 可复用同一台机器）。 */
        final Map<String, String> reuseKeys = new HashMap<>();
        /** 成品 / 废料类别（id → 该项物品）：由所属配方的 {@code results} 池派生，本轮新增（用户验收标准 #3）。 */
        final java.util.TreeMap<String, ItemStack> results = new java.util.TreeMap<>();
        final java.util.TreeMap<String, ItemStack> scraps = new java.util.TreeMap<>();
        // 类别 id → 预估需求（每 1 个最终产物）：同一类别被多台单元引用时取最大值（保守口径）
        final Map<String, Long> estimated = new HashMap<>();
        for (final UnitData unit : units) {
            // 1) 严格归属：只认本单元所属的那一条配方
            final SequencedAssemblyRecipe recipe = resolveUnitRecipe(level, unit, chamberRecipeIds);
            final ItemStack transitionalStack = recipe == null ? ItemStack.EMPTY : recipe.getTransitionalItem();
            final Item transitional = transitionalStack.isEmpty() ? null : transitionalStack.getItem();
            final SequencedRecipe<?> step = recipe == null ? null : stepForUnit(recipe, unit);
            // 该单元对应「配方展开序列」里的步下标（identity 比较；找不到为 -1）
            final int stepIndex = step == null ? -1 : recipe.getSequence().indexOf(step);
            // 中间产物类别的「归属步序」：优先用样板自己配置的步（与推料 / 收回判定同一套步序域），
            // 样板没记步时才退回配方展开序列里的下标；两者都取不到 = -1（界面按「步骤未知」标注）。
            final int busStep = unit.step() >= 0 ? unit.step() : stepIndex;
            // 本单元所属配方 id（写类别 id / 复用键用；样板没记时退回配方自己解析出来的 id）
            final ResourceLocation unitRecipe = recipeIdOf(level, unit, recipe);
            final String recipeIdForCategory = unitRecipe != null ? unitRecipe.toString()
                : (unit.recipe() == null ? "" : unit.recipe());
            // 2) 样板自带标记的输入物（玩家 / JEI 明确指定）：算作本步的输入原料。
            //    <b>候选整组登记</b>：能在该步的配方里找到「包含它」的那个 ingredient 时，
            //    就把该 ingredient 的全部候选一起登记（玩家手填代表物也不会漏掉标签的其它候选）。
            final ItemStack declared = unit.input();
            if (declared != null && !declared.isEmpty()) {
                // <b>2026-10-05：这里原来漏了配方限定</b>，于是同一件物品被登记进
                // 「{@code input:<物品>}」（旧格式）与「{@code input:<配方>#<物品>}」<b>两个</b>类别，
                // 玩家在界面上就看到两份：旧格式那份<b>正是「原料里混进齿轮 / 大齿轮」与「锌粒和铁粒
                // 又出现」的来源</b>（实测快照 20261005-124450 chamber@-5,-60,6 两套并存）。
                // 修复：与下面 {@code bumpEstimated} 用同一个配方限定 id。
                addInputCategory(inputs, declared, recipeIdForCategory);
                registerInputCandidates(inputCandidates, step, transitional, declared,
                    recipeIdForCategory);
                bumpEstimated(estimated, inputCategoryIdForRecipe(declared.getItem(), recipeIdForCategory),
                    declared.getCount() * requirementMultiplier(recipe, declared.getItem()));
            }
            boolean hasOwnInput = false;
            if (step != null) {
                // 该步真正消耗的物品输入（<b>整组候选</b>；assemblyStepInputGroups 已剔除被 Create
                // 覆盖到下标 0 的过渡件，并把标签 / 多值列表展开成「代表物 + 全部候选」）
                for (final SequencedRecipeProbe.InputGroup group
                    : SequencedRecipeProbe.assemblyStepInputGroups(step.getRecipe(), transitional)) {
                    if (group.candidates().isEmpty()) {
                        continue;
                    }
                    hasOwnInput = true;
                    final ItemStack representative = group.representative();
                    addInputCategory(inputs, representative, recipeIdForCategory);
                    // <b>类别 id 按配方限定</b>（2026-10-05 用户实测：精密构件那里显示了铁粒 + 锌粒）。
                    // 旧 id 只含物品名（input:minecraft:iron_nugget），而「精密构件」与「列车轨道」
                    // 都在同一步声明了铁粒——两条配方的候选于是被并进<b>同一个</b>类别，
                    // 精密构件那一格也被显示成「铁粒或锌粒」（用户要求：精密构件只能铁粒，
                    // 只有列车轨道才铁粒或锌粒）。带上配方 id 后，两条配方各有自己的类别、
                    // 各自的候选集合，界面与过滤项都不再互相污染。
                    final String inputCategory = inputCategoryIdForRecipe(representative.getItem(), recipeIdForCategory);
                    registerInputCandidates(inputCandidates, inputCategory, group.candidates());
                    bumpEstimated(estimated, inputCategory,
                        representative.getCount() * requirementMultiplier(recipe, representative.getItem()));
                }
                for (final FluidStack stack : SequencedRecipeProbe.stepInputFluids(step.getRecipe())) {
                    hasOwnInput = true;
                    addFluidCategory(fluids, stack);
                    bumpEstimated(estimated, fluidCategoryId(stack.getFluid()),
                        stack.getAmount() * requirementMultiplier(recipe, null));
                }
            }
            // 3) 中间产物：严格来自所属配方的过渡件，绝不从其它配方反查；按「配方 + 归属步骤」分桶
            if (transitional != null) {
                if (busStep >= 0) {
                    // 该步的「按步过滤」原型 = 过渡件 + (配方 id, 步序) 的进度组件（同一配方同一步序只建一份）
                    final String key = intermediateKey(recipeIdForCategory, busStep);
                    transitionalsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(transitional);
                    if (!transitionalStack.isEmpty() && !stepPrototypes.containsKey(key)
                        && unitRecipe != null) {
                        stepPrototypes.put(key, stepPrototypeOf(transitionalStack, unitRecipe, busStep));
                    }
                    // 复用键（用户第 2 条）：步骤类型 + 输入候选集合（与配方无关）⇒ 语义相同的步骤同键
                    reuseKeys.putIfAbsent(key, stepReuseKey(step));
                } else {
                    // 步序未知（旧样板 / 配方查不到）：归入不带步序的旧类别（与旧存档兼容）
                    transitionalsByKey.computeIfAbsent(UNKNOWN_STEP_KEY, ignored -> new LinkedHashSet<>())
                        .add(transitional);
                }
                // 一台机器承担同类型的多个步时，<b>每个步骤各自出一个独立类别</b>（用户硬要求：
                // 「intermediate:1 与 intermediate:2 同时存在于同一台机器上」，两个类别互不顶掉）。
                // 数据源就是「本仓负责的步」（见 #computeOwnedSteps），因此这里不会混进别的仓负责的步。
                if (unit.recipe() != null && !unit.recipe().isEmpty()) {
                    for (final int ownedStep : ownedSteps().getOrDefault(unit.recipe(), Set.of())) {
                        if (ownedStep < 0) {
                            continue;
                        }
                        final String key = intermediateKey(recipeIdForCategory, ownedStep);
                        transitionalsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                            .add(transitional);
                        if (unitRecipe != null && !transitionalStack.isEmpty()
                            && !stepPrototypes.containsKey(key)) {
                            stepPrototypes.put(key,
                                stepPrototypeOf(transitionalStack, unitRecipe, ownedStep));
                        }
                        // 复用键按「该配方在该步序的处理器类型 + 输入候选集合」算：
                        // 同一台机器在同一条配方里承担的重复步骤天然同键（同类型同输入）。
                        if (recipe != null && ownedStep < recipe.getSequence().size()) {
                            reuseKeys.putIfAbsent(key,
                                stepReuseKey(recipe.getSequence().get(ownedStep)));
                        }
                    }
                }
            }
            // 4) 输入原料兜底：该步除过渡件之外没有任何输入（冲压这类只有 1 个 ingredient 的步骤）
            //    → 用「所属总样板的主原料」。<b>但只允许第 1 步（stepIndex == 0）这样做</b>：
            //    起始原料只能在第 1 步对应的那次输出里出现，中间步骤绝不再输出它（用户硬要求，避免重复投喂）。
            if (!hasOwnInput && recipe != null && stepIndex == 0) {
                final SequencedRecipeProbe.InputGroup main =
                    SequencedRecipeProbe.mainIngredientGroup(recipe.getIngredient());
                if (!main.candidates().isEmpty()) {
                    final ItemStack representative = main.representative();
                    addInputCategory(inputs, representative, recipeIdForCategory);
                    // 同 2)：起步原料类别也按配方限定，避免两条配方的主原料被并进同一个类别
                    final String mainCategory = inputCategoryIdForRecipe(representative.getItem(), recipeIdForCategory);
                    registerInputCandidates(inputCandidates, mainCategory, main.candidates());
                    bumpEstimated(estimated, mainCategory,
                        representative.getCount() * requirementMultiplier(recipe, representative.getItem()));
                }
            }
            // 5) 本步骤用哪台机器（仅中间产物类别的标签行显示用）
            recordStepMachine(step, intermediateKey(recipeIdForCategory, busStep), stepMachines);
            // 6) 成品 / 废料类别（本轮新增，用户验收标准 #3）：从所属配方的 results 池派生。
            //    与「中间产物」互不影响：成品不是过渡件，类别前缀不同（result: / scrap:），
            //    因此既不会和 intermediate:<step> 抢同一个过滤项，也不会互相顶掉。
            if (recipe != null) {
                addProductCategories(recipe, results, scraps);
            }
        }
        // <b>用户第 2 条（第 18 轮）：总样板的流程编排把某一步指派给了本仓 ⇒ 那一步的「类别」也要出现</b>
        // （哪怕本仓此刻没有<b>该配方</b>对应的单元样板 —— 但整条链一台样板都没有时不再补，
        // 见下方第 35 轮闸门：那种情形下这一遍的产物全部是「猜」出来的）。数据源 = 网络内序列装配
        // 样板库的<b>总样板</b>：它的每一步都记录了被指派到哪台机器
        // （{@link SequencePatternData.UnitEntry#machinePos()}）。
        //
        // <h2>第 20 轮收紧：这里<b>只</b>补「中间产物」这一个类别 —— 别的什么都不补（严重回归的必修项）</h2>
        // <p>为什么：<b>类别一旦显示出来，玩家就会去勾它；而勾中的类别会直接变成本仓的「备料需求」</b>
        // （{@link #fillInternalForBus} 按 {@code busCategories() ∩ busCategoryOwners} 生成目标量）。
        // 上一轮这里连该步的<b>输入类别</b>（如列车轨道的「石头台阶 / 铁粒」）与<b>成品 / 废料类别</b>都补了，
        // 玩家照着自己看到的去勾 ⇒ 本仓在<b>没有任何列车轨道订单</b>的情况下开始拉石头台阶 / 铁粒、
        // 推给机械手、并产出 {@code create:incomplete_track}（用户第 1 条原话：
        // 「我都没下单列车轨道，你怎么就开始组装起列车轨道来了？而且他还在一直输出」）。</p>
        // <p>为什么「只补中间产物」是安全的：中间产物类别不代表任何「要买什么料」的需求
        // （它不进 {@link #stepExtraStockTarget} 的每批需求，见 {@link #fillInternalForBus}），
        // 它只是让玩家<b>看得见、并能在总线上勾选</b>下一步骤要交接的过渡件；真正能不能动，
        // 仍由 {@link #ownedSteps()}（属主 = 本仓单元样板 + 运行中订单）决定。</p>
        //
        // <h2>第 35 轮闸门：整条链<b>一台单元样板都没有</b>时，这里一个字都不补（用户现场）</h2>
        // <p>用户原话：「如果说我这一个执行舱里面什么也不放……那个详细配置界面应该啥也不显示。
        // 准确来说，它的配方还是显示的，但是其他东西应该不显示。因为你只是知道他能够干这个配方
        // 而已 —— 的确我能干这事情，只是配方配上了。就比如说机械手装配，他不一定能把中间产物
        // 也配上。」</p>
        //
        // <p><b>为什么必须闸住</b>：本段补出来的类别 id = {@code intermediate:<配方id>:<步序>}，
        // 它需要「<b>具体是哪一条序列装配配方 + 第几步</b>」才能得出；而本仓一台单元样板都没有时，
        // 这两个事实<b>只</b>来自「总样板的机器指派」（= 玩家在样板终端里把配方配上了，见
        // {@link #patternAssignedSteps(Level)}）。「配方配上了」只说明这台机器<b>能</b>做这类加工
        // —— 配方 / 处理器类型这一层信息由执行仓自己的界面（配方类型 + 名字）显示，<b>不由类别快照
        // 提供</b> —— 并不说明它此刻真的要交接某一步的过渡件。而类别一旦画进总线的「类别详细配置」，
        // 玩家就会去勾；勾中又会被当成「本仓要这一步的东西」，等于把「能加工」误读成「已经在做」，
        // 正是用户否定的那件事。因此没有样板 ⇒ 类别表直接为空 ⇒ 详细配置界面啥也不显示。</p>
        //
        // <p><b>判据为什么取链级</b>：一条链 = 一个逻辑执行仓（见 {@link #chainCategories()}），
        // 因此这里问 {@link #chainHasAnyUnit()}（链上任意一台放着样板）而不是本台 {@code unitSlots}：
        // 链上任意一台有样板 ⇒ 整链照旧显示由它推出的类别，绝不按「当前这台空不空」决定整链显示。
        // 有样板时本行退化为原来的 {@link #patternAssignedSteps(Level)} ⇒ 既有行为逐字不变。</p>
        final Map<String, Set<Integer>> patternAssigned =
            chainHasAnyUnit() ? patternAssignedSteps(level) : Map.<String, Set<Integer>>of();
        for (final Map.Entry<String, Set<Integer>> assigned : patternAssigned.entrySet()) {
            final SequencedAssemblyRecipe recipe = assemblyById(level, assigned.getKey());
            final ResourceLocation recipeLocation = ResourceLocation.tryParse(assigned.getKey());
            if (recipe == null || recipeLocation == null) {
                continue;
            }
            final ItemStack transitionalStack = recipe.getTransitionalItem();
            final List<SequencedRecipe<?>> sequence = recipe.getSequence();
            if (transitionalStack.isEmpty() || sequence.isEmpty()) {
                continue;
            }
            final Item transitional = transitionalStack.getItem();
            for (final int step : assigned.getValue()) {
                if (step < 0 || step >= sequence.size()) {
                    continue;
                }
                final SequencedRecipe<?> sr = sequence.get(step);
                final String key = intermediateKey(assigned.getKey(), step);
                transitionalsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(transitional);
                stepPrototypes.putIfAbsent(key, stepPrototypeOf(transitionalStack, recipeLocation, step));
                reuseKeys.putIfAbsent(key, stepReuseKey(sr));
                recordStepMachine(sr, key, stepMachines);
                // 刻意<b>不</b>登记该步的输入 / 流体输入，也<b>不</b>登记成品 / 废料类别：
                // 那些类别一旦被勾中就会生成备料 / 收料需求，等于绕过「必须有人下单」这条硬底线。
            }
        }
        // 7) 过渡件不再作为「输入原料」重复出一项（用户实测：冲压仓两个类别一模一样）
        final Set<Item> allTransitionals = new LinkedHashSet<>();
        for (final Set<Item> stepItems : transitionalsByKey.values()) {
            allTransitionals.addAll(stepItems);
        }
        if (!allTransitionals.isEmpty()) {
            inputs.values().removeIf(stack -> allTransitionals.contains(stack.getItem()));
            // 候选集合同样要剔除过渡件：否则「该步除过渡件外没有输入」时会把过渡件当成输入候选，
            // 于是备料会把后续步骤的未完成件当成原料再拉一份（净效果 = 中间产物多出来）。
            for (final java.util.Iterator<Map.Entry<String, LinkedHashSet<Item>>> it =
                 inputCandidates.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<String, LinkedHashSet<Item>> entry = it.next();
                entry.getValue().removeAll(allTransitionals);
                if (entry.getValue().isEmpty() || !inputs.containsKey(entry.getKey())) {
                    it.remove(); // 候选被清空 / 该类别已不存在（输入被过渡件占满）⇒ 整组去掉
                }
            }
        }
        final List<BusCategoryInfo> result =
            new ArrayList<>(inputs.size() + fluids.size() + results.size() + scraps.size() + 1);
        for (final Map.Entry<String, ItemStack> entry : inputs.entrySet()) {
            final ItemStack stack = entry.getValue();
            // <b>items = 整组候选</b>（首个 = 代表物 = 图标 / 每批所需量）：RS 的过滤项按候选逐个下发，
            // 于是「标签型输入原料」的任一候选都能被拉进仓、推给机器（用户第 1 条）。
            final List<Item> candidates = candidateListOf(inputCandidates, entry.getKey(), stack.getItem());
            result.add(new BusCategoryInfo(entry.getKey(), stack.getItem(), null, "",
                candidates, List.of(), stack.getCount(),
                estimated.getOrDefault(entry.getKey(), (long) stack.getCount()), ItemStack.EMPTY, null));
        }
        for (final Map.Entry<String, FluidStack> entry : fluids.entrySet()) {
            final FluidStack stack = entry.getValue();
            result.add(new BusCategoryInfo(entry.getKey(), null, stack.getFluid(), "",
                List.of(), List.of(stack.getFluid()), stack.getAmount(),
                estimated.getOrDefault(entry.getKey(), (long) stack.getAmount()), ItemStack.EMPTY, null));
        }
        if (!transitionalsByKey.isEmpty()) {
            // 中间产物：每个「<b>配方 + 归属步序</b>」一项独立类别
            // （id = intermediate:&lt;配方id&gt;:&lt;步序&gt;；步序未知 = intermediate）。
            // 同一过渡件跨多步时会在多个类别里各出现一次 —— 这正是用户要的「按步骤区分 + 可整选某一步」；
            // 过滤项 = 该步的「按步原型」（过渡件 + 进度组件，见 #stepPrototypes），
            // 因此「第 N 步」与「第 N+1 步」是两个互不覆盖的过滤项（即使两者是同一个物品）。
            // 顺序：TreeMap 的键是 {@code %04d|配方id} ⇒ 已知步序升序、同一步序内按配方 id 升序，稳定可复现。
            for (final Map.Entry<String, LinkedHashSet<Item>> entry : transitionalsByKey.entrySet()) {
                if (UNKNOWN_STEP_KEY.equals(entry.getKey())) {
                    continue; // 步序未知留到最后单独出（界面上「步骤未知」不应抢在「第 1 步」前面）
                }
                final List<Item> items = new ArrayList<>(entry.getValue());
                final String recipeId = recipeIdOfKey(entry.getKey());
                final int step = stepOfKey(entry.getKey());
                // 名称 / 图标一律用过渡件物品自己的（labelKey 空串 → 界面回退到物品真实名字）；
                // stepMachine 供界面标签行写「第 N 步 · 冲压」（取不到 = null → 退回「第 N 步的中间产物」）。
                result.add(new BusCategoryInfo(RsccBusCategory.intermediateId(recipeId, step),
                    items.get(0), null, "", items, List.of(), 1L, 0L,
                    stepPrototypes.getOrDefault(entry.getKey(), ItemStack.EMPTY),
                    stepMachines.get(entry.getKey()),
                    reuseKeys.getOrDefault(entry.getKey(), "")));
            }
            final LinkedHashSet<Item> unknown = transitionalsByKey.get(UNKNOWN_STEP_KEY);
            if (unknown != null && !unknown.isEmpty()) {
                final List<Item> items = new ArrayList<>(unknown);
                result.add(new BusCategoryInfo(RsccBusCategory.INTERMEDIATE, items.get(0), null, "",
                    items, List.of(), 1L, 0L, ItemStack.EMPTY, null));
            }
        }
        // 成品 / 废料：排在最后（界面上与输入 / 中间产物各成一组，互不混杂）。
        // 勾选语义与其他类别一致：输出总线勾中 = 把该产出的物品推给机器；输入总线勾中 = 从机器 / 仓里收回。
        for (final Map.Entry<String, ItemStack> entry : results.entrySet()) {
            final ItemStack stack = entry.getValue();
            result.add(new BusCategoryInfo(entry.getKey(), stack.getItem(), null, "",
                List.of(stack.getItem()), List.of(), 1L, 0L, ItemStack.EMPTY, null));
        }
        for (final Map.Entry<String, ItemStack> entry : scraps.entrySet()) {
            final ItemStack stack = entry.getValue();
            result.add(new BusCategoryInfo(entry.getKey(), stack.getItem(), null, "",
                List.of(stack.getItem()), List.of(), 1L, 0L, ItemStack.EMPTY, null));
        }
        return result;
    }

    /**
     * 记录「第 N 步用哪台机器」（<b>仅用于中间产物类别的标签行</b>）。
     *
     * <p><b>为什么不用 Create 的 {@code IAssemblyRecipe#getDescriptionForAssembly()}</b>：
     * 它带 {@code @OnlyIn(CLIENT)}（服务端 jar 里被剥离，调用即抛 {@code NoSuchMethodError}），
     * 而类别表是<b>服务端</b>算出来的。{@code addRequiredMachines} 两端都可用，因此这里取它给出的
     * 第一台机器（如 {@code create:mechanical_press}），客户端再用该物品的悬浮名渲染出「冲压 / 注液」这种短名
     * —— 数据来源是配方本身，不依赖任何写死的映射表。</p>
     *
     * <p>取不到（附属模组的自定义装配配方没实现 {@code IAssemblyRecipe} / 抛异常）时什么都不记，
     * 界面自动退回「第 N 步的中间产物」文案，绝不影响类别表本身的生成。</p>
     */
    private static void recordStepMachine(@org.jetbrains.annotations.Nullable final SequencedRecipe<?> step,
                                          final String stepKey, final Map<String, Item> stepMachines) {
        if (step == null || stepKey == null || UNKNOWN_STEP_KEY.equals(stepKey)
            || stepMachines.containsKey(stepKey)) {
            return;
        }
        try {
            final Set<net.minecraft.world.level.ItemLike> machines = new LinkedHashSet<>();
            step.getAsAssemblyRecipe().addRequiredMachines(machines);
            for (final net.minecraft.world.level.ItemLike machine : machines) {
                final Item item = machine == null ? null : machine.asItem();
                if (item != null) {
                    stepMachines.put(stepKey, item);
                    return;
                }
            }
        } catch (final RuntimeException ignored) {
            // 自定义装配配方实现不完整（未实现 IAssemblyRecipe 的方法）：不显示机器名，绝不抛
        }
    }

    // ==================== 「配方 + 步序」复合键 / 候选组 / 复用键（本轮新增） ====================

    /**
     * 「步序未知」的复合键（步序取不到时用它，对应不带步序的旧类别 {@code intermediate}）。
     * <p>键格式 = {@code %08d|配方id}，因此 {@link java.util.TreeMap} 会先按步序升序、再按配方 id 排序，
     * 界面顺序稳定可复现；{@code -1} 只作为「未知」哨兵，不参与排序上的语义。</p>
     */
    private static final String UNKNOWN_STEP_KEY = "-1|";

    /** 拼「配方 + 步序」复合键（步序 &lt; 0 或配方未知 = {@link #UNKNOWN_STEP_KEY}）。 */
    private static String intermediateKey(@org.jetbrains.annotations.Nullable final String recipeId,
                                         final int step) {
        if (step < 0 || recipeId == null || recipeId.isEmpty()) {
            return UNKNOWN_STEP_KEY;
        }
        return String.format("%08d|%s", step, recipeId);
    }

    /** 复合键 → 配方 id（未知键返回空串）。 */
    private static String recipeIdOfKey(final String key) {
        if (key == null || UNKNOWN_STEP_KEY.equals(key)) {
            return "";
        }
        final int bar = key.indexOf('|');
        return bar < 0 ? "" : key.substring(bar + 1);
    }

    /** 复合键 → 步序（未知键返回 -1）。 */
    private static int stepOfKey(final String key) {
        if (key == null || UNKNOWN_STEP_KEY.equals(key)) {
            return -1;
        }
        final int bar = key.indexOf('|');
        if (bar <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(0, bar));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * <b>「语义相同的中间步骤」复用键</b>（用户第 2 条）：{@code 步骤类型 + 该步应用物的全部候选集合}。
     *
     * <h2>判据与「什么算相同 / 不算」</h2>
     * <ul>
     *     <li><b>相同 ⇒ 可复用</b>：步骤类型（{@code create:pressing} 等注册 id）一致，且该步的
     *     <b>应用物候选集合</b>（下标 ≥1 的 ingredient 全部候选，按注册名排序）完全一致 ——
     *     例如「列车轨道的冲压」与「坚固板的冲压」（都是 {@code create:pressing} 且都不吃应用物）、
     *     「列车轨道的第 1、2 步装铁粒」（都是 {@code create:deploying} 且都是{铁粒|锌粒}）；
     *     此时用户可以让<b>同一台机器 / 同一条总线</b>同时服务这两步（类别仍按配方分开，见
     *     {@link RsccBusCategory#intermediateId(String, int)}）。</li>
     *     <li><b>不相同 ⇒ 不可复用</b>：类型不同，或输入集合不同 —— 例如列车的装铁粒（铁粒|锌粒）
     *     与精密构件的装铁粒（只要铁粒）：候选集合不同，键不同，因此<b>绝不会</b>被当作同一步；
     *     界面/日志据此明确「这两步不是同一个语义」，避免用户误配导致推错料。</li>
     *     <li>步骤类型取不到 / 步骤为 {@code null} ⇒ 空串（不表态，界面不显示可复用提示）。</li>
     * </ul>
     * <p>只读，纯计算，不缓存（每个单元每 20 tick 算一次，开销可忽略）。</p>
     */
    private static String stepReuseKey(@org.jetbrains.annotations.Nullable final SequencedRecipe<?> step) {
        if (step == null) {
            return "";
        }
        final String type = SequencedRecipeProbe.recipeTypeId(step.getRecipe());
        if (type == null || type.isEmpty()) {
            return "";
        }
        final List<String> inputs = new ArrayList<>(4);
        for (final Item item : SequencedRecipeProbe.stepApplicationCandidates(step.getRecipe())) {
            final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (key != null) {
                inputs.add(key.toString());
            }
        }
        java.util.Collections.sort(inputs);
        final List<String> fluids = new ArrayList<>(2);
        for (final FluidStack stack : SequencedRecipeProbe.stepInputFluids(step.getRecipe())) {
            final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.FLUID
                .getKey(stack.getFluid());
            if (key != null) {
                fluids.add(key + "x" + stack.getAmount());
            }
        }
        java.util.Collections.sort(fluids);
        return type + "|" + String.join(",", inputs) + "|" + String.join(",", fluids);
    }

    /**
     * 登记一个「输入原料组」的全部候选（去重、保序；首个 = 代表物）。
     * <p>与 {@link #addInputCategory} 分工：后者只登记<b>每批所需量 / 图标</b>（代表物一份），
     * 本方法登记<b>匹配用的整组候选</b> —— 两者合成一个类别（id 相同）。</p>
     */
    private static void registerInputCandidates(final Map<String, LinkedHashSet<Item>> inputCandidates,
                                                final String categoryId, final List<Item> candidates) {
        if (categoryId == null || categoryId.isEmpty() || candidates == null || candidates.isEmpty()) {
            return;
        }
        final LinkedHashSet<Item> target = inputCandidates.computeIfAbsent(categoryId,
            ignored -> new LinkedHashSet<>());
        for (final Item item : candidates) {
            if (item != null) {
                target.add(item);
            }
        }
    }

    /**
     * 据「样板自带标记的输入物」补齐候选组：在该步的配方里找到<b>包含它的那个 ingredient</b>，
     * 把该 ingredient 的全部候选一起登记（id 仍用玩家标记的那件物品 ⇒ 不会多出一个重复类别）。
     * <p>找不到（手工样板 / 配方查不到）时只登记它自己（保持既有行为）。
     * <b>只读</b>：只算一个本地映射，不搬运任何资源。</p>
     */
    private static void registerInputCandidates(final Map<String, LinkedHashSet<Item>> inputCandidates,
                                                final SequencedRecipe<?> step,
                                                final Item transitional, final ItemStack declared,
                                                @org.jetbrains.annotations.Nullable final String recipeId) {
        if (declared == null || declared.isEmpty()) {
            return;
        }
        // 配方限定 id（与 addInputCategory / bumpEstimated 同源）——旧写法只有物品名，
        // 会让同一件物品同时存在「旧格式」与「配方限定」两个类别。
        final String categoryId = inputCategoryIdForRecipe(declared.getItem(), recipeId);
        if (step != null) {
            for (final SequencedRecipeProbe.InputGroup group
                : SequencedRecipeProbe.assemblyStepInputGroups(step.getRecipe(), transitional)) {
                if (group.candidates().contains(declared.getItem())) {
                    registerInputCandidates(inputCandidates, categoryId, group.candidates());
                    return;
                }
            }
        }
        registerInputCandidates(inputCandidates, categoryId, List.of(declared.getItem()));
    }

    /**
     * <b>只读</b>：某个类别的候选物品注册名，拼成 {@code ,} 分隔串（供界面轮播 / 显示）。
     *
     * <p>空串 = 「只有图标那一件」（中间产物 / 成品 / 废料都是这一类）。
     * 顺序与 {@link BusCategoryInfo#items()} 一致，首个 = 代表物 = 类别图标。</p>
     */
    private static String categoryCandidateIds(final BusCategoryInfo info) {
        if (info == null || info.items() == null || info.items().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(64);
        for (final net.minecraft.world.item.Item item : info.items()) {
            if (item == null) {
                continue;
            }
            final net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        return sb.toString();
    }

    /** 类别 id → 候选列表（保证非空、首个 = 代表物；缺失时回退单元素代表物）。 */
    private static List<Item> candidateListOf(final Map<String, LinkedHashSet<Item>> inputCandidates,
                                              final String categoryId, final Item representative) {
        final LinkedHashSet<Item> found = inputCandidates.get(categoryId);
        if (found == null || found.isEmpty()) {
            return representative == null ? List.of() : List.of(representative);
        }
        final List<Item> result = new ArrayList<>(found);
        if (representative != null && result.remove(representative)) {
            result.add(0, representative); // 代表物必须是首个（图标 / 每批所需量口径一致）
        }
        return result;
    }

    // ==================== 「输入原料组」可互换候选（用户第 1 条：整组视为一个原料） ====================

    /**
     * <b>只读</b>：本仓「输入原料组」里与 {@code item} <b>可互换</b>的其它候选物品。
     *
     * <h2>为什么需要它（用户第 1 条：标签型输入原料要「视为一个原料」）</h2>
     * <p>一个 ingredient 的多个候选是<b>一个</b>原料：备料任一即可、缺料任一有货即不缺。
     * 但「可互换」必须<b>再收窄一步</b>，否则会误判跨配方的共用候选：列车轨道的
     * 装铁粒候选是{铁粒, 锌粒}，而精密构件的装铁粒只要铁粒 —— 两者共用同一个
     * {@code input:minecraft:iron_nugget} 类别。若把「锌粒在网」当成「铁粒不缺」，
     * 精密构件那条线就会在真缺铁粒时被静默饿死。因此可互换只在下面两种情形成立：</p>
     * <ol>
     *     <li><b>起步原料组</b>：两者都属于本仓某条配方的起步原料候选（开新件只看工位空不空，
     *     任一份台阶都能起件）；</li>
     *     <li><b>同一工位当前待加工步同时接受两者</b>（{@link #stepExtraInputsOfStep}）——
     *     也就是说这一刻它们确实任选其一都能推进那一步；</li>
     *     <li><b>「配方上下文包含关系」（R1，本轮新增）</b>：{@code item} 此刻<b>被哪几条有订单的配方
     *     当成投入物</b>，而 {@code other} 必须<b>把这几条全都覆盖</b>
     *     （见 {@link #coversItemNeeds}）。</li>
     * </ol>
     * <p><b>第 ③ 条为什么必须单独存在（用户原话：「铁粒和锌粒混杂在一起的问题仍然还没有解决」）</b>：
     * 第 ② 条问的是「工位此刻要什么」，而<b>没有工位在等料的那一刻</b>（产线刚起、机器空着、
     * 上一件刚被消耗）根本没有 {@code PendingStep} 可问，于是只靠第 ① ② 条时
     * 「锌粒在网」照样会被当成「铁粒不缺」—— 精密构件那条线在真缺铁粒时被静默饿死。
     * 第 ③ 条把判据换成<b>配方上下文</b>：精密构件只把「铁粒」写进它的 ingredient，
     * 因此锌粒覆盖不了它的需求（{@code coversItemNeeds(铁粒, 锌粒) == false}）；而列车轨道那一步的
     * 同一个 ingredient 就写着{铁粒, 锌粒}，因此锌粒能覆盖它（{@code == true}）。</p>
     * <p>三种都不成立 ⇒ 返回空集（不表态），调用方按「各自的独立需求」处理。<b>只读</b>，
     * 不搬运 / 不修改任何资源；判不出来一律空集（绝不因判不出来而少报或拒绝供料）。</p>
     */
    private Set<Item> interchangeableCandidates(@org.jetbrains.annotations.Nullable final Item item) {
        final Set<Item> result = new LinkedHashSet<>();
        if (item == null) {
            return result;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.isInput() || info.isFluidInput() || info.items().size() < 2
                || !info.items().contains(item)) {
                continue;
            }
            for (final Item other : info.items()) {
                if (other != null && other != item && acceptsInterchangeably(item, other)) {
                    result.add(other);
                }
            }
        }
        return result;
    }

    /** 只读：这一刻 {@code a} 与 {@code b} 是否「任一件都能用」（起步原料组，或某工位当前步同时接受两者）。 */
    private boolean acceptsInterchangeably(final Item a, final Item b) {
        if (isStartIngredient(a) && isStartIngredient(b)) {
            return true; // 同一配方组的起步原料候选：任一份都能起件
        }
        for (final BlockPos target : busSupplyTargets()) {
            final PendingStep pending = pendingStepOn(target);
            if (pending == null) {
                continue;
            }
            final Set<Item> wanted = stepExtraInputsOfStep(pending);
            if (wanted.contains(a) && wanted.contains(b)) {
                return true; // 这台机器这一刻任一件都能推进（配方上下文见 coversItemNeeds）
            }
            // <b>R1：工位这一刻同时接受两者，仍要再问一次「配方上下文」</b>。
            // 为什么不能就此放行：两条配方共用一批机械手时，工位上的 {@code PendingStep} 只代表
            // <b>那一刻</b>那一件在加工哪条配方；而备料要服务的还有另一条同样有订单的配方
            // （用户现场：精密构件与列车轨道共用机械手）。此时若只按「本步接受两者」放行，
            // 另一条配方的需求就被这次「已满足」吞掉了。含关系成立才放行，因此
            // 「精密构件要铁粒 + 轨道要铁粒」这一对仍会被正确拦下（锌粒覆盖不了精密构件）。
            if (wanted.contains(a) && wanted.contains(b) && coversItemNeeds(a, b)) {
                return true;
            }
        }
        return coversItemNeeds(a, b);
    }

    // ==================== R1：配方上下文的「候选包含关系」（唯一实现） ====================

    /**
     * <b>只读</b>：本仓「有订单」的配方里，每一步的<b>全部 ingredient 候选组</b>
     * （{@code 配方 id → 物品 → 它所属的全部候选组}）。
     *
     * <h2>口径为什么必须分「第 1 步」与「其后各步」（与 {@code computeBusCategories} 同源）</h2>
     * <ul>
     *     <li><b>第 1 步（{@code index == 0}）</b>：Create 会把下标 0 覆盖成「过渡件 + 主原料」的复合，
     *     而 {@link #computeBusCategories} 登记这一步的输入类别时，用的是
     *     <b>主原料候选组</b>（{@link #mainIngredientCandidates}）+ {@code assemblyStepInputGroups}
     *     里除过渡件之外的组。这里<b>逐字照抄同一份来源</b>，否则「第 0 步的候选包含关系」会与
     *     类别表里显示的候选不一致。</li>
     *     <li><b>其后各步</b>：与 {@link #computeStepExtraInputs} 同源
     *     （{@link SequencedRecipeProbe#assemblyStepInputGroups}）。</li>
     * </ul>
     * <p><b>只收有订单的配方</b>（{@link #recipeOrdered(String)}）：用户现场是「同一个铁粒 / 锌粒
     * 标签同时出现在两条配方的步里」，而同一时刻可能只有一条有订单 —— 只按有订单的那几条做包含关系，
     * 才不会让「没人下单的那条配方」把候选集合稀释掉（那正是旧的「混在一起」）。</p>
     * <p>结果按类别表的同一节拍缓存（{@link #inputCouplingCache}），取配方失败一律跳过、绝不抛。</p>
     */
    private Map<String, Map<Item, List<List<Item>>>> inputCoupling() {
        Map<String, Map<Item, List<List<Item>>>> cached = inputCouplingCache;
        if (cached != null) {
            return cached;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return Map.of(); // 判不出来 ⇒ 空表 ⇒ 候选包含关系不表态（调用方退回既有行为）
        }
        final Map<String, Map<Item, List<List<Item>>>> result = new LinkedHashMap<>();
        for (final Map.Entry<String, Set<Integer>> entry : ownedSteps().entrySet()) {
            if (!recipeOrdered(entry.getKey())) {
                continue; // 没有活跃订单的配方不参与：它此刻不该影响任何一件料的「算不算已有」
            }
            final SequencedAssemblyRecipe recipe = assemblyById(level, entry.getKey());
            if (recipe == null) {
                continue; // 配方查不到（数据包未加载 / 版本差异）：跳过，绝不因此断供
            }
            final List<SequencedRecipe<?>> sequence = recipe.getSequence();
            if (sequence.isEmpty()) {
                continue;
            }
            final Item transitional = recipe.getTransitionalItem().getItem();
            final Map<Item, List<List<Item>>> byItem = new LinkedHashMap<>();
            // 第 1 步：主原料候选组（与 computeBusCategories 登记起步原料类别时同一份来源）
            final List<Item> mainCandidates = mainIngredientCandidates(recipe);
            if (!mainCandidates.isEmpty()) {
                addCouplingGroup(byItem, mainCandidates);
            }
            for (final int step : entry.getValue()) {
                final int index = Math.floorMod(step, sequence.size());
                for (final SequencedRecipeProbe.InputGroup group
                    : SequencedRecipeProbe.assemblyStepInputGroups(sequence.get(index).getRecipe(),
                        transitional)) {
                    addCouplingGroup(byItem, group.candidates());
                }
            }
            if (!byItem.isEmpty()) {
                result.put(entry.getKey(), byItem);
            }
        }
        cached = result;
        inputCouplingCache = cached;
        return cached;
    }

    /** 把一组候选登记进「物品 → 它所属的候选组」（组按内容去重；空组 / 单件组也登记，它们就是「不宽」的证据）。 */
    private static void addCouplingGroup(final Map<Item, List<List<Item>>> byItem, final List<Item> group) {
        if (group == null || group.isEmpty()) {
            return;
        }
        final List<Item> normalized = new ArrayList<>(group.size());
        for (final Item item : group) {
            if (item != null && !normalized.contains(item)) {
                normalized.add(item);
            }
        }
        if (normalized.isEmpty()) {
            return;
        }
        for (final Item item : normalized) {
            final List<List<Item>> groups = byItem.computeIfAbsent(item, ignored -> new ArrayList<>(2));
            if (!groups.contains(normalized)) {
                groups.add(normalized);
            }
        }
    }

    /**
     * 只读：配方 {@code recipeId} 里 {@code item} 所属的全部候选组
     * （{@link #inputCoupling()} 的「配方 → 物品 → 组」三级表的取值口）。
     */
    private List<List<Item>> itemCouplingGroups(final String recipeId, final Item item) {
        if (recipeId == null || item == null) {
            return List.of();
        }
        final Map<Item, List<List<Item>>> byItem = inputCoupling().get(recipeId);
        if (byItem == null) {
            return List.of();
        }
        final List<List<Item>> groups = byItem.get(item);
        return groups == null ? List.of() : groups;
    }

    /**
     * 只读：此刻<b>有订单</b>的配方里，把 {@code item} 当成投入物的那些配方 id。
     * <p><b>唯一实现</b>：{@link #coversItemNeeds} 与自检脚本的等价模型都读它，
     * 因此「哪几条配方要这块料」只有一个答案。</p>
     */
    private Set<String> itemNeededRecipes(final Item item) {
        final Set<String> result = new LinkedHashSet<>();
        if (item == null) {
            return result;
        }
        for (final Map.Entry<String, Map<Item, List<List<Item>>>> entry : inputCoupling().entrySet()) {
            if (entry.getValue().containsKey(item)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * <b>R1 的唯一判据（只读）</b>：{@code other} 能不能把 {@code item} 此刻的<b>全部</b>需求顶下来。
     *
     * <h2>判据（一句话）</h2>
     * <p>「本仓把 {@code item} 当成投入物的每一条有订单配方里，{@code item} 所属的每一个 ingredient
     * 候选组都必须<b>同样包含</b> {@code other}」。</p>
     *
     * <h2>用它验算用户给的两条规则（就是需求里的原话）</h2>
     * <pre>
     *   精密构件只铁粒：唯一配方 create:precision_mechanism 的装铁粒 ingredient = [铁粒]（单候选）
     *     itemNeededRecipes(铁粒) = {precision_mechanism}
     *     组 [铁粒] 不含锌粒  ⇒ coversItemNeeds(铁粒, 锌粒) = false
     *     ⇒ 锌粒在网也不能算「铁粒不缺」：只能取铁粒（R1 第一条）。
     *
     *   列车轨道优先取有的那一个：create:track 第 0/1 步的 ingredient = [铁粒, 锌粒]（同一 ingredient 两候选）
     *     itemNeededRecipes(铁粒) = {track}
     *     组 [铁粒, 锌粒] 含锌粒 ⇒ coversItemNeeds(铁粒, 锌粒) = true
     *     ⇒ 网络里有锌粒时不必再抽铁粒（R1 第二条：「有锌粒就优先取有货的那一个」）。
     *     反向 coversItemNeeds(锌粒, 铁粒) 同样为真 ⇒ 两边对称，谁有货就取谁。
     *
     *   两条配方<b>同时</b>有订单（共用同一批机械手，用户抱怨的「混杂」现场）
     *     itemNeededRecipes(铁粒) = {precision_mechanism, track}
     *     锌粒只覆盖 track、覆盖不了 precision_mechanism ⇒ coversItemNeeds(铁粒, 锌粒) = false
     *     ⇒ 依然会去取铁粒（不会因为「轨道能用锌」就把精密构件饿死）；
     *       而锌粒本身作为它的组的代表，走的是同一条判据 ⇒ 谁先有货取谁、两边都不缺料。
     * </pre>
     *
     * <p><b>判不出来一律「覆盖不了」（返回 false）</b>：不表态时退回既有行为
     * （各自的独立需求都要满足），因此绝不会因为读不到配方而断供 —— 用户硬要求 R2。</p>
     */
    private boolean coversItemNeeds(final Item item, final Item other) {
        if (item == null || other == null) {
            return false;
        }
        if (item == other) {
            return true;
        }
        final Set<String> needed = itemNeededRecipes(item);
        if (needed.isEmpty()) {
            return false; // 这一刻没有任何有订单的配方要它 ⇒ 不存在「被覆盖」这回事
        }
        for (final String recipe : needed) {
            final List<List<Item>> groups = itemCouplingGroups(recipe, item);
            if (groups.isEmpty()) {
                // 配方数据缺一块（配方查不到 / 这一步读不出组）：不表态 ⇒ 按「覆盖不了」处理
                // （宁可多取一件，绝不断供 —— 用户硬要求 R2）。
                return false;
            }
            for (final List<Item> group : groups) {
                if (!group.contains(other)) {
                    return false; // 这条配方里 {@code item} 有一个候选组不接受 {@code other} ⇒ 覆盖不了
                }
            }
        }
        return true;
    }

    /**
     * <b>R1 + R2（只读）</b>：本仓此刻该为哪一件料备料（替代供料的第二档，用 {@link Substitution} 记录）。
     *
     * <h2>为什么要分「最优」与「替补」两档（用户 R2：不管怎么样，还是得取到一个）</h2>
     * <p>R1 收敛之后，{@code itemTargets} 里的目标量只挂在「这块料必须由它自己满足」的那些物品上。
     * 但用户同时要求<b>绝不能因此断供</b>：如果网络里只有替补件（现场：精密构件与轨道都在跑，
     * 铁粒用光、只剩锌粒），替代件必须能顶上轨道那一步，否则「候选不齐」就变成了「整条线停摆」。
     * 因此替补件的目标量 = <b>该物品的候选组里、那些「覆盖不了全部需求」的兄弟</b>
     * 所能顶下来的那一部分需求。</p>
     *
     * <p><b>口径（为什么替补件的目标量只能取「最优件那一份」而不是把需求拆开加总）</b>：一个 ingredient
     * 的多个候选是<b>一个</b>原料（既有「整组只备一种」逻辑，见 {@link #interchangeableAvailable}），
     * 因此「最优件 + 替补件」合起来仍然只该备 {@code target} 件，绝不能各备一份总量翻倍。
     * 这里用 {@code Math::max} 归并同几对需求、每一笔的上界都是最优件自己的目标量
     * （既有的 {@code stepExtraStockTarget} 上界一字未动）。</p>
     *
     * <p><b>判不出来一律返回空表</b>：退回「只备最优件」的既有行为（不会少备一件，
     * 因为最优件那一档已经在 {@code itemTargets} 里）。只读，不搬运任何资源。</p>
     */
    private Map<Item, Long> substituteTargetsFor(final Map<Item, Long> itemTargets) {
        if (itemTargets.isEmpty()) {
            return Map.of();
        }
        final Map<Item, Long> result = new LinkedHashMap<>();
        for (final Map.Entry<Item, Long> entry : itemTargets.entrySet()) {
            // 代表物（= 最优件）先看：它是否真的要去取。取不到货才需要替补，
            // 而「要不要给某一台机器备料」由既有的 wantingTargetCount 闸门说了算（它已按工位口径算过）。
            if (!isStepExtraInput(entry.getKey()) || entry.getValue() <= 0L) {
                continue;
            }
            for (final Item sibling : interchangeableCandidates(entry.getKey())) {
                if (!isStepExtraInput(sibling)) {
                    continue; // 起步原料组不在本档（它们一个循环只消耗一份，替补会放大在制件数）
                }
                if (coversItemNeeds(entry.getKey(), sibling)) {
                    continue; // 并列最优件（谁有货取谁）：它走自己的目标量，不属于「替补」这一档
                }
                // 这个兄弟顶不下最优件的全部需求 ⇒ 它是替补：只有最优件真缺货时才有意义。
                // 目标量取「最优件此刻的目标量」这一上界（整组仍然只备一种，绝不翻倍）。
                result.merge(sibling, entry.getValue(), Math::max);
            }
        }
        return result;
    }

    /**
     * <b>R2（只读）</b>：这一刻是否<b>真的需要</b>动用 {@code substitute} 来顶 {@code preferred}。
     *
     * <p>三个条件缺一不可：① 最优件在本仓内部、网络、机器侧<b>都没有</b>（一点都取不到）；
     * ② 它<b>确实</b>是替补（覆盖不了最优件的全部需求 —— 否则它就是并列最优件，走普通路径）；
     * ③ 该替补件此刻<b>真的有货</b>（没货也不能凭空造，仍然如实报缺）。</p>
     */
    private boolean needsSubstituteFor(final Item preferred, final Item substitute) {
        if (preferred == null || substitute == null || preferred == substitute) {
            return false;
        }
        if (coversItemNeeds(preferred, substitute)) {
            return false; // 并列最优件：由它自己的目标量负责，不走替补档
        }
        if (preferredAvailable(preferred)) {
            return false; // 最优件有货 ⇒ 不需要替补（R1 优先级优先）
        }
        return substituteAvailable(substitute);
    }

    /** 只读：本仓内部存储 / 机器侧 / RS 网络里此刻有没有 {@code item}（= {@link #substituteHoldings} 的布尔投影）。 */
    private boolean preferredAvailable(final Item item) {
        return substituteHoldings(item) > 0L;
    }

    /**
     * 只读：这一刻 {@code item} 作为替补件有没有意义 —— 必须有<b>至少一条被选中的输出总线能匹配它</b>。
     *
     * <p>「网络里到底有没有它」不在这里判：那由 {@code pullItem} 的既有闸门（缺额 + 网络存量）负责，
     * 本方法只回答「抽进来推得出去吗」这一问（与 {@code pullItem} 里 {@code anyBusOwnsResource}
     * 同一条闸门、同一份数据），避免为一件注定推不出去的料去开合成 / 写拒绝日志。</p>
     */
    private boolean substituteAvailable(final Item item) {
        return item != null
            && anyBusOwnsResource(new ItemStack(item), new ItemResource(item));
    }

    /**
     * <b>R2 + R3（只读）</b>：{@code substitute} 这一档此刻是否成立 —— 成立才允许抽它，并把
     * 「这一次供的不是最优那一件」记进 {@link #handoffUsed}（末尾由 {@link #reportHandoffUsage()} 播报）。
     *
     * <p>逐一问「{@code substitute} 能顶替的每一个最优件」：只要有一个最优件<b>此刻真的一点都取不到</b>
     * 且它<b>确实</b>需要这个替补（见 {@link #needsSubstituteFor}），这一档就成立。
     * 一个都不成立 ⇒ 不抽替补（最优件还有货，优先级优先 —— R1）。</p>
     */
    private boolean substituteApplies(final Map<Item, Long> itemTargets, final Item substitute) {
        for (final Item preferred : itemTargets.keySet()) {
            if (needsSubstituteFor(preferred, substitute)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：抽取途中的「替补档让路」判定（{@code pullItem} 专用，语义与
     * {@link #substituteApplies} 一致，只是把「最优件有货」的口径收窄为<b>真的能用</b>）。
     *
     * <h2>为什么不能直接用 {@link #preferredAvailable}</h2>
     * <p>{@code pullItem} 里那道「整组只备一种」（{@link #interchangeableAvailable}）问的是
     * 「兄弟候选有没有货」，而备料目标量问的是「该喂给机器的那一份到位没有」—— 两者并不等价：
     * 最优件可能正<b>躺在仓里等着</b>（还没被推给机器），此时整组尚未满足，替补档必须能顶上，
     * 否则机械手会一直空手（用户抱怨的「候选不齐就断供」）。因此这里按
     * <b>「本仓内部还没拿到 + 机器侧还没拿着」</b>来判：只要内部存储或机器侧已有最优件，
     * 就说明备料目标已经达成、不需要替补；否则这一趟允许抽替补件。</p>
     *
     * <p>判不出来（没有目标表 / 没有可互换兄弟）一律 {@code false} ⇒ 行为与改动前逐字一致。</p>
     */
    private boolean substituteAppliesForPull(final Item substitute) {
        if (substitute == null) {
            return false;
        }
        for (final Item preferred : interchangeableCandidates(substitute)) {
            if (coversItemNeeds(preferred, substitute)) {
                continue; // 并列最优件：它有自己的目标量，不属于「替补」这一档
            }
            if (itemStorage.countOf(preferred) > 0L || machineHeldItem(preferred) > 0L) {
                continue; // 最优件已经在本仓 / 机器侧（备料目标已达成）⇒ 不需要替补
            }
            if (substituteHoldings(substitute) > 0L) {
                return true; // 最优件一点都取不到，而替补件确有货 ⇒ 让路抽它（R2）
            }
        }
        return false;
    }

    /** 只读：这一刻 {@code item} 的可取量（本仓内部存储 + 机器侧 + RS 网络），用于「有没有货」判定。 */
    private long substituteHoldings(final Item item) {
        if (item == null) {
            return 0L;
        }
        long have = itemStorage.countOf(item) + machineHeldItem(item);
        final Network network = getNode().getNetworkOrNull();
        final StorageNetworkComponent storage = network == null
            ? null : network.getComponent(StorageNetworkComponent.class);
        if (storage != null) {
            have += storage.get(new ItemResource(item));
        }
        return have;
    }

    /**
     * <b>只读</b>：{@code substitute} 这次实际顶替的是哪一件（用于横幅展示「原本要的那一件」）。
     *
     * <p>取「能顶替的、且此刻真取不到的最优件」里的第一个；一个都没有时返回 {@code null}
     * （横幅那一行会退化成「替补件」自己，绝不显示空名字）。</p>
     */
    @org.jetbrains.annotations.Nullable
    private Item preferredFor(final Map<Item, Long> itemTargets, final Item substitute) {
        for (final Item preferred : itemTargets.keySet()) {
            if (needsSubstituteFor(preferred, substitute)) {
                return preferred;
            }
        }
        return null;
    }

    /**
     * <b>R3 + R4：替代供料的横幅（边沿语义，用户明确反感重复提示）</b>。
     *
     * <h2>两个状态、两条边沿</h2>
     * <ul>
     *     <li><b>进入</b>：本轮真的抽进了替补件（指纹从空 / 旧值变成新值）⇒ 播报<b>一次</b>；</li>
     *     <li><b>恢复</b>：本轮一件替补都没抽（指纹变回空串）⇒ <b>只重置状态、不播报</b>；</li>
     *     <li><b>同一状态原样持续</b>（指纹一字不变）⇒ 一条都不再发，不管过去多久、冷却到没到。</li>
     * </ul>
     * <p>冷却（{@value #BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS} tick）只在<b>状态真的翻转</b>时才起作用，
     * 用于抑制「替代 / 不替代」在一秒内来回抖动导致的刷屏；它<b>不会</b>让一份持续存在的状态被重复播报
     * —— 这正是它与缺料横幅（缺口持续时要按冷却重复提醒）在语义上的区别。</p>
     *
     * <p>横幅只发给本仓 {@value #BUS_SHORTAGE_NOTIFY_RADIUS} 格内的玩家（服务端权威）；
     * 只读 + 只发消息，不搬运、不修改任何库存。</p>
     */
    private void reportHandoffUsage() {
        final String signature = handoffSignature();
        if (signature.equals(handoffSignature)) {
            return; // 同一状态原样持续：绝不重复播报（用户硬要求）
        }
        final Level level = getLevel();
        final long now = level == null ? 0L : level.getGameTime();
        if (signature.isEmpty()) {
            // 「恢复」这一条边沿：只重置状态，不播报（R3 的边沿语义）。
            handoffSignature = "";
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("handoff@" + RsccAssemblyDebug.at(worldPosition),
                    "state=none",
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " handoff state=none (回到有最优件可用的状态)");
            }
            return;
        }
        if (now < handoffNotifyTick) {
            return; // 状态刚翻转但仍在冷却窗口内：等下一次（避免抖动刷屏）
        }
        handoffSignature = signature;
        handoffNotifyTick = now + BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS;
        cretae.cookiewyq.rs_create_compat.support.RsccDiag.recordBanner("handoff_chamber",
            worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ());
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.transition("handoff@" + RsccAssemblyDebug.at(worldPosition), signature,
                RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " handoff {" + signature + "}"
                    + " reason=preferred_unavailable_substitute_used");
        }
        if (!(level instanceof final net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        CompatCompletionSender.sendToNearby(serverLevel, worldPosition,
            BUS_SHORTAGE_NOTIFY_RADIUS * BUS_SHORTAGE_NOTIFY_RADIUS, handoffBannerRows());
    }

    /**
     * 当前替代状态的指纹（<b>稳定可复现</b>）：每笔「替补 → 最优件」按 id 排序后拼成串；无替代时为空串。
     *
     * <p><b>为什么指纹里只有「哪一对」、不含件数</b>（用户极端反感重复提示）：件数会随机器一直在吃而
     * 每一轮都变（1 → 2 → 3…），若把它算进指纹，「同一件事还在持续」就会被当成「状态变了」而反复弹窗。
     * 因此这里只认<b>换料关系本身</b>：同一对一直有效 ⇒ 全程只有进入时那一条提示；
     * 换成另一对（或换成另一件替补）才算一次新的状态变化 —— 那时确实发生了值得说一声的事。</p>
     */
    private String handoffSignature() {
        if (handoffUsed.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>(handoffUsed.size());
        for (final Substitution pair : handoffUsed.keySet()) {
            parts.add(idOf(pair.preferred()) + "->" + idOf(pair.actual()));
        }
        java.util.Collections.sort(parts);
        return String.join(",", parts);
    }

    /**
     * 替代横幅的行：第 1 行标题，第 2 行「原本要的那一件 → 实取的那一件 ×件数」。
     * <p>与缺料横幅同一套构造方式（段 = 图标 + 语言键，服务端不解析文案），因此中英玩家各看各的；
     * 最多列 {@value #BUS_SHORTAGE_NOTIFY_MAX} 对，其余用一条「还有 N 对」概括（本轮改走横幅是为了
     * 不把长列表挤成一行）。</p>
     */
    private List<CompletionBannerPayload.Row> handoffBannerRows() {
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(2);
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE,
            CompletionBannerPayload.localized(KEY_HANDOFF_TITLE)));
        final List<ItemStack> icons = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final List<Map.Entry<Substitution, Long>> entries = new ArrayList<>(handoffUsed.entrySet());
        final int listed = Math.min(entries.size(), BUS_SHORTAGE_NOTIFY_MAX);
        for (int i = 0; i < listed; i++) {
            if (i > 0) {
                icons.add(ItemStack.EMPTY);
                texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
                colors.add(COLOR_TEXT);
            }
            final Substitution pair = entries.get(i).getKey();
            final Item preferred = pair.preferred() == null ? pair.actual() : pair.preferred();
            icons.add(new ItemStack(preferred));
            icons.add(new ItemStack(pair.actual()));
            // 条目文案由语言键给出（单位 / 箭头方向都在译文里，服务端不解析语言）
            texts.add(CompletionBannerPayload.localized(KEY_HANDOFF_ENTRY,
                new ItemStack(preferred).getHoverName().getString(),
                new ItemStack(pair.actual()).getHoverName().getString()));
            colors.add(PALETTE[i % PALETTE.length]);
        }
        if (entries.size() > listed) {
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
            colors.add(COLOR_TEXT);
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_HANDOFF_MORE, entries.size() - listed));
            colors.add(COLOR_TEXT);
        }
        // 段与图标必须等长（客户端逐段内联渲染）：上面每段各配一个图标，这里再核一次，
        // 少一个就补空图标、多一个就截掉，绝不把不对齐的数据送出去。
        while (icons.size() < texts.size()) {
            icons.add(ItemStack.EMPTY);
        }
        while (texts.size() < icons.size()) {
            texts.add("");
        }
        rows.add(CompletionBannerPayload.Row.colored(icons, texts, colors));
        return rows;
    }

    /** 只读：物品注册名的字符串形式（取不到时退化成空串，仅用于指纹 / 诊断，不参与任何判定）。 */
    private static String idOf(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return "-";
        }
        final net.minecraft.resources.ResourceLocation key =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "-" : key.toString();
    }

    /**
     * <b>只读</b>：{@code item} 所在「输入原料组」的可互换兄弟里，此刻是否已经有货
     * （本仓内部存储 / RS 网络 / 机器侧任一处）—— 整组因此视为<b>已满足</b>。
     *
     * <p>备料 / 缺料 / 补合成三处共用本判据（「整组视为一个原料」的唯一实现）：
     * 任一候已有 ⇒ 不再抽第二种、不算缺、不请求补合成。</p>
     */
    private boolean interchangeableAvailable(final Item item) {
        final Set<Item> siblings = interchangeableCandidates(item);
        if (siblings.isEmpty()) {
            return false;
        }
        final Network network = getNode().getNetworkOrNull();
        final StorageNetworkComponent storage = network == null
            ? null : network.getComponent(StorageNetworkComponent.class);
        for (final Item sibling : siblings) {
            // <b>R1（本轮收紧的那一步）</b>：兄弟候选只有<b>把这块料的全部需求顶得下来</b>时
            // 才算「整组已满足」。为什么必须在这里也判一次（不能只靠调用方）：
            // 本方法是「整组视为一个原料」的唯一实现，三处共用；现场是「铁粒用光、只剩锌粒，
            // 而精密构件也在跑」—— 锌粒顶不了精密构件的铁粒，若这里回 true，
            // 抽取侧会直接跳过 → 铁粒永远进不来（用户抱怨的「候选不齐就断供」）。
            // 判不出来 ⇒ coversItemNeeds 返回 false ⇒ 按「还没满足」处理（宁可多取一件）。
            if (!coversItemNeeds(item, sibling)) {
                continue;
            }
            if (itemStorage.countOf(sibling) > 0L) {
                return true;
            }
            if (machineHeldItem(sibling) > 0L) {
                return true;
            }
            if (storage != null && storage.get(new ItemResource(sibling)) > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读：{@code item} 是否是所在「输入原料组」的<b>非代表物</b>（= 不是该类别的图标 / 每批需求那一件）。
     * <p>用途：缺料上报与补合成请求<b>整组只做一次</b>（由代表物承担），否则标签的 3 个候选会各报一条
     * 重复缺口 / 各请求一次补合成。判据与 {@code labelOf} 同一口径：类别的 {@code items().get(0)}。</p>
     */
    private boolean isInterchangeableFollower(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.isInput() || info.isFluidInput() || info.items().size() < 2) {
                continue;
            }
            if (info.items().contains(item) && info.items().get(0) != item) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把配方 {@code results} 池拆成「成品 / 废料」并登记为独立类别（本轮新增，用户验收标准 #3）。
     *
     * <p><b>数据从哪来</b>：Create 的 {@link SequencedAssemblyRecipe#resultPool}（= 配方 JSON 的
     * {@code results} 字段；Create 没有单独的 scraps 池，废料 = 池里除「主产物」以外的概率项）。
     * 拆分口径与 SPT / JEI 完全一致（{@link SequencedRecipeProbe#splitResultPool}），
     * 因此界面上看到的「成品 / 废料」与玩家在样板终端里看到的就是同一份数据。</p>
     *
     * <p><b>归属口径</b>：成品 / 废料没有「进度步」可言，因此它们<b>不参与</b>按步判定
     * （{@code matchesCategoryStep} 对没有按步原型的类别一律放行，{@code isNextForMyMachines} 对非过渡件
     * 一律放行）—— 与 {@code intermediate:<step>} 的按步归属互不干扰，也不会互相顶掉。</p>
     */
    private static void addProductCategories(final SequencedAssemblyRecipe recipe,
                                             final Map<String, ItemStack> results,
                                             final Map<String, ItemStack> scraps) {
        final SequencedRecipeProbe.Split split = SequencedRecipeProbe.splitResultPool(recipe.resultPool);
        for (final SequencedRecipeProbe.Output output : split.results()) {
            addProductCategory(results, RsccBusCategory.RESULT_PREFIX, output.stack());
        }
        for (final SequencedRecipeProbe.Output output : split.scraps()) {
            addProductCategory(scraps, RsccBusCategory.SCRAP_PREFIX, output.stack());
        }
    }

    /** 登记一个成品 / 废料类别（同物品只出一项；id 由前缀 + 物品注册名拼成）。 */
    private static void addProductCategory(final Map<String, ItemStack> target, final String prefix,
                                           final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final String id = productCategoryId(prefix, stack.getItem());
        if (!id.isEmpty()) {
            target.putIfAbsent(id, stack.copy());
        }
    }

    /**
     * 「预估需求」的每批倍数：<b>起始原料</b>（= 所属总样板主原料）一个循环只消耗一份 → 1；
     * 其余步骤输入在一个循环里每个步骤会跑 {@code loops} 次 → {@code loops}（至少 1）。
     *
     * <p>注意这是「正常情况（不看概率）」的口径：用户要求界面显示的预估就是正常所需量，
     * 并额外提示「因概率原因实际可能超出」，因此这里<b>不</b>乘 1/概率。</p>
     */
    private static long requirementMultiplier(@org.jetbrains.annotations.Nullable
                                              final SequencedAssemblyRecipe recipe,
                                              @org.jetbrains.annotations.Nullable final Item item) {
        if (recipe == null) {
            return 1L;
        }
        // 起始原料按<b>候选集合</b>判（标签型起步原料的任一候选都只消耗一份 / 一个循环）
        if (item != null && mainIngredientCandidates(recipe).contains(item)) {
            return 1L; // 起始原料：一个循环只消耗一份
        }
        return Math.max(1L, recipe.getLoops());
    }

    /** 累加某类别的「预估需求」（同一类别多次出现取最大值，保证界面数字稳定、保守）。 */
    private static void bumpEstimated(final Map<String, Long> estimated, final String id, final long value) {
        if (id == null || id.isEmpty() || value <= 0) {
            return;
        }
        estimated.merge(id, value, Math::max);
    }

    /**
     * 严格归属：本单元样板对应的那条 Create 序列装配配方；无法唯一确定时返回 {@code null}。
     * <ol>
     *     <li>样板记了配方 id → 直接按 id 定位；</li>
     *     <li>没记 id → 按「配方类型 + 样板输入物」找候选，<b>只认唯一命中</b>：候选范围优先取
     *     <b>本仓其它样板记录过的配方 id</b>（同一批样板总是同一条配方生成的），范围内没有再看全部配方；
     *     命中 0 条或 ≥2 条都返回 null（宁可不显示，也绝不串配方）。</li>
     * </ol>
     */
    @org.jetbrains.annotations.Nullable
    private SequencedAssemblyRecipe resolveUnitRecipe(final Level level, final UnitData unit,
                                                      final Set<ResourceLocation> chamberRecipeIds) {
        final SequencedAssemblyRecipe direct = directAssemblyOf(level, unit);
        if (direct != null) {
            return direct;
        }
        final String recipeType = unit.recipeType() == null ? "" : unit.recipeType();
        if (recipeType.isEmpty()) {
            return null;
        }
        final Item wanted = unit.input() == null || unit.input().isEmpty() ? null : unit.input().getItem();
        if (wanted == null) {
            return null; // 既无配方 id 也无输入物：无从区分同类型的多条配方，不猜
        }
        if (!chamberRecipeIds.isEmpty()) {
            final List<SequencedAssemblyRecipe> inChamber =
                collectAssemblyCandidates(level, recipeType, wanted, chamberRecipeIds);
            if (inChamber.size() == 1) {
                return inChamber.get(0);
            }
            if (inChamber.size() > 1) {
                return null;
            }
        }
        final List<SequencedAssemblyRecipe> global = collectAssemblyCandidates(level, recipeType, wanted, null);
        return global.size() == 1 ? global.get(0) : null;
    }

    /**
     * 收集候选配方：含一个「配方类型 == {@code recipeType}，且真正消耗了 {@code wanted}（或第 0 个
     * ingredient 复合里含 {@code wanted}）」的步骤的序列装配配方。
     * {@code scope} 非空时只在该配方 id 集合内找；最多收集 2 条（调用方只关心 0 / 1 / 多条）。
     */
    private static List<SequencedAssemblyRecipe> collectAssemblyCandidates(
        final Level level, final String recipeType, final Item wanted,
        final @org.jetbrains.annotations.Nullable Set<ResourceLocation> scope) {
        final List<SequencedAssemblyRecipe> found = new ArrayList<>(2);
        try {
            for (final RecipeHolder<?> holder : level.getRecipeManager()
                .getAllRecipesFor(com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())) {
                if (!(holder.value() instanceof final SequencedAssemblyRecipe recipe)) {
                    continue;
                }
                if (scope != null && !scope.contains(holder.id())) {
                    continue;
                }
                if (sequenceMatchesUnit(recipe, recipeType, wanted)) {
                    found.add(recipe);
                    if (found.size() > 1) {
                        return found; // 已经能确定「不唯一」，无需继续扫
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方系统异常（未加载完 / 版本差异）：当作「没有候选」，绝不抛异常
        }
        return found;
    }

    /** 该序列装配配方是否含一个「配方类型匹配、且真正消耗了 {@code wanted}」的步骤。 */
    private static boolean sequenceMatchesUnit(final SequencedAssemblyRecipe recipe,
                                               final String recipeType, final Item wanted) {
        if (wanted == null) {
            return false; // 无输入物可比对：不认任何候选（避免把同类型的其它配方算进来）
        }
        final Item transitional = recipe.getTransitionalItem().getItem();
        for (final SequencedRecipe<?> step : recipe.getSequence()) {
            if (!recipeType.equals(SequencedRecipeProbe.recipeTypeId(step.getRecipe()))) {
                continue;
            }
            if (stepConsumes(step, transitional, wanted)) {
                return true;
            }
        }
        return false;
    }

    /** 该步是否消耗了指定物品（含第 0 个 ingredient 复合里的主原料）。 */
    private static boolean stepConsumes(final SequencedRecipe<?> step, final Item transitional, final Item wanted) {
        for (final ItemStack stack : SequencedRecipeProbe.assemblyStepInputs(step.getRecipe(), transitional)) {
            if (stack.getItem() == wanted) {
                return true;
            }
        }
        final var ingredients = step.getRecipe().getIngredients();
        if (!ingredients.isEmpty()) {
            for (final ItemStack stack : ingredients.get(0).getItems()) {
                if (stack.getItem() == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 严格取「本单元对应的那一步」：优先按样板记录的步骤下标取（下标处的配方类型必须与样板一致）；
     * 下标不可用 / 不一致时，在所属配方里取第一个「配方类型一致、且（样板未记输入物 或 真正消耗了它）」
     * 的步骤。找不到返回 {@code null}（此时不推导该步输入，避免把别的步骤的数据算进来）。
     */
    @org.jetbrains.annotations.Nullable
    private static SequencedRecipe<?> stepForUnit(final SequencedAssemblyRecipe recipe, final UnitData unit) {
        final List<SequencedRecipe<?>> sequence = recipe.getSequence();
        if (sequence.isEmpty()) {
            return null;
        }
        final String recipeType = unit.recipeType() == null ? "" : unit.recipeType();
        if (recipeType.isEmpty()) {
            // 老语义样板只有 recipe id + 步骤号
            return unit.step() >= 0 ? sequence.get(Math.floorMod(unit.step(), sequence.size())) : null;
        }
        if (unit.step() >= 0) {
            final SequencedRecipe<?> indexed = sequence.get(Math.floorMod(unit.step(), sequence.size()));
            if (recipeType.equals(SequencedRecipeProbe.recipeTypeId(indexed.getRecipe()))) {
                return indexed;
            }
        }
        final Item wanted = unit.input() == null || unit.input().isEmpty() ? null : unit.input().getItem();
        final Item transitional = recipe.getTransitionalItem().getItem();
        for (final SequencedRecipe<?> step : sequence) {
            if (!recipeType.equals(SequencedRecipeProbe.recipeTypeId(step.getRecipe()))) {
                continue;
            }
            if (wanted == null || stepConsumes(step, transitional, wanted)) {
                return step;
            }
        }
        return null;
    }

    /** 序列装配总样板的主原料（{@code getIngredient()} 的第一个非空物品；无则空栈）。 */
    private static ItemStack mainIngredientOf(final SequencedAssemblyRecipe recipe) {
        for (final ItemStack stack : recipe.getIngredient().getItems()) {
            if (!stack.isEmpty()) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 只读：总样板主原料（= 起步原料）的<b>全部候选</b>。
     * <p><b>为什么不能用 {@link #mainIngredientOf} 代表物判等（用户第 1 条）</b>：列车轨道的起步原料是
     * 标签 {@code create:sleepers}（石头台阶 / 平滑石台阶 / 安山岩台阶），玩家手里往往只有其中一种；
     * 只认代表物时，其它候选既不被当作起步原料（不受「工位有空才开新件」保护），
     * 也推不出「这是哪条配方的起件步」⇒ 用户实测「列车轨道完全无法正常进行」。
     * 候选集合口径与其余判定同源（{@link SequencedRecipeProbe#mainIngredientGroup(Ingredient)}）。</p>
     */
    private static List<Item> mainIngredientCandidates(@org.jetbrains.annotations.Nullable
                                                      final SequencedAssemblyRecipe recipe) {
        if (recipe == null) {
            return List.of();
        }
        final SequencedRecipeProbe.InputGroup group =
            SequencedRecipeProbe.mainIngredientGroup(recipe.getIngredient());
        final List<Item> result = new ArrayList<>(group.candidates());
        // 代表物（{@link #mainIngredientOf}）永远排首位：类别图标 / 每批所需量 / 缺口只在它身上记一次
        final ItemStack representative = mainIngredientOf(recipe);
        if (!representative.isEmpty() && result.remove(representative.getItem())) {
            result.add(0, representative.getItem());
        }
        return result;
    }

    /**
     * 登记一种「输入原料」类别。
     *
     * <p><b>同一物品被多个步骤使用时取「最大的那一份」（本轮修复：按步的原料数量）</b>：
     * 不同步骤消耗同一种原料的数量可以不同（附属模组 / 多步配方里 1 件与 3 件并存）。
     * 旧实现是 {@code putIfAbsent}（首次出现的那一步赢了），于是「靠后那一步要 3 件、
     * 靠前那一步只要 1 件」时，类别里记的每批需求只有 1 —— 备料目标量（
     * {@code fillInternalForBus} 的 {@code perBatch = info.amount()}）随之偏小，
     * 靠后那一步永远缺料。改取最大值后，同一物品**一步都不缺**，且不会因为「取第一条」
     * 而随样板槽顺序变化（判定确定、可复现）。</p>
     */
    private static void addInputCategory(final Map<String, ItemStack> inputs, final ItemStack stack) {
        addInputCategory(inputs, stack, "");
    }

    /**
     * 同上，但类别 id <b>按配方限定</b>（{@code input:<配方>#<物品>}）。
     *
     * <p><b>2026-10-05 用户实测的漏改点</b>：上一版只把「候选登记 / 预估」改成了配方限定 id，
     * 而这里仍然用 {@link #inputCategoryId(Item)}（只有物品名）—— 于是 {@code inputs} 表的键
     * <b>与候选表的键对不上</b>，最终生成的类别 id 又退回旧格式，两条配方继续共用一个铁粒类别
     * （用户截图：精密构件仍显示「铁粒 + 锌粒」）。</p>
     */
    private static void addInputCategory(final Map<String, ItemStack> inputs, final ItemStack stack,
                                         @org.jetbrains.annotations.Nullable final String recipeId) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final String id = inputCategoryIdForRecipe(stack.getItem(), recipeId);
        if (!id.isEmpty()) {
            inputs.merge(id, stack.copy(),
                (existing, candidate) -> candidate.getCount() > existing.getCount() ? candidate : existing);
        }
    }

    /**
     * 登记一种「流体输入」类别（口径与 {@link #addInputCategory} 完全对称：同种流体在多步里
     * 需要的 mB 不同时取最大值，保证每批需求不会因为「首次出现的那一步只要一点点」而偏小）。
     */
    private static void addFluidCategory(final Map<String, FluidStack> fluids, final FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final String id = fluidCategoryId(stack.getFluid());
        if (!id.isEmpty()) {
            fluids.merge(id, stack.copy(),
                (existing, candidate) -> candidate.getAmount() > existing.getAmount() ? candidate : existing);
        }
    }

    /**
     * <b>只读</b>：本仓此刻「真的在为它跑」的序列装配配方 id 集合（= 网络里<b>活跃（未被挂起）</b>的、
     * 目标资源命中本仓某条样板的产物 / 过渡件 / 起步原料的任务所对应的配方）。
     *
     * <h2>为什么需要它（用户第 2 条：还是产生多余中间产物）</h2>
     * <p>旧存档里勾的是不带配方的旧类别 {@code intermediate:<步序>}；{@link #normalizeBusOwners()}
     * 为了兼容会把它在<b>所有</b>含该步序的配方上各展开一次。当同一台执行舱同时挂了
     * <b>两条流程</b>的样板（现场：{@code create:sequenced_assembly/sturdy_sheet} 与
     * {@code create:sequenced_assembly/track} 的第 2 步都是冲压）时，一个 {@code intermediate:2}
     * 就会同时给两条配方放行 ⇒ 本仓会把<b>不属于当前订单</b>那条流程的中间产物也备料 / 导出，
     * 这正是用户十几遍重复的「还是产生多余中间产物」。有了本判据，旧 id 的展开被夹在
     * 「<b>本仓当前流程真正属主</b>的配方」里：同一时刻只有正在跑的那条流程会被放行。</p>
     * <p><b>判不出来（无网络 / 无活跃任务）⇒ 返回空集</b>：调用方据此保持旧行为（展开全部），
     * 此时本仓的门控是关的（没有任何导出），因此不会造成多余搬运。只读，不搬运任何资源。</p>
     */
    private Set<String> activePipelineRecipeIds() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return Set.of();
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return Set.of();
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return Set.of();
        }
        final List<TaskStatus> statuses = autocrafting.getStatuses();
        if (statuses.isEmpty()) {
            return Set.of();
        }
        // 本仓每条样板的「配方 id → 它的产物 ∪ 过渡件 ∪ 起步原料」物品集合
        final Map<String, Set<Item>> recipeItems = new LinkedHashMap<>();
        for (final UnitData unit : unitsForExport()) {
            final SequencedAssemblyRecipe recipe = directAssemblyOf(level, unit);
            if (recipe == null) {
                continue;
            }
            final ResourceLocation id = recipeIdOf(level, unit, recipe);
            if (id == null) {
                continue;
            }
            final Set<Item> items = recipeItems.computeIfAbsent(id.toString(), key -> new LinkedHashSet<>());
            final ItemStack transitional = recipe.getTransitionalItem();
            if (!transitional.isEmpty()) {
                items.add(transitional.getItem());
            }
            for (final ProcessingOutput output : recipe.resultPool) {
                if (!output.getStack().isEmpty()) {
                    items.add(output.getStack().getItem());
                }
            }
            for (final ItemStack ingredient : recipe.getIngredient().getItems()) {
                if (!ingredient.isEmpty()) {
                    items.add(ingredient.getItem());
                }
            }
        }
        if (recipeItems.isEmpty()) {
            return Set.of();
        }
        final Set<String> active = new LinkedHashSet<>();
        for (final TaskStatus status : statuses) {
            if (AssemblyWatchdog.isSuspended(status.info().id().id())) {
                continue; // 挂起的任务不算「当前流程」（本仓此刻本来就冻结）
            }
            if (!(status.info().resource() instanceof final ItemResource resource)) {
                continue;
            }
            for (final Map.Entry<String, Set<Item>> entry : recipeItems.entrySet()) {
                if (entry.getValue().contains(resource.item())) {
                    active.add(entry.getKey());
                }
            }
        }
        return active;
    }

    /**
     * 服务端：把「每台相连输出总线当前选择的类别」整表重建为权威归属表（{@link #busCategoryOwners}）。
     *
     * <p><b>2026-10-06 口径变更（用户需求：成链 = 一个逻辑执行仓 / 扩容）</b>：整表的计算已经搬到
     * 唯一事实源 {@link #chainBusOwners()}（链级：类别范围 = 整条链的并集、总线集合 = 整条链的并集），
     * 本方法只负责「比较 → 写入 → 通知」这三件与调用方有关的事。于是所有既有消费点
     * （备料 {@code fillInternalForBus}、份额 {@code exportShare}、界面共享角标、总线配置校验）
     * 自动读到同一份链级视图，<b>不存在第二张归属表</b>。</p>
     */
    public void normalizeBusOwners() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        final Map<String, List<BlockPos>> rebuilt = chainBusOwners();
        final boolean roundRobin = rebuilt.values().stream().anyMatch(list -> list.size() > 1);
        final boolean changed = !rebuilt.equals(busCategoryOwners);
        if (changed) {
            busCategoryOwners.clear();
            busCategoryOwners.putAll(rebuilt);
            setChanged();
        }
        busRoundRobinActive = roundRobin;
        if (changed) {
            pushBusTurnToExporters(); // 归属变了：立刻让相关输出总线按新归属重建导出清单
        }
    }

    /**
     * <b>唯一事实源</b>：链级「类别 id → 选择它的输出总线坐标（升序）」整表。
     *
     * <h2>为什么是链级（用户需求）</h2>
     * <p>用户：「这一条链……相当于同属于同一个执行场，相当于给执行仓扩容；这一条链的上面，
     * 它的这一个输入输出总线应该保持一样的。」因此类别范围 = 整条链的并集
     * （{@link #chainCategories()}）、总线集合 = 整条链的并集（{@link #chainExporterPositions()}），
     * 于是「在链上任意一处接线」与「在链首接线」看到的是同一套总线、同一套类别。</p>
     *
     * <h2>去重（本次最大风险的正面回答：「一总线一次」）</h2>
     * <ol>
     *     <li>总线集合由 {@link #chainExporterPositions()} 用 {@link LinkedHashSet} 按坐标去重
     *     —— 同一台总线即使同时被链上多台仓的线缆触达，也只会出现<b>一次</b>；</li>
     *     <li>登记走 {@link #addBusOwner}，它对同一台总线在同一个类别下只会加一次；</li>
     *     <li>类别集合由 {@link #chainCategories()} 按 id 去重，每个 id 只有一个属主仓。</li>
     * </ol>
     * <p>因此「同一台总线只被算一次、只被消费一次」在<b>结构上</b>成立：链级表是<b>一张</b>表
     * （每台成员各自重建，但内容按坐标 / id 唯一确定 ⇒ 各台看到的是同一份），
     * 归属表里的每个坐标只驱动一次份额判定、一次在制名额判定、一次排队判定 ——
     * 不会因为链上有 N 台仓而把同一件事各算一遍（那正是会直接弄坏
     * {@code blockedByMachineQueue} / {@code startCapacityForRecipe} / {@code inFlightUnitsForRecipe}
     * 的重复计数）。</p>
     *
     * <h2>归一三条规则</h2>
     * <p>① 该类别对<b>本链</b>必须有意义（在 {@link #chainCategories()} 里）；
     * ② 选择方必须是「<b>属于本链（分支）</b>」的输出总线；
     * ③ 同一类别的多台选择者一律保留（共享），顺序 = 坐标升序（确定性，份额按此顺序）。</p>
     * <p>每次调用都整表重建，因此拆方块 / 改选择 / 换模式 / 拔样板都会自愈。</p>
     */
    private Map<String, List<BlockPos>> chainBusOwners() {
        final Level level = getLevel();
        final Map<String, List<BlockPos>> rebuilt = new LinkedHashMap<>();
        if (level == null || level.isClientSide()) {
            return rebuilt;
        }
        final List<ChainBusCategory> categories = chainCategories();
        final Set<String> visible = new LinkedHashSet<>();
        for (final ChainBusCategory entry : categories) {
            visible.add(entry.info().id());
        }
        // <b>把「本链当前可见的类别 id」整表打进日志</b>（2026-10-05 用户要求：
        // 「下一轮我会把类别 id 直接打进日志 —— 你倒是把这个日志加进去啊」）。
        // 为什么必须在日志里也能看到：类别 id 是「按配方限定」的（{@code input:<配方>#<物品>}），
        // 而快照里的 {@code categories} 字段在某些时刻是空的 —— 没有这行日志就无法判定
        // 「配方限定到底生效没有 / 界面读的是不是同一份类别」。
        // 去重：整表内容不变时不重复刷（由 RsccAssemblyDebug.changed 的签名去重负责）。
        if (RsccAssemblyDebug.isEnabled()) {
            final String signature = RsccAssemblyDebug.machine("chamber", worldPosition);
            if (RsccAssemblyDebug.changed("cats@" + signature, String.join(",", visible))) {
                RsccAssemblyDebug.event("[rscc-assembly] chamber@" + signature
                    + " cat_ids=" + visible);
            }
        }
        // 「本链当前流程」的配方（旧 id 归一化只在它里面展开，见 {@link #chainActivePipelineRecipeIds()}）：
        // 每次整表重建至多算一次（惰性：只有真的遇到旧存档 id 才算，见下），避免为新存档白花开销。
        Set<String> activeRecipes = null;
        for (final BlockPos pos : chainExporterPositions()) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            // 只取「已存在」的方块实体（CHECK）：整表重建绝不为邻块强制初始化
            if (!(level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
                instanceof final RsccExporterExecutorMode exporter)) {
                continue;
            }
            List<String> requested = exporter.rscc$getExportCategoryIds();
            if (!exporter.rscc$isCategorySelectionExplicit()) {
                // 没显式选过：默认 = 全部「输入性产物」类别 + 中间产物（过渡件）。
                // 为什么必须带上中间产物：总线输出模式下引擎只做「备料」，中间产物要被送到
                // 「下一步骤」的机器，唯一路径就是「本仓把网络里的过渡件吸进内部存储 → 输出总线推给机器」；
                // 若默认不含 INTERMEDIATE，链会在第一步之后直接断掉（用户验收标准 #3）。
                // <b>链级</b>：默认集取整条链的并集（见 chainDefaultExportCategoryIds）。
                requested = chainDefaultExportCategoryIds();
            }
            if (requested == null) {
                continue;
            }
            for (final String id : requested) {
                if (!visible.contains(id) && RsccBusCategory.isLegacyIntermediateId(id)) {
                    // 旧存档兼容（中间产物先改成「按步拆」、本轮再改成「配方 + 步序」）：
                    // 老存档里勾的是不带配方（甚至不带步序）的旧 id，而本链此刻只有
                    // {@code intermediate:<配方>:<步序>}。若直接丢弃，旧存档里勾好的「中间产物」
                    // 会整条失效（导出清单里一项都取不到 → 链在第一步之后断掉）。
                    // 归一规则：不带步序 → 展开成全部中间产物类别；带步序 → 展开成「该步序在本链
                    // <b>当前流程</b>下」的类别。
                    // <b>本轮修正（用户第 2 条：还是产生多余中间产物）</b>：旧实现在「带步序」时于
                    // <b>所有</b>含该步序的配方上展开 —— 同一条执行舱同时挂 sturdy_sheet 与 track 时，
                    // 一个 {@code intermediate:2} 会同时给两条配方放行 ⇒ 另一条流程的中间产物被一起导出。
                    // 现在若「本链当前流程」（{@link #chainActivePipelineRecipeIds()}）非空，
                    // 则只展开属于它的配方；判不出来（空集，且此时门控关着、本就不导出）才退回展开全部。
                    // <b>惰性求值</b>：只有真的遇到旧 id 才算（新存档里永远遇不到），
                    // 否则每次整表重建都会为「可能根本用不到」的链级流程扫描白花一遍开销。
                    if (activeRecipes == null) {
                        activeRecipes = chainActivePipelineRecipeIds();
                    }
                    final int legacyStep = RsccBusCategory.legacyIntermediateStep(id);
                    final StringBuilder expanded = new StringBuilder(48);
                    for (final ChainBusCategory entry : categories) {
                        final BusCategoryInfo info = entry.info();
                        if (!info.isIntermediate() || (legacyStep >= 0 && info.step() != legacyStep)) {
                            continue;
                        }
                        if (!activeRecipes.isEmpty() && !activeRecipes.contains(info.recipe())) {
                            continue; // 不属于当前流程的配方：本旧 id 不为它放行（避免多余中间产物）
                        }
                        addBusOwner(rebuilt, visible, info.id(), pos);
                        if (expanded.length() > 0) {
                            expanded.append(',');
                        }
                        expanded.append(info.id());
                    }
                    // 归一化只在状态变化时打一条（用户要求「旧格式要能看出被归一成了什么」）
                    RsccAssemblyDebug.transition("legacycat@" + RsccAssemblyDebug.at(pos), expanded.toString(),
                        RsccAssemblyDebug.machine("exporter", pos)
                            + " legacy category '" + id + "' normalized -> [" + expanded + "]"
                            + " (步序 / 配方可从本链当前类别推出；未推出的不会被导出)");
                    continue;
                }
                addBusOwner(rebuilt, visible, id, pos);
            }
        }
        for (final List<BlockPos> owners : rebuilt.values()) {
            owners.sort(Comparator.comparingLong(BlockPos::asLong));
        }
        return rebuilt;
    }

    /**
     * 把某台输出总线登记为某类别的选择者（去重；类别对本仓无意义时忽略）。
     * <p>抽出来是为了「旧存档的 {@code intermediate} 展开成各步 id」与普通路径共用同一套判定，
     * 不会出现两条略有差异的登记逻辑。</p>
     */
    private static void addBusOwner(final Map<String, List<BlockPos>> owners, final Set<String> visible,
                                    final String id, final BlockPos pos) {
        if (id == null || !visible.contains(id)) {
            return;
        }
        final List<BlockPos> list = owners.computeIfAbsent(id, key -> new ArrayList<>());
        if (!list.contains(pos)) {
            list.add(pos);
        }
    }

    /** 逐台通知相连的输出总线按当前轮次重建导出清单（共享均分的执行端）。 */
    private void pushBusTurnToExporters() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        for (final BlockPos pos : connectedExporterPositions()) {
            if (level.isLoaded(pos)
                && level.getBlockEntity(pos) instanceof final RsccExporterExecutorMode exporter) {
                exporter.rscc$refreshBusTurn();
            }
        }
    }

    /**
     * 本仓当前应交给某台输出总线导出的过滤项（服务端权威）。
     * <p><b>共享均分规则</b>：某类别只被 1 台选中 → 全给它（原行为）；被 N&gt;1 台选中 →
     * 只有「本轮轮到的」那一台能拿到 —— 轮次 = {@code gameTime % N}，顺序即
     * {@link #busCategoryOwners} 里的坐标升序。于是各台按 tick 轮流导出：在 RS 输出总线默认
     * 「每 tick 每过滤项若干件」的节流下就是<b>按物品 / 按批的轮询均分</b>（除不尽时余数自然落到
     * 轮询顺序靠前的总线，且不丢不复制）。</p>
     * <p>总量守恒由「执行舱内部存储的原子抽取 → 目标插入 → 余量回写执行舱」链路保证
     * （见 {@code support/RsccChamberExportStrategy}）：轮到谁，谁才从<b>本仓内部存储</b>里取；
     * 没轮到的取不走，因此不可能丢失或复制。</p>
     *
     * <h2>2026-10-06：这条清单是<b>链级</b>的（用户需求：成链 = 一个逻辑执行仓 / 扩容）</h2>
     * <p>用户原话：「这一条链……相当于给执行仓扩容……它的这一个输入输出总线应该保持一样的，
     * 它应该同步那个执行仓里面的所有内容。」因此本方法产出的过滤项 = <b>整条链的类别并集</b>
     * （{@link #chainCategories()}），而不是只看「总线物理上绑定的那台仓」。</p>
     *
     * <h2>「一类别一属主、一总线一次」（本次最大风险的正面回答）</h2>
     * <ol>
     *     <li><b>一条总线永远只有一台仓计算它的过滤项</b>：输出总线的归属由它自己的线缆 BFS 解析为
     *     <b>唯一一台</b>执行仓（{@code rscc$linkedExecutor}，多台可达时整条总线被停用），
     *     而本方法只由那台仓调用（见 {@code AbstractExporterBlockEntityMixin#rscc$applyExportFilters}）。
     *     因此「排队 / 在制名额 / 跨舱在制计数」这些闸门对同一台总线每轮只可能被求值一次 ——
     *     链展开<b>没有</b>让它们被链上每台仓各算一遍。</li>
     *     <li><b>一个类别永远只有一个属主仓</b>：{@link #chainCategories()} 按 id 去重，
     *     每个类别只带上坐标 / 优先级确定的那一台仓；<b>判据一律由属主仓自己回答</b>
     *     （见 {@link #ownBusExportFilters(BlockPos, Set, Map)}，在属主实例上执行），因此同一个类别的
     *     {@code blockedByMachineQueue} / {@code unitCapacityLeftFor} / {@code hasExportableItem}
     *     每条总线每轮只算一次，且用的是「就像总线接在它身上」的那台仓的判据（不是绑定的那台）。</li>
     *     <li><b>类别 id 去重</b>：{@code handled} 集合保证同一个 id 只被处理一次
     *     （本仓有的类别优先，其后按链成员坐标升序），因此一个类别不会因为「链上两台仓都定义了它」
     *     而产出两份过滤项、被消费两次。</li>
     * </ol>
     */
    public List<ResourceKey> busExportFilters(@org.jetbrains.annotations.Nullable final BlockPos exporterPos) {
        final Level level = getLevel();
        if (level == null || exporterPos == null) {
            return List.of();
        }
        // 「自动合成」门控（服务端权威）：执行舱的自动合成未开启时一律不导出 —— 先有自动合成，
        // 才谈得上把原料 / 中间产物喂进机器。<b>判据用 isAutoCraftingEnabled()（= 有「与本仓相关」的任务）</b>：
        // 与进货侧 fillInternalForBus 同一个闸门。旧版本这里只看 busAutoCraftGate，于是
        // 「网络里只有别人的任务在跑」时导出侧照旧放行、进货侧却关着 —— 输出总线每 tick 都判一次
        // chamber_empty（实测 2184 / 4063 次纯无效重复），坚固板第二次冲压因此永远拿不到料。
        // <b>链语义下这道闸门仍是「总线自己那台仓」的</b>（与搬运层 RsccChamberExportStrategy 的闸门逐字同源，
        // 不会出现「清单里有、搬运层却拒收」的不一致）；链上的每一台成员另外还要各自通过
        // 它自己的同一道闸门，才允许把自己的类别并进来（见下）。
        if (!isAutoCraftingEnabled()) {
            return List.of();
        }
        final List<ResourceKey> result = new ArrayList<>();
        final Set<String> handled = new LinkedHashSet<>();
        // <b>按链成员坐标升序</b>（chainMembers 已排序）逐台并入：先到者先定义，后到者不重复
        // —— 顺序确定，因此同一条链上无论问哪一台仓，得到的都是同一份清单。
        // 归属表由<b>本仓</b>（= 那条总线自己解析出的唯一执行仓）传入：它就是链级表
        // （{@link #chainBusOwners()}，内容与链上任何一台仓算出的逐字相同），
        // 于是份额 / 轮询 / 让位判定在整条链上只用同一张表，不会因为「读的是哪台仓的副本」而分叉。
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            if (member.isAutoCraftingEnabled()) {
                // 该成员「自己的」过滤项：判据全部在它身上执行（就像那条总线接在它身上一样）。
                // 它是自己类别的唯一属主，且 handled 保证一个类别只算一次。
                result.addAll(member.ownBusExportFilters(exporterPos, handled, busCategoryOwners));
            }
            for (final BusCategoryInfo info : member.busCategories()) {
                if (info.id() != null) {
                    handled.add(info.id()); // 无论是否产出过滤项，这个 id 都已由该成员「回答过」
                }
            }
        }
        return result;
    }

    /**
     * {@link #busExportFilters(BlockPos)} 的<b>单仓实现</b>：只算「本仓类别表里定义的那些类别」。
     *
     * <p>调用方是 {@link #busExportFilters(BlockPos)}（链级调度：谁是某个类别的属主，就由谁调用它），
     * 因此本方法内部的一切判据（排队 / 名额 / 在制件 / 空转 / 份额）都天然只被算<b>一次</b>。</p>
     *
     * @param skipIds          已经由链上更靠前的成员回答过的类别 id（链级去重；不串链时为空集）
     * @param ownersByCategory 链级归属表（类别 id → 选择它的输出总线坐标，升序）。由调用方传入
     *                         <b>而不是在这里读本仓那一份</b>：份额 / 轮询必须用同一张表，
     *                         否则「同一条总线」在链上不同副本里可能被算成不同的份额（重复消费）。
     */
    private List<ResourceKey> ownBusExportFilters(final BlockPos exporterPos, final Set<String> skipIds,
                                                  final Map<String, List<BlockPos>> ownersByCategory) {
        final Level level = getLevel();
        if (level == null || exporterPos == null) {
            return List.of();
        }
        final List<ResourceKey> result = new ArrayList<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (info.id() == null || skipIds.contains(info.id())) {
                continue; // 链级去重：同一个类别 id 只由链上第一台定义它的仓回答一次（本仓跳过它）
            }
            final List<BlockPos> owners = ownersByCategory.get(info.id());
            if (owners == null) {
                continue; // 没有任何输出总线选它：不导出（既有语义）
            }
            // 本类别的全部判据整块交给下面那个方法：它「返回」= 本类别到此为止，
            // 与改造前那条 `continue`（本类别不导出、继续下一个类别）逐条对应。
            result.addAll(categoryExportFilters(info, exporterPos, owners));
        }
        return result;
    }

    /**
     * <b>单个类别</b>的导出过滤项（= 改造前 {@code busExportFilters} 循环体，逐行搬过来，未改任何判据）。
     *
     * <p>为什么必须拆出来：链级展开后，一个类别的判据要能<b>在「定义它的那台仓」上</b>执行
     * （见 {@link #busExportFilters(BlockPos)} 的「一类别一属主」），因此它必须是可以单独调用的一次判定；
     * 同时「本类别不导出」的语义必须只结束<b>这一个类别</b>（原代码里的 {@code continue}），
     * 不能连带砍掉后面的类别 —— 这就是本方法每个提前出口都返回 <b>本类别自己</b>的结果的原因。</p>
     *
     * @param owners 该类别归属的输出总线坐标（升序）——由调用方从<b>链级归属表</b>取出后传入，
     *               而不是在这里自己查表：<b>份额 / 轮询必须用同一张链级表</b>，
     *               这样「一条总线只被算一次、只被消费一次」在结构上成立
     */
    private List<ResourceKey> categoryExportFilters(final BusCategoryInfo info, final BlockPos exporterPos,
                                                    final List<BlockPos> owners) {
        final Level level = getLevel();
        final List<ResourceKey> result = new ArrayList<>(2);
        // <b>下面这一大段是从改造前的 {@code busExportFilters} 循环体逐行搬过来的</b>（连原缩进一起保留，
        // 便于与 git 历史逐行对照）：判据一个字都没改，唯一的形式变化是
        // 「本类别不导出」由 {@code continue} 变成 {@code return result}（= 只结束这一个类别）。
            // 「一次一份」节流（仅输入类类别，用户要求）：供料目标机器里还压着<b>没消化掉的</b>
            // 同类输入时原料时，本轮不把该类别交给总线 —— 下一 tick 机器一消化就自动恢复。
            // 与面输出认领（{@link #holdsItem}）同一口径，防止只有一台机械手 / 一台机器时被塞满。
            // <b>本轮按「本总线自己的目标机器」判</b>（见 #occupiedInputMaterialsAt）：一台仓给多台机器
            // 供料时，各机器所处的步骤不同，用全仓并集会把别的机器正需要的那一份料也一起掐掉。
            final Set<Item> occupied = occupiedInputMaterialsAt(exporterPos);
            if (info.isInput() && anyHeld(occupied, info.items())) {
                return result; // 本类别这一轮到此为止（其它类别继续判）
            }
            // <b>流体侧的「一次一份」末端闸门（本轮新增，与物品侧 occupied 严格对称）</b>：本总线朝向的
            // 机器此刻<b>还压着够一批的</b>同类流体（例如注液机罐里还剩 500 mB 岩浆）时，本类别这一轮
            // 干脆不交给总线 —— 否则总线每 tick 都试一次，只能得到
            // {@code DESTINATION_DOES_NOT_ACCEPT/machine_full}（用户点名的岩浆刷屏行：实测 126 次加权、
            // 5 秒窗口内最多 10 条），而执行仓为此抽进自己罐里的那一份流体也只是「抽了不用」。
            // 判据只读（{@link #holdsInputFluidAt}），机器一消耗掉就自愈。
            if (info.items().isEmpty() && !info.fluids().isEmpty()
                && holdsInputFluidAt(level, exporterPos, info.fluids(), Math.max(1L, info.amount()))) {
                return result;
            }
            // <b>归属判定必须先于一切豁免</b>（本轮修正，用户实测最严重的问题）。用户原话：
            // 「我贴的置物台那个地方，<b>只选了金板和那几个中间产物</b>，但是他却<b>输出了一个齿轮</b>」——
            // 根因正是下面那条「步骤专用投入物不分台」的豁免把归属判定一起绕过了：齿轮类别只被两台
            // <b>机械手</b>总线勾选（归属表里只有它们俩），置物台总线与齿轮毫不相干，却照样拿到了齿轮
            // 过滤项。实测日志（latest.log 21:35:30）：
            // <pre>
            // exporter@(-6,-60,6) cats=[input:create:golden_sheet, intermediate:0, intermediate:1, intermediate:2]
            // exporter@(-6,-60,6) push {item=create:cogwheel x1} to=(-7,-60,6) result=EXPORTED
            // </pre>
            // 即「一条总线推了它<b>从没勾选</b>的类别」。因此归属判定提到最前，任何类别都不得例外。
            final int index = owners.indexOf(exporterPos);
            if (index < 0) {
                return result; // 本机没选这一类：本类别到此为止
            }
            // <b>2026-10-06 新增：共用同一台机器的多条配方之间的「排队」（用户需求一）</b>。
            //
            // 用户原话：「谁先下单，那么就谁先站这一个东西，直到它完成了，再去下一个。」
            // 为什么必须压在推料侧：{@link #ownedSteps()} 只是把「同一台机器的多步」并在一起，
            // <b>没有任何互斥</b> —— 冲压仓同时挂着 sturdy_sheet#1,#2 与 track#2 时，两条配方各自的
            // 输出总线在同一 tick 都能拿到过滤项，于是互相抢这一台冲压机、双线同时超额生产
            // （快照 20261006-100050：缓存池压着 96 件 incomplete_track + 6 件 unprocessed_obsidian_sheet）。
            //
            // 排队判据是<b>只读</b>的（见 {@link #machineReservedRecipe}）：赢家 = 共机的配方里
            // 「下单最早、且此刻真的有活可干」的那一条。本类别不属于赢家 ⇒ 这一轮不交给总线：
            // 落选配方的过渡件（= 要让机器加工的那一件）不会下到机器上抢工位，已完成的件就
            // <b>留在缓存仓里等着</b> —— 正是用户要的「某个步骤被堵住时，让前面所有步骤先做完，
            // 把中间产物堆进缓存仓，然后等待」。落选配方若还有一件正压在机器上，则放行它的投入物
            // 先做完（反死锁逃生口，见 {@link #blockedByMachineQueue} 规则 ③）。
            // 判不出来（单配方仓 / 只有一条配方有单 / 归属判不出）时返回 false，行为逐字不变。
            // 本闸门只影响<b>这一份清单快照</b>：机器一腾出来（赢家做完 / 它一时无件可做），
            // 下一次清单重算（≤ {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick，见 busExportRefreshCooldown）
            // 就把落选方放回来，因此最多晚 1 秒恢复，绝不会永久停工。
            if (blockedByMachineQueue(info)) {
                traceMachineQueueSkip(info, exporterPos);
                return result;
            }
            // <b>「此刻推出去必然是空转」的类别一律不交给这一轮</b>（用户要求：修到不再有持续性的
            // 无效重复）。清单是<b>快照</b>（RS 只在归属变化时让总线重建），而总线的搬运策略每 tick 都拿
            // 这份快照去取料 —— 快照里的类别一旦「此刻干不了活」，就会每 tick 判一次失败：
            //   * 齿轮 {@code RESOURCE_MISSING/chamber_empty} 228 次、未完成黑曜石板 793 次（仓里一件都没有）；
            //   * 金板 {@code SKIPPED/machine_busy_with_my_step} 2576 次（工位上还压着在制件）。
            // 两道闸门都<b>只影响清单这一份快照</b>，不动任何搬运 / 认领判定：备料侧（pull）独立于清单，
            // 料一备好、工位一空出来，下一次清单重算（≤ {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick）就恢复。
            final BlockPos filterTarget = supplyTargetOf(level, exporterPos);
            if (info.isInput() && isStartIngredientCategory(info)
                && filterTarget != null && unitInFlightAt(filterTarget)) {
                return result; // 工位上还有在制件：再投一份起步原料就是「多耗一份主原料」，且必然被 machine_busy 拒
            }
            // <b>2026-10-06 新增：起步原料的「跨执行舱在制名额」闸门</b>（详见
            // {@link #startCapacityForRecipe} 的推导）。为什么上面那条不够：件做完工位 A 的这一步、被推到
            // <b>另一个执行舱</b>的机器上之后，A 的工位是空的 —— 只看工位就会再投一份起步原料、再开一件，
            // 正是用户实测的「下单 1 条轨道产出 2 条」「冲压的时候又来了一个」。
            // 与备料侧 {@link #clampStartIngredientTargets} 同一个名额函数，因此推料 / 备料两侧一致；
            // 只影响这份清单快照（料仍留在仓里，下一次清单重算就恢复），绝不搬运 / 销毁任何资源。
            if (info.isInput() && isStartIngredientCategory(info) && !unitCapacityLeftFor(info)) {
                return result; // 订单不允许再开新件：这一轮不把起步原料交给总线
            }
            if (!hasExportableItem(info)) {
                return result; // 仓里此刻一件都推不出去（空转）：不发这一轮
            }
            if (isStepExtraCategory(info)) {
                // 「步骤专用投入物」（机械手手里那件）<b>只豁免「轮询」</b>（用户硬要求：
                // 同一步骤的同一份投入物可能要被多台机器各拿一份）；<b>份额上限照旧成立</b> ——
                // 用户本轮明确：「下单 1 个时只该有一台机器在动，哪一台都行但要稳定」，
                // 机械手也是机器，因此份额不够时它同样不参与（否则又变成两台同时装配）。
                final BlockPos target = supplyTargetOf(level, exporterPos);
                if (target == null) {
                    return result;
                }
                if (!ownsStepExtraTurn(exporterPos, owners, stepExtraProbeOf(info))) {
                    traceShareSkip(info, exporterPos, "step_extra_share_exhausted");
                    return result;
                }
                // <b>用户第 1 条（齿轮堵塞）：这一步此刻只在「真的在加工」的工位上投料</b>。
                // 机械手 / 置物台是「手里握着这份步骤专用投入物、等台上有在制件时再应用」的机器：
                // 把料推给一台<b>此刻没有在制件</b>的机器，只会让那件料<b>卡在它手里</b>（既不被消耗、
                // 也不会被收回）——实测 {@code exporter@(-6,-58,6) push {item=create:cogwheel}
                // to=machine@(-7,-58,6)} 就是那件永久卡死的齿轮。判据只读，且「一个工位都不在加工」时
                // <b>整条放行</b>（退回份额口径，保留「先给一台预装」的既有语义），绝不因判不出来而断供。
                if (!stepExtraOnlyWorkingStations(exporterPos, owners)) {
                    return result;
                }
                for (final Item item : info.items()) {
                    if (item != null && inputMaterialWantedNow(target, item)) {
                        result.add(new ItemResource(item));
                    }
                }
                return result;
            }
            // <b>份额（本轮核心语义修正）</b>：一次自动合成的<b>下单数量</b>决定同时参与生产的
            // 机器 / 总线台数上限 —— 份额 = min(总归属台数, 订单剩余量)。因此：
            //   * 下单 1 个 + 两台机器 → 只有份额内那一台导出（另一台拿不到任何过滤项 ⇒ 不动作）；
            //   * 一条执行器挂两条<b>完全相同</b>的输出总线 → 同一时刻只用其中一条（份额 = 1）；
            //   * 下单 4 个 + 两台机器 → 两台都参与（份额 = 2）；
            //   * 判定完全由「归属表顺序 + 订单剩余量」决定，<b>与 gameTime 无关</b> ⇒ 稳态零抖动。
            // 旧实现是 {@code gameTime % owners.size()}，等价于「每台各拿一半时间」，
            // 于是下单 1 个时两台机器都会拿到料、都产出（用户实测最严重的问题）。
            if (index >= exportShare(owners.size())) {
                // 例外：份额内的那些总线<b>全部</b>都收不下这一份资源时，让出这一轮到份额外的总线 ——
                // 否则一台真的卡住的机器会把整条线饿死（不得死锁）。判据见 ownsFallbackTurn 的说明：
                // 机器只是「正在加工」（手里压着在制件 / 同种料）<b>不算</b>收不下。
                //
                // <b>2026-10-05 修「下单 10 个精密构件仍多发一个金板 / 两个置物台各一件」。</b>
                //
                // 实测（日志 [rscc-trace] + 快照 20261005-193605）：
                //   exporter@(-6,-60,6) bus_push golden_sheet -> machine@(-7,-60,6) EXPORTED/ok
                //   exporter@(-6,-60,5) bus_push golden_sheet -> machine@(-7,-60,5) EXPORTED/ok
                //   exporter@(-6,-60,6) bus_skip  golden_sheet reason=DESTINATION_DOES_NOT_ACCEPT/machine_full
                // 也就是：份额内那台（-7,-60,6）正忙 ⇒ 份额外的（-7,-60,5）<b>顶上来又投了一份金板</b>
                // ⇒ 两个置物台各一件、订单只做 1 件却开了 2 件在制件。
                //
                // 根因：本例外只检查「份额内的机器是否收不下」，<b>却没检查「还有没有在制名额」</b>。
                // 「机器在加工」确实不是「收不下」，但它同样意味着<b>已经有一件在制了</b> ——
                // 此时让份额外的总线再投一份，就是在<b>多开一件在制件</b>，正是这一例外要避免的事。
                //
                // 修法：让位<b>先过在制名额闸门</b> ——
                //   允许在制件数（{@link #allowedConcurrentUnits()}）− 当前在制件数（{@link #inFlightUnitCount()}）≤ 0
                // 时，说明订单不允许再开新件，份额外总线必须继续等（份额内的那台加工完自然会放行）。
                // 该闸门与备料侧 {@code :5753} 的 cap 口径<b>完全同源</b>，因此推料 / 备料两侧一致。
                //
                // <b>2026-10-06：名额改成「按配方」算（必得配方严格、概率配方动态）</b>——
                // 原式在这里对起步原料类别是错的：一个仓同时挂「轨道（下单 1）」与「精密构件（下单 64）」
                // 时，整仓名额 64 会把石头台阶也一起放行；而跨执行舱在制件（件已推到别的仓的机器上）
                // 根本不在这条式子里。起步原料类别改走 {@link #unitCapacityLeftFor}（→
                // {@link #startCapacityFor} → {@link #startCapacityForRecipe}，含跨舱计数与概率放大），
                // <b>其它类别一字未改</b>（旧式就在 {@link #unitCapacityLeftFor} 的非起步原料分支里）。
                final boolean unitCapacityLeft = unitCapacityLeftFor(info);
                if (!unitCapacityLeft || !ownsFallbackTurn(exporterPos, owners, shareProbeOf(info))) {
                    traceShareSkip(info, exporterPos, "share_exhausted_order_remaining");
                    return result;
                }
            }
            // 中间产物类别：过滤项 = 过渡件 + 该步的进度组件（一个类别一项）。
            // <b>为什么不能只给裸物品</b>：同一配方里第 2、3 步可以是同一个处理器（坚固板都是冲压），
            // 两处过渡件是同一个物品 —— 只给裸 {@code ItemResource} 会让两个类别退化成同一个过滤项
            // 而互相覆盖（用户实测「只能输出一个第二步的中间产物」）。带上步序后，勾中任一类别
            // 都只放行该步的件；匹配点见 {@link #busExportMatches}（输出总线推料侧）
            // 与 {@link #matchesCategoryStep}（输入总线收回侧）。
            if (info.hasStepFilter()) {
                // <b>2026-10-06 撤销我上一版的「去重」—— 它是个回归。</b>
                //
                // 上上版我让「同一个裸物品只手出最低那一步的过滤项」，理由是想消除推送时的步序歧义。
                // 但用户实测证伪了它：<b>只勾显示的第 3 步（内部第 2 步）时「冲压一次，然后就回去了」</b>。
                // 机制：去掉第 2 步的过滤项后，件被压一次、步序变成第 2 步，而导出清单里只剩第 1 步的
                // 过滤项 ⇒ {@code busExportMatches} 不再匹配 ⇒ 推不出去；而导入总线的选择里有第 2 步
                // ⇒ 那份件被抄回网络。于是恰好表现为「压一次就回去」。
                //
                // 正确认识：<b>过滤项本身不决定「做哪一步」</b> —— 步序由<b>件自身的
                // {@code SEQUENCED_ASSEMBLY} 组件</b>决定，机器按其加工。
                // 过滤项只回答「这一份要不要交给这条总线」，多个同类过滤项是<b>幂等的</b>，
                // 不会造成「做错步」。因此这里恢复「每个步类别各自出各自的过滤项」。
                result.add(ItemResource.ofItemStack(info.filterPrototype()));
                return result; // 中间产物类别没有流体，本类别到此为止
            }
            for (final Item item : info.items()) {
                if (item != null) {
                    result.add(new ItemResource(item));
                }
            }
            for (final Fluid fluid : info.fluids()) {
                if (fluid != null) {
                    result.add(new FluidResource(fluid));
                }
            }
        return result;
    }

    // ========== 份额分配（「下单数量」决定同时参与生产的机器 / 总线台数；本轮核心语义） ==========

    /**
     * <b>本仓此刻允许几台输出总线（= 几台机器）同时参与生产</b>（本轮核心语义修正的唯一入口）。
     *
     * <h2>为什么必须是「下单数量」而不是「有几个人选了它」</h2>
     * 用户本轮原话：「我明明下单的是<b>一个</b>，他却还是<b>两台机器同时输出</b>……如果说有两台机器，
     * 我又只下单一个的话，你应该<b>只分配给其中一个机器</b>（哪一台无所谓，但要稳定）」。
     * 旧实现用 {@code gameTime % owners.size()} 把类别按 tick 轮流交给各台（各拿一半时间），
     * 于是两台机器都会先后拿到同一份料、都各自产出一件 —— 正是「下单 1 个、两台同时输出」的机制。
     *
     * <h2>规则</h2>
     * <pre>
     *   份额 = clamp(订单剩余量, 1, 归属台数)
     *   归属表里<b>下标 &lt; 份额</b>的那些总线参与；其余总线本 tick 拿不到任何过滤项（无份额则不动作）。
     * </pre>
     * <p>归属表在 {@link #normalizeBusOwners()} 里按坐标升序稳定排序，因此「哪几台参与」是
     * <b>确定且稳定</b>的函数：只随「订单剩余量跨过一个整数」而变化，<b>与 gameTime 无关</b> ——
     * 同一份状态永远给同一批总线，绝不每 tick 换机器。</p>
     * <p>订单推进时剩余量自然变小 ⇒ 份额回收（例如下单 4、两台机器：跑完 2 个后份额降到 1，
     * 只剩一台继续）；订单完成 / 取消时门控整条关闭（{@link #busAutoCraftGate}），份额不再被使用。</p>
     * <p><b>判不出来时取「在制件数」这一保守下界（本轮修正，不再按全部台数）</b>：旧实现在读不到
     * 剩余件数时直接返回 {@code ownerCount}（= 有几台就几台在推），正是「下单 1 件却多耗一份起步原料」
     * 的机制（短命任务常读不到剩余件数）；现在一律经 {@link #allowedConcurrentUnits()}，
     * <b>不确定时取小不取大</b>。</p>
     */
    private int exportShare(final int ownerCount) {
        if (ownerCount <= 1) {
            return Math.max(0, ownerCount);
        }
        // <b>本轮修正（用户第 ①⑤ 条：下单 1 件≠64 件 / 数量快速增减）</b>：旧实现在
        // remaining ≤ 0（判不出来）时直接 <b>return ownerCount</b> —— 判不出来 = 份额被整个跳过 =
        // 「有几台机器就有几台在推」。这正是「下单 1 件却多耗一份黑曜石粉」的机制：短命任务常读不到
        // 剩余件数 → 两条输出总线同时把「起步原料」推给两台机器 → 各开一件在制件 → 多耗一份粉；
        // 而 64 件的长任务读得到 remaining=64 → 闸门生效，所以「下单 64 件反而没问题」。
        // 现在一律经 {@link #allowedConcurrentUnits()}：判不出来时取「本仓当下真在制 / 待补的件数」
        // 这一保守下界（至少 1），<b>不确定时取小不取大</b> —— 下单 1 件时精确只允许 1 件在制。
        final long allowed = allowedConcurrentUnits();
        return (int) Math.max(1L, Math.min(ownerCount, allowed));
    }

    /**
     * 「份额内的那一批总线」此刻是否<b>全都干不了活</b>（= 目标机器没被识别 / 机器还压着上一份）。
     * <p>用于 {@link #ownsFallbackTurn} 的例外判定：份额是「同时最多几台在动」的<b>上限</b>，
     * 不是「只许固定这几台」的死结 —— 份额内的机器卡住（或被拆掉、目标不是容器）时，
     * 份额外的总线可以顶上，因此「一台机器被别的东西占死 ⇒ 整条线再也不动」这种死锁不会出现。</p>
     */
    private boolean shareFrontBusy(final List<BlockPos> owners, final int index) {
        final Level level = getLevel();
        for (int i = 0; i <= index && i < owners.size(); i++) {
            if (level == null) {
                return false;
            }
            if (supplyTargetOf(level, owners.get(i)) == null) {
                continue; // 这台没有可供给的目标容器（被拆 / 朝向不对）→ 它本来就干不了活
            }
            final Set<Item> occupied = occupiedInputMaterialsAt(owners.get(i));
            if (occupied.isEmpty()) {
                return false; // 有一台没被占：它还能干活，不需要让位
            }
        }
        return true;
    }

    /**
     * 「份额内的这些总线是不是<b>真的收不下这一份资源</b>」——{@link #shareFrontBusy} 的<b>按资源</b>版本。
     *
     * <h2>为什么要按「收得下 / 收不下」而不是「机器里压着东西」（本轮修正）</h2>
     * 旧判据把「目标机器里压着<b>在制件</b>」当成「这台干不了活」，于是份额内的机器<b>刚被喂进一份
     * 起步原料</b>（这是正常加工，不是卡住）时就让位给份额外的总线 —— 用户实测后果：
     * 下单 1 个坚固板 / 精密构件，却有<b>两台机器各被推了一份起步原料</b>、各自开一件在制件
     * （原话：「在另外一台机器下方输出了一次金板，而那个机械手并没有获得输入时产物」）。
     *
     * <p><b>判据（只读，三道，顺序即优先级）</b>：
     * ① 目标机器<b>所属工位已经握着这一份</b>（{@link #supplyTargetHoldsItem}：手 / 台上还有未被消耗的
     * 投入物）→ 它<b>正在干活</b>，绝不让位（用户硬要求：同一台机器同一时刻最多一份未消耗投入物）；
     * ② 目标机器<b>此刻还能收下这一份</b>（{@link #targetCanAccept}，= 一个 SIMULATE 插入）→ 不让位；
     * ③ 目标机器上压着「正等本仓某一步加工」的在制件（{@link #stepUnitInFlightOn}）→ 也不让位。
     * 三者皆否（无容器 / 容器满 / 拒收且无在制件）才算「真的干不了活」。
     * <b>resource 为 {@code null} 时退化成旧口径</b>（过渡件 / 产物类没有「一份具体物品」可比对，
     * 见 {@link #shareProbeOf}）。</p>
     */
    private boolean shareFrontBusyFor(final List<BlockPos> owners, final int index,
                                      @org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return shareFrontBusy(owners, index);
        }
        final Level level = getLevel();
        for (int i = 0; i <= index && i < owners.size(); i++) {
            if (level == null) {
                return false;
            }
            final BlockPos target = supplyTargetOf(level, owners.get(i));
            if (target == null) {
                continue; // 没有可供给的目标容器（被拆 / 朝向不对）→ 它本来就干不了活
            }
            // <b>「前位机器手上还有一份没被消耗掉的同类投入物」→ 它正在干活，绝不让位</b>
            //（用户硬要求：同一台机器同一时刻最多一份未消耗投入物）。这一条是<b>必需</b>的：
            // 置物台只有 1 格 / 机械手只有 1 个手位，前位机器一旦已经握着这份料，一次 SIMULATE
            // 插入必然失败 —— 只看「收得下吗」就会把「已握料」误判成「收不下」，
            // 于是份额外那条总线顶上、把同一份起步原料推给第二台机器
            //（实测：{@code share=1 owners=2 remaining=1} 却仍然
            // {@code exporter@(-6,-60,6) push {item=create:golden_sheet} to=machine@(-7,-60,6)}），
            // 那一份随后又被输入总线原样收回 —— 用户看到的正是「金板多输出一个、是多余的」。
            if (supplyTargetHoldsItem(level, target, item)) {
                return false;
            }
            if (targetCanAccept(level, target, item)) {
                return false; // 它还能收下这一份 → 还干得了活，不需要让位
            }
            // <b>「机器里压着正在加工的这一件」不算「收不下」</b>（本轮修正，用户实测「下单 1 个却两台机器
            // 各被推一份起步原料」的根因）：置物台只有 1 格、机械手只有 1 个手位，因此机器正加工着
            // 本仓这一步的在制件时，一次 SIMULATE 插入<b>必然失败</b> —— 旧判据据此认定份额内那台
            // 「收不下这一份」→ 让份额外的总线顶替 → 于是两台机器各拿到一份起步原料、各开一件在制件
            // （用户原话：「在另外一台机器下方输出了一次金板，而那个机械手并没有获得输入时产物」）。
            // 机器正在加工 = 它<b>正在干活</b>，绝不该让位；只有「机器空着却拒收」（朝向不对 / 容器满 /
            // 手里压着别的东西）才是真的收不下。
            // <b>「工位上压着本仓产线的一件东西」⇒ 它正在干活，绝不让位（本轮新增）</b>。
            // 机械手一次只拿一件：手里握着<b>本步的另一样投入物</b>时（例如这一步要的是大齿轮），
            // {@link #targetCanAccept} 对本件（齿轮）必然为 {@code false} —— 旧判据据此认定
            // 「份额内那台干不了活」，于是份额外的第二条总线顶上，把齿轮推给<b>第二台这一步根本
            // 用不上它的机械手</b>（实测 {@code exporter@(-6,-58,6) push {item=create:cogwheel}
            // to=machine@(-7,-58,6)}），那件齿轮既推不进去、也不会被消耗，就永久卡在第二台机械手上
            // （用户第 ② 条「齿轮还是堵塞」的直接来源）。判据只认「本仓类别里的物品」
            // （{@link #machineBusyOnPipeline}）：外来杂物不算「在干活」，因此「机器真的被别的东西
            // 占死」时仍然照旧让位，不会死锁。
            if (machineBusyOnPipeline(level, target)) {
                return false;
            }
            if (stepUnitInFlightOn(target)) {
                return false;
            }
        }
        return true;
    }

    /**
     * <b>只读</b>：「步骤专用投入物」这一步此刻是否应当只发给「真的在加工」的那些工位。
     *
     * <h2>为什么要它（用户第 1 条「齿轮堵塞」的直接来源）</h2>
     * <p>机械手 / 置物台是「手里握着这份步骤专用投入物、等台上有在制件时再应用」的机器。把料推给一台
     * <b>此刻没有在制件</b>的机器，那件料会<b>卡在它手里</b>：既不会被消耗（没有在制件可应用），
     * 也不会被输入总线收回（收回侧保护输入类原料）—— 实测 {@code exporter@(-6,-58,6) push
     * {item=create:cogwheel} to=machine@(-7,-58,6)} 就是那件永久卡死的齿轮。</p>
     *
     * <p><b>判据</b>：在归属表里找「供料目标压着本仓某一步在制件」（{@link #stepUnitInFlightOn}）的工位。</p>
     * <ul>
     *     <li>一个这样的工位都没有（比如换批的空档）→ <b>整条放行</b>，退回份额口径，
     *     保留「先给一台预装」的既有语义，绝不因判不出来而断供；</li>
     *     <li>有 → 只放行<b>本机自己就是这类工位</b>的那些总线；其余本类别这一轮不拿过滤项。</li>
     * </ul>
     * <p>只读，绝不搬运 / 销毁；同一份机器状态给出同一个结论，不会每 tick 抖动。</p>
     */
    private boolean stepExtraOnlyWorkingStations(final BlockPos exporterPos, final List<BlockPos> owners) {
        final Level level = getLevel();
        if (level == null) {
            return true; // 判不出来 → 放行
        }
        boolean anyWorking = false;
        boolean mineWorking = false;
        for (final BlockPos owner : owners) {
            final BlockPos target = supplyTargetOf(level, owner);
            if (target == null || !stepUnitInFlightOn(target)) {
                continue;
            }
            anyWorking = true;
            if (owner.equals(exporterPos)) {
                mineWorking = true;
            }
        }
        return !anyWorking || mineWorking;
    }

    /**
     * <b>只读</b>：这一格机器此刻是否压着「<b>本仓产线里的一件东西</b>」（本仓任一类别的物品 /
     * 过渡件 / 在制件）—— 也就是「<b>它正在干活</b>」。
     *
     * <h2>为什么要单独认这一条（用户第 ② 条「齿轮还是堵塞」）</h2>
     * 机械手 / 置物台一次只处理一件：手里握着本步的<b>另一样</b>投入物时，对本件的一次 SIMULATE
     * 插入必然失败（Create 的 {@code DeployerItemHandler} 对「手里已有不同物品」一律拒收）。
     * 只看「收得下吗」就会把「手里正拿着本步的另一样料」误判成「这台干不了活」，
     * 于是份额外的总线顶上、把这一件推给<b>第二台用不上它的机器</b>，然后永久卡在那里。
     * 判据故意<b>只看本仓类别里的物品</b>（而不是「压着任何东西」）：外来杂物不算「在干活」，
     * 因此「机器真的被别的东西占死」时仍然会走让位分支，不会死锁。只读，绝不搬运 / 销毁。</p>
     */
    private boolean machineBusyOnPipeline(final Level level, final BlockPos target) {
        final Set<Item> held = occupiedItemsOf(level, target);
        if (held.isEmpty()) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            for (final Item item : info.items()) {
                if (item != null && held.contains(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 只读：目标机器此刻能否收下这一份物品（无容器 / 已满 / 拒收 → {@code false}）。
     * <p>用一次 {@code SIMULATE} 插入判定，与推料侧真正搬运时用的是同一个能力（{@code ItemHandlerHelper}），
     * 因此「判得能收下」与「真的收得下」口径一致；只读，绝不搬运。</p>
     */
    private static boolean targetCanAccept(final Level level, final BlockPos target,
                                           @org.jetbrains.annotations.Nullable final Item item) {
        if (level == null || target == null || item == null) {
            return false;
        }
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, target);
        if (handler == null) {
            return false;
        }
        final ItemStack probe = new ItemStack(item, 1);
        return net.neoforged.neoforge.items.ItemHandlerHelper
            .insertItem(handler, probe, true).getCount() < probe.getCount();
    }

    /**
     * 份额判定用的「一份具体物品」探针（= 该类别真正要推出去的那一件）。
     * <p>只有「单一物品 + 非按步过滤」的类别才给得出（原料 / 产物类）；按步过滤的中间产物类别与
     * 流体类别返回 {@code null} → 让位判定退化回旧口径（{@link #shareFrontBusy}）。</p>
     */
    @org.jetbrains.annotations.Nullable
    private static Item shareProbeOf(final BusCategoryInfo info) {
        if (info == null || info.hasStepFilter() || info.items().size() != 1) {
            return null;
        }
        return info.items().get(0);
    }

    /**
     * 份额外的总线是否应当<b>顶替</b>这一轮（唯一让位条件：份额内的机器<b>全都收不下这一份</b>，
     * 且本机目标收得下）。
     * <p>让位的判定同样只读、确定：同一份机器状态给出同一个结论，因此不会退化成每 tick 抖动。</p>
     * <p><b>本轮修正</b>：判据换成「收得下 / 收不下这一份」（{@link #shareFrontBusyFor}）——
     * 「机器里压着在制件」是<b>正常加工</b>，不再触发让位（否则会多推一份起步原料、多开一件在制件，
     * 即用户实测的「另外一台机器下方多输出了一次金板」）。{@code item == null} 的类别（过渡件 / 流体）
     * 仍走旧口径，保证「一台真被占死的机器不失锁」。</p>
     */
    private boolean ownsFallbackTurn(final BlockPos exporterPos,
                                     final List<BlockPos> owners,
                                     @org.jetbrains.annotations.Nullable final Item item) {
        final int index = owners.indexOf(exporterPos);
        final int share = exportShare(owners.size());
        if (index < share) {
            return true; // 份额内：本来就有权
        }
        if (!shareFrontBusyFor(owners, share - 1, item)) {
            return false; // 份额内的机器还能干活 → 不越份额
        }
        // 份额内全都收不下这一份：本机目标收得下就可以顶上（否则这一轮谁都拿不到 → 卡死）
        final Level level = getLevel();
        if (item != null) {
            return targetCanAccept(level, supplyTargetOf(level, exporterPos), item);
        }
        final Set<Item> mine = occupiedInputMaterialsAt(exporterPos);
        return mine.isEmpty();
    }

    /**
     * 「步骤专用投入物」类别的份额判定（只豁免轮询，不豁免份额，见 {@link #exportShare}）。
     * <p>同样带「份额内机器全被占则让位」的例外，理由与 {@link #ownsFallbackTurn} 相同。</p>
     *
     * <p><b>本轮修正（用户实测「齿轮堵着推不进去」）</b>：让位判据原先固定用「机器里压着任何物品即算
     * 干不了活」（{@code item = null} 的旧口径）—— 而机械手 / 置物台<b>正在加工</b>时手里必然压着东西，
     * 于是份额内那台永远被判成「干不了活」→ 份额外的总线被放行 → 两台机器同时被推入<b>同一步</b>的投入物
     * （实测 {@code step_extra_share_exhausted} 与 {@code bus_push} 交替出现 205 / 394 次）。
     * 现在改为传入该类别那件<b>具体投入物</b>，让位判据与物品 / 起步原料侧同一个
     * （{@link #shareFrontBusyFor}：只有「前位机器收不下这一件、且它并没有在加工本仓这一步的件」才让位）。
     * 类别里含多件物品时退回旧口径（{@code null}），保持既有行为。</p>
     */
    private boolean ownsStepExtraTurn(final BlockPos exporterPos,
                                      final List<BlockPos> owners,
                                      @org.jetbrains.annotations.Nullable final Item item) {
        final int index = owners.indexOf(exporterPos);
        if (index < 0) {
            return false;
        }
        return ownsFallbackTurn(exporterPos, owners, item);
    }

    /**
     * 「步骤专用投入物」类别用于让位判定的<b>一件具体投入物</b>：类别里恰好只有一种物品时返回它，
     * 否则返回 {@code null}（退回旧口径）。只读。
     */
    @org.jetbrains.annotations.Nullable
    private static Item stepExtraProbeOf(final BusCategoryInfo info) {
        if (info == null || info.items().size() != 1) {
            return null;
        }
        return info.items().get(0);
    }

    // ========== 共享机器的排队：「谁先下单，谁先站这台机器，直到它做完再轮到下一个」 ==========

    /**
     * <b>只读</b>：本仓这台机器此刻被<b>哪一条配方</b>占着（= 共用同一台机器的多条配方之间的排队赢家）。
     *
     * <h2>用户原话与它对应的机制（本轮需求一）</h2>
     * <p>用户原话：「<b>谁先下单，那么就谁先站这一个东西，直到它完成了，再去下一个。</b>」
     * 现场（快照 {@code 20261006-100050}）：冲压仓同时承担
     * {@code create:sequenced_assembly/sturdy_sheet} 的第 2、3 步与
     * {@code create:sequenced_assembly/track} 的第 3 步 ——
     * {@code ownedSteps = create:sequenced_assembly/sturdy_sheet#1,#2, create:sequenced_assembly/track#2}
     * （同一台机器被两条配方共用）。而既有的 {@link #ownedSteps()} <b>只把这几步并在一起，
     * 没有任何互斥 / 排队</b>：两条配方各自的输出总线在同一个 tick 里都能拿到过滤项，
     * 于是互相抢这一台冲压机、双线同时超额生产（缓存池一次压着 96 件 {@code create:incomplete_track}
     * + 6 件 {@code create:unprocessed_obsidian_sheet}）。本方法就是那道<b>只读</b>的排队判据。</p>
     *
     * <h2>「下单先后」从哪里读（先查清，再决定次序）</h2>
     * <p>RS 的任务状态里<b>读得到下单时刻</b>：{@link TaskStatus.TaskInfo#startTime()} ——
     * RS 在<b>创建任务</b>那一刻写入 {@code System.currentTimeMillis()}（RS 源码
     * {@code api/autocrafting/task/TaskImpl.java} 第 71 行 {@code this.startTime = System.currentTimeMillis();}），
     * 并且随任务存档一起读写（{@code common/autocrafting/autocrafter/TaskSnapshotPersistence.java}
     * 的 {@code START_TIME} 标签），因此<b>重启后仍保持原始先后</b>。本仓本来就在读同一个
     * {@link TaskStatus}（见 {@link #remainingOrderUnitsFor(String)}），不新增第二套任务查询。</p>
     * <p><b>为什么不能用任务 id 排序</b>：{@code TaskId#create()} 是 {@code UUID.randomUUID()}
     * （随机，与下单时间无关）—— 拿它排序等于抛硬币。因此先后一律取 {@code startTime}，
     * 平局才用「配方 id 字典序」这个<b>确定性</b>次序兜底。</p>
     *
     * <h2>判据（全部只读，绝不搬运 / 销毁任何资源）</h2>
     * <ol>
     *     <li><b>候选</b> = {@link #ownedSteps()} 里本仓负责的配方中，此刻<b>还有未完成订单</b>
     *     （{@link #remainingOrderUnitsFor(String)} &gt; 0；挂起的任务已被它排除）的那些；</li>
     *     <li>候选还必须「<b>此刻真的用得上这台机器</b>」（{@link #machineHasWorkFor}）——
     *     这正是用户那句里的那个「或」：「直到它完成了（<b>或该配方暂时没有可加工的件</b>），才让给下一条」。
     *     先下单的那条若根本没有件可做，机器立刻让出来，绝不空占；这同时是<b>反饿死</b>的护栏
     *     （一条永远排不到活的前序配方不会把整台机器锁死）；</li>
     *     <li>候选不足 2 条（没有争用 / 判不出来）⇒ 返回 {@code null} = <b>不排队</b>，
     *     行为与改造前逐字一致（单配方仓、以及「只有一条配方有单」的绝大多数时刻都走这条）；</li>
     *     <li>否则按 {@code (下单时刻, 配方 id)} 升序取最小者 = 赢家。</li>
     * </ol>
     *
     * <h2>局限（必须写清楚，绝不假装它就是「严格的下单顺序」）</h2>
     * <ul>
     *     <li>{@code startTime} 是<b>墙上时钟</b>（毫秒），不是游戏刻：同一毫秒内的两笔订单由
     *     「配方 id 字典序」打破平局 —— 那是<b>确定性</b>次序，但<b>不等于</b>真实先后；</li>
     *     <li>服务器改系统时间 / 从别的存档导入任务后，先后可能与玩家的直觉不一致；</li>
     *     <li>读不到下单时刻（{@link #orderStartTimeFor(String)} 返回 {@code -1}）的配方
     *     <b>不参与当赢家</b>（排在最后）—— 「读不到先后就不让它独占机器」，宁可退回既有行为，
     *     也绝不因为读不到就把机器锁在某条配方上。</li>
     * </ul>
     *
     * <p>只读；每 tick 至多真算一次（{@link #machineTurnProbeTick} 一族），同一份世界状态给出同一个答案，
     * 因此不会每 tick 换赢家、不会抖动。</p>
     */
    @org.jetbrains.annotations.Nullable
    private String machineReservedRecipe() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        final long now = level.getGameTime();
        if (now != machineTurnProbeTick) {
            machineTurnProbeTick = now;
            machineTurnProbed = false;
            machineTurnRecipe = null;
        }
        if (!machineTurnProbed) {
            machineTurnProbed = true;
            machineTurnRecipe = computeMachineReservedRecipe(level);
        }
        return machineTurnRecipe;
    }

    /** {@link #machineReservedRecipe()} 的唯一实现（只读；由每 tick 缓存保证至多算一次）。 */
    @org.jetbrains.annotations.Nullable
    private String computeMachineReservedRecipe(final Level level) {
        final Set<String> owned = ownedSteps().keySet();
        if (owned.size() < 2) {
            return null; // 本仓只服务一条配方：根本不存在「共用机器」，行为逐字不变
        }
        String winner = null;
        long winnerStart = Long.MAX_VALUE;
        int contenders = 0;
        for (final String recipeId : owned) {
            if (recipeId == null || recipeId.isEmpty()) {
                continue;
            }
            if (remainingOrderUnitsFor(recipeId) <= 0L) {
                continue; // 没有未完成订单（含被挂起的）：它此刻不占这台机器
            }
            if (!machineHasWorkFor(level, recipeId)) {
                continue; // 暂时没有可加工的件：让给下一条（用户原话里的「或」）
            }
            contenders++;
            // 判不出来（-1）⇒ 映射成「最晚」，即不让它当赢家（见上面 javadoc 的局限第 ③ 条）
            final long start = orderStartTimeFor(recipeId);
            final long key = start < 0L ? Long.MAX_VALUE : start;
            if (winner == null || key < winnerStart
                || (key == winnerStart && recipeId.compareTo(winner) < 0)) {
                winner = recipeId;
                winnerStart = key;
            }
        }
        return contenders < 2 ? null : winner;
    }

    /**
     * <b>只读</b>：这条配方此刻在<b>这台机器上还有活可干</b>吗（= 它还会把件送到本仓这台机器上加工）。
     *
     * <p>四路，任一成立即「有活」（顺序即从「最确定」到「最间接」）：</p>
     * <ol>
     *     <li>本仓内部存储里已经压着一件它的过渡件（{@link #storedUnitsForRecipe(String)}）—— 立刻可推；</li>
     *     <li>本仓某个工位上正压着它的在制件（{@link #registeredUnitsForRecipe}）—— 机器正在做它的件；</li>
     *     <li>网络共享缓存池（缓存仓磁盘）里压着它的过渡件、且<b>下一步归本仓</b>
     *     （{@link #pooledTransitionalUnits(Level, String)} 的第 ② 项）—— 备料侧会把那一份拉进来接着加工；</li>
     *     <li>它还能再开新件（{@link #startCapacityForRecipe(String)} &gt; 0）<b>且起件步就归本仓</b>
     *     （{@link #isOwnedStep}）—— 新开的件会在本仓这台机器上开工。</li>
     * </ol>
     * <p>四路皆否 ⇒ 它此刻确实用不上这台机器 ⇒ 让给下一条配方（绝不空占）。
     * 全部只读，绝不搬运 / 销毁任何资源。</p>
     */
    private boolean machineHasWorkFor(final Level level, final String recipeId) {
        if (storedUnitsForRecipe(recipeId) > 0L) {
            return true;
        }
        if (registeredUnitsForRecipe(level, recipeId, inFlightMarkerItems(recipeId)) > 0L) {
            return true;
        }
        if (pooledTransitionalUnits(level, recipeId)[1] > 0L) {
            return true;
        }
        return startCapacityForRecipe(recipeId) > 0L && isOwnedStep(recipeId, 0);
    }

    /**
     * <b>只读</b>：这个类别属于<b>哪几条配方</b>（{@link #machineReservedRecipe()} 排队闸门的归属判据）。
     *
     * <p>解析口径与既有的 {@link #categoryRecipeOrdered(BusCategoryInfo)} 完全同源，不新增第二套：</p>
     * <ul>
     *     <li>中间产物类别：{@link BusCategoryInfo#recipe()} 自带配方段（{@code intermediate:<配方>:<步序>}）；</li>
     *     <li>输入类别：配方段夹在类别 id 里（{@code input:<配方>#<物品>}）；</li>
     *     <li>老格式（{@code input:<物品>}，没有配方段）⇒ 退回「该物品在本仓哪几条配方里是投入物」；</li>
     *     <li><b>流体 / 成品 / 废料类别返回空集</b>：它们不参与排队 —— 正在加工的那一件对岩浆之类的
     *     消耗属于「把已经开好的件做完」，拦它只会把在制件饿死。</li>
     * </ul>
     */
    private Set<String> categoryRecipeIds(final BusCategoryInfo info) {
        if (info == null) {
            return Set.of();
        }
        final String declared = info.recipe();
        if (declared != null && !declared.isEmpty()) {
            return Set.of(declared); // 中间产物类别：配方段就在类别里
        }
        final String id = info.id();
        if (id == null || !id.startsWith(RsccBusCategory.INPUT_PREFIX)) {
            return Set.of(); // 流体 / 成品 / 废料：不参与排队
        }
        final int hash = id.indexOf('#');
        if (hash > RsccBusCategory.INPUT_PREFIX.length()) {
            return Set.of(id.substring(RsccBusCategory.INPUT_PREFIX.length(), hash)); // 新格式
        }
        // 老格式：按物品回退（主原料候选 ∪ 各步的步骤专用投入物）
        final Set<String> byItem = new LinkedHashSet<>();
        for (final Item item : info.items()) {
            if (item == null) {
                continue;
            }
            byItem.addAll(startIngredientRecipes(item));
            for (final Map.Entry<String, Map<Integer, Set<Item>>> entry : stepExtraInputs().entrySet()) {
                for (final Set<Item> items : entry.getValue().values()) {
                    if (items.contains(item)) {
                        byItem.add(entry.getKey());
                    }
                }
            }
        }
        return byItem;
    }

    /**
     * <b>只读</b>：这一类别此刻是否因为「<b>共用同一台机器的另一条配方正占着它</b>」而必须停手
     * （推料侧 {@link #busExportFilters} 与备料侧 {@link #fillInternalForBus} 共用这一道闸门）。
     *
     * <h2>三条规则（顺序即优先级）</h2>
     * <ol>
     *     <li>类别属于赢家（{@link #machineReservedRecipe}）／归属判不出来（空集）／根本没有争用
     *     ⇒ <b>不拦</b>。归属判不出来就退回改造前的行为，绝不用一个猜出来的归属去掐料；</li>
     *     <li>落选配方<b>且类别是过渡件（中间产物）</b> ⇒ <b>拦</b>。这就是「谁先站这台机器」的物理含义：
     *     机器的加工件只有一个来源，落选配方的过渡件一旦下去就是抢工位；拦下它，落选配方的件就
     *     停在缓存仓里等着（用户原话：「把中间产物堆进缓存仓，然后等待」）；</li>
     *     <li>落选配方的其余类别（起步原料 / 步骤专用投入物 / 流体）⇒ 默认也<b>拦</b>
     *     （「不要继续开新件」），<b>但有一条物理逃生口</b>：机器上此刻正压着这条落选配方的一件在制件
     *     （{@link #loserOnMyStations}）时一律放行 —— 先把它做完、让机器腾出来。
     *     没有这条逃生口，一个卡在机器上的落选件会把赢家<b>也</b>一起锁死（机器的加工位只有一个），
     *     那是任何串行化方案都必须避开的死锁。</li>
     * </ol>
     * <p>只读，绝不搬运 / 销毁任何资源；判据全部来自同一 tick 的既有探针，因此不会抖动。</p>
     */
    private boolean blockedByMachineQueue(final BusCategoryInfo info) {
        final String reserved = machineReservedRecipe();
        if (reserved == null || reserved.isEmpty()) {
            return false; // 没有争用（或判不出来）：行为与改造前逐字一致
        }
        final Set<String> mine = categoryRecipeIds(info);
        if (mine.isEmpty() || mine.contains(reserved)) {
            return false; // 归属判不出来 / 正是赢家：不拦
        }
        if (info.isIntermediate()) {
            return true; // 规则 ②：不让落选配方的件上机器（它们留在缓存仓里等着）
        }
        final Level level = getLevel();
        if (level != null && loserOnMyStations(level, mine)) {
            return false; // 规则 ③ 的逃生口：机器上正压着它的件 → 放行投入物，先做完再让位
        }
        return true; // 规则 ③：其余类别也不许再往这台机器里灌
    }

    /**
     * <b>只读</b>：这些（落选）配方里，有任意一条的一件在制件<b>正压在本仓的某个工位 / 机器上</b>吗。
     * <p>用途见 {@link #blockedByMachineQueue} 规则 ③ 的逃生口：机器只有一个加工位，落选件还压在
     * 机器上时必须允许它的投入物继续流过去（否则它永远做不完，赢家也永远上不了机器 = 死锁）。</p>
     * <p>两路判据都是既有的只读探针，不新增第二套：① 登记表 + 工位占用（{@link #registeredUnitsForRecipe}）；
     * ② 机器上「正在加工的那一件」自己的进度组件（{@link #pendingStepOn}，与推料侧的按步判定同源）——
     * 第 ② 路覆盖「登记表里没有它」的情形（例如玩家手工把一件放到机器上）；
     * 第 ② 路的工位表在<b>面输出（FACE）模式</b>下恒为空表，此时仍由第 ① 路覆盖本模组自己推上去的件
     * （登记表就是推料侧写的），因此不会退化成「一律放行」。</p>
     */
    private boolean loserOnMyStations(final Level level, final Set<String> recipes) {
        for (final String recipeId : recipes) {
            if (recipeId == null || recipeId.isEmpty()) {
                continue;
            }
            if (registeredUnitsForRecipe(level, recipeId, inFlightMarkerItems(recipeId)) > 0L) {
                return true;
            }
        }
        for (final BlockPos target : busSupplyTargets()) {
            final PendingStep pending = pendingStepOn(target);
            if (pending != null && recipes.contains(pending.recipeId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：这条配方的订单<b>下单时刻</b>（= RS 任务的 {@link TaskStatus.TaskInfo#startTime()}，毫秒）。
     *
     * <p>取「以该配方产物 / 过渡件为目标、且未被挂起」的全部任务里<b>最早</b>的那一个
     * （同一条配方被下单两次时，先下的那一笔决定先后）。判据与
     * {@link #remainingOrderUnitsFor(String)} 同一份数据（{@link #recipeGoalItems(String)} +
     * {@link #taskTouches}），因此「有单」与「先后」绝不会各说各话。</p>
     * <p>判不出来返回 {@code -1}（调用方把它排到最后：读不到先后就不让它独占机器）。</p>
     */
    private long orderStartTimeFor(final String recipeId) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || recipeId == null || recipeId.isEmpty()) {
            return -1L;
        }
        final long now = level.getGameTime();
        if (now != orderStartProbeTick) {
            orderStartProbeTick = now;
            orderStartProbeCache.clear();
        }
        final Long cached = orderStartProbeCache.get(recipeId);
        if (cached != null) {
            return cached;
        }
        final long result = computeOrderStartTimeFor(recipeId);
        orderStartProbeCache.put(recipeId, result);
        return result;
    }

    /** {@link #orderStartTimeFor(String)} 的唯一实现（每 tick 每配方至多一次）。 */
    private long computeOrderStartTimeFor(final String recipeId) {
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return -1L;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return -1L;
        }
        final Set<Item> targets = recipeGoalItems(recipeId);
        if (targets.isEmpty()) {
            return -1L;
        }
        long best = -1L;
        for (final TaskStatus status : autocrafting.getStatuses()) {
            if (AssemblyWatchdog.isSuspended(status.info().id().id())) {
                continue; // 挂起 ≠ 在做（与 remainingOrderUnitsFor 同一口径）
            }
            if (!taskTouches(status, targets)) {
                continue;
            }
            final long start = status.info().startTime();
            if (start > 0L && (best < 0L || start < best)) {
                best = start;
            }
        }
        return best;
    }

    /**
     * 排队闸门下的追踪日志（<b>只在状态翻转时各打一条</b>，与 {@link #traceShareSkip} 同一理由：
     * 「这一轮没轮到我」是设计内的稳态，绝不能变成刷屏 —— 用户点名要求修掉「持续性的无效重复」）。
     * 只改日志通道，<b>不改任何判定</b>。
     */
    private void traceMachineQueueSkip(final BusCategoryInfo info, final BlockPos exporterPos) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final String reserved = machineReservedRecipe();
        final Item first = info.items().isEmpty() ? null : info.items().get(0);
        RsccAssemblyDebug.transition("queue@" + RsccAssemblyDebug.at(exporterPos) + "#" + info.id(),
            "machine_reserved_by=" + reserved,
            RsccAssemblyDebug.traceLine(RsccAssemblyDebug.itemId(first), -1L,
                RsccAssemblyDebug.machine("chamber", worldPosition), "bus_queue_skip",
                RsccAssemblyDebug.machine("exporter", exporterPos),
                "machine_reserved_by=" + reserved + " category_recipe=" + categoryRecipeIds(info),
                networkItemAmount(first)));
    }

    /**
     * 只读：本网络里<b>与本仓产线相关</b>的那条自动合成任务的<b>订单剩余件数</b>
     * （份额 {@link #exportShare} 的唯一数据源）。
     *
     * <p><b>怎么算</b>：① 取任务的目标产物总数 {@link TaskStatus.TaskInfo#amount()}；
     * ② 减去已经交付的部分 —— 交付量取该任务的 {@link TaskStatus.Item} 里<b>目标资源那一项</b>的
     * {@code stored + crafting}（RS 把「外部产线送回来的成品」记在任务的内部暂存里，见
     * {@code ExternalTaskPattern#trySatisfy}），而不是用 {@code percentageCompleted}
     * —— 后者对外部样板（本模组的序列装配就是 EXTERNAL 样板）恒为 0，拿它当剩余量会导致份额永远不回收。</p>
     * <p>多个相关任务时取<b>最大值</b>（保守：并行度取最宽松的那条，不会把正在进行的产线掐掉）。</p>
     * <p>取不到（无网络 / 无自动合成组件 / 没有相关任务）时返回 {@code -1}（= 判不出来），
     * 调用方按「全部台数」处理。</p>
     */
    private long remainingOrderUnits() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return -1L;
        }
        // 每 tick 只真正算一次（份额会在同一 tick 内被多个类别 / 多条总线查询，
        // 而 RS 的 getStatuses() 每次都会新建一个列表 → 不缓存就是每 tick 上千次分配）。
        final long now = level.getGameTime();
        if (now == remainingOrderProbeTick) {
            return remainingOrderProbeValue;
        }
        remainingOrderProbeTick = now;
        remainingOrderProbeValue = computeRemainingOrderUnits();
        return remainingOrderProbeValue;
    }

    /** {@link #remainingOrderUnits()} 的实际计算（每 tick 至多一次，见上面的缓存）。 */
    private long computeRemainingOrderUnits() {
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return -1L;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return -1L;
        }
        final List<TaskStatus> statuses = autocrafting.getStatuses();
        if (statuses.isEmpty()) {
            return -1L; // 没有任务：门控此时也已关闭，份额无意义（返回「判不出来」最保守）
        }
        final Set<Item> related = pipelineItemsCache;
        // <b>2026-10-05 修掉「多余的中间产物 / 一份料投两台机器」的根因</b>（子代理闭环定位）。
        //
        // 旧判据用的是 {@link #pipelineItemsCache}，而那个集合<b>包含投入物与起步原料</b>
        // （金板、齿轮…）。于是后台一个「维持 325 个金板」的<b>定量保持器</b>任务也满足
        // {@link #isRelatedResource} ⇒ 它的剩余量（325 甚至 65）被 {@code best} 取最大值 ⇒
        // {@link #allowedConcurrentUnits()} 随之被抬高 ⇒ 同时放开
        // {@link #exportShare} / {@link #wantingTargetCount} / {@code clampStartIngredientTargets}
        // ⇒ <b>两条输出总线各推同一份起步原料、各开一件在制件</b> ⇒
        // 多出来的那一件就是用户看到的「多余的中间产物」。
        //
        // 正确口径 = {@link #conservativeGoalItems()}：本仓产线的<b>产物 / 在制件</b>，
        // **已剔除投入物与起步原料**（与 {@code hasGoalTask()} 同一份语义：补给任务不是订单）。
        // 判不出来（空集）⇒ 退回旧集合（保守：不因为判不出来就改变份额）。
        final Set<Item> goalOnly = conservativeGoalItems();
        final Set<Item> orderScope = goalOnly.isEmpty() ? related : goalOnly;
        final Set<Fluid> relatedFluids = inputCategoryFluids();
        long best = -1L;
        for (final TaskStatus status : statuses) {
            if (orderScope != null
                && !isRelatedResource(status.info().resource(), orderScope, relatedFluids)) {
                continue; // 与「本仓订单」无关的任务（含投入物补给 / 别的机器在跑）不参与份额推导
            }
            // <b>2026-10-06 根因修复</b>：已交付量改用 RS 权威读数
            // {@link AssemblyWatchdog#deliveredAmount}（= root EXTERNAL 样板的
            // {@code iterationsReceived} 镜像；旧口径的 {@code stored + crafting} 对本模组样板<b>恒为 0</b>，
            // 证据链见那个方法的 javadoc）。对 INTERNAL 样板它内部仍会退回 stored + crafting，兼容不变。
            final long ordered = Math.max(1L, status.info().amount());
            final long delivered = AssemblyWatchdog.deliveredAmount(status);
            // <b>2026-10-06 修正（剩余量下限 1 → 0）</b>：本方法的语义是「订单<u>还差几件</u>」，
            // 「已交付满」必须能被表示成 0 —— 旧写法 {@code Math.max(1L, ordered - delivered)}
            // 把剩余量下限钉死在 1，于是「已满足」这一状态<b>永远表示不出来</b>。
            // {@code -1} 仍然是「判不出来」，与 0 语义不同（调用方各走各的分支，见
            // {@link #allowedConcurrentUnits()} 与 {@link #startCapacityForRecipe(String)}）。
            best = Math.max(best, Math.max(0L, ordered - delivered));
        }
        return best;
    }

    /**
     * <b>只读</b>：本仓此刻「真的有几件在制 / 待补」—— 份额判不出来时的<b>确定性</b>数据源。
     *
     * <h2>为什么要它（用户第 ①④⑤ 条：下单 1 件 ≠ 64 件 / 数量快速增减）</h2>
     * <p>份额（{@link #exportShare} 与备料侧的 {@link #wantingTargetCount}）的唯一理想数据源是
     * 「订单剩余件数」；但下单 1 件时任务太短命，常常在同一 tick 里读不到（{@link #remainingOrderUnits()}
     * 返回 {@code -1}）。旧实现在这种情况下<b>整个跳过份额闸门</b>（导出侧退回「全部台数」），
     * 于是两条输出总线同时把同一份起步原料推给两台机器、各开一件在制件 —— 用户实测
     * 「下单 1 个坚固板消耗了两个黑曜石粉」，而「下单 64 个反而正常」（长任务读得到剩余件数）。</p>
     * <p>本方法给出一条与订单状态<b>无关</b>的确定性下界：本仓的供料工位里此刻有几个正压在制件
     * （{@link #unitInFlightAt}），再加上仓内还没推出去的那一份（{@link #hasInFlightUnit}）。</p>
     * <p><b>取小不取大</b>：下界只会低估不会高估，因此拿它夹份额绝不会多耗一份原料 ——
     * 这正是用户要的「不确定时宁可少、绝不多」。</p>
     * <p>每 tick 至多真算一次（见 {@link #inFlightProbeTick}）。只读，绝不搬运 / 销毁任何资源。</p>
     */
    private long inFlightUnitCount() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return 0L;
        }
        final long now = level.getGameTime();
        if (now == inFlightProbeTick) {
            return inFlightProbeValue;
        }
        inFlightProbeTick = now;
        inFlightProbeValue = computeInFlightUnitCount(level);
        return inFlightProbeValue;
    }

    /** {@link #inFlightUnitCount()} 的实际计算（只读；判不出来时取确定性的保守值）。 */
    private long computeInFlightUnitCount(final Level level) {
        long count = 0L;
        final Map<BlockPos, BlockPos> stations = supplyStations(level);
        for (final BlockPos target : stations.values()) {
            if (target != null && unitInFlightAt(target)) {
                count++;
            }
        }
        // 仓内还压着一件本仓产线的在制件（还没轮到推给机器）同样占用一个「在制名额」。
        // 只在一个都没有时补这一份，避免把同一件数两次（工位那一份已经算过 id 的场合）。
        if (count == 0L && hasInFlightUnit()) {
            count = 1L;
        }
        // <b>2026-10-06 修「列车轨道下单 1 出 2」（多开件）。</b>
        //
        // 上面那条探针要求工位上的件<b>带 {@code SEQUENCED_ASSEMBLY} 组件</b>才认（见 {@link #holdsUnitAt}）。
        // 而<b>机械手（deployer）手里的件读不到组件</b> —— 件握在手上、不在容器格子里，
        // Create 的 {@code DeployerItemHandler} 只在「正在部署」那一刻才把它暴露出来。
        // 于是 {@code count} 恒为 0：
        // <pre>
        //   allowedConcurrentUnits() = 订单剩余 = 1
        //   cap = allowed − inFlightUnitCount() = 1 − 0 = 1     ← 以为一件都没开
        //   ⇒ {@link #clampStartIngredientTargets} 放行再投一份起步原料（石头台阶）
        //   ⇒ 开第二件在制 ⇒ 下单 1 条轨道却产出 2 条（用户实测）
        // </pre>
        // 坚固板当年犯过同一类错（下单 1 消耗 2 份粉），那次漏在起步原料的夹量上；
        // 轨道这次漏在<b>在制件计数</b>上。
        //
        // 修法：用<b>在制件登记表</b>补一份计数。登记表由<b>每一次成功的推料</b>写入
        // （见 {@code RsccChamberExportStrategy}），因此与「能不能从方块上读出组件」无关；
        // 判据只要求「登记的工位上<b>此刻还压着东西</b>」（不要求是登记的那一件 ——
        // 起步原料被消耗、变成过渡件之后，工位依然被占，那正是「这一件还在做」）。
        // 取 {@code max}：两条探针互为补充，绝不把同一件数两次，也绝不因探针失灵而少算。
        final long registeredBusy = registeredBusyStations(level);
        return Math.min(Math.max(count, registeredBusy), BUS_ITEM_TARGET_MAX);
    }

    /**
     * <b>只读</b>：在制件登记表里「工位上此刻还压着东西」的工位数。
     *
     * <p>与 {@link #unitInFlightAt} 的区别：<b>完全不看数据组件</b>，只看那一格是不是空的。
     * 用于计量「本仓已经开了几件在制」—— 机械手手里的件读不出组件，靠组件判定会把它漏掉
     * （详细机制见 {@link #computeInFlightUnitCount} 里的说明）。</p>
     */
    private long registeredBusyStations(final Level level) {
        if (level == null || inFlightUnits.isEmpty()) {
            return 0L;
        }
        // <b>2026-10-06 补第二处盲点：机械手「手里」的那一件。</b>
        //
        // 上一版只查「登记的工位那一格容器」。而 <b>Create 的机械手把件抓进手里时，
        // 它朝向的那个置物台是空的</b> ⇒ 查出「工位空着」⇒ 计数仍为 0 ⇒ 仍然多开一件
        // （用户实测：下单 1 条轨道，「冲压的时候又来了一个」）。
        //
        // 因此每个登记工位要连同<b>所有指向它的机器</b>一起查（{@link #supplyStations} 的
        // 值→键反查），机械手的手、注液机的工作面都算 —— 它们在任意一个位置压着东西，
        // 这个工位就处于「正在加工」。
        final Map<BlockPos, BlockPos> stations = supplyStations(level);
        long busy = 0L;
        for (final BlockPos station : new ArrayList<>(inFlightUnits.keySet())) {
            if (stationIsOccupied(level, station)) {
                busy++;
                continue;
            }
            for (final Map.Entry<BlockPos, BlockPos> entry : stations.entrySet()) {
                if (station.equals(entry.getValue()) && stationIsOccupied(level, entry.getKey())) {
                    busy++;
                    break;
                }
            }
        }
        return busy;
    }

    /** <b>只读</b>：这一格容器里是不是压着东西（{@link #registeredBusyStations} 的逐格实现）。 */
    private static boolean stationIsOccupied(final Level level, final BlockPos pos) {
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                .itemHandlerAt(level, pos);
        if (handler == null) {
            return false; // 判不了（不是容器 / 区块未加载）→ 不计
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：本仓此刻允许「同时在制」的件数 —— 份额的唯一收敛值（{@link #exportShare} 与
     * 备料侧共用）。
     *
     * <p>订单剩余件数可读（{@code > 0}）时就用它；读不到时退回 {@link #inFlightUnitCount()} 给出的
     * 确定性下界，<b>下限恒为 1</b>（至少允许一件开工，绝不因为读不到订单而彻底断供）。</p>
     *
     * <p><b>不变量</b>：对同一份世界状态，本方法是纯函数（只读 + 每 tick 缓存），因此
     * 「哪几台机器 / 哪几条总线参与」在同一 tick 内对所有查询方一致，不会抖动。</p>
     */
    private long allowedConcurrentUnits() {
        final long remaining = remainingOrderUnits();
        if (remaining > 0L) {
            return remaining;
        }
        return Math.max(1L, inFlightUnitCount());
    }

    // ==================== 在制名额感知「结果池概率」（2026-10-06：区分必得 / 概率配方） ====================

    /**
     * <b>只读</b>：这条序列装配配方的<b>主产物是不是必得</b>（100%）。
     *
     * <h2>判据（完全照抄 Create 自己的轮盘口径，不另写一套）</h2>
     * <p>Create 的 {@code SequencedAssemblyRecipe#rollResult} 是一台<b>权重轮盘</b>：
     * {@code totalWeight = Σ resultPool[].chance}，然后
     * {@code number = random * totalWeight} 依次减掉每一项的权重，第一个把 {@code number} 减到负数的项就是产物
     * （见 {@code local_src/create_src/com/simibubi/create/content/processing/sequenced/SequencedAssemblyRecipe.java:132-144}）。
     * 因此「主产物必得」= <b>轮盘只能落在主产物上</b>。这里复用本模组既有的同一份换算
     * {@link SequencedRecipeProbe#splitResultPool}（= Create JEI 的 {@code getOutputChance()} 口径，
     * 见交接文档 §11.1），并要求同时成立：</p>
     * <ol>
     *     <li>结果池里<b>只有一个非空项</b>（{@code scraps} 为空）—— 池里只要还有别的非空项，
     *     轮盘就有机会抽到它（哪怕它的 {@code chance} 写得很小，例如 120 : 8）；</li>
     *     <li>该项的归一概率（= 该项权重 / 全部非空项权重之和）<b>≥ 1</b>。权重全为 0 时该概率是 0
     *     （Create 在这种池上返回空栈）⇒ 不算必得，落到保守的一侧。</li>
     * </ol>
     *
     * <p><b>判不出来一律按「非必得」</b>（配方查不到 / 不是序列装配配方 / 结果池解析异常）：
     * 非必得 ⇔ 走「动态多开」那一侧，因此<b>宁可多开也不会因为判不出来而把概率产线卡死</b>。
     * 代价不对称：把必得的误判成非必得只是多开几件；把非必得的误判成必得，会让一条永远凑不够订单的
     * 产线被死死按在「只能开 R 件」上（R 次轮盘全落空 ⇒ 一件成品都没有）。</p>
     *
     * <p>实测对照（本轮已核实）：{@code create:sturdy_sheet} / {@code create:track} 的 {@code results}
     * 只有一项、且<b>没有</b> {@code chance} 字段 ⇒ 必得；{@code create:precision_mechanism} 的池是
     * 120 : 8 : 8 : 5 : 3 : 2 : 2 : 1 : 1 ⇒ 主产物归一概率 120/150 = 0.8 ⇒ 非必得。
     * 只读，绝不搬运 / 销毁任何资源。</p>
     *
     * @param recipeId 序列装配配方的注册 id（如 {@code create:sequenced_assembly/track}）
     */
    private boolean guaranteedResult(final String recipeId) {
        final SequencedRecipeProbe.Split split = resultPoolSplit(recipeId);
        if (split == null || split.results().size() != 1 || !split.scraps().isEmpty()) {
            return false; // 判不出来 / 池里还有别的非空项 ⇒ 保守按「非必得」
        }
        return split.results().get(0).chance() >= 1.0F;
    }

    /**
     * <b>只读</b>：该配方主产物的<b>归一概率</b> p（Create 轮盘口径：主产物权重 / 全部非空项权重之和）；
     * 判不出来（配方缺失 / 结果池里没有非空产物 / 解析异常）返回 {@code -1}。
     * <p>唯一的用途是 {@link #startCapacityForRecipe(String)} 里非必得配方的放大倍数。</p>
     */
    private float mainResultChance(final String recipeId) {
        final SequencedRecipeProbe.Split split = resultPoolSplit(recipeId);
        return split == null || split.results().isEmpty() ? -1F : split.results().get(0).chance();
    }

    /**
     * 结果池的拆分（与 JEI / 单元样板终端同一份换算，见 {@link SequencedRecipeProbe#splitResultPool}）；
     * 配方查不到、或结果池结构异常（附属模组自定义项）时返回 {@code null} = 「判不出来」。
     * <p>只读；调用方一律把 {@code null} 当「非必得」处理（见 {@link #guaranteedResult(String)}）。</p>
     */
    @org.jetbrains.annotations.Nullable
    private SequencedRecipeProbe.Split resultPoolSplit(final String recipeId) {
        final Level level = getLevel();
        if (level == null || recipeId == null || recipeId.isEmpty()) {
            return null;
        }
        final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
        if (recipe == null) {
            return null; // 配方查不到（数据包改过 / 未加载完）：当作判不出来
        }
        try {
            return SequencedRecipeProbe.splitResultPool(recipe.resultPool);
        } catch (final RuntimeException ignored) {
            return null; // 结果池结构异常：同样按判不出来处理，绝不抛
        }
    }

    /**
     * <b>只读</b>：起步原料 {@code item} 此刻<b>还能再开几件新在制件</b>（= 推料 / 备料两侧共用的「在制名额」），
     * <b>按「这份料属于哪条配方」分别算，取最小的那一个</b>。
     *
     * <h2>为什么必须按配方分（用户原话：「要区分百分之百概率和不是百分之百概率的序列装配」）</h2>
     * <p>同一台执行舱常常同时挂着两条配方的样板（现场：列车轨道与精密构件的第 0 步都是
     * {@code create:deploying}，只能共用一台机械手），而两条配方的订单量 / 产出概率完全不同。
     * 用一个「整仓名额」去夹两份<b>不同的</b>起步原料（石头台阶 / 金板）必然有一条被另一条带偏：
     * 轨道下单 1 条、精密构件下单 64 个时整仓名额是 64 ⇒ 石头台阶也被放行 ⇒ 又变成
     * 「下单 1 条轨道产出 2 条」。因此这里逐物品问它自己的配方。</p>
     *
     * <p><b>判不出来</b>（这份料属于哪条配方算不出：样板没记配方 id / 配方被数据包移除）时退回
     * <b>旧口径</b>（{@code allowedConcurrentUnits() − inFlightUnitCount()}）：判不出来绝不放宽，
     * 也绝不断料 —— 与 {@link #exportShare} 的「不确定时取小不取大」同一方向。</p>
     * <p>只读，绝不搬运 / 销毁任何资源。</p>
     */
    private long startCapacityFor(final Item item) {
        final Set<String> recipes = startIngredientRecipes(item);
        if (recipes.isEmpty()) {
            // 判不出「这份料属于哪条配方」：原样沿用旧口径（整仓名额）
            final long cap = Math.max(0L, allowedConcurrentUnits() - inFlightUnitCount());
            return cap;
        }
        long capacity = Long.MAX_VALUE;
        for (final String recipeId : recipes) {
            capacity = Math.min(capacity, startCapacityForRecipe(recipeId));
        }
        return Math.max(0L, capacity);
    }

    /**
     * <b>只读</b>：这条配方此刻<b>还能再开几件新在制件</b>（0 = 一件都不许再开）。
     *
     * <h2>① 必得（100%）配方：严格「有多少订单就发多少件」</h2>
     * <pre>
     *   名额 = 订单剩余量 R − 已在制件数 inFlight
     * </pre>
     * <p>因为 p = 1 ⇒ 每一件在制件都必出一件成品 ⇒ 「同时在制件数 ≤ 订单剩余量」既不会少做、
     * 也绝不多开。这里的 {@code inFlight} <b>必须跨执行舱</b>（见 {@link #inFlightUnitsForRecipe}）：
     * 件做完工位 A 的步骤、被推到工位 B 之后，<b>A 自己的登记工位已经空了</b> —— 只看本仓的话 A 会得出
     * 「我一件都没开」⇒ 名额恢复 ⇒ 再投一份起步原料 ⇒ 又开一件。这正是用户实测的
     * 「下单 1 条轨道产出 2 条」「冲压的时候又来了一个」。</p>
     *
     * <h2>② 非必得（p &lt; 100%）配方：按预期产出率放大（推导）</h2>
     * <p>设订单剩余 R 件、主产物单次概率 p（Create 轮盘：p = 主产物权重 / Σ 全部非空项权重，
     * 见 {@link SequencedRecipeProbe#splitResultPool}）。每跑完一件在制件 = <b>一次独立轮盘</b>，
     * 交付 R 件成品就是「第 R 次成功之前要投多少次」：</p>
     * <pre>
     *   X = 第 R 次成功所需要的轮盘次数（负二项分布），E[X] = R / p
     *   ⇒ 允许同时在制的件数 = ceil(R / p)
     *
     *   为什么取 ceil(R/p)：
     *     * 不取更小（例如既有口径 R）：并发数小于「期望所需次数」时，产能被并发上限卡住，
     *       订单要跑得比期望更久才能凑够 —— 与用户「非 100% 就要动态发」的要求相反；
     *     * 不取更大：再放大只是让更多件同时烧同一份起步原料，期望产出不变、废料更多。
     *   例（precision_mechanism，p = 120 / 150 = 0.8）：
     *     下单 1 个  ⇒ 允许 ceil(1 / 0.8)  = 2 件在制
     *     下单 64 个 ⇒ 允许 ceil(64 / 0.8) = 80 件在制（既有口径是 64 ⇒ 只会更宽，绝不更紧）
     * </pre>
     * <p><b>p 判不出来</b>（配方缺失 / 池里没有非空产物）时<b>不放大、直接用 R</b>：那正是<b>既有行为</b>
     * —— 「判不出来绝不收紧」是这里唯一安全的保守方向（少开只会慢一点，而既有的「未达标继续补料」
     * 仍会一件一件做到达标；多开则可能把起步原料成倍投进机器）。</p>
     *
     * <h2>③ 订单剩余量判不出来</h2>
     * <p>沿用既有口径：<b>一件在制都没有时允许开一件，已经有在制件时一件都不再开</b>
     * （{@code max(1, inFlight) − inFlight}，与 {@link #allowedConcurrentUnits} 的读不到分支同形）
     * —— 用户要求的「不确定时宁可少、绝不多」。</p>
     *
     * <p>只读；每 tick 每配方至多真算一次（{@link #startCapacityProbeCache}），因此对同一份世界状态
     * 是纯函数，备料 / 推料两侧在同一 tick 内读到同一个数。</p>
     */
    private long startCapacityForRecipe(final String recipeId) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            // 未进入世界 / 客户端：不做新判定，沿用旧口径（{@link #startCapacityFor} 的另一条回退路径）
            return Math.max(0L, allowedConcurrentUnits() - inFlightUnitCount());
        }
        if (recipeId == null || recipeId.isEmpty()) {
            return Math.max(0L, allowedConcurrentUnits() - inFlightUnitCount());
        }
        final long now = level.getGameTime();
        if (now != startCapacityProbeTick) {
            startCapacityProbeTick = now;
            startCapacityProbeCache.clear();
        }
        final Long cached = startCapacityProbeCache.get(recipeId);
        if (cached != null) {
            return cached;
        }
        // 真正算一次（下面全部只读，绝不搬运 / 销毁任何资源）
        final long remaining = remainingOrderUnitsFor(recipeId);
        final long inFlight = inFlightUnitsForRecipe(recipeId);
        long budgetForLog = -1L; // 只给诊断用：这条配方的「开件预算」（判不出来 = -1）
        final long capacity;
        // <b>2026-10-06 修正（`<= 0` → `< 0`，本轮「多开新件」的第二处成因）</b>：
        // {@code remaining} 现在有四种取值，语义各不相同，绝不能再混为一谈：
        //   * {@link #NO_ORDER_READABLE} = <b>读得到、但没有需求</b>（任务一条不剩 / 没有一条以本配方
        //     为目标）⇒ 一件都不许再开。名额<b>绝不允许比门控更宽</b>：此刻
        //     {@code hasGoalTask()} 已经是 false、{@code gate autocrafting=false reason=no_active_task}，
        //     放行只会得到「拉料 → 被 RS 拒（share_exhausted_order_remaining remaining=-1）→ 退回」的抖动
        //     （实测 3 次：11:54:43.108 / 11:56:36.209 / 11:57:34.009，见 {@link #NO_ORDER_READABLE}）；
        //   * {@code < 0} = 判不出来（没有网络 / 读不到任务表 / 配方解析不出来）
        //     ⇒ 走下面「不确定时取小不取大」的保守下限（至少允许一件，绝不因为读不出来就断供）；
        //   * {@code == 0} = 订单<b>已经交付满</b>（delivered ≥ ordered）
        //     ⇒ 一件都不许再开（旧写法在这里会走「判不出来」分支，空管线时又放 1 件 —— 实测
        //       每单都会多开一件，正是用户看到的「多出来的半成品」之一）；
        //   * {@code > 0} = 还差这么多件 ⇒ 按必得 / 非必得两种口径算名额。
        if (remaining == NO_ORDER_READABLE) {
            // 没有需求（≠ 判不出来）：名额与门控同宽（门控此时必然已关），一件都不开
            budgetForLog = 0L;
            capacity = 0L;
        } else if (remaining < 0L) {
            // 判不出来：不确定时取小不取大（与 allowedConcurrentUnits 的读不到分支同形）
            capacity = inFlight <= 0L ? 1L : 0L;
        } else {
            long allowed = remaining;
            if (!guaranteedResult(recipeId)) {
                final float p = mainResultChance(recipeId);
                if (p > 0F && p < 1F) {
                    // ② 非必得：按「第 R 次成功的期望次数 R/p」放大（推导见上方 javadoc）
                    allowed = (long) Math.ceil(remaining / (double) p);
                }
                // p ≥ 1 / p ≤ 0 / 判不出来（-1）⇒ 不放大，保持既有口径（绝不收紧）
            }
            budgetForLog = allowed;
            // ① 必得：allowed = R（严格），② 非必得：allowed = ceil(R/p)；两者都减去跨舱在制件数
            capacity = allowed - inFlight;
        }
        final long result = Math.max(0L, capacity);
        // ---- 只读诊断：名额的完整现场读数（只在状态翻转时各打一行，绝不刷屏；不参与任何判定）----
        // 为什么要有它（用户要求补上这块取证盲区）：本轮根因（delivered 恒为 0）之所以只能靠推理
        // 定位，就是因为日志里从来没有「remaining / delivered / inFlight / 名额」这一组现场数字。
        // 打出来之后，下次一眼可证「delivered 是不是又变成 0 了」以及名额是被谁压住的。
        if (RsccAssemblyDebug.isEnabled()) {
            final long[] parts = inFlightBreakdownForRecipe(recipeId);
            final long deliveredForLog = deliveredUnitsForRecipe(recipeId);
            RsccAssemblyDebug.transition("capacity@" + RsccAssemblyDebug.at(worldPosition) + "#" + recipeId,
                "R=" + remaining + ";D=" + deliveredForLog + ";I=" + inFlight + ";A=" + budgetForLog
                    + ";C=" + result,
                RsccAssemblyDebug.machine("chamber", worldPosition) + " capacity " + recipeId
                    + " remaining=" + remaining + " delivered=" + deliveredForLog
                    + " inflight=" + inFlight + "(reg=" + parts[0] + ",store=" + parts[1]
                    + ",pool=" + parts[2] + ",settled=" + parts[3] + ")"
                    + " allowed=" + budgetForLog + " cap=" + result);
        }
        startCapacityProbeCache.put(recipeId, result);
        return result;
    }

    /**
     * <b>只读</b>：全网络（本仓 + 别的执行舱）此刻属于这条配方的<b>在制件数</b>。
     *
     * <h2>为什么必须跨执行舱（用户实测：下单 1 条轨道产出 2 条）</h2>
     * <p>一条序列装配链常常<b>横跨多台执行舱</b>（装配仓做机械手步、冲压仓做冲压步）。件在工位 A 做完
     * 那一步、被推到工位 B 之后，<b>A 自己的登记工位就空了</b>：只看本仓的话 A 会认为「我一件都没开」
     * ⇒ 名额恢复 ⇒ 再投一份起步原料 ⇒ 又开一件。这就是「冲压的时候又来了一个」的机制，也是
     * 「必得配方必须严格等于订单剩余量」这条要求在物理上唯一能成立的前提。</p>
     *
     * <h2>口径（全部只读，绝不搬运 / 销毁）</h2>
     * <ol>
     *     <li><b>登记表</b>（{@link #inFlightUnits}，由每一次成功推料写入）：每条「配方 id 相符」的登记，
     *     只要它登记的工位（或<b>指向该工位的机械手「手里」</b>，即 Create 的 {@code DeployerItemHandler}
     *     抓走那一件时置物台是空的那种情形）此刻还压着东西，就算 1 件。按<b>物理位置</b>去重，
     *     因此同集群 / 多台仓指向同一台机器时绝不会把同一件数两次；
     *     <p><b>关键细节：登记的 recipe 字段可能是空的。</b>{@code RsccChamberExportStrategy}（第 254-264 行）
     *     在推的是<b>起步原料</b>（石头台阶 / 黑曜石粉：裸物品，没有进度组件）或<b>无组件的过渡件</b>时，
     *     只能记 {@code recipe = ""}。若只认 recipe 相符，那么「刚把起步原料推给机器」这一整个窗口都会被
     *     漏掉 ⇒ 名额恢复 ⇒ 立刻再投一份起步原料 ⇒ <b>多开一件</b>（正是本轮要修的 bug，而且比修之前更糟）。
     *     因此这里对「recipe 为空」的登记再按<b>推的是哪一件</b>归属（{@link #registeredUnitMatches}）：
     *     只有「该配方的起步原料候选 / 该配方的过渡件」才算它的在制件 —— 别的配方的料、步骤专用投入物、
     *     成品 / 废料都不会被算成在制件。</p></li>
     *     <li><b>仓内还压着、还没推出去的在制件</b>：每个<b>唯一</b>的内部存储（集群共用同一份，
     *     按对象身份去重）里「带该配方进度组件」的每一件都算 1 件。与 {@link #computeInFlightUnitCount}
     *     对「仓内那一件」的口径同向（那里只在工位探针全为 0 时补 1 件），这里按件数更精确：
     *     仓里的一件过渡件 = 一个已经开了工、迟早要走完序列的在制件，它已经占用了本产线的一份名额。</li>
     *     <li><b>2026-10-06 修正（需求二，本轮的正确性核心）：停在网络共享中间产物缓存池
     *     （缓存仓磁盘）里、属于本配方的过渡件也算 1 件。</b>
     *     上一轮为了绕开「名额恒为 0」，有意把它们排除在在制件之外 —— <b>方向是错的</b>：
     *     缓存池里那 96 件 {@code create:incomplete_track} <b>就是本订单已经开出去的产出物</b>，
     *     不计入 ⇒ 名额算成「0 件在制」⇒ 无限开新件 ⇒ 超额生产（快照 {@code 20261006-100050}：
     *     96 件轨道半成品 + 6 件坚固板半成品堆积，成品数远超订单量）。
     *     <p>归属<b>只按配方</b>（{@link #pooledTransitionalUnits(Level, String)}）：判据是件自己的
     *     {@code create:sequenced_assembly} 进度组件里的配方 id，并与 Create 配方自己的
     *     {@link SequencedAssemblyRecipe#getTransitionalItem()} 对齐 —— <b>绝不</b>用「名字里含
     *     incomplete_ / unprocessed_」这类启发式（坚固板的过渡件叫 {@code create:unprocessed_obsidian_sheet}，
     *     {@code create:unprocessed_obsidian_sheet}，
     *     而配方叫 {@code create:sequenced_assembly/sturdy_sheet}，名字根本对不上）。</p></li>
     *     <li><b>2026-10-06 新增（第 ④ 项：「还剩 1 件中间产物」的根因修复，改动仅此一处口径）。</b>
     *     本链机器 / 置物台 / 机械手手上、以及本仓内部存储里压着的<b>本配方「未交付成品件」</b>
     *     （{@link #settledResultItems(String)}：结果池物品，已剔掉过渡件与起步原料）也算 1 件。
     *     <p><b>为什么必须有它（日志原文取证）</b>：成品从「做完」到「被 RS 认领」之间有一段窗口 ——
     *     它先压在工位上，随后被本仓的堵塞自愈收回本仓（{@code unblock_recovered}），最后才写回网络；
     *     在这整段窗口里它<b>既不是过渡件</b>（不带进度组件 ⇒ ①②③ 都不认它）
     *     <b>也还没被认领</b>（{@code delivered} 还没加）⇒ {@code delivered + inflight} 比真实开件数少 1 ⇒
     *     名额凭空多出 1 ⇒ <b>多开一件</b>。实测（订单 10 条轨道）：</p>
     *     <pre>
     * 11:56:16.711 track inflight=10(reg=2,store=0,pool=8) delivered=0   ← 10 件全部开出去（峰值）
     * 11:56:42.006 chamber@(-16,-60,10) unblock_recovered {item=create:track x1} from=(-16,-60,12)
     * 11:56:42.009 chamber@(-5,-60,6) capacity .../track remaining=8 delivered=2 inflight=7(reg=0,store=1,pool=6) allowed=8 cap=1
     * 11:56:42.210 chamber@(-5,-60,6) take_to_chamber {minecraft:stone_slab x1}    ← 第 11 件（多出来的那一件）
     * 11:56:42.259 chamber@(-5,-60,6) capacity .../track remaining=7 delivered=3 inflight=7(reg=1,store=0,pool=6) allowed=7 cap=0
     *     </pre>
     *     <p>成品在那 3 ms 之前刚被收回本仓（就是 ④ 要数的东西），而 {@code delivered=2} 是它的
     *     <b>认领滞后</b> —— 见 {@link AssemblyWatchdog#deliveredAmount} 的说明。加上 ④ 之后同一时刻
     *     {@code inflight=8} ⇒ {@code allowed=8} ⇒ {@code cap=0}，那第 11 件不会开出去
     *     （订单计数器 {@code (-5,-60,6)|minecraft:stone_slab|pull 11} 与快照 {@code cachePool.storedTotal=1}
     *     的 {@code create:incomplete_track} 正是这件）。</p>
     *     <p><b>为什么不会卡死</b>：④ 数的位置就是本仓自己的作业位置（工位 + 本仓内部存储），
     *     成品一旦写回网络（既有回流：{@code return ... to=network}）就立刻不再计入，而 RS 在同 tick 内認領
     *     ⇒ 名额当 tick 就还回来。② 与 ④ 各自独立去重（同一份集群存储 / 同一物理位置只数一次），
     *     且 ④ 的作业集合与过渡件、起步原料互斥，因此绝不会把同一件数两次（多算只会让名额更紧，
     *     方向安全，但口径仍保持单一）。</p></li>
     * </ol>
     * <p><b>方向说明（宁可少开，不会卡死）</b>：本方法只会把「在制件数」算得<b>更全</b>
     * （跨舱 + 仓内 + 缓存池），因此名额只会更小（对必得配方正是「绝不多开」要的方向）。
     * 残留的整批过渡件不会永久压住名额：两条任务结束边沿都会把它们回流网络
     * （见 {@code flushResidualInputs} / {@code flushStationStartIngredients}）。</p>
     *
     * <h2>把缓存池里的半成品计入在制件，为什么<b>不会</b>卡死（本轮必须自洽的那条推理）</h2>
     * <ol>
     *     <li><b>名额为 0 只冻结「开新件」，不冻结「把已经开出去的件做完」。</b>
     *     名额只出现在两个地方：起步原料（{@link #unitCapacityLeftFor} → {@link #startCapacityForRecipe}）
     *     与备料侧的 {@link #clampStartIngredientTargets} —— 两者都<b>只作用于起步原料</b>
     *     （Create 的 {@code ingredient}，唯一能开出一件新在制件的投入物）。
     *     过渡件（中间产物）类别走的根本不是这条闸门（{@link #unitCapacityLeftFor} 的非起步原料分支）。</li>
     *     <li><b>池里的半成品是可复用的，不是死件</b>：备料侧按「本仓内部存储里有没有」
     *     判断要不要拉料（{@code RsccChamberItemStorage#countOf} 自 2026-10-06 起<b>只算内部存储</b>、
     *     不再把池子算进来），而缓存盘本身又是 RS 网络存储（{@code RsccCacheExposedStorage}），
     *     所以 {@link #fillInternalForBus} 会照常把池里那些过渡件<b>拉进本仓 → 输出总线推给机器</b>
     *     → 机器把它们做完 → 成品被输入总线收回网络交付给任务。
     *     于是「名额 = 0」这一 tick 里，<b>机器照样在动，只是不再开新件</b> —— 订单靠这些半成品走完，
     *     而不是需要新料。</li>
     *     <li><b>真正推不动的半成品不占名额</b>（反卡死的硬护栏）：只有「下一步归本网络某台在线
     *     执行舱」的过渡件才计入（见 {@link #pooledTransitionalUnits(Level, String)}）。因此一堆
     *     没有任何机器能做下一步的陈旧半成品<b>不会</b>把新订单的名额永久压成 0 ——
     *     这正是上一轮担心的那个「名额恒为 0、直接卡死」的场景，现在由「可推进才计入」从结构上排除。</li>
     * </ol>
     * <p><b>与上一轮那次卡死的关系（划清界线）</b>：{@code 20260806-084256} 那次「池里 35 件 ⇒ 备料闸门判
     * already_enough ⇒ 从不把料拉进本仓」的根因在<b>备料侧</b>的 {@code countOf}（当时把池子算成了
     * 「本仓手上的料」），该口径已改为只算内部存储；本方法是<b>另一个量</b>（在制件计数），
     * 两者不再互相干扰 —— 也就是说：本方法扣名额，备料侧照样把池里的件拉进来做完。</p>
     */
    private long inFlightUnitsForRecipe(final String recipeId) {
        final long[] parts = inFlightBreakdownForRecipe(recipeId);
        return Math.min(parts[0] + parts[1] + parts[2] + parts[3], BUS_ITEM_TARGET_MAX);
    }

    /**
     * <b>只读</b>：{@link #inFlightUnitsForRecipe(String)} 的<b>四个分量</b>，供名额诊断逐项打印
     * （{@code inflight=I(reg=..,store=..,pool=..,settled=..)}，见 {@link #startCapacityForRecipe(String)}）。
     *
     * <p>返回长度为 4 的数组（与 {@link #pooledTransitionalUnits(Level, String)} 同样的「避免新增小类型」口径）：</p>
     * <ul>
     *     <li>{@code [0]} = 登记表命中的工位 / 机械手手里那几件（跨仓去重）；</li>
     *     <li>{@code [1]} = 各仓内部存储里带本配方进度组件、还没推出去的件（同一份集群存储只数一次）；</li>
     *     <li>{@code [2]} = 网络共享中间产物缓存池里「任何在线执行舱都能继续推进」的件；</li>
     *     <li>{@code [3]} = <b>2026-10-06 新增（本轮「还剩 1 件」的根因修复）</b>：
     *     本链此刻压着的<b>未交付成品件</b>（结果池物品）—— 见 {@link #settledResultItems(String)}。</li>
     * </ul>
     * <p><b>与 {@link #inFlightUnitsForRecipe(String)} 严格同源</b>：那个方法现在只是把这四个分量相加并夹上限，
     * 因此「诊断看到的四个数」与「判定真正扣掉的名额」永远是同一份数据，绝不会有第二套口径。
     * <b>不做上限裁剪</b>（诊断要看到真实分量），相加后的裁剪由调用方负责。只读，绝不搬运 / 销毁任何资源。</p>
     */
    private long[] inFlightBreakdownForRecipe(final String recipeId) {
        final long[] parts = new long[4];
        final Level level = getLevel();
        if (level == null || level.isClientSide() || recipeId == null || recipeId.isEmpty()) {
            return parts;
        }
        // 该配方的「在制件标志物」= 起步原料候选 ∪ 过渡件（给 recipe 为空的登记做归属用）
        final Set<Item> markers = inFlightMarkerItems(recipeId);
        final List<SequenceExecutionChamberBlockEntity> chambers = chambersThisTick(level);
        final Set<Long> countedPositions = new HashSet<>(); // 已计入的物理位置（跨仓去重）
        final Set<cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage> countedStorages =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); // 集群共用同一份存储
        boolean sawSelf = false;
        // ④ 的作业集合：本配方结果池里的成品（已剔除过渡件与起步原料，因此与 ①②③ 绝不重叠）
        final Set<Item> settled = settledResultItems(recipeId);
        final Set<cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage> settledStorages =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); // ④ 的仓内去重（独立于 ②）
        for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
            if (chamber == null) {
                continue;
            }
            if (chamber == this) {
                sawSelf = true;
            }
            // ① 登记表：该仓推过去、此刻工位（或机械手手里）还压着东西的那几件
            final Map<BlockPos, BlockPos> stations = chamber.supplyStations(level);
            for (final Map.Entry<BlockPos, InFlightUnit> entry : new ArrayList<>(chamber.inFlightUnits.entrySet())) {
                final InFlightUnit unit = entry.getValue();
                if (!registeredUnitMatches(unit, recipeId, markers)) {
                    continue; // 别的配方的在制件 / 步骤投入物 / 成品：不占这条配方的名额
                }
                final BlockPos station = entry.getKey();
                if (stationIsOccupied(level, station)) {
                    if (countedPositions.add(station.asLong())) {
                        parts[0]++;
                    }
                    continue;
                }
                for (final Map.Entry<BlockPos, BlockPos> machine : stations.entrySet()) {
                    if (station.equals(machine.getValue()) && stationIsOccupied(level, machine.getKey())
                        && countedPositions.add(machine.getKey().asLong())) {
                        parts[0]++; // 机械手手里那一件（工位本身是空的，见 registeredBusyStations 的说明）
                        break;
                    }
                }
            }
            // ② 仓内压着、还没推出去的在制件（同一份集群存储只数一次）
            if (countedStorages.add(chamber.internalItemStorage())) {
                parts[1] += chamber.storedUnitsForRecipe(recipeId);
            }
            // ④ 本仓内部存储里「已经做完、还没写回网络」的本配方成品件（unblock 自救把堵塞的成品收回本仓那一段）
            // 只扫「内部存储本体」（不含网络共享缓存池）：池子里的东西按定义已经被 RS 认领，
            // 不该由本分量重复计量（[1] 用 itemStorage() 视图是既有口径，这里不跟着放大）。
            if (!settled.isEmpty() && settledStorages.add(chamber.internalItemStorage())) {
                parts[3] += countSettledIn(chamber.internalItemStorage(), settled, recipeId);
            }
        }
        if (!sawSelf) {
            // 理论上不会发生（本仓一定在自己的网络图里）：兜底只算自己，绝不因为拿不到网络而少算
            parts[1] += storedUnitsForRecipe(recipeId);
            parts[0] += registeredUnitsForRecipe(level, recipeId, markers);
            if (!settled.isEmpty() && settledStorages.add(internalItemStorage())) {
                parts[3] += countSettledIn(internalItemStorage(), settled, recipeId);
            }
        }
        // ③ <b>2026-10-06 修正（需求二）</b>：网络共享中间产物缓存池（缓存仓磁盘）里属于本配方的过渡件
        // 也算在制件 —— 它们就是本订单已经开出去的产出物（详见本方法 javadoc 的「为什么不会卡死」）。
        // 只取第 ① 项（全网络任何在线执行舱都能推进的件数）：推不动的陈旧半成品不占名额，绝不因此卡死。
        // 池子是网络级共享的，因此这里<b>只加一次</b>（不像仓内那样逐台累加）。
        parts[2] += pooledTransitionalUnits(level, recipeId)[0];
        // ④ 本链各台机器 / 置物台 / 机械手手上压着的「未交付成品件」（跨仓按物理位置去重，
        // 且与 ① 共用同一张已计数位置表：同一个位置绝不数两次）。
        if (!settled.isEmpty()) {
            for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
                if (chamber == null) {
                    continue;
                }
                for (final Map.Entry<BlockPos, BlockPos> station : chamber.supplyStations(level).entrySet()) {
                    parts[3] += countSettledAt(level, station.getKey(), settled, recipeId, countedPositions);
                    parts[3] += countSettledAt(level, station.getValue(), settled, recipeId, countedPositions);
                }
            }
            if (!sawSelf) {
                for (final Map.Entry<BlockPos, BlockPos> station : supplyStations(level).entrySet()) {
                    parts[3] += countSettledAt(level, station.getKey(), settled, recipeId, countedPositions);
                    parts[3] += countSettledAt(level, station.getValue(), settled, recipeId, countedPositions);
                }
            }
        }
        return parts;
    }

    /**
     * <b>只读</b>：这条配方结果池里的<b>成品物品</b>（= {@link #recipeGoalItems(String)} 里剔掉过渡件与
     * 起步原料候选之后剩下的那几件）。用于 {@link #inFlightBreakdownForRecipe(String)} 第 ④ 分量的归属判据。
     *
     * <p>为什么必须剔掉过渡件：过渡件由 ①②③ 三个既有分量按「件自己的进度组件」计数，成品则不带那个组件；
     * 两者互斥，因此 ④ 与它们绝不会把同一件数两次。起步原料同理（它是「还没开件」的料，不是成品）。</p>
     * <p>只读：配方解析不出来时返回空集 ⇒ ④ 恒为 0 ⇒ 退回既有口径（绝不因为解析不出来而改变名额）。</p>
     */
    private Set<Item> settledResultItems(final String recipeId) {
        final Set<Item> goals = recipeGoalItems(recipeId);
        if (goals.isEmpty()) {
            return Set.of();
        }
        final Set<Item> results = new LinkedHashSet<>(goals);
        final Level level = getLevel();
        final SequencedAssemblyRecipe recipe = level == null ? null : assemblyById(level, recipeId);
        if (recipe != null) {
            final ItemStack transitional = recipe.getTransitionalItem();
            if (!transitional.isEmpty()) {
                results.remove(transitional.getItem());
            }
        }
        results.removeAll(inFlightMarkerItems(recipeId));
        return results;
    }

    /** 只读：这份容器里「本配方成品件」的件数（无容器 / 未加载 ⇒ 0，绝不因为读不到而放宽）。 */
    private static long countSettledIn(@org.jetbrains.annotations.Nullable final net.neoforged.neoforge.items.IItemHandler handler,
                                       final Set<Item> settled, final String recipeId) {
        if (handler == null) {
            return 0L;
        }
        long total = 0L;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty() || !settled.contains(stack.getItem())) {
                continue;
            }
            // 带本配方进度组件的件是「过渡件」，由 ①②③ 负责 ⇒ 这里跳过，绝不数两次
            final SequencedAssembly assembly = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (assembly != null && recipeId.equals(assembly.id().toString())) {
                continue;
            }
            total += Math.max(1, stack.getCount());
        }
        return total;
    }

    /** 只读：该物理位置上压着的本配方成品件（位置已在别处计过 ⇒ 0；不重复计数）。 */
    private static long countSettledAt(final Level level, @org.jetbrains.annotations.Nullable final BlockPos pos,
                                       final Set<Item> settled, final String recipeId, final Set<Long> countedPositions) {
        if (pos == null || !countedPositions.add(pos.asLong())) {
            return 0L;
        }
        return countSettledIn(
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, pos),
            settled, recipeId);
    }

    /**
     * <b>只读</b>：网络共享中间产物缓存池（缓存仓磁盘）里属于这条配方的过渡件件数。
     *
     * <p>返回长度为 2 的数组（避免为此新增一个只在一处使用的类型）：</p>
     * <ul>
     *     <li>{@code [0]} = <b>本网络里任何一台在线执行舱都能继续推进</b>的件数（下一步归某台在线仓）
     *     —— 用于 {@link #inFlightUnitsForRecipe(String)} 的在制件口径；</li>
     *     <li>{@code [1]} = <b>只有本仓能推进</b>的件数（下一步归本仓）—— 用于
     *     {@link #machineHasWorkFor(Level, String)} 判断排队赢家是不是还有活干。</li>
     * </ul>
     *
     * <h2>归属只按配方（绝不用名字启发式）</h2>
     * <p>三道判据，缺一不可：① 池里那一项的<b>物品</b>必须等于 Create 配方自己的
     * {@link SequencedAssemblyRecipe#getTransitionalItem()}（坚固板的过渡件是
     * {@code create:unprocessed_obsidian_sheet}，而配方 id 是
     * {@code create:sequenced_assembly/sturdy_sheet} —— 任何「按名字猜」的口径在这里都会错）；
     * ② 那一件<b>自己的 {@code create:sequenced_assembly} 进度组件里的配方 id</b> 必须等于本配方
     * （这是唯一权威判据，也是「同一条链共用同一个过渡件物品」时唯一能分清归属的东西）；
     * ③ 它的「下一步」（{@code step % 序列长度}）必须归<b>某台在线执行舱</b>，否则不计入
     * （推不动的陈旧半成品不占名额 = 上一轮担心的「名额恒为 0 卡死」的结构性排除）。</p>
     *
     * <h2>已知边界（有意为之，写清楚而不是假装没有）</h2>
     * <ul>
     *     <li><b>不带 {@code create:sequenced_assembly} 组件的过渡件不计入。</b>
     *     Create 的 {@code SequencedRecipe#initFromSequencedAssembly} 把第 0 步的投入物放宽成
     *     「过渡件（裸物品）∪ 主原料」，因此裸过渡件确实可能合法存在（它等价于「第 0 步还没做」）。
     *     但裸件<b>没有配方 id</b>：两条配方共用同一个过渡件物品时无法区分归属，任何「按物品猜归属」
     *     的口径都会把别人的件算到本订单头上。因此这里<b>只在件自己带着配方 id 时计入</b> —— 这是保守方向：
     *     少算 ⇒ 名额更宽 ⇒ 只是「慢一点」，绝不会因此多开一件，也绝不会卡死；而这些裸件照样会被
     *     备料 / 推料侧按既有判据（无组件一律放行）拉去加工。</li>
     * </ul>
     *
     * <p>只读：{@link cretae.cookiewyq.rs_create_compat.support.RsccSharedCache#poolContents} 只做
     * 「读盘上有什么」，绝不写池、绝不抽任何一件；每 tick 每配方至多真算一次
     * （{@link #pooledProbeCache}）。</p>
     */
    private long[] pooledTransitionalUnits(final Level level, final String recipeId) {
        final long now = level.getGameTime();
        if (now != pooledProbeTick) {
            pooledProbeTick = now;
            pooledProbeCache.clear();
        }
        final long[] cached = pooledProbeCache.get(recipeId);
        if (cached != null) {
            return cached;
        }
        final long[] result = new long[2];
        final Network network = getNode().getNetworkOrNull();
        final SequencedAssemblyRecipe recipe = network == null ? null : assemblyById(level, recipeId);
        if (recipe != null) {
            final ItemStack transitionalStack = recipe.getTransitionalItem();
            final int size = recipe.getSequence().size();
            if (!transitionalStack.isEmpty() && size > 0) {
                final Item transitional = transitionalStack.getItem();
                for (final Map.Entry<ResourceKey, Long> entry
                    : cretae.cookiewyq.rs_create_compat.support.RsccSharedCache
                        .poolContents(level, network).entrySet()) {
                    final Long amount = entry.getValue();
                    if (amount == null || amount <= 0L
                        || !(entry.getKey() instanceof final ItemResource resource)
                        || resource.item() != transitional) {
                        continue;
                    }
                    final SequencedAssembly assembly =
                        resource.toItemStack(1).get(AllDataComponents.SEQUENCED_ASSEMBLY);
                    if (assembly == null || !recipeId.equals(assembly.id().toString())) {
                        continue; // 按配方归属：只认件自己的进度组件，绝不按物品名猜
                    }
                    final int nextStep = Math.floorMod(assembly.step(), size);
                    if (isOwnedStep(recipeId, nextStep)) {
                        result[1] += amount;
                    }
                    if (ownedStepAnyChamber(level, recipeId, nextStep)) {
                        result[0] += amount;
                    }
                }
            }
        }
        pooledProbeCache.put(recipeId, result);
        return result;
    }

    /** 只读：本网络里有没有<b>任意一台在线执行舱</b>认领了「这条配方 + 这一步」（{@link #isOwnedStep} 的跨舱版本）。 */
    private boolean ownedStepAnyChamber(final Level level, final String recipeId, final int step) {
        for (final SequenceExecutionChamberBlockEntity chamber : chambersThisTick(level)) {
            if (chamber != null && chamber.isOwnedStep(recipeId, step)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读：这条登记算不算「该配方的一个在制件」。
     *
     * <p><b>为什么不能只比 recipe 字段</b>：推的是起步原料 / 无组件的过渡件时登记里带不出配方 id
     * （{@code RsccChamberExportStrategy} 第 254-264 行只能记 {@code recipe = ""}）。只认 recipe 相符就会
     * 漏掉「刚把起步原料推给机器」的整个窗口 ⇒ 名额恢复 ⇒ 多开一件（比修之前更糟）。
     * 因此 recipe 为空时退回「推的是哪一件」：只有该配方的<b>起步原料候选</b>或<b>过渡件</b>才算它的在制件
     * —— 别的配方的料、步骤专用投入物（齿轮 / 铁粒）、成品 / 废料都不会被算成在制件。
     * 若这条登记<b>明确记着别的配方 id</b>，则一律不算（绝不把别人的件算到自己头上）。</p>
     *
     * <p>只读；两个 {@code Set} 都是调用方预先算好的（每 tick 每配方一次）。</p>
     */
    private static boolean registeredUnitMatches(@org.jetbrains.annotations.Nullable final InFlightUnit unit,
                                                 final String recipeId, final Set<Item> markers) {
        if (unit == null) {
            return false;
        }
        final String registered = unit.recipe();
        if (recipeId.equals(registered)) {
            return true; // 登记的件自己带着进度组件：配方 id 是权威判据
        }
        if (registered != null && !registered.isEmpty()) {
            return false; // 明确记着别的配方：绝不是本配方的在制件
        }
        return unit.item() != null && markers.contains(unit.item());
    }

    /** 只读：该配方的「在制件标志物」= 起步原料候选整组 ∪ 过渡件（{@link #registeredUnitMatches} 用）。 */
    private Set<Item> inFlightMarkerItems(final String recipeId) {
        final Set<Item> markers = new LinkedHashSet<>();
        final Level level = getLevel();
        if (level == null || recipeId == null || recipeId.isEmpty()) {
            return markers;
        }
        final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
        if (recipe == null) {
            return markers; // 配方查不到：标志物为空 ⇒ 只有「recipe 相符」的登记会被算（保守，不误算别人的件）
        }
        markers.addAll(mainIngredientCandidates(recipe));
        final ItemStack transitional = recipe.getTransitionalItem();
        if (!transitional.isEmpty()) {
            markers.add(transitional.getItem());
        }
        return markers;
    }

    /** 只读：本仓<b>登记表</b>里配方相符、且工位（或指向它的机器）还压着东西的件数（{@link #inFlightUnitsForRecipe} 的兜底路径）。 */
    private long registeredUnitsForRecipe(final Level level, final String recipeId, final Set<Item> markers) {
        long busy = 0L;
        final Map<BlockPos, BlockPos> stations = supplyStations(level);
        for (final Map.Entry<BlockPos, InFlightUnit> entry : new ArrayList<>(inFlightUnits.entrySet())) {
            if (!registeredUnitMatches(entry.getValue(), recipeId, markers)) {
                continue;
            }
            if (stationIsOccupied(level, entry.getKey())) {
                busy++;
                continue;
            }
            for (final Map.Entry<BlockPos, BlockPos> machine : stations.entrySet()) {
                if (entry.getKey().equals(machine.getValue()) && stationIsOccupied(level, machine.getKey())) {
                    busy++;
                    break;
                }
            }
        }
        return busy;
    }

    /** 只读：本仓内部存储里「带该配方进度组件」的在制件数（按件数计；只扫内部存储，不含共享缓存池）。 */
    private long storedUnitsForRecipe(final String recipeId) {
        long total = 0L;
        final net.neoforged.neoforge.items.IItemHandler store = itemStorage();
        for (int slot = 0; slot < store.getSlots(); slot++) {
            final ItemStack stack = store.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final SequencedAssembly assembly = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (assembly != null && recipeId.equals(assembly.id().toString())) {
                total += Math.max(1, stack.getCount());
            }
        }
        return total;
    }

    /** 只读：本网络全部执行舱（每 tick 复用一份快照；{@link #inFlightUnitsForRecipe} 用）。 */
    private List<SequenceExecutionChamberBlockEntity> chambersThisTick(final Level level) {
        final long now = level.getGameTime();
        if (now != chambersProbeTick) {
            chambersProbeTick = now;
            chambersProbeCache = chambersOf(getNode().getNetworkOrNull());
        }
        return chambersProbeCache;
    }

    /**
     * {@link #remainingOrderUnitsFor(String)} 的<b>第三种</b>返回值：任务表<b>读得到</b>、
     * 但本仓整条产线（{@link #hasGoalTask()}）确实<b>一条订单都没有</b> ⇒ 「<b>现在没有这份需求</b>」。
     *
     * <h2>为什么必须与 {@code -1}（判不出来）分开（2026-10-06 实测取证）</h2>
     * <p>旧实现把这两种完全不同的状态都写成 {@code -1}，于是名额函数把它们一起送进
     * 「不确定 ⇒ 保守放 1 件」那条分支。日志原文（{@code run/logs/latest.log}）：</p>
     * <pre>
     * 11:56:55.311 chamber@(-5,-60,6) capacity .../track remaining=-1 delivered=0 inflight=1(reg=0,store=1,pool=0) allowed=-1 cap=0
     * 11:57:32.510 binding task=8c6fda6c... product=列车轨道 x1      ← 新的 1 件订单
     * 11:57:33.961 chamber@(-16,-60,10) insert_network {create:track x1} net_after=11   ← 该订单已被满足
     * 11:57:34.009 chamber@(-5,-60,6) capacity .../track remaining=-1 delivered=0 inflight=0(reg=0,store=0,pool=0) allowed=-1 cap=1
     * 11:57:34.111 chamber@(-5,-60,6) take_to_chamber {minecraft:stone_slab x1}         ← 真的去拉料开了一件
     * 11:57:34.160 bus_share_skip ... reason=share_exhausted_order_remaining remaining=-1 ← RS 当场拒绝：没有订单
     * 11:57:34.411 chamber@(-5,-60,6) return {item x2} to=network reason=task_finished   ← 又原样退回
     * </pre>
     * <p>即：<b>任务一条不剩（门控 {@code gate autocrafting=false reason=no_active_task} 已经关闭）
     * 却仍然被放行 1 件</b>，拉料 → 被 RS 拒 → 退回，纯抖动。同一形状在本次会话里出现 3 次
     * （{@code 11:54:43.108}、{@code 11:56:36.209}、{@code 11:57:34.009}），
     * 坚固板那两次对应快照计数器 {@code (-10,-60,10)|create:powdered_obsidian|pull 22 / push 20}
     * 里多出来的 2 次拉料 + 2 次退回（{@code return: 2}）。</p>
     */
    private static final long NO_ORDER_READABLE = -2L;

    /**
     * <b>只读</b>：本网络里<b>以这条配方的产物 / 过渡件为目标</b>的自动合成任务的剩余件数
     * （口径与 {@link #computeRemainingOrderUnits} 完全同源，只把「范围」从整仓一条缩小到这一条配方）。
     *
     * <p><b>为什么不能直接用整仓的 {@link #remainingOrderUnits()}</b>：一个仓同时挂两条配方时
     * （列车轨道下单 1 + 精密构件下单 64），整仓剩余量是两者的<b>最大值</b> 64 —— 拿它当轨道的订单量，
     * 就会把石头台阶也放行 64 次，正是「下单 1 条轨道产出 2 条」的另一条路径。</p>
     *
     * <p><b>三种返回值（第三种是本轮 2026-10-06 新增，见 {@link #NO_ORDER_READABLE}）</b>：
     * {@code >= 0} = 还差这么多件；{@code -1} = <b>判不出来</b>（无网络 / 无自动合成组件 / 配方解析不出来）
     * —— 调用方走「不确定 ⇒ 保守放 1 件」，绝不因为读不到就断供；
     * {@link #NO_ORDER_READABLE} = <b>读得到、但没有需求</b> —— 调用方一件都不放行
     * （名额绝不允许比门控更宽：门控此时已经关闭，放行只会得到「拉料 → 被 RS 拒 → 退回」的抖动）。
     * 挂起的任务不算「在做」（与 {@link #orderedRecipes()} 同一口径）。</p>
     */
    private long remainingOrderUnitsFor(final String recipeId) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || recipeId == null || recipeId.isEmpty()) {
            return -1L;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return -1L;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return -1L;
        }
        final List<TaskStatus> statuses = autocrafting.getStatuses();
        if (statuses.isEmpty()) {
            // 任务表<b>读得到</b>、一条任务都没有 = 「现在没有需求」（≠ 判不出来）：
            // 此时门控必然也是关的（{@link #hasGoalTask()} 会返回 false，见 gateReason 的 no_active_task）。
            return NO_ORDER_READABLE;
        }
        final Set<Item> targets = recipeGoalItems(recipeId);
        if (targets.isEmpty()) {
            return -1L; // 配方都解析不出来：判不出来（调用方退保守分支）
        }
        long best = -1L;
        for (final TaskStatus status : statuses) {
            if (AssemblyWatchdog.isSuspended(status.info().id().id())) {
                continue; // 挂起 ≠ 在做（与 hasGoalTask / orderedRecipes 同一口径）
            }
            if (!taskTouches(status, targets)) {
                continue;
            }
            // <b>2026-10-06 根因修复（本轮的核心）</b>：已交付量改用 RS 权威读数
            // {@link AssemblyWatchdog#deliveredAmount}。旧口径是「目标资源那一项的 stored + crafting」，
            // 而 RS 只把外部产线送回的成品写进 {@code internalStorage} 供 {@code stored} 读取 ——
            // 本模组的样板是 EXTERNAL 型 <b>root</b> 样板，它的 {@code beforeInsert} 恒返回 0
            // （RS 源码 {@code ExternalTaskPattern.java:96-102}），认领只发生在 {@code afterInsert}
            // （同文件 104-110 → {@code trySatisfy} 112-125），<b>只减 expectedOutputs、不写 internalStorage</b>
            // ⇒ 旧口径的 delivered <b>恒为 0</b> ⇒ remaining 恒 = 订单量 ⇒ 每交付一件名额就回到 1
            // ⇒ 又开一件（实测：订单 10 + 10，开件 19 + 14，多出的半成品堆在缓存池）。
            // 新读数取 root EXTERNAL 样板的 {@code iterationsReceived}（唯一被正确维护的量，且随任务存档），
            // 对 INTERNAL 样板仍回退 stored + crafting（兼容不变）。
            final long ordered = Math.max(1L, status.info().amount());
            final long delivered = AssemblyWatchdog.deliveredAmount(status);
            // <b>2026-10-06 修正（剩余量下限 1 → 0）</b>：「订单剩余量」必须能表示「已交付满 = 0」，
            // 否则即使 delivered 修对，{@code Math.max(1L, …)} 也会让「已满足」分支永不生效
            // （空管线时仍会再放 1 件）。这里保留 {@code -1} = 判不出来，由
            // {@link #startCapacityForRecipe(String)} 分开处理：{@code < 0} 才走「不确定」的保守下限。
            best = Math.max(best, Math.max(0L, ordered - delivered));
        }
        if (best < 0L && !hasGoalTask()) {
            // 任务表读得到（上面已经确认过自动合成组件在读）、但本仓整条产线最后一条订单也已经消失
            // —— 与 {@link #hasGoalTask()} 的 no_active_task / no_relevant_task 同一口径。
            // <b>只有这一条路</b>才升级成「没有需求」：{@code hasGoalTask()} 在网络读不到 /
            // 产物集合解析不出来时同样返回 false，因此这里再断言一次「网络与自动合成组件读得到」，
            // 绝不把「判不出来」误判成「没需求」（那正是用户明确禁止的「判不出来就断供」）。
            final Network networkForNoOrder = getNode().getNetworkOrNull();
            if (networkForNoOrder != null
                && networkForNoOrder.getComponent(AutocraftingNetworkComponent.class) != null) {
                return NO_ORDER_READABLE;
            }
        }
        return best;
    }

    /**
     * <b>只读诊断专用</b>：这条配方此刻的「已交付件数」（任务筛选与
     * {@link #remainingOrderUnitsFor(String)} 完全同源：目标命中本配方 + 未挂起，多个任务取最大）。
     *
     * <p>只给 {@link #startCapacityForRecipe(String)} 的「状态翻转诊断行」用，因此只在
     * {@link RsccAssemblyDebug#isEnabled()} 为真时才会被调用（关闭调试时一次都不算）。
     * <b>任何判定都不读这个方法的返回值</b>：名额一律走
     * {@link #remainingOrderUnitsFor(String)}，本方法只是把同一个量单独摊出来给日志看
     * （用户要求补上「delivered 是不是又变成 0」的现场读数）。</p>
     */
    private long deliveredUnitsForRecipe(final String recipeId) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || recipeId == null || recipeId.isEmpty()) {
            return 0L;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return 0L;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return 0L;
        }
        final Set<Item> targets = recipeGoalItems(recipeId);
        if (targets.isEmpty()) {
            return 0L;
        }
        long best = 0L;
        for (final TaskStatus status : autocrafting.getStatuses()) {
            if (AssemblyWatchdog.isSuspended(status.info().id().id()) || !taskTouches(status, targets)) {
                continue; // 挂起 ≠ 在做（与 remainingOrderUnitsFor 同一口径）
            }
            best = Math.max(best, AssemblyWatchdog.deliveredAmount(status));
        }
        return best;
    }

    /**
     * 只读：这条配方「有人下单时会被点到的那些物品」= 过渡件 + 结果池里全部非空产物
     * （与 {@link #orderedRecipes()} 里按配方分的那份目标集合同一口径：投入物 / 起步原料绝不算「有单」）。
     */
    private Set<Item> recipeGoalItems(final String recipeId) {
        final Level level = getLevel();
        if (level == null || recipeId == null || recipeId.isEmpty()) {
            return Set.of();
        }
        final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
        if (recipe == null) {
            return Set.of();
        }
        final Set<Item> targets = new LinkedHashSet<>();
        final ItemStack transitional = recipe.getTransitionalItem();
        if (!transitional.isEmpty()) {
            targets.add(transitional.getItem());
        }
        for (final ProcessingOutput output : recipe.resultPool) {
            final ItemStack stack = output.getStack();
            if (!stack.isEmpty()) {
                targets.add(stack.getItem());
            }
        }
        return targets;
    }

    /**
     * 只读：这份「起步原料」属于本仓哪几条配方（{@link #startCapacityFor} 的唯一数据源）。
     * <p>与 {@link #startIngredients()} 同源（都从 {@link #ownedSteps()} 出发），因此候选整组都入表：
     * 标签型起步原料（{@code create:sleepers} 的三种台阶）的任一候选都能反查回它自己的配方。</p>
     */
    private Set<String> startIngredientRecipes(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return Set.of();
        }
        Map<Item, Set<String>> cached = startIngredientRecipesCache;
        if (cached == null) {
            final Level level = getLevel();
            cached = level == null ? Map.of() : computeStartIngredientRecipes(level);
            startIngredientRecipesCache = cached;
        }
        final Set<String> recipes = cached.get(item);
        return recipes == null ? Set.of() : recipes;
    }

    /** {@link #startIngredientRecipes(Item)} 的唯一实现（只读；与 {@code computeStartIngredients} 同一遍历）。 */
    private Map<Item, Set<String>> computeStartIngredientRecipes(final Level level) {
        final Map<Item, Set<String>> result = new LinkedHashMap<>();
        for (final String recipeId : ownedSteps().keySet()) {
            final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
            if (recipe == null) {
                continue; // 配方查不到（数据包改过 / 未加载完）：不参与本表（与 startIngredients 一致）
            }
            for (final Item item : mainIngredientCandidates(recipe)) {
                result.computeIfAbsent(item, key -> new LinkedHashSet<>()).add(recipeId);
            }
        }
        return result;
    }

    /**
     * <b>只读</b>：这一类别此刻还有没有「开一件新在制件」的名额（份额外总线让位前的闸门）。
     *
     * <p><b>起步原料类别</b>（唯一会开出新在制件的投入物）走<b>按配方</b>的名额
     * （{@link #startCapacityFor}）：必得配方严格「订单剩余 − 跨舱在制件」，非必得配方按
     * {@code ceil(R/p)} 放大 —— 详细推导见 {@link #startCapacityForRecipe}。</p>
     *
     * <p><b>起步原料以外的类别</b>（过渡件 / 步骤专用投入物 / 流体）不会开出新件，沿用旧口径
     * （{@code allowedConcurrentUnits() − inFlightUnitCount()}）—— 行为逐字不变。</p>
     */
    private boolean unitCapacityLeftFor(final BusCategoryInfo info) {
        if (isStartIngredientCategory(info)) {
            for (final Item item : info.items()) {
                if (item != null && startCapacityFor(item) > 0L) {
                    return true;
                }
            }
            return false;
        }
        final boolean unitCapacityLeft = allowedConcurrentUnits() - inFlightUnitCount() > 0L;
        return unitCapacityLeft;
    }

    /**
     * 份额不足（本机没分到份）时的追踪日志：说明「为什么不导出」，并给出当时的份额与网络存量。
     *
     * <p><b>本轮修正（用户要求「修到不再有持续性的无效重复」）</b>：旧实现走
     * {@link RsccAssemblyDebug#trace}（同因合并，每 5 秒一条并带 {@code (same cause repeated n times)}），
     * 而「本机没分到份」是<b>设计内的稳态</b>（下单 1 个 + 两条输出总线 ⇒ 份额恒为 1），
     * 于是日志里稳定刷出 {@code step_extra_share_exhausted ... repeated 79 times in 5s}
     * （齿轮）与 {@code share_exhausted_order_remaining ... repeated 98 times in 5s}（金板）——
     * 这正是用户看到的「堵齿轮」噪声：一条<b>不存在的堵塞</b>被反复广播。
     * 现在改走 {@link RsccAssemblyDebug#transition}（<b>只在状态翻转时各打一条</b>），
     * 于是稳态零输出、份额真正变化（轮次易主 / 订单剩余量变了）时仍能一眼看到。
     * 只改日志通道，<b>不改任何判定</b>。</p>
     */
    private void traceShareSkip(final BusCategoryInfo info, final BlockPos exporterPos,
                                final String reason) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final List<BlockPos> owners = busCategoryOwners.get(info.id());
        final int ownerCount = owners == null ? 0 : owners.size();
        final Item first = info.items().isEmpty() ? null : info.items().get(0);
        final String item = first == null ? "-" : RsccAssemblyDebug.itemId(first);
        final int share = exportShare(ownerCount);
        RsccAssemblyDebug.transition("share@" + RsccAssemblyDebug.at(exporterPos) + "#" + info.id(),
            reason + ";" + share + "/" + ownerCount,
            RsccAssemblyDebug.traceLine("item=" + item, -1L,
                RsccAssemblyDebug.machine("exporter", exporterPos), "bus_share_skip",
                "network",
                reason + " share=" + share + " owners=" + ownerCount
                    + " remaining=" + remainingOrderUnits(),
                networkItemAmount(first)));
    }

    /** 只读：网络里该物品（按物品种类汇总，不看数据组件）当前存量；取不到返回 {@code -1}。 */
    private long networkItemAmount(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null || getLevel() == null) {
            return -1L;
        }
        final Network network = getNode().getNetworkOrNull();
        final StorageNetworkComponent storage = network == null
            ? null : network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return -1L;
        }
        long total = 0L;
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            if (tracked.resourceAmount().resource() instanceof final ItemResource resource
                && resource.item() == item) {
                total += tracked.resourceAmount().amount();
            }
        }
        return total;
    }

    /**
     * 追踪（「移交下一台仓」这一段）：本仓对某份<b>网络里的件</b>做出的结论 —— 成功取进内部存储
     * （{@code event=take_to_chamber}）或被哪一条判据拦下（{@code event=pull_rejected / pull_hold}）。
     * <p>{@code net} 一律带真实存量，因此「网络里有货却没动」与「网络里确实没有」在日志里一眼可分。</p>
     */
    private void tracePull(final ItemResource resource, final long amount, final String event,
                           final String reason, final long net) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final String itemId = RsccAssemblyDebug.itemId(resource.item());
        RsccAssemblyDebug.trace("trace-pull@" + RsccAssemblyDebug.at(worldPosition) + "#" + itemId
                + "#" + event,
            RsccAssemblyDebug.traceLine("item=" + itemId, amount, "network", event,
                RsccAssemblyDebug.machine("chamber", worldPosition), reason, net));
    }

    /**
     * 「备料侧本 tick 什么都不做」的追踪（<b>状态翻转才打一条</b>）：抑制冷却中 / 仓内已够 / 抽不到。
     *
     * <p><b>为什么单开一条通道（用户要求「修到不再有持续性的无效重复」）</b>：这类结论是
     * <b>设计内的稳态</b>（仓内已够就是已够），却会被每个引擎节拍重新判定一次；走
     * {@link RsccAssemblyDebug#trace} 时日志里于是稳定刷出
     * {@code pull_hold ... reason=already_enough ... (same cause repeated 343 times in 5s)} ——
     * 实测齿轮一项就占了它全部日志的 1/3（用户点名「还是会堵齿轮」的观感来源之一）。
     * 改走 {@link RsccAssemblyDebug#transition} 后：<b>状态翻转时才各一条</b>，
     * 于是「从已够变成不够 / 从冷却转为放行」仍然看得见，稳态零输出。</p>
     *
     * @param holdReason 稳态标识（<b>必须是稳定字符串</b>，它参与去重：{@code already_enough} /
     *                   {@code storage_full} / {@code extract_zero} / {@code refusal_cooldown}）
     * @param detail     正文里的明细（可以带每秒都在变的数字，因为它只在翻转那一条出现）
     */
    private void tracePullHold(final ItemResource resource, final long amount, final String holdReason,
                               final String detail, final long net) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final String itemId = RsccAssemblyDebug.itemId(resource.item());
        RsccAssemblyDebug.transition(
            "trace-pull-hold@" + RsccAssemblyDebug.at(worldPosition) + "#" + itemId,
            holdReason,
            RsccAssemblyDebug.traceLine("item=" + itemId, amount, "network", "pull_hold",
                RsccAssemblyDebug.machine("chamber", worldPosition), detail, net));
    }

    /**
     * 「一次一份」节流的只读探针：<b>相连输出总线正在供料的那些机器里，此刻还压着哪些物品</b>
     * （= 上一份还没被消化掉的「输入时原料」）。
     *
     * <p><b>口径</b>：目标位置取自 {@link #busSupplyTargets()}（= 相连输出总线朝向的那一格，
     * 也就是机器 / 置物台本身），只读它们的物品容器；容器里的<b>任何</b>物品都算「机器还被占用」，
     * 因为「要送进去的同种料还在里面」正是我们要判的情形，而按物品种类比对放在调用侧
     * （{@link #anyHeld}）做，一个探针结果可以服务所有类别。</p>
     *
     * <p>每 tick 只真正读一遍（{@link #occupiedProbeTick}），机器一旦消化掉某物，
     * 它下一次探针就消失 —— 节流因此是自愈的。客户端 / 未接入网络一律返回空集（不参与任何搬运）。</p>
     */
    private Set<Item> occupiedInputMaterials() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return Set.of();
        }
        final long now = level.getGameTime();
        if (now == occupiedProbeTick) {
            return occupiedProbeItems;
        }
        final Set<Item> occupied = new HashSet<>(8);
        for (final BlockPos target : busSupplyTargets()) {
            occupied.addAll(occupiedItemsOf(level, target));
        }
        occupiedProbeTick = now;
        occupiedProbeItems = occupied;
        return occupied;
    }

    /**
     * 「一次一份」节流的只读探针（<b>按本总线目标机器</b>）：<b>这一台</b>输出总线面对的那台机器
     * 此刻还压着哪些物品。
     *
     * <h2>为什么必须按「这一台」而不是全仓并集</h2>
     * 一台执行舱常常同时给多台机器供料，而各机器所处的步骤并不相同（用户实测：一台仓对着两台机器，
     * 一台还在第 1 步、另一台已经第 2 步）。若用「全仓并集」判定「这类料机器还压着没消化」，
     * A 机器手里的料会把 B 机器正需要的<b>同一份料</b>也一起掐掉 —— 症状就是用户说的
     * 「不是每种输出各一份，而是总体只出一份」。按本总线自己的目标机器判定后，每台机器各自只留一份。
     *
     * <p><b>口径</b>：只读该总线朝向那一格的物品容器；容器里的<b>任何</b>物品都算「机器还被占用」，
     * 按物品种类比对放在调用侧（{@link #anyHeld}），一个探针结果可以服务所有类别。
     * 每 tick 每台机器只真正读一遍（{@link #occupiedAtProbeTick}），机器一消化就自愈；
     * 客户端 / 未接入网络一律返回空集（不参与任何搬运）。只读，绝不搬运 / 销毁任何资源。</p>
     */
    private Set<Item> occupiedInputMaterialsAt(final BlockPos exporterPos) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || exporterPos == null) {
            return Set.of();
        }
        final long now = level.getGameTime();
        if (now != occupiedAtProbeTick) {
            occupiedAtProbeTick = now;
            occupiedAtProbeCache.clear();
        }
        final Set<Item> cached = occupiedAtProbeCache.get(exporterPos);
        if (cached != null) {
            return cached;
        }
        final BlockPos target = supplyTargetOf(level, exporterPos);
        final Set<Item> occupied = target == null ? Set.of() : occupiedItemsOf(level, target);
        occupiedAtProbeCache.put(exporterPos, occupied);
        return occupied;
    }

    /**
     * <b>只读</b>：本总线朝向的机器此刻是不是已经压着「<b>够一批</b>」这类流体
     * （{@code ≥ perBatch} mB，例如岩浆 500 mB）—— {@link #occupiedInputMaterialsAt} 的<b>流体版</b>。
     *
     * <h2>为什么必须与物品侧对称</h2>
     * 物品侧早就有「机器里还压着同种投入物 ⇒ 这一轮不交给总线」（{@code anyHeld}
     * 对占用探针的投影），流体侧却一直漏着：于是总线每 tick 都对注液机试一次 {@code fill}，注液机罐里还有岩浆时必然
     * 得到 {@code DESTINATION_DOES_NOT_ACCEPT/machine_full} —— 实测 126 次加权、5 秒窗口内最多 10 条，
     * 正是用户点名「岩浆没有被正常消耗」时看到的刷屏。补上闸门后：机器一消耗掉，下一次清单重算
     * （≤ {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick）就自动放行，功能一字未减。
     *
     * <p>判据只读、只按「本总线自己的目标机器」判（与物品侧同口径，绝不用全仓并集，
     * 否则一台仓给多台机器供料时会把别的机器正需要的那一份也一起掐掉）。</p>
     */
    private boolean holdsInputFluidAt(final Level level, final BlockPos exporterPos,
                                      final List<Fluid> fluids, final long perBatch) {
        if (level == null || exporterPos == null || fluids.isEmpty() || perBatch <= 0L) {
            return false;
        }
        final BlockPos target = supplyTargetOf(level, exporterPos);
        return target != null && machineHeldFluidAt(level, target, fluids) >= perBatch;
    }

    /**
     * <b>只读</b>：本仓相连的全部供料目标上此刻压着多少 mB 的指定流体（不在列表里的流体不计）。
     *
     * <p>用于备料侧的「一次一份在飞」闸门（见 {@link #pullFluid}）：目标量按「机器侧已经压着多少」
     * 扣掉，于是「推进注液机 → 它还没消耗 → 又抽一份进仓」这种<b>抽了不用</b>不会发生。
     * 目标机器不是流体容器 / 未加载一律计 0（不表态即不拦，绝不因读不到而断供）。只读。</p>
     */
    private long machineHeldFluid(final Fluid fluid) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || fluid == null) {
            return 0L;
        }
        long held = 0L;
        final List<Fluid> one = List.of(fluid);
        for (final BlockPos target : busSupplyTargets()) {
            held += machineHeldFluidAt(level, target, one);
        }
        return held;
    }

    /** <b>只读</b>：某一格机器的流体容器上此刻压着多少 mB 的指定流体（口径与物品侧 {@link #holdsItemAt} 对称）。 */
    private static long machineHeldFluidAt(final Level level, final BlockPos target, final List<Fluid> fluids) {
        if (level == null || target == null || fluids.isEmpty()) {
            return 0L;
        }
        final net.neoforged.neoforge.fluids.capability.IFluidHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.fluidHandlerAt(level, target);
        if (handler == null) {
            return 0L;
        }
        long held = 0L;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            final net.neoforged.neoforge.fluids.FluidStack inTank = handler.getFluidInTank(tank);
            if (!inTank.isEmpty() && fluids.contains(inTank.getFluid())) {
                held += inTank.getAmount();
            }
        }
        return held;
    }

    /**
     * <b>只读</b>：此刻是否<b>至少有一条输出总线真的需要</b>这一类流体 —— 也就是「拉进来推得出去」。
     *
     * <h2>为什么流体侧必须有这道闸门（用户第 1 条：岩浆有时消耗有时不消耗）</h2>
     * <p>物品侧早有 {@code anyBusOwnsResource}：没有任何输出总线选中它这一步的类别时，本仓<b>不</b>把
     * 它从网络抽进来。流体侧此前<b>没有对应判据</b>，于是「没有总线选中岩浆类别 / 选中的总线其目标机器
     * 都已经压着够一批」时，本仓照样抽 500 mB 进自己的罐 —— 网络当刻被扣，而它根本没有消费者，
     * 只能在任务收尾时由 {@code flushResidualInputs} 原样还回网络。玩家看到的正是
     * 「扣一下又回来 / 有时扣有时不扣」这种抖动。补上对称闸门后，每一次流体离开网络都对应
     * 「确实有一条总线要把它送给一台还没喝够的机器」，账目因此可逐笔对上。</p>
     *
     * <p><b>判据</b>：遍历本仓可见类别里含该流体的那些，取其归属总线（{@link #busCategoryOwners}）；
     * 只要有一条总线的供料目标存在、且该目标机器里压着的同类流体<b>还不到一批</b>
     * （{@link #machineHeldFluidAt}），就算「需要」。判不出来（无世界 / 无归属）一律返回
     * {@code false} = 不抽 —— 因为「没有任何总线要它」时抽进来必然推不出去；
     * 而总线归属表每 {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 重建，玩家一勾上类别最迟 1 秒就恢复。
     * 只读，绝不搬运 / 销毁任何资源。</p>
     */
    private boolean anyBusNeedsFluid(final Fluid fluid) {
        if (fluid == null) {
            return true; // 判不了 → 放行（绝不因判不出来而断供）
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return true;
        }
        final List<Fluid> one = List.of(fluid);
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.fluids().contains(fluid)) {
                continue;
            }
            final List<BlockPos> owners = busCategoryOwners.get(info.id());
            if (owners == null || owners.isEmpty()) {
                continue; // 没有总线选中它：这一类流体本仓不备料
            }
            final long perBatch = Math.max(1L, info.amount());
            for (final BlockPos owner : owners) {
                final BlockPos target = supplyTargetOf(level, owner);
                if (target == null) {
                    continue; // 该总线没有可供给的目标容器（被拆 / 朝向不对）：它本来就送不出去
                }
                if (machineHeldFluidAt(level, target, one) >= perBatch) {
                    continue; // 这台机器已经压着够一批：这一刻不需要再抽
                }
                return true;
            }
        }
        return false;
    }

    /** 只读：某台输出总线朝向的那一格（= 它在供料的机器 / 置物台）；拿不到返回 {@code null}。 */
    @org.jetbrains.annotations.Nullable
    private static BlockPos supplyTargetOf(final Level level, final BlockPos exporterPos) {
        if (!level.isLoaded(exporterPos)) {
            return null;
        }
        // 只取「已存在」的方块实体（CHECK），绝不为查询强制初始化邻块
        if (level.getChunkAt(exporterPos).getBlockEntity(exporterPos, LevelChunk.EntityCreationType.CHECK)
            instanceof RsccExporterExecutorMode exporter) {
            return exporter.rscc$supplyTargetPos();
        }
        return null;
    }

    /**
     * 只读：一个供料目标所属的<b>物理工位</b>坐标（= 机械手实际作用的那一格；非机械手就是它自己）。
     *
     * <h2>为什么必须有「工位」这个概念（用户硬要求：针对一台机器只推一份）</h2>
     * 「机械手 + 它朝向 2 格外的置物台」是<b>同一件在制品的同一个工位</b>：
     * {@link #pendingStepOn(BlockPos)} 对两者的结论完全相同（机械手没有在制件时就去看它的操作对象，
     * 见 {@link #operandStepOf}）。若不合并，同一个工位会被计成「两台机器」——
     * 备料目标量因此翻倍（实测 {@code cogwheel target=3} 而实际只要 1）、
     * 「这一台是否已经握着这件料」也会被拆成两份互不知情的答案。
     * 合并后：<b>一个工位 = 一份</b>，多台机器各自独立，同一工位绝不多份。
     * <p>判据只按「谁是它的操作对象」这一件事，不做任何坐标特判；只读。</p>
     */
    private static BlockPos stationKey(final Level level, final BlockPos target) {
        final BlockPos operand = operatingPosOf(level, target);
        return operand == null ? target : operand;
    }

    /**
     * 只读：把本仓的供料目标按其<b>物理工位</b>归并（键 = {@link #stationKey}，值 = 该工位的代表目标）。
     *
     * <p>去重的直接效果（用户实测的两条抱怨都由此而来）：</p>
     * <ul>
     *     <li>备料目标量不再把「置物台 + 对着它的机械手」算成两份 ⇒ 单台机器只备/只推<b>一份</b>；</li>
     *     <li>「这台是不是已经握着这件料」不再被拆成两个各说各话的答案（见
     *     {@link #supplyTargetHoldsItem}）。</li>
     * </ul>
     * <p>每 tick 至多调用几次，规模 = 供料目标数（个位数），只读、不搬运。</p>
     */
    private Map<BlockPos, BlockPos> supplyStations(final Level level) {
        final Map<BlockPos, BlockPos> stations = new LinkedHashMap<>(4);
        for (final BlockPos target : busSupplyTargets()) {
            stations.putIfAbsent(stationKey(level, target), target);
        }
        return stations;
    }

    /**
     * 只读：这一格（机器本身 / 机械手的操作对象）此刻是否压着这件物品。
     * <p>「压着」= 容器里存在同一种物品（与 {@code RsccChamberExportStrategy#holdsSameItem} 同一口径，
     * 同物品即算占用，不看数据组件）；没有容器 / 未加载一律不表态（{@code false}）。只读。</p>
     */
    private static boolean holdsItemAt(final Level level, final BlockPos pos, final Item item) {
        if (level == null || pos == null || item == null) {
            return false;
        }
        final IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, pos);
        if (handler == null) {
            return false;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (handler.getStackInSlot(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：这台供料目标所属的<b>整个工位</b>（置物台 / 机器本身 + 所有对着它的机械手）此刻
     * 是否已经压着这件物品 —— 也就是「<b>这台机器手上还有未被消耗的投入物</b>」这条用户硬判据的唯一实现。
     *
     * <h2>为什么必须把「工位」整体看一遍（用户实测「下单 1 个金板却多输出一份」的根因）</h2>
     * 置物台只有 1 格、机械手只有 1 个手位：一旦这一件已经躺在台上（或攥在手里），
     * 再往里推必然被原样拒收。旧判据只看「一次 SIMULATE 插入成不成功」——
     * 于是「已经握着料」被误读成「<b>收不下 ⇒ 这台干不了活</b>」，份额外的第二条总线便顶了上来，
     * 把同一份起步原料推给<b>第二台机器</b>、接着又原样被收回（实测日志：
     * {@code share_exhausted_order_remaining share=1} 之后紧跟
     * {@code exporter@(-6,-60,6) bus_push {item=create:golden_sheet} to=machine@(-7,-60,6)}）。
     * 这条判据把「握着料 = 正在干活」写死，因此「一台机器同一时刻最多一份未消耗投入物」成为不变式。
     * <p>只读，绝不搬运 / 销毁任何资源。</p>
     */
    private boolean supplyTargetHoldsItem(final Level level, final BlockPos target, final Item item) {
        if (level == null || target == null || item == null) {
            return false;
        }
        final BlockPos station = stationKey(level, target);
        if (holdsItemAt(level, target, item) || holdsItemAt(level, station, item)) {
            return true;
        }
        // 同一工位上的另一台机器（机械手）也要看：料可能正攥在手里，而不是摆在台上。
        for (final BlockPos other : busSupplyTargets()) {
            if (other.equals(target) || other.equals(station)) {
                continue;
            }
            if (stationKey(level, other).equals(station) && holdsItemAt(level, other, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：同一工位上的<b>另一台</b>供料目标（典型：对着同一个置物台的另一台机械手，或
     * 同一工位的第二条输出总线）此刻是否已经握着 {@code item} —— 也就是「这一份已经有人喂过了」。
     *
     * <h2>为什么需要它（本轮修正，用户第 ① ② 条：齿轮被推到工位上卡住 / 多出一份输入时原料）</h2>
     * <p>「一台机器同一时刻最多一份未消耗的投入物」这条不变式，<b>备料侧</b>早就是<b>按工位</b>判的
     * （{@link #supplyTargetHoldsItem}），而<b>推料侧</b>（{@code RsccChamberExportStrategy#transferItem}）
     * 只判了「<b>本总线朝向的那一格</b>里有没有同种料」。一个工位由 2 个及以上的供料目标组成时
     * （置物台 + 对着它的机械手 = 同一工位，或同一个置物台上并排两台机械手），
     * 第 2 条总线的 {@code holdsSameItem(自己的目标)} 为假 ⇒ 它会<b>再推一份</b>给同一工位：
     * 多出来的那一份既没有消费者（该步只需一件），又因为「本步要它」而在收回侧受保护 ⇒
     * 只能躺在那儿等卡住闸门，正是用户实测的「齿轮卡在工位上、只能手动拿走」与
     * 「中间产物多出一份」的共同来源。</p>
     *
     * <p><b>口径刻意比 {@link #supplyTargetHoldsItem} 窄</b>：只看<b>同一工位的其它供料目标</b>
     * （同一台机器 + 工位本体 + 兄弟目标里的「已经握着」），<b>不看</b>工位本体（置物台表面）上的
     * 散落件 —— 置物台上的一件成品 / 废料不该阻止机械手去拿它自己那一步该拿的投入物。
     * 判据只读、不搬运任何资源；判不出来（无世界 / 客户端）一律 {@code false}（不拦）。</p>
     *
     * @param target 本次要推过去的那一格（本总线朝向的机器 / 置物台）
     * @param item   本次要推的物品
     * @return {@code true} = 同一工位的兄弟目标已经握着这一件 ⇒ 不要再推第二份
     */
    public boolean stationTwinHoldsStepExtra(@org.jetbrains.annotations.Nullable final BlockPos target,
                                             @org.jetbrains.annotations.Nullable final Item item) {
        if (target == null || item == null || !isStepExtraInput(item)) {
            return false; // 只对「步骤专用投入物」表态（主原料 / 流体输入 / 中间产物沿用既有口径）
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false; // 客户端不参与搬运
        }
        final BlockPos station = stationKey(level, target);
        for (final BlockPos other : busSupplyTargets()) {
            // 跳过自己、也跳过「工位本体」（置物台 / 传送带表面）—— 与 supplyTargetHoldsItem 的循环
            // 同一口径：台面上的散落件（废料 / 成品）不该阻止机械手去拿它那一步该拿的投入物。
            if (other.equals(target) || other.equals(station)) {
                continue;
            }
            if (stationKey(level, other).equals(station) && holdsItemAt(level, other, item)) {
                return true; // 同一工位的另一台机器已经握着这一件 ⇒ 本总线不再推第二份
            }
        }
        return false;
    }

    /** 只读：某一格机器容器里此刻压着的所有物品种类（没有容器 / 未加载 = 空集）。 */
    private static Set<Item> occupiedItemsOf(final Level level, final BlockPos target) {
        if (target == null || !level.isLoaded(target)) {
            return Set.of();
        }
        final BlockEntity machine = level.getBlockEntity(target);
        if (machine == null) {
            return Set.of();
        }
        final IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
            target, machine.getBlockState(), machine, null);
        if (handler == null) {
            return Set.of();
        }
        final Set<Item> occupied = new HashSet<>(4);
        for (int i = 0; i < handler.getSlots(); i++) {
            final ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                occupied.add(stack.getItem());
            }
        }
        return occupied;
    }

    /** 给定的「已被机器占着」的物品集合里，是否命中该类别的任一过滤项（含 null 防御）。 */
    private static boolean anyHeld(final Set<Item> occupied, final List<Item> items) {
        if (occupied.isEmpty()) {
            return false;
        }
        for (final Item item : items) {
            if (item != null && occupied.contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 总线输出模式下的「进货」：把<b>被相连输出总线选中的类别</b>所需的物料从 RS 网络搬进本仓内部存储。
     *
     * <p><b>为什么需要这一步</b>：输出总线的取货源已经改成<b>本仓内部存储</b>
     * （见 {@code support/RsccChamberExportStrategy}），所以必须有人先把物料放进来。语义与用户描述一致：
     * 「先把所需要的原料移动到（输出到）序列执行器里面，然后与执行器相连的输出总线从执行器里面把
     * 物品和流体输出出去」。</p>
     *
     * <p><b>口径（本轮改为按需备料）</b>：</p>
     * <ul>
     *     <li>只搬<b>有输出总线在选</b>的类别（{@link #busCategoryOwners} 的键集）：没被选的类别一律不搬，
     *     不把网络里的东西无谓地吸进本仓；</li>
     *     <li>按<b>资源条目</b>逐条搬（带数据组件的过渡件也能按物品准确归类）。每个资源的目标量 =
     *     <b>该类别「每批所需量」× {@value #BUS_TARGET_BATCHES}</b>（策略 A）或
     *     <b>该类别「预估需求」× {@value #BUS_TARGET_BATCHES}</b>（策略 B，见 {@link RsccSupplyStrategy}），
     *     再按 {@value #BUS_ITEM_TARGET_MAX} 件 / {@value #BUS_FLUID_TARGET_MAX} mB 的安全上限与内部存储剩余
     *     容量夹紧 —— <b>仓内已够就不再抽</b>，超出所需的部分一样不抽，因此不会累积、不会无限增长；</li>
     *     <li><b>门控</b>：{@link #busAutoCraftGate} 未开启（网络里没有进行中的自动合成任务）时完全不搬，
     *     与输出总线的「一律不导出」就是同一道闸门；{@link #busRelevantTaskGate} 未开启（网络里有任务、
     *     但都与本仓产线无关）时同样不搬 —— 这两者合起来就是「先有<b>与本仓相关</b>的自动合成，
     *     才谈得上喂料」。最后一条很关键（用户实测断点）：网络里跑着<b>别的</b>任务时，
     *     若本仓仍照旧备料，那么「本条任务结束 → 残留原料回流网络」会与「下一 tick 又备回来」
     *     形成来回搬运；两个方向共用同一个门控后，回流过的料不会被重新吸进本仓；</li>
     *     <li><b>策略 B 补充</b>：备料后按缺口对网络里不足 / 缺失的输入发起 RS 自动合成
     *     （见 {@link #requestMissingViaAutocraft}），从而「持续供应直到目标产物达标」；</li>
     *     <li><b>不丢不复制</b>：抽取是原子的；万一仓内容量不足装不下（deficit 已按剩余容量夹过，
     *     属理论不可达），剩余物立即退回网络，绝不销毁；本方法<b>只从网络取、从不写回物料</b>，
     *     因此同一份物料不会既在网络又在本仓。</li>
     * </ul>
     */
    private void fillInternalForBus(final StorageNetworkComponent storage, final Network network,
                                    final Map<String, Integer> disallowThresholds) {
        if (!busAutoCraftGate || !busRelevantTaskGate || !busGoalTaskGate || !busConfigGate
            || busCategoryOwners.isEmpty()) {
            return;
        }
        // <b>用户第 4 条：推不动就不要再拉（备料前先确认下游能消化）。</b>
        // 目的地持续拒收（机器 / 置物台已满、或不接受）或该步没有任何机器认领时，备料侧一律停手 ——
        // 否则就是实测形态：目标机器频繁 {@code machine_full}，而本仓每 5 秒还在 {@code pull=20 feed=19}，
        // 齿轮的 net 单调下降（191→…→106）。收回侧不受此闸门影响（把东西还给网络永远不会有害）。
        if (pushStalledOnDestination()) {
            return;
        }
        // <b>R3：本轮「替代供料」记录清零</b>（下面按 R2 抽取替补件时逐笔登记，末尾由
        // {@link #reportHandoffUsage()} 按<b>边沿</b>播报一次）。清零放在这里而不是方法末尾，
        // 是为了让中途任何一条 `return` 都不会留下半份旧记录。
        handoffUsed.clear();
        final boolean targetMode = RsccSupplyPolicy.strategy(getLevel()) == RsccSupplyStrategy.TARGET;
        // 「按步拒绝」抑制表的自愈点：一旦「哪台机器轮到哪一步」变了，上一轮基于**旧步序**得出的
        // 「本步不要它」结论立刻作废，否则那份抑制会把**新步序**下的正常备料一起压掉
        // （用户实测：机械手手里空着的 2~3 秒，正是 100 tick 抑制窗口跨过了步序切换那一刻）。
        final String wantSignature = wantedStepSignature();
        if (!wantSignature.equals(lastWantedStepSignature)) {
            lastWantedStepSignature = wantSignature;
            stepRefusalCooldowns.clear();
        }
        // 资源 → 目标量（同种资源被多个类别引用时取最大需求）
        final Map<Item, Long> itemTargets = new LinkedHashMap<>();
        final Map<Fluid, Long> fluidTargets = new LinkedHashMap<>();
        // 「中间产物（过渡件）」集合：只用于<b>缺料上报的排除</b>（见 #reportBusShortages 与 #intermediateOnly）。
        // 备料行为一字未改（过渡件仍可从网络拉回复用），只是不再把它算进「缺少材料」。
        final Set<Item> intermediateItems = new LinkedHashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (!busCategoryOwners.containsKey(info.id())) {
                continue;
            }
            // <b>硬底线（第 20 轮回归 A）：只有「本仓真正拥有该步」的类别才允许生成备料需求。</b>
            // 类别表里有一类是「总样板指派补出来的<b>显示用</b>类别」（见 computeBusCategories 第 ② 遍）——
            // 它只供玩家看见 / 勾选。玩家勾中它以后，若这里照旧算成需求，就等价于「没有订单也开工」
            // （用户第 1 条的实测：没下单列车轨道，仓却去拉石头台阶 / 铁粒并一直产出过渡件）。
            // 因此这里按 (配方, 步序) 再夹一次：带配方信息的类别必须命中 ownedSteps()。
            if (!categoryOwned(info)) {
                continue;
            }
            // <b>按配方闸门</b>（2026-10-05 用户实测：没下单列车轨道却去拉石头台阶）。
            // 输入 / 流体类别在 computeBusCategories 里<b>不带配方字段</b>（recipe 为空），
            // 因此上面那道 categoryOwned 对它们恒为真、拦不住。这里改用<b>类别 id 里夹带的配方 id</b>
            // （见 inputCategoryIdForRecipe：input:<配方>#<物品>）直接问「这条配方此刻有活跃订单吗」。
            //
            // <b>2026-10-05 新增豁免（/rs_create_compat reuse on 时才生效）</b>：
            // 「中间产物」类别在没有活跃订单时也要放行 —— 这正是用户要的语义
            // （原话：「当网络中具有某序列装配的中间产物、然后现在又要开始这个序列装配的时候，
            // 是否优先使用它的中间产物，而不是从头开始合成」）。
            //
            // 为避免退化成「没订单也开工」（第 20 轮那个严重回归），豁免<b>再加一道硬条件</b>：
            // 只有当网络里<b>此刻真的有</b>该中间产物（存量 &gt; 0）时才放行。
            // 没有存量 ⇒ 与开关关闭时完全同行为 ⇒ 不会凭空拉料、不会凭空开新件。
            final boolean reuseIntermediate = info.isIntermediate()
                && RsccIntermediateReusePolicy.reuseIntermediates(getLevel())
                && hasNetworkStock(info);
            if (!categoryRecipeOrdered(info) && !reuseIntermediate) {
                continue;
            }
            // <b>2026-10-06：备料侧与推料侧共用同一道「共享机器排队」闸门</b>
            // （判据见 {@link #blockedByMachineQueue}，需求一的另一面）。为什么备料侧也要拦：
            // 本仓这台机器被<b>先下单的那条配方</b>占着时，把落选那条配方的过渡件从网络 / 缓存池
            // 拉进本仓只会堵在本仓内部存储里（推不出去），既占容量又制造「有料却不动」的假象。
            // 拦下之后，落选配方的半成品<b>原地留在缓存仓（网络共享缓存池）</b>里等着，
            // 前序步骤做完的件继续堆在那儿 —— 这正是用户要的「堆进缓存仓，然后等待」；
            // 机器一腾出来（赢家的活干完 / 它一时无件可做），下一 tick 这道闸门自动打开，
            // 池里那些件会被正常拉进来接着加工。落选配方若还有一件正压在机器上，闸门同样放行
            // （规则 ③ 的逃生口），先把它做完。判不出来时返回 false，行为逐字不变。
            if (blockedByMachineQueue(info)) {
                continue;
            }
            if (info.isIntermediate()) {
                for (final Item item : info.items()) {
                    if (item != null) {
                        intermediateItems.add(item);
                    }
                }
            }
            final long perBatch = Math.max(1L, info.amount()); // 配方每批所需（物品 = 件数，流体 = mB）
            // 策略 A（MATERIALS）：不看概率，只按「每批需求 × 1」备料（既有口径）。
            // 策略 B（TARGET）：按「预估需求」备料（步骤输入 = 每批 × loops），持续供应到达标。
            final long want = targetMode ? Math.max(perBatch, info.estimated()) : perBatch;
            final long itemTarget = Math.min(want * BUS_TARGET_BATCHES, BUS_ITEM_TARGET_MAX);
            final long fluidTarget = Math.min(want * BUS_TARGET_BATCHES, BUS_FLUID_TARGET_MAX);
            for (final Item item : info.items()) {
                if (item != null) {
                    // 「步骤专用投入物」再按「每台要它的机器各一份」夹一次（见 #stepExtraStockTarget）：
                    // 既不是「总共一份」，也不是「每批 × loops」的大缓冲 —— 大缓冲是多备出来的那几份
                    // 立刻被判成「本步不要它」→ 被收回侧抄回网络 → 再买一遍的死循环的来源。
                    final long cap = stepExtraStockTarget(item);
                    // 用户硬要求：只有当机器「缺料而停」时才补一份；一个工位都不缺它 → 本 tick 一条判断都不做。
                    // 于是 pull 侧也不会再产生「本步不要它」的反复判定（齿轮空转的根源）。
                    if (cap <= 0L) {
                        continue;
                    }
                    itemTargets.merge(item, Math.min(itemTarget, cap), Math::max);
                }
            }
            for (final Fluid fluid : info.fluids()) {
                if (fluid != null) {
                    fluidTargets.merge(fluid, fluidTarget, Math::max);
                }
            }
        }
        // <b>「补合成」用的目标表：在「在制名额」夹量<b>之前</b>留一份快照（本轮新增，用户第 ④ 条）</b>。
        // 为什么必须留：{@link #clampStartIngredientTargets} 会在「本仓此刻已经有一件在制」时把起步原料
        // 整条从备料表里去掉 —— 那是为了不让<b>备料侧</b>多买一份；但「向 RS 请求补合成」
        // （{@link #requestMissingViaAutocraft}）只是请求 RS 把材料做进网络，<b>不抽取、不投入</b>，
        // 缺料时（尤其是网络里一件都没有）必须照常请求，否则用户写好的配方永远不会被调用 ——
        // 用户原话：「我已经给金板编写了一个配方，可以用它来进行自动合成，但你却并没有正常使它调用」。
        // 用夹量前的目标量去请求，请求量仍然只是「每批需求 × 1」（{@value #BUS_TARGET_BATCHES} 批），
        // 因此不会退化成「整批拉出」（用户第 ⑤ 条），只是把「补合成」这一步从备料夹量里解耦出来。
        final Map<Item, Long> autocraftItemTargets = new LinkedHashMap<>(itemTargets);
        final Map<Fluid, Long> autocraftFluidTargets = new LinkedHashMap<>(fluidTargets);
        // <b>起步原料的「在制名额」夹量（见 {@link #clampStartIngredientTargets}）</b>：
        // 必须在「起始原料多开一件」进入备料之前把目标量夹掉，否则仓里会多出一份粉、下一 tick 就被
        // 第二条总线推给第二台机器（用户实测「下单 1 个坚固板消耗两个黑曜石粉」的最后一环）。
        clampStartIngredientTargets(itemTargets);
        // <b>R1 + R2：把「替补件」的目标表算出来（只读）</b>。
        // 为什么必须有它（用户硬要求：「不管怎么样，还是得取到一个」）：R1 收敛之后目标量只挂在
        // 「必须由它自己满足」的那一件上；网络里如果恰好只有替补件（现场：铁粒用光、只剩锌粒），
        // 没有这张表就会一件都抽不进来 —— 「候选不齐」被放大成「整条线停摆」。
        // 这张表只影响「抽哪一件」，每件的目标量仍受同一条 {@code stepExtraStockTarget} 上界约束，
        // 因此整组仍然只备一种、绝不会因为多了一条路径就多备一份。
        final Map<Item, Long> substituteTargets = substituteTargetsFor(itemTargets);
        // 只读诊断：把「本 tick 真正的备料目标表」原样留一份给 /rs_create_compat diag 导出。
        // 纯记录，不参与任何判定（因此对既有行为零影响）。
        diagItemTargets = new LinkedHashMap<>(itemTargets);
        diagFluidTargets = new LinkedHashMap<>(fluidTargets);
        if (itemTargets.isEmpty() && fluidTargets.isEmpty()
            && autocraftItemTargets.isEmpty() && autocraftFluidTargets.isEmpty()) {
            return;
        }
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            final ResourceKey key = tracked.resourceAmount().resource();
            final long available = tracked.resourceAmount().amount();
            if (available <= 0) {
                continue;
            }
            if (key instanceof final ItemResource itemResource) {
                final Long target = itemTargets.get(itemResource.item());
                if (target != null) {
                    pullItem(storage, itemResource, available, target, disallowThresholds);
                    continue;
                }
                // <b>R2：替补件这一档（只读判定，抽取仍走同一个 pullItem）</b>。
                final Long substituteTarget = substituteTargets.get(itemResource.item());
                if (substituteTarget == null || substituteTarget <= 0L) {
                    continue;
                }
                if (!substituteApplies(itemTargets, itemResource.item())) {
                    continue; // 最优件其实有货 / 这个兄弟覆盖不了需求 ⇒ 这一档不成立
                }
                // 抽取是否真的成功以 pullItem 的返回值为准（它是唯一的搬运点）；只有真的抽进来一件
                // 才登记一次替代供料，因此绝不会弹出一条「其实没抽到」的假提示。
                if (pullItem(storage, itemResource, available, substituteTarget, disallowThresholds)) {
                    handoffUsed.merge(new Substitution(preferredFor(itemTargets, itemResource.item()),
                        itemResource.item()), 1L, Long::sum);
                }
            } else if (key instanceof final FluidResource fluidResource) {
                final Long target = fluidTargets.get(fluidResource.fluid());
                if (target != null) {
                    pullFluid(storage, fluidResource, available, target);
                }
            }
        }
        // 对「网络里不足 / 缺失」的输入原料按缺口发起 RS 自动合成（缺什么补什么）。
        //
        // <b>本轮修正（用户第 D 条：「序列装配为什么它不能自己触发精致存储它自己的合成？……我已经给
        // 金板编写了一个配方，可以用它来进行自动合成，但你却并没有正常地使它调用」）</b>：
        // 旧实现把这一步<b>只挂在「直到目标产物达标」策略下</b>（{@code if (targetMode)}）—— 于是用默认
        // 策略（按每批需求）时，即便玩家给缺的原料写了完整配方，本仓也只会「报缺」而<b>从不向 RS 请求合成</b>。
        // 现在改为<b>只要本仓真在跑一条有人下单的产线</b>就补：本方法唯一入口 {@link #fillInternalForBus}
        // 已经在开头要求 {@code busGoalTaskGate}（= 有人为本产线下单），因此「无订单不自动合成」这条约束
        // 一字未改 —— 两者不矛盾：<b>有订单才请求 RS 合成</b>。
        // <b>用户第 ④ 条（本轮修正）</b>：请求用「在制名额夹量<b>之前</b>」的目标表（{@code autocraft*}），
        // 因此「本仓已有一件在制 ⇒ 起步原料的备料目标被夹成 0」时，这条「补合成」仍然照常发出 ——
        // 否则用默认策略 + 恰好有一件在制（绝大多数时刻）时，那条请求恒不发出，
        // 玩家写好的金板配方就永远得不到调用（正是用户实测的现象）。
        requestMissingViaAutocraft(network, autocraftItemTargets, autocraftFluidTargets);
        // 缺料上报（诊断日志 + <b>给玩家的行动栏提示</b>）：无论诊断开关是否打开都要跑，
        // 因为「因缺料而停 ⇒ 必须弹提示」是用户硬要求（见 reportBusShortages）。
        reportBusShortages(storage, itemTargets, fluidTargets, intermediateOnly(intermediateItems));
        // <b>R3/R4：替代供料的提示（边沿语义）</b>。放在最后：它读的是本轮
        // {@link #pullItem} 真的抽进了哪些替补件（{@link #handoffUsed}），因此
        // 「提示」与「实际发生的事」严格一一对应，不会凭预测弹窗。
        reportHandoffUsage();
    }

    /**
     * 只读：从「中间产物（过渡件）」集合里剔掉<b>同时又是本仓输入类 / 成品类类别</b>的那些，
     * 得到「<b>纯中间产物</b>」集合 —— 缺料上报的排除清单（用户要求：提示缺少原料时必须<b>自动忽略中间产物</b>）。
     *
     * <p><b>为什么要剔</b>：万一某个物品<b>既是</b>某步的过渡件、<b>又是</b>某类真实投入物
     * （玩家把两种用途都勾上），把它从缺料里排除就会漏报真实缺料。这里只排除「<b>纯</b>中间产物」，
     * 因此「该报的绝不会漏」（用户验收标准：别把真实缺料漏掉）。只读，不搬运任何资源。</p>
     */
    private Set<Item> intermediateOnly(final Set<Item> intermediateItems) {
        if (intermediateItems.isEmpty()) {
            return Set.of();
        }
        final Set<Item> realInputs = new LinkedHashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (!busCategoryOwners.containsKey(info.id()) || info.isIntermediate()) {
                continue; // 只看「非中间产物」且本仓真的勾了的类别
            }
            for (final Item item : info.items()) {
                if (item != null) {
                    realInputs.add(item);
                }
            }
        }
        final Set<Item> result = new LinkedHashSet<>(intermediateItems);
        result.removeAll(realInputs);
        return result;
    }

    /**
     * <b>只读</b>：把「起步原料」的备料目标量夹到「本仓此刻还能再开几件」以内（就地改 {@code itemTargets}）。
     *
     * <h2>为什么它必须是独立的一道闸门（用户第 ①⑤ 条）</h2>
     * <p>「起步原料」（Create 序列装配的 {@code ingredient}，例如黑曜石粉 / 金板）是唯一会
     * <b>开出一件新在制件</b>的投入物，而它的备料目标量本来只有 {@code 每批需求 × 1} 份
     * （{@link #BUS_TARGET_BATCHES}）—— 小数目的目标量挡不住下面这条循环：</p>
     * <pre>
     *   推 1 份粉给机器 A → 仓里空出 1 格 → 缺口又是 1 → 再抽 1 份粉进仓
     *                     → 第二条输出总线把这份粉推给机器 B → 两台机器各开一件在制件
     * </pre>
     * <p>用户实测「下单 1 个坚固板消耗了两个黑曜石粉」（而订单 64 件时读得到剩余件数、闸门生效，
     * 因此反而正常）。判据 = <b>「这份起步原料还能再开几件」</b>（{@link #startCapacityFor}）。差额为 0 时
     * 把这份起步原料整条从目标表里去掉（{@code cap <= 0} ⇒ 一条判定都不做），于是「一件在飞时绝不再备第二份」。</p>
     *
     * <p><b>2026-10-06：判据从「整仓一个名额」细化成「逐物品按配方算」</b>（{@link #startCapacityForRecipe}）：
     * 必得（100%）配方严格 {@code 订单剩余 − 跨执行舱在制件}，非必得（&lt;100%）配方按
     * {@code ceil(订单剩余 / p) − 跨执行舱在制件} 放大（推导见 {@link #startCapacityForRecipe}）。
     * 只改「怎么算这个名额」，闸门的位置与「为 0 就整条去掉」的语义一字未改。</p>
     *
     * <p><b>为什么不是「总是 1 份」</b>：订单 64 件 + 两台机器时差额是 62，两台机器仍能并行开工；
     * 只有「判不出来 + 已经有一件在制」时才收敛到 0，正是用户要的「不确定时取小不取大」。</p>
     *
     * <p>绝不搬运 / 销毁任何资源：本方法只改一份<b>本地</b>目标量映射。</p>
     */
    private void clampStartIngredientTargets(final Map<Item, Long> itemTargets) {
        if (itemTargets.isEmpty()) {
            return;
        }
        boolean hasStartIngredient = false;
        for (final Item item : itemTargets.keySet()) {
            if (isStartIngredient(item)) {
                hasStartIngredient = true;
                break;
            }
        }
        if (!hasStartIngredient) {
            return; // 与本仓起步原料无关（绝大多数批次）：一条判定都不做
        }
        // <b>2026-10-06：名额改成逐物品各算各的</b>（{@link #startCapacityFor} → 按「这份料属于哪条配方」，
        // 必得配方严格 = 订单剩余 − 跨舱在制件，非必得配方 = ceil(R/p) − 跨舱在制件）。
        // 旧写法对整张目标表只算一个 cap，于是「一个仓同时挂轨道（下单 1）与精密构件（下单 64）」时，
        // 整仓名额 64 会把石头台阶也一起放行 —— 那正是「下单 1 条轨道产出 2 条」的另一条路径。
        // 快照遍历：下面要就地删键（名额为 0 的整条去掉）。
        for (final Item item : new ArrayList<>(itemTargets.keySet())) {
            if (!isStartIngredient(item)) {
                continue;
            }
            final long capacity = startCapacityFor(item);
            if (capacity <= 0L) {
                itemTargets.remove(item); // 名额为 0 ⇒ 整条去掉（后续一条判定都不做，见 pullItem 的 entries）
                continue;
            }
            final Long target = itemTargets.get(item);
            if (target != null) {
                itemTargets.put(item, Math.min(target, capacity));
            }
        }
    }

    /**
     * <b>只读</b>：本仓产线相关的自动合成任务是不是<b>已经全部交付满</b>（= 需求已达成）。
     *
     * <h2>为什么要它（用户第 4 条：做完了也报缺）</h2>
     * <p>一次自动合成任务的「目标量」在<b>最后一件正在加工</b>的那几秒里仍然成立；此刻物料全在线
     * （网络 0 + 本仓 0 + 机器侧那一份正被消耗、{@code machineHeldFluid} 读不到），于是
     * {@link #reportBusShortages} 算出一个「尾巴缺口」，任务一完成这条提示就成了纯噪音
     * （用户原话：「我放了三桶熔岩做 64 个坚固板，绝对够、刚好够，但到最后它告诉我缺少 500 毫桶熔岩，
     * 可是它完美完整地把 64 个坚固板做完了」）。</p>
     * <p><b>口径</b>：与 {@link #computeRemainingOrderUnits} 完全同一份数据（{@link TaskStatus} 的
     * {@code amount()} 与 {@link AssemblyWatchdog#deliveredAmount} 给出的已交付量）。已交付量<b>不再</b>
     * 取 {@code stored + crafting}（那是 RS 的 internalStorage 口径，对本模组的 root EXTERNAL 样板恒为 0，
     * 会让本方法恒判「没交付满」，见 {@link #remainingOrderUnitsFor(String)} 的根因说明），也不用
     * {@code percentageCompleted}（外部样板的权重是剩余派发次数，派发一空加权完成度恒为 0）。</p>
     * <p><b>返回值语义</b>：相关任务全部交付满 → {@code true}（不得报缺）；
     * 只要还有一条相关任务未交付满 → {@code false}（照旧按缺口报）；
     * 一条相关任务都没有（任务已完成 / 已取消）→ {@code true}（没有未满足的需求，同样不得报缺）。</p>
     * <p>绝不搬运 / 销毁任何资源：本方法只读任务状态。</p>
     */
    private boolean orderDeliveredInFull() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return true; // 客户端不参与缺料上报；服务端判定见下
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return true; // 没挂在网络上：没有「本仓相关需求」可言
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return true;
        }
        final Set<Item> related = pipelineItemsCache;
        final Set<Fluid> relatedFluids = inputCategoryFluids();
        for (final TaskStatus status : autocrafting.getStatuses()) {
            if (related != null && !isRelatedResource(status.info().resource(), related, relatedFluids)) {
                continue; // 与本仓产线无关的任务（别的机器在跑）：不影响本仓是否该报缺
            }
            // <b>2026-10-06 根因修复</b>：已交付量改用 RS 权威读数（见
            // {@link #remainingOrderUnitsFor(String)} 处的完整说明）。旧口径的 stored + crafting
            // 对本模组的 root EXTERNAL 样板恒为 0 ⇒ 本方法恒返回 false ⇒「刚好够却仍报缺」永远修不掉。
            final long ordered = Math.max(1L, status.info().amount());
            final long delivered = AssemblyWatchdog.deliveredAmount(status);
            if (delivered < ordered) {
                return false; // 还有未交付的份：这时报缺才是真的缺
            }
        }
        return true; // 相关任务全部交付满 / 没有相关任务 ⇒ 需求已达成，不得报缺
    }

    /**
     * 本仓「总线备料」当前仍缺什么（含<b>流体类型</b>）缺多少、成因，并<b>据此给玩家提示</b>。
     *
     * <p><b>两件事，各自有闸门</b>：</p>
     * <ol>
     *     <li><b>诊断日志</b>（前缀 {@code [rscc-assembly]}）：只在「缺口集合」发生变化时打一条
     *     （{@link RsccAssemblyDebug#transition}），因此稳态零输出；</li>
     *     <li><b>缺料提示</b>（<b>横幅</b>，用户要求）：<b>只有真的缺料才发</b>，同一份缺口最多每
     *     {@value #BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS} tick 发一次（缺口变化时立即再发一条）——
     *     既满足「因缺料而停必须弹提示、并说清缺哪种材料缺多少」，又不会刷屏。
     *     横幅只发给本仓 {@value #BUS_SHORTAGE_NOTIFY_RADIUS} 格内的玩家（服务端权威）。
     *     <p><b>为什么走横幅而不是行动栏（用户原话）</b>：「我之前不是说过缺料的时候要弹横幅吗？」
     *     —— 行动栏在物品栏上方一闪而过，容易漏看；横幅是工程既有的
     *     {@code report.CompatCompletionSender} 那条一次性 Toast 路径（与蓝图装填器 / 看门狗同款），
     *     更显眼且能与其它完成提示统一呈现。</p></li>
     * </ol>
     * <p>成因判定：内部存储已满 → {@code storage_full}；网络里一点都没有 → {@code no_stock}；
     * 网络有货但存量不足 → {@code net_short}。只读，绝不搬运 / 销毁任何资源。</p>
     *
     * <p><b>为什么不把「中间产物（过渡件）」算进缺料（用户硬要求，本轮修正）</b>：过渡件
     * （{@code create:unprocessed_obsidian_sheet} / {@code create:incomplete_precision_mechanism} …）
     * 是<b>产线自己在线造出来的</b>，网络里天然一件都没有 —— 旧实现把它们也按「类别目标量 − 仓内量」
     * 计入缺口，于是日志与横幅常年写着
     * {@code short {item=create:incomplete_precision_mechanism x1 net=0 reason=no_stock}}。
     * 用户原话：「它提示我缺少中间产物。提示缺少原料的时候，应该自动忽略中间产物。」
     * 因此上报时按 {@code intermediateOnly}（纯中间产物，见 {@link #intermediateOnly}）整条跳过；
     * <b>备料行为不动</b>（网络里若有过渡件残留，照旧可以拉回来复用），真实原料缺料也照旧会报。</p>
     */
    private void reportBusShortages(final StorageNetworkComponent storage,
                                    final Map<Item, Long> itemTargets,
                                    final Map<Fluid, Long> fluidTargets,
                                    final Set<Item> intermediateOnly) {
        // <b>用户第 4 条：需求已满足 / 任务已完成时不得报缺</b>（「三桶熔岩刚好够、做完了还报缺 500 mB」）。
        // 根因：一次自动合成任务的目标量在「最后一件正在加工」的那几秒仍然成立，而此刻物料全在线
        // （网络 0 + 仓 0 + 机器侧正被消耗的那一份没被 machineHeldFluid 读到），于是算出一个「尾巴缺口」，
        // 等任务一完成这条提示就成了噪音。这里在「相关任务已全部交付满 / 已经没有相关任务」时整条跳过，
        // 因此「报缺」只会出现在<b>真的有未交付需求</b>的时候（判据见 orderDeliveredInFull）。
        if (orderDeliveredInFull()) {
            clearShortageGates(); // 归零：真的缺料时能立刻再报一次（不被上一份缺口签名压住）
            return;
        }
        // <b>用户第 3 条（本轮）：可自动合成的资源一律不报缺、静默等待</b>（「某原料我已经给它写了配方，
        // 它要时间做出来；别一直在这里『缺少材料、缺少材料』地喊」）。判据 = 终端里有以它为输出的样板
        // （= 它可以被自动合成，或已有它的合成任务在跑）。取不到自动合成组件时<b>照旧报缺</b>
        // （绝不因为判不出来而漏掉真实缺料）。只读。
        final Network shortageNetwork = getNode().getNetworkOrNull();
        final AutocraftingNetworkComponent shortageAuto = shortageNetwork == null ? null
            : shortageNetwork.getComponent(AutocraftingNetworkComponent.class);
        // 「满了」的判据统一为「内部存储 + 磁盘缓存都装不下」：磁盘放入后就成为本仓可用空间的一部分，
        // 因此只有两者同时没有余量时才算 storage_full（与既有诊断口径一脉相承，只是分母变大了）。
        final long itemCapacity = itemStorage.getRemainingCapacity();
        final long fluidCapacity = outputTank.getRemainingCapacity();
        // 网络侧存量按「物品种类 / 流体种类」汇总一次（<b>本轮修正</b>），而不是
        // `storage.get(new ItemResource(item))`：后者用的是**不带数据组件**的资源键，
        // 而中间产物 / 过渡件在网络里是带 `create:sequenced_assembly` 组件的另一种资源键 ——
        // 于是日志永远打 `net=0 reason=no_stock`，把「网络里其实有货」报成「一点货都没有」
        // （实测日志里 `short {item=create:unprocessed_obsidian_sheet x1 net=0 reason=no_stock}`
        // 就是这样产生的，排查时会被误导到「网络里没料」这条错路上）。
        final Map<Item, Long> netItems = new LinkedHashMap<>();
        final Map<Fluid, Long> netFluids = new LinkedHashMap<>();
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            final ResourceKey key = tracked.resourceAmount().resource();
            final long amount = tracked.resourceAmount().amount();
            if (amount <= 0) {
                continue;
            }
            if (key instanceof final ItemResource item) {
                netItems.merge(item.item(), amount, Long::sum);
            } else if (key instanceof final FluidResource fluid) {
                netFluids.merge(fluid.fluid(), amount, Long::sum);
            }
        }
        final StringBuilder sb = new StringBuilder(96);
        final List<Shortage> shortages = new ArrayList<>(4);
        for (final Map.Entry<Item, Long> entry : itemTargets.entrySet()) {
            if (intermediateOnly.contains(entry.getKey())) {
                continue; // 纯中间产物（过渡件）：产线自己在线造，网络里没有不算「缺料」（用户硬要求）
            }
            // <b>「输入原料组」按整组判定（用户第 1 条：标签型输入原料）</b>：
            //   ① 非代表物不重复报（一个 ingredient 只由代表物报一条，否则三种台阶各报一条）；
            //   ② 可互换的兄弟候选任一处有货（仓内 / 网络 / 机器侧）⇒ 整组已满足，不算缺
            //      —— 否则「网络里有平滑石台阶、代表物石头台阶没有」会被误报成缺料。
            // 两条都只读，且只在「确实可互换」时生效（不会吞掉精密构件那条真缺铁粒）。
            if (isInterchangeableFollower(entry.getKey()) || interchangeableAvailable(entry.getKey())) {
                continue;
            }
            // <b>缺口 = 目标量 −（本仓内部存量 + 网络存量）</b>（本轮修正，用户第 ① 条：
            // 「终端里明明有 32~64 桶岩浆，却一直提示缺少 500 mB 熔岩，而且它明明在正常跑」）。
            // 旧实现只减「本仓内部存量」，网络存量算了却只写进日志的 net= 字段、<b>从不参与判定</b>：
            // 物料正常地「网络 → 本仓 → 机器」流动时，本仓内部恰好是空的（刚推给机器），于是每 5 秒
            // 报一次「缺 500 mB」——网络里明明堆着几十桶。现在把网络存量算进可用量，
            // 因此「网络里有货 ⇒ 不报缺」，只有「网络 + 本仓都凑不齐」才真的报缺。
            // <b>可用量再减「本仓供料目标（机器 / 置物台）上已经压着的那一份」</b>（口径与流体侧的
            // machineHeldFluid 严格对称）。为什么必须有它（用户第 1 条：刚好够却报缺）：起步原料一旦
            // 被推进机器，本仓内部立刻为空、网络里的存量也在减少 —— 只减「本仓 + 网络」会在「机器上
            // 正压着那一份（还没被消耗）」的每个瞬间都误报一次缺料。
            final long shortBy = entry.getValue() - storedItemAmount(entry.getKey())
                - netItems.getOrDefault(entry.getKey(), 0L)
                - machineHeldItem(entry.getKey());
            if (shortBy <= 0) {
                continue;
            }
            // <b>在途合成量也算「已有」（用户第 4 条：明明够了却报缺）</b>：RS 已经开好任务、正在合成
            // 这个物品时，缺口会在这几十 tick 内被补齐 —— 此刻报缺就是纯噪音（用户原话「刚刚明明还是
            // 够了」）。口径与 {@link #requestMissingViaAutocraft} 的缺口计算完全一致（那里也扣在途量），
            // 因此「要多少」与「报不报缺」用的是同一个可用量口径，不会出现「一边请求一边喊缺」。
            // 判不出来（拿不到自动合成组件）时返回 0 ⇒ 照旧报缺，绝不漏掉真实缺料。
            if (shortBy <= autocraftInFlight(new ItemResource(entry.getKey()))) {
                continue;
            }
            // 用户第 3 条：该资源「可被自动合成 / 已有合成任务在跑」⇒ 不报缺、静默等待（本轮新增）
            if (isAutoCraftable(shortageAuto, entry.getKey())) {
                continue;
            }
            appendShortage(sb, "item=" + RsccAssemblyDebug.itemId(entry.getKey()), shortBy,
                netItems.getOrDefault(entry.getKey(), 0L), itemCapacity);
            final ItemStack shortageIcon = new ItemStack(entry.getKey());
            shortages.add(new Shortage(shortageIcon, shortageIcon.getHoverName().getString(), shortBy,
                KEY_SHORTAGE_ENTRY_ITEM));
        }
        for (final Map.Entry<Fluid, Long> entry : fluidTargets.entrySet()) {
            // 流体比物品还多一处「已经在机器里了」：注液机罐里压着 500 mB 时本仓罐里必然是空的
            // （抽进来就立刻推出去），只减本仓存量必然每 5 秒误报一次。因此可用量 =
            // 本仓罐内 + 网络存量 + <b>本仓供料目标（机器侧）已经压着的那一份</b>（machineHeldFluid）。
            final long shortBy = entry.getValue() - storedFluidAmount(entry.getKey())
                - netFluids.getOrDefault(entry.getKey(), 0L) - machineHeldFluid(entry.getKey());
            if (shortBy <= 0) {
                continue;
            }
            // 物品侧同口径（见上面那段）：在途合成量覆盖缺口 ⇒ 不报缺。流体单位是 mB，账目一步不差。
            if (shortBy <= autocraftInFlight(new FluidResource(entry.getKey()))) {
                continue;
            }
            // 用户第 3 条：流体口径与物品侧完全一致（可自动合成 ⇒ 不报缺、静默等待）。
            if (isAutoCraftable(shortageAuto, entry.getKey())) {
                continue;
            }
            appendShortage(sb, "fluid=" + RsccAssemblyDebug.fluidId(entry.getKey()), shortBy,
                netFluids.getOrDefault(entry.getKey(), 0L), fluidCapacity);
            final FluidStack shortageFluid = new FluidStack(
                net.minecraft.core.registries.BuiltInRegistries.FLUID.wrapAsHolder(entry.getKey()), 1);
            // 流体没有「物品图标」可言，图标留空（名字仍按流体显示名走）；单位是 mB（用户要求带单位）。
            shortages.add(new Shortage(ItemStack.EMPTY, shortageFluid.getHoverName().getString(), shortBy,
                KEY_SHORTAGE_ENTRY_FLUID));
        }
        if (sb.length() == 0) {
            clearShortageGates();
            return;
        }
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.transition("short@" + RsccAssemblyDebug.at(worldPosition), sb.toString(),
                RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " short {" + sb + "}"
                    + " cache={" + itemCacheTag() + "}");
        }
        notifyBusShortage(shortages, sb.toString());
    }

    /**
     * 清空缺料播报的两道状态：<b>上一份缺口指纹</b>与<b>稳定期起点</b>。
     *
     * <p><b>为什么两者必须一起清</b>：缺口消失（或需求已达成）后再出现<b>完全相同</b>的一份缺口时，
     * 若只清「上次播报过的签名」而保留稳定期起点，新出现的缺口会立刻通过稳定期判定并弹横幅 ——
     * 那正是「抖动又弹一次」的漏洞。一起清空后，任何一次新的缺口都必须重新稳定
     * {@value #BUS_SHORTAGE_STABLE_TICKS} tick 才会被播报。只改本类的三个播报状态字段，不搬运任何资源。</p>
     */
    private void clearShortageGates() {
        busShortageSignature = "";
        shortageStableSignature = "";
        shortageStableSince = 0L;
    }

    /**
     * 一条缺口（横幅用）：材料图标 + 显示名 + 还缺多少 + <b>条目语言键</b>（物品 / 流体各一条，
     * 单位写在译文里 —— 用户本轮要求「资源不足提示缺少单位」，见 {@link #KEY_SHORTAGE_ENTRY_ITEM}）。
     */
    private record Shortage(ItemStack icon, String name, long missing, String entryKey) {
    }

    /**
     * 缺料横幅：<b>只有真的缺料才发</b>；同一份缺口最多每
     * {@value #BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS} tick 一条（缺口变化时立即再发一条）。
     *
     * <p><b>为什么必须加它（用户原话）</b>：「它又有那个什么概率嘛，所以说它偶尔会直接停下来。
     * 这种时候我不说了吗：缺少材料也需要弹弹窗。但是你现在并没有弹弹窗。」
     * 因此这里在「本仓缺料（等待原料 / {@code no_stock} / {@code net_short} / {@code storage_full}）」
     * 时给附近玩家弹一条<b>横幅</b>——走工程既有的 {@link CompatCompletionSender} 一次性 Toast 路径
     * （与蓝图装填器 / 看门狗横幅同款），列出缺哪种材料、缺多少（最多列
     * {@value #BUS_SHORTAGE_NOTIFY_MAX} 种，其余用「其他 N 种」概括）。</p>
     *
     * <p><b>为什么是横幅而不是行动栏（用户原话）</b>：「我之前不是说过缺料的时候要弹横幅吗？」
     * 行动栏在物品栏上方一闪而过、容易漏看；横幅更显眼，且与其它完成提示同一套呈现。</p>
     *
     * <p>只读 + 只发消息：不搬运、不修改任何库存。服务端权威，客户端不参与。</p>
     */
    private void notifyBusShortage(final List<Shortage> shortages, final String detail) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || shortages.isEmpty()) {
            return;
        }
        final long now = level.getGameTime();
        final String signature = shortages.toString();
        // <b>先过「稳定期」（用户第 4 条：明明够了却提示少了一个黑曜石粉末）</b>：
        // 缺口指纹一变就把起点重置为现在并<b>返回</b>（先不发）；只有同一份缺口原样持续
        // {@value #BUS_SHORTAGE_STABLE_TICKS} tick 才继续往下走。因此「网络 ⇄ 本仓 ⇄ 机器」之间
        // 一两帧的瞬时缺口永远不会被播报，而真缺料只晚 2 秒被报告。
        if (!signature.equals(shortageStableSignature)) {
            shortageStableSignature = signature;
            shortageStableSince = now;
            return;
        }
        if (now - shortageStableSince < BUS_SHORTAGE_STABLE_TICKS) {
            return; // 缺口还没稳定（可能是瞬时抖动）：这一轮一条都不发
        }
        // <b>用户第 4 条（本轮）：同一份缺口「未变化就绝不重复播报」</b>。
        // 旧实现只做「5 秒限频」：冷却一到就把<b>同一份</b>缺口再弹一次，于是玩家看到的就是「怎么一直在弹」。
        // 现在改成<b>去重为主、限频为辅</b>：
        //   * 签名与上一次完全一致 ⇒ 一条都不再发（直到缺口真的变化，例如缺口从 1 变成 2 或消失）；
        //   * 签名变了但距上次不足 {@value #BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS} tick ⇒ 仍等冷却
        //     （防止缺口在 1 上下抖动时反复刷屏）。
        if (signature.equals(busShortageSignature) || now < busShortageNotifyTick) {
            // 诊断（只读）：同一份缺口被去重 / 限频吞掉 —— 记一笔，证明「条件持续存在但只提示一次」。
            cretae.cookiewyq.rs_create_compat.support.RsccDiag.recordBannerSuppressed("shortage_chamber",
                worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ());
            return; // 同一份缺口 / 冷却未到：不重复发（不刷屏）
        }
        busShortageSignature = signature;
        busShortageNotifyTick = now + BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS;
        cretae.cookiewyq.rs_create_compat.support.RsccDiag.recordBanner("shortage_chamber",
            worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ());
        // 发横幅的同时留一条结构化日志（同一处、同一限频：每仓最多每 5 秒一条），
        // 于是「缺料 ⇒ 一定有提示」可以在日志里被逐条取证（自检脚本的断言③据此判定）。
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " alert reason=missing_material missing {" + detail + "}");
        }
        if (!(level instanceof final net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        // 横幅只发给本仓附近的玩家（CompatCompletionSender 内部按平方距离筛选）。
        CompatCompletionSender.sendToNearby(serverLevel, worldPosition,
            BUS_SHORTAGE_NOTIFY_RADIUS * BUS_SHORTAGE_NOTIFY_RADIUS, shortageBannerRows(shortages));
    }

    /**
     * 缺料横幅的行：第 1 行标题，第 2 行「材料×缺量、…、其他 N 种」（带物品图标 + 逐段颜色）。
     * <p>每一段都是「图标 + 语言键段」：语言键由客户端解析（服务端不解析），中英玩家各看各的。</p>
     */
    private static List<CompletionBannerPayload.Row> shortageBannerRows(final List<Shortage> shortages) {
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(2);
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE,
            CompletionBannerPayload.localized(KEY_SHORTAGE_TITLE)));
        final List<ItemStack> icons = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        final int listed = Math.min(shortages.size(), BUS_SHORTAGE_NOTIFY_MAX);
        for (int i = 0; i < listed; i++) {
            if (i > 0) {
                icons.add(ItemStack.EMPTY);
                texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
                colors.add(COLOR_TEXT);
            }
            final Shortage shortage = shortages.get(i);
            icons.add(shortage.icon());
            // 条目 = 「材料 ×缺量 单位」（单位写在条目译文里，物品 / 流体各一条键，见 KEY_SHORTAGE_ENTRY_*）：
            // 用户本轮要求「资源不足提示缺少单位」，例如「缺少材料：黑曜石粉 ×5 个」/ 流体「… ×500 mB」。
            texts.add(CompletionBannerPayload.localized(shortage.entryKey(), shortage.name(), shortage.missing()));
            colors.add(PALETTE[i % PALETTE.length]);
        }
        if (shortages.size() > listed) {
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
            colors.add(COLOR_TEXT);
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_SHORTAGE_MORE, shortages.size() - listed));
            colors.add(COLOR_TEXT);
        }
        rows.add(CompletionBannerPayload.Row.colored(icons, texts, colors));
        return rows;
    }

    /**
     * 诊断：物品缓存状态（内部存储 + 网络内共享缓存池）。
     * <p>为什么要把池子单独列出来（用户要求「缓存满时…在诊断日志里体现」）：只看总量的话，
     * 玩家无法判断「装不下」到底是因为<b>附近没有缓存仓 / 盘没插</b>、还是<b>连盘也满了</b>。</p>
     */
    private String itemCacheTag() {
        return "cap=" + itemStorage.getRemainingCapacity()
            + " pool=" + cretae.cookiewyq.rs_create_compat.support.RsccSharedCache.describe(
                getLevel(), mainNetworkNode.getNetwork());
    }

    /** 追加一条缺口描述：{@code item=xxx x5 net=0 reason=no_stock}。 */
    private static void appendShortage(final StringBuilder sb, final String resource, final long shortBy,
                                       final long network, final long capacity) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        final String reason = capacity <= 0 ? "storage_full" : (network <= 0 ? "no_stock" : "net_short");
        sb.append(resource).append(" x").append(shortBy)
            .append(" net=").append(network)
            .append(" reason=").append(reason);
    }

    /** 诊断：执行舱物品存储（内部 + 磁盘缓存）里该物品的现有总量。 */
    private long storedItemAmount(final Item item) {
        return itemStorage.countOf(item);
    }

    /** 诊断：内部流体存储里该流体的现有总量（mB）。 */
    private long storedFluidAmount(final Fluid fluid) {
        long have = 0;
        for (final FluidStack stack : outputTank.getTanksSnapshot()) {
            if (stack.getFluid() == fluid) {
                have += stack.getAmount();
            }
        }
        return have;
    }

    /**
     * <b>只读</b>：把「网络里本仓产线相关物品的数量下降」记成一条 {@code net_down} 日志（用户第 5 条）。
     *
     * <h2>为什么要它 / 怎么区分「系统往返」与「用户手动拿走」</h2>
     * <p>玩家手动从终端拿走一件是<b>外部事件</b>，本仓无从「知道」它发生过；它在日志里唯一的表现就是
     * 「网络里该件少了」。因此这里逐轮比对上一次观察到的网络存量，只在<b>真的下降</b>时输出一条：</p>
     * <pre>[rscc-assembly] chamber@(x,y,z) net_down {item=create:cogwheel} delta=-1 net=58-&gt;57
     *     src=external_or_pull (同 tick 有 take_to_chamber = 本仓拉料；没有 = 玩家手动拿走)</pre>
     * <ul>
     *     <li><b>用户手动拿走</b> ⇒ 该行<b>同一 tick 没有</b> {@code take_to_chamber}（本仓拉料）行。
     *     玩家拿一件就出现一条 {@code net_down}，与他标的「齿轮-1 / 齿轮-2」一一对应；</li>
     *     <li><b>系统往返</b> ⇒ 同一件会在 5 秒内出现<b>成对</b>的
     *     {@code take_to_chamber}（本仓拉料，net 下降）与 {@code insert_network} / importer 的
     *     {@code took ... result=MOVED}（收回，net 上升）。因此「只有 net_down、没有 take_to_chamber」
     *     = 外部拿走；「net_down 紧跟 net 回升且有 take_to_chamber」= 系统往返。</li>
     * </ul>
     * <p>只读；只在数量下降时输出（稳态零输出，不刷屏）。</p>
     */
    private void logPipelineNetDrops(final StorageNetworkComponent storage) {
        final Set<Item> pipeline = pipelineItemsCache;
        if (pipeline == null || pipeline.isEmpty()) {
            lastNetAmount.clear();
            logPipelineNetFluidDrops(storage);
            return;
        }
        for (final Item item : pipeline) {
            final long now = storage.get(new ItemResource(item));
            final Long before = lastNetAmount.put(item, now);
            if (before == null || now >= before) {
                continue;
            }
            if (!RsccAssemblyDebug.isEnabled()) {
                continue;
            }
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " net_down {item=" + RsccAssemblyDebug.itemId(item) + "} delta=-" + (before - now)
                + " net=" + before + "->" + now
                + " src=external_or_pull (同 tick 有 take_to_chamber = 本仓拉料；没有 = 玩家手动拿走)");
        }
        logPipelineNetFluidDrops(storage);
    }

    /**
     * <b>只读</b>：流体侧的 {@code net_down}（与物品侧逐字段对称，本轮新增，用户第 1 条）。
     *
     * <p>「岩浆有的时候消耗、有的时候不消耗」这种观感之所以无法定位，正是因为流体侧此前<b>没有</b>
     * 物品侧那样的「网络数量下降」证据。现在每轮比对本仓「流体输入」类别里每种流体的网络存量，
     * 只在<b>真的下降</b>时输出一条，并标明「同 tick 有 pull fluid = 本仓抽取；没有 = 外部（玩家 /
     * 别的设备）拿走的」，于是「这一截岩浆是谁拿走的」可以被逐笔对上。只读，不参与任何判定。</p>
     */
    private void logPipelineNetFluidDrops(final StorageNetworkComponent storage) {
        final Set<Fluid> fluids = inputCategoryFluids();
        if (fluids.isEmpty()) {
            lastNetFluidAmount.clear();
            return;
        }
        for (final Fluid fluid : fluids) {
            final long now = storage.get(new FluidResource(fluid));
            final Long before = lastNetFluidAmount.put(fluid, now);
            if (before == null || now >= before) {
                continue;
            }
            if (!RsccAssemblyDebug.isEnabled()) {
                continue;
            }
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " net_down {fluid=" + RsccAssemblyDebug.fluidId(fluid) + "} delta=-" + (before - now)
                + " net=" + before + "->" + now
                + " src=external_or_pull (同 tick 有 pull fluid = 本仓抽取；没有 = 外部拿走)");
        }
    }

    /**
     * 策略 B（TARGET）专用：对每个「网络存量 < 目标量」的输入原料，按缺口向 RS 自动合成发起请求。
     * <ul>
     *     <li>只对<b>已有输出样板</b>的资源请求（{@code getPatternsByOutput} 非空），避免空转刷屏；</li>
     *     <li>{@code ensureTask} 必须携带非空取消令牌，否则 RS 内部会 NPE（同高级定量保持器）；</li>
     *     <li>每 {@value #BUS_AUTOCRAFT_COOLDOWN_TICKS} tick 才评估一次，不产生包 / CPU 风暴；
     *     已有任务在跑时 {@code ensureTask} 返回 {@code TASK_ALREADY_RUNNING}，天然幂等。</li>
     * </ul>
     * <p>绝不销毁任何资源：本方法只读网络存量 + 发起合成请求，不抽取、不写入。</p>
     */
    private void requestMissingViaAutocraft(final Network network,
                                            final Map<Item, Long> itemTargets,
                                            final Map<Fluid, Long> fluidTargets) {
        if (network == null) {
            return;
        }
        if (--busAutocraftCooldown > 0) {
            return;
        }
        busAutocraftCooldown = BUS_AUTOCRAFT_COOLDOWN_TICKS;
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (autocrafting == null || storage == null) {
            return;
        }
        // <b>用户第 6 条：按需补发、达标即停</b>。
        //   * 确定性配方（results 池 100% 回流主产物，例如坚固板）→ <b>一次只发「当前这一件所需」的一份</b>：
        //     请求 1 份原料，等它进入网络 / 被加工、下一轮冷却到了重新核对缺口 —— 达标（deficit ≤ 0）
        //     自然就不再发；这正是用户原话「先发一份，然后等它进入网络、完成之后查看数量是否达标，
        //     达标了就不再发送」。
        //   * 概率性配方（results 池里还有概率废料，例如精密构件）→ <b>先按订单量整批发</b>一批，
        //     这一批全部走完之后再核对缺口、不够再补发（用户原话「先发 N 份（下单 64 就发 64），
        //     这一堆全部过完之后发现还不够，再继续发」）。
        // 现状（本轮修正前）恒用 {@value #BUS_AUTOCRAFT_BATCH}：确定性配方也会一次预请求 64 份原料 ——
        // 这正是用户第 5 条「开始装配后先把料疯狂拉出去」的来源之一。
        // <b>本轮修正（用户第 ⑤ 条：无条件禁止「数量快速增减」）</b>：旧实现在「读不到剩余件数」时
        // 退回整批量 {@value #BUS_AUTOCRAFT_BATCH}（64）—— 而「读不到剩余件数」恰恰是短命任务的常态，
        // 于是概率性产线会在订单很短时也先按 64 份向 RS 要货（整批拉出 → 任务结束时整批退回，
        // 网络存量剧烈跳动）。现在一律经 {@link #allowedConcurrentUnits()}：读不到剩余件数时取
        // 「本仓当下真在制 / 待补的件数」这一保守下界（至少 1），因此请求量恒 ≤ 真实并发需求，
        // 网络存量曲线平滑（每件成品一个单峰），<b>不出现整批拉出 / 整批退回</b>。
        // <b>新增第三档「按机器台数发」（默认关 ⇒ 与变更前逐字一致）</b>：
        // 请求量 = 本仓所在链此刻真正在喂的<b>机器台数</b>（每台各一份），随后再夹一次
        // 「本仓此刻还允许在制的件数」（{@link #machineSupplyBatch()} 里的 {@link #allowedConcurrentUnits()}）。
        // 于是「有几台机子就发几份」让每台机器都能同时开工，又绝不会超过订单还需要开的件数 ——
        // 真正的开工闸门 {@link #startCapacityForRecipe(String)} 一字未改。
        // 未启用该档时 {@code machineRefillBatch = 0}，下面那条旧口径与两个调用点逐字不变。
        final boolean machineRefill = RsccRefillPolicy.machineRefill(getLevel());
        final long machineRefillBatch = machineRefill ? machineSupplyBatch() : 0L;
        final long requestBatch = pipelineUsesChance()
            ? (machineRefill ? machineRefillBatch
                : Math.max(1L, Math.min(BUS_AUTOCRAFT_BATCH, allowedConcurrentUnits())))
            : (machineRefill ? machineRefillBatch : 1L);
        // <b>用户第 1 / 2 条（本轮）：按缺口补发的开关（默认关 ⇒ 与旧行为逐字一致）</b>。
        // 开关打开且本仓产线全是确定性配方时，下面两个循环会<b>追加</b>一次「确保至少有 deficit」的请求
        // （确定性配方成品率 100%，按缺口要足不会烧料）；概率性配方一律不追加（仍只用上面那条分批递进），
        // 因此绝不会因为「成品率 < 100%」被按缺口要足而烧料。两条路径都靠 {@code ensureTask} 的
        // 「至少 N」幂等语义收敛：缺口 ≤ 0 时一条请求都不再发，存在缺口时每
        // {@value #BUS_AUTOCRAFT_COOLDOWN_TICKS} tick 继续补一批，直到补齐为止。
        final boolean gapRefill = RsccRefillPolicy.gapRefill(getLevel());
        // 与上面同一个判据（并发调用两次：一次给「旧口径的分批量」，一次给「按缺口补发是否适用」）。
        // 本方法每 {@value #BUS_AUTOCRAFT_COOLDOWN_TICKS} tick 才真正执行一次，故开销可忽略。
        final boolean deterministicPipeline = !pipelineUsesChance();
        // <b>只读诊断（用户原话：「我不确定它有没有正常发挥」）</b>：把「这一轮走哪一档、读到几台机器、
        // 本轮实际请求量」三件事并排打一行，且<b>只在状态翻转时各打一条</b>（见
        // {@link RsccAssemblyDebug#transition}）—— 稳态零噪声，但换档 / 台数变化 / 请求量变化立刻可证。
        if (RsccAssemblyDebug.isEnabled()) {
            final String refillModeName = machineRefill ? "machines" : (gapRefill ? "gap" : "off");
            RsccAssemblyDebug.transition("refill@" + RsccAssemblyDebug.at(worldPosition) + "#" + refillModeName,
                "mode=" + refillModeName + ";machines=" + machineRefillBatch + ";request=" + requestBatch,
                RsccAssemblyDebug.machine("chamber", worldPosition) + " refill_batch mode=" + refillModeName
                    + " machines=" + machineRefillBatch + " request=" + requestBatch
                    + " deterministic=" + deterministicPipeline);
        }
        for (final Map.Entry<Item, Long> entry : itemTargets.entrySet()) {
            // <b>「输入原料组」只补一次、且任一候选已可用就补（用户第 1 条）</b>：
            // 标签型原料（石头台阶 / 平滑石台阶 / 安山岩台阶）是<b>一个</b>原料 ——
            // 非代表物不重复请求（否则会为 3 个候选各开一个合成任务，把订单量放大 3 倍），
            // 可互换兄弟已有货时也不请求（整组已满足）。
            if (isInterchangeableFollower(entry.getKey()) || interchangeableAvailable(entry.getKey())) {
                continue;
            }
            final ResourceKey resource = new ItemResource(entry.getKey());
            // <b>缺口 = 目标量 −（网络存量 + 本仓内部存量）</b>（本轮修正，用户第 ④⑤ 条）：
            // 只减网络存量会把「本仓里已经备着的那一份」当成缺口 ⇒ 每 40 tick 多请求一份（数量快速增减）。
            // 加上本仓内部存量后，「仓里已经有一份 ⇒ 不请求」自然成立，缺料（网络与本仓都没有）才请求。
            final long have = storage.get(resource) + storedItemAmount(entry.getKey());
            // <b>用户第 1 条（本轮）：缺口口径再扣「机器侧压着的那一份」与「RS 已在合成的在途量」</b> ——
            // 两者都是「已经存在、只是不在网络里」的量，扣掉后请求量只会更小，符合「宁可少不可多」。
            final long deficit = entry.getValue() - have
                - machineHeldItem(entry.getKey()) - autocraftInFlight(resource);
            if (deficit > 0 && !autocrafting.getPatternsByOutput(resource).isEmpty()) {
                // 每次只请求「一批」缺口（{@value #BUS_AUTOCRAFT_BATCH}）：用户第 ③ 条要求不要按任务
                // 总需求量一次性预抽；分多次小批请求与一次性大请求在总量上等价（下一次冷却到了继续补），
                // 差别只在于网络存量曲线是平滑的。ensureTask 语义是「确保至少有这么多」，天然幂等。
                autocrafting.ensureTask(resource, Math.min(deficit, requestBatch),
                    Actor.EMPTY, new TimeoutableCancellationToken());
                // 用户第 1 条（opt-in）：确定性配方按缺口一次要足（deficit 已夹紧 ⇒ 绝不多要）。
                if (gapRefill && deterministicPipeline) {
                    autocrafting.ensureTask(resource, deficit,
                        Actor.EMPTY, new TimeoutableCancellationToken());
                }
            }
        }
        for (final Map.Entry<Fluid, Long> entry : fluidTargets.entrySet()) {
            final ResourceKey resource = new FluidResource(entry.getKey());
            // 流体同物品侧：可用量 = 网络 + 本仓罐内 + <b>机器侧压着的那一份</b>（口径与缺料上报一致），
            // 因此「注液机罐里还压着 500 mB」时不会再请求一份新的（数量快速增减的另一条路径）。
            final long have = storage.get(resource) + storedFluidAmount(entry.getKey())
                + machineHeldFluid(entry.getKey());
            final long deficit = entry.getValue() - have;
            if (deficit > 0 && !autocrafting.getPatternsByOutput(resource).isEmpty()) {
                autocrafting.ensureTask(resource, Math.min(deficit, requestBatch),
                    Actor.EMPTY, new TimeoutableCancellationToken());
                // 用户第 1 条（opt-in）：确定性配方按缺口一次要足（口径与物品侧完全一致）。
                if (gapRefill && deterministicPipeline) {
                    autocrafting.ensureTask(resource, deficit,
                        Actor.EMPTY, new TimeoutableCancellationToken());
                }
            }
        }
    }

    /**
     * <b>只读</b>：新增第三档「按机器台数发」的请求量 —— <b>本仓此刻喂着几台机子就往 RS 要几份</b>
     * （每台各一份 ⇒ 有几台就能同时开工几件）。
     *
     * <h2>「机器台数」的口径 = {@link #supplyStations(Level)} 的台数（为什么）</h2>
     * <p>本仓只有这一个「机器台数」的真源：{@link #supplyStations(Level)} 把相连输出总线朝向的
     * <b>供料目标</b>按 {@link #stationKey(Level, BlockPos)}（<b>物理工位</b>）归并。选它有三个理由：</p>
     * <ol>
     *     <li>它把「置物台 + 对着它的机械手」算成<b>一台</b>：{@code pendingStepOn} 对两者的结论完全一样，
     *     若不归并就会把同一台机器数成两台 ⇒ 多发一份（这正是备料侧修过的那个坑，
     *     见 {@link #wantingTargetCount(Item)}）；</li>
     *     <li>它同时是备料侧「每种原料 × 每台要它的机器各一份」用的<b>同一份事实源</b>，
     *     因此「按台数发」与「每台各一份」天然同口径，不会一个说 3 台、另一个说 2 台；</li>
     *     <li>它只在<b>总线输出</b>模式下非空，而本方法唯一的调用链（{@code fillInternalForBus} →
     *     {@code requestMissingViaAutocraft}）本身也只在总线输出模式下执行（见引擎里的
     *     {@code if (outputMode == OutputMode.BUS)}），因此这里不存在「另一种输出模式读不到台数」的空窗；</li>
     *     <li><b>必须是链级口径</b>：{@code ensureTask} 的语义是「整个网络里<b>至少</b>有这么多正在合成」
     *     （{@code AutocraftingNetworkComponentImpl#ensureTask} 先减去网络级的 {@code currentlyCrafting}
     *     再补差额），因此「发多少份」是一个<b>网络级</b>的量；用「只算本仓物理认领的总线」的口径会随
     *     链上仓数被重复请求、反而数不准。{@link #supplyStations(Level)} 正是链级口径，与
     *     {@code remainingOrderUnits()} / {@code exportShare} 的链级份额同源。</li>
     * </ol>
     *
     * <h2>为什么必须再夹一次在制名额（不许让新模式顶破名额）</h2>
     * <p>用户要的是「有多少台机子就发多少个」，但若台数比订单还需要开的件数还大
     * （例如下单 1 件、却挂着 3 台机器），照台数发就会为一件订单要来 3 份原料 —— 那正是用户要避免的
     * 「过料」。因此这里再夹一次 {@link #allowedConcurrentUnits()}（= 订单剩余件数；读不到时取
     * 「本仓当下真在制 / 待补的件数」这一保守下界，至少 1），与 {@link #exportShare(int)} /
     * {@link #wantingTargetCount(Item)} 用的是同一个收敛值。</p>
     * <p>于是在制名额的上界<b>只减不增</b>：请求量 ≤ 订单剩余件数 ≤ 非必得配方的 {@code ceil(R/p)}
     * （见 {@link #startCapacityForRecipe(String)}），而真正的开工闸门根本不看请求量、只看名额，
     * 因此「按台数发」不可能让开件数超过名额上限。</p>
     *
     * <p><b>判不出来（未进入世界 / 客户端 / 没有任何供料目标）⇒ 1</b>：与旧口径里确定性配方的
     * 「一次一份」逐字一致，绝不放量（不确定时取小不取大）。只读，绝不搬运 / 销毁任何资源。</p>
     */
    private long machineSupplyBatch() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return 1L;
        }
        final int machines = supplyStations(level).size();
        if (machines <= 0) {
            return 1L;
        }
        return Math.max(1L, Math.min((long) machines, Math.max(1L, allowedConcurrentUnits())));
    }

    /**
     * <b>只读</b>：本仓产线里是否存在<b>带概率的序列装配配方</b>（Create 的 {@code results} 池除主产物外
     * 还有概率项，即 {@link SequencedRecipeProbe#splitResultPool} 的 {@code scraps} 非空）。
     *
     * <h2>为什么必须区分「确定性 / 概率性」（用户第 6 条给的算法）</h2>
     * <p>确定性配方（坚固板的结果池只有它自己，100% 回流主产物）：一次只该发「当前这一件所需」的一份，
     * 加工完回网后重新核对是否达标；概率性配方（精密构件的结果池里还有齿轮等概率项）：同一步骤多次产出
     * 未必都是主产物，因此先把订单量一批发下去、整批走完再核对缺口补发 —— 否则会明显掉产能。</p>
     * <p>判据取「任一在本仓产线里的配方的结果池含概率项」。判不出来 / 没有配方时返回 {@code false}
     * （= 按确定性处理，一次一份 ⇒ 走「不过量」这一侧，与用户「尽可能避免出现发多原料」同向）。</p>
     */
    private boolean pipelineUsesChance() {
        final Level level = getLevel();
        if (level == null) {
            return false;
        }
        final List<UnitData> units = unitsForExport();
        if (units.isEmpty()) {
            return false;
        }
        final Set<ResourceLocation> chamberRecipeIds = new LinkedHashSet<>();
        for (final UnitData unit : units) {
            final ResourceLocation id = ResourceLocation.tryParse(unit.recipe() == null ? "" : unit.recipe());
            if (id != null) {
                chamberRecipeIds.add(id);
            }
        }
        for (final UnitData unit : units) {
            final SequencedAssemblyRecipe recipe = resolveUnitRecipe(level, unit, chamberRecipeIds);
            if (recipe == null) {
                continue;
            }
            if (!SequencedRecipeProbe.splitResultPool(recipe.resultPool).scraps().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：RS 自动合成里「正在合成」的该资源总量（在途量）。
     *
     * <p>用户第 1 条要求「缺口 = 目标 −（网络 + 本仓 + 机器侧 + <b>在制 / 在途</b>）」：在途 = RS 已经为它
     * 开好任务、但还没交付的那部分。把它从缺口里扣掉是<b>保守</b>方向（只会让请求更少，绝不多报）；
     * 读不到任务状态（没挂网络 / 没有自动合成组件 / 状态瞬时为空）时返回 0 —— 此时缺口退化成
     * 「网络 + 本仓 + 机器侧」这一确定下界，仍由 {@code ensureTask} 的「至少 N」幂等语义收敛。</p>
     * <p>只读，绝不改任何任务 / 库存。</p>
     */
    private long autocraftInFlight(final ResourceKey resource) {
        if (resource == null) {
            return 0L;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return 0L;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return 0L;
        }
        long inFlight = 0L;
        for (final TaskStatus status : autocrafting.getStatuses()) {
            if (status.items() == null) {
                continue;
            }
            for (final TaskStatus.Item item : status.items()) {
                if (resource.equals(item.resource())) {
                    inFlight += Math.max(0L, item.crafting());
                }
            }
        }
        return inFlight;
    }

    /**
     * <b>只读</b>：该物品此刻能不能被 RS 自动合成（终端里有配方能产出它）。
     *
     * <p>用户第 3 条：<b>只要某项资源可以被自动合成（或已有该资源的合成任务在跑），就不再报「缺少材料」</b>
     * —— 一直静默等待它到位即可。判据取「有以它为输出的样板」，读不到自动合成组件时返回 {@code false}
     * （= 判不出来 ⇒ 照旧报缺，绝不因为判不出来而漏报真实缺料）。只读，绝不搬运 / 销毁资源。</p>
     */
    private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting, final Item item) {
        return autocrafting != null && item != null
            && !autocrafting.getPatternsByOutput(new ItemResource(item)).isEmpty();
    }

    /** 流体版 {@link #isAutoCraftable(AutocraftingNetworkComponent, Item)}（口径完全对称）。 */
    private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting, final Fluid fluid) {
        return autocrafting != null && fluid != null
            && !autocrafting.getPatternsByOutput(new FluidResource(fluid)).isEmpty();
    }

    /**
     * 把网络上的一种物品资源抽进内部物品存储（不超目标量、不超剩余容量；装不下的退回网络）。
     *
     * @return {@code true} = 这一轮<b>真的从网络抽进了一件或更多</b>（供 R2「替代供料」按事实登记，
     *     见 {@link #reportHandoffUsage()}）；任何一条闸门拦下 / 抽不到都返回 {@code false}
     */
    private boolean pullItem(final StorageNetworkComponent storage, final ItemResource resource,
                             final long available, final long target,
                             final Map<String, Integer> disallowThresholds) {
        final Level level = getLevel();
        final long now = level == null ? 0L : level.getGameTime();
        // <b>R2：这一趟抽的是「替补件」吗</b>（最优件一点都取不到、而这个兄弟确有货）——
        // 是的话下面那道「整组只备一种」（{@link #interchangeableAvailable}）必须让路：
        // 那一道的本意是「整组已经满足了就别再抽第二种」，而替补档的前提恰恰是<b>整组还没满足</b>
        // （最优件一件都没有）；此时再让兄弟把路堵上，机器就会一直空着 —— 正是用户抱怨的
        // 「候选不齐就断供」。其余所有闸门（按步 / 总线归属 / 每步所需量 / 容量）一字未动。
        final boolean substitutePass = substituteAppliesForPull(resource.item());
        // 抑制（用户实测的 1 Hz 空转）：本仓刚刚「按步拒绝」过的资源，短冷却内不再重新判定、也不再抱怨。
        // <b>只压「拒绝」这一种结论</b>，因此正常流（本仓真正需要的件）永远不受影响；
        // 玩家改了机器绑定 / 补了样板后，最多 {@value #STEP_REFUSAL_COOLDOWN_TICKS} tick 就自愈。
        final Long coolUntil = stepRefusalCooldowns.get(resource);
        if (coolUntil != null && now < coolUntil) {
            // 追踪：这条分支原先<b>完全静默</b>（防刷屏），于是「网络里有货、本仓却没动」
            // 时日志里一条都看不到。本轮改走<b>状态翻转</b>通道（见 #tracePullHold）：
            // 「抑制冷却中」是设计内的稳态，按 tick 重复打印就是用户点名要修掉的「持续性无效重复」。
            tracePullHold(resource, available, "refusal_cooldown",
                "refusal_cooldown_left=" + (coolUntil - now) + "tick", available);
            return false;
        }
        // 防中间产物错误回流：被「禁止回流步骤」拦下的未完成物品本机一律不拉进内部存储
        //（留在网络里由玩家 / 其它设备处理，绝不销毁）
        if (isInputBlockedResource(resource, disallowThresholds)) {
            if (RsccAssemblyDebug.isEnabled()) {
                final ItemStack probeStack = resource.toItemStack(1);
                final SequencedAssembly assembly = probeStack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
                RsccAssemblyDebug.reject("blockedbus@" + RsccAssemblyDebug.at(worldPosition)
                        + "#item:" + RsccAssemblyDebug.itemId(resource.item()),
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " reject {item=" + RsccAssemblyDebug.itemId(resource.item()) + "}"
                        + " reason=disallow_inputting_by_step"
                        + " step=" + (assembly == null ? "-" : assembly.step()));
            }
            tracePull(resource, available, "pull_rejected", "disallow_inputting_by_step", available);
            suppressStepRefusal(resource, now);
            return false;
        }
        // 「按步骤」过滤（用户实测「网络⇄机器空转」的根因）：过渡件只有在「下一步正轮到本仓某台机器」
        // 时才允许被吸进本仓 —— 否则吸进来也只会被输出总线推给一台对它无事可做的机器，
        // 再被输入总线原样收回网络，形成「推出 → 收回 → 再推出」的死循环（每圈都要过一次机器）。
        // 非过渡件（原料 / 成品 / 废料）不受影响，保持既有行为。
        final ItemStack probeStack = resource.toItemStack(1);
        if (!isNextForMyMachines(probeStack)) {
            final SequencedAssembly assembly = probeStack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            final int nextStep = nextStepOf(level, assembly);
            // 理由分档（用户要能一眼看懂「为什么这份件被收回网络」）：
            //   step_not_mine        = 别的在线执行仓显式认领了这一步 → 交给它（正常换机器）；
            //   no_machine_owns_step = 同类型没有任何在线仓能承担这一步 → 需要玩家干预（显式 WARN）。
            final String owner = nextStep < 0 ? "-" : stepOwnerDetail(level, assembly, nextStep);
            final String reason = "NOBODY".equals(owner) ? "no_machine_owns_step" : "step_not_mine";
            final boolean nobody = "NOBODY".equals(owner);
            if (nobody) {
                // <b>用户第 2/3 条：这条「没机器认领」必须让看门狗看见，从而弹出明确提示</b>
                //（实测它每 5 秒 8 次却全程没有任何横幅 ⇒ 玩家「下单毫无反应却没有任何弹窗」）。
                //
                // 2026-10-06：这两句是**玩家可见的功能**（横幅 + 停滞判定），不是日志 ——
                // 因此必须留在 devLogs 开关之外。旧实现把它们与日志一起包在 isEnabled() 里，
                // 一旦把开发日志默认关掉，「没机器认领」的横幅就会跟着消失（行为回归）。
                stepOwnerMissingAt = level.getGameTime();
                // 2026-10-05：把「原因」也记下来 —— 它与「下游机器满」是两件完全不同的事，
                // 旧实现只有一个布尔值，于是两种原因在横幅上都显示成「执行器掉线」。
                noteStall(StallReason.STEP_OWNER_MISSING,
                    RsccAssemblyDebug.itemId(resource.item()));
            }
            final boolean devLogs = RsccAssemblyDebug.isEnabled();
            if (nobody || devLogs) {
                final String body = RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " reject {item=" + RsccAssemblyDebug.itemId(resource.item())
                    + " step=" + (assembly == null ? "-" : assembly.step())
                    + " next=" + (nextStep < 0 ? "-" : nextStep) + "}"
                    + " reason=" + reason
                    + " stepOwner=" + owner;
                if (devLogs) {
                    // 同因合并：首次立即打一条，之后每 5 秒汇总一次（不再每秒刷屏），保留可诊断性。
                    RsccAssemblyDebug.reject("notmine@" + RsccAssemblyDebug.at(worldPosition)
                        + "#item:" + RsccAssemblyDebug.itemId(resource.item()), body);
                    tracePull(resource, available, "pull_rejected",
                        reason + " step=" + (assembly == null ? "-" : assembly.step())
                            + " next=" + (nextStep < 0 ? "-" : nextStep) + " stepOwner=" + owner, available);
                }
                if (nobody) {
                    // 「下一步没有任何机器认领」是真正需要玩家干预的情形 → WARN。
                    // 必要日志：<b>不受 devLogs 开关控制</b>（warn() 自己按「仓 + 件」只报一次，不刷屏）。
                    RsccAssemblyDebug.warn("unowned@" + RsccAssemblyDebug.at(worldPosition)
                            + "#item:" + RsccAssemblyDebug.itemId(resource.item()),
                        body + " (该步没有任何在线执行仓认领；这份件只能留在网络，等玩家补机器 / 补样板)");
                }
            }
            suppressStepRefusal(resource, now);
            return false;
        }
        // 「本仓当前待加工步已经不要它」的步骤专用投入物不备料（用户实测断点，与收回侧同一个判据）：
        // 齿轮既是精密构件第 0 步的投入物、又在同一条配方的 results 池里（= 废料）。置物台上的在制件
        // 已经走到「要大齿轮」那一步时，机械手手里那件齿轮就是压死产线的废料：推不进去
        // （Create 的 DeployerItemHandler 对「手里已有不同物品」一律拒收），旧版又把它当输入类保护
        // → 用户只能手动把齿轮抠出来。因此收回侧放开「当前步不要的那一份」
        // （见 RsccChamberImportStrategy#autoAcceptsItem 的例外 ①），这里必须同步不放行备料：
        // 否则「刚被收回网络 → 下一秒又被备回来」会退化成每秒一次的来回搬运。
        // 判据与推料侧同一个 inputMaterialWantedNow(Item)：判不出来时返回 true（放行），绝不因判不出来而断供。
        if (!inputMaterialWantedNow(resource.item())) {
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.reject("unwantedstep@" + RsccAssemblyDebug.at(worldPosition)
                        + "#item:" + RsccAssemblyDebug.itemId(resource.item()),
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " reject {item=" + RsccAssemblyDebug.itemId(resource.item()) + "}"
                        + " reason=not_wanted_this_step (本仓当前待加工步不要它：不备料，让收回侧把它带回网络)");
            }
            tracePull(resource, available, "pull_rejected", "not_wanted_this_step", available);
            suppressStepRefusal(resource, now);
            return false;
        }
        // 「拉进来必须推得出去」（防止把件从网络吸进仓里却没有任何总线能把它推给机器）：
        // 必须有一条相连输出总线选了「能匹配这份资源」的类别。非过渡件的类别不带步序，
        // 只要类别被选就匹配 —— 与既有行为逐字一致；过渡件则按步匹配（见 matchesCategoryStep）。
        //
        // <b>2026-10-05 修「98 个中间产物堆在网络里排不出去 + 日志刷屏」。</b>
        //
        // 实测（构建 22:06:26）：
        // <pre>
        // chamber@(-10,-60,10) reject {item=create:unprocessed_obsidian_sheet step=1 next=1}
        //     reason=step_not_mine stepOwner=ELSEWHERE          ← 注液仓（第 0 步）在拒绝第 1 步的件
        // item=create:unprocessed_obsidian_sheet x98 ... pull_rejected  net=98   ← 网络上积压 98 个
        // chamber@(-16,-60,10) pull {item=create:unprocessed_obsidian_sheet x1} target=1   ← 冲压仓一次只拉 1 个
        // </pre>
        //
        // 两个问题：
        // <ol>
        //     <li><b>注液仓根本不该尝试拉「归别的仓的第 1 步件」</b>。它是第 0 步的仓，
        //     而这份件已经走完第 0 步、下一步归冲压仓。旧判据 {@code isNextForMyMachines} 对
        //     <b>不带进度组件的件</b>（坚固板/列车轨道的中间产物都没有组件）<b>一律放行</b>，
        //     于是注液仓每 tick 都尝试一次、每 tick 被拒一次 → 日志刷屏 + 无用功。</li>
        //     <li>真正的接收方（冲压仓）受「在制件 / 机器占用」限制，一次只拉得动 1 个 ——
        //     它是对的（Create 一台机器一次只有一件），但积压要靠它自己慢慢消化，
        //     不该被注液仓的无效尝试干扰。</li>
        // </ol>
        //
        // 修法：<b>当这份件的下一步归属已明确落在别的仓（{@code STEP_OWNER = ELSEWHERE}）时，
        // 本仓直接不参与</b> —— 既不放行也不报错，安静跳过。判据与拒绝路径共用同一个
        // {@code stepOwnerDetail}（唯一实现），因此「谁该拉」只有一个答案。
        //
        // <b>2026-10-05 二次修正：这道检查必须对「无进度组件的件」也生效。</b>
        //
        // 上一版把它写在 {@code if (!isNextForMyMachines(probeStack))} <b>里面</b> ——
        // 而 {@code isNextForMyMachines} 对<b>不带 SEQUENCED_ASSEMBLY 组件的件一律返回 true</b>
        // （原料 / 成品，以及坚固板与列车轨道那种「无组件的中间产物」），
        // 于是这道检查对<b>恰恰最需要它的那一类</b>根本不执行。
        //
        // 实测后果（快照 20261005-222155，注液仓）：
        // <pre>
        //   materials: create:unprocessed_obsidian_sheet target=1 net=0 chamber=1 machine=0
        //   计数器：  (-10,-60,10)|unprocessed_obsidian_sheet|pull=15 |return=14
        //   两个置物台： OutputBuffer 全空（HeldItem 已不在）
        // </pre>
        // 也就是说：注液仓把<b>它自己刚产出的</b>未加工片从置物台<b>拉进了自己怀里</b>，
        // 然后既推不出去（置物台是它自己的机器、推回去等于空转）也不放手 ——
        // 那件永远走不到第 1 步（冲压），坚固板一件都产不出来。
        //
        // 现在把检查提到外面，并且<b>不再依赖 {@code isNextForMyMachines}</b> ——
        // 那条判据对无组件的件恒为 true，正是漏洞本身。改为只看「下一步归谁」这一个事实：
        //   * {@code stepOwnerDetail} 返回别的仓 ⇒ 本仓不拉（留给它 / 网络）；
        //   * 返回本仓 / 判不出来（{@code "-"}）⇒ 照旧继续走后面的闸门（绝不因判不出来而断供）。
        final SequencedAssembly probeAssembly = probeStack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        final int probeNextStep = nextStepOf(level, probeAssembly);
        if (probeNextStep >= 0) {
            final String probeOwner = stepOwnerDetail(level, probeAssembly, probeNextStep);
            if ("ELSEWHERE".equals(probeOwner)) {
                return false; // 下一步归别的仓：本仓既不拉也不报（安静跳过）
            }
        }
        if (!anyBusOwnsResource(probeStack, resource)) {
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.reject("nobus@" + RsccAssemblyDebug.at(worldPosition)
                        + "#item:" + RsccAssemblyDebug.itemId(resource.item()),
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " reject {item=" + RsccAssemblyDebug.itemId(resource.item()) + "}"
                        + " reason=no_bus_selected_this_step (没有输出总线选它这一步的类别，留在网络)");
            }
            tracePull(resource, available, "pull_rejected", "no_bus_selected_this_step", available);
            suppressStepRefusal(resource, now);
            return false;
        }
        // <b>「整组只备一种」（用户第 1 条：标签型输入原料）</b>：同一 ingredient 的其它候选
        // （与该件「可互换」的那些）已经在本仓 / 机器侧 / 网络里时，<b>不再</b>抽这一种 ——
        // 一个 ingredient 的 3 个候选是「一个原料」，不是 3 份需求；否则仓里会同时囤
        // 石头台阶 + 平滑石台阶 + 安山岩台阶（多备两份、白占两格、任务结束再退回）。
        // 判据见 {@link #interchangeableAvailable}：只把「这一刻确实任一件都能用」的兄弟算进来，
        // 因此不会因为「精密构件要铁粒、而仓里躺着列车轨道用的锌粒」而把铁粒饿死。
        if (!substitutePass && interchangeableAvailable(resource.item())) {
            tracePullHold(resource, available, "candidate_sibling_present",
                "candidate_sibling_present target=" + target, available);
            return false;
        }
        final int deficit = busItemDeficit(resource.item(), target);
        if (deficit <= 0) {
            final String why = itemStorage.getRemainingCapacity() <= 0 ? "storage_full" : "already_enough";
            if (RsccAssemblyDebug.isEnabled() && itemStorage.getRemainingCapacity() <= 0) {
                RsccAssemblyDebug.reason("fullbus@" + RsccAssemblyDebug.at(worldPosition) + "#item",
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " pull {item=" + RsccAssemblyDebug.itemId(resource.item()) + "}"
                        + " target=" + target + " got=0 reason=storage_full");
            }
            tracePullHold(resource, available, why, why + " target=" + target, available);
            return false;
        }
        final long extracted = storage.extract(resource, Math.min(deficit, available),
            Action.EXECUTE, Actor.EMPTY);
        if (extracted <= 0) {
            // 追踪：网络「说有货」却一件都抽不出来（与别的任务争抢 / 被别的东西先取走）—— 原先静默
            tracePullHold(resource, available, "extract_zero", "extract_zero", available);
            return false;
        }
        // 守恒账本：本仓「离开网络」的那一份（物品；记账在插入内部存储之前，因此首次记账的基线不含它）
        flowLedger.fromNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
            .itemKey(resource.item()), extracted);
        final ItemStack leftover = itemStorage.insertItem(0, resource.toItemStack(extracted), false);
        if (!leftover.isEmpty()) {
            // 理论不可达（deficit 已按剩余容量夹过）：剩余物退回网络，绝不销毁
            storage.insert(ItemResource.ofItemStack(leftover), leftover.getCount(),
                Action.EXECUTE, Actor.EMPTY);
        }
        // 追踪（「移交下一台仓」这一段）：本仓已把这份件从网络取进内部存储，成功 / 退回量都写清；
        // net 取「抽取之后」的真实网络存量，于是「取走了一份」在数字上也能对上。
        tracePull(resource, extracted, "take_to_chamber",
            "ok target=" + target + " returned=" + leftover.getCount()
                + " chamberHave=" + itemStorage.countOf(resource.item()),
            available - extracted + leftover.getCount());
        setChanged();
        if (RsccAssemblyDebug.isEnabled()) {
            // 同因子合并（用户要求：保留 pull 诊断，但别让高频 pull 行成为负担）：
            // 旧写法把 net 放进状态串，而 net 每秒都在变 → 状态必然翻转 → 每次都打。
            // 改走「同因合并」通道：同一「仓 + 件」首次立即一条，之后每 5 秒一条并带重复计数
            // （明细一字不减：extracted / target / net / returned；总量另有 5 秒聚合摘要 summary）。
            RsccAssemblyDebug.repeat(
                "pull@" + RsccAssemblyDebug.at(worldPosition) + "#item:"
                    + RsccAssemblyDebug.itemId(resource.item()),
                RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " pull {item=" + RsccAssemblyDebug.itemId(resource.item()) + " x" + extracted + "}"
                    + " target=" + target
                    + " net=" + available
                    + " returned=" + leftover.getCount()
                    + " reason=ok");
            RsccAssemblyDebug.countPull(extracted);
            RsccAssemblyDebug.countReturn(leftover.getCount());
        }
        return true; // 真的从网络抽进了内部存储（替补供料的登记以此为准，见 reportHandoffUsage）
    }

    /** 把网络上的一种流体资源抽进内部流体存储（不超目标量、不超剩余容量；装不下的退回网络）。 */
    private void pullFluid(final StorageNetworkComponent storage, final FluidResource resource,
                           final long available, final long target) {
        // <b>「一次一份在飞」闸门（本轮新增，与物品侧的「工位上还压着这一件 ⇒ 不再备第二份」
        // （{@link #supplyTargetHoldsItem}）严格同源，只是这里走的是流体）</b>：
        // 目标机器（注液机 / 盆）此刻还压着这类流体时，这一份「在飞」，目标量按它扣掉 ——
        // 于是「推进注液机 → 它还没消耗 → 又抽 500 mB 进执行仓罐」这种<b>抽了不用</b>不会发生，
        // 用户实测的「岩浆一直在流动但没被消耗」正是这条路径（抽 30000 mB / 消耗 21500 mB /
        // 126 次推不动）。判据只读，机器一消耗掉就自愈，绝不因判不出来而断供。
        // <b>与物品侧对称的「拉进来必须推得出去」闸门（本轮新增，用户第 1 条：岩浆有时消耗有时不消耗）</b>：
        // 物品侧早就有 {@link #anyBusOwnsResource}(没有任何输出总线能推它 ⇒ 不拉)；流体侧此前<b>缺失</b>，
        // 于是「没有任何总线选中这一类流体 / 选中的总线其目标机器都已经压着够一批」时，本仓照样把
        // 500 mB 抽进自己的罐里 —— 网络当刻被扣，而它推不出去（没有消费者），直到任务结束时才由
        // {@link #flushResidualInputs} 原样回流。玩家看到的正是「岩浆有的时候消耗、有的时候不消耗」
        // 这种<b>扣了又还</b>的抖动。补上这道对称闸门后：流体只在「确实有总线需要它、且目标机器还没
        // 喝够」时才离开网络，于是岩浆的每一次下降都对应「真的往注液机送了一份」，账目可逐笔对上。
        // 判据只读（总线归属表 + 目标机器已有量），机器一消耗 / 总线一勾上就自愈，绝不因判不出来而断供。
        if (!anyBusNeedsFluid(resource.fluid())) {
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition(
                    "nobusfluid@" + RsccAssemblyDebug.at(worldPosition) + "#"
                        + RsccAssemblyDebug.fluidId(resource.fluid()),
                    "held",
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " pull {fluid=" + RsccAssemblyDebug.fluidId(resource.fluid()) + "} got=0"
                        + " reason=no_bus_needs_fluid (没有输出总线需要它 / 目标机器已压着够一批，留在网络)");
            }
            return;
        }
        final long effectiveTarget = Math.max(0L, target - machineHeldFluid(resource.fluid()));
        final int deficit = busFluidDeficit(resource.fluid(), effectiveTarget);
        if (deficit <= 0) {
            if (RsccAssemblyDebug.isEnabled() && outputTank.getRemainingCapacity() <= 0) {
                RsccAssemblyDebug.reason("fullbus@" + RsccAssemblyDebug.at(worldPosition) + "#fluid",
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " pull {fluid=" + RsccAssemblyDebug.fluidId(resource.fluid()) + "}"
                        + " target=" + target + " got=0 reason=storage_full");
            }
            return;
        }
        final long extracted = storage.extract(resource, Math.min(deficit, available),
            Action.EXECUTE, Actor.EMPTY);
        if (extracted <= 0) {
            return;
        }
        // 守恒账本：本仓「离开网络」的那一份（流体，单位 mB；记账在注入内部罐之前）
        flowLedger.fromNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
            .fluidKey(resource.fluid()), extracted);
        final FluidStack stack = new FluidStack(
            net.minecraft.core.registries.BuiltInRegistries.FLUID.wrapAsHolder(resource.fluid()),
            (int) extracted, resource.components());
        final int filled = outputTank.fill(stack,
            net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        if (filled < extracted) {
            // 理论不可达（deficit 已按剩余容量夹过）：剩余量退回网络，绝不销毁
            storage.insert(new FluidResource(resource.fluid(), resource.components()),
                extracted - filled, Action.EXECUTE, Actor.EMPTY);
        }
        setChanged();
        if (RsccAssemblyDebug.isEnabled()) {
            // 与物品侧同一口径：pull 走「同因合并」通道（首次立即 + 每 5 秒一条带重复计数），
            // 不再把每秒都在变的 net 当状态因子（否则等于没节流），明细一字不减。
            RsccAssemblyDebug.repeat(
                "pull@" + RsccAssemblyDebug.at(worldPosition) + "#fluid:"
                    + RsccAssemblyDebug.fluidId(resource.fluid()),
                RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " pull {fluid=" + RsccAssemblyDebug.fluidId(resource.fluid()) + " x" + extracted + "}"
                    + " target=" + target
                    + " net=" + available
                    + " returned=" + (extracted - filled)
                    + " reason=ok");
            RsccAssemblyDebug.countPullFluid(extracted);
            RsccAssemblyDebug.countReturn(extracted - filled);
        }
    }

    /**
     * 某种物品在「物品存储统一视图」（内部存储 + 磁盘缓存）里还差多少件才够「目标量」
     * （目标量 = 每批所需 × {@value #BUS_TARGET_BATCHES}，已由调用方按安全上限夹紧；
     * 同时受统一视图的剩余容量约束）。已经够 → 返回 0（不再抽取）。
     * <p>磁盘缓存计入「已有量」与「剩余容量」：因此磁盘里的中间产物对备料判定同样算数，
     * 不会出现「磁盘里明明有、却还去网络抽一份」的重复备料。</p>
     *
     * <p><b>本轮再夹一道「每轮抽取批上限」</b>（{@value #BUS_PULL_BATCH_ITEMS} 件）：
     * 用户第 ③ 条要求原料要<b>逐步消耗</b>，不允许出现与加工进度不匹配的一次性大幅跳变
     * （现象：金板被一次性预抽，网络存量瞬间掉一大截）。目标量表达的是「仓里最终该有多少」，
     * 而这里是「这一轮真的从网络抽多少」——把「一步到位」摊成每
     * {@value #ENGINE_INTERVAL_TICKS} tick 一小批。目标量不变，因此<b>总量守恒</b>、
     * 最终仍会补到位，不卡料、吞吐不降（批上限 4 件 / 0.25 秒远高于任何机器的消耗速度）。</p>
     */
    private int busItemDeficit(final Item item, final long target) {
        final long have = itemStorage.countOf(item);
        final long deficit = Math.min(Math.min(target - have, itemStorage.getRemainingCapacity()),
            BUS_PULL_BATCH_ITEMS);
        return deficit <= 0 ? 0 : (int) Math.min(deficit, Integer.MAX_VALUE);
    }

    /**
     * 某种流体在内部存储里还差多少 mB 才够「目标量」（目标量 = 每批所需 × {@value #BUS_TARGET_BATCHES}，
     * 已由调用方按安全上限夹紧；同时受内部存储剩余容量约束）。仓内已够 → 返回 0（不再抽取）。
     * <p>同样夹一道「每轮抽取批上限」（{@value #BUS_PULL_BATCH_FLUID} mB，见
     * {@link #busItemDeficit} 的说明）：让流体存量也平滑下降，而不是一轮见底。</p>
     */
    private int busFluidDeficit(final Fluid fluid, final long target) {
        long have = 0;
        for (final FluidStack stack : outputTank.getTanksSnapshot()) {
            if (stack.getFluid() == fluid) {
                have += stack.getAmount();
            }
        }
        final long deficit = Math.min(Math.min(target - have, outputTank.getRemainingCapacity()),
            BUS_PULL_BATCH_FLUID);
        return deficit <= 0 ? 0 : (int) Math.min(deficit, Integer.MAX_VALUE);
    }

    /**
     * 方块被破坏时的内部存储掉落（供方块调用，绝不吞资源）：
     * 物品直接掉世界；流体优先写回 RS 网络（零损耗），网络不可用 / 装不下时退化为按桶掉落
     * （与执行舱内部存储的掉落约定一致）。总线输出模式下搬运进来的缓冲物料也在其中，因此拆方块不会丢东西。
     */
    public void dropOutputContents(final Level level, final BlockPos pos) {
        if (level.isClientSide()) {
            return; // 掉落是服务端权威的行为，客户端不生成任何东西
        }
        long droppedItems = 0;
        for (int i = 0; i < outputStorage.getSlots(); i++) {
            final ItemStack stack = outputStorage.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            final ItemStack taken = outputStorage.extractItem(i, stack.getCount(), false);
            if (!taken.isEmpty()) {
                Block.popResource(level, pos, taken);
                droppedItems += taken.getCount();
            }
        }
        final StorageNetworkComponent storage = currentStorage();
        long fluidToNetwork = 0;
        long fluidKept = 0;
        for (final FluidStack stack : outputTank.getTanksSnapshot()) {
            long handled = storage == null ? 0L : storage.insert(
                new FluidResource(stack.getFluid(), stack.getComponentsPatch()),
                stack.getAmount(), Action.EXECUTE, Actor.EMPTY);
            int rest = (int) (stack.getAmount() - handled);
            if (rest > 0) {
                final ItemStack bucket = net.neoforged.neoforge.fluids.FluidUtil.getFilledBucket(
                    stack.copyWithAmount(rest));
                if (!bucket.isEmpty()) {
                    Block.popResource(level, pos, bucket);
                    rest = 0; // 已交给桶形态，算作已交付
                }
            }
            // 只抽走「已交付」的那部分：没有桶形态、又回不了网的流体原样留在罐里（绝不静默销毁）
            final int delivered = stack.getAmount() - rest;
            if (delivered > 0) {
                outputTank.drain(stack.copyWithAmount(delivered),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            }
            if (rest > 0) {
                LOGGER.warn("SequenceExecutionChamber at {} keeps {} mB of {} : no network storage and no"
                    + " bucket form, it must not be destroyed silently",
                    pos, rest, stack.getFluid());
            }
            fluidToNetwork += handled;
            fluidKept += rest;
        }
        setChanged();
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", pos)
                + " drop {item x" + droppedItems + ", fluid x" + fluidToNetwork + "}"
                + " to=world/network"
                + " kept=" + fluidKept
                + " reason=block_removed");
            RsccAssemblyDebug.countReturn(droppedItems + fluidToNetwork);
            RsccAssemblyDebug.countReject(fluidKept);
        }
    }

    /** 本仓当前接入的 RS 网络存储组件（未接入 / 客户端一律 {@code null}）。 */
    @org.jetbrains.annotations.Nullable
    private StorageNetworkComponent currentStorage() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        final Network network = getNode().getNetworkOrNull();
        return network == null ? null : network.getComponent(StorageNetworkComponent.class);
    }

    /**
     * 某台输出总线应下发给界面的类别快照（有序）：每个类别带「是否已选 / 共享台数」。
     *
     * @param exporterPos 该输出总线坐标
     * @param explicit    该输出总线是否显式选过类别（未显式选过时按「默认全选输入性产物」展示）
     * @param chosen      该输出总线显式选择的类别 id（{@code explicit == false} 时忽略）
     */
    public List<RsccBusCategory> busCategorySnapshot(final BlockPos exporterPos, final boolean explicit,
                                                     @org.jetbrains.annotations.Nullable final List<String> chosen) {
        normalizeBusOwners();
        final List<RsccBusCategory> result = new ArrayList<>();
        // <b>链级类别并集</b>（用户需求：接在链上任意一处 = 属于整条链）：界面「可选类别」必须是
        // 整条链的并集，否则玩家在链上的另一台仓勾过的类别根本选不到，而它其实是被整条链共用的。
        for (final ChainBusCategory entry : chainCategories()) {
            final BusCategoryInfo info = entry.info();
            // 没显式选过时按「真正生效的默认集」展示 = 输入类 + 中间产物（= defaultExportCategoryIds()）。
            // 本轮把中间产物一并标为已选：它本来就在默认导出集里（少了它链在第一步之后必断），
            // 旧实现只标输入类 ⇒ 界面显示的默认集比实际导出的少一项，会误导玩家；
            // 更关键的是「详细配置」子界面以快照为编辑起点，若默认少一项，玩家一点「确定」
            // 就会把中间产物从导出集里真正去掉（把显示上的不一致变成功能故障）。
            final boolean selected = explicit
                ? chosen != null && chosen.contains(info.id())
                : (info.isInput() || info.isIntermediate());
            final List<BlockPos> owners = busCategoryOwners.get(info.id());
            final int shared = owners == null || owners.isEmpty() ? 1 : owners.size();
            result.add(new RsccBusCategory(info.id(), iconItemId(info.icon()), iconFluidId(info.fluidIcon()),
                info.labelKey(), iconItemId(info.stepMachine()), selected, shared,
                info.amount(), info.estimated(), info.reuseKey() == null ? "" : info.reuseKey(),
                // 候选随类别一起下发（2026-10-05：界面不再自己猜候选，避免两条配方互相污染）
                categoryCandidateIds(info)));
        }
        // 整链一台单元样板都没有时，快照本来是空的 ⇒ 界面连「配方标签页」都建不出来。
        // 用户 2026-10-06 拍板：这一页仍要建（「能干这类加工」是已知事实），只是页内零行。
        appendRecipeTabMarker(result);
        return result;
    }

    /**
     * <b>对称入口（延长型输入）</b>：某台输入总线应下发给界面的类别快照（有序）。
     *
     * <p><b>与 {@link #busCategorySnapshot} 的三点差异（都是收回方向的语义决定的）</b>：</p>
     * <ol>
     *     <li><b>没有「多台均分」</b>：收回是把执行仓内部存储的东西<b>原子抽取</b>进网络，多台输入总线
     *     同时收也只是各自取走不同的那一份，不可能重复取走同一份，因此不需要轮询归属表，
     *     {@code sharedCount} 恒为 {@value #IMPORT_SNAPSHOT_SHARED_COUNT}（界面不画共享角标）；</li>
     *     <li><b>不显示「每批投入量 / 预估需求」</b>：那两个数是「喂给机器需要多少」，对「收回」没有意义，
     *     因此一律置 0（界面自动跳过对应行）；</li>
     *     <li><b>默认全不选（手动模式）/ 自动勾选非输入类（全自动模式）</b>：手动模式下 {@code chosen}
     *     为空表时所有类别都是未勾选（收回方向默认打开会与「延长型输出」的供料互相拉扯）；
     *     全自动模式下由 {@code auto} 决定 —— 输入类恒不收回、其余（中间产物 / 成品 / 废料）恒收回，
     *     界面只读展示，玩家不必勾选（见 {@link #busImportCategorySnapshot(List, boolean)}）。</li>
     * </ol>
     *
     * @param chosen 该输入总线显式选择的类别 id（{@code null} / 空 = 一个都不收回）；{@code auto} 为真时忽略
     */
    public List<RsccBusCategory> busImportCategorySnapshot(
        @org.jetbrains.annotations.Nullable final List<String> chosen) {
        return busImportCategorySnapshot(chosen, false);
    }

    /**
     * 带「全自动」开关的类别快照（服务端权威）：{@code auto == true} 时输入类恒不选中、非输入类恒选中，
     * 因此界面把类别条当成<b>只读展示</b>（标题写「自动收回：中间产物 / 成品 / 废料」），玩家不需要勾选。
     *
     * @param chosen 手动模式下显式选择的类别 id（{@code auto == true} 时忽略）
     * @param auto   是否处于全自动收回模式（= 玩家没开「手动」开关，这是默认值）
     */
    public List<RsccBusCategory> busImportCategorySnapshot(
        @org.jetbrains.annotations.Nullable final List<String> chosen, final boolean auto) {
        final List<RsccBusCategory> result = new ArrayList<>();
        // <b>链级类别并集</b>（与输出总线侧同一口径）：输入总线接在链上任意一台仓，其可选类别
        // 同样是整条链的并集 —— 否则「链上另一台仓定义的中间产物步」在界面上根本选不到。
        // 真正的收回判定同样按链级类别逐条走（见 RsccChamberImportStrategy 的手动分支）。
        for (final ChainBusCategory entry : chainCategories()) {
            final BusCategoryInfo info = entry.info();
            final boolean selected = auto
                ? !info.isInput()
                : chosen != null && chosen.contains(info.id());
            result.add(new RsccBusCategory(info.id(), iconItemId(info.icon()), iconFluidId(info.fluidIcon()),
                info.labelKey(), iconItemId(info.stepMachine()), selected,
                IMPORT_SNAPSHOT_SHARED_COUNT, 0L, 0L,
                info.reuseKey() == null ? "" : info.reuseKey(),
                // 输入总线的类别同样带上候选（界面两侧共用同一份显示逻辑）
                categoryCandidateIds(info)));
        }
        // 与输出总线侧**逐字同一口径**（两处界面是同一个子界面，标签页必须一样）：整链无样板时补标记。
        appendRecipeTabMarker(result);
        return result;
    }

    /**
     * 往界面快照里补一条<b>「配方标签页标记」</b>（{@link RsccBusCategory#RECIPE_TAB_PREFIX}）。
     *
     * <h2>它解决什么（用户 2026-10-06 拍板）</h2>
     * <p>上一轮实现了「整条链都没有单元样板 ⇒ 类别表为空」。而标签页是<b>由类别 id 反推</b>的
     * （见 {@code BusCategoryConfigScreen#tabKeysOf}），类别表空了 ⇒ 连「配方标签页」也建不出来。
     * 用户明确选择：<b>仍建那一页，但页内一条都不显示</b> ——
     * 「这台机器能做这类加工」是已知事实（本仓绑定的 {@code recipeType}），
     * 而「具体配方 + 步序 / 要交接哪些件」需要单元样板，没有样板就是零行。</p>
     *
     * <h2>为什么只加在快照的返回值里（不污染任何判定的证明）</h2>
     * <ul>
     *     <li>它<b>不</b>写进 {@code busCategoriesCache} / {@code busCategories()}，也不进
     *     {@link #chainCategories()} —— 于是 {@link #chainBusOwners()} 归属表、
     *     {@code computeOwnedSteps()} 属主、备料 / 供料目标、排队 / 名额 / 在制计数
     *     的数据源<b>一个字都没变</b>（那些路径只读类别表缓存与链级类别表）；</li>
     *     <li>归属表重建（{@link #chainBusOwners()}）只认「在 {@link #chainCategories()} 里」的 id，
     *     因此即便老存档里留过这个 id，它也永远拿不到属主（本方法每次调用都整表重建）；</li>
     *     <li>{@code selected} 恒 {@code false}（见下）：界面不会把它放进勾选集合，
     *     提交的勾选表里也就永远不会出现它；</li>
     *     <li>补的条件取<b>链级</b> {@link #chainHasAnyUnit()}：链上任意一台有样板 ⇒
     *     一个字都不补 ⇒ 既有行为逐字不变（标签页照旧由真实类别反推）。</li>
     * </ul>
     *
     * @param result 本方法调用方正在拼的那一份界面快照（就地追加；服务端其它状态一概不动）
     */
    private void appendRecipeTabMarker(final List<RsccBusCategory> result) {
        // ① 只在本链真的一台单元样板都没有时补：任一成员有样板 ⇒ 走既有行为（逐字不变）；
        // ② 快照非空时不补：那时标签页由真实类别反推，再补一条只会多出一个点不动的空页。
        if (!result.isEmpty() || chainHasAnyUnit()) {
            return;
        }
        // 处理器类型是「能干什么」这一层唯一确定的事实（具体是哪条序列装配配方在没有样板时无从得知）。
        final String id = RsccBusCategory.recipeTabId(getRecipeType());
        if (id.isEmpty()) {
            return; // 连处理器类型都没绑定：没有任何可读的页名，不建页（界面照旧「没有可选类别」）
        }
        // selected 恒 false（界面据此排除它：不画行、不进计数、不可勾选）；
        // sharedCount = 1（不画「共享」角标），其余字段一律空 / 0 —— 它不画任何行，字段都不会被读到。
        result.add(new RsccBusCategory(id, "", "", "", "", false, 1, 0L, 0L, "", ""));
    }

    /**
     * 只读：本仓全部「输入类」物品（= 原料 / 流体输入的物品那一类）。
     * <p><b>用途</b>：全自动收回的判定基准 —— 「非输入类」才算机器的产出（中间产物 / 成品 / 废料）。
     * 刻意放在执行舱上（类别模型的权威在这里），输入总线策略只按返回集合做判定，不自己解释类别 id。</p>
     */
    public Set<Item> inputCategoryItems() {
        final Set<Item> items = new LinkedHashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (info.id() == null || !info.id().startsWith(RsccBusCategory.INPUT_PREFIX)) {
                continue;
            }
            for (final Item item : info.items()) {
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    /** 只读：本仓全部「流体输入」类别的流体（口径与 {@link #inputCategoryItems()} 对称）。 */
    public Set<Fluid> inputCategoryFluids() {
        final Set<Fluid> fluids = new LinkedHashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (info.id() == null || !info.id().startsWith(RsccBusCategory.FLUID_PREFIX)) {
                continue;
            }
            for (final Fluid fluid : info.fluids()) {
                if (fluid != null) {
                    fluids.add(fluid);
                }
            }
        }
        return fluids;
    }

    /**
     * 只读：本仓所在<b>链（分支）</b>上全部「输入类」物品的<b>并集</b>（= 链上任何一台仓要的料都算本链的输入料）。
     *
     * <h2>为什么收料侧要用并集（2026-10-06 链展开的配套，防「备料 / 收回」每秒来回搬运）</h2>
     * <p>链展开后，一条链总线选中的类别会让<b>链上每一台</b>仓都去备料（见 {@link #busCategoryOwners}）。
     * 而链上的仓彼此相邻 ⇒ 本来就在同一个存储集群（{@code RsccMachineCluster}）里，仓内存储是<b>同一份对象</b>。
     * 若收回侧仍只认「本总线绑定那一台仓」的输入类，那么「链上另一台仓的输入料」会被判定成
     * 「非输入类 ⇒ 收回」抄回网络，下一秒又被备料侧买回来 —— 正是历次实测里最典型的一种回归
     * （实测日志：{@code chamber pull{x5}} 与 {@code importer took{x5}} 出现在同一秒）。
     * 取并集后：链上任何一台仓要的料都受「输入类」保护，两侧口径重新一致。</p>
     *
     * <p><b>保守方向</b>：并集只会让「更少的东西被收回」。任务结束的一次性边沿令牌
     * （{@code claimResidualInputReclaim}）照旧绕过本保护，因此「停任务后残留回流一次」不受影响。</p>
     */
    public Set<Item> chainInputCategoryItems() {
        final Set<Item> items = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            items.addAll(member.inputCategoryItems());
        }
        return items;
    }

    /** 只读：本仓所在链（分支）上全部「流体输入」类别的并集（口径与 {@link #chainInputCategoryItems()} 对称）。 */
    public Set<Fluid> chainInputCategoryFluids() {
        final Set<Fluid> fluids = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            fluids.addAll(member.inputCategoryFluids());
        }
        return fluids;
    }

    /** 物品 → 注册名字符串（无物品返回空串）。 */
    private static String iconItemId(@org.jetbrains.annotations.Nullable final Item item) {
        if (item == null) {
            return "";
        }
        final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "" : key.toString();
    }

    /** 流体 → 注册名字符串（无流体返回空串）。 */
    private static String iconFluidId(@org.jetbrains.annotations.Nullable final Fluid fluid) {
        if (fluid == null) {
            return "";
        }
        final ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        return key == null ? "" : key.toString();
    }

    /**
     * 每 tick 由网络节点驱动（在 {@link #tickEngine} 最前面调用）：
     * <ol>
     *     <li>每 {@link #BUS_SCHEDULE_INTERVAL_TICKS} tick 重建类别列表并归一化归属（定期自愈）；</li>
     *     <li>存在共享类别时，逐 tick 让相连的输出总线按当前轮次重建导出清单（轮询均分的执行端）。</li>
     * </ol>
     */
    public void tickBusScheduler() {
        // 每 tick 重算：它只描述「本 tick 刚发生了任务结束」这一瞬间（见字段 javadoc）
        busTaskEndedThisTick = false;
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        // 「挂起当刻即停」（用户硬要求）：门控平时每 10 tick 才复查，挂起最多 0.5 秒才生效。
        // 看门狗每次挂起 / 继续 / 任务终止都会推进一个全局版本号，这里逐 tick 比对；一变就把
        // 复查冷却清零 ⇒ 挂起 / 继续的那一个 tick 内门控立即重算，本仓当刻停 / 启。
        final int suspendEpoch = AssemblyWatchdog.suspendEpoch();
        if (suspendEpoch != lastSuspendEpoch) {
            lastSuspendEpoch = suspendEpoch;
            busGateCooldown = 0;
        }
        // 「自动合成」门控：每 10 tick 复查一次；状态翻转时立刻重推导出清单
        // （输出总线据此停止 / 恢复导出，客户端界面也据此显示提示）
        if (--busGateCooldown <= 0) {
            busGateCooldown = BUS_GATE_INTERVAL_TICKS;
            final boolean gate = computeAutoCrafting();
            // 与本仓产线相关的任务（gate 为假时必然没有相关任务，不必再查）：
            // 一次遍历同时回答「有没有活跃的相关任务」（决定开闸）与「相关任务是否全被挂起」（决定冻结）。
            final int state = gate ? relevantTaskState() : RELEVANCE_NONE;
            final boolean relevant = state == RELEVANCE_ACTIVE;
            final boolean frozen = state == RELEVANCE_SUSPENDED_ONLY;
            // 「真的有人为本产线下单」：只有它也为真，本仓才取料 / 投料 / 自动合成
            // （用户第 ⑦ 条：没有订单 ⇒ 零取料、零投料、零自动合成）。
            final boolean goal = relevant && hasGoalTask();
            busGoalTaskGate = goal;
            // <b>按配方细化「有没有单」</b>（2026-10-05 用户实测：一仓两配方时列车轨道被无单开工）：
            // 整仓闸门（上面那个 goal）只说明「有人为本仓的某条配方下单」，不说明是哪一条。
            // 这里再算一次「哪几条配方真的有活跃订单」，供开件 / 继续 / 补料逐配方判定。
            orderedRecipesCache = gate && goal ? orderedRecipes() : Set.of();
            // <b>用户第 5 条：逐步骤校验「总线有没有为该步配好输入 / 输出」</b>。
            // 不齐 ⇒ {@link #busConfigGate} 为假 ⇒ 本仓<b>不取料 / 不投料</b>（见 isAutoCraftingEnabled /
            // fillInternalForBus）⇒ 不会「配置没配好却一直抽原料、白烧黑曜石粉与岩浆」；
            // 同时按签名去重播一次横幅，明确报出「第 N 步（机器名）：缺输入 / 缺输出总线配置」。
            final List<BusConfigGap> configGaps = gate && relevant && goal ? busConfigGaps() : List.of();
            final boolean configOk = configGaps.isEmpty();
            if (configOk != busConfigGate) {
                busConfigGate = configOk;
                if (RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " gate bus_config_ok=" + configOk
                        + (configOk ? "" : " gaps=" + configGaps.size()));
                }
            }
            if (configOk) {
                busConfigGapSignature = "";
            } else {
                final String gapSignature = configGaps.toString();
                if (!gapSignature.equals(busConfigGapSignature)) {
                    busConfigGapSignature = gapSignature; // 内容变了才再播一次（绝不每 0.5 秒刷）
                    sendBusConfigBanner(configGaps);
                }
            }
            // <b>用户第 4 条：没有中间产物缓存仓时，明确告诉玩家「中间产物只在舱内流转、终端看不到」</b>
            // 并列出该步会产出什么。只在有相关任务在跑（且未冻结）时播，内容变化才播一次。
            if (gate && relevant && goal && !frozen) {
                notifyIntermediatesInvisibleWithoutCache();
                // <b>用户第 4 条（下单了却啥也不发生）：明确报出「本仓一步都不认领」这条卡住原因。</b>
                // 它最常见的来源就是「这间舱的单元样板槽是空的 / 没放对本步的样板」——
                // 那种情况下所有门控都为真、界面也显示已下单，但本仓一步都不会动，玩家只能干等。
                // 这里只说清原因（限频：同签名只出一次），不改任何状态、不做任何搬运。
                if (ownedSteps().isEmpty() && RsccAssemblyDebug.changed(
                    "noowned@" + RsccAssemblyDebug.at(worldPosition),
                    Boolean.TRUE.toString())) {
                    RsccAssemblyDebug.warn("noowned@" + RsccAssemblyDebug.at(worldPosition),
                        "stalled " + RsccAssemblyDebug.machine("chamber", worldPosition)
                            + " 已下单（门控全开）但本仓<b>没有认领配方的任何一步</b>："
                            + "单元样板槽为空或没放本步的样板 ⇒ 一步都不会动。"
                            + "请在槽位里放入该步的单元样板（或改用指派了本仓的总样板重新生成）");
                }
            }
            // 「已结束」必须<b>连续确认</b>（本轮修正，见 {@value #BUS_GATE_END_CONFIRM}）：旧实现
            // 「一观察到 gate/relevant 翻转就当作任务结束」—— 而 RS 的网络图重建 / 自动合成组件
            // 短暂为空 / 节点瞬时不挂网络都会让这两个值闪一下，于是残留原料被搬回网络、整条产线停摆
            // （用户实测：「任务跑着跑着整条线突然不动」；日志里表现为 gate autocrafting=false
            // 紧接着又 true，且中间一件都没动）。改为连续 K 次复查都空闲才算真结束：
            // 抖动最多让备料停 1.5 秒、<b>不再误触发回流</b>，而真正的取消 / 完成也只晚 1.5 秒收尾。
            // <b>冻结（挂起）不算空闲</b>：挂起是「玩家让它先停」，不是「任务结束」—— 一旦算作空闲，
            // 连续 3 次后就会触发「残留回流 + 收尾」，把该冻结的东西搬回网络（用户要求「含搬运也要停」）。
            final boolean idleNow = (!gate || !relevant) && !frozen;
            gateIdleStreak = idleNow ? gateIdleStreak + 1 : 0;
            if (gate != busAutoCraftGate || relevant != busRelevantTaskGate || frozen != busFrozenGate) {
                // 「本条序列装配任务结束（完成 / 取消 / 停止）」的两个边沿，两者都要回流本仓残留的输入原料：
                //   ① 网络整体空闲（gate true→false）—— 既有语义，保留；
                //   ② 本仓相关任务从「在跑」翻转为「不在跑」—— 用户实测断点：取消了本条任务，
                //      但网络里还有别的任务在跑，上面的边沿永远等不到，残留原料就永远堵在仓里。
                // <b>冻结（全被挂起）时两个边沿都不算结束</b>：挂起只是暂停，绝不能在那一刻把东西搬走。
                final boolean taskEnded = !frozen && ((busAutoCraftGate && !gate)
                    || (busRelevantTaskGate && !relevant));
                // <b>「新订单开始」边沿（2026-10-05 修复）</b>：本仓从「没有相关任务」翻到「有相关任务」
                // 的这一刻，上一条空闲期留下的「推不动」证据必须作废 —— 否则它会立刻把新下单的任务
                // 判成 EXECUTOR_OFFLINE 并挂起（实测 stall=41 tick，而机器一台没掉、料一份不缺）。
                // 见 clearPushStallEvidence() 里的完整复现链。
                final boolean taskStarted = relevant && !busRelevantTaskGate && !frozen;
                busAutoCraftGate = gate;
                busRelevantTaskGate = relevant;
                busFrozenGate = frozen;
                if (taskStarted) {
                    clearPushStallEvidence();
                }
                normalizeBusOwners();
                pushBusTurnToExporters();
                setChanged();
                if (taskEnded) {
                    // 顺序保证（用户要求「门控一翻转，同一 tick 内就先停备料、并立刻回流」）：
                    // 先置一次性闸门 → 本 tick 的 fillInternalForBus 被整 tick 跳过 → 再做回流，
                    // 因此不可能出现「刚把残留还回网络，同一 tick 又把它吸回本仓」的来回搬运。
                    busTaskEndedThisTick = true;
                    // 同一刻发出「一次性残留回流令牌」：输入总线在紧接的那一次自动收回里凭它把
                    // 「压在机器 / 置物台上、本仓没吃掉的输入类残留」（例如注液机里剩的 500 mB 岩浆）
                    // 收进网络一次。令牌用完即废 + 有有效期，因此不会退化成「空闲时持续收输入类」。
                    busResidualInputToken = true;
                    busResidualInputDeadline = level.getGameTime() + BUS_RESIDUAL_FLUSH_WINDOW_TICKS;
                    busSuspendFlushed = false; // 任务结束后若再次挂起，仍要退一次
                    flushResidualInputs();
                } else if (frozen) {
                    // 挂起冻结：作废可能还压着的一次性令牌（此时任何收回都属于「搬运」，必须停），
                    // 也不再允许「收尾」被触发（idleNow 已把 frozen 排除在外）。东西原地冻结，
                    // 等玩家点「继续」；门控一解冻（relevant 假→真）就回到上面那一支重新放行备料。
                    busResidualInputToken = false;
                    // <b>用户第 6 条：挂起 ⇒ 该任务已拉进本仓的所有物品 / 流体先退回网络</b>
                    // （恢复时由既有备料逻辑按需再拉回）。同一段挂起只退一次（busSuspendFlushed）。
                    if (!busSuspendFlushed) {
                        busSuspendFlushed = true;
                        flushChamberForSuspend();
                    }
                } else if (relevant) {
                    // 本条任务重新开跑（相关门控 false→true）：上一轮的一次性令牌立即作废，
                    // 否则「刚停又立刻重开」时会出现「新任务刚喂进去的料被边沿令牌收回来」的拉扯。
                    // 同时复位「挂起已回流」标记，使下一次挂起还能再退一次。
                    busSuspendFlushed = false;
                    busResidualInputToken = false;
                }
                if (RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " gate autocrafting=" + gate
                        + " relevant=" + relevant
                        + (frozen ? " frozen=true" : "")
                        + " reason=" + (frozen ? "suspended_frozen" : gateReason(gate, relevant)));
                }
            }
            // <b>2026-10-06（取消 / 挂起链审计）：挂起仍在持续、而先前那一次退回因网络不可达没做成 ⇒ 重试。</b>
            // 一次性标记由 {@code flushChamberForSuspend} 在「网络不可达」时复位，因此这里只在
            // 「这一段挂起确实还没退成功」时进来；成功一次即置位，绝不在同一段挂起里重复搬运
            //（放不下而留在舱内的余量属于「网络装不下」，那时标记已是 true，不会再来）。
            // 冻结期间本仓不备料 / 不推料 / 不搬运，因此这个重试是纯「还东西回网络」，与挂起语义一致。
            if (frozen && !busSuspendFlushed) {
                busSuspendFlushed = true;
                flushChamberForSuspend();
            }
            // 连续空闲达到阈值且尚未收尾过 → 现在收尾一次（与「翻转当 tick 收尾」同一个执行点，
            // 因此「先停备料、再回流」的顺序保证一字未改，只是把「判定结束」改成需要确认）。
            if (idleNow && gateIdleStreak >= BUS_GATE_END_CONFIRM && !busTaskEndFired) {
                busTaskEndFired = true;
                busTaskEndedThisTick = true;
                busAutoCraftGate = false;
                busRelevantTaskGate = false;
                normalizeBusOwners();
                pushBusTurnToExporters();
                busResidualInputToken = true;
                busResidualInputDeadline = level.getGameTime() + BUS_RESIDUAL_FLUSH_WINDOW_TICKS;
                // 顺序要点（2026-10-06）：残留回流里新增了「工位侧起步原料残留」这一段，它的唯一凭据
                // 就是下面那张在制件登记表（哪个工位、本仓最后推过去的是哪一件）。因此必须
                // <b>先回流、再清表</b> —— 清表挪后一个语句，语义一字未改（仍发生在「任务确认结束」
                // 这一刻、同一个 tick 内），但残留不会因为「凭据先被抹掉」而漏收。
                flushResidualInputs();
                // 任务确认结束 ⇒ 在制件登记表整表清空（东西都要还回网络；见设计文档第 4 步）
                rscc$clearAllUnits();
                if (RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " gate autocrafting=false relevant=false reason=task_end_confirmed"
                        + " idleStreak=" + gateIdleStreak);
                }
            }
            if (!idleNow) {
                busTaskEndFired = false; // 任务又跑起来了：下一次空闲可以再收尾一次
            } else {
                // <b>2026-10-05 修掉「多出一个金板且永久滞留」</b>（子代理闭环定位）。
                //
                // 收尾是<b>一次性边沿</b>：令牌只活 {@link #BUS_RESIDUAL_FLUSH_WINDOW_TICKS}（40）tick，
                // 而且输入总线领取一次就作废。可是「堵塞自愈」{@code recoverBlockingTargetItem()} 每 tick 都在跑、
                // 恢复窗口长达 {@link #REFUSED_TARGET_RECOVER_TICKS}（200）tick —— 它可以在边沿<b>之后</b>
                // 把压在机器上的输入类（起步原料 / 投入物）搬进本仓内部存储；那一份此后
                // <b>再也没有任何回流路径</b>（机器侧对起步原料恒不回收、舱内只在边沿回一次）
                // ⇒ 玩家看到「多出一个金板，就放在那里了」。
                //
                // 修法：<b>任务已确认结束、而本仓仍持有输入类物品</b>时，每 tick 续命那枚令牌。
                // 语义没变（仍然是「任务结束后才允许收输入类」），只是把窗口从「一次 40 tick」改成
                // 「只要还有残留就一直允许」，直到真正收干净（此后条件不再成立、令牌自然失效）。
                if (busTaskEndFired && hasResidualInputsToFlush()) {
                    busResidualInputToken = true;
                    busResidualInputDeadline = level.getGameTime() + BUS_RESIDUAL_FLUSH_WINDOW_TICKS;
                }
            }
        }
        if (--busScheduleCooldown <= 0) {
            busScheduleCooldown = BUS_SCHEDULE_INTERVAL_TICKS;
            // 顺序要点：先重建「本仓负责的步」（类别表读它，用来给每个步各出一个中间产物类别），
            // 再重建类别表 —— 否则新增 / 移除的步要晚一个节拍才反映到类别上。
            unitsCache = collectUnits(level);
            ownedStepsCache = computeOwnedSteps(level);
            busCategoriesCache = computeBusCategories();
            // 同一节拍重建「步骤专用投入物」表（它读「本仓负责的步」，必须紧跟 ownedStepsCache 之后，
            // 否则会读到上一节拍的步表）
            stepExtraInputsCache = computeStepExtraInputs(level);
            // 与类别表同一节拍重建：阈值表 + 相关性物品集合
            //（前者供输入总线的「未完成件能不能收回」判据，后者供「本条任务是否还在跑」）
            disallowThresholdsCache = disallowInputThresholds(level);
            pipelineItemsCache = computePipelineItems(level);
            goalItemsCache = computeGoalItems(level);
            normalizeBusOwners();
            if (RsccAssemblyDebug.isEnabled()) {
                final String signature = categorySignature();
                RsccAssemblyDebug.transition("cats@" + RsccAssemblyDebug.at(worldPosition), signature,
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " cats=[" + signature + "]"
                        // cats= 是**链级**类别并集（见 categorySignature）：链上台数一并打出来，
                        // 避免「4 台各打一小份」造成的误读（实测日志形态）。
                        + " chain=" + chainMembers().size()
                        + " output=" + outputMode.key()
                        + " cluster=" + clusterSize);
            }
        }
        // 导出清单的<b>有界重算</b>（本轮修正，用户要求：修到不再有持续性的无效重复）。
        // 旧实现只在「有共享类别」时每 tick 重推清单，单归属类别（绝大多数现场）的清单一旦下发就
        // <b>永不重建</b> —— 于是快照里那些「此刻干不了活」的类别（仓里没料 / 工位上还有在制件）
        // 会被搬运策略每 tick 判一次失败：实测齿轮 chamber_empty 228 次、金板 machine_busy 2576 次。
        // 现在改为：共享类别仍每 tick 重推（份额轮次依赖它），其余类别每
        // {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 重推一次（≤1 秒），因此「空转判定」从
        // 每 tick 一次降到每次重算一次，而机器一空出来 / 料一备好最多 1 秒就恢复供料。
        if (--busExportRefreshCooldown <= 0) {
            busExportRefreshCooldown = BUS_SCHEDULE_INTERVAL_TICKS;
            pushBusTurnToExporters();
        } else if (busRoundRobinActive) {
            pushBusTurnToExporters();
        }
    }

    // ==================== 用户第 5 条：下单前 / 运行中的「总线未配置」逐步骤校验 ====================

    /** 一步的「总线配置缺口」：{@code step} 0-based、{@code machine} 该步指派的机器名、{@code input} 缺的是输入还是输出。 */
    public record BusConfigGap(int step, String machine, boolean input) {
    }

    /**
     * <b>只读</b>：本仓当前流程里「相连总线没有为该步配置对应输入 / 输出」的步骤清单（用户第 5 条）。
     *
     * <h2>口径（与推料 / 收回 / 类别表同源，绝不另立一套）</h2>
     * <p>对 {@link #ownedSteps()} 里「本仓负责的每条配方 × 每一步」：</p>
     * <ol>
     *     <li><b>输入</b>：该步的每个输入组（{@link SequencedRecipeProbe#assemblyStepInputGroups}，
     *     含第 0 步的起步原料候选）都必须有「<b>被某条相连总线勾中</b>的输入类别」覆盖它的候选；
     *     流体同理（{@link SequencedRecipeProbe#stepInputFluids}）。</li>
     *     <li><b>输出</b>：非末步 ⇒ 该步的中间产物类别 {@code intermediate:<配方>:<步序>}
     *     必须被某条相连总线勾中；末步 ⇒ 配方的 {@code results} 里至少有一项被勾中的成品 / 废料类别覆盖。</li>
     * </ol>
     * <p>「被勾中」取 {@link #busCategoryOwners}（相连总线的权威归属表）—— 类别<b>存在</b>但没有任何
     * 总线勾它，等于没配置，正是用户「我忘记配置了」的那种情况。判不出来（无世界 / 客户端）返回空表
     * （= 放行），绝不因为判不出来而停线。只读，不搬运任何资源。</p>
     */
    public List<BusConfigGap> busConfigGaps() {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || outputMode != OutputMode.BUS) {
            return List.of(); // 「面输出」模式根本没有「总线配置」这回事：不校验
        }
        // 「本仓当前流程」的配方（与 normalizeBusOwners 的旧 id 归一化同源）：没有正在跑的流程 ⇒ 不校验，
        // 也就不会因为「另一条没在跑的配方」而误报（用户第 1 条怀疑点 c）。
        final Set<String> activeRecipes = activePipelineRecipeIds();
        if (activeRecipes.isEmpty()) {
            return List.of();
        }
        // 输出总线勾中的类别 id（{@link #busCategoryOwners} 的键，含旧 id 归一化后的新 id）：
        // 「喂料」这一步只可能由输出总线完成，因此**输入侧**只认它。
        final Set<String> exporterCats = busCategoryOwners.keySet();
        // 输入总线勾中的类别 id + 是否处于「全自动收回」：
        // 本架构里机器的产出 / 废料 / 中间产物是靠输入总线收回网络的（自动模式默认收全部非输入类），
        // 因此**输出侧**必须把输入总线一并算进来 —— 否则一套完全正确的配置会被判成「缺输出总线配置」。
        final Set<String> claimedOutput = new LinkedHashSet<>(exporterCats);
        boolean autoImporter = false;
        for (final BlockPos pos : connectedImporterPositions()) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
                instanceof final RsccImporterExecutorMode importer) {
                if (importer.rscc$isAutoCollect()) {
                    autoImporter = true;
                }
                claimedOutput.addAll(importer.rscc$getImportCategoryIds());
            }
        }
        if (exporterCats.isEmpty() && claimedOutput.isEmpty() && !autoImporter) {
            return List.of(); // 一条总线都没连：本仓本来就不会动作，不在这里报
        }
        final String machine = getChamberDisplayName();
        final List<BusConfigGap> gaps = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        for (final Map.Entry<String, Set<Integer>> entry : ownedSteps().entrySet()) {
            final String recipeId = entry.getKey();
            if (!activeRecipes.contains(recipeId)) {
                continue; // 不是本仓当前流程的配方：不校验（否则旧 id 归一化的过滤会把它误报成「缺配置」）
            }
            final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
            if (recipe == null) {
                continue;
            }
            final List<SequencedRecipe<?>> sequence = recipe.getSequence();
            if (sequence.isEmpty()) {
                continue;
            }
            final ItemStack transitionalStack = recipe.getTransitionalItem();
            final Item transitional = transitionalStack.isEmpty() ? null : transitionalStack.getItem();
            for (final int step : entry.getValue()) {
                if (step < 0 || step >= sequence.size() || !visited.add(recipeId + "#" + step)) {
                    continue;
                }
                final SequencedRecipe<?> sr = sequence.get(step);
                boolean inputOk = true;
                for (final SequencedRecipeProbe.InputGroup group
                    : SequencedRecipeProbe.assemblyStepInputGroups(sr.getRecipe(), transitional)) {
                    if (!anyClaimedInputCovers(exporterCats, group.candidates())) {
                        inputOk = false;
                        break;
                    }
                }
                if (inputOk) {
                    for (final FluidStack stack : SequencedRecipeProbe.stepInputFluids(sr.getRecipe())) {
                        if (!anyClaimedFluidCovers(exporterCats, stack.getFluid())) {
                            inputOk = false;
                            break;
                        }
                    }
                }
                if (!inputOk) {
                    gaps.add(new BusConfigGap(step, machine, true));
                }
                if (!stepOutputConfigured(claimedOutput, autoImporter,
                    RsccBusCategory.intermediateId(recipeId, step), recipe, step)) {
                    gaps.add(new BusConfigGap(step, machine, false));
                }
            }
        }
        return gaps;
    }

    /**
     * 供输出总线调用：记录一次「目的地拒收」（机器 / 置物台已满、或不接受这份件）。
     * <p>只记时间戳与坐标（见 {@link #destinationRefusalAt} / {@link #lastRefusedTarget}），
     * 不搬运 / 不修改任何资源。</p>
     *
     * @param target   被拒收的供料目标坐标（可为 {@code null}：调用方拿不到时只记时刻）
     * @param resource 被拒收的资源（用于日志与「只回收它」的保守判定；可为 {@code null}）
     */
    public void rscc$noteDestinationRefusal(@org.jetbrains.annotations.Nullable final BlockPos target,
                                            @org.jetbrains.annotations.Nullable final net.minecraft.world.item.Item resource) {
        final Level level = getLevel();
        if (level != null && !level.isClientSide()) {
            final long now = level.getGameTime();
            destinationRefusalAt = now;
            if (target != null) {
                lastRefusedTarget = target.immutable();
                // 按目标分别记（同一帧只保留最近一次）：判定「整仓推不动」时要用
                refusedTargetAt.put(target.immutable(), now);
            }
            if (resource != null) {
                lastRefusedItem = resource;
            }
            noteStall(StallReason.DESTINATION_REFUSED,
                resource == null ? "-" : RsccAssemblyDebug.itemId(resource));
        }
    }

    /**
     * <b>由输出总线在「成功把东西投进机器」时调用</b>：清掉该目标的拒收记录 + 记下这次成功时刻。
     *
     * <p>为什么必须清（用户实测「齿轮只堵一个，另外一个也停工」）：旧实现只在<b>任务开始</b>时清一次全仓证据，
     * 一次拒收会在 {@value #PUSH_STALL_WINDOW_TICKS} tick 内一直压着整仓；而只要别处仍在正常投料，
     * 就说明「下游并没有整体堵住」，那 60 tick 的压制纯属误伤。</p>
     */
    public void rscc$notePushSucceeded(@org.jetbrains.annotations.Nullable final BlockPos target) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        lastSuccessfulPushAt = level.getGameTime();
        if (target != null) {
            refusedTargetAt.remove(target);
        }
    }

    /**
     * <b>只读</b>：这个目的地「还在拒收」多久了（tick）；没有有效拒收记录返回 {@code -1}。
     *
     * <h2>为什么需要它（2026-10-06：恢复「下游被堵」横幅的必需入口）</h2>
     * <p>看门狗判「输出阻塞」走的是 {@link #pushStalledOnDestination()}，而它的聚合口径是
     * <b>{@code refusedTargetAt.size() >= 2}</b>（必须<b>两台以上</b>目的地同时拒收）。
     * 玩家现场只有<b>一台</b>冲压机 / 一个置物台 ⇒ {@code size()} 恒为 1 ⇒ 判据<b>恒为假</b> ⇒
     * 横幅永远不弹（实测：日志里 {@code push_stalled reason=DESTINATION_REFUSED} 与
     * {@code machine_full} 一直在刷，而快照 {@code shortage.banners.sent} 整场为空）。</p>
     *
     * <p><b>为什么不能直接放宽 {@code pushStalledOnDestination()}</b>：它同时是
     * {@code fillInternalForBus} 的「停手」闸门 —— 放宽会重新引入 2026-10-05 修掉的
     * 「齿轮只堵一个、另外一个也停工」。因此这里另给它一个<b>不带聚合口径</b>的只读入口，
     * 供<b>提示层</b>单独使用：谁堵了就报谁，与「整仓要不要停手」解耦。</p>
     *
     * <p>过期的拒收不算「还在堵」（与 {@link #PUSH_STALL_WINDOW_TICKS} 同一窗口），
     * 因此推送一旦成功、或停手窗口滑过，本方法自动回到 {@code -1}，提示的<b>恢复边沿</b>自然成立。</p>
     */
    public long rscc$destinationRefusalAge(@org.jetbrains.annotations.Nullable final BlockPos target) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || target == null) {
            return -1L;
        }
        final Long at = refusedTargetAt.get(target.immutable());
        if (at == null) {
            return -1L;
        }
        final long age = level.getGameTime() - at;
        return age > PUSH_STALL_WINDOW_TICKS ? -1L : age;
    }

    /**
     * <b>只读</b>：这个目的地此刻是不是「正在拒收我们的推送」（供提示层使用，见
     * {@link #rscc$destinationRefusalAge(BlockPos)} 的完整说明）。
     */
    public boolean rscc$destinationStuck(@org.jetbrains.annotations.Nullable final BlockPos target) {
        return rscc$destinationRefusalAge(target) >= 0L;
    }

    /**
     * <b>只读</b>：本仓此刻有没有<b>任意一个</b>目的地正在拒收推送。
     *
     * <p>与 {@link #pushStalledOnDestination()} 的唯一区别：那个方法要求
     * <b>{@code refusedTargetAt.size() >= 2}</b>（两台以上同时拒收），因此
     * <b>只有一台机器 / 一个置物台的现场恒为 false</b>；本方法对<b>任意一个</b>有效拒收就返回 true，
     * 专供<b>提示层</b>使用。两者的分工：</p>
     * <ul>
     *     <li>{@code pushStalledOnDestination()} —— <b>整仓要不要停手</b>（{@code fillInternalForBus}
     *     的闸门）。这里必须保留「≥2」口径，否则会重新引入 2026-10-05 修掉的
     *     「齿轮只堵一个、另外一个也停工」。</li>
     *     <li>本方法 —— <b>要不要给玩家弹提示</b>。谁堵了就说谁，与「整仓停手」解耦。</li>
     * </ul>
     */
    public boolean rscc$anyDestinationStuck() {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || refusedTargetAt.isEmpty()) {
            return false;
        }
        final long now = level.getGameTime();
        for (final Long at : refusedTargetAt.values()) {
            if (at != null && now - at <= PUSH_STALL_WINDOW_TICKS) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：窗口内「最近一次拒收我们的那台目的地」坐标（没有 = {@code null}）。
     *
     * <p>为什么不直接用 {@link #rscc$blockedTargetPos()}：那是 {@code lastRefusedTarget}，而成功推送
     * 只清 {@code refusedTargetAt}、<b>不清</b>它 ⇒ 它可能指向一台「后来已经被成功推送过」的旧目标。
     * 看门狗的「零进展」判据必须拿<b>此刻真的还在拒收</b>的那一格
     * （见 {@code AssemblyWatchdog#stallProgressOf}），否则会去看错工位、把别人的进度当成自己的。</p>
     *
     * <p>多台目标同时拒收时返回<b>时间戳最新</b>的那一个（与热探针「谁刚拒收就判谁」同一口径）。
     * 只读，不改任何状态、不搬运任何资源。</p>
     */
    @org.jetbrains.annotations.Nullable
    public BlockPos rscc$latestRefusedTarget() {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || refusedTargetAt.isEmpty()) {
            return null;
        }
        final long now = level.getGameTime();
        BlockPos latest = null;
        long latestAt = Long.MIN_VALUE;
        for (final Map.Entry<BlockPos, Long> entry : refusedTargetAt.entrySet()) {
            final Long at = entry.getValue();
            if (at == null || now - at > PUSH_STALL_WINDOW_TICKS) {
                continue;
            }
            if (at > latestAt) {
                latestAt = at;
                latest = entry.getKey();
            }
        }
        return latest;
    }

    /**
     * <b>只读</b>：窗口内「<b>全部</b>还在拒收本仓推送」的目的地坐标（时间戳最新在前；空表 = 没有）。
     *
     * <h2>为什么需要它（2026-10-06 第三次：两条配方共用一台机器时的误挂起）</h2>
     * <p>装配看门狗的「零进展」判据原先只问 {@link #rscc$latestRefusedTarget()} 这<b>一格</b>
     * （时间戳最新的那一格）。当<b>两条配方共用同一批工位</b>时（实测：机械手 {@code create:deploying}
     * 同时承担 {@code precision_mechanism#0..2} 与 {@code track#0..1}），拒收会在两台置物台之间
     * <b>交替</b>发生（{@code -6,-60,5} 与 {@code -6,-60,6} 两条输出总线各自推 {@code create:golden_sheet}），
     * 于是「时间戳最新的那一格」每几秒就换一台 —— 看门狗拿 A 格的旧指纹去比 B 格的新内容，
     * 比对必然「没变」，「别处已经成功推过」「另一格已经被取走」这两件事都看不见，
     * 最终把「机器正忙着做另一条配方的件」判成了「堵死了」并挂起（用户实测：点「继续」后一切正常）。</p>
     *
     * <p>因此这里把「此刻仍在拒收的那些格子」<b>如实全部交出</b>，让看门狗<b>逐格</b>比对
     * （它自己负责记住每格在窗口起点的内容指纹）。判据与本方法同源：与
     * {@link #PUSH_STALL_WINDOW_TICKS} 同一窗口，过期的不算；只读，不改任何状态、不搬运任何资源。</p>
     */
    public java.util.List<BlockPos> rscc$refusedTargets() {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || refusedTargetAt.isEmpty()) {
            return java.util.List.of();
        }
        final long now = level.getGameTime();
        final java.util.List<Map.Entry<BlockPos, Long>> alive = new java.util.ArrayList<>();
        for (final Map.Entry<BlockPos, Long> entry : refusedTargetAt.entrySet()) {
            final Long at = entry.getValue();
            if (at == null || now - at > PUSH_STALL_WINDOW_TICKS) {
                continue; // 过期的拒收不算「还在堵」（与 rscc$latestRefusedTarget 同一口径）
            }
            alive.add(entry);
        }
        // 与 rscc$latestRefusedTarget「谁刚拒收就判谁」同一取舍：时间戳最新在前，顺序稳定。
        alive.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        final java.util.List<BlockPos> out = new java.util.ArrayList<>(alive.size());
        for (final Map.Entry<BlockPos, Long> entry : alive) {
            out.add(entry.getKey());
        }
        return java.util.List.copyOf(out);
    }

    /**
     * 兼容旧签名（只记时刻、不记坐标/资源）。保留是为了不破坏既有调用点与自检锚点。
     */
    public void rscc$noteDestinationRefusal() {
        rscc$noteDestinationRefusal(null, null);
    }

    /**
     * 记录一次「推不动」的原因（只写诊断字段，不搬运 / 不改任何判定输入）。
     * <p>原因签名变化时打一条 {@code [rscc-assembly]}，于是「第一次为什么推不动」永远看得见；
     * 持续同一原因不重复刷屏（由 {@link RsccAssemblyDebug#repeat} 的 5 秒合并闸兜底）。</p>
     */
    private void noteStall(final StallReason reason, final String resource) {
        stallReason = reason;
        stallResource = resource == null ? "-" : resource;
        final String signature = reason.name() + "|" + stallResource;
        if (!signature.equals(stallSignature)) {
            stallSignature = signature;
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " push_stalled reason=" + reason.name()
                    + " resource=" + stallResource
                    + " (目的地持续拒收 = 机器满 / 不接受；某步无机器认领 = 缺机器 / 缺样板)"
                    + " destinationRefusalAge=" + ageOf(destinationRefusalAt)
                    + " stepOwnerMissingAge=" + ageOf(stepOwnerMissingAt));
            }
        }
    }

    /** 该时间戳距今多少 tick（从未发生过返回 {@code -1}）。 */
    private long ageOf(final long stamp) {
        final Level level = getLevel();
        if (level == null || stamp == NEVER) {
            return -1L;
        }
        return level.getGameTime() - stamp;
    }

    /**
     * <b>只读诊断</b>：此刻「推不动」的<b>具体原因</b>（{@code null} = 没有推不动）。
     *
     * <p>看门狗用它把「机器满」与「缺机器 / 缺样板」分成两种 reason 与两种横幅文案 ——
     * 旧实现只有一个布尔值，于是无论哪种原因，玩家看到的都是「执行器掉线」。</p>
     */
    @org.jetbrains.annotations.Nullable
    public StallReason rscc$stallReason() {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || stallReason == null) {
            return null;
        }
        return pushStalledOnDestination() ? stallReason : null;
    }

    /** <b>只读诊断</b>：最近一次「推不动」涉及的资源（物品注册名 / {@code fluid:…}；未知 {@code "-"}）。 */
    public String rscc$stallResource() {
        return stallResource == null ? "-" : stallResource;
    }

    /**
     * <b>只读</b>：最近一次被下游拒收的供料目标坐标（从未发生过返回 {@code null}）。
     *
     * <h2>为什么需要它（2026-10-06；提示链路的恢复边沿）</h2>
     * <p>装配看门狗要回答「下游是不是又能收料了」。它<b>不能</b>用「拒收证据过期了」当答案：
     * 任务一旦被挂起，本仓的 {@code isAutoCraftingEnabled()} 即为假 ⇒ 本仓<b>不再尝试推送</b>，
     * 而且仓内物品已被退回网络 ⇒ 证据必然过期 —— 那是「我们没在推」，不是「下游能收」。
     * 唯一可验证的事实是：<b>上次堵住我们的那个工位现在空不空</b>。坐标由此提供，
     * 与堵塞自愈 {@code recoverBlockingTargetItem()} 用的是<b>同一个</b>字段（{@code lastRefusedTarget}），
     * 因此不会出现「自愈认的坐标」与「提示认的坐标」两套真源。</p>
     * <p>只读，不改任何状态、不搬运任何资源。</p>
     */
    @org.jetbrains.annotations.Nullable
    public BlockPos rscc$blockedTargetPos() {
        return lastRefusedTarget;
    }

    // ==================== 堵塞自愈：把占住供料目标的那一件收回来 ====================

    /**
     * <b>把占住供料目标（置物台 / 机器）的那一件收回本仓</b>（2026-10-05 新增；只读判定 + 真实搬运）。
     *
     * <h2>它解决什么（用户实机的齿轮 / 废料堵塞）</h2>
     * <p>Create 的置物台一次只放一件。精密构件装配失败时废料（例如<b>小齿轮</b>）会留在置物台上；
     * 小齿轮又恰好是后面某一步的投入物 ⇒ 输出总线想把「小齿轮」推上去时被拒收
     * （置物台手里已经有一件）⇒ 本仓被判「推不动」⇒ 看门狗弹「机器满了」并挂起。
     * 而机器其实是空的，那只是<b>一件占位的废料</b>。玩家手动拿走就恢复正常 —— 这正是要自动化的事。</p>
     *
     * <h2>为什么只能由本仓做（不能指望输入总线）</h2>
     * <p>输入总线走 RS 的过滤器（类别勾选）；一件「不在任何类别里」的废料不会命中它的过滤器，
     * 因此实测日志里它只输出 {@code took=- result=RECLAIM_ONLY} —— 一条都没真正收走。
     * 本仓则可以直接读相邻供料目标的物品能力（与缺料判定共用
     * {@link RsccChamberImportStrategy#itemHandlerAt} 同一口径）。</p>
     *
     * <h2>保守边界（绝不抢别人的料）</h2>
     * <ol>
     *     <li>只处理<b>最近一次真的拒收过</b>的那个坐标（{@link #lastRefusedTarget}），不扫全部目标；</li>
     *     <li>只回收「<b>本仓拥有的配方</b>的过渡件（未完成件）」或「<b>本仓输出类别里的废料</b>」
     *     —— 即「这一件本来就属于本产线」；别人的料、别的模组的物品一律不碰；</li>
     *     <li>回收量一次一件（置物台只有一件），且优先匹配 {@link #lastRefusedItem}；</li>
     *     <li>任何一步判不出来（无世界 / 无能力 / 非本产线物品）⇒ 什么都不做，维持既有行为。</li>
     * </ol>
     * <p>回收路径与其它收集完全一致：取出 → 进本仓内部存储 → 由既有回网逻辑写回网络
     * （并记 {@link RsccFlowLedger#fromMachine} 账，守恒不破）。</p>
     *
     * @return 实际收回的件数（0 = 没东西可收 / 不属于本产线）
     */
    private int recoverBlockingTargetItem() {
        final Level level = getLevel();
        final BlockPos target = lastRefusedTarget;
        if (level == null || level.isClientSide() || target == null) {
            return 0;
        }
        // 拒收证据的<b>新鲜度</b>决定这条通道是否还成立：
        //   * 60 tick 内（判定窗口）：仍在「推不动」状态，属于持续故障 ⇒ 必须收；
        //   * 60~200 tick：可能<b>任务已经结束 / 玩家取消了</b> —— 此时输出总线不再尝试，
        //     拒收时间戳自然变旧，但那一件<b>还压在置物台上</b>（用户实测：取消后金板回网、
        //     中间产物留在置物台上堵着）。收尾窗口就是为这一种情形留的。
        //   * 超过 200 tick：坐标可能早已被别人重新用上 ⇒ 不碰（保守）。
        final long age = level.getGameTime() - destinationRefusalAt;
        if (age > REFUSED_TARGET_RECOVER_TICKS) {
            lastRefusedTarget = null;
            lastRefusedItem = null;
            return 0;
        }
        final boolean withinStall = age <= PUSH_STALL_WINDOW_TICKS;
        final net.neoforged.neoforge.items.IItemHandler handler =
            cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy.itemHandlerAt(level, target);
        if (handler == null) {
            // 目的地<b>没有物品能力</b>（例如 Create 的机械手 / 工作盆不暴露 IItemHandler，
            // 或该坐标根本不是容器）⇒ 本仓无法把它取回来。这种情况必须让玩家看得见，
            // 否则「说会自愈但从没自愈」永远查不出来。
            if (RsccAssemblyDebug.changed("unblock_no_handler@" + RsccAssemblyDebug.at(worldPosition)
                    + "#" + RsccAssemblyDebug.at(target), stallSignature)) {
                RsccAssemblyDebug.warn("unblock_no_handler@" + RsccAssemblyDebug.at(worldPosition)
                        + "#" + RsccAssemblyDebug.at(target),
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " unblock_unavailable target=" + RsccAssemblyDebug.at(target)
                        + " reason=target_has_no_item_handler"
                        + " (该工位不暴露物品能力 ⇒ 本仓取不回来，只能玩家手动清空该工位)");
            }
            return 0;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || !belongsToMyPipeline(inSlot)) {
                continue;
            }
            // <b>关键条件 ②（2026-10-05 用户第三次纠正后重写 —— 这次才是对的判据）</b>
            //
            // 用户原话：<i>「不同输出总对应的是不同的地方。就比如说这个机械手的一个输出总线它对着机械手，
            // 它只负责供给输入原料；另一个总线对着那个置物台，它只负责输出这个原料和中间产物。
            // 那么我问你，齿轮他妈的到了置物台上面，它还是不是正常的？肯定不是啊，
            // 那肯定就是有问题，那肯定就不包含在那个置物台的那一个正常的输出列表之中，
            // 所以它肯定得被回收啊」</i>。
            //
            // 我前两版都判错了：一次用「本仓某一步会不会施加它」，一次用「工位在制件的步序要不要它」——
            // 两者都是<b>全仓口径</b>，把两台机器的职责混着看了。
            //
            // 正确判据（本方法）：<b>只问「拥有这台工位的那条输出总线」自己的类别清单</b>——
            //   * 这件在该总线的类别里 ⇒ 它本来就该出现在这台机器上 ⇒ 留着；
            //   * 不在 ⇒ 它不在这台机器的正常输出列表里 ⇒ <b>就是堵塞物，必须收回</b>。
            // 权威数据是 {@link #busCategoryOwners}（类别 → 拥有它的总线坐标），与推料侧用的是同一张表。
            if (busTargetAccepts(target, inSlot.getItem())) {
                continue;
            }
            // <b>关键保守条件 ③</b>（2026-10-05 用户实测「无限产生未完成件」）：
            // <b>过渡件（未完成件）一律不回收</b>。它是要继续加工的在制品，
            // 收进本仓等于把产线往回拽一步 —— 实测表现为「装金板那一步的未完成件被收回 →
            // 仓里再做一份 → 再被收回」，一件都装不上。
            if (isTransitionOfMyRecipes(inSlot.getItem())) {
                continue;
            }
            // <b>关键保守条件</b>：只有「置物台手里那件 ≠ 我们刚才想推的那件」时才算「被占住」。
            // 若两者相同，说明这是「机械手刚把过渡件放回置物台、总线同时想推同一件」的正常竞态
            // （Create 的工作盆 / 置物台一次只放一件），此时把它收走等于把机械手的活抹掉，
            // 会让产线来回搬运 —— 因此这种情况一律不碰，交给正常流程。
            // <b>收尾窗口放宽</b>：任务已经结束（不在判定窗口内）时不可能再有机械手推进，
            // 因此即便「手里那件 == 我方想推的那件」，也应当收回 —— 否则取消后它永远堵着。
            if (withinStall && lastRefusedItem != null && inSlot.getItem() == lastRefusedItem) {
                continue;
            }
            // 一次一件（供料目标上本来就只压着一件）；先模拟抽取，避免「抽一半又放回去」的抖动
            final ItemStack simulated = handler.extractItem(slot, 1, true);
            if (simulated.isEmpty()) {
                continue;
            }
            final int accepted = canAcceptTransitionLocally(simulated);
            if (accepted <= 0) {
                // 本仓也装不下：留给既有回收路径（输入总线 / 玩家），绝不销毁
                return 0;
            }
            final ItemStack taken = handler.extractItem(slot, accepted, false);
            if (taken.isEmpty()) {
                return 0;
            }
            final int leftover = acceptTransitionLocally(taken);
            final int moved = taken.getCount() - leftover;
            if (moved <= 0) {
                // 极端情况：抽出来了却收不进去 ⇒ 原样还回去（绝不吞掉玩家资源）
                handler.insertItem(slot, taken, false);
                return 0;
            }
            setChanged();
            // 堵塞已解除 ⇒ 清掉拒收证据，避免「收回了却还在报推不动」
            destinationRefusalAt = NEVER;
            lastRefusedTarget = null;
            lastRefusedItem = null;
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " unblock_recovered {" + describeItem(taken) + "} from="
                    + RsccAssemblyDebug.at(target)
                    + " reason=target_held_item_blocking_next_push"
                    + " (占住供料目标的废料 / 过渡件已收回本仓，随后按既有回流写回网络)");
            }
            return moved;
        }
        // 找到了目标、却没有任何「本产线的件」可收：分两种情形，都必须留下可查的证据 ——
        //   * 工位是空的 / 装的是别人的东西 ⇒ 与堵塞无关（安静返回即可）；
        //   * 工位装的是本产线的件、但都属于「我方想推的那一件」⇒ 竞态保护拦下了（也安静）。
        // 真正需要告警的是「有件、但我方判定不属于本产线」——那往往意味着判定口径漏了一种废料。
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || belongsToMyPipeline(inSlot)) {
                continue;
            }
            if (RsccAssemblyDebug.changed("unblock_foreign@" + RsccAssemblyDebug.at(worldPosition)
                    + "#" + RsccAssemblyDebug.itemId(inSlot.getItem()), stallSignature)) {
                RsccAssemblyDebug.warn("unblock_foreign@" + RsccAssemblyDebug.at(worldPosition)
                        + "#" + RsccAssemblyDebug.itemId(inSlot.getItem()),
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " unblock_skipped target=" + RsccAssemblyDebug.at(target)
                        + " holds=" + RsccAssemblyDebug.itemId(inSlot.getItem())
                        + " reason=not_my_pipeline"
                        + " (工位上有件但不属于本仓这条产线 ⇒ 保守不动；若它确实堵住了本产线请反馈此件名)");
            }
            return 0;
        }
        return 0;
    }

    /**
     * <b>只读</b>：这一件是否属于本仓这条产线（决定堵塞自愈能不能碰它）。
     *
     * <p>判据只有两条，任何一条命中即可：</p>
     * <ol>
     *     <li><b>本仓认领配方的过渡件</b>（未完成件）—— 压在工位上的在制件本来就是本产线的；</li>
     *     <li><b>本仓输出类别里的废料 / 成品</b> —— 装配失败留下的小齿轮正是这一类，也正是
     *     用户实机里堵住置物台的那一件。</li>
     * </ol>
     * <p>明确<b>不</b>包含「别的配方 / 别的模组的物品」：那些不是本仓的，碰了就是抢料。</p>
     */
    private boolean belongsToMyPipeline(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // ① 本产线的未完成件（按「配方产物名 + incomplete_/unprocessed_ 前缀」反查）
        if (isTransitionOfMyRecipes(stack.getItem())) {
            return true;
        }
        // ② 本仓输出类别里的物品（废料 / 成品；输入类别不算 —— 那是「还没用掉的料」）
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isInput()) {
                continue;
            }
            if (info.items().contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：该物品是不是「本仓认领配方」的未完成件（按注册名判定，与 Create 的命名规则一致）。
     *
     * <p>判定方式：把本仓 {@link #ownedSteps()} 里每条配方的输出物取出来，看它的
     * {@code incomplete_} / {@code unprocessed_} 前缀变体是否等于该物品 —— 这是
     * 「这件是不是我这条产线的在制件」最稳的判据（不依赖数据组件是否还在）。</p>
     */
    /**
     * <b>只读（公开入口）</b>：该物品是不是本仓配方的「过渡件 / 未完成件」。
     *
     * <p>供输出总线的「工位已被别的在制件占住 ⇒ 不推过渡件」闸门使用
     * （见 {@code RsccChamberExportStrategy#transferItem}）。判据本体就是
     * {@link #isTransitionOfMyRecipes}，不写第二套。</p>
     */
    public boolean isTransitionItem(final net.minecraft.world.item.Item item) {
        return item != null && isTransitionOfMyRecipes(item);
    }

    private boolean isTransitionOfMyRecipes(final net.minecraft.world.item.Item item) {
        final String id = RsccAssemblyDebug.itemId(item);
        if (id == null) {
            return false;
        }
        // <b>2026-10-06 决定性修复：改用配方的 {@code transitional_item} 判定，不再猜名字。</b>
        //
        // 旧实现只做「名字后缀」匹配：
        // <pre>
        //   stripped = id.replace("create:incomplete_", "").replace("create:unprocessed_", "")
        //   recipeId.endsWith(stripped)          // ← 猜
        // </pre>
        // 三条样板配方里它<b>只对两条成立</b>：
        // <pre>
        //   precision_mechanism ← incomplete_precision_mechanism   ✓ 后缀命中
        //   track               ← incomplete_track                 ✓ 后缀命中
        //   sturdy_sheet        ← unprocessed_obsidian_sheet       ✗ 后缀不命中！
        // </pre>
        // 于是 {@code isTransitionItem(未加工黑曜石板)} <b>恒为 false</b>，连锁后果两条：
        // <ol>
        //     <li>{@link #isMyProductOrScrap} 里那句「过渡件立即 bail-out」<b>对坚固板不生效</b>
        //     ⇒ 坚固板在制品被判成「本仓产物」⇒ {@code RsccChamberImportStrategy} 机器侧第一条子句
        //     <b>在任务运行期间也放行</b> ⇒ 件刚推上置物台就被抄回网络（用户实测「冲压一次，
        //     然后就回去了」的真正机制；也让此前所有回收侧闸门对坚固板全部失效）；</li>
        //     <li>{@code track} / 精密构件的过渡件名字命中 ⇒ 机器侧永不放行 ⇒ 取消后仍压在工位、
        //     占死机器唯一的加工位。</li>
        // </ol>
        // <b>两个方向相反的错来自同一个判据</b>，因此这里换成数据驱动的唯一事实源：
        // Create 配方自己的 {@code getTransitionalItem()}（见 {@link #assemblyById}）。
        final Level level = getLevel();
        if (level == null) {
            return false; // 判不了 → 保守「不是」（与旧行为一致，不因判不出来而改变搬运）
        }
        for (final String recipeId : ownedSteps().keySet()) {
            if (recipeId == null || recipeId.isEmpty()) {
                continue;
            }
            final SequencedAssemblyRecipe recipe = assemblyById(level, recipeId);
            if (recipe != null && recipe.getTransitionalItem().getItem() == item) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：这件东西是不是「本仓产线的产物 / 废料」（= 本仓自己推出去、理应收回来的那些）。
     *
     * <p>用途：本仓<b>没有活跃订单</b>时，输入总线在<b>机器侧</b>只允许回收这一类东西
     * （见 {@code RsccChamberImportStrategy} 的 {@code noOrder} 分支）。判据 =
     * {@link #belongsToMyPipeline} 且<b>不属于</b>本仓的输入类（投入物 / 起步原料）。
     * 于是「玩家往空闲的冲压机上放一个铁锭」既不是产物也不是废料 ⇒ 本仓一个字节都不碰，
     * 那台机器照常按它自己的配方工作（用户明确要求的「像一台正常的机器一样」）。</p>
     */
    public boolean isMyProductOrScrap(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !belongsToMyPipeline(stack)) {
            return false;
        }
        // <b>2026-10-05 用户实测的「无限产生未完成件」修复</b>：
        // <b>过渡件（未完成件）绝不是「产物 / 废料」</b>，它是在制品，必须留在机器上继续加工。
        //
        // 实测形态（用户原话：「下单之后我只下了一个，他一直在那里就输出精板，然后想装的时候
        // 估计就直接被收回了，只有很少几个被装上了，然后回流到网络之中，然后就继续这样子」）：
        // 被推给机械手的「装金板」那一步的未完成件，被本判据当成「本仓产物」⇒ 输入总线立刻把它
        // 抄回网络 ⇒ 仓里又做一份新的 ⇒ 再抄回 …… 于是无限产生未完成件，而一件都装不上。
        if (isTransitionOfMyRecipes(stack.getItem())) {
            return false;
        }
        // 输入类（本仓任何一条配方的投入物 / 起步原料）不算「产物 / 废料」：
        // 它们出现在机器上只可能是「玩家自己在用那台机器」或「还没轮到喂的备料」，都不该被无单抄走。
        if (isInputMaterial(stack.getItem()) || isStartIngredient(stack.getItem())) {
            return false;
        }
        return true;
    }

    /**
     * <b>只读</b>：这件东西是不是「本仓某一步<b>本来就该用</b>的投入物」。
     *
     * <h2>为什么堵塞自愈必须问这一句（2026-10-05 实机日志：自愈反而制造往返）</h2>
     * <p>用户新构建的日志里，同一个机械手工位在 5 秒内出现
     * 「push → unblock_recovered → 再 push」共 6 次。查明原因：机械手的「手里那件」是
     * <b>Create 的正常放置结果</b>（上一步做完的过渡件交到本工位、或是本步要施加的那件），
     * 而旧的自愈只看「它不匹配我正要推的那一件」就把它收回 —— 等于把机械手刚摆好的活抹掉，
     * 下一步又得重新推一遍，于是来回搬运。</p>
     *
     * <p>判据（只认 Create 配方数据，不猜）：遍历本仓负责的每个 (配方, 步)，取
     * {@link SequencedRecipeProbe#stepApplicationCandidates}（该步机械手要<b>施加</b>的那件，
     * 例如「装齿轮」步 = 齿轮 / 大齿轮），命中即认为「它待在这里是合理的」⇒ <b>不碰</b>。</p>
     */
    private boolean isAppliedByMySteps(final net.minecraft.world.item.Item item) {
        if (item == null) {
            return false;
        }
        final Level level = getLevel();
        if (level == null) {
            return false;
        }
        for (final Map.Entry<String, Set<Integer>> entry : ownedSteps().entrySet()) {
            final SequencedAssemblyRecipe recipe = assemblyById(level, entry.getKey());
            if (recipe == null) {
                continue;
            }
            final List<SequencedRecipe<?>> sequence = recipe.getSequence();
            for (final int step : entry.getValue()) {
                if (step < 0 || step >= sequence.size()) {
                    continue;
                }
                if (SequencedRecipeProbe.stepApplicationCandidates(sequence.get(step).getRecipe())
                    .contains(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * <b>清空「推不动」证据</b>（不含搬运；只清诊断时间戳）。
     *
     * <h2>为什么必须在「新订单开始」时清（2026-10-05 实机取证的头号修复）</h2>
     * <p>复现链（两份快照 + 日志逐行比对得出）：</p>
     * <ol>
     *     <li>玩家在<b>没有订单</b>的时候用定量保持器补货，期间本仓观察到一次
     *     「目的地拒收」或「某步没机器认领」⇒ 记下时间戳；</li>
     *     <li>{@link #fillInternalForBus} 因此整段停手（{@code pushStalledOnDestination()} 为真）；</li>
     *     <li>玩家下单 —— 门控刚开（{@code gate … relevant=true}），
     *     <b>但那个时间戳还没过期</b>（窗口 60 tick）；</li>
     *     <li>看门狗 1 秒扫一次，读到 {@code pushStalledOnDestination()=true} ⇒ 直接判
     *     {@code EXECUTOR_OFFLINE} 并挂起（实测 {@code stall=41}，即不到 1 秒）；
     *     而 {@code offlineSteps="-"}、{@code missingMaterials=0}、料也够 —— 机器一台没掉、料一份不缺；</li>
     *     <li>本仓被冻结 ⇒ 永不尝试 ⇒ 时间戳永续 ⇒ 任务永远起不来（自锁）。</li>
     * </ol>
     * <p>「新订单开始」这句话必须能被判定：门控由「无相关任务」翻到「有相关任务」的那一刻就是它。
     * 那一刻清空证据是<b>语义正确</b>的 —— 上一段空闲期的观察不能用来判定一条刚下单的任务。</p>
     */
    public void clearPushStallEvidence() {
        destinationRefusalAt = NEVER;
        stepOwnerMissingAt = NEVER;
        // 按目标的记录一并清掉（否则「清证据」只清掉全仓那一个时间戳，
        // 逐目标判定仍会用旧记录判「所有目标都在拒收」）。
        refusedTargetAt.clear();
        lastSuccessfulPushAt = NEVER;
        stallReason = null;
        stallResource = "-";
        stallSignature = "";
    }

    /**
     * <b>只读</b>：本仓此刻是否处于「推不动」的持续状态 —— 目的地持续拒收（机器满 / 不接受），
     * 或某一步没有任何在线执行仓认领。
     *
     * <p>窗口 {@value #PUSH_STALL_WINDOW_TICKS} tick（3 秒）：机器偶尔满一瞬不会被当成持续状态。
     * 装配看门狗据此把原因归类为 {@code EXECUTOR_OFFLINE} / {@code OUTPUT_BLOCKED}，
     * 于是既有的完成横幅会明确报出原因，玩家不再莫名干等。只读，不改任何状态。</p>
     *
     * <p><b>⚠ 写法上的硬要求（2026-10-05 的严重 bug）</b>：判定必须是
     * {@code now - stamp <= WINDOW} 且 {@code stamp != NEVER}，<b>绝不能</b>用
     * {@code Long.MIN_VALUE} 当哨兵 —— 相减会溢出回绕成负数，而负数恒 ≤ 窗口，
     * 于是「从未发生」被当成「刚刚发生」，每一台仓从加载起就恒定报告推不动。
     * 见 {@link #NEVER} 的说明。</p>
     */
    public boolean pushStalledOnDestination() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        final long now = level.getGameTime();
        // <b>按目标判定</b>（2026-10-05 用户实测：「齿轮只堵一个的时候另外一个也停工了」）：
        // 「某一步没有任何仓认领」依旧是全仓性质（与目标无关），照旧直接成立；
        // 而「目的地拒收」必须问「是不是<b>所有</b>目标都在拒收」——
        // 只要还有任一台机器在这段时间里收下过东西，本仓就没有整体堵住。
        if (now - stepOwnerMissingAt <= PUSH_STALL_WINDOW_TICKS) {
            return true;
        }
        if (now - destinationRefusalAt > PUSH_STALL_WINDOW_TICKS) {
            return false; // 窗口内一次拒收都没有
        }
        return allKnownTargetsRefusing(now);
    }

    /**
     * <b>只读</b>：窗口内被拒收过的目标是不是「本仓已知的全部目标」。
     *
     * <p>判定顺序：</p>
     * <ol>
     *     <li>窗口内<b>有过成功投料</b> ⇒ 立刻判「没堵」（下游至少有一台在收）；</li>
     *     <li>把所有超过窗口的旧记录清掉，若仍有在窗口内的目标 ⇒ 看有没有任何目标<b>没有</b>拒收记录：
     *     有 ⇒ 没堵（它还能收）；</li>
     *     <li>已知目标集合里<b>全部</b>都在窗口内拒收 ⇒ 才算真堵。</li>
     * </ol>
     */
    private boolean allKnownTargetsRefusing(final long now) {
        if (lastSuccessfulPushAt != NEVER && now - lastSuccessfulPushAt <= PUSH_STALL_WINDOW_TICKS) {
            return false; // 刚刚还投进去过东西 ⇒ 下游没整体堵住
        }
        refusedTargetAt.entrySet().removeIf(entry -> now - entry.getValue() > PUSH_STALL_WINDOW_TICKS);
        // 落在窗口内的拒收目标：只有一个 ⇒ <b>不判整仓堵</b>。
        // 为什么「一个就不算」（用户实测原话：「齿轮只堵一个的时候另外一个也停工了」）：
        // 本仓常接多台机器，一台手里压着件、另一台空着能收 —— 那不是「下游整体堵住」，
        // 而是「那一台需要清一下」。整仓停手（不备料 / 不投料 + 看门狗挂起）属于误伤；
        // 真堵（所有目标都不收）时，下面这条 `<= 1` 也不会成立，因为那时记录数会 ≥ 2。
        return refusedTargetAt.size() >= 2;
    }

    /** 只读：本仓是否观察到「某一步没有任何在线执行仓认领」（横幅据此把话说成「缺机器 / 缺样板」）。 */
    public boolean pipelineStepOwnerMissing() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        return level.getGameTime() - stepOwnerMissingAt <= PUSH_STALL_WINDOW_TICKS;
    }

    /**
     * <b>只读</b>：这个类别是否属于「本仓真正拥有」的步骤（第 20 轮回归 A 的硬底线闸门）。
     *
     * <p>带 {@code (配方, 步序)} 的类别（即 {@code intermediate:<配方>:<步序>}）必须命中
     * {@link #ownedSteps()}；**没命中就一律不生成备料 / 收料需求**，从而保证
     * 「总样板补出来的<b>显示用</b>类别即使被勾中，也不会让本仓在没有订单时开工」。</p>
     *
     * <p>不带配方信息的类别（{@code input:} / {@code fluid:} / {@code result:} / {@code scrap:}）
     * 一律放行 —— 它们是既有口径（玩家把某个输入 / 成品类别勾到总线上，就代表要喂 / 要收它），
     * 不属于本轮收紧的对象。</p>
     */
    private boolean categoryOwned(final BusCategoryInfo info) {
        final String recipe = info.recipe();
        final int step = info.step();
        if (recipe == null || recipe.isEmpty() || step < 0) {
            return true;
        }
        if (!ownedSteps().getOrDefault(recipe, Set.of()).contains(step)) {
            return false;
        }
        // <b>按配方闸门</b>（2026-10-05）：本仓负责这条配方，但<b>此刻没有人下单</b>它 ⇒ 不生成备料需求。
        // 用户实测：一仓放「列车轨道 + 精密构件」两张样板时，为精密构件下单会把列车轨道的
        // 石头台阶 / 铁粒也一并拉进来（于是仓里出现列车轨道的起步原料，进而开始装没人下单的列车轨道）。
        // 只有「本仓负责 且 真的有活跃订单」的 (配方, 步) 才准补料 —— 与开件用的是同一把闸门。
        return recipeOrdered(recipe);
    }

    /**
     * <b>只读</b>：该输入类别所属的配方此刻有活跃订单吗。
     *
     * <p>输入类别的 {@code BusCategoryInfo#recipe()} 是<b>空串</b>（见 computeBusCategories 里
     * {@code new BusCategoryInfo(entry.getKey(), stack.getItem(), null, "", ...)}），因此上面那道
     * {@link #categoryOwned(BusCategoryInfo)} 对它们恒为真、拦不住任何东西。配方 id 夹在
     * <b>类别 id</b> 里（{@link #inputCategoryIdForRecipe}：{@code input:<配方>#<物品>}），
     * 这里把它取出来问 {@link #recipeOrdered(String)}。</p>
     *
     * <p>取不到配方 id（手工样板 / 老存档 / 旧格式 id）时<b>回退按物品归属判定</b>：
     * 看本仓负责的哪几条配方把该物品当投入物，<b>只要其中任意一条有活跃订单就放行</b>
     * （任何一条有单 ⇒ 这条输入确实被需要）；<b>一条都没有</b> ⇒ 拦下。</p>
     *
     * <p><b>为什么必须回退而不是直接放行</b>（2026-10-05 快照实测）：老存档 / 上一版本的类别 id 是
     * 旧格式（{@code input:minecraft:iron_nugget}，没有配方段），若直接返回 {@code true}，
     * 这道闸门对老数据<b>等于不存在</b> —— 用户看到的「没下单列车轨道却还在 pull stone_slab」
     * 正是这条路径。回退判定既修好老数据，又不会误停「真的有人下单」的输入。</p>
     */
    private boolean categoryRecipeOrdered(final BusCategoryInfo info) {
        final String id = info.id();
        if (id == null || !id.startsWith(RsccBusCategory.INPUT_PREFIX)) {
            return true; // 只对「输入原料」类别做这道细化
        }
        final int hash = id.indexOf('#');
        if (hash >= 0) {
            // 新格式：id 自带配方段，直接问那一条
            final String recipe = id.substring(RsccBusCategory.INPUT_PREFIX.length(), hash);
            return recipeOrdered(recipe);
        }
        // 旧格式：按「该物品在本仓哪几条配方里是投入物」回退判定
        return anyRecipeDeclaringOrdered(info.items());
    }

    /**
     * <b>只读</b>：给定物品集合里，有没有任意一件被本仓某条<b>有活跃订单</b>的配方当作投入物。
     *
     * <p>判据来源 = 本仓每条配方该步的 {@link SequencedRecipeProbe#assemblyStepInputGroups} 候选集合
     * （与 {@code computeBusCategories} 登记输入类别时同一份数据），因此不存在第二套口径。</p>
     */
    private boolean anyRecipeDeclaringOrdered(final List<Item> items) {
        if (items == null || items.isEmpty()) {
            return true; // 没有物品信息 ⇒ 不作判断
        }
        final Level level = getLevel();
        if (level == null) {
            return true;
        }
        for (final Map.Entry<String, Set<Integer>> entry : ownedSteps().entrySet()) {
            if (!recipeOrdered(entry.getKey())) {
                continue; // 这条配方没单：它声明的投入物不构成「需要」
            }
            final SequencedAssemblyRecipe recipe = assemblyById(level, entry.getKey());
            if (recipe == null) {
                continue;
            }
            final ItemStack transitionalStack = recipe.getTransitionalItem();
            final Item transitional = transitionalStack.isEmpty() ? null : transitionalStack.getItem();
            final List<SequencedRecipe<?>> sequence = recipe.getSequence();
            for (final int step : entry.getValue()) {
                if (step < 0 || step >= sequence.size()) {
                    continue;
                }
                for (final SequencedRecipeProbe.InputGroup group
                    : SequencedRecipeProbe.assemblyStepInputGroups(
                        sequence.get(step).getRecipe(), transitional)) {
                    for (final Item candidate : group.candidates()) {
                        if (items.contains(candidate)) {
                            return true; // 这条<有单>配方确实要它
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：<b>拥有</b>该工位的那条输出总线，它自己的类别清单里有没有这件物品。
     *
     * <h2>为什么必须「按各自总线」判（2026-10-05 用户第三次纠正）</h2>
     * <p>用户原话：<i>「不同输出总对应的是不同的地方……机械手的一个输出总线它对着机械手，它只负责供给
     * 输入原料；另一个总线对着那个置物台，它只负责输出这个原料和中间产物。那么齿轮到了置物台上面，
     * 它肯定就不包含在那个置物台的正常输出列表之中，所以它肯定得被回收」</i>。</p>
     *
     * <p>前两版都用「全仓口径」（本仓某一步会不会用到它 / 工位在制件那一步要不要它），把两台机器的
     * 职责混着看 —— 于是「齿轮放在置物台上」被误判成「正常」，永远收不掉。</p>
     *
     * <p>判据：遍历 {@link #busCategoryOwners}（类别 → 拥有它的总线坐标），只取<b>目标坐标等于该工位</b>
     * 的那些类别，看这件物品是否落在其中任一类别里。取不到归属表（还没建立）⇒ 返回 {@code true}
     * （<b>不回收</b>，保守；等归属表建立后再判，绝不因为「表还没建好」就乱收东西）。</p>
     */
    private boolean busTargetAccepts(final BlockPos target, final net.minecraft.world.item.Item item) {
        if (target == null || item == null || busCategoryOwners.isEmpty()) {
            return true; // 归属表还没建立 ⇒ 不作判断，绝不乱收
        }
        for (final Map.Entry<String, List<BlockPos>> entry : busCategoryOwners.entrySet()) {
            if (!entry.getValue().contains(target)) {
                continue; // 这条总线不对着本工位 ⇒ 与它无关
            }
            for (final BusCategoryInfo info : busCategories()) {
                if (!info.id().equals(entry.getKey())) {
                    continue;
                }
                if (info.items().contains(item)) {
                    return true; // 本工位的那条总线确实管这件 ⇒ 正常，留着
                }
            }
        }
        return false; // 本工位的总线不管它 ⇒ 它就是堵塞物
    }

    /** 只读：这组候选里有没有任意一项被某条<b>输出总线</b>勾中的输入类别覆盖。 */
    private boolean anyClaimedInputCovers(final Set<String> claimed, final List<Item> candidates) {
        if (candidates.isEmpty()) {
            return true; // 该组没有候选（例如纯过渡件）⇒ 本判据不表态
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.isInput() || info.isFluidInput() || !claimed.contains(info.id())) {
                continue;
            }
            for (final Item item : candidates) {
                if (info.items().contains(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 只读：该流体有没有被某条<b>输出总线</b>勾中的流体输入类别覆盖。 */
    private boolean anyClaimedFluidCovers(final Set<String> claimed, final Fluid fluid) {
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isFluidInput() && claimed.contains(info.id()) && info.fluids().contains(fluid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读：该步的<b>产出</b>有没有总线负责。
     *
     * <p><b>两种合法归宿（用户第 1 条修的就是这里）</b>：</p>
     * <ol>
     *     <li>被某条总线勾中的类别覆盖 —— {@code intermediate:<配方>:<步序>}（中间产物）、
     *     或成品 / 废料类别（末步）；</li>
     *     <li>或者本仓有<b>全自动收回的输入总线</b> —— 它默认收回全部非输入类
     *     （成品 / 废料 / 中间产物），这正是本模组的标准配置（实测：玩家勾了中间产物、
     *     成品 / 废料交给输入总线收回）。旧实现只认第 ① 种 ⇒ 把这套正确配置误报成「缺输出总线配置」。</li>
     * </ol>
     *
     * @param intermediateId 该步的中间产物类别 id（{@code RsccBusCategory.intermediateId}）
     */
    private boolean stepOutputConfigured(final Set<String> claimedOutput, final boolean autoImporter,
                                         final String intermediateId,
                                         final SequencedAssemblyRecipe recipe, final int step) {
        final int last = recipe.getSequence().size() - 1;
        if (step < last) {
            return autoImporter || claimedOutput.contains(intermediateId);
        }
        boolean hasOutput = false;
        for (final ProcessingOutput out : recipe.resultPool) {
            if (out.getStack().isEmpty()) {
                continue;
            }
            hasOutput = true;
            if (autoImporter) {
                return true;
            }
            for (final BusCategoryInfo info : busCategories()) {
                if (info.isProduct() && claimedOutput.contains(info.id())
                    && info.items().contains(out.getStack().getItem())) {
                    return true;
                }
            }
        }
        return !hasOutput; // 末步没有任何产出 ⇒ 不表态
    }

    /**
     * 把「总线未配置」缺口播成一条横幅（复用完成 / 缺料横幅同一套出口，用户要求别再造一套 UI）。
     * <p>最多列 3 条缺口（超出用「另有 N 步」概括），并给一条可操作提示；同一份缺口只在
     * 内容变化时播一次（由调用方按签名去重）。纯展示，不改任何状态。</p>
     */
    private void sendBusConfigBanner(final List<BusConfigGap> gaps) {
        final Level level = getLevel();
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || gaps.isEmpty()) {
            return;
        }
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(4);
        rows.add(CompletionBannerPayload.Row.text(0xFFFFAA00,
            CompletionBannerPayload.localized("gui.rs_create_compat.bus_config_banner.title")));
        final int listed = Math.min(gaps.size(), 3);
        for (int i = 0; i < listed; i++) {
            final BusConfigGap gap = gaps.get(i);
            // 语言键由客户端解析（服务端只有 en_us，见 CompletionBannerPayload#localized 的说明），
            // 「缺输入 / 缺输出」拆成两个键：嵌套的语言键在参数里不会被二次解析。
            rows.add(CompletionBannerPayload.Row.text(0xFFFF7F7F,
                CompletionBannerPayload.localized(gap.input()
                        ? "gui.rs_create_compat.bus_config_banner.gap_input"
                        : "gui.rs_create_compat.bus_config_banner.gap_output",
                    gap.step() + 1, gap.machine())));
        }
        if (gaps.size() > listed) {
            rows.add(CompletionBannerPayload.Row.text(0xFFE6E6E6,
                CompletionBannerPayload.localized("gui.rs_create_compat.bus_config_banner.more",
                    gaps.size() - listed)));
        }
        rows.add(CompletionBannerPayload.Row.text(0xFF9EC7FF,
            CompletionBannerPayload.localized("gui.rs_create_compat.bus_config_banner.hint")));
        CompatCompletionSender.sendToNearby(server, worldPosition, 96 * 96, rows);
    }

    // ==================== 用户第 4 条：没有中间产物缓存仓时，终端看不到中间产物 ====================

    /** 最近一次已播报的「中间产物可见性」签名（内容变了才再播一次）。 */
    private String intermediateVisibilitySignature = "";

    /**
     * <b>只读</b>：本流程的中间产物条目（每种过渡件一个代表栈 + 本仓此刻持有量）。
     *
     * <p>数据源 = {@link #busCategories()} 里的中间产物类别（{@code intermediate:<配方>:<步序>}），
     * 因此「该步会产出什么」在没有缓存仓、甚至一件都还没产出时也能立刻回答。只读，不搬运任何资源。</p>
     */
    public List<ItemStack> pipelineIntermediateEntries() {
        final List<ItemStack> result = new ArrayList<>(4);
        final Set<Item> seen = new HashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.isIntermediate() || info.items().isEmpty()) {
                continue;
            }
            final Item icon = info.icon() != null ? info.icon() : info.items().get(0);
            if (icon == null || !seen.add(icon)) {
                continue;
            }
            // 计数取「本仓内部存储 + 共享缓存池」的统一视图；没有缓存仓时就是内部存储（可能为 0）
            final int have = (int) Math.min(Integer.MAX_VALUE, itemStorage.countOf(icon));
            result.add(new ItemStack(icon, Math.max(1, have)));
        }
        return result;
    }

    /**
     * 网络内<b>没有任何中间产物缓存仓</b>时，把「本流程的中间产物」明确播报一次
     * （用户第 4 条：「终端里面并没有看到任何中间产物……我也没有放那个中间产物缓存仓，
     * 这是怎么回事？」—— 正确答案是「没有缓存仓时中间产物只在执行舱内部流转、终端自然看不到」）。
     *
     * <p>横幅列出该步会产出什么（最多 3 种，附本仓当前持有量），并给出可操作提示
     * （放一台中间产物缓存仓即可在终端看到）。同一份内容只播一次（按签名去重），
     * 且只在「确实有相关任务在跑」时才播，绝不骚扰空闲期的玩家。纯展示，不改任何状态。</p>
     */
    private void notifyIntermediatesInvisibleWithoutCache() {
        final Level level = getLevel();
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
            return;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null
            || !cretae.cookiewyq.rs_create_compat.support.RsccSharedCache
                .storages(level, network).isEmpty()) {
            intermediateVisibilitySignature = "";
            return; // 有缓存仓（或判不出来）：中间产物会在终端显示，无需提示
        }
        final List<ItemStack> entries = pipelineIntermediateEntries();
        if (entries.isEmpty()) {
            intermediateVisibilitySignature = "";
            return;
        }
        final StringBuilder signature = new StringBuilder(48);
        for (final ItemStack entry : entries) {
            signature.append(RsccAssemblyDebug.itemId(entry.getItem())).append('x')
                .append(entry.getCount()).append(';');
        }
        if (signature.toString().equals(intermediateVisibilitySignature)) {
            return; // 同一份内容只播一次（绝不每 0.5 秒刷）
        }
        intermediateVisibilitySignature = signature.toString();
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(4);
        rows.add(CompletionBannerPayload.Row.text(0xFFFFAA00,
            CompletionBannerPayload.localized("gui.rs_create_compat.intermediate_visible.title")));
        final int listed = Math.min(entries.size(), 3);
        for (int i = 0; i < listed; i++) {
            final ItemStack entry = entries.get(i);
            rows.add(CompletionBannerPayload.Row.withIcon(0xFFE6E6E6, entry,
                entry.getHoverName().getString() + " ×" + entry.getCount()));
        }
        if (entries.size() > listed) {
            rows.add(CompletionBannerPayload.Row.text(0xFFE6E6E6,
                CompletionBannerPayload.localized("gui.rs_create_compat.intermediate_visible.more",
                    entries.size() - listed)));
        }
        rows.add(CompletionBannerPayload.Row.text(0xFF9EC7FF,
            CompletionBannerPayload.localized("gui.rs_create_compat.intermediate_visible.hint")));
        CompatCompletionSender.sendToNearby(server, worldPosition, 96 * 96, rows);
    }

    // ==================== 用户第 6 条：挂起 ⇒ 先退回网络；恢复 ⇒ 再按需拉回 ====================

    /**
     * <b>挂起边沿</b>：把本仓内部存储（物品 + 磁盘缓存）与内部罐里<b>所有东西</b>先退回 RS 网络
     * （用户硬要求：「挂起的时候……这个任务的所有东西应该都会先回到网络之中，然后恢复的时候再把它还原回去」）。
     *
     * <p><b>与 {@link #flushResidualInputs()} 的区别</b>：那个只在「任务结束」时回流<b>输入类残留</b>；
     * 本方法在<b>挂起</b>时回流<b>全部</b>（备料 + 中间产物 + 成品 / 废料），因为挂起语义是「这条任务
     * 先让位、东西还回网络」，恢复时由既有备料逻辑按需再拉回来。</p>
     *
     * <p><b>不丢不复制（硬底线）</b>：物品「先插网络（拿到真实收下量）→ 再按该量精确抽取内部存储」；
     * 流体「先抽出内部罐 → 再填网络 → 填不下的余量原样填回内部罐」。网络装不下的部分<b>原地留下</b>，
     * 绝不销毁。记账走 {@link RsccFlowLedger#toNetwork}，因此「离开 − 进入 = 留存」仍然平账。</p>
     * <p>只读判定 + 单次搬运；同一段挂起只执行一次（由 {@link #busSuspendFlushed} 保证）。</p>
     */
    private void flushChamberForSuspend() {
        final Level level = getLevel();
        final StorageNetworkComponent storage = currentStorage();
        if (level == null || level.isClientSide() || storage == null) {
            // <b>2026-10-06（取消 / 挂起链审计）：网络此刻不可达 ⇒ 这一轮一个字节都没退。</b>
            // 调用方是在调用<b>之前</b>就把「本段挂起已退过」的一次性标记置位的（见 tickBusScheduler），
            // 因此这里<b>必须把它复位</b>：否则「挂起 ⇒ 舱内东西全退回网络」这条契约会在
            // 节点瞬时不挂网 / 网络图重建时静默失效 —— 东西留在舱里、日志里一个字都没有、玩家无从察觉。
            // 复位后由 tickBusScheduler 在「仍处于冻结」的后续复查节拍里重试（做成一次即置位，绝不重复搬运）。
            if (level != null && !level.isClientSide()) {
                busSuspendFlushed = false;
            }
            return;
        }
        long returnedItems = 0;
        long keptItems = 0;
        long returnedFluid = 0;
        long keptFluid = 0;
        for (int slot = 0; slot < itemStorage.getSlots(); slot++) {
            final ItemStack inSlot = itemStorage.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            // 「带进度组件的堆」绝不能被清空数据组件（同一审计；理由见 flushResidualInputs 处）：
            // 只对「纯粹的原料堆」用 DataComponentPatch.EMPTY，其余一律原样保留全部组件。
            final boolean sequencedAssemblyPiece = inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null;
            final boolean raw = SequenceMaterialGuard.isRawMaterial(inSlot);
            final ItemResource resource = raw && !sequencedAssemblyPiece
                ? new ItemResource(inSlot.getItem(), DataComponentPatch.EMPTY)
                : ItemResource.ofItemStack(inSlot);
            // <b>2026-10-05 架构修正：这里不再手动「写缓存池」。</b>
            //
            // 旧实现先调 {@code RsccSharedCache.insertIntoPool}、再把余量插网络 —— 那是
            // 「缓存盘不是网络存储源」时代的补丁。现在缓存盘<b>已经是真正的网络存储源</b>
            // （{@code IntermediateCacheNetworkNode implements StorageProvider}，带
            // 「只收中间产物 + 插入优先级 +1000 + 抽出优先级 −1000」），所以：
            //   * 中间产物进网络时由 RS 自己按优先级落进缓存盘，不需要我们指定；
            //   * 手动写池绕过优先级排序，还与随后的网络插入对同一份资源做两次操作 ——
            //     这正是实测「写进去又出来、占用恒为 0」的成因之一。
            final long inserted = storage.insert(
                resource, inSlot.getCount(), Action.EXECUTE, Actor.EMPTY);
            if (inserted > 0) {
                itemStorage.extractItem(slot, (int) inserted, false);
                returnedItems += inserted;
                flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                    .itemKey(inSlot.getItem()), inserted);
            }
            keptItems += inSlot.getCount() - inserted;
        }
        // 流体：先按种类汇总（同一流体可能占多个罐），再逐个回流；填不下的余量原样填回
        final Map<Fluid, Integer> fluidByType = new LinkedHashMap<>();
        for (final FluidStack inTank : outputTank.getTanksSnapshot()) {
            if (!inTank.isEmpty() && inTank.getAmount() > 0) {
                fluidByType.merge(inTank.getFluid(), inTank.getAmount(), Integer::sum);
            }
        }
        for (final Map.Entry<Fluid, Integer> entry : fluidByType.entrySet()) {
            final FluidStack drained = outputTank.drain(new FluidStack(entry.getKey(), entry.getValue()),
                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                continue;
            }
            final long inserted = storage.insert(new FluidResource(entry.getKey()), drained.getAmount(),
                Action.EXECUTE, Actor.EMPTY);
            if (inserted < drained.getAmount()) {
                outputTank.fill(drained.copyWithAmount((int) (drained.getAmount() - inserted)),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                keptFluid += drained.getAmount() - inserted;
            }
            if (inserted > 0) {
                flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                    .fluidKey(entry.getKey()), inserted);
            }
            returnedFluid += inserted;
        }
        if (returnedItems > 0 || returnedFluid > 0) {
            setChanged();
        }
        if (RsccAssemblyDebug.isEnabled()
            && (returnedItems > 0 || returnedFluid > 0 || keptItems > 0 || keptFluid > 0)) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " return {item x" + returnedItems + ", fluid x" + returnedFluid + "}"
                + " kept {item x" + keptItems + ", fluid x" + keptFluid + "}"
                + " to=network reason=task_suspended");
        }
    }

    /**
     * 任务结束（完成 / 取消 / 停止）后的收尾：把本仓内部存储里<b>尚未被消耗的「输入时原料」</b>
     * 全部搬回 RS 网络（用户要求：不要让残留原料原地堵住机器）。
     *
     * <p><b>怎么知道「本条任务」结束了（本轮修正）</b>：RS 的 {@code getStatuses()} 是<b>按任务</b>给出的
     * （{@link TaskStatus} 带 {@code TaskInfo.id()} 与目标资源），因此不必等整个网络空闲 ——
     * 只要<b>与本仓产线相关</b>的任务从「在跑」翻转为「不在跑」（完成 / 取消 / 停止都会让它从列表里消失），
     * 本仓就立刻收尾一次；网络里还跑着别的任务也不影响。相关性判据见
     * {@link #hasRelevantSequenceTask()}（目标资源 ∈ 本仓配方产物 / 过渡件 / 起始原料 / 输入类物品）。
     * <p>两条边沿都触发：① 网络整体空闲（既有语义）；② 本仓相关任务翻转（本轮新增）。</p>
     * <p><b>为什么不再只等「整个网络空闲」</b>：旧判据要求「网络里一个任务都没有」，
     * 玩家只是取消了自己这一条任务、旁边还有别的任务在跑时，残留原料就永远等不到那一刻
     * （用户实测：多余的输入原料没有回流）。</p>
     *
     * <p><b>与既有「全自动收回」的区别（关键）</b>：收回侧（{@code RsccChamberImportStrategy} 的全自动模式）
     * 刻意<b>恒不收回输入类原料</b>（判据即 {@link BusCategoryInfo#isInput()}）—— 任务进行中把原料抽回网络
     * 会被立刻再搬回来，与供料互相拉扯。本方法只在任务<b>已经结束</b>时做一次收尾，
     * 把这批「还压在执行舱里」的输入原料还回网络，因此两种情形互不冲突 ——
     * 而「本条任务结束」这一边沿与 {@link #busRelevantTaskGate} 共用同一个值，
     * 所以回流与备料不可能同时发生（不会来回搬运）。</p>
     *
     * <p><b>判据（两条取并集，互不遗漏）</b>：</p>
     * <ol>
     *     <li>内部存储里带「原料标记」的堆（{@link SequenceMaterialGuard#isRawMaterial}）——
     *     执行舱自己标过、后来又被收回来的那批料（面输出模式下就是这样回到内部存储的）；</li>
     *     <li>物品种类 / 流体种类落在当前<b>输入类类别</b>的过滤项里（{@link BusCategoryInfo#isInput()}）——
     *     总线输出模式下由 {@link #fillInternalForBus} 备料进来的那批。</li>
     * </ol>
     * <p>中间产物 / 成品 / 废料类别<b>不在</b>此列：它们的回网另有其路（中间产物输出面），本方法一字不碰，
     * 因此不会把「未完成中间产物」误当成原料提前抽走。</p>
     *
     * <p><b>不丢不复制</b>：物品按「先插网络（拿到真实收下量）→ 再按该量精确抽取内部存储」，
     * 与中间产物输出面同一口径；流体按「先抽出内部存储 → 再填网络 → 填不下的余量原样填回内部存储」，
     * 与输出总线的搬运口径一致。回写网络时先把「原料标记」去掉（用不带数据组件的 {@code ItemResource}），
     * 因此网络里永远只有「原始原料」这一种资源，不会污染正常合成 / 自动合成匹配。网络装不下的部分
     * <b>原地留下</b>并记一条日志，绝不销毁、绝不重复。</p>
     */
    /**
     * <b>只读</b>：本仓此刻是否还压着「输入类」物品（= 该还回网络、却可能因收尾窗口过期而滞留的那些）。
     *
     * <p>用途见 {@code tickBusScheduler} 里「任务已结束 ⇒ 续命残留回流令牌」那段：只要这个方法返回 true，
     * 输入总线就被允许继续把输入类收回网络，直到收干净为止。判据与 {@link #flushResidualInputs()}
     * 完全同一份（{@code busCategories()} 的输入类 + 原料标记），因此不会出现「它说没有、那边还有」。</p>
     */
    private boolean hasResidualInputsToFlush() {
        final Set<Item> inputItems = new HashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (info.isInput()) {
                inputItems.addAll(info.items());
            }
        }
        for (int slot = 0; slot < itemStorage.getSlots(); slot++) {
            final ItemStack inSlot = itemStorage.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            if (SequenceMaterialGuard.isRawMaterial(inSlot) || inputItems.contains(inSlot.getItem())) {
                return true;
            }
        }
        return false;
    }

    private void flushResidualInputs() {
        final Level level = getLevel();
        final StorageNetworkComponent storage = currentStorage();
        if (level == null || level.isClientSide() || storage == null) {
            return;
        }
        final Set<Item> inputItems = new HashSet<>();
        final Set<Fluid> inputFluids = new HashSet<>();
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.isInput()) {
                continue;
            }
            for (final Item item : info.items()) {
                if (item != null) {
                    inputItems.add(item);
                }
            }
            for (final Fluid fluid : info.fluids()) {
                if (fluid != null) {
                    inputFluids.add(fluid);
                }
            }
        }
        long returnedItems = 0;
        long keptItems = 0;
        long returnedFluid = 0;
        long keptFluid = 0;
        // ① 物品：带原料标记的（执行舱自标）+ 落在输入类类别里的，一并回网
        // （遍历「内部存储 + 磁盘缓存」的统一视图：磁盘里的残留原料同样会被回网，绝不落在磁盘里没人管）
        for (int slot = 0; slot < itemStorage.getSlots(); slot++) {
            final ItemStack inSlot = itemStorage.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            // <b>2026-10-05 修「取消任务后中间产物永久滞留在仓内」。</b>
            //
            // 旧判据只放行「原料 + 输入类」，把<b>中间产物 / 成品 / 废料</b>挡在外面，注释写的是
            // 「留给各自既有的回收路径」。但**中间产物根本没有另一条回收路径** ——
            // 那条路径只在「有活跃订单、输入总线凭一次性令牌收回」时才存在。
            // 一旦订单被取消（`materials=[]`）、令牌早已作废，压在仓内的中间产物就<b>永久滞留</b>，
            // 而它占着机器唯一的加工位 ⇒ 后续任何配方都推不进去。
            //
            // 实测（快照 20261005-214424，chamber@-16,-60,10 冲压）：
            //   materials=[]（无活跃订单）  却  storedItems=[{create:incomplete_track, count:1}]
            //   —— 那件轨道半成品把冲压位占死，坚固板的 unprocessed_obsidian_sheet 永远进不来。
            //
            // 修法：**任务确认结束后，本仓内部存储里的东西一律回网**（不管它是原料、输入类还是中间产物）。
            // 这本来就是「任务结束 → 东西还回网络」的语义，只是在中间产物这一类上漏了。
            // 回网时按资源原样插入；中间产物会由缓存仓的网络源（插入优先级 +1000）自动优先落进缓存盘。
            // <b>2026-10-06（取消 / 挂起链审计）：带 {@code SEQUENCED_ASSEMBLY} 的堆一律算过渡件。</b>
            //
            // 为什么必须单列这一条（可证的漏网，见审计结论）：上面那两条判据都不覆盖所有过渡件 ——
            //  ① {@link #isTransitionItem(Item)} 是<b>物品名启发式</b>（名字里含 incomplete_ / unprocessed_，
            //     且去掉 {@code create:} 前缀后是某条配方 id 的<b>后缀</b>）。它对 Create 三条链并不都成立：
            //     坚固板的过渡件是 {@code create:unprocessed_obsidian_sheet}，配方 id 是
            //     {@code create:sequenced_assembly/sturdy_sheet} ⇒ {@code endsWith("obsidian_sheet")}
            //     为假 ⇒ 该判据对它恒返回 false（见 {@link #isTransitionOfMyRecipes}）；
            //  ② {@link #isMyProductOrScrap} 只认「本仓类别表里列过的物品」，而类别表登记的是配方声明的
            //     {@code transitional_item}；任何「某一步产出<b>另一个物品</b>」的过渡件（addon / 自定义
            //     results）都不在类别表里。于是 ①② 同时为假 ⇒ 这一份被 continue 跳过、永久留在舱内
            //     （舱内不再有任何回流路径：输入总线只收「本仓类别表 + 原料标记 + 组件判定」认得的，
            //     而本方法每个「任务结束」边沿只跑一次）。
            // 组件是权威判据，且与回收侧 {@code RsccChamberImportStrategy.isTransitionItem(ItemStack)}
            // 完全同一口径（那里就是 {@code stack.get(SEQUENCED_ASSEMBLY) != null}），因此这里补上它：
            // 既不会再漏掉过渡件，也不会把「与本仓产线完全无关的裸物品」收走。
            final boolean sequencedAssemblyPiece = inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null;
            if (!sequencedAssemblyPiece
                && !SequenceMaterialGuard.isRawMaterial(inSlot) && !inputItems.contains(inSlot.getItem())
                && !isTransitionItem(inSlot.getItem()) && !isMyProductOrScrap(inSlot)) {
                continue; // 与本仓产线完全无关的东西：不碰（绝不乱收别人的物品）
            }
            // <b>2026-10-05 修复我自己引入的严重回归：这里绝不能抹掉数据组件。</b>
            //
            // 旧写法对<b>所有</b>回网物品都构造 {@code new ItemResource(item, DataComponentPatch.EMPTY)}
            // —— 它抹掉的是<b>全部</b>组件，其中包含 {@code SEQUENCED_ASSEMBLY}（进度步）。
            //
            // 后果（用户实测原话）：「在终端里发现一个拿在手上的未完成黑曜石板，
            // 查看它的 tooltip 发现<b>没有任何步骤痕迹</b>，就单纯一个未完成的黑曜石板」——
            // 那正是被抹掉组件的过渡件。它<b>不应该存在</b>：Create 的过渡件必然带进度组件，
            // 抹掉之后冲压机再也判不出「这是第几步」，整条链永久卡死。
            //
            // 为什么以前没暴露：这段代码原先<b>只处理「原料 + 输入类」</b>，过渡件被 continue 排除在外，
            // 所以永远走不到这里。是我为了修「取消任务后残留滞留」才把过渡件放进来的
            // （见上面那段注释），于是它们开始被抹组件 —— 这是我引入的回归。
            //
            // 现在：<b>只有带「原料标记」的堆才去掉标记</b>（那是执行舱自己加的标记，网络里不该有），
            // 其余一律用 {@link ItemResource#ofItemStack} 原样保留全部数据组件。
            // <b>2026-10-06（同一审计）：带进度组件的堆即便同时带「原料标记」，也绝不清空数据组件。</b>
            // {@code DataComponentPatch.EMPTY} 去掉的是<b>全部</b>组件（含 {@code SEQUENCED_ASSEMBLY}），
            // 那正是「未完成黑曜石板没有任何步骤痕迹」那条已知事故；因此只对「纯粹的原料堆」
            // （带原料标记且不带进度组件）走清空口径 —— 网络里仍然只有原始原料这一种资源。
            final ItemResource residualResource = SequenceMaterialGuard.isRawMaterial(inSlot)
                && !sequencedAssemblyPiece
                ? new ItemResource(inSlot.getItem(), DataComponentPatch.EMPTY)
                : ItemResource.ofItemStack(inSlot);
            // <b>2026-10-05 架构修正：不再手动「写缓存池」。</b>
            //
            // 旧实现先调 {@code RsccSharedCache.insertIntoPool}、把余量再插网络 —— 那是
            // 「缓存盘不是网络存储源」时代的补丁。现在缓存盘<b>已经是真正的网络存储源</b>
            // （{@code IntermediateCacheNetworkNode implements StorageProvider}，带
            // 「只收中间产物 + 插入优先级 +1000 + 抽出优先级 −1000」），所以：
            //   * 中间产物进网络时会由 RS 自己按优先级落进缓存盘，不需要我们指定；
            //   * 手动写池绕过优先级排序，还与随后的网络插入对同一份资源做两次操作 ——
            //     这正是实测「写进去又出来、占用恒为 0」的成因之一。
            final long inserted = storage.insert(
                residualResource, inSlot.getCount(), Action.EXECUTE, Actor.EMPTY);
            if (inserted > 0) {
                itemStorage.extractItem(slot, (int) inserted, false);
                returnedItems += inserted;
                // 守恒账本：本仓「进入网络」的那一份（任务收尾的残留输入原料回流）
                flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                    .itemKey(inSlot.getItem()), inserted);
                // 追踪（「插入网络」这一段）：残留输入原料回网的真实入网量 + 当时网络存量
                if (RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.trace("trace-return@" + RsccAssemblyDebug.at(worldPosition)
                            + "#" + RsccAssemblyDebug.itemId(inSlot.getItem()),
                        RsccAssemblyDebug.traceLine(
                            "item=" + RsccAssemblyDebug.itemId(inSlot.getItem()), inserted,
                            RsccAssemblyDebug.machine("chamber", worldPosition), "insert_network",
                            "network", "task_finished_residual net_after="
                                + networkItemAmount(inSlot.getItem()),
                            networkItemAmount(inSlot.getItem())));
                }
            }
            keptItems += inSlot.getCount() - inserted;
        }
        // ② 流体：同一流体可能占多个罐，先按种类汇总再处理（逐罐处理会重复统计）
        final Map<Fluid, Integer> fluidByType = new LinkedHashMap<>();
        for (final FluidStack inTank : outputTank.getTanksSnapshot()) {
            if (!inTank.isEmpty() && inTank.getAmount() > 0 && inputFluids.contains(inTank.getFluid())) {
                fluidByType.merge(inTank.getFluid(), inTank.getAmount(), Integer::sum);
            }
        }
        for (final Map.Entry<Fluid, Integer> entry : fluidByType.entrySet()) {
            final FluidStack drained = outputTank.drain(new FluidStack(entry.getKey(), entry.getValue()),
                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                continue;
            }
            final long inserted = storage.insert(new FluidResource(entry.getKey()), drained.getAmount(),
                Action.EXECUTE, Actor.EMPTY);
            if (inserted < drained.getAmount()) {
                // 网络装不下：余量原样填回内部存储（绝不销毁、绝不重复）
                outputTank.fill(drained.copyWithAmount((int) (drained.getAmount() - inserted)),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                keptFluid += drained.getAmount() - inserted;
            }
            // 守恒账本：本仓「进入网络」的那一份（任务收尾的残留流体回流，单位 mB）
            flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                .fluidKey(entry.getKey()), inserted);
            returnedFluid += inserted;
        }
        // ③ 工位侧（机器 / 置物台）：本仓自己推过去、任务结束后仍没被消耗的「起步原料」残留。
        // 用户实测断点：坚固板任务成功之后，置物台上还剩一份黑曜石粉「堵在原地」，没有任何回收路径。
        // 判据与守恒口径见 #flushStationStartIngredients（只认「本仓推料登记表 + 起步原料 + 工位上没有在制件」）。
        final long[] stationReturn = flushStationStartIngredients(storage);
        final long stationItems = stationReturn[0];
        final long stationKept = stationReturn[1];
        if (stationItems > 0 || stationKept > 0) {
            setChanged();
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " return {item x" + stationItems + "}"
                    + " kept {item x" + stationKept + "}"
                    + " from=stations to=network reason=task_finished_station_residual");
            }
        }
        if (returnedItems > 0 || returnedFluid > 0) {
            setChanged();
            if (RsccAssemblyDebug.isEnabled()) {
                // 只在真的搬了东西（数量 > 0）时才打这一条（用户要求：稳态零输出）；
                // 数量口径 = 「实际插入网络成功的量」（returned*），余量原地留下并另记一条（kept 字段 =
                // 网络拒收 / 装不下而留在本仓的量），因此日志里的数与实际清空量严格一致（别报 500 实际清 0）。
                RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                    + " return {item x" + returnedItems + ", fluid x" + returnedFluid + "}"
                    + " kept {item x" + keptItems + ", fluid x" + keptFluid + "}"
                    + " to=network reason=task_finished");
                RsccAssemblyDebug.countReturn(returnedItems + returnedFluid);
            }
        }
        if (keptItems > 0 || keptFluid > 0) {
            throttledLog("task finished: {} item(s) / {} mB of input material could not go back to the"
                + " network (network full); they stay inside the chamber (never destroyed)",
                keptItems, keptFluid);
        }
    }

    /**
     * <b>工位侧（机器 / 置物台）上的「起步原料残留」回流</b>（任务确认结束 / 任务结束边沿专用）。
     *
     * <h2>为什么需要它（用户实测：坚固板任务成功之后，置物台上还剩一份黑曜石粉堵在原地）</h2>
     * <p>总线输出模式下起步原料是<b>输出总线</b>推给工位（置物台 / 机器）的，推过去之后由 Create
     * 在工位上把它变成过渡件。若任务在「粉刚压上去、还没被注液 / 冲压」时结束，这一份粉就留在工位上 ——
     * 而它此后<b>没有任何回收路径</b>：</p>
     * <ul>
     *     <li>输入总线的机器侧<b>平时</b>一律按硬约束「任务运行期间起步原料绝不收回」拒绝它
     *     （{@code RsccChamberImportStrategy#autoAcceptsItem} 的 {@code isStartIngredient} 分支）；</li>
     *     <li>输入总线「任务刚结束」的一次性边沿（{@code residualEdge}）又被
     *     {@code RsccChamberImportStrategy#transfer} 的机器侧闸门（{@code !residualEdge && ...}）整体绕开
     *     —— 那是为修「刚推给机械手的金板被立刻抄回、反复横跳」而有意收紧的，代价正是
     *     「机器 / 置物台上的起步原料永远收不回来」；</li>
     *     <li>原先的 {@link #flushResidualInputs()} <b>只扫本仓内部存储与内部罐</b>，根本不碰工位。</li>
     * </ul>
     * <p>因此这份残留只能由本仓自己在「任务确认结束」这一刻主动收回；判据必须<b>精确到「这一份是本仓
     * 自己推过去的」</b>，否则会把玩家手工放在空闲机器上的同类原料一起收走（用户硬要求：没有任何任务在
     * 使用某台机器时，本仓的总线不该影响那台机器的任何地方）。唯一凭据是在制件登记表
     * （{@link #inFlightUnits}，由 {@code RsccChamberExportStrategy} 在<b>每一次成功推料</b>后写入）：
     * 它记着「哪个工位、本仓最后推过去的是哪一件」。</p>
     *
     * <h2>判据（全部成立才收；任一条不成立都一个字节都不动）</h2>
     * <ol>
     *     <li>该工位在登记表里，且<b>登记的那一件 == 工位上实际压着的那一件</b>（同物品）
     *     ⇒ 证明这一份<b>正是本仓推过去的</b>（不是玩家手工放的，也不是别的仓推的 ——
     *     跨仓共享工位时对方仓登记的件与本仓登记的不一致，天然被排除）；</li>
     *     <li>它是本仓某条配方的<b>起步原料</b>（{@link #isStartIngredient}）—— 过渡件 / 成品 / 废料
     *     各有自己的归宿（交接文档 §5.2），本方法一字不碰；</li>
     *     <li>该工位此刻<b>没有在制件</b>（{@link #unitInFlightAt}）—— 有在制件说明这份原料正等着开件 /
     *     正被产线使用，绝不抢。</li>
     * </ol>
     * <p><b>为什么不会破坏「任务运行期间不收回起步原料」</b>：本方法只被 {@link #flushResidualInputs()}
     * 调用，而后者只在两条<b>任务结束边沿</b>（相关任务由「在跑」翻转为「不在跑」、以及连续空闲确认收尾）
     * 被调用；任务在跑时这两条边沿都不会发生（挂起冻结时更是两个边沿都不算结束）。
     * 因此「任务期间起步原料绝不被收回」一字未改。</p>
     *
     * <p><b>不丢不复制（与内部存储回流同一口径）</b>：先对网络做 {@link Action#SIMULATE}，只按网络收得下的量
     * <b>精确抽取</b>该工位容器，再正式插入；插入结果小于抽出量时余量<b>原样放回该工位</b>，
     * 网络收不下就这一点都不动（原地留下并记进 kept），绝不销毁、绝不复制。带「原料标记」的那一份
     * 按未标记资源入网（网络里只保留原始原料这一种资源）。</p>
     *
     * @return {@code {实际入网量, 网络拒收 / 装不下而留在工位上的量}}
     */
    private long[] flushStationStartIngredients(final StorageNetworkComponent storage) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || storage == null || inFlightUnits.isEmpty()) {
            return new long[]{0L, 0L};
        }
        long moved = 0L;
        long kept = 0L;
        // 快照遍历：搬运动作不会改登记表，但保持「边遍历边读世界」的写法会掩盖将来的改动，故先取快照
        for (final Map.Entry<BlockPos, InFlightUnit> entry : new ArrayList<>(inFlightUnits.entrySet())) {
            final BlockPos station = entry.getKey();
            final InFlightUnit unit = entry.getValue();
            final Item item = unit == null ? null : unit.item();
            if (item == null) {
                continue;
            }
            // <b>2026-10-06 配套修复（同伴 D 指出的必要补充）：工位侧的过渡件也要能收。</b>
            //
            // 把 {@link #isTransitionOfMyRecipes} 改成数据驱动之后，坚固板的工位件不再被
            // 机器侧第一条子句误放行（那修掉了「冲压一次就回去」），但反过来它现在会被
            // {@code isTransitionReclaimAllowed} 按住 —— 那个判据<b>只看步序、完全不知道任务在不在跑</b>，
            // 于是<b>取消订单后过渡件仍压在工位上，占死机器唯一的加工位</b>
            // （track / 精密构件今天就已经是这个毛病，原因是它们的名字命中了旧启发式）。
            //
            // 判据（在「任务确认结束」这个边沿上调用本方法，见调用点）：
            //   * 起步原料：保持原判据（登记表那件 + 工位上没有别的在制件）；
            //   * 过渡件：<b>该配方此刻已经没有任何活跃订单</b> ⇒ 没人再要它 ⇒ 收回网络。
            //     有订单时绝不收 —— 它还要在本机继续被加工（用户语义：「等它做完这一步」）。
            final boolean startIngredient = isStartIngredient(item);
            final boolean transitionPiece = isTransitionItem(item);
            if (!startIngredient && !transitionPiece) {
                continue; // 成品 / 废料 / 别的物品各有归宿，不在这里碰
            }
            if (transitionPiece) {
                // 过渡件本身就是「工位上的在制件」，因此不能沿用下面那条「有在制件就不收」的闸门；
                // 改用「配方还有没有活跃订单」这一个事实。
                boolean ordered = false;
                final IItemHandler probeHandler =
                    cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                        .itemHandlerAt(level, station);
                if (probeHandler != null) {
                    for (int s = 0; s < probeHandler.getSlots(); s++) {
                        final SequencedAssembly asm =
                            probeHandler.getStackInSlot(s).get(AllDataComponents.SEQUENCED_ASSEMBLY);
                        if (asm != null && recipeOrdered(asm.id().toString())) {
                            ordered = true;
                            break;
                        }
                    }
                }
                if (ordered) {
                    continue; // 还没做完 / 还要继续加工：寸步不让
                }
            } else if (unitInFlightAt(station)) {
                continue; // 工位上还压着在制件 ⇒ 这份原料正被产线使用，绝不抢
            }
            final IItemHandler handler =
                cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                    .itemHandlerAt(level, station);
            if (handler == null) {
                continue; // 工位没了 / 区块未加载 / 不是容器 ⇒ 判不了就不动
            }
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                final ItemStack inSlot = handler.getStackInSlot(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) {
                    continue; // 只收「登记表记的那一件」，别的物品（含玩家放的）一律不碰
                }
                // 入网资源：原料标记必须先去掉（网络里只保留原始原料），其余原样保留数据组件
                // <b>2026-10-06（取消 / 挂起链审计）：带进度组件的堆即便同时带原料标记，也绝不清空组件</b>
                // —— {@code DataComponentPatch.EMPTY} 会连 {@code SEQUENCED_ASSEMBLY} 一起抹掉，
                // 那正是「过渡件变成没有步骤痕迹的裸物品」那条已知事故（口径与 flushResidualInputs 一致）。
                final boolean sequencedAssemblyPiece = inSlot.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null;
                final boolean raw = SequenceMaterialGuard.isRawMaterial(inSlot);
                final ItemResource resource = raw && !sequencedAssemblyPiece
                    ? new ItemResource(item, DataComponentPatch.EMPTY)
                    : ItemResource.ofItemStack(inSlot);
                final long acceptable = storage.insert(resource, inSlot.getCount(), Action.SIMULATE, Actor.EMPTY);
                if (acceptable <= 0) {
                    kept += inSlot.getCount(); // 网络收不下：一点都不动，原地留下
                    continue;
                }
                final ItemStack taken = handler.extractItem(
                    slot, (int) Math.min(acceptable, inSlot.getCount()), false);
                if (taken.isEmpty()) {
                    continue;
                }
                final long netBefore = cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                    .networkItemAmount(storage, taken.getItem());
                final long inserted = storage.insert(resource, taken.getCount(), Action.EXECUTE, Actor.EMPTY);
                final long landed = inserted <= 0 ? 0L
                    : (netBefore < 0 ? inserted : Math.min(inserted, Math.max(0L,
                        cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                            .networkItemAmount(storage, taken.getItem()) - netBefore)));
                if (inserted < taken.getCount()) {
                    // 网络没吃完（理论不可达：已按 SIMULATE 夹过）：余量原样放回该工位，绝不销毁
                    handler.insertItem(slot, taken.copyWithCount((int) (taken.getCount() - inserted)), false);
                    kept += taken.getCount() - inserted;
                }
                if (inserted > 0) {
                    moved += landed;
                    // 守恒账本：本仓产线「进入网络」的那一份（工位侧残留回流）
                    flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                        .itemKey(taken.getItem()), inserted);
                    if (RsccAssemblyDebug.isEnabled()) {
                        RsccAssemblyDebug.trace("trace-return@" + RsccAssemblyDebug.at(station)
                                + "#" + RsccAssemblyDebug.itemId(taken.getItem()),
                            RsccAssemblyDebug.traceLine(
                                "item=" + RsccAssemblyDebug.itemId(taken.getItem()), inserted,
                                RsccAssemblyDebug.machine("machine", station), "insert_network",
                                "network", "task_finished_station_residual net_after="
                                    + cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                                        .networkItemAmount(storage, taken.getItem()),
                                cretae.cookiewyq.rs_create_compat.support.RsccChamberImportStrategy
                                    .networkItemAmount(storage, taken.getItem())));
                    }
                }
            }
        }
        return new long[]{moved, kept};
    }

    /**
     * 只读诊断：门控为假时<b>到底是哪一种「没有任务」</b>（本轮修正的日志质量）。
     * <p>旧实现三种完全不同的原因都写成同一句 {@code reason=no_active_task}，而它们的处置完全不同：</p>
     * <ul>
     *     <li>{@code network_unavailable}：本仓节点此刻没挂在网络上（区块卸载 / 图重建瞬时态）——
     *     这是<b>瞬时</b>的，不该被当成「任务结束」（见 {@link #BUS_GATE_END_CONFIRM}）；</li>
     *     <li>{@code no_autocrafting_component}：网络里没有自动合成组件（接线 / 结构问题）；</li>
     *     <li>{@code no_active_task}：确实一条任务都没有（任务完成 / 取消后的稳态）。</li>
     * </ul>
     * <p>有任务但与本仓产线无关时返回 {@code no_relevant_task}（旧实现把它与「一条任务都没有」
     * 混为一谈，排查「为什么本仓不备料」时会被彻底带偏）。只读，不改变任何状态。</p>
     */
    private String gateReason(final boolean gate, final boolean relevant) {
        if (gate) {
            return relevant ? "relevant_task" : "unrelated_task_only";
        }
        final Level level = getLevel();
        final Network network = level == null ? null : getNode().getNetworkOrNull();
        if (network == null) {
            return "network_unavailable";
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return "no_autocrafting_component";
        }
        return autocrafting.getStatuses().isEmpty() ? "no_active_task" : "no_relevant_task";
    }

    /**
     * 与本仓「六向相邻或线缆相连」、且<b>属于本仓所在链（分支）</b>的全部输出总线坐标
     * （按坐标稳定排序；服务端、带缓存）。
     *
     * <p><b>2026-10-06 口径变更（用户需求）</b>：一条输出总线只要接在链上的<b>任意一台</b>执行仓上，
     * 就等于接在整条链（整个分支）上 —— 因此这里收的是「链上任一台仓认领的总线」，而不是
     * 「物理上只贴着本仓的那一条」。判定走唯一事实源 {@link #isInMyChain(BlockPos)}（← {@link #chainMembers()}）；
     * 没串链时链就是本仓自己，行为与改造前<b>逐字一致</b>。</p>
     */
    public List<BlockPos> connectedExporterPositions() {
        return collectExporters(true);
    }

    /**
     * 只读查询：本仓在「总线输出」模式下<b>正在供料的目标坐标集合</b>
     * （= 相连输出总线所朝向的那一面的相邻方块，也就是机器 / 置物台所在的位置）。
     *
     * <p><b>为什么需要它（用户要求）</b>：机器加工完的产出会压在机器 / 置物台上，而输出总线只能把
     * 原料推进去、不会把它收走；因此「贴着机器放」的输入总线必须知道<b>本仓在给哪些机器供料</b>，
     * 才能从那些机器的输出侧把产出收进 RS 网络（见 {@code support/RsccChamberImportStrategy}）。</p>
     *
     * <p><b>口径</b>：非总线输出模式 / 客户端一律返回空表；只枚举「归属落在本仓所在链（分支）上」的输出总线
     * （与 {@link #connectedExporterPositions()} 同源，含 20 tick 缓存），因此线缆分叉到两台仓时不会
     * 把<b>别的链</b>上的仓的机器算进来，而<b>同一条链</b>上任一台仓挂的总线都会算进来
     * （用户需求：总线接在链上任意一台 = 属于整条链）。返回的坐标已去重并按坐标升序，便于调用方稳定遍历。</p>
     */
    public List<BlockPos> busSupplyTargets() {
        return supplyTargets(true);
    }

    /**
     * 只读：<b>本仓物理上</b>认领的输出总线所朝向的那些工位（= 改造前的「只贴着本仓」口径，
     * 不含同链其它仓的总线）。
     *
     * <p><b>为什么链展开之后还必须保留这份「物理口径」</b>：它唯一的用途是
     * {@code RsccChamberImportStrategy} 里那条「<b>下一步仍由同一台机器负责 ⇒ 留在原处、绝不搬走</b>」
     * 的判据 —— 那条判据问的是「这台机器是不是<b>那台接手仓自己</b>在供料」（= 同一个机器连续做多步），
     * 换成链口径会把「同链另一台仓供料的机器」也算成「它自己的机器」，从而把本该<b>直接交接</b>的
     * 过渡件压回 RS 网络绕一圈（正是 2026-10-05 实测「网络里留不住 → 冲压机永远拿不到料」的成因）。
     * 因此这里刻意只认物理认领，链口径只用于「收料要扫哪些工位 / 哪些总线算我的」。</p>
     */
    public List<BlockPos> selfBusSupplyTargets() {
        return supplyTargets(false);
    }

    /**
     * {@link #busSupplyTargets()} / {@link #selfBusSupplyTargets()} 的唯一实现。
     *
     * @param wholeChain {@code true} = 链（分支）口径（同链任一台仓认领的总线都算本仓的）；
     *                   {@code false} = 只算「总线自身解析出的执行仓就是本仓」的那些
     */
    private List<BlockPos> supplyTargets(final boolean wholeChain) {
        if (outputMode != OutputMode.BUS) {
            return List.of(); // 只有「总线输出」模式才谈得上「本仓在给谁供料」
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return List.of();
        }
        final List<BlockPos> targets = new ArrayList<>(2);
        for (final BlockPos pos : connectedExporterPositions()) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            // 只取「已存在」的方块实体（CHECK），搜索 / 查询阶段绝不强制初始化邻块
            if (!(level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
                instanceof final RsccExporterExecutorMode exporter)) {
                continue;
            }
            if (!wholeChain && !worldPosition.equals(exporter.rscc$linkedExecutorPos())) {
                continue; // 物理口径：这条总线其实归同链的别台仓
            }
            final BlockPos target = exporter.rscc$supplyTargetPos();
            if (target != null && level.isLoaded(target) && !targets.contains(target)) {
                targets.add(target);
            }
        }
        targets.sort(Comparator.comparingLong(BlockPos::asLong));
        return targets;
    }

    /** 与本仓「相邻或线缆相连」的全部输出总线（不做「属于本仓 / 本链」过滤；仅供模式切换时通知用）。 */
    private List<BlockPos> reachableExporterPositions() {
        return collectExporters(false);
    }

    /**
     * 沿「可穿行集合」（{@link RsccWireBlocks}：RS 线缆 + 其它输出总线 / 输入总线）BFS 收集与本仓相连的输出总线。
     * <p><b>最终判定规则</b>：从本仓出发，只允许经过可穿行集合，<b>不穿过任何机器 / 容器</b>；
     * 与「本仓或任意可达导线」六向相邻的输出总线方块实体即为相连。
     * 步数上限 {@link #BUS_LINK_MAX_STEPS}（与输出总线侧同一上限，两侧判定一致）；{@code restrictToMyChain}
     * 为真时还要求该总线<b>自身 BFS 解析出的执行仓与本仓同属一条链（分支）</b>
     * （见 {@link #isInMyChain(BlockPos)}：本仓自己当然算，因此「只贴着本仓」的旧口径是它的子集），
     * 于是「线缆分叉到两台仓」时只会把<b>同链</b>的那一台算进来，别的链上的仓照旧不算。</p>
     */
    private List<BlockPos> collectExporters(final boolean restrictToMyChain) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return List.of();
        }
        final long now = level.getGameTime();
        if (restrictToMyChain && connectedExportersCache != null && now < connectedExportersExpireAt) {
            return connectedExportersCache;
        }
        final List<BlockPos> found = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(worldPosition);
        queue.add(worldPosition);
        int steps = 0;
        while (!queue.isEmpty() && steps <= BUS_LINK_MAX_STEPS) {
            final BlockPos cell = queue.poll();
            steps++;
            for (final Direction direction : Direction.values()) {
                final BlockPos neighbor = cell.relative(direction);
                if (!visited.add(neighbor)) {
                    continue;
                }
                if (!level.isLoaded(neighbor)) {
                    continue;
                }
                // 只读方块状态来判定：不对「仅用于穿行」的方块取方块实体（避免探测时强制初始化邻块 → 递归）
                final BlockState neighborState = level.getBlockState(neighbor);
                if (RsccWireBlocks.isExporterBus(neighborState)) {
                    // 只有确认是「输出总线方块」后才取它的方块实体，且「只取已存在的、不创建」
                    final BlockEntity blockEntity = level.getChunkAt(neighbor)
                        .getBlockEntity(neighbor, LevelChunk.EntityCreationType.CHECK);
                    if (blockEntity instanceof RsccExporterExecutorMode exporter) {
                        final BlockPos exporterPos = blockEntity.getBlockPos();
                        if (!found.contains(exporterPos)
                            && (!restrictToMyChain || isInMyChain(exporter.rscc$linkedExecutorPos()))) {
                            found.add(exporterPos);
                        }
                        // 输出总线本身也算导线的一部分：继续往后穿行（用户要求「紧贴的输出总线 / 输入总线也能被识别」）
                    }
                }
                if (RsccWireBlocks.isWire(neighborState)) {
                    queue.add(neighbor); // RS 线缆 / 其它输出总线 / 输入总线：继续穿行
                }
            }
        }
        found.sort(Comparator.comparingLong(BlockPos::asLong));
        if (restrictToMyChain) {
            connectedExportersCache = found;
            connectedExportersExpireAt = now + BUS_CONNECT_CACHE_TICKS;
        }
        return found;
    }

    /**
     * 与本仓「相邻或线缆相连」、且<b>属于本仓所在链（分支）</b>的全部<b>输入总线</b>坐标
     * （按坐标稳定排序；服务端、带 20 tick 缓存）。口径与 {@link #connectedExporterPositions()} 完全并列
     * （同一份 {@link #isInMyChain(BlockPos)} 判定）：收集侧同样「接在链上任意一台仓 = 属于整条链」。
     */
    public List<BlockPos> connectedImporterPositions() {
        return collectImporters(true);
    }

    /**
     * {@link #collectExporters(boolean)} 的<b>输入总线</b>版（同一套 BFS / 上限 / 「只读状态、不初始化邻块」口径）。
     * <p>用途见 {@link #connectedImportersCache}：把「机器的产出由哪条总线负责」也算进来，
     * 避免把「靠输入总线全自动收回产出」的正确配置误判成「缺输出总线配置」。</p>
     */
    private List<BlockPos> collectImporters(final boolean restrictToMyChain) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return List.of();
        }
        final long now = level.getGameTime();
        if (restrictToMyChain && connectedImportersCache != null && now < connectedImportersExpireAt) {
            return connectedImportersCache;
        }
        final List<BlockPos> found = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(worldPosition);
        queue.add(worldPosition);
        int steps = 0;
        while (!queue.isEmpty() && steps <= BUS_LINK_MAX_STEPS) {
            final BlockPos cell = queue.poll();
            steps++;
            for (final Direction direction : Direction.values()) {
                final BlockPos neighbor = cell.relative(direction);
                if (!visited.add(neighbor) || !level.isLoaded(neighbor)) {
                    continue;
                }
                final BlockState neighborState = level.getBlockState(neighbor);
                if (RsccWireBlocks.isImporterBus(neighborState)) {
                    final BlockEntity blockEntity = level.getChunkAt(neighbor)
                        .getBlockEntity(neighbor, LevelChunk.EntityCreationType.CHECK);
                    if (blockEntity instanceof RsccImporterExecutorMode importer) {
                        final BlockPos importerPos = blockEntity.getBlockPos();
                        if (!found.contains(importerPos)
                            && (!restrictToMyChain || isInMyChain(importer.rscc$linkedExecutorPos()))) {
                            found.add(importerPos);
                        }
                    }
                }
                if (RsccWireBlocks.isWire(neighborState)) {
                    queue.add(neighbor);
                }
            }
        }
        found.sort(Comparator.comparingLong(BlockPos::asLong));
        if (restrictToMyChain) {
            connectedImportersCache = found;
            connectedImportersExpireAt = now + BUS_CONNECT_CACHE_TICKS;
        }
        return found;
    }

    /** 样板带配方 id 时，按配方 id 取出所属的序列装配配方；否则 null。 */
    @org.jetbrains.annotations.Nullable
    private SequencedAssemblyRecipe directAssemblyOf(final Level level, final UnitData unit) {
        final ResourceLocation recipeId = ResourceLocation.tryParse(
            unit.recipe() == null ? "" : unit.recipe());
        if (recipeId == null) {
            return null;
        }
        try {
            final Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(recipeId);
            return holder.isPresent() && holder.get().value() instanceof final SequencedAssemblyRecipe recipe
                ? recipe : null;
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 取该单元样板所属配方在配方管理器里的<b>注册 id</b>（样板记了就直接用；没记则反查注册表）。
     *
     * <p><b>为什么需要它</b>：中间产物类别的「按步过滤」原型必须带真实的配方 id
     * （{@code create:sequenced_assembly} 组件的 {@code id}），否则「同一过渡件属于多条配方」时
     * 无法区分是哪一条。反查只在「样板没记配方 id」（v4 语义样板）时发生，且类别表每
     * {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 才重建一次，开销可忽略。</p>
     */
    @org.jetbrains.annotations.Nullable
    private static ResourceLocation recipeIdOf(final Level level, final UnitData unit,
                                               final @org.jetbrains.annotations.Nullable SequencedAssemblyRecipe recipe) {
        final ResourceLocation direct = ResourceLocation.tryParse(unit.recipe() == null ? "" : unit.recipe());
        if (direct != null) {
            return direct;
        }
        if (recipe == null) {
            return null;
        }
        try {
            for (final RecipeHolder<?> holder : level.getRecipeManager()
                .getAllRecipesFor(com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())) {
                if (holder.value() == recipe) {
                    return holder.id(); // identity 比较：resolveUnitRecipe 返回的就是这张表里的实例
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方系统异常（未加载完 / 版本差异）：拿不到 id → 该步骤退化为「只按物品过滤」
        }
        return null;
    }

    /** 构造某一步的「按步过滤」原型：过渡件 + (配方 id, 步序) 的 {@code create:sequenced_assembly} 组件。 */
    private static ItemStack stepPrototypeOf(final ItemStack transitionalStack,
                                             final ResourceLocation recipe, final int step) {
        final ItemStack prototype = transitionalStack.copyWithCount(1);
        prototype.set(AllDataComponents.SEQUENCED_ASSEMBLY, new SequencedAssembly(recipe, step, 0F));
        return prototype;
    }

    /** 本仓全部单元样板的单元数据（含未绑定名字时的；导出用，不做 isBound 过滤）。 */
    private List<UnitData> unitsForExport() {
        final Level level = getLevel();
        if (level == null) {
            return List.of();
        }
        final List<UnitData> units = new ArrayList<>(UNIT_SLOTS);
        for (int i = 0; i < unitSlots.getContainerSize(); i++) {
            final ItemStack stack = unitSlots.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final UnitData unit = SequencePatternData.readUnit(stack, level.registryAccess());
            if (unit != null) {
                units.add(unit);
            }
        }
        return units;
    }

    /**
     * 本仓是否已设定「配方类型 + 名字」（未设定时不允许放入单元样板、也不参与引擎）。
     * <p>链上台取的是<b>链共享</b>的那一份（链首持有的），因此整条链要么一起可用、要么一起不可用。</p>
     */
    public boolean isBound() {
        final String type = getRecipeType();
        final String name = getChamberName();
        return type != null && !type.isEmpty() && name != null && !name.isEmpty();
    }

    /**
     * 本仓是否<b>还放着任何单元样板</b>。
     * <p>用途：只要仓里还有单元样板，就不允许更改「配方类型」（改类型会让已放样板全部失配、
     * 甚至让引擎行为错乱），必须先把样板全部取走。名字不受此限制。</p>
     */
    public boolean hasAnyUnit() {
        for (int i = 0; i < unitSlots.getContainerSize(); i++) {
            if (!unitSlots.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ========== 链：名字与配置共享（方向 = 方块朝向） ==========
    // 参考实现：Refined Storage 2.0 的自动合成仓 AutocrafterBlockEntity（refinedstorage-common
    // com.refinedmods.refinedstorage.common.autocrafting.autocrafter）。它靠「自己朝向的邻居」串成链：
    //   * getConnectedMachine() —— 自己朝向那一格的方块实体；
    //   * getChainingRoot()     —— 沿「朝向」逐台前进（上限 MAX_CHAINED_AUTOCRAFTERS = 8），走到的那台即链首；
    //   * isPartOfChain()       —— getChainingRoot() != this；
    //   * isHeadOfChain()       —— 自己不是成员、且有邻居自动合成仓指向自己；
    //   * getName()             —— 委托给链首（成员名字一律读链首）；
    //   * setCustomName(name)   —— isPartOfChain() 时直接 return（成员改不了名字）。
    // 本模组沿用同一套「沿朝向走到链首 + 链首唯一持有」，且方向<b>就是方块放置时那支箭头</b>
    // （{@link SequenceExecutionChamberBlock#FACING}，因此机器侧与链指向是同一条朝向）；
    // 只把 RS 的「成员改不了」改成用户要求的「任意一处改动 = 整链同步」（写链首，见 setRecipeType / setChamberName）。
    // 链不进任何缓存：每次按需从当前世界状态推导，因此放置 / 破坏 / 旋转 / 区块加载卸载都会在下一次读取时自然生效。
    //
    // 方向不再由玩家设置 ⇒ 分叉 / 成环 / 超长只可能由「方块怎么摆」造成，必须在纯推导里确定性地收敛
    // （见 winningPredecessor / isCycleCut），绝不做任何破坏性写入（朝向由方块状态决定，无法「清除指向」）：
    //   * 分叉（多台指向同一台）：只有坐标（asLong）最小的那台算「胜出前辈」，其余各自成为链首；
    //   * 成环：环上坐标最大的那台断开（纯推导；环上各台看到的环集合一致 ⇒ 结论一致，不会拉锯）；
    //   * 超长（> 8 台）：照 RS 退回本台自己，于是这一段各台分别持有自己的配置，绝不吞掉任何数据。

    /**
     * 本仓的链指向方向 = 方块放置时的水平朝向（玩家看到的那支箭头）；
     * 旧存档 / 异常状态下 property 缺失时回退到机器朝向字段（二者本就同源）。
     */
    public Direction chainFace() {
        final BlockState state = getBlockState();
        return state.hasProperty(SequenceExecutionChamberBlock.FACING)
            ? state.getValue(SequenceExecutionChamberBlock.FACING) : machineFace;
    }

    /**
     * 只读地取某坐标上的执行仓方块实体：区块未加载 / 不是执行仓 → {@code null}。
     * <b>绝不加载区块、绝不创建方块实体</b>（链解析会被引擎与界面频繁调用，不能有副作用）。
     */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity chamberAt(final BlockPos pos) {
        final Level currentLevel = getLevel();
        if (currentLevel == null || !currentLevel.isLoaded(pos)) {
            return null;
        }
        final BlockEntity blockEntity = currentLevel.getChunkAt(pos)
            .getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        return blockEntity instanceof final SequenceExecutionChamberBlockEntity chamber ? chamber : null;
    }

    /** 沿朝向（未做胜出 / 环处理）直接相邻的那一台执行仓（指向空气 / 未加载 → {@code null}）。 */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity faceTarget() {
        return chamberAt(worldPosition.relative(chainFace()));
    }

    /**
     * <b>链级「同配方」校验的唯一实现</b>：本仓与 {@code other} 是否可以成为同一条链上的相邻两台。
     *
     * <h2>为什么必须有这道校验（用户原话）</h2>
     * <p>用户：「只有当若干执行仓的<b>配方相同</b>（同一个配方 ID / 同一种处理器类型，例如全是
     * {@code create:pressing} 或全是 {@code create:deploying}）时才成链……<b>不同配方类型的仓绝不合并</b>。」
     * 链是「扩容」（多块当一块用），不是「把几条不相干的产线捆在一起」：把冲压仓与机械手仓连成一条，
     * 只会让总线把冲压的步推到机械手、把机械手的步推到冲压机。因此两台<b>都显式配过</b>、
     * 且类型不同的仓之间<b>不连链</b>（它们各自成为独立的一段，各自保留自己的配置，什么都不丢）。</p>
     *
     * <h2>为什么比较「本仓自己的字段」而不是 {@link #getRecipeType()}</h2>
     * <p>{@code getRecipeType()} 是<b>链委托</b>（成员一律读链首那一份），而成员自己的字段通常为空。
     * 若拿委托值比较，「链首→成员」这条边是否成立就会依赖它自己是否成立（自指），判定不稳定。
     * 因此这里比较 {@link #ownRecipeType()}（各自的权威字段）。</p>
     *
     * <h2>为什么「一方为空」也算兼容（关键，绝不是漏判）</h2>
     * <p>整条链的配方类型<b>只存在链首那一份</b>（成员一律转发写入链首，见 {@link #setRecipeType}），
     * 因此成员自己的字段<b>本来就该是空的</b>。若要求「双方都非空且相等」，玩家放好三台仓、
     * 只在链首填一次配方类型，链就会当场断成三截（严重回归）。所以规则是：
     * 只有「双方都显式填过、且不相等」才拒绝连链 —— 这恰好只排除用户明确否定的那种合并。</p>
     */
    private boolean chainRecipeCompatibleWith(
        @org.jetbrains.annotations.Nullable final SequenceExecutionChamberBlockEntity other) {
        if (other == null || other == this) {
            return false;
        }
        final String mine = ownRecipeType();
        final String theirs = other.ownRecipeType();
        return mine.isEmpty() || theirs.isEmpty() || mine.equals(theirs);
    }

    /**
     * {@code target} 的「胜出前辈」：所有指向 {@code target} 的相邻执行仓里坐标（{@link BlockPos#asLong()}）
     * 最小的一台；没有（或被区块卸载遮住）则 {@code null}。
     * <p>纯方位判定（不递归）：分叉时只有一个胜出者，其余各自成为链首 —— 双方看到的邻居集合一致时
     * 结论就一致，因此不会来回拉锯，也不需要任何破坏性写入（方向由方块朝向决定，无法「解除指向」）。</p>
     * <p><b>同配方校验（用户需求）</b>：只有与 {@code target} <b>配方兼容</b>的邻居才算前辈
     * （见 {@link #chainRecipeCompatibleWith}）—— 不同配方类型的仓绝不合并成一条链。这一处判定同时
     * 覆盖 {@link #chainHead()}（沿 {@link #winnerNext()} 前进）与 {@link #collectUpstream}（反向 BFS），
     * 因此整条链的成员集合口径只有一份。</p>
     */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity winningPredecessor(final SequenceExecutionChamberBlockEntity target) {
        SequenceExecutionChamberBlockEntity winner = null;
        for (final Direction direction : Direction.values()) {
            final SequenceExecutionChamberBlockEntity neighbor =
                target.chamberAt(target.worldPosition.relative(direction));
            if (neighbor != null && target.chainRecipeCompatibleWith(neighbor)
                && neighbor.faceTarget() == target
                && (winner == null || neighbor.worldPosition.asLong() < winner.worldPosition.asLong())) {
                winner = neighbor;
            }
        }
        return winner;
    }

    /** 胜出规则下的下一台（不含环处理；指向空气 / 未加载 / 本台不是胜出前辈 / 配方不同 → {@code null}，本台即链首）。 */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity winnerNext() {
        final SequenceExecutionChamberBlockEntity target = faceTarget();
        return target != null && chainRecipeCompatibleWith(target) && winningPredecessor(target) == this
            ? target : null;
    }

    /**
     * 本台是否要按「环」断开自己的指向：沿胜出规则走能绕回自己（即本台在环上），且本台是环上坐标最大的一台。
     * <p>纯推导、不改方块状态：环上各台看到的是同一个环集合 ⇒ 只有坐标最大者判定为「断」，
     * 于是环恰好被切断一次，其余各台都能沿指向走到它 —— 链首因此唯一且确定。</p>
     * <p>行走进度以 {@link #MAX_CHAIN_LENGTH} 为界：超出该长度的环（正常摆放不会出现）不参与判定，
     * 交给 {@link #chainHead()} 的 visited 集兜底终止，仍然不会递归失控。</p>
     */
    private boolean isCycleCut() {
        if (winnerNext() == null) {
            return false; // 本来就到头了：与环无关（提前短路，省掉一次行走）
        }
        final Set<BlockPos> visited = new HashSet<>();
        visited.add(worldPosition);
        long maxPos = worldPosition.asLong();
        SequenceExecutionChamberBlockEntity current = this;
        for (int step = 0; step < MAX_CHAIN_LENGTH; step++) {
            current = current.winnerNext();
            if (current == null) {
                return false; // 走到端点：不在环上
            }
            if (current == this) {
                return maxPos == worldPosition.asLong(); // 绕回自己：只有环上坐标最大者断开
            }
            if (!visited.add(current.worldPosition)) {
                return false; // 撞进「别的」环：本台不在环上
            }
            maxPos = Math.max(maxPos, current.worldPosition.asLong());
        }
        return false; // 环比链长上限还长：交由 chainHead 的 visited 集兜底
    }

    /** 本台在链里的下一台（胜出规则 + 环打断后的最终结果；{@code null} = 本台即链首）。 */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity effectiveNext() {
        final SequenceExecutionChamberBlockEntity next = winnerNext();
        return next == null || isCycleCut() ? null : next;
    }

    /** 本仓是否指向了 {@code other}（「谁指向我」的反向查找用；已计入胜出与环规则）。 */
    private boolean isLinkedTo(final SequenceExecutionChamberBlockEntity other) {
        return other != null && winningPredecessor(other) == this && !isCycleCut();
    }

    /**
     * 本仓所在链的<b>链首</b>（照 RS 的 {@code getChainingRoot}）：沿「胜出 + 环打断」后的朝向逐台前进。
     * <p>链首是链上唯一持有名字 / 配方类型的一方。三种终止都确定：</p>
     * <ol>
     *     <li>下一台不是执行仓（指向空气 / 目标区块未加载）⇒ 当前这台即链首；</li>
     *     <li>绕回走过的坐标（环）⇒ 在当前这台停住；</li>
     *     <li>走满 {@link #MAX_CHAIN_LENGTH} 台仍未到端点（链太长）⇒ 照 RS 的做法<b>退回本台自己</b>，
     *     于是这一段各台分别成为链首、各自持有自己的配置（绝不丢数据）。</li>
     * </ol>
     */
    public SequenceExecutionChamberBlockEntity chainHead() {
        SequenceExecutionChamberBlockEntity current = this;
        final Set<BlockPos> visited = new HashSet<>();
        visited.add(worldPosition);
        for (int step = 0; step < MAX_CHAIN_LENGTH; step++) {
            final SequenceExecutionChamberBlockEntity next = current.effectiveNext();
            if (next == null || !visited.add(next.worldPosition)) {
                return current;
            }
            current = next;
        }
        return this;
    }

    /** 本仓是否是「链的成员」（有链首且链首不是自己）。 */
    public boolean isPartOfChain() {
        return chainHead() != this;
    }

    /** 本仓是否是「链首」且有台指向它（照 RS 的 {@code isHeadOfChain}；台数为 1 时返回 false）。 */
    public boolean isChainHead() {
        if (isPartOfChain()) {
            return false;
        }
        for (final Direction direction : Direction.values()) {
            final SequenceExecutionChamberBlockEntity neighbor = chamberAt(worldPosition.relative(direction));
            if (neighbor != null && neighbor.isLinkedTo(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本仓所在链的全部执行仓（含自身），按坐标升序；不与任何执行舱相连时只含自身。
     * <p>做法：先沿朝向走到链首（端点），再从链首沿「谁指向我」反向 BFS。链的方向由方块朝向定义
     * （所有人都指向链首），因此成员只能从链首那一侧往下数。写入 / 广播 / 快照都以本方法的结果为准。</p>
     */
    public List<SequenceExecutionChamberBlockEntity> chainMembers() {
        final List<SequenceExecutionChamberBlockEntity> members = collectUpstream(chainHead());
        members.sort(Comparator.comparingLong(member -> member.worldPosition.asLong()));
        return members;
    }

    /**
     * 反向 BFS：从 {@code start} 沿「谁指向我」收集成员（<b>含 {@code start} 自身</b>），
     * 限长 {@link #MAX_CHAIN_LENGTH}（与 RS 的 {@code MAX_CHAINED_AUTOCRAFTERS} 一致）。
     * <p>每台至多只有一个「胜出前辈」（分叉时只有坐标最小的那台算数），因此反向走不会分叉；
     * 并且只收「自己也算出同一个链首」的那些台 —— 超长链上被 {@link #chainHead()} 退回自己的那一段
     * 自然被排除，保证 {@code chainMembers()} 与 {@code chainHead()} 的口径永远一致。</p>
     */
    private List<SequenceExecutionChamberBlockEntity> collectUpstream(
        final SequenceExecutionChamberBlockEntity start) {
        final List<SequenceExecutionChamberBlockEntity> members = new ArrayList<>();
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<SequenceExecutionChamberBlockEntity> queue = new ArrayDeque<>();
        visited.add(start.worldPosition);
        queue.add(start);
        while (!queue.isEmpty() && members.size() < MAX_CHAIN_LENGTH) {
            final SequenceExecutionChamberBlockEntity current = queue.poll();
            members.add(current);
            final SequenceExecutionChamberBlockEntity predecessor = current.winningPredecessor(current);
            if (predecessor == null || predecessor.isCycleCut() || predecessor.chainHead() != start) {
                continue; // 没有胜出前辈 / 本台是环上要被断开的那个 / 该台不认这个链首
            }
            if (visited.add(predecessor.worldPosition)) {
                queue.add(predecessor);
            }
        }
        return members;
    }

    /** 链上台数（1 = 独立，没有与任何执行舱相连）。 */
    public int chainSize() {
        return chainMembers().size();
    }

    /**
     * <b>唯一事实源</b>：本仓所在「链 / 分支」上<b>全部执行仓的坐标</b>（含自身；已去重、按坐标升序）。
     *
     * <p>本方法只是既有链推导 {@link #chainMembers()} 的<b>坐标投影</b>（去重 / 排序都由它保证），
     * 因此「链是什么」这件事全工程仍然只有一处实现，不存在第二套链口径。</p>
     */
    public List<BlockPos> chainMemberPositions() {
        final List<SequenceExecutionChamberBlockEntity> members = chainMembers();
        final List<BlockPos> positions = new ArrayList<>(members.size());
        for (final SequenceExecutionChamberBlockEntity member : members) {
            positions.add(member.worldPosition);
        }
        return positions;
    }

    /**
     * <b>只读</b>：本仓所在链（分支）的<b>身份</b> —— 就是这条链的链首坐标。
     *
     * <h2>为什么需要它</h2>
     * <p>线缆搜链必须回答「够到的这几台里，哪些其实是<b>同一个逻辑执行仓</b>」（用户布局：同配方 4 台
     * 沿箭头排成一条链 = 一台，扩容）。这个问题的答案只有链推导知道，因此身份也由链推导给出，
     * 而不是在搜链里另写一套「是不是同一条链」的判断。</p>
     *
     * <h2>为什么用「链首坐标」当身份</h2>
     * <p>链推导是纯方位的：同一台仓每次算出的链首一定相同（{@link #chainHead()} 对环 / 分叉 / 超长链
     * 都有确定的终止规则），而<b>同一条链上任意两台算出的链首必然是同一台</b>（都沿 {@link #effectiveNext()}
     * 走到同一个端点）；反过来，链首相同也必然是同一条链。因此「链首坐标相等 ⇔ 同一个逻辑执行仓」，
     * 可以直接当去重键，不必再比成员集合（{@link #chainMembers()} 也是从链首出发反向收集的，口径同源）。</p>
     * <p><b>为什么不是「链上坐标最小的那台」</b>：那只是成员集合上的一个排序巧合，环 / 分叉 / 超长链时
     * 与链首并不一致；而复用链首就不引入第二套链口径。</p>
     * <p><b>唯一的退化情形（偏保守）</b>：链超过 {@link #MAX_CHAIN_LENGTH} 台时 {@link #chainHead()} 会退回
     * 「本台自己」，于是这一段各台拿到不同身份、被当成不同的链 —— 那与 {@link #chainMembers()} 在同样
     * 情形下各台只认自己那一段的口径完全一致，且落在「宁可判归属未确定」的保守一侧。</p>
     * <p>本方法不含任何写入，也不强制加载区块（只经 {@link #chamberAt} 用 CHECK 取已存在的方块实体），
     * 因此可在线缆搜链 / 归属判定里安全调用。</p>
     */
    public BlockPos chainIdentity() {
        return chainHead().worldPosition;
    }

    /**
     * 链级类别表的一行：类别 + <b>定义它的那台仓</b>（该类别的一切判据都由这台仓回答）。
     *
     * <p>见 {@link #chainCategories()}：这是「一条链 = 一个逻辑执行仓」在<b>类别</b>上的唯一事实源。</p>
     */
    public record ChainBusCategory(BusCategoryInfo info, SequenceExecutionChamberBlockEntity owner) {
    }

    /**
     * <b>唯一事实源</b>：本仓所在链（分支）上<b>全部可见类别</b>，每项带「定义它的那台仓」。
     *
     * <h2>为什么需要它（用户需求：成链 = 扩容，多块当一块用）</h2>
     * <p>用户原话：「这一条链的上面，它的这一个输入输出总线应该保持一样的，它应该同步那个执行仓里面的
     * 所有内容」—— 因此链上<b>任意一处</b>的总线，其<b>可选类别</b>必须是整条链的并集，而不是只看
     * 「总线物理上绑定的那台仓」。链成员各自持有自己的单元样板，所以它们的类别表并不相同
     * （同一台处理器可以承担不同序列装配配方的不同步），只有并集才是「这一个逻辑执行仓」的类别全集。</p>
     *
     * <h2>口径（与既有链推导同源，绝不另立一套）</h2>
     * <ul>
     *     <li>成员集合：{@link #chainMembers()}（唯一事实源，已含同配方校验与去重排序）；</li>
     *     <li>顺序：<b>严格按链成员坐标升序</b>，成员内保持它自己类别表的原顺序；同一类别 id 只保留
     *     <b>第一次出现</b>的那一项（⇒ 属主 = 链上坐标最小的那台定义者）。
     *     这个顺序是<b>全局确定</b>的：链上任意一台仓调用本方法得到的是逐字相同的一份表
     *     ——「谁是某个类别的属主」在整条链上只有一个答案，因此它的判据每条总线每轮只会被算一次
     *     （不会因为问的是哪台仓而换人）。</li>
     *     <li>不串链时链 = 本仓自己 ⇒ 结果与 {@link #busCategories()} 逐字相同（行为零变化）。</li>
     *     <li><b>去重即「一类别一属主」</b>：同一个类别 id 只会带上一个 owner，因此它的一切判据
     *     （排队 / 名额 / 在制件 / 空转）每条总线每轮只会被算<b>一次</b>，绝不会因为链上有 N 台仓
     *     而各算一遍（重复计数会直接弄坏这些闸门）。</li>
     * </ul>
     * <p>只读；不缓存（与链推导同一成本模型：链不进任何缓存，每次按当前世界状态现推）。</p>
     */
    public List<ChainBusCategory> chainCategories() {
        final List<ChainBusCategory> result = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            for (final BusCategoryInfo info : member.busCategories()) {
                if (info.id() != null && seen.add(info.id())) {
                    result.add(new ChainBusCategory(info, member));
                }
            }
        }
        return result;
    }

    /**
     * 只读：本仓所在链（分支）上<b>全部可见类别</b>（含链上其它成员样板生成的类别），按 id 去重、
     * 顺序与 {@link #chainCategories()} 逐字一致（链成员坐标升序 + 成员内原顺序，先到者定义）。
     *
     * <h2>为什么需要它（用户需求：4 台成链 = 一台，能读到里面所有的单元样板）</h2>
     * <p>用户原话：「这四台要被识别成【一台】。然后它应该是能够读取里面所有的单元样板的。」
     * 「本逻辑仓有哪些类别」这个问题必须只有一个答案，而 {@link #busCategories()} 只是
     * <b>本台方块</b>的类别（由本台 {@code unitSlots} 推出，见 {@link #computeBusCategories()}）——
     * 直接拿它回答链级问题就会「只看到自己那一台」。</p>
     *
     * <h2>为什么只是投影，不是第二套表（去重口径）</h2>
     * <p>本方法<b>只</b>把 {@link #chainCategories()}（单一事实源，已按 id 去重、每项带唯一属主仓）
     * 里的 {@link BusCategoryInfo} 取出来，<b>不新增任何判定</b>：同一类别 id 在整条链上仍然只有一个
     * 属主（= 链上坐标最小的定义者），因此依赖「一个类别只被算一次」的闸门
     * （{@code blockedByMachineQueue} / {@code startCapacityForRecipe} / {@code inFlightUnitsForRecipe}）
     * 不会被重复计数。<b>刻意不做</b>「把别台样板并进本台 {@link #ownedSteps()}」那种展开 ——
     * 属主判定必须留在各自那台仓身上。</p>
     * <p>不串链时链 = 本台 ⇒ 结果与 {@link #busCategories()} 逐字相同（行为零变化）。只读，不缓存。</p>
     */
    public List<BusCategoryInfo> chainBusCategories() {
        final List<ChainBusCategory> entries = chainCategories();
        final List<BusCategoryInfo> infos = new ArrayList<>(entries.size());
        for (final ChainBusCategory entry : entries) {
            infos.add(entry.info());
        }
        return infos;
    }

    /**
     * 链级「没显式选过时」的默认导出类别 = 链上任一台仓默认集（输入类 + 中间产物）的并集。
     * <p>为什么必须链级：玩家新放一条输出总线时它是「没显式选过」状态，走的是默认集；
     * 若默认集仍只取绑定仓那一台，链上别的仓的输入料 / 过渡件就永远进不了这条总线的清单
     * （用户要的「接在链上任意一处 = 属于整条链」就不成立）。</p>
     */
    private List<String> chainDefaultExportCategoryIds() {
        final List<String> ids = new ArrayList<>();
        for (final ChainBusCategory entry : chainCategories()) {
            final BusCategoryInfo info = entry.info();
            if (info.isInput() || info.isIntermediate()) {
                ids.add(info.id());
            }
        }
        return ids;
    }

    /**
     * 链级「本链当前真的在跑的序列装配配方」并集（只用于旧存档类别 id 的归一化，见 {@link #normalizeBusOwners()}）。
     * <p>为什么要并集：旧 id（{@code intermediate:<步序>}）的展开被夹在「当前流程」里；
     * 链级归属表是所有成员共用的，若各成员各按自己的流程展开，同一张表就会因调用者不同而不同
     * （面板显示 / 备料 / 推料互相打架）。取并集后表的内容唯一确定。</p>
     */
    private Set<String> chainActivePipelineRecipeIds() {
        final Set<String> ids = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            ids.addAll(member.activePipelineRecipeIds());
        }
        return ids;
    }

    /**
     * 链级输出总线坐标集合（去重、按坐标升序）：链上<b>每一台</b>仓各自「线缆可达」的输出总线求并集，
     * 再按唯一事实源 {@link #chamberInMyChain(BlockPos)}（← {@link #chainMembers()}）过滤。
     *
     * <h2>为什么要从「每台成员各自可达」求并集</h2>
     * <p>一条总线可能只贴着链上的某一台仓（其它成员没有线缆连到它）。若只从本仓出发 BFS，本仓就
     * 看不到它的选择（用户要的「整条链共享同一套总线」就不成立）。这里刻意用
     * {@code member.collectExporters(false)}（<b>纯线缆可达</b>，不带归属过滤）逐个成员枚举，
     * 归属过滤统一在最后做一次 —— 这样既不递归回链推导（{@code collectExporters(true)} 会调
     * {@link #isInMyChain(BlockPos)}），也不会给同一台总线登记第二次（{@link LinkedHashSet} 按坐标去重）。</p>
     *
     * <p><b>「一总线一次」的结构性保证（本次最大风险的正面回答）</b>：本方法只产出<b>坐标集合</b>，
     * 每台总线在其中至多出现一次；而它被消费的地方（{@link #chainBusOwners()} 的登记、
     * {@link #normalizeBusOwners()} 的整表重建）都按坐标去重，因此同一台总线在链级归属表里
     * 只会有一个条目、只会被算一次。</p>
     */
    private List<BlockPos> chainExporterPositions() {
        final Level level = getLevel();
        final Set<BlockPos> found = new LinkedHashSet<>();
        if (level == null || level.isClientSide()) {
            return List.of();
        }
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            for (final BlockPos pos : member.collectExporters(false)) {
                if (!level.isLoaded(pos) || found.contains(pos)) {
                    continue;
                }
                // 只取「已存在」的方块实体（CHECK）：查询阶段绝不为邻块强制初始化
                if (!(level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
                    instanceof final RsccExporterExecutorMode exporter)) {
                    continue;
                }
                if (chamberInMyChain(exporter.rscc$linkedExecutorPos()) != null) {
                    found.add(pos); // 归属判定与既有的 collectExporters(true) 完全同源
                }
            }
        }
        final List<BlockPos> result = new ArrayList<>(found);
        result.sort(Comparator.comparingLong(BlockPos::asLong));
        return result;
    }

    /**
     * <b>唯一事实源</b>：给定坐标上的执行仓是否与本仓同属一条链（分支）—— 是则返回那一台，否则 {@code null}。
     *
     * <p>「链上所有仓都把同一条总线视为自己的」这条语义，全部三处口径（类别归属
     * {@link #busCategoryOwners}、供料目标 {@link #busSupplyTargets()}、收料归属
     * {@code RsccChamberImportStrategy}）都只经本方法判定；<b>不串链时链 = 本仓自己</b>，
     * 于是改造前的「只认物理相邻那一台」是它的退化情形，行为逐字一致。</p>
     */
    @org.jetbrains.annotations.Nullable
    private SequenceExecutionChamberBlockEntity chamberInMyChain(
        @org.jetbrains.annotations.Nullable final BlockPos pos) {
        if (pos == null) {
            return null;
        }
        if (worldPosition.equals(pos)) {
            return this; // 本仓：最常见的一支，省掉一次链推导（区块卸载时链推不出来也不影响这一支）
        }
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            if (member.worldPosition.equals(pos)) {
                return member;
            }
        }
        return null;
    }

    /**
     * 只读：某台总线「自身解析出的执行仓」是否与本仓同属一条链（分支）。
     * <p>见 {@link #chamberInMyChain(BlockPos)}：这是 {@link #isInMyChain(BlockPos)} / 全部归属口径的唯一判据。</p>
     */
    public boolean isInMyChain(@org.jetbrains.annotations.Nullable final BlockPos chamberPos) {
        return chamberInMyChain(chamberPos) != null;
    }

    /**
     * 只读：本仓所在链（分支）上「<b>供料工位坐标 → 物理上认领它的那台执行仓</b>」。
     *
     * <h2>为什么必须有这张表（收料侧按工位判「这一步是谁的」）</h2>
     * <p>链展开后一台输入总线会扫到整条链的工位，而「这一步此刻要不要这件料 / 这份件能不能收回」的判据
     * （{@code stationStepWantsInput} / {@code isTransitionReclaimAllowed} / {@code rscc$isRegisteredStation}）
     * 全部是<b>按仓</b>成立的（各自持有在制件登记表与单元样板）。若一律用「本总线物理上绑定的那一台」去判
     * 别的仓的工位，就会用错仓的步序与登记表 —— 那正是「多开一件 / 抢走正在加工的件」的成因。
     * 因此这里给出每个工位的<b>属主</b>，让收料侧对每一台机器都用它自己那台仓的判据。</p>
     *
     * <h2>数据来源（不新增任何判定）</h2>
     * <ul>
     *     <li>链成员：{@link #chainMembers()}（唯一事实源）；</li>
     *     <li>总线集合：{@link #connectedExporterPositions()}（链口径，已带 20 tick 缓存）—— 即「属于本链的总线」；</li>
     *     <li>每台总线的属主：它<b>自身解析出的执行仓</b>（{@code rscc$linkedExecutorPos()}，与输出策略实际
     *     取货源的那台完全同源）；工位：它朝向的那一格（{@code rscc$supplyTargetPos()}）。</li>
     * </ul>
     * <p><b>去重</b>：同一工位被同链的两台仓各挂一条总线时只保留<b>坐标最小</b>的那台（{@code putIfAbsent} +
     * 链成员按坐标升序），因此一个工位永远只有一个属主、绝不会被两条链口径各判一遍（重复计数）。</p>
     */
    public Map<BlockPos, SequenceExecutionChamberBlockEntity> chainStationOwners() {
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> owners = new LinkedHashMap<>();
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return owners;
        }
        for (final BlockPos busPos : connectedExporterPositions()) {
            if (!level.isLoaded(busPos)) {
                continue;
            }
            // 只取「已存在」的方块实体（CHECK），绝不为查询强制初始化邻块
            if (!(level.getChunkAt(busPos).getBlockEntity(busPos, LevelChunk.EntityCreationType.CHECK)
                instanceof final RsccExporterExecutorMode exporter)) {
                continue;
            }
            final SequenceExecutionChamberBlockEntity owner = chamberInMyChain(exporter.rscc$linkedExecutorPos());
            if (owner == null) {
                continue; // 归属未确定 / 不属于本链：与改造前一致（这条总线不参与本仓的任何判定）
            }
            final BlockPos target = exporter.rscc$supplyTargetPos();
            if (target != null) {
                owners.putIfAbsent(target, owner);
            }
        }
        return owners;
    }

    /** 链上任一台是否放着单元样板（链级「配方类型锁定」的判据：整条链共享同一个配方类型）。 */
    public boolean chainHasAnyUnit() {
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            if (member.hasAnyUnit()) {
                return true;
            }
        }
        return false;
    }

    /** 本仓自己的名字（不做链委托；用于链首以外的场合，例如「拆链时接住原值」的判空）。 */
    private String ownChamberName() {
        return chamberName == null ? "" : chamberName;
    }

    /** 本仓自己的配方类型（不做链委托）。 */
    private String ownRecipeType() {
        return recipeType == null ? "" : recipeType;
    }

    /** 链状态快照（S2C 同步 / 界面 tooltip 用；服务端权威）。 */
    public ChainState chainState() {
        final SequenceExecutionChamberBlockEntity head = chainHead();
        return new ChainState(chainFace().get3DDataValue(), chainMembers().size(), head == this,
            head.worldPosition, head.getChamberDisplayName(), chainHasAnyUnit());
    }

    /**
     * 链状态快照。
     *
     * @param linkOrdinal 本仓的链指向方向序号（即方块朝向 {@link Direction#get3DDataValue()}，只读展示用）
     * @param size        本仓所在链的执行仓台数（1 = 独立）
     * @param head        本仓是否是链首（{@code size > 1} 时才有意义）
     * @param headPos     链首坐标（{@code size == 1} 时等于本台坐标）
     * @param headName    链首的显示名（链首没起名时为坐标字符串）
     * @param chainUnits  链上任一台是否放着单元样板（链级配方类型锁定的判据）
     */
    public record ChainState(int linkOrdinal, int size, boolean head, BlockPos headPos, String headName,
                             boolean chainUnits) {
    }

    /**
     * 本仓<b>被拆掉（方块破坏 / 拆除）</b>时的链移交：把链级共享的名字 / 配方类型交给「指向本台的执行仓」，
     * 使它接任新一段的链首时不至于把配置丢掉（配置只存在链首那一份上，链首一没就等于整条链失忆）。
     * <p><b>最多只有一个接收方</b>：链上每台至多只有一个「胜出前辈」（见 {@link #winningPredecessor}），
     * 所以不存在「多个子台各接一份配置、形成两条同名链」的情况。只写给「还没有自己值」的接收方，
     * 绝不覆盖、绝不销毁。</p>
     * <p>调用时机由方块 {@code onRemove} 保证：世界 / 相邻方块实体都已加载，且不是区块卸载。</p>
     */
    public void handOverChainOnRemoval() {
        if (ownChamberName().isEmpty() && ownRecipeType().isEmpty()) {
            return; // 本台本来就不是权威持有者（成员 / 空仓）：直接消失即可
        }
        if (!isChainHead()) {
            return; // 台数为 1 / 本台不是链首：没有下家需要接住
        }
        for (final Direction direction : Direction.values()) {
            final SequenceExecutionChamberBlockEntity child = chamberAt(worldPosition.relative(direction));
            if (child == null || !child.isLinkedTo(this)) {
                continue; // 只看「指向本台」的那一台
            }
            if (child.ownChamberName().isEmpty() && child.ownRecipeType().isEmpty()) {
                child.recipeType = ownRecipeType();
                child.chamberName = ownChamberName();
                child.markDirtyAndSync();
                LOGGER.info("SequenceExecutionChamber at {} removed; {} takes over the chain binding {} / {}",
                    worldPosition, child.worldPosition, child.recipeType, child.chamberName);
            }
        }
    }

    /**
     * 服务端链自愈（由方块的服务端 ticker 限频调用）：把<b>旧存档留在「非链首」身上的配置归位到链首</b>。
     * <p>方向改为由方块朝向推导后链首可能换人：旧存档把「链指向」单独存在 {@code ChainLink} 里（本轮起忽略），
     * 而名字 / 配方类型本来是写在「当时的链首」那台身上。若新链首恰好是空的，整条链看起来就会「失忆」——
     * 因此由链首从链上把那份配置收上来。规则确定且无破坏性：</p>
     * <ol>
     *     <li>自己已经持有配置的链首无需收；成员 / 独立空仓直接返回；</li>
     *     <li>收上来时取「链上第一份非空配置」，成员按坐标升序 ⇒ 结论唯一、与调用顺序无关；</li>
     *     <li><b>只复制不删除</b>：来源那台自己的值原样保留，即使以后链结构再变也不会丢数据；</li>
     *     <li>旧存档的分叉 / 环在这里<b>无需任何写入</b>：它们已由 {@link #winningPredecessor} /
     *     {@link #isCycleCut} 在纯推导里确定性地收敛。</li>
     * </ol>
     */
    public void sanitizeRayServer() {
        if (--chainSanitizeCooldown > 0) {
            return;
        }
        chainSanitizeCooldown = CHAIN_SANITIZE_INTERVAL_TICKS;
        final Level currentLevel = getLevel();
        if (currentLevel == null || currentLevel.isClientSide()) {
            return;
        }
        if (!ownChamberName().isEmpty() || !ownRecipeType().isEmpty()) {
            return; // 本台已有权威配置（本来就不是「失忆」状态）
        }
        if (isPartOfChain()) {
            return; // 成员不持有权威配置：归位由链首负责
        }
        if (winningPredecessor(this) == null) {
            return; // 没有任何执行舱指向本台（独立空仓）：谈不上「链上另有配置」，廉价短路
        }
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            if (member == this
                || (member.ownChamberName().isEmpty() && member.ownRecipeType().isEmpty())) {
                continue;
            }
            this.recipeType = member.ownRecipeType();
            this.chamberName = member.ownChamberName();
            markDirtyAndSync();
            broadcastChainBinding();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("SequenceExecutionChamber at {} adopted the chain binding {} / {} from {}",
                    worldPosition, recipeType, chamberName, member.worldPosition);
            }
            return;
        }
    }

    /**
     * 服务端：把「本仓 + 本仓所在链上每一台」的绑定 / 链状态快照推给「正打开其中任一台界面」的玩家。
     * <p>链上的名字与配方类型是整链共享的，改动后必须让链上各台的界面立刻刷新，否则会出现
     * 「这一台显示改了、另一台还是旧值」的假象。只在服务端调用；没有查看者时只是空转，不产生任何包。</p>
     */
    public void broadcastChainBinding() {
        final Level currentLevel = getLevel();
        if (!(currentLevel instanceof final net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            member.sendBindingToViewers(serverLevel);
        }
    }

    /** 把本仓的绑定 / 链状态快照发给「当前正打开本仓界面」的玩家（通常 0~1 人）。 */
    private void sendBindingToViewers(final net.minecraft.server.level.ServerLevel serverLevel) {
        for (final net.minecraft.server.level.ServerPlayer player : serverLevel.players()) {
            if (player.containerMenu instanceof final cretae.cookiewyq.rs_create_compat.menu
                    .SequenceExecutionChamberMenu menu
                && menu.getChamber() != null
                && menu.getChamber().getBlockPos().equals(worldPosition)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket.of(this));
            }
        }
    }

    // ========== 新语义（v4）：配方类型 / 显示名绑定（链上由链首唯一持有） ==========

    /**
     * 本仓绑定的配方类型 id（空 = 未绑定）。
     * <p><b>链语义</b>：链上只有链首持有权威值，成员一律读链首 —— 于是整链的配方类型天然一致，
     * 不存在「同一份配置各存一份、读档后分裂」的可能。</p>
     */
    public String getRecipeType() {
        return chainHead().ownRecipeType();
    }

    /**
     * 设置本仓绑定的配方类型 id（null 视为空）。标记保存并同步方块更新。
     * <p><b>链上没有「只改这一台」这回事</b>：本仓是成员时，值一律写到链首（整链唯一那一份），
     * 因此<b>在链上任意一台提交修改 = 整条链立刻生效</b>，绝不会出现「只改了其中一台」的分裂状态。</p>
     */
    public void setRecipeType(final String recipeType) {
        final SequenceExecutionChamberBlockEntity head = chainHead();
        if (head != this) {
            head.setRecipeType(recipeType); // 转发到链首：整链同步
            return;
        }
        final String value = recipeType == null ? "" : recipeType;
        if (value.equals(ownRecipeType())) {
            return;
        }
        this.recipeType = value;
        markDirtyAndSync();
    }

    /**
     * 本仓的用户自定义名（链上由链首唯一持有；空 = 未设置，显示时请用 {@link #getChamberDisplayName()}）。
     * 注意：不能叫 {@code getName()}，那会与方块实体自带的 {@code getName()}（返回 Component）冲突。
     */
    public String getChamberName() {
        return chainHead().ownChamberName();
    }

    /**
     * 设置本仓的用户自定义名（null 视为空）。标记保存并同步方块更新。
     * <p><b>链上没有「只改这一台」这回事</b>：本仓是成员时，值写到链首（整链唯一那一份），
     * 因此<b>在链上任意一台提交改名 = 整条链一起改名</b>。</p>
     */
    public void setChamberName(final String name) {
        final SequenceExecutionChamberBlockEntity head = chainHead();
        if (head != this) {
            head.setChamberName(name); // 转发到链首：整链同步
            return;
        }
        final String value = name == null ? "" : name;
        if (value.equals(ownChamberName())) {
            return;
        }
        this.chamberName = value;
        markDirtyAndSync();
    }

    /** 新语义：本仓能否执行指定配方类型（链共享的配方类型与参数完全相等）。 */
    public boolean acceptsRecipe(final String recipeType) {
        final String own = getRecipeType();
        return own != null && !own.isEmpty() && own.equals(recipeType);
    }

    /**
     * 只读：本仓是否<b>配置完成</b>（名字与配方类型都非空）—— 单元样板管理舱据此决定是否显示本台。
     *
     * <p><b>为什么要这道判据（用户要求）</b>：没配名字 / 没配配方类型的执行舱<b>还没接进任何产线</b>，
     * 出现在管理舱里只会让玩家看到一堆「拿它没办法」的空组；用户原话：「如果说他没有绑定、就是
     * 没有正确配置它的名字和配方类型的话，它不应该显示到单元样板管理器中」。</p>
     *
     * <p>两个值都走<b>链委托</b>（{@link #getChamberName()} / {@link #getRecipeType()}）：整条链只有链首
     * 持有权威值，因此「链首配好了」= 整链都算配好，不会因为成员自己是空值而被误判成未配置
     * （那会把链组的成员凭空删掉）。只读，不改变任何状态。</p>
     */
    public boolean isProperlyConfigured() {
        final String name = getChamberName();
        final String type = getRecipeType();
        return name != null && !name.isEmpty() && type != null && !type.isEmpty();
    }

    /**
     * 新语义：本仓所在网络内是否已有<b>另一条链</b>使用同名（空名视为不冲突）。
     * <p><b>链语义</b>：整条链只有一个共享名字，因此「同链」不算占用（同链改名的合法情形），
     * 只与链首不同的那些执行仓比较。未接入网络时无从比较，返回 false（放行）。</p>
     * <p>判据就是 {@link #occupiedChamberNamesInNetwork()} 的集合成员关系 —— 一处口径，
     * 界面侧的实时禁用与这里的最终拒绝永远同一个结论。</p>
     */
    public boolean isNameTakenInNetwork(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return occupiedChamberNamesInNetwork().contains(name);
    }

    /**
     * 新语义：本仓所在网络内<b>已经占用了的名字集合</b>（去重；不含本台所在链的名字；未接入网络时为空集）。
     *
     * <p><b>为什么单独抽出来</b>：客户端要在玩家输入名字时<b>实时</b>判断重名并禁用「确定」按钮，
     * 而客户端没有网络对象、也读不到别人的方块实体。因此把这份集合随
     * {@code SyncChamberBindingPacket}（打开界面时下发一次）同步给客户端，界面直接做集合成员判断 ——
     * 与这里的服务端判据<b>逐字同源</b>，不会出现「客户端放行、服务端拒绝」或反之
     * （大小写、trim、空名的处理自然一致）。</p>
     */
    public Set<String> occupiedChamberNamesInNetwork() {
        final SequenceExecutionChamberBlockEntity myHead = chainHead();
        final Set<String> names = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity chamber : chambersInNetwork()) {
            if (chamber.chainHead() == myHead) {
                continue; // 自己 / 同一条链：共享同一个名字，不算冲突
            }
            final String name = chamber.getChamberName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 新语义：本仓所在网络内全部执行仓已绑定的配方类型（去重；未接入网络时为空集）。 */
    public Set<String> boundRecipeTypesInNetwork() {
        final Set<String> bound = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity chamber : chambersInNetwork()) {
            final String type = chamber.getRecipeType();
            if (type != null && !type.isEmpty()) {
                bound.add(type);
            }
        }
        return bound;
    }

    /** 本仓所在网络内的全部执行仓（含自身）；未接入网络返回空列表。 */
    private List<SequenceExecutionChamberBlockEntity> chambersInNetwork() {
        return chambersOf(getNode().getNetworkOrNull());
    }

    /**
     * 新语义：显示名 —— 自定义名非空则用自定义名，否则回退为坐标字符串 {@code "x,y,z"}。
     * <p>链上台一律显示链共享的名字（链首持有的那一个），因此链上每台的名字完全一致。</p>
     * <p>注意：不能叫 {@code getDisplayName()} —— RS 基类
     * {@code AbstractBaseNetworkNodeContainerBlockEntity} 已用 {@code final Component getDisplayName()}
     * 实现了 {@link net.minecraft.world.Nameable}，同名不同返回类型会编译失败，故用本名。</p>
     */
    public String getChamberDisplayName() {
        final String name = getChamberName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    /** 标记方块实体需保存，并在服务端向客户端同步一次方块更新。 */
    private void markDirtyAndSync() {
        setChanged();
        connectedExportersCache = null; // 结构 / 模式可能变了：连接检测缓存作废
        connectedImportersCache = null; // 输入总线的连接检测同源作废（用户第 1 条：校验要算上它）
        busCategoriesCache = null;      // 类别列表随之作废（下次访问时重建）
        ownedStepsCache = null;         // 「本仓负责的步」同源作废（它也是按步判定的唯一数据源）
        stepExtraInputsCache = null;    // 「步骤专用投入物」表读上面那份步表，必须一起作废
        inputCouplingCache = null;      // R1 的「候选包含关系」读同一份步表，必须一起作废
        startIngredientsCache = null;   // 「起步原料」表同样读上面那份步表，必须一起作废
        startIngredientRecipesCache = null; // 同源：「起步原料 → 配方」反查表（在制名额用）一起作废
        final Level level = getLevel();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ========== 右键交互：放/取单元样板（无 GUI） ==========

    /** 手持单元样板右键：放入第一个空格，成功返回 true。 */
    public boolean putUnit(final Player player, final ItemStack held) {
        if (!acceptsUnit(held)) {
            return false;
        }
        for (int i = 0; i < unitSlots.getContainerSize(); i++) {
            if (unitSlots.getItem(i).isEmpty()) {
                unitSlots.setItem(i, held.split(1));
                setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * 该单元样板能否放进本仓（用户需求 B13/B15 的硬约束，界面槽位与右键放入共用同一判定）：
     * <ol>
     *     <li>只接受本模组的<b>单元样板</b>（原版 RS 样板、综合样板一律拒收）；</li>
     *     <li>本仓必须先设定好<b>配方类型 + 名字</b>，否则任何样板都放不进去；</li>
     *     <li>样板自带的配方类型必须与本仓绑定的配方类型<b>完全相等</b>
     *     （冲压仓只收冲压单元样板）。</li>
     * </ol>
     */
    public boolean acceptsUnit(final ItemStack stack) {
        if (stack.isEmpty() || !stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get()) || !isBound()) {
            return false;
        }
        final HolderLookup.Provider registries = getLevel() != null
            ? getLevel().registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final UnitData unit = SequencePatternData.readUnit(stack, registries);
        if (unit == null) {
            return false;
        }
        final String unitType = unit.recipeType() == null ? "" : unit.recipeType();
        return !unitType.isEmpty() && unitType.equals(getRecipeType());
    }

    /** 空手右键：从最后一格取出一张单元样板到玩家手中，装不下则弹出到世界。 */
    public boolean takeUnit(final Level level, final BlockPos pos, final Player player) {
        for (int i = unitSlots.getContainerSize() - 1; i >= 0; i--) {
            final ItemStack inSlot = unitSlots.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            final ItemStack one = inSlot.split(1);
            if (inSlot.isEmpty()) {
                unitSlots.setItem(i, ItemStack.EMPTY);
            }
            if (!player.getInventory().add(one)) {
                Block.popResource(level, pos, one);
            }
            setChanged();
            return true;
        }
        return false;
    }

    // ========== 引擎：分布式认领 ==========

    /** 网络节点每 tick 驱动：内部 20 tick 节流，到点做一次全表扫描并至多认领一种资源/一个单元。 */
    public void tickEngine(final Network network) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        // 输出总线类别调度（每 tick）：重建类别 / 归一归属 / 共享类别的轮询均分（必须在 20 tick 节流之前）
        tickBusScheduler();
        // 「按步拒绝」的抑制冷却逐 tick 过期：过期即恢复判定，因此玩家改配置最多 5 秒就自愈
        if (!stepRefusalCooldowns.isEmpty()) {
            final long nowTick = level.getGameTime();
            stepRefusalCooldowns.entrySet().removeIf(entry -> entry.getValue() <= nowTick);
        }
        // 同种资源的认领冷却逐 tick 递减
        if (!claimCooldowns.isEmpty()) {
            final java.util.Iterator<Map.Entry<ResourceKey, Integer>> it = claimCooldowns.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<ResourceKey, Integer> entry = it.next();
                final int remaining = entry.getValue() - 1;
                if (remaining <= 0) {
                    it.remove();
                } else {
                    entry.setValue(remaining);
                }
            }
        }
        // 配方 sequence 长度缓存周期失效，数据包重载后可自愈
        if (--seqCacheAge <= 0) {
            seqSizeCache.clear();
            seqCacheAge = SEQ_CACHE_TICKS;
        }
        // 引擎节流：不到点不扫全表
        if (engineCooldown > 0) {
            engineCooldown--;
            return;
        }
        engineCooldown = ENGINE_INTERVAL_TICKS;
        engineRuns++;

        // ---- 守恒账本的运行期审计（本轮新增：账本此前一个字都不写） ----
        // 为什么必须有：RsccFlowLedger 原先没有任何 Logger，unbalanced() 只被 /rs_create_compat diag
        // 的导出读取，于是「日志里没有失衡告警」其实是「根本没写日志」，不是一个守恒不变量被观测过。
        // 这里每秒（20 tick）审计一次：对平零输出、首次失衡立刻一条、持续失衡合并成一条、
        // 恢复时打一条收尾；只读（只走内存计数器 + 只读活量探针），任何判定都不读它。
        // 放在 engineCooldown 的 return 之后：被节流跳过的 tick 不需要重复审计。
        if (--ledgerAuditCooldown <= 0) {
            ledgerAuditCooldown = LEDGER_AUDIT_INTERVAL_TICKS;
            flowLedger.tickAudit(cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug.machine(
                "chamber", worldPosition), System.currentTimeMillis());
        }

        // ---- 堵塞自愈：把占住供料目标的那一件收回来（2026-10-05） ----
        // 为什么放在最前面、且不受任何门控限制：这件东西<b>已经不在本产线上</b>了（它是废料 / 走错的件），
        // 压在置物台上只会让后续每一次推送被拒 ⇒ 弹「机器满了」并反复挂起。
        // 回收 = 把它搬回网络，属「还东西」而非「开工」，因此与备料门控无关；
        // 它自带保守边界（只碰本产线自己的件、只碰最近真的拒收过的那个坐标），见方法注释。
        recoverBlockingTargetItem();

        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        // 「禁止回流步骤」配置（单元样板的 disallow_inputting_by_step，按配方 id 取阈值）：
        // 本轮只在引擎节流点之后的这一处统一读取，两个输入分支（总线备料 / 面输出认领）共用同一份。
        // 用户第 5 条：把「网络里该件数量下降」也纳入日志（玩家手动拿走 = 外部事件，唯一表现就是数量下降）。
        logPipelineNetDrops(storage);
        final Map<String, Integer> disallowThresholds = disallowInputThresholds(level);
        // 总线输出（延长型输出）：推料 / 喂料交给输出总线（它从本仓内部存储按类别取料推给面对的机器），
        // 但「收集」方向必须保留 —— 否则机器加工完的产出压在机器 / 置物台上没人收，输出总线的目的地
        // 永远 machine_full，链条在第一步之后直接断（用户实测断点的根因：exporter push x0 machine_full）。
        if (outputMode == OutputMode.BUS) {
            // 挂起冻结（用户硬要求「挂起当刻即全面停止，含备料 / 推料 / 搬运」）：整段跳过，
            // 于是既不收集（机器 → 本仓），也不备料（网络 → 本仓）。推料（本仓 → 机器）由
            // isAutoCraftingEnabled()=false 使输出总线拿不到清单 / 在搬运层被 SKIPPED，
            // 输入总线的收回（机器 / 本仓 → 网络）由 RsccChamberImportStrategy 入口短路。
            if (busFrozenGate) {
                return;
            }
            collectFromFaces(level, storage); // 保留收集：OUTPUT 面 → 内部存储；INTERMEDIATE 面 → 回写网络
            // 顺序保证（用户要求）：本 tick 若刚检测到「本条任务结束」，回流已经在 tickBusScheduler 里
            // 做完，这里把备料整 tick 跳过 —— 停止后不允许再补一次料，也不允许把刚回流的东西又吸回来。
            if (!busTaskEndedThisTick) {
                fillInternalForBus(storage, network, disallowThresholds);
            }
            return;
        }
        // 挂起冻结（面输出模式同样整段跳过：不收集、不认领喂料）。
        if (busFrozenGate) {
            return;
        }
        // 面配置：先按「产物输出面 / 中间产物输出面」把相邻容器里的东西收进来
        collectFromFaces(level, storage);
        // 无可用单元样板则休眠
        final List<UnitData> units = collectUnits(level);
        if (units.isEmpty()) {
            return;
        }
        final List<IItemHandler> inputHandlers = itemHandlersFor(FaceMode.INPUT);
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            final ResourceKey key = tracked.resourceAmount().resource();
            final long amount = tracked.resourceAmount().amount();
            if (amount <= 0 || !(key instanceof final ItemResource itemResource)) {
                continue; // 只认领物品资源
            }
            if (claimCooldowns.containsKey(key)) {
                continue; // 同种资源仍在冷却（防多台仓争抢同一物品）
            }
            final ItemStack probe = itemResource.toItemStack(1);
            final SequencedAssembly assembly = probe.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            // 防中间产物错误回流：本配方的单元样板若把「禁止回流起始步骤」配成 N（0-based，单循环内），
            // 则步骤序 ≥ N 的未完成物品一律不认领 —— 即拒绝把它作为输入投回执行舱（留在网络里，绝不销毁）。
            if (isInputBlockedByStep(level, probe, assembly, disallowThresholds)) {
                if (RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.reason("blocked@" + RsccAssemblyDebug.at(worldPosition) + "#" + key,
                        RsccAssemblyDebug.machine("chamber", worldPosition)
                            + " reject {" + describeItem(probe.copyWithCount((int) Math.min(amount, CLAIM_BATCH))) + "}"
                            + " reason=disallow_inputting_by_step"
                            + " step=" + (assembly == null ? "-" : assembly.step()));
                }
                continue;
            }
            final UnitData unit = matchUnit(level, probe, assembly, units);
            if (unit == null) {
                continue; // 该资源不属于本机任何单元
            }
            // 「一次一份」节流（用户要求，防单机械手工况把机器塞满）：
            // 直接读<b>目标机器自己的物品容器</b>——里面还留着上一条没被消化的同种输入时原料时，
            // 这一轮不再送（等机器把它吃掉 / 变成中间产物，下一轮立刻放行）。
            // 判据用「机器是否仍持有该料」而不是「我们发过几次」，因此机器一旦消化就自然恢复供料。
            if (!inputHandlers.isEmpty() && holdsItem(inputHandlers, itemResource.item())) {
                continue;
            }
            // 认领：从网络取出「一份」（{@link #INPUT_FEED_UNIT}），送至机器侧容器，放不下的立即回写网络
            final long extracted = storage.extract(itemResource,
                Math.min(amount, INPUT_FEED_UNIT), Action.EXECUTE, Actor.EMPTY);
            if (extracted <= 0) {
                continue;
            }
            claimCooldowns.put(key, RESOURCE_COOLDOWN_TICKS);
            fedSinceLog += extracted;
            setChanged();
            final ItemStack stack = itemResource.toItemStack(extracted);
            // 方案 2：任务第一步 / 初始原料被输出给机器的这一刻，给这份原料打「原料」标记
            // （只打在送去机器的那一份上；回写网络时仍用未标记的原始资源，网络里不会出现带标记的变体）。
            final ItemStack toFeed = assembly == null && unit.recipe() != null && !unit.recipe().isEmpty()
                ? markedRawMaterial(stack, unit)
                : stack;
            ItemStack leftover = toFeed;
            if (!inputHandlers.isEmpty()) {
                for (final IItemHandler handler : inputHandlers) {
                    for (int i = 0; i < handler.getSlots() && !leftover.isEmpty(); i++) {
                        leftover = handler.insertItem(i, leftover, false);
                    }
                    if (leftover.isEmpty()) {
                        break;
                    }
                }
            } else {
                throttledLog("no INPUT-configured face has an item container; {} x {} returned to the network",
                    extracted, probe.getHoverName().getString());
            }
            long returnedBack = 0;
            if (!leftover.isEmpty()) {
                final long returned = storage.insert(itemResource, leftover.getCount(),
                    Action.EXECUTE, Actor.EMPTY);
                if (returned < leftover.getCount()) {
                    // 极端情况（网络被占满）：剩余部分落回世界，绝不吞物品（落回世界的仍是未标记的原始原料）
                    Block.popResource(level, worldPosition,
                        stack.copyWithCount((int) (leftover.getCount() - returned)));
                }
                returnedBack = returned;
            }
            if (RsccAssemblyDebug.isEnabled()) {
                final long fed = extracted - leftover.getCount();
                final long voided = leftover.getCount() - returnedBack;
                final String faceTag = describePositions(inputFacePositions());
                // 「打原料标记」的判定：只有「不带进度组件的起步原料」这一路才会打标记（方案 2）
                final boolean rawMark = assembly == null;
                // 认领属于「状态翻转」类事件（每台机器每个资源只在状态变化时各打一条，稳态零输出），
                // 因此这里用 transition 而不是 event：持续送料时不会每秒刷一行
                RsccAssemblyDebug.transition(
                    "claim@" + RsccAssemblyDebug.at(worldPosition) + "#"
                        + RsccAssemblyDebug.itemId(itemResource.item()),
                    "step=" + unit.step() + ";mark=" + rawMark + ";to=" + faceTag,
                    RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " claim gate=ALLOWED step=" + unit.step()
                        + " recipe=" + (unit.recipe() == null || unit.recipe().isEmpty() ? "-" : unit.recipe())
                        + " need {" + describeItem(unit.input()) + "}"
                        + " took=" + extracted
                        + " fed=" + fed
                        + " to=" + faceTag
                        + " mark=" + (rawMark
                            ? "raw_material(recipe=" + unit.recipe() + ", step=" + unit.step() + ")"
                            : "none(transitional_passthrough)")
                        + " returned=" + returnedBack
                        + " rejected=" + voided);
                RsccAssemblyDebug.countFeed(fed);
                RsccAssemblyDebug.countReturn(returnedBack);
                RsccAssemblyDebug.countReject(voided);
            }
            return; // 每轮引擎只处理一种资源、一个单元
        }
    }

    /** 收集所有单元样板的单元数据（未绑定「配方类型 + 名字」时视为无样板，本机不工作）。 */
    private List<UnitData> collectUnits(final Level level) {
        if (!isBound()) {
            return List.of();
        }
        final List<UnitData> units = new ArrayList<>(UNIT_SLOTS);
        for (int i = 0; i < unitSlots.getContainerSize(); i++) {
            final ItemStack stack = unitSlots.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final UnitData unit = SequencePatternData.readUnit(stack, level.registryAccess());
            if (unit != null) {
                units.add(unit);
            }
        }
        return units;
    }

    /**
     * 匹配规则：
     * <ol>
     *     <li>资源带 SEQUENCED_ASSEMBLY(recipeId, step)：要求单元 U.recipe() 非空且等于该 recipeId，
     *     并且 {@code resourceStep % seqSize == U.step()}（seqSize 由配方管理器查得并缓存）；
     *     U.recipe() 为空的单元不认领带组件资源。</li>
     *     <li>资源不带组件（起步原料）：要求 U.step()==0、U.recipe() 非空，
     *     且 U.input() 为空或与资源同物品。</li>
     * </ol>
     */
    @org.jetbrains.annotations.Nullable
    private UnitData matchUnit(final Level level,
                               final ItemStack probe,
                               @org.jetbrains.annotations.Nullable final SequencedAssembly assembly,
                               final List<UnitData> units) {
        if (assembly != null) {
            // 过渡件：按 配方 id + 当前展开步（对 seqSize 取模）认领
            final String recipeId = assembly.id().toString();
            final int seqSize = sequenceSize(level, assembly.id());
            if (seqSize <= 0) {
                return null; // 配方不存在（数据包未加载/被移除）
            }
            final int resourceStep = Math.floorMod(assembly.step(), seqSize);
            for (final UnitData unit : units) {
                if (unit.recipe() == null || unit.recipe().isEmpty()) {
                    continue;
                }
                if (!unit.recipe().equals(recipeId)) {
                    continue;
                }
                if (unit.step() != resourceStep) {
                    continue;
                }
                return unit;
            }
            // 该步由本仓负责（同类型的一台机器可以承担多条步，样板只记了其中一步时同样要认领）：
            // 用「本仓负责的步」表兜底，返回一份以本仓配方类型组装的等价单元，供上层取输入 / 记账。
            // 判据仍然是同一个 {@link #ownedSteps()}，不新增第二套「该不该收」的逻辑。
            if (isOwnedStep(recipeId, resourceStep)) {
                final String recipeType = getRecipeType();
                return new UnitData(recipeType == null ? "" : recipeType, ItemStack.EMPTY, "",
                    recipeId, resourceStep, false, "", recipeType == null ? "" : recipeType);
            }
            return null;
        }
        // 起步原料：只允许 step 0 且配方已知、输入匹配的单元认领
        for (final UnitData unit : units) {
            if (unit.step() != 0) {
                continue;
            }
            if (unit.recipe() == null || unit.recipe().isEmpty()) {
                continue;
            }
            final ItemStack input = unit.input();
            if (input != null && !input.isEmpty() && !probe.is(input.getItem())) {
                continue;
            }
            return unit;
        }
        return null;
    }

    /** 查询 Create 序列装配配方的 sequence 长度（带缓存，失败返回 -1）。 */
    private int sequenceSize(final Level level, final ResourceLocation recipeId) {
        final Integer cached = seqSizeCache.get(recipeId);
        if (cached != null) {
            return cached;
        }
        int size = -1;
        final Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(recipeId);
        if (holder.isPresent() && holder.get().value() instanceof final SequencedAssemblyRecipe recipe) {
            size = recipe.getSequence().size();
        }
        seqSizeCache.put(recipeId, size);
        return size;
    }

    // ========== 原料标记 / 防中间产物错误回流 ==========

    /**
     * 汇总本机全部单元样板上的「禁止回流步骤」配置：配方 id → 阈值（数组最小值，0-based、单循环内）。
     * <p>配置键 = {@code disallow_inputting_by_step}（NBT int 数组，见 {@link SequenceMaterialGuard}），
     * 挂在单元样板自己的 CustomData 上；没有配置的配方不出现在结果里（= 放行，对旧存档零影响）。
     * 同一配方多份单元样板配置不一致时取最小阈值（更严格的一方生效，不静默放宽）。</p>
     */
    private Map<String, Integer> disallowInputThresholds(final Level level) {
        Map<String, Integer> thresholds = null;
        for (int i = 0; i < unitSlots.getContainerSize(); i++) {
            final ItemStack stack = unitSlots.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final int threshold = SequenceMaterialGuard.threshold(
                SequenceMaterialGuard.readDisallowInputtingByStep(stack));
            if (threshold == SequenceMaterialGuard.NO_THRESHOLD) {
                continue;
            }
            final String recipe = SequenceMaterialGuard.readPatternRecipeId(stack);
            if (recipe.isEmpty()) {
                continue; // 未记配方 id 的步骤无法唯一定位（配方的不同步骤会串），不参与拦截
            }
            if (thresholds == null) {
                thresholds = new HashMap<>(4);
            }
            thresholds.merge(recipe, threshold, Math::min);
        }
        return thresholds == null ? Map.of() : thresholds;
    }

    /**
     * 该未完成物品是否被「禁止回流步骤」配置拦下（服务端权威）。
     * <p>只做判定，不动任何资源：拦下 = 不认领（物品留在网络里），绝不销毁。</p>
     */
    private boolean isInputBlockedByStep(final Level level, final ItemStack probe,
                                         @org.jetbrains.annotations.Nullable final SequencedAssembly assembly,
                                         final Map<String, Integer> thresholds) {
        if (assembly == null || thresholds.isEmpty()) {
            return false;
        }
        final Integer threshold = thresholds.get(assembly.id().toString());
        if (threshold == null) {
            return false; // 该配方没配「禁止回流步骤」
        }
        if (!SequenceMaterialGuard.isInputBlocked(probe,
            sequenceSize(level, assembly.id()), threshold)) {
            return false;
        }
        throttledLog("refused {}(recipe={}, step={}): past the no-return step configured on its unit pattern;"
                + " it stays in the network (never destroyed)",
            probe.getHoverName().getString(), assembly.id(),
            SequenceMaterialGuard.stepInLoop(assembly, sequenceSize(level, assembly.id())));
        return true;
    }

    /** 复制一份送去机器的原料并打上「原料」标记（任务第一步 / 初始原料输出时刻）。 */
    private static ItemStack markedRawMaterial(final ItemStack stack, final UnitData unit) {
        final ItemStack marked = stack.copy();
        SequenceMaterialGuard.markRawMaterial(marked, ResourceLocation.tryParse(unit.recipe()), unit.step());
        return marked;
    }

    /** 网络资源版本的 {@link #isInputBlockedByStep}（供「总线输出备料」一类的输入路径复用）。 */
    private boolean isInputBlockedResource(final ItemResource resource,
                                           final Map<String, Integer> thresholds) {
        if (thresholds.isEmpty()) {
            return false;
        }
        final Level level = getLevel();
        if (level == null) {
            return false;
        }
        final ItemStack probe = resource.toItemStack(1);
        return isInputBlockedByStep(level, probe,
            probe.get(AllDataComponents.SEQUENCED_ASSEMBLY), thresholds);
    }

    /**
     * 「禁止回流步骤」阈值表的缓存入口（与类别表同一 {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 重建）。
     *
     * <p><b>为什么要缓存</b>：{@link #isTransitionReclaimAllowed} 会被输入总线<b>逐格、逐机器</b>调用，
     * 每次都遍历 54 个样板槽去读 NBT 太浪费。缓存与类别表<b>同一节拍</b>（每
     * {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 重建一次），因此玩家改样板最多 1 秒内生效，
     * 两侧（认领 / 收回）读取到的也始终是同一份口径。</p>
     */
    private Map<String, Integer> cachedDisallowThresholds(final Level level) {
        Map<String, Integer> cached = disallowThresholdsCache;
        if (cached == null) {
            cached = disallowInputThresholds(level);
            disallowThresholdsCache = cached;
        }
        return cached;
    }

    /**
     * 只读判定（供输入总线的「全自动收回」使用）：这份<b>未完成件</b>此刻是否允许被收回 RS 网络。
     *
     * <p><b>为什么需要它（用户实测的最严重问题）</b>：输入总线的全自动收回原先只判「带进度组件就收回」，
     * 于是「刚完成第 1 步、正要送进下一步骤的机器」的中间产物会被立刻抽回网络，链条断在原地
     * （它并没有等到变成「步骤 2 完成」再回流）。收回侧必须与<b>推料侧同一口径</b>：
     * <b>本仓还会把它交给机器继续加工时一律不许收回</b>。</p>
     *
     * <p><b>判据（本轮改为「按步骤」，用户点名要这个口径）</b>：</p>
     * <ol>
     *     <li>被样板配置的「禁止回流步骤」{@code disallow_inputting_by_step} 拦下
     *     （{@code step % sequenceSize >= 阈值}）→ 本仓不会再把它当输入投回 → 允许收回（用户配置优先）；</li>
     *     <li>{@code s + 1 == m}（{@code s} = 物品当前进度步、{@code m} = 本仓某份单元样板被指派的步序）
     *     → <b>这一份正是本仓机器下一步要加工的对象</b> → <b>绝对不许收回</b>（留给机器加工）；</li>
     *     <li>{@code s >= m}（本仓机器该做的已经做完）或本仓没有任何样板与它同配方（机器与它无关）
     *     → 允许收回（交给下一步骤的机器 / 回网）；</li>
     *     <li><b>判不出来就保守</b>（本仓没有可用样板 / 配方总步数 T 查不到 / 本仓尚未进入世界）
     *     → <b>不许收回</b>：宁可留，也不要「推出 → 收回 → 再推出」的空转。</li>
     * </ol>
     * <p>判据本体只有一份（{@link SequenceMaterialGuard#judgeStep}），与推料 / 备料侧的
     * {@link #isNextForMyMachines} 共用，因此对同一 (物品, 机器) 两侧结论严格互补
     * —— 同一 tick 内不可能出现「刚推出又立刻收回」。</p>
     *
     * <p>不带进度组件的物品返回 {@code false}（不是过渡件，由调用方的类别判据负责）；
     * 无论哪条分支都<b>只判定、不搬运</b>，绝不销毁任何资源。</p>
     */
    public boolean isTransitionReclaimAllowed(final ItemStack probe) {
        if (probe.isEmpty()) {
            return false;
        }
        final SequencedAssembly assembly = probe.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (assembly == null) {
            return false; // 不是过渡件：本判定不负责，交给调用方的类别判据
        }
        final Level level = getLevel();
        if (level == null) {
            return false; // 判不了（本仓尚未进入世界）→ 保守「还要它」，与「判不出来一律不收回」同一口径
        }
        // ① 已到 / 超过样板配置的「禁止回流步骤」：本仓不会再把它作为输入投回（用户配置优先，语义照旧）
        if (isInputBlockedByStep(level, probe, assembly, cachedDisallowThresholds(level))) {
            return true;
        }
        // ② 按步骤（s + 1 == m）：本仓只要还有一份样板「下一步就要加工它」就绝不收回
        return stepVerdictFor(level, probe, assembly) == StepVerdict.NOT_MINE;
    }

    /**
     * <b>设计文档 §7.2 规则 3 的唯一实现</b>：无组件的过渡件此刻是否算「归本工位的下一步」。
     *
     * <pre>
     * resolve(候选物品, 工位)：
     *   无组件但物品名 = 某配方的过渡件名 ⇒ AMBIGUOUS
     *   只在【① 该配方此刻确实有活跃订单】且【② 该配方的下一个未被满足的步属于本工位】时，
     *   才当作 IN_STEP(配方, 下一步)
     * </pre>
     *
     * <h2>为什么必须就是「这两个条件」（我前面五次失败的原因）</h2>
     * <p>坚固板的第 1、2 步产出<b>同一个裸物品</b>（{@code unprocessed_obsidian_sheet}，不带
     * {@code SEQUENCED_ASSEMBLY} 组件），步序无从读出 ⇒ 只能照文档用这两个条件反推。
     * 我此前用过五个旁证，全部在用户的配置下恒为假：</p>
     * <ul>
     *     <li>固定时间窗口 —— 用户指出步骤可能长达 60 秒，任何秒数都是错的；</li>
     *     <li>{@code inputItems}（输入类别）—— 冲压仓的输入类别<b>是空集</b>；</li>
     *     <li>{@code stationStepWantsInput} —— 冲压步<b>没有投入物</b>，恒为 false；</li>
     *     <li>{@code busSupplyTargets} 的调用位置 —— 与输出模式耦合；</li>
     *     <li>「登记表里有没有」—— 只证明「推过」，<b>不证明「下一步归我」</b>。</li>
     * </ul>
     *
     * <p>本方法只回答那一个问题，且<b>只读</b>（配方查询 + 类别表 + 订单状态），不搬运任何资源。</p>
     *
     * @param station 该过渡件此刻所在的工位（机器 / 置物台）
     * @param item    该过渡件物品
     */
    public boolean rscc$bareTransitionIsMineNow(final BlockPos station,
                                                @org.jetbrains.annotations.Nullable final Item item) {
        if (item == null || station == null) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (!info.hasStepFilter() || !info.items().contains(item)) {
                continue; // 只关心「中间产物（带步序）」类别，且这件必须是它的候选
            }
            // id 形如 intermediate:<配方 id>:<步序>；配方 id 自身含冒号 ⇒ 步序取最后一个冒号之后
            final String id = info.id();
            final int last = id.lastIndexOf(':');
            if (last <= 0) {
                continue;
            }
            final String recipeId = id.substring("intermediate:".length(), last);
            final int step;
            try {
                step = Integer.parseInt(id.substring(last + 1));
            } catch (final NumberFormatException ignored) {
                continue; // 旧格式（如 intermediate:1）没有配方 ⇒ 判不出来，跳过
            }
            // ① 该配方此刻有活跃订单（没有订单 ⇒ AMBIGUOUS，绝不当成「归我」）
            if (!recipeOrdered(recipeId)) {
                continue;
            }
            // ② 该步属于本仓
            if (!isOwnedStep(recipeId, step)) {
                continue;
            }
            // 且这份件必须就在「本仓给它供料的那台机器」上 ⇒ 本仓给它加工，回收侧不许碰。
            //
            // <b>文档只要求 ① ②，这里把 ③ 写成「空表也算通过」</b>：
            // {@code busSupplyTargets()} 在「面输出（FACE）」模式下恒为空表
            //（它开头就 {@code if (outputMode != BUS) return List.of();}），
            // 若把空表当「不匹配」，面输出模式就会退回旧行为。而 ① ② 已足以断定
            // 「这份件此刻归本仓的这一步」—— 本仓的机器就是本仓在加工的那些工位。
            final List<BlockPos> myTargets = busSupplyTargets();
            if (myTargets.isEmpty() || myTargets.contains(station)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只读（供输出总线的「备料 / 推料」使用）：这份物品是不是<b>本仓机器下一步就要加工的对象</b>
     * （= {@link #isTransitionReclaimAllowed} 的<b>严格互补</b>判定）。
     *
     * <p><b>为什么推料侧也要按步骤（用户实测「网络⇄机器空转」的根因）</b>：备料 / 推料原先只按
     * 「物品是不是这一类别里的」决定，于是<b>注液仓</b>（样板 = 第 0 步）也会把「已经注过液的
     * 过渡件（s = 1）」从网络吸进自己、再推给注液机 —— 而注液机对这份料根本无事可做，只能被
     * 输入总线原样收回网络，于是「推出 → 立刻收回 → 再推出」无限循环，每一圈都要过一次机器
     * （实测日志：网络岩浆每秒掉 500）。按步骤后，注液仓只推「它那一步的料」，
     * 第二步的过渡件只会被负责第二步的仓吸走，拉锯自然消失。</p>
     *
     * <p><b>口径</b>：非过渡件（不带 {@code create:sequenced_assembly} 组件，即配方原料 / 成品 / 废料）
     * 一律返回 {@code true} —— 它们没有「进度步」可言，保持既有行为（原料按类别照常喂）。
     * 过渡件则要求本仓存在一份单元样板，它与这份过渡件同配方、且<b>下一步正轮到它</b>
     * （{@link SequenceMaterialGuard.StepVerdict#NEXT_FOR_MACHINE}）。<b>判不出来</b>
     * （配方总步数查不到 → {@link SequenceMaterialGuard.StepVerdict#UNKNOWN}）时返回 {@code false}：
     * 宁可留在网络里，也不要推给机器后被立刻收回（那正是空转）。</p>
     */
    public boolean isNextForMyMachines(final ItemStack probe) {
        if (probe.isEmpty()) {
            return false;
        }
        final SequencedAssembly assembly = probe.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (assembly == null) {
            // <b>2026-10-05 取证：这一支是「冲压仓给机器 2 件、却从网络抽 0 件」的入口。</b>
            //
            // 事实对照（快照 20261005-185129，chamber@-16,-60,10 冲压）：
            //   item:create:incomplete_track  离开网络=0  进入网络=0  给机器=2  未平账=+1  现存=1
            // 即：它往机器推了 2 件<b>自己从未从网络取过</b>的东西，而本仓真正该加工的
            // {@code create:unprocessed_obsidian_sheet}（坚固板的冲压步中间件）却一件都没推进去。
            //
            // 为什么走到这里：{@code create:unprocessed_obsidian_sheet}（Create 冲压步产出的中间件）
            // <b>没有 SEQUENCED_ASSEMBLY 进度组件</b> ⇒ 本方法在这里直接 {@code return true}（无条件放行）
            // ⇒ <b>推给机器时不做任何步序判定</b>。而下面的
            // {@code SequenceMaterialGuard#judgeStep} 对同样「无进度组件」的产物返回的是
            // {@link StepVerdict#NOT_MINE}（:296-297）—— <b>两处结论相反</b>。
            //
            // 上面 :6440-6443 的注释自己就写着这条放行会造成
            // 「推出 → 收回 → 再推出」的死循环（每圈都要过一次机器）—— 代码与其自身注释矛盾。
            //
            // 这里先只<b>取证</b>（不改判定）：把「被无条件放行的、无进度组件的中间产物」
            // 逐件记一次，并带上目标机器与下一步信息，用来确认它到底被推给了哪台机器。
            // 只读，不搬运、不改变任何判定结果。
            if (RsccAssemblyDebug.isEnabled()) {
                final net.minecraft.world.item.Item item = probe.getItem();
                final String itemId = RsccAssemblyDebug.itemId(item);
                if (itemId != null && (itemId.contains("incomplete_") || itemId.contains("unprocessed_"))) {
                    RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                        + " plain_intermediate_pass item=" + itemId
                        + " hasAssembly=false ownedSteps=" + ownedSteps().keySet()
                        + " reason=no_sequenced_assembly_component_allows_any_machine");
                }
            }
            return true; // 没有进度步可言（原料 / 成品 / 废料）：保持既有行为，交给类别判据
        }
        final Level level = getLevel();
        if (level == null) {
            return false; // 判不了 → 保守：不推进机器
        }
        // 「禁止回流步骤」是玩家对这条配方的显式指令：被拦下的料本仓连认领都不认领，
        // 当然也不该再推给机器（否则推过去也只会被原样收回）。
        if (isInputBlockedByStep(level, probe, assembly, cachedDisallowThresholds(level))) {
            return false;
        }
        return nextForMyMachines(level, probe, assembly);
    }

    // ==================== 「跨阶段中间产物」的直接交接（不经 RS 网络） ====================

    /**
     * <b>只读</b>：这份过渡件的<b>下一步正由本仓负责</b>（= 本仓就是它的接手方）。
     *
     * <p>与 {@link #isNextForMyMachines(ItemStack)} 是同一个判定的<b>命名入口</b>：输入总线侧要用
     * 「我是它的接手方」这个语义（而不是「我能不能把它推出去」），因此单列一个名字把意图写清楚 ——
     * 判定本体仍然只有一份（{@code SequenceMaterialGuard} 的按步结论），不新增第二套口径。</p>
     */
    public boolean wantsTransitionNext(final ItemStack probe) {
        return probe != null && !probe.isEmpty() && isNextForMyMachines(probe);
    }

    /**
     * <b>只读</b>：本仓内部存储此刻能否收下这一份（{@code simulate} 插入，绝不搬运）。
     * 返回可收下的件数（0 = 收不下）。
     */
    public int canAcceptTransitionLocally(final ItemStack probe) {
        if (probe == null || probe.isEmpty()) {
            return 0;
        }
        final ItemStack leftover = itemStorage.insertItem(0, probe.copy(), true);
        return probe.getCount() - leftover.getCount();
    }

    /**
     * 把一份过渡件<b>直接收进本仓内部存储</b>（真实写入）。返回<b>收下后剩余的件数</b>
     * （0 = 全部收下；余量由调用方原样还回机器 / 网络，绝不销毁）。
     */
    public int acceptTransitionLocally(final ItemStack probe) {
        if (probe == null || probe.isEmpty()) {
            return 0;
        }
        final ItemStack leftover = itemStorage.insertItem(0, probe.copy(), false);
        setChanged();
        // 守恒账本：邻仓交接进来的那一份（从本仓边界看是「从外部取回」，同样不复制、不销毁）
        final long accepted = probe.getCount() - leftover.getCount();
        flowLedger.fromMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
            .itemKey(probe.getItem()), accepted);
        return leftover.getCount();
    }

    /**
     * 本网络内的全部执行仓（服务端只读；{@code null} / 未接入网络返回空列表）。
     * <p>枚举走 RS 自己的网络图（{@link com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent}），
     * <b>不实现第二套「谁能连谁」</b>：被扳手断开 / 被分隔框架阻断的那一侧本来就不在图里。</p>
     */
    public static List<SequenceExecutionChamberBlockEntity> chambersOf(
        @org.jetbrains.annotations.Nullable final Network network) {
        if (network == null) {
            return List.of();
        }
        final com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent graph =
            network.getComponent(com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent.class);
        if (graph == null) {
            return List.of();
        }
        final List<SequenceExecutionChamberBlockEntity> chambers = new ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer container
            : graph.getContainers()) {
            if (container.getNode() instanceof final SequenceExecutionChamberNetworkNode node
                && node.getBlockEntity() != null) {
                chambers.add(node.getBlockEntity());
            }
        }
        return chambers;
    }

    /**
     * 只读：仓内这一份是否被<b>该过滤项</b>按步放行（<b>严格</b>口径：「物品 + 同一配方 + 同一步序」）。
     *
     * <p><b>2026-10-06 起推料侧不再用本方法</b>：推料走放宽一档的
     * {@link #busExportAcceptsForPush}（理由见那里的说明）。本方法保留为「该件是否正属于该步」的
     * 严格判据（诊断 / 兼容入口），收回侧用的仍是 {@link #matchesCategoryStep}。</p>
     *
     * <p><b>为什么需要它（用户硬要求：按「步骤」精确过滤，而不是只按物品）</b>：过滤项若只带裸物品，
     * 同一配方里第 N 步与第 N+1 步（同一个处理器 → 同一个过渡件物品）就是同一个过滤项，
     * 勾中一个另一个必然失效。因此中间产物类别的过滤项带上了该步的
     * {@code create:sequenced_assembly} 进度组件，这里按「物品 + 同一配方 + 同一步序（对 T 取模）」
     * 匹配（见 {@link SequenceMaterialGuard#sameStep}）。</p>
     *
     * <p><b>非中间产物过滤项不受影响</b>：原料 / 流体输入类别的过滤项没有进度组件，
     * {@code sameStep} 一律放行，因此行为与既有<b>逐字一致</b>。本方法只读，绝不搬运 / 销毁资源。</p>
     *
     * @param candidate 仓内（或机器侧）待推送的实际物品
     * @param filter    本仓交给该输出总线的过滤项（中间产物类别带进度组件）
     */
    public boolean busExportMatches(final ItemStack candidate, final ItemResource filter) {
        if (candidate.isEmpty() || filter == null || !candidate.is(filter.item())) {
            return false;
        }
        final SequencedAssembly want = filter.toItemStack(1).get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (want == null) {
            return true; // 过滤项不带进度步：按裸物品匹配（原料 / 成品一类的既有行为）
        }
        final Level level = getLevel();
        final int size = level == null ? -1 : sequenceSize(level, want.id());
        return SequenceMaterialGuard.sameStep(
            candidate.get(AllDataComponents.SEQUENCED_ASSEMBLY), want, size);
    }

    /**
     * <b>推料侧</b>（{@code RsccChamberExportStrategy}）专用的放行判据：仓内这一份能否被
     * 「该过滤项所代表的类别」推给本总线朝向的机器。
     *
     * <h2>2026-10-06：为什么推料侧必须比 {@link #busExportMatches} 宽一档</h2>
     * <p>中间产物类别的过滤项带着<b>某一个特定步序</b>（{@link BusCategoryInfo#filterPrototype}），
     * 而 {@link #busExportMatches} 要求「件自身的步序 == 过滤项步序」。可是一台机器可以
     * <b>连续承担同一条配方的多个步</b>（坚固板 {@code create:sturdy_sheet} 的第 2、3 步都是冲压，
     * {@link #ownedSteps()} 里这两步都归本仓）。步序过滤于是把「一台机器」拆成了「N 个必须分别勾选的
     * 类别」：少勾一个步，做完上一步的件就再也推不出去。用户实测症状正是
     * 「冲压一次，然后就回去了」（件已在第 s+1 步，而导出清单里只剩第 s 步的过滤项）。</p>
     *
     * <p><b>放宽的边界（三条，缺一不可）</b>：</p>
     * <ol>
     *     <li>物品必须与过滤项相同；</li>
     *     <li>{@link #isNextForMyMachines(ItemStack)} 必须为真 —— 即「本仓的机器确实还要它」。
     *     步序正确性由 {@link #ownedSteps()} 这一份既有判据把守（推料侧
     *     {@code RsccChamberExportStrategy#transferItem} 本来就先过它），本方法<b>不新增第二套步序判定</b>；</li>
     *     <li>双方都带进度组件时必须是<b>同一条配方</b>（同一个过渡件可能属于多条配方，绝不能混推）。</li>
     * </ol>
     *
     * <p>过滤项不带步序（原料 / 成品 / 流体输入类别）时与既有行为逐字一致；候选件不带进度组件
     * （Create 的 {@code create:unprocessed_*} 一类中间件）时沿用 {@link SequenceMaterialGuard#sameStep}
     * 的既有放宽口径（无步序可供错配）。<b>收回侧不受影响</b>：{@link #matchesCategoryStep}
     * 与 {@link #busExportMatches} 的行为逐字不变。</p>
     *
     * <p><b>过滤项原型的步序不参与判定</b>：它只用来回答「这个类别是不是按步类别」「是不是同一条配方」，
     * 真正被推上去的是仓内那一格原样的 {@link ItemStack}（含它自己的步序）—— 由推料侧保证。</p>
     *
     * <p>只读，绝不搬运 / 销毁任何资源。</p>
     */
    public boolean busExportAcceptsForPush(final ItemStack candidate, final ItemResource filter) {
        if (candidate == null || candidate.isEmpty() || filter == null
            || !candidate.is(filter.item())) {
            return false; // 物品都不同：任何情况下都不推
        }
        if (!isNextForMyMachines(candidate)) {
            // 本仓的机器对它已无活可干（或判不出来）→ 与既有闸门同源地拦下：
            // 放开步序比较之后，这一条是「不推错料」的唯一守卫，绝不能省。
            return false;
        }
        final SequencedAssembly want = filter.toItemStack(1).get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (want == null) {
            return true; // 非按步过滤项：物品相同即放行（原料 / 成品 / 流体输入的既有行为）
        }
        final SequencedAssembly mine = candidate.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (mine == null) {
            return true; // 过滤项带步序而候选不带：无步序可错配（与 sameStep 的既有放宽一致）
        }
        // 同配方 + 本仓还要它 ⇒ 放行（步序按机器归属判，不按勾选判）；id 用 Objects.equals 防两侧任一为 null
        return java.util.Objects.equals(want.id(), mine.id());
    }

    /**
     * 只读（供输入总线的「手动收回」使用）：这一份是否落在该类别里
     * （= 输入总线收回侧的「按步过滤」匹配点，与输出总线侧同源）。
     *
     * <p>非中间产物类别 / 带不出步序的旧类别没有「按步原型」→ 一律放行（既有行为，只按物品匹配）；
     * 中间产物类别则要求「同一配方 + 同一步序」，因此勾「第 N 步」只收该步的过渡件。</p>
     */
    public boolean matchesCategoryStep(final BusCategoryInfo info, final ItemStack candidate) {
        if (info == null || candidate.isEmpty() || !info.hasStepFilter()) {
            return true;
        }
        final Level level = getLevel();
        final SequencedAssembly want = info.filterPrototype().get(AllDataComponents.SEQUENCED_ASSEMBLY);
        final int size = level == null || want == null ? -1 : sequenceSize(level, want.id());
        return SequenceMaterialGuard.sameStep(
            candidate.get(AllDataComponents.SEQUENCED_ASSEMBLY), want, size);
    }

    /**
     * 「按步骤」判定的核心（只读）：把<b>本仓所在链（分支）上成员定义的步</b>的结论聚合成一个三态结论。
     *
     * <h2>2026-10-06：步序来源从「本台」放宽到「本链」（用户需求「成链 = 一台逻辑执行仓 / 扩容」）</h2>
     * <p>界面早已是链级的（{@link #chainCategories()} 按 id 去重、每项带唯一属主仓），因此一条总线
     * 的<b>类别清单</b>含整条链；但搬运层的这一步判定原先只读本台 {@link #ownedSteps()}，
     * 于是「本台样板没定义、链上别台定义了」的那一步被判 {@code NOT_MINE} ⇒
     * 输出总线<b>勾得上却推不动</b>、输入总线还会把它当「没人认领」误报（用户抱怨的引擎侧一半）。
     * 现在步序集合取自 {@link #chainOwnedSteps()}（= 本台 ∪ 同链各台定义的步，纯成员并集）。</p>
     *
     * <p><b>放宽的只有「合起来能不能做」这一问</b>：{@link #ownedSteps()} 的<b>属主语义不变</b>
     * （仍只来自本台 {@code unitSlots}，仍决定 {@link #chainCategories()} 里每个类别的属主仓），
     * 判定<b>仍然在本仓上求值</b>（{@code level} 取本仓 ⇒ 配方总步数 T 与本仓缓存都只有一份；
     * 成员只提供「它定义了哪些步」这一份数据，与 {@link #chainCategories()} 读
     * {@code member.busCategories()} 完全同源）。</p>
     *
     * <p>聚合规则（顺序即优先级）：</p>
     * <ol>
     *     <li>只要有一份样板说「下一步正轮到它」（{@link StepVerdict#NEXT_FOR_MACHINE}）
     *     → 结论就是它：这份料本仓要，<b>推给机器、且绝不收回</b>；</li>
     *     <li>否则只要有一份同配方样板<b>判不出来</b>（配方总步数查不到 → {@link StepVerdict#UNKNOWN}）
     *     → 结论 UNKNOWN：<b>两侧都停手</b>（不推、也不收），宁可留在原处，也不要「推出 → 收回」空转；</li>
     *     <li>否则（本仓没有任何可用样板 / 所有样板都与它无关 / 它那一步本仓已经做完）
     *     → {@link StepVerdict#NOT_MINE}：本仓对它已无活可干 → 允许收回。</li>
     * </ol>
     * <p>与收回侧 {@link #isTransitionReclaimAllowed} 共用本方法，因此两侧结论严格互补：
     * 「本链要它」⇔ 不收回，同一 tick 内不可能出现「刚推出又立刻收回」；导出 / 导入两侧也因此
     * <b>同源</b>（同一份并集、同一个判据本体），不会出现「清单里有、搬运层拒收」或「整堆推给机器」。</p>
     */
    private StepVerdict stepVerdictFor(final Level level, final ItemStack probe,
                                       final SequencedAssembly assembly) {
        // 步序集合 = 整条链的并集（见 #chainOwnedSteps 的去重与「只作成员判定」说明）；
        // 不串链时它 = 本台 ownedSteps()，单台仓行为逐字不变。
        final Map<String, Set<Integer>> owned = chainOwnedSteps();
        if (owned.isEmpty()) {
            return StepVerdict.UNKNOWN; // 判不了（整条链都没有可用样板 / 推不出任何步）→ 保守：不推、也不收
        }
        final int totalSteps = sequenceSize(level, assembly.id());
        boolean unknown = false;
        // 遍历「本链负责的步」而不是「样板自己记的步」：同一台机器管同类型的多个步时，
        // 每一个步都要能被独立判定（用户实测：坚固板的第 2、3 步都是冲压，只认一个步就把第 3 步漏判成
        // 「不是我的」→ 件被过早收回 → 任务卡死）。判据本体仍是唯一的
        // {@link SequenceMaterialGuard#judgeStep}，因此与推料侧结论严格互补。
        for (final Map.Entry<String, Set<Integer>> entry : owned.entrySet()) {
            for (final int step : entry.getValue()) {
                final StepVerdict verdict = SequenceMaterialGuard.judgeStep(
                    probe, entry.getKey(), step, totalSteps);
                if (verdict == StepVerdict.NEXT_FOR_MACHINE) {
                    return StepVerdict.NEXT_FOR_MACHINE; // s % T == m：这份料就是本仓这台机器下一步要加工的
                }
                if (verdict == StepVerdict.UNKNOWN) {
                    unknown = true; // 同配方但总步数查不到：本仓判不出来 → 保守
                }
            }
        }
        return unknown ? StepVerdict.UNKNOWN : StepVerdict.NOT_MINE;
    }

    /**
     * <b>只读</b>：本仓所在链（分支）上「<b>成员定义的步</b>」的并集（配方 id → 单循环步序集合）
     * —— 这是「<b>这条链合起来能不能加工这一步</b>」的唯一事实源，<b>只</b>供
     * {@link #stepVerdictFor}（搬运层的三态判定）消费。
     *
     * <h2>为什么需要它（用户：「这四台要被识别成一台……相当于一个扩容」的引擎侧一半）</h2>
     * <p>界面早就是链级的（{@link #chainCategories()}：链上全部类别按 id 去重、每项带唯一属主仓），
     * 但搬运层的步序判定原先只读<b>本台</b> {@link #ownedSteps()}：一条接在链上任意一处、清单含整条链的
     * 输出总线，遇到「本台样板没定义、链上别台定义了」的那一步时会被判 {@code NOT_MINE}
     * ⇒ <b>勾得上、推不动</b>（{@code RsccChamberExportStrategy#transferItem} 的
     * {@code isNextForMyMachines} / {@code busExportAcceptsForPush}）；收料侧对称地把它当
     * 「没人认领」而抄回网络。本表把「能不能做」这一问提升到链级，两侧同时修好。</p>
     *
     * <h2>它<b>只</b>回答「合起来能不能做」，绝不回答「归谁」（本次最大风险的正面回答）</h2>
     * <p>本表是纯<b>成员判定</b>用的并集，<b>不得</b>流进任何产出「每仓数量 / 属主」的地方 ——
     * {@link #computeBusCategories()}（→ {@link #chainCategories()} 的属主仓）、
     * {@code machineReservedRecipe} / {@code blockedByMachineQueue} / {@code startCapacityForRecipe} /
     * {@code unitCapacityLeftFor} / {@code inFlightUnitsForRecipe} / {@code pooledTransitionalUnits} /
     * {@code startIngredients} 全部继续只读<b>本台</b> {@link #ownedSteps()}。把并集喂给它们 =
     * 让链上 N 台对同一件事各算一遍 = 重复计数，那正是会直接弄坏上述闸门的写法
     * （也正是本工程刻意<b>不</b>做的「ownedSteps 链级展开」：那还会让
     * {@code intermediate:<配方>:2} / {@code result:*} 的属主从真正定义它的那台翻成链上坐标最小的那台，
     * 判据随即在<b>错的机器</b>上求值）。</p>
     *
     * <h2>去重方式（一次都不多算）</h2>
     * <ul>
     *     <li>{@link LinkedHashSet} 按 {@code (配方 id, 步序)} 合并：同一步被链上多台成员同时定义时
     *     只留一份 —— <b>集合语义，没有任何计数</b>，因此不存在「同一台仓被重复计数」；</li>
     *     <li>本表<b>不缓存</b>（与 {@link #chainMembers()} / {@link #chainCategories()} 同一成本模型：
     *     链不进任何缓存，每次按当前世界状态现推），拆台 / 加台 / 拔样板立刻生效；
     *     每台成员的 {@link #ownedSteps()} 仍是<b>它自己</b>那份节拍缓存（不重复解析样板 NBT）；</li>
     *     <li>判定<b>仍在本仓求值</b>：判据本体 {@link SequenceMaterialGuard#judgeStep} 用的是本仓的
     *     {@code level}（配方总步数 T）与本仓缓存，成员只提供「它定义了哪些步」这一份数据
     *     —— 与 {@link #chainCategories()} 读 {@code member.busCategories()} 完全同源；</li>
     *     <li>链上任意一台调用本方法得到的是<b>逐字相同</b>的一份表（成员集合由
     *     {@link #chainMembers()} 唯一确定），因此同一份件在链上任何一台总线那里结论一致，
     *     不会「这台说推、那台说收」地拉锯。</li>
     * </ul>
     *
     * <p><b>不串链时 {@link #chainMembers()} = 本台</b> ⇒ 本表与本台 {@link #ownedSteps()} 逐字相同
     * （单台仓行为零变化）。只读：绝不搬运 / 修改任何状态。</p>
     */
    private Map<String, Set<Integer>> chainOwnedSteps() {
        final Map<String, Set<Integer>> merged = new LinkedHashMap<>();
        for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {
            for (final Map.Entry<String, Set<Integer>> entry : member.ownedSteps().entrySet()) {
                merged.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                    .addAll(entry.getValue());
            }
        }
        return merged;
    }

    /**
     * 「本仓负责的步」表的缓存入口（与类别表同一 {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 节拍重建）。
     *
     * <p><b>为什么缓存</b>：收回侧（输入总线的全自动收回）会逐格、逐机器调用，推料侧与备料侧每节拍也调用，
     * 每次都去解析样板 NBT + 查配方太浪费；节拍与类别表一致，因此两侧读到的永远是同一份口径。</p>
     *
     * <p><b>属主语义（不得放宽）</b>：本表只来自<b>本台</b> {@code unitSlots}（见
     * {@link #computeOwnedSteps}），它是 {@link #chainCategories()} 里「谁是某个类别的属主」的唯一依据；
     * 「整条链合起来能不能加工这一步」是另一个问题，走 {@link #chainOwnedSteps()}。</p>
     */
    private Map<String, Set<Integer>> ownedSteps() {
        Map<String, Set<Integer>> cached = ownedStepsCache;
        if (cached == null) {
            final Level level = getLevel();
            cached = level == null ? Map.of() : computeOwnedSteps(level);
            ownedStepsCache = cached;
        }
        return cached;
    }

    /**
     * <b>「本仓负责的步」的唯一实现</b>（只读：绝不搬运 / 销毁任何资源）。
     *
     * <p>两路来源的并集：</p>
     * <ol>
     *     <li><b>样板自己记的步</b>（旧语义，保留）：按「单循环」归一（{@code step % T}），
     *     与 {@link SequenceMaterialGuard#judgeStep} 的步序域一致 —— 否则 {@code loops > 1} 的配方里
     *     步序 ≥ T 的旧样板永远匹配不上；</li>
     *     <li><b>同一配方里「配方类型与本仓样板一致」的每一步</b>：一台机器（同一个处理器）可以承担
     *     一条配方的多个步骤（坚固板第 2、3 步都是冲压），玩家在那个机器位置<b>只放一份该类型的
     *     单元样板是合法的</b>，这些步同样归本仓。例外：某一步若被<b>另一台（非同集群的）在线执行仓
     *     显式放了同样「配方 + 步序」的样板</b>，则那一步归那台仓，本仓不认（互不越权）。</li>
     * </ol>
     *
     * <p><b>判定只按步骤</b>：本方法只看「配方 id + 步序 + 配方类型」，<b>不做任何方块位置比较</b>；
     * 位置只用于「这一步是不是被别的仓显式认领了」这一条排除，且同一集群的成员共用同一份内部存储，
     * 视为同一台机器（不互相排除）。</p>
     *
     * <p>推不出来的样板（没记配方 id / 配方被数据包移除 / 没记配方类型且没记步序）<b>不参与</b>本表，
     * 保持既有行为（无步序 = 认领侧不认过渡件、判定侧保守），绝不靠猜。</p>
     */
    private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {
        final List<UnitData> units = cachedUnits();
        if (units.isEmpty()) {
            return Map.of();
        }
        final Set<String> claimedElsewhere = stepsClaimedElsewhere(level);
        final Map<String, Set<Integer>> owned = new LinkedHashMap<>();
        for (final UnitData unit : units) {
            final String recipeId = unit.recipe() == null ? "" : unit.recipe();
            if (recipeId.isEmpty()) {
                continue; // 记不下配方 id（v4 纯类型样板）：推不出步序 → 不参与本表
            }
            final SequencedAssemblyRecipe recipe = directAssemblyOf(level, unit);
            final List<SequencedRecipe<?>> sequence = recipe == null ? List.of() : recipe.getSequence();
            final Set<Integer> steps = owned.computeIfAbsent(recipeId, key -> new LinkedHashSet<>());
            if (unit.step() >= 0) {
                steps.add(sequence.isEmpty() ? unit.step() : Math.floorMod(unit.step(), sequence.size()));
            }
            final String recipeType = unit.recipeType() == null ? "" : unit.recipeType();
            if (recipeType.isEmpty() || sequence.isEmpty()) {
                continue; // 无从推断「同一台机器还能做哪些步」→ 只保留样板自己记的那一步
            }
            for (int step = 0; step < sequence.size(); step++) {
                if (!recipeType.equals(SequencedRecipeProbe.recipeTypeId(sequence.get(step).getRecipe()))) {
                    continue; // 这一步不是本仓这台机器能做的
                }
                if (claimedElsewhere.contains(recipeId + "#" + step)) {
                    continue; // 别的仓已经显式认领了这一步 → 归它，本仓不越权
                }
                steps.add(step);
            }
        }
        // <b>严禁把「总样板把某步指派给本仓」并进这里（第 20 轮严重回归的根因）</b>：
        // 曾把 {@link #patternAssignedSteps(Level)} 的产物合并进本方法，于是「本仓<b>能显示</b>某步的类别」
        // 被当成了「本仓<b>拥有</b>该步」。后果就是用户第 1 条：他只是在总线详细配置里勾了列车轨道的
        // 「中间产物 + 石头台阶」类别（那些类别正是由总样板补出来给他勾的），本仓就自认是列车轨道第 0 步
        // 的属主 —— <b>没下单也开始备料 / 推料 / 产出列车轨道</b>，而且间歇性复发（本仓只要因<b>别的</b>
        // 任务把门控打开，它就顺手把列车轨道那一步也跑了；别的任务结束门控一关，它就"正常"了）。
        // 口径（硬底线）：{@link #patternAssignedSteps(Level)} 的产物<b>只能</b>用于「补类别供显示 / 勾选」
        // （见 computeBusCategories），<b>不得</b>进入本方法（= 属主判定），也不得进入任何备料 / 推料 /
        // 导出 / 收回的闸门 —— 属主只能来自「本仓自己的单元样板」+「运行中订单」。
        return owned;
    }

    /**
     * <b>只读</b>：本网络里「总样板的流程编排把某一步指派给了本仓（或本仓所在集群）」的映射
     * （配方 id → 步序集合）。
     *
     * <h2>为什么需要它（用户第 2 条：输出总线类别清单漏了列车轨道）</h2>
     * <p>本仓的类别表原先只从<b>本仓自己的单元样板</b>推导。同一台机器同时服务两条配方时
     * （精密构件与列车轨道的机械手装配步都归它），它的单元样板往往只记了其中一条配方的 id ⇒
     * 另一条配方的类别<b>根本不会生成</b>，界面上看不到、也就无从勾选 —— 用户原话：
     * 「这个列车轨道它也需要机械手进行装配，但这里没有显示……列车轨道已经在这个样板库里面了」。</p>
     * <p>而「哪一步归哪台机器」在<b>总样板</b>里是显式记录的：{@link SequencePatternData.AssemblyData}
     * 的每一步带 {@link SequencePatternData.UnitEntry#machinePos()}。因此从网络里的样板库读一遍即可：
     * <b>凡是把这台机器（或它的集群成员）记为某一步的执行者</b>，那一步就归本仓。判据只读、不搬运资源。</p>
     */
    private Map<String, Set<Integer>> patternAssignedSteps(final Level level) {
        final Map<String, Set<Integer>> assigned = new LinkedHashMap<>();
        if (level == null || level.isClientSide()) {
            return assigned;
        }
        final Network network = getNode().getNetworkOrNull();
        final GraphNetworkComponent graph = network == null
            ? null : network.getComponent(GraphNetworkComponent.class);
        if (graph == null) {
            return assigned;
        }
        final Set<Long> cluster = new HashSet<>();
        cluster.add(worldPosition.asLong());
        for (final BlockPos member : RsccMachineCluster.clusterMembers(level, worldPosition)) {
            cluster.add(member.asLong());
        }
        for (final NetworkNodeContainer container : graph.getContainers()) {
            if (!(container
                instanceof com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer inWorld)) {
                continue;
            }
            final BlockPos pos = inWorld.getLocalPosition();
            if (pos == null || !level.isLoaded(pos)) {
                continue;
            }
            // 只取「已存在」的方块实体（查询阶段绝不强制初始化邻块）
            if (!(level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
                instanceof final SequenceAssemblyExecutorBlockEntity executor)) {
                continue;
            }
            final net.minecraft.world.Container inventory = executor.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                final ItemStack stack = inventory.getItem(slot);
                final SequencePatternData.AssemblyData assembly = stack.isEmpty() ? null
                    : SequencePatternData.readAssembly(stack, level.registryAccess());
                if (assembly == null) {
                    continue;
                }
                for (final SequencePatternData.UnitEntry unit : assembly.units()) {
                    if (unit.machinePos() == null || !cluster.contains(unit.machinePos().asLong())
                        || unit.recipe() == null || unit.recipe().isEmpty() || unit.step() < 0) {
                        continue;
                    }
                    assigned.computeIfAbsent(unit.recipe(), key -> new LinkedHashSet<>()).add(unit.step());
                }
            }
        }
        return assigned;
    }

    /**
     * 只读：本仓所在网络里<b>不由本仓所在集群持有</b>的其它执行仓<b>显式放着的</b>「{@code 配方 id#步序}」集合。
     *
     * <p>用途见 {@link #computeOwnedSteps} 的第 ② 条：按配方类型推断「本仓还能做哪些步」时，把别人
     * 已经明确认领的步排除掉，两台同类型机器各自负责不同步时互不越权。同集群成员被排除在外
     * （它们与本仓共用同一份内部存储，等效于同一台机器）。只读遍历，不改动任何仓。</p>
     */
    private Set<String> stepsClaimedElsewhere(final Level level) {
        final Set<String> claimed = new HashSet<>();
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return claimed;
        }
        final Set<Long> mine = new HashSet<>();
        for (final BlockPos pos : RsccMachineCluster.clusterMembers(level, worldPosition)) {
            mine.add(pos.asLong());
        }
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        if (graph == null) {
            return claimed;
        }
        for (final NetworkNodeContainer container : graph.getContainers()) {
            if (!(container.getNode() instanceof final SequenceExecutionChamberNetworkNode node)
                || node.getBlockEntity() == null) {
                continue;
            }
            final SequenceExecutionChamberBlockEntity other = node.getBlockEntity();
            if (mine.contains(other.getBlockPos().asLong())) {
                continue; // 自己 / 同集群成员：同一台机器，不算「别人认领」
            }
            final net.minecraft.world.Container slots = other.unitSlots;
            for (int i = 0; i < slots.getContainerSize(); i++) {
                final UnitData unit = SequencePatternData.readUnit(slots.getItem(i), level.registryAccess());
                if (unit == null || unit.step() < 0 || unit.recipe() == null || unit.recipe().isEmpty()) {
                    continue;
                }
                claimed.add(unit.recipe() + "#" + unit.step());
            }
        }
        return claimed;
    }

    /** 只读：该「配方 + 单循环步序」是否归本仓（{@link #ownedSteps()} 的成员判定）。 */
    private boolean isOwnedStep(final String recipeId, final int step) {
        if (recipeId == null || recipeId.isEmpty() || step < 0) {
            return false;
        }
        final Set<Integer> steps = ownedSteps().get(recipeId);
        return steps != null && steps.contains(step);
    }

    /**
     * 只读诊断：本仓「负责的步」是否<b>没有任何在线机器认领</b>（用于「为什么这份件被收回网络」的 WARN）。
     *
     * <p>三态结论：{@code MINE}（本仓要做）/ {@code ELSEWHERE}（别的在线仓显式认领了这一步）/
     * {@code NOBODY}（同类型没有任何在线仓放着能承担这一步的样板）。{@code NOBODY} 就是用户要看到的
     * 「下一步没有任何机器认领 → 该件只能留在网络等玩家处理」。</p>
     */
    private String stepOwnerDetail(final Level level, final SequencedAssembly assembly, final int step) {
        final String recipeId = assembly.id().toString();
        if (isOwnedStep(recipeId, step)) {
            return "MINE";
        }
        if (stepsClaimedElsewhere(level).contains(recipeId + "#" + step)) {
            return "ELSEWHERE";
        }
        return "NOBODY";
    }

    /** 只读：这份过渡件「下一步」是单循环内的第几步（{@code s % T}；判不出来返回 -1）。 */
    private int nextStepOf(final Level level,
                           final @org.jetbrains.annotations.Nullable SequencedAssembly assembly) {
        if (level == null || assembly == null) {
            return -1;
        }
        final int totalSteps = sequenceSize(level, assembly.id());
        return totalSteps <= 0 ? -1 : Math.floorMod(assembly.step(), totalSteps);
    }

    /**
     * 只读（总线输出模式专用）：是否有<b>相连输出总线选了</b>「能匹配这份资源」的类别。
     *
     * <p><b>为什么需要它</b>：「拉进内部存储」必须与「推给机器」配对 —— 没有总线会推它，就别把它从网络
     * 吸进来（吸进来只会堵在仓里，比留在网络更难处理）。非过渡件的类别不带步序，只要类别被选就匹配，
     * 因此与既有行为逐字一致；过渡件按步匹配（同一个 {@link #matchesCategoryStep}），于是
     * 「勾了第 2 步、没勾第 3 步」时不会把第 3 步的件吸进仓里。</p>
     */
    private boolean anyBusOwnsResource(final ItemStack probe, final ItemResource resource) {
        if (busCategoryOwners.isEmpty()) {
            return false;
        }
        for (final BusCategoryInfo info : busCategories()) {
            if (!busCategoryOwners.containsKey(info.id())) {
                continue;
            }
            if (!info.items().contains(resource.item())) {
                continue;
            }
            if (matchesCategoryStep(info, probe)) {
                return true;
            }
            // <b>2026-10-06 拉入侧放宽（同伴 A 指出的唯一缺口）。</b>
            //
            // 旧判据要求「某个被选中的类别恰好等于这份件的步序」。于是<b>只勾了一个步类别</b>时，
            // 另一步的件会被判 {@code no_bus_selected_this_step} <b>留在网络里</b> ——
            // 无论推送侧放得多宽都跑不到（件根本进不了仓）。
            //
            // 正确口径与推送侧同源（见 {@code busExportAcceptsForPush}）：<b>本仓拥有这份件的步序</b>
            // 就够了，不要求「玩家勾选的类别恰好等于那一步」。理由：步序由件自身的
            // {@code SEQUENCED_ASSEMBLY} 组件决定，而「该步归谁」由本仓的单元样板决定
            // （{@link #ownedSteps()}）—— 这两者才是唯一判据；「玩家勾了哪个类别」只决定
            // 「哪条总线负责搬运」，不该决定「这份料本仓要不要」。
            //
            // 只对<b>过渡件</b>放宽（原料 / 成品仍走原来的严格类别匹配），因此不影响既有语义。
            final SequencedAssembly assembly = probe.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (assembly != null && isOwnedStep(assembly.id().toString(),
                Math.max(0, nextStepOf(getLevel(), assembly)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录一次「按步拒绝」：短冷却内不再为同一资源重复判定 / 重复抱怨（打断「收回 → 立刻再试 → 再拒」）。
     * <p>只记拒绝结论，因此正常流不会被抑制；键的基数 = 被拒过的资源种类（正常很小），超限整表清空防无界增长。</p>
     */
    private void suppressStepRefusal(final ItemResource resource, final long now) {
        if (stepRefusalCooldowns.size() > 64 && !stepRefusalCooldowns.containsKey(resource)) {
            stepRefusalCooldowns.clear();
        }
        stepRefusalCooldowns.put(resource, now + STEP_REFUSAL_COOLDOWN_TICKS);
    }

    /** 只读：本仓样板是否「下一步正轮到它加工这份过渡件」（{@link #stepVerdictFor} 的布尔投影）。 */
    private boolean nextForMyMachines(final Level level, final ItemStack probe,
                                      final SequencedAssembly assembly) {
        return stepVerdictFor(level, probe, assembly) == StepVerdict.NEXT_FOR_MACHINE;
    }

    /**
     * 单元样板列表的缓存入口（与类别表同一 {@value #BUS_SCHEDULE_INTERVAL_TICKS} tick 节拍重建）。
     * <p><b>为什么缓存</b>：{@link #isTransitionReclaimAllowed} 会被输入总线逐格、逐机器调用，
     * 每次都遍历 54 个槽并解析样板 NBT 太浪费；认领侧本来也是每
     * {@link #ENGINE_INTERVAL_TICKS} tick 才取一次样板，因此节拍一致、口径不变。</p>
     */
    private List<UnitData> cachedUnits() {
        List<UnitData> cached = unitsCache;
        if (cached == null) {
            final Level level = getLevel();
            cached = level == null ? List.of() : collectUnits(level);
            unitsCache = cached;
        }
        return cached;
    }

    /** 当前机器朝向（面配置的默认输入面即该方向）。 */
    public Direction getMachineFace() {
        return machineFace;
    }

    /** 取指定面模式下的相邻物品容器（按面顺序）。 */
    private List<IItemHandler> itemHandlersFor(final FaceMode mode) {
        final Level level = getLevel();
        if (level == null) {
            return List.of();
        }
        final List<IItemHandler> handlers = new ArrayList<>(2);
        for (final Direction direction : Direction.values()) {
            if (getFaceMode(direction) != mode) {
                continue;
            }
            final BlockPos pos = worldPosition.relative(direction);
            final BlockEntity neighbor = level.getBlockEntity(pos);
            if (neighbor == null) {
                continue;
            }
            final IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
                pos, neighbor.getBlockState(), neighbor, direction.getOpposite());
            if (handler != null) {
                handlers.add(handler);
            }
        }
        return handlers;
    }

    /**
     * 这些物品容器里是否还留着某物品（= 上一次送过去的那份「输入时原料」还没被机器消化）。
     *
     * <p><b>为什么用「读机器容器」而不是「我们自己发过几次」</b>：机器把料吃掉、或把它变成中间产物 /
     * 推到下一个工位后，容器里就查不到它了 —— 此刻立刻放行下一份。因此节流是<b>自愈</b>的，
     * 不依赖任何需要持久化 / 需要额外同步的计数（重启、换机器、拆装都不会卡死）。
     * 只读判定，不动任何资源。</p>
     */
    private static boolean holdsItem(final List<IItemHandler> handlers, final Item item) {
        for (final IItemHandler handler : handlers) {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (handler.getStackInSlot(i).is(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取指定面模式下的相邻流体容器。 */
    private List<net.neoforged.neoforge.fluids.capability.IFluidHandler> fluidHandlersFor(final FaceMode mode) {
        final Level level = getLevel();
        if (level == null) {
            return List.of();
        }
        final List<net.neoforged.neoforge.fluids.capability.IFluidHandler> handlers = new ArrayList<>(2);
        for (final Direction direction : Direction.values()) {
            if (getFaceMode(direction) != mode) {
                continue;
            }
            final BlockPos pos = worldPosition.relative(direction);
            final BlockEntity neighbor = level.getBlockEntity(pos);
            if (neighbor == null) {
                continue;
            }
            final net.neoforged.neoforge.fluids.capability.IFluidHandler handler = level.getCapability(
                Capabilities.FluidHandler.BLOCK, pos, neighbor.getBlockState(), neighbor, direction.getOpposite());
            if (handler != null) {
                handlers.add(handler);
            }
        }
        return handlers;
    }

    /**
     * 面配置驱动的收集：
     * <ul>
     *     <li>{@link FaceMode#OUTPUT} 面：从相邻容器抽出<b>产物</b>（物品 / 流体）存入本仓内部存储；</li>
     *     <li>{@link FaceMode#INTERMEDIATE} 面：从相邻容器抽出<b>过渡件</b>并直接回写 RS 网络
     *     （供下一个执行仓认领；网络装不下时并入内部存储，绝不丢物）。</li>
     * </ul>
     */
    private void collectFromFaces(final Level level, final StorageNetworkComponent storage) {
        final boolean debug = RsccAssemblyDebug.isEnabled();
        // 产物输出面 → 内部物品存储
        for (final IItemHandler handler : itemHandlersFor(FaceMode.OUTPUT)) {
            for (int i = 0; i < handler.getSlots(); i++) {
                final ItemStack snapshot = handler.getStackInSlot(i).copy();
                final int moved = moveToStorage(handler, i);
                if (moved > 0) {
                    setChanged();
                    // 守恒账本：机器 → 本仓的那一份（产物面收集）
                    flowLedger.fromMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                        .itemKey(snapshot.getItem()), moved);
                    if (debug) {
                        RsccAssemblyDebug.transition(
                            "collect@" + RsccAssemblyDebug.at(worldPosition) + "#product:"
                                + RsccAssemblyDebug.itemId(snapshot.getItem()),
                            "seen",
                            RsccAssemblyDebug.machine("chamber", worldPosition)
                                + " collect kind=product {"
                                + describeItem(snapshot.copyWithCount(moved)) + "}"
                                + " moved=" + moved + " to=internal");
                        RsccAssemblyDebug.countCollect(moved);
                    }
                }
            }
        }
        // 中间产物输出面 → 回写网络（装不下才留在内部存储）
        for (final IItemHandler handler : itemHandlersFor(FaceMode.INTERMEDIATE)) {
            for (int i = 0; i < handler.getSlots(); i++) {
                final ItemStack simulated = handler.extractItem(i, COLLECT_BATCH, true);
                if (simulated.isEmpty()) {
                    continue;
                }
                // 「原料」标记只用于在回流这一刻区分「未被机器吃掉的原料」与「未完成中间产物」：
                // 回写网络前把标记去掉，网络里因此只有「原始原料」这一种资源（不影响正常合成 / 自动合成匹配）；
                // 物品本身数量不变、一个都不会少（下面只按实际插入成功的数量抽取）。
                final boolean rawMaterial = SequenceMaterialGuard.isRawMaterial(simulated);
                final ItemResource insertResource = rawMaterial
                    ? new ItemResource(simulated.getItem(), DataComponentPatch.EMPTY)
                    : ItemResource.ofItemStack(simulated);
                final long inserted = storage.insert(insertResource,
                    simulated.getCount(), Action.EXECUTE, Actor.EMPTY);
                if (inserted > 0) {
                    handler.extractItem(i, (int) inserted, false);
                    setChanged();
                }
                final ItemStack leftover = handler.extractItem(i, COLLECT_BATCH, true);
                int stashed = 0;
                if (!leftover.isEmpty()) {
                    stashed = moveToStorage(handler, i);
                    if (stashed > 0) {
                        setChanged();
                        // 守恒账本：机器 → 本仓的那一份（网络装不下而暂存内部存储的过渡件）
                        flowLedger.fromMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                            .itemKey(simulated.getItem()), stashed);
                    }
                }
                if (debug) {
                    RsccAssemblyDebug.transition(
                        "collect@" + RsccAssemblyDebug.at(worldPosition) + "#intermediate:"
                            + RsccAssemblyDebug.itemId(simulated.getItem()),
                        "raw_stripped=" + rawMaterial,
                        RsccAssemblyDebug.machine("chamber", worldPosition)
                            + " collect kind=intermediate {" + describeItem(simulated) + "}"
                            + " mark=" + (rawMaterial ? "raw_material_removed" : "none")
                            + " reflow=ALLOWED(to_next_step)"
                            + " to_network=" + inserted
                            + " to_internal=" + stashed
                            + " rejected=" + (simulated.getCount() - inserted - stashed));
                    RsccAssemblyDebug.countCollect(simulated.getCount());
                    RsccAssemblyDebug.countReturn(inserted);
                    RsccAssemblyDebug.countReject(simulated.getCount() - inserted - stashed);
                }
            }
        }
        // 产物输出面 → 内部流体存储（统一安全语义：先模拟自身剩余容量 → 只抽能收下的量 → 差额回滚回来源容器）
        for (final net.neoforged.neoforge.fluids.capability.IFluidHandler handler
            : fluidHandlersFor(FaceMode.OUTPUT)) {
            final FluidStack probe = handler.getTanks() > 0
                ? handler.getFluidInTank(0) : FluidStack.EMPTY;
            final int moved = SafeCollect.pullFluid(handler, outputTank, FLUID_COLLECT_BATCH,
                rejected -> LOGGER.warn("SequenceExecutionChamber at {} failed to hand back {} mB of fluid"
                    + " (source container refused the rollback)", worldPosition, rejected));
            if (moved > 0) {
                setChanged();
                // 守恒账本：机器 → 本仓的那一份（产物面流体收集，单位 mB）
                if (!probe.isEmpty()) {
                    flowLedger.fromMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger
                        .fluidKey(probe.getFluid()), moved);
                }
                if (debug) {
                    RsccAssemblyDebug.transition(
                        "collect@" + RsccAssemblyDebug.at(worldPosition) + "#product:fluid:"
                            + RsccAssemblyDebug.fluidId(probe.getFluid()),
                        "seen",
                        RsccAssemblyDebug.machine("chamber", worldPosition)
                            + " collect kind=product {fluid=" + RsccAssemblyDebug.fluidId(probe.getFluid())
                            + " x" + moved + "} moved=" + moved + " to=internal");
                    RsccAssemblyDebug.countCollect(moved);
                }
            }
        }
    }

    /**
     * 把相邻容器的某格尽量搬进本仓物品存储（内部存储，满了就进磁盘缓存；不可搬时不动任何东西），
     * 返回搬运成功的数量。
     * <p><b>安全语义</b>（由 {@link SafeCollect#pullItems} 统一实现）：先模拟自身剩余容量 →
     * 只按能收下的量抽取 → 插入 → 执行不足的差额回滚回来源格；自身缓存（内部 + 磁盘）满时<b>一格都不抽</b>。</p>
     */
    private int moveToStorage(final IItemHandler handler, final int slot) {
        return SafeCollect.pullItems(handler, slot, itemStorage, COLLECT_BATCH, this::popIntoWorld);
    }

    /** 兜底归宿：把「放不进任何存储」的残料落到本方块处（绝不销毁）。 */
    private void popIntoWorld(final ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        final Level level = getLevel();
        if (level != null && !level.isClientSide()) {
            Block.popResource(level, worldPosition, stack);
        }
    }

    // ========== 诊断（全部只读，且调用点一律先判 RsccAssemblyDebug.isEnabled()） ==========

    /** 六个面的模式签名（{@code north=none,east=input,...}），供诊断日志使用。 */
    private String faceModeSignature() {
        final StringBuilder sb = new StringBuilder(64);
        for (final Direction direction : Direction.values()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(direction.getName()).append('=').append(getFaceMode(direction).key());
        }
        return sb.toString();
    }

    /**
     * <b>链级</b>当前可见类别 + 每批需求量的签名（{@code input:xxx=2,fluid:yyy=250,intermediate=1}）。
     *
     * <p><b>为什么改成链级</b>：本签名进的是 {@code cats@} 诊断日志，是「这台逻辑执行仓到底看得见哪些
     * 类别」的唯一证据来源。链上 4 台仓各自只持有自己的单元样板 ⇒ 只打本台的话，日志里那 4 台会各显示
     * 一小份（实测：链上 3 台各 2 项、1 台 5 项），与界面上的链级类别并集（5 项）对不上，
     * 玩家据此判断「读不到里面所有的单元样板」完全合理。改成链级后日志与界面同源
     * （都走 {@link #chainBusCategories()}，去重口径见那里），不再有两份互相矛盾的事实。</p>
     */
    private String categorySignature() {
        final StringBuilder sb = new StringBuilder(96);
        for (final BusCategoryInfo info : chainBusCategories()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(info.id()).append('=').append(info.amount());
        }
        return sb.toString();
    }

    /** 诊断用：当前配置为 INPUT 面、且相邻确实存在物品容器的方块坐标（只读，不做任何搬运）。 */
    private List<BlockPos> inputFacePositions() {
        final Level level = getLevel();
        if (level == null) {
            return List.of();
        }
        final List<BlockPos> positions = new ArrayList<>(2);
        for (final Direction direction : Direction.values()) {
            if (getFaceMode(direction) != FaceMode.INPUT) {
                continue;
            }
            final BlockPos pos = worldPosition.relative(direction);
            final BlockEntity neighbor = level.getBlockEntity(pos);
            if (neighbor == null) {
                continue;
            }
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, neighbor.getBlockState(),
                neighbor, direction.getOpposite()) != null) {
                positions.add(pos);
            }
        }
        return positions;
    }

    /** 物品堆叠 → {@code item=<id> x<count>}（空堆叠返回 {@code item=-}）。 */
    private static String describeItem(final ItemStack stack) {
        return stack == null || stack.isEmpty()
            ? "item=-"
            : "item=" + RsccAssemblyDebug.itemId(stack.getItem()) + " x" + stack.getCount();
    }

    /** 坐标列表 → {@code (x,y,z)|(x,y,z)}（空列表返回 {@code -}）。 */
    private static String describePositions(final List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder(48);
        for (final BlockPos pos : positions) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(RsccAssemblyDebug.at(pos));
        }
        return sb.toString();
    }

    /** 限频日志：仅当长时间未打印时输出汇总/提示，严禁刷屏。 */
    private void throttledLog(final String message, final Object... args) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return; // 诊断总开关关闭时不输出任何东西
        }
        if (--logCooldown > 0) {
            return;
        }
        logCooldown = LOG_INTERVAL_TICKS;
        if (fedSinceLog > 0) {
            LOGGER.info("SequenceExecutionChamber at {} fed {} item(s) in the last {} ticks ({} engine runs)",
                worldPosition, fedSinceLog, LOG_INTERVAL_TICKS, engineRuns);
            fedSinceLog = 0;
        }
        LOGGER.info("SequenceExecutionChamber at {} " + message, args);
    }

    // ========== 持久化 ==========

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // <b>单元样板槽逐格带 "Slot" 落盘</b>（收敛到共用实现 {@link RsccSlotNbt}）。
        // 为什么必须这样（与其余五台机器同一根因）：原版 {@code SimpleContainer#createTag} 只写物品、
        // <b>不写 "Slot"</b>，配套的 {@code fromTag} 走的是 {@code addItem}（先并入同类堆、再占第一个空槽）——
        // 玩家把同一张 / 不同类型样板放在 3、17、42 这类不连续的格子里，存盘再读回就会被挤进靠前的格子，
        // 于是「每一步的样板」与「槽位下标」的对应关系被破坏（按槽位登记配方 / 步序的缓存随之错位）。
        tag.put("UnitSlots", RsccSlotNbt.write(unitSlots, registries));
        // 磁盘槽已移除：新版<b>不再写出</b> DiskSlot 键（老档里的那个键由 readLegacyDiskSlot 迁移一次后自然消失）
        tag.putInt("Facing", machineFace.get3DDataValue());
        // 新语义：配方类型 / 显示名
        tag.putString("ChamberRecipeType", recipeType == null ? "" : recipeType);
        tag.putString("ChamberName", chamberName == null ? "" : chamberName);
        // 链方向不再持久化：它就是方块朝向（{@code FACING}），由 blockstate 自带保存，
        // 「谁是链首」每次按当前世界状态现推即可（旧档遗留的 ChainLink 写入随本轮一并去掉）。
        // 面配置（6 个面的模式序号）
        final int[] modes = new int[faceModes.length];
        for (int i = 0; i < faceModes.length; i++) {
            modes[i] = faceModes[i].ordinal();
        }
        tag.putIntArray("FaceModes", modes);
        // 输出模式（面输出 / 总线输出；旧存档缺省 = 面输出）
        tag.putInt("OutputMode", outputMode.ordinal());
        // 输出总线「类别归属」（服务端权威：类别 id → 有序输出总线坐标列表；共享均分的权威数据）
        final CompoundTag ownersTag = new CompoundTag();
        for (final Map.Entry<String, List<BlockPos>> entry : busCategoryOwners.entrySet()) {
            final long[] positions = new long[entry.getValue().size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = entry.getValue().get(i).asLong();
            }
            ownersTag.putLongArray(entry.getKey(), positions);
        }
        tag.put("BusCategoryOwners", ownersTag);
        // 旧存档迁移标记 + 尚未结算的旧磁盘（见 #loadAdditional：磁盘槽已按用户要求移除，
        // 老档里那一块盘必须退回世界，绝不丢；两处都写才能保证「迁移一次 + 崩溃也不丢」）
        tag.putBoolean(TAG_LEGACY_DISK_MIGRATED, legacyDiskMigrated);
        if (pendingLegacyDisk != null && !pendingLegacyDisk.isEmpty()) {
            tag.put(TAG_LEGACY_DISK_PENDING, pendingLegacyDisk.save(registries));
        }
        // 内部存储（与自动合成仓同一套结构；重载后内容零损耗）
        // 集群持久化边界：一个集群只有主控把「共享的那一份内容」落盘，其余成员写空载荷，
        // 否则同一份内容会被写成多份，读档合并后资源翻倍（复制事故）。
        if (clusterOwnsPayload) {
            tag.put("OutputItems", outputStorage.serializeNBT(registries));
            if (!outputTank.isEmpty()) {
                tag.put("OutputFluids", outputTank.writeToNBT(registries, new CompoundTag()));
            }
        }
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("UnitSlots")) {
            // <b>逐格放回原槽位下标</b>（共用实现 {@link RsccSlotNbt}：有 "Slot" 就按下标落位；
            // 旧档没有 "Slot" 时退回原版 {@code fromTag} 的兼容搬运 —— 只做一次、绝不丢物，
            // 下一次存盘即自动升级为新格式）。
            RsccSlotNbt.read(tag.getList("UnitSlots", net.minecraft.nbt.Tag.TAG_COMPOUND),
                unitSlots, registries);
        }
        // 旧存档迁移：磁盘槽已移除（用户决定），老档里那块盘按下面的流程退回世界
        readLegacyDiskSlot(tag, registries);
        // 旧档无该字段：回退到方块朝向 property
        if (tag.contains("Facing")) {
            final int facing = tag.getInt("Facing");
            if (facing >= 0 && facing < Direction.values().length) {
                machineFace = Direction.from3DDataValue(facing);
            }
        } else if (getBlockState().hasProperty(SequenceExecutionChamberBlock.FACING)) {
            machineFace = getBlockState().getValue(SequenceExecutionChamberBlock.FACING);
        }
        // 面配置：旧档没有该字段时按「机器朝向面 = 原料输入面」补齐（行为与旧版一致）
        java.util.Arrays.fill(faceModes, FaceMode.NONE);
        faceModes[machineFace.get3DDataValue()] = FaceMode.INPUT;
        final int[] modes = tag.getIntArray("FaceModes");
        for (int i = 0; i < faceModes.length && i < modes.length; i++) {
            final FaceMode[] values = FaceMode.values();
            faceModes[i] = modes[i] >= 0 && modes[i] < values.length ? values[modes[i]] : FaceMode.NONE;
        }
        if (tag.contains("OutputItems")) {
            outputStorage.deserializeNBT(registries, tag.getCompound("OutputItems"));
        }
        if (tag.contains("OutputFluids")) {
            outputTank.readFromNBT(registries, tag.getCompound("OutputFluids"));
        }
        // 新语义：旧档无这些 tag → 保持缺省（"" = 未绑定）
        recipeType = tag.contains("ChamberRecipeType") ? tag.getString("ChamberRecipeType") : "";
        chamberName = tag.contains("ChamberName") ? tag.getString("ChamberName") : "";
        // 链方向：旧档的 ChainLink 字段被<b>刻意忽略</b>（不再读）—— 链由方块朝向推导，
        // 读档后「谁是链首」自动重建；链首若因此换了人，其配置由 sanitizeRayServer 从链上归位。
        // 输出模式：旧档没有该 tag → 面输出（行为与旧版完全一致）
        applyOutputMode(tag.contains("OutputMode") ? tag.getInt("OutputMode") : OutputMode.FACE.ordinal());
        // 输出总线类别归属：旧档没有 → 空（读到后会由 normalizeBusOwners 自愈）
        busCategoryOwners.clear();
        if (tag.contains("BusCategoryOwners")) {
            final CompoundTag ownersTag = tag.getCompound("BusCategoryOwners");
            for (final String id : ownersTag.getAllKeys()) {
                final long[] positions = ownersTag.getLongArray(id);
                final List<BlockPos> owners = new ArrayList<>(positions.length);
                for (final long packed : positions) {
                    owners.add(BlockPos.of(packed));
                }
                owners.sort(Comparator.comparingLong(BlockPos::asLong));
                busCategoryOwners.put(id, owners);
            }
        }
        // <b>读后按槽位重建设备状态（用户第 2 条）</b>：样板槽刚被逐格放回，所有「由样板推导出来」的缓存
        // 都必须作废，否则读档 / 放下方块物品 / 数据合并之后仍用旧那份单元列表 ⇒
        // 「配方 / 步序 / 中间产物类别」与新的槽位内容不再一一对应。
        // 注意：{@link #unitsCache} 是这些缓存的<b>源头</b>（类别表 / 负责的步 / 步骤专用投入物 / 起步原料
        // 都从它派生），因此它必须一起作废，不能只作废下游那几张表。
        unitsCache = null;
        busCategoriesCache = null; // 样板/配方可能已变：首次访问时重建
        ownedStepsCache = null;    // 同源：按步判定的数据源一并作废（含它衍生的「步骤专用投入物」表）
        stepExtraInputsCache = null;
        inputCouplingCache = null; // R1 的「候选包含关系」与上面两份表同源，一并作废
        startIngredientsCache = null; // 同源：「起步原料」表也跟着作废
        startIngredientRecipesCache = null; // 同源：「起步原料 → 配方」反查表（在制名额用）跟着作废
        pendingStepTick = Long.MIN_VALUE; // 供料目标上的过渡件状态可能已变：待加工步探针下次重新读
    }

    // ========== 旧存档迁移（执行舱磁盘槽已移除） ==========

    /**
     * 读档时把老档「DiskSlot」里的磁盘迁出来，等首个服务端 tick 退回世界。
     *
     * <h2>为什么必须迁移（严禁丢东西）</h2>
     * 用户决定「磁盘改插在共享的中间产物缓存仓上」，于是执行舱不再有磁盘槽。老存档里那一格若还插着盘，
     * 新版既读不出来、也没地方显示 —— 不迁移就等于把玩家的盘（以及盘里所有中间产物）吞掉。
     * 因此这里只在读档时<b>取出</b>那一块盘，交给 {@link #resolveLegacyDiskServer()} 落到世界。
     *
     * <h2>为什么不拆盘里的物品</h2>
     * RS 的 {@code StorageRepository} 按磁盘物品携带的 UUID 指认存储：<b>盘走到哪、内容就跟到哪</b>。
     * 把盘内的东西「拆出来另算一份」既会破坏这个指认，也会产生「同一批物品存在两处」的复制事故。
     * 因此这里只搬<b>盘</b>这一件物品，一个字节都不碰盘内内容。
     *
     * <h2>幂等（同一份旧数据只退还一次）</h2>
     * <ul>
     *     <li>迁移一次性标记 {@code LegacyDiskMigrated} <b>写进 NBT</b>：迁移后本仓再读档都不会重来
     *     （同一个方块实体被反复读档也不会重复退还）；</li>
     *     <li>新版<b>永远不再写出</b> {@code DiskSlot} 键，所以「迁移过一次」的存档里这个键自然消失，
     *     即使标记因异常丢失，也不存在第二次迁移的来源；</li>
     *     <li>万一在读档与下一次落盘之间崩溃：存档里仍是旧键，下次读档会再退一次 —— 这是 NBT 迁移
     *     无法再强的边界（新版无法在崩溃前改写旧数据），但此时 {@code LegacyDiskPending} 也已经落盘，
     *     两条路径都<b>不会丢盘</b>。</li>
     * </ul>
     */
    private void readLegacyDiskSlot(final CompoundTag tag, final HolderLookup.Provider registries) {
        // 标记取「或」：本实例一旦认过账就不再认第二遍（同一个方块实体被反复喂同一份旧数据也只退一次）
        legacyDiskMigrated = legacyDiskMigrated || tag.getBoolean(TAG_LEGACY_DISK_MIGRATED);
        if (tag.contains(TAG_LEGACY_DISK_PENDING)
            && (pendingLegacyDisk == null || pendingLegacyDisk.isEmpty())) {
            // 上一轮迁出来、还没退回世界就被存档带走了：继续按「未结算」处理（先来后到，不影响守恒）。
            // 内存里已经有待结算的盘时不覆盖：那一块同样是「还没落地」的旧盘，覆盖等于把它抹掉。
            final SimpleContainer pending = new SimpleContainer(1);
            pending.fromTag(tag.getList(TAG_LEGACY_DISK_PENDING, net.minecraft.nbt.Tag.TAG_COMPOUND),
                registries);
            final ItemStack carried = pending.getItem(0);
            if (!carried.isEmpty()) {
                pendingLegacyDisk = carried;
            }
        }
        if (legacyDiskMigrated || !tag.contains("DiskSlot")) {
            return; // 新版存档永远没有 DiskSlot 键；已经迁移过的也不再迁移
        }
        final SimpleContainer legacy = new SimpleContainer(1);
        legacy.fromTag(tag.getList("DiskSlot", net.minecraft.nbt.Tag.TAG_COMPOUND), registries);
        legacyDiskMigrated = true; // 先认账：无论槽里有没有盘，这份数据都已经处理过
        final ItemStack disk = legacy.getItem(0);
        if (disk.isEmpty()) {
            return;
        }
        pendingLegacyDisk = disk;
        legacyDiskMigratedChambers++;
        LOGGER.info("[rs_create_compat] 序列执行仓旧存档迁移：把执行舱磁盘槽里的磁盘退回世界"
            + "（本仓 {}，累计第 {} 台）；磁盘内物品随磁盘一起走（RS 存储仓库按磁盘 UUID 指认）",
            worldPosition, legacyDiskMigratedChambers);
    }

    /**
     * 服务端首个 tick：把迁移出来的旧磁盘落到本方块处（{@link #popIntoWorld}）。
     * <p>选「掉落到世界里」而不是「还给玩家」：读档时刻没有任何玩家上下文
     * （区块可能由别人 / 别的设备加载），无法可靠地把物品塞进某个人的背包；掉落到方块处是确定性的、
     * 且盘里的东西随盘走，玩家捡起来即可继续用。</p>
     * <p>本方法由方块 ticker 每 tick 调一次，落地后立刻置空并 {@code setChanged()}，
     * 于是「已经结算过」这件事立即落盘（不会每 tick 重复掉）。</p>
     */
    public void resolveLegacyDiskServer() {
        final ItemStack disk = pendingLegacyDisk;
        if (disk == null || disk.isEmpty()) {
            return;
        }
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return; // 只在服务端落地（客户端也会 tick 到本方法，但绝不能在客户端生成实体）
        }
        pendingLegacyDisk = null;
        popIntoWorld(disk);
        setChanged();
    }

    // ========== 机器集群（同类型机器相邻 = 一个整体：容量叠加 + 内容共享） ==========

    /** 集群载荷：整个集群<b>共用同一份</b>「物品存储 + 流体存储」对象。 */
    public record ClusterPayload(cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage items,
                                 cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage fluids) {
    }

    /** 本机载荷的稳定视图（身份稳定是管理器判定「是否已换成共享那一份」的前提）。 */
    private ClusterPayload clusterPayload;
    /** 本机是否承担集群内容的落盘职责（单机 = true；集群里只有主控为 true）。 */
    private boolean clusterOwnsPayload = true;
    /** 当前集群规模（台数，单机 = 1）。 */
    private int clusterSize = 1;

    @Override
    public BlockPos rscc$clusterPos() {
        return worldPosition;
    }

    @Override
    public Level rscc$clusterLevel() {
        return level;
    }

    @Override
    public Object rscc$clusterNewPayload(final int machineCount) {
        final int count = Math.max(1, machineCount);
        // 容量叠加：物品总量 = 台数 × 单机总量；流体总量 = 台数 × 单机容量
        return new ClusterPayload(
            new cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage(
                Config.autocrafterOutputSlots
                    * cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage.STACK_LIMIT * count),
            new cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage(
                Config.autocrafterFluidCapacity * count));
    }

    @Override
    public Object rscc$clusterPayload() {
        if (clusterPayload == null || clusterPayload.items() != outputStorage
            || clusterPayload.fluids() != outputTank) {
            clusterPayload = new ClusterPayload(outputStorage, outputTank);
        }
        return clusterPayload;
    }

    @Override
    public void rscc$clusterAdopt(final Object shared) {
        if (!(shared instanceof ClusterPayload payload) || payload.items() == outputStorage) {
            return;
        }
        outputStorage = payload.items();
        outputTank = payload.fluids();
        clusterPayload = payload;
        setChanged();
    }

    @Override
    public void rscc$clusterStandalone() {
        if (outputTank.getCapacity() == Config.autocrafterFluidCapacity) {
            return; // 已是单机规格：不重建对象，保持载荷身份稳定
        }
        // 调用方保证内容已按拆集群规则移交，这里只换成一份空的单机存储
        outputStorage = new cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage(
            Config.autocrafterOutputSlots
                * cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage.STACK_LIMIT);
        outputTank = new cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage(
            Config.autocrafterFluidCapacity);
        clusterPayload = null;
        setChanged();
    }

    @Override
    public long rscc$clusterMerge(final Object from, final Object into) {
        if (!(from instanceof ClusterPayload source) || !(into instanceof ClusterPayload target)
            || source == target) {
            return 0L;
        }
        long leftover = 0L;
        // 物品：逐槽搬（插入是「任意位置」语义，忽略槽位下标），搬运是「移动」不是「复制」
        for (int i = 0; i < source.items().getSlots(); i++) {
            final ItemStack stack = source.items().getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            final ItemStack rest = target.items().insertItem(0, stack, false);
            final int moved = stack.getCount() - rest.getCount();
            if (moved > 0) {
                source.items().extractItem(i, moved, false);
            }
            leftover += rest.getCount();
        }
        // 流体：逐罐搬（同流体自动并入同一罐）
        for (final FluidStack tank : source.fluids().getTanksSnapshot()) {
            final int moved = target.fluids().fill(tank, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (moved > 0) {
                source.fluids().drain(tank.copyWithAmount(moved),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            }
            leftover += (long) tank.getAmount() - moved;
        }
        return leftover;
    }

    @Override
    public void rscc$clusterSpill(final Object payload) {
        // rscc-audit-ok: 这是「兜底归宿」而不是「收集」——内容在这里被明确交付给世界
        // （Block.popResource / 装桶掉落），世界侧没有容量上限因此不需要容量核对；
        // 每一步都是「先取出、紧接着落回世界」，不存在先删后插的丢失窗口。
        final Level currentLevel = getLevel();
        if (!(payload instanceof ClusterPayload data) || currentLevel == null || currentLevel.isClientSide()) {
            return;
        }
        for (int i = 0; i < data.items().getSlots(); i++) {
            final ItemStack stack = data.items().getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            data.items().extractItem(i, stack.getCount(), false);
            Block.popResource(currentLevel, worldPosition, stack);
        }
        final List<FluidStack> fluids = data.fluids().getTanksSnapshot();
        data.fluids().setFluid(FluidStack.EMPTY);
        for (final ItemStack bucket : BlockContentReleaser.fluidsAsBuckets(fluids)) {
            Block.popResource(currentLevel, worldPosition, bucket);
        }
    }

    @Override
    public boolean rscc$clusterOwnsPayload() {
        return clusterOwnsPayload;
    }

    @Override
    public void rscc$clusterSetOwnsPayload(final boolean owns) {
        clusterOwnsPayload = owns;
    }

    @Override
    public void rscc$clusterChanged(final int memberCount, final BlockPos masterPos) {
        clusterSize = Math.max(1, memberCount);
        setChanged();
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)
                + " cluster=" + clusterSize
                + " master=" + (masterPos == null ? "-" : RsccAssemblyDebug.at(masterPos))
                + " output=" + outputMode.key());
        }
    }

    /** 当前参与的机器台数（1 = 单机）。 */
    public int getClusterSize() {
        return clusterSize;
    }

    /**
     * 只读诊断：本仓「负责的步」的稳定摘要（形如 {@code create:sturdy_sheet#1,create:sturdy_sheet#2}）。
     * <p>供「步骤 → 机器」绑定快照日志使用（见 {@code AssemblyWatchdog}）—— 一眼看出同一台机器到底认了
     * 哪几步，这正是「一台机器承担多个步骤时每个步骤都要能被独立表达」的可验证出口。只读，不搬运任何资源。</p>
     */
    public String debugOwnedSteps() {
        final Map<String, Set<Integer>> owned = ownedSteps();
        if (owned.isEmpty()) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder(64);
        for (final Map.Entry<String, Set<Integer>> entry : owned.entrySet()) {
            for (final int step : entry.getValue()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(entry.getKey()).append('#').append(step);
            }
        }
        return sb.toString();
    }

    // ==================== 诊断快照（<b>只读</b>；供 /rs_create_compat diag 导出） ====================

    /**
     * 最近一次总线备料计算出的「资源 → 目标量」快照（只读诊断用）。
     * <p>由备料引擎在算完之后原样存一份；<b>不参与任何判定</b>，因此对既有行为零影响。</p>
     */
    private Map<Item, Long> diagItemTargets = new LinkedHashMap<>();
    /** 同 {@link #diagItemTargets}，流体侧。 */
    private Map<Fluid, Long> diagFluidTargets = new LinkedHashMap<>();

    /**
     * <b>只读诊断</b>：本仓当前完整状态的结构化快照（八组字段里的「执行舱」一组）。
     *
     * <p>覆盖「位置 / 关联配方类型 / 面配置 / 在制与否 / 负责的步 / 单元样板 / 现存物品与流体 /
     * 各原料的五量（目标量 / 网络存量 / 本仓存量 / 机器侧存量 / 在途量）/ 可见类别（与服务端同源）」。

     * <p><b>只读</b>：只调用公开 / 同类的只读访问器，不抽取、不写入、不销毁任何资源，
     * 也不改变任何任务状态或策略档位，因此可以在产线运行中随时调用。</p>
     */
    public Map<String, Object> rscc$diagReport() {
        final Map<String, Object> out = new LinkedHashMap<>();
        // <b>2026-10-05：诊断时无条件把「本仓当前类别 id」打进日志</b>。
        //
        // 为什么必须放在这里，而不是只放在类别重建那一处：那处用 {@code changed(...)} 去重
        // ⇒ 类别内容没变时<b>一条都不打</b>。实测（用户要求「把类别 id 直接打进日志，你倒是加进去啊」
        // 之后）grep {@code cat_ids=} 结果是<b>空</b> —— 因为门控复查时类别没变，日志从未产生。
        // 放在这里：每次 {@code /rs_create_compat diag}（或快照）都必然会打一条，玩家发日志就能看到。
        if (RsccAssemblyDebug.isEnabled()) {
            final List<String> ids = new ArrayList<>();
            for (final BusCategoryInfo info : busCategories()) {
                ids.add(info.id() + "=" + info.items().size() + "i");
            }
            RsccAssemblyDebug.event("[rscc-assembly] chamber@"
                + RsccAssemblyDebug.at(worldPosition) + " diag_cat_ids=" + ids);
        }

        final Level level = getLevel();
        final com.refinedmods.refinedstorage.api.network.Network network =
            mainNetworkNode == null ? null : mainNetworkNode.getNetwork();
        final com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent storage =
            network == null ? null
                : network.getComponent(com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent.class);

        out.put("pos", worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ());
        out.put("name", getChamberDisplayName());
        out.put("recipeType", getRecipeType() == null ? "" : getRecipeType());
        out.put("outputMode", outputMode.key());
        out.put("clusterSize", clusterSize);
        out.put("inFlight", hasInFlightUnit());
        out.put("ownedSteps", debugOwnedSteps());
        out.put("energyUsage", getEnergyUsage());

        final Map<String, Object> faces = new LinkedHashMap<>();
        for (final Direction direction : Direction.values()) {
            faces.put(direction.getName(), getFaceMode(direction).name());
        }
        out.put("faces", faces);

        final List<Object> units = new ArrayList<>();
        for (int slot = 0; slot < unitSlots.getContainerSize(); slot++) {
            final ItemStack stack = unitSlots.getItem(slot);
            if (!stack.isEmpty()) {
                units.add(RsccAssemblyDebug.itemId(stack.getItem()));
            }
        }
        out.put("units", units);

        // ---- 本仓现存物品 / 流体（内部存储 + 磁盘缓存的统一视图） ----
        final Map<String, Long> itemCounts = new java.util.TreeMap<>();
        for (int slot = 0; slot < itemStorage.getSlots(); slot++) {
            final ItemStack stack = itemStorage.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                itemCounts.merge(RsccAssemblyDebug.itemId(stack.getItem()), (long) stack.getCount(), Long::sum);
            }
        }
        final List<Object> storedItems = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : itemCounts.entrySet()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", entry.getKey());
            row.put("count", entry.getValue());
            storedItems.add(row);
        }
        out.put("storedItems", storedItems);

        final Map<String, Long> fluidCounts = new java.util.TreeMap<>();
        for (final FluidStack stack : outputTank.getTanksSnapshot()) {
            if (!stack.isEmpty()) {
                fluidCounts.merge(RsccAssemblyDebug.fluidId(stack.getFluid()), (long) stack.getAmount(), Long::sum);
            }
        }
        final List<Object> storedFluids = new ArrayList<>();
        for (final Map.Entry<String, Long> entry : fluidCounts.entrySet()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("fluid", entry.getKey());
            row.put("mB", entry.getValue());
            storedFluids.add(row);
        }
        out.put("storedFluids", storedFluids);

        // ---- 各原料的五量（目标量 / 网络存量 / 本仓存量 / 机器侧存量 / 在途量） ----
        final List<Object> materials = new ArrayList<>();
        for (final Map.Entry<Item, Long> entry : diagItemTargets.entrySet()) {
            final Item item = entry.getKey();
            final long target = entry.getValue();
            final long networkAmount = storage == null ? 0L
                : storage.get(new ItemResource(item));
            final long chamberAmount = itemStorage.countOf(item);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("res", RsccAssemblyDebug.itemId(item));
            row.put("type", "item");
            row.put("target", target);
            row.put("net", networkAmount);
            row.put("chamber", chamberAmount);
            row.put("machine", machineHeldItem(item));
            row.put("inflight", Math.max(0L, target - networkAmount - chamberAmount));
            materials.add(row);
        }
        for (final Map.Entry<Fluid, Long> entry : diagFluidTargets.entrySet()) {
            final Fluid fluid = entry.getKey();
            final long target = entry.getValue();
            final long networkAmount = storage == null ? 0L
                : storage.get(new FluidResource(fluid));
            final long chamberAmount = storedFluidAmount(fluid);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("res", RsccAssemblyDebug.fluidId(fluid));
            row.put("type", "fluid");
            row.put("target", target);
            row.put("net", networkAmount);
            row.put("chamber", chamberAmount);
            row.put("machine", machineHeldFluid(fluid));
            row.put("inflight", Math.max(0L, target - networkAmount - chamberAmount));
            materials.add(row);
        }
        materials.sort((a, b) -> String.valueOf(((Map<?, ?>) a).get("res"))
            .compareTo(String.valueOf(((Map<?, ?>) b).get("res"))));
        out.put("materials", materials);

        // ---- 可见类别（与服务端同源；八组字段里的「配方四类分类」直接取自它） ----
        final List<Object> categories = new ArrayList<>();
        for (final BusCategoryInfo info : busCategories()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", info.id());
            row.put("kind", info.id() != null && info.id().startsWith(RsccBusCategory.FLUID_PREFIX) ? "inputFluid"
                : info.isInput() ? "ingredient"
                : info.isIntermediate() ? "intermediate"
                : info.isResult() ? "product"
                : info.isScrap() ? "scrap" : "unknown");
            row.put("step", info.step());
            final List<Object> items = new ArrayList<>();
            for (final Item item : info.items()) {
                if (item != null) {
                    items.add(RsccAssemblyDebug.itemId(item));
                }
            }
            row.put("items", items);
            final List<Object> fluids = new ArrayList<>();
            for (final Fluid fluid : info.fluids()) {
                if (fluid != null) {
                    fluids.add(RsccAssemblyDebug.fluidId(fluid));
                }
            }
            row.put("fluids", fluids);
            row.put("amount", info.amount());
            row.put("estimated", info.estimated());
            // 本轮新增（用户第 1 / 2 / 3 条）：中间产物的归属配方 + 「语义相同的步骤」复用键。
            // 只读诊断；配方空串 = 步序 / 配方未知（旧写法类别）。
            row.put("recipe", info.recipe());
            row.put("reuseKey", info.reuseKey() == null ? "" : info.reuseKey());
            categories.add(row);
        }
        out.put("categories", categories);

        // ---- 链级视图（用户需求：4 台成链 = 一台，并且要能「读取里面所有的单元样板」） ----
        // 这一段<b>只读、只加字段</b>（既有键一个不动）：本仓的 units / categories 仍是「本台方块」的，
        // 这里额外给出「这条逻辑执行仓」的成员、全部单元样板与类别并集，口径与界面 / 日志同源
        // （chainMembers / chainBusCategories，绝不新造第二套链级判定）。
        final List<SequenceExecutionChamberBlockEntity> chainMemberList = chainMembers();
        final SequenceExecutionChamberBlockEntity chainHeadEntity = chainHead();
        final Map<String, Object> chainInfo = new LinkedHashMap<>();
        chainInfo.put("size", chainMemberList.size());
        chainInfo.put("head", RsccAssemblyDebug.at(chainHeadEntity.worldPosition));
        chainInfo.put("headName", chainHeadEntity.getChamberDisplayName());
        final List<Object> chainMemberPositions = new ArrayList<>(chainMemberList.size());
        for (final SequenceExecutionChamberBlockEntity member : chainMemberList) {
            chainMemberPositions.add(RsccAssemblyDebug.at(member.worldPosition));
        }
        chainInfo.put("members", chainMemberPositions);
        // 链上**全部成员**的单元样板（按成员坐标升序、成员内按槽位升序）：这就是「里面所有的单元样板」
        // 的字面清单。读取一律用已存在的方块实体（成员由 chainMembers 给出，未加载的成员根本不在链里），
        // 不加载区块、不创建方块实体、不搬运任何资源。
        final List<Object> chainUnitRows = new ArrayList<>();
        for (final SequenceExecutionChamberBlockEntity member : chainMemberList) {
            for (int memberSlot = 0; memberSlot < member.unitSlots.getContainerSize(); memberSlot++) {
                final ItemStack memberStack = member.unitSlots.getItem(memberSlot);
                if (memberStack.isEmpty()) {
                    continue;
                }
                final Map<String, Object> unitRow = new LinkedHashMap<>();
                unitRow.put("chamber", RsccAssemblyDebug.at(member.worldPosition));
                unitRow.put("slot", memberSlot);
                unitRow.put("item", RsccAssemblyDebug.itemId(memberStack.getItem()));
                final UnitData memberUnit = level == null
                    ? null : SequencePatternData.readUnit(memberStack, level.registryAccess());
                unitRow.put("recipe", memberUnit == null || memberUnit.recipe() == null
                    ? "" : memberUnit.recipe());
                unitRow.put("step", memberUnit == null ? -1 : memberUnit.step());
                unitRow.put("recipeType", memberUnit == null || memberUnit.recipeType() == null
                    ? "" : memberUnit.recipeType());
                chainUnitRows.add(unitRow);
            }
        }
        chainInfo.put("units", chainUnitRows);
        final List<Object> chainCategoryIds = new ArrayList<>();
        for (final BusCategoryInfo info : chainBusCategories()) {
            chainCategoryIds.add(info.id());
        }
        chainInfo.put("categoryIds", chainCategoryIds);
        out.put("chain", chainInfo);

        // ---- 守恒账本（用户第 1 条：物品与流体分别记账；未对平条目直接列出） ----
        out.put("flowLedger", flowLedgerReport());
        return out;
    }

    /**
     * <b>只读</b>：本仓相连的供料目标（机器 / 置物台）上此刻压着多少份该物品
     * （口径与 {@link #supplyTargetHoldsItem} 同源：同一工位只算一次）。
     *
     * <p><b>为什么要公开它（用户第 1 条：刚好够却报缺）</b>：缺料上报
     * （{@link #reportBusShortages}）必须把「机器上正压着的那一份」算进可用量，否则起步原料一被
     * 推进机器就会在每个瞬间被误报一次；这与流体侧的 {@code machineHeldFluid} 严格对称。
     * 另供诊断导出（{@code /rs_create_compat diag}）读取。只读，绝不搬运 / 销毁任何资源。</p>
     */
    public long machineHeldItem(final Item item) {
        final Level level = getLevel();
        if (level == null || level.isClientSide() || item == null) {
            return 0L;
        }
        long held = 0L;
        for (final BlockPos target : busSupplyTargets()) {
            if (supplyTargetHoldsItem(level, target, item)) {
                held += 1L;
            }
        }
        return held;
    }

    /**
     * 本机被移除（方块破坏 / 区块卸载）时的拆集群钩子：
     * 内容按 {@link cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster} 的规则
     * 「跟着当时持有它的一方走」，绝不复制、绝不销毁。
     */
    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide()) {
            cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster.onMemberRemoved(level, this);
        }
        super.setRemoved();
    }

    /**
     * 注册方块能力：
     * <ol>
     *     <li>RS 网络节点容器（无 GUI 侧限制）；</li>
     *     <li>物品 / 流体能力 —— <b>只在被设为「产物输出面 / 中间产物输出面」的那一面开放，
     *     且一律只允许抽取（{@link ExtractOnlyItemHandler}）</b>：与用户要求「执行仓只允许输出、
     *     不允许物流往里输入」完全一致。</li>
     * </ol>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.logisticsItemHandler(direction)
        );
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.logisticsFluidHandler(direction)
        );
    }

    /** 该方向对外暴露的物品能力（非输出面 / null 方向一律不暴露）。 */
    @org.jetbrains.annotations.Nullable
    public net.neoforged.neoforge.items.IItemHandler logisticsItemHandler(
        @org.jetbrains.annotations.Nullable final Direction direction) {
        if (direction == null) {
            return null;
        }
        final FaceMode mode = getFaceMode(direction);
        if (mode != FaceMode.OUTPUT && mode != FaceMode.INTERMEDIATE) {
            return null;
        }
        // 对外物流能力同样走「统一视图」：磁盘缓存里的中间产物也是本仓的内容物，
        // 漏斗 / 管道从输出面能抽到它（与内部存储里的东西一视同仁，不会出现「磁盘里的抽不出来」）。
        return new cretae.cookiewyq.rs_create_compat.support.ExtractOnlyHandlers.Item(itemStorage);
    }

    /** 该方向对外暴露的流体能力（非输出面 / null 方向一律不暴露）。 */
    @org.jetbrains.annotations.Nullable
    public net.neoforged.neoforge.fluids.capability.IFluidHandler logisticsFluidHandler(
        @org.jetbrains.annotations.Nullable final Direction direction) {
        if (direction == null) {
            return null;
        }
        final FaceMode mode = getFaceMode(direction);
        if (mode != FaceMode.OUTPUT && mode != FaceMode.INTERMEDIATE) {
            return null;
        }
        return new cretae.cookiewyq.rs_create_compat.support.ExtractOnlyHandlers.Fluid(outputTank);
    }
}
