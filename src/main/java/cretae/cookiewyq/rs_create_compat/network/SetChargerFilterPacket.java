package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import cretae.cookiewyq.rs_create_compat.support.ChargingFilter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * C2S：把过滤界面上的<b>整份配置</b>写进充电器（黑/白名单 + 四类名单，全量覆盖）。
 *
 * <h2>为什么是全量覆盖、而不是「加一条 / 删一条」</h2>
 * <p>过滤界面的所有操作（勾选 / 取消 / 添加名字 / 删除条目）都作用在<b>同一份</b>本地快照上，
 * 玩家看到的就是他要的那份配置。若改成增量包，界面上「先勾后取消」两次操作会被服务端
 * 按到达顺序各自执行一次 —— 顺序一乱（网络重排、玩家点得快）就与玩家看到的配置不一致。
 * 全量覆盖把「界面现在长什么样」当成唯一意图，服务端只做清洗与落盘；配合
 * {@link SyncChargerFilterPacket} 的全量下发，整条链路是幂等的。</p>
 *
 * <h2>为什么不回 ack</h2>
 * <p>界面是「乐观更新」：勾选立刻反映在本地，服务端按权威覆盖并落盘。失败的唯一可能是
 * 归属校验不过（界面已经关了 / 方块被拆 / 离得太远），那种情况下界面本来也不会再看到结果。
 * 多一个 ack 包只会给每条勾选加一次来回延迟，且没有任何界面需要它。</p>
 *
 * <h2>服务端校验（与 {@link OpenChargerFilterPacket} 逐条相同）</h2>
 * <p>{@code containerId} 必须等于玩家当前菜单 → 菜单必须是 {@link RangeChargerMenu} →
 * {@code stillValid(player)}。三道全过才写方块实体（{@code setChargingFilter} 内部
 * 是「整体 copy + setChanged」，只动过滤配置，不碰能量 / 范围 / 升级）。</p>
 *
 * @param containerId  发起请求的菜单 id
 * @param whitelist    false = 黑名单；true = 白名单
 * @param droppedItems 掉落物名单（物品注册名）——整份覆盖
 * @param carriedItems 玩家身上物品名单（物品注册名）——整份覆盖
 * @param blocks       「世界中的方块」名单（方块注册名，第 110 轮）——整份覆盖
 * @param playerNames  玩家名名单（服务端会再转小写去重）——整份覆盖
 * @param playerIds    玩家 UUID 名单——整份覆盖
 */
public record SetChargerFilterPacket(int containerId, boolean whitelist,
                                    List<ResourceLocation> droppedItems,
                                    List<ResourceLocation> carriedItems,
                                    List<ResourceLocation> blocks,
                                    List<String> playerNames,
                                    List<UUID> playerIds)
    implements CustomPacketPayload {

    public static final Type<SetChargerFilterPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_charger_filter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetChargerFilterPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.BOOL.encode(buf, packet.whitelist());
                SyncChargerFilterPacket.ResourceCodecList.encode(buf, packet.droppedItems());
                SyncChargerFilterPacket.ResourceCodecList.encode(buf, packet.carriedItems());
                // 第 110 轮：方块名单走自己的闸门（MAX_BLOCK_ENTRIES），但编解码实现仍只有
                // Sync 包那一份（两包各写一遍的话，改一侧漏一侧就是线格式错位）。
                SyncChargerFilterPacket.encodeBlocks(buf, packet.blocks());
                SyncChargerFilterPacket.encodeNames(buf, packet.playerNames());
                SyncChargerFilterPacket.encodeUuids(buf, packet.playerIds());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean whitelist = ByteBufCodecs.BOOL.decode(buf);
                final List<ResourceLocation> dropped =
                    SyncChargerFilterPacket.ResourceCodecList.decode(buf);
                final List<ResourceLocation> carried =
                    SyncChargerFilterPacket.ResourceCodecList.decode(buf);
                final List<ResourceLocation> blocks = SyncChargerFilterPacket.decodeBlocks(buf);
                final List<String> names = SyncChargerFilterPacket.decodeNames(buf);
                final List<UUID> ids = SyncChargerFilterPacket.decodeUuids(buf);
                return new SetChargerFilterPacket(containerId, whitelist, dropped, carried,
                    blocks, names, ids);
            }
        );

    public static void handle(final SetChargerFilterPacket packet, final ServerPlayer player) {
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
        // 清洗（去空 / 去重 / 名字转小写）只在 ChargingFilter.of 里做一次；服务端不信任包里的任何形状
        charger.setChargingFilter(ChargingFilter.of(
            packet.whitelist(), packet.droppedItems(), packet.carriedItems(),
            packet.blocks(), packet.playerNames(), packet.playerIds()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
