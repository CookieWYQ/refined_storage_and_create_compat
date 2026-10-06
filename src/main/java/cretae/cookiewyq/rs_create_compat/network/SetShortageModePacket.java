package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyMonitorBridge;
import cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：自动合成监视器界面上的「缺料处置」开关（<b>挂起 / 等待</b>）—— 用户第 4 条。
 *
 * <h2>为什么走包而不是让客户端自己记</h2>
 * <p>档位落在主世界的 {@link RsccShortagePolicy}（{@code SavedData}，读档保留），
 * 而它被<b>服务端看门狗</b>在每一条任务上使用（缺料持续超过阈值时的自动挂起判据）。
 * 因此真值只能在服务端：客户端点一下只发「我想要哪一档」，由服务端写入后
 * <b>立刻单播回权威值</b>（{@link SyncShortageModePacket}），界面再据此渲染。</p>
 *
 * <h2>校验</h2>
 * <ul>
 *     <li>必须<b>正开着自动合成监视器</b>（{@link RsccAssemblyMonitorBridge} 是那个菜单上的桥接接口），
 *     否则忽略 —— 免得别的界面（或伪造包）能改全服策略；</li>
 *     <li>档位序号越界一律忽略；</li>
 *     <li>与指令 {@code /rs_create_compat shortagemode} 落到同一个 {@code setMode} 上（只有一处写入）。</li>
 * </ul>
 *
 * <p><b>不消耗 / 不产出任何物品流体</b>：本包只翻一个枚举位。</p>
 */
public record SetShortageModePacket(int mode) implements CustomPacketPayload {
    public static final Type<SetShortageModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_shortage_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetShortageModePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetShortageModePacket::mode,
            SetShortageModePacket::new
        );

    public static void handle(final SetShortageModePacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof RsccAssemblyMonitorBridge)) {
            return; // 只有正开着监视器的玩家能改这条策略
        }
        final RsccShortagePolicy.Mode mode = RsccShortagePolicy.Mode.values().length > packet.mode()
            && packet.mode() >= 0 ? RsccShortagePolicy.Mode.values()[packet.mode()] : null;
        if (mode == null) {
            return;
        }
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        RsccShortagePolicy.get(server).setMode(mode);
        // 立刻把权威值单播回去：界面下一次 render 就是新样子（不做任何乐观更新）
        PacketDistributor.sendToPlayer(player, new SyncShortageModePacket(mode.ordinal()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
