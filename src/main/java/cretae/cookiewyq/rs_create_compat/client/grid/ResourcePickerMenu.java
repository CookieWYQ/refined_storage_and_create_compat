package cretae.cookiewyq.rs_create_compat.client.grid;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.grid.GridNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.node.grid.GridExtractMode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridInsertMode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridOperations;
import com.refinedmods.refinedstorage.api.network.node.grid.GridWatcher;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.ExtractableStorage;
import com.refinedmods.refinedstorage.api.storage.InsertableStorage;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.TrackedResourceAmount;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceType;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.GridData;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 网络资源请求器的 <b>RS 网格界面</b>（继承 RS 自己的 {@code AbstractGridContainerMenu}）——
 * <b>这就是请求器的主界面</b>：选资源、改数量、发送全部在这里完成，不再跳回方块界面。
 *
 * <h2>为什么必须有这个类（不能复用 RS 的菜单）</h2>
 * <p>{@code GridContainerMenu} / {@code WirelessGridContainerMenu} 的构造器把 {@code MenuType}
 * <b>硬编码</b>成 {@code Menus.INSTANCE.getGrid()}（javap 证据见 {@code docs/RESOURCE_PICKER_DESIGN.md} §1.3）
 * —— 走它们的话客户端永远去查 RS 自己那张 Screen 表，我们的注册不会生效。
 * 因此这里只做一件事：在自己的 {@code super(...)} 里传<b>我们自己的</b>
 * {@link RS_Create_Compat#RESOURCE_PICKER_MENU}（约 10 行，正是设计文档 §1.3 的唯一「必须自己写」的部分）。</p>
 *
 * <h2>「只选不搬」：三层硬保证（用户报告的「物品看似放进去、其实还在身上」的正面修复）</h2>
 * <ol>
 *     <li><b>入口</b>：{@link #openFor} 先过 {@link #isConnectedToNetwork}。请求器没接在
 *     「有其它节点 + 有电」的网络上 ⇒ <b>根本打不开</b>，调用方会回一条明确提示；</li>
 *     <li><b>服务端操作层</b>：{@link RequesterGrid#createOperations} 恒返回
 *     {@link #EMPTY_OPERATIONS} —— 即使客户端伪造 RS 的取出 / 放入包，服务端也一个物品都不动；</li>
 *     <li><b>客户端</b>：网格区域的点击全部被 {@code ResourcePickerScreen#mouseClicked} 吞掉
 *     （悬停资源 = 选中 / 取消选中；空格子也吞），<b>绝不</b>落到 {@code super} 的
 *     取出 / 放入 / 自动合成路径。</li>
 * </ol>
 *
 * <h2>为什么 {@code isGridActive()} 不能只看 StorageNetworkComponent</h2>
 * <p>RS 2.x 的 {@code NetworkBuilderImpl#initialize} 会给<b>每一个</b>网络节点建一个
 * {@code Network}（哪怕它一个邻居都没有：{@code mergeNetworksOfNodes} →
 * {@code findPivotNetworkForMerge(...).orElseGet(() -> createNetwork(pivot))}），而
 * {@code StorageNetworkComponent} 是「对每个 Network 都注册」的组件工厂
 * （{@code AbstractModInitializer#registerNetworkComponents}）。所以
 * 「network != null」「storage != null」在一个孤立方块上<b>恒为真</b> ——
 * 旧代码的 no-network 守卫因此永远不触发（这正是用户看到「未连接网络仍能打开网格」的根因）。
 * 现在改为判<b>网络图里除了自己还有别的节点</b> + <b>网络有电</b>（与 RS 自己的
 * {@code calculateActive()} 同一口径）。</p>
 *
 * <h2>已选清单从哪来</h2>
 * <p>打开时随菜单额外数据下发一份；之后每次编辑由服务端写回方块实体，并用
 * {@code SyncRequesterConfigPacket} 把<b>权威快照</b>回给本菜单的 {@code containerId}
 * （屏幕在 {@code containerTick} 里从 {@code SyncRequesterConfigPacket#takeFor} 取走）。
 * 客户端因此永远只是只读镜像，没有本地写入路径。</p>
 */
public class ResourcePickerMenu extends AbstractGridContainerMenu {
    /** 界面标题语言键（服务端 openMenu 的 title 与客户端共用同一份）。 */
    public static final String TITLE_KEY = "gui.rs_create_compat.resource_picker.title";

    /**
     * 「打不开网格」的提示语言键（请求器<b>没有接在可用网络上</b>时由
     * {@code NetworkResourceRequesterMenu#openResourcePicker} 回给玩家）。
     * <p>为什么不静默返回：一个永远空白的网格会让玩家以为「网络里什么都没有」，
     * 而不是「这台机器还没接上网络」。</p>
     */
    public static final String MSG_NO_NETWORK =
        "gui.rs_create_compat.resource_picker.msg.no_network";

    /**
     * 「网格界面没能打开」的提示语言键（服务端 {@code openMenu} 被拒绝时用）。
     *
     * <p>与 {@link #MSG_NO_NETWORK} 分开：前者是「这台机器没接网络」，后者是
     * 「网络没问题，但这次 {@code openMenu} 没成」。1.21.1 里 {@code openMenu} 只在
     * {@code createMenu} 返回 {@code null} 时返回空（玩家已有界面会先被
     * {@code closeContainer()} 关掉，<b>不是</b>静默拒绝）；但无论哪种原因，
     * 右键点了都必须有一句，绝不把「点了没反应」留在那里。</p>
     */
    public static final String MSG_OPEN_FAILED =
        "gui.rs_create_compat.resource_picker.msg.open_failed";

    /** 服务端权威：本菜单属于哪台请求器（客户端重建菜单时为 {@code null}）。 */
    @Nullable
    private final NetworkResourceRequesterBlockEntity requester;

    /** 客户端：请求器当前已选资源的只读镜像（打开时随菜单额外数据下发一次）。 */
    private final List<OrderEntry> selectedOrders;

    // ==================== 两个构造器（服务端 / 客户端，各一条） ====================

    /**
     * 服务端构造器（唯一会注册 watcher 的那一条）：{@code grid} 由 {@link #openFor} 现场构造。
     */
    private ResourcePickerMenu(final int id,
                               final Inventory playerInventory,
                               final RequesterGrid grid,
                               final NetworkResourceRequesterBlockEntity requester) {
        super(RS_Create_Compat.RESOURCE_PICKER_MENU.get(), id, playerInventory, grid);
        this.requester = requester;
        this.selectedOrders = List.of();
    }

    /**
     * 客户端构造器（由 {@code IMenuTypeExtension} 工厂经 {@link #read} 调用）：
     * 只拿到 {@code GridData} 与已选资源镜像，没有任何方块实体 / 网格实现。
     */
    private ResourcePickerMenu(final int id,
                               final Inventory playerInventory,
                               final GridData data,
                               final List<OrderEntry> selectedOrders) {
        super(RS_Create_Compat.RESOURCE_PICKER_MENU.get(), id, playerInventory, data);
        this.requester = null;
        this.selectedOrders = List.copyOf(selectedOrders);
    }

    /** 客户端工厂（{@code IMenuTypeExtension} 用）：按「先 GridData、再已选资源」的顺序解码。 */
    public static ResourcePickerMenu read(final int id,
                                          final Inventory playerInventory,
                                          final RegistryFriendlyByteBuf buf) {
        final GridData data = GridData.STREAM_CODEC.decode(buf);
        final List<OrderEntry> orders = readOrders(buf);
        return new ResourcePickerMenu(id, playerInventory, data, orders);
    }

    // ==================== 打开（服务端唯一入口） ====================

    /**
     * 打开网格界面（服务端）。
     *
     * <p>链路与 RS 自己的无线网格一致：{@code player.openMenu(provider, writer)} 把
     * {@code GridData} 与「已选资源镜像」写进菜单额外数据；客户端由
     * {@code AdvancedOpenScreenPayload} → {@code menuType.create(id, inv, buf)} → {@link #read} 重建。</p>
     *
     * @return {@code false} 表示<b>没有打开</b>（请求器没有接在可用网络上）—— 调用方必须给出提示，
     *     绝不要「打开一个永远空白的网格」让玩家以为网络里什么都没有。
     */
    public static boolean openFor(final ServerPlayer player,
                                  final NetworkResourceRequesterBlockEntity requester) {
        if (player == null || !isConnectedToNetwork(requester)) {
            return false;
        }
        final RequesterGrid grid = new RequesterGrid(requester);
        final List<OrderEntry> orders = snapshotOrders(requester);
        final java.util.OptionalInt opened = player.openMenu(
            new SimpleMenuProvider(
                (id, inventory, ignored) -> new ResourcePickerMenu(id, inventory, grid, requester),
                Component.translatable(TITLE_KEY)),
            buf -> {
                GridData.STREAM_CODEC.encode(buf, GridData.of(grid));
                writeOrders(buf, orders);
            });
        // 打开后立刻补一份「权威配置快照」给这张新菜单：目标接收器、本网络接收器列表、
        // 连通性、节流数值都在里面。不发这一份的话，网格界面在玩家第一次操作之前
        // 只能显示「未选目标 / 未知节拍」—— 而网格现在是**主界面**（右键直接进来），
        // 一进来就该是服务端真值。
        opened.ifPresent(id -> NetworkResourceRequesterMenu.sendConfigSyncTo(player, id, "", -1));
        // 2026-10-25（第 96 轮独立复核 P2）：这里**必须**返回 openMenu 的真实结果。
        // 两个调用方（右键方块 / 网格里的「设置」按钮）正是靠 false 去给 MSG_PICKER_OPEN_FAILED
        // 提示的；无条件 true 会让那条提示永远不可达，玩家看到的就是「点了没反应」。
        return opened.isPresent();
    }

    /**
     * 请求器此刻是否<b>真的</b>接在一个可用的 RS 网络上。
     *
     * <p>判据（两条都要）：① 网络图里除了自己还有别的节点（孤立方块会被 RS 分配一个只有它自己的
     * Network，所以「network != null」不能当连通性）；② 网络有电（{@link EnergyNetworkComponent#getStored()}
     * ≥ 本机耗电）—— 与 RS 自己的 {@code AbstractBaseNetworkNodeContainerBlockEntity#calculateActive()}
     * 同一口径。没控制器 / 没电的网络本来也投递不了任何东西。</p>
     */
    public static boolean isConnectedToNetwork(
        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        if (requester == null || requester.getLevel() == null) {
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

    /**
     * 玩家此刻打开的容器属于哪台请求器（只认本菜单；请求器菜单由调用方先判）。
     * <p>放在这里而不是 {@code menu/} 里，是为了让「谁认得这个菜单」只有一个出处：
     * {@code NetworkResourceRequesterMenu#requesterOf} 是唯一的转接点。</p>
     */
    @Nullable
    public static NetworkResourceRequesterBlockEntity requesterOf(
        @Nullable final AbstractContainerMenu menu) {
        return menu instanceof ResourcePickerMenu picker ? picker.requester : null;
    }

    // ==================== 只读访问（全部给屏幕层用） ====================

    /** 请求器当前已选资源的只读镜像（打开时的快照；屏幕收到新快照后会替换）。 */
    public List<OrderEntry> selectedOrders() {
        return selectedOrders;
    }

    /**
     * 网格界面活得比「连接」更短：方块被拆 / 玩家走远 / 网络断开 ⇒ 服务端直接关掉它。
     * <p>{@code ServerPlayer#tick} 每 tick 调一次本方法；这样「界面上还能点、服务端其实已经不做」
     * 的错位不会出现。</p>
     */
    @Override
    public boolean stillValid(final Player player) {
        if (requester == null) {
            return true; // 客户端镜像：有效性由服务端校验
        }
        return NetworkResourceRequesterMenu.stillValidFor(player, requester)
            && isConnectedToNetwork(requester);
    }

    // ==================== 「只选不搬」：接住 / 挡掉 RS 的搬运通道 ====================

    /**
     * 服务端：<b>不</b>接收 RS 的「过滤槽选中」回传。
     *
     * <p>本界面用 {@link cretae.cookiewyq.rs_create_compat.network.RequesterOrderPacket} 做
     * 增 / 删 / 改（它能带数量与动作），因此这个 RS 通道在这里一律忽略 ——
     * 绝不允许一条只带资源键的包产生任何写入。</p>
     */
    @Override
    public void handleResourceFilterSlotUpdate(final int index, final PlatformResourceKey resource) {
        // 故意为空：见方法注释（只选不搬 + 写入必须带动作与数量）
    }

    /**
     * 选择器<b>不搬运任何物品</b>：Shift 点击 / 双击收集一律不做。
     * <p>网格本身没有槽位（点击已被屏幕层吞掉），这里再挡一次玩家背包的「快速移动」，
     * 免得玩家在选资源时不小心把背包里的东西塞进网络。</p>
     */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY;
    }

    // ==================== 已选资源的编解码 ====================

    /** 一条「已选」资源：资源键 + 数量（屏幕的「已选」面板与网格高亮只用它，不改任何配置）。 */
    public record OrderEntry(PlatformResourceKey key, long amount) {
    }

    /** 服务端：把方块实体里的权威已选资源压成镜像（顺序 = 加入顺序 = 投递顺序）。 */
    public static List<OrderEntry> snapshotOrders(
        final NetworkResourceRequesterBlockEntity requester) {
        final List<OrderEntry> out = new ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.resource.ResourceAmount entry
            : requester.getOrders()) {
            if (entry != null && entry.resource() instanceof PlatformResourceKey key) {
                out.add(new OrderEntry(key, entry.amount()));
            }
        }
        return List.copyOf(out);
    }

    private static void writeOrders(final RegistryFriendlyByteBuf buf, final List<OrderEntry> orders) {
        buf.writeVarInt(orders.size());
        for (final OrderEntry entry : orders) {
            ResourceCodecs.STREAM_CODEC.encode(buf, entry.key());
            buf.writeVarLong(entry.amount());
        }
    }

    private static List<OrderEntry> readOrders(final RegistryFriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        final List<OrderEntry> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final PlatformResourceKey key = ResourceCodecs.STREAM_CODEC.decode(buf);
            out.add(new OrderEntry(key, buf.readVarLong()));
        }
        return List.copyOf(out);
    }

    // ==================== Grid 适配器（服务端只读视图） ====================

    /**
     * 把「请求器所在网络」适配成 RS 的 {@link Grid}（设计文档 §3.5 的最小喂料清单）。
     *
     * <p>每个方法都只是把 RS 自己那条链路转发出去（与 {@code AbstractGridBlockEntity} 的口径一致）：
     * 资源 = {@code StorageNetworkComponent#getResources}、可合成 = {@code AutocraftingNetworkComponent#getOutputs}、
     * watcher = 一个 {@link GridNetworkNode} 实例（它就是 watcher 管理器）。
     * 自动合成的 {@code Preview} 系列在本界面<b>不提供</b>（选择器没有合成语义），一律回空。</p>
     *
     * <p><b>唯一的偏离</b>：{@link #createOperations} 恒回 {@link #EMPTY_OPERATIONS} ——
     * 本界面只读，绝不推出 / 放入任何物品。</p>
     */
    static final class RequesterGrid implements Grid {
        private final NetworkResourceRequesterBlockEntity requester;
        /** RS 的 watcher 管理器（限流 / 批量 / 每 tick flush / 清空 / active 变化全在 RS 那边）。 */
        private final GridNetworkNode node = new GridNetworkNode(0L);

        RequesterGrid(final NetworkResourceRequesterBlockEntity requester) {
            this.requester = requester;
            this.node.setNetwork(network());
        }

        @Nullable
        private Network network() {
            return requester.getNode() == null ? null : requester.getNode().getNetworkOrNull();
        }

        @Nullable
        private StorageNetworkComponent storage() {
            final Network network = network();
            return network == null ? null : network.getComponent(StorageNetworkComponent.class);
        }

        @Override
        public void addWatcher(final GridWatcher watcher, final Class<? extends Actor> actorType) {
            // 网络可能在两次 open 之间被重建：注册前把节点挂到「此刻」的网络上（无 watcher 时开销为 0）
            node.setNetwork(network());
            node.addWatcher(watcher, actorType);
        }

        @Override
        public void removeWatcher(final GridWatcher watcher) {
            node.removeWatcher(watcher);
        }

        @Override
        public Storage getItemStorage() {
            return storage();
        }

        @Override
        public boolean isGridActive() {
            // 有网络 ≠ 接在网络里（RS 给孤立方块也建一个只有它自己的 Network）⇒ 必须用连通性判据
            return isConnectedToNetwork(requester) && storage() != null;
        }

        @Override
        public List<TrackedResourceAmount> getResources(final Class<? extends Actor> actorType) {
            final StorageNetworkComponent storage = storage();
            return storage == null ? List.of() : storage.getResources(actorType);
        }

        @Override
        public Set<PlatformResourceKey> getAutocraftableResources() {
            final Network network = network();
            if (network == null) {
                return Set.of();
            }
            final AutocraftingNetworkComponent autocrafting =
                network.getComponent(AutocraftingNetworkComponent.class);
            if (autocrafting == null) {
                return Set.of();
            }
            return autocrafting.getOutputs().stream()
                .filter(PlatformResourceKey.class::isInstance)
                .map(PlatformResourceKey.class::cast)
                .collect(Collectors.toUnmodifiableSet());
        }

        /**
         * <b>恒为空操作</b>：本界面只选资源，绝不清点 / 放入物品。
         * <p>这一条是「物品看似放入、实际还在身上」的服务端硬保险：即使客户端伪造了 RS 的
         * {@code GridInsertPacket} / {@code GridExtractPacket}，落到这里也只会得到
         * {@code false}，方块实体 / 网络存储一个字节都不会动。</p>
         */
        @Override
        public GridOperations createOperations(final ResourceType resourceType,
                                               final ServerPlayer player) {
            return EMPTY_OPERATIONS;
        }

        @Override
        public boolean canMenuStayOpen(final Player player) {
            final net.minecraft.world.level.Level level = requester.getLevel();
            if (level == null || level.getBlockEntity(requester.getBlockPos()) != requester) {
                return false;
            }
            return player.distanceToSqr(requester.getBlockPos().getX() + 0.5,
                requester.getBlockPos().getY() + 0.5,
                requester.getBlockPos().getZ() + 0.5) <= 64.0;
        }

        // ---------- PreviewProvider：选择器不提供自动合成（一律回空，绝不发任务） ----------

        @Override
        public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource,
                                                              final long amount,
                                                              final CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<TreePreview>> getTreePreview(final ResourceKey resource,
                                                                      final long amount,
                                                                      final CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Long> getMaxAmount(final ResourceKey resource,
                                                    final CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public Optional<TaskId> startTask(final ResourceKey resource,
                                          final long amount,
                                          final Actor actor,
                                          final boolean notify,
                                          final CancellationToken cancellationToken) {
            return Optional.empty();
        }
    }

    /** 「什么都不搬」的操作集（绝不返回 {@code null}：RS 的策略对象会在取料时无条件调用它）。 */
    private static final GridOperations EMPTY_OPERATIONS = new GridOperations() {
        @Override
        public boolean extract(final ResourceKey resource, final GridExtractMode mode,
                               final InsertableStorage destination) {
            return false;
        }

        @Override
        public boolean insert(final ResourceKey resource, final GridInsertMode mode,
                              final ExtractableStorage source) {
            return false;
        }
    };
}
