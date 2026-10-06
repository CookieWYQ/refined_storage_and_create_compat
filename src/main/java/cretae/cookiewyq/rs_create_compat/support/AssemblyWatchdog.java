package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.TrackedResourceAmount;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.autocrafting.monitor.AbstractAutocraftingMonitorContainerMenu;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import cretae.cookiewyq.rs_create_compat.network.SequenceExecutionChamberNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket;
import cretae.cookiewyq.rs_create_compat.report.CompatCompletionSender;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「自动合成任务卡住（停滞 / 掉线 / 缺料 / 无进展）」的观察者 + 挂起 / 恢复控制器（服务端权威）。
 *
 * <p><b>职责</b>：只读地盯住<b>网络里所有自动合成任务</b>（本模组的「序列装配」任务与 RS 原版任务
 * 共用同一套语义），把三类卡住原因记进任务记录并按需弹一条<b>纯展示</b>横幅：</p>
 * <ol>
 *     <li><b>执行器 / 机器掉线</b>：序列装配看「样板里指派的执行仓坐标是否还在网络里」；
 *     RS 原版任务看 RS 自己给出的 {@code TaskStatus.Item#type}（REJECTED / LOCKED / NONE_FOUND）；</li>
 *     <li><b>缺原料</b>：网络里既没有该资源、也没有别的任务在产出它；</li>
 *     <li><b>无进展</b>：完成度与各项计数连续很久没有任何变化（兜底判据）。</li>
 * </ol>
 *
 * <p><b>挂起与恢复（服务端权威）</b>：判定成立后任务进入 {@link SuspendState#SUSPENDED} —— 由
 * {@code mixin/TaskContainerMixin} 让 RS 不再 step 这条任务，于是它<b>不再抽取网络原料、不再向
 * 机器投料、不再占用那台执行器的推进</b>；它已经抽出的中间产物原地冻结在任务自己的
 * {@code internalStorage} 里（<b>不复制、不销毁、不被别人抢走</b>）。</p>
 *
 * <p><b>恢复的唯一入口是玩家点「继续」</b>（监视器里的按钮，走
 * {@link cretae.cookiewyq.rs_create_compat.network.AssemblyTaskActionPacket} → 服务端 {@link #resume}）：
 * 挂起后<b>一律不自动恢复</b> —— 扫描把原因判回正常（机器重新上线 / 原料补足）也<b>不会</b>解挂。
 * <b>为什么这么定（用户原话）</b>：玩家拆机器常常不是失误而是<b>故意</b>的 —— 这时往往要顺手
 * <b>增加机器数量 / 增加输出总线数量并重新配置</b>，所以「必须要点击继续才能继续任务」；
 * 若一检测到（连上）就自动继续，玩家还没来得及改配置，任务就带着旧配置又跑起来了。
 * 挂起超上限后按配置决定「继续挂起」或「安全回收」（调 RS 自己的取消路径，物品原样还回网络）。</p>
 *
 * <p><b>为什么是只读观察者</b>：执行舱（{@code SequenceExecutionBlockEntity}）与 RS 的任务对象正被
 * 并行任务改动，本类<b>绝不改</b>它们的内部数据，只调用公开只读 API；「挂起」也只做「跳过 step」这一件事。</p>
 *
 * <p><b>绝不改执行舱</b>：{@code SequenceExecutionChamberBlockEntity} 正被并行任务改动，本类只调用
 * 它的公开只读 API（{@code getNetworkForItem} / {@code getChamberDisplayName} / {@code getRecipeType}
 * / {@code outputStorage} / {@code outputTank}）与 RS 网络图、样板库公开 API。</p>
 *
 * <p><b>记录结构</b>：每条任务一条 {@link Record}，字段含任务 uuid、记录时的世界 tick、当前 tick、卡住原因、
 * 挂起状态机，外加一份用于横幅 / 监视器的只读快照。任务 uuid 直接取精致存储自动合成任务自带的 {@link TaskId}
 * （{@code TaskId.create()} 是任务创建时的随机 UUID，天然区分「内容完全相同的两条任务」——
 * 例如两批都下单 10 个精密构件）。</p>
 *
 * <p><b>计时规则</b>：扫描（{@link #SCAN_INTERVAL_TICKS}，1 秒一次）时只做<b>只读分类</b>：把
 * {@code reason} 与只读快照写出来；随后每 tick 由 {@link #advanceSuspendState} 统一计时，
 * 原因连续超过该原因对应的阈值（掉线 {@code assemblyOfflinePersistTicks} / 缺料
 * {@code assemblyStallTimeoutTicks} / 无进展 {@code assemblyNoProgressTimeoutTicks}）才挂起。
 * 正常推进的任务 {@code reason} 恒为 {@code NONE}，计数归零，<b>绝不会被挂起</b>。</p>
 *
 * <p><b>清理规则</b>（幂等、无泄漏）：任务完成 / 取消 / 任务对象从自动合成组件里消失 → 本次扫描立即移除记录；
 * 其中「玩家按原生取消」这条路径更快也更关键：{@code TaskContainerMixin} 发现任务进入 RS 的回收态
 * （{@code RETURNING_INTERNAL_STORAGE}）时当 tick 放行 RS 自己的回收（内部暂存立刻回网），并调用
 * {@link #onTaskTerminated(UUID)} <b>即时移除</b>我方挂起记录 + 立刻重播告警快照 —— 用户原话
 * 「取消时，不管是怎么样的，你要立刻返回」，所以既不能让 RS 的回收被挂起冻住，也不能在监视器上
 * 留下「已挂起」的幽灵行（绝不依赖记录过期）。
 * 另有兜底：超过 {@code Config.assemblyRecordExpiryTicks}（默认 12000 = 10 分钟）没被扫描到的记录被回收；
 * 记录总数硬上限 {@link #MAX_RECORDS}，超限先丢最旧的。记录里只保存不可变数据（uuid / 坐标 / 物品栈快照 /
 * 资源键），<b>不持有 BlockEntity / Network / Level 引用</b>。</p>
 *
 * <p><b>持久化选择</b>：计时器与记录<b>不落盘</b>。理由：RS 的 {@link TaskId} 是任务创建时生成的随机 UUID、
 * 不随存档保存，重启后旧 uuid 无意义；因此重启一律<b>视为新任务</b>（记录重建、计时从 0 开始）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class AssemblyWatchdog {
    /** 记录总数硬上限：超限先丢最旧的（防任何异常情况下的无界增长）。 */
    public static final int MAX_RECORDS = 256;
    /** 重型扫描间隔（tick）：计时器每 tick 走，网络扫描 1 秒一次。 */
    public static final int SCAN_INTERVAL_TICKS = 20;
    /** 横幅广播半径（格）：以注册该样板的样板库为中心。 */
    private static final int BANNER_RADIUS = 96;
    private static final int BANNER_RADIUS_SQ = BANNER_RADIUS * BANNER_RADIUS;
    /** 横幅第 3 行最多列几种原料 / 几个步骤（超出的用「等 N」概括，尽量避免该行被拆成多行）。 */
    private static final int MAX_LISTED = 3;
    /**
     * {@link Reason#EXECUTOR_OFFLINE} 的<b>连续确认次数</b>（本轮新增；见
     * {@code classifySequence} 的说明）。
     * <p>值 1 = 「上一次复查也观察到离线」即可确认 —— 与旧的「两次离线集合逐项相同」同一个时间跨度
     * （1 秒），但不再要求两次报的是<b>同一批</b>步骤，因此任务在「序列装配 / RS 原版」两种分类之间
     * 翻转时也能确认（旧判据在这种情况下永远确认不了 ⇒ 断缝不挂起、不弹横幅）。</p>
     */
    private static final int OFFLINE_CONFIRM_STRIKES = 1;
    /** 连续确认计数的饱和上限（防任何异常情况下的无界增长；到顶后恒为「已确认」）。 */
    private static final int OFFLINE_CONFIRM_STRIKES_MAX = 1_000_000;
    /**
     * 「下游通了」这条<b>收尾提示</b>的稳定窗口（tick）：2026-10-06 起判据换成<b>正向只读探针</b>
     * （「上次拒收我们的那个工位现在是不是空的」，见 {@link #trackPushStallRecovery}），
     * 因此窗口的含义是「<b>工位连续 {@value} tick（5 秒）都空着</b>」—— 它挡掉的是
     * 「机器刚把手里那件交出去、瞬间空了一下」这类假恢复。
     * <p>它<b>只</b>作用于这条纯展示提示的稳定性，<b>不</b>参与任何堵塞判定 / 挂起判定。</p>
     */
    private static final int PUSH_CLEARED_HOLD_TICKS = 100;
    /**
     * <b>「某一步没有任何在线执行仓认领」的进入边沿确认窗口</b>（tick；2026-10-06 新增，见
     * {@link #advancePushNotice}）。
     *
     * <p>这是<b>确定性</b>信号：这一步的件谁都做不了（没有一台在线执行仓认领它），件只会一直留在
     * 网络里。因此只要连续观察到 {@value} tick（0.5 秒）就成立。</p>
     *
     * <p><b>为什么必须提前</b>：旧路径要等「1 秒扫描节拍（{@value #SCAN_INTERVAL_TICKS} tick）+
     * 2 秒离线阈值（{@code Config.assemblyOfflinePersistTicks}）」，玩家观感就是「延迟反应」——
     * 提示要等 3 秒才来，用户原话「他应该在那个发现了的时候就直接弹提示」。</p>
     */
    private static final int PUSH_NOTICE_NO_OWNER_TICKS = 10;
    /**
     * <b>「下游工位收不下这一件」的进入边沿：『零进展』窗口</b>（tick；2026-10-06 由固定 42 tick 改成
     * 「拒收 <b>且</b> 零进展」，见 {@link #stallProgressOf}）。
     *
     * <h2>为什么旧的 42 tick 必然误报（用户实测两次：先弹「堵着了」、几秒后又弹「已恢复」，其实没堵）</h2>
     * <p>机器<b>正在加工</b>时本来就会正常拒收（{@code DESTINATION_DOES_NOT_ACCEPT/machine_full} 是忙机器的
     * 常态），而输出总线对同一目标的退避重试是
     * {@code RsccChamberExportStrategy.REFUSAL_BACKOFF_TICKS = 40} ⇒ 只要任一步加工超过 40 tick
     * （Create 的常见步骤都远超此值），<b>两次连续拒收</b>就把「窗口内一直在拒收」凑满了 ⇒ 弹「堵着了」；
     * 加工完成后工位空出来，又弹「已恢复」。全程其实什么都没堵 —— 旧判据把「忙」当成了「堵」。</p>
     *
     * <h2>新判据：拒收只是「正常状态」，「拒收 且 长时间零进展」才是堵</h2>
     * <p>窗口内必须同时成立（逐条实现见 {@link #stallProgressOf}）：</p>
     * <ol>
     *     <li><b>拒收持续</b>：每 tick 的热探针都能命中 —— 也就是
     *     {@code PUSH_STALL_WINDOW_TICKS = 60} 内没有一次成功推送把该目标的拒收记录清掉；</li>
     *     <li><b>零进展</b>：本仓向<b>这些</b>目的地（不只是一台：两条配方共用一批工位时，
     *     拒收会在它们之间交替刷新时间戳，见 {@code SequenceExecutionChamberBlockEntity#rscc$refusedTargets}）
     *     的成功推送全部中断 ≥ 本窗口（{@code SequenceExecutionChamberBlockEntity#rscc$lastPushOkAge}），
     *     并且每一格工位上的东西（件 / 件数 / 进度步）在本窗口内<b>一个 tick 都没变过</b>；</li>
     *     <li><b>工位上压着的不是「我们推进去的在制件」</b>：是的话说明机器正拿着我们那件在加工 ——
     *     Create 推进 {@code step} 的唯一位置是一步<b>做完</b>时
     *     （{@code SequencedAssemblyRecipe#advance}，{@code progress} 字段也是那时按步序重算的），
     *     因此它与「这一步要 200 tick 还是 1200 tick」无关；同一台机器连续承担多个步时步序还会
     *     <b>原地推进</b>，所以判据是「同一件（物品 + 配方）且步序没倒退」而不是「步序相等」，
     *     <b>一律不是堵塞</b>。这与本模组既有口径
     *     {@code RsccChamberImportStrategy#inTransitionResidence} 是同一件事
     *     （那时连<b>收回侧</b>都不许碰它，提示侧当然更不能说它堵）。</li>
     * </ol>
     *
     * <p><b>2026-10-06 第三次加固（两条配方共用一台机器时的误挂起）</b>：上面第 2 条的口径从
     * 「最近拒收的<b>那一台</b>」改成「本段窗口内出现过的<b>全部</b>拒收工位，逐格比对基线」——
     * 只要<b>任意一格</b>动过（另一条配方的件被做完 / 被取走）或<b>任意一格</b>又收下过我们的推送，
     * 就算「机器在忙」而不是堵。实机取证与复现链见 {@link #stallProgressOf} 的 javadoc。</p>
     *
     * <h2>窗口 {@value} tick 的推导（不是随手取数）</h2>
     * <ul>
     *     <li>{@code REFUSAL_BACKOFF_TICKS} = 40：窗口必须<b>跨过整整一次</b>退避重试 —— 否则一次瞬时拒收
     *     就能凑满窗口，那正是本轮要修掉的误报形态；</li>
     *     <li>{@code SCAN_INTERVAL_TICKS} = 20：再留一个完整扫描周期，让 1 Hz 的扫描与每 tick 的热探针
     *     至少交叉核对一次；</li>
     *     <li>40 + 20 = 60 = <b>执行舱自己的</b> {@code PUSH_STALL_WINDOW_TICKS}（它定义的「持续拒收」
     *     窗口正是 60 tick）。三处常量由此统一成同一个「持续」的量，不再出现「42 说堵、60 说没堵」
     *     两套口径。</li>
     * </ul>
     * <p>60 tick = 3 秒 ⇒ 真实堵塞仍是<b>秒级</b>发现；而正常加工（无论几十秒）由第 3 条挡掉。</p>
     */
    private static final int PUSH_NOTICE_ZERO_PROGRESS_TICKS = 60;

    /** 卡住原因（序号即网络包里的 REASON_*，声明顺序不可变）。 */
    public enum Reason {
        /** 无异常。 */
        NONE,
        /** 原料 / 输入时原料缺少至无法继续（由计时器判定）。 */
        MISSING_MATERIAL,
        /** 步骤执行器（序列执行仓）/ 机器脱离网络、被拆、未加载，或拒绝接收。 */
        EXECUTOR_OFFLINE,
        /** 长时间没有任何进展（兜底判据）。 */
        NO_PROGRESS,
        /**
         * 玩家手动挂起（本轮新增，<b>追加在末尾</b>：序号即网络包里的 REASON_*，不能插在中间）。
         *
         * <p>与上面三种「自动判定出来的卡住原因」区分开：它由 {@link #suspend}（玩家点「挂起」）
         * 写入，挂起语义完全相同，只是监视器上的标记要能显示「是玩家自己挂的」。</p>
         */
        MANUAL,
        /**
         * <b>下游输出被阻塞</b>（2026-10-05 新增，同样追加在末尾）。
         *
         * <p>与 {@link #EXECUTOR_OFFLINE} 的区别是<b>机器在线</b>：某台执行仓持续把料推不出去
         * （目的地机器 / 置物台拒收、已满、或不接受这一件）。旧实现把它并进
         * {@code EXECUTOR_OFFLINE}，于是横幅说「执行器掉线」，而玩家的机械手与置物台明明是空的
         * —— 实机取证（两份快照 + 日志）证明那一次真正的原因是「某一步没有任何在线执行仓认领」，
         * 与「掉线」和「满」都不是一件事。现在两者各有自己的 reason 与文案。</p>
         */
        OUTPUT_BLOCKED
    }

    /**
     * 挂起状态机（序列装配任务与 RS 原版任务共用同一套）。
     *
     * <p><b>为什么只有两态（本轮移除 PROBING）</b>：RS 没有「暂停 / 恢复」API，把任务停下来的手段是让
     * {@code TaskContainer} 不再 step 它（见 {@code mixin/TaskContainerMixin}）。旧实现为了「自动恢复」
     * 又加了一个「探测窗口（PROBING）」态，想临时解挂让 RS 自己重试再据进展判定是否恢复 —— 但这套
     * 自动恢复已被撤销（见类注释：玩家故意拆机器 / 要重新配置，必须手动点「继续」），退避 / 探测窗口
     * 随之失去意义。于是只剩两态：{@link #RUNNING}（RS 照常推进）与 {@link #SUSPENDED}（不再被 step，
     * 只等玩家的「继续」）。</p>
     */
    public enum SuspendState {
        /** 正常运行（未被挂起，RS 照常推进）。 */
        RUNNING,
        /** 已挂起：不再被 step（不抽料 / 不投料 / 不驱动执行器），物品冻结在任务自身暂存里；只由玩家点「继续」解除。 */
        SUSPENDED
    }

    /** 一条任务记录（服务端权威；只读快照 + 计时状态）。 */
    public static final class Record {
        private final UUID taskId;
        private final BlockPos executorPos;
        /** 任务产物（RS 资源键；用于「更换机器」时在样板库里找回对应总样板）。 */
        private final ItemResource product;
        /** 记录时的世界 tick。 */
        private final long recordedTick;
        /** 最近一次被扫描到（= 任务仍然存在）的世界 tick。 */
        private long lastSeenTick;
        /** 当前世界 tick。 */
        private long currentTick;
        /** 当前原因连续持续的 tick 数（每 tick +1；原因变回 NONE 时归零）。 */
        private int stallTicks;
        /** 是否已经弹过横幅（保证「&gt; 阈值只触发一次」）。 */
        private boolean notified;
        /** 是否处于挂起（等待自动恢复或玩家在监视器里处置）；恒等于 {@code suspendState != RUNNING}。 */
        private boolean suspended;
        private Reason reason = Reason.NONE;
        /**
         * <b>本轮新增（用户实测：所有序列装配都报"设备掉线"）</b>：本任务<b>实际用到</b>的执行仓里
         * 是否有任意一台处于「推不动」状态（目的地持续拒收 / 已满）。
         * <p>只有当 {@link #reason} 因「推不动」被设成 {@link Reason#EXECUTOR_OFFLINE}
         * 或 {@link Reason#OUTPUT_BLOCKED} 时才为 true（此时 {@link #offlineSteps} 必为空
         * —— 判据与「步骤掉线」互斥）。横幅据此区分「输出阻塞」与「设备掉线」两种文案，
         * 避免误导玩家去拆机器。</p>
         */
        private boolean pushStalled;
        /**
         * 「推不动」的具体命中（哪台仓 / 什么原因 / 涉及哪个资源）；只读诊断数据，供横幅与快照使用。
         * <p>2026-10-05 新增：旧实现只有一个布尔值，于是「下游机器满」与「某步没有机器认领」
         * 在横幅上都显示成「执行器掉线」。</p>
         */
        @Nullable
        private StallHit stallDetail;
        /** 是不是「序列装配」任务（false = RS 原版自动合成任务）。只影响原因判定与文案，挂起语义完全相同。 */
        private boolean sequence;
        /**
         * 最近一次<b>成功解析到</b>的总样板装配数据（用户第 ⑥ 条的第三条路径）。
         * <p>样板库自己掉线后，扫描再也解析不到它的样板（{@code patterns} 里没有条目）⇒ 若不缓存，
         * 这条任务就会被降级成「RS 原版任务」、失去离线判定 ⇒ 断缝后不挂起、不弹横幅。
         * 缓存后只要「曾经」是序列装配任务，就一直按序列装配分类。只存不可变数据（不钉住方块实体）。</p>
         */
        @Nullable
        private SequencePatternData.AssemblyData lastAssembly;
        /** 与 {@link #lastAssembly} 配套的样板库坐标（掉线判定 / 横幅锚点用）。 */
        @Nullable
        private BlockPos lastExecutorPos;
        /**
         * RS 此刻是否正在「把内部暂存还回网络」（{@code TaskState.RETURNING_INTERNAL_STORAGE}）。
         * <p>这种状态下<b>不提供</b>「手动挂起」：把它跳过会让 RS 自己的回收也一起停住（东西白白冻在暂存里）。
         * 若此刻还挂着，则这条记录会被<b>同步移除</b>（用户要求「取消必须立刻返回」，不留幽灵行 ——
         * 见 {@link #onTaskTerminated(UUID)} 与 {@code scanNetwork} 的 returning 分支）。每次扫描刷新，只读。</p>
         */
        private boolean returning;
        // ---- 挂起状态机（与序列装配共用同一套）----
        private SuspendState suspendState = SuspendState.RUNNING;
        /** 本次连续挂起的起始 tick（超限兜底用）。 */
        private long suspendedSinceTick;
        /** 挂起超限是否已提示过（避免每 tick 刷屏）。 */
        private boolean overflowNotified;
        // ---- 只读快照（横幅 / 管理器展示用）----
        private ItemStack productIcon = ItemStack.EMPTY;
        private String productName = "";
        private long amount;
        private List<ItemStack> materialIcons = List.of();
        private List<String> materialNames = List.of();
        /** 缺少的原料种数（可能多于横幅里列出的 {@link #MAX_LISTED} 种）。 */
        private int materialTotal;
    /**
     * <b>整单口径</b>的原料缺口（资源 → 还差多少件）。
     * <p>与 {@link #materialIcons} / {@link #materialTotal} 的区别：那两者是「去重后每种缺一件」，
     * 而这是「这件订单一共还要多少」。用户实测要的就是后者：
     * 「下单 64 个，却只提示缺少一个大齿轮」。</p>
     */
    private java.util.Map<String, Long> materialDeficit = java.util.Map.of();
        private List<SyncAssemblyAlertsPacket.OfflineStep> offlineSteps = List.of();
        /**
         * 「连续观察到执行器离线」的复查次数（本轮新增）。
         * <p>为什么不是「与上一次的离线集合逐项相同」：任务在「序列装配」与「RS 原版」两种分类之间
         * 翻转时（执行器 / 扫描入口瞬时掉出网络图就会这样），{@code classifyGeneric} 会把
         * {@code offlineSteps} 清空 ⇒ 旧判据永远凑不齐「上一次也报同一批」⇒ <b>真实断缝永远不被确认</b>，
         * 于是既不挂起也没有横幅（用户实测「线缆断了却什么提示都没有」）。计数只在「本次复查完全没有
         * 离线证据」时归零，因此真实的瞬时抖动仍然不会被当成断缝。</p>
         */
        private int offlineStrikes;
        /**
         * 「下游已经又能收料了」这条<b>收尾提示是否已经弹过</b>（2026-10-06 新增；恢复边沿位）。
         *
         * <p>语义与 {@link #notified} 对称：{@code notified} 保证「同一卡住状态只弹一次（进入边沿）」，
         * 本字段保证「同一次恢复只提示一次（退出边沿）」。重新堵上时由
         * {@link #trackPushStallRecovery} 复位，于是「堵 → 通 → 又堵 → 又通」每一段各提示一次，
         * 而<b>持续不变的同一状态绝不重复弹</b>（用户硬要求）。</p>
         */
        private boolean pushClearedNotified;
        /**
         * 「工位空出来」的<b>连续观察起点</b>（世界 tick；{@code -1} = 当前并未观察到空）。
         *
         * <p>这是提示层的<b>稳定窗口</b>（不是堵塞判定，也不参与挂起）：判据是正向只读探针
         * 「上次拒收我们的那个工位现在空不空」，而机器刚把手里那件交出去时也会空一下 ——
         * 若一见空就播「已恢复」，堵住—通了之间来回抖一次就会连播两条横幅。因此要求
         * 「连续 {@value #PUSH_CLEARED_HOLD_TICKS} tick 都空着」才播那一条收尾提示。</p>
         */
        private long pushClearedSinceTick = -1L;
        // ---- 「发现即提示」的进入边沿（2026-10-06 新增；每 tick 热探针，见 advancePushNotice）----
        /**
         * 本任务<b>实际用到的执行仓坐标</b>（样板库锚点 + 每一步指派的机器；扫描时缓存的不可变副本）。
         *
         * <p>它让「发现即提示」不必等下一次 1 秒扫描，也<b>不必</b>做任何网络图遍历 / 存储扫描 ——
         * 每 tick 只对这几个坐标做一次方块实体查表（见 {@link #hotPushStallHit}）。
         * 只存坐标，<b>不</b>钉住任何方块实体 / 网络。</p>
         */
        private List<BlockPos> taskChambers = List.of();
        /**
         * 进入边沿是否<b>已重新武装</b>（同一段堵塞只弹一次）。
         *
         * <p>语义与 {@link #notified} 对称，但判据不同：{@code notified} 只保证「一次挂起弹一次」，
         * 而它在玩家点「继续」时会被复位 —— 于是「继续后仍然堵着」会在 2 秒后<b>再弹一条同样的
         * 横幅</b>（用户实测的「多发」）。这里改成只有<b>观察到工位真的空出来</b>
         * （{@link #trackPushStallRecovery} 的正向探针）才重新武装，因此
         * 「持续堵」「继续后仍堵」都<b>绝不</b>重复弹。</p>
         */
        private boolean pushNoticeArmed = true;
        /** 当前这段「推不动」的连续观察起点（世界 tick；{@code -1} = 本 tick 并未观察到）。 */
        private long pushNoticeSinceTick = -1L;
        /**
         * 热探针是否<b>确实</b>接管过这条记录的「推不动」判定（见 {@link #advancePushNotice}）。
         *
         * <p>它只用于抑制老路径（{@link #advanceSuspendState} 的「扫描 + stallTicks 阈值」），
         * 因此取「热探针真的命中过」而不是「有资格命中」：万一某次方块实体查表与扫描口径不一致，
         * 老路径仍然保留，绝不出现<b>两条路都不弹</b>。</p>
         */
        private boolean pushNoticeHot;
        /**
         * 最近一次「让窗口重新起算」的那一格（{@code null} = 还没发生过 / 当前这段观察已作废）。
         * <p><b>只用于诊断</b>（日志里说清「是哪一格证明了还有进展」）；判定本身只看
         * {@link #pushNoticeStationBaselines}。</p>
         */
        @Nullable
        private BlockPos pushNoticeStationAt;
        /**
         * <b>本段观察窗口内出现过的全部拒收工位 → 它在「窗口起点」的内容指纹</b>
         * （件 / 件数 / 进度步；见 {@link #stationFingerprintOf}）。
         *
         * <h2>为什么是「一张表 + 窗口起点基线」，而不是「一台 + 上一 tick」（2026-10-06 第三次修正）</h2>
         * <p>旧实现用<b>两个字段</b>记「{@code latestRefusedTarget()} 这一格上一 tick 长什么样」。
         * 当<b>两条配方共用同一批工位</b>时（实测：机械手同时承担 {@code precision_mechanism} 与
         * {@code track} 的步），拒收会在两台置物台之间<b>交替</b>刷新时间戳 ⇒
         * {@code rscc$latestRefusedTarget()} 每几秒就换一格 ⇒ 旧字段里的坐标与新读到的那一格对不上，
         * 比对被整对作废（「绝不是同一格」），于是「另一格刚被取走」「另一格刚推成功」这两件本可以
         * 证明「机器在忙」的事实<b>全部看不见</b>，最终把「另一条配方正在被加工」判成了「堵死了」。</p>
         *
         * <p>现在改成：<b>逐格</b>记基线（键 = 工位坐标，值 = 该格在窗口起点的指纹），
         * 于是任意一格的任何变化都能被算成「进展」；窗口重新起算时清空重记（见
         * {@link #advancePushNotice}）。窗口内没再出现过的工位自然随表一起被清掉 ——
         * 绝不把上一段观察的旧指纹拿来当作这一段「没变」的证据。</p>
         */
        private final Map<BlockPos, String> pushNoticeStationBaselines = new LinkedHashMap<>();
        /**
         * <b>窗口刚刚重新起算 ⇒ 下一 tick 的逐格基线要整体重取</b>。
         *
         * <p>为什么不能直接 {@code clear()} 那张表：键集本身就是「<b>本段观察里曾经拒收过我们的工位</b>」，
         * 而「某一格刚刚收下了我们推的料」（= 上一次进展的来源）恰恰是<b>下一 tick 还要继续用</b>的
         * 证据 —— 一清就把它从观察集合里删掉了，于是「本仓刚推成功 → 工位安静 60 tick → 误判堵死」
         * 会重新出现（本轮的复现脚本场景 13 第 40 tick 起就是这么丢掉 A 格的）。
         * 因此这里只<b>重取数值</b>（把基线刷成当前内容），<b>保留键集</b>。</p>
         */
        private boolean pushNoticeRebaseline;
        // ---- 进度比对（判断「有没有在动」）----
        private double lastPercentage = -1.0;
        private long lastItemTotal = -1L;

        private Record(final UUID taskId, final BlockPos executorPos, final ItemResource product,
                       final long recordedTick) {
            this.taskId = taskId;
            this.executorPos = executorPos.immutable();
            this.product = product;
            this.recordedTick = recordedTick;
            this.lastSeenTick = recordedTick;
        }

        public UUID taskId() {
            return taskId;
        }

        public long recordedTick() {
            return recordedTick;
        }

        public long currentTick() {
            return currentTick;
        }

        public int stallTicks() {
            return stallTicks;
        }

        public Reason reason() {
            return reason;
        }

        public boolean suspended() {
            return suspended;
        }

        /**
         * 只读快照（不可变；给网络包 / 管理器用）。
         *
         * <p><b>未挂起时：原因一律写 {@code REASON_NONE}、展示用的原料 / 步骤列表一律留空。</b>
         * 理由有两条：</p>
         * <ol>
         *     <li>客户端只在「已挂起」时才画挂起原因标记（见 {@code AutocraftingMonitorScreenMixin}），
         *     未挂起的快照只需要「这一位动作」—— 它此刻可被玩家手动挂起；</li>
         *     <li>健康任务每扫描都在动（原料被消耗、缺料集合每秒都在变），把这些字段带上会让
         *     {@code broadcast} 的「内容指纹」每秒翻新 ⇒ 白白刷包（挂起后这些字段是冻结的，反而稳定）。</li>
         * </ol>
         */
        public SyncAssemblyAlertsPacket.Alert snapshot() {
            if (!suspended) {
                return new SyncAssemblyAlertsPacket.Alert(taskId, SyncAssemblyAlertsPacket.REASON_NONE,
                    actions(), productName, productIcon, amount, List.of(), List.of());
            }
            final List<SyncAssemblyAlertsPacket.Material> materials = new ArrayList<>(materialIcons.size());
            for (int i = 0; i < materialIcons.size() && i < materialNames.size(); i++) {
                materials.add(new SyncAssemblyAlertsPacket.Material(materialIcons.get(i), materialNames.get(i)));
            }
            return new SyncAssemblyAlertsPacket.Alert(taskId, reason.ordinal(), actions(), productName,
                productIcon, amount, materials, offlineSteps);
        }

        /**
         * 这条记录此刻<b>允许客户端点哪些动作</b>（位集，唯一的按钮可见性判据）。
         *
         * <p><b>为什么由服务端算</b>：客户端没有「任务是否仍被挂起」「执行仓还在不在网络里」的权威信息，
         * 之前让界面按 {@code reason + offlineSteps} 自己推断，服务端一旦重新分类（例如执行仓回来了、
         * 原因从掉线改判成缺料），客户端就会单独把「更换机器」抹掉 —— 玩家看到的是「按钮莫名消失」。
         * 现在服务端一次性判定，客户端只做「有这一位 → 渲染这个按钮」的映射，两边不可能不一致。</p>
         *
         * <p><b>状态 → 按钮判定表（本轮明确，互斥且穷举）</b>：</p>
         * <pre>
         *   进行中（未被挂起，且 RS 未在回收）      → 挂起
         *   已挂起（且 RS 未在回收）                → 继续（+ 更换机器，仅「执行器掉线且有步骤坐标」时）
         *   正在回收内部暂存 / 已完成（returning）  → 无任何动作位（界面不画任何按钮）
         * </pre>
         * <p>最后一行是本轮修正点：旧实现只在「未挂起」那条分支上看 {@code returning}，
         * 于是<b>已挂起</b>的任务一旦被 RS 收尾，仍会继续广播「继续」位 —— 玩家看到
         * 「一边继续、一边完成」。收尾态一律不给任何动作位，界面那一行与按钮当帧消失。</p>
         */
        private int actions() {
            if (returning) {
                // RS 正在把内部暂存还回网络（取消 / 收尾）或任务已结束：这不是「可处置」的状态，
                // 任何按钮（挂起 / 继续 / 更换机器）都不该出现 —— 与「取消必须立刻返回、不留幽灵行」同一口径。
                return SyncAssemblyAlertsPacket.ACTION_BIT_NONE;
            }
            if (!suspended) {
                // 没挂起：只要这条任务此刻还能被 RS 推进，玩家就可以把它「挂起」让位。
                // 这一位与「继续」互斥：挂起后走下面的分支，界面同一时刻只会渲染其中一个按钮。
                return SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND;
            }
            // 挂起就一定可以「继续」（清挂起 + 重新计时）。
            int actions = SyncAssemblyAlertsPacket.ACTION_BIT_RESUME;
            if (reason == Reason.EXECUTOR_OFFLINE && !offlineSteps.isEmpty()) {
                // 「更换机器」必须能指名掉线的是哪一步，否则这项动作根本无从下手。
                actions |= SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE;
            }
            return actions;
        }
    }

    /** 维度 → (任务 uuid → 记录)。 */
    private static final Map<ResourceKey<Level>, Map<UUID, Record>> RECORDS = new HashMap<>();
    /** 维度 → 已加载区块坐标（打包 long），由区块加载 / 卸载事件维护（扫描的坐标来源）。 */
    private static final Map<ResourceKey<Level>, Set<Long>> LOADED_CHUNKS = new HashMap<>();
    /** 维度 → 最近一次广播出去的告警快照指纹（只有变化时才广播，避免刷包）。 */
    private static final Map<ResourceKey<Level>, String> LAST_BROADCAST = new HashMap<>();
    /**
     * 挂起 / 继续 / 任务终止的<b>全局版本号</b>（只增不减、服务端权威，本轮新增）。
     *
     * <p><b>为什么需要它（用户要求「挂起当刻即停」）</b>：执行仓的门控（
     * {@code SequenceExecutionChamberBlockEntity#tickBusScheduler}）平时每 10 tick 才复查一次，
     * 挂起最多要 0.5 秒才反映到「本仓停工」。执行仓逐 tick 比对本版本号，一变就把复查冷却清零，
     * 于是<b>玩家点「挂起 / 继续」的那一个 tick 内</b>本仓立即停 / 启 —— 不需要给执行仓表加订阅，
     * 也不需要任何反向引用（本类仍然只做只读观察 + 挂起状态机的单一入口）。</p>
     *
     * <p>客户端恒为 0（记录表在客户端恒为空），不影响单人局里的本地自动合成。</p>
     */
    private static int SUSPEND_EPOCH;
    /**
     * 维度 → 该维度的 {@link ServerLevel}（每次扫描刷新）。
     *
     * <p><b>为什么需要它</b>：玩家按原生「取消」时，我方由 {@code TaskContainerMixin} 在
     * <b>当 tick</b> 收到「任务已进入回收 / 结束态」的通知（{@link #onTaskTerminated}），
     * 需要立刻把告警快照重播一次、把幽灵行从监视器上抹掉 —— 而那一刻手上只有任务 uuid，
     * 没有 {@link ServerLevel}。这里缓存最近一次扫描过的 level（只存服务端已加载维度的实例，
     * 维度卸载时清掉），用来完成这次「立即广播」。</p>
     */
    private static final Map<ResourceKey<Level>, ServerLevel> LEVELS = new HashMap<>();

    private AssemblyWatchdog() {
    }

    // ==================== 事件：tick / 区块 / 维度 / 玩家 ====================

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        for (final ServerLevel level : event.getServer().getAllLevels()) {
            tickLevel(level);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(final ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final var pos = event.getChunk().getPos();
        LOADED_CHUNKS.computeIfAbsent(level.dimension(), key -> new HashSet<>())
            .add(packChunk(pos.x, pos.z));
    }

    @SubscribeEvent
    public static void onChunkUnload(final ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final Set<Long> chunks = LOADED_CHUNKS.get(level.dimension());
        if (chunks != null) {
            final var pos = event.getChunk().getPos();
            chunks.remove(packChunk(pos.x, pos.z));
        }
    }

    /** 维度卸载：把该维度的记录、区块表、广播指纹一起丢掉（无泄漏）。 */
    @SubscribeEvent
    public static void onLevelUnload(final LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        RECORDS.remove(level.dimension());
        LOADED_CHUNKS.remove(level.dimension());
        LAST_BROADCAST.remove(level.dimension());
        LEVELS.remove(level.dimension());
    }

    /** 玩家登录：把当前告警快照单独补发给他（否则打开管理器时按钮会因缺数据而不可用）。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendAlerts(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(final PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendAlerts(player);
        }
    }

    // ==================== 每 tick ====================

    private static void tickLevel(final ServerLevel level) {
        final long now = level.getGameTime();
        if (now % SCAN_INTERVAL_TICKS == 0L) {
            scanLevel(level, now);
        }
        final Map<UUID, Record> records = RECORDS.get(level.dimension());
        if (records == null || records.isEmpty()) {
            return;
        }
        // 超限「安全回收」会结束任务 —— 必须等遍历结束再从表里删（否则 ConcurrentModificationException）
        final List<UUID> reclaimed = new ArrayList<>();
        for (final Record record : records.values()) {
            record.currentTick = now;
            // 「发现即提示」的进入边沿：每 tick 一次只读热探针（不做网络遍历），
            // 因此在「第一次确凿发现」的那一 tick 就能弹提示，不必等下一次 20 tick 扫描。
            advancePushNotice(level, record, now);
            advanceSuspendState(level, record, now, reclaimed);
        }
        for (final UUID taskId : reclaimed) {
            records.remove(taskId);
        }
    }

    /**
     * 推进单条记录的挂起状态机（每 tick 一次）—— <b>「挂起」判定的唯一入口</b>。
     *
     * <p><b>状态机</b>（{@link SuspendState}，只有两态）：</p>
     * <ol>
     *     <li>{@code RUNNING}：扫描给出的 {@code reason} 连续超过该原因对应的阈值
     *     （掉线 {@code assemblyOfflinePersistTicks}／缺料 {@code assemblyStallTimeoutTicks}／
     *     无进展 {@code assemblyNoProgressTimeoutTicks}）→ 弹一次横幅 → 转 {@code SUSPENDED}；</li>
     *     <li>{@code SUSPENDED}：任务被 {@code TaskContainerMixin} 跳过（不再占用执行器、不再抽料投料），
     *     <b>就一直挂着</b>（哪怕原因已经恢复正常），只等玩家点「继续」；</li>
     *     <li>连续挂起超过 {@code assemblySuspendLimitTicks} → 提示一次；按
     *     {@code assemblySuspendOverflowReclaim} 决定「继续挂起」还是「安全回收」（走 RS 自己
     *     的取消路径，物品原样还回网络，不销毁）。</li>
     * </ol>
     *
     * <p><b>本轮撤回「自动恢复」（用户原话）</b>：旧实现一旦把 {@code reason} 重新判回
     * {@link Reason#NONE}（或观察到一次进展）就当场彻底恢复 —— 这是错的。玩家拆机器常常是
     * <b>故意</b>的（要增加机器数量 / 增加输出总线数量并重新配置），必须「点击继续才能继续任务」，
     * 而不能「一检测到（连上）就立刻继续」。因此这里<b>不再有任何自动恢复路径</b>：恢复只由玩家的
     * {@code AssemblyTaskActionPacket.ACTION_RESUME} → {@link #resume} 触发。已挂起时扫描也
     * <b>冻结原因</b>（见 {@code scanNetwork}），因此横幅与监视器上的「已挂起：执行器离线 / 缺原料」
     * 不会被改成别的值。</p>
     *
     * <p><b>为什么阈值按原因分开</b>：RS 原版任务状态里的「机器拒绝 / 锁定 / 找不到接收端」可能是
     * 瞬时的（机器输入口刚好满了一瞬），只有持续一段时间才算真掉线；而序列装配用「指派的执行仓坐标
     * 不在网络里」这个确定性信号，所以给一个较短的持续窗口即可，避免误挂健康任务。</p>
     */
    private static void advanceSuspendState(final ServerLevel level, final Record record, final long now,
                                            final List<UUID> reclaimed) {
        if (record.reason == Reason.NONE) {
            if (record.stallTicks != 0) {
                record.stallTicks = 0;
            }
        } else if (record.stallTicks < Integer.MAX_VALUE - 1) {
            record.stallTicks++;
        }

        // 已挂起：不做任何自动恢复 —— 挂着就一直挂着，直到玩家点「继续」（见上面注释）。
        // 原因连续超过该原因对应的阈值才挂起；正常推进的任务 reason 恒为 NONE，绝不会被挂起。
        //
        // <b>用户第 5 条新增开关（服务端权威、按存档持久化）</b>：缺料处置可选「挂起」或「一直等待」。
        // 默认档 {@link RsccShortagePolicy.Mode#SUSPEND} 与既有行为逐字一致；选 {@link RsccShortagePolicy.Mode#WAIT}
        // 时只压制「缺料」这一种原因的<b>自动</b>挂起（任务原地保留，原料补上后自动继续）。
        // 执行器离线 / 无进展这两类<b>照旧挂起</b>：否则一个真的掉线的任务会永远卡住、把后面的任务全堵死
        // —— 那正是这条开关要避免的事。手动挂起（{@link #suspend}）不受本开关影响，任何时候都生效。
        final boolean shortWaiting = record.reason == Reason.MISSING_MATERIAL
            && RsccShortagePolicy.mode(level) == RsccShortagePolicy.Mode.WAIT;
        if (record.suspendState == SuspendState.RUNNING) {
            // <b>2026-10-06（P1）：序列装配的「推不动」两族改由每 tick 的热探针统一负责</b>
            //（见 {@link #advancePushNotice}）—— 这里不再让它们走「1 秒扫描 + 2 秒 stallTicks 阈值」
            // 那条老路。两个理由：
            //   ① 老路是「延迟反应」的主要来源（扫描节拍最多 1 秒 + 阈值 2 秒）；
            //   ② 两条路同时存在时，同一次堵塞会弹第二条横幅 —— 玩家点「继续」后仍然堵着时
            //      老路会在 2 秒后把同样的横幅再弹一遍，正是用户反感的「多发」。
            // 其余原因（缺料 / 无进展 / 步骤掉线 / RS 原版任务）与未缓存执行仓坐标的记录照旧走这里。
            final boolean noticeOwned = record.pushNoticeHot && pushStallReason(record);
            if (!shortWaiting && record.reason != Reason.NONE && !noticeOwned
                && record.stallTicks > thresholdFor(record.reason)) {
                suspendRecord(record, record.reason, now);
                sendBanner(level, record);
            }
        } else if (now % SCAN_INTERVAL_TICKS == 0L && record.reason != Reason.NONE) {
            // 诊断（只读）：已挂起 —— 卡住条件仍持续存在，但横幅只弹过一次（被去重吞掉）。
            // 每个扫描周期记一笔（只累加内存计数，不打任何日志），用于报告「条件持续了多久 / 只提示了一次」。
            RsccDiag.recordBannerSuppressed(
                "watchdog_" + record.reason.name().toLowerCase(java.util.Locale.ROOT), record.taskId.toString());
        }

        if (record.suspendState != SuspendState.RUNNING
            && !record.overflowNotified
            && now - record.suspendedSinceTick > suspendLimitTicks()) {
            record.overflowNotified = true;
            overflowHandle(level, record, reclaimed);
        }
    }

    /**
     * <b>「挂起」的唯一实现</b>：写原因 → 置「已通知」位 → 状态机转 {@link SuspendState#SUSPENDED}。
     *
     * <p><b>为什么两处共用一处</b>：自动挂起（{@link #advanceSuspendState}，原因连续超过阈值）与玩家
     * 手动挂起（{@link #suspend}）在语义上必须<b>完全一致</b> —— 都经由同一处
     * {@link #setSuspendState} 让 {@code TaskContainerMixin} 从此跳过这条任务的 step（不再抽料 /
     * 不再投料 / 不再驱动执行器），已抽出的中间件原地冻结在任务自身暂存里（不复制、不销毁）。
     * 只有「原因标记」由调用方指定：自动挂起沿用判定出来的原因，手动挂起写 {@link Reason#MANUAL}。</p>
     */
    private static void suspendRecord(final Record record, final Reason reason, final long now) {
        record.reason = reason;
        record.notified = true;
        setSuspendState(record, SuspendState.SUSPENDED, now);
    }

    /** 彻底恢复：清掉挂起状态机与计时器（<b>只有</b>玩家点「继续」这一条路会走到这里）。 */
    private static void resumeRecord(final Record record, final long now) {
        setSuspendState(record, SuspendState.RUNNING, now);
        record.suspendedSinceTick = 0L;
        record.overflowNotified = false;
        record.reason = Reason.NONE;
        record.stallTicks = 0;
        record.notified = false;
        // 「下游通了」的收尾提示同样重新武装：下一次再堵上时，两半提示（堵 / 通）各来一次。
        record.pushClearedNotified = false;
        record.pushClearedSinceTick = -1L;
    }

    /** 状态与 {@code suspended} 一起写回，保证「挂起标记」与状态机永不失配。 */
    private static void setSuspendState(final Record record, final SuspendState state, final long now) {
        if (record.suspendState == state) {
            return;
        }
        if (state != SuspendState.RUNNING && record.suspendState == SuspendState.RUNNING) {
            record.suspendedSinceTick = now;
        }
        record.suspendState = state;
        record.suspended = state != SuspendState.RUNNING;
        // 状态真的变了：推进全局版本号 —— 执行仓逐 tick 比对它，从而「挂起 / 继续当刻即停 / 即启」。
        SUSPEND_EPOCH++;
    }

    /**
     * 挂起 / 继续 / 任务终止的全局版本号（只增不减）。
     * <p>执行仓（{@code tickBusScheduler}）逐 tick 比对：一变就立即复查门控，因此挂起 / 继续
     * <b>当刻</b>生效，不必等下一个 10 tick 的门控节拍。只读、无副作用、客户端恒为 0。</p>
     */
    public static int suspendEpoch() {
        return SUSPEND_EPOCH;
    }

    /**
     * 挂起超限的最终处置。
     * <p>默认 {@code HOLD}：继续挂起（永不自动取消），只打一条结构化日志（不刷屏）。</p>
     */
    private static void overflowHandle(final ServerLevel level, final Record record,
                                       final List<UUID> reclaimed) {
        if (!Config.assemblySuspendOverflowReclaim) {
            RsccAssemblyDebug.warn("suspendhold@" + record.taskId,
                "suspend-hold task=" + record.taskId + " reason=" + record.reason
                    + " suspendedTicks=" + (record.currentTick - record.suspendedSinceTick)
                    + " sequence=" + record.sequence
                    + " —— 仍保持挂起（assemblySuspendOverflowReclaim=false）");
            return;
        }
        final boolean ok = reclaim(level, record);
        if (ok) {
            reclaimed.add(record.taskId);
        }
        RsccAssemblyDebug.warn("suspendreclaim@" + record.taskId,
            "suspend-reclaim task=" + record.taskId + " reason=" + record.reason
                + " ok=" + ok + " sequence=" + record.sequence);
    }

    /**
     * 「安全回收」：调用 RS <b>自己的</b>取消路径结束任务，让 {@code TaskImpl} 把内部暂存
     * （已抽出的中间产物）原样 insert 回网络（{@code returnInternalStorageAndTryCompleteTask}）。
     * <p><b>不复制 / 不销毁</b>：全程只调 RS 的公开 API，本模组不搬运任何物品或流体。</p>
     * <p>这是「挂起超限」的兜底处置，<b>不是</b>面向玩家的第二个「取消」按钮 ——
     * 监视器上仍然只有 RS 原生的「取消 / 取消全部」。</p>
     * <p>调用方负责在遍历结束后把记录从表里删掉（本方法<b>不</b>改记录表）。</p>
     */
    private static boolean reclaim(final ServerLevel level, final Record record) {
        final Network network = networkOf(level, record);
        if (network == null) {
            return false;
        }
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return false;
        }
        autocrafting.cancel(new TaskId(record.taskId));
        return true;
    }

    /** 该原因对应的「连续卡住多少 tick 才算数」阈值（判定只有这一处）。 */
    private static int thresholdFor(final Reason reason) {
        return switch (reason) {
            // 「输出阻塞」与「设备掉线」共用同一档：两者都是确定性信号（不是猜出来的），
            // 只是原因不同；阈值分开会让同一个物理现象按文案差异挂起得时快时慢。
            case EXECUTOR_OFFLINE, OUTPUT_BLOCKED -> positive(Config.assemblyOfflinePersistTicks, 40);
            case NO_PROGRESS -> positive(Config.assemblyNoProgressTimeoutTicks, 600);
            default -> stallThreshold();
        };
    }

    /** 挂起上限（tick）；同时被自检引用，保证「上限规则」只有一处。 */
    public static int suspendLimitTicks() {
        return positive(Config.assemblySuspendLimitTicks, 12000);
    }

    /** 负值 / 0（配置未加载）时回落到默认值。 */
    private static int positive(final int value, final int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int stallThreshold() {
        return positive(Config.assemblyStallTimeoutTicks, 100);
    }

    /** 过期上限（tick）；同时被 {@link #isExpired} 引用，保证「判定规则」只有一处。 */
    public static int recordExpiryTicks() {
        return Config.assemblyRecordExpiryTicks > 0 ? Config.assemblyRecordExpiryTicks : 12000;
    }

    /** 「当前 tick − 记录时（最近一次见到）的 tick」超过上限即视为过期记录（自检可复用）。 */
    public static boolean isExpired(final long currentTick, final long lastSeenTick) {
        return currentTick - lastSeenTick > recordExpiryTicks();
    }

    // ==================== 扫描（只读） ====================

    private static void scanLevel(final ServerLevel level, final long now) {
        // 缓存本维度实例：TaskContainerMixin 在「任务进入回收 / 结束态」时会用它做一次即时广播
        // （把幽灵「已挂起」行立刻从监视器上抹掉），那一刻手上只有 uuid。
        LEVELS.put(level.dimension(), level);
        final Map<UUID, Record> records =
            RECORDS.computeIfAbsent(level.dimension(), key -> new LinkedHashMap<>());
        final Set<Long> chunks = LOADED_CHUNKS.get(level.dimension());
        if (chunks == null || chunks.isEmpty()) {
            // 已知已加载区块为空：没有任何执行器可见 ⇒ 全部记录视为「任务对象消失」
            records.clear();
            broadcast(level);
            return;
        }
        final Set<UUID> live = new HashSet<>();
        // 同一次扫描里同一个网络只处理一次（多台样板库挂同一网络时）
        final Set<Network> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final long packed : new ArrayList<>(chunks)) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow((int) (packed >> 32), (int) packed);
            if (chunk == null) {
                continue;
            }
            for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                final Network network = nodeNetwork(blockEntity);
                if (network == null || !visited.add(network)) {
                    continue; // 不是网络节点 / 未接网络，或该网络本轮已处理
                }
                // 扫描入口不再限定「自家的序列装配样板库」：任何 RS 网络节点方块实体都行，
                // 这样纯 RS 网络（只有原版自动合成器）里的任务也能被发现并挂起。
                final SequenceAssemblyExecutorBlockEntity executor =
                    blockEntity instanceof SequenceAssemblyExecutorBlockEntity self ? self : null;
                scanNetwork(level, network, executor, blockEntity.getBlockPos(), records, live, now);
            }
        }
        // ---- 清理：任务消失立即移除；过期兜底回收；硬上限丢最旧 ----
        final long nowTick = now;
        records.values().removeIf(record -> !live.contains(record.taskId())
            || isExpired(nowTick, record.lastSeenTick));
        trimToLimit(records);
        broadcast(level);
    }

    private static void trimToLimit(final Map<UUID, Record> records) {
        if (records.size() <= MAX_RECORDS) {
            return;
        }
        final List<Record> ordered = new ArrayList<>(records.values());
        ordered.sort((a, b) -> Long.compare(a.recordedTick, b.recordedTick));
        final int excess = records.size() - MAX_RECORDS;
        for (int i = 0; i < excess && i < ordered.size(); i++) {
            records.remove(ordered.get(i).taskId());
        }
    }

    /**
     * 扫描一个网络：识别网络里<b>所有</b>自动合成任务并更新记录
     * （既包含本模组的「序列装配」任务，也包含 RS 原版自动合成任务）。
     *
     * <p>本方法只做<b>只读分类</b>：把 {@code record.reason} / 只读快照写出来；
     * 「什么时候真的挂起 / 恢复 / 兜底回收」全部由 {@link #advanceSuspendState} 一处决定。</p>
     *
     * @param fallback 触发本次扫描的那台序列装配样板库（若入口不是样板库则为 {@code null}）
     * @param scannerPos 触发本次扫描的那台网络节点坐标（记录锚点兜底用）
     */
    private static void scanNetwork(final ServerLevel level, final Network network,
                                    @Nullable final SequenceAssemblyExecutorBlockEntity fallback,
                                    final BlockPos scannerPos,
                                    final Map<UUID, Record> records, final Set<UUID> live,
                                    final long now) {
        final AutocraftingNetworkComponent autocrafting =
            network.getComponent(AutocraftingNetworkComponent.class);
        if (autocrafting == null) {
            return;
        }
        final List<TaskStatus> statuses = autocrafting.getStatuses();
        if (statuses.isEmpty()) {
            return;
        }
        // 该网络内由「序列装配样板库」注册的全部总样板：产物 → 装配数据 + 宿主样板库坐标
        final Map<ItemResource, PatternRef> patterns = new HashMap<>();
        for (final SequenceAssemblyExecutorBlockEntity executor : executorsOf(level, network, fallback)) {
            final Container inventory = executor.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                final ItemStack stack = inventory.getItem(slot);
                final SequencePatternData.AssemblyData assembly =
                    stack.isEmpty() ? null : SequencePatternData.readAssembly(stack, level.registryAccess());
                if (assembly == null) {
                    continue;
                }
                for (final SequencePatternData.PatternOutput output : assembly.results()) {
                    if (output.stack().isEmpty()) {
                        continue;
                    }
                    patterns.putIfAbsent(ItemResource.ofItemStack(output.stack()),
                        new PatternRef(executor.getBlockPos(), output.stack().copyWithCount(1), assembly));
                }
            }
        }
        // 该网络的「有什么料 / 谁在跑」快照（每个网络算一次，供所有任务共用）
        final Set<ItemResource> present = presentItems(network);
        final boolean intermediateBack = hasIntermediate(present);
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers = chambersOf(network);
        final boolean chamberInFlight = anyChamberInFlight(chambers);

        for (final TaskStatus status : statuses) {
            if (!(status.info().resource() instanceof final ItemResource resource)) {
                continue;
            }
            live.add(status.info().id().id());
            final PatternRef pattern = patterns.get(resource);
            final boolean isNewTask = !records.containsKey(status.info().id().id());
            // 记录的锚点：序列装配任务用样板库坐标（「更换机器」要用）；RS 原版任务用本次扫描入口。
            final Record record = records.computeIfAbsent(status.info().id().id(),
                id -> new Record(id, pattern == null ? scannerPos : pattern.executorPos(), resource, now));
            // <b>用户第 ⑥ 条（本轮新增的第三条路径）：样板库自身掉线时，{@code patterns} 里根本没有它的条目
            // ⇒ {@code pattern == null} ⇒ 旧实现把这条任务降级成「RS 原版任务」（{@code classifyGeneric}），
            // 而通用分类<b>不做离线判定</b>（依赖 RS 给的 REJECTED / LOCKED / NONE_FOUND，序列装配的外部
            // 样板不会给这些）⇒ 断缝后<b>既不挂起也不弹横幅</b>（用户实测「断缝后什么提示都没有」）。
            // 现在把「上一次成功解析到的样板数据」缓存进记录：只要这条任务<b>曾经</b>是序列装配任务，
            // 就永远按序列装配分类 —— 于是样板库 / 执行仓一掉线，离线判据立刻成立、照常挂起并弹横幅。
            if (pattern != null) {
                record.lastAssembly = pattern.assembly();
                record.lastExecutorPos = pattern.executorPos();
                // 「发现即提示」的每 tick 热探针只认这份缓存坐标（见 Record#taskChambers）：
                // 因此它必须与判据同源（同一条样板的执行仓锚点 + 每一步指派的机器）。
                record.taskChambers = taskChambersOf(pattern);
            }
            final boolean sequenceTask = pattern != null || record.lastAssembly != null;
            record.sequence = sequenceTask;
            record.lastSeenTick = now;
            // RS 是否正在把内部暂存还回网络（任务已被取消 / 正在收尾）：这种状态不给「手动挂起」按钮
            // （把它跳过会把 RS 自己的回收一起冻住）。每次扫描刷新，供 actions() 判定。
            record.returning = status.state() == TaskState.RETURNING_INTERNAL_STORAGE;
            record.productIcon = pattern != null ? pattern.product() : itemIcon(resource);
            record.productName = pattern != null
                ? pattern.product().getHoverName().getString()
                : record.productIcon.getHoverName().getString();
            record.amount = status.info().amount();
            if (isNewTask && pattern != null && !record.returning) {
                // 一次性「步骤 → 负责机器」绑定快照（每次任务开始时一条，不每秒刷）：
                // 下次再出现「件被过早收回 / 没人认领」时，一眼就能看出每一步绑到了哪台机器、在不在线。
                // 顺序要点：必须放在上面那几个快照字段写完之后 —— 否则这条日志里的产物名 / 数量
                // 永远是空的（用户实测日志 `binding task=... product= x0`），定位问题时会误导。
                // 另：任务已进入回收态（returning）时不再重复打印 —— 取消后记录被同步移除、下一轮扫描
                // 会重建它，此时若还打印会让人误以为「又来了一条新任务」。
                logBindingSnapshot(level, chambers, pattern, status.info().id().id(), record);
            }

            // 进度指纹每个任务都刷（只用于更新只读快照；挂起后不再据此自动恢复）。
            final boolean progress = progressed(record, status);
            if (record.returning) {
                // RS 自己正在把内部暂存还回网络（玩家按原生「取消」→ TaskImpl.cancel() 当刻置入该
                // 状态，或任务自然收尾）：绝不干预、也绝不挂起，否则会把 RS 自己的回收一起冻住。
                //
                // 用户硬要求「取消时立刻返回、绝不等记录过期（默认约 10 分钟）」：所以这里若发现这条
                // 记录此刻还挂着，就<b>就地同步移除</b> —— 任务已在收尾，监视器上不得再残留
                // 「已挂起 + 继续」的幽灵行 / 幽灵按钮（TaskContainerMixin 那一路是当 tick 即时移除，
                // 本分支是同一口径的兜底：即使某次 step 没被调用到，下一次扫描也必然清干净）。
                if (record.suspendState != SuspendState.RUNNING) {
                    records.remove(record.taskId());
                    continue;
                }
                record.reason = Reason.NONE;
            } else if (record.suspendState != SuspendState.RUNNING) {
                // 已挂起：<b>冻结原因与只读快照</b>，不再重新分类 ——
                // 旧实现正是在这里「观察到进展 / 原因判回 NONE 就当场恢复」，本轮已撤销（见 advanceSuspendState）：
                // 玩家拆机器常常是故意的，还要重新配置，因此必须手动点「继续」才继续。
                // 冻结还保证监视器上的「已挂起：执行器离线 / 缺原料」与已弹出的横幅不会被改写成别的值。
                //
                // <b>2026-10-06：冻结之上加一条只读的「下游是否已经通了」复查（提示链路的恢复边沿）</b>。
                // 它<b>不</b>碰 reason / 快照 / 挂起状态（冻结语义一字不改、也绝无自动恢复），
                // 只用既有的 push_stalled 口径决定「要不要弹那条一次性的收尾横幅」——
                // 否则「堵住时弹过」的提示永远没有下半句，玩家无从判断现在是等待还是继续。
                trackPushStallRecovery(level, record, chambers);
            } else if (sequenceTask) {
                // <b>样板库此刻解析不到（它自己掉线了）时，用记录里缓存的上一份样板数据继续按「序列装配」分类</b>
                // —— 正是这一条让「样板库 / 执行仓掉线」能被判定成 {@link Reason#EXECUTOR_OFFLINE}
                // 并弹横幅（见上面 {@code sequenceTask} 处的说明）。若从未解析到过样板数据，
                // {@code sequenceTask} 为假，仍走通用分类（行为与既有逐字一致）。
                final PatternRef effective = pattern != null
                    ? pattern
                    : new PatternRef(record.lastExecutorPos, record.productIcon, record.lastAssembly);
                classifySequence(record, level, chambers, effective, status, present, statuses,
                    progress, chamberInFlight, intermediateBack, autocrafting);
            } else {
                classifyGeneric(record, status, present, statuses, progress, autocrafting);
            }
        }
    }

    /**
     * 「序列装配」任务的判定（原因只有一处写入口）：
     * <ol>
     *     <li>样板里指派的执行仓坐标不在同网络的执行仓集合里 → {@link Reason#EXECUTOR_OFFLINE}
     *     （确定性信号：方块被拆 / 脱离网络 / 所在区块未加载）；</li>
     *     <li>此刻推不动（无进度、无相关子自动合成在跑、执行仓里没压着料、无中间产物回流，且缺料）
     *     → {@link Reason#MISSING_MATERIAL}；</li>
     *     <li>否则 {@link Reason#NONE}。</li>
     * </ol>
     */
    private static void classifySequence(final Record record, final ServerLevel level,
                                         final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
                                         final PatternRef pattern, final TaskStatus status,
                                         final Set<ItemResource> present, final List<TaskStatus> statuses,
                                         final boolean progress, final boolean chamberInFlight,
                                         final boolean intermediateBack,
                                         final AutocraftingNetworkComponent autocrafting) {
        final List<SyncAssemblyAlertsPacket.OfflineStep> offline = offlineSteps(level, chambers, pattern);
        // <b>连续两次确认（本轮修正）</b>：RS 的网络图重建 / 区块加载的瞬时态会让「指派的那台执行仓
        // 不在本网络的执行仓集合里」闪一下，旧实现一次观察就判定 EXECUTOR_OFFLINE 并<b>挂起任务</b>——
        // 用户实测「跑着跑着任务就不动了」，而日志上这正是 `binding ... chamberExists=true` 几秒后
        // 又 `watchdog reason=EXECUTOR_OFFLINE`。这里要求「上一次复查也报了同样的离线步骤」才判定，
        // 因此真实掉线只晚 1 秒被发现，而瞬时抖动不会再误挂起任务（也不会因此把成品冻在任务暂存里）。
        final boolean offlineConfirmed = !offline.isEmpty()
            && (sameOfflineSteps(offline, record.offlineSteps)
                || record.offlineStrikes >= OFFLINE_CONFIRM_STRIKES);
        // 连续确认计数（本轮新增）：本次没有任何离线证据 ⇒ 归零；有 ⇒ +1（饱和）。
        record.offlineStrikes = offline.isEmpty()
            ? 0 : Math.min(record.offlineStrikes + 1, OFFLINE_CONFIRM_STRIKES_MAX);
        record.offlineSteps = offline;
        if (!offline.isEmpty()) {
            // 机器真的不在网络里（被拆 / 断线 / 区块未加载）⇒ 这不是「输出阻塞」，横幅必须走掉线文案。
            record.pushStalled = false;
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.trace("trace-offline@" + record.taskId(),
                    RsccAssemblyDebug.traceLine("item=" + record.productName, record.amount,
                        "watchdog",
                        offlineConfirmed ? "executor_offline" : "executor_offline_pending",
                        "task=" + record.taskId(),
                        "offlineSteps=" + offlineSummary(offline)
                            + " chambersOnline=" + chambers.size(), -1L));
            }
            if (offlineConfirmed) {
                record.materialIcons = List.of();
                record.materialNames = List.of();
                record.materialTotal = 0;
                record.reason = Reason.EXECUTOR_OFFLINE;
                return;
            }
            // 首次观察：不判定（瞬时态），但也不当成「缺料 / 无进展」——直接放行，交给下一次复查
            record.reason = Reason.NONE;
            return;
        }
        // 「这条任务还差几件」（= 目标量 − 已交付量，交付量取 stored + crafting，与执行舱同一口径）：
        // 用来判定「起步原料是否已经被在制件顶掉一份」（见 missing 的说明）。
        final long delivered = deliveredAmount(status);
        final long remaining = Math.max(0L, Math.max(1L, status.info().amount()) - delivered);
        final List<ItemStack> needed = neededItems(level, pattern.assembly());
        final List<ItemStack> missing = missing(needed, present, chambers, pattern.assembly(), remaining);
        record.materialIcons = new ArrayList<>(missing.subList(0, Math.min(missing.size(), MAX_LISTED)));
        record.materialNames = record.materialIcons.stream()
            .map(stack -> stack.getHoverName().getString()).toList();
        // 横幅第 3 行只列前 MAX_LISTED 种，超出部分要用「等 N 种」概括 —— 这里必须记下<b>总数</b>，
        // 否则那一段永远走不到（曾漏赋值，缺料多于 3 种时横幅会少说一部分原因）。
        record.materialTotal = missing.size();
        // <b>2026-10-05 新增：整单口径的原料缺口</b>（用户实测：「下单 64 个，却只提示缺少一个大齿轮」）。
        //
        // 为什么会有这个落差：{@link #missing} 的返回是「<b>去重后每种缺一件</b>」——
        // 大齿轮网络里有 1 个 ⇒ 它不算缺，于是永远只报「缺 1 个」。
        // 但一条 64 件的订单要 64 个大齿轮（每件 1 个），玩家想看到的是「还差 63 个」。
        // 因此这里另算一份<b>整单缺口</b>：每件需要量 × 剩余件数 − 现有量。只写诊断字段。
        record.materialDeficit = materialDeficit(needed, present, remaining);
        final boolean subTaskRunning = relatedSubTaskRunning(statuses, status, needed);
        // <b>用户第 3 条（本轮）：缺的这几项若「可以被自动合成」⇒ 不算缺料、静默等待（不报缺、不挂起）</b>。
        // 用户原话：「某项资源它是可以被自动合成的，就不要显示缺少材料了，就一直等它获得材料再醒（继续）」。
        // 判据 = 终端里有以它为输出的样板（= 它可以被自动合成，或已有它的合成任务在跑）。只有「既没有在跑的
        // 合成任务、也不可自动合成」才真正算缺料。取不到自动合成组件时一律为 false（照旧按缺料判定，
        // 绝不因为判不出来而把真实缺料静默掉）。
        final boolean allMissingAutoCraftable = !missing.isEmpty()
            && missing.stream().allMatch(stack -> isAutoCraftable(autocrafting, stack));
        // 可推进 = 进度在动 / 相关子自动合成在跑 / 执行仓里还压着料 / 中间产物回流 / 一项原料都不缺。
        //
        // 为什么最后一项是「missing 为空」而不是「缺的比需要的少」：序列装配的原料是逐项消耗的，
        // 「一部分原料还在、另一项缺失」恰恰就是「原料缺少至无法继续」的<b>典型形态</b>，
        // 也正是用户验收标准里第三行「由于 &lt;原料&gt; 缺少或自动合成失败」要覆盖的情形；
        // 若把它当成可推进，横幅在这类任务上就永远不会弹。只有「一项都不缺」才说明
        // 卡住的原因不是缺料（那时不该走缺料文案）。
        // <b>本轮新增（用户第 1 条：刚好够却仍报缺）</b>：这条任务的交付已经满（remaining ≤ 0）——
        // 需求已达成，此刻任何「尾巴缺口」都是噪音，绝不报缺（与执行舱 orderDeliveredInFull 同一口径）。
        // <b>用户第 2/3 条：机器被占用 / 下游满 / 该步没有任何机器认领 ⇒ 必须给明确提示，不能干等。</b>
        // 这两种持续状态由执行舱记录（{@code pushStalledOnDestination}，3 秒窗口），这里归类成
        // {@link Reason#EXECUTOR_OFFLINE}（该枚举文档本就含「或<b>拒绝接收</b>」）⇒ 到达阈值后走既有的
        // 「挂起 + 横幅」通道，横幅会明确报出「第 N 步 / 哪台执行仓 + 原因」。
        // 实测依据：{@code bus_skip … reason=DESTINATION_DOES_NOT_ACCEPT/machine_full} 每 5 秒 11~13 次、
        // {@code reject {item=create:incomplete_track step=1} stepOwner=NOBODY} 每 5 秒 8 次，
        // 而整场日志<b>没有任何 {@code watchdog suspend}</b> —— 旧判据里「推不动」既不算缺料也不算离线，
        // 于是 reason 恒为 NONE：既不挂起也不弹横幅，玩家看到的就是「东西被抽干了、机器占着、毫无提示地干等」。
        // <b>本轮修正（用户实测：所有序列装配都报"设备掉线"）</b>：旧实现 {@code anyChamberPushStalled(chambers)}
        // 扫的是<b>整个网络里所有执行仓</b> —— 网络里<b>任意一台</b>执行仓被下游顶住（pushStalledOnDestination），
        // <b>所有</b>序列装配任务的 reason 都被设成 EXECUTOR_OFFLINE，横幅统一显示「设备掉线」。
        // 用户原话：「所有的这一个 就是血液装配的，他妈他都显示我设备掉线」—— 实际上机器都在，只是<b>别的任务</b>
        // 把某台仓的下游堵了。这里只检查<b>本任务实际用到</b>的执行仓（每一步 machinePos + executorPos），
        // 其他仓堵不堵与本任务无关。
        final StallHit stall = taskChamberPushStalled(chambers, pattern);
        final boolean pushStalled = stall != null;
        final boolean canAdvance = !pushStalled
            && (progress || subTaskRunning || chamberInFlight || remaining <= 0L
                || intermediateBack || missing.isEmpty() || allMissingAutoCraftable);
        // <b>2026-10-05：把「推不动」拆成两种诚实的 reason（不再一律叫「执行器掉线」）</b>
        //   * 某步没有任何在线执行仓认领 → EXECUTOR_OFFLINE（确实是设备 / 样板的问题）；
        //   * 下游机器 / 置物台持续拒收 → OUTPUT_BLOCKED（机器满 / 不接受，机器本身在线）。
        // 旧实现把两者压成 EXECUTOR_OFFLINE，于是「机械手和置物台明明是空的」也会被告知
        // 「执行器掉线」—— 玩家据此去拆机器 / 换样板，方向完全错。
        if (pushStalled && stall.reason() == SequenceExecutionChamberBlockEntity.StallReason.STEP_OWNER_MISSING) {
            record.reason = Reason.EXECUTOR_OFFLINE;
        } else if (pushStalled) {
            record.reason = Reason.OUTPUT_BLOCKED;
        } else {
            // 走到这里说明<b>确实没有</b>「推不动」⇒ 才允许落到缺料档。
            // （这段写法被自检以字面量锚定，改动前请先看 selfcheck_assembly_watchdog / round21）
            record.reason = canAdvance ? Reason.NONE : Reason.MISSING_MATERIAL;
        }
        // 只在「机器还在、只是下游拒收」时才置 true —— 横幅据此走「输出阻塞」文案而非「设备掉线」。
        // offline 不为空时上面已置 false 并 return；这里 offline 必为空，pushStalled=true 即输出阻塞。
        record.pushStalled = pushStalled;
        record.stallDetail = stall;
    }

    /**
     * 只读：本任务<b>实际用到</b>的执行仓里是否有任意一台处于「推不动」状态。
     * <p>只检查本任务涉及的执行仓（每一步的 machinePos + executorPos），不检查网络里其他仓 ——
     * 否则别的任务把仓堵了，本任务也会被误判成 EXECUTOR_OFFLINE（用户实测：所有序列装配都报"设备掉线"）。</p>
     *
     * <h2>2026-10-05：从布尔值升级为「带原因的命中」（实机取证的直接产物）</h2>
     * <p>用户下单精密构件 x1，任务 2 秒内被判 {@code EXECUTOR_OFFLINE} 并挂起，横幅说「执行器掉线」，
     * 而机械手 / 置物台全空。快照证明 {@code offlineSteps="-"}、{@code missingMaterials=0}、
     * 类别 {@code estimated=5}（料够）—— 真正成立的是这个「推不动」，而它当时把
     * 「下游机器满」与「某步没有机器认领」压成了同一个布尔值，横幅只会说「掉线」。
     * 现在返回<b>哪台仓、什么原因（{@code StallReason}）、涉及哪个资源</b>，
     * 于是「机器满」与「缺机器 / 缺样板」可以被报成两种不同的原因与两种文案。</p>
     */
    @Nullable
    private static StallHit taskChamberPushStalled(
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
        final PatternRef pattern) {
        final Set<BlockPos> taskPositions = new HashSet<>();
        taskPositions.add(pattern.executorPos());
        for (final SequencePatternData.UnitEntry unit : pattern.assembly().units()) {
            final BlockPos pos = unit.machinePos();
            if (pos != null) {
                taskPositions.add(pos);
            }
        }
        for (final BlockPos pos : taskPositions) {
            final SequenceExecutionChamberBlockEntity chamber = chambers.get(pos);
            if (chamber == null) {
                continue;
            }
            // <b>2026-10-06：提示层改用「任意一个目的地拒收」的只读入口。</b>
            //
            // {@code pushStalledOnDestination()} 要求 <b>≥2 台</b>目的地同时拒收（见
            // SequenceExecutionChamberBlockEntity#rscc$anyDestinationStuck 的说明），
            // 而玩家现场只有<b>一台</b>冲压机 / 一个置物台 ⇒ 它恒为 false ⇒
            // 「下游被堵」的横幅<b>永远不弹</b>（实测：日志里 push_stalled 与 machine_full
            // 一直在刷，而快照 shortage.banners.sent 整场为空）。
            //
            // 这里改成：<b>两者取或</b>。整仓停手仍按原口径（不放宽），
            // 但<b>提示</b>只要有一个目的地堵着就成立。
            if (!chamber.pushStalledOnDestination() && !chamber.rscc$anyDestinationStuck()) {
                continue;
            }
            final SequenceExecutionChamberBlockEntity.StallReason reason = chamber.rscc$stallReason();
            return new StallHit(pos, reason == null
                ? SequenceExecutionChamberBlockEntity.StallReason.DESTINATION_REFUSED : reason,
                chamber.rscc$stallResource());
        }
        return null;
    }

    /** 一次「推不动」的命中（台仓 + 原因 + 资源名）；只读诊断数据。 */
    private record StallHit(BlockPos chamberPos, SequenceExecutionChamberBlockEntity.StallReason reason,
                            String resource) {
    }

    /**
     * <b>「发现即提示」的进入边沿</b>（2026-10-06 新增；每 tick 一次，纯只读）。
     *
     * <h2>它解决什么（用户原话）</h2>
     * <p>「他是有延迟反应……他应该在那个发现了的时候就直接弹提示」。旧链路上，提示要穿过三处等待：
     * 网络扫描节拍（{@value #SCAN_INTERVAL_TICKS} tick = 1 秒）、该原因对应的
     * {@code stallTicks} 阈值（2 秒）、以及序列装配掉线还要多一次复查 —— 合计最多约 3 秒，玩家观感
     * 就是「延迟反应」。本方法把进入边沿判定提前到<b>第一次确凿发现</b>：只对<b>扫描时缓存的</b>
     * 执行仓坐标做方块实体查表（无网络图遍历 / 无存储扫描），因此可以每 tick 跑。</p>
     *
     * <h2>必要的防抖（为什么不是 0 tick）</h2>
     * <p>见 {@link #PUSH_NOTICE_NO_OWNER_TICKS}（确定性信号，0.5 秒）与
     * {@link #PUSH_NOTICE_ZERO_PROGRESS_TICKS}（「拒收 <b>且</b> 零进展」才算堵，3 秒）——
     * 后者是本轮修掉「机器正常加工也弹堵着了」的判据，见
     * {@link #stallProgressOf}。两者都是<b>秒级</b>，不改变「发现即提示」的要求。</p>
     *
     * <h2>边沿语义（P3：同一状态只弹一次）</h2>
     * <p>弹过就清 {@link Record#pushNoticeArmed}，而<b>重新武装只由
     * {@link #trackPushStallRecovery} 的正向探针</b>（工位真的空出来）触发 —— 因此：
     * 持续堵 0 条重复；堵 → 通 → 稳定后各 1 条；再堵 → 再通 → 各 1 条；抖动 0 条；
     * <b>玩家点「继续」后仍堵着 0 条</b>（旧的 {@code notified} 复位语义正是在这里产生「多发」）。</p>
     */
    private static void advancePushNotice(final ServerLevel level, final Record record, final long now) {
        if (!pushNoticeHandled(record) || record.suspendState != SuspendState.RUNNING) {
            resetPushObservation(record);
            return;
        }
        // <b>新任务的第一个扫描周期是「证据交接期」</b>：执行仓会在「新订单开始」边沿清空上一段
        // 空闲期留下的推不动证据（{@code clearPushStallEvidence}，见其 javadoc 里那条自锁复现链），
        // 而那次清理最晚发生在门控的下一个复查节拍（{@code BUS_GATE_INTERVAL_TICKS = 10} tick）。
        // 这里给一个同等量级的宽限期，避免拿「上一个订单留下的拒收时间戳」去判定一条刚下单的任务
        // —— 那正是 2026-10-05 修掉的「新下单 2 秒内被判输出阻塞并挂起」。
        if (now - record.recordedTick() < SCAN_INTERVAL_TICKS) {
            resetPushObservation(record);
            return;
        }
        final StallHit hit = hotPushStallHit(level, record);
        if (hit == null) {
            // 探针不再命中 ⇒ 当前这段观察整段作废：连「逐格工位指纹」也一起忘掉，
            // 于是「拒收 → 通 → 又拒收」之间的抖动绝不可能被拼成一段「持续」。
            resetPushObservation(record);
            // 「通了 ⇒ 重新武装」只由 trackPushStallRecovery 的正向探针负责：挂起后本仓被冻结、
            // 已被清空，它根本不再尝试推送 ⇒ 「这里的证据不见了」什么也不能证明（旧实现正是
            // 据此在每次挂起后约 5 秒误报一次「下游已经能收料了」）。
            return;
        }
        // 热探针确实命中了：从这一刻起老路径（扫描 + stallTicks 阈值）交棒 —— 否则同一次堵塞
        // 会被弹第二条横幅。判据取「真的命中过」而不是「有资格命中」，避免两条路都不弹。
        record.pushNoticeHot = true;
        if (record.pushNoticeSinceTick < 0L) {
            record.pushNoticeSinceTick = now;
            // <b>窗口起点</b>：逐格基线必须从这一刻的内容开始记（见 Record#pushNoticeStationBaselines）。
            // 先清再记 ⇒ 绝不把上一段观察（或上一个工位）的旧指纹当成这一段「没变」的证据。
            record.pushNoticeStationBaselines.clear();
        }
        if (!record.pushNoticeArmed) {
            return; // 这一段堵塞已经提示过：同一状态只弹一次
        }
        final boolean noOwner =
            hit.reason() == SequenceExecutionChamberBlockEntity.StallReason.STEP_OWNER_MISSING;
        final int window;
        if (noOwner) {
            // 「这一步没有任何在线执行仓认领」是<b>确定性</b>信号（见 PUSH_NOTICE_NO_OWNER_TICKS）：
            // 它和「下游忙不忙 / 这条线动没动」无关，照旧 0.5 秒确认，不参与「零进展」判定。
            window = PUSH_NOTICE_NO_OWNER_TICKS;
        } else {
            // <b>本轮修复点</b>：目的地拒收只是「正常状态」，必须再叠加「零进展」才是堵
            //（旧实现只看「窗口内一直在拒收」⇒ 机器正常加工必然误报，见
            // PUSH_NOTICE_ZERO_PROGRESS_TICKS 的复现说明）。
            window = PUSH_NOTICE_ZERO_PROGRESS_TICKS;
            final StallProgress progress = stallProgressOf(level, record, hit.chamberPos());
            // 诊断（只读）：把「为什么没弹」也留在日志里 —— 状态翻转才打一条，不刷屏。
            // 实机复核时会看到 state=MACHINE_ON_OUR_PIECE（机器正加工我们那件）
            // 或 state=OTHER_LINE_PROGRESS（工位上的东西在动：另一条配方的件被做完 / 被取走）。
            if (RsccAssemblyDebug.isEnabled()) {
                RsccAssemblyDebug.transition("noticeprogress@" + record.taskId, progress.name(),
                    "watchdog notice-progress task=" + record.taskId
                        + " state=" + progress.name()
                        + " window=" + window
                        + " stations=" + record.pushNoticeStationBaselines.size()
                        + " proof=" + (record.pushNoticeStationAt == null
                            ? "-" : RsccAssemblyDebug.at(record.pushNoticeStationAt))
                        + " at=" + RsccAssemblyDebug.at(record.executorPos));
            }
            if (progress == StallProgress.PROGRESS || progress == StallProgress.OTHER_LINE_PROGRESS) {
                // 这条线（或压在这些工位上的那些件）在窗口内动过 ⇒ 「零进展」不成立：
                // 窗口从现在<b>重新起算</b>（不是不判，而是重新数），逐格基线随之重取 ——
                // 于是同一个变化不会被反复当成「刚刚发生的进展」，而「刚刚还推成功过的那一格」
                // 仍然留在观察集合里（见 Record#pushNoticeRebaseline）。
                record.pushNoticeSinceTick = now;
                record.pushNoticeRebaseline = true;
                return;
            }
            if (progress != StallProgress.NONE) {
                // MACHINE_ON_OUR_PIECE：工位上压着的正是我们推进去的那件在制件（步序没倒退）
                //   ⇒ 机器正拿着它加工，这不是堵塞；
                // UNKNOWN：方块不在 / 没有拒收坐标 / 拿不到物品能力 ⇒ 判不出来。
                // 两者都<b>不</b>构成「拒收 + 零进展」：不弹，也不累积窗口
                //（判不出来时宁可不说，也不给玩家一条假的「堵了」）。
                resetPushObservation(record);
                return;
            }
        }
        if (now - record.pushNoticeSinceTick < window) {
            return;
        }
        record.pushNoticeArmed = false;
        resetPushObservation(record);
        record.stallDetail = hit;
        record.pushStalled = true;
        // 判据互斥（与 classifySequence 同一口径）：这条原因是「推不动」⇒ 就不是「步骤掉线」。
        record.offlineSteps = List.of();
        record.reason = hit.reason() == SequenceExecutionChamberBlockEntity.StallReason.STEP_OWNER_MISSING
            ? Reason.EXECUTOR_OFFLINE : Reason.OUTPUT_BLOCKED;
        // 与既有路径完全一致的两件事：挂起（不再抽料 / 不再投料 / 中间件原地冻结）+ 弹一次横幅。
        suspendRecord(record, record.reason, now);
        sendBanner(level, record);
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event("watchdog notice task=" + record.taskId
                + " step=" + stalledStepOf(record) + " cause=" + hit.reason()
                + " resource=" + hit.resource() + " reason=" + record.reason
                + " window=" + window + " tick=" + record.currentTick
                + " at=" + RsccAssemblyDebug.at(record.executorPos));
        }
    }

    /**
     * <b>作废当前这段「零进展」观察</b>：连窗口起点（{@link Record#pushNoticeSinceTick}）与
     * <b>逐格</b>内容基线（{@link Record#pushNoticeStationBaselines}）一起清掉。
     *
     * <p>调用点即「这段观察不再成立」的全部情形：热探针不再命中（下游通了 / 证据过期）、
     * 判不出结论（UNKNOWN）、工位上是我们自己的在制件（MACHINE_ON_OUR_PIECE）、
     * 已挂起或尚未接管（{@code pushNoticeHandled} / 证据交接期）、以及<b>刚刚弹过</b>。
     * 两件事必须<b>一起</b>清：只清窗口不清基线，下一段观察会把上一段的旧内容当成「没变」的证据；
     * 只清基线不清窗口，则「工位一直在换件」会被误算成「持续堵了 3 秒」。</p>
     */
    private static void resetPushObservation(final Record record) {
        record.pushNoticeSinceTick = -1L;
        record.pushNoticeStationAt = null;
        record.pushNoticeRebaseline = false;
        record.pushNoticeStationBaselines.clear();
    }

    /**
     * 这条记录是否由每 tick 的热探针负责「推不动」两族（序列装配 + 已缓存本任务用到的执行仓坐标）。
     * <p>判不出来（旧样板 / 缓存缺失 / RS 原版任务）时返回 {@code false}，那条记录照旧走
     * 「扫描 + {@code stallTicks} 阈值」的老路 —— 绝不因为新链路判不出来就丢掉提示。</p>
     */
    private static boolean pushNoticeHandled(final Record record) {
        return record.sequence && record.lastAssembly != null && !record.taskChambers.isEmpty();
    }

    /**
     * <b>只读</b>：用<b>缓存的</b>执行仓坐标做一次廉价热探针 —— 判定口径与
     * {@link #taskChamberPushStalled} 逐字一致（整仓停手 {@code pushStalledOnDestination}
     * 与「任意一个目的地拒收」{@code rscc$anyDestinationStuck} 取或，整仓口径不放宽）。
     */
    @Nullable
    private static StallHit hotPushStallHit(final ServerLevel level, final Record record) {
        for (final BlockPos pos : record.taskChambers) {
            if (!(level.getBlockEntity(pos) instanceof SequenceExecutionChamberBlockEntity chamber)) {
                continue;
            }
            if (!chamber.pushStalledOnDestination() && !chamber.rscc$anyDestinationStuck()) {
                continue;
            }
            final SequenceExecutionChamberBlockEntity.StallReason reason = chamber.rscc$stallReason();
            return new StallHit(pos, reason == null
                ? SequenceExecutionChamberBlockEntity.StallReason.DESTINATION_REFUSED : reason,
                chamber.rscc$stallResource());
        }
        return null;
    }

    /**
     * <b>只读</b>：本任务实际用到的执行仓坐标（样板库锚点 + 每一步指派的机器）。
     * <p>与 {@link #taskChamberPushStalled} 的候选集合同源，取不可变副本；顺序稳定（先锚点、后按步序），
     * 因此热探针的命中结果在两次扫描之间是确定的。</p>
     */
    private static List<BlockPos> taskChambersOf(final PatternRef pattern) {
        final List<BlockPos> positions = new ArrayList<>();
        positions.add(pattern.executorPos().immutable());
        for (final SequencePatternData.UnitEntry unit : pattern.assembly().units()) {
            final BlockPos pos = unit.machinePos();
            if (pos != null && !positions.contains(pos)) {
                positions.add(pos.immutable());
            }
        }
        return List.copyOf(positions);
    }

    /**
     * 「目的地拒收」这条判据的几种结论（<b>只有</b> {@link #NONE} 才允许弹「堵了」；
     * 判据与窗口推导见 {@link #PUSH_NOTICE_ZERO_PROGRESS_TICKS}）。
     */
    private enum StallProgress {
        /** 窗口内既没有成功推送、工位也一个 tick 都没变过 ⇒ 满足「拒收 + 零进展」。 */
        NONE,
        /** 窗口内观察到<b>本仓这条线</b>在推进（本仓又成功推入过这些工位之一）⇒ 窗口重新起算。 */
        PROGRESS,
        /**
         * 窗口内观察到<b>「工位上那件东西」在动</b>（件变了 / 件数变了 / 步序推进了 / 被取走了）
         * —— 换句话说是<b>机器在忙（哪怕忙的是另一条配方）</b>，不是堵。
         *
         * <p>2026-10-06 新增。它与 {@link #PROGRESS} 在<b>窗口语义上完全一样</b>
         * （都让窗口重新起算，都不弹），单独留一个名字只是为了让日志直接说出
         * 「没弹是因为工位在动，而不是因为我们这条线推成功了」——
         * 这正是这一轮实机取证里最需要看清的一件事。</p>
         */
        OTHER_LINE_PROGRESS,
        /** 工位上压着的正是「本仓推入、步序还没推进」的在制件 ⇒ 机器正在加工它，不是堵塞。 */
        MACHINE_ON_OUR_PIECE,
        /** 判不出来（方块不在 / 没有拒收坐标 / 拿不到物品能力）⇒ 不据此弹「堵了」。 */
        UNKNOWN
    }

    /**
     * <b>只读</b>：「目的地持续拒收」这条线上，最近一个窗口内<b>到底有没有进展</b> ——
     * 这是本轮误报（机器正常加工却弹「堵着了」）的修复点。
     *
     * <h2>2026-10-06 第三次修正：判据必须<b>逐格</b>看，不能只看「最近拒收的那一格」</h2>
     * <p><b>实机取证（用户实测「精密构件与列车轨道同时进行时又莫名暂停，点继续就正常」）</b>：
     * 快照 {@code run/rscc_diag/20261006-125123/snapshot.json} 里那台执行仓
     * {@code (-5,-60,6)}（名 {@code USE}，{@code create:deploying}）的 {@code ownedSteps} 同时包含
     * {@code create:sequenced_assembly/precision_mechanism#0..2} 与
     * {@code create:sequenced_assembly/track#0..1} —— <b>两条配方共用同一台（同一批）机器</b>；
     * 该仓的计数 {@code (-7,-60,5)|create:precision_mechanism|collect=5} 与
     * {@code (-7,-60,5)|create:incomplete_track|collect=12}（{@code -7,-60,6} 同样两者都有）
     * 进一步证明<b>两台置物台都在轮流做两条配方的件</b>。日志（{@code 12:49:24.671}）里两条任务
     * 同时被判 {@code step=0 cause=DESTINATION_REFUSED resource=create:golden_sheet}
     * （{@code 精密构件 x10} / {@code 列车轨道 x10}，同一 tick、同一 {@code stall=53}），
     * 而在此之前的两分钟里这条线其实一直在出货：{@code 12:48:59.767 / 12:49:06.965 / 12:49:13.718}
     * 三次 {@code take_from_machine create:precision_mechanism}，
     * {@code 12:48:59.771 / 12:49:06.967 / 12:49:14.170 / 12:49:23.614} 四次
     * {@code bus_push create:golden_sheet ... EXPORTED/ok}。</p>
     *
     * <p><b>为什么旧判据会误判</b>：{@code rscc$latestRefusedTarget()} 只给「<b>时间戳最新</b>的那一格」。
     * 两条总线（{@code exporter@(-6,-60,5)} / {@code @(-6,-60,6)}）轮流被拒
     * （日志：{@code 12:49:21.364 → machine@(-7,-60,5)} 与 {@code 12:49:22.711 → machine@(-7,-60,6)}
     * 两次 {@code DESTINATION_DOES_NOT_ACCEPT/machine_full}），于是「最新的那一格」每隔一两秒就换一台 ⇒
     * 旧实现那两个「同一格上一 tick 长什么样」的字段次次对不上（换格即作废）⇒
     * ①{@code (-7,-60,5)} 上占位的 {@code create:cogwheel} 被本仓自愈收走
     * （{@code 12:49:21.667 unblock_recovered}）、②{@code (-7,-60,6)} 上的
     * {@code create:andesite_alloy} 被取走（{@code 12:49:22.715 take_from_machine}）、
     * ③{@code (-7,-60,5)} 在 {@code 12:49:23.614} 又成功收下了一件 {@code create:golden_sheet}
     * —— 这三件都能证明「机器在忙」，却一件都没被算成进展。挂起后玩家点「继续」一切正常，
     * 与判据结论正好相反。</p>
     *
     * <h2>现在的进展信号（全部只读；一律取<b>本段窗口内出现过的全部拒收工位</b>，逐格判定）</h2>
     * <ol>
     *     <li>{@code rscc$lastPushOkAge(target)}：本仓向<b>那一格</b>的最近一次成功推送时刻
     *     （记账 = 在制件登记表，由每一次成功推料写入）。刻意<b>不</b>用全仓的
     *     {@code lastSuccessfulPushAt}：「别处推成功过」不能证明<b>这一格</b>动过，用它会把
     *     「谁堵了就报谁」重新耦合回整仓闸门（而整仓闸门按用户要求不许放宽）；</li>
     *     <li>工位内容指纹（每一格的件 / 件数 / 进度步，见 {@link #stationFingerprintOf}）与
     *     <b>窗口起点</b>的基线（{@link Record#pushNoticeStationBaselines}）逐格比对：
     *     换了一件、变成空、步序推进过 ⇒ 都是进展。</li>
     *     <li>{@code rscc$registeredUnitAt(target)} + 工位实际内容：那件东西是不是
     *     「我们推入的那件在制件（含已在原地推进的步）」（见 {@link #holdsOurInFlightPiece}）
     *     ⇒ 机器正拿着它在加工。</li>
     * </ol>
     *
     * <h2>为什么这样既不放跑「真堵」、又不误报「机器在忙」</h2>
     * <ul>
     *     <li><b>工位长期空着还被拒收</b>：那些格子在窗口里从没出现过任何变化、也从没成功收下过
     *     我们的推送 ⇒ 每格基线都等于现状 ⇒ 仍然在 {@value #PUSH_NOTICE_ZERO_PROGRESS_TICKS} tick
     *     （3 秒）判成堵 ⇒ 秒级挂起（要求 2 的第一种形态）；</li>
     *     <li><b>件长期不动、且无人推成功</b>：占位那件一个 tick 都没变过、本仓向该格的成功推送也
     *     早已中断 ⇒ 同样 3 秒判堵（要求 2 的第二种形态）；</li>
     *     <li><b>机器在忙（无论忙的是谁）</b>：那一格的内容必然在动（另一条配方的过渡件被做完 /
     *     被取走 / 又收下一件）或本仓又成功推入过 ⇒ 每次都被算成进展并把窗口重新起算 ⇒
     *     只要机器还在动，窗口永远凑不满 60 tick（要求 1）。</li>
     * </ul>
     *
     * <h2>拿不到的信号（如实说明）</h2>
     * <p>Create <b>不</b>对外提供「这台机器正在加工第几步 / 还要多久」的只读接口：它推进物品
     * {@code step} 的唯一位置是一步<b>做完</b>时（{@code SequencedAssemblyRecipe#advance}），
     * 且那个 {@code progress} 字段本身就是按步序算出来的
     * （{@code new SequencedAssembly(id, step + 1, (step + 1f) / (sequence.size() * loops))}）。
     * 因此加工期间物品上的进度步<b>本来就不会动</b> —— 靠「步序有没有推进」区分不出
     * 「加工中」与「堵死」。最可靠的替代读法就是第 3 条：<b>不是「看不到进展」，而是「此刻我们那件
     * 正被机器持有、这一步还没做完」</b>。这与本模组既有口径
     * （{@code RsccChamberImportStrategy#inTransitionResidence}：步序未推进 ⇒ 连收回侧都不许碰它，
     * 且明确写着「任何固定时间窗口都是错的判据」）是同一件事，不新增第二套真源。</p>
     *
     * <p><b>仍然存在的边界（如实说明）</b>：某一步的投入物是<b>不带进度组件的裸原料</b>
     * （例如 {@code create:golden_sheet}）时，登记表里 {@code step = -1}
     * （见 {@code RsccChamberExportStrategy#noteUnitPushed} 的调用点），第 3 条对它不成立；
     * 若某台机器<b>只做这一件、且超过 3 秒毫无可见变化</b>，本判据仍会判堵。
     * 这条边界本轮<b>没有</b>改（改动它需要读机器的动力状态，属于另一件事），
     * 因此保留在这里以免下一轮重复踩点。</p>
     *
     * <p>本方法只读：只写 {@link Record} 里用于比对「变没变」的字段，绝不搬运 / 不修改世界状态、
     * 也不改挂起状态。</p>
     */
    private static StallProgress stallProgressOf(final ServerLevel level, final Record record,
                                                 final BlockPos chamberPos) {
        if (!(level.getBlockEntity(chamberPos) instanceof SequenceExecutionChamberBlockEntity chamber)) {
            return StallProgress.UNKNOWN;
        }
        // 只看「此刻真的还在拒收」的那些格子（不是 lastRefusedTarget：成功推送不会清它，可能是旧目标）。
        final List<BlockPos> refused = chamber.rscc$refusedTargets();
        if (refused.isEmpty() && record.pushNoticeStationBaselines.isEmpty()) {
            return StallProgress.UNKNOWN;
        }
        // 观察集合 = 此刻仍在拒收的格子 ∪ 本段窗口里「曾经拒收过我们」的格子。
        // 后者必须留着：一次成功推送会把该格从 refusedTargetAt 里删掉（rscc$notePushSucceeded），
        // 而「它刚刚收下了我们推的料」恰恰是「机器在忙」最硬的证据 —— 旧实现只盯「最新的那一格」，
        // 一被删就换格，于是这份证据永远看不见（见本方法 javadoc 的实机取证）。
        final Set<BlockPos> watched = new LinkedHashSet<>(refused);
        watched.addAll(record.pushNoticeStationBaselines.keySet());
        // 窗口刚重新起算的那一 tick：把每一格的基线整体刷成当前内容（保留键集），这一 tick 不算「变化」。
        final boolean rebaseline = record.pushNoticeRebaseline;
        record.pushNoticeRebaseline = false;
        boolean ours = false;
        boolean changed = false;
        boolean accepted = false;
        int readable = 0;
        BlockPos changedAt = null;
        for (final BlockPos target : watched) {
            // 口径与执行舱自己的堵塞自愈 / 恢复探针同源（RsccChamberImportStrategy#itemHandlerAt）。
            final IItemHandler handler = RsccChamberImportStrategy.itemHandlerAt(level, target);
            if (handler == null) {
                continue; // 判不出来（区块未加载 / 这一格不收物品）⇒ 这一格不表态
            }
            final List<ItemStack> slots = new ArrayList<>(handler.getSlots());
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                slots.add(handler.getStackInSlot(slot));
            }
            readable++;
            // 信号③（否决项）：工位上压着的正是「我们推进去的那件在制件」⇒ 机器在加工它。
            // 放在最前面：Create 只在一步做完时才推进 step，所以这与「这一步要多久」无关。
            if (!ours && holdsOurInFlightPiece(slots, chamber.rscc$registeredUnitAt(target))) {
                ours = true;
                record.pushNoticeStationAt = target.immutable();
            }
            // 信号②（本轮从「一台」扩到「逐格」）：这一格的内容 / 件数 / 进度步有没有变过。
            // 基线取「本段窗口起点（或窗口刚重算）时这一格的样子」（见 Record#pushNoticeStationBaselines）。
            final String fingerprint = stationFingerprintOf(slots);
            final String baseline = record.pushNoticeStationBaselines.get(target);
            if (rebaseline || baseline == null) {
                record.pushNoticeStationBaselines.put(target.immutable(), fingerprint);
            } else if (!changed && !fingerprint.equals(baseline)) {
                changed = true;
                changedAt = target.immutable();
            }
            // 信号①（本轮同样从「一台」扩到「逐格」）：本仓向这一格的最近一次成功推送是否还在窗口内
            // （还在 ⇒ 这条线明显是通的；而且它天然覆盖「别的配方刚刚被这一格收下」这件事）。
            final long pushOkAge = chamber.rscc$lastPushOkAge(target);
            if (pushOkAge >= 0L && pushOkAge < PUSH_NOTICE_ZERO_PROGRESS_TICKS) {
                accepted = true;
            }
        }
        if (readable == 0) {
            return StallProgress.UNKNOWN;
        }
        if (ours) {
            return StallProgress.MACHINE_ON_OUR_PIECE;
        }
        if (changed) {
            record.pushNoticeStationAt = changedAt;
            return StallProgress.OTHER_LINE_PROGRESS;
        }
        if (accepted) {
            record.pushNoticeStationAt = refused.isEmpty() ? null : refused.get(0);
            return StallProgress.PROGRESS;
        }
        return StallProgress.NONE;
    }

    /**
     * <b>只读</b>：工位上是否压着「<b>我们推入的那件在制件</b>」（= 机器正拿着它在加工，不是堵塞）。
     *
     * <p>判据：该件物品与配方 id 与登记表里<b>本仓最近一次推进这台目的地</b>的那件相同，
     * 且它自己的进度步 {@code >=} 推入时记的步序。步序取自物品的
     * {@code create:sequenced_assembly} 组件 —— Create 只在一步<b>做完</b>时才推进它
     * （{@code SequencedAssemblyRecipe#advance}）。</p>
     *
     * <p><b>为什么允许「步序更大」（而不是要求相等）</b>：同一台机器<b>连续承担多个步</b>时
     * （本模组已支持的配置，见 {@code RsccChamberExportStrategy} 的按步放行说明），Create 会在
     * <b>原地</b>把步序推进一位、机器接着加工下一步 —— 这期间工位上的件与拒收状态都不变，
     * 若要求「步序相等」，窗口就会在步序推进后第 {@value #PUSH_NOTICE_ZERO_PROGRESS_TICKS} tick
     * 弹出一条假的「堵着了」。而「步序更大的那件」在无人接手时会被<b>本模组自己的收回侧</b>取走
     * （它的留驻闸门按「步序 == 推入步」判，步序一推进就放行），因此这里不设时间上限，
     * 与那条既有口径一致（用户的原话就在那段注释里：任何固定时间窗口都是错的判据）。</p>
     *
     * <p>没有进度组件的件（原料 / 无组件的中间产物 / 装配失败留下的废料）与步序 {@code -1} 的登记
     * <b>一律不算</b>：一件不会被消耗的料压在工位上，本身就是「这条线卡住了」的典型形态，
     * 不能拿它当「机器在干活」的挡箭牌。</p>
     */
    private static boolean holdsOurInFlightPiece(
        final List<ItemStack> slots,
        @Nullable final SequenceExecutionChamberBlockEntity.InFlightUnit unit) {
        if (unit == null || unit.step() < 0 || unit.recipe().isEmpty()) {
            return false;
        }
        for (final ItemStack stack : slots) {
            if (stack.isEmpty() || stack.getItem() != unit.item()) {
                continue;
            }
            final var progress = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (progress != null && progress.step() >= unit.step()
                && progress.id().toString().equals(unit.recipe())) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：工位内容指纹（每一格的件 / 件数 / 进度步）；只用于比对「变没变」，
     * 不参与任何搬运 / 判定以外的用途。
     */
    private static String stationFingerprintOf(final List<ItemStack> slots) {
        final StringBuilder sb = new StringBuilder(slots.size() * 24);
        for (final ItemStack stack : slots) {
            sb.append('|');
            if (stack.isEmpty()) {
                continue;
            }
            sb.append(RsccAssemblyDebug.itemId(stack.getItem())).append('x').append(stack.getCount());
            final var progress = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            sb.append('#').append(progress == null ? "-" : progress.id() + "@" + progress.step());
        }
        return sb.toString();
    }

    /**
     * 「RS 原版自动合成任务」的判定 —— 不靠猜：RS 自己在 {@code TaskStatus.Item#type()} 里已经给出了
     * 明确的卡住信号（见 {@code ExternalTaskPattern#appendStatus}）。
     *
     * <ul>
     *     <li>{@code REJECTED}：接收端（自动合成器的目标机器 / 中继）拒绝接收 —— 机器被拆、掉线、
     *     或其输入口位置规则不接受；</li>
     *     <li>{@code LOCKED}：该自动合成器处于锁定状态（锁模式 / 红石信号），推不动；</li>
     *     <li>{@code NONE_FOUND}：该层的样板一个可用接收端都找不到（样板提供者已离线 / 未加载）。</li>
     * </ul>
     * 这三种统一归到 {@link Reason#EXECUTOR_OFFLINE}。都不成立时再看「缺料」：任务想抽、但网络里既
     * 没有该资源、也没有别的任务正在产出它 → {@link Reason#MISSING_MATERIAL}。两者都不成立就交给
     * 兜底的「无进展」计时器（{@link Reason#NO_PROGRESS}）。
     *
     * <p><b>为什么有进展就一律不挂起</b>：REJECTED / LOCKED / NONE_FOUND 都可能是**瞬时**的
     * （机器输入口刚好满了一瞬、红石脉冲刚好把它锁了一刻）。只要这一次扫描看到任务在动
     * （完成度或各项计数变了），就说明它没有真的卡住，直接归零 {@code reason} —— 这正是
     * 「正常运行时绝不挂起」的保证。</p>
     */
    private static void classifyGeneric(final Record record, final TaskStatus status,
                                        final Set<ItemResource> present, final List<TaskStatus> statuses,
                                        final boolean progress,
                                        final AutocraftingNetworkComponent autocrafting) {
        record.offlineSteps = List.of();
        // RS 原版任务的 EXECUTOR_OFFLINE（REJECTED/LOCKED/NONE_FOUND）与本任务「执行仓推不动」语义不同，
        // 这里清掉 pushStalled，避免任务从「序列装配」翻到「RS 原版」时残留上一次的 true 走错横幅文案。
        record.pushStalled = false;
        if (progress) {
            record.materialIcons = List.of();
            record.materialNames = List.of();
            record.materialTotal = 0;
            record.reason = Reason.NONE;
            return;
        }
        boolean sinkBad = false;
        final List<ItemResource> missing = new ArrayList<>();
        for (final TaskStatus.Item item : status.items()) {
            switch (item.type()) {
                case REJECTED, LOCKED, NONE_FOUND -> sinkBad = true;
                default -> {
                }
            }
            if (item.extracting() > 0 && item.resource() instanceof final ItemResource resource
                && !present.contains(resource) && !craftedByOthers(statuses, status, resource)
                && !isAutoCraftable(autocrafting, resource)) {
                // 用户第 3 条（本轮）：可被自动合成的资源不算「缺料」⇒ 不报缺、不挂起，静默等待它做出来。
                missing.add(resource);
            }
        }
        record.materialIcons = missing.stream().limit(MAX_LISTED)
            .map(resource -> itemIcon(resource)).toList();
        record.materialNames = record.materialIcons.stream()
            .map(stack -> stack.getHoverName().getString()).toList();
        record.materialTotal = missing.size();
        if (sinkBad) {
            record.reason = Reason.EXECUTOR_OFFLINE;
        } else if (!missing.isEmpty()) {
            record.reason = Reason.MISSING_MATERIAL;
        } else {
            record.reason = Reason.NO_PROGRESS;
        }
    }

    /** 网络里是否有别的任务正在产出该资源（有的话「缺料」只是暂时的，不该算卡住）。 */
    private static boolean craftedByOthers(final List<TaskStatus> statuses, final TaskStatus self,
                                           final ItemResource resource) {
        for (final TaskStatus other : statuses) {
            if (!other.info().id().equals(self.info().id()) && other.info().resource().equals(resource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>只读</b>：该资源此刻能不能被 RS 自动合成（终端里有以它为输出的样板）。
     *
     * <p>用户第 3 条：<b>可被自动合成的资源不算「缺料」</b> —— 一直静默等它做出来即可（不报缺、不挂起）。
     * 读不到自动合成组件时返回 {@code false}（= 判不出来 ⇒ 照旧按缺料判定，绝不漏掉真实缺料）。只读。</p>
     */
    private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting,
                                           final ItemResource resource) {
        return autocrafting != null && resource != null
            && !autocrafting.getPatternsByOutput(resource).isEmpty();
    }

    /** 物品堆版本（只看物品身份，与备料 / 补合成同一口径）。 */
    private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting,
                                           final ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && isAutoCraftable(autocrafting, new ItemResource(stack.getItem()));
    }

    /** 资源 → 展示用 1 个的图标（非物品资源或异常时给空栈，不影响判定）。 */
    private static ItemStack itemIcon(final ItemResource resource) {
        try {
            return resource.toItemStack(1);
        } catch (final RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    // ==================== 判定辅助（全部只读） ====================

    /**
     * 该网络内所有序列装配样板库（网络图里注册成 Autocrafter 的那批；图里一台都没有时才用触发扫描的那台）。
     * <p>扫描入口现在不再限定样板库（纯 RS 网络也要能进来），因此 {@code fallback} 可能为 {@code null}。</p>
     */
    private static List<SequenceAssemblyExecutorBlockEntity> executorsOf(
        final ServerLevel level, final Network network,
        @Nullable final SequenceAssemblyExecutorBlockEntity fallback) {
        final List<SequenceAssemblyExecutorBlockEntity> result = new ArrayList<>();
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        if (graph != null) {
            for (final NetworkNodeContainer container : graph.getContainers()) {
                if (!(container instanceof InWorldNetworkNodeContainer inWorld)) {
                    continue;
                }
                final BlockPos pos = inWorld.getLocalPosition();
                if (pos == null) {
                    continue;
                }
                if (level.getBlockEntity(pos) instanceof SequenceAssemblyExecutorBlockEntity executor) {
                    result.add(executor);
                }
            }
        }
        if (result.isEmpty() && fallback != null) {
            result.add(fallback);
        }
        return result;
    }

    /**
     * 该方块实体若是「已接网络的 RS 网络节点」则返回它的网络 —— 扫描入口（任何节点方块实体都行）。
     * <p>用 {@link RsccNodeContainerAccess} 读基类受保护字段 {@code mainNetworkNode}（见 mixin/accessor
     * 的 {@code AbstractNetworkNodeContainerBlockEntityAccessor}），因此自动合成器 / 中继 / 线缆 /
     * 本模组样板库都能当入口，不需要为每种方块写一份分支。</p>
     */
    @Nullable
    private static Network nodeNetwork(final BlockEntity blockEntity) {
        if (!(blockEntity instanceof RsccNodeContainerAccess access)) {
            return null;
        }
        final NetworkNode node = access.rscc$mainNetworkNode();
        return node == null ? null : node.getNetwork();
    }

    /** 该网络内全部序列执行仓（掉线判定与「更换机器」候选都来自它）。 */
    private static Map<BlockPos, SequenceExecutionChamberBlockEntity> chambersOf(final Network network) {
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> result = new HashMap<>();
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        if (graph == null) {
            return result;
        }
        for (final NetworkNodeContainer container : graph.getContainers()) {
            if (container.getNode() instanceof SequenceExecutionChamberNetworkNode node
                && node.getBlockEntity() != null) {
                final SequenceExecutionChamberBlockEntity chamber = node.getBlockEntity();
                result.put(chamber.getBlockPos(), chamber);
            }
        }
        return result;
    }

    /** 装配样板里「这一步要用的执行仓」是否仍在线（存在于同一网络内）。 */
    private static List<SyncAssemblyAlertsPacket.OfflineStep> offlineSteps(
        final ServerLevel level, final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
        final PatternRef pattern) {
        final List<SyncAssemblyAlertsPacket.OfflineStep> result = new ArrayList<>();
        final List<SequencePatternData.UnitEntry> units = pattern.assembly().units();
        for (int step = 0; step < units.size(); step++) {
            final SequencePatternData.UnitEntry unit = units.get(step);
            final BlockPos pos = unit.machinePos();
            if (pos == null) {
                // <b>本轮修正（用户第 ④ 条：线缆断开后必须挂起并弹横幅）</b>：旧实现把「未指派机器」的步
                // 整个跳过 —— 而玩家的样板完全可能是自动指派 / 只记了配方类型。于是一旦断掉线缆，
                // 这一步没有任何在线执行仓能服务它，watchdog 却**什么都判不出来**（离线集合恒为空）
                // ⇒ 不挂起、不弹横幅，正是用户实测「断缝后什么提示都没有」。
                // 现在改判「本网络里有没有一台执行仓能承担这一步」（与「更换机器」候选同一个判据：
                // 配方类型相等）。配方类型未知时仍然跳过（判不出来就绝不打草惊蛇）。
                final String unassignedType = unit.recipeType();
                if (unassignedType == null || unassignedType.isEmpty()
                    || servableByRecipeType(chambers, unassignedType)) {
                    continue;
                }
                result.add(new SyncAssemblyAlertsPacket.OfflineStep(step, RecipeTypeNames.of(unassignedType),
                    unit.machineName() == null || unit.machineName().isEmpty()
                        ? pattern.executorPos().toShortString() : unit.machineName(),
                    pattern.executorPos()));
                continue;
            }
            if (chambers.containsKey(pos)) {
                continue; // 在线的不算掉线
            }
            final BlockEntity blockEntity = level.getBlockEntity(pos);
            final String machineName = blockEntity instanceof SequenceExecutionChamberBlockEntity chamber
                ? chamber.getChamberDisplayName()
                : (unit.machineName() == null || unit.machineName().isEmpty()
                    ? pos.toShortString() : unit.machineName());
            result.add(new SyncAssemblyAlertsPacket.OfflineStep(step,
                RecipeTypeNames.of(unit.recipeType()), machineName, pos));
        }
        return result;
    }

    /**
     * 只读：本网络里是否有一台执行仓能承担给定配方类型的那一步。
     * <p>与「更换机器」候选（{@code machineCandidates}）用的是同一个判据（配方类型相等），
     * 因此「谁能接手这一步」与「这一步是否掉线」不可能给出两套互相矛盾的结论。</p>
     */
    private static boolean servableByRecipeType(
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers, final String recipeType) {
        for (final SequenceExecutionChamberBlockEntity chamber : chambers.values()) {
            if (recipeType.equals(chamber.getRecipeType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一次性「步骤 → 负责机器」绑定快照（<b>每次任务开始时一条</b>；用户要求，不每秒刷）。
     *
     * <p><b>为什么必须一次性且逐行</b>：用户实测的「过早回流 / 错误回流」根因就在「某一步绑到了哪台
     * 执行仓」上，而 {@link #sendBanner} 只记一条汇总（旧版连「是哪一步」都不写）。这里在任务开始时
     * 把每一步打印出来：{@code machine=坐标|unassigned}、{@code chamberExists}、
     * {@code recipeType}、该仓当前<b>认了哪几步</b>（{@code chamberSteps}）、以及
     * <b>下一步由谁负责</b>（{@code nextStepOwner}）—— 下一次拿到日志就能直接定位，而不是靠推断。</p>
     *
     * <p>三条是「需要玩家干预」的情形，各<b>显式 WARN 一次</b>：该步的机器坐标不在网络里 / 该步没指派
     * 机器 —— 这时这件东西收回来也不会有任何机器接手（正好是用户看到的「错误回流」）。只读，不搬运任何资源。</p>
     */
    private static void logBindingSnapshot(final ServerLevel level,
                                           final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
                                           final PatternRef pattern, final UUID taskId,
                                           final Record record) {
        // 2026-10-06：本方法里除了开发日志，还夹着<b>两条必要 WARN</b>（「这一步没有任何在线机器认领」
        // /「样板没覆盖配方全部步骤」）。旧实现在方法开头 `if (!isEnabled()) return;`，一旦把开发日志
        // 默认关掉，这两条玩家可见的失败提示就会一起消失 —— 所以现在只把关卡套在 event() 上，
        // WARN 照常执行（它们各自按 taskId 只报一次）。
        final boolean devLogs = RsccAssemblyDebug.isEnabled();
        final List<SequencePatternData.UnitEntry> units = pattern.assembly().units();
        final int totalSteps = totalStepsOf(level, units);
        if (devLogs) {
            RsccAssemblyDebug.event("binding task=" + taskId
                + " product=" + record.productName + " x" + record.amount
                + " steps=" + units.size()
                + (totalSteps > 0 ? " sequence=" + totalSteps
                    : " sequence=? recipe=" + rawRecipeId(units))
                + " loops=" + pattern.assembly().loops()
                + " executor=" + RsccAssemblyDebug.at(pattern.executorPos()));
        }
        for (int step = 0; step < units.size(); step++) {
            final SequencePatternData.UnitEntry unit = units.get(step);
            final BlockPos pos = unit.machinePos();
            final SequenceExecutionChamberBlockEntity chamber = pos == null ? null : chambers.get(pos);
            final String machine = pos == null ? "unassigned" : RsccAssemblyDebug.at(pos);
            final String recipeType = unit.recipeType() == null || unit.recipeType().isEmpty()
                ? "-" : unit.recipeType();
            if (devLogs) {
                RsccAssemblyDebug.event("binding step=" + step
                    + " machine=" + machine
                    + " chamberExists=" + (chamber != null)
                    + " recipeType=" + recipeType
                    + " chamberSteps=[" + (chamber == null ? "-" : chamber.debugOwnedSteps()) + "]"
                    + " nextStepOwner=" + nextStepOwnerOf(units, chambers, step, totalSteps,
                        pattern.assembly().loops()));
            }
            if (chamber == null) {
                // 只有「这一步确实存在」才可能被服务：没机器 / 机器不在网络里都收不回来再加工
                RsccAssemblyDebug.warn("bindingorphan@" + taskId + "#" + step,
                    "binding-orphan step=" + step + " machine=" + machine
                        + " chamberExists=false recipeType=" + recipeType
                        + " —— 这一步的件没有任何在线执行仓能接手（收回网络也不会被加工）；"
                        + "请在样板终端重新指派该步的机器 / 重新生成样板");
            }
        }
        // <b>用户第 3 条（列车轨道一点都合成不了）：样板的流程编排没有覆盖配方的全部步骤时，
        // 明确报错，绝不静默不动。</b> 合并后的列数 < 配方步数是正常的（相邻相同步骤被并成一列 +
        // 重复次数 N），但「展开后覆盖到的步数」必须 ≥ 配方步数；否则末步 / 中间某一步没有任何
        // 执行仓认领，链路会停在那里 —— 玩家看到的正是「啥也不动」。
        final int covered = coveredStepCount(units);
        if (totalSteps > 0 && covered < totalSteps) {
            RsccAssemblyDebug.warn("bindinggap@" + taskId,
                "binding-gap task=" + taskId + " product=" + record.productName
                    + " patternColumns=" + units.size() + " coveredSteps=" + covered
                    + " recipeSteps=" + totalSteps
                    + " —— 样板的流程编排没有覆盖配方的全部步骤（第 " + (covered + 1)
                    + " 步起没有任何执行仓认领），链路会停在这里；"
                    + "请在样板终端重新导入该配方 / 补齐缺失的步骤后重新生成样板");
        }
    }

    /**
     * 「两次复查报告的是不是同一批离线步骤」（本轮新增：EXECUTOR_OFFLINE 的连续确认判据）。
     * <p>比「步序 + 机器坐标」两项即可：同一个任务里同一步的机器绑定是恒定的，两次都报同一批
     * 才说明这不是瞬时抖动（网络图重建 / 区块加载中的一瞬间）。只读，不改变任何状态。</p>
     */
    private static boolean sameOfflineSteps(final List<SyncAssemblyAlertsPacket.OfflineStep> now,
                                           final List<SyncAssemblyAlertsPacket.OfflineStep> before) {
        if (now.isEmpty() || before.isEmpty() || now.size() != before.size()) {
            return false;
        }
        for (final SyncAssemblyAlertsPacket.OfflineStep step : now) {
            boolean found = false;
            for (final SyncAssemblyAlertsPacket.OfflineStep other : before) {
                if (other.stepIndex() == step.stepIndex()
                    && other.machinePos().equals(step.machinePos())) {
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

    /** 离线步骤摘要（日志用）：{@code 0@(-10,-60,10),1@(-16,-60,10)}。 */
    private static String offlineSummary(final List<SyncAssemblyAlertsPacket.OfflineStep> steps) {
        final StringBuilder sb = new StringBuilder(48);
        for (final SyncAssemblyAlertsPacket.OfflineStep step : steps) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(step.stepIndex()).append('@').append(step.machinePos().toShortString());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /**
     * 只读：该步做完之后「下一步由谁负责」（{@code step + 1} 对总步数取模；末步且单循环 = 成品，无下一步）。
     * <p>这正是用户点名要看的那一项：「下一步仍然由同一台执行器负责」→ 该件绝不该被收回；
     * 「下一步没有任何机器认领」→ 才允许收回 / 导出到网络。判据只用电位与步序，不做任何方块比较。</p>
     */
    private static String nextStepOwnerOf(final List<SequencePatternData.UnitEntry> units,
                                          final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
                                          final int step, final int totalSteps, final int loops) {
        if (units.isEmpty() || step < 0) {
            return "none";
        }
        final int total = totalSteps > 0 ? totalSteps : units.size();
        final int next = step + 1;
        if (next >= total && loops <= 1) {
            return "none(成品)"; // 末步走完就是成品：没有下一步骤，按「非输入类」正向回收
        }
        final int index = total > 0 ? Math.floorMod(next, total) : next;
        final BlockPos pos = machineAtExpandedStep(units, index);
        if (pos == null) {
            // <b>本轮修正（用户第 3 条：列车轨道一点都合成不了）</b>：旧实现直接拿
            // {@code units.get(index)}，而 {@code units} 是「相邻相同步骤<b>合并</b>后的列」，
            // 与配方展开后的真实步序不是同一个下标域（坚固板/列车轨道的两步冲压被并成一列）。
            // 因此当 index 落在被合并掉的层级上时，会走到 {@code index >= units.size()} 分支
            // 打印 {@code nextStepOwner=none} —— 它看起来像「没有下一步骤」，实际是「下标域对不上」，
            // 排查时被误导。现在改成按「样板自己记的 step + count」把列展开回真实步序再取机器，
            // 取不到 = 样板的编排<b>没有覆盖</b>这一步 ⇒ 明确报 {@code unassigned}（绝不能静默）。
            return "unassigned";
        }
        return chambers.containsKey(pos)
            ? RsccAssemblyDebug.machine("chamber", pos)
            : RsccAssemblyDebug.at(pos) + "(offline)";
    }

    /**
     * 只读：把「合并后的列」按 {@code step + count} 展开回配方的真实步序域，再取该步的机器。
     * <p>样板自己记了步序（{@code unit.step() >= 0}）时按 {@code [step, step + count)} 命中；
     * 没记步序的旧样板退化为「按列顺序累加」的保守映射。找不到即 {@code null}（= 这一步没有编排）。</p>
     */
    @Nullable
    private static BlockPos machineAtExpandedStep(final List<SequencePatternData.UnitEntry> units,
                                                  final int stepIndex) {
        int cursor = 0;
        for (final SequencePatternData.UnitEntry unit : units) {
            final int count = Math.max(1, unit.count());
            if (unit.step() >= 0) {
                if (stepIndex >= unit.step() && stepIndex < unit.step() + count) {
                    return unit.machinePos();
                }
            } else if (stepIndex >= cursor && stepIndex < cursor + count) {
                return unit.machinePos();
            }
            cursor += count;
        }
        return null;
    }

    /** 只读：样板的编排「展开后覆盖到第几步」（= max(step + count)；全部没有步序时退回列数）。 */
    private static int coveredStepCount(final List<SequencePatternData.UnitEntry> units) {
        int covered = 0;
        boolean anyStep = false;
        int cursor = 0;
        for (final SequencePatternData.UnitEntry unit : units) {
            final int count = Math.max(1, unit.count());
            if (unit.step() >= 0) {
                anyStep = true;
                covered = Math.max(covered, unit.step() + count);
            }
            cursor += count;
        }
        return anyStep ? covered : cursor;
    }
/**
     * 只读诊断：总样板里记录的第一条「配方 id」原样返回（查不到步数时报出来，供人一眼看出是「样板记的 id
     * 与数据包里的真实 id 不一致」还是「配方确实不存在」）。
     * <p>为什么需要它（本轮日志质量修正）：旧日志在查不到步数时只写 {@code sequence=?}，
     * 排查时无法区分上面两种完全不同的原因（前者要重新生成样板、后者要看数据包）。</p>
     */
    private static String rawRecipeId(final List<SequencePatternData.UnitEntry> units) {
        for (final SequencePatternData.UnitEntry unit : units) {
            if (unit.recipe() != null && !unit.recipe().isEmpty()) {
                return unit.recipe();
            }
        }
        return "-";
    }

    /** 只读：该总样板对应的 Create 配方单循环步数（查不到返回 -1）。 */
    private static int totalStepsOf(final ServerLevel level, final List<SequencePatternData.UnitEntry> units) {
        for (final SequencePatternData.UnitEntry unit : units) {
            final String recipe = unit.recipe();
            if (recipe == null || recipe.isEmpty()) {
                continue;
            }
            final ResourceLocation id = ResourceLocation.tryParse(recipe);
            if (id == null) {
                continue;
            }
            try {
                final var holder = level.getRecipeManager().byKey(id);
                if (holder.isPresent()
                    && holder.get().value() instanceof final SequencedAssemblyRecipe assembly) {
                    return assembly.getSequence().size();
                }
            } catch (final RuntimeException ignored) {
                // 配方系统异常（未加载完 / 版本差异）→ 当作查不到，绝不抛异常
            }
        }
        return -1;
    }

    /**
     * 该总样板「真的需要玩家提供」的原料清单（起步原料 + 各步骤的物品投入，<b>去掉过渡件</b>）。
     *
     * <p><b>为什么要去掉过渡件（用户硬要求：提示缺少原料时必须自动忽略中间产物）</b>：
     * 样板为每一步记的「输入物品」对注液 / 冲压这类步骤往往就是<b>过渡件本身</b>
     * （例如坚固板的 {@code create:unprocessed_obsidian_sheet}）—— 它是产线在线上自己造出来的，
     * 网络里天然一件都没有。旧实现把它也计进「缺少的原料」，于是横幅常年写着
     * 「由于 未完成黑曜石板、黑曜石粉 缺少或自动合成失败」。这里按配方的
     * {@code transitional_item} 精确剔除，因此真实原料（黑曜石粉）的缺料照旧会报。
     * <p>去重与「起步原料」的保留口径不变；只读。</p>
     */
    private static List<ItemStack> neededItems(final ServerLevel level,
                                               final SequencePatternData.AssemblyData assembly) {
        final Set<Item> transitionals = transitionalItems(level, assembly);
        final List<ItemStack> result = new ArrayList<>();
        if (!assembly.ingredient().isEmpty()) {
            result.add(assembly.ingredient().copyWithCount(1));
        }
        for (final SequencePatternData.UnitEntry unit : assembly.units()) {
            if (unit.input() == null || unit.input().isEmpty()) {
                continue;
            }
            if (transitionals.contains(unit.input().getItem())) {
                continue; // 过渡件：产线在线自造，不计入「缺少的原料」
            }
            result.add(unit.input().copyWithCount(1));
        }
        return result;
    }

    /**
     * 只读：该总样板所引用配方们的 {@code transitional_item} 物品集合（查不到配方的步骤不参与）。
     * <p>与 {@code SequenceExecutionChamberBlockEntity} 侧读过渡件的口径同源（都用配方自己的
     * {@code getTransitionalItem()}），因此「哪些是中间产物」不会出现两套答案。只读。</p>
     */
    private static Set<Item> transitionalItems(final ServerLevel level,
                                               final SequencePatternData.AssemblyData assembly) {
        final Set<Item> result = new HashSet<>();
        for (final SequencePatternData.UnitEntry unit : assembly.units()) {
            final String recipe = unit.recipe();
            if (recipe == null || recipe.isEmpty()) {
                continue;
            }
            final ResourceLocation id = ResourceLocation.tryParse(recipe);
            if (id == null) {
                continue;
            }
            try {
                final var holder = level.getRecipeManager().byKey(id);
                if (holder.isPresent()
                    && holder.get().value() instanceof final SequencedAssemblyRecipe sequenced) {
                    final ItemStack transitional = sequenced.getTransitionalItem();
                    if (transitional != null && !transitional.isEmpty()) {
                        result.add(transitional.getItem());
                    }
                }
            } catch (final RuntimeException ignored) {
                // 配方系统异常（未加载完 / 版本差异）→ 当作查不到，绝不抛异常
            }
        }
        return result;
    }

    /** 网络里「此刻存在的物品资源」（只读快照）。 */
    private static Set<ItemResource> presentItems(final Network network) {
        final Set<ItemResource> result = new HashSet<>();
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return result;
        }
        for (final TrackedResourceAmount tracked : storage.getResources(PlayerActor.class)) {
            if (tracked.resourceAmount().amount() <= 0) {
                continue;
            }
            if (tracked.resourceAmount().resource() instanceof final ItemResource item) {
                result.add(item);
            }
        }
        return result;
    }

    /** 网络里是否有「中间产物回流」：带 Create 序列装配进度组件的过渡件。 */
    private static boolean hasIntermediate(final Set<ItemResource> present) {
        for (final ItemResource resource : present) {
            if (resource.toItemStack(1).get(AllDataComponents.SEQUENCED_ASSEMBLY) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该网络的执行仓里是否还压着「<b>真正在加工</b>的东西」（在制件 / 过渡件）。
     *
     * <p><b>为什么不能用「仓里有没有东西」（用户实测「缺料却不弹提示」的根因）</b>：
     * 总线输出模式下执行仓会<b>常驻</b>一批备料（原料、流体），旧判据
     * （{@code itemStorage().getItemCount() > 0 || !outputTank.isEmpty()}）因此几乎恒为真，
     * 于是 {@code classifySequence} 里的「可推进」恒成立 → 缺料原因永远判不出来 →
     * 任务真卡住时也不会有任何横幅 / 提示（用户原话：「缺少材料也需要弹弹窗。但是你现在并没有弹弹窗」）。
     * 在制件（带 {@code create:sequenced_assembly} 进度组件的过渡件）才是「这条产线真的在做一件事」
     * 的唯一标志，因此改用 {@link SequenceExecutionChamberBlockEntity#hasInFlightUnit()}。
     * 只读，不搬运 / 销毁任何资源。</p>
     */
    private static boolean anyChamberInFlight(
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers) {
        for (final SequenceExecutionChamberBlockEntity chamber : chambers.values()) {
            if (chamber.hasInFlightUnit()) {
                return true;
            }
            // <b>本轮修正（用户第 1 条：刚好够却仍提示缺少黑曜石粉末）</b>：在制件被输出总线推给
            // 机器 / 置物台之后，就<b>不再停在执行舱内部存储里</b>了 —— 只看仓内会漏掉「机器上正加工的
            // 那一件」，于是产线明明在跑（只是两步之间仓内没有在制件）也被判成「不推进 + 缺料」，
            // 5 秒后弹出一条「由于 未完成黑曜石板、黑曜石粉 缺少或自动合成失败」。这里改用
            // pipeline 口径（仓内 + 机器 / 置物台），判据才算完整。
            if (chamber.pipelineHasInFlightUnit()) {
                return true;
            }
        }
        return false;
    }

    /** 任务在两次扫描之间有没有动过（完成度或各计数之和变化）。 */
    private static boolean progressed(final Record record, final TaskStatus status) {
        long total = 0;
        for (final TaskStatus.Item item : status.items()) {
            total += item.stored() + item.extracting() + item.processing() + item.scheduled() + item.crafting();
        }
        final boolean changed = record.lastPercentage >= 0
            && (Math.abs(status.percentageCompleted() - record.lastPercentage) > 1.0E-6
            || total != record.lastItemTotal);
        record.lastPercentage = status.percentageCompleted();
        record.lastItemTotal = total;
        return changed;
    }

    /** 网络里是否有别的自动合成任务正在合成本条任务的所需原料。 */
    private static boolean relatedSubTaskRunning(final List<TaskStatus> statuses, final TaskStatus self,
                                                 final List<ItemStack> needed) {
        if (needed.isEmpty()) {
            return false;
        }
        final Set<ItemResource> keys = new HashSet<>();
        for (final ItemStack stack : needed) {
            keys.add(ItemResource.ofItemStack(stack));
        }
        for (final TaskStatus other : statuses) {
            if (other.info().id().equals(self.info().id())) {
                continue;
            }
            if (other.info().resource() instanceof final ItemResource resource && keys.contains(resource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 所需里「此刻真的凑不出来」的部分（去重）。
     *
     * <h2>「有」的口径（用户第 1 条：刚好够却误报缺少黑曜石粉末）</h2>
     * <p>只把「网络里有没有」当唯一口径是不够的 —— 序列装配的原料在任一时刻完全可能正压在
     * <b>执行舱内部 / 机器 / 置物台</b>上（刚被推进去、还没被消耗），网络里当然是 0。于是：</p>
     * <ol>
     *     <li>网络里有一份 ⇒ 有；</li>
     *     <li>网络里没有、但本网络的执行舱 / 机器 / 置物台上压着一份 ⇒ 有（正在被消耗）；</li>
     *     <li>「起步原料」还额外被<b>已经在制的那一件</b>顶掉一份：只要
     *     {@code 订单剩余件数 ≤ 在制件数}（{@code remainingUnits ≤ inFlightUnits}），说明这一份原料
     *     已经消耗进产线了 —— 此时它不是缺料。</li>
     * </ol>
     * <p>三条都凑不出来才算缺料 ⇒ 「刚好够」全程零误报；而<b>真的缺料</b>（网络 + 仓 / 机器都空、
     * 且订单还没做完）照旧会报，绝不漏。只读，绝不搬运 / 销毁任何资源。</p>
     */
    /**
     * <b>只读</b>：整单口径的原料缺口 = 「每件需要量 × 剩余件数 − 现有量」（> 0 的才记）。
     *
     * <p>用户实测（原话）：<i>「现在底下是缺少大齿轮，但现在大齿轮一个都没有了，所以说理论上来说
     * 它并不是缺少一个大齿轮的事情，它是缺少很多个大齿轮……它只着眼于现在这一个场面」</i>。</p>
     *
     * <p>只写诊断：不影响缺料判定、不影响挂起、不影响任何搬运。</p>
     */
    private static java.util.Map<String, Long> materialDeficit(final List<ItemStack> needed,
                                                               final Set<ItemResource> present,
                                                               final long remainingUnits) {
        final java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        if (needed == null || needed.isEmpty()) {
            return out;
        }
        final long units = Math.max(1L, remainingUnits);
        final java.util.Map<String, Long> perUnit = new java.util.LinkedHashMap<>();
        for (final ItemStack stack : needed) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final String id = RsccAssemblyDebug.itemId(stack.getItem());
            // 每件需要量：neededItems 给的是 1 件，但配方里可能写多件 —— 用栈自带数量兜底
            perUnit.merge(id, (long) Math.max(1, stack.getCount()), Long::sum);
        }
        for (final java.util.Map.Entry<String, Long> entry : perUnit.entrySet()) {
            final long want = entry.getValue() * units;
            final long have = presentAmount(present, entry.getKey());
            final long gap = want - have;
            if (gap > 0L) {
                out.put(entry.getKey(), gap);
            }
        }
        return out;
    }

    /** 只读：给定物品 id 在当前「有」的集合里有多少件（按物品匹配，取不到数量时按 1 计）。 */
    private static long presentAmount(final Set<ItemResource> present, final String itemId) {
        long total = 0L;
        for (final ItemResource key : present) {
            if (itemId.equals(RsccAssemblyDebug.itemId(key.item()))) {
                total += 1L; // present 是「有没有」的集合（每种一份），保守按 1 计
            }
        }
        return total;
    }

    private static List<ItemStack> missing(final List<ItemStack> needed, final Set<ItemResource> present,
                                           final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
                                           final SequencePatternData.AssemblyData assembly,
                                           final long remainingUnits) {
        final List<ItemStack> result = new ArrayList<>();
        final Set<ItemResource> seen = new HashSet<>();
        final Item ingredient = assembly.ingredient().isEmpty() ? null : assembly.ingredient().getItem();
        long inFlightUnits = 0L;
        for (final SequenceExecutionChamberBlockEntity chamber : chambers.values()) {
            inFlightUnits += chamber.pipelineInFlightCount();
        }
        final boolean ingredientAlreadyInFlight = remainingUnits <= inFlightUnits;
        for (final ItemStack stack : needed) {
            final ItemResource key = ItemResource.ofItemStack(stack);
            if (present.contains(key) || !seen.add(key)) {
                continue;
            }
            if (ingredient != null && stack.getItem() == ingredient && ingredientAlreadyInFlight) {
                continue; // 已经在制的那一件已消耗掉一份起步原料 ⇒ 不是缺料（尾巴路径）
            }
            if (anyChamberSupplies(chambers, stack.getItem())) {
                continue; // 正压在执行舱 / 机器 / 置物台上（正在被消耗或等着被喂给机器）
            }
            result.add(stack);
        }
        return result;
    }

    /** 只读：本网络的任一执行舱（含它正在供料的机器 / 置物台）此刻是否压着这一份物品。 */
    private static boolean anyChamberSupplies(
        final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers, final Item item) {
        for (final SequenceExecutionChamberBlockEntity chamber : chambers.values()) {
            if (chamber.pipelineSuppliesItem(item)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 2026-10-06：RS 权威「已交付量」镜像（本轮根因修复的核心） ====================

    /**
     * <b>任务 uuid + 产出资源 → 「该任务已经从外部产线收到几件成品」</b>（RS 权威读数的只读镜像）。
     *
     * <h2>为什么必须有它（本轮实测的根因，证据链闭合）</h2>
     * <p>本模组的序列装配样板是 <b>EXTERNAL 型、且是任务的 root 样板</b>。RS 的任务状态里
     * 「目标资源那一项」的读数对本模组样板<b>恒为 0</b>，因此「订单剩余量 = 订单量 − 已交付量」
     * 里的已交付量恒为 0 ⇒ 每交付一件、在制数掉 1 ⇒ 名额立刻回到 1 ⇒ <b>又开一件</b>。
     * 实测（订单 坚固板 10 + 列车轨道 10）开件 19 + 14、多出的 9 + 3 件半成品堆在缓存池，
     * 而交付数恰好 10/10 —— 正是「多开新件」而不是「少交付」。三个恒为 0 的字段（RS 2.0.0 源码）：</p>
     * <ul>
     *     <li>{@code TaskStatus.Item#stored} 只由任务自己的 {@code internalStorage} 填
     *     （{@code TaskImpl.java:162-164}），而 root EXTERNAL 样板的 {@code beforeInsert} 直接
     *     {@code return 0}（{@code ExternalTaskPattern.java:96-102}）、认领只发生在
     *     {@code afterInsert}（同文件 104-110 → {@code trySatisfy} 112-125），
     *     <b>只减 {@code expectedOutputs}、从不写 {@code internalStorage}</b>；</li>
     *     <li>{@code TaskStatus.Item#crafting} 只由 {@code InternalTaskPattern#appendStatus} 填
     *     （{@code InternalTaskPattern.java:74-81}）；</li>
     *     <li>{@code TaskStatus#percentageCompleted} 对外部样板也读不出进度：外部样板的权重是
     *     {@code iterationsToSendToSink}（{@code ExternalTaskPattern.java:174-177}），派发一空权重即为 0
     *     ⇒ {@code TaskImpl#getStatus} 的加权和恒为 0（{@code TaskImpl.java:151-165}）。</li>
     * </ul>
     * <p>唯一被<b>正确维护</b>的量是 {@code ExternalTaskPattern#iterationsReceived}
     * （{@code ExternalTaskPattern.java:28} 字段、{@code :127-141} 每次认领都更新、{@code :252} 随任务存档
     * 读写）—— 它正是「已收到几次产出的完整迭代」= 本模组口径下的<b>已交付件数</b>。
     * 它不暴露在任何公开 API 上，因此由
     * {@code cretae.cookiewyq.rs_create_compat.mixin.RsccTaskDeliveredMixin}（注入
     * {@code TaskImpl#getStatus} 的 RETURN）+ 两个 {@code @Accessor} 只读镜像到这里；
     * 每次 t 读任务状态都会刷新，<b>不落盘也不需要落盘</b>（RS 自己把
     * {@code iterationsReceived} 存进任务快照，重载后由同一个注入重新读出）。</p>
     *
     * <p><b>只读保证</b>：镜像只被本模组自己的判定<b>读</b>（执行舱的在制名额、缺料横幅、收尾判据），
     * 绝不写任何任务状态、绝不搬运任何资源；镜像里没有记录时读取返回 0
     * （= 退回既有口径，绝不因为镜像缺失而放宽或收紧额外的量）。</p>
     */
    private static final Map<UUID, Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long>>
        TASK_DELIVERED = new HashMap<>();

    /** 镜像表的软上限（键 = 任务 uuid；正常远小于它，超限逐个淘汰最老的条目，避免无界增长）。 */
    private static final int TASK_DELIVERED_LIMIT = 512;

    /**
     * 登记一次「RS 已交付」读数（由 {@code RsccTaskDeliveredMixin} 在每次 {@code TaskImpl#getStatus}
     * 返回时调用）。<b>单调</b>：同一 (任务, 资源) 只保留最大值 —— 交付量在物理上不可能倒退，
     * 而 RS 的 {@code iterationsReceived} 也不会；用 max 只是让「读到的顺序」不影响结论。
     */
    public static void publishTaskDelivered(
        final UUID taskId,
        final com.refinedmods.refinedstorage.api.resource.ResourceKey resource,
        final long delivered) {
        if (taskId == null || resource == null || delivered <= 0L) {
            return;
        }
        synchronized (TASK_DELIVERED) {
            final Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long> byResource =
                TASK_DELIVERED.computeIfAbsent(taskId, key -> new LinkedHashMap<>());
            byResource.merge(resource, delivered, Math::max);
            while (TASK_DELIVERED.size() > TASK_DELIVERED_LIMIT) {
                final java.util.Iterator<UUID> iterator = TASK_DELIVERED.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    /** 只读：这条任务在该资源上的 RS 权威交付读数；没有记录返回 0（= 退回既有口径）。 */
    public static long publishedTaskDelivered(
        final UUID taskId,
        final com.refinedmods.refinedstorage.api.resource.ResourceKey resource) {
        if (taskId == null || resource == null) {
            return 0L;
        }
        synchronized (TASK_DELIVERED) {
            final Map<com.refinedmods.refinedstorage.api.resource.ResourceKey, Long> byResource =
                TASK_DELIVERED.get(taskId);
            final Long value = byResource == null ? null : byResource.get(resource);
            return value == null ? 0L : value;
        }
    }

    /**
     * 只读：这条任务的<b>已交付量</b>。
     *
     * <p><b>两个来源取大</b>：</p>
     * <ol>
     *     <li><b>RS 权威镜像</b>（{@link #publishedTaskDelivered}，= root EXTERNAL 样板的
     *     {@code iterationsReceived}）—— 对本模组的序列装配样板唯一成立的读数；</li>
     *     <li>任务状态里目标资源的 {@code stored + crafting} —— 对 <b>INTERNAL</b> 样板
     *     （RS 原版合成 / 本模组别的样板）仍然是正确读数，因此保留为兼容来源。</li>
     * </ol>
     * <p>两者都是只读；取大不会让任何一条产线多开（只会更保守）。</p>
     * <p><b>为什么不直接用 {@code percentageCompleted}</b>：外部样板的权重是「剩余派发次数」，
     * 派发一空权重即为 0 ⇒ 加权完成度恒为 0（{@code ExternalTaskPattern.java:174-177} +
     * {@code TaskImpl.java:151-165}），拿它当已交付量等于恒为 0。</p>
     */
    public static long deliveredAmount(final TaskStatus status) {
        if (status == null || status.info() == null) {
            return 0L;
        }
        long delivered = 0L;
        for (final TaskStatus.Item item : status.items()) {
            if (item.resource().equals(status.info().resource())) {
                delivered += Math.max(0L, item.stored()) + Math.max(0L, item.crafting());
            }
        }
        return Math.max(delivered,
            publishedTaskDelivered(status.info().id().id(), status.info().resource()));
    }

    // ==================== 横幅（复用既有「完成横幅」实现，纯展示） ====================

    private static final int COLOR_TITLE = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_HINT = 0xFF9EC7FF;
    /** 逐段颜色调色板：同一行里并排的多种原料 / 多个步骤用不同颜色区分。 */
    private static final int[] PALETTE = {0xFFFF7F7F, 0xFFFFD479, 0xFF9EE493, 0xFF8ECBFF};

    private static final String KEY_SUSPENDED = "gui.rs_create_compat.assembly.banner.suspended";
    private static final String KEY_SUSPENDED_AUTO = "gui.rs_create_compat.assembly.banner.suspended_auto";
    private static final String KEY_PRODUCT = "gui.rs_create_compat.assembly.banner.product";
    private static final String KEY_PRODUCT_AUTO = "gui.rs_create_compat.assembly.banner.product_auto";
    private static final String KEY_MATERIAL_PREFIX = "gui.rs_create_compat.assembly.banner.material_prefix";
    private static final String KEY_MATERIAL_SUFFIX = "gui.rs_create_compat.assembly.banner.material_suffix";
    private static final String KEY_MATERIAL_MORE = "gui.rs_create_compat.assembly.banner.material_more";
    private static final String KEY_OFFLINE_PREFIX = "gui.rs_create_compat.assembly.banner.offline_prefix";
    private static final String KEY_OFFLINE_SUFFIX = "gui.rs_create_compat.assembly.banner.offline_suffix";
    private static final String KEY_OFFLINE_MORE = "gui.rs_create_compat.assembly.banner.offline_more";
    /** RS 原版任务的「机器掉线」行（没有「第几步 / 哪台执行仓」可言，只给一句结论）。 */
    private static final String KEY_OFFLINE_AUTO = "gui.rs_create_compat.assembly.banner.offline_auto";
    /**
     * 「输出阻塞」横幅文案（机器还在、只是目的地持续拒收 / 已满）—— 与 {@link #KEY_OFFLINE_AUTO}
     * 的「设备掉线」区分开：用户实测「所有序列装配都报设备掉线」，但实际上机器只是被下游顶住，
     * 拆机器毫无意义。prefix / suffix 用于带步骤号的「输出阻塞：第 N 步…」形式；
     * auto 用于没有步骤号兜底（与 {@link #KEY_OFFLINE_PREFIX}/{@link #KEY_OFFLINE_SUFFIX}/
     * {@link #KEY_OFFLINE_AUTO} 三件套一一对应）。
     */
    private static final String KEY_OUTPUT_BLOCKED_PREFIX = "gui.rs_create_compat.assembly.banner.output_blocked_prefix";
    private static final String KEY_OUTPUT_BLOCKED_SUFFIX = "gui.rs_create_compat.assembly.banner.output_blocked_suffix";
    private static final String KEY_OUTPUT_BLOCKED_AUTO = "gui.rs_create_compat.assembly.banner.output_blocked_auto";
    /**
     * <b>第 3 行的「堵塞原因」段</b>（2026-10-06 新增；P2：文案要说清「哪一步、因为什么、无法继续」）。
     *
     * <p>旧实现只有 {@link #KEY_OUTPUT_BLOCKED_AUTO} 一句没有主语的结论（「下游工位推不进去」），
     * 玩家既不知道是第几步，也不知道是「机器在加工」还是「这一步根本没有任何在线执行仓认领」——
     * 用户原话：「某一步已完成，下一步因为什么堵塞，所以无法继续」。</p>
     *
     * <p>两条键都是<b>拼接片段</b>（前缀 {@link #KEY_OUTPUT_BLOCKED_PREFIX} 已含「第 」，
     * 因此它们以「 步」开头），值首空格是语义的一部分，已在
     * {@code tools/verify_lang_simplify.py} 的 {@code EDGE_SPACE_BY_DESIGN} 里逐键登记。</p>
     */
    private static final String KEY_PUSH_CAUSE_REFUSED = "gui.rs_create_compat.push_stall.cause.refused";
    private static final String KEY_PUSH_CAUSE_NO_OWNER = "gui.rs_create_compat.push_stall.cause.no_owner";
    /**
     * <b>「下游又通了」的收尾提示</b>（2026-10-06 新增；恢复边沿，只弹一次）。
     *
     * <p>与「输出阻塞」横幅<b>成对</b>：堵住时弹一次（明确告诉玩家现在是等待），重新通了之后
     * 再弹一次（明确告诉玩家下游已经能收料、任务仍停在暂停态等他处置）—— 这两半合起来才是
     * 用户要的那句「现在是等待还是继续」。同一状态持续期间绝不重复弹（边沿位
     * {@link Record#pushClearedNotified}）。</p>
     *
     * <p>键放在独立命名空间下（不是 {@code gui.rs_create_compat.assembly.*}）：那一段语言键的
     * <b>条数</b>被 {@code tools/selfcheck_assembly_watchdog.py} 钉死，本提示不属于那套固定文案。</p>
     */
    private static final String KEY_PUSH_CLEARED_TITLE = "gui.rs_create_compat.push_stall.cleared.title";
    private static final String KEY_PUSH_CLEARED_BODY = "gui.rs_create_compat.push_stall.cleared.body";
    /** RS 原版任务的「无进展」行。 */
    private static final String KEY_NO_PROGRESS = "gui.rs_create_compat.assembly.banner.no_progress";
    private static final String KEY_PAUSED = "gui.rs_create_compat.assembly.banner.paused";
    private static final String KEY_HINT = "gui.rs_create_compat.assembly.banner.hint";
    private static final String KEY_SEPARATOR = "gui.rs_create_compat.assembly.banner.separator";

    /**
     * 弹一条 5 行横幅（纯展示，不注册任何点击回调）：
     * 1 已挂起 / 2 产物 / 3 原因 / 4 已暂停 / 5 去监视器处置。
     * <p>行数恒为 5：第 1、2 行按「序列装配 / RS 原版任务」二选一（两者措辞不同），
     * 第 3 行按 {@link Reason} 三选一，其余两行完全一致。</p>
     */
    private static void sendBanner(final ServerLevel level, final Record record) {
        // 手动挂起不弹横幅：那条横幅是在解释「为什么它自己不动了」，而手动挂起是玩家刚刚亲手做的动作，
        // 监视器上的「已挂起：玩家手动挂起」标记 + 按钮状态就是它的全部反馈（与「继续」成功后不播报同一口径）。
        if (record.reason == Reason.MANUAL) {
            return;
        }
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(5);
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE, CompletionBannerPayload.localized(
            record.sequence ? KEY_SUSPENDED : KEY_SUSPENDED_AUTO)));
        // 第 2 行：产物名在前、物品图标紧跟在名字之后（用户原话「名字后跟图标」）。
        // 段是「图标 + 文本」的成对结构，所以「名后跟图」要拆成两段：先纯文本段，再「纯图标段」。
        //
        // <b>本轮修正（F 组：断链 ⇒ 必然挂起 + 必然发横幅）</b>：旧实现在这一行之前有
        // {@code if (record.productIcon.isEmpty()) { return; }} —— 图标取不到（产物物品被数据包移除、
        // 或该任务的目标不是可渲染的物品）时<b>整条横幅被静默丢弃</b>。可是「挂起」已经发生，
        // 玩家此刻最需要的恰恰是这条横幅（否则任务就那么停在那里、没有任何解释 —— 用户实测
        // 「跑单中途链路断开后，既没挂起提示也没横幅」）。现在把图标降级为<b>可选装饰</b>：
        // 取不到就用纯文本行照常发，横幅<b>永远发得出去</b>。
        final CompletionBannerPayload.Row productRow = record.productIcon.isEmpty()
            ? CompletionBannerPayload.Row.text(COLOR_TEXT,
                CompletionBannerPayload.localized(record.sequence ? KEY_PRODUCT : KEY_PRODUCT_AUTO,
                    record.amount, record.productName))
            : CompletionBannerPayload.Row.segments(COLOR_TEXT,
                List.of(ItemStack.EMPTY, record.productIcon),
                List.of(CompletionBannerPayload.localized(record.sequence ? KEY_PRODUCT : KEY_PRODUCT_AUTO,
                    record.amount, record.productName), ""));
        rows.add(productRow);
        rows.add(reasonRow(record));
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE, CompletionBannerPayload.localized(KEY_PAUSED)));
        rows.add(CompletionBannerPayload.Row.text(COLOR_HINT, CompletionBannerPayload.localized(KEY_HINT)));
        CompatCompletionSender.sendToNearby(level, record.executorPos, BANNER_RADIUS_SQ, rows);
        RsccDiag.recordBanner("watchdog_" + record.reason.name().toLowerCase(java.util.Locale.ROOT),
            record.taskId.toString());
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event("watchdog task=" + record.taskId + " reason=" + record.reason
                + " product=" + record.productName + " x" + record.amount
                + " stall=" + record.stallTicks + " tick=" + record.currentTick
                + " at=" + RsccAssemblyDebug.at(record.executorPos));
        }
    }

    /**
     * 第 3 行：按原因三选一（序列装配的掉线行会列出「第几步 / 哪台执行仓」，其余是单句结论）。
     * <p><b>本轮新增（用户实测：所有序列装配都报"设备掉线"）</b>：当 {@link Record#pushStalled} 为 true
     * 且 {@code offlineSteps} 为空时（机器还在、只是下游拒收/已满），走「输出阻塞」文案 ——
     * 与「设备掉线」区分，避免误导玩家去拆机器。当 {@code offlineSteps} 非空时（机器真的不在），
     * 仍走既有掉线行（列出哪几步的执行仓不在）。</p>
     */
    private static CompletionBannerPayload.Row reasonRow(final Record record) {
        return switch (record.reason) {
            // 2026-10-05：OUTPUT_BLOCKED 与 EXECUTOR_OFFLINE 分成两支 ——
            // 「下游机器满 / 不接受」不该说成「执行器掉线」（实测横幅与现场完全对不上：
            // 玩家被告知掉线，而机械手与置物台是空的、设备一台没少）。
            // 2026-10-06：带上「第 N 步」把话说到具体工位 —— 既有的 output_blocked_prefix /
            // output_blocked_suffix 两条文案此前<b>从未被引用过</b>（声明了却没有调用点），
            // 于是横幅只剩一句没有主语的结论。这里改用 outputBlockedRow 把它们接起来。
            case OUTPUT_BLOCKED -> outputBlockedRow(record);
            case EXECUTOR_OFFLINE -> {
                // 兼容旧口径：万一 pushStalled 为真但原因是「下游拒收」，仍然走输出阻塞文案。
                if (record.pushStalled && record.offlineSteps.isEmpty()) {
                    // 2026-10-06（P2）：原先这里只给一句没有步序的结论（KEY_OUTPUT_BLOCKED_AUTO），
                    // 而这一支恰恰是「某一步没有任何在线执行仓认领」那种真·无法继续 ⇒ 与
                    // OUTPUT_BLOCKED 共用 outputBlockedRow（按 stallDetail 的原因分文案，带上步序）。
                    yield outputBlockedRow(record);
                }
                // 真实掉线：列出哪几步的执行仓不在（序列装配）；RS 原版任务无步骤可言，给单句结论。
                if (record.sequence && !record.offlineSteps.isEmpty()) {
                    yield offlineRow(record);
                }
                yield CompletionBannerPayload.Row.text(COLOR_TEXT,
                    CompletionBannerPayload.localized(KEY_OFFLINE_AUTO));
            }
            case NO_PROGRESS -> CompletionBannerPayload.Row.text(COLOR_TEXT,
                CompletionBannerPayload.localized(KEY_NO_PROGRESS));
            default -> materialRow(record);
        };
    }

    /** 第 3 行（缺料）：由于 &lt;原料1 图标+名&gt;、&lt;原料2 …&gt; 缺少或自动合成失败。 */
    private static CompletionBannerPayload.Row materialRow(final Record record) {
        final List<ItemStack> icons = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        icons.add(ItemStack.EMPTY);
        texts.add(CompletionBannerPayload.localized(KEY_MATERIAL_PREFIX));
        colors.add(COLOR_TEXT);
        for (int i = 0; i < record.materialIcons.size(); i++) {
            if (i > 0) {
                icons.add(ItemStack.EMPTY);
                texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
                colors.add(COLOR_TEXT);
            }
            icons.add(record.materialIcons.get(i));
            texts.add(record.materialNames.get(i));
            colors.add(PALETTE[i % PALETTE.length]);
        }
        if (record.materialTotal > record.materialIcons.size()) {
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
            colors.add(COLOR_TEXT);
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_MATERIAL_MORE,
                record.materialTotal - record.materialIcons.size()));
            colors.add(COLOR_TEXT);
        }
        icons.add(ItemStack.EMPTY);
        texts.add(CompletionBannerPayload.localized(KEY_MATERIAL_SUFFIX));
        colors.add(COLOR_TEXT);
        return CompletionBannerPayload.Row.colored(icons, texts, colors);
    }

    /** 第 3 行（掉线）：由于 &lt;配方名（执行器名 @坐标）&gt;… 的执行器掉线。 */
    private static CompletionBannerPayload.Row offlineRow(final Record record) {
        final List<ItemStack> icons = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        icons.add(ItemStack.EMPTY);
        texts.add(CompletionBannerPayload.localized(KEY_OFFLINE_PREFIX));
        colors.add(COLOR_TEXT);
        final int listed = Math.min(record.offlineSteps.size(), MAX_LISTED);
        for (int i = 0; i < listed; i++) {
            final SyncAssemblyAlertsPacket.OfflineStep step = record.offlineSteps.get(i);
            if (i > 0) {
                icons.add(ItemStack.EMPTY);
                texts.add(CompletionBannerPayload.localized(KEY_SEPARATOR));
                colors.add(COLOR_TEXT);
            }
            icons.add(ItemStack.EMPTY);
            texts.add(step.recipeName() + "（" + step.machineName() + " @"
                + step.machinePos().toShortString() + "）");
            colors.add(PALETTE[i % PALETTE.length]);
        }
        if (record.offlineSteps.size() > listed) {
            icons.add(ItemStack.EMPTY);
            texts.add(CompletionBannerPayload.localized(KEY_OFFLINE_MORE, record.offlineSteps.size() - listed));
            colors.add(COLOR_TEXT);
        }
        icons.add(ItemStack.EMPTY);
        texts.add(CompletionBannerPayload.localized(KEY_OFFLINE_SUFFIX));
        colors.add(COLOR_TEXT);
        return CompletionBannerPayload.Row.colored(icons, texts, colors);
    }

    /**
     * 第 3 行（输出阻塞 / 某步无人认领）：<b>把话说到「哪一步 + 因为什么 + 无法继续」</b> ——
     * {@code 输出阻塞：第 <N> 步的下游工位收不下这一件，任务无法继续；到监视器点继续}。
     *
     * <p><b>为什么补这一条（2026-10-06，P2）</b>：{@link #KEY_OUTPUT_BLOCKED_PREFIX} /
     * {@link #KEY_OUTPUT_BLOCKED_SUFFIX} 从被加进语言文件那天起<b>就没有任何调用点</b>
     * （{@code reasonRow} 只用了 {@link #KEY_OUTPUT_BLOCKED_AUTO} 那一句），于是玩家只被告知
     * 「下游工位被占住」却<b>不知道是哪一步、也不知道为什么</b>；而用户要的是能回答
     * 「哪一步做完了、下一步为什么堵、还能不能继续」的一句话。现在步序取自记录里缓存的装配数据
     * （{@link Record#lastAssembly}）与 {@link Record#stallDetail} 里那台仓的坐标比对，
     * 原因取 {@link Record#stallDetail} 已经分好的 {@code StallReason} ——
     * 判据只用既有字段，不新增任何探测。</p>
     *
     * <p><b>步序取不到时</b>（老样板没记步序 / 缓存缺失）退化成 {@link #KEY_OUTPUT_BLOCKED_AUTO}
     * 的单句结论 —— 绝不因为取不到步序就不弹（与「横幅永远发得出去」同一口径）。</p>
     */
    private static CompletionBannerPayload.Row outputBlockedRow(final Record record) {
        final int step = stalledStepOf(record);
        if (step < 0) {
            return CompletionBannerPayload.Row.text(COLOR_TEXT,
                CompletionBannerPayload.localized(KEY_OUTPUT_BLOCKED_AUTO));
        }
        final boolean noOwner = record.stallDetail != null
            && record.stallDetail.reason() == SequenceExecutionChamberBlockEntity.StallReason.STEP_OWNER_MISSING;
        return CompletionBannerPayload.Row.colored(
            List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY),
            List.of(CompletionBannerPayload.localized(KEY_OUTPUT_BLOCKED_PREFIX),
                Integer.toString(step),
                CompletionBannerPayload.localized(noOwner ? KEY_PUSH_CAUSE_NO_OWNER : KEY_PUSH_CAUSE_REFUSED),
                CompletionBannerPayload.localized(KEY_OUTPUT_BLOCKED_SUFFIX)),
            List.of(COLOR_TEXT, PALETTE[0], PALETTE[1], COLOR_TEXT));
    }

    /**
     * <b>只读</b>：这次「推不动」命中的是哪一步（取不到返回 {@code -1}）。
     *
     * <p>判据：{@link Record#stallDetail} 里的那台执行仓坐标 == 某一步的指派机器坐标；
     * 样板自己记了步序（{@code unit.step() >= 0}）就用它，否则用该步在样板里的列下标。
     * 只读既有字段，不做任何方块查询。</p>
     */
    private static int stalledStepOf(final Record record) {
        final StallHit hit = record.stallDetail;
        if (hit == null || record.lastAssembly == null) {
            return -1;
        }
        final List<SequencePatternData.UnitEntry> units = record.lastAssembly.units();
        for (int column = 0; column < units.size(); column++) {
            final SequencePatternData.UnitEntry unit = units.get(column);
            if (unit.machinePos() != null && unit.machinePos().equals(hit.chamberPos())) {
                return unit.step() >= 0 ? unit.step() : column;
            }
        }
        return -1;
    }

    /**
     * <b>「下游又通了」的收尾提示</b>（恢复边沿；纯展示；同一状态只弹一次）。
     *
     * <p>它是「下游推不进去」那条横幅的另一半：堵住时玩家被问到「现在到底是等待还是继续」，
     * 这里给出答案 —— 下游已经能收料，任务仍停在暂停态，去监视器点「继续」就接着跑。
     * 与挂起动作本身无关（本方法<b>不</b>改任何状态），因此不会自动解挂，
     * 也就不违反「必须玩家点继续才恢复」这条硬规则。</p>
     */
    private static void sendPushClearedBanner(final ServerLevel level, final Record record) {
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(3);
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE,
            CompletionBannerPayload.localized(KEY_PUSH_CLEARED_TITLE)));
        // 产物行走既有的 product / product_auto 两条键（与挂起横幅同一套措辞、同一个参数顺序）。
        rows.add(CompletionBannerPayload.Row.text(COLOR_TEXT,
            CompletionBannerPayload.localized(record.sequence ? KEY_PRODUCT : KEY_PRODUCT_AUTO,
                record.amount, record.productName)));
        rows.add(CompletionBannerPayload.Row.text(COLOR_HINT,
            CompletionBannerPayload.localized(KEY_PUSH_CLEARED_BODY)));
        CompatCompletionSender.sendToNearby(level, record.executorPos, BANNER_RADIUS_SQ, rows);
        RsccDiag.recordBanner("watchdog_push_cleared", record.taskId.toString());
        if (RsccAssemblyDebug.isEnabled()) {
            RsccAssemblyDebug.event("watchdog push-cleared task=" + record.taskId
                + " product=" + record.productName + " x" + record.amount
                + " at=" + RsccAssemblyDebug.at(record.executorPos));
        }
    }

    /** 这条记录是不是因为「下游推不动」而被挂起（输出阻塞 / 该步无机器认领）。 */
    private static boolean pushStallReason(final Record record) {
        return record.reason == Reason.OUTPUT_BLOCKED
            || (record.reason == Reason.EXECUTOR_OFFLINE && record.pushStalled);
    }

    /**
     * <b>挂起期间的「下游是否已经能收料了」只读复查</b>（2026-10-06 新增；提示链路的恢复边沿）。
     *
     * <h2>为什么必须单独做这一步</h2>
     * <p>已挂起记录的<b>原因与快照是被冻结的</b>（见 {@code scanNetwork} 里那条分支：
     * 冻结保证监视器上的「已挂起：…」不会被改写成别的值，也不会有任何自动恢复）。
     * 冻结的代价是：<b>堵住时弹过的那条横幅没有对应的收尾</b> —— 玩家无从判断「现在是等待还是继续」。</p>
     *
     * <h2>2026-10-06 重写：判据从「堵塞证据不见了」改成「工位真的空出来了」（正向只读探针）</h2>
     * <p>旧判据（把既有的 {@code taskChamberPushStalled} 在挂起期间再问一次，空了就算通）在本仓被冻结时
     * <b>恒定误报</b>：挂起后 {@code isAutoCraftingEnabled()} 为假 ⇒ 本仓<b>不再尝试推送</b>、而且已被
     * {@code flushChamberForSuspend} 清空 ⇒ 拒收证据必然在 60 tick 窗口内过期 ⇒ 「证据不见了」。
     * 于是每次挂起后约 5 秒都会弹一条「下游已经能收料了」，玩家照做点「继续」，任务随即又被拒、
     * 又挂起 —— 正是用户说的「多发」。</p>
     * <p>新判据问的是<b>可验证的事实</b>：上次拒收我们的那个工位（{@code rscc$blockedTargetPos}）
     * 现在是不是<b>空的</b>（空 ⇒ 下一次推送必被收下）。口径与执行仓自己的堵塞自愈
     * （{@code recoverBlockingTargetItem}）同源 —— 都读同一个 {@code RsccChamberImportStrategy#itemHandlerAt}。
     * 探针只读，<b>绝不</b>改 {@code reason} / 快照 / 挂起状态。</p>
     *
     * <p><b>边沿语义</b>：工位连续 {@value #PUSH_CLEARED_HOLD_TICKS} tick 真的空着才弹一次，
     * 弹完置位；重新堵上时复位。同一次恢复绝不重复弹，并且这一刻同时把进入边沿
     * （{@link Record#pushNoticeArmed}）重新武装 —— 于是「堵 → 通 → 稳定 → 再堵」每一段各提示一次。</p>
     */
    private static void trackPushStallRecovery(final ServerLevel level, final Record record,
                                               final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers) {
        if (!pushStallReason(record) || record.stallDetail == null || record.lastAssembly == null
            || record.lastExecutorPos == null) {
            return; // 不是「推不动」挂起的记录 ⇒ 本提示不参与
        }
        final long now = level.getGameTime();
        // 流体目标没有对应的只读「空不空」判据（物品槽为空不等于罐子装得下）⇒ 不猜、不播。
        final String resource = record.stallDetail.resource();
        if (resource != null && resource.startsWith("fluid:")) {
            record.pushClearedNotified = false;
            record.pushClearedSinceTick = -1L;
            return;
        }
        if (!stationFree(level, blockedStationOf(chambers, record))) {
            record.pushClearedNotified = false; // 还堵着 / 又堵上：重新武装收尾提示
            record.pushClearedSinceTick = -1L;
            return;
        }
        if (record.pushClearedNotified) {
            return; // 已经提示过「通了」：同一状态绝不重复弹
        }
        if (record.pushClearedSinceTick < 0L) {
            record.pushClearedSinceTick = now;
            return;
        }
        if (now - record.pushClearedSinceTick < PUSH_CLEARED_HOLD_TICKS) {
            return;
        }
        record.pushClearedNotified = true;
        record.pushClearedSinceTick = -1L;
        // 工位真的空出来 = 下游确实能收料 ⇒ 进入边沿重新武装：下一次再堵上时两半提示各来一次。
        record.pushNoticeArmed = true;
        sendPushClearedBanner(level, record);
    }

    /**
     * <b>只读</b>：上次拒收我们的那台供料目标（取不到返回 {@code null}）。
     * <p>坐标由执行仓提供（{@code rscc$blockedTargetPos}）—— 它记的是「最近一次真的拒收过我们的
     * 那个坐标」，与执行仓自己的堵塞自愈用的是<b>同一个</b>字段，不新增第二套真源。</p>
     */
    @Nullable
    private static BlockPos blockedStationOf(final Map<BlockPos, SequenceExecutionChamberBlockEntity> chambers,
                                            final Record record) {
        final StallHit hit = record.stallDetail;
        if (hit == null) {
            return null;
        }
        final SequenceExecutionChamberBlockEntity chamber = chambers.get(hit.chamberPos());
        return chamber == null ? null : chamber.rscc$blockedTargetPos();
    }

    /**
     * <b>只读</b>：该工位此刻是不是空的（空 ⇒ 下一次推送必被收下）。
     * <p>取不到物品能力（方块没了 / 区块未加载 / 本来就不收物品）一律返回 {@code false} ——
     * 判不出来时绝不播「已经能收料了」（宁可不提示，也不给玩家一条假的安心话）。</p>
     */
    private static boolean stationFree(final ServerLevel level, @Nullable final BlockPos target) {
        if (target == null) {
            return false;
        }
        final IItemHandler handler = RsccChamberImportStrategy.itemHandlerAt(level, target);
        if (handler == null) {
            return false;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ==================== 同步 / 处置 API（服务端权威） ====================

    /**
     * 当前维度里所有<b>此刻有动作可做</b>的任务的只读快照。
     *
     * <p>「有动作可做」= 已挂起（可「继续」）或仍可被推进（可「挂起」）——
     * 后者同样要发给客户端，否则监视器上就看不到「挂起」按钮（本轮新增的手动挂起）。</p>
     */
    public static List<SyncAssemblyAlertsPacket.Alert> alerts(final ServerLevel level) {
        final Map<UUID, Record> records = RECORDS.get(level.dimension());
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        final List<SyncAssemblyAlertsPacket.Alert> result = new ArrayList<>();
        for (final Record record : records.values()) {
            if (record.actions() != SyncAssemblyAlertsPacket.ACTION_BIT_NONE) {
                result.add(record.snapshot());
            }
        }
        return result;
    }

    /** 把快照广播给该维度里「正开着自动合成管理器」的玩家（只有内容变化时才发，避免刷包）。 */
    private static void broadcast(final ServerLevel level) {
        final List<SyncAssemblyAlertsPacket.Alert> alerts = alerts(level);
        final String fingerprint = alerts.toString();
        if (fingerprint.equals(LAST_BROADCAST.get(level.dimension()))) {
            return;
        }
        LAST_BROADCAST.put(level.dimension(), fingerprint);
        if (alerts.isEmpty()) {
            // 仍然要把「空表」发给开着的玩家，让他们把按钮置灰
            LAST_BROADCAST.put(level.dimension(), fingerprint);
        }
        final SyncAssemblyAlertsPacket packet = new SyncAssemblyAlertsPacket(alerts);
        for (final ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof AbstractAutocraftingMonitorContainerMenu) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    /**
     * 把当前告警快照单独发给某个玩家（登录 / 换维度时补发，以及界面刚打开时按需补发 ——
     * 后者由 {@code RequestAssemblyAlertsPacket} 触发，否则「告警产生时没开着监视器」的玩家
     * 手里永远没有快照，监视器里的处置按钮就永远不会渲染）。
     */
    public static void sendAlerts(final ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
            new SyncAssemblyAlertsPacket(alerts(player.serverLevel())));
    }

    @Nullable
    private static Record findRecord(final ServerPlayer player, final UUID taskId) {
        final Map<UUID, Record> records = RECORDS.get(player.serverLevel().dimension());
        return records == null ? null : records.get(taskId);
    }

    /**
     * 该任务此刻是否被本模组挂起（{@code mixin/TaskContainerMixin} 每 tick 都会问这里的
     * 「挂起 = 不再被 step」的实现点）。
     *
     * <p>纯读、无副作用、客户端安全：挂起记录只由服务端扫描填充，客户端恒为空 → 直接返回 false，
     * 不会影响客户端 / 单人局里的本地自动合成。</p>
     */
    public static boolean isSuspended(final UUID taskId) {
        if (taskId == null || RECORDS.isEmpty()) {
            return false;
        }
        for (final Map<UUID, Record> records : RECORDS.values()) {
            final Record record = records.get(taskId);
            if (record != null) {
                return record.suspendState != SuspendState.RUNNING;
            }
        }
        return false;
    }

    /**
     * 「该任务已进入收尾 / 结束态」的<b>即时通知</b>（由 {@code mixin/TaskContainerMixin} 每 tick 调用）。
     *
     * <p><b>为什么要有它（用户硬要求）</b>：玩家按 RS <b>原生「取消」</b>后，任务当刻被
     * {@code TaskImpl#cancel()} 置为 {@code RETURNING_INTERNAL_STORAGE}；{@code TaskContainerMixin}
     * 会<b>放行</b> RS 自己的回收逻辑（当 tick 就把内部暂存 insert 回网络，见该 mixin 的注释）。
     * 但此时我方的挂起记录（以及据此发出的监视器告警快照）若还留着，界面就会继续显示
     * 「已挂起 / 继续」——那是<b>幽灵记录</b>：任务其实已经在收尾。因此这里同步把这条记录移除，
     * 并立刻重播一次告警快照，让那一行与按钮<b>当帧消失</b>，不必等下一次 20 tick 扫描。</p>
     *
     * <p><b>幂等、无副作用、只处理「确实被我们挂起过」的记录</b>：只有 {@code suspendState != RUNNING}
     * 的记录才会被移除（还在正常推进 / 从未挂起的任务一律不动，所以「运行中任务按原生取消」
     * 这条路径完全不受影响）。记录一旦移除，后续重复调用是空操作；下一次扫描若任务仍在收尾，
     * 重建出来的记录是 {@code suspended=false} 的（actions 为 NONE，界面不会画任何按钮），
     * 因此不会出现「移除 → 重建 → 再移除」的抖动或刷包。客户端安全：{@code RECORDS} 在客户端恒为空，
     * 直接返回。</p>
     */
    public static void onTaskTerminated(final UUID taskId) {
        if (taskId == null || RECORDS.isEmpty()) {
            return;
        }
        for (final Map.Entry<ResourceKey<Level>, Map<UUID, Record>> entry : RECORDS.entrySet()) {
            final Record record = entry.getValue().get(taskId);
            if (record == null || record.suspendState == SuspendState.RUNNING) {
                continue;
            }
            entry.getValue().remove(taskId);
            SUSPEND_EPOCH++; // 记录没了 = 挂起态没了：通知执行仓当刻重算门控（解冻 / 停摆）
            final ServerLevel level = LEVELS.get(entry.getKey());
            if (level != null) {
                broadcast(level); // 立刻重播一次告警快照：幽灵行 / 按钮当帧消失
            }
        }
    }

    /**
     * 「继续 / 恢复」：{@link SuspendState} 归位到 {@code RUNNING}，并让检测器<b>重新开始计时</b>
     * （不是立刻再弹一次）。这是<b>唯一</b>的恢复入口（挂起后一律不自动恢复，见类注释）。
     *
     * @return 该任务确实存在且被清除了挂起状态。
     */
    public static boolean resume(final ServerPlayer player, final UUID taskId) {
        final Record record = findRecord(player, taskId);
        if (record == null) {
            return false; // 任务已消失 → 安全失败
        }
        resumeRecord(record, player.serverLevel().getGameTime());
        record.lastSeenTick = player.serverLevel().getGameTime();
        // 日志取证（用户要求「挂起 / 继续要有日志证据」）：一条便够 —— 它标出玩家动作的<b>确切时刻</b>，
        // 与执行仓的 `gate ... reason=suspended_frozen` / `gate ... reason=relevant_task` 对照即可确认
        // 「挂起后确实不再有 pull / feed / push / 加工」。
        RsccAssemblyDebug.event("watchdog resume task=" + taskId + " product=" + record.productName
            + " x" + record.amount + " sequence=" + record.sequence
            + " at=" + RsccAssemblyDebug.at(record.executorPos)
            + " tick=" + player.serverLevel().getGameTime());
        return true;
    }

    /**
     * <b>「手动挂起」</b>：玩家在自动合成监视器里主动把一条正在跑的任务让出来
     * （「先挂起、让出位置，让别人先做」）—— <b>原版 RS 自动合成任务与序列装配任务都适用</b>。
     *
     * <p>挂起语义与自动挂起<b>完全一致</b>（与自动挂起共用唯一实现 {@link #suspendRecord}）：
     * 不再被 step ⇒ 不再占用执行器 / 不再抽取网络原料 / 不再投料，已加工中间件原地冻结在任务自身
     * {@code internalStorage} 里（<b>不复制、不销毁</b>），也不阻塞同一网络里的后续任务。区别只在原因标记：
     * 写 {@link Reason#MANUAL}，监视器上因此显示「已挂起：玩家手动挂起」。</p>
     *
     * <p><b>挂起后一律不自动恢复</b>（与自动挂起同一规则）：只有玩家再点「继续」才回到 {@code RUNNING}。</p>
     *
     * <p><b>服务端权威校验</b>（缺一不可）：任务确实还在服务端记录里、当前维度找得到（即玩家有权限触达它）、
     * 且此刻处于 {@link SuspendState#RUNNING}，并且不是 RS 正在回收内部暂存（{@link Record#returning}）——
     * 否则安全失败返回 false，绝不误挂别的任务、也绝不冻住 RS 自己的回收。</p>
     *
     * @return 校验通过且已把该任务挂起。
     */
    public static boolean suspend(final ServerPlayer player, final UUID taskId) {
        final Record record = findRecord(player, taskId);
        if (record == null || record.suspendState != SuspendState.RUNNING || record.returning) {
            return false; // 任务已消失 / 已挂起 / RS 正在回收内部暂存 → 安全失败
        }
        final long now = player.serverLevel().getGameTime();
        suspendRecord(record, Reason.MANUAL, now);
        record.lastSeenTick = now;
        // 日志取证（用户要求「挂起要有日志证据」）：这条标出<b>挂起当刻</b>，之后同一台仓的
        // pull / feed / push / 加工日志必须停止；执行仓会打出 `gate ... frozen=true reason=suspended_frozen`。
        RsccAssemblyDebug.event("watchdog suspend task=" + taskId + " product=" + record.productName
            + " x" + record.amount + " sequence=" + record.sequence
            + " at=" + RsccAssemblyDebug.at(record.executorPos) + " tick=" + now
            + " reason=manual");
        return true;
    }

    /**
     * 「更换机器」候选：该步配方类型下、同一网络内可用的序列执行仓（按坐标排序）。
     */
    public static List<SyncChamberListPacket.Entry> machineCandidates(final ServerPlayer player,
                                                                      final UUID taskId,
                                                                      final int stepIndex) {
        final Record record = findRecord(player, taskId);
        if (record == null) {
            return List.of();
        }
        final ServerLevel level = player.serverLevel();
        final Network network = networkOf(level, record);
        if (network == null) {
            return List.of();
        }
        final SequencePatternData.UnitEntry unit = unitOf(level, record, stepIndex);
        if (unit == null) {
            return List.of();
        }
        final String recipeType = unit.recipeType() == null ? "" : unit.recipeType();
        final List<SyncChamberListPacket.Entry> result = new ArrayList<>();
        for (final SequenceExecutionChamberBlockEntity chamber : chambersOf(network).values()) {
            if (!recipeType.isEmpty() && !recipeType.equals(chamber.getRecipeType())) {
                continue; // 只给同配方类型的机器（与样板终端的选择器判定一致）
            }
            result.add(new SyncChamberListPacket.Entry(chamber.getBlockPos(),
                chamber.getChamberDisplayName(),
                chamber.getRecipeType() == null ? "" : chamber.getRecipeType()));
        }
        result.sort((a, b) -> Long.compare(a.pos().asLong(), b.pos().asLong()));
        return result;
    }

    /**
     * 「更换机器」写入：把总样板上该步的执行仓指派改成 {@code pos}。
     * <p><b>不复制 / 不销毁物品</b>：只把同一张总样板读出来、改掉一步的指派字段、再写回原槽位。</p>
     */
    public static boolean changeStepMachine(final ServerPlayer player, final UUID taskId, final int stepIndex,
                                            @Nullable final BlockPos pos, final String name) {
        final Record record = findRecord(player, taskId);
        if (record == null) {
            return false;
        }
        final ServerLevel level = player.serverLevel();
        final BlockEntity blockEntity = level.getBlockEntity(record.executorPos);
        if (!(blockEntity instanceof SequenceAssemblyExecutorBlockEntity executor)) {
            return false; // 样板库已消失 → 安全失败
        }
        final Container inventory = executor.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            final SequencePatternData.AssemblyData assembly =
                stack.isEmpty() ? null : SequencePatternData.readAssembly(stack, level.registryAccess());
            if (assembly == null || !produces(assembly, record.product)) {
                continue;
            }
            if (stepIndex < 0 || stepIndex >= assembly.units().size()) {
                return false;
            }
            final List<SequencePatternData.UnitEntry> units = new ArrayList<>(assembly.units());
            final SequencePatternData.UnitEntry unit = units.get(stepIndex);
            // 必须把「输入原料组候选」原样带过去（unit.inputCandidates()）。
            // 漏掉它的后果（本轮日志/审计时发现）：换机器会**静默清空**该步的候选组
            // （UnitEntry 的紧凑构造器里 candidatesOrRepresentative() 会退回「只有代表物」），
            // 于是列车轨道那种「铁粒 或 锌粒」的总样板在玩家换过一次机器后，
            // 又退化成「只要铁粒」——与 §7.1 修的 bug 是同一个症状，只是从另一条路复发，
            // 而且因为不落任何日志，玩家只会看到「锌粒又没了」。
            units.set(stepIndex, new SequencePatternData.UnitEntry(unit.machine(), unit.count(), unit.input(),
                unit.crafter(), unit.recipe(), unit.step(), pos, name == null ? "" : name,
                unit.recipeType(), unit.inputFluid(), unit.inputCandidates()));
            // 总样板**重建**这一层也必须带上「起步原料候选组」（assembly.ingredientCandidates()），
            // 与上面那行 inputCandidates 是**同一个口径**：AssemblyData 的第 6 参就是主原料候选组。
            // 为什么不能省：AssemblyData 的候选组一旦缺省成空表，candidatesOrRepresentative() 就退回
            // 「只有代表物一件」，于是换过机器后石头台阶那组候选**静默消失**
            // （症状：主原料只认一个候选；与 inputCandidates 那条路是同一类坑，只是发生在总样板层）。
            // 这里显式给第 6 参 = 唯一做法，不再走会吞候选组的便捷构造器（该构造器已删除）。
            final SequencePatternData.AssemblyData updated = new SequencePatternData.AssemblyData(
                assembly.ingredient(), assembly.loops(), units, assembly.results(), assembly.scraps(),
                assembly.ingredientCandidates());
            // 原地改写同一张样板（数量 / 槽位不变，绝不复制）
            final ItemStack replacement = stack.copyWithCount(stack.getCount());
            SequencePatternData.writeAssembly(replacement, updated, level.registryAccess());
            inventory.setItem(slot, replacement);
            inventory.setChanged();
            return true;
        }
        return false;
    }

    /** 该总样板的产物里是否包含这条任务的产物资源。 */
    private static boolean produces(final SequencePatternData.AssemblyData assembly, final ItemResource product) {
        for (final SequencePatternData.PatternOutput output : assembly.results()) {
            if (!output.stack().isEmpty() && ItemResource.ofItemStack(output.stack()).equals(product)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static SequencePatternData.UnitEntry unitOf(final ServerLevel level, final Record record,
                                                        final int stepIndex) {
        final BlockEntity blockEntity = level.getBlockEntity(record.executorPos);
        if (!(blockEntity instanceof SequenceAssemblyExecutorBlockEntity executor)) {
            return null;
        }
        final Container inventory = executor.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            final SequencePatternData.AssemblyData assembly =
                stack.isEmpty() ? null : SequencePatternData.readAssembly(stack, level.registryAccess());
            if (assembly == null || !produces(assembly, record.product)) {
                continue;
            }
            if (stepIndex >= 0 && stepIndex < assembly.units().size()) {
                return assembly.units().get(stepIndex);
            }
            return null;
        }
        return null;
    }

    /**
     * 记录的锚点坐标 → 所在网络。
     * <p>锚点对序列装配任务 = 样板库坐标，对 RS 原版任务 = 首次发现它时的扫描入口（任意网络节点）。
     * 因此这里对「任意 RS 网络节点」都要能取到网络（见 {@link #nodeNetwork}），不能只认自家的样板库。</p>
     */
    @Nullable
    private static Network networkOf(final ServerLevel level, final Record record) {
        final BlockEntity blockEntity = level.getBlockEntity(record.executorPos);
        return blockEntity == null ? null : nodeNetwork(blockEntity);
    }

    /** 该维度里某个步骤的当前指派机器名（供管理器展示；找不到返回空串）。 */
    public static String currentMachineName(final ServerPlayer player, final UUID taskId, final int stepIndex) {
        final Record record = findRecord(player, taskId);
        if (record == null) {
            return "";
        }
        final SequencePatternData.UnitEntry unit = unitOf(player.serverLevel(), record, stepIndex);
        return unit == null || unit.machineName() == null ? "" : unit.machineName();
    }

    /** 该维度里某个步骤的配方类型（供管理器请求候选时回传）。 */
    public static String stepRecipeType(final ServerPlayer player, final UUID taskId, final int stepIndex) {
        final Record record = findRecord(player, taskId);
        if (record == null) {
            return "";
        }
        final SequencePatternData.UnitEntry unit = unitOf(player.serverLevel(), record, stepIndex);
        return unit == null || unit.recipeType() == null ? "" : unit.recipeType();
    }

    /** 该维度里当前记录数（自检用）。 */
    public static int recordCount(final ServerLevel level) {
        final Map<UUID, Record> records = RECORDS.get(level.dimension());
        return records == null ? 0 : records.size();
    }

    /**
     * <b>只读诊断</b>：该维度当前已加载区块的坐标（打包 long，与 {@code ChunkPos#asLong} 同构）。
     * <p>供 {@link RsccDiag} 枚举方块实体（执行舱 / 总线 / 定量保持器）使用 ——
     * 直接复用本类已经维护好的「已加载区块」表，避免第二次 BFS / 第二份真源。只读，不改变任何状态。</p>
     */
    public static Set<Long> rscc$loadedChunks(final ResourceKey<Level> dimension) {
        return LOADED_CHUNKS.get(dimension);
    }

    /**
     * <b>只读诊断</b>：该维度全部任务记录的结构化快照（供 {@code /rs_create_compat diag} 导出）。
     * <p>逐条给出「任务 id / 执行舱坐标 / 产物 / 数量 / 原因 / 是否挂起 / 挂起态 / 停滞计时 /
     * 是否序列装配 / 是否正在回收 / 缺料种数 / 掉线步骤摘要」。<b>不</b>读取 / 修改任何方块或任务对象，
     * 只把记录里既有的不可变字段拼成 map（因此可以在任何时刻安全调用，含产线运行中）。</p>
     */
    public static List<Map<String, Object>> rscc$diagRecords(final ServerLevel level) {
        final List<Map<String, Object>> out = new ArrayList<>();
        final Map<UUID, Record> records = RECORDS.get(level.dimension());
        if (records == null) {
            return out;
        }
        for (final Record record : records.values()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("dimension", level.dimension().location().toString());
            row.put("task", record.taskId.toString());
            row.put("pos", record.executorPos.getX() + "," + record.executorPos.getY()
                + "," + record.executorPos.getZ());
            row.put("product", record.productName);
            row.put("amount", record.amount);
            row.put("reason", record.reason.name());
            row.put("suspended", record.suspended);
            row.put("suspendState", record.suspendState.name());
            row.put("stallTicks", record.stallTicks);
            row.put("sequence", record.sequence);
            row.put("returning", record.returning);
            row.put("missingMaterials", record.materialTotal);
            // 整单口径缺口（用户要的「还差多少个」）
            row.put("materialDeficit", record.materialDeficit);
            row.put("offlineSteps", offlineSummary(record.offlineSteps));
            // 2026-10-05：把「推不动」的具体原因与资源也导出 —— 旧快照只有 reason=EXECUTOR_OFFLINE
            // 一个词，无法区分「下游机器满」与「某步没有机器认领」（实测因此无法自证现场）。
            final StallHit stall = record.stallDetail;
            row.put("pushStalled", record.pushStalled);
            row.put("stallCause", record.pushStalled && stall != null ? stall.reason().name() : "-");
            row.put("stallChamber", record.pushStalled && stall != null
                ? stall.chamberPos().getX() + "," + stall.chamberPos().getY() + "," + stall.chamberPos().getZ()
                : "-");
            row.put("stallResource", record.pushStalled && stall != null ? stall.resource() : "-");
            out.add(row);
        }
        out.sort((a, b) -> String.valueOf(a.get("task")).compareTo(String.valueOf(b.get("task"))));
        return out;
    }

    /** 打包区块坐标（x 高 32 位、z 低 32 位，与 {@code ChunkPos#asLong} 同构）。 */
    private static long packChunk(final int x, final int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    /** 一条总样板的只读引用（宿主样板库坐标 + 产物 + 装配数据）。 */
    private record PatternRef(BlockPos executorPos, ItemStack product,
                              SequencePatternData.AssemblyData assembly) {
    }
}
