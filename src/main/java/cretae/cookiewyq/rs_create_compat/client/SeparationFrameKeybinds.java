package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.platform.InputConstants;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 分隔框架的<b>快捷键</b>：戴护目镜时按一下，切换「显示所有分隔框架方块」。
 *
 * <p><b>复用既有基础设施，不新造一套</b>：</p>
 * <ul>
 *     <li>按键本身沿用工程既有的 {@code client.TerminalKeybinds} 那一套写法与
 *     <b>同一个按键分类</b>（{@link TerminalKeybinds#CATEGORY}）—— 两个快捷键会并列出现在
 *     原版「选项 → 控制 → 按键绑定」的同一个分组里，玩家能一起改键；</li>
 *     <li>「是不是戴了护目镜」直接用 Create 的
 *     {@code GogglesItem#isWearingGoggles(player)}，不自己写装备槽检查；</li>
 *     <li>触发时机走与终端快捷键同一条链路：本类在 MOD 总线注册 {@link KeyMapping}，
 *     按键事件由主类在客户端初始化时挂到游戏总线的 {@link InputEvent.Key} 上
 *     （见 {@code ClientInit#onClientSetup}）。</li>
 * </ul>
 *
 * <p><b>为什么必须是开关而不是「按住」</b>：用户要求护目镜不能一直显示（一直显会怪），
 * 所以默认关、按一下才开；再按一下关掉，状态由 actionbar 一句话回执。</p>
 *
 * <p><b>默认键</b>：{@code H} —— 原版与 Create（Alt / Ctrl 系）、本模组终端快捷键（G）
 * 都未占用。</p>
 *
 * <p><b>不会误触发</b>：{@link KeyConflictContext#IN_GAME} 保证「打开了任意界面（含聊天栏）」时
 * 按键不激活；这里再显式挡一次「有界面 / 旁观」，并把界面期间累计的按下丢弃。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public final class SeparationFrameKeybinds {
    /** 按键名（原版按键绑定界面里显示的那一行）。 */
    public static final String TOGGLE_REVEAL = "key.rs_create_compat.toggle_separation_frames";

    /** 默认键：H（原版 / Create / 本模组终端快捷键均未占用）。 */
    private static final int DEFAULT_KEY = GLFW.GLFW_KEY_H;

    private static final String KEY_ON = "message.rs_create_compat.separation_frame_reveal.on";
    private static final String KEY_OFF = "message.rs_create_compat.separation_frame_reveal.off";

    private static KeyMapping toggleReveal;

    private SeparationFrameKeybinds() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        final KeyMapping mapping = new KeyMapping(
            TOGGLE_REVEAL,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM.getOrCreate(DEFAULT_KEY),
            TerminalKeybinds.CATEGORY
        );
        event.register(mapping);
        toggleReveal = mapping;
    }

    /** 游戏总线（{@code InputEvent.Key}）：按一下即切换「护目镜下的框架显示」。 */
    public static void onKeyInput(final InputEvent.Key event) {
        final KeyMapping mapping = toggleReveal;
        final Minecraft minecraft = Minecraft.getInstance();
        if (mapping == null) {
            return;
        }
        if (minecraft.player == null || minecraft.screen != null || minecraft.player.isSpectator()) {
            // 有界面 / 旁观：不响应，并丢弃这期间累计的按下（与终端快捷键同一套防误触处理）
            discardClicks(mapping);
            return;
        }
        if (!mapping.consumeClick()) {
            return;
        }
        discardClicks(mapping);
        SeparationFrameVisibility.toggleReveal();
        final boolean on = SeparationFrameVisibility.isRevealToggled();
        // 纯客户端表现，只给本人一句 actionbar 回执（不涉及服务端状态）
        minecraft.player.displayClientMessage(Component.translatable(on ? KEY_ON : KEY_OFF), true);
    }

    /** 丢弃累计的「按下」（原版按键计数与界面无关，故需主动清掉）。 */
    private static void discardClicks(final KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // 故意空转：只为把计数清零
        }
    }
}
