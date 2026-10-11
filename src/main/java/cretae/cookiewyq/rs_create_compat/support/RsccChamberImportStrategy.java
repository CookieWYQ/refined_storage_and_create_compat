package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.importer.ImporterTransferStrategy;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.resource.filter.Filter;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllDataComponents;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 「延长型输入（序列执行仓回流）」模式下输入总线的传输策略 —— <b>取货源是执行舱自身 + 它正在供料的机器</b>。
 *
 * <p><b>流向语义</b>（{@link RsccChamberExportStrategy} 的对称版）：</p>
 * <pre>
 *   执行舱内部存储 ─┐
 *                  ├─(本策略)─▶ RS 网络
 *   机器 / 置物台  ─┘
 * </pre>
 * <p>与原版输入总线的差别是「取货源」：不再是本总线朝向那一面的相邻容器，而是</p>
 * <ol>
 *     <li>执行舱物品存储（{@code RsccChamberItemStorage} 统一视图 = 内部存储 + 磁盘缓存）与
 *     流体存储 {@code outputTank}；</li>
 *     <li><b>本仓正在供料的机器输出侧</b>（{@link SequenceExecutionChamberBlockEntity#busSupplyTargets()}：
 *     相连输出总线所朝向的那些机器 / 置物台）。为什么必须加上这一条（用户实测断点）：
 *     机器加工完的产出会压在机器 / 置物台上，而输出总线只会往里推、不会把它收走；
 *     于是目的地永远 {@code machine_full}、链条第一步之后就断。玩家只要把输入总线
 *     <b>贴着机器或置物台</b>放（配合连到执行舱的线缆）就能用，不必理解「内部存储」这回事。
 *     <p><b>第 24 轮（用户需求：链 / 分支）</b>：一条输入总线只要接在链上的<b>任意一台</b>执行仓上，
 *     就属于整条链 —— 因此这里的工位清单是<b>整条链</b>的工位（{@code busSupplyTargets()} 已是链口径），
 *     而每台机器用<b>它自己的属主仓</b>的判据来判（见 {@code chainStationOwners} / {@code stationOwner}）：
 *     用错仓的步序与在制件登记表会误收正在加工的件。没串链时链 = 本仓自己，行为逐字不变。</p></li>
 * </ol>
 * <p>因此必须替换掉 RS 自己构造的 transfer strategy（它写死了从相邻容器抽）。</p>
 *
 * <p><b>收回什么（本轮改为默认全自动）</b>：</p>
 * <ul>
 *     <li><b>全自动（默认）</b>：{@link RsccImporterExecutorMode#rscc$isAutoCollect()} 为真时不需要
 *     任何勾选 —— 自动收回<b>非输入类</b>的东西：成品、废料，以及<b>执行舱已经不再认领</b>的
 *     过渡件（按「步骤」口径：物品当前进度步 {@code s} 与执行舱样板被指派的步 {@code m} 比，
 *     {@code s + 1 == m} 表示这台机器下一步就要加工它 → 不收回；{@code s >= m} 或与本仓机器无关 → 收回；
 *     <p><b>判不出来一律不收回</b>）。「输入类」类别（配方原料 / 流体输入）<b>平时一律不收回</b>，
 *     避免把刚喂进机器的料又抽回来；<b>例外只有一处</b>：本条任务<b>刚结束</b>的那一个边沿会
 *     领取一次性令牌（{@link SequenceExecutionChamberBlockEntity#claimResidualInputReclaim()}），
 *     那一次允许把输入类残留也收一遍（用户验收标准 #1：500 mB 残留只在「刚结束那一刻」收一次，
 *     令牌用完即废 → 之后手工放上去的输入原料不会被持续收走）。
 *     <b>执行舱还会继续加工的过渡件也不收回</b> —— 它要继续在产线上按步流转（用户实测最严重的问题：
 *     只完成到第 1 步的中间产物被立刻抽回网络，没等到变成「步骤 2 完成」）。判据见
 *     {@link #autoAcceptsItem} / {@link #autoAcceptsFluid}，其中过渡件那一条与执行舱推料侧同一口径
 *     （同一个 {@link SequenceExecutionChamberBlockEntity#isTransitionReclaimAllowed}，
 *     而它与 {@link SequenceExecutionChamberBlockEntity#isNextForMyMachines} 共用
 *     {@code SequenceMaterialGuard#judgeStep}，两侧结论严格互补）；</li>
 *     <li><b>手动（可选）</b>：玩家在界面上关掉「自动」后，退回既有行为 ——
 *     只收回界面里勾选的类别，未勾选（含从没勾过）的类别一点都不取。</li>
 * </ul>
 *
 * <p><b>不丢不复制（硬约束，两个来源同一套语义）</b>：</p>
 * <ul>
 *     <li>先对网络做 {@link Action#SIMULATE} 插入，<b>只按网络能收下的量</b>决定抽多少；
 *     网络收不下就这一格 / 这一罐一点都不动（留在原处，绝不销毁、绝不复制）；</li>
 *     <li>正式搬运时<b>原子抽取</b>，再插进网络；万一插入结果小于抽出量
 *     （理论不可达：已按 SIMULATE 夹过），余量<b>原样放回原处</b>（执行舱内部存储 / 机器）；</li>
 *     <li>物料在任一时刻只存在于<b>一处</b>：要么在执行舱内部存储里、要么在机器里、要么在网络里。
 *     本策略<b>只往网络写</b>，从不反向插进除「原处」以外的任何地方；</li>
 *     <li>抽取是原子的，因此多台输入总线同时收回也只是各自取走不同的那一份，<b>不可能重复取走同一份</b>。</li>
 * </ul>
 *
 * <p><b>原料标记</b>：执行舱会给「第一步输出给机器的原料」打上 {@code rs_create_compat:raw_material}
 * 数据组件（仅用于区分「没被机器吃掉的原料」与「未完成中间产物」）。收回进网络前必须<b>去掉标记</b>，
 * 否则网络里会出现「带标记的原料变体」，正常合成 / 自动合成就匹配不上了 ——
 * 口径与执行舱自己的「中间产物输出面」回写完全一致（见 {@code SequenceMaterialGuard}）。
 * 过渡件（未完成中间产物）则连它的 {@code create:sequenced_assembly} 组件一起进网络，
 * 因为下一个执行舱要靠它认领。</p>
 *
 * <p><b>「任务刚结束」那一个边沿的例外（本轮修复）</b>：该边沿的一次性令牌
 * （{@link SequenceExecutionChamberBlockEntity#claimResidualInputReclaim()}）除了放行输入类，
 * 也放行<b>未完成件</b> —— 用户实测「我先取消任务之后，这些中间产物不会回流到网络之中」：
 * 任务都没了，「下一步仍由同一台机器负责」这件事不再成立（没有任何在跑的自动合成会把它接着做下去），
 * 此时按住它只会让它在仓里 / 机器上永久冻结、终端里一份都看不到。令牌只发一次、只在
 * 「与本仓产线相关的任务从『在跑』翻转为『不在跑』」那一个边沿发出，并在下一次自动搬运里被消费掉，
 * 因此<b>任务运行期间</b>「下一步仍由同一台机器（或同 cluster）负责 ⇒ 中间产物绝不被收回」一字未改。</p>
 *
 * <p><b>脱绑</b>：执行舱被拆掉 / 切回面输出 / 超出线缆范围后，本策略把 transfer 完全委托给 RS 原版
 * 策略（{@code delegate}，与 RS 工厂同一套参数：物品 / 流体 capability 源 + 堆叠 / 调节升级配额），
 * 因此输入总线不会变成哑巴，也不会残留对执行舱的引用 —— 未连线时行为与 RS 原生输入总线逐字节一致。</p>
 *
 * <p><b>诊断</b>：本策略只<b>加</b>结构化日志（前缀 {@code [rscc-assembly]}，见
 * {@link RsccAssemblyDebug}）—— <b>只有真正搬动了东西（数量 &gt; 0）才打</b>（用户要求：{@code x0}
 * 的搬运日志一律不打），且只在状态翻转时各打一条（稳态零输出）；「没绑定 / 手动模式类别没选 /
 * 原处是空的 / 网络拒收」各有独立原因标签；不改变任何搬运判定。</p>
 */
public final class RsccChamberImportStrategy implements ImporterTransferStrategy {
    /** 单次从某一格 / 某一罐抽取的上限（与执行舱 {@code COLLECT_BATCH} 同量级）。 */
    private static final int COLLECT_BATCH = 16;
    /** 单次从机器某一罐流体抽取的上限（mB）。 */
    private static final int FLUID_COLLECT_BATCH = 1000;

    /** 本策略所属的输入总线（用它解析当前绑定的执行舱；服务端权威、逐次解析、不缓存引用）。 */
    private final RsccImporterExecutorMode owner;
    /**
     * 脱绑时的回退策略（RS 原版「相邻容器 → 网络」行为）。
     * <p>刻意由构造方按 RS 自己的工厂链构建，而不是去反射读节点里那个 strategy：既不需要新增 accessor，
     * 也保证回退行为与 RS 完全同源。</p>
     */
    private final ImporterTransferStrategy delegate;

    // ---------- 诊断（只读；不参与任何搬运判定） ----------
    /** 本总线自身坐标（日志用）。 */
    private final BlockPos selfPos;
    /** 日志前缀 {@code importer@(x,y,z)}。 */
    private final String debugTag;

    public RsccChamberImportStrategy(final RsccImporterExecutorMode owner,
                                     final BlockPos selfPos,
                                     final ImporterTransferStrategy delegate) {
        this.owner = owner;
        this.selfPos = selfPos;
        this.delegate = delegate;
        this.debugTag = RsccAssemblyDebug.machine("importer", selfPos);
        // 安装 = 本总线刚连上一台「总线输出」模式的执行舱：只在状态翻转时打一条
        if (RsccAssemblyDebug.isEnabled()) {
            final BlockPos linked = owner.rscc$linkedExecutorPos();
            RsccAssemblyDebug.transition("importerlink@" + RsccAssemblyDebug.at(selfPos),
                linked == null ? "unlinked" : RsccAssemblyDebug.at(linked),
                debugTag + " linked=" + (linked == null ? "-" : RsccAssemblyDebug.machine("chamber", linked))
                    + " auto=" + owner.rscc$isAutoCollect()
                    + " cats=" + owner.rscc$getImportCategoryIds()
                    + " reason=strategy_installed");
        }
    }

    @Override
    public boolean transfer(final Filter filter, final Actor actor, final Network network) {
        final SequenceExecutionChamberBlockEntity chamber = owner.rscc$getLinkedExecutor();
        if (chamber == null) {
            // 未绑定（拆掉 / 切模式 / 超出线缆范围）：退回原版「相邻容器 → 网络」，不留哑巴总线、不留幽灵引用
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("take@" + RsccAssemblyDebug.at(selfPos) + "#unlinked",
                    "delegated",
                    debugTag + " took=- inserted=0 rejected=0 result=DELEGATED reason=chamber_unlinked");
            }
            return delegate.transfer(filter, actor, network);
        }
        final RootStorage storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return false;
        }
        // <b>挂起冻结（用户硬要求「挂起当刻即全面停止，含备料 / 推料 / 搬运」）</b>：本仓相关的自动合成
        // 任务全部被挂起时，本仓停工 —— 输入总线这一侧（「机器 / 本仓 → 网络」与「跨阶段直接交接」）
        // 必须一起停，否则机器上的过渡件仍会被抄走 / 输入类仍会被收回网络，玩家看到的就是
        // 「挂起之后它好像还是在处理」。这里整条短路（不搬任何东西、不做任何判定）。
        // 只读判定 + 一次状态翻转日志（稳态零输出），绝不改任何搬运语义。
        if (chamber.isBusFrozen()) {
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("take@" + RsccAssemblyDebug.at(selfPos) + "#frozen",
                    "frozen",
                    debugTag + " linked=" + RsccAssemblyDebug.machine("chamber", chamber.getBlockPos())
                        + " took=- inserted=0 rejected=0 result=IDLE reason=task_suspended_frozen");
            }
            return false;
        }
        // <b>「没有任何玩家为本产线下单」时同样整条短路（本轮新增，用户第 ③ 条）</b>：
        // 出口侧早就由 {@link SequenceExecutionChamberBlockEntity#isAutoCraftingEnabled()} 拦住了，
        // 但<b>收回侧</b>原先没有任何「有单」判据 —— 于是即使没有人下单，本仓的输入总线仍会把机器 /
        // 置物台上的成品、废料、以及「下一步归别人的过渡件」往外搬（跨阶段直接交接也照做）。
        // 那既是「零取料 / 零投料」的反例，也让「无单却像在产」的现象在搬运层复现。
        // 唯一的例外是「本条任务刚结束」的一次性残留回流令牌：它只在那一个边沿有效（≤40 tick、
        // 领取即作废），收尾必须照常走，否则用户硬要求「取消立刻回收残留」会被这条闸门误杀。
        // <b>用户第 ② 条（本轮修正）：自动模式下的废料 / 成品必须「统一收回」，不受「有单」闸门限制。</b>
        // 旧实现无论手动 / 自动都在这里整条短路 —— 后果正是用户原话「你这显示『收回齿轮』啊，
        // 你为什么又不收回那个齿轮？」：一条订单结束（或本仓没有以自己产物为目标的活跃任务）时，
        // 压在与本仓相连机器 / 置物台上的齿轮（既是某步投入物、又是同一条配方的废料）永远收不回网络。
        // 现在只在两处收紧，其余照旧：
        //   * <b>手动模式</b>（玩家没勾类别 / 没单）：保持既有行为，整条短路（玩家显式选择优先）；
        //   * <b>自动模式</b>：仍然允许「回收方向」（机器 / 本仓 → 网络）—— 因为那是「把东西还回网络」，
        //     不是「取料 / 投料 / 起新件」；但把「<b>跨阶段直接交接</b>」（会把一份过渡件直接喂给
        //     另一台执行仓 = 推进生产）整条关掉（见下面的 {@code noOrder} → {@code transitionSink}）。
        // 于是「无玩家下单 ⇒ 零取料 / 零投料 / 零自动合成」这条硬约束一字未改（出口侧仍由
        // {@link SequenceExecutionChamberBlockEntity#isAutoCraftingEnabled()} 拦着），
        // 而「废料统一收回」这条恢复成立。
        final boolean auto = owner.rscc$isAutoCollect();
        boolean noOrderFlag = false;
        if (!chamber.isAutoCraftingEnabled() && !chamber.hasResidualInputReclaim()) {
            if (!auto) {
                if (RsccAssemblyDebug.isEnabled()) {
                    RsccAssemblyDebug.transition("take@" + RsccAssemblyDebug.at(selfPos) + "#nogoal",
                        "idle_no_order",
                        debugTag + " linked=" + RsccAssemblyDebug.machine("chamber", chamber.getBlockPos())
                            + " took=- inserted=0 rejected=0 result=IDLE reason=no_player_order");
                }
                return false;
            }
            noOrderFlag = true; // 自动模式：只收回「成品 / 废料」，不做跨阶段直接交接、不收回输入类
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("take@" + RsccAssemblyDebug.at(selfPos) + "#nogoal_scrap",
                    "idle_no_order_scrap_only",
                    debugTag + " linked=" + RsccAssemblyDebug.machine("chamber", chamber.getBlockPos())
                        + " auto=true took=- result=RECLAIM_ONLY reason=no_player_order_scrap_reclaim");
            }
        }
        // 手动模式的勾选集合（全自动时完全不参与判定）
        final Set<String> selected = auto ? Set.of() : new LinkedHashSet<>(owner.rscc$getImportCategoryIds());
        if (!auto && selected.isEmpty()) {
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("take@" + RsccAssemblyDebug.at(selfPos) + "#nocats",
                    "idle_no_categories",
                    debugTag + " linked=" + RsccAssemblyDebug.machine("chamber", chamber.getBlockPos())
                        + " auto=false cats=[]"
                        + " took=- inserted=0 rejected=0 result=IDLE reason=no_categories_selected");
            }
            return false;
        }
        final Level level = chamber.getLevel();
        if (level == null) {
            return false;
        }
        // <b>「工位 → 属主仓」表（本轮：链 / 分支）</b>：一台输入总线只要接在链上的任意一台执行仓上，
        // 就属于整条链（用户需求），因此它要扫的工位是<b>整条链</b>的工位（{@code chamber.busSupplyTargets()}
        // 已经由链口径给出，见 SequenceExecutionChamberBlockEntity#connectedExporterPositions）。
        // 但「这一步要不要这件料 / 这份件能不能收回」的判据全部<b>按仓</b>成立（在制件登记表、单元样板、
        // 步序判据都在各自的仓上），所以机器侧一律用<b>该工位的属主仓</b>来判 —— 否则用绑定仓的步序去判
        // 别的仓的工位，会误收正在加工的件（链上每台仓自己的判据才是唯一事实源）。
        // 一个工位只有一个属主（表内 putIfAbsent，按坐标升序），因此绝不存在「同一台机器被两套链口径
        // 各判一遍」的重复计数；这条总线本身也只被算作一条（份额 / 轮询仍在绑定仓那一份归属表里）。
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> stationOwners = chamber.chainStationOwners();
        final StringBuilder detail = RsccAssemblyDebug.isEnabled() ? new StringBuilder(96) : null;
        final long[] tally = new long[3]; // 0 = moved, 1 = rejected, 2 = 剥离原料标记的件数
        // 本次是否处在「任务刚结束」的一次性边沿收尾（只影响日志里的 reason，见下）
        boolean edgeReclaim = false;

        if (auto) {
            // ============ 全自动：非输入类恒收回；「刚结束」那一轮才额外收回输入类（一次） ============
            // 仓内存储（含集群共享的那一份）用<b>整条链的输入类并集</b>当保护集合：链上的仓彼此相邻
            // ⇒ 本来就是同一个存储集群、同一份存储对象，而「链上另一台仓的输入料」正是被这条链总线
            // 选中后才备进来的（见 SequenceExecutionChamberBlockEntity#busCategoryOwners 的链口径）。
            // 若仍只认绑定仓自己的输入类，它会被当成「非输入类 ⇒ 收回」抄回网络 → 与备料形成
            // 每秒来回搬运。机器侧的判据仍按<b>工位属主</b>逐台取（见下），两者互不影响。
            final Set<Item> inputItems = chamber.chainInputCategoryItems();
            final Set<Fluid> inputFluids = chamber.chainInputCategoryFluids();
            // 「仓 → 该仓输入类集合」的按次缓存：机器侧逐格 / 逐罐都要问，集合不能反复重建
            final Map<SequenceExecutionChamberBlockEntity, Set<Item>> ownerInputItems = new HashMap<>();
            final Map<SequenceExecutionChamberBlockEntity, Set<Fluid>> ownerInputFluids = new HashMap<>();
            // 用户验收标准 #1（一次性边沿）：输入类的保护平时成立，只在「本条任务刚结束」那一个边沿
            // 放开<b>一次</b> —— 领取令牌会立刻消费它（见 claimResidualInputReclaim 的说明），
            // 因此之后每一次自动收回都回到「输入类受保护」：
            //   * 任务刚结束那一轮 → 压在机器 / 置物台上与本仓里的残留（例如已推给注液机、
            //     还没被消耗的那 500 mB 岩浆）被收一次，用户看到的仍是「停任务后岩浆一点不剩」；
            //   * 之后玩家手工放上去的输入原料 → 不再被自动收走（本轮修掉的回归）。
            final boolean residualEdge = chamber.claimResidualInputReclaim();
            // <b>跨阶段中间产物的「直接交接」</b>（本轮新增，见 {@link NetworkTransitionSink}）：
            // 一份过渡件的下一步若由<b>本网络内另一台</b>执行仓负责，就没有任何理由让它先去 RS 网络「绕一圈」
            // —— 用户实测（latest.log 10:14:48）它进网络的那一刻 {@code net_after=1}，而真正该接手它的
            // 冲压仓在两秒后仍然读到 {@code net=0 reason=no_stock}，于是坚固板的第二次冲压永远拿不到料。
            // 直接放进接手仓的内部存储后，那条仓的输出总线立刻能把它推给冲压机，
            // 全程不经过网络（既不丢也不复制：先 SIMULATE、收得下才从机器抽取，收不下的余量原样还回机器）。
            // <b>无单（{@code noOrder}）时不做「跨阶段直接交接」</b>：那一步会把一份过渡件直接喂给
            // 「负责它下一步」的另一台执行仓，属于<b>推进生产</b>，与「无玩家下单 ⇒ 零取料 / 零投料」
            // 冲突。此时 {@code transitionSink} 为 {@code null}，跨阶段件照旧走「插入网络」这一条
            // （回收方向），绝不会被本总线直接喂进另一台仓。
            final TransitionSink transitionSink = noOrderFlag ? null : new NetworkTransitionSink(
                () -> SequenceExecutionChamberBlockEntity.chambersOf(network),
                // 本仓（分支）的供料目标：这些工位上的东西正在本链加工，本仓的导入侧一律不碰
                // （链口径 —— 同链其它仓供料的工位同样属于本分支，防止「本分支的导入侧抢走本分支正在加工的件」
                // 那种「抢走 → 交接 → 再抢走」的循环；见 NetworkTransitionSink#simulate 的说明）
                chamber == null ? java.util.Set.<BlockPos>of()
                    : new java.util.HashSet<>(chamber.busSupplyTargets()));

            // <b>仓内存储</b>与<b>机器 / 置物台</b>用两套判据（本轮修正，用户实测「一直输出 / 不会转移」的根因）：
            // 机器侧那份「本步不要它就收回」是**对的**（它压在机械手手里会把产线卡死，是原修法的目标）；
            // 但仓内存储里那份只是「还没轮到喂」的备料 —— 用同一判据抄回网络，就等于把备料侧刚买回来的料
            // 立刻退回（实测日志：chamber pull{x5} → 同一秒 importer took{x5}，每秒来回、机器一个件都推不出去）。
            // 因此仓内输入类平时一律受保护，只有「任务刚结束」那一次边沿才收（residualEdge）；
            // 本轮又补上第二条例外「在途自动合成任务正等着它」，见下。
            // ==================== 在途自动合成任务的「预留」释放（本轮新增） ====================
            //
            // <b>用户报告</b>：「针对精密构件，他们在制作的时候，有可能被你提前消耗……我看着有几个齿轮和大齿轮的
            // 合成任务位置卡在那里不动」，且与精密构件制作者相关。
            //
            // <b>机制（RS 源码交叉验证，全文见 {@link SequenceMaterialGuard#isInFlight}）</b>：
            // RS 2.0 <b>没有任何「网络存量已被预留」的表示</b> —— {@code RootStorageListener} 只有
            // {@code beforeInsert} / {@code afterInsert}（{@code RootStorageListener.java:18,30}），
            // {@code RootStorageImpl#extract}（{@code RootStorageImpl.java:85-87}）是裸委托。
            // 一条任务「还没拿到」的量只存在于它自己的 {@code initialRequirements} 账上
            // （{@code TaskImpl.java:199-222} 逐步抽取、{@code :214} 抽到就减），并被 RS 报成
            // {@code TaskStatus.Item#extracting}（{@code autocrafting/status} 包里那个状态构造器的
            // {@code extracting(...)}，2.0.0 源码 {@code status} 包 :30-33）。
            // 因此只要本模组把网络里那几件抽进自己怀里，那条任务就<b>永远等不到</b> ——
            // 它每一 tick 都只再试一次 {@code extract}，没有任何超时、没有第二次机会，
            // 于是在监视器上<b>永久停在原地</b>（正是用户看到的现象）。
            //
            // <b>为什么这里（仓内 → 网络）是修得动的那一侧</b>：本侧只会把东西<b>还回网络</b>，
            // 从不扣减别人，因此天然不可能制造新的饿死；而「仓内压着一件没有工位要它、
            // 却正是别人在等的东西」是本模组唯一能主动解开那种卡死的动作。
            // 判据与既有「废料例外」严格同源（见 {@link #autoAcceptsChamberItem}）：
            // <b>没有任何工位此刻要它</b>（{@code inputMaterialWantedNow(item)} 为假）才放行，
            // 因此绝不会把机器正等着的那一件抄走；而且备料侧的闸门是
            // {@code stepExtraStockTarget(item) <= 0 ⇒ 本 tick 一条判断都不做}
            // （见 {@code SequenceExecutionChamberBlockEntity#fillInternalForBus}），
            // 即「没有工位要它 ⇒ 备料侧不会再买回来」—— 于是这条释放<b>不会</b>变成
            // 「买进来 → 退回去」的每秒往返。
            //
            // <b>只读</b>：这里只算一张表（谁在等什么），真正的搬运仍走原来那条插入路径。
            final Map<ResourceKey, Long> inflightClaims = inflightClaimsOf(network);
            // 无单时（{@code noOrderFlag}）不释放：那会把「本仓没单」时的输入类也放回网络，
            // 越过了「无玩家下单 ⇒ 零取料 / 零投料」这条硬约束（收回方向的既有例外只覆盖成品 / 废料）。
            final boolean releaseInflight = !noOrderFlag && !inflightClaims.isEmpty();
            // 「这一件是不是别人正在等的」：与入网资源口径一致 —— 原料标记会在入网前被剥掉，
            // 因此带标记与不带标记两种形态都查一次（查不到即「没人等它」，与既有行为逐字一致）。
            final Predicate<ItemStack> claimedByInflight = stack -> {
                if (stack.isEmpty()) {
                    return false;
                }
                return inflightClaims.containsKey(ItemResource.ofItemStack(stack))
                    || inflightClaims.containsKey(new ItemResource(stack.getItem(), DataComponentPatch.EMPTY));
            };
            final Predicate<ItemStack> acceptChamberItem = stack ->
                autoAcceptsChamberItem(stack, inputItems, residualEdge, chamber)
                    || (releaseInflight
                        && releasesChamberItemForInflight(stack, inputItems, chamber, claimedByInflight));
            // <b>机器侧必须带上「哪一台机器」</b>（本轮修正）：收回侧的「本步不要它就收回」与推料侧的
            // 「本步要的才推」必须是同一个判据的两面，而推料侧是按<b>本机</b>判的（机械手要看它朝向 2 格外的
            // 那一格才知道自己在第几步）。若这里仍用「全仓并集」的旧口径，会出现新的死锁：
            // 机械手手里压着一件「本步不要的」投入物 → 推料侧（正确）不推、收回侧（旧口径）却认为「要它」
            // 也不收 → 手里那件永远拿不掉，整条线卡死（用户实测「直接就是卡住了」正是这个状态）。
            // 带上坐标后两侧严格互补：不要它的那一份必然被这台机器的输入总线抄走。
            // <b>无单时的机器侧铁律</b>（2026-10-05 用户实测，原话：「如果当前没有任何任务在使用某一台
            // 序列执行机，那么它对应的这些输入输出总线不应该影响它的机器的任何地方……我此时放上一个铁锭，
            // 我想直接用它冲压成铁板，那么它应该像一台正常的机器一样直接被冲压成铁板、然后安安静静待在那里
            // 等着我拿走，而不是像现在这样子放上去、它居然没有执行任何任务也会被立刻收走」）。
            //
            // 旧行为：机器侧用「本步不要它就收回」这条判据 —— 而「本步是哪一步」在没有活跃任务时
            // 根本没有意义（配方与步序都还没确定），于是任何一件不匹配的投入物都被当场抄走。
            // 对一个<b>空闲</b>的执行舱来说，玩家摆在机器上的东西属于那条<b>独立的产线</b>
            // （冲压机自己的配方），与序列装配毫无关系，本仓无权搬运。
            //
            // 新行为：无单时机器侧<b>只收回「本仓产线的产物 / 废料」</b>（那是本仓自己推出去、该收回来的东西），
            // 其余一律不碰。
            // 抽成 final 副本：lambda 里不能捕获「之后还会被重新赋值」的局部变量（noOrderFlag 在本分支之前刚被赋值）。
            // <b>2026-10-05 用户实测的「反复横跳」修复</b>：机器侧<b>不再</b>因为「任务刚结束 / 刚挂起」
            // 那枚一次性令牌（{@code residualEdge}）去收回<b>输入类</b>。
            //
            // 实测形态（用户原话：「把金板输过来就立刻收回，然后导致他一直安装不了，然后一直在那反复横跳」）：
            //   ① 输出总线把「装金板」这一步的金板推给机械手；
            //   ② 紧接着令牌生效 ⇒ 输入总线把刚推过去的金板当「输入类残留」抄回网络；
            //   ③ 输出总线再推一份 ⇒ 再被抄回 …… 机械手永远拿不到那一件，产线原地打转。
            //
            // 为什么原来会这么写：想保证「停任务后机器上的残留（例如已推给注液机、还没消耗的岩浆）
            // 一点不剩」。但代价是<b>把正在产线上的一件也收走</b> —— 用户明确要求
            // 「玩家手动挂起需要把东西给收回去呀」，指的是<b>停任务时把料还回来</b>，
            // 而不是「在干活的当口把刚喂进去的料抽走」。
            //
            // 因此机器侧一律只收回 {@link #isMyProductOrScrap}（本仓产物 / 废料）；
            // 真正的残留回流由 {@code flushResidualInputs()}（任务结束时主动把仓内 / 已领料还回网络）承担，
            // 那条路不需要从机器手里抢料。
            // 抽成 final 副本：lambda 不能捕获「之后还会被重新赋值」的局部变量。
            final boolean noOrderHere = noOrderFlag;
            final BiPredicate<BlockPos, ItemStack> acceptMachineItem = (pos, stack) -> {
                final SequenceExecutionChamberBlockEntity owner = stationOwner(stationOwners, pos, chamber);
                if (owner.isMyProductOrScrap(stack)) {
                    return true; // 工位属主的产物 / 废料：照旧收回（口径与该仓自己的输入总线完全一致）
                }
                // 「任务刚结束」的一次性边沿令牌只属于<b>本总线绑定的那台仓</b>：链上别的仓的收尾由它们
                // 自己的输入总线负责，本总线<b>不代领</b>别人的令牌（同一枚令牌被两条总线各消费一次会
                // 让「只收一次」的语义变成「收两次」，而链展开后本总线已经能扫到整条链的工位）。
                final boolean edge = owner == chamber && residualEdge;
                final boolean noOrder = owner == chamber ? noOrderHere : !owner.isAutoCraftingEnabled();
                return !noOrder && !edge
                    && autoAcceptsItem(pos, stack, inputItemsOf(ownerInputItems, owner), false, owner);
            };
            // 流体侧同样按工位属主判「这一步的输入流体」：用绑定仓的集合去判别人的工位，会把<b>别人正在
            // 加工用的流体</b>（例如注液机里还没消耗的岩浆）当成「非输入类 ⇒ 收回」抽走。
            final BiPredicate<BlockPos, FluidStack> acceptFluid = (pos, stack) -> {
                final SequenceExecutionChamberBlockEntity owner = stationOwner(stationOwners, pos, chamber);
                final boolean edge = owner == chamber && residualEdge;
                return edge || !inputFluidsOf(ownerInputFluids, owner).contains(stack.getFluid());
            };
            // 仓内存储的<b>扫描结构一字未改</b>：仍然只扫「本总线绑定的那台仓」那一份存储一次
            // （同链的仓彼此相邻 ⇒ 本来就是同一个存储集群 {@code RsccMachineCluster}，仓内存储是同一份对象，
            // 按链上每台仓各扫一遍只会让同一份存储被多套口径反复取舍）；变的只是<b>保护集合</b>——
            // 取整条链输入类的并集（见上）。
            final Predicate<FluidStack> acceptChamberFluid = stack ->
                residualEdge || !inputFluids.contains(stack.getFluid());
            accumulate(chamber, level, selfPos, storage, actor, acceptChamberItem, acceptChamberFluid,
                acceptMachineItem, acceptFluid, "auto", detail, tally, transitionSink);
            edgeReclaim = residualEdge;
        } else {
            // ============ 手动：按玩家勾选的类别逐条收回（既有行为） ============
            // <b>2026-10-06 链级展开（用户需求：接在链上任意一处 = 属于整条链）</b>：
            // 遍历的是<b>整条链的类别并集</b>（{@code chainCategories()}，已按 id 去重），
            // 与界面下发的那份快照（{@code busImportCategorySnapshot}）逐字同源 ——
            // 界面上选得到的类别，这里就一定收得动，绝不会出现「选得到却没反应」的假选项。
            // 步序判据由<b>定义该类别的仓</b>回答（本仓定义的类别仍由本仓回答，与改造前逐字一致；
            // 链上别的仓定义的类别才交给它自己）—— 步序是「按仓成立」的：用绑定仓的样板去判
            // 别的仓的步，会把上面那一步的件误判成「已经做完」而抄回网络。
            for (final SequenceExecutionChamberBlockEntity.ChainBusCategory entry
                : chamber.chainCategories()) {
                final SequenceExecutionChamberBlockEntity.BusCategoryInfo info = entry.info();
                if (!selected.contains(info.id())) {
                    continue;
                }
                // 勾中一类 = 收回该类：中间产物类别额外按「步序」匹配（与输出总线推料侧同源，
                // 见 SequenceExecutionChamberBlockEntity#matchesCategoryStep）—— 同一配方里
                // 第 N 步与第 N+1 步可能是同一个处理器（同一个过渡件物品），只有带上步序
                // 才能让「勾第 N 步」只收该步的件，而不是把两步的件一起收走。
                // 仓内存储侧用<b>定义该类别的仓</b>的步序判据（本方法只扫绑定仓的存储）；
                // 机器侧用<b>该工位属主仓</b>的步序判据（链展开后工位可能属于链上另一台仓）。
                final SequenceExecutionChamberBlockEntity definer = entry.owner();
                final Predicate<ItemStack> acceptItem = stack -> info.items().contains(stack.getItem())
                    && definer.matchesCategoryStep(info, stack);
                final BiPredicate<BlockPos, ItemStack> acceptMachineItem = (pos, stack) ->
                    info.items().contains(stack.getItem())
                        && stationOwner(stationOwners, pos, chamber).matchesCategoryStep(info, stack);
                final Predicate<FluidStack> acceptChamberFluid = stack ->
                    info.fluids().contains(stack.getFluid());
                final BiPredicate<BlockPos, FluidStack> acceptFluid = (pos, stack) ->
                    info.fluids().contains(stack.getFluid());
                // 手动模式只按玩家勾选的类别收（与机器无关），因此两套来源用同一个判据的投影
                accumulate(chamber, level, selfPos, storage, actor, acceptItem, acceptChamberFluid,
                    acceptMachineItem, acceptFluid, info.id(), detail, tally, null);
            }
        }

        final long moved = tally[0];
        final long rejected = tally[1];
        if (moved > 0) {
            chamber.setChanged(); // 内部存储可能变了：通知执行舱落盘 / 同步
        }
        final boolean movedAny = moved > 0;
        if (RsccAssemblyDebug.isEnabled()) {
            // 只有真正搬动了东西才打（用户要求：x0 的搬运日志一律不打）。
            // 「余量留在原处」的数量仍然写进正文（rejected），它是「有意义的失败」而不是噪声。
            // 状态串带上 edge / flowing 前缀：于是「任务刚结束的边沿收尾」一定与常规流动态不同，
            // 必然各打一条（用户要求这条边沿收尾要能看见：reason=task_finished_edge），
            // 而同一状态重复发生仍然零输出（稳态零噪声）。
            if (movedAny && RsccAssemblyDebug.changed("take@" + RsccAssemblyDebug.at(selfPos),
                (edgeReclaim ? "edge;" : "flowing;") + "stripped=" + tally[2])) {
                RsccAssemblyDebug.event(debugTag
                    + " linked=" + RsccAssemblyDebug.machine("chamber", chamber.getBlockPos())
                    + " auto=" + auto
                    + (auto ? (edgeReclaim ? " cats=[auto+edge]" : " cats=[auto]") : " cats=" + selected)
                    + " targets=" + chamber.busSupplyTargets().size()
                    + " took {" + (detail == null || detail.length() == 0 ? "-" : detail) + "}"
                    + " inserted=" + moved
                    + " rejected=" + rejected
                    + " mark_removed=" + tally[2]
                    + " result=MOVED reason=" + (edgeReclaim ? "task_finished_edge" : "ok"));
            }
            RsccAssemblyDebug.countCollect(moved);
            RsccAssemblyDebug.countReturn(moved);
            RsccAssemblyDebug.countReject(rejected);
        }
        return movedAny;
    }

    /**
     * 把「仓内 → 网络」与「机器输出侧 → 网络」两个来源的结果累加进 {@code tally}。
     *
     * @param acceptChamberFluid <b>仓内存储</b>侧的流体判据（不带坐标：只扫绑定仓的那一份存储，口径未变）。
     * @param acceptMachineItem 机器侧判据<b>带机器坐标</b>（{@link java.util.function.BiPredicate}）：
     *                          「这台机器此刻还要不要这一份」必须按<b>本机</b>判（机械手的事见
     *                          {@link #autoAcceptsItem}），否则与推料侧不再是同一个判据的两面
     *                          （推料侧按本机判）→ 会出现「推料侧不推、收回侧也不收」的死锁。
     * @param acceptFluid       机器侧流体判据，同样<b>带机器坐标</b>（口径同 {@code acceptMachineItem}）：
     *                          「这一步的输入流体」是<b>工位属主那台仓</b>的数据，链展开后不能拿绑定仓的
     *                          集合去判别人的工位。
     */
    private static void accumulate(final SequenceExecutionChamberBlockEntity chamber, final Level level,
                                   final BlockPos selfPos,
                                   final RootStorage storage, final Actor actor,
                                   final Predicate<ItemStack> acceptChamberItem,
                                   final Predicate<FluidStack> acceptChamberFluid,
                                   final BiPredicate<BlockPos, ItemStack> acceptMachineItem,
                                   final BiPredicate<BlockPos, FluidStack> acceptFluid,
                                   final String label, final StringBuilder detail, final long[] tally,
                                   @org.jetbrains.annotations.Nullable final TransitionSink transitionSink) {
        add(sweepChamberItems(chamber, storage, actor, acceptChamberItem, label, detail), tally);
        add(sweepChamberFluids(chamber, storage, actor, acceptChamberFluid, label, detail), tally);
        for (final BlockPos target : chamber.busSupplyTargets()) {
            add(pullMachineItems(level, selfPos, target, storage, actor, acceptMachineItem, label, detail,
                transitionSink), tally);
            add(pullMachineFluids(level, target, storage, actor, acceptFluid, label, detail), tally);
        }
    }

    /**
     * 工位 → 属主仓（见 {@code SequenceExecutionChamberBlockEntity#chainStationOwners()}）。
     * <p>查不到时退回「本总线绑定的那台仓」：链推导读不出来（区块卸载 / 恰好跨过缓存窗口）时与改造前的
     * 单仓口径逐字一致，绝不因为判不出来而漏扫或错扫。</p>
     */
    private static SequenceExecutionChamberBlockEntity stationOwner(
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> owners, final BlockPos pos,
        final SequenceExecutionChamberBlockEntity fallback) {
        final SequenceExecutionChamberBlockEntity owner = owners == null ? null : owners.get(pos);
        return owner == null ? fallback : owner;
    }

    /** 该仓的「输入类」物品集合（一次 transfer 内按仓缓存：逐格 / 逐罐调用，集合不能反复重建）。 */
    private static Set<Item> inputItemsOf(final Map<SequenceExecutionChamberBlockEntity, Set<Item>> memo,
                                          final SequenceExecutionChamberBlockEntity chamber) {
        return memo.computeIfAbsent(chamber, SequenceExecutionChamberBlockEntity::inputCategoryItems);
    }

    /** 该仓的「流体输入」集合（口径与 {@link #inputItemsOf} 对称）。 */
    private static Set<Fluid> inputFluidsOf(final Map<SequenceExecutionChamberBlockEntity, Set<Fluid>> memo,
                                            final SequenceExecutionChamberBlockEntity chamber) {
        return memo.computeIfAbsent(chamber, SequenceExecutionChamberBlockEntity::inputCategoryFluids);
    }

    private static void add(final ImportTake take, final long[] tally) {
        tally[0] += take.moved();
        tally[1] += take.rejected();
        tally[2] += take.strippedRaw();
    }

    /** 追加一条「本次取回」明细：{@code [label] item=xxx x2/0}（moved/rejected）。 */
    private static void appendTake(final StringBuilder detail, final String label, final String resource,
                                   final long moved, final long rejected) {
        if (detail == null || (moved <= 0 && rejected <= 0)) {
            return;
        }
        if (detail.length() > 0) {
            detail.append(',');
        }
        detail.append('[').append(label).append("] ").append(resource)
            .append(" x").append(moved).append('/').append(rejected);
    }

    /**
     * 一次取回的结果：成功进网络的量 + 被网络拒收（留在原处）的量 + 其中「剥掉原料标记」的件数。
     */
    private record ImportTake(long moved, long rejected, long strippedRaw) {
        private static final ImportTake EMPTY = new ImportTake(0L, 0L, 0L);

        private ImportTake(final long moved, final long rejected) {
            this(moved, rejected, 0L);
        }
    }

    /**
     * 全自动判据（物品）：<b>非输入类</b>才算机器的产出。
     *
     * <p><b>参数 {@code chamber} 的语义（第 24 轮：链 / 分支）</b>：它是<b>这个工位的属主仓</b>
     * （见 {@link #stationOwner} 与
     * {@code SequenceExecutionChamberBlockEntity#chainStationOwners()}），不是「本总线物理绑定的那台仓」。
     * 链展开后一条输入总线会扫到整条链的工位，而这些判据（在制件登记表 / 单元样板 / 步序）全部按仓成立，
     * 因此必须用属主仓。绑定路径下两者是同一台，行为与改造前逐字一致。</p>
     *
     * <p><b>裸输入原料不收回</b>：只有「不带任何进度组件的裸输入原料」才排除，
     * 因此刚喂进机器、还没被吃掉的原料不会被立刻抽回来（与供料互相拉扯）。</p>
     *
     * <p><b>未完成件（带 {@code create:sequenced_assembly} 进度组件）按「执行舱还要不要它」判定</b>
     * —— 这是本轮修掉的最严重问题（用户实测：只完成到第 1 步的中间产物被立刻抽回网络，
     * 没等到变成「步骤 2 完成」）。判据<b>与执行舱推料侧完全同源</b>：
     * {@link SequenceExecutionChamberBlockEntity#isTransitionReclaimAllowed} 内部就是
     * {@code SequenceMaterialGuard#judgeStep} 的「按步骤」结论（{@code s + 1 == m} → 不收回；
     * {@code s >= m} / 无关 → 收回；判不出来 → 不收回），而推料侧
     * {@link SequenceExecutionChamberBlockEntity#isNextForMyMachines} 用的是同一个方法，
     * 因此「推过去」与「收回来」严格互补，同一 tick 内不可能互搏。
     * <ul>
     *     <li>执行舱<b>还会把它交给机器继续加工</b>（{@code s + 1 == m}）→ <b>不收回</b>，
     *     让它留在机器或仓里按步流转（这正是用户要的「等到变成下一步完成再回流」）；</li>
     *     <li>执行舱机器<b>已经做完它那一步</b>（{@code s >= m}）或本仓与它无关 → 收回网络，
     *     由下游（下一台执行舱 / 玩家）接手 —— 多机产线靠这一条才能把完成某步的过渡件送去下一步骤；</li>
     *     <li>成品 / 废料（不带进度组件且不属输入类）→ 照旧收回，<b>正向回收一字未改</b>；
     *     而「输入类原料」（配方原料 / 流体输入）的保护<b>平时成立</b>，例外只有两处（见下）。</li>
     * </ul>
     *
     * <p><b>例外 ①（本轮新增，用户实测断点）：「本仓当前待加工步已经不要它」的步骤专用投入物也收回。</b>
     * 典型就是 {@code create:cogwheel} —— 它既是精密构件第 0 步（机械手装配）的投入物，
     * <b>又</b>在同一条配方 {@code results} 池里（= 废料，用户原话：「精密构建的废料列表里有齿轮」）。
     * 置物台上的在制件已经走到「要大齿轮」那一步时，机械手手里那件齿轮就是压死产线的废料：
     * 推不进去（Create 的 {@code DeployerItemHandler#insertItem} 对「手里已有<b>不同</b>物品」一律拒收），
     * 旧版又把它当「输入类」保护起来不收 → 产线卡死，用户只能手动把齿轮抠出来
     * （原话：「那几次卡顿都是我手动把齿轮拿回去的」）。
     * 判据复用推料侧的同一个
     * {@link SequenceExecutionChamberBlockEntity#inputMaterialWantedNow(BlockPos, Item)}
     * （步骤专用投入物 + <b>这一台机器</b>当前待加工步不要它；<b>必须带坐标</b>：推料侧就是按本机判的，
     * 机械手要看它朝向 2 格外的置物台 / 传送带才知道自己第几步），并且备料侧
     * （{@code SequenceExecutionChamberBlockEntity#pullItem}）用同一族判据<b>同步不放行</b>，
     * 因此「推料放的 ⇔ 收回不放」严格互补、<b>判定只保留一份</b>：
     * 正在加工的那一件（当前步要的）绝不被抽走，也不会出现「收回 → 下一秒又备回来」的来回搬运。
     * 非「步骤专用投入物」（配方主原料等）在它里面恒返回 {@code true}（= 放行），
     * 因此裸原料的保护与「判不出来不拦」的既有口径一字未改。</p>
     *
     * <p><b>例外 ②（既有）</b>：{@code residualEdge}（= 本条任务<b>刚结束</b>的那一个边沿，
     * 见 {@link SequenceExecutionChamberBlockEntity#claimResidualInputReclaim()}）为真时把输入类
     * 一起收<b>一次</b>：令牌被领取后立即作废，因此下一轮又回到「输入类受保护」。
     * 这既满足「停任务后残留（含机器里剩的 500 mB 岩浆）回流一次」，又不会退化成
     * 「网络空闲时把玩家手工放上去的输入原料也持续收走」（用户验收标准 #1）。</p>
     */
    private static boolean autoAcceptsItem(final BlockPos pos, final ItemStack stack,
                                           final Set<Item> inputItems,
                                           final boolean residualEdge,
                                           final SequenceExecutionChamberBlockEntity chamber) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
            // 未完成件：<b>任何时刻</b>都只按「执行舱还要不要它继续加工」判（含「任务刚结束」那一个边沿）。
            //
            // <b>本轮修正（用户实测：「回流了一个精密构件的半成品。最终回来的不可能包含半成品」）</b>：
            // 旧实现在 {@code residualEdge}（= 本条任务刚结束的一次性边沿）里对未完成件<b>一律放行</b>，
            // 于是任务一结束，压在机器上的半成品就被本总线抄进 RS 网络 —— 实测日志：
            // {@code item=create:incomplete_precision_mechanism x1 | from=machine@(-7,-60,6) |
            // event=insert_network | ... | reason=... claimed_by_task=no}，
            // 紧接着 {@code importer@(-8,-60,5) took {... incomplete_precision_mechanism x1/0}
            // ... result=MOVED reason=task_finished_edge}。半成品因此出现在「任务结束后的回流结果」里，
            // 用户明确不接受（「最终回来的不可能包含半成品，不应该能包含半成品」）。
            // 现在未完成件不再吃这个例外：它<b>留在机器上</b>（下次任务继续加工，正是用户认可的归宿
            // 「留在机器上被继续加工」），或者被「跨阶段直接交接」交给负责它下一步的仓
            //（见 {@link #pullMachineItems} 的 {@code transitionSink} 分支）—— 两者都既不销毁也不复制。
            // 而「残留的输入类原料（例如注液机里剩的 500 mB 岩浆）」的一次性回流<b>一字未改</b>，
            // 见下面那两处 {@code residualEdge} 分支。
            //
            // <b>2026-10-06：本判据的步序来源与推料侧是同一份链级并集</b>
            // （{@code SequenceExecutionChamberBlockEntity#chainOwnedSteps()} = 本仓 ∪ 本链各台定义的步）。
            // 为什么必须与推料侧同源：推料侧刚放宽到「认整条链的步」（输出总线清单本来就是链级的），
            // 若收回侧仍只认本台样板，链上别台定义的那一步就会被本总线判成「本仓做完了 / 没人认领」
            // 而把刚推进机器的件抄回网络 —— 那正是「推出 → 收回 → 再推出」的空转（用户实测的拉锯）。
            // 放宽的只有「合起来能不能做」；判据仍在<b>本工位的属主仓</b>上求值（见 {@link #stationOwner}），
            // 属主语义与求值仓都没变。
            return chamber.isTransitionReclaimAllowed(stack);
        }
        // <b>2026-10-05 决定性修复：「没有进度组件的过渡件，绝不回收」。</b>
        //
        // 这是「自动模式不行、手动模式行」的最终答案。手动模式只按玩家勾选的类别放行 / 回收，
        // <b>根本不经过本方法</b>；自动模式则要在这里决定收不收。
        //
        // 实测（构建 23:04:10，快照 20261005-230825）：
        // <pre>
        //   (-16,-60,12)|create:unprocessed_obsidian_sheet|collect  27
        //   (-16,-60,12)|create:unprocessed_obsidian_sheet|return   27   ← 同一件在置物台上循环 27 次
        //   (-16,-60,10)|create:unprocessed_obsidian_sheet|pull     28
        //   (-16,-60,11)|create:unprocessed_obsidian_sheet|push     27
        // </pre>
        //
        // 为什么前面几版闸门全部失效：它们都建立在「能读出这份件的进度步」之上
        // （{@code inTransitionResidence} 的步序判据、{@code stationStepWantsInput}、
        // {@code isNextForMyMachines} 的步序比较）。而<b>坚固板与列车轨道的中间产物本身就不带
        // {@code SEQUENCED_ASSEMBLY} 组件</b> —— 步序根本不存在 ⇒ 所有步序判据全部失效，
        // 一路落到最后一行 {@code !inputItems.contains(...)}（冲压仓的输入类别是空集）⇒ 恒为「回收」。
        //
        // 正确规则（无需步序、无需时间窗口）：<b>过渡件只要不带进度组件，就绝不从机器上回收。</b>
        // 理由：机器上的这种件只有两种可能 ——
        //   ① 正被 Create 加工：加工完成后 Create 会给它加上进度组件，届时走上面那条分支
        //      （{@code isTransitionReclaimAllowed}）正常判定，流程自然继续；
        //   ② 非法残留：由「任务确认结束」的整仓回流收尾（独立路径，不受本闸门影响）。
        // <b>2026-10-06 按设计文档 §7.2 规则 3 重写（唯一判据，不再用任何旁证）。</b>
        //
        // 无组件的过渡件（坚固板 / 列车轨道的中间产物，步序读不出来）：
        //   ① 该配方此刻有活跃订单，且
        //   ② 该配方「下一个未被满足的步」属于本工位
        //   ⇒ 算 IN_STEP(配方, 下一步) ⇒ <b>本工位正在加工它 ⇒ 绝不许回收</b>（返回 false）。
        //
        // 不满足上述条件时返回 true：放行给「跨阶段交接」路径（见 pullMachineItems 的
        // transitionSink 分支），由它把这份件交给负责下一步的另一台仓；若无人接手，
        // 才走常规回收进网络。两条路都不销毁、不复制。
        //
        // 用户实测依据：勾显示的第 3 步时「冲压一次，然后就回去了」—— 第二次冲压前件被抄回网络。
        // 按本条规则，冲压仓拥有第 2、3 步且该配方有活跃订单 ⇒ 第① ② 条都成立 ⇒ 不许回收。
        if (chamber.isTransitionItem(stack.getItem())) {
            return !chamber.rscc$bareTransitionIsMineNow(pos, stack.getItem());
        }
        // <b>用户第 ③ 条硬要求（本轮新增）：已被机器持有、且这台机器「此刻这一步」仍然需要它的投入物，
        // 绝不被收回再推。</b> 这是「齿轮在两台机器间反复推—收—推」的真实机制的直接堵口：
        // 一份齿轮被 A 仓推给共享工位后，朝同一个工位的 B 仓输入总线会来收它 —— 而齿轮往往不在
        // B 仓自己的「输入类」里（B 只负责后面的机械手步骤），于是旧判据「不是我输入类 ⇒ 是产出 /
        // 废料 ⇒ 收回」把这份<b>还没被消耗的、正是该工位本步要用的</b>投入物抄回网络，
        // 下一秒 A 仓又推一份回来 ⇒ 每秒往返。
        // 判据只读（见 {@link SequenceExecutionChamberBlockEntity#stationStepWantsInput}）：按<b>这台机器
        // 上在制件的进度步</b>解析配方与该步输入，判不出来一律放行（绝不武断），因此不会误伤任何产线；
        // 它同时也保护了「配方主原料」（起步原料）与「步骤专用投入物」两类，且与推料侧互补
        //（推料侧放行 ⇔ 本闸门拦下），同一 tick 内不可能互搏。
        // 「任务刚结束」的一次性边沿（{@code residualEdge}）照旧绕开本闸门：收尾必须能把残留还回网络。
        if (!residualEdge && chamber.stationStepWantsInput(pos, stack.getItem())) {
            return false;
        }
        // <b>2026-10-05 修「自动模式抢料、手动模式正常」—— 用户实测的决定性线索。</b>
        //
        // 用户原话：<i>「如果把冲压那部分的输入总线从自动模式改成手动模式，并且只选择这一个黑曜石板，
        // 那么它就会成功了」</i>。手动模式只按「勾选的类别」放行 / 回收，<b>不经过下面这条自动判据</b>；
        // 自动模式则一路落到最后一行：
        // <pre>
        //   return residualEdge || !inputItems.contains(stack.getItem());
        // </pre>
        // 而<b>冲压仓的「输入类别」是空集</b>（冲压步骤不消耗任何下标 ≥1 的投入物，它唯一的输入就是
        // 那个中间产物本身）⇒ {@code !inputItems.contains(任何东西)} <b>恒为 true</b>
        // ⇒ <b>置物台上的在制件每 tick 都被判成「不是我的输入 ⇒ 回收」</b> ⇒ 无限推收循环。
        //
        // 上面那道 {@code stationStepWantsInput} 保护<b>同样失效</b>：它查的是「这一步的投入物
        // （下标 ≥1）」，冲压步没有任何投入物 ⇒ 恒为 false，保护不触发。注液仓之所以正常，
        // 正因为它的输入类别非空（粉 + 岩浆）—— 这就是「注液能收、冲压不能收」的全部原因。
        //
        // 修法（只用一个事实）：<b>凡是「本仓自己的供料目标」，
        // 其上由本仓推过去的过渡件，回收侧一律不碰。</b>
        // 它的去向由两条既有路径负责，都不是「抄回网络」：
        //   * 跨阶段交接（{@code transitionSink}）交给负责下一步的另一台仓；
        //   * 任务确认结束时整仓回流一次。
        // 这样「机器在加工时绝不被抢走」与「步骤做完后照常流转」同时成立，<b>且与步骤耗时无关</b>。
        // 判据用「在制件登记表」而不是 {@code busSupplyTargets()}：后者要求 outputMode == BUS，
        // 玩家的仓是 FACE 模式时返回空表 ⇒ 闸门恒不触发（我上一版改了却没生效的原因正是它）。
        // 登记表由每一次成功推料写入，与输出模式无关。
        //
        // 并且<b>只在「步序还没推进」期间保护</b>（{@link #inTransitionResidence} 的步序判据）——
        // 否则件一到本工位就被永久锁住，步骤做完也走不掉。
        // 这里传 {@code level = null}：只用步序分支，不启用时间兜底（静态判据里拿不到世界时间）。
        if (chamber.isTransitionItem(stack.getItem())
            && chamber.rscc$isRegisteredStation(pos)
            && inTransitionResidence(pos, null, stack)) {
            return false;
        }
        // 例外 ①：输入类里「<b>这一台机器</b>当前待加工步不要的那一份」= 压在机器上的废料性残留 → 收回。
        // <b>本轮修正：判据带机器坐标</b>（原来用「全仓并集」的旧口径）。必须按本机判，因为推料侧
        // （RsccChamberExportStrategy#transferItem → SequenceExecutionChamberBlockEntity#inputMaterialWantedNow
        // (BlockPos, Item)）是按本机判的：机械手要看它朝向 2 格外的置物台 / 传送带才知道自己第几步。
        // 两侧口径不同会造出新的死锁：手里压着一件「本步不要的」投入物时，推料侧（正确地）不推，
        // 而旧口径的收回侧认为「全仓还要它」也不收 → 那件永远拿不掉，产线永久停在「拿住的却是大齿轮」。
        // 注意：非「步骤专用投入物」（配方主原料等）在 inputMaterialWantedNow 里恒返回 true，因此
        // 「刚喂进去的原料不被立刻抄回来」这条保护一点没变。
        //
        // <b>本轮新增（用户实测：金板被反复「推出去 → 收回来」，网络存量来回跳动 = 多耗金板 + ③
        // 「数值异常大幅变动」的共同根因）：「起步原料」在任务期间一律不收回。</b>
        // 现场：一条产线里 A 仓把金板推给共享工位（置物台 / 传送带）开一件在制件，而 B 仓的输入总线
        // 朝向同一个工位；B 仓的「输入类」里并不含金板（它只负责后面的机械手步骤），于是旧判据
        // 「不属于我的输入类 ⇒ 是产出 / 废料 ⇒ 收回」把那份<b>还没被消耗的起步原料</b>抄回网络 ——
        // 下一秒 A 仓又推一份回来，形成每秒往返；用户看到的正是「金板多输出一个、是多余的」
        // 与「网络存量瞬间掉一截又慢慢回来」。
        // 起步原料（Create 序列装配的 ingredient）永远只可能是「要开新件的那一份」，不可能是废料；
        // 因此任务运行期间它只该留在工位上被消耗，<b>任务结束的那一次边沿（residualEdge）照旧收一遍</b>
        // （用户验收标准：停任务后残留要回流一次），保护口径与「输入类」完全一致。
        if (chamber.isStartIngredient(stack.getItem()) && !residualEdge) {
            return false;
        }
        if (inputItems.contains(stack.getItem()) && !chamber.inputMaterialWantedNow(pos, stack.getItem())) {
            return true;
        }
        // 例外 ② + 常规：输入类平时受保护；只有「任务刚结束」的那一次边沿收尾才连输入类一起收；
        // 非输入类（成品 / 废料）一律收回
        return residualEdge || !inputItems.contains(stack.getItem());
    }

    /**
     * 全自动判据（物品）——<b>执行舱内部存储</b>专用（与机器侧的 {@link #autoAcceptsItem} 分开）。
     *
     * <h2>为什么要与机器侧分开（用户实测「一直输出 / 不会转移」的根因）</h2>
     * 「本步不要它就收回」这条判据是为<b>压在机器 / 机械手手里</b>的废料性残留准备的：
     * 那份料物理上挡着机器（Create 的 {@code DeployerItemHandler} 对「手里已有不同物品」一律拒收），
     * 不收走产线就永久卡住。但执行舱内部存储里那一份只是<b>还没轮到的备料</b>：
     * 备料侧刚把它从网络买进来（可能正是「下一步」要用的那一件），若用同一条判据立刻抄回网络，
     * 就等于「买进来 → 退回去」每秒来回搬运 —— 实测日志里
     * {@code chamber pull{cogwheel x5}} 与 {@code importer took{cogwheel x5}} 出现在同一秒，
     * 机器一个件都推不出去（用户：「还是会一直输出 / 连转移都不会转移了」）。
     *
     * <p>因此仓内存储里的「输入类」（配方原料 / 流体输入 / 步骤专用投入物）<b>平时一律受保护</b>，
     * 只有任务刚结束的那一次边沿（{@code residualEdge}）才收一遍；非输入类（成品 / 废料）与
     * 过渡件的判据与机器侧完全一致（{@link #autoAcceptsItem}）。判据只读，绝不搬运 / 销毁资源。</p>
     *
     * <p>本轮新增的「在途任务预留释放」是<b>并列的第二条例外</b>
     * （{@link #releasesChamberItemForInflight}），本方法一个字都没有改 —— 两条判据在调用处以
     * {@code ||} 合成，于是既有行为可逐字核对。</p>
     */
    private static boolean autoAcceptsChamberItem(final ItemStack stack, final Set<Item> inputItems,
                                                  final boolean residualEdge,
                                                  final SequenceExecutionChamberBlockEntity chamber) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
            // 未完成件：同机器侧（autoAcceptsItem）—— 任何时刻都按「执行舱还要不要它」判，
            // 「任务刚结束」那一个一次性边沿也不例外（用户硬要求：最终交付 / 回流结果里不得出现半成品）。
            return chamber.isTransitionReclaimAllowed(stack);
        }
        if (inputItems.contains(stack.getItem())) {
            // <b>本轮修正（B 组：自动模式下废料必须统一收回）</b>：一个物品<b>既</b>是某步的投入物、
            // <b>又</b>在同一条配方的 results 池里（= 废料，典型 {@code create:cogwheel}）时，它同时
            // 落在「输入类」与「废料」两个类别里。旧实现先按「输入类」恒保护 ⇒ 这份废料在仓里<b>永远
            // 收不回去</b>，而界面上废料类别明明标着「已收回」—— 正是用户原话「你这显示『收回齿轮』啊，
            // 你为什么又不收回那个齿轮？」。现在补一条例外：<b>没有任何工位此刻要它的废料</b>照常收回。
            //
            // 判据与机器侧 {@link #autoAcceptsItem} 的例外 ① 严格同源（同一个
            // {@link SequenceExecutionChamberBlockEntity#inputMaterialWantedNow(Item)} 全仓并集版）：
            // 「任一工位还要它」⇒ 受保护（不收回），「一个工位都不要它」⇒ 是废料性残留，收回网络。
            // 起步原料永不适用本例外（它只可能是「要开新件的那一份」，绝不是废料）。
            if (!chamber.isStartIngredient(stack.getItem())
                && chamber.isScrapItem(stack.getItem())
                && !chamber.inputMaterialWantedNow(stack.getItem())) {
                return true;
            }
            // 仓内备料：平时受保护（避免与备料侧来回搬运），只有任务结束边沿收一次
            return residualEdge;
        }
        // 起步原料与机器侧同口径（见 autoAcceptsItem 的说明）：任务期间绝不收回，
        // 只有「任务刚结束」的那一次边沿收一遍。放在这里是为了覆盖「它不是本仓输入类」的跨仓情形。
        if (chamber.isStartIngredient(stack.getItem())) {
            return residualEdge;
        }
        return true; // 成品 / 废料：照旧收回
    }

    /**
     * <b>本轮新增：仓内「某条在途自动合成任务正等着抽取」的那一份照常还回网络。</b>
     *
     * <h2>用户报告与机制</h2>
     * <p>用户原话：「针对精密构件，他们在制作的时候，有可能被你提前消耗」；「我看着有几个齿轮和大齿轮的
     * 合成任务位置卡在那里不动」，且确认与精密构件制作相关。</p>
     * <p>机制（RS 源码交叉验证，全文见 {@link SequenceMaterialGuard#isInFlight}）：RS 2.0
     * <b>没有任何「网络存量已被预留」的表示</b> —— 根存储只有<b>插入</b>侧的拦截钩子
     * （{@code RootStorageListener.java:18,30}），抽取侧是裸委托
     * （{@code RootStorageImpl.java:85-87}）。一条任务「还没拿到」的量只活在它自己的
     * {@code initialRequirements} 账上（{@code TaskImpl.java:199-222}，抽到就在 {@code :214} 减掉），
     * 并被 RS 报成 {@code TaskStatus.Item#extracting}（{@code autocrafting/status} 包里那个
     * 状态构造器唯一写它的地方）。因此只要本模组把网络里那几件抽进自己怀里，那条任务就
     * <b>永远等不到</b>：它每一步只再试一次 {@code extract}，没有超时、没有第二次机会，
     * 于是在自动合成监视器上<b>永久停在原地</b> —— 正是用户看到的现象。</p>
     *
     * <h2>为什么这一侧（仓内 → 网络）是修得动的那一侧</h2>
     * <p>本侧只把东西<b>还回网络</b>，从不扣减别人，因此不可能制造新的饿死；而「仓内压着一件
     * 没有任何工位要它、却正是别人在等的东西」是本模组唯一能主动解开那种卡死的动作。</p>
     *
     * <h2>为什么不会抢走机器正等的料、也不会变成每秒往返</h2>
     * <ul>
     *     <li>与既有的「废料例外」共用一个前置条件：{@code inputMaterialWantedNow(item)} 为假
     *     （<b>整条链上没有任何工位此刻要它</b>）—— 机器正等的那一件永远不满足它；</li>
     *     <li>起步原料一律不适用（它只可能是「要开新件的那一份」）；</li>
     *     <li>未完成件（带 {@code create:sequenced_assembly} 组件）一律不适用 ——
     *     「下一步归本机」的保护（{@code isTransitionReclaimAllowed}）仍由既有判据独占；</li>
     *     <li>备料侧的闸门是「没有工位要它 ⇒ 本 tick 一条判断都不做」
     *     （{@code SequenceExecutionChamberBlockEntity#fillInternalForBus} 里的
     *     {@code stepExtraStockTarget(item) <= 0 ⇒ continue}），也就是<b>不会再买回来</b>，
     *     因此不存在「买进来 → 退回去」的每秒往返。</li>
     * </ul>
     *
     * <p><b>只读</b>：本方法只回答「这一件该不该还回网络」，不搬运任何资源。</p>
     *
     * @param claimedByInflight 「这一件是不是某条在途任务等着抽取的资源」（{@code null} = 本轮不启用）
     */
    private static boolean releasesChamberItemForInflight(final ItemStack stack, final Set<Item> inputItems,
                                                          final SequenceExecutionChamberBlockEntity chamber,
                                                          @org.jetbrains.annotations.Nullable
                                                          final Predicate<ItemStack> claimedByInflight) {
        if (claimedByInflight == null || stack.isEmpty() || !inputItems.contains(stack.getItem())) {
            return false;
        }
        if (stack.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
            return false; // 未完成件：「下一步归本机」的保护由既有判据独占，绝不由本例外放开
        }
        if (chamber.isStartIngredient(stack.getItem())) {
            return false; // 起步原料：只可能是「要开新件的那一份」，绝不收回
        }
        if (chamber.inputMaterialWantedNow(stack.getItem())) {
            return false; // 有任何工位此刻要它 ⇒ 绝不抢走
        }
        return claimedByInflight.test(stack);
    }

    /**
     * <b>只读</b>：把网络上「已被在途自动合成任务等着抽取的资源」汇总成一张表
     * （资源 → 还没抽到的量）。
     *
     * <p>{@code AutocraftingNetworkComponent#getStatuses()} 会为每条任务重建一次
     * {@code TaskStatus}（RS 侧 {@code AutocraftingNetworkComponentImpl.java:244-247}），因此
     * <b>每次搬运只算一次</b>、不放进逐格循环里。取不到自动合成组件时返回空表 ——
     * 于是「没有自动合成能力」的网络行为与改造前逐字一致。</p>
     *
     * <p>本方法<b>不排除</b>本产线自己的任务：这里的用途是「把仓里没人要的那一份还回网络」，
     * 是纯释放方向、绝不扣减别人，因此多算（把自己的任务也算进来）只会让释放更容易成立，
     * 不会饿死任何一方。需要「别人的预留」语义（从网络取料前夹住预算）的调用方必须用
     * {@code SequenceMaterialGuard#pendingExtraction(List, Set)} 并传入自己的任务 id。</p>
     */
    private static Map<ResourceKey, Long> inflightClaimsOf(final Network network) {
        final AutocraftingNetworkComponent autocrafting =
            network == null ? null : network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return java.util.Map.of();
        }
        return SequenceMaterialGuard.pendingExtraction(autocrafting.getStatuses());
    }

    // ==================== 执行舱内部存储 → RS 网络 ====================

    /**
     * 把执行舱内部物品存储里「被 {@code accept} 接受」的堆叠收进网络。
     * <p>手动模式按<b>物品种类</b>匹配（忽略数据组件，与输出总线侧「强制模糊模式」同一口径，
     * 因此过渡件即使换了数据组件也认得出）；全自动模式由 {@link #autoAcceptsItem} 判定。</p>
     */
    private static ImportTake sweepChamberItems(final SequenceExecutionChamberBlockEntity chamber,
                                                final RootStorage storage, final Actor actor,
                                                final Predicate<ItemStack> accept, final String label,
                                                final StringBuilder detail) {
        long moved = 0;
        long rejected = 0;
        long stripped = 0;
        // 遍历「执行舱物品存储统一视图」（内部存储 + 磁盘缓存）：磁盘里的中间产物同样会被收回网络，
        // 因此「东西进了磁盘就再也收不回来」的死角不存在（见 RsccChamberItemStorage）。
        final net.neoforged.neoforge.items.IItemHandler chamberStore = chamber.itemStorage();
        for (int slot = 0; slot < chamberStore.getSlots(); slot++) {
            final ItemStack inSlot = chamberStore.getStackInSlot(slot);
            if (inSlot.isEmpty() || !accept.test(inSlot)) {
                continue;
            }
            // 入网资源：原料必须先去掉「原料标记」（网络里只保留原始原料这一种资源），
            // 过渡件则连数据组件一起入网（下一个执行舱靠它认领当前进度）
            final boolean rawMaterial = SequenceMaterialGuard.isRawMaterial(inSlot);
            final ItemResource resource = rawMaterial
                ? new ItemResource(inSlot.getItem(), DataComponentPatch.EMPTY)
                : ItemResource.ofItemStack(inSlot);
            // 只收网络收得下的量：先模拟插入，收不下就这一格一点都不动（留在执行舱）
            final long acceptable = storage.insert(resource, inSlot.getCount(), Action.SIMULATE, actor);
            if (acceptable <= 0) {
                rejected += inSlot.getCount();
                continue;
            }
            final ItemStack taken = chamberStore.extractItem(
                slot, (int) Math.min(acceptable, inSlot.getCount()), false);
            if (taken.isEmpty()) {
                continue;
            }
            final long netBefore = networkItemAmount(storage, taken.getItem());
            final long inserted = storage.insert(resource, taken.getCount(), Action.EXECUTE, actor);
            final long landed = landedAmount(storage, taken.getItem(), netBefore, inserted);
            if (inserted < taken.getCount()) {
                // 网络没吃完（理论不可达：已按 SIMULATE 夹过）：余量原样放回执行舱（内部优先、满了进磁盘），绝不销毁
                chamberStore.insertItem(0,
                    taken.copyWithCount((int) (taken.getCount() - inserted)), false);
                rejected += taken.getCount() - inserted;
            }
            if (inserted > 0) {
                moved += landed;
                if (rawMaterial) {
                    stripped += landed; // 这一份是「剥掉原料标记后」才进网络的
                }
                appendTake(detail, label, "item=" + RsccAssemblyDebug.itemId(taken.getItem()), landed,
                    taken.getCount() - landed);
                // 追踪：执行舱内部存储里的件回写网络（与机器侧同一套字段口径）
                traceInsert(RsccAssemblyDebug.machine("chamber", chamber.getBlockPos()), storage,
                    "chamber", chamber.getBlockPos(), taken.getCount(), inserted, landed, taken);
            }
        }
        return new ImportTake(moved, rejected, stripped);
    }

    /**
     * 把执行舱内部流体存储里「被 {@code accept} 接受」的罐收进网络。
     * <p>先取罐快照再逐个扣减，避免边遍历边改内部列表
     * （{@link RsccUnboundedFluidStorage#getTanksSnapshot()}）。</p>
     */
    private static ImportTake sweepChamberFluids(final SequenceExecutionChamberBlockEntity chamber,
                                                 final RootStorage storage, final Actor actor,
                                                 final Predicate<FluidStack> accept, final String label,
                                                 final StringBuilder detail) {
        long moved = 0;
        long rejected = 0;
        for (final FluidStack inTank : chamber.outputTank.getTanksSnapshot()) {
            if (inTank.isEmpty() || !accept.test(inTank)) {
                continue;
            }
            final FluidResource resource = new FluidResource(inTank.getFluid(), inTank.getComponentsPatch());
            // 只收网络收得下的量：先模拟插入，收不下就这一罐一点都不动（留在执行舱）
            final long acceptable = storage.insert(resource, inTank.getAmount(), Action.SIMULATE, actor);
            if (acceptable <= 0) {
                rejected += inTank.getAmount();
                continue;
            }
            final FluidStack drained = chamber.outputTank.drain(
                inTank.copyWithAmount((int) Math.min(acceptable, inTank.getAmount())),
                IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                continue;
            }
            final long netBefore = networkFluidAmount(storage, inTank.getFluid());
            final long inserted = storage.insert(
                new FluidResource(drained.getFluid(), drained.getComponentsPatch()),
                drained.getAmount(), Action.EXECUTE, actor);
            final long landed = landedAmountFluid(storage, drained.getFluid(), netBefore, inserted);
            if (inserted < drained.getAmount()) {
                // 网络没吃完（理论不可达：已按 SIMULATE 夹过）：余量原样放回执行舱，绝不销毁
                chamber.outputTank.fill(
                    drained.copyWithAmount((int) (drained.getAmount() - inserted)),
                    IFluidHandler.FluidAction.EXECUTE);
                rejected += drained.getAmount() - inserted;
            }
            if (inserted > 0) {
                moved += landed;
                appendTake(detail, label, "fluid=" + RsccAssemblyDebug.fluidId(drained.getFluid()), landed,
                    drained.getAmount() - landed);
            }
        }
        return new ImportTake(moved, rejected);
    }

    /**
     * <b>「刚推给机器的过渡件必须留够加工时间才允许收回」</b>（2026-10-05 修「坚固板做不出来」）。
     *
     * <h2>证据（实机日志，构建 20:27:53）</h2>
     * <pre>
     * exporter@(-16,-60,11) bus_push unprocessed_obsidian_sheet -&gt; machine@(-16,-60,12) EXPORTED/ok
     * machine@(-16,-60,12) take_from_machine -&gt; importer@(-15,-60,12) reason=ok      ← 立刻抄走
     * （同一对 push / take 循环 24 次）
     * 全日志 item=create:sturdy_sheet 计数 = 0                                ← 一件成品都没产出
     * </pre>
     *
     * <p>机制：{@link #pullMachineItems} 的「跨阶段中间产物直接交给接手仓」分支
     * （见本文件 {@code transitionSink != null && isTransitionItem(probe)}）会在物品刚被推进机器的
     * <b>下一 tick</b> 就把它取走 —— Create 的冲压机需要若干 tick 才能完成一次冲压，
     * 于是<b>那一件永远压不完</b>，也就永远产不出 {@code create:sturdy_sheet}。
     * 玩家听到的「冲压声音」正是这台机器在反复接收 / 失去同一件料。</p>
     *
     * <p>修法：记录「最近一次把过渡件推进某台机器」的时刻，在
     * {@value #TRANSITION_RESIDENCE_TICKS} tick 内<b>不从这个工位收回过渡件</b>。
     * 加工完成后机器会产出下一阶段物品（或把在制件留在台上），过了窗口照常收回，
     * 因此既不会饿死产线、也不会让料永久滞留。</p>
     */
    private static final long TRANSITION_RESIDENCE_TICKS = 200L;

    /** 工位 → 最近一次「推入过渡件」的游戏刻（兜底用；判据以步序为准）。 */
    private static final java.util.Map<BlockPos, Long> TRANSITION_PUSHED_AT =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 工位 → <b>推进去那一刻该件的进度步</b>。
     *
     * <h2>为什么用步序而不是时间（用户指出的关键）</h2>
     * <p>用户原话：<i>「你放宽到多少秒都没有用，因为哪天有个整合包作者直接让某一个步骤的时间变成
     * 60 秒，就单纯恶心你，你放宽了多少秒都没用」</i>。</p>
     * <p>完全正确：<b>任何固定的时间窗口都是错的判据</b>。正确的信号是
     * 「<b>这一步做完了没有</b>」，而它只体现在<b>物品自身的进度步</b>上 ——
     * 机器加工完成后，Create 会把那个件的 {@code step} 推进一位。
     * 因此判据是：<b>推入时记的步序 == 该件当前的步序 ⇒ 这一步还没做完 ⇒ 绝不许回收</b>。
     * 与步骤耗时无关（1 tick 还是 60 秒都一样正确）。</p>
     */
    private static final java.util.Map<BlockPos, Integer> TRANSITION_STEP_AT =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 记一次「刚把过渡件推进该工位」（由输出总线侧在成功推送后调用）。 */
    public static void noteTransitionPushed(final BlockPos target, final long gameTime,
                                            final int step) {
        if (target == null) {
            return;
        }
        TRANSITION_PUSHED_AT.put(target.immutable(), gameTime);
        TRANSITION_STEP_AT.put(target.immutable(), step);
    }

    /**
     * 该工位上的这份过渡件是否<b>「还在加工推进去时的那一步」</b>（= 绝不许回收）。
     *
     * <p><b>判据是步序，不是时间</b>（见 {@link #TRANSITION_STEP_AT} 的说明）：
     * 推入时记下该件的 {@code step}，只要它<b>还是那个值</b>，就说明这一步没做完 ——
     * 无论这一步要 1 tick 还是 60 秒，结论都正确。</p>
     *
     * <p>只有「判不出步序」（该件没有进度组件，或没有推入记录）时才退回
     * {@link #TRANSITION_RESIDENCE_TICKS} 那个时间兜底 —— 它只是兜底，不是主判据。</p>
     */
    private static boolean inTransitionResidence(final BlockPos pos, final Level level,
                                                 final ItemStack probe) {
        final Integer pushedStep = TRANSITION_STEP_AT.get(pos);
        final var progress = probe == null ? null
            : probe.get(com.simibubi.create.AllDataComponents.SEQUENCED_ASSEMBLY);
        if (pushedStep != null && pushedStep >= 0 && progress != null) {
            // 步序没变 ⇒ 机器还在做这一步 ⇒ 拦住；步序推进了 ⇒ 放行（正是用户要的语义）
            return progress.step() == pushedStep;
        }
        final Long at = TRANSITION_PUSHED_AT.get(pos);
        return at != null && level != null && level.getGameTime() - at < TRANSITION_RESIDENCE_TICKS;
    }

    // ==================== 机器 / 置物台输出侧 → RS 网络 ====================

    /**
     * 从「本仓正在供料的那台机器」的物品栏里，把被 {@code accept} 接受的东西收进网络。
     * <p>语义与 {@link #sweepChamberItems} 完全一致（先 SIMULATE 夹量 → 原子抽取 → 插入 → 余量原样
     * 还回机器），只是取货源换成了机器的 {@link IItemHandler}；机器拒绝收回滚时绝不销毁。</p>
     *
     * <p><b>唯一的例外：跨阶段中间产物直接交给接手仓</b>（{@code transitionSink} 非空时，见
     * {@link NetworkTransitionSink}）—— 它不由本方法插入网络，而是放进「负责它下一步」那台执行仓的
     * 内部存储。判定与搬运都仍在本方法的「先模拟、再抽取、余量还回」这套守恒口径内。</p>
     */
    private static ImportTake pullMachineItems(final Level level, final BlockPos selfPos,
                                               final BlockPos pos,
                                               final RootStorage storage, final Actor actor,
                                               final BiPredicate<BlockPos, ItemStack> accept,
                                               final String label,
                                               final StringBuilder detail,
                                               @org.jetbrains.annotations.Nullable final TransitionSink transitionSink) {
        final IItemHandler handler = itemHandlerAt(level, pos);
        if (handler == null) {
            return ImportTake.EMPTY;
        }
        long moved = 0;
        long rejected = 0;
        long stripped = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack probe = handler.extractItem(slot, COLLECT_BATCH, true);
            if (probe.isEmpty() || !accept.test(pos, probe)) {
                continue;
            }
            // <b>留驻闸门（2026-10-05 提到最前面 —— 修「冲压被错误回收」）。</b>
            //
            // 用户语义原话：<i>「他现在正在进行第二步，然后输入总线就想着：他在进行第二步，
            // 我也等到他第三步的时候再回收；然后他冲压变成第三步，OK 回收。」</i>
            // 也就是：<b>机器这一步没做完，绝不许动它台上的件。</b>
            //
            // 上一版我把这道闸门只加在下面「跨阶段交接」那一条分支上，于是它<b>只挡住了交接、
            // 没挡住真正的回收路径</b>（generic {@code storage.insert} 那条）—— 冲压仓照样每 tick 抢料。
            //
            // 放到循环最前面 ⇒ 留驻期内这份过渡件<b>一律不碰</b>（既不交接、也不回收），
            // 等 Create 把这一步做完、物品自身的进度步推进之后，闸门自然放行。
            if (isTransitionItem(probe) && inTransitionResidence(pos, level, probe)) {
                continue;
            }
            // ① 跨阶段中间产物：先问「本网络里有没有一台仓正等着它下一步」，有就直接交给它（不经网络）
            if (transitionSink != null && isTransitionItem(probe)) {

                final int localOk = transitionSink.simulate(probe, pos);
                if (localOk > 0) {
                    final ItemStack takenLocal = handler.extractItem(slot, localOk, false);
                    if (!takenLocal.isEmpty()) {
                        final int leftover = transitionSink.accept(takenLocal);
                        if (leftover > 0) {
                            // 接手仓没吃完（理论不可达：已按 SIMULATE 夹过）：余量原样还回机器，绝不销毁
                            handler.insertItem(slot,
                                takenLocal.copyWithCount(leftover), false);
                        }
                        final int handed = takenLocal.getCount() - leftover;
                        moved += handed; // 它确实从机器上被取走了（只是去向是「接手仓」而不是网络）
                        traceTake(tagOf(selfPos), "machine", pos, takenLocal.getItem(), takenLocal.getCount(),
                            "ok_handover");
                        traceHandover(tagOf(selfPos), pos, takenLocal, leftover);
                        appendTake(detail, "handover",
                            "item=" + RsccAssemblyDebug.itemId(takenLocal.getItem()), handed, leftover);
                        continue; // 这一格本轮处理完（本总线一次只搬一格）
                    }
                }
            }
            final boolean rawMaterial = SequenceMaterialGuard.isRawMaterial(probe);
            final ItemStack toInsert = rawMaterial ? strippedCopy(probe) : probe;
            final ItemResource resource = ItemResource.ofItemStack(toInsert);
            final long acceptable = storage.insert(resource, toInsert.getCount(), Action.SIMULATE, actor);
            if (acceptable <= 0) {
                rejected += probe.getCount();
                continue;
            }
            final ItemStack taken = handler.extractItem(slot, (int) Math.min(acceptable, probe.getCount()), false);
            if (taken.isEmpty()) {
                continue;
            }
            // 追踪（「机器产出 → 输入总线收取」这一段）：机器侧这一份被本总线取走
            traceTake(tagOf(selfPos), "machine", pos, taken.getItem(), taken.getCount(), "ok");
            final ItemStack insertStack = rawMaterial ? strippedCopy(taken) : taken;
            // <b>插入前后各读一次网络聚合存量</b>：RS 的 insert 返回值把「被任务截收的量」也算进
            // inserted（见 RootStorageImpl#insert），而某些存储源（例如挂在 Create 创造板条箱上的
            // RS 外部存储：BottomlessItemHandler#insertItem 恒返回 EMPTY）会「假收下」——
            // 上报成功、却既不进容器也不进网络聚合存量。两者都表现为 reported &gt; landed。
            final long netBefore = networkItemAmount(storage, taken.getItem());
            final long inserted = storage.insert(
                ItemResource.ofItemStack(insertStack), insertStack.getCount(), Action.EXECUTE, actor);
            final long landed = landedAmount(storage, taken.getItem(), netBefore, inserted);
            if (inserted < taken.getCount()) {
                // 网络没吃完（理论不可达：已按 SIMULATE 夹过）：余量原样还回机器，绝不销毁
                handler.insertItem(slot, taken.copyWithCount((int) (taken.getCount() - inserted)), false);
                rejected += taken.getCount() - inserted;
            }
            if (inserted > 0) {
                moved += landed;
                if (rawMaterial) {
                    stripped += landed;
                }
                appendTake(detail, label, "item=" + RsccAssemblyDebug.itemId(taken.getItem()), landed,
                    taken.getCount() - landed);
                traceInsert(tagOf(selfPos), storage, "machine", pos, taken.getCount(), inserted, landed,
                    taken);
            }
        }
        return new ImportTake(moved, rejected, stripped);
    }

    /** 从「本仓正在供料的那台机器」的流体罐里把被 {@code accept} 接受的流体收进网络（语义同上）。 */
    private static ImportTake pullMachineFluids(final Level level, final BlockPos pos,
                                                final RootStorage storage, final Actor actor,
                                                final BiPredicate<BlockPos, FluidStack> accept,
                                                final String label,
                                                final StringBuilder detail) {
        final IFluidHandler handler = fluidHandlerAt(level, pos);
        if (handler == null) {
            return ImportTake.EMPTY;
        }
        long moved = 0;
        long rejected = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            final FluidStack inTank = handler.getFluidInTank(tank);
            if (inTank.isEmpty() || !accept.test(pos, inTank)) {
                continue;
            }
            final FluidResource resource = new FluidResource(inTank.getFluid(), inTank.getComponentsPatch());
            final long acceptable = storage.insert(resource, inTank.getAmount(), Action.SIMULATE, actor);
            if (acceptable <= 0) {
                rejected += inTank.getAmount();
                continue;
            }
            final int want = (int) Math.min(acceptable, Math.min(inTank.getAmount(), FLUID_COLLECT_BATCH));
            final FluidStack drained = handler.drain(inTank.copyWithAmount(want),
                IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) {
                continue;
            }
            final long netBefore = networkFluidAmount(storage, drained.getFluid());
            final long inserted = storage.insert(
                new FluidResource(drained.getFluid(), drained.getComponentsPatch()),
                drained.getAmount(), Action.EXECUTE, actor);
            final long landed = landedAmountFluid(storage, drained.getFluid(), netBefore, inserted);
            if (inserted < drained.getAmount()) {
                // 网络没吃完（理论不可达）：余量原样还回机器，绝不销毁
                handler.fill(drained.copyWithAmount((int) (drained.getAmount() - inserted)),
                    IFluidHandler.FluidAction.EXECUTE);
                rejected += drained.getAmount() - inserted;
            }
            if (inserted > 0) {
                moved += landed;
                appendTake(detail, label, "fluid=" + RsccAssemblyDebug.fluidId(drained.getFluid()), landed,
                    drained.getAmount() - landed);
            }
        }
        return new ImportTake(moved, rejected);
    }

    /** 去掉原料标记的副本（网络里只保留「原始原料」这一种资源，口径与执行舱侧一致）。 */
    private static ItemStack strippedCopy(final ItemStack stack) {
        final ItemStack copy = stack.copy();
        SequenceMaterialGuard.stripRawMaterial(copy);
        return copy;
    }

    // ==================== 端到端追踪（本轮新增；只读，不参与任何搬运判定） ====================

    /**
     * 追踪：<b>「机器产出 → 输入总线收取」</b>这一段（{@code event=take_from_machine}）。
     * <p>说明「这一份是谁从哪台机器拿走、交给了哪条输入总线」，因此「机器上明明产出过、却没人收」
     * 与「收了」可以逐行区分。</p>
     */
    private static void traceTake(final String tag, final String fromKind, final BlockPos fromPos,
                                  @org.jetbrains.annotations.Nullable final Item item,
                                  final long amount, final String reason) {
        if (!RsccAssemblyDebug.isEnabled() || item == null) {
            return;
        }
        RsccAssemblyDebug.trace("trace-take:" + tag + "#" + RsccAssemblyDebug.itemId(item),
            RsccAssemblyDebug.traceLine("item=" + RsccAssemblyDebug.itemId(item), amount,
                RsccAssemblyDebug.machine(fromKind, fromPos), "take_from_machine", tag, reason, -1L));
    }

    /** 输入总线自身的日志前缀（{@code importer@(x,y,z)}）。 */
    private static String tagOf(final BlockPos selfPos) {
        return RsccAssemblyDebug.machine("importer", selfPos);
    }

    /**
     * 追踪：<b>「机器产出 → 接手仓」</b>这一段（{@code event=handover_to_chamber}）。
     *
     * <p>它证明「这份跨阶段中间产物<b>没有</b>进 RS 网络，而是直接交给了负责它下一步的那台仓」——
     * 于是「进网络 → 在两秒后仍然读不到」这条断链在日志里可以直接排除。</p>
     */
    private static void traceHandover(final String tag, final BlockPos machinePos, final ItemStack stack,
                                      final int leftover) {
        if (!RsccAssemblyDebug.isEnabled() || stack == null || stack.isEmpty()) {
            return;
        }
        final String to = NetworkTransitionSink.lastSinkPos() == null
            ? "-" : RsccAssemblyDebug.machine("chamber", NetworkTransitionSink.lastSinkPos());
        final long handed = stack.getCount() - Math.max(0, leftover);
        RsccAssemblyDebug.trace("trace-handover:" + tag + "#" + RsccAssemblyDebug.itemId(stack.getItem())
                + "#" + to,
            RsccAssemblyDebug.traceLine("item=" + RsccAssemblyDebug.itemId(stack.getItem()), handed,
                RsccAssemblyDebug.machine("machine", machinePos), "handover_to_chamber", to,
                "cross_stage_next_step_local (" + tag + " 直接交接，不经网络)", -1L));
    }

    /**
     * 「跨阶段中间产物」的<b>本地接手方</b>端口（仅本类使用）。
     *
     * <p><b>为什么需要它（用户实测断点的根因）</b>：一条多仓产线里，上一阶段机器产出的过渡件常常要交给
     * <b>另一台</b>执行仓（例如坚固板：注液机产出未完成黑曜石板 → 冲压仓负责第 2、3 步）。
     * 旧实现只有一条路 —— 让输入总线把它插进 RS 网络，再由接手仓从网络里拉回来。实测
     * （latest.log 10:14:48）它插入时 {@code reported=1 landed=1 net_after=1}，而两秒后接手仓读到的
     * 仍是 {@code net=0 reason=no_stock}：这份中间产物<b>在网络里留不住</b>，于是冲压机永远拿不到料，
     * 坚固板一件都出不来（本局 {@code sturdy_sheet} 只有 4 条、{@code unprocessed_obsidian_sheet} 140 条）。
     * 直接交接把这一环从「网络」上摘掉，链路对「网络里有没有它」不再有任何依赖。</p>
     *
     * <p><b>守恒</b>：调用方必须按 {@link #simulate} 夹量、{@link #accept} 收下、余量原样还回机器，
     * 与 {@code pullMachineItems} 既有的「先模拟、再抽取、余量还回」完全同一套口径。</p>
     */
    private interface TransitionSink {
        /** 只读：这一份能否交给「负责它下一步」的那台仓；返回可收下的件数（0 = 没有接手方）。 */
        int simulate(ItemStack probe, BlockPos machinePos);

        /** 真正收下（必须紧跟同一次 {@link #simulate} 之后调用）；返回收不下的余量件数。 */
        int accept(ItemStack probe);
    }

    /**
     * {@link TransitionSink} 的唯一实现：在本网络的执行仓里找「下一步归它」的那一台。
     *
     * <h2>归属判据（只保留一份）</h2>
     * 复用执行仓自己的 {@code SequenceExecutionChamberBlockEntity#wantsTransitionNext}（= 按步判定
     * {@code SequenceMaterialGuard}），<b>不在本类里重写第二套</b>；因此「谁接手」与「谁能推给机器」
     * 永远是同一个答案。
     *
     * <h2>为什么排除「接手仓自己的供料目标」</h2>
     * 若这份过渡件此刻就压在接手仓<b>正在供料</b>的那台机器上，那它不是「跨阶段」，而是「本仓这一步还没
     * 干完的活」—— 按用户硬性要求（「任务运行期间下一步仍由同一台机器负责时中间产物绝不收回」）它必须
     * <b>留在机器上</b>由同一台机器连续加工（坚固板第 2、3 步都是冲压，正是靠这一条连做的）。
     * 只有「它出现在<u>别的</u>机器上」才需要换机器，那才是本类要接管的搬运。
     *
     * <h2>零副作用</h2>
     * 仓列表惰性解析（一次 transfer 最多枚举一次网络图），且只在本总线真的碰到过渡件时才解析；
     * 找不到接手方 / 接手仓收不下 → 返回 0，调用方原样走既有的「插入网络」路径，行为逐字不变。
     */
    private static final class NetworkTransitionSink implements TransitionSink {
        /** 最近一次成功交接的接手仓坐标（仅供诊断日志读取；只存坐标，不持有方块实体引用）。 */
        @org.jetbrains.annotations.Nullable
        private static BlockPos lastSinkPos;

        private final java.util.function.Supplier<List<SequenceExecutionChamberBlockEntity>> chambersSource;
        /**
         * <b>调用方（本仓）自己的供料目标坐标</b>：这些工位上的东西正在本仓加工，
         * 本仓的导入侧一律不碰（否则会把正在加工的件自己抢走 → 与后续交接形成无限循环）。
         */
        private final java.util.Set<BlockPos> localSupplyTargets;
        private List<SequenceExecutionChamberBlockEntity> chambers;
        @org.jetbrains.annotations.Nullable
        private SequenceExecutionChamberBlockEntity target;

        NetworkTransitionSink(
            final java.util.function.Supplier<List<SequenceExecutionChamberBlockEntity>> chambersSource,
            final java.util.Set<BlockPos> localSupplyTargets) {
            this.chambersSource = chambersSource;
            this.localSupplyTargets = localSupplyTargets;
        }

        /** 最近一次成功交接的接手仓坐标（诊断用；未发生过返回 {@code null}）。 */
        @org.jetbrains.annotations.Nullable
        static BlockPos lastSinkPos() {
            return lastSinkPos;
        }

        @Override
        public int simulate(final ItemStack probe, final BlockPos machinePos) {
            target = null;
            if (machinePos == null) {
                return 0;
            }
            // <b>2026-10-05 修「同一件料被反复抢走又推回」的无限循环。</b>
            //
            // 实测日志（构建 22:06:26）：
            // <pre>
            // item=create:unprocessed_obsidian_sheet | from=machine@(-10,-60,12)
            //     | event=take_from_machine | to=importer@(-9,-60,12) | reason=ok_handover
            // item=create:unprocessed_obsidian_sheet | event=handover_to_chamber | to=chamber@(-16,-60,10)
            //     | reason=cross_stage_next_step_local
            // （计数器：(-16,-60,12)|unprocessed_obsidian_sheet|collect=15 / return=15 ⇒ 同一件料循环 15 次）
            // </pre>
            // 机制：注液仓的<b>导入总线</b>从<b>它自己的置物台</b>（`-10,-60,12`，注液机正在那里加工第 0 步）
            // 把未加工片抢走，再「交接」给冲压仓；冲压仓把它推到自己的置物台，又被抢走 —— 无限循环，
            // 那件永远完不成任何一步，坚固板一件都产不出来。
            //
            // 根因就在下面这道闸门：它只排除了「接手仓<b>自己</b>的机器」
            // （= 下一步仍由同一台机器负责 ⇒ 留在原处），却<b>没排除「归还仓自己的机器」</b>
            // （= 这台机器是本仓的供料目标，那件正在<b>本仓</b>加工中，绝不该被本仓自己搬走）。
            //
            // 因此新增：<b>凡是「本仓（调用方）的供料目标」，本仓的导入侧一律不碰</b>。
            // 判据由调用方注入（见 {@link #localSupplyTargets}），不在这里猜坐标。
            if (localSupplyTargets != null && localSupplyTargets.contains(machinePos)) {
                return 0; // 本仓自己的供料目标：东西正在本仓加工，绝不搬走
            }
            if (chambers == null) {
                chambers = chambersSource.get();
            }
            for (final SequenceExecutionChamberBlockEntity candidate : chambers) {
                if (!candidate.wantsTransitionNext(probe)) {
                    continue; // 它下一步不归这台仓
                }
                // <b>这里刻意用「物理口径」（selfBusSupplyTargets）而不是链口径</b>：本判据问的是
                // 「这台机器是不是<b>那台接手仓自己</b>在供料」（= 同一个机器连续做多步 ⇒ 留在原处）。
                // 若换成链口径（{@code busSupplyTargets()}），同链另一台仓供料的机器也会被判成
                // 「它自己的机器」，本该<b>直接交接</b>的过渡件就会被压回 RS 网络绕一圈 ——
                // 那正是 2026-10-05 实测「网络里留不住、冲压机永远拿不到料」的成因。
                if (candidate.selfBusSupplyTargets().contains(machinePos)) {
                    return 0; // 就是它自己的机器 → 「下一步仍由同一台机器负责」→ 留在原处，绝不搬走
                }
                final int accepted = candidate.canAcceptTransitionLocally(probe);
                if (accepted > 0) {
                    target = candidate;
                    return accepted;
                }
            }
            return 0;
        }

        @Override
        public int accept(final ItemStack probe) {
            final SequenceExecutionChamberBlockEntity candidate = target;
            target = null;
            if (candidate == null) {
                return probe == null ? 0 : probe.getCount();
            }
            lastSinkPos = candidate.getBlockPos().immutable();
            return candidate.acceptTransitionLocally(probe);
        }
    }

    /**
     * 追踪：<b>「输入总线收取 → 插入网络」</b>这一段（{@code event=insert_network}）。
     *
     * <p><b>为什么要用「插入前后网络聚合存量之差」而不是只用 insert 的返回值</b>（本轮修掉的最严重日志缺陷）：
     * {@code RootStorageImpl#insert} 返回的是 {@code inserted + intercepted} —— RS 的任务
     * （{@code ExternalTaskPattern#trySatisfy}，只对<b>非根</b>样板生效）会<b>在插入瞬间把产出截收进
     * 自己的内部暂存</b>；而某些<b>存储源</b>（例如挂在 Create {@code create:creative_crate} 上的
     * RS 外部存储：{@code BottomlessItemHandler#insertItem} 恒返回 {@code EMPTY}）会「<b>假收下</b>」：
     * 上报成功，却既不进容器、也不进网络聚合存量。两者都表现为 {@code reported > landed}，
     * 因此这里把三者一起打出来：{@code reported}（上报量）、{@code landed}（真实入网增量）、
     * {@code net_after}（插入后网络存量）、{@code not_retained}（上报了却没被网络保住的量）。</p>
     *
     * <p><b>{@code claimed_by_task} 的口径（本轮修正）</b>：只有「非未完成件」才可能是任务产出
     * —— 本模组的样板（{@code SequenceAssemblyPatternItem#buildPattern}）只把 {@code results} 第一项
     * 登记为 {@code output}，过渡件（带 {@code create:sequenced_assembly} 进度组件的未完成件）
     * <b>既不登记为 output 也不登记为 byproduct</b>。因此未完成件被「收下却不保留」时
     * {@code claimed_by_task} 恒为 {@code no} —— 那是存储源吞掉了它，不是任务认领。</p>
     */
    private void traceInsert(final RootStorage storage, final String fromKind, final BlockPos fromPos,
                             final long amount, final long reportedInserted, final long landed,
                             final ItemStack stack) {
        traceInsert(RsccAssemblyDebug.machine("importer", selfPos), storage, fromKind, fromPos,
            amount, reportedInserted, landed, stack);
    }

    /** {@link #traceInsert} 的静态版本（执行舱侧的「回写网络」也走同一套字段口径）。 */
    private static void traceInsert(final String tag, final RootStorage storage, final String fromKind,
                                    final BlockPos fromPos, final long amount,
                                    final long reportedInserted, final long landed,
                                    final ItemStack stack) {
        if (!RsccAssemblyDebug.isEnabled() || stack == null || stack.isEmpty()) {
            return;
        }
        final Item item = stack.getItem();
        final long net = networkItemAmount(storage, item);
        final boolean transition = isTransitionItem(stack);
        // <b>口径修正（本轮）：用「这次插入的网络增量」判，而不是用「插入后的网络总存量」判。</b>
        // 旧写法是 {@code net < reportedInserted}（{@code net} 是该物品在网络里的<b>总存量</b>，
        // 实测一个坚固板能到 190）—— 它几乎恒为 false，于是<b>每一行都写成 claimed_by_task=no</b>，
        // 连 RS 自己已经记完账的任务产出也被误报成「没人认领」（用户拿这份日志取证时被误导）。
        // 物理判据只有一个：上报插入 N、网络总存量只涨了 M &lt; N ⇒ 差额被 RS 任务<b>截收</b>进它的
        // 内部暂存（{@code ExternalTaskPattern#trySatisfy}）。{@code landed} 就是这个增量，
        // 因此 {@code landed < reported} 才等价于「被任务认领」。
        final boolean claimed = !transition && landed < reportedInserted;
        final long notRetained = Math.max(0L, reportedInserted - landed);
        RsccAssemblyDebug.trace("trace-insert:" + tag + "#" + RsccAssemblyDebug.itemId(item),
            RsccAssemblyDebug.traceLine("item=" + RsccAssemblyDebug.itemId(item), amount,
                RsccAssemblyDebug.machine(fromKind, fromPos), "insert_network", "network",
                "reported=" + reportedInserted + " landed=" + landed + " net_after=" + net
                    + (claimed ? " claimed_by_task=yes" : " claimed_by_task=no")
                    + " not_retained=" + notRetained, net));
        if (transition && notRetained > 0 && net < reportedInserted) {
            // 需要玩家干预的异常：未完成件被网络「假收下」（既不在容器也不在网络聚合存量里）。
            // 典型场景：RS 外部存储挂在 Create 创造板条箱上 —— BottomlessItemHandler#insertItem
            // 恒返回 EMPTY（假收下），而 RS 的外部存储缓存按「容器实际内容」重建 → 这一份被凭空销毁，
            // 于是中间产物永远进不了网络 → 下一步的机器永远取不到它。WARN 同处只打一条（不刷屏）。
            RsccAssemblyDebug.warn("voided@" + tag + "#" + RsccAssemblyDebug.itemId(item),
                RsccAssemblyDebug.machine(fromKind, fromPos) + " 网络「假收下」了未完成件 "
                    + RsccAssemblyDebug.itemId(item) + " x" + notRetained
                    + "（reported=" + reportedInserted + " landed=" + landed + " net_after=" + net
                    + "）—— 它既没进容器也没进网络存量。请检查网络里的外部存储："
                    + "挂在 create:creative_crate 上的 refinedstorage:external_storage 会接受任意物品却不存任何东西。");
        }
    }

    /** 只读：该堆叠是不是「未完成件」（带 Create 序列装配进度组件的过渡件）。 */
    public static boolean isTransitionItem(final ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && stack.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null;
    }

    /**
     * 只读：这一份插入<b>真正进了网络聚合存量</b>多少（{@code min(reported, max(0, netAfter - netBefore))}）。
     * <p>{@code reported <= 0} → 0；取不到网络存量（{@code < 0}，例如网络为空）→ 不武断，按 {@code reported} 处理。</p>
     */
    private static long landedAmount(final RootStorage storage, final Item item,
                                     final long netBefore, final long reported) {
        if (reported <= 0) {
            return 0L;
        }
        if (netBefore < 0) {
            return reported;
        }
        final long netAfter = networkItemAmount(storage, item);
        return netAfter < 0 ? reported : Math.min(reported, Math.max(0L, netAfter - netBefore));
    }

    /** {@link #landedAmount} 的流体版（同一口径；数量单位 mB）。 */
    private static long landedAmountFluid(final RootStorage storage, final Fluid fluid,
                                          final long netBefore, final long reported) {
        if (reported <= 0) {
            return 0L;
        }
        if (netBefore < 0) {
            return reported;
        }
        final long netAfter = networkFluidAmount(storage, fluid);
        return netAfter < 0 ? reported : Math.min(reported, Math.max(0L, netAfter - netBefore));
    }

    /** 只读：网络里该物品（按物品种类汇总，不看数据组件）的存量；取不到返回 {@code -1}。 */
    public static long networkItemAmount(@org.jetbrains.annotations.Nullable final RootStorage storage,
                                         @org.jetbrains.annotations.Nullable final Item item) {
        if (storage == null || item == null) {
            return -1L;
        }
        long total = 0L;
        for (final ResourceAmount amount : storage.getAll()) {
            if (amount.resource() instanceof final ItemResource resource && resource.item() == item) {
                total += amount.amount();
            }
        }
        return total;
    }

    /** 只读：网络里该流体（按流体种类汇总，不看数据组件）的存量（mB）；取不到返回 {@code -1}。 */
    public static long networkFluidAmount(@org.jetbrains.annotations.Nullable final RootStorage storage,
                                          @org.jetbrains.annotations.Nullable final Fluid fluid) {
        if (storage == null || fluid == null) {
            return -1L;
        }
        long total = 0L;
        for (final ResourceAmount amount : storage.getAll()) {
            if (amount.resource() instanceof final FluidResource resource && resource.fluid() == fluid) {
                total += amount.amount();
            }
        }
        return total;
    }

    /**
     * 取该坐标的物品能力：<b>先试无侧面（unsided）再逐面尝试</b>。
     * <p>为什么两种都试：Create 的置物台一类的方块对六个面返回同一个 handler，而无侧面查询在部分
     * 实现里可能为 {@code null}；反过来也有只按面暴露的方块。逐个试是「不猜朝向也能收」的最稳做法，
     * 且只在少数几个供料目标上做，开销可忽略。</p>
     * <p><b>public</b>：执行舱的「当前待加工步」探针（{@code SequenceExecutionChamberBlockEntity#pendingStepOnTargets}）
     * 读的是<b>同一批供料目标</b>的同一种能力，共用本方法才不会出现「收回读得到、探针读不到」的两套口径。</p>
     */
    @org.jetbrains.annotations.Nullable
    public static IItemHandler itemHandlerAt(final Level level, final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        final BlockState state = blockEntity.getBlockState();
        final IItemHandler unsided = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state,
            blockEntity, null);
        if (unsided != null) {
            return unsided;
        }
        for (final Direction direction : Direction.values()) {
            final IItemHandler side = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state,
                blockEntity, direction);
            if (side != null) {
                return side;
            }
        }
        return null;
    }

    /**
     * 取该坐标的流体能力（口径与 {@link #itemHandlerAt} 对称）。
     * <p><b>public</b>：执行舱的「流体一次一份」闸门（{@code SequenceExecutionChamberBlockEntity#machineHeldFluid}
     * 与 {@code #holdsInputFluidAt}）读的是<b>同一批供料目标</b>的流体能力，共用本方法才不会出现
     * 「收回读得到、闸门读不到」的两套口径。</p>
     */
    @org.jetbrains.annotations.Nullable
    public static IFluidHandler fluidHandlerAt(final Level level, final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        final BlockState state = blockEntity.getBlockState();
        final IFluidHandler unsided = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, state,
            blockEntity, null);
        if (unsided != null) {
            return unsided;
        }
        for (final Direction direction : Direction.values()) {
            final IFluidHandler side = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, state,
                blockEntity, direction);
            if (side != null) {
                return side;
            }
        }
        return null;
    }
}
