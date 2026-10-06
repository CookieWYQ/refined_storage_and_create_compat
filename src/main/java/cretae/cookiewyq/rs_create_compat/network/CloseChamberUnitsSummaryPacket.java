package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.ChamberUnitsSummaryMenu;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/**
 * C2S：关闭「执行仓单元样板汇总」并<b>回到序列装配样板终端</b>。
 *
 * <p>键盘 Esc 走原版 {@code onClose()}（只关容器、回世界），因此界面把「关闭」按钮与
 * {@code onClose()} 都接在这里：先发本包请服务端把终端菜单重新打开，再走原版关闭流程 ——
 * 随后客户端发来的「关闭旧容器」包因为 containerId 已变化会被服务端忽略，于是玩家回到终端界面。</p>
 */
public record CloseChamberUnitsSummaryPacket() implements CustomPacketPayload {
    public static final Type<CloseChamberUnitsSummaryPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "close_chamber_units_summary"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CloseChamberUnitsSummaryPacket> STREAM_CODEC =
        StreamCodec.unit(new CloseChamberUnitsSummaryPacket());

    public static void handle(final CloseChamberUnitsSummaryPacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof ChamberUnitsSummaryMenu summaryMenu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = summaryMenu.getTerminal();
        if (terminal == null) {
            return;
        }
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> SequencePatternTerminalMenu.create(id, inventory, terminal),
            Component.translatable("block.rs_create_compat.sequence_pattern_terminal")));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
