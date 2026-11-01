package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.support.widget.ScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu;
import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheActionPacket;
import cretae.cookiewyq.rs_create_compat.network.ReceiverCacheEntry;
import cretae.cookiewyq.rs_create_compat.network.SetReceiverNamePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;
import java.util.Set;

/**
 * 网络资源接收器界面：<b>名字 + 状态 + 节拍 + 可滚动资源列表（物品与流体混排，分页）+ 玩家背包</b>。
 *
 * <h2>第 96 轮到底改了什么（用户第 6 条）</h2>
 * <p>用户原话：「<i>内容是可以直接打开取出了，但是你那个流体该怎么办呢？而且你这个格子这么少……
 * 资源是没有上限的，你这么一点怎么装得完</i>」。缓存是种类无上限的存储，而旧界面只有
 * 16 个固定镜像格 + 4 条流体条目 ⇒ 第 17 种物品 / 第 5 种流体<b>看不见也取不出</b>。现在：</p>
 * <ul>
 *     <li><b>看得见全部种类</b>：列表由服务端分页下发（每页 {@link NetworkResourceReceiverMenu#PAGE_SIZE}
 *     条 = 2 列 × 6 行，第 96 轮复核 P5 从 8 条提到 12 条），滚轮 / 滚动条 / 页码箭头翻页，
 *     页眉显示「第 N / M 页」；每行 = 图标 + 名称 + 数量；</li>
 *     <li><b>流体与物品同一张列表</b>：流体行同样是「图标 + 名称 + 数量（mB）」，
 *     点它就按 <b>1000 mB 换 1 桶</b>（背包里必须有空桶）取出 —— 这是用户「你那个流体该怎么办」的答案；</li>
 *     <li><b>取出走 C2S 包</b>（{@link ReceiverCacheActionPacket}）：左键 = 取一组 / 换一桶，
 *     Shift + 左键 = 全部 / 尽可能多整桶；服务端校验 containerId + 距离后从真实缓存扣减，
 *     放不下的部分<b>回填缓存</b>（绝不复制；流体的「没有空桶」则<b>一点不扣</b>并提示）。</li>
 * </ul>
 *
 * <h2>为什么这个类必须待在 {@code client/} 包里</h2>
 * <p>{@link AbstractContainerScreen} 与 {@code GuiGraphics} 都是客户端专属类型；
 * 专用服务端上根本没有这些类。硬规则：<b>任何引用 {@code net.minecraft.client.*} 的类
 * 只能放在 {@code client/} 包下</b>，由 {@code ClientInit}（{@code Dist.CLIENT}）注册。</p>
 *
 * <h2>背景 = 工具生成（第 128 轮）</h2>
 * <p>背景 {@code textures/gui/network_resource_receiver.png}（236×252）由
 * {@code tools/make_gui_bg.py --slots-menu "214,7,1,6" "9,176,9,3" "9,230,9,1"} 生成
 * （{@code pic/bg.png} 九宫格 + {@code pic/slot.png} 槽框烘焙，槽框左上角 = Menu 坐标 − 1）。
 * 真正槽位（升级槽 / 背包 / 快捷栏）的槽框<b>已在图里</b>；<b>资源行不画槽位框</b>
 * （18×18 会压到 12px 行），改用悬停高亮；流体贴图用工程唯一的
 * {@link GhostMarkerRenderer#renderFluid}，滚动条用 RS 原版 {@link ScrollbarWidget}
 * （它自带 {@code widget/scrollbar} 精灵）。</p>
 *
 * <h2>名字编辑（服务端权威）</h2>
 * <p>输入框 + 「确定」→ C2S {@code SetReceiverNamePacket} → 服务端校验 → 写名字 →
 * S2C {@code SyncReceiverConfigPacket} 回发「权威名字 + 已占用名字集合 + 操作结果」。
 * 客户端只用这份快照做<b>即时提示</b>，永远不先写本地值、也永远不直接改 NBT。</p>
 */
public class NetworkResourceReceiverScreen extends AbstractContainerScreen<NetworkResourceReceiverMenu> {
    /** 本界面全部语言键的前缀（与语言文件逐字一致）。 */
    private static final String LANG = "gui.rs_create_compat.network_resource_receiver.";

    /** 背景贴图（{@link NetworkResourceReceiverMenu#PANEL_W} × {@code PANEL_H}；工具生成，槽框已烘焙）。 */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        RS_Create_Compat.MODID, "textures/gui/network_resource_receiver.png");

    /** 标题颜色：黑色（浅色面板上白字看不清，全模组机器标题统一口径）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    /** 正文颜色：深灰（与 RS 原版标签同款）。 */
    private static final int COLOR_TEXT = 0xFF404040;
    /** 次要文字（页码 / 已禁用箭头）：更浅的灰。 */
    private static final int COLOR_MUTED = 0xFF7A7A7A;
    /** 警示颜色：暗红（「没名字就不能用」是必须被看见的信息）。 */
    private static final int COLOR_WARN = 0xFF9B2020;
    /** 成功提示的颜色（暗绿，与错误红区分）。 */
    private static final int COLOR_OK = 0xFF1E6B2E;

    // ==================== 文字行（Menu 坐标） ====================
    /** 名字说明 / 即时提示行：标题之下、输入框之上。 */
    private static final int HINT_Y = 16;
    /** 输入框：左侧留白带内，右边界 129，离升级槽框（213）很远。 */
    private static final int BOX_X = 9;
    private static final int BOX_Y = 24;
    private static final int BOX_W = 120;
    private static final int BOX_H = 16;
    /** 「确定」按钮：紧跟输入框右侧。 */
    private static final int OK_X = BOX_X + BOX_W + 4;
    private static final int OK_W = 46;
    /** 状态行（改名结果 / 未命名 / 已命名）。 */
    private static final int STATUS_X = 9;
    private static final int STATUS_Y = 44;
    /** 节流行。 */
    private static final int PACING_Y = 53;
    /** 缓存摘要行（物品种类 / 件数）。 */
    private static final int SUMMARY_Y = 61;
    /** 流体摘要行（流体种类 / mB）。 */
    private static final int FLUID_LINE_Y = 70;
    /** 页眉（页码箭头）行。 */
    private static final int HEADER_Y = 79;
    /** 「取全部」按钮（Menu 坐标；右侧、与摘要行同一水平带，不压列表）。 */
    private static final int TAKE_ALL_X = 163;
    private static final int TAKE_ALL_Y = 56;
    private static final int TAKE_ALL_W = 46;
    private static final int TAKE_ALL_H = 16;

    /** 左侧文字的可用宽度（升级槽框左沿 = 213，再留 2px 间隔）。 */
    private static final int TEXT_MAX_W = 213 - STATUS_X - 2;
    /** 摘要行可用宽度（右侧「取全部」按钮从 {@link #TAKE_ALL_X} 起）。 */
    private static final int SUMMARY_MAX_W = TAKE_ALL_X - STATUS_X - 2;
    /** 「插件」栏标题：与右侧升级槽同列，右边界正好顶到槽框左沿（213）。 */
    private static final int UPGRADES_LABEL_X = 194;
    private static final int UPGRADES_LABEL_Y = 6;
    /** 该标题的可用宽度 = 槽框左沿 213 − 起点 194 = 19px（见 renderLabels 里的说明）。 */
    private static final int UPGRADES_LABEL_MAX_W = 213 - UPGRADES_LABEL_X;

    // ==================== 页码箭头（行内固定槽位，命中判定与绘制严格同源） ====================
    private static final int ARROW_PREV_X = 9;
    private static final int ARROW_NEXT_X = 60;
    private static final int ARROW_Y = HEADER_Y;
    private static final int ARROW_W = 10;
    private static final int ARROW_H = 9;
    /** 页码文本（「N/M」）的绘制原点。 */
    private static final int PAGE_TEXT_X = 22;

    /** 改名输入框（{@code init()} 里创建；重开界面时会重建）。 */
    private EditBox nameBox;
    /** 「取全部」按钮（缓存里的物品全部塞背包）。 */
    private Button takeAllButton;
    /** 资源列表滚动条（RS 原版控件；拖动 / 点击 → 请求翻页）。 */
    private ScrollbarWidget scrollbar;

    // ==================== 服务端权威只读镜像（全部来自 S2C 快照） ====================
    /** 服务端权威的当前名字。 */
    private String syncedName = "";
    /** 服务端下发的「本网络已占用名字集合」（只用于即时提示，最终判据仍在服务端）。 */
    private Set<String> occupiedNames = Set.of();
    /** 服务端下发的最近一次操作提示（语言键；空串 = 无提示）。 */
    private String messageKey = "";
    /** 缓存摘要：物品种类 / 总件数 / 流体种类 / 流体总量（mB）。 */
    private int itemTypes;
    private long itemTotal;
    private int fluidTypes;
    private int fluidTotal;
    /** 当前页的行（下标 = 服务端下发顺序；点击时把<b>整行身份</b>回传，不靠下标猜）。 */
    private List<ReceiverCacheEntry> entries = List.of();
    /** 当前页码（0 基）与总页数：**只读快照**，客户端不自己算。 */
    private int page;
    private int pageCount = 1;
    /** 聚合后的资源种类总数。 */
    private int totalTypes;
    /** 输出面（服务端从 blockstate 读；客户端只用它显示）。 */
    private Direction facing = Direction.NORTH;

    public NetworkResourceReceiverScreen(final NetworkResourceReceiverMenu menu,
                                         final Inventory inventory,
                                         final Component title) {
        super(menu, inventory, title);
        this.imageWidth = NetworkResourceReceiverMenu.PANEL_W;
        this.imageHeight = NetworkResourceReceiverMenu.PANEL_H;
        this.titleLabelX = 9;
        this.titleLabelY = 6;
        // 玩家背包标签：按 Menu 的背包 y=176 上推 10px（与其它机器同一间距）
        this.inventoryLabelY = 166;
    }

    @Override
    protected void init() {
        super.init();
        // 改名输入框：初值 = 服务端上次同步的名字（客户端菜单里没有方块实体，读不到本地值）
        nameBox = new EditBox(font, leftPos + BOX_X, topPos + BOX_Y, BOX_W, BOX_H,
            Component.translatable(LANG + "name.label"));
        nameBox.setMaxLength(NetworkResourceReceiverMenu.MAX_NAME_LENGTH);
        nameBox.setValue(syncedName);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable(LANG + "name.button"),
                button -> submitName())
            .bounds(leftPos + OK_X, topPos + BOX_Y, OK_W, BOX_H)
            .build());
        // 「取全部」：把缓存里的物品全部塞背包（仍走原版菜单按钮协议：服务端自带 containerId / 距离校验）
        takeAllButton = Button.builder(Component.translatable(LANG + "cache.take_all"),
                button -> sendMenuButton(NetworkResourceReceiverMenu.BUTTON_TAKE_ALL_ITEMS))
            .bounds(leftPos + TAKE_ALL_X, topPos + TAKE_ALL_Y, TAKE_ALL_W, TAKE_ALL_H)
            .build();
        addRenderableWidget(takeAllButton);
        // 资源列表滚动条：偏移 = 页码（拖动 / 点击都经 listener 变成一次翻页请求）
        scrollbar = new ScrollbarWidget(
            leftPos + NetworkResourceReceiverMenu.SCROLLBAR_X,
            topPos + NetworkResourceReceiverMenu.SCROLLBAR_Y,
            ScrollbarWidget.Type.NORMAL,
            NetworkResourceReceiverMenu.SCROLLBAR_H);
        scrollbar.setListener(offset -> requestPage((int) Math.round(offset)));
        refreshScrollbar();
        addWidget(scrollbar);
    }

    // ==================== 交互（全部只发「请求」，不写本地配置 / 不读方块实体） ====================

    /**
     * 提交一次改名（唯一出口）：只把「玩家想改成的名字」发给服务端，等 S2C 快照回来再更新显示。
     * <p>客户端<b>不</b>先写本地名字：否则被服务端拒绝时界面会先闪成新名字再跳回旧名字。</p>
     */
    private void submitName() {
        if (nameBox == null) {
            return;
        }
        messageKey = ""; // 清掉上一条提示，避免旧结果与新操作混在一起
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetReceiverNamePacket(menu.containerId, nameBox.getValue()));
    }

    /** 发一个原版菜单按钮（「取全部」）。 */
    private void sendMenuButton(final int buttonId) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    /**
     * 请求翻到某一页（唯一出口）：发 {@link ReceiverCacheActionPacket#ACTION_PAGE}。
     * <p><b>为什么不在客户端自己翻</b>：页码是服务端菜单状态，行内容也由服务端下发；
     * 客户端自己翻会与服务端页码分叉（而且下一帧就会被下一份快照推翻，表现为「翻页闪一下又跳回」）。</p>
     */
    private void requestPage(final int requested) {
        final int clamped = Math.max(0, Math.min(pageCount - 1, requested));
        if (clamped == page) {
            return; // 已经在目标页：不重复发包（滚轮滚到边界时就是这里短路）
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            ReceiverCacheActionPacket.page(menu.containerId, clamped));
    }

    /** 请求取出一行（物品 = 一组 / 全部；流体 = 一桶 / 尽可能多整桶）。 */
    private void requestTake(final ReceiverCacheEntry entry, final boolean all) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(entry.isFluid()
            ? ReceiverCacheActionPacket.takeFluid(menu.containerId, entry.fluid(), all)
            : ReceiverCacheActionPacket.takeItem(menu.containerId, entry.item(), all));
    }

    /** 回车 = 点「确定」（输入法与玩家习惯一致；不拦其他键，Esc 仍然关界面）。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (nameBox != null && nameBox.isFocused() && (keyCode == 257 || keyCode == 335)) {
            submitName();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 鼠标点击：资源行 ⇒ 请求取出；页码箭头 ⇒ 请求翻页；其它区域走原版。
     * <p>资源行<b>没有</b>对应的原版槽位（一行 = 一种资源，可能横跨多个缓存格；
     * 流体更是没有 ItemStack），因此这里用显式的矩形命中，命中的<b>整行身份</b>回传服务端，
     * 服务端再按身份去真实缓存里定位 —— 绝不会因为「这一页刚好刷新了」而取错资源。</p>
     */
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 0) {
            final int entryIndex = entryIndexAt(mouseX, mouseY);
            if (entryIndex >= 0 && entryIndex < entries.size()) {
                // 用户口径：左键 = 主操作（取一组 / 换一桶），Shift = 全部
                requestTake(entries.get(entryIndex), hasShiftDown());
                return true;
            }
            final int arrow = pageArrowAt(mouseX, mouseY);
            if (arrow != 0) {
                requestPage(page + arrow);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滚轮：在资源列表上 = 翻页；其它地方交给原版（不拦截）。 */
    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (isOverList(mouseX, mouseY)) {
            if (scrollY != 0.0) {
                requestPage(page - (int) Math.signum(scrollY));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ==================== 命中判定（与绘制严格同源） ====================

    /** 鼠标落在哪一行（返回<b>当前页内</b>的下标；不在列表上 ⇒ -1）。 */
    private int entryIndexAt(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        for (int row = 0; row < NetworkResourceReceiverMenu.PAGE_ROWS; row++) {
            for (int col = 0; col < NetworkResourceReceiverMenu.PAGE_COLS; col++) {
                final int cellX = NetworkResourceReceiverMenu.LIST_X0
                    + col * (NetworkResourceReceiverMenu.COL_W + NetworkResourceReceiverMenu.COL_GAP);
                final int cellY = NetworkResourceReceiverMenu.LIST_Y0
                    + row * NetworkResourceReceiverMenu.ROW_H;
                if (x >= cellX && x < cellX + NetworkResourceReceiverMenu.COL_W
                    && y >= cellY && y < cellY + NetworkResourceReceiverMenu.ROW_H) {
                    // 下标口径 = 行优先：条目 i 的列 = i % COLS、行 = i / COLS（与绘制逐字一致）
                    final int index = row * NetworkResourceReceiverMenu.PAGE_COLS + col;
                    return index < entries.size() ? index : -1;
                }
            }
        }
        return -1;
    }

    /** 鼠标是否落在列表区域（滚轮翻页用）。 */
    private boolean isOverList(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return x >= NetworkResourceReceiverMenu.LIST_X0 - 1
            && x < NetworkResourceReceiverMenu.LIST_X0 + NetworkResourceReceiverMenu.PAGE_COLS
            * (NetworkResourceReceiverMenu.COL_W + NetworkResourceReceiverMenu.COL_GAP)
            && y >= NetworkResourceReceiverMenu.LIST_Y0 - 1
            && y < NetworkResourceReceiverMenu.LIST_Y0 + NetworkResourceReceiverMenu.PAGE_ROWS
            * NetworkResourceReceiverMenu.ROW_H;
    }

    /** 页码箭头命中：-1 = 上一页、+1 = 下一页、0 = 没命中。 */
    private int pageArrowAt(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        if (y < ARROW_Y || y >= ARROW_Y + ARROW_H) {
            return 0;
        }
        if (x >= ARROW_PREV_X && x < ARROW_PREV_X + ARROW_W) {
            return -1;
        }
        if (x >= ARROW_NEXT_X && x < ARROW_NEXT_X + ARROW_W) {
            return 1;
        }
        return 0;
    }

    // ==================== S2C 快照 ====================

    /**
     * S2C：服务端权威快照（名字 + 已占用名字集合 + 操作提示 + 缓存摘要 / 当前页列表 / 页码 / 输出面）。
     * <p>只在「打开界面 / 改名 / 取出 / 翻页 / 缓存摘要变化（每 10 tick 至多一次）」时到达；
     * 界面每帧只读这些字段，绝不在渲染循环里拼数据、也绝不读方块实体。</p>
     */
    public void onServerSync(final SyncReceiverConfigPacket packet) {
        syncedName = packet.name() == null ? "" : packet.name();
        occupiedNames = Set.copyOf(packet.occupiedNames());
        messageKey = packet.messageKey() == null ? "" : packet.messageKey();
        itemTypes = packet.itemTypes();
        itemTotal = packet.itemTotal();
        fluidTypes = packet.fluidTypes();
        fluidTotal = packet.fluidTotal();
        entries = List.copyOf(packet.entries());
        page = Math.max(0, packet.page());
        pageCount = Math.max(1, packet.pageCount());
        totalTypes = packet.totalTypes();
        facing = packet.facing() == null ? Direction.NORTH : packet.facing();
        refreshScrollbar();
        if (nameBox != null && !nameBox.isFocused()) {
            // 正在输入时不覆盖玩家自己的文本（否则服务端每次同步都会打断输入）
            nameBox.setValue(syncedName);
        }
    }

    /**
     * 把滚动条同步成「服务端权威页码」。
     * <p>顺序很重要：先<b>写本地 page</b>（{@link #page} 已在 {@link #onServerSync} 里更新），
     * 再 {@code setOffset(...)} —— 它的 listener 会因为「已经在这一页」而不再发包，
     * 于是不会出现「每份快照都回一个翻页包」的 ping-pong。</p>
     */
    private void refreshScrollbar() {
        if (scrollbar == null) {
            return;
        }
        // 只在「服务端说种类真的超过一页」时才启用滚动条：两个数都来自快照，
        // totalTypes 是服务端聚合后的权威种类数（pageCount 是由它推出来的），
        // 因此这里不会出现「客户端自己算页数」的第二套口径。
        scrollbar.setEnabled(pageCount > 1
            && totalTypes > NetworkResourceReceiverMenu.PAGE_SIZE);
        scrollbar.setMaxOffset(Math.max(0, pageCount - 1));
        scrollbar.setOffset(page);
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        // 整图 blit：槽框已烘焙在 PNG 里（槽框左上角 = Menu 坐标 − 1），一次画完
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 全部文字不带阴影（工程硬规则：全模组 drawString 一律传 false）
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 「插件」栏标题：只有 19px（槽框左沿 213 − 起点 194），英文原串 "Upgrades" 放不下 ⇒ 语言值
        // 已收成 "Upg"（完整含义在空升级槽的 tooltip 里）。绘制走 drawFitLine：完整显示、绝不省略。
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "upgrades"),
            UPGRADES_LABEL_X, UPGRADES_LABEL_Y, UPGRADES_LABEL_MAX_W, COLOR_TITLE);
        // 以下 5 行都是「一句话 + 数字」的读数：第 104 轮起一律 drawFitLine（放不下整体缩字号，
        // 绝不成省略号）。行宽 TEXT_MAX_W / SUMMARY_MAX_W 是这几行的硬上限。
        RsccTextFit.drawFitLine(guiGraphics, font, nameHint(), STATUS_X, HINT_Y, TEXT_MAX_W,
            nameHintColor());
        // 状态行：优先显示服务端最近一次操作提示（改名 / 取缓存的结果）
        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), STATUS_X, STATUS_Y, TEXT_MAX_W,
            statusColor());
        // 节流行：数值**只读**菜单的同步值（客户端没有方块实体，自算必然分叉）
        RsccTextFit.drawFitLine(guiGraphics, font, throttleLine(), STATUS_X, PACING_Y, TEXT_MAX_W,
            COLOR_TEXT);
        // 缓存摘要（物品 / 流体各一行：挤一行会溢出摘要行可用宽度，而这两个数字玩家最想看）
        RsccTextFit.drawFitLine(guiGraphics, font, cacheSummary(), STATUS_X, SUMMARY_Y, SUMMARY_MAX_W,
            COLOR_TEXT);
        RsccTextFit.drawFitLine(guiGraphics, font, fluidSummary(), STATUS_X, FLUID_LINE_Y,
            SUMMARY_MAX_W, fluidTotal > 0 ? COLOR_TITLE : COLOR_TEXT);
        // 页眉：页码箭头 + 「N/M」
        renderPageHeader(guiGraphics);
        // 资源列表（图标 + 名称 + 数量）
        renderEntries(guiGraphics, mouseX, mouseY);
        guiGraphics.drawString(font, Component.translatable("container.inventory"),
            inventoryLabelX, inventoryLabelY, COLOR_TEXT, false);
    }

    /** 页眉：「<  1/2  >」（箭头可点；只有一页时画成灰色，`滚轮也能翻`在 tooltip 里说明）。 */
    private void renderPageHeader(final GuiGraphics guiGraphics) {
        final boolean many = pageCount > 1;
        final boolean canPrev = page > 0;
        final boolean canNext = page < pageCount - 1;
        guiGraphics.drawString(font, "<", ARROW_PREV_X, ARROW_Y,
            canPrev ? COLOR_TITLE : COLOR_MUTED, false);
        guiGraphics.drawString(font, (page + 1) + "/" + pageCount, PAGE_TEXT_X, ARROW_Y,
            many ? COLOR_TITLE : COLOR_MUTED, false);
        guiGraphics.drawString(font, ">", ARROW_NEXT_X, ARROW_Y,
            canNext ? COLOR_TITLE : COLOR_MUTED, false);
    }

    /**
     * 资源列表：每行 = 图标（物品 / 流体贴图）+ 名称 + 右对齐数量。
     *
     * <p><b>第 96 轮复核 P5：每页 8 → 12 条</b>。行高从 18px 压到
     * {@code NetworkResourceReceiverMenu#ROW_H}（12px），图标按
     * {@code ICON_SCALE}（0.75）缩到 {@code ICON_SIZE}（12px）—— 「9px 字体 + 12px 图标」
     * 是 12px 行高的最小可读档。列数保持 2（3 列会把名称压到 1~2 个字）；
     * 因此条目数只能从行数来：2 × 6 = 12 条/页。</p>
     *
     * <p><b>绘制与命中严格同源</b>：行/列/行高的像素位置只有
     * {@code NetworkResourceReceiverMenu} 里那组常量，{@link #entryIndexAt} 用的是同一组。</p>
     *
     * <p><b>为什么不画 18px 槽位框</b>：{@code McGui.slotFrame} 是 18×18，画在 12px 行上会压到
     * 下一行。这里改成「悬停时给图标区加一层高亮」（与原生槽位高亮同一观感），
     * 既省了 6px 行高，也不会让相邻行互相压。</p>
     */
    private void renderEntries(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int hovered = entryIndexAt(mouseX, mouseY);
        for (int i = 0; i < entries.size(); i++) {
            final ReceiverCacheEntry entry = entries.get(i);
            if (entry.isEmpty()) {
                continue;
            }
            final int col = i % NetworkResourceReceiverMenu.PAGE_COLS;
            final int row = i / NetworkResourceReceiverMenu.PAGE_COLS;
            if (row >= NetworkResourceReceiverMenu.PAGE_ROWS) {
                break;
            }
            final int x = NetworkResourceReceiverMenu.LIST_X0
                + col * (NetworkResourceReceiverMenu.COL_W + NetworkResourceReceiverMenu.COL_GAP);
            final int y = NetworkResourceReceiverMenu.LIST_Y0 + row * NetworkResourceReceiverMenu.ROW_H;
            // 悬停高亮：只盖图标那 12×12（与原版槽位高亮同一套半透明白）
            if (i == hovered) {
                guiGraphics.fill(x, y, x + NetworkResourceReceiverMenu.ICON_SIZE,
                    y + NetworkResourceReceiverMenu.ICON_SIZE, 0x80FFFFFF);
            }
            renderEntryIcon(guiGraphics, entry, x, y);
            // 数量贴右边界：先量宽再决定名称能占多少（硬规则：不许写死宽度硬塞长字符串）
            final String amount = amountText(entry);
            final int amountX = x + NetworkResourceReceiverMenu.COL_W - font.width(amount);
            final int nameMax = Math.max(8,
                amountX - (x + NetworkResourceReceiverMenu.NAME_X) - 3);
            final Component name = entry.hoverName();
            // 名称是自由文本（第三方模组物品名），列宽固定 91px ⇒ 放不下时整体缩字号（下限 0.6），
            // **绝不加省略号**；完整原名在行悬停提示里（renderEntryTooltip），那是唯一能看到全名的地方。
            RsccTextFit.drawFitLine(guiGraphics, font,
                name == null ? Component.empty() : name,
                x + NetworkResourceReceiverMenu.NAME_X, y + NetworkResourceReceiverMenu.TEXT_DY,
                nameMax, COLOR_TEXT);
            guiGraphics.drawString(font, amount, amountX,
                y + NetworkResourceReceiverMenu.TEXT_DY, COLOR_TITLE, false);
        }
    }

    /**
     * 行内图标（物品 / 流体）：按 {@code ICON_SIZE / 16} 缩放整块 pose 再画 16px 图形，
     * 因此<b>不需要新贴图</b>，也不会因为缩小而改动物品渲染器的其它语义。
     * <p>物品走 {@code renderItem}、流体走工程唯一的 {@link GhostMarkerRenderer#renderFluid}
     * （它按 {@code pose().last()} 的矩阵画四边形，所以同样被缩放）。</p>
     */
    private void renderEntryIcon(final GuiGraphics guiGraphics, final ReceiverCacheEntry entry,
                                 final int x, final int y) {
        final com.mojang.blaze3d.vertex.PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale(NetworkResourceReceiverMenu.ICON_SCALE,
            NetworkResourceReceiverMenu.ICON_SCALE, 1.0F);
        if (entry.isFluid()) {
            GhostMarkerRenderer.renderFluid(guiGraphics, 0, 0, entry.fluid());
        } else {
            guiGraphics.renderItem(entry.item(), 0, 0);
        }
        pose.popPose();
    }

    /** 数量文本：物品 = 件数；流体 = mB（超过 1 桶时同时给出桶数更直观，但先保证宽度）。 */
    private String amountText(final ReceiverCacheEntry entry) {
        if (!entry.isFluid()) {
            return RsccNumberField.compact(entry.amount());
        }
        return RsccNumberField.compact(entry.amount()) + "mB";
    }

    /**
     * 节流行：「每 tick N 批、每批 M 件」。
     * <p>速度升级 = 每 tick 几批、叠加升级 = 每批多少（用户明确定下的语义）；
     * 数值来自方块实体经 {@code ContainerData} 广播的权威值 —— 界面绝不自己按槽位重算。</p>
     */
    private Component throttleLine() {
        return Component.translatable(LANG + "throttle",
            menu.getThrottleBatchesPerTick(), menu.getThrottleBatch());
    }

    /** 缓存摘要行（物品）：「缓存：N 种物品 / M 件」。 */
    private Component cacheSummary() {
        if (itemTypes <= 0) {
            return Component.translatable(LANG + "cache.empty");
        }
        return Component.translatable(LANG + "cache.summary", itemTypes, itemTotal);
    }

    /** 流体摘要行：「流体（K 种 / L mB）」，没有时写「流体：没有」。 */
    private Component fluidSummary() {
        if (fluidTypes <= 0 && fluidTotal <= 0) {
            return Component.translatable(LANG + "cache.fluid.none");
        }
        return Component.translatable(LANG + "cache.fluid", fluidTypes, fluidTotal);
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 滚动条不是 renderLabels 里的文字：它必须在整屏坐标里画一次（与 RS 原版界面同一写法）
        if (scrollbar != null) {
            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        // 槽位（升级物品 / 玩家背包）的 tooltip：AbstractContainerScreen 不自动渲染，必须手动调用
        renderTooltip(guiGraphics, mouseX, mouseY);
        // 空升级槽：走与范围充电器同一个 UpgradeSlotTooltips（图标行 + 原版背景，逐像素一致）
        renderUpgradeSlotTooltips(guiGraphics, mouseX, mouseY);
        // 节流行：速度 / 叠加 / 一组一组过 三句机制说明（从插件槽 tooltip 搬出来）
        renderThrottleTooltip(guiGraphics, mouseX, mouseY);
        // 「本网络已占用的名字」：悬停输入框时给出完整集合
        renderOccupiedNamesTooltip(guiGraphics, mouseX, mouseY);
        // 资源行 / 页码箭头 / 「取全部」的操作说明
        renderEntryTooltip(guiGraphics, mouseX, mouseY);
        renderPageTooltip(guiGraphics, mouseX, mouseY);
        renderTakeAllTooltip(guiGraphics, mouseX, mouseY);
        // 读数行（名称提示 / 状态 / 缓存摘要 / 流体摘要）：第 126 轮补的 hover 兜底
        renderReadoutLineTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 悬停读数行 → 该行<b>完整文本</b>（第 126 轮用户反馈：「带长文本的话，如果不手动进行渲染，
     * 那么它的 tooltip 是没有的」）。
     *
     * <p>上面那几行都走 {@code RsccTextFit.drawFitLine}：放不下就整体缩字号（下限 0.6），
     * 缩完玩家只能看到变小的一行、看不到原文。Minecraft 的 tooltip 不会自己出现 —— 必须手动画。
     * 这里输出的就是 {@link #renderLabels} 里同一批函数的结果（{@code nameHint()} /
     * {@code statusLine()} / {@code cacheSummary()} / {@code fluidSummary()}），
     * 不是另写一份文案，所以永远与屏幕上那行一致。</p>
     *
     * <p>矩形与绘制同源（{@code STATUS_X} + 各行 {@code *_Y}，行高 = 字体 9px），
     * 并与其它 tooltip 保持同一帧纪律：只在真的悬停在行上、且原版槽位 tooltip 不会画时画。</p>
     */
    private void renderReadoutLineTooltips(final GuiGraphics guiGraphics, final int mouseX,
                                           final int mouseY) {
        final Component line;
        if (isOverReadoutLine(mouseX, mouseY, HINT_Y, TEXT_MAX_W)) {
            line = nameHint();
        } else if (isOverReadoutLine(mouseX, mouseY, STATUS_Y, TEXT_MAX_W)) {
            line = statusLine();
        } else if (isOverReadoutLine(mouseX, mouseY, SUMMARY_Y, SUMMARY_MAX_W)) {
            line = cacheSummary();
        } else if (isOverReadoutLine(mouseX, mouseY, FLUID_LINE_Y, SUMMARY_MAX_W)) {
            line = fluidSummary();
        } else {
            return;
        }
        if (line.getString().isEmpty()) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(line), java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 鼠标是否落在一行读数上（行高 = 字体 9px；与 {@code renderLabels} 用同一组常量）。 */
    private boolean isOverReadoutLine(final double mouseX, final double mouseY,
                                      final int lineY, final int lineWidth) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return y >= lineY && y < lineY + 9 && x >= STATUS_X && x < STATUS_X + lineWidth;
    }

    /**
     * 悬停资源行 → 完整名称 + 数量 + 操作说明。
     * <p>名称是自由文本（第三方模组物品名），列宽只有 91px：行里走 {@code RsccTextFit.drawFitLine}
     * （放不下整体缩字号、下限 0.6，<b>不省略</b>），缩到下限仍放不下时 tooltip 是<b>唯一</b>能看到
     * 完整名称的地方 —— 工程硬规则「放不下的完整文本必须能用 tooltip 看到」。</p>
     * <p>与其它 tooltip 同一帧纪律：只有真的悬停在行上、且原版槽位 tooltip 不会画时才画
     * （判据复用工程唯一实现 {@code RsccTooltipLayers.vanillaSlotTooltipWillDraw}）。</p>
     */
    private void renderEntryTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                    final int mouseY) {
        final int index = entryIndexAt(mouseX, mouseY);
        if (index < 0 || index >= entries.size()) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        final ReceiverCacheEntry entry = entries.get(index);
        final Component name = entry.hoverName();
        if (name == null) {
            return;
        }
        final List<Component> lines = new java.util.ArrayList<>(3);
        lines.add(name.copy()
            .append(" ")
            .append(entry.isFluid()
                ? RsccNumberField.grouped(entry.amount()) + " mB"
                : RsccNumberField.grouped(entry.amount()) + " 件"));
        lines.add(Component.translatable(LANG + (entry.isFluid()
            ? "cache.fluid.hint" : "cache.slot.hint")));
        if (pageCount > 1) {
            lines.add(Component.translatable(LANG + "cache.fluid.page"));
        }
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 悬停页码区域 → 说明「滚轮 / 点箭头均可翻页」。 */
    private void renderPageTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                   final int mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        if (y < ARROW_Y || y >= ARROW_Y + ARROW_H || x < ARROW_PREV_X || x > ARROW_NEXT_X + ARROW_W) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font,
            List.of(Component.translatable(LANG + "cache.fluid.page")),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /** 悬停「取全部」按钮 → 说明这个按钮把缓存物品全部塞进背包（背包满时剩余留在缓存）。 */
    private void renderTakeAllTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                      final int mouseY) {
        if (takeAllButton == null || !takeAllButton.isMouseOver(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "cache.take_all.hint")),
            java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 悬停改名输入框 → 显示本网络已占用的名字集合。
     *
     * <p><b>为什么必须挡原版那一份</b>：本界面在 {@link #render} 里已经无条件调了一次
     * {@code renderTooltip}（槽位物品）；如果鼠标同时压在一个有物品的槽位上，两份 tooltip 的
     * 行数不同 ⇒ 定位点不同 ⇒ 文字互相压。判据复用工程唯一实现
     * {@code RsccTooltipLayers.vanillaSlotTooltipWillDraw}（与 {@code hoveredSlot} 同源）。</p>
     */
    private void renderOccupiedNamesTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                            final int mouseY) {
        if (nameBox == null || !nameBox.isMouseOver(mouseX, mouseY)) {
            return;
        }
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        final Component line = occupiedNames.isEmpty()
            ? Component.translatable(LANG + "name.occupied.none")
            : Component.translatable(LANG + "name.occupied", String.join(", ", occupiedNames));
        guiGraphics.renderTooltip(font, List.of(line), java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 空升级槽的常显 tooltip：<b>直接走全工程统一实现</b> {@link UpgradeSlotTooltips#render}
     * （与范围充电器 / 请求器同一个调用点）。
     *
     * <h2>第 101 轮用户反馈：这里原来是「自己拼的纯文字」</h2>
     * <p>用户抱怨请求器的插件槽 tooltip「画的是什么狗屎」、要求「保持与范围充电器一致」。
     * 本类旧实现与请求器同名方法一样，把空槽提示拼成 {@code Component.literal("<…>")} +
     * {@code getHoverName() + " (上限)"} 的纯文字（没有升级物品图标、没有
     * {@code TooltipRenderUtil} 背景）—— 于是<b>本模组三台带插件槽的机器长得三样</b>。
     * 现在统一成 {@link UpgradeSlotTooltips#render}：标题 {@code <空升级槽位>} + 每个允许的升级
     * 一行「图标 + 名称 + 单种上限」+ 原版背景，与 {@code RangeChargerScreen} 逐像素同一套。</p>
     * <p>「速度 / 叠加 / 一组一组过」这三句机制说明搬到<b>节流行</b>的悬停提示
     * （见 {@link #renderThrottleTooltip}）：插件槽里只画统一那套，不再叠第二个框。</p>
     *
     * <p>判定必须有 {@code menu.getCarried().isEmpty()}：手里正拿着东西时不弹空槽提示。
     * 判定用原版 {@code hoveredSlot}，因此与绘制范围严格同源。</p>
     */
    private void renderUpgradeSlotTooltips(final GuiGraphics guiGraphics,
                                           final int mouseX, final int mouseY) {
        if (hoveredSlot instanceof UpgradeSlot upgradeSlot
            && hoveredSlot.getItem().isEmpty()
            && menu.getCarried().isEmpty()) {
            // 唯一画法：与范围充电器同一个实现（图标行 + 原版背景），不再自己拼文字
            UpgradeSlotTooltips.render(upgradeSlot, guiGraphics, font, mouseX, mouseY);
        }
    }

    /**
     * 悬停「节流」行 → 速度 / 叠加升级各自的作用（机制层，SHIFT 才展开）。
     * <p>三句都是从插件槽 tooltip 搬过来的（见 {@link #renderUpgradeSlotTooltips}）：
     * 它们描述的是<b>这一行数字</b>（每 tick 几批 / 每批多少），放在这里才指得着。
     * 与其它自绘提示同一帧纪律：命中判定不与 {@code hoveredSlot} 同源 ⇒ 先问
     * {@code vanillaSlotTooltipWillDraw}，避免同一帧两个框。</p>
     */
    private void renderThrottleTooltip(final GuiGraphics guiGraphics, final int mouseX,
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
            List.of(throttleLine()),
            List.of(
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(LANG + "upgrade.speed.effect")),
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(LANG + "upgrade.stack.effect")),
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(LANG + "upgrade.one_by_one"))),
            mouseX, mouseY);
    }

    /** 鼠标是否落在「节流」行（tooltip 用；与 {@code renderLabels} 的绘制原点同源）。 */
    private boolean isOverPacingLine(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return y >= PACING_Y && y < PACING_Y + 9 && x >= STATUS_X && x < STATUS_X + TEXT_MAX_W;
    }

    /**
     * 状态行：有服务端提示 ⇒ 提示（改名 / 取缓存的结果）；
     * 否则未命名 ⇒ 「未命名 = 不可用」；已命名 ⇒ 「已命名；缓存输出到朝向（X）的容器」。
     * <p>数据来源是服务端下发的 {@link #syncedName} 与 {@link #facing}（客户端菜单的
     * {@code receiver} 恒为 null，绝不能本读方块实体）。</p>
     */
    private Component statusLine() {
        if (!messageKey.isEmpty()) {
            return Component.translatable(messageKey);
        }
        return syncedName.isEmpty()
            ? Component.translatable(LANG + "status.unnamed")
            : Component.translatable(LANG + "status.named.facing", facing.getName());
    }

    /** 状态行颜色：未命名 / 服务端拒绝 = 警示红；成功提示 = 暗绿；已命名 = 正文灰。 */
    private int statusColor() {
        if (!messageKey.isEmpty()) {
            return isSuccessMessage() ? COLOR_OK : COLOR_WARN;
        }
        return syncedName.isEmpty() ? COLOR_WARN : COLOR_TEXT;
    }

    /** 这条提示是不是「成功」类（判据是一组常量，不猜字符串后缀）。 */
    private boolean isSuccessMessage() {
        return NetworkResourceReceiverMenu.MSG_NAME_APPLIED.equals(messageKey)
            || NetworkResourceReceiverMenu.MSG_CACHE_TAKEN_ALL.equals(messageKey)
            || NetworkResourceReceiverMenu.MSG_FLUID_TAKEN.equals(messageKey);
    }

    /**
     * 名字行（输入框上方）：正常显示说明；输入为空 / 超长 / 含非法字符 / 与占用集合冲突时变成即时提示。
     *
     * <p><b>形状判据与服务端同一段代码</b>：{@link NetworkResourceReceiverMenu#checkNameShape(String)}，
     * 因此不会出现「客户端说可以、服务端说不行」。这里只做提示，<b>不做</b>任何写入。</p>
     */
    private Component nameHint() {
        final String text = nameBox == null ? "" : nameBox.getValue().trim();
        final NetworkResourceReceiverMenu.NameApplyResult shape =
            NetworkResourceReceiverMenu.checkNameShape(text);
        if (shape != NetworkResourceReceiverMenu.NameApplyResult.OK) {
            return switch (shape) {
                case EMPTY -> Component.translatable(LANG + "name.hint.empty");
                case TOO_LONG -> Component.translatable(LANG + "name.hint.too_long",
                    NetworkResourceReceiverMenu.MAX_NAME_LENGTH);
                default -> Component.translatable(LANG + "name.hint.invalid");
            };
        }
        if (occupiedNames.contains(text)) {
            return Component.translatable(LANG + "name.hint.taken");
        }
        return Component.translatable(LANG + "name.label");
    }

    /** 名字行颜色：形状非法 / 重名 = 警示红；其余 = 正文灰。 */
    private int nameHintColor() {
        final String text = nameBox == null ? "" : nameBox.getValue().trim();
        if (NetworkResourceReceiverMenu.checkNameShape(text)
            != NetworkResourceReceiverMenu.NameApplyResult.OK) {
            return COLOR_WARN;
        }
        return occupiedNames.contains(text) ? COLOR_WARN : COLOR_TEXT;
    }
}
