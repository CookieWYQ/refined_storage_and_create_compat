package cretae.cookiewyq.rs_create_compat.menu;

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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 网络资源接收器菜单：<b>6 个升级槽 + 可滚动资源列表（分页 12 条 / 页）+ 玩家背包</b>。
 *
 * <h2>第 96 轮为什么把固定 16 格换成「分页资源列表」</h2>
 * <p>用户原话：「<i>内容是可以直接打开取出了，但是你那个流体该怎么办呢？而且你这个格子这么少……
 * 资源是没有上限的，你这么一点怎么装得完</i>」。缓存是
 * {@link RsccUnboundedItemStorage} / {@link RsccUnboundedFluidStorage}（<b>种类无上限</b>），
 * 而旧界面只有 16 个镜像物品格 + 4 条流体条目 ⇒ <b>第 17 种物品 / 第 5 种流体在界面里根本看不见
 * 也取不出</b>。现在：
 * <ul>
 *     <li><b>覆盖全部种类</b>：列表由服务端按「资源身份」聚合后<b>分页</b>下发
 *     （{@link SyncReceiverConfigPacket#entries()}，每页 {@link #PAGE_SIZE} 行，
 *     带页码 {@link #PAGE_SIZE} 与总种类数）；客户端<b>不遍历缓存</b>、也不自己算分页；</li>
 *     <li><b>物品与流体混排</b>：同一张列表里既有物品行也有流体行（用户「流体该怎么办」就是
 *     要它们能被看见并取出），每行 = 图标 + 名称 + 数量；</li>
 *     <li><b>取出走 C2S 包</b>：{@link ReceiverCacheActionPacket}（资源身份 + 「要不要全部」），
 *     服务端校验 containerId / 距离 / 服务端侧后从真实缓存扣减。原版槽位协议表达不了
 *     「按身份跨多格取」与「流体换桶」，因此这一轮<b>不再</b>用镜像槽位；</li>
 *     <li><b>翻页也只是服务端菜单状态</b>（页码），不写缓存、不需要额外权限。</li>
 * </ul>
 *
 * <h2>手势（用户口径：左键 = 主操作，Shift = 全部）</h2>
 * <ul>
 *     <li><b>物品行</b>：左键 = 取一组（该资源的最大堆叠）；Shift + 左键 = 全部（直到背包满，
 *     放不下的<b>回填缓存</b>）；</li>
 *     <li><b>流体行</b>：左键 = <b>1000 mB 换 1 桶</b>（背包里必须有空桶，没有就<b>一点不扣</b>
 *     并提示）；Shift + 左键 = 尽可能多的整桶，剩余继续留在缓存；</li>
 *     <li><b>滚轮 / 滚动条 / 页码箭头</b> = 翻页。</li>
 * </ul>
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
 * <p>「每 tick 几批 / 每批多少」由方块实体经 {@link ContainerData}（见 {@link #DATA_COUNT}）
 * 每 tick 广播一次，界面只读 {@link #getThrottleBatchesPerTick()} / {@link #getThrottleBatch()}。</p>
 */
public class NetworkResourceReceiverMenu extends AbstractContainerMenu {
    /** 升级槽数量 = 方块实体的槽数（唯一来源，避免两处各写一个 6）。 */
    public static final int UPGRADE_COUNT = cretae.cookiewyq.rs_create_compat.support
        .RsccSpeedStackUpgradeContainer.SLOTS;
    /** 升级槽起始下标。 */
    public static final int UPGRADE_START = 0;
    /** 玩家背包 / 快捷栏起始下标（升级槽之后就是它：资源列表<b>没有</b>真实槽位）。 */
    public static final int PLAYER_START = UPGRADE_START + UPGRADE_COUNT;

    // ==================== 资源列表（可滚动 / 分页） ====================

    /**
     * 每页的<b>列数</b>。
     * <p>为什么是 2 列：面板宽度只有 236，扣掉右侧升级槽栏（x≥213）与滚动条（12px）后，
     * 列表可用宽度 = 188px。3 列会把单列压到约 58px —— 图标 12 + 名称 + 数量之后名称只剩
     * 1~2 个字（用户第 96 轮抱怨的就是「看不清 / 装不下」），因此宽度上最多 2 列。
     * 想要更多条目只能从<b>行高</b>里挤（见 {@link #ROW_H}）。</p>
     */
    public static final int PAGE_COLS = 2;
    /**
     * 每页的<b>行数</b>（视觉行）。
     * <p><b>第 96 轮复核 P5 修正：4 → 6</b>。原口径每页 8 条（2×4、行高 18），在
     * 「缓存 1728 件 / 种类无上限」下最坏要翻 200+ 页，用户原话「资源是没有上限的，你这么一点
     * 怎么装得完」只算勉强达标。现在行高 12px（图标 12px + 9px 文字刚好放得下）+ 6 行
     * ⇒ <b>每页 12 条</b>（{@link #PAGE_SIZE}），翻页次数直接砍到原来的 2/3。</p>
     * <p><b>为什么不继续加行</b>：列表纵向可用空间 = 88…160（72px，上面是状态 / 节拍 / 摘要 /
     * 页眉，下面是玩家背包标签），12px 行高已是最小可读档（9px 字 + 12px 图标）；
     * 再加行只能压字或压图标，那是拿「看得清」换「看得多」，不划算。</p>
     */
    public static final int PAGE_ROWS = 6;
    /** 每页条目数 = 列 × 行 = <b>12</b>（服务端每页最多下发这么多行）。 */
    public static final int PAGE_SIZE = PAGE_COLS * PAGE_ROWS;

    /** 列表首行左上角（Menu 坐标）。 */
    public static final int LIST_X0 = 9;
    /**
     * 列表首行 y（88）：上面 79~87 是页眉（页码箭头），下面 176 起是玩家背包。
     * <p>整段条目区 = 88 … 88 + {@link #PAGE_ROWS} × {@link #ROW_H} = 88…160，
     * 与背包标签（166）留 6px；与命名框（24…40）/「取全部」（56…72）/ 升级槽（x≥213）/
     * 滚动条（x≥199）都不相交 —— 这几条由自检的几何断言守着
     * （{@code tools/selfcheck_round96_receiver.py} 的 B6）。</p>
     */
    public static final int LIST_Y0 = 88;
    /**
     * 行高（px）。
     * <p><b>12px 是「12px 图标 + 9px 文字」的最小可读档</b>（原版字体行高 9）：图标按
     * {@link #ICON_SCALE}（0.75）缩到 12px，文字基线放 y+2，上下各留 1px。
     * 改这里的任何值都必须同步 {@link #SCROLLBAR_H} 与自检 B6 的几何断言。</p>
     */
    public static final int ROW_H = 12;
    /** 行内图标边长（px）：物品 / 流体图标都按这个尺寸画（16 × {@link #ICON_SCALE}）。 */
    public static final int ICON_SIZE = 12;
    /** 图标缩放系数 = 12/16 = 0.75（绘制时用 pose 缩放，不新增贴图）。 */
    public static final float ICON_SCALE = ICON_SIZE / 16.0F;
    /** 名称相对行左沿的偏移（图标 12 + 3px 间隔）。 */
    public static final int NAME_X = ICON_SIZE + 3;
    /** 名称 / 数量的文字纵向偏移（12px 行里放 9px 字：上 2 下 1）。 */
    public static final int TEXT_DY = 2;
    /** 单列宽度（图标 12 + 名称 + 右对齐数量）。 */
    public static final int COL_W = 91;
    /** 两列之间的间隔。 */
    public static final int COL_GAP = 6;
    /** 滚动条（RS 原版 {@code ScrollbarWidget}，宽 12）左上角：列表右侧、升级槽框（x=213）之前。 */
    public static final int SCROLLBAR_X = 199;
    public static final int SCROLLBAR_Y = LIST_Y0;
    /** 滚动条高度 = 列表总高。 */
    public static final int SCROLLBAR_H = PAGE_ROWS * ROW_H;
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
     * <p>0 = <b>每 tick 批数</b>（速度升级旋钮），1 = 单批搬运量（件，叠加升级旋钮）。</p>
     */
    public static final int DATA_COUNT = 2;

    /**
     * 接收器名字的硬上限（字符数，按 {@code String#length()} 计）。
     * <p>为什么要有上界：名字会被写进 NBT、随配置同步包下发、并作为请求器的目标地址显示在界面上。</p>
     */
    public static final int MAX_NAME_LENGTH = 32;

    // ==================== 菜单按钮 id（原版 ServerboundContainerButtonClickPacket） ====================

    /** 按钮：把缓存里的物品<b>全部</b>塞进玩家背包（背包满时剩余留在缓存）。 */
    public static final int BUTTON_TAKE_ALL_ITEMS = 0;
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

    /** 取缓存的结果提示（同样是「服务端只发 key」，界面翻译后显示在状态行）。 */
    public static final String MSG_CACHE_TAKEN_ALL = "gui.rs_create_compat.network_resource_receiver.msg.cache_taken_all";
    public static final String MSG_CACHE_BAG_FULL = "gui.rs_create_compat.network_resource_receiver.msg.cache_bag_full";
    public static final String MSG_CACHE_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.cache_empty";
    public static final String MSG_FLUID_TAKEN = "gui.rs_create_compat.network_resource_receiver.msg.fluid_taken";
    public static final String MSG_FLUID_EMPTY = "gui.rs_create_compat.network_resource_receiver.msg.fluid_empty";
    public static final String MSG_FLUID_INSUFFICIENT =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_insufficient";
    /** 「背包里没有空桶」——用户口径：<b>没有空桶就不扣，并明确提示需要空桶</b>。 */
    public static final String MSG_FLUID_NO_CONTAINER =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_container";
    public static final String MSG_FLUID_NO_SPACE =
        "gui.rs_create_compat.network_resource_receiver.msg.fluid_no_space";
    public static final String MSG_FLUID_GONE = "gui.rs_create_compat.network_resource_receiver.msg.fluid_gone";

    /** 缓存摘要 / 资源列表的重发间隔（tick）：只在指纹变化时真正发包，绝不每 tick 拼数据。 */
    private static final int CACHE_SYNC_INTERVAL = 10;

    @Nullable
    private final NetworkResourceReceiverBlockEntity receiver;

    /**
     * 服务端权威的节流 / 升级数据（见 {@link #DATA_COUNT}）。
     * <p>客户端重建菜单时 {@code receiver == null}，任何「按槽位自己重算」都会与方块实体分叉。</p>
     */
    private final ContainerData data;

    /**
     * 服务端当前的页码（0 基）。<b>客户端不持有权威页码</b>：界面显示的是 S2C 快照里的
     * {@code page}，翻页请求经 {@link ReceiverCacheActionPacket} 回到服务端后再由快照回发。
     */
    private int page;

    /** 上一次同步给这个界面的「缓存 + 页码」指纹（只用于判断要不要补发快照，不参与业务）。 */
    private long lastSentSignature = Long.MIN_VALUE;
    /** 缓存摘要复查倒计时。 */
    private int cacheSyncCooldown = CACHE_SYNC_INTERVAL;

    /** 当前打开本菜单的服务端玩家（首次由 {@link #broadcastChanges()} 反查后缓存）。 */
    @Nullable
    private ServerPlayer serverPlayer;
    /** 首次快照是否已发送（保证「打开界面时一定有权威值」，且之后只在变更时发）。 */
    private boolean configSynced;
    /**
     * 本网络已占用名字的缓存（只在改名时重算）。
     * <p>缓存摘要的补发不应该每 10 tick 枚举一次整张网络图；占用集合只是客户端的即时提示，
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
     * <p>旧实现这里是「节拍间隔」，但间隔的物理下限就是 1 tick，速度升级永远无法让它变小 ——
     * 那个字段只会让界面永远显示「每 1 tick 一批」。现在服务端同步的是真正参与搬运的批数。</p>
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

    // ==================== 名字（逐字沿用既有语义，本轮未改） ====================

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

    // ==================== 资源列表：分页 ====================

    /** 总页数（空缓存也算 1 页：界面要显示「第 1/1 页」而不是「第 1/0 页」）。 */
    public static int pageCountFor(final int totalTypes) {
        return Math.max(1, (Math.max(0, totalTypes) + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 当前服务端页码（0 基；客户端读它没有意义，界面只读 S2C 快照）。 */
    public int getPage() {
        return page;
    }

    /**
     * 服务端：处理一次翻页请求（滚轮 / 滚动条 / 页码箭头都走这里）。
     * <p>夹到 {@code [0, pageCount - 1]}；<b>边界也回一次快照</b>，让客户端与服务端页码严格一致
     * （否则「滚到最底再滚一下」会让客户端本地页码与服务端分叉）。</p>
     */
    public void applyPageRequest(final int requested) {
        if (!isServerAuthoritative()) {
            return;
        }
        final int pages = pageCountFor(receiver.cacheEntries().size());
        final int clamped = Math.max(0, Math.min(pages - 1, requested));
        page = clamped;
        sendConfigSyncInternal("");
    }

    // ==================== 取出：物品（真实扣减，绝不复制） ====================

    /**
     * 服务端：取出一行<b>物品</b>（{@code all = false} ⇒ 一组；{@code true} ⇒ 直到背包满）。
     *
     * <h2>守恒顺序（最重要的判据）</h2>
     * <ol>
     *     <li>按<b>资源身份</b>（{@link ItemStack#isSameItemSameComponents}，与列表聚合口径同源）
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
        //    正常取走一两种时不刷提示（行数与数字立刻变化，本身就是反馈）
        if (given <= 0) {
            sendConfigSyncInternal(MSG_CACHE_BAG_FULL);
        } else if (receiver.getItemTypeCount() <= 0) {
            sendConfigSyncInternal(MSG_CACHE_TAKEN_ALL);
        } else {
            sendConfigSyncInternal(rest.isEmpty() ? "" : MSG_CACHE_BAG_FULL);
        }
    }

    /**
     * 服务端：菜单按钮「全部取出」= 把缓存里的物品逐种塞进背包。
     * <p>背包先满 ⇒ 立刻停止，<b>剩余原样留在缓存里</b>（不扣、不销毁）并提示。</p>
     * <p>与资源列表的 Shift + 左键<b>不同</b>：那个只取「被点的这一种」，这个把<b>所有种类</b>都清空
     * （用户仍然需要「一键把接收器腾空」的入口）。</p>
     */
    private void takeAllCacheItems(final Player player) {
        if (!isServerAuthoritative()) {
            return;
        }
        final RsccUnboundedItemStorage cache = receiver.getItemCache();
        int moved = 0;
        boolean bagFull = false;
        // 有界循环：每次拿走整个「第一种非空种类」；放不下就停（不会死循环）
        for (int guard = 0; guard < cache.getSlots() + 4; guard++) {
            final int slot = firstNonEmptySlot(cache);
            if (slot < 0) {
                break;
            }
            final ItemStack stored = cache.getStackInSlot(slot);
            final ItemStack taken = cache.extractItem(slot, stored.getCount(), false);
            if (taken.isEmpty()) {
                break;
            }
            final ItemStack rest = taken.copy();
            player.getInventory().add(rest);
            moved += taken.getCount() - rest.getCount();
            if (!rest.isEmpty()) {
                final ItemStack refused = cache.insertItem(0, rest, false);
                if (!refused.isEmpty()) {
                    player.drop(refused, false); // 极端兜底：绝不销毁
                }
                bagFull = true;
                break;
            }
        }
        receiver.setChanged();
        if (bagFull) {
            sendConfigSyncInternal(MSG_CACHE_BAG_FULL);
        } else if (moved <= 0) {
            sendConfigSyncInternal(MSG_CACHE_EMPTY);
        } else {
            sendConfigSyncInternal(MSG_CACHE_TAKEN_ALL);
        }
    }

    /** 缓存里第一个非空槽位（没有 ⇒ -1）。 */
    private static int firstNonEmptySlot(final RsccUnboundedItemStorage cache) {
        for (int slot = 0; slot < cache.getSlots(); slot++) {
            if (!cache.getStackInSlot(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    // ==================== 取出：流体（1000 mB / 桶，背包里必须有空桶） ====================

    /**
     * 服务端：把一行<b>流体</b>换成整桶（{@code 1000 mB/桶}）。
     *
     * <h2>用户口径（第 96 轮原话：「你那个流体该怎么办呢」）</h2>
     * <ul>
     *     <li><b>左键 = 1000 mB 换 1 桶</b>；<b>Shift = 尽可能多的整桶</b>，剩余继续留在缓存；</li>
     *     <li><b>背包里必须有空桶</b>（原版 {@link Items#BUCKET}）：没有就<b>一点都不扣</b>，
     *     并明确提示「需要空桶」；</li>
     *     <li>光标上的空桶优先（与 RS 的「光标优先」一致，避免误耗背包里的桶）。</li>
     * </ul>
     *
     * <h2>守恒顺序</h2>
     * <ol>
     *     <li><b>先模拟</b>：用空桶副本 {@code FluidUtil.tryFillContainer(..., doFill=false)} 确认
     *     「这种流体真的有桶形态」——模拟不扣任何缓存；</li>
     *     <li>再<b>真正 drain</b> 恰好整桶的数量，然后才消耗空桶、发放满桶；</li>
     *     <li>若消耗空桶失败（理论上不会：数量已先数过），把刚 drain 出来的流体<b>原样灌回缓存</b>
     *     ——绝不存在「扣了没给」；</li>
     *     <li>满桶放不进背包就掉在脚下（{@link #giveToPlayer}），<b>绝不销毁</b>。</li>
     * </ol>
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
        final int emptyBuckets = countEmptyBuckets(player);
        if (emptyBuckets <= 0) {
            // 用户硬要求：没有空桶 ⇒ 一点不扣 + 明确提示（不能「扣了再说装不下」）
            sendConfigSyncInternal(MSG_FLUID_NO_CONTAINER);
            return;
        }
        final IFluidHandler source = new SingleFluidEntry(receiver.getFluidCache(), identity);
        // ① 先模拟：确认这种流体真的能装进桶（某些模组流体没有桶形态）
        final ItemStack filledTemplate = simulateFilledBucket(source);
        if (filledTemplate.isEmpty()) {
            sendConfigSyncInternal(MSG_FLUID_NO_SPACE);
            return;
        }
        final int maxBuckets = (int) Math.min(emptyBuckets, current.getAmount() / BUCKET_MB);
        final int buckets = all ? maxBuckets : 1;
        int given = 0;
        for (int i = 0; i < buckets; i++) {
            // ② 真正扣减（按身份 drain 恰好 1 桶）
            final FluidStack drained = receiver.getFluidCache().drain(
                identity.copyWithAmount((int) BUCKET_MB), IFluidHandler.FluidAction.EXECUTE);
            if (drained.getAmount() < (int) BUCKET_MB) {
                // 缓存里剩下的不足一桶：把已经取出的放回去，结束
                if (!drained.isEmpty()) {
                    receiver.getFluidCache().fill(drained, IFluidHandler.FluidAction.EXECUTE);
                }
                break;
            }
            // ③ 消耗一个空桶（失败就把流体灌回去 —— 绝不「扣了没给」）
            if (!consumeOneEmptyBucket(player)) {
                receiver.getFluidCache().fill(drained, IFluidHandler.FluidAction.EXECUTE);
                break;
            }
            // ④ 发放满桶（放不下就掉在脚下）
            giveToPlayer(player, filledTemplate.copy());
            given++;
        }
        if (given <= 0) {
            sendConfigSyncInternal(MSG_FLUID_NO_SPACE);
            return;
        }
        receiver.setChanged();
        sendConfigSyncInternal(MSG_FLUID_TAKEN);
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

    /** 背包（含光标）里空桶的总数。 */
    private int countEmptyBuckets(final Player player) {
        int total = 0;
        final ItemStack carried = getCarried();
        if (isEmptyBucket(carried)) {
            total += carried.getCount();
        }
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
     * 消耗<b>一个</b>空桶（光标优先，其次背包）。返回是否真的消耗掉了。
     * <p>光标上有一叠空桶时只减 1（其余仍留在光标上），避免「为了一桶把整叠都吃掉」。</p>
     */
    private boolean consumeOneEmptyBucket(final Player player) {
        final ItemStack carried = getCarried();
        if (isEmptyBucket(carried)) {
            if (carried.getCount() <= 1) {
                setCarried(ItemStack.EMPTY);
            } else {
                carried.shrink(1);
                setCarried(carried);
            }
            return true;
        }
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
    private static ItemStack simulateFilledBucket(final IFluidHandler source) {
        final ItemStack empty = new ItemStack(Items.BUCKET);
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
     * 只认「被点击的那一条」的单罐流体源：把整份多罐缓存包成 1 个罐。
     * <p>为什么必须这样包：{@code FluidUtil.tryFillContainer} 会先问「能灌多少」再真灌；
     * 若直接交出整份多罐缓存，它会拿第一个罐应答，可能把<b>另一种</b>流体灌进玩家容器。
     * 本类只应答 {@code identity} 这一种（流体 + 数据组件都相同），其余一律 0 / 空。</p>
     */
    private static final class SingleFluidEntry implements IFluidHandler {
        private final RsccUnboundedFluidStorage cache;
        private final FluidStack identity;

        SingleFluidEntry(final RsccUnboundedFluidStorage cache, final FluidStack identity) {
            this.cache = cache;
            this.identity = identity;
        }

        /** 此刻缓存里这一种流体的真实存量（没有 ⇒ 空栈）。 */
        private FluidStack current() {
            for (final FluidStack stack : cache.getTanksSnapshot()) {
                if (FluidStack.isSameFluidSameComponents(stack, identity)) {
                    return stack;
                }
            }
            return FluidStack.EMPTY;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            return tank == 0 ? current() : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(final int tank) {
            return cache.getCapacity();
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
            final FluidStack stored = current();
            if (stored.isEmpty()) {
                return FluidStack.EMPTY;
            }
            return cache.drain(resource.copyWithAmount(Math.min(resource.getAmount(), stored.getAmount())),
                action);
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return drain(identity.copyWithAmount(maxDrain), action);
        }
    }

    // ==================== C2S 动作（取物 / 取流体 / 翻页） ====================

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
     * <p>与改名、清空订单同一套判据 —— 「谁能开着这个界面」与「谁能取这个缓存」永远同一个答案。</p>
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
            case ReceiverCacheActionPacket.ACTION_PAGE -> applyPageRequest(packet.page());
            default -> {
                // 未知动作：服务端不写任何东西（也绝不回一个「成功」）
            }
        }
    }

    // ==================== 原版菜单按钮协议（「取全部」） ====================

    /**
     * 原版菜单按钮（{@code ServerboundContainerButtonClickPacket}）的服务端入口。
     * <p>原版在调用本方法<b>之前</b>已经校验了 containerId、旁观者与 {@link #stillValid}，
     * 因此这里只需要再确认「确实在服务端、方块实体还在」并按 id 分发。</p>
     *
     * @return 是否处理了这个按钮（未知 id ⇒ false，原版据此当作无效按钮）
     */
    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (!isServerAuthoritative()) {
            return false;
        }
        if (id == BUTTON_TAKE_ALL_ITEMS) {
            takeAllCacheItems(player);
            return true;
        }
        return false;
    }

    // ==================== S2C 配置同步（只在变更 / 首次打开 / 翻页 / 取出后发） ====================

    /**
     * 把权威配置（名字 + 本网络已占用名字集合 + 本次操作提示 + 缓存摘要 / 当前页资源列表 / 输出面）
     * 发给客户端。<b>改名路径专用</b>：占用集合可能刚变化，因此强制重算。
     */
    public void sendConfigSync(final String messageKey) {
        occupiedSnapshot = null;
        sendConfigSyncInternal(messageKey);
    }

    /** 只发快照（占用集合沿用缓存；翻页 / 取出 / 摘要变化时用这条，不扫网络图）。 */
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
        // 全量聚合快照（物品 + 流体，按资源身份）⇒ 夹页码 ⇒ 只下发当前一页
        final List<ReceiverCacheEntry> all = receiver.cacheEntries();
        final int pages = pageCountFor(all.size());
        page = Math.max(0, Math.min(pages - 1, page));
        final List<ReceiverCacheEntry> pageEntries = slice(all, page);
        final SyncReceiverConfigPacket packet = SyncReceiverConfigPacket.of(
            containerId, receiver, occupiedSnapshot, messageKey == null ? "" : messageKey,
            page, pages, all.size(), pageEntries);
        lastSentSignature = signatureOf(all, page, receiver.getItemTotalCount(),
            receiver.getFluidTotalAmount());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
    }

    /** 当前页的行（服务端只下发这一页；空缓存 ⇒ 空列表）。 */
    private static List<ReceiverCacheEntry> slice(final List<ReceiverCacheEntry> all, final int page) {
        if (all.isEmpty()) {
            return List.of();
        }
        final int from = Math.max(0, page) * PAGE_SIZE;
        if (from >= all.size()) {
            return List.of();
        }
        final int to = Math.min(all.size(), from + PAGE_SIZE);
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
        // 每 10 tick 复查一次：只在「资源列表 / 数量 / 页码」真的变了才发包
        final List<ReceiverCacheEntry> all = receiver.cacheEntries();
        final long signature = signatureOf(all, page, receiver.getItemTotalCount(),
            receiver.getFluidTotalAmount());
        if (signature != lastSentSignature) {
            sendConfigSyncInternal("");
        }
    }

    /**
     * 指纹（纯函数：同一份缓存 + 同一页码 ⇒ 同一个值）。
     * <p>只用于「要不要补发快照」，<b>不参与任何业务判断</b>。资源身份与数量都要计入 ——
     * 否则「1 个泥土换成 1 个石头」（种类数 / 总件数都相同）不会触发补发，界面就会一直显示旧资源。</p>
     */
    private static long signatureOf(final List<ReceiverCacheEntry> all, final int page,
                                    final long itemTotal, final int fluidTotal) {
        final List<Object> parts = new ArrayList<>(all.size() + 3);
        parts.add(page);
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
     *     <li>玩家背包 → <b>只进升级槽</b>（资源列表<b>没有</b>真实槽位，也不可能接受放入：
     *     缓存写入只属于请求器投递与相邻容器推入）。</li>
     * </ul>
     * <p>资源行的「Shift = 全部取出」由界面按行命中后发
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
