package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccKeeperGeometry;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import cretae.cookiewyq.rs_create_compat.support.KeeperSlotConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 高级资源定量保持器界面。
 * <p>每行 = 1 个 ghost 标记槽 + [−] 目标数量输入框 [+] + 单位/堵塞短提示 + <b>两个独立的小开关按钮</b>
 * （自动合成 / 过量销毁）。所有开关统一为「一行文本 + 一个 ✓/✗ 按钮」（✓ 绿、✗ 红），
 * 不再用「开 / 关」文字；数量调节一律 <b>− → 输入框 → +</b> 从左到右。</p>
 * <p>自动合成的前提是装了「自动合成升级」：没装升级时按钮显示 ✗ 且不可开启；装了之后每个槽位
 * 还能单独关掉。过量销毁是纯每槽开关。</p>
 * <p><b>背景</b>：用原版九宫格精灵拼出 MC 原生面板（{@link McGui#panel}），槽位框用
 * {@link McGui#slotFrame}（原版容器观感），不再使用占位用的自绘 PNG。</p>
 * <p>所有文字无阴影；自绘元素 tooltip 全部手动渲染，hover 判定与绘制范围严格一致。</p>
 * <p>槽位坐标为 Menu 坐标（精灵 + 1）；下方 Java 控件使用精灵坐标。</p>
 */
public class AdvancedQuantityKeeperScreen extends AbstractContainerScreen<AdvancedQuantityKeeperMenu> {
    // ===== 布局（精灵坐标；Menu 槽位 = 精灵 + 1，由 Menu 负责）=====
    private static final int ROW_Y = RsccKeeperGeometry.ROW_FIRST_Y;    // 行起点 y
    private static final int ROW_STEP = RsccKeeperGeometry.ROW_STEP;    // 行距（24→26：腾出提示带）
    /**
     * 目标数量的三个控件：<b>− 在最左 → 输入框在中间 → + 在最右</b>（用户强调：说的就是这三个控件
     * 彼此的相对位置，不是「整个背景的最左边」）。几何<b>全部</b>取自
     * {@link RsccKeeperGeometry}（与基础版共用同一批常量），本轮把输入框加宽到 76px
     * （文本区 67px ⇒ "100,000,000" 连千分位放得下），并把右侧两列开关右移让位。
     */
    private static final int MINUS_SPRITE_X = RsccKeeperGeometry.ROW_MINUS_X;
    private static final int BOX_DY = RsccKeeperGeometry.ROW_BOX_DY;
    private static final int BOX_SPRITE_X = RsccKeeperGeometry.ROW_BOX_X;
    private static final int BOX_W = RsccKeeperGeometry.ROW_BOX_W;
    private static final int BOX_H = RsccKeeperGeometry.ROW_BOX_H;
    private static final int PLUS_SPRITE_X = RsccKeeperGeometry.ROW_PLUS_X;
    private static final int NUM_BTN_W = RsccKeeperGeometry.ROW_BTN_W;
    private static final int NUM_BTN_H = RsccKeeperGeometry.ROW_BTN_H;
    /**
     * 单位 / 状态文字（个 / mB）左沿 = 26（与 {@link RsccKeeperGeometry#ROW_UNIT_X} 同值）。
     * <p>写成字面量而不是引用常量：工程既有的 GUI 布局校验
     * （{@code tmp_textures/verify_gui_layout.py#verify_adv_keeper_no_stored_region}）用正则读这个数字
     * 做几何断言（「短提示起点 + 30px 仍 &lt; 自动合成列」）。改值时两处必须同步 ——
     * {@code tools/selfcheck_round50_keeper_fluid_display.py} 会断言两者相等。</p>
     */
    private static final int UNIT_SPRITE_X = 26;
    /**
     * 两列开关的列左沿 = 148 / 170（与 {@link RsccKeeperGeometry} 同值）。
     * <p>本轮从 134 / 162 右移，给加宽后的输入框腾位置；列与列之间留 6px 缝，
     * 右列最右 186 &lt; 插件槽 x=187，不会压到插件槽。写成字面量：工程既有的 GUI 布局校验
     * （{@code tmp_textures/verify_gui_layout.py}）用正则读这两个数字做几何断言；
     * 改值时两处必须同步 —— {@code selfcheck_round50_keeper_fluid_display.py} 会断言相等。</p>
     * <p><b>「已存 / 目标」那一段文字已按要求删除</b>：目标数量本身就是可编辑的输入框，
     * 再复述一遍既占地方又容易和真实值混淆；已存数量属于诊断信息，改由销毁日志输出。</p>
     */
    private static final int AUTOCRAFT_BTN_X = 148;
    private static final int OVERFLOW_BTN_X = 170;
    private static final int TOGGLE_BTN_W = RsccKeeperGeometry.TOGGLE_BTN_W;
    private static final int TOGGLE_BTN_H = 12;
    private static final int TOGGLE_DY = 3;
    /** 两列标题的基线 y（位于标题行与第 1 行之间的留白带，行首槽顶 = 24，不会压到槽位）。 */
    private static final int COL_HEADER_Y = 14;
    /** 列标题缩放（0.75：完整「自动合成 / 过量销毁」也放得进 16px 列宽，且仍清晰可读）。 */
    private static final float COL_HEADER_SCALE = 0.75F;
    /** 列标题 hover 判定半宽（与绘制宽度同源；两列列心相距 22px，半宽 10 保证不重叠）。 */
    private static final int COL_HEADER_HALF_W = RsccKeeperGeometry.COL_HEADER_HALF_W;

    /** 文字颜色（原版浅灰面板上用深色字）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_TEXT_DIM = 0xFF6A6A6A;
    private static final int COLOR_BLOCKED = 0xFFAA3333;

    private static final String LANG = "gui.rs_create_compat.advanced_quantity_keeper.";

    private final EditBox[] targetBoxes = new EditBox[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] minusButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] plusButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] autocraftButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] overflowButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    /** 上次本地修改目标数量的 tick（防止服务端同步值覆盖输入框）。 */
    private final long[] lastLocalEditTick = new long[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    /** S2C 同步的 4 槽配置快照（ghost 槽绘制流体 / tooltip 用）。 */
    private final KeeperSlotConfig[] syncedConfigs = new KeeperSlotConfig[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.RsccRedstoneModeProperty
        rscc$redstoneMode;

    public AdvancedQuantityKeeperScreen(final AdvancedQuantityKeeperMenu menu,
                                        final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210;
        this.imageHeight = 210;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在布局中
        this.titleLabelY = 10000;     // 标题自绘（无阴影）
        for (int i = 0; i < syncedConfigs.length; i++) {
            syncedConfigs[i] = KeeperSlotConfig.EMPTY;
            lastLocalEditTick[i] = -1000;
        }
    }

    /** S2C：接收 4 槽配置快照。 */
    public void setSyncedConfigs(final List<KeeperSlotConfig> configs) {
        if (configs == null) {
            return;
        }
        for (int i = 0; i < syncedConfigs.length; i++) {
            syncedConfigs[i] = i < configs.size() ? configs.get(i) : KeeperSlotConfig.EMPTY;
        }
    }

    /** 第 {@code row} 行当前同步到的流体标记 id（非流体标记 / 未同步返回 null）。 */
    @Nullable
    private net.minecraft.resources.ResourceLocation syncedFluidId(final int row) {
        final KeeperSlotConfig config = syncedConfigs[row];
        return config.isFluid() ? config.id() : null;
    }

    @Override
    protected void init() {
        super.init();
        for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
            final int rowY = ROW_Y + row * ROW_STEP;
            final int r = row;
            final EditBox box = new EditBox(font, leftPos + BOX_SPRITE_X, topPos + rowY + BOX_DY,
                BOX_W, BOX_H, Component.literal("target" + row));
            box.setTextShadow(false);
            box.setValue(RsccNumberField.editable(menu.getTarget(r), textWindow()));
            // 解析口径只有一份实现（RsccNumberField.wire）：千分位 / 后缀 / 科学计数都在里面
            RsccNumberField.wire(box, value -> {
                if (box.isFocused()) {
                    lastLocalEditTick[r] = gameTime();
                }
                applyTargetIfValid(r, value); // 输入即应用
            });
            targetBoxes[row] = box;
            addRenderableWidget(box);

            // 目标数量 [−] / [+]（− 在左、+ 在右，与全局一致）
            minusButtons[row] = addRenderableWidget(new Button.Builder(Component.literal("-"),
                btn -> bumpTarget(r, -1))
                .bounds(leftPos + MINUS_SPRITE_X, topPos + rowY + BOX_DY, NUM_BTN_W, NUM_BTN_H).build());
            plusButtons[row] = addRenderableWidget(new Button.Builder(Component.literal("+"),
                btn -> bumpTarget(r, 1))
                .bounds(leftPos + PLUS_SPRITE_X, topPos + rowY + BOX_DY, NUM_BTN_W, NUM_BTN_H).build());

            // 「自动合成」每槽开关（前提是装了自动合成升级，见服务端 shouldAutoCraft）
            autocraftButtons[row] = addRenderableWidget(McGui.toggle(
                leftPos + AUTOCRAFT_BTN_X, topPos + rowY + TOGGLE_DY, TOGGLE_BTN_W, TOGGLE_BTN_H,
                () -> menu.isAutoCraft(r),
                on -> PacketDistributorHelper.toggleAutocraft(menu, r, on)));
            // 「过量销毁」每槽开关（与自动合成完全独立）
            overflowButtons[row] = addRenderableWidget(McGui.toggle(
                leftPos + OVERFLOW_BTN_X, topPos + rowY + TOGGLE_DY, TOGGLE_BTN_W, TOGGLE_BTN_H,
                () -> menu.isDestroyOverflow(r),
                on -> PacketDistributorHelper.toggleOverflow(menu, r, on)));
        }
        // 红石模式（忽略 / 高电平 / 低电平）：复用 RS 原版侧边按钮，挂在面板左侧外缘
        rscc$redstoneMode = cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.attach(this);
    }

    /** 红石模式由服务端权威值驱动显示（控件本体复用 RS 原版，hover 时才手动渲染 tooltip）。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (rscc$redstoneMode != null) {
            rscc$redstoneMode.set(menu.rscc$getRedstoneMode().ordinal());
        }
    }

    /** 发送「自动合成 / 过量销毁」切换请求（服务端权威写入）。 */
    private static final class PacketDistributorHelper {
        private static void toggleAutocraft(final AdvancedQuantityKeeperMenu menu,
                                           final int row, final boolean enabled) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperAutocraftPacket(
                    menu.containerId, row, enabled));
        }

        private static void toggleOverflow(final AdvancedQuantityKeeperMenu menu,
                                           final int row, final boolean enabled) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperOverflowPacket(
                    menu.containerId, row, enabled));
        }
    }

    private long gameTime() {
        return minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0;
    }

    /** 输入框文本区可用像素宽（几何与「放得下才加千分位」的判定同源）。 */
    private static int textWindow() {
        return RsccNumberField.textWindow(BOX_W);
    }

    /** 第 {@code row} 行输入框里的当前数值（后缀 / 科学计数都会被正确解析；解析不出则退回服务端值）。 */
    private long currentValue(final int row) {
        final EditBox box = targetBoxes[row];
        if (box != null) {
            final String text = box.getValue();
            if (!text.isEmpty()) {
                final RsccNumberField.Parsed parsed = RsccNumberField.parse(text);
                if (parsed.ok()) {
                    return parsed.value();
                }
            }
        }
        return Math.max(0L, menu.getTarget(row));
    }

    /** 把数值写回输入框并立即应用（± 按钮、回车规范化共用同一条路径）。 */
    private void setTargetField(final int row, final long value) {
        final EditBox box = targetBoxes[row];
        if (box == null) {
            return;
        }
        box.setValue(RsccNumberField.editable(value, textWindow()));
        lastLocalEditTick[row] = gameTime();
        applyTargetIfValid(row, value);
    }

    /** 目标数量 [−]/[+]：修改输入框并立即应用（与输入框配合）。 */
    private void bumpTarget(final int row, final int delta) {
        final int step = hasShiftDown() ? 10 : 1; // Shift 快速增减（与范围控件保持一致的手感）
        // 下限 0：0 = 「未标记」（该项不参与维持 / 合成 / 销毁）；上限由 clampToInt 统一兜底
        setTargetField(row, Math.max(0L, currentValue(row) + (long) delta * step));
    }

    private void applyTargetIfValid(final int row, final long value) {
        if (value < 0L) {
            return;
        }
        // 允许发送 0：0 = 「未标记」（服务端同样按 0 = 未标记处理）
        // 只发原始数值：物品 = 个数、流体/气体 = mB（换算只发生在展示层，服务端存的原值精度不丢）
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperTargetPacket(
                menu.containerId, row, RsccNumberField.clampToInt(value)));
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        for (int row = 0; row < targetBoxes.length; row++) {
            final EditBox box = targetBoxes[row];
            if (box != null && box.isFocused() && (keyCode == 257 || keyCode == 335)) { // Enter
                // 回车 = 把当前文本规范化（1000b → 1,000,000），再发一次包
                setTargetField(row, currentValue(row));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 背景 ====================

    /**
     * 自绘面板 PNG（{@code ADV_QUANTITY_KEEPER_GUI_DOC_V2.md} 约定，210×210）：
     * 已内含全部 46 个槽位框，故整图 {@code blit} 即可，槽位框不再由 Java 重复绘制。
     */
    private static final ResourceLocation CUSTOM_PANEL =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "textures/gui/advanced_quantity_keeper.png");

    /**
     * 背景：整图 {@code blit} 自绘面板 PNG（槽位框由 PNG 提供）。
     * <p>PNG 缺失（{@link #CUSTOM_PANEL} 为 null）时退回原版九宫格面板（MC 自带风格）
     * + 原版观感的 18×18 槽位框（{@link McGui#slotFrame}）。</p>
     */
    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        if (CUSTOM_PANEL != null) {
            guiGraphics.blit(CUSTOM_PANEL, leftPos, topPos, 0.0F, 0.0F,
                imageWidth, imageHeight, imageWidth, imageHeight);
            return;
        }
        McGui.panel(guiGraphics, leftPos, topPos, imageWidth, imageHeight);
        for (final Slot slot : menu.slots) {
            McGui.slotFrame(guiGraphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    // ==================== 槽位渲染 ====================

    /** ghost 标记槽：显式流体/气体标记画流体贴图，其余交回原版渲染物品。 */
    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final Slot slot) {
        final int row = menu.markerRowOf(slot);
        if (row >= 0 && menu.getMarkerForm(row) >= 1) {
            final net.minecraft.resources.ResourceLocation id = syncedFluidId(row);
            final FluidStack fluid = id == null ? FluidStack.EMPTY : GhostMarkerRenderer.toFluidStack(id, 1L);
            if (!fluid.isEmpty()) {
                GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, fluid);
                return;
            }
        }
        super.renderSlot(guiGraphics, slot);
    }

    // ==================== 文字 ====================

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, 8, 6, COLOR_TITLE, false);
        // 两列开关的列标题（只在顶部画一次，省下每一行的空间；列心 = 该列按钮中心）
        drawColumnHeader(guiGraphics, LANG + "autocraft_label", columnCenterX(0));
        drawColumnHeader(guiGraphics, LANG + "overflow_label", columnCenterX(1));
        for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
            final int rowY = ROW_Y + row * ROW_STEP;
            // 输入框左侧的短状态：未标记 = 空、正常 = 单位（个 / mB）。
            // 「堵塞」不再挤在缝里（会把文字压到开关上）：堵塞改由输入框描红 +
            // 输入框 tooltip 给出结论，见 statusText / render 的输入框分支。
            // 桶换算（原始 mB = 多少桶）同样走输入框 tooltip —— 本面板行距 24px，
            // 行内与行下方都没有第二条空带能放下可见提示（详见 RsccKeeperGeometry 的注释）。
            guiGraphics.drawString(font, statusText(row), UNIT_SPRITE_X, rowY + BOX_DY + 3,
                COLOR_TEXT_DIM, false);
        }
    }

    /**
     * 输入框右侧的短状态文字：未标记 → 空；其余 → 单位（个 / mB）。
     * <p>不再显示「已存 / 目标」那一整段（用户要求删除）：目标数量本身就是输入框里的值，
     * 已存数量属于诊断信息，改由销毁日志给出；「堵塞」也不再占用这条 6px 缝（会压到右列开关），
     * 改为输入框描红 + 输入框 tooltip 说明原因，玩家反而更容易定位到是哪一行。</p>
     */
    private Component statusText(final int row) {
        if (!menu.hasMarker(row)) {
            return Component.empty();
        }
        return Component.translatable(menu.getMarkerForm(row) >= 1 ? LANG + "unit_mb" : LANG + "unit_item");
    }

    // ==================== 两列开关的列标题 ====================

    /** 第 {@code col} 列（0 = 自动合成，1 = 过量销毁）的按钮中心 x（精灵坐标）。 */
    private static int columnCenterX(final int col) {
        return (col == 0 ? AUTOCRAFT_BTN_X : OVERFLOW_BTN_X) + TOGGLE_BTN_W / 2;
    }

    /** 以 {@link #COL_HEADER_SCALE} 缩放把列标题居中画在该列上方（无阴影）。 */
    private void drawColumnHeader(final GuiGraphics guiGraphics, final String key, final int centerX) {
        final Component text = Component.translatable(key);
        final var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(centerX - font.width(text) * COL_HEADER_SCALE / 2.0F, COL_HEADER_Y, 0.0F);
        pose.scale(COL_HEADER_SCALE, COL_HEADER_SCALE, 1.0F);
        guiGraphics.drawString(font, text, 0, 0, COLOR_TEXT, false);
        pose.popPose();
    }

    /** 鼠标是否压在第 {@code col} 列的标题上（与绘制范围共用同一批几何常量）。 */
    private boolean isOverColumnHeader(final int col, final int mouseX, final int mouseY) {
        final int centerX = columnCenterX(col);
        return isHovering(centerX - COL_HEADER_HALF_W, COL_HEADER_Y - 1,
            COL_HEADER_HALF_W * 2, 10, mouseX, mouseY);
    }

    /** 列标题 tooltip（本模组 GUI 不会自动渲染 tooltip，必须手动调用）。 */
    private void renderColumnHeaderTooltip(final GuiGraphics guiGraphics, final int col,
                                           final int mouseX, final int mouseY) {
        if (col == 0) {
            guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "autocraft_label"),
                Component.translatable(menu.hasAutocraftingUpgrade()
                    ? LANG + "autocraft_header.tip" : LANG + "autocraft_no_upgrade.tip")),
                java.util.Optional.empty(), mouseX, mouseY);
        } else {
            guiGraphics.renderTooltip(font, List.of(
                Component.translatable(LANG + "overflow_label"),
                Component.translatable(LANG + "overflow_header.tip")),
                java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    // ==================== 每帧同步控件状态 ====================

    private void syncWidgets() {
        for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
            final EditBox box = targetBoxes[row];
            if (box != null) {
                final int serverTarget = menu.getTarget(row);
                if (!box.getValue().equals(Integer.toString(serverTarget))
                    && !box.isFocused()
                    && (minecraft == null || minecraft.level == null
                    || minecraft.level.getGameTime() - lastLocalEditTick[row] >= 10)) {
                    // 服务端回填也走同一套展示口径（大数加千分位，放不下才退回纯数字）
                    box.setValue(RsccNumberField.editable(serverTarget, textWindow()));
                }
            }
            // 两个开关统一 ✓ 绿 / ✗ 红（共用 {@link McGui#toggleLabel}）；没装升级时自动合成恒为 ✗
            McGui.refreshToggle(autocraftButtons[row], menu.isAutoCraft(row));
            McGui.refreshToggle(overflowButtons[row], menu.isDestroyOverflow(row));
            // 没装自动合成升级 → 「自动合成」开关真正禁用（active=false：既点不动，也由原版画成置灰）
            autocraftButtons[row].active = menu.hasAutocraftingUpgrade();
        }
    }

    /**
     * 本行「堵塞」的视觉告警：给输入框描一圈红边（唯一实现）。
     * <p>为什么从「右侧写『堵塞』两个字」改成描边：这条缝只有 6px 宽，中文两字 18px 必然压到右列开关；
     * 描边不占空间、且直接把玩家引到「这一行的数量输入框」，配合输入框 tooltip 说明原因。</p>
     */
    private void drawBlockedOutline(final GuiGraphics guiGraphics, final int row) {
        if (!menu.isBlocked(row)) {
            return;
        }
        final int x = leftPos + BOX_SPRITE_X - 1;
        final int y = topPos + ROW_Y + row * ROW_STEP + BOX_DY - 1;
        final int w = BOX_W + 2;
        final int h = BOX_H + 2;
        guiGraphics.fill(x, y, x + w, y + 1, COLOR_BLOCKED);                 // 上
        guiGraphics.fill(x, y + h - 1, x + w, y + h, COLOR_BLOCKED);         // 下
        guiGraphics.fill(x, y, x + 1, y + h, COLOR_BLOCKED);                 // 左
        guiGraphics.fill(x + w - 1, y, x + w, y + h, COLOR_BLOCKED);         // 右
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        syncWidgets();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 堵塞告警描边画在控件之上（super.render 之后），否则会被 EditBox 的底色盖住
        for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
            drawBlockedOutline(guiGraphics, row);
        }
        // 两列标题的 tooltip（标题带与行控件互不重叠，故先判、命中即返回）
        for (int col = 0; col < 2; col++) {
            if (isOverColumnHeader(col, mouseX, mouseY)) {
                renderColumnHeaderTooltip(guiGraphics, col, mouseX, mouseY);
                return;
            }
        }
        // 自绘控件 tooltip：hover 判定与绘制范围严格一致
        for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
            final Button autocraft = autocraftButtons[row];
            // 用几何判定（不能用 isMouseOver：控件 inactive 时它恒为 false，禁用态就拿不到提示了）
            if (autocraft != null && isHovering(AUTOCRAFT_BTN_X, ROW_Y + row * ROW_STEP + TOGGLE_DY,
                TOGGLE_BTN_W, TOGGLE_BTN_H, mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "autocraft_label"),
                    Component.translatable(menu.hasAutocraftingUpgrade()
                        ? (menu.isAutoCraft(row) ? LANG + "autocraft_on.tip" : LANG + "autocraft_off.tip")
                        : LANG + "autocraft_no_upgrade.tip")),
                    java.util.Optional.empty(), mouseX, mouseY);
                return;
            }
            final Button overflow = overflowButtons[row];
            if (overflow != null && isHovering(OVERFLOW_BTN_X, ROW_Y + row * ROW_STEP + TOGGLE_DY,
                TOGGLE_BTN_W, TOGGLE_BTN_H, mouseX, mouseY)) {
                guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "overflow_label"),
                    Component.translatable(menu.isDestroyOverflow(row)
                        ? LANG + "overflow_on.tip" : LANG + "overflow_off.tip"),
                    Component.translatable(LANG + "overflow_shared.tip")),
                    java.util.Optional.empty(), mouseX, mouseY);
                return;
            }
            final EditBox box = targetBoxes[row];
            if (box != null && isOverBox(box, mouseX, mouseY)) {
                // 目标数量输入框：本行堵塞时优先解释堵塞原因（红色描边就画在这个框上）；
                // 正常时给出本行口径（流体 / 气体按 mB）+ 当前值的桶换算（1 桶 = 1000 mB）
                guiGraphics.renderTooltip(font,
                    java.util.List.of(Component.translatable(menu.isBlocked(row)
                        ? LANG + "blocked.tooltip" : LANG + "target_tooltip"),
                        Component.literal(RsccNumberField.bucketTooltip(currentValue(row)))),
                    java.util.Optional.empty(), mouseX, mouseY);
                return;
            }
        }
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用（空插件槽 tooltip 由此触发）
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    private boolean isOverBox(final EditBox box, final int mouseX, final int mouseY) {
        return mouseX >= box.getX() && mouseX < box.getX() + box.getWidth()
            && mouseY >= box.getY() && mouseY < box.getY() + box.getHeight();
    }

    // ==================== ghost 槽 / 升级槽 tooltip ====================

    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot == null) {
            return;
        }
        // 空插件槽：提示「可放入哪些升级」（与工程其它界面完全一致）
        if (hoveredSlot instanceof UpgradeSlot upgradeSlot && hoveredSlot.getItem().isEmpty()
            && menu.getCarried().isEmpty()) {
            UpgradeSlotTooltips.render(upgradeSlot, guiGraphics, font, mouseX, mouseY);
            return;
        }
        final int row = menu.markerRowOf(hoveredSlot);
        if (row < 0) {
            return;
        }
        if (menu.getMarkerForm(row) >= 1) {
            final net.minecraft.resources.ResourceLocation id = syncedFluidId(row);
            if (id != null) {
                GhostMarkerRenderer.renderFluidTooltip(guiGraphics, font, id, false, null, mouseX, mouseY);
                return;
            }
        }
        if (hoveredSlot.getItem().isEmpty()) {
            guiGraphics.renderTooltip(font,
                List.of(Component.translatable(LANG + "marker_tooltip")),
                java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
