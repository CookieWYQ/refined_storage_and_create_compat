package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * C2S：新语义（v4）—— 请求当前终端网络内的执行仓列表。
 * <p>{@code recipeTypeFilter} 为空 = 要全部机器；否则只回传该配方类型的机器。</p>
 * <p>服务端通过 {@code player.containerMenu} 定位当前打开的序列装配样板终端，并把结果用
 * {@link SyncChamberListPacket} 回传。</p>
 */
public record RequestChamberListPacket(String recipeTypeFilter) implements CustomPacketPayload {
    public static final Type<RequestChamberListPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "request_chamber_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestChamberListPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestChamberListPacket::recipeTypeFilter,
            RequestChamberListPacket::new
        );

    public static void handle(final RequestChamberListPacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof SequencePatternTerminalMenu menu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null) {
            return;
        }
        final String filter = packet.recipeTypeFilter();
        final List<SequencePatternTerminalBlockEntity.ChamberInfo> chambers =
            filter == null || filter.isEmpty() ? terminal.listChambers() : terminal.listChambersFor(filter);
        PacketDistributor.sendToPlayer(player, SyncChamberListPacket.from(chambers));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
