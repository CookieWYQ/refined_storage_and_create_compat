package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.network.TerminalPowerPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 高级远程多功能终端：<b>绑定网络有没有电</b>的<b>唯一服务端权威</b>（第 100 轮）。
 *
 * <h2>为什么需要它（用户实机反馈）</h2>
 * <p>「网络完全没电了，样板终端还是能点、只是里面物品不可见」—— 模式 Tab 是纯客户端的，
 * 客户端又看不到网络存量（RS 只把网络能量同步给<b>控制器界面 / 便携网格</b>，本终端打开的
 * 网格 / 样板 / 管理器 / 监视器界面<b>没有</b> {@code EnergyInfo}），因此「有没有电」必须由服务端
 * 算好推给客户端，客户端只负责画灰 + 拦点击。</p>
 *
 * <h2>判据（与 RS 原版无线网格 {@code WirelessGrid#isGridActive} 同一口径）</h2>
 * <ul>
 *     <li>{@code bound} = 终端确实连着一张<b>此刻可达</b>的网络：绑定位置存在、所在区块已加载、
 *     该方块是 RS 网络节点、且 {@code getNetworkForItem()} 非 null。任何一步失败都按
 *     <b>没有网络 = 没电</b>处理（{@code bound=false}）—— 与打开界面时「绑空网络、显示灰色空状态」
 *     完全同一套解析（{@link AdvancedRemoteTerminalItem#resolveBoundNetwork} 是唯一实现，
 *     界面打开的绑定路径也走它）；</li>
 *     <li><b>没电</b> = {@code stored <= 0}（<b>不是</b> {@code capacity == 0}：一台没装能量立方的
 *     网络 capacity 也是 0，但那是「没有供电单元」，同样按没电处理，判据落在 stored 上更直观）；</li>
 *     <li>服务端配置 <b>不需要能量</b>（{@link RefinedStorageApi#isEnergyRequired()} 为 false）时，
 *     只要 {@code bound} 就算有电 —— 否则「关掉耗能」的服务器上所有终端会永远变灰（回归）。</li>
 * </ul>
 * <p><b>绝不</b>把「终端物品<b>自身</b>的电量」混进来：那是 RS 原版的另一条逻辑（物品没电时
 * 界面内操作禁用），用户明确要求「终端本身有电也不行」。本类只读绑定网络的
 * {@link EnergyNetworkComponent}，不读物品能量组件。</p>
 *
 * <h2>下发（S2C 心跳）</h2>
 * <p>{@link #start} 在<b>打开模式界面之后</b>立刻发一条 {@link TerminalPowerPacket}（客户端拿到
 * 首个快照就能变灰，不用等心跳），之后每 {@link #INTERVAL_TICKS} tick 一条；玩家关掉界面
 * （{@code containerMenu == inventoryMenu}）或终端物品没了就<b>立刻停</b>并发一条收条包之外
 * 什么都不做（客户端有 5 秒过期兜底，见 {@code client/TerminalPowerState}）。
 * 心跳只为「界面开着时网络掉电 / 恢复」服务，成本 = 每玩家每秒一次方块实体查找，可忽略。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class TerminalNetworkPower {
    /** 心跳间隔（tick）：20 tick = 1 秒，与任务口径一致。 */
    public static final int INTERVAL_TICKS = 20;

    /**
     * 「绑定的网络没有电」提示（Tab 悬停 tooltip 与服务端拒绝切换时的 actionbar 共用<b>同一句</b>）。
     * <p>两个 {@code %s} = 存量 / 容量（FE）。语言键由 Lead 落 zh/en，本类只引用键名。</p>
     */
    public static final String LANG_NO_POWER =
        "item.rs_create_compat.advanced_remote_terminal.network_no_power";

    /** 每玩家一条「这一局界面开着」的观察记录（只在服务端主线程读写；下线 / 关界面即移除）。 */
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();

    private TerminalNetworkPower() {
    }

    /** 一条观察记录：怎么再找到那台终端 + 上次下发心跳的 gameTime。 */
    private static final class Watch {
        private final SlotReference slotReference;
        private final int mode;
        private long lastSentTick = Long.MIN_VALUE / 2L;

        Watch(final SlotReference slotReference, final int mode) {
            this.slotReference = slotReference;
            this.mode = mode;
        }
    }

    // ==================== 快照（唯一判据） ====================

    /**
     * 生成一份权威快照：{@code stored/capacity} 来自绑定网络的 {@link EnergyNetworkComponent}，
     * {@code powered} 是本模组「能不能切模式」的<b>唯一</b>判据（见类注释）。
     *
     * <p>读不到网络 / 网络组件缺失（未成形）⇒ {@code stored=0, capacity=0, bound=false}。</p>
     */
    public static TerminalPowerPacket snapshot(final ServerPlayer player, final ItemStack stack) {
        final Optional<Network> network = AdvancedRemoteTerminalItem.resolveBoundNetwork(player, stack);
        if (network.isEmpty()) {
            return new TerminalPowerPacket(0L, 0L, false, false);
        }
        final EnergyNetworkComponent energy =
            network.get().getComponent(EnergyNetworkComponent.class);
        if (energy == null) {
            // 网络存在但没有能量组件（未成形 / 异常网络实现）⇒ 按没电处理，绝不抛异常
            return new TerminalPowerPacket(0L, 0L, true, false);
        }
        final long stored = Math.max(0L, energy.getStored());
        final long capacity = Math.max(0L, energy.getCapacity());
        final boolean powered = stored > 0L || !RefinedStorageApi.INSTANCE.isEnergyRequired();
        return new TerminalPowerPacket(stored, capacity, true, powered);
    }

    /** 服务端权威判定：这个终端此刻<b>能不能切模式</b>（绑定网络有电）。 */
    public static boolean isPowered(final ServerPlayer player, final ItemStack stack) {
        return snapshot(player, stack).powered();
    }

    /** 「没电」提示文本（tooltip 与 actionbar 共用）；{@code stored/capacity} 为 FE 原文。 */
    public static Component noPowerText(final long stored, final long capacity) {
        return Component.translatable(LANG_NO_POWER, stored, capacity);
    }

    // ==================== 心跳 ====================

    /**
     * 开始观察「这台终端打开的界面」：立刻下发一条快照，之后每 {@link #INTERVAL_TICKS} tick 一条。
     * <p>由 {@link AdvancedRemoteTerminalItem#openModeScreen} 在<b>界面真的开出来之后</b>调用
     * （打开失败 / 抛异常时不调用）。同一玩家重复调用只保留最新一条（切模式重开界面即此情形）。</p>
     */
    public static void start(final ServerPlayer player, final SlotReference slotReference) {
        if (player == null || slotReference == null || player.connection == null) {
            return;
        }
        final ItemStack stack = slotReference.resolve(player).orElse(ItemStack.EMPTY);
        if (!(stack.getItem() instanceof AdvancedRemoteTerminalItem)) {
            return;
        }
        WATCHES.put(player.getUUID(), new Watch(slotReference, AdvancedRemoteTerminalItem.getMode(stack)));
        send(player, snapshot(player, stack));
    }

    /** 停止观察（下线 / 关界面 / 终端没了）。重复调用是空操作。 */
    public static void stop(final ServerPlayer player) {
        if (player != null) {
            WATCHES.remove(player.getUUID());
        }
    }

    private static void send(final ServerPlayer player, final TerminalPowerPacket packet) {
        if (player.connection == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, packet);
    }

    @SubscribeEvent
    public static void onPlayerTick(final PlayerTickEvent.Post event) {
        // PlayerTickEvent 双端都触发：客户端没有「服务端权威」，整类逻辑只认 ServerPlayer。
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final Watch watch = WATCHES.get(player.getUUID());
        if (watch == null) {
            return;
        }
        if (player.connection == null) {
            WATCHES.remove(player.getUUID());
            return;
        }
        // 关界面 ⇒ 立刻停：没有任何「界面关闭」事件可以订阅（RS 菜单不是我们的），
        // 但玩家还开着容器界面时 containerMenu != inventoryMenu；回到背包界就是关了。
        if (player.containerMenu == player.inventoryMenu) {
            WATCHES.remove(player.getUUID());
            return;
        }
        final ItemStack stack = watch.slotReference.resolve(player).orElse(ItemStack.EMPTY);
        if (!(stack.getItem() instanceof AdvancedRemoteTerminalItem)
            || AdvancedRemoteTerminalItem.getMode(stack) != watch.mode) {
            // 终端被搬走 / 被销毁 / 被别的链路改了模式：这条观察已经没有意义
            WATCHES.remove(player.getUUID());
            return;
        }
        final long gameTime = player.serverLevel().getGameTime();
        if (gameTime - watch.lastSentTick < INTERVAL_TICKS) {
            return;
        }
        watch.lastSentTick = gameTime;
        send(player, snapshot(player, stack));
    }

    /** 下线清理：不让观察表跨存档 / 跨连接存活。 */
    @SubscribeEvent
    public static void onLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            WATCHES.remove(player.getUUID());
        }
    }
}
