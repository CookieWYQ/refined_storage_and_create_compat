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

import java.util.List;

/**
 * C2S：设置输入总线在「延长型输入」模式下要<b>收回 RS 网络</b>的类别 id 集合（多选）。
 * <p>{@link SetExporterExecutorCategoriesPacket} 的对称版：客户端发「我想要的全量选择」
 * （每次点一下都重发整理后的整表），服务端不做乐观更新 —— 按容器 id 解析当前打开的菜单 →
 * 取方块实体 → 写入选择，随后经 {@link SyncImporterExecutorModePacket} 回传权威快照，
 * 客户端界面据此刷新每个类别的勾选态。</p>
 * <p>发空表 = 什么都不收回（手动模式下的默认值）。<b>全自动开关走
 * {@link SetImporterAutoCollectPacket}</b>：本包只写「勾选表」，两者互不覆盖，
 * 因此切自动 / 手动不会改写玩家的手动配置。</p>
 */
public record SetImporterExecutorCategoriesPacket(int containerId, List<String> categoryIds)
    implements CustomPacketPayload {
    public static final Type<SetImporterExecutorCategoriesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "set_importer_executor_categories"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetImporterExecutorCategoriesPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetImporterExecutorCategoriesPacket::containerId,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
            SetImporterExecutorCategoriesPacket::categoryIds,
            SetImporterExecutorCategoriesPacket::new
        );

    public static void handle(final SetImporterExecutorCategoriesPacket packet, final ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.containerId != packet.containerId()) {
            return;
        }
        if (!(player.containerMenu instanceof RsccImporterMenuBridge bridge)) {
            return;
        }
        if (!(bridge.rscc$getImporter() instanceof RsccImporterExecutorMode importer)) {
            return;
        }
        // 服务端权威：选择直接写入，是否「可见」由执行舱的类别列表决定（不可用的 id 不会出现在快照里）
        importer.rscc$setImportCategoryIds(packet.categoryIds());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
