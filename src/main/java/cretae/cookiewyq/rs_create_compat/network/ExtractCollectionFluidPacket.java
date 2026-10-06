package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：点击归流缓存仓界面的流体格子 → 用玩家的空容器换出 1 桶（1000 mB）。
 * <p>本包只是「意图」：服务端校验 containerId 后，由菜单做「先模拟、后执行」的原子取出
 * （见 {@code CollectionCacheMenu#extractFluidToPlayer}）—— 没有空容器 / 缓存不足 1 桶 /
 * 该流体没有容器形态时什么都不做，也<b>不提示</b>（与 RS 一致），绝不出现「扣了不给」或凭空产出容器。</p>
 */
public record ExtractCollectionFluidPacket(int containerId, ResourceLocation id, CompoundTag nbt)
    implements CustomPacketPayload {

    public static final Type<ExtractCollectionFluidPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "extract_collection_fluid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtractCollectionFluidPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ExtractCollectionFluidPacket::containerId,
            ResourceLocation.STREAM_CODEC, ExtractCollectionFluidPacket::id,
            ByteBufCodecs.COMPOUND_TAG, ExtractCollectionFluidPacket::nbt,
            ExtractCollectionFluidPacket::new
        );

    public static void handle(final ExtractCollectionFluidPacket packet, final ServerPlayer player) {
        if (player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof CollectionCacheMenu menu)) {
            return;
        }
        menu.extractFluidToPlayer(player, packet.id(), packet.nbt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
