package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 「序列装配」整条链路的<b>结构化诊断日志</b>（可开关 / 节流 / 统一前缀）。
 *
 * <p>本轮<b>只加诊断、不改任何行为逻辑</b>：用于让用户跑一遍后把日志发回来，据此定位
 * 「最终产物没回流到网络 / 中间产物没被正确回收到网络再分给下一步的机器」这类问题。</p>
 *
 * <p><b>输出格式（硬性）</b>：统一前缀 {@value #PREFIX}，每条一行，字段固定顺序
 * <pre>
 *   [rscc-assembly] 机器类型@坐标 | 步骤/类别 | 资源 id | 数量 | 结果/原因
 * </pre>
 * 例：</p>
 * <pre>
 *   [rscc-assembly] chamber@(12,64,-3) step=2 claim recipe=create:filling need {item=create:iron_sheet x2}
 *   [rscc-assembly] importer@(12,64,-1) linked=chamber@(12,64,-3) cats=[product] took {item=create:iron_sheet x2} inserted=2 rejected=0
 * </pre>
 *
 * <p><b>节流（不刷屏的三道闸）</b>：</p>
 * <ol>
 *     <li>{@link #transition}：同一台机器的同一类事件<b>只在状态翻转时</b>各打一条（稳态零输出）；</li>
 *     <li>{@link #reason}：同一个「原因」字符串在 <b>1 秒</b>内只打一条；</li>
 *     <li>{@link #reject}：同一个「拒绝原因」<b>首次立即</b>一条、之后每 <b>5 秒</b>合并一条
 *     {@code repeated n times}（{@code step_not_mine} 这类每秒重复的拒绝不再刷屏）；</li>
 *     <li>{@link #warn}：需要玩家干预的异常（例如「这一步没有任何在线机器认领」）每处只 WARN 一条；</li>
 *     <li>{@link #countPull}/{@link #countFeed}/{@link #countCollect}/{@link #countReturn}/{@link #countReject}
 *     ：只累加计数，<b>每 5 秒</b>最多输出一条聚合摘要（且窗口内没有活动时不输出）。</li>
 * </ol>
 * <p>稳态（持续有料在流、状态不翻转）的最坏输出速率 =
 * 「每 5 秒 1 条摘要」+「每个因由键 每秒 1 条」+「每台机器每次状态翻转 1 条」，与机器数量线性相关，
 * 与 tick 无关。见自检脚本 {@code tools/selfcheck_assembly_debug.py} 的估算。</p>
 *
 * <p><b>性能</b>：所有调用点都必须先判 {@link #isEnabled()}（关闭时一行不打、且<b>不进行任何字符串拼接</b>）；
 * 本类的计数 / 去重表只在开启时更新。开关状态本身的变化<b>始终</b>打一条 INFO（含关闭那一次）。</p>
 *
 * <p><b>开关</b>：初值来自配置 {@code rsccAssemblyDebug}（默认 {@code true}），
 * 运行时可用指令 {@code /rs_create_compat debug assembly <on|off>}（等价别名
 * {@code /rs_create_compat assemblydebug <on|off>}）切换。</p>
 */
public final class RsccAssemblyDebug {
    /** 统一前缀：所有诊断行都以它开头（便于 grep）。 */
    public static final String PREFIX = "[rscc-assembly]";
    /**
     * <b>端到端追踪</b>的专用前缀（本轮新增）：一条成品 / 中间产物从「机器产出」到「进入 RS 网络」
     * 必须经过「机器产出 → 输出总线判定 → 移交下一台仓 → 输入总线收取 → 插入网络」五段，
     * 本前缀的每一行都带齐 {@code item/amount | from | event | to | reason | net} 六个字段，
     * 因此「东西在哪一段停住 / 消失」可以直接逐行读出，不需要再靠推断。
     *
     * <p>与 {@link #PREFIX} 分开是为了让玩家 / 开发者可以 <b>只</b> grep 这一族行
     * （{@code [rscc-trace]}），不被类别表 / 门控 / 摘要等噪声干扰。</p>
     */
    public static final String TRACE_PREFIX = "[rscc-trace]";

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccAssemblyDebug.class);

    /** 聚合摘要间隔：5 秒。 */
    private static final long SUMMARY_INTERVAL_NANOS = 5_000_000_000L;
    /** 同一「原因」字符串的去重窗口：1 秒。 */
    private static final long REASON_WINDOW_NANOS = 1_000_000_000L;
    /**
     * 同因 reject 的合并窗口：5 秒。
     * <p>用户要求：{@code step_not_mine} 这类<b>每秒重复</b>的拒绝不要刷屏 —— 首次立即打一条
     * （保留可诊断性），之后每 5 秒汇总一条「repeated n times」。</p>
     */
    private static final long REJECT_MERGE_NANOS = 5_000_000_000L;
    /** 状态 / 原因表的软上限（键通常是方块坐标，正常远小于它；超限即整表清空，避免无界增长）。 */
    private static final int TABLE_LIMIT = 512;

    /** 运行时开关；初值取自 {@code Config.rsccAssemblyDebug}。 */
    private static volatile boolean enabled = true;

    /** key → 上次状态（{@link #transition} 用）。 */
    private static final Map<String, String> LAST_STATE = new HashMap<>();
    /** 原因 key → 上次打印时刻（纳秒，{@link #reason} 用）。 */
    private static final Map<String, Long> LAST_REASON_NANOS = new HashMap<>();
    /** 同因 reject 的计数 / 窗口起点（key → {count, windowStartNanos}，{@link #reject} 用）。 */
    private static final Map<String, long[]> REJECT_WINDOWS = new HashMap<>();
    /** {@link #trace} 的同因合并窗口表（与 {@link #REJECT_WINDOWS} 分开：两族日志的键空间互不干扰）。 */
    private static final Map<String, long[]> TRACE_WINDOWS = new HashMap<>();
    /** {@link #dedupe} 的同因合并窗口表（同样与上面两族分开：查重日志要能单独 grep / 单独看窗口）。 */
    private static final Map<String, long[]> DEDUPE_WINDOWS = new HashMap<>();

    // ---------- 聚合计数（自本窗口开始以来） ----------
    private static long windowStartNanos = System.nanoTime();
    private static long pullCount;
    /** 流体抽取计数（mB），与物品件数分开，避免摘要里的量纲混合（见 {@link #countPullFluid}）。 */
    private static long pullFluidCount;
    private static long feedCount;
    private static long collectCount;
    private static long returnCount;
    private static long rejectCount;

    private RsccAssemblyDebug() {
    }

    // ==================== 开关 ====================

    /** 诊断是否开启。调用点必须先用它守卫，避免关闭时进行字符串拼接。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 用配置初始化（模组加载 / 配置重载时调用）；值发生变化时打一条 INFO。 */
    public static void initFromConfig(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        LOGGER.info("{} debug={} (source=config)", PREFIX, value ? "on" : "off");
    }

    /** 运行时开关（指令调用）。状态变化本身打一条 INFO —— 即使切换到「关」也只打这一条。 */
    public static void setEnabled(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        LOGGER.info("{} debug={} (source=command)", PREFIX, value ? "on" : "off");
    }

    // ==================== 字段格式化 ====================

    /** 坐标 → {@code (x,y,z)}。 */
    public static String at(final BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    /** {@code 机器类型@坐标}，如 {@code chamber@(12,64,-3)}。 */
    public static String machine(final String kind, final BlockPos pos) {
        return kind + "@" + at(pos);
    }

    /** 物品注册名（取不到返回 {@code "?"}）。 */
    public static String itemId(final Item item) {
        if (item == null) {
            return "?";
        }
        final ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "?" : key.toString();
    }

    /** 流体注册名（取不到返回 {@code "?"}）。 */
    public static String fluidId(final Fluid fluid) {
        if (fluid == null) {
            return "?";
        }
        final ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        return key == null ? "?" : key.toString();
    }

    // ==================== 输出 ====================

    /** 一次性事件（调用方保证低频：状态变化 / 单批搬运等）。 */
    public static void event(final String body) {
        if (enabled) {
            // 默认采集（本轮新增）：把这条正文按「坐标 + 资源」喂给诊断计数器（只读、不刷屏）。
            RsccDiag.observe(body);
            LOGGER.info("{} {}", PREFIX, body);
        }
    }

    /**
     * 状态翻转判定：{@code key} 的上次状态与 {@code state} 相同 → 返回 {@code false}（不打）；
     * 不同 → 记录新状态并返回 {@code true}（调用方据此再调 {@link #event}，细节字符串只在翻转时才拼）。
     */
    public static boolean changed(final String key, final String state) {
        if (!enabled) {
            return false;
        }
        if (state.equals(LAST_STATE.get(key))) {
            return false;
        }
        put(LAST_STATE, key, state);
        return true;
    }

    /** {@link #changed} + {@link #event} 的合并便捷写法。 */
    public static void transition(final String key, final String state, final String body) {
        if (changed(key, state)) {
            LOGGER.info("{} {}", PREFIX, body);
        }
    }

    /**
     * 原因去重：同一 {@code key} 在 1 秒内只放行一条。
     */
    public static void reason(final String key, final String body) {
        if (!enabled) {
            return;
        }
        final long now = System.nanoTime();
        final Long last = LAST_REASON_NANOS.get(key);
        if (last != null && now - last < REASON_WINDOW_NANOS) {
            return;
        }
        put(LAST_REASON_NANOS, key, now);
        RsccDiag.observe(body);
        LOGGER.info("{} {}", PREFIX, body);
    }

    /**
     * <b>同因合并</b>的「拒绝」日志：同一个 {@code key}（= 机器 + 件 + 因由）<b>首次立即打一条</b>
     * （保留可诊断性，玩家不用等窗口），之后只累加计数，每满 5 秒汇总一条
     * {@code ... (same cause repeated n times in 5s)}。
     *
     * <p><b>为什么需要它（用户要求）</b>：被本仓按步拒绝的件会每个引擎节拍（1 秒）重新判定一次，
     * 于是 {@code reject {... step=2} reason=step_not_mine} 会<b>每秒刷一条</b>，把真正有用的日志
     * （料流 / 任务 / 收回）淹掉。合并后同一原因稳态最多每 5 秒一条，且「第一次」与「持续多少次」
     * 都在日志里，可诊断性不降。</p>
     */
    public static void reject(final String key, final String body) {
        if (!enabled) {
            return;
        }
        final long now = System.nanoTime();
        final long[] window = REJECT_WINDOWS.get(key);
        if (window == null) {
            // 该原因第一次出现：立刻打明细（玩家不用等窗口），窗口从这一刻开始
            put(REJECT_WINDOWS, key, new long[]{1L, now});
            RsccDiag.observe(body);
            LOGGER.info("{} {}", PREFIX, body);
            return;
        }
        window[0]++;
        RsccDiag.observe(body);
        if (now - window[1] >= REJECT_MERGE_NANOS) {
            LOGGER.info("{} {} (same cause repeated {} times in 5s)", PREFIX, body, window[0]);
            window[0] = 0L;
            window[1] = now;
        }
    }

    /**
     * <b>同因合并</b>的「重复动作」日志：与 {@link #reject} 共用同一套窗口与格式
     * （同一 {@code key} 首次立即一条，之后每 5 秒一条并带 {@code (same cause repeated n times in 5s)}）。
     *
     * <p><b>为什么要它（用户要求）</b>：{@code pull} 这类「成功但高频」的动作原先走
     * {@link #transition}，而它的状态串里带着 {@code net}（网络余量）—— 这个数每秒都在变，
     * 于是状态<b>必然</b>每次都翻转、等于没有节流，日志被 pull 行淹没（用户实测 5 秒 2000 行的量级）。
     * 改走本通道后：同一「仓 + 资源」稳态最多每 5 秒一条，首次明细仍然立刻可见（可诊断性不降），
     * 总搬运量另有 5 秒聚合摘要（{@code summary window=5s pull=...}）兜底。</p>
     */
    public static void repeat(final String key, final String body) {
        reject(key, body);
    }

    /**
     * <b>端到端追踪</b>（本轮新增）：把「一个成品 / 一份中间产物在整条链路里的每一步」打成一行，
     * 统一前缀 {@value #TRACE_PREFIX}，字段固定顺序：
     * <pre>
     *   [rscc-trace] item=&lt;注册名&gt; x&lt;n&gt; | from=&lt;来源&gt; | event=&lt;产出|尝试插入网络|插入成功|被拒|被丢|移交下一台仓|被收回|留在原处&gt;
     *                | to=&lt;目标&gt; | reason=&lt;具体分支&gt; | net=&lt;该时刻网络内该物品存量&gt;
     * </pre>
     * <p><b>为什么要单独一族</b>：用户明确质疑「日志做得太烂」—— 旧日志只在<b>搬运量 &gt; 0</b> 时打，
     * 且 {@code storage.insert(...)} 的返回值把 RS「被任务截收的量」也算成 inserted
     * （见 {@code RootStorageImpl#insert} 返回 {@code inserted + intercepted}），
     * 于是出现「日志说 inserted=1，但终端里一件都看不到」这种自相矛盾的记录。
     * 本族日志强制把 {@code net}（<b>真实入网存量</b>）与事件原因一起打出来，
     * 两者一对就能立刻区分「真进网络」还是「被别的东西截收 / 停在某一段」。</p>
     * <p><b>限频（与 {@link #reject} 同一套）</b>：同一 {@code key}（机器 + 物品 + 事件）首次立即一条，
     * 之后每 5 秒合并一条 {@code (same cause repeated n times in 5s)}；因此稳态不刷屏，
     * 而「第一次发生」永远看得见。</p>
     *
     * @param key  去重键（调用方按「机器 + 物品 + 事件」拼；不要放每秒都在变的数字）
     * @param body 正文（由调用方的 {@link #traceLine} 拼出）
     */
    public static void trace(final String key, final String body) {
        if (!enabled) {
            return;
        }
        final long now = System.nanoTime();
        final long[] window = TRACE_WINDOWS.get(key);
        if (window == null) {
            put(TRACE_WINDOWS, key, new long[]{1L, now});
            RsccDiag.observe(body);
            LOGGER.info("{} {}", TRACE_PREFIX, body);
            return;
        }
        window[0]++;
        RsccDiag.observe(body);
        if (now - window[1] >= REJECT_MERGE_NANOS) {
            LOGGER.info("{} {} (same cause repeated {} times in 5s)", TRACE_PREFIX, body, window[0]);
            window[0] = 0L;
            window[1] = now;
        }
    }

    /**
     * 拼一条 {@link #trace} 正文（字段顺序固定，便于机器解析 / 断言）：
     * {@code item=<id> x<n> | from=<来源> | event=<事件> | to=<目标> | reason=<分支> | net=<网络存量>}。
     *
     * @param resource {@code item=create:sturdy_sheet} / {@code fluid=minecraft:lava}（由调用方给全样式）
     * @param amount   数量（件 / mB；&lt; 0 时写 {@code -}）
     * @param from     来源（如 {@code machine@(x,y,z)} / {@code chamber@(x,y,z)} / {@code network}）
     * @param event    事件（产出 / take_to_chamber / insert_network / insert_claimed / …）
     * @param to       目标（如 {@code network} / {@code chamber@(x,y,z)} / {@code exporter@(x,y,z)}）
     * @param reason   具体分支原因（失败 / 放行的唯一原因；必填，不允许空）
     * @param net      该时刻网络内该物品的存量（&lt; 0 时写 {@code -}）
     */
    public static String traceLine(final String resource, final long amount, final String from,
                                   final String event, final String to, final String reason,
                                   final long net) {
        return resource + " x" + (amount < 0 ? "-" : amount)
            + " | from=" + from
            + " | event=" + event
            + " | to=" + to
            + " | reason=" + reason
            + " | net=" + (net < 0 ? "-" : net);
    }

    /**
     * 一次性 WARN（同一 {@code key} 只打一条，状态复位由 {@link #changed} 的同一张表负责）：
     * 用于<b>需要玩家干预</b>的异常（例如「这一步没有任何在线机器认领」）—— 这类信息不该只出现在
     * 每秒刷屏的 INFO 里，也不该每节拍重复。
     */
    public static void warn(final String key, final String body) {
        if (!enabled || !changed("warn:" + key, "warned")) {
            return;
        }
        RsccDiag.observe(body);
        LOGGER.warn("{} {}", PREFIX, body);
    }

    // ==================== 单元样板查重（可审计的「为什么跳过」） ====================

    /** 查重日志的统一前缀（便于单独 grep：{@code [rscc-dedupe]}）。 */
    public static final String DEDUPE_PREFIX = "[rscc-dedupe]";

    /**
     * <b>一次单元样板查重判定</b>的可审计日志（本轮新增）。
     *
     * <h2>为什么必须有它（§7.3 审计的取证缺口）</h2>
     * <p>旧日志里只有「<b>全部</b>步骤都被跳过」时的一条汇总
     * （{@code terminal generate: all 2 unit step(s) skipped as duplicates -> total pattern only}），
     * 而 {@code UnitPatternDedupe#findDuplicate} 已经算出的<b>命中来源与槽位当场被丢弃</b>。
     * 实机那三次「2 步全跳过」因此永久不可复现：事后存档里已经没有任何一条列车轨道 deploying
     * 单元样板，谁也说不清它当时是跟<b>哪一张</b>判成了重复 —— 审计原文正是「命中来源不可复现，
     * 缺的正是 Match 日志」。</p>
     *
     * <h2>输出规则（走既有的 5 秒同因合并闸，绝不刷屏）</h2>
     * <ul>
     *     <li>{@code skipped}：判为重复而<b>不生成</b>该步。首次立即一条（含来源 + 槽位 + 判据摘要），
     *     之后同一「步骤 + 来源 + 槽位」每 5 秒合并一条 {@code (same cause repeated n times in 5s)}；</li>
     *     <li>{@code keep}：<b>判为不重复</b>（会生成）但该步开了「跳过重复」开关 —— 这是
     *     「明明看到有相同样板却没跳过」这类怀疑的唯一反证，因此也留一条（同样限频）。</li>
     * </ul>
     * <p>注意：只有「生成」与「新建单元样板」这两条真正的决策路径会调用它；
     * 界面刷新用的只读探测（{@code SyncStepMachinesPacket}）不写日志，因此稳态零输出。</p>
     *
     * @param label     决策来源（如 {@code terminal generate} / {@code unit create}）
     * @param stepIndex 步序（全局下标；未知填 {@code -1}）
     * @param duplicate 是否判定为重复
     * @param source    命中来源（{@code null} = 没有命中）
     * @param slot      命中槽位（没有命中时无意义）
     * @param basis     判据摘要（配方 + 步序 + 操作类型 + 代表物 + 候选组）
     */
    public static void dedupe(final String label, final int stepIndex, final boolean duplicate,
                              final String source, final int slot, final String basis) {
        if (!enabled) {
            return;
        }
        final String where = stepIndex < 0 ? "step=?" : "step=" + stepIndex;
        final String body = duplicate
            ? "dedupe " + label + " " + where + " SKIPPED (duplicate of " + source + " slot " + slot
                + ") | " + basis
            : "dedupe " + label + " " + where + " NOT duplicate (will generate) | " + basis;
        // 键刻意不含「数量 / 时刻」这类每次都变的字段，否则合并闸形同虚设（见 reject 的教训）。
        final String key = "dedupe|" + label + "|" + where + "|" + (duplicate ? "skip" : "keep")
            + "|" + (source == null ? "-" : source) + "|" + slot;
        // 直接走合并闸（复用同一套 5 秒窗口），但换前缀以便与装配事件流分开 grep。
        // 刻意**不**喂 RsccDiag.observe：查重与「流量守恒」无关，不该污染那张计数表。
        final long now = System.nanoTime();
        final long[] window = DEDUPE_WINDOWS.get(key);
        if (window == null) {
            put(DEDUPE_WINDOWS, key, new long[]{1L, now});
            LOGGER.info("{} {}", DEDUPE_PREFIX, body);
            return;
        }
        window[0]++;
        if (now - window[1] >= REJECT_MERGE_NANOS) {
            LOGGER.info("{} {} (same cause repeated {} times in 5s)", DEDUPE_PREFIX, body, window[0]);
            window[0] = 0L;
            window[1] = now;
        }
    }

    // ==================== 聚合计数 ====================

    /** 累计「从网络拉料」的量（<b>只算物品件数</b>；流体走 {@link #countPullFluid}，两者不可相加）。 */
    public static void countPull(final long amount) {
        if (enabled && amount > 0) {
            pullCount += amount;
            flushIfDue();
        }
    }

    /**
     * 累计「从网络拉流体」的量（mB）。
     *
     * <p><b>为什么要与 {@link #countPull} 分开</b>：旧实现把流体抽取的 mB 直接加进同一个
     * {@code pullCount}，于是摘要里的 {@code pull=} 变成「件数 + mB」的混合量纲 ——
     * 一次岩浆搬运就是 1000，摘要显示 {@code pull=1503/5s} 会被误读成「每秒 300 件物品被抽进仓」，
     * 而实际物品抽取只有十几件（用户第 5 条「疯狂拉出」的取证因此会被这个数字带偏）。
     * 分开后：{@code pull=} 恒为「件」、{@code pullFluid=} 恒为「mB」，两者各自可解释。</p>
     */
    public static void countPullFluid(final long amount) {
        if (enabled && amount > 0) {
            pullFluidCount += amount;
            flushIfDue();
        }
    }

    /** 累计「喂给相邻机器」的量。 */
    public static void countFeed(final long amount) {
        if (enabled && amount > 0) {
            feedCount += amount;
            flushIfDue();
        }
    }

    /** 累计「从机器 / 执行舱侧收回」的量。 */
    public static void countCollect(final long amount) {
        if (enabled && amount > 0) {
            collectCount += amount;
            flushIfDue();
        }
    }

    /** 累计「回写网络」的量。 */
    public static void countReturn(final long amount) {
        if (enabled && amount > 0) {
            returnCount += amount;
            flushIfDue();
        }
    }

    /** 累计「被网络拒收 / 被拦下」的量。 */
    public static void countReject(final long amount) {
        if (enabled && amount > 0) {
            rejectCount += amount;
            flushIfDue();
        }
    }

    /** 每 5 秒最多一条聚合摘要；窗口内没有任何活动时<b>不</b>输出（不刷屏）。 */
    private static void flushIfDue() {
        final long now = System.nanoTime();
        if (now - windowStartNanos < SUMMARY_INTERVAL_NANOS) {
            return;
        }
        windowStartNanos = now;
        if (pullCount == 0 && pullFluidCount == 0 && feedCount == 0 && collectCount == 0
            && returnCount == 0 && rejectCount == 0) {
            return;
        }
        LOGGER.info("{} summary window=5s pull={} pullFluid={} feed={} collect={} return={} reject={}",
            PREFIX, pullCount, pullFluidCount, feedCount, collectCount, returnCount, rejectCount);
        pullCount = 0;
        pullFluidCount = 0;
        feedCount = 0;
        collectCount = 0;
        returnCount = 0;
        rejectCount = 0;
    }

    /** 无界增长防护：表满即清（键的基数有界，正常不会触发）。 */
    private static <V> void put(final Map<String, V> table, final String key, final V value) {
        if (table.size() >= TABLE_LIMIT && !table.containsKey(key)) {
            table.clear();
        }
        table.put(key, value);
    }
}
