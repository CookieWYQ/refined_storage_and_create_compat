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
 * C2S：客户端点击终端模式切换 Tab 时发送（仿照 Universal-Grid 的 SetCursorPosStackPacket）：
 * 携带当前鼠标<b>原生窗口坐标</b>（cursorX/cursorY）。服务端把模式写回物品并重开对应界面，
 * 随后发送 {@link RestoreCursorPacket} 把坐标回发给客户端恢复鼠标 —— 避免服务端重开菜单时
 * 鼠标被重置到屏幕中心。
 */
public record SwitchTerminalModePacket(SlotReference slotReference, int mode,
                                       int cursorX, int cursorY) implements CustomPacketPayload {
    public static final Type<SwitchTerminalModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "switch_terminal_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SwitchTerminalModePacket> STREAM_CODEC =
        StreamCodec.composite(
            SlotReferenceFactory.STREAM_CODEC, SwitchTerminalModePacket::slotReference,
            ByteBufCodecs.INT, SwitchTerminalModePacket::mode,
            ByteBufCodecs.INT, SwitchTerminalModePacket::cursorX,
            ByteBufCodecs.INT, SwitchTerminalModePacket::cursorY,
            SwitchTerminalModePacket::new
        );

    public static void handle(final SwitchTerminalModePacket packet, final ServerPlayer player) {
        final Optional<ItemStack> stackOpt = packet.slotReference().resolve(player);
        if (stackOpt.isPresent() && stackOpt.get().getItem() instanceof AdvancedRemoteTerminalItem item) {
            final ItemStack stack = stackOpt.get();
            // 已是目标模式则不重开（避免点击当前 Tab 导致界面关闭重开）
            if (AdvancedRemoteTerminalItem.getMode(stack) == packet.mode()) {
                return;
            }
            AdvancedRemoteTerminalItem.setMode(stack, packet.mode());
            item.openModeScreen(player, stack, packet.slotReference());
            // 成就触发点：这一位玩家刚打开 / 切到了某个终端模式（「三模全开」成就的一个 criterion）
            cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onTerminalMode(player, packet.mode());
            // 仿照 Universal-Grid：新界面打开后把切换前的鼠标坐标回发客户端恢复（跳过无效坐标）
            if (packet.cursorX() >= 0 && packet.cursorY() >= 0) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new RestoreCursorPacket(packet.cursorX(), packet.cursorY()));
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
