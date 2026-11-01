package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.platform.InputConstants;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.OpenAdvancedRemoteTerminalPacket;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 高级远程多功能终端的<b>快捷键</b>：在游戏内按一下（默认 <b>G</b>，未占用）就打开终端，
 * 不用先拿在手上右键。按键出现在原版「选项 → 控制 → 按键绑定」的独立分类里，可自由改键 / 解绑。
 *
 * <p><b>链路</b>：按一下 → 置位 {@link TerminalOpenIntent}（右下角模式切换按钮只对「终端打开的界面」显示）
 * → 发 {@link OpenAdvancedRemoteTerminalPacket}（空负载）→ 服务端按固定优先级
 * （主手 → 副手 → 背包 0..35 → Curios）自己找第一台终端并打开其当前模式界面。</p>
 *
 * <h2>第 83 轮：<b>换掉整个按键驱动方式</b>（不再用 {@code InputEvent.Key}）</h2>
 * <p>第 82 轮只加了「忽略 GLFW_REPEAT」，用户实机<b>仍然打不开</b>。这一轮不再打补丁，而是
 * <b>照抄同一环境里被证明能用的做法</b>：通用终端（Universal-Grid 0.3.2）的热键<b>完全不在</b>
 * {@code InputEvent.Key} 里处理，而是挂在 {@code ClientTickEvent.Post} 上做状态机
 * （反汇编见 {@code AbstractClientModInitializer#tickInputEvents}：{@code activePress} /
 * {@code holdTriggered} / {@code pressTime}）。本轮把这一层改成同构：</p>
 * <ul>
 *     <li><b>驱动点</b>：{@link #onClientTick(ClientTickEvent.Post)}，每客户端 tick 一次；</li>
 *     <li><b>按下来源</b>：{@link KeyMapping#consumeClick()}（原版 {@code KeyboardHandler} 在
 *     {@code GLFW_PRESS} 与 {@code GLFW_REPEAT} 都会自增的那份计数 —— 这里一次 tick 全部排空）；</li>
 *     <li><b>物理状态</b>：{@link KeyMapping#isDown()}（{@code GLFW_REPEAT} 不会改变它，只有真正的
 *     按下 / 抬起会）；</li>
 *     <li><b>一次物理按下 = 一条打开请求</b>：{@link #activePress} 记住「这一次按下已经处理过」，
 *     按住不放时后续 tick 里的重复点击<b>只清计数、不发包</b>；抬手（{@code !isDown()}）才复位。</li>
 * </ul>
 * <p>为什么这能治「按下去没反应」：{@code ServerPlayer#openMenu} 每被请求一次都会先
 * {@code closeContainer()} 再开新界面。旧写法（每个按键事件一条请求）在按住 / 连点 G 时会以系统
 * 重复速率把界面反复拆建，玩家眼里就是「什么都没发生」。现在一条物理按下只发一条请求。</p>
 *
 * <h2>六点日志（第 83 轮：<b>不再受 {@code devLogs} 控制</b>）</h2>
 * <p>前两轮的教训是：诊断行全挂在 {@code devLogs} 总开关后面，而用户默认没开 ⇒ 一轮反馈回来
 * 又是「没有任何日志」，只能靠猜。本轮把这条链路切成六个观测点，<b>一次按键各打一条
 * INFO</b>（不是每 tick、不是每次按键 —— 状态机保证一次物理按下只有一轮）：</p>
 * <ol>
 *     <li>{@code ①按键}（本类）—— tick 轮询第一次看到这次物理按下；</li>
 *     <li>{@code ②发包前}（本类）—— 过了全部守卫、即将发包；</li>
 *     <li>{@code ③发包后}（本类）—— {@code sendToServer} 正常返回（包确实离开了客户端）；</li>
 *     <li>{@code ④服务端收到}（{@link OpenAdvancedRemoteTerminalPacket}）—— 服务端处理体入口；</li>
 *     <li>{@code ⑤开菜单}（{@code AdvancedRemoteTerminalItem#openModeScreen}）—— 真的要开界面了；</li>
 *     <li>{@code ⑥打开后校验}（{@link OpenAdvancedRemoteTerminalPacket}）—— {@code containerMenu} 判定。</li>
 * </ol>
 * <p>一眼定位断点：<b>只有 ①⇒ 按键到了、守卫或发包出问题；①②③ 齐 ⇒ 客户端没事，看服务端；
 * ④ 缺 ⇒ 包没到服务端；④⑤ 有而 ⑥ 失败 ⇒ 菜单开了又被关（{@code stillValid} / 界面构造失败）；
 * 六点齐全 ⇒ 这次真的打开了。</b></p>
 * <p><b>为什么可以不打 {@code devLogs} 也不刷屏</b>：六点只在「一次真实物理按下」上各打一条，
 * 而 {@link #activePress} 状态机 + {@link #MIN_PRESS_INTERVAL_MS} 把「按住不放 / 连点」压成
 * 一次一条；真正高频的那两类日志（守卫拦下、重复点击）仍然只在 {@code devLogs} 打开时输出，
 * 并额外做 {@value #NOISE_THROTTLE_MS} 毫秒窗口节流 + 被压条数汇总。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public final class TerminalKeybinds {
    /** 按键分类（本模组自己的类别名，中英双语见 lang：key.categories.rs_create_compat）。 */
    public static final String CATEGORY = "key.categories.rs_create_compat";
    /** 按键名（原版按键绑定界面里显示的那一行）。 */
    public static final String OPEN_TERMINAL = "key.rs_create_compat.open_advanced_remote_terminal";

    /** 默认键：G —— 原版未占用、Create（Alt / Ctrl 系）与 RS（默认不绑定）也都未占用。 */
    private static final int DEFAULT_KEY = GLFW.GLFW_KEY_G;

    /**
     * 两条打开请求之间的最小间隔（毫秒）。
     * <p>状态机已经保证「一次物理按下只开一次」，这里再兜一层：玩家光速连点时，
     * 相邻两次请求若落在同一个 {@code closeContainer()} + 开界面窗口内，同样会把界面拆掉重建。
     * 100ms 远小于人手连点间隔，正常操作感觉不到。</p>
     */
    private static final long MIN_PRESS_INTERVAL_MS = 100L;

    /** 「守卫拦下 / 重复点击」这类噪音日志的节流窗口（毫秒）：同一窗口内只打一条 + 被压条数。 */
    private static final long NOISE_THROTTLE_MS = 1000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(TerminalKeybinds.class);

    /** 热键映射（{@code RegisterKeyMappingsEvent} 里赋值；此前为 null，属于正常瞬态）。 */
    private static KeyMapping openTerminal;

    /** tick 轮询监听器是否已挂到游戏总线（幂等闸：重复挂会变成一 tick 两跳）。 */
    private static boolean tickListenerAttached;

    /**
     * 「这一次物理按下已经处理过」。
     * <p>这是本轮的核心状态位：{@code isDown()} 在按住期间恒为 true（GLFW 的 repeat 不改它），
     * 所以「第一次看到按下 ⇒ 处理；已经处理过 ⇒ 只清计数」就把重复事件彻底关掉了。</p>
     */
    private static boolean activePress;

    /** 上一次真正发出打开请求的时刻（{@link #MIN_PRESS_INTERVAL_MS} 用）。 */
    private static long lastPressAt;

    /** 噪音日志（守卫 / 重复）的节流器：避免「一按就打一条」在关界面期间刷屏。 */
    private static final Throttle NOISE = new Throttle(NOISE_THROTTLE_MS);

    /** 旧入口只记一次，避免 {@code InputEvent.Key}（每个按键都会来）刷屏。 */
    private static boolean legacyEntryNoted;

    /** 「热键未绑定」只 WARN 一次（不是每 tick）。 */
    private static boolean unboundNoted;

    private TerminalKeybinds() {
    }

    /**
     * MOD 总线：注册按键映射，<b>并把 tick 轮询监听器挂到游戏总线</b>。
     *
     * <p>为什么在这里顺手挂监听器：{@code ClientInit} 里已有的是旧入口
     * {@code NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onKeyInput)} 的接线，而本轮允许改动的
     * 文件不含 {@code ClientInit}。游戏总线对象在模组加载期就存在，{@code addListener} 随时可以调用，
     * 因此在本事件里挂 {@link #onClientTick} 是安全的；{@link #tickListenerAttached} 保证只挂一次。</p>
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        final KeyMapping mapping = new KeyMapping(
            OPEN_TERMINAL,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM.getOrCreate(DEFAULT_KEY),
            CATEGORY
        );
        event.register(mapping);
        openTerminal = mapping;
        attachTickListener();
        // 「构建锚点」：这一行在，就说明当前客户端跑的确实是「tick 轮询 + 六点日志」这一版。
        // 用户反馈只贴一行日志时，先看这一行在不在，能立刻排除「装的还是旧 jar」。
        // 同时把**实际绑定的键名**打出来 —— 如果玩家在按键设置里清空过这个绑定点，
        // 原版会一直静默（永远不会有 ①按键），日志里 `unbound=true` 就是唯一线索。
        LOGGER.info("[rscc-terminal-key] 终端热键已就绪：tick 轮询状态机（一次物理按下 = 一条打开请求），"
            + "六点日志已全部开启（不受 devLogs 控制）；key={} unbound={}",
            mapping.getKey().getName(), mapping.isUnbound());
    }

    /**
     * 游戏总线：<b>热键的唯一驱动点</b>（{@code ClientTickEvent.Post}，与通用终端同构）。
     *
     * <p>每个客户端 tick 做三件事：读物理状态、排空点击计数、按状态机决定要不要发一条打开请求。
     * 整个方法体内<b>没有任何位置 / 移动 / 输入向量判定</b>（round72 自检会按 31 个 token 复查），
     * 只有「有没有界面 / 是否旁观」这两个与输入通道本身有关的守卫。</p>
     */
    public static void onClientTick(final ClientTickEvent.Post event) {
        final KeyMapping mapping = openTerminal;
        if (mapping == null) {
            // 只可能发生在 MOD 总线注册之前（此时玩家还在加载界面）。不静默：一次性提示后不再打扰。
            NOISE.log(LOGGER, "热键尚未注册（RegisterKeyMappingsEvent 未到），本次 tick 忽略");
            return;
        }
        final boolean down = mapping.isDown();
        final int clicks = drainClicks(mapping);
        if (mapping.isUnbound() && !unboundNoted) {
            // 一次性 WARN（不是每 tick）：这个绑定点被清空时，原版永远不会有 ①按键，
            // 玩家看到的就是「按什么都没反应」—— 这条必须能自查，否则又回到「无日志可查」。
            unboundNoted = true;
            LOGGER.warn("[rscc-terminal-key] 热键当前**未绑定**任何按键 ⇒ 永远不会有 ①按键；"
                + "请到「选项 → 控制 → 按键绑定 → {}」重新绑定", CATEGORY);
        }
        if (clicks <= 0) {
            if (!down) {
                // 抬手：下一次物理按下可以重新触发
                activePress = false;
            }
            // silent-ok: 本 tick 没有新的按下（正常 99.9% 的 tick 都走这里）—— 写日志就是每秒 20 条刷屏，
            // 而「没有按下」本来也不是一次可诊断的失败。
            return;
        }
        if (activePress) {
            // 同一次物理按下的后续（按住不放时原版对 GLFW_REPEAT 也会自增点击）：
            // 只清计数、不发包。这是「界面被反复拆建」的直接堵点。
            NOISE.log(LOGGER, "按住重复：丢弃 %d 次重复按下（一次物理按下只发一条打开请求）", clicks);
            return;
        }
        activePress = true;
        final Minecraft minecraft = Minecraft.getInstance();
        // ① 按键进 handler：证明 tick 轮询真的看到了这次物理按下（哪怕后面被守卫拦下）
        LOGGER.info("[rscc-terminal-key] ①按键 down={} clicks={} player={} screen={} spectator={}",
            down, clicks, minecraft.player != null, minecraft.screen != null,
            minecraft.player != null && minecraft.player.isSpectator());
        if (minecraft.player == null || minecraft.screen != null || minecraft.player.isSpectator()) {
            // 有界面（含聊天栏 / 文本框 / 任意 GUI）或旁观：一律不响应。点击已经在上面排空，
            // 不会残留到「界面关掉的那一瞬间」再弹终端。噪音日志受 devLogs + 1 秒窗口节流。
            NOISE.log(LOGGER, "守卫拦下本次按下（有界面 / 旁观）；点击已排空，不会残留到界面关闭的瞬间");
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastPressAt < MIN_PRESS_INTERVAL_MS) {
            // 连点兜底：相邻两次请求靠得太近时只保留一条，避免「刚开的界面又被 closeContainer 掉」
            NOISE.log(LOGGER, "距上一次打开请求不足 %dms，忽略本次（防连点重复拆建界面）",
                MIN_PRESS_INTERVAL_MS);
            return;
        }
        lastPressAt = now;
        // 置位打开意图：即将打开的界面是「本模组终端打开的」，右下角模式切换按钮才允许出现
        TerminalOpenIntent.mark();
        // ② 发包前：走到这里说明所有守卫都过了，「不发包」已经不可能再是原因
        LOGGER.info("[rscc-terminal-key] ②发包前 → C2S OpenAdvancedRemoteTerminalPacket");
        try {
            PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE);
            // ③ 发包后：这一行在 = 包确实离开了客户端（问题只可能在服务端之后）
            LOGGER.info("[rscc-terminal-key] ③发包后：已交给连接（等 ④ 服务端收到）");
        } catch (final RuntimeException e) {
            // 连接已断（null）等异常：仍然要让玩家 / 日志看得见，绝不静默失败
            LOGGER.warn("[rscc-terminal-key] ③发包失败：发送打开终端 C2S 包抛异常（不受 devLogs 控制）", e);
        }
    }

    /**
     * <b>旧入口（保留空壳，不参与判定）</b>：{@code InputEvent.Key}。
     *
     * <p>第 83 轮起热键改由 {@link #onClientTick} 驱动，本方法<b>绝不消费点击计数</b> ——
     * 一旦在这里 {@code consumeClick()}，就会把 tick 状态机要用的那一次按下吃掉，热键彻底失效。
     * 之所以保留同名同签名的方法：{@code ClientInit} 里已有
     * {@code NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onKeyInput)} 的接线，而本轮允许改动的
     * 文件不含 {@code ClientInit} —— 保留空壳，接线不用动、也不会重复打开。</p>
     *
     * <p>顺带承担一个兜底职责：万一 {@code RegisterKeyMappingsEvent} 那条挂监听器的路径没走到
     * （理论上不会），这里补挂一次 tick 监听器，保证热键仍然可用。</p>
     */
    public static void onKeyInput(final InputEvent.Key event) {
        if (!tickListenerAttached) {
            attachTickListener();
        }
        if (legacyEntryNoted) {
            return;
        }
        legacyEntryNoted = true;
        // 一次性说明：旧入口还在被调用，但已经不再决定「开不开」。这条也是「ClientInit 接线没坏」的证据。
        LOGGER.info("[rscc-terminal-key] 旧入口 InputEvent.Key 仍在这条接线上，但已不再驱动打开"
            + "（改由 ClientTickEvent.Post 轮询，见 ①按键）");
    }

    /** 幂等挂载 tick 轮询监听器（游戏总线）。 */
    private static void attachTickListener() {
        if (tickListenerAttached) {
            return;
        }
        tickListenerAttached = true;
        NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onClientTick);
    }

    /**
     * 排空当前累计的「按下」并返回次数。
     * <p>{@code consumeClick()} 的原版语义与玩家状态无关（{@code clickCount > 0} 就自减并返回 true），
     * 因此在 tick 里排空它既能拿到「本 tick 到底按了几下」，也不会留下残余计数。</p>
     */
    private static int drainClicks(final KeyMapping mapping) {
        int clicks = 0;
        while (mapping.consumeClick()) {
            clicks++;
        }
        return clicks;
    }

    /**
     * 最小日志节流器：同一类消息在 {@code minIntervalMs} 窗口内只放行一条，并把被压掉的条数
     * 附在下一条后面。
     *
     * <p><b>为什么要它</b>：守卫分支在「任意 GUI 打开期间」每个 tick 都可能被走到，
     * 直接打日志会以 20 条/秒刷屏；而完全不打又会退回「静默失败」。节流 + 计数汇总两头都占。</p>
     */
    private static final class Throttle {
        private final long minIntervalMs;
        private long lastAt;
        private int suppressed;

        private Throttle(final long minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }

        /** 放行则按 {@code devLogs} 口径打一条；否则只累计「被压掉几条」。 */
        private void log(final Logger logger, final String format, final Object... args) {
            if (!RsccAssemblyDebug.isEnabled()) {
                return;
            }
            final long now = System.currentTimeMillis();
            if (now - lastAt < minIntervalMs) {
                suppressed++;
                return;
            }
            lastAt = now;
            final String suffix = suppressed == 0 ? "" : "（上一窗口压掉 " + suppressed + " 条同类）";
            suppressed = 0;
            logger.info("[rscc-terminal-key] " + String.format(format, args) + suffix);
        }
    }
}
