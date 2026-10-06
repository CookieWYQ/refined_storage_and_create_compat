package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * C2S：在自动合成监视器里对一条自动合成任务做处置（原版 RS 任务与序列装配任务共用）。
 *
 * <p>动作：{@code 0 = 继续}（清除挂起状态并让检测器重新计时）、
 * {@code 1 = 挂起}（玩家主动让出一条正在跑的任务，让出执行器给别的任务先做）。</p>
 *
 * <p><b>为什么两个动作共用一个包</b>：它们作用在同一个对象（当前选中的任务）上、走同一条服务端校验
 * 路径、成功时都不播报、失败时都只提示一次 —— 分成两个包只会让「同一件事的第二条入口」多一处。</p>
 *
 * <p><b>为什么这里没有「取消」</b>：RS 监视器原生就有「取消 / 取消全部」按钮，
 * 它们走 {@code C2SPackets.sendAutocraftingMonitorCancel} → {@code AutocraftingMonitor#cancel}
 * 这条既有路径。我们早期在界面上另外加过一个取消按钮，等于给同一件事开了<b>第二条入口</b>，
 * 已于本轮删除（连同这个动作常量与服务端方法），保证全模组<b>只有一条取消路径</b>。</p>
 *
 * <p><b>服务端权威</b>：只有该任务确实仍在服务端记录里、且当前状态允许该动作
 * （挂起要求 RUNNING；继续要求已挂起）才会动作；否则安全失败并回一条提示，绝不误伤别的任务。</p>
 *
 * <p><b>必须在服务端主线程上执行</b>：{@code handle} 会读改 {@code AssemblyWatchdog} 的记录表
 * （服务端主线程每 tick 都在写它）并调 {@code ServerLevel} / {@code Player} 的主线程方法。注册处
 * （{@code RS_Create_Compat.registerPayloads}）用 {@code ctx.enqueueWork} 把它投递到主线程 ——
 * 漏掉这一步时处理器跑在网络线程上，会偶发读不到刚写的状态或抛异常丢包，玩家看到的就是
 * 「点了没反应 / 点几下才反应」。</p>
 */
public record AssemblyTaskActionPacket(UUID taskId, int action) implements CustomPacketPayload {
    /** 继续：清挂起 + 重新计时。 */
    public static final int ACTION_RESUME = 0;
    /**
     * 挂起：玩家主动把当前任务挂起（不再占用执行器 / 不阻塞后续任务，中间件原地冻结）。
     *
     * <p>与自动挂起共用 {@code AssemblyWatchdog} 里唯一那处挂起实现，只是原因标记为「玩家手动挂起」。</p>
     */
    public static final int ACTION_SUSPEND = 1;

    public static final Type<AssemblyTaskActionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "assembly_task_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyTaskActionPacket> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, AssemblyTaskActionPacket::taskId,
            ByteBufCodecs.VAR_INT, AssemblyTaskActionPacket::action,
            AssemblyTaskActionPacket::new
        );

    public static void handle(final AssemblyTaskActionPacket packet, final ServerPlayer player) {
        // <b>成功时刻意不播报</b>（用户明确要求删掉：「我点击继续、检测器重新计时，这些话语不要有」）
        // —— 挂起 / 挂起标记本身会随服务端快照一起变化，那才是「它已经按你要的做了」的唯一权威表现，
        // 再在动作栏复述一遍纯属噪声。
        // <b>失败仍提示一次</b>：这是一条「为什么点了没反应」的必要解释（任务已不存在 / 已不在运行 /
        // 已挂起 / 执行器不可达），不是过程性播报，删掉会让玩家彻底无从判断。
        if (packet.action() == ACTION_RESUME) {
            if (!AssemblyWatchdog.resume(player, packet.taskId())) {
                fail(player, "message.rs_create_compat.assembly.action_failed");
            }
            return;
        }
        if (packet.action() == ACTION_SUSPEND
            && !AssemblyWatchdog.suspend(player, packet.taskId())) {
            fail(player, "message.rs_create_compat.assembly.suspend_failed");
        }
    }

    /** 极简失败提示：动作栏一行，且每个动作最多提示一次（只有真的失败才走到这里）。 */
    private static void fail(final ServerPlayer player, final String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
