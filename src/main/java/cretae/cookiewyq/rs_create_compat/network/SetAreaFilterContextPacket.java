package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换「正在编辑过滤器的区域」（第 101 轮 P1：每区域独立过滤器）。
 *
 * <p>用户需求：<i>「每一个区域内可以吸收的东西都是不一样的：某个范围只吸物品 A，另一个范围只吸物品 B」</i>。
 * 界面上的做法是复用界面里那 48 个 ghost 匹配格：选中区域 N 后，匹配窗口显示 / 读写的都是
 * <b>区域 N</b> 的过滤条目（含标记配置子界面、Shift 写标记、数量 / 标签 / NBT 规则），
 * 因此不需要第二套槽位机制。本包只负责「切换当前编辑对象」这一个动作。</p>
 *
 * <h2>为什么是菜单级状态、服务端权威</h2>
 * <ul>
 *     <li>它只影响「ghost 窗口显示谁的条目」，<b>不参与吸收判定</b>，所以不需要写进方块实体 NBT；</li>
 *     <li>两个玩家同时开同一台机器时可以各自编辑不同区域，互不干扰；</li>
 *     <li>服务端校验：必须是当前打开的高级仓菜单、{@code containerId} 一致、{@code stillValid}
 *     （方块仍在 + 8 格内），且区域序号必须落在当前生效区域数内，越界收敛成 {@code -1}（退出编辑）。</li>
 * </ul>
 */
public record SetAreaFilterContextPacket(int containerId, int areaIndex) implements CustomPacketPayload {
    public static final Type<SetAreaFilterContextPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_area_filter_context"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetAreaFilterContextPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetAreaFilterContextPacket::containerId,
            ByteBufCodecs.VAR_INT, SetAreaFilterContextPacket::areaIndex,
            SetAreaFilterContextPacket::new
        );

    public static void handle(final SetAreaFilterContextPacket packet, final ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return;
        }
        if (!(player.containerMenu instanceof AdvancedCollectionCacheMenu menu)
            || menu.containerId != packet.containerId()
            || !menu.stillValid(player)) {
            return; // 陈旧 / 伪造包：丢掉（不退回主手，避免改到别的物品）
        }
        menu.setEditingArea(packet.areaIndex());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
