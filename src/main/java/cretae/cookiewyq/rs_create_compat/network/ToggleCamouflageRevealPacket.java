package cretae.cookiewyq.rs_create_compat.network;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：客户端<b>戴着护目镜按 K</b> 时发出的「切换显示状态」请求（用户第 2 条纠正）。
 *
 * <h2>为什么是空负载（不带坐标）</h2>
 * <p>用户原话：「我要的按下 K 键是<b>切换状态</b>，就像是那个<b>显示那个风格框架那样子</b>，
 * 而<b>并不是单独把某一格给显示</b>」。上一版把坐标带上、只翻准星指向的那一格 —— 语义错了；
 * 现在它就是一个<b>全局开关</b>（整个玩家视角下所有伪装填充方块一起切换），
 * 与「分隔框架的显示开关」同一个手感：所以包里<b>没有任何位置信息</b>。</p>
 *
 * <h2>服务端权威 + 客户端只读</h2>
 * <ol>
 *     <li>客户端只负责「按下键 → 发这个空包」，自己不保存任何状态；</li>
 *     <li>服务端校验护目镜（{@link GogglesItem#isWearingGoggles}，读头部装备槽，服务端同样读得到）
 *     → 翻转<b>该玩家</b>的全局开关（存进玩家持久化数据，重登保留，见
 *     {@link RsccCamouflage#toggleMaterialHidden}) → 立刻把新状态单播回该玩家
 *     （{@link SyncCamouflageRevealPacket}）；</li>
 *     <li>客户端收到后写进只读镜像（{@code RsccCamouflage#isMaterialHidden()}），
 *     渲染器每帧读它决定画「完整填充方块」还是「只显示框架」。</li>
 * </ol>
 *
 * <p><b>为什么按玩家而不是按世界</b>：显示是「看的人」的偏好 —— 换成玩家自己身上的状态后，
 * 甲玩家按 K 只影响甲看到的画面，不会去改乙看到的同一格；同时也天然重登保留、跨维度不丢。</p>
 *
 * <p><b>不消耗 / 不产出任何物品</b>：本包只翻一个布尔位，任何路径都不动物品与流体
 * （守恒见 {@link RsccCamouflage#toggleMaterialHidden} 的注释）。</p>
 */
public record ToggleCamouflageRevealPacket() implements CustomPacketPayload {
    public static final Type<ToggleCamouflageRevealPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "toggle_camouflage_reveal"));
    /** 空负载：没有字段要传（{@code StreamCodec.unit} 就是「什么都没有」的标准写法）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleCamouflageRevealPacket> STREAM_CODEC =
        StreamCodec.unit(new ToggleCamouflageRevealPacket());

    /** 切换后「所有伪装格只显示框架」。 */
    private static final String KEY_HIDDEN = "message.rs_create_compat.camouflage_reveal.hidden";
    /** 切换后「所有伪装格恢复完整显示填充方块」。 */
    private static final String KEY_VISIBLE = "message.rs_create_compat.camouflage_reveal.visible";
    /** 没戴护目镜（客户端理论上不会发包，这里只是服务端兜底反馈）。 */
    private static final String KEY_NEED_GOGGLES = "message.rs_create_compat.camouflage_reveal.need_goggles";

    public static void handle(final ToggleCamouflageRevealPacket packet, final ServerPlayer player) {
        if (!GogglesItem.isWearingGoggles(player)) {
            // 服务端权威：没戴护目镜就什么都不改（用户要求「佩戴护目镜时按 K」）
            player.displayClientMessage(Component.translatable(KEY_NEED_GOGGLES), true);
            return;
        }
        final boolean hidden = RsccCamouflage.toggleMaterialHidden(player);
        // 立刻把新的全局状态单播回该玩家（同一 tick 内客户端镜像就更新，下一帧渲染即生效）
        RsccCamouflage.syncHiddenTo(player);
        // 纯结果型反馈（用户明确要求不要解释性废话）：只报「现在是什么状态」
        player.displayClientMessage(Component.translatable(hidden ? KEY_HIDDEN : KEY_VISIBLE), true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
