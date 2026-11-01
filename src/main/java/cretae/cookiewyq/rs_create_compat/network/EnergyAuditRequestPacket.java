package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * C2S：能耗监视页的<b>唯一客户端 → 服务端请求</b>（刷新 / 选中 / 取消选中，一个包三种意图）。
 *
 * <h2>为什么刷新是「客户端拉」而不是「服务端推」</h2>
 * <p>服务端推需要一张「谁的界面还开着」的会话表，而这个界面是<b>纯 Screen、没有容器菜单</b>
 * （见 {@code client/screen/EnergyAuditScreen}），服务端拿不到「界面关了」这个事实，
 * 只能靠 {@code containerMenu == inventoryMenu} 之类的间接判据，关屏后还会多发最多一个心跳。
 * 客户端轮询把「要不要数据」这件事交给唯一知道真相的一方：界面一关，{@code Screen#tick} 不再跑，
 * 请求自然停止，服务端<b>零常驻会话</b>。代价是每秒一个空包（20 tick），可忽略。</p>
 *
 * <h2>为什么不用三个包</h2>
 * <p>三个意图的字段是包含关系（刷新无参、取消无坐标、选中带坐标），拆成三个包会让注册表多两行、
 * 客户端多两处构造点，而语义上它们只是同一个动作的不同参数。{@link #action()} 是唯一的判别位。</p>
 *
 * <h2>服务端绝不信任客户端</h2>
 * <ul>
 *     <li>「选中」的坐标必须由服务端<b>重新枚举本网络</b>后确认「确实存在且当前耗电 &gt; 0」才回显；
 *     否则忽略（客户端无法让服务端去高亮任意方块）；</li>
 *     <li>刷新有节流（{@code support/NetworkEnergyAudit} 内按 gameTime 夹紧），不会因为客户端
 *     狂发请求而让服务端每秒枚举网络几百次；</li>
 *     <li>解码侧不预分配（{@code action} 之前的字段都是定长 / 变长整数，无条数字段）。</li>
 * </ul>
 *
 * @param action     {@link #ACTION_REFRESH} / {@link #ACTION_SELECT} / {@link #ACTION_CLEAR}
 * @param hasPos     是否携带坐标（只有 SELECT 带）
 * @param dimension  坐标所在维度（无坐标时为 null）
 * @param pos        坐标（无坐标时为 {@link BlockPos#ZERO}）
 */
public record EnergyAuditRequestPacket(int action, boolean hasPos,
                                       @Nullable ResourceLocation dimension, BlockPos pos)
    implements CustomPacketPayload {

    /** 只要一份最新快照（界面每 {@code energyAuditRefreshTicks} tick 发一次）。 */
    public static final int ACTION_REFRESH = 0;
    /** 选中某台方块（服务端校验后回显）。 */
    public static final int ACTION_SELECT = 1;
    /** 取消选中（服务端把回显的选中置空；框立刻消失，不靠 TTL 自己灭）。 */
    public static final int ACTION_CLEAR = 2;

    public static final Type<EnergyAuditRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "energy_audit_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyAuditRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.action());
                ByteBufCodecs.BOOL.encode(buf, packet.hasPos());
                if (packet.hasPos() && packet.dimension() != null) {
                    ResourceLocation.STREAM_CODEC.encode(buf, packet.dimension());
                    ByteBufCodecs.INT.encode(buf, packet.pos().getX());
                    ByteBufCodecs.INT.encode(buf, packet.pos().getY());
                    ByteBufCodecs.INT.encode(buf, packet.pos().getZ());
                }
            },
            buf -> {
                final int action = ByteBufCodecs.VAR_INT.decode(buf);
                final boolean hasPos = ByteBufCodecs.BOOL.decode(buf);
                if (!hasPos) {
                    return new EnergyAuditRequestPacket(action, false, null, BlockPos.ZERO);
                }
                final ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
                final BlockPos pos = new BlockPos(ByteBufCodecs.INT.decode(buf),
                    ByteBufCodecs.INT.decode(buf),
                    ByteBufCodecs.INT.decode(buf));
                return new EnergyAuditRequestPacket(action, true, dimension, pos);
            }
        );

    /** 构造「刷新」请求。 */
    public static EnergyAuditRequestPacket refresh() {
        return new EnergyAuditRequestPacket(ACTION_REFRESH, false, null, BlockPos.ZERO);
    }

    /** 构造「选中」请求。 */
    public static EnergyAuditRequestPacket select(final ResourceLocation dimension, final BlockPos pos) {
        return new EnergyAuditRequestPacket(ACTION_SELECT, true, dimension, pos);
    }

    /** 构造「取消选中」请求。 */
    public static EnergyAuditRequestPacket clear() {
        return new EnergyAuditRequestPacket(ACTION_CLEAR, false, null, BlockPos.ZERO);
    }

    public static void handle(final EnergyAuditRequestPacket packet, final ServerPlayer player) {
        // 服务端权威解析 / 校验 / 组包全部在 support/NetworkEnergyAudit（双端安全的普通类）。
        // 与既有 C2S 处理体（SwitchTerminalModePacket 等）同一约定：处理体直接在主线程上下文执行业务。
        cretae.cookiewyq.rs_create_compat.support.NetworkEnergyAudit.handleRequest(player, packet);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
