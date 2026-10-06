package cretae.cookiewyq.rs_create_compat.network;

/**
 * S2C 载荷的「客户端专属处理」<b>唯一间接层</b>（专用服务端安全性）。
 *
 * <h2>为什么必须有它（专用服务端启动即崩的根因）</h2>
 * <p>{@code registrar.playToClient(X.TYPE, X.STREAM_CODEC, X::handle)} 会<b>读取载荷类的静态字段</b>，
 * 于是载荷类在<b>注册期</b>就被初始化并链接；JVM 链接期要校验<b>全部</b>方法字节码，而校验器在做
 * 「实参 ↔ 形参」的<b>跨类可赋值性</b>检查时<b>必须解析并加载</b>涉及的两个类。只要载荷类的处理体里
 * 出现「把客户端类型交给客户端类型形参」的写法（例如
 * {@code Minecraft.getInstance().getToasts().addToast(new CompatCompletionToast(rows))}），
 * 专用服务端就会在注册期去加载 {@code net.minecraft.client.gui.components.toasts.Toast}
 * —— 服务端没有这个类 ⇒ {@code NoClassDefFoundError} ⇒ 模组加载失败。</p>
 *
 * <h2>本类为什么是安全的</h2>
 * <p>本类只使用<b>双端都存在</b>的类型：{@link Sink} 的每个方法都只是 {@code default} 空实现，
 * 形参一律是 {@code network} 包里的载荷 record（服务端也加载它们）。客户端在 {@code ClientInit}
 * 里用 {@code client/ClientPayloadSink} 覆盖这些方法；专用服务端上永远是空实现，
 * 且 PLAY→CLIENT 的处理器在服务端一次都不会被调用 —— 因此注册期与运行期都不会触碰
 * {@code net.minecraft.client.*} 里的任何类型。</p>
 *
 * <p>与既有的 {@code RefreshCamouflagePacket#setClientTracker} 是同一套做法的统一版：
 * 那个只有一个包需要注入，这里 13 个包统一走一层桥，避免每个包各自维护一份静态字段。</p>
 */
public final class ClientPayloadHooks {
    /**
     * 客户端实现（服务端上恒为 {@link #EMPTY}）。
     * <p>全部方法都是 {@code default} 空实现：服务端即使被调用也只是什么都不做。</p>
     */
    public interface Sink {
        /** 「模式切换后恢复鼠标原生坐标」（GLFW 窗口操作）。 */
        default void restoreCursor(final RestoreCursorPacket packet) {
        }

        /** 「更换机器」：弹出机器选择子界面。 */
        default void openMachineSelect(final AssemblyMachineCandidatesPacket packet) {
        }

        /** 「总样板改绑机器」：弹出 / 就地刷新改绑界面。 */
        default void openPatternRebind(final AssemblyPatternRebindOpenPacket packet) {
        }

        /** 完成横幅：右下角 Toast。 */
        default void showCompletionBanner(final CompletionBannerPayload payload) {
        }

        /** 高级定量保持器：把 4 槽配置快照写回已打开的界面。 */
        default void syncAdvKeeperConfig(final SyncAdvKeeperConfigPacket packet) {
        }

        /** 序列装配告警快照：写入客户端只读镜像。 */
        default void syncAssemblyAlerts(final SyncAssemblyAlertsPacket packet) {
        }

        /** 输出总线「归属未确定」快照：写入叠加层。 */
        default void syncBusInterference(final SyncBusInterferencePacket packet) {
        }

        /** 执行仓绑定 / 面模式：把权威值刷新回已打开的面配置界面。 */
        default void syncChamberBinding(final SyncChamberBindingPacket packet) {
        }

        /** 执行仓单元样板汇总：把权威值刷新回已打开的汇总界面。 */
        default void syncChamberUnits(final SyncChamberUnitsPacket packet) {
        }

        /** 归流缓存仓匹配区快照：写入已打开的界面（主界面或匹配设置子窗口）。 */
        default void syncCollectionMarkers(final SyncCollectionMarkersPacket packet) {
        }

        /** 「优先复用中间产物」开关：写入客户端只读镜像。 */
        default void syncIntermediateReuse(final SyncIntermediateReusePacket packet) {
        }

        /** 定量保持器流体 / 气体标记：写回已打开的界面。 */
        default void syncQuantityFluidMarker(final SyncQuantityFluidMarkerPacket packet) {
        }

        /** 缺料处置策略：写入客户端只读镜像。 */
        default void syncShortageMode(final SyncShortageModePacket packet) {
        }
    }

    /** 专用服务端上的空实现（也用于客户端挂载之前的兜底）。 */
    private static final Sink EMPTY = new Sink() {
    };

    private static volatile Sink sink = EMPTY;

    private ClientPayloadHooks() {
    }

    /**
     * 挂上真正的客户端实现（只有 {@code client/ClientInit#onClientSetup} 调用一次）。
     *
     * <p>传 {@code null} 表示退回空实现。方法引用 {@code ClientPayloadSink::new} 在客户端类里求值，
     * 因此本类自身不会因此加载任何客户端类型。</p>
     */
    public static void install(final Sink impl) {
        sink = impl == null ? EMPTY : impl;
    }

    /** 当前实现（永不为 null；服务端上恒为空实现）。 */
    public static Sink get() {
        return sink;
    }
}
