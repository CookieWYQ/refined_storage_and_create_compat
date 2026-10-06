package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.support.RsccTerminalLocator;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：玩家按下「打开高级远程多功能终端」快捷键时发送（<b>空负载</b>，不带任何引用）。
 *
 * <p><b>为什么不复用 RS 原生的 {@code use_slot_referenced_item}</b>：那条链路要求客户端先找出
 * <b>唯一一个</b>引用（{@code CompositeSlotReferenceProvider#findForUse}：0 个报「找不到」、
 * &gt;1 个报「重复」并拒绝），而本模组终端可能同时出现在背包与 Curios 饰品槽里，
 * 一旦多于一台就直接打不开（玩家实测回归：生存版打不开、只有创造版能开）。
 * 这里改成「客户端只喊一声，由服务端按固定优先级自己找」——服务端权威、结果确定、
 * 与 RS 的「唯一性」语义彻底解耦，且<b>多个也不拒绝</b>（取优先级第一台，见 {@link RsccTerminalLocator}）。</p>
 *
 * <p>只影响「打开界面」这一步：模式切换 / 电量 / 网络绑定等逻辑仍全部复用既有实现，
 * 不新增任何复制 / 销毁物品的路径。</p>
 */
public record OpenAdvancedRemoteTerminalPacket() implements CustomPacketPayload {
    /** 空负载包只需一个实例（{@link StreamCodec#unit} 复用同一对象）。 */
    public static final OpenAdvancedRemoteTerminalPacket INSTANCE = new OpenAdvancedRemoteTerminalPacket();
    public static final Type<OpenAdvancedRemoteTerminalPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "open_advanced_remote_terminal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAdvancedRemoteTerminalPacket> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    /**
     * 服务端处理：按固定优先级查找第一台高级远程多功能终端并打开其当前模式界面。
     * <p>一台都没有 → 只回一条红字短提示（在动作栏显示，一次按键只发一条，不会刷屏）。</p>
     */
    public static void handle(final OpenAdvancedRemoteTerminalPacket packet, final ServerPlayer player) {
        final Optional<RsccTerminalLocator.Location> located = RsccTerminalLocator.locate(player);
        if (located.isEmpty() || !(located.get().stack().getItem() instanceof AdvancedRemoteTerminalItem item)) {
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.not_found")
                    .withStyle(ChatFormatting.RED),
                true);
            return;
        }
        // 服务端权威：物品内部再按引用取一次存活栈并打开当前模式界面（手持右键走的是同一个入口）
        item.useFromShortcut(player, located.get().slotReference());
        // 成就触发点：这一位玩家确实用快捷键把终端打开了（并补发一次当前模式，见 RsccAdvancements）
        RsccAdvancements.onTerminalOpenedByHotkey(player);
        RsccAdvancements.onTerminalMode(player, AdvancedRemoteTerminalItem.getMode(located.get().stack()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
