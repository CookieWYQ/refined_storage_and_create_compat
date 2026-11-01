package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.support.TerminalNetworkPower;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C2S：客户端点击终端模式切换 Tab 时发送（仿照 Universal-Grid 的 SetCursorPosStackPacket）：
 * 携带当前鼠标<b>原生窗口坐标</b>（cursorX/cursorY）。服务端把模式写回物品并重开对应界面，
 * 随后发送 {@link RestoreCursorPacket} 把坐标回发给客户端恢复鼠标 —— 避免服务端重开菜单时
 * 鼠标被重置到屏幕中心。
 *
 * <h2>第 100 轮：服务端权威「绑定网络没电 ⇒ 拒绝切换」</h2>
 * <p>客户端会把没电时的 Tab 置灰并<b>不发</b>本包，但那只是体验层；服务端仍然<b>必须</b>自己判一次，
 * 否则改过的客户端 / 迟到的点击 / 快照过期窗口都可能真的把模式切过去（界面重开 = 玩家界面被关掉）。
 * 判据与界面侧同源：{@link TerminalNetworkPower#isPowered}（只读<b>绑定网络</b>的
 * {@code EnergyNetworkComponent}，与终端物品自身电量无关）。拒绝时<b>不静默</b>：
 * 一条 actionbar 提示 + 补发一份电量快照（客户端据此立刻置灰，不必等下一拍心跳）。</p>
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
            // 【第 100 轮｜服务端权威电量门】绑定网络没电时一律拒绝切换：不改 mode、不重开界面
            // （重开界面会把玩家正开着的界面关掉），也不静默 —— 给一条可见提示说明原因，
            // 同时补发一份电量快照，让客户端的模式 Tab 立刻置灰（不必等下一拍心跳）。
            // 判据与界面侧完全同源：support/TerminalNetworkPower（只读绑定网络，不读终端自身电量）。
            if (!TerminalNetworkPower.isPowered(player, stack)) {
                final TerminalPowerPacket power = TerminalNetworkPower.snapshot(player, stack);
                player.displayClientMessage(
                    TerminalNetworkPower.noPowerText(power.stored(), power.capacity()), true);
                PacketDistributor.sendToPlayer(player, power);
                return;
            }
            // 已是目标模式则不重开（避免点击当前 Tab 导致界面关闭重开）
            final int previousMode = AdvancedRemoteTerminalItem.getMode(stack);
            if (previousMode == packet.mode()) {
                return;
            }
            AdvancedRemoteTerminalItem.setMode(stack, packet.mode());
            // 【第 101 轮｜能耗监视页的会话生命周期】
            // 切到<b>除能耗页以外</b>的任何一档 ⇒ 这台终端的能耗页会话立刻作废：
            // 服务端不再为一个已经不在的界面组快照（客户端那侧界面被替换后也不再发请求，
            // 这里只是把「万一还有一两个在途请求」也堵死）。
            if (packet.mode() != AdvancedRemoteTerminalItem.MODE_ENERGY) {
                cretae.cookiewyq.rs_create_compat.support.NetworkEnergyAudit.stop(player);
            }
            // 第 4 个参数 = 切走之前的那一档 = 能耗页里点「返回终端」要回去的界面。
            // 若上一档本身是能耗页（理论上到不了这里，因为「同模式不重开」），退回合成终端兜底。
            item.openModeScreen(player, stack, packet.slotReference(),
                previousMode == AdvancedRemoteTerminalItem.MODE_ENERGY
                    ? AdvancedRemoteTerminalItem.MODE_GRID
                    : previousMode);
            // 成就触发点：这一位玩家刚打开 / 切到了某个终端模式（「三模全开」成就的一个 criterion）
            cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onTerminalMode(player, packet.mode());
            // 仿照 Universal-Grid：新界面打开后把切换前的鼠标坐标回发客户端恢复（跳过无效坐标）
            if (packet.cursorX() >= 0 && packet.cursorY() >= 0) {
                PacketDistributor.sendToPlayer(
                    player, new RestoreCursorPacket(packet.cursorX(), packet.cursorY()));
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
