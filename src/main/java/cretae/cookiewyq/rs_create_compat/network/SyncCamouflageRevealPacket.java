package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：把<b>该玩家自己的</b>「隐藏已填充方块」全局开关下发给他的客户端（用户第 2 条）。
 *
 * <h2>为什么单独一个轻量包，而不是塞进外壳材质那条链路</h2>
 * <p>外壳材质是<b>被裹方块方块实体上的数据附件</b>（由 NeoForge 自动同步，见
 * {@code support/RsccCamouflageAttachment}），描述的是<b>世界</b>里哪些格被伪装、外壳是什么，
 * 对所有玩家都一样；而本包是一位布尔值、描述的是<b>这位玩家</b>的显示偏好 ——
 * 两者生命周期完全不同（前者随方块实体 NBT / 区块下发走，后者只在登录 / 换维度 / 重生 /
 * 按下 K 时发）。混在一起会让「甲改壳」与「乙切显示」互相触发无谓的重发。</p>
 *
 * <h2>为什么要主动下发（而不是客户端自己记）</h2>
 * <p>「重登保留」这条要求决定了真值必须存在服务端（玩家持久化数据）：客户端没有存档、
 * 也没有权限改它。因此：登录 / 换维度 / 重生时由 {@code RsccCamouflageInteraction} 的既有挂点
 * 各发一次；按下 K 切换后由服务端在同一个 tick 内回发一次（见
 * {@code ToggleCamouflageRevealPacket#handle}）。客户端只把它写进只读镜像。</p>
 */
public record SyncCamouflageRevealPacket(boolean hidden) implements CustomPacketPayload {
    public static final Type<SyncCamouflageRevealPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_camouflage_reveal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCamouflageRevealPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL, SyncCamouflageRevealPacket::hidden,
            SyncCamouflageRevealPacket::new
        );

    public static void handle(final SyncCamouflageRevealPacket packet, final IPayloadContext ctx) {
        // 纯客户端表现：写进只读镜像即可（渲染器每帧读它），不触发任何世界交互
        ctx.enqueueWork(() -> RsccCamouflage.applyClientHidden(packet.hidden()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
