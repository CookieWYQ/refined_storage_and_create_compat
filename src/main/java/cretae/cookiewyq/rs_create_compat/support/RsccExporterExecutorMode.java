package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 桥接接口：由 Mixin（注入 RS 输出总线方块实体 {@code AbstractExporterBlockEntity}）实现，
 * 让其它代码类型安全地读写「延长型输出（序列执行仓模式）」的状态。
 * <p>检测方式（最终规则）：输出总线从自身出发<b>沿线缆方块搜链</b>（只经过 RS 线缆 / 总线、不穿机器，
 * 上限 64 步、格子数上限 128），够得到处于「总线输出（延长型输出）」模式的序列执行仓才算数；
 * <b>六向相邻</b>是「0 步线缆」的特例。一趟搜链同时数出<b>可达几台</b>与<b>是否穷尽</b>：
 * 可达<b>恰好 1 台</b>且穷尽才认归属，否则判为<b>归属未确定</b>并停用延长型（见 {@link RsccBusInterference}）。</p>
 * <p><b>类别模型</b>：导出类别不再写死 2 类，而是由执行仓的单元样板 + Create 配方数据推导出
 * 一个<b>有序的类别集合</b>（见 {@link RsccBusCategory}）；本接口以「稳定类别 id 集合」读写选择结果。
 * 同一类别可被多台输出总线同时选中，此时执行仓按轮询<b>均分</b>该类别的产出。</p>
 * <p>与 {@link RsccAutocrafterStorage} 同理，本接口刻意放在 support 包：mixin 包被 mixins.xml
 * 声明为 Mixin 专用包，普通类放在其中被外部直接引用会抛 IllegalClassLoadError。</p>
 */
public interface RsccExporterExecutorMode {
    /**
     * 本输出总线是否处在「延长型输出」的布局里（= 沿可穿行集合够得到<b>至少一台</b>
     * 「总线输出」模式的执行仓）。
     * <p><b>注意语义（本轮改造）</b>：为真只代表界面要显示类别条 / 可能显示「已停用」红条；
     * <b>能不能真的搬运</b>由 {@link #rscc$getLinkedExecutor()} 决定 —— 归属未确定时它是 {@code null}
     * （延长型被停用，退回普通输出总线）。判定见 {@link RsccBusInterference#inspect}。</p>
     */
    boolean rscc$isExecutorMode();

    /**
     * <b>原始布局判定</b>：线缆是否够得到<b>至少一台</b>「总线输出」执行舱 ——
     * <b>不含</b>玩家那条「强制普通总线」开关。
     * <p><b>为什么必须与 {@link #rscc$isExecutorMode()} 分开</b>（用户硬要求：改界面归属不得丢掉状态信息）：
     * 「是否要显示<i>已停用红条 + 显示可达区域按钮</i>」依据的是<b>这个</b>判定，
     * 因此玩家把总线强制成普通界面后，只要归属确实未确定，红条与按钮<b>照旧显示</b>；
     * 而「界面用哪套（类别条还是普通过滤器）」依据 {@link #rscc$isExecutorMode()}。</p>
     */
    boolean rscc$isLinkedLayout();

    /**
     * 玩家是否已把本条输出总线<b>强制为普通总线</b>（用户原话：「赶紧做我要的按钮」）。
     * <p>为真时一律回到普通输入输出总线界面（过滤器可编辑、走 RS 原版「网络 → 目标」策略），
     * 不再被自动判定成「序列装配总线」。（持久化、服务端权威。）</p>
     */
    boolean rscc$isForceNormalBus();

    /** 设置「强制普通总线」（服务端权威；C2S 包调用）。落盘 + 立刻按新归属重建策略与导出清单。 */
    void rscc$setForceNormalBus(boolean force);

    /**
     * 最近一次归属判定的完整报告（服务端权威；客户端一律 {@link RsccBusInterference.Report#CLEAR}）。
     * <p>界面同步链路（容器菜单 Mixin）直接取它来下发「是否已停用 / 可达几台 / 线缆簇 / 可达执行舱坐标」，
     * 因此界面与搬运看到的是<b>同一份</b>判定结果（同一缓存窗口，不存在第二趟搜索）。</p>
     */
    RsccBusInterference.Report rscc$linkReport();

    /** 本输出总线当前已选的类别 id（有序、去重；仅本机自己的请求，服务端权威）。 */
    List<String> rscc$getExportCategoryIds();

    /**
     * 本机是否<b>显式选过</b>类别（玩家在界面上点过）。
     * <p>{@code false} 表示该输出总线自放置/读档以来从未被点过 —— 此时执行仓按「默认全选
     * 全部输入性产物类别」处理（与改造前「输出原料默认开、输出中间产物默认关」的行为对齐）；
     * 一旦玩家点过任何一项，就以 {@link #rscc$getExportCategoryIds()} 为准。</p>
     */
    boolean rscc$isCategorySelectionExplicit();

    /** 设置已选类别 id（服务端权威，来自输出总线界面上的类别开关）。 */
    void rscc$setExportCategoryIds(List<String> categoryIds);

    /**
     * 本输出总线应下发给界面的类别快照（有序）：每个类别带「是否已选 / 共享台数」。
     * <p>服务端调用时会顺带把类别的归属<b>归一</b>一次（已无意义的类别自动清掉）；
     * 客户端 / 未绑定时返回空列表。</p>
     */
    List<RsccBusCategory> rscc$getCategorySnapshot();

    /**
     * 按当前轮询轮次重建导出清单（服务端逐 tick 由执行仓驱动）。
     * <p>仅在「有类别被多台输出总线共享」时才会改变内容：共享类别的过滤项只在本机轮到的那一 tick
     * 出现在导出清单里，从而实现「多个输出总线均分同一类别的产出」。</p>
     */
    void rscc$refreshBusTurn();

    /**
     * 重新检测「相邻 / 线缆相连」的执行仓并重建导出清单（执行仓切换输出模式后由它主动调用）。
     * <p>同时重建 transfer strategy：本模式下会强制使用「模糊模式」的扩展器，
     * 这样不带数据组件的物品过滤项也能匹配网络上带 NBT 的过渡件。</p>
     */
    void rscc$refreshExecutorMode();

    /**
     * <b>只读</b>：玩家打开本总线界面时刷新一次「归属解析结果」——<b>只作废并重算缓存</b>，
     * 绝不重装传输策略、绝不动任何搬运状态。
     *
     * <p><b>为什么单独一个方法（用户硬要求：打开界面是只读行为）</b>：界面打开是极低频的旁观动作，
     * 它不该有能改变产线推进的副作用。{@link #rscc$refreshExecutorMode()} 会重装节点策略
     * （{@code setTransferStrategy}）——那是「方块重新初始化 / 切模式」才该做的事；
     * 若挂在界面打开路径上，玩家每右键一次总线就会动一次搬运链路。本方法只做
     * 「作废 20 tick 归属缓存 + 立刻重算」，结果与下一 tick 的常规重算完全一致，因此
     * <b>打开界面在任务状态上是零副作用</b>（可被自检断言）。</p>
     */
    void rscc$refreshLinkForUi();

    /**
     * 「邻块失效事件」轻量失效：邻块的放置 / 破坏 / 被替换可能改变导线连通性，需要作废连接缓存。
     * <p><b>刻意不做同步搜索</b>：事件回调发生在世界正在被修改的过程中，此处只作废缓存 / 安装标记 /
     * 退避窗口，真正的重算留给下一 tick 的 {@link #rscc$serverTick()} 安全时机。
     * 客户端调用是空操作。</p>
     */
    void rscc$invalidateNeighborLink();

    /** 本输出总线最终绑定的执行仓坐标（未绑定 / 客户端一律返回 {@code null}）。 */
    @Nullable
    BlockPos rscc$linkedExecutorPos();

    /**
     * 本输出总线最终绑定的执行仓方块实体（未绑定 / 客户端一律 {@code null}）。
     * <p><b>本轮新增</b>：延长模式下的取货源就是这台执行舱的<b>内部存储</b>
     * （见 {@link RsccChamberExportStrategy}），因此策略需要拿到执行舱实体本身而不只是坐标。
     * 逐次解析（走 20 tick 缓存），不持有引用 —— 拆方块 / 切模式后自然解析为 {@code null}。</p>
     */
    @Nullable
    SequenceExecutionChamberBlockEntity rscc$getLinkedExecutor();

    /**
     * 本输出总线<b>正在供料的目标坐标</b>（= 它朝向那一面的相邻方块，也就是机器 / 置物台；未绑定 /
     * 朝向未知 / 客户端一律返回 {@code null}）。
     * <p><b>为什么需要它</b>：执行舱要能回答「我现在在给哪些机器供料」（见
     * {@code SequenceExecutionChamberBlockEntity#busSupplyTargets()}），而「供料目标」这件事只有输出总线
     * 自己知道（它的朝向）。输入总线据此从这些机器的输出侧把机器产出收回网络
     * （见 {@code RsccChamberImportStrategy}），因此这是「输入总线贴着机器放就能用」这条链路的
     * 唯一数据来源。只读、逐次计算（读方块状态推朝向），不持有任何引用。</p>
     */
    @Nullable
    BlockPos rscc$supplyTargetPos();

    /**
     * 关联执行舱的「自动合成」是否已开启（服务端权威；未绑定执行仓 / 客户端一律返回 {@code false}）。
     * <p>未开启时本输出总线的导出清单必然为空（执行仓不给过滤项），界面据此显示提示。</p>
     */
    boolean rscc$isAutoCrafting();

    /**
     * 服务端逐 tick 驱动（由「网络节点容器方块实体」的 tick 入口注入统一调用）。
     * <p><b>用途</b>：把「延长模式策略的安装」从方块实体的 {@code initialize}（初始化阶段，不能探测邻块）
     * 挪到安全的 tick 时机做<b>懒安装</b> —— 首次 tick 解析归属并安装，装好后不再重复；
     * 未绑定时保留 RS 原版策略并留待下次 tick 重试。</p>
     * <p>客户端调用是空操作（内部会按 {@code ServerLevel} 判定）。</p>
     */
    void rscc$serverTick();
}
