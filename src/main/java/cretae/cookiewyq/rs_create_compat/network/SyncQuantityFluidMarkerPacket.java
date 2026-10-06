package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：把定量保持器「直接标记的流体/气体」同步给客户端，供界面用
 * {@code GhostMarkerRenderer} 绘制流体图标（ContainerData 只能传数值，传不了资源 id）。
 * <p>{@code id == null} 表示当前没有直接流体标记。</p>
 */
public record SyncQuantityFluidMarkerPacket(int containerId, ResourceLocation id, CompoundTag nbt)
    implements CustomPacketPayload {

    public static final Type<SyncQuantityFluidMarkerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_quantity_fluid_marker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncQuantityFluidMarkerPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.BOOL.encode(buf, packet.id() != null);
                if (packet.id() != null) {
                    ResourceLocation.STREAM_CODEC.encode(buf, packet.id());
                }
                ByteBufCodecs.COMPOUND_TAG.encode(buf, packet.nbt() == null ? new CompoundTag() : packet.nbt());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final ResourceLocation id = ByteBufCodecs.BOOL.decode(buf)
                    ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
                final CompoundTag nbt = ByteBufCodecs.COMPOUND_TAG.decode(buf);
                return new SyncQuantityFluidMarkerPacket(containerId, id, nbt);
            }
        );

    public static void handle(final SyncQuantityFluidMarkerPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                instanceof cretae.cookiewyq.rs_create_compat.client.screen.QuantityKeeperScreen screen
                && screen.getMenu().containerId == packet.containerId()) {
                screen.setFluidMarker(packet.id(), packet.nbt());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
