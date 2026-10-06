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

import java.util.List;

/**
 * C2S：设置输出总线在「延长型输出」模式下要导出的<b>类别 id 集合</b>（多选）。
 * <p>客户端发的是「<b>我想要的全量选择</b>」（每次点一下都重发整理后的整表），服务端不做乐观更新：
 * 服务端按容器 id 解析当前打开的菜单 → 取方块实体 → 写入选择并让执行仓<b>重新归一</b>归属
 * （不可用 / 无意义的 id 自然落不了地），随后经 {@link SyncExporterExecutorModePacket} 回传权威快照，
 * 客户端界面据此刷新每个类别的勾选态与「几台共享」标记。</p>
 */
public record SetExporterExecutorCategoriesPacket(int containerId, List<String> categoryIds)
    implements CustomPacketPayload {
    public static final Type<SetExporterExecutorCategoriesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_exporter_executor_categories"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetExporterExecutorCategoriesPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetExporterExecutorCategoriesPacket::containerId,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
            SetExporterExecutorCategoriesPacket::categoryIds,
            SetExporterExecutorCategoriesPacket::new
        );

    public static void handle(final SetExporterExecutorCategoriesPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof RsccExporterMenuBridge bridge)) {
            return;
        }
        if (!(bridge.rscc$getExporter() instanceof RsccExporterExecutorMode exporter)) {
            return;
        }
        // 服务端权威：选择直接写入，是否「可见 / 生效」由执行仓归一决定（不可用的 id 不会出现在快照里）
        exporter.rscc$setExportCategoryIds(packet.categoryIds());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
