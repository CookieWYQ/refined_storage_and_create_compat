package cretae.cookiewyq.rs_create_compat.client;

/**
 * 客户端只读镜像：<b>「优先复用网络中的中间产物」开关</b>（服务端权威，见
 * {@code support.RsccIntermediateReusePolicy}）。
 *
 * <h2>为什么客户端必须自己存一份</h2>
 * <p>真值落在主世界的存档级 {@code SavedData} 里，而读取方法
 * {@code reuseIntermediates(Level)} 对<b>客户端 Level 一律回落到默认档（关）</b> ——
 * 客户端拿不到服务端存档。RS 的「自动合成预览」界面又是<b>纯客户端</b>开出来的
 * （{@code ClientPlatformUtil.openCraftingPreview} 里直接 new 出界面与菜单，没有服务端菜单、
 * 也就没有 ContainerData 可搭），所以只能靠一条包把权威值送过来。</p>
 *
 * <p>客户端<b>从不</b>自己改它：只在收到 {@code SyncIntermediateReusePacket} 时写入，
 * 由预览界面读取渲染提示。因此单机 / 联机下显示的都与服务端存档一致，
 * 不存在「看着开了其实没开」。</p>
 *
 * <p>默认值与服务端默认档一致（关）：快照还没到时，界面显示的正是服务端实际行为
 * （逐字老样子），不会先误导一下再改。</p>
 */
public final class IntermediateReuseClient {
    /** 默认档 = 服务端默认档（关，纯 opt-in）。 */
    private static volatile boolean enabled = false;

    private IntermediateReuseClient() {
    }

    /** S2C 收包后写入只读镜像。 */
    public static void set(final boolean value) {
        enabled = value;
    }

    /** 当前开关（快照缺席时 = 服务端默认档「关」）。 */
    public static boolean enabled() {
        return enabled;
    }
}
