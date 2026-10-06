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
 * C2S：在序列装配样板终端界面里点「单元样板库」标题 → 打开<b>真槽位</b>的
 * 「执行仓单元样板汇总」容器菜单（{@link ChamberUnitsSummaryMenu}）。
 *
 * <p><b>为什么要走服务端打开</b>：汇总要跨多台执行仓呈现真 {@code Slot}，必须是服务端权威的
 * {@code AbstractContainerMenu}（原版槽位同步 / 点击 / Shift / 拖拽都依赖它）。
 * 因此这里只发一个「请打开」的请求，由服务端校验后 {@code openMenu}。</p>
 */
public record OpenChamberUnitsSummaryPacket() implements CustomPacketPayload {
    public static final Type<OpenChamberUnitsSummaryPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "open_chamber_units_summary"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenChamberUnitsSummaryPacket> STREAM_CODEC =
        StreamCodec.unit(new OpenChamberUnitsSummaryPacket());

    public static void handle(final OpenChamberUnitsSummaryPacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = terminalMenu.getTerminal();
        if (terminal == null) {
            return;
        }
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, ignored) -> ChamberUnitsSummaryMenu.create(id, inventory, terminal),
            Component.translatable(ChamberUnitsSummaryMenu.TITLE_KEY)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
