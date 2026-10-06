package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列执行仓界面：54 格（6×9）单元样板槽 + 玩家背包（大型双箱式布局，176×222）。
 * <p>标题右侧提供「配置」入口，打开独立子界面 {@link ChamberBindingConfigScreen} 编辑本仓的
 * 「配方类型 + 名字」；本仓当前绑定由 S2C {@link SyncChamberBindingPacket} 同步
 * （容器打开时发送一次、确认修改后服务端回发一次），在背包标签带处渲染展示。</p>
 * <p><b>本界面已无磁盘槽（用户决定）</b>：中间产物的磁盘改插在「中间产物缓存仓」
 * （{@code intermediate_cache}）里，网络内所有执行舱共用那一份池子；因此面板高度恢复成
 * 引入磁盘槽之前的 176×222（原版 {@code generic_54.png} 整图直出，<b>不新画任何背景贴图</b>）。
 * 需要放 / 换盘请打开缓存仓界面。</p>
 * <p>硬规则：文字全部 {@code drawString(..., false)}（含按钮标签，见 {@link NoShadowButton}）；
 * 自绘元素（配置按钮、绑定状态行）的 tooltip 全部手动渲染，hover 判定与绘制范围同源。</p>
 */
public class SequenceExecutionChamberScreen extends AbstractContainerScreen<SequenceExecutionChamberMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final String LANG = "gui.rs_create_compat.sequence_execution_chamber.";

    /** 面板尺寸：原版双箱整图（54 格样板槽 + 玩家背包 + 快捷栏）。 */
    private static final int PANEL_H = 222;

    /** 「配置」按钮（标题行右侧，Menu 坐标）。
     *  <p>y=4 / 高 13：原版 generic_54 背景的容器边框是 4px（0..3），按钮下移 1px 且高度收 1px，
     *  既不压边框，也不碰到 y=17 起的第一行槽位框。</p> */
    private static final int CONFIG_BTN_X = 122;
    private static final int CONFIG_BTN_Y = 4;
    private static final int CONFIG_BTN_W = 26;
    private static final int CONFIG_BTN_H = 13;
    /** 「面配置」按钮：紧挨「配置」右侧（标题行内，仍不压容器边框与首行槽位）。 */
    private static final int FACE_BTN_X = 150;
    private static final int FACE_BTN_Y = 4;
    private static final int FACE_BTN_W = 22;
    private static final int FACE_BTN_H = 13;
    /** 标题可占用的最大像素宽（到配置按钮左侧再留 2px）。 */
    private static final int TITLE_MAX_W = CONFIG_BTN_X - 8 - 2;
    /** 标题颜色：黑色（浅色面板上白字看不清，全模组机器标题统一口径）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    /**
     * 绑定状态<b>单行横排</b>（玩家背包标签带，Menu 坐标）。
     * <p><b>为什么这么排</b>：本界面上方 6 行单元样板槽框占 17..124，玩家背包槽框自 139 起，
     * 中间只有 125..138 这条 14px 留白带。旧实现把配方类型用 0.5 缩放小字放 126、名字放 131，
     * 用户实测「配方类型那么小一个字谁看得清」。现在改为<b>单行横排</b>：配方类型与名字并排在
     * y=128 这一行，一律用原版字号（行高 9px，占 128..136），既不越出留白带、也不遮挡任何槽位。</p>
     */
    private static final int STATUS_X = 8;
    private static final int STATUS_Y = 128;
    /** 单行高度 = 原版字号 {@code font.lineHeight}。 */
    private static final int STATUS_ROW_H = 9;
    private static final int STATUS_MAX_W = 160;

    private Button configButton;

    public SequenceExecutionChamberScreen(final SequenceExecutionChamberMenu menu,
                                          final Inventory inventory,
                                          final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        // 176×222：原版 generic_54.png 整图直出（磁盘槽移除后面板高度恢复原样）
        this.imageHeight = PANEL_H;
        this.titleLabelY = 5;
        this.inventoryLabelY = 10000; // 该带改由本类渲染「绑定状态」
    }

    @Override
    protected void init() {
        super.init();
        // 注意：这里**不能**清空 SyncChamberBindingPacket 缓存。服务端在 initMenu（先于
        // ClientboundOpenScreenPacket）就发了同步包，客户端往往在界面 init 之前已经收到；
        // 早期版本在此清缓存会把刚同步到的值抹掉，界面于是永远停在「绑定同步中…」。
        configButton = addRenderableWidget(new NoShadowButton(
            leftPos + CONFIG_BTN_X, topPos + CONFIG_BTN_Y, CONFIG_BTN_W, CONFIG_BTN_H,
            Component.translatable(LANG + "config"), button -> openBindingConfig()));
        addRenderableWidget(new NoShadowButton(
            leftPos + FACE_BTN_X, topPos + FACE_BTN_Y, FACE_BTN_W, FACE_BTN_H,
            Component.translatable(LANG + "face.button"), button -> openFaceConfig()));
    }

    /** 打开「面配置」子界面：逐面设置原料输入 / 产物输出 / 中间产物输出 + 切换输出模式（含总线输出）。 */
    private void openFaceConfig() {
        if (minecraft == null) {
            return;
        }
        final SyncChamberBindingPacket sync = SyncChamberBindingPacket.getLastReceived();
        minecraft.setScreen(new ChamberFaceConfigScreen(this,
            sync == null ? null : sync.pos(),
            sync == null ? java.util.List.of() : sync.faceModes(),
            sync == null ? 0 : sync.outputMode()));
    }

    /** 打开绑定配置子界面：整份权威快照（含链状态）交给子界面；尚未同步到时也允许打开（服务端按当前打开的菜单解析执行仓）。 */
    private void openBindingConfig() {
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new ChamberBindingConfigScreen(this, SyncChamberBindingPacket.getLastReceived()));
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        // 原版双箱整图直出：单元样板槽 + 绑定状态带 + 玩家背包 + 快捷栏（与引入磁盘槽之前逐像素一致）
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 标题 = 本执行仓的名字（未命名时回落到菜单标题，即方块默认名「序列执行仓」）；
        // 太长按像素截断成「…」（完整名字放 tooltip）。名字只在这里显示，配方类型行不再重复。
        guiGraphics.drawString(font, Component.literal(trimToWidth(font, screenTitle(), TITLE_MAX_W)),
            titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 绑定状态：单行横排（配方类型 + 链台数后缀；占 128..136，不与任何槽位重叠）
        guiGraphics.drawString(font, statusLine(), STATUS_X, STATUS_Y, 0x404040, false);
    }

    /** 界面标题：优先本执行仓的名字；未命名（或尚未同步到）时用菜单标题（方块名「序列执行仓」）。 */
    private String screenTitle() {
        final SyncChamberBindingPacket sync = SyncChamberBindingPacket.getLastReceived();
        if (sync != null && sync.name() != null && !sync.name().isEmpty()) {
            return sync.name();
        }
        return title.getString();
    }

    /**
     * 绑定状态单行文案：<b>配方类型</b>（{@code 配方类型：X}，未绑定时显示「配方类型：未绑定」），
     * 串了链时再补一段「链 N 台 · 指向 X」后缀（完整链信息在 tooltip 里，不放这一行）。
     * <p>名字已经独占标题栏，这里再重复一遍只会挤占本就只有 14px 的留白带，故移除。</p>
     */
    private Component statusLine() {
        final Component base = recipeTypeLine();
        final SyncChamberBindingPacket sync = SyncChamberBindingPacket.getLastReceived();
        if (sync == null || !sync.chained()) {
            return base;
        }
        return Component.empty().append(base).append(" ")
            .append(Component.translatable(LANG + "chain.suffix.directed",
                sync.chainSize(), linkDirectionName(sync)));
    }

    /** 本台的「链指向」方向名（未指向 / 越界 → {@code —}）；链上指向即「朝哪一台」。 */
    private static Component linkDirectionName(final SyncChamberBindingPacket sync) {
        final Direction direction = sync.linkOrdinal() >= 0 && sync.linkOrdinal() < Direction.values().length
            ? Direction.from3DDataValue(sync.linkOrdinal()) : null;
        return direction == null
            ? Component.literal("—")
            : Component.translatable(LANG + "face.dir." + direction.getName());
    }

    /** 配方类型文案（链上取的是链首那一份，因此链上各台显示完全一致）。 */
    private Component recipeTypeLine() {
        final SyncChamberBindingPacket sync = SyncChamberBindingPacket.getLastReceived();
        if (sync == null) {
            return Component.translatable(LANG + "syncing");
        }
        final String rawType = sync.recipeType();
        return rawType == null || rawType.isEmpty()
            ? Component.translatable(LANG + "recipe_type.unbound")
            : Component.translatable(LANG + "recipe_type", RecipeTypeNames.display(rawType));
    }

    /**
     * 链归属 tooltip：本台属于哪条链 / 链头是哪台 / 本台朝向哪一侧、名字与配方类型整链共享。
     * 链方向来自方块朝向（放置时那支箭头），玩家不需要也无法另行配置。
     * 未串链时明确写「独立」，避免玩家误以为「没显示 = 坏了」。
     */
    private List<Component> chainTooltipLines() {
        final List<Component> lines = new ArrayList<>();
        final SyncChamberBindingPacket sync = SyncChamberBindingPacket.getLastReceived();
        if (sync == null) {
            return lines;
        }
        if (!sync.chained()) {
            lines.add(Component.translatable(LANG + "chain.tip.standalone")
                .withStyle(ChatFormatting.GRAY));
            return lines;
        }
        final BlockPos headPos = sync.headPos();
        final String headText = sync.headName() == null || sync.headName().isEmpty()
            ? Component.translatable(LANG + "chain.head.unknown").getString()
            : sync.headName();
        final String headDesc = sync.chainHead()
            ? Component.translatable(LANG + "chain.head.self").getString()
            : (headPos == null ? headText
                : headText + " (" + headPos.getX() + ", " + headPos.getY() + ", " + headPos.getZ() + ")");
        lines.add(Component.translatable(LANG + "chain.tip.head", headDesc)
            .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(LANG + "chain.tip.link", linkDirectionName(sync))
            .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(LANG + "chain.tip.shared")
            .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        // 「配置」始终可点：同步值只是用于回填初始值，不是打开配置界面的前置条件
        // （写回由服务端按「当前打开的执行仓菜单」解析，未同步到也能正常填写 / 提交）
        if (configButton != null) {
            configButton.active = true;
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // AbstractContainerScreen 不自动渲染槽位 tooltip（原版只处理控件登记的 deferred tooltip），
        // 必须手动调用，否则 54 格单元样板槽悬停时没有任何物品提示
        renderTooltip(guiGraphics, mouseX, mouseY);
        renderSptTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 自绘元素 tooltip（判定范围与绘制范围严格一致，左闭右开）。
     *  <p>本模组附加的说明统一走唯一实现 {@link RsccTooltipLayers}：默认收起，按住对应键才展开；
     *  标题 / 状态文字等<b>身份信息</b>保持常显。</p> */
    private void renderSptTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (isHoveringRect(CONFIG_BTN_X, CONFIG_BTN_Y, CONFIG_BTN_W, CONFIG_BTN_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "config.tip"), mouseX, mouseY);
            return;
        }
        if (isHoveringRect(FACE_BTN_X, FACE_BTN_Y, FACE_BTN_W, FACE_BTN_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "face.button.tip"), mouseX, mouseY);
            return;
        }
        // 标题（名字）：完整名字常显 + 说明按 Shift 层展开（标题太长时绘制成「…」，hover 才能看到全名）
        if (isHoveringRect(titleLabelX, titleLabelY, TITLE_MAX_W, STATUS_ROW_H, mouseX, mouseY)) {
            RsccTooltipLayers.render(guiGraphics, font,
                java.util.List.of(Component.literal(screenTitle())),
                java.util.List.of(RsccTooltipLayers.shift(Component.translatable(LANG + "title.tip"))),
                mouseX, mouseY);
            return;
        }
        // 配方类型 / 链状态行：悬停判定与绘制范围同源（宽度取实际文字宽，封顶 STATUS_MAX_W）
        if (SyncChamberBindingPacket.getLastReceived() != null) {
            final Component line = statusLine();
            final int width = Math.min(font.width(line), STATUS_MAX_W);
            if (isHoveringRect(STATUS_X, STATUS_Y, width, STATUS_ROW_H, mouseX, mouseY)) {
                // 状态行本身 = 身份/状态（常显）；链状态明细 = Shift；配方类型说明 = Shift。
                final List<RsccTooltipLayers.Line> layered = new ArrayList<>(chainTooltipLines().size() + 1);
                for (final Component chainLine : chainTooltipLines()) {
                    layered.add(RsccTooltipLayers.shift(chainLine));
                }
                layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "recipe_type.tip")));
                RsccTooltipLayers.render(guiGraphics, font, java.util.List.of(line), layered, mouseX, mouseY);
            }
        }
    }

    private boolean isHoveringRect(final int x, final int y, final int w, final int h,
                                   final double mouseX, final double mouseY) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + w
            && mouseY >= topPos + y && mouseY < topPos + y + h;
    }

    /** 按像素宽截断字符串（尾部补省略号）。 */
    private static String trimToWidth(final Font font, final String text, final int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String s = text;
        while (s.length() > 1 && font.width(s + "...") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 无阴影文字的原版按钮（DEFAULT_NARRATION 是 protected static，只能在 Button 子类里引用）。 */
    private static final class NoShadowButton extends Button {
        private NoShadowButton(final int x, final int y, final int width, final int height,
                               final Component message, final OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        public void renderString(final GuiGraphics guiGraphics, final Font font, final int color) {
            guiGraphics.drawString(font, getMessage(),
                getX() + (getWidth() - font.width(getMessage())) / 2,
                getY() + (getHeight() - 8) / 2, color, false);
        }
    }
}
