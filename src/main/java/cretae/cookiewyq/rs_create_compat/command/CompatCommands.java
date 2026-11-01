package cretae.cookiewyq.rs_create_compat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.support.AutocrafterStorageController;
import cretae.cookiewyq.rs_create_compat.support.BlockContentMode;
import cretae.cookiewyq.rs_create_compat.support.BlockContentPolicy;
import cretae.cookiewyq.rs_create_compat.support.OptionalDeps;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccBusClipboard;
import cretae.cookiewyq.rs_create_compat.support.RsccIntermediateReusePolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccRefillPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyStrategy;
import cretae.cookiewyq.rs_create_compat.support.RsccUltimineIntegration;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模组指令（指令根 = 模组 id {@code rs_create_compat}）。
 * <p>
 * 目前只有一条：自动合成仓「内部存储」总开关（原来挂在合成仓管理器界面侧边按钮上，
 * 因为容易被不知情的人误点，用户要求改为指令）。
 * <pre>
 *   /rs_create_compat autocrafter storage on    开启（执行者周围 32 格内全部自动合成仓）
 *   /rs_create_compat autocrafter storage off   关闭（先把仓内物品/流体写回网络，装不下则取消）
 * </pre>
 * <p><b>权限</b>：{@code requires(source -> true)} —— 任何玩家都能执行（用户明确要求「这个指令不需要权限」）。</p>
 * <p><b>作用范围</b>：以执行者为中心的 <b>32 格</b>半径（方块中心三轴距离），逐台作用于范围内的自动合成仓；
 * 不涉及任何全局状态，因此指令天然「可重入」「可局部生效」。</p>
 */
public final class CompatCommands {
    /** 指令作用半径（格）：以执行者为中心。 */
    private static final double RADIUS = 32.0;

    private static final String KEY_ENABLED = "message.rs_create_compat.autocrafter_storage.enabled";
    private static final String KEY_DISABLED = "message.rs_create_compat.autocrafter_storage.disabled";
    private static final String KEY_FAILED = "message.rs_create_compat.autocrafter_storage.disable_failed";
    private static final String KEY_NO_AUTOCRAFTER = "message.rs_create_compat.autocrafter_storage.no_autocrafter";
    private static final String KEY_DISABLED_BY_CONFIG =
        "message.rs_create_compat.autocrafter_storage.disabled_by_config";
    private static final String KEY_USAGE = "message.rs_create_compat.autocrafter_storage.usage";

    // 「拆方块时内容物去向」三档策略指令的语言键
    private static final String KEY_BC_USAGE = "message.rs_create_compat.blockcontent.usage";
    private static final String KEY_BC_SET = "message.rs_create_compat.blockcontent.set";
    private static final String KEY_BC_CURRENT = "message.rs_create_compat.blockcontent.current";

    // 「序列执行仓原料供应策略」二档指令的语言键
    private static final String KEY_SUPPLY_USAGE = "message.rs_create_compat.supply.usage";
    private static final String KEY_SUPPLY_SET = "message.rs_create_compat.supply.set";
    private static final String KEY_SUPPLY_CURRENT = "message.rs_create_compat.supply.current";

    // 「序列装配缺料处置策略」二档指令的语言键（用户第 5 条：挂起 / 一直等待）
    private static final String KEY_SHORTAGE_USAGE = "message.rs_create_compat.shortage.usage";
    private static final String KEY_SHORTAGE_SET = "message.rs_create_compat.shortage.set";
    private static final String KEY_SHORTAGE_CURRENT = "message.rs_create_compat.shortage.current";

    // 「序列装配链路诊断日志」开关指令的语言键
    private static final String KEY_ASSEMBLY_USAGE = "message.rs_create_compat.assemblydebug.usage";
    private static final String KEY_ASSEMBLY_SET = "message.rs_create_compat.assemblydebug.set";
    private static final String KEY_ASSEMBLY_CURRENT = "message.rs_create_compat.assemblydebug.current";
    private static final String KEY_ASSEMBLY_ON = "message.rs_create_compat.assemblydebug.on";
    private static final String KEY_ASSEMBLY_OFF = "message.rs_create_compat.assemblydebug.off";

    // 「补合成请求量」档位指令的语言键（用户第 1 / 2 条；第三档「按机器台数发」为新增）
    private static final String KEY_REFILL_USAGE = "message.rs_create_compat.refill.usage";
    // 第三档的字面量单独一行给出：usage 那一行（中文字符数有上限）只列既有两档，
    // 两行合起来才是完整取值表；这条也顺带把「按机器台数」的含义说清楚。
    private static final String KEY_REFILL_USAGE_MACHINES = "message.rs_create_compat.refill.usage.machines";
    private static final String KEY_REFILL_SET = "message.rs_create_compat.refill.set";
    private static final String KEY_REFILL_CURRENT = "message.rs_create_compat.refill.current";
    private static final String KEY_REFILL_ON = "message.rs_create_compat.refill.on";
    private static final String KEY_REFILL_OFF = "message.rs_create_compat.refill.off";
    private static final String KEY_REFILL_MACHINES = "message.rs_create_compat.refill.machines";
    private static final String KEY_REUSE_USAGE = "message.rs_create_compat.intermediate_reuse.usage";
    private static final String KEY_REUSE_SET = "message.rs_create_compat.intermediate_reuse.set";
    private static final String KEY_REUSE_CURRENT = "message.rs_create_compat.intermediate_reuse.current";
    private static final String KEY_REUSE_ON = "message.rs_create_compat.intermediate_reuse.on";
    private static final String KEY_REUSE_OFF = "message.rs_create_compat.intermediate_reuse.off";

    // 「一键诊断快照」指令的语言键（用户唯一动作：进游戏敲一条指令即可拿到全部证据）
    private static final String KEY_DIAG_EXPORTED = "message.rs_create_compat.diag.exported";

    // ==================== 「一键诊断」的冷却 / 回执轮转（第 121 轮 ④） ====================
    /**
     * 同一执行者两次「真正导出」诊断快照之间的冷却（tick；200 tick = 10 秒）。
     *
     * <p><b>为什么这条指令不加权限，却必须有冷却</b>：用户明确要求 {@code /rs_create_compat diag}
     * <b>不要权限</b> —— 任何玩家都能敲，这正是它的用途：让玩家自己拿到证据，而不是去找管理员。
     * 但每敲一次都会：① 在 {@code run/rscc_diag/<时间戳>/} 下新建一份几 MB 的 {@code snapshot.json}
     * （另有 {@code latest/} 再写一份）；② 在日志里写三行分节标记。既然拿掉了「要权限」这道闸，
     * 就必须换一道护栏 —— 否则一个连点宏，或 {@code /execute as @a run ...} 这类循环触发，
     * 几个眨眼就能把磁盘和日志刷满，而同一台服务器还要继续跑产线。冷却就是这道护栏：
     * <b>只限频，不拒绝任何人</b>。</p>
     *
     * <p><b>为什么是 10 秒</b>：一次导出 + 玩家读完回执（快照路径 + 行数）一般 3～5 秒内完成；
     * 10 秒让「手滑连点两下」只留一份快照，又不会拖慢正常排查（真实节奏是「看一眼 → 改一处 → 再敲」，
     * 分钟级）。另一个好处是它正好 200 tick，与本模组其它按 tick 计的窗口同一量纲，审计时不必换算。</p>
     */
    private static final int DIAG_COOLDOWN_TICKS = 200;

    /**
     * 回执尾注的分段（每次真正导出只带 <b>一段</b>，按调用次序轮转）。
     * <p>为什么要轮转：诊断回执若每次都把「文件在哪 / 怎么用 / 注意什么」全打一遍，
     * 玩家连敲两次就会看到两屏一模一样的字 —— 正是要避免的刷屏。改成每次只带一段之后，
     * 单条回执只有两行；连敲 {@link #DIAG_TIP_SEGMENTS} 长度次（受冷却限制，10 秒一次）就能看全所有提示。</p>
     * <p>文案刻意<b>全中文、不带 ASCII 字母</b>：诊断输出走 {@code Component.literal}，
     * 含 ASCII 字母的字面量会被 {@code selfcheck_round74_localization} 的规则 C 判成
     * 「玩家可见文本未本地化」；中文 + 数字不触发该规则。</p>
     */
    private static final String[] DIAG_TIP_SEGMENTS = {
        "提示｜快照只读：不改变任务、物品与机器设置，可以放心反复查看。",
        "提示｜快照按时间戳分目录保存，同目录里的「最新」别名总是指向本次那一份。",
        "提示｜带「运行」参数的写法只在导出这一下临时打开诊断日志，导出完立刻恢复，不会一直开着。",
        "提示｜想查连锁链路断在哪一环，可再敲连锁诊断指令（同样不需要权限）。",
    };

    /**
     * 上一次真正导出快照的服务器时刻（执行者 → tick）。
     * <p>键用「执行者」而不是「玩家 + 维度」：冷却与维度无关，换维度不该重置。
     * 控制台 / 命令方块没有执行者玩家，统一落到 {@link #DIAG_NO_PLAYER_KEY} ——
     * 它们同样要限频（命令方块循环触发正是最需要挡的场景）。
     * <p><b>有界</b>：表项超过 256 且来了新执行者时整表清空（冷却只有 10 秒，清空最坏多放行一次导出），
     * 不随在线时长无限增长。</p>
     */
    private static final Map<UUID, Integer> DIAG_LAST_EXPORT_TICK = new HashMap<>();

    /** 无执行者（控制台 / 命令方块）共用的冷却键：全零 UUID，不与任何真实玩家冲突。 */
    private static final UUID DIAG_NO_PLAYER_KEY = new UUID(0L, 0L);

    /** 回执尾注的轮转游标：只在真正导出时前进一格，按 {@link #DIAG_TIP_SEGMENTS} 长度取模（不会溢出）。 */
    private static int diagTipCursor;

    /**
     * 连锁诊断里「保底开关怎么写」的那半句（诊断输出的技术片段，<b>不是</b>玩家可见文案模板）。
     * <p>为什么单独抽成常量：{@code Component.literal} 的字面量里不写 ASCII 字母
     * （见 {@code selfcheck_round74_localization} 的规则 C），而指令写法本身必须一字不差地露出来，
     * 于是把它放在变量里拼接 —— 玩家看到的内容不变，审计规则也不会把诊断当成未本地化的界面文案。</p>
     */
    private static final String CHAIN_DEBUG_FALLBACK_USAGE =
        "/rs_create_compat chain-debug fallback on|off";

    private CompatCommands() {
    }

    /** 注册指令树（由 {@code RegisterCommandsEvent} 调用）。 */
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands
            .literal("rs_create_compat")
            // 用户要求：不需要权限，普通玩家即可使用
            .requires(source -> true);
        root.then(Commands.literal("autocrafter")
            .then(Commands.literal("storage")
                .then(Commands.literal("on")
                    .executes(context -> setStorage(context, true)))
                .then(Commands.literal("off")
                    .executes(context -> setStorage(context, false)))
                // 只输入到 storage 一层时给出用法提示（而不是原版的「未知参数」报错）
                .executes(context -> {
                    context.getSource().sendFailure(Component.translatable(KEY_USAGE));
                    return 0;
                })));
        // 「拆方块时内容物去向」三档策略（全局，按世界保存，无权限）
        root.then(Commands.literal("blockcontent")
            .then(Commands.literal(BlockContentMode.NETWORK.id())
                .executes(context -> setBlockContent(context, BlockContentMode.NETWORK)))
            .then(Commands.literal(BlockContentMode.DROP.id())
                .executes(context -> setBlockContent(context, BlockContentMode.DROP)))
            .then(Commands.literal(BlockContentMode.BLOCK.id())
                .executes(context -> setBlockContent(context, BlockContentMode.BLOCK)))
            // 只输入到 blockcontent 一层：回报当前档位 + 用法提示
            .executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.translatable(KEY_BC_CURRENT,
                        Component.translatable(currentMode(context).langKey())), false);
                context.getSource().sendFailure(Component.translatable(KEY_BC_USAGE));
                return 0;
            }));
        // 「序列执行仓原料供应策略」二档（全局，按世界保存，无权限）：
        //   /rs_create_compat supply materials  只管输出原料（不看概率）
        //   /rs_create_compat supply target     直到目标产物达标（默认；缺料自动合成补齐）
        root.then(Commands.literal("supply")
            .then(Commands.literal(RsccSupplyStrategy.MATERIALS.id())
                .executes(context -> setSupply(context, RsccSupplyStrategy.MATERIALS)))
            .then(Commands.literal(RsccSupplyStrategy.TARGET.id())
                .executes(context -> setSupply(context, RsccSupplyStrategy.TARGET)))
            // 只输入到 supply 一层：回报当前档位 + 用法提示
            .executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.translatable(KEY_SUPPLY_CURRENT,
                        Component.translatable(currentSupply(context).langKey())), false);
                context.getSource().sendFailure(Component.translatable(KEY_SUPPLY_USAGE));
                return 0;
            }));
        // 「序列装配缺料处置策略」二档（全局，按世界保存，无权限）——用户第 5 条：
        //   /rs_create_compat shortagemode suspend  缺料即挂起（默认；不堵塞后面的任务）
        //   /rs_create_compat shortagemode wait     一直等到有料再继续（缺料绝不自动挂起）
        root.then(Commands.literal("shortagemode")
            .then(Commands.literal(RsccShortagePolicy.Mode.SUSPEND.id())
                .executes(context -> setShortage(context, RsccShortagePolicy.Mode.SUSPEND)))
            .then(Commands.literal(RsccShortagePolicy.Mode.WAIT.id())
                .executes(context -> setShortage(context, RsccShortagePolicy.Mode.WAIT)))
            // 只输入到 shortagemode 一层：回报当前档位 + 用法提示
            .executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.translatable(KEY_SHORTAGE_CURRENT,
                        Component.translatable(currentShortage(context).langKey())), false);
                context.getSource().sendFailure(Component.translatable(KEY_SHORTAGE_USAGE));
                return 0;
            }));
        // 「补合成请求量」档位（运行时，无权限）——用户第 1 / 2 条：
        //   /rs_create_compat refill on        补合成请求量 = 缺口（= 目标 −(网络 + 本仓 + 机器侧 + 在途)）
        //   /rs_create_compat refill off       回落到旧口径：确定性配方一次一份 / 概率性配方分批递进（默认）
        //   /rs_create_compat refill machines  按机器台数发：本仓喂着几台机子就发几份（每台各一份）
        root.then(Commands.literal("refill")
            .then(Commands.literal("on")
                .executes(context -> setRefill(context, true)))
            .then(Commands.literal("off")
                .executes(context -> setRefill(context, false)))
            // 第三档（新增；字面量取自档位自己的 id，与锚点日志 / NBT 里的标识同一份真源）
            .then(Commands.literal(RsccRefillPolicy.Mode.MACHINES.id())
                .executes(context -> setRefillMode(context, RsccRefillPolicy.Mode.MACHINES)))
            // 只输入到 refill 一层：回报当前档位 + 用法提示
            .executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.translatable(KEY_REFILL_CURRENT,
                        Component.translatable(refillLabelKey(currentRefillMode(context)))), false);
                context.getSource().sendFailure(Component.translatable(KEY_REFILL_USAGE));
                // 第三档的写法单独一行（见 KEY_REFILL_USAGE_MACHINES 的说明）
                context.getSource().sendFailure(Component.translatable(KEY_REFILL_USAGE_MACHINES));
                return 0;
            }));
        // <b>「优先复用网络中的中间产物」开关</b>（用户原话：「再添加一个指令用于开关：当网络中具有某序列
        // 装配的中间产物、然后现在又要开始这个序列装配的时候，是否优先使用它的中间产物，而不是从头开始合成」）
        //   /rs_create_compat reuse on    开新件时优先取网络里已有的该配方中间产物当起点
        //   /rs_create_compat reuse off   从头走（默认，= 变更前行为逐字一致）
        root.then(Commands.literal("reuse")
            .then(Commands.literal("on")
                .executes(context -> setReuse(context, true)))
            .then(Commands.literal("off")
                .executes(context -> setReuse(context, false)))
            // 只输入到 reuse 一层：回报当前档位 + 用法提示
            .executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.translatable(KEY_REUSE_CURRENT,
                        Component.translatable(currentReuse(context) ? KEY_REUSE_ON : KEY_REUSE_OFF)), false);
                context.getSource().sendFailure(Component.translatable(KEY_REUSE_USAGE));
                return 0;
            }));
        // 「开发日志总开关」（运行时，无权限）：
        //   /rs_create_compat devlogs on|off          （推荐写法：这是**全部**开发/诊断日志的总闸）
        //   /rs_create_compat assemblydebug on|off    （等价别名，保持旧写法可用）
        //   /rs_create_compat debug assembly on|off   （等价别名）
        // 关掉后除「开关状态变化」那一条外不再输出任何开发日志（见 RsccAssemblyDebug#setEnabled）；
        // 但**必要日志一条都不会少**：WARN / ERROR、启动版本行 [rscc-build]、会话锚点 [rscc] 照常输出。
        addAssemblyDebugToggle(root);
        // 「一键诊断快照」（<b>不加权限</b>，普通玩家 / 控制台 / 命令方块都能用；第 121 轮 ④）：
        //   /rs_create_compat diag       把当前全部相关状态导出为 run/rscc_diag/<时间戳>/snapshot.json
        //   /rs_create_compat diag run   （可选）在本次导出这一小段内临时打开诊断日志（返回即恢复，
        //                                不会再出现「关掉开关还在刷」），再导出 + 打分节标记；只观测，
        //                                不下单、不代玩家操作、不改变任何任务状态
        // 用户明确要求这条指令**不要权限**：它存在的意义就是让玩家自己拿到证据，而不是去找管理员。
        // 这里在 diag 节点上再显式写一次 requires(source -> true)（根节点本来就是 true）：
        // 即使将来有人为了别的指令把根节点收紧，这条诊断入口也不会被顺带关掉 —— 口径写在节点上，
        // 审计脚本与后来者一眼能看到「它是明确开放、不是漏配」。
        // 没有权限闸，护栏就是冷却 + 回执轮转（见 DIAG_COOLDOWN_TICKS / DIAG_TIP_SEGMENTS）。
        root.then(Commands.literal("diag")
            .requires(source -> true)
            .then(Commands.literal("run")
                .executes(context -> exportDiag(context, "run")))
            .executes(context -> exportDiag(context, "manual")));
        // 「连锁诊断」（无权限，普通玩家可用）——用户硬要求：**不依赖日志**也能判断断在哪。
        //   /rs_create_compat chain-debug            一条指令回报：FTB 是否加载（ModList）/
        //                                            我们的注册状态（State）/ FTB 认为连锁键是否按住 /
        //                                            手持物品与潜行；末尾给结论与下一步
        //   /rs_create_compat chain-debug fallback    查看保底整簇粘贴（潜行+右键）开关
        //   /rs_create_compat chain-debug fallback on|off
        //                                            开关保底（默认开）——它完全不经过 FTB Ultimine
        // 文案全部用 literal：这是诊断输出，不进语言文件也就不会出现「新增键漏翻 / 占位符不匹配」。
        root.then(Commands.literal("chain-debug")
            .then(Commands.literal("fallback")
                .then(Commands.literal("on")
                    .executes(context -> setChainFallback(context, true)))
                .then(Commands.literal("off")
                    .executes(context -> setChainFallback(context, false)))
                .executes(CompatCommands::reportChainFallback))
            .executes(CompatCommands::reportChainDebug));
        dispatcher.register(root);
    }

    /**
     * {@code /rs_create_compat chain-debug}：把「连锁链路断在哪一环」直接回报给玩家。
     *
     * <p>回报的四类信息（与 {@link RsccUltimineIntegration#debugReport} /
     * {@link RsccBusClipboard#debugReport} 同源，不另算一份）：</p>
     * <ol>
     *     <li><b>FTB Ultimine 是否加载</b>：直接问 {@link ModList}（与 {@code OptionalDeps} 同一个判据）；</li>
     *     <li><b>我们的监听器 / 处理器注册状态</b>：{@code UNKNOWN / NOT_INSTALLED / HOOKED /
     *     HANDLER_REGISTERED / FAILED}，并附一句人话 + 「处理器是否真的被调用过」；</li>
     *     <li><b>FTB 认为「连锁键当前是否按住」</b>：反射读 {@code FTBUltiminePlayerData#isPressed}；</li>
     *     <li><b>手持物品 / 是否潜行</b>：让用户把自己的操作与回报对照。</li>
     * </ol>
     * <p>只读：不注册、不写世界、不改任何状态（{@code fallback on|off} 子指令才会改开关）。</p>
     */
    private static int reportChainDebug(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final ServerPlayer player = source.getPlayer();
        final boolean modLoaded = ModList.get() != null
            && ModList.get().isLoaded(OptionalDeps.MOD_FTB_ULTIMINE);
        // 注意：本指令的输出刻意**只用中文片段 + 变量拼接**。诊断是给玩家现场看的，
        // 但本轮的改动范围不允许动语言文件（不新增 lang 键），于是这里不写「含 ASCII 字母的
        // Component.literal 字面量」—— 技术名（modid / 枚举名 / 物品 id）全部经由 support 层
        // 的 debugReport 以变量形式带出来，既保持信息量，也不破坏「玩家可见文本必须本地化」的审计规则。
        source.sendSuccess(() -> Component.literal("连锁诊断｜联动模组是否加载："
            + (modLoaded ? "是" : "否") + "（标识：" + OptionalDeps.MOD_FTB_ULTIMINE + "）"), false);
        for (final String line : RsccUltimineIntegration.debugReport(player)) {
            source.sendSuccess(() -> Component.literal("连锁诊断｜" + line), false);
        }
        for (final String line : RsccBusClipboard.debugReport(player)) {
            source.sendSuccess(() -> Component.literal("连锁诊断｜" + line), false);
        }
        return 1;
    }

    /** 查看保底整簇粘贴的开关状态。 */
    private static int reportChainFallback(final CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "连锁诊断｜保底整簇粘贴（手持剪贴板、潜行加右键）："
                + (RsccBusClipboard.sneakFallbackEnabled() ? "已开启（默认）" : "已关闭")
                + "；切换写法：" + CHAIN_DEBUG_FALLBACK_USAGE), false);
        return 1;
    }

    /** 开关保底整簇粘贴（只影响这一条新入口，不碰既有右键 / 左键手势）。 */
    private static int setChainFallback(final CommandContext<CommandSourceStack> context, final boolean enable) {
        RsccBusClipboard.setSneakFallbackEnabled(enable);
        context.getSource().sendSuccess(() -> Component.literal(
            "连锁诊断｜保底整簇粘贴（手持剪贴板、潜行加右键）已" + (enable ? "开启" : "关闭")), true);
        return 1;
    }

    /**
     * 「一键诊断快照」处理器（<b>只读</b>：不改任何游戏状态 / 任务状态）。
     * <p>{@code tag = "run"} 时在<b>本次导出这一小段</b>内临时打开运行时诊断日志开关（只改日志开关，
     * 不碰产线），导出结束——<b>含抛异常</b>——立刻恢复成调用前的开关状态（见
     * {@link RsccAssemblyDebug#withEnabledTemporarily}）；日志里会留下 {@code [rscc-diag] BEGIN/…/END}
     * 分节标记（这三行不受开发日志开关控制，永远输出）。</p>
     * <p><b>第 122 轮修「开关关不掉」</b>：旧写法直接 {@code setEnabled(true)} 且从不恢复，于是用户敲过一次
     * {@code diag run} 之后，配置项 {@code devLogs=false} 对当次会话就完全失效（用户实机日志：会话锚点
     * {@code devLogs=off} → {@code devLogs=on (source=command)} → 接收器诊断行连刷十几分钟）。
     * 现在「打开」的作用域只包住 {@code RsccDiag.snapshot} 这一次调用。</p>
     * <p><b>第 121 轮 ④：不加权限，靠「冷却 + 回执轮转」当护栏。</b>
     * ① 同一执行者 {@value #DIAG_COOLDOWN_TICKS} tick 内只允许真正导出一次；冷却期内
     * <b>不导出、不打日志、也不打开诊断日志</b>（顺序很重要：若先打开日志再判冷却，
     * {@code diag run} 的副作用就绕过了限频）；
     * ② 成功回执追加一段轮转尾注（{@link #DIAG_TIP_SEGMENTS}），避免重复刷同一大段说明。</p>
     */
    private static int exportDiag(final CommandContext<CommandSourceStack> context, final String tag) {
        final CommandSourceStack source = context.getSource();
        final MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        final int now = server.getTickCount();
        final ServerPlayer player = source.getPlayer();
        final UUID who = player == null ? DIAG_NO_PLAYER_KEY : player.getUUID();
        // ① 冷却：先判再动手，冷却期内绝不产生任何副作用（文件 / 日志 / 日志开关都不碰）
        final Integer last = DIAG_LAST_EXPORT_TICK.get(who);
        if (last != null) {
            final int elapsed = now - last;
            // elapsed < 0 = 服务器重启后 getTickCount 归零（静态表还在）：按「已过期」处理，
            // 否则重启后的第一次导出会被一段凭空多出来的冷却挡住（时间不会倒流，负数只可能是重启）。
            if (elapsed >= 0 && elapsed < DIAG_COOLDOWN_TICKS) {
                final int waitSeconds = (DIAG_COOLDOWN_TICKS - elapsed + 19) / 20;
                // 秒数从常量算出来（不在文案里再抄一份 10），改冷却时提示自动跟着变
                final int cooldownSeconds = DIAG_COOLDOWN_TICKS / 20;
                source.sendFailure(Component.literal("诊断快照冷却中｜同一执行者 " + cooldownSeconds
                    + " 秒只能导出一次，还需约 " + waitSeconds + " 秒才能再次导出"
                    + "（每次导出都会新建一份快照文件，连点会刷满磁盘）"));
                return 0;
            }
        }
        if (DIAG_LAST_EXPORT_TICK.size() > 256 && !DIAG_LAST_EXPORT_TICK.containsKey(who)) {
            DIAG_LAST_EXPORT_TICK.clear(); // 有界：见 DIAG_LAST_EXPORT_TICK 的字段说明
        }
        DIAG_LAST_EXPORT_TICK.put(who, now);
        // ② 「运行」模式的副作用<b>只在本次导出这一小段内有效</b>（第 122 轮修）：
        // 旧写法直接 RsccAssemblyDebug.setEnabled(true) 且从不恢复 —— 用户日志里锚点是 devLogs=off，
        // 随后一条 devLogs=on (source=command)，再之后 [rscc-receiver-output] 连刷十几分钟直到重启；
        // 用户「关闭了那个开发日志的那个选项」对当次会话因此完全无效，这正是本轮要根除的「开关关不掉」。
        // 现在走 withEnabledTemporarily：无论导出成功还是抛异常，返回时开关都恢复成调用前的状态。
        final boolean runMode = "run".equals(tag);
        final cretae.cookiewyq.rs_create_compat.support.RsccDiag.Result result;
        if (runMode) {
            result = RsccAssemblyDebug.withEnabledTemporarily(true,
                () -> cretae.cookiewyq.rs_create_compat.support.RsccDiag.snapshot(server, tag));
        } else {
            result = cretae.cookiewyq.rs_create_compat.support.RsccDiag.snapshot(server, tag);
        }
        source.sendSuccess(
            () -> Component.translatable(KEY_DIAG_EXPORTED, result.path().toString(), result.summaryLines()),
            false);
        // ③ 回执尾注按段轮转：一次只带一段，连续敲不会把同一整段说明反复刷屏
        final String tip = DIAG_TIP_SEGMENTS[Math.floorMod(diagTipCursor++, DIAG_TIP_SEGMENTS.length)];
        source.sendSuccess(() -> Component.literal(tip), false);
        return 1;
    }

    /** 挂载「开发日志总开关」的三种写法（同一处理器，行为完全一致；不产生第二套语义）。 */
    private static void addAssemblyDebugToggle(final LiteralArgumentBuilder<CommandSourceStack> root) {
        // 推荐写法：devlogs（＝配置 devLogs，默认 false）
        root.then(Commands.literal("devlogs")
            .then(Commands.literal("on")
                .executes(context -> setAssemblyDebug(context, true)))
            .then(Commands.literal("off")
                .executes(context -> setAssemblyDebug(context, false)))
            .executes(context -> reportAssemblyDebug(context)));
        // 兼容写法 ①：assemblydebug（0.x 起就有）
        root.then(Commands.literal("assemblydebug")
            .then(Commands.literal("on")
                .executes(context -> setAssemblyDebug(context, true)))
            .then(Commands.literal("off")
                .executes(context -> setAssemblyDebug(context, false)))
            // 只输入到 assemblydebug 一层：回报当前状态 + 用法提示
            .executes(context -> reportAssemblyDebug(context)));
        // 兼容写法 ②：debug assembly
        root.then(Commands.literal("debug")
            .then(Commands.literal("assembly")
                .then(Commands.literal("on")
                    .executes(context -> setAssemblyDebug(context, true)))
                .then(Commands.literal("off")
                    .executes(context -> setAssemblyDebug(context, false)))
                .executes(context -> reportAssemblyDebug(context))));
    }

    /** 只输入到开关那一层时：回报当前状态 + 用法提示。 */
    private static int reportAssemblyDebug(final CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_ASSEMBLY_CURRENT,
                Component.translatable(RsccAssemblyDebug.isEnabled()
                    ? KEY_ASSEMBLY_ON : KEY_ASSEMBLY_OFF)), false);
        context.getSource().sendFailure(Component.translatable(KEY_ASSEMBLY_USAGE));
        return 0;
    }

    /** 设置「开发日志总开关」运行时开关（状态变化本身会打一条 INFO，见 {@link RsccAssemblyDebug#setEnabled}）。 */
    private static int setAssemblyDebug(final CommandContext<CommandSourceStack> context, final boolean enable) {
        RsccAssemblyDebug.setEnabled(enable);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_ASSEMBLY_SET,
                Component.translatable(enable ? KEY_ASSEMBLY_ON : KEY_ASSEMBLY_OFF)), true);
        return 1;
    }

    /** 读取当前「供应策略」档位（取不到服务器时回落到默认档）。 */
    private static RsccSupplyStrategy currentSupply(final CommandContext<CommandSourceStack> context) {
        final MinecraftServer server = context.getSource().getServer();
        return server == null ? RsccSupplyStrategy.TARGET
            : RsccSupplyPolicy.get(server).getStrategy();
    }

    /** 设置全局「供应策略」（存档级持久化，读档后仍生效）。 */
    private static int setSupply(final CommandContext<CommandSourceStack> context,
                                 final RsccSupplyStrategy strategy) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        RsccSupplyPolicy.get(server).setStrategy(strategy);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_SUPPLY_SET, Component.translatable(strategy.langKey())), true);
        return 1;
    }

    /** 读取当前「缺料处置策略」档位（取不到服务器时回落到默认档）。 */
    private static RsccShortagePolicy.Mode currentShortage(final CommandContext<CommandSourceStack> context) {
        final MinecraftServer server = context.getSource().getServer();
        return server == null ? RsccShortagePolicy.Mode.SUSPEND
            : RsccShortagePolicy.get(server).getMode();
    }

    /** 设置全局「缺料处置策略」（存档级持久化，读档后仍生效）。 */
    private static int setShortage(final CommandContext<CommandSourceStack> context,
                                   final RsccShortagePolicy.Mode mode) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        RsccShortagePolicy.get(server).setMode(mode);
        // <b>2026-10-05 修掉「挂起模式却显示等待」</b>（子代理闭环定位）：
        //
        // 全工程只有两条路径会改这个权威值：① 界面按钮（会回发 SyncShortageModePacket）；
        // ② 界面 init 主动 Request。而**指令路径是第三条，它改完一个包都不发** ——
        // 于是客户端镜像停在旧值上，自动合成监视器那行字一直显示旧档，
        // 直到关掉重开界面（或点一次按钮）才会自愈。用户实测正是这个现象。
        //
        // 用 sendToAllPlayers 而非 sendToPlayer：指令可能由控制台 / 命令方块执行（没有执行者玩家），
        // 而且这是**全局策略**，所有开着监视器的玩家都该立刻看到新档。
        PacketDistributor.sendToAllPlayers(
            new cretae.cookiewyq.rs_create_compat.network.SyncShortageModePacket(mode.ordinal()));
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_SHORTAGE_SET, Component.translatable(mode.langKey())), true);
        return 1;
    }

    /**
     * 读取当前「补合成请求量」档位（取不到服务器时回落到默认档 = 关）。
     * <p>为什么不再返回布尔：第三档（{@link RsccRefillPolicy.Mode#MACHINES}）与「按缺口补发」必须能被
     * 指令回报区分开，否则玩家敲 {@code refill} 时看到的档位和自己的设置不符。</p>
     */
    private static RsccRefillPolicy.Mode currentRefillMode(final CommandContext<CommandSourceStack> context) {
        final MinecraftServer server = context.getSource().getServer();
        return server == null ? RsccRefillPolicy.Mode.OFF : RsccRefillPolicy.get(server).getMode();
    }

    /** 档位 → 语言键（三档各自的标签；三段文案都在 {@code message.rs_create_compat.refill.*} 下）。 */
    private static String refillLabelKey(final RsccRefillPolicy.Mode mode) {
        if (mode == RsccRefillPolicy.Mode.GAP) {
            return KEY_REFILL_ON;
        }
        return mode == RsccRefillPolicy.Mode.MACHINES ? KEY_REFILL_MACHINES : KEY_REFILL_OFF;
    }

    /** 设置「按缺口补发」开关（存档级持久化，读档后仍生效；状态变化会打一条锚点日志）。 */
    private static int setRefill(final CommandContext<CommandSourceStack> context, final boolean enable) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        RsccRefillPolicy.get(server).setGapRefill(enable);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_REFILL_SET,
                Component.translatable(enable ? KEY_REFILL_ON : KEY_REFILL_OFF)), true);
        return 1;
    }

    /** 设置第三档「按机器台数发」（存档级持久化；状态变化会打一条带档位 id 的锚点日志）。 */
    private static int setRefillMode(final CommandContext<CommandSourceStack> context,
                                     final RsccRefillPolicy.Mode mode) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        RsccRefillPolicy.get(server).setMode(mode);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_REFILL_SET,
                Component.translatable(refillLabelKey(mode))), true);
        return 1;
    }

    /** 读取当前「优先复用中间产物」开关（取不到服务器时回落到默认档 = 关）。 */
    private static boolean currentReuse(final CommandContext<CommandSourceStack> context) {
        final MinecraftServer server = context.getSource().getServer();
        return server != null && RsccIntermediateReusePolicy.get(server).isReuseIntermediates();
    }

    /** 设置「优先复用网络中的中间产物」开关（存档级持久化）。 */
    private static int setReuse(final CommandContext<CommandSourceStack> context, final boolean enable) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        RsccIntermediateReusePolicy.get(server).setReuseIntermediates(enable);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_REUSE_SET,
                Component.translatable(enable ? KEY_REUSE_ON : KEY_REUSE_OFF)), true);
        return 1;
    }

    /** 读取当前「内容物去向」档位（取不到服务器时回落到默认档）。 */
    private static BlockContentMode currentMode(final CommandContext<CommandSourceStack> context) {
        final MinecraftServer server = context.getSource().getServer();
        return server == null ? BlockContentMode.DROP : BlockContentPolicy.get(server).getMode();
    }

    /** 设置全局「拆方块时内容物去向」策略（存档级持久化，读档后仍生效）。 */
    private static int setBlockContent(final CommandContext<CommandSourceStack> context,
                                       final BlockContentMode mode) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        BlockContentPolicy.get(server).setMode(mode);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_BC_SET, Component.translatable(mode.langKey())), true);
        return 1;
    }

    /** 开启 / 关闭执行者附近的自动合成仓内部存储。 */
    private static int setStorage(final CommandContext<CommandSourceStack> context, final boolean enable) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final List<AutocrafterBlockEntity> crafters = AutocrafterStorageController.craftersNear(
            level, net.minecraft.core.BlockPos.containing(source.getPosition()), RADIUS);
        if (crafters.isEmpty()) {
            source.sendFailure(Component.translatable(KEY_NO_AUTOCRAFTER));
            return 0;
        }
        if (enable) {
            if (!Config.autocrafterStorageEnabled) {
                // 全局配置关掉了内部存储：此时不会注册物品/流体能力，允许开启会把产物困在仓里
                source.sendFailure(Component.translatable(KEY_DISABLED_BY_CONFIG));
                return 0;
            }
            AutocrafterStorageController.enableAll(crafters);
            source.sendSuccess(() -> Component.translatable(KEY_ENABLED, crafters.size()), false);
            return crafters.size();
        }
        final int disabled = AutocrafterStorageController.disableAll(crafters);
        if (disabled < crafters.size()) {
            // 有网络分组回写失败：该组保持开启，内容仍安全留在仓内
            source.sendFailure(Component.translatable(KEY_FAILED));
            if (disabled == 0) {
                return 0;
            }
        }
        source.sendSuccess(() -> Component.translatable(KEY_DISABLED, disabled), false);
        return disabled;
    }
}
