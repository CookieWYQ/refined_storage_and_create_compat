package cretae.cookiewyq.rs_create_compat.support;

import com.simibubi.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FTB Ultimine（连锁采矿）集成：让<b>两种框架</b>与<b>Create 剪贴板</b>都支持它的<b>右键连锁</b>。
 *
 * <p><b>为什么只有它会触发批量</b>：用户明确要求「我的意思不是指直接让他批量套壳，我的意思指的是
 * 在连锁模式下就是 FTB 的连锁模式下进行批量套上这个伪装框架」「我并不是想让直接让空手套壳」。
 * 因此普通右键只套命中那一格（见 {@code item/SeparationFrameItem} / {@code item/CamouflageFrameItem}），
 * 而「成片套壳」这条唯一的批量路径挂在这里 —— 玩家<b>按住连锁键</b>右键时，FTB 才会调用本处理器。</p>
 *
 * <p><b>剪贴板为什么也挂在这里</b>：用户第 2 条要求「使用连锁框住的所有输入输出总线，一键配置」。
 * 剪贴板复制 / 粘贴的既有手势（{@code RsccBusClipboard}）是「单台 / 同一线缆簇」，而「连锁圈」这份
 * 候选集合只有 FTB 知道 —— 于是同一个右键处理器按<b>手里的物品</b>分派：剪贴板 →
 * {@link RsccBusClipboard#chainPaste}，框架 → {@link RsccSheathChain#chainSheath}，
 * <b>「要被伪装的方块」（例如黑曜石）→ {@link RsccCamouflageChain#chainApplyMaterial}</b>
 * （把连锁范围内已有的伪装框架一次性改成这种材质；用户原话「把它们全都设定成什么，比如说黑曜石之类的」）。
 * 三者的触发条件完全相同（按住连锁键 + 右键），因此玩家不需要记三套操作，
 * 也不会出现「拿着剪贴板连锁却把框架套上去」这种串味：分派顺序是剪贴板 → 框架 → 材质，
 * 每一支只在「手里确实是那一类东西」时才接手，其余一律返回 0 交回 FTB / 原版。</p>
 *
 * <h2>接入点（已按 {@code run/mods/ftb-ultimine-neoforge-2101.1.15.jar} 的真实字节码核对）</h2>
 * <ul>
 *     <li><b>事件</b>：{@code dev.ftb.mods.ftbultimine.api.rightclick.RegisterRightClickHandlerEvent}
 *     —— 一个<b>接口</b>，静态字段 {@code public static final Event<RegisterRightClickHandlerEvent> REGISTER}
 *     是 Architectury 的 {@code dev.architectury.event.Event}；FTB 在
 *     {@code serverStarting} 时调 {@code REGISTER.invoker().register(dispatcher)} 把注册事件发一遍
 *     （自己的内建处理器也在同一个时机登记，所以本模组挂监听器的时机与之完全一致）；</li>
 *     <li><b>登记入口</b>：{@code RegisterRightClickHandlerEvent#register(Dispatcher)}
 *     （{@code Dispatcher = RegisterRightClickHandlerEvent$Dispatcher}，它只有一个方法
 *     {@code void registerHandler(RightClickHandler)}）—— 实现在 FTB 侧是枚举
 *     {@code dev.ftb.mods.ftbultimine.rightclick.RightClickDispatcher.INSTANCE}；</li>
 *     <li><b>处理器接口</b>：{@code dev.ftb.mods.ftbultimine.api.rightclick.RightClickHandler}，
 *     {@code int handleRightClickBlock(ShapeContext, InteractionHand, Collection<BlockPos>)}
 *     返回「实际影响的方块数」，返回 {@code > 0} 时 FTB 立刻返回
 *     （{@code EventResult.interruptTrue()}：取消这次原版右键）并按它自己的冷却 / 经验规则记账；
 *     另有一个默认方法 {@code boolean hurtItemAndCheckIfBroken(ServerPlayer, InteractionHand)}
 *     （本模组不消耗工具耐久，固定返回 {@code false}）；</li>
 *     <li><b>候选集合由 FTB 给出</b>：那个 {@code Collection<BlockPos>} 就是它按「当前连锁形状 +
 *     配置上限」算好的候选（{@code RightClickDispatcher} 直接把
 *     {@code FTBUltiminePlayerData#cachedPositions()} 传进来）；上限读自
 *     {@code ShapeContext#maxBlocks()}（那名玩家<b>生效</b>的值，已含它的 ranks 节点与属性修正）。
 *     本模组沿用它，不另发明范围 / 上限数值。</li>
 *     <li><b>是否需要按物品注册</b>：没有按物品的扩展点，处理器是<b>全局</b>的（每个右键回调都会轮到）。
 *     因此本实现第一件事就是确认「手里拿的是不是分隔框架 / 伪装框架」，不是就立刻返回 0 把交互让给别人。</li>
 * </ul>
 *
 * <h2>为什么全程反射（工程既有做法，见 {@link RsccCuriosTerminalSlot}）</h2>
 * <p>FTB Ultimine 是<b>可选</b>依赖：本模组必须能在没装它的环境里照常加载、照常工作。
 * 只要我们在编译期引用了它的类，未安装时就可能在校验阶段解析失败
 * （{@code NoClassDefFoundError}）—— 而这里要做的事情非常小（注册一个右键处理器 + 读三个值），
 * 完全够用反射完成。于是：</p>
 * <ul>
 *     <li>本类<b>没有</b>任何 {@code dev.ftb.mods.*} / {@code dev.architectury.*} 的 import 或符号，
 *     编译期不依赖它（{@code build.gradle} 里也不需要它的 jar）；</li>
 *     <li>{@link #register()} 第一句就是 {@link OptionalDeps#isFtbUltimineLoaded()}：
 *     没装 → 直接返回并记一条 info 日志，连 {@code Class.forName} 都不会执行；</li>
 *     <li>API 名字变了 / 缺类 / 反射失败 / 连它的类都解析失败（{@code LinkageError}）→ 只记<b>一条</b>
 *     warning（带上具体是哪个 API 类 / 方法），本模组行为回到「一格一格右键」，绝不抛给玩家、绝不影响加载，
 *     也绝不在每次右键时重复刷屏（失败只通报一次，见 {@link #warnOnce}）；</li>
 *     <li><b>玩家侧也要知道</b>（第 82 轮补）：反射注册失败 / 没装 FTB 时，{@link #state()} 会是
 *     {@link State#FAILED} / {@link State#NOT_INSTALLED}，{@link #unavailableMessageKey()} 给出对应文案键；
 *     {@link RsccBusClipboard} 在玩家复制完总线配置后（每人每局一次）把结论用聊天消息告诉他 ——
 *     否则「连锁按了没反应」在游戏里完全无法自查。成功登记处理器时还会记一条 INFO
 *     （{@code FTB Ultimine register event observed}），那是「反射注册真的走通了」的运行期证据。</li>
 * </ul>
 *
 * <p><b>客户端不做预测</b>：FTB Ultimine 自己会在客户端把「当前连锁形状」的方块描边显示出来
 * （它的 {@code SendShapePacket} 把选择结果下发到客户端），玩家按连锁键时看到的就是它给出的候选；
 * 本模组没必要再画一套。真正的套壳结果由 {@link RsccSheaths} / {@link RsccCamouflage} 的服务端快照（S2C）下发，
 * 客户端下一帧就是新状态 —— 因此这里只做服务端权威处理，不新增任何网络包。</p>
 */
public final class RsccUltimineIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger(RsccUltimineIntegration.class);

    /** FTB Ultimine 的公开 API：注册右键连锁处理器的 Architectury 事件（接口 + 静态字段 REGISTER）。 */
    private static final String EVENT_CLASS =
        "dev.ftb.mods.ftbultimine.api.rightclick.RegisterRightClickHandlerEvent";
    /** 上面那个事件的 {@code Dispatcher}（提供 {@code registerHandler}）。 */
    private static final String DISPATCHER_CLASS = EVENT_CLASS + "$Dispatcher";
    /** FTB Ultimine 的公开 API：右键连锁处理器接口。 */
    private static final String HANDLER_CLASS =
        "dev.ftb.mods.ftbultimine.api.rightclick.RightClickHandler";
    /** Architectury 的事件基类（FTB 的 {@code REGISTER} 就是它的实例；只反射用它的 {@code register}）。 */
    private static final String ARCHITECTURY_EVENT_CLASS = "dev.architectury.event.Event";

    /** 「失败原因只记一次」的闸门（避免每次右键都刷一条 warning）。 */
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

    // ------------------------------------------------------------------
    // 第 83 轮：连锁链路的「断在哪一环」观测点
    //
    // 用户实机反馈「连锁还是没生效」，而上一轮的运行期证据只到「处理器注册成功」。
    // 从那一步到「方块真的被改了」之间还有 6 道闸（全部按 FTB 2101.1.15 的字节码核对过）：
    //   ① 我们的代理被 FTB 真的调用了吗（RightClickDispatcher#dispatchRightClick 遍历 handlers）；
    //   ② 参数的四个字段真的填了吗（ShapeContext#player/origPos/maxBlocks + data.cachedPositions()）；
    //   ③ 手里拿的是不是剪贴板 / 框架（不是就交回 FTB）；
    //   ④ 命中格是不是本模组的总线（不是就静默交回 —— 这是「玩家点了别的方块」的正常路径）；
    //   ⑤ 各种跳过原因各发生了几次（权限 / 种类 / 距离 / 未加载 / 上限）；
    //   ⑥ 最终真的写进去几条。
    // 任何一环没有日志，「没生效」就永远只能靠猜。下面这几个状态位只服务于日志，不参与判定。
    // ------------------------------------------------------------------

    /** 是否已经打过「handler 被调用」那一条。它在**首次**时不受 devLogs 控制（每人每进程一次）。 */
    private static final AtomicBoolean HANDLER_CALL_SEEN = new AtomicBoolean();

    /** 连锁链路细节日志的时间节流（毫秒）：同一窗口内只放行一条，并附上被压掉的条数。 */
    private static final long CHAIN_LOG_WINDOW_MS = 1000L;

    /** 上一次连锁日志的时刻（服务端主线程读写，不需要同步）。 */
    private static long lastChainLogAt;

    /** 当前窗口内被节流压掉的条数。 */
    private static final AtomicInteger CHAIN_LOG_SUPPRESSED = new AtomicInteger();

    /** 「FTB 的连锁键状态」这个反射探针是否已经失败过（失败只通报一次，之后不再尝试）。 */
    private static final AtomicBoolean PRESS_PROBE_FAILED = new AtomicBoolean();

    /**
     * 「FTB 的连锁形状候选」探针是否已经失败过（失败只通报一次）。
     * <p>与 {@link #PRESS_PROBE_FAILED} 分开：读键状态走 {@code isPressed()}，读形状走
     * {@code cachedPositions()} —— 一个 API 漂移不该把另一个诊断也一起判死。</p>
     */
    private static final AtomicBoolean POSITION_PROBE_FAILED = new AtomicBoolean();

    /**
     * 给玩家看的诊断文案键（第 82 轮新增）：连锁粘贴「按了没反应」时，这两条环境前提
     * —— <b>装没装 FTB Ultimine</b>、<b>本模组的反射注册成没成功</b> —— 玩家在游戏里看不出来。
     * {@link RsccBusClipboard} 会在玩家复制总线配置后（每人每局一次）把这里返回的结论告诉他。
     */
    private static final String MSG_NOT_INSTALLED = "message.rs_create_compat.ultimine.not_installed";
    /** 反射注册失败 / 处理期异常（降级为单格）。 */
    private static final String MSG_INTEGRATION_FAILED = "message.rs_create_compat.ultimine.integration_failed";

    /**
     * 集成状态。<b>为什么需要它</b>：日志只有翻文件才看得到，而玩家需要的是「在游戏里被告知」；
     * 同时它也让「注册到底走到哪一步」这件事有一个可判定的口径（而不是只凭一行 INFO 猜）。
     */
    public enum State {
        /** 还没跑过 {@link #register()}（正常启动流程里瞬态；此时不要对玩家下任何结论）。 */
        UNKNOWN,
        /** 没装 FTB Ultimine：连锁入口不存在，但单台 / 整簇粘贴照旧。 */
        NOT_INSTALLED,
        /** 监听器已挂到 FTB 的注册事件上，但还没看到它发那次事件（正常：要等它开服）。 */
        HOOKED,
        /** FTB 发过注册事件、我们的右键处理器已经登记进它的 dispatcher —— <b>这条链路真的通了</b>。 */
        HANDLER_REGISTERED,
        /** 反射注册失败 / 处理期抛异常：已降级为「一格一格」，日志里有一条可定位的 warning。 */
        FAILED
    }

    /** 当前状态（volatile：构造期写、游戏线程读）。 */
    private static volatile State state = State.UNKNOWN;

    private RsccUltimineIntegration() {
    }

    /**
     * 注册「连锁套壳」处理器（在模组构造时调用一次）。
     *
     * <p>时机与 FTB 自己注册内建处理器的时机一致（都在它 {@code serverStarting} 之前把自己的监听器
     * 挂到那个事件上）。FTB 每次开服都会把注册事件重发一遍，所以这里不缓存状态、也不做幂等，
     * 与它的内建处理器行为完全一样。</p>
     *
     * <p><b>为什么连 {@link LinkageError} 也要接住</b>：{@code RegisterRightClickHandlerEvent} 的
     * 静态字段是 Architectury 的 {@code Event}，光解析它的静态初始化就可能因为
     * 「装了 ftbultimine 却缺建筑 API」而抛 {@code NoClassDefFoundError} —— 那属于 {@code Error}
     * 而不是 {@code Exception}，但本模组仍然必须照常加载，因此这里一并接住并降级为单格。</p>
     */
    public static void register() {
        if (!OptionalDeps.isFtbUltimineLoaded()) {
            state = State.NOT_INSTALLED;
            LOGGER.info("FTB Ultimine is not loaded: frames stay single-cell "
                + "(right-click frames exactly one cable/pipe at a time), and clipboard chain-paste "
                + "is unavailable (single-bus / whole-cluster paste still work). "
                + "Players are told once when they copy a bus config.");
            logInitState("FTB 联动初始化（未安装）");
            return;
        }
        try {
            final ClassLoader loader = RsccUltimineIntegration.class.getClassLoader();
            final Class<?> eventClass = Class.forName(EVENT_CLASS, false, loader);
            final Class<?> dispatcherClass = Class.forName(DISPATCHER_CLASS, false, loader);
            final Class<?> handlerClass = Class.forName(HANDLER_CLASS, false, loader);
            final Object event = eventClass.getField("REGISTER").get(null);
            // 监听器：FTB 发注册事件时会调用它的 register(Dispatcher)，我们在这时把处理器登记进去
            final Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{eventClass},
                (proxy, method, args) -> onRegisterEvent(proxy, method, args, loader, handlerClass,
                    dispatcherClass));
            registerListenerMethod(event.getClass()).invoke(event, listener);
            state = State.HOOKED;
            LOGGER.info("FTB Ultimine integration: listener attached to {} (waiting for its register "
                + "event at server start). Holding a separation/camouflage frame, right-clicking a "
                + "cable/fluid pipe with the Ultimine key held frames the whole same-family run; "
                + "holding a Create clipboard, it pastes the bus config to every same-kind bus in the "
                + "chained shape", EVENT_CLASS);
            logInitState("FTB 联动初始化（监听器已挂）");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 绝不因为集成失败而影响加载 / 影响既有玩法：退回单格套壳，日志里留下可定位的原因
            state = State.FAILED;
            warnOnce("Could not hook FTB Ultimine (API missing or changed: " + EVENT_CLASS + " / "
                + DISPATCHER_CLASS + " / " + HANDLER_CLASS + "); frames keep working one cell at a time "
                + "and clipboard chain-paste is unavailable. Players are told once when they copy a "
                + "bus config", e);
            logInitState("FTB 联动初始化（失败，已降级）");
        }
    }

    /**
     * <b>初始化时无条件打一条 INFO</b>（不受 {@code devLogs} 控制，用户要求）。
     *
     * <p>用户实机反馈的痛点不是「日志不够多」，而是「得先开 devLogs / 翻文件才知道联动在不在」。
     * 因此初始化结束时只留这一条<b>固定格式</b>的行：{@code [rscc-ultimine] state=...}。
     * 它与 {@code /rs_create_compat chain-debug} 报的是同一个状态源，用户不必先学会开日志。</p>
     */
    private static void logInitState(final String phase) {
        LOGGER.info("[rscc-ultimine] {}：state={}（{}），FTB Ultimine 已加载={}；"
                + "随时可用 /rs_create_compat chain-debug 现场自查",
            phase, state, stateMeaning(state), OptionalDeps.isFtbUltimineLoaded());
    }

    /**
     * 给玩家看的「连锁粘贴不可用」文案键；<b>可用 / 还说不准</b>时返回 {@code null}。
     *
     * <p>为什么 {@link State#HOOKED} 与 {@link State#UNKNOWN} 都返回 {@code null}：前者是「监听器挂上了、
     * 但 FTB 还没开服发事件」，后者是「{@link #register()} 还没跑」—— 这两种状态下说「不可用」都是<b>误报</b>，
     * 而误报比不报更伤（玩家会去乱改配置）。只有确定的两头才下结论。</p>
     */
    @Nullable
    public static String unavailableMessageKey() {
        final State current = state;
        if (current == State.NOT_INSTALLED) {
            return MSG_NOT_INSTALLED;
        }
        if (current == State.FAILED) {
            return MSG_INTEGRATION_FAILED;
        }
        return null;
    }

    /** 当前状态（诊断 / 自检用）。 */
    public static State state() {
        return state;
    }

    /**
     * <b>我们的右键处理器到底被 FTB 调用过没有</b>（本进程内，一旦调用过就是 {@code true}）。
     *
     * <p><b>为什么 {@link #state()} 不够</b>：{@code HANDLER_REGISTERED} 只证明「FTB 发过注册事件、
     * 我们把处理器登记进了它的 dispatcher」，<b>不能</b>证明它真的派发过一次。用户实机遇到的
     * 「按了连锁键却一行 {@code [rscc-chain-bus]} 都没有」正好落在两者之间：注册成功、但从未被调用。
     * 这个位由 {@link #chain} 的第一条日志顺手置位（{@link #HANDLER_CALL_SEEN}），
     * 于是 {@code /rs_create_compat chain-debug} 能当场把「我们没注册上」与「FTB 没派发」分开。</p>
     */
    public static boolean handlerCallSeen() {
        return HANDLER_CALL_SEEN.get();
    }

    /**
     * <b>一条指令就能看清「断在哪一环」</b>的文本报告（{@code /rs_create_compat chain-debug} 的数据源）。
     *
     * <p>用户的要求是「不依赖日志也能判断」：这里把<b>四类事实</b>直接摊在一行行文本里 ——
     * ① 注册状态（{@link State} 的语义，而不是只有一个枚举名）；② 处理器是否真的被 FTB 调用过；
     * ③ FTB 自己认为「连锁键是否按住」（{@link #ultimineKeyPressed}，反射读 {@code isPressed}）；
     * ④ 手持物品 / 是否潜行（让用户对照自己的操作）。最后附一句结论，指明下一步该做什么。</p>
     *
     * <p>只读：不注册、不改状态、不写世界；任何反射失败都退化成「读不到」而不是异常。</p>
     *
     * @param player 执行指令的玩家；控制台 / 命令方块时为 {@code null}（此时键状态与手持无从谈起）
     */
    public static List<String> debugReport(@Nullable final ServerPlayer player) {
        final List<String> lines = new ArrayList<>();
        final boolean loaded = OptionalDeps.isFtbUltimineLoaded();
        final State current = state;
        lines.add("联动注册状态: " + current + " —— " + stateMeaning(current));
        lines.add("处理器是否被 FTB 调用过（本进程）: "
            + (handlerCallSeen() ? "是（FTB 的派发已经走到我们）" : "否（还没派发到我们）"));
        if (player == null) {
            lines.add("FTB 认为连锁键按住: 无执行者玩家（控制台 / 命令方块），读不到");
            lines.add("手持: 无执行者玩家；潜行: 无");
        } else {
            final Boolean pressed = ultimineKeyPressed(player);
            lines.add("FTB 认为连锁键按住: "
                + (pressed == null ? "读不到（未安装 / 探针失败）" : pressed ? "是" : "否")
                + "（按住连锁键的同时执行本指令才能对照）");
            lines.add("手持(主手/副手): " + RsccAssemblyDebug.itemId(player.getMainHandItem().getItem())
                + " / " + RsccAssemblyDebug.itemId(player.getOffhandItem().getItem())
                + "；潜行: " + (player.isShiftKeyDown() ? "是" : "否"));
        }
        lines.add("结论: " + conclusion(loaded, current));
        return lines;
    }

    /** {@link State} 的中文含义（指令回报用，避免用户对着枚举名猜）。 */
    private static String stateMeaning(final State value) {
        return switch (value) {
            case UNKNOWN -> "还没跑过 register()（正常只出现在启动极早期）";
            case NOT_INSTALLED -> "没装 FTB Ultimine，连锁入口不存在";
            case HOOKED -> "监听器已挂到 FTB 的注册事件，等它开服发事件";
            case HANDLER_REGISTERED -> "处理器已登记进 FTB 的 dispatcher（注册这一步通了）";
            case FAILED -> "反射注册失败，已降级为单格";
        };
    }

    /** 指令最后那句「所以现在该做什么」。 */
    private static String conclusion(final boolean loaded, final State current) {
        if (!loaded) {
            return "FTB 未安装 ⇒ 没有连锁入口；请用「手持剪贴板 + 潜行 + 右键」整簇粘贴（保底，默认开启）";
        }
        if (current == State.FAILED || current == State.NOT_INSTALLED) {
            return "注册没走通（见启动时的 WARN 堆栈）⇒ FTB 不会调用我们；用「潜行 + 右键」保底整簇粘贴";
        }
        if (current == State.HOOKED) {
            return "监听器已挂、但 FTB 还没发注册事件（通常要等服务端开完）；先等一局再敲一次本指令";
        }
        if (current == State.HANDLER_REGISTERED) {
            return handlerCallSeen()
                ? "链路已通（处理器被调用过）：若结果仍不对，看命中格 / 剪贴板载荷 / 种类"
                : "注册成功但从未被调用 ⇒ 断在 FTB 那侧（多半是它认为连锁键没按住）；"
                    + "先对照上面的键状态，再用「潜行 + 右键」保底";
        }
        return "还没跑过初始化 register()（正常只出现在启动极早期）";
    }

    /**
     * <b>FTB 的「连锁键是否按住」在服务端的状态</b>（{@code null} = 读不到 / 没装）。
     *
     * <p><b>为什么要这个探针</b>：用户报告「按住连锁键右击总线没反应」，而这条链路在 FTB 侧的
     * 第一道闸就是 {@code FTBUltiminePlayerData#isPressed()}（字节码：{@code blockRightClick}
     * 第 57 行读它，false 就直接 {@code pass}，<b>根本不会派发到我们的处理器</b>）。
     * 也就是说「键到底有没有被 FTB 认为按住」决定了故障在<b>它那侧</b>还是<b>我们这侧</b> ——
     * 这件事光看我们自己的日志永远分不清。因此在本模组自己的右键入口（{@link RsccBusClipboard}
     * 的复制路径，排在 FTB 之后）顺手把它的状态读出来，写进同一条日志。
     * 全程反射、任何一步失败都只影响这条诊断，不影响玩法。</p>
     */
    @Nullable
    public static Boolean ultimineKeyPressed(final ServerPlayer player) {
        if (!OptionalDeps.isFtbUltimineLoaded() || PRESS_PROBE_FAILED.get()) {
            return null;
        }
        try {
            final ClassLoader loader = RsccUltimineIntegration.class.getClassLoader();
            final Class<?> modClass = Class.forName("dev.ftb.mods.ftbultimine.FTBUltimine", false, loader);
            final Object mod = modClass.getMethod("getInstance").invoke(null);
            if (mod == null) {
                return null;
            }
            final Object data = modClass.getMethod("getOrCreatePlayerData", Player.class).invoke(mod, player);
            if (data == null) {
                return null;
            }
            final Object pressed = data.getClass().getMethod("isPressed").invoke(data);
            return pressed instanceof Boolean value ? value : null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (PRESS_PROBE_FAILED.compareAndSet(false, true)) {
                // 只通报一次：探针本身坏掉不该在每次右键时刷屏，但也绝不静默（否则日志里「没有这一项」
                // 会被误读成「FTB 说键没按住」）
                LOGGER.warn("[rscc-chain] 读不到 FTB 的连锁键状态（探针失败，只通报一次）", e);
            }
            return null;
        }
    }

    /**
     * <b>FTB 当前连锁形状的候选坐标</b>（服务端；{@code null} = 读不到 / 没装 / 形状还没算出来）。
     *
     * <h2>为什么需要它（第 95 轮：左键粘贴「连锁不生效」）</h2>
     * <p>用户原话：「用右键应用能正常，用左键就不行；明明本来就该是左键的」。根因是<b>结构性的</b>：
     * FTB Ultimine 只对外提供<b>右键</b>的连锁扩展点
     * （{@code RegisterRightClickHandlerEvent} → {@code RightClickHandler#handleRightClickBlock}），
     * 它的公开 API 里<b>没有任何左键扩展点</b>（已按 {@code mods/ftb-ultimine-neoforge-2101.1.15.jar}
     * 全量字节码核对：整个 jar 里只有 {@code blockRightClick} 一处会派发给外部处理器）。
     * 于是「按住连锁键 + 左键」这条 Create 剪贴板的原始粘贴手势，在我们的左键入口里只能拿到
     * 命中格本身，连锁圈里其余总线一台都写不到 —— 玩家看到的就是「左键不行」。</p>
     *
     * <p>左键这条入口因此<b>自己</b>去读 FTB 已经算好的候选集合：它的
     * {@code FTBUltiminePlayerData} 每 tick 在连锁键按住时维护 {@code cachedPositions()}
     * （右键派发时 FTB 交给我们的正是同一份集合，见 {@code RightClickDispatcher}）。读的是
     * <b>public</b> 方法 {@code cachedPositions()} 与 {@code hasCachedPositions()}，
     * 与右键那条链路<b>同源同口径</b>，不另发明形状 / 上限。</p>
     *
     * <p><b>降级</b>：没装 / 反射失败 / 连锁键没按住 / 形状为空 ⇒ 返回 {@code null}，
     * 调用方退回「一台 / 一条线缆簇」的既有语义；探针失败只记一条 warning，绝不刷屏、绝不抛异常。</p>
     *
     * @param player 执行左键的玩家（服务端）
     * @return 不可变的候选坐标列表（可能为空 = 形状还没算好）；读不到时 {@code null}
     */
    @Nullable
    public static List<BlockPos> chainPositions(final ServerPlayer player) {
        if (!OptionalDeps.isFtbUltimineLoaded() || POSITION_PROBE_FAILED.get()) {
            return null;
        }
        try {
            final ClassLoader loader = RsccUltimineIntegration.class.getClassLoader();
            final Class<?> modClass = Class.forName("dev.ftb.mods.ftbultimine.FTBUltimine", false, loader);
            final Object mod = modClass.getMethod("getInstance").invoke(null);
            if (mod == null) {
                return null;
            }
            final Object data = modClass.getMethod("getOrCreatePlayerData", Player.class).invoke(mod, player);
            if (data == null) {
                return null;
            }
            final Class<?> dataClass = data.getClass();
            final Object has = dataClass.getMethod("hasCachedPositions").invoke(data);
            if (!(has instanceof Boolean cached) || !cached) {
                return null; // 连锁键没按住 / 形状还没算出来：不猜，交给调用方的降级路径
            }
            final Object raw = dataClass.getMethod("cachedPositions").invoke(data);
            if (!(raw instanceof Collection<?> collection)) {
                return null;
            }
            final List<BlockPos> positions = new ArrayList<>(collection.size());
            for (final Object element : collection) {
                if (element instanceof final BlockPos pos) {
                    positions.add(pos);
                }
            }
            return positions.isEmpty() ? null : List.copyOf(positions);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (POSITION_PROBE_FAILED.compareAndSet(false, true)) {
                // 只通报一次：探针坏掉不该在每次左键时刷屏，但也不能静默（否则「左键只粘一台」
                // 会被误判成搬运 bug，而不是「读不到 FTB 的形状」）
                LOGGER.warn("[rscc-chain] 读不到 FTB 的连锁形状候选（探针失败，只通报一次）；"
                    + "左键将退回单台 / 整簇粘贴", e);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 反射：注册路径
    // ------------------------------------------------------------------

    /**
     * FTB 调用 {@code RegisterRightClickHandlerEvent#register(Dispatcher)} 时：把我们的处理器登记进去。
     *
     * <p><b>第 82 轮补的几道闸</b>（都是「静默失效」的高发点）：</p>
     * <ol>
     *     <li><b>先处理 Object 方法</b>：动态代理也会收到 {@code toString / hashCode / equals} ——
     *     旧实现一律返回 {@code null}，一旦 FTB / Architectury 在日志里拼一下这个监听器就是 NPE；</li>
     *     <li><b>整段包 try/catch</b>：处理器登记失败时不再把异常从代理里抛回 FTB 的事件分发
     *     （那会沿着它的开服流程冒出去），而是降级成 {@link State#FAILED} + 一条 warning；</li>
     *     <li><b>成功时记一条 INFO</b>：这是「反射注册真的走到最后一步」的唯一运行期证据 ——
     *     {@code HOOKED} 只说明监听器挂上了，{@code HANDLER_REGISTERED} 才说明 FTB 发了事件、
     *     我们的处理器进了它的 dispatcher。</li>
     * </ol>
     */
    @Nullable
    private static Object onRegisterEvent(final Object proxy, final Method method, final Object[] args,
                                          final ClassLoader loader, final Class<?> handlerClass,
                                          final Class<?> dispatcherClass) {
        switch (method.getName()) {
            case "toString":
                return "RsccUltimineRegisterListener";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            default:
                break;
        }
        if (!"register".equals(method.getName()) || args == null || args.length != 1 || args[0] == null) {
            return defaultOf(method.getReturnType());
        }
        try {
            final Object handler = Proxy.newProxyInstance(loader, new Class<?>[]{handlerClass}, HANDLER_CALLS);
            // 走接口声明的方法：实现类（RightClickDispatcher）可能是包私有，那样直接取实现类的方法会有可见性问题
            dispatcherClass.getMethod("registerHandler", handlerClass).invoke(args[0], handler);
            state = State.HANDLER_REGISTERED;
            LOGGER.info("FTB Ultimine register event observed: right-click handler registered with {} "
                + "(clipboard chain-paste + frame chain are now live)", args[0].getClass().getName());
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 登记失败 = 连锁永远收不到回调：降级为单格，并且只记一次、可定位
            state = State.FAILED;
            warnOnce("Could not register the FTB Ultimine right-click handler (registerHandler on "
                + dispatcherClass.getName() + "); frames keep working one cell at a time and clipboard "
                + "chain-paste is unavailable", e);
        }
        return defaultOf(method.getReturnType());
    }

    /**
     * 在事件对象上找「注册监听器」的方法。
     * <p>优先用 Architectury 的公开接口 {@code Event#register(T)}：泛型擦除后参数是 {@code Object}，
     * 从公开接口取方法可以避开「实现类是包私有」的可见性问题；版本不一致时退化为按名字找唯一的 1 参方法。</p>
     */
    private static Method registerListenerMethod(final Class<?> eventImplClass) throws NoSuchMethodException {
        try {
            return Class.forName(ARCHITECTURY_EVENT_CLASS, false, eventImplClass.getClassLoader())
                .getMethod("register", Object.class);
        } catch (final ReflectiveOperationException | LinkageError ignored) {
            // 继续走下面的兜底扫描
        }
        for (final Method candidate : eventImplClass.getMethods()) {
            if ("register".equals(candidate.getName()) && candidate.getParameterCount() == 1) {
                return candidate;
            }
        }
        throw new NoSuchMethodException("no register(...) on " + eventImplClass.getName());
    }

    // ------------------------------------------------------------------
    // 反射：处理器实现
    // ------------------------------------------------------------------

    /** 我们的 {@code RightClickHandler} 实现（用动态代理，避免编译期引用它的接口）。 */
    private static final InvocationHandler HANDLER_CALLS = RsccUltimineIntegration::onHandlerCall;

    /**
     * 处理器代理的<b>总入口</b>：整段 try/catch 全包。
     *
     * <p><b>为什么连这里也要接住</b>：FTB 在它自己的右键回调里直接调用本代理，
     * 一旦这里漏出异常，就会沿着 FTB 的事件链冒到原版交互里 —— 玩家看到的是右键报错 / 崩溃。
     * 因此任何异常都在这里被降级成「本模组不接手这次右键」（返回 0 / 返回各方法的安全默认值），
     * 由下面的物品路径照常执行「只套命中那一格」。</p>
     */
    private static Object onHandlerCall(final Object proxy, final Method method, final Object[] args) {
        try {
            switch (method.getName()) {
                case "handleRightClickBlock":
                    return chain(args);
                case "hurtItemAndCheckIfBroken":
                    // 本模组不消耗工具耐久（框架不是可损耗工具），且该方法带 @ApiStatus.NonExtendable
                    return Boolean.FALSE;
                case "toString":
                    return "RsccFrameChainHandler";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length == 1 && proxy == args[0];
                default:
                    // 将来 API 新增的默认方法：一律返回「什么都没做」的默认值，绝不打断连锁流程
                    return defaultOf(method.getReturnType());
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 【第 83 轮】这里以前只有 warnOnce(整局一次) —— 第一次之后的异常**完全静默**，
            // 而这条链路的异常恰好等于「连锁永远返回 0，玩家看到的就是没反应」。
            // 现在：第一次完整堆栈 WARN（唯一能看见「异常被吞」的机会），之后按次数汇总，
            // 既不刷屏也不再让第 2..N 次消失。
            handlerFailure(method.getName(), e);
            return defaultOf(method.getReturnType());
        }
    }

    /** 处理器调用失败的计数（只服务日志）。 */
    private static final AtomicInteger HANDLER_FAILURES = new AtomicInteger();

    /**
     * 处理器抛异常时的日志：<b>第一次带完整堆栈的 WARN</b>，之后每 100 次汇总一条。
     *
     * <p>为什么第一次必须带堆栈：这条路径最终返回 {@code 0}（本模组不接手这次右键），
     * 从玩家视角与「正常交回」完全一样。没有堆栈，就永远不知道是 API 变了、还是某个方块实体
     * 在写入时炸了。</p>
     */
    private static void handlerFailure(final String methodName, final Throwable cause) {
        final int count = HANDLER_FAILURES.incrementAndGet();
        if (count == 1) {
            LOGGER.warn("[rscc-chain] FTB 连锁处理器 {} 抛异常（第 1 次，完整堆栈）；本次按 0 处理、"
                + "退回单格，后续同类只汇总不再打堆栈", methodName, cause);
        } else if (count <= 5 || count % 100 == 0) {
            LOGGER.warn("[rscc-chain] FTB 连锁处理器 {} 再次抛异常（累计 {} 次）：{}",
                methodName, count, cause.toString());
        }
    }

    /**
     * {@code handleRightClickBlock(ShapeContext, InteractionHand, Collection<BlockPos>)}：
     * 读出需要的三个值，交给 {@link RsccSheathChain#chainSheath} 做真正的判定与落库。
     *
     * <p><b>参数语义（按 FTB 2101.1.15 的字节码核对，别凭印象）</b>：</p>
     * <ul>
     *     <li>{@code args[0]} = {@code ShapeContext}（record：{@code player() / origPos() / face() /
     *     origState() / matcher() / maxBlocks()}）；</li>
     *     <li>{@code args[1]} = {@code InteractionHand}（玩家这次右键用的是哪只手 —— 剪贴板要按这只手取）；</li>
     *     <li>{@code args[2]} = 候选集合，来源是 {@code RightClickDispatcher#dispatchRightClick} 传进来的
     *     {@code FTBUltiminePlayerData#cachedPositions()}（即本次连锁形状算出来的方块列表）—— <b>不是</b>
     *     FTB 原始事件里的某个字段，所以它一定非 null、但可能只有命中这一格。</li>
     * </ul>
     *
     * <p><b>返回值语义</b>：{@code >0} = 本模组真的改了几格，FTB 会立刻 {@code EventResult.interruptTrue()}
     * 取消这次原版右键、并且按 {@code max(0, 返回值-1)} 记经验代价；{@code 0} = 本模组不接手，
     * FTB 继续问下一个处理器 / 交回原版。因此「返回 0」本身不是错误，但<b>静默地返回 0</b> 是 ——
     * 那正是玩家眼里的「按了没反应」。</p>
     *
     * <p>任何异常都吞在上面并返回 {@code 0}（= 本模组不接手这次右键）：宁可退回单格套壳，
     * 也不能让连锁把玩家的右键卡死。</p>
     */
    private static Object chain(final Object[] args) throws ReflectiveOperationException {
        if (args == null || args.length < 3) {
            chainLog(true, "①handler 被调用但参数不足（args=%d）⇒ 返回 0（FTB 交回下一个处理器）",
                args == null ? 0 : args.length);
            return 0;
        }
        final Object first = args[0];
        final Object maxBlocksValue = first == null ? null : read(first, "maxBlocks");
        final Object playerValue = first == null ? null : read(first, "player");
        final Object anchorValue = first == null ? null : read(first, "origPos");
        if (!(playerValue instanceof ServerPlayer player)
            || !(anchorValue instanceof BlockPos anchor)
            || !(args[1] instanceof InteractionHand hand)
            || !(maxBlocksValue instanceof Number maxBlocks)
            || !(args[2] instanceof Collection<?> raw)) {
            // ② 参数没填满：这是「API 版本变了」的特征，必须留痕（否则表现为「连锁静默失效」）
            chainLog(true, "②handler 参数类型不符 ⇒ 返回 0：player=%s origPos=%s hand=%s maxBlocks=%s 候选=%s",
                typeName(playerValue), typeName(anchorValue), typeName(args[1]),
                typeName(maxBlocksValue), typeName(args[2]));
            return 0;
        }
        final List<BlockPos> candidates = new ArrayList<>(raw.size());
        for (final Object element : raw) {
            if (element instanceof BlockPos pos) {
                candidates.add(pos);
            }
        }
        // 手里是 Create 剪贴板 = 另一条明确的语义「连锁批量粘贴总线配置」（见 RsccBusClipboard#chainPaste）：
        // 剪贴板不是框架，链到 RsccSheathChain 只会一路返回 0，因此在分派处就分开，读起来也更清楚。
        final ItemStack held = player.getItemInHand(hand);
        // ① 这一条是「最小可验证路径」的判据：**有它 ⇒ FTB 真的派发到我们了**。
        // 首次不受 devLogs 控制（每人每进程一次），之后的细节按 devLogs + 1 秒窗口节流。
        chainLog(true, "①handler 被调用：hand=%s anchor=%s 候选=%d 上限=%d 手持=%s",
            hand, anchor, candidates.size(), maxBlocks.intValue(), held.getItem());
        // ------------------------------------------------------------------
        // 【分派顺序的硬约束（第 87 轮写死在这里，自检 selfcheck_round77 逐句断言）】
        //   剪贴板 → 框架（套上 / 取下） → 材质（改成手里那种方块）
        // 为什么剪贴板必须排第一：
        //   ① 剪贴板是 BlockItem，排在材质前面是**必需**的 —— 否则「拿着剪贴板连锁」会被
        //      RsccCamouflageChain 当成「拿方块改材质」（它只判 BlockItem），在已经裹了外壳的
        //      总线 / 线缆上把外壳材质换成剪贴板方块；
        //   ② 这一支**无论返回值是 0 还是 >0 都直接 return**：0 表示「这次连锁不归总线管」，
        //      正确做法是交回 FTB / 原版，而不是让后面的两支再猜一遍玩家的意图。
        //      于是「总线分支被前面的分支抢掉」在结构上不可能发生 —— 它前面一支都没有。
        // 与伪装材质连锁的差异（用户实机「材质能连、配置不能」的确切差异）：
        //   差别不在分派，而在**目标方块能不能被 FTB 的同方块洪泛走到**：材质目标（线缆 / 框架）
        //   连成一片，总线之间却隔着线缆 ⇒ FTB 交给我们的候选只剩命中格。兜底在
        //   RsccBusClipboard#chainPaste 里沿线缆簇补齐（见那里的类注释）。
        // ------------------------------------------------------------------
        if (AllBlocks.CLIPBOARD.isIn(held)) {
            final int applied =
                RsccBusClipboard.chainPaste(player, hand, anchor, candidates, maxBlocks.intValue());
            // ⑥ 本次连锁的最终结果（剪贴板分支）。细节（各跳过原因计数）由 chainPaste 自己打。
            chainLog(false, "⑥剪贴板连锁结束：applied=%d（0 = 这次右键交回 FTB / 原版）", applied);
            return applied;
        }
        // ② 框架（套上 / 取下）：语义与优先级一字未改 —— 拿框架连锁永远是「套 / 取框架」。
        final int framed =
            RsccSheathChain.chainSheath(player, hand, anchor, candidates, maxBlocks.intValue());
        // ⑥ 框架分支的结果（0 = 手里不是框架 / 不是同族线缆 ⇒ 继续往下分派）
        chainLog(false, "⑥框架连锁结束：applied=%d（0 = 手里不是框架 / 不是同族线缆）", framed);
        if (framed > 0) {
            return framed;
        }
        // ③ 手里拿的是<b>要被伪装的方块</b>（例如黑曜石）+ 命中格已经裹着框架 = 「连锁应用材质」。
        // 只有前面两条都明确不接手（framed == 0）才会走到这里，因此与既有的「连锁套壳 / 取回」
        // 和「剪贴板批量粘贴」都不会打架（触发区分见 RsccCamouflageChain 的类注释）。
        final int material =
            RsccCamouflageChain.chainApplyMaterial(player, hand, anchor, candidates, maxBlocks.intValue());
        // ⑥ 本次连锁的最终结果（材质分支）
        chainLog(false, "⑥连锁应用材质结束：applied=%d（0 = 手里不是方块 / 命中格不是伪装格）", material);
        return material;
    }

    /** 反射失败时给日志用的「这个值到底是什么类型」，避免日志里只有一句「参数不符」。 */
    private static String typeName(@Nullable final Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /**
     * 连锁链路的观测日志（<b>受 {@code devLogs} 控制 + {@value #CHAIN_LOG_WINDOW_MS} 毫秒节流</b>），
     * 唯一的例外是 {@code firstAlways=true} 且是**本进程第一次**被 FTB 调用：
     * 那一条不受开关控制 —— 否则用户不开 devLogs 时，「handler 到底有没有被调用」这个最关键的
     * 问题依然无法用一份日志回答。
     */
    private static void chainLog(final boolean firstAlways, final String format, final Object... args) {
        final boolean firstCall = firstAlways && HANDLER_CALL_SEEN.compareAndSet(false, true);
        if (!firstCall && !RsccAssemblyDebug.isEnabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!firstCall && now - lastChainLogAt < CHAIN_LOG_WINDOW_MS) {
            CHAIN_LOG_SUPPRESSED.incrementAndGet();
            return;
        }
        lastChainLogAt = now;
        final int suppressed = CHAIN_LOG_SUPPRESSED.getAndSet(0);
        final String suffix = suppressed == 0 ? "" : "（上一窗口压掉 " + suppressed + " 条同类）";
        final String prefix = firstCall ? "[rscc-chain] 首次：" : "[rscc-chain] ";
        LOGGER.info(prefix + String.format(format, args) + suffix);
    }

    /** 读 {@code ShapeContext} 的访问器（record 的公开方法，双端同名）。 */
    private static Object read(final Object target, final String accessor) throws ReflectiveOperationException {
        return accessorMethod(target.getClass(), accessor).invoke(target);
    }

    /** 沿「本类 → 父类」找无参公开方法（record 的访问器就在本类上，父类循环只是稳妥）。 */
    private static Method accessorMethod(final Class<?> type, final String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getMethod(name);
            } catch (final NoSuchMethodException ignored) {
                // 继续沿父类找
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    /**
     * 记一条 warning，但<b>整局只记一次</b>。
     *
     * <p><b>为什么要这道闸</b>：失败点有两个（注册期一次性、每次右键都可能碰到的处理期），
     * 后者若不加限流就会在玩家每按一次连锁键时刷一条 —— 用户要求「不刷屏，只记一次日志」。</p>
     */
    private static void warnOnce(final String message, final Throwable cause) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn(message, cause);
        }
    }

    /** 基本类型的默认值（动态代理对未处理的方法必须返回合法值，否则调用方会 NPE / 抛 ClassCastException）。 */
    @Nullable
    private static Object defaultOf(final Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0D;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return (char) 0; // 只剩 char
    }
}
