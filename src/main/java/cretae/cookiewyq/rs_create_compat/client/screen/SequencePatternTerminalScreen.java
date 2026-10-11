package cretae.cookiewyq.rs_create_compat.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.SptGuiTextures;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RecipeTypeMachines;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import cretae.cookiewyq.rs_create_compat.network.RequestChamberListPacket;
import cretae.cookiewyq.rs_create_compat.network.SetArrangementScrollPacket;
import cretae.cookiewyq.rs_create_compat.network.SetResultScrollPacket;
import cretae.cookiewyq.rs_create_compat.network.SetSequenceChancePacket;
import cretae.cookiewyq.rs_create_compat.network.SetStepMachinePacket;
import cretae.cookiewyq.rs_create_compat.network.SetStepSkipDuplicatePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncStepMachinesPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 序列装配样板终端界面（v10：底部左侧 <b>1 格总样板槽</b> + 右侧 6 格单元样板窗口（小滚动条 / 滚轮翻页），
 * 两个标签均为正常字号；全宽流程编排，见 {@code SPT_GUI_TECH_DOC_V7.md}）。
 * <p><b>理念</b>：无 Tab；单元样板统一由「单元样板管理舱」管理，本终端只保留一个
 * <b>6 格单元样板窗口</b>（滚动条 / 滚轮翻页）用于取用生成出来的样板，
 * 腾出的横向空间全部给中列流程编排（卡片 126px 宽，机器名 / 步骤信息不再被截断）。</p>
 * <p>布局（V10）：中列流程编排占满左侧全宽（8 行 = 自绘行槽框 + 9-slice 卡片，可滚动）｜
 * 右列输入原料槽 + 产物 4×3 + 废料 4×3（各带 SMALL 轨道与概率小字）｜按钮区（生成按钮 +
 * 3 格样板输入槽 + <b>只读</b>循环次数文本）｜底部样板区（<b>1 格总样板槽</b> ｜ 断开处放一条小滚动条
 * ｜ <b>6 格单元样板窗口</b>，整排水平居中）+ 玩家背包 / 快捷栏。</p>
 * <p><b>硬规则</b>：精灵坐标 → Menu 坐标 = x、y 均 +1（本类常量除滚动条外全为 Menu 坐标，
 * 滚动条沿用「精灵坐标」口径且绘制 / 命中同源）；所有自绘元素的
 * tooltip 必须手动渲染，且绘制范围与 hover 判定共用同一批判定函数
 * （{@link #globalStepOf} / {@link #rowAt}）；所有 drawString 一律无阴影。</p>
 * <p><b>只读范围（本轮明确）</b>：流程顺序 / 每步次数 / 输入原料标记 / 产物 / 废料 / <b>循环次数</b>
 * 完全由配方导入生成（展示数据，非真实物品），玩家的修改入口一律取消；<b>但「该步用哪台执行舱」是可改的</b>
 * —— 行内「◀ 机器名 ▶」的左右箭头可以点，点击即切换候选并走既有的
 * {@link SetStepMachinePacket} 由服务端权威写入（用户要求：机器需要能更改）。
 * 底部那格总样板槽（只出不进）与 6 格单元样板窗口是两个真实容器，本来就可交互，不受这条约束影响。</p>
 */
public class SequencePatternTerminalScreen extends AbstractContainerScreen<SequencePatternTerminalMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/sequence_pattern_terminal.png");

    private static final String LANG = "gui.rs_create_compat.sequence_pattern_terminal.";

    /** 背景尺寸。 */
    private static final int BG_W = 256;
    private static final int BG_H = 324;

    // ===== 顶栏（Menu 坐标）=====
    /** 标题：左对齐、占顶栏左侧，与右侧搜索框完全分离（不再被搜索框压住 / 不再靠省略号）。 */
    private static final int TITLE_X = 17;
    private static final int TITLE_Y = 5;
    /** 搜索框只过滤流程编排：整列右移到最右，把左边让给标题。 */
    private static final int SEARCH_X = 157;
    private static final int SEARCH_Y = 5;
    private static final int SEARCH_W = 96;
    private static final int SEARCH_H = 12;

    // ===== 主内容区：流程编排（v8 起整列为全宽，左列「单元样板库」已彻底删除）=====
    private static final int ARR_ROWS = SequencePatternTerminalBlockEntity.ARRANGEMENT_WINDOW;
    private static final int ARR_TITLE_X = 9;
    private static final int ARR_TITLE_Y = 23;
    /** 卡片 9-slice 衬底：Menu (26, 32+i*18) 126×18（槽框 17px + 卡片，铺满面板可用宽度）。 */
    private static final int CARD_X = 26;
    private static final int CARD_Y = 32;
    private static final int CARD_W = 126;
    private static final int CARD_H = 18;
    /** 卡片内文字。 */
    private static final int ROW_TEXT_X = 29;
    /** 第 1 行操作名最大宽度（右侧还要放 ×N，超出由 trimToWidth 截断）。 */
    private static final int ROW_OP_W = 100;
    /** ×N 次数文本右边界（比卡片右内边留 3px，不与卡片边框相贴）。 */
    private static final int ROW_COUNT_RIGHT = 149;
    /** 机器区宽度（◀ 机器名 ▶ 的可点区域；最左侧 8px 让给机器方块图标）。 */
    private static final int ROW_MACHINE_W = 120;
    /** 第二行左侧的机器方块图标边长（16×16 图标按 0.5 缩放）。 */
    private static final int MACHINE_ICON = 8;
    /**
     * 行内机器选择箭头 ◀ / ▶ 的<b>点击热区宽度</b>（Menu px）。
     * <p>字形本身只有约 6px 宽，直接按字形判定很难点中；这里左右各外扩到 10px。
     * 两个热区之间仍隔着整段机器名（{@link #ROW_MACHINE_W} − 图标 − 20 ≈ 92px），
     * 因此不会互相吞点击，也不会把「机器名」误当成按钮。</p>
     */
    private static final int MACHINE_ARROW_HIT_W = 10;
    /** 行内机器控件所在那一行的高（与绘制的 8px 字形同高）。 */
    private static final int ROW_CONTROL_H = 8;
    /** 行 hover / tooltip 区域（行槽框 + 卡片，二者都由本类绘制）。 */
    private static final int ROW_X1 = 9;
    private static final int ROW_X2 = 152;
    private static final int ARR_SCROLL_X = 153;
    private static final int ARR_SCROLL_Y = 32;
    private static final int ARR_SCROLL_H = 152;
    private static final int ARR_PANEL_X1 = 7;
    private static final int ARR_PANEL_X2 = 160;
    private static final int ARR_PANEL_Y1 = 21;
    private static final int ARR_PANEL_Y2 = 188;

    // ===== 右列（文字与槽位框的坐标一律取「精灵坐标 +1」） =====
    /** 输入原料标签与槽位（唯一操作口）。 */
    private static final int INPUT_LABEL_X = 166;
    private static final int OP_LABEL_Y = 25;
    private static final int RESULT_TITLE_X = 166;
    private static final int RESULT_TITLE_Y = 58;
    private static final int SCRAP_TITLE_X = 166;
    private static final int SCRAP_TITLE_Y = 123;
    private static final int SECTION_SCROLL_X = 246;
    private static final int RESULT_SCROLL_Y = 64;
    private static final int SCRAP_SCROLL_Y = 129;
    private static final int SECTION_SCROLL_H = 52;
    private static final int SECTION_PANEL_X1 = 163;
    private static final int SECTION_PANEL_X2 = 253;
    private static final int RESULT_PANEL_Y1 = 58;
    private static final int RESULT_PANEL_Y2 = 120;
    private static final int SCRAP_PANEL_Y1 = 123;
    private static final int SCRAP_PANEL_Y2 = 185;

    // ===== 控制行（全部原版控件，Menu y=190 高 18，V5 文档 §3.6） =====
    private static final int CONTROL_Y = 190;
    private static final int CONTROL_H = 18;
    private static final int GEN_X = 9;
    private static final int GEN_W = 56;
    /**
     * 行内「跳过重复样板」小开关：与第 1 行文字同高、右对齐在卡片第 1 行最右端。
     * <p><b>为什么是行内控件而不是子界面</b>：用户原话「就只一个开关……不需要额外的界面……归咎为每一个步骤给它单独设定」。
     * 因此把「是否重复生成」做成<b>每一步行内就地可点</b>的小控件，复用行内既有的机器控件横带那套
     * 「绘制范围与 hover 判定同源（左闭右开）」的做法，绝不新开子窗口、不引入搜索框 / 维度切换。</p>
     * <p><b>几何</b>：盒宽 {@link #ROW_SKIP_W}=20、高 {@link #ROW_SKIP_H}=8，右缘对齐 ×N 原右边界
     * （{@link #ROW_COUNT_RIGHT}）；×N 因此左移 {@code ROW_SKIP_W + 2}px。它与第 2 行的机器控件
     * （{@link #rowControlY} 起）几何上完全不相交（一个在 `cardTop+1..+9`，一个在 `cardTop+9..+17`）。</p>
     * <p><b>状态从哪来</b>：显示状态（「已有 / 没有」= 该步机器 / 样板是否已就位）与开关状态都只读服务端下发的
     * {@link SyncStepMachinesPacket}（见 {@link #stepSkipDuplicateOf} / {@link #stepUnitEquipped}），
     * 点击只发 {@link SetStepSkipDuplicatePacket} —— 客户端不写任何权威状态。</p>
     */
    private static final int ROW_SKIP_W = 20;
    private static final int ROW_SKIP_H = ROW_CONTROL_H;
    /** 行内开关的背景色：开 = 琥珀（跳过生效）；关 = 深灰（照常生成）。 */
    private static final int COLOR_SKIP_BG_ON = 0xFF7A5A00;
    private static final int COLOR_SKIP_BG_OFF = 0xFF333333;
    /** 行内开关的文字色：机器 / 样板已就位 = 亮白；没有 = 灰。 */
    private static final int COLOR_SKIP_TEXT_HAS = 0xFFFFFFFF;
    private static final int COLOR_SKIP_TEXT_NONE = 0xFFBFBFBF;
    /**
     * 样板输入槽组（3 格，{@code refinedstorage:pattern}）：Menu (70/88/106, 191)。
     * <p><b>为什么在这里</b>：原「导入」按钮占 Menu 69..125，按用户要求删除该按钮后这块留白
     * 正好放下 3 格槽位（54px），界面<b>不留空洞</b>；槽框用 {@link McGui#slotFrame} 运行时绘制，
     * <b>不</b>改背景贴图（因此背景留白 / 槽位像素校验不受影响）。</p>
     * <p><b>为什么需要它</b>：生成样板的耗材（精致存储样板）以前直接从玩家背包扣，玩家必须背在身上；
     * 现在改为玩家把样板放进终端自己的这 3 格（随方块 NBT 持久化），生成只从终端内扣。</p>
     * <p>按钮行 18px 高、左右都是原版控件，放不下第三个标签 —— 这 3 格的说明一律走
     * <b>手动渲染的 tooltip</b>（硬规则：本模组不会自动渲染 tooltip），槽框本身即可表明是槽位。</p>
     */
    /**
     * 「循环」标签（Java 渲染、无阴影）：<b>只读</b>显示配方给出的循环次数。
     * <p>用户要求「循环次数是固定值，不要让用户修改」→ 旧的 [-] [输入框] [+] 三个控件整体删除，
     * 改成纯文本「循环 ×N」（值由服务端 ContainerData 下发，客户端只显示，不提供任何编辑入口）。</p>
     */
    private static final int LOOP_LABEL_X = 130;
    private static final int LOOP_LABEL_Y = 195;
    /** 只读循环文本的 hover 判定宽度（tooltip 用；「循环 ×N」在正常字号下约 40~60px）。 */
    private static final int LOOP_LABEL_W = 60;

    // ===== 底部 =====
    private static final int INV_LABEL_X = 49;
    private static final int INV_LABEL_Y = 233;

    // ===== 底部样板区（v10：左侧 1 格总样板槽 ｜ 断开（翻页小滚动条）｜ 右侧 6 格单元样板窗口，整排居中） =====
    /** 两组标题与行内控件的基线 y（正常字号，垂直居中于 17px 槽行：213 + (17-8)/2 = 217）。 */
    private static final int PATTERN_LABEL_Y = 217;
    /**
     * 「总样板」标题左对齐的 Menu x：整排最左端（Menu 8），右缘 35 —— 与第 1 格槽框（精灵 36）留 1px。
     * <p>整排从 Menu 8 排到 Menu 249（共 241px），左右各留 4px 白、几何中心 127.5 = 窗口中心。</p>
     */
    private static final int PATTERN_TITLE_X = 8;
    /**
     * 「单元样板」标题左对齐的 Menu x：整排最右端，落在第 6 格单元样板槽框（精灵 228）右侧 3px 处。
     * <p><b>为什么文案缩短成「单元」</b>：250px 的排宽里，1 格总样板槽（17）＋ 6 格单元样板槽（107）
     * ＋「总样板」27 ＋「单元样板」36 ＋ 翻页滚动条 7 合计 194px，其实是够的；但两端标签留白与
     * 滚动条居中还需要间距，按用户「空间不够就缩短文案」的口径继续用短的「单元」（18px），
     * 排布更舒展（完整文案仍由语言键 {@code unit_pattern_slots}（「单元样板」）供 tooltip 使用）。</p>
     */
    private static final int UNIT_TITLE_X = 232;
    /** 翻页方式（v10）：<b>复用工程既有的滚动条</b>，不再画 ◀ ▶ 按钮（用户觉得那对按钮「怪怪的」）。 */
    /**
     * 单元样板窗口的翻页滚动条（Menu 坐标）：7×16，与其它三条滚动条同款（{@link SptScrollbarWidget}.SMALL）。
     * <p><b>几何</b>：Menu (85, 213)（精灵 84..90 / 212..228），正好落在「总样板槽（精灵 36..52）」与
     * 「单元样板槽（精灵 122..228）」之间的断开区里 —— 与两侧槽框、两端标签、按钮区面板互不重叠
     * （见 {@code tmp_textures/verify_gui_layout.py}）。背景该处保持留白，滚动条连同轨道由 Java 运行时
     * 绘制（{@link SptScrollbarWidget#renderWidget} 的 fill + 滑块贴图），<b>不新画任何背景贴图</b>。</p>
     */
    private static final int UNIT_SCROLL_X = 85;
    private static final int UNIT_SCROLL_Y = 213;
    private static final int UNIT_SCROLL_W = 7;
    private static final int UNIT_SCROLL_H = 16;
    /**
     * 滚轮翻页热区（Menu）：覆盖「滚动条 → 单元样板槽 6 格」，
     * 只吃这一块，绝不抢产物 / 废料 / 流程编排的滚轮区（几何上完全不相交）。
     */
    private static final int UNIT_SCROLL_X1 = 85;
    private static final int UNIT_SCROLL_X2 = 230;
    private static final int UNIT_SCROLL_Y1 = 212;
    private static final int UNIT_SCROLL_Y2 = 230;

    /** 文本颜色（无阴影）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_LABEL = 0xFF404040;
    private static final int COLOR_ROW_OP = 0xFFF0E6D0;
    private static final int COLOR_ROW_MACHINE = 0xFFE0E0E0;
    private static final int COLOR_ROW_MACHINE_PREVIEW = 0xFF93AECC;
    private static final int COLOR_ROW_COUNT = 0xFFFFE3A8;
    private static final int COLOR_CHANCE = 0xFFFFE066;
    private static final int COLOR_NO_MACHINE = 0xFFFF6A6A;
    /** 翻页按钮字形（可用 / 不可用）。 */
    private static final int COLOR_PAGE_GLYPH = 0xFF2E2E2E;
    private static final int COLOR_PAGE_GLYPH_OFF = 0xFF7A7A7A;

    private EditBox searchBox;
    private Button generateButton;
    private SptScrollbarWidget arrangementScrollbar;
    private SptScrollbarWidget resultScrollbar;
    private SptScrollbarWidget scrapScrollbar;
    /** 单元样板窗口翻页滚动条（v10，取代 ◀ ▶ 按钮）。 */
    private SptScrollbarWidget unitPageScrollbar;
    private final List<SptScrollbarWidget> scrollbars = new ArrayList<>();

    /** 距上次本地滚动交互的帧数：期间不采纳服务端回传的偏移，避免拖拽/滚轮被“拽回”。 */
    private int framesSinceArrScroll;
    private int framesSinceResultScroll;
    private int framesSinceScrapScroll;
    private int framesSinceUnitPageScroll;
    /** 上一次的搜索文本（变化时才刷新行可见性）。 */
    private String lastSearch = "";
    /** 被点击选中的卡片行（全局步下标，-1 = 无）：用于卡片的“选中”三态。 */
    private int selectedRow = -1;
    /**
     * 配方类型 → 「能执行它的机器」缓存（键含客户端世界身份，换存档 / 换维度即失效）。
     * <p><b>第 49 轮：从「每个界面实例一份」改成「整个客户端会话一份」</b>。旧实现把这几张缓存挂在
     * 界面实例上（构造时新建），于是<b>每次按快捷键开终端都要重建一遍</b>：逐个配方类型去配方表里
     * 反查机器（还要探 32 条配方的 {@code getToastSymbol()}），以及逐步骤按配方解析候选组 / 过渡件。
     * 这些结果只取决于「客户端世界 + 配方表」，与界面实例无关，缓存跨实例复用是纯赚。
     * 失效维度只有「客户端世界」：{@link #ensureCacheOwner()} 发现世界换了就整批清空。</p>
     */
    private static final java.util.Map<String, java.util.List<RecipeTypeMachines.Machine>> machineCache =
        new java.util.HashMap<>();
    /** 序列装配配方 ingredient 的候选缓存（键 = 配方 id）：跨界面实例复用，见 {@link #machineCache}。 */
    private static final java.util.Map<String, List<ItemStack>> assemblyInputCache = new java.util.HashMap<>();
    /** 「优先复用中间产物」打开时，该配方过渡件的解析结果缓存（键 = 候选缓存键）：跨界面实例复用。 */
    private static final java.util.Map<String, ItemStack> reuseIntermediateCache = new java.util.HashMap<>();
    /** 每步处理配方 ingredient 的候选缓存（键 = 配方 id + '#' + 步序）：跨界面实例复用。 */
    private static final java.util.Map<String, List<ItemStack>> stepInputCache = new java.util.HashMap<>();
    /** 上面四张缓存当前归属的客户端世界（用于判「世界换了 ⇒ 整批作废」）。 */
    private static net.minecraft.world.level.Level cacheOwner;
    /**
     * 机器列表（{@code SyncChamberListPacket}）的派生缓存：上一次快照对象 + 「配方类型 + 搜索词」→ 列表。
     * <p><b>为什么要它</b>：{@link #chambersFor} 在渲染时每个可见行要问它好几次（机器名、有没有机器、
     * 箭头是否可点），而旧实现每次调用都<b>现场过滤 + 现场排序 + 现场分配</b>一个新列表
     * （O(执行仓数 log 执行仓数) × 每帧 20 次以上）。列表本身只随服务端快照或搜索词变化，
     * 因此按这两个维度缓存即可，命中时一次比较就能拿到同一份不可变列表。</p>
     */
    private static List<SyncChamberListPacket.Entry> chamberCacheSource = List.of();
    private static String chamberCacheQuery = "";
    private static final java.util.Map<String, List<SyncChamberListPacket.Entry>> chamberCache =
        new java.util.HashMap<>();

    public SequencePatternTerminalScreen(final SequencePatternTerminalMenu menu,
                                         final Inventory inventory,
                                         final Component title) {
        super(menu, inventory, title);
        this.imageWidth = BG_W;
        this.imageHeight = BG_H;
        this.titleLabelY = 10000;     // 标题全部由本类按文档坐标绘制
        this.inventoryLabelY = 10000;
    }

    /**
     * 四张跨实例缓存的世界归属检查：世界换了（换存档 / 换维度 / 退回标题）就整批清空。
     * <p>不这样做的话，旧存档里某条配方 id 的候选组会被带进新存档显示（客户端缓存没有别的失效入口）。</p>
     */
    private static void ensureCacheOwner() {
        final net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        if (level == cacheOwner) {
            return;
        }
        cacheOwner = level;
        machineCache.clear();
        assemblyInputCache.clear();
        reuseIntermediateCache.clear();
        stepInputCache.clear();
    }

    @Override
    protected void init() {
        super.init();

        // 搜索框：只过滤流程编排（不再过滤单元样板库）
        searchBox = new EditBox(font, leftPos + SEARCH_X, topPos + SEARCH_Y, SEARCH_W, SEARCH_H,
            Component.translatable(LANG + "search_hint"));
        searchBox.setMaxLength(64);
        searchBox.setTextShadow(false);
        addRenderableWidget(searchBox);

        // 流程编排滚动条（SMALL，轨道 Menu (153,32) 7×152）：真正生效，拖拽/滚轮都改偏移
        arrangementScrollbar = new SptScrollbarWidget(leftPos + ARR_SCROLL_X, topPos + ARR_SCROLL_Y,
            SptScrollbarWidget.Type.SMALL, ARR_SCROLL_H);
        arrangementScrollbar.setListener(offset -> {
            final int row = (int) Math.round(offset);
            // 监听器一被调用就重开「本地交互自持窗口」：无论这次是玩家拖动 / 滚轮，还是本类把权威值
            // 回写成控件（见 updateScrollbars），都重新计时，保证不会在玩家操作中途被服务端旧值「拽回」。
            framesSinceArrScroll = 0;
            if (row != menu.getArrangementOffset()) {
                menu.setArrangementOffset(row);
                PacketDistributor.sendToServer(
                    new SetArrangementScrollPacket(menu.containerId, row));
            }
        });
        scrollbars.add(arrangementScrollbar);

        // 产物 / 废料滚动条（SMALL，轨道 Menu (246,64) / (246,129) 7×52）
        resultScrollbar = new SptScrollbarWidget(leftPos + SECTION_SCROLL_X, topPos + RESULT_SCROLL_Y,
            SptScrollbarWidget.Type.SMALL, SECTION_SCROLL_H);
        resultScrollbar.setListener(offset -> {
            final int page = (int) Math.round(offset);
            framesSinceResultScroll = 0;
            if (page != menu.getResultOffset()) {
                menu.setResultOffset(page);
                PacketDistributor.sendToServer(new SetResultScrollPacket(menu.containerId, false, page));
            }
        });
        scrollbars.add(resultScrollbar);
        scrapScrollbar = new SptScrollbarWidget(leftPos + SECTION_SCROLL_X, topPos + SCRAP_SCROLL_Y,
            SptScrollbarWidget.Type.SMALL, SECTION_SCROLL_H);
        scrapScrollbar.setListener(offset -> {
            final int page = (int) Math.round(offset);
            framesSinceScrapScroll = 0;
            if (page != menu.getScrapOffset()) {
                menu.setScrapOffset(page);
                PacketDistributor.sendToServer(new SetResultScrollPacket(menu.containerId, true, page));
            }
        });
        scrollbars.add(scrapScrollbar);

        // 单元样板窗口翻页滚动条（v10）：与其它三条同款，绝对定位到某一页（服务端权威夹紧）。
        // 拖动 / 点轨道 → 把目标页码编进按钮 id 发给服务端；滚轮则由 mouseScrolled 的 ±1 通道处理。
        unitPageScrollbar = new SptScrollbarWidget(leftPos + UNIT_SCROLL_X, topPos + UNIT_SCROLL_Y,
            SptScrollbarWidget.Type.SMALL, UNIT_SCROLL_H, UNIT_SCROLL_W);
        unitPageScrollbar.setListener(page -> {
            final int target = (int) Math.round(page);
            framesSinceUnitPageScroll = 0;
            if (target != menu.getUnitPage()) {
                sendButton(SequencePatternTerminalMenu.BTN_UNIT_PAGE_SET + Math.max(0, target));
            }
        });
        scrollbars.add(unitPageScrollbar);

        // 控制行：生成样板（「导入」按钮已按用户要求删除：配方多起来不如 JEI 直接选择；
        // 其腾出的位置改放「样板输入槽」3 格，见下方 renderBg 的槽框绘制）
        generateButton = addRenderableWidget(noShadowButton(GEN_X, CONTROL_Y, GEN_W, CONTROL_H,
            Component.translatable(LANG + "generate"), btn -> sendButton(SequencePatternTerminalMenu.BTN_GENERATE)));

        // 循环次数：配方给定、只读（用户要求「不要让用户修改」）。
        // 旧的 [-] [输入框] [+] 三个控件与它们的 C2S 包发送逻辑已整体删除，这里不再注册任何控件，
        // 数值由 renderLabels 直接以文本绘制（值来自服务端 ContainerData）。

        // 打开界面：拉取机器列表（供行内机器选择 / 详细配置使用）
        PacketDistributor.sendToServer(new RequestChamberListPacket(""));
    }

    /** 原版按钮外观，但文字按硬规则无阴影（{@code drawString(..., false)}）。 */
    private Button noShadowButton(final int x, final int y, final int w, final int h,
                                  final Component message, final Button.OnPress onPress) {
        return new NoShadowButton(leftPos + x, topPos + y, w, h, message, onPress);
    }

    /** 无阴影文字的原版按钮（DEFAULT_NARRATION 是 protected static，只能在 Button 子类里引用）。 */
    private static final class NoShadowButton extends Button {
        private NoShadowButton(final int x, final int y, final int width, final int height,
                               final Component message, final OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        @Override
        public void renderString(final GuiGraphics guiGraphics, final Font font, final int color) {
            guiGraphics.drawString(font, getMessage(),
                getX() + (getWidth() - font.width(getMessage())) / 2,
                getY() + (getHeight() - 8) / 2, color, false);
        }
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /**
     * 循环次数（<b>只读</b>）：数值来自服务端 ContainerData；客户端不再有任何编辑入口
     * （旧的输入框 / ± 按钮 / {@code SetSequenceCountPacket} 发送逻辑已整体删除 ——
     * 用户要求「循环次数是固定值，不要让用户修改」）。
     */
    private int loopsValue() {
        return menu.getLoops();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ===== 绘制与命中共用的判定 =====

    /** 窗口行 i 对应的全局步下标；返回 -1 表示该行不在窗口内（不绘制、不提示、不可交互）。 */
    private int globalStepOf(final int windowRow) {
        final int global = menu.getArrangementOffset() + windowRow;
        return global < menu.getArrangementSize() ? global : -1;
    }

    /** 卡片衬底上边缘的 Menu y（Menu (26, 32+i*18) 126×18）。 */
    private static int cardTop(final int windowRow) {
        return CARD_Y + windowRow * 18;
    }

    /** 卡片内第二行（机器选择控件所在行）的 Menu y：与绘制同源，命中判定直接复用。 */
    private static int rowControlY(final int windowRow) {
        return cardTop(windowRow) + 9;
    }

    /** 行内机器控件（◀ 机器名 ▶）左端的 Menu x（机器图标右侧）。 */
    private static int rowControlX() {
        return ROW_TEXT_X + MACHINE_ICON;
    }

    /** 行内机器控件的可用宽度（含 ◀ 与 ▶ 两个字形）。 */
    private static int rowControlW() {
        return ROW_MACHINE_W - MACHINE_ICON;
    }

    /** 行内「跳过重复」开关的左端（Menu x；右缘恒贴卡片第 1 行最右端 {@link #ROW_COUNT_RIGHT}）。 */
    private static int rowSkipX() {
        return ROW_COUNT_RIGHT - ROW_SKIP_W;
    }

    /** 行内「跳过重复」开关所在那一行（卡片第 1 行）的 Menu y：与绘制同源，命中判定直接复用。 */
    private static int rowSkipY(final int windowRow) {
        return cardTop(windowRow) + 1;
    }

    /** 卡片第 1 行 ×N 次数的右边界（把最右端 {@code ROW_SKIP_W + 2}px 让给行内开关）。 */
    private static int rowCountRight() {
        return rowSkipX() - 2;
    }

    /** 鼠标所在的流程行（行槽框 + 卡片合并判定，-1 = 没有）；绘制、hover、tooltip 全部走这里。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (mouseX < leftPos + ROW_X1 || mouseX >= leftPos + ROW_X2) {
            return -1;
        }
        for (int i = 0; i < ARR_ROWS; i++) {
            final int global = globalStepOf(i);
            if (global < 0) {
                continue;
            }
            final int top = topPos + cardTop(i);
            if (mouseY >= top && mouseY < top + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    /** 与绘制范围一致的「是否在 x,y 处绘制该单元」判定（用于自绘元素 tooltip）。 */
    private boolean isHoveringRect(final int x, final int y, final int w, final int h,
                                   final double mouseX, final double mouseY) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + w
            && mouseY >= topPos + y && mouseY < topPos + y + h;
    }

    // ===== 搜索（只过滤流程编排） =====

    private String searchQuery() {
        return searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 该步是否通过顶部搜索框（空查询 = 全部通过）。用于行内容显示与交互判定。
     * <p>顶部搜索框只按<b>操作名（机器种类）</b>与<b>机器名</b>做自由文本匹配。
     * 「按步骤类型 / 按机器」那套结构化过滤（连同它的子窗口与「当前过滤」摘要行）已按用户最终口径
     * 整体删除 —— 用户要的不是「筛选哪些行显示」，而是「每一步行内一个开关」。</p>
     */
    private boolean rowMatches(final int globalStep) {
        if (globalStep < 0) {
            return true;
        }
        final String query = searchQuery();
        if (query.isEmpty()) {
            return true;
        }
        final String op = machineKindName(recipeTypeOf(globalStep)).toLowerCase(Locale.ROOT);
        final String machine = machineNameOf(globalStep).toLowerCase(Locale.ROOT);
        return op.contains(query) || machine.contains(query);
    }

    // ===== 行内「跳过重复样板」开关（每一步单独设定；只读展示 + 点击走 C2S 包） =====

    /**
     * 该步生成时是否跳过重复样板（客户端读服务端快照）。
     * <p><b>为什么是「每一步」而不是全局</b>：单元样板本身<b>不绑定机器</b>（判重只看语义，
     * 见 {@code UnitPatternDedupe}）；真正绑定机器、且逐台机器执行的是<b>流程编排的每一步</b>。
     * 因此「这一步要不要跳过重复生成」属于那一步，而不是整个终端、更不是某台机器的属性。</p>
     * <p>无快照时回落默认开（与服务端「旧存档缺键 ⇒ 默认开」的默认档一致）。</p>
     */
    private boolean stepSkipDuplicateOf(final int globalStep) {
        final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(globalStep);
        return entry == null || entry.skipDuplicate();
    }

    /** 该步是否已存在语义完全相同的单元样板（服务端扫描结果，随 {@link SyncStepMachinesPacket} 下发）。 */
    private boolean stepDuplicateExists(final int globalStep) {
        final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(globalStep);
        return entry != null && entry.duplicateExists();
    }

    /**
     * 该步的<b>机器 / 样板是否已就位</b>（服务端扫描结果，随 {@link SyncStepMachinesPacket} 下发）
     * —— 行内那两个字「已有 / 没有」显示的就是这一位。
     *
     * <h2>为什么它<b>不是</b>{@link #stepDuplicateExists}</h2>
     * <p>两者回答的是两个不同的问题，此前混用正是用户实测的缺陷（原话：
     * 「这一个冲压明明是有的，但是你却显示为没有」）：</p>
     * <ul>
     *     <li>{@code stepDuplicateExists}：「<b>生成时会跳过重复吗</b>」= 同一条配方、同一个步序的
     *     完全相同的样板是否已存在（判据在 {@code UnitPatternDedupe#sameSemantics}，生成侧同源）；</li>
     *     <li>{@code unitEquipped}：「<b>这一步的机器 / 样板有没有</b>」= 本网络里是否已有一条链，
     *     它的配方类型就是该步的操作类型，且链上确实放着一张该操作类型的单元样板
     *     （判据在 {@code SequencePatternTerminalBlockEntity#stepUnitEquipped}）。</li>
     * </ul>
     * <p>实机存档里的反例：列车轨道的冲压步与坚固板的冲压步是两条配方 ⇒ 判重为 false，
     * 但动力冲压机槽里确实放着冲压样板 ⇒ 就位为 true。行内文字问的是后者，tooltip 里再单列前者。</p>
     */
    private boolean stepUnitEquipped(final int globalStep) {
        final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(globalStep);
        return entry != null && entry.unitEquipped();
    }

    /** 行内开关的 hover / 点击判定（与 {@link #renderStepSkipToggle} 的绘制范围同源：左闭右开）。 */
    private boolean stepSkipToggleHitAt(final int windowRow, final double mouseX, final double mouseY) {
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return false;
        }
        final int global = globalStepOf(windowRow);
        if (global < 0 || !rowMatches(global)) {
            return false;
        }
        final int y = topPos + rowSkipY(windowRow);
        if (mouseY < y || mouseY >= y + ROW_SKIP_H) {
            return false;
        }
        final int x = leftPos + rowSkipX();
        return mouseX >= x && mouseX < x + ROW_SKIP_W;
    }

    /**
     * 点击行内开关：<b>只发 C2S 包</b>（携带目标状态 = 当前值的取反），由服务端校验下标 / 终端存在后
     * 写回并回传快照。客户端不写任何权威状态（与行内机器切换同一套「服务端权威」做法）。
     */
    private void toggleStepSkipDuplicate(final int windowRow) {
        final int global = globalStepOf(windowRow);
        if (global < 0) {
            return;
        }
        PacketDistributor.sendToServer(
            new SetStepSkipDuplicatePacket(global, !stepSkipDuplicateOf(global)));
    }

    /** 该步的配方类型（优先取服务端快照，回退读单元样板上的 RecipeType 标记）。 */
    private String recipeTypeOf(final int globalStep) {
        final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(globalStep);
        if (entry != null && entry.recipeType() != null && !entry.recipeType().isEmpty()) {
            return entry.recipeType();
        }
        final SequencePatternData.UnitData unit = unitDataAt(globalStep);
        return unit == null || unit.recipeType() == null ? "" : unit.recipeType();
    }

    /** 该配方类型下「可用机器」列表（先取全量，再按顶部搜索框过滤；按坐标排序）。 */
    private List<SyncChamberListPacket.Entry> chambersFor(final String recipeType) {
        final String query = searchQuery();
        if (query.isEmpty()) {
            return chambersForType(recipeType);
        }
        ensureChamberCache();
        final String key = recipeType + "\u0000" + query;
        final List<SyncChamberListPacket.Entry> cached = chamberCache.get(key);
        if (cached != null) {
            return cached;
        }
        final List<SyncChamberListPacket.Entry> result = new ArrayList<>();
        for (final SyncChamberListPacket.Entry chamber : chambersForType(recipeType)) {
            if (chamber.name().toLowerCase(Locale.ROOT).contains(query)) {
                result.add(chamber);
            }
        }
        // 冻结成不可变列表再入缓存：调用方只读，避免任何一条路径拿回去改坏缓存
        final List<SyncChamberListPacket.Entry> frozen = List.copyOf(result);
        chamberCache.put(key, frozen);
        return frozen;
    }

    /**
     * 该配方类型下的<b>全部</b>候选执行仓（按坐标排序，<b>不</b>受顶部搜索框过滤）。
     * <p>给「机器选择」子界面用：它自带搜索框，若父界面先按自己的搜索词砍一刀，
     * 玩家在子界面里就搜不到那些被父界面过滤掉的机器。</p>
     * <p><b>第 49 轮：结果按「快照 + 配方类型」缓存</b>。旧实现每次调用都现场过滤 + 现场排序 + 新建列表，
     * 而渲染时每个可见行要问它好几次（机器名 / 有没有机器 / 箭头是否可点），一帧几十次分配与排序。
     * 列表只随服务端快照变化，因此用快照对象身份做失效维度即可。</p>
     */
    private List<SyncChamberListPacket.Entry> chambersForType(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return List.of();
        }
        ensureChamberCache();
        final List<SyncChamberListPacket.Entry> cached = chamberCache.get(recipeType);
        if (cached != null) {
            return cached;
        }
        final List<SyncChamberListPacket.Entry> result = new ArrayList<>();
        for (final SyncChamberListPacket.Entry chamber : SyncChamberListPacket.getLastReceived()) {
            if (recipeType.equals(chamber.recipeType())) {
                result.add(chamber);
            }
        }
        result.sort(java.util.Comparator.comparingLong((SyncChamberListPacket.Entry c) -> c.pos().asLong()));
        final List<SyncChamberListPacket.Entry> frozen = List.copyOf(result);
        chamberCache.put(recipeType, frozen);
        return frozen;
    }

    /**
     * 机器列表派生缓存的失效检查：服务端快照换了对象、搜索词变了 ⇒ 整批作废。
     * <p>{@code SyncChamberListPacket.getLastReceived()} 每次收包都是一个新列表对象，因此身份比较就够了；
     * 搜索词必须单独记一份，否则「同一份快照 + 换了搜索词」会命中按旧词过滤的缓存（筛错行）。</p>
     */
    private void ensureChamberCache() {
        final List<SyncChamberListPacket.Entry> source = SyncChamberListPacket.getLastReceived();
        final String query = searchQuery();
        if (source == chamberCacheSource && query.equals(chamberCacheQuery)) {
            return;
        }
        chamberCacheSource = source;
        chamberCacheQuery = query;
        chamberCache.clear();
    }

    /** 该步要显示的机器名（已指派用指派值；未指派用该配方类型下第一台作预览；都没有则空串）。 */
    private String machineNameOf(final int globalStep) {
        final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(globalStep);
        if (entry != null && entry.hasPos() && !entry.machineName().isEmpty()) {
            return entry.machineName();
        }
        final List<SyncChamberListPacket.Entry> candidates = chambersFor(recipeTypeOf(globalStep));
        return candidates.isEmpty() ? "" : candidates.get(0).name();
    }

    /** 该步是否已显式指派机器（与 {@link #machineNameOf} / 快照同源）。 */
    private boolean isMachineAssigned(final int globalStep) {
        final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(globalStep);
        return entry != null && entry.hasPos() && !entry.machineName().isEmpty();
    }

    /**
     * 该步是否<b>真的</b>没有机器可用：已指派时一律为 false（即便快照里暂时查不到候选，
     * 也不能在已绑定的情况下误报「无可用机器」）。
     */
    private boolean hasNoMachine(final int globalStep) {
        return !isMachineAssigned(globalStep) && chambersFor(recipeTypeOf(globalStep)).isEmpty();
    }

    // ===== 行内「切换执行仓」控件（用户要求：那对左右箭头必须能点） =====

    /**
     * 行内机器选择箭头 ◀ / ▶ 的命中方向（与 {@link #renderRowTexts} 的绘制范围同源：
     * 都用 {@link #rowControlX} / {@link #rowControlW} / {@link #rowControlY}）。
     *
     * @return {@code -1} = 命中 ◀（上一台）、{@code +1} = 命中 ▶（下一台）、{@code 0} = 没命中
     */
    private int machineSwitchDirectionAt(final int windowRow, final double mouseX, final double mouseY) {
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return 0;
        }
        final int global = globalStepOf(windowRow);
        // 窗口外 / 被搜索过滤 / 这一格没有机器可切（画的是红字「无可用机器」）→ 箭头不可点
        if (global < 0 || !rowMatches(global) || hasNoMachine(global)) {
            return 0;
        }
        final int y = topPos + rowControlY(windowRow);
        if (mouseY < y || mouseY >= y + ROW_CONTROL_H) {
            return 0;
        }
        final int x1 = leftPos + rowControlX();
        final int x2 = x1 + rowControlW();
        if (mouseX >= x1 && mouseX < x1 + MACHINE_ARROW_HIT_W) {
            return -1;
        }
        if (mouseX >= x2 - MACHINE_ARROW_HIT_W && mouseX < x2) {
            return 1;
        }
        return 0;
    }

    /**
     * 切换该步绑定的执行仓（◀ 上一台 / ▶ 下一台，到两端环绕）。
     *
     * <p><b>走既有的 C2S 包</b>：{@link SetStepMachinePacket} —— 与服务端
     * （{@code SetStepMachinePacket#handle} 只接受「网络中真实存在的执行仓」并立刻回传全量
     * 步骤机器快照）同一条路，客户端不写任何权威状态。快照回来后
     * {@link #machineNameOf} / {@link #isMachineAssigned} 自动显示新的指派。</p>
     *
     * <p><b>候选与当前值同源</b>：候选来自 {@link #chambersFor}（该步配方类型 + 搜索过滤 + 按坐标排序），
     * 当前值来自 {@link #machineNameOf}。未指派时它显示的是「第一台预览」，因此第一次点 ▶
     * 就等于把预览那台<b>正式指派</b>下去（而不是跳到第二台）——与玩家看到的文字一致。</p>
     */
    private void switchStepMachine(final int windowRow, final int direction) {
        final int global = globalStepOf(windowRow);
        if (global < 0) {
            return;
        }
        final SyncChamberListPacket.Entry target = neighborMachine(global, direction);
        if (target == null) {
            return;
        }
        PacketDistributor.sendToServer(new SetStepMachinePacket(global, true, target.pos(), target.name()));
    }

    /**
     * 鼠标是否压在行内「机器控件」横带上（◀ 机器名 ▶ 一整段；与绘制共用
     * {@link #rowControlX} / {@link #rowControlW} / {@link #rowControlY}，因此绘制与命中不会错开）。
     */
    private boolean machineStripHitAt(final int windowRow, final double mouseX, final double mouseY) {
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return false;
        }
        final int global = globalStepOf(windowRow);
        if (global < 0 || !rowMatches(global)) {
            return false;
        }
        final int y = topPos + rowControlY(windowRow);
        if (mouseY < y || mouseY >= y + ROW_CONTROL_H) {
            return false;
        }
        final int x1 = leftPos + rowControlX();
        return mouseX >= x1 && mouseX < x1 + rowControlW();
    }

    /**
     * 打开「机器选择」子界面（可搜索）。<b>与自动合成监视器的「更换机器」复用同一个类</b>
     * {@link StepMachineSelectScreen}（用户明确要求不要写两套）。
     *
     * <p>两条入口共用本方法：①点击行内机器控件横带（◀ 机器名 ▶ 那一段）②Ctrl+左键点击该步骤整行。
     * 选择结果由子界面自己发 {@link SetStepMachinePacket}（服务端会校验「机器必须真实存在于网络中」），
     * 客户端不写任何权威状态。</p>
     */
    private void openMachineSelect(final int globalStep) {
        if (minecraft == null) {
            return;
        }
        final String recipeType = recipeTypeOf(globalStep);
        // 候选 = 该步配方类型下的全部执行仓（不受父界面搜索框过滤，见 chambersForType）
        final List<SyncChamberListPacket.Entry> candidates = chambersForType(recipeType);
        minecraft.setScreen(new StepMachineSelectScreen(this, globalStep, recipeType, candidates,
            isMachineAssigned(globalStep) ? machineNameOf(globalStep) : ""));
    }

    // <b>2026-10-05 用户要求删除：{@code openStepDetail} 与其子界面 {@code StepDetailConfigScreen}
    // 已整体移除</b>（原话：「按下 Ctrl 加左键单击打开的那个界面有什么实际意义？
    // 另一个不已经够了吗，又有搜索框又可以选择，所以把 Ctrl 加左键的那个界面删了」）。
    // 每一步的机器切换统一走左键点「机器控件横带」→ {@link StepMachineSelectScreen}（可搜索）。

    /**
     * 该步在 {@code direction}（-1 = ◀ / +1 = ▶）方向上的目标机器（两端环绕；没有候选 → {@code null}）。
     * <p>切换动作与 tooltip 的「将切换到 %s」共用本方法，因此提示里说的那台就是点下去会生效的那台。</p>
     */
    @org.jetbrains.annotations.Nullable
    private SyncChamberListPacket.Entry neighborMachine(final int globalStep, final int direction) {
        final List<SyncChamberListPacket.Entry> candidates = chambersFor(recipeTypeOf(globalStep));
        if (candidates.isEmpty()) {
            return null;
        }
        final int current = indexOfAssignedMachine(globalStep, candidates);
        final int next = current < 0
            ? (direction > 0 ? 0 : candidates.size() - 1)
            : Math.floorMod(current + direction, candidates.size());
        return candidates.get(next);
    }

    /** 该步当前指派的机器在候选里的下标（未指派 / 不在候选里 → {@code -1}，按「预览第一台」处理）。 */
    private int indexOfAssignedMachine(final int globalStep, final List<SyncChamberListPacket.Entry> candidates) {
        if (!isMachineAssigned(globalStep)) {
            return -1;
        }
        final String assigned = machineNameOf(globalStep);
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).name().equals(assigned)) {
                return i;
            }
        }
        return -1;
    }

    /** 该步候选机器里「当前显示的那一台」的序号（1 起；未指派 = 预览第一台 → 1）。 */
    private int machineOrdinal(final int globalStep, final List<SyncChamberListPacket.Entry> candidates) {
        final int index = indexOfAssignedMachine(globalStep, candidates);
        return index < 0 ? 1 : index + 1;
    }

    // ===== 槽位数据 =====

    private RegistryAccess registryAccess() {
        return minecraft != null && minecraft.level != null
            ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    /** 窗口行 i 上的单元样板物品。 */
    private ItemStack arrangementUnitAt(final int windowRow) {
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return ItemStack.EMPTY;
        }
        return menu.getSlot(SequencePatternTerminalMenu.SLOT_ARRANGEMENT_START + windowRow).getItem();
    }

    /** 全局步下标上的单元样板数据（无则 null）。 */
    private SequencePatternData.UnitData unitDataAt(final int globalStep) {
        if (globalStep < 0) {
            return null;
        }
        // 只在滚动窗口内的步（= 有槽位的行）可读到物品；窗口外的步不渲染，也就不需要数据
        final int windowRow = globalStep - menu.getArrangementOffset();
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return null;
        }
        final ItemStack unit = arrangementUnitAt(windowRow);
        return unit.isEmpty() ? null : SequencePatternData.readUnit(unit, registryAccess());
    }

    // ===== 多选（标签）输入原料：候选列表 + 循环显示 =====
    // 数据来源全部是「客户端本地按配方回查」，与配方导入时的代表物同源（不额外走网络包）：
    //   * 顶部「输入原料」= 序列装配配方的 ingredient（出厂就是标签，如「任意台阶」）；
    //   * 流程编排行 = 该步处理配方里 index ≥ 1 的 ingredient（index 0 被 Create 覆盖为过渡件）。
    // 候选一份由 GhostMarkerRenderer.candidateItems 收集、cycleCandidate 轮播 —— 顶部槽与流程行共用同一实现。

    /**
     * 顶部「输入原料」（总装配主原料）的全部候选。
     *
     * <h2>解析链（为什么必须回落）</h2>
     * <ol>
     *     <li><b>① 单元样板记录的序列装配配方 id</b>（{@code unit.recipe()}）：最快、最准；</li>
     *     <li><b>② 主产物槽</b>：在全部 {@code create:sequenced_assembly} 配方里找「产出该物」的那一条；</li>
     *     <li><b>③ 该步记录的输入物（代表物）</b>：找 ingredient 里包含它的那一条。</li>
     * </ol>
     * <p><b>为什么必须有 ② / ③</b>：展示数据（{@code DisplayArrangement}）随方块 NBT 持久化，
     * 只要某一步的单元样板里<b>没写（或写不出）配方 id</b>，只按 ① 查表就会失败，于是这一格
     * <b>静默退回单件代表物</b> —— 玩家看到的就是「只有一种石头台阶、不轮播」。有了回落，
     * 只要这条配方的 ingredient（或主产物）还认得出来，整组候选（石头 / 平滑石 / 安山岩台阶）
     * 就照常轮播。</p>
     */
    private List<ItemStack> assemblyInputCandidates() {
        final net.minecraft.world.level.Level level = clientLevel();
        if (level == null) {
            return List.of();
        }
        final SequencePatternData.UnitData unit = firstArrangementUnit();
        if (unit == null) {
            return List.of();
        }
        final String key = candidateCacheKey(unit);
        ensureCacheOwner(); // 换世界 ⇒ 整批作废（见 machineCache 的 javadoc）
        final List<ItemStack> cached = assemblyInputCache.get(key);
        if (cached != null) {
            return cached;
        }
        final List<ItemStack> candidates = resolveAssemblyMainCandidates(level, unit);
        // 同 stepInputCache：空结果不入缓存（首帧配方未就绪时算出的空表不能留整局）。
        if (!candidates.isEmpty()) {
            assemblyInputCache.put(key, candidates);
        }
        return candidates;
    }

    /** 当前可见编排列里第一份可用单元样板（优先「带配方 id」的那一份，其次第一份非空）。 */
    @Nullable
    private SequencePatternData.UnitData firstArrangementUnit() {
        SequencePatternData.UnitData first = null;
        // 只看<b>当前可见窗口</b>的行（客户端只有这些行的槽位内容；窗口外的步骤读不到单元样板）
        for (int row = 0; row < ARR_ROWS; row++) {
            final int global = globalStepOf(row);
            if (global < 0) {
                continue;
            }
            final SequencePatternData.UnitData unit = unitDataAt(global);
            if (unit == null) {
                continue;
            }
            if (unit.recipe() != null && !unit.recipe().isEmpty()) {
                return unit;
            }
            if (first == null) {
                first = unit;
            }
        }
        return first;
    }

    /** 展示数据的候选缓存键：配方 id 优先；没有 id 时用「配方类型 + 代表物注册名」兜底（仍然稳定）。 */
    private static String candidateCacheKey(final SequencePatternData.UnitData unit) {
        final String recipe = unit.recipe() == null ? "" : unit.recipe();
        if (!recipe.isEmpty()) {
            return recipe;
        }
        final ItemStack input = unit.input() == null ? ItemStack.EMPTY : unit.input();
        final String item = input.isEmpty() ? "-"
            : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(input.getItem()).toString();
        return (unit.recipeType() == null ? "" : unit.recipeType()) + "|" + item;
    }

    /** 主原料候选的解析链（① → ② → ③）；全部失败返回空表（调用方回退到槽内代表物）。 */
    private List<ItemStack> resolveAssemblyMainCandidates(final net.minecraft.world.level.Level level,
                                                          final SequencePatternData.UnitData unit) {
        final List<ItemStack> byId = candidatesOf(assemblyRecipeById(level, unit.recipe()));
        if (!byId.isEmpty()) {
            return byId;
        }
        final List<ItemStack> byProduct = candidatesOf(findAssemblyByProduct(level, firstResultStack()));
        if (!byProduct.isEmpty()) {
            return byProduct;
        }
        final ItemStack representative = unit.input() == null ? ItemStack.EMPTY : unit.input();
        final List<ItemStack> byInput = candidatesOf(findAssemblyByIngredient(level, representative));
        return byInput.isEmpty() ? List.of() : byInput;
    }

    /** 一条序列装配配方的主原料候选（标签 / 多值列表整组展开）；recipe 为 null 时返回空表。 */
    private static List<ItemStack> candidatesOf(@Nullable final SequencedAssemblyRecipe recipe) {
        return recipe == null ? List.of()
            : GhostMarkerRenderer.candidateItems(List.of(recipe.getIngredient()));
    }

    /**
     * 「优先复用网络中的中间产物」打开时，该配方对应的<b>过渡件</b>（= 中间产物，例如未完成的黑曜石板）；
     * 开关为关 / 解析不到配方时返回<b>空栈</b>（调用方走老路径）。
     *
     * <h2>为什么这里必须改（用户实测的「误导」）</h2>
     * <p>顶部「输入原料」那一格显示的是总装配的<b>起步原料</b>（{@link #assemblyInputCandidates()}，
     * 例如 {@code create:powdered_obsidian} 黑曜石粉）。而开关打开后，新的一件是从<b>过渡件</b>起步的
     * （执行仓优先把网络里已有的中间产物取来当起点，跳过前面几步），此时再把起步原料摆在那一格就是错的：
     * 玩家会以为还缺黑曜石粉。因此开关打开时这一格改画过渡件，标题也随之换成「中间产物」。</p>
     *
     * <p><b>开关为关时逐字保持现状</b>：本方法第一句就返回空栈，渲染 / tooltip / 标题全部走老路径
     * （起步原料 + 候选轮播），一个像素都不变。</p>
     *
     * <p>过渡件来自 Create 配方自己的 {@code getTransitionalItem()}（与执行仓、缺料提示同一口径），
     * 因此「哪些是中间产物」全工程只有一个答案，不靠物品名猜。配方按与主原料候选<b>同一条解析链</b>
     * 取（① 单元样板记的配方 id → ② 主产物 → ③ 主原料），老样板 / 重新生成的样板都能解析出来；
     * 真的解析不到就退回老显示，绝不显示一个空槽。</p>
     */
    private ItemStack reuseIntermediate() {
        if (!menu.isReuseIntermediates()) {
            return ItemStack.EMPTY;
        }
        final net.minecraft.world.level.Level level = clientLevel();
        final SequencePatternData.UnitData unit = firstArrangementUnit();
        if (level == null || unit == null) {
            return ItemStack.EMPTY;
        }
        final String key = candidateCacheKey(unit);
        ensureCacheOwner(); // 换世界 ⇒ 整批作废（见 machineCache 的 javadoc）
        final ItemStack cached = reuseIntermediateCache.get(key);
        if (cached != null) {
            return cached;
        }
        final SequencedAssemblyRecipe recipe = resolveAssemblyRecipe(level, unit);
        final ItemStack transitional = recipe == null ? ItemStack.EMPTY : recipe.getTransitionalItem();
        final ItemStack result = transitional == null ? ItemStack.EMPTY : transitional.copyWithCount(1);
        if (!result.isEmpty()) {
            // 空结果不入缓存：首帧配方未就绪时算出的「没有」不能留整局（与上面两份候选缓存同一口径）
            reuseIntermediateCache.put(key, result);
        }
        return result;
    }

    /** 与 {@link #resolveAssemblyMainCandidates} 同一条解析链，但返回<b>配方本体</b>（找不到 = null）。 */
    @Nullable
    private SequencedAssemblyRecipe resolveAssemblyRecipe(final net.minecraft.world.level.Level level,
                                                          final SequencePatternData.UnitData unit) {
        final SequencedAssemblyRecipe byId = assemblyRecipeById(level, unit.recipe());
        if (byId != null) {
            return byId;
        }
        final SequencedAssemblyRecipe byProduct = findAssemblyByProduct(level, firstResultStack());
        if (byProduct != null) {
            return byProduct;
        }
        final ItemStack representative = unit.input() == null ? ItemStack.EMPTY : unit.input();
        return findAssemblyByIngredient(level, representative);
    }

    /** 按配方 id 取序列装配配方（id 为空 / 查不到 / 不是序列装配配方 ⇒ null，绝不抛）。 */
    @Nullable
    private static SequencedAssemblyRecipe assemblyRecipeById(final net.minecraft.world.level.Level level,
                                                              final String recipeId) {
        if (level == null || recipeId == null || recipeId.isEmpty()) {
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) {
            return null;
        }
        try {
            final Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(id);
            return holder.isPresent() && holder.get().value() instanceof SequencedAssemblyRecipe assembly
                ? assembly : null;
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    /** 在全部序列装配配方里找「产出该物」的那一条（产物槽为空 ⇒ null）。 */
    @Nullable
    private static SequencedAssemblyRecipe findAssemblyByProduct(final net.minecraft.world.level.Level level,
                                                                 final ItemStack product) {
        if (level == null || product.isEmpty()) {
            return null;
        }
        try {
            for (final RecipeHolder<?> holder : level.getRecipeManager().getAllRecipesFor(
                com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())) {
                if (!(holder.value() instanceof SequencedAssemblyRecipe assembly)) {
                    continue;
                }
                for (final com.simibubi.create.content.processing.recipe.ProcessingOutput output
                    : assembly.resultPool) {
                    if (!output.getStack().isEmpty()
                        && ItemStack.isSameItemSameComponents(output.getStack(), product)) {
                        return assembly;
                    }
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方系统未就绪：按「找不到」处理
        }
        return null;
    }

    /** 在全部序列装配配方里找「主原料包含该物」的那一条（代表物为空 ⇒ null）。 */
    @Nullable
    private static SequencedAssemblyRecipe findAssemblyByIngredient(final net.minecraft.world.level.Level level,
                                                                    final ItemStack representative) {
        if (level == null || representative.isEmpty()) {
            return null;
        }
        try {
            for (final RecipeHolder<?> holder : level.getRecipeManager().getAllRecipesFor(
                com.simibubi.create.AllRecipeTypes.SEQUENCED_ASSEMBLY.getType())) {
                if (holder.value() instanceof SequencedAssemblyRecipe assembly
                    && assembly.getIngredient().test(representative)) {
                    return assembly;
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方系统未就绪：按「找不到」处理
        }
        return null;
    }

    /** 主产物槽里的那一件（产物区第一格；空则空栈）。 */
    private ItemStack firstResultStack() {
        final Slot slot = menu.getSlot(SequencePatternTerminalMenu.SLOT_RESULT_START);
        return slot == null ? ItemStack.EMPTY : slot.getItem();
    }

    /**
     * 该步输入原料的全部候选（标签型 ingredient 展开成多件；&gt; 1 表示这一格是「多选」）。
     * <p>跳过 index 0（Create 用过渡件覆盖了它），与导入时 {@code stepInput} 的口径一致。</p>
     * <p><b>回落</b>：单元样板没记（或记错）序列装配配方 id 时，按「配方类型 + 该步记录的输入物」
     * 在配方表里找那条真正消耗它的处理配方（{@link #fallbackStepRecipe}）—— 找不到才退回单件代表物，
     * 因此「重新生成 / 旧档」都不会再把整组候选静默压成一件。</p>
     */
    private List<ItemStack> stepInputCandidates(final int globalStep) {
        final int windowRow = globalStep - menu.getArrangementOffset();
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return List.of();
        }
        final ItemStack unitStack = arrangementUnitAt(windowRow);
        if (unitStack.isEmpty()) {
            return List.of();
        }
        final SequencePatternData.UnitData unit = unitDataAt(globalStep);
        if (unit == null) {
            return List.of();
        }
        final net.minecraft.world.level.Level level = clientLevel();
        final String key = candidateCacheKey(unit) + "#" + unit.step();
        ensureCacheOwner(); // 换世界 ⇒ 整批作废（见 machineCache 的 javadoc）
        final List<ItemStack> cached = stepInputCache.get(key);
        if (cached != null) {
            return fromCache(cached, unit);
        }
        com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe =
            SequencePatternTerminalBlockEntity.stepRecipe(level, unit);
        if (recipe == null) {
            recipe = fallbackStepRecipe(level, unit);
        }
        List<ItemStack> candidates = List.of();
        if (recipe != null) {
            final NonNullList<Ingredient> all = recipe.getIngredients();
            final List<Ingredient> inputs = new ArrayList<>(Math.max(0, all.size() - 1));
            for (int i = 1; i < all.size(); i++) {
                inputs.add(all.get(i));
            }
            candidates = GhostMarkerRenderer.candidateItems(inputs);
        }
        // **空结果绝不入缓存**（2026-10-05）：配方在首帧可能还没就绪（数据包重载 / 刚进世界），
        // 若把那一刻的空表缓存下来，整局都会只显示代表物 —— 用户看到的就是「怎么都不轮换」。
        // 非空才缓存，空结果每次重算（成本是一次配方回查，可忽略）。
        if (!candidates.isEmpty()) {
            stepInputCache.put(key, candidates);
        }
        return fromCache(candidates, unit);
    }

    /**
     * 缓存 / 现场解析结果的统一回落：候选为空时先用<b>单元样板自己记的候选组</b>
     * （{@code SequencePatternData#TAG_INPUT_CANDIDATES}，列车轨道「铁粒 或 锌粒」就是这么落盘的），
     * 仍为空才退回单件代表物。
     * <p>为什么需要这一级（列车轨道：铁粒/锌粒候选未显示）：配方回查是「按当前配方表现场展开」，
     * 一旦配方查不到（旧样板 / 数据包差异 / 配方类型未注册）就会整组丢失；而单元样板里落盘的候选组
     * 与配方无关，永远读得出来 —— 因此它是回查失败时最可靠的一手数据。</p>
     */
    private static List<ItemStack> fromCache(final List<ItemStack> resolved,
                                             final SequencePatternData.UnitData unit) {
        if (resolved != null && !resolved.isEmpty()) {
            return resolved;
        }
        final List<ItemStack> declared = unit == null ? List.of() : unit.candidatesOrRepresentative();
        return declared.isEmpty() ? singleInput(unit) : declared;
    }

    /**
     * 回落解析该步的处理配方，两级：
     * <ol>
     *     <li>按单元样板的 <b>配方类型 id</b> 在注册的该类型配方里找第一条「下标 ≥ 1 的 ingredient
     *     真的能取到这件物品」的配方（与执行仓「按配方类型 + 输入物找候选配方」同一口径）；</li>
     *     <li>配方类型也没记（更旧的单元）时：<b>主产物槽</b> → 找到这条序列装配配方 →
     *     在它的 sequence 里找同判据的那一步。这让「装粒（铁粒或锌粒）」这类步骤在旧数据上也能整组展开。</li>
     * </ol>
     * <p>只读；找不到返回 null（调用方回退单件代表物）。</p>
     */
    @Nullable
    private com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> fallbackStepRecipe(
        final net.minecraft.world.level.Level level, final SequencePatternData.UnitData unit) {
        if (level == null) {
            return null;
        }
        final ItemStack wanted = unit.input() == null ? ItemStack.EMPTY : unit.input();
        if (wanted.isEmpty()) {
            return null;
        }
        final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> byType =
            stepRecipeByType(level, unit.recipeType(), wanted);
        if (byType != null) {
            return byType;
        }
        return stepRecipeByProduct(level, wanted);
    }

    /** ① 按配方类型 id 找处理配方（类型 id 为空 / 未注册 ⇒ null）。
     *  <p><b>多条命中时取「候选组更宽」的那一条</b>（列车轨道：铁粒/锌粒候选未显示）：
     *  同一个物品可能同时是多条同类型配方的应用物 —— 例如铁粒既是列车轨道装铁粒步的候选之一
     *  （{@code [c:nuggets/iron, c:nuggets/zinc]}），又是精密构件装铁粒步的唯一应用物（{@code [c:nuggets/iron]}）。
     *  旧实现返回<b>第一条</b>命中的配方，因此铁粒一撞上「只要铁粒」的那条，界面上就永远只剩铁粒。
     *  只认<b>唯一命中</b>时保持原样；多条命中时必须偏向「候选更宽」的那条（宽者必然包含窄者的语义）。</p> */
    @Nullable
    private static com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> stepRecipeByType(
        final net.minecraft.world.level.Level level, final String recipeType, final ItemStack wanted) {
        final ResourceLocation typeId = ResourceLocation.tryParse(recipeType == null ? "" : recipeType);
        if (typeId == null) {
            return null;
        }
        final net.minecraft.world.item.crafting.RecipeType<?> type =
            net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.get(typeId);
        if (type == null) {
            return null;
        }
        com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> best = null;
        int bestWidth = -1;
        try {
            final Iterable<?> holders = level.getRecipeManager()
                .getAllRecipesFor((net.minecraft.world.item.crafting.RecipeType) type);
            for (final Object value : holders) {
                if (!(value instanceof RecipeHolder<?> holder)
                    || !(holder.value()
                    instanceof com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe)) {
                    continue;
                }
                if (!consumesAsApplication(recipe, wanted)) {
                    continue;
                }
                final int width = applicationCandidateWidth(recipe, wanted);
                if (best == null || width > bestWidth) {
                    best = recipe;
                    bestWidth = width;
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方类型未注册 / 版本差异：按「找不到」处理
        }
        return best;
    }

    /** 该配方里「包含 {@code wanted} 的那个应用物 ingredient」的候选数（判不出时返回 0，不参与择优）。 */
    private static int applicationCandidateWidth(
        final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe,
        final ItemStack wanted) {
        if (recipe == null || wanted == null || wanted.isEmpty()) {
            return 0;
        }
        int width = 0;
        final NonNullList<Ingredient> all = recipe.getIngredients();
        for (int i = 1; i < all.size(); i++) {
            int matched = 0;
            boolean consumed = false;
            for (final ItemStack candidate : all.get(i).getItems()) {
                if (candidate.isEmpty()) {
                    continue;
                }
                matched++;
                if (candidate.is(wanted.getItem())) {
                    consumed = true;
                }
            }
            if (consumed) {
                width = Math.max(width, matched);
            }
        }
        return width;
    }

    /** ② 经主产物与该步输入物，在这条序列装配配方的 sequence 里找那一步。 */
    @Nullable
    private com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> stepRecipeByProduct(
        final net.minecraft.world.level.Level level, final ItemStack wanted) {
        final SequencedAssemblyRecipe assembly = findAssemblyByProduct(level, firstResultStack());
        if (assembly == null) {
            return null;
        }
        try {
            for (final com.simibubi.create.content.processing.sequenced.SequencedRecipe<?> step
                : assembly.getSequence()) {
                final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe = step.getRecipe();
                if (consumesAsApplication(recipe, wanted)) {
                    return recipe;
                }
            }
        } catch (final RuntimeException ignored) {
            // 配方结构异常：按「找不到」处理
        }
        return null;
    }

    /** 该处理配方是否把 {@code wanted} 当作「应用物」（下标 ≥ 1 的 ingredient 能取到它）。 */
    private static boolean consumesAsApplication(
        final com.simibubi.create.content.processing.recipe.ProcessingRecipe<?, ?> recipe,
        final ItemStack wanted) {
        if (recipe == null) {
            return false;
        }
        final NonNullList<Ingredient> all = recipe.getIngredients();
        for (int i = 1; i < all.size(); i++) {
            for (final ItemStack candidate : all.get(i).getItems()) {
                if (!candidate.isEmpty() && candidate.is(wanted.getItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 回退：样板自带的代表物（配方解析不出来时至少显示它）。 */
    private static List<ItemStack> singleInput(final SequencePatternData.UnitData unit) {
        final ItemStack input = unit == null ? ItemStack.EMPTY : unit.input();
        return input == null || input.isEmpty() ? List.of() : List.of(input);
    }

    /** 按像素宽截断字符串（尾部补省略号）。 */
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

    /** 以 0.5 缩放绘制“小字”；maxFontWidth <= 0 表示不做宽度截断。 */
    private void drawSmallText(final GuiGraphics guiGraphics,
                               final Component text,
                               final float x,
                               final float y,
                               final int color,
                               final int maxFontWidth) {
        final Component shown = maxFontWidth > 0
            ? Component.literal(trimToWidth(font, text.getString(), maxFontWidth))
            : text;
        final var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.scale(0.5F, 0.5F, 1.0F);
        guiGraphics.drawString(font, shown, 0, 0, color, false);
        pose.popPose();
    }

    /** 配方类型显示名：统一走 {@link RecipeTypeNames}。 */
    private static String recipeTypeName(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return I18n.get(LANG + "empty_unit");
        }
        return RecipeTypeNames.of(recipeType);
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, BG_W, BG_H);
        // 流程编排卡片：spt_card_bg.png 的 9-slice 三态衬底（默认 / 悬停 / 选中）
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        final int hoveredRow = rowAt(mouseX, mouseY);
        for (int i = 0; i < ARR_ROWS; i++) {
            final int global = globalStepOf(i);
            if (global < 0 || !rowMatches(global)) {
                continue; // 窗口外的行 / 被搜索过滤的行不绘制卡片
            }
            final int state = global == selectedRow ? 2 : (i == hoveredRow ? 1 : 0);
            SptGuiTextures.blitNineSlice(guiGraphics, SptGuiTextures.CARD,
                leftPos + CARD_X, topPos + cardTop(i), CARD_W, CARD_H,
                0, state * SptGuiTextures.CARD_SRC, SptGuiTextures.CARD_SRC, SptGuiTextures.CARD_SRC,
                1, SptGuiTextures.CARD_SRC, SptGuiTextures.CARD_SRC * SptGuiTextures.CARD_STATES);
        }
        // 全宽流程编排面板：面板内部全为底色，行槽框 + 卡片全部由本类在 renderBg / renderSlot 自绘
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // 样板输入槽（3 格）的槽框：背景里没有烘焙这三格（删除导入按钮后腾出的位置），
        // 因此用 Java 逐像素画 MC 原版观感的 18×18 槽框（复用 McGui.slotFrame，不新增任何贴图）。
        for (int i = 0; i < SequencePatternTerminalBlockEntity.RS_PATTERN_SLOT_SIZE; i++) {
            McGui.slotFrame(guiGraphics,
                leftPos + SequencePatternTerminalMenu.RS_PATTERN_SLOT_X + i * 18 - 1,
                topPos + SequencePatternTerminalMenu.RS_PATTERN_SLOT_Y - 1);
        }
    }

    /**
     * 流程编排行：行槽框由本类自绘（背景未烘焙），格内显示该步输入物预览。
     * <p>唯一那格总样板槽（底部左侧）与单元样板槽 6 格的槽框<b>已烘焙在背景里</b>，因此这里不再自绘。</p>
     */
    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final Slot slot) {
        final int idx = slot.index;
        if (idx >= SequencePatternTerminalMenu.SLOT_ARRANGEMENT_START
            && idx < SequencePatternTerminalMenu.SLOT_ARRANGEMENT_END) {
            renderArrangementRow(guiGraphics, idx - SequencePatternTerminalMenu.SLOT_ARRANGEMENT_START);
            return;
        }
        if (idx == SequencePatternTerminalMenu.SLOT_INPUT) {
            renderInputSlot(guiGraphics, slot);
            return;
        }
        super.renderSlot(guiGraphics, slot);
    }

    /**
     * 顶部「输入原料」格的绘制：<b>多选（标签）时循环显示全部候选</b>
     * （与流程编排行共用 {@link GhostMarkerRenderer#cycleCandidate}，因此两处轮播完全同步）。
     * <p>单选 / 无候选时走原版渲染（与改动前一模一样），槽内真实内容一个字节都不动 ——
     * 这里只改「画什么」，不参与任何写入。</p>
     */
    private void renderInputSlot(final GuiGraphics guiGraphics, final Slot slot) {
        // 「优先复用中间产物」打开时这一格画的是<b>过渡件</b>（中间产物）：它不在槽里（槽里存的仍是
        // 起步原料，一个字节都不动），因此必须显式画出来。开关为关时 reuseIntermediate() = 空栈，
        // 直接落到下面的老路径（与改动前一模一样）。
        final ItemStack intermediate = reuseIntermediate();
        if (!intermediate.isEmpty()) {
            guiGraphics.renderItem(intermediate, slot.x, slot.y);
            return;
        }
        final List<ItemStack> candidates = assemblyInputCandidates();
        if (candidates.size() > 1) {
            guiGraphics.renderItem(GhostMarkerRenderer.cycleCandidate(candidates), slot.x, slot.y);
            return;
        }
        super.renderSlot(guiGraphics, slot);
    }

    /** 自绘一行：槽框 + 步骤序号 + 输入物 / 输入流体标记（绘制范围与 {@link #rowAt} 一致）。 */
    private void renderArrangementRow(final GuiGraphics guiGraphics, final int windowRow) {
        final int x = SequencePatternTerminalMenu.ARR_SLOT_X;
        final int y = SequencePatternTerminalMenu.ARR_SLOT_Y + windowRow * 18;
        // 槽框必须画在 (x-1, y-1)：17×17 槽框素材的原点 = 槽位坐标 -1（与原版槽位框、悬停高亮同源），
        // 否则物品图标 / 悬停高亮都会与槽框错开 1px（用户实测「偏差」的根因）。
        guiGraphics.blit(TEXTURE, x - 1, y - 1, SLOT_SRC_U, SLOT_SRC_V, SLOT_SRC, SLOT_SRC, BG_W, BG_H);
        final int global = globalStepOf(windowRow);
        if (global < 0) {
            return;
        }
        drawSmallText(guiGraphics, Component.literal(Integer.toString(global + 1)), x + 1, y + 1,
            0xFFFFD24A, -1);
        if (!rowMatches(global)) {
            return;
        }
        // 输入标记：优先显示流体（注液器一类步骤的输入是流体），否则显示该步的物品输入
        final net.neoforged.neoforge.fluids.FluidStack fluid = stepFluidAt(global);
        if (!fluid.isEmpty()) {
            cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer
                .renderFluid(guiGraphics, x, y, fluid);
            return;
        }
        final SequencePatternData.UnitData unit = unitDataAt(global);
        if (unit != null) {
            // 标签 / 多选输入（例如「任意台阶」「铁粒或锌粒」）：循环显示全部候选 —— 与顶部输入原料槽
            // 共用 GhostMarkerRenderer.candidateItems + cycleCandidate（同一份候选、同一套轮播相位）。
            // 这里<b>只认候选列表</b>（它的最后一级回落就是 unit.input()）：因此「配方解析得到、但单元
            // 样板没记输入物」的步骤也能照常显示输入图标，不会误画成空槽斜杠。
            final List<ItemStack> candidates = stepInputCandidates(global);
            if (candidates.size() > 1) {
                guiGraphics.renderItem(GhostMarkerRenderer.cycleCandidate(candidates), x, y);
                return;
            }
            if (!candidates.isEmpty()) {
                guiGraphics.renderItem(candidates.get(0), x, y);
                return;
            }
        }
        // 空槽位标识（用户要求）：该步（或这一行）没有物品 / 流体输入时，画一道斜杠明确表示
        // 「这里就是没有东西」，而不是「界面没加载出来」。有输入时（上面的两条分支）绝不绘制。
        cretae.cookiewyq.rs_create_compat.client.widget.McGui.emptySlotMarker(guiGraphics, x, y);
    }

    /** 该步标记的输入流体（存在该步单元样板的数据里；无标记返回空栈）。 */
    private net.neoforged.neoforge.fluids.FluidStack stepFluidAt(final int globalStep) {
        final int windowRow = globalStep - menu.getArrangementOffset();
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        final ItemStack unit = arrangementUnitAt(windowRow);
        if (unit.isEmpty()) {
            return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
        }
        return SequencePatternData.readUnitFluid(unit, registryAccess());
    }

    /**
     * 自绘行槽框的**取样图块**在背景里的左上角（精灵坐标）。
     * <p>取样自「输入原料槽框」（精灵 (183,32) = Menu (184,33)）：它是本背景中<b>唯一</b>被 Java
     * 依赖的烘焙槽框，且始终可见（输入原料槽永远渲染），因此删掉单元库 / 装配槽 / 输出槽槽框后，
     * 行槽框仍能取到正确图块（见 {@code SPT_GUI_TECH_DOC_V4.md} §0/§2.1）。</p>
     */
    private static final int SLOT_SRC_U = 183;
    private static final int SLOT_SRC_V = 32;
    private static final int SLOT_SRC = 17;

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 面板标题（全部无阴影）
        // 顶栏标题：左对齐、独占左侧（搜索框已右移，二者不再重叠）
        guiGraphics.drawString(font, title, TITLE_X, TITLE_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "arrangement"), ARR_TITLE_X, ARR_TITLE_Y,
            COLOR_TITLE, false);
        // 输入原料标签（唯一操作口）：标题必须跟着格子里的东西走 —— 「优先复用中间产物」打开时
        // 这一格画的是过渡件（中间产物），标签就写「中间产物」；开关为关时仍是「输入原料」（逐字不变）。
        // 两种文案都是 4 个汉字宽，因此标签几何 / 不压边框的约束一字未改。
        guiGraphics.drawString(font, Component.translatable(LANG
            + (reuseIntermediate().isEmpty() ? "op.input" : "op.intermediate")),
            INPUT_LABEL_X, OP_LABEL_Y, COLOR_LABEL, false);
        // 产物 / 废料小标题
        guiGraphics.drawString(font, Component.translatable(LANG + "results.short"), RESULT_TITLE_X,
            RESULT_TITLE_Y, COLOR_LABEL, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "scraps"), SCRAP_TITLE_X,
            SCRAP_TITLE_Y, COLOR_LABEL, false);
        // 物品栏标签
        guiGraphics.drawString(font, Component.translatable(LANG + "inventory"), INV_LABEL_X, INV_LABEL_Y,
            COLOR_LABEL, false);
        // 循环次数（Java 渲染、无阴影、<b>只读</b>）：值来自配方（服务端 ContainerData），
        // 客户端不提供任何编辑入口 —— 用户要求「循环次数是固定值，不要让用户修改」。
        guiGraphics.drawString(font, Component.translatable(LANG + "loops", loopsValue()),
            LOOP_LABEL_X, LOOP_LABEL_Y, COLOR_LABEL, false);

        renderPatternRowLabels(guiGraphics);
        renderRowTexts(guiGraphics);
    }

    /**
     * 底部样板区的两组标题（<b>一律正常字号、无阴影</b>）。
     * <p><b>为什么不再用 0.5 缩放</b>：用户嫌「总样板 / 单元样板 / 第几页」太小。v10 排布为
     * 「左侧 1 格总样板槽 + 中间一条小滚动条 + 右侧 6 格单元样板窗口」，两个标签落在整排的两端，
     * 正常字号放得下；只有右侧文案需要缩短（见 {@link #UNIT_TITLE_X}）。
     * 页码不单独成文本 —— 它由滚动条位置 + tooltip（键 {@code unit_page}）表达，
     * 保持断开区干净（见 {@code SPT_GUI_TECH_DOC_V7.md} §3.6）。</p>
     */
    private void renderPatternRowLabels(final GuiGraphics guiGraphics) {
        guiGraphics.drawString(font, Component.translatable(LANG + "pattern_slots"), PATTERN_TITLE_X,
            PATTERN_LABEL_Y, COLOR_LABEL, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "unit_pattern_slots.short"), UNIT_TITLE_X,
            PATTERN_LABEL_Y, COLOR_LABEL, false);
    }

    // ===== 单元样板窗口：翻页滚动条（v10；自绘控件 → tooltip 也必须手动渲染） =====

    /** 滚动条 hover 判定（与绘制范围同源：Menu (85,213) 7×16）。 */
    private boolean unitPageScrollHovered(final double mouseX, final double mouseY) {
        return isHoveringRect(UNIT_SCROLL_X, UNIT_SCROLL_Y, UNIT_SCROLL_W, UNIT_SCROLL_H, mouseX, mouseY);
    }

    /** 单元样板槽一组的几何范围（含 17×17 槽框外扩 1px）：越界格没有 hoveredSlot，用它补「不可用」提示。 */
    private boolean unitSlotRowHovered(final double mouseX, final double mouseY) {
        return isHoveringRect(SequencePatternTerminalMenu.UNIT_SLOT_X - 1,
            SequencePatternTerminalMenu.UNIT_SLOT_Y - 1,
            (SequencePatternTerminalMenu.UNIT_WINDOW - 1) * 18 + 17, 17, mouseX, mouseY);
    }

    /** 卡片两行文字：第 1 行操作名 + 右侧 ×N，第 2 行机器选择控件（◀ 机器名 ▶）。 */
    private void renderRowTexts(final GuiGraphics guiGraphics) {
        for (int i = 0; i < ARR_ROWS; i++) {
            final int global = globalStepOf(i);
            if (global < 0 || !rowMatches(global)) {
                continue;
            }
            // 卡片 54×18（含 1px 边框）：两行各占 8px，取 +1 / +9 使两行都不压上下边框
            final int lineY1 = cardTop(i) + 1;
            final int lineY2 = rowControlY(i);
            // 第 1 行：该步用到的<b>机器</b>（按配方类型反查机器方块，例：create:deploying → 机械手），
            // 而不是配方类型名，更不是产物（用户明确要求）。
            final SequencePatternData.UnitData unit = unitDataAt(global);
            final String op = unit == null ? I18n.get(LANG + "empty_unit")
                : machineKindName(recipeTypeOf(global));
            // 第 1 行右侧：×N 次数（先算宽度，操作名可用宽度随之收缩，两段文字不重叠）；
            // 最右端再放 20px 的「跳过重复」行内开关（见 renderStepSkipToggle）。
            final int count = Math.max(1, menu.getArrangementCount(i));
            final Component countText = Component.literal("×" + count);
            final int countW = font.width(countText);
            final int countRight = rowCountRight();
            final int opMaxW = Math.min(ROW_OP_W, countRight - ROW_TEXT_X - countW - 2);
            guiGraphics.drawString(font, Component.literal(trimToWidth(font, op, Math.max(4, opMaxW))),
                ROW_TEXT_X, lineY1, COLOR_ROW_OP, false);
            guiGraphics.drawString(font, countText, countRight - countW, lineY1,
                COLOR_ROW_COUNT, false);
            // 行内「跳过重复样板」开关：文字显示该步的机器 / 样板是否已就位（链级），底色是本步开关，
            // 点击即切换本步的生成语义（判重口径与「就位」是两件事，见 renderStepSkipToggle 的 javadoc）
            renderStepSkipToggle(guiGraphics, i, global);
            // 第 2 行：机器方块图标 + 机器选择控件（◀ 机器名 ▶）
            if (hasNoMachine(global)) {
                guiGraphics.drawString(font, Component.translatable(LANG + "no_machine"), ROW_TEXT_X, lineY2,
                    COLOR_NO_MACHINE, false);
                continue;
            }
            final SyncStepMachinesPacket.Entry entry = SyncStepMachinesPacket.stepAt(global);
            final boolean assigned = entry != null && entry.hasPos() && !entry.machineName().isEmpty();
            final String machine = machineNameOf(global);
            final int color = assigned ? COLOR_ROW_MACHINE : COLOR_ROW_MACHINE_PREVIEW;
            // 机器方块图标：卡片上直接可见，不必打开执行舱 / 详细配置才能看到是哪台机器
            drawScaledItem(guiGraphics, machineIconFor(recipeTypeOf(global)), ROW_TEXT_X, lineY2);
            final int textX = rowControlX();
            final int textW = rowControlW();
            // ◀ / ▶ 两个字形就是「切换执行仓」的按钮（可点：见 mouseClicked → switchStepMachine）；
            // 位置与命中判定共用 rowControlX/rowControlW/rowControlY（绘制与命中同源，不会错开）。
            // 没有候选机器时字形转灰（不可点，与命中判定同一条件 hasNoMachine/chambersFor）
            final int arrowColor = chambersFor(recipeTypeOf(global)).isEmpty() ? COLOR_PAGE_GLYPH_OFF : color;
            guiGraphics.drawString(font, Component.literal("◀"), textX, lineY2, arrowColor, false);
            guiGraphics.drawString(font, Component.literal("▶"),
                textX + textW - font.width("▶"), lineY2, arrowColor, false);
            final int innerW = textW - 2 * (font.width("◀") + 1);
            guiGraphics.drawString(font, Component.literal(trimToWidth(font, machine, innerW)),
                textX + font.width("◀") + 1, lineY2, color, false);
        }
    }

    /**
     * 行内「跳过重复样板」小开关（手搓结构色，不新增任何 GUI 贴图）。
     * <ul>
     *     <li><b>文字</b> = 该步的机器 / 样板是否已就位（「已有」/「没有」，措辞来自语言文件）。
     *     判据是 {@link #stepUnitEquipped}（链级「这一台机器备好这一步的样板了吗」），
     *     <b>不是</b>「是否已有完全相同的样板」—— 后者只是 tooltip 里单列的一行事实；</li>
     *     <li><b>底色</b> = 该步的开关状态（琥珀 = 生成时跳过重复；深灰 = 照常生成）；
     *     开关真值只读 {@link SyncStepMachinesPacket}，点击切换走 {@link SetStepSkipDuplicatePacket}。</li>
     * </ul>
     * <p><b>绘制范围与 hover / 点击判定同源</b>：三者共用 {@link #rowSkipX()} / {@link #rowSkipY} /
     * {@link #ROW_SKIP_W} / {@link #ROW_SKIP_H}，且都用左闭右开区间，因此不会出现「画在这里却点不到」。
     * 开关落在卡片第 1 行的最右端（Menu x {@link #rowSkipX()}..{@code +ROW_SKIP_W}），与第 2 行的
     * 机器控件横带在 y 上完全不相交，也不会压到左侧的 ×N 次数（×N 已左移到 {@link #rowCountRight()}）。</p>
     */
    private void renderStepSkipToggle(final GuiGraphics guiGraphics, final int windowRow,
                                      final int globalStep) {
        final boolean equipped = stepUnitEquipped(globalStep);
        final boolean skip = stepSkipDuplicateOf(globalStep);
        final int x = rowSkipX();
        final int lineY = rowSkipY(windowRow);
        final int bg = skip ? COLOR_SKIP_BG_ON : COLOR_SKIP_BG_OFF;
        guiGraphics.fill(x, lineY, x + ROW_SKIP_W, lineY + ROW_SKIP_H, bg);
        final Component text = Component.translatable(
            LANG + (equipped ? "card.skip.has" : "card.skip.missing"));
        guiGraphics.drawString(font, text, x + (ROW_SKIP_W - font.width(text)) / 2, lineY,
            equipped ? COLOR_SKIP_TEXT_HAS : COLOR_SKIP_TEXT_NONE, false);
    }

    /**
     * 行内开关的提示（自绘控件 → 必须手动渲染）：<b>两件不同的事实</b>分开列 + 当前开关状态 + 点击说明。
     * <p>第 2 行是{@link #stepUnitEquipped(int) 行内那两个字}的含义（机器 / 样板是否已就位），
     * 第 3 行是判重口径的事实（同配方同步序的完全相同样板是否已存在，决定「生成时跳过」会不会生效）——
     * 两者此前被混成一句，于是「冲压机上有冲压样板」被显示成「没有」。</p>
     * <p>与本模组其它附加信息一样走 {@link RsccTooltipLayers#renderAttached}（默认收起、按住 Shift 展开）。</p>
     */
    private List<Component> stepSkipToggleTooltipLines(final int globalStep) {
        final boolean equipped = stepUnitEquipped(globalStep);
        final boolean exists = stepDuplicateExists(globalStep);
        final boolean skip = stepSkipDuplicateOf(globalStep);
        final List<Component> lines = new ArrayList<>(5);
        lines.add(Component.translatable(LANG + "card.skip.tip"));
        lines.add(Component.translatable(LANG + "card.skip.equipped",
            Component.translatable(LANG + (equipped ? "card.skip.has" : "card.skip.missing"))));
        lines.add(Component.translatable(LANG + "card.skip.exists",
            Component.translatable(LANG + (exists ? "card.skip.has" : "card.skip.missing"))));
        lines.add(Component.translatable(LANG + "card.skip.state",
            Component.translatable(LANG + (skip ? "card.skip.on" : "card.skip.off"))));
        lines.add(Component.translatable(LANG + "card.skip.help"));
        return lines;
    }

    /**
     * 配方类型 → 「能执行它的机器」候选（客户端按配方类型懒加载并缓存）。
     * <p>反查走 {@link RecipeTypeMachines}（Create 官方 JEI 催化剂 + 配方自带的 toast symbol）；
     * 查不到时返回空列表（调用方回退显示配方类型名，绝不退化成显示原料 / 产物）。</p>
     */
    private java.util.List<RecipeTypeMachines.Machine> machinesFor(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return java.util.List.of();
        }
        ensureCacheOwner(); // 换世界 ⇒ 整批作废（见 machineCache 的 javadoc）
        return machineCache.computeIfAbsent(recipeType, RecipeTypeMachines::forRecipeType);
    }

    /** 该步「用到的机器」显示名（配方类型 → 机器方块；查不到回退配方类型名）。 */
    private String machineKindName(final String recipeType) {
        final java.util.List<RecipeTypeMachines.Machine> machines = machinesFor(recipeType);
        if (!machines.isEmpty()) {
            return machines.get(0).name().getString();
        }
        return recipeTypeName(recipeType);
    }

    /**
     * 配方类型 → 机器方块图标（取该配方类型下第一台机器）；查不到时返回空栈（不绘制图标）。
     */
    private ItemStack machineIconFor(final String recipeType) {
        final java.util.List<RecipeTypeMachines.Machine> machines = machinesFor(recipeType);
        return machines.isEmpty() ? ItemStack.EMPTY : machines.get(0).icon();
    }

    /** 画一个 0.5 缩放的方块图标（16×16 → 8×8）；空栈时跳过。 */
    private void drawScaledItem(final GuiGraphics guiGraphics, final ItemStack stack,
                                final int x, final int y) {
        if (stack.isEmpty()) {
            return;
        }
        final var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.scale(0.5F, 0.5F, 1.0F);
        guiGraphics.renderItem(stack, 0, 0);
        pose.popPose();
    }

    /**
     * 产物 / 废料每格右下角的概率小字。
     * <p><b>必须在物品图标之后绘制（上层）</b>：本方法由 {@link #render} 在 {@code super.render}
     * （内含槽位物品渲染）之后调用，并把 z 抬到 300，保证任何情况下都不会被物品图标盖住。
     * 位置取格子右下角（右对齐），与物品图标错开、清晰可见。</p>
     */
    private void renderChanceOverlay(final GuiGraphics guiGraphics) {
        final var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, 300.0F); // 抬到物品图标（z=0）之上
        for (int i = 0; i < SequencePatternTerminalMenu.RESULT_WINDOW; i++) {
            renderChanceText(guiGraphics, SequencePatternTerminalMenu.SLOT_RESULT_START + i,
                menu.getResultChance(i));
        }
        for (int i = 0; i < SequencePatternTerminalMenu.SCRAP_WINDOW; i++) {
            renderChanceText(guiGraphics, SequencePatternTerminalMenu.SLOT_SCRAP_START + i,
                menu.getScrapChance(i));
        }
        pose.popPose();
    }

    /** 单格概率小字（绝对坐标；右对齐到格子右下角、上移 4px 以避开原版堆叠数所在角落）。
     *  <p>数字格式与 Create JEI 完全一致（{@code <1} / {@code >99} / 四舍五入的整数），见
     *  {@link cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe#chanceNumber(float)}。</p> */
    private void renderChanceText(final GuiGraphics guiGraphics, final int slotIndex, final float chance) {
        final Slot slot = menu.getSlot(slotIndex);
        if (!slot.isActive() || slot.getItem().isEmpty()) {
            return;
        }
        final Component text = Component.literal(
            cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe.chanceNumber(chance) + "%");
        drawSmallText(guiGraphics, text,
            leftPos + slot.x + 16 - font.width(text) * 0.5F, topPos + slot.y + 10, COLOR_CHANCE, -1);
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        syncOffsets();
        if (searchBox != null && !searchBox.getValue().equals(lastSearch)) {
            lastSearch = searchBox.getValue();
        }
        // 生成按钮启用条件（三项全部由服务端 ContainerData 权威判定，客户端只读）：
        //   ① 需要量 need > 0（流程 / 产物有效，且**没有**已存在的总样板）；
        //   ② 终端内样板张数 ≥ need（缺料时直接置灰，不再发聊天栏消息）；
        //   ③ 唯一那格总样板槽为空（由 need 已经蕴含，这里再对可见格做一次防御性确认）。
        if (generateButton != null) {
            final int need = menu.getGenerateNeed();
            final int have = menu.getRsPatternCount();
            final Slot totalSlot = menu.getSlot(SequencePatternTerminalMenu.SLOT_TOTAL_PATTERN_START);
            final boolean totalSlotEmpty = totalSlot == null || totalSlot.getItem().isEmpty();
            generateButton.active = need > 0 && have >= need && totalSlotEmpty;
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 概率小字必须在物品图标之后绘制：super.render 内已完成槽位/物品渲染，这里再叠加（z 抬升）
        renderChanceOverlay(guiGraphics);
        updateScrollbars();
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible) {
                scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }
        renderTooltip(guiGraphics, mouseX, mouseY);
        renderSptTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * 每帧同步：总步数/条目数变化后本地偏移收敛 + 采纳服务端权威偏移（本地交互期间除外）。
     * <p><b>为什么阈值是 10 帧且要判 {@link SptScrollbarWidget#isDragging()}</b>：本地一次拖动 /
     * 滚轮之后，C2S 包要跑完「客户端 → 服务端 → 数据槽回传」的往返（至少 1 tick，通常 2 tick 以上）。
     * 若只等 3 帧就采纳服务端权威值，会在回包到达前把偏移写回<b>旧值</b>，玩家看到的就是
     * 「滚动条被拽回去、根本拖不动」。拖动期间更是绝对不能回写。</p>
     */
    private void syncOffsets() {
        if (framesSinceArrScroll < Integer.MAX_VALUE) {
            framesSinceArrScroll++;
        }
        if (framesSinceArrScroll > 10 && arrangementScrollbar != null && !arrangementScrollbar.isDragging()) {
            menu.setArrangementOffset(menu.getSyncedArrangementOffset());
        }
        if (menu.getArrangementOffset() > menu.getMaxArrangementOffset()) {
            menu.setArrangementOffset(menu.getMaxArrangementOffset());
        }
        if (framesSinceResultScroll < Integer.MAX_VALUE) {
            framesSinceResultScroll++;
        }
        if (framesSinceResultScroll > 10 && resultScrollbar != null && !resultScrollbar.isDragging()) {
            menu.setResultOffset(menu.getSyncedResultOffset());
        }
        if (menu.getResultOffset() > menu.getMaxResultOffset()) {
            menu.setResultOffset(menu.getMaxResultOffset());
        }
        if (framesSinceScrapScroll < Integer.MAX_VALUE) {
            framesSinceScrapScroll++;
        }
        if (framesSinceScrapScroll > 10 && scrapScrollbar != null && !scrapScrollbar.isDragging()) {
            menu.setScrapOffset(menu.getSyncedScrapOffset());
        }
        if (menu.getScrapOffset() > menu.getMaxScrapOffset()) {
            menu.setScrapOffset(menu.getMaxScrapOffset());
        }
        // 单元样板窗口翻页滚动条：页面是服务端权威字段（menu.unitPage 在客户端读 ContainerData），
        // 因此本地交互后同样要等回包再采纳（阈值 / 拖动判定与其它三条完全一致）。
        if (framesSinceUnitPageScroll < Integer.MAX_VALUE) {
            framesSinceUnitPageScroll++;
        }
        if (framesSinceUnitPageScroll > 10 && unitPageScrollbar != null && !unitPageScrollbar.isDragging()) {
            final int page = Math.max(0, Math.min(menu.getUnitPage(), menu.getUnitMaxPage()));
            if (Math.abs(unitPageScrollbar.getOffset() - page) > 0.001) {
                unitPageScrollbar.setOffset(page);
            }
        }
    }

    /**
     * 把菜单里的权威值写回滚动条。
     * <p><b>只在「控件当前偏移 ≠ 菜单偏移」时才写</b>：{@code setOffset} 会触发监听器，而监听器会重置
     * 「本地交互自持窗口」的帧计数；若每帧无条件写回，{@link #syncOffsets()} 里「等 10 帧后采纳服务端
     * 权威偏移」的分支就永远不会执行，滚动条与服务端也将不再严格一致（与
     * {@code CollectionCacheScreen#updateScrollbars} 同一套修法）。</p>
     */
    private void updateScrollbars() {
        if (arrangementScrollbar != null) {
            final int max = menu.getMaxArrangementOffset();
            arrangementScrollbar.setMaxOffset(max);
            arrangementScrollbar.setEnabled(max > 0);
            if (Math.abs(arrangementScrollbar.getOffset() - menu.getArrangementOffset()) > 0.001) {
                arrangementScrollbar.setOffset(menu.getArrangementOffset());
            }
        }
        if (resultScrollbar != null) {
            final int max = menu.getMaxResultOffset();
            resultScrollbar.setMaxOffset(max);
            resultScrollbar.setEnabled(max > 0);
            if (Math.abs(resultScrollbar.getOffset() - menu.getResultOffset()) > 0.001) {
                resultScrollbar.setOffset(menu.getResultOffset());
            }
        }
        if (scrapScrollbar != null) {
            final int max = menu.getMaxScrapOffset();
            scrapScrollbar.setMaxOffset(max);
            scrapScrollbar.setEnabled(max > 0);
            if (Math.abs(scrapScrollbar.getOffset() - menu.getScrapOffset()) > 0.001) {
                scrapScrollbar.setOffset(menu.getScrapOffset());
            }
        }
        // 单元样板窗口翻页滚动条：一条腿走「页」（0..最大页），与 menu 的权威页码同源。
        if (unitPageScrollbar != null) {
            final int max = menu.getUnitMaxPage();
            unitPageScrollbar.setMaxOffset(max);
            unitPageScrollbar.setEnabled(max > 0);
            if (Math.abs(unitPageScrollbar.getOffset() - menu.getUnitPage()) > 0.001) {
                unitPageScrollbar.setOffset(menu.getUnitPage());
            }
        }
    }

    // ===== tooltip =====

    /**
     * 槽位 tooltip：AbstractContainerScreen 不会自动渲染槽位 tooltip，必须手动调用。
     * 流程编排行显示的是「输入物预览」而非槽内实际物品，故这里跳过其原版物品提示。
     */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final Slot hovered = hoveredSlot;
        if (hovered != null && hovered.index >= SequencePatternTerminalMenu.SLOT_ARRANGEMENT_START
            && hovered.index < SequencePatternTerminalMenu.SLOT_ARRANGEMENT_END) {
            return;
        }
        // 输入原料槽：内容只读（由导入 / 生成逻辑写入），但<b>必须有 tooltip</b>。
        // 这里只挡掉原版「槽内物品自身提示」这条路（避免与下面 renderSptTooltips 里那份合并提示
        // 叠成两个框），真正的提示由 renderSptTooltips 统一渲染（物品名 + 该格的角色说明）。
        if (hovered != null && hovered.index == SequencePatternTerminalMenu.SLOT_INPUT) {
            return;
        }
        // 唯一那格总样板槽的 tooltip 由本类统一渲染（含产出物自身提示），
        // 此处跳过原版路径避免叠两个框
        if (hovered != null && hovered.index >= SequencePatternTerminalMenu.SLOT_TOTAL_PATTERN_START
            && hovered.index < SequencePatternTerminalMenu.SLOT_TOTAL_PATTERN_END) {
            return;
        }
        // 产物 / 废料槽：本类把「物品自身提示 + 概率 / 数量说明」合并成<b>一份</b> tooltip，
        // 因此这里必须跳过原版路径，否则会与自定义 tooltip 重叠渲染两遍（用户实测问题）。
        if (hovered != null && hovered.index >= SequencePatternTerminalMenu.SLOT_RESULT_START
            && hovered.index < SequencePatternTerminalMenu.SLOT_SCRAP_END) {
            return;
        }
        // 单元样板槽：同样合并成一份（物品提示 + 槽位标题 + 页码 / 越界说明）
        if (hovered != null && hovered.index >= SequencePatternTerminalMenu.SLOT_UNIT_PATTERN_START
            && hovered.index < SequencePatternTerminalMenu.SLOT_UNIT_PATTERN_END) {
            return;
        }
        // 样板输入槽（3 格）：本类把它合并成一份（位置说明 + 现有张数 + 物品自身提示）
        if (hovered != null && hovered.index >= SequencePatternTerminalMenu.SLOT_RS_PATTERN_START
            && hovered.index < SequencePatternTerminalMenu.SLOT_RS_PATTERN_END) {
            return;
        }
        super.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 自绘信息元素的 tooltip（判定范围与绘制范围严格一致，自上而下取第一个命中）。 */
    private void renderSptTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 0) 唯一那格总样板槽：物品自身提示（有物品时，常显）+「还有几张藏在内部格位」（Shift 层）
        if (hoveredSlot != null && hoveredSlot.index >= SequencePatternTerminalMenu.SLOT_TOTAL_PATTERN_START
            && hoveredSlot.index < SequencePatternTerminalMenu.SLOT_TOTAL_PATTERN_END) {
            renderPatternSlotTooltip(guiGraphics, hoveredSlot, mouseX, mouseY);
            return;
        }

        // 0.5) 样板输入槽（3 格，生成耗材入口）：说清「该放什么」
        if (hoveredSlot != null && hoveredSlot.index >= SequencePatternTerminalMenu.SLOT_RS_PATTERN_START
            && hoveredSlot.index < SequencePatternTerminalMenu.SLOT_RS_PATTERN_END) {
            renderRsPatternTooltip(guiGraphics, mouseX, mouseY);
            return;
        }

        // 0.2) 输入原料槽（右列，配方自动标注）：物品名（常显）+ 该格的角色说明（Shift 层）
        if (hoveredSlot != null && hoveredSlot.index == SequencePatternTerminalMenu.SLOT_INPUT) {
            renderInputSlotTooltip(guiGraphics, mouseX, mouseY);
            return;
        }

        // 1) 流程行：整行（槽框 + 卡片）提示交互说明；悬停 ◀ / ▶ 时提示「将切换到哪一台」；
        //    悬停行内「跳过重复」开关时改为提示该开关（自绘控件必须手动渲染 tooltip）。
        final int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            if (stepSkipToggleHitAt(row, mouseX, mouseY)) {
                renderLines(guiGraphics, stepSkipToggleTooltipLines(globalStepOf(row)), mouseX, mouseY);
                return;
            }
            renderLines(guiGraphics, rowTooltipLines(row, machineSwitchDirectionAt(row, mouseX, mouseY)),
                mouseX, mouseY);
            return;
        }
        // 1.5) 单元样板槽 / 翻页滚动条（全部自绘或自管提示，且必须手动渲染）
        if (hoveredSlot != null && hoveredSlot.index >= SequencePatternTerminalMenu.SLOT_UNIT_PATTERN_START
            && hoveredSlot.index < SequencePatternTerminalMenu.SLOT_UNIT_PATTERN_END) {
            renderUnitSlotTooltip(guiGraphics, hoveredSlot, mouseX, mouseY);
            return;
        }
        if (unitPageScrollHovered(mouseX, mouseY)) {
            renderLines(guiGraphics, unitPageTooltipLines(), mouseX, mouseY);
            return;
        }
        // 越界格（{@code isActive()==false} 时原版不给 hoveredSlot）也要有提示，否则「点不动又没说明」
        if (hoveredSlot == null && unitSlotRowHovered(mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "unit_slots.inactive")),
                mouseX, mouseY);
            return;
        }
        // 2) 产物 / 废料可见槽：一份 tooltip = 物品自身提示（有物品时）+ 概率 / 数量 / 操作说明
        final Slot sectionSlot = sectionSlotAt(mouseX, mouseY);
        if (sectionSlot != null) {
            renderSectionTooltip(guiGraphics, sectionSlot, mouseX, mouseY);
            return;
        }
        // 3) 控制行按钮提示：生成按钮（含<b>不可生成的原因</b>：缺几样 / 先取走总样板 / 流程无效）
        if (isHoveringRect(GEN_X, CONTROL_Y, GEN_W, CONTROL_H, mouseX, mouseY)) {
            renderLines(guiGraphics, generateTooltipLines(), mouseX, mouseY);
            return;
        }
        // 3.1) 只读循环次数：显示当前值 + 「来自配方、不可修改」
        if (isHoveringRect(LOOP_LABEL_X, LOOP_LABEL_Y - 1, LOOP_LABEL_W, 10, mouseX, mouseY)) {
            renderLines(guiGraphics, List.of(Component.translatable(LANG + "loops", loopsValue()),
                Component.translatable(LANG + "loops.tip")), mouseX, mouseY);
        }
    }

    /**
     * 输入原料槽的 tooltip（<b>用户点名：把鼠标放在原料那一格上必须有正确提示</b>）。
     * <p>内容 = 该格的角色说明（「本步消耗的主原料，由配方导入决定」）+ <b>物品自身提示</b>
     * （物品名 / 词条，与其它格子同一份 {@link #itemTooltipLines}）+ 需要时的数量行。
     * 空槽时给「尚未导入 / 该步无物品原料」的说明，绝不留一个既没内容又没提示的死格。</p>
     */
    private void renderInputSlotTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final List<Component> always = new ArrayList<>(4);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(4);
        // 「优先复用中间产物」打开时这一格画的是过渡件：常显 = 它自己的物品提示 + 一行角色说明
        // （用户要求「要能看出这是中间产物」—— 因此这一行放在<b>常显层</b>，不需要按 Shift）。
        final ItemStack intermediate = reuseIntermediate();
        if (!intermediate.isEmpty()) {
            always.addAll(itemTooltipLines(intermediate));
            always.add(Component.translatable(LANG + "input_slot.intermediate")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
            renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
            return;
        }
        // 注：这里原来还有一行无条件的 {@code input_slot.tip}（「本步消耗的主原料，由配方导入决定」），
        // 与下面多候选分支里那句<b>完全重复</b>（同一句话出现两次）。2026-10-05 删掉这一行，
        // 只在真正需要它的分支里给一次。
        // 多选（标签）输入：<b>常显当前轮播到的那一件的普通物品 tooltip</b>（不枚举候选、不写数量）。
        final List<ItemStack> candidates = assemblyInputCandidates();
        final ItemStack stack = candidates.isEmpty()
            ? (hoveredSlot == null ? ItemStack.EMPTY : hoveredSlot.getItem())
            : candidates.get(0);
        if (stack.isEmpty()) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "input_slot.empty")));
            renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
            return;
        }
        if (candidates.size() > 1) {
            // <b>常显层 = 当前轮播到的那一件的名称</b>（2026-10-05 用户要求，原话：
            // 「我要求你和那个产物啊流程编排那里的一样，都是直接正常显示它的那个名称，
            // 然后按下 Shift 才显示……该输入支持多种轮换。但是不要把它都列举出来，因为它本身就在轮换，
            // 就不需要再把它写出来了」）。
            //
            // 旧实现直接 return，于是常显层<b>一行都没有</b> ⇒ 玩家把鼠标放上去只看到
            // 「按住 Shift 查看更多」，连这一格是什么都读不到 —— 这正是用户点名的毛病。
            // 现在：常显 = 当前那一件的名称（与图标轮播同源，因此名字与图标永远指同一件）；
            // Shift 层 = 候选总数 + 完整候选清单（详情，不常显）。
            // 常显：当前轮播到的那一件的<b>普通物品 tooltip</b>（名字 / 词条）—— 用户要求「铁粒和锌粒要显示
            // 它们本身的、物品的那个正常 tooltip，不是自定义 tooltip」。
            final ItemStack shown = GhostMarkerRenderer.cycleCandidate(candidates);
            always.addAll(itemTooltipLines(shown));
            // <b>Shift 扩展层（2026-10-05 用户最终要求）</b>：
            //   * <b>一行都不写「等 N 种」「图标轮换」</b>（原话：「删除所有这种语言文件中的他妈的什么图标
            //     轮换，你当我是瞎子呀，看不见图标在轮换了……也不需要说几种到几种这样子的，
            //     更不用说明有多少，更不用具体写出有哪一些」）；
            //   * <b>枚举候选清单也一并删掉</b> —— 它已经在轮换了，列出来纯属噪声；
            //   * 这一格该显示的就是<b>当前轮播到的那一件的普通物品 tooltip</b>（下面 always 那行已经给了）。
            // 因此这里只保留「这是输入原料格」这一句角色说明 —— 用已有的
            // {@code input_slot.tip}（「本步消耗的主原料，由配方导入决定」）。
            //
            // ⚠ 2026-10-05 修掉的真 bug：这里原来用的是**那个带 %s 占位符的候选键**，而调用不传参
            // ⇒ 玩家按 Shift 直接看到 `输入原料：%s`。那个键已从语言文件删除，
            // 并新增 {@code tools/audit_placeholder_keys.py} 专门扫「带 %s 却没人传参」的键，防复发。
            layered.add(RsccTooltipLayers.shift(
                Component.translatable(LANG + "input_slot.tip")));
            renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
            return;
        }
        always.addAll(itemTooltipLines(stack)); // 物品名（含词条），与其它格子同源 → 常显
        if (stack.getCount() > 1) {
            layered.add(RsccTooltipLayers.ctrl(
                Component.translatable(LANG + "input_slot.count", stack.getCount())));
        }
        renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
    }

    /**
     * 「生成样板」按钮的提示（<b>用户要求：缺料不发聊天栏，改成 tooltip 说清缺几样</b>）。
     * <p>四种情形按优先级：已有总样板（必须先取走）→ 流程 / 产物无效 → 缺 N 个样板 → 可以生成时的说明。
     * 全部判据都取自服务端下发的数据槽（{@code generateNeed} / {@code rsPatternCount}），
     * 与按钮的启用条件同源，因此「提示里说的」与「点下去会发生的」永远一致。</p>
     */
    private List<Component> generateTooltipLines() {
        final int need = menu.getGenerateNeed();
        final int have = menu.getRsPatternCount();
        final List<Component> lines = new ArrayList<>();
        if (menu.getPatternCount() > 0) {
            // 唯一那格总样板槽（含老存档藏在靠内格位的）里还有样板：必须先取走
            lines.add(Component.translatable(LANG + "generate.tooltip.blocked"));
        } else if (need <= 0) {
            lines.add(Component.translatable(LANG + "generate.tooltip.invalid"));
        } else if (have < need) {
            lines.add(Component.translatable(LANG + "generate.tooltip.missing", need - have));
            lines.add(Component.translatable(LANG + "generate.tip"));
        } else {
            lines.add(Component.translatable(LANG + "generate.tip"));
        }
        return lines;
    }

    /**
     * 样板输入槽（3 格）是否被悬停（与 {@link #renderRsPatternSlots} 的绘制范围同源）。
     * <p>这 3 格是真实槽位，悬停时 {@code hoveredSlot} 可能已指向它；但原版槽位 tooltip 不会自动渲染，
     * 因此这里仍要显式判定 + 手动渲染（硬规则）。
     */
    private boolean rsPatternSlotHovered(final double mouseX, final double mouseY) {
        for (int i = 0; i < SequencePatternTerminalBlockEntity.RS_PATTERN_SLOT_SIZE; i++) {
            if (isHoveringRect(SequencePatternTerminalMenu.RS_PATTERN_SLOT_X + i * 18,
                SequencePatternTerminalMenu.RS_PATTERN_SLOT_Y, 16, 16, mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 样板输入槽的提示（<b>只留「该放什么 + 现在够不够」这类可操作信息</b>）。
     * <p>按用户要求删掉了「样板存在终端里、不用背在身上；方块终端掉落、手持终端随物品保存」这类
     * 解释界面元素含义的自研说明（判定标准：解释元素含义的删、告诉玩家该做什么的留）。</p>
     */
    private void renderRsPatternTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final List<Component> always = new ArrayList<>(4);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(2);
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "rs_pattern_slots.tip")));
        layered.add(RsccTooltipLayers.ctrl(
            Component.translatable(LANG + "rs_pattern_slots.stored", menu.getRsPatternCount())));
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            always.addAll(itemTooltipLines(hoveredSlot.getItem()));
        }
        renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
    }

    /**
     * 流程行的提示内容（与卡片文字同源信息；不再附「滚轮 / 左键」一类操作说明，保持 tooltip 精简）。
     *
     * @param arrowDirection 鼠标是否停在行内 ◀ / ▶ 上（-1 / +1 / 0）：非 0 时额外提示「将切换到哪一台」
     */
    private List<Component> rowTooltipLines(final int windowRow, final int arrowDirection) {
        final int global = globalStepOf(windowRow);
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(LANG + "card.step", global + 1));
        // 只读说明：步骤顺序 / 次数由配方生成，但<b>执行仓可切换</b>（用户要求恢复，见 switchStepMachine）
        lines.add(Component.translatable(LANG + "card.readonly"));
        final SequencePatternData.UnitData unit = unitDataAt(global);
        if (unit == null) {
            lines.add(Component.translatable(LANG + "empty_row"));
            return lines;
        }
        // 第 1 行显示的是「该步用到的机器」，因此 tooltip 同样先给出机器（再附原始配方类型 id）
        lines.add(Component.translatable(LANG + "card.machine_kind", machineKindName(recipeTypeOf(global))));
        lines.add(RecipeTypeNames.idLine(recipeTypeOf(global)));
        lines.add(Component.translatable(LANG + "card.count", Math.max(1, menu.getArrangementCount(windowRow))));
        // 多选（标签）输入：<b>只写「当前轮播到的那一件」的名称</b>，与行内图标同源。
        // 2026-10-05 用户要求（原话）：「不需要说几种到几种这样子的，更不用说明有多少，
        // 更不用具体写出有哪一些，他妈的他已经在轮换了」⇒ 不再带任何数量 / 「等 N 种」文案。
        final List<ItemStack> inputCandidates = stepInputCandidates(global);
        if (!inputCandidates.isEmpty()) {
            final ItemStack shownInput = inputCandidates.size() > 1
                ? GhostMarkerRenderer.cycleCandidate(inputCandidates)
                : inputCandidates.get(0);
            lines.add(Component.translatable(LANG + "card.input", shownInput.getHoverName()));
        }
        lines.add(Component.translatable(LANG + "card.machine",
            hasNoMachine(global) ? Component.translatable(LANG + "no_machine") : machineNameOf(global)));
        // 行内 ◀ / ▶ 的提示（自绘控件，必须手动渲染）：候选机器数 + 当前第几台；
        // 悬停在箭头上时改成「将切换到哪一台」——与点击真正的落点同源（都走 neighborMachine）。
        final List<SyncChamberListPacket.Entry> candidates = chambersFor(recipeTypeOf(global));
        if (!candidates.isEmpty()) {
            if (arrowDirection != 0) {
                final SyncChamberListPacket.Entry target = neighborMachine(global, arrowDirection);
                if (target != null) {
                    lines.add(Component.translatable(LANG + "card.machine_switch.to", target.name()));
                }
            } else {
                lines.add(Component.translatable(LANG + "card.machine_switch",
                    machineOrdinal(global, candidates), candidates.size()));
            }
        }
        // 打开「机器选择」子界面的两条入口（自绘控件 / 手势，必须由 tooltip 说清，否则玩家猜不到）
        lines.add(Component.translatable(LANG + "card.machine_open"));
        // 输入流体标记（注液器一类步骤）：名称 + 数量，与槽内流体图标同源
        final net.neoforged.neoforge.fluids.FluidStack fluid = stepFluidAt(global);
        if (!fluid.isEmpty()) {
            lines.add(Component.translatable(LANG + "card.fluid",
                fluid.getHoverName(),
                // 数量文案与 RS 规则一致（≥1 桶写 B、否则 mB），由本类的本地格式化给出：
                // 共享的格式器（GhostMarkerRenderer#fluidAmount）正在被另一处改动删除，
                // 本类不再依赖它，避免「别人的改动把本终端界面一起编译不过」。
                fluidAmountText(fluid.getAmount())));
        }
        // 该步是否需要输入原料：判据必须<b>同时</b>覆盖物品与流体——
        // 否则「需要岩浆」这类纯流体步骤会被误报成「该步不需要输入原料」（用户实测问题）。
        final boolean allowsItem = stepAllowsItemInput(global);
        final boolean allowsFluid = stepAllowsFluidInput(global);
        if (!allowsItem && allowsFluid) {
            lines.add(Component.translatable(LANG + "card.fluid_input_only"));
        } else if (!allowsItem && !allowsFluid) {
            lines.add(Component.translatable(LANG + "card.no_item_input"));
        }
        return lines;
    }

    /**
     * 流体数量文案（与 RS 原版规则一致）：{@code ≥1 桶} 写 {@code B}（保留 1 位小数），否则写 {@code mB}。
     * <p><b>为什么本类自带这一份</b>：原来调用的是 {@code GhostMarkerRenderer#fluidAmount}，
     * 而那个共享方法在另一处改动中被删除（同一次改动还留在别的界面里没改完）。
     * 终端界面的这一步 tooltip 与那个改动毫无关系，因此把这几行留在本类里，
     * 保证「别人的改动」不会把本终端界面一起拖成编译不过；输出文本与旧实现逐字相同。</p>
     */
    private static String fluidAmountText(final long mB) {
        if (mB >= 1000L) {
            final long whole = mB / 1000L;
            final long frac = (mB % 1000L) / 100L;
            return frac == 0 ? whole + "B" : whole + "." + frac + "B";
        }
        return mB + "mB";
    }

    /** 方块世界实例（客户端菜单也持有 ClientLevel；不可用时返回 null）。 */
    private net.minecraft.world.level.Level clientLevel() {
        return minecraft == null ? null : minecraft.level;
    }

    /**
     * 唯一那格总样板槽的提示：物品自身提示（有物品时）+ 「还有几张藏在内部格位」（若有）。
     * <p>按用户要求删掉了「总样板槽：存放生成出的总样板」这类解释界面元素含义的自研说明；
     * 保留的 {@code pattern_slots.hidden} 是<b>可操作信息</b>（告诉玩家把现存样板取空后会继续前移出更多）。</p>
     */
    private void renderPatternSlotTooltip(final GuiGraphics guiGraphics, final Slot slot,
                                          final int mouseX, final int mouseY) {
        final List<Component> always = new ArrayList<>(3);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(2);
        if (slot.hasItem()) {
            always.addAll(itemTooltipLines(slot.getItem()));
        }
        // 操作入口必须常显（用户第 3 条：从总样板改每一步的机器 / 次数），否则玩家猜不到 Ctrl+左键
        always.add(Component.translatable(LANG + "pattern_slots.detail.tip"));
        final int hidden = menu.getHiddenPatternCount();
        if (hidden > 0) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "pattern_slots.hidden", hidden)));
        }
        renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
    }

    /**
     * 单元样板槽提示：物品自身提示（有物品时）+（越界格）不可用说明。
     * <p>按用户要求删掉了「单元样板槽 x-y / 共 N 张（可放入 / 取出）」这类解释界面元素含义的说明；
     * 越界格的「不可用」说明保留 —— 它是「为什么点不动」的唯一反馈（属于告诉玩家该知道的事）。</p>
     */
    private void renderUnitSlotTooltip(final GuiGraphics guiGraphics, final Slot slot,
                                       final int mouseX, final int mouseY) {
        final List<Component> always = new ArrayList<>(2);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(2);
        if (slot.hasItem()) {
            always.addAll(itemTooltipLines(slot.getItem()));
        }
        if (!slot.isActive()) {
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "unit_slots.inactive")));
        }
        renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
    }

    /** 翻页滚动条提示：翻页方式 + 当前页码（滚动条是自绘控件，tooltip 必须手动渲染）。 */
    private List<Component> unitPageTooltipLines() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(LANG + "unit_page.tip"));
        lines.add(Component.translatable(LANG + "unit_page",
            menu.getUnitPage() + 1, menu.getUnitMaxPage() + 1));
        return lines;
    }

    /**
     * 该全局步是否<b>真的</b>存在物品输入（判据来自该步配方数据，见
     * {@link SequencePatternTerminalBlockEntity#stepAllowsItemInput}）：动力冲压机（create:pressing）
     * 一类步骤没有额外物品输入 → 该行的输入格禁用；有输入时自动启用。与 JEI 禁用 / 服务端拦截同源。
     */
    private boolean stepAllowsItemInput(final int globalStep) {
        final int windowRow = globalStep - menu.getArrangementOffset();
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return false;
        }
        final ItemStack unit = arrangementUnitAt(windowRow);
        return !unit.isEmpty()
            && SequencePatternTerminalBlockEntity.stepAllowsItemInput(clientLevel(), unit);
    }

    /** 该全局步是否<b>真的</b>存在流体输入（判据来自该步配方数据；灌注 / 注液一类为 true）。 */
    private boolean stepAllowsFluidInput(final int globalStep) {
        final int windowRow = globalStep - menu.getArrangementOffset();
        if (windowRow < 0 || windowRow >= ARR_ROWS) {
            return false;
        }
        final ItemStack unit = arrangementUnitAt(windowRow);
        return !unit.isEmpty()
            && SequencePatternTerminalBlockEntity.stepAllowsFluidInput(clientLevel(), unit);
    }

    /** 鼠标所在的产物/废料可见槽（与绘制范围一致；不在网格内返回 null）。 */
    private Slot sectionSlotAt(final double mouseX, final double mouseY) {
        for (final Slot slot : menu.slots) {
            final int idx = slot.index;
            if (idx < SequencePatternTerminalMenu.SLOT_RESULT_START
                || idx >= SequencePatternTerminalMenu.SLOT_SCRAP_END || !slot.isActive()) {
                continue;
            }
            if (isHoveringRect(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return slot;
            }
        }
        return null;
    }

    /** 产物/废料的提示内容：**一份** tooltip —— 物品自身提示（有物品时）+ 角色 + 概率 / 数量 + 操作说明。 */
    private void renderSectionTooltip(final GuiGraphics guiGraphics, final Slot slot,
                                      final int mouseX, final int mouseY) {
        final boolean scrap = slot.index >= SequencePatternTerminalMenu.SLOT_SCRAP_START;
        final int start = scrap ? SequencePatternTerminalMenu.SLOT_SCRAP_START
            : SequencePatternTerminalMenu.SLOT_RESULT_START;
        final int windowIndex = slot.index - start;
        final int global = (scrap ? menu.getScrapOffset() : menu.getResultOffset()) * 12 + windowIndex + 1;
        final float chance = scrap ? menu.getScrapChance(windowIndex) : menu.getResultChance(windowIndex);
        final List<Component> always = new ArrayList<>(4);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(4);
        // 物品自身提示（名称等）：常显（原版那一路已在此处取代，仍必须常显）
        if (slot.hasItem()) {
            always.addAll(itemTooltipLines(slot.getItem()));
        }
        if (slot.hasItem()) {
            // 概率与产出数量属于数值 → Ctrl 层；角色说明 → Shift 层
            layered.add(RsccTooltipLayers.shift(
                Component.translatable(LANG + (scrap ? "scraps.tip" : "results.tip"), global)));
            layered.add(RsccTooltipLayers.ctrl(cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe
                .chanceComponent(chance)));
            layered.add(RsccTooltipLayers.ctrl(
                Component.translatable(LANG + "amount", slot.getItem().getCount())));
        } else {
            layered.add(RsccTooltipLayers.shift(
                Component.translatable(LANG + "section_readonly.tip")));
        }
        renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
    }

    /** 物品自身提示行（名称 / 词条等），供自定义 tooltip 合并复用。 */
    private List<Component> itemTooltipLines(final ItemStack stack) {
        return stack.getTooltipLines(
            net.minecraft.world.item.Item.TooltipContext.of(minecraft == null ? null : minecraft.level),
            minecraft == null ? null : minecraft.player,
            minecraft != null && minecraft.options.advancedItemTooltips
                ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                : net.minecraft.world.item.TooltipFlag.Default.NORMAL);
    }

    /** 手动渲染多行 tooltip（本模组的 GUI 不会自动渲染 tooltip）。
     *  <p>本方法的入参约定为<b>全是本模组附加信息</b>（控制说明 / 机制 / 数值…）：统一交唯一实现
     *  {@link RsccTooltipLayers#renderAttached}，即默认收起、按住 Shift 才展开，并自动补提示行。</p> */
    private void renderLines(final GuiGraphics guiGraphics, final List<Component> lines,
                             final int mouseX, final int mouseY) {
        RsccTooltipLayers.renderAttached(guiGraphics, font, lines, mouseX, mouseY);
    }

    /** 「物品自身 tooltip（常显）+ 本模组附加信息（按层）」的渲染入口。 */
    private void renderWithItemLines(final GuiGraphics guiGraphics, final List<Component> always,
                                     final List<RsccTooltipLayers.Line> layered,
                                     final int mouseX, final int mouseY) {
        RsccTooltipLayers.render(guiGraphics, font, always, layered, mouseX, mouseY);
    }

    // ===== 交互 =====

    /**
     * 交互入口。
     * <p><b>可交互</b>：底部单元样板窗口的翻页滚动条、另外三条滚动条、以及<b>流程行内「切换执行仓」的
     * ◀ / ▶</b>（用户要求恢复：机器是可改的，走 {@link SetStepMachinePacket} 由服务端权威写入）。</p>
     * <p><b>仍然只读</b>：流程编排本身（步骤顺序 / 次数）、输入原料标记槽、产物 / 废料标记槽、循环次数 ——
     * 它们的只读判定在 {@code SequencePatternTerminalMenu} 的槽位实现里（{@code mayPlace} /
     * {@code mayPickup} / {@code remove} 恒空）或根本没有控件；
     * 底部那格总样板槽（只出不进）与 6 格单元样板窗口本来就是可交互的真实槽位，同样未受影响。</p>
     */
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int clickedButton) {
        // <b>2026-10-05 用户要求：删掉「Ctrl+左键」打开的「步骤详细配置」子界面。</b>
        // 原话：<i>「按下 Ctrl 加左键单击打开的那个界面有什么实际意义？
        // 另一个（可搜索的机器选择）不已经够了吗，又有搜索框又可以选择，所以把 Ctrl 加左键的那个界面删了」</i>。
        // 因此原来这里的两条 Ctrl 入口（① 总样板槽 ② 步骤行）都已移除；
        // 每一步的机器切换统一走左键点「机器控件横带」→ {@code StepMachineSelectScreen}（可搜索）。
        //
        // 行内机器切换 ◀ / ▶（自绘控件）：命中即消费点击，避免穿透到下面的槽位
        final int arrowRow = rowAt(mouseX, mouseY);
        final int arrowDirection = machineSwitchDirectionAt(arrowRow, mouseX, mouseY);
        if (arrowDirection != 0) {
            switchStepMachine(arrowRow, arrowDirection);
            return true;
        }
        // 行内「跳过重复」开关（自绘控件，卡片第 1 行最右端）：命中即切换本步的生成语义并消费点击。
        // 它只发 C2S 包（服务端权威写回 + 回传快照），与行内机器切换同一套做法。
        if (clickedButton == 0 && arrowRow >= 0 && stepSkipToggleHitAt(arrowRow, mouseX, mouseY)) {
            toggleStepSkipDuplicate(arrowRow);
            return true;
        }
        // 每步行内入口（互不重叠，命中即消费）：
        //   左键点机器控件横带（机器图标右侧那段 ◀ 机器名 ▶）→ 机器选择子界面（可搜索，换机最快）。
        //   （2026-10-05 用户要求：原来这里的「Ctrl+左键点该步骤整行 → 步骤详细配置」已删除。）
        if (clickedButton == 0 && arrowRow >= 0) {
            final int global = globalStepOf(arrowRow);
            if (global >= 0 && machineStripHitAt(arrowRow, mouseX, mouseY)) {
                openMachineSelect(global);
                return true;
            }
        }
        // 四条滚动条（含单元样板窗口翻页条）：统一转发，命中即消费点击
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible && scrollbar.mouseClicked(mouseX, mouseY, clickedButton)) {
                resetScrollFrames(scrollbar);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, clickedButton);
    }

    private void resetScrollFrames(final SptScrollbarWidget scrollbar) {
        if (scrollbar == arrangementScrollbar) {
            framesSinceArrScroll = 0;
        } else if (scrollbar == resultScrollbar) {
            framesSinceResultScroll = 0;
        } else if (scrollbar == scrapScrollbar) {
            framesSinceScrapScroll = 0;
        } else if (scrollbar == unitPageScrollbar) {
            framesSinceUnitPageScroll = 0;
        }
    }

    @Override
    public void mouseMoved(final double mouseX, final double mouseY) {
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible) {
                scrollbar.mouseMoved(mouseX, mouseY);
            }
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * 拖动转发：MC 在「按住鼠标移动」时只派发 {@code mouseDragged}（不派发 {@code mouseMoved}）。
     * <p>自绘滚动条不是原版子控件，必须由本界面显式转发，否则按住拖动期间滑块<b>一个事件都收不到</b>
     * —— 玩家看到的就是「滚动条拖不动，只能点轨道或滚轮」（用户反复反馈的根因）。</p>
     */
    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible && scrollbar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                resetScrollFrames(scrollbar);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible && scrollbar.mouseReleased(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 滚轮：只用于<b>浏览</b>（流程编排 / 产物 / 废料 / 单元样板窗口翻页）。
     * <p>流程由配方生成，不接受任何修改（无「Shift+滚轮改次数」「切换机器」）。</p>
     */
    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double delta) {
        if (!hasShiftDown() && !hasControlDown()) {
            // 单元样板槽区域：滚轮翻页（放在最前，且热区只覆盖底部那一组，
            // 与产物 / 废料 / 流程编排的滚轮区在几何上完全不相交，不会抢它们的滚动）。
            // v10 起翻页滚动条就在这一热区内，滚轮直接交给它（±1 页；页码仍是服务端权威）。
            if (x >= leftPos + UNIT_SCROLL_X1 && x < leftPos + UNIT_SCROLL_X2
                && y >= topPos + UNIT_SCROLL_Y1 && y < topPos + UNIT_SCROLL_Y2) {
                if (unitPageScrollbar != null && unitPageScrollbar.visible) {
                    if (unitPageScrollbar.mouseScrolled(x, y, scrollX, delta)) {
                        framesSinceUnitPageScroll = 0;
                    }
                }
                return true;
            }
            // 产物 / 废料区域滚动
            if (x >= leftPos + SECTION_PANEL_X1 && x <= leftPos + SECTION_PANEL_X2) {
                if (y >= topPos + RESULT_PANEL_Y1 && y <= topPos + RESULT_PANEL_Y2
                    && resultScrollbar.mouseScrolled(x, y, scrollX, delta)) {
                    framesSinceResultScroll = 0;
                    return true;
                }
                if (y >= topPos + SCRAP_PANEL_Y1 && y <= topPos + SCRAP_PANEL_Y2
                    && scrapScrollbar.mouseScrolled(x, y, scrollX, delta)) {
                    framesSinceScrapScroll = 0;
                    return true;
                }
            }
            // 流程编排区域滚动
            if (x >= leftPos + ARR_PANEL_X1 && x < leftPos + ARR_PANEL_X2
                && y >= topPos + ARR_PANEL_Y1 && y < topPos + ARR_PANEL_Y2
                && arrangementScrollbar.mouseScrolled(x, y, scrollX, delta)) {
                framesSinceArrScroll = 0;
                return true;
            }
        }
        return super.mouseScrolled(x, y, scrollX, delta);
    }
}
