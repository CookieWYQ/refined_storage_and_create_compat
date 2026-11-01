package cretae.cookiewyq.rs_create_compat.client.grid;

import com.refinedmods.refinedstorage.common.api.grid.view.GridResource;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.grid.screen.GridScreen;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.resource.ResourceTypes;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import cretae.cookiewyq.rs_create_compat.network.RequesterOrderPacket;
import cretae.cookiewyq.rs_create_compat.network.SetRequesterTargetPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket;
import cretae.cookiewyq.rs_create_compat.network.TriggerRequesterPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 网络资源请求器的<b>网格主界面</b>（继承 RS 自己的 {@link GridScreen}）——
 * <b>右键请求器直接进这里</b>（第 96 轮用户原话：「反正一打开就是网格界面」）。
 *
 * <h2>一个界面里做完所有事</h2>
 * <ul>
 *     <li><b>左网格</b>：RS 自己的资源网格（搜索 / 排序 / 视图过滤全在），
 *     左键点资源 = 按「数量框」的值加入订单清单，右键 = 从清单移除；</li>
 *     <li><b>右清单</b>：订单清单（图标 + 名称 + 数量 + ×），<b>条目数无上限</b>、
 *     可滚轮滚动、<b>自带搜索框</b>（只过滤已加入的条目，大小写不敏感，匹配名称 / 资源 id）；</li>
 *     <li><b>清单底部</b>：改数量（输入框 + 回车应用 / −/+ 步进并立即应用，
 *     全部走既有 C2S 包与服务端校验）；</li>
 *     <li><b>顶部工具栏</b>：目标接收器（循环切换）/ 立即发送 / 清空订单 / 节流文字 /
 *     「设置」（切回原来的请求器设置界面）。</li>
 * </ul>
 *
 * <h2>客户端只读：所有写入都是「请求」</h2>
 * <p>增 / 删 / 改数量发 {@link RequesterOrderPacket}（动作 + 下标 + 完整资源键），
 * 目标切换发 {@link SetRequesterTargetPacket}，发送发 {@link TriggerRequesterPacket}
 * （服务端走与红石同一个 {@code triggerRequest()}），切设置界面发
 * {@code RequesterOrderPacket.ACTION_OPEN_SETTINGS}。服务端每一步都重新解析
 * 「玩家现在打开的是哪台请求器」并校验距离，客户端<b>不写世界、不查能力、不读方块实体</b>，
 * 显示层只吃两份只读来源：打开时的菜单快照 + {@link SyncRequesterConfigPacket} 的权威快照。</p>
 *
 * <h2>几何硬约束（字节码证据见 {@code docs/RESOURCE_PICKER_DESIGN.md} §2.1 / §4.2）</h2>
 * <ul>
 *     <li>网格 GUI 是 {@code 193×176}（{@code GridScreen} 硬编码），本类<b>不动</b>它的布局；</li>
 *     <li>右侧清单面板左沿 = {@code leftPos + 193 + 4 = +197}：<b>不压滚动条列</b>（{@code +174}）、
 *     <b>不压搜索框</b>（{@code +95..+162}）；</li>
 *     <li>顶部工具栏画在 GUI <b>上方</b>的空档里（{@code topPos - 20}）：176 高的 GUI 在
 *     1280×720 / GUI 缩放 3×（可用 426×240）时上下各余 32px，正好放一条 16px 工具条 ——
 *     这样它才是「顶部工具条」，而不是又挤进本来就只有 118px 宽的右列；</li>
 *     <li>面板 / 工具条背景画在 {@code super.render} <b>之前</b>（否则会盖住面板里的输入框与按钮），
 *     文字画在之后。</li>
 * </ul>
 *
 * <h2>性能</h2>
 * <p>清单行只在「构造 / 收到权威快照 / 改搜索词」时重建（{@link Row}），渲染循环里只做
 * {@code drawString} / {@code fill} / {@code renderItem}；每帧不扫网络、不解析注册表。
 * 搜索过滤在条目表上做一次子串匹配，<b>不</b>每帧解析。</p>
 */
public class ResourcePickerScreen extends GridScreen<ResourcePickerMenu> {
    /** 本界面全部语言键的前缀（与语言文件逐字一致）。 */
    private static final String LANG = "gui.rs_create_compat.resource_picker.";
    /**
     * 请求器命名空间的文案前缀：目标接收器 / 订单标题等文案<b>复用</b>设置界面那几条，
     * 不在这里另写一份（同一件事只有一套文案）。
     */
    private static final String QLANG = "gui.rs_create_compat.network_resource_requester.";

    // ==================== RS 网格界面的固定几何（javap 证据见设计文档 §2.1 / §4.2） ====================
    /** {@code GridScreen} 的固定宽度（构造器 {@code sipush 193}）。 */
    public static final int GRID_IMAGE_W = 193;
    /** 滚动条列相对 {@code leftPos} 的 x（{@code AbstractStretchingScreen.init} 的 {@code sipush 174}）。 */
    public static final int SCROLLBAR_X = 174;
    /** 搜索框相对 {@code leftPos} 的 x / y 与尺寸（{@code bipush 94 + 1}、{@code bipush 67}）。 */
    public static final int SEARCH_X = 95;
    public static final int SEARCH_W = 67;
    public static final int SEARCH_Y = 7;
    public static final int SEARCH_H = 16;

    // ==================== 右侧订单清单面板（全部相对 leftPos / topPos） ====================
    /** 面板与 GUI 右沿的间距。 */
    public static final int PANEL_GAP = 4;
    /** 面板左沿相对 {@code leftPos} 的偏移：193 + 4 = 197（> 滚动条列 174、> 搜索框右沿 162）。 */
    public static final int PANEL_X_OFFSET = GRID_IMAGE_W + PANEL_GAP;
    /**
     * 面板宽（够放「图标 + 名称 + 数量 + ×」，也够放「数量框 + −/+」）。
     * <p>刻意压到 118：1280×720 + GUI 缩放 3× 时可用宽度只有 426，GUI 右沿到屏幕右沿只剩 ~113px
     * —— 再宽就会被屏幕边缘裁掉。</p>
     */
    public static final int PANEL_W = 118;
    /** 面板顶相对 {@code topPos} 的偏移（与网格第一行齐平）。 */
    public static final int PANEL_TOP = 19;
    /** 面板内边距。 */
    public static final int PANEL_PAD = 3;
    /** 标题行高。 */
    public static final int HEADER_H = 11;
    /** 清单自带搜索框高。 */
    public static final int FILTER_H = 12;
    /** 每条订单的行高。 */
    public static final int ROW_H = 11;
    /** 行内图标的边长（物品图标 16×16 半比例绘制）。 */
    public static final int ICON_SIZE = 8;
    /** 行内名称相对面板左沿的 x（图标 8 + 间隔 2）。 */
    public static final int NAME_X = PANEL_PAD + 10;
    /** 行尾「×」（删除）热区的宽度。 */
    public static final int DELETE_W = 11;
    /** 数量编辑行高（输入框 16）。 */
    public static final int AMOUNT_ROW_H = 16;
    /** 底部提示行高。 */
    public static final int MESSAGE_H = 10;
    /** 控件之间的间距。 */
    public static final int GAP = 2;

    // ==================== 顶部工具栏 ====================
    /** 工具条高。 */
    public static final int TOOLBAR_H = 16;
    /** 工具条底边与 GUI 顶边的间距。 */
    public static final int TOOLBAR_GAP = 4;
    /** 工具条内按钮间距。 */
    public static final int BTN_GAP = 3;
    /** 目标接收器按钮宽（{@code ◀ 名字}；名字放不下时由原版 Button 整串滚动显示，不截断）。 */
    public static final int TARGET_W = 96;
    /** 「立即发送」按钮宽。 */
    public static final int SEND_W = 52;
    /** 「清空订单」按钮宽。 */
    public static final int CLEAR_W = 46;
    /** 「设置」按钮宽。 */
    public static final int SETTINGS_W = 40;

    // ==================== 颜色 ====================
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_HINT = 0xFF1F4E79;
    private static final int COLOR_WARN = 0xFF9B2020;
    private static final int COLOR_OK = 0xFF1E6B2E;
    /** 选中行的高亮底色（半透明蓝）。 */
    private static final int COLOR_ROW_SELECTED = 0x3300A0FF;
    /** 行尾「×」的常色 / 悬停色。 */
    private static final int COLOR_DELETE = 0xFF7A2020;
    private static final int COLOR_DELETE_HOVER = 0x44FF3030;
    /** 流体类型的数量色（与物品区分开）。 */
    private static final int COLOR_FLUID = 0xFF1F6FB0;
    /** 网格里「已在订单清单里」的格子高亮（半透明琥珀底 + 深绿 1px 边框）。 */
    private static final int COLOR_SELECTED_FILL = 0x55FFC107;
    private static final int COLOR_SELECTED_BORDER = 0xFF2E7D32;
    /** 滚动指示条（清单比可视区高时才画）。 */
    private static final int COLOR_SCROLL_TRACK = 0x22000000;
    private static final int COLOR_SCROLL_THUMB = 0x66FFFFFF;
    /** 删除用的字形（原版字体里有 U+00D7；不用字母 x，避免和物品名混在一起）。 */
    private static final String DELETE_GLYPH = "×";

    /** 网格每行 9 列（{@code AbstractGridScreen.COLUMNS} 的 {@code bipush 9}）。 */
    private static final int COLUMNS = 9;

    /** 订单清单的权威显示行（构造 / 收到快照时重建：渲染循环不再解析注册表）。 */
    private final List<Row> rows = new ArrayList<>();
    /** 搜索过滤后的可见行下标（指向 {@link #rows}；渲染与点击都按它换算）。 */
    private final List<Integer> visible = new ArrayList<>();
    /** 已在订单清单里的资源键（网格高亮用 O(1) 判定）。 */
    private final Set<PlatformResourceKey> selectedKeys = new HashSet<>();

    /** 加入数量 / 改数量的唯一输入框（解析口径：{@link RsccNumberField}）。 */
    private EditBox amountBox;
    /** 数量 −/+ 步进按钮（立即应用）。 */
    private Button minusButton;
    private Button plusButton;
    /** 订单清单自己的搜索框（只过滤已加入的条目，不触碰网格搜索）。 */
    private EditBox filterBox;
    /** 工具栏：目标接收器循环切换。 */
    private Button targetButton;
    /** 工具栏：立即发送。 */
    private Button sendButton;
    /** 工具栏：清空订单。 */
    private Button clearButton;
    /** 工具栏：打开设置界面（原来的请求器小面板）。 */
    private Button settingsButton;

    /** 当前聚焦的订单条目（{@link #rows} 的下标；-1 = 没有）。 */
    private int focusIndex = -1;
    /** 已把「哪一条资源」的数量载入数量框（避免每次快照都覆盖玩家正在输入的值）。 */
    private PlatformResourceKey amountLoadedKey;
    /** 最近一次解析成功的数量（输入框为空 / 非法时保留上一次的值；新建条目默认 1）。 */
    private long parsedAmount = 1L;
    /** 本地 / 服务端最近一次操作提示（语言键；空串 = 无）。 */
    private String messageKey = "";

    // ==================== 服务端权威镜（只显示用） ====================
    /** 服务端权威的目标接收器名（空串 = 未配置）。 */
    private String syncedTarget = "";
    /** 本网络内已命名接收器的只读视图（名字 + 坐标）。 */
    private List<SyncRequesterConfigPacket.ReceiverView> receivers = List.of();
    /** 请求器此刻是否接在可用网络上（工具栏据此说明「为什么发送不出去」）。 */
    private boolean networkConnected;
    /** 节流：每 tick 批数 / 单批量（服务端快照下发，界面不算）。 */
    private int pacingBatchesPerTick = 1;
    private long pacingBatch = 1L;

    // ==================== 几何（init 时算好，渲染与控件共用） ====================
    private int panelX;
    private int panelY;
    private int panelCapacity = 1;
    /** 清单的滚动偏移（条目比可视区高时用滚轮翻，绝不静默丢弃条目）。 */
    private int panelScroll;
    private int toolbarX;
    private int toolbarY;
    private int toolbarW;
    /** 工具条里节流文字的起点 x 与可用宽（放不下就只在 tooltip 里给）。 */
    private int pacingX;
    private int pacingW;

    /** 单行的只读显示数据（资源键 + 图标 + 名称 + 搜索文本 + 数量文本 + 是否流体 + 权威数量）。 */
    private record Row(PlatformResourceKey key, ItemStack icon, String name, String searchText,
                       String amountText, boolean fluid, long amount) {
    }

    public ResourcePickerScreen(final ResourcePickerMenu menu, final Inventory inventory,
                                final Component title) {
        super(menu, inventory, title);
        rebuildRowsFromSnapshot();
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();
        layoutPanel();
        layoutToolbar();
        buildAmountControls();
        buildFilterBox();
        buildToolbar();
        focusRow(focusIndex, true);
        updateButtons();
    }

    /** 清单底部：数量输入框 + −/+（步进后立即应用）。 */
    private void buildAmountControls() {
        final int inner = PANEL_W - PANEL_PAD * 2;
        final int stepW = 22;
        final int boxW = inner - stepW * 2 - GAP * 2;
        amountBox = new EditBox(font, panelX + PANEL_PAD, amountY(), boxW, AMOUNT_ROW_H,
            Component.translatable(LANG + "panel.amount"));
        amountBox.setTextShadow(false);
        amountBox.setValue(RsccNumberField.editable(parsedAmount,
            RsccNumberField.textWindow(boxW)));
        RsccNumberField.wire(amountBox, value -> parsedAmount = value);
        addRenderableWidget(amountBox);
        minusButton = Button.builder(Component.literal("-"), button -> stepAmount(-1))
            .bounds(panelX + PANEL_PAD + boxW + GAP, amountY(), stepW, AMOUNT_ROW_H)
            .build();
        addRenderableWidget(minusButton);
        plusButton = Button.builder(Component.literal("+"), button -> stepAmount(1))
            .bounds(panelX + PANEL_PAD + boxW + GAP + stepW + GAP, amountY(), stepW, AMOUNT_ROW_H)
            .build();
        addRenderableWidget(plusButton);
    }

    /** 清单自己的搜索框（只过滤已加入的订单条目）。 */
    private void buildFilterBox() {
        final int inner = PANEL_W - PANEL_PAD * 2;
        filterBox = new EditBox(font, panelX + PANEL_PAD, filterY(), inner, FILTER_H,
            Component.translatable(LANG + "order.search"));
        filterBox.setTextShadow(false);
        filterBox.setMaxLength(48);
        filterBox.setHint(Component.translatable(LANG + "order.search"));
        filterBox.setResponder(text -> {
            rebuildVisible();
            clampFocus();
            updateButtons();
        });
        addRenderableWidget(filterBox);
    }

    /** 顶部工具栏：目标接收器 / 立即发送 / 清空订单 / 设置。 */
    private void buildToolbar() {
        targetButton = Button.builder(targetLabel(), button -> cycleTarget(hasShiftDown() ? -1 : 1))
            .bounds(toolbarX, toolbarY, TARGET_W, TOOLBAR_H)
            .build();
        addRenderableWidget(targetButton);
        sendButton = Button.builder(Component.translatable(LANG + "panel.send"),
                button -> sendNow())
            .bounds(sendX(), toolbarY, SEND_W, TOOLBAR_H)
            .build();
        addRenderableWidget(sendButton);
        clearButton = Button.builder(Component.translatable(QLANG + "order.clear"),
                button -> requestClearOrders())
            .bounds(clearX(), toolbarY, CLEAR_W, TOOLBAR_H)
            .build();
        addRenderableWidget(clearButton);
        settingsButton = Button.builder(Component.translatable(LANG + "toolbar.settings"),
                button -> openSettings())
            .bounds(settingsX(), toolbarY, SETTINGS_W, TOOLBAR_H)
            .build();
        addRenderableWidget(settingsButton);
    }

    /** 算出面板位置 / 容量（屏幕尺寸变化时 {@code init} 会重新调用）。 */
    private void layoutPanel() {
        final int preferred = leftPos + PANEL_X_OFFSET;
        panelX = Math.min(preferred, Math.max(leftPos + GRID_IMAGE_W, width - PANEL_W - 2));
        panelY = topPos + PANEL_TOP;
        final int available = Math.max(ROW_H * 2, height - panelY - 2);
        // 固定控件总高：标题 + 搜索框 + 数量行 + 提示行 + 间距 + 上下内边距
        final int fixed = HEADER_H + FILTER_H + GAP + AMOUNT_ROW_H + GAP + MESSAGE_H + GAP
            + PANEL_PAD * 2;
        panelCapacity = Math.max(1, (available - fixed) / ROW_H);
    }

    /** 算出工具条位置（放在 GUI 上方的空档里；极窄窗口里夹到 0，宁可压住标题行也不画出屏幕）。 */
    private void layoutToolbar() {
        toolbarY = Math.max(0, topPos - TOOLBAR_H - TOOLBAR_GAP);
        toolbarX = leftPos;
        final int right = Math.min(panelX + PANEL_W, width - 2);
        toolbarW = Math.max(0, right - toolbarX);
        pacingX = settingsX() + SETTINGS_W + BTN_GAP + GAP;
        pacingW = Math.max(0, toolbarX + toolbarW - pacingX);
    }

    private int targetX() {
        return toolbarX;
    }

    private int sendX() {
        return targetX() + TARGET_W + BTN_GAP;
    }

    private int clearX() {
        return sendX() + SEND_W + BTN_GAP;
    }

    private int settingsX() {
        return clearX() + CLEAR_W + BTN_GAP;
    }

    /** 清单标题行的 y（面板坐标）。 */
    private int headerY() {
        return panelY + PANEL_PAD;
    }

    /** 清单搜索框的 y。 */
    private int filterY() {
        return headerY() + HEADER_H;
    }

    /** 可见订单行的顶。 */
    private int rowsTop() {
        return filterY() + FILTER_H + GAP;
    }

    /** 数量编辑行的 y。 */
    private int amountY() {
        return rowsTop() + panelCapacity * ROW_H + GAP;
    }

    /** 底部提示行的 y。 */
    private int messageY() {
        return amountY() + AMOUNT_ROW_H + GAP;
    }

    /** 面板总高（容量固定 ⇒ 加 / 删条目时控件不跳动）。 */
    private int panelHeight() {
        return (messageY() + MESSAGE_H + PANEL_PAD) - panelY;
    }

    // ==================== 订单清单镜像 ====================

    /** 从菜单额外数据（打开时的快照）重建显示行。 */
    private void rebuildRowsFromSnapshot() {
        rows.clear();
        selectedKeys.clear();
        for (final ResourcePickerMenu.OrderEntry entry : getMenu().selectedOrders()) {
            addRow(entry.key(), entry.amount());
        }
        rebuildVisible();
        clampFocus();
    }

    /** 从服务端权威快照重建显示行（每次增 / 删 / 改数量之后都会来一份）。 */
    private void rebuildRowsFromPacket(final SyncRequesterConfigPacket packet) {
        syncedTarget = packet.target() == null ? "" : packet.target();
        receivers = List.copyOf(packet.receivers());
        networkConnected = packet.networkConnected();
        pacingBatchesPerTick = Math.max(1, packet.pacingBatchesPerTick());
        pacingBatch = Math.max(0L, packet.pacingBatch());
        messageKey = packet.messageKey() == null ? "" : packet.messageKey();
        rows.clear();
        selectedKeys.clear();
        for (final SyncRequesterConfigPacket.OrderView view : packet.orders()) {
            if (view.key() != null) {
                addRow(view.key(), view.amount());
            }
        }
        rebuildVisible();
        clampFocus();
        if (packet.focusOrder() >= 0 && packet.focusOrder() < rows.size()) {
            focusRow(packet.focusOrder(), true);
        } else if (focusIndex >= 0) {
            // 条目的下标可能因为删除而移动：焦点跟着「当前这一条」重新对齐数量
            focusRow(focusIndex, true);
        }
        refreshToolbarLabels();
    }

    /** 追加一条显示行（资源键为空 ⇒ 跳过，绝不显示一条不可操作的行）。 */
    private void addRow(final PlatformResourceKey key, final long amount) {
        if (key == null) {
            return;
        }
        final boolean fluid = key.getResourceType() == ResourceTypes.FLUID;
        final String name = nameOf(key, fluid);
        rows.add(new Row(key, iconOf(key), name, (name + " " + idOf(key, fluid))
            .toLowerCase(Locale.ROOT), amountText(amount, fluid), fluid, amount));
        selectedKeys.add(key);
    }

    /** 资源键 → 行图标（物品用自身，流体用桶；都没有时留空 ⇒ 渲染时画一个色块）。 */
    private static ItemStack iconOf(final PlatformResourceKey key) {
        if (key instanceof ItemResource item) {
            return item.toItemStack();
        }
        if (key instanceof FluidResource fluidKey) {
            final Fluid fluid = fluidKey.fluid();
            if (fluid != null && fluid != Fluids.EMPTY) {
                final ItemStack bucket = new ItemStack(fluid.getBucket());
                if (!bucket.isEmpty() && bucket.getItem() != Items.AIR) {
                    return bucket;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 资源键 → 本地化显示名（解析不出来就退回键本身，绝不显示空串）。 */
    private static String nameOf(final PlatformResourceKey key, final boolean fluid) {
        if (key instanceof ItemResource item) {
            return item.toItemStack().getHoverName().getString();
        }
        if (key instanceof FluidResource fluidKey) {
            final Fluid found = fluidKey.fluid();
            if (found != null && found != Fluids.EMPTY) {
                return new FluidStack(found, 1).getHoverName().getString();
            }
        }
        return String.valueOf(key);
    }

    /**
     * 资源键 → 注册名（清单搜索要能按「资源 id」匹配，用户明确要求）。
     * <p>物品 / 流体走原版注册表；第三方类型（气体等）退回 {@code toString()} ——
     * 它至少包含类型名，能搜到，且绝不抛异常。</p>
     */
    private static String idOf(final PlatformResourceKey key, final boolean fluid) {
        if (key instanceof ItemResource item) {
            final ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.item());
            return id == null ? "" : id.toString();
        }
        if (key instanceof FluidResource fluidKey) {
            final ResourceLocation id = fluidKey.fluid() == null
                ? null : BuiltInRegistries.FLUID.getKey(fluidKey.fluid());
            return id == null ? "" : id.toString();
        }
        return String.valueOf(key);
    }

    /** 数量文本：物品按「个」，流体按桶换算（与请求器同一套口径，见 {@code RsccNumberField}）。 */
    private static String amountText(final long amount, final boolean fluid) {
        return fluid
            ? RsccNumberField.bucketHint(amount, 34)
            : "x" + RsccNumberField.grouped(amount);
    }

    /** 按搜索词重建可见下标表（大小写不敏感，匹配名称 / 资源 id）。 */
    private void rebuildVisible() {
        visible.clear();
        final String query = filterQuery();
        for (int index = 0; index < rows.size(); index++) {
            if (query.isEmpty() || rows.get(index).searchText().contains(query)) {
                visible.add(index);
            }
        }
        clampScroll();
    }

    /** 当前搜索词（小写、去首尾空白；没有输入框时为空）。 */
    private String filterQuery() {
        if (filterBox == null) {
            return "";
        }
        return filterBox.getValue().trim().toLowerCase(Locale.ROOT);
    }

    // ==================== 只选不搬：点击 / 滚轮 ====================

    /**
     * 点击：<b>订单行</b> = 选中（行尾「×」= 删除，右键也删除）；<b>网格区域</b> = 悬停资源左键加入、
     * 右键移除，空格子也吞掉；其余区域（工具栏 / 搜索框 / 数量框 / 玩家背包）走原生控件行为。
     *
     * <p><b>为什么网格区域要无条件 {@code return true}</b>：RS 的
     * {@code AbstractGridScreen#mouseClicked} 有一条<b>不需要悬停资源</b>的放入分支
     * （{@code canInsert = isOverStorageArea && 手持物品非空}），会把玩家光标上的物品塞进网络。
     * 本界面只选资源，所以这一整块矩形里的点击一律不落 {@code super}。</p>
     */
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        final int index = orderRowAt(mouseX, mouseY);
        if (index >= 0) {
            if (button == 1 || isOverDelete(mouseX)) {
                sendRemove(index);
            } else {
                // 点行 = 把这一条的数量重新载入数量框（即使它就是上一次聚焦的那一条）
                amountLoadedKey = null;
                focusRow(index, true);
            }
            updateButtons();
            return true;
        }
        if (isOverStorageArea(mouseX, mouseY)) {
            final GridResource hovered = getCurrentGridResource();
            if (hovered != null) {
                final PlatformResourceKey key = hovered.getResourceForRecipeMods();
                if (key != null) {
                    if (button == 0) {
                        sendAdd(key);
                    } else if (button == 1) {
                        sendRemoveKey(key);
                    }
                }
            }
            // 只选不搬：网格区域无论哪个键、有没有悬停资源，都不落 super
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滚轮：网格区域一律吞掉（RS 的滚轮在网格上 = 逐个取出 / 放入）；清单上 = 滚动。 */
    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (isOverStorageArea(mouseX, mouseY)) {
            return true;
        }
        if (isOverOrderRows(mouseX, mouseY)) {
            panelScroll = Math.max(0,
                Math.min(maxScroll(), panelScroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 鼠标是否落在订单行区域（只有这里消费滚轮与行点击）。 */
    private boolean isOverOrderRows(final double mouseX, final double mouseY) {
        if (mouseX < panelX || mouseX > panelX + PANEL_W) {
            return false;
        }
        final int y = (int) mouseY;
        return y >= rowsTop() && y < rowsTop() + panelCapacity * ROW_H;
    }

    /** 鼠标是否落在行尾「×」删除热区。 */
    private boolean isOverDelete(final double mouseX) {
        return mouseX >= deleteX();
    }

    /** 行尾「×」的 x（面板坐标）。 */
    private int deleteX() {
        return panelX + PANEL_W - PANEL_PAD - DELETE_W;
    }

    /**
     * 鼠标是否落在 RS 的网格内容区（<b>精确复刻</b> {@code AbstractGridScreen#isOverStorageArea}）：
     * {@code leftPos+7 .. leftPos+168}，纵向用基类的 {@code isInStretchedArea}（同一份可见行数）。
     */
    private boolean isOverStorageArea(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return x >= 7 && x <= 168 && isInStretchedArea(y);
    }

    /** 鼠标命中的订单条目下标（{@link #rows} 的权威下标；不在行区域 ⇒ -1）。 */
    private int orderRowAt(final double mouseX, final double mouseY) {
        if (!isOverOrderRows(mouseX, mouseY)) {
            return -1;
        }
        final int slot = panelScroll + ((int) mouseY - rowsTop()) / ROW_H;
        return slot >= 0 && slot < visible.size() ? visible.get(slot) : -1;
    }

    /** 回车（数量框聚焦时）= 把数量应用给当前选中的那一条。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && amountBox != null && amountBox.isFocused()) {
            applyAmount();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // 服务端权威快照：每次增 / 删 / 改 / 切目标 / 发送之后由菜单按本菜单 id 下发一份
        final SyncRequesterConfigPacket packet =
            SyncRequesterConfigPacket.takeFor(getMenu().containerId);
        if (packet != null) {
            rebuildRowsFromPacket(packet);
        }
        updateButtons();
    }

    // ==================== 工具栏动作（全部只发请求，不写本地配置） ====================

    /**
     * 循环切换目标接收器：<b>点一次就提交一次</b>。
     *
     * <p>为什么这里不像设置界面那样「翻页 + 选定」两步：工具栏只有一个按钮位（右列只有 118px，
     * 顶部工具条也只有一个按钮的宽度），而<b>每一次点击都是玩家的一次显式决定</b>；
     * 服务端仍然按名字现解析（找不到 / 重名一律拒绝并回提示），所以最坏情况是「没改成」，
     * 不会把资源投到一台没被选中的机器上。Shift+左键 = 上一台。</p>
     */
    private void cycleTarget(final int delta) {
        if (receivers.isEmpty()) {
            // 点了必须给一句：本网络里没有可选的接收器
            messageKey = QLANG + "target.known.none";
            return;
        }
        // 目标还没设 / 已不在本网络（拆了 / 改名了）⇒ 第一次点击直接落到候选第一台，
        // 而不是「从 0 再 +1」跳过第一台（那会让玩家以为第一台永远选不中）。
        int base = -1;
        for (int index = 0; index < receivers.size(); index++) {
            if (receivers.get(index).name().equals(syncedTarget)) {
                base = index;
                break;
            }
        }
        final SyncRequesterConfigPacket.ReceiverView next = base < 0
            ? receivers.get(0)
            : receivers.get(Math.floorMod(base + delta, receivers.size()));
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetRequesterTargetPacket(getMenu().containerId, next.name()));
    }

    /** 工具栏「立即发送」：服务端走与红石同一个 {@code triggerRequest()}。 */
    private void sendNow() {
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new TriggerRequesterPacket(getMenu().containerId));
    }

    /** 工具栏「清空订单」：只清订单清单（目标 / 缓存 / 升级一个都不动，服务端校验归属）。 */
    private void requestClearOrders() {
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(getMenu().containerId, RequesterOrderPacket.ACTION_CLEAR,
                -1, "", false, 0));
    }

    /**
     * 工具栏「设置」：切回原来的请求器界面（命名目标 / 插件槽 / 投递进度）。
     * <p>菜单类型只能由服务端 {@code openMenu} 决定，所以这里只是发一个「请打开设置」的动作；
     * 动作借用既有 C2S 包，不新增 payload 注册。</p>
     */
    private void openSettings() {
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(getMenu().containerId,
                RequesterOrderPacket.ACTION_OPEN_SETTINGS, -1, "", false, 0));
    }

    // ==================== 订单清单编辑（全部只发请求） ====================

    /** 左键点网格资源：把它按数量框的当前值加入订单清单（同资源自动合并，无条数上限）。 */
    private void sendAdd(final PlatformResourceKey key) {
        if (key == null) {
            return;
        }
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(getMenu().containerId, RequesterOrderPacket.ACTION_ADD, -1,
                "", false, currentAmount(), key));
    }

    /** 右键点网格里已在清单里的资源：按它的下标移除。 */
    private void sendRemoveKey(final PlatformResourceKey key) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).key().equals(key)) {
                sendRemove(index);
                return;
            }
        }
        // 点了必须给一句：这个资源其实不在清单里（多端 / 旧界面同时改过）
        messageKey = QLANG + "msg.order_bad_index";
    }

    /** 移除第 {@code index} 条（带完整资源键做二次确认，服务端同样再校一遍）。 */
    private void sendRemove(final int index) {
        if (index < 0 || index >= rows.size()) {
            messageKey = QLANG + "msg.order_bad_index";
            return;
        }
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(getMenu().containerId, RequesterOrderPacket.ACTION_REMOVE,
                index, "", rows.get(index).fluid(), 0, rows.get(index).key()));
    }

    /** 把数量框的值写回当前选中的那一条（回车 / 「应用」语义）。 */
    private void applyAmount() {
        if (focusIndex < 0 || focusIndex >= rows.size()) {
            // 点了必须给一句：先选中一条
            messageKey = LANG + "msg.select_first";
            return;
        }
        final RsccNumberField.Parsed parsed = parseAmount();
        if (!parsed.ok() || parsed.value() <= 0L) {
            messageKey = LANG + "msg.bad_amount";
            return;
        }
        messageKey = "";
        final Row row = rows.get(focusIndex);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(getMenu().containerId,
                RequesterOrderPacket.ACTION_SET_AMOUNT, focusIndex, "", row.fluid(),
                RsccNumberField.clampToInt(parsed.value()), row.key()));
    }

    /**
     * −/+ 步进并<b>立即应用</b>到当前选中的那一条。
     * <p>步长：物品 1 件；流体 1 桶（1000 mB）—— 按住 Shift 再乘 10。
     * 结果夹在 [1, int 上限]，绝不发一个 0 / 负数（服务端也会拒）。</p>
     */
    private void stepAmount(final int direction) {
        if (focusIndex < 0 || focusIndex >= rows.size()) {
            messageKey = LANG + "msg.select_first";
            return;
        }
        final Row row = rows.get(focusIndex);
        final long step = (row.fluid() ? 1000L : 1L) * (hasShiftDown() ? 10L : 1L);
        final long current = row.amount();
        final long next = Math.max(1L, Math.min(Integer.MAX_VALUE, current + step * direction));
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(getMenu().containerId,
                RequesterOrderPacket.ACTION_SET_AMOUNT, focusIndex, "", row.fluid(),
                (int) next, row.key()));
    }

    /** 数量框当前值（解析失败 / ≤0 ⇒ 1；解析口径仍走 {@link RsccNumberField#parse}）。 */
    private int currentAmount() {
        final RsccNumberField.Parsed parsed = parseAmount();
        if (parsed.ok() && parsed.value() > 0L) {
            return RsccNumberField.clampToInt(parsed.value());
        }
        return parsedAmount > 0L ? RsccNumberField.clampToInt(parsedAmount) : 1;
    }

    /** 数量框的解析结果（唯一口径）。 */
    private RsccNumberField.Parsed parseAmount() {
        return RsccNumberField.parse(amountBox == null ? "" : amountBox.getValue());
    }

    /** 选中某一行：高亮它、（可选）把它的数量载入数量框。 */
    private void focusRow(final int index, final boolean loadAmount) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        focusIndex = index;
        // 只在「聚焦的资源真的换了」时重载数量，绝不覆盖玩家正在输入的值
        if (loadAmount && !rows.get(index).key().equals(amountLoadedKey)) {
            loadAmount(index);
        }
    }

    /** 把某一条的数量载入数量框。 */
    private void loadAmount(final int index) {
        if (index < 0 || index >= rows.size() || amountBox == null) {
            return;
        }
        amountLoadedKey = rows.get(index).key();
        final long amount = rows.get(index).amount();
        parsedAmount = amount > 0L ? amount : 1L;
        amountBox.setValue(RsccNumberField.editable(parsedAmount,
            RsccNumberField.textWindow(amountBox.getWidth())));
    }

    /** 修正越界的焦点 / 滚动偏移；焦点若被搜索词过滤掉就清掉（绝不改一条看不见的条目）。 */
    private void clampFocus() {
        if (focusIndex >= rows.size()) {
            focusIndex = rows.size() - 1;
        }
        if (focusIndex >= 0 && !isVisible(focusIndex)) {
            focusIndex = -1;
            amountLoadedKey = null;
        }
        clampScroll();
        if (focusIndex >= 0) {
            final int slot = visible.indexOf(focusIndex);
            if (slot >= 0) {
                if (slot < panelScroll) {
                    panelScroll = slot;
                } else if (slot >= panelScroll + panelCapacity) {
                    panelScroll = slot - panelCapacity + 1;
                }
            }
        }
    }

    /** 该权威下标是否在当前可见表里。 */
    private boolean isVisible(final int index) {
        return visible.contains(index);
    }

    /** 滚动上限（可见条目数 - 可视行数，夹 ≥ 0）。 */
    private int maxScroll() {
        return Math.max(0, visible.size() - panelCapacity);
    }

    private void clampScroll() {
        panelScroll = Math.max(0, Math.min(panelScroll, maxScroll()));
    }

    /** −/+ 只在「确实选中了一条」时可点（其余按钮永远可点：点了必有一句提示）。 */
    private void updateButtons() {
        final boolean hasSelection = focusIndex >= 0 && focusIndex < rows.size();
        if (minusButton != null) {
            minusButton.active = hasSelection;
        }
        if (plusButton != null) {
            plusButton.active = hasSelection;
        }
    }

    /** 工具栏文案随快照刷新（目标名字 / 连接状态）。 */
    private void refreshToolbarLabels() {
        if (targetButton != null) {
            targetButton.setMessage(targetLabel());
        }
        if (sendButton != null) {
            sendButton.setMessage(Component.translatable(LANG + "panel.send"));
        }
    }

    // ==================== 网格高亮（重算 RS 的格位几何） ====================

    /**
     * 在基类画完每一行之后，给「已在订单清单里」的格子叠一层高亮。
     *
     * <h2>几何来源（字节码，设计文档 §4.2 已复算一遍）</h2>
     * <pre>
     * AbstractStretchingScreen#renderBg → renderRows(g, x=leftPos, y=topPos, topOffset=19, visibleRows, mouseX, mouseY)
     * AbstractGridScreen#renderRows   → cellX = x + 7 ; cellY = y + topOffset + row*18 - getScrollbarOffset()
     * AbstractGridScreen#renderCell   → 内容区左上 = (cellX + 1 + col*18, cellY + 1)
     *                                   index = row * 9 + col（COLUMNS = 9）
     * </pre>
     */
    @Override
    protected void renderRows(final GuiGraphics guiGraphics, final int x, final int y,
                              final int topOffset, final int visibleRows,
                              final int mouseX, final int mouseY) {
        super.renderRows(guiGraphics, x, y, topOffset, visibleRows, mouseX, mouseY);
        if (selectedKeys.isEmpty()) {
            return;
        }
        final List<GridResource> view = getMenu().getRepository().getViewList();
        final int scrollbarOffset = getScrollbarOffset();
        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                final int index = row * COLUMNS + col;
                if (index < 0 || index >= view.size()) {
                    break;
                }
                final GridResource resource = view.get(index);
                if (resource == null) {
                    continue;
                }
                final PlatformResourceKey key = resource.getResourceForRecipeMods();
                if (key == null || !selectedKeys.contains(key)) {
                    continue;
                }
                final int cellX = x + 7 + 1 + col * 18;
                final int cellY = y + topOffset + row * 18 - scrollbarOffset + 1;
                guiGraphics.fill(cellX, cellY, cellX + 16, cellY + 16, COLOR_SELECTED_FILL);
                // 1px 边框（四条窄条；内缘正好压在 16×16 内容区的外沿，不压图标）
                guiGraphics.fill(cellX - 1, cellY - 1, cellX + 17, cellY, COLOR_SELECTED_BORDER);
                guiGraphics.fill(cellX - 1, cellY + 16, cellX + 17, cellY + 17, COLOR_SELECTED_BORDER);
                guiGraphics.fill(cellX - 1, cellY, cellX, cellY + 16, COLOR_SELECTED_BORDER);
                guiGraphics.fill(cellX + 16, cellY, cellX + 17, cellY + 16, COLOR_SELECTED_BORDER);
            }
        }
    }

    // ==================== 渲染 ====================

    /**
     * 背景 → 网格 / 控件 → 面板文字。
     * <p>顺序是硬约束：面板与工具条背景必须在 {@code super.render} 之前（否则会盖住里面的
     * 输入框与按钮），文字必须在之后（否则会被网格行覆盖）。两块背景都在 GUI 之外的空白区，
     * 因此不影响任何点击命中。</p>
     */
    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        renderSelectedPanelBackground(guiGraphics);
        renderToolbarBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 原版槽位 tooltip（玩家背包 / 升级槽）—— 本模组 GUI 不会自动画，必须手动调一次
        renderTooltip(guiGraphics, mouseX, mouseY);
        renderToolbarText(guiGraphics);
        renderSelectedPanel(guiGraphics, mouseX, mouseY);
        renderHoverTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 右侧订单清单面板的背景（<b>必须在控件之前画</b>：否则会盖住面板里的搜索框 / 数量框 / 按钮）。
     * <p>方法名沿用本工程既定的一对契约名 {@code renderSelectedPanelBackground} /
     * {@code renderSelectedPanel}（{@code selfcheck_round86} ⑦-2 按这一对名字核对
     * 「背景在前、内容在后」的渲染顺序）；语义上它画的就是「当前已选订单清单」的面板。</p>
     */
    private void renderSelectedPanelBackground(final GuiGraphics guiGraphics) {
        McGui.panel(guiGraphics, panelX, panelY, PANEL_W, panelHeight());
    }

    /** 顶部工具条的背景（同样必须在 {@code super.render} 之前画）。 */
    private void renderToolbarBackground(final GuiGraphics guiGraphics) {
        if (toolbarW > 0) {
            McGui.panel(guiGraphics, toolbarX - 2, toolbarY - 2, toolbarW + 4, TOOLBAR_H + 4);
        }
    }

    /** 工具条右侧的速率文字「每 tick 批数 · 单批量」（放不下就只留 tooltip）。 */
    private void renderToolbarText(final GuiGraphics guiGraphics) {
        if (pacingW < 24) {
            return;
        }
        // 第 104 轮：完整显示（放不下整体缩字号、下限 0.6 倍），不再切成「每 tick 3 批…」；
        // 全文仍在悬停提示里（renderToolbarTooltip 的 pacing 那一段）。
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "toolbar.rate",
                pacingBatchesPerTick, RsccNumberField.compact(pacingBatch)),
            pacingX, toolbarY + (TOOLBAR_H - 8) / 2, pacingW, COLOR_TEXT);
    }

    /** 清单面板的内容（<b>必须在 {@code super.render} 之后画</b>，否则会被网格行覆盖）：标题 / 行 / 滚动指示 / 底部提示。 */
    private void renderSelectedPanel(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 标题：订单 N 种（筛选中时由底部提示行说明比例）
        final Component header = Component.translatable(QLANG + "order.label", rows.size());
        RsccTextFit.drawFitLine(guiGraphics, font, header,
            panelX + PANEL_PAD, headerY(), PANEL_W - PANEL_PAD * 2, COLOR_TITLE);
        // 行
        final int start = Math.min(panelScroll, Math.max(0, visible.size() - 1));
        final int end = Math.min(visible.size(), start + panelCapacity);
        for (int slot = start; slot < end; slot++) {
            final int y = rowsTop() + (slot - start) * ROW_H;
            renderOrderRow(guiGraphics, y, rows.get(visible.get(slot)), mouseX, mouseY);
        }
        renderScrollHint(guiGraphics);
        // 底部提示行：服务端提示 > 搜索状态 > 用法
        final String hintKey;
        final Object[] hintArgs;
        final boolean warn;
        if (!messageKey.isEmpty()) {
            hintKey = messageKey;
            hintArgs = EMPTY_ARGS;
            warn = !isSuccessMessage();
        } else if (rows.isEmpty()) {
            hintKey = LANG + "order.empty";
            hintArgs = EMPTY_ARGS;
            warn = false;
        } else if (!filterQuery().isEmpty() && visible.isEmpty()) {
            hintKey = LANG + "order.no_match";
            hintArgs = new Object[] {filterBox.getValue().trim()};
            warn = true;
        } else if (!filterQuery().isEmpty()) {
            hintKey = LANG + "order.filtered";
            hintArgs = new Object[] {visible.size(), rows.size()};
            warn = false;
        } else {
            hintKey = LANG + "panel.hint";
            hintArgs = EMPTY_ARGS;
            warn = false;
        }
        // 底部提示行是「服务端提示 / 搜索状态 / 用法」的唯一位：完整显示（缩字号而不截断）。
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(hintKey, hintArgs),
            panelX + PANEL_PAD, messageY(), PANEL_W - PANEL_PAD * 2,
            warn ? COLOR_WARN : COLOR_HINT);
    }

    private static final Object[] EMPTY_ARGS = new Object[0];

    /** 画一行：半比例图标 + 名称 + 右对齐数量 + 行尾「×」。 */
    private void renderOrderRow(final GuiGraphics guiGraphics, final int y, final Row row,
                                final int mouseX, final int mouseY) {
        final int x = panelX + PANEL_PAD;
        final int rowW = PANEL_W - PANEL_PAD * 2;
        final boolean focused = focusIndex >= 0 && focusIndex < rows.size()
            && rows.get(focusIndex).key().equals(row.key());
        if (focused) {
            guiGraphics.fill(x, y, x + rowW, y + ROW_H - 1, COLOR_ROW_SELECTED);
        }
        // ① 图标（16×16 缩放一半 = 8×8；没有桶的流体画一个色块）
        if (row.icon().isEmpty()) {
            guiGraphics.fill(x, y + 1, x + ICON_SIZE, y + 1 + ICON_SIZE,
                row.fluid() ? COLOR_FLUID : COLOR_TEXT);
        } else {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate((float) x, (float) (y + 1), 0.0F);
            guiGraphics.pose().scale(0.5F, 0.5F, 1.0F);
            guiGraphics.renderItem(row.icon(), 0, 0);
            guiGraphics.pose().popPose();
        }
        // ② 数量（右对齐，结束在「×」左侧）
        final String amount = row.amountText();
        final int amountX = Math.max(x + NAME_X, deleteX() - 2 - font.width(amount));
        // ③ 名称（在剩余宽度里完整显示：缩字号而不截断）
        final int nameMax = Math.max(10, amountX - 2 - (panelX + NAME_X));
        // 名称是自由文本（物品 / 流体的显示名）⇒ 完整显示：缩字号而不截断，全文在行悬停提示里
        // （renderRowTooltip）。数量是自算的短数字，同样不截断。
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(row.name()),
            panelX + NAME_X, y + 2, nameMax, COLOR_TEXT);
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(amount), amountX, y + 2,
            Math.max(10, deleteX() - 2 - amountX), row.fluid() ? COLOR_FLUID : COLOR_TEXT);
        // ④ 行尾「×」（鼠标在这个热区上时给个红底，明确它是删除）
        final int delX = deleteX();
        if (mouseX >= delX && mouseX < delX + DELETE_W
            && mouseY >= y && mouseY < y + ROW_H) {
            guiGraphics.fill(delX - 1, y, delX + DELETE_W, y + ROW_H - 1, COLOR_DELETE_HOVER);
        }
        guiGraphics.drawString(font, DELETE_GLYPH, delX + 2, y + 2, COLOR_DELETE, false);
    }

    /** 清单比可视区高时，在面板右缘画一条细滚动指示（绝不静默隐藏条目）。 */
    private void renderScrollHint(final GuiGraphics guiGraphics) {
        if (visible.size() <= panelCapacity) {
            return;
        }
        final int trackX = panelX + PANEL_W - 1;
        final int trackTop = rowsTop();
        final int trackH = panelCapacity * ROW_H - 1;
        guiGraphics.fill(trackX, trackTop, trackX + 1, trackTop + trackH, COLOR_SCROLL_TRACK);
        final int thumbH = Math.max(4, trackH * panelCapacity / visible.size());
        final int max = Math.max(1, visible.size() - panelCapacity);
        final int thumbY = trackTop + (trackH - thumbH) * Math.min(panelScroll, max) / max;
        guiGraphics.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, COLOR_SCROLL_THUMB);
    }

    /** 服务端提示是否属于「成功」类（决定颜色；判据是一组常量，不猜字符串后缀）。 */
    private boolean isSuccessMessage() {
        return NetworkResourceRequesterMenu.MSG_ORDER_ADDED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_REMOVED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_UPDATED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_CLEARED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_TARGET_OK.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_TRIGGER_OK.equals(messageKey);
    }

    // ==================== tooltip（全部手动绘制：MC 不会替我们调） ====================

    /**
     * 悬停 tooltip 的唯一出口（在 {@code super.render} 之后调用，因此永远画在最上层）。
     * <p>槽位的原版 tooltip 已经由 {@link #render} 里的 {@code renderTooltip(...)} 画过，
     * 这里用 {@code vanillaSlotTooltipWillDraw} 保证<b>同一格只画一份</b>（不叠框）。</p>
     */
    private void renderHoverTooltips(final GuiGraphics guiGraphics, final int mouseX,
                                     final int mouseY) {
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        if (renderToolbarTooltip(guiGraphics, mouseX, mouseY)) {
            return;
        }
        if (renderRowTooltip(guiGraphics, mouseX, mouseY)) {
            return;
        }
        if (renderAmountTooltip(guiGraphics, mouseX, mouseY)) {
            return;
        }
        renderFilterTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 工具条各控件的悬停说明之一（返回 true = 已经画过 tooltip）。 */
    private boolean renderToolbarTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                         final int mouseY) {
        if (targetButton != null && targetButton.isMouseOver(mouseX, mouseY)) {
            final Component list = receivers.isEmpty()
                ? Component.translatable(QLANG + "target.known.none")
                : Component.translatable(QLANG + "target.known", receiverNames());
            guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "toolbar.target.hint"), list),
                java.util.Optional.empty(), mouseX, mouseY);
            return true;
        }
        if (sendButton != null && sendButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "toolbar.send.hint")),
                java.util.Optional.empty(), mouseX, mouseY);
            return true;
        }
        if (clearButton != null && clearButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(QLANG + "order.clear.hint")),
                java.util.Optional.empty(), mouseX, mouseY);
            return true;
        }
        if (settingsButton != null && settingsButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "toolbar.settings.hint")),
                java.util.Optional.empty(), mouseX, mouseY);
            return true;
        }
        if (pacingW >= 24 && mouseX >= pacingX && mouseX < pacingX + pacingW
            && mouseY >= toolbarY && mouseY < toolbarY + TOOLBAR_H) {
            // 节流口径与设置界面逐字同一份文案（同一个语言键），空格不够时也能看到全文
            guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(QLANG + "upgrade.pacing",
                        pacingBatchesPerTick, RsccNumberField.grouped(pacingBatch))),
                java.util.Optional.empty(), mouseX, mouseY);
            return true;
        }
        return false;
    }

    /** 订单行的完整名称 + 数量（行内名称缩字号也放不下时，tooltip 给全文）。 */
    private boolean renderRowTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                     final int mouseY) {
        final int index = orderRowAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        final Row row = rows.get(index);
        final Component amountLine = row.fluid()
            ? Component.translatable(QLANG + "order.entry.fluid", row.name(),
            RsccNumberField.grouped(row.amount()))
            : Component.translatable(QLANG + "order.entry.item", row.name(),
            RsccNumberField.grouped(row.amount()));
        final List<Component> lines = new ArrayList<>(3);
        lines.add(amountLine);
        if (isOverDelete(mouseX)) {
            lines.add(Component.translatable(LANG + "order.delete.hint", row.name()));
        }
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
        return true;
    }

    /** 数量框 / −/+ 的用法（回车应用、步长口径）。 */
    private boolean renderAmountTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                        final int mouseY) {
        final boolean overBox = amountBox != null && amountBox.isMouseOver(mouseX, mouseY);
        final boolean overStep = (minusButton != null && minusButton.isMouseOver(mouseX, mouseY))
            || (plusButton != null && plusButton.isMouseOver(mouseX, mouseY));
        if (!overBox && !overStep) {
            return false;
        }
        final boolean fluid = focusIndex >= 0 && focusIndex < rows.size()
            && rows.get(focusIndex).fluid();
        guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "order.amount.hint",
                    Component.translatable(LANG + (fluid ? "order.amount.step.fluid"
                        : "order.amount.step.item"), hasShiftDown() ? 10 : 1).getString())),
            java.util.Optional.empty(), mouseX, mouseY);
        return true;
    }

    /** 清单搜索框的说明（只过滤已加入的条目，不碰网格搜索）。 */
    private boolean renderFilterTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                        final int mouseY) {
        if (filterBox == null || !filterBox.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "order.search.hint")),
            java.util.Optional.empty(), mouseX, mouseY);
        return true;
    }

    // ==================== 文案 ====================

    /** 工具栏目标按钮文案：{@code ◀ 名字}（没有候选 ⇒「没有可选接收器」）。 */
    private Component targetLabel() {
        if (receivers.isEmpty()) {
            return Component.translatable(QLANG + "target.entry.none");
        }
        final SyncRequesterConfigPacket.ReceiverView current = currentReceiver();
        if (current == null) {
            return Component.translatable(QLANG + "target.entry.none");
        }
        // 第 104 轮：接收器名字是玩家自由命名（最长 32 字），按钮只有 96px ⇒ 不再裁成「名字…」，
        // 交给原版 Button 的 renderScrollingString 整串滚动显示；全表在按钮悬停提示里（全部候选名）。
        return Component.translatable(QLANG + "target.entry", current.name());
    }

    /** 服务端权威目标在候选里的那一项（不在 ⇒ null：拆了 / 改名了 / 还没设）。 */
    private SyncRequesterConfigPacket.ReceiverView currentReceiver() {
        for (final SyncRequesterConfigPacket.ReceiverView view : receivers) {
            if (view.name().equals(syncedTarget)) {
                return view;
            }
        }
        return null;
    }

    /**
     * tooltip 里列出的候选名字串（<b>全部</b>候选，逗号分隔）。
     * <p>第 104 轮：旧实现只列前 6 台再补一个字面量 {@code " ..."}，用户口径是「界面不要省略号」
     * ⇒ 现在全部拼进去，由原版 tooltip 自己折行，一条信息都不丢。</p>
     */
    private String receiverNames() {
        final StringBuilder builder = new StringBuilder();
        for (int index = 0; index < receivers.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(receivers.get(index).name());
        }
        return builder.toString();
    }

    // ==================== 工具 ====================

}
