package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * C2S：切换归流缓存仓「阻塞名单」里的某一种资源（物品 / 流体）。
 * <p>阻塞语义（用户需求）：被阻塞的资源<b>仍可被本机收集、也可被物流方块输入、玩家仍可取走</b>，
 * 但它绝不再被 {@code insert} 进 RS 网络（本机对它相当于「只进不出」）。</p>
 *
 * <p><b>本轮新增 {@code tags}（用户第 ③ 条根因）</b>：旧包只带一个<b>具体资源 id</b>，
 * 因此「按标签注册的匹配条目」（例如「所有板子」标签）点阻塞时，只有示例物那一件被挡住，
 * 同族的<b>金板</b>照样瞬间回流进网络。现在把该条目的<b>标签集合</b>一并带上：服务端据此
 * 把标签写进「按标签阻塞」名单，于是<b>属于该标签的任何资源都被挡住</b>。
 * 非标签条目 / 主界面 Ctrl+左键（缓存格的具体资源）一律传空集合，行为与改动前逐字一致。</p>
 *
 * <p>服务端只接受「当前打开的菜单就是该容器」的请求（服务端权威 + 容器校验）；
 * 结果经 {@code SyncCollectionMarkersPacket} 回传客户端做红色标识。</p>
 */
public record SetCollectionBlockedPacket(int containerId, boolean fluid, ResourceLocation id, boolean blocked,
                                         List<ResourceLocation> tags)
    implements CustomPacketPayload {

    public static final Type<SetCollectionBlockedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_collection_blocked"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectionBlockedPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectionBlockedPacket::containerId,
            ByteBufCodecs.BOOL, SetCollectionBlockedPacket::fluid,
            ResourceLocation.STREAM_CODEC, SetCollectionBlockedPacket::id,
            ByteBufCodecs.BOOL, SetCollectionBlockedPacket::blocked,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), SetCollectionBlockedPacket::tags,
            SetCollectionBlockedPacket::new
        );

    /**
     * 兼容旧调用点的便捷构造：只切换「具体资源 id」的阻塞（不涉及标签）。
     * <p>主界面缓存格的 Ctrl+左键走它 —— 那里要精确控制这一个资源。</p>
     */
    public SetCollectionBlockedPacket(final int containerId, final boolean fluid,
                                     final ResourceLocation id, final boolean blocked) {
        this(containerId, fluid, id, blocked, List.of());
    }

    public static void handle(final SetCollectionBlockedPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof CollectionCacheMenu cacheMenu)) {
            return;
        }
        cacheMenu.setBlocked(packet.fluid(), packet.id(), packet.blocked(), packet.tags());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
