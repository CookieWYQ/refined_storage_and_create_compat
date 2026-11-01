package cretae.cookiewyq.rs_create_compat.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 能耗监视快照里的<b>一种方块</b>（按方块注册 id 合并的「类型」行）。
 *
 * <h2>为什么按「方块注册 id」合并，而不是按物品 / 按类</h2>
 * <p>用户原话：「<i>相同种类的方块合并显示放在一块（就比如说那些总线什么的放在一块）</i>」。
 * 「同一种」的最细、最不会误解的判据就是<b>方块注册 id</b>（{@code minecraft:hopper} /
 * {@code refinedstorage:importer}）：同 id 的方块外观、名字、耗电语义完全一样，合并没有歧义；
 * 若按物品合并，会把「同一种方块的不同颜色变体」混在一起；若按 Java 类合并，
 * 「各种总线」会变成一行，但玩家没法从一行里看出到底哪一种是耗电大户。</p>
 *
 * <p>每种类型展开后是它<b>每一台</b>的坐标 / 维度 / 读数（{@link #nodes()}），
 * 这样「某一种里到底是哪一台在耗电」直接可见。</p>
 *
 * @param blockId      方块注册 id（合并键 + 无图标时的兜底名字）
 * @param icon         图标（方块对应物品的 {@link ItemStack}；方块没有对应物品时为 {@link ItemStack#EMPTY}）。
 *                     图标只在客户端渲染时用，不参与任何判定
 * @param count        该类型的<b>方块台数</b>（= {@link #nodes()} 的完整数量；截断时可能大于列表长度）
 * @param currentUsage 该类型所有台数的<b>当前</b>耗电合计（FE/t）
 * @param nominalUsage 该类型所有台数的<b>标称</b>耗电合计（FE/t）
 * @param nodes        每台的明细（已按当前耗电降序；因快照上限被截断时可能少于 {@link #count}）
 */
public record EnergyAuditType(ResourceLocation blockId, ItemStack icon, int count,
                              long currentUsage, long nominalUsage, List<EnergyAuditNode> nodes) {

    /** 单条类型行里最多携带的子项条数（解码侧硬上限；与 Config 的节点上限同量级，绝不无限预分配）。 */
    public static final int MAX_NODES_PER_TYPE = 4096;

    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyAuditType> STREAM_CODEC =
        StreamCodec.of(
            (buf, type) -> {
                ResourceLocation.STREAM_CODEC.encode(buf, type.blockId());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, type.icon());
                ByteBufCodecs.VAR_INT.encode(buf, type.count());
                ByteBufCodecs.VAR_LONG.encode(buf, type.currentUsage());
                ByteBufCodecs.VAR_LONG.encode(buf, type.nominalUsage());
                ByteBufCodecs.VAR_INT.encode(buf, type.nodes().size());
                for (final EnergyAuditNode node : type.nodes()) {
                    EnergyAuditNode.STREAM_CODEC.encode(buf, node);
                }
            },
            buf -> {
                final ResourceLocation blockId = ResourceLocation.STREAM_CODEC.decode(buf);
                final ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                final int count = ByteBufCodecs.VAR_INT.decode(buf);
                final long current = ByteBufCodecs.VAR_LONG.decode(buf);
                final long nominal = ByteBufCodecs.VAR_LONG.decode(buf);
                final int declared = ByteBufCodecs.VAR_INT.decode(buf);
                // 解码纪律（与 SyncChargerFilterPacket 同一套）：**照声明条数把字节全读完**（否则后续
                // 字段全部错位），但只保留前 MAX_NODES_PER_TYPE 条 —— 内存上界与对端写了什么无关。
                final List<EnergyAuditNode> nodes = new ArrayList<>();
                for (int i = 0; i < declared; i++) {
                    final EnergyAuditNode node = EnergyAuditNode.STREAM_CODEC.decode(buf);
                    if (nodes.size() < MAX_NODES_PER_TYPE) {
                        nodes.add(node);
                    }
                }
                return new EnergyAuditType(blockId, icon, count, current, nominal, List.copyOf(nodes));
            }
        );

    public EnergyAuditType {
        icon = icon == null ? ItemStack.EMPTY : icon;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
