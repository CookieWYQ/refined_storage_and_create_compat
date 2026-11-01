package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * C2S：网络资源接收器<b>可滚动资源列表</b>的三个动作（取物品 / 取流体 / 翻页）。
 *
 * <h2>为什么必须有自己的 C2S 包</h2>
 * <p>第 96 轮把界面从「16 个固定镜像槽 + 4 条流体条目」改成「逐页同步的资源列表」：
 * 一种物品在缓存里可能横跨多个内部槽位，而界面要的是「这一种资源总共多少」；
 * 流体的身份还得带数据组件。原版槽位协议 / 菜单按钮协议都表达不了这两件事，
 * 因此取出必须按<b>资源身份 + 数量</b>发一次请求（用户第 96 轮口径明确要求「资源 key + 数量」）。</p>
 *
 * <h2>纪律（与 {@link RequesterOrderPacket} 同一套）</h2>
 * <ul>
 *     <li>客户端<b>只提交「要做什么」</b>：资源身份（{@link ItemStack} / {@link FluidStack}，都含数据组件）、
 *     要不要「全部」（Shift）。<b>数量不由客户端决定</b> —— 服务端按真实存量与背包空间自己算，
 *     因此不存在「客户端说取 999999 个」这种请求；</li>
 *     <li>服务端先校验<b>归属</b>（这个 containerId 对应的菜单就是本接收器）与<b>距离 / 存在性</b>
 *     （{@link NetworkResourceReceiverMenu#stillValid} 逐字同源），再从真实缓存扣减；</li>
 *     <li>扣减与给予是同一笔账：先算「背包能收下多少」，只从缓存取那么多，剩下的<b>回填缓存</b>
 *     —— 绝不复制、绝不销毁、也绝不「扣了没给」；</li>
 *     <li>翻页只是<b>服务端菜单状态</b>（页码），不写任何缓存，也不需要额外权限。</li>
 * </ul>
 *
 * <h2>dist 安全</h2>
 * <p>本包只被服务端处理（{@code playToServer}），处理体只调用 {@code menu} 包里的静态方法，
 * 不出现任何 {@code net.minecraft.client.*}。</p>
 */
public record ReceiverCacheActionPacket(int containerId, int action, int page,
                                        ItemStack item, FluidStack fluid, boolean all)
    implements CustomPacketPayload {

    /** 取物品：按 {@link #item} 的身份从缓存取（一组 / 全部由 {@link #all} 决定）。 */
    public static final int ACTION_TAKE_ITEM = 0;
    /** 取流体：按 {@link #fluid} 的身份换整桶（1000 mB / 桶，背包里必须有空桶）。 */
    public static final int ACTION_TAKE_FLUID = 1;
    /** 翻页：只改服务端菜单里的页码，然后回发快照。 */
    public static final int ACTION_PAGE = 2;

    /** 翻页请求的便捷构造（不带资源身份）。 */
    public static ReceiverCacheActionPacket page(final int containerId, final int page) {
        return new ReceiverCacheActionPacket(containerId, ACTION_PAGE, page, ItemStack.EMPTY,
            FluidStack.EMPTY, false);
    }

    /** 取物品请求。 */
    public static ReceiverCacheActionPacket takeItem(final int containerId, final ItemStack stack,
                                                     final boolean all) {
        return new ReceiverCacheActionPacket(containerId, ACTION_TAKE_ITEM, 0, stack,
            FluidStack.EMPTY, all);
    }

    /** 取流体请求。 */
    public static ReceiverCacheActionPacket takeFluid(final int containerId, final FluidStack stack,
                                                      final boolean all) {
        return new ReceiverCacheActionPacket(containerId, ACTION_TAKE_FLUID, 0, ItemStack.EMPTY, stack,
            all);
    }

    public static final Type<ReceiverCacheActionPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "receiver_cache_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReceiverCacheActionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_INT.encode(buf, packet.action());
                ByteBufCodecs.VAR_INT.encode(buf, packet.page());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,
                    packet.item() == null ? ItemStack.EMPTY : packet.item());
                FluidStack.OPTIONAL_STREAM_CODEC.encode(buf,
                    packet.fluid() == null ? FluidStack.EMPTY : packet.fluid());
                ByteBufCodecs.BOOL.encode(buf, packet.all());
            },
            buf -> {
                final int containerId = ByteBufCodecs.VAR_INT.decode(buf);
                final int action = ByteBufCodecs.VAR_INT.decode(buf);
                final int page = ByteBufCodecs.VAR_INT.decode(buf);
                final ItemStack item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                final FluidStack fluid = FluidStack.OPTIONAL_STREAM_CODEC.decode(buf);
                final boolean all = ByteBufCodecs.BOOL.decode(buf);
                return new ReceiverCacheActionPacket(containerId, action, page, item, fluid, all);
            }
        );

    /**
     * 服务端处理体。
     * <p>本方法<b>只做转交</b>：归属 / 距离 / 服务端侧判定都在
     * {@link NetworkResourceReceiverMenu#handleCacheAction(ServerPlayer, ReceiverCacheActionPacket)}
     * 里（与改名、清空订单同一套判据），因此「谁能开这个界面」与「谁能取这个缓存」永远同一个答案。</p>
     */
    public static void handle(final ReceiverCacheActionPacket packet, final ServerPlayer player) {
        NetworkResourceReceiverMenu.handleCacheAction(player, packet);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
