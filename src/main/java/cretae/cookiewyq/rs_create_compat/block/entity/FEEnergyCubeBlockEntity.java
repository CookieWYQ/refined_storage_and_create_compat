package cretae.cookiewyq.rs_create_compat.block.entity;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.FEEnergyCubeBlock;
import cretae.cookiewyq.rs_create_compat.network.FEEnergyCubeNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.EnergyCubeCluster;
import cretae.cookiewyq.rs_create_compat.support.EnergyCubeNetworkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * FE 能量立方（{@code rs_create_compat:fe_energy_cube}）：单块 5000 万 FE 的「备用电池」方块。
 *
 * <h2>两套数值口径（<b>故意不同</b>，改之前先读这段 —— 上一轮有人把两套并成一套又出了重复计数）</h2>
 * <ul>
 *     <li><b>RS 网络节点</b>（{@link FEEnergyCubeNetworkNode}）：{@code getStored / getCapacity} 只报
 *     <b>本块</b>。网络能量组件会把所有 provider 逐块<b>求和</b>，求和后才是集群总量 N×50M；
 *     节点再报集群值 = 每一块都被重复加 N 次（平方级虚增）；</li>
 *     <li><b>六面 FE 能力视图</b>（{@link #getExternalEnergyStorage()}）：报<b>集群</b>存量 / 容量 ——
 *     外部机器从任意一面抽电，看到的就是「整块电池」，这是「容量共享、从整体取电」的对外承诺。</li>
 * </ul>
 *
 * <h2>记账模型（用户口径：每块只按自身 stored、拆开各保留）</h2>
 * <p>每块自己的电量存在自己的 {@link EnergyStorage} 里，容量恒为 {@link Config#feEnergyCubeCapacity}；
 * 集群（{@link EnergyCubeCluster}）只是「N 块拼成一个更大的电池」的<b>视图</b>，不持有电量、不做移交。
 * 因此破坏一块 = 掉落的方块物品带走<b>它自己那一份</b>（见方块 {@code getDrops}），其余块一格不动 ——
 * 没有平均、没有复制、没有「拆开时谁多谁少」。
 *
 * <h2>充电门控（用户 2026-10-31 亲口澄清的「低优先级到底多低」口径）</h2>
 * <p>从 <b>RS 网络</b>抽电灌进立方，需要<b>同时</b>成立下面两条，缺一条就不抽：
 * <ol>
 *     <li><b>控制器（非立方能量池）电量 ≥ 阈值</b>：阈值由 {@link Config#feEnergyCubeChargeMode}
 *     （{@code percent} 默认 <b>90</b> / {@code absolute} 绝对 FE）二选一，实现在
 *     {@link #computeGate}；</li>
 *     <li><b>网络电量正在上涨</b>（{@link Trend#RISING}）：按 {@link #TREND_WINDOW_TICKS} tick
 *     采样窗口比较「非立方池 + 加回本立方自己的抽取」这个反事实读数，<b>严格变大</b>才算上涨
 *     （相等 = 持平，<b>不算</b>上涨）；采样与判定见 {@link #sampleChargeTrend}。</li>
 * </ol>
 * 任一条不满足 ⇒ <b>不从网络抽电</b>，但立方仍然可以放电（备用电池的本职工作）。
 * <p><b>第 106 轮起：外部 FE 给立方充电 / 从立方抽电完全不过门控</b>（见
 * {@link ClusterEnergyCapability#receiveEnergy} / {@link ClusterEnergyCapability#extractEnergy}）。
 * 上一版 RE：外部 FE 只拦「控制器低电」，那仍然把「玩家自己接的发电机给立方灌电」按网络状态拦掉 ——
 * 用户对 beta.9 的实机口径是「立方是传递的介质 / 缓存，可以被别人注入、也可以被抽取」，
 * 外部 FE 与 RS 网络宽不宽裕无关。门控<b>只</b>作用于「从 RS 网络能量池抽电进立方」这一条路径
 * （{@link #onNetworkTick}）。
 *
 * <h2>「上涨」为什么量的是<b>非立方池 + 加回本立方自己抽走的电</b></h2>
 * <p>趋势必须回答的是「<b>网络里发电是不是大于用电</b>」，所以直接量「非立方存量」（控制器口径）——
 * 它对「外部 FE 给立方充电」<b>完全免疫</b>（外部 FE 进的是本块的 storage，不进网络池）。<br>
 * 但裸量非立方存量会有一个自反馈：<b>本立方从网络抽电会让它下降</b>（RS 的 {@code extract} 从
 * provider 池里扣）⇒ 一开始充就「看起来没涨」⇒ 立刻停抽 ⇒ 网络再涨 ⇒ 再抽，自己把自己钉在
 * 「充-停-充-停」的振荡上。因此采样时把<b>本立方在上个窗口内抽走的量加回去</b>
 * （{@link #trendPulledSinceBaseline}）：这个「反事实读数」= 假设本立方没抽时非立方池会有多少，
 * 它的窗口变化就是「发电 − 其它用电」。
 * <p>对比另一种看起来更简单的口径 —— 量<b>网络能量池总量</b>（含立方）：它对「立方抽电」天然中性，
 * 但对「外部 FE 给立方充电」会<b>假阳性</b>（外部电源灌进立方 ⇒ 网络总量上升 ⇒ 被当成「网络在涨」
 * ⇒ 立方同时从 RS 网络抽电）。用户明确说过外部 FE 是玩家自己接的电源，这个假阳性会直接违背
 * 「外部 FE 不该被趋势闸影响」的初衷，所以不采用。
 * <p>已知残留（如实写清）：同一张网络上有<b>多组</b>立方簇时，别的簇抽电仍会压低本簇看到的趋势
 * （每块只加回自己那一份），表现为几组立方轮流充、平均充速变慢；因为趋势每 5 tick 才判一次、
 * 阈值还有 90% 兜底，这个影响是收敛的（不会永远充不进）。
 * <p><b>「控制器电量」到底怎么读（RS 没有公开 API，这是等效口径）</b>：{@code Network} 不暴露 node
 * container 列表，{@code EnergyNetworkComponent} 只给「所有 provider 的聚合值」，拿不到「只有控制器」。
 * 因此本类用：
 * <pre>非立方存量 = 网络能量池总量 − <b>本网络所有</b>能量立方节点的存量之和</pre>
 * 减的是<b>整张网络上的所有立方</b>（{@link EnergyCubeNetworkRegistry} 登记），不是自己这一簇 ——
 * 同一张网络可以挂多组互不相邻的立方簇，只减自己那组时其它立方会继续污染百分比，网络里立方越多、
 * 闸越关不上（极端情况下永远充不进电）。容量同理。
 * <p><b>这个等效口径的前提</b>（必须如实记住）：网络里除控制器与能量立方外<b>没有其它第三方
 * EnergyProvider</b>。若有别的模组能量方块也实现了 {@code EnergyProvider}，它的电量会被算进
 * 「控制器电量」里 —— 这份实现无从区分（RS API 没给身份信息）。
 * <p>percent 模式用 {@code double}（创造控制器的 stored/capacity 都是 {@code Long.MAX_VALUE}，
 * long 乘法会溢出成负数）；两者都各自夹到 ≥0（防极端并发下减成负）。
 * <p><b>没有网络时</b>：门外状态（{@link Gate#NO_NETWORK}），外部 FE 允许充放（单独立着就是一块普通电池）；
 * 一旦接上网络，下一次 {@code doWork} 就立刻回到门控。
 *
 * <h2>第 106 轮：立方 = <b>传递介质 / 缓存</b>（四条能量路径，缺一条就与用户口径不符）</h2>
 * <p>用户 beta.9 实机原话：「它应该是作为一个传递的介质，它是一个缓存；它也可以主动的向旁边的那些方块
 * 提供能源，也可以被别的吸取，也可以被别人注入……这一个能量立方它的优先级是最低的，它只是作为一个
 * 最后的缓存。」据此四条路径各自成立：
 * <ol>
 *     <li><b>主动供能</b>：每 tick 向六个相邻面的 {@code Capabilities.EnergyStorage.BLOCK} 推送 FE，
 *     每 tick 总量 ≤ {@link Config#feEnergyCubePushPerTick}；<b>不推给别的能量立方</b>（集群内部已共享
 *     容量，相邻立方之间来回倒电没有收益）。实现见 {@link #pushToNeighbours()}，由网络节点
 *     {@code doWork} 每 tick 调用（<b>与是否接入 RS 网络无关</b>：没接控制器时它依然是一块电池）；</li>
 *     <li><b>被抽取</b>：{@link ClusterEnergyCapability#extractEnergy} 恒可用，<b>永不看门控</b>；</li>
 *     <li><b>被注入</b>：{@link ClusterEnergyCapability#receiveEnergy} / {@code canReceive} 恒允许，
 *     <b>永不看门控</b>（第 106 轮改：上一版在 {@link Gate#DENY_LOW_CONTROLLER} 时返回 0/false，
 *     把「玩家自己接的发电机给立方灌电」也拦掉了，与用户口径矛盾）；</li>
 *     <li><b>从 RS 网络能量池抽电</b>：<b>唯一</b>被门控的路径（{@link #onNetworkTick}，即
 *     pullFromNetwork 那一段），控制器电量不足 / 网络没在涨时一律不抽。</li>
 * </ol>
 * <p><b>「最低优先级」到底体现在哪</b>：
 * <ul>
 *     <li><b>相对相邻机器</b>：第 1 条主动推送 —— 立方把自己存的电推给贴着的范围充电器 / 其它 FE 机器，
 *     让它们<b>先满</b>；立方只是最后一级缓存。<b>不需要改充电器任何速率语义</b>（充电器自己照旧从
 *     网络池抽电 + 本地缓存，立方推送只是多一条「贴着就能拿电」的路）；</li>
 *     <li><b>相对网络能量池</b>：{@code FEEnergyCubeNetworkNode#extract} 只补「非立方 provider 补不上的
 *     差额」，也就是说网络耗能时<b>先抽控制器 / 其它 provider，最后才轮到立方</b>（不依赖 provider 的
 *     加入顺序 —— 那是 {@code LinkedHashSet} 的插入序，玩家先放立方后放控制器时会反过来）。</li>
 * </ul>
 * <p><b>边界（如实写清）</b>：立方满 ⇒ 收方 simulate 得 0，收不到电，这属于<b>容量限制</b>，
 * 不是门控；相邻方块全满时推送量为 0，界面「相邻供能」显示“关”（有对象但本 tick 没送出去）。
 */
public class FEEnergyCubeBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<FEEnergyCubeNetworkNode> {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本块自身电量的落盘键（int；单块容量 = Config.feEnergyCubeCapacity，int 范围内）。 */
    public static final String TAG_ENERGY = "Energy";

    /**
     * 界面数据槽数量（见 {@link #getContainerData()}）。
     * <p>15/16 = 对外充放速率 / 网络抽取速率；17 = 趋势三态；18/19 = 上个采样窗口的净变化 lo/hi；
     * <b>20 = 相邻供能三态</b>（第 106 轮：0 无相邻 / 1 开 / 2 关，见 {@link #ADJACENT_SUPPLY_NONE}）。
     */
    public static final int DATA_SLOT_COUNT = 21;

    /** 门控状态（数据槽 7 的编码见 {@link #gateOrdinal(Gate)}）。 */
    public static final int GATE_NO_NETWORK = 0;
    public static final int GATE_ALLOW = 1;
    /** 控制器（非立方池）低于阈值 ⇒ 拒绝<b>从网络抽电</b>（第 106 轮起<b>不再拦外部 FE</b>）。 */
    public static final int GATE_DENY_LOW_CONTROLLER = 2;
    /** 阈值达标但网络电量没在涨 ⇒ 拒绝从网络抽电（同样不影响外部 FE）。 */
    public static final int GATE_DENY_NOT_RISING = 3;

    // ==================== 相邻供能三态（数据槽 20） ====================
    /** 六个相邻面<b>没有</b>任何可接收 FE 的方块（含「相邻的只有能量立方」—— 立方之间不互推）。 */
    public static final int ADJACENT_SUPPLY_NONE = 0;
    /** 有可接收的相邻方块，且本 tick 真的把电推出去了（绿字，玩家看到「正在供能」）。 */
    public static final int ADJACENT_SUPPLY_ON = 1;
    /** 有可接收的相邻方块，但本 tick 一个 FE 都没推出去（本块没电 / 对方都满了）。 */
    public static final int ADJACENT_SUPPLY_OFF = 2;


    /** 趋势编码（数据槽 17）：0 = 采样中（窗口未满 / 刚接入网络），1 = 上涨，2 = 持平，3 = 下降。 */
    public static final int TREND_UNKNOWN = 0;
    public static final int TREND_RISING = 1;
    public static final int TREND_FLAT = 2;
    public static final int TREND_FALLING = 3;

    /**
     * 趋势采样窗口（tick）。
     * <p><b>为什么不是每 tick 判一次</b>：RS 里发电机 / 机器是按 tick 批次扣加的，单 tick 的差值
     * 会被同一 tick 内的调度顺序带偏（例如发电机在消耗方之后 tick，就会间歇性看到「持平」）。
     * 5 tick（= 0.25 秒）足够把单 tick 噪声抹平，又短到「网络一不涨就停」不至于明显滞后。
     */
    public static final int TREND_WINDOW_TICKS = 5;

    /**
     * 门控四态。
     * <p>判定顺序（见 {@link #computeGate}）：无网络 → 阈值不达标 → 趋势不是上涨 → 放行。
     * 也就是说「控制器低电」比「网络没在涨」<b>优先</b>报告：控制器吃紧时玩家该做的是先修发电，
     * 而趋势行（数据槽 17）仍然如实显示真实趋势，不丢信息。
     * <p><b>第 106 轮起：这门控只决定「要不要从 RS 网络能量池抽电进立方」</b>；
     * 外部 FE 的注入 / 抽取<b>不再看它</b>（见 {@link ClusterEnergyCapability}）。
     */
    public enum Gate {
        /** 没接入任何 RS 网络：没有控制器可判定；外部 FE 允许自由充放（本来也没有网络可抽）。 */
        NO_NETWORK,
        /** 控制器电量 ≥ 阈值 <b>且</b> 网络电量在上涨：允许从网络抽电。 */
        ALLOW,
        /** 有网络，但控制器电量 < 阈值：不从网络抽电（<b>只拦这一条路径</b>）。 */
        DENY_LOW_CONTROLLER,
        /** 控制器电量 ≥ 阈值，但网络电量不在上涨（持平 / 下降 / 采样中）：不从网络抽电。 */
        DENY_NOT_RISING
    }

    /** 网络电量趋势（数据槽 17 的编码见 {@link #trendOrdinal(Trend)}）。 */
    public enum Trend {
        /** 还没有完整采样窗口（刚放置 / 刚接入网络 / 刚读档）：<b>不算上涨</b>，宁可不吸。 */
        UNKNOWN,
        /** 窗口内非立方池（加回本立方抽取后）严格变大 ⇒ 发电 &gt; 其它用电。 */
        RISING,
        /** 窗口内读数<b>完全没变</b>（相等不算上涨 —— 这条是用户口径的核心）。 */
        FLAT,
        /** 窗口内读数变小 ⇒ 用电 &gt; 发电。 */
        FALLING
    }

    /** 本块自己的能量存储（容量 / 输入 / 输出三条夹量见 {@link OwnEnergyStorage}）。 */
    private final OwnEnergyStorage energyStorage;
    /** 六面 FE 能力视图：对外报集群数值、按 {@link Config#feEnergyCubeMaxTransfer} 限速。 */
    private final ClusterEnergyCapability externalEnergyStorage;

    // ==================== 网络电量趋势（「正在上涨」这条闸的采样状态） ====================

    /** 最近一次采样时的 {@code level.getGameTime()}（{@link Long#MIN_VALUE} = 还没采过）。 */
    private long trendSampleTick = Long.MIN_VALUE;
    /** 是否已有基线：false ⇒ 本 tick 只建立基线、不判趋势（趋势保持 {@link Trend#UNKNOWN}）。 */
    private boolean trendHasBaseline;
    /** 上次采样时的「反事实非立方存量」（= 非立方存量 + 本立方自基线以来抽走的量）。 */
    private long trendBaseline;
    /** 自基线以来<b>本立方</b>从网络抽走的量（FE）：采样时加回去，抵消「一充就不涨」的自反馈。 */
    private long trendPulledSinceBaseline;
    /** 最近一个完整窗口的净变化（FE，带符号；只用于界面显示 / 排错，不参与门控）。 */
    private long trendDelta;
    /** 当前趋势：服务端每 {@link #TREND_WINDOW_TICKS} tick 更新一次；客户端恒为 {@link Trend#UNKNOWN}。 */
    private Trend chargeTrend = Trend.UNKNOWN;

    // ==================== 相邻供能（第 106 轮：立方 = 传递介质） ====================

    /**
     * 相邻供能三态（数据槽 20）：{@link #ADJACENT_SUPPLY_NONE} / {@link #ADJACENT_SUPPLY_ON} /
     * {@link #ADJACENT_SUPPLY_OFF}。
     * <p>由 {@link #pushToNeighbours()} 每 tick 更新一次，界面只读；<b>不在客户端计算</b>
     * （客户端 ticker 为 null，也不该去查邻居能力）。
     */
    private int adjacentSupplyState = ADJACENT_SUPPLY_NONE;

    /**
     * 本 tick 正在「从 RS 网络能量池抽电」的<b>重入标记</b>。
     * <p>为什么必须有：{@link #onNetworkTick} 里 {@code network.extract(request)} 会遍历<b>所有</b>
     * provider（含本节点自己）。请求量虽然已经按「非立方存量」夹过，但同一 tick 里别的机器可能先把
     * 控制器抽走 ⇒ 遍历到本节点时非立方池已经空了 ⇒ 会把立方自己的电抽出来再灌回立方自己
     * （左右口袋搬运，还会给趋势采样记一笔假的「抽了电」）。置位期间
     * {@code FEEnergyCubeNetworkNode#extract} 直接返回 0：<b>立方永远不做自己的电源</b>。
     */
    private boolean pullingFromNetwork;

    /** 本 tick 上一次相邻推送实际送出的 FE（只用于 devlog / 自检取证，不进数据槽）。 */
    private int lastPushedPerTick;

    public FEEnergyCubeBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.FE_ENERGY_CUBE_BLOCK_ENTITY.get(), pos, state, new FEEnergyCubeNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        // 内部三条夹量都放到容量：本块电量的写入路径有两条（外部 FE 灌入 / 网络抽取灌入），
        // 真正该夹「外部机器能灌多快」的是能力视图那一层（ClusterEnergyCapability），
        // 网络抽取灌入则必须「抽多少收多少」—— 若这里按传输速率截断，差额就是
        // 「已从网络扣掉、却没人收到」的纯损耗（与 RangeChargerNetworkNode 上那段注释同一个教训）。
        this.energyStorage = new OwnEnergyStorage(Config.feEnergyCubeCapacity);
        this.externalEnergyStorage = new ClusterEnergyCapability();
    }

    /**
     * 本块自己的能量存储：容量 / maxReceive / maxExtract 全部 = 容量，另加一个「直接设值」的恢复入口。
     *
     * <p><b>为什么必须有 {@link #restoreEnergy}</b>：NeoForge 的 {@link EnergyStorage#receiveEnergy} 会被
     * 自身的 {@code maxReceive} 夹住，而读档是「<b>设定值</b>」语义、不是「接收能量」语义。
     * 若用 receiveEnergy 恢复 5000 万，一次只写进 maxReceive ⇒ 玩家看到「存满了，重进又空了」
     * （这正是范围充电器 2026-10-05 实测到的那个 bug，见 RangeChargerBlockEntity.RestorableEnergyStorage）。
     */
    private static final class OwnEnergyStorage extends EnergyStorage {
        OwnEnergyStorage(final int capacity) {
            super(Math.max(1, capacity), Math.max(1, capacity), Math.max(1, capacity));
        }

        /** 把存储量<b>直接设为</b> {@code amount}（只夹到 [0, capacity]，与 maxReceive 无关）。 */
        void restoreEnergy(final int amount) {
            this.energy = Math.clamp(amount, 0, this.capacity);
        }
    }

    // ==================== 本块数值（网络节点 / 落盘 / 掉落物品都用它） ====================

    /** 本块自己的存量（≤ {@link #getOwnCapacity()}）。 */
    public int getOwnEnergyStored() {
        return energyStorage.getEnergyStored();
    }

    /** 本块自己的容量（= {@link Config#feEnergyCubeCapacity}，构造时取）。 */
    public int getOwnCapacity() {
        return energyStorage.getMaxEnergyStored();
    }

    /** 六面 FE 能力视图（输入输出按 {@link Config#feEnergyCubeMaxTransfer} 夹住、数值报集群）。 */
    public IEnergyStorage getExternalEnergyStorage() {
        return externalEnergyStorage;
    }

    /**
     * 从<b>本块</b>扣电（集群内部用；不检查门控、不限速 —— 门控在调用方）。
     *
     * @return 真实扣掉的量
     */
    public long extractOwn(final long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        final int got = energyStorage.extractEnergy((int) Math.min(amount, Integer.MAX_VALUE), false);
        if (got > 0) {
            setChanged();
        }
        return got;
    }

    /**
     * 往<b>本块</b>灌电（集群内部用；不检查门控、不限速 —— 门控在调用方）。
     *
     * @return 真实收下的量（被本块剩余空间夹住）
     */
    public long receiveOwn(final long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        final int got = energyStorage.receiveEnergy((int) Math.min(amount, Integer.MAX_VALUE), false);
        if (got > 0) {
            setChanged();
        }
        return got;
    }

    // ==================== 集群数值（界面 / 六面能力视图用） ====================

    /** 集群存量 = Σ 各成员自身存量（服务端）；客户端 / 未放置时退回本块值。 */
    public long getClusterEnergyStored() {
        if (level == null || level.isClientSide()) {
            return getOwnEnergyStored();
        }
        return snapshot().stored();
    }

    /** 集群容量 = Σ 各成员自身容量（服务端）；客户端 / 未放置时退回本块值。 */
    public long getClusterCapacity() {
        if (level == null || level.isClientSide()) {
            return getOwnCapacity();
        }
        return snapshot().capacity();
    }

    /** 集群块数（服务端；客户端 / 未放置时恒为 1）。 */
    public int getClusterMemberCount() {
        if (level == null || level.isClientSide()) {
            return 1;
        }
        return snapshot().size();
    }

    private EnergyCubeCluster.Snapshot snapshot() {
        return EnergyCubeCluster.resolve(level, worldPosition);
    }

    // ==================== 门控读数（Stats：网络池总量 / 立方贡献 / 集群） ====================

    /**
     * 门控读数快照：网络总量、<b>本网络所有立方</b>的贡献、本集群数值。
     *
     * @param cubeStored   本网络所有能量立方节点的存量之和（{@link EnergyCubeNetworkRegistry}）
     * @param cubeCapacity 本网络所有能量立方节点的容量之和
     * @param members      本集群块数（仅界面显示用）
     */
    private record Stats(long networkStored, long networkCapacity,
                         long cubeStored, long cubeCapacity,
                         long clusterStored, long clusterCapacity, int members) {
        /**
         * 「控制器电量」的等效存量 = 网络池总量 − <b>本网络所有立方</b>。
         * <p>下限夹 0：极端时序 / 第三方 provider 让减法越界时绝不允许出现负数（那会被当成「没电」）。
         */
        long nonCubeStored() {
            return Math.max(0L, networkStored - cubeStored);
        }

        /**
         * 「控制器电量」的等效容量 = 网络池总容量 − 本网络所有立方。
         * <p>创造控制器的 {@code Long.MAX_VALUE} 也走这里（两个 long 相减不溢出）。
         */
        long nonCubeCapacity() {
            return Math.max(0L, networkCapacity - cubeCapacity);
        }
    }

    /**
     * 组装一次快照：集群数值只解析一次（界面每 tick 只有一次）＋ 网络总量 ＋ 本网络所有立方的贡献。
     * <p><b>为什么减的是「本网络所有立方」而不是「本集群」</b>：同一张网络可以挂多组互不相邻的立方簇，
     * 只减自己那组时，其它立方簇的电量会继续污染百分比（网络里立方越多、闸越关不上）。
     */
    private Stats stats(@Nullable final Network network) {
        if (level == null || level.isClientSide()) {
            return new Stats(0L, 0L, 0L, 0L, getOwnEnergyStored(), getOwnCapacity(), 1);
        }
        final EnergyCubeCluster.Snapshot snapshot = snapshot();
        final long clusterStored = snapshot.stored();
        final long clusterCapacity = snapshot.capacity();
        final int members = snapshot.size();
        if (network == null) {
            return new Stats(0L, 0L, 0L, 0L, clusterStored, clusterCapacity, members);
        }
        final EnergyNetworkComponent energy = network.getComponent(EnergyNetworkComponent.class);
        return new Stats(energy.getStored(), energy.getCapacity(),
            EnergyCubeNetworkRegistry.totalStored(network),
            EnergyCubeNetworkRegistry.totalCapacity(network),
            clusterStored, clusterCapacity, members);
    }

    // ==================== 网络电量趋势采样 ====================

    /**
     * 采样「网络电量趋势」：每 {@link #TREND_WINDOW_TICKS} tick 比较一次
     * <b>反事实非立方存量</b>（= {@code stats.nonCubeStored()} + 本立方期间抽走的量），
     * <b>严格变大</b>才算 {@link Trend#RISING}（相等 = {@link Trend#FLAT}，<b>不算</b>上涨）。
     *
     * <h2>两个口径细节（都是真会出错的点）</h2>
     * <ol>
     *     <li><b>量非立方存量</b>（控制器口径）而不是网络总量：外部 FE 给立方充电会让<b>总量</b>上升，
     *     拿总量判趋势会把「玩家自己接的电源在充」误判成「网络电量在涨」，于是立方一边收外部 FE
     *     一边从 RS 网络抽电；非立方存量对这条完全免疫。</li>
     *     <li><b>加回本立方自己抽走的量</b>（{@link #trendPulledSinceBaseline}）：否则「一充就不涨」
     *     ⇒ 停抽 ⇒ 再涨 ⇒ 再抽 的自反馈振荡。加回后得到的读数 =「假设本立方没抽时非立方池会有多少」，
     *     它的窗口变化就是「发电 − 其它用电」。</li>
     * </ol>
     *
     * <h2>采样点为什么在「本 tick 抽电之前」</h2>
     * <p>本方法由 {@link #onNetworkTick} 在门控判定<b>之前</b>调用：读数是「抽电前」的，不能把
     * 本 tick 刚抽走的量夹进基线。
     *
     * <h2>UNKNOWN 的处理</h2>
     * <p>没有基线（刚放置 / 刚接入网络 / 刚读档）时只建立基线、趋势留在 {@link Trend#UNKNOWN}；
     * {@link Trend#UNKNOWN} <b>不算上涨</b> ⇒ 最多 5 tick 内不吸电。这是故意的：宁可晚 0.25 秒
     * 开始吸，也不要在「还不知道网络在不在涨」的时候先抢一票。
     *
     * @param stats 本 tick 的快照（用 {@code nonCubeStored}，即控制器口径；理由见上）
     */
    private void sampleChargeTrend(final Stats stats) {
        if (level == null || level.isClientSide()) {
            return;
        }
        final long now = level.getGameTime();
        // 反事实读数：把本立方自己抽走的加回去（详见类注释「为什么量的是非立方池 + 加回」）。
        final long counterfactual = stats.nonCubeStored() + trendPulledSinceBaseline;
        if (!trendHasBaseline) {
            trendHasBaseline = true;
            trendBaseline = counterfactual;
            trendPulledSinceBaseline = 0L;
            trendSampleTick = now;
            trendDelta = 0L;
            chargeTrend = Trend.UNKNOWN;
            return;
        }
        // now < trendSampleTick：世界时间被重置（换维度 / 重载存档）时的兜底 —— 立刻重采，别等窗口。
        if (now >= trendSampleTick && now - trendSampleTick < TREND_WINDOW_TICKS) {
            return;
        }
        final long delta = counterfactual - trendBaseline;
        chargeTrend = delta > 0L ? Trend.RISING : (delta < 0L ? Trend.FALLING : Trend.FLAT);
        trendDelta = delta;
        trendBaseline = counterfactual;
        trendPulledSinceBaseline = 0L;
        trendSampleTick = now;
    }

    /**
     * 作废趋势采样（网络接入 / 离开 / 读档时调用）：下一次 {@link #onNetworkTick} 重新建立基线。
     * <p>不重置就跨网络沿用旧基线 ⇒ 把「旧网络的增长」当成新网络在涨，白吸一票。
     */
    public void resetChargeTrend() {
        trendHasBaseline = false;
        trendSampleTick = Long.MIN_VALUE;
        trendBaseline = 0L;
        trendPulledSinceBaseline = 0L;
        trendDelta = 0L;
        chargeTrend = Trend.UNKNOWN;
    }

    /** 当前趋势（服务端采样值；客户端恒为 {@link Trend#UNKNOWN}，界面永远只读数据槽）。 */
    public Trend getChargeTrend() {
        return chargeTrend;
    }

    /** 数据槽 17 的编码：0 = 采样中 / 1 = 上涨 / 2 = 持平 / 3 = 下降。 */
    public static int trendOrdinal(final Trend trend) {
        return switch (trend) {
            case UNKNOWN -> TREND_UNKNOWN;
            case RISING -> TREND_RISING;
            case FLAT -> TREND_FLAT;
            case FALLING -> TREND_FALLING;
        };
    }

    // ==================== 门控 ====================

    /**
     * 门控判定（<b>唯一实现</b>）：界面状态行 + 网络抽取（{@link #onNetworkTick}）两处走这里。
     * <p><b>第 106 轮起外部 FE 的注入 / 抽取不再调用本方法</b> —— 门控只管「从 RS 网络能量池抽电」。
     *
     * <p>判定表（四态，与界面数据槽 7 的编码一致）：
     * <ul>
     *     <li><b>NO_NETWORK</b>：{@code network == null}（没接上控制器）⇒ 没有「控制器电量」可判；
     *     <b>不会</b>从网络抽电（没网络可抽）；</li>
     *     <li><b>percent</b>：{@code nonCubeCapacity <= 0 ? 0 : nonCubeStored / nonCubeCapacity}，
     *     {@code percent*100 >= feEnergyCubeChargeThresholdPercent}（默认 90，含等号）才算达标；</li>
     *     <li><b>absolute</b>：{@code nonCubeStored >= feEnergyCubeChargeThresholdAbsolute} 才算达标；</li>
     *     <li>阈值<b>不</b>达标 ⇒ {@link Gate#DENY_LOW_CONTROLLER}（不从网络抽电）；</li>
     *     <li>阈值达标但趋势 {@code != RISING} ⇒ {@link Gate#DENY_NOT_RISING}（不从网络抽电）；</li>
     *     <li>两条都满足 ⇒ {@link Gate#ALLOW}。</li>
     * </ul>
     * <p>趋势读的是缓存的 {@link #chargeTrend}（由 {@link #sampleChargeTrend} 更新），本方法
     * <b>不做采样</b> —— 它会被界面（每 tick 逐槽读）高频调用，在这里采样会把「窗口」打碎成
     * 每 tick 一次，等于没有窗口。
     */
    private Gate computeGate(@Nullable final Network network, final Stats stats) {
        if (network == null) {
            return Gate.NO_NETWORK;
        }
        final long nonCubeStored = stats.nonCubeStored();
        final long nonCubeCapacity = stats.nonCubeCapacity();
        final boolean thresholdMet;
        if (Config.feEnergyCubeChargeModeAbsolute) {
            thresholdMet = nonCubeStored >= Config.feEnergyCubeChargeThresholdAbsolute;
        } else {
            // 用 double 而不是 long 乘法：创造控制器的 stored/capacity 都是 Long.MAX_VALUE，
            // `stored * 100` 会溢出成负数，把「无限电」判成「没电」。
            final double percent = nonCubeCapacity <= 0L
                ? 0.0D : nonCubeStored * 100.0D / (double) nonCubeCapacity;
            thresholdMet = percent >= Config.feEnergyCubeChargeThresholdPercent;
        }
        if (!thresholdMet) {
            // 低电优先报告：控制器吃紧时玩家该先补发电；趋势行（数据槽 17）仍显示真实趋势。
            return Gate.DENY_LOW_CONTROLLER;
        }
        // 低优先级的核心：控制器电量够，但网络没在涨 ⇒ 不抢电（用户口径：不断上涨才充）。
        return chargeTrend == Trend.RISING ? Gate.ALLOW : Gate.DENY_NOT_RISING;
    }

    /** 当前门控状态（服务端权威；客户端网络节点没有网络 ⇒ 恒为 NO_NETWORK，界面不靠它判断）。 */
    public Gate getGateState() {
        final Network network = mainNetworkNode.getNetwork();
        return computeGate(network, stats(network));
    }

    /** 数据槽 7 的编码：0 = 无网络 / 1 = 允许 / 2 = 低电拒绝 / 3 = 网络没在涨。 */
    public static int gateOrdinal(final Gate gate) {
        return switch (gate) {
            case NO_NETWORK -> GATE_NO_NETWORK;
            case ALLOW -> GATE_ALLOW;
            case DENY_LOW_CONTROLLER -> GATE_DENY_LOW_CONTROLLER;
            case DENY_NOT_RISING -> GATE_DENY_NOT_RISING;
        };
    }

    // ==================== 网络抽取灌入（节点 doWork 的入口） ====================

    /**
     * 由 {@link FEEnergyCubeNetworkNode#doWork()} 每 tick 调用：<b>先采样趋势、后判门控</b>，
     * 门控为 {@link Gate#ALLOW}（控制器 ≥ 阈值 <b>且</b> 网络电量在涨）时才从网络能量池抽电灌进集群。
     *
     * <p><b>「从网络抽电」实际抽的是谁</b>：RS 2.0.9 的 {@code EnergyNetworkComponentImpl#extract}
     * 按 provider 加入顺序逐个取。第 106 轮起 {@code FEEnergyCubeNetworkNode#extract} 会主动
     * 「让位」—— 只要同一张网络上非立方 provider 还有电，立方只补它们补不上的差额，
     * 于是这一抽<b>永远先落在控制器 / 其它 provider 上，立方自己是最后一个</b>
     * （不再依赖 {@code LinkedHashSet} 的插入顺序）。<br>
     * 也就是说「控制器 ≥ 阈值时把电存进立方」在实现上不是「从富余电量抽」（RS 没有「富余」这个对象），
     * 而是「阈值允许时直接从控制器/网络池抽」。这段注释是事实说明，不是设计缺陷。
     *
     * <h2>抽取量为什么是「三重夹」</h2>
     * <ol>
     *     <li>不能超过<b>集群剩余空间</b>（灌不进去的电会凭空消失 —— 范围充电器当年就是把网抽干、
     *     差额全损耗，见 {@code RangeChargerNetworkNode#doWork} 的注释）；</li>
     *     <li>不能超过 {@link Config#feEnergyCubeNetworkPullPerTick}（这是本功能的「充电功率」旋钮）；</li>
     *     <li>不能超过<b>非立方 provider 的总存量</b>（{@code stats.nonCubeStored()}，主要是控制器）：
     *     请求量若大于别人的存量，{@code EnergyNetworkComponentImpl#extract} 会继续往下遍历，
     *     可能反过来抽到<b>我们自己或同网络的其它立方</b> —— 那一圈「从左口袋搬到右口袋」只会让
     *     每块的账面电量乱跳，没有任何收益。</li>
     * </ol>
     * <p>三项都满足时，「抽到多少」一定能被集群整份收下（集群剩余空间在抽取前后只增不减），
     * 若真出现 {@code received < extracted}，说明有人改了不变量，这里 WARN 留证（绝不静默丢能量）。
     * <p><b>第 106 轮：抽取期间置位 {@link #pullingFromNetwork}</b>，让本节点在遍历到自己时直接
     * 返回 0 —— 这一抽绝不能变成「从立方自己身上抽电再灌回立方」（左右口袋搬运 + 趋势假阳性）。
     */
    public void onNetworkTick(@Nullable final Network network) {
        if (network == null || level == null || level.isClientSide()) {
            return;
        }
        final Stats stats = stats(network);
        // 先采样趋势、再做门控判定：采样必须在「本 tick 抽电之前」，否则采样里会夹进本立方
        // 刚抽走的电（负反馈），详见 sampleChargeTrend 的注释。
        sampleChargeTrend(stats);
        if (computeGate(network, stats) != Gate.ALLOW) {
            return;
        }
        final long space = stats.clusterCapacity() - stats.clusterStored();
        if (space <= 0L) {
            return;
        }
        final long others = stats.nonCubeStored();
        if (others <= 0L) {
            return;
        }
        final long request = Math.min(Math.min(space, (long) Config.feEnergyCubeNetworkPullPerTick), others);
        if (request <= 0L) {
            return;
        }
        final long extracted;
        // try/finally：即使 extract 抛异常也不能把标记永久留在 true 上（那会让立方永远不放电）。
        pullingFromNetwork = true;
        try {
            extracted = network.getComponent(EnergyNetworkComponent.class).extract(request);
        } finally {
            pullingFromNetwork = false;
        }
        if (extracted <= 0L) {
            return;
        }
        // 记进「本立方自基线以来抽走的量」：趋势采样时加回去，抵消「一充就不涨」的自反馈。
        // 请求量已被 others（非立方存量）夹住 ⇒ 这些电确实是从非立方池里拿的。
        trendPulledSinceBaseline += extracted;
        final long received = receiveIntoCluster(extracted);
        if (received < extracted) {
            LOGGER.warn("[rs_create_compat] FE 能量立方 @{} 从网络抽取 {} FE 但只收下 {} FE（差额来自不变量被破坏，"
                + "不是正常路径）；请把这条日志和当时的网络状态一起上报", worldPosition, extracted, received);
        }
    }

    /**
     * 网络耗能时由节点调用：从<b>整个集群</b>扣电（按坐标字典序从最小成员开始），不受传输速率限制。
     * <p>客户端 / 未加载世界一律返回 0：<b>绝不在客户端写世界</b>。
     */
    public long dischargeToNetwork(final long amount) {
        if (level == null || level.isClientSide() || amount <= 0L) {
            return 0L;
        }
        return snapshot().extract(amount);
    }

    /** 从整个集群扣电（服务端写路径；客户端 / 未加载返回 0）。 */
    private long extractFromCluster(final long amount) {
        if (level == null || level.isClientSide() || amount <= 0L) {
            return 0L;
        }
        return snapshot().extract(amount);
    }

    /** 往整个集群灌电（先本块、再按字典序填其它块；服务端写路径，客户端返回 0）。 */
    private long receiveIntoCluster(final long amount) {
        if (level == null || level.isClientSide() || amount <= 0L) {
            return 0L;
        }
        return snapshot().receive(amount, worldPosition);
    }

    // ==================== 相邻供能（第 106 轮：立方 = 传递介质 / 缓存） ====================

    /**
     * 由 {@link FEEnergyCubeNetworkNode#doWork()} <b>每 tick 无条件</b>调用（与是否接入 RS 网络无关）：
     * 向六个相邻面推送 FE，然后更新界面「相邻供能」三态（数据槽 20）。
     *
     * <p>客户端 / 未加载世界直接返回：绝不在客户端写世界（客户端 ticker 本来也是 null）。
     */
    public void tickNeighbourPush() {
        if (level == null || level.isClientSide()) {
            return;
        }
        lastPushedPerTick = pushToNeighbours();
    }

    /**
     * 向六个相邻方块的 {@code Capabilities.EnergyStorage.BLOCK} 主动推送 FE（立方作为「传递介质」）。
     *
     * <h2>为什么「先 simulate 再真收」</h2>
     * <p>先从本机扣、再让对方收，中间若对方只收下一部分，差额就是「凭空消失的电」。因此：
     * <ol>
     *     <li>先 {@code receiveEnergy(offer, true)} 问清对方本 tick 能吃多少（<b>simulate 不动任何状态</b>）；</li>
     *     <li>按 {@code min(自身存量, 本 tick 预算, 对方能吃)} 从<b>集群</b>扣；</li>
     *     <li>再 {@code receiveEnergy(taken, false)} 真收；万一实际收下的比预期少（模拟与实际不一致），
     *     <b>把差额收回集群</b>，记账上永远守恒（与 {@code DecompositionGeneratorBlockEntity#pushToNeighbours}
     *     同一套守恒口径）。</li>
     * </ol>
     *
     * <h2>两个必须跳过的对象</h2>
     * <ul>
     *     <li><b>别的能量立方</b>：集群内部已经共享容量（每块只记自己那一份、取电时跨成员扣），
     *     相邻立方之间互相推 = 把一份电在几个方块之间来回搬，没有任何收益，还会让界面数字乱跳；
     *     判定用<b>方块类型</b>（{@code instanceof FEEnergyCubeBlock}），不依赖方块实体是否已加载；</li>
     *     <li><b>未加载的区块</b>：不为「给邻居充电」去加载区块（不 {@code getBlockEntity} 未加载坐标）。</li>
     * </ul>
     *
     * <h2>上限口径</h2>
     * <p>每 tick 总量 ≤ {@link Config#feEnergyCubePushPerTick}，<b>六面分摊这一个预算</b>
     * （与降解室发电机同口径）；单次真收还受收方自己的 {@code receiveEnergy} 夹量。
     * <p><b>预算是「每个立方一份」</b>，不是「每个集群一份」：N 块相邻的立方簇贴着同一台机器时，
     * 理论上限是 N × 本配置值 / tick（每块各自 tick 一次、各自推一次），实际受收方
     * {@code receiveEnergy} 的夹量约束。这个口径与「每块立方都是一块独立电池」一致。
     * <p><b>六面的顺序是 {@code Direction.values()} 的固定顺序</b>（DOWN / UP / NORTH / SOUTH / WEST / EAST），
     * 预算按这个顺序先到先得 ⇒ 相邻多台机器、且总预算小于它们的总需求时，排在后面的面可能吃不饱
     * （残留风险，如实记：没有做轮转 / 公平调度，因为每 tick 10,000 FE 对单台机器通常绰绰有余）。
     * <p>推送<b>不看门控</b>：门控只管「从 RS 网络抽电进立方」，立方把已存的电送出去属于「放电」，
     * 是备用电池 / 缓存的本职工作（{@code Gate.DENY_LOW_CONTROLLER} 时也一样送）。
     *
     * @return 本 tick 实际送出的 FE 总量（≤ 预算）
     */
    private int pushToNeighbours() {
        int hasReceiver = 0;
        long budget = Math.max(0L, (long) Config.feEnergyCubePushPerTick);
        int total = 0;
        for (final Direction direction : Direction.values()) {
            final BlockPos target = worldPosition.relative(direction);
            if (!level.isLoaded(target)) {
                continue;
            }
            // 不推给别的能量立方：集群内部已共享容量，相邻互推只会来回倒电（见方法注释）。
            if (level.getBlockState(target).getBlock() instanceof FEEnergyCubeBlock) {
                continue;
            }
            final BlockEntity neighbour = level.getBlockEntity(target);
            if (neighbour == null) {
                continue;
            }
            final IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, target,
                neighbour.getBlockState(), neighbour, direction.getOpposite());
            if (storage == null || !storage.canReceive()) {
                continue;
            }
            // 有可接收的邻居 —— **先记状态、再判预算**：本块没电 / 预算用尽时界面要显示「关」
            // （有对象、但本 tick 没送出去），不能错显示成「无相邻」。
            hasReceiver = 1;
            if (budget <= 0L) {
                continue;
            }
            final long available = getClusterEnergyStored();
            final int offer = (int) Math.min(Math.min(budget, available), Integer.MAX_VALUE);
            if (offer <= 0) {
                continue;
            }
            // ① simulate：问清对方本 tick 真能吃多少（不改变任何状态）
            final int accepted = storage.receiveEnergy(offer, true);
            if (accepted <= 0) {
                continue;
            }
            // ② 按 accepted 从集群扣电（accepted ≤ available，一定扣得到）
            final long taken = extractFromCluster(accepted);
            if (taken <= 0L) {
                continue;
            }
            // ③ 真收 + 守恒兜底：实际收下少于扣掉的，把差额收回集群（绝不让电消失）
            final int actual = storage.receiveEnergy((int) taken, false);
            if (actual < taken) {
                receiveIntoCluster(taken - actual);
            }
            final int moved = (int) Math.max(0L, Math.min(actual, taken));
            total += moved;
            budget -= moved;
        }
        adjacentSupplyState = hasReceiver == 0
            ? ADJACENT_SUPPLY_NONE
            : (total > 0 ? ADJACENT_SUPPLY_ON : ADJACENT_SUPPLY_OFF);
        if (total > 0) {
            setChanged();
        }
        return total;
    }

    /** 数据槽 20：相邻供能三态（{@link #ADJACENT_SUPPLY_NONE} / {@code ON} / {@code OFF}）。 */
    public int getAdjacentSupplyState() {
        return adjacentSupplyState;
    }

    /** 本 tick 相邻推送实际送出的 FE（诊断用；界面走数据槽 20 的三态而不是这个数）。 */
    public int getLastPushedPerTick() {
        return lastPushedPerTick;
    }

    /** 本 tick 是否正在「从 RS 网络能量池抽电」——节点 {@code extract} 用它避免「自己抽自己」。 */
    public boolean isPullingFromNetwork() {
        return pullingFromNetwork;
    }

    // ==================== 六面 FE 能力视图 ====================

    /**
     * 对外（{@code Capabilities.EnergyStorage.BLOCK}，六个方向 + null 共用同一个实例）的能量视图。
     *
     * <ul>
     *     <li><b>数值报集群</b>：外部机器从任意一面看 / 抽，都是整块电池（容量共享的对外承诺）；</li>
     *     <li><b>速率夹到</b> {@link Config#feEnergyCubeMaxTransfer}：每次调用（每面每 tick 的每一次）上限；</li>
     *     <li><b>输入 / 输出永不受门控</b>（第 106 轮）：用户口径是「立方是传递的介质 / 缓存，
     *     可以被别人注入、也可以被别的吸取」。上一版在 {@link Gate#DENY_LOW_CONTROLLER} 时把
     *     {@code receiveEnergy} 打成 0、{@code canReceive} 打成 false —— 结果「玩家自己接的发电机
     *     给立方灌电」被网络状态拦掉了，与用户口径直接矛盾。<b>门控只作用于「从 RS 网络能量池
     *     抽电进立方」那一条路径</b>（{@link #onNetworkTick}）。</li>
     * </ul>
     *
     * <p><b>「满电」不等于「门控」</b>：立方满了以后 {@code receiveEnergy} 返回 0，这是
     * <b>容量限制</b>（空间夹量 {@code Math.max(0L, space)} 得 0），任何门控状态都不该被当成原因；
     * 修这台机器的人若把「满」误读成「被门控拦了」，会往完全错误的方向排查。
     *
     * <p><b>对外能力是 int 口径，会有饱和边界（如实写清）</b>：{@link IEnergyStorage} 的
     * {@code getEnergyStored / getMaxEnergyStored} 只有 int，而 50,000,000 × <b>43 块</b> =
     * 2,150,000,000 &gt; {@link Integer#MAX_VALUE} ⇒ <b>43 块及以上</b>时外部机器看到的容量会饱和到
     * {@code Integer.MAX_VALUE}（不是精确值）。本模组自己的界面走 16 个 long 数据槽，仍显示精确值。
     * <p><b>内部的「还能装多少」永远用 long 精确值算</b>（{@code getClusterCapacity() -
     * getClusterEnergyStored()} 是 long），饱和只发生在「返回值转 int」这一步，绝不参与空间计算 ——
     * 否则 43 块之后会以为还有 21 亿空间，把电灌进已经不存在的空间（= 丢电）。
     */
    private final class ClusterEnergyCapability implements IEnergyStorage {
        @Override
        public int receiveEnergy(final int toReceive, final boolean simulate) {
            if (toReceive <= 0) {
                return 0;
            }
            // 第 106 轮：外部 FE 注入永不受门控（门控只拦「从 RS 网络抽电」，见 onNetworkTick）。
            final long space = getClusterCapacity() - getClusterEnergyStored();
            final long request = Math.min(Math.min((long) toReceive, transferLimit()), Math.max(0L, space));
            if (request <= 0L) {
                return 0;
            }
            if (simulate) {
                return clampToInt(request);
            }
            return clampToInt(receiveIntoCluster(request));
        }

        @Override
        public int extractEnergy(final int toExtract, final boolean simulate) {
            if (toExtract <= 0) {
                return 0;
            }
            final long available = Math.min(Math.min((long) toExtract, transferLimit()),
                getClusterEnergyStored());
            if (available <= 0L) {
                return 0;
            }
            if (simulate) {
                return clampToInt(available);
            }
            return clampToInt(extractFromCluster(available));
        }

        @Override
        public int getEnergyStored() {
            return clampToInt(getClusterEnergyStored());
        }

        @Override
        public int getMaxEnergyStored() {
            return clampToInt(getClusterCapacity());
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            // 第 106 轮：永远可以接收外部 FE（注入不过门控）。立方满了时 receiveEnergy 自然返回 0，
            // 那是容量夹量，不是「不可接收」—— canReceive 回答的是「这个机器是否允许外部灌电」。
            return true;
        }
    }

    /** 单次能力调用的传输上限（配置允许 1..Integer.MAX_VALUE，这里至少按 1 处理）。 */
    private static long transferLimit() {
        return Math.max(1, Config.feEnergyCubeMaxTransfer);
    }

    /** long → int 饱和转换（集群总量 / 容量可能超过 int；对外的 IEnergyStorage 只有 int 口径）。 */
    private static int clampToInt(final long value) {
        return (int) Math.max(0L, Math.min(value, (long) Integer.MAX_VALUE));
    }

    // ==================== 界面数据槽 ====================

    /**
     * 供菜单同步的实时数据（<b>21 个槽</b>）。服务端权威，客户端界面只读同步值、绝不自己算。
     *
     * <ul>
     *     <li>0 / 1 = 本块存量 / 容量（单块 ≤ 5000 万，int 够）；</li>
     *     <li>2 / 3 = 集群存量 lo / hi、4 / 5 = 集群容量 lo / hi（N 块可达 32 亿，超 int ⇒ 拆两段）；</li>
     *     <li>6 = 集群块数；7 = 门控四态（0 无网络 / 1 允许 / 2 低电拒绝 / 3 网络没在涨）；</li>
     *     <li>8 = 计费模式（0 = percent，1 = absolute）；9 = 百分比阈值；10 = 绝对阈值；</li>
     *     <li>11 / 12 = 「非立方」存量 lo / hi、13 / 14 = 「非立方」容量 lo / hi
     *     （= 网络池总量 − 本网络所有立方；界面状态行与 tooltip 的「控制器 xx%」就来自这两对）；</li>
     *     <li>15 = 对外充放速率上限、16 = 网络抽取速率（都来自配置，<b>由服务端下发</b> ——
     *     界面绝不读 {@code Config}：专服上客户端配置可能和服务端不同，读本地配置会显示错的速率）；</li>
     *     <li><b>17 = 网络电量趋势</b>（0 采样中 / 1 上涨 / 2 持平 / 3 下降；服务端按
     *     {@link #TREND_WINDOW_TICKS} tick 采样得出，客户端只读）；</li>
     *     <li><b>18 / 19 = 上个采样窗口的净变化 lo / hi</b>（FE，带符号；已加回本立方自己的抽取，
     *     即「网络盈余」口径；只用于显示 / 排错，
     *     <b>不参与门控</b> —— 门控只看数据槽 7 与 17 的编码）；</li>
     *     <li><b>20 = 相邻供能三态</b>（第 106 轮：0 无相邻 / 1 开 / 2 关；由
     *     {@link #pushToNeighbours()} 每 tick 实测得出，客户端只显示）。</li>
     * </ul>
     *
     * <p><b>每 tick 只算一次</b>：{@code AbstractContainerMenu} 每 tick 会<b>逐槽</b>读 {@code get(i)}
     * （21 次），若每格都去解析集群 / 求和网络，打开一个界面就是每秒上千次区块查询。
     * 因此这里按 {@code level.getGameTime()} 做一格 tick 级缓存 —— 同一 tick 内所有槽共享一份快照。
     */
    public ContainerData getContainerData() {
        return new ContainerData() {
            private long snapshotTick = Long.MIN_VALUE;
            private long ownStored;
            private long ownCapacity;
            private long clusterStored;
            private long clusterCapacity;
            private long nonCubeStored;
            private long nonCubeCapacity;
            private int members;
            private int gate;
            private int trend;
            private long trendDelta;
            private int adjacent;

            private void refresh() {
                final long now = level == null ? Long.MIN_VALUE : level.getGameTime();
                if (now == snapshotTick) {
                    return;
                }
                snapshotTick = now;
                ownStored = getOwnEnergyStored();
                ownCapacity = getOwnCapacity();
                final Network network = mainNetworkNode.getNetwork();
                final Stats stats = stats(network);
                clusterStored = stats.clusterStored();
                clusterCapacity = stats.clusterCapacity();
                nonCubeStored = stats.nonCubeStored();
                nonCubeCapacity = stats.nonCubeCapacity();
                members = stats.members();
                gate = gateOrdinal(computeGate(network, stats));
                trend = trendOrdinal(chargeTrend);
                trendDelta = FEEnergyCubeBlockEntity.this.trendDelta;
                // 相邻供能状态由 tickNeighbourPush 每 tick 实测写入，这里只读缓存值
                // （数据槽 refresh 绝不能去遍历 6 个邻居 + 查能力：界面每 tick 会读 21 次）。
                adjacent = FEEnergyCubeBlockEntity.this.adjacentSupplyState;
            }

            @Override
            public int get(final int index) {
                refresh();
                return switch (index) {
                    case 0 -> clampToInt(ownStored);
                    case 1 -> clampToInt(ownCapacity);
                    case 2 -> lo(clusterStored);
                    case 3 -> hi(clusterStored);
                    case 4 -> lo(clusterCapacity);
                    case 5 -> hi(clusterCapacity);
                    case 6 -> members;
                    case 7 -> gate;
                    case 8 -> Config.feEnergyCubeChargeModeAbsolute ? 1 : 0;
                    case 9 -> Config.feEnergyCubeChargeThresholdPercent;
                    case 10 -> Config.feEnergyCubeChargeThresholdAbsolute;
                    case 11 -> lo(nonCubeStored);
                    case 12 -> hi(nonCubeStored);
                    case 13 -> lo(nonCubeCapacity);
                    case 14 -> hi(nonCubeCapacity);
                    // 15/16 = 速率上限：服务端下发（客户端配置可能与服务端不同，界面绝不读本地 Config）
                    case 15 -> Config.feEnergyCubeMaxTransfer;
                    case 16 -> Config.feEnergyCubeNetworkPullPerTick;
                    // 17 = 趋势三态；18/19 = 上窗口净变化 lo/hi（带符号，join 能还原负数）
                    case 17 -> trend;
                    case 18 -> lo(trendDelta);
                    case 19 -> hi(trendDelta);
                    // 20 = 相邻供能三态（0 无相邻 / 1 开 / 2 关；服务端每 tick 实测）
                    case 20 -> adjacent;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 只读：门控 / 数值全部由服务端逻辑决定，界面没有任何可写数据槽
            }

            @Override
            public int getCount() {
                return DATA_SLOT_COUNT;
            }
        };
    }

    /** long 的低 32 位（拆成两个 int 过数据槽；高 32 位见 {@link #hi(long)}）。 */
    public static int lo(final long value) {
        return (int) (value & 0xFFFFFFFFL);
    }

    /** long 的高 32 位。 */
    public static int hi(final long value) {
        return (int) (value >>> 32);
    }

    /** 由数据槽的两个 int 还原 long（界面侧使用；高低位与 {@link #lo}/{@link #hi} 严格配对）。 */
    public static long join(final int low, final int high) {
        return ((long) high << 32) | (low & 0xFFFFFFFFL);
    }

    // ==================== 名称（Nameable） ====================

    /**
     * 界面标题 / 方块名走方块名（与 RangeChargerBlockEntity 同一做法）。
     * <p>基类实现了 {@code Nameable}，而 {@code BlockEntity} 的默认 {@code getName()} 不会从方块名派生，
     * 因此必须显式覆写（不覆写 = 编译期「未实现抽象方法」）。
     */
    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    // ==================== 落盘 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_ENERGY, energyStorage.getEnergyStored());
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 读档是「设定值」语义：直接设值，绝不走 receiveEnergy（那会被 maxReceive 截断）。
        final int saved = Math.max(0, tag.getInt(TAG_ENERGY));
        energyStorage.restoreEnergy(saved);
        // 区块重载 / 放置还原都会改变集群构成或成员身份；顺手作废解析缓存（幂等、缓存为空时零成本）。
        EnergyCubeCluster.invalidateAround(level, worldPosition);
        // 趋势基线不能跨读档沿用：读档前后网络能量池总量可能完全不同（换维度 / 服务器重启），
        // 沿用旧基线会把「存档间的差」当成「这一 tick 在涨」。冷启动 = 重新建立基线。
        resetChargeTrend();
    }

    @Override
    public void setRemoved() {
        // 成员离场：邻居的集群缓存必须作废，否则幸存者会继续按旧块数算容量（最多 20 tick 才能自愈）。
        EnergyCubeCluster.invalidateAround(level, worldPosition);
        // 兜底注销网络登记：幽灵节点会让门控的「非立方存量 = 总量 − 立方」减得过头，把闸永久关死。
        mainNetworkNode.unregisterFromNetwork();
        super.setRemoved();
    }

    // ==================== 能力注册 ====================

    /** 向 NeoForge 注册能量与网络节点容器能力（MOD 总线事件，见 RS_Create_Compat 的监听）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        // 六面（含 null）都返回同一个集群视图：所有方向 + 通用查询都走这一个实例。
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            RS_Create_Compat.FE_ENERGY_CUBE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getExternalEnergyStorage()
        );
        // 注册为 RS 网络节点容器 —— 没有这一条，方块永远进不了网络：能量池里没有它、
        // 门控读不到控制器、备用电源完全不生效（这是最容易漏的一条）。
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.FE_ENERGY_CUBE_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
