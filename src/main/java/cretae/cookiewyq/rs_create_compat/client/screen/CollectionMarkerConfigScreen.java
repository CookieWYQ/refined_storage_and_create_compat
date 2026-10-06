package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.SptScrollbarWidget;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerConfigPacket;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 归流缓存仓「匹配条目配置」界面：对某条 ghost 标记编辑需求数量与匹配规则。
 * <p><b>本轮重构（用户最终版）</b>：把旧的「手填标签名 + 应用/清除」改成
 * <b>「匹配标签」开关 + 该物品自带标签的多选列表</b>——放入示例物（如"橡木原木"）后打开本界面，
 * 打开「匹配标签」开关即可从示例物身上的全部标签里勾选一个或多个，选中的集合就是这一条的匹配条件
 * （命中物必须同时带上集合里的每个标签）；标签、匹配 NBT、数量三者<b>互相兼容</b>可同时生效。
 * 勾选后主界面的匹配槽会在「所有能被命中的物品」之间轮询显示，让玩家直观看到会匹配到什么。</p>
 * <p>自绘面板 PNG（{@code tmp_textures/COLLECTION_MARKER_CONFIG_GUI_DOC_V2.md} 约定 256×220）；
 * 尺寸不符时基类自动回退原版九宫格。所有开关统一为「一行文本 + 一个 ✓/✗ 按钮」；
 * 所有文字无阴影，tooltip 全部手动渲染且 hover 判定与绘制范围同源。</p>
 * <p>右上角资源图标与主界面<b>同源</b>（{@link GhostMarkerRenderer#displayItem}）：标签过滤器在全部命中物
 * 之间轮播，普通条目显示示例物，流体走流体贴图 —— 两个界面必然显示同一个物品。</p>
 */
public class CollectionMarkerConfigScreen extends ChildConfigScreen {
    private static final int PANEL_W = 256;
    private static final int PANEL_H = 220;

    /** 自绘面板 PNG（约定 256×220）；缺失 / 尺寸不符时退回原版九宫格。 */
    private static final ResourceLocation CUSTOM_PANEL =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "textures/gui/collection_cache/marker_config.png");

    @Override
    @Nullable
    protected ResourceLocation customPanel() {
        return CUSTOM_PANEL;
    }

    // ===== 布局常量（全部为 Menu 坐标 = 文档“精灵坐标 + 1”） =====
    private static final int ICON_X = 232;
    private static final int ICON_Y = 6;
    /**
     * 数量输入框：Menu (76,28) 148×16。
     * <p><b>本轮右移</b>（44/180 → 76/148）：左侧「数量」标签在流体条目下是「数量 (mB)」，
     * 英文语言下更长（{@code Amount (mB)} 约 62px，从 x=8 起画到 x≈70）—— 旧输入框从 x=44 起会与
     * 后面的「(mB)」单位重叠（用户实测）。右移到 76 后中英两种语言下都留出 ≥6px 间隙；
     * 宽度同步收窄以保右缘仍在 224（不侵入右上角资源图标 232 起的 16×16）。</p>
     */
    private static final int AMOUNT_BOX_X = 76;
    private static final int AMOUNT_BOX_Y = 28;
    private static final int AMOUNT_BOX_W = 148;
    private static final int INCREMENT_Y = 50;
    private static final int INCREMENT_W = 26;
    private static final int INCREMENT_GAP = 2;
    /** 三个开关行（文本 + ✓/✗ 按钮）：匹配 NBT / 阻塞 / 匹配标签。 */
    private static final int NBT_ROW_Y = 76;
    private static final int NBT_BTN_X = 232;
    private static final int NBT_BTN_Y = 70;
    private static final int BLOCK_ROW_Y = 95;
    private static final int BLOCK_BTN_X = 232;
    private static final int BLOCK_BTN_Y = 89;
    private static final int TAGS_ROW_Y = 114;
    private static final int TAGS_BTN_X = 232;
    private static final int TAGS_BTN_Y = 108;
    /**
     * 第四行「直接销毁」开关：画在<b>标题行右端留白</b>（标签 x150 / ✓✗ 按钮 x210，避开右上角资源图标 232）。
     * <p>危险开关：点击第一次只进入「待确认」态（标签变红提示、预览行显示不可恢复警告），
     * 第二次点击才真正开启并发包；关闭则一步到位（恢复安全状态，无需确认）。</p>
     */
    /**
     * 「直接销毁」开关：标签 + 紧贴它右侧的按钮。
     * <p>2026-10-05 用户反馈「直接销毁那几个字和那个按钮离的还是太远了，把按钮靠近直接销毁」
     * ⇒ 按钮从 210 移到 184，标签右缘与按钮左缘只剩 {@value #DESTROY_LABEL_GAP} px。
     * 同时标签按像素截断，英文 / 待确认态的红字也不会压到按钮上。</p>
     */
    private static final int DESTROY_LABEL_X = 150;
    private static final int DESTROY_BTN_X = 184;
    private static final int DESTROY_BTN_Y = 5;
    /** 标签右缘与按钮左缘之间的间距（用户要求「靠近」）。 */
    private static final int DESTROY_LABEL_GAP = 2;
    private static final int TOGGLE_BTN_W = 20;
    private static final int TOGGLE_BTN_H = 14;
    /** 标签多选列表（可滚动）。 */
    private static final int LIST_X = 8;
    private static final int LIST_Y = 130;
    private static final int LIST_W = 226;
    private static final int LIST_H = 50;
    private static final int ROW_H = 10;
    private static final int VISIBLE_ROWS = LIST_H / ROW_H;
    private static final int SCROLL_X = 234;
    private static final int SCROLL_Y = LIST_Y;
    private static final int SCROLL_W = 10;
    /** 命中预览行（列表下方）。 */
    private static final int PREVIEW_Y = 184;
    private static final int ACTION_Y = 196;
    private static final int HALF_W = 116;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_DIM = 0xFF6A6A6A;
    private static final int COLOR_ROW_SEL = 0xFF3C6E3C;
    private static final int COLOR_ROW_TEXT_SEL = 0xFFEAF7EA;
    private static final int COLOR_ROW_HOVER = 0x30FFFFFF;

    /** RS 原版增量序列：+1/+10/+64 与 -1/-10/-64。 */
    private static final int[] INCREMENTS = {-64, -10, -1, 1, 10, 64};

    private final int containerId;
    private final int markerIndex;
    private final MarkerEntry entry;
    private final long initialAmount;

    private EditBox amountBox;
    /** 本地编辑中的「匹配 NBT」开关（确认时发给服务端）。 */
    private boolean matchNbt;
    private Button matchNbtButton;
    /** 本地镜像的「阻塞」开关（服务端权威值经 {@link #applyBlockedSync} 回填）。 */
    private boolean blocked;
    private Button blockedButton;
    /** 本地编辑中的「匹配标签」开关。 */
    private boolean tagMode;
    private Button tagsButton;
    /** 示例物自带的全部标签（按名称排序，供多选）。 */
    private final List<ResourceLocation> availableTags = new ArrayList<>();
    /** 已选中的匹配标签（保持与 availableTags 一致的顺序）。 */
    private final Set<ResourceLocation> selectedTags = new LinkedHashSet<>();
    /** 列表首个可见行下标。 */
    private int firstVisibleRow;
    private SptScrollbarWidget scrollbar;
    /** 当前选中标签集合能命中的物品种类数（-1 = 尚未计算）。 */
    private int matchedPreview = -1;
    /** 「直接销毁」开关的本地镜像（服务端权威值回填；开启必须二次确认）。 */
    private boolean destroy;
    /** 是否处于「待确认」态（第一次点击后、第二次点击前；此期间不发任何包）。 */
    private boolean destroyArmed;
    private Button destroyButton;

    public CollectionMarkerConfigScreen(final Screen parent,
                                        final int containerId,
                                        final int markerIndex,
                                        final MarkerEntry entry,
                                        final boolean blocked,
                                        final boolean destroy) {
        super(Component.translatable("gui.rs_create_compat.collection_cache.config_title"),
            parent, PANEL_W, PANEL_H);
        this.containerId = containerId;
        this.markerIndex = markerIndex;
        this.entry = entry;
        this.initialAmount = Math.max(1L, entry.amount());
        this.matchNbt = entry.matchNbt();
        this.blocked = blocked;
        this.destroy = destroy;
        this.tagMode = entry.isTagFilter();
        this.availableTags.addAll(tagsOf(entry));
        this.selectedTags.addAll(entry.tags());
    }

    /** 示例物（物品 / 方块物品按物品标签、流体按流体标签）身上携带的全部标签。 */
    private static List<ResourceLocation> tagsOf(final MarkerEntry entry) {
        final List<ResourceLocation> tags = new ArrayList<>();
        if (entry.fluid()) {
            final Fluid fluid = entry.id() == null ? null : BuiltInRegistries.FLUID.get(entry.id());
            if (fluid != null && fluid != Fluids.EMPTY) {
                BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags()
                    .forEach((TagKey<Fluid> tag) -> tags.add(tag.location()));
            }
        } else {
            final Item item = entry.id() == null ? null : BuiltInRegistries.ITEM.get(entry.id());
            if (item != null && item != Items.AIR) {
                BuiltInRegistries.ITEM.wrapAsHolder(item).tags()
                    .forEach((TagKey<Item> tag) -> tags.add(tag.location()));
            }
        }
        tags.sort((a, b) -> a.toString().compareTo(b.toString()));
        return tags;
    }

    @Override
    protected void init() {
        super.init();

        // 数量输入框：仅允许数字
        amountBox = new EditBox(font, px + AMOUNT_BOX_X, py + AMOUNT_BOX_Y,
            AMOUNT_BOX_W, 16, Component.translatable("gui.rs_create_compat.collection_cache.amount"));
        amountBox.setMaxLength(9);
        amountBox.setTextShadow(false);
        amountBox.setValue(Long.toString(initialAmount));
        amountBox.setResponder(text -> {
            if (!text.matches("\\d*")) {
                amountBox.setValue(text.replaceAll("\\D", ""));
            }
        });
        addRenderableWidget(amountBox);
        setFocused(amountBox);

        // 增量按钮（RS 原版顶部 1/10/64、底部 -1/-10/-64）
        for (int i = 0; i < INCREMENTS.length; i++) {
            final int increment = INCREMENTS[i];
            final int x = px + 8 + i * (INCREMENT_W + INCREMENT_GAP);
            addRenderableWidget(new Button.Builder(
                Component.literal((increment > 0 ? "+" : "") + increment),
                button -> changeAmount(increment))
                .bounds(x, py + INCREMENT_Y, INCREMENT_W, 16).build());
        }

        // 匹配 NBT（数据组件严格匹配）：与标签匹配可同时生效
        matchNbtButton = new Button.Builder(McGui.toggleLabel(matchNbt), button -> {
            matchNbt = !matchNbt;
            button.setMessage(McGui.toggleLabel(matchNbt));
        }).bounds(px + NBT_BTN_X, py + NBT_BTN_Y, TOGGLE_BTN_W, TOGGLE_BTN_H).build();
        addRenderableWidget(matchNbtButton);

        // 阻塞（该条目对应的资源）：与主界面 Ctrl+左键是同一份服务端权威名单
        blockedButton = McGui.toggle(px + BLOCK_BTN_X, py + BLOCK_BTN_Y,
            TOGGLE_BTN_W, TOGGLE_BTN_H, () -> blocked, this::sendBlockedToggle);
        addRenderableWidget(blockedButton);

        // 匹配标签开关：打开后即可在下方列表里多选该物品自带的标签
        tagsButton = McGui.toggle(px + TAGS_BTN_X, py + TAGS_BTN_Y,
            TOGGLE_BTN_W, TOGGLE_BTN_H, () -> tagMode, value -> {
                final boolean wasOn = tagMode;
                tagMode = value;
                if (value && !wasOn) {
                    resetUnusedAmountForTagFilter();
                }
                refreshPreview();
            });
        addRenderableWidget(tagsButton);

        // 「直接销毁」开关：开启是危险操作 —— 第一次点击只进入「待确认」态（不发包），
        // 第二次点击才真正开启并发包；关闭一步到位（恢复到安全状态，无需确认）。
        destroyButton = McGui.toggle(px + DESTROY_BTN_X, py + DESTROY_BTN_Y,
            TOGGLE_BTN_W, TOGGLE_BTN_H, () -> destroy, value -> {
                if (!value) {
                    destroyArmed = false;
                    destroy = false;
                    sendMarkerDestroy(false);
                } else if (!destroyArmed) {
                    destroyArmed = true; // 第一次点击：只提醒，不发包
                } else {
                    destroyArmed = false;
                    destroy = true;
                    sendMarkerDestroy(true); // 第二次点击：确认开启
                }
            });
        addRenderableWidget(destroyButton);

        // 标签列表滚动条（轨道贴列表右缘；只有条目多于可见行时才可交互）
        scrollbar = new SptScrollbarWidget(px + SCROLL_X, py + SCROLL_Y,
            SptScrollbarWidget.Type.NORMAL, LIST_H, SCROLL_W);
        scrollbar.setListener(offset -> firstVisibleRow = (int) Math.round(offset));
        refreshPreview();

        // 确认 / 取消
        addRenderableWidget(new Button.Builder(
            Component.translatable("gui.rs_create_compat.collection_cache.confirm"),
            button -> confirmAndReturn())
            .bounds(px + 8, py + ACTION_Y, HALF_W, 16).build());
        addRenderableWidget(new Button.Builder(
            Component.translatable("gui.rs_create_compat.collection_cache.cancel"),
            button -> returnToParent())
            .bounds(px + 132, py + ACTION_Y, HALF_W, 16).build());
    }

    // ==================== 标签集合 / 预览 ====================

    /**
     * 生效的匹配标签集合：开关关闭 / 未勾选任何标签时为**空**（= 只看这一个具体物品）。
     * <p>只下发「示例物自身确实带有」的标签（{@link #availableTags}）：标签过滤器按 AND 判定，
     * 一旦集合里混入示例物不自带的标签，这个集合<b>永远不可能被满足</b> —— 连示例物本身都命中不了，
     * 服务端就会表现为「界面看起来是标签匹配，但一条都吸不进来」。服务端会再做一次同样的收敛（权威兜底）。</p>
     */
    private List<ResourceLocation> effectiveTags() {
        if (!tagMode || selectedTags.isEmpty()) {
            return List.of();
        }
        final List<ResourceLocation> tags = new ArrayList<>();
        for (final ResourceLocation tag : availableTags) {
            if (selectedTags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** 重算「当前选中标签集合能命中多少种资源」（物品条目数物品、流体条目数流体；开关关闭 / 未选标签时为 -1）。 */
    private void refreshPreview() {
        matchedPreview = -1;
        if (!tagMode || selectedTags.isEmpty()) {
            return;
        }
        matchedPreview = entry.fluid() ? countMatchingFluids() : countMatchingItems();
    }

    /**
     * 能同时带上全部选中标签的物品种类数。
     * <p><b>为什么必须区分物品 / 流体</b>：流体条目拿到的是<b>流体标签</b>，此前一律按物品标签统计，
     * 于是「勾了标签却显示可匹配 0 种物品」，看起来像标签匹配根本不生效。</p>
     */
    private int countMatchingItems() {
        final List<TagKey<Item>> keys = new ArrayList<>();
        for (final ResourceLocation tag : selectedTags) {
            keys.add(TagKey.create(Registries.ITEM, tag));
        }
        int count = 0;
        for (final Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            boolean all = true;
            for (final TagKey<Item> key : keys) {
                if (!item.builtInRegistryHolder().is(key)) {
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

    /** 能同时带上全部选中标签的流体种类数（与物品条目分开统计，见 {@link #countMatchingItems()}）。 */
    private int countMatchingFluids() {
        final List<TagKey<Fluid>> keys = new ArrayList<>();
        for (final ResourceLocation tag : selectedTags) {
            keys.add(TagKey.create(Registries.FLUID, tag));
        }
        int count = 0;
        for (final Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) {
                continue;
            }
            boolean all = true;
            for (final TagKey<Fluid> key : keys) {
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

    /** 切换某标签的选中状态。 */
    private void toggleTag(final ResourceLocation tag) {
        if (!selectedTags.remove(tag)) {
            selectedTags.add(tag);
        }
        refreshPreview();
    }

    /**
     * 打开「匹配标签」时，把<b>还没被玩家改过</b>的数量（= 标记时沿用的手持堆叠数量，常见 64）归 1。
     * <p><b>为什么</b>：标签过滤器收集的是「一整类资源」，而数量是「一次要凑齐多少才下手」的门槛。
     * 沿用手持堆叠数会让「只丢进来两三个」的场面<b>一件都吸不进来</b>，玩家看到的现象就是
     * 「标签匹配没生效」；归 1 后只要该类资源出现就会被收集，想批量凑数仍可自行改大。</p>
     * <p>只在数量框仍是初始值（玩家没动过）时归 1，绝不覆盖玩家已经填好的数字。</p>
     */
    private void resetUnusedAmountForTagFilter() {
        if (amountBox == null || initialAmount <= 1L) {
            return;
        }
        if (amountBox.getValue().trim().equals(Long.toString(initialAmount))) {
            amountBox.setValue("1");
        }
    }

    private void maxScroll() {
        if (scrollbar == null) {
            return;
        }
        final int max = Math.max(0, availableTags.size() - VISIBLE_ROWS);
        scrollbar.setMaxOffset(max);
        scrollbar.setEnabled(max > 0);
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow, max));
        scrollbar.setOffset(firstVisibleRow);
    }

    /** 标签列表行矩形（Menu 坐标 → 屏幕坐标）。 */
    private boolean inList(final double mouseX, final double mouseY) {
        return mouseX >= px + LIST_X && mouseX < px + LIST_X + LIST_W
            && mouseY >= py + LIST_Y && mouseY < py + LIST_Y + LIST_H;
    }

    /** 鼠标压在列表第几行（-1 = 没有）。 */
    private int rowAt(final double mouseX, final double mouseY) {
        if (!inList(mouseX, mouseY)) {
            return -1;
        }
        final int row = (int) ((mouseY - (py + LIST_Y)) / ROW_H);
        final int index = firstVisibleRow + row;
        return row >= 0 && row < VISIBLE_ROWS && index < availableTags.size() ? row : -1;
    }

    /** 列表滚动：把可见窗口移动 delta 行（不触发服务端交互）。 */
    private void scrollRows(final int delta) {
        final int max = Math.max(0, availableTags.size() - VISIBLE_ROWS);
        firstVisibleRow = Math.max(0, Math.min(firstVisibleRow + delta, max));
    }

    // ==================== 服务端交互 ====================

    /** 统一开关样式：✓ 绿 / ✗ 红（共用 {@link McGui#toggleLabel}，全工程一致）。 */
    private static Component toggleLabel(final boolean on) {
        return McGui.toggleLabel(on);
    }

    /**
     * 切换本条目对应资源的「阻塞」开关（与主界面 Ctrl+左键共用同一条 C2S 协议）。
     * <p><b>本轮修复（用户第 ③ 条）</b>：按标签注册的条目（{@code tagMode}）把<b>当前选中的标签集合</b>
     * 一并下发 —— 服务端据此把标签写进「按标签阻塞」名单，于是属于该标签的<b>所有</b>资源
     * （含金板）都被挡住而留在缓存里，不再瞬间回流进网络。</p>
     */
    private void sendBlockedToggle(final boolean value) {
        blocked = value;
        if (entry.id() == null) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.SetCollectionBlockedPacket(
                containerId, entry.fluid(), entry.id(), value,
                tagMode ? effectiveTags() : List.of()));
    }

    /**
     * 发送「直接销毁」开关到服务端。
     * <p>只有<b>经过二次确认</b>的调用点才会走到这里（开启：第二次点击；关闭：一次点击），
     * 因此一律带 {@code confirmed = true}；服务端仍会对「开启未确认」做硬校验。</p>
     */
    private void sendMarkerDestroy(final boolean value) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerDestroyPacket(
                containerId, markerIndex, value, true));
    }

    /**
     * S2C：把主界面的匹配窗口快照（含「直接销毁」标志）<b>转交父界面</b>。
     * <p>本窗口开在最前时主界面收不到同步包，若不转交，关闭本窗口后主界面的销毁标识会停留在旧值；
     * 转交后主界面始终显示服务端权威状态（只读展示，不写任何服务端状态）。</p>
     */
    public void applyMarkerWindowSync(final int windowStart, final List<MarkerEntry> entries,
                                      final List<Boolean> destroyFlags) {
        if (parent instanceof CollectionCacheScreen parentScreen) {
            parentScreen.setMarkerEntries(windowStart, entries, destroyFlags);
        }
    }

    /**
     * S2C：服务端权威「阻塞名单」到达时刷新本行开关，并转交父界面刷新主界面标识。
     * <p>按标签注册的条目按<b>标签</b>判定（全部选中标签都在阻塞名单里才算已阻塞），
     * 与主界面的红色标识同一套口径。</p>
     */
    public void applyBlockedSync(final List<ResourceLocation> items, final List<ResourceLocation> fluids,
                                 final List<ResourceLocation> itemTags, final List<ResourceLocation> fluidTags) {
        if (entry.id() != null) {
            final List<ResourceLocation> list = entry.fluid() ? fluids : items;
            final List<ResourceLocation> tagList = entry.fluid() ? fluidTags : itemTags;
            final boolean byId = list != null && list.contains(entry.id());
            final boolean byTag = tagMode && tagList != null && !tagList.isEmpty()
                && tagList.containsAll(effectiveTags());
            blocked = byId || byTag;
        }
        if (parent instanceof CollectionCacheScreen parentScreen) {
            parentScreen.setBlocked(items, fluids, itemTags, fluidTags);
        }
    }

    /** 按 RS 原版 {@code AbstractAmountScreen#correctDelta} 的语义调整增量。 */
    private void changeAmount(final int delta) {
        long current = 1L;
        try {
            current = Math.max(1L, Long.parseLong(amountBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
            // 输入非法时按 1 处理
        }
        final long corrected = current == 1L && delta > 1 ? delta - 1L : delta;
        amountBox.setValue(Long.toString(Math.max(1L, current + corrected)));
    }

    /** 确认：一次性下发数量 / 匹配 NBT / 匹配标签集合（服务端权威）。 */
    private void confirmAndReturn() {
        final int amount = parseAmount();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new SetCollectionMarkerConfigPacket(containerId, markerIndex, entry.fluid(), entry.id(),
                entry.nbt(), amount, matchNbt, effectiveTags()));
        returnToParent();
    }

    private int parseAmount() {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, Long.parseLong(amountBox.getValue().trim())));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        maxScroll();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 标题 / 被标记物图标（深色字配原版浅灰面板，均无阴影）
        guiGraphics.drawString(font, title, px + 8, py + 8, COLOR_TITLE, false);
        // 右上角资源图标：与主界面<b>同源</b>（GhostMarkerRenderer.displayItem）——
        // 标签过滤器在「全部命中物」之间轮播，普通条目显示被标记的示例物，流体走流体贴图。
        // 此前这里直接取 MarkerEntry#displayStack()（只认静态代表物、对流体/不可解析 id 返回空栈），
        // 数据源与主界面不同 → 标签模式下主界面正常轮播而本图标显示异常（用户实测）。
        final boolean fluid = entry.fluid();
        if (fluid) {
            GhostMarkerRenderer.renderFluid(guiGraphics, px + ICON_X, py + ICON_Y,
                GhostMarkerRenderer.toFluidStack(entry.id(), initialAmount));
        } else {
            guiGraphics.renderItem(GhostMarkerRenderer.displayItem(entry), px + ICON_X, py + ICON_Y);
        }
        // 「被阻塞」标识：与主界面槽位<b>同一套视觉语言</b>（同一个 McGui.blockedSlotMark）——
        // 用户要求「侧栏 / 图形界面里也要能看到被阻塞的格」。装饰环画在 16×16 图标之外，
        // 不覆盖资源图标本身；图标区在面板右上角（232..248），外扩 2px 仍完全在面板内。
        if (blocked) {
            McGui.blockedSlotMark(guiGraphics, px + ICON_X, py + ICON_Y);
        }
        guiGraphics.drawString(font, Component.translatable(fluid
                ? "gui.rs_create_compat.collection_cache.amount_mb"
                : "gui.rs_create_compat.collection_cache.amount"),
            px + 8, py + 32, COLOR_TEXT, false);

        // 三个开关行的文本
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.collection_cache.match_nbt"),
            px + 8, py + NBT_ROW_Y + 3, COLOR_TEXT, false);
        if (matchNbtButton != null) {
            matchNbtButton.setMessage(toggleLabel(matchNbt));
        }
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.collection_cache.blocked"),
            px + 8, py + BLOCK_ROW_Y, blocked ? 0xFFC03030 : COLOR_TEXT, false);
        if (blockedButton != null) {
            McGui.refreshToggle(blockedButton, blocked);
        }
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.collection_cache.match_tags"),
            px + 8, py + TAGS_ROW_Y, tagMode ? 0xFF2E7D32 : COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(tagMode
                ? "gui.rs_create_compat.collection_cache.match_tags.on"
                : "gui.rs_create_compat.collection_cache.match_tags.off"),
            px + 82, py + TAGS_ROW_Y, tagMode ? 0xFF2E7D32 : COLOR_DIM, false);
        if (tagsButton != null) {
            McGui.refreshToggle(tagsButton, tagMode);
        }

        // 「直接销毁」开关（标题行右端留白）：待确认态用红字提示「确认销毁？」，按钮显示 ✓/✗
        if (destroyButton != null) {
            final String label = Component.translatable(destroyArmed
                ? "gui.rs_create_compat.collection_cache.destroy_marker.armed"
                : "gui.rs_create_compat.collection_cache.destroy_marker.label").getString();
            guiGraphics.drawString(font, Component.literal(trimToWidth(label,
                    DESTROY_BTN_X - DESTROY_LABEL_X - DESTROY_LABEL_GAP)),
                px + DESTROY_LABEL_X, py + 9,
                destroy || destroyArmed ? 0xFFC02020 : COLOR_TEXT, false);
            destroyButton.setMessage(McGui.toggleLabel(destroy));
        }

        renderTagList(guiGraphics, mouseX, mouseY);
        // 命中预览 / 危险确认提醒（待确认时把预览行让给「不可恢复」警告，确保玩家看得到）
        if (destroyArmed) {
            guiGraphics.drawString(font,
                Component.translatable("gui.rs_create_compat.collection_cache.destroy_marker.warn"),
                px + 8, py + PREVIEW_Y, 0xFFC02020, false);
        } else if (tagMode) {
            guiGraphics.drawString(font, Component.translatable(
                    matchedPreview < 0 ? LANG_PREVIEW_NONE : LANG_PREVIEW,
                    Math.max(0, matchedPreview), selectedTags.size()),
                px + 8, py + PREVIEW_Y, COLOR_DIM, false);
        } else {
            guiGraphics.drawString(font,
                Component.translatable("gui.rs_create_compat.collection_cache.match_tags.hint"),
                px + 8, py + PREVIEW_Y, COLOR_DIM, false);
        }

        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    private static final String LANG_PREVIEW = "gui.rs_create_compat.collection_cache.match_tags.preview";
    private static final String LANG_PREVIEW_NONE = "gui.rs_create_compat.collection_cache.match_tags.preview_none";

    /** 标签多选列表：每行一个复选框样式的标签项，点击切换选中。 */
    private void renderTagList(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 列表底：凹陷浅色（与面板区分），保证文字清晰
        guiGraphics.fill(px + LIST_X - 1, py + LIST_Y - 1,
            px + LIST_X + LIST_W + 1, py + LIST_Y + LIST_H + 1, 0xFF2B2B2B);
        guiGraphics.fill(px + LIST_X, py + LIST_Y,
            px + LIST_X + LIST_W, py + LIST_Y + LIST_H, 0xFFC6C6C6);
        if (availableTags.isEmpty()) {
            guiGraphics.drawString(font,
                Component.translatable("gui.rs_create_compat.collection_cache.match_tags.empty"),
                px + LIST_X + 4, py + LIST_Y + 4, COLOR_DIM, false);
            return;
        }
        final int hovered = rowAt(mouseX, mouseY);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            final int index = firstVisibleRow + row;
            if (index >= availableTags.size()) {
                break;
            }
            final ResourceLocation tag = availableTags.get(index);
            final int y = py + LIST_Y + row * ROW_H;
            final boolean selected = selectedTags.contains(tag);
            if (selected) {
                guiGraphics.fill(px + LIST_X, y, px + LIST_X + LIST_W, y + ROW_H, COLOR_ROW_SEL);
            } else if (row == hovered && tagMode) {
                guiGraphics.fill(px + LIST_X, y, px + LIST_X + LIST_W, y + ROW_H, COLOR_ROW_HOVER);
            }
            final String mark = selected ? "[x] " : "[ ] ";
            final String text = trimToWidth(mark + tag, LIST_W - 6);
            final int color = !tagMode ? COLOR_DIM
                : (selected ? COLOR_ROW_TEXT_SEL : COLOR_TEXT);
            guiGraphics.drawString(font, Component.literal(text), px + LIST_X + 3, y + 1, color, false);
        }
        if (scrollbar != null && scrollbar.visible) {
            scrollbar.render(guiGraphics, mouseX, mouseY, 0.0F);
        }
    }

    /** 按像素宽截断字符串（尾部补省略号）。 */
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

    /** 手动渲染 tooltip：图标 / 三个开关 / 列表行（判定与绘制同源）。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (mouseX >= px + ICON_X && mouseX < px + ICON_X + 16
            && mouseY >= py + ICON_Y && mouseY < py + ICON_Y + 16) {
            if (entry.fluid()) {
                // 流体名 = 身份信息（常显）；数量 = 数值（Ctrl 层）
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                    guiGraphics, font, List.of(GhostMarkerRenderer.fluidName(entry.id())),
                    List.of(cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.ctrl(
                        Component.translatable("gui.rs_create_compat.marker.amount",
                            GhostMarkerRenderer.fluidAmount(initialAmount)))),
                    mouseX, mouseY);
            } else {
                // tooltip 与图标同源：hover 到哪个轮播命中物，就给哪个物品的 tooltip
                guiGraphics.renderTooltip(font, GhostMarkerRenderer.displayItem(entry), mouseX, mouseY);
            }
            return;
        }
        if (matchNbtButton != null && matchNbtButton.isMouseOver(mouseX, mouseY)) {
            renderTip(guiGraphics, "gui.rs_create_compat.collection_cache.match_nbt.tip", mouseX, mouseY);
            return;
        }
        if (blockedButton != null && blockedButton.isMouseOver(mouseX, mouseY)) {
            renderTip(guiGraphics, "gui.rs_create_compat.collection_cache.blocked.tip", mouseX, mouseY);
            return;
        }
        if (tagsButton != null && tagsButton.isMouseOver(mouseX, mouseY)) {
            renderTip(guiGraphics, "gui.rs_create_compat.collection_cache.match_tags.tip", mouseX, mouseY);
            return;
        }
        // 「直接销毁」开关：说明危险 + 当前状态（两行都由 RsccTooltipLayers 分层，默认收起）
        if (destroyButton != null && destroyButton.isMouseOver(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(guiGraphics, font, List.of(
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable("gui.rs_create_compat.collection_cache.destroy_marker.tip")),
                cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                    Component.translatable(destroy
                        ? "gui.rs_create_compat.collection_cache.destroy_marker.on"
                        : "gui.rs_create_compat.collection_cache.destroy_marker.off"))),
                mouseX, mouseY);
            return;
        }
        // 标签列表行：只给完整标签名（选中状态已由 [x] / 底色表达，不再堆三行操作说明）
        final int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            final ResourceLocation tag = availableTags.get(firstVisibleRow + row);
            GhostMarkerRenderer.renderLines(guiGraphics, font, List.of(Component.literal("#" + tag)),
                mouseX, mouseY);
        }
    }

    /** 控制说明的统一出口：本模组附加信息，默认收起、按住 Shift 才展开（唯一实现 RsccTooltipLayers）。 */
    private void renderTip(final GuiGraphics guiGraphics, final String key,
                           final int mouseX, final int mouseY) {
        cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
            guiGraphics, font, Component.translatable(key), mouseX, mouseY);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (scrollbar != null && scrollbar.visible && scrollbar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        final int row = rowAt(mouseX, mouseY);
        if (row >= 0 && button == 0 && tagMode) {
            toggleTag(availableTags.get(firstVisibleRow + row));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(final double mouseX, final double mouseY) {
        // 滚动条悬停（拖动改由 mouseDragged 转发：MC 按住移动时只派发 mouseDragged）
        if (scrollbar != null && scrollbar.visible) {
            scrollbar.mouseMoved(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * 拖动转发：MC 在「按住鼠标移动」时只派发 {@code mouseDragged}（不派发 {@code mouseMoved}），
     * 而滚动条不是原版子控件 —— 不转发就会出现「能点/能滚轮，但按住拖不动」。
     */
    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button,
                                final double dragX, final double dragY) {
        if (scrollbar != null && scrollbar.visible
            && scrollbar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (scrollbar != null && scrollbar.visible
            && scrollbar.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double delta) {
        if (inList(x, y) && tagMode && availableTags.size() > VISIBLE_ROWS) {
            scrollRows(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, delta);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (amountBox != null && amountBox.isFocused()) {
                confirmAndReturn();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
