package cretae.cookiewyq.rs_create_compat.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 能耗监视快照里的<b>一台方块</b>（一个世界坐标一条）。
 *
 * <h2>两条读数为什么都要（不要只留一条）</h2>
 * <ul>
 *     <li>{@link #currentUsage()}：<b>此刻真的在被抽走的量</b>。
 *     口径与 RS 自己的抽取路径逐字一致 —— {@code AbstractNetworkNode#doWork()} 里
 *     {@code if (network == null || !active) return; ... extract(getEnergyUsage())}，
 *     因此本字段 = {@code node.isActive() ? node.getEnergyUsage() : 0}；</li>
 *     <li>{@link #nominalUsage()}：{@code node.getEnergyUsage()} 的原值（标称）。
 *     机器待机（{@code active == false}，例如红石模式关掉 / 网络没电）时它仍然 &gt; 0，
 *     界面据此显示「这台是会耗电的机器，现在待机」而不是把它从列表里删掉。</li>
 * </ul>
 * <p><b>不自己乘任何系数</b>：两个值都直接来自 RS，本模组不做「按速度升级折算」「按工作时长折算」
 * 之类的二次加工 —— 那会让界面读数与 F3 / 控制器界面对不上。</p>
 *
 * <p>同一坐标可能有<b>多个容器</b>（RS 的 Relay 就同时挂 input / output 两个网络节点，每个节点各自
 * {@code doWork} 抽一次电），聚合时按坐标合并并<b>逐节点求和</b>，因此这里的值已经是该坐标所有节点的合计。</p>
 *
 * @param dimension     维度 id（来自 {@code InWorldNetworkNodeContainer#getPosition()} 的 {@code GlobalPos}）
 * @param pos           方块坐标
 * @param currentUsage  当前每 tick 抽取量（FE/t，= active ? 标称 : 0）
 * @param nominalUsage  标称每 tick 耗电（FE/t，{@code AbstractNetworkNode#getEnergyUsage()}）
 * @param active        该坐标上是否有任一节点处于 active（能真正工作）状态
 */
public record EnergyAuditNode(ResourceLocation dimension, BlockPos pos,
                              long currentUsage, long nominalUsage, boolean active) {

    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyAuditNode> STREAM_CODEC =
        StreamCodec.of(
            (buf, node) -> {
                ResourceLocation.STREAM_CODEC.encode(buf, node.dimension());
                ByteBufCodecs.INT.encode(buf, node.pos().getX());
                ByteBufCodecs.INT.encode(buf, node.pos().getY());
                ByteBufCodecs.INT.encode(buf, node.pos().getZ());
                ByteBufCodecs.VAR_LONG.encode(buf, node.currentUsage());
                ByteBufCodecs.VAR_LONG.encode(buf, node.nominalUsage());
                ByteBufCodecs.BOOL.encode(buf, node.active());
            },
            buf -> new EnergyAuditNode(
                ResourceLocation.STREAM_CODEC.decode(buf),
                new BlockPos(ByteBufCodecs.INT.decode(buf),
                    ByteBufCodecs.INT.decode(buf),
                    ByteBufCodecs.INT.decode(buf)),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.BOOL.decode(buf)
            )
        );

    /** 选中判定用的稳定键（维度 + 坐标；不依赖列表下标 —— 快照每秒重排，下标会漂）。 */
    public String key() {
        return keyOf(dimension(), pos());
    }

    /** 与 {@link #key()} 同一口径的静态版本（客户端只拿到拆开的字段时用）。 */
    public static String keyOf(final ResourceLocation dimension, final BlockPos pos) {
        return dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 坐标的玩家可读写法（与 F3 的 {@code x, y, z} 同序）。 */
    public static String posText(final BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /** 是否在给定坐标上（客户端列表点选后本地比对用；服务端校验走 {@link #keyOf}）。 */
    public boolean isAt(@Nullable final ResourceLocation dimension, @Nullable final BlockPos pos) {
        return pos != null && pos.equals(this.pos) && dimension != null && dimension.equals(this.dimension);
    }
}
