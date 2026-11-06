package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.grid.screen.GridScreen;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu;
import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import cretae.cookiewyq.rs_create_compat.network.SetReceiverNamePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 网络资源接收器界面（第 132 轮：<b>改成发送器同款 RS {@code GridScreen} 基底</b>）。
 *
 * <h2>用户意见</h2>
 * <p>用户原话：「你完全理解错我意思了，你自己写一套这个狗屎东西干什么？我的意思就像第一张图（发送器界面）
 * 那样自己实现一个这个东西，一样是这样重新写……你直接像图一那样搞一个这个东西重写一下，然后加点东西不就得了。」
 * 图一 = 本工程 {@code ResourcePickerScreen}：继承 RS 的 {@code GridScreen}，搜索框 / 视图 / 排序侧键 /
 * 滚动条 / 数量角标 / 槽位 tooltip / 点击取物全部由 RS 提供。</p>
 *
 * <p>因此本类<b>删除</b>了旧版的：自绘 {@code TEXTURE}、9×7 手画格子、{@code renderAmountBadge}、
 * 自带 {@code ScrollbarWidget}、竖排「插件」栏。保留的 extras 只有三件：</p>
 * <ol>
 *     <li><b>名字输入框 + 确定</b>（服务端权威改名链路一个字没改）；</li>
 *     <li><b>6 个升级槽</b>（真实菜单槽，坐标在 {@code init()} 里按右侧面板重新定位）；</li>
 *     <li><b>状态 / 能量提示</b>（配置状态、缓存摘要、耗电、手势提示）。</li>
 * </ol>
 *
 * <h2>extras 面板为什么不改 {@code imageWidth}</h2>
 * <p>RS 的网格贴图固定 193×176。把面板画在 GUI 右侧的空白区（与发送器一致）而不是把贴图拉宽，
 * 可以保证 RS 的搜索框 / 滚动条 / 侧键 / 背包坐标一个像素都不动。面板位置会夹到屏幕内
 * （{@code panelX = min(leftPos + 197, width - 118 - 2)}），并把 6 个升级槽的
 * {@link Slot#x}/{@link Slot#y} 同步到面板内 —— 槽位坐标是纯客户端的显示属性，
 * 服务端只认槽下标，因此不会产生同步问题。</p>
 *
 * <h2>点击 / 滚动全部交给 RS</h2>
 * <p>左键取、Shift 进背包、右键取一半由 {@code AbstractGridScreen#mouseClicked} 走
 * {@code menu.onExtract}；滚轮 / 滚动条由 {@code AbstractStretchingScreen} 与 RS 的
 * {@code ScrollbarWidget} 处理。本类<b>不</b>覆盖 {@code mouseClicked} / {@code mouseScrolled}
 * —— 任何「再自己写一套命中」都会重新制造用户讨厌的那套东西。</p>
 */
public class NetworkResourceReceiverScreen extends GridScreen<NetworkResourceReceiverMenu> {
    /** 本界面全部语言键的前缀（与语言文件逐字一致）。 */
    private static final String LANG = "gui.rs_create_compat.network_resource_receiver.";

    /** RS 网格 GUI 的固定宽度（{@code GridScreen} 硬编码 193）。 */
    private static final int GRID_IMAGE_W = 193;
    /** extras 面板宽（够放输入框 / 确定 / 两列升级槽）。 */
    private static final int PANEL_W = 112;
    /** 面板与 GUI 右沿的间距。 */
    private static final int PANEL_GAP = 2;
    /** 面板左沿相对 {@code leftPos} 的偏移（193 + 2 = 195，不压滚动条列 174）。 */
    private static final int PANEL_X_OFFSET = GRID_IMAGE_W + PANEL_GAP;
    /** 面板内边距。 */
    private static final int PANEL_PAD = 4;
    /** 面板内容宽。 */
    private static final int INNER_W = PANEL_W - PANEL_PAD * 2;

    /** 名字输入框。 */
    private static final int NAME_Y = 18;
    private static final int NAME_H = 16;
    private static final int NAME_W = 64;
    /** 「确定」按钮（同一行，右对齐）。 */
    private static final int OK_W = INNER_W - NAME_W - 2;

    /** 状态行 / 缓存摘要 / 流体摘要 / 能量 / 手势 / 插件标题的 y（相对面板顶）。 */
    private static final int STATUS_Y = 6;
    private static final int CACHE_Y = 38;
    private static final int FLUID_Y = 49;
    private static final int HINT_Y = 60;
    private static final int UPGRADES_Y = 71;

    private static final int COLOR_TITLE = 0xFFFFE08A;
    private static final int COLOR_TEXT = 0xFFE8E8E8;
    private static final int COLOR_HINT = 0xFFAFC8E8;
    private static final int COLOR_WARN = 0xFFFF8A8A;
    private static final int COLOR_OK = 0xFF8FE08F;

    /** 改名输入框（{@code init()} 里创建；重开界面时会重建）。 */
    private EditBox nameBox;

    // ==================== 服务端权威只读镜像（全部来自 S2C 快照） ====================
    private String syncedName = "";
    private Set<String> occupiedNames = Set.of();
    private String messageKey = "";
    private String lastShownMessageKey = "";
    private boolean configured;
    private int itemTypes;
    private long itemTotal;
    private int fluidTypes;
    private int fluidTotal;

    // ==================== 几何（init 时算好，渲染与槽位共用） ====================
    private int panelX;
    private int panelY;
    private int panelH;
    /** 面板摘要刷新倒计时（从 RS 资源仓库聚合，不依赖 S2C 全量包；每 10 tick 一次）。 */
    private int summaryCooldown;

    public NetworkResourceReceiverScreen(final NetworkResourceReceiverMenu menu,
                                         final Inventory inventory,
                                         final Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        layoutPanel();
        final int x = panelX + PANEL_PAD;
        final int y = panelY + NAME_Y;
        nameBox = new EditBox(font, x, y, NAME_W, NAME_H,
            Component.translatable(LANG + "name.label"));
        nameBox.setMaxLength(NetworkResourceReceiverMenu.MAX_NAME_LENGTH);
        nameBox.setValue(syncedName);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable(LANG + "name.button"),
                button -> submitName())
            .bounds(panelX + PANEL_W - PANEL_PAD - OK_W, y, OK_W, NAME_H)
            .build());
        // 升级槽坐标是 Slot 的构造参数（final）⇒ 先告诉菜单面板原点，再让菜单重排一次槽位。
        menu.setClientPanelOrigin(
            panelX + PANEL_PAD - leftPos,
            panelY + NetworkResourceReceiverMenu.UPGRADE_Y0 - topPos);
        menu.resized(imageHeight - 99 + 17, 0, 0);
    }

    /** 面板位置：优先贴 GUI 右侧，屏幕不够宽时夹到屏幕内（绝不画到屏幕外）。 */
    private void layoutPanel() {
        panelX = Math.max(0, Math.min(leftPos + PANEL_X_OFFSET, width - PANEL_W - 2));
        panelY = topPos;
        panelH = Math.max(160, imageHeight);
    }

    /**
     * 每 10 tick 从 RS 的客户端资源仓库重算一次面板摘要。
     * <p>为什么不只读 S2C 全量包：全量包只在打开 / 改名时下发，之后缓存变化走的是 RS 的增量
     * {@code GridUpdatePacket}。若摘要只读全量包，玩家会看到「格子数量在动、下面那行摘要不动」。
     * 仓库本身就是服务端网格的只读镜像，在这里按资源类型求和只是显示层聚合，不产生第二份权威账。</p>
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (--summaryCooldown > 0) {
            return;
        }
        summaryCooldown = 10;
        int items = 0;
        long itemAmount = 0L;
        int fluids = 0;
        long fluidAmount = 0L;
        for (final ResourceAmount amount : menu.getRepository().copyBackingList().copyState()) {
            if (amount.resource() instanceof ItemResource) {
                items++;
                itemAmount += amount.amount();
            } else if (amount.resource() instanceof FluidResource) {
                fluids++;
                fluidAmount += amount.amount();
            }
        }
        itemTypes = items;
        itemTotal = itemAmount;
        fluidTypes = fluids;
        fluidTotal = (int) Math.min(fluidAmount, Integer.MAX_VALUE);
    }

    // ==================== 交互（全部只发「请求」，不写本地配置 / 不读方块实体） ====================

    /** 提交一次改名：只把「玩家想改成的名字」发给服务端，等 S2C 快照回来再更新显示。 */
    private void submitName() {
        if (nameBox == null) {
            return;
        }
        messageKey = "";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetReceiverNamePacket(menu.containerId, nameBox.getValue()));
    }

    /** 回车 = 点「确定」；其它键先给 RS 的搜索框（{@code super.keyPressed}）。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (nameBox != null && nameBox.isFocused() && (keyCode == 257 || keyCode == 335)) {
            submitName();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== S2C 快照 ====================

    /**
     * S2C：服务端权威快照（名字 + 已占用名字集合 + 结果提示 + {@code GridData} 全量资源）。
     * <p>资源经 {@code menu.applyClientGridSnapshot(...)} 灌进 RS 的资源仓库；界面每帧只读这份仓库，
     * 绝不在渲染循环里拼数据、也绝不读方块实体。</p>
     */
    public void onServerSync(final SyncReceiverConfigPacket packet) {
        syncedName = packet.name() == null ? "" : packet.name();
        occupiedNames = Set.copyOf(packet.occupiedNames());
        messageKey = packet.messageKey() == null ? "" : packet.messageKey();
        configured = packet.configured();
        itemTypes = packet.itemTypes();
        itemTotal = packet.itemTotal();
        fluidTypes = packet.fluidTypes();
        fluidTotal = packet.fluidTotal();
        if (packet.replaceGrid()) {
            menu.applyClientGridSnapshot(packet.gridData());
        } else {
            // 改名 / active 变化：资源仓库不动，资源增量继续走 RS 的 GridUpdatePacket
            menu.onActiveChanged(packet.gridData().active());
        }
        if (nameBox != null && !nameBox.isFocused()) {
            nameBox.setValue(syncedName);
        }
        if (!messageKey.isEmpty() && !messageKey.equals(lastShownMessageKey)
            && minecraft != null && minecraft.player != null) {
            lastShownMessageKey = messageKey;
            minecraft.player.displayClientMessage(Component.translatable(messageKey), true);
        } else if (messageKey.isEmpty()) {
            lastShownMessageKey = "";
        }
    }

    // ==================== 渲染 ====================

    /**
     * 面板背景 → 面板文字 → RS 网格 / 控件 / tooltip。
     * <p>面板背景与静态文字必须在 {@code super.render} 之前：面板在 GUI <b>之外</b>的右侧，
     * 不会被 RS 的 {@code renderBg} 覆盖；放在之前是为了让输入框 / 按钮画在面板上面。</p>
     */
    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        renderPanelBackground(guiGraphics);
        renderPanelText(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 原版槽位 tooltip（玩家背包 / 升级槽）—— 本模组 GUI 不会自动画，必须手动调一次
        renderUpgradeSlotTooltips(guiGraphics, mouseX, mouseY);
        renderNameBoxTooltip(guiGraphics, mouseX, mouseY);
    }

    private void renderPanelBackground(final GuiGraphics guiGraphics) {
        McGui.panel(guiGraphics, panelX, panelY, PANEL_W, panelH);
        // 6 个升级槽是真实 Menu 槽位；面板是运行时画的，必须自己补槽框（与槽位内容坐标 −1 同源）。
        for (int i = 0; i < NetworkResourceReceiverMenu.UPGRADE_COUNT; i++) {
            final int slotX = panelX + PANEL_PAD
                + (i % 2) * NetworkResourceReceiverMenu.UPGRADE_DX;
            final int slotY = panelY + NetworkResourceReceiverMenu.UPGRADE_Y0
                + (i / 2) * NetworkResourceReceiverMenu.UPGRADE_DY;
            McGui.slotFrame(guiGraphics, slotX - 1, slotY - 1);
        }
    }

    private void renderPanelText(final GuiGraphics guiGraphics) {
        final int x = panelX + PANEL_PAD;
        final int w = INNER_W;

        // ① 状态：未命名 / 已命名（未运行只改颜色，不再新增语言键）
        final Component status;
        final int statusColor;
        if (!configured) {
            status = Component.translatable(LANG + "status.unnamed");
            statusColor = COLOR_WARN;
        } else {
            status = Component.translatable(LANG + "status.named");
            statusColor = menu.isActive() ? COLOR_OK : COLOR_WARN;
        }
        // rscc:no-tooltip 固定短标签：状态行（完整含义在输入框 tooltip 与动作条提示里）
        RsccTextFit.drawFitLine(guiGraphics, font, status, x, panelY + STATUS_Y, w, statusColor);

        // ② 缓存摘要（物品 / 流体）
        // rscc:no-tooltip 固定短标签：一行摘要（数字本身自解释；网格 tooltip 给每格精确数量）
        RsccTextFit.drawFitLine(guiGraphics, font,
            Component.translatable(LANG + "cache.summary", itemTypes, itemTotal),
            x, panelY + CACHE_Y, w, COLOR_TEXT);
        // rscc:no-tooltip 固定短标签：一行流体摘要
        RsccTextFit.drawFitLine(guiGraphics, font,
            Component.translatable(LANG + "cache.fluid", fluidTypes, fluidTotal),
            x, panelY + FLUID_Y, w, COLOR_HINT);
        // ③ 手势提示（与旧界面同一条文案，不新增同义键）
        // rscc:no-tooltip 固定短标签：操作手势说明（RS 网格自身也有悬停提示）
        RsccTextFit.drawFitLine(guiGraphics, font,
            Component.translatable(LANG + "cache.slot.hint"),
            x, panelY + HINT_Y, w, COLOR_HINT);
        // ④ 插件栏标题
        // rscc:no-tooltip 固定短标签：升级槽分区标题（完整含义在空升级槽 tooltip 里）
        RsccTextFit.drawFitLine(guiGraphics, font,
            Component.translatable(LANG + "upgrades"),
            x, panelY + UPGRADES_Y, w, COLOR_TITLE);
    }

    /**
     * 空升级槽的常显 tooltip：直接走全工程统一实现 {@link UpgradeSlotTooltips#render}
     * （与范围充电器 / 请求器同一个调用点）。
     */
    private void renderUpgradeSlotTooltips(final GuiGraphics guiGraphics,
                                           final int mouseX, final int mouseY) {
        if (hoveredSlot instanceof UpgradeSlot upgradeSlot
            && hoveredSlot.getItem().isEmpty()
            && menu.getCarried().isEmpty()) {
            UpgradeSlotTooltips.render(upgradeSlot, guiGraphics, font, mouseX, mouseY);
        }
    }

    /**
     * 悬停改名输入框 → 名字规则（形状判据与服务端同一段代码
     * {@link NetworkResourceReceiverMenu#checkNameShape(String)}）+ 服务端最近一次提示 +
     * 本网络已占用的名字集合。
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
            lines.add(Component.translatable(messageKey));
        }
        lines.add(nameHint());
        lines.add(occupiedNames.isEmpty()
            ? Component.translatable(LANG + "name.occupied.none")
            : Component.translatable(LANG + "name.occupied", String.join(", ", occupiedNames)));
        guiGraphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    // ==================== 名字提示（只用于 tooltip，不占界面像素） ====================

    /** 名字行提示：正常显示说明；输入为空 / 超长 / 含非法字符 / 与占用集合冲突时变成即时提示。 */
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
