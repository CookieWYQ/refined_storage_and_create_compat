package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.AreaMarkEditPacket;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>「某一个区域的过滤标记」独立编辑界面</b>（第 112 轮）。
 *
 * <h2>用户原话（为什么需要这个界面）</h2>
 * <p>「无法正常的单独给某一个区域标记，就非得把这个标记放在方块中进行吗这样子感觉很麻烦，
 * 切换的时候更麻烦」。第 101 轮的每区域过滤存在<b>方块实体</b>上，键是区域序号，所以只能：
 * 走到机器前 → 把选定器插进槽 → 进机器界面 → 用 {@code < >} 一行行切过去 → 在那 48 格 ghost 窗口里配。
 * 第 112 轮把过滤标记搬到<b>选定器物品</b>上（{@link AreaMarks}），这个界面就是它的入口：
 * <b>手里拿着选定器就能打开，点哪个区域配哪个区域，不需要机器、不需要切换</b>。</p>
 *
 * <h2>排版（全部自绘，不新增任何 GUI 贴图）</h2>
 * <pre>
 *   y=  8  区域标记 · 刷铁机 · 27 格                       ← 标题 + 副标题（同一行）
 *   y= 20  本区域的过滤标记          背包（点一下加入）     ← 列标签
 *   y= 34  [9×4 ghost 格]            [9×4 背包格]          ← 网格（18×18，行距 18）
 *   y=109  标记 3 / 288 条                                 ← 数量摘要（恒画）
 *   y=121  左键点标记 = 移除；左键点背包物品 = 加入        ← 状态行（闸门 &gt; 反馈 &gt; 帮助）
 *   y=133  还没有标记 ⇒ 这个区域什么都不吸                 ← 空状态说明（仅空表时画）
 *   y=152  [清空标记]  [返回]                              ← 按钮（高 {@link #BUTTON_H} = 18）
 * </pre>
 * <p>面板 352×177；网格仍是 9×4（{@code GRID_Y} / {@code PITCH} / {@code LEFT_X} / {@code RIGHT_X}
 * 的既有语义<b>一个都没动</b>，动的只有文字行）。</p>
 * <p><b>纵向已无死区</b>（第 124 轮收尾）：上边框→标题 8px、文字块 8..142、最后一行文字底 142 →
 * 按钮顶 152 = <b>10px</b>、按钮 152..170、按钮底 → 面板底 = <b>7px</b>。旧的 {@code PANEL_H = 232}
 * 把按钮放在 208..226，最后一行文字（142）到按钮之间空着 <b>66px</b>，整块「上面挤下面空」。</p>
 * <ul>
 *     <li>左侧格 = 该区域已有的 ghost 标记（与机器匹配区<b>同一份数据</b>、同一种 {@link MarkerEntry}）；
 *     <b>左键点一下 = 移除这一条</b>（再点背包里的物品就加回来，误删代价极低）；</li>
 *     <li>右侧格 = 玩家<b>自己背包</b>的镜像（只读副本，点击<b>不消耗</b>物品，只写一条 ghost 标记）；
 *     服务端按「背包第 N 格」自己取物品，客户端发不了伪造 NBT；</li>
 *     <li>数量门槛 / NBT / 标签匹配这些细调仍在机器界面的 48 格窗口里（同一份数据，两边等价）。</li>
 * </ul>
 *
 * <h2>第 124 轮：文字全部挪出网格（用户实机截图里的「字压格子」）</h2>
 * <p>用户原话：「截图里左半『本区域的过滤标记』网格的第 2 行上，压着一行说明文字（与网格重叠）；
 * 下方『标记 0 / 288 条』与『空的区域什么都不吸（安全默认）；标记跟着选定器走。』两行也贴得过近。」
 * —— 这是<b>渲染层硬 bug</b>，不是审美问题。当时的三处几何 bug 与修法：</p>
 * <ol>
 *     <li>副标题 {@code SUBTITLE_Y = 22} 与列标签 {@code GRID_Y - 10 = 24} 两行互相压住 7px
 *     （行高 9）⇒ 标题与副标题<b>并成一行</b>（{@link #TITLE_Y}），列标签下移到 {@link #LABEL_Y}；</li>
 *     <li>空状态提示画在 {@code GRID_Y + GRID_H / 2 - 4 = 66}，正落在网格第 2 行格子（{@code 52..70}）上
 *     ⇒ 搬到网格下方第三行 {@link #EMPTY_Y}（<b>仅空表时画</b>；槽框是实心矩形，任何画在网格内部的
 *     文字都必然压在某一行格子上，所以「网格外」是唯一正确解）；</li>
 *     <li>数量行与状态行只差 9px（{@code INFO_Y} 与 {@code INFO_Y + HINT_LINE_H}，刚好顶死）⇒
 *     网格下方三行统一行距 12px（行高 9 + 间隙 {@link #TEXT_LINE_GAP} = 3，硬下限 2）。</li>
 * </ol>
 * <p>判据落在 {@code tools/selfcheck_round124_area_mark_layout.py}：从<b>真源码</b>解析出每一处
 * {@code RsccTextFit.drawFitLine} 的 y / 可用宽度和每一个格子矩形（含槽框那 1px 外沿），
 * <b>两两求交必须为空</b>，并把上面三处旧坐标当反例判负；
 * {@code build/mock/area_mark_mock_3x.png} 是同一套几何画出来的目视图。</p>
 *
 * <h2>为什么客户端能直接读标记（不用额外 S2C 包）</h2>
 * <p>标记存在选定器物品的 {@code CUSTOM_DATA} 上，而物品本身（主手 / 菜单槽）是原版同步的一部分；
 * 服务端写完走 {@link cretae.cookiewyq.rs_create_compat.support.AreaSelectorTargets#sync}，
 * 客户端下一帧读到的就是新数据。因此本界面<b>只发意图、只读同步下来的物品</b>，
 * 不维护第二份状态（也不会出现「界面显示 A、服务端是 B」）。</p>
 * <p><b>但读的时候必须带注册表</b>（第 119 轮 C-5 / 第 120 轮）：旧档里还有 {@code Item}
 * （整份 ItemStack）编码的标记，没有注册表会被解码器当成「读不出来」丢掉，于是<b>本界面的列表比服务端短</b>，
 * 而「左键移除」发的是下标 ⇒ 删错一条。本类所有读取统一走
 * {@link #clientMarks(net.minecraft.world.item.ItemStack, Area)}（= 服务端同一套解码口径）。</p>
 *
 * <h2>硬规则</h2>
 * <ul>
 *     <li>不新增 GUI PNG：面板走 {@link ChildConfigScreen} 的原版九宫格精灵，槽位走
 *     {@link McGui#slotFrame}（逐像素复刻工程既有槽位素材）；</li>
 *     <li>所有文本先量宽再画（{@link RsccTextFit}），绝不写死像素硬塞长字符串；</li>
 *     <li>tooltip 全部手动渲染（原版不会给自绘元素画 tooltip）；</li>
 *     <li>客户端不写世界、不查能力：只发 {@link AreaMarkEditPacket} 意图。</li>
 * </ul>
 */
public class AreaMarkScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.area_mark.";

    private static final int PANEL_W = 352;
    /**
     * 面板高 177 = 按钮底（{@code BUTTON_Y + BUTTON_H = 152 + 18 = 170}）+ <b>7px</b> 下留白。
     * <p>第 124 轮收尾前是 232（按钮在 208..226，按钮下照样 6px，但<b>按钮上方</b>空出 66px 死区，
     * 整块「上面挤下面空」）。这里写死字面量、不写 {@code BUTTON_Y + BUTTON_H + 7}：
     * {@code BUTTON_Y} 声明在本行之后，Java 的 illegal forward reference 会编译报错；
     * 「177 == BUTTON_Y + BUTTON_H + 7」由 {@code tools/selfcheck_round124_area_mark_layout.py}
     * 的 F2 断言钉住（底留白必须落在 6..8），改任一边而忘了另一边会直接判负。</p>
     */
    private static final int PANEL_H = 177;

    /** 单行文字的行高（原版字体 9px）；下面所有纵向排版都按它算。 */
    private static final int TEXT_LINE_H = 9;
    /**
     * 相邻两行文字之间留的像素数。
     * <p>硬约束是「≥ 2px」（{@code tools/selfcheck_round124_area_mark_layout.py} 判负），
     * 这里给 3px：9px 字体实际字形只占 8px，留 3px 才不会在实机上看成「顶死」
     * （用户第 124 轮反馈的「两行贴得过近」就是间隙 0 造成的）。</p>
     */
    private static final int TEXT_LINE_GAP = 3;

    /**
     * 标题行 y：<b>标题与副标题同一行</b>（左 = 标题，中 = 分隔点，右 = 副标题「区域名 · 体积」）。
     * <p>第 124 轮之前副标题独占 {@code y = 22}，而列标签画在 {@code GRID_Y - 10 = 24} ⇒ 两行只差 2px、
     * 互相压住 7px。面板上沿（y=0）到网格槽框上沿（{@code GRID_Y - 1 = 33}）只有 33px，三行 9px 文字
     * + 两处 ≥2px 间隙最少要 31px，再扣掉面板 4px 边框<b>真的塞不下</b> ⇒ 只能「改排列」（把标题与副标题
     * 并成一行），而不是缩字号 / 加省略号（见 {@code docs/TEXT_NO_ELLIPSIS_RULES.md} 的优先级）。</p>
     */
    private static final int TITLE_Y = 8;
    /** 标题与副标题之间的分隔符（纯符号、无 ASCII 字母 ⇒ 不触发本地化自检的「硬编码字符串」判据）。 */
    private static final String SUBTITLE_SEP = " · ";

    /** 列标签行 y：标题行下 3px（行距 12）；再往下 4px 就是网格槽框上沿。 */
    private static final int LABEL_Y = TITLE_Y + TEXT_LINE_H + TEXT_LINE_GAP;

    /** 列 / 行数（9×4 = 36 个格子，和原版背包宽度对齐）。 */
    private static final int COLS = 9;
    private static final int ROWS = 4;
    /** 格子步进（16px 图标 + 2px 间隙 = 与 {@link McGui#SLOT_FRAME_SIZE} 一致）。 */
    private static final int PITCH = McGui.SLOT_FRAME_SIZE;
    /** 网格左上角（左右两块各 9 列 × 18px = 162px 宽）。 */
    private static final int LEFT_X = 12;
    private static final int RIGHT_X = 178;
    private static final int GRID_Y = 34;
    private static final int GRID_H = ROWS * PITCH;

    /**
     * 网格下方三行文字（第 124 轮重排）：数量摘要 / 状态行 / 空状态说明。
     * <p>三行都<b>不许进网格</b>：槽框矩形是 {@code [GRID_Y - 1, GRID_Y + GRID_H - 1] = [33, 105]}，
     * 所以第一行只能落在 105 之后；行距统一 {@code TEXT_LINE_H + TEXT_LINE_GAP = 12}（间隙 3px）。</p>
     */
    private static final int INFO_Y = GRID_Y + GRID_H + 3;
    private static final int HINT_Y = INFO_Y + TEXT_LINE_H + TEXT_LINE_GAP;
    private static final int EMPTY_Y = HINT_Y + TEXT_LINE_H + TEXT_LINE_GAP;

    /**
     * 按钮顶 y：最后一行文字底（{@code EMPTY_Y + TEXT_LINE_H = 142}）下移 <b>10px</b>。
     * <p>旧值 208 = 文字块下面空 66px 死区（用户：整块看起来「上面挤、下面空」）。
     * 判据 F3 要求这段间隙在 8..12（不许贴死、也不许留死区）。</p>
     */
    private static final int BUTTON_Y = 152;
    /** 按钮高（与 {@code Button.Builder#bounds} 的第四个参数同源，面板高按它推底留白）。 */
    private static final int BUTTON_H = 18;
    private static final int CLEAR_W = 90;
    private static final int BACK_W = 70;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF6A6A6A;
    private static final int COLOR_OK = 0xFF1B6B1B;
    private static final int COLOR_WARN = 0xFF8A4B00;
    private static final int COLOR_ERROR = 0xFF8B1A1A;

    /** 菜单路径用的容器 id（{@code < 0} = 手持路径：读 / 写主手选定器）。 */
    private final int containerId;
    /** 正在编辑的区域（服务端会按<b>六坐标几何</b>再校验一次是不是真在选定器上）。 */
    private final Area area;

    private Button clearButton;
    private Button backButton;

    /** 一条反馈（乐观提示；服务端权威，下一帧同步回来就是真值）。 */
    @Nullable
    private String notice;
    private int noticeColor = COLOR_DIM;
    /** 反馈存活到哪个毫秒（避免永久挂在界面上）。 */
    private long noticeUntilMs;

    public AreaMarkScreen(@Nullable final Screen parent, final int containerId, final Area area) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.containerId = containerId;
        this.area = area;
    }

    // ==================== 数据来源 ====================

    /** 目标选定器（客户端那份同步副本；空 = 界面只读空表，不会崩）。 */
    private ItemStack targetStack() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        if (containerId >= 0) {
            // 菜单路径：服务端 AreaSelectorTargets.resolve 对「菜单 id 对不上 / 槽空 / 槽里不是选定器」
            // 一律丢包、绝不回退主手；客户端也不回退 —— 否则会出现「界面显示主手那支选定器、服务端改的是
            // 菜单槽（或什么都不改）」的静默失效（第 113 轮 D10-f；第 119 轮 C-2 把「槽空」也纳入同一口径）。
            if (minecraft.player.containerMenu instanceof AdvancedCollectionCacheMenu menu
                && menu.containerId == containerId) {
                final ItemStack inSlot = menu.getAreaSelectorStack();
                return inSlot.is(RS_Create_Compat.AREA_SELECTOR.get()) ? inSlot : ItemStack.EMPTY;
            }
            return ItemStack.EMPTY;
        }
        final ItemStack mainHand = minecraft.player.getMainHandItem();
        return mainHand.is(RS_Create_Compat.AREA_SELECTOR.get()) ? mainHand : ItemStack.EMPTY;
    }

    /**
     * 菜单路径但专用槽里没有选定器（被拿走 / 换成了别的东西）。
     * <p>服务端 {@code resolve} 这时会直接丢包（连 sync 都没有）；如果界面照常发意图并立刻弹
     * 「已加入 / 已移除」，就是一句与事实相反的提示。这里先说明并拦住（第 119 轮 C-2：
     * 区域列表与标记界面两个入口同一口径 —— 菜单槽空 ⇒ 不回退主手、也不假装成功）。</p>
     */
    private boolean menuTargetMissing() {
        if (containerId < 0 || !targetStack().isEmpty()) {
            return false;
        }
        showNotice(Component.translatable(
            "gui.rs_create_compat.area_selector.status.no_slot").getString(), COLOR_ERROR);
        return true;
    }

    /**
     * 客户端当前的<b>注册表上下文</b>（解码旧档标记用）；拿不到 = {@code null}。
     * <p>客户端在世界里必然有 {@code level}，所以正常情况下永远拿得到。</p>
     */
    @Nullable
    static net.minecraft.core.HolderLookup.Provider clientRegistries() {
        final net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.registryAccess();
    }

    /**
     * 客户端读某区域的标记，<b>与服务器同一份解码口径（带注册表）</b>。
     *
     * <h2>为什么必须带注册表（第 119 轮 C-5 / 第 120 轮修）</h2>
     * <p>旧档里还有用 {@code Item}（<b>整份 ItemStack</b>）编码的标记，而
     * {@code CollectionCacheBlockEntity#decodeMarkerTag} 在 {@code registries == null} 时<b>读不出这种条目，
     * 直接返回 null 由调用方丢弃</b>。服务端读的是 {@code player.registryAccess()}（见
     * {@link AreaMarkEditPacket}），客户端以前走两参重载（{@code registries = null}）⇒ 两边的列表
     * <b>长度 / 顺序不同</b>；而「左键移除一条」发的是<b>下标</b>（{@code ACTION_REMOVE + markerIndex}）⇒
     * 删掉的是服务器列表里同下标的<b>另一条</b>。带注册表之后两边解码结果同构，下标自然对齐。</p>
     *
     * <h2>拿不到注册表时：返回空表，<b>不回退</b>「无注册表」读法</h2>
     * <p>回退会重新造出一份<b>短的</b>列表 —— 那正是「下标错位」的成因。宁可这一帧列表为空
     * （界面不渲染任何标记格 ⇒ 玩家点不到任何一行 ⇒ <b>不可能删错</b>），也不显示一份下标不可信的列表。
     * 该窗口只存在于「断开连接 / 切维度」的瞬间（屏幕随后就被关掉），代价可忽略。</p>
     */
    static List<MarkerEntry> clientMarks(final ItemStack stack, @Nullable final Area area) {
        if (stack.isEmpty() || area == null) {
            return List.of();
        }
        final net.minecraft.core.HolderLookup.Provider registries = clientRegistries();
        if (registries == null) {
            return List.of(); // 见上面的取舍：不显示，绝不退回 null 注册表读法
        }
        return AreaMarks.get(stack, area, registries);
    }

    /**
     * 该区域当前的标记（读物品上的 {@link AreaMarks}）。
     * <p><b>渲染与「移除 / 加入 / 清空」全部只经由这一个入口</b>，所以界面上第 N 格显示的那一条
     * 与服务端列表的第 N 条是同一份解码结果 —— 这是 {@code REMOVE} 下标可用的前提。</p>
     */
    private List<MarkerEntry> marks() {
        return clientMarks(targetStack(), area);
    }

    /**
     * 「这个选定器已经绑到别的维度了」的说明（{@code null} = 可以编辑）。
     * <p>服务端 {@link AreaMarkEditPacket} 与区域包同一条维度闸门：绑定了别的维度就丢包。
     * 如果界面不先拦，玩家会看到「加了一条但什么都没变」—— 正是本项目反复修的那类静默失效。</p>
     */
    @Nullable
    private String dimensionBlocker() {
        final ItemStack stack = targetStack();
        if (stack.isEmpty()) {
            // 菜单路径的空槽由 menuTargetMissing() 先解释（并拦住动作）；这里只剩「手持路径连物品都没了」
            // 这种本来就不该出现的状态，不必再解释第二遍。
            return null;
        }
        final net.minecraft.resources.ResourceLocation bound =
            cretae.cookiewyq.rs_create_compat.support.AreaSelection.get(stack).dimension();
        final Minecraft minecraft = Minecraft.getInstance();
        if (bound == null || minecraft.level == null) {
            return null;
        }
        return bound.equals(minecraft.level.dimension().location())
            ? null
            : Component.translatable(LANG + "notice.dimension", bound.toString()).getString();
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();
        clearButton = addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "button.clear"), button -> clearAll())
            .bounds(px + (PANEL_W - CLEAR_W - BACK_W - 8) / 2,
                py + BUTTON_Y, CLEAR_W, BUTTON_H)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable(LANG + "button.clear.tip")))
            .build());
        backButton = addRenderableWidget(new Button.Builder(
            Component.translatable(LANG + "button.back"), button -> returnToParent())
            .bounds(px + (PANEL_W - CLEAR_W - BACK_W - 8) / 2 + CLEAR_W + 8,
                py + BUTTON_Y, BACK_W, BUTTON_H)
            .build());
    }

    // ==================== 动作 ====================

    /** 从背包第 {@code slot} 格加一条标记（服务端自己取物品，不消耗）。 */
    private void addFromInventory(final int slot) {
        if (menuTargetMissing()) {
            return;
        }
        final String blocker = dimensionBlocker();
        if (blocker != null) {
            showNotice(blocker, COLOR_ERROR);
            return;
        }
        final ItemStack source = inventoryStack(slot);
        if (source.isEmpty()) {
            return;
        }
        // 单条标记的组件补丁太大（成书 / 带内容物的容器 / 大 NBT 物品）⇒ 别让它把选定器的
        // CUSTOM_DATA 撑到随物品同步时超包。服务端同样会兜底拒绝（静默），所以这里必须先给提示。
        final net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        final MarkerEntry candidate = MarkerEntry.item(source,
            CollectionCacheBlockEntity.MIN_UI_MARK_AMOUNT, false,
            level == null ? null : level.registryAccess());
        if (AreaMarks.isOversized(candidate)) {
            showNotice(Component.translatable(LANG + "notice.too_complex").getString(), COLOR_ERROR);
            return;
        }
        final List<MarkerEntry> current = marks();
        // 第 114 轮：单条没超，但「整件选定器」的标记表加上这一条会超总量上限 ⇒ 同样先提示。
        // 按整件选定器算（所有区域），因为 CUSTOM_DATA 是随物品整体同步的。
        final List<MarkerEntry> next = new ArrayList<>(current.size() + 1);
        next.addAll(current);
        next.add(candidate);
        if (AreaMarks.isOverBudget(targetStack(), area, next)) {
            // 文案用「线上字节」口径（≈64 KB），不是堆估算的 512 KB —— 玩家看得懂的那个数
            showNotice(Component.translatable(LANG + "notice.too_many",
                AreaMarks.MAX_TOTAL_WIRE_BYTES / 1024).getString(), COLOR_ERROR);
            return;
        }
        if (current.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {
            showNotice(Component.translatable(LANG + "notice.full",
                CollectionCacheBlockEntity.MATCH_CAPACITY).getString(), COLOR_ERROR);
            return;
        }
        for (final MarkerEntry entry : current) {
            if (entry != null && !entry.isEmpty() && !entry.fluid()
                && entry.id() != null && entry.id().equals(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(source.getItem()))) {
                showNotice(Component.translatable(LANG + "notice.duplicate").getString(), COLOR_WARN);
                return;
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new AreaMarkEditPacket(containerId, AreaMarkEditPacket.ACTION_ADD, area,
                AreaMarkEditPacket.NO_INDEX, slot));
        showNotice(Component.translatable(LANG + "notice.added", source.getHoverName()).getString(),
            COLOR_OK);
    }

    /** 移除该区域的第 {@code index} 条标记。 */
    private void removeAt(final int index) {
        if (menuTargetMissing()) {
            return;
        }
        final String blocker = dimensionBlocker();
        if (blocker != null) {
            showNotice(blocker, COLOR_ERROR);
            return;
        }
        final List<MarkerEntry> current = marks();
        if (index < 0 || index >= current.size()) {
            return;
        }
        final MarkerEntry entry = current.get(index);
        final ItemStack display = entry == null ? ItemStack.EMPTY : entry.displayStack();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new AreaMarkEditPacket(containerId, AreaMarkEditPacket.ACTION_REMOVE, area,
                index, AreaMarkEditPacket.NO_INDEX));
        showNotice(Component.translatable(LANG + "notice.removed",
            display.isEmpty() ? Component.literal("?") : display.getHoverName()).getString(), COLOR_DIM);
    }

    /** 清空该区域的全部标记（= 该区域什么都不吸）。 */
    private void clearAll() {
        if (menuTargetMissing()) {
            return;
        }
        final String blocker = dimensionBlocker();
        if (blocker != null) {
            showNotice(blocker, COLOR_ERROR);
            return;
        }
        if (marks().isEmpty()) {
            showNotice(Component.translatable(LANG + "notice.already_empty").getString(), COLOR_DIM);
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new AreaMarkEditPacket(containerId, AreaMarkEditPacket.ACTION_CLEAR, area,
                AreaMarkEditPacket.NO_INDEX, AreaMarkEditPacket.NO_INDEX));
        showNotice(Component.translatable(LANG + "notice.cleared").getString(), COLOR_WARN);
    }

    private void showNotice(final String text, final int color) {
        notice = text;
        noticeColor = color;
        noticeUntilMs = System.currentTimeMillis() + 4000L;
    }

    /** 玩家背包第 {@code slot} 格（客户端镜像，只读）。 */
    private ItemStack inventoryStack(final int slot) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || slot < 0
            || slot >= minecraft.player.getInventory().getContainerSize()) {
            return ItemStack.EMPTY;
        }
        return minecraft.player.getInventory().getItem(slot);
    }

    /** 背包格 → 背包下标：前 3 行是主背包 9..35，第 4 行是快捷栏 0..8。 */
    private static int inventorySlotFor(final int cell) {
        if (cell < 0 || cell >= COLS * ROWS) {
            return -1;
        }
        if (cell < COLS * 3) {
            return 9 + cell; // 主背包：第 0 行 = 背包第 9 格（与打开背包时看到的一致）
        }
        return cell - COLS * 3; // 快捷栏：0..8
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawTitleAndLabels(guiGraphics);
        drawMarkGrid(guiGraphics, mouseX, mouseY);
        drawInventoryGrid(guiGraphics, mouseX, mouseY);
        drawInfoAndNotice(guiGraphics);
        drawTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 标题行（标题 + 副标题<b>同一行</b>）与列标签行。
     * <p>两行 y 差固定 {@code TEXT_LINE_H + TEXT_LINE_GAP = 12}，且列标签行的下沿
     * （{@code LABEL_Y + TEXT_LINE_H = 29}）必须在网格槽框上沿（{@code GRID_Y - 1 = 33}）之上
     * —— 第 124 轮之前它画在 {@code GRID_Y - 10 = 24}，正好和副标题（22）压在一起 7px。</p>
     * <p>副标题可用宽度 = 行尾 − 副标题起点（随标题实宽变化；zh 标题「区域标记」= 36px ⇒ 275px，
     * en 标题「Area Marks」= 56px ⇒ 255px）。区域名最长 {@code Area#MAX_NAME_LENGTH = 24} 字，
     * 最坏一行 = {@code 24 个全角字(24×9=216) + " · "(17) + 体积（最多 "1048576 格" = 55）}
     * ⇒ zh 最坏 288px、en 最坏 315px，<b>会超出可用宽度</b>，此时
     * {@link RsccTextFit#drawFitLine} 把<b>整行等比缩小</b>（不是截断）：
     * zh 默认体积上限（32768）0.996 倍 ≈ 8.97px、配置顶满 1,048,576 时 0.955 倍 ≈ 8.6px、
     * en 最坏 0.81 倍 ≈ 7.3px —— 都落在 9px 行高内、且远高于
     * {@link RsccTextFit#MIN_SCALE} = 0.6 的 tooltip 兜底线。</p>
     * <p>{@code drawFitLine} <b>没有按宽度截断的分支</b>，所以「· 体积」这一段在任何宽度下都不会被
     * 挤掉（唯一后果是整行小一点）。判据落在 {@code tools/selfcheck_round124_area_mark_layout.py} 的 ⑦ 组。</p>
     */
    private void drawTitleAndLabels(final GuiGraphics guiGraphics) {
        // 标题在左（宽度按整行给：它只有几个字，正常不会触发缩字号）
        final float titleW = RsccTextFit.drawFitLine(guiGraphics, font, title,
            px + LEFT_X, py + TITLE_Y, PANEL_W - LEFT_X * 2, COLOR_TITLE);
        // 副标题紧接标题右侧：中点分隔 + 「区域名 · 体积」，可用宽度按行尾算
        final int separatorW = font.width(SUBTITLE_SEP);
        final int separatorX = px + LEFT_X + (int) Math.ceil(titleW);
        final int subtitleX = separatorX + separatorW;
        final int subtitleW = px + PANEL_W - LEFT_X - subtitleX;
        if (subtitleW > 0) {
            RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(SUBTITLE_SEP),
                separatorX, py + TITLE_Y, separatorW, COLOR_DIM);
            RsccTextFit.drawFitLine(guiGraphics, font,
                Component.translatable(LANG + "subtitle", describeArea()),
                subtitleX, py + TITLE_Y, subtitleW, COLOR_DIM);
        }
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "label.marks"),
            px + LEFT_X, py + LABEL_Y, COLS * PITCH, COLOR_TEXT);
        RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "label.inventory"),
            px + RIGHT_X, py + LABEL_Y, COLS * PITCH, COLOR_TEXT);
    }

    /** 区域描述：「名字 · 体积」（体积文案走语言键，英文界面里不会漏出中文「格」）。 */
    private String describeArea() {
        if (area == null) {
            return "—";
        }
        final String name = area.name();
        final String volume = Component.translatable(LANG + "volume", area.volume()).getString();
        return name == null || name.isEmpty() ? volume : name + " · " + volume;
    }

    /**
     * 左侧 ghost 标记网格。
     * <p><b>网格里面不画任何文字</b>（第 124 轮）：槽框是实心矩形（{@code [GRID_Y-1, GRID_Y+GRID_H-1]}），
     * 画在网格内部的文字必然压在某一行格子上 —— 用户实机截图里「第 2 行上压着一行说明文字」就是原先
     * 那句空状态提示（{@code GRID_Y + GRID_H / 2 - 4 = 66}）。空状态说明现在在
     * {@link #drawInfoAndNotice} 的第三行（{@link #EMPTY_Y}），在网格下方。</p>
     */
    private void drawMarkGrid(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final List<MarkerEntry> entries = marks();
        final int hovered = markCellAt(mouseX, mouseY);
        for (int cell = 0; cell < COLS * ROWS; cell++) {
            final int x = px + LEFT_X + (cell % COLS) * PITCH;
            final int y = py + GRID_Y + (cell / COLS) * PITCH;
            McGui.slotFrame(guiGraphics, x - 1, y - 1);
            if (cell == hovered) {
                guiGraphics.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
            }
            if (cell >= entries.size()) {
                continue;
            }
            final MarkerEntry entry = entries.get(cell);
            final ItemStack display = entry == null ? ItemStack.EMPTY : entry.displayStack();
            if (display.isEmpty()) {
                continue;
            }
            guiGraphics.renderItem(display, x, y);
            // 数量门槛 > 1 时右下角写数量（与机器匹配区同一口径：门槛，不是「库存」）
            if (entry.amount() > 1L) {
                final String amount = entry.amount() > 999L ? "999+" : Long.toString(entry.amount());
                guiGraphics.drawString(font, amount, x + 17 - font.width(amount), y + 9,
                    0xFFFFFFFF, true);
            }
        }
    }

    private void drawInventoryGrid(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int hovered = inventoryCellAt(mouseX, mouseY);
        for (int cell = 0; cell < COLS * ROWS; cell++) {
            final int x = px + RIGHT_X + (cell % COLS) * PITCH;
            final int y = py + GRID_Y + (cell / COLS) * PITCH;
            McGui.slotFrame(guiGraphics, x - 1, y - 1);
            if (cell == hovered) {
                guiGraphics.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
            }
            final ItemStack stack = inventoryStack(inventorySlotFor(cell));
            if (stack.isEmpty()) {
                continue;
            }
            guiGraphics.renderItem(stack, x, y);
            if (stack.getCount() > 1) {
                guiGraphics.renderItemDecorations(font, stack, x, y);
            }
        }
    }

    /**
     * 网格下方三行文字（第 124 轮重排，行距统一 12px = 行高 9 + 间隙 3）：
     * <ol>
     *     <li><b>数量摘要</b>（{@link #INFO_Y}，恒画）：「标记 N / 288 条」，多出 36 格时换成
     *     {@code info.count.more} 如实说明「其余在机器界面里看」（不静默截断）；</li>
     *     <li><b>状态行</b>（{@link #HINT_Y}，恒画）：维度闸门 &gt; 操作反馈 &gt; 空状态说明 / 操作帮助。
     *     优先级不变，只是位置从「和数量行只差 9px」下移到行距 12px；</li>
     *     <li><b>空状态说明</b>（{@link #EMPTY_Y}，<b>仅空表时画</b>）：安全默认口径
     *     {@code hint.blocks_all}。第 124 轮之前「还没有标记」那句画在网格里（压格子），
     *     现在换成「空状态说明」独占网格下方一行。</li>
     * </ol>
     */
    private void drawInfoAndNotice(final GuiGraphics guiGraphics) {
        final List<MarkerEntry> entries = marks();
        // 第 1 行：数量摘要
        // 格子只有 9×4 = 36 个：多于它时如实说明「其余在机器窗口里看」，
        // 不能假装只配了 36 条（本项目反复强调「不许静默截断」）。
        final Component countLine = entries.size() > COLS * ROWS
            ? Component.translatable(LANG + "info.count.more", entries.size(),
                CollectionCacheBlockEntity.MATCH_CAPACITY, COLS * ROWS)
            : Component.translatable(LANG + "info.count", entries.size(),
                CollectionCacheBlockEntity.MATCH_CAPACITY);
        RsccTextFit.drawFitLine(guiGraphics, font, countLine,
            px + LEFT_X, py + INFO_Y, PANEL_W - LEFT_X * 2, COLOR_TEXT);
        // 第 2 行：状态（维度闸门 > 操作反馈 > 空状态说明 / 操作帮助）
        final String blocker = dimensionBlocker();
        final boolean showingNotice = notice != null && System.currentTimeMillis() < noticeUntilMs;
        final String hint;
        final int hintColor;
        if (blocker != null) {
            hint = blocker;
            hintColor = COLOR_ERROR;
        } else if (showingNotice) {
            hint = notice;
            hintColor = noticeColor;
        } else {
            hint = Component.translatable(
                entries.isEmpty() ? LANG + "hint.marks_empty" : LANG + "hint.edit_help").getString();
            hintColor = COLOR_DIM;
        }
        RsccTextFit.drawFitLine(guiGraphics, font, Component.literal(hint),
            px + LEFT_X, py + HINT_Y, PANEL_W - LEFT_X * 2, hintColor);
        // 第 3 行：空状态说明（仅空表）——「空的区域什么都不吸」是安全默认口径，
        // 有标记时这句不成立，所以不能恒画。
        if (entries.isEmpty()) {
            RsccTextFit.drawFitLine(guiGraphics, font, Component.translatable(LANG + "hint.blocks_all"),
                px + LEFT_X, py + EMPTY_Y, PANEL_W - LEFT_X * 2, COLOR_DIM);
        }
    }

    private void drawTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int markCell = markCellAt(mouseX, mouseY);
        final List<MarkerEntry> entries = marks();
        if (markCell >= 0 && markCell < entries.size()) {
            final MarkerEntry entry = entries.get(markCell);
            final List<Component> lines = new ArrayList<>(
                AdvancedCollectionCacheScreen.filterIconTooltipLines(entry));
            lines.add(Component.translatable(LANG + "tip.remove").withStyle(
                net.minecraft.ChatFormatting.GRAY));
            guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
        final int inventoryCell = inventoryCellAt(mouseX, mouseY);
        if (inventoryCell >= 0) {
            final ItemStack stack = inventoryStack(inventorySlotFor(inventoryCell));
            if (!stack.isEmpty()) {
                final List<Component> lines = new ArrayList<>(1);
                lines.add(stack.getHoverName());
                lines.add(Component.translatable(LANG + "tip.add").withStyle(
                    net.minecraft.ChatFormatting.GRAY));
                guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            }
            return;
        }
        // 说明行（悬停在两行小字上时给出完整说明：放不下时缩字号而不是省略）。
        // 第 124 轮：命中区必须落在「真正画了字的那两行」上 —— 旧写法用的是早已没人绘制的
        // HINT_Y 区（y=110..128），玩家把鼠标放在说明文字上反而什么都看不到。
        final boolean onStatusLine = mouseY >= py + HINT_Y && mouseY < py + HINT_Y + TEXT_LINE_H;
        final boolean onEmptyLine = entries.isEmpty()
            && mouseY >= py + EMPTY_Y && mouseY < py + EMPTY_Y + TEXT_LINE_H;
        if (onStatusLine || onEmptyLine) {
            guiGraphics.renderTooltip(font,
                List.of(Component.translatable(LANG + "tip.ghost"),
                    Component.translatable(LANG + "tip.capacity",
                        CollectionCacheBlockEntity.MATCH_CAPACITY)),
                java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /** 鼠标在哪个「标记格」上（{@code -1} = 不在网格里或超出已有条目数）。 */
    private int markCellAt(final int mouseX, final int mouseY) {
        return cellAt(mouseX, mouseY, px + LEFT_X);
    }

    /** 鼠标在哪个「背包格」上（{@code -1} = 不在网格里）。 */
    private int inventoryCellAt(final int mouseX, final int mouseY) {
        return cellAt(mouseX, mouseY, px + RIGHT_X);
    }

    private int cellAt(final int mouseX, final int mouseY, final int gridX) {
        if (mouseX < gridX || mouseX >= gridX + COLS * PITCH) {
            return -1;
        }
        if (mouseY < py + GRID_Y || mouseY >= py + GRID_Y + GRID_H) {
            return -1;
        }
        final int column = (mouseX - gridX) / PITCH;
        final int row = (mouseY - (py + GRID_Y)) / PITCH;
        return row * COLS + column;
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 0) {
            final int markCell = markCellAt((int) mouseX, (int) mouseY);
            if (markCell >= 0) {
                if (markCell < marks().size()) {
                    removeAt(markCell);
                }
                return true;
            }
            final int inventoryCell = inventoryCellAt((int) mouseX, (int) mouseY);
            if (inventoryCell >= 0) {
                addFromInventory(inventorySlotFor(inventoryCell));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
