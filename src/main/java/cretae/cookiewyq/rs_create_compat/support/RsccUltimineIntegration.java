package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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

/**
 * FTB Ultimine（连锁采矿）集成：让<b>两种框架</b>都支持它的<b>右键连锁</b>。
 *
 * <p><b>为什么只有它会触发批量</b>：用户明确要求「我的意思不是指直接让他批量套壳，我的意思指的是
 * 在连锁模式下就是 FTB 的连锁模式下进行批量套上这个伪装框架」「我并不是想让直接让空手套壳」。
 * 因此普通右键只套命中那一格（见 {@code item/SeparationFrameItem} / {@code item/CamouflageFrameItem}），
 * 而「成片套壳」这条唯一的批量路径挂在这里 —— 玩家<b>按住连锁键</b>右键时，FTB 才会调用本处理器。</p>
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
 *     warning，本模组行为回到「一格一格右键」，绝不抛给玩家、绝不影响加载，
 *     也绝不在每次右键时重复刷屏（失败只通报一次，见 {@link #warnOnce}）。</li>
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
            LOGGER.info("FTB Ultimine is not loaded: frames stay single-cell "
                + "(right-click frames exactly one cable/pipe at a time)");
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
                (proxy, method, args) -> onRegisterEvent(method, args, loader, handlerClass, dispatcherClass));
            registerListenerMethod(event.getClass()).invoke(event, listener);
            LOGGER.info("Hooked FTB Ultimine right-click chaining: holding a separation/camouflage frame, "
                + "right-clicking a cable/fluid pipe while the Ultimine key is held frames the whole "
                + "same-family run at once");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 绝不因为集成失败而影响加载 / 影响既有玩法：退回单格套壳，日志里留下可定位的原因
            warnOnce("Could not hook FTB Ultimine (API missing or changed); frames keep working "
                + "one cell at a time", e);
        }
    }

    // ------------------------------------------------------------------
    // 反射：注册路径
    // ------------------------------------------------------------------

    /** FTB 调用 {@code RegisterRightClickHandlerEvent#register(Dispatcher)} 时：把我们的处理器登记进去。 */
    @Nullable
    private static Object onRegisterEvent(final Method method, final Object[] args, final ClassLoader loader,
                                          final Class<?> handlerClass, final Class<?> dispatcherClass)
            throws ReflectiveOperationException {
        if (!"register".equals(method.getName()) || args == null || args.length != 1 || args[0] == null) {
            return defaultOf(method.getReturnType());
        }
        final Object handler = Proxy.newProxyInstance(loader, new Class<?>[]{handlerClass}, HANDLER_CALLS);
        // 走接口声明的方法：实现类（RightClickDispatcher）可能是包私有，那样直接取实现类的方法会有可见性问题
        dispatcherClass.getMethod("registerHandler", handlerClass).invoke(args[0], handler);
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
            warnOnce("FTB Ultimine handler call failed; falling back to single-cell framing", e);
            return defaultOf(method.getReturnType());
        }
    }

    /**
     * {@code handleRightClickBlock(ShapeContext, InteractionHand, Collection<BlockPos>)}：
     * 读出需要的三个值，交给 {@link RsccSheathChain#chainSheath} 做真正的判定与落库。
     *
     * <p>任何异常都吞在上面并返回 {@code 0}（= 本模组不接手这次右键）：宁可退回单格套壳，
     * 也不能让连锁把玩家的右键卡死。</p>
     */
    private static Object chain(final Object[] args) throws ReflectiveOperationException {
        if (args == null || args.length < 3) {
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
            return 0;
        }
        final List<BlockPos> candidates = new ArrayList<>(raw.size());
        for (final Object element : raw) {
            if (element instanceof BlockPos pos) {
                candidates.add(pos);
            }
        }
        return RsccSheathChain.chainSheath(player, hand, anchor, candidates, maxBlocks.intValue());
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
