package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：客户端点击终端模式切换 Tab 时发送，服务端把模式写回物品并重开对应 RS 原版界面。
 */
public record SwitchTerminalModePacket(SlotReference slotReference, int mode) implements CustomPacketPayload {
    public static final Type<SwitchTerminalModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "switch_terminal_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SwitchTerminalModePacket> STREAM_CODEC =
        StreamCodec.composite(
            SlotReferenceFactory.STREAM_CODEC, SwitchTerminalModePacket::slotReference,
            ByteBufCodecs.INT, SwitchTerminalModePacket::mode,
            SwitchTerminalModePacket::new
        );

    public static void handle(final SwitchTerminalModePacket packet, final ServerPlayer player) {
        final Optional<ItemStack> stackOpt = packet.slotReference().resolve(player);
        if (stackOpt.isPresent() && stackOpt.get().getItem() instanceof AdvancedRemoteTerminalItem item) {
            final ItemStack stack = stackOpt.get();
            AdvancedRemoteTerminalItem.setMode(stack, packet.mode());
            item.openModeScreen(player, stack, packet.slotReference());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
