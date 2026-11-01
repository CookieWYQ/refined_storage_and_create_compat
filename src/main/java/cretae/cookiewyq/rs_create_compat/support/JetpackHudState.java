package cretae.cookiewyq.rs_create_compat.support;

/**
 * 电动飞行背包 HUD 的<b>客户端只读镜像</b>（服务端每 5 tick 下发的权威快照）。
 *
 * <h2>为什么这个类放在 {@code support} 而不是 {@code client}</h2>
 * <p>S2C 载荷类（{@code network/JetpackStatusPacket}）在<b>注册期</b>就会被专用服务端加载并链接；
 * 只要它的处理体里出现任何 {@code net.minecraft.client.*} 类型，专用服务端就会在链接期
 * {@code NoClassDefFoundError}（本工程已经踩过一次，见 {@code ClientPayloadHooks} 的类注释）。
 * 因此载荷处理体只写这个<b>双端都存在</b>的纯数据类，真正的绘制放在
 * {@code client/JetpackHudOverlay}（服务端永远不会加载它）。</p>
 *
 * <h2>数据来源：服务端权威，客户端不推算</h2>
 * <ul>
 *     <li>{@code energy} / {@code capacity}：服务端读<b>物品组件里的真实电量</b>后下发；</li>
 *     <li>{@code charging}：判据 = <b>本 tick 背包能量比上一 tick 变大</b>（真的发生了能量转移，
 *     不是距离判定）；</li>
 *     <li>{@code inRange}：判据 = <b>玩家在任意一台范围充电器的几何范围内</b>（服务端按充电器
 *     自己的范围框算，见 {@link JetpackChargerRange}）。</li>
 * </ul>
 * <p>客户端<b>不</b>查方块实体、<b>不</b>读物品 NBT 自己算、<b>不</b>写任何世界状态 —— 只把这三个值
 * 原样画出来。快照超过 {@link #STALE_MS} 没更新（掉线 / 服务端停了 / 换维度间隙）时整条 HUD 隐藏，
 * 不会留一个「冻结在最后一次读数」的假面板。</p>
 *
 * <h2>线程</h2>
 * <p>写入发生在客户端主线程（载荷处理体用 {@code ctx.enqueueWork} 投递），读取发生在渲染线程，
 * 因此字段用 {@code volatile} 的单份不可变快照（{@link Snapshot}）而不是多个散字段 ——
 * 半更新（读到了新电量配旧容量）在读端就不可能发生。</p>
 */
public final class JetpackHudState {
    /** 快照超过这么久没更新就整条隐藏（毫秒）。服务端 5 tick（250ms）一条，留 20 倍余量。 */
    public static final long STALE_MS = 5000L;

    /** 一份完整快照（不可变；整份替换，读端永远看到一致的一组值）。 */
    public record Snapshot(boolean equipped, boolean charging, boolean inRange,
                           int energy, int capacity, long receivedAtMs) {
        /** 服务端没发过任何快照时的占位（未装备 = 不显示）。 */
        public static final Snapshot EMPTY = new Snapshot(false, false, false, 0, 0, 0L);

        /** 电量占比 {@code [0, 1]}（容量脏数据 / 0 时按 0 处理，绝不出现 NaN）。 */
        public double fillRatio() {
            if (capacity <= 0) {
                return 0.0D;
            }
            return Math.clamp((double) energy / (double) capacity, 0.0D, 1.0D);
        }
    }

    private static volatile Snapshot current = Snapshot.EMPTY;

    private JetpackHudState() {
    }

    /** 收到一份服务端快照（客户端主线程调用；服务端不会走到这里）。 */
    public static void accept(final boolean equipped, final boolean charging, final boolean inRange,
                              final int energy, final int capacity) {
        current = new Snapshot(equipped, charging, inRange,
            Math.max(0, energy), Math.max(0, capacity), System.currentTimeMillis());
    }

    /** 清空（断开连接 / 切换世界时由客户端调用，避免残留上一个世界的数据）。 */
    public static void clear() {
        current = Snapshot.EMPTY;
    }

    /** 当前快照（永不为 null；过期时返回 {@link Snapshot#EMPTY}）。 */
    public static Snapshot snapshot() {
        final Snapshot snapshot = current;
        if (snapshot.receivedAtMs() == 0L) {
            return Snapshot.EMPTY;
        }
        if (System.currentTimeMillis() - snapshot.receivedAtMs() > STALE_MS) {
            return Snapshot.EMPTY;
        }
        return snapshot;
    }
}
