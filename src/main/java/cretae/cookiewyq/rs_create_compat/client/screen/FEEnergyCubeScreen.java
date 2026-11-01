package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccEnergyBar;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.FEEnergyCubeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FE 能量立方界面：一条 RS 风格竖向电量条 + 八行状态文字（集群块数 / 集群电量 / 本块电量 /
 * 门控状态 / 对外充放速率 / 网络抽取速率 / <b>网络电量趋势</b> / <b>相邻供能</b>）。
 * <b>没有物品槽、没有玩家背包区</b>（立方没有库存）。
 *
 * <h2>第 106 轮：第 8 行「相邻供能：开 / 关 / 无相邻」</h2>
 * <p>用户 beta.9 实机口径是「立方是传递的介质 / 缓存，贴着放的范围充电器应该先被它供电」。
 * 这一行回答的是「它现在到底在不在给旁边供电」：<b>开</b> = 有可接收的邻居且本 tick 真送出了电；
 * <b>关</b> = 有可接收的邻居但一个 FE 都没送出去（本块没电 / 对方满了）；
 * <b>无相邻</b> = 一个可接收的邻居都没有（相邻只有别的能量立方也算「无相邻」—— 立方之间不互推）。
 * 三态全部由服务端在推送时实测、走数据槽 20 下发，客户端<b>只显示</b>（绝不自己遍历邻居）。
 * <p>行位置 {@code ROW_8_Y = 99}：第 7 行在 y=88，行距 11 ⇒ 第 8 行 99；面板 166 高、读数板
 * （y=17..86）之下的空白足够，不压任何装饰，也不需要改贴图。
 *
 * <h2>正文行与「低优先级」的解释责任</h2>
 * <p>第 4 行是门控状态、第 7 行是网络电量趋势（上涨 / 持平 / 下降 / 采样中）。这两行合起来回答
 * 「为什么现在没从网络充电」：门控行说清是<b>控制器低于阈值</b>还是<b>网络没在涨</b>，趋势行给出网络电量
 * 的方向与上个采样窗口的净变化（悬浮在电量条上看 tooltip）。用户口径是「网络电量不断上涨 +
 * 控制器 ≥ 90%（可配）才开始充」，界面必须能让人一眼看出是哪一条没满足。
 * <p><b>注意（第 106 轮）</b>：门控只管「从 RS 网络抽电进立方」，<b>外部 FE 的注入 / 抽取不受它影响</b>
 * （立方可以被别的机器随时灌电 / 抽电）。因此第 4 行绝不是「这块电池能不能收电」的答案，
 * 玩家给立方接发电机时不需要看门控行。
 *
 * <h2>画布与电量条（与资源侧的固定契约，改之前先和资源同学对齐）</h2>
 * <ul>
 *     <li>贴图 {@code assets/rs_create_compat/textures/gui/fe_energy_cube.png} = <b>176×166</b>，
 *     即整个面板；{@code imageWidth = 176 / imageHeight = 166}；</li>
 *     <li><b>背景 blit 必须用 9 参重载</b>（显式传贴图真实宽高）。1.21.1 的 7 参
 *     {@code blit(rl,x,y,u,v,w,h)} 把纹理尺寸硬编码成 256×256，对 176×166 的图只会取左上
 *     121×108 再拉伸 ⇒ 右/下边框被裁、凹槽与下面的 fill 错位；</li>
 *     <li><b>电量条（第 108 轮起）</b>：不再自绘平涂条，改走 {@link RsccEnergyBar}
 *     （= RS 原版 {@code ProgressWidget} + sprite {@code refinedstorage:widget/progress_bar}），
 *     与范围充电器 / 分解发电机<b>同一份实现</b>；本类只提供几何（16 宽 × 58 高 @ (14,19)，
 *     见 {@link #ENERGY_BAR_X}）与两个 getter；</li>
 *     <li>贴图里那条旧 14×60 凹槽已在<b>第 128 轮</b>换成工具生成的 MC 容器底
 *     （{@code tools/gen_round128_gui_bg.py} → {@code make_gui_bg.draw_container}，
 *     {@code pic/bg.png} 九宫格 4px 边框），底图不带凹槽；电量条轨道由
 *     {@link RsccEnergyBar#drawTrack} 的 18×60 外框<b>现画</b>在 (14,19)；</li>
 * </ul>
 *
 * <h2>数据来源（两条硬规则）</h2>
 * <ol>
 *     <li><b>界面绝不读 {@code Config}</b>：所有数值（含两个速率）都来自
 *     {@link FEEnergyCubeMenu} 的 21 个数据槽（含 17 = 网络电量趋势、18/19 = 上窗口净变化、
 *     20 = 相邻供能三态）
 *     = 服务端算好下发。专服上客户端配置可能和服务端不同，
 *     读本地配置会显示错的速率/阈值（仓库里范围充电器已修过同类问题）；</li>
 *     <li><b>数字格式统一用 {@link RsccNumberField#grouped(long)}</b>（{@code 50,000,000}）：
 *     与范围充电器界面同一套写法（第 108 轮起范围充电器的悬浮数值句也走
 *     {@link RsccEnergyBar#valueLine} ⇒ {@code grouped}，三处同源），中文界面里不出现
 *     {@code M/k} 这类英文缩写。</li>
 *     <li><b>语言键里的 {@code %s} 个数必须与本类传的实参个数「严格相等」</b>：少传会让
 *     {@code String.format} 抛 {@code MissingFormatArgumentException}，原版把整行替换成
 *     「Format error: ...」——看起来只是「文案变了」，实际是运行期格式化异常（第 99 轮
 *     {@code transfer} 就这么坏过一次）；多传则是死实参并重复显示。本工程由
 *     {@code tools/selfcheck_round99_energy_cube.py} 的 ⑧-f 逐键静态核对 Java 实参 × lang 占位符
 *     （⑧-g 再核对 zh/en 同构），{@code tools/gen_fe_energy_cube_assets.py#check_lang_contract}
 *     另有一份「正文每行 × 中英 ≤ 136px」的宽度硬断言（资源侧契约这次多出第 7 行 trend，需要同步）。</li>
 * </ol>
 * <p>文字一律 {@code dropShadow = false}（本工程界面统一口径：浅底上不描边）。
 *
 * <p><b>能力视图的 int 饱和边界（界面不受影响）</b>：对外 {@code IEnergyStorage} 是 int 口径，
 * 50,000,000 × 43 块 = 21.5 亿 &gt; {@code Integer.MAX_VALUE}，所以 <b>43 块及以上</b>时外部机器
 * 读到的容量会饱和；本界面走 long 数据槽，显示的永远是精确值（详见方块实体类注释）。
 */
public class FEEnergyCubeScreen extends AbstractContainerScreen<FEEnergyCubeMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/fe_energy_cube.png");

    // ===== 电量条（第 108 轮：复用范围充电器 / RS 控制器那套 RsccEnergyBar；坐标一律是面板内坐标） =====
    /**
     * 电量条内容区左上角 = <b>(14, 19)</b>；宽固定 16（{@link RsccEnergyBar#WIDTH}），高 58。
     *
     * <h2>为什么高是 58 而不是参考实现的 51（第 108 轮 Lead 裁决 ①「方案 B」）</h2>
     * <p>立方这张背景贴图把「凹槽」烤进了 PNG：{@code (14,18)} 起 <b>14×60</b>，边框
     * {@code #000000}（上/左）/ {@code #3A4A4E}（下/右）、槽底 {@code #1B2A2E}（逐像素核过，
     * 脚本 {@code build/probe_jars/rs209assets/_check_cover2.py}）。本类现在<b>不再往里填色</b>，
     * 而是用 {@link RsccEnergyBar#drawTrack} 画 18×(58+2) 的外框 + {@code #2C2C2C} 槽底，
     * <b>把整条旧凹槽盖住</b>：外框所占矩形 = <b>(13,18)..(30,77)</b>，旧凹槽 = (14,18)..(27,77)，
     * 前者<b>完全包含</b>后者；且 x=28..30 这 3 列在 y=18..77 是 60/60 行的 {@code #C0C0C0}
     * 读数板纯底（没有边框 / 文字 / 装饰），紧邻外侧 y=17 与 x=12 是读数板边框 {@code #B2B2B2}、
     * y=78 与 x=31 是纯底 —— 因此「盖」不会吃掉任何贴图元素，也<b>不需要重新生成 PNG</b>
     * （Lead 明确本轮不动 PNG / 生成器）。</p>
     * <p>高度比范围充电器高 7px（58 vs 51），实机看不出差别；换来「零资源改动 + sprite 原样」。</p>
     */
    private static final int ENERGY_BAR_X = 14;
    private static final int ENERGY_BAR_Y = 19;
    private static final int ENERGY_BAR_HEIGHT = 58;

    // ===== 文字（电量条右侧） =====
    /** 文字起始 x（面板内）：电量条右边 14+2 = 30 起画，留 2px 空隙。 */
    private static final int TEXT_X = 32;
    /**
     * 读数板（贴图里那块比面板略深的底）的面板内坐标，板内 = 电量条 + 6 行正文。
     * <p>数值与 {@code tools/gen_fe_energy_cube_assets.py} 的 {@code PLATE_X0, PLATE_Y0,
     * PLATE_X1, PLATE_Y1 = 12, 17, 170, 86} 同源：贴图侧把它圈成一块底，本类侧用它做
     * <b>悬浮提示的命中范围</b>（第 104 轮：数值全的 tooltip 必须能悬停到，见
     * {@link #isOverReadout(int, int)}）。
     */
    private static final int PLATE_X0 = 12;
    private static final int PLATE_Y0 = 17;
    private static final int PLATE_X1 = 170;
    private static final int PLATE_Y1 = 86;
    /** 正文各行的基线 y（面板内坐标；行距 11）：1~6 在读数板内，7 在板下 88，8（相邻供能）在 99。 */
    private static final int ROW_1_Y = 20;
    private static final int ROW_2_Y = 31;
    private static final int ROW_3_Y = 42;
    private static final int ROW_4_Y = 53;
    private static final int ROW_5_Y = 64;
    private static final int ROW_6_Y = 75;
    /**
     * 第 7 行 = 网络电量趋势。
     * <p><b>为什么不接在第 4 行后面、而是画到 y=88</b>：读数板契约是 y=17..86 那块比面板略深的底
     * （底边 1px 高光在 y=86），正文 6 行必须落在 y=20..84（见
     * {@code tools/gen_fe_energy_cube_assets.py} 的 TEXT_* 常量）。趋势行是第 7 行，只能放到板子
     * <b>下面</b>：y=86 正好压在那条 1px 高光线上（字顶被一条亮线穿过），所以再让 2px、从 88 起画。
     * 面板本身 166 高，下方本来就有 70px 空白，不压任何装饰；底色与板底只差 6 个灰阶，视觉上仍是
     * 同一块面板。
     */
    private static final int ROW_7_Y = 88;
    /**
     * 第 8 行 = 相邻供能三态（第 106 轮：开 / 关 / 无相邻）。
     * <p>行距 11 ⇒ {@code ROW_7_Y + 11 = 99}。面板 166 高、读数板之下的空白足够，且不压任何装饰
     * （第 7 行已经写在板子下面，理由见上）；<b>不需要改贴图</b>。
     */
    private static final int ROW_8_Y = 99;
    /**
     * 文字可用宽度：面板 176 − 右边界 8 − 起点 32 = 136。
     * <p><b>第 104 轮起绝不截断</b>：一律走 {@link RsccTextFit#drawFitLine}（放不下就整体缩字号、下限
     * 0.6 倍，<b>不加省略号</b>）。行宽放不下的读数只有「绝对模式下的门控行」一处，它的数字改由
     * 电量条的悬停提示完整给出（见 {@link #gateTooltipLine()}）。</p>
     */
    private static final int TEXT_MAX_W = 176 - TEXT_X - 8;

    /** 标题颜色（浅底黑字，与范围充电器 / 定量保持器一致）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    /** 正文颜色。 */
    private static final int COLOR_TEXT = 0xFF404040;
    /** 门控「允许充能」的颜色。 */
    private static final int COLOR_ALLOW = 0xFF1B7F3B;
    /** 门控「低电暂停」的颜色。 */
    private static final int COLOR_DENY = 0xFF9B1C1C;
    /** 门控「网络没在涨，等它涨」的颜色（琥珀色：这是低优先级的正常等待，不是故障）。 */
    private static final int COLOR_WAIT = 0xFF8A6100;
    /** 门控「未接入网络」的颜色。 */
    private static final int COLOR_NO_NETWORK = 0xFF5A5A5A;

    public FEEnergyCubeScreen(final FEEnergyCubeMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        // 没有玩家背包：把原版背包标签推到画布外（与 RangeChargerScreen 同一做法）
        this.inventoryLabelY = 10000;
        this.titleLabelY = 6;
    }

    /**
     * 原版容器界面的固定三段式：{@code super.render}（背景 + 控件）→ <b>手动</b> {@code renderTooltip}。
     *
     * <p><b>为什么必须手动调</b>：1.21.1 的 {@code AbstractContainerScreen#render} 方法体里
     * <b>没有任何</b> {@code renderTooltip} 调用（用 {@code javap -c} 逐字节核实过：render 只做
     * {@code renderBackground} / 事件 / 控件 / 槽位 / 浮动物品，最后直接 {@code return}）——
     * {@code protected void renderTooltip(...)} 是<b>原版特意留给子类的钩子</b>。不手动调，
     * 电量条的悬浮提示永远不会出现（第 95 轮 {@code InventoryProxyScreen} 就踩过这一条）。
     * <p>顺序不能颠倒：tooltip 必须画在 {@code super.render} 之后，否则会被背景 / 控件盖住。
     */
    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        // 面板 = 整张贴图（176×166，第 128 轮起由 tools/make_gui_bg.py 生成的 MC 容器底）。
        // 必须 9 参：7 参把纹理尺寸写死 256×256（见类注释）。
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        // 电量条：1px 黑框 + #2C2C2C 槽底（工具底图不带凹槽，这里现画一条），
        // 亮起部分由 RsccEnergyBar（RS 原版 ProgressWidget）在 renderWidget 阶段画。
        RsccEnergyBar.drawTrack(guiGraphics, leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
            ENERGY_BAR_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        // 电量条（第 108 轮）：不再自绘平涂条，直接复用范围充电器 / RS 控制器那套 RsccEnergyBar
        // （= RS 原版 ProgressWidget + sprite refinedstorage:widget/progress_bar + 同款 1px 黑框）。
        // 比例用「集群存量 / 集群容量」：用户要的就是「N 块 = 一个更大的电池」，界面上这条条
        // 应该反映整块电池；本块的精确数值在第三行文字与读数板 tooltip 里。
        addRenderableWidget(RsccEnergyBar.create(leftPos + ENERGY_BAR_X, topPos + ENERGY_BAR_Y,
            ENERGY_BAR_HEIGHT,
            () -> menu.getClusterEnergy(), () -> menu.getClusterCapacity(),
            () -> List.of(RsccEnergyBar.valueLine(menu.getClusterEnergy(), menu.getClusterCapacity()))));
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        drawRow(guiGraphics, ROW_1_Y, Component.translatable(
            "gui.rs_create_compat.fe_energy_cube.cluster", menu.getMemberCount()), COLOR_TEXT);
        // 精确存量大数（千分位）—— 容量留给 tooltip，否则 176 宽的面板放不下两个 9 位数
        drawRow(guiGraphics, ROW_2_Y, Component.translatable(
            "gui.rs_create_compat.fe_energy_cube.cluster_stored",
            RsccNumberField.grouped(menu.getClusterEnergy())), COLOR_TEXT);
        drawRow(guiGraphics, ROW_3_Y, Component.translatable(
            "gui.rs_create_compat.fe_energy_cube.own_stored",
            RsccNumberField.grouped(menu.getOwnEnergy())), COLOR_TEXT);
        drawRow(guiGraphics, ROW_4_Y, gateLine(), gateColor());
        // 第 99 轮真 bug 的现场：本行曾经只传 1 个实参，而当时 lang 的 transfer 是【双占位】
        // 「对外充放 %s FE/t，从网络抽取 %s FE/t」⇒ String.format 抛 MissingFormatArgumentException
        // ⇒ 原版把整行渲染成「Format error: ...」。修法是把 lang 收成单占位（ROW_6 已经单独显示
        // 网络抽取，双占位是早期「一行合并显示」的遗留），实参个数随之回到 1。
        // 铁律：**实参个数必须与 lang 的 %s 个数严格相等**——多传是死实参（并会重复显示第二个值），
        // 少传直接崩成 Format error；由 selfcheck_round99 的 ⑧-f 逐键静态核对，改 lang 必回看这里。
        drawRow(guiGraphics, ROW_5_Y, Component.translatable(
            "gui.rs_create_compat.fe_energy_cube.transfer",
            RsccNumberField.grouped(menu.getMaxTransfer())), COLOR_TEXT);
        drawRow(guiGraphics, ROW_6_Y, Component.translatable(
            "gui.rs_create_compat.fe_energy_cube.pull",
            RsccNumberField.grouped(menu.getNetworkPullPerTick())), COLOR_TEXT);
        // 第 7 行：网络电量趋势（上涨 / 持平 / 下降 / 采样中）—— 「为什么没充」的另一半答案。
        drawRow(guiGraphics, ROW_7_Y, trendLine(), trendColor());
        // 第 8 行（第 106 轮）：相邻供能三态 —— 「它现在到底在不在给旁边供电」。
        drawRow(guiGraphics, ROW_8_Y, adjacentSupplyLine(), adjacentSupplyColor());
    }

    /**
     * 第 8 行：相邻供能（第 106 轮）。
     * <p>三态词表走 0 实参短键（{@code adjacent.on / .off / .none}），外层键
     * {@code adjacent} 只带 1 个 {@code %s}；中文「相邻供能：无相邻」与英文
     * {@code "Adjacent supply: None"} 都在 136px 内，超了也有 {@link RsccTextFit} 兜底缩字号。
     * <p>状态值来自服务端数据槽 20，客户端<b>不遍历邻居</b>（也就不会因为区块没加载而算错）。
     */
    private Component adjacentSupplyLine() {
        return Component.translatable("gui.rs_create_compat.fe_energy_cube.adjacent",
            Component.translatable(adjacentSupplyWordKey()));
    }

    /** 相邻供能三态 → 文案键（数据槽 20 的编码；界面只做映射，不参与任何判定）。 */
    private String adjacentSupplyWordKey() {
        return switch (menu.getAdjacentSupplyState()) {
            case FEEnergyCubeMenu.ADJACENT_SUPPLY_ON -> "gui.rs_create_compat.fe_energy_cube.adjacent.on";
            case FEEnergyCubeMenu.ADJACENT_SUPPLY_OFF -> "gui.rs_create_compat.fe_energy_cube.adjacent.off";
            default -> "gui.rs_create_compat.fe_energy_cube.adjacent.none";
        };
    }

    /** 第 8 行的颜色：真的在供电 = 绿、有对象但没送出去 = 正文色、没有可供电邻居 = 灰。 */
    private int adjacentSupplyColor() {
        return switch (menu.getAdjacentSupplyState()) {
            case FEEnergyCubeMenu.ADJACENT_SUPPLY_ON -> COLOR_ALLOW;
            case FEEnergyCubeMenu.ADJACENT_SUPPLY_OFF -> COLOR_TEXT;
            default -> COLOR_NO_NETWORK;
        };
    }

    /**
     * 第 7 行：网络电量趋势。
     * <p>词表走三态 + 采样中（0 个实参的短键，方便中文/英文各自控制在 136px 内）；
     * 没接网络时直接把「未接入 RS 网络」当作趋势值，复用已有键、不新增一条「无网络」文案。
     */
    private Component trendLine() {
        if (menu.getGateState() == FEEnergyCubeMenu.GATE_NO_NETWORK) {
            return Component.translatable("gui.rs_create_compat.fe_energy_cube.trend",
                Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.nonetwork"));
        }
        return Component.translatable("gui.rs_create_compat.fe_energy_cube.trend",
            Component.translatable(trendWordKey()));
    }

    /** 趋势三态 → 文案键（数据槽 17 的编码，界面只做映射、不参与任何判定）。 */
    private String trendWordKey() {
        return switch (menu.getTrendState()) {
            case FEEnergyCubeMenu.TREND_RISING -> "gui.rs_create_compat.fe_energy_cube.trend.rising";
            case FEEnergyCubeMenu.TREND_FLAT -> "gui.rs_create_compat.fe_energy_cube.trend.flat";
            case FEEnergyCubeMenu.TREND_FALLING -> "gui.rs_create_compat.fe_energy_cube.trend.falling";
            default -> "gui.rs_create_compat.fe_energy_cube.trend.unknown";
        };
    }

    /** 第 7 行的颜色：上涨 = 绿（能吸）、下降 = 红、持平 = 正文色、采样中 / 无网络 = 灰。 */
    private int trendColor() {
        return switch (menu.getTrendState()) {
            case FEEnergyCubeMenu.TREND_RISING -> COLOR_ALLOW;
            case FEEnergyCubeMenu.TREND_FALLING -> COLOR_DENY;
            case FEEnergyCubeMenu.TREND_FLAT -> COLOR_TEXT;
            default -> COLOR_NO_NETWORK;
        };
    }

    /**
     * 第 4 行：门控状态一句话说明（四种状态各自的文案见数据槽 7 的编码）。
     *
     * <h2>第 104 轮：为什么绝对模式这一行只写状态、不写数字</h2>
     * <p>百分比模式下两个实参是「100.0% / 90%」这类<b>有界短串</b>，整行最宽 130px（中文）/
     * 111px（英文），136px 的行宽本来就放得下——这里保持原样，玩家不损失任何读数。</p>
     * <p>绝对模式下实参是「2,147,483,647 FE」这种<b>十位数</b>：中文整行要 244px、英文 225px，
     * 即使按用户口径把字号缩到 0.6 倍也放不进 136px（缩到 0.6 会溢出面板右边界）。
     * 因此绝对模式这一行改成<b>完整短句</b>（状态本身，0 个数字实参），
     * 控制器电量 / 阈值的完整数值改由电量条的悬停提示给出（{@link #gateTooltipLine()}）——
     * 这正是用户要的「改排列：主行完整、细节可见」，而不是把文字截成「暂停（控制器 100.0…」。</p>
     */
    private Component gateLine() {
        if (menu.isAbsoluteMode()) {
            // 绝对模式：主行只留完整短句（数字进 tooltip），否则 136px 无论如何都放不下
            return Component.translatable(shortGateKey());
        }
        switch (menu.getGateState()) {
            case FEEnergyCubeMenu.GATE_ALLOW:
                return Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.allow",
                    controllerPercentText());
            case FEEnergyCubeMenu.GATE_DENY_LOW_CONTROLLER:
                return Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.deny",
                    controllerPercentText(), thresholdText());
            case FEEnergyCubeMenu.GATE_DENY_NOT_RISING:
                // 阈值达标、只是网络没在涨：文案只报这一条（0 个实参，136px 内塞得下），
                // 趋势行（第 7 行）给出具体是持平 / 下降 / 采样中。
                return Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.not_rising");
            default:
                return Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.nonetwork");
        }
    }

    /**
     * 绝对模式专用：门控状态的<b>完整短句</b>（不含数字）。
     * <p>两个新键由 Lead 落盘（本类只引用）：{@code gate.short.allow} / {@code gate.short.deny}。</p>
     */
    private String shortGateKey() {
        return switch (menu.getGateState()) {
            case FEEnergyCubeMenu.GATE_ALLOW -> "gui.rs_create_compat.fe_energy_cube.gate.short.allow";
            case FEEnergyCubeMenu.GATE_DENY_LOW_CONTROLLER ->
                "gui.rs_create_compat.fe_energy_cube.gate.short.deny";
            case FEEnergyCubeMenu.GATE_DENY_NOT_RISING ->
                "gui.rs_create_compat.fe_energy_cube.gate.not_rising";
            default -> "gui.rs_create_compat.fe_energy_cube.gate.nonetwork";
        };
    }

    /**
     * 电量条悬停提示里的「门控完整原因」：百分比 / 绝对两种模式都给<b>完整</b>数值。
     * <p>这是 {@link #gateLine()} 在绝对模式下省掉的那段数字的唯一完整去处（悬浮提示不占行宽，
     * 因此永远放得下）。占位符实参个数与百分比模式完全一致，改 lang 时两边一起看。</p>
     */
    private Component gateTooltipLine() {
        return switch (menu.getGateState()) {
            case FEEnergyCubeMenu.GATE_ALLOW -> Component.translatable(
                "gui.rs_create_compat.fe_energy_cube.gate.allow", controllerPercentText());
            case FEEnergyCubeMenu.GATE_DENY_LOW_CONTROLLER -> Component.translatable(
                "gui.rs_create_compat.fe_energy_cube.gate.deny",
                controllerPercentText(), thresholdText());
            case FEEnergyCubeMenu.GATE_DENY_NOT_RISING ->
                Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.not_rising");
            default -> Component.translatable("gui.rs_create_compat.fe_energy_cube.gate.nonetwork");
        };
    }

    /** 第 4 行的颜色：允许 = 绿、控制器低电 = 红、网络没在涨 = 琥珀、未接入网络 = 灰。 */
    private int gateColor() {
        return switch (menu.getGateState()) {
            case FEEnergyCubeMenu.GATE_ALLOW -> COLOR_ALLOW;
            case FEEnergyCubeMenu.GATE_DENY_LOW_CONTROLLER -> COLOR_DENY;
            case FEEnergyCubeMenu.GATE_DENY_NOT_RISING -> COLOR_WAIT;
            default -> COLOR_NO_NETWORK;
        };
    }

    /** 「控制器电量」显示：百分比模式给「xx.x%」，绝对模式给「12,345,678 FE」（与门控判定同一口径）。 */
    private String controllerPercentText() {
        if (menu.isAbsoluteMode()) {
            return RsccNumberField.grouped(menu.getNonCubeStored()) + " FE";
        }
        return formatPercent(menu.getNonCubeStored(), menu.getNonCubeCapacity());
    }

    /** 阈值显示：与 {@link #controllerPercentText()} 同一模式（两处必须同时切换，否则玩家会读到两种单位）。 */
    private String thresholdText() {
        return menu.isAbsoluteMode()
            ? RsccNumberField.grouped(menu.getThresholdAbsolute()) + " FE"
            : menu.getThresholdPercent() + "%";
    }

    /**
     * 画一行读数：<b>完整显示</b>（第 104 轮口径）。
     * <p>放不下时 {@link RsccTextFit#drawFitLine} 会整体水平缩字号（下限 0.6 倍）而不是加省略号；
     * 本界面的 6 行读数在最坏配置下都只需要 ≥0.95 倍，绝不会缩到糊。</p>
     */
    private void drawRow(final GuiGraphics guiGraphics, final int y, final Component text, final int color) {
        RsccTextFit.drawFitLine(guiGraphics, font, text, TEXT_X, y, TEXT_MAX_W, color);
    }

    /**
     * 读数区的悬浮 tooltip：给出<b>精确</b>数值（正文为了不溢出只显示关键量）。
     * <p>判定范围与绘制范围同源（都用 {@link #PLATE_X0}/{@link #PLATE_Y0}/{@link #PLATE_X1}/
     * {@link #PLATE_Y1} 那块读数板），不会出现「看得到却悬停不到」。
     * <p><b>第 104 轮</b>：判定范围从「只有那条 14px 宽的电量条」扩大到<b>整块读数板</b>——
     * 因为绝对模式下第 4 行的完整数字（控制器电量 / 阈值）挪到了这里，只悬停那条细条根本找不到。
     * 板内没有槽位、没有按钮，扩大判定不会和任何控件抢 tooltip。</p>
     */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (isOverReadout(mouseX, mouseY)) {
            final List<Component> lines = new ArrayList<>(6);
            // 第 1 行 = 第 4 行的完整版（含绝对模式被省掉的控制器电量 / 阈值数字）
            lines.add(gateTooltipLine());
            lines.add(Component.translatable("gui.rs_create_compat.fe_energy_cube.own",
                RsccNumberField.grouped(menu.getOwnEnergy()),
                RsccNumberField.grouped(menu.getOwnCapacity())));
            lines.add(Component.translatable("gui.rs_create_compat.fe_energy_cube.cluster_energy",
                RsccNumberField.grouped(menu.getClusterEnergy()),
                RsccNumberField.grouped(menu.getClusterCapacity())));
            lines.add(Component.translatable("gui.rs_create_compat.fe_energy_cube.percent",
                formatPercent(menu.getClusterEnergy(), menu.getClusterCapacity())));
            lines.add(Component.translatable("gui.rs_create_compat.fe_energy_cube.cluster",
                menu.getMemberCount()));
            // 趋势的原始读数：「最近 5 tick 网络净变化（不含本立方抽取）+1,234,567 FE」—— 比一个
            // 「上涨/持平」更有说服力，也方便玩家自己核对「为什么没充」。
            lines.add(Component.translatable("gui.rs_create_compat.fe_energy_cube.trend_delta",
                FEEnergyCubeMenu.TREND_WINDOW_TICKS, signedFe(menu.getTrendDelta())));
            guiGraphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    /**
     * 鼠标是否落在读数板（电量条右侧的正文区）上。
     * <p>坐标与贴图同源：读数板 = 面板内 (12,17)..(170,86)（见 {@code gen_fe_energy_cube_assets.py}
     * 的 {@code PLATE_*} 常量）。左闭右开。</p>
     * <p><b>第 108 轮：先排除电量条本身</b> —— 条的 tooltip 由 RS 原版 {@code ProgressWidget}
     * 自己渲染（{@link RsccEnergyBar#valueLine} 的数值句），这里若再画一份读数板 tooltip，
     * 同一帧会出现<b>两个</b>框叠着（旧实现因为不敢让 widget 自己弹，才只有读数板一份）。</p>
     */
    private boolean isOverReadout(final int mouseX, final int mouseY) {
        if (isOverEnergyBar(mouseX, mouseY)) {
            return false; // 让位给 ProgressWidget 自己的 tooltip
        }
        return mouseX >= leftPos + PLATE_X0 && mouseX < leftPos + PLATE_X1 + 1
            && mouseY >= topPos + PLATE_Y0 && mouseY < topPos + PLATE_Y1 + 1;
    }

    /**
     * 鼠标是否落在电量条的<b>内容区</b>（= widget 的 hover 区域；左闭右开 16×58）。
     * <p>判定与 {@link RsccEnergyBar#create} 传的几何同源（同一组 {@code ENERGY_BAR_*} 常量 +
     * 同一个 {@link RsccEnergyBar#WIDTH}），因此「看到哪条就悬停到哪条」。
     */
    private boolean isOverEnergyBar(final int mouseX, final int mouseY) {
        return mouseX >= leftPos + ENERGY_BAR_X
            && mouseX < leftPos + ENERGY_BAR_X + RsccEnergyBar.WIDTH
            && mouseY >= topPos + ENERGY_BAR_Y
            && mouseY < topPos + ENERGY_BAR_Y + ENERGY_BAR_HEIGHT;
    }

    // ==================== 数字格式化 ====================

    /**
     * 带符号的 FE 数（{@code +1,234,567} / {@code -4,096} / {@code +0}）。
     * <p><b>为什么不能直接 {@code RsccNumberField.grouped(long)}</b>：它内部走 {@code raw(value)}，
     * 而 {@code raw} 把负数夹成 0（那是给「数量 / 容量」这种非负量用的），拿它显示「净变化」会把
     * 「−123 FE」显示成「0 FE」，正好把「网络在掉电」说成「没变化」。所以符号自己拼、绝对值再分组。
     */
    private static String signedFe(final long value) {
        if (value == 0L) {
            return "+0";
        }
        // Long.MIN_VALUE 取反会溢出；现实中不可能出现，但显示层不能因此打印负数：
        final long magnitude = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        return (value > 0L ? "+" : "-") + RsccNumberField.grouped(magnitude);
    }

    /** 百分比（{@code 45.0%}）：只在服务端下发的整数对上做一次除法，不参与任何判定。 */
    private static String formatPercent(final long stored, final long capacity) {
        if (capacity <= 0L || stored <= 0L) {
            return "0.0%";
        }
        final double percent = Math.min(100.0D, (double) stored * 100.0D / (double) capacity);
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
