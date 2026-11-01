package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：充电器界面上的「过滤」按钮 → 请服务端把<b>权威过滤配置</b>发过来（并顺带记一笔「见过这个玩家」）。
 *
 * <h2>为什么是「先请求、再开界面」而不是「客户端直接开界面」</h2>
 * <p>过滤界面要显示两份客户端拿不到的东西：①方块实体里的黑/白名单与三类名单（服务端权威）；
 * ②本存档已知玩家候选池（主世界 {@code SavedData}，客户端完全访问不到）。
 * 因此必须先向服务端要一份 {@link SyncChargerFilterPacket}，界面由 S2C 处理体在客户端主线程上打开
 * （见 {@code ChargerFilterScreen#openOrRefresh}）。客户端菜单是二参工厂重建的，
 * {@code RangeChargerMenu#getCharger()} 在客户端<b>恒为 null</b>，本地没有任何可开的配置来源。</p>
 *
 * <h2>三道校验（与服务端其它 C2S 包同一口径）</h2>
 * <ol>
 *     <li>{@code containerId} 必须等于玩家<b>当前</b>打开的菜单 —— 防止别的界面冒充；
 *     也顺带保证「界面已经关了、玩家还在按」的迟到请求不会写到另一台充电器上；</li>
 *     <li>菜单必须是 {@link RangeChargerMenu}（拿得到方块实体）；</li>
 *     <li>{@code stillValid(player)}：维度 / 距离 / 方块实体身份（拆掉 / 换掉后立刻失效）。</li>
 * </ol>
 *
 * <h2>顺带记录玩家</h2>
 * <p>{@link RangeChargerBlockEntity#sendFilterSnapshot(ServerPlayer)} 内部会
 * {@code ChargerKnownPlayers.record(player)}：专用服务端刚起动、登录事件还没分发时，
 * 玩家的名字也必须能出现在候选池里，否则界面第一眼就是「一个玩家都没有」。</p>
 */
public record OpenChargerFilterPacket(int containerId) implements CustomPacketPayload {
    public static final Type<OpenChargerFilterPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "open_charger_filter"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenChargerFilterPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenChargerFilterPacket::containerId,
            OpenChargerFilterPacket::new
        );

    public static void handle(final OpenChargerFilterPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null
            || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof RangeChargerMenu menu)) {
            return;
        }
        if (!menu.stillValid(player)) {
            return;
        }
        final RangeChargerBlockEntity charger = menu.getCharger();
        if (charger == null) {
            return; // 客户端菜单（charger == null）永远走不到这里，这里只是防御
        }
        charger.sendFilterSnapshot(player);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
