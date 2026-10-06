package cretae.cookiewyq.rs_create_compat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.support.AutocrafterStorageController;
import cretae.cookiewyq.rs_create_compat.support.BlockContentMode;
import cretae.cookiewyq.rs_create_compat.support.BlockContentPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.RsccIntermediateReusePolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccRefillPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyPolicy;
import cretae.cookiewyq.rs_create_compat.support.RsccSupplyStrategy;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

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
        // 「序列装配链路诊断日志」开关（运行时，无权限）：
        //   /rs_create_compat debug assembly on|off   （推荐写法，用户验收要求的形式）
        //   /rs_create_compat assemblydebug on|off    （等价别名，保持旧写法可用）
        // 关掉后除「开关状态变化」那一条外不再输出任何日志（见 RsccAssemblyDebug#setEnabled）。
        addAssemblyDebugToggle(root);
        // 「一键诊断快照」（无权限，普通玩家可用）：
        //   /rs_create_compat diag       把当前全部相关状态导出为 run/rscc_diag/<时间戳>/snapshot.json
        //   /rs_create_compat diag run   （可选）先强制打开诊断日志，再导出 + 打分节标记；只观测，
        //                                不下单、不代玩家操作、不改变任何任务状态
        root.then(Commands.literal("diag")
            .then(Commands.literal("run")
                .executes(context -> exportDiag(context, "run")))
            .executes(context -> exportDiag(context, "manual")));
        dispatcher.register(root);
    }

    /**
     * 「一键诊断快照」处理器（<b>只读</b>：不改任何游戏状态 / 任务状态）。
     * <p>{@code tag = "run"} 时先把运行时诊断日志开关强制打开（只改日志开关，不碰产线），
     * 再导出快照；日志里会留下 {@code [rscc-diag] BEGIN/…/END} 分节标记。</p>
     */
    private static int exportDiag(final CommandContext<CommandSourceStack> context, final String tag) {
        final MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        if ("run".equals(tag)) {
            // 只改「日志开关」这一件事：不抽料、不投料、不下单、不挂起 / 恢复任何任务。
            RsccAssemblyDebug.setEnabled(true);
        }
        final cretae.cookiewyq.rs_create_compat.support.RsccDiag.Result result =
            cretae.cookiewyq.rs_create_compat.support.RsccDiag.snapshot(server, tag);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_DIAG_EXPORTED, result.path().toString(), result.summaryLines()),
            false);
        return 1;
    }

    /** 挂载「序列装配诊断日志」开关的两种写法（同一处理器，行为完全一致）。 */
    private static void addAssemblyDebugToggle(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("assemblydebug")
            .then(Commands.literal("on")
                .executes(context -> setAssemblyDebug(context, true)))
            .then(Commands.literal("off")
                .executes(context -> setAssemblyDebug(context, false)))
            // 只输入到 assemblydebug 一层：回报当前状态 + 用法提示
            .executes(context -> reportAssemblyDebug(context)));
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

    /** 设置「序列装配诊断日志」运行时开关（状态变化本身会打一条 INFO，见 {@link RsccAssemblyDebug#setEnabled}）。 */
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
