package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：把「流体 / 气体」直接设置为定量保持器的标记（任务 2 的新路径，
 * 不再依赖「容器物品反推流体」）。
 * <ul>
 *     <li>{@code containerId}：发送方的容器 id，服务端据此校验确为本人打开的定量保持器界面；</li>
 *     <li>{@code form}：标记形态，{@code 1} = 流体，{@code 2} = 气体；{@code 0} 或 {@code id == null} 表示清除；</li>
 *     <li>{@code id}：流体/气体注册名（可为 null 表示清除）；</li>
 *     <li>{@code nbt}：数据组件补丁编码（不透明负载，原样写入方块实体）。</li>
 * </ul>
 */
public record SetQuantityFluidMarkerPacket(int containerId, int form,
                                           ResourceLocation id, CompoundTag nbt)
    implements CustomPacketPayload {

    public static final Type<SetQuantityFluidMarkerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_quantity_fluid_marker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetQuantityFluidMarkerPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.form());
                ByteBufCodecs.BOOL.encode(buf, packet.id() != null);
                if (packet.id() != null) {
                    ResourceLocation.STREAM_CODEC.encode(buf, packet.id());
                }
                ByteBufCodecs.COMPOUND_TAG.encode(buf, packet.nbt() == null ? new CompoundTag() : packet.nbt());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int form = ByteBufCodecs.VAR_INT.decode(buf);
                final ResourceLocation id = ByteBufCodecs.BOOL.decode(buf)
                    ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
                final CompoundTag nbt = ByteBufCodecs.COMPOUND_TAG.decode(buf);
                return new SetQuantityFluidMarkerPacket(containerId, form, id, nbt);
            }
        );

    public static void handle(final SetQuantityFluidMarkerPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof QuantityKeeperMenu menu)) {
            return; // 容器校验不通过：直接忽略
        }
        menu.applyFluidMarker(packet.form(), packet.id(), packet.nbt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
