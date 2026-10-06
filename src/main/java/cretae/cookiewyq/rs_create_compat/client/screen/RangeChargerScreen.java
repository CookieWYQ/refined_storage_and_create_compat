package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.RepeatButton;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 范围充电器界面：能量条（带框）+ 三轴范围（输入框编辑 / 加减按钮，shift ±5）
 * + 右侧插件槽（速度升级提升充电速率）+ 玩家背包。
 */
public class RangeChargerScreen extends AbstractContainerScreen<RangeChargerMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/range_charger.png");

    private final EditBox[] rangeBoxes = new EditBox[3];
    private final RepeatButton[] stepButtons = new RepeatButton[6];
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private RsccRedstoneModeButton.RsccRedstoneModeProperty rscc$redstoneMode;
    /** 上次本地修改范围的 tick（快速点击时防止服务端同步值覆盖输入框，造成"反应慢"）。 */
    private long lastLocalEditTick = -1000;

    // ===== 文字坐标与可用宽度（超出即按像素截断，保证不压相邻文字 / 右侧升级槽 / 面板边） =====
    /** 标题颜色：黑色（浅底上白字看不清，与定量保持器一致）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int UPGRADES_LABEL_X = 168;
    /** 右侧升级槽首列背景单元左边界为 x=187（Menu 坐标）。 */
    private static final int UPGRADES_LABEL_MAX_W = 187 - UPGRADES_LABEL_X;
    private static final int TARGETS_X = 8;
    /** 同行右侧「充电速率」从 x=96 起，故左侧留给「目标数」的宽度留 2px 间隔。 */
    private static final int TARGETS_MAX_W = 96 - TARGETS_X - 2;
    private static final int RATE_X = 96;
    private static final int RATE_MAX_W = 187 - RATE_X;
    /** 左下两段数值文字的基线 y（悬停 tooltip 的判定范围与绘制范围共用这一行）。 */
    private static final int LABEL_ROW_Y = 72;
    /** 单行文字的判定高度（原版字体 9px）。 */
    private static final int LABEL_ROW_H = 9;

    public RangeChargerScreen(final RangeChargerMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧插件栏（仿 RS）
        this.imageHeight = 166;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // 能量条（照搬 RS 控制器 ProgressWidget：16x51 与 XYZ 三行高度齐平 + 悬浮显示能量数值）
        addRenderableWidget(new com.refinedmods.refinedstorage.common.support.widget.ProgressWidget(
            leftPos + 26, topPos + 20, 16, 51,
            () -> menu.getMaxEnergy() <= 0 ? 0D : (double) menu.getEnergy() / menu.getMaxEnergy(),
            () -> java.util.List.of(Component.literal(menu.getEnergy() + " / " + menu.getMaxEnergy() + " FE"))
        ));
        // 三行范围：标签 + 输入框 + [-] [+]（无限范围升级时不隐藏 XYZ 输入，只放开上限可随意调大）
        addRangeRow(20, 0);
        addRangeRow(37, 1);
        addRangeRow(54, 2);
        // 红石模式（忽略 / 高电平 / 低电平）：复用 RS 原版侧边按钮，挂在面板左侧外缘
        rscc$redstoneMode = RsccRedstoneModeButton.attach(this);
    }

    /** 红石模式由服务端权威值驱动显示（控件本体复用 RS 原版，hover 时才手动渲染 tooltip）。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (rscc$redstoneMode != null) {
            rscc$redstoneMode.set(menu.rscc$getRedstoneMode().ordinal());
        }
    }

    private void addRangeRow(final int y, final int axis) {
        // 布局：标签 + [-] 输入框 [+] 一字排开，输入框中心 = 两按钮中点
        final EditBox box = new EditBox(font, leftPos + 86, topPos + y + 1, 40, 12, Component.literal("range"));
        // 输入框长度放宽到 10 位：放入无限范围升级后上限取消，可显示/输入极大值
        box.setMaxLength(10);
        box.setValue(Integer.toString(rangeValue(axis)));
        box.setResponder(text -> {
            if (!text.matches("\\d*")) {
                box.setValue(text.replaceAll("[^\\d]", ""));
            }
            // 仅用户聚焦输入时标记本地编辑；初始化/同步 setValue 不标记，保证服务端值能回填
            if (box.isFocused()) {
                markLocalEdit();
                applyRangeIfValid(axis);
            }
        });
        rangeBoxes[axis] = box;
        addRenderableWidget(box);
        // ± 按钮点击立即生效（shift ±5），按住持续重复；上限跟随 menu.getMaxRange()（放入无限升级后为极大值）
        final RepeatButton minus = new RepeatButton(leftPos + 64, topPos + y, 20, 14, Component.literal("-"), btn -> {
            markLocalEdit();
            rangeBoxes[axis].setValue(Integer.toString(Math.max(1, rangeValue(axis) - (hasShiftDown() ? 5 : 1))));
            applyRangeIfValid(axis);
        });
        stepButtons[axis * 2] = minus;
        addRenderableWidget(minus);
        final RepeatButton plus = new RepeatButton(leftPos + 128, topPos + y, 20, 14, Component.literal("+"), btn -> {
            markLocalEdit();
            final int current = rangeValue(axis);
            final int step = hasShiftDown() ? 5 : 1;
            rangeBoxes[axis].setValue(Integer.toString(Math.min(menu.getMaxRange(),
                current > Integer.MAX_VALUE - step ? Integer.MAX_VALUE : current + step)));
            applyRangeIfValid(axis);
        });
        stepButtons[axis * 2 + 1] = plus;
        addRenderableWidget(plus);
    }

    /** 标记一次本地编辑（防止服务端同步值覆盖输入框）。 */
    private void markLocalEdit() {
        lastLocalEditTick = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0;
    }

    /** 应用单个轴（± 按钮 / 输入框编辑共用）。 */
    private void applyRangeIfValid(final int axis) {
        final EditBox box = rangeBoxes[axis];
        if (box == null || !box.getValue().matches("\\d+")) {
            return;
        }
        final int value;
        try {
            value = Integer.parseInt(box.getValue());
        } catch (final NumberFormatException e) {
            return;
        }
        if (value < 1 || value > menu.getMaxRange()) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.SetRangePacket(menu.containerId, axis, value));
    }

    private int rangeValue(final int axis) {
        // 优先取输入框当前值（连续点击 / 按住重复时基于当前值累加，不卡顿）
        final EditBox box = rangeBoxes[axis];
        if (box != null) {
            final String text = box.getValue();
            if (text.matches("\\d+")) {
                return Integer.parseInt(text);
            }
        }
        return serverValue(axis);
    }

    /** 服务端权威值（数据槽同步），输入框与其对比用于回填。 */
    private int serverValue(final int axis) {
        return switch (axis) {
            case 0 -> menu.getRangeX();
            case 1 -> menu.getRangeY();
            default -> menu.getRangeZ();
        };
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter：触发输入框即时应用
            for (int axis = 0; axis < 3; axis++) {
                if (rangeBoxes[axis] != null && rangeBoxes[axis].isFocused()) {
                    applyRangeIfValid(axis);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        // 背景包含全部槽位与能量条底槽；能量进度由 ProgressWidget 绘制（照搬 RS 控制器）
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 166);
        // 能量条外框（16x51，与 ProgressWidget 位置一致）：深色边框 + 内部深底，仿 RS 控制器背景框
        final int ex = leftPos + 26;
        final int ey = topPos + 20;
        guiGraphics.fill(ex - 1, ey - 1, ex + 17, ey + 52, 0xFF000000);
        guiGraphics.fill(ex, ey, ex + 16, ey + 51, 0xFF2C2C2C);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 轴标签：XYZ（放入无限范围升级也不隐藏，只是取消上限可随意调大）
        drawLabel(guiGraphics, 20, "X");
        drawLabel(guiGraphics, 37, "Y");
        drawLabel(guiGraphics, 54, "Z");
        // 插件栏标题（左移，避免与插件槽重叠；英文串过长时按像素截断，绝不压到 x=187 起的槽位）
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(font,
                Component.translatable("gui.rs_create_compat.range_charger.upgrades").getString(),
                UPGRADES_LABEL_MAX_W)),
            UPGRADES_LABEL_X, 4, 0xFFFFFF, false);
        // 左下：当前充电对象数 + 充电速率（同一行，速率在右边；能量数值由能量条悬浮 tooltip 显示）
        // 两条文字各有固定可用宽度，超长（尤其英文）时截断，保证彼此不重叠、也不压右侧升级槽
        final int targets = menu.getTargetCount();
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(font,
                Component.translatable("gui.rs_create_compat.range_charger.targets", targets).getString(),
                TARGETS_MAX_W)),
            TARGETS_X, LABEL_ROW_Y, 0xFFFFFF, false);
        // 速率随速度升级逐级翻倍，很容易长到十位数 —— 截断后根本读不出到底是多少，
        // 因此完整数值与构成放到悬停 tooltip 里（见 renderValueTooltips）。
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(font,
                Component.translatable("gui.rs_create_compat.range_charger.rate", chargeRate()).getString(),
                RATE_MAX_W)),
            RATE_X, LABEL_ROW_Y, 0xFFFFFF, false);
    }

    /** 当前充电速率（FE/t）：基础值按已装速度升级数量逐级翻倍（与充电逻辑同源）。 */
    private int chargeRate() {
        return cretae.cookiewyq.rs_create_compat.Config.rangeChargerChargeRate
            << menu.getUpgradeCount();
    }

    /** 判定点是否落在某一行的绘制范围内（判定范围与绘制范围同源，避免「看得到却悬停不到」）。 */
    private boolean isOverLabelRow(final int x, final int maxWidth,
                                   final double mouseX, final double mouseY) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + maxWidth
            && mouseY >= topPos + LABEL_ROW_Y && mouseY < topPos + LABEL_ROW_Y + LABEL_ROW_H;
    }

    /**
     * 左下两段数值文字的 tooltip：这两行按固定可用宽度截断（否则会压到相邻文字 / 右侧插件槽），
     * 速率很大时截断串（「充电速率：16384000…」）无法读出真实值，故悬停时给出<b>完整</b>数值 ——
     * 速率还附带「基础值 + 速度升级级数」的构成说明，玩家据此知道还能不能再加升级。
     */
    private void renderValueTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (hoveredSlot != null) {
            return; // 槽位提示优先，同一处绝不叠两个框
        }
        // 两段数值都是本模组附加信息：数值归 Ctrl 层，速率构成说明归 Shift 层
        // （统一走唯一实现 RsccTooltipLayers：默认收起，按住对应键才展开）。
        if (isOverLabelRow(TARGETS_X, TARGETS_MAX_W, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    "gui.rs_create_compat.range_charger.targets", menu.getTargetCount()), mouseX, mouseY);
            return;
        }
        if (isOverLabelRow(RATE_X, RATE_MAX_W, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                guiGraphics, font, java.util.List.of(),
                java.util.List.of(
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                        Component.translatable("gui.rs_create_compat.range_charger.rate.tip",
                            cretae.cookiewyq.rs_create_compat.Config.rangeChargerChargeRate,
                            menu.getUpgradeCount())),
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.ctrl(
                        Component.translatable("gui.rs_create_compat.range_charger.rate",
                            chargeRate()))),
                mouseX, mouseY);
        }
    }

    /** 按像素宽截断字符串（尾部补省略号）：保证文字不越过相邻文字 / 控件 / 槽位。 */
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

    private void drawLabel(final GuiGraphics guiGraphics, final int y, final String text) {
        guiGraphics.drawString(font, Component.literal(text), 52, y + 3, 0xFFFFFF, false);
    }

    /** 输入框与服务端值同步（未聚焦且非快速本地点击期间）。无限范围时禁用输入与按钮并显示 ∞。 */
    private void syncRangeBoxes() {
        final boolean infinite = menu.hasInfiniteRange();
        for (int axis = 0; axis < 3; axis++) {
            final EditBox box = rangeBoxes[axis];
            if (box != null) {
                box.active = !infinite;
                if (infinite) {
                    if (!box.getValue().equals("∞")) {
                        box.setValue("∞"); // 只读展示
                    }
                } else if (!box.isFocused()
                    && !box.getValue().equals(Integer.toString(serverValue(axis)))
                    && (minecraft == null || minecraft.level == null
                        || minecraft.level.getGameTime() - lastLocalEditTick >= 10)) {
                    box.setValue(Integer.toString(serverValue(axis)));
                }
            }
            final RepeatButton minus = stepButtons[axis * 2];
            final RepeatButton plus = stepButtons[axis * 2 + 1];
            if (minus != null) {
                minus.active = !infinite;
            }
            if (plus != null) {
                plus.active = !infinite;
            }
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        final boolean infinite = menu.hasInfiniteRange();
        // 按住 ± 持续触发（tick 为 final 不可覆写，改由渲染帧驱动）
        if (!infinite) {
            for (final RepeatButton button : stepButtons) {
                if (button != null) {
                    button.tickRepeat();
                }
            }
        }
        syncRangeBoxes();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (!infinite) {
            renderHoverTooltips(guiGraphics, mouseX, mouseY);
        }
        // 左下两段数值文字的完整值提示（无限范围时同样可用：文字仍照常渲染）
        renderValueTooltips(guiGraphics, mouseX, mouseY);
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用（升级槽 tooltip 由此触发）
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** ± 按钮 shift 加速提示（hover 判定直接使用按钮自身的命中范围，保证与绘制范围一致）。 */
    private void renderHoverTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        for (final RepeatButton button : stepButtons) {
            if (button != null && button.isMouseOver(mouseX, mouseY)) {
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                    guiGraphics, font,
                    Component.translatable("gui.rs_create_compat.range_charger.step"), mouseX, mouseY);
                return;
            }
        }
    }

    /** 空升级槽悬停提示（样式照搬 RS 原版，按充电器允许列表渲染）。 */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot instanceof cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot upgradeSlot
            && hoveredSlot.getItem().isEmpty()
            && menu.getCarried().isEmpty()) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips.render(
                upgradeSlot, guiGraphics, font, mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
