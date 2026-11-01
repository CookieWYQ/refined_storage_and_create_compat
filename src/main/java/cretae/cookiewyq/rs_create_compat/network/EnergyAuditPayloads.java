package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 把能耗监视的 3 个载荷注册到网络层（<b>自注册</b>，不经主类 {@code registerPayloads}）。
 *
 * <h2>为什么单独一个文件，而不是塞进 {@code RS_Create_Compat#registerPayloads}</h2>
 * <p>{@code RS_Create_Compat.java} 是 Lead 与其它并行 agent 的<b>并发写域</b>：往里加几行注册语
 * 会与别人的编辑互相覆盖；漏加时运行期就是「payload 未注册」崩溃。NeoForge 的
 * {@code RegisterPayloadHandlersEvent} <b>允许多个监听器各自用同一个 modid 注册</b>
 * （每个监听器只注册自己的 {@code Type}），因此本文件只注册这 3 个包，与主类集中注册完全等价。
 * 本工程本轮已有同款先例（{@code network/TerminalPowerPayloads.java}，另一条线的写域）。</p>
 *
 * <p><b>绝不能重复注册同一个 {@code Type}</b>：若将来有人把这 3 行挪回主类，
 * 必须同时删除本文件（否则 NeoForge 在加载期就会因重复 type 拒绝启动）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚不支持自动推断 bus
public final class EnergyAuditPayloads {
    private EnergyAuditPayloads() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(final RegisterPayloadHandlersEvent event) {
        // C2S：刷新 / 选中 / 取消选中（服务端校验后回快照）
        event.registrar(RS_Create_Compat.MODID).playToServer(
            EnergyAuditRequestPacket.TYPE,
            EnergyAuditRequestPacket.STREAM_CODEC,
            (packet, ctx) -> EnergyAuditRequestPacket.handle(
                packet, (net.minecraft.server.level.ServerPlayer) ctx.player())
        );
        // S2C：整份快照（处理体只经 EnergyAuditClientBridge 间接调客户端，专服链接期安全）
        event.registrar(RS_Create_Compat.MODID).playToClient(
            SyncEnergyAuditPacket.TYPE,
            SyncEnergyAuditPacket.STREAM_CODEC,
            SyncEnergyAuditPacket::handle
        );
    }
}
