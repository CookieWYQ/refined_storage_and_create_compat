package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C：「序列装配任务告警」整份快照（服务端权威 → 自动合成管理器界面）。
 *
 * <p>只在内容变化时广播给「正开着自动合成管理器」的玩家；玩家登录 / 换维度时补发一次。
 * 客户端把快照缓存起来（见 {@code client/AssemblyAlertsClient}），管理器界面据此决定
 * 「继续 / 更换机器」两个按钮是否渲染、是否可点，并在面板标题行右侧画一行挂起原因。</p>
 *
 * <p>{@code reason} 用 {@code AssemblyWatchdog.Reason} 的序号（0 无 / 1 缺料 / 2 掉线），
 * 这里刻意<b>不</b>引用 support 侧的枚举类型，保持网络层只依赖纯数据。</p>
 *
 * <p><b>为什么每条告警还要带 {@code actions} 位集</b>：按钮「该不该出现」是<b>服务端状态</b>，
 * 不是客户端可以自行推断的展示细节（曾让客户端按 {@code reason + offlineSteps} 自己算，
 * 结果服务端一旦重新分类，按钮集合就会在客户端单独变化、看起来像「按钮莫名消失」）。
 * 现在服务端在 {@code AssemblyWatchdog.Record#snapshot()} 里<b>一次性判定</b>这条任务此刻允许
 * 客户端点哪些动作，客户端只做「有这一位 → 渲染这个按钮」的映射 —— 一份状态源，零推断。</p>
 */
public record SyncAssemblyAlertsPacket(List<Alert> alerts) implements CustomPacketPayload {
    /** 原因序号（与 {@code AssemblyWatchdog.Reason} 的声明顺序一致）。 */
    public static final int REASON_NONE = 0;
    /** 原料缺少至无法继续。 */
    public static final int REASON_MISSING_MATERIAL = 1;
    /** 步骤执行器（序列执行仓）/ 机器掉线、被拆、未加载或拒绝接收。 */
    public static final int REASON_EXECUTOR_OFFLINE = 2;
    /** 长时间没有任何进展（兜底判据：既没在抽料、也没在加工、也没在投料）。 */
    public static final int REASON_NO_PROGRESS = 3;
    /**
     * 玩家手动挂起（本轮新增，<b>追加在末尾</b>，不动上面几个既有序号）。
     *
     * <p>与自动挂起的三种原因<b>语义完全一致</b>（不再占用执行器 / 不阻塞后续任务 / 中间件原地冻结），
     * 唯一的差别是「谁挂起的」—— 手动挂起时监视器上的标记会显示「已挂起：玩家手动挂起」，
     * 而不是「执行器离线 / 缺少原料 / 长时间无进展」。</p>
     */
    public static final int REASON_MANUAL = 4;
    /**
     * <b>下游输出被阻塞</b>（2026-10-05 追加在末尾，不动上面几个既有序号）。
     *
     * <p>机器<b>在线</b>，但某台执行仓持续把料推不出去（目的地机器 / 置物台拒收、已满、不接受这一件）。
     * 与 {@link #REASON_EXECUTOR_OFFLINE} 分开的理由：旧实现把两者压成同一个 reason，于是横幅一律说
     * 「执行器掉线」—— 实机取证时玩家被告知掉线，而机械手与置物台是空的、设备一台没少，
     * 玩家于是按错误方向去拆机器 / 换样板（「我讲错你就修错」的典型来源）。</p>
     */
    public static final int REASON_OUTPUT_BLOCKED = 5;

    /** 动作位集：什么都没提供（纯展示，界面不渲染任何按钮）。 */
    public static final int ACTION_BIT_NONE = 0;
    /** 「继续」：清除挂起并让检测器重新计时（服务端权威）。 */
    public static final int ACTION_BIT_RESUME = 1;
    /** 「更换机器」：为掉线的那一步改派执行仓（只有能定位到掉线步骤时才提供）。 */
    public static final int ACTION_BIT_CHANGE_MACHINE = 2;
    /**
     * 「挂起」：玩家主动把一条<b>正在跑</b>的任务让出来（本轮新增）。
     *
     * <p>与「继续」<b>互斥</b>：服务端在「该任务未被挂起且仍可被 RS 推进」时给这一位，
     * 挂起后改给 {@link #ACTION_BIT_RESUME} —— 因此界面同一时刻只会渲染其中一个，不增加横向压力。</p>
     */
    public static final int ACTION_BIT_SUSPEND = 4;

    /** 一条挂起任务（只读快照）。 */
    public record Alert(UUID taskId, int reason, int actions, String productName, ItemStack productIcon,
                        long amount, List<Material> materials, List<OfflineStep> offlineSteps) {
        public Alert {
            productIcon = productIcon == null ? ItemStack.EMPTY : productIcon;
            materials = List.copyOf(materials);
            offlineSteps = List.copyOf(offlineSteps);
        }

        /** 这条告警此刻是否允许客户端执行该动作（唯一的按钮可见性判据）。 */
        public boolean offers(final int actionBit) {
            return (actions & actionBit) != 0;
        }
    }

    /** 缺少的原料 / 输入时原料（图标 + 名字）。 */
    public record Material(ItemStack icon, String name) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Material> STREAM_CODEC =
            StreamCodec.composite(
                ItemStack.OPTIONAL_STREAM_CODEC, Material::icon,
                ByteBufCodecs.STRING_UTF8, Material::name,
                Material::new
            );
    }

    /** 掉线的步骤执行器（步骤下标 + 配方名 + 执行器名 + 坐标）。 */
    public record OfflineStep(int stepIndex, String recipeName, String machineName, BlockPos machinePos) {
        public static final StreamCodec<RegistryFriendlyByteBuf, OfflineStep> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OfflineStep::stepIndex,
                ByteBufCodecs.STRING_UTF8, OfflineStep::recipeName,
                ByteBufCodecs.STRING_UTF8, OfflineStep::machineName,
                BlockPos.STREAM_CODEC, OfflineStep::machinePos,
                OfflineStep::new
            );
    }

    public static final Type<SyncAssemblyAlertsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_assembly_alerts"));

    /** 字段数超过 {@code StreamCodec.composite} 的重载上限，故手写（与 CompletionBannerPayload 同一写法）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAssemblyAlertsPacket> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public void encode(final RegistryFriendlyByteBuf buf, final SyncAssemblyAlertsPacket payload) {
                buf.writeVarInt(payload.alerts().size());
                for (final Alert alert : payload.alerts()) {
                    UUIDUtil.STREAM_CODEC.encode(buf, alert.taskId());
                    buf.writeVarInt(alert.reason());
                    buf.writeVarInt(alert.actions());
                    buf.writeUtf(alert.productName());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, alert.productIcon());
                    buf.writeVarLong(alert.amount());
                    buf.writeVarInt(alert.materials().size());
                    for (final Material material : alert.materials()) {
                        Material.STREAM_CODEC.encode(buf, material);
                    }
                    buf.writeVarInt(alert.offlineSteps().size());
                    for (final OfflineStep step : alert.offlineSteps()) {
                        OfflineStep.STREAM_CODEC.encode(buf, step);
                    }
                }
            }

            @Override
            public SyncAssemblyAlertsPacket decode(final RegistryFriendlyByteBuf buf) {
                final int count = buf.readVarInt();
                final List<Alert> alerts = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    final UUID taskId = UUIDUtil.STREAM_CODEC.decode(buf);
                    final int reason = buf.readVarInt();
                    final int actions = buf.readVarInt();
                    final String productName = buf.readUtf();
                    final ItemStack productIcon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                    final long amount = buf.readVarLong();
                    final int materialCount = buf.readVarInt();
                    final List<Material> materials = new ArrayList<>(materialCount);
                    for (int m = 0; m < materialCount; m++) {
                        materials.add(Material.STREAM_CODEC.decode(buf));
                    }
                    final int stepCount = buf.readVarInt();
                    final List<OfflineStep> steps = new ArrayList<>(stepCount);
                    for (int s = 0; s < stepCount; s++) {
                        steps.add(OfflineStep.STREAM_CODEC.decode(buf));
                    }
                    alerts.add(new Alert(taskId, reason, actions, productName, productIcon, amount,
                        materials, steps));
                }
                return new SyncAssemblyAlertsPacket(alerts);
            }
        };

    /** 客户端：缓存快照（由 AssemblyAlertsClient 持有；管理器界面读它的当前选中任务）。 */
    public static void handle(final SyncAssemblyAlertsPacket packet,
                             final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> cretae.cookiewyq.rs_create_compat.client.AssemblyAlertsClient.setAlerts(
            packet.alerts()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
