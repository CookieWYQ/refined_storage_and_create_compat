package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.KeeperSlotConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：把高级定量保持器的 4 槽配置快照同步给客户端（ContainerData 只能传数值，传不了资源 id/NBT）。
 * <p>打开界面时发送；各槽标记变更后服务端也会重发，供 ghost 槽绘制物品/流体/气体与 tooltip。</p>
 */
public record SyncAdvKeeperConfigPacket(int containerId, List<KeeperSlotConfig> configs)
    implements CustomPacketPayload {

    public static final Type<SyncAdvKeeperConfigPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_adv_keeper_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAdvKeeperConfigPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.configs().size());
                for (final KeeperSlotConfig config : packet.configs()) {
                    ByteBufCodecs.VAR_INT.encode(buf, config.form());
                    ByteBufCodecs.BOOL.encode(buf, config.id() != null);
                    if (config.id() != null) {
                        ResourceLocation.STREAM_CODEC.encode(buf, config.id());
                    }
                    ByteBufCodecs.COMPOUND_TAG.encode(buf, config.nbt());
                    ByteBufCodecs.VAR_LONG.encode(buf, config.amount());
                    ByteBufCodecs.BOOL.encode(buf, config.autocraft());
                }
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int size = ByteBufCodecs.VAR_INT.decode(buf);
                final List<KeeperSlotConfig> configs = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    final int form = ByteBufCodecs.VAR_INT.decode(buf);
                    final ResourceLocation id = ByteBufCodecs.BOOL.decode(buf)
                        ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
                    final CompoundTag nbt = ByteBufCodecs.COMPOUND_TAG.decode(buf);
                    final long amount = ByteBufCodecs.VAR_LONG.decode(buf);
                    final boolean autocraft = ByteBufCodecs.BOOL.decode(buf);
                    configs.add(new KeeperSlotConfig(form, id, nbt, amount, autocraft));
                }
                return new SyncAdvKeeperConfigPacket(containerId, configs);
            }
        );

    public static void handle(final SyncAdvKeeperConfigPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                instanceof cretae.cookiewyq.rs_create_compat.client.screen.AdvancedQuantityKeeperScreen screen
                && screen.getMenu().containerId == packet.containerId()) {
                screen.setSyncedConfigs(packet.configs());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
