package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * <b>执行舱边界的守恒账本</b>（服务端权威；只记数、绝不搬运 / 生成 / 销毁任何资源）。
 *
 * <h2>为什么需要它（用户第 1 条：坚固板的黑曜石粉与岩浆「又开始乱消耗」）</h2>
 * <p>用户只看到「网络里某个数字变了」却看不出「这一份到底是谁、往哪个方向搬的」。物品侧与流体侧
 * 的备料 / 推料 / 收回代码是两条路径，任一处在取整、批上限、机器侧在飞量上不对称，就会表现为
 * 「有时扣有时不扣」——而肉眼无法从「一个数字」里分辨这两种情况。本账本把执行舱边界上的
 * <b>四类流动</b>逐资源累加，于是守恒不变量可以被逐条核对、并<b>直接列出不满足的条目</b>。</p>
 *
 * <h2>守恒不变量（物品与流体各自独立记账）</h2>
 * <pre>
 *   离开网络 + 世界收集 − 进入网络 = 留存 + 销毁
 *   留存 = 舱内存量增量 + 推给机器 − 从机器取回
 *
 *   ⇒ 未对平 = 留存 + 销毁 − (fromNetwork + fromWorld − toNetwork)
 * </pre>
 * <p>理想情况下 {@code 未对平 == 0}：从网络 / 世界取走的东西，要么还在舱里 / 机器上，要么（任务收尾时）
 * 又还回了网络，要么被<b>有意销毁</b>；一个单位的量既不会凭空出现，也不会凭空消失。</p>
 *
 * <p><b>世界收集（{@link #fromWorld}）与销毁（{@link #destroyed}）为什么必须单列，不能混记</b>：
 * 执行舱的一切都来自网络（{@code fromWorld} 恒 0），而归流缓存仓的资源来自<b>世界</b>（掉落物 /
 * 流体源方块 / 经验球）而不是网络 —— 若不把世界收集记入「离开」一侧，缓存仓每次吸取都会让账目
 * 平白多出一笔无法解释的「留存」。同一道理，销毁是<b>有意让资源消失</b>：若把它伪装成
 * 「回流到网络」（多记 toNetwork）或「留存」（假装还在），账本虽然看着平，却彻底丧失了取证价值 ——
 * 出了复制 / 吞物事故时再也查不出「这一份到底去哪了」。因此销毁必须走独立的
 * {@link #destroyed} 计数，未对平公式里它加在「留存」那一侧。</p>
 *
 * <h2>为什么「未对平」只作诊断、不做硬崩溃</h2>
 * <p>账本只挂在本模组自己的四个执行点（备料 / 推料 / 收集 / 收尾回流）上。世界里有<b>本模组之外的
 * 搬运</b>（玩家手动、别的模组、集群拆分 / 区块卸载 / 读档基线）同样会改变舱内存量，它们不该被
 * 误报成「本模组漏计」。因此本类把差额如实列出，并用 {@link #verdictOf(Row)} /
 * {@link #breakdown(Row)} <b>把差额定位到具体那一小段</b>（舱内存量 / 推给机器 / 进入网络 / 销毁），
 * 供 {@code /rs_create_compat diag} 与日志取证；<b>任何判定都不读它</b>，因此对既有行为零影响。</p>
 * <p><b>第 95 轮修正（用户实机日志里的持续失衡）</b>：一开始这里只把差额写成
 * {@code 可能来源: external_or_baseline}，结果真出现失衡时（{@code create:incomplete_precision_mechanism}
 * 每 60 秒一条）完全指不出是哪条路径 —— 而那一次的实际根因是工位侧残留回流
 * <b>只记了「进入网络」没记「从机器取回」</b>（见 {@code SequenceExecutionChamberBlockEntity
 * #flushStationStartIngredients}），一条纯记账漏项。所以「指出路径」不是锦上添花：
 * 没有它，账本只会制造新的误报。现在判决里能直接读出
 * {@code station_return_unbooked} 这种「差在哪一段」的结论。</p>
 *
 * <p>基线的取法：某个资源<b>第一次</b>被记账时，用 {@code liveProbe} 把「此刻的真实舱内存量」记成基线，
 * 于是重启 / 读档 / 中途存盘都不会制造假差额（计数器是内存态、不落盘）。</p>
 *
 * <p>线程模型：只在服务端主线程的方块实体 tick / 总线搬运里调用，因此不加锁。</p>
 */
public final class RsccFlowLedger {

    /** 一个资源的账目（物品按「件」、流体按「mB」，各自独立，绝不混算）。 */
    private static final class Cell {
        long fromNetwork;
        long toNetwork;
        long toMachine;
        long fromMachine;
        /** 世界收集（掉落物 / 流体源方块 / 经验球）：归流缓存仓的「离开」一侧。执行舱恒 0。 */
        long fromWorld;
        /** 有意销毁（缓存区清空 / 匹配槽「直接销毁」）：与「留存」同侧，绝不伪装成回流。 */
        long destroyed;
        long baseLive;
        boolean seeded;
    }

    /** 资源键 → 账目。用 {@link LinkedHashMap} 保持「先出现的先列出」这一稳定顺序。 */
    private final Map<String, Cell> cells = new LinkedHashMap<>();

    /**
     * 「此刻舱内真实存量」探针（由执行舱注入）：物品走统一视图（内部 + 磁盘缓存）、流体走内部罐。
     * 只在某个资源第一次被记账时调用一次，用于取基线；缺省恒 0（未注入时账目仍可用，只是基线为 0）。
     */
    private ToLongFunction<String> liveProbe = key -> 0L;

    /** 注入「舱内真实存量」探针（执行舱构造 / 首次使用时调用一次即可）。 */
    public void setLiveProbe(final ToLongFunction<String> probe) {
        if (probe != null) {
            this.liveProbe = probe;
        }
    }

    /** 物品 → 稳定资源键（{@code item:create:sturdy_sheet}）。 */
    public static String itemKey(final Item item) {
        return item == null ? "item:?" : "item:" + BuiltInRegistries.ITEM.getKey(item);
    }

    /** 流体 → 稳定资源键（{@code fluid:minecraft:lava}）。 */
    public static String fluidKey(final Fluid fluid) {
        return fluid == null ? "fluid:?" : "fluid:" + BuiltInRegistries.FLUID.getKey(fluid);
    }

    private Cell cell(final String key) {
        Cell found = cells.get(key);
        if (found != null) {
            return found;
        }
        final Cell created = new Cell();
        created.baseLive = Math.max(0L, safeProbe(key));
        created.seeded = true;
        cells.put(key, created);
        return created;
    }

    private long safeProbe(final String key) {
        try {
            return liveProbe.applyAsLong(key);
        } catch (final RuntimeException ignored) {
            return 0L; // 探针异常（未进世界 / 能力拿不到）→ 基线取 0，绝不因为诊断而影响运行
        }
    }

    /** 记账：本仓<b>从 RS 网络取出</b>（离开网络）。{@code amount} 必须 &gt; 0 才有意义。 */
    public void fromNetwork(final String key, final long amount) {
        if (amount > 0L) {
            cell(key).fromNetwork += amount;
        }
    }

    /** 记账：本仓<b>放入 RS 网络</b>（进入网络）。 */
    public void toNetwork(final String key, final long amount) {
        if (amount > 0L) {
            cell(key).toNetwork += amount;
        }
    }

    /** 记账：本仓<b>推给机器 / 置物台</b>（留在产线上，未回网）。 */
    public void toMachine(final String key, final long amount) {
        if (amount > 0L) {
            cell(key).toMachine += amount;
        }
    }

    /** 记账：本仓<b>从机器 / 置物台取回</b>（含跨仓交接进来的那一份）。 */
    public void fromMachine(final String key, final long amount) {
        if (amount > 0L) {
            cell(key).fromMachine += amount;
        }
    }

    /**
     * 记账：本仓<b>从世界收集</b>（归流缓存仓的「离开」一侧：掉落物 / 流体源方块 / 经验球）。
     * <p>归流缓存仓的资源来自世界而不是网络，因此必须与 {@link #fromNetwork} 并列计入
     * 「离开」一侧，未对平公式才可能为 0（见类注释）。执行舱不调用本方法，行为不受影响。</p>
     */
    public void fromWorld(final String key, final long amount) {
        if (amount > 0L) {
            cell(key).fromWorld += amount;
        }
    }

    /**
     * 记账：本仓<b>有意销毁</b>（缓存区清空、匹配槽「直接销毁」）。
     * <p>销毁是玩家显式授权、不可逆的行为，绝不是「搬运失误」：因此必须走独立计数，
     * 与「回流到网络」（{@link #toNetwork}）和「留存」严格区分，账目才可审计。</p>
     */
    public void destroyed(final String key, final long amount) {
        if (amount > 0L) {
            cell(key).destroyed += amount;
        }
    }

    /** 一条账目的只读快照（供诊断导出 / 日志；不暴露可变状态）。 */
    public record Row(String key, long fromNetwork, long toNetwork, long toMachine, long fromMachine,
                      long fromWorld, long destroyed, long live, long retained, long unaccounted) {
    }

    /** 全部账目（按首次出现顺序）。 */
    public List<Row> rows() {
        final List<Row> result = new ArrayList<>(cells.size());
        for (final Map.Entry<String, Cell> entry : cells.entrySet()) {
            final String key = entry.getKey();
            final Cell cell = entry.getValue();
            final long live = Math.max(0L, safeProbe(key));
            result.add(rowOf(key, cell.fromNetwork, cell.toNetwork, cell.toMachine, cell.fromMachine,
                cell.fromWorld, cell.destroyed, live, cell.baseLive));
        }
        return result;
    }

    /**
     * <b>守恒算式的唯一实现</b>（纯函数：只吃数字，不碰 Minecraft、不读任何字段）。
     *
     * <p>为什么单独抽出来：公式此前埋在 {@link #rows()} 里，于是「账本算得对不对」既没法单独验、
     * 也没法在自检里把两条模拟路径（正常跨步 / 真丢）真的跑一遍 —— 只能靠读源码相信。
     * 现在 {@link #rows()} 与自检走<b>同一份</b>实现，改公式就一定会同时改到两边。</p>
     *
     * <pre>
     *   留存   = 舱内存量增量 + 推给机器 − 从机器取回
     *   未对平 = 留存 + 销毁 −（离开网络 + 世界收集 − 进入网络）
     * </pre>
     *
     * @param live     此刻舱内（本仓边界内）该资源的真实存量
     * @param baseLive 该资源第一次被记账时的存量基线（读档 / 重启不会制造假差额）
     */
    public static Row rowOf(final String key, final long fromNetwork, final long toNetwork,
                            final long toMachine, final long fromMachine, final long fromWorld,
                            final long destroyed, final long live, final long baseLive) {
        final long retained = (live - baseLive) + toMachine - fromMachine;
        final long unaccounted = retained + destroyed
            - (fromNetwork + fromWorld - toNetwork);
        return new Row(key, fromNetwork, toNetwork, toMachine, fromMachine, fromWorld, destroyed,
            live, retained, unaccounted);
    }

    /**
     * <b>把差额定位到具体搬运路径</b>（纯函数，自检直接跑它）。
     *
     * <p>恒等式：{@code 未对平 = 舱内Δ + 机器Δ + 销毁 + 网络净额}，其中
     * {@code 网络净额 = 进入网络 − 离开网络 − 世界收集}、{@code 机器Δ = 推给机器 − 从机器取回}。
     * 于是「哪一段能吞下这笔差额」就等价于「这笔差额只可能落在哪条路径上」，而不是一句
     * {@code 可能来源: external_or_baseline} 让玩家自己猜。</p>
     *
     * @return {@code balanced} = 对平；其余取值见 {@link #explain(String)}
     */
    public static String verdictOf(final Row row) {
        final long diff = row.unaccounted();
        if (diff == 0L) {
            return "balanced";
        }
        // 四个能「吞下」差额的桶（它们的和恒等于 diff）
        final long chamber = row.retained() - row.toMachine() + row.fromMachine();
        final long machine = row.toMachine() - row.fromMachine();
        final long networkNet = row.toNetwork() - row.fromNetwork() - row.fromWorld();
        final long destroyed = row.destroyed();
        if (diff > 0L) {
            if (chamber > 0L) {
                return "retained_in_chamber";
            }
            if (machine > 0L) {
                return "station_return_unbooked";
            }
            if (networkNet > 0L) {
                return "network_in_unbooked";
            }
            if (destroyed > 0L) {
                return "destroyed_unexplained";
            }
            return "created_from_nowhere";
        }
        if (chamber < 0L) {
            return "lost_from_chamber";
        }
        if (machine < 0L) {
            return "machine_over_reclaimed";
        }
        if (networkNet < 0L) {
            return "lost_after_leaving_network";
        }
        if (destroyed > 0L) {
            return "destroyed_miscount";
        }
        return "deficit_unexplained";
    }

    /** {@link #verdictOf(Row)} 的中文解释（日志用；一条判决对应一句「该查哪里」）。 */
    public static String explain(final String verdict) {
        return switch (verdict) {
            case "balanced" -> "对平";
            case "retained_in_chamber" -> "差额落在「舱内存量」：多出来的还压在本仓存储里（跨步保留 / "
                + "未记账的进料：外部插入、别的模组、或读档基线漂移）";
            case "station_return_unbooked" -> "差额落在「推给机器」：这一份推给工位后离开了工位，"
                + "却没有配对的「从机器取回 / 入网」记账（工位侧残留回流漏记）";
            case "network_in_unbooked" -> "差额落在「进入网络」：入网量比记账多（入网侧漏记）";
            case "destroyed_unexplained" -> "差额落在「销毁」：销毁记账与差额对不上";
            case "created_from_nowhere" -> "凭空多出：既不在舱内、也不在机器上、也没有销毁记账 ⇒ 疑似复制";
            case "lost_from_chamber" -> "舱内比记账少：这一份离开了本仓、却没有任何出网 / 销毁记账 ⇒ "
                + "真丢失（或被本模组之外的路径取走）";
            case "machine_over_reclaimed" -> "从机器取回多于推给机器 ⇒ 重复记账 / 怀疑复制";
            case "lost_after_leaving_network" -> "离开网络之后既不在舱内也不在机器上、且无销毁记账 ⇒ "
                + "真丢失（或被本模组之外的路径取走）";
            case "destroyed_miscount" -> "销毁记账比实际多";
            default -> "差额来源无法从四段里定位（公式被改过？）";
        };
    }

    /**
     * 一条失衡的<b>路径拆解</b>：把「进 / 出 / 销毁 / 回收 / 舱内」四段数字与判决放在同一行。
     * <p>用户实机日志里原本只有 {@code item:X=1} 这样一个数字，看不出是哪条搬运路径错 ——
     * 这条拆解就是为了让下一份日志能直接指向路径，而不是再猜一次。</p>
     */
    public static String breakdown(final Row row) {
        final String verdict = verdictOf(row);
        final long chamber = row.retained() - row.toMachine() + row.fromMachine();
        final long machine = row.toMachine() - row.fromMachine();
        final long networkNet = row.toNetwork() - row.fromNetwork() - row.fromWorld();
        return row.key()
            + " 差额=" + row.unaccounted()
            + " 路径[离开网络=" + row.fromNetwork()
            + " 进入网络=" + row.toNetwork()
            + " 世界收集=" + row.fromWorld()
            + " 推给机器=" + row.toMachine()
            + " 从机器取回=" + row.fromMachine()
            + " 销毁=" + row.destroyed()
            + " 舱内=" + row.live() + " 舱内存量基线外=" + chamber
            + " 网络净额=" + networkNet + " 机器Δ=" + machine + "]"
            + " 判定=" + verdict + "（" + explain(verdict) + "）";
    }

    /**
     * <b>不满足守恒不变量的条目</b>（{@code 未对平 != 0}）—— 用户要求「任何不满足的条目直接列出」。
     * <p>只读。差额的可能来源见类注释（本模组之外的搬运 / 基线），因此调用方只应把它当诊断线索。</p>
     */
    public List<Row> unbalanced() {
        final List<Row> result = new ArrayList<>(0);
        for (final Row row : rows()) {
            if (row.unaccounted() != 0L) {
                result.add(row);
            }
        }
        return result;
    }

    /** 一行可读账目：{@code item:create:sturdy_sheet in=0 out=2 machine=2 live=0 未对平=0}。 */
    public static String describe(final Row row) {
        return row.key()
            + " 离开网络=" + row.fromNetwork()
            + " 进入网络=" + row.toNetwork()
            + " 推给机器=" + row.toMachine()
            + " 从机器取回=" + row.fromMachine()
            + " 世界收集=" + row.fromWorld()
            + " 销毁=" + row.destroyed()
            + " 留存=" + row.retained()
            + " 未对平=" + row.unaccounted();
    }

    /** 诊断导出用：全部账目 + 未对平条目（可直接进 JSON）。 */
    public Map<String, Object> report() {
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<Object> all = new ArrayList<>();
        for (final Row row : rows()) {
            final Map<String, Object> one = new LinkedHashMap<>();
            one.put("res", row.key());
            one.put("fromNetwork", row.fromNetwork());
            one.put("toNetwork", row.toNetwork());
            one.put("toMachine", row.toMachine());
            one.put("fromMachine", row.fromMachine());
            one.put("fromWorld", row.fromWorld());
            one.put("destroyed", row.destroyed());
            one.put("live", row.live());
            one.put("retained", row.retained());
            one.put("unaccounted", row.unaccounted());
            all.add(one);
        }
        out.put("cells", all);
        final List<Object> bad = new ArrayList<>();
        for (final Row row : unbalanced()) {
            // 每一条失衡都带「路径拆解 + 判决」：导出 JSON 的人不需要自己再算一遍四段数字
            bad.add(breakdown(row));
        }
        out.put("unbalanced", bad);
        return out;
    }

    // ==================== 运行期审计日志（本轮新增：账本此前「一个字都不写」） ====================

    /** 审计日志的统一前缀（便于 grep：{@code [rscc-ledger]}）。 */
    public static final String PREFIX = "[rscc-ledger]";
    /** 「未对平」在被判定为「持续」之前，允许连续出现多少次采样。 */
    private static final int SUSTAINED_SAMPLES = 3;
    /** 同一台机器的同一条失衡，两次「重复合并」之间的最短间隔（毫秒）。 */
    private static final long REPEAT_WINDOW_MILLIS = 30_000L;
    /** 审计状态表软上限（键 = 来源坐标，正常远小于它；超限整表清空，避免无界增长）。 */
    private static final int AUDIT_TABLE_LIMIT = 512;

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccFlowLedger.class);

    /**
     * 一台机器的审计状态（静态表，按来源键；方块实体卸载后条目很快因「无失衡」被复位）。
     */
    private static final class Audit {
        /** 上一次采样的失衡签名（排序后的 {@code 资源=差}，对平 = 空串）。 */
        String signature = "";
        /** 该签名连续出现了多少次采样。 */
        int samples;
        /** 该签名是否已经「正式报过」（持续失衡只合并、不重复刷屏）。 */
        boolean reported;
        /** 该签名上一次「重复合并」输出的时刻（毫秒）。 */
        long lastReportMillis;
        /** 该签名下累计被合并掉的采样次数。 */
        long suppressed;
        /** 本会话见过的最大单条差额绝对值（含瞬时尖峰，用于「是不是我漏看了」的自证）。 */
        long peak;
        /** 尖峰对应的资源键（{@code peak} 的解释）。 */
        String peakKey = "";
    }

    private static final Map<String, Audit> AUDITS = new HashMap<>();

    /**
     * <b>周期性审计</b>（只读；由执行舱每秒调用一次）：把「未对平」变成一定会出现在日志里的事实。
     *
     * <h2>为什么必须有它（上一轮取证的致命盲区）</h2>
     * <p>本类此前<b>没有任何 Logger</b>：{@link #unbalanced()} 只被 {@code /rs_create_compat diag}
     * 的导出读取，而那份导出停在 2026-10-02。于是「运行日志里没有失衡告警」这句结论
     * 其实是「<b>根本没有写日志</b>」，不是「没有失衡」—— 一个守恒不变量在整场会话里
     * 从来没有被观测过一次。</p>
     *
     * <h2>输出规则（稳态零输出、异常必可见）</h2>
     * <ol>
     *     <li><b>对平</b>（没有任何条目 {@code 未对平 != 0}）：不打任何行；若上一次是失衡，
     *     打一条 {@code balanced ... （恢复）} 收尾 —— 「失衡已结束」与「从未失衡」必须能分辨；</li>
     *     <li><b>首次失衡</b>：立刻一条 {@code unbalanced ... （first）}（不用等任何窗口，
     *     玩家跑一次就能看到）；</li>
     *     <li><b>持续失衡</b>：同一签名连续出现 &lt; {@value #SUSTAINED_SAMPLES} 次时只累加计数
     *     （瞬时抖动不刷屏）；达到后打一条 {@code sustained}；之后每
     *     {@value #REPEAT_WINDOW_MILLIS} 毫秒最多合并一条 {@code （same imbalance repeated n times）}；</li>
     *     <li><b>签名变化</b>（资源 / 方向 / 差额变了）＝ 新的失衡，重新走第 2 步；</li>
     *     <li>另有 {@code peak=} 字段记录本会话见过的最大差额 —— 即使某个尖峰在两次采样之间
     *     自我恢复，它也留在日志里（否则「瞬时打印了两份」这类错误永远无法观测）。</li>
     * </ol>
     *
     * <p><b>只读保证</b>：本方法只调用 {@link #rows()}（内存里的计数器 + 只读活量探针），
     * 绝不写任何容器、绝不改任何任务状态，也<b>不被任何判定读取</b>（与类注释的承诺一致）。</p>
     *
     * @param origin  来源标识（执行舱用 {@code chamber@(x,y,z)}）；必须在同一台机器上稳定不变
     * @param millis  当前时刻（毫秒，通常 {@code System.currentTimeMillis()}）
     */
    public void tickAudit(final String origin, final long millis) {
        if (origin == null || origin.isEmpty()) {
            return;
        }
        final List<Row> bad = unbalanced();
        final Audit audit = AUDITS.get(origin);
        if (bad.isEmpty()) {
            if (audit != null && (audit.reported || audit.samples > 0)) {
                // 开发诊断（INFO）：受 devLogs 总开关控制；下面的 sustained 是 WARN，属于必要日志，始终输出。
                if (RsccAssemblyDebug.isEnabled()) {
                    LOGGER.info("{} {} balanced again (was {} sample(s) of imbalance, peak={} on {})",
                        PREFIX, origin, audit.suppressed + audit.samples, audit.peak,
                        audit.peakKey.isEmpty() ? "-" : audit.peakKey);
                }
            }
            if (audit != null) {
                AUDITS.remove(origin);
            }
            return;
        }
        final String signature = signatureOf(bad);
        final Audit state = audit != null ? audit : new Audit();
        if (AUDITS.size() >= AUDIT_TABLE_LIMIT && audit == null) {
            AUDITS.clear();
        }
        AUDITS.put(origin, state);
        // 尖峰记录：即使签名马上变化，最大值也留在日志里（「瞬时多打了一份」的唯一可观测痕迹）
        for (final Row row : bad) {
            if (Math.abs(row.unaccounted()) > Math.abs(state.peak)) {
                state.peak = row.unaccounted();
                state.peakKey = row.key();
            }
        }
        if (!signature.equals(state.signature)) {
            state.signature = signature;
            state.samples = 1;
            state.reported = false;
            state.suppressed = 0L;
            state.lastReportMillis = millis;
            // 开发诊断（INFO）：受 devLogs 总开关控制。
            if (RsccAssemblyDebug.isEnabled()) {
                LOGGER.info("{} {} unbalanced (first) peak={} on {} :: {} || {}",
                    PREFIX, origin, state.peak, state.peakKey, signature, breakdownOf(bad));
            }
            return;
        }
        state.samples++;
        if (!state.reported && state.samples >= SUSTAINED_SAMPLES) {
            state.reported = true;
            state.lastReportMillis = millis;
            // 【第 95 轮】持续失衡的那一条必须能<b>指向具体搬运路径</b>：
            // 旧稿只有 `资源=差额`（例如 `item:create:incomplete_precision_mechanism=1`），
            // 用户实机日志里连续刷了 2 分半，却完全看不出是「进 / 出 / 销毁 / 回收」哪一段错 ——
            // 只能靠事后读代码猜。现在把四段数字 + 判决（含中文解释）接在同一行。
            LOGGER.warn("{} {} unbalanced (sustained {} samples) peak={} on {} :: {} || {}",
                PREFIX, origin, state.samples, state.peak, state.peakKey, signature, breakdownOf(bad));
            return;
        }
        state.suppressed++;
        if (millis - state.lastReportMillis >= REPEAT_WINDOW_MILLIS) {
            // 开发诊断（INFO）：受 devLogs 总开关控制。
            if (RsccAssemblyDebug.isEnabled()) {
                LOGGER.info("{} {} unbalanced (same imbalance repeated {} times, peak={} on {}) :: {} || {}",
                    PREFIX, origin, state.suppressed, state.peak, state.peakKey, signature,
                    breakdownOf(bad));
            }
            state.lastReportMillis = millis;
            state.suppressed = 0L;
        }
    }

    /** 一次采样里最多列出几条失衡的路径拆解（其余折成「另有 N 条」，避免一行日志刷出一屏）。 */
    private static final int MAX_BREAKDOWN_ROWS = 4;

    /** 把本次采样的失衡条目折成「路径拆解」串（与 {@link #breakdown(Row)} 同一份实现）。 */
    private static String breakdownOf(final List<Row> bad) {
        final StringBuilder sb = new StringBuilder(320);
        int shown = 0;
        for (final Row row : bad) {
            if (shown >= MAX_BREAKDOWN_ROWS) {
                sb.append(" | ...另有 ").append(bad.size() - shown).append(" 条失衡未列出");
                break;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            sb.append(breakdown(row));
            shown++;
        }
        return sb.toString();
    }

    /** 失衡签名：{@code 资源=差额}（按资源键排序，保证同一状态两次采样逐字节一致，自身可 diff）。 */
    private static String signatureOf(final List<Row> rows) {
        final List<String> parts = new ArrayList<>(rows.size());
        for (final Row row : rows) {
            parts.add(row.key() + "=" + row.unaccounted());
        }
        java.util.Collections.sort(parts);
        return String.join(", ", parts);
    }
}

