package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 「可成集群的机器」在本模组内部统一的<b>存储委托协议</b>。
 *
 * <h2>语义（用户需求原话的落地）</h2>
 * <ul>
 *     <li><b>同类型机器相邻（6 向）</b> → 构成一个整体（集群）；</li>
 *     <li><b>容量叠加</b>：整体容量 = 机器台数 × 单机容量（例如 27 格 + 27 格 = 54 格）；</li>
 *     <li><b>内容共享</b>：整个集群<b>只有一份</b>资源数据（物品 / 流体），往任意一台里存，
 *     其它机器立刻可见 —— 实现方式是「所有成员持同一个存储对象」，而不是各存各的再同步。</li>
 * </ul>
 *
 * <h2>为什么用 {@code Object} 传存储</h2>
 * 各机器的内部存储类型不同（{@code SimpleContainer} + {@link MultiFluidCache}、
 * {@link RsccUnboundedItemStorage} + {@link RsccUnboundedFluidStorage}…），而<b>一个集群里的机器
 * 必然是同一类型</b>，因此管理器只搬运这个不透明的「载荷」对象，具体语义由实现类自己解释。
 * 载荷对象必须是<b>稳定身份</b>（同一份存储每次返回同一个实例），管理器会用 {@code ==} 判定
 * 「存储是否已经换成共享的那份」。
 *
 * <h2>与 SafeCollect 的关系</h2>
 * 本协议只改「存储挂在哪一份上」，不改任何搬运语义：{@link SafeCollect} 的
 * 「先 SIMULATE 算能收多少 → 只按实际能收的量抽取 → 不足回滚」继续成立；容量叠加后
 * 「满」= 整体满，此时不收集、不吞资源。
 */
public interface RsccClusterable {
    /** 本机坐标（集群成员身份）。 */
    BlockPos rscc$clusterPos();

    /** 本机所在世界（仅服务端参与集群）。 */
    Level rscc$clusterLevel();

    /**
     * 新建一份「容量 = {@code machineCount} 台叠加、内容为空」的共享存储。
     * <p>只在集群重建时由主控对象调用，返回的载荷随后被所有成员采纳。
     */
    Object rscc$clusterNewPayload(int machineCount);

    /**
     * 新建一份「容量 = {@code members} 各自单机容量<b>求和</b>、内容为空」的共享存储。
     * <p>默认实现退化为 {@link #rscc$clusterNewPayload(int) machineCount × 本机单机容量} ——
     * 只有<b>同族但单机容量不同</b>的机器才需要覆写（本工程的装填器家族：基础 54 格 + 高级 108 格
     * 相邻时算一个整体）。若沿用「台数 × 某一种单机容量」，混阶集群会凭空缩小（高级被当成基础）
     * 或放大（基础被当成高级）整体容量，两者都不允许。
     */
    default Object rscc$clusterNewPayloadFor(final java.util.List<RsccClusterable> members) {
        return rscc$clusterNewPayload(members == null || members.isEmpty() ? 1 : members.size());
    }

    /**
     * 本机当前持有的存储载荷（<b>身份稳定</b>：同一份存储必须每次返回同一个对象实例）。
     */
    Object rscc$clusterPayload();

    /** 把本机的存储字段切换到共享载荷（切换后本机与集群看到的是同一份内容）。 */
    void rscc$clusterAdopt(Object shared);

    /**
     * 把本机存储切换回「只有自己一台」的独立存储（<b>内容为空</b>）。
     * <p>只在本机脱离集群、且内容已按规则移交之后调用；因此这里绝不把旧内容再抛一次，
     * 否则会与集群保留的那一份重复。
     */
    void rscc$clusterStandalone();

    /**
     * 把 {@code from} 的<b>全部内容搬进</b> {@code into}（同类型载荷，搬运是「移动」不是「复制」）。
     *
     * @return {@code from} 中<b>没能搬走</b>的余量（0 = 全部搬空）；余量留给调用方兜底，绝不销毁
     */
    long rscc$clusterMerge(Object from, Object into);

    /** 兜底归宿：把给定载荷里剩下的内容落回世界（物品掉落 / 流体装桶掉落），绝不静默销毁。 */
    void rscc$clusterSpill(Object payload);

    /**
     * 本机是否承担「内容落盘」职责。
     * <p>一个集群里<b>只有主控</b>为 true：避免同一份内容被写成多份，读档后互相合并导致翻倍。
     */
    boolean rscc$clusterOwnsPayload();

    /** 设置内容落盘职责（只有集群主控为 true）。 */
    void rscc$clusterSetOwnsPayload(boolean owns);

    /** 集群规模 / 主控变化回调（用于标记区块脏、刷新界面显示等）。 */
    void rscc$clusterChanged(int memberCount, BlockPos masterPos);

    /**
     * 两台机器是否可以并入同一个集群。
     * <p>默认要求<b>同一类型的机器</b>（载荷类型必须一致，否则无法互相搬运内容）；
     * 未来若有「同类型但还要看配置是否一致」的机器（例如按标记资源绑定的存储），
     * 重写本方法即可把这类约束纳入连通判定。
     */
    default boolean rscc$clusterCompatible(final RsccClusterable other) {
        return other != null && other.getClass() == getClass();
    }
}
