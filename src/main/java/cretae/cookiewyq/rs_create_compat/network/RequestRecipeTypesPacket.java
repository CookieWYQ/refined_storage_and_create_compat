package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * C2S：请求「配方类型候选列表」（含全部已注册配方类型，当前网络内已有执行仓绑定的排前面）。
 * <p>服务端根据玩家当前打开的菜单定位所在网络，收集已绑定类型后交由
 * {@link SyncRecipeTypesPacket#from(java.util.Collection)} 构造并回传。</p>
 * <p>可用于两处界面：执行仓绑定配置（{@link SequenceExecutionChamberMenu}）与
 * 单元样板配置（{@link SequencePatternTerminalMenu}）。</p>
 */
public record RequestRecipeTypesPacket() implements CustomPacketPayload {
    public static final Type<RequestRecipeTypesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "request_recipe_types"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRecipeTypesPacket> STREAM_CODEC =
        StreamCodec.unit(new RequestRecipeTypesPacket());

    public static void handle(final RequestRecipeTypesPacket packet, final ServerPlayer player) {
        final Set<String> bound = new HashSet<>();
        if (player.containerMenu instanceof SequenceExecutionChamberMenu menu
            && menu.getChamber() != null) {
            // 执行仓配置界面：直接用本仓所在网络的已绑定类型
            bound.addAll(menu.getChamber().boundRecipeTypesInNetwork());
        } else if (player.containerMenu instanceof SequencePatternTerminalMenu menu
            && menu.getTerminal() != null) {
            // 终端（单元样板配置）：用终端所在网络的执行仓绑定
            for (final SequencePatternTerminalBlockEntity.ChamberInfo chamber
                : menu.getTerminal().listChambers()) {
                if (chamber.recipeType() != null && !chamber.recipeType().isEmpty()) {
                    bound.add(chamber.recipeType());
                }
            }
        }
        PacketDistributor.sendToPlayer(player, SyncRecipeTypesPacket.from(bound));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
