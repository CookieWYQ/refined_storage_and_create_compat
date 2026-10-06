package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：（保留包，当前无 UI 使用）网络中自动合成仓的名称列表。
 * <p>「单元样板库 / 装配编排」两个 Tab 及其概念已在 v6 删除（单元样板自动生成），
 * 终端不再需要展示自动合成仓选择，故客户端仅消费本包、不做任何处理。</p>
 */
public record SyncAutocrafterNamesPacket(List<String> names) implements CustomPacketPayload {
    public static final Type<SyncAutocrafterNamesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_autocrafter_names"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAutocrafterNamesPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncAutocrafterNamesPacket::names,
            SyncAutocrafterNamesPacket::new
        );

    public static void handle(final SyncAutocrafterNamesPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        // 无 UI 使用：仅消费，避免遗留的自动合成仓选择概念。
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
