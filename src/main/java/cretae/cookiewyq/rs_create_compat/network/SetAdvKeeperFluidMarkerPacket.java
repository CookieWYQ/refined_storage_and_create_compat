package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：把「流体 / 气体」直接设置为高级定量保持器第 {@code slotIndex} 个配置槽的标记。
 * <ul>
 *     <li>{@code form}：1 = 流体，2 = 气体；{@code 0} 或 {@code id == null} 表示清除该槽标记；</li>
 *     <li>{@code nbt}：数据组件补丁编码（不透明负载，原样写入方块实体）。</li>
 * </ul>
 */
public record SetAdvKeeperFluidMarkerPacket(int containerId, int slotIndex, int form,
                                            ResourceLocation id, CompoundTag nbt)
    implements CustomPacketPayload {

    public static final Type<SetAdvKeeperFluidMarkerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_adv_keeper_fluid_marker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetAdvKeeperFluidMarkerPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.slotIndex());
                ByteBufCodecs.VAR_INT.encode(buf, packet.form());
                ByteBufCodecs.BOOL.encode(buf, packet.id() != null);
                if (packet.id() != null) {
                    ResourceLocation.STREAM_CODEC.encode(buf, packet.id());
                }
                ByteBufCodecs.COMPOUND_TAG.encode(buf, packet.nbt() == null ? new CompoundTag() : packet.nbt());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int slotIndex = ByteBufCodecs.VAR_INT.decode(buf);
                final int form = ByteBufCodecs.VAR_INT.decode(buf);
                final ResourceLocation id = ByteBufCodecs.BOOL.decode(buf)
                    ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
                final CompoundTag nbt = ByteBufCodecs.COMPOUND_TAG.decode(buf);
                return new SetAdvKeeperFluidMarkerPacket(containerId, slotIndex, form, id, nbt);
            }
        );

    public static void handle(final SetAdvKeeperFluidMarkerPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof AdvancedQuantityKeeperMenu menu)) {
            return;
        }
        menu.applyFluidMarker(packet.slotIndex(), packet.form(), packet.id(), packet.nbt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
