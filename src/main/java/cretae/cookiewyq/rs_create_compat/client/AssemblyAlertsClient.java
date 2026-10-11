package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.client.screen.StepMachineSelectScreen;
import cretae.cookiewyq.rs_create_compat.network.AssemblyStepMachinePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket;
import com.refinedmods.refinedstorage.common.autocrafting.monitor.AutocraftingMonitorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧「序列装配任务告警」缓存 + 处置按钮的<b>唯一状态源</b>。
 *
 * <p>服务端在内容变化时广播 {@link SyncAssemblyAlertsPacket}，并在登录 / 换维度 / 监视器界面
 * 打开（{@code RequestAssemblyAlertsPacket}）时补发；本类持有最近一份快照，
 * 供自动合成监视器界面（{@code mixin/client/AutocraftingMonitorScreenMixin}）决定
 * 「继续 / 更换机器」按钮的可见性与可用性。</p>
 *
 * <p><b>为什么必须是「一份」状态源</b>（本轮修法的核心）：按钮「该不该出现」只能由
 * <b>服务端</b>说了算 —— 快照里每条告警带一个动作位集（{@link SyncAssemblyAlertsPacket.Alert#offers}），
 * 客户端只做「有这一位 → 渲染这个按钮」的映射。界面不再按 {@code reason / offlineSteps} 自己推断，
 * 于是「点击后按钮集合只随服务端状态变化」，也不会因为服务端重新分类而单独抹掉某个按钮。</p>
 *
 * <p><b>第 63 轮补的那一半：没有快照 ⇒ 默认给「挂起」</b>（见 {@link #actionView}）。
 * 「只信服务端」与「任何任务都必须有按钮」曾经互相冲突：服务端手里没有这条任务的记录时
 * （流体任务、记录还没建出来的那一瞬、跨维度），快照里就没有这一行，三个动作位全假 ⇒
 * 整排按钮一个都不画。现在把「没有快照」这一种情况单独定义成<b>默认位</b>而不是「没有位」，
 * 并由服务端就地补扫描把记录建出来 —— 两条合起来才是完整的不变量：
 * <b>界面显示了这条任务 ⇒ 一定画得出一颗按钮；画出来的按钮 ⇒ 服务端一定处理得了</b>。</p>
 *
 * <p><b>在途动作（pending）</b>：一次点击 = 一次发包。点击当刻把该 (任务, 动作) 记为在途，
 * 按钮<b>立刻</b>变成不可点（即时反馈，不必等服务端一个扫描周期），这段时间内的重复点击不再发包；
 * 新的服务端快照到达（或 2 秒超时）后自动解锁。在途状态<b>只影响可用性、绝不影响可见性</b> ——
 * 所以「点一下按钮就没了」在结构上不可能发生。</p>
 */
public final class AssemblyAlertsClient {
    /**
     * 在途动作的超时（纳秒）：到点自动解锁。
     *
     * <p>为什么必须有超时：服务端只在<b>内容变化</b>时广播快照，万一这次点击恰好不改变任何内容
     * （例如服务端记录已经不存在），就没有新快照来解锁 —— 没有超时的话按钮会永久变灰。</p>
     */
    private static final long PENDING_TIMEOUT_NANOS = 2_000_000_000L;

    private static volatile List<SyncAssemblyAlertsPacket.Alert> alerts = List.of();

    /** (任务 uuid # 动作位) → 点击时刻（{@link System#nanoTime()}）。 */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();

    private AssemblyAlertsClient() {
    }

    /** 覆盖缓存（S2C 处理器调用；主线程执行）。新快照 = 新的服务端权威状态 → 清掉在途标记。 */
    public static void setAlerts(final List<SyncAssemblyAlertsPacket.Alert> received) {
        alerts = received == null ? List.of() : List.copyOf(received);
        PENDING.clear();
    }

    /** 最近一份告警快照（永不为 null）。 */
    public static List<SyncAssemblyAlertsPacket.Alert> alerts() {
        return alerts;
    }

    /** 某条任务的告警（快照里没有这条任务则为 null）。 */
    @Nullable
    public static SyncAssemblyAlertsPacket.Alert alertOf(@Nullable final UUID taskId) {
        if (taskId == null) {
            return null;
        }
        for (final SyncAssemblyAlertsPacket.Alert alert : alerts) {
            if (alert.taskId().equals(taskId)) {
                return alert;
            }
        }
        return null;
    }

    /**
     * <b>按钮判定专用的告警视图</b>（第 63 轮新增）：快照里有这条任务就给真快照；
     * <b>没有就给一份「只带挂起位」的默认告警</b>（{@code alertOf} 仍然诚实返回 null）。
     *
     * <h2>为什么这份默认值必须存在（用户硬要求）</h2>
     * <p>「不管什么任务都要有这个按钮，没有例外」。而按钮的可见性完全由「这一行告警给不给某个动作位」
     * 决定（{@link #actionView}）—— 服务端<b>还没有为这条任务建记录</b>时它不在快照里
     * （记录只由 1 秒一拍的扫描产生；流体任务的记录在第 63 轮之前更是永远建不出来），
     * 于是三个动作位全假、<b>整排按钮一个都不画</b>。现在把「没有记录」定义成一种<b>状态</b>：
     * 界面既然显示了这条任务（{@code taskId != null}），就默认给「挂起」位。</p>
     *
     * <h2>为什么只兜「挂起」这一位</h2>
     * <ul>
     *     <li>「继续」的前提是「它已经被我们挂起」—— 没有记录就不可能被挂起，兜它等于给一颗必然失败的按钮；</li>
     *     <li>「更换机器」的前提是「知道是哪一步的执行仓掉线」（那是记录里的只读快照），同理。</li>
     * </ul>
     *
     * <h2>服务端那一半</h2>
     * <p>客户端画得出 ⇒ 服务端必须处理得了：{@code AssemblyWatchdog#suspend} 在查不到记录时会
     * <b>就地补一拍全维度扫描</b>把记录建出来再执行（{@code #rescanNow}），
     * 因此这一位不是「画着好看」的假按钮。反过来，{@code alertOf} 仍然只回答「服务端给没给」——
     * 界面据此决定要不要补拉一次快照（{@code rscc$requestAlertIfMissing}），
     * 于是真快照一到，这一行立刻换成服务端权威的动作位（例如「继续」）。</p>
     *
     * <p>零副作用、零状态：默认告警只在本方法里现造，不进缓存、不改任何服务端可见状态
     * （{@code reason} 取 {@code REASON_NONE} ⇒ 界面也不会因此画出「已挂起」标记）。</p>
     */
    @Nullable
    public static SyncAssemblyAlertsPacket.Alert alertOrDefault(@Nullable final UUID taskId) {
        final SyncAssemblyAlertsPacket.Alert received = alertOf(taskId);
        if (received != null || taskId == null) {
            return received;
        }
        return new SyncAssemblyAlertsPacket.Alert(taskId, SyncAssemblyAlertsPacket.REASON_NONE,
            SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND, "", net.minecraft.world.item.ItemStack.EMPTY,
            0L, List.of(), List.of(), null);
    }

    /** 读取某条任务告警上的第 1 个掉线步骤下标（没有则 -1）。 */
    public static int firstOfflineStep(@Nullable final SyncAssemblyAlertsPacket.Alert alert) {
        if (alert == null || alert.offlineSteps().isEmpty()) {
            return -1;
        }
        return alert.offlineSteps().get(0).stepIndex();
    }

    /**
     * 按钮要用的三态视图（<b>可见性 + 可用性</b>），由「服务端快照 + 本地在途动作」唯一决定。
     *
     * <p><b>它本身不做任何推断</b>：{@code visible} 就是「这一行告警给不给这个动作位」。
     * 第 63 轮新增的「任何任务都有按钮」不是在这里补的 —— 而是在 {@link #alertOrDefault} 里，
     * 把「服务端还没有这条任务的记录」这一种情况折成一份<b>只带挂起位的默认告警</b>。
     * 这样「按钮可见性 = 动作位」这条单一状态源<b>一个字都没改</b>，
     * 变化只发生在「没有记录时那一位是什么」这个唯一的新定义上。</p>
     *
     * @param alert     当前选中任务的告警视图（{@link #alertOrDefault}；null = 没有选中任务）
     * @param taskId    当前选中任务 id
     * @param actionBit 该按钮对应的动作位（{@link SyncAssemblyAlertsPacket#ACTION_BIT_RESUME} 等）
     */
    public static ActionView actionView(@Nullable final SyncAssemblyAlertsPacket.Alert alert,
                                        @Nullable final UUID taskId, final int actionBit) {
        final boolean offered = alert != null && alert.offers(actionBit);
        return new ActionView(offered, offered && !isPending(taskId, actionBit));
    }

    /**
     * 「点击一次即发包」闸门：返回 true 表示本次点击应当发出去。
     *
     * <p>同一个 (任务, 动作) 在 {@link #PENDING_TIMEOUT_NANOS} 内重复点击返回 false ——
     * 玩家的手速不会变成一串重复包（服务端也就不会把同一件事做多遍）。</p>
     */
    public static boolean beginAction(@Nullable final UUID taskId, final int actionBit) {
        if (taskId == null) {
            return false;
        }
        final long now = System.nanoTime();
        final String key = pendingKey(taskId, actionBit);
        final Long previous = PENDING.get(key);
        if (previous != null && now - previous < PENDING_TIMEOUT_NANOS) {
            return false;
        }
        PENDING.put(key, now);
        return true;
    }

    /** 该 (任务, 动作) 是否正在等待服务端答复（只影响按钮可用性）。 */
    public static boolean isPending(@Nullable final UUID taskId, final int actionBit) {
        if (taskId == null) {
            return false;
        }
        final Long at = PENDING.get(pendingKey(taskId, actionBit));
        return at != null && System.nanoTime() - at < PENDING_TIMEOUT_NANOS;
    }

    /** 请求某步的候选机器（服务端回 {@code AssemblyMachineCandidatesPacket}）。 */
    public static void requestMachines(final UUID taskId, final int stepIndex) {
        PacketDistributor.sendToServer(new AssemblyStepMachinePacket(
            taskId, AssemblyStepMachinePacket.ACTION_REQUEST, stepIndex,
            net.minecraft.core.BlockPos.ZERO, ""));
    }

    /**
     * 弹出<b>既有的</b>机器选择子界面（{@link StepMachineSelectScreen}），确认时发「写入指派」包。
     * <p>只在当前界面仍是 RS 自动合成管理器时弹出 —— 玩家已经关掉界面 / 切到别处时不强行切屏。
     * <p>无论是否真的弹出，都先把该任务的在途标记清掉（服务端已经答复过了）。
     */
    public static void openMachineSelect(final UUID taskId, final int stepIndex, final String recipeType,
                                         final String currentMachineName,
                                         final List<SyncChamberListPacket.Entry> candidates) {
        clearPending(taskId);
        final Screen current = Minecraft.getInstance().screen;
        if (current == null) {
            return;
        }
        if (!(current instanceof AutocraftingMonitorScreen)) {
            return;
        }
        Minecraft.getInstance().setScreen(new StepMachineSelectScreen(
            current, stepIndex, recipeType, candidates, currentMachineName,
            selected -> {
                if (selected == null) {
                    return;
                }
                PacketDistributor.sendToServer(new AssemblyStepMachinePacket(
                    taskId, AssemblyStepMachinePacket.ACTION_SET, stepIndex, selected.pos(), selected.name()));
            }));
    }

    /** 清掉某条任务的全部在途动作（服务端已经给出新的权威状态）。 */
    private static void clearPending(final UUID taskId) {
        PENDING.keySet().removeIf(key -> key.startsWith(taskId + "#"));
    }

    /**
     * 在途标记的键。<b>「挂起」与「继续」共用同一个「主处置」键</b>。
     *
     * <p><b>为什么必须共用（用户第 3 条：说了挂起但并没有挂起）</b>：这两颗按钮在监视器里
     * <b>占同一个槽位</b>（服务端同一时刻只给其中一位）。旧实现按 actionBit 分别记在途，
     * 于是「点挂起 → 1 秒后服务端快照把那一格换成继续 → 玩家的第二下点击（以为没生效，再点一次）」
     * 会立刻发出 RESUME，把刚挂起的任务当场解挂 —— 实机日志可见
     * {@code watchdog suspend task=…} 之后 1.3 秒紧跟 {@code watchdog resume task=…}。
     * 归到同一个键后：一次转换落地（收到服务端新快照）之前，另一位也处于在途 ⇒
     * 按钮变灰、点击被吞掉，玩家的「再点一下」不再撤销刚做的动作。</p>
     */
    private static String pendingKey(final UUID taskId, final int actionBit) {
        final int group = actionBit == SyncAssemblyAlertsPacket.ACTION_BIT_RESUME
            || actionBit == SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND
            ? SyncAssemblyAlertsPacket.ACTION_BIT_RESUME : actionBit;
        return taskId + "#" + group;
    }

    /** 一个按钮的三态：{@code visible} = 服务端允许这个动作；{@code enabled} = 允许且不处于在途。 */
    public record ActionView(boolean visible, boolean enabled) {
    }
}
