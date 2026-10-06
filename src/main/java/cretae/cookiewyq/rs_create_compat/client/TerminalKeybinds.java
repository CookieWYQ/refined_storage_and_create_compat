package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.platform.InputConstants;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.OpenAdvancedRemoteTerminalPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 高级远程多功能终端的<b>快捷键</b>：在游戏内按一下（默认 <b>G</b>，未占用）就打开终端，
 * 不用先拿在手上右键。按键出现在原版「选项 → 控制 → 按键绑定」的独立分类里，可自由改键 / 解绑。
 * <p>
 * <b>链路</b>：按一下 → 置位 {@link TerminalOpenIntent}（右下角模式切换按钮只对「终端打开的界面」显示）
 * → 发 {@link OpenAdvancedRemoteTerminalPacket}（空负载）→ 服务端按固定优先级
 * （主手 → 副手 → 背包 0..35 → Curios）自己找第一台终端并打开其当前模式界面。
 * <p>
 * <b>为什么不再走 RS 的 {@code RefinedStorageApi#useSlotReferencedItem}</b>：那条链路要求客户端先找出
 * <b>唯一一个</b>引用（0 个报「找不到」、&gt;1 个报「重复」并拒绝打开）。本模组终端可能同时存在于背包与
 * Curios 饰品槽，于是「身上有两台就谁都打不开」，而 RS 的提示文案恒用第一个候选（普通版）的名字，
 * 玩家体感就成了「生存版打不开、只有创造版能开」（用户实测回归）。现在改为服务端权威查找：
 * <b>多个也不拒绝</b>，按优先级取第一台，三版终端（普通 / 满电 / 创造）走完全相同的路径。
 * <p>
 * <b>不会误触发</b>：{@link KeyConflictContext#IN_GAME} 保证「打开了任意界面（含聊天栏 / 文本框）」时
 * 该按键根本不激活；这里再显式检查一次「无界面 + 非旁观」。
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

    private static KeyMapping openTerminal;

    private TerminalKeybinds() {
    }

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
    }

    /** 游戏总线（{@code InputEvent.Key}）：按下快捷键即请求打开终端，真正打开由服务端权威完成。 */
    public static void onKeyInput(final InputEvent.Key event) {
        final KeyMapping mapping = openTerminal;
        final Minecraft minecraft = Minecraft.getInstance();
        if (mapping == null) {
            return;
        }
        if (minecraft.player == null || minecraft.screen != null || minecraft.player.isSpectator()) {
            // 有界面（含聊天栏 / 文本框 / 任意 GUI）或旁观：一律不响应，
            // 并丢弃这期间累计的按下 —— 否则「在界面里按过这个键」会在界面关闭瞬间弹出终端。
            discardClicks(mapping);
            return;
        }
        if (!mapping.consumeClick()) {
            return;
        }
        // 一次按键只发一条请求：服务端「找不到终端」时只回一条提示，不会因为累计的多次按下刷屏
        discardClicks(mapping);
        // 置位打开意图：即将打开的界面是「本模组终端打开的」，右下角模式切换按钮才允许出现
        TerminalOpenIntent.mark();
        PacketDistributor.sendToServer(OpenAdvancedRemoteTerminalPacket.INSTANCE);
    }

    /** 丢弃累计的「按下」（原版按键计数与界面无关，故需主动清掉）。 */
    private static void discardClicks(final KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // 故意空转：只为把计数清零
        }
    }
}
