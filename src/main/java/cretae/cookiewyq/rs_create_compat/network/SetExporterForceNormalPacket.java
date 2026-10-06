package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterMenuBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换输出总线的「<b>强制为普通总线</b>」开关（用户原话：「赶紧做我要的按钮」）。
 *
 * <p><b>为什么需要它</b>：本模组会把「沿线缆够得着序列执行仓」的输出总线<b>自动</b>判定成
 * 「序列装配总线」（界面换成类别条、取料策略换成从执行仓取）。玩家需要一个按钮把这条
 * <b>自动判定关掉</b>，之后打开就是<b>普通输出总线界面</b>。</p>
 *
 * <p><b>服务端权威</b>：客户端只发「我想要的状态」，服务端按容器 id 解析当前打开的菜单 → 取方块实体 →
 * 校验后写入（落盘、读档保留），随后经 {@link SyncExporterExecutorModePacket} 回传权威状态，
 * 客户端界面据此切换。客户端镜像永远是只读的。</p>
 */
public record SetExporterForceNormalPacket(int containerId, boolean forceNormal) implements CustomPacketPayload {
    public static final Type<SetExporterForceNormalPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_exporter_force_normal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetExporterForceNormalPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetExporterForceNormalPacket::containerId,
            ByteBufCodecs.BOOL, SetExporterForceNormalPacket::forceNormal,
            SetExporterForceNormalPacket::new
        );

    public static void handle(final SetExporterForceNormalPacket packet, final ServerPlayer player) {
        // 服务端只接受「当前打开的菜单就是该容器」的请求（与其它总线包同一套校验）
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof RsccExporterMenuBridge bridge)) {
            return;
        }
        if (!(bridge.rscc$getExporter() instanceof RsccExporterExecutorMode exporter)) {
            return;
        }
        exporter.rscc$setForceNormalBus(packet.forceNormal());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
