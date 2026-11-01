package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccResourceKeys;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 网络资源请求器菜单：<b>6 个升级槽</b>（只收速度 + 叠加，与接收器同一份白名单与容器实现）+ 玩家背包。
 *
 * <h2>资源的选择 / 数量 / 发送在 RS 网格里完成；本界面负责「看得见 + 能触发」</h2>
 * <p>玩家点「网格」后进入 {@code client/grid/ResourcePickerScreen}（RS 自己的网格），
 * 在里面<b>连续</b>选资源、改数量、直接发送 —— 这正是用户要求的「选择物品、发送物品全部在网格界面内完成」。
 * 因此本菜单<b>没有</b>「添加 / 删除 / 改数量」这类控件（那是网格的活）。</p>
 * <p>但「没有编辑控件」不等于「什么都不显示」：第 95 轮用户抱怨的正是
 * 「资源发送的界面并没有发生多大的改变」。所以界面正文现在会<b>只读显示</b>：
 * 目标接收器（名字 + 坐标）、订单内容（图标 × 数量）、最近一轮的已投 / 剩余 / 缺口、
 * 每 tick 批数 / 单批量、以及一条状态行；并提供一个「清空订单」按钮
 * （C2S {@code RequesterOrderPacket#ACTION_CLEAR}，服务端校验后才写）。
 * 这些数字全部由服务端下发（见 {@code SyncRequesterConfigPacket}），客户端不自己算。</p>
 *
 * <h2>为什么请求器也要 6 个升级槽（与接收器对称）</h2>
 * <p>速度升级 = 让一轮投递来得更快、叠加升级 = 让一次投递送得更多；两台机器的槽位、白名单、
 * 容器实现、上限全部同源（{@code RsccSpeedStackUpgradeContainer}）。界面上「插件」这一栏
 * 与接收器同一位置、同一标题，玩家不必猜「为什么只有接收器能装」。</p>
 *
 * <h2>升级数值一律来自同步通道</h2>
 * <p>与接收器同一写法：本机升级数 / 每 tick 批数 / 单批量由方块实体经 {@link ContainerData} 广播，
 * 界面只读；客户端重建菜单时 {@code requester == null}，没有方块实体、也没有容器内容，
 * 任何自算都会分叉。</p>
 *
 * <h2>服务端权威</h2>
 * <p>目标接收器（C2S {@code SetRequesterTargetPacket}：客户端从 {@link #receiverViews()} 下发的
 * 候选里挑一台，只把<b>名字</b>提交回来；服务端校验「本网络内存在且唯一」）、
 * 已选资源的增 / 删 / 改数量（C2S {@code RequesterOrderPacket}，逐字段校验；
 * <b>没有条数上限</b>）、以及「立即发送」（C2S {@code TriggerRequesterPacket} →
 * 方块实体既有的 {@code triggerRequest()}）。
 * 客户端界面上所有可写配置都只是只读镜像 + 请求包，没有任何本地写入路径。</p>
 *
 * <h2>目标不再「手打名字」</h2>
 * <p>{@link #receiverViews()} 把本网络内所有已命名接收器（名字 + 坐标）随配置快照下发一次，
 * 界面把它们做成「◀ 名字 ▶」+「选定」：玩家不再需要知道 / 记住名字。
 * <b>存储层与校验一个字都没改</b> —— 存进去的仍是 {@code targetName}，
 * 仍由 {@code resolveReceiversByName} 现解析（找不到 / 重名都拒绝，绝不随机挑一台）。</p>
 *
 * <h2>红石语义</h2>
 * <p>红石上升沿走方块实体（{@link NetworkResourceRequesterBlockEntity#onRedstonePowerChanged}），
 * <b>不经过菜单</b>；界面 / 网格上的发送按钮与它是「两条触发路径、同一个触发方法」
 * （{@code triggerRequest()}）。</p>
 */
public class NetworkResourceRequesterMenu extends AbstractContainerMenu {
    /**
     * 菜单标题语言键（方块名，中英都已存在）。
     * <p>菜单里<b>只有</b>这一处 {@code Component.translatable}：服务端从不拼自然语言，
     * 其余提示一律是 {@code MSG_*} 语言键常量，随 S2C 快照下发由客户端翻译。</p>
     */
    public static final String MENU_TITLE_KEY = "block.rs_create_compat.network_resource_requester";
    /** 升级槽数量 = 方块实体的槽数（唯一来源，避免两处各写一个 6）。 */
    public static final int UPGRADE_COUNT = cretae.cookiewyq.rs_create_compat.support
        .RsccSpeedStackUpgradeContainer.SLOTS;
    /** 升级槽起始下标。 */
    public static final int UPGRADE_START = 0;
    /** 玩家背包 / 快捷栏起始下标。 */
    public static final int PLAYER_START = UPGRADE_START + UPGRADE_COUNT;

    /** 升级槽白名单：只有这两类（与接收器<b>同一份</b>常量，绝不各写一份）。 */
    public static final List<String> ALLOWED_UPGRADES =
        cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu.ALLOWED_UPGRADES;

    /**
     * 同步数据槽位数（与方块实体 {@code getContainerData()} 的 {@code getCount()} 必须相等）。
     * <p>0 = 每 tick 批数，1 = 单批投递量；升级个数不需要同步（升级槽物品本身就是权威显示）。</p>
     */
    public static final int DATA_COUNT = 2;

    /**
     * 面板尺寸（与界面类同名常量同源；**与接收器同尺寸**，便于两台机器并排时观感一致）。
     * <p>210×238（第 95 轮从 210×180 加高）：上半区放「红石提示 / 状态 / 目标选择器 / 三个动作按钮 /
     * 节拍 / 订单图标×数量 / 已投·剩余·缺口」，右侧 6 个插件槽，玩家背包下移到 {@link #INV_Y0}。
     * 加高是必须的 —— 用户抱怨的正是「界面没有发生多大的改变」，订单内容没有可画的地方。
     * 体积仍刻意压在「1280×720 + 自动 GUI 缩放 3×」以内（238 &lt; 240 = 720/3）。</p>
     */
    public static final int PANEL_W = 210;
    public static final int PANEL_H = 238;

    /** 插件槽列（与接收器同一列位置；上面留一行画「插件」标题）。 */
    private static final int UPGRADE_X = 188;
    private static final int UPGRADE_Y0 = 16;

    /** 玩家背包 / 快捷栏（加高面板后整体下移，背包标签在界面类里按同一间距上推 10px）。 */
    private static final int INV_X0 = 9;
    private static final int INV_Y0 = 164;
    private static final int HOTBAR_Y = 218;

    @Nullable
    private final NetworkResourceRequesterBlockEntity requester;

    /** 服务端权威的节流 / 升级数据（见 {@link #DATA_COUNT}）。 */
    private final ContainerData data;

    /** 客户端重建菜单用（无方块实体）。 */
    public NetworkResourceRequesterMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public NetworkResourceRequesterMenu(final int id,
                                        final Inventory inventory,
                                        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        super(RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_MENU.get(), id);
        this.requester = requester;
        this.data = requester != null ? requester.getContainerData() : new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
        // 升级槽：6 格竖排，白名单只有速度 + 叠加（第 ① 层准入；第 ② 层在容器里）
        final cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer upgrades =
            requester != null
                ? requester.getUpgradeContainer()
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

    public static NetworkResourceRequesterMenu create(final int id,
                                                      final Inventory inventory,
                                                      final NetworkResourceRequesterBlockEntity requester) {
        return new NetworkResourceRequesterMenu(id, inventory, requester);
    }

    /** 纯 getter：暴露本菜单持有的方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public NetworkResourceRequesterBlockEntity getRequester() {
        return requester;
    }

    // ==================== 节流数值（界面只读这里，绝不自己算） ====================

    /**
     * 每 tick 批数（速度升级旋钮）：1 / 2 / 4 / … / 64。
     * <p>旧实现这里是「节拍间隔」，但间隔的物理下限就是 1 tick，速度升级永远无法让它变小 ——
     * 那个字段只会让界面永远显示「每 1 tick 一批」。现在同步的是真正参与投递的批数。</p>
     */
    public int getThrottleBatchesPerTick() {
        return data.get(0);
    }

    /** 单批投递量（件）。 */
    public long getThrottleBatch() {
        return data.get(1);
    }

    /**
     * 是否已配置目标（客户端重建菜单时恒为 false：客户端没有方块实体）。
     * <p>界面在客户端改读 S2C 快照里的 target（{@code SyncRequesterConfigPacket#target}），
     * 因此本方法只服务服务端逻辑 / 调试。</p>
     */
    public boolean isConfigured() {
        return requester != null && requester.isProperlyConfigured();
    }

    /** 上次请求是否成功（客户端镜像菜单没有方块实体 ⇒ 恒为 false；界面改读 S2C 快照的 lastSuccess）。 */
    public boolean wasLastRequestSuccessful() {
        return requester != null && requester.wasLastRequestSuccessful();
    }

    // ==================== 配置：目标名 + 已选资源的服务端权威入口 ====================

    // 已选资源**没有条数上限**：用户明确要求「订单上限没有设限」，因此本类与方块实体都不再有任何
    // 封顶常量（连 MAX_ORDERS / MAX_ORDER_ENTRIES 这类名字都不该出现，自检会扫）。

    /** 目标名结果。除 {@code OK} 外一律<b>不写入</b>方块实体。 */
    public enum TargetApplyResult {
        /** 已应用（或与原目标相同）。 */
        OK,
        /** 空名。 */
        EMPTY,
        /** 超过名字长度上限。 */
        TOO_LONG,
        /** 含控制字符 / §。 */
        INVALID_CHARS,
        /** 本网络里没有这个名字的接收器（找不到目标必须提示）。 */
        NOT_FOUND,
        /** 这个名字被多于一台接收器占用（历史重名 / 网络合并）：拒绝，绝不随机挑一台。 */
        AMBIGUOUS,
        /** 不在服务端。 */
        NOT_SERVER
    }

    /** 已选资源的操作结果。除 {@code OK} 外一律<b>不写入</b>方块实体。 */
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

    /** 提示语言键（服务端只发 key，界面自己翻译）。 */
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
    /**
     * 「清空订单」的两个结果语言键。
     * <p>分开的两条而不是一条：清空成功与「本来就是空的」对玩家是两种事实，
     * 用同一句「已清空」会让空订单的玩家以为自己的点击没生效。</p>
     */
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

    /**
     * 「网格打不开」的语言键 —— <b>转接</b>网格菜单里的同一常量，不另写一份文案。
     *
     * <p>它是编译期常量（{@code static final String} 由另一个常量初始化 ⇒ javac 直接内联），
     * 因此 {@code block/**} 引用本字段时<b>不会</b>在类加载期去解析 {@code client/grid} 的类，
     * 专用服务端安全判据（{@code tools/selfcheck_round47_dedicated_server_safety.py} ②）
     * 与本类既有的「网格菜单引用只出现在 menu 层」纪律都不受影响。</p>
     */
    public static final String MSG_PICKER_NO_NETWORK =
        cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu.MSG_NO_NETWORK;

    /**
     * 「网格界面没能打开」的语言键（服务端 {@code openMenu} 被拒绝时）—— 同样是<b>转接</b>，
     * 与 {@link #MSG_PICKER_NO_NETWORK} 配成一对：前者=没接网络，后者=网络没问题但这次没开成。
     * 两种情况都必须给玩家一句，否则就是「右键点了没反应」。
     */
    public static final String MSG_PICKER_OPEN_FAILED =
        cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu.MSG_OPEN_FAILED;

    /**
     * 服务端设置目标接收器（<b>唯一写入口</b>）。
     *
     * <p>客户端提交的是「玩家从候选列表里挑中的那台接收器」的<b>名字</b>（{@link #receiverViews()}
     * 下发的条目之一）—— 与直接手打名字走的是同一条路：界面只负责让玩家挑得动，
     * <b>判据全在这里</b>。因此「列表是不是最新」「名字是不是玩家自己敲的」都不影响安全性。</p>
     *
     * <h2>校验顺序（任何一步失败都不写入）</h2>
     * <ol>
     *     <li>必须是服务端（客户端镜像菜单 {@code requester == null} ⇒ {@code NOT_SERVER}）；</li>
     *     <li>空名 ⇒ {@code EMPTY}；长度 / 控制字符 ⇒ 拒绝（复用接收器菜单的同一段形状判据）；</li>
     *     <li><b>目标必须存在</b>：{@code resolveReceiversByName(name)} 为空 ⇒ {@code NOT_FOUND}；
     *     多于一台 ⇒ {@code AMBIGUOUS}（绝不随机挑一台）；</li>
     *     <li>全部通过才 {@code setTargetName(name)}（与原目标相同则只回成功，不重复写）。</li>
     * </ol>
     */
    public TargetApplyResult applyTargetFromUi(final String rawName) {
        if (requester == null || requester.getLevel() == null || requester.getLevel().isClientSide()) {
            return TargetApplyResult.NOT_SERVER;
        }
        final String name = rawName == null ? "" : rawName.trim();
        final NetworkResourceReceiverMenu.NameApplyResult shape =
            NetworkResourceReceiverMenu.checkNameShape(name);
        if (shape != NetworkResourceReceiverMenu.NameApplyResult.OK) {
            final TargetApplyResult result = switch (shape) {
                case EMPTY -> TargetApplyResult.EMPTY;
                case TOO_LONG -> TargetApplyResult.TOO_LONG;
                case INVALID_CHARS -> TargetApplyResult.INVALID_CHARS;
                default -> TargetApplyResult.EMPTY;
            };
            sendConfigSync(messageKeyFor(result));
            return result;
        }
        final List<NetworkResourceReceiverBlockEntity> hits = requester.resolveReceiversByName(name);
        if (hits.isEmpty()) {
            sendConfigSync(MSG_TARGET_NOT_FOUND);
            return TargetApplyResult.NOT_FOUND;
        }
        if (hits.size() > 1) {
            sendConfigSync(MSG_TARGET_AMBIGUOUS);
            return TargetApplyResult.AMBIGUOUS;
        }
        if (!name.equals(requester.getTargetName())) {
            requester.setTargetName(name);
        }
        sendConfigSync(MSG_TARGET_OK);
        return TargetApplyResult.OK;
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

    /**
     * 服务端<b>唯一</b>的「追加一条已选资源」实现。
     *
     * <p>请求器界面上<b>没有</b>「添加」按钮，网格界面里点一下资源就走这里 ——
     * 判据仍然只有一处：数量 &gt; 0、资源键非空，然后整体交给
     * {@link NetworkResourceRequesterBlockEntity#setOrders} 去合并 / 落盘（唯一清洗点）。
     * <b>没有条数上限</b>（用户明确要求「订单上限没有设限」）。</p>
     */
    static OrderResult appendOrderEntryChecked(
        @Nullable final NetworkResourceRequesterBlockEntity requester,
        @Nullable final PlatformResourceKey key, final int amount) {
        if (!isServerAuthoritative(requester)) {
            return OrderResult.NOT_SERVER;
        }
        if (amount <= 0) {
            return OrderResult.INVALID_AMOUNT;
        }
        if (key == null) {
            return OrderResult.INVALID_RESOURCE;
        }
        final List<com.refinedmods.refinedstorage.api.resource.ResourceAmount> next =
            new java.util.ArrayList<>(requester.getOrders());
        // 同资源合并交给 setOrders 的同一段逻辑：这里只负责把「一条新请求」交出去
        next.add(new com.refinedmods.refinedstorage.api.resource.ResourceAmount(key, amount));
        requester.setOrders(next);
        return OrderResult.OK;
    }

    /** 服务端：删除某一条的判据（下标 + 完整资源键二次确认，全部在写入之前）。 */
    static OrderResult removeEntryChecked(
        @Nullable final NetworkResourceRequesterBlockEntity requester,
        final int index, @Nullable final PlatformResourceKey key) {
        if (!isServerAuthoritative(requester)) {
            return OrderResult.NOT_SERVER;
        }
        final List<com.refinedmods.refinedstorage.api.resource.ResourceAmount> current =
            requester.getOrders();
        if (index < 0 || index >= current.size()) {
            return OrderResult.BAD_INDEX;
        }
        if (!entryMatches(current.get(index), key)) {
            return OrderResult.BAD_INDEX;
        }
        final List<com.refinedmods.refinedstorage.api.resource.ResourceAmount> next =
            new java.util.ArrayList<>(current);
        next.remove(index);
        requester.setOrders(next);
        return OrderResult.OK;
    }

    /** 服务端：改写某一条数量的判据（数量 &gt; 0、下标有效、资源键一致，全部在写入之前）。 */
    static OrderResult setAmountChecked(
        @Nullable final NetworkResourceRequesterBlockEntity requester,
        final int index, final int amount, @Nullable final PlatformResourceKey key) {
        if (!isServerAuthoritative(requester)) {
            return OrderResult.NOT_SERVER;
        }
        if (amount <= 0) {
            return OrderResult.INVALID_AMOUNT;
        }
        final List<com.refinedmods.refinedstorage.api.resource.ResourceAmount> current =
            requester.getOrders();
        if (index < 0 || index >= current.size()) {
            return OrderResult.BAD_INDEX;
        }
        if (!entryMatches(current.get(index), key)) {
            return OrderResult.BAD_INDEX;
        }
        final List<com.refinedmods.refinedstorage.api.resource.ResourceAmount> next =
            new java.util.ArrayList<>(current);
        next.set(index, new com.refinedmods.refinedstorage.api.resource.ResourceAmount(
            current.get(index).resource(), amount));
        requester.setOrders(next);
        return OrderResult.OK;
    }

    // ==================== 网格 / 请求器两个界面共用的服务端入口 ====================

    /**
     * 服务端：<b>清空订单</b>（请求器界面上的「清空订单」按钮与网格里的同义操作共用这一处）。
     *
     * <p>校验与增删改完全同一套：{@code requester} 必须是服务端权威（客户端镜像菜单
     * {@code requester == null} ⇒ {@code NOT_SERVER}），且必须属于玩家当前打开的菜单
     * （由调用方 {@code NetworkResourceRequesterMenu.requesterOf} + {@code stillValidFor} 保证）。
     * 清空只动<b>订单清单</b>：目标名字、升级、{@link NetworkResourceRequesterBlockEntity#getOrders()}
     * 之外的任何状态（缓存 / 网络节点 / 回滚暂存区）一个字都不碰 ——
     * 「清空订单」不是「拆掉机器」，也不该顺手丢掉已经取出来的资源。</p>
     *
     * @return {@link OrderResult#OK}（清空成功）/ {@link OrderResult#NOT_SERVER}；
     * 本来就是空清单也返回 {@code OK}，但回一条「本来就是空」的文案（见 {@link #MSG_ORDER_ALREADY_EMPTY}）
     */
    public static OrderResult clearOrdersFor(
        @Nullable final net.minecraft.server.level.ServerPlayer player,
        final int containerId) {
        final NetworkResourceRequesterBlockEntity requester = player == null
            ? null : requesterOf(player.containerMenu);
        // 与增删改同一套两道校验：① 服务端权威（客户端镜像菜单没有方块实体）；
        // ② 方块还在、玩家离得够近（stillValidFor）—— 清空虽然没有「下标 / 资源键」可伪造，
        // 但同样是「写方块实体」，不能比别的动作少一道门。
        if (!isServerAuthoritative(requester) || !stillValidFor(player, requester)) {
            return OrderResult.NOT_SERVER;
        }
        final boolean wasEmpty = requester.getOrders().isEmpty();
        if (!wasEmpty) {
            requester.clearOrders();
        }
        sendConfigSyncTo(player, containerId, wasEmpty ? MSG_ORDER_ALREADY_EMPTY : MSG_ORDER_CLEARED, -1);
        return OrderResult.OK;
    }
    //
    // 为什么全部做成静态：网格界面（client/grid/ResourcePickerMenu）也要增删改 + 发送，
    // 而它**不是**本菜单的实例。判据仍然只有本类这一处，网络包只做「解析出方块实体 + 归属校验」。

    /** 服务端权威前置（客户端镜像菜单没有方块实体，也没有可写的世界）。 */
    public static boolean isServerAuthoritative(
        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        return requester != null && requester.getLevel() != null
            && !requester.getLevel().isClientSide();
    }

    /**
     * 服务端：请求器方块还在、玩家离得够近。
     * <p>与右键开界面同一判据；网格界面（{@code ResourcePickerMenu#stillValid}）也复用它。</p>
     */
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

    /**
     * 玩家此刻打开的容器属于哪台请求器（请求器界面 / 网格界面都认；其它界面 ⇒ {@code null}）。
     * <p>网络包只调这一个方法，因此 {@code network/**} 里<b>不会</b>出现对 client 包的直接引用
     * （专用服务端安全硬约束见 {@code tools/selfcheck_round47_dedicated_server_safety.py} ②）。</p>
     */
    @Nullable
    public static NetworkResourceRequesterBlockEntity requesterOf(
        @Nullable final AbstractContainerMenu menu) {
        if (menu instanceof NetworkResourceRequesterMenu self) {
            return self.getRequester();
        }
        return cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu.requesterOf(menu);
    }

    /**
     * 服务端：把权威快照发给指定玩家（{@code containerId} 决定落到哪个界面）。
     * <p>请求器界面与网格界面共用这一条通道：快照永远是<b>完整覆盖</b>，客户端只读。</p>
     */
    public static void sendConfigSyncTo(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                        final int containerId, @Nullable final String messageKey,
                                        final int focusOrder) {
        if (player == null) {
            return;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterOf(player.containerMenu);
        if (requester == null) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.of(
                containerId, requester, receiverViews(requester),
                messageKey == null ? "" : messageKey, requester.wasLastRequestSuccessful(),
                focusOrder,
                cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
                    .isConnectedToNetwork(requester)));
    }

    /**
     * 服务端：追加一条已选资源（网格里点一下资源）。
     * @param containerId 发起请求的那张菜单的 id（快照按它回给对应界面）
     */
    public static OrderResult addEntryFor(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                          final int containerId,
                                          @Nullable final PlatformResourceKey key, final int amount) {
        if (player == null) {
            return OrderResult.NOT_SERVER;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterOf(player.containerMenu);
        final OrderResult result = appendOrderEntryChecked(requester, key, amount);
        sendConfigSyncTo(player, containerId,
            result == OrderResult.OK ? MSG_ORDER_ADDED : messageKeyFor(result),
            result == OrderResult.OK ? indexOfOrder(requester, key) : -1);
        return result;
    }

    /** 服务端：删除某一条已选资源（网格里右键点已选项 / 已选行）。 */
    public static OrderResult removeEntryFor(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                             final int containerId, final int index,
                                             @Nullable final PlatformResourceKey key) {
        if (player == null) {
            return OrderResult.NOT_SERVER;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterOf(player.containerMenu);
        final OrderResult result = removeEntryChecked(requester, index, key);
        sendConfigSyncTo(player, containerId,
            result == OrderResult.OK ? MSG_ORDER_REMOVED : messageKeyFor(result), -1);
        return result;
    }

    /** 服务端：改写某一条的数量（网格数量框回车 / 「应用」）。 */
    public static OrderResult setAmountFor(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                           final int containerId, final int index, final int amount,
                                           @Nullable final PlatformResourceKey key) {
        if (player == null) {
            return OrderResult.NOT_SERVER;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterOf(player.containerMenu);
        final OrderResult result = setAmountChecked(requester, index, amount, key);
        sendConfigSyncTo(player, containerId,
            result == OrderResult.OK ? MSG_ORDER_UPDATED : messageKeyFor(result),
            result == OrderResult.OK ? index : -1);
        return result;
    }

    /**
     * 服务端：立即发送（请求器界面与网格界面共用）。
     * <p>先按与红石路径<b>同一套判据</b>确认目标已配置且唯一存在（否则给出明确提示、<b>不触发</b>），
     * 再调用方块实体既有的 {@code triggerRequest()} —— 搬运逻辑完全在那边。</p>
     */
    public static void triggerFor(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                 final int containerId) {
        if (player == null) {
            return;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterOf(player.containerMenu);
        if (!isServerAuthoritative(requester)) {
            return;
        }
        if (!requester.isProperlyConfigured()) {
            sendConfigSyncTo(player, containerId, MSG_TRIGGER_UNCONFIGURED, -1);
            return;
        }
        final List<NetworkResourceReceiverBlockEntity> hits =
            requester.resolveReceiversByName(requester.getTargetName());
        if (hits.isEmpty()) {
            sendConfigSyncTo(player, containerId, MSG_TRIGGER_NOT_FOUND, -1);
            return;
        }
        if (hits.size() > 1) {
            sendConfigSyncTo(player, containerId, MSG_TRIGGER_AMBIGUOUS, -1);
            return;
        }
        requester.triggerRequest();
        sendConfigSyncTo(player, containerId,
            requester.wasLastRequestSuccessful() ? MSG_TRIGGER_OK : MSG_TRIGGER_FAILED, -1);
    }

    /**
     * 服务端：某资源当前在已选列表里的下标（没有 ⇒ -1）。
     * <p>网格里刚加入一条时用它算出「刚加进去的是第几条」，随快照带回去让那一行被选中。</p>
     */
    public static int indexOfOrder(
        @Nullable final NetworkResourceRequesterBlockEntity requester,
        @Nullable final PlatformResourceKey resource) {
        if (requester == null || resource == null) {
            return -1;
        }
        final List<com.refinedmods.refinedstorage.api.resource.ResourceAmount> current =
            requester.getOrders();
        for (int index = 0; index < current.size(); index++) {
            final com.refinedmods.refinedstorage.api.resource.ResourceAmount entry = current.get(index);
            if (entry != null && resource.equals(entry.resource())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 兼容入口（旧协议）：按「资源注册名 + 是否流体」解析成资源键，再走通用实例入口。
     * <p>新界面（网格）直接带完整 {@link PlatformResourceKey}，不会走这里。</p>
     */
    public OrderResult addOrderEntry(final String resourceId, final boolean fluid, final int amount) {
        return addOrderEntry(parseResourceKey(requester, resourceId, fluid), amount);
    }

    /**
     * 追加一条已选资源（<b>实例入口</b>：兼容旧调用）。
     * <p>判据与网格界面完全同源（{@link #appendOrderEntryChecked}），本方法只负责把
     * 「服务端玩家 + 本菜单 id」接上，然后复用静态入口的提示通道。</p>
     */
    public OrderResult addOrderEntry(@Nullable final PlatformResourceKey key, final int amount) {
        if (!isServerAuthoritative()) {
            return OrderResult.NOT_SERVER;
        }
        final OrderResult result = appendOrderEntryChecked(requester, key, amount);
        sendConfigSync(result == OrderResult.OK ? MSG_ORDER_ADDED : messageKeyFor(result));
        return result;
    }

    /**
     * 删除某一条（<b>实例入口</b>：用完整资源键做二次确认）。
     *
     * <p>为什么还要比对资源：清单可能被另一名玩家 / 红石路径改动过，客户端手里的下标就可能是旧的。
     * 只校验「下标在范围内」会把「改到别人头上」当成成功 —— 这里用完整 {@link PlatformResourceKey}
     * 相等做二次确认（含数据组件），不匹配就回「该条目已不存在」，绝不动任何一条别的资源。</p>
     */
    public OrderResult removeOrderEntry(final int index, @Nullable final PlatformResourceKey key) {
        if (!isServerAuthoritative()) {
            return OrderResult.NOT_SERVER;
        }
        final OrderResult result = removeEntryChecked(requester, index, key);
        sendConfigSync(result == OrderResult.OK ? MSG_ORDER_REMOVED : messageKeyFor(result));
        return result;
    }

    /** 兼容入口（旧协议）：把「注册名 + 是否流体」解析成键后再走通用实例入口。 */
    public OrderResult removeOrderEntry(final int index, final String resourceId, final boolean fluid) {
        return removeOrderEntry(index, parseResourceKey(requester, resourceId, fluid));
    }

    /**
     * 改写某一条的数量（<b>实例入口</b>：绝对值；必须 &gt; 0，下标必须有效，
     * 且该下标的资源键必须与客户端看到的<b>完全一致</b>）。
     * <p>资源比对的理由同 {@link #removeOrderEntry(int, PlatformResourceKey)}：防止用旧下标改到别的条目。</p>
     */
    public OrderResult setOrderAmount(final int index, final int amount,
                                      @Nullable final PlatformResourceKey key) {
        if (!isServerAuthoritative()) {
            return OrderResult.NOT_SERVER;
        }
        final OrderResult result = setAmountChecked(requester, index, amount, key);
        sendConfigSync(result == OrderResult.OK ? MSG_ORDER_UPDATED : messageKeyFor(result));
        return result;
    }

    /** 兼容入口（旧协议）：把「注册名 + 是否流体」解析成键后再走通用实例入口。 */
    public OrderResult setOrderAmount(final int index, final int amount, final String resourceId,
                                      final boolean fluid) {
        return setOrderAmount(index, amount, parseResourceKey(requester, resourceId, fluid));
    }

    /** 第 {@code index} 条是不是客户端看到的那一条（<b>完整资源键</b>相等；键为空 ⇒ 判否）。 */
    private static boolean entryMatches(
        @Nullable final com.refinedmods.refinedstorage.api.resource.ResourceAmount entry,
        @Nullable final PlatformResourceKey key) {
        return entry != null && key != null && key.equals(entry.resource());
    }

    /** 服务端权威前置（客户端镜像菜单没有方块实体，也没有可写的世界）。 */
    private boolean isServerAuthoritative() {
        return requester != null && requester.getLevel() != null
            && !requester.getLevel().isClientSide();
    }

    /**
     * 解析客户端提交的资源标识（旧协议字段；新界面直接带完整资源键，不走这里）。
     *
     * <p>顺序刻意分成两段：</p>
     * <ol>
     *     <li><b>原版物品 / 流体注册名</b>：与改前逐字一致（空气 / {@code Fluids.EMPTY} / 未注册 ⇒ 不认）；</li>
     *     <li><b>第三方类型令牌</b>（{@link RsccResourceKeys#parseToken}）：只有装了官方联动、
     *     网络里真的出现气体之类资源时，选择列表才会下发这种令牌。解码走 RS 自己的
     *     {@code ResourceCodecs.CODEC}，所以我们不需要认识任何第三方类型。</li>
     * </ol>
     * <p>两段都不认 ⇒ {@code null}（调用方回 {@code INVALID_RESOURCE}，绝不猜一个资源）。</p>
     */
    @Nullable
    public static PlatformResourceKey parseResourceKey(
        @Nullable final NetworkResourceRequesterBlockEntity requester,
        @Nullable final String resourceId, final boolean fluid) {
        if (!isServerAuthoritative(requester)) {
            return null;
        }
        return parseResource(requester.getLevel().registryAccess(), resourceId, fluid);
    }

    @Nullable
    private static PlatformResourceKey parseResource(
        @Nullable final net.minecraft.core.HolderLookup.Provider registries,
        @Nullable final String resourceId, final boolean fluid) {
        if (resourceId == null || resourceId.isEmpty()) {
            return null;
        }
        final net.minecraft.resources.ResourceLocation id =
            net.minecraft.resources.ResourceLocation.tryParse(resourceId);
        if (id != null) {
            if (fluid) {
                if (net.minecraft.core.registries.BuiltInRegistries.FLUID.containsKey(id)) {
                    final net.minecraft.world.level.material.Fluid found =
                        net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id);
                    if (found != null && found != net.minecraft.world.level.material.Fluids.EMPTY) {
                        return new com.refinedmods.refinedstorage.common.support.resource.FluidResource(
                            found);
                    }
                }
            } else if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                final net.minecraft.world.item.Item found =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (found != null && found != net.minecraft.world.item.Items.AIR) {
                    return new com.refinedmods.refinedstorage.common.support.resource.ItemResource(
                        found);
                }
            }
        }
        // 第三方类型（气体等）：旧界面把 RsccResourceKeys 生成的稳定令牌放在「注册名」字段里
        return RsccResourceKeys.parseToken(resourceId, registries);
    }

    /**
     * 本网络内所有「已命名接收器」的只读视图（<b>名字 + 坐标</b>），界面选择器的唯一数据源。
     *
     * <h2>数据从哪来（不新写一套枚举）</h2>
     * <p>走的是<b>本菜单上一批就已经在用的那一段</b>网络枚举：RS 自己的
     * {@code GraphNetworkComponent#getContainers()}（因此天然尊重「扳手断开线缆」与
     * 「分隔框架阻断」两道闸门），筛的也是接收器<b>同一套</b>判据 {@code isProperlyConfigured()}
     * —— 与 {@code NetworkResourceReceiverBlockEntity#receiversInNetwork()} 的成员判据逐条同源
     * （那边是 private，因此这里沿用同一段遍历写法，而<b>不是</b>加第二套判据）。
     * 服务端最终判定仍然只认请求器既有的 {@code resolveReceiversByName(name)}。</p>
     *
     * <p><b>只回「视图」不回方块实体</b>：普通玩家拿不到别人的方块实体，快照里也不能塞网络对象；
     * 名字 + 坐标足以让玩家认出「就是眼前那台」。空列表（本网络没有任何已命名接收器）是合法结果，
     * 界面据此显示「请先给接收器命名」。</p>
     */
    public List<cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView>
    receiverViews() {
        return receiverViews(requester);
    }

    /** 静态版本：网格界面发快照时也走同一段枚举（枚举口径仍然只有这一处）。 */
    static List<cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView>
    receiverViews(@Nullable final NetworkResourceRequesterBlockEntity requester) {
        if (!isServerAuthoritative(requester)) {
            return List.of();
        }
        final com.refinedmods.refinedstorage.api.network.Network network =
            requester.getNode().getNetworkOrNull();
        if (network == null) {
            return List.of();
        }
        final java.util.List<
            cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView> views =
            new java.util.ArrayList<>();
        for (final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer
            container : network.getComponent(
            com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent.class)
            .getContainers()) {
            if (!(container.getNode()
                instanceof cretae.cookiewyq.rs_create_compat.network.NetworkResourceReceiverNetworkNode
                node)) {
                continue;
            }
            final NetworkResourceReceiverBlockEntity found = node.getBlockEntity();
            // 与 receiversInNetwork()/occupiedReceiverNamesInNetwork() 同一道判据：空名 = 不可用，
            // 因此不进候选（选了也投递不了，那只会变成一条注定失败的配置）
            if (found == null || !found.isProperlyConfigured()) {
                continue;
            }
            views.add(new cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket
                .ReceiverView(found.getReceiverName(), found.getBlockPos().getX(),
                found.getBlockPos().getY(), found.getBlockPos().getZ()));
        }
        // 顺序稳定（按名字，再按坐标）：界面翻页时条目不会因为网络图遍历顺序变化而乱跳
        views.sort(java.util.Comparator
            .comparing(
                cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView::name)
            .thenComparingInt(
                cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView::x)
            .thenComparingInt(
                cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView::y)
            .thenComparingInt(
                cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket.ReceiverView::z));
        return List.copyOf(views);
    }

    /**
     * 界面上「立即发送」按钮的服务端入口（实例壳：判据全在 {@link #triggerFor}）。
     *
     * <p>红石上升沿与两个界面（请求器 / 网格）的发送按钮共用方块实体里那一个
     * {@code triggerRequest()} —— 搬运逻辑（取料 / 写缓存 / 回滚）完全在那边，本方法一行都不复制。</p>
     */
    public void triggerRequestFromUi() {
        if (!isServerAuthoritative()) {
            return;
        }
        final net.minecraft.server.level.ServerPlayer player = serverPlayerOrFind();
        if (player == null) {
            return;
        }
        triggerFor(player, containerId);
    }

    /** 资源注册名 → 字符串（{@code null} ⇒ 空串，绝不把 "null" 写进包）。 */
    private static String idOf(@Nullable final net.minecraft.resources.ResourceLocation id) {
        return id == null ? "" : id.toString();
    }

    // ==================== S2C 同步（只在变更 / 首次打开时发） ====================

    /** 当前打开本菜单的服务端玩家（首次由 {@link #broadcastChanges()} 反查后缓存）。 */
    @Nullable
    private net.minecraft.server.level.ServerPlayer serverPlayer;
    /** 首次快照是否已发送。 */
    private boolean configSynced;
    /** 上一次快照里的连通性（用于「开着界面时网络被拆了」时把按钮变灰）。 */
    private boolean lastSyncedConnected;
    /** 连通性复查的倒计时（tick）：每 20 tick 复查一次，绝不在每帧扫网络图。 */
    private int connectedRecheck = 20;
    /**
     * 界面打开期间「投递进度」的补发间隔（tick）。
     * <p>一次触发可能跨很多 tick 才投完（速度升级少时每 tick 只投几批），而快照只在
     * 「动作发生」时发 —— 中间过程客户端看不到，「已投 / 剩余」会停在第一批的数字上。</p>
     */
    private static final int LIVE_SYNC_INTERVAL = 10;
    /** 进度补发倒计时。 */
    private int liveSyncCooldown = LIVE_SYNC_INTERVAL;
    /** 最近一次补发的投递账签名（投完且数字不变 ⇒ 不再发）。 */
    private long lastRoundSignature = Long.MIN_VALUE;

    /**
     * 把权威配置（目标名 + 已选资源 + 本网络接收器列表 + 本次操作提示）发给客户端。
     * <p>只在「首次打开 / 每次成功或失败的变更」时调用，绝不每 tick 发。</p>
     */
    public void sendConfigSync(final String messageKey) {
        final net.minecraft.server.level.ServerPlayer player = serverPlayerOrFind();
        if (requester == null || player == null) {
            return;
        }
        lastSyncedConnected = cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
            .isConnectedToNetwork(requester);
        sendConfigSyncTo(player, containerId, messageKey, -1);
    }

    /**
     * 服务端：打开「网格选资源」界面（界面上「网格」按钮 → C2S {@code OpenRequesterPickerPacket}）。
     *
     * <h2>为什么入口在菜单里，而不是在包的处理体里</h2>
     * <p>{@code network/**} 不允许引用本模组 {@code client} 包的类（专用服务端安全硬规则，
     * 见 {@code tools/selfcheck_round47_dedicated_server_safety.py} 的 ②），
     * 因此「打开哪个菜单」这个只跟本方块有关的动作留在菜单里，网格菜单 / 屏幕自己负责开屏。</p>
     *
     * <p><b>没有连接可用网络时，网格根本不会打开</b>：这是用户报告的严重问题的正面修复
     * （旧判据只看「有没有 Network 对象 / 有没有 StorageNetworkComponent」，而 RS 给每个孤立方块
     * 都建了一个只有它自己的网络 ⇒ 守卫永远不触发）。判据见
     * {@code ResourcePickerMenu#isConnectedToNetwork}，这里只负责把失败原因回给界面。</p>
     *
     * @param amount 兼容参数（旧协议随包带来的默认数量；网格里每一条的数量由玩家当场输入）
     */
    public void openResourcePicker(final int amount) {
        if (!isServerAuthoritative()) {
            return;
        }
        final net.minecraft.server.level.ServerPlayer player = serverPlayerOrFind();
        if (player == null) {
            return;
        }
        // 判据只有 ResourcePickerMenu#openFor 一处：右键方块走静态入口 openPickerFor，
        // 本方法（设置面板上的旧「网格」入口）直接调同一个 openFor —— 两条入口不会各有一套连通性判据。
        if (!cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
            .openFor(player, requester)) {
            // 两种失败必须分开说：没接网络 vs 这次 openMenu 没成（后者在 1.21.1 里只有
            // createMenu 返回 null 才会发生）。任何一种都不许静默。
            sendConfigSync(isPickerConnected(requester)
                ? MSG_PICKER_OPEN_FAILED : MSG_PICKER_NO_NETWORK);
        }
    }

    /**
     * 服务端：这台请求器此刻是否接在可用网络上（网格能否打开的<b>唯一</b>判据的口径来源）。
     * <p>{@code block/**} 与 {@code menu/**} 只通过本方法问「为什么没打开」，
     * 不各自复写连通性判据（判据仍在 {@code ResourcePickerMenu#isConnectedToNetwork} 一处）。</p>
     */
    public static boolean isPickerConnected(
        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        return cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
            .isConnectedToNetwork(requester);
    }

    /**
     * 服务端：给这台请求器打开<b>网格主界面</b>（唯一的打开入口，右键方块与「打开网格编辑订单」共用）。
     *
     * <p>判据全在 {@code ResourcePickerMenu#openFor}（接在可用网络上才开），本方法只做空值前置，
     * 因此「方块右键」与「界面按钮」两条路径不可能各有一套判据。</p>
     *
     * @return {@code false} = 没打开（没接在可用网络上）。调用方必须给一句提示，
     *     绝不留一个点了没反应的交互。
     */
    public static boolean openPickerFor(@Nullable final net.minecraft.server.level.ServerPlayer player,
                                        @Nullable final NetworkResourceRequesterBlockEntity requester) {
        if (player == null || requester == null) {
            return false;
        }
        return cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
            .openFor(player, requester);
    }

    /**
     * 服务端：打开原来的<b>设置界面</b>（网格工具栏的「设置」按钮 = 命名目标 / 插件槽 / 投递进度）。
     *
     * <h2>为什么这一条也在菜单里</h2>
     * <p>菜单类型只能由服务端 {@code openMenu} 决定，客户端按钮无从自行换界面；而
     * {@code network/**} 又不许引用 {@code client/**}（专用服务端安全判据 ②），
     * 所以「把玩家从网格切回设置界面」这个动作落在菜单层：
     * C2S 仍是既有的 {@code RequesterOrderPacket}（动作 {@code ACTION_OPEN_SETTINGS}），
     * 因此<b>不需要新注册任何 payload</b>。</p>
     *
     * <p>两道校验与增删改完全相同：解析出玩家<b>当前打开的</b>菜单所属请求器 +
     * {@link #stillValidFor}（方块还在、距离够近）。任何一步不满足 ⇒ 什么都不做。</p>
     */
    public static void openSettingsFor(@Nullable final net.minecraft.server.level.ServerPlayer player) {
        if (player == null) {
            return;
        }
        final NetworkResourceRequesterBlockEntity requester = requesterOf(player.containerMenu);
        if (!isServerAuthoritative(requester) || !stillValidFor(player, requester)) {
            return;
        }
        // 2026-10-25（第 96 轮独立复核 P3）：openMenu 的返回值这里**不能**转成一句话——
        // 菜单类只许发语言键、不许自己拼自然语言（`selfcheck_round86` ①-7 是硬判据）。
        // 这条路径（从网格点「设置」而 openMenu 返回空）极罕见（原版会先 closeContainer 再开，
        // 只有菜单工厂返回 null 才会失败），因此保持静默并在交付说明里登记为已知限制。
        player.openMenu(providerFor(requester), requester.getBlockPos());
    }

    /**
     * 打开本菜单的<b>唯一</b> {@code MenuProvider} 工厂（右键方块 / 从网格切回设置共用）。
     *
     * <p>菜单标题是<b>语言键</b>（客户端翻译），服务端从不拼自然语言 —— 这也是本类里
     * <b>唯一</b>允许出现 {@code Component.translatable} 的位置（其余提示一律是 {@code MSG_*}
     * 常量，随 S2C 快照下发，由客户端翻译）。</p>
     */
    public static net.minecraft.world.MenuProvider providerFor(
        final NetworkResourceRequesterBlockEntity requester) {
        return new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, ignored) -> create(id, inventory, requester),
            Component.translatable(MENU_TITLE_KEY));
    }

    /**
     * 首次 {@code broadcastChanges} 时反查玩家并补发一份权威快照；
     * 之后 ① 每 10 tick 补发一次「投递进度」（一轮投递可能跨很多 tick，界面上的「已投」必须能看到在涨）；
     * ② 每 20 tick 复查一次「请求器还接在可用网络上吗」，状态变了就补发一份
     * （界面据此刷新提示；第 96 轮起<b>不</b>再把按钮变灰 —— 灰按钮等于「点了没反应」）。
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!isServerAuthoritative()) {
            return;
        }
        if (!configSynced) {
            final net.minecraft.server.level.ServerPlayer player = serverPlayerOrFind();
            if (player != null) {
                configSynced = true;
                sendConfigSync("");
            }
            return;
        }
        // ① 投递进度：只在「本轮仍在续投」或「账目数字变了」时发；投完且数字不变就不再发。
        //    一轮投递可能跨很多 tick（速度升级少时每 tick 只投几批），中间过程不补发的话，
        //    客户端的「已投 / 剩余」会一直停在第一批的数字上 —— 那是界面在撒谎。
        if (--liveSyncCooldown <= 0) {
            liveSyncCooldown = LIVE_SYNC_INTERVAL;
            final long signature = roundSignature();
            if (requester.isRoundActive() || signature != lastRoundSignature) {
                lastRoundSignature = signature;
                // 空提示：不要把玩家正在看的操作结果顶掉
                sendConfigSync("");
            }
        }
        // ② 连通性复查
        if (--connectedRecheck > 0) {
            return;
        }
        connectedRecheck = 20;
        final boolean connected = cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerMenu
            .isConnectedToNetwork(requester);
        if (connected != lastSyncedConnected) {
            // 提示照旧走同一条通道；这里刻意发空提示，避免把玩家上一条操作结果盖掉又重复一遍
            sendConfigSync("");
        }
    }

    /** 当前投递账的指纹（纯 getter 组合；只用于「要不要补发进度」，不参与任何业务判断）。 */
    private long roundSignature() {
        return java.util.Objects.hash(requester.getRoundPlanned(), requester.getRoundDelivered(),
            requester.getRoundRemaining(), requester.getRoundShortfall(),
            requester.isRoundActive());
    }

    /** 缓存 / 反查当前打开本菜单的服务端玩家。 */
    @Nullable
    private net.minecraft.server.level.ServerPlayer serverPlayerOrFind() {
        if (serverPlayer != null) {
            return serverPlayer;
        }
        if (requester == null || requester.getLevel() == null) {
            return null;
        }
        for (final Player player : requester.getLevel().players()) {
            if (player.containerMenu == this
                && player instanceof net.minecraft.server.level.ServerPlayer found) {
                serverPlayer = found;
                return found;
            }
        }
        return null;
    }

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
        if (requester == null || requester.getLevel() == null) {
            return true; // 客户端重建菜单：有效性由服务端校验
        }
        return stillValidFor(player, requester);
    }
}
