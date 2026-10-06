package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity.FaceMode;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity.OutputMode;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.network.SetChamberFacePacket;
import cretae.cookiewyq.rs_create_compat.network.SetChamberOutputModePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列执行仓「面配置」子界面（参考通用机械 Mekanism 的机器面配置交互思路）。
 * <p><b>为什么这样设计</b>：Mekanism 的机器是「点方块的六个面，逐面切换 输入 / 输出 / 无」；
 * 本仓需要表达的是「原料从哪面喂给相邻 Create 机、产物 / 中间产物从哪面收回来」，
 * 因此把面模式收敛成 4 档：无 / 原料输入 / 产物输出 / 中间产物输出，交互仍是「点面循环切换」。</p>
 * <p><b>布局</b>：左侧是「方块展开图（十字网）」——上 / 下 / 北 / 南 / 西 / 东 六个方块面各画成一个 40×40
 * 的色块，颜色即工作模式；格内「面名 + 模式短名」一律用<b>原版字号</b>并垫一条半透明白底条，
 * 因此不必靠 tooltip 也能一眼看清（此前是 30×30 + 0.5 缩放小字，用户反馈看不清）。
 * 左键点击色块 = 切换到下一个模式（服务端权威，回包后刷新）。</p>
 * <p><b>本轮排版调整</b>：删除了底部那一大串说明文字（它过长会压住「关闭」按钮），
 * 改为右侧「图例」逐条按像素宽折行显示四档模式的<b>完整名称</b>；模式细节仍由 tooltip 给出，
 * 因此不再出现「文字压按钮」。</p>
 * <p><b>本轮新增：输出模式切换</b>（右上角按钮）——「面输出」按上面六个面的配置工作；
 * 「总线输出（延长型输出）」则<b>整体忽略逐面配置</b>，改由相邻 RS 输出总线把网络里的
 * 输入原料 / 中间产物推给它面对的机器。选总线输出时六个格子会整体压暗并标注「已忽略」。</p>
 * <p><b>硬规则</b>：面板走基类 {@link ChildConfigScreen#renderPanel} 的原版九宫格（MC 自带风格，无自绘 PNG）；
 * 所有文字 {@code drawString(..., false)}；tooltip 全部手动渲染，且 hover 判定与绘制范围共用同一批坐标。</p>
 */
public class ChamberFaceConfigScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.sequence_execution_chamber.";

    private static final int PANEL_W = 250;
    private static final int PANEL_H = 176;

    private static final int TITLE_X = 10;
    private static final int TITLE_Y = 10;

    /** 方块面格子边长与间距（本轮由 30 放大到 40：格内文字改用原版字号，直接可读、不再依赖 tooltip）。 */
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
    /** 图例文字可用宽度（折行用，绝不越出面板右内边 247）。 */
    private static final int LEGEND_TEXT_W = 54;
    /** 图例每条之间的最小间距。 */
    private static final int LEGEND_GAP = 3;

    private static final int CLOSE_X = 178;
    private static final int CLOSE_Y = 152;
    private static final int CLOSE_W = 66;
    private static final int CLOSE_H = 16;

    /** 输出模式切换按钮（右上角，与标题同高）。 */
    private static final int MODE_BTN_X = 178;
    private static final int MODE_BTN_Y = 8;
    private static final int MODE_BTN_W = 66;
    private static final int MODE_BTN_H = 16;

    /** 格子内文字底色条（半透明白，保证深色字在任何模式色上都清晰可读）。 */
    private static final int COLOR_TEXT_PLATE = 0x55FFFFFF;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_CELL_TEXT = 0xFF1F1F1F;

    /** 目标执行仓坐标；null = 由服务端按当前打开的执行仓菜单解析。 */
    @Nullable
    private final BlockPos pos;
    /** 本地编辑中的面模式（服务端回包后以服务端为准覆盖）。 */
    private final FaceMode[] modes = new FaceMode[Direction.values().length];
    /** 本地镜像的输出模式（服务端回包后以服务端为准覆盖）。 */
    private OutputMode outputMode = OutputMode.FACE;
    /** 输出模式切换按钮（按钮文案每帧按当前模式刷新）。 */
    private Button modeButton;

    public ChamberFaceConfigScreen(final Screen parent, @Nullable final BlockPos pos,
                                  final List<Integer> serverModes, final int serverOutputMode) {
        super(Component.translatable(LANG + "face.title"), parent, PANEL_W, PANEL_H);
        this.pos = pos;
        applyServerModes(serverModes);
        applyServerOutputMode(serverOutputMode);
    }

    /** 用服务端权威快照覆盖本地输出模式。 */
    private void applyServerOutputMode(final int serverOutputMode) {
        final OutputMode[] values = OutputMode.values();
        outputMode = serverOutputMode >= 0 && serverOutputMode < values.length
            ? values[serverOutputMode] : OutputMode.FACE;
    }

    /** 用服务端权威快照覆盖本地模式（回包到达 / 首次打开时调用）。 */
    private void applyServerModes(final List<Integer> serverModes) {
        final FaceMode[] values = FaceMode.values();
        for (int i = 0; i < modes.length; i++) {
            modes[i] = FaceMode.NONE;
        }
        if (serverModes == null) {
            return;
        }
        for (int i = 0; i < modes.length && i < serverModes.size(); i++) {
            final Integer ordinal = serverModes.get(i);
            modes[i] = ordinal != null && ordinal >= 0 && ordinal < values.length
                ? values[ordinal] : FaceMode.NONE;
        }
    }

    @Override
    protected void init() {
        super.init();
        modeButton = addRenderableWidget(new Button.Builder(modeButtonLabel(), button -> toggleOutputMode())
            .bounds(px + MODE_BTN_X, py + MODE_BTN_Y, MODE_BTN_W, MODE_BTN_H).build());
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "face.close"),
            button -> returnToParent())
            .bounds(px + CLOSE_X, py + CLOSE_Y, CLOSE_W, CLOSE_H).build());
    }

    /** 输出模式按钮文案：`输出：面输出` / `输出：总线输出`。 */
    private Component modeButtonLabel() {
        return Component.translatable(LANG + "output.button",
            Component.translatable(LANG + "output." + outputMode.key()));
    }

    /** 切换输出模式（服务端权威；回包后以服务端值覆盖本地）。 */
    private void toggleOutputMode() {
        final OutputMode next = outputMode.next(1);
        outputMode = next; // 本地即时反馈
        modeButton.setMessage(modeButtonLabel());
        PacketDistributor.sendToServer(new SetChamberOutputModePacket(
            pos == null ? SetChamberOutputModePacket.NO_POS : pos, next.ordinal()));
    }

    // ==================== 几何（绘制与命中共用） ====================

    /** 第 {@code index} 个面格子的矩形（Menu/屏幕坐标由调用方补 px/py）。 */
    private int[] cellRect(final int index) {
        return switch (index) {
            case 0 -> new int[] {NET_UP_X, NET_UP_Y};                 // 顶面（上）
            case 5 -> new int[] {NET_DOWN_X, NET_DOWN_Y};             // 底面（下）
            default -> new int[] {                                   // 中间一行 4 面
                NET_MID_X0 + (index - 1) * (CELL + CELL_GAP), NET_MID_Y};
        };
    }

    /** 中间行下标 → 方向。 */
    private static Direction midFace(final int index) {
        return NET_MID_FACES[index - 1];
    }

    /** 面格子的方向（上 / 下 / 中间行）。 */
    private static Direction faceDirection(final int index) {
        return switch (index) {
            case 0 -> Direction.UP;
            case 5 -> Direction.DOWN;
            default -> midFace(index);
        };
    }

    /** 鼠标压在哪个面格子上（-1 = 没有）；绘制、点击、tooltip 全部走这里。 */
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

    /** 面模式对应的颜色（图例与格子共用，保证颜色语义一致）。 */
    private static int colorOf(final FaceMode mode) {
        return switch (mode) {
            case INPUT -> 0xFF4CAF50;
            case OUTPUT -> 0xFF2196F3;
            case INTERMEDIATE -> 0xFFF57C00;
            default -> 0xFF9E9E9E;
        };
    }

    private static String modeKey(final FaceMode mode) {
        return LANG + "face.mode." + mode.key();
    }

    /** 格子内使用的「模式短名」语言键（完整名称留给图例与 tooltip）。 */
    private static String shortKey(final FaceMode mode) {
        return LANG + "face.mode.short." + mode.key();
    }

    private static String directionKey(final Direction direction) {
        return LANG + "face.dir." + direction.getSerializedName();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (modeButton != null) {
            modeButton.setMessage(modeButtonLabel());
        }
        guiGraphics.drawString(font, title, px + TITLE_X, py + TITLE_Y, COLOR_TITLE, false);
        // 六个面格子（先铺色块 + 1px 描边，再写面名与模式名；一律原版字号，直接可读）
        for (int i = 0; i < 6; i++) {
            final int[] rect = cellRect(i);
            final int x = px + rect[0];
            final int y = py + rect[1];
            final Direction direction = faceDirection(i);
            final FaceMode mode = modes[direction.get3DDataValue()];
            guiGraphics.fill(x, y, x + CELL, y + CELL, colorOf(mode));
            guiGraphics.fill(x, y, x + CELL, y + 1, 0xFF2B2B2B);
            guiGraphics.fill(x, y + CELL - 1, x + CELL, y + CELL, 0xFF2B2B2B);
            guiGraphics.fill(x, y, x + 1, y + CELL, 0xFF2B2B2B);
            guiGraphics.fill(x + CELL - 1, y, x + CELL, y + CELL, 0xFF2B2B2B);
            // 面名（上）＋ 模式短名（下）：正常字号 + 半透明白底条 → 任何模式色上都清晰
            final Component dirName = Component.translatable(directionKey(direction));
            guiGraphics.fill(x + 1, y + 3, x + CELL - 1, y + 12, COLOR_TEXT_PLATE);
            guiGraphics.drawString(font, dirName, x + (CELL - font.width(dirName)) / 2, y + 4,
                COLOR_CELL_TEXT, false);
            final Component modeName = Component.translatable(shortKey(mode));
            guiGraphics.fill(x + 1, y + CELL - 13, x + CELL - 1, y + CELL - 4, COLOR_TEXT_PLATE);
            guiGraphics.drawString(font, modeName, x + (CELL - font.width(modeName)) / 2,
                y + CELL - 12, COLOR_CELL_TEXT, false);
        }
        // 总线输出：逐面配置整体被忽略 —— 十字网整体压暗 + 居中标注，一眼可见「这些设置当前不生效」
        if (outputMode == OutputMode.BUS) {
            final int x1 = px + NET_MID_X0;
            final int y1 = py + NET_UP_Y;
            final int x2 = px + NET_MID_X0 + 4 * (CELL + CELL_GAP) - CELL_GAP;
            final int y2 = py + NET_DOWN_Y + CELL;
            guiGraphics.fill(x1, y1, x2, y2, 0xB0000000);
            final Component ignored = Component.translatable(LANG + "output.ignored");
            guiGraphics.drawString(font, ignored,
                (x1 + x2 - font.width(ignored)) / 2, (y1 + y2) / 2 - 4, 0xFFFFFFFF, false);
        }
        // 图例：四档模式的颜色 + 完整名称（逐条按像素宽折行，绝不越界、不与下方按钮重叠）
        int legendY = py + LEGEND_Y;
        for (final FaceMode value : FaceMode.values()) {
            guiGraphics.fill(px + LEGEND_X, legendY,
                px + LEGEND_X + LEGEND_SWATCH, legendY + LEGEND_SWATCH, colorOf(value));
            guiGraphics.fill(px + LEGEND_X, legendY,
                px + LEGEND_X + LEGEND_SWATCH, legendY + 1, 0xFF2B2B2B);
            guiGraphics.fill(px + LEGEND_X, legendY + LEGEND_SWATCH - 1,
                px + LEGEND_X + LEGEND_SWATCH, legendY + LEGEND_SWATCH, 0xFF2B2B2B);
            int textY = legendY;
            for (final net.minecraft.util.FormattedCharSequence line
                : font.split(Component.translatable(modeKey(value)), LEGEND_TEXT_W)) {
                guiGraphics.drawString(font, line, px + LEGEND_X + LEGEND_SWATCH + 4, textY,
                    COLOR_TEXT, false);
                textY += 9;
            }
            legendY = Math.max(legendY + LEGEND_SWATCH, textY) + LEGEND_GAP;
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** tooltip：输出模式按钮 / 面格子（名称 + 当前模式 + 切换提示）。全部手动渲染，**行数压到最少**。
     *  <p>方向名 = 身份信息（常显）；模式语义说明 = Shift 层；当前值是哪个模式 = Ctrl 层
     *  （统一走唯一实现 {@link RsccTooltipLayers}，默认收起）。</p> */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (modeButton != null && modeButton.isMouseOver(mouseX, mouseY)) {
            // 只给「当前模式」的语义那一行：按钮上已写明模式名，不再重复标题与「怎么点」的说明
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "output." + outputMode.key() + ".tip"), mouseX, mouseY);
            return;
        }
        final int cell = cellAt(mouseX, mouseY);
        if (cell < 0) {
            return; // 说明块已移除：除模式按钮与面格子外不再有其它 tooltip 触发区
        }
        final Direction direction = faceDirection(cell);
        final FaceMode mode = modes[direction.get3DDataValue()];
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(4);
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "face.mode." + mode.key() + ".tip")));
        if (outputMode == OutputMode.BUS) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "output.cell.ignored")));
        }
        layered.add(RsccTooltipLayers.ctrl(Component.translatable(LANG + "face.current",
            Component.translatable(modeKey(mode)))));
        RsccTooltipLayers.render(guiGraphics, font,
            List.of(Component.translatable(directionKey(direction))), layered, mouseX, mouseY);
    }

    /** 手动渲染多行 tooltip（本模组的 GUI 不会自动渲染 tooltip）。 */
    private void renderLines(final GuiGraphics guiGraphics, final List<Component> lines,
                             final int mouseX, final int mouseY) {
        final List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>(lines.size());
        for (final Component line : lines) {
            wrapped.add(line.getVisualOrderText());
        }
        guiGraphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        final int cell = cellAt(mouseX, mouseY);
        if (cell >= 0) {
            final Direction direction = faceDirection(cell);
            final int index = direction.get3DDataValue();
            // 左键 = 下一个模式，右键 = 上一个模式（与服务端权威值同步前的本地即时反馈）
            final FaceMode next = modes[index].next(button == 1 ? -1 : 1);
            modes[index] = next;
            final BlockPos target = pos == null ? SetChamberFacePacket.NO_POS : pos;
            PacketDistributor.sendToServer(
                new SetChamberFacePacket(target, index, next.ordinal()));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 服务端权威快照（每次回包后调用；由 {@link SyncChamberBindingPacket} 的客户端处理器转发）。 */
    public void onServerSync(final List<Integer> serverModes, final int serverOutputMode) {
        applyServerModes(serverModes);
        applyServerOutputMode(serverOutputMode);
        if (modeButton != null) {
            modeButton.setMessage(modeButtonLabel());
        }
    }
}
