package cretae.cookiewyq.rs_create_compat.block.entity;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.NetworkResourceReceiverNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.NetworkResourceRequesterNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.PendingSenderSends;
import cretae.cookiewyq.rs_create_compat.support.RequesterSenderBackend;
import cretae.cookiewyq.rs_create_compat.support.SenderShortage;
import cretae.cookiewyq.rs_create_compat.support.ViewOnlyHandlers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「网络资源请求器」方块实体：<b>红石上升沿 + 界面手动按钮</b>两种触发，同一个方块。
 *
 * <h2>它负责什么</h2>
 * <ol>
 *     <li><b>红石上升沿</b>：{@link #onRedstonePowerChanged(boolean)} 里<b>逐字照搬 Create 红石请求器</b>
 *     的边沿语义（{@code RedstoneRequesterBlockEntity.java:77-88}）：
 *     <pre>if (redstonePowered == hasNeighborSignal) return;   // 同电平不重复触发
 *lastRequestSucceeded = false;
 *if (hasNeighborSignal) triggerRequest();
 *redstonePowered = hasNeighborSignal;</pre>
 *     区别只有一处：Create 从 {@code level} 现查信号，本方块由
 *     {@code NetworkResourceRequesterBlock#neighborChanged} 把信号值传进来
 *     —— 因为本方块的 blockstate JSON 只有 {@code active} 两个变体，不能再用一个
 *     {@code POWERED} 方块状态去转发（理由见方块类的 javadoc）；</li>
 *     <li><b>配置存自己 NBT</b>：目标名（{@code TargetName}）、「上次请求是否成功」
 *     （{@code LastRequestSucceeded}）、订单清单（{@code Orders}）、待投计划（{@code PendingPlan}）
 *     与回滚暂存（{@code ReturnBuffer}）都落在方块实体 NBT 里 —— 与 Create 的
 *     {@code EncodedAddress} / {@code EncodedRequest} 同一存法（设计文档 §2.1 / §7.3）；</li>
 *     <li><b>目标解析</b>：{@link #resolveReceiversByName(String)} 在<b>自己所在网络</b>里按名字找接收器，
 *     0 个 = 未找到、1 个 = 命中、≥2 个 = <b>重名</b>（{@link #triggerRequest()} 据此<b>拒绝投递</b>，绝不随机挑一个）。</li>
 *     <li><b>投递</b>：把订单量按「每 tick 批数（速度升级）× 单批量（叠加升级）」分批写进接收器缓存，
 *     写不进去的余量先回滚网络、网络也不收才进 {@link #returnBuffer}；见下面的数据流。</li>
 * </ol>
 *
 * <h2>投递数据流（所有数值只有一处来源 = 升级容器 → {@code RsccRateLimiter}）</h2>
 * <pre>triggerRequest()
 *   → 节流闸门 throttleTick()（间隔恒为 1 tick：闸门每次触发都放行；
 *     真正的提速旋钮是「每 tick 批数」= throttleBatchesPerTick()，速度升级 = 1/2/4/…/64 批）
 *   → resolveReceiversByName(targetName)：0 / 1 / ≥2 三种结果，≥2 一律拒绝
 *   → 把「这次触发要投的量」并入 pendingPlan（= 订单数量；<b>每次触发都投这么多</b>）
 *   → 逐条计划：每批 want = min(还差多少, getThrottleBatch())（叠加升级 → 单批量）
 *       → net.extract(SIMULATE) 算能取多少
 *       → 接收器缓存 SIMULATE 算能吃多少
 *       → net.extract(EXECUTE) 取 min(能取, 能吃)
 *       → 写接收器（物品 / 流体进缓存；其它 PlatformResourceKey 由接收器直通它朝向的容器）
 *       → 写不进去的余量 net.insert 回滚；网络也拒收 ⇒ 留在 returnBuffer 下一 tick 重试
 *       → <b>在同一轮内循环分批</b>，直到该条达到目标量 / 取不到 / 吃不下 /
 *         本 tick 的批数用完（min(MAX_BATCHES_PER_TICK, throttleBatchesPerTick())）
 *   → 只有「本 tick 批数用完」造成的没投完才留在 pendingPlan 里，由 doWork() 下一 tick 接着投
 *     （同一请求，不重复投；pendingPlan 随 NBT 落盘，拆方块 / 重进存档后继续投完）</pre>
 *
 * <p><b>订单语义</b>：订单清单 = 「<b>每次触发</b>投这么多」的目标量（不是「总共投这么多」、也不是
 * 会递减的清单）。因此一次红石上升沿 / 点一次「立即发送」= 一次请求，请求量就是清单上的数量；
 * 两次触发 = 两次请求，各自的数量都要投出去（测试里钉住了这个语义，见
 * {@code tools/selfcheck_round71_new_machine_pacing.py} §6 / §7）。</p>
 *
 * <p><b>守恒铁律</b>：每一轮都满足「取出的量 == 写入接收器的量 + 回滚进网络的量 + 暂存在自己缓冲的量」。
 * 缓冲 {@link #returnBuffer} 会落盘，因此拆方块 / 重进存档都不会让资源凭空消失。</p>
 *
 * <h2>它<b>不</b>负责什么</h2>
 * <ul>
 *     <li><b>界面</b>：资源的选择 / 数量 / 发送全部在 RS 网格界面（{@code client/grid/ResourcePicker*}）里完成，
 *     方块实体只提供「清单 + 投递」这一份权威状态；</li>
 *     <li><b>不实现比较器输出</b>：设计文档 §7.5 第 3 条「建议 B 也提供比较器输出」尚未落地
 *     （当前方块没有比较器输出语义，界面也不显示相关数值）。</li>
 * </ul>
 *
 * <h2>升级槽与节流（已接好，且投递就接在闸门之后）</h2>
 * <p>设计文档 §1.4 第 2 条把两台机器的旋钮定了下来，<b>两台都装 6 槽、只收速度 + 叠加</b>：
 * <b>速度升级 = 每 tick 几批，叠加升级 = 每批多少（放大单批投递量）</b>。
 * 两者都经 {@link RsccRateLimiter} 换算，<b>一处都没有自写公式</b>，也<b>没有</b>用 RS 的
 * {@code UpgradeContainer} ticker（它的 {@code 9 - 2n} 在 6 个速度升级时是 {@code -3}，负节拍）。</p>
 * <p><b>速度升级 = 每 tick 几批</b>（0/1/…/6 个 ⇒ 1/2/4/8/16/32/64 批），
 * <b>叠加升级 = 每批多少</b>（64…448 件）。<b>一次触发 = 把整张订单排进 {@link #pendingPlan}，
 * 按「每 tick 批数」逐步投完：总量恒等于订单量</b>（不存在「一次只投 64 件」的上限），
 * 只是可能要跨多 tick 完成；只有「本 tick 批数用完」的部分会自动留下续投，
 * 缓存满 / 网络不够属于缺口（明确提示、本次请求结束，下次触发重新计算）。这句话写进了升级槽 tooltip
 * （网格工具栏的节拍文字；第 131 轮起节拍固定，不再由升级给出）。</p>
 * <p>节流闸门接在 {@link #triggerRequest()} 上（{@link #throttleTick()}），真实投递在闸门之后；
 * 每 tick 的动作只有两类：{@link #doWork()} 里的缓冲重试，以及「上一 tick 批数没用完的请求」续投
 * （{@link #continuePendingDelivery()}）。两者都为空时，{@code doWork()} 一个网络查询都不做。</p>
 */
public class NetworkResourceRequesterBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<NetworkResourceRequesterNetworkNode> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 目标（接收器名字）在 NBT 里的键。名字是本方块唯一的「必须配置项」。 */
    private static final String TAG_TARGET_NAME = "TargetName";
    /** 上次请求是否成功（Create 同名字段的对应物，供后续界面 / 比较器输出使用）。 */
    private static final String TAG_LAST_SUCCESS = "LastRequestSucceeded";
    /**
     * 服务端权威的「上一次看到的红石电平」，用于上升沿判定。
     * <p><b>落盘</b>：重进存档后第一次 {@code neighborChanged} 若读到与上次相同的电平，不能误判成新脉冲
     * （反之也不能因为读档把这个状态清零而丢掉一次真实上升沿）。</p>
     */
    private static final String TAG_POWERED = "RedstonePowered";
    /** 订单清单在 NBT 里的键（形状 = RS 的 `{resource, amount}` 列表）。 */
    private static final String TAG_ORDERS = "Orders";
    /** 上一次解析成功的接收器坐标（只用于区分「目标区块未加载」与「目标真的没了」）。 */
    private static final String TAG_LAST_TARGET = "LastTargetPos";
    /** 回滚失败、暂存在请求器自己身上的资源（必须落盘，否则拆方块就凭空消失）。 */
    private static final String TAG_RETURN_BUFFER = "ReturnBuffer";
    /**
     * 跨 tick 续投的「还差多少」（必须落盘：一次触发可能因为本 tick 批数不够而跨多 tick 投完，
     * 中途重进存档 / 区块卸载后必须接着投，否则玩家看到的是「脉冲只投了一部分」）。
     */
    private static final String TAG_PENDING_PLAN = "PendingPlan";
    /**
     * 最近一轮投递的账（计划 / 已投 / 缺口 / 是否进行中）。
     * <p>落盘的理由只有一个：重进存档后界面仍然显示上一轮的真实结果，而不是凭空归零
     * （归零会被玩家当成「我明明投过」的撒谎界面）。它不参与投递，读档后也不续算。</p>
     */
    private static final String TAG_ROUND_PLANNED = "RoundPlanned";
    private static final String TAG_ROUND_DELIVERED = "RoundDelivered";
    private static final String TAG_ROUND_SHORTFALL = "RoundShortfall";
    private static final String TAG_ROUND_ACTIVE = "RoundActive";

    /**
     * 订单清单的编解码：直接用 RS 自己的 {@code ResourceAmount} codec（物品 / 流体同一个 codec），
     * 因此 NBT 形状就是 RS 磁盘那种 `{resource: {...}, amount: <long>}`，零自定义格式。
     */
    private static final Codec<List<ResourceAmount>> ORDER_CODEC = ResourceCodecs.AMOUNT_CODEC.listOf();

    /** 同一条失败提示的最小间隔（tick）：防红石钟每 2 tick 一次把聊天框刷爆；首次永远立即提示。 */
    private static final long HINT_COOLDOWN_TICKS = 20L;

    /**
     * 单 tick 批数的<b>硬上限</b>：纯粹「防单 tick 卡死」的兜底护栏，<b>正常玩法到不了</b>。
     *
     * <p>每 tick 批数由速度升级给出（{@code throttleBatchesPerTick()} = 1/2/4/…/64，
     * 满级也只有 64）；实际预算是 {@code Math.min(MAX_BATCHES_PER_TICK, throttleBatchesPerTick())}，
     * 因此 64 &lt; 512 ⇒ 这条常量在正常玩法下永远不生效，只在「以后有人把批数公式改到离谱值」时兜底。</p>
     *
     * <p>一批 = 1 次 {@code extract(SIMULATE)} + 1 次接收器 SIMULATE + 1 次 {@code extract(EXECUTE)}
     * + 1 次接收器 EXECUTE。成本主要在接收器侧的 {@code ItemStack} / {@code FluidStack} 构造
     * 与一次槽位读写（量级 µs），而网络侧只是一次 Map 查询 + 长整型加减；满速度 + 满叠加
     * （64 批 × 448 件）≈ 28672 件 / tick，仍在毫秒级。</p>
     *
     * <p><b>订单数量本身没有上限</b>：本 tick 没投完的部分留在 {@link #pendingPlan} 里
     * （并随 NBT 落盘），下一 tick 由 {@link #doWork()} → {@link #continuePendingDelivery()} 接着投。</p>
     *
     * <p>只数「真的从网络取到资源」的批次：SIMULATE 发现没料 / 吃不下时不计，因此
     * 「网络空着」的请求不会因为反复探测而卡住主线程。</p>
     *
     * <p>预算是<b>按游戏 tick</b>算的（见 {@link #deliveryBudgetUsed}）：同一个 tick 里
     * 「红石上升沿触发一次 + doWork 续投一次」合计也不会超过这个批数。</p>
     */
    private static final int MAX_BATCHES_PER_TICK = 512;

    /** 目标接收器的名字（空串 = 未配置）。 */
    private String targetName = "";
    /** 上一次请求是否成功（本轮是否有任何一件资源真的落进接收器缓存）。 */
    private boolean lastRequestSucceeded;
    /** 服务端权威的红石电平（上升沿判定的唯一状态）。 */
    private boolean redstonePowered;

    /**
     * 订单清单：每条 = 「资源 + 数量」，顺序即投递顺序。
     * <p><b>为什么用 RS 的 {@link ResourceAmount}</b>：它自己就带 {@code resource + amount} 的 codec，
     * 且物品 / 流体共用同一份实现（{@code ResourceCodecs.AMOUNT_CODEC}）—— 不另造第二种落盘格式。</p>
     * <p>数量口径与 RS 一致：物品 = 个数；流体 = mB（1 桶 = 1000 mB）。</p>
     */
    private final List<ResourceAmount> orders = new ArrayList<>();

    /**
     * 跨 tick 续投用的「待投计划」：本次请求<b>还差多少没投进接收器</b>。
     *
     * <p><b>它是计划，不是资源</b>：计划里的每一件都还在 RS 网络里，只有真的 {@code extract(EXECUTE)}
     * 成功才可能进 {@link #returnBuffer}。它<b>随 NBT 落盘</b>（{@value #TAG_PENDING_PLAN}）：
     * 一次触发把整张订单排进来，按「每 tick 批数」逐步投完 —— 订单越大 / 速度升级越少，
     * 跨的 tick 越多，中途区块卸载 / 重进存档后必须接着投。丢掉它虽然不会丢资源，
     * 但会让玩家看到「脉冲只投了一部分」，违背「一次请求最终投出的总量 = 订单量」的语义。</p>
     *
     * <p><b>什么时候非空</b>：只有「本 tick 批数（速度升级 → {@code throttleBatchesPerTick()}，
     * 硬上限 {@link #MAX_BATCHES_PER_TICK}）用完了、订单还没投完」时才有剩余（自动续投）；
     * 缓存满 / 网络不够属于缺口，本次请求就地结束并提示（等下一次触发重新计算，不无限空转）。</p>
     *
     * <p><b>为什么按资源合并、且跨触发累加</b>：订单语义是「<b>每次触发</b>投这么多」（见类 javadoc），
     * 所以两次触发 = 两次请求，两次的数量都要投出去；同资源合并成一条，因此条目数只与
     * 「订单里有几种资源」有关，不会随触发次数无限增长（合并用饱和加法，见 {@link #mergeIntoPendingPlan}）。</p>
     *
     * <p><b>不会重复投 / 不会过量的唯一理由</b>：{@link #deliverBatch} 每批的请求量恒为
     * {@code min(还剩多少, 单批量)}，且计划量<b>只按真的写进接收器的量</b>递减
     * （{@code remaining -= inserted}）—— 回滚掉的部分没有投进去，计划量因此不会被多减。</p>
     */
    private final List<ResourceAmount> pendingPlan = new ArrayList<>();

    /**
     * 最近一次 {@link #deliverBatch} 是否出现了「取不到 / 接收器吃不下 / 只投出一部分」的缺口。
     * <p>只用于给 {@code lastRequestSucceeded} 一个诚实的结论（{@code 订单 80 而网络只有 50}
     * 必须<b>不是</b>成功，并且已经提示过具体数量）；它不是投递状态机的一部分，下一轮投递开头就会重置。</p>
     */
    private boolean shortfallThisDelivery;

    /**
     * 最近一轮投递的「账」：计划总量 / 已投 / 缺口（单位 = 资源自身：物品件数 / 流体 mB）。
     *
     * <p><b>为什么要有这三个数</b>：界面要显示「已投 / 剩余 / 缺口」（第 95 轮用户要求界面真的
     * 显示内容，不是只有状态行）。这三项都是<b>服务端权威</b>的投递事实，客户端只读镜像：
     * 客户端重建菜单时 {@code requester == null}，任何自算都会分叉。</p>
     *
     * <p><b>语义（一轮 = 一次触发）</b>：</p>
     * <ul>
     *     <li>{@code roundPlanned} = 触发那一刻订单清单的数量总和；</li>
     *     <li>{@code roundDelivered} = 这一轮真的写进接收器缓存的量（跨 tick 续投累加）；</li>
     *     <li>{@code roundShortfall} = 这一轮结束时「投不出去」的量（网络没有 / 接收器吃不下 /
     *     目标没了）。续投中不记缺口（还打算投），只有本轮真的结束且没投完才落这个数。</li>
     * </ul>
     * <p>「剩余」不单独存：{@code planned - delivered - shortfall} 就是它（成功投完 = 0，
     * 续投中 = 还差的量，出现缺口后 = 0 且缺口里已经计入）。</p>
     *
     * <p><b>只是账，不是状态机</b>：这三个字段不参与任何投递判断，读档后继续显示上一轮的结果；
     * 再次触发时整体重置。因此即使它们与真实缓存不一致，也绝不会影响搬运 / 守恒。</p>
     */
    private long roundPlanned;
    private long roundDelivered;
    private long roundShortfall;
    /** 本轮是否还在进行（续投中）。只影响界面文案的「进行中」措辞，不影响搬运。 */
    private boolean roundActive;

    /**
     * 「批量预算」的按 tick 记账：上一轮投递发生的 gameTime + 那个 tick 已经花掉的批数。
     * <p>为什么需要它：同一个游戏 tick 里可能先被红石 / 按钮触发一次（{@link #triggerRequest()}），
     * 之后又轮到 {@link #doWork()} 续投一次 —— 两处各花一整份批数就等于预算翻倍。
     * 记下「本 tick 已用多少批」后，无论被调用几次，合计都不会超过预算。</p>
     */
    private long deliveryBudgetTick = Long.MIN_VALUE;
    private int deliveryBudgetUsed;

    /**
     * 回滚失败的暂存区：写入接收器失败 ⇒ 先 {@code insert} 回 RS 网络；网络也拒收的那部分留在这里，
     * 下一 tick（{@link #doWork()}）再试。**必须落盘** —— 它装的是真实资源。
     */
    private final List<ResourceAmount> returnBuffer = new ArrayList<>();

    /**
     * 上一次解析成功的接收器坐标。
     * <p><b>为什么要记住</b>：{@code resolveReceiversByName} 只能枚举「已加载」的网络节点，
     * 因此目标区块未加载与目标被拆除在解析结果上完全一样（都是 0 个）。记住坐标后用
     * {@code Level#isLoaded} 就能把两者分开：未加载 ⇒ 本轮静默跳过（下轮再试，配置不动）；
     * 已加载却解析不到 ⇒ 真的找不到 ⇒ 明确拒绝并提示。</p>
     */
    @Nullable
    private BlockPos lastResolvedTargetPos;

    /**
     * 「合成后补投」的待办（第 131 轮 F 组）：玩家选「合成 25 并发送 75」之后，
     * 缺口由 RS 异步合成，这里每 tick 看一次「网络里现在够不够」—— 够了就投给目标接收器。
     * <p><b>不落盘</b>：它描述「合成任务还在跑」这一当下的机器状态，写进 NBT 只会变成
     * 「一个永远等不到货的补投任务」；不落盘最坏只是「重进存档后这次补投不做了」，资源仍在网络里。</p>
     */
    private final List<PendingSenderSends.PendingSend> pendingSends = new ArrayList<>();

    /** 失败提示的限频表（reason → 上次提示的 gameTime）。只为防刷屏，不改任何投递语义。 */
    private final Map<String, Long> lastHintTicks = new LinkedHashMap<>();

    /**
     * 节流闸门的倒计时（tick）：与接收器同一写法（先自减再判 ⇒ 间隔 1 时恒等于每次触发都放行）。
     * <p>间隔恒为 1（{@code throttleIntervalTicks()} 写死）：速度升级的实际旋钮是
     * {@code throttleBatchesPerTick()}（每 tick 批数），不是缩短间隔。</p>
     * <p><b>为什么不落盘</b>：请求是「显式触发」的，闸门只描述「这次触发要不要立刻放行」，
     * 属于此刻的机器状态；落盘一个可能说谎的计数器只会引入「读档后卡 N tick」这类看不见的偏差。</p>
     */
    private int throttleCooldown;

    public NetworkResourceRequesterBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_BLOCK_ENTITY.get(), pos, state,
            new NetworkResourceRequesterNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
    }

    // ==================== 访问器 ====================

    /** 本机的网络节点（供能力注册与目标解析使用）。 */
    public NetworkResourceRequesterNetworkNode getNode() {
        return mainNetworkNode;
    }

    // ==================== 节拍（第 131 轮起固定，不再吃升级） ====================

    /**
     * 节流闸门：返回「这一轮要不要动作」。
     *
     * <p>第 131 轮用户拍板：「发送器跟插件槽没关系，<b>红石激活的速度决定发送速度</b>」——
     * 因此本闸门只剩一个物理下限（每次触发都放行）。真正决定吞吐的是
     * {@link #getThrottleBatchesPerTick()} / {@link #getThrottleBatch()} 两个常量。
     * 保留本方法是为了让 {@code triggerRequest} 的闸门顺序（F0 → F1 → F2 → F3）与文档 /
     * 历史断言保持一致：闸门仍然存在，只是永远放行。</p>
     */
    public boolean throttleTick() {
        return true;
    }

    /** 每 tick 批数（固定常量；红石脉冲多快就发多快，不吃速度升级）。 */
    public int getThrottleBatchesPerTick() {
        return cretae.cookiewyq.rs_create_compat.support.SenderDelivery.FIXED_BATCHES_PER_TICK;
    }

    /** 单批投递量（固定常量；不吃叠加升级）。 */
    public long getThrottleBatch() {
        return cretae.cookiewyq.rs_create_compat.support.SenderDelivery.FIXED_BATCH_SIZE;
    }

    /** 本机耗电（FE/t）：与接收器同一口径。 */
    public long getEnergyUsage() {
        return 2;
    }

    /**
     * 本方块<b>没有</b>红石模式按钮：它的红石语义是「上升沿触发一次」，
     * 与 RS 那三档「忽略 / 高电平工作 / 低电平工作」不是同一个概念，
     * 硬套上去只会让玩家以为可以调成「忽略红石却仍然脉冲触发」。
     * 因此与执行舱同口径返回 false（界面右侧不出现那个按钮）。
     */
    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /** 目标接收器名字（空串 = 未配置）。 */
    public String getTargetName() {
        return targetName;
    }

    /** 设置目标名（{@code null} 归一为 {@code ""}）；解析是「每次现解析」，因此改名自动跟随。 */
    public void setTargetName(@Nullable final String name) {
        final String value = name == null ? "" : name;
        if (value.equals(this.targetName)) {
            return;
        }
        this.targetName = value;
        setChanged();
    }

    /** 是否已配置到「可以尝试投递」的程度（判据只有目标名一项：订单可以为空，空订单会在触发时明确提示）。 */
    public boolean isProperlyConfigured() {
        return targetName != null && !targetName.isEmpty();
    }

    /** 上次请求是否成功（供界面 / 后续比较器输出）。 */
    public boolean wasLastRequestSuccessful() {
        return lastRequestSucceeded;
    }

    // ==================== 最近一轮投递的账（只读；界面显示「已投 / 剩余 / 缺口」） ====================

    /** 最近一轮的计划总量（= 触发那一刻订单清单的数量总和）。 */
    public long getRoundPlanned() {
        return roundPlanned;
    }

    /** 最近一轮真的写进接收器缓存的量（跨 tick 续投累加）。 */
    public long getRoundDelivered() {
        return roundDelivered;
    }

    /** 最近一轮结束时「投不出去」的量（续投中恒为 0）。 */
    public long getRoundShortfall() {
        return roundShortfall;
    }

    /**
     * 最近一轮还剩多少没投：{@code 计划 − 已投 − 缺口}。
     * <p>三种情形自动一致：成功投完 / 结束后计入缺口 ⇒ 0；续投中 ⇒ 还差的量。
     * 用 {@code Math.max(0, ...)} 夹住：同一 tick 被多个脉冲触发时 {@code pendingPlan} 会合并，
     * 计划数按新订单重置而上一轮余量还在，理论上可能出现负数 —— 那只是显示上的保守值，不影响搬运。</p>
     */
    public long getRoundRemaining() {
        return Math.max(0L, roundPlanned - roundDelivered - roundShortfall);
    }

    /** 最近一轮是否仍在续投（界面据此把状态行写成「进行中」）。 */
    public boolean isRoundActive() {
        return roundActive;
    }

    /** 开始新的一轮账（只记数字，不碰 pendingPlan / 订单 / 任何资源）。 */
    private void beginRound() {
        roundPlanned = 0L;
        for (final ResourceAmount entry : orders) {
            roundPlanned = saturatedAdd(roundPlanned, entry.amount());
        }
        roundDelivered = 0L;
        roundShortfall = 0L;
        roundActive = !orders.isEmpty();
        setChanged();
    }

    /** 把本次批次真正投出的量记进本轮账（在 {@link #deliverBatch} 返回后调用）。 */
    private void recordRoundDelivery(final long moved) {
        if (moved <= 0L) {
            return;
        }
        roundDelivered = saturatedAdd(roundDelivered, moved);
        setChanged();
    }

    /**
     * 本轮结束：出现缺口 ⇒ 把「计划 − 已投」记成缺口；正常投完 ⇒ 缺口保持 0。
     * <p>只改账目，不碰 {@code pendingPlan}（它已经被投完或由调用方决定作废）。</p>
     */
    private void finishRound(final boolean shortfall) {
        if (!roundActive) {
            return;
        }
        roundShortfall = shortfall ? Math.max(0L, roundPlanned - roundDelivered) : 0L;
        roundActive = false;
        setChanged();
    }

    /** 饱和加法（计划 / 已投都是 long，订单合并处已经用同一口径防溢出）。 */
    private static long saturatedAdd(final long left, final long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    // ==================== 订单清单（落盘 + 投递；编辑走网格界面 / RequesterOrderPacket） ====================

    /**
     * 订单清单的只读快照（顺序 = 投递顺序）。
     * <p>返回的是<b>副本列表</b>但元素不可变（{@code ResourceAmount} 是 record）：调用方改不到内部状态。</p>
     */
    public List<ResourceAmount> getOrders() {
        return List.copyOf(orders);
    }

    /**
     * 整体替换订单清单（唯一的写入口；后续界面的「应用配置」也走这里）。
     *
     * <p>清洗规则（全部是「拒绝坏数据」而不是「猜一个」）：</p>
     * <ol>
     *     <li>跳过 {@code null} / 数量 ≤ 0 / 资源不是 {@link PlatformResourceKey} 的条目；</li>
     *     <li>同一资源出现多条 ⇒ <b>合并数量</b>（避免「看起来两条、实际要投两遍」）。</li>
     * </ol>
     *
     * <p><b>没有条数上限</b>：玩家要在网格里一次选多少种资源就选多少种 ——
     * 真实网络里的不同资源种类本身就是有界的（= 网络存储里出现过的资源种类数），
     * 因此这里不再人为封顶，也<b>绝不</b>静默丢弃玩家已经选中的条目。</p>
     */
    public void setOrders(@Nullable final List<ResourceAmount> entries) {
        final Map<PlatformResourceKey, Long> merged = new LinkedHashMap<>();
        int dropped = 0;
        if (entries != null) {
            for (final ResourceAmount entry : entries) {
                if (entry == null || entry.amount() <= 0L
                    || !(entry.resource() instanceof PlatformResourceKey key)) {
                    dropped++;
                    continue;
                }
                merged.merge(key, entry.amount(),
                    (left, right) -> left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right);
            }
        }
        orders.clear();
        for (final Map.Entry<PlatformResourceKey, Long> entry : merged.entrySet()) {
            orders.add(new ResourceAmount(entry.getKey(), entry.getValue()));
        }
        if (dropped > 0) {
            // 拒绝绝不静默：日志 + 附近玩家可见提示（资源键无法落盘 / 数量非正，收下来只会变成丢数据）
            LOGGER.warn("[rs_create_compat] 网络资源请求器@{} 有 {} 条无效条目被拒绝"
                + "（资源键无法落盘 / 数量非正）", worldPosition, dropped);
            hint("order_rejected", dropped);
        }
        setChanged();
    }

    /** 清空订单清单（保留目标名字）。 */
    public void clearOrders() {
        if (orders.isEmpty()) {
            return;
        }
        orders.clear();
        setChanged();
    }

    // ==================== 红石上升沿（两种触发之一） ====================

    /**
     * 红石电平变化：<b>只在上升沿触发一次</b>。
     *
     * <p>逐字照搬 Create 红石请求器的边沿判定（{@code RedstoneRequesterBlockEntity.java:77-88}）：
     * 同电平直接返回（因此持续通电不会每 tick 重复投递），跳变时先把
     * {@code lastRequestSucceeded} 清为 false（「这一轮的结果还不知道」），
     * 仅在<b>上升沿</b>（有信号）时真正触发。</p>
     *
     * @param hasNeighborSignal 这一格当前有没有红石信号（由方块在服务端现查后传入）
     */
    public void onRedstonePowerChanged(final boolean hasNeighborSignal) {
        if (redstonePowered == hasNeighborSignal) {
            return; // 边沿判定：电平没变 ⇒ 什么都不做
        }
        lastRequestSucceeded = false;
        if (hasNeighborSignal) {
            triggerRequest();
        }
        redstonePowered = hasNeighborSignal;
        setChanged();
    }

    /**
     * 触发一轮投递 —— <b>红石上升沿与界面手动按钮共用的唯一入口</b>（用户要求「两种触发，同一个方块」，
     * 这里就体现在「两种触发都只调这一个方法」）。
     *
     * <p>本方法现在真的会取料 / 写入接收器缓存，数据流见类 javadoc。四道前置闸门<b>顺序刻意固定</b>：</p>
     * <ol>
     *     <li><b>F0 配置判定</b>在最前 —— 「配置是否完整」必须先于一切带副作用的动作（推进闸门 / 解析目标 /
     *     改状态）。这条顺序让「先按几次空脉冲、再填目标名」的第一次请求一定能被看见
     *     （而不是先被闸门或目标解析吞掉，变成最难查的「有时候第一次不生效」）；</li>
     *     <li><b>F1 节流闸门</b> {@link #throttleTick()}：走升级容器 → {@link RsccRateLimiter}，
     *     批数由速度升级给出、单批量由叠加升级给出；</li>
     *     <li><b>F2 目标解析</b>：0 个 = 未找到（拒绝 + 提示）；≥2 个 = 重名（拒绝 + 提示，<b>绝不随机挑一台</b>）；
     *     唯一命中才继续；</li>
     *     <li><b>F3 订单非空</b>：空清单没有可投的东西，同样明确提示（不静默）。</li>
     * </ol>
     *
     * <p><b>「目标区块未加载」不是失败</b>：解析不到、但上一次命中的坐标所在区块确实未加载 ⇒
     * 本轮<b>静默跳过</b>（配置一个字都不动，下轮再试）。只有「区块已加载却仍然解析不到」才判「找不到」，
     * 避免玩家一走进未加载区就看到满屏红字。</p>
     *
     * <p><b>一次触发投多少：整张订单</b>（分批循环，见 {@link #deliverBatch}）。
     * 红石上升沿与界面手动按钮<b>语义完全相同</b> —— 订单数量就是目标量，没有理由让「脉冲」比
     * 「按钮」少投；单批量只决定这一轮分几批投完。<b>总量恒等于订单量</b>（不存在「一次只投
     * 16 / 64 件」的上限），装的速度升级越少、跨的 tick 越多；本 tick 批数用完的剩余由
     * {@link #continuePendingDelivery()} 下一 tick 接着投，绝不丢掉、也绝不重复投。</p>
     */
    public void triggerRequest() {
        triggerRequest(null, null);
    }

    /** 只投「这些」（F 组：夹过库存的现有部分），不再询问缺口（询问已经发生过了）。 */
    public void triggerRequest(@Nullable final List<ResourceAmount> plan) {
        triggerRequest(null, plan);
    }

    /**
     * 投递的<b>唯一实现</b>。
     *
     * @param asker 触发者（界面按钮 = 点按钮的玩家；红石 = {@code null} ⇒ 现场找最近的玩家来问缺口）
     * @param plan  {@code null} = 按整张订单投；非 null = 只投这份计划（F 组「先投现有部分」）
     */
    public void triggerRequest(@Nullable final ServerPlayer asker,
                               @Nullable final List<ResourceAmount> plan) {
        if (level == null || level.isClientSide()) {
            return; // 只有服务端权威世界才投递（客户端没有网络 / 没有可写的方块实体）
        }
        if (!isProperlyConfigured()) {
            setRequestSucceeded(false);
            hint("unconfigured");
            return; // F0：未配置（目标名为空）⇒ 不动作
        }
        if (!throttleTick()) {
            return; // F1：节流闸门未放行
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            setRequestSucceeded(false);
            hint("no_network");
            return;
        }
        // F1b：有网络但没电 / 节点未激活 ⇒ 不投递（与接收器 doWork 的 isActive 同一判据）。
        // 必须先判「有没有网络」再判「活没活」：否则未接网络会被误报成「没供电」。
        // 不修改任何配置：名字、订单、缓冲全部原样留着，重新上电后再触发即可。
        if (!mainNetworkNode.isActive()) {
            setRequestSucceeded(false);
            hint("no_power");
            return;
        }
        // F2：0 / 1 / ≥2 三种结果，这里逐条处理 —— 重名绝不随机挑一台
        final List<NetworkResourceReceiverBlockEntity> hits = resolveReceiversByName(targetName);
        if (hits.size() > 1) {
            setRequestSucceeded(false);
            hint("ambiguous", hits.size(), targetName);
            return;
        }
        if (hits.isEmpty()) {
            if (isRememberedTargetChunkUnloaded()) {
                // 目标区块未加载 ⇒ 本轮跳过。配置（名字 + 订单 + 缓冲）一个字都不动，下轮再试。
                LOGGER.debug("[rs_create_compat] 请求器@{} 的目标「{}」所在区块未加载，本轮跳过",
                    worldPosition, targetName);
                return;
            }
            setRequestSucceeded(false);
            hint("not_found", targetName);
            return;
        }
        final NetworkResourceReceiverBlockEntity receiver = hits.get(0);
        rememberResolvedTarget(receiver);
        if (orders.isEmpty()) {
            setRequestSucceeded(false);
            hint("no_orders");
            return; // F3：没有订单就没有可投的东西
        }
        // F 组（第 131 轮）：先算缺口 —— 有缺口且该资源可自动合成 ⇒ **先不发**，向玩家询问。
        // 只在「按整张订单投」时问（plan != null 的那一次正是询问之后的「投现有部分」，再问就会死循环）。
        if (plan == null) {
            final ServerPlayer recipient = asker != null ? asker : nearestPlayerForShortage();
            if (recipient != null && SenderShortage.ask(recipient,
                new RequesterSenderBackend(this))) {
                return; // 已经发出询问：本轮一件都不投（用户要求「先不发」）
            }
        }
        // 「这一次触发要投的量」= 订单清单的数量（每次触发都投这么多，绝不递减清单）
        enqueuePendingPlan(plan);
        // 账目：本轮计划 = 当前订单总量。必须在 deliverBatch 之前开始，投出的量才算得进这一轮
        beginRound();
        final long moved = deliverBatch(network, receiver);
        recordRoundDelivery(moved);
        if (shortfallThisDelivery || pendingPlan.isEmpty()) {
            // 投完了（或出现缺口、本轮就地结束）⇒ 结账；还在续投则保持「进行中」
            finishRound(shortfallThisDelivery);
        }
        // 「成功」的判据：这一轮投出过资源，<b>或者</b>请求已经排进待投计划等着下一 tick 续投
        // （同一 tick 内预算可能已被前一次投递用掉），并且<b>没有出现缺口</b>。
        // 网络不够 / 接收器吃不下 / 只投出一部分都算缺口 —— 已经 hint 过具体数量，
        // 所以「订单 80 + 网络 50」绝不会被报成成功投了 80。
        setRequestSucceeded((moved > 0L || !pendingPlan.isEmpty()) && !shortfallThisDelivery);
    }

    /**
     * 每 tick 的兜底：① 续投上一 tick 没用完预算的那部分请求；② 归还 {@link #returnBuffer}。
     *
     * <p><b>两件事都不做时直接返回</b>：缓冲为空且没有未投完的计划 ⇒ 一个网络查询都不做
     * （本方法的每 tick 成本 = 两次 {@code isEmpty()}）。</p>
     *
     * <p><b>为什么续投不会绕过订单语义</b>：续投用的是 {@link #pendingPlan} 里「本次请求还差多少」，
     * 不是订单清单，所以既不会每 tick 重投一遍订单，也不会因为续投而多投一件（见 {@link #deliverBatch}）。
     * <b>缓冲里装的资源相反，永远只回网络</b>（见下面）：那一批在语义上已经失败，
     * 再投给接收器等于绕过订单 / 节流语义，守恒账也会变成「取一次、写两次」。</p>
     */
    @Override
    public void doWork() {
        super.doWork();
        if (level == null || level.isClientSide()) {
            return;
        }
        // 没电 / 离网 ⇒ 不续投、也不归还缓冲（与接收器同一个 isActive 判据）。
        // 关键语义：这里只是「先不做」，pendingPlan 与 returnBuffer 都原样留着 ——
        // 重新上电后继续投 / 继续归还，一件资源都不会丢。
        if (!mainNetworkNode.isActive()) {
            return;
        }
        // 第 131 轮 F 组：合成后的补投。凑齐即投、超时（10 分钟）放弃并提示，绝不无限挂起。
        // 放在「缓冲 / 续投」之前：补投是用户刚确认过的动作，优先级高于后台归还。
        if (!pendingSends.isEmpty()) {
            PendingSenderSends.tick(this, pendingSends);
        }
        if (returnBuffer.isEmpty() && pendingPlan.isEmpty()) {
            return; // 没有暂存资源、也没有未投完的请求 ⇒ 什么都不查
        }
        if (!pendingPlan.isEmpty()) {
            continuePendingDelivery();
        }
        if (returnBuffer.isEmpty()) {
            return;
        }
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        for (int index = returnBuffer.size() - 1; index >= 0; index--) {
            final ResourceAmount held = returnBuffer.get(index);
            final long returned = storage.insert(held.resource(), held.amount(), Action.EXECUTE, Actor.EMPTY);
            if (returned <= 0L) {
                continue; // 网络仍然不收：原样留在缓冲里，下一 tick 再试（绝不销毁）
            }
            final long rest = held.amount() - returned;
            if (rest <= 0L) {
                returnBuffer.remove(index);
            } else {
                returnBuffer.set(index, new ResourceAmount(held.resource(), rest));
            }
            setChanged();
        }
        if (!returnBuffer.isEmpty()) {
            // 每 tick 重试会刷屏：这一类提示走限频（见 hint），且只报一次「仍无法归还」
            hint("buffer_retry_failed", describeResource(returnBuffer.get(0).resource()));
        }
    }

    /**
     * 跨 tick 续投：把 {@link #pendingPlan} 剩下的量接着投完（只在「本 tick 批数用完」时才会出现）。
     *
     * <p><b>不重复投的保证</b>：每轮都从 {@link #pendingPlan} 里读「当前还差多少」，
     * 而它只按「真的写进接收器的量」递减 —— 上一 tick 已投出去的量早已从计划里扣掉，
     * 因此不可能再投一次；每批的请求量又是 {@code min(还差多少, 单批量)}，因此也不可能过量。</p>
     *
     * <p><b>为什么不扫网络</b>：接收器来自 {@link #lastResolvedTargetPos}（触发时刚记住的坐标），
     * 一次 {@code Level#getBlockEntity} 就取回来，<b>不</b>重新枚举 {@code GraphNetworkComponent} ——
     * 每 tick 全量枚举与「目标还在不在」无关，没有理由付这笔钱。</p>
     */
    private void continuePendingDelivery() {
        final Network network = getNode().getNetworkOrNull();
        if (network == null) {
            return;
        }
        final NetworkResourceReceiverBlockEntity receiver = pendingReceiver(network);
        if (receiver == null) {
            return;
        }
        final long moved = deliverBatch(network, receiver);
        recordRoundDelivery(moved);
        if (pendingPlan.isEmpty() || shortfallThisDelivery) {
            // 只有「投完了」或「出现缺口」才更新结果；进行中的续投不改上一轮的结论
            finishRound(shortfallThisDelivery);
            setRequestSucceeded(moved > 0L && !shortfallThisDelivery);
        }
    }

    /**
     * 续投用的接收器：从 {@link #lastResolvedTargetPos} 直接取回（<b>不做名字枚举</b>）。
     *
     * <p>三种情形分得很清楚：坐标还没记住 ⇒ 计划作废；区块未加载 ⇒ 本轮<b>静默暂停</b>
     * （计划不丢、一个网络查询都不做，等区块回来继续）；方块不在了 / 变成未配置 / 断网 ⇒
     * 计划作废并明确提示（计划不是资源，作废它不会丢任何一件东西；下一次触发会重新解析目标）。</p>
     */
    @Nullable
    private NetworkResourceReceiverBlockEntity pendingReceiver(final Network network) {
        if (lastResolvedTargetPos == null || level == null) {
            dropPendingPlan("pending_lost");
            return null;
        }
        if (!level.isLoaded(lastResolvedTargetPos)) {
            return null;
        }
        if (!(level.getBlockEntity(lastResolvedTargetPos) instanceof NetworkResourceReceiverBlockEntity receiver)
            || !receiver.isProperlyConfigured()
            || receiver.getNode().getNetworkOrNull() == null) {
            dropPendingPlan("pending_lost");
            return null;
        }
        return receiver;
    }

    /** 作废待投计划：只影响「还打算投多少」，不碰任何已经取出的资源（守恒与它无关）。 */
    private void dropPendingPlan(final String reason) {
        if (pendingPlan.isEmpty()) {
            return;
        }
        pendingPlan.clear();
        // 计划作废 = 本轮结束，剩下的量记进缺口（界面从此不再显示「剩余」，而是明确「缺口」）
        finishRound(true);
        setRequestSucceeded(false);
        hint(reason, targetName);
    }

    /** 记录 / 清空「上次请求是否成功」；只有真的变化才标脏。 */
    private void setRequestSucceeded(final boolean succeeded) {
        if (lastRequestSucceeded == succeeded) {
            return;
        }
        lastRequestSucceeded = succeeded;
        setChanged();
    }

    // ==================== 投递：取料 → 写接收器缓存 → 回滚（守恒） ====================

    /**
     * 逐条投递 {@link #pendingPlan}（本次请求还差多少），返回<b>真正落进接收器缓存的总件数</b>。
     *
     * <p>第 131 轮把「四步走 + 守恒账 + 回滚 + 暂存」整段搬进
     * {@code support/SenderDelivery#deliver}（终端发送模式复用同一份实现），
     * 本方法只剩三件方块实体自己的事：① 按 gameTime 记账本 tick 批数预算；
     * ② 把「本 tick 批数用完」的剩余写回 {@link #pendingPlan}（下一 tick 续投）；
     * ③ 提示走 {@link #hint}（限频）、回滚失败进 {@link #returnBuffer}。</p>
     *
     * <p><b>节拍固定</b>：每 tick 批数 / 单批量是常量（用户拍板「红石激活多快就发多快」），
     * 不再有速度 / 叠加升级参与。</p>
     */
    private long deliverBatch(final Network network, final NetworkResourceReceiverBlockEntity receiver) {
        // 每 tick 批数 / 单批量都是固定常量（用户第 131 轮拍板：红石脉冲多快就发多快）。
        // 预算按 gameTime 记账：同一个 tick 内无论触发 / 续投被调用几次，合计都只花这些批。
        final int perTickBatches =
            Math.min(cretae.cookiewyq.rs_create_compat.support.SenderDelivery.MAX_BATCHES_PER_TICK,
                getThrottleBatchesPerTick());
        final long now = level == null ? 0L : level.getGameTime();
        if (now != deliveryBudgetTick) {
            deliveryBudgetTick = now;
            deliveryBudgetUsed = 0;
        }
        // 投递引擎只有一份（support/SenderDelivery）：终端发送模式复用同一段代码，
        // 因此「取料 → 夹容量 → 写入 → 回滚 → 暂存」的守恒语义不可能在两处漂移。
        final cretae.cookiewyq.rs_create_compat.support.SenderDelivery.Result result =
            cretae.cookiewyq.rs_create_compat.support.SenderDelivery.deliver(network,
                new cretae.cookiewyq.rs_create_compat.support.SenderDelivery.ReceiverSink() {
                    @Override
                    public long apply(final ResourceKey resource, final long amount,
                                      final boolean simulate) {
                        return applyToReceiverCache(receiver, resource, amount, simulate);
                    }

                    @Override
                    public boolean canHandle(final ResourceKey resource) {
                        return receiver.canHandle(resource);
                    }

                    @Override
                    public String displayName() {
                        return receiver.getReceiverDisplayName();
                    }
                },
                List.copyOf(pendingPlan), perTickBatches, getThrottleBatch(),
                deliveryBudgetUsed,
                new cretae.cookiewyq.rs_create_compat.support.SenderDelivery.Feedback() {
                    @Override
                    public void hint(final String reason, final Object... args) {
                        NetworkResourceRequesterBlockEntity.this.hint(reason, args);
                    }

                    @Override
                    public void buffer(final ResourceKey resource, final long amount) {
                        bufferReturn(resource, amount);
                    }
                });
        // 计划量只按「真的写进接收器」的量递减由引擎保证；这里只把剩余接下 tick 的部分写回计划
        pendingPlan.clear();
        pendingPlan.addAll(result.carry());
        deliveryBudgetUsed = result.batchesUsed();
        shortfallThisDelivery = result.shortfall();
        return result.moved();
    }

    /**
     * 把「这一次触发要投的量」并入 {@link #pendingPlan}：<b>订单语义 = 每次触发都投这么多</b>
     * （不是「总共投这么多」，也绝不递减订单清单 —— 清单是配置，见类 javadoc）。
     *
     * <p>两次触发 = 两次请求，各自的数量都要投出去；同资源在计划里合并成一条（饱和相加），
     * 因此计划条目数只与「订单里有几种资源」有关。</p>
     */
    private void enqueuePendingPlan(@Nullable final List<ResourceAmount> explicitPlan) {
        if (explicitPlan != null) {
            // F 组：只投「夹过库存的那一份」（用户选「合成并发送」/「只发送现有」时的前一半）
            for (final ResourceAmount entry : explicitPlan) {
                if (entry == null || entry.amount() <= 0L
                    || !(entry.resource() instanceof PlatformResourceKey)) {
                    continue;
                }
                mergeIntoPendingPlan(entry.resource(), entry.amount());
            }
            return;
        }
        for (final ResourceAmount order : List.copyOf(orders)) {
            final ResourceKey resource = order.resource();
            final long amount = order.amount();
            if (!(resource instanceof PlatformResourceKey) || amount <= 0L) {
                continue; // 坏条目在 setOrders 已经过滤，这里只是防线
            }
            mergeIntoPendingPlan(resource, amount);
        }
    }

    /** 把 {@code amount} 并入计划里的同资源条目（没有就新开一条），用饱和加法防溢出。 */
    private void mergeIntoPendingPlan(final ResourceKey resource, final long amount) {
        if (amount <= 0L) {
            return;
        }
        for (int index = 0; index < pendingPlan.size(); index++) {
            final ResourceAmount held = pendingPlan.get(index);
            if (!held.resource().equals(resource)) {
                continue;
            }
            final long sum = held.amount() > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE : held.amount() + amount;
            pendingPlan.set(index, new ResourceAmount(resource, sum));
            return;
        }
        pendingPlan.add(new ResourceAmount(resource, amount));
    }

    /**
     * 把 {@code amount} 个资源写进接收器，返回<b>实际受理量</b>；{@code simulate} 时只模拟。
     *
     * <p><b>本方法刻意不认识物品 / 流体</b>：具体往哪儿写（物品缓存 / 流体缓存 / 直通朝向容器）
     * 是接收器自己的事，见
     * {@link NetworkResourceReceiverBlockEntity#applyResource(ResourceKey, long, boolean)}。
     * 请求器只负责「取多少、写多少、余量回滚」，因此任何 {@link ResourceKey}
     * （包括装了官方 Mekanism 联动后才存在的气体）都自动获得同一套守恒语义。</p>
     *
     * <p>返回值完全由接收器回答（能收多少就返回多少），这里<b>不假设</b>任何容量。</p>
     */
    private long applyToReceiverCache(final NetworkResourceReceiverBlockEntity receiver,
                                      final ResourceKey resource,
                                      final long amount,
                                      final boolean simulate) {
        if (receiver == null || amount <= 0L) {
            return 0L;
        }
        return receiver.applyResource(resource, amount, simulate);
    }

    /**
     * 拆方块结算用：<b>取出并清空</b> {@link #returnBuffer} 里的物品条目。
     *
     * <p>为什么必须有它：{@code ReturnBuffer} 装的是<b>真实资源</b>（已经从网络取出、但回滚失败的那部分）。
     * 方块被拆时必须交给 {@code BlockContentReleaser} 按全局「内容物去向」策略结算，
     * 否则这三档策略（回网 / 爆出 / 存方块）对它就形同虚设，资源会凭空消失。</p>
     *
     * <p>只取物品条目，流体条目留给 {@link #releaseReturnBufferFluids()}：{@code BlockContentReleaser}
     * 对物品与流体分别按不同策略结算（流体永远不爆出）。数量超过 {@code int} 时按块拆分，
     * 绝不因为 {@code ItemStack} 的 int 上限而截断丢失。</p>
     */
    public List<ItemStack> releaseReturnBufferItems() {
        final List<ItemStack> out = new ArrayList<>();
        for (int index = returnBuffer.size() - 1; index >= 0; index--) {
            final ResourceAmount held = returnBuffer.get(index);
            if (!(held.resource() instanceof ItemResource item)) {
                continue;
            }
            long rest = held.amount();
            while (rest > 0L) {
                final int chunk = (int) Math.min(rest, Integer.MAX_VALUE);
                out.add(item.toItemStack(chunk));
                rest -= chunk;
            }
            returnBuffer.remove(index);
        }
        if (!out.isEmpty()) {
            setChanged();
        }
        return out;
    }

    /** 拆方块结算用：取出并清空 {@link #returnBuffer} 里的流体条目（数量超过 int 时按块拆分）。 */
    public List<FluidStack> releaseReturnBufferFluids() {
        final List<FluidStack> out = new ArrayList<>();
        for (int index = returnBuffer.size() - 1; index >= 0; index--) {
            final ResourceAmount held = returnBuffer.get(index);
            if (!(held.resource() instanceof FluidResource fluid)) {
                continue;
            }
            long rest = held.amount();
            while (rest > 0L) {
                final int chunk = (int) Math.min(rest, Integer.MAX_VALUE);
                out.add(new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid.fluid()), chunk,
                    fluid.components()));
                rest -= chunk;
            }
            returnBuffer.remove(index);
        }
        if (!out.isEmpty()) {
            setChanged();
        }
        return out;
    }

    /** 往缓冲里合并一件资源（同资源合并，绝不重复开条目）。 */
    private void bufferReturn(final ResourceKey resource, final long amount) {
        if (amount <= 0L || !(resource instanceof PlatformResourceKey key)) {
            return;
        }
        for (int index = 0; index < returnBuffer.size(); index++) {
            final ResourceAmount held = returnBuffer.get(index);
            if (!held.resource().equals(key)) {
                continue;
            }
            final long sum = held.amount() > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE : held.amount() + amount;
            returnBuffer.set(index, new ResourceAmount(key, sum));
            setChanged();
            return;
        }
        returnBuffer.add(new ResourceAmount(key, amount));
        setChanged();
    }

    /**
     * support 层（{@link PendingSenderSends} / {@link SenderShortage}）往缓冲里交一件回滚失败资源。
     * <p>为什么开放这个方法：投递引擎是共享的，补投路径也可能出现「取出来又放不回去」，
     * 那部分资源必须进同一个 {@link #returnBuffer}（下一 tick 归还网络），<b>绝不能另开一个容器</b>。</p>
     */
    public void bufferReturned(final ResourceKey resource, final long amount) {
        bufferReturn(resource, amount);
    }

    /** support 层登记「合成后补投」待办（见 {@link #pendingSends}）。 */
    public void addPendingSends(final List<PendingSenderSends.PendingSend> sends) {
        if (sends == null || sends.isEmpty()) {
            return;
        }
        pendingSends.addAll(sends);
    }

    /** 场上还挂着的补投待办（只读，自检 / 调试用）。 */
    public List<PendingSenderSends.PendingSend> pendingSendsView() {
        return List.copyOf(pendingSends);
    }

    /**
     * 红石触发时找一个「可以问缺口」的玩家：取 64 格内最近的一名。
     * <p>没有玩家在附近 ⇒ 返回 {@code null}，本轮按旧路径发送（投现有 + 缺口提示），
     * 绝不因为「问不出口」而什么都不做。</p>
     */
    @Nullable
    private ServerPlayer nearestPlayerForShortage() {
        if (level == null || level.getServer() == null) {
            return null;
        }
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Player player : level.players()) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                continue;
            }
            final double distance = player.distanceToSqr(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5);
            if (distance <= 4096.0 && distance < bestDistance) {
                best = serverPlayer;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 记住解析成功的接收器坐标（只用于区分「未加载」与「找不到」）。 */
    private void rememberResolvedTarget(final NetworkResourceReceiverBlockEntity receiver) {
        final BlockPos pos = receiver.getBlockPos();
        if (pos.equals(lastResolvedTargetPos)) {
            return;
        }
        lastResolvedTargetPos = pos;
        setChanged();
    }

    /** 上一次命中的目标坐标所在区块是否未加载（没有历史坐标 ⇒ false，按「找不到」处理）。 */
    private boolean isRememberedTargetChunkUnloaded() {
        return lastResolvedTargetPos != null && level != null && !level.isLoaded(lastResolvedTargetPos);
    }

    // ==================== 失败提示（每条失败都必须有可见提示） ====================

    /**
     * 给附近玩家（64 格内）发一条失败提示，并写一条 DEBUG 日志。
     *
     * <p><b>为什么是「附近玩家」而不是全服</b>：请求器是方块，故障只与这一格的配置 / 网络有关，
     * 与 {@code SchematicLoaderBlockEntity} 的完成提示同一口径（{@code distanceToSqr(...) <= 4096}）。</p>
     * <p><b>限频</b>：同一条 reason 最多每 {@link #HINT_COOLDOWN_TICKS} tick 提示一次（首次立即提示）——
     * 红石钟每 2 tick 触发一次时，没有这一道会把聊天框刷爆；限频只影响「重复提示」，
     * 不影响任何投递 / 回滚语义。</p>
     */
    public void hint(final String reason, final Object... args) {
        if (level == null || level.isClientSide()) {
            return;
        }
        final long now = level.getGameTime();
        final Long last = lastHintTicks.get(reason);
        if (last != null && now - last < HINT_COOLDOWN_TICKS) {
            return;
        }
        lastHintTicks.put(reason, now);
        final Component message = Component.translatableWithFallback(
            "gui.rs_create_compat.network_resource_requester.fail." + reason,
            hintFallback(reason), args).withStyle(ChatFormatting.RED);
        LOGGER.debug("[rs_create_compat] 请求器@{} 投递失败 reason={} args={}",
            worldPosition, reason, java.util.Arrays.toString(args));
        for (final Player player : level.players()) {
            if (player.distanceToSqr(worldPosition.getX() + 0.5,
                worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 4096.0) {
                player.displayClientMessage(message, false);
            }
        }
    }

    /**
     * 提示文案的中文兜底（语言键缺失时直接显示它）。
     * <p>语言文件里已经有这些 key 时自动让位给语言文件；这层兜底只保证「key 缺失也不会静默」。
     * 兜底文案与 {@code assets/rs_create_compat/lang/zh_cn.json} 的对应条目逐条同义。</p>
     */
    private static String hintFallback(final String reason) {
        return switch (reason) {
            case "unconfigured" -> "请求器还没有设置目标名字，未投递";
            case "no_network" -> "请求器没有接入 RS 网络，无法投递";
            case "no_power" -> "请求器没有供电（或网络未激活），本轮不投递；配置与缓冲原样保留";
            // 第三方容器抛异常：已按「未写入」回滚，绝不让资源消失
            case "deliver_exception" -> "写入 %s 时目标容器抛异常；已回滚到网络 / 缓冲，不会销毁资源";
            case "not_found" -> "未找到名为「%s」的接收器（可能被拆除、改名，或不在本网络）";
            case "ambiguous" -> "网络里有 %s 台接收器都叫「%s」，已拒绝投递（不会随机挑一台）";
            case "no_orders" -> "请求器里还没有选中任何资源，未投递";
            case "unsupported" -> "接收器没有能接收 %s 的通道（不是物品 / 流体，且它朝向的容器不接受这种资源）";
            case "no_stock" -> "RS 网络里没有 %s，本轮跳过这一条";
            case "receiver_full" -> "接收器「%s」的缓存已经放不下 %s，本轮跳过这一条";
            case "extract_failed" -> "从网络取 %s 失败（模拟放行、执行失败），本轮跳过这一条";
            case "partial" -> "接收器「%s」没吃下全部 %s（写入 %s、回滚 %s、取出 %s）";
            case "buffer_buffered" -> "网络拒收回滚的 %s ×%s，已暂存到请求器缓冲，下一 tick 重试";
            case "buffer_retry_failed" -> "请求器缓冲里的 %s 仍然无法归还网络（网络存储可能已满）";
            // 订单要 80 而网络只有 50：投 50、明确报「不够」，绝不报成「成功投了 80」
            case "short_stock" -> "RS 网络里的 %s 不够：本轮要 %s 件，只投出 %s 件（不会重复投）";
            // 订单里有无法落盘的资源键 / 数量非正：明确拒绝，绝不静默丢条目
            case "order_rejected" -> "有 %s 条已选资源无法保存（资源键不支持落盘或数量非正），已拒绝";
            // 跨 tick 续投的目标没了（拆除 / 改名成未配置 / 断网）：计划作废，绝不丢资源
            case "pending_lost" -> "投递目标「%s」已不可用（拆除 / 改名 / 断开网络），未投完的请求已作废";
            // 网络没有存储组件（理论上不会发生）：明确报出来，而不是静默「投了 0 件」
            case "no_storage" -> "RS 网络里没有可用的存储，无法取料";
            // F 组：合成后补投超时（10 分钟）
            case "shortage_gave_up" -> "补投超时（10 分钟）：接收器「%s」还差 %s，自动合成没有凑齐，已放弃本次补投";
            // 守恒自证失败：这是真正的 bug 信号，必须让玩家 / 日志看见，绝不静默
            case "conservation_broken" -> "投递守恒校验失败（取出 %s / 写入 %s / 回滚 %s / 暂存 %s），请把日志发给作者";
            default -> "请求器投递失败：" + reason;
        };
    }

    /** 资源显示名（委托共享实现：物品 / 流体各取自己的 hover name，其它类型退回 toString）。 */
    private static Component describeResource(final ResourceKey resource) {
        return cretae.cookiewyq.rs_create_compat.support.SenderDelivery.describeResource(resource);
    }

    // ==================== 目标解析（现解析，不做注册表快照） ====================

    /**
     * 在自己所在网络内按名字解析接收器。
     *
     * <p><b>为什么每次现解析、不做快照</b>（设计文档 §4.4 的核心决议）：
     * 名字是玩家随时可改的，快照就必须有失效通知机制，而任何「需要同步的缓存副本」都是
     * 一个可能说谎的状态源。现解析的代价是 O(网络节点数)，而调用时机只有「一轮请求」，
     * 完全可以接受。副作用是<b>目标改名 / 拆除自动跟随</b>，不需要任何通知。</p>
     *
     * <p><b>区块未加载 / 未接入网络</b>：{@code GraphNetworkComponent#getContainers()} 只含已加载的节点，
     * 因此这两类情形自然表现为「找不到」，而不是报错（与 {@code RsccSharedCache} 的
     * 「晚一点看见」同一哲学）。</p>
     *
     * @param name 目标名字（空名 ⇒ 直接返回空列表，因为空名的接收器根本不是合法的投递目标）
     * @return 命中的接收器（0 个 = 未找到；1 个 = 命中；≥2 个 = 重名冲突，调用方必须拒绝而不是挑一个）
     */
    public List<NetworkResourceReceiverBlockEntity> resolveReceiversByName(@Nullable final String name) {
        // 枚举口径只有一处（SenderDelivery）：终端发送模式 / 目标选择子界面用的都是它
        return cretae.cookiewyq.rs_create_compat.support.SenderDelivery
            .resolveReceiversByName(getNode() == null ? null : getNode().getNetworkOrNull(), name);
    }

    // ==================== 持久化 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_TARGET_NAME, targetName == null ? "" : targetName);
        tag.putBoolean(TAG_LAST_SUCCESS, lastRequestSucceeded);
        // 红石电平落盘：重进存档后第一次 neighborChanged 不会因为「电平与上次相同」而不触发
        // —— 重载后玩家再给一次脉冲必须能工作，这是「脉冲触发」这个语义的基本要求。
        tag.putBoolean(TAG_POWERED, redstonePowered);
        // 订单清单：走 RS 自己的 `{resource, amount}` codec（物品 / 流体同一份实现）
        ORDER_CODEC.encodeStart(ops(registries), orders).result()
            .ifPresent(encoded -> tag.put(TAG_ORDERS, encoded));
        // 回滚失败的暂存资源：必须落盘（拆方块 / 读档都不能让它凭空消失）
        ORDER_CODEC.encodeStart(ops(registries), returnBuffer).result()
            .ifPresent(encoded -> tag.put(TAG_RETURN_BUFFER, encoded));
        // 跨 tick 续投的待投计划：必须落盘（一次触发可能跨多 tick；读档后要继续投完）
        ORDER_CODEC.encodeStart(ops(registries), pendingPlan).result()
            .ifPresent(encoded -> tag.put(TAG_PENDING_PLAN, encoded));
        // 上次命中的目标坐标：只用于区分「目标区块未加载」与「目标真的没了」
        if (lastResolvedTargetPos != null) {
            tag.putLong(TAG_LAST_TARGET, lastResolvedTargetPos.asLong());
        }
        // 最近一轮投递的账：落盘后界面仍能显示上一轮的真实结果（这些数不参与投递，读档不续算）
        tag.putLong(TAG_ROUND_PLANNED, roundPlanned);
        tag.putLong(TAG_ROUND_DELIVERED, roundDelivered);
        tag.putLong(TAG_ROUND_SHORTFALL, roundShortfall);
        tag.putBoolean(TAG_ROUND_ACTIVE, roundActive);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetName = tag.getString(TAG_TARGET_NAME);
        lastRequestSucceeded = tag.getBoolean(TAG_LAST_SUCCESS);
        redstonePowered = tag.getBoolean(TAG_POWERED);
        lastResolvedTargetPos = tag.contains(TAG_LAST_TARGET)
            ? BlockPos.of(tag.getLong(TAG_LAST_TARGET)) : null;
        // 第 131 轮：请求器不再吃升级，旧存档里的 Upgrades 键**原样忽略**（不读、不写、不迁移）——
        // 读回也不会用，写了只会让玩家以为它还在生效。
        // 待投计划：先清空（方块实体可能被复用），再按 codec 读回；坏数据 ⇒ 空计划（不丢任何资源）
        pendingPlan.clear();
        if (tag.contains(TAG_PENDING_PLAN)) {
            ORDER_CODEC.parse(ops(registries), tag.get(TAG_PENDING_PLAN)).result()
                .ifPresent(plan -> {
                    for (final ResourceAmount entry : plan) {
                        if (entry != null && entry.amount() > 0L) {
                            pendingPlan.add(entry);
                        }
                    }
                });
        }
        // 清单解不开 ⇒ 保持空清单（绝不抛异常、绝不静默改成别的资源）；
        // 解出来之后统一走 setOrders 的清洗（合并重复 / 夹上限 / 丢弃非正数）
        if (tag.contains(TAG_ORDERS)) {
            ORDER_CODEC.parse(ops(registries), tag.get(TAG_ORDERS)).result()
                .ifPresentOrElse(this::setOrders, () -> LOGGER.warn(
                    "[rs_create_compat] 请求器@{} 的订单清单解析失败，已按空清单处理", worldPosition));
        }
        returnBuffer.clear();
        if (tag.contains(TAG_RETURN_BUFFER)) {
            ORDER_CODEC.parse(ops(registries), tag.get(TAG_RETURN_BUFFER)).result()
                .ifPresent(buffer -> {
                    for (final ResourceAmount entry : buffer) {
                        if (entry != null && entry.amount() > 0L) {
                            bufferReturn(entry.resource(), entry.amount());
                        }
                    }
                });
        }
        // 最近一轮投递的账：只用于界面显示；旧存档没有这些键 ⇒ 0 / 未进行中（不猜历史值）
        roundPlanned = tag.getLong(TAG_ROUND_PLANNED);
        roundDelivered = tag.getLong(TAG_ROUND_DELIVERED);
        roundShortfall = tag.getLong(TAG_ROUND_SHORTFALL);
        roundActive = tag.getBoolean(TAG_ROUND_ACTIVE) && roundPlanned > 0L;
    }

    /** NBT 编解码用的 ops：带注册表上下文（数据组件解析需要它；与 {@code MarkerEntry#ops} 同一做法）。 */
    private static com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops(
        final HolderLookup.Provider registries) {
        return registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    /** 区块加载完成：本机没有需要复核的槽位内容（升级槽已删除），只走基类。 */
    @Override
    public void onLoad() {
        super.onLoad();
    }

    /**
     * 网络节点容器能力：让请求器成为 RS 网络成员。
     * <p>它必须接入网络，否则既取不到料、也枚举不到同一网络里的接收器
     * （「同网络唯一」的作用域就是 {@code Network} 对象本身）。</p>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        // 回滚暂存区（returnBuffer）也是真实资源：用户硬规则要求「所有带缓存的方块都对外暴露
        // Inventory」。它只读、且只在无侧别查询（Jade）时暴露 —— 这个缓冲区的唯一出路是
        // 「下一 tick 自动归还网络」（doWork），任何外部写入 / 抽取都会破坏那套守恒重试语义。
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> direction == null
                ? ViewOnlyHandlers.itemOf(() -> List.copyOf(blockEntity.returnBuffer))
                : null
        );
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> direction == null
                ? ViewOnlyHandlers.fluidOf(() -> List.copyOf(blockEntity.returnBuffer))
                : null
        );
    }
}
