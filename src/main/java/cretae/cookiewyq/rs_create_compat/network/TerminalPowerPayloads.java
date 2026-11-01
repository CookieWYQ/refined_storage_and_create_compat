package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 把 {@link TerminalPowerPacket} 注册到网络层（第 100 轮）。
 *
 * <h2>为什么单独一个文件，而不是塞进 {@code RS_Create_Compat#registerPayloads}</h2>
 * <p>本轮 <code>RS_Create_Compat.java</code> 是 Lead（以及其他 agent）的<b>并发写域</b>：
 * 往那里加 4 行注册语会与别人的编辑互相覆盖，而且一旦漏加，运行期「界面打开时发快照」就会抛
 * 「payload 未注册」，把一个纯显示功能变成崩溃源。NeoForge 的 {@code RegisterPayloadHandlersEvent}
 * 允许多个监听器各自用同一个 modid 注册（版本字符串同一个）—— 本文件只注册这<b>一个</b>包，
 * 与主类集中注册完全等价，且不会有第二处再注册它（重复注册同一 payload type 会在加载期报错）。</p>
 *
 * <p><b>若将来有人把注册挪回主类</b>：必须同时删除本文件（或删除这里的注册方法），
 * 否则 NeoForge 会对同一个 {@code Type} 注册两次而拒绝启动。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public final class TerminalPowerPayloads {
    private TerminalPowerPayloads() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        // S2C：绑定网络电量快照（载荷处理体只经 ClientPayloadHooks 间接调客户端，专服链接期安全）
        event.registrar(RS_Create_Compat.MODID).playToClient(
            TerminalPowerPacket.TYPE,
            TerminalPowerPacket.STREAM_CODEC,
            TerminalPowerPacket::handle
        );
    }
}
