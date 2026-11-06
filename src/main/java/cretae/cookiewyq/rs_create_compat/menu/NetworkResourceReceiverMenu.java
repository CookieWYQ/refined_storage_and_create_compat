package cretae.cookiewyq.rs_create_compat.menu;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.impl.node.grid.GridNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridExtractMode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridInsertMode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridOperations;
import com.refinedmods.refinedstorage.api.network.node.grid.GridWatcher;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.ExtractableStorage;
import com.refinedmods.refinedstorage.api.storage.InsertableStorage;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.TrackedResourceAmount;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.ResourceType;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.GridData;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheActionPacket;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheEntry;
import cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket;
import cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 网络资源接收器菜单（第 132 轮：<b>改成 RS 自己的网格菜单</b>）。
 *
 * <h2>用户意见与修法</h2>
 * <p>用户原话：「你完全理解错我意思了，你自己写一套这个狗屎东西干什么？我的意思就像第一张图（发送器界面）
 * 那样自己实现一个这个东西，一样是这样重新写……你直接像图一那样搞一个这个东西重写一下，然后加点东西不就得了。」
 * 因此本类不再 {@code extends AbstractContainerMenu} 手写 9×7 格子 / 分页 / 自带滚动条，而是
 * {@code extends AbstractGridContainerMenu}：资源仓库、搜索、排序、视图过滤、滚动、点击取物、
 * 数量角标、tooltip 全部交给 RS；本类只补三件 RS 不认识的事：</p>
 * <ol>
 *     <li><b>缓存作为网格数据源</b>：内部 {@link ReceiverGrid} 把 {@code itemCache / fluidCache}
 *     聚合成 RS 的 {@link TrackedResourceAmount}；</li>
 *     <li><b>从缓存取出</b>：{@link CacheGridOperations} 按 {@link GridExtractMode} 把
 *     {@code ENTIRE_RESOURCE / HALF_RESOURCE / SINGLE_RESOURCE} 换成条目数，再搬运到 RS 给的
 *     {@link InsertableStorage}（光标 / 背包 / 流体容器 —— 由 RS 的策略决定）；{@code insert} 恒
 *     {@code false}（界面不能放入）；</li>
 *     <li><b>extras 同步</b>：名字 / 已占用名字 / 状态 / 缓存统计 / 朝向 继续走
 *     {@link SyncReceiverConfigPacket}；资源本身走 {@link GridData} + RS 的增量包。</li>
 * </ol>
 *
 * <h2>缓存变化如何到达已打开的界面（第 132 轮的硬要求）</h2>
 * <p>方块实体维护一个单调递增的 {@link NetworkResourceReceiverBlockEntity#cacheVersion()}：
 * {@code doWork} 输出、外部漏斗 / 溜槽插入、请求器投递、界面取出都会经过
 * {@link NetworkResourceReceiverBlockEntity#setChanged()}（{@code applyResource} 也补了
 * {@code setChanged}）。菜单在每个 {@code broadcastChanges()} 里只做一次 {@code long} 比较；
 * 版本变了才扫一遍缓存做差异，然后逐条调用
 * {@link AbstractGridContainerMenu#onChanged(ResourceKey, long, com.refinedmods.refinedstorage.api.storage.tracked.TrackedResource)}
 * —— 这正是 RS 内部 watcher 的入口，RS 会把它合并 / 限流成 {@code GridUpdatePacket} 发给客户端。
 * 客户端最终落到 {@link #onResourceUpdate(ResourceKey, long, com.refinedmods.refinedstorage.api.storage.tracked.TrackedResource)}
 * 的同一个资源仓库里，因此「界面开着时缓存变了，格子数量会动」。</p>
 *
 * <h2>流体取出</h2>
 * <p>不再在本类里手写「光标 → 背包 → 网络 → 都没有」的桶逻辑：RS 的
 * {@code FluidGridExtractionStrategy} 已经按这个顺序（:55 / :57 / :59 / :62）实现，并且通过
 * {@link GridOperations#extract} 的 {@link InsertableStorage} 回调用我们的缓存当源。
 * 我们只负责「从缓存真扣」和「失败回填」：模拟装得下才真扣，目标只收一部分就把余量灌回缓存，
 * 所以仍然满足旧界面的守恒铁律（取不出就一个 mB 都不扣、扣了没给必须回滚）。
 * {@link #getItemStorage()} 返回缓存里的物品视图，于是「背包没有空桶」时 RS 会先看接收器缓存里
 * 有没有空桶，再决定是否换桶 —— 与 RS 网格的通用语义一致。</p>
 *
 * <h2>客户端绝不读方块实体</h2>
 * <p>客户端构造器 {@code receiver == null}，只持有 {@link GridData} 空壳 + 等待
 * {@link SyncReceiverConfigPacket} 灌入；任何「本地重算」都会与服务端分叉。</p>
 */
public class NetworkResourceReceiverMenu extends AbstractGridContainerMenu {
    /** 升级槽数量 = 方块实体的槽数（唯一来源，避免两处各写一个 6）。 */
    public static final int UPGRADE_COUNT = RsccSpeedStackUpgradeContainer.SLOTS;
    /** 玩家背包 / 快捷栏槽数（RS {@code resized()} 先加这 36 个槽）。 */
    public static final int PLAYER_SLOT_COUNT = 36;
    /** 玩家背包起点（RS 的 {@code resized()} 先加玩家背包）。 */
    public static final int PLAYER_START = 0;
    /** 玩家背包终点（不含）。 */
    public static final int PLAYER_END = PLAYER_START + PLAYER_SLOT_COUNT;
    /** 升级槽起点（玩家背包之后）。 */
    public static final int UPGRADE_START = PLAYER_END;

    /** 升级槽默认坐标（客户端界面在 {@code init()} 里按 extras 面板重新定位；服务端坐标无意义）。 */
    public static final int UPGRADE_X = 200;
    public static final int UPGRADE_Y0 = 83;
    /** 升级槽在面板内的行列间距（2 列 × 3 行）。 */
    public static final int UPGRADE_DX = 20;
    public static final int UPGRADE_DY = 18;

    /** 升级槽白名单：只有这两类（用户拍板「其他升级不可以安装」）。 */
    public static final List<String> ALLOWED_UPGRADES = List.of("speed_upgrade", "stack_upgrade");

    /**
     * 同步数据槽位数（与方块实体 {@code getContainerData()} 的 {@code getCount()} 必须相等）。
     * <p>0 = 每 tick 批数（速度升级旋钮），1 = 单批搬运量（件，叠加升级旋钮）。
     * 新界面不再显示「节拍」句，但数据槽保留：服务端调参 / 诊断 / 历史自检仍读它。</p>
     */
    public static final int DATA_COUNT = 2;

    /** 接收器名字的硬上限（字符数，按 {@code String#length()} 计）。 */
    public static final int MAX_NAME_LENGTH = 32;

    // ==================== 操作结果 / 提示（服务端只发语言键，界面自己翻译） ====================

    /** 改名结果。{@code OK} 之外一律<b>没有写入</b>方块实体。 */
    public enum NameApplyResult {
        /** 已应用（或与当前名字相同、无需改动）。 */
        OK,
        /** 空名 / 只有空白：未命名不可用，拒绝。 */
        EMPTY,
        /** 超过 {@link #MAX_NAME_LENGTH}。 */
        TOO_LONG,
        /** 含控制字符或 {@code §}（会破坏界面 / 聊天显示）。 */
        INVALID_CHARS,
        /** 同网络内已有另一台接收器用了这个名字。 */
        TAKEN,
        /** 不在服务端（客户端镜像菜单 / 方块实体已失效）：不写、不发。 */
        NOT_SERVER
    }

    /** 操作结果的语言键（S2C 下发纯 key，界面自己翻译；服务端从不拼自然语言）。 */
    public static final String MSG_NAME_APPLIED = "gui.rs_create_compat.network_resource_receiver.msg.name_applied";
    public static final String MSG_NAME_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.name_empty";
    public static final String MSG_NAME_TOO_LONG = "gui.rs_create_compat.network_resource_receiver.msg.name_too_long";
    public static final String MSG_NAME_INVALID = "gui.rs_create_compat.network_resource_receiver.msg.name_invalid";
    public static final String MSG_NAME_TAKEN = "gui.rs_create_compat.network_resource_receiver.msg.name_taken";

    /*
     * 第 132 轮：下面这些「取物结果」提示已不再由服务端产生（取物现在走 RS 的
     * GridExtractPacket → FluidGridExtractionStrategy / ItemGridExtractionStrategy），
     * 但它们仍在语言文件里、也被旧存档 / 旧诊断引用。保留为常量而不是删除：
     * ① 语言文件本轮不允许改（Lead 统一合并），删常量会让这些键变成孤儿；
     * ② 将来若要在 RS 网格里补动作条提示，键还在这里。
     */
    public static final String MSG_CACHE_TAKEN_ALL = "gui.rs_create_compat.network_resource_receiver.msg.cache_taken_all";
    public static final String MSG_CACHE_BAG_FULL = "gui.rs_create_compat.network_resource_receiver.msg.cache_bag_full";
    public static final String MSG_CACHE_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.cache_empty";
    public static final String MSG_FLUID_TAKEN = "gui.rs_create_compat.network_resource_receiver.msg.fluid_taken";
    public static final String MSG_FLUID_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.fluid_empty";
    public static final String MSG_FLUID_INSUFFICIENT =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_insufficient";
    public static final String MSG_FLUID_NO_CONTAINER =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_container";
    public static final String MSG_FLUID_NO_SPACE =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_space";
    public static final String MSG_FLUID_GONE = "gui.rs_create_compat.network_resource_receiver.msg.fluid_gone";

    /** 客户端构造器用的空网格镜像（真正的资源由第一份 {@link SyncReceiverConfigPacket} 灌入）。 */
    private static final GridData EMPTY_GRID_DATA = new GridData(false, List.of(), Set.of());

    /** 服务端权威方块实体（客户端为 {@code null}）。 */
    @Nullable
    private final NetworkResourceReceiverBlockEntity receiver;

    /** 服务端网格适配器（客户端为 {@code null}：RS 的客户端策略只发包，不碰服务端 Grid）。 */
    @Nullable
    private final ReceiverGrid receiverGrid;

    /** 6 个升级槽（服务端 = 方块实体那份；客户端 = 只读镜像，真正的校验在服务端）。 */
    private final RsccSpeedStackUpgradeContainer upgradeContainer;

    /** 节流 / 升级数据（见 {@link #DATA_COUNT}）。 */
    private final ContainerData data;

    /** 本网络已占用名字的缓存（只在改名时重算）。 */
    @Nullable
    private List<String> occupiedSnapshot;

    /** 上次同步给客户端的资源 → 总量（只用于算增量；不参与业务判定）。 */
    private Map<ResourceKey, Long> lastResourceAmounts = Map.of();
    /** 上次同步时的缓存版本号（{@code O(1)} 判断要不要扫缓存）。 */
    private long lastCacheVersion = Long.MIN_VALUE;
    /** 上次同步时的 active 状态（网络 / 名字变化时补一次 GridActivePacket）。 */
    private boolean lastActive;
    /** 首次快照是否已发送（保证「打开界面时一定有权威值」）。 */
    private boolean configSynced;

    /**
     * 客户端 extras 面板里「升级槽第一格内容」的菜单坐标（相对 {@code leftPos / topPos}）。
     * <p>槽位坐标是 {@code Slot} 的构造参数且不可变，因此屏幕在算出面板真实位置后**重排一次
     * {@link #resized}**，让槽位在创建时就落在面板里（而不是事后改 {@code Slot.x} —— 1.21 里
     * 那两个字段是 final）。服务端不看这两个值，默认值只用于 {@code resized(0,0,0)} 的占位。</p>
     */
    private int panelOriginX = UPGRADE_X;
    private int panelOriginY = UPGRADE_Y0;

    // ==================== 构造器（服务端 / 客户端各一条） ====================

    /** 客户端构造器（普通 {@code MenuType} 工厂调用；资源由 S2C 快照灌入）。 */
    public NetworkResourceReceiverMenu(final int id, final Inventory inventory) {
        super(RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_MENU.get(), id, inventory, EMPTY_GRID_DATA);
        this.receiver = null;
        this.receiverGrid = null;
        this.upgradeContainer = new RsccSpeedStackUpgradeContainer(
            new RsccSpeedStackUpgradeContainer.Host() {
                @Override
                public void dropStack(final ItemStack stack) {
                    // 客户端镜像容器：永远不会被弹出（真正的校验只在服务端做）
                }

                @Override
                public void onUpgradesChanged() {
                    // 客户端镜像容器：无需落盘
                }
            },
            () -> false);
        this.data = new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
        // RS 的 GridContainerMenu 也在构造器里先 resized(0,0,0)，保证菜单在屏幕 init 之前就有槽。
        resized(0, 0, 0);
    }

    /** 服务端构造器（唯一会注册 watcher 的那一条）：{@code grid} 由 {@link #create} 现场构造。 */
    private NetworkResourceReceiverMenu(final int id,
                                        final Inventory inventory,
                                        final NetworkResourceReceiverBlockEntity receiver,
                                        final ReceiverGrid grid) {
        super(RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_MENU.get(), id, inventory, grid);
        this.receiver = receiver;
        this.receiverGrid = grid;
        this.upgradeContainer = receiver.getUpgradeContainer();
        this.data = receiver.getContainerData();
        addDataSlots(data);
        resized(0, 0, 0);
    }

    /** 方块打开入口（{@code NetworkResourceReceiverBlock#getMenuProvider} 调它）。 */
    public static NetworkResourceReceiverMenu create(final int id,
                                                     final Inventory inventory,
                                                     final NetworkResourceReceiverBlockEntity receiver) {
        return new NetworkResourceReceiverMenu(id, inventory, receiver, new ReceiverGrid(receiver));
    }

    // ==================== 槽位 ====================

    /**
     * RS 的拉伸屏幕在每次 {@code init()} 都会调它：先清空全部槽、再加玩家背包。
     * <p>第 132 轮在这里补 6 个升级槽（玩家背包之后），因此客户端与服务端的槽下标顺序完全一致：
     * {@code 0..35 = 玩家背包 / 快捷栏}、{@code 36..41 = 升级槽}。</p>
     */
    @Override
    public void resized(final int playerInventoryY, final int topYStart, final int topYEnd) {
        super.resized(playerInventoryY, topYStart, topYEnd);
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            addSlot(new UpgradeSlot(upgradeContainer, i,
                panelOriginX + (i % 2) * UPGRADE_DX,
                panelOriginY + (i / 2) * UPGRADE_DY,
                ALLOWED_UPGRADES));
        }
    }

    /**
     * 客户端专用：告诉菜单「extras 面板第一格升级槽在菜单坐标里的位置」，随后屏幕会重排槽位。
     * <p>服务端不调用它（服务端的槽位坐标对玩家不可见，也没有任何逻辑依赖坐标）。</p>
     */
    public void setClientPanelOrigin(final int originX, final int originY) {
        this.panelOriginX = originX;
        this.panelOriginY = originY;
    }

    // ==================== 只读访问 ====================

    /** 服务端方块实体；客户端镜像菜单为 {@code null}。 */
    @Nullable
    public NetworkResourceReceiverBlockEntity getReceiver() {
        return receiver;
    }

    /** 升级容器（服务端 = 方块实体同一份；客户端 = 镜像）。 */
    public RsccSpeedStackUpgradeContainer getUpgradeContainer() {
        return upgradeContainer;
    }

    /** 每 tick 批数（速度升级旋钮）；新界面不显示，保留给服务端调参 / 诊断。 */
    public int getThrottleBatchesPerTick() {
        return data.get(0);
    }

    /** 单批搬运量（件）。 */
    public long getThrottleBatch() {
        return data.get(1);
    }

    /** 本仓当前是否「配置完成」（有名字）——客户端只服务服务端逻辑与调试。 */
    public boolean isConfigured() {
        return receiver != null && receiver.isProperlyConfigured();
    }

    // ==================== 名字（逐字沿用既有语义） ====================

    /**
     * 名字是否「形状合法」：去掉首尾空白后非空、不超长、不含控制字符或 {@code §}。
     * <p>客户端输入框的即时提示与服务端最终拒绝必须用<b>同一段代码</b>判断形状。</p>
     */
    public static boolean isValidName(final String trimmedName) {
        if (trimmedName == null || trimmedName.isEmpty() || trimmedName.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmedName.length(); i++) {
            final char ch = trimmedName.charAt(i);
            if (ch < 0x20 || ch == 0x7F || ch == '\u00a7') {
                return false;
            }
        }
        return true;
    }

    /** 名字形状为什么被拒（供界面提示；{@code OK} 表示形状没问题）。 */
    public static NameApplyResult checkNameShape(final String rawName) {
        final String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return NameApplyResult.EMPTY;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return NameApplyResult.TOO_LONG;
        }
        if (!isValidName(name)) {
            return NameApplyResult.INVALID_CHARS;
        }
        return NameApplyResult.OK;
    }

    /** 结果 → 提示语言键（{@code OK} 也有成功提示；{@code NOT_SERVER} 不提示）。 */
    public static String messageKeyFor(final NameApplyResult result) {
        return switch (result) {
            case OK -> MSG_NAME_APPLIED;
            case EMPTY -> MSG_NAME_EMPTY;
            case TOO_LONG -> MSG_NAME_TOO_LONG;
            case INVALID_CHARS -> MSG_NAME_INVALID;
            case TAKEN -> MSG_NAME_TAKEN;
            case NOT_SERVER -> "";
        };
    }

    /**
     * 服务端应用一次改名（唯一写入口）。
     * <p>校验顺序：服务端 → 空名 → 长度 / 字符 → 重名（先判等、再判重）→ 写入 → 回发完整快照。</p>
     */
    public NameApplyResult applyNameFromUi(final String rawName) {
        if (!isServerAuthoritative()) {
            return NameApplyResult.NOT_SERVER;
        }
        final String name = rawName == null ? "" : rawName.trim();
        final NameApplyResult shape = checkNameShape(name);
        if (shape != NameApplyResult.OK) {
            sendConfigSync(messageKeyFor(shape));
            return shape;
        }
        // 名字没变 ⇒ 直接成功（先判等：否则「保持原名」会被自己的名字判成冲突）
        if (name.equals(receiver.getReceiverName())) {
            sendConfigSync(MSG_NAME_APPLIED);
            return NameApplyResult.OK;
        }
        if (receiver.isNameTakenInNetwork(name)) {
            sendConfigSync(MSG_NAME_TAKEN);
            return NameApplyResult.TAKEN;
        }
        receiver.setReceiverName(name);
        sendConfigSync(MSG_NAME_APPLIED);
        return NameApplyResult.OK;
    }

    // ==================== 服务端：缓存 → 网格增量 + 全量快照 ====================

    /**
     * 把权威快照（名字 + 已占用名字 + 结果提示 + {@link GridData} 全量资源）发给客户端。
     * <p><b>改名路径专用</b>：占用集合可能刚变化，因此强制重算。</p>
     */
    public void sendConfigSync(final String messageKey) {
        occupiedSnapshot = null;
        sendConfigSyncInternal(messageKey);
    }

    /** 只发快照（占用集合沿用缓存；打开 / active 变化 / 改名后调用）。 */
    private void sendConfigSyncInternal(final String messageKey) {
        if (!isServerAuthoritative() || receiverGrid == null) {
            return;
        }
        final ServerPlayer player = serverPlayer();
        if (player == null) {
            return;
        }
        if (occupiedSnapshot == null) {
            occupiedSnapshot = List.copyOf(receiver.occupiedReceiverNamesInNetwork());
        }
        final Map<ResourceKey, Long> amounts = snapshotAmounts();
        final GridData gridData = gridDataOf(amounts);
        final boolean replaceGrid = !configSynced;
        if (replaceGrid) {
            // 只有「打开后的第一份」才让客户端清空仓库重建；改名只更新 extras / active，
            // 资源继续走 RS 的增量包（否则「全量」与「已排队的增量」会在客户端叠两次）。
            lastResourceAmounts = amounts;
            lastCacheVersion = receiver.cacheVersion();
        }
        lastActive = gridData.active();
        configSynced = true;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            SyncReceiverConfigPacket.of(containerId, receiver, occupiedSnapshot, messageKey,
                gridData, replaceGrid));
    }

    /**
     * 每 tick：版本号变了才扫缓存 + 发增量；active 变了补一次 RS 的 GridActivePacket。
     * <p>顺序很重要：增量先排进 RS 的 {@code pendingUpdates}，再 {@code super.broadcastChanges()}
     * 让它在同一 tick 内尝试 flush。</p>
     */
    @Override
    public void broadcastChanges() {
        if (isServerAuthoritative() && receiverGrid != null) {
            if (!configSynced) {
                sendConfigSyncInternal("");
            } else {
                if (receiver.cacheVersion() != lastCacheVersion) {
                    final Map<ResourceKey, Long> current = snapshotAmounts();
                    sendResourceDiffs(lastResourceAmounts, current);
                    lastResourceAmounts = current;
                    lastCacheVersion = receiver.cacheVersion();
                }
                final boolean active = receiverGrid.isGridActive();
                if (active != lastActive) {
                    lastActive = active;
                    onActiveChanged(active);
                }
            }
        }
        super.broadcastChanges();
    }

    /**
     * 把「上次总量 → 这次总量」的差逐条交给 RS 的 watcher 入口。
     * <p>只对 {@code delta != 0} 的资源调用；消失的资源用负 delta（RS 的
     * {@code ResourceRepositoryImpl#update} 会在总量归零时把它从视图里删掉）。</p>
     */
    private void sendResourceDiffs(final Map<ResourceKey, Long> previous,
                                   final Map<ResourceKey, Long> current) {
        for (final Map.Entry<ResourceKey, Long> entry : current.entrySet()) {
            final long before = previous.getOrDefault(entry.getKey(), 0L);
            final long delta = entry.getValue() - before;
            if (delta != 0L) {
                onChanged(entry.getKey(), delta, null);
            }
        }
        for (final Map.Entry<ResourceKey, Long> entry : previous.entrySet()) {
            if (!current.containsKey(entry.getKey()) && entry.getValue() != 0L) {
                onChanged(entry.getKey(), -entry.getValue(), null);
            }
        }
    }

    /** 缓存当前全量（资源身份 → 总量；顺序 = 物品在前、流体在后，与 {@code cacheEntries()} 同源）。 */
    private Map<ResourceKey, Long> snapshotAmounts() {
        final Map<ResourceKey, Long> out = new LinkedHashMap<>();
        if (receiver == null) {
            return out;
        }
        for (final ReceiverCacheEntry entry : receiver.cacheEntries()) {
            final ResourceKey key = resourceKeyOf(entry);
            if (key != null && entry.amount() > 0L) {
                out.put(key, entry.amount());
            }
        }
        return out;
    }

    /** 由「资源 → 总量」构造 RS 的 {@link GridData}（active 由 ReceiverGrid 判定）。 */
    private GridData gridDataOf(final Map<ResourceKey, Long> amounts) {
        final List<GridData.GridResource> resources = new ArrayList<>(amounts.size());
        for (final Map.Entry<ResourceKey, Long> entry : amounts.entrySet()) {
            if (entry.getValue() > 0L) {
                resources.add(new GridData.GridResource(
                    new ResourceAmount(entry.getKey(), entry.getValue()), Optional.empty()));
            }
        }
        final boolean active = receiverGrid != null && receiverGrid.isGridActive();
        return new GridData(active, resources, Set.of());
    }

    // ==================== 客户端：快照 → RS 资源仓库 ====================

    /**
     * 客户端：用 S2C 的全量 {@link GridData} 重建 RS 资源仓库。
     * <p>这是本类唯一「客户端写显示层」的入口（只写仓库，不碰方块实体 / 不写缓存）。
     * 逐条走 {@link #onResourceUpdate} 是为了复用 RS 的资源映射（物品 / 流体图标、数量排序），
     * 而不是另写一套「客户端自己的网格列表」。</p>
     */
    public void applyClientGridSnapshot(final GridData gridData) {
        if (receiver != null || gridData == null) {
            return;
        }
        getRepository().setPreventSorting(true);
        onClear();
        for (final GridData.GridResource resource : gridData.resources()) {
            if (resource == null || resource.resourceAmount() == null
                || resource.resourceAmount().amount() <= 0L) {
                continue;
            }
            onResourceUpdate(resource.resourceAmount().resource(),
                resource.resourceAmount().amount(),
                resource.trackedResource().orElse(null));
        }
        getRepository().setPreventSorting(false);
        getRepository().sort();
        onActiveChanged(gridData.active());
    }

    // ==================== C2S 旧动作（第 132 轮已不再使用） ====================

    /**
     * 第 131 轮及以前：{@link ReceiverCacheActionPacket} 的「取物品 / 取流体 / 翻页」入口。
     *
     * <p>第 132 轮起界面是 RS 的 {@code GridScreen}：点击由 RS 发 {@code GridExtractPacket}，
     * 服务端落到 {@link #onExtract} → {@code ReceiverGrid.createOperations(...).extract(...)}，
     * 旧的三道守卫 / 取物实现整体删除。这里保留方法只为让旧包类的注册链路继续编译；
     * <b>它不写任何东西</b>（即使客户端伪造旧包也取不走一个物品）。</p>
     */
    public static void handleCacheAction(final ServerPlayer player,
                                         final ReceiverCacheActionPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        final net.minecraft.world.inventory.AbstractContainerMenu open = player.containerMenu;
        if (open == null || open.containerId != packet.containerId()) {
            return;
        }
        if (!(open instanceof NetworkResourceReceiverMenu menu)) {
            return;
        }
        if (!menu.stillValid(player)) {
            return;
        }
        // 故意为空：取物唯一通道 = RS GridExtractPacket → onExtract → CacheGridOperations#extract。
    }

    // ==================== 槽位移动 / 有效性 ====================

    /**
     * Shift + 左键：升级槽 ↔ 玩家背包。
     * <p>玩家背包 → 网格<b>不</b>存在（{@code insert} 恒 false），因此不在背包与「缓存」之间搬任何东西。</p>
     */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stackInSlot = slot.getItem();
        final ItemStack copy = stackInSlot.copy();
        if (index < PLAYER_END) {
            if (!moveItemStackTo(stackInSlot, UPGRADE_START, UPGRADE_START + UPGRADE_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        }
        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /**
     * 有效性：服务端问 {@link ReceiverGrid#canMenuStayOpen}（方块实体还在 + 距离）；
     * 客户端镜像菜单（{@code receiver == null}）恒 true，由服务端负责关屏。
     */
    @Override
    public boolean stillValid(final Player player) {
        if (receiver == null) {
            return true;
        }
        return receiverGrid != null && receiverGrid.canMenuStayOpen(player);
    }

    // ==================== 内部工具 ====================

    /** 把界面缓存条目转成 RS 的资源身份（物品 / 流体二选一）。 */
    @Nullable
    private static ResourceKey resourceKeyOf(final ReceiverCacheEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        if (entry.isFluid()) {
            return new FluidResource(entry.fluid().getFluid(), entry.fluid().getComponentsPatch());
        }
        return ItemResource.ofItemStack(entry.item());
    }

    /** 缓存里某种资源此刻的总量（0 = 没有）。 */
    private static long cachedAmount(final NetworkResourceReceiverBlockEntity receiver,
                                     final ResourceKey resource) {
        if (receiver == null || resource == null) {
            return 0L;
        }
        for (final ReceiverCacheEntry entry : receiver.cacheEntries()) {
            final ResourceKey key = resourceKeyOf(entry);
            if (resource.equals(key)) {
                return entry.amount();
            }
        }
        return 0L;
    }

    /** 从缓存真扣 / 模拟扣（物品按身份跨格，流体按流体 + 组件精确匹配）。 */
    private static long extractFromCache(final NetworkResourceReceiverBlockEntity receiver,
                                         final ResourceKey resource, final long amount,
                                         final boolean simulate) {
        if (receiver == null || resource == null || amount <= 0L) {
            return 0L;
        }
        if (resource instanceof ItemResource item) {
            return extractItems(receiver, item, amount, simulate);
        }
        if (resource instanceof FluidResource fluid) {
            final FluidStack drained = receiver.getFluidCache().drain(
                com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil
                    .toFluidStack(fluid, amount),
                simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
            return drained.getAmount();
        }
        return 0L;
    }

    private static long extractItems(final NetworkResourceReceiverBlockEntity receiver,
                                     final ItemResource item, final long amount,
                                     final boolean simulate) {
        final ItemStack wanted = item.toItemStack(1);
        long remaining = amount;
        long extracted = 0L;
        final var cache = receiver.getItemCache();
        for (int slot = 0; slot < cache.getSlots() && remaining > 0L; slot++) {
            final ItemStack stored = cache.getStackInSlot(slot);
            if (stored.isEmpty() || !ItemStack.isSameItemSameComponents(stored, wanted)) {
                continue;
            }
            final int take = (int) Math.min(remaining, stored.getCount());
            final ItemStack got = cache.extractItem(slot, take, simulate);
            extracted += got.getCount();
            remaining -= got.getCount();
        }
        return extracted;
    }

    /** 把余量灌回缓存（唯一回滚点）；缓存容量刚刚被腾出来，必然装得下。 */
    private static long insertIntoCache(final NetworkResourceReceiverBlockEntity receiver,
                                        final ResourceKey resource, final long amount) {
        if (receiver == null || resource == null || amount <= 0L) {
            return 0L;
        }
        if (resource instanceof ItemResource item) {
            long remaining = amount;
            long inserted = 0L;
            while (remaining > 0L) {
                final int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
                final ItemStack rest = receiver.getItemCache().insertItem(0, item.toItemStack(chunk), false);
                final long accepted = chunk - rest.getCount();
                if (accepted <= 0L) {
                    break;
                }
                inserted += accepted;
                remaining -= accepted;
            }
            return inserted;
        }
        if (resource instanceof FluidResource fluid) {
            return receiver.getFluidCache().fill(
                com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil
                    .toFluidStack(fluid, amount),
                IFluidHandler.FluidAction.EXECUTE);
        }
        return 0L;
    }

    /** 本菜单是否持有<b>服务端</b>的权威方块实体（客户端镜像菜单恒为 false）。 */
    private boolean isServerAuthoritative() {
        return receiver != null && receiver.getLevel() != null && !receiver.getLevel().isClientSide();
    }

    @Nullable
    private ServerPlayer serverPlayer() {
        return playerInventory.player instanceof ServerPlayer server ? server : null;
    }

    // ==================== Grid 适配器（服务端：把缓存伪装成一张 RS 网格） ====================

    /**
     * 把 {@code itemCache / fluidCache} 适配成 RS 的 {@link Grid}。
     *
     * <p>只实现 RS 网格真正会调用的东西：资源列表、active、物品存储（供流体换桶时找空桶）、
     * 取出操作、watcher（一个 {@link GridNetworkNode} 实例）。自动合成相关一律空 / 0
     * —— 接收器不是自动合成源。</p>
     */
    static final class ReceiverGrid implements Grid {
        private final NetworkResourceReceiverBlockEntity receiver;
        /**
         * RS 的 watcher 管理器：本网格的 watcher 注册 / 注销必须走它（菜单 {@code removed} 会调
         * {@link #removeWatcher}）。本网格不挂在任何网络节点上，因此网络侧 rootStorage 为 null，
         * 只做本地登记，不会把缓存的更新算两次。
         */
        private final GridNetworkNode node = new GridNetworkNode(0L);
        /** 流体换桶时 RS 会问「网格里还有没有空桶」——返回缓存里的物品视图。 */
        private final CacheItemStorage itemStorage;

        ReceiverGrid(final NetworkResourceReceiverBlockEntity receiver) {
            this.receiver = receiver;
            this.itemStorage = new CacheItemStorage(receiver);
        }

        @Override
        public void addWatcher(final GridWatcher watcher, final Class<? extends Actor> actorType) {
            node.addWatcher(watcher, actorType);
        }

        @Override
        public void removeWatcher(final GridWatcher watcher) {
            node.removeWatcher(watcher);
        }

        @Override
        public Storage getItemStorage() {
            return itemStorage;
        }

        @Override
        public boolean isGridActive() {
            return receiver.getLevel() != null
                && !receiver.isRemoved()
                && receiver.getNode().isActive()
                && receiver.isProperlyConfigured();
        }

        @Override
        public List<TrackedResourceAmount> getResources(final Class<? extends Actor> actorType) {
            final List<TrackedResourceAmount> out = new ArrayList<>();
            for (final ReceiverCacheEntry entry : receiver.cacheEntries()) {
                final ResourceKey key = resourceKeyOf(entry);
                if (key != null && entry.amount() > 0L) {
                    out.add(new TrackedResourceAmount(
                        new ResourceAmount(key, entry.amount()), null));
                }
            }
            return out;
        }

        @Override
        public Set<PlatformResourceKey> getAutocraftableResources() {
            return Set.of();
        }

        @Override
        public GridOperations createOperations(final ResourceType resourceType, final ServerPlayer player) {
            return new CacheGridOperations(receiver, player);
        }

        @Override
        public boolean canMenuStayOpen(final Player player) {
            // 与请求器 stillValidFor 同一判据：方块实体身份 + 8 格（64.0 平方距离）。
            return receiver.getLevel() != null
                && receiver.getLevel().getBlockEntity(receiver.getBlockPos()) == receiver
                && player.distanceToSqr(receiver.getBlockPos().getX() + 0.5,
                receiver.getBlockPos().getY() + 0.5,
                receiver.getBlockPos().getZ() + 0.5) <= 64.0;
        }

        // ---------- PreviewProvider：接收器不提供自动合成 ----------

        @Override
        public CompletableFuture<Optional<Preview>> getPreview(final ResourceKey resource, final long amount,
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
        public Optional<TaskId> startTask(final ResourceKey resource, final long amount,
                                          final Actor actor, final boolean notify,
                                          final CancellationToken cancellationToken) {
            return Optional.empty();
        }
    }

    /**
     * 缓存自身的物品存储视图（只给 RS 的流体换桶路径用）。
     *
     * <p>{@code extract} 会真的从 {@code itemCache} 扣减（RS 的
     * {@code FluidGridExtractionStrategy} 在「背包没有空桶」时会先 SIMULATE 问一次）；
     * {@code insert} 用于「满桶发不出去时把空桶还回来」这条回滚路径。</p>
     */
    private static final class CacheItemStorage implements Storage {
        private final NetworkResourceReceiverBlockEntity receiver;

        CacheItemStorage(final NetworkResourceReceiverBlockEntity receiver) {
            this.receiver = receiver;
        }

        @Override
        public Collection<ResourceAmount> getAll() {
            final List<ResourceAmount> out = new ArrayList<>();
            for (final ReceiverCacheEntry entry : receiver.cacheEntries()) {
                if (entry.isFluid() || entry.item().isEmpty()) {
                    continue;
                }
                out.add(new ResourceAmount(ItemResource.ofItemStack(entry.item()), entry.amount()));
            }
            return out;
        }

        @Override
        public long getStored() {
            return receiver.getItemTotalCount();
        }

        @Override
        public long insert(final ResourceKey resource, final long amount, final Action action,
                           final Actor actor) {
            if (!(resource instanceof ItemResource item)) {
                return 0L;
            }
            final boolean simulate = action == Action.SIMULATE;
            long remaining = amount;
            long inserted = 0L;
            while (remaining > 0L) {
                final int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
                final ItemStack rest = receiver.getItemCache().insertItem(0, item.toItemStack(chunk), simulate);
                final long accepted = chunk - rest.getCount();
                if (accepted <= 0L) {
                    break;
                }
                inserted += accepted;
                remaining -= accepted;
            }
            if (!simulate && inserted > 0L) {
                receiver.setChanged();
            }
            return inserted;
        }

        @Override
        public long extract(final ResourceKey resource, final long amount, final Action action,
                            final Actor actor) {
            if (!(resource instanceof ItemResource item)) {
                return 0L;
            }
            final boolean simulate = action == Action.SIMULATE;
            final long extracted = extractItems(receiver, item, amount, simulate);
            if (!simulate && extracted > 0L) {
                receiver.setChanged();
            }
            return extracted;
        }
    }

    /**
     * 从缓存取出的操作集。
     * <p>与 RS 自己的 {@code GridOperationsImpl} 同语义：先模拟「缓存能扣多少」，再模拟
     * 「目标能收多少」，只按目标承诺的量真扣；真写入时目标只收一部分就把余量灌回缓存。
     * {@link #insert} 恒 false —— 界面不能放入。</p>
     */
    private static final class CacheGridOperations implements GridOperations {
        private final NetworkResourceReceiverBlockEntity receiver;
        private final Actor actor;

        CacheGridOperations(final NetworkResourceReceiverBlockEntity receiver, final ServerPlayer player) {
            this.receiver = receiver;
            this.actor = new PlayerActor(player);
        }

        @Override
        public boolean extract(final ResourceKey resource, final GridExtractMode extractMode,
                               final InsertableStorage destination) {
            if (receiver == null || resource == null || destination == null) {
                return false;
            }
            final long available = cachedAmount(receiver, resource);
            if (available <= 0L) {
                return false;
            }
            final long want = switch (extractMode) {
                case ENTIRE_RESOURCE -> available;
                case HALF_RESOURCE -> available == 1L ? 1L : available / 2L;
                case SINGLE_RESOURCE -> 1L;
            };
            if (want <= 0L) {
                return false;
            }
            // ① 模拟缓存能扣多少
            final long simulated = extractFromCache(receiver, resource, want, true);
            if (simulated <= 0L) {
                return false;
            }
            // ② 模拟目标能收多少（光标 / 背包 / 流体容器）
            final long accepted = destination.insert(resource, simulated, Action.SIMULATE, actor);
            if (accepted <= 0L) {
                return false;
            }
            // ③ 只按目标承诺的量真扣
            final long extracted = extractFromCache(receiver, resource, accepted, false);
            if (extracted <= 0L) {
                return false;
            }
            // ④ 真写；目标只收一部分 ⇒ 余量灌回缓存（绝不销毁）
            final long inserted = destination.insert(resource, extracted, Action.EXECUTE, actor);
            if (inserted < extracted) {
                insertIntoCache(receiver, resource, extracted - inserted);
            }
            if (extracted > 0L) {
                receiver.setChanged();
            }
            return inserted > 0L;
        }

        @Override
        public boolean insert(final ResourceKey resource, final GridInsertMode insertMode,
                              final ExtractableStorage source) {
            // 界面不能放入：RS 的 GridInsertPacket 即使被伪造，落到这里也只会得到 false。
            return false;
        }
    }
}
