package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * S2C：把「这条延长型总线是否因<b>归属未确定</b>而停用 + 可达的执行舱 / 线缆簇」同步给正在看它界面的客户端。
 *
 * <p><b>为什么单独一个包（而不是塞进既有的模式同步包）</b>：这里要带「线缆簇 + 可达执行舱」两组坐标
 * （用于界面文案与半透明叠加层），体积比「模式 + 类别」大得多；分开之后，既有包的格式与语义一字未动，
 * 这份信息只在<b>真的变化时</b>才发（服务端侧直接复用方块实体那份 20 tick 缓存的判定报告，
 * 见两个容器菜单 Mixin）。</p>
 *
 * @param containerId    界面容器 id（客户端按它校验「是不是当前这条总线」）
 * @param disabled       归属未确定（服务端权威判定，见 {@code RsccBusInterference}）→ 延长型已停用
 * @param truncated      探查未能穷尽（走满步数 / 格子数到顶），这时可达执行舱可能不全，界面需单独说明
 * @param reachableCount 线缆<b>一共</b>可达的执行舱台数（界面文案里的 N）
 * @param cluster        线缆簇（含总线自身；供半透明叠加层标出「延长段」）
 * @param chambers       可达执行舱坐标（供界面文案 + 叠加层标红）
 */
public record SyncBusInterferencePacket(int containerId, boolean disabled, boolean truncated,
                                        int reachableCount, List<BlockPos> cluster,
                                        List<BlockPos> chambers)
    implements CustomPacketPayload {
    public static final Type<SyncBusInterferencePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_bus_interference"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncBusInterferencePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncBusInterferencePacket::containerId,
            ByteBufCodecs.BOOL, SyncBusInterferencePacket::disabled,
            ByteBufCodecs.BOOL, SyncBusInterferencePacket::truncated,
            ByteBufCodecs.VAR_INT, SyncBusInterferencePacket::reachableCount,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncBusInterferencePacket::cluster,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncBusInterferencePacket::chambers,
            SyncBusInterferencePacket::new
        );

    /** 客户端侧只读镜像 + 叠加层显隐状态（唯一一份，见 {@code client/BusInterferenceOverlay}）。 */
    public static void handle(final SyncBusInterferencePacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> cretae.cookiewyq.rs_create_compat.client.BusInterferenceOverlay.apply(
            packet.containerId(), packet.disabled(), packet.truncated(), packet.reachableCount(),
            packet.cluster(), packet.chambers()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
