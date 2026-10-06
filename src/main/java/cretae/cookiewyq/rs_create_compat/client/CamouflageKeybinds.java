package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.ToggleCamouflageRevealPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 「隐藏 / 恢复已填充方块」的<b>快捷键 K</b>（用户第 1 条 + 第 2 条纠正：<b>全局切换状态</b>）。
 *
 * <h2>与「分隔框架显示开关」同一套做法（用户原话：就像显示那个框架那样子）</h2>
 * <ul>
 *     <li><b>按键注册</b>：与 {@link TerminalKeybinds} / {@link SeparationFrameKeybinds} 逐字同一套 ——
 *     MOD 总线 {@link RegisterKeyMappingsEvent} 里注册 {@link KeyMapping}，共用<b>同一个按键分类</b>
 *     {@link TerminalKeybinds#CATEGORY}（{@link KeyConflictContext#IN_GAME}），
 *     因此在原版「选项 → 控制 → 按键绑定」的同一分组里可以<b>改成任意键</b>；</li>
 *     <li><b>按下事件</b>：游戏总线的 {@link InputEvent.Key}，由 {@code client/ClientInit} 挂一次，
 *     与本模组另外两个快捷键同一条链路；有界面 / 旁观时不响应并丢弃累计按下；</li>
 *     <li><b>触发语义</b>：一次按下 = 一次<b>全局状态翻转</b>（所有伪装格一起切），
 *     <b>不再看准星指向哪一格</b>（上一版那套「瞄准单格」已删除）。</li>
 * </ul>
 *
 * <h2>客户端只做两件事，状态归服务端</h2>
 * <ol>
 *     <li>判护目镜（{@link GogglesItem#isWearingGoggles}，与工程既有做法一致；没戴就只给一句提示、不发包）；</li>
 *     <li>发一次<b>空负载</b>的 {@link ToggleCamouflageRevealPacket} —— 服务端翻转该玩家的全局开关、
 *     存进玩家持久化数据（重登保留），并立刻单播回新状态；客户端只把它写进只读镜像。</li>
 * </ol>
 *
 * <p><b>默认键 K</b>：原版、Create（Alt / Ctrl 系）与本模组既有的 G（终端）/ H（分隔框架）都未占用；
 * 它只是默认值，玩家改过之后以玩家设置为准。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public final class CamouflageKeybinds {
    /** 按键名（原版按键绑定界面里显示的那一行）。 */
    public static final String TOGGLE_REVEAL = "key.rs_create_compat.toggle_camouflage_reveal";

    /** 默认键：K（原版 / Create / 本模组既有快捷键 G、H 都未占用）。 */
    private static final int DEFAULT_KEY = GLFW.GLFW_KEY_K;

    /** 没戴护目镜（用户要求只有戴护目镜时才响应）。 */
    private static final String KEY_NEED_GOGGLES = "message.rs_create_compat.camouflage_reveal.need_goggles";

    private static KeyMapping toggleReveal;

    private CamouflageKeybinds() {
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

    /** 游戏总线（{@code InputEvent.Key}）：按一下即请求翻转「全体伪装格的显示状态」。 */
    public static void onKeyInput(final InputEvent.Key event) {
        final KeyMapping mapping = toggleReveal;
        final Minecraft minecraft = Minecraft.getInstance();
        if (mapping == null) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null
            || minecraft.player.isSpectator()) {
            // 有界面 / 旁观：不响应，并丢弃这期间累计的按下（与另外两个快捷键同一套防误触处理）
            discardClicks(mapping);
            return;
        }
        if (!mapping.consumeClick()) {
            return;
        }
        discardClicks(mapping);
        if (!GogglesItem.isWearingGoggles(minecraft.player)) {
            // 护目镜判定直接用 Create 自己的 GogglesItem#isWearingGoggles（不自己写装备槽检查）
            minecraft.player.displayClientMessage(Component.translatable(KEY_NEED_GOGGLES), true);
            return;
        }
        // 只发一次「切换全局状态」请求：真值在服务端（玩家持久化数据），客户端随后按 S2C 只读镜像渲染
        PacketDistributor.sendToServer(new ToggleCamouflageRevealPacket());
    }

    /** 丢弃累计的「按下」（原版按键计数与界面无关，故需主动清掉）。 */
    private static void discardClicks(final KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // 故意空转：只为把计数清零
        }
    }
}
