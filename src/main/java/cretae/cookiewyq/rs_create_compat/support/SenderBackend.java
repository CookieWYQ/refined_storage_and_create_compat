package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 第 131 轮：**发送源**（「把网络里的资源投给某台网络资源接收器」的那一侧）的统一后端。
 *
 * <h2>为什么需要这层抽象</h2>
 * <p>第 131 轮用户要求「终端也要支持网络资源发送器的功能」。发送这件事本来只属于
 * {@code NetworkResourceRequesterBlockEntity}（方块 + 红石脉冲）。若不抽出这一层，
 * 终端的发送模式就会变成<b>第二套投递实现</b>（订单解析 / 重名接收器拒绝 / 分批投递 /
 * 缺口回滚 / 红石语义各自一份），两套写法迟早对不上 —— 工程约定「同一条行为只有一份实现」。</p>
 * <p>因此 {@code ResourcePickerMenu} / {@code ResourcePickerScreen}（网格 + 订单清单 + 目标 + 发送）
 * 只认本接口，<b>零方块假设</b>；「请求器方块」与「终端物品」各自提供一个实现：</p>
 * <ul>
 *     <li>{@code RequesterSenderBackend}（发送器方块：状态存在方块实体 NBT）；</li>
 *     <li>{@code TerminalSenderBackend}（高级远程终端物品：状态存在物品 {@code CustomData}，
 *     网络由终端物品解析出的 RS 网络给出）。</li>
 * </ul>
 *
 * <h2>纪律（谁是权威）</h2>
 * <ul>
 *     <li>本接口的方法<b>只在服务端</b>被调用（客户端只拿快照画界面）；</li>
 *     <li>任何写操作（订单 / 目标名）必须<b>先校验归属与存在性</b>（{@link #stillValid(ServerPlayer)}），
 *     再改状态；</li>
 *     <li>订单数量、缺口、投递预算一律由服务端算，客户端传上来的数量只是「玩家想发多少」；</li>
 *     <li>{@link #save()} 是「把状态写回持久介质」的钩子（方块实体写 NBT / 终端写物品组件），
 *     调用方不必知道介质。</li>
 * </ul>
 */
public interface SenderBackend {
    /** 本发送源此刻接入的网络；未接网络 / 没电 / 已失效时返回 {@code null}。 */
    @Nullable
    Network network();

    /**
     * 本发送源此刻是否<b>真的</b>接在一个可用的 RS 网络上（界面能不能打开 / 发送能不能成功）。
     *
     * <p>为什么不能只看 {@link #network()} 是否为 {@code null}：RS 2.x 会给<b>每一个</b>网络节点
     * 建一个 {@code Network}（哪怕它一个邻居都没有，见
     * {@code NetworkBuilderImpl#initialize → mergeNetworksOfNodes →
     * findPivotNetworkForMerge(...).orElseGet(() -> createNetwork(pivot))}），
     * 而 {@code StorageNetworkComponent} 是「对每个 Network 都注册」的组件工厂 ——
     * 所以孤立方块上 {@code network() != null} <b>恒为真</b>。判据因此必须是
     * 「网络图里除了自己还有别的节点」+「网络有电」，由实现方各自按自己的节点算。</p>
     */
    boolean isConnected();

    /** 服务端：这名玩家此刻还能不能操作这个发送源（方块还在且够近 / 终端还在背包或饰品槽里）。 */
    boolean stillValid(ServerPlayer player);

    /**
     * 上一次 {@link #trigger(ServerPlayer)} 是否成功投出过东西（供界面显示结论）。
     * <p>默认 {@code false}：拿不出结论的实现（例如终端没有缓存结论）不该假装成功。</p>
     */
    default boolean lastTriggerSucceeded() {
        return false;
    }

    /** 服务端权威订单快照（顺序稳定，直接给界面列表用）。 */
    List<ResourceAmount> orders();

    /** 整表替换订单（服务端校验后调用；空表 = 清空）。 */
    void setOrders(@Nullable List<ResourceAmount> entries);

    /** 清空订单（等价于 {@code setOrders(List.of())}，单独留一个名字给按钮语义）。 */
    void clearOrders();

    /** 目标接收器名字（未设置为空串）。 */
    String targetName();

    /** 本网络里「可以用作目标」的接收器名字（已命名 + 可达；用于目标选择子界面）。 */
    List<String> receiverNames();

    /**
     * 设置目标接收器名字。
     *
     * @return 是否被接受（拒绝原因由实现方用 actionbar / 提示通道说明，绝不静默）
     */
    boolean setTargetName(ServerPlayer player, @Nullable String name);

    /**
     * 手动触发一次发送（与红石脉冲同一个入口）：解析目标 → 校验 → 分批投递。
     * <p>缺口 / 自动合成询问也在这里决定（见本轮 {@code SenderShortage} 支持类）。</p>
     */
    void trigger(ServerPlayer player);

    /**
     * 只投「这些」（第 131 轮 F 组）：玩家选「合成 X 并发送 Y」/「只发送现有」时，
     * 立刻投出去的是<b>夹过库存的那一份计划</b>，而不是整张订单（整张只会投出能投的部分，
     * 剩下的缺口已经交给 {@code PendingSenderSends} 补投）。
     *
     * <p>默认实现退回 {@link #trigger(ServerPlayer)}：拿不出「按计划投递」能力的发送源
     * 仍然是合法实现，只是 F 组的「先投现有部分」会退化成「照常发一轮」。有能力的实现要覆写它。</p>
     */
    default void triggerWithPlan(final ServerPlayer player, @Nullable final List<ResourceAmount> plan) {
        trigger(player);
    }

    /** 每 tick 允许的批数（第 131 轮起不再吃速度升级，固定值由实现给出）。 */
    int batchesPerTick();

    /** 每一批搬多少（物品 = 件，流体 = mB；不再吃叠加升级）。 */
    long batchSize();

    /** 把内存状态写回持久介质（方块实体 / 终端物品）。默认无动作。 */
    default void save() {
    }

    /**
     * 「我这张菜单背后是一个发送源」—— 由发送界面菜单实现的极小接口。
     *
     * <p>为什么放在接口里而不是让网络包去认具体的菜单类：{@code network/**} 只许引用
     * 服务端安全的类（专用服务端安全硬约束不许它引用本模组 {@code client} 包的类），
     * 而发送界面（{@code client/grid/ResourcePickerMenu}）在 client 包里。有了本接口，
     * 网络包只写「{@code player.containerMenu instanceof SenderBackend.Holder}」，
     * 请求器方块与终端物品两套菜单共用同一条解析路径，谁也不需要在包里被点名。</p>
     */
    interface Holder {
        /** 本菜单背后的发送源（客户端重建的镜像菜单恒为 {@code null}：客户端没有可写的服务端状态）。 */
        @Nullable
        SenderBackend senderBackend();
    }
}
