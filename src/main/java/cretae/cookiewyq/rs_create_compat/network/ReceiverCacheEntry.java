package cretae.cookiewyq.rs_create_compat.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * 网络资源接收器<b>资源列表</b>里的一行（物品或流体，二选一）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>接收器的缓存是 {@code RsccUnboundedItemStorage} / {@code RsccUnboundedFluidStorage}
 * —— <b>种类无上限</b>。旧界面用「16 个固定镜像物品格 + 4 条流体条目」表示它，
 * 于是第 17 种物品、第 5 种流体<b>在界面里根本看不见也取不出</b>（用户第 96 轮实测反馈）。
 * 现在界面改成「可滚动资源列表」，列表的行就是本 record：一行 = 一种资源（图标 + 名称 + 数量）。</p>
 *
 * <h2>为什么数量单独一个 {@code long}，而不是直接塞进 {@code ItemStack#getCount}</h2>
 * <p>{@link ItemStack#getCount()} 是 {@code int} 且单堆上限 64；而同一「物品 + 数据组件」在缓存里
 * 可能横跨<b>多个内部槽位</b>（{@code RsccUnboundedItemStorage} 每格最多 64 个、总容量 1728）。
 * 界面要显示的是<b>这一种资源的总量</b>，因此列表在服务端按身份聚合后单独带一个 {@code amount}；
 * 图标只用于渲染（{@code item().getCount()} 恒为 1，见 {@link #ofItem}）。</p>
 *
 * <h2>dist 安全</h2>
 * <p>本 record 只使用双端都存在的类型（{@link ItemStack} / {@link FluidStack}）与
 * {@code network} 包里的编解码器，不含任何 {@code net.minecraft.client.*}，
 * 因此可以被方块实体（服务端）构造、被界面（客户端）读取。</p>
 */
public record ReceiverCacheEntry(boolean isFluid, ItemStack item, FluidStack fluid, long amount) {
    /** 物品行：{@code stack} 只提供图标 / 名称（数量抹成 1），真实数量在 {@code amount}。 */
    public static ReceiverCacheEntry ofItem(final ItemStack stack, final long amount) {
        final ItemStack icon = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        return new ReceiverCacheEntry(false, icon, FluidStack.EMPTY, Math.max(0L, amount));
    }

    /** 流体行：{@code stack} 只提供图标 / 名称（数量抹成 1 mB），真实数量（mB）在 {@code amount}。 */
    public static ReceiverCacheEntry ofFluid(final FluidStack stack, final long amount) {
        final FluidStack icon = stack == null ? FluidStack.EMPTY : stack.copyWithAmount(1);
        return new ReceiverCacheEntry(true, ItemStack.EMPTY, icon, Math.max(0L, amount));
    }

    /** 这一行是否有内容（空行的图标 / 名称都没有意义）。 */
    public boolean isEmpty() {
        return isFluid ? fluid.isEmpty() : item.isEmpty();
    }

    /** 该行的显示名（物品名 / 流体名；客户端 tooltip 也用同一个来源）。 */
    @Nullable
    public net.minecraft.network.chat.Component hoverName() {
        if (isEmpty()) {
            return null;
        }
        return isFluid ? fluid.getHoverName() : item.getHoverName();
    }

    /**
     * 身份指纹：用于「要不要重发快照」的判断（不参与任何业务判定）。
     * <p>物品 / 流体的<b>数据组件</b>必须计入 —— 同一种「岩浆」带不同组件在 RS 里是不同资源，
     * 界面也必须当成两行。</p>
     */
    public long fingerprint() {
        if (isEmpty()) {
            return 0L;
        }
        final long id = isFluid
            ? net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(fluid.getFluid())
            : net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(item.getItem());
        return java.util.Objects.hash(isFluid, id,
            isFluid ? fluid.getComponents() : item.getComponents(), amount);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ReceiverCacheEntry> STREAM_CODEC =
        StreamCodec.of(
            (buf, entry) -> {
                ByteBufCodecs.BOOL.encode(buf, entry.isFluid());
                if (entry.isFluid()) {
                    FluidStack.OPTIONAL_STREAM_CODEC.encode(buf, entry.fluid());
                } else {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, entry.item());
                }
                ByteBufCodecs.VAR_LONG.encode(buf, entry.amount());
            },
            buf -> {
                final boolean isFluid = ByteBufCodecs.BOOL.decode(buf);
                final FluidStack fluidStack = isFluid
                    ? FluidStack.OPTIONAL_STREAM_CODEC.decode(buf)
                    : FluidStack.EMPTY;
                final ItemStack itemStack = isFluid
                    ? ItemStack.EMPTY
                    : ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                final long amount = ByteBufCodecs.VAR_LONG.decode(buf);
                return new ReceiverCacheEntry(isFluid, itemStack, fluidStack, amount);
            }
        );
}
