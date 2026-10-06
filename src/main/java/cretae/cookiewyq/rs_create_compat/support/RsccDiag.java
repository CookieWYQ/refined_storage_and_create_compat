package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「一次操作 → 全部证据」的诊断核心（服务端权威、<b>只读</b>）。
 *
 * <h2>它解决什么</h2>
 * 用户明确表示：<i>「电脑不在我旁边，你先继续完善日志工作，确保我待会运行时只需要执行一次操作，
 * 你就可以掌握所有问题所在。」</i> 因此本类做三件事：
 * <ol>
 *     <li><b>默认采集</b>：钩在 {@link RsccAssemblyDebug} 的既有输出通道上（该通道默认开启），
 *     把「推 / 收 / 回流 / 机满 / 本步不要」等关键事件按「坐标 + 资源」聚合计数，
 *     并保留最近 N 条明细 —— <b>不需要用户敲任何开关指令</b>；</li>
 *     <li><b>一键快照</b>：{@code /rs_create_compat diag} 把「当前全部相关状态」导出成结构化
 *     {@code run/rscc_diag/<yyyyMMdd-HHmmss>/snapshot.json}（八组字段，见 {@link #snapshot}）；</li>
 *     <li><b>分节锚点</b>：导出时在日志里打 {@value #PREFIX} BEGIN/…/END 标记，便于文本侧解析。</li>
 * </ol>
 *
 * <h2>为什么是只读</h2>
 * 本类<b>绝不</b>抽取 / 写入 / 销毁任何物品或流体，也<b>绝不</b>改变任何任务状态、策略档位或方块数据；
 * 它只做「读 + 记录 + 落盘」。因此可以在任何时刻安全调用（含产线运行中）。
 *
 * <h2>不刷屏</h2>
 * 计数器只在内存里累加，<b>不</b>额外打日志；唯一的新增日志行是「导出时的分节标记」与
 * 「服务器启动时的锚点行」{@code [rscc] diag logging ON (default)}（各一条）。
 * <p>注意：喂给计数表的事件来自 {@link RsccAssemblyDebug} 的输出通道，而该通道自 2026-10-06 起
 * 默认关闭（配置 {@code devLogs=false}）⇒ 关闭状态下计数表不会有新数据；但
 * {@code /rs_create_compat diag run} 会先把开发日志打开再导出，一键诊断能力不受影响。</p>
 * 快照文件本身按<b>固定插入顺序 + 显式排序</b>写出，同一状态两次导出除时间戳外逐字节一致。
 */
public final class RsccDiag {
    /** 分节标记前缀（便于文本侧 grep / 解析）。 */
    public static final String PREFIX = "[rscc-diag]";
    /** 启动锚点前缀（证明「默认就在采集」）。 */
    public static final String ANCHOR_PREFIX = "[rscc]";
    /** 相对游戏目录的导出子目录名。 */
    public static final String DIR_NAME = "rscc_diag";
    /** 最近事件环形缓冲容量（每段订单通常远小于此）。 */
    private static final int RECENT_LIMIT = 256;

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccDiag.class);

    private static final DateTimeFormatter DIR_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ---- 解析用的正则（全部只读、无副作用） ----
    private static final Pattern P_ITEM = Pattern.compile("\\bitem=([a-z_0-9:.]+)");
    private static final Pattern P_FLUID = Pattern.compile("\\bfluid=([a-z_0-9:.]+)");
    private static final Pattern P_AMOUNT = Pattern.compile("\\bx(\\d+)");
    private static final Pattern P_EVENT = Pattern.compile("\\bevent=([A-Za-z_0-9]+)");
    private static final Pattern P_REASON = Pattern.compile("\\breason=([A-Za-z_0-9]+)");
    private static final Pattern P_POS = Pattern.compile("(?:chamber|machine|exporter|importer)@\\((-?\\d+),(-?\\d+),(-?\\d+)\\)");

    /** 诊断总开关（默认 true；不依赖任何指令，重开服务器也是开）。 */
    private static volatile boolean enabled = true;
    /** 本会话的启动时刻（锚点行 / 快照 meta 用）。 */
    private static volatile String sessionStartedAt = "-";
    private static volatile long sessionStartedMillis;

    /** 计数：{@code pos|res|kind -> [count]}（服务端权威；只在内存里累加，永不主动打日志）。 */
    private static final Map<String, long[]> COUNTERS = new LinkedHashMap<>();
    /** 最近事件明细（插入顺序 = 时间顺序；环形，超出丢最旧）。 */
    private static final Deque<String[]> RECENT = new ArrayDeque<>();
    /** 横幅历史：{@code kind|key -> [sent, suppressedDeduped]}。 */
    private static final Map<String, long[]> BANNERS = new LinkedHashMap<>();

    // ---- 伪装显示开关（K 键）的最近一次切换 ----
    private static long revealToggleCount;
    private static int revealLastPositions = -1;
    private static long revealLastTick = -1L;

    private RsccDiag() {
    }

    // ==================== 开关 / 锚点 ====================

    /** 诊断是否开启（默认 true）。调用点用它守卫昂贵或敏感的采集动作。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 服务器启动时调用一次：记录会话起点并打一条<b>可核对的锚点行</b>。
     * <p>这条行的唯一用途是让「默认就在采集」这件事实可被日志事实验证 ——
     * 用户不需要敲 {@code /rs_create_compat assemblydebug on}。</p>
     */
    public static void onServerStarted(final MinecraftServer server) {
        sessionStartedAt = LocalDateTime.now().format(ISO);
        sessionStartedMillis = System.currentTimeMillis();
        // 构建指纹（唯一一行 [rscc-build]）：让「这份日志是哪一版代码跑出来的」可自证。
        // 为什么放在这里第一句：它必须出现在会话起点之前，之后任何一行 [rscc-assembly] 才能被归属到
        // 确定的 revision（见 TECHNICAL_HANDOFF.md §7.3 结论 ⑥：旧日志无 git hash / 编译时间，
        // 只能靠 mtime 猜，于是把一份晚于修复代码的日志误当成了验证证据）。
        RsccBuildInfo.logOnce();
        // 锚点行保持原文开头不变（tools/verify_build_stamp.py 用它定位会话起点），末尾追加 devLogs 实际档位：
        // 「采集」默认是开的（本类只累加计数、不刷日志），但「开发日志输出」默认是关的 —— 两者必须能分辨。
        LOGGER.info("{} diag logging ON (default) session={} exportDir={} devLogs={}",
            ANCHOR_PREFIX, sessionStartedAt, exportRoot(),
            RsccAssemblyDebug.isEnabled() ? "on" : "off");
        // 把「当前缺料处置档位」默认落一条日志（用户不敲任何指令也能在日志里核对 ⑧ 档位与行为是否一致）。
        try {
            LOGGER.info("{} shortage-mode={} (session default, source=save)",
                ANCHOR_PREFIX, RsccShortagePolicy.get(server).getMode().id());
        } catch (final RuntimeException ignored) {
            // 服务器尚未准备好 overworld 数据存储：跳过（绝不影响启动）
        }
        // 把「补合成请求量档位」的默认档也落一条锚点日志（用户第 1 / 2 条：不敲指令也能在日志里核对；
        // 第三档新增后打的是档位自己的 id：off / gap / machines）。
        try {
            LOGGER.info("{} refill={} (session default, source=save)",
                ANCHOR_PREFIX, RsccRefillPolicy.get(server).getMode().id());
        } catch (final RuntimeException ignored) {
            // 同上：跳过，绝不影响启动
        }
    }

    // ==================== 采集入口（由 RsccAssemblyDebug 的既有通道驱动） ====================

    /**
     * 观察一条既有的诊断正文，按「坐标 + 资源」聚合计数并保留最近明细。
     * <p>由 {@link RsccAssemblyDebug} 的 {@code event / trace / reject / repeat / warn} 统一调用，
     * 因此覆盖了执行舱、输出 / 输入总线、看门狗的所有关键事件，而<b>无需逐点改调用方</b>。</p>
     */
    public static void observe(final String body) {
        if (!enabled || body == null || body.isEmpty()) {
            return;
        }
        final String kind = kindOf(body);
        if (kind == null) {
            return;
        }
        final String resource = resourceOf(body);
        final long amount = amountOf(body);
        final Matcher pos = P_POS.matcher(body);
        final String where = pos.find() ? "(" + pos.group(1) + "," + pos.group(2) + "," + pos.group(3) + ")" : "-";
        final String key = where + "|" + resource + "|" + kind;
        final long[] cell = COUNTERS.computeIfAbsent(key, ignored -> new long[1]);
        cell[0] += Math.max(1L, amount);
        if (RECENT.size() >= RECENT_LIMIT) {
            RECENT.pollFirst();
        }
        RECENT.addLast(new String[]{LocalDateTime.now().format(ISO), where, directionOf(kind, pos.groupCount() > 0, body),
            resource, Long.toString(Math.max(1L, amount)), kind});
    }

    /** 记录一次横幅发送（kind = 横幅族，key = 去重键）。 */
    public static void recordBanner(final String kind, final String key) {
        if (!enabled) {
            return;
        }
        BANNERS.computeIfAbsent(kind + "|" + key, ignored -> new long[2])[0]++;
    }

    /**
     * 记录一次「横幅被去重吞掉」：同一卡住条件已弹过横幅，后续扫描不再重复弹。
     * <p>这正是用户关心的「被去重吞掉的次数」—— 它证明「条件一直存在但只提示了一次」。</p>
     */
    public static void recordBannerSuppressed(final String kind, final String key) {
        if (!enabled) {
            return;
        }
        BANNERS.computeIfAbsent(kind + "|" + key, ignored -> new long[2])[1]++;
    }

    /** 记录一次伪装显示开关（K 键）的切换（{@code affected} = 受影响格数）。 */
    public static void recordRevealToggle(final int affected, final long gameTime) {
        if (!enabled) {
            return;
        }
        revealToggleCount++;
        revealLastPositions = affected;
        revealLastTick = gameTime;
    }

    /** 只读：该维度当前被伪装（套壳）的格子总数（供 K 键切换时记录「受影响格数」）。 */
    public static int camouflagedCount(final ServerLevel level) {
        return level == null ? -1 : countCamouflaged(level);
    }

    // ==================== 一键快照 ====================

    /** 导出结果（供指令回执 / 自检）。 */
    public record Result(Path path, int summaryLines, int chambers, int buses, int counters) {
    }

    /**
     * 把「当前全部相关状态」导出为结构化快照（八组字段）：
     * <ol>
     *     <li>{@code chambers} —— 所有执行舱（位置 / 配方 / 类别 / 在制 / 原料五量）；</li>
     *     <li>{@code buses} —— 所有总线（位置 / 归属 / 布局 / 强制普通 / 可见类别与已勾选）；</li>
     *     <li>{@code counters} + {@code recentEvents} —— 按坐标 + 资源的近期计数与最近推 / 收明细；</li>
     *     <li>{@code shortage} —— 缺料策略档位 / 挂起记录 / 横幅历史（含被去重吞掉的次数）；</li>
     *     <li>{@code conservation} —— 每 (坐标, 资源) 的「进入 = 离开 + 留存」汇总与不平账条目；</li>
     *     <li>{@code keepers} —— 定量保持器仲裁（谁权威 / 谁让位）/ 过量销毁开关与销毁计数；</li>
     *     <li>{@code camouflage} —— 伪装格数量与 K 键最近一次切换（受影响格数）；</li>
     *     <li>{@code recipes} —— 每条在用配方的四类分类（原料 / 输入时原料 / 成品 / 废料 / 中间产物）。</li>
     * </ol>
     * <b>只读</b>：只调用公开只读 API，不改变任何游戏状态。
     */
    public static Result snapshot(final MinecraftServer server, final String tag) {
        final LocalDateTime now = LocalDateTime.now();
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta(server, tag, now));

        final List<Object> chambers = new ArrayList<>();
        final List<Object> buses = new ArrayList<>();
        final List<Object> keepers = new ArrayList<>();
        final List<Object> conservationLedger = new ArrayList<>();
        // 2026-10-05 新增：**所有机器**的完整状态 + 所有容器的内容 + 网络级总量。
        // 用户要求：「导出当前网络所有这些机子里面的内部数据，比如说某台机子我对它的设置」
        // —— 目的就是让「玩家复述」不再参与定位（复述与现场不一致时会直接导致修错方向）。
        final List<Object> machines = new ArrayList<>();
        final List<Object> containers = new ArrayList<>();
        final List<Object> universalStorage = new ArrayList<>();
        final List<Object> networks = new ArrayList<>();
        final List<Object> inventory = new ArrayList<>();
        int camouflagedTotal = 0;

        for (final ServerLevel level : server.getAllLevels()) {
            camouflagedTotal += countCamouflaged(level);
            for (final BlockEntity be : blockEntities(level)) {
                if (be instanceof SequenceExecutionChamberBlockEntity chamber) {
                    chambers.add(chamber.rscc$diagReport());
                } else if (be instanceof RsccExporterExecutorMode exporter) {
                    buses.add(exporterReport(level, be.getBlockPos(), exporter, true));
                } else if (be instanceof RsccImporterExecutorMode importer) {
                    buses.add(importerReport(level, be.getBlockPos(), importer, false));
                } else if (be instanceof QuantityKeeperBlockEntity keeper) {
                    keepers.add(keeperReport(level, be.getBlockPos(), keeper));
                } else if (be instanceof AdvancedQuantityKeeperBlockEntity keeper) {
                    keepers.add(advancedKeeperReport(level, be.getBlockPos(), keeper));
                }
                machines.add(machineReport(level, be));
                final Object container = containerReport(level, be);
                if (container != null) {
                    containers.add(container);
                }
            }
        }
        sortListOfMaps(chambers, "pos");
        sortListOfMaps(buses, "pos");
        sortListOfMaps(keepers, "pos");
        sortListOfMaps(machines, "pos");
        sortListOfMaps(containers, "pos");

        // 玩家背包（默认只记「本模组的终端 / 磁盘 / 样板」这些与故障相关的格，避免整包噪声）
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            inventory.add(playerInventory(player));
        }
        // 网络级总量：把「所有含通用盘的网络」的存量逐项列出。这是「网络里到底有多少料」的权威答案，
        // 不再需要玩家目视终端后复述（复述错一次就够把排查带偏）。
        try {
            universalStorage.addAll(universalStorageView(server));
        } catch (final RuntimeException ignored) {
            // 取不到就留空：诊断绝不能因为某一类机器实现变化而整体失败
        }

        root.put("chambers", chambers);
        root.put("buses", buses);
        root.put("machines", machines);
        root.put("containers", containers);
        root.put("universalStorage", universalStorage);
        root.put("playerInventory", inventory);
        root.put("counters", countersView());
        root.put("recentEvents", recentView());

        final Map<String, Object> shortage = new LinkedHashMap<>();
        shortage.put("mode", server.overworld() == null ? "suspend"
            : RsccShortagePolicy.get(server).getMode().id());
        shortage.put("suspended", suspendedView(server));
        shortage.put("banners", bannersView());
        root.put("shortage", shortage);

        // <b>2026-10-06 本轮新增：补合成请求量档位</b>（用户原话「我不确定它有没有正常发挥」）。
        // 为什么必须有它：此前快照里完全没有这一档的字段，「指令 / 界面到底生效没有」只能靠翻
        // 默认日志里的 `[rscc] refill=…` 那一行。现在它与 shortage.mode 并列，导出快照即可核对；
        // 每个执行舱「读到几台机器、本轮请求多少」则在该仓的 refill_batch 日志行里。
        final Map<String, Object> refill = new LinkedHashMap<>();
        refill.put("mode", server.overworld() == null ? "off"
            : RsccRefillPolicy.get(server).getMode().id());
        root.put("refill", refill);

        root.put("conservation", conservationView(chambers));
        root.put("keepers", keepersView(keepers));
        root.put("camouflage", camouflageView(camouflagedTotal));
        root.put("recipes", recipesView(chambers));
        // <b>2026-10-05 新增：中间产物缓存池的观测</b>。
        // 此前快照里<b>完全没有</b>缓存仓的字段，导致「插了盘但占用一直是 0」这件事
        // 只能靠日志猜 —— 而日志又是按需写的。现在它每张快照都带着。
        root.put("cachePool", cachePoolView(server));
        // <b>2026-10-06 本轮新增：自动合成任务现状（TaskStatus 原样 dump + 已交付量）</b>。
        // 为什么必须补：本轮根因是「已交付量被读成 0」，而此前快照里<b>完全没有任务这一节</b>
        // ⇒ 只能靠推理。现在 tasks[].delivered 与 tasks[].items[].stored/crafting 并排，
        // 「delivered 是否恒 0」一眼可证（详见 tasksView 的 javadoc）。
        root.put("tasks", tasksView(server));

        // ---- 落盘 ----
        final String text = RsccDiagJson.write(root);
        final Path dir = exportRoot().resolve(now.format(DIR_STAMP));
        final Path file = dir.resolve("snapshot.json");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            final Path latest = exportRoot().resolve("latest");
            Files.createDirectories(latest);
            Files.writeString(latest.resolve("snapshot.json"), text, StandardCharsets.UTF_8);
        } catch (final Exception exception) {
            LOGGER.warn("{} export failed: {}", PREFIX, exception.toString());
            return new Result(file, 0, chambers.size(), buses.size(), COUNTERS.size());
        }
        final int summaryLines = text.split("\n", -1).length;
        // 分节标记（用户要求带固定前缀，便于文本侧解析）。除这三行 + 聊天栏一行外，
        // /rs_create_compat diag <b>不再写任何日志</b>（不刷屏）。
        LOGGER.info("{} BEGIN tag={} chambers={} buses={} machines={} containers={} keepers={} "
                + "universalStorage={} players={} counters={} lines={} path={}",
            PREFIX, tag, chambers.size(), buses.size(), machines.size(), containers.size(),
            keepers.size(), universalStorage.size(), inventory.size(), COUNTERS.size(), summaryLines, file);
        LOGGER.info("{} groups=chambers,buses,machines,containers,universalStorage,playerInventory,"
                + "counters,recentEvents,shortage,conservation,keepers,camouflage,recipes,cachePool,tasks",
            PREFIX);
        LOGGER.info("{} END tag={}", PREFIX, tag);
        return new Result(file, summaryLines, chambers.size(), buses.size(), COUNTERS.size());
    }

    // ==================== 2026-10-05 新增：全机器 / 全容器 / 玩家 / 通用盘导出 ====================

    /**
     * 一台机器的完整状态（<b>通用兜底</b>：任何方块实体都有一条）。
     *
     * <h2>为什么必须「通用」而不是逐个加方法</h2>
     * <p>用户要求「导出当前网络所有这些机子里面的内部数据，比如说某台机子我对它的设置」。
     * 若只给「已知要查的那几种机器」加导出，那么下一次问题的现场照样会漏 —— 而这正是
     * 「玩家复述 ⇒ 复述错 ⇒ 修错方向」的成因。因此这里做两件事：</p>
     * <ol>
     *     <li>凡是显式实现了 {@link RsccDiagnosable} 的机器 → 用它的<b>自述</b>（字段最全）；</li>
     *     <li>其余一律列出「方块 id + 完整 NBT」—— NBT 是<b>存档里的真实设置</b>，
     *     包含了面模式、绑定、队列、升级槽、目标量等全部配置，且永远与本版本的写入逻辑一致；</li>
     *     <li>两类都统一列出容器槽位内容（见 {@link #containerReport}）。</li>
     * </ol>
     * <p>只读：{@code saveWithoutMetadata} 不修改任何状态（它只读字段并组装 NBT）。</p>
     */
    private static Map<String, Object> machineReport(final ServerLevel level, final BlockEntity be) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final BlockPos pos = be.getBlockPos();
        out.put("pos", key(pos));
        out.put("dimension", level.dimension().location().toString());
        out.put("block", String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(level.getBlockState(pos).getBlock())));
        out.put("type", be.getType() == null ? "-" : String.valueOf(
            net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
        out.put("owner", be.getClass().getSimpleName());
        if (be instanceof RsccDiagnosable diagnosable) {
            try {
                out.put("selfReport", diagnosable.rscc$diagReport());
            } catch (final RuntimeException exception) {
                out.put("selfReport", "error: " + exception);
            }
        }
        out.put("nbt", nbtOf(level, be));
        return out;
    }

    /**
     * 方块实体的 NBT（<b>截断到 4000 字符</b>）。
     * <p>截断是刻意的：全量 NBT 在大机器上可以有几十 KB，快照会迅速膨胀到无法阅读。
     * 4000 字符足以覆盖「设置类」字段（数量总在 NBT 的前部，槽位内容另有 {@link #containerReport} 精确列出）。</p>
     */
    private static String nbtOf(final ServerLevel level, final BlockEntity be) {
        try {
            final String text = be.saveWithoutMetadata(level.registryAccess()).toString();
            return text.length() <= 4000 ? text : text.substring(0, 4000) + "…(截断)";
        } catch (final RuntimeException exception) {
            return "error: " + exception;
        }
    }

    /**
     * 这个方块实体里<b>所有能读到的物品槽</b>（只列非空槽 + 每个槽组的总容量）。
     *
     * <h2>⚠ 为什么不能只判 {@code instanceof Container}（2026-10-05 实机踩到）</h2>
     * <p>第一版写成「{@code be instanceof Container} 才导出」，实机快照结果是
     * <b>{@code machines=160 而 containers=0}</b> —— 因为本模组（以及 RS）的机器<b>并不是</b>原版
     * {@code Container}：{@code SchematicLoaderBlockEntity#getInventory()} 返回 NeoForge 的
     * {@code ItemStackHandler}，{@code QuantityKeeperBlockEntity#getInventory()} 返回
     * {@code SimpleContainer}；它们只是<b>持有</b>一个容器对象，自身不是容器。
     * 于是「导出每台机器的内部数据」这条要求恰好在那几台最需要它的机器上完全落空。</p>
     *
     * <p>现在的口径：① {@code be instanceof Container} → 直接读；② 否则用<b>反射</b>枚举该方块实体的
     * 公开无参方法，凡是返回 {@code ItemStackHandler} 或 {@code Container}（含其子类）的都当作一个
     * 「槽组」列出，方法名即组名（{@code inventory} / {@code queue} / {@code upgradeContainer}…）。
     * 反射在这里是<b>刻意</b>的：新加机器时不必再改诊断代码 —— 否则「导出覆盖面」永远落后于功能，
     * 而漏掉的那台机器恰好就是下一次要靠玩家复述的那台。</p>
     *
     * <p>只读：只调用 getter 与 {@code getStackInSlot} / {@code getItem}，不写任何槽。</p>
     */
    @Nullable
    private static Object containerReport(final ServerLevel level, final BlockEntity be) {
        final java.util.Map<String, Object> groups = new LinkedHashMap<>();
        if (be instanceof Container container) {
            final Object group = slotGroup("container", container.getContainerSize(),
                index -> container.getItem(index));
            if (group != null) {
                groups.put("container", group);
            }
        }
        for (final java.lang.reflect.Method method : be.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
                continue;
            }
            final String name = method.getName();
            if (!name.startsWith("get")) {
                continue;
            }
            final Class<?> type = method.getReturnType();
            final boolean itemHandler = net.neoforged.neoforge.items.ItemStackHandler.class
                .isAssignableFrom(type);
            final boolean vanillaContainer = Container.class.isAssignableFrom(type);
            if (!itemHandler && !vanillaContainer) {
                continue;
            }
            try {
                final Object value = method.invoke(be);
                if (value == null || value == be) {
                    continue;
                }
                final Object group;
                if (value instanceof net.neoforged.neoforge.items.ItemStackHandler handler) {
                    group = slotGroup(decap(name), handler.getSlots(), handler::getStackInSlot);
                } else if (value instanceof Container vanilla) {
                    group = slotGroup(decap(name), vanilla.getContainerSize(), vanilla::getItem);
                } else {
                    continue;
                }
                if (group != null) {
                    groups.put(decap(name), group);
                }
            } catch (final Throwable ignored) {
                // 单个 getter 失败绝不影响其它槽组（也绝不影响整份快照）
            }
        }
        if (groups.isEmpty()) {
            return null;
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", key(be.getBlockPos()));
        out.put("dimension", level.dimension().location().toString());
        out.put("owner", be.getClass().getSimpleName());
        out.put("slotGroups", groups);
        return out;
    }

    /** 一个槽组的快照：{@code size} + 非空槽列表（空槽不列，避免几十行噪声）。 */
    private static Object slotGroup(final String name, final int size,
                                    final java.util.function.IntFunction<ItemStack> getter) {
        if (size <= 0) {
            return null;
        }
        final List<Object> slots = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            final ItemStack stack;
            try {
                stack = getter.apply(slot);
            } catch (final RuntimeException ignored) {
                continue;
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("item", String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem())));
            row.put("count", stack.getCount());
            if (stack.isDamageableItem()) {
                row.put("damage", stack.getDamageValue());
            }
            if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                final var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                final String text = data == null ? "" : data.copyTag().toString();
                row.put("customData", text.length() <= 1200 ? text : text.substring(0, 1200) + "…(截断)");
            }
            slots.add(row);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", size);
        out.put("nonEmptySlots", slots);
        return out;
    }

    /** {@code getInventory} → {@code inventory}（槽组名用短名，更接近玩家自己的说法）。 */
    private static String decap(final String getter) {
        final String trimmed = getter.startsWith("get") ? getter.substring(3) : getter;
        return trimmed.isEmpty() ? getter
            : Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    /**
     * 玩家背包里<b>与本模组相关</b>的格（终端 / 磁盘 / 样板 / 蓝图）+ 手持物 + 坐标。
     * <p>刻意不做整包导出：那会产生几百行噪声，而排查真正需要的是「玩家此刻拿着什么、
     * 身上有哪些本模组的物品」。手持物单列一项，因为「手滑拿错」是最常见的人为因素。</p>
     */
    private static Map<String, Object> playerInventory(final ServerPlayer player) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", player.getGameProfile().getName());
        out.put("dimension", player.level().dimension().location().toString());
        out.put("pos", key(player.blockPosition()));
        out.put("held", itemLabel(player.getMainHandItem()));
        out.put("offhand", itemLabel(player.getOffhandItem()));
        final List<Object> interesting = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()));
            if (!id.startsWith("rs_create_compat:") && !id.startsWith("refinedstorage:")) {
                continue;
            }
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("item", id);
            row.put("count", stack.getCount());
            row.put("name", stack.getHoverName().getString());
            interesting.add(row);
        }
        out.put("rsItems", interesting);
        return out;
    }

    /** 物品的 {@code 注册名 ×数量}（空栈写 {@code "-"}）。 */
    private static String itemLabel(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "-";
        }
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
            + " x" + stack.getCount();
    }

    /**
     * 网络级 / 磁盘级的「总量」视图：把每一个<b>通用储存磁盘</b>物品里的容量与资源逐项列出。
     *
     * <p>数据来源是磁盘物品自己的 NBT（{@code UniversalStorageData} 编解码），因此
     * 「网络里到底有多少料」有了权威答案 —— 不需要玩家目视终端后复述，而「复述错一次」
     * 就足以把排查带向错误方向。找不到磁盘 / 解析失败时返回空表，绝不影响其余字段。</p>
     */
    private static List<Object> universalStorageView(final MinecraftServer server) {
        final List<Object> out = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            for (final BlockEntity be : blockEntities(level)) {
                if (!(be instanceof Container container)) {
                    continue;
                }
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    final ItemStack stack;
                    try {
                        stack = container.getItem(slot);
                    } catch (final RuntimeException ignored) {
                        continue;
                    }
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    final String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()));
                    if (!id.startsWith("rs_create_compat:universal_storage_disk")) {
                        continue;
                    }
                    final Map<String, Object> row = new LinkedHashMap<>();
                    row.put("at", key(be.getBlockPos()));
                    row.put("slot", slot);
                    row.put("item", id);
                    // 磁盘内容直接取物品的 CustomData（通用盘的资源表就在那里，编解码见 UniversalStorageData）。
                    // 取不到就写 "-"：这是「网络里到底有多少料」的权威答案的<b>补充</b>，
                    // 绝不因为某一次结构变化让整个快照失败。
                    String data = "-";
                    try {
                        final var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                        if (custom != null) {
                            final String text = custom.copyTag().toString();
                            data = text.length() <= 6000 ? text : text.substring(0, 6000) + "…(截断)";
                        }
                    } catch (final RuntimeException ignored) {
                        data = "-";
                    }
                    row.put("storageData", data);
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> meta(final MinecraftServer server, final String tag, final LocalDateTime now) {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema", "rscc_diag_snapshot");
        meta.put("version", 1);
        meta.put("tag", tag);
        meta.put("generatedAt", now.format(ISO));
        meta.put("sessionStartedAt", sessionStartedAt);
        final List<Object> levels = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            levels.add(level.dimension().location().toString());
        }
        levels.sort(java.util.Comparator.comparing(String::valueOf));
        meta.put("levels", levels);
        return meta;
    }

    // ==================== 各组视图 ====================

    private static Map<String, Object> countersView() {
        final Map<String, Object> out = new TreeMap<>(); // 键排序 → 确定性
        for (final Map.Entry<String, long[]> entry : COUNTERS.entrySet()) {
            out.put(entry.getKey(), entry.getValue()[0]);
        }
        return new LinkedHashMap<>(out);
    }

    private static List<Object> recentView() {
        final List<Object> out = new ArrayList<>();
        for (final String[] entry : RECENT) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", entry[0]);
            row.put("pos", entry[1]);
            row.put("direction", entry[2]);
            row.put("resource", entry[3]);
            row.put("amount", Long.parseLong(entry[4]));
            row.put("kind", entry[5]);
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> bannersView() {
        final Map<String, Object> sent = new TreeMap<>();
        final Map<String, Object> suppressed = new TreeMap<>();
        for (final Map.Entry<String, long[]> entry : BANNERS.entrySet()) {
            sent.put(entry.getKey(), entry.getValue()[0]);
            suppressed.put(entry.getKey(), entry.getValue()[1]);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", new LinkedHashMap<>(sent));
        out.put("dedupedSuppressed", new LinkedHashMap<>(suppressed));
        return out;
    }

    private static List<Object> suspendedView(final MinecraftServer server) {
        final List<Object> out = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            out.addAll(AssemblyWatchdog.rscc$diagRecords(level));
        }
        return out;
    }

    /**
     * 自动合成任务现场（组 12，<b>2026-10-06 本轮新增</b>）：把每条任务的 {@link TaskStatus} <b>原样</b>导出。
     *
     * <h2>为什么必须新增这一段（本轮根因取证的盲区）</h2>
     * <p>本轮定位到的根因是「已交付量被读成 0」——而<b>快照里根本没有任务这一节</b>，
     * 于是「delivered 是不是 0、每项的 stored/extracting/processing/scheduled/crafting 各是多少」
     * 只能靠日志与推理，无法一眼证实。有了这一段，下次只要看一眼
     * {@code tasks[].delivered} 与 {@code tasks[].items[]} 就能直接判定：</p>
     * <ul>
     *     <li>{@code delivered} = 本模组最终采用的权威读数
     *     （{@code AssemblyWatchdog#deliveredAmount}：root EXTERNAL 样板的 {@code iterationsReceived} 镜像）；</li>
     *     <li>{@code items[].stored / crafting} = RS 自报的 internalStorage 口径 —— 对本模组样板应当<b>恒为 0</b>
     *     （这就是旧口径 delivered ≡ 0 的直接证据）；</li>
     *     <li>{@code items[].scheduled} = 还没派发给 sink 的迭代数（本模组的执行器会把每轮原料放回网络，
     *     因此它很快归零，不能用它当"还没开工"）。</li>
     * </ul>
     * <p><b>只读</b>：只调用 {@code AutocraftingNetworkComponent#getStatuses()}（RS 自己重建状态，
     * 不推进任务、不改任何状态），并按网络去重（同一网络多台仓只导一次）。
     * 取不到（没网络 / 没自动合成组件）时静默跳过 —— 诊断绝不因为某一类机器而整体失败。</p>
     */
    private static List<Object> tasksView(final MinecraftServer server) {
        final List<Object> out = new ArrayList<>();
        final Set<Network> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final ServerLevel level : server.getAllLevels()) {
            for (final BlockEntity be : blockEntities(level)) {
                final Network network;
                if (be instanceof SequenceExecutionChamberBlockEntity chamber) {
                    network = chamber.getNode().getNetworkOrNull();
                } else if (be instanceof cretae.cookiewyq.rs_create_compat.block.entity
                    .SequenceAssemblyExecutorBlockEntity executor) {
                    network = executor.getNode().getNetworkOrNull();
                } else {
                    continue;
                }
                if (network == null || !seen.add(network)) {
                    continue;
                }
                final com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent
                    autocrafting = network.getComponent(
                        com.refinedmods.refinedstorage.api.network.autocrafting
                            .AutocraftingNetworkComponent.class);
                if (autocrafting == null) {
                    continue;
                }
                for (final com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus status
                    : autocrafting.getStatuses()) {
                    out.add(taskRow(status));
                }
            }
        }
        return out;
    }

    /** 一条任务的完整现场（{@link #tasksView(MinecraftServer)} 的单行实现）。只读。 */
    private static Map<String, Object> taskRow(
        final com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus status) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", status.info().id().id().toString());
        row.put("resource", String.valueOf(status.info().resource()));
        row.put("amount", status.info().amount());
        row.put("state", status.state().name());
        row.put("startTime", status.info().startTime());
        row.put("percentageCompleted", fixed2(status.percentageCompleted()));
        // 本模组最终采用的「已交付量」（前两行读数的对照物，见 tasksView 的说明）
        row.put("delivered", AssemblyWatchdog.deliveredAmount(status));
        row.put("suspended", AssemblyWatchdog.isSuspended(status.info().id().id()));
        final List<Object> items = new ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus.Item item : status.items()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resource", String.valueOf(item.resource()));
            entry.put("type", item.type().name());
            entry.put("sinkKey", item.sinkKey() == null ? "-" : String.valueOf(item.sinkKey()));
            entry.put("stored", item.stored());
            entry.put("extracting", item.extracting());
            entry.put("processing", item.processing());
            entry.put("scheduled", item.scheduled());
            entry.put("crafting", item.crafting());
            items.add(entry);
        }
        row.put("items", items);
        return row;
    }

    /**
     * 守恒审计（组 5）：按 (坐标, 资源) 汇总「进入 = 离开 + 留存」。
     * <p>进入 = 网络→仓(pull) + 机器→仓(collect)；离开 = 仓→机器(feed) + 仓→网络(return)；
     * 留存 = 该坐标该资源的仓内现有量（执行舱从 {@code chambers} 的 {@code storedItems} 取）。</p>
     * <p>不平账 = |进入 − 离开 − 留存| &gt; 容差。任何非零条目都能直接暴露「凭空生成 / 消失」。</p>
     */
    private static Map<String, Object> conservationView(final List<Object> chambers) {
        // 坐标 + 资源 → 各量
        final Map<String, long[]> ledger = new TreeMap<>();
        for (final Map.Entry<String, long[]> entry : COUNTERS.entrySet()) {
            final String[] parts = entry.getKey().split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            final String res = parts[1];
            final String kind = parts[2];
            final long[] cell = ledger.computeIfAbsent(parts[0] + "|" + res, ignored -> new long[5]);
            final long amount = entry.getValue()[0];
            switch (kind) {
                case "pull" -> cell[0] += amount;
                case "collect" -> cell[1] += amount;
                case "feed" -> cell[2] += amount;
                case "return" -> cell[3] += amount;
                default -> {
                }
            }
        }
        // 留存：执行舱的当前实际存量
        for (final Object chamber : chambers) {
            if (!(chamber instanceof Map<?, ?> map)) {
                continue;
            }
            final String pos = String.valueOf(map.get("pos"));
            storedInto(ledger, pos, map.get("storedItems"));
            storedInto(ledger, pos, map.get("storedFluids"));
        }
        final List<Object> rows = new ArrayList<>();
        final List<Object> unbalanced = new ArrayList<>();
        for (final Map.Entry<String, long[]> entry : ledger.entrySet()) {
            final long[] cell = entry.getValue();
            final long inbound = cell[0] + cell[1];
            final long outbound = cell[2] + cell[3];
            final long retained = cell[4];
            final long diff = inbound - outbound - retained;
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", entry.getKey());
            row.put("in", inbound);
            row.put("out", outbound);
            row.put("retained", retained);
            row.put("diff", diff);
            rows.add(row);
            if (inbound + outbound + retained > 0 && diff != 0) {
                unbalanced.add(row);
            }
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", rows.size());
        out.put("ledger", rows);
        out.put("unbalanced", unbalanced);
        return out;
    }

    private static void storedInto(final Map<String, long[]> ledger, final String pos, final Object stored) {
        if (!(stored instanceof List<?> list)) {
            return;
        }
        for (final Object entry : list) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }
            final Object res = row.containsKey("item") ? row.get("item") : row.get("fluid");
            if (res == null) {
                continue;
            }
            final Object amount = row.get("count") != null ? row.get("count") : row.get("mB");
            if (!(amount instanceof Number number)) {
                continue;
            }
            final long[] cell = ledger.computeIfAbsent(pos + "|" + res, ignored -> new long[5]);
            cell[4] += number.longValue();
        }
    }

    private static Map<String, Object> keepersView(final List<Object> keepers) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", keepers.size());
        out.put("entries", keepers);
        return out;
    }

    private static Map<String, Object> camouflageView(final int total) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("camouflagedPositions", total);
        out.put("revealToggleCount", revealToggleCount);
        out.put("lastToggleAffectedPositions", revealLastPositions);
        out.put("lastToggleTick", revealLastTick);
        out.put("rebuildNote", "客户端网格重建的方块数 / 区段数在客户端侧，服务端不可观测；"
            + "这里给出「受影响格数」作为等价证据（K 键切换会为这些格重烘）");
        return out;
    }

    /** 配方四类分类（组 8）：直接取每个执行舱的类别表（与服务端同源）。 */
    private static List<Object> recipesView(final List<Object> chambers) {
        final List<Object> out = new ArrayList<>();
        for (final Object chamber : chambers) {
            if (!(chamber instanceof Map<?, ?> map)) {
                continue;
            }
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("chamber", String.valueOf(map.get("pos")));
            row.put("recipeType", String.valueOf(map.get("recipeType")));
            row.put("categories", map.get("categories"));
            out.add(row);
        }
        return out;
    }

    // ==================== 方块实体枚举 ====================

    /** 该维度当前已加载的全部方块实体（来源 = 看门狗维护的已加载区块表）。 */
    private static List<BlockEntity> blockEntities(final ServerLevel level) {
        final List<BlockEntity> result = new ArrayList<>();
        final Set<Long> chunks = AssemblyWatchdog.rscc$loadedChunks(level.dimension());
        if (chunks == null || chunks.isEmpty()) {
            return result;
        }
        for (final long packed : new ArrayList<>(chunks)) {
            final LevelChunk chunk = level.getChunkSource().getChunkNow((int) (packed >> 32), (int) packed);
            if (chunk != null) {
                result.addAll(chunk.getBlockEntities().values());
            }
        }
        return result;
    }

    private static int countCamouflaged(final ServerLevel level) {
        int count = 0;
        for (final BlockEntity be : blockEntities(level)) {
            if (RsccCamouflage.isCamouflaged(level, be.getBlockPos())) {
                count++;
            }
        }
        return count;
    }

    // ==================== 总线 / 保持器报告 ====================

    private static Map<String, Object> exporterReport(final ServerLevel level, final BlockPos pos,
                                                      final RsccExporterExecutorMode bus, final boolean exporter) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", key(pos));
        out.put("kind", "exporter");
        out.put("linkedLayout", bus.rscc$isLinkedLayout());
        out.put("executorMode", bus.rscc$isExecutorMode());
        out.put("forceNormal", bus.rscc$isForceNormalBus());
        out.put("autoCrafting", bus.rscc$isAutoCrafting());
        out.put("linkedExecutor", bus.rscc$linkedExecutorPos() == null ? "-" : key(bus.rscc$linkedExecutorPos()));
        out.put("selectedCategories", new ArrayList<Object>(bus.rscc$getExportCategoryIds()));
        out.put("selectionExplicit", bus.rscc$isCategorySelectionExplicit());
        final RsccBusInterference.Report report = bus.rscc$linkReport();
        out.put("interference", interference(report));
        final List<Object> visible = new ArrayList<>();
        for (final RsccBusCategory category : bus.rscc$getCategorySnapshot()) {
            visible.add(categoryView(category));
        }
        out.put("visibleCategories", visible);
        return out;
    }

    private static Map<String, Object> importerReport(final ServerLevel level, final BlockPos pos,
                                                      final RsccImporterExecutorMode bus, final boolean exporter) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", key(pos));
        out.put("kind", "importer");
        out.put("linkedLayout", bus.rscc$isLinkedLayout());
        out.put("executorMode", bus.rscc$isExecutorMode());
        out.put("forceNormal", bus.rscc$isForceNormalBus());
        out.put("autoCollect", bus.rscc$isAutoCollect());
        out.put("linkedExecutor", bus.rscc$linkedExecutorPos() == null ? "-" : key(bus.rscc$linkedExecutorPos()));
        out.put("selectedCategories", new ArrayList<Object>(bus.rscc$getImportCategoryIds()));
        final RsccBusInterference.Report report = bus.rscc$linkReport();
        out.put("interference", interference(report));
        final List<Object> visible = new ArrayList<>();
        for (final RsccBusCategory category : bus.rscc$getCategorySnapshot()) {
            visible.add(categoryView(category));
        }
        out.put("visibleCategories", visible);
        return out;
    }

    /**
     * <b>中间产物缓存池的观测视图</b>（2026-10-05 新增）。
     *
     * <p>背景：缓存仓的磁盘已从「网络外的孤岛池」改成「真正的网络存储源」
     * （{@code IntermediateCacheNetworkNode implements StorageProvider} +
     * {@code PriorityStorage} 高插入优先级）。但快照里<b>一直没有它的字段</b>，
     * 于是「插了 1k 盘、占用一直是 0」无法从快照判断 —— 只能靠日志猜。
     * 这里把它记全：盘数 / 每块盘的已用与容量 / 盘里的具体内容。</p>
     *
     * <p>数据源与 {@code RsccSharedCache} 完全同源
     * （{@code IntermediateCacheBlockEntity#appendPoolStorages}），不写第二套解析。</p>
     */
    private static Map<String, Object> cachePoolView(final MinecraftServer server) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<Object> disks = new ArrayList<>();
        long storedTotal = 0L;
        long capacityTotal = 0L;
        try {
            for (final ServerLevel level : server.getAllLevels()) {
                for (final BlockEntity be : blockEntities(level)) {
                    if (!(be instanceof final cretae.cookiewyq.rs_create_compat.block.entity
                        .IntermediateCacheBlockEntity warehouse)) {
                        continue;
                    }
                    final Map<String, Object> disk = new LinkedHashMap<>();
                    disk.put("pos", key(warehouse.getBlockPos()));
                    disk.put("connected", warehouse.getNode().getNetworkOrNull() != null);
                    // <b>关键事实：这块盘是不是真的进了网络的存储源列表</b>
                    // （{@code RootStorage#hasSource} 会递归问每个 CompositeAwareChild）。
                    // 这一项为 false ⇒ 不管优先级多高，RS 都不会往它里面写 ——
                    // 这正是「迁移 moved=32 但 poolFree 恒为 1024」最可能的解释。
                    boolean inSourceList = false;
                    long netStored = 0L;
                    final var warehouseNetwork = warehouse.getNode().getNetworkOrNull();
                    if (warehouseNetwork != null) {
                        final var comp = warehouseNetwork.getComponent(
                            com.refinedmods.refinedstorage.api.network.storage
                                .StorageNetworkComponent.class);
                        if (comp != null) {
                            netStored = comp.getStored();
                            inSourceList = comp.hasSource(s -> s == warehouse.getNode().getStorage());
                        }
                    }
                    disk.put("inNetworkSourceList", inSourceList);
                    disk.put("networkTotalStored", netStored);
                    final List<Object> contents = new ArrayList<>();
                    final List<com.refinedmods.refinedstorage.common.api.storage.SerializableStorage>
                        pools = new ArrayList<>();
                    warehouse.appendPoolStorages(level, pools);
                    for (final com.refinedmods.refinedstorage.common.api.storage.SerializableStorage s
                        : pools) {
                        for (final com.refinedmods.refinedstorage.api.resource.ResourceAmount amount
                            : s.getAll()) {
                            if (amount.amount() <= 0L
                                || !(amount.resource() instanceof final com.refinedmods
                                    .refinedstorage.common.support.resource.ItemResource item)) {
                                continue;
                            }
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(item.item()).toString());
                            row.put("count", amount.amount());
                            contents.add(row);
                        }
                        capacityTotal += RsccSharedCache.capacityOf(s);
                        storedTotal += RsccSharedCache.storedOf(s);
                    }
                    disk.put("contents", contents);
                    disks.add(disk);
                }
            }
            out.put("diskCount", disks.size());
            out.put("storedTotal", storedTotal);
            out.put("capacityTotal", capacityTotal);
            out.put("disks", disks);
        } catch (final RuntimeException ignored) {
            // 诊断绝不能因为取不到而整体失败
            out.put("error", "unavailable");
        }
        return out;
    }

    private static Map<String, Object> interference(final RsccBusInterference.Report report) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("disabled", report.disabled());
        out.put("truncated", report.truncated());
        out.put("reachableCount", report.reachableCount());
        return out;
    }

    private static Map<String, Object> categoryView(final RsccBusCategory category) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", category.id());
        out.put("group", group(category.id()));
        out.put("iconItem", category.iconItem());
        out.put("iconFluid", category.iconFluid());
        out.put("stepMachine", category.stepMachine());
        out.put("selected", category.selected());
        out.put("sharedCount", category.sharedCount());
        out.put("amount", category.amount());
        out.put("estimated", category.estimated());
        return out;
    }

    private static Map<String, Object> keeperReport(final ServerLevel level, final BlockPos pos,
                                                    final QuantityKeeperBlockEntity keeper) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", key(pos));
        out.put("kind", "quantity_keeper");
        out.put("label", keeper.diagnosticLabel());
        out.put("destroyOverflow", keeper.isDestroyOverflow());
        out.put("destroyedTotal", keeper.overflowLog().destroyedTotal());
        out.put("claimed", strings(keeper.claimedResources()));
        out.put("yielded", yielded(level, keeper.getNode(), keeper.claimedResources()));
        return out;
    }

    private static Map<String, Object> advancedKeeperReport(final ServerLevel level, final BlockPos pos,
                                                            final AdvancedQuantityKeeperBlockEntity keeper) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", key(pos));
        out.put("kind", "advanced_quantity_keeper");
        out.put("label", keeper.diagnosticLabel());
        final List<Object> slots = new ArrayList<>();
        for (int slot = 0; slot < AdvancedQuantityKeeperBlockEntity.SLOT_COUNT; slot++) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("destroyOverflow", keeper.isDestroyOverflow(slot));
            row.put("destroyedTotal", keeper.overflowLog(slot).destroyedTotal());
            row.put("target", keeper.getTarget(slot));
            slots.add(row);
        }
        out.put("slots", slots);
        out.put("claimed", strings(keeper.claimedResources()));
        out.put("yielded", yielded(level, keeper.getNode(), keeper.claimedResources()));
        // <b>2026-10-05 新增：每个「已认领资源」当前算出来的数量</b>。
        // 用户实测：「我把金板标记 65 个，网络里刚好 65 个，但界面只显示 64 个」。
        // 这句话里有两个不同的数：① RS 网络自己报的存量；② 保持器算出来用于判断「达标了吗」的数量。
        // 没有这行就无法区分「网络真的只有 64」与「保持器少算了 1」—— 直接把两个数摊进快照。
        out.put("counted", countedAmounts(level, keeper.getNode(), keeper.claimedResources()));
        return out;
    }

    /**
     * 只读：每个已认领资源「当前算到多少」（= 保持器据此判断是否达标的那个数）。
     * <p>取不到网络 / 资源时给 {@code -1}（明确表示「没算出来」，而不是 0）。</p>
     */
    private static List<Object> countedAmounts(final ServerLevel level, final KeeperCluster.Node node,
                                               final List<ResourceKey> claimed) {
        final List<Object> out = new ArrayList<>();
        final Network network = networkOf(level, node);
        if (network == null) {
            return out;
        }
        final com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent storage =
            network.getComponent(
                com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent.class);
        if (storage == null) {
            return out;
        }
        for (final ResourceKey key : claimed) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", String.valueOf(key));
            row.put("counted", storage.get(key));
            out.add(row);
        }
        out.sort(java.util.Comparator.comparing(String::valueOf));
        return out;
    }

    private static List<Object> yielded(final ServerLevel level, final KeeperCluster.Node node,
                                        final List<ResourceKey> claimed) {
        final List<Object> out = new ArrayList<>();
        final Network network = networkOf(level, node);
        if (network == null) {
            return out;
        }
        final Set<ResourceKey> yielded = KeeperCluster.yieldedResources(network, node);
        for (final ResourceKey key : yielded) {
            out.add(String.valueOf(key));
        }
        out.sort(java.util.Comparator.comparing(String::valueOf));
        return out;
    }

    private static Network networkOf(final ServerLevel level, final KeeperCluster.Node node) {
        if (node instanceof NetworkNode networkNode) {
            return networkNode.getNetwork();
        }
        return null;
    }

    private static List<Object> strings(final List<ResourceKey> keys) {
        final List<Object> out = new ArrayList<>();
        for (final ResourceKey key : keys) {
            out.add(String.valueOf(key));
        }
        out.sort(java.util.Comparator.comparing(String::valueOf));
        return out;
    }

    // ==================== 工具 ====================

    /** 事件正文 → 计数类别（返回 {@code null} 表示不计）。 */
    private static String kindOf(final String body) {
        final Matcher event = P_EVENT.matcher(body);
        if (event.find()) {
            return switch (event.group(1)) {
                case "bus_push" -> "push";
                case "take_to_chamber" -> "pull";
                case "take_from_machine", "handover_to_chamber" -> "collect";
                case "insert_network", "insert_claimed" -> "return";
                case "task_finished_residual", "return" -> "reclaim";
                case "bus_skip", "machine_full" -> "machine_full";
                case "bus_share_skip" -> "share_skip";
                default -> null;
            };
        }
        final Matcher reason = P_REASON.matcher(body);
        if (reason.find()) {
            final String value = reason.group(1);
            if ("not_wanted_this_step".equals(value) || "machine_full".equals(value)
                || "no_bus_selected_this_step".equals(value) || "disallow_inputting_by_step".equals(value)
                || "step_not_mine".equals(value) || "no_machine_owns_step".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private static String directionOf(final String kind, final boolean hasPos, final String body) {
        return switch (kind) {
            case "push", "feed" -> "chamber->machine";
            case "pull" -> "network->chamber";
            case "collect" -> "machine->chamber";
            case "return", "reclaim" -> "chamber->network";
            default -> body.contains("from=network") ? "network->chamber" : "unknown";
        };
    }

    private static String resourceOf(final String body) {
        final Matcher item = P_ITEM.matcher(body);
        if (item.find()) {
            return item.group(1);
        }
        final Matcher fluid = P_FLUID.matcher(body);
        return fluid.find() ? fluid.group(1) : "-";
    }

    private static long amountOf(final String body) {
        final Matcher amount = P_AMOUNT.matcher(body.split("\\|", 2)[0]);
        return amount.find() ? Long.parseLong(amount.group(1)) : 1L;
    }

    /** 类别 id → 四类分组名（与 {@link RsccBusCategory} 前缀规则同源）。 */
    private static String group(final String id) {
        if (id == null) {
            return "unknown";
        }
        if (id.startsWith(RsccBusCategory.INPUT_PREFIX)) {
            return "ingredient";
        }
        if (id.startsWith(RsccBusCategory.FLUID_PREFIX)) {
            return "inputFluid";
        }
        if (id.startsWith(RsccBusCategory.INTERMEDIATE)) {
            return "intermediate";
        }
        if (id.startsWith(RsccBusCategory.RESULT_PREFIX)) {
            return "product";
        }
        if (id.startsWith(RsccBusCategory.SCRAP_PREFIX)) {
            return "scrap";
        }
        return "unknown";
    }

    private static String key(final BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 按 map 里的某个字符串字段排序（保证同一状态两次导出的数组顺序一致）。 */
    private static void sortListOfMaps(final List<Object> list, final String field) {
        list.sort((a, b) -> {
            final String left = a instanceof Map<?, ?> m ? String.valueOf(m.get(field)) : "";
            final String right = b instanceof Map<?, ?> m ? String.valueOf(m.get(field)) : "";
            return left.compareTo(right);
        });
    }

    /** 导出根目录（保证落在 {@code run/rscc_diag}）。 */
    private static Path exportRoot() {
        Path base = FMLPaths.GAMEDIR.get();
        if (!Files.isDirectory(base.resolve("logs")) && Files.isDirectory(base.resolve("run"))) {
            base = base.resolve("run");
        }
        return base.resolve(DIR_NAME);
    }

    /** 供指令回执使用：把导出路径转成相对显示（绝对路径原样返回）。 */
    public static String describe(final Result result) {
        return result.path() + " (" + result.chambers() + " chambers, " + result.buses() + " buses, "
            + result.counters() + " counters, " + result.summaryLines() + " lines)";
    }

    /** 小工具：把 double 格式化为稳定的两位小数（供文本摘要）。 */
    public static String fixed2(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
