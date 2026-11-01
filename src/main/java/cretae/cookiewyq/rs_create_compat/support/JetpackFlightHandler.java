package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem;
import cretae.cookiewyq.rs_create_compat.network.JetpackStatusPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 电动飞行背包的<b>服务端权威飞行状态机</b>（每 tick 一次）＋ HUD 快照下发。
 *
 * <h2>为什么必须服务端权威</h2>
 * <p>创造式飞行靠的是 {@code player.getAbilities().mayfly = true}。这条权限<b>会随玩家存档落盘</b>
 * （{@code Abilities#addSaveData}），所以只要「授予了却没在正确时机收回」，玩家就会拿到
 * <b>永久免费飞行</b>（下线再上线还在飞）。本类的全部复杂度都花在「什么时候必须收回」上。</p>
 *
 * <h2>授予条件（三个必须同时满足）</h2>
 * <ol>
 *     <li>玩家装备着<b>生效位置</b>上的背包（胸甲槽 / Curios {@code back} / Curios {@code chest}，
 *     见 {@link JetpackEquipped}）；</li>
 *     <li>电量足够付一次 tick 成本（{@code energy >= flightCost}）；</li>
 *     <li>玩家<b>不是</b>创造 / 旁观模式。</li>
 * </ol>
 *
 * <h2>收回路径（5 种情形，缺一条就是免费飞行 bug）</h2>
 * <ol>
 *     <li><b>卸下</b>：每 tick 重新判定，下一 tick 就把 {@code mayfly/flying} 收回（≤ 1 tick）；</li>
 *     <li><b>电量耗尽</b>：飞行中扣不动一次成本 ⇒ 立刻收回（玩家当场坠落）＋ 发一次「电量耗尽」title；</li>
 *     <li><b>换维度</b>：{@link PlayerEvent.PlayerChangedDimensionEvent} 先收再判，新维度下一 tick 重新授予；</li>
 *     <li><b>下线</b>：{@link PlayerEvent.PlayerLoggedOutEvent} 收回并清标记（避免把 mayfly=true 存进玩家数据）；</li>
 *     <li><b>死亡</b>：{@link LivingDeathEvent} 收回（否则复活时 {@code restoreFrom} 会把 mayfly 复制给新身体）。</li>
 * </ol>
 * <p>另外两条兜底：
 * <b>登录幂等撤销</b>（{@link PlayerEvent.PlayerLoggedInEvent}）—— 玩家持久数据里若仍有
 * {@link #TAG_GRANTED} 标记，说明「这条 mayfly 是我们上次发的」，先收再判（老存档 / 崩溃 / 强退都能自救）；
 * 以及<b>绝不覆盖创造 / 旁观</b>—— 这两类玩家整段跳过，连 {@code mayfly} 都不碰（他们的飞行权归
 * 游戏模式自己管；本类只清掉自己的标记）。</p>
 *
 * <h2>收回只收「我们发的」</h2>
 * <p>授予时在 {@code player.getPersistentData()} 里记 {@link #TAG_GRANTED}，收回时只撤带这个标记的权限。
 * 这样别的模组 / 命令授予的 {@code mayfly} 不会被我们误杀；而「我们发的」这份标记跟着玩家存档走，
 * 所以跨存档也能认出来（这是登录兜底能生效的前提）。</p>
 *
 * <h2>只对 ServerPlayer 生效</h2>
 * <p>{@link PlayerTickEvent.Post} 在<b>双端都会触发</b>；本类只处理 {@link ServerPlayer}
 * （客户端绝不能写 {@code Abilities} —— 既无效也会与服务端打架）。</p>
 *
 * <h2>耗电口径</h2>
 * <p>只在 {@code abilities.flying == true} 时扣 {@code Config.electricJetpackFlightCostPerTick}
 * （默认 8 FE/tick，第 108 轮用户口径「耗电量降到 8 FE/tick，这个值可以配置」；
 * 配置键 {@code electricJetpackFlightCostPerTick}，范围 1..Integer.MAX_VALUE）；<b>上升 / 悬停 / 下降同一口径</b>（只看 flying 标志，不看垂直速度）——
 * 简单、可预期、玩家能用「关掉飞行就停止耗电」来控制消耗。只从生效背包自身扣，
 * 不从背包里其它 FE 物品扣（避免「为什么我身上的电容被抽干了」的歧义）。</p>
 *
 * <h2>HUD 快照</h2>
 * <p>每 {@link #STATUS_INTERVAL_TICKS} tick 给「已装备」的玩家发一条 {@link JetpackStatusPacket}；
 * 装备状态 true→false 时立即补一条 {@code equipped=false} 让客户端收条。
 * 其中 {@code charging} 的判据是<b>本 tick 开始时背包能量比上一 tick 结束时更大</b>
 * （真的发生了能量转移，不是距离判定；先采样再扣电，所以「一边充一边飞」不会被自己的耗电抵消成假阴性）；
 * {@code inRange} 是<b>几何判定</b>（{@link JetpackChargerRange}）。两者是两件事，客户端分开显示。</p>
 *
 * <h2>低电 / 耗尽 title 的触发条件</h2>
 * <ul>
 *     <li><b>低电</b>（{@link #LANG_LOW_POWER}）：<b>只在该玩家此刻真的在飞</b>
 *     （{@code abilities.flying == true}）时提示；没在飞时即使电量低于阈值也<b>一声不吭</b>
 *     （用户第 105 轮明确口径）。飞行中跨阈值那一次立即提示，持续低电每
 *     {@link #NOTICE_THROTTLE_TICKS} tick 至多一条；低电时落地不会把「已提示」状态写死，
 *     下次真的起飞若仍低电会立即补一条。</li>
 *     <li><b>低电阈值的口径（第 109 轮用户实机反馈后改）</b>：不再是「剩余百分比」，而是
 *     <b>「剩余还能飞多少分钟」</b>——阈值电量 = {@code electricJetpackLowPowerMinutes}（默认 30）×
 *     1200 tick/min × {@code electricJetpackFlightCostPerTick}；<b>严格小于</b>才提示
 *     （剩余正好 30 分钟不提示）。原因：容量 5000 万 FE 时旧的 10% = 500 万 FE，
 *     充到 200 万（4%）明明还能飞 208 分钟，却一直被判「电量不足」。
 *     副标题会写出精确的「剩余可飞约 N 分钟」（与 HUD / tooltip 同一个函数）。</li>
 *     <li><b>耗尽</b>（{@link #LANG_DEPLETED}）：只在「本次真的因为电量不足以支付一 tick
 *     成本而被收回飞行权限、且收回前确实在飞」时提示（见 {@code wasFlying}）；同样 30 秒节流。</li>
 * </ul>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class JetpackFlightHandler {
    /** 「这条 mayfly 是我们发的」标记（写在玩家持久数据里，随存档走，用于跨存档识别）。 */
    public static final String TAG_GRANTED = "RsccJetpackGranted";

    /** HUD 快照心跳间隔（tick）：5 tick = 250ms，客户端开条无明显延迟，开销可忽略。 */
    public static final int STATUS_INTERVAL_TICKS = 5;
    /** 低电 / 耗尽提示的最慢重复间隔（tick）：600 tick = 30 秒（不刷屏）。 */
    public static final int NOTICE_THROTTLE_TICKS = 600;

    private static final int TITLE_FADE_IN_TICKS = 5;
    private static final int TITLE_STAY_TICKS = 40;
    private static final int TITLE_FADE_OUT_TICKS = 10;

    /** 语言键（zh/en 由 Lead 落；本类只引用键名）。 */
    public static final String LANG_LOW_POWER = "item.rs_create_compat.electric_jetpack.low_power.title";
    /** 第 109 轮：低电 title 的副标题（把「还能飞几分钟」直接写出来，玩家不用自己算）。 */
    public static final String LANG_LOW_POWER_SUBTITLE =
        "item.rs_create_compat.electric_jetpack.low_power.subtitle";
    public static final String LANG_DEPLETED = "item.rs_create_compat.electric_jetpack.depleted.title";

    /** 每玩家状态（只在服务端主线程读写；下线 / 死亡时移除，不会无限增长）。 */
    private static final Map<UUID, PlayerState> STATES = new HashMap<>();

    private JetpackFlightHandler() {
    }

    /** 单个玩家的服务端状态：跨 tick 需要记住的最小集合。 */
    private static final class PlayerState {
        /** 上一次 tick 的生效背包（按<b>实例</b>比较：换了一件就重置充电判定，避免「换上新满电背包」被当成在充电）。 */
        private ItemStack pack = ItemStack.EMPTY;
        /** 上一次 tick 结束时采样到的电量（用于「能量变大 ⇒ 正在被充能」判据）。 */
        private int previousEnergy;
        /** 是否已经就「低电」提示过（跨阈值只提示一次；离开低电后重置）。 */
        private boolean lowPowerNotified;
        /** 上一次发低电 title 的 gameTime。 */
        private long lastLowPowerNoticeTick = Long.MIN_VALUE / 2L;
        /** 上一次发电量耗尽 title 的 gameTime。 */
        private long lastDepletedNoticeTick = Long.MIN_VALUE / 2L;
        /** 上一次发 HUD 快照的 gameTime。 */
        private long lastStatusTick = Long.MIN_VALUE / 2L;
        /** 上一次快照里 equipped 是不是 true（用于 true→false 时补一条收条包）。 */
        private boolean statusEquipped;
    }

    // ==================== 每 tick ====================

    @SubscribeEvent
    public static void onPlayerTick(final PlayerTickEvent.Post event) {
        // 只认服务端玩家：PlayerTickEvent 双端都会触发，客户端绝不能写 Abilities。
        if (event.getEntity() instanceof ServerPlayer player) {
            tick(player);
        }
    }

    private static void tick(final ServerPlayer player) {
        // 假玩家（Create 的机械臂 / 部署器用的 FakePlayer）与「还没完成登录」的玩家 connection 是 null：
        // 下面会通过 PacketDistributor / connection.send 发包，那两处都不做 null 检查 ⇒ 会 NPE。
        // 他们本来也不可能真的起飞，因此整段跳过（既不发包也不管理能力）。
        if (player.connection == null) {
            return;
        }
        final long gameTime = player.serverLevel().getGameTime();
        final PlayerState state = STATES.computeIfAbsent(player.getUUID(), key -> new PlayerState());
        final int cost = ElectricJetpackItem.flightCost();
        final int capacity = ElectricJetpackItem.capacity();
        final boolean privileged = player.isCreative() || player.isSpectator();
        final CompoundTag persistent = player.getPersistentData();

        // ① 选出生效背包（三处位置，见 JetpackEquipped）；没有就是空栈
        final ItemStack pack = JetpackEquipped.active(player, cost);
        final boolean equipped = !pack.isEmpty();
        // ② 先采样「本 tick 开始时的电量」（充电判据要用它，不能被本 tick 的耗电抵消）
        final int energyBefore = equipped ? ElectricJetpackItem.getEnergy(pack) : 0;
        final boolean charging = equipped && state.pack == pack && energyBefore > state.previousEnergy;

        if (privileged) {
            // 创造 / 旁观：整段跳过能力管理。他们的飞行权归游戏模式自己管 ——
            // 我们只把自己的标记清掉（下次回到生存会按「无标记」重新判定，不会带走旧授权）。
            persistent.remove(TAG_GRANTED);
        } else if (equipped && energyBefore >= cost) {
            // ③ 够电：授予（仅在需要时写 + 同步，避免每 tick 发包）
            final Abilities abilities = player.getAbilities();
            if (!abilities.mayfly) {
                abilities.mayfly = true;
                player.onUpdateAbilities();
            }
            // 标记只在「从无到有」时写一次：每 tick 覆盖同一个键会白白产生 NBT 分配。
            if (!persistent.getBoolean(TAG_GRANTED)) {
                persistent.putBoolean(TAG_GRANTED, true);
            }
        } else {
            // ④ 没背 / 没电：只收回「我们发的」那份权限
            final boolean wasFlying = player.getAbilities().flying;
            boolean revokedByUs = false;
            if (persistent.getBoolean(TAG_GRANTED)) {
                revoke(player);
                revokedByUs = true;
            }
            if (revokedByUs && wasFlying && equipped) {
                // 飞行中电量扣不动 ⇒ 当场断飞，并提示一次（30 秒内不重复）
                if (gameTime - state.lastDepletedNoticeTick >= NOTICE_THROTTLE_TICKS) {
                    state.lastDepletedNoticeTick = gameTime;
                    sendTitle(player, LANG_DEPLETED);
                }
            }
        }

        // ⑤ 飞行耗电（只在真的 flying 时扣；上升 / 悬停 / 下降同口径）
        if (!privileged && equipped && player.getAbilities().flying
            && ElectricJetpackItem.getEnergy(pack) >= cost) {
            ElectricJetpackItem.consume(pack, cost);
        }

        // ⑥ 本 tick 结束时的电量（HUD 显示它）
        final int energyAfter = equipped ? ElectricJetpackItem.getEnergy(pack) : 0;

        // ⑦ 低电提示：**只在玩家真的处于飞行状态时**提示（用户第 105 轮口径：
        //    「电量低但没在飞就别弹 title」——低电站在地上充电 / 挂机 / 走动时不该被反复打断）。
        //    判据是原版 `abilities.flying`（= 创造式飞行真的开着），不是「离地 / 掉电中」；
        //    飞行中跨阈值那一次立即发，持续低电按 30 秒节流（都不刷屏）。
        //    【第 109 轮】阈值口径改成「剩余可飞分钟 → FE」：threshold = minutes × 1200 × cost；
        //    **严格小于**才提示（剩余正好阈值分钟时不提示），副标题写出剩余分钟数。
        if (!privileged && equipped) {
            final long threshold = ElectricJetpackItem.lowPowerThresholdFe();
            if (energyAfter < threshold) {
                // 先判「是否在飞」再判节流：不在飞时既不发送、也不写 lowPowerNotified，
                // 这样下一次真的起飞（仍然低电）还能立刻提示，落地期间不会被吞掉。
                if (player.getAbilities().flying
                    && (!state.lowPowerNotified
                        || gameTime - state.lastLowPowerNoticeTick >= NOTICE_THROTTLE_TICKS)) {
                    state.lowPowerNotified = true;
                    state.lastLowPowerNoticeTick = gameTime;
                    sendTitle(player, LANG_LOW_POWER, Component.translatable(LANG_LOW_POWER_SUBTITLE,
                        ElectricJetpackItem.remainingFlightMinutes(energyAfter)));
                }
            } else {
                state.lowPowerNotified = false;
            }
        } else {
            state.lowPowerNotified = false;
        }

        // ⑧ HUD 快照
        sendStatus(player, state, gameTime, equipped, charging, energyAfter, capacity);

        // ⑨ 记住本 tick 的采样（充电判据的上一基准；换包时 pack 实例不同 ⇒ 不误判成在充电）
        state.pack = equipped ? pack : ItemStack.EMPTY;
        state.previousEnergy = energyAfter;
    }

    // ==================== 能力的授予 / 收回 ====================

    /**
     * 收回飞行权限：先清标记，再<b>只在真的持有</b>时才写字段并同步（少发无意义的 abilities 包）。
     * <p>调用方必须已经排除创造 / 旁观（本方法不判断游戏模式，免得两处口径打架）。</p>
     */
    private static void revoke(final ServerPlayer player) {
        player.getPersistentData().remove(TAG_GRANTED);
        final Abilities abilities = player.getAbilities();
        if (abilities.mayfly || abilities.flying) {
            abilities.mayfly = false;
            // flying 也一起收回：只撤 mayfly 不撤 flying 时，客户端仍会在空中「飘」到下一次 abilities 同步，
            // 那是「已经耗尽却还能悬停一瞬」的错误观感。
            abilities.flying = false;
            player.onUpdateAbilities();
        }
    }

    /** 安全收回：创造 / 旁观跳过（绝不覆盖游戏模式自己的权限，只清我们自己打的标记）。 */
    private static void revokeUnlessPrivileged(final ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) {
            player.getPersistentData().remove(TAG_GRANTED);
            return;
        }
        revoke(player);
    }

    // ==================== 事件：收回路径的 5 种情形 + 登录兜底 ====================

    /** 换维度：先收再判（新维度下一 tick 会按新的生效背包重新授予）。 */
    @SubscribeEvent
    public static void onChangedDimension(final PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            revokeUnlessPrivileged(player);
            resetState(player);
        }
    }

    /** 下线：收回并清标记，避免把 mayfly=true 写进玩家存档。 */
    @SubscribeEvent
    public static void onLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            revokeUnlessPrivileged(player);
            STATES.remove(player.getUUID());
        }
    }

    /** 死亡：收回（否则复活时 restoreFrom 会把 mayfly 复制给新身体）。 */
    @SubscribeEvent
    public static void onDeath(final LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            revokeUnlessPrivileged(player);
            STATES.remove(player.getUUID());
        }
    }

    /** 登录幂等兜底：持久标记还在 ⇒ 这条 mayfly 是我们上次发的，先收再判（下一 tick 若装备了会重新授予）。 */
    @SubscribeEvent
    public static void onLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            revokeUnlessPrivileged(player);
            resetState(player);
        }
    }

    /** 复活：状态重来（死亡事件已收回权限；这里顺手清状态，避免带着旧背包实例做充电判定）。 */
    @SubscribeEvent
    public static void onRespawn(final PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            revokeUnlessPrivileged(player);
            resetState(player);
        }
    }

    private static void resetState(final ServerPlayer player) {
        final PlayerState state = STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        state.pack = ItemStack.EMPTY;
        state.previousEnergy = 0;
        state.lowPowerNotified = false;
        state.lastStatusTick = Long.MIN_VALUE / 2L;
        state.statusEquipped = false;
    }

    // ==================== HUD 快照 ====================

    private static void sendStatus(final ServerPlayer player,
                                   final PlayerState state,
                                   final long gameTime,
                                   final boolean equipped,
                                   final boolean charging,
                                   final int energy,
                                   final int capacity) {
        if (!equipped) {
            if (state.statusEquipped) {
                // 装备消失：补一条收条包，客户端立刻隐藏 HUD（不等超时）
                PacketDistributor.sendToPlayer(player,
                    new JetpackStatusPacket(false, false, false, 0, 0));
                state.statusEquipped = false;
            }
            return;
        }
        if (gameTime - state.lastStatusTick < STATUS_INTERVAL_TICKS) {
            return;
        }
        state.lastStatusTick = gameTime;
        state.statusEquipped = true;
        PacketDistributor.sendToPlayer(player, new JetpackStatusPacket(
            true, charging, JetpackChargerRange.inRange(player), energy, capacity));
    }

    // ==================== 提示（title） ====================

    /** 发一条 title（不节流；节流由调用方按 {@link #NOTICE_THROTTLE_TICKS} 处理）。 */
    private static void sendTitle(final ServerPlayer player, final String langKey) {
        sendTitle(player, langKey, null);
    }

    /**
     * 发一条 title + 可选副标题（不节流；节流由调用方处理）。
     * <p>第 109 轮起「低电」会把剩余可飞分钟作为副标题发出来：只显示「电量不足」玩家不知道还剩多久，
     * 而阈值本身就按分钟定义。副标题为 null 时不发副标题包（耗尽提示沿用旧行为）。</p>
     */
    private static void sendTitle(final ServerPlayer player, final String langKey,
                                  final Component subtitle) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(
            TITLE_FADE_IN_TICKS, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable(langKey)));
        if (subtitle != null) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }
}
