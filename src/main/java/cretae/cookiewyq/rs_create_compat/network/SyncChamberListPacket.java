package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：新语义（v4）—— 把当前终端网络内的执行仓列表回传给客户端。
 * <p>每项含：{@link Entry#pos()}（坐标）、{@link Entry#name()}（显示名）、
 * {@link Entry#recipeType()}（绑定的配方类型 id）。</p>
 * <p><b>接口约定（供下一阶段 GUI 使用）</b>：本包不直接驱动任何现有 Screen（前端重做前禁止改动），
 * 客户端处理器只把最近一次结果缓存到 {@link #getLastReceived()}；屏幕重做后可调用该方法读取，
 * 或在屏幕内自行注册处理器覆盖缓存。</p>
 */
public record SyncChamberListPacket(List<Entry> chambers) implements CustomPacketPayload {
    /** 单台执行仓的同步项。 */
    public record Entry(BlockPos pos, String name, String recipeType) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, Entry::pos,
                ByteBufCodecs.STRING_UTF8, Entry::name,
                ByteBufCodecs.STRING_UTF8, Entry::recipeType,
                Entry::new
            );
    }

    public static final Type<SyncChamberListPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_chamber_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncChamberListPacket> STREAM_CODEC =
        StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncChamberListPacket::chambers,
            SyncChamberListPacket::new
        );

    /** 客户端最近一次收到的机器列表（不可变；屏幕重做前由前端读取）。 */
    private static volatile List<Entry> lastReceived = List.of();

    /** 客户端最近一次收到的机器列表（可能为空列表，永不为 null）。 */
    public static List<Entry> getLastReceived() {
        return lastReceived;
    }

    /** 由终端方块实体的 ChamberInfo 列表构造同步包。 */
    public static SyncChamberListPacket from(final List<SequencePatternTerminalBlockEntity.ChamberInfo> chambers) {
        final List<Entry> entries = new ArrayList<>(chambers.size());
        for (final SequencePatternTerminalBlockEntity.ChamberInfo info : chambers) {
            entries.add(new Entry(info.pos(), info.name(), info.recipeType()));
        }
        return new SyncChamberListPacket(entries);
    }

    /**
     * <b>事件驱动的新鲜度</b>：某台执行仓的「是否可用 / 显示名 / 配方类型」写成功之后，
     * 把<b>当场现算</b>的列表推给正开着「同一条网络」的序列样板终端的玩家。
     *
     * <h2>为什么需要它（用户要求「时时刻刻都能检测到」）</h2>
     * <p>列表本身<b>没有任何缓存</b>（{@link SequencePatternTerminalBlockEntity#listChambers()} 每次都按
     * 网络现状现算），但客户端只在<b>界面打开那一拍</b>请求一次。于是「终端已经开着、配置才完成」时
     * （多人服务器上别人帮你配 / 链自愈把配置归位到新链首 / 拆掉一台仓）界面会一直停在旧列表，
     * 直到重开界面 —— 这就会被玩家看成「刷新没做好」。</p>
     * <p>这里刻意<b>不</b>用「客户端定时强制刷新」：那既费带宽又把陈旧藏在背后。改为在<b>写入点</b>
     * （{@code setRecipeType} / {@code setChamberName} / 链自愈 / 拆链移交）触发一次推送，
     * 因此「配置完成 → 下一次界面渲染」之间不存在窗口；没有写入时一包都不发。</p>
     *
     * <h2>开销</h2>
     * <p>只在配置 / 链归属真的变化时调用（不是每 tick）：先按玩家数扫一遍（通常 0~4 人），
     * 只对「确实开着同网络终端」的玩家发送，且整条网络只现算<b>一份</b>列表（各终端的列表同源）。
     * 没有查看者时连 {@code listChambers()} 都不会执行。</p>
     *
     * @param level   执行仓所在世界（非服务端 / null ⇒ 什么都不做）
     * @param network 执行仓当前所在网络（null = 未接入网络 ⇒ 无终端可推）
     */
    public static void pushAvailabilityChanged(@org.jetbrains.annotations.Nullable final Level level,
                                               @org.jetbrains.annotations.Nullable final Network network) {
        if (!(level instanceof final ServerLevel serverLevel) || network == null) {
            return;
        }
        List<Entry> snapshot = null;
        for (final ServerPlayer player : serverLevel.players()) {
            if (!(player.containerMenu instanceof final SequencePatternTerminalMenu menu)) {
                continue;
            }
            final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
            if (terminal == null) {
                continue;
            }
            // 同一条网络才有同一份机器集合：跨网络的终端列表内容不同，不能拿这一份去覆盖它
            if (terminal.getNetworkOrNull() != network) {
                continue;
            }
            if (snapshot == null) {
                snapshot = List.copyOf(from(terminal.listChambers()).chambers());
            }
            PacketDistributor.sendToPlayer(player, new SyncChamberListPacket(snapshot));
        }
    }

    public static void handle(final SyncChamberListPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> lastReceived = List.copyOf(packet.chambers()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
