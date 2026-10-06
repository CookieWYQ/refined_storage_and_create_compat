package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * S2C：<b>「这几格的伪装数据变了，重烘一下你那段网格」的极轻信号</b>。
 *
 * <h2>为什么需要它（NeoForge 的附件同步不含这一步）</h2>
 * <p>外壳材质本身<b>不走这个包</b>：它挂在被裹方块的方块实体上，由 NeoForge 的附件同步
 * （{@code SyncAttachmentsPayload}）自动下发给观察者，客户端读到的就是权威数据。
 * 但 NeoForge 把附件写回客户端方块实体之后<b>不会</b>重烘区块网格 —— 而外壳的几何是
 * <b>烘焙产物</b>（见 {@code client/model/CamouflageShellModel}）。不给客户端一个
 * 「这一段脏了」的信号，玩家换壳 / 取壳后会一直看到旧画面，直到因为别的原因重烘那一块。</p>
 * <p>Create 的伪装板在<b>客户端</b>做这件事（{@code CopycatBlockEntity#redraw()} →
 * {@code level.sendBlockUpdated(pos, state, state, 16)}）；本模组把它挪到服务端一侧，
 * 用这个包送到正确的玩家手里 —— 服务端本来就只能在服务端做（客户端拿不到别的玩家的坐标系）。</p>
 *
 * <h2>它<b>不</b>携带任何渲染数据</h2>
 * <p>负载只有坐标（{@code BlockPos#asLong()} 列表）。材质仍然只有一条来源 = 方块实体附件，
 * 因此这不属于被本轮彻底删除的那条「按世界坐标反查伪装记录」的链路：
 * 本包<b>查不到任何东西</b>，只是把「请重烘」这件事告诉客户端。</p>
 *
 * <h2>为什么按区块打包、且用 {@code sendToPlayersTrackingChunk}</h2>
 * <p>连锁套壳一次可能改动几十格，逐格发包会浪费带宽；同区块的坐标合成一条包，
 * 并且只发给<b>正在观察该区块</b>的玩家（别人的客户端不该收到）。</p>
 *
 * <h2>为什么处理体里没有任何客户端类型</h2>
 * <p>专用服务端也会加载本类（它被注册进 {@code playToClient}），因此这里只能使用
 * <b>双端都存在</b>的类型：{@code ctx.player().level()} 在客户端运行时本来就是
 * {@code ClientLevel}，而 {@code Level#sendBlockUpdated} 是双端逐字同名的方法
 * （客户端那一侧正是 {@code ClientLevel#sendBlockUpdated → LevelRenderer.blockChanged}，
 * 会把这一格周围 3×3 个区段标脏并重烘）。因此不需要引用
 * {@code net.minecraft.client.*} 里的任何一个类。</p>
 */
public record RefreshCamouflagePacket(List<Long> positions) implements CustomPacketPayload {
    public static final Type<RefreshCamouflagePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "refresh_camouflage"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RefreshCamouflagePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), RefreshCamouflagePacket::positions,
            RefreshCamouflagePacket::new
        );

    /**
     * 客户端注册的「这条坐标是伪装格」回调（<b>专用服务端上恒为 {@code null}</b>，一次都不会被调用）。
     *
     * <h2>为什么必须是可注入的回调，而不是在这里直接引用客户端类</h2>
     * <p>本类被注册进 {@code playToClient}，因此<b>专用服务端也会加载它</b>；一旦这里出现任何
     * {@code net.minecraft.client.*} 的类型，专用服务端会解析失败。回调在客户端初始化时由
     * {@code ClientInit} 挂上（见 {@code client/CamouflageShellDisplay#track}），服务端侧永远是 null。</p>
     *
     * <h2>为什么要登记坐标（用户第 1 条：K 键切换要平滑）</h2>
     * <p>K 键翻转的是「外壳画材质还是画框架」这一态，而它是<b>烘焙产物</b> —— 翻转后必须把受影响的格
     * 重新标脏。客户端据此维护一份「伪装格坐标」表，切换时<b>只</b>重建这些格（旧实现调
     * {@code levelRenderer.allChanged()} 整片重烘，会造成整帧不渲染 + 强烈卡顿）。</p>
     */
    private static volatile java.util.function.Consumer<BlockPos> clientTracker;

    /** 客户端：挂上「伪装格坐标」登记回调（只有 {@code ClientInit} 调用一次）。 */
    public static void setClientTracker(final java.util.function.Consumer<BlockPos> tracker) {
        clientTracker = tracker;
    }

    public static void handle(final RefreshCamouflagePacket packet, final IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            final Level level = ctx.player().level();
            final java.util.function.Consumer<BlockPos> tracker = clientTracker;
            for (final long packed : packet.positions()) {
                final BlockPos pos = BlockPos.of(packed);
                if (tracker != null) {
                    tracker.accept(pos); // 客户端侧顺手登记（K 键切换时据此逐格重建）
                }
                refresh(level, pos);
            }
        });
    }

    /**
     * 让这一格所在的区段重烘一次（服务端上执行时是无副作用的空操作，且本包只会在客户端被处理）。
     *
     * <p><b>为什么是 public</b>：「让一格重烘」只留这一份实现 —— 收包当场（本类）与
     * K 键切换（{@code client/CamouflageShellDisplay#refreshTracked}，它按同样的两步做，
     * 但额外按区段去重）都走同一套口径，不会漂移。</p>
     *
     * <p><b>为什么方块实体不在时也照样标脏</b>：外壳的几何在<b>方块自己被烘焙</b>时产出，
     * 而「这一段脏了」与「这一格有没有方块实体」无关；反过来说，若在数据还没到位时因为
     * 方块实体缺席而提前返回，就正好放跑了「随后补烘」这条兜底路径。</p>
     */
    public static void refresh(final BlockGetter level, final BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            // 与 Create 伪装板的 redraw() 同一条链路：清掉缓存的模型数据
            be.requestModelDataUpdate();
        }
        if (level instanceof final Level realLevel) {
            // 把这一格周围 3×3×3 个区段标脏（下一次重建就会重新取数、重新烘外壳）
            final BlockState state = realLevel.getBlockState(pos);
            realLevel.sendBlockUpdated(pos, state, state, 16);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
