package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：范围充电器「查看充电范围」的**服务端权威数据**（坐标 + 三轴范围）。
 *
 * <h2>为什么必须走 S2C，而不是客户端自己算</h2>
 * <p>客户端菜单是用注册期的二参工厂 {@code new RangeChargerMenu(id, inventory)} 重建的
 * （{@code MenuType} 是普通类型，不是 {@code IMenuTypeExtension}），因此
 * {@code RangeChargerMenu#getChargerPos()} 在客户端<b>恒为 null</b>；
 * 若在界面里拿它画框只会画在 {@code (0,0,0)} 或直接 NPE。坐标必须由服务端下发。</p>
 *
 * <p>三轴范围虽然已经由 {@code ContainerData} 0/1/2 同步，这里仍<b>一并回传</b>：
 * 保证「框的几何」与「这次请求被服务端接受的那一刻的范围」是同一份快照，
 * 不会出现「坐标是新的、范围还是上一 tick 的」这种两处拼出来的框。</p>
 *
 * <p><b>线程</b>：处理体只把包转交给 {@link ClientPayloadHooks}（客户端实现再做界面判断），
 * 并投递到客户端主线程；专用服务端上本类不含任何 {@code net.minecraft.client.*} 类型。</p>
 *
 * @param containerId 菜单 id（客户端只更新 id 对得上的那一个界面）
 * @param pos         充电器方块坐标（服务端权威）
 * @param rangeX      X 轴范围（<b>直径口径</b>，与充电扫描 {@code rangeX / 2} 一致）
 * @param rangeY      Y 轴范围（同上）
 * @param rangeZ      Z 轴范围（同上）
 */
public record ShowChargerRangePacket(int containerId, BlockPos pos,
                                     int rangeX, int rangeY, int rangeZ)
    implements CustomPacketPayload {

    public static final Type<ShowChargerRangePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "show_charger_range"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShowChargerRangePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::containerId,
            BlockPos.STREAM_CODEC, ShowChargerRangePacket::pos,
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::rangeX,
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::rangeY,
            ByteBufCodecs.VAR_INT, ShowChargerRangePacket::rangeZ,
            ShowChargerRangePacket::new
        );

    public static void handle(final ShowChargerRangePacket packet, final IPayloadContext ctx) {
        // 客户端专属处理在 ClientPayloadSink（本类保持只依赖双端类型，专用服务端安全）
        ctx.enqueueWork(() -> ClientPayloadHooks.get().showChargerRange(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
