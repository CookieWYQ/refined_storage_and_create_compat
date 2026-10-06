package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.RepeatButton;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import cretae.cookiewyq.rs_create_compat.network.SetSequenceResultPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 序列装配样板终端「产物 / 废料条目」子窗口：一次性设置<b>产出概率 + 产出数量</b>。
 * <p>此前只能用滚轮按 10% 一档改概率、且完全不能设置数量；现在改为在子窗口里用两个
 * <b>− → 输入框 → +</b> 的行直接编辑（Shift 一次 ±5 / ±10，输入框可直接输入，自动夹到合法区间）。</p>
 * <p>面板背景走基类 {@link ChildConfigScreen#renderPanel}（原版九宫格精灵 = MC 自带风格），
 * 与工程其它子窗口完全一致；所有文字无阴影，tooltip 手动渲染且 hover 判定与绘制范围同源。</p>
 */
public class SequenceResultConfigScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.sequence_pattern_terminal.";

    private static final int PANEL_W = 190;
    private static final int PANEL_H = 132;

    /** 自绘面板 PNG（{@code CHILD_PANELS_DELIVERY.md} 约定，190×132）；缺失时退回原版九宫格。 */
    private static final ResourceLocation CUSTOM_PANEL =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "textures/gui/spt/result_config.png");

    @Override
    @Nullable
    protected ResourceLocation customPanel() {
        return CUSTOM_PANEL;
    }

    private static final int PAD = 12;
    private static final int TITLE_Y = 10;
    private static final int CHANCE_ROW_Y = 34;
    private static final int AMOUNT_ROW_Y = 58;
    private static final int ROW_H = 16;
    private static final int LABEL_W = 56;
    private static final int MINUS_X = PAD + LABEL_W;
    private static final int BOX_X = MINUS_X + 20;
    private static final int BOX_W = 44;
    private static final int PLUS_X = BOX_X + BOX_W + 2;
    private static final int BTN_W = 20;
    private static final int ACTION_Y = 104;
    private static final int ACTION_W = 60;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;

    private final SequencePatternTerminalMenu menu;
    private final boolean scrap;
    private final int windowIndex;
    private final ItemStack icon;
    private final FluidStack fluidIcon;

    private EditBox chanceBox;
    private EditBox amountBox;
    private final RepeatButton[] buttons = new RepeatButton[4];

    public SequenceResultConfigScreen(final Screen parent, final SequencePatternTerminalMenu menu,
                                      final boolean scrap, final int windowIndex,
                                      final int chancePercent, final int amount,
                                      final ItemStack icon, final FluidStack fluidIcon) {
        super(Component.translatable(LANG + (scrap ? "scrap.config.title" : "result.config.title")),
            parent, PANEL_W, PANEL_H);
        this.menu = menu;
        this.scrap = scrap;
        this.windowIndex = windowIndex;
        this.icon = icon == null ? ItemStack.EMPTY : icon;
        this.fluidIcon = fluidIcon == null ? FluidStack.EMPTY : fluidIcon;
        this.initialChance = Math.max(0, Math.min(100, chancePercent));
        this.initialAmount = Math.max(1, Math.min(64, amount));
    }

    private final int initialChance;
    private final int initialAmount;

    @Override
    protected void init() {
        super.init();
        // 概率行（%）：− → 输入框 → +
        chanceBox = numberBox(CHANCE_ROW_Y, Integer.toString(initialChance), 3);
        amountBox = numberBox(AMOUNT_ROW_Y, Integer.toString(initialAmount), 2);

        buttons[0] = new RepeatButton(px + MINUS_X, py + CHANCE_ROW_Y, BTN_W, ROW_H,
            Component.literal("-"), b -> step(chanceBox, -1, 0, 100));
        buttons[1] = new RepeatButton(px + PLUS_X, py + CHANCE_ROW_Y, BTN_W, ROW_H,
            Component.literal("+"), b -> step(chanceBox, 1, 0, 100));
        buttons[2] = new RepeatButton(px + MINUS_X, py + AMOUNT_ROW_Y, BTN_W, ROW_H,
            Component.literal("-"), b -> step(amountBox, -1, 1, 64));
        buttons[3] = new RepeatButton(px + PLUS_X, py + AMOUNT_ROW_Y, BTN_W, ROW_H,
            Component.literal("+"), b -> step(amountBox, 1, 1, 64));
        for (final RepeatButton button : buttons) {
            addRenderableWidget(button);
        }

        addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "result.config.confirm"), b -> confirmAndReturn())
            .bounds(px + PAD, py + ACTION_Y, ACTION_W, 18).build());
        addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "result.config.cancel"), b -> returnToParent())
            .bounds(px + PAD + ACTION_W + 4, py + ACTION_Y, ACTION_W, 18).build());
    }

    private EditBox numberBox(final int rowY, final String initial, final int maxLength) {
        final EditBox box = new EditBox(font, px + BOX_X, py + rowY, BOX_W, ROW_H, Component.empty());
        box.setMaxLength(maxLength);
        box.setTextShadow(false);
        box.setValue(initial);
        box.setResponder(text -> {
            if (!text.matches("\\d*")) {
                box.setValue(text.replaceAll("[^\\d]", ""));
            }
        });
        addRenderableWidget(box);
        return box;
    }

    /** ± 按钮：Shift 快速增减（概率 ±5 / 数量 ±10），任何情况下都夹在合法区间内（不超限）。 */
    private void step(final EditBox box, final int direction, final int min, final int max) {
        if (box == null) {
            return;
        }
        final int big = max > 10 ? 5 : 10;
        final int delta = direction * (hasShiftDown() ? big : 1);
        final int current = value(box, min);
        box.setValue(Integer.toString(Math.max(min, Math.min(max, current + delta))));
    }

    private int value(final EditBox box, final int fallback) {
        if (box != null && box.getValue().matches("\\d+")) {
            try {
                return Integer.parseInt(box.getValue());
            } catch (final NumberFormatException ignored) {
                // 落到 fallback
            }
        }
        return fallback;
    }

    private void confirmAndReturn() {
        final int percent = Math.max(0, Math.min(100, value(chanceBox, initialChance)));
        final int amount = Math.max(1, Math.min(64, value(amountBox, initialAmount)));
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetSequenceResultPacket(menu.containerId, scrap, windowIndex, percent, amount));
        returnToParent();
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        for (final RepeatButton button : buttons) {
            if (button != null) {
                button.tickRepeat();
            }
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        // 被配置的条目图标（物品或流体）
        if (!fluidIcon.isEmpty()) {
            GhostMarkerRenderer.renderFluid(guiGraphics, px + PANEL_W - PAD - 16, py + TITLE_Y - 4, fluidIcon);
        } else if (!icon.isEmpty()) {
            guiGraphics.renderItem(icon, px + PANEL_W - PAD - 16, py + TITLE_Y - 4);
        }
        // 两行标签（用户要求：删掉「0-100%」「1-64」这类超出背景 / 没必要的区间提示文本）
        guiGraphics.drawString(font, Component.translatable(LANG + "result.config.chance"),
            px + PAD, py + CHANCE_ROW_Y + 4, COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "result.config.amount"),
            px + PAD, py + AMOUNT_ROW_Y + 4, COLOR_TEXT, false);

        // ± 按钮 tooltip（hover 判定用按钮自身范围，与绘制范围一致）
        for (final RepeatButton button : buttons) {
            if (button != null && button.isMouseOver(mouseX, mouseY)) {
                RsccTooltipLayers.renderAttached(guiGraphics, font,
                    Component.translatable(LANG + "result.config.step"), mouseX, mouseY);
                return;
            }
        }
        // 概率行 tooltip：用 Create JEI 的同一套换算 / 格式化显示当前输入值（数字与 JEI 完全一致）
        if (mouseX >= px + PAD && mouseX < px + PLUS_X + BTN_W
            && mouseY >= py + CHANCE_ROW_Y && mouseY < py + CHANCE_ROW_Y + ROW_H) {
            final int percent = Math.max(0, Math.min(100, value(chanceBox, initialChance)));
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                    .chanceComponent(percent / 100F), mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
