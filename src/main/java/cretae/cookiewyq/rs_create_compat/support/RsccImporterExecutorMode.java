package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 桥接接口：由 Mixin（注入 RS 输入总线方块实体 {@code AbstractImporterBlockEntity}）实现，
 * 让其它代码类型安全地读写「延长型输入（序列执行仓回流模式）」的状态。
 *
 * <p><b>为什么需要它（用户需求）</b>：本模组把 RS 的输出总线延长成了「延长型输出」（输出总线
 * 从执行仓内部存储取料推给机器）。这里做它的<b>对称版</b>：让输入总线在<b>相邻或沿线缆连到一台
 * 处于「总线输出（延长型输出）」模式的执行仓</b>时进入「延长型输入」——不再只从它面对的容器吸取，
 * 而是把<b>相连执行仓内部存储里该类别的东西收回 RS 网络</b>，也就是接管原先由「回流总线」承担的
 * 回流职责（用户原话：「用这个方式就可以很方便地直接在这一步获取那种该收回的东西和不该收回的东西」）。</p>
 *
 * <p><b>检测方式</b>：与输出总线侧<b>完全同一份实现</b> ——{@link RsccWireLinkSearch} 从本机出发，
 * 只经过「可穿行集合」（RS 线缆 / 输出总线 / 输入总线，见 {@link RsccWireBlocks}）、不穿过机器，
 * 上限 64 步；六向相邻是「0 步线缆」的特例；尊重 {@link RsccCableCuts} 的扳手断开；结果缓存 20 tick，
 * 邻块事件驱动失效（见 {@link RsccBusLinkInvalidation}）。<b>归属必须唯一</b>（可达恰好 1 台且探查穷尽），
 * 否则判为「归属未确定」并停用延长型（与输出总线侧逐字同一条规则）。</p>
 *
 * <p><b>类别模型</b>：与输出总线共用 {@link RsccBusCategory}（id 规则、图标、界面控件全部一致），
 * 只是方向相反：输出总线的「已选」= 要从执行仓推给机器，输入总线的「已选」= 要从执行仓收回网络。</p>
 *
 * <p><b>默认值全自动</b>：收回方向默认<b>不需要玩家勾选任何类别</b> —— 自动收回「非输入类」的
 * 中间产物 / 成品 / 废料（见 {@link #rscc$isAutoCollect()}）；只有高级玩家显式打开「手动」开关后，
 * 才回到「只收回勾选的类别」。取货范围也不再只有执行舱内部存储：<b>同时</b>按类别从「本仓所供料的
 * 机器（输出总线面对的那些机器 / 置物台）的输出侧」取货，因此输入总线<b>贴着机器放</b>就能用
 * （见 {@code RsccChamberImportStrategy} 与
 * {@code SequenceExecutionChamberBlockEntity#busSupplyTargets()}）。</p>
 *
 * <p>与 {@link RsccExporterExecutorMode} 同理，本接口刻意放在 support 包：mixin 包被 mixins.json
 * 声明为 Mixin 专用包，普通类放在其中被外部直接引用会抛 {@code IllegalClassLoadError}。</p>
 */
public interface RsccImporterExecutorMode {
    /**
     * 本输入总线是否处在「延长型输入」的布局里（= 沿可穿行集合够得到<b>至少一台</b>
     * 「总线输出」模式的执行仓）。
     * <p><b>注意语义（本轮改造）</b>：为真只代表界面要显示类别条 / 可能显示「已停用」红条；
     * <b>能不能真的搬运</b>由 {@link #rscc$getLinkedExecutor()} 决定 —— 归属未确定时它是 {@code null}
     * （延长型被停用，退回普通输入总线）。与输出总线侧同一条规则，判定见 {@link RsccBusInterference}。</p>
     */
    boolean rscc$isExecutorMode();

    /**
     * <b>原始布局判定</b>：线缆是否够得到<b>至少一台</b>「总线输出」执行舱 ——
     * <b>不含</b>玩家那条「强制普通总线」开关（与输出总线侧逐字同一条规则）。
     * <p>「已停用红条 + 显示可达区域按钮」依据这个判定，因此把总线强制成普通界面后，
     * 只要归属确实未确定，红条与按钮照旧显示。</p>
     */
    boolean rscc$isLinkedLayout();

    /**
     * 玩家是否已把本条输入总线<b>强制为普通总线</b>（用户原话：「赶紧做我要的按钮」）。
     * <p>为真时一律回到普通输入输出总线界面（走 RS 原版「相邻容器 → 网络」策略）。</p>
     */
    boolean rscc$isForceNormalBus();

    /** 设置「强制普通总线」（服务端权威；C2S 包调用）。落盘 + 立刻按新归属重建策略。 */
    void rscc$setForceNormalBus(boolean force);

    /**
     * 最近一次归属判定的完整报告（服务端权威；客户端一律 {@link RsccBusInterference.Report#CLEAR}）。
     * <p>界面同步链路（容器菜单 Mixin）直接取它下发「是否已停用 / 可达几台 / 线缆簇 / 可达执行舱坐标」，
     * 与输出总线侧完全同一份语义。</p>
     */
    RsccBusInterference.Report rscc$linkReport();

    /** 本输入总线当前已选的类别 id（有序、去重；仅本机自己的请求，服务端权威）。 */
    List<String> rscc$getImportCategoryIds();

    /** 设置已选类别 id（服务端权威，来自输入总线界面上的类别开关；空表 = 什么都不收回）。 */
    void rscc$setImportCategoryIds(List<String> categoryIds);

    /**
     * <b>剪贴板：把本条总线的玩家配置写进 {@code tag}</b>（复制档；服务端调用）。
     * <p>写的是「玩家眼里这条总线的配置」：RS 过滤槽（含模糊模式）+ 类别勾选 + 「全自动收回」开关 +
     * 「强制普通总线」开关。字段含义与格式只有一份定义，见 {@link RsccBusConfig}。</p>
     */
    void rscc$writeBusConfig(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider);

    /**
     * <b>剪贴板：从 {@code tag} 读回配置并写入本机</b>（粘贴档；服务端权威）。
     * <p>实现必须先过 {@link RsccBusConfig#acceptsKind}（版本 + 种类）再写任何一个字段：
     * 校验不过就<b>一个字节都不写</b>（输入总线的配置不会被粘到输出总线上）。</p>
     */
    void rscc$readBusConfig(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider);

    /**
     * 本输入总线是否处于<b>全自动收回</b>模式（默认 {@code true}）。
     * <p><b>为什么默认全自动（用户要求）</b>：玩家不该为了收回中间产物 / 成品 / 废料去逐个勾选类别。
     * 全自动 = <b>自动收集「非输入类」</b>：中间产物（各个进度状态，含同 id 不同组件的过渡件）、
     * 成品、废料一律收回；输入原料类别一律不收回（避免把刚喂进去的料又抽回来）。
     * 关掉它 = 手动模式，退回「只收回玩家勾选的类别」的既有行为（给高级玩家用）。</p>
     */
    boolean rscc$isAutoCollect();

    /**
     * 设置「全自动收回」开关（服务端权威，来自输入总线界面上的自动 / 手动开关）。
     * <p>旧存档兼容：读档时若<b>不存在</b>该开关的持久化键，则按「显式勾选过任何类别 = 手动模式、
     * 否则全自动」推导（见实现类），因此旧存档里玩家的显式勾选不会被静默丢掉、也不会报错。</p>
     */
    void rscc$setAutoCollect(boolean auto);

    /**
     * 本输入总线应下发给界面的类别快照（有序）：每个类别带「本机是否已勾选收回」。
     * <p>客户端 / 未绑定时返回空列表。</p>
     */
    List<RsccBusCategory> rscc$getCategorySnapshot();

    /**
     * 重新检测「相邻 / 线缆相连」的执行仓并重建取料策略（执行仓切换输出模式、或本机被邻块事件叫醒时调用）。
     */
    void rscc$refreshExecutorMode();

    /**
     * <b>只读</b>：玩家打开本总线界面时刷新一次「归属解析结果」——<b>只作废并重算缓存</b>，
     * 绝不重装传输策略、绝不动任何搬运状态（{@link RsccExporterExecutorMode#rscc$refreshLinkForUi()}
     * 的对称实现，理由见那里：打开界面必须是只读行为）。
     */
    void rscc$refreshLinkForUi();

    /**
     * 「邻块失效事件」轻量失效：邻块的放置 / 破坏 / 被替换可能改变导线连通性，需要作废连接缓存。
     * <p><b>刻意不做同步搜索</b>：事件回调发生在世界正在被修改的过程中，此处只作废缓存 / 安装标记 /
     * 退避窗口，真正的重算留给下一 tick 的 {@link #rscc$serverTick()} 安全时机。客户端调用是空操作。</p>
     */
    void rscc$invalidateNeighborLink();

    /** 本输入总线最终绑定的执行仓坐标（未绑定 / 客户端一律返回 {@code null}）。 */
    @Nullable
    BlockPos rscc$linkedExecutorPos();

    /**
     * 本输入总线最终绑定的执行仓方块实体（未绑定 / 客户端一律 {@code null}）。
     * <p>延长模式下的「取货源」就是这台执行仓的<b>内部存储</b>（见 {@link RsccChamberImportStrategy}），
     * 因此策略需要拿到执行仓实体本身。逐次解析（走 20 tick 缓存），不持有引用 ——
     * 拆方块 / 切模式后自然解析为 {@code null}。</p>
     */
    @Nullable
    SequenceExecutionChamberBlockEntity rscc$getLinkedExecutor();

    /**
     * 服务端逐 tick 驱动（由「网络节点容器方块实体」的 tick 入口注入统一调用）。
     * <p><b>用途</b>：把「延长模式取料策略的安装」从方块实体的 {@code initialize}（初始化阶段，
     * 不能探测邻块）挪到安全的 tick 时机做<b>懒安装</b>。客户端调用是空操作。</p>
     */
    void rscc$serverTick();
}
