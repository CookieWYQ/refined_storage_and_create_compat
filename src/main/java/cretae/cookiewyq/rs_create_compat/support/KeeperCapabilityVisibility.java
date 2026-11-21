package cretae.cookiewyq.rs_create_compat.support;

/**
 * 第 142 轮（第 118 轮复核 <b>H2 剩余</b>）：定量保持器「对外能力」的<b>调用者可见性</b>闸门。
 *
 * <h2>要解决什么</h2>
 * <p>保持器把「本机缓冲 + 网络里与标记同类的资源」通过 {@code IItemHandler} / {@code IFluidHandler}
 * 能力暴露给相邻方块（供漏斗、管道取出 / 送入，这是本方块的设计功能）。但 RS 2.0.9 的<b>外部存储</b>
 * 会把相邻方块的能力注册成<b>网络自己的存储源</b>：</p>
 * <ol>
 *     <li><b>网络镜像被重复计算</b>：镜像 = 网络存量本身，网络存量 = 真实存储 + 镜像 ⇒ 虚高
 *     （每个虚拟槽最多 +{@code STORAGE_SLOT_LIMIT}）；保持器拿虚高值算「超出目标」的部分，
 *     会把真实资源多销毁一截；</li>
 *     <li><b>本机缓冲也被重复计入</b>：缓冲一旦被 RS 当成网络存储，{@code net.get(resource)} 就已包含它，
 *     而 {@code KeeperOverflow.localDestroyAmount} 的池子又是 {@code net.get + 缓冲余量}
 *     ⇒ 缓冲里的资源被算了两遍，销毁量同样偏多；</li>
 *     <li>读 / 抽路径绕成递归（第 118 轮只给「抽取」加了重入闸，存量读取没有）。</li>
 * </ol>
 *
 * <h2>为什么是「对 RS 隐藏、对第三方照旧」</h2>
 * <ul>
 *     <li>本方块<b>故意</b>把能力暴露给外部自动化（设计功能）——不能一刀切删掉；</li>
 *     <li>但对 <b>RS 自己的</b>存储聚合来说，这个能力整体都是冗余且有害的：RS 要读存量、要取资源，
 *     直接走网络即可，完全不需要经过保持器的能力。让 RS 看不见它，双计与递归一起消失；</li>
 *     <li>识别方式：看调用栈里有没有 RS 的类。这是唯一可靠的判据 —— {@code IItemHandler} /
 *     {@code IFluidHandler} 的接口签名里不携带「调用者是谁」，能力查询也没有方向/来源参数。</li>
 * </ul>
 *
 * <h2>边界（如实写明）</h2>
 * <ul>
 *     <li><b>隐藏是整份能力</b>（读 / 插入 / 抽取 / 流体 fill / drain 全部）：只要 RS 还能看见任何一部分，
 *     它就会把「看得见的那部分」注册成网络存储，双计与池子重复计数就还在；</li>
 *     <li>对<b>第三方</b>（Create 漏斗 / 管道 / 原版漏斗 / 其他模组）完全不变：仍能读、仍能抽、仍能送入；</li>
 *     <li>RS 网络里的资源本来就能直接取用，隐藏后不会少任何可达路径；玩家若在保持器旁贴 RS 外部存储，
 *     那台外部存储不再把保持器当存储源（这本来就是双计的来源）。</li>
 * </ul>
 */
public final class KeeperCapabilityVisibility {
    /** 只有前缀匹配的类才算「RS 自己的存储聚合」：{@code com.refinedmods.refinedstorage.*}。 */
    private static final String REFINED_STORAGE_PACKAGE = "com.refinedmods.refinedstorage";
    /** 只看调用栈最上面这么多帧（RS 外部存储 → 本 handler 的链路远短于此）。 */
    private static final int MAX_FRAMES = 24;

    private static final StackWalker WALKER = StackWalker.getInstance();

    private KeeperCapabilityVisibility() {
    }

    /**
     * 当前调用者是否属于 RS 自己的存储聚合（外部存储 / 接口等）。
     *
     * <p><b>线程边界</b>：只在服务端线程的 handler 调用里使用；{@link StackWalker} 本身线程安全。
     * 玩家正常从网络终端里取资源不经过本能力（走的是 RS 网络节点），因此不会被误判。</p>
     */
    public static boolean isRefinedStorageCaller() {
        return WALKER.walk(frames -> frames
            .limit(MAX_FRAMES)
            .anyMatch(frame -> frame.getClassName().startsWith(REFINED_STORAGE_PACKAGE)));
    }

    /** 整份能力是否应对当前调用者隐藏（= 调用者是 RS 自己）。语义别名，便于调用点读起来像自然语言。 */
    public static boolean hiddenFromCaller() {
        return isRefinedStorageCaller();
    }
}
