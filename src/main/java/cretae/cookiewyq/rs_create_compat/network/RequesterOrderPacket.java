package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RequesterSenderBackend;
import cretae.cookiewyq.rs_create_compat.support.SenderBackend;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * C2S：已选资源的<b>增 / 删 / 改数量</b>（三个动作共用同一个包，只有 {@code action} 不同）。
 *
 * <h2>为什么用「动作码 + 下标」而不是「整份清单回传」</h2>
 * <p>整份回传等于让客户端决定清单内容，服务端只能照单全收；本包改成<b>一次只请求一个动作</b>，
 * 服务端对每个动作分别校验（下标在范围内、数量 &gt; 0、资源键可解析、资源键与下标处的那一条一致），
 * 处理完再把权威清单用 {@link SyncRequesterConfigPacket} 发回去。客户端界面因此永远是
 * 「只读镜像 + 请求变更」，不可能出现「客户端自己改了 NBT」这种绕过路径。</p>
 *
 * <h2>两个界面共用</h2>
 * <p>请求器界面与网格界面都会打开同一台请求器，因此本包<b>不</b>限定菜单类型：
 * 服务端用 {@code SenderBackend.Holder#senderBackend()} 解析出「这张菜单背后的发送源」，
 * 归属判据只有那一处（{@code SenderBackend#stillValid(ServerPlayer)}）。</p>
 *
 * <h2>没有条数上限</h2>
 * <p>玩家在网格里点多少种资源就记多少种 —— 服务端不再有「满了就拒绝」的分支
 * （用户明确要求「订单上限没有设限」）。</p>
 *
 * <h2>资源身份：完整的 {@link PlatformResourceKey}</h2>
 * <p>新界面直接带 {@code key}（物品 / 流体 / 气体一视同仁），用 RS 自己的
 * {@link ResourceCodecs#STREAM_CODEC} 编解码 —— 它按「资源类型注册表」dispatch，
 * 因此装了官方 Mekanism 联动后气体键自动能走，而本模组<b>一行 {@code mekanism} 引用都没有</b>。</p>
 * <p><b>向后兼容</b>：旧的 6 参构造仍然保留（{@code key = null}），此时服务端退回
 * 「资源注册名 + 是否流体」的旧解析路径。</p>
 */
public record RequesterOrderPacket(int containerId, int action, int index, String resourceId,
                                   boolean fluid, int amount,
                                   @Nullable PlatformResourceKey key)
    implements CustomPacketPayload {

    /** 追加一条（同资源自动合并；**没有条数上限**）。 */
    public static final int ACTION_ADD = 0;
    /** 删除某一条。 */
    public static final int ACTION_REMOVE = 1;
    /** 改写某一条的数量（绝对值）。 */
    public static final int ACTION_SET_AMOUNT = 2;
    /**
     * 清空整张订单清单（请求器界面的「清空订单」按钮）。
     * <p>与其它三个动作同一套纪律：客户端只提交「要做什么」，服务端校验归属 / 服务端侧身份后才写。
     * 本动作不需要 {@code index} / {@code key}，字段仍照常编码（填 {@code -1} / 空串），
     * 保持协议形状统一 —— 服务端也<b>不</b>用这两个字段做任何判断。</p>
     */
    public static final int ACTION_CLEAR = 3;

    /**
     * 兼容旧界面的构造：没有完整键。
     * <p>服务端会按「{@code resourceId} + {@code fluid}」解析（物品 / 流体注册名，或第三方类型令牌），
     * 与改前的行为逐字一致。</p>
     */
    public RequesterOrderPacket(final int containerId, final int action, final int index,
                                final String resourceId, final boolean fluid, final int amount) {
        this(containerId, action, index, resourceId, fluid, amount, null);
    }

    public static final Type<RequesterOrderPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "requester_order"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequesterOrderPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.action());
                ByteBufCodecs.VAR_INT.encode(buf, packet.index());
                ByteBufCodecs.STRING_UTF8.encode(buf,
                    packet.resourceId() == null ? "" : packet.resourceId());
                ByteBufCodecs.BOOL.encode(buf, packet.fluid());
                ByteBufCodecs.INT.encode(buf, packet.amount());
                final PlatformResourceKey key = packet.key();
                // 显式布尔标记：旧路径（key = null）不写一个字节的资源键
                ByteBufCodecs.BOOL.encode(buf, key != null);
                if (key != null) {
                    ResourceCodecs.STREAM_CODEC.encode(buf, key);
                }
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int action = ByteBufCodecs.VAR_INT.decode(buf);
                final int index = ByteBufCodecs.VAR_INT.decode(buf);
                final String resourceId = ByteBufCodecs.STRING_UTF8.decode(buf);
                final boolean fluid = ByteBufCodecs.BOOL.decode(buf);
                final int amount = ByteBufCodecs.INT.decode(buf);
                final PlatformResourceKey key = ByteBufCodecs.BOOL.decode(buf)
                    ? ResourceCodecs.STREAM_CODEC.decode(buf)
                    : null;
                return new RequesterOrderPacket(containerId, action, index, resourceId, fluid, amount,
                    key);
            }
        );

    public static void handle(final RequesterOrderPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        // 归属：只认「玩家此刻打开的这张菜单」背后的发送源（请求器方块或终端物品）。
        // 其它界面 / 客户端镜像菜单 ⇒ senderBackend() 为 null ⇒ 一律不写。
        if (!(player.containerMenu instanceof SenderBackend.Holder holder)) {
            return;
        }
        final SenderBackend backend = holder.senderBackend();
        if (backend == null || !backend.stillValid(player)) {
            return;
        }
        // 资源身份：新界面给完整键；旧协议只给「注册名 + 是否流体」，走同一段解析
        final PlatformResourceKey key = packet.key() != null
            ? packet.key()
            : RequesterSenderBackend.parseResourceKey(player.level().registryAccess(),
            packet.resourceId(), packet.fluid());
        switch (packet.action()) {
            case ACTION_ADD -> {
                final RequesterSenderBackend.OrderResult result =
                    RequesterSenderBackend.addEntry(backend, key, packet.amount());
                RequesterSenderBackend.syncTo(player, backend,
                    result == RequesterSenderBackend.OrderResult.OK
                        ? RequesterSenderBackend.MSG_ORDER_ADDED
                        : RequesterSenderBackend.messageKeyFor(result),
                    result == RequesterSenderBackend.OrderResult.OK
                        ? RequesterSenderBackend.indexOfOrder(backend, key) : -1);
            }
            case ACTION_REMOVE -> {
                final RequesterSenderBackend.OrderResult result =
                    RequesterSenderBackend.removeEntry(backend, packet.index(), key);
                RequesterSenderBackend.syncTo(player, backend,
                    result == RequesterSenderBackend.OrderResult.OK
                        ? RequesterSenderBackend.MSG_ORDER_REMOVED
                        : RequesterSenderBackend.messageKeyFor(result), -1);
            }
            case ACTION_SET_AMOUNT -> {
                final RequesterSenderBackend.OrderResult result =
                    RequesterSenderBackend.setAmount(backend, packet.index(), packet.amount(), key);
                RequesterSenderBackend.syncTo(player, backend,
                    result == RequesterSenderBackend.OrderResult.OK
                        ? RequesterSenderBackend.MSG_ORDER_UPDATED
                        : RequesterSenderBackend.messageKeyFor(result),
                    result == RequesterSenderBackend.OrderResult.OK ? packet.index() : -1);
            }
            case ACTION_CLEAR -> {
                final boolean wasEmpty = backend.orders().isEmpty();
                final RequesterSenderBackend.OrderResult result =
                    RequesterSenderBackend.clearOrders(backend);
                RequesterSenderBackend.syncTo(player, backend,
                    result != RequesterSenderBackend.OrderResult.OK
                        ? RequesterSenderBackend.messageKeyFor(result)
                        : (wasEmpty ? RequesterSenderBackend.MSG_ORDER_ALREADY_EMPTY
                        : RequesterSenderBackend.MSG_ORDER_CLEARED), -1);
            }
            default -> {
                // 未知动作：服务端不写任何东西（也绝不回一个「成功」）
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
