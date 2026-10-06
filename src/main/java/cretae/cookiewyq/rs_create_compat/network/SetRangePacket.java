package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：范围充电器界面设置范围数值（输入框输入 / ± 按钮点击，shift 时步长 5）。
 * 通过 containerId 定位当前打开的菜单，客户端重建菜单（无方块实体引用）也能发送。
 * axis: 0=X, 1=Y, 2=Z。
 */
public record SetRangePacket(int containerId, int axis, int value) implements CustomPacketPayload {
    public static final Type<SetRangePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_range"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRangePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetRangePacket::containerId,
            ByteBufCodecs.INT, SetRangePacket::axis,
            ByteBufCodecs.INT, SetRangePacket::value,
            SetRangePacket::new
        );

    public static void handle(final SetRangePacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof RangeChargerMenu rangeChargerMenu)) {
            return;
        }
        rangeChargerMenu.setRangeValue(packet.axis(), packet.value());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
