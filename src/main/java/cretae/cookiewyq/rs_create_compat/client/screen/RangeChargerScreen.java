package cretae.cookiewyq.rs_create_compat.client.screen;

import com.simibubi.create.AllSpecialTextures;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.RepeatButton;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccEnergyBar;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import cretae.cookiewyq.rs_create_compat.network.ShowChargerRangePacket;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * 范围充电器界面：能量条（带框）+ 三轴范围（输入框编辑 / 加减按钮，shift ±5）
 * + 右侧插件槽（速度升级提升充电速率）+ 玩家背包。
 *
 * <h2>第 98 轮新增：两颗按钮（用户原话「单独搞一个按钮出来」「再加上一个按钮黑名单或白名单」）</h2>
 * <p>「查看」按钮（{@code (150,20,36,14)}）正下方竖排两颗同宽按钮，与它共用同一列、同一高度：</p>
 * <ul>
 *     <li><b>「高亮」</b>{@code (150,37,36,14)}：本机的「高亮充电目标」开关（落盘到方块实体、
 *     默认开）。点击走原版菜单按钮通道
 *     {@code handleInventoryButtonClick(containerId, RangeChargerMenu.BUTTON_TOGGLE_HIGHLIGHT)}
 *     —— 服务端权威、与既有的范围 ± 按钮/查看框选同一条协议，客户端不自己记状态。
 *     <b>状态怎么看出来</b>：文案随 {@code menu.isHighlightEnabled()}（服务端经 ContainerData
 *     同步）在「高亮 / 高亮关」之间切换，tooltip 里再补一行「当前：开启 / 关闭」；</li>
 *     <li><b>「过滤」</b>{@code (150,54,36,14)}：打开充电过滤子界面（{@link ChargerFilterScreen}）。
 *     点击发 {@code OpenChargerFilterPacket}（C2S，服务端校验归属后回 S2C 快照，
 *     再由 {@code ClientPayloadSink} 调 {@code ChargerFilterScreen#openOrRefresh}）。</li>
 * </ul>
 * <p><b>为什么 tooltip 手写而不是 {@code .tooltip(Tooltip.create(...))}</b>：高亮按钮的 tooltip 是
 * <b>动态的</b>（当前开关状态 + 总闸是否被配置关掉，最多三行），而 {@code Tooltip.create(Component)}
 * 只接受静态文本；本工程的既有做法也是「可交互元素一律手动渲染 tooltip、hover 判定与绘制同源」
 * （见 {@link #renderHoverTooltips}）。</p>
 *
 * <h2>总闸（降级为配置项）</h2>
 * <p>{@code Config.rangeChargerHighlightTargets} 从「唯一开关」降级为<b>总闸</b>：
 * 它为 false 时所有充电器都不发高亮包、按钮也不生效，因此 tooltip 会额外加一行
 * 「配置中已全局关闭」——玩家不会对着一个点了没反应的按钮猜原因。</p>
 *
 * <h2>第 102 轮：信息行改成「三种高亮的图例 + 已满跳过计数」，速率上移到顶栏</h2>
 * <p>用户实机反馈两件事：①「正在给谁充电要一眼分得清」（掉落实体要边框、玩家要高亮玩家、
 * 方块要高亮方块）②「满电的就跳过，减少无意义的次数」。第一件事的另一半是<b>让玩家知道
 * 哪个颜色代表哪类目标</b>，第二件事则<b>完全没有视觉副作用</b>（不充、不高亮、不发包），
 * 必须有一个计数把它变成可见事实 —— 两者都要位置。</p>
 * <p>可用的书写空间只有两条 9px 高的行（顶栏 y=6、信息行 y=72，其余被能量条 / 三行 XYZ /
 * 三颗侧边按钮 / 插件槽 / 背包占满，见 {@code GUI_TEXTURE_ISSUES.md} 的版面记录），
 * 于是这样分配：</p>
 * <ul>
 *     <li><b>顶栏右侧</b>：充电速率（右对齐，短键 {@code rate_short}，见 {@link #RATE_RIGHT}）；</li>
 *     <li><b>信息行</b>：左边三格图例（色块 + 类型名，颜色与
 *     {@code ChargerTargetHighlight} 的公开常量同源），右端「已满跳过 %s」（数据槽 13）。</li>
 * </ul>
 * <p>图例优先、数字让位：{@link #drawLegend} 先把三格画完并返回右边界，「已满跳过」只在
 * 剩余宽度里画（数字太长时整体缩字号，下限 0.6 倍，完整数字仍在悬停提示里）—— 图例缺一格等于「颜色 → 类型」
 * 这条信息永久丢失，而跳过数只是统计值，两者不对等。目标数从这一行挪进悬停提示
 * （{@link #renderValueTooltips}，与图例同一处 hover，按住 Shift 显示）。</p>
 */
public class RangeChargerScreen extends AbstractContainerScreen<RangeChargerMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/range_charger.png");

    /**
     * 能量条内容区左上角（GUI 内坐标；宽度固定 = {@link RsccEnergyBar#WIDTH} = 16）。
     * <p><b>第 108 轮：这组值只出现一次</b> —— {@code init}（建 widget 与悬浮数值句）与
     * {@code renderBg}（画 1px 外框 + {@code #2C2C2C} 槽底）都引用它。旧实现两处各写魔数
     * {@code 26 / 20}，改一处忘另一处就是「框在左、条在右」；条本身的实现已统一到
     * {@link RsccEnergyBar}（= RS 原版 {@code ProgressWidget} + 同款黑框/深灰槽底），
     * 而本类就是这套实现的<b>参考实现</b>（FE 能量立方 / 分解发电机都对着它改）。
     */
    private static final int ENERGY_BAR_X = 26;
    private static final int ENERGY_BAR_Y = 20;

    private final EditBox[] rangeBoxes = new EditBox[3];
    private final RepeatButton[] stepButtons = new RepeatButton[6];
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private RsccRedstoneModeButton.RsccRedstoneModeProperty rscc$redstoneMode;
    /** 上次本地修改范围的 tick（快速点击时防止服务端同步值覆盖输入框，造成"反应慢"）。 */
    private long lastLocalEditTick = -1000;

    // ===== 「查看充电范围」框选（Create 蓝图同款 Outliner；坐标由服务端 S2C 下发） =====
    /** 框选显示时长（tick）：100 tick = 5 秒，之后由 Outliner 自己再淡出 8 tick。 */
    private static final int RANGE_OUTLINE_TTL = 100;
    /**
     * 显示窗口内「每 tick 重喂一次」用的短 TTL（tick）。
     * <p>Outliner 的条目在 {@code tickOutlines} 里每 tick 递减 {@code ticksTillRemoval}；
     * 每 tick 用 2 重喂 ⇒ 只要界面还开着，条目永远处于「未开始淡出」，可见时间由
     * {@link #rangeOutlineUntil} 控制；停止重喂后 2 tick 开始淡出、8 tick 后移除。</p>
     */
    private static final int RANGE_OUTLINE_REFRESH_TTL = 2;
    /**
     * 框选颜色：橙色，<b>刻意与蓝图框选的 0x6886c5 不同</b>（用户要求「颜色需要与蓝图不同」）。
     * <p>⚠️ {@code OutlineParams#colored(int)} 的入参是 RGB（{@code new Color(color, false)}），
     * 不能带 alpha —— 面的透明度由 Outliner 内部按淡出计算；这里给 {@code 0xFF9F1C}（无 alpha 通道）。</p>
     */
    private static final int RANGE_OUTLINE_COLOR = 0xFF9F1C;
    /**
     * 框选线宽（格）：{@code 1/16}，与蓝图框选 / 本模组其它范围框同一口径。
     *
     * <p><b>为什么必须显式给（本轮修复的落点之一）</b>：旧实现只写了 {@code colored(...)}，
     * 既没有面贴图、也没有线宽 ⇒ {@code OutlineParams} 的默认线宽只有 {@code 1/32} 格
     * （≈ 半个像素），一整个 50×50×50 的框在实机里细到几乎不可见；而 GUI 打开时世界又被
     * 菜单背景虚化，玩家看到的就是「点了没有任何反应」。现在补上棋盘面贴图 + 蓝图同款线宽。</p>
     */
    private static final float RANGE_OUTLINE_LINE_WIDTH = 1 / 16f;
    /** Outliner 槽位 key：重复 show 会替换同一条目，remove 一次即清干净。 */
    private static final String RANGE_OUTLINE_SLOT = RS_Create_Compat.MODID + ":range_charger_range";
    /** 当前正在展示的框选快照（{@code null} = 没有框）。 */
    @Nullable
    private ShowChargerRangePacket rangeSnapshot;
    /** 当前框选的渲染包围盒（与服务端充电扫描同口径，见 {@link #showChargerRange}）。 */
    @Nullable
    private AABB rangeBox;
    /** 这次框选截止的客户端 gameTime（到期后不再重喂，由 Outliner 自行淡出）。 */
    private long rangeOutlineUntil = Long.MIN_VALUE;

    // ===== 文字坐标与可用宽度（超出即整体缩字号，绝不截断；保证不压相邻文字 / 右侧升级槽 / 面板边） =====
    /** 标题颜色：黑色（浅底上白字看不清，与定量保持器一致）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int UPGRADES_LABEL_X = 168;
    /** 右侧升级槽首列背景单元左边界为 x=187（Menu 坐标）。 */
    private static final int UPGRADES_LABEL_MAX_W = 187 - UPGRADES_LABEL_X;
    /**
     * 顶栏右侧「充电速率」的右对齐边界（{@code x=167} = 右列「插件」标签起点 168 再留 1px）。
     *
     * <p><b>第 102 轮为什么把速率从左下信息行挪到顶栏</b>：左下那一行（y=72..84，13px 高，只有
     * 一行文字的空间）现在要放用户这轮要的两样东西 —— <b>三种高亮的图例</b>与
     * <b>「已满跳过 N」</b>。整块面板能写字的地方只有两条 9px 高的行（顶栏 y=6、信息行 y=72），
     * 而图例（三格色块 + 类型名）+ 跳过计数 + 目标数 + 速率在一条 178px 宽的行里放不下
     * （英文标签更宽）。因此速率移到顶栏右侧右对齐：它是唯一「有固定单位、占宽小」的数值。
     * 目标数则改到信息行的悬停提示里（与图例同一处 hover，见 {@link #renderValueTooltips}）。</p>
     */
    private static final int RATE_RIGHT = 167;
    /** 左下信息行的图例起点（面板内边 x=3 起，留出左边距）。 */
    private static final int LEGEND_X = 8;
    /** 「已满跳过 N」的右对齐边界（右列插件槽左缘 = 186，留 1px）。 */
    private static final int SKIPPED_RIGHT = 185;
    /** 图例色块边长（px）。 */
    private static final int LEGEND_SWATCH = 5;
    /** 色块与类型名之间的间距（px）。 */
    private static final int LEGEND_SWATCH_GAP = 2;
    /** 两个图例条目之间的间距（px）：取 6 是为了给英文标签（Block / Item / Player）留出余量。 */
    private static final int LEGEND_ENTRY_GAP = 6;
    /**
     * 图例三格的颜色，<b>与 {@link cretae.cookiewyq.rs_create_compat.client.ChargerTargetHighlight}
     * 的公开常量同源</b>（颜色语义只有一处定义：世界里画什么颜色、界面色块画什么颜色，
     * 绝不各写一份十六进制）。顺序 = 方块 / 掉落物 / 玩家。
     */
    private static final int[] LEGEND_COLORS = {
        cretae.cookiewyq.rs_create_compat.client.ChargerTargetHighlight.COLOR_BLOCK,
        cretae.cookiewyq.rs_create_compat.client.ChargerTargetHighlight.COLOR_ITEM,
        cretae.cookiewyq.rs_create_compat.client.ChargerTargetHighlight.COLOR_PLAYER};
    /** 图例三格的语言键（见交付给 Lead 的语言键清单）。 */
    private static final String[] LEGEND_KEYS = {
        "gui.rs_create_compat.range_charger.legend.block",
        "gui.rs_create_compat.range_charger.legend.item",
        "gui.rs_create_compat.range_charger.legend.player"};
    /** 左下信息行的基线 y（图例色块 / 跳过计数 / 悬停 tooltip 判定范围共用这一行）。 */
    private static final int LABEL_ROW_Y = 72;
    /** 单行文字的判定高度（原版字体 9px）。 */
    private static final int LABEL_ROW_H = 9;

    // ===== 第 98 轮：右侧两颗按钮（与「查看」同列、同宽同高；x=150..186 在 ± 按钮与插件槽之间） =====
    /** 三颗按钮共用的几何：x = 150、宽 36、高 14（「查看」= y 20，本轮的「高亮」/「过滤」= 37 / 54）。 */
    private static final int SIDE_BTN_X = 150;
    private static final int SIDE_BTN_W = 36;
    private static final int SIDE_BTN_H = 14;
    private static final int HIGHLIGHT_BTN_Y = 37;
    private static final int FILTER_BTN_Y = 54;

    /** 「高亮」按钮（状态由 {@link #refreshHighlightButton()} 每帧回写）。 */
    private Button highlightButton;
    /** 「过滤」按钮（打开子界面）。 */
    private Button filterButton;

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
        // 能量条（照搬 RS 控制器 ProgressWidget：16x51 与 XYZ 三行高度齐平 + 悬浮显示能量数值）。
        // 第 108 轮起「条 + 外框 + 数值句」整套抽到 RsccEnergyBar（内部仍是 RS 原版 ProgressWidget，
        // sprite = refinedstorage:widget/progress_bar），三家纯电界面共用；本类作为参考实现只留参数。
        // 唯一可见变化 = 悬浮数值句加了千分位（RsccNumberField.grouped，与立方 / 发电机同一口径）。
        addRenderableWidget(RsccEnergyBar.create(leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
            RsccEnergyBar.DEFAULT_HEIGHT,
            () -> menu.getEnergy(), () -> menu.getMaxEnergy(),
            () -> java.util.List.of(RsccEnergyBar.valueLine(menu.getEnergy(), menu.getMaxEnergy()))));
        // 三行范围：标签 + 输入框 + [-] [+]（无限范围升级时不隐藏 XYZ 输入，只放开上限可随意调大）
        addRangeRow(20, 0);
        addRangeRow(37, 1);
        addRangeRow(54, 2);
        // 「查看充电范围」：放在 + 按钮（x=128..148）与右侧插件槽（背景 x=187 起）之间的空位；
        // 高 14 与上面三行 ± 按钮同高。点击后由服务端校验并回 S2C 快照，客户端不猜坐标。
        // ⚠️ 按钮只有 36 宽（左右各 2px 内边距 ⇒ 可用 32px），而 4 个汉字 = 36px：
        // 原版 AbstractButton#renderString 走 Widget#renderScrollingString（字节码已核实），
        // 超宽会变成来回滚动的走马灯 ⇒ 语言串用两字「查看」，完整说明放 tooltip。
        addRenderableWidget(Button.builder(
                Component.translatable("gui.rs_create_compat.range_charger.show_range"),
                button -> toggleChargerRange())
            .bounds(leftPos + 150, topPos + 20, 36, 14)
            .tooltip(Tooltip.create(
                Component.translatable("gui.rs_create_compat.range_charger.show_range.tip")))
            .build());
        // 第 98 轮：「高亮充电目标」开关（本机、落盘、默认开）+ 「充电过滤」子界面入口。
        // 两颗按钮与上面的「查看」同列同宽同高，垂直间距 17（20 → 37 → 54），
        // 因此三颗按钮在视觉上是一组；tooltip 一律手写（动态内容，见类注释）。
        highlightButton = Button.builder(
                Component.translatable("gui.rs_create_compat.range_charger.highlight.on"),
                button -> toggleHighlight())
            .bounds(leftPos + SIDE_BTN_X, topPos + HIGHLIGHT_BTN_Y, SIDE_BTN_W, SIDE_BTN_H)
            .build();
        addRenderableWidget(highlightButton);
        filterButton = Button.builder(
                Component.translatable("gui.rs_create_compat.range_charger.filter"),
                button -> openChargerFilter())
            .bounds(leftPos + SIDE_BTN_X, topPos + FILTER_BTN_Y, SIDE_BTN_W, SIDE_BTN_H)
            .build();
        addRenderableWidget(filterButton);
        refreshHighlightButton();
        // 红石模式（忽略 / 高电平 / 低电平）：复用 RS 原版侧边按钮，挂在面板左侧外缘
        rscc$redstoneMode = RsccRedstoneModeButton.attach(this);
    }

    /**
     * 「高亮」按钮的文案随服务端同步的开关状态切换（每帧回写，服务端一改立刻跟上）。
     * <p><b>为什么文案而不是图标</b>：按钮只有 36×14（可用宽 32px），两三个汉字是上限；
     * 换文案是唯一「不新增贴图、不溢出、且一眼能看出状态」的做法。
     * 英文侧 {@code highlight.off} 特意取短词（长串会被原版按钮的滚动文字机制切成走马灯）。</p>
     */
    private void refreshHighlightButton() {
        if (highlightButton == null) {
            return;
        }
        highlightButton.setMessage(Component.translatable(
            menu.isHighlightEnabled()
                ? "gui.rs_create_compat.range_charger.highlight.on"
                : "gui.rs_create_compat.range_charger.highlight.off"));
    }

    /**
     * 切换本机「高亮充电目标」开关：走<b>原版菜单按钮通道</b>（与范围 ± / 查看框选同一条协议）。
     * <p>为什么不在客户端自己翻转状态：开关落盘在方块实体里，客户端菜单没有 BE（见
     * {@link RangeChargerMenu#getChargerPos()} 的说明），本地翻转只会造出「界面说开、服务端说关」
     * 的分叉；服务端处理完会通过 ContainerData 数据槽 12 同步回来，界面下一帧就变。</p>
     */
    private void toggleHighlight() {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                RangeChargerMenu.BUTTON_TOGGLE_HIGHLIGHT);
        }
    }

    /**
     * 打开「充电过滤」子界面：只发一次 C2S 请求，界面由服务端回的快照驱动。
     * <p>为什么不直接 {@code setScreen}：过滤内容（勾选集合 + 已知玩家名单）是服务端权威数据，
     * 客户端没有；由服务端组包后经 {@code ClientPayloadHooks#openChargerFilter} →
     * {@code ChargerFilterScreen#openOrRefresh} 打开，才能保证「看到的就是服务端正在用的」。</p>
     */
    private void openChargerFilter() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.OpenChargerFilterPacket(menu.containerId));
    }

    /** 红石模式由服务端权威值驱动显示（控件本体复用 RS 原版，hover 时才手动渲染 tooltip）。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (rscc$redstoneMode != null) {
            rscc$redstoneMode.set(menu.rscc$getRedstoneMode().ordinal());
        }
        // 框选维护：显示窗口内每 tick 重喂一次（保证「点一下一定看得到」，不依赖一次性 show 不被别人清掉）
        updateRangeOutline();
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
        // 背景包含全部槽位与能量条底槽；能量进度由 RsccEnergyBar 里的 RS ProgressWidget 绘制
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 166);
        // 能量条外框（16x51，与 widget 位置一致）：1px 纯黑 + #2C2C2C 槽底。
        // 第 108 轮起与 FE 能量立方 / 分解发电机共用同一份实现（RsccEnergyBar.drawTrack），
        // 像素与第 98 轮完全一致（同坐标 / 同尺寸 / 同色值）。
        RsccEnergyBar.drawTrack(guiGraphics, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
            RsccEnergyBar.DEFAULT_HEIGHT);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 轴标签：XYZ（放入无限范围升级也不隐藏，只是取消上限可随意调大）
        drawLabel(guiGraphics, 20, "X");
        drawLabel(guiGraphics, 37, "Y");
        drawLabel(guiGraphics, 54, "Z");
        // 插件栏标题（左移，避免与插件槽重叠；英文原串 "Upgrades" 放不下 19px ⇒ 语言值已收成 "Upg"。
        // 第 104 轮起走 drawFitLine：完整显示、绝不省略，也绝不压到 x=187 起的槽位）
        RsccTextFit.drawFitLine(guiGraphics, font,
            Component.translatable("gui.rs_create_compat.range_charger.upgrades"),
            UPGRADES_LABEL_X, 4, UPGRADES_LABEL_MAX_W, 0xFFFFFF);
        // 顶栏右侧：充电速率（第 102 轮从信息行上移，见 RATE_RIGHT 的说明）
        drawRateLabel(guiGraphics);
        // 左下信息行：三种目标的高亮图例（自绘色块 + 类型名） + 右对齐的「已满跳过 N」
        drawInfoRow(guiGraphics);
    }

    /**
     * 顶栏右侧的「充电速率」：右对齐到 {@link #RATE_RIGHT}，并且<b>绝不压到标题</b>。
     *
     * <p>标题宽度随语言变化（英文 {@code Range Charger} 比中文宽得多），因此可用宽度是
     * <b>现算</b>的：{@code RATE_RIGHT - (标题右边 + 6)}。速率很大（十位数）时<b>整体缩字号</b>
     * （下限 {@link RsccTextFit#MIN_SCALE}）而不是截断——第 104 轮用户口径：界面文本一律完整显示。
     * 完整值 + 构成说明仍然在悬停提示里（{@link #renderValueTooltips}）。</p>
     */
    private void drawRateLabel(final GuiGraphics guiGraphics) {
        final String text = Component.translatable(
            "gui.rs_create_compat.range_charger.rate_short", chargeRate()).getString();
        final int maxWidth = rateLabelMaxWidth();
        final int drawnWidth = fittedWidth(text, maxWidth);
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(text),
            RATE_RIGHT - drawnWidth, titleLabelY, maxWidth, 0xFFFFFF);
    }

    /**
     * 一段文本在 {@code maxWidth} 内「完整显示」时实际占的宽度（= 缩字号之后的宽度）。
     * <p>绘制（{@link #drawRateLabel}）与悬停判定（{@link #isOverRateLabel}）都用它，
     * 所以「看到哪一段就悬停得到哪一段」依然成立。缩放口径与 {@link RsccTextFit#drawFitLine}
     * 完全一致（下限 {@link RsccTextFit#MIN_SCALE}）。</p>
     */
    private int fittedWidth(final String text, final int maxWidth) {
        final Component component = Component.literal(text);
        final float scale = Math.max(RsccTextFit.MIN_SCALE,
            RsccTextFit.requiredScaleForLine(font, component, maxWidth));
        return Math.round(font.width(component) * scale);
    }

    /** 速率文字可用的像素宽（绘制与悬停判定同源，避免「看得到却悬停不到」）。 */
    private int rateLabelMaxWidth() {
        return Math.max(1, RATE_RIGHT - (titleLabelX + font.width(title) + 6));
    }

    /**
     * 左下信息行：<b>图例</b>（三格自绘色块 + 类型名，颜色与世界里画的框同源）+
     * 右对齐的「已满跳过 %s」。
     *
     * <h2>为什么是「图例 + 跳过」这一组合</h2>
     * <p>用户这一轮提了两件事：①「正在给谁充电要一眼分得清」②「满电的就跳过，减少无意义的次数」。
     * 后者<b>没有任何视觉副作用</b>（不充、不高亮、不发包），只靠世界里的框是看不见的，
     * 因此必须有一个计数把它变成可见事实；前者则需要「颜色 → 类型」的对照。
     * 两者都属于「本机当下的状态」，放在同一行正好一行放得下（图例约 100~120px，
     * 跳过计数约 30~50px，行宽 8..185 ⇒ 够用），超出时图例自动少画一格，
     * 绝不会压住右边的计数（见 {@link #drawLegend}）。</p>
     */
    private void drawInfoRow(final GuiGraphics guiGraphics) {
        // 顺序很关键：先画图例（内容固定，绝不能被数字挤掉），再在剩下的宽度里放「已满跳过 N」。
        // 第 104 轮：数字再长也**不截断**，而是在剩余宽度里整体缩字号（下限 0.6 倍）——
        // 「跳过数」是个统计值，缩到下限也比读成「已满跳过 1…」强；图例优先级仍然最高。
        final int legendEnd = drawLegend(guiGraphics, SKIPPED_RIGHT);
        final int available = Math.max(1, SKIPPED_RIGHT - (legendEnd + 6));
        final String skipped = skippedLabel();
        final int drawnWidth = fittedWidth(skipped, available);
        final int skippedX = Math.max(legendEnd + 6, SKIPPED_RIGHT - drawnWidth);
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(skipped), skippedX,
            LABEL_ROW_Y, available, 0xFFFFFF);
    }

    /** 「已满跳过 %s」的完整文字（服务端数据槽 13，客户端只显示）。 */
    private String skippedLabel() {
        return Component.translatable("gui.rs_create_compat.range_charger.skipped",
            menu.getSkippedFullTargets()).getString();
    }

    /**
     * 画图例：三格「色块 + 类型名」，从左到右排，<b>放不下就停</b>（宁可少一格也不压别的东西）。
     *
     * @param limitX 图例的右边界（不含）；超过它的条目直接不画
     * @return 图例实际画到的右边界（没画任何东西时 = {@link #LEGEND_X}）；
     *         「已满跳过」从它右边 6px 起画，两者绝不重叠
     */
    private int drawLegend(final GuiGraphics guiGraphics, final int limitX) {
        int x = LEGEND_X;
        int end = LEGEND_X;
        for (int i = 0; i < LEGEND_COLORS.length; i++) {
            final int entryX = i == 0 ? x : x + LEGEND_ENTRY_GAP;
            final String label = Component.translatable(LEGEND_KEYS[i]).getString();
            final int entryWidth = LEGEND_SWATCH + LEGEND_SWATCH_GAP + font.width(label);
            if (entryX + entryWidth > limitX) {
                break;
            }
            drawSwatch(guiGraphics, entryX, LABEL_ROW_Y + 2, LEGEND_COLORS[i]);
            guiGraphics.drawString(font, Component.literal(label),
                entryX + LEGEND_SWATCH + LEGEND_SWATCH_GAP, LABEL_ROW_Y, 0xFFFFFF, false);
            x = entryX + entryWidth;
            end = x;
        }
        return end;
    }

    /**
     * 自绘色块：{@link #LEGEND_SWATCH}×{@link #LEGEND_SWATCH} 的实心方块 + 上下各 1px 深色描边。
     * <p>画法与 {@code ChamberFaceConfigScreen} / {@code CollectionInputFaceConfigScreen} 的图例
     * 完全同一套（面板底色是浅灰，纯色块在浅底上边缘会糊掉，上下各压一条深色就立住了）。</p>
     */
    private static void drawSwatch(final GuiGraphics guiGraphics, final int x, final int y, final int color) {
        guiGraphics.fill(x, y, x + LEGEND_SWATCH, y + LEGEND_SWATCH, color);
        guiGraphics.fill(x, y, x + LEGEND_SWATCH, y + 1, 0xFF2B2B2B);
        guiGraphics.fill(x, y + LEGEND_SWATCH - 1, x + LEGEND_SWATCH, y + LEGEND_SWATCH, 0xFF2B2B2B);
    }

    /**
     * 当前充电速率（FE/t）。
     *
     * <h2>为什么不在这里算（2026-10-17 修：界面显示负数）</h2>
     * <p>旧实现是 {@code Config.rangeChargerChargeRate << menu.getUpgradeCount()}。而
     * {@code Config.rangeChargerChargeRate} 允许到 1,000,000、满堆叠 = power 18 ⇒
     * {@code 1e6 << 18} 在 Java 里<b>溢出成负数</b>，于是「方块实体充得好好的、界面却显示负数」。
     * 更糟的是它和方块实体<b>各写一份公式</b>：两张嘴说同一件事，任何一侧改了另一侧都不会红。</p>
     *
     * <p>修法抽成 {@link RangeChargerMenu#getChargeRate()}：界面<b>只读</b>菜单的同步值，
     * 菜单读的是方块实体 <b>一个 tick 内</b>由 {@code ContainerData} 送来的
     * {@code getUpgradeCount()}（= {@code RsccRateLimiter.power(...)}，已经夹到
     * {@code [0, MAX_POWER]}），再调用<b>与方块实体同一个</b>
     * {@link RsccRateLimiter#rateSaturating(int, int, int)}。</p>
     */
    private int chargeRate() {
        return menu.getChargeRate();
    }

    /** 判定点是否落在左下信息行（图例 + 已满跳过）的绘制范围内 —— 判定范围与绘制范围同源。 */
    private boolean isOverInfoRow(final double mouseX, final double mouseY) {
        return mouseX >= leftPos + LEGEND_X && mouseX < leftPos + SKIPPED_RIGHT
            && mouseY >= topPos + LABEL_ROW_Y && mouseY < topPos + LABEL_ROW_Y + LABEL_ROW_H;
    }

    /**
     * 判定点是否落在顶栏右侧的速率文字上：左边界按<b>当前速率</b>现算（与 {@link #drawRateLabel}
     * 同一个 {@link #fittedWidth} + 同一个宽度上限），因此「看得到哪一段就悬停得到哪一段」。
     */
    private boolean isOverRateLabel(final double mouseX, final double mouseY) {
        final String text = Component.translatable(
            "gui.rs_create_compat.range_charger.rate_short", chargeRate()).getString();
        final int left = RATE_RIGHT - fittedWidth(text, rateLabelMaxWidth());
        return mouseX >= leftPos + left && mouseX < leftPos + RATE_RIGHT
            && mouseY >= topPos + titleLabelY && mouseY < topPos + titleLabelY + LABEL_ROW_H;
    }

    /**
     * 两处自绘文字（顶栏速率 / 左下信息行）的悬停提示。
     *
     * <h2>为什么这两处要 tooltip</h2>
     * <p>顶栏速率在可用宽度里整体缩字号（速率能长到十位数，例如 {@code 16384000}），缩得太小读不出 ⇒
     * 悬停时给出<b>完整</b>数值 + 「基础值 × 速度升级」的构成说明。左下信息行是图例 + 跳过计数，
     * 悬停时把<b>颜色含义</b>说清楚（常显行），并把「本 tick 正在充几个对象 / 因已满跳过几个」
     * 放在 Shift 层（数值归附加层，与工程其它界面的分层习惯一致）。</p>
     *
     * <p>与其它 tooltip 同一帧纪律：{@code hoveredSlot != null} 时让位（原版会为有物品的槽画一份），
     * 两处出口各自带 {@code return}，同一帧绝不出两个框。</p>
     */
    private void renderValueTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (hoveredSlot != null) {
            return; // 槽位提示优先，同一处绝不叠两个框
        }
        if (isOverRateLabel(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                guiGraphics, font, java.util.List.of(),
                java.util.List.of(
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                        // 构成说明用的是菜单同步下来的等效档数（服务端权威），与上面的速率同源；
                        // 这里的 Config 只用来显示「基础值是多少」，不参与任何计算。
                        Component.translatable("gui.rs_create_compat.range_charger.rate.tip",
                            cretae.cookiewyq.rs_create_compat.Config.rangeChargerChargeRate,
                            menu.getUpgradeCount())),
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.ctrl(
                        Component.translatable("gui.rs_create_compat.range_charger.rate",
                            chargeRate()))),
                mouseX, mouseY);
            return;
        }
        if (isOverInfoRow(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                guiGraphics, font,
                java.util.List.of(Component.translatable(
                    "gui.rs_create_compat.range_charger.legend.tip")),
                java.util.List.of(
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                        Component.translatable("gui.rs_create_compat.range_charger.targets",
                            menu.getTargetCount())),
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                        Component.translatable("gui.rs_create_compat.range_charger.skipped.tip",
                            menu.getSkippedFullTargets()))),
                mouseX, mouseY);
        }
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

    /**
     * 点击「查看充电范围」：显示中 → 立刻移除；否则发 C2S 请求（坐标由服务端 S2C 回传）。
     *
     * <p>C2S 复用原版菜单按钮通道（{@code ServerboundContainerButtonClickPacket}）：
     * 服务端 {@code RangeChargerMenu#clickMenuButton} 先校验菜单仍有效（维度 / 距离 / 方块实体身份），
     * 再让方块实体回 S2C 快照 —— 客户端<b>不猜</b>坐标，也不信自己缓存的旧范围。</p>
     */
    private void toggleChargerRange() {
        if (isChargerRangeVisible()) {
            hideChargerRange();
            return;
        }
        // 先清掉可能残留在 Outliner 里的旧条目（上一次 5 秒已过、条目正在淡出）
        hideChargerRange();
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, RangeChargerMenu.BUTTON_SHOW_RANGE);
        }
    }

    /**
     * S2C 到达（服务端已校验）：按「直径口径」画出<b>三维立方体</b>框选。
     *
     * <h2>几何（必须与充电扫描同口径）</h2>
     * <p>⚠️ {@code rangeX/Y/Z} 是<b>直径</b>不是半径 —— 充电扫描用 {@code half = range / 2}
     * （{@code RangeChargerBlockEntity#scanPlayers} / {@code #scanItems}）。这里逐字同口径：
     * 三轴都先取半宽，奇数走整数除法（51 → 25），再与蓝图框选相同的
     * {@code new AABB(角点, 角点).expandTowards(1, 1, 1)} 组合，得到
     * {@code min = pos - half}、{@code max = pos + half + 1}（含整格）——
     * 与扫描用的 AABB 逐格相同，<b>不会画成 2 倍</b>。三轴都参与 ⇒ 包住整个立方体，不是平面。</p>
     *
     * <h2>显示方式（本轮修复）</h2>
     * <ol>
     *     <li>立刻用<b>棋盘面贴图 + 1/16 线宽</b>画出（旧实现只有 1/32 细线且没有面，实机几乎不可见）；</li>
     *     <li>显示窗口（{@link #RANGE_OUTLINE_TTL} = 5 秒）内由 {@link #containerTick()} 每 tick 重喂，
     *     不依赖「一次性 show 之后条目不被别人清掉」；</li>
     *     <li>关界面时<b>不立刻删除</b>，而是按剩余时间重新武装，让玩家关掉 GUI 后仍能在世界里
     *     看到橙框自然淡出（旧实现 {@code removed()} 里直接 remove ⇒ 玩家一关界面就什么都看不到）。</li>
     * </ol>
     */
    public void showChargerRange(final ShowChargerRangePacket packet) {
        final BlockPos pos = packet.pos();
        final int halfX = packet.rangeX() / 2;
        final int halfY = packet.rangeY() / 2;
        final int halfZ = packet.rangeZ() / 2;
        this.rangeBox = new AABB(
            pos.getX() - halfX, pos.getY() - halfY, pos.getZ() - halfZ,
            pos.getX() + halfX, pos.getY() + halfY, pos.getZ() + halfZ
        ).expandTowards(1, 1, 1);
        this.rangeSnapshot = packet;
        this.rangeOutlineUntil = gameTime() + RANGE_OUTLINE_TTL;
        feedRangeOutline(RANGE_OUTLINE_TTL);
    }

    /**
     * 显示窗口内每 tick 重喂一次框选；到期后停止重喂，让条目按最后一次的 2 tick TTL 自然淡出。
     * <p>Outliner 的条目是全局的：这样即使条目被别的路径清掉，下一 tick 就会补回来
     * （这正是蓝图 {@code chaseAABB} 每 tick 喂的同一个路子）。</p>
     */
    private void updateRangeOutline() {
        if (rangeSnapshot == null || rangeBox == null) {
            return;
        }
        if (gameTime() >= rangeOutlineUntil) {
            rangeSnapshot = null; // 停止重喂：条目 2 tick 后开始淡出、8 tick 后移除
            rangeBox = null;
            return;
        }
        feedRangeOutline(RANGE_OUTLINE_REFRESH_TTL);
    }

    /** 把当前框选喂给全局 Outliner（颜色 / 面贴图 / 线宽与蓝图框选同一套，只有颜色不同）。 */
    private void feedRangeOutline(final int ttl) {
        if (rangeBox == null) {
            return;
        }
        Outliner.getInstance()
            .showAABB(RANGE_OUTLINE_SLOT, rangeBox, ttl)
            .colored(RANGE_OUTLINE_COLOR)
            .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
            .lineWidth(RANGE_OUTLINE_LINE_WIDTH);
    }

    /** 框选是否还在显示窗口内（按钮二次点击用它判断「现在点一下是取消」）。 */
    private boolean isChargerRangeVisible() {
        return rangeSnapshot != null && rangeBox != null && gameTime() < rangeOutlineUntil;
    }

    /** 立即移除框选（二次点击关闭用；{@code Outliner.remove} 对不存在的 slot 是空操作）。 */
    private void hideChargerRange() {
        Outliner.getInstance().remove(RANGE_OUTLINE_SLOT);
        rangeSnapshot = null;
        rangeBox = null;
        rangeOutlineUntil = Long.MIN_VALUE;
    }

    /** 客户端 gameTime（世界未就绪时用 0，避免 NPE；此时也不会有框选需要维护）。 */
    private long gameTime() {
        return minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0L;
    }

    @Override
    public void removed() {
        // 关界面后<b>继续把剩余时间画完</b>（本轮修复：旧实现这里直接 remove，玩家一关 GUI 就什么都看不到；
        // 而 GUI 开着时世界又被菜单背景虚化，薄线框基本不可见）。
        // 做法：不再每 tick 重喂，但按剩余 TTL 重新武装一次，交给 Outliner 自己倒计时 + 淡出。
        if (rangeSnapshot != null && rangeBox != null) {
            final long remaining = rangeOutlineUntil - gameTime();
            if (remaining > 0) {
                Outliner.getInstance()
                    .showAABB(RANGE_OUTLINE_SLOT, rangeBox, (int) remaining)
                    .colored(RANGE_OUTLINE_COLOR)
                    .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                    .lineWidth(RANGE_OUTLINE_LINE_WIDTH);
            } else {
                // 剩余时间已经过了（gameTime 跳变 / 客户端卡顿等极端情况）：走统一移除路径
                hideChargerRange();
            }
        }
        rangeSnapshot = null;
        rangeBox = null;
        rangeOutlineUntil = Long.MIN_VALUE;
        super.removed();
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        final boolean infinite = menu.hasInfiniteRange();
        // 「高亮」按钮的文案跟随服务端同步的开关状态（每帧回写，绝不本地猜）
        refreshHighlightButton();
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
        // 本轮两颗按钮的 tooltip 与「无限范围」无关（± 按钮被禁用时它们仍然可用），
        // 因此放在 infinite 判断之外单独一次。
        renderSideButtonTooltips(guiGraphics, mouseX, mouseY);
        // 左下两段数值文字的完整值提示（无限范围时同样可用：文字仍照常渲染）
        renderValueTooltips(guiGraphics, mouseX, mouseY);
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用（升级槽 tooltip 由此触发）
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /**
     * 「高亮」/「过滤」两颗按钮的手动 tooltip（本模组 GUI 不会自动渲染 tooltip）。
     *
     * <p><b>高亮按钮的 tooltip 是动态的</b>，最多三行：</p>
     * <ol>
     *     <li>功能说明（{@code highlight.tip}）；</li>
     *     <li>当前状态（{@code highlight.state.on} / {@code .off}）—— 与按钮文案同一份数据
     *     （{@code menu.isHighlightEnabled()}），不可能出现「按钮说开、提示说关」；</li>
     *     <li>且仅当总闸 {@code Config.rangeChargerHighlightTargets == false} 时追加
     *     {@code highlight.config_off}：告诉玩家「这个开关现在点了也不生效」以及原因。</li>
     * </ol>
     * <p>与其它 tooltip 同一帧纪律：{@code vanillaSlotTooltipWillDraw(hoveredSlot)} 为真时让位，
     * 同一帧同一位置绝不画两个框。</p>
     */
    private void renderSideButtonTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
        if (highlightButton != null && highlightButton.isMouseOver(mouseX, mouseY)) {
            final java.util.List<Component> lines = new java.util.ArrayList<>(3);
            lines.add(Component.translatable("gui.rs_create_compat.range_charger.highlight.tip"));
            lines.add(Component.translatable(menu.isHighlightEnabled()
                ? "gui.rs_create_compat.range_charger.highlight.state.on"
                : "gui.rs_create_compat.range_charger.highlight.state.off"));
            if (!cretae.cookiewyq.rs_create_compat.Config.rangeChargerHighlightTargets) {
                lines.add(Component.translatable(
                    "gui.rs_create_compat.range_charger.highlight.config_off"));
            }
            guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
        if (filterButton != null && filterButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font,
                java.util.List.of(Component.translatable("gui.rs_create_compat.range_charger.filter.tip")),
                java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /** ± 按钮 shift 加速提示（hover 判定直接使用按钮自身的命中范围，保证与绘制范围一致）。
     *  <p><b>同一帧同一格只画一份</b>：本方法按控件几何命中（与 {@code hoveredSlot} 不同源），
     *  而 {@link #render} 随后<b>无条件</b>调用 {@link #renderTooltip}（原版槽位路径）——
     *  原版这一帧若已为鼠标下那个有物品的槽画了一份，这里就绝不能再画第二份
     *  （两份 tooltip 行数不同 ⇒ 贴边时定位点不同 ⇒ 文字互相压）。</p> */
    private void renderHoverTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers
            .vanillaSlotTooltipWillDraw(hoveredSlot)) {
            return;
        }
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
