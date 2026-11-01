package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C：范围充电器「本 tick 真充了谁」的服务端权威快照（充电目标高亮）。
 *
 * <h2>内容</h2>
 * <ul>
 *     <li>{@code blocks}：本 tick 真的充进去能量的<b>方块坐标</b>（客户端画橙色框）；</li>
 *     <li>{@code itemIds}：本 tick 真的充进去能量的<b>掉落物实体 id</b>（客户端画青色框 + 发光）；</li>
 *     <li>{@code playerIds}：本 tick 身上真的有物品被充电的<b>玩家 UUID</b>（客户端画绿色框 + 发光）。</li>
 * </ul>
 *
 * <h2>只发「真充了谁」</h2>
 * <p>方块实体在 {@code chargeBlock} / {@code scanItems} / {@code chargePlayer} 的
 * {@code accepted > 0} 分支里登记目标，因此本包里的每一条都代表<b>实际发生的能量转移</b>，
 * 不是「扫描到但充满 / 不是可充电物品」的候选。没有真充电目标时服务端不发空包（只会在
 * 「从有到无」的那一次发一条空快照让客户端清屏），避免空包风暴。</p>
 *
 * <p><b>第 102 轮补充</b>：第 102 轮加的「满电目标直接跳过」让<b>已经充满</b>的目标连候选都不算
 * （判定在分摊预算之前、{@code accepted} 永远为 0）⇒ 它们自然也不会出现在本包的任何列表里，
 * 客户端不会为一个「其实没在充」的满电目标画框 / 点亮。协议本身没有变化（仍是三张列表，
 * 颜色由客户端按类型决定），所以注册行不需要动。</p>
 *
 * <h2>发送范围 / 配置开关</h2>
 * <p>只发给同维度、以充电器为圆心 {@code 64} 格内的玩家；{@code Config.rangeChargerHighlightTargets}
 * 关闭时<b>一个包都不发</b>（判定在方块实体里，客户端收不到任何包）。</p>
 *
 * <p><b>线程 / 专用服务端</b>：本类只依赖双端类型（{@code BlockPos} / {@code UUID} / 载荷 record），
 * 处理体只把包转交给 {@link ClientPayloadHooks}，客户端实现位于 {@code client/ChargerTargetHighlight}。
 * 因此专用服务端加载 / 链接本类时不会碰任何 {@code net.minecraft.client.*} 类型。</p>
 *
 * <p><b>不写存档 / 不改服务端世界状态</b>：客户端只做「画框 + 客户端实体发光」，
 * 发光是 {@code Entity#setGlowingTag} 的纯客户端视觉标记，下一次服务端同步就会自然覆盖；
 * 服务端从不因本功能写入任何 NBT 或实体数据。</p>
 *
 * @param blocks    本 tick 真充了能量的方块坐标（客户端画高亮框）
 * @param itemIds   本 tick 真充了能量的掉落物实体 id（客户端发光）
 * @param playerIds 本 tick 身上有物品真充了能量的玩家 UUID（客户端发光）
 */
public record ChargerHighlightPacket(List<BlockPos> blocks, List<Integer> itemIds, List<UUID> playerIds)
    implements CustomPacketPayload {

    public static final Type<ChargerHighlightPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "charger_highlight"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChargerHighlightPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.<RegistryFriendlyByteBuf, BlockPos, List<BlockPos>>collection(
                ArrayList::new, BlockPos.STREAM_CODEC),
            ChargerHighlightPacket::blocks,
            ByteBufCodecs.<RegistryFriendlyByteBuf, Integer, List<Integer>>collection(
                ArrayList::new, ByteBufCodecs.VAR_INT),
            ChargerHighlightPacket::itemIds,
            ByteBufCodecs.<RegistryFriendlyByteBuf, UUID, List<UUID>>collection(
                ArrayList::new, UUIDUtil.STREAM_CODEC),
            ChargerHighlightPacket::playerIds,
            ChargerHighlightPacket::new
        );

    public static void handle(final ChargerHighlightPacket packet, final IPayloadContext ctx) {
        // 客户端专属处理在 client/ChargerTargetHighlight（本类保持只依赖双端类型，专用服务端安全）
        ctx.enqueueWork(() -> ClientPayloadHooks.get().syncChargerHighlight(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
