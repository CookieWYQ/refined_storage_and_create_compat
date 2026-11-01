package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.SenderShortage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * S2C：**「发送量超过网络存量，但可以自动合成」的询问**（第 131 轮 F 组）。
 *
 * <h2>为什么是一个单独的包，而不是塞进配置快照</h2>
 * <p>快照是「界面常态」的完整覆盖（每改一次订单都发），而询问是<b>一次性的交互</b>：
 * 它必须能被「回答 / 超时 / 关界面」明确消费掉，否则同一份快照反复下发会让客户端弹出一串
 * 询问窗口。因此用一个只带 5 个数字的独立包，客户端用 {@link #takeFor(int)} 取走一次即清空。</p>
 *
 * <h2>只带数字，不带资源对象</h2>
 * <p>用户拍板的文案只有四个数（目标 / 现有 / 最多可合成 / 还缺）＋两按钮上的两个数。
 * 客户端不参与任何算术：五个数都是服务端算好发下来的（{@link SenderShortage.Awaiting} 里
 * 那四条 {@code *Total()} 就是同一套口径）。这样「按钮说合成 25 并发送 75」与
 * 「服务端真的投 75」不可能对不上。</p>
 *
 * <h2>dist 安全</h2>
 * <p>本类工厂在服务端调用，处理体只在客户端把包塞进信箱；全类没有
 * {@code net.minecraft.client.*} 引用。</p>
 */
public record SenderShortagePromptPacket(int containerId, long target, long have,
                                         long craftable, long missing, long sendAfterCraft)
    implements CustomPacketPayload {

    public static final Type<SenderShortagePromptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "sender_shortage_prompt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SenderShortagePromptPacket>
        STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                ByteBufCodecs.VAR_INT.encode(buf, packet.containerId());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.target());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.have());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.craftable());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.missing());
                ByteBufCodecs.VAR_LONG.encode(buf, packet.sendAfterCraft());
            },
            buf -> new SenderShortagePromptPacket(
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf))
        );

    /** 服务端工厂：五个数全部来自 {@link SenderShortage.Awaiting}（口径只有一处）。 */
    public static SenderShortagePromptPacket of(final int containerId,
                                                final SenderShortage.Awaiting awaiting) {
        return new SenderShortagePromptPacket(containerId, awaiting.targetTotal(),
            awaiting.haveTotal(), awaiting.craftableTotal(), awaiting.missingTotal(),
            awaiting.sendAfterCraftTotal());
    }

    // ==================== 客户端信箱 ====================

    /** 客户端「最近收到的一份询问」（只在客户端主线程读写）。 */
    @Nullable
    private static volatile SenderShortagePromptPacket lastReceived;

    /**
     * 取走「给 {@code containerId} 这一份」的询问（没有 ⇒ {@code null}；取走后清空）。
     * <p>由发送界面在 {@code containerTick} 里调用；取走即清空 ⇒ 同一个询问只会弹一次窗。</p>
     */
    @Nullable
    public static SenderShortagePromptPacket takeFor(final int containerId) {
        final SenderShortagePromptPacket packet = lastReceived;
        if (packet == null || packet.containerId() != containerId) {
            return null;
        }
        lastReceived = null;
        return packet;
    }

    /** 只有 {@code containerId} 对得上的界面才收；对不上的直接丢弃（不弹错窗口）。 */
    public static void handle(final SenderShortagePromptPacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> lastReceived = packet);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
