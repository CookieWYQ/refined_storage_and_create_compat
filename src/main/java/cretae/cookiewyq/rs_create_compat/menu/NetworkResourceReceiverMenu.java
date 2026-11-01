package cretae.cookiewyq.rs_create_compat.menu;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheActionPacket;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheEntry;
import cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket;
import cretae.cookiewyq.rs_create_compat.support.RsccUnboundedFluidStorage;
import cretae.cookiewyq.rs_create_compat.support.RsccUnboundedItemStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 网络资源接收器菜单：<b>6 个升级槽 + 终端样式可滚动网格（物品 / 流体混排）+ 玩家背包</b>。
 *
 * <h2>第 131 轮：从「分页列表」改成「终端网格」（用户第 6 张图的意见）</h2>
 * <p>用户原话：「<i>这个网络资源接收器这个显示的很莫名其妙……这就相当于是一个取东西的地方，
 * 它应该是保持和那个终端是一样的，就是终端它不是可以滚动嘛，和终端一样可以滚动</i>」。
 * 于是本轮的界面形态 = 和 RS 终端一致的<b>格子网格</b>：
 * <ul>
 *     <li>格子是 {@link #CELL}×{@link #CELL}（18px，MC 标准槽位），格内是物品 / 流体图标 + 数量角标；</li>
 *     <li><b>滚轮 + 滚动条</b>（RS 原版 {@code ScrollbarWidget}）按<b>行</b>滚动：
 *     服务端下发「从第 {@link #window} 行开始的一屏格子」，客户端只读这一屏；</li>
 *     <li>点击格子 = 取出：左键 = 取一组 / 换一桶，Shift = 尽量全取（口径与第 96 轮逐字一致）；</li>
 *     <li>界面里<b>没有</b>任何状态句、页码句、摘要句、「取全部」按钮（用户：「好多废话」）——
 *     这些数字要么在格子里（数量角标），要么根本不需要（已命名 / 输出朝向 / 节拍）。</li>
 * </ul>
 *
 * <h2>为什么滚动仍然要走服务端（而不是客户端本地切页）</h2>
 * <p>RS 2.0.9 自己的网格也是这样做的：屏幕上显示的那一屏由服务端算（{@code GridScrollPacket}
 * 把滚动行回传服务端，服务端再同步网格视图），客户端<b>不</b>持有全量镜像。
 * 我们沿用同一模式：页首行（{@link #firstRow}）是服务端菜单状态，滚轮 / 滚动条只发
 * {@link ReceiverCacheActionPacket#ACTION_PAGE}，由服务端夹到
 * {@code [0, rowCount - GRID_ROWS]} 后回发快照。这样「屏幕上画的那一屏」永远只有一份权威来源，
 * 不会出现「客户端自己滚了、服务端还在旧行」的分叉。</p>
 *
 * <h2>流体取出顺序：<b>按 RS 2.0.9 的真实顺序</b>（本轮唯一的顺序真相）</h2>
 * <p>用户原话：「<i>终端它那个流体怎么取的呢？……顺序你按照 RS 的来。</i>」+ 第 131 轮验收补一句
 * 「<i>这个顺序我不明白，你按照 RS 的来</i>」。因此这里不以「先网络再背包」这句话为准，
 * 而以 RS 2.0.9 的<b>代码顺序</b>为准：
 * {@code com.refinedmods.refinedstorage.neoforge.grid.strategy.FluidGridExtractionStrategy#onExtract}
 * （本地源码检出 {@code local_src/rs_src/.../FluidGridExtractionStrategy.java}，行号按该文件）：
 * <pre>
 *   :55  containerOnCursor  → 光标上有能装流体的容器：直接灌进光标容器
 *   :57  bucketInInventory  → 消耗<b>玩家背包</b>里的一个空桶，把流体灌成满桶交给玩家
 *   :59  bucketInStorage    → 背包里没有桶，才从<b>网络物品存储</b>里取一个空桶
 *   :62  都没有            → 只 return true，什么都不做（<b>一个 mB 都不扣</b>）
 * </pre>
 * <p>注意 {@code :57} 在 {@code :59} 之前 —— RS 是「<b>背包优先、网络兜底</b>」
 * （先消耗玩家自己带的桶，玩家没带才动网络里的桶）。本类逐字照此实现，
 * 并把「光标容器」保留为第 0 步（{@code :55}，契约 §3.B.4 没写这一步，但少了它
 * 「手上拿着空桶点流体」会取不出来）。</p>
 * <p><b>守恒不受顺序影响</b>：无论是谁的桶，都是「扣 1000 mB = 消耗 1 个空桶 = 发出 1 个满桶」；
 * 每条路径失败都回滚（RS 的 {@code insertSourceBucket} 同一语义，见 :105-108 / :122-128）。</p>
 *
 * <h2>客户端重建菜单时绝不读方块实体</h2>
 * <p>{@link #receiver} 在客户端恒为 {@code null}；客户端只读 S2C 快照
 * （{@link SyncReceiverConfigPacket}）与 {@link ContainerData}，任何「本地重算」都会与服务端分叉。</p>
 *
 * <h2>第 ① 层准入校验就在这个类里</h2>
 * <p>{@link UpgradeSlot} 的构造器显式传入 {@code List.of("speed_upgrade", "stack_upgrade")}：
 * 白名单之外的任何物品在 {@code mayPlace} 里被拒。上限走 {@link UpgradeSlot#maxPerUpgrade}
 * （这两类都是 {@link UpgradeSlot#MAX_PER_UPGRADE} = 6）。方块实体侧还有第 ② 层兜底
 * （{@code RsccSpeedStackUpgradeContainer#validate}），第 ③ 层是 RS 升级注册表。</p>
 *
 * <h2>节流数值不在这里算</h2>
 * <p>「每 tick 几批 / 每批多少」由方块实体经 {@link ContainerData}（见 {@link #DATA_COUNT}）每 tick 广播。
 * 第 131 轮界面不再显示它（用户：节拍那句是废话），但数据槽保留 —— 服务端调参 / 诊断仍需要它。</p>
 */
public class NetworkResourceReceiverMenu extends AbstractContainerMenu {
    /** 升级槽数量 = 方块实体的槽数（唯一来源，避免两处各写一个 6）。 */
    public static final int UPGRADE_COUNT = cretae.cookiewyq.rs_create_compat.support
        .RsccSpeedStackUpgradeContainer.SLOTS;
    /** 升级槽起始下标。 */
    public static final int UPGRADE_START = 0;
    /** 玩家背包 / 快捷栏起始下标（升级槽之后就是它：网格<b>没有</b>真实槽位）。 */
    public static final int PLAYER_START = UPGRADE_START + UPGRADE_COUNT;

    // ==================== 网格（终端样式：18px 格子 + 按行滚动） ====================

    /**
     * 网格列数 = <b>9</b>（与 RS 终端 / 原版背包同一列数，玩家一眼就懂）。
     * <p>为什么不是 10 或更多：面板宽 236，扣掉滚动条（x=174 起 12px）与右侧升级槽栏（x=214 起）
     * 之后，网格可用宽度就是 {@code 174 - 9 = 165px}，9 列 × 18px = 162px 刚好；10 列需要 180px，
     * 会压到滚动条。</p>
     */
    public static final int GRID_COLS = 9;
    /**
     * 网格行数 = <b>7</b>：上边界 {@link #GRID_Y0}（38，名字行下沿）到下边界 164（玩家背包标签 166 之上），
     * 可用 126px = 7 × 18px，一格不剩、也一格不挤。
     */
    public static final int GRID_ROWS = 7;
    /** 格子边长（18 = MC 标准槽位；物品图标 16px 居中留 1px 边）。 */
    public static final int CELL = 18;
    /** 网格左上角（Menu 坐标 = 物品图标左上角；槽框在 PNG 里烘焙在 −1 处）。 */
    public static final int GRID_X0 = 9;
    public static final int GRID_Y0 = 38;
    /** 网格像素宽 / 高（= 列数 / 行数 × {@link #CELL}）。 */
    public static final int GRID_W = GRID_COLS * CELL;
    public static final int GRID_H = GRID_ROWS * CELL;
    /** 一屏的格子数 = 一次快照最多下发的条数。 */
    public static final int WINDOW_SIZE = GRID_COLS * GRID_ROWS;

    /** 滚动条（RS 原版 {@code ScrollbarWidget}，宽 12）左上角：网格右侧、升级槽框（x=213）之前。 */
    public static final int SCROLLBAR_X = GRID_X0 + GRID_W + 3;
    public static final int SCROLLBAR_Y = GRID_Y0;
    /** 滚动条高度 = 网格总高。 */
    public static final int SCROLLBAR_H = GRID_H;
    /** 滚动条宽度（RS {@code ScrollbarWidget.Type.NORMAL} 的宽度，只用于几何断言）。 */
    public static final int SCROLLBAR_W = 12;

    /** 玩家背包 / 快捷栏（与界面类同名常量同源）。 */
    public static final int INV_X0 = 9;
    public static final int INV_Y0 = 176;
    public static final int HOTBAR_Y = 230;

    /** 面板尺寸（与界面类同名常量同源）。 */
    public static final int PANEL_W = 236;
    public static final int PANEL_H = 252;

    /** 升级槽列（界面右侧独立栏，与范围充电器同一布局口径）。 */
    public static final int UPGRADE_X = 214;
    public static final int UPGRADE_Y0 = 7;

    /** 升级槽白名单：只有这两类（用户拍板「其他升级不可以安装」）。 */
    public static final List<String> ALLOWED_UPGRADES = List.of("speed_upgrade", "stack_upgrade");

    /**
     * 同步数据槽位数（与方块实体 {@code getContainerData()} 的 {@code getCount()} 必须相等）。
     * <p>0 = <b>每 tick 批数</b>（速度升级旋钮），1 = 单批搬运量（件，叠加升级旋钮）。
     * 第 131 轮界面不再显示这两行字，但数据槽保留：服务端调参 / 诊断与历史自检仍读它。</p>
     */
    public static final int DATA_COUNT = 2;

    /**
     * 接收器名字的硬上限（字符数，按 {@code String#length()} 计）。
     * <p>为什么要有上界：名字会被写进 NBT、随配置同步包下发、并作为请求器的目标地址显示在界面上。</p>
     */
    public static final int MAX_NAME_LENGTH = 32;

    /** 一次流体交互的量：1 桶 = 1000 mB（与归流缓存仓 / 用户口径一致）。 */
    public static final long BUCKET_MB = 1000L;

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

    /** 取缓存的结果提示（同样是「服务端只发 key」，界面用动作条翻译显示）。 */
    public static final String MSG_CACHE_TAKEN_ALL = "gui.rs_create_compat.network_resource_receiver.msg.cache_taken_all";
    public static final String MSG_CACHE_BAG_FULL = "gui.rs_create_compat.network_resource_receiver.msg.cache_bag_full";
    public static final String MSG_CACHE_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.cache_empty";
    public static final String MSG_FLUID_TAKEN = "gui.rs_create_compat.network_resource_receiver.msg.fluid_taken";
    public static final String MSG_FLUID_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.fluid_empty";
    public static final String MSG_FLUID_INSUFFICIENT =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_insufficient";
    /** 「哪都找不到能装它的容器」——用户口径：<b>取不出就不扣，一个 mB 都不扣</b>。 */
    public static final String MSG_FLUID_NO_CONTAINER =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_container";
    public static final String MSG_FLUID_NO_SPACE =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_space";
    public static final String MSG_FLUID_GONE = "gui.rs_create_compat.network_resource_receiver.msg.fluid_gone";

    /** 缓存摘要 / 网格窗口的复查间隔（tick）：只在指纹变化时真正发包，绝不每 tick 拼数据。 */
    private static final int CACHE_SYNC_INTERVAL = 10;

    /** 空桶的资源键（RS 的 {@code BUCKET_ITEM_RESOURCE} 同一口径；网络路径按它找容器）。 */
    private static final ItemResource BUCKET_ITEM_RESOURCE = ItemResource.ofItemStack(new ItemStack(Items.BUCKET));

    @Nullable
    private final NetworkResourceReceiverBlockEntity receiver;

    /**
     * 服务端权威的节流 / 升级数据（见 {@link #DATA_COUNT}）。
     * <p>客户端重建菜单时 {@code receiver == null}，任何「按槽位自己重算」都会与方块实体分叉。</p>
     */
    private final ContainerData data;

    /**
     * 服务端当前<b>页首行</b>（0 基，单位 = 网格行）。
     * <p><b>客户端不持有权威行号</b>：界面显示的是 S2C 快照里的 {@code page}（含义 = 页首行），
     * 滚动请求经 {@link ReceiverCacheActionPacket#ACTION_PAGE} 回到服务端后再由快照回发。</p>
     */
    private int firstRow;

    /** 上一次同步给这个界面的「窗口 + 行号」指纹（只用于判断要不要补发快照，不参与业务）。 */
    private long lastSentSignature = Long.MIN_VALUE;
    /** 网格窗口复查倒计时。 */
    private int cacheSyncCooldown = CACHE_SYNC_INTERVAL;

    /** 当前打开本菜单的服务端玩家（首次由 {@link #broadcastChanges()} 反查后缓存）。 */
    @Nullable
    private ServerPlayer serverPlayer;
    /** 首次快照是否已发送（保证「打开界面时一定有权威值」，且之后只在变更时发）。 */
    private boolean configSynced;
    /**
     * 本网络已占用名字的缓存（只在改名时重算）。
     * <p>网格窗口的补发不应该每 10 tick 枚举一次整张网络图；占用集合只是客户端的即时提示，
     * 真正判据永远在服务端。</p>
     */
    @Nullable
    private List<String> occupiedSnapshot;

    /** 客户端重建菜单用（无方块实体；数据由同步通道回填）。 */
    public NetworkResourceReceiverMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public NetworkResourceReceiverMenu(final int id,
                                       final Inventory inventory,
                                       @Nullable final NetworkResourceReceiverBlockEntity receiver) {
        super(RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_MENU.get(), id);
        this.receiver = receiver;
        this.data = receiver != null
            ? receiver.getContainerData()
            : new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
        // 升级槽：6 格竖排，白名单只有速度 + 叠加（第 ① 层准入）
        final cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer upgrades =
            receiver != null
                ? receiver.getUpgradeContainer()
                : new cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer(
                    new cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer.Host() {
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
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            addSlot(new UpgradeSlot(upgrades, i, UPGRADE_X, UPGRADE_Y0 + i * 18, ALLOWED_UPGRADES));
        }
        // 玩家主物品栏（3 行 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INV_X0 + col * 18, INV_Y0 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INV_X0 + col * 18, HOTBAR_Y));
        }
    }

    public static NetworkResourceReceiverMenu create(final int id,
                                                     final Inventory inventory,
                                                     final NetworkResourceReceiverBlockEntity receiver) {
        return new NetworkResourceReceiverMenu(id, inventory, receiver);
    }

    /** 纯 getter：暴露本菜单持有的方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public NetworkResourceReceiverBlockEntity getReceiver() {
        return receiver;
    }

    // ==================== 节流数值（界面只读这里，绝不自己算） ====================

    /**
     * 每 tick 批数（速度升级旋钮）：1 / 2 / 4 / … / 64。
     * <p>第 131 轮界面不再显示它（用户：节拍是废话），但保留 getter：服务端调参 / 诊断 / 历史自检仍要用。</p>
     */
    public int getThrottleBatchesPerTick() {
        return data.get(0);
    }

    /** 单批搬运量（件）。 */
    public long getThrottleBatch() {
        return data.get(1);
    }

    /**
     * 本仓当前是否「配置完成」（有名字）—— 客户端读服务端权威值。
     * <p>客户端重建的菜单（{@code receiver == null}）恒为 false；界面在客户端改读 S2C 快照里的
     * {@code SyncReceiverConfigPacket#name}，因此这里只服务服务端逻辑与调试。</p>
     */
    public boolean isConfigured() {
        return receiver != null && receiver.isProperlyConfigured();
    }

    // ==================== 名字（逐字沿用既有语义，第 131 轮未改） ====================

    /**
     * 名字是否「形状合法」：去掉首尾空白后非空、不超长、不含控制字符或 {@code §}。
     *
     * <p><b>为什么这个判据是 static 且公开</b>：客户端输入框的即时提示与服务端的最终拒绝必须
     * 用<b>同一段代码</b>判断形状（否则会出现「客户端说可以、服务端说不行」）。</p>
     */
    public static boolean isValidName(final String trimmedName) {
        if (trimmedName == null || trimmedName.isEmpty() || trimmedName.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmedName.length(); i++) {
            final char ch = trimmedName.charAt(i);
            if (ch < 0x20 || ch == 0x7F || ch == '\u00a7') {
                return false; // 控制字符 / 颜色码：会把界面和聊天行弄乱
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
     *
     * <h2>校验顺序（任何一步失败都<b>不写入</b>，并把原因回发界面）</h2>
     * <ol>
     *     <li><b>必须是服务端</b>：客户端镜像菜单（{@code receiver == null}）直接返回 {@code NOT_SERVER}；</li>
     *     <li><b>空名</b>：{@code trim()} 后为空 ⇒ {@code EMPTY}；</li>
     *     <li><b>长度 / 字符</b>：超过 {@link #MAX_NAME_LENGTH} 或含控制字符 / {@code §} ⇒ 拒绝；</li>
     *     <li><b>重名</b>：{@code isNameTakenInNetwork(name)}（判据在方块实体里，<b>不在本类重写</b>）
     *     ⇒ {@code TAKEN}；<b>改成本名不算冲突</b>（先判等、再判重——顺序不能反）；</li>
     *     <li>全部通过才 {@code setReceiverName(name)}，随后把权威快照回发。</li>
     * </ol>
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

    // ==================== 网格窗口：按行滚动 ====================

    /** 总行数（空缓存也算 1 行：界面不会因为「0 行」而算出负的最多滚动量）。 */
    public static int rowCountFor(final int totalTypes) {
        return Math.max(1, (Math.max(0, totalTypes) + GRID_COLS - 1) / GRID_COLS);
    }

    /** 页首行的最大值（= 总行数 − 可见行数；不足一屏时为 0 ⇒ 滚动条禁用）。 */
    public static int maxFirstRowFor(final int totalTypes) {
        return Math.max(0, rowCountFor(totalTypes) - GRID_ROWS);
    }

    /** 当前服务端页首行（0 基；客户端读它没有意义，界面只读 S2C 快照）。 */
    public int getFirstRow() {
        return firstRow;
    }

    /**
     * 服务端：处理一次滚动请求（滚轮 / 滚动条都走这里）。
     * <p>夹到 {@code [0, maxFirstRow]}；<b>边界也回一次快照</b>，让客户端与服务端行号严格一致
     * （否则「滚到最底再滚一下」会让客户端本地行号与服务端分叉）。</p>
     */
    public void applyScrollRequest(final int requested) {
        if (!isServerAuthoritative()) {
            return;
        }
        final int max = maxFirstRowFor(receiver.cacheEntries().size());
        firstRow = Math.max(0, Math.min(max, requested));
        sendConfigSyncInternal("");
    }

    // ==================== 取出：物品（真实扣减，绝不复制） ====================

    /**
     * 服务端：取出一格<b>物品</b>（{@code all = false} ⇒ 一组；{@code true} ⇒ 直到背包满）。
     *
     * <h2>守恒顺序（最重要的判据）</h2>
     * <ol>
     *     <li>按<b>资源身份</b>（{@link ItemStack#isSameItemSameComponents}，与网格聚合口径同源）
     *     在缓存里定位<b>所有</b>同身份槽位 —— 同一种物品可能横跨多格（每格 64）；</li>
     *     <li>先 {@code extractItem(..., EXECUTE)} 真正扣减（服务端唯一扣减点），拿到的是副本；</li>
     *     <li>只把<b>背包确实收下</b>的部分算作已取走，放不下的<b>原样回填缓存</b>
     *     （回填失败时掉在脚下 —— 绝不销毁）；</li>
     *     <li>于是「缓存扣减量 == 玩家获得量」恒成立，<b>不存在复制</b>。</li>
     * </ol>
     */
    private void takeItem(final Player player, final ItemStack identity, final boolean all) {
        final RsccUnboundedItemStorage cache = receiver.getItemCache();
        final ItemStack wanted = identity.copyWithCount(1);
        // 1) 先算「这一种一共多少」与「能取多少」
        int stored = 0;
        for (int slot = 0; slot < cache.getSlots(); slot++) {
            final ItemStack each = cache.getStackInSlot(slot);
            if (!each.isEmpty() && ItemStack.isSameItemSameComponents(each, wanted)) {
                stored += each.getCount();
            }
        }
        if (stored <= 0) {
            sendConfigSyncInternal(MSG_CACHE_EMPTY);
            return;
        }
        final int perStack = Math.max(1, Math.min(64, wanted.getMaxStackSize()));
        final int want = all ? stored : Math.min(perStack, stored);
        // 2) 逐格真实扣减（同身份可能横跨多格）
        ItemStack taken = ItemStack.EMPTY;
        int remaining = want;
        for (int slot = 0; slot < cache.getSlots() && remaining > 0; slot++) {
            final ItemStack each = cache.getStackInSlot(slot);
            if (each.isEmpty() || !ItemStack.isSameItemSameComponents(each, wanted)) {
                continue;
            }
            final ItemStack got = cache.extractItem(slot, Math.min(remaining, each.getCount()), false);
            if (got.isEmpty()) {
                continue;
            }
            remaining -= got.getCount();
            taken = taken.isEmpty() ? got : taken.copyWithCount(taken.getCount() + got.getCount());
        }
        if (taken.isEmpty()) {
            sendConfigSyncInternal(MSG_CACHE_EMPTY);
            return;
        }
        // 3) 塞背包：只有真收下的才算取走，放不下的回填缓存
        final ItemStack rest = taken.copy();
        player.getInventory().add(rest);
        final int given = taken.getCount() - rest.getCount();
        if (!rest.isEmpty()) {
            final ItemStack refused = cache.insertItem(0, rest, false);
            if (!refused.isEmpty()) {
                player.drop(refused, false); // 极端兜底：绝不销毁
            }
        }
        receiver.setChanged();
        // 4) 提示：放不下 ⇒ 明确说「剩下的还在缓存」；整张列表被取空 ⇒ 说「全部取出」；
        //    正常取走一两种时不刷提示（格子里的数量角标立刻变化，本身就是反馈）
        if (given <= 0) {
            sendConfigSyncInternal(MSG_CACHE_BAG_FULL);
        } else if (receiver.getItemTypeCount() <= 0) {
            sendConfigSyncInternal(MSG_CACHE_TAKEN_ALL);
        } else {
            sendConfigSyncInternal(rest.isEmpty() ? "" : MSG_CACHE_BAG_FULL);
        }
    }

    // ==================== 取出：流体（照 RS 2.0.9 的容器顺序） ====================

    /**
     * 服务端：把一格<b>流体</b>换成满容器（{@code 1000 mB/桶}）。
     *
     * <h2>容器来源顺序 = RS 2.0.9 的真实顺序（{@code onExtract} :55 / :57 / :59 / :62）</h2>
     * <ol>
     *     <li><b>光标上的容器</b>（:55 {@code containerOnCursor}）：有人把空桶 / 液体容器拿在手上
     *     ⇒ 直接灌进去（{@code extractWithContainerOnCursor}）；</li>
     *     <li><b>玩家背包里的容器</b>（:57 {@code bucketInInventory}）：消耗一个背包里的空桶
     *     （{@code bucketFromInventory = true}）；</li>
     *     <li><b>网络里的容器</b>（:59 {@code bucketInStorage}）：背包里没有桶，才从 RS 网络的物品
     *     存储里取一个空桶（{@code hasBucketInStorage} 先 SIMULATE，{@code extractSourceBucket(false)}
     *     再真取）；</li>
     *     <li><b>都没有</b>（:62）⇒ {@link #MSG_FLUID_NO_CONTAINER}，<b>一个 mB 都不扣</b>
     *     （RS 那里就是一句 {@code return true}，没有任何后续尝试）。</li>
     * </ol>
     *
     * <h2>守恒（任何一步失败都必须回滚）</h2>
     * <p>顺序固定为「先确认容器 → 再模拟装得下 → 再真扣流体 → 再消耗容器 → 再发满容器」。
     * 任何一步失败都把已经扣的流体<b>原样灌回缓存</b>，因此不存在「扣了没给」；
     * 满容器放不进背包就掉在脚下（{@link #giveToPlayer}），<b>绝不销毁</b>。</p>
     */
    private void takeFluid(final Player player, final FluidStack identity, final boolean all) {
        final FluidStack current = findCurrentFluid(identity);
        if (current.isEmpty()) {
            sendConfigSyncInternal(MSG_FLUID_GONE);
            return;
        }
        if (current.getAmount() < (int) BUCKET_MB) {
            sendConfigSyncInternal(MSG_FLUID_INSUFFICIENT);
            return;
        }
        // ① 光标上的容器（RS :55；有容器就直接灌，不消耗任何桶）
        if (fillCarriedContainer(player, identity)) {
            return;
        }
        // ② 玩家背包里的空桶（RS :57 —— 注意它排在“网络”之前：背包优先、网络兜底）
        final int inventoryBuckets = countInventoryBuckets(player);
        if (inventoryBuckets > 0) {
            takeFluidWithInventoryBucket(player, identity, all, inventoryBuckets);
            return;
        }
        // ③ 网络里的空桶（RS :59 —— 背包里没有桶时才动网络里的桶）
        final int networkBuckets = networkBucketCount();
        if (networkBuckets > 0) {
            takeFluidWithNetworkBucket(player, identity, all, networkBuckets);
            return;
        }
        // ④ 都没有（RS :62）：一个 mB 都不扣，只给一句提示（不静默、也不「扣了再说装不下」）
        sendConfigSyncInternal(MSG_FLUID_NO_CONTAINER);
    }

    /**
     * ① 光标上的容器：把缓存里的流体灌进 {@code getCarried()} 的液体容器。
     * <p>RS 的同一逻辑在 {@code extractWithContainerOnCursor}：它的目标是
     * {@code menu.getCarried()} 的 {@code Capabilities.FluidHandler.ITEM}，灌满后
     * {@code menu.setCarried(destination.getContainer())}。这里逐字同源。</p>
     * <p><b>为什么只处理数量为 1 的光标栈</b>：叠着的容器（如 16 个空桶）一次只能变成一个满桶，
     * 把整叠替换成 getContainer() 会凭空吞掉其余 15 个 —— 那是复制/销毁事故。数量 &gt; 1 时
     * 走 ②/③ 的「一次消耗一个」路径。</p>
     *
     * @return 是否真的发生了「灌进光标容器」这件事（false ⇒ 交给后面的容器来源）
     */
    private boolean fillCarriedContainer(final Player player, final FluidStack identity) {
        final ItemStack carried = getCarried();
        if (carried.isEmpty() || carried.getCount() != 1) {
            return false;
        }
        final IFluidHandlerItem handler = carried.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) {
            return false;
        }
        final int available = currentFluidAmount(identity);
        if (available < (int) BUCKET_MB) {
            return false;
        }
        // 先模拟：这个容器到底能装多少（装不进去 ⇒ 交给后面的来源，绝不先扣）
        final int accepted = handler.fill(identity.copyWithAmount(available),
            IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) {
            return false;
        }
        // 真扣缓存（按身份 drain 恰好 accepted mB），再把同量灌进容器
        final FluidStack drained = receiver.getFluidCache().drain(
            identity.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            return false;
        }
        final int inserted = handler.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        if (inserted != drained.getAmount()) {
            // 灌不进去（理论上不会：上面已模拟过）⇒ 把流体灌回缓存，一个 mB 都不少
            if (inserted > 0) {
                refillFluidCache(drained.copyWithAmount(inserted));
            }
            refillFluidCache(drained.copyWithAmount(drained.getAmount() - Math.max(0, inserted)));
            return false;
        }
        setCarried(handler.getContainer());
        receiver.setChanged();
        sendConfigSyncInternal(MSG_FLUID_TAKEN);
        return true;
    }

    /** ③ 用<b>网络</b>里的空桶装流体（RS {@code :59} 的 {@code bucketInStorage} 分支，背包桶的兜底）。 */
    private void takeFluidWithNetworkBucket(final Player player, final FluidStack identity,
                                            final boolean all, final int availableBuckets) {
        final StorageNetworkComponent storage = networkStorage();
        if (storage == null) {
            sendConfigSyncInternal(MSG_FLUID_NO_CONTAINER);
            return;
        }
        final int buckets = all
            ? (int) Math.min(availableBuckets, currentFluidAmount(identity) / BUCKET_MB)
            : 1;
        final ItemStack filled = simulateFilledBucket(identity);
        if (filled.isEmpty()) {
            sendConfigSyncInternal(MSG_FLUID_NO_SPACE);
            return;
        }
        int given = 0;
        for (int i = 0; i < buckets; i++) {
            // 先真扣一桶流体
            final FluidStack drained = drainOneBucket(identity);
            if (drained.getAmount() < (int) BUCKET_MB) {
                refillFluidCache(drained);
                break;
            }
            // 再从网络取一个空桶（SIMULATE 不通过 ⇒ 把流体灌回去，一个 mB 都不扣）
            final long extracted = storage.extract(BUCKET_ITEM_RESOURCE, 1L, Action.EXECUTE, Actor.EMPTY);
            if (extracted < 1L) {
                refillFluidCache(drained);
                break;
            }
            giveToPlayer(player, filled.copy());
            given++;
        }
        if (given <= 0) {
            sendConfigSyncInternal(MSG_FLUID_NO_CONTAINER);
            return;
        }
        receiver.setChanged();
        sendConfigSyncInternal(MSG_FLUID_TAKEN);
    }

    /** ② 用<b>玩家背包</b>里的空桶装流体（RS {@code :57} 的 {@code bucketInInventory} 分支）。 */
    private void takeFluidWithInventoryBucket(final Player player, final FluidStack identity,
                                              final boolean all, final int availableBuckets) {
        final int buckets = all
            ? (int) Math.min(availableBuckets, currentFluidAmount(identity) / BUCKET_MB)
            : 1;
        final ItemStack filled = simulateFilledBucket(identity);
        if (filled.isEmpty()) {
            sendConfigSyncInternal(MSG_FLUID_NO_SPACE);
            return;
        }
        int given = 0;
        for (int i = 0; i < buckets; i++) {
            final FluidStack drained = drainOneBucket(identity);
            if (drained.getAmount() < (int) BUCKET_MB) {
                refillFluidCache(drained);
                break;
            }
            if (!consumeOneEmptyBucket(player)) {
                refillFluidCache(drained); // 绝不「扣了没给」
                break;
            }
            giveToPlayer(player, filled.copy());
            given++;
        }
        if (given <= 0) {
            sendConfigSyncInternal(MSG_FLUID_NO_CONTAINER);
            return;
        }
        receiver.setChanged();
        sendConfigSyncInternal(MSG_FLUID_TAKEN);
    }

    // ---------- 流体取出的小工具（全部只在这一节使用） ----------

    /** 缓存里这一种流体此刻的真实存量（mB；没有 ⇒ 0）。 */
    private int currentFluidAmount(final FluidStack identity) {
        final FluidStack current = findCurrentFluid(identity);
        return current.isEmpty() ? 0 : current.getAmount();
    }

    /** 从缓存里真扣<b>恰好一桶</b>（不足一桶时返回实际扣到的量，调用方负责回灌）。 */
    private FluidStack drainOneBucket(final FluidStack identity) {
        return receiver.getFluidCache().drain(identity.copyWithAmount((int) BUCKET_MB),
            IFluidHandler.FluidAction.EXECUTE);
    }

    /**
     * 把流体原样灌回缓存（唯一的回滚点）。
     * <p>为什么这里不写「灌不回去就掉在脚下」：{@link RsccUnboundedFluidStorage} 是<b>种类无上限</b>的
     * 缓存，被回滚的量在几个指令之前刚刚从这里扣走（容量必然够），因此回灌不可能失败；
     * 真出现异常时宁可把量留在缓存里由随后的快照如实显示，也不做「掉一个 0 量物品」这种假动作。</p>
     */
    private void refillFluidCache(final FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        receiver.getFluidCache().fill(stack, IFluidHandler.FluidAction.EXECUTE);
    }

    /** 本接收器所接网络里的<b>物品存储</b>（拿不到网络 ⇒ null）。 */
    @Nullable
    private StorageNetworkComponent networkStorage() {
        if (receiver == null) {
            return null;
        }
        final Network network = receiver.getNode().getNetworkOrNull();
        return network == null ? null : network.getComponent(StorageNetworkComponent.class);
    }

    /** 网络物品存储里此刻能取出的空桶数量（RS 的 {@code hasBucketInStorage} 同口径：SIMULATE）。 */
    private int networkBucketCount() {
        final StorageNetworkComponent storage = networkStorage();
        if (storage == null) {
            return 0;
        }
        final long amount = storage.extract(BUCKET_ITEM_RESOURCE, 64L, Action.SIMULATE, Actor.EMPTY);
        return (int) Math.min(64L, Math.max(0L, amount));
    }

    /** 缓存里与 {@code identity} 同一身份（流体 + 数据组件）的那一罐（没有 ⇒ 空栈）。 */
    private FluidStack findCurrentFluid(final FluidStack identity) {
        if (receiver == null || identity == null || identity.isEmpty()) {
            return FluidStack.EMPTY;
        }
        for (final FluidStack stack : receiver.getFluidSnapshot()) {
            if (FluidStack.isSameFluidSameComponents(stack, identity)) {
                return stack;
            }
        }
        return FluidStack.EMPTY;
    }

    /** 背包（<b>不含光标</b>：光标有自己的分支）里空桶的总数。 */
    private int countInventoryBuckets(final Player player) {
        int total = 0;
        final Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (isEmptyBucket(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 该物品是不是空桶（原版 {@link Items#BUCKET}；用户口径就是「空桶换一桶」）。 */
    private static boolean isEmptyBucket(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.BUCKET);
    }

    /**
     * 消耗<b>一个</b>背包里的空桶。返回是否真的消耗掉了。
     * <p>光标上的桶不在这里消耗（走 {@link #fillCarriedContainer} 这条独立分支，
     * 而且那条分支不消耗桶 —— 它直接把流体灌进那个容器）。</p>
     */
    private boolean consumeOneEmptyBucket(final Player player) {
        final Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (!isEmptyBucket(stack)) {
                continue;
            }
            if (stack.getCount() <= 1) {
                inventory.setItem(slot, ItemStack.EMPTY);
            } else {
                stack.shrink(1);
            }
            return true;
        }
        return false;
    }

    /**
     * <b>只模拟</b>「一个空桶装满这种流体」的结果（不扣任何缓存）：
     * 成功 ⇒ 返回装满的容器（数量 1）；这种流体没有桶形态 / 桶装不下 ⇒ {@code EMPTY}。
     * <p>模拟走 {@code FluidUtil.tryFillContainer(..., doFill = false)}：它只询问
     * {@link IFluidHandler#fill} 的 SIMULATE 分支，因此 {@link SingleFluidEntry} 的
     * {@code drain} 一次都不会被调用（那条只在 doFill = true 时才发生）。</p>
     */
    private static ItemStack simulateFilledBucket(final FluidStack identity) {
        final ItemStack empty = new ItemStack(Items.BUCKET);
        final IFluidHandler source = new SingleFluidHandler(identity);
        final net.neoforged.neoforge.fluids.FluidActionResult simulated =
            FluidUtil.tryFillContainer(empty, source, (int) BUCKET_MB, null, false);
        if (!simulated.isSuccess() || simulated.getResult().isEmpty()
            || ItemStack.isSameItemSameComponents(empty, simulated.getResult())) {
            return ItemStack.EMPTY;
        }
        return simulated.getResult().copyWithCount(1);
    }

    /** 把结果容器交给玩家：先并入背包，放不下就掉在脚下（绝不销毁物品）。 */
    private static void giveToPlayer(final Player player, final ItemStack stack) {
        final ItemStack rest = stack.copy();
        player.getInventory().add(rest);
        if (!rest.isEmpty()) {
            player.drop(rest, false);
        }
    }

    /**
     * 只认「被点击的那一条」的模拟流体源：<b>不碰缓存</b>，只回答「一桶能不能装下」。
     * <p>为什么不能用真缓存当源：{@code FluidUtil.tryFillContainer} 会先问「能灌多少」，
     * 若直接交出整份多罐缓存，它会拿第一个罐应答，可能把<b>另一种</b>流体灌进玩家容器。
     * 本类只应答 {@code identity} 这一种（流体 + 数据组件都相同），并且在 SIMULATE 时
     * 按 {@link #amount} 假装有水 —— 因此这个模拟源<b>永远不动真缓存</b>。</p>
     */
    private static final class SingleFluidHandler implements IFluidHandler {
        private final FluidStack identity;

        SingleFluidHandler(final FluidStack identity) {
            this.identity = identity;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            // 报告「一桶」而不是 1 mB：FluidUtil.tryFillContainer(..., doFill=false) 是按
            // getFluidInTank 的量去模拟填装的，报 1 mB 会得到「装了 1 mB 的桶」这种假结果。
            return tank == 0 ? identity.copyWithAmount((int) BUCKET_MB) : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(final int tank) {
            return (int) BUCKET_MB;
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return false; // 只出不进
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            return 0; // 只出不进
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            if (resource == null || resource.isEmpty()
                || !FluidStack.isSameFluidSameComponents(resource, identity)) {
                return FluidStack.EMPTY;
            }
            return identity.copyWithAmount(resource.getAmount());
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return drain(identity.copyWithAmount(maxDrain), action);
        }
    }

    // ==================== C2S 动作（取物 / 取流体 / 滚动） ====================

    /**
     * C2S 动作的服务端入口（{@link ReceiverCacheActionPacket#handle} 只做转交）。
     *
     * <h2>三道校验（任一不过 ⇒ <b>什么都不写</b>）</h2>
     * <ol>
     *     <li><b>containerId 对应本菜单</b>：{@code player.containerMenu} 必须就是
     *     {@code packet.containerId()} 的那个菜单（防「改了包里的 id 去操作别人的机器」）；</li>
     *     <li><b>菜单类型</b>：必须是本类（同一个 id 被别的界面复用时不会被误当成接收器）；</li>
     *     <li><b>距离 / 存在性</b>：{@link #stillValid(Player)}（方块实体还在原坐标 + 8 格内）。</li>
     * </ol>
     * <p>与改名同一套判据 —— 「谁能开着这个界面」与「谁能取这个缓存」永远同一个答案。</p>
     */
    public static void handleCacheAction(final ServerPlayer player,
                                         final ReceiverCacheActionPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        final AbstractContainerMenu open = player.containerMenu;
        if (open == null || open.containerId != packet.containerId()) {
            return;
        }
        if (!(open instanceof NetworkResourceReceiverMenu menu)) {
            return;
        }
        if (!menu.stillValid(player)) {
            return;
        }
        menu.applyCacheAction(player, packet);
    }

    /** 分发一次 C2S 动作（调用方已完成三道校验）。 */
    private void applyCacheAction(final Player player, final ReceiverCacheActionPacket packet) {
        if (!isServerAuthoritative()) {
            return;
        }
        switch (packet.action()) {
            case ReceiverCacheActionPacket.ACTION_TAKE_ITEM -> takeItem(player, packet.item(),
                packet.all());
            case ReceiverCacheActionPacket.ACTION_TAKE_FLUID -> takeFluid(player, packet.fluid(),
                packet.all());
            case ReceiverCacheActionPacket.ACTION_PAGE -> applyScrollRequest(packet.page());
            default -> {
                // 未知动作：服务端不写任何东西（也绝不回一个「成功」）
            }
        }
    }

    // ==================== S2C 配置同步（只在变更 / 首次打开 / 滚动 / 取出后发） ====================

    /**
     * 把权威配置（名字 + 本网络已占用名字集合 + 本次操作提示 + 网格窗口 / 页首行）
     * 发给客户端。<b>改名路径专用</b>：占用集合可能刚变化，因此强制重算。
     */
    public void sendConfigSync(final String messageKey) {
        occupiedSnapshot = null;
        sendConfigSyncInternal(messageKey);
    }

    /** 只发快照（占用集合沿用缓存；滚动 / 取出 / 指纹变化时用这条，不扫网络图）。 */
    private void sendConfigSyncInternal(final String messageKey) {
        if (!isServerAuthoritative()) {
            return;
        }
        final ServerPlayer player = serverPlayerOrFind();
        if (player == null) {
            return;
        }
        if (occupiedSnapshot == null) {
            occupiedSnapshot = List.copyOf(receiver.occupiedReceiverNamesInNetwork());
        }
        // 全量聚合快照（物品 + 流体，按资源身份）⇒ 夹页首行 ⇒ 只下发当前一屏
        final List<ReceiverCacheEntry> all = receiver.cacheEntries();
        final int rows = rowCountFor(all.size());
        firstRow = Math.max(0, Math.min(maxFirstRowFor(all.size()), firstRow));
        final List<ReceiverCacheEntry> window = window(all, firstRow);
        final SyncReceiverConfigPacket packet = SyncReceiverConfigPacket.of(
            containerId, receiver, occupiedSnapshot, messageKey == null ? "" : messageKey,
            firstRow, rows, all.size(), window);
        lastSentSignature = signatureOf(all, firstRow, receiver.getItemTotalCount(),
            receiver.getFluidTotalAmount());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
    }

    /**
     * 当前一屏的格子（服务端只下发这一屏；空缓存 ⇒ 空列表）。
     * <p>起点 = 页首行 × 列数，长度 = {@link #WINDOW_SIZE}（不足则到末尾为止）。
     * 因此「第 N 行第 M 列」的格子下标就是 {@code N * GRID_COLS + M}，与界面绘制逐字同源。</p>
     */
    private static List<ReceiverCacheEntry> window(final List<ReceiverCacheEntry> all,
                                                   final int firstRow) {
        if (all.isEmpty()) {
            return List.of();
        }
        final int from = Math.max(0, firstRow) * GRID_COLS;
        if (from >= all.size()) {
            return List.of();
        }
        final int to = Math.min(all.size(), from + WINDOW_SIZE);
        return List.copyOf(all.subList(from, to));
    }

    /** 首次 {@code broadcastChanges} 时反查玩家并补发一次权威快照；之后只在指纹变化时补发。 */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!isServerAuthoritative()) {
            return;
        }
        if (!configSynced) {
            final ServerPlayer player = serverPlayerOrFind();
            if (player != null) {
                configSynced = true;
                sendConfigSync("");
            }
            return;
        }
        if (--cacheSyncCooldown > 0) {
            return;
        }
        cacheSyncCooldown = CACHE_SYNC_INTERVAL;
        // 每 10 tick 复查一次：只在「网格窗口 / 数量 / 页首行」真的变了才发包
        final List<ReceiverCacheEntry> all = receiver.cacheEntries();
        final long signature = signatureOf(all, firstRow, receiver.getItemTotalCount(),
            receiver.getFluidTotalAmount());
        if (signature != lastSentSignature) {
            sendConfigSyncInternal("");
        }
    }

    /**
     * 指纹（纯函数：同一份缓存 + 同一页首行 ⇒ 同一个值）。
     * <p>只用于「要不要补发快照」，<b>不参与任何业务判断</b>。资源身份与数量都要计入 ——
     * 否则「1 个泥土换成 1 个石头」（种类数 / 总件数都相同）不会触发补发，界面就会一直显示旧资源。</p>
     */
    private static long signatureOf(final List<ReceiverCacheEntry> all, final int firstRow,
                                    final long itemTotal, final int fluidTotal) {
        final List<Object> parts = new ArrayList<>(all.size() + 3);
        parts.add(firstRow);
        parts.add(itemTotal);
        parts.add(fluidTotal);
        for (final ReceiverCacheEntry entry : all) {
            parts.add(entry.fingerprint());
        }
        return Objects.hash(parts.toArray());
    }

    /** 缓存 / 反查当前打开本菜单的服务端玩家。 */
    @Nullable
    private ServerPlayer serverPlayerOrFind() {
        if (serverPlayer != null) {
            return serverPlayer;
        }
        if (receiver == null || receiver.getLevel() == null) {
            return null;
        }
        for (final Player player : receiver.getLevel().players()) {
            if (player.containerMenu == this && player instanceof ServerPlayer found) {
                serverPlayer = found;
                return found;
            }
        }
        return null;
    }

    /** 本菜单是否持有<b>服务端</b>的权威方块实体（客户端镜像菜单恒为 false）。 */
    private boolean isServerAuthoritative() {
        return receiver != null && receiver.getLevel() != null && !receiver.getLevel().isClientSide();
    }

    // ==================== 槽位移动 ====================

    /**
     * Shift + 左键：
     * <ul>
     *     <li>升级槽 → 玩家背包（原行为）；</li>
     *     <li>玩家背包 → <b>只进升级槽</b>（网格<b>没有</b>真实槽位，也不可能接受放入：
     *     缓存写入只属于请求器投递与相邻容器推入）。</li>
     * </ul>
     * <p>格子的「Shift = 全部取出」由界面按格子命中后发
     * {@link ReceiverCacheActionPacket}（不是槽位点击），见 {@link #takeItem} /
     * {@link #takeFluid}。</p>
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
        if (index < PLAYER_START) {
            // 升级槽 → 玩家背包
            if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_START + 36, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 升级槽（白名单由 UpgradeSlot 判定，非升级物品留在原处）
            if (!moveItemStackTo(stackInSlot, UPGRADE_START, PLAYER_START, false)) {
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

    @Override
    public boolean stillValid(final Player player) {
        if (receiver == null || receiver.getLevel() == null) {
            return true; // 客户端重建菜单：有效性由服务端校验
        }
        return receiver.getLevel().getBlockEntity(receiver.getBlockPos()) == receiver
            && player.distanceToSqr(receiver.getBlockPos().getX() + 0.5,
            receiver.getBlockPos().getY() + 0.5,
            receiver.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
