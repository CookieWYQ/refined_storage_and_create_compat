package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：「打开网格选资源界面」（请求器界面上的「网格」按钮）。
 *
 * <h2>为什么要一个包</h2>
 * <p>菜单只能由<b>服务端</b> {@code openMenu} 打开，客户端按钮无法自行换界面；
 * 而「要打开的是哪个菜单」又完全由服务端发出的 {@code MenuType} 决定
 * （{@code docs/RESOURCE_PICKER_DESIGN.md} §1.2）。因此这里只需要把「请打开选择器 +
 * 新条目默认数量」这一个极小请求交给服务端，真正的打开动作在
 * {@link NetworkResourceRequesterMenu#openResourcePicker(int)} 里（它负责把 GridData
 * 写进菜单额外数据）。</p>
 *
 * <h2>三道归属校验（与本站其他 C2S 包同一口径）</h2>
 * <ol>
 *     <li>{@code containerId} 必须等于玩家当前菜单 —— 防止别的界面冒充本界面来打开网格；</li>
 *     <li>菜单必须是 {@link NetworkResourceRequesterMenu}（拿得到方块实体）；</li>
 *     <li>{@code stillValid(player)}（方块还在、距离够近）—— 与右键开界面同一判据。</li>
 * </ol>
 *
 * <h2>dist 安全（本类为什么一行客户端类型都没有）</h2>
 * <p>本类在<b>注册期</b>就会被专用服务端加载并链接，且 {@code tools/selfcheck_round47} 明确禁止
 * {@code network/**} 引用<b>本模组 client 包的类</b>（比外部客户端类型更严）。因此这里只点
 * {@code menu} 的公开入口，网格菜单 / 屏幕本身留在 {@code client/grid} 里由 {@code ClientInit} 挂载。</p>
 */
public record OpenRequesterPickerPacket(int containerId, int amount) implements CustomPacketPayload {

    /** 数量缺省值：请求器界面数量框没解析出有效值时按 1 件处理（与界面「添加」的兜底一致）。 */
    public static final int DEFAULT_AMOUNT = 1;

    public static final Type<OpenRequesterPickerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "open_requester_picker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequesterPickerPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenRequesterPickerPacket::containerId,
            ByteBufCodecs.VAR_INT, OpenRequesterPickerPacket::amount,
            OpenRequesterPickerPacket::new
        );

    /** 服务端处理：校验归属 → 交给菜单（打开网格 / 打不开时给明确提示）。 */
    public static void handle(final OpenRequesterPickerPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()
            || !(player.containerMenu instanceof NetworkResourceRequesterMenu menu)) {
            return;
        }
        if (!menu.stillValid(player)) {
            return;
        }
        menu.openResourcePicker(packet.amount());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
