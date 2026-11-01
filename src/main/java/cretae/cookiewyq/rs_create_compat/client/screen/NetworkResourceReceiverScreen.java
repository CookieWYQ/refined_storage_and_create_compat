package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.support.widget.ScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 网络资源接收器界面：<b>名字输入 + 确定</b>，然后一块<b>终端样式的可滚动格子网格</b>，下面是玩家背包。
 *
 * <h2>第 131 轮：用户第 6 张图的意见（「显示的很莫名其妙」）</h2>
 * <p>用户原话：「<i>这个网络资源接收器这个显示的很莫名其妙。首先他好多废话（「已命名」「没有」这些全是废话）。
 * 「取全部」这个按钮也是废话。输入框还可以。……这就相当于是一个取东西的地方……
 * 它应该是保持和那个终端是一样的，就是终端它不是可以滚动嘛，和终端一样可以滚动。</i>」</p>
 * <p>于是本界面把旧版的 5 行状态读数（名字提示 / 状态 / 节拍 / 缓存摘要 / 流体摘要）+ 页眉页码箭头 +
 * 「取全部」按钮<b>整片</b>删掉，换成：</p>
 * <ul>
 *     <li>顶行只有<b>输入框 + 「确定」</b>（服务端权威改名链路一个字没改，见
 *     {@link #submitName()}）；</li>
 *     <li>中间是 <b>{@link NetworkResourceReceiverMenu#GRID_COLS}×{@link NetworkResourceReceiverMenu#GRID_ROWS}
 *     的格子网格</b>：每格 18px，格内是物品 / 流体图标 + 数量角标 —— 就是终端的样子；槽框已经
 *     烘焙在背景 PNG 里（第 128 轮铁律：{@code renderBg} 一次 blit、不许 fill 画面板）；</li>
 *     <li><b>滚轮 + 滚动条</b>（RS 原版 {@link ScrollbarWidget}）按行滚动，手感与终端一致；
 *     没有上一页 / 下一页按钮，也没有页码句子；</li>
 *     <li>点格子 = 取出（左键一组 / Shift 全部）；客户端<b>只报「取谁、要不要全部」</b>，
 *     数量与能否收下由服务端算（契约 §3.B.3）。</li>
 * </ul>
 *
 * <h2>为什么「格子 = 取出」而不是「格子 = 选中」</h2>
 * <p>用户给这个界面的定位是「取东西的地方」：方格子里就是那件东西，点它就是拿走。
 * 因此没有选中态、没有详情面板、没有二次确认 —— 拿走一个物品不需要确认（它不销毁任何东西，
 * 服务端装不下就回填缓存）。选中态在过去只为「再按下方的按钮」服务，那两个按钮（取全部）已经删了。</p>
 *
 * <h2>为什么滚动要发服务端包</h2>
 * <p>屏幕上的这一屏格子由服务端按「页首行」切片下发（每屏
 * {@link NetworkResourceReceiverMenu#WINDOW_SIZE} 格）；客户端只读 {@link #entries}，
 * <b>不</b>持有全量缓存镜像。这与 RS 2.0.9 自己的网格同一模式（{@code GridScrollPacket}
 * 把滚动行回传服务端，服务端再同步视图），所以滚动的「权威行号」只有一份，
 * 不会出现「客户端滚了、服务端还在旧行」的分叉。</p>
 *
 * <h2>为什么服务端提示改走动作条（而不是界面里的一行字）</h2>
 * <p>改名结果 / 「背包满了，剩下的还在缓存」这类反馈必须有，但用户明确讨厌界面里的状态句。
 * 现在 {@link #onServerSync} 收到<b>新的</b>非空 {@code messageKey} 就用
 * {@code displayClientMessage(..., true)} 打到动作条（不占 GUI 像素），悬停输入框时还能在 tooltip 里重看。</p>
 *
 * <h2>为什么这个类必须待在 {@code client/} 包里</h2>
 * <p>{@link AbstractContainerScreen} 与 {@code GuiGraphics} 都是客户端专属类型；
 * 专用服务端上根本没有这些类。硬规则：<b>任何引用 {@code net.minecraft.client.*} 的类
 * 只能放在 {@code client/} 包下</b>，由 {@code ClientInit}（{@code Dist.CLIENT}）注册。</p>
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
    /** 数量角标的字色：白（底下垫一层半透明黑底，深色图标上也读得清）。 */
    private static final int COLOR_BADGE = 0xFFFFFFFF;
    /** 数量角标的底：半透明黑（只垫字，不碰槽框）。 */
    private static final int COLOR_BADGE_BACK = 0x90000000;
    /** 悬停格子的高亮：与原版槽位高亮同一套半透明白。 */
    private static final int COLOR_CELL_HOVER = 0x80FFFFFF;

    // ==================== 顶行：名字输入 + 确定（Menu 坐标） ====================
    /** 输入框：标题之下一条，右边界 129，离升级槽框（213）很远。 */
    private static final int BOX_X = 9;
    private static final int BOX_Y = 18;
    private static final int BOX_W = 120;
    private static final int BOX_H = 16;
    /** 「确定」按钮：紧跟输入框右侧（133…179，不碰滚动条 174…186 —— 按钮在输入框行、滚动条在网格行）。 */
    private static final int OK_X = BOX_X + BOX_W + 4;
    private static final int OK_W = 46;

    /** 「插件」栏标题：与右侧升级槽同列，右边界正好顶到槽框左沿（213）。 */
    private static final int UPGRADES_LABEL_X = 194;
    private static final int UPGRADES_LABEL_Y = 6;
    /** 该标题的可用宽度 = 槽框左沿 213 − 起点 194 = 19px（见 renderLabels 里的说明）。 */
    private static final int UPGRADES_LABEL_MAX_W = 213 - UPGRADES_LABEL_X;

    /** 改名输入框（{@code init()} 里创建；重开界面时会重建）。 */
    private EditBox nameBox;
    /** 资源网格滚动条（RS 原版控件；拖动 / 点击 → 请求滚动到那一行）。 */
    private ScrollbarWidget scrollbar;

    // ==================== 服务端权威只读镜像（全部来自 S2C 快照） ====================
    /** 服务端权威的当前名字。 */
    private String syncedName = "";
    /** 服务端下发的「本网络已占用名字集合」（只用于输入框 tooltip，最终判据仍在服务端）。 */
    private Set<String> occupiedNames = Set.of();
    /** 服务端下发的最近一次操作提示（语言键；空串 = 无提示）。 */
    private String messageKey = "";
    /** 当前这一屏的格子（下标 = 服务端下发顺序 = 行优先）。 */
    private List<ReceiverCacheEntry> entries = List.of();
    /** 页首行（0 基，单位 = 网格行）：**服务端权威值**，客户端只镜像。 */
    private int firstRow;
    /** 总行数（服务端算，客户端不自己按 entries 猜）。 */
    private int rowCount = 1;
    /** 上一次已经用动作条播过的提示（同一句不重复刷）。 */
    private String lastShownMessageKey = "";

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
        // 网格滚动条：偏移 = 页首行（拖动 / 点击都经 listener 变成一次滚动请求）
        scrollbar = new ScrollbarWidget(
            leftPos + NetworkResourceReceiverMenu.SCROLLBAR_X,
            topPos + NetworkResourceReceiverMenu.SCROLLBAR_Y,
            ScrollbarWidget.Type.NORMAL,
            NetworkResourceReceiverMenu.SCROLLBAR_H);
        scrollbar.setListener(offset -> requestScroll((int) Math.round(offset)));
        refreshScrollbar();
        addWidget(scrollbar);
    }

    // ==================== 交互（全部只发「请求」，不写本地配置 / 不读方块实体） ====================

    /**
     * 提交一次改名（唯一出口）：只把「玩家想改成的名字」发给服务端，等 S2C 快照回来再更新显示。
     * <p>客户端<b>不</b>先写本地名字：否则被服务端拒绝时界面会先闪成新名字再跳回旧名字。
     * （第 131 轮只改了「显示在哪里」，这条链路一个字没动。）</p>
     */
    private void submitName() {
        if (nameBox == null) {
            return;
        }
        messageKey = ""; // 清掉上一条提示，避免旧结果与新操作混在一起
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetReceiverNamePacket(menu.containerId, nameBox.getValue()));
    }

    /** 页首行的最大合法值（= 总行数 − 可见行数；不足一屏 ⇒ 0，滚动条禁用）。 */
    private int maxFirstRow() {
        return Math.max(0, rowCount - NetworkResourceReceiverMenu.GRID_ROWS);
    }

    /**
     * 请求把网格滚到某一页首行（唯一出口）：发
     * {@link ReceiverCacheActionPacket#ACTION_PAGE}。
     * <p><b>为什么不在客户端自己滚</b>：这一屏格子由服务端切片下发，客户端自己滚会与服务端
     * 行号分叉（而且下一帧就会被下一份快照推翻，表现为「滚一下又弹回」）。RS 2.0.9 的网格
     * 也是把滚动行回传服务端（{@code GridScrollPacket}），这里同一口径。</p>
     * <p><b>为什么先乐观写 {@link #firstRow}</b>：拖动滚动条时 listener 会连续给出新行号，
     * 若每次都等服务端回包，滚动条会「粘住再跳」。先写本地行号 → 滚动条不反弹；
     * 服务端若夹到别的值，下一份快照会立刻纠正（权威仍在服务端）。</p>
     */
    private void requestScroll(final int row) {
        final int clamped = Math.max(0, Math.min(maxFirstRow(), row));
        if (clamped == firstRow) {
            return; // 已经在这一行：不重复发包（滚动条的 setOffset 回灌就是从这里短路）
        }
        firstRow = clamped;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            ReceiverCacheActionPacket.page(menu.containerId, clamped));
    }

    /**
     * 请求取出一格（物品 = 一组 / 全部；流体 = 一桶 / 尽可能多整桶）。
     * <p>客户端<b>只报资源身份 + 要不要全部</b>；数量、是否装得下、先消耗谁的桶，全部由服务端算
     * （契约 §3.B.3）。</p>
     */
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
     * 鼠标点击：格子 ⇒ 请求取出（左键一组 / Shift 全部）；其它区域走原版（升级槽 / 背包）。
     * <p>格子<b>没有</b>对应的原版槽位（一格 = 一种资源，可能横跨多个缓存格；流体更没有 ItemStack），
     * 因此这里用显式矩形命中，命中的<b>整格身份</b>回传服务端，服务端再按身份去真实缓存里定位 ——
     * 绝不会因为「这一屏刚好刷新了」而取错资源。</p>
     */
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 0) {
            final int index = cellIndexAt(mouseX, mouseY);
            if (index >= 0 && index < entries.size()) {
                requestTake(entries.get(index), hasShiftDown());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 滚轮：在网格上 = 按行滚动（一格一行，终端手感）；其它地方交给原版（不拦截）。 */
    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (isOverGrid(mouseX, mouseY)) {
            if (scrollY != 0.0) {
                requestScroll(firstRow - (int) Math.signum(scrollY));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ==================== 命中判定（与绘制严格同源） ====================

    /**
     * 鼠标落在第几格（返回<b>本屏内</b>的下标；不在网格上 / 超出这一屏 ⇒ -1）。
     * <p>下标口径 = 行优先：格 i 的列 = {@code i % GRID_COLS}、行 = {@code i / GRID_COLS}
     * —— 与服务端切片 {@code NetworkResourceReceiverMenu#window(...)} 逐字一致。</p>
     */
    private int cellIndexAt(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        if (x < NetworkResourceReceiverMenu.GRID_X0
            || x >= NetworkResourceReceiverMenu.GRID_X0 + NetworkResourceReceiverMenu.GRID_W
            || y < NetworkResourceReceiverMenu.GRID_Y0
            || y >= NetworkResourceReceiverMenu.GRID_Y0 + NetworkResourceReceiverMenu.GRID_H) {
            return -1;
        }
        final int col = (x - NetworkResourceReceiverMenu.GRID_X0) / NetworkResourceReceiverMenu.CELL;
        final int row = (y - NetworkResourceReceiverMenu.GRID_Y0) / NetworkResourceReceiverMenu.CELL;
        final int index = row * NetworkResourceReceiverMenu.GRID_COLS + col;
        return index < entries.size() ? index : -1;
    }

    /** 鼠标是否落在网格区域（滚轮滚动用）。 */
    private boolean isOverGrid(final double mouseX, final double mouseY) {
        final int x = (int) mouseX - leftPos;
        final int y = (int) mouseY - topPos;
        return x >= NetworkResourceReceiverMenu.GRID_X0
            && x < NetworkResourceReceiverMenu.GRID_X0 + NetworkResourceReceiverMenu.GRID_W
            && y >= NetworkResourceReceiverMenu.GRID_Y0
            && y < NetworkResourceReceiverMenu.GRID_Y0 + NetworkResourceReceiverMenu.GRID_H;
    }

    // ==================== S2C 快照 ====================

    /**
     * S2C：服务端权威快照（名字 + 已占用名字集合 + 操作提示 + 这一屏格子 / 页首行 / 总行数）。
     * <p>只在「打开界面 / 改名 / 取出 / 滚动 / 指纹变化（每 10 tick 至多一次）」时到达；
     * 界面每帧只读这些字段，绝不在渲染循环里拼数据、也绝不读方块实体。</p>
     */
    public void onServerSync(final SyncReceiverConfigPacket packet) {
        syncedName = packet.name() == null ? "" : packet.name();
        occupiedNames = Set.copyOf(packet.occupiedNames());
        messageKey = packet.messageKey() == null ? "" : packet.messageKey();
        entries = List.copyOf(packet.entries());
        // 快照里的 page 字段从第 131 轮起含义 = 页首行（单位 = 网格行），线格式没变
        firstRow = Math.max(0, packet.page());
        rowCount = Math.max(1, packet.pageCount());
        refreshScrollbar();
        if (nameBox != null && !nameBox.isFocused()) {
            // 正在输入时不覆盖玩家自己的文本（否则服务端每次同步都会打断输入）
            nameBox.setValue(syncedName);
        }
        // 服务端提示：用动作条播一次（不占 GUI 像素；同一句不重复刷）
        if (!messageKey.isEmpty() && !messageKey.equals(lastShownMessageKey)
            && minecraft != null && minecraft.player != null) {
            lastShownMessageKey = messageKey;
            minecraft.player.displayClientMessage(Component.translatable(messageKey), true);
        } else if (messageKey.isEmpty()) {
            lastShownMessageKey = "";
        }
    }

    /**
     * 把滚动条同步成「服务端权威页首行」。
     * <p>顺序很重要：先<b>写本地行号</b>（{@link #firstRow} 已在 {@link #onServerSync} 里更新），
     * 再 {@code setOffset(...)} —— 它的 listener 会因为「已经在这一行」而不再发包，
     * 于是不会出现「每份快照都回一个滚动包」的 ping-pong。</p>
     */
    private void refreshScrollbar() {
        if (scrollbar == null) {
            return;
        }
        final int max = maxFirstRow();
        scrollbar.setEnabled(max > 0);
        scrollbar.setMaxOffset(max);
        scrollbar.setOffset(Math.max(0, Math.min(max, firstRow)));
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        // 整图 blit：槽框（升级槽 / 网格 / 背包 / 快捷栏）已烘焙在 PNG 里，一次画完。
        // 第 128 轮铁律：renderBg 只做一次 blit，不用 fill 画面板。
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 全部文字不带阴影（工程硬规则：全模组 drawString 一律传 false）
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 「插件」栏标题：只有 19px（槽框左沿 213 − 起点 194），英文原串 "Upgrades" 放不下 ⇒ 语言值
        // 已收成 "Upg"（完整含义在空升级槽的 tooltip 里）。绘制走 drawFitLine：完整显示、绝不省略。
        // rscc:no-tooltip 固定短标签：升级槽分区标题（lang 固定词，长度由语言文件控制）
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "upgrades"),
            UPGRADES_LABEL_X, UPGRADES_LABEL_Y, UPGRADES_LABEL_MAX_W, COLOR_TITLE);
        // 资源网格（图标 + 数量角标）：与命中判定同源
        renderGrid(guiGraphics, mouseX, mouseY);
        guiGraphics.drawString(font, Component.translatable("container.inventory"),
            inventoryLabelX, inventoryLabelY, COLOR_TEXT, false);
    }

    /**
     * 资源网格：每格 = 图标（物品 / 流体）+ 数量角标，悬停格加一层原版同款白色高亮。
     *
     * <p><b>坐标全部来自 {@link NetworkResourceReceiverMenu}</b>（列数 / 行数 / 格子边长 / 原点）：
     * 绘制、命中判定、服务端切片三处用的是同一组常量，因此「第 i 格」在三个地方永远是同一格。
     * 槽框在 PNG 里（槽框左上角 = Menu 坐标 − 1），这里只画格子<b>内容</b>，不画框。</p>
     *
     * <p><b>数量角标</b>：物品 = 件数；流体 = <b>桶数</b>（玩家要做的就是「换几桶」，mB 在 tooltip 里
     * 同时给出）。角标先垫一层半透明黑底再写白字，深色图标（岩浆 / 原油）上也读得清；
     * 数值过大时走 {@link RsccTextFit#drawFitLine} 整体缩字号，<b>绝不省略</b>。</p>
     */
    private void renderGrid(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int hovered = cellIndexAt(mouseX, mouseY);
        for (int i = 0; i < entries.size() && i < NetworkResourceReceiverMenu.WINDOW_SIZE; i++) {
            final ReceiverCacheEntry entry = entries.get(i);
            if (entry.isEmpty()) {
                continue;
            }
            final int col = i % NetworkResourceReceiverMenu.GRID_COLS;
            final int row = i / NetworkResourceReceiverMenu.GRID_COLS;
            final int x = NetworkResourceReceiverMenu.GRID_X0 + col * NetworkResourceReceiverMenu.CELL;
            final int y = NetworkResourceReceiverMenu.GRID_Y0 + row * NetworkResourceReceiverMenu.CELL;
            // 图标：16×16 画在 18×18 格子里（+1 居中，槽框在 PNG 的 −1 处）
            if (entry.isFluid()) {
                GhostMarkerRenderer.renderFluid(guiGraphics, x + 1, y + 1, entry.fluid());
            } else {
                guiGraphics.renderItem(entry.item(), x + 1, y + 1);
            }
            renderAmountBadge(guiGraphics, entry, x, y);
            if (i == hovered) {
                // 与原版槽位高亮逐像素同一观感（16×16 内区，不盖槽框）
                guiGraphics.fill(x, y, x + NetworkResourceReceiverMenu.CELL - 2,
                    y + NetworkResourceReceiverMenu.CELL - 2, COLOR_CELL_HOVER);
            }
        }
    }

    /** 数量角标（右下角；先铺半透明黑底，再写白字；放不下整体缩字号）。 */
    private void renderAmountBadge(final GuiGraphics guiGraphics, final ReceiverCacheEntry entry,
                                   final int x, final int y) {
        // 文本来自唯一工厂 amountText(...)：格子角标（紧凑）与 tooltip 的数量行（精确）同一口径。
        final Component badge = Component.literal(amountText(entry, false));
        final int maxW = NetworkResourceReceiverMenu.CELL - 3;
        final int textW = Math.min(maxW, font.width(badge));
        final int textX = x + NetworkResourceReceiverMenu.CELL - 1 - textW;
        final int textY = y + NetworkResourceReceiverMenu.CELL - 10;
        // 底：只垫在字下面（半透明黑），不碰槽框 —— 不是「用 fill 画面板」
        guiGraphics.fill(Math.max(x, textX - 1), textY, x + NetworkResourceReceiverMenu.CELL - 1,
            textY + 9, COLOR_BADGE_BACK);
        RsccTextFit.drawFitLine(guiGraphics, font, badge, textX, textY, textW, COLOR_BADGE);
    }

    /**
     * 数量文本的<b>唯一工厂方法</b>：格子角标（{@code exact = false} ⇒ 紧凑写法，18px 格子里放得下）
     * 与格子 tooltip 的数量行（{@code exact = true} ⇒ 千分位精确值）都从这里取，
     * 因此「屏幕上那个数」和「tooltip 里那个数」永远是同一种资源的同一个量，不会各算一份。
     *
     * <p>单位口径：物品 = 件数；流体 = <b>桶数</b>（玩家要做的就是「换几桶」；
     * tooltip 里再用 {@code bucketTooltip} 给出精确 mB + 桶数）。</p>
     */
    private static String amountText(final ReceiverCacheEntry entry, final boolean exact) {
        final long value = entry.isFluid()
            ? Math.max(0L, RsccNumberField.buckets(entry.amount()))
            : entry.amount();
        return exact ? RsccNumberField.grouped(value) : RsccNumberField.compact(value);
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
        // 格子：完整名称 + 精确数量 + 手势说明（行内放不下全名时，这里是唯一能看到全名的地方）
        renderGridTooltip(guiGraphics, mouseX, mouseY);
        // 输入框：名字规则 / 服务端提示 / 本网络已占用的名字
        renderNameBoxTooltip(guiGraphics, mouseX, mouseY);
    }

    /**
     * 悬停格子 → 完整名称 + 精确数量 + 手势说明。
     * <p>名称是自由文本（第三方模组物品名），格宽只有 18px：格内<b>不画名称</b>（那是终端的做法——
     * 鼠标悬停才知道是什么），因此 tooltip 是唯一能看到全名的地方，必须给。
     * 与其它 tooltip 同一帧纪律：只有真的悬停在格子上、且原版槽位 tooltip 不会画时才画
     * （判据复用工程唯一实现 {@link RsccTooltipLayers#vanillaSlotTooltipWillDraw}）。</p>
     */
    private void renderGridTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int index = cellIndexAt(mouseX, mouseY);
        if (index < 0 || index >= entries.size()) {
            return;
        }
        if (RsccTooltipLayers.vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        final ReceiverCacheEntry entry = entries.get(index);
        final Component name = entry.hoverName();
        if (name == null) {
            return;
        }
        final List<Component> lines = new ArrayList<>(3);
        if (entry.isFluid()) {
            // 流体：mB 与桶数一起给（角标只写桶数，这里补齐原始量）；
            // 手势说明用新键 grid.fluid_hint —— 旧的 cache.fluid.hint 写的是「用背包里的空桶」，
            // 而第 131 轮起是「先背包桶、再网络桶」（RS 2.0.9 的真实顺序），旧文案会误导。
            lines.add(name.copy().append(" ")
                .append(RsccNumberField.bucketTooltip(entry.amount())));
            lines.add(Component.translatable(LANG + "grid.fluid_hint"));
        } else {
            // 数量行与格子角标同源（同一个工厂 amountText(..., true) ⇒ 精确千分位），
            // 因此 tooltip 里的数和角标上的数是同一份口径，不会「屏幕上 1.2万、tooltip 12,345」互相矛盾。
            lines.add(name.copy().append(" × ").append(amountText(entry, true)));
            // 物品手势与第 96 轮逐字一致，直接复用既有键（不新增同义 key）
            lines.add(Component.translatable(LANG + "cache.slot.hint"));
        }
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 悬停改名输入框 → 名字规则（形状判据与服务端同一段代码
     * {@link NetworkResourceReceiverMenu#checkNameShape(String)}）+ 服务端最近一次提示 +
     * 本网络已占用的名字集合。
     *
     * <p><b>为什么名字反馈全塞进 tooltip</b>：用户明确要求界面上只留「输入框 + 确定」，
     * 原来那行「已命名 / 未命名 / 重名」的状态句属于「废话」。但反馈不能没有 ——
     * 因此改成「悬停输入框才展开」，另外服务端每次结果还会用动作条播一次（见 {@link #onServerSync}）。</p>
     */
    private void renderNameBoxTooltip(final GuiGraphics guiGraphics, final int mouseX,
                                      final int mouseY) {
        if (nameBox == null || !nameBox.isMouseOver(mouseX, mouseY)) {
            return;
        }
        if (RsccTooltipLayers.vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        final List<Component> lines = new ArrayList<>(3);
        if (!messageKey.isEmpty()) {
            lines.add(Component.translatable(messageKey)); // 服务端最近一次结果（成功 / 拒绝原因）
        }
        lines.add(nameHint());
        lines.add(occupiedNames.isEmpty()
            ? Component.translatable(LANG + "name.occupied.none")
            : Component.translatable(LANG + "name.occupied", String.join(", ", occupiedNames)));
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * 空升级槽的常显 tooltip：<b>直接走全工程统一实现</b> {@link UpgradeSlotTooltips#render}
     * （与范围充电器 / 请求器同一个调用点）。
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

    // ==================== 名字提示（只用于 tooltip，不再占界面像素） ====================

    /**
     * 名字行提示：正常显示说明；输入为空 / 超长 / 含非法字符 / 与占用集合冲突时变成即时提示。
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
}
