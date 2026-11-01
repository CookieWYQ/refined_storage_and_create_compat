package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import cretae.cookiewyq.rs_create_compat.support.MarkerBudget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：JEI ghost 输入 —— 把 JEI 面板拖拽的物品写入序列装配终端的<b>输入原料标记槽</b>
 * （只记录类型、数量恒为 1）。
 *
 * <p><b>第 117 轮更正（独立复核）</b>：旧注释写「标记槽只记录类型，普通槽直接放入」—— 与实现不符：
 * {@code SequencePatternTerminalMenu#placeGhost} 只认 {@link #slotId()} == {@code SLOT_INPUT}
 * （索引不对就直接 return），<b>没有</b>任何「普通槽直接放入」的分支。另外界面上已经不再提供 JEI ghost
 * 放置目标（{@code SequenceTerminalJeiPlugin} 的 ghost handler 恒返回空表），所以本包当前
 * <b>没有正常发送方</b>，事实上只对改包生效 —— 保留它是因为这个闸门本身必须在
 * （写进标记槽的 {@code ItemStack} 会永久落盘并随同步回发）。</p>
 */
public record SetSequenceGhostPacket(int containerId, int slotId, ItemStack stack) implements CustomPacketPayload {
    public static final Type<SetSequenceGhostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_ghost"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceGhostPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetSequenceGhostPacket::containerId,
            ByteBufCodecs.VAR_INT, SetSequenceGhostPacket::slotId,
            ItemStack.OPTIONAL_STREAM_CODEC, SetSequenceGhostPacket::stack,
            SetSequenceGhostPacket::new
        );

    public static void handle(final SetSequenceGhostPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        // 第 116 轮：JEI ghost 拖进来的是<b>客户端给的 ItemStack</b>，会写进终端的输入原料标记槽并落盘
        // （随方块实体每次同步回发）。与「把物品设为标记」的其它机器同一类风险 ⇒ 同一把单条尺。
        final var terminal = terminalMenu.getTerminal();
        if (terminal != null && terminal.getLevel() != null
            && cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isStackTooBig(
                packet.stack(), terminal.getLevel().registryAccess())) {
            warnTooLarge();
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.rs_create_compat.sequence.marker_too_large"), true);
            return;
        }
        terminalMenu.placeGhost(packet.slotId(), packet.stack());
    }

    /** 「物品数据过大已拒绝」的 WARN（第 116 轮）：每进程只打一条，改包刷包不会刷日志。 */
    private static boolean tooLargeWarned;

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("rs_create_compat/SetSequenceGhostPacket");

    private static void warnTooLarge() {
        if (!tooLargeWarned) {
            tooLargeWarned = true;
            LOGGER.warn("[rs_create_compat] 序列终端：拒绝了一次「ghost 输入标记」写入 —— 物品自带的数据超过单条上限"
                + "（MarkerBudget.MAX_ENTRY_BYTES=" + MarkerBudget.MAX_ENTRY_BYTES + " 堆估算，≈2 KB 线上意图）；正常 JEI 拖拽不会触发，若是改包，本条即证据");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
