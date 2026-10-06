package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.exporter.ExporterTransferStrategyImpl;
import com.refinedmods.refinedstorage.api.network.node.exporter.ExporterTransferStrategy;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.storage.root.FuzzyRootStorage;
import com.refinedmods.refinedstorage.common.api.upgrade.UpgradeState;
import com.refinedmods.refinedstorage.common.exporter.ExporterTransferQuotaProvider;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.storage.CapabilityCacheImpl;
import com.refinedmods.refinedstorage.neoforge.storage.FluidHandlerInsertableStorage;
import com.refinedmods.refinedstorage.neoforge.storage.ItemHandlerInsertableStorage;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 「总线输出（延长型输出）」模式下输出总线的传输策略 —— <b>取货源是执行舱自身</b>。
 *
 * <p><b>流向语义（本轮修正）</b>：原料先由执行舱从 RS 网络搬进<b>自己的内部存储</b>
 * （见 {@code SequenceExecutionChamberBlockEntity#fillInternalForBus}），本策略再按输出总线勾选的
 * 类别从<b>执行舱内部存储</b>（物品走 {@code RsccChamberItemStorage} 统一视图 = 内部存储 + 磁盘缓存 /
 * 流体 {@code outputTank}）取料，
 * 并<b>照旧从本总线被朝向的那一面推给相邻机器</b>。也就是说：</p>
 * <pre>
 *   RS 网络 ──(执行舱搬运)──▶ 执行舱内部存储 ──(本策略)──▶ 本总线朝向的机器
 * </pre>
 * <p>与原版输出总线的唯一差别就是「抽取源」：不再是 {@code network.getComponent(StorageNetworkComponent)}，
 * 而是执行舱的内部存储。因此必须替换掉 RS 自己构造的 transfer strategy（它写死了从网络抽）。</p>
 *
 * <p><b>不丢不复制</b>：</p>
 * <ul>
 *     <li>物料在任一时刻只存在于<b>一处</b>：要么在网络里、要么在执行舱内部存储里、要么在目标机器里；
 *     执行舱只把物料从网络搬进自己、本策略只把物料从执行舱搬给机器，<b>任何一步都不会反向插进网络</b>；</li>
 *     <li>先 {@code SIMULATE} 目标能否收下，收不下就一点都不动（返回
 *     {@link Result#DESTINATION_DOES_NOT_ACCEPT}）；</li>
 *     <li>正式搬运时从执行舱精确扣除，插不下的余量<b>立即回写执行舱</b>（既不销毁也不回流网络）；</li>
 *     <li>「谁有资格导出」仍由执行舱的轮询归属表决定（只有本轮轮到的总线才会拿到该类别过滤项），
 *     没轮到的总线根本不会调用本策略，因此不存在同一条产出被两台总线同时取走。</li>
 * </ul>
 *
 * <p><b>按步过滤的放宽（2026-10-06，修「压一次以后压不了第二次」）</b>：抽取这一层只按<b>物品</b>
 * 找料，并且<b>原样</b>使用仓内那一格的 {@link ItemStack}（含它自己的
 * {@code create:sequenced_assembly} 组件 —— 绝不另造裸物品、绝不改写步序）。
 * 「这一步该不该推给本仓的机器」由执行舱的 {@code isNextForMyMachines} /
 * {@code busExportAcceptsForPush} 判定，因此「同一台机器连续承担多个步」不再要求
 * 「每个步的类别都被总线勾选」。</p>
 *
 * <p><b>门控</b>：执行舱的「自动合成」未开启（网络里没有进行中的自动合成任务）时一律
 * {@link Result#SKIPPED} —— 与 {@code busExportFilters} 返回空清单是同一道闸门的两道保险。</p>
 *
 * <p><b>脱绑</b>：执行舱被拆掉 / 切回面输出 / 超出线缆范围后，本策略会退化为委托 RS 原版
 * 「网络 → 目标」策略（{@link ExporterTransferStrategyImpl}，与 RS 工厂同一套参数：模糊扩展器 +
 * 堆叠/调节升级配额），因此输出总线不会变成哑巴，也不会残留对执行舱的引用。</p>
 *
 * <p><b>诊断</b>：本策略只<b>加</b>结构化日志（前缀 {@code [rscc-assembly]}，见
 * {@link RsccAssemblyDebug}）—— 只在「每次搬运的结果状态翻转」时各打一条（稳态零输出），
 * 并把推给机器的量累加进 5 秒聚合摘要；不改变任何搬运判定。</p>
 */
public final class RsccChamberExportStrategy implements ExporterTransferStrategy {
    /**
     * 「输入时原料」单次搬运量上限（<b>用户验收标准 #2.1</b>）：即使装了堆叠升级把 RS 的导出配额
     * 放大到 &gt; 1，属于「输入性产物 / 流体输入」类别的资源也一律<b>一次一份</b> ——
     * 单台机械手 / 单台机器才不会被一次塞满而堵塞。非输入类（中间产物）仍按 RS 配额走。
     */
    private static final long INPUT_FEED_UNIT = 1L;

    /**
     * <b>「目的地拒收退避」窗口（tick）</b>。
     *
     * <h2>为什么必须有（2026-10-05 用户实测「置物台一直有取入取出的声音」）</h2>
     * <p>实测日志：</p>
     * <pre>
     * exporter@(-6,-60,6) bus_push golden_sheet -&gt; machine@(-7,-60,6) EXPORTED/ok
     * exporter@(-6,-60,6) bus_skip golden_sheet -&gt; machine@(-7,-60,6) DESTINATION_DOES_NOT_ACCEPT/machine_full
     * exporter@(-6,-60,6) bus_push golden_sheet -&gt; machine@(-7,-60,6) EXPORTED/ok   ← 立刻又推
     * </pre>
     * <p>机器明明以 {@code machine_full} <b>拒收</b>了，总线下一 tick <b>立刻再推</b> ——
     * 无限循环。玩家听到的就是那个「置物台取入取出」的声音，同时两个置物台各压一份
     * （订单只做 1 件却开了 2 件在制件）。</p>
     *
     * <p>旧实现只把拒收<b>记到执行舱上</b>（供看门狗与堵塞自愈用），<b>没有任何退避</b>：
     * 拒绝记录不影响下一次推送。这里补上退避 —— 被同一目标拒收后，本总线在
     * {@value #REFUSAL_BACKOFF_TICKS} tick 内<b>不再推</b>，让下游把手里那件加工完
     * （机器满 = 它正在加工），而不是每 tick 敲一次门。</p>
     */
    private static final long REFUSAL_BACKOFF_TICKS = 40L;

    // ---------- 「推料物品 == 目标机器当前步骤所需物品」的断言拒绝原因（只用于放弃 + 限频日志） ----------
    /**
     * 断言拒绝：这一件<b>不是</b>目标机器当前这一步所需的投入物。
     * <p>用户硬要求：「任何一次推料，必须与该目标机器『当前这一步所需的投入物』完全一致（推错 = 绝不发生）」。
     * 因此不匹配时一律<b>放弃这次推送</b>（物品留在执行舱内部存储里，不丢不复制），
     * 并由 {@link #transfer} 记一条<b>限频</b>日志（首次立即一条、之后每 5 秒合并一条）。</p>
     */
    private static final String ASSERT_NOT_CURRENT_STEP_INPUT = "assert_not_current_step_input";
    /**
     * 断言拒绝：目标机器（机械手的手）此刻被<b>另一件「本步不要的」投入物</b>占着。
     * <p>Create 的手只有一个位置、且对「手里已有不同物品」一律拒收，所以这时候推本步要的那件
     * <b>必然失败</b>：放弃推送（不推错料），并<b>不反复重试</b>（同样走限频日志）。
     * 判据见 {@code SequenceExecutionChamberBlockEntity#blockedByForeignStepExtra}。</p>
     */
    private static final String ASSERT_MACHINE_HOLDS_OTHER_STEP_INPUT = "assert_machine_holds_other_step_input";
    /**
     * 断言拒绝：这一件「步骤专用投入物」的目标机器<b>不是它的消费者</b>（不是机械手）。
     * <p>Create 的 {@code DeployerItemHandler} 只认<b>机械手自己的手位</b>：把这种料推到置物台 /
     * 传输带上，它<b>永远不会被消耗</b>（机械手不会去台面取料）—— 用户实测「齿轮被推到置物台然后
     * 没有消费者、只能手动拿走」正是这条路径。判据见
     * {@code SequenceExecutionChamberBlockEntity#consumesStepExtraAt(BlockPos)}。</p>
     */
    private static final String ASSERT_STEP_EXTRA_NOT_CONSUMER = "assert_step_extra_not_consumer";

    /** 本策略所属的输出总线（用它解析当前绑定的执行舱；服务端权威、逐次解析、不缓存引用）。 */
    private final RsccExporterExecutorMode owner;
    /** 目标机器能力（本总线朝向那一面相邻方块）。 */
    private final CapabilityCacheImpl destination;
    /** 每个过滤项的每批搬运量（含堆叠升级 / 调节升级，口径与 RS 完全一致）。 */
    private final ToLongFunction<ResourceKey> itemQuota;
    private final ToLongFunction<ResourceKey> fluidQuota;
    /** 脱绑时的回退策略（RS 原版「网络 → 目标」行为）。 */
    private final ExporterTransferStrategy itemDelegate;
    private final ExporterTransferStrategy fluidDelegate;

    // ---------- 诊断（只读；不参与任何搬运判定） ----------
    /** 本总线自身坐标（日志用）。 */
    private final BlockPos selfPos;
    /** 目标机器坐标 = 本总线朝向那一面相邻方块（日志用）。 */
    private final BlockPos targetPos;
    /** 日志前缀 {@code exporter@(x,y,z)}。 */
    private final String debugTag;

    /** 构造时传入的服务器世界（退避要读 gameTime）。 */
    private final net.minecraft.server.level.ServerLevel level;

    /** 上一次「本目标拒收」的游戏刻（{@code Long.MIN_VALUE/2} = 从未拒收）。 */
    private long rscc$lastRefusalAt = Long.MIN_VALUE / 2;

    public RsccChamberExportStrategy(final RsccExporterExecutorMode owner,
                                     final ServerLevel level,
                                     final BlockPos selfPos,
                                     final BlockPos targetPos,
                                     final Direction targetFace,
                                     final UpgradeState upgrades) {
        this.owner = owner;
        this.selfPos = selfPos;
        this.targetPos = targetPos;
        this.debugTag = RsccAssemblyDebug.machine("exporter", selfPos);
        this.level = level;
        this.destination = new CapabilityCacheImpl(level, targetPos, targetFace);
        final ItemHandlerInsertableStorage itemDestination = new ItemHandlerInsertableStorage(destination);
        final FluidHandlerInsertableStorage fluidDestination = new FluidHandlerInsertableStorage(destination);
        this.itemQuota = new ExporterTransferQuotaProvider(1, upgrades, itemDestination::getAmount, true);
        this.fluidQuota = new ExporterTransferQuotaProvider(
            Platform.INSTANCE.getBucketAmount(), upgrades, fluidDestination::getAmount, true);
        this.itemDelegate = new ExporterTransferStrategyImpl(
            itemDestination, itemQuota, FuzzyRootStorage.expander());
        this.fluidDelegate = new ExporterTransferStrategyImpl(
            fluidDestination, fluidQuota, FuzzyRootStorage.expander());
        // 绑定成功（安装本策略 = 确实连上了一台「总线输出」执行舱）：只在状态翻转时打一条
        if (RsccAssemblyDebug.isEnabled()) {
            final BlockPos linked = owner.rscc$linkedExecutorPos();
            RsccAssemblyDebug.transition("exporterlink@" + RsccAssemblyDebug.at(selfPos),
                linked == null ? "unlinked" : RsccAssemblyDebug.at(linked),
                debugTag + " linked=" + (linked == null ? "-" : RsccAssemblyDebug.machine("chamber", linked))
                    + " cats=" + owner.rscc$getExportCategoryIds()
                    + " target=" + RsccAssemblyDebug.at(targetPos)
                    + " reason=strategy_installed");
        }
    }

    @Override
    public Result transfer(final ResourceKey resource, final Actor actor, final Network network) {
        // <b>拒收退避闸门</b>：本目标刚拒收过 ⇒ 本轮直接跳过（不解析 chamber、不敲目标的门）。
        // 放在最前面，因此退避期内这条总线<b>完全不参与</b>推送 —— 声音与空转同时消失。
        if (level != null
            && level.getGameTime() - rscc$lastRefusalAt < REFUSAL_BACKOFF_TICKS) {
            return Result.SKIPPED;
        }
        final SequenceExecutionChamberBlockEntity chamber = owner.rscc$getLinkedExecutor();
        if (chamber == null) {
            // 未绑定（拆掉 / 切模式 / 超出线缆范围）：退回原版「网络 → 目标」，不留哑巴总线、不留幽灵引用
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("push@" + RsccAssemblyDebug.at(selfPos) + "#unlinked",
                    "delegated",
                    debugTag + " push {" + describeResource(resource) + "}"
                        + " to=" + RsccAssemblyDebug.at(targetPos)
                        + " result=DELEGATED reason=chamber_unlinked");
            }
            if (resource instanceof ItemResource) {
                return itemDelegate.transfer(resource, actor, network);
            }
            if (resource instanceof FluidResource) {
                return fluidDelegate.transfer(resource, actor, network);
            }
            return Result.SKIPPED;
        }
        if (!chamber.isAutoCraftingEnabled()) {
            // 门控：网络里没有进行中的自动合成任务 → 一律不导出
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("push@" + RsccAssemblyDebug.at(selfPos) + "#gate",
                    "gate_off",
                    debugTag + " push {" + describeResource(resource) + "}"
                        + " to=" + RsccAssemblyDebug.at(targetPos)
                        + " result=SKIPPED reason=autocrafting_gate_off");
            }
            return Result.SKIPPED;
        }
        final TransferOutcome outcome;
        if (resource instanceof final ItemResource item) {
            outcome = transferItem(chamber, item);
        } else if (resource instanceof final FluidResource fluid) {
            outcome = transferFluid(chamber, fluid);
        } else {
            outcome = new TransferOutcome(Result.SKIPPED, 0L, "unsupported_resource");
        }
        // <b>用户第 2/3 条：把「目的地拒收（机器 / 置物台已满、或不接受）」记到执行舱上</b>，
        // 供装配看门狗归类并弹横幅，同时供执行舱的「堵塞自愈」定位是哪台供料目标被占住。
        // 2026-10-05：从「只记时刻」升级为「记时刻 + 目标坐标 + 资源」—— 因为实测的真相是
        // 「置物台被一件废料占住」（齿轮堵塞），而只知道「有东西被拒收」无法把它收回来。
        // <b>「刚推入过渡件」登记（2026-10-05 修「坚固板做不出来」）</b>：
        // 只有真的把过渡件推进了目标，才启动它的「留驻窗口」——
        // 收回侧据此在 {@link RsccChamberImportStrategy#TRANSITION_RESIDENCE_TICKS} 内不把它抢走，
        // 让 Create 的冲压 / 注液有机会把这件加工完。
        //
        // <b>2026-10-06：登记用的步序一律取自「真正推进去的那一格物品」</b>（{@code outcome.pushed()}），
        // 不再由过滤项资源反推（旧写法 {@code resource.toItemStack(1)}）。理由：推料侧的按步过滤已放宽为
        // 「同一台机器可以连续承担多个步」，过滤项资源上的步序<b>未必</b>等于被推那一件的步序；
        // 若按过滤项登记，收回侧的 {@code inTransitionResidence} 会读成「步序已经推进了 ⇒ 这一步做完了」
        // 而把刚推进机器的件<b>立刻抄走</b>——正是用户实测的「压一次就回去」。
        final ItemStack pushedStack = outcome.pushed();
        if (outcome.moved() > 0L && level != null && !pushedStack.isEmpty()
            && chamber.isTransitionItem(pushedStack.getItem())) {
            // 步序取自该件自己的进度组件（-1 = 带不出组件，退回时间兜底）
            final var pushedProgress = pushedStack.get(
                com.simibubi.create.AllDataComponents.SEQUENCED_ASSEMBLY);
            RsccChamberImportStrategy.noteTransitionPushed(targetPos, level.getGameTime(),
                pushedProgress == null ? -1 : pushedProgress.step());
        }
        // <b>在制件登记表（设计文档第 4 步）</b>：任何一次成功的推料都登记「哪个工位压着哪条配方的第几步」。
        // 步序同样取自被推那一件自己的进度组件；带不出组件（原料、以及坚固板 / 列车轨道那种
        // 「无组件的中间产物」）时记 recipe=null / step=-1（表示「开新件或无法定步」）。
        if (outcome.moved() > 0L && level != null && !pushedStack.isEmpty()) {
            final var progress = pushedStack.get(
                com.simibubi.create.AllDataComponents.SEQUENCED_ASSEMBLY);
            chamber.rscc$noteUnitPushed(
                targetPos,
                progress == null ? null : progress.id().toString(),
                progress == null ? -1 : progress.step(),
                pushedStack.getItem());
        }
        if (outcome.result() == Result.DESTINATION_DOES_NOT_ACCEPT && outcome.moved() <= 0L) {
            chamber.rscc$noteDestinationRefusal(targetPos,
                resource instanceof final ItemResource itemResource ? itemResource.item() : null);
            // <b>退避</b>：先记下「本目标刚拒收」，接下来的 REFUSAL_BACKOFF_TICKS 内不再推 ——
            // 否则机器满（= 正在加工）时会每 tick 敲一次门，形成无休止的推 / 拒循环。
            if (level != null) {
                rscc$lastRefusalAt = level.getGameTime();
            }
        } else if (outcome.moved() > 0L) {
            // <b>成功投料 ⇒ 立即清掉该目标的拒收记录</b>（2026-10-05 用户实测：
            // 「齿轮只堵一个的时候另外一个也停工了」）。
            // 旧实现只有全仓一个拒收时间戳、且仅在任务开始时清一次，于是一台机器堵住会把
            // 整仓压 60 tick（不备料 / 不投料），另一台明明能收却一起停。
            // 现在只要别处真的把东西推进去了，就证明「下游没有整体堵住」→ 撤掉那 60 tick 的压制。
            chamber.rscc$notePushSucceeded(targetPos);
        }
        if (RsccAssemblyDebug.isEnabled()) {
            // 追踪（「输出总线判定」这一段）：本总线对这一份的最终判定（推出去 / 没推 + 具体原因），
            // 同一（总线 + 资源 + 结果）首次立即一条、之后每 5 秒一条 —— 于是「为什么没推出去」
            // （机器满 / 仓里没有 / 不是本步要的 / 手里被别件占着）在 trace 族里也能直接读到。
            RsccAssemblyDebug.trace("trace-bus@" + RsccAssemblyDebug.at(selfPos)
                    + "#" + describeResource(resource) + "#" + outcome.result().name()
                    + "/" + outcome.detail(),
                RsccAssemblyDebug.traceLine(describeResource(resource), outcome.moved(),
                    RsccAssemblyDebug.machine("exporter", selfPos),
                    outcome.moved() > 0 ? "bus_push" : "bus_skip",
                    RsccAssemblyDebug.machine("machine", targetPos),
                    outcome.result().name() + "/" + outcome.detail(), -1L));
            // <b>只有真正推动了东西（数量 &gt; 0）才打搬运日志</b>：{@code x0}（机器满 / 仓里没这种料）
            // 一律不再打（用户要求，实测日志里 `push {... x0} ... machine_full / chamber_empty` 是噪声）。
            // 「短交（推了一部分、余量退回机器 = ok_leftover_returned）」与「被拒收的量」都是
            // moved &gt; 0 的那一条正文里的字段，因此「有意义的失败」没有丢。
            final String state = outcome.moved() > 0
                ? "/moved=" + outcome.detail()
                : "/idle";
            if (outcome.moved() > 0
                && RsccAssemblyDebug.changed("push@" + RsccAssemblyDebug.at(selfPos), state)) {
                RsccAssemblyDebug.event(debugTag
                    + " push {" + describeResource(resource) + " x" + outcome.moved() + "}"
                    + " to=" + RsccAssemblyDebug.at(targetPos)
                    + " result=" + outcome.result().name()
                    + " reason=" + outcome.detail());
            }
            RsccAssemblyDebug.countFeed(outcome.moved());
            // <b>断言级（错配）拒绝的限频日志</b>：上面那条「只有 moved > 0 才打」的闸门会把
            // 「本该推、却因为目标机器此刻不需要这一件」这种<b>最有诊断价值</b>的拒绝一起吞掉
            // （用户实测「手里拿着大齿轮直接卡住」时日志里一条都没有）。因此这里单开一条通道，
            // 走 {@link RsccAssemblyDebug#reject}：同一（总线 + 件 + 因由）首次立即一条、之后每 5 秒
            // 合并一条 {@code (same cause repeated n times in 5s)} —— 既不刷屏，也绝不静默。
            if (outcome.moved() <= 0 && isAssertionRefusal(outcome.detail())) {
                RsccAssemblyDebug.reject(
                    debugTag + "#" + outcome.detail() + "#" + describeResource(resource),
                    debugTag + " push {" + describeResource(resource) + "}"
                        + " to=" + RsccAssemblyDebug.at(targetPos)
                        + " result=SKIPPED reason=" + outcome.detail()
                        + " (推料物品 != 该机器当前这一步所需的投入物)");
            }
        }
        return outcome.result();
    }

    /** 该原因串是不是「断言级错配拒绝」（只有这两种才配一条限频日志，其余 x0 噪声照旧不打）。 */
    private static boolean isAssertionRefusal(final String detail) {
        return ASSERT_NOT_CURRENT_STEP_INPUT.equals(detail)
            || ASSERT_MACHINE_HOLDS_OTHER_STEP_INPUT.equals(detail)
            || ASSERT_STEP_EXTRA_NOT_CONSUMER.equals(detail);
    }

    /** 资源 → {@code item=create:iron_sheet} / {@code fluid=minecraft:water}。 */
    private static String describeResource(final ResourceKey resource) {
        if (resource instanceof final ItemResource item) {
            return "item=" + RsccAssemblyDebug.itemId(item.item());
        }
        if (resource instanceof final FluidResource fluid) {
            return "fluid=" + RsccAssemblyDebug.fluidId(fluid.fluid());
        }
        return "id=" + resource;
    }

    /**
     * 一次搬运的结果 + 实际搬走的量 + 细分原因（只用于诊断，不参与判定）
     * + <b>真正被推进去的那一件</b>（{@link ItemStack#EMPTY} = 什么都没推 / 流体）。
     *
     * <p>为什么必须把它带回来：过滤项资源上的步序（{@code item}）在按步过滤放宽之后
     * <b>未必</b>等于被推那一件的步序，而推料后的两处登记（过渡件留驻窗口 / 在制件登记表）必须用
     * <b>物品自己的组件</b>；否则收回侧会按错误的步序认为「这一步已经做完了」而把件立刻抄走。</p>
     */
    private record TransferOutcome(Result result, long moved, String detail, ItemStack pushed) {
        /** 没有可登记的物品（流体 / 什么都没推动）时的简写。 */
        TransferOutcome(final Result result, final long moved, final String detail) {
            this(result, moved, detail, ItemStack.EMPTY);
        }
    }

    /**
     * 计划抽取的一格：<b>槽位 + 那一格的原样堆叠</b>（含它自己的数据组件）。
     *
     * <p>记槽位是刻意的：抽取时就认这一格，于是推上去的东西<b>必然是仓内那一格的原物</b>
     * （含它自身的 {@code create:sequenced_assembly} 步序），既不会跨槽拼出一个「组件取自 A、数量含 B」
     * 的杂种堆叠，也不会因为过滤项 / 仓内步序不同而抽不出来。</p>
     */
    private record SlotPick(int slot, ItemStack stack) {
    }

    // ==================== 物品：执行舱内部存储 → 本总线朝向的机器 ====================

    private TransferOutcome transferItem(final SequenceExecutionChamberBlockEntity chamber,
                                         final ItemResource item) {
        final IItemHandler target = destination.getItemHandler().orElse(null);
        if (target == null) {
            return new TransferOutcome(Result.DESTINATION_DOES_NOT_ACCEPT, 0L, "no_target_container");
        }
        // 「一次一份」的末端落点（输入类原料）：目标机器<b>此刻还压着同种料</b>时，一份都不补。
        // 为什么必须在搬运这一层再判一次（而不是只靠执行舱的类别级节流）：类别级节流是
        // 「重建导出清单那一刻」的快照（见 SequenceExecutionChamberBlockEntity#busExportFilters），
        // 单归属类别在稳态下清单根本不重建（{@code busRoundRobinActive} 为假 → 不每 tick 重推），
        // 于是那份快照会一直放行，在清单重建之前把机器塞满。用户实测后果：机械手手里被堆到 36 个齿轮
        // （「一次装配只该消耗一个齿轮，为什么输了 64 个」），而 Create 的
        // {@code DeployerItemHandler#insertItem} 对「手里已有不同物品」一律拒收 ⇒ 下一步骤的
        // 大齿轮 / 铁粒永远进不去 ⇒ 装配永久卡在首次装配之后（进度 1/15、step=1 冻住）。
        // 判据用「机器此刻有没有这件料」而不是「我们发过几次」：机器一消耗掉就自然恢复供料，
        // 与面输出认领侧的 holdsItem 完全同一口径（同一份判定，两个执行点都只是它的投影）。
        if (chamber.isInputMaterial(item.item())) {
            // <b>「步骤专用投入物」只能推给它的真正消费者（本轮新增，用户第 2 条：齿轮被推到置物台
            // 然后没有消费者）</b>：这种料只由机械手（Create 的 deploying 步）从<b>自己的手位</b>应用到
            // 工位上；推到置物台 / 传输带上它永远不会被消耗（机械手不会去台面取料），只会永久躺在那儿等
            // 卡住闸门。旧实现只看「目标机器此刻这一步要不要它」——而台面上的在制件让它同样回答「要」，
            // 于是这条路径漏判。判据只认机械手方块（{@code consumesStepExtraAt}），与收回侧的保护判定互补：
            // 主原料 / 过渡件 / 流体输入不适用本闸门（{@code isStepExtraInputItem} 为假时直接跳过）。
            if (chamber.isStepExtraInputItem(item.item()) && !chamber.consumesStepExtraAt(targetPos)) {
                return new TransferOutcome(Result.SKIPPED, 0L, ASSERT_STEP_EXTRA_NOT_CONSUMER);
            }
            if (holdsSameItem(target, item.item())) {
                return new TransferOutcome(Result.SKIPPED, 0L, "machine_holds_input");
            }
            // 步骤专用投入物（机械手手里那件「此刻唯一该拿的东西」）：只有当前待加工步要的那一件放行。
            // 为什么：多步共用一个机械手时（未完成精密构件第 0/1/2 步分别要齿轮 / 大齿轮 / 铁粒），
            // 三件东西是三个独立类别、彼此没有顺序信息，若都推过去，Create 会因「手里已有不同物品」
            // 拒收后续那件 → 装配永久停在第 1 步。判据唯一实现在执行舱（见 inputMaterialWantedNow），
            // 按「本总线面对的那一台机器」判（用户硬要求：不是「总体只出一份」）：一条仓常常同时
            // 给多台机器供料，各机器所处的步骤并不相同；用全仓最小步会把靠后那台需要的投入物一并拦下。
            //
            // <b>这是一道断言级校验（用户硬要求：任何一次推料，必须与该目标机器「当前这一步所需的
            // 投入物」完全一致，推错 = 绝不发生）</b>：不匹配就<b>放弃这次推送</b>（不推出去），
            // 并由 transfer(...) 记一条限频日志（见 ASSERT_REFUSAL_*）。判不出「此刻要什么」时也一律
            // 不推（执行舱侧已改成严格口径，见 SequenceExecutionChamberBlockEntity#inputMaterialWantedNow）：
            // 塞错一件进机械手手里会被 Create 永久拒收（DeployerItemHandler 拒收不同物品）→ 整条线卡死，
            // 比「这一 tick 不推」坏得多；而判据一旦可判（置物台拿到料）立刻自愈。
            if (!chamber.inputMaterialWantedNow(targetPos, item.item())) {
                return new TransferOutcome(Result.SKIPPED, 0L, ASSERT_NOT_CURRENT_STEP_INPUT);
            }
            // <b>工位级「已有同类件」闸门（本轮新增，用户第 ① ② 条）</b>：上面的
            // {@code holdsSameItem(target, ...)} 只看本总线朝向的那一格，而「一台机器同一时刻最多一份
            // 未消耗的投入物」这条不变式是<b>按工位</b>（置物台 + 对着它的机械手 = 同一工位）成立的
            // （备料侧早就用 supplyTargetHoldsItem 这么判，推料侧此前漏了）。一个工位由 2 条及以上
            // 总线 / 2 台机械手组成时，第 2 条总线只看自己那一格 ⇒ 会再推一份给同一工位 —— 多出来的
            // 那一份没有消费者，又因「本步要它」在收回侧受保护，只能躺在工位上等卡住闸门
            //（= 用户实测「齿轮卡在工位上、只能手动拿走」与「中间产物多出一份」的共同来源）。
            // 判据唯一实现在执行舱（见 SequenceExecutionChamberBlockEntity#stationTwinHoldsStepExtra），
            // 只对「步骤专用投入物」表态，且只看同一工位的兄弟目标，绝不因此拦主原料 / 流体输入。
            if (chamber.stationTwinHoldsStepExtra(targetPos, item.item())) {
                return new TransferOutcome(Result.SKIPPED, 0L, "station_twin_holds_input");
            }
            // 「手里/机器里压着一件『本步不要的』投入物」→ 放弃这次推送（用户硬要求：不推错料
            // <b>且不反复重试</b>）。Create 的手只有一个位置：手里拿着别的东西时，本步要的那件必然进不去，
            // 每 tick 试一次只是白烧；等收回侧把占位的那件抄走（RsccChamberImportStrategy 的例外 ①）
            // 或步序轮到它，本闸门立刻放行。判据唯一实现在执行舱，与非机械手工况无关（那里没有这类件）。
            if (chamber.blockedByForeignStepExtra(targetPos, item.item())) {
                return new TransferOutcome(Result.SKIPPED, 0L, ASSERT_MACHINE_HOLDS_OTHER_STEP_INPUT);
            }
        }
        // 「机器还在加工这一步的件时不再开新件」（<b>只压在起步原料上</b>，见 #isStartIngredient 的说明）：
        // 起步原料一喂进去就等于「开一件新在制件」，而机器上一次只能加工一件。原先只比对「机器有没有
        // 同种料」（上面的 holdsSameItem），对「机器上压着的已经是过渡件」的情形看不见 ——
        // 实测里注液仓于是每约 1 秒补一份黑曜石粉（+500 mB 岩浆），而整条线每件要 4 秒以上，
        // 网络里的粉掉得比板材产出快约三倍（用户：「三份粉才出一份板」）。
        // 其余输入（注液步的岩浆、机械手那件投入物）不加这道闸门：它们只把「已经开好的那一件」往前推，
        // 拦下来会把正在加工的那一件直接饿死。判据只按步骤 / 身份，不做方块坐标比较。
        //
        // <b>本轮修正（用户「定量法」实测：下单 N 个坚固板只该消耗 N 份黑曜石粉，实际多耗 1 份）</b>：
        // 旧判据用的是 {@code stepUnitInFlightOn}，它要求「这份在制件的下一步<b>正由本仓负责</b>」——
        // 对「注液机刚把粉变成未完成黑曜石板、下一步（冲压）归<b>别的</b>仓」的跨仓产线看不见，
        // 于是同一件在制品被喂了<b>两份</b>起步原料。改用 {@code unitInFlightAt}（按工位判「有没有在制件」，
        // 与 Create 的「一台机器一次只加工一件」同一条不变式），备料侧（{@code stepExtraStockTarget}
        // / {@code wantingTargetCount}）用同一个判据同步收紧，两侧只保留一份判定。
        if (chamber.isStartIngredient(item.item()) && chamber.unitInFlightAt(targetPos)) {
            return new TransferOutcome(Result.SKIPPED, 0L, "machine_busy_with_my_step");
        }
        // <b>2026-10-05 修「坚固板卡死」（用户实测 + 日志证据）。</b>
        //
        // 日志：{@code chamber@(-16,-60,10) push_stalled reason=DESTINATION_REFUSED
        // resource=create:unprocessed_obsidian_sheet}，而同一台的
        // {@code intermediate:...sturdy_sheet:1/:2} 与 {@code intermediate:...track:2} 同时挂在那台冲压机上。
        //
        // 机制：Create 的冲压机<b>只有一个加工位</b>。那台冲压机此刻手里压着
        // {@code create:incomplete_track}（列车轨道第 2 步的在制件），于是坚固板的
        // {@code create:unprocessed_obsidian_sheet} <b>永远进不去</b>（{@code machine_full}）——
        // 用户看到的就是「推了一个到冲压、冲压了一下、然后就没别的了」，
        // 而网络里始终没有走完两步的坚固板。
        //
        // 上面那道闸门只压在「起步原料」上（它的语义是「不要多开一件」）。
        // 但<b>过渡件（中间产物）同样只有一个加工位</b>，机器里已经压着<b>另一件</b>过渡件时再推，
        // 结果必然是 machine_full（推不进去还每 tick 白试），甚至更糟：把那件在制件挤掉。
        //
        // 因此新增「工位已被别的在制件占住 ⇒ 不推过渡件」这道闸门：
        //   * 只对<b>过渡件（未完成件）</b>表态；
        //   * 只在「工位上确有在制件」且「那件不是本件」时拦；
        //   * 判据复用 {@link SequenceExecutionChamberBlockEntity#unitInFlightAt}（与起步原料那道同源），
        //     不写第二套「在制件」判定。
        // 机器把手里那件加工完、推走之后，本闸门立刻自愈 ⇒ 不会断供、不会死锁。
        if (chamber.isTransitionItem(item.item()) && chamber.unitInFlightAt(targetPos)) {
            return new TransferOutcome(Result.SKIPPED, 0L, "machine_busy_with_other_transition");
        }
        // 配额：RS 自己按堆叠 / 调节升级算出来的每批搬运量；<b>输入类原料再夹到 1 件</b>
        //（用户验收标准 #2.1：单台机械手 / 单台机器不能被一次塞满，故「输入时原料」一次一份）
        final long quota = Math.min(itemQuota.applyAsLong(item),
            chamber.isInputMaterial(item.item()) ? INPUT_FEED_UNIT : Long.MAX_VALUE);
        if (quota <= 0) {
            return new TransferOutcome(Result.SKIPPED, 0L, "no_quota");
        }
        // 计划：从执行舱物品存储（内部存储 + 磁盘缓存，见 RsccChamberItemStorage）里按「同物品」挑堆叠
        //（忽略数据组件，与强制模糊模式同一口径），总量 ≤ 配额。
        // <b>2026-10-06：不再要求「物品 + 组件完全相同」</b> —— 过渡件的组件里装着它的步序，而同一个过渡件
        // 会在多个步之间流转（坚固板第 1/2 步都是冲压）；按组件精确匹配会让「件在第 s 步」只有
        // 「第 s 步」那一个过滤项能把它取出来。步序该不该推由舱内判定（见下面的 busExportAcceptsForPush），
        // 抽取与推送一律<b>原样使用仓内那一格</b>。
        final List<SlotPick> picks = new ArrayList<>(2);
        long plannedTotal = 0;
        final IItemHandler chamberStore = chamber.itemStorage();
        for (int slot = 0; slot < chamberStore.getSlots() && plannedTotal < quota; slot++) {
            final ItemStack inSlot = chamberStore.getStackInSlot(slot);
            if (inSlot.isEmpty() || !inSlot.is(item.item())) {
                continue;
            }
            // 「按步骤」过滤（本轮新增，与输入总线的收回判定严格互补）：本仓不该喂给机器的<b>过渡件</b>
            // 一律不推 —— 例如被收集面临时收进内部存储、却属于「下一步骤」的那一份。
            // 推过去机器也加工不了，只会被输入总线原样收回网络，形成「推出 → 收回」空转
            // （用户实测：网络岩浆每秒掉 500、中间产物在网络与机器之间来回跑）。
            // 非过渡件（配方原料 / 成品 / 废料）没有进度步可言，一律放行，行为与既有完全一致。
            //
            // <b>2026-10-06：本判据的步序来源已是链级</b>（{@code SequenceExecutionChamberBlockEntity
            // #chainOwnedSteps()} = 本仓 ∪ 本链各台定义的步）。为什么必须这样：这条总线的类别清单本来就是
            // 链级的（{@code busExportFilters}），于是「清单里勾得上、这台仓的样板却没定义那一步」会被判
            // {@code NOT_MINE} ⇒ 用户实测的「勾得上、推不动」。放宽的只有「这条链合起来能不能做」这一问：
            // 属主判定（{@code ownedSteps()}）与<b>求值所在的仓</b>都没变 —— 判据仍在这条总线绑定的这台仓上、
            // 用它自己的配方总步数求值，因此与输入总线收回侧（{@code isTransitionReclaimAllowed}）严格互补。
            if (!chamber.isNextForMyMachines(inSlot)) {
                continue;
            }
            // 「按步」放行判据（<b>本轮放宽</b>，判定点见 SequenceExecutionChamberBlockEntity
            // #busExportAcceptsForPush）：过渡件只要求「物品相同 + 本仓的机器确实还要它」，
            // <b>不再</b>要求过滤项原型上的步序与它逐位相同。否则「同一台机器连续承担多个步」时，
            // 必须把每个步的类别都勾上才推得动 —— 少勾一个，压完这一步的件就再也推不出去
            //（用户实测：「冲压一次，然后就回去了」）。非中间产物过滤项（原料 / 流体）行为逐字不变。
            if (!chamber.busExportAcceptsForPush(inSlot, item)) {
                continue;
            }
            final int want = (int) Math.min(quota - plannedTotal, inSlot.getCount());
            // 原样记住「哪一格、那一格的东西」（含它自己的组件）：推送时用它，绝不另造裸物品。
            picks.add(new SlotPick(slot, inSlot.copyWithCount(want)));
            plannedTotal += want;
        }
        if (picks.isEmpty()) {
            // 执行舱内部存储里没有这一类物料（本机无从导出）
            return new TransferOutcome(Result.RESOURCE_MISSING, 0L, "chamber_empty");
        }
        // 先模拟目标能收下多少：收不下就一点都不动
        long acceptable = 0;
        for (final SlotPick planned : picks) {
            final ItemStack pick = planned.stack();
            final int want = pick.getCount();
            final ItemStack remainder = ItemHandlerHelper.insertItem(target, pick, true);
            acceptable += want - remainder.getCount();
        }
        if (acceptable <= 0) {
            return new TransferOutcome(Result.DESTINATION_DOES_NOT_ACCEPT, 0L, "machine_full");
        }
        long moved = 0;
        long budget = acceptable;
        long leftoverBack = 0;
        ItemStack pushedSample = ItemStack.EMPTY;
        for (final SlotPick pick : picks) {
            if (budget <= 0) {
                break;
            }
            final int take = (int) Math.min(pick.stack().getCount(), budget);
            // 从「刚才那一格」原样取出（每份都带它自己那一格的组件）；槽位漂移时退回按「同物品」扫描。
            for (final ItemStack extracted : extractPick(chamberStore, pick, take)) {
                if (extracted.isEmpty() || budget <= 0) {
                    continue;
                }
                if (pushedSample.isEmpty()) {
                    // 记账样本 = 仓内那一格的原物（含它自己的步序组件）：推料后的两处登记拿它读步序，
                    // 绝不拿过滤项资源反推（那可能是另一个步序 —— 见 transfer(...) 里的说明）。
                    pushedSample = extracted.copy();
                }
                final int extractedCount = extracted.getCount();
                final ItemStack remainder = ItemHandlerHelper.insertItem(target, extracted, false);
                final int inserted = extractedCount - remainder.getCount();
                moved += inserted;
                budget -= inserted;
                if (!remainder.isEmpty()) {
                    // 余量立即回写执行舱物品存储（内部优先、满了进磁盘）：绝不销毁，也绝不回流 RS 网络
                    chamberStore.insertItem(0, remainder, false);
                    leftoverBack += remainder.getCount();
                }
            }
        }
        if (moved > 0) {
            chamber.setChanged();
            // 守恒账本（只读诊断）：本仓「推给机器」的那一份 —— 与备料侧 fromNetwork 相对，
            // 于是「离开网络 = 留在本产线（舱内 + 机器）＋还回网络」这条账可以被逐笔核对。
            chamber.recordFlowToMachine(item, moved);
            return new TransferOutcome(Result.EXPORTED, moved,
                leftoverBack > 0 ? "ok_leftover_returned" : "ok", pushedSample);
        }
        return new TransferOutcome(Result.DESTINATION_DOES_NOT_ACCEPT, 0L, "machine_full");
    }

    /**
     * 目标机器此刻是否已经压着<b>同一种物品</b>（同物品即算占用，与「模糊模式」同一口径，不看数据组件）。
     *
     * <p>只读。用于「输入类原料一次一份」的末端判定：机器没消耗掉上一份之前不再补第二份。
     * 与 {@code SequenceExecutionChamberBlockEntity#holdsItem} 是同一套语义（那边读的是本仓 INPUT 面
     * 相邻的容器，这边读的是本总线朝向的机器），因此面输出与总线输出两种模式下供料节流口径一致。</p>
     */
    private static boolean holdsSameItem(final IItemHandler handler, final Item item) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && inSlot.is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code pick} 指的那一格原样取出（<b>含该格自身的组件</b>）；该格已被搬空 / 槽位漂移时
     * 退回按「同物品」全仓扫描（见 {@link #extractFromChamber}）。
     *
     * <p>返回的每一份都是仓内真实存在过的堆叠 —— 绝不构造裸物品、绝不改写步序。</p>
     */
    private static List<ItemStack> extractPick(final IItemHandler chamberStore,
                                               final SlotPick pick,
                                               final int amount) {
        final ItemStack inSlot = chamberStore.getStackInSlot(pick.slot());
        if (!inSlot.isEmpty() && inSlot.is(pick.stack().getItem())) {
            final ItemStack part = chamberStore.extractItem(
                pick.slot(), Math.min(amount, inSlot.getCount()), false);
            if (!part.isEmpty()) {
                return List.of(part);
            }
        }
        return extractFromChamber(chamberStore, pick.stack(), amount);
    }

    /**
     * 从执行舱物品存储（内部存储 + 磁盘缓存）里取出「<b>与 prototype 同物品</b>」的 {@code amount} 个，
     * 返回<b>逐格原样取出</b>的堆叠清单（每份都带它自己那一格的组件；跨槽<b>不合并</b>，由调用方逐份插入）。
     *
     * <p><b>2026-10-06（本次修复点）</b>：旧实现用 {@link ItemStack#isSameItemSameComponents} 匹配，
     * 于是「同一个过渡件、不同步序」的两份在存储里被当成两种东西：只要原型上的步序与仓内那一格的步序不同，
     * 就一件都取不出来。步序过滤的职责属于只读判定（{@code busExportAcceptsForPush} /
     * {@code isNextForMyMachines}），抽取这一层只按<b>物品</b>找料；抽出来的东西原样推上去，
     * 因此推给机器的那一件永远带着它自己的进度组件。</p>
     */
    private static List<ItemStack> extractFromChamber(final IItemHandler chamberStore,
                                                      final ItemStack prototype,
                                                      final int amount) {
        int remaining = amount;
        final List<ItemStack> taken = new ArrayList<>(2);
        for (int slot = 0; slot < chamberStore.getSlots() && remaining > 0; slot++) {
            final ItemStack inSlot = chamberStore.getStackInSlot(slot);
            if (inSlot.isEmpty() || !inSlot.is(prototype.getItem())) {
                continue;
            }
            final ItemStack part = chamberStore.extractItem(
                slot, Math.min(remaining, inSlot.getCount()), false);
            if (part.isEmpty()) {
                continue;
            }
            remaining -= part.getCount();
            taken.add(part);
        }
        return taken;
    }

    // ==================== 流体：执行舱内部存储 → 本总线朝向的机器 ====================

    private TransferOutcome transferFluid(final SequenceExecutionChamberBlockEntity chamber,
                                          final FluidResource fluidResource) {
        final IFluidHandler target = destination.getFluidHandler().orElse(null);
        if (target == null) {
            return new TransferOutcome(Result.DESTINATION_DOES_NOT_ACCEPT, 0L, "no_target_container");
        }
        // 配额：流体输入类别同样夹到「一份 = 1 桶」，理由同物品侧的 #2.1（一次一份不堵塞机器）
        final long quota = Math.min(fluidQuota.applyAsLong(fluidResource),
            chamber.isInputFluid(fluidResource.fluid())
                ? Platform.INSTANCE.getBucketAmount() : Long.MAX_VALUE);
        if (quota <= 0) {
            return new TransferOutcome(Result.SKIPPED, 0L, "no_quota");
        }
        final FluidStack inTank = firstTankMatching(chamber, fluidResource);
        if (inTank.isEmpty()) {
            // 执行舱内部流体存储里没有这一类流体
            return new TransferOutcome(Result.RESOURCE_MISSING, 0L, "chamber_empty");
        }
        final FluidStack probe = inTank.copyWithAmount((int) Math.min(quota, inTank.getAmount()));
        final int acceptable = target.fill(probe, IFluidHandler.FluidAction.SIMULATE);
        if (acceptable <= 0) {
            return new TransferOutcome(Result.DESTINATION_DOES_NOT_ACCEPT, 0L, "machine_full");
        }
        final FluidStack drained = chamber.outputTank.drain(
            inTank.copyWithAmount(Math.min(acceptable, inTank.getAmount())),
            IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            return new TransferOutcome(Result.RESOURCE_MISSING, 0L, "chamber_empty");
        }
        final int filled = target.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            // 余量立即回写执行舱内部流体存储：绝不销毁，也绝不回流 RS 网络
            chamber.outputTank.fill(drained.copyWithAmount(drained.getAmount() - filled),
                IFluidHandler.FluidAction.EXECUTE);
        }
        if (filled > 0) {
            chamber.setChanged();
            // 守恒账本（只读诊断）：流体侧与物品侧同一口径（单位 mB）
            chamber.recordFlowToMachine(fluidResource, filled);
            return new TransferOutcome(Result.EXPORTED, filled,
                filled < drained.getAmount() ? "ok_leftover_returned" : "ok");
        }
        return new TransferOutcome(Result.DESTINATION_DOES_NOT_ACCEPT, 0L, "machine_full");
    }

    /** 执行舱内部流体存储里第一个「同种流体」的罐（按流体种类匹配，忽略数据组件）。 */
    private static FluidStack firstTankMatching(final SequenceExecutionChamberBlockEntity chamber,
                                                final FluidResource resource) {
        for (final FluidStack stack : chamber.outputTank.getTanksSnapshot()) {
            if (stack.getFluid() == resource.fluid()) {
                return stack;
            }
        }
        return FluidStack.EMPTY;
    }
}
