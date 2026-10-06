package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionInputFacePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 归流缓存仓「输入面配置」子界面 —— 用户需求「序列装配回流器需要可以配置多个的输入」的落地交互。
 * <p><b>语义（与序列执行仓的面配置同一套交互理念）</b>：本仓的物流输入（漏斗 / 管道塞入）
 * 可以<b>按面逐面开关</b>，因此「输入来源」不止一个、且可由玩家自由增删；
 * 界面沿用执行仓面配置的「方块展开图（十字网）」布局 —— 上 / 西 / 北 / 东 / 南 / 下 六个面各一个
 * 40×40 色块，<b>左键 / 右键 = 开/关该面</b>，颜色即状态（绿 = 该面可输入、灰 = 该面不接受输入）。</p>
 * <p><b>本轮排版调整（与执行仓面配置对齐）</b>：格子由 30×30 + 0.5 缩放小字放大到 <b>40×40</b>，
 * 格内「面名 + 状态短名」改用<b>原版字号</b>并垫一条半透明白底条，不再依赖 tooltip 才能看清；
 * 右侧「图例」逐条给出两档状态的完整名称；细节点仍由 tooltip 给出。
 * 几何与 {@link ChamberFaceConfigScreen} 完全一致（同一套布局常量）。</p>
 * <p><b>硬规则</b>：面板走 {@link ChildConfigScreen#renderPanel} 的原版九宫格（无自绘 PNG）；
 * 所有文字 {@code drawString(..., false)}；tooltip 全部手动渲染，且 hover 判定与绘制范围共用同一批坐标。
 * 状态一律读服务端权威值（菜单数据槽 19），客户端不做本地镜像，避免与服务端不一致。</p>
 */
public class CollectionInputFaceConfigScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.collection_cache.input_face.";

    /** 面板尺寸与执行仓面配置子界面完全一致（250×176，同一套观感）。 */
    private static final int PANEL_W = 250;
    private static final int PANEL_H = 176;

    private static final int TITLE_X = 10;
    private static final int TITLE_Y = 10;

    /** 方块面格子边长与间距（与执行仓面配置一致：40×40、间距 2，格内用原版字号）。 */
    private static final int CELL = 40;
    private static final int CELL_GAP = 2;
    /** 十字网：上（顶）、中间一行 4 面、下（底）。 */
    private static final int NET_MID_X0 = 8;
    /** 顶 / 底单格的 x：让 4 格中间行水平居中。 */
    private static final int NET_UP_X = NET_MID_X0 + 3 * (CELL + CELL_GAP) / 2;
    private static final int NET_UP_Y = 26;
    private static final int NET_MID_Y = NET_UP_Y + CELL + CELL_GAP;
    private static final int NET_DOWN_X = NET_UP_X;
    private static final int NET_DOWN_Y = NET_MID_Y + CELL + CELL_GAP;

    /** 中间一行的四个面（按此顺序绘制）。 */
    private static final Direction[] NET_MID_FACES = {
        Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH
    };

    /** 图例区（右侧，逐条自动折行）。 */
    private static final int LEGEND_X = 178;
    private static final int LEGEND_Y = 34;
    private static final int LEGEND_SWATCH = 8;
    /** 图例文字可用宽度（折行用，绝不越出面板右内边）。 */
    private static final int LEGEND_TEXT_W = 54;
    /** 图例每条之间的最小间距。 */
    private static final int LEGEND_GAP = 3;

    private static final int CLOSE_X = 178;
    private static final int CLOSE_Y = 152;
    private static final int CLOSE_W = 66;
    private static final int CLOSE_H = 16;

    /** 格子内文字底色条（半透明白，保证深色字在任何状态色上都清晰可读）。 */
    private static final int COLOR_TEXT_PLATE = 0x55FFFFFF;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_CELL_TEXT = 0xFF1F1F1F;
    /** 开：绿（可输入）；关：灰（不接受输入）。 */
    private static final int COLOR_ON = 0xFF4CAF50;
    private static final int COLOR_OFF = 0xFF9E9E9E;

    /** 父界面持有的菜单：状态读它、开关也发它（容器 id 用于服务端校验）。 */
    private final CollectionCacheMenu menu;

    public CollectionInputFaceConfigScreen(final Screen parent, final CollectionCacheMenu menu) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.menu = menu;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "close"),
            button -> returnToParent())
            .bounds(px + CLOSE_X, py + CLOSE_Y, CLOSE_W, CLOSE_H).build());
    }

    // ==================== 几何（绘制 / 命中共用同一批坐标） ====================

    private int[] cellRect(final int index) {
        return switch (index) {
            case 0 -> new int[] {NET_UP_X, NET_UP_Y};                 // 顶面（上）
            case 5 -> new int[] {NET_DOWN_X, NET_DOWN_Y};             // 底面（下）
            default -> new int[] {                                   // 中间一行 4 面
                NET_MID_X0 + (index - 1) * (CELL + CELL_GAP), NET_MID_Y};
        };
    }

    private static Direction faceDirection(final int index) {
        return switch (index) {
            case 0 -> Direction.UP;
            case 5 -> Direction.DOWN;
            default -> NET_MID_FACES[index - 1];
        };
    }

    private int cellAt(final double mouseX, final double mouseY) {
        for (int i = 0; i < 6; i++) {
            final int[] rect = cellRect(i);
            if (mouseX >= px + rect[0] && mouseX < px + rect[0] + CELL
                && mouseY >= py + rect[1] && mouseY < py + rect[1] + CELL) {
                return i;
            }
        }
        return -1;
    }

    private boolean isEnabled(final Direction direction) {
        return menu != null && menu.isInputFace(direction);
    }

    private static String directionKey(final Direction direction) {
        return LANG + "dir." + direction.getSerializedName();
    }

    private static int colorOf(final boolean on) {
        return on ? COLOR_ON : COLOR_OFF;
    }

    private static String stateKey(final boolean on) {
        return LANG + (on ? "on" : "off");
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + TITLE_X, py + TITLE_Y, COLOR_TITLE, false);
        // 六个面格子（先铺色块 + 1px 描边，再写面名与状态短名；一律原版字号，直接可读）
        for (int i = 0; i < 6; i++) {
            final int[] rect = cellRect(i);
            final int x = px + rect[0];
            final int y = py + rect[1];
            final Direction direction = faceDirection(i);
            final boolean on = isEnabled(direction);
            guiGraphics.fill(x, y, x + CELL, y + CELL, colorOf(on));
            guiGraphics.fill(x, y, x + CELL, y + 1, 0xFF2B2B2B);
            guiGraphics.fill(x, y + CELL - 1, x + CELL, y + CELL, 0xFF2B2B2B);
            guiGraphics.fill(x, y, x + 1, y + CELL, 0xFF2B2B2B);
            guiGraphics.fill(x + CELL - 1, y, x + CELL, y + CELL, 0xFF2B2B2B);
            // 面名（上）＋ 状态短名（下）：正常字号 + 半透明白底条 → 任何状态色上都清晰
            final Component dirName = Component.translatable(directionKey(direction));
            guiGraphics.fill(x + 1, y + 3, x + CELL - 1, y + 12, COLOR_TEXT_PLATE);
            guiGraphics.drawString(font, dirName, x + (CELL - font.width(dirName)) / 2, y + 4,
                COLOR_CELL_TEXT, false);
            final Component stateName = Component.translatable(stateKey(on));
            guiGraphics.fill(x + 1, y + CELL - 13, x + CELL - 1, y + CELL - 4, COLOR_TEXT_PLATE);
            guiGraphics.drawString(font, stateName, x + (CELL - font.width(stateName)) / 2,
                y + CELL - 12, COLOR_CELL_TEXT, false);
        }
        // 图例：两档状态的颜色 + 完整名称（逐条按像素宽折行，绝不越界、不与下方按钮重叠）
        int legendY = py + LEGEND_Y;
        for (final boolean on : new boolean[] {true, false}) {
            guiGraphics.fill(px + LEGEND_X, legendY,
                px + LEGEND_X + LEGEND_SWATCH, legendY + LEGEND_SWATCH, colorOf(on));
            guiGraphics.fill(px + LEGEND_X, legendY,
                px + LEGEND_X + LEGEND_SWATCH, legendY + 1, 0xFF2B2B2B);
            guiGraphics.fill(px + LEGEND_X, legendY + LEGEND_SWATCH - 1,
                px + LEGEND_X + LEGEND_SWATCH, legendY + LEGEND_SWATCH, 0xFF2B2B2B);
            int textY = legendY;
            for (final net.minecraft.util.FormattedCharSequence line
                : font.split(Component.translatable(stateKey(on)), LEGEND_TEXT_W)) {
                guiGraphics.drawString(font, line, px + LEGEND_X + LEGEND_SWATCH + 4, textY,
                    COLOR_TEXT, false);
                textY += 9;
            }
            legendY = Math.max(legendY + LEGEND_SWATCH, textY) + LEGEND_GAP;
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** tooltip：面格子（方向 + 当前状态 + 状态说明）；全部手动渲染，判定与绘制同源，**不再堆「怎么点」的说明**。
     *  <p>方向名 = 身份信息（常显）；状态语义 = Shift 层；当前开关值 = Ctrl 层
     *  （统一走唯一实现 {@link RsccTooltipLayers}，默认收起）。</p> */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int cell = cellAt(mouseX, mouseY);
        if (cell < 0) {
            return;
        }
        final Direction direction = faceDirection(cell);
        final boolean on = isEnabled(direction);
        RsccTooltipLayers.render(guiGraphics, font,
            List.of(Component.translatable(directionKey(direction))),
            List.of(
                RsccTooltipLayers.shift(Component.translatable(on ? LANG + "state.on.tip"
                    : LANG + "state.off.tip")),
                RsccTooltipLayers.ctrl(Component.translatable(LANG + "current",
                    Component.translatable(stateKey(on))))),
            mouseX, mouseY);
    }

    /** 手动渲染多行 tooltip（本模组的 GUI 不会自动渲染 tooltip）。 */
    private void renderLines(final GuiGraphics guiGraphics, final List<Component> lines,
                             final int mouseX, final int mouseY) {
        final java.util.List<net.minecraft.util.FormattedCharSequence> wrapped =
            new java.util.ArrayList<>(lines.size());
        for (final Component line : lines) {
            wrapped.add(line.getVisualOrderText());
        }
        guiGraphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        final int cell = cellAt(mouseX, mouseY);
        if (cell >= 0 && menu != null) {
            final Direction direction = faceDirection(cell);
            // 左键 / 右键都表示「翻转该面的输入开关」（服务端权威，回包后界面自动刷新）
            PacketDistributor.sendToServer(new SetCollectionInputFacePacket(
                menu.containerId, direction.ordinal(), !isEnabled(direction)));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
