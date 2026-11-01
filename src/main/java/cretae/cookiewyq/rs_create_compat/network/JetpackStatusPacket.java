package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.JetpackHudState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：电动飞行背包的<b>HUD 权威快照</b>（每 5 tick 一条，装备状态变化时立即补一条）。
 *
 * <h2>字段含义（全部由服务端算，客户端只画）</h2>
 * <ul>
 *     <li>{@code equipped}：玩家此刻是否装备着<b>生效位置</b>（胸甲槽 / Curios {@code back} /
 *     Curios {@code chest}）上的电动飞行背包；{@code false} 时客户端立刻收起 HUD；</li>
 *     <li>{@code charging}：判据 = <b>本 tick 生效背包的能量比上一 tick 变大</b>
 *     （真的发生了能量转移）。<b>这不是距离判定</b>：满电、被过滤拦下、充电器没电时都不会置位 ——
 *     宁可显示「范围内」而不谎报「充电中」；</li>
 *     <li>{@code inRange}：判据 = <b>玩家落在任意一台范围充电器的范围框内</b>
 *     （服务端按充电器自己的 {@code rangeX/Y/Z} 几何算，见 {@code support/JetpackChargerRange}）；</li>
 *     <li>{@code energy} / {@code capacity}：物品组件里的<b>真实电量</b>与容量（FE），
 *     客户端据此画「当前 / 总」能量条与文本。</li>
 * </ul>
 *
 * <h2>为什么不用「客户端自己读物品组件」</h2>
 * <p>两点：① 物品组件的同步依赖物品栏 / 饰品槽的同步时机，飞行中每 tick 扣电<b>不保证</b>逐 tick
 * 同步到客户端，HUD 会跳变；② 「是否正在被充能」「是否在充电器范围内」这两件事客户端无从自查
 * （客户端不允许查方块实体）。因此统一走这条服务端权威快照。</p>
 *
 * <h2>线程 / 专用服务端安全</h2>
 * <p>处理体只写 {@link JetpackHudState}（双端都存在的纯数据类，见那里的类注释），
 * 不出现任何 {@code net.minecraft.client.*} 类型；真正绘制在 {@code client/JetpackHudOverlay}
 * （专用服务端不加载）。因此本类在注册期被专用服务端链接也不会 {@code NoClassDefFoundError}。</p>
 *
 * <h2>不写世界 / 不改服务端状态</h2>
 * <p>本包只读快照，客户端不回发任何东西；服务端发送它不产生任何副作用。</p>
 *
 * @param equipped 是否装备着生效位置上的背包
 * @param charging 是否真的在充能（本 tick 能量变大）
 * @param inRange  是否在任意一台范围充电器的范围内（几何判定）
 * @param energy   当前电量（FE）
 * @param capacity 容量（FE）
 */
public record JetpackStatusPacket(boolean equipped, boolean charging, boolean inRange,
                                  int energy, int capacity) implements CustomPacketPayload {

    public static final Type<JetpackStatusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "jetpack_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JetpackStatusPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, JetpackStatusPacket::equipped,
            ByteBufCodecs.BOOL, JetpackStatusPacket::charging,
            ByteBufCodecs.BOOL, JetpackStatusPacket::inRange,
            ByteBufCodecs.VAR_INT, JetpackStatusPacket::energy,
            ByteBufCodecs.VAR_INT, JetpackStatusPacket::capacity,
            JetpackStatusPacket::new
        );

    public static void handle(final JetpackStatusPacket packet, final IPayloadContext ctx) {
        // 客户端专属落地只有「写只读镜像」这一句：不碰任何客户端类型，专服链接期安全。
        ctx.enqueueWork(() -> JetpackHudState.accept(
            packet.equipped(), packet.charging(), packet.inRange(),
            packet.energy(), packet.capacity()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
