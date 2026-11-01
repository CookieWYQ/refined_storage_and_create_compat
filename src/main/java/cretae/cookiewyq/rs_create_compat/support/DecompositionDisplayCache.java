package cretae.cookiewyq.rs_create_compat.support;

import java.util.List;

/**
 * 客户端只读镜像：服务端下发的那份「JEI 展示用降解条目」（见 {@link DecompositionDisplayEntry}）。
 *
 * <h2>为什么需要一份"镜像"而不是直接读 {@link DecompositionTable}</h2>
 * <p>{@link DecompositionTable} 只在<b>服务端</b>被数据包加载器填充（
 * {@link DecompositionDataLoader#onAddReloadListeners} 注册的是 {@code AddReloadListenerEvent}，
 * 只有服务端会分发）。专用服务器上客户端拿不到服务端的 {@code data/}，本机表是空的
 * —— 直接读它，JEI 分类页会是<b>空的</b>（而且没有任何报错，最容易被当成"JEI 没做"）。</p>
 * <p>因此服务端在数据包同步时（{@code OnDatapackSyncEvent}：进服 + {@code /reload}）
 * 把展开好的条目发一份过来，落到这里。JEI 只读这里。</p>
 *
 * <h2>降级：没有镜像时退回"本机表"</h2>
 * <p>单人游戏 / 局域网主机上服务端与客户端同 JVM，本机表就是权威数据；
 * 而且 JEI 可能在镜像到达<b>之前</b>就把配方列表建好了（进服时序：JEI 收完配方就重建）。
 * 因此 {@link #entries()} 在「从未收到过镜像」时直接返回
 * {@link DecompositionTable#displayEntries()} —— 单人整局都不会走到网络路径，也照样有数据。
 * 收到镜像后（哪怕内容是空表）以镜像为准：那是服务端此刻的真实答案。</p>
 *
 * <h2>线程</h2>
 * <p>写入只发生在客户端主线程（载荷处理体 {@code enqueueWork}），读取发生在客户端主线程
 * （JEI 配方注册 / 点击）。字段是 {@code volatile} 的不可变列表，读写无锁。</p>
 */
public final class DecompositionDisplayCache {
    /** 服务端下发过的镜像（不可变；永不为 null）。 */
    private static volatile List<DecompositionDisplayEntry> synced = List.of();
    /** 是否至少收到过一次镜像（false = 还没有，读方退回本机表）。 */
    private static volatile boolean received;

    private DecompositionDisplayCache() {
    }

    /**
     * 收下一份服务端镜像（覆盖旧值；空列表也是合法结果 —— 「服务端表是空的」同样要如实反映）。
     *
     * @param entries 服务端展开的条目（可为 null / 空 = 空表）
     */
    public static void accept(final List<DecompositionDisplayEntry> entries) {
        synced = entries == null || entries.isEmpty() ? List.of() : List.copyOf(entries);
        received = true;
    }

    /** 丢弃镜像（退出世界时用；下次进服 / 单人游戏会重新取）。 */
    public static void clear() {
        synced = List.of();
        received = false;
    }

    /** 是否已收到过服务端镜像（调试 / 自检用）。 */
    public static boolean received() {
        return received;
    }

    /**
     * 当前应该展示的条目：优先服务端镜像，没有镜像时退回本机表（单人游戏 / 镜像尚未到达）。
     *
     * @return 不可变列表（可能为空）
     */
    public static List<DecompositionDisplayEntry> entries() {
        if (received) {
            return synced;
        }
        return DecompositionTable.displayEntries();
    }
}
