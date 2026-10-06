package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.RepeatButton;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionRadiusPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 归流缓存仓「收集范围」子窗口（三轴 X / Y / Z）。
 * <p>交互与排版刻意对齐工程里既有的 <b>范围充电器</b>（{@link RangeChargerScreen}）：
 * 每行 = 轴标签 + {@code [-]} + <b>可直接输入的输入框</b> + {@code [+]}；
 * <b>Shift + 点击 ± 一次 ±5</b>，按住 ± 连续调整，输入框回车立即应用；
 * 任何输入都会被夹到 {@code 1..上限}（<b>不会超限</b>）。</p>
 * <p>上限 = 基础 16 + 每个「范围升级」25；放入「创造范围升级」后三轴无限（输入框显示 ∞ 并禁用）。</p>
 * <p>服务端权威：数值通过 {@link SetCollectionRadiusPacket} 写入方块实体，界面显示值由数据槽回传。
 * 面板背景走基类 {@link ChildConfigScreen#renderPanel}（原版九宫格精灵 = MC 自带风格）。</p>
 */
public class CollectionRangeConfigScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.collection_cache.";

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 126;

    /** 自绘面板 PNG（{@code COLLECTION_CACHE_GUI_DOC_V4.md} 约定，200×126）；缺失时退回原版九宫格。 */
    private static final ResourceLocation CUSTOM_PANEL =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "textures/gui/collection_cache/range_config.png");

    @Override
    @Nullable
    protected ResourceLocation customPanel() {
        return CUSTOM_PANEL;
    }

    private static final int PAD = 12;
    private static final int TITLE_Y = 10;
    private static final int FIRST_ROW_Y = 32;
    private static final int ROW_STEP = 20;
    private static final int LABEL_X = PAD;
    private static final int MINUS_X = 36;
    private static final int BOX_X = 60;
    private static final int BOX_W = 40;
    private static final int BOX_H = 14;
    private static final int PLUS_X = 104;
    private static final int BTN_W = 20;
    private static final int BTN_H = 14;
    /** 行右侧的「上限」提示文字。 */
    private static final int LIMIT_X = 132;
    private static final int INFO_Y = 94;
    private static final int ACTION_Y = 106;
    private static final int ACTION_W = 60;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF6A6A6A;

    private final CollectionCacheMenu menu;
    private final EditBox[] boxes = new EditBox[3];
    private final RepeatButton[] buttons = new RepeatButton[6];
    /** 上次本地修改的 tick：期间不采纳服务端回传值，避免输入被「回跳」拽回（照搬范围充电器的做法）。 */
    private long lastLocalEditTick = -1000;

    public CollectionRangeConfigScreen(final Screen parent, final CollectionCacheMenu menu) {
        super(Component.translatable(LANG + "range.title"), parent, PANEL_W, PANEL_H);
        this.menu = menu;
    }

    @Override
    protected void init() {
        super.init();
        for (int axis = 0; axis < 3; axis++) {
            final int y = FIRST_ROW_Y + axis * ROW_STEP;
            final int a = axis;
            final EditBox box = new EditBox(font, px + BOX_X, py + y, BOX_W, BOX_H,
                Component.literal("radius" + axis));
            box.setMaxLength(10); // 无限范围升级后可显示/输入极大值
            box.setTextShadow(false);
            box.setValue(Integer.toString(menu.getCollectRadius(axis)));
            box.setResponder(text -> {
                if (!text.matches("\\d*")) {
                    box.setValue(text.replaceAll("[^\\d]", ""));
                }
                if (box.isFocused()) {
                    applyAxis(a);
                }
            });
            boxes[axis] = box;
            addRenderableWidget(box);

            buttons[axis * 2] = new RepeatButton(px + MINUS_X, py + y, BTN_W, BTN_H,
                Component.literal("-"), b -> step(a, -1));
            addRenderableWidget(buttons[axis * 2]);
            buttons[axis * 2 + 1] = new RepeatButton(px + PLUS_X, py + y, BTN_W, BTN_H,
                Component.literal("+"), b -> step(a, 1));
            addRenderableWidget(buttons[axis * 2 + 1]);
        }
        // 关闭按钮：数值即时生效（服务端权威），这里只负责返回父界面
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "range.close"),
            b -> returnToParent())
            .bounds(px + (PANEL_W - ACTION_W) / 2, py + ACTION_Y, ACTION_W, 18).build());
    }

    /** ± 按钮：Shift 一次 ±5，否则 ±1；不超限（自动夹到 1..上限）。 */
    private void step(final int axis, final int direction) {
        final EditBox box = boxes[axis];
        if (box == null) {
            return;
        }
        final int step = hasShiftDown() ? 5 : 1;
        final int current = currentValue(axis);
        final int next = clamp(axis, current + direction * step);
        markLocalEdit();
        box.setValue(Integer.toString(next));
        applyAxis(axis);
    }

    /** 标记一次本地编辑（10 tick 内不采纳服务端回传值）。 */
    private void markLocalEdit() {
        lastLocalEditTick = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0;
    }

    /** 输入框当前值（非法时回落服务端同步值）。 */
    private int currentValue(final int axis) {
        final EditBox box = boxes[axis];
        if (box != null && box.getValue().matches("\\d+")) {
            try {
                return Math.max(1, Integer.parseInt(box.getValue()));
            } catch (final NumberFormatException ignored) {
                // 落到服务端值
            }
        }
        return menu.getCollectRadius(axis);
    }

    /** 夹到合法范围：不超限。 */
    private int clamp(final int axis, final int value) {
        return Math.max(CollectionCacheBlockEntity.MIN_COLLECT_RADIUS,
            Math.min(menu.getMaxCollectRadius(), value));
    }

    /** 应用某一轴（输入框 / ± 共用）：非法或超限直接忽略，合法值发服务端。 */
    private void applyAxis(final int axis) {
        final EditBox box = boxes[axis];
        if (box == null || !box.getValue().matches("\\d+")) {
            return;
        }
        final int value;
        try {
            value = Integer.parseInt(box.getValue());
        } catch (final NumberFormatException e) {
            return;
        }
        final int clamped = clamp(axis, value);
        markLocalEdit();
        if (value > menu.getMaxCollectRadius() && !menu.hasInfiniteRange()) {
            // 超限：把输入框直接改回上限，给玩家明确反馈（而不是静默丢弃）
            box.setValue(Integer.toString(clamped));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetCollectionRadiusPacket(menu.containerId, axis, clamped));
    }

    /** 每帧同步输入框与服务端值（未聚焦、且非无限范围、且不在本地编辑保护期内时回填）。 */
    private void syncBoxes() {
        final boolean infinite = menu.hasInfiniteRange();
        final boolean protectingLocalEdit = minecraft != null && minecraft.level != null
            && minecraft.level.getGameTime() - lastLocalEditTick < 10;
        for (int axis = 0; axis < 3; axis++) {
            final EditBox box = boxes[axis];
            if (box == null) {
                continue;
            }
            box.active = !infinite;
            if (infinite) {
                if (!"∞".equals(box.getValue())) {
                    box.setValue("∞"); // 只读展示
                }
            } else if (!box.isFocused() && !protectingLocalEdit
                && !box.getValue().equals(Integer.toString(menu.getCollectRadius(axis)))) {
                box.setValue(Integer.toString(menu.getCollectRadius(axis)));
            }
            final RepeatButton minus = buttons[axis * 2];
            final RepeatButton plus = buttons[axis * 2 + 1];
            if (minus != null) {
                minus.active = !infinite;
            }
            if (plus != null) {
                plus.active = !infinite;
            }
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        // 按住 ± 连续调整（tick 为 final 不可覆写，改由渲染帧驱动）
        if (!menu.hasInfiniteRange()) {
            for (final RepeatButton button : buttons) {
                if (button != null) {
                    button.tickRepeat();
                }
            }
        }
        syncBoxes();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 标题 / 轴标签 / 每轴当前值（全部无阴影；按要求不再标注上限，也不说明范围升级数量）
        guiGraphics.drawString(font, title, px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        final String[] axes = {"X", "Y", "Z"};
        final boolean infinite = menu.hasInfiniteRange();
        for (int axis = 0; axis < 3; axis++) {
            final int y = FIRST_ROW_Y + axis * ROW_STEP;
            guiGraphics.drawString(font, Component.literal(axes[axis]), px + LABEL_X, py + y + 3,
                COLOR_TEXT, false);
            guiGraphics.drawString(font, Component.literal(infinite
                    ? "∞"
                    : Integer.toString(menu.getCollectRadius(axis))),
                px + LIMIT_X, py + y + 3, COLOR_DIM, false);
        }
        // ± 按钮的 Shift 加速提示（hover 判定直接用按钮自身命中范围，与绘制范围一致）
        for (final RepeatButton button : buttons) {
            if (button != null && button.isMouseOver(mouseX, mouseY)) {
                // 控制说明属于本模组附加信息：默认收起，按住 Shift 才展开（唯一实现 RsccTooltipLayers）
                RsccTooltipLayers.renderAttached(guiGraphics, font,
                    Component.translatable(LANG + "range.step"), mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
