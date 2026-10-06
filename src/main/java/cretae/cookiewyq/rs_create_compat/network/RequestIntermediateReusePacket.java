package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccIntermediateReusePolicy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：RS「自动合成预览」界面刚打开时，请求补发一份「优先复用中间产物」开关快照（无参数）。
 *
 * <h2>为什么必须走包</h2>
 * <p>开关是主世界存档级的 {@link RsccIntermediateReusePolicy}（{@code SavedData}），
 * <b>客户端读不到</b>：{@code reuseIntermediates(Level)} 对客户端 Level 一律回落到默认档（关）。
 * 而那个预览界面的主路径是<b>纯客户端</b>开出来的（{@code ClientPlatformUtil.openCraftingPreview}
 * 直接 new 界面 + new 菜单，<b>没有服务端菜单</b>），所以既没有 ContainerData 通道，
 * 也没有别的既有同步通道可搭 —— 只能自己拉一次。</p>
 *
 * <p><b>只读</b>：本包不携带任何数据、不改任何服务端状态，只让服务端回一份
 * {@link SyncIntermediateReusePacket}。伪造它没有任何副作用。</p>
 *
 * <p><b>为什么「开界面拉一次」就够</b>：界面开着的时候玩家敲不了指令（键盘事件被界面吃掉），
 * 所以「界面开着时开关被指令改掉」在结构上不可能发生；每次打开界面都是一份新快照。</p>
 */
public record RequestIntermediateReusePacket() implements CustomPacketPayload {
    public static final Type<RequestIntermediateReusePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "request_intermediate_reuse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestIntermediateReusePacket> STREAM_CODEC =
        StreamCodec.unit(new RequestIntermediateReusePacket());

    public static void handle(final RequestIntermediateReusePacket packet, final ServerPlayer player) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
            new SyncIntermediateReusePacket(RsccIntermediateReusePolicy.get(server).isReuseIntermediates()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
