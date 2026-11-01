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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 高级资源定量保持器界面。
 * <p>每行 = 1 个 ghost 标记槽 + [−] 目标数量输入框 [+] + <b>两个独立的功能开关</b>
 * （自动合成 / 过量销毁）。数量调节一律 <b>− → 输入框 → +</b> 从左到右。</p>
 *
 * <h2>三处界面口径（第 90 轮）</h2>
 * <ol>
 *     <li><b>流体 / 气体标记槽 = 流体贴图渲染（不是桶）</b>：用户明确否掉上一轮「槽里放真实桶物品」
 *     的做法（「岩浆必须显示为液体/流体渲染，不能显示为桶状」）。现在
 *     {@link #renderSlot} 对本模组自己的流体 / 气体标记槽调用工程既有的
 *     {@link GhostMarkerRenderer#renderFluid}（RS {@code AbstractFluidRenderer} 同款：
 *     原版流体 still 贴图 + {@code getTintColor} 顶点着色），<b>槽里不再放任何物品</b>，
 *     因此也绝不会画出桶。标记语义仍然<b>只</b>由 {@code fluidMarkerIds[] / fluidForms[]}
 *     （客户端 = S2C 的 {@link KeeperSlotConfig} form）判定，绝不从物品类型反推。
 *     没有流体贴图 / 未同步到 id ⇒ 只有原版 {@code super.renderSlot} 一条路径（空槽，可接受的降级），
 *     不另造图标、不退回桶。</li>
 *     <li><b>列标签 = 两行完整词</b>：「自动 / 合成」「过量 / 销毁」（en: Auto / Craft、Over / flow）。
 *     上一轮的单行短词「自动 / 销毁」会被读成「自动销毁」（用户原话），因此改成两行；
 *     两行各 9px、标签块 18px，坐标见 {@link RsccKeeperGeometry#COLUMN_LABEL_Y}。
 *     为防止英文长标题（184px）压到标签带，标题按 {@link RsccKeeperGeometry#TITLE_BAND_W} 折行。</li>
 *     <li><b>目标数量行</b>：输入框 <b>74×14</b>（比上一轮 70×12 更宽更高），
 *     <b>[+] 右侧不再有任何重复数字</b>——行内只有 [−] / 输入框 / [+] 三个控件，
 *     目标值只存在于可编辑的输入框里（自检对「+= 右侧无文本」做源码 + 几何双重断言）。</li>
 * </ol>
 * <p><b>tooltip</b>：流体标记槽里没有物品，原版物品 tooltip 不会自己出现，因此这里复用工程既有的
 * {@link GhostMarkerRenderer#renderFluidTooltip} 画<b>流体自身的名字</b>
 * （{@code FluidType#getDescription()}，即 RS / JEI 同源的 {@code FluidStack#getHoverName}，
 * 是可翻译 Component，<b>不是 literal 硬拼</b>），不再拼任何数量 / 桶换算。</p>
 * <p><b>背景</b>：整图 {@code blit} 交付的 210×210 面板 PNG（槽位框已烘焙在内）。</p>
 * <p>所有文字无阴影；自绘元素 tooltip 全部手动渲染，hover 判定与绘制范围严格一致。</p>
 * <p>槽位坐标为 Menu 坐标（精灵 + 1）；下方 Java 控件使用精灵坐标。</p>
 */
public class AdvancedQuantityKeeperScreen extends AbstractContainerScreen<AdvancedQuantityKeeperMenu> {
    // ===== 布局（精灵坐标；Menu 槽位 = 精灵 + 1，由 Menu 负责）=====
    private static final int ROW_Y = RsccKeeperGeometry.ROW_FIRST_Y;    // 行起点 y
    private static final int ROW_STEP = RsccKeeperGeometry.ROW_STEP;    // 行距 24（烘焙在背景 PNG 里，不能改）
    /**
     * 目标数量的三个控件：<b>− 在最左 → 输入框在中间 → + 在最右</b>（用户强调：说的就是这三个控件
     * 彼此的相对位置，不是「整个背景的最左边」）。几何<b>全部</b>取自
     * {@link RsccKeeperGeometry}（与基础版共用同一批常量）。
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
     * <p><b>本轮起不再绘制单位文字</b>（与基础版同一条口径：单位与桶换算都在输入框 tooltip 里），
     * 常量保留是因为工程既有的 GUI 布局校验
     * （{@code tmp_textures/verify_gui_layout.py#verify_adv_keeper_no_stored_region}）用正则读这个数字；
     * {@code tools/selfcheck_round50_keeper_fluid_display.py} 会断言它与几何常量相等。</p>
     */
    private static final int UNIT_SPRITE_X = 26;
    /**
     * 两列开关的列左沿 = 135 / 164（与 {@link RsccKeeperGeometry} 同值）。
     * <p>两列列心 143 / 172（相距 29px）；本轮的列标签是两个 2 字行（中文 18px、
     * 英文最宽 26px），缝 = 29 − 25 = 4px，仍满足既有「两标签缝 ≥ 4px」判据。
     * 写成字面量：工程既有的 GUI 布局校验（{@code tmp_textures/verify_gui_layout.py}）
     * 用正则读这两个数字做几何断言；改值时两处必须同步（{@code selfcheck_round50 / round84}
     * 会断言相等）。</p>
     * <p><b>「已存 / 目标」那一段文字已按要求删除</b>：目标数量本身就是可编辑的输入框，
     * 因此 <b>[+] 右侧不再画任何数字</b>（见 {@link #renderLabels}：行内文字一个都没有）。</p>
     */
    private static final int AUTOCRAFT_BTN_X = 135;
    private static final int OVERFLOW_BTN_X = 164;
    private static final int TOGGLE_BTN_W = RsccKeeperGeometry.TOGGLE_BTN_W;
    private static final int TOGGLE_BTN_H = RsccKeeperGeometry.TOGGLE_BTN_H;
    private static final int TOGGLE_DY = RsccKeeperGeometry.TOGGLE_DY;
    /** 两列列标签第一行的行顶（4：标题带 6..23 的同一高度带，第一行控件 26 起，隔 5px）。 */
    private static final int COLUMN_LABEL_Y = RsccKeeperGeometry.COLUMN_LABEL_Y;
    /** 列标签单行行高（两行 = 4..12 / 13..21）。 */
    private static final int COLUMN_LABEL_LINE_H = RsccKeeperGeometry.COLUMN_LABEL_LINE_H;

    /** 文字颜色（原版浅灰面板上用深色字）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    /** 「堵塞」告警描边颜色。 */
    private static final int COLOR_BLOCKED = 0xFFAA3333;

    private static final String LANG = "gui.rs_create_compat.advanced_quantity_keeper.";

    /**
     * 两列列标签（<b>缓存 Component</b>：每帧只做 {@code font.width + drawString}，绝不拼字符串）。
     *
     * <h2>为什么是两行</h2>
     * <p>用户原话：「不能只显示『自动和销毁』，容易误解为『自动销毁』。应显示『自动合成』和『过量销毁』。
     * 一行显示不下、字太小时，应显示为两行。」两列列心只相距 29px，一行完整词中文 36px /
     * 英文 40px 必然相交，因此拆成两行：中文「自动 / 合成」「过量 / 销毁」，
     * 英文「Auto / Craft」「Over / flow」——两行拼起来就是完整词，单行宽度 ≤
     * {@link RsccKeeperGeometry#COLUMN_LABEL_MAX_W} = 26px。</p>
     * <p>行内文本<b>不</b>含 {@code \n}（工程自检禁止把换行塞进 Component）：两行是两个独立
     * 的 {@link Component}，由 {@link #drawColumnLabel} 逐行绘制。</p>
     */
    private static final Component COLUMN_LABEL_AUTOCRAFT_1 =
        Component.translatable(LANG + "column_autocraft");
    private static final Component COLUMN_LABEL_AUTOCRAFT_2 =
        Component.translatable(LANG + "column_autocraft.2");
    private static final Component COLUMN_LABEL_OVERFLOW_1 =
        Component.translatable(LANG + "column_overflow");
    private static final Component COLUMN_LABEL_OVERFLOW_2 =
        Component.translatable(LANG + "column_overflow.2");

    private final EditBox[] targetBoxes = new EditBox[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] minusButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] plusButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] autocraftButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    private final Button[] overflowButtons = new Button[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    /** 上次本地修改目标数量的 tick（防止服务端同步值覆盖输入框）。 */
    private final long[] lastLocalEditTick = new long[AdvancedQuantityKeeperMenu.MARKER_SLOTS];
    /**
     * S2C 同步的 4 槽配置快照。
     * <p><b>本轮起重新参与渲染</b>：流体 / 气体标记槽要用它拿 {@code fluidId} 去铺流体贴图
     * （{@link #renderSlot}）与画流体名 tooltip（{@link #renderTooltip}）。
     * 标记类型（{@code form}）仍以 Menu 的 ContainerData 为准（每 tick 同步、不会滞后），
     * 本快照只提供 ContainerData 装不下的资源 id。</p>
     */
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

            // 「自动合成」每槽开关：全工程统一的 ✓/✗ 样式（McGui.toggle：绿 ✓ / 红 ✗）。
            // 状态每帧由 syncWidgets 用 McGui.refreshToggle 回写服务端权威值；
            // 两个开关同形，功能区分由「列标签（本列正上方的自动 / 销毁）+ 各自独立的悬停 tooltip」
            // 共同承担（见 renderLabels 的 drawColumnLabel 与 render 里的两段 tooltip）。
            autocraftButtons[row] = addRenderableWidget(McGui.toggle(
                leftPos + AUTOCRAFT_BTN_X, topPos + rowY + TOGGLE_DY,
                TOGGLE_BTN_W, TOGGLE_BTN_H,
                () -> menu.isAutoCraft(r),
                on -> PacketDistributorHelper.toggleAutocraft(menu, r, on)));
            // 「过量销毁」每槽开关（与自动合成完全独立）：同一套 ✓/✗ 样式，
            // 靠 12px 列间距 + 列正上方的「销毁」标签 + 各自 tooltip 区分，不再另起图标样式。
            overflowButtons[row] = addRenderableWidget(McGui.toggle(
                leftPos + OVERFLOW_BTN_X, topPos + rowY + TOGGLE_DY,
                TOGGLE_BTN_W, TOGGLE_BTN_H,
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

    /**
     * ghost 标记槽：<b>物品标记走原版；流体 / 气体标记画流体贴图（绝不是桶）</b>。
     *
     * <h2>用户原话（第 90 轮，逐字）</h2>
     * <p>「<b>岩浆必须显示为液体/流体渲染，不能显示为桶状</b>。参考第四张图中的流体渲染效果。
     * 之前的方向部分正确，但当前实现完全错误。」——上一轮把标记槽改成「真实桶物品」被明确否掉。</p>
     *
     * <h2>现在的做法：复用工程既有的流体渲染工具</h2>
     * <p>满足 {@code menu.getMarkerForm(row) >= 1}（= 该行是<b>直接流体 / 气体标记</b>，
     * 判据来自方块实体的 {@code fluidMarkerIds[] / fluidForms[]}，<b>绝不</b>看槽里的物品类型）时：</p>
     * <ol>
     *     <li>{@link GhostMarkerRenderer#toFluidStack} 把资源 id 转成用于绘制的 {@link FluidStack}；</li>
     *     <li>{@link GhostMarkerRenderer#renderFluid} 在槽位 {@code (slot.x, slot.y)} 画 16×16 流体贴图
     *     （原版 still 贴图 + {@code IClientFluidTypeExtensions#getTintColor} 顶点色；
     *     与基础版 {@code QuantityKeeperScreen#renderSlot}、RS 的 {@code AbstractFluidRenderer} 同一套做法）；</li>
     *     <li>画完 {@code return} —— <b>不再</b> {@code super.renderSlot}，因此槽里即使残留物品
     *     （旧存档的桶）也不会被画出来。</li>
     * </ol>
     * <p>方块实体那边已同步改成<b>不往标记槽写任何物品</b>（{@code setFluidMarker} 写空栈 +
     * 读档时清理旧版本的桶展示物），因此「桶」这条路径在数据层就不存在。</p>
     *
     * <h2>降级（无贴图 / 未同步）</h2>
     * <ol>
     *     <li><b>资源 id 解析不出 FluidStack</b>（未注册 / {@code Fluids.EMPTY} / S2C 还没到达）：
     *     {@link GhostMarkerRenderer#toFluidStack} 返回 {@link FluidStack#EMPTY}，
     *     本方法落到原版 {@code super.renderSlot}（该槽没有物品 ⇒ 空槽）。</li>
     *     <li><b>FluidStack 有效但没有 still 贴图</b>：{@link GhostMarkerRenderer#renderFluid}
     *     自己判空后不画任何四边形；本方法仍然 {@code return}（不再走 super），
     *     于是该格保持<b>空槽</b> —— 这是有意的：绝不为了「能看见」退回桶 / 另造图标。</li>
     * </ol>
     * <p>两种情况都只是「看不见图标」，<b>标记语义与数据完全不受影响</b>（流体仍在
     * {@code fluidMarkerIds[]} 里，网络仍按 FluidResource 进料）；悬停时 tooltip 仍由
     * {@link #renderTooltip} 给出流体名（名字与渲染贴图互不依赖）。</p>
     */
    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final Slot slot) {
        final int row = menu.markerRowOf(slot);
        if (row >= 0 && menu.getMarkerForm(row) >= 1) {
            final FluidStack fluid = GhostMarkerRenderer.toFluidStack(fluidMarkerId(row), 1L);
            if (!fluid.isEmpty()) {
                // 流体 / 气体标记：铺该流体的 still 贴图（不是桶、不是物品图标）
                GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, fluid);
                return;
            }
        }
        // 物品标记 / 空槽 / 流体降级：原版槽位渲染（物品图标 / 数量角标 / 拖拽预览 / hover 高亮）
        super.renderSlot(guiGraphics, slot);
    }

    /** 第 {@code row} 行「直接流体 / 气体标记」的资源 id（无则 null；只在 S2C 快照里取）。 */
    @Nullable
    private ResourceLocation fluidMarkerId(final int row) {
        if (row < 0 || row >= syncedConfigs.length) {
            return null;
        }
        final KeeperSlotConfig config = syncedConfigs[row];
        return config != null && config.isFluid() ? config.id() : null;
    }

    // ==================== 文字 ====================

    /**
     * 面板内的文字：①标题（超宽按单词折行）；②<b>两列列标签（各两行）</b>。
     *
     * <p><b>行内不画任何文字</b>：每行只有 [−] / 输入框 / [+] 三个控件 + 两个 ✓/✗ 按钮，
     * 单位与桶换算都在输入框 tooltip 里；目标值只存在于可编辑的输入框 ——
     * <b>[+] 右侧没有、也不允许再出现重复数字</b>（自检对「代码里在这段区间没有 drawString」
     * 与「这段区间只有 4px 宽」双重断言）。</p>
     * <p>列标签画在各自开关列<b>正上方</b>：第一行 y=4..12、第二行 y=13..21（两行各 9px），
     * 第一行 Java 控件从 y=26 起 ⇒ 5px 间隙；标题按 {@link RsccKeeperGeometry#TITLE_BAND_W}
     * 折行后最宽 116px，列标签左沿 ≥ 130 ⇒ 横向不相交（见 {@link RsccKeeperGeometry#COLUMN_LABEL_Y}）。</p>
     */
    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        drawTitle(guiGraphics);
        // 开关的 ✓/✗ 由按钮本体画（控件之上、拖拽物品之下），这里不再叠加任何图标 / 边框。
        // 列标签：Component 静态缓存，每帧只量宽度 + 画两行（不拼字符串）。
        drawColumnLabel(guiGraphics, COLUMN_LABEL_AUTOCRAFT_1, COLUMN_LABEL_AUTOCRAFT_2, AUTOCRAFT_BTN_X);
        drawColumnLabel(guiGraphics, COLUMN_LABEL_OVERFLOW_1, COLUMN_LABEL_OVERFLOW_2, OVERFLOW_BTN_X);
    }

    /**
     * 面板标题：按 {@link RsccKeeperGeometry#TITLE_BAND_W} 折行后逐行绘制（无阴影）。
     * <p>为什么折行：英文标题 {@code Advanced Resource Quantity Keeper} 实测 184px，
     * 一行会横穿整个面板、压到列标签带（130..184）与插件槽列；设计文档 V3 §4 给标题留的
     * 就是 8..116。中文标题 81px 不会折行（{@code Font#split} 原样返回一行），
     * 因此中文界面观感不变。折行<b>不截断文案</b>、不改 key。</p>
     */
    private void drawTitle(final GuiGraphics guiGraphics) {
        final List<FormattedCharSequence> lines = font.split(title, RsccKeeperGeometry.TITLE_BAND_W);
        // 标题带只有两行的高度（6..14 / 15..23；24 起是列标签与第一行控件）：
        // 内置中英标题折行后分别是 1 行 / 2 行；第三方语言包若更长，最多画两行，
        // 绝不让第 3 行压到列标签 / 第一行控件上。
        final int count = Math.min(lines.size(), 2);
        for (int i = 0; i < count; i++) {
            guiGraphics.drawString(font, lines.get(i), 8,
                RsccKeeperGeometry.TITLE_Y + i * RsccKeeperGeometry.TITLE_LINE_H,
                COLOR_TITLE, false);
        }
    }

    /**
     * 画一个<b>两行</b>列标签块：两行都水平居中于对应开关列（列心 = 列左沿 + 开关宽/2）。
     * <p>块宽 = 两行里较宽那一行；x 的唯一实现在 {@link RsccKeeperGeometry#columnLabelX}
     * （与几何自检同一公式），块内每一行再按自身宽度居中，因此短的那行不会左右跳。
     * 两行分别画在 {@link #COLUMN_LABEL_Y} 与 {@code + COLUMN_LABEL_LINE_H}，无阴影。</p>
     */
    private void drawColumnLabel(final GuiGraphics guiGraphics, final Component line1,
                                 final Component line2, final int columnX) {
        final int width1 = font.width(line1);
        final int width2 = font.width(line2);
        final int blockWidth = Math.max(width1, width2);
        final int x = RsccKeeperGeometry.columnLabelX(columnX, blockWidth);
        guiGraphics.drawString(font, line1, x + (blockWidth - width1) / 2,
            COLUMN_LABEL_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, line2, x + (blockWidth - width2) / 2,
            COLUMN_LABEL_Y + COLUMN_LABEL_LINE_H, COLOR_TITLE, false);
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
            // 两个开关回到全工程统一的 ✓/✗ 样式：消息每帧按服务端权威值回写
            // （不能用点击时的旧值，否则服务端拒绝 / 多端同步时会显示错）。
            // 没装自动合成升级 → 「自动合成」真正禁用，并画灰色 ✗（§7✗，与基础版
            // QuantityKeeperScreen 同一口径）；它与「过量销毁」的红色 ✗ 在禁用态下一眼可分。
            autocraftButtons[row].active = menu.hasAutocraftingUpgrade();
            final boolean hasUpgrade = autocraftButtons[row].active;
            if (hasUpgrade) {
                McGui.refreshToggle(autocraftButtons[row], menu.isAutoCraft(row));
            } else {
                autocraftButtons[row].setMessage(Component.literal("§7✗"));
            }
            McGui.refreshToggle(overflowButtons[row], menu.isDestroyOverflow(row));
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
        // 注意：两个开关的 ✓/✗ 由按钮本体绘制（不在这里自绘图标 / 边框）。
        // 自绘控件 tooltip：hover 判定与绘制范围严格一致
        for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
            final Button autocraft = autocraftButtons[row];
            // 用几何判定（不能用 isMouseOver：控件 inactive 时它恒为 false，禁用态就拿不到提示了）
            if (autocraft != null && isHovering(AUTOCRAFT_BTN_X, ROW_Y + row * ROW_STEP + TOGGLE_DY,
                TOGGLE_BTN_W, TOGGLE_BTN_H, mouseX, mouseY)) {
                // 可分辨性（硬要求）：两个开关同形，所以悬停时要能明确回答「这是哪一个、点它切什么」。
                // 第一行 = 功能名（自动合成），第二行 = 点击语义，第三行 = 当前状态；
                // 过量销毁那一段同样有自己的一整套文案，两者不会共用一句话。
                guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "autocraft_label"),
                    Component.translatable(LANG + "autocraft_header.tip"),
                    Component.translatable(menu.hasAutocraftingUpgrade()
                        ? (menu.isAutoCraft(row) ? LANG + "autocraft_on.tip" : LANG + "autocraft_off.tip")
                        : LANG + "autocraft_no_upgrade.tip")),
                    java.util.Optional.empty(), mouseX, mouseY);
                return;
            }
            final Button overflow = overflowButtons[row];
            if (overflow != null && isHovering(OVERFLOW_BTN_X, ROW_Y + row * ROW_STEP + TOGGLE_DY,
                TOGGLE_BTN_W, TOGGLE_BTN_H, mouseX, mouseY)) {
                // 过量销毁：功能名 + 点击语义 + 当前状态 + 多台仲裁规则（与自动合成完全不共用文案）。
                guiGraphics.renderTooltip(font, List.of(
                    Component.translatable(LANG + "overflow_label"),
                    Component.translatable(LANG + "overflow_header.tip"),
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

    /**
     * 槽位 tooltip：物品标记交给原版；<b>流体 / 气体标记给出流体自身的名字</b>（既有工具，不自拼）。
     *
     * <h2>为什么流体这条要自己补一次</h2>
     * <p>本轮把流体标记槽改回<b>流体贴图渲染</b>（槽里不再放桶物品），因此
     * {@code super.renderTooltip} 对空槽什么都不画 —— 没有这一句，鼠标停在岩浆格上就完全没有提示。
     * 这里调用的是工程既有、唯一实现 {@link GhostMarkerRenderer#renderFluidTooltip}：
     * 它只输出 {@code FluidType#getDescription()}（= {@code FluidStack#getHoverName}，
     * RS {@code FluidStackFluidRenderer#getTooltip} 同源），是<b>可翻译 Component</b>，
     * 不是 {@code Component.literal} 硬拼；它<b>不拼数量 / 桶换算</b>，也不再叠第二行。</p>
     * <p><b>不覆盖别的模组</b>：物品标记槽仍走上面 {@code super.renderTooltip} 的原版物品 tooltip；
     * 流体槽里没有物品，原版这一帧本来就不会画，本类这一句是唯一的一份，不会出现两个框。</p>
     * <p>「<b>真正空槽</b>」（既无物品标记也无流体 / 气体标记）仍补一条用法提示；
     * 仍问一次 {@code vanillaSlotTooltipWillDraw}：万一槽里有物品，原版这一帧已经画过，不能再叠第二个框。</p>
     */
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
        // 流体 / 气体标记槽：槽里没有物品（数据层也不放桶）⇒ 原版不会画，这里补「流体自身」那一行；
        // 名字来自 FluidType#getDescription()（可翻译 Component），不是 literal、不自拼数量。
        // 仍要求槽内为空：万一旧存档的桶还没被清掉（原版这一帧已经画了物品 tooltip），
        // 就只保留原版那一个框，绝不叠第二个。
        final ResourceLocation fluidId = fluidMarkerId(row);
        if (fluidId != null && hoveredSlot.getItem().isEmpty()) {
            GhostMarkerRenderer.renderFluidTooltip(guiGraphics, font, fluidId,
                false, null, mouseX, mouseY);
            return;
        }
        // 只有「真正空槽」（既无物品标记也无流体 / 气体标记）才补一条用法提示；
        // 仍问一次 vanillaSlotTooltipWillDraw：万一槽里有物品，原版这一帧已经画过，不能再叠第二个框。
        if (hoveredSlot.getItem().isEmpty() && !menu.hasMarker(row)
            && !cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
                .vanillaSlotTooltipWillDraw(hoveredSlot)) {
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
