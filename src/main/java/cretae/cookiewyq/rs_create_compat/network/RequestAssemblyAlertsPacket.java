package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：请求补发一份「序列装配任务告警」快照（无参数）。
 *
 * <p><b>为什么需要它</b>：告警只在<b>内容变化</b>时广播给「那一刻正开着自动合成监视器」的玩家
 * （见 {@code AssemblyWatchdog#broadcast}），另外只在登录 / 换维度时补发一次。玩家如果在告警产生
 * 的那一刻没有开着监视器、之后再打开，客户端手里就<b>一份快照都没有</b> —— 监视器里的处置按钮
 * 会因为「本地查不到该任务的告警」而永远不渲染（这正是用户实测的「断开之后到监视器里操作，
 * 按钮也没渲染出来」）。因此界面一打开就主动拉一次。</p>
 *
 * <p><b>服务端权威</b>：本包不携带任何数据，服务端只按自身记录回一份只读快照，
 * 客户端无法借此伪造 / 篡改任何服务端状态。</p>
 */
public record RequestAssemblyAlertsPacket() implements CustomPacketPayload {
    public static final Type<RequestAssemblyAlertsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "request_assembly_alerts"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestAssemblyAlertsPacket> STREAM_CODEC =
        StreamCodec.unit(new RequestAssemblyAlertsPacket());

    public static void handle(final RequestAssemblyAlertsPacket packet, final ServerPlayer player) {
        AssemblyWatchdog.sendAlerts(player);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
