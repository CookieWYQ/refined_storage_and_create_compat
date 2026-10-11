package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionAbsorbTogglePacket;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionScrollPacket;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionXpFormPacket;
import cretae.cookiewyq.rs_create_compat.support.AbsorbType;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.XpForm;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 归流缓存仓界面（v4 重排布局，256×324，见 {@code tmp_textures/COLLECTION_CACHE_GUI_DOC_V4.md}）：
 * 上方「匹配区」12 列 × 4 行 ghost 槽（左键标记 / 空手左键打开配置 / Shift+左键清除），
 * 下方「缓存区」12 列 × 4 行真实存储槽；两条滚动条的轨道分别**贴在各自面板内右缘**。
 * 缓存面板下方是一行矮面板（4 个吸取开关：物品 / 流体 / 气体 / 经验，一行文本 + 一个按钮，**不加 tooltip**），
 * 其下 6 格横排升级槽（物品栏正上方居中），底部玩家背包与快捷栏。
 * <p>流体 / 气体<b>不再独占面板</b>（V4 文档 §8）：与物品共用同一套匹配槽 / 缓存槽。匹配槽的流体来自
 * {@link MarkerEntry} 同步包（见 {@link GhostMarkerRenderer}）；缓存槽的流体以叠加方式绘制在
 * 当前可见窗口内「没有物品」的缓存格上，数量小字沿用 RS 规则（≥1 桶显示 B，否则 mB）。
 * <p>两个区域各有独立的滚动条与偏移（服务端权威，经 ContainerData 同步）。
 * <p>硬规则：<b>精灵坐标 → Menu 坐标 = x、y 均 +1</b>（本类常量全部为 Menu 坐标）；
 * AbstractContainerScreen 不会自动渲染槽位 tooltip，所有 tooltip 都在 {@link #render} 里手动调用。
 */
public class CollectionCacheScreen extends AbstractContainerScreen<CollectionCacheMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/collection_cache.png");

    private static final String LANG = "gui.rs_create_compat.collection_cache.";

    private static final int BG_W = 256;
    private static final int BG_H = 324;

    // ===== 几何常量（全部为 Menu 坐标，见 COLLECTION_CACHE_GUI_DOC_V4.md） =====
    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 6;

    /** 收集范围入口按钮（标题行右侧留白区）：点击打开三轴范围子窗口（与范围充电器同款三轴交互）。 */
    private static final int RANGE_BTN_X = 204;
    private static final int RANGE_BTN_Y = 4;
    private static final int RANGE_BTN_W = 42;
    private static final int RANGE_BTN_H = 14;

    /** 「输入面」入口按钮（标题行，紧邻范围按钮左侧；与执行仓「面配置」同一套交互理念）。 */
    private static final int INPUT_FACE_BTN_X = 160;
    private static final int INPUT_FACE_BTN_Y = 4;
    private static final int INPUT_FACE_BTN_W = 42;
    private static final int INPUT_FACE_BTN_H = 14;

    /**
     * 「经验存储形态」循环按钮（标题行，输入面按钮左侧的剩余留白内）：点击在 经验颗粒 / 液态经验 之间循环
     * （「自动」模式已按用户要求删除；缺前置的形态自动跳过）。
     * <p>位置说明：标题文字从 x=8 起（中英文最长约 84px，即到 x≈92），输入面按钮从 x=160 起；
     * 按钮取 100..156，两边各留 8px / 4px，不压任何槽位 / 滚动条 / 面板边框，也无需改动背景几何。
     * 文案只用短名（56px 内可完整显示「经验:液态经验」/「XP:Liquid XP」），完整说明走 tooltip。</p>
     */
    private static final int XP_FORM_BTN_X = 100;
    private static final int XP_FORM_BTN_Y = 4;
    private static final int XP_FORM_BTN_W = 56;
    private static final int XP_FORM_BTN_H = 14;

    /** 分区标签（Java 文字，背景留白）：匹配区精灵 (9,20) 240×8、缓存区精灵 (9,106) 240×8。 */
    private static final int MATCH_LABEL_X = 10;
    private static final int MATCH_LABEL_Y = 21;
    private static final int CACHE_LABEL_X = 10;
    private static final int CACHE_LABEL_Y = 107;
    /** 匹配区标题带可画宽度（标题 + 「已失效」后缀都在这条带里，超出即按像素截断）。 */
    private static final int MATCH_LABEL_BAND_W = 104;

    /**
     * <b>删除模式开关</b>（2026-10-05）：放在「缓存区」标题带的空白处（Menu y106 那一行），
     * 与匹配区「全收 / 反转」同一套「标签 + 紧贴按钮」关系。
     *
     * <h2>历史（避免再改回去）</h2>
     * <p>曾有一版把「销毁缓存区」做成一颗独立的「一次性全清」按钮挂在缓存标题带右端。
     * 用户明确要求<b>删掉那颗按钮</b>（原话：「销毁缓存区那一个按钮删掉我让你留了吗，只留下一个用来确认
     * 开关这个销毁匹配到的资源文本以及它对应的启动关闭按钮，不要留这一个销毁缓存区域的按钮」）。
     * 删除模式的语义就是「持续销毁命中『标记为删除』的资源」，它<b>自己</b>就是那个启动 / 关闭按钮，
     * 因此缓存标题带上只保留这一个控件。</p>
     */
    private static final int DELETE_MODE_LABEL_Y = 109;
    private static final int DELETE_MODE_BTN_Y = 106;
    private static final int DELETE_MODE_BTN_W = 14;
    private static final int DELETE_MODE_BTN_H = 12;
    /** 标签与它所属按钮的水平间距（用户要求「离近一点」；取 2px = 贴边但仍可读）。 */
    private static final int LABEL_GAP = 2;
    /** 「直接销毁」匹配槽标识的颜色（醒目的红，画在槽位内容右上角）。 */
    private static final int COLOR_DESTROY_MARK = 0xFFFF2020;

    /** 匹配区滚动条：Menu (227,33) 宽 10 高 71（精灵 (226,32)，贴在匹配区面板内右缘）。 */
    private static final int MATCH_SCROLL_X = 227;
    private static final int MATCH_SCROLL_Y = 33;
    /** 缓存区滚动条：Menu (227,119) 宽 10 高 71（精灵 (226,118)，贴在缓存区面板内右缘）。 */
    private static final int CACHE_SCROLL_X = 227;
    private static final int CACHE_SCROLL_Y = 119;
    private static final int SCROLL_W = 10;
    private static final int SCROLL_H = 71;

    /**
     * 「阻塞」标识的红色底（ARGB：alpha 0x40 = 25%）。
     * <p>只铺在 16×16 图标区、且绘制在<b>内容之下</b>：图标 / 流体 / 数量一律仍清晰可读，
     * 面板底纹与槽位描边都不动，因此不会出现「整格 / 整个背景变红」。</p>
     * <p><b>底色深浅一个字都不改</b>（用户明确要求「用原本的那种就可以」）：本轮新增的显眼标识
     * 全部画在<b>内容之外的槽位装饰层</b>（见 {@link McGui#blockedSlotMark}），不再往内容上叠任何东西。</p>
     */
    private static final int COLOR_BLOCKED_BASE = 0x40FF3030;

    /**
     * 「匹配区失效 / 按钮被自动模式禁用」时的说明文字颜色（灰）。
     * <p>用于 {@code 吸取所有物品} 开启后把「匹配区」标题与按钮标签灰化 —— 这就是「禁用态」的表达，
     * 不动背景图层（背景 PNG 一个字都不改）。</p>
     */
    private static final int COLOR_DISABLED = 0xFF8A8A8A;

    /**
     * 三个模式控件（全收 / 反转 / 销毁匹配资源）：一行，每个标签紧贴自己右侧的按钮。
     * <p>2026-10-05 用户连续几轮反馈后定稿的排布规则（原话：「文本和这个按钮中间离得近一点，
     * 两组不同之间的间隔离远一点」「整体往右边移」）：</p>
     * <ul>
     *     <li><b>标签 → 按钮 = 2px</b>（{@value #LABEL_GAP}，贴边但仍可读）；</li>
     *     <li><b>组与组 = 30px</b>（{@value #GROUP_GAP}，一眼能看出哪两个字属于哪一个复选框）；</li>
     *     <li>整行右移（首组从 x104 起），右端不越出面板（末组按钮右缘 186 &lt; 252）。</li>
     * </ul>
     */
    private static final int LABEL_BAND_W = 26;
    /** 组与组之间的水平间距（用户要求「两组之间的间隔离远一点」）。 */
    private static final int GROUP_GAP = 30;
    /** 复选框宽度（三组共用；必须在推导下一组起点之前声明，Java 静态初始化不允许前向引用）。 */
    private static final int TOGGLE_W = 14;
    private static final int COLLECT_ALL_LABEL_X = 104;
    private static final int COLLECT_ALL_BTN_X = COLLECT_ALL_LABEL_X + LABEL_BAND_W + LABEL_GAP;
    private static final int INVERT_LABEL_X = COLLECT_ALL_BTN_X + TOGGLE_W + GROUP_GAP;
    private static final int INVERT_BTN_X = INVERT_LABEL_X + LABEL_BAND_W + LABEL_GAP;
    /**
     * 删除模式那一组（「销毁匹配资源」+ 复选框）。
     *
     * <p><b>2026-10-05 用户反馈</b>：「销毁匹配资源以及它的按钮移到背景外面去了往左边移」，
     * 而且「现在销毁匹配资源就只显示了销毁两个字，剩下几个字变成 ... 了」。</p>
     *
     * <p>原因：它原来是从反转那一组自动推导出来的（{@code INVERT_BTN_X + TOGGLE_W + GROUP_GAP}），
     * 而共享的 {@code LABEL_BAND_W = 26} 只够两个汉字 —— 「销毁匹配资源」有 <b>6 个汉字</b>（≈54px），
     * 于是被 {@code trimToWidth} 截成「销毁…」，起点也被推到 204、按钮落到面板右缘之外。</p>
     *
     * <p>改法：<b>这两项不再从反转推导</b>，按「标签够放 6 个汉字 + 按钮留在面板内」直接定值；
     * 反转那一组一个字都不动（用户明确要求）。</p>
     */
    private static final int DELETE_MODE_LABEL_W = 54;
    private static final int DELETE_MODE_LABEL_X = 88;
    private static final int DELETE_MODE_BTN_X = DELETE_MODE_LABEL_X + DELETE_MODE_LABEL_W + LABEL_GAP;
    /** 控件行：Menu y=19 高 12（上不压标题行，下不压匹配区面板首行 Menu 32）。 */
    private static final int TOGGLE_ROW_Y = 19;
    private static final int TOGGLE_H = 12;
    /** 两个开关的文字标签基线 y（与按钮垂直居中：19 + 2）。 */
    private static final int TOGGLE_LABEL_Y = 21;

    /** 两个区域的滚轮命中范围（与面板凹陷一致）：匹配区 Menu (7,32) 244×73、缓存区 Menu (7,118) 244×73。 */
    private static final int MATCH_PANEL_X1 = 7;
    private static final int MATCH_PANEL_X2 = 251;
    private static final int MATCH_PANEL_Y1 = 32;
    private static final int MATCH_PANEL_Y2 = 105;
    private static final int CACHE_PANEL_X1 = 7;
    private static final int CACHE_PANEL_X2 = 251;
    private static final int CACHE_PANEL_Y1 = 118;
    private static final int CACHE_PANEL_Y2 = 191;

    /** 4 个吸取开关：一行 4 组（<b>按钮紧贴文本左侧</b>，避免出现「物品的按钮像是在控制流体」的错位感）。 */
    private static final int[] ABSORB_BTN_X = {15, 75, 135, 195};
    /** 文本紧跟在各自按钮右侧（+17 = 按钮宽 14 + 3px 间隔），组宽 60。 */
    private static final int ABSORB_LABEL_DX = 17;
    private static final int ABSORB_GROUP_W = 60;
    /** 按钮 y（Menu 197，高 12）与文本基线 y（Menu 199）。 */
    private static final int ABSORB_ROW_Y = 197;
    private static final int ABSORB_LABEL_Y = 199;
    private static final int ABSORB_BTN_W = 14;
    private static final int ABSORB_BTN_H = 12;

    private SptScrollbarWidget matchScrollbar;
    private SptScrollbarWidget cacheScrollbar;
    private final List<SptScrollbarWidget> scrollbars = new ArrayList<>();
    /** 距上次本地滚动的帧数：期间不采纳服务端回传偏移，避免拖拽被“拽回”。 */
    private int framesSinceMatchScroll;
    private int framesSinceCacheScroll;
    /** 匹配区同步窗口（S2C）第一条的全局下标。 */
    private int markerWindowStart;
    /** 匹配区当前可见窗口信息（S2C 同步）。 */
    private List<MarkerEntry> markerEntries = List.of();
    /** 匹配区当前可见窗口的「直接销毁」标志（S2C 同步，与 {@link #markerEntries} 一一对应）。 */
    private List<Boolean> markerDestroyFlags = List.of();
    /** 缓存区流体内容（S2C 同步；由后端在缓存同步包中调用 {@link #setCacheFluidEntries}）。 */
    private List<MarkerEntry> cacheFluidEntries = List.of();
    /** 「阻塞名单」快照（S2C 同步）：被阻塞的资源格子画红底（醒目标识，不用文字）。 */
    private final java.util.Set<ResourceLocation> blockedItems = new java.util.HashSet<>();
    private final java.util.Set<ResourceLocation> blockedFluids = new java.util.HashSet<>();
    /** 「按标签阻塞」名单（服务端权威；本轮新增，见 {@link #isEntryBlocked}）。 */
    private final java.util.Set<ResourceLocation> blockedItemTags = new java.util.HashSet<>();
    private final java.util.Set<ResourceLocation> blockedFluidTags = new java.util.HashSet<>();
    /** 当前可见缓存窗口内每格的流体叠加（下标 = 窗口内格号，无则 null）；每帧重算。 */
    private final MarkerEntry[] fluidOverlay = new MarkerEntry[CollectionCacheMenu.CACHE_WINDOW];
    /** 4 个吸取开关按钮（序与 {@link AbsorbType#values()} 一致）。 */
    private final Button[] absorbButtons = new Button[AbsorbType.values().length];
    /** 「吸取所有物品」开关按钮（✓/✗；开启后匹配区整体失效）。 */
    private Button collectAllButton;
    /** 「反转匹配」开关按钮（✓/✗；匹配区白名单 ↔ 黑名单；被「吸取所有物品」禁用时置灰）。 */
    private Button invertMatchButton;
    /** 收集范围入口按钮（打开三轴范围子窗口）。 */
    private Button rangeButton;
    /** 「输入面」入口按钮（打开逐面输入开关子窗口）。 */
    private Button inputFaceButton;
    /** 经验形态循环按钮（自动 / 颗粒 / 液态；文案每帧由菜单同步数据刷新）。 */
    private Button xpFormButton;
    /** 删除模式复选框（2026-10-05）：状态来自服务端数据槽；开启需经确认子窗口。 */
    private Button deleteModeButton;
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.RsccRedstoneModeProperty
        rscc$redstoneMode;

    public CollectionCacheScreen(final CollectionCacheMenu menu,
                                 final Inventory inventory,
                                 final Component title) {
        super(menu, inventory, title);
        this.imageWidth = BG_W;
        this.imageHeight = BG_H;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
        this.titleLabelY = TITLE_Y;
    }

    /** S2C：接收匹配区<b>当前可见窗口</b>（{@code windowStart} 为该窗口第一条的全局下标）。 */
    public void setMarkerEntries(final int windowStart, final List<MarkerEntry> entries,
                                 final List<Boolean> destroyFlags) {
        this.markerWindowStart = Math.max(0, windowStart);
        this.markerEntries = entries != null ? entries : List.of();
        this.markerDestroyFlags = destroyFlags != null ? destroyFlags : List.of();
    }

    /**
     * 某全局下标的匹配槽是否处于「直接销毁」模式（客户端只读展示；服务端权威）。
     * <p>下标先减去同步窗口起点再查标志表 —— 判定与绘制范围同源，翻页后也一致。</p>
     */
    public boolean isMarkerDestroy(final int globalIndex) {
        final int windowIndex = globalIndex - markerWindowStart;
        return windowIndex >= 0 && windowIndex < markerDestroyFlags.size()
            && Boolean.TRUE.equals(markerDestroyFlags.get(windowIndex));
    }

    /**
     * S2C：接收缓存区流体内容（与物品共用缓存槽显示，见 V4 文档 §8）。
     * <p>后端在缓存同步时调用；条目用 {@link MarkerEntry} 承载
     * （{@code fluid=true}，{@code id}=流体注册名，{@code amount}=mB）。</p>
     */
    public void setCacheFluidEntries(final List<MarkerEntry> entries) {
        this.cacheFluidEntries = entries != null ? entries : List.of();
    }

    /** S2C：接收「阻塞名单」快照（具体资源 id + 被阻塞的标签，两组各含物品 / 流体），用于红色标识与 tooltip 文案。 */
    public void setBlocked(final List<ResourceLocation> items, final List<ResourceLocation> fluids,
                           final List<ResourceLocation> itemTags, final List<ResourceLocation> fluidTags) {
        blockedItems.clear();
        blockedFluids.clear();
        blockedItemTags.clear();
        blockedFluidTags.clear();
        if (items != null) {
            blockedItems.addAll(items);
        }
        if (fluids != null) {
            blockedFluids.addAll(fluids);
        }
        if (itemTags != null) {
            blockedItemTags.addAll(itemTags);
        }
        if (fluidTags != null) {
            blockedFluidTags.addAll(fluidTags);
        }
    }

    /** 兼容重载：不带标签名单（等价于空标签集合）。 */
    public void setBlocked(final List<ResourceLocation> items, final List<ResourceLocation> fluids) {
        setBlocked(items, fluids, null, null);
    }

    /** 该资源是否被阻塞（<b>仅</b>具体 id 名单；标签判定见 {@link #isItemBlocked} / {@link #isFluidBlocked}）。 */
    private boolean isBlocked(final boolean fluid, @Nullable final ResourceLocation id) {
        if (id == null) {
            return false;
        }
        return fluid ? blockedFluids.contains(id) : blockedItems.contains(id);
    }

    /**
     * 命中该物品的「被阻塞标签」（可能多个；已排序，tooltip 顺序稳定）。
     * <p><b>与服务端同源</b>：{@code CollectionCacheBlockEntity#isBlockedStack} 判定「该物品带上
     * 被阻塞标签集合里的<b>任意一个</b>」即阻塞。界面此前只看具体 id 名单，因此「按标签（例如
     * 『所有板子』）注册的匹配条目」真的被挡住回流时，同族物品的 tooltip 仍显示「未阻塞」
     * —— 这就是用户实测的「确实被阻塞了，tooltip 却说未阻塞」的根因。</p>
     */
    private List<ResourceLocation> blockingItemTags(final ItemStack stack) {
        if (stack.isEmpty() || blockedItemTags.isEmpty()) {
            return List.of();
        }
        final Item item = stack.getItem();
        final List<ResourceLocation> hits = new ArrayList<>(2);
        for (final ResourceLocation tag : blockedItemTags) {
            if (tag != null && item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag))) {
                hits.add(tag);
            }
        }
        hits.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        return hits;
    }

    /** 命中该流体 / 气体的「被阻塞标签」（口径与 {@link #blockingItemTags} 一致，对应服务端 {@code isBlockedFluid}）。 */
    private List<ResourceLocation> blockingFluidTags(@Nullable final ResourceLocation id) {
        if (id == null || blockedFluidTags.isEmpty()) {
            return List.of();
        }
        final net.minecraft.world.level.material.Fluid fluid =
            net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
            return List.of();
        }
        final List<ResourceLocation> hits = new ArrayList<>(2);
        for (final ResourceLocation tag : blockedFluidTags) {
            if (tag != null && fluid.builtInRegistryHolder().is(TagKey.create(Registries.FLUID, tag))) {
                hits.add(tag);
            }
        }
        hits.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        return hits;
    }

    /** 该物品是否被阻塞：具体 id 命中 ∪ 命中任一被阻塞标签（= 服务端 {@code isBlockedStack}）。 */
    private boolean isItemBlocked(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return isBlocked(false,
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
            || !blockingItemTags(stack).isEmpty();
    }

    /** 该流体是否被阻塞：具体 id 命中 ∪ 命中任一被阻塞标签（= 服务端 {@code isBlockedFluid}）。 */
    private boolean isFluidBlocked(@Nullable final ResourceLocation id) {
        return isBlocked(true, id) || !blockingFluidTags(id).isEmpty();
    }

    /**
     * 该<b>匹配条目</b>是否被阻塞：具体资源 id 命中 <b>或</b> 该条目的标签集合命中「按标签阻塞」名单。
     * <p>按标签注册的条目点阻塞后，服务端把<b>标签</b>写进阻塞名单，因此界面也必须按标签判定 ——
     * 否则金板虽然真的被挡在网络之外，格子看起来却是「未阻塞」。</p>
     */
    private boolean isEntryBlocked(final MarkerEntry entry) {
        if (entry == null || entry.isEmpty()) {
            return false;
        }
        return isBlocked(entry.fluid(), entry.id()) || !entryBlockingTags(entry).isEmpty();
    }

    /**
     * 匹配条目被阻塞时「被谁阻塞」的标签集合（与 {@link #isEntryBlocked} <b>同一判据</b>，
     * 只为 tooltip 说明来源；未按标签阻塞 / 不是标签条目时返回空表）。
     */
    private List<ResourceLocation> entryBlockingTags(final MarkerEntry entry) {
        if (entry == null || entry.isEmpty() || !entry.isTagFilter()) {
            return List.of();
        }
        final java.util.Set<ResourceLocation> tags = entry.fluid() ? blockedFluidTags : blockedItemTags;
        return !tags.isEmpty() && tags.containsAll(entry.tags()) ? entry.tags() : List.of();
    }

    /** 全局下标 → 同步窗口内的条目（不在当前窗口内返回空条目）。 */
    private MarkerEntry markerEntry(final int globalIndex) {
        final int index = globalIndex - markerWindowStart;
        if (index < 0 || index >= markerEntries.size()) {
            return MarkerEntry.EMPTY;
        }
        final MarkerEntry entry = markerEntries.get(index);
        return entry == null ? MarkerEntry.EMPTY : entry;
    }

    @Override
    protected void init() {
        super.init();
        // 匹配区滚动条（NORMAL，轨道 10 宽，贴在匹配区面板内右缘）
        matchScrollbar = new SptScrollbarWidget(leftPos + MATCH_SCROLL_X, topPos + MATCH_SCROLL_Y,
            SptScrollbarWidget.Type.NORMAL, SCROLL_H, SCROLL_W);
        matchScrollbar.setListener(offset -> {
            final int page = (int) Math.round(offset);
            framesSinceMatchScroll = 0;
            if (page != menu.getMatchOffset()) {
                menu.setMatchOffset(page);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new SetCollectionScrollPacket(menu.containerId, true, page));
            }
        });
        scrollbars.add(matchScrollbar);
        // 缓存区滚动条（NORMAL，轨道 10 宽，贴在缓存区面板内右缘）
        cacheScrollbar = new SptScrollbarWidget(leftPos + CACHE_SCROLL_X, topPos + CACHE_SCROLL_Y,
            SptScrollbarWidget.Type.NORMAL, SCROLL_H, SCROLL_W);
        cacheScrollbar.setListener(offset -> {
            final int page = (int) Math.round(offset);
            framesSinceCacheScroll = 0;
            if (page != menu.getCacheOffset()) {
                menu.setCacheOffset(page);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new SetCollectionScrollPacket(menu.containerId, false, page));
            }
        });
        scrollbars.add(cacheScrollbar);
        // 两个新开关（画在「匹配区」标题带右侧留白里，与匹配区语义相邻）：
        //   「吸取所有物品」：开启后匹配区整体失效（不过滤，全收）—— 与「反转匹配」同时开启时本开关优先；
        //   「反转匹配」：匹配区从白名单变黑名单；被「吸取所有物品」禁用时置灰（三态：✓ / ✗ / 禁用）。
        // 两者都是服务端权威（只发 C2S 意图包，显示值每帧由数据槽回写）。
        collectAllButton = addRenderableWidget(McGui.toggle(
            leftPos + COLLECT_ALL_BTN_X, topPos + TOGGLE_ROW_Y, TOGGLE_W, TOGGLE_H,
            () -> menu.isCollectAll(),
            on -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetCollectionCollectAllPacket(
                    menu.containerId, on))));
        invertMatchButton = addRenderableWidget(McGui.toggle(
            leftPos + INVERT_BTN_X, topPos + TOGGLE_ROW_Y, TOGGLE_W, TOGGLE_H,
            () -> menu.isInvertMatch(),
            on -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetCollectionInvertMatchPacket(
                    menu.containerId, on))));
        // 4 个吸取开关：统一「一行文本 + 一个 ✓/✗ 按钮」（勾绿 / 叉红，全工程同一套控件）。
        // 状态由服务端持久化并经数据槽回传；按要求这四个开关不加 tooltip。
        final AbsorbType[] types = AbsorbType.values();
        for (int i = 0; i < types.length && i < absorbButtons.length; i++) {
            final AbsorbType type = types[i];
            absorbButtons[i] = addRenderableWidget(McGui.toggle(
                leftPos + ABSORB_BTN_X[i], topPos + ABSORB_ROW_Y, ABSORB_BTN_W, ABSORB_BTN_H,
                () -> menu.isAbsorbEnabled(type),
                on -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new SetCollectionAbsorbTogglePacket(menu.containerId, type, on))));
        }
        // 收集范围：入口按钮 → 打开三轴范围子窗口（与「范围充电器」同款 3 行 [-] 输入框 [+] 交互）
        rangeButton = new Button.Builder(Component.translatable(LANG + "range"), btn -> openRangeConfig())
            .bounds(leftPos + RANGE_BTN_X, topPos + RANGE_BTN_Y, RANGE_BTN_W, RANGE_BTN_H)
            .build();
        addRenderableWidget(rangeButton);
        // 输入面：入口按钮 → 打开「哪些面可以输入」子窗口（与执行仓面配置同款十字网交互）
        inputFaceButton = new Button.Builder(Component.translatable(LANG + "input_face"),
            btn -> openInputFaceConfig())
            .bounds(leftPos + INPUT_FACE_BTN_X, topPos + INPUT_FACE_BTN_Y,
                INPUT_FACE_BTN_W, INPUT_FACE_BTN_H)
            .build();
        addRenderableWidget(inputFaceButton);
        // 经验存储形态：标题行的循环按钮（折算成经验颗粒 / 折算成液态经验）。
        // 两种形态的收集对象都是经验球，按钮只切换「经验点存成什么」；点击只发 C2S 包，
        // 写入与持久化都由服务端负责（序号解码见 XpForm#byOrdinal，本轮修好了液态的解码）。
        xpFormButton = new Button.Builder(xpFormLabel(menu.getXpForm()), btn -> cycleXpForm())
            .bounds(leftPos + XP_FORM_BTN_X, topPos + XP_FORM_BTN_Y, XP_FORM_BTN_W, XP_FORM_BTN_H)
            .build();
        addRenderableWidget(xpFormButton);
        // <b>2026-10-05：这里原来有一颗「销毁缓存区」按钮，已按要求<b>删除</b>。</b>
        // 用户原话：「销毁缓存区那一个按钮删掉我让你留了吗，只留下一个用来确认开关这个销毁匹配到的资源
        // 文本以及它对应的启动关闭按钮，不要留这一个销毁缓存区域的按钮」。
        // 因此缓存标题带上只保留<b>一个</b>控件：删除模式开关（删除模式的语义就是
        // 「持续销毁命中『标记为删除』的资源」，它自己就是那个启动 / 关闭按钮）。
        // 服务端的 deleteMode 总闸与确认子窗口照旧，只是不再有「一次性全清」的入口。
        // 删除模式复选框：放回缓存区标题带的空白处（Menu y106 那一行），
        // 「删除模式」标签紧贴其左侧 —— 与匹配区「全收 / 反转」同一套「标签 + 紧贴按钮」关系。
        // **开启**必须先经确认子窗口（不可逆操作），**关闭**立即生效。
        deleteModeButton = addRenderableWidget(McGui.toggle(
            leftPos + DELETE_MODE_BTN_X, topPos + DELETE_MODE_BTN_Y,
            DELETE_MODE_BTN_W, DELETE_MODE_BTN_H,
            () -> menu.isDeleteMode(),
            on -> {
                if (on) {
                    // 开启：只开确认窗口，**不发包**
                    openDeleteModeConfirm();
                } else {
                    // 关闭：恢复安全状态，无需确认 —— 但仍然经服务端权威落地
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new cretae.cookiewyq.rs_create_compat.network.SetCollectionDeleteModePacket(
                            menu.containerId, false, true));
                }
            }));
        // 红石模式（忽略 / 高电平 / 低电平）：复用 RS 原版侧边按钮，挂在面板左侧外缘
        rscc$redstoneMode = cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.attach(this);
    }

    /**
     * 打开「开启删除模式」确认子窗口（2026-10-05）。
     * <p>与一次性全清同样的纪律：主界面复选框的「开启」动作只打开本窗口、<b>不发包</b>；
     * 确认窗口才发送带确认位的 C2S 包（见 {@code SetCollectionDeleteModePacket}）。
     * 用户要求把「确认要销毁匹配到的资源」这件事固定成这一步 —— 删除模式一旦开着就会持续销毁，
     * 因此开启前必须显式确认一次。</p>
     */
    private void openDeleteModeConfirm() {
        if (minecraft != null) {
            minecraft.setScreen(new CollectionDeleteModeConfirmScreen(this, menu));
        }
    }

    /** 红石模式由服务端权威值驱动显示（控件本体复用 RS 原版，hover 时才手动渲染 tooltip）。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (rscc$redstoneMode != null) {
            rscc$redstoneMode.set(menu.rscc$getRedstoneMode().ordinal());
        }
    }

    /** 打开「输入面配置」子窗口（逐面开启/关闭漏斗 / 管道的输入；服务端权威）。 */
    private void openInputFaceConfig() {
        if (minecraft != null) {
            minecraft.setScreen(new CollectionInputFaceConfigScreen(this, menu));
        }
    }

    /** 当前允许物流输入的面数（服务端权威值；用于按钮 tooltip）。 */
    private int inputFaceCount() {
        int count = 0;
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (menu.isInputFace(direction)) {
                count++;
            }
        }
        return count;
    }

    /** 打开三轴收集范围子窗口（子窗口里可 Shift 快速增减、直接输入、超限自动夹紧）。 */
    private void openRangeConfig() {
        if (minecraft != null) {
            minecraft.setScreen(new CollectionRangeConfigScreen(this, menu));
        }
    }

    /** 向服务端发送「切换阻塞」请求（本地先用已知名单翻转，立即得到视觉反馈；服务端权威值随后覆盖）。 */
    private void sendBlockedToggle(final boolean fluid, @Nullable final ResourceLocation id) {
        sendBlockedToggle(fluid, id, null);
    }

    /**
     * 向服务端发送「切换阻塞」请求（可带<b>标签集合</b>）。
     * <p><b>本轮修复（用户第 ③ 条）</b>：匹配条目若按标签注册（{@code tags} 非空），阻塞必须按标签生效 ——
     * 否则同族资源（金板）会被服务端判定为「未阻塞」而瞬间回流进网络。
     * 主界面缓存格的 Ctrl+左键不传标签（要精确控制那一个资源），行为与改动前一致。</p>
     */
    private void sendBlockedToggle(final boolean fluid, @Nullable final ResourceLocation id,
                                   @Nullable final List<ResourceLocation> tags) {
        if (id == null) {
            return;
        }
        final boolean tagged = tags != null && !tags.isEmpty();
        final boolean next = !(tagged
            ? (isBlocked(fluid, id) || (fluid ? blockedFluidTags : blockedItemTags).containsAll(tags))
            : isBlocked(fluid, id));
        // 本地即时翻转（乐观更新），服务端会回传权威名单
        if (tagged) {
            final java.util.Set<ResourceLocation> tagSet = fluid ? blockedFluidTags : blockedItemTags;
            if (next) {
                tagSet.addAll(tags);
            } else {
                tagSet.removeAll(tags);
            }
        }
        (fluid ? blockedFluids : blockedItems).add(id);
        if (!next) {
            (fluid ? blockedFluids : blockedItems).remove(id);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.SetCollectionBlockedPacket(
                menu.containerId, fluid, id, next, tagged ? tags : List.of()));
    }

    private static String absorbNameKey(final AbsorbType type) {
        return switch (type) {
            case ITEM -> LANG + "absorb.item";
            case FLUID -> LANG + "absorb.fluid";
            case GAS -> LANG + "absorb.gas";
            case EXPERIENCE -> LANG + "absorb.experience";
        };
    }

    // ==================== 经验存储形态（折算成经验颗粒 / 折算成液态经验） ====================

    /** 经验存储形态的短名语言键（按钮文案用；tooltip 复用同一套短名）。 */
    private static String xpFormKey(final XpForm form) {
        return switch (form) {
            case ORB -> LANG + "xp_form.orb";
            case LIQUID -> LANG + "xp_form.liquid";
        };
    }

    /** 按钮文案（形如「经验:液态经验」）：按钮只有 60px 宽，故只用短名，完整说明走 tooltip。 */
    private Component xpFormLabel(final XpForm form) {
        return Component.translatable(LANG + "xp_form.btn", Component.translatable(xpFormKey(form)));
    }

    /**
     * 点击循环：颗粒 → 液态 → 颗粒（<b>「自动」模式已按用户要求删除</b>）。
     * <p>循环<b>自动跳过不可选的形态</b>：缺前置（没装「机械动力：附魔工业」）时液态整个跳过，
     * 因此点按钮不会切到一个当前根本不可用的形态上（用户要求：缺前置时该选项不可选）。
     * 只发 C2S 包，服务端写入后方块实体并落 NBT（服务端还会再拦一道）。</p>
     */
    private void cycleXpForm() {
        final XpForm next = menu.getXpForm().nextSelectable();
        if (next == menu.getXpForm()) {
            return; // 没有别的可选形态（例如只有颗粒可用）：不发送无意义的包
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetCollectionXpFormPacket(menu.containerId, next));
    }

    /**
     * 每帧把服务端权威的经验存储形态写回按钮文案（与 4 个吸取开关同一套「服务端权威」刷新方式），
     * 并同步按钮的可用性：只有「还能切换到别的可选形态」时按钮才可点（否则点它没有任何效果）。
     * <p>按钮文案取自菜单数据槽 21 的解码结果，其解码口径是 {@link XpForm#byOrdinal(int)} ——
     * 那里此前不认现行液态序号，于是无论怎么点都显示成颗粒（本轮已修）。</p>
     */
    private void refreshXpFormButton() {
        if (xpFormButton != null) {
            xpFormButton.setMessage(xpFormLabel(menu.getXpForm()));
            xpFormButton.active = menu.getXpForm().nextSelectable() != menu.getXpForm();
        }
    }

    // ==================== 匹配条目配置 ====================

    /** 打开独立的匹配条目配置界面（照搬 RS 原版：空手左键点击过滤器槽 → 打开独立配置 Screen）。 */
    private void openMarkerConfig(final int markerIndex) {
        if (minecraft == null) {
            return;
        }
        final MarkerEntry entry = markerEntry(markerIndex);
        if (entry.isEmpty()) {
            return;
        }
        minecraft.setScreen(new CollectionMarkerConfigScreen(
            this, menu.containerId, markerIndex, entry, isEntryBlocked(entry),
            isMarkerDestroy(markerIndex)));
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        syncOffsets();
        refreshAbsorbButtons();
        refreshMatchModeButtons();
        // 经验形态按钮的文案必须每帧跟着服务端权威值刷新：此前只定义了刷新方法却漏了调用，
        // 于是点按钮虽然真的切了模式（tooltip 显示正确），按钮上的字却永远停在初始的「自动」。
        refreshXpFormButton();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        updateScrollbars();
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible) {
                scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }
        renderTooltip(guiGraphics, mouseX, mouseY);
        renderCacheTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 每帧把服务端权威的开关状态写回按钮文案（按钮点击后由数据槽回传）。 */
    private void refreshAbsorbButtons() {
        final AbsorbType[] types = AbsorbType.values();
        for (int i = 0; i < types.length && i < absorbButtons.length; i++) {
            if (absorbButtons[i] != null) {
                McGui.refreshToggle(absorbButtons[i], menu.isAbsorbEnabled(types[i]));
            }
        }
    }

    /**
     * 两个新开关的每帧刷新（三态视觉）：
     * <ul>
     *     <li>「吸取所有物品」：✓（开）/ ✗（关）两态；</li>
     *     <li>「反转匹配」：✓（开）/ ✗（关）/ <b>置灰（被自动模式禁用）</b> 三态 ——
     *     「吸取所有物品」开启时匹配区整体失效，此时该按钮 {@code active = false}（原版按钮会画成禁用灰），
     *     并仍按服务端真值显示 ✓/✗，让玩家看得出「设置还在，只是现在不生效」。</li>
     * </ul>
     */
    private void refreshMatchModeButtons() {
        if (collectAllButton != null) {
            McGui.refreshToggle(collectAllButton, menu.isCollectAll());
        }
        if (invertMatchButton != null) {
            McGui.refreshToggle(invertMatchButton, menu.isInvertMatch());
            invertMatchButton.active = !menu.isInvertMatchDisabled();
        }
        // 删除模式复选框：状态来自服务端数据槽（开启需确认，因此点击后状态会在包往返后才变）。
        if (deleteModeButton != null) {
            McGui.refreshToggle(deleteModeButton, menu.isDeleteMode());
        }
    }

    /** 每帧同步：本地交互结束后采纳服务端权威偏移，并把越界偏移立即收敛。 */
    private void syncOffsets() {
        if (framesSinceMatchScroll < Integer.MAX_VALUE) {
            framesSinceMatchScroll++;
        }
        // 拖动中不回写（否则高延迟下刚拖到的位置会被服务端旧值「拽回」）；松手后再等 10 帧让回包到达
        if (framesSinceMatchScroll > 10 && matchScrollbar != null && !matchScrollbar.isDragging()) {
            menu.setMatchOffset(menu.getSyncedMatchOffset());
        }
        if (menu.getMatchOffset() > menu.getMaxMatchOffset()) {
            menu.setMatchOffset(menu.getMaxMatchOffset());
        }
        if (framesSinceCacheScroll < Integer.MAX_VALUE) {
            framesSinceCacheScroll++;
        }
        if (framesSinceCacheScroll > 10 && cacheScrollbar != null && !cacheScrollbar.isDragging()) {
            menu.setCacheOffset(menu.getSyncedCacheOffset());
        }
        if (menu.getCacheOffset() > menu.getMaxCacheOffset()) {
            menu.setCacheOffset(menu.getMaxCacheOffset());
        }
    }

    /**
     * 把菜单里的权威值写回滚动条。
     * <p>只在「控件当前偏移 ≠ 菜单偏移」时才写：{@code setOffset} 会触发监听器，而监听器会重置
     * 「本地交互结束」的等待计数；若每帧都写，{@link #syncOffsets()} 里「等待 10 帧后采纳服务端权威偏移」
     * 的分支就永远不会执行，滚动条状态与服务端就不再严格一致。</p>
     */
    private void updateScrollbars() {
        if (matchScrollbar != null) {
            final int max = menu.getMaxMatchOffset();
            matchScrollbar.setMaxOffset(max);
            matchScrollbar.setEnabled(max > 0);
            if (Math.abs(matchScrollbar.getOffset() - menu.getMatchOffset()) > 0.001) {
                matchScrollbar.setOffset(menu.getMatchOffset());
            }
        }
        if (cacheScrollbar != null) {
            final int max = menu.getMaxCacheOffset();
            cacheScrollbar.setMaxOffset(max);
            cacheScrollbar.setEnabled(max > 0);
            if (Math.abs(cacheScrollbar.getOffset() - menu.getCacheOffset()) > 0.001) {
                cacheScrollbar.setOffset(menu.getCacheOffset());
            }
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, BG_W, BG_H);
        updateFluidOverlay();
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, TITLE_X, TITLE_Y, 0xFF333333, false);
        // 两分区标签（Java 文字，背景留白）：匹配区 / 缓存区。
        // 「吸取所有物品」开启时匹配区整体失效 → 标题灰化 + 追加失效后缀（三态里的「禁用态」表达）
        final boolean matchDisabled = menu.isCollectAll();
        Component matchLabel = Component.translatable(LANG + "region_marker");
        if (matchDisabled) {
            matchLabel = Component.translatable(LANG + "region_marker.disabled",
                matchLabel.getString());
        }
        guiGraphics.drawString(font, Component.literal(trimToWidth(matchLabel.getString(), MATCH_LABEL_BAND_W)),
            MATCH_LABEL_X, MATCH_LABEL_Y, matchDisabled ? COLOR_DISABLED : 0xFF333333, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "region_cache"),
            CACHE_LABEL_X, CACHE_LABEL_Y, 0xFF333333, false);
        // 删除模式复选框的文字标签（缓存区标题带；紧贴其按钮左侧，按像素截断，英文长词也不越界）
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(
                Component.translatable(LANG + "delete_mode.label").getString(),
                DELETE_MODE_BTN_X - DELETE_MODE_LABEL_X - LABEL_GAP)),
            DELETE_MODE_LABEL_X, DELETE_MODE_LABEL_Y,
            menu.isDeleteMode() ? COLOR_DESTROY_MARK : 0xFF333333, false);
        // 两个新开关的文字标签（画在各自 ✓/✗ 按钮左侧；按像素截断，英文长词也不越界）
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(Component.translatable(LANG + "collect_all.label").getString(),
                COLLECT_ALL_BTN_X - COLLECT_ALL_LABEL_X - LABEL_GAP)),
            COLLECT_ALL_LABEL_X, TOGGLE_LABEL_Y, matchDisabled ? 0xFF333333 : COLOR_DISABLED, false);
        guiGraphics.drawString(font,
            Component.literal(trimToWidth(Component.translatable(LANG + "invert.label").getString(),
                INVERT_BTN_X - INVERT_LABEL_X - LABEL_GAP)),
            INVERT_LABEL_X, TOGGLE_LABEL_Y, matchDisabled ? COLOR_DISABLED : 0xFF333333, false);
        // 收集范围入口按钮的文字由原版 Button 自己绘制（MC 风格按钮贴图），此处不再重复画标签：
        // 三轴数值在子窗口里编辑（与范围充电器同款交互），数值明细走按钮 tooltip。
        // 4 个吸取开关：按钮 + 紧跟其后的文本（按像素截断，保证英文长词也不越到下一组）
        final AbsorbType[] types = AbsorbType.values();
        for (int i = 0; i < types.length && i < ABSORB_BTN_X.length; i++) {
            guiGraphics.drawString(font,
                Component.literal(trimToWidth(
                    Component.translatable(absorbNameKey(types[i])).getString(),
                    ABSORB_GROUP_W - ABSORB_LABEL_DX - 2)),
                ABSORB_BTN_X[i] + ABSORB_LABEL_DX, ABSORB_LABEL_Y, 0xFF333333, false);
        }
    }

    /** 按像素宽截断字符串（尾部补省略号）：保证文字不越过所属分组。 */
    private String trimToWidth(final String text, final int maxWidth) {
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

    // ==================== 缓存槽上的流体叠加（与物品共用同一套槽，V4 文档 §8） ====================

    /**
     * 重算可见缓存窗口内的流体叠加：按顺序填到「没有物品」的可见缓存格，避免盖住真实物品。
     * <p>每帧在 {@link #renderBg} 里刷新一次；渲染 / tooltip / 点击共用同一份结果（判定与绘制同源）。</p>
     */
    private void updateFluidOverlay() {
        Arrays.fill(fluidOverlay, null);
        int cursor = 0;
        for (int i = 0; i < fluidOverlay.length && cursor < cacheFluidEntries.size(); i++) {
            final Slot slot = menu.getSlot(CollectionCacheMenu.MATCH_WINDOW + i);
            if (slot == null || !slot.getItem().isEmpty()) {
                continue; // 有物品的格子留给物品
            }
            fluidOverlay[i] = cacheFluidEntries.get(cursor++);
        }
    }

    /** 可见缓存格上的流体条目（下标 = 窗口内格号；无则 null）。 */
    @Nullable
    private MarkerEntry cacheFluidAt(final int windowIndex) {
        if (windowIndex < 0 || windowIndex >= fluidOverlay.length) {
            return null;
        }
        return fluidOverlay[windowIndex];
    }

    /**
     * 光标上的容器装不装得下缓存里的这种流体（<b>客户端预测</b>，只用于决定「这次点击算不算取出流体」；
     * 服务端会再完整校验一遍，客户端不产生任何权威结果）。
     * <p>判断口径与服务端一致：走容器的 {@code IFluidHandlerItem.fill(SIMULATE)}，
     * 且模拟在物品<b>副本</b>上进行 —— 绝不会动到光标上的真实物品。</p>
     */
    private static boolean cursorAccepts(final MarkerEntry fluid, final ItemStack carried) {
        final net.neoforged.neoforge.fluids.capability.IFluidHandlerItem handler =
            carried.copy().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM);
        if (handler == null) {
            return false; // 不是流体容器（拿着普通物品 → 这次点击照旧交给槽位本身）
        }
        final FluidStack cached = GhostMarkerRenderer.toFluidStack(fluid.id(),
            Math.min(fluid.amount(), CollectionCacheMenu.BUCKET_MB));
        return !cached.isEmpty() && handler.fill(cached,
            net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE) > 0;
    }

    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final Slot slot) {
        // 「阻塞」标识分两层，物品与流体走**完全同一套**视觉语言（用户本轮要求「都要明显」）：
        //   ① 内容**之下**：原本那种半透明淡红背景（alpha 0x40，深浅一个字都没改）；
        //   ② 内容**之外**：统一的槽位装饰层 —— 2px 厚高饱和红环 + 四角角标
        //      （见 McGui.blockedSlotMark）。它在 16×16 内容区之外，绝不覆盖物品 / 流体图标，
        //      也不会因为「流体贴图铺满整格」而被挡住（上一轮只给流体画边缘细红边 → 物品格几乎看不出）。
        final boolean blocked = isSlotBlocked(slot);
        if (blocked) {
            guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, COLOR_BLOCKED_BASE);
        }
        final int idx = slot.index;
        if (idx >= 0 && idx < CollectionCacheMenu.MATCH_WINDOW && menu.isMarkerWindow(idx)) {
            renderMarkerSlot(guiGraphics, slot);
            // 「直接销毁」匹配槽标识：内容右上角画一个醒目的红色 ✖，让玩家一眼看出该槽是销毁模式
            // （判定与同步窗口同源：isMarkerDestroy 已按 windowStart 归一化下标）
            if (isMarkerDestroy(menu.globalIndex(idx))) {
                guiGraphics.drawString(font, "✖", slot.x + 9, slot.y + 1, COLOR_DESTROY_MARK, false);
            }
        } else {
            super.renderSlot(guiGraphics, slot);
            // 缓存区：只在「没有物品」的格子上叠加流体/气体图标（图标是唯一的可视化手段）；
            // 数量等附加信息一律写进 tooltip，不再往物品图标上叠加数量角标。
            final MarkerEntry fluid = cacheFluidAt(idx - CollectionCacheMenu.MATCH_WINDOW);
            if (fluid != null && fluid.fluid()) {
                final FluidStack stack = GhostMarkerRenderer.toFluidStack(fluid.id(), fluid.amount());
                if (!stack.isEmpty()) {
                    GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, stack);
                }
            }
        }
        if (blocked) {
            McGui.blockedSlotMark(guiGraphics, slot.x, slot.y);
        }
    }

    /**
     * 被阻塞的槽位判定（匹配槽按标记资源、缓存槽按物品或流体叠加；绘制与命中共用本方法）。
     * <p>不做描边、不改面板底纹：物品与流体一律先铺 {@link #COLOR_BLOCKED_BASE} 一层淡红底，
     * 再在内容之外画 {@link McGui#blockedSlotMark} 的统一装饰环。</p>
     */
    private boolean isSlotBlocked(final Slot slot) {
        if (slot.index >= 0 && slot.index < CollectionCacheMenu.MATCH_WINDOW) {
            final MarkerEntry entry = markerEntry(menu.globalIndex(slot.index));
            return isEntryBlocked(entry);
        }
        if (slot.index >= CollectionCacheMenu.MATCH_WINDOW
            && slot.index < CollectionCacheMenu.WINDOW_SLOTS) {
            if (!slot.getItem().isEmpty()) {
                // 物品侧与「缓存→网络」的真实判定同源：具体 id ∪ 命中被阻塞标签
                return isItemBlocked(slot.getItem());
            }
            final MarkerEntry fluid = cacheFluidAt(slot.index - CollectionCacheMenu.MATCH_WINDOW);
            return fluid != null && fluid.fluid() && isFluidBlocked(fluid.id());
        }
        return false;
    }

    /**
     * 匹配槽以 ghost 方式绘制：物品 / 流体（气体同样走流体贴图）。
     * <p><b>不叠加数量角标</b>：数量 / 匹配规则 / 命中数量等附加信息一律写进 tooltip
     * （见 {@link #renderMarkerTooltip}），因此一格只会渲染一份 tooltip。
     * 唯一的图标叠加是「阻塞」标识（内容之下的 {@link #COLOR_BLOCKED_BASE} 淡红底 +
     * 内容之外由 {@link McGui#blockedSlotMark} 画的统一装饰环），由用户明确要求保留。</p>
     * <p>物品条目的展示物走 {@link GhostMarkerRenderer#displayItem}（标签过滤器轮播全部命中物），
     * 与「匹配设置」子窗口右上角的图标<b>同源</b>，避免两个界面显示不一致。</p>
     */
    private void renderMarkerSlot(final GuiGraphics guiGraphics, final Slot slot) {
        final MarkerEntry entry = markerEntry(menu.globalIndex(slot.index));
        if (entry.fluid()) {
            final FluidStack stack = GhostMarkerRenderer.toFluidStack(entry.id(), entry.amount());
            if (stack.isEmpty()) {
                return;
            }
            GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, stack);
        } else {
            final ItemStack stack = GhostMarkerRenderer.displayItem(entry);
            if (stack.isEmpty()) {
                return;
            }
            guiGraphics.renderItem(stack, slot.x, slot.y);
        }
    }

    /**
     * 能同时带上条目全部匹配标签的物品列表（客户端本地按标签注册表计算，无需额外网络包）。
     * <p>计算与缓存都在 {@link GhostMarkerRenderer#tagMatchedItems}（与「匹配设置」子窗口共用一份结果），
     * 本方法只做一层语义别名，调用点读起来更贴近本界面的措辞。</p>
     */
    private List<ItemStack> tagMatchedStacks(final MarkerEntry entry) {
        return GhostMarkerRenderer.tagMatchedItems(entry);
    }

    /**
     * 槽位 tooltip：AbstractContainerScreen 不会自动渲染槽位 tooltip，必须由 render 手动调用本方法。
     * <p><b>同一格只渲染一份 tooltip</b>：匹配槽（物品 / 流体 / 标签过滤器）一律走
     * {@link #renderMarkerTooltip}，内容与"服务端实际收集"同源（都用同步包里的同一条标记），
     * 不再出现"原版 tooltip 一份 + 自写一份"或数量重复两遍的情况。</p>
     * <p>空插件槽沿用与其它界面一致的“可放入升级”提示（样式照搬 RS 原版）。</p>
     */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 0) 缓存槽上的流体/气体叠加（与物品共用缓存槽）：手动渲染名称 / 数量 / 取出说明
        if (hoveredSlot != null && menu.getCarried().isEmpty()) {
            final MarkerEntry fluid = cacheFluidAt(hoveredSlot.index - CollectionCacheMenu.MATCH_WINDOW);
            if (fluid != null && fluid.fluid()) {
                renderFluidTooltip(guiGraphics, fluid, mouseX, mouseY);
                return;
            }
        }
        // 速度 / 堆叠升级槽：在物品自身 tooltip 之后追加「在本机的实际作用」，让玩家知道升级到底改变了什么
        final Component effect = upgradeEffectLine(hoveredSlot);
        if (effect != null && menu.getCarried().isEmpty() && minecraft != null && minecraft.player != null) {
            final ItemStack stack = hoveredSlot.getItem();
            final List<Component> lines = new ArrayList<>(stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(minecraft.level), minecraft.player,
                minecraft.options.advancedItemTooltips
                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
            // 「本机实际作用」是数值型附加信息 → CTRL 层（默认隐藏 + 一条提示行）
            RsccTooltipLayers.append(lines, RsccTooltipLayers.ctrl(effect));
            guiGraphics.renderTooltip(font, lines, stack.getTooltipImage(), mouseX, mouseY);
            return;
        }
        // 1) 匹配槽（ghost 标记）：物品 / 流体 / 标签过滤器统一一份 tooltip，内容来自同步包（服务端权威）
        if (hoveredSlot != null && menu.getCarried().isEmpty() && minecraft != null && minecraft.player != null
            && hoveredSlot.index >= 0 && hoveredSlot.index < CollectionCacheMenu.MATCH_WINDOW
            && menu.isMarkerWindow(hoveredSlot.index)) {
            final MarkerEntry marker = markerEntry(menu.globalIndex(hoveredSlot.index));
            if (!marker.isEmpty()) {
                renderMarkerTooltip(guiGraphics, marker, isMarkerDestroy(menu.globalIndex(hoveredSlot.index)),
                    mouseX, mouseY);
                return;
            }
        }
        // 2) 缓存槽（真实存储）：物品 tooltip（常显）+ 阻塞结论（常显）+ 「被谁阻塞」细节（Alt 层）
        if (hoveredSlot != null && menu.getCarried().isEmpty() && minecraft != null && minecraft.player != null
            && hoveredSlot.index >= CollectionCacheMenu.MATCH_WINDOW
            && hoveredSlot.index < CollectionCacheMenu.WINDOW_SLOTS && !hoveredSlot.getItem().isEmpty()) {
            final ItemStack stack = hoveredSlot.getItem();
            final List<Component> lines = new ArrayList<>(stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(minecraft.level), minecraft.player,
                minecraft.options.advancedItemTooltips
                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
            lines.addAll(itemBlockedStateLines(stack));
            RsccTooltipLayers.append(lines, itemBlockedDetailLines(stack));
            guiGraphics.renderTooltip(font, lines, stack.getTooltipImage(), mouseX, mouseY);
            return;
        }
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        // 3) 空插件槽：提示可放入的升级种类（与其它界面保持一致；本机的升级槽由容器包装，需按槽位下标兜底）
        if (hoveredSlot != null && hoveredSlot.getItem().isEmpty() && menu.getCarried().isEmpty()) {
            if (hoveredSlot instanceof UpgradeSlot upgradeSlot) {
                cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips.render(
                    upgradeSlot, guiGraphics, font, mouseX, mouseY);
            } else if (isUpgradeSlotIndex(hoveredSlot.index)) {
                cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips.render(
                    CollectionCacheMenu.allowedUpgrades(), guiGraphics, font, mouseX, mouseY);
            }
        }
    }

    /**
     * 匹配条目的完整 tooltip（<b>唯一入口</b>，一格只渲染一份）：
     * <ul>
     *     <li><b>标签过滤器</b>：显示"按标签匹配" + 全部被匹配标签（可多个）+ "会匹配到的方块 / 物品：N 种"
     *     —— 不再只显示示例物的名字（示例物只是代表物）；</li>
     *     <li><b>普通条目</b>：物品走物品自身 tooltip（名字 + NBT / 组件等全部信息），流体走流体名；</li>
     *     <li>之后统一追加数量 / 匹配 NBT 规则与阻塞状态（标签条目与普通条目<b>列出的信息一致</b>）。</li>
     * </ul>
     */
    private void renderMarkerTooltip(final GuiGraphics guiGraphics, final MarkerEntry marker,
                                     final boolean destroy, final int mouseX, final int mouseY) {
        // 常显 = 这一格到底标了什么（物品自身 tooltip / 流体名）+ 阻塞结论（+ 销毁模式结论）；
        // 其余附加信息按层收起：Shift 机制 / Ctrl 数值 / Alt 来源（见 RsccTooltipLayers）。
        final List<Component> always = new ArrayList<>(4);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(6);
        if (marker.isTagFilter()) {
            // 标签过滤器的全部标签合并成<b>一行</b>（此前每个标签占一行，选多几个就会挡住半个屏幕）
            final StringBuilder joined = new StringBuilder();
            for (final ResourceLocation tag : marker.tags()) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append('#').append(tag);
            }
            layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "marker.tag_filter")));
            // 原始 #标签 列表是「来源 / 调试」信息 → Alt 层（默认不显示，避免一屏都是 #xxx）
            layered.add(RsccTooltipLayers.alt(Component.literal(joined.toString())));
            layered.add(RsccTooltipLayers.ctrl(marker.fluid()
                ? Component.translatable(LANG + "marker.matched_fluid", tagMatchedFluidKinds(marker))
                : Component.translatable(LANG + "marker.matched", tagMatchedStacks(marker).size())));
        } else if (marker.fluid()) {
            always.add(GhostMarkerRenderer.fluidName(marker.id()));
        } else {
            final ItemStack stack = marker.displayStack();
            if (stack.isEmpty()) {
                return;
            }
            always.addAll(stack.getTooltipLines(
                net.minecraft.world.item.Item.TooltipContext.of(minecraft == null ? null : minecraft.level),
                minecraft == null ? null : minecraft.player,
                minecraft != null && minecraft.options.advancedItemTooltips
                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        }
        layered.addAll(markerRuleLines(marker));
        always.addAll(blockedStateLines(isEntryBlocked(marker)));
        layered.addAll(blockedDetailLines(isEntryBlocked(marker), isBlocked(marker.fluid(), marker.id()),
            entryBlockingTags(marker)));
        // 「直接销毁」是这一格的危险状态结论 → 常显（红字），与槽位上的红色 ✖ 标识同源
        if (destroy) {
            always.add(Component.translatable(LANG + "marker.destroy.on")
                .withStyle(net.minecraft.ChatFormatting.RED));
        }
        RsccTooltipLayers.render(guiGraphics, font, always, layered, mouseX, mouseY);
    }

    /** 该槽位下标是否是本机的插件槽（本机插件槽以容器包装，不是 UpgradeSlot 实例）。 */
    private static boolean isUpgradeSlotIndex(final int index) {
        return index >= CollectionCacheMenu.UPGRADE_START
            && index < CollectionCacheMenu.UPGRADE_START + CollectionCacheMenu.UPGRADE_COUNT;
    }

    /**
     * 匹配条目的规则说明行（数量 + 匹配 NBT）：与条目自身的名称 / 标签说明合并成<b>同一份</b> tooltip。
     * <p>这两行都是「数值/规则细节」→ <b>CTRL</b> 层，默认收起。</p>
     */
    private List<RsccTooltipLayers.Line> markerRuleLines(final MarkerEntry marker) {
        return List.of(
            RsccTooltipLayers.ctrl(Component.translatable(LANG + "marker.amount_line",
                marker.fluid() ? GhostMarkerRenderer.fluidAmount(marker.amount())
                    : String.valueOf(Math.max(1L, marker.amount())))),
            RsccTooltipLayers.ctrl(Component.translatable(marker.matchNbt()
                ? LANG + "marker.match_nbt.on" : LANG + "marker.match_nbt.off")));
    }

    /** 能同时带上条目全部匹配标签的流体种类数（客户端本地按流体标签注册表计算）。 */
    private int tagMatchedFluidKinds(final MarkerEntry marker) {
        if (!marker.isTagFilter()) {
            return 0;
        }
        final List<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>> keys = new ArrayList<>();
        for (final ResourceLocation tag : marker.tags()) {
            keys.add(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.FLUID, tag));
        }
        int count = 0;
        for (final net.minecraft.world.level.material.Fluid fluid
            : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
            if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) {
                continue;
            }
            boolean all = true;
            for (final net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> key : keys) {
                if (!fluid.builtInRegistryHolder().is(key)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                count++;
            }
        }
        return count;
    }

    /** 缓存槽流体叠加的 tooltip：流体名（常显）+ 数量 / 取出方式 / 阻塞状态（按层收起）。 */
    private void renderFluidTooltip(final GuiGraphics guiGraphics, final MarkerEntry entry,
                                    final int mouseX, final int mouseY) {
        final List<Component> always = new ArrayList<>(2);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(4);
        always.add(GhostMarkerRenderer.fluidName(entry.id()));
        layered.add(RsccTooltipLayers.ctrl(Component.translatable(LANG + "cache.fluid_amount",
            GhostMarkerRenderer.fluidAmount(entry.amount()))));
        layered.add(RsccTooltipLayers.shift(Component.translatable(
            entry.amount() >= CollectionCacheMenu.BUCKET_MB
                ? LANG + "cache.fluid_click" : LANG + "cache.fluid_insufficient")));
        final boolean blocked = isFluidBlocked(entry.id());
        always.addAll(blockedStateLines(blocked));
        layered.addAll(blockedDetailLines(blocked, isBlocked(true, entry.id()),
            blockingFluidTags(entry.id())));
        RsccTooltipLayers.render(guiGraphics, font, always, layered, mouseX, mouseY);
    }

    /** 缓存区物品的阻塞<b>结论</b>（常显：用户要求被阻塞时必须一眼看得见）。 */
    private List<Component> itemBlockedStateLines(final ItemStack stack) {
        return blockedStateLines(isItemBlocked(stack));
    }

    /** 缓存区物品的「被谁阻塞」细节（Alt 层，默认隐藏）。 */
    private List<RsccTooltipLayers.Line> itemBlockedDetailLines(final ItemStack stack) {
        final boolean byId = isBlocked(false,
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
        final List<ResourceLocation> byTags = blockingItemTags(stack);
        return blockedDetailLines(byId || !byTags.isEmpty(), byId, byTags);
    }

    /** 阻塞<b>结论</b>行（常显）：已阻塞 / 未阻塞 —— 状态永远可见，细节才分层。 */
    private static List<Component> blockedStateLines(final boolean blocked) {
        return List.of(Component.translatable(LANG + (blocked ? "blocked.on" : "blocked.off")));
    }

    /**
     * 「阻塞」的<b>细节</b>行：为什么不可回流 + 被谁挡住。
     * <p>说明行（{@code blocked.mark}）归 <b>SHIFT</b>；「具体资源被精确阻塞」与「命中了哪些被阻塞标签」
     * 属于来源 / 调试信息，归 <b>ALT</b>。判定与服务端完全同一份名单（见 {@link #isItemBlocked} /
     * {@link #isFluidBlocked} / {@link #isEntryBlocked}），因此不可能出现「实际被挡住、界面却说未阻塞」。</p>
     */
    private List<RsccTooltipLayers.Line> blockedDetailLines(final boolean blocked, final boolean byId,
                                                            final List<ResourceLocation> byTags) {
        if (!blocked) {
            return List.of();
        }
        final List<RsccTooltipLayers.Line> lines = new ArrayList<>(3);
        lines.add(RsccTooltipLayers.shift(Component.translatable(LANG + "blocked.mark")));
        if (byId) {
            lines.add(RsccTooltipLayers.alt(Component.translatable(LANG + "blocked.by_id")));
        }
        if (byTags != null && !byTags.isEmpty()) {
            final StringBuilder joined = new StringBuilder();
            for (final ResourceLocation tag : byTags) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append('#').append(tag);
            }
            lines.add(RsccTooltipLayers.alt(
                Component.translatable(LANG + "blocked.by_tag", joined.toString())));
        }
        return lines;
    }

    /**
     * 自绘信息元素的 tooltip（收集范围 / 输入面 / 经验形态 / 两个新开关）；
     * 判定与绘制一致，**每处只给必要行**。
     * <p>第 7 轮按用户要求删掉了「匹配区」「缓存区」这两条<b>讲解型</b>说明
     * （它们只是在解释界面元素是什么），保留收集范围 / 输入面 / 经验形态 / 两个开关这些
     * 「点了会发生什么、前置条件是什么」的必要提示。</p>
     * <p>所有 tooltip 一律<b>手动渲染</b>（本模组的 GUI 不会自动渲染 tooltip）。</p>
     */
    private void renderCacheTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 0) 经验存储形态按钮：当前值 + 两种折算目标各存成什么；缺前置的形态**明确说明缺哪个前置**（不是静默不可用）
        if (xpFormButton != null && xpFormButton.isMouseOver(mouseX, mouseY)) {
            final List<RsccTooltipLayers.Line> lines = new ArrayList<>();
            lines.add(RsccTooltipLayers.shift(Component.translatable(LANG + "xp_form.tip.current",
                Component.translatable(xpFormKey(menu.getXpForm())))));
            lines.add(RsccTooltipLayers.shift(Component.translatable(LANG + "xp_form.tip.orb")));
            // 液态：装了「机械动力：附魔工业」才可选；没装时补一行「不可选 + 缺哪个前置」
            // （用户要求：未安装该前置时该选项不可选，且必须说明缺少哪个前置）。
            final Component missing = XpForm.LIQUID.missingDependency();
            if (missing == null) {
                lines.add(RsccTooltipLayers.shift(Component.translatable(LANG + "xp_form.tip.liquid")));
            } else {
                lines.add(RsccTooltipLayers.shift(Component.translatable(LANG + "xp_form.tip.liquid.unavailable")));
                lines.add(RsccTooltipLayers.shift(missing));
            }
            RsccTooltipLayers.render(guiGraphics, font, lines, mouseX, mouseY);
            return;
        }
        // 0.1) 输入面入口按钮（原版 Button 不会自动渲染 tooltip，必须手动调用）
        if (inputFaceButton != null && inputFaceButton.isMouseOver(mouseX, mouseY)) {
            // 说明=机制（Shift）；「当前允许几个面」=数值（Ctrl）
            RsccTooltipLayers.render(guiGraphics, font, List.of(
                RsccTooltipLayers.shift(Component.translatable(LANG + "input_face.tip")),
                RsccTooltipLayers.ctrl(Component.translatable(LANG + "input_face.state", inputFaceCount()))),
                mouseX, mouseY);
            return;
        }
        // 0.2) 收集范围入口按钮（原版 Button 不会自动渲染 tooltip，必须手动调用；按钮不在槽位区，先判）
        if (rangeButton != null && rangeButton.isMouseOver(mouseX, mouseY)) {
            // 三轴数值与耗电都是「数值细节」→ Ctrl 层
            RsccTooltipLayers.render(guiGraphics, font, List.of(
                RsccTooltipLayers.ctrl(Component.translatable(LANG + "range.tip",
                    menu.getCollectRadius(0), menu.getCollectRadius(1), menu.getCollectRadius(2))),
                RsccTooltipLayers.ctrl(Component.translatable(LANG + "range.energy",
                    cretae.cookiewyq.rs_create_compat.Config.collectionCacheEnergyPerRadius
                        * (menu.getCollectRadius(0) + menu.getCollectRadius(1)
                            + menu.getCollectRadius(2) - 3)))), mouseX, mouseY);
            return;
        }
        // 0.3) 「吸取所有物品」开关：当前状态 + 它开启后匹配区失效（优先级说明）
        if (collectAllButton != null && collectAllButton.isMouseOver(mouseX, mouseY)) {
            RsccTooltipLayers.render(guiGraphics, font, List.of(
                RsccTooltipLayers.shift(Component.translatable(LANG + "collect_all.tip")),
                RsccTooltipLayers.shift(Component.translatable(menu.isCollectAll()
                    ? LANG + "collect_all.on" : LANG + "collect_all.off")),
                RsccTooltipLayers.shift(Component.translatable(LANG + "collect_all.priority"))),
                mouseX, mouseY);
            return;
        }
        // 0.4) 「反转匹配」开关：当前状态；被「吸取所有物品」禁用时明确说明原因（第三态）
        if (invertMatchButton != null && invertMatchButton.isMouseOver(mouseX, mouseY)) {
            final boolean disabled = menu.isInvertMatchDisabled();
            RsccTooltipLayers.render(guiGraphics, font, List.of(
                RsccTooltipLayers.shift(Component.translatable(LANG + "invert.tip")),
                RsccTooltipLayers.shift(Component.translatable(menu.isInvertMatch()
                    ? LANG + "invert.on" : LANG + "invert.off")),
                RsccTooltipLayers.shift(Component.translatable(
                    disabled ? LANG + "invert.disabled.tip" : LANG + "invert.enabled.tip"))),
                mouseX, mouseY);
            return;
        }
        // 鼠标压在槽位上时让槽位 tooltip 独占，避免与自绘提示叠加
        if (hoveredSlot != null) {
            return;
        }
        // 删除模式复选框：常显 = 规则复述（必须命中匹配槽 + 该槽勾了标记为删除）；
        // Ctrl 层 = 当前速率（随速度 / 堆叠升级变化）—— 与用户「速率受升级影响」的要求同源。
        if (deleteModeButton != null && deleteModeButton.isMouseOver(mouseX, mouseY)) {
            RsccTooltipLayers.render(guiGraphics, font, List.of(
                RsccTooltipLayers.shift(Component.translatable(LANG + "delete_mode.tip")),
                RsccTooltipLayers.ctrl(Component.translatable(LANG + "delete_mode.rate",
                    menu.getDeleteRatePerTick()))),
                mouseX, mouseY);
            return;
        }
    }

    /**
     * 升级槽内「速度 / 堆叠升级」在本机上的实际作用说明行（不是这两者时返回 null）。
     * <p>物品自身的 tooltip 只描述升级的通用语义，这里补上本机的真实数值：
     * 速度升级同时提升「吸取速度（扫描频率）」与「缓存→网络回流速度」；堆叠升级提升
     * 「缓存每格上限」与「单次吞吐」。</p>
     */
    @Nullable
    private Component upgradeEffectLine(final Slot slot) {
        if (!(slot instanceof UpgradeSlot) || slot.getItem().isEmpty()) {
            return null;
        }
        final net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return null;
        }
        return switch (id.getPath()) {
            case "speed_upgrade" -> Component.translatable(LANG + "upgrade.speed.effect",
                menu.getSpeedUpgradeCount());
            case "stack_upgrade" -> Component.translatable(LANG + "upgrade.stack.effect",
                menu.getStackUpgradeCount(), menu.getCacheSlotCapacity());
            default -> null;
        };
    }

    /** 手动渲染多行 tooltip（自绘元素专用：本模组的 GUI 不会自动渲染 tooltip）。 */
    private void renderLines(final GuiGraphics guiGraphics, final List<Component> lines,
                             final int mouseX, final int mouseY) {
        GhostMarkerRenderer.renderLines(guiGraphics, font, lines, mouseX, mouseY);
    }

    // ==================== 输入转发 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        for (final SptScrollbarWidget scrollbar : scrollbars) {
            if (scrollbar.visible && scrollbar.mouseClicked(mouseX, mouseY, button)) {
                if (scrollbar == matchScrollbar) {
                    framesSinceMatchScroll = 0;
                } else if (scrollbar == cacheScrollbar) {
                    framesSinceCacheScroll = 0;
                }
                return true;
            }
        }
        final Slot hovered = hoveredSlot;
        // Ctrl + 左键：切换该资源的「阻塞」开关（标记槽 / 缓存槽都支持；详见 tooltip 提示）
        if (button == 0 && hasControlDown() && hovered != null && menu.getCarried().isEmpty()) {
            if (hovered.index < CollectionCacheMenu.MATCH_WINDOW && menu.isMarkerWindow(hovered.index)) {
                final MarkerEntry entry = markerEntry(menu.globalIndex(hovered.index));
                if (!entry.isEmpty()) {
                    // 按标签注册的条目：把标签一起交给服务端，阻塞才会覆盖整个标签族（金板因此被挡住）
                    sendBlockedToggle(entry.fluid(), entry.id(), entry.tags());
                    return true;
                }
            } else if (hovered.index >= CollectionCacheMenu.MATCH_WINDOW
                && hovered.index < CollectionCacheMenu.WINDOW_SLOTS) {
                final ItemStack stack = hovered.getItem();
                if (!stack.isEmpty()) {
                    sendBlockedToggle(false,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
                    return true;
                }
                final MarkerEntry fluid = cacheFluidAt(hovered.index - CollectionCacheMenu.MATCH_WINDOW);
                if (fluid != null && fluid.fluid()) {
                    sendBlockedToggle(true, fluid.id());
                    return true;
                }
            }
        }
        // 缓存槽上的流体叠加（左键）：用空容器换出「装满的容器」——光有缓存取不出东西，
        // 必须先有容器（与 RS 一致：没容器就什么也不发生、也不提示）。
        if (button == 0 && hovered != null) {
            final MarkerEntry fluid = cacheFluidAt(hovered.index - CollectionCacheMenu.MATCH_WINDOW);
            if (fluid != null && fluid.fluid()) {
                final ItemStack carried = menu.getCarried();
                // 空手：交给服务端去背包里找容器（服务端权威，找不到就静默）；手上有容器：
                // 只在「这件容器装得下这种流体」时才把点击当作取出，否则让给槽位本身
                // （玩家可能只是想把手上的东西放进缓存）。
                if (carried.isEmpty() || cursorAccepts(fluid, carried)) {
                    if (fluid.amount() >= CollectionCacheMenu.BUCKET_MB) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new cretae.cookiewyq.rs_create_compat.network.ExtractCollectionFluidPacket(
                                menu.containerId, fluid.id(), fluid.nbt()));
                    }
                    return true;
                }
            }
        }
        if (button == 0 && hovered != null
            && hovered.index < CollectionCacheMenu.MATCH_WINDOW && menu.isMarkerWindow(hovered.index)) {
            final int markerIndex = menu.globalIndex(hovered.index);
            final ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                // RS 原版：空手左键点击已有过滤器的槽 → 打开独立配置界面（数量/规则）
                if (!hasShiftDown() && !markerEntry(markerIndex).isEmpty()) {
                    openMarkerConfig(markerIndex);
                    return true;
                }
                // 空手 + Shift 或空槽：交回菜单 clicked()，由服务端清除标记
            } else if (menu.isMarkerItemPresent(carried)) {
                // 客户端前置拦截：同一物品只能标记一次
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                        LANG + "duplicate"), true);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
     * 拖动转发：MC 在「按住鼠标移动」时只派发 {@code mouseDragged}（不派发 {@code mouseMoved}），
     * 自绘滚动条不是原版子控件，必须由本界面显式转发 —— 否则按住拖动期间滑块收不到任何事件
     * （用户反复反馈的「滚动条拖不动」的根因）。
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

    /** 本地滚动交互开始时重置对应的「自持帧计数」（拖动/点击/滚轮共用）。 */
    private void resetScrollFrames(final SptScrollbarWidget scrollbar) {
        if (scrollbar == matchScrollbar) {
            framesSinceMatchScroll = 0;
        } else if (scrollbar == cacheScrollbar) {
            framesSinceCacheScroll = 0;
        }
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

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double delta) {
        // 匹配区滚轮
        if (x >= leftPos + MATCH_PANEL_X1 && x <= leftPos + MATCH_PANEL_X2
            && y >= topPos + MATCH_PANEL_Y1 && y <= topPos + MATCH_PANEL_Y2
            && matchScrollbar.mouseScrolled(x, y, scrollX, delta)) {
            framesSinceMatchScroll = 0;
            return true;
        }
        // 缓存区滚轮
        if (x >= leftPos + CACHE_PANEL_X1 && x <= leftPos + CACHE_PANEL_X2
            && y >= topPos + CACHE_PANEL_Y1 && y <= topPos + CACHE_PANEL_Y2
            && cacheScrollbar.mouseScrolled(x, y, scrollX, delta)) {
            framesSinceCacheScroll = 0;
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, delta);
    }
}
