package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import cretae.cookiewyq.rs_create_compat.network.OpenRequesterPickerPacket;
import cretae.cookiewyq.rs_create_compat.network.RequesterOrderPacket;
import cretae.cookiewyq.rs_create_compat.network.SetRequesterTargetPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket;
import cretae.cookiewyq.rs_create_compat.network.TriggerRequesterPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 网络资源请求器的<b>设置界面</b>：<b>目标接收器 + 订单摘要 + 投递进度 + 节拍 + 状态 +
 * 立即发送 / 清空订单 / 「打开网格编辑订单」 + 右侧 6 个插件槽</b> + 玩家背包。
 *
 * <h2>第 96 轮：这台小面板不再是主界面</h2>
 * <p>用户原话：「<i>发送器他就不要用那种额外的东西，就是网格，然后打开网格之后旁边就有一个清单……
 * 反正一打开就是网格界面</i>」。因此：</p>
 * <ul>
 *     <li><b>右键方块直接进网格界面</b>（{@code ResourcePickerScreen}：左网格 + 右侧可搜索的订单清单）；</li>
 *     <li>本界面变成<b>设置</b>：从网格工具栏的「设置」按钮进来，负责命名目标、插件槽、
 *     投递进度这些「不是每次都要看」的东西；</li>
 *     <li>原来那套 <b>4×2 = 8 个订单格删掉了</b> —— 那是人为上限（订单条目本来就没有上限，
 *     第 9 种资源在旧界面里根本看不见）。现在只留<b>一行摘要</b>「订单 N 种 / 共 M 件」+
 *     一个大按钮「打开网格编辑订单」。<b>订单的编辑（搜索 / 删除 / 改数量）全部在网格里完成</b>。</li>
 * </ul>
 *
 * <h2>「立即发送」「清空订单」「打开网格」都真的可用</h2>
 * <ul>
 *     <li>立即发送：{@link TriggerRequesterPacket} → 服务端既有的 {@code triggerRequest()}
 *     （红石与按钮共用同一个入口，校验全在服务端）；</li>
 *     <li>清空订单：{@link RequesterOrderPacket#ACTION_CLEAR} → 服务端
 *     {@code NetworkResourceRequesterMenu#clearOrdersFor}（校验归属 / 服务端侧后才写，只清订单）；</li>
 *     <li>打开网格：{@link OpenRequesterPickerPacket}（未接入可用网络时<b>不静默失败</b>，
 *     直接在本界面提示行给出原因）。</li>
 * </ul>
 * <p>三处都只是「请求」，客户端没有任何本地写配置的路径。</p>
 *
 * <h2>背景 = 工具生成（第 128 轮）</h2>
 * <p>背景 {@code textures/gui/network_resource_requester.png}（210×238）由
 * {@code tools/make_gui_bg.py --slots-menu "188,16,1,6" "9,164,9,3" "9,218,9,1"} 生成
 * （{@code pic/bg.png} 九宫格 + {@code pic/slot.png} 槽框烘焙，槽框左上角 = Menu 坐标 − 1）；
 * {@code renderBg} 一次 blit，不再有 {@code McGui.panel} / 逐槽 {@code McGui.slotFrame}。</p>
 *
 * <h2>dist 安全</h2>
 * <p>引用 {@code net.minecraft.client.*} 的类只能放在 {@code client/} 包下，
 * 由 {@code ClientInit} 注册，服务端不加载。</p>
 */
public class NetworkResourceRequesterScreen extends AbstractContainerScreen<NetworkResourceRequesterMenu> {
    /** 本界面全部语言键的前缀（与语言文件逐字一致）。 */
    private static final String LANG = "gui.rs_create_compat.network_resource_requester.";

    /** 背景贴图（{@link NetworkResourceRequesterMenu#PANEL_W} × {@code PANEL_H}；工具生成，槽框已烘焙）。 */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        RS_Create_Compat.MODID, "textures/gui/network_resource_requester.png");

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_WARN = 0xFF9B2020;
    /** 成功提示（暗绿）。 */
    private static final int COLOR_OK = 0xFF1E6B2E;
    /** 提示行颜色：暗蓝（教程性信息，与错误红区分开）。 */
    private static final int COLOR_HINT = 0xFF1F4E79;

    // ==================== 上半区（Menu 坐标） ====================
    private static final int TEXT_X = 8;
    private static final int REDSTONE_Y = 18;
    private static final int STATUS_Y = 29;
    private static final int TARGET_HINT_Y = 40;
    private static final int BOX_H = 16;

    private static final int TARGET_X = 8;
    private static final int TARGET_Y = 47;
    private static final int TARGET_CYCLE_W = 124;
    private static final int TARGET_SELECT_X = TARGET_X + TARGET_CYCLE_W + 4;
    private static final int TARGET_SELECT_W = 40;

    /** 动作按钮行：立即发送 / 清空订单（两个按钮都真的能用；宽度铺满左列）。 */
    private static final int TRIGGER_X = 8;
    private static final int TRIGGER_Y = 66;
    private static final int TRIGGER_W = 88;
    private static final int TRIGGER_H = 18;
    private static final int CLEAR_X = TRIGGER_X + TRIGGER_W + 4;
    private static final int CLEAR_W = 88;

    /**
     * 「订单摘要」行 + 「打开网格编辑订单」大按钮。
     * <p>第 96 轮把这台机器的订单编辑整体搬到网格界面后，这里只剩一行摘要与一个入口 ——
     * 原来的 4×2 图标格是人为上限（第 9 种资源根本看不见），已经删掉。</p>
     */
    private static final int PACING_Y = 90;
    private static final int SUMMARY_Y = 99;
    private static final int OPEN_GRID_X = 8;
    private static final int OPEN_GRID_Y = 112;
    private static final int OPEN_GRID_W = 176;
    private static final int OPEN_GRID_H = 20;
    private static final int STATS_Y = 138;

    /** 左列文字可用宽度（左列右沿 176，再留 2px）。 */
    private static final int TEXT_MAX_W = 176 - TEXT_X - 2;
    /** 「插件」栏标题：与接收器同一位置。 */
    private static final int UPGRADES_LABEL_X = 168;
    private static final int UPGRADES_LABEL_Y = 6;
    /**
     * 「插件」栏标题的可用宽度：到升级槽框左沿（187）为止 = 19px。
     * <p>英文原串 "Upgrades" 是 40px，放不下 ⇒ 语言值已收成 "Upg"（完整含义在空升级槽的 tooltip 里）。
     * 绘制走 {@code RsccTextFit.drawFitLine}：完整显示、绝不省略。</p>
     */
    private static final int UPGRADES_LABEL_MAX_W = 187 - UPGRADES_LABEL_X;

    // ==================== 控件与只读镜像 ====================
    /** 接收器选择器按钮（点它 = 翻页；只能挑，不能写）。 */
    private Button targetButton;
    /** 「选定」按钮：把当前光标那台接收器的<b>名字</b>提交给服务端。 */
    private Button selectButton;
    /** 「打开网格编辑订单」大按钮：本界面的唯一订单入口。 */
    private Button openGridButton;
    /** 「立即发送」按钮。 */
    private Button triggerButton;
    /** 「清空订单」按钮。 */
    private Button clearButton;

    /** 服务端权威的目标接收器名字（S2C；空串 = 未配置）。 */
    private String syncedTarget = "";
    /** 服务端权威的接收器候选列表（本网络内所有已命名接收器，名字 + 坐标；S2C）。 */
    private List<SyncRequesterConfigPacket.ReceiverView> receivers = List.of();
    /** 选择器光标（客户端本地；「选定」才发包）。越界在每次同步后夹回。 */
    private int receiverIndex;
    /** 已选资源（服务端权威顺序 = 投递顺序；客户端只读显示）。 */
    private List<SyncRequesterConfigPacket.OrderView> orders = List.of();
    /** 服务端权威：请求器此刻是否接在可用网络上。 */
    private boolean networkConnected;
    /** 服务端最近一次操作提示（语言键；空串 = 无）。 */
    private String messageKey = "";
    /** 最近一轮投递的账（服务端权威；单位随资源自身：物品件 / 流体 mB）。 */
    private long roundPlanned;
    private long roundDelivered;
    private long roundRemaining;
    private long roundShortfall;
    private boolean roundActive;

    public NetworkResourceRequesterScreen(final NetworkResourceRequesterMenu menu,
                                          final Inventory inventory,
                                          final Component title) {
        super(menu, inventory, title);
        this.imageWidth = NetworkResourceRequesterMenu.PANEL_W;
        this.imageHeight = NetworkResourceRequesterMenu.PANEL_H;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        // 玩家背包标签：背包已下移到 Menu.INV_Y0 = 164，标签按同一间距上推 10px
        this.inventoryLabelY = 154;
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();
        // 接收器选择器：初值 = 服务端权威目标（没有就停在列表第一台）
        targetButton = Button.builder(targetLabel(),
                button -> cycleReceiver(hasShiftDown() ? -1 : 1))
            .bounds(leftPos + TARGET_X, topPos + TARGET_Y, TARGET_CYCLE_W, BOX_H)
            .build();
        addRenderableWidget(targetButton);
        selectButton = Button.builder(Component.translatable(LANG + "target.button"),
                button -> submitTarget())
            .bounds(leftPos + TARGET_SELECT_X, topPos + TARGET_Y, TARGET_SELECT_W, BOX_H)
            .build();
        addRenderableWidget(selectButton);
        // 立即发送：服务端调用既有的 triggerRequest()（红石与按钮共用同一个入口）
        triggerButton = Button.builder(Component.translatable(LANG + "trigger.button"),
                button -> trigger())
            .bounds(leftPos + TRIGGER_X, topPos + TRIGGER_Y, TRIGGER_W, TRIGGER_H)
            .build();
        addRenderableWidget(triggerButton);
        // 清空订单：只提交「清空」这个动作，服务端校验后写；不动目标、不动缓存
        clearButton = Button.builder(Component.translatable(LANG + "order.clear"),
                button -> requestClearOrders())
            .bounds(leftPos + CLEAR_X, topPos + TRIGGER_Y, CLEAR_W, TRIGGER_H)
            .build();
        addRenderableWidget(clearButton);
        // 打开网格：网格现在是订单的唯一编辑处（搜索 / 删除 / 改数量 / 发送 / 切目标）。
        // 未接入可用网络时**不变灰**：点它必须给出一句「为什么打不开」，而不是点了没反应。
        openGridButton = Button.builder(openGridLabel(),
                button -> openResourceGrid())
            .bounds(leftPos + OPEN_GRID_X, topPos + OPEN_GRID_Y, OPEN_GRID_W, OPEN_GRID_H)
            .build();
        addRenderableWidget(openGridButton);
        updateButtons();
        updateTargetButtons();
    }

    // ==================== 按钮动作（全部只发「请求」，不写本地配置） ====================

    /**
     * 翻页：在服务端下发的候选列表里切换光标（<b>只动本地光标，不发包、不写配置</b>）。
     * <p>为什么翻页不直接提交：请求器收到红石脉冲就会投递，翻页过程中如果一路写目标，
     * 中途某个脉冲就可能把资源投到「顺手翻到」的那台机器上。选定是一次显式决定，翻页不是。</p>
     */
    private void cycleReceiver(final int delta) {
        if (receivers.isEmpty()) {
            return;
        }
        receiverIndex = Math.floorMod(receiverIndex + delta, receivers.size());
        messageKey = "";
        updateTargetButtons();
    }

    /**
     * 选定：把光标那台接收器的<b>名字</b>发给服务端（唯一提交入口）。
     * <p>提交的是名字而不是「第几台 / 坐标」：存储层不变（请求器 NBT 里就是 targetName），
     * 服务端仍然按名字在本网络里现解析 —— 找不到 / 重名都由服务端拒绝并回提示。</p>
     */
    private void submitTarget() {
        final SyncRequesterConfigPacket.ReceiverView view = selectedReceiver();
        if (view == null) {
            return;
        }
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetRequesterTargetPacket(menu.containerId, view.name()));
    }

    /** 光标所指的候选（列表为空 / 下标越界 ⇒ null）。 */
    private SyncRequesterConfigPacket.ReceiverView selectedReceiver() {
        if (receivers.isEmpty()) {
            return null;
        }
        return receivers.get(Math.min(Math.max(receiverIndex, 0), receivers.size() - 1));
    }

    /** 立即发送：目标校验在服务端做（找不到 / 未配置都会回提示）。 */
    private void trigger() {
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new TriggerRequesterPacket(menu.containerId));
    }

    /**
     * 清空订单：走既有 C2S 包 {@link RequesterOrderPacket} 的第 4 个动作。
     * <p>服务端 {@code NetworkResourceRequesterMenu#clearOrdersFor} 会重新解析「玩家现在打开的
     * 是哪台请求器」并校验归属 / 距离，因此客户端连「我清的是哪台」都不需要说，
     * 也无从伪造别人的方块实体。订单为空时按钮是灰的（见 {@link #updateButtons}）。</p>
     */
    private void requestClearOrders() {
        if (orders.isEmpty()) {
            return;
        }
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new RequesterOrderPacket(menu.containerId, RequesterOrderPacket.ACTION_CLEAR, -1,
                "", false, 0));
    }

    /**
     * 打开网格界面（订单的搜索 / 删除 / 改数量 / 发送 / 切目标全在那边）。
     * <p>未接入可用网络时网格在服务端根本打不开（{@code ResourcePickerMenu#openFor} 的判据），
     * 因此这里<b>先给一句提示</b>再决定发不发包：点了没反应是最糟的交互，而静默失败更糟 ——
     * 玩家会以为按钮坏了。</p>
     */
    private void openResourceGrid() {
        if (!networkConnected) {
            messageKey = NetworkResourceRequesterMenu.MSG_PICKER_NO_NETWORK;
            return;
        }
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new OpenRequesterPickerPacket(menu.containerId, 1));
    }

    // ==================== S2C：权威快照 ====================

    /**
     * 服务端配置快照（目标 + 已选资源 + 接收器候选列表 + 连通性 + 提示 + 最近一轮投递账）。
     * <p>只写镜像字段，不发任何包 —— 这里绝不能再触发一次请求，否则会形成回环。</p>
     */
    public void onServerSync(final SyncRequesterConfigPacket packet) {
        syncedTarget = packet.target() == null ? "" : packet.target();
        messageKey = packet.messageKey() == null ? "" : packet.messageKey();
        receivers = List.copyOf(packet.receivers());
        networkConnected = packet.networkConnected();
        orders = List.copyOf(packet.orders());
        roundPlanned = packet.roundPlanned();
        roundDelivered = packet.roundDelivered();
        roundRemaining = packet.roundRemaining();
        roundShortfall = packet.roundShortfall();
        roundActive = packet.roundActive();
        // 光标对齐权威目标（目标为空 / 已不在候选里 ⇒ 停在第一台）：翻页本身不会触发同步，
        // 所以「玩家正在挑，突然被同步拽回去」只会发生在真正发生了配置变更之后。
        receiverIndex = indexOfReceiver(syncedTarget);
        updateButtons();
        updateTargetButtons();
    }

    /** 名字 → 候选下标（没有 / 空名 ⇒ 0：光标永远停在合法位置）。 */
    private int indexOfReceiver(final String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < receivers.size(); index++) {
            if (name.equals(receivers.get(index).name())) {
                return index;
            }
        }
        return 0;
    }

    // ==================== 每帧只读渲染 ====================

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        // 整图 blit：槽框已烘焙在 PNG 里（槽框左上角 = Menu 坐标 − 1），一次画完
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 全部文字不带阴影（工程硬规则）
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 「插件」标题：只有 19px 宽（槽框左沿 187 − 起点 168），语言值已收成 "Upg"（见常量注释）
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "upgrades"),
            UPGRADES_LABEL_X, UPGRADES_LABEL_Y, UPGRADES_LABEL_MAX_W, COLOR_TITLE);
        // 以下各行都是「一句话 + 数字」的读数：第 104 轮起一律 drawFitLine（放不下整体缩字号、
        // 下限 0.6，绝不加省略号）。左列硬上限 = TEXT_MAX_W。
        // 红石提示：本方块「脉冲触发」这件事的唯一可见说明
        RsccTextFit.drawFitLine(guiGraphics, font,
            Component.translatable(LANG + "redstone.hint"), TEXT_X, REDSTONE_Y, TEXT_MAX_W, COLOR_HINT);
        // 目标接收器行：候选为空 / 目标已不在网络 / 光标待提交时给出对应提示（见 targetHint）
        RsccTextFit.drawFitLine(guiGraphics, font, targetHint(), TEXT_X, TARGET_HINT_Y, TEXT_MAX_W,
            targetHintColor());
        // 状态行：优先显示服务端最近一次提示；否则显示目标 / 未接入网络
        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), TEXT_X, STATUS_Y, TEXT_MAX_W,
            statusColor());
        // 节拍行：每 tick 批数 / 单批量（只读菜单同步值）
        RsccTextFit.drawFitLine(guiGraphics, font, pacingLine(), TEXT_X, PACING_Y, TEXT_MAX_W, COLOR_TEXT);
        // 订单摘要行：订单 N 种 / 共 M 件（订单内容本身在网格界面里编辑，本界面只给摘要）
        RsccTextFit.drawFitLine(guiGraphics, font, summaryLine(), TEXT_X, SUMMARY_Y, TEXT_MAX_W,
            orders.isEmpty() ? COLOR_HINT : COLOR_TITLE);
        // 投递进度（已投 / 剩余 / 缺口）：只有真的触发过一轮才有数字可显示，
        // 否则一行「已投 0 / 剩余 0」会让玩家以为投过了却什么都没投
        if (roundPlanned > 0L) {
            RsccTextFit.drawFitLine(guiGraphics, font, progressLine(), TEXT_X, STATS_Y, TEXT_MAX_W,
                progressColor());
        }
        guiGraphics.drawString(font, Component.translatable("container.inventory"),
            inventoryLabelX, inventoryLabelY, COLOR_TEXT, false);
    }

    /**
     * 订单摘要行：{@code 订单 N 种 / 共 M 件}（空订单 ⇒ 一句「订单：空」+ 去哪加）。
     *
     * <p><b>为什么只给摘要</b>：订单条目没有上限（服务端本来就是 {@code List}），
     * 旧界面的 4×2 = 8 格是人为限制 —— 第 9 种资源在界面上根本不存在。第 96 轮把订单内容整体
     * 搬进网格界面（可滚动 + 可搜索 + 可删 + 可改数量），这里只留一行统计与一个入口。</p>
     *
     * <p>{@code M} 是各条数量的<b>直接相加</b>（物品件 + 流体 mB），与投递进度行同一口径，
     * 只作规模参考 —— 完整口径写在 tooltip 里。</p>
     */
    private Component summaryLine() {
        if (orders.isEmpty()) {
            return Component.translatable(LANG + "order.none");
        }
        long total = 0L;
        for (final SyncRequesterConfigPacket.OrderView view : orders) {
            total += Math.max(0L, view.amount());
        }
        return Component.translatable(LANG + "order.summary", orders.size(),
            RsccNumberField.compact(total));
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
        // 空升级槽的常显 tooltip（走与范围充电器同一个 UpgradeSlotTooltips，逐像素一致）
        renderUpgradeSlotTooltips(guiGraphics, mouseX, mouseY);
        // 选择器：悬停显示「怎么用」+ 本网络候选全表（提示行只放得下一条）
        renderReceiverListTooltip(guiGraphics, mouseX, mouseY);
        renderOpenGridTooltip(guiGraphics, mouseX, mouseY);
        renderClearTooltip(guiGraphics, mouseX, mouseY);
        // 节拍行：速度 / 叠加升级各自的作用（从插件槽 tooltip 搬出来，见 renderPacingTooltip）
        renderPacingTooltip(guiGraphics, mouseX, mouseY);
        // 订单摘要 / 投递进度：悬停给出「订单在哪编辑」与三个数字的口径
        renderSummaryTooltip(guiGraphics, mouseX, mouseY);
        renderProgressTooltip(guiGraphics, mouseX, mouseY);
        // 提示行 / 状态行：第 126 轮补的 hover 兜底（缩字号后只有 hover 能看全）
        renderReadoutLineTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 悬停「目标提示行 / 状态行」→ 该行<b>完整文本</b>（第 126 轮用户反馈：自绘文本没有 tooltip）。
     *
     * <p>两行都走 {@code RsccTextFit.drawFitLine}：放不下就整体缩字号（下限 0.6），
     * 缩完玩家只看到变小的一行、看不到原文；tooltip 必须由界面自己画。这里输出的就是
     * {@link #renderLabels} 里同一对函数（{@code targetHint()} / {@code statusLine()}）的结果。</p>
     */
    private void renderReadoutLineTooltips(final GuiGraphics guiGraphics, final int mouseX,
                                           final int mouseY) {
        final boolean overHint = isOverLine(mouseX, mouseY, TARGET_HINT_Y);
        if (!overHint && !isOverLine(mouseX, mouseY, STATUS_Y)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        final Component line = overHint ? targetHint() : statusLine();
        if (line.getString().isEmpty()) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(line), java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 鼠标是否落在 {@code lineY} 那一行文字上（行高 = 字体 9px；与 {@code renderLabels} 同一组常量）。 */
    private boolean isOverLine(final double mouseX, final double mouseY, final int lineY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return y >= lineY && y < lineY + 9 && x >= TEXT_X && x < TEXT_X + TEXT_MAX_W;
    }

    /**
     * 悬停接收器选择器 → 一行用法 + <b>候选全表</b>。
     * <p><b>第 104 轮：全表不再截断</b>。旧实现只列前 8 台再补一个字面量 {@code " ..."}，
     * 用户口径是「界面文本不要省略号」⇒ 现在把<b>所有</b>候选名都拼进去（原版 tooltip 自己会折行，
     * 放得下就一行、放不下就多行，绝不丢信息、绝不加省略号）。</p>
     */
    private void renderReceiverListTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                           final int mouseY) {
        if (targetButton == null || !targetButton.isMouseOver(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        final Component list = receivers.isEmpty()
            ? Component.translatable(LANG + "target.known.none")
            : Component.translatable(LANG + "target.known", receiverNamesForTooltip());
        guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "target.hint.pick"), list),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /** tooltip 里显示的候选名字串（<b>全部</b>候选，逗号分隔；由原版 tooltip 负责折行）。 */
    private String receiverNamesForTooltip() {
        final StringBuilder builder = new StringBuilder();
        for (int index = 0; index < receivers.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(receivers.get(index).name());
        }
        return builder.toString();
    }

    /**
     * 悬停「网格」按钮 → 说明这条主入口怎么用；未接入网络时说明为什么点不了。
     * <p>与选择器 tooltip 同一帧纪律：只有真正悬停在这个按钮上才画。</p>
     */
    private void renderOpenGridTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                       final int mouseY) {
        if (openGridButton == null || !openGridButton.isMouseOver(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + (networkConnected
                    ? "order.open_grid.hint" : "order.open_grid.no_network"))),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 悬停「清空订单」按钮 → 说明它只清订单、不动目标与缓存。 */
    private void renderClearTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                    final int mouseY) {
        if (clearButton == null || !clearButton.isMouseOver(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "order.clear.hint")),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 悬停订单摘要行 → 「订单在哪编辑」+ 两个数字的口径。
     * <p>摘要行本身只放得下一句 {@code 订单 N 种 / 共 M 件}，因此「怎么改订单」
     * 与「M 是怎么加出来的」必须在这里说清，否则玩家会去点摘要文字。</p>
     */
    private void renderSummaryTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                      final int mouseY) {
        if (!isOverSummaryLine(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(
                // 第 126 轮：先把这一行自己的完整文本放进来（缩字号后它是唯一能看全的地方），
                // 再跟两句机制说明。
                summaryLine(),
                Component.translatable(LANG + "order.summary.hint"),
                Component.translatable(LANG + "order.stats.units")),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 悬停「已投 / 剩余 / 缺口」→ 说明三个数的口径。
     * <p>不同资源的数量单位不同（物品 = 件、流体 = mB），这里必须写清「数字是各资源数量相加，
     * 只作进度参考」，否则玩家会把它当成一个可以跨类型比较的量。</p>
     */
    private void renderProgressTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                       final int mouseY) {
        if (!isOverProgressLine(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(
                // 第 126 轮：同上，先放这一行的完整文本（缩字号后的兜底）
                progressLine(),
                Component.translatable(LANG + "order.stats.hint"),
                Component.translatable(LANG + "order.stats.units")),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 悬停「节拍」行 → 速度 / 叠加升级各自的作用（机制层，SHIFT 才展开）。
     *
     * <p><b>为什么把这两句从插件槽 tooltip 里搬出来</b>（第 101 轮用户反馈：插件槽 tooltip
     * 「画的是什么狗屎」）：插件槽的空槽提示必须与<b>范围充电器逐像素一致</b>，而那一套
     * （{@code UpgradeSlotTooltips}：升级物品图标 + 名称 + 单种上限 + {@code TooltipRenderUtil} 背景）
     * 是「图标行」；再往里塞纯文字行只能另起一个框 ⇒ 两个框贴边时定位点不同、文字互相压。
     * 因此插件槽只走统一那套，机制说明搬到它真正描述的那一行（节拍行 = 每 tick 几批 / 每批多少）。</p>
     */
    private void renderPacingTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                     final int mouseY) {
        if (!isOverPacingLine(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
            guiGraphics, font,
            List.of(pacingLine()),
            List.of(
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(LANG + "upgrade.speed.effect")),
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(LANG + "upgrade.stack.effect"))),
            mouseX, mouseY);
    }

    /** 鼠标是否落在「节奏：每 tick N 批，每批 M 件」那一行（tooltip 用）。 */
    private boolean isOverPacingLine(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return y >= PACING_Y && y < PACING_Y + 9 && x >= TEXT_X && x < TEXT_X + TEXT_MAX_W;
    }

    /** 鼠标是否落在「订单 N 种 / 共 M 件」那一行（tooltip 用）。 */
    private boolean isOverSummaryLine(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return y >= SUMMARY_Y && y < SUMMARY_Y + 9 && x >= TEXT_X && x < TEXT_X + TEXT_MAX_W;
    }

    /** 鼠标是否落在「已投 / 剩余 / 缺口」那一行（tooltip 用）。 */
    private boolean isOverProgressLine(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return y >= STATS_Y && y < STATS_Y + 9 && x >= TEXT_X && x < TEXT_X + TEXT_MAX_W
            && roundPlanned > 0L;
    }

    // ==================== 交互 ====================

    /** 回车（当光标与权威目标不同时）= 选定当前候选。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            final SyncRequesterConfigPacket.ReceiverView selected = selectedReceiver();
            if (selected != null && !selected.name().equals(syncedTarget)) {
                submitTarget();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    /**
     * 按钮可用性：<b>所有按钮都保持可点</b>。
     *
     * <p>第 96 轮的口径是「点了没反应必须给一句消息」（{@code docs/ROUND96_USER_FEEDBACK.md} §3）：
     * 把「未接入网络 / 订单为空」变成灰色不可点，玩家只会以为界面坏了 —— 而且灰按钮本身就
     * 是「点了没反应」。因此这里只刷新文案，不再按状态禁用；每个动作的失败原因由
     * 服务端快照（{@code messageKey}）或本界面的一句话给出。</p>
     */
    private void updateButtons() {
        if (openGridButton != null) {
            openGridButton.setMessage(openGridLabel());
        }
    }

    /**
     * 刷新选择器两个按钮的文案 / 可用性（候选为空时「选定」变灰：没有东西可提交）。
     * <p><b>只在「初始化 / 收到快照 / 翻页」时调用，不进 {@code containerTick}</b>：
     * 按钮文案要经 {@code font.width} 裁宽，没必要每 tick 重算一遍。</p>
     */
    private void updateTargetButtons() {
        if (targetButton != null) {
            targetButton.setMessage(targetLabel());
        }
        if (selectButton != null) {
            selectButton.active = !receivers.isEmpty();
        }
    }

    /**
     * 选择器按钮文案：{@code ◀ 名字}（列表为空 ⇒「请先给接收器命名」）。
     * <p><b>第 104 轮：名字不再按像素裁掉</b>。接收器名字是玩家自由命名的（最长 32 字，中英文都可能），
     * 按钮只有 124px 宽 —— 旧实现把它切成「名字…」。现在交给 {@code Button} 自己的原版渲染路径
     * {@code Widget#renderScrollingString}：超宽时整串<b>来回滚动</b>（原样显示全部字符，
     * 不省略、不丢字），悬停按钮还能在 tooltip 里看到本网络<b>全部</b>候选名（见
     * {@link #renderReceiverListTooltip}）。</p>
     */
    private Component targetLabel() {
        final SyncRequesterConfigPacket.ReceiverView selected = selectedReceiver();
        if (selected == null) {
            return Component.translatable(LANG + "target.entry.none");
        }
        return Component.translatable(LANG + "target.entry", selected.name());
    }

    /**
     * 「打开网格编辑订单」按钮文案。
     * <p>把「已选几种订单」放在入口上，玩家一眼就知道订单有几条；订单内容本身
     * （图标 + 名称 + 数量 + 搜索 + 删除 + 改数量）在网格界面里，不再画在这台小面板上。</p>
     */
    private Component openGridLabel() {
        return orders.isEmpty()
            ? Component.translatable(LANG + "order.open_grid.empty")
            : Component.translatable(LANG + "order.open_grid.n", orders.size());
    }

    // ==================== 文案 ====================

    /**
     * 状态行：有服务端提示 ⇒ 提示；未接入网络 ⇒ 明确原因；否则显示目标接收器。
     * <p>客户端只能显示服务端同步过来的值（{@code syncedTarget} + {@code receivers} + {@code networkConnected}），
     * 不读方块实体。目标已被拆除 / 改名（不在候选里）时退回只显示名字 —— 让玩家看得见「它不在了」。</p>
     */
    private Component statusLine() {
        if (!messageKey.isEmpty()) {
            return Component.translatable(messageKey);
        }
        if (!networkConnected) {
            return Component.translatable(LANG + "status.no_network");
        }
        return syncedTarget.isEmpty()
            ? Component.translatable(LANG + "status.unconfigured")
            : Component.translatable(LANG + "target.current", targetText());
    }

    /** 权威目标的显示文本：在候选里 ⇒「名字 (x, y, z)」；不在（拆了 / 改名了）⇒ 只有名字。 */
    private String targetText() {
        for (final SyncRequesterConfigPacket.ReceiverView view : receivers) {
            if (view.name().equals(syncedTarget)) {
                return entryText(view);
            }
        }
        return syncedTarget;
    }

    /** 一条候选的显示文本：{@code 名字 (x, y, z)}（坐标是数字，不需要翻译）。 */
    private static String entryText(final SyncRequesterConfigPacket.ReceiverView view) {
        return view.name() + " (" + view.x() + ", " + view.y() + ", " + view.z() + ")";
    }

    /**
     * 节拍行：「每 tick N 批、每批 M 件」。
     * <p>数值只读菜单同步值（{@code ContainerData} 索引 0 / 1，由方块实体每 tick 广播），
     * 界面绝不按升级槽自己重算 —— 客户端菜单里没有方块实体，自算必分叉。</p>
     */
    private Component pacingLine() {
        return Component.translatable(LANG + "upgrade.pacing",
            menu.getThrottleBatchesPerTick(), menu.getThrottleBatch());
    }

    /**
     * 投递进度行：{@code 已投 X · 剩余 Y · 缺口 Z}（服务端权威，客户端不做算术）。
     * <p>续投中额外标注「投递中」：那时「剩余」是<b>还在计划里</b>的量，不是失败。</p>
     */
    private Component progressLine() {
        return Component.translatable(LANG + (roundActive ? "order.stats.active" : "order.stats"),
            RsccNumberField.compact(roundDelivered),
            RsccNumberField.compact(roundRemaining),
            RsccNumberField.compact(roundShortfall));
    }

    /** 进度行颜色：有缺口 = 警示红；其余正常。 */
    private int progressColor() {
        return roundShortfall > 0L ? COLOR_WARN : COLOR_TEXT;
    }

    /** 状态行颜色：成功提示 / 已配置 = 正常；失败提示 / 未配置 / 未接入网络 = 警示红。 */
    private int statusColor() {
        if (!messageKey.isEmpty()) {
            return isSuccessMessage() ? COLOR_OK : COLOR_WARN;
        }
        return syncedTarget.isEmpty() || !networkConnected ? COLOR_WARN : COLOR_TEXT;
    }

    /** 这条提示是不是「成功」类（决定颜色；判据是一组常量，不猜字符串后缀）。 */
    private boolean isSuccessMessage() {
        return NetworkResourceRequesterMenu.MSG_TARGET_OK.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_ADDED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_REMOVED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_UPDATED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_ORDER_CLEARED.equals(messageKey)
            || NetworkResourceRequesterMenu.MSG_TRIGGER_OK.equals(messageKey);
    }

    /**
     * 目标行（选择器上方）：正常显示「目标接收器」；候选为空 / 目标已不在网络 / 光标还没提交
     * 时给出对应的即时提示（只提示、不写任何配置）。
     *
     * <p>三种提示刻意分开：<b>空列表</b>（先去给接收器命名）/ <b>目标不在了</b>（拆除或改名，
     * 必须重新选）/ <b>待选</b>（正在看另一台，还没按「选定」）。合成一句会让玩家不知道下一步该做什么。</p>
     */
    private Component targetHint() {
        if (receivers.isEmpty()) {
            return Component.translatable(LANG + "target.known.none");
        }
        if (!syncedTarget.isEmpty() && !targetStillInList()) {
            return Component.translatable(LANG + "target.hint.missing");
        }
        final SyncRequesterConfigPacket.ReceiverView selected = selectedReceiver();
        if (selected == null) {
            return Component.translatable(LANG + "target.label");
        }
        if (selected.name().equals(syncedTarget)) {
            return Component.translatable(LANG + "target.label");
        }
        return Component.translatable(LANG + "target.pending", entryText(selected));
    }

    private int targetHintColor() {
        if (receivers.isEmpty()) {
            return COLOR_WARN;
        }
        if (!syncedTarget.isEmpty() && !targetStillInList()) {
            return COLOR_WARN;
        }
        final SyncRequesterConfigPacket.ReceiverView selected = selectedReceiver();
        if (selected == null || selected.name().equals(syncedTarget)) {
            return COLOR_TEXT;
        }
        return COLOR_HINT;
    }

    /** 权威目标此刻还在候选列表里吗（拆除 / 改名 ⇒ false）。 */
    private boolean targetStillInList() {
        for (final SyncRequesterConfigPacket.ReceiverView view : receivers) {
            if (view.name().equals(syncedTarget)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 升级槽 tooltip（与范围充电器逐像素一致） ====================

    /**
     * 空升级槽的常显 tooltip：<b>直接走全工程统一实现</b>
     * {@link cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips#render}
     * （范围充电器 / 接收器都是这一个调用）。
     *
     * <h2>第 101 轮用户反馈：这里原来是「自己拼的纯文字」，跟别的机器完全不一样</h2>
     * <p>用户原话：「<i>那个网络资源发送器（请求器）的插件槽为什么还保留着？而且它的插件槽
     * tooltip 要保持一致 —— 你去翻一下范围充电器的那些插件槽 tooltip 是怎么画的，
     * 你这个画的是什么狗屎</i>」。</p>
     * <p>旧实现（本类已删除的 {@code renderUpgradeTooltipWithPacing}）把空槽提示拼成
     * {@code Component.literal("<…>")} + {@code getHoverName() + " (上限)"} 的<b>纯文字</b>，
     * 再交给 {@code RsccTooltipLayers.render} 画 —— 没有升级物品图标、没有
     * {@code TooltipRenderUtil} 背景、也没有 {@code renderImage} 画图标这一层，
     * 因此同一台机器上「插件槽」和其它机器（范围充电器 / 接收器）长得<b>完全两样</b>。</p>
     * <p>现在只保留一个调用点：{@code UpgradeSlotTooltips.render(...)}
     * —— 与 {@code RangeChargerScreen#renderValueTooltips} 逐字同一套（标题 {@code <空升级槽位>}
     * 深灰 + 每个允许的升级一行「图标 + 名称 + 单种上限」+ 原版 tooltip 背景）。
     * 「速度升级 / 叠加升级各自干什么」是机制说明，搬到<b>节拍行</b>的悬停提示
     * （见 {@link #renderPacingTooltip}），因此这里不再叠第二个框、也不会留下孤儿语言键。</p>
     *
     * <p>判定必须有 {@code menu.getCarried().isEmpty()}：手里正拿着东西时不弹空槽提示。
     * 判定用原版 {@code hoveredSlot}，因此与绘制范围严格同源。</p>
     */
    private void renderUpgradeSlotTooltips(final GuiGraphics guiGraphics,
                                           final int mouseX, final int mouseY) {
        if (hoveredSlot instanceof cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot upgradeSlot
            && hoveredSlot.getItem().isEmpty()
            && menu.getCarried().isEmpty()) {
            // 唯一画法：与范围充电器同一个实现（图标行 + 原版背景），不再自己拼文字
            cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips.render(
                upgradeSlot, guiGraphics, font, mouseX, mouseY);
        }
    }

    // ==================== 工具 ====================

}
