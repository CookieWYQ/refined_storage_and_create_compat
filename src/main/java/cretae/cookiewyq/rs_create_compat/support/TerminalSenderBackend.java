package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第 131 轮：{@link SenderBackend} 的<b>终端物品</b>实现 —— 高级远程多功能终端的第 8 个模式
 * 「发送」（{@link AdvancedRemoteTerminalItem#MODE_SEND}）的后端。
 *
 * <h2>为什么是「同一套界面 + 另一个后端」而不是新界面</h2>
 * <p>用户要求「给我们的终端再加一个功能，就是也支持这一个网络资源发送器的功能」。
 * 发送这件事（选资源 / 订单清单 / 目标选择 / 缺口询问 / 守恒投递）在请求器那边已经是一整套，
 * 这里只换两件事：<b>状态存在哪儿</b>（方块实体 NBT → 终端物品 {@code CustomData}）与
 * <b>网络从哪儿来</b>（方块自己的节点 → 终端物品绑定的网络）。
 * 界面（{@code ResourcePickerMenu} / {@code ResourcePickerScreen}）、订单编辑校验
 * （{@code RequesterSenderBackend} 的静态方法）、投递引擎（{@link SenderDelivery}）、
 * 缺口询问（{@link SenderShortage}）<b>一行都不复制</b> —— 两条路径共用同一份实现，
 * 因此不可能出现「终端少校验一道」或「两套投递语义漂移」。</p>
 *
 * <h2>{@link #isConnected()} 的语义为什么是「界面能不能开」</h2>
 * <p>契约 §3.D4 要求：「终端失效（不在背包/饰品槽、没电、网络断开）⇒ <b>界面照开</b>但发送被拒并说明」。
 * 而共享件 {@code ResourcePickerMenu#openFor} 的第一行就是 {@code if (!backend.isConnected()) return false;}
 * —— 那个守卫是为<b>请求器方块</b>写的（方块没接网络时开界面确实没有意义）。
 * 终端这边的语义不同：界面里的订单 / 目标都存在<b>物品</b>上，没网也能编辑、能准备，
 * 所以这里把「能不能开界面」与「能不能发送」<b>拆成两条判据</b>：</p>
 * <ul>
 *     <li>{@link #isConnected()} = 终端物品此刻还在玩家身上（现场定位，<b>不缓存</b> {@link ItemStack}）；
 *     ⇒ 界面照开（没网时网格是灰空态，与终端其它模式「绑空网络照开」完全一致）；</li>
 *     <li>「网络可达 + 网络有电 + 目标唯一 + 订单非空」全部在 {@link #trigger(ServerPlayer)} 里
 *     逐条检查，每条都给一句<b>可见</b>的拒绝原因（actionbar，绝不静默）。</li>
 * </ul>
 * <p>这个取舍已与 Lead 对齐（用户原话级要求优先于共享件里为方块写的守卫）。</p>
 *
 * <h2>状态为什么存在物品 {@code CustomData} 里</h2>
 * <p>终端是<b>物品</b>：世界中没有对应的方块实体。订单 / 目标若只放内存，关界面即丢；
 * 若放静态表，就与「这台终端」解绑（换一台终端、放进箱子再拿出来都会串味）。
 * 因此写进物品自身的数据组件（键 {@link #TAG_ORDERS} / {@link #TAG_TARGET}），
 * <b>跟着物品走</b>：开界面读回、改了就写回；与方块请求器「状态跟着方块走」完全等价。</p>
 * <p>序列化<b>复用 RS 自己的</b> {@code ResourceCodecs.AMOUNT_CODEC}（{@code {resource, amount}} 列表），
 * 与请求器方块实体的 {@code Orders} 键同一份 codec —— 物品 / 流体 / 装了官方联动后的气体
 * 全部走同一条编解码路径，不自己拼 {@code key}/{@code amount} 两个字段。</p>
 *
 * <h2>终端没有「tick 源」，两处差异（都是刻意的，不是漏做）</h2>
 * <ol>
 *     <li><b>跨 tick 续投</b>：方块每 tick 有 {@code doWork()}，终端没有。所以一次触发在<b>一次调用内</b>
 *     按轮把钱投完（每轮 = {@code batchesPerTick()} 批预算），总轮数封顶
 *     {@link #MAX_TRIGGER_ROUNDS} 轮；仍没投完（= 单次发送 &gt; 512 批 ≈ 3.2 万件）时明确提示
 *     「还有多少没投」，绝不假装成功；</li>
 *     <li><b>合成后补投</b>（{@code PendingSenderSends}）：它的驱动器挂在请求器方块的 {@code doWork()} 上，
 *     且 {@code register(...)} 只接受 {@code RequesterSenderBackend} ⇒ <b>终端暂不补投</b>。
 *     用户选「合成 X 并发送 Y」时：现有部分照常立刻投出、合成任务照常启动（{@link SenderShortage} 共用），
 *     只是合成完之后的那一段不会自动送来 —— 完成消息里已把这条限制写明。</li>
 * </ol>
 *
 * <h2>回滚失败（守恒的最后一道）</h2>
 * <p>{@link SenderDelivery} 在「接收器没吃下全部」时会把余量写回 RS 网络；网络也拒收的那部分
 * 由 {@link SenderDelivery.Feedback#buffer} 交回调用方。请求器方块有落盘的 {@code returnBuffer}
 * 且每 tick 重试；终端没有 tick 源，因此这里：<b>物品直接还给玩家背包</b>（背包满就掉在脚边，
 * 一件不销毁）；<b>非物品（流体等）挂进内存重试表</b>，下次发送时先试着归还网络。
 * 重试表不落盘 —— 这是终端这条链路能做到的最小代价，且该情形在引擎里近乎不可达
 * （回滚的是刚取出来的量，同一张网络通常立刻收得回去）。</p>
 */
public final class TerminalSenderBackend implements SenderBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerminalSenderBackend.class);

    /** 订单清单在终端物品 {@code CustomData} 里的键（契约 §3.D3）。 */
    public static final String TAG_ORDERS = "RsccSenderOrders";
    /** 目标接收器名字在终端物品 {@code CustomData} 里的键（契约 §3.D3）。 */
    public static final String TAG_TARGET = "RsccSenderTarget";

    /** 与请求器方块实体 {@code Orders} 键<b>同一份</b> codec（物品 / 流体 / 第三方资源统一）。 */
    private static final Codec<List<ResourceAmount>> ORDER_CODEC = ResourceCodecs.AMOUNT_CODEC.listOf();

    /**
     * 一次 {@link #trigger(ServerPlayer)} 内最多投几轮（每轮重新领一整份 {@code batchesPerTick()} 批预算）。
     * <p>8 轮 × 8 批 × 64 件 ≈ 4096 件；取 64 轮 = 512 批 ≈ 3.2 万件，覆盖「一次性把手里的东西全发走」
     * 的常见规模，同时把单次调用的主线程成本钉在一个可预算的上界里（方块那边是靠跨 tick 分摊的）。</p>
     */
    private static final int MAX_TRIGGER_ROUNDS = 64;

    /** 终端消息文案的命名空间（Lead 统一写进 lang；缺失时下面的中文兜底仍会显示，绝不静默）。 */
    public static final String LANG_MSG = "gui.rs_create_compat.terminal_send.msg.";

    /** 「终端已经不在身上」——打开阶段就拒绝的那一句（Lead 要求：别让界面开出来再被关掉）。 */
    public static final String MSG_NO_TERMINAL = LANG_MSG + "no_terminal";
    public static final String MSG_NO_NETWORK = LANG_MSG + "no_network";
    public static final String MSG_NO_POWER = LANG_MSG + "no_power";
    public static final String MSG_UNCONFIGURED = LANG_MSG + "unconfigured";
    public static final String MSG_NO_ORDERS = LANG_MSG + "no_orders";
    public static final String MSG_PENDING_CARRY = LANG_MSG + "pending_carry";
    public static final String MSG_ORDER_NOT_SAVED = LANG_MSG + "order_not_saved";
    public static final String MSG_BUFFERED_TO_PLAYER = LANG_MSG + "buffered_to_player";
    public static final String MSG_BUFFERED_RETRY_LATER = LANG_MSG + "buffered_retry_later";

    /**
     * 回滚失败的<b>非物品</b>资源重试表（玩家 UUID → 待归还网络的数量）。
     * <p>只在服务端主线程读写；不落盘（见类注释「回滚失败」一节）。</p>
     */
    private static final Map<UUID, List<ResourceAmount>> ROLLBACK_RETRY = new ConcurrentHashMap<>();

    /** 打开这张界面的玩家（服务端；客户端重建的界面里没有本对象，见 {@code SenderBackend.Holder}）。 */
    private final ServerPlayer player;
    /** 打开界面时那台终端的位置引用（<b>不存 {@link ItemStack}</b>：背包内容包会把它换成新实例）。 */
    private final SlotReference slotReference;

    /** 订单清单（内存镜像；权威副本在物品 {@code CustomData} 里，见 {@link #save()}）。 */
    private final List<ResourceAmount> orders = new ArrayList<>();
    /** 目标接收器名字（空串 = 未配置）。 */
    private String targetName = "";
    /** 上一次触发的结论（只服务界面显示，不落盘：它是「这一刻」的事实，不是配置）。 */
    private boolean lastTriggerSucceeded;

    private TerminalSenderBackend(final ServerPlayer player, final SlotReference slotReference) {
        this.player = player;
        this.slotReference = slotReference;
        load();
    }

    // ==================== 打开 / 失效 ====================

    /**
     * 服务端打开入口：终端物品还在 ⇒ 建好后端并<b>从物品 {@code CustomData} 读回</b>订单 / 目标。
     *
     * <p>返回 {@code null} 表示「这台终端在打开这一刻就已经不在身上了」——
     * 调用方必须<b>在打开界面之前</b>就给出提示并返回（Lead 明确要求：别让界面开出来再被
     * {@code stillValid} 关掉，玩家只会看到「一闪就没」）。</p>
     */
    @Nullable
    public static TerminalSenderBackend open(final ServerPlayer player,
                                             final SlotReference slotReference) {
        if (player == null || slotReference == null) {
            return null;
        }
        final TerminalSenderBackend backend = new TerminalSenderBackend(player, slotReference);
        return backend.liveStack().isEmpty() ? null : backend;
    }

    /**
     * 现取「这台终端此刻的存活栈」。
     *
     * <p><b>绝不缓存 {@link ItemStack}</b>（{@code TerminalModeTabOverlay} 的类注释里写尽了这个坑）：
     * Minecraft 打开容器时服务端先发开屏包、紧接着发内容包，客户端把每个槽位的 {@code ItemStack}
     * 换成新解码出来的实例 ⇒ 初始化那一刻抓到的引用几毫秒后就不在背包里了，往它身上写等于没写。
     * 这里每次都<b>按引用现取</b>；引用失效（物品被搬走 / 被销毁 / 换槽位）时才按服务端固定优先级
     * 重新定位一次（{@link RsccTerminalLocator#locate}）—— 与快捷键打开、模式 Tab 读模式<b>同一套</b>顺序。</p>
     */
    private ItemStack liveStack() {
        final Optional<ItemStack> resolved = slotReference.resolve(player);
        if (resolved.isPresent() && isTerminal(resolved.get())) {
            return resolved.get();
        }
        return RsccTerminalLocator.locate(player)
            .map(RsccTerminalLocator.Location::stack)
            .filter(TerminalSenderBackend::isTerminal)
            .orElse(ItemStack.EMPTY);
    }

    /** 只看类型不看型号（普通 / 满电 / 创造三版终端一视同仁）。 */
    private static boolean isTerminal(@Nullable final ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && stack.getItem() instanceof AdvancedRemoteTerminalItem;
    }

    // ==================== SenderBackend：身份 / 网络 ====================

    /**
     * 本发送源此刻接入的网络 = <b>终端物品绑定的那张 RS 网络</b>。
     * <p>解析口径唯一实现 = {@link AdvancedRemoteTerminalItem#resolveBoundNetwork}：它正是终端打开
     * 其它模式界面时往虚拟方块实体里绑的那一条路径（绑定位置存在、区块已加载、方块是网络节点、
     * 节点已接入网络），因此「界面拿到的是哪张网络」与「发送用哪张网络」不可能分叉。</p>
     */
    @Override
    @Nullable
    public Network network() {
        final ItemStack stack = liveStack();
        if (stack.isEmpty() || player == null) {
            return null;
        }
        return AdvancedRemoteTerminalItem.resolveBoundNetwork(player, stack).orElse(null);
    }

    /**
     * 判据 = <b>「这个发送源现在能不能把发送界面开出来」</b> = 终端物品还在身上（现场定位）。
     * <p>为什么不等于「网络可达 + 有电」：见类注释「{@code isConnected()} 的语义」一节 ——
     * 契约 §3.D4 要求终端失效时界面照开、发送被拒并说明。真正的发送前置在
     * {@link #trigger(ServerPlayer)} 里逐条检查。</p>
     */
    @Override
    public boolean isConnected() {
        return !liveStack().isEmpty();
    }

    /**
     * 服务端：这名玩家此刻还能不能操作这台终端打开的界面（物品还在身上 ⇒ 有效）。
     * <p>用<b>现定位</b>而不是「打开时捕获的引用」：玩家把终端换槽、放进箱子、丢掉，
     * 下一次 {@code ServerPlayer#tick} 就会把这张界面关掉（{@code ResourcePickerMenu#stillValid}）。</p>
     */
    @Override
    public boolean stillValid(final ServerPlayer player) {
        if (player == null || player != this.player) {
            // 同一张菜单只属于打开它的那名玩家：别的玩家（理论上到不了，界面是私有的）一律拒绝
            return false;
        }
        return !liveStack().isEmpty();
    }

    // ==================== SenderBackend：订单 / 目标 ====================

    @Override
    public List<ResourceAmount> orders() {
        return List.copyOf(orders);
    }

    /**
     * 整表替换订单（服务端唯一的写入口；清洗规则与请求器方块<b>逐条相同</b>：
     * 丢非正数 / 丢非平台资源键 / 同资源合并 / 不设条数上限）。
     * <p>这里同时写回物品：{@code RequesterOrderPacket} 的每个动作后面都会再调一次 {@link #save()}，
     * 这里是第二道（幂等）保险 —— 写两次只会多花一次 NBT 拷贝，绝不会产生两份不同的状态。</p>
     */
    @Override
    public void setOrders(@Nullable final List<ResourceAmount> entries) {
        final List<ResourceAmount> cleaned = sanitize(entries);
        orders.clear();
        orders.addAll(cleaned);
        save();
    }

    @Override
    public void clearOrders() {
        if (orders.isEmpty()) {
            return; // 空表再清空是空操作（不白写一次物品 NBT）
        }
        orders.clear();
        save();
    }

    @Override
    public String targetName() {
        return targetName == null ? "" : targetName;
    }

    @Override
    public List<String> receiverNames() {
        return SenderDelivery.receiverNames(network());
    }

    /**
     * 写目标名字 —— <b>直接写，不在这里调 {@code RequesterSenderBackend.applyTarget}。</b>
     *
     * <p>为什么刻意不复用那条「校验 + 写」的静态入口：{@code applyTarget} 在<b>写入之前</b>会回调
     * {@code backend.setTargetName(...)}，若本方法再调回它就会形成无限递归（同一个坑在
     * {@code RequesterSenderBackend} 里踩过一次，已由 Lead 安排修复）。
     * 「名字形状 / 目标必须存在且唯一」的<b>唯一权威</b>是
     * {@code RequesterSenderBackend.applyTarget}，{@code SetRequesterTargetPacket} 走的正是它；
     * 本方法只负责「把这个已经被校验过的名字落到物品上」。</p>
     */
    @Override
    public boolean setTargetName(final ServerPlayer player, @Nullable final String name) {
        final String value = name == null ? "" : name.trim();
        if (value.equals(targetName)) {
            return true;
        }
        targetName = value;
        save();
        return true;
    }

    // ==================== SenderBackend：发送 ====================

    @Override
    public void trigger(final ServerPlayer player) {
        triggerWithPlan(player, null);
    }

    /**
     * 投递的<b>唯一实现</b>（界面「发送」按钮 / 缺口询问的「只投现有部分」共用）。
     *
     * <p>闸门顺序与请求器方块的 {@code triggerRequest} 一一对应：未配置 → 网络 → 电量 →
     * 目标唯一 → 订单非空 → 缺口询问；任何一步失败都<b>不投递 + 给一句可见原因</b>。
     * 搬运本身全部交给 {@link SenderDelivery#deliver}（守恒 / 回滚 / 批节拍一份实现）。</p>
     *
     * @param plan {@code null} = 按整张订单投；非 null = 只投这份计划（缺口询问之后的「先投现有部分」）
     */
    @Override
    public void triggerWithPlan(final ServerPlayer player, @Nullable final List<ResourceAmount> plan) {
        lastTriggerSucceeded = false;
        if (player == null || player.level().isClientSide()) {
            return; // 只有服务端权威世界才投递（客户端没有网络 / 不能改物品）
        }
        final ItemStack stack = liveStack();
        if (stack.isEmpty()) {
            hint(MSG_NO_TERMINAL);
            return;
        }
        // F0：配置判定在最前 —— 「配置是否完整」必须先于一切带副作用的动作（与请求器同一顺序）
        if (targetName().isEmpty()) {
            hint(MSG_UNCONFIGURED);
            return;
        }
        final Network network = network();
        if (network == null) {
            hint(MSG_NO_NETWORK);
            return;
        }
        // 绑定网络没电 ⇒ 不投递（判据唯一实现 = TerminalNetworkPower，与「Mode Tab 置灰」同源）。
        // 配置（目标 / 订单）一个字都不动，恢复供电后再点发送即可。
        if (!TerminalNetworkPower.isPowered(player, stack)) {
            hint(MSG_NO_POWER);
            return;
        }
        // 上一次回滚失败、暂时挂着的资源：先归还网络（一件都不丢，见类注释「回滚失败」）
        retryRollbackRetryTable(network);
        // F2：0 / 1 / ≥2 三种结果，重名绝不随机挑一台（解析口径唯一实现 = SenderDelivery）
        final String target = targetName();
        final List<NetworkResourceReceiverBlockEntity> hits =
            SenderDelivery.resolveReceiversByName(network, target);
        if (hits.size() > 1) {
            hint(stateFail("ambiguous"), hits.size(), target);
            return;
        }
        if (hits.isEmpty()) {
            hint(stateFail("not_found"), target);
            return;
        }
        // F3：没有订单就没有可投的东西
        final List<ResourceAmount> source = sanitize(plan != null ? plan : orders);
        if (source.isEmpty()) {
            hint(MSG_NO_ORDERS);
            return;
        }
        // F 组：有缺口且可自动合成 ⇒ 先不发，向玩家询问（询问实现与请求器共用 SenderShortage）。
        // 只在「按整张订单投」时问：plan != null 的那一次正是询问之后的「投现有部分」，再问就会死循环。
        if (plan == null && SenderShortage.ask(player, this)) {
            return; // 询问已发出：本轮一件都不投（用户要求「先不发」）
        }
        deliver(network, hits.get(0), source);
    }

    @Override
    public boolean lastTriggerSucceeded() {
        return lastTriggerSucceeded;
    }

    /**
     * 打开界面时「这台终端此刻还不能发送」的<b>第一条</b>原因（完整语言键），全都能发 ⇒ {@code null}。
     *
     * <p>为什么需要在<b>打开时</b>就有这一条：契约 §3.D4 要求终端失效时「界面照开但发送被拒并说明」。
     * 界面能开（{@link #isConnected()} 恒真）就意味着玩家一进来只看到一张灰网格 / 空清单；
     * 让他点一次「发送」才知道原因，体验上仍然是「点了没反应」。这里把这条原因交给
     * {@code ResourcePickerMenu} 的底部提示行（与订单编辑提示同一处、同一种红字），
     * 玩家一进界面就能看到「没绑定网络 / 没电 / 没选目标 / 订单是空的」。</p>
     *
     * <p>判据与 {@link #triggerWithPlan} 的闸门<b>同源同序</b>（终端在 → 未配置 → 网络 → 电量 →
     * 订单），因此底部提示行说的话与点「发送」得到的拒绝原因不可能互相矛盾。</p>
     */
    @Nullable
    public String firstIssueKey() {
        if (liveStack().isEmpty()) {
            return MSG_NO_TERMINAL;
        }
        if (targetName().isEmpty()) {
            return MSG_UNCONFIGURED;
        }
        if (network() == null) {
            return MSG_NO_NETWORK;
        }
        final ItemStack stack = liveStack();
        if (!TerminalNetworkPower.isPowered(player, stack)) {
            return MSG_NO_POWER;
        }
        if (orders.isEmpty()) {
            return MSG_NO_ORDERS;
        }
        return null;
    }

    /** 固定节拍：与请求器<b>同一组常量</b>（用户拍板「红石激活多快就发多快」，不吃升级）。 */
    @Override
    public int batchesPerTick() {
        return SenderDelivery.FIXED_BATCHES_PER_TICK;
    }

    /** 固定批量：与请求器同一组常量（不吃叠加升级）。 */
    @Override
    public long batchSize() {
        return SenderDelivery.FIXED_BATCH_SIZE;
    }

    // ==================== 持久化：物品 CustomData ====================

    /**
     * 把内存状态写回<b>终端物品</b>（{@link SenderBackend#save()} 的唯一实现）。
     * <p>编码失败（理论上不会：条目已在 {@link #sanitize} 里收敛成 {@link PlatformResourceKey}）
     * 时<b>不覆盖旧键</b>并明确提示 —— 绝不用「编码失败 ⇒ 清空」把玩家的订单悄悄吃掉。</p>
     */
    @Override
    public void save() {
        final ItemStack stack = liveStack();
        if (stack.isEmpty() || player == null) {
            return;
        }
        final Tag encoded;
        try {
            encoded = ORDER_CODEC.encodeStart(ops(), List.copyOf(orders)).result().orElse(null);
        } catch (final RuntimeException exception) {
            LOGGER.warn("[rs_create_compat] 终端发送订单编码失败，保留旧数据", exception);
            hint(MSG_ORDER_NOT_SAVED);
            return;
        }
        if (encoded == null) {
            hint(MSG_ORDER_NOT_SAVED);
            return;
        }
        final String target = targetName == null ? "" : targetName;
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
            data -> data.update(tag -> {
                tag.put(TAG_ORDERS, encoded);
                tag.putString(TAG_TARGET, target);
            }));
    }

    /** 开界面时从物品 {@code CustomData} 读回（坏数据 ⇒ 空清单 / 空目标，绝不抛异常）。 */
    private void load() {
        final ItemStack stack = liveStack();
        if (stack.isEmpty() || player == null) {
            return;
        }
        final CompoundTag tag;
        try {
            tag = customDataCopy(stack);
        } catch (final RuntimeException exception) {
            LOGGER.warn("[rs_create_compat] 终端发送数据读取失败，按空配置打开", exception);
            return;
        }
        orders.clear();
        targetName = tag == null ? "" : tag.getString(TAG_TARGET);
        if (tag == null || !tag.contains(TAG_ORDERS)) {
            return; // 全新终端：空订单 + 无目标（原样打开，不报错）
        }
        try {
            final Optional<List<ResourceAmount>> parsed =
                ORDER_CODEC.parse(ops(), tag.get(TAG_ORDERS)).result();
            if (parsed.isPresent()) {
                orders.addAll(sanitize(parsed.get()));
            }
        } catch (final RuntimeException exception) {
            // 解不开 ⇒ 保持空清单（绝不抛异常、绝不静默改成别的资源）
            LOGGER.warn("[rs_create_compat] 终端发送订单解析失败，按空清单处理", exception);
        }
    }

    /** 物品自定义数据的只读副本（读不修改原 tag；没有该组件 ⇒ {@code null}）。 */
    @Nullable
    private static CompoundTag customDataCopy(final ItemStack stack) {
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    /** NBT 编解码的 ops（带注册表上下文：资源键里的数据组件需要它；与方块实体同一做法）。 */
    private DynamicOps<Tag> ops() {
        return RegistryOps.create(NbtOps.INSTANCE, player.level().registryAccess());
    }

    // ==================== 投递 ====================

    /**
     * 按轮把 {@code source} 投完（每轮一整份预算），返回前把结论写进 {@link #lastTriggerSucceeded}。
     * <p>终止条件两条：① 计划投完；② 某一轮<b>一件都没投出去</b>（说明卡住，绝不空转）。
     * 轮数封顶 {@link #MAX_TRIGGER_ROUNDS}；到顶仍有余量 ⇒ 明确提示还有多少没投（绝不假装成功）。</p>
     */
    private void deliver(final Network network,
                         final NetworkResourceReceiverBlockEntity receiver,
                         final List<ResourceAmount> source) {
        long movedTotal = 0L;
        boolean shortfall = false;
        List<ResourceAmount> carry = source;
        int rounds = 0;
        while (!carry.isEmpty() && rounds < MAX_TRIGGER_ROUNDS) {
            final SenderDelivery.Result result = SenderDelivery.deliver(network, sink(receiver), carry,
                batchesPerTick(), batchSize(), 0, feedback());
            movedTotal += result.moved();
            shortfall |= result.shortfall();
            final List<ResourceAmount> next = result.carry();
            rounds++;
            if (result.moved() <= 0L) {
                // 一点都没投出去：引擎已经 hint 过具体原因（没料 / 吃不下 / 异常），再转圈只会刷屏
                carry = next;
                break;
            }
            carry = next;
        }
        // 「成功」判据与请求器一致：真的投出过东西，且没有出现缺口
        lastTriggerSucceeded = movedTotal > 0L && !shortfall;
        if (!carry.isEmpty()) {
            hint(MSG_PENDING_CARRY, sum(carry));
        }
    }

    /** 把接收器适配成投递引擎的 sink（往哪儿写 / 能收多少由接收器自己回答）。 */
    private static SenderDelivery.ReceiverSink sink(final NetworkResourceReceiverBlockEntity receiver) {
        return new SenderDelivery.ReceiverSink() {
            @Override
            public long apply(final ResourceKey resource, final long amount, final boolean simulate) {
                return receiver.applyResource(resource, amount, simulate);
            }

            @Override
            public boolean canHandle(final ResourceKey resource) {
                return receiver.canHandle(resource);
            }

            @Override
            public String displayName() {
                return receiver.getReceiverDisplayName();
            }
        };
    }

    private SenderDelivery.Feedback feedback() {
        return new SenderDelivery.Feedback() {
            @Override
            public void hint(final String reason, final Object... args) {
                TerminalSenderBackend.this.hint(reason, args);
            }

            @Override
            public void buffer(final ResourceKey resource, final long amount) {
                keepRolledBack(resource, amount);
            }
        };
    }

    /**
     * 回滚失败（网络拒收刚取出来的量）时的最后归宿：<b>物品进玩家背包</b>（背包满就掉脚边），
     * 非物品挂进内存重试表等下次发送时归还。<b>一件都不销毁</b>。
     */
    private void keepRolledBack(@Nullable final ResourceKey resource, final long amount) {
        if (resource == null || amount <= 0L || player == null) {
            return;
        }
        if (resource instanceof ItemResource item) {
            final int maxStack = Math.max(1, item.toItemStack(1).getMaxStackSize());
            long rest = amount;
            while (rest > 0L) {
                final int chunk = (int) Math.min(rest, (long) maxStack);
                final ItemStack stack = item.toItemStack(chunk);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false); // 背包满：掉在玩家脚边（不销毁）
                }
                rest -= chunk;
            }
            player.getInventory().setChanged();
            hint(MSG_BUFFERED_TO_PLAYER, SenderDelivery.describeResource(resource), amount);
            return;
        }
        ROLLBACK_RETRY.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>())
            .add(new ResourceAmount(resource, amount));
        hint(MSG_BUFFERED_RETRY_LATER, SenderDelivery.describeResource(resource), amount);
    }

    /** 先把上次回滚失败挂着的资源归还网络（能还多少还多少，剩下的原样留着）。 */
    private void retryRollbackRetryTable(final Network network) {
        if (player == null) {
            return;
        }
        final List<ResourceAmount> pending = ROLLBACK_RETRY.get(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return;
        }
        for (int index = pending.size() - 1; index >= 0; index--) {
            final ResourceAmount held = pending.get(index);
            final long returned = storage.insert(held.resource(), held.amount(), Action.EXECUTE, Actor.EMPTY);
            if (returned <= 0L) {
                continue; // 网络仍不收：原样留着，下次再试（绝不销毁）
            }
            final long rest = held.amount() - returned;
            if (rest <= 0L) {
                pending.remove(index);
            } else {
                pending.set(index, new ResourceAmount(held.resource(), rest));
            }
        }
        if (pending.isEmpty()) {
            ROLLBACK_RETRY.remove(player.getUUID());
        }
    }

    // ==================== 清洗（与请求器方块逐条同一规则） ====================

    /**
     * 订单清洗：跳过 {@code null} / 数量 ≤ 0 / 非 {@link PlatformResourceKey} 的条目，
     * 同资源<b>合并数量</b>（饱和相加防溢出），<b>不设条数上限</b>，绝不静默丢玩家已选中的条目。
     */
    private static List<ResourceAmount> sanitize(@Nullable final List<ResourceAmount> entries) {
        final Map<PlatformResourceKey, Long> merged = new LinkedHashMap<>();
        if (entries != null) {
            for (final ResourceAmount entry : entries) {
                if (entry == null || entry.amount() <= 0L
                    || !(entry.resource() instanceof PlatformResourceKey key)) {
                    continue;
                }
                merged.merge(key, entry.amount(),
                    (left, right) -> left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right);
            }
        }
        final List<ResourceAmount> out = new ArrayList<>(merged.size());
        for (final Map.Entry<PlatformResourceKey, Long> entry : merged.entrySet()) {
            out.add(new ResourceAmount(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(out);
    }

    private static long sum(final List<ResourceAmount> entries) {
        long total = 0L;
        for (final ResourceAmount entry : entries) {
            if (entry == null || entry.amount() <= 0L) {
                continue;
            }
            total = total > Long.MAX_VALUE - entry.amount() ? Long.MAX_VALUE : total + entry.amount();
        }
        return total;
    }

    // ==================== 提示（服务端发 key，界面 / actionbar 自己翻译） ====================

    /** 投递引擎的通用失败原因：<b>复用请求器已存在的那套文案</b>（同一件事只有一套说法）。 */
    private static String stateFail(final String reason) {
        return "gui.rs_create_compat.network_resource_requester.fail." + reason;
    }

    /**
     * 给玩家一条可见提示（actionbar：界面开着时聊天框常常被忽略，actionbar 一定在视野里）。
     * <p>文案取「语言键 + 中文兜底」：Lead 统一落 lang 之前也不会显示裸键名，</p>
     * 也绝不会出现「点了没反应」这种无出口结局。</p>
     */
    private void hint(final String keyOrReason, final Object... args) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        final String key = resolveKey(keyOrReason);
        final Component message = Component.translatableWithFallback(key, fallbackFor(key), args)
            .withStyle(ChatFormatting.RED);
        player.displayClientMessage(message, true);
        // 第 131 轮 round122 判据 E1（以及工程硬规则）：任何打印点必须过「唯一闸门」
        // RsccAssemblyDebug.isEnabled()（`Config.devLogs` 只是配置镜像值，工程明确禁止拿它当判据）。
        // 这条虽然只在玩家每次操作时打一条，但连点发送同样能刷屏；发布版默认安静。
        if (cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug.isEnabled()) {
            LOGGER.debug("[rs_create_compat][terminal-send] {} args={}", key,
                java.util.Arrays.toString(args));
        }
    }

    /** 传入的可能是完整语言键（本类常量）或裸 reason（投递引擎的 reason）：统一成完整键。 */
    private static String resolveKey(final String keyOrReason) {
        if (keyOrReason.startsWith("gui.") || keyOrReason.startsWith("item.")) {
            return keyOrReason;
        }
        return switch (keyOrReason) {
            case "no_terminal" -> MSG_NO_TERMINAL;
            case "no_network" -> MSG_NO_NETWORK;
            case "no_power" -> MSG_NO_POWER;
            case "unconfigured" -> MSG_UNCONFIGURED;
            case "no_orders" -> MSG_NO_ORDERS;
            case "order_not_saved" -> MSG_ORDER_NOT_SAVED;
            case "buffered_to_player" -> MSG_BUFFERED_TO_PLAYER;
            case "buffered_retry_later" -> MSG_BUFFERED_RETRY_LATER;
            case "pending_carry" -> MSG_PENDING_CARRY;
            default -> stateFail(keyOrReason);
        };
    }

    /**
     * 中文兜底文案（语言文件里已有对应 key 时自动让位给语言文件）。
     * <p>请求器那一族失败原因的兜底与 {@code NetworkResourceRequesterBlockEntity#hintFallback}
     * 逐条同义；终端这一族是本轮新增，措辞按「终端 / 绑定网络」的口径写（请求器文案里写的
     * 「请求器」对终端是错的，所以这几条必须单独一套）。</p>
     */
    private static String fallbackFor(final String key) {
        if (key.startsWith(LANG_MSG)) {
            return switch (key.substring(LANG_MSG.length())) {
                case "no_terminal" -> "这台高级远程终端已经不在你身上，无法发送";
                case "no_network" -> "这台终端还没有绑定可用的 RS 网络，无法发送（先对着网络节点潜行右键绑定）";
                case "no_power" -> "绑定的网络没有电（或未激活），本轮不发送；订单与目标原样保留";
                case "unconfigured" -> "这台终端还没有选择目标接收器，无法发送";
                case "no_orders" -> "订单是空的：先在网格里点选要发送的资源";
                case "pending_carry" -> "单次发送上限已到，还有 %s 件没投出去；再点一次发送接着投";
                case "order_not_saved" -> "订单没能写进终端物品（编码失败），当前会话仍生效；请把日志发给作者";
                case "buffered_to_player" -> "网络拒收回滚的 %s ×%s，已放进你的背包 / 掉在脚边（不会销毁）";
                case "buffered_retry_later" -> "网络拒收回滚的 %s ×%s，已暂存在终端里，下次发送时重试归还网络";
                default -> "终端发送失败：" + key.substring(LANG_MSG.length());
            };
        }
        // 投递引擎 / 目标解析的通用原因（与请求器逐条同义，只是这里以玩家为中心显示）
        final String reason = key.substring(key.lastIndexOf('.') + 1);
        return switch (reason) {
            case "ambiguous" -> "网络里有 %s 台接收器都叫「%s」，已拒绝投递（不会随机挑一台）";
            case "not_found" -> "未找到名为「%s」的接收器（可能被拆除、改名，或不在本网络）";
            case "no_stock" -> "RS 网络里没有 %s，本轮跳过这一条";
            case "short_stock" -> "RS 网络里的 %s 不够：本轮要 %s 件，只投出 %s 件（不会重复投）";
            case "receiver_full" -> "接收器「%s」的缓存已经放不下 %s，本轮跳过这一条";
            case "unsupported" -> "接收器没有能接收 %s 的通道（不是物品 / 流体，且它朝向的容器不接受这种资源）";
            case "extract_failed" -> "从网络取 %s 失败（模拟放行、执行失败），本轮跳过这一条";
            case "partial" -> "接收器「%s」没吃下全部 %s（写入 %s、回滚 %s、取出 %s）";
            case "deliver_exception" -> "写入 %s 时目标容器抛异常；已回滚到网络 / 背包，不会销毁资源";
            case "no_storage" -> "RS 网络里没有可用的存储，无法取料";
            case "pending_lost" -> "投递目标「%s」已不可用（拆除 / 改名 / 断开网络），未投完的请求已作废";
            case "buffer_buffered" -> "网络拒收回滚的 %s ×%s，已交给玩家 / 暂存，绝不销毁";
            case "buffer_retry_failed" -> "暂存里的 %s 仍然无法归还网络（网络存储可能已满）";
            case "conservation_broken" -> "投递守恒校验失败（取出 %s / 写入 %s / 回滚 %s / 暂存 %s），请把日志发给作者";
            default -> "终端发送失败：" + reason;
        };
    }

    // ==================== 自检用的只读探针（不参与运行路径） ====================

    /**
     * 本后端是否还持有这台终端的订单（自检 / 调试用；<b>不</b>暴露 {@link ItemStack}）。
     * <p>刻意只返回布尔：外部拿不到物品引用，也就没有「缓存 ItemStack」这条退化路径。</p>
     */
    public boolean hasLoadedState() {
        return !orders.isEmpty() || !targetName().isEmpty();
    }
}
