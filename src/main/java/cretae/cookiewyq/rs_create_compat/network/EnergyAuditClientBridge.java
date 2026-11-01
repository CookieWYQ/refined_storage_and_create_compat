package cretae.cookiewyq.rs_create_compat.network;

/**
 * S2C 载荷的「客户端专属处理」间接层（专用服务端安全性），<b>能耗监视专用</b>。
 *
 * <h2>为什么不能直接写在载荷类里</h2>
 * <p>{@code registrar.playToClient(TYPE, CODEC, X::handle)} 会在<b>注册期</b>就加载并链接载荷类，
 * JVM 校验字节码时必须解析处理体里出现的<b>每一个类型</b>。只要处理体里出现
 * 「{@code Minecraft.getInstance()...} 交给某个客户端形参」，专用服务端就会在注册期去加载
 * {@code net.minecraft.client.*} ⇒ {@code NoClassDefFoundError} ⇒ 模组加载失败。
 * 本工程已经因此炸过一次（见 {@link ClientPayloadHooks} 的类注释，那里是它的通用版）。</p>
 *
 * <h2>为什么不再复用 {@link ClientPayloadHooks}</h2>
 * <p>本功能与「终端绑定网络电量」那条线<b>并行开发</b>，{@code ClientPayloadHooks#Sink} 是该线的并发
 * 写域。这里用<b>完全同构</b>的独立桥（{@code default} 空实现 + 客户端 install），
 * 好处是两边各自加方法时不会互相改同一个文件、也不会有一次合并把其中一条的接线吃掉。
 * 语义、线程模型、install 时机（客户端 {@code FMLClientSetupEvent}）与
 * {@link ClientPayloadHooks} 一字不差。</p>
 *
 * <h2>本类为什么是专服安全的</h2>
 * <p>本类只引用双端都存在的类型（{@link Sink} 的形参是本包里的载荷 record，服务端也加载它）；
 * 真正的界面代码在 {@code client/EnergyAuditClientSetup} 里，专用服务端永远不会加载那个类。</p>
 */
public final class EnergyAuditClientBridge {
    /** 客户端实现（服务端上恒为 {@link #EMPTY}）。 */
    public interface Sink {
        /** 收到一份能耗快照：开界面 / 就地刷新 / 喂高亮（全部在客户端主线程上执行）。 */
        default void onSnapshot(final SyncEnergyAuditPacket packet) {
        }
    }

    /** 专用服务端上的空实现（也用于客户端挂载之前的兜底）。 */
    private static final Sink EMPTY = new Sink() {
    };

    private static volatile Sink sink = EMPTY;

    private EnergyAuditClientBridge() {
    }

    /** 挂上真正的客户端实现（只有 {@code client/EnergyAuditClientSetup} 调用一次）。 */
    public static void install(final Sink impl) {
        sink = impl == null ? EMPTY : impl;
    }

    /** 当前实现（永不为 null；服务端上恒为空实现）。 */
    public static Sink get() {
        return sink;
    }
}
