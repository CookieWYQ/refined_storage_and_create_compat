package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：高级远程多功能终端的<b>绑定网络电量快照</b>（第 100 轮）。
 *
 * <h2>为什么需要这个包</h2>
 * <p>网络模式 Tab 是否置灰取决于「终端绑定的那张网络此刻有没有电」，而 RS 只把网络能量同步给
 * <b>控制器界面 / 便携网格</b>（{@code EnergyContainerMenu} + {@code EnergyInfoPacket}），
 * 本终端打开的网格 / 样板 / 管理器 / 监视器界面里<b>没有</b>这个字段，客户端无从自查
 * ⇒ 由服务端算好下发（判据唯一实现见 {@code support/TerminalNetworkPower#snapshot}）。</p>
 *
 * <h2>字段</h2>
 * <ul>
 *     <li>{@code stored} / {@code capacity}：绑定网络的存量 / 容量（FE，来自
 *     {@code EnergyNetworkComponent}）；只用于 tooltip 展示数值；</li>
 *     <li>{@code bound}：终端是否连着一张<b>此刻可达</b>的网络（未绑定 / 未加载 / 未成形 = false）；</li>
 *     <li>{@code powered}：服务端权威的「能不能切模式」。<b>不是</b>客户端由 stored/capacity
 *     再推算的东西 —— 服务端配置「不需要能量」时 bound 即 powered，这个口径只有服务端知道。</li>
 * </ul>
 *
 * <h2>专用服务端安全</h2>
 * <p>处理体只经 {@link ClientPayloadHooks} 的 default 方法间接触达客户端，本类自身不出现任何
 * {@code net.minecraft.client.*} 类型（见 {@link ClientPayloadHooks} 的类注释）。</p>
 *
 * <h2>线程</h2>
 * <p>写客户端只读镜像（{@code client/TerminalPowerState}）必须在客户端主线程 →
 * 处理体走 {@code ctx.enqueueWork}。</p>
 *
 * @param stored   绑定网络存量（FE）
 * @param capacity 绑定网络容量（FE）
 * @param bound    是否绑到一张此刻可达的网络
 * @param powered  服务端权威：此刻能否切换模式
 */
public record TerminalPowerPacket(long stored, long capacity, boolean bound, boolean powered)
    implements CustomPacketPayload {

    public static final Type<TerminalPowerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "terminal_power"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalPowerPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TerminalPowerPacket::stored,
            ByteBufCodecs.VAR_LONG, TerminalPowerPacket::capacity,
            ByteBufCodecs.BOOL, TerminalPowerPacket::bound,
            ByteBufCodecs.BOOL, TerminalPowerPacket::powered,
            TerminalPowerPacket::new
        );

    public static void handle(final TerminalPowerPacket packet, final IPayloadContext ctx) {
        // 客户端专属落地只有「写只读镜像」这一句：不碰任何客户端类型，专服链接期安全。
        ctx.enqueueWork(() -> ClientPayloadHooks.get().updateTerminalPower(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
