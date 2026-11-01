package cretae.cookiewyq.rs_create_compat.support;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.compat.jade.RsccBusJadePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>用机械动力的剪贴板批量安排输入 / 输出总线的配置</b>（用户第 7 条：「一个一个手动配置这一个
 * 输入输出总线太麻烦了……得想一个办法，使它更加便于快速大量的给他安排相同的配置」）。
 *
 * <h2>为什么走 Create 剪贴板这条既有链路（而不是自己发明一套「复制 / 粘贴」按钮）</h2>
 * <p>Create 的剪贴板本来就有一套成熟的<b>「右击复制配置 / 左击粘贴配置」</b>约定
 * （{@code ClipboardValueSettingsHandler}），它的物品提示里写的就是「复制并在其他地方应用方块的某些配置，
 * 比如过滤槽」。因此总线的过滤槽 / 类别勾选顺理成章应该走同一个手势 —— 玩家不需要学新东西，
 * 也不需要本模组再加一套界面控件。</p>
 *
 * <h2>为什么本类要自己写一份挂点（不能直接实现 Create 的 {@code ClipboardCloneable}）</h2>
 * <p>Create 自己那条链路要求目标方块实体是它的 {@code SmartBlockEntity}
 * （{@code ClipboardValueSettingsHandler#interact} 第一句就是 {@code instanceof SmartBlockEntity}），
 * 而 RS 的总线方块实体继承的是 RS 自己的 {@code AbstractCableLikeBlockEntity} ——
 * <b>结构上不可能</b>变成 SmartBlockEntity（那等于换掉 RS 的整个方块实体体系）。
 * 因此这里用<b>同一套手势、同一份剪贴板数据格式</b>（{@code copied_values} 里的 {@code copiedValues} 子标签），
 * 只是挂点自己写：既不改 RS、也不改 Create，两个模组各自的存储格式都不动。</p>
 *
 * <h2>手势（与 Create 的剪贴板约定一致，潜行是「范围档」）</h2>
 * <ul>
 *     <li><b>手持剪贴板右击总线</b> → 复制这条总线的全部玩家配置（RS 过滤槽 + 模糊模式 +
 *     本模组类别勾选 + 「全自动收回」+「强制普通总线」，见 {@link RsccBusConfig}）；</li>
 *     <li><b>手持剪贴板左击总线</b> → 把配置粘贴到<b>这一条</b>总线；</li>
 *     <li><b>潜行 + 左击总线</b> → 粘贴到<b>同一条线缆簇里的全部同种总线</b>（「快速大量」那一档）。
 *     范围边界见 {@link #pasteCluster}：同一线缆簇（扳手断开的接缝 / 被分隔框架冻结的接缝都算断）、
 *     同一种总线、最多 {@link #BULK_MAX_BUSES} 台，绝不跨簇扩散。</li>
 *     <li><b>按住 FTB Ultimine 连锁键 + 右击总线</b>（手持剪贴板、不潜行）→ <b>连锁粘贴</b>：
 *     FTB 本次连锁圈里的同种总线一次写完，见 {@link #chainPaste}。这是「先用连锁框住一排总线、
 *     再一键配置」的那一档；没有装 FTB Ultimine 时这条入口根本不存在，其余手势一字不变。</li>
 *     <li><b>按住 FTB Ultimine 连锁键 + 左击总线</b>（手持剪贴板、不潜行，<b>第 95 轮新增</b>）→
 *     <b>同一套连锁粘贴</b>。加它的原因：用户实机报「右键应用正常、左键不行，明明本来该是左键」
 *     —— Create 剪贴板的原始粘贴手势就是<b>左键</b>，而 FTB 只对外提供右键扩展点
 *     （{@code RightClickHandler}；整个 jar 没有左键派发口）。因此左键这条入口自己读 FTB 已经算好的
 *     {@code cachedPositions()}（{@link RsccUltimineIntegration#chainPositions}），候选集与右键
 *     <b>同源同口径</b>；读不到就退回下面那条「只粘命中这一台」，绝不静默失效。</li>
 *     <li><b>手持剪贴板 + 潜行 + 右击总线</b>（<b>第 88 轮新增的保底</b>）→ 一次<b>整簇粘贴</b>，
 *     <b>完全不经过 FTB Ultimine</b>，见 {@link #sneakClusterPaste}。存在的理由：FTB 可能压根不派发
 *     连锁右键（用户日志里连 {@code [rscc-chain-bus]} 都没有）；这条完全走原版右键事件，默认开启，
 *     可用 {@code /rs_create_compat chain-debug fallback off} 关闭。</li>
 * </ul>
 *
 * <h2>「剪贴板在哪只手里」：左键必须自己找副手（第 95 轮）</h2>
 * <p>{@code PlayerInteractEvent.LeftClickBlock} 的构造函数把 hand <b>写死成 {@code MAIN_HAND}</b>，
 * 基类 {@code getItemStack()} = {@code player.getItemInHand(那个 hand)} —— 因此<b>左键事件在结构上
 * 永远看不到副手</b>，而右键事件带的是真实那只手。旧实现直接读 {@code event.getItemStack()}，
 * 于是「副手拿剪贴板 + 左键粘贴」必然一声不响地什么都不做（右键却正常）—— 用户报的
 * 「左键不行右键却可以」里，这是能静态证明的一条。现在两只手都查，见
 * {@link #clipboardHand(Player)}。</p>
 *
 * <h2>为什么连锁粘贴同时挂在「右击」和「左击」上</h2>
 * <p>FTB Ultimine 的<b>左键</b>默认是「连锁挖掘」：如果本模组不接管左键，玩家框住一排总线再按连锁键
 * 左击，就可能在同一串操作里既粘贴配置又挖掉方块。我们接管的方式是<b>两端都取消这次左键</b>
 * （取消后客户端连挖掘预测都不做），于是「连锁挖掘」在这条手势上被彻底拦住，
 * 只留下「粘贴配置」这一件事。右键在 FTB 里是「对连锁圈做一次使用动作」，语义上也对齐
 * （它的内建处理器——斧头去皮 / 铲子铺路 / 锄头耕地——都在那里）。两只键靠 FTB 的按键状态分成
 * 「连锁」与「单台」，本模组不需要再加一个按键或模式开关。</p>
 *
 * <h2>为什么连锁粘贴要沿「线缆簇」补目标（第 87 轮：用户实机「配置连锁还是不行」的根因）</h2>
 * <p>FTB Ultimine 的连锁形状是<b>「同一种方块」的洪泛</b>（{@code ShapelessShape} +
 * {@code MATCH_BY_BLOCK}：从命中格出发只走向方块类型完全相同的邻格）。这对
 * <b>伪装材质</b>这条分支恰好合用 —— 框架 / 伪装下面的方块全是<b>同一种线缆</b>，连成一片，
 * 一步都不断；但对<b>总线配置</b>这条分支是结构性的错配：一条 RS 线缆上挂的一串总线，彼此之间
 * 往往隔着<b>线缆方块</b>，洪泛从命中那台总线出发<b>一步都走不出去</b>，FTB 交给我们的候选集合
 * 里就只剩命中格自己。于是「材质能连锁、配置不能」的确切差异不是分派、不是权限、也不是距离，
 * 而是<b>目标方块在 FTB 形状里是否连成一片</b>。</p>
 * <p>因此本类在「FTB 形状里一个同种总线都给不出来」时，沿<b>同一条线缆簇</b>补齐同种总线
 * （{@link RsccWireLinkSearch#searchChamberLink} 的唯一一趟展开，与潜行左击整簇粘贴同一套边界：
 * 扳手断开的接缝 / 被分隔框架冻结的接缝都算断，台数上限 {@link #ULTIMINE_MAX_BUSES}、
 * 逐块距离复检 {@link #ULTIMINE_MAX_DISTANCE} 一条不少）。形状里已经有同种总线时<b>完全按形状</b>
 * 走，不做任何扩展 —— 这条兜底只在原本「一格都写不出去」的那条路径上生效。</p>
 *
 * <h2>服务端权威</h2>
 * <p>两端都只做一件事：<b>取消事件</b>（否则左击会顺手把方块挖了、右击会打开总线界面）。
 * 读剪贴板、校验、写方块实体、报文案<b>全部只在服务端</b>执行（{@code instanceof ServerLevel} 之后）。
 * 因此客户端无法凭自己那份数据改写世界状态，也不存在「客户端改了一半、服务端没改」的分裂 ——
 * 连一个自定义网络包都不需要（左击 / 右击本来就是原版会发给服务端的动作）。</p>
 *
 * <h2>为什么不破坏别的模组复制过的内容</h2>
 * <p>写入时只<b>替换本模组那一段键</b>（{@link #CLIPBOARD_KEY}），其余段落原样保留 ——
 * Create 自己的复制路径是「整份重建」，本模组刻意不这么做：玩家可能先复制了某个 Create 机器的配置，
 * 再来复制总线，那次复制不应该把前一份静默抹掉。</p>
 *
 * <h2>专用服务端安全</h2>
 * <p>本类只引用两端都存在的类型（Create 的剪贴板物品与数据组件、原版事件、本模组的 support 类），
 * 不含任何 {@code net.minecraft.client.*} 引用，因此在专用服务端上加载安全。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class RsccBusClipboard {
    /**
     * 剪贴板 {@code copied_values} 里属于本模组的那一段。
     * <p>带命名空间是刻意的：{@code copied_values} 是所有模组共用的一张表
     * （Create 的 {@code getClipboardKey()} 也是同样做法），段名冲突就等于互相覆盖配置。
     */
    private static final String CLIPBOARD_KEY = RS_Create_Compat.MODID + ":bus_config";

    /**
     * 剪贴板里本模组那一段的键（{@code rs_create_compat:bus_config}）。
     *
     * <p><b>为什么把它暴露出来</b>：客户端 tooltip（{@code client/tooltip/ClipboardTooltipHandler}）
     * 要读<b>同一段</b>数据来显示「复制了什么方块的什么配置」。键名必须只有一份定义 ——
     * 复制写一个键、提示读另一个键，就会变成「复制成功了但提示永远空白」这种最难查的静默失效。
     * 本类仍然只保留 {@link #CLIPBOARD_KEY} 这一个常量，访问器只是它的只读出口。</p>
     */
    public static String clipboardKey() {
        return CLIPBOARD_KEY;
    }

    /**
     * 整簇粘贴的台数上限。
     * <p>边界的一部分：即使玩家把几百台总线接成一整张线网，一次潜行左击最多也只改这么多台
     * （线缆簇本身的搜索是「每 tick 预算 + 跨 tick 续扫」，见 {@link RsccWireLinkSearch}
     * 里的 {@code BUS_LINK_TICK_BUDGET}；撞上异常大线网时由 {@code BUS_LINK_HARD_CAP} 兜底）。
     */
    public static final int BULK_MAX_BUSES = 64;

    /**
     * <b>FTB Ultimine 连锁粘贴</b>一次最多改写的台数。
     * <p><b>为什么直接复用 {@link #BULK_MAX_BUSES} 这个值</b>：两条批量路径都是「一次手势写一批总线配置」，
     * 上限没有理由凭空分成两个数字；而「只改一处」比「两处都记得改」可靠得多 ——
     * 这里引用常量而不是再写一个 64，将来调整整簇上限时连锁粘贴会一起跟上。
     * 实际生效上限还取 FTB 的 {@code ShapeContext#maxBlocks()}（玩家 ranks / 属性生效值）的较小者。</p>
     */
    public static final int ULTIMINE_MAX_BUSES = BULK_MAX_BUSES;

    /**
     * 连锁粘贴允许的最远距离（格，按方块中心算，平方比较）。
     * <p>普通交互距离只有 4~5 格，但 Ultimine 的连锁形状本来就可能伸到视线外一小段；32 格足以覆盖
     * 「框住一整排 / 一整面总线」的常规用法，同时保证玩家不可能隔着半张地图改写别人的总线。
     * 候选坐标不带维度信息，本模组只在玩家当前 {@link ServerLevel} 里解析 —— 跨维度在结构上不可能发生。</p>
     */
    public static final double ULTIMINE_MAX_DISTANCE = 32.0D;

    /** 已复制：{@code %s} = 方块名。 */
    private static final String MSG_COPIED = "message.rs_create_compat.bus_clipboard.copied";
    /** 已粘贴到一条总线：{@code %s} = 方块名。 */
    private static final String MSG_PASTED = "message.rs_create_compat.bus_clipboard.pasted";
    /** 已粘贴到多条总线：{@code %s} = 台数。 */
    private static final String MSG_PASTED_MANY = "message.rs_create_compat.bus_clipboard.pasted_many";
    /**
     * 连锁粘贴的一句结果：成功 / 跳过 / 类型不符 三个计数。
     * <p>参数顺序固定为 {@code applied, skipped, mismatched}，中文与英文都按同一顺序放占位符 ——
     * 文案里不解释每个数字的成因（那是代码注释的事），玩家只需要一眼看清这次到底改了什么。</p>
     */
    private static final String MSG_CHAIN_PASTED = "message.rs_create_compat.bus_clipboard.pasted_ultimine";
    /**
     * <b>连锁右键但剪贴板里没有本模组配置</b>（用户反馈里最常见的一种「按了没反应」）。
     * <p>{@code %s} = 方块名。这条路径<b>不是静默返回</b>：先把命中的这一条复制进剪贴板（保住
     * 「第一次右击先复制」这个直觉），再明确告诉玩家「按住连锁键再右击一次就是整片粘贴」。</p>
     */
    private static final String MSG_NO_PAYLOAD_COPIED =
        "message.rs_create_compat.bus_clipboard.no_payload_copied";
    /** 冒险模式 / 无权修改：{@code %s} 不用占位符。 */
    private static final String MSG_CANNOT_BUILD = "message.rs_create_compat.bus_clipboard.cannot_build";
    /** 连锁范围内只有命中这一条（没有同类总线）：不是失败，但必须说清楚「为什么没有整片」。 */
    private static final String MSG_CHAIN_NO_SIBLINGS =
        "message.rs_create_compat.bus_clipboard.pasted_ultimine_no_siblings";
    /** 连锁粘贴一条都没写进去：{@code %s} = 跳过条数，{@code %s} = 类型不符条数。 */
    private static final String MSG_CHAIN_NONE = "message.rs_create_compat.bus_clipboard.pasted_ultimine_none";
    /** 种类不一致（剪贴板里是另一种总线的配置）。 */
    private static final String MSG_KIND_MISMATCH = "message.rs_create_compat.bus_clipboard.kind_mismatch";
    /** 校验通过但写入没成功（方块实体已不是那条总线：极罕见的状态错位）。 */
    private static final String MSG_FAILED = "message.rs_create_compat.bus_clipboard.failed";

    /**
     * 已经被告知过「连锁粘贴不可用」的玩家（进程内）。
     * <p>为什么只报一次：这句话是<b>环境诊断</b>（没装 FTB / 反射注册失败），不是每次操作的结果；
     * 每复制一次就刷一遍会变成噪音。只在服务端线程读写，用并发集合只是为了不依赖「单线程」这个假设。</p>
     */
    private static final Set<java.util.UUID> ULTIMINE_NOTICE_SENT =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // 【保底｜不依赖 FTB Ultimine 的整簇粘贴】
    //
    // 用户实机：开了 devLogs、按了连锁键，日志里连一条 [rscc-chain-bus] 都没有
    // ⇒ 最可能是 FTB 压根没把右键派发到我们（它那侧的闸先挡掉了）。
    // 与其等 FTB 修，不如给一个**完全不经过 FTB** 的手势：
    //   手持剪贴板（且剪贴板里有本模组配置）+ 潜行 + 右击总线 = 一次整簇粘贴。
    // 复用既有 pasteCluster / RsccWireLinkSearch，边界（同一线缆簇 / 同种总线 / 上限 /
    // 不跨维度）一个字都不改 —— 新组合只是一条新的入口，不是第二套语义。
    //
    // 为什么默认开启：用户的原问题是「连锁按了没反应」，而保底若默认关，用户仍然要先去
    // 找开关（他能看到的只有「还是没反应」）。开启后也只有非常窄的组合会命中（见
    // sneakClusterPaste 的四道前置），误触概率极低；不想要时一条指令即可关掉。
    // ------------------------------------------------------------------

    /** 保底整簇粘贴（潜行 + 右键）是否启用；默认 {@code true}。 */
    private static volatile boolean sneakFallbackEnabled = true;

    /** 保底是否已经真的生效过一次（只服务日志：证明这条路径是活的）。 */
    private static final AtomicBoolean SNEAK_FALLBACK_USED = new AtomicBoolean();

    /** 保底整簇粘贴当前是否启用（诊断指令 / 自检用）。 */
    public static boolean sneakFallbackEnabled() {
        return sneakFallbackEnabled;
    }

    /**
     * 开关保底整簇粘贴（运行时，随进程；由 {@code /rs_create_compat chain-debug fallback on|off} 调用）。
     *
     * <p>只改这一条新入口的开关：既有的「右击复制 / 左击粘贴 / 潜行左击整簇」<b>一个字都不受影响</b>。</p>
     */
    public static void setSneakFallbackEnabled(final boolean enable) {
        sneakFallbackEnabled = enable;
        // 必要日志（INFO，不受 devLogs 控制）：用户必须能在日志里看到「保底被关了」这件事
        LOGGER.info("[rscc-ultimine] 保底整簇粘贴（手持剪贴板 + 潜行 + 右键）已{}", enable ? "开启" : "关闭");
    }

    // ------------------------------------------------------------------
    // 第 83 轮：连锁链路的观测点（受 devLogs 控制 + 1 秒窗口节流）
    //
    // 上一轮给「玩家看得见的失败」都补了文案，但真正难查的是「FTB 压根没派发到我们」这种
    // **无痕**情形：玩家按住连锁键右击总线，看到的却是「复制成功」（甚至什么都没有）。
    // 这里的日志与 RsccUltimineIntegration 的 [rscc-chain] 同前缀，一条 grep 就能把
    // 「FTB 有没有派发 / 我们拿到了几条候选 / 每条为什么被跳过 / 最终写了几条」串起来。
    // ------------------------------------------------------------------
    private static final Logger LOGGER = LoggerFactory.getLogger(RsccBusClipboard.class);

    /** 连锁观测日志的节流窗口（毫秒）。 */
    private static final long CHAIN_LOG_WINDOW_MS = 1000L;

    /** 上一次连锁观测日志的时刻（服务端主线程读写）。 */
    private static long lastChainLogAt;

    /** 当前窗口内被节流压掉的条数。 */
    private static int chainLogSuppressed;

    /**
     * 「剪贴板连锁的第一次判定」是否已经<b>无条件</b>打过日志（与本模组 {@code devLogs} 总开关无关）。
     * <p><b>为什么单独要这一条</b>：用户实机报「配置连锁还是不行」时，日志里最需要回答的问题是
     * 「这次右键到底有没有走到总线分支、卡在哪一道闸」。上一轮的 {@code chainObserve} 全部受
     * {@code devLogs} 控制 —— 玩家不开开关时日志里仍然一片空白，等于没有观测点。
     * 这一条<b>每人每进程只打一次</b>，不可能刷屏，却能把「用哪只手 / 是不是潜行 / 命中是不是总线 /
     * 剪贴板里有没有本模组载荷 / FTB 给了几条候选 / 上限多少」一次说清。</p>
     */
    private static final AtomicBoolean CHAIN_FIRST_DECISION = new AtomicBoolean();

    /**
     * 「左键第一判定」是否已分别在<b>客户端 / 服务端</b>无条件打过一条（与 {@code devLogs} 无关）。
     * <p>两端各一个位：客户端那条证明「事件真的送到了客户端」，服务端那条证明
     * 「START_DESTROY_BLOCK 包真的到了服务端」—— 用户报「左键没反应」时，这两条缺哪一条
     * 就直接指向断点，不需要再猜。</p>
     */
    private static final AtomicBoolean LEFT_CLIENT_DECISION = new AtomicBoolean();
    private static final AtomicBoolean LEFT_SERVER_DECISION = new AtomicBoolean();

    /**
     * 连锁观测日志：受 {@code devLogs} 总开关控制，并按 {@value #CHAIN_LOG_WINDOW_MS} 毫秒窗口节流
     * （被压掉的条数会在下一条上汇总，既不刷屏也不失明）。
     */
    private static void chainObserve(final String format, final Object... args) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastChainLogAt < CHAIN_LOG_WINDOW_MS) {
            chainLogSuppressed++;
            return;
        }
        lastChainLogAt = now;
        final String suffix = chainLogSuppressed == 0 ? "" : "（上一窗口压掉 " + chainLogSuppressed + " 条同类）";
        chainLogSuppressed = 0;
        LOGGER.info("[rscc-chain] " + String.format(format, args) + suffix);
    }

    // ------------------------------------------------------------------
    // 【诊断｜rscc-chain-bus】总线连锁粘贴的「断点标尺」
    //
    // 与上面的 [rscc-chain] 是两族日志：这一族的唯一职责是回答「按住连锁键右击一次，
    // 总线配置到底断在哪一步」—— ①进入 → ②形状候选 → ③线缆簇补齐（触发/未触发 + 结果完整性）
    // → ④五类跳过原因各几次 → ⑤最终写了几台。前缀固定 [rscc-chain-bus]，一条 grep 读完整条链。
    //
    // 开关：工程既有的 devLogs 总闸 RsccAssemblyDebug.isEnabled()（与 [rscc-chain] 同一个开关）。
    // 节流：按「诊断点」各自 1 秒 1 条（不是整个函数共用一个窗口）—— 一次按键要在同一毫秒里
    //      连打 6 条，共用窗口会把后面 5 条全部压掉，断点反而看不见。
    // ------------------------------------------------------------------

    /** [rscc-chain-bus] 的节流窗口（毫秒）：同一诊断点每秒最多 1 条。 */
    private static final long BUS_LOG_WINDOW_MS = 1000L;

    /** 诊断点 → 上次输出时刻（键只有本类写死的 6 个，表不会增长）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> BUS_LOG_LAST_AT =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 总线连锁诊断：<b>受 {@code devLogs} 控制 + 按诊断点 1 秒节流</b>，前缀统一 {@code [rscc-chain-bus]}。
     *
     * @param site 诊断点（同时是节流键；同一函数里的不同诊断点互不挤压）
     */
    private static void busChainLog(final String site, final String format, final Object... args) {
        if (!RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = BUS_LOG_LAST_AT.get(site);
        if (last != null && now - last < BUS_LOG_WINDOW_MS) {
            return;
        }
        BUS_LOG_LAST_AT.put(site, now);
        LOGGER.info("[rscc-chain-bus] " + site + " " + String.format(format, args));
    }

    /**
     * FTB 认为「连锁键是否按住」：{@code TRUE} / {@code FALSE} / {@code null}（读不到）。
     * <p>这是把「它那侧的闸」与「我们这侧的闸」分开的唯一手段：本模组的连锁处理器<b>只会在
     * FTB 认为键按住时</b>被调用（{@code blockRightClick} 第一道业务闸就是 {@code isPressed()}）。</p>
     */
    @Nullable
    private static Object ftbPressed(@Nullable final Player player) {
        return player instanceof final ServerPlayer serverPlayer
            ? RsccUltimineIntegration.ultimineKeyPressed(serverPlayer)
            : null;
    }

    private RsccBusClipboard() {
    }

    /**
     * 右击 = <b>复制</b>这条总线的配置（潜行 = 保底整簇粘贴，见 {@link #sneakClusterPaste}）。
     *
     * <p><b>潜行这一档怎么变过（第 88 轮）</b>：原先是「潜行时完全不接管」，理由是与 Create 自己的
     * 剪贴板挂点同一个取舍（{@code ClipboardValueSettingsHandler#interact} 里 {@code player.isShiftKeyDown()}
     * 就返回）。现在潜行 + 右击多了一条<b>保底整簇粘贴</b>，因为用户的连锁链路可能压根没被 FTB 派发
     * （日志里连 {@code [rscc-chain-bus]} 都没有），需要一个<b>不经过 FTB</b> 的批量手势。
     * 避让做法（四道前置，缺一不接管，见 {@link #sneakClusterPaste}）：只在「手持剪贴板 +
     * 剪贴板里确实有本模组配置 + 命中格确实是本模组总线 + mayBuild」时接管；
     * 空剪贴板 / 别的方块 / 别的物品一律原样交回 —— 因此「潜行右击打开总线界面」「潜行右击别的方块」
     * 这些既有行为只在「拿着刚复制过总线配置的剪贴板去潜行右击另一条总线」这一种组合下才会变，
     * 而那正是玩家想批量粘贴的时刻。它<b>不影响</b>既有的「潜行 + 左键 = 整簇粘贴」。</p>
     *
     * <p><b>为什么优先级是 {@link EventPriority#LOW} 而不是 NORMAL</b>（与连锁粘贴的先后顺序）：
     * FTB Ultimine 的右键连锁经 Architectury 的 {@code InteractionEvent.RIGHT_CLICK_BLOCK} 分发，而那个
     * 桥接监听器是 {@code EventPriority.HIGH}（已按 {@code architectury-13.0.11-neoforge} 的字节码核对）。
     * 连锁粘贴成功时 FTB 会取消事件，本处理器排在它<b>之后</b>就能先看到 {@code isCanceled()} 直接返回；
     * 否则「按住连锁键右击」会先复制命中格、再用这份刚被覆盖的载荷去连锁粘贴 ——
     * 等于把玩家原来的剪贴板内容悄悄换掉。没按连锁键时 FTB 不取消，本处理器照旧复制，行为不变。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        // 【第 83 轮｜连锁的分界观测点】本处理器排在 FTB 的 Architectury 桥接（HIGH）**之后**，
        // 因此「事件到这里时是取消还是没取消」正好回答了一个之前无法回答的问题：
        //   取消 ⇒ 上游有人接手了这次右键（按住连锁键时，几乎必然是 FTB 的连锁处理器返回了 >0）；
        //   没取消 + 手持剪贴板 + 目标是总线 ⇒ **FTB 根本没有派发到我们的连锁处理器**。
        // 后者以前是完全无痕的（玩家只会看到「复制成功」或什么都没有），现在有日志 + 有键状态探针。
        if (event.isCanceled()) {
            if (event.getLevel() instanceof final ServerLevel serverLevel
                && AllBlocks.CLIPBOARD.isIn(event.getItemStack())) {
                chainObserve("右键事件已被上游取消（手持剪贴板）：若本次按住了连锁键，说明 FTB 连锁已接手；"
                    + "FTB 认为连锁键按住=%s", ftbPressed(event.getEntity()));
            }
            return; // 已经被更外层的处理（伪装框架 / 分隔框架 / 扳手断缝）结算：一次右键只做一件事
        }
        final Player player = event.getEntity();
        if (player == null) {
            return;
        }
        if (player.isShiftKeyDown()) {
            // 【保底｜不依赖 FTB】潜行 + 右键 = 整簇粘贴（见 sneakClusterPaste）。
            // 为什么仍然先看 isCanceled()：上面已经 return 了（上游接手 ⇒ 一次右键只做一件事）。
            // 既有语义「潜行 + 左键 = 整簇粘贴」在 onLeftClickBlock 里一字未动；
            // 新组合走的是另一只键，因此两者不重叠，也不需要玩家改习惯。
            sneakClusterPaste(event, player);
            return;
        }
        final ItemStack clipboard = event.getItemStack();
        if (!AllBlocks.CLIPBOARD.isIn(clipboard)) {
            return;
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final String kind = kindAt(level, pos);
        if (kind == null) {
            return; // 不是输入 / 输出总线：连事件都不取消，让 Create / RS / 原版照旧处理
        }
        if (level instanceof final ServerLevel serverLevel) {
            // 手持剪贴板 + 目标是本模组总线 + 事件**没被取消** ⇒ FTB 没派发连锁，这次右键走到的是
            // 本模组的「复制」路径。玩家如果以为自己在做连锁粘贴，就会觉得「连锁没生效」——
            // 这条日志把「他那侧没发」与「我们这侧没写」当场分开。
            chainObserve("右键未被取消（手持剪贴板 + 目标是总线）⇒ FTB 没有派发连锁；"
                + "FTB 认为连锁键按住=%s（false/null 就是它那侧的闸），候选集合本次不可见",
                ftbPressed(player));
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(level instanceof final ServerLevel serverLevel)) {
            return; // 客户端只取消事件：物品与世界状态一律由服务端那份数据决定
        }
        if (!copyInto(serverLevel, pos, kind, clipboard)) {
            return; // 方块状态说它是总线、方块实体却不是：宁可什么都不做（绝不写一份空配置进剪贴板）
        }
        player.displayClientMessage(
            Component.translatable(MSG_COPIED, nameOf(level, pos)), true);
        // 顺手做一次「连锁粘贴可用吗」的环境诊断（每人每局只说一次）：
        // 玩家复制完就想按住连锁键去批量粘贴，这时候告诉他「装了没 / 联动生效没」最有用。
        if (player instanceof final ServerPlayer serverPlayer) {
            notifyUltimineAvailabilityOnce(serverPlayer);
        }
    }

    /**
     * <b>保底整簇粘贴</b>（第 88 轮）：手持剪贴板 + <b>潜行 + 右击</b>总线 ⇒ 一次整簇粘贴，
     * <b>完全不经过 FTB Ultimine</b>。
     *
     * <h2>为什么需要这条新入口</h2>
     * <p>用户实机：开了 {@code devLogs}、按住连锁键右击，{@code latest.log} 里<b>连一条
     * {@code [rscc-chain-bus]} 都没有</b>。那说明 FTB 在它自己的 {@code blockRightClick} 第一道闸
     * （{@code FTBUltiminePlayerData#isPressed()}）或形状派发处就没把这次右键交给我们 ——
     * 我们这侧再怎么写都轮不到。于是需要一条<b>只依赖原版右键事件</b>的批量入口。</p>
     *
     * <h2>为什么不复用「潜行 + 左键整簇」（那条语义原样保留）</h2>
     * <p>{@link #onLeftClickBlock} 里的 {@code player.isShiftKeyDown() ? pasteCluster(...)} 一字未改，
     * 它仍是「潜行 + 左键 = 整簇粘贴」，本方法只是另一只键上的<b>同一套</b>实现
     * （{@link #pasteCluster} → {@link RsccWireLinkSearch#searchChamberLink}，同一条线缆簇 /
     * 同种总线 / {@link #BULK_MAX_BUSES} 上限 / 不跨维度）。
     * 两者不冲突：左键 = 挖方块那一侧，右键 = 使用那一侧，玩家一次只按一只键。</p>
     *
     * <h2>与既有右键语义怎么避让（四道前置，缺一不接管）</h2>
     * <ol>
     *     <li>手里必须是 Create 剪贴板（否则原样交回：别的物品的潜行右键不受影响）；</li>
     *     <li>剪贴板里<b>确实有</b>本模组的总线配置（空剪贴板不接管 ⇒ 「潜行右击总线」照旧打开总线界面）；</li>
     *     <li>命中格必须是本模组的总线（别的方块照旧）；</li>
     *     <li>{@code player.mayBuild()}（无权限时不取消事件、也不写，交给原版处理）。</li>
     * </ol>
     * <p>另有上游取消检查：{@code event.isCanceled()} 的路径在调用点之前就 return 了
     * （FTB 连锁 / 框架 / 扳手已经结算过这次右键），所以不会出现「连锁粘一遍、保底再粘一遍」。</p>
     *
     * <p><b>服务端权威</b>：两端都只取消事件（阻止总线界面弹出），真正的读剪贴板 / 校验 / 写入
     * 只在 {@code ServerLevel} 分支内（与既有粘贴路径完全一致）。</p>
     */
    private static void sneakClusterPaste(final PlayerInteractEvent.RightClickBlock event, final Player player) {
        if (!sneakFallbackEnabled) {
            return; // 被指令关掉：潜行右键回到「不接管」
        }
        final ItemStack clipboard = event.getItemStack();
        if (!AllBlocks.CLIPBOARD.isIn(clipboard)) {
            return; // 前置 1：不是剪贴板 —— 别的物品的潜行右键照旧
        }
        final CompoundTag payload = payloadOf(clipboard);
        if (payload == null) {
            return; // 前置 2：空剪贴板 —— 照旧打开总线界面（不抢「潜行右击」这条既有路径）
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final String kind = kindAt(level, pos);
        if (kind == null) {
            return; // 前置 3：不是本模组总线 —— 交回原版 / RS / Create
        }
        if (!player.mayBuild()) {
            if (level instanceof final ServerLevel serverLevel) {
                player.displayClientMessage(Component.translatable(MSG_CANNOT_BUILD), true);
            }
            return; // 前置 4：无权限 —— 不取消事件，行为与「没接管」一致
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(level instanceof final ServerLevel serverLevel)) {
            return; // 客户端只取消事件：写入只在服务端
        }
        if (!RsccBusConfig.acceptsKind(payload, kind)) {
            // 与单台 / 连锁粘贴同一条闸门：种类或版本不符时一个字都不写
            player.displayClientMessage(Component.translatable(MSG_KIND_MISMATCH), true);
            return;
        }
        final int applied = pasteCluster(serverLevel, pos, kind, payload);
        if (applied <= 0) {
            player.displayClientMessage(Component.translatable(MSG_FAILED), true);
            return;
        }
        player.displayClientMessage(applied == 1
            ? Component.translatable(MSG_PASTED, nameOf(level, pos))
            : Component.translatable(MSG_PASTED_MANY, applied), true);
        logSneakFallbackUsedOnce(player, applied);
    }

    /**
     * 保底整簇粘贴第一次真的写进去时，无条件打一条 INFO（不受 {@code devLogs} 控制）。
     * <p>为什么要它：这条路径是「不依赖 FTB」的兜底，用户需要在日志里看到它确实接住了操作 ——
     * 否则「保底生效了」与「连锁生效了」在事后无法区分，回归时又会回到猜的状态。
     * 每进程只打一次，不刷屏。</p>
     */
    private static void logSneakFallbackUsedOnce(final Player player, final int applied) {
        if (SNEAK_FALLBACK_USED.compareAndSet(false, true)) {
            LOGGER.info("[rscc-ultimine] 保底整簇粘贴生效（潜行+右键，不经过 FTB）：写入 {} 台，玩家={}，"
                + "锚点簇由 RsccWireLinkSearch 给出", applied, player.getName().getString());
        }
    }

    /**
     * 保底 / 剪贴板载荷的<b>只读诊断</b>（{@code /rs_create_compat chain-debug} 的数据源）。
     * <p>配合 {@link RsccUltimineIntegration#debugReport}：那边回答「FTB 那条链路通不通」，
     * 这边回答「不经过 FTB 时手里有没有可用的东西」—— 两条合起来，用户一条指令就知道该按哪种手势。</p>
     */
    public static List<String> debugReport(@Nullable final ServerPlayer player) {
        final List<String> lines = new java.util.ArrayList<>();
        lines.add("保底整簇粘贴（手持剪贴板 + 潜行 + 右键）: "
            + (sneakFallbackEnabled() ? "已开启（默认）" : "已关闭"));
        if (player == null) {
            lines.add("手持剪贴板: 无执行者玩家（控制台 / 命令方块），读不到");
            lines.add("切换保底: /rs_create_compat chain-debug fallback on|off");
            return lines;
        }
        final ItemStack main = player.getMainHandItem();
        final ItemStack off = player.getOffhandItem();
        final boolean holdingClipboard = AllBlocks.CLIPBOARD.isIn(main) || AllBlocks.CLIPBOARD.isIn(off);
        final CompoundTag payload = AllBlocks.CLIPBOARD.isIn(main) ? payloadOf(main)
            : AllBlocks.CLIPBOARD.isIn(off) ? payloadOf(off) : null;
        lines.add("手持剪贴板: " + (holdingClipboard ? "是" : "否")
            + "；剪贴板里有本模组配置: " + (payload == null ? "否（先右击一条总线复制）"
                : "是（种类=" + payload.getString(RsccBusConfig.KEY_KIND) + "）"));
        lines.add("切换保底: /rs_create_compat chain-debug fallback on|off");
        return lines;
    }

    /**
     * 左击 = <b>粘贴</b>（潜行 = 整簇粘贴；按住连锁键 = 连锁粘贴）。
     *
     * <h2>第 95 轮：为什么实机「左键不行、右键却正常」（已按字节码定位，不是猜）</h2>
     * <p>用户原话：「用右键应用能正常，用左键就不行；明明本来就该是左键的」。逐条核对的结果：</p>
     * <ol>
     *     <li><b>「客户端 setCanceled 把 START_DESTROY_BLOCK 包吞掉，服务端永远收不到」—— 不成立。</b>
     *     {@code MultiPlayerGameMode#startDestroyBlock} 的字节码是：先
     *     {@code CommonHooks.onLeftClickBlock(...)}（触发本事件），再
     *     {@code startPrediction(lambda)} → 该 lambda 的<b>两个出口都 {@code return} 同一个
     *     {@code ServerboundPlayerActionPacket(START_DESTROY_BLOCK)}</b>，而
     *     {@code startPrediction} 无条件 {@code connection.send(packet)} —— <b>没有任何一处读
     *     {@code isCanceled()}</b>。服务端 {@code ServerPlayerGameMode#handleBlockBreakAction}
     *     也是<b>第一句</b>就 fire 本事件、取消才 return。所以两端都会收到，取消只影响「别挖方块」。</li>
     *     <li><b>副手剪贴板 —— 成立，是真缺陷。</b>
     *     {@code PlayerInteractEvent.LeftClickBlock} 的构造函数把 hand <b>写死成
     *     {@code InteractionHand.MAIN_HAND}</b>，而 {@code getItemStack()} 就是
     *     {@code player.getItemInHand(那个 hand)}（两条都见该类的 {@code javap -c}）。
     *     也就是说：左键事件<b>在结构上永远看不到副手</b>。旧实现直接读
     *     {@code event.getItemStack()}，于是「副手拿剪贴板 + 左键粘贴」必然一声不响地什么都不做
     *     —— 而右键事件带的是<b>真实那只手</b>（{@code RightClickBlock} 的 ctor 收 hand 参数），
     *     所以右手能粘、左手不能。现在两处都改成<b>先主手、再副手</b>自己解析
     *     （见 {@link #clipboardHand(Player)}）。</li>
     *     <li><b>FTB 连锁键 —— 成立，是手势语义缺口。</b>FTB Ultimine 对外<b>只有右键</b>扩展点
     *     （整包字节码核对：{@code RightClickHandler} 是唯一派发口，没有左键派发）。
     *     旧实现里「按住连锁键 + 左键」只会写命中那一台，连锁圈里的其余总线一台都写不到 ——
     *     玩家看到的就是「右键连锁能粘、左键不行」。现在左键这条入口自己读 FTB 已经算好的
     *     {@code cachedPositions()}（{@link RsccUltimineIntegration#chainPositions}），
     *     候选集与右键链路<b>同源同口径</b>。</li>
     *     <li><b>别的模组先取消 —— 已逐条排除</b>：Create 的 {@code ClipboardValueSettingsHandler}
     *     在 {@code instanceof SmartBlockEntity} 那一句就返回（RS 总线不是它），不会取消；
     *     FTB 整个 jar 没有 {@code LeftClickBlock} 引用；本机整合包内其余命中该事件的处理器
     *     （AFN 安全 / 小机器人 / 灭火器 / 收纳袋…）都先按<b>自己那类目标或物品</b>过滤，
     *     RS 总线不在其中；Architectury 只做桥接、不取消。</li>
     * </ol>
     *
     * <h2>为什么必须在两端都取消</h2>
     * <p>左击在原版里的默认语义是「开始挖这个方块」。两端一致取消才是干净的「这一下是粘贴，不是挖」。
     * NeoForge 21.1 的 {@code LeftClickBlock#setCanceled(true)} 顺带把 {@code useBlock / useItem}
     * 置成 {@code FALSE}（字节码），因此客户端也不会先把方块挖裂 —— 只取消、不改任何世界状态。</p>
     *
     * <h2>为什么不接管「剪贴板上没有本模组配置」的情形</h2>
     * <p>那样玩家拿着剪贴板挖总线会被无端拦住 —— 只有确实复制过总线配置（载荷存在且能通过校验）
     * 时才接管。空剪贴板 / 别的方块 / 别的物品一律照常挖。</p>
     *
     * <h2>诊断</h2>
     * <p>每侧进程内第一条「手持剪贴板 + 目标相关」的左键各打一条
     * {@code [rscc-chain] 左键·客户端 / 左键·服务端}，把<b>事件报的手 / 真实拿着剪贴板的手 /
     * 载荷有无 / 目标种类 / 最终应用数</b>放在同一行 —— 下次「左键没反应」能一眼分清是
     * 客户端没收到、服务端没收到、收到但没载荷，还是收到且写了但玩家没看见。</p>
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event) {
        final Player player = event.getEntity();
        if (event.isCanceled()) {
            // 上游（LOWEST / LOW 优先级的处理器）已经取消了这次左键：本模组什么都做不了。
            // 这是「别的模组先取消」这一假设的唯一观测点 —— 只在手持剪贴板时记一条
            // （devLogs 门 + 1 秒节流），于是「左键没反应」到底断在「上游取消」还是
            // 「我们没接管」在日志里能当场分开，不必再猜。
            if (player != null && clipboardHand(player) != null) {
                chainObserve("左键事件在到达本模组之前已被上游取消（手持剪贴板）⇒ 本次不粘贴；"
                    + "若日志里只有这一条、没有「左键·服务端」那条，说明左键被别的处理器抢走了");
            }
            return;
        }
        if (player == null) {
            return;
        }
        // NeoForge 21.1 的 LeftClickBlock 只带 MAIN_HAND（ctor 写死）：事件给的那只手永远只是主手，
        // 因此这里必须自己把副手也查一遍，不能信 event.getItemStack()。
        final ClipboardHand found = clipboardHand(player);
        if (found == null) {
            return; // 两只手都不是剪贴板：与「批量配置」无关，照常挖
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final String kind = kindAt(level, pos);
        final boolean clientSide = level.isClientSide();
        leftClickDiagnoseOnce(clientSide, player, event, found, kind);
        if (kind == null || found.payload() == null) {
            // 不是本模组总线 / 剪贴板上没有本模组的配置：不接管（照常挖方块）
            return;
        }
        final CompoundTag payload = found.payload();
        // ⚠ 先判「种类是否匹配」，**再**取消事件（第 95 轮交付前复核 P2）：
        // 旧顺序是「先 setCanceled(true) → 客户端直接 return → 服务端才判 acceptsKind」，
        // 于是种类/版本不符时客户端把这次左键吞掉（挖不动方块）、服务端又拒绝执行 ⇒
        // 玩家看到的是「点了完全没反应」。现在两侧用同一个判据：不匹配就谁都不接管。
        if (!RsccBusConfig.acceptsKind(payload, kind)) {
            if (!clientSide && player instanceof final ServerPlayer serverPlayer) {
                // 校验不过就一个字都不写：这是「不要误改到不相关的总线」那条硬要求的落点
                serverPlayer.displayClientMessage(Component.translatable(MSG_KIND_MISMATCH), true);
            }
            return;
        }
        event.setCanceled(true); // 顺带把 useBlock / useItem 置 FALSE：客户端不会本地挖裂方块
        if (clientSide) {
            return; // 客户端只取消事件：读写一律落在服务端那份数据上
        }
        if (!(level instanceof final ServerLevel serverLevel)) {
            return;
        }
        // 按住 FTB 连锁键 + 不潜行 ⇒ 连锁粘贴（候选集由 FTB 给出，与右键链路同源）。
        // 读不到形状（没装 / 探针失败 / 形状还没算出来）⇒ 退回原有语义，绝不静默失效。
        final List<BlockPos> chain = !player.isShiftKeyDown() && Boolean.TRUE.equals(ftbPressed(player))
            && player instanceof final ServerPlayer serverPlayer
            ? RsccUltimineIntegration.chainPositions(serverPlayer)
            : null;
        final int applied;
        final String mode;
        if (player.isShiftKeyDown()) {
            mode = "cluster";
            applied = pasteCluster(serverLevel, pos, kind, payload);
        } else if (chain != null && player instanceof final ServerPlayer serverPlayer) {
            mode = "chain";
            // chainPaste 自己负责全部玩家文案（成功 / 只改到一台 / 一条没写），这里不再重复提示
            applied = chainPaste(serverPlayer, found.hand(), pos, chain, ULTIMINE_MAX_BUSES);
        } else {
            mode = "single";
            applied = pasteOne(serverLevel, pos, kind, payload) ? 1 : 0;
        }
        leftPasteResult(player, level, pos, kind, found, mode, chain == null ? -1 : chain.size(), applied);
        if ("chain".equals(mode)) {
            return; // 已经在 chainPaste 里给过文案
        }
        if (applied <= 0) {
            player.displayClientMessage(Component.translatable(MSG_FAILED), true);
            return;
        }
        player.displayClientMessage(applied == 1
            ? Component.translatable(MSG_PASTED, nameOf(level, pos))
            : Component.translatable(MSG_PASTED_MANY, applied), true);
    }

    // ------------------------------------------------------------------
    // 左键粘贴：手解析 + 诊断（第 95 轮）
    // ------------------------------------------------------------------

    /**
     * 「哪只手里有剪贴板」的唯一解析入口。
     *
     * <p><b>为什么必须是两只手</b>：{@code PlayerInteractEvent.LeftClickBlock} 的构造函数把 hand
     * 写死成 {@code MAIN_HAND}（见 {@code javap -c
     * 'net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$LeftClickBlock'}），
     * 而基类 {@code getItemStack()} 就是 {@code player.getItemInHand(hand)} ——
     * <b>左键事件在结构上永远看不到副手</b>。只认主手，就会让「副手拿剪贴板 + 左键」永远静默无操作，
     * 而右键（事件带真实那只手）却正常 —— 正是用户报的「左键不行、右键可以」。</p>
     *
     * <p>优先级：<b>有载荷的主手 &gt; 有载荷的副手 &gt; 空载荷主手 &gt; 空载荷副手</b> ——
     * 先按「能不能真粘」选，再按「玩家更可能指的是哪只手」选，避免「副手刚好有配置、
     * 主手是空剪贴板」时反而粘不了。</p>
     */
    @Nullable
    private static ClipboardHand clipboardHand(final Player player) {
        final ItemStack main = player.getMainHandItem();
        final ItemStack off = player.getOffhandItem();
        final boolean mainIsClipboard = AllBlocks.CLIPBOARD.isIn(main);
        final boolean offIsClipboard = AllBlocks.CLIPBOARD.isIn(off);
        if (mainIsClipboard) {
            final CompoundTag payload = payloadOf(main);
            if (payload != null || !offIsClipboard) {
                return new ClipboardHand(InteractionHand.MAIN_HAND, main, payload);
            }
        }
        if (offIsClipboard) {
            return new ClipboardHand(InteractionHand.OFF_HAND, off, payloadOf(off));
        }
        return null;
    }

    /**
     * 一次左键粘贴的「手 + 物品 + 载荷」。
     * <p>{@code payload == null} 表示手里是剪贴板但里面没有本模组的配置（= 照常挖方块）。</p>
     */
    private record ClipboardHand(InteractionHand hand, ItemStack stack, @Nullable CompoundTag payload) {
    }

    /**
     * 左键诊断的<b>第一判定</b>：每侧（客户端 / 服务端）进程内各打一条 INFO，不受 {@code devLogs} 控制。
     *
     * <p>为什么只打第一条：它是「这条链路的两端到底通不通」的静态前提，不是每次操作的结果。
     * 一行里同时给出<b>事件报的手</b>与<b>真实拿着剪贴板的手</b>，于是「API 把副手吞了」
     * 这类问题在日志里就能被直接读出来，不需要复现第二次。</p>
     */
    private static void leftClickDiagnoseOnce(final boolean clientSide, final Player player,
                                              final PlayerInteractEvent.LeftClickBlock event,
                                              final ClipboardHand found, @Nullable final String kind) {
        if (clientSide ? !LEFT_CLIENT_DECISION.compareAndSet(false, true)
            : !LEFT_SERVER_DECISION.compareAndSet(false, true)) {
            return;
        }
        // 结论必须与真实分支一致：载荷缺失 / 目标不是总线时我们**不接管**（照常挖方块），
        // 这时候如果日志还写「已取消」就是新的「陈述与代码不符」。
        final boolean takeOver = kind != null && found.payload() != null;
        LOGGER.info("[rscc-chain] 左键·{}：事件已收到（hand={} 事件报的物品={}） 真实拿着剪贴板的手={} "
                + "载荷={} 目标={} 潜行={} 连锁键={} ⇒ {}",
            clientSide ? "客户端" : "服务端",
            event.getHand(), RsccAssemblyDebug.itemId(event.getItemStack().getItem()),
            found.hand(), found.payload() == null ? "无" : "有",
            kind == null ? "非本模组总线" : kind,
            player.isShiftKeyDown() ? "是" : "否",
            ftbPressed(player),
            !takeOver ? "不接管（照常挖方块）"
                : clientSide ? "取消这次挖掘并交给服务端" : "在服务端执行粘贴");
    }

    /**
     * 左键粘贴的<b>结果</b>一条（受 {@code devLogs} 控制 + 1 秒窗口节流，与 {@link #chainObserve} 同一套闸）。
     * <p>{@code candidates < 0} = 这次不是连锁模式（没有 FTB 候选集）。</p>
     */
    private static void leftPasteResult(final Player player, final Level level, final BlockPos pos,
                                        final String kind, final ClipboardHand found, final String mode,
                                        final int candidates, final int applied) {
        chainObserve("左键粘贴：手=%s 目标=%s(%s) 模式=%s 候选=%s 应用=%d",
            found.hand(), RsccAssemblyDebug.itemId(level.getBlockState(pos).getBlock().asItem()),
            kind, mode, candidates < 0 ? "-" : Integer.toString(candidates), applied);
    }

    // ------------------------------------------------------------------
    // FTB Ultimine 连锁粘贴
    // ------------------------------------------------------------------

    /**
     * <b>连锁粘贴</b>（FTB Ultimine 连锁；右键回调 + 左键自读形状）：把剪贴板里的总线配置写到<b>连锁圈中的同种总线</b>上。
     *
     * <h2>触发方式（与既有手势互不重叠）</h2>
     * <p>两个入口都转进本方法，候选集合<b>同源</b>（都是 FTB 那份 {@code cachedPositions()}）：</p>
     * <ul>
     *     <li>FTB 的<b>右键</b>连锁回调（{@link RsccUltimineIntegration} 的 {@code RightClickHandler}）
     *     —— 按住连锁键 + 右击；</li>
     *     <li>本模组自己的<b>左键</b>入口（{@link #onLeftClickBlock}）—— 按住连锁键 + 左击。
     *     第 95 轮新增：Create 剪贴板的原始粘贴手势是左键，而 FTB 只对外提供右键扩展点，
     *     因此左键这条自己读 FTB 已算好的候选（{@link RsccUltimineIntegration#chainPositions}）。</li>
     * </ul>
     * <p>因此完整手势表：</p>
     * <ul>
     *     <li><b>普通右击</b> = 复制配置（{@link #onRightClickBlock}，不潜行）；</li>
     *     <li><b>连锁键 + 右击 / 连锁键 + 左击</b> = 连锁粘贴（本方法）；</li>
     *     <li><b>左击</b> = 单台粘贴、<b>潜行 + 左击</b> = 一条线缆簇整簇粘贴
     *     （{@link #onLeftClickBlock}）。左键在 FTB 里本是「连锁挖掘」，本模组接管后<b>两端都取消</b>
     *     这次挖掘（客户端连挖掘预测都不做），所以「批量配置」不会被误当成挖方块。</li>
     * </ul>
     * <p><b>为什么潜行时不接管</b>：与 {@link #onRightClickBlock} 同一条取舍 —— 潜行是别的模组 /
     * Create 剪贴板自己的「精确档」，本模组不抢。玩家潜行走连锁右键时这里返回 0，交回 FTB / 原版。</p>
     *
     * <h2>边界与安全（逐条落在实现里）</h2>
     * <ul>
     *     <li><b>服务端权威</b>：只接受 {@link ServerPlayer}，全部读写都在 {@code player.serverLevel()} 内；
     *     不新增任何网络包，客户端也没有「直接改方块实体」的路径可走；</li>
     *     <li><b>只写同种总线</b>：以命中格的总线种类为准（它同时必须通过
     *     {@link RsccBusConfig#acceptsKind} 的版本 / 种类校验）。连锁圈里另一种总线只计数
     *     （「类型不符」），<b>一个字节都不写</b>；</li>
     *     <li><b>去重</b>：{@link LinkedHashSet} 保序去重 —— FTB 的候选集合里重复坐标只会被处理一次；</li>
     *     <li><b>上限</b>：{@code min(FTB maxBlocks, ULTIMINE_MAX_BUSES)}；超出上限的候选计入「跳过」，
     *     不会悄悄消失；</li>
     *     <li><b>距离 / 权限</b>：{@code player.mayBuild()} + 逐块复检 {@link #ULTIMINE_MAX_DISTANCE}；
     *     只在一个 {@link ServerLevel} 里解析坐标，跨维度在结构上不可能；</li>
     *     <li><b>逐块容错</b>：单块写入包在 try/catch 里，异常只让这一块计入「跳过」，
     *     整批继续、异常绝不冒回 FTB 的事件链；</li>
     *     <li><b>一遍写入</b>：每条总线只调用一次 {@link #pasteOne}（同一份载荷对象直接传下去，
     *     本方法不做「先试写再补写」的第二遍，也不逐块发包）。</li>
     * </ul>
     *
     * <h2>「不同网络」的总线照常粘贴</h2>
     * <p>配置是<b>逐块</b>的：过滤槽 / 类别 / 开关全部写在每台总线自己的方块实体上，没有「跨网络写一份」
     * 这回事；类别 id 由各自关联的执行仓按它自己的可达类别解释。因此连锁圈跨到另一张 RS 网络也不会
     * 污染别人 —— 真正需要拒绝的只有「种类不符」。</p>
     *
     * <h2>反馈（第 82 轮的硬要求：<b>任何一条「什么都没发生」都必须有话说</b>）</h2>
     * <p>用户实机反馈「第 12 点的连锁也不行」—— 排查发现这条链路的早期版本全是<b>静默 return 0</b>：
     * 剪贴板里没有配置 / 没有权限 / 范围内没有同类总线 / 一条都没写进去，玩家看到的都是「按了没反应」。
     * 现在逐条对应：</p>
     * <ul>
     *     <li>剪贴板里没有本模组配置 ⇒ 当场把命中这一条<b>复制</b>进去，并提示「再按住连锁键点一次」
     *     （{@link #MSG_NO_PAYLOAD_COPIED}）；</li>
     *     <li>没有权限（冒险模式）⇒ {@link #MSG_CANNOT_BUILD}；</li>
     *     <li>种类 / 版本不符 ⇒ {@link #MSG_KIND_MISMATCH}（既有）；</li>
     *     <li>一条都没写进去 ⇒ {@link #MSG_CHAIN_NONE}（带跳过 / 类型不符计数）；</li>
     *     <li>连锁范围里只有命中这一条 ⇒ {@link #MSG_CHAIN_NO_SIBLINGS}
     *     （这正是「为什么没有整片粘贴」最容易被误判成「功能坏了」的一种）；</li>
     *     <li>成功且确实有多条 ⇒ {@link #MSG_CHAIN_PASTED}（成功 / 跳过 / 类型不符 三个计数）。</li>
     * </ul>
     * <p>仍然<b>必须静默</b>的只有两种情况：手里不是剪贴板（不是我们的手势），以及目标不是输入 / 输出总线
     * （玩家只是拿着剪贴板按住连锁键点了别的方块 —— 这时 FTB / 原版该照常处理）。</p>
     *
     * @return 真正写入的台数；{@code 0} = 本模组一条都没写（调用方把这次右键交回 FTB / 原版，
     *     与 {@link RsccSheathChain#chainSheath} 的返回约定一致）
     */
    public static int chainPaste(final ServerPlayer player, final InteractionHand hand, final BlockPos anchor,
                                 final Collection<BlockPos> ultimineCandidates, final int limit) {
        // 【诊断｜rscc-chain-bus·①】进入连锁粘贴：把「这次带了什么」先摊开。
        // 放在所有提前 return 之前，于是「limit<=0 / 手里不是剪贴板 / 潜行 / 命中不是总线」这些
        // 早退也能从日志里看出断在哪一道闸，而不是只有一条 [rscc-chain] 的结论。
        busChainLog("chainPaste/①进入",
            "进入连锁粘贴：hand=%s 手持=%s 潜行=%s 命中=%s 命中方块=%s 形状候选=%d 上限=%d",
            hand, RsccAssemblyDebug.itemId(player.getItemInHand(hand).getItem()),
            player.isShiftKeyDown(), anchor,
            BuiltInRegistries.BLOCK.getKey(player.serverLevel().getBlockState(anchor).getBlock()),
            ultimineCandidates.size(), limit);
        if (limit <= 0) {
            chainObserve("②chainPaste 提前返回：FTB 上限 limit=%d（<=0）⇒ 这次不连锁任何方块", limit);
            return 0; // FTB 这次不打算连锁任何方块：谈不上「粘贴失败」，不打扰玩家
        }
        final ItemStack clipboard = player.getItemInHand(hand);
        if (!AllBlocks.CLIPBOARD.isIn(clipboard)) {
            chainObserve("②chainPaste 提前返回：hand=%s 手里不是剪贴板（%s）", hand, clipboard.getItem());
            return 0; // 手里不是剪贴板：这条链路与「批量配置」无关（框架那条由上层分派）
        }
        if (player.isShiftKeyDown()) {
            chainObserve("②chainPaste 提前返回：玩家潜行 ⇒ 精确档留给原版（会打开总线界面，不是「没反应」）");
            // 潜行是既有「精确档」：本模组不抢。这里仍然静默交回 FTB / 原版 —— 与前面几条不同，
            // 这条路径**不会是「没反应」**：原版会把总线界面打开，玩家看得见这次右键被谁处理了。
            return 0;
        }
        final ServerLevel level = player.serverLevel();
        final String anchorKind = kindAt(level, anchor);
        // 【第 87 轮】载荷提前读一步：只为了让「第一次判定」那条日志把「剪贴板里到底有没有配置」
        // 一起说清（读的是物品组件上的一个子标签，代价可以忽略）。
        final CompoundTag payload = payloadOf(clipboard);
        logFirstDecision(player, hand, clipboard, anchor, anchorKind, payload, ultimineCandidates, limit);
        if (anchorKind == null) {
            // 正常路径：玩家拿着剪贴板按住连锁键点了别的方块。写一条「为什么不接手」，
            // 免得日后把「点了不该点的方块」误判成「连锁失效」。
            chainObserve("②chainPaste 提前返回：命中格 %s 不是本模组总线 ⇒ 交回 FTB / 原版", anchor);
            return 0; // 目标不是输入 / 输出总线：本模组不接手，也不抢 FTB / 原版的交互
        }
        chainObserve("②chainPaste 进入：anchor=%s kind=%s 候选=%d 上限=%d",
            anchor, anchorKind, ultimineCandidates.size(), limit);
        if (!player.mayBuild()) {
            chainObserve("③没有权限（mayBuild=false）⇒ 回「改不了」而不是静默");
            // 冒险模式 / 被限制：这不是「没配置」，而是「改不了」，必须说出来
            player.displayClientMessage(Component.translatable(MSG_CANNOT_BUILD), true);
            return 0;
        }
        if (payload == null) {
            chainObserve("③剪贴板里没有本模组配置 ⇒ 先把命中这一条复制进去，再让玩家按一次");
            // 最常见的一种「连锁没反应」：剪贴板里根本没有本模组的配置（从没复制过）。
            // 不再静默返回：先把命中的这一条复制进去，再明确告诉玩家下一步（按住连锁键再次右击）。
            if (copyInto(level, anchor, anchorKind, clipboard)) {
                player.displayClientMessage(
                    Component.translatable(MSG_NO_PAYLOAD_COPIED, nameOf(level, anchor)), true);
            } else {
                player.displayClientMessage(Component.translatable(MSG_FAILED), true);
            }
            // 返回 1：这次连锁右键已经明确做了一件事（复制），不该再被 FTB / 原版当成普通右击
            return 1;
        }
        if (!RsccBusConfig.acceptsKind(payload, anchorKind)) {
            chainObserve("③种类 / 版本不符 ⇒ 一个字都不写，直接回提示（anchorKind=%s）", anchorKind);
            // 版本不认识 / 种类不符：一个字都不写，并且明确回复玩家（与单台粘贴同一条闸门）
            player.displayClientMessage(Component.translatable(MSG_KIND_MISMATCH), true);
            // 返回 1 让 FTB 吃掉这次连锁右键（不再打开总线界面）；FTB 对返回值 1 的经验代价是 0
            return 1;
        }

        // 规划：anchor 永远第一个（它已过种类校验），其余候选去重后按 FTB 顺序收进来。
        //
        // 【第 87 轮｜为什么还要沿线缆簇补一批】FTB 的形状是「同一种方块」的洪泛
        // （ShapelessShape + MATCH_BY_BLOCK），而总线之间通常隔着线缆方块 —— 从命中那台总线
        // 出发一步都走不出去，候选里只剩它自己（见类注释的「材质能、配置不能」根因）。
        // 因此这里数一下「形状里到底给了几台同种总线」：一台都没有时，才沿同一条线缆簇补齐
        // （与潜行左击整簇粘贴同一套边界与同一趟展开；上限 / 距离 / 种类逐条复检）。
        // 形状里已经给了同种总线 ⇒ 完全按形状走，不扩展 —— 兜底只覆盖原本「一格都写不出去」的路径。
        final int cap = Math.min(limit, ULTIMINE_MAX_BUSES);
        // 【诊断｜rscc-chain-bus·②】候选数量来自 FTB 交给我们的「形状集合」（去重前），
        // 与实际上限并列打出来：候选 0 条 vs 候选很多但全被跳过，是两种完全不同的故障。
        busChainLog("chainPaste/②候选",
            "Ultimine 形状候选=%d 条（去重前）命中种类=%s 实际上限=%d = min(FTB %d, ULTIMINE_MAX_BUSES %d)",
            ultimineCandidates.size(), anchorKind, cap, limit, ULTIMINE_MAX_BUSES);
        final Set<BlockPos> targets = new LinkedHashSet<>();
        targets.add(anchor);
        int skipped = 0;
        int sameKindFromShape = 0;
        // 【诊断计数】五类跳过原因 + 写入期细分（只累加，不参与任何判定）。
        int skipNotOurBus = 0;
        int skipKindMismatch = 0;
        int skipUnloaded = 0;
        int skipTooFar = 0;
        int skipOverCap = 0;
        int writeNotBus = 0;
        int writeTooFar = 0;
        for (final BlockPos pos : ultimineCandidates) {
            if (pos == null || targets.contains(pos)) {
                continue; // 去重：连锁集合可能含重复坐标（anchor 本身也可能出现在候选里）
            }
            if (!level.isLoaded(pos) || !withinReach(player, pos)) {
                skipped++; // 未加载 / 玩家已走远：不为一格判定去加载区块
                // 【诊断】把「未加载」与「超距」分开数（只读判定，不改变上面的分支）
                if (!level.isLoaded(pos)) {
                    skipUnloaded++;
                } else {
                    skipTooFar++;
                }
                continue;
            }
            if (targets.size() >= cap) {
                skipped++; // 上限之外的候选计入「跳过」，不悄悄丢弃
                skipOverCap++;
                continue;
            }
            targets.add(pos);
            if (anchorKind.equals(kindAt(level, pos))) {
                sameKindFromShape++; // 只服务上面那句判断：形状里有没有「能写的第二台」
            }
        }
        int sameKindFromCluster = 0;
        if (sameKindFromShape == 0) {
            // FTB 的形状走不过线缆：沿同一条线缆簇补齐同种总线。
            // 只收「同种总线」—— 线缆本身不是目标，既不占名额也不计入「跳过」。
            // 【诊断｜rscc-chain-bus·③】补齐是否被触发、被谁调用（唯一入口就是下面这行）。
            busChainLog("chainPaste/③补齐",
                "线缆簇补齐已触发（形状内同种=0）：调用方=RsccBusClipboard.chainPaste → "
                    + "RsccWireLinkSearch.searchChamberLink(level, anchor).cluster()");
            final RsccWireLinkSearch.LinkWalk busWalk =
                RsccWireLinkSearch.searchChamberLink(level, anchor);
            // 【诊断｜rscc-chain-bus·③b】补齐当时的结果是否完整：
            // LinkWalk 只在「整趟扫完 / 触顶 HARD_CAP」时发布，分帧中途不会对外出现半份；
            // exhaustive=false 是唯一「首扫没扫完」的来源（异常巨大线网的安全阀）。
            busChainLog("chainPaste/③b补齐",
                "补齐当时=%s；exhaustive=%s 簇格=%d 可达=%d",
                busWalk.exhaustive() ? "首扫已完成（完整结果）" : "首扫未完成（触顶 HARD_CAP）",
                busWalk.exhaustive(), busWalk.cluster().size(), busWalk.reachableCount());
            for (final BlockPos pos : busWalk.cluster()) {
                if (pos == null || targets.contains(pos)) {
                    continue;
                }
                if (!anchorKind.equals(kindAt(level, pos))) {
                    // 【诊断】只读方块状态，区分「不是本模组总线」与「另一种总线」（不改变上面这条判定）
                    if (kindAt(level, pos) == null) {
                        skipNotOurBus++;
                    } else {
                        skipKindMismatch++;
                    }
                    continue;
                }
                if (!level.isLoaded(pos) || !withinReach(player, pos)) {
                    skipped++;
                    // 【诊断】未加载 / 超距分开数
                    if (!level.isLoaded(pos)) {
                        skipUnloaded++;
                    } else {
                        skipTooFar++;
                    }
                    continue;
                }
                if (targets.size() >= cap) {
                    skipped++;
                    skipOverCap++;
                    continue;
                }
                targets.add(pos);
                sameKindFromCluster++;
            }
        } else {
            // 【诊断｜rscc-chain-bus·③】没触发补齐也要留一条：否则「没补齐」与「补齐了没写入」分不清。
            busChainLog("chainPaste/③补齐未触发",
                "线缆簇补齐未触发：形状内已有同种总线 %d 台 ⇒ 完全按形状走，不做任何扩展",
                sameKindFromShape);
        }
        chainObserve("②规划完成：形状内同类=%d 线缆簇补齐=%d 目标=%d 跳过=%d 上限=%d",
            sameKindFromShape, sameKindFromCluster, targets.size(), skipped, cap);

        // 写入：每条总线恰好一次，单块异常不中断整批
        int applied = 0;
        int mismatched = 0;
        int failed = 0;
        for (final BlockPos pos : targets) {
            final String kind = kindAt(level, pos);
            if (kind == null) {
                failed++; // 方块已被拆除 / 已不是本模组的总线
                writeNotBus++;
                continue;
            }
            if (!anchorKind.equals(kind)) {
                mismatched++; // 类型不符：只跳过、绝不写（「不要乱写」那条硬要求）
                continue;
            }
            if (!withinReach(player, pos)) {
                failed++; // 处理途中玩家走动：这一块不写
                writeTooFar++;
                continue;
            }
            try {
                if (pasteOne(level, pos, kind, payload)) {
                    applied++;
                } else {
                    failed++; // 方块状态是总线、方块实体却不是：极罕见的状态错位
                }
            } catch (final RuntimeException e) {
                // 单块异常只影响这一块：整批继续，也绝不把异常冒回 FTB 的事件链。
                // 【第 88 轮】以前这里是彻底吞掉的（只有一个 failed++）：玩家看到「少写了一台」，
                // 日志里一个字都没有。现在第一次带完整堆栈的 WARN、之后按次数汇总，
                // 且**不受 devLogs 控制** —— 否则关掉开关后这条异常又变成不可见。
                failed++;
                pasteWriteFailure(pos, kind, e);
            }
        }

        // 【诊断｜rscc-chain-bus·④】五类跳过原因各几次 —— 不管是「候选没给」「被距离挡」
        // 还是「种类不符」，都能在这一行里找到对应数字；与上面的 ①进入 / ②候选 连起来读。
        busChainLog("chainPaste/④跳过原因",
            "跳过原因计数：非本模组总线=%d 种类不符=%d 未加载=%d 超距=%d 超上限=%d"
                + "（形状候选=%d 规划目标=%d 形状内同种=%d 簇补齐同种=%d 上限=%d）",
            skipNotOurBus + writeNotBus, skipKindMismatch + mismatched, skipUnloaded,
            skipTooFar + writeTooFar, skipOverCap,
            ultimineCandidates.size(), targets.size(), sameKindFromShape, sameKindFromCluster, cap);
        // 【诊断｜rscc-chain-bus·⑤】最终写入几台（放在所有 return 之前：成功 / 一条没写 / 只写命中格都看得见）。
        busChainLog("chainPaste/⑤最终写入",
            "最终写入=%d 台（规划目标=%d 台；写入失败其它=%d）",
            applied, targets.size(), failed - writeNotBus - writeTooFar);

        if (applied == 0) {
            chainObserve("④一条都没写：targets=%d 跳过=%d 类型不符=%d 失败=%d（上限=%d）",
                targets.size(), skipped, mismatched, failed, cap);
            // 一条都没写：把「为什么」给出来（跳过 = 已拆 / 走远 / 越界 / 写入异常；类型不符单独计）
            player.displayClientMessage(
                Component.translatable(MSG_CHAIN_NONE, skipped + failed, mismatched), true);
            return 1;
        }
        if (targets.size() == 1) {
            chainObserve("④只改到命中这一条：applied=%d（候选里没有同种总线，或被上限 / 距离全挡掉）", applied);
            // 只改到命中这一条：连锁圈里没有同种总线（或被上限 / 距离全部挡掉）。
            // 单独一句，因为「明明框了一大片却只改了一条」最容易被当成「连锁粘贴没生效」。
            player.displayClientMessage(Component.translatable(MSG_CHAIN_NO_SIBLINGS), true);
            return applied;
        }
        chainObserve("④连锁粘贴完成：applied=%d targets=%d 跳过=%d 类型不符=%d 失败=%d",
            applied, targets.size(), skipped, mismatched, failed);
        player.displayClientMessage(Component.translatable(MSG_CHAIN_PASTED,
            applied, skipped + failed, mismatched), true);
        return applied;
    }

    /** 目标是否在连锁粘贴允许的距离内（按方块中心算；逐块复检，玩家走动后不再写入）。 */
    private static boolean withinReach(final ServerPlayer player, final BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
            <= ULTIMINE_MAX_DISTANCE * ULTIMINE_MAX_DISTANCE;
    }

    /**
     * 单块写入失败<b>不再静默</b>：第 1 次带完整堆栈的 WARN，之后按次数汇总。
     *
     * <p>与 {@code RsccUltimineIntegration#handlerFailure} 同一套口径：第一次的堆栈是唯一能看出
     * 「API 变了 / 方块实体在写入时炸了 / 某个槽位数据坏了」的证据；第 2 次起只汇总，不刷屏。
     * <b>不受 {@code devLogs} 控制</b> —— 关掉开关时这类异常仍然必须可见。</p>
     */
    private static void pasteWriteFailure(final BlockPos pos, final String kind, final RuntimeException cause) {
        final int count = PASTE_WRITE_FAILURES.incrementAndGet();
        if (count == 1) {
            LOGGER.warn("[rscc-chain-bus] 写入总线配置抛异常（第 1 次，完整堆栈）；该块计为失败、"
                + "整批继续。pos={} kind={}", pos, kind, cause);
        } else if (count <= 5 || count % 100 == 0) {
            LOGGER.warn("[rscc-chain-bus] 写入总线配置再次抛异常（累计 {} 次）：pos={} kind={} {}",
                count, pos, kind, cause.toString());
        }
    }

    /** 单块写入异常计数（只服务日志）。 */
    private static final AtomicInteger PASTE_WRITE_FAILURES = new AtomicInteger();

    // ------------------------------------------------------------------
    // 载荷读写
    // ------------------------------------------------------------------

    /** 取剪贴板上本模组那一段载荷；没有（或为空）返回 {@code null}。 */
    @Nullable
    private static CompoundTag payloadOf(final ItemStack clipboard) {
        final CompoundTag copied = clipboard
            .getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY)
            .copiedValues().orElse(null);
        if (copied == null || !copied.contains(CLIPBOARD_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return null;
        }
        final CompoundTag payload = copied.getCompound(CLIPBOARD_KEY);
        return payload.isEmpty() ? null : payload;
    }

    /**
     * 剪贴板连锁的<b>第一次判定</b>：无条件打一条 INFO（每进程一次，不受 {@code devLogs} 控制）。
     *
     * <p>这一条不是「细节日志」，而是<b>最小可诊断路径</b>：它把这次右键的六个决定性事实放在同一行里 ——
     * 用哪只手、手里是不是剪贴板、是不是潜行、命中格是不是本模组总线（是哪种）、
     * 剪贴板里有没有本模组载荷（载荷自称是哪种总线）、FTB 给了几条候选 / 上限多少。
     * 只要用户复现一次，日志就能把「FTB 没派发 / 分派被抢 / 卡在种类 / 卡在载荷 / 卡在距离」当场分开。</p>
     *
     * <p>为什么只打第一次：它是环境与链路的<b>静态前提</b>，不是每次操作的结果；每次右键都打会变成噪音。</p>
     */
    private static void logFirstDecision(final ServerPlayer player, final InteractionHand hand,
                                         final ItemStack clipboard, final BlockPos anchor,
                                         @Nullable final String anchorKind,
                                         @Nullable final CompoundTag payload,
                                         final Collection<BlockPos> candidates, final int limit) {
        if (!CHAIN_FIRST_DECISION.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("[rscc-chain] 剪贴板连锁首次判定（不受 devLogs 控制）：hand={} 手持={} 潜行={} "
                + "命中={} 命中种类={} 载荷种类={} 候选={} 上限={}",
            hand, clipboard.getItem(), player.isShiftKeyDown(), anchor, anchorKind,
            payload == null ? "无" : payload.getString(RsccBusConfig.KEY_KIND), candidates.size(), limit);
    }

    /**
     * 把载荷写进剪贴板：<b>只替换本模组这一段</b>，其余段落（Create 自己的、别的模组的）原样保留。
     * <p>写的是 {@code copied_values} 子标签（Create 复制配置时用的同一格），并把剪贴板类型置成
     * {@link ClipboardType#WRITTEN} —— 与 Create 自己复制配置后的表现一致（物品外观也会跟着变）。</p>
     */
    private static void writeToClipboard(final ItemStack clipboard, final CompoundTag payload) {
        final ClipboardContent content = clipboard
            .getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY);
        final CompoundTag merged = content.copiedValues().map(CompoundTag::copy).orElseGet(CompoundTag::new);
        merged.put(CLIPBOARD_KEY, payload);
        clipboard.set(AllDataComponents.CLIPBOARD_CONTENT,
            content.setType(ClipboardType.WRITTEN).setCopiedValues(merged));
    }

    // ------------------------------------------------------------------
    // 目标识别与写入
    // ------------------------------------------------------------------

    /**
     * 这一格是哪一种总线（{@link RsccBusConfig#KIND_IMPORTER} / {@link RsccBusConfig#KIND_EXPORTER}），
     * 不是总线则 {@code null}。
     * <p>只读方块状态（{@link RsccWireBlocks} 的类别判定，绝不取方块实体），因此这一步永远廉价、
     * 也不会因为「探测一个无关方块」而强制加载它的方块实体。</p>
     */
    @Nullable
    private static String kindAt(final Level level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (RsccWireBlocks.isImporterBus(state)) {
            return RsccBusConfig.KIND_IMPORTER;
        }
        if (RsccWireBlocks.isExporterBus(state)) {
            return RsccBusConfig.KIND_EXPORTER;
        }
        return null;
    }

    /**
     * 把这一格总线的配置<b>复制</b>进剪贴板（不显示任何文案：由调用方决定说什么）。
     *
     * <p>为什么单独抽出来：单台复制（{@link #onRightClickBlock}）与连锁粘贴的「剪贴板还是空的」
     * 兜底（{@link #chainPaste}）要写的是<b>同一份</b>载荷。两份代码分别写一次，迟早会出现
     * 「一条路径写全了、另一条漏了某个开关」的静默不一致 —— 而这正是玩家最难自查的那类 bug。</p>
     *
     * <p><b>第 87 轮补的显示用子标签</b>：除了粘贴真正要用的配置键，这里还写入
     * {@link RsccBusJadePayload} 那一份（归属执行舱 / 自动 / 方向 / 六节类别与代表物）。
     * 它<b>只服务 tooltip</b>，粘贴侧从不读它 —— 因此「剪贴板里能看到什么」与「能粘什么」
     * 不会因为这份附加数据而产生任何行为差异。</p>
     *
     * @return {@code false} = 方块状态说是总线、方块实体却不是（宁可什么都不做，也绝不写一份空配置进剪贴板）
     */
    private static boolean copyInto(final ServerLevel level, final BlockPos pos, final String kind,
                                    final ItemStack clipboard) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        final CompoundTag payload = new CompoundTag();
        if (blockEntity instanceof final RsccImporterExecutorMode importer
            && RsccBusConfig.KIND_IMPORTER.equals(kind)) {
            importer.rscc$writeBusConfig(payload, level.registryAccess());
        } else if (blockEntity instanceof final RsccExporterExecutorMode exporter
            && RsccBusConfig.KIND_EXPORTER.equals(kind)) {
            exporter.rscc$writeBusConfig(payload, level.registryAccess());
        } else {
            return false;
        }
        // 【第 87 轮】把 Jade 那条载荷（归属执行舱 / 自动 / 方向 / 六节类别与代表物）也带进剪贴板：
        // 客户端 tooltip 直接复用 RsccBusJadePayload.lines(...)，于是「剪贴板详情」与「瞄准总线时的
        // Jade 提示」是同一份数据、同一套分类口径、同一批文案键 —— 工程里不会出现第二套「六个类别」。
        // 未绑定执行舱时它一个键都不写（与 Jade 的既有口径一致）；这份子标签只用于显示，
        // 粘贴侧（rscc$readBusConfig）只读自己认识的键，多出来的键不会写回总线一个字节。
        RsccBusJadePayload.write(payload, level, pos, blockEntity);
        writeToClipboard(clipboard, payload);
        return true;
    }

    /**
     * 「连锁粘贴可用吗」的环境诊断，每位玩家每局只说一次。
     *
     * <p>用户实机反馈第 12 条「连锁也不行」有两个环境级前提：FTB Ultimine <b>装没装</b>、
     * 本模组的反射注册<b>成没成功</b>。这两件事玩家在游戏里看不出来 —— 于是这里借「刚复制完配置」
     * 这个最相关的时间点，用一行聊天消息把结论告诉他（不可用时才说），而不是让他一直以为功能坏了。</p>
     */
    private static void notifyUltimineAvailabilityOnce(final ServerPlayer player) {
        final String key = RsccUltimineIntegration.unavailableMessageKey();
        if (key == null) {
            return; // 集成正常（或还没到 FTB 发注册事件的时机）：不打扰
        }
        if (ULTIMINE_NOTICE_SENT.add(player.getUUID())) {
            player.displayClientMessage(Component.translatable(key), false);
        }
    }

    /** 把载荷写到这一条总线；方块实体类型与种类不符时返回 {@code false}（一个字节都不写）。 */
    private static boolean pasteOne(final ServerLevel level, final BlockPos pos, final String kind,
                                    final CompoundTag payload) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (RsccBusConfig.KIND_IMPORTER.equals(kind)
            && blockEntity instanceof final RsccImporterExecutorMode importer) {
            importer.rscc$readBusConfig(payload, level.registryAccess());
            return true;
        }
        if (RsccBusConfig.KIND_EXPORTER.equals(kind)
            && blockEntity instanceof final RsccExporterExecutorMode exporter) {
            exporter.rscc$readBusConfig(payload, level.registryAccess());
            return true;
        }
        return false;
    }

    /**
     * <b>整簇粘贴</b>（潜行 + 左击）：把载荷写到「同一条线缆簇里的全部同种总线」，返回真正写入的台数。
     *
     * <h2>范围边界（硬要求：不要误改到不相关的总线）</h2>
     * <ol>
     *     <li><b>同一线缆簇</b>：复用 {@link RsccWireLinkSearch#searchChamberLink} 的唯一一趟展开
     *     —— 它同时尊重「扳手断开的接缝」（{@link RsccCableCuts}）与「被分隔框架冻结的接缝」
     *     （{@link SeparationFrameGuard}），并且有步数 / 格子数上限。因此断开的那一段产线<b>不会</b>
     *     被顺着摸过去；这里刻意不另写一套 BFS，否则「哪些总线算一簇」迟早会出现两种口径；</li>
     *     <li><b>同一种总线</b>：只改与目标同类的那些格子（输入总线不会把配置糊到输出总线上
     *     —— 那也正是载荷种类校验要拦的事，这里再按方块状态收一次口）；</li>
     *     <li><b>台数上限</b>：{@link #BULK_MAX_BUSES}，到顶即停；</li>
     *     <li><b>不跨维度</b>：整趟展开只在一个 {@link ServerLevel} 里进行。</li>
     * </ol>
     * <p>兜底：展开被重入守卫拦下（另一趟搜链正在进行）时簇可能是空的，
     * 此时至少把配置贴到玩家点的那一条总线上 —— 潜行左击在任何情况下都不会变成「什么都没发生」。</p>
     */
    private static int pasteCluster(final ServerLevel level, final BlockPos origin, final String kind,
                                    final CompoundTag payload) {
        final List<BlockPos> cluster = RsccWireLinkSearch.searchChamberLink(level, origin).cluster();
        int applied = 0;
        for (final BlockPos pos : cluster) {
            if (applied >= BULK_MAX_BUSES) {
                break;
            }
            if (!kind.equals(kindAt(level, pos))) {
                continue;
            }
            if (pasteOne(level, pos, kind, payload)) {
                applied++;
            }
        }
        if (applied == 0 && pasteOne(level, origin, kind, payload)) {
            applied = 1;
        }
        return applied;
    }

    /** 方块名（只读方块状态，不碰方块实体）。 */
    private static Component nameOf(final Level level, final BlockPos pos) {
        return level.getBlockState(pos).getBlock().getName();
    }
}
