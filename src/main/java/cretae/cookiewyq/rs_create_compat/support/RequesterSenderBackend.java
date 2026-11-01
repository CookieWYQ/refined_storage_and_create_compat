package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 131 轮：{@link SenderBackend} 的<b>请求器方块</b>实现 —— 把「状态存在方块实体 NBT 里」这件事
 * 收进这一层，网格界面 / 网络包因此<b>零方块假设</b>。
 *
 * <h2>为什么这里还承担「服务端编辑入口」的静态方法</h2>
 * <p>订单增删改 / 目标设定 / 立即发送这几条<b>服务端校验链</b>本来在
 * {@code NetworkResourceRequesterMenu} 里。菜单类随「删除请求器设置界面」一起失去界面，
 * 而校验链不能跟着消失（它才是防伪造的那一层）。把它放在这里的原因：</p>
 * <ol>
 *     <li>这些方法只依赖 {@link SenderBackend}，与「哪种发送源」无关 —— 终端复用同一套校验，
 *     不会出现「终端少校一道归属」；</li>
 *     <li>{@code network/**} 只许引用服务端安全的类（专用服务端安全硬约束），而
 *     {@code client/grid/ResourcePickerMenu} 不能出现在包里；消息键 / 枚举 / 校验全部放这里，
 *     包类只做「解析出发送源 → 调这里」。</li>
 * </ol>
 */
public final class RequesterSenderBackend implements SenderBackend {
    /** 请求器方块实体（服务端唯一状态源）。 */
    private final NetworkResourceRequesterBlockEntity requester;

    public RequesterSenderBackend(final NetworkResourceRequesterBlockEntity requester) {
        this.requester = requester;
    }

    /** 底层方块实体（目标解析 / 调试用；界面层不该依赖它）。 */
    public NetworkResourceRequesterBlockEntity blockEntity() {
        return requester;
    }

    // ==================== SenderBackend ====================

    @Override
    @Nullable
    public Network network() {
        return requester.getNode() == null ? null : requester.getNode().getNetworkOrNull();
    }

    /**
     * 连通性判据（两条都要）：① 网络图里除了自己还有别的节点；② 网络有电（≥ 本机耗电）。
     *
     * <p>为什么不能只看 {@code network() != null}：见 {@link SenderBackend#isConnected()} 的说明
     * （RS 给孤立方块也建一个只有它自己的 {@code Network}）。这里与 RS 自己的
     * {@code AbstractBaseNetworkNodeContainerBlockEntity#calculateActive()} 同一口径。</p>
     */
    @Override
    public boolean isConnected() {
        return isConnectedToNetwork(requester);
    }

    @Override
    public boolean stillValid(final ServerPlayer player) {
        return stillValidFor(player, requester);
    }

    @Override
    public List<ResourceAmount> orders() {
        return requester.getOrders();
    }

    @Override
    public void setOrders(@Nullable final List<ResourceAmount> entries) {
        requester.setOrders(entries);
    }

    @Override
    public void clearOrders() {
        requester.clearOrders();
    }

    @Override
    public String targetName() {
        return requester.getTargetName() == null ? "" : requester.getTargetName();
    }

    @Override
    public List<String> receiverNames() {
        return SenderDelivery.receiverNames(network());
    }

    /**
     * <b>直接写</b>目标名（不校验、不广播）。
     *
     * <h2>P0 修（第 131 轮）：这里**绝不能**再调 {@link #applyTarget}</h2>
     * <p>旧写法是 {@code return applyTarget(player, this, name) == OK;}，而 {@code applyTarget}
     * 在校验通过后又会调 {@code backend.setTargetName(player, name)} 来写 —— 两边互调、
     * <b>没有基例</b>（写入前名字还没变，所以每次都重新进入 {@code applyTarget}），
     * 玩家一点「确定目标」就 {@code StackOverflowError} 打崩服务端。</p>
     * <p>现在的分工是单向的、不可能成环：</p>
     * <ul>
     *     <li>{@code applyTarget}（服务端唯一校验链）= 校验 → 调本方法写 → 回权威快照；</li>
     *     <li>本方法只做「写进方块实体 + 标脏」，<b>不做任何校验、不发任何包</b>；
     *     需要校验的调用方必须先过 {@code applyTarget}。</li>
     * </ul>
     */
    @Override
    public boolean setTargetName(final ServerPlayer player, @Nullable final String name) {
        if (!stillValid(player)) {
            return false;
        }
        requester.setTargetName(name);
        requester.setChanged();
        return true;
    }

    /**
     * 发送一次：直接进方块实体的唯一触发入口。
     *
     * <p><b>为什么不在这里做目标 / 连通性预检</b>：那些判据在方块实体的 {@code triggerRequest}
     * 里已经逐条存在（F0 配置 → F1 闸门 → 网络 → 电量 → F2 目标唯一 → F3 订单非空），
     * 这里再写一遍就是第二份判据；{@link #triggerFor} 的预检只是为了让界面能在
     * 「明显不成立」时立刻回一条快照，两条路径最终都落到同一个实现。</p>
     */
    @Override
    public void trigger(final ServerPlayer player) {
        requester.triggerRequest(player, null);
    }

    /**
     * 只投「这些」（F 组「合成并发送」/「只发送现有」的一半）：把夹过库存的计划交给方块实体，
     * 走与整张订单<b>完全相同</b>的投递引擎（{@code triggerRequest(计划)}），
     * 因此守恒 / 节拍 / 目标校验一条不少。
     */
    @Override
    public void triggerWithPlan(final ServerPlayer player, @Nullable final List<ResourceAmount> plan) {
        requester.triggerRequest(plan);
    }

    /** 固定节拍：用户拍板「红石激活多快就发多快」，不吃速度升级。 */
    @Override
    public int batchesPerTick() {
        return SenderDelivery.FIXED_BATCHES_PER_TICK;
    }

    /** 固定批量：不吃叠加升级。 */
    @Override
    public long batchSize() {
        return SenderDelivery.FIXED_BATCH_SIZE;
    }

    @Override
    public boolean lastTriggerSucceeded() {
        return requester.wasLastRequestSuccessful();
    }

    /** 写回持久介质 = 把方块实体标脏（NBT 由原版在存档时取走）。 */
    @Override
    public void save() {
        requester.setChanged();
    }

    // ==================== 服务端前置判据（唯一出处） ====================

    /** 服务端权威前置：方块实体在服务端世界里（客户端镜像菜单没有方块实体，也没有可写的世界）。 */
    public static boolean isServerAuthoritative(
        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        return requester != null && requester.getLevel() != null
            && !requester.getLevel().isClientSide();
    }

    /**
     * 请求器是否真的接在一个可用的 RS 网络上（网格能否打开 / 发送能否成功的唯一判据）。
     * <p>判据见 {@link #isConnected()} 的注释；{@code block/**}、{@code menu/**} 都只问这里，
     * 不各自复写。</p>
     */
    public static boolean isConnectedToNetwork(
        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        if (requester == null || requester.getLevel() == null || requester.getNode() == null) {
            return false;
        }
        final Network network = requester.getNode().getNetworkOrNull();
        if (network == null) {
            return false;
        }
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        if (graph == null) {
            return false;
        }
        boolean hasOtherNode = false;
        for (final NetworkNodeContainer container : graph.getContainers()) {
            if (container != null && container.getNode() != requester.getNode()) {
                hasOtherNode = true;
                break;
            }
        }
        if (!hasOtherNode) {
            return false;
        }
        final EnergyNetworkComponent energy = network.getComponent(EnergyNetworkComponent.class);
        return energy != null && energy.getStored() >= requester.getEnergyUsage();
    }

    /** 服务端：请求器方块还在、玩家离得够近（与右键开界面同一判据）。 */
    public static boolean stillValidFor(@Nullable final Player player,
                                        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        if (player == null || requester == null || requester.getLevel() == null) {
            return false;
        }
        return requester.getLevel().getBlockEntity(requester.getBlockPos()) == requester
            && player.distanceToSqr(requester.getBlockPos().getX() + 0.5,
            requester.getBlockPos().getY() + 0.5,
            requester.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    // ==================== 订单编辑（服务端唯一实现；终端复用同一套） ====================

    /** 已选资源的操作结果。除 {@code OK} 外一律<b>不写入</b>发送源。 */
    public enum OrderResult {
        /** 已应用。 */
        OK,
        /** 资源标识无法解析成任意 {@link PlatformResourceKey}（未注册名 / 坏令牌 / 非平台键）。 */
        INVALID_RESOURCE,
        /** 数量 ≤ 0（上限由输入控件夹到 int）。 */
        INVALID_AMOUNT,
        /** 下标越界（条目可能刚被删除）。 */
        BAD_INDEX,
        /** 不在服务端。 */
        NOT_SERVER
    }

    /** 目标名结果。除 {@code OK} 外一律<b>不写入</b>。 */
    public enum TargetApplyResult {
        OK,
        EMPTY,
        TOO_LONG,
        INVALID_CHARS,
        /** 本网络里没有这个名字的接收器。 */
        NOT_FOUND,
        /** 这个名字被多于一台接收器占用（历史重名 / 网络合并）：拒绝，绝不随机挑一台。 */
        AMBIGUOUS,
        /** 不在服务端。 */
        NOT_SERVER
    }

    /** 订单结果 → 提示语言键。 */
    public static String messageKeyFor(final OrderResult result) {
        return switch (result) {
            case OK -> MSG_ORDER_UPDATED;
            case INVALID_RESOURCE -> MSG_ORDER_INVALID_RESOURCE;
            case INVALID_AMOUNT -> MSG_ORDER_INVALID_AMOUNT;
            case BAD_INDEX -> MSG_ORDER_BAD_INDEX;
            case NOT_SERVER -> "";
        };
    }

    /** 目标结果 → 提示语言键。 */
    public static String messageKeyFor(final TargetApplyResult result) {
        return switch (result) {
            case OK -> MSG_TARGET_OK;
            case EMPTY -> MSG_TARGET_EMPTY;
            case TOO_LONG -> MSG_TARGET_TOO_LONG;
            case INVALID_CHARS -> MSG_TARGET_INVALID;
            case NOT_FOUND -> MSG_TARGET_NOT_FOUND;
            case AMBIGUOUS -> MSG_TARGET_AMBIGUOUS;
            case NOT_SERVER -> "";
        };
    }

    /** 某资源当前在已选列表里的下标（没有 ⇒ -1；网格里刚加入一条时用来让那一行被选中）。 */
    public static int indexOfOrder(@Nullable final SenderBackend backend,
                                   @Nullable final PlatformResourceKey resource) {
        if (backend == null || resource == null) {
            return -1;
        }
        final List<ResourceAmount> current = backend.orders();
        for (int index = 0; index < current.size(); index++) {
            final ResourceAmount entry = current.get(index);
            if (entry != null && resource.equals(entry.resource())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 服务端：追加一条已选资源。
     * <p>判据只有两条：数量 &gt; 0、资源键非空；合并 / 清洗交给
     * {@link SenderBackend#setOrders} 的唯一实现（同资源合并、丢弃非正数、绝不静默丢条目）。
     * <b>没有条数上限</b>（用户明确要求「订单上限没有设限」）。</p>
     */
    public static OrderResult addEntry(@Nullable final SenderBackend backend,
                                       @Nullable final PlatformResourceKey key, final int amount) {
        if (backend == null) {
            return OrderResult.NOT_SERVER;
        }
        if (amount <= 0) {
            return OrderResult.INVALID_AMOUNT;
        }
        if (key == null) {
            return OrderResult.INVALID_RESOURCE;
        }
        final List<ResourceAmount> next = new ArrayList<>(backend.orders());
        next.add(new ResourceAmount(key, amount));
        backend.setOrders(next);
        backend.save();
        return OrderResult.OK;
    }

    /** 服务端：删除某一条（下标 + 完整资源键二次确认，全部在写入之前）。 */
    public static OrderResult removeEntry(@Nullable final SenderBackend backend, final int index,
                                          @Nullable final PlatformResourceKey key) {
        if (backend == null) {
            return OrderResult.NOT_SERVER;
        }
        final List<ResourceAmount> current = backend.orders();
        if (index < 0 || index >= current.size() || !entryMatches(current.get(index), key)) {
            // 下标与资源键必须同时对得上：清单可能被另一名玩家 / 另一张界面改过，
            // 只校验下标会把「删掉别人刚加的那条」当成成功。
            return OrderResult.BAD_INDEX;
        }
        final List<ResourceAmount> next = new ArrayList<>(current);
        next.remove(index);
        backend.setOrders(next);
        backend.save();
        return OrderResult.OK;
    }

    /** 服务端：改写某一条数量（数量 &gt; 0、下标有效、资源键一致，全部在写入之前）。 */
    public static OrderResult setAmount(@Nullable final SenderBackend backend, final int index,
                                        final int amount, @Nullable final PlatformResourceKey key) {
        if (backend == null) {
            return OrderResult.NOT_SERVER;
        }
        if (amount <= 0) {
            return OrderResult.INVALID_AMOUNT;
        }
        final List<ResourceAmount> current = backend.orders();
        if (index < 0 || index >= current.size() || !entryMatches(current.get(index), key)) {
            return OrderResult.BAD_INDEX;
        }
        final List<ResourceAmount> next = new ArrayList<>(current);
        next.set(index, new ResourceAmount(current.get(index).resource(), amount));
        backend.setOrders(next);
        backend.save();
        return OrderResult.OK;
    }

    /** 服务端：清空整张订单（只动清单：目标 / 缓冲 / 网络节点一个字都不碰）。 */
    public static OrderResult clearOrders(@Nullable final SenderBackend backend) {
        if (backend == null) {
            return OrderResult.NOT_SERVER;
        }
        backend.clearOrders();
        backend.save();
        return OrderResult.OK;
    }

    /** 第 {@code index} 条是不是客户端看到的那一条（<b>完整资源键</b>相等；键为空 ⇒ 判否）。 */
    private static boolean entryMatches(@Nullable final ResourceAmount entry,
                                        @Nullable final PlatformResourceKey key) {
        return entry != null && key != null && key.equals(entry.resource());
    }

    /**
     * 服务端：设定目标接收器（唯一写入口）。
     *
     * <p>校验顺序（任何一步失败都不写入）：服务端 → 名字形状（空 / 超长 / 控制字符）→
     * <b>目标必须存在且唯一</b>（{@code resolveReceiversByName} 0 个 = 找不到、≥2 = 重名，
     * 绝不随机挑一台）→ 写 → 回权威快照。拒绝同样回一条明确文案，绝不静默。</p>
     *
     * <p><b>P0（第 131 轮修）</b>：本方法与 {@link SenderBackend#setTargetName} 之间只许
     * <b>单向</b>调用（本方法 → 实现的直接写）。旧写法让实现方调回本方法，一设目标就
     * {@code StackOverflowError} 打崩服务端（详见实现类里 {@code setTargetName} 的注释）。</p>
     */
    public static TargetApplyResult applyTarget(@Nullable final ServerPlayer player,
                                                @Nullable final SenderBackend backend,
                                                @Nullable final String rawName) {
        if (player == null || backend == null || player.level().isClientSide()) {
            return TargetApplyResult.NOT_SERVER;
        }
        final String name = rawName == null ? "" : rawName.trim();
        final cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu.NameApplyResult shape =
            cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu.checkNameShape(name);
        if (shape != cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu
            .NameApplyResult.OK) {
            final TargetApplyResult shapeResult = switch (shape) {
                case EMPTY -> TargetApplyResult.EMPTY;
                case TOO_LONG -> TargetApplyResult.TOO_LONG;
                case INVALID_CHARS -> TargetApplyResult.INVALID_CHARS;
                default -> TargetApplyResult.EMPTY;
            };
            syncTo(player, backend, messageKeyFor(shapeResult), -1);
            return shapeResult;
        }
        final List<NetworkResourceReceiverBlockEntity> hits =
            SenderDelivery.resolveReceiversByName(backend.network(), name);
        if (hits.isEmpty()) {
            // 拒绝也必须回一份快照（界面据此显示「为什么没改成」），绝不静默
            syncTo(player, backend, messageKeyFor(TargetApplyResult.NOT_FOUND), -1);
            return TargetApplyResult.NOT_FOUND;
        }
        if (hits.size() > 1) {
            syncTo(player, backend, messageKeyFor(TargetApplyResult.AMBIGUOUS), -1);
            return TargetApplyResult.AMBIGUOUS;
        }
        // 顺序钉死：**校验全部通过 → 才写**（写走 SenderBackend#setTargetName，是直接写；
        // 本方法的实现方绝不许反过来再调本方法 —— 那正是第 131 轮 P0 的 StackOverflowError）。
        if (!name.equals(backend.targetName())) {
            backend.setTargetName(player, name);
        }
        backend.save();
        syncTo(player, backend, messageKeyFor(TargetApplyResult.OK), -1);
        return TargetApplyResult.OK;
    }

    // ==================== 发送（按钮 / 红石共用） ====================

    /**
     * 服务端：立即发送（界面按钮入口）。
     *
     * <p>先按与红石路径<b>同一套判据</b>确认目标已配置且唯一存在（否则明确提示、<b>不触发</b>），
     * 再调 {@link SenderBackend#trigger}——搬运逻辑在方块实体 / 终端的实现里，本方法一行都不复制。</p>
     */
    public static void triggerFor(@Nullable final ServerPlayer player,
                                  @Nullable final SenderBackend backend) {
        if (player == null || backend == null || player.level().isClientSide()) {
            return;
        }
        if (!backend.isConnected()) {
            syncTo(player, backend, MSG_TRIGGER_FAILED, -1);
            return;
        }
        if (backend.targetName().isEmpty()) {
            syncTo(player, backend, MSG_TRIGGER_UNCONFIGURED, -1);
            return;
        }
        final List<NetworkResourceReceiverBlockEntity> hits =
            SenderDelivery.resolveReceiversByName(backend.network(), backend.targetName());
        if (hits.isEmpty()) {
            syncTo(player, backend, MSG_TRIGGER_NOT_FOUND, -1);
            return;
        }
        if (hits.size() > 1) {
            syncTo(player, backend, MSG_TRIGGER_AMBIGUOUS, -1);
            return;
        }
        backend.trigger(player);
        syncTo(player, backend, backend.lastTriggerSucceeded() ? MSG_TRIGGER_OK : MSG_TRIGGER_FAILED,
            -1);
    }

    // ==================== 资源标识解析（旧协议兼容） ====================

    /**
     * 解析客户端提交的资源标识（旧协议的「注册名 + 是否流体」字段）。
     *
     * <p>顺序刻意分成两段：① 原版物品 / 流体注册名（空气 / {@code Fluids.EMPTY} / 未注册 ⇒ 不认）；
     * ② 第三方类型令牌（{@link RsccResourceKeys#parseToken}）—— 只有装了官方联动、网络里真的出现
     * 气体之类资源时，选择列表才会下发这种令牌。两段都不认 ⇒ {@code null}（调用方回
     * {@code INVALID_RESOURCE}，绝不猜一个资源）。</p>
     */
    @Nullable
    public static PlatformResourceKey parseResourceKey(
        @Nullable final HolderLookup.Provider registries,
        @Nullable final String resourceId, final boolean fluid) {
        if (resourceId == null || resourceId.isEmpty()) {
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(resourceId);
        if (id != null) {
            if (fluid) {
                if (BuiltInRegistries.FLUID.containsKey(id)) {
                    final Fluid found = BuiltInRegistries.FLUID.get(id);
                    if (found != null && found != Fluids.EMPTY) {
                        return new FluidResource(found);
                    }
                }
            } else if (BuiltInRegistries.ITEM.containsKey(id)) {
                final Item found = BuiltInRegistries.ITEM.get(id);
                if (found != null && found != Items.AIR) {
                    return new ItemResource(found);
                }
            }
        }
        return RsccResourceKeys.parseToken(resourceId, registries);
    }

    // ==================== S2C 同步 ====================

    /**
     * 服务端：把权威快照发给玩家（{@code containerId} 决定落到哪个界面）。
     * <p>完整覆盖，客户端只读；目标 / 订单 / 接收器候选 / 连通性 / 固定节拍都在里面。</p>
     */
    public static void syncTo(@Nullable final ServerPlayer player,
                              @Nullable final SenderBackend backend,
                              @Nullable final String messageKey, final int focusOrder) {
        if (player == null || backend == null || player.containerMenu == null) {
            return;
        }
        final int containerId = player.containerMenu.containerId;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            SyncRequesterConfigPacket.of(containerId, backend,
                SenderDelivery.receiverViews(backend.network()),
                messageKey == null ? "" : messageKey, backend.lastTriggerSucceeded(), focusOrder,
                backend.isConnected(), player.level().registryAccess()));
    }

    // ==================== 消息键（服务端只发 key，界面自己翻译） ====================

    public static final String MSG_TARGET_OK =
        "gui.rs_create_compat.network_resource_requester.msg.target_ok";
    public static final String MSG_TARGET_EMPTY =
        "gui.rs_create_compat.network_resource_requester.msg.target_empty";
    public static final String MSG_TARGET_TOO_LONG =
        "gui.rs_create_compat.network_resource_requester.msg.target_too_long";
    public static final String MSG_TARGET_INVALID =
        "gui.rs_create_compat.network_resource_requester.msg.target_invalid";
    public static final String MSG_TARGET_NOT_FOUND =
        "gui.rs_create_compat.network_resource_requester.msg.target_not_found";
    public static final String MSG_TARGET_AMBIGUOUS =
        "gui.rs_create_compat.network_resource_requester.msg.target_ambiguous";
    public static final String MSG_ORDER_ADDED =
        "gui.rs_create_compat.network_resource_requester.msg.order_added";
    public static final String MSG_ORDER_REMOVED =
        "gui.rs_create_compat.network_resource_requester.msg.order_removed";
    public static final String MSG_ORDER_UPDATED =
        "gui.rs_create_compat.network_resource_requester.msg.order_updated";
    public static final String MSG_ORDER_INVALID_AMOUNT =
        "gui.rs_create_compat.network_resource_requester.msg.order_invalid_amount";
    public static final String MSG_ORDER_INVALID_RESOURCE =
        "gui.rs_create_compat.network_resource_requester.msg.order_invalid_resource";
    public static final String MSG_ORDER_BAD_INDEX =
        "gui.rs_create_compat.network_resource_requester.msg.order_bad_index";
    public static final String MSG_ORDER_CLEARED =
        "gui.rs_create_compat.network_resource_requester.msg.order_cleared";
    public static final String MSG_ORDER_ALREADY_EMPTY =
        "gui.rs_create_compat.network_resource_requester.msg.order_already_empty";
    public static final String MSG_TRIGGER_OK =
        "gui.rs_create_compat.network_resource_requester.msg.trigger_ok";
    public static final String MSG_TRIGGER_FAILED =
        "gui.rs_create_compat.network_resource_requester.msg.trigger_failed";
    public static final String MSG_TRIGGER_UNCONFIGURED =
        "gui.rs_create_compat.network_resource_requester.msg.trigger_unconfigured";
    public static final String MSG_TRIGGER_NOT_FOUND =
        "gui.rs_create_compat.network_resource_requester.msg.trigger_not_found";
    public static final String MSG_TRIGGER_AMBIGUOUS =
        "gui.rs_create_compat.network_resource_requester.msg.trigger_ambiguous";

    /** 「网格打不开：没接网络」的语言键（{@code ResourcePickerMenu} 与本类共用同一条文案）。 */
    public static final String MSG_PICKER_NO_NETWORK =
        "gui.rs_create_compat.resource_picker.msg.no_network";
    /** 「网格界面没能打开」的语言键（与上面配成一对，两者都必须给玩家一句）。 */
    public static final String MSG_PICKER_OPEN_FAILED =
        "gui.rs_create_compat.resource_picker.msg.open_failed";
}
