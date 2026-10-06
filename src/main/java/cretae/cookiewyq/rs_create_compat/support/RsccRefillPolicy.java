package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

/**
 * 「序列装配补合成请求量」的持久化档位（服务端权威）。<b>三档</b>：
 *
 * <ul>
 *     <li>{@link Mode#OFF}（<b>默认</b>）：确定性配方一次只请求一份、概率性配方按「此刻允许在制的件数」
 *     分批递进 —— 这就是本档位出现<b>之前</b>的旧行为，逐字一致；</li>
 *     <li>{@link Mode#GAP}：确定性配方按缺口一次要足（缺口 = 目标 −(网络 + 本仓 + 机器侧 + 在途)）；
 *     概率性配方<b>仍</b>走分批递进（按缺口要足会把成品率 &lt; 100% 的产线烧料）；</li>
 *     <li>{@link Mode#MACHINES}（<b>新增的第三档</b>）：按<b>机器台数</b>发 —— 本仓此刻喂着几台机器，
 *     就往 RS 里要几份（每台各一份），于是几台机器可以同时开工；请求量再夹一次「本仓此刻还允许在制的
 *     件数」，因此下单 1 件而挂 3 台机器时只会要 1 份，绝不过料。</li>
 * </ul>
 *
 * <h2>为什么需要它（用户原话）</h2>
 * <p>用户描述的两种处置「一个一个地发请求（产能不够）」与「一次性下单（可能过料）」，
 * 分别落在 {@link Mode#OFF} 与 {@link Mode#GAP}；他随后要求「再加一种：它当前有多少台机子就发多少个」
 * —— 那就是 {@link Mode#MACHINES}。</p>
 *
 * <h2>存在哪里 / 为什么</h2>
 * <p>与 {@link RsccShortagePolicy} / {@link RsccSupplyPolicy} 完全同一套做法：存放在<b>主世界
 * （overworld）的 {@code DimensionDataStorage}</b> 作为 {@link SavedData}（数据名
 * {@code rs_create_compat_refill_mode}）。用户要求「开关持久化、读档仍生效」，SavedData 与存档同生共死，
 * 天然满足；读取无副作用、无网络开销。</p>
 *
 * <h2>默认档位（为什么是「关」）</h2>
 * <p>默认 {@link Mode#OFF} = <b>与变更前的旧行为逐字一致</b>（确定性配方一次只请求 1 份、概率性配方按
 * {@code allowedConcurrentUnits()} 分批递进）。这是本任务「默认档按『变更前后行为一致』的稳妥选择」的
 * 落点：两个新档位都是纯 opt-in，任何旧存档 / 没敲过指令的玩家都不会因为这次改动而改变产线的网络存量
 * 曲线，因此<b>不引入任何行为回归</b>；想启用「按缺口补发」执行
 * {@code /rs_create_compat refill on}，想启用「按机器台数发」执行
 * {@code /rs_create_compat refill machines} 即可（一条指令、立即生效、随存档保存）。</p>
 *
 * <h2>旧存档兼容（为什么保留 {@code gap_refill} 布尔键）</h2>
 * <p>第三档之前的版本只存一个布尔 {@link #TAG_ENABLED}。读档时：新键 {@link #TAG_MODE} 存在 ⇒ 按它还原；
 * 不存在（旧存档）⇒ 用布尔键回退成 {@link Mode#GAP} / {@link Mode#OFF}。<b>两个键都写</b>，
 * 因此「升级后再降级回旧版本」时旧版本读到的布尔语义仍然正确（gap = {@link Mode#GAP}）。</p>
 */
public final class RsccRefillPolicy extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 与 {@link RsccDiag#ANCHOR_PREFIX} 同款的锚点前缀（用户不敲指令也能在日志里核对开关状态）。 */
    private static final String ANCHOR = "[rscc]";
    /** 存档内的数据名。 */
    private static final String DATA_NAME = "rs_create_compat_refill_mode";
    private static final String TAG_ENABLED = "gap_refill";
    /** 三档口径的字符串键（第三档新增；旧存档没有它 ⇒ 由 {@link #TAG_ENABLED} 布尔回退）。 */
    private static final String TAG_MODE = "refill_mode";

    /**
     * 三档口径。<b>{@link #id()} 同时进 NBT 与锚点日志</b>，因此它的取值就是可被外部核对的稳定标识。
     */
    public enum Mode {
        /** 默认档：确定性配方一次一份、概率性配方按并发名额分批递进（= 变更前的旧行为）。 */
        OFF("off"),
        /** 按缺口补发：确定性配方要足缺口；概率性配方仍分批递进（避免烧料）。 */
        GAP("gap"),
        /** 按机器台数发：本仓此刻喂着几台机子就发几份（每台各一份），再夹在在制名额以内。 */
        MACHINES("machines");

        private final String id;

        Mode(final String id) {
            this.id = id;
        }

        /** 稳定的字符串标识（NBT 存它、锚点日志打它、指令字面量用它）。 */
        public String id() {
            return id;
        }
    }

    /** 默认档位：关（= 变更前行为逐字一致，纯 opt-in）。 */
    private static final boolean DEFAULT_ENABLED = false;
    /** 默认档位的唯一真源（与 {@link #DEFAULT_ENABLED} 同向：{@code OFF} ⟺ 布尔 false）。 */
    private static final Mode DEFAULT_MODE = Mode.OFF;

    private Mode mode = DEFAULT_MODE;
    /** 旧版布尔键的镜像：{@code true} ⟺ {@link Mode#GAP}（只用于旧存档兼容的读写）。 */
    private boolean gapRefill = DEFAULT_ENABLED;

    /** 取（或首次创建）主世界里的档位数据。 */
    public static RsccRefillPolicy get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RsccRefillPolicy::new, RsccRefillPolicy::load),
            DATA_NAME
        );
    }

    private static RsccRefillPolicy load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final RsccRefillPolicy data = new RsccRefillPolicy();
        // 旧存档没有该键时保持默认档（= 关），因此读档不会「悄悄打开」新行为。
        if (tag.contains(TAG_ENABLED)) {
            data.gapRefill = tag.getBoolean(TAG_ENABLED);
        }
        // 新键优先；旧存档没有新键 ⇒ 用布尔键回退（false → OFF，true → GAP），
        // 因此「第三档之前存的档」读进来后行为与它保存那一刻逐字一致（绝不会自己跳成 MACHINES）。
        final Mode parsed = byId(tag.getString(TAG_MODE));
        data.mode = parsed == null ? (data.gapRefill ? Mode.GAP : Mode.OFF) : parsed;
        data.gapRefill = data.mode == Mode.GAP;
        return data;
    }

    /** 按 {@link Mode#id()} 解析档位；认不出（旧存档 / 手改坏的 NBT）返回 {@code null}。 */
    @org.jetbrains.annotations.Nullable
    public static Mode byId(final String id) {
        if (id == null) {
            return null;
        }
        for (final Mode candidate : Mode.values()) {
            if (candidate.id.equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 读取当前档位；只在服务端有效（客户端 / 取不到服务器时回落到默认档）。
     * <p>与 {@link RsccShortagePolicy#mode(Level)} 同一口径：绝不在客户端缓存，避免单机 / 联机下读到过期值。</p>
     */
    public static Mode mode(final Level level) {
        if (level instanceof ServerLevel server) {
            final MinecraftServer mc = server.getServer();
            if (mc != null) {
                return get(mc).mode;
            }
        }
        return DEFAULT_MODE;
    }

    /**
     * 兼容旧调用点：当前是否处于「按缺口补发」档。
     * <p>语义与第三档之前完全一致（{@code true} ⟺ 那个布尔键为真），只是现在它等价于
     * {@code mode(level) == GAP} —— 于是新增的 {@link Mode#MACHINES} 档<b>不会</b>被误当成
     * 「按缺口要足」（执行舱里那条「确定性配方再追加一次 deficit」的路径据此自动关闭）。</p>
     */
    public static boolean gapRefill(final Level level) {
        return mode(level) == Mode.GAP;
    }

    /** 新增第三档的只读读取（与 {@link #gapRefill(Level)} 同一口径，供执行舱请求量使用）。 */
    public static boolean machineRefill(final Level level) {
        return mode(level) == Mode.MACHINES;
    }

    public boolean isGapRefill() {
        return mode == Mode.GAP;
    }

    /** 当前档位（指令回报 / 诊断快照用）。 */
    public Mode getMode() {
        return mode;
    }

    /**
     * 设置「按缺口补发」开关（{@code refill on} / {@code refill off} 的兼容入口）。
     * <p>保留它（而不是让指令直接调 {@link #setMode(Mode)}）有两个理由：① 旧的指令字面量与
     * 旧的语言键 {@code refill.on} / {@code refill.off} 语义完全不变；② 状态变化时打的那条
     * <b>锚点行</b>里带着 {@code on} / {@code off} 这两个稳定标识，玩家在日志里核对的方式不变。</p>
     */
    public void setGapRefill(final boolean enabled) {
        final Mode target = enabled ? Mode.GAP : Mode.OFF;
        if (target == mode) {
            return;
        }
        mode = target;
        gapRefill = enabled;
        setDirty();
        LOGGER.info("{} refill={} (source=command)", ANCHOR, enabled ? "on" : "off");
    }

    /**
     * 设置档位（幂等；变化时打一条<b>锚点行</b>并标记脏数据以便落盘）。
     * <p>锚点行让「指令确实生效」这件事在日志里可被事实核对，与 {@code RsccAssemblyDebug} 的
     * {@code debug=on (source=command)} 同款；这里打的是档位自己的 {@link Mode#id()}。</p>
     */
    public void setMode(final Mode newMode) {
        if (newMode == null || newMode == mode) {
            return;
        }
        mode = newMode;
        gapRefill = newMode == Mode.GAP;
        setDirty();
        LOGGER.info("{} refill={} (source=command)", ANCHOR, newMode.id());
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putBoolean(TAG_ENABLED, gapRefill);
        tag.putString(TAG_MODE, mode.id());
        return tag;
    }
}
