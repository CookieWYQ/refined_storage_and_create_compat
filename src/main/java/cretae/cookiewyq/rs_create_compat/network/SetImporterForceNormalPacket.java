package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterMenuBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：切换输入总线的「<b>强制为普通总线</b>」开关 —— {@link SetExporterForceNormalPacket} 的对称版。
 *
 * <p>与输出总线侧完全同一条规则：把「被自动判定成序列装配总线」这件事关掉后，
 * 界面回到普通输入总线（走 RS 原版「相邻容器 → 网络」策略）。服务端权威 + 容器校验 + 回传权威状态。</p>
 */
public record SetImporterForceNormalPacket(int containerId, boolean forceNormal) implements CustomPacketPayload {
    public static final Type<SetImporterForceNormalPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_importer_force_normal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetImporterForceNormalPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetImporterForceNormalPacket::containerId,
            ByteBufCodecs.BOOL, SetImporterForceNormalPacket::forceNormal,
            SetImporterForceNormalPacket::new
        );

    public static void handle(final SetImporterForceNormalPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof RsccImporterMenuBridge bridge)) {
            return;
        }
        if (!(bridge.rscc$getImporter() instanceof RsccImporterExecutorMode importer)) {
            return;
        }
        importer.rscc$setForceNormalBus(packet.forceNormal());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
