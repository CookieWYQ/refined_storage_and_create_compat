package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedSchematicLoaderMenu;
import cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：客户端滚动条翻页时发送，服务端同步集群行偏移，
 * 使 ClusterSlot 动态映射到集群中对应装填器的库存行（内存共享翻页）。
 * <p>{@code queueRow} 是「高级↔高级共享队列」的行偏移（基础界面用不到，恒发 0）：
 * 队列与内存一样会叠加，因此也需要与内存同源的翻页机制。
 */
public record SetClusterRowPacket(int containerId, int rowOffset, int queueRow) implements CustomPacketPayload {
    public static final Type<SetClusterRowPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_cluster_row"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetClusterRowPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetClusterRowPacket::containerId,
            ByteBufCodecs.VAR_INT, SetClusterRowPacket::rowOffset,
            ByteBufCodecs.VAR_INT, SetClusterRowPacket::queueRow,
            SetClusterRowPacket::new
        );

    public static void handle(final SetClusterRowPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId()) {
            return;
        }
        if (menu instanceof SchematicLoaderMenu loader) {
            loader.setRowOffset(packet.rowOffset());
        } else if (menu instanceof AdvancedSchematicLoaderMenu advanced) {
            advanced.setRowOffset(packet.rowOffset());
            advanced.setQueueRowOffset(packet.queueRow());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
