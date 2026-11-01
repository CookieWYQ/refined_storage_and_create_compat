package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.network.TerminalPowerPacket;

/**
 * 高级远程多功能终端：<b>绑定网络电量</b>的客户端只读镜像（服务端权威快照的唯一落点）。
 *
 * <h2>为什么在 {@code client} 包而网络包里不出现它</h2>
 * <p>{@code network/TerminalPowerPacket} 的处理体<b>只</b>调
 * {@code ClientPayloadHooks.get().updateTerminalPower(packet)}（双端安全的接口）；
 * 真正写本类的是 {@code client/ClientPayloadSink}（客户端才加载）。因此专用服务端链接期
 * 不会去加载本类，注册期也不会 {@code NoClassDefFoundError}（同一套做法见
 * {@code network/ClientPayloadHooks} 的类注释）。</p>
 *
 * <h2>三种状态</h2>
 * <ul>
 *     <li><b>未知</b>（还没收到过快照 / 快照过期）：<b>不</b>置灰任何 Tab —— 这是「有电时行为与今天
 *     完全一致」的保证：网络包丢了 / 服务端没发（比如别的链路打开了同一个 RS 界面）时，
 *     绝不能凭「没数据」把正常终端锁死；</li>
 *     <li><b>有电</b>（{@code powered=true}）：与今天完全一致（只按 {@code supported} 判）；</li>
 *     <li><b>没电</b>（{@code powered=false}）：所有模式 Tab 一律置灰 + 不可点。</li>
 * </ul>
 * <p>「没电」包含「未绑定 / 绑定位置未加载 / 网络未成形」—— 服务端已把这些折叠成
 * {@code powered=false}（见 {@code support/TerminalNetworkPower#snapshot}），客户端不做二次推断，
 * <b>尤其不看终端物品自身的电量</b>（用户明确：终端本身有电也不行）。</p>
 *
 * <h2>过期</h2>
 * <p>服务端在界面打开期间每 20 tick（1 秒）发一条；{@link #STALE_MS} 留 5 倍余量。超过它没更新
 * （掉线 / 换维度 / 服务端停了）就回落到「未知」＝不置灰，不会把界面冻结在最后一次读数上。</p>
 *
 * <h2>线程</h2>
 * <p>写入发生在客户端主线程（载荷处理体已 {@code enqueueWork}），读取发生在渲染线程 ⇒
 * 用 {@code volatile} 持有<b>整份不可变快照</b>，读端不可能看到「新存量配旧容量」的半更新。</p>
 */
public final class TerminalPowerState {
    /** 快照过期时间（毫秒）：服务端 1 秒一条，留 5 倍余量。 */
    public static final long STALE_MS = 5000L;

    /**
     * 一份完整快照（不可变；整份替换）。
     *
     * @param known    是否拿到过有效快照（false = 未知，不置灰）
     * @param stored   绑定网络存量（FE）
     * @param capacity 绑定网络容量（FE）
     * @param bound    终端是否绑到一张此刻可达的网络
     * @param powered  服务端权威：此刻能否切换模式
     * @param receivedAtMs 接收时刻（{@link System#currentTimeMillis()}）
     */
    public record Snapshot(boolean known, long stored, long capacity, boolean bound,
                           boolean powered, long receivedAtMs) {
        /** 什么都没收到过（未知）。 */
        public static final Snapshot UNKNOWN = new Snapshot(false, 0L, 0L, false, false, 0L);

        /** 「已知没电」：所有 Tab 置灰不可点。 */
        public boolean locked() {
            return known && !powered;
        }
    }

    private static volatile Snapshot current = Snapshot.UNKNOWN;

    private TerminalPowerState() {
    }

    /** 收到一份服务端快照（客户端主线程；服务端不会走到这里）。 */
    public static void accept(final TerminalPowerPacket packet) {
        current = new Snapshot(true,
            Math.max(0L, packet.stored()),
            Math.max(0L, packet.capacity()),
            packet.bound(),
            packet.powered(),
            System.currentTimeMillis());
    }

    /**
     * 清空为「未知」：只在<b>换了一个界面</b>（新打开 / 换模式重开）时调用。
     * <p>为什么换界面要清：上一台终端「没电」的快照不该决定新界面的 Tab —— 新界面的权威快照
     * 由服务端在打开后立刻补发（{@code TerminalNetworkPower#start}）。同一界面的重复初始化
     * （窗口缩放 / 从子界面返回）<b>不</b>清，避免整排 Tab 闪一下。
     */
    public static void reset() {
        current = Snapshot.UNKNOWN;
    }

    /** 当前快照（永不为 null；过期 / 没收到过 ⇒ {@link Snapshot#UNKNOWN}）。 */
    public static Snapshot snapshot() {
        final Snapshot snapshot = current;
        if (!snapshot.known()) {
            return Snapshot.UNKNOWN;
        }
        if (System.currentTimeMillis() - snapshot.receivedAtMs() > STALE_MS) {
            return Snapshot.UNKNOWN;
        }
        return snapshot;
    }

    /** 绑定网络此刻是否确定没电（= 所有模式 Tab 必须置灰不可点）。未知时恒为 false。 */
    public static boolean isLocked() {
        return snapshot().locked();
    }
}
