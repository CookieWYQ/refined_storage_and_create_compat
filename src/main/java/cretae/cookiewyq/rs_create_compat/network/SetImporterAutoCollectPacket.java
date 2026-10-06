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
 * C2S：切换输入总线「延长型输入」模式的<b>全自动 / 手动</b>开关（默认全自动）。
 *
 * <p><b>为什么单独一个包，而不是并进 {@link SetImporterExecutorCategoriesPacket}</b>：
 * 那个包写的是「要收回哪些类别」，而全自动模式下类别集合由服务端按「非输入类」推导、与玩家勾选无关。
 * 若两者共用一个包，切开关时就必须把「当前看到的勾选」当成新配置写回去，
 * 会静默改写玩家手动模式的配置。分成两个包后，<b>切开关只动开关</b>，手动勾选原样保留（切回来还在）。</p>
 *
 * <p>与类别包同一套服务端权威校验：按 {@code containerId} 解析当前打开的菜单 → 桥接接口 → 方块实体，
 * 任一环节不符直接丢弃（防串台）。写入后由 {@link SyncImporterExecutorModePacket} 回传权威快照。</p>
 */
public record SetImporterAutoCollectPacket(int containerId, boolean autoCollect)
    implements CustomPacketPayload {
    public static final Type<SetImporterAutoCollectPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_importer_auto_collect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetImporterAutoCollectPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetImporterAutoCollectPacket::containerId,
            ByteBufCodecs.BOOL, SetImporterAutoCollectPacket::autoCollect,
            SetImporterAutoCollectPacket::new
        );

    public static void handle(final SetImporterAutoCollectPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof RsccImporterMenuBridge bridge)) {
            return;
        }
        if (!(bridge.rscc$getImporter() instanceof RsccImporterExecutorMode importer)) {
            return;
        }
        importer.rscc$setAutoCollect(packet.autoCollect());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
