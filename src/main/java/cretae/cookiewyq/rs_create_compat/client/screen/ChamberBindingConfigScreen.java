package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RecipeTypeMachines;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.client.widget.ScrollSelectWidget;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.network.RequestRecipeTypesPacket;
import cretae.cookiewyq.rs_create_compat.network.SetChamberBindingPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncRecipeTypesPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 序列执行仓「绑定配置」子界面：用滚轮选择控件设置本仓的配方类型，并编辑显示名。
 * <p>以 <b>父窗口之上的子窗口</b> 形态弹出（见 {@link ChildConfigScreen}）：父界面（执行仓界面）保持可见并变暗；
 * 确认时发 {@link SetChamberBindingPacket}（服务端校验 BE / 距离 / <b>名字唯一性</b> 后写入，
 * 并回发 {@code SyncChamberBindingPacket} 供父界面刷新展示），取消 / Esc 直接返回父界面。</p>
 * <p><b>列表展示主体 = 能执行该配方的机器方块</b>（{@link RecipeTypeMachines} 反查）：每行文字是该配方类型下
 * 当前选中机器的名称，下方 16×16 图标是该机器方块 —— 既不是配方产物，也不是配方原料。
 * 当同一个配方类型有多个候选机器（例如「熔炼」同时能被原版熔炉与某个更高级的熔炉执行）时，
 * <b>点击图标即可轮询切换到下一个机器</b>，tooltip 里会写明「第几个 / 共几个」；
 * 若确实找不到任何已知机器，则回退显示配方类型本身的名称，绝不退化成显示原料。</p>
 * <p>配方类型候选由服务端 {@code SyncRecipeTypesPacket} 下发（全部已注册类型，已绑定的排前面）；
 * 名字仍用 {@link EditBox}，<b>重名在输入时即时判定</b>：名字落在「本网络已被别的链占用的名字集合」里时
 * 直接把「确定」按钮置灰（鼠标悬停给出原因），不必等提交后才被服务端拒回一条聊天消息。
 * 该集合来自服务端下发的 {@link SyncChamberBindingPacket#occupiedNames()}（打开界面时一次），
 * 与 {@code SequenceExecutionChamberBlockEntity#isNameTakenInNetwork} 逐字同源；
 * 服务端仍然保留最终校验（客户端只是体验优化，绝不是唯一防线）。</p>
 * <p><b>链</b>：链方向 = 方块放置时的朝向（那支箭头），与 RS 的自动合成仓一致，<b>不在这里配置</b>。
 * 链上<b>名字与配方类型是整链共享的（只存链首那一份）</b>，因此<b>在链上任意一台改都等于改整条链</b>；
 * 本界面只把「本台属于哪条链 / 链头是哪台 / 配置整链共享」显示在链状态行与 tooltip 里。</p>
 * <p><b>面板外观</b>：{@link #renderPanel} 用原版九宫格精灵拼出子窗口（MC 自带观感），不再手搓边框。</p>
 * <p>硬规则：所有 {@code drawString} 无阴影、EditBox {@code setTextShadow(false)}；
 * 自绘元素的 tooltip 全部手动渲染，且 hover 判定与绘制范围同源。</p>
 */
public class ChamberBindingConfigScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.sequence_execution_chamber.";

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 190;

    /** 自绘面板 PNG（{@code CHAMBER_BINDING_CONFIG_GUI_DOC.md} 约定，200×190）；缺失时退回原版九宫格。 */
    private static final ResourceLocation CUSTOM_PANEL =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID,
            "textures/gui/chamber_binding_config.png");

    @Override
    @Nullable
    protected ResourceLocation customPanel() {
        return CUSTOM_PANEL;
    }

    private static final int TITLE_X = 10;
    private static final int TITLE_Y = 10;
    private static final int RECIPE_LABEL_Y = 30;
    private static final int RECIPE_BOX_Y = 42;
    private static final int RECIPE_BOX_W = 180;
    /** 配方类型选择框下方的「机器方块」图标（16×16，与 tooltip / 点击判定范围同源）。 */
    private static final int MACHINE_ICON_Y = 62;
    private static final int MACHINE_ICON_SIZE = 16;
    private static final int NAME_LABEL_Y = 84;
    private static final int NAME_BOX_Y = 96;
    private static final int NAME_BOX_W = 180;
    private static final int BOX_H = 16;
    private static final int ACTION_Y = 118;
    private static final int ACTION_W = 88;
    /** 链状态行（只读展示：链台数 / 链头），紧随按钮行下方。 */
    private static final int CHAIN_STATUS_Y = 142;
    private static final int CHAIN_STATUS_W = 180;
    private static final int STATUS_ROW_H = 9;

    /** 文字颜色（无阴影；浅灰原版面板上用深色字）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_GRAY = 0xFF6B6B6B;

    /**
     * 目标执行仓坐标；{@code null} = 客户端尚未同步到坐标（由服务端按当前打开的执行仓菜单解析）。
     */
    @Nullable
    private final BlockPos pos;
    private final String initialRecipeType;
    private final String initialName;
    /**
     * 目标执行仓里是否还放着单元样板（由 S2C 同步）。
     * <p>为真时<b>锁定配方类型</b>：控件灰显不可交互（必须把样板全部取走才能改类型）。
     * 名字不受影响，仍可修改。</p>
     */
    private final boolean hasUnits;
    /** 链状态快照（链台数 / 链头 / 链上是否有样板）；用于开启时回填展示与 tooltip。 */
    private final int chainSize;
    /** 本台是否就是链首（由服务端判定；链台数为 1 时无意义）。 */
    private final boolean chainHead;
    @Nullable
    private final BlockPos headPos;
    private final String headName;
    private final boolean chainUnits;

    /** 配方类型滚轮选择控件（候选 = 任务二下发的全部已注册配方类型，已绑定的排前面）。 */
    private ScrollSelectWidget<String> recipePicker;
    /** 上一帧的候选快照：变化时才回填控件，避免每帧重建列表。 */
    private List<String> lastCandidates = List.of();
    /** 玩家是否手动改过配方类型：未改动前，候选到达后优先对齐服务端下发的当前绑定值。 */
    private boolean recipeTouched;
    private EditBox nameBox;
    /** 「确定」按钮：名字重名时置灰（见 {@link #nameConflict()}），而不是等提交后才被服务端拒回一条消息。 */
    private Button confirmButton;
    /**
     * 本台所在网络内<b>已被别的链占用的名字集合</b>（服务端权威结果，随 {@link SyncChamberBindingPacket} 下发）。
     * 界面只做集合成员判断，因此与 {@code isNameTakenInNetwork} 的结论一致；同一台机器的新快照到达时会刷新。
     */
    private Set<String> occupiedNames = Set.of();

    /** 配方类型 → 候选机器（反查结果缓存；屏幕关闭即丢弃，避免每帧重扫配方）。 */
    private final Map<String, List<RecipeTypeMachines.Machine>> machineCache = new HashMap<>();
    /** 配方类型 → 玩家选中的机器下标（多个机器时点图标轮询；仅本界面内的展示偏好，不回写服务端）。 */
    private final Map<String, Integer> machineIndexByType = new HashMap<>();
    /** 配方类型 id → 列表显示文案（同名机器用配方类型消歧，见 {@link #rebuildLabels()}）。 */
    private final Map<String, String> labelById = new HashMap<>();
    /** 当前配方类型对应的候选机器（每帧刷新；空列表 = 该类型没有已知机器，回退显示配方类型名）。 */
    private List<RecipeTypeMachines.Machine> currentMachines = List.of();

    public ChamberBindingConfigScreen(final Screen parent,
                                      @Nullable final SyncChamberBindingPacket sync) {
        super(Component.translatable(LANG + "config.title"), parent, PANEL_W, PANEL_H);
        this.pos = sync == null ? null : sync.pos();
        this.initialRecipeType = sync == null || sync.recipeType() == null ? "" : sync.recipeType();
        this.initialName = sync == null || sync.name() == null ? "" : sync.name();
        this.hasUnits = sync != null && sync.hasUnits();
        this.chainSize = sync == null ? 1 : sync.chainSize();
        this.chainHead = sync != null && sync.chainHead();
        this.headPos = sync == null ? null : sync.headPos();
        this.headName = sync == null ? "" : sync.headName();
        this.chainUnits = sync != null && sync.chainUnits();
        this.occupiedNames = sync == null || sync.occupiedNames() == null
            ? Set.of() : new HashSet<>(sync.occupiedNames());
    }

    @Override
    protected void init() {
        super.init();
        machineCache.clear();

        // 配方类型：滚轮选择控件（候选由服务端下发；打开界面时请求）
        lastCandidates = SyncRecipeTypesPacket.getLastReceived();
        recipeTouched = false;
        recipePicker = new ScrollSelectWidget<String>(font,
            px + TITLE_X, py + RECIPE_BOX_Y, RECIPE_BOX_W, BOX_H)
            .forOptions(lastCandidates)
            .titled(Component.translatable(LANG + "config.recipe_type"))
            // 行文字 = 该配方类型下当前选中机器的名称（找不到机器则回退配方类型名）
            .displaying(this::displayForRecipeType)
            .hinting(Component.translatable(LANG + "config.recipe_type.hint"))
            .writingTo(index -> recipeTouched = true)
            // 仓里还有单元样板 → 锁定配方类型：控件灰显且不响应滚轮 / 点击（服务端同样会拒绝）
            .selectable(!hasUnits)
            // 滚轮 tooltip 最底部追加当前选中项的原始配方类型 id（暗灰+斜体，低调）
            .showingExtraTooltip(id -> List.of(RecipeTypeNames.idLine(id)));
        recipePicker.setState(indexOfOption(initialRecipeType));
        rebuildLabels();
        addRenderableWidget(recipePicker);
        PacketDistributor.sendToServer(new RequestRecipeTypesPacket());

        // 名字输入框（重名由本界面实时判定并置灰「确定」；服务端仍保留同一判据的最终校验）
        nameBox = new EditBox(font, px + TITLE_X, py + NAME_BOX_Y, NAME_BOX_W, BOX_H,
            Component.translatable(LANG + "config.name"));
        nameBox.setMaxLength(64);
        nameBox.setValue(initialName);
        nameBox.setTextShadow(false);
        addRenderableWidget(nameBox);
        setFocused(nameBox);

        // 确定 / 取消（确定按钮的可用性每帧由 nameConflict() 决定，见 render）
        confirmButton = new Button.Builder(Component.translatable(LANG + "config.confirm"),
            button -> confirmAndReturn())
            .bounds(px + TITLE_X, py + ACTION_Y, ACTION_W, BOX_H).build();
        addRenderableWidget(confirmButton);
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "config.cancel"),
            button -> returnToParent())
            .bounds(px + TITLE_X + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H).build());
    }

    // ==================== 名字重名（实时判定） ====================

    /**
     * 输入的名字是否与「本网络内别的链已占用的名字」重复。
     *
     * <p><b>口径与服务端逐条对齐</b>（{@code SequenceExecutionChamberBlockEntity#isNameTakenInNetwork}）：</p>
     * <ul>
     *     <li><b>trim</b>：与提交时 {@code nameBox.getValue().trim()} 一致，首尾空格不参与比较；</li>
     *     <li><b>空名 / 纯空格</b>：trim 后为空 → 不算冲突（服务端同样直接放行，空名含义是「回退坐标显示」）；</li>
     *     <li><b>大小写</b>：与已有名字<b>区分大小写</b>地逐字符比较 —— 服务端用的是 {@code equals}，
     *     这里就必须用 {@code HashSet#contains}（同样是区分大小写的精确匹配），
     *     绝不自作主张忽略大小写，否则会出现「客户端放行、服务端拒绝」；</li>
     *     <li><b>同链不算冲突</b>：服务端比的是「别的链占用的名字」，服务端算这份集合时已把本链排除，
     *     因此这里不需要再排除「自己当前的名字」—— 原样改名时输入值本来就不在该集合里。</li>
     * </ul>
     */
    private boolean nameConflict() {
        return nameBox != null && occupiedNames.contains(nameBox.getValue().trim());
    }

    /** 同一台机器的新快照到达后刷新占用集合（只在能确认是同一台时采用，避免拿别台的名单做判断）。 */
    private void syncOccupiedNames() {
        final SyncChamberBindingPacket latest = SyncChamberBindingPacket.getLastReceived();
        if (latest == null || latest.occupiedNames() == null) {
            return;
        }
        if (pos != null && !pos.equals(latest.pos())) {
            return; // 不是本台（例如刚看过另一台执行仓）：宁可沿用打开时那一份，也不拿错名单判重名
        }
        occupiedNames = new HashSet<>(latest.occupiedNames());
    }

    // ==================== 链状态（只读展示） ====================

    /** 链状态行文案（服务端同步快照）：链台数 / 链头；独立时明确写「独立」。 */
    private Component chainStatusLine() {
        if (chainSize <= 1) {
            return Component.translatable(LANG + "config.chain.status.standalone");
        }
        return Component.translatable(LANG + "config.chain.status", chainSize, headText());
    }

    /** 链头显示：本台就是链头时直接说明，否则给「名字 (x, y, z)」。 */
    private String headText() {
        if (chainHead) {
            return Component.translatable(LANG + "chain.head.self").getString();
        }
        final String name = headName == null || headName.isEmpty()
            ? Component.translatable(LANG + "chain.head.unknown").getString() : headName;
        if (headPos == null) {
            return name;
        }
        return name + " (" + headPos.getX() + ", " + headPos.getY() + ", " + headPos.getZ() + ")";
    }

    /** 链状态 tooltip：状态行 + 「整链共享」说明 + 链级样板锁定说明。 */
    private List<FormattedCharSequence> chainStatusTooltip() {
        final List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(chainStatusLine().getVisualOrderText());
        lines.add(Component.translatable(LANG + "config.chain.shared.tip")
            .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        if (chainSize > 1) {
            lines.add(Component.translatable(LANG + "config.chain.head.tip")
                .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        if (chainUnits) {
            lines.add(Component.translatable(LANG + "config.chain.units.tip")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC).getVisualOrderText());
        }
        return lines;
    }

    // ==================== 机器方块反查 ====================

    /** 该配方类型的候选机器（带缓存；空结果不缓存，便于配方加载完成后自愈）。 */
    private List<RecipeTypeMachines.Machine> machinesOf(final String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) {
            return List.of();
        }
        final List<RecipeTypeMachines.Machine> cached = machineCache.get(recipeTypeId);
        if (cached != null) {
            return cached;
        }
        final List<RecipeTypeMachines.Machine> computed = RecipeTypeMachines.forRecipeType(recipeTypeId);
        if (!computed.isEmpty()) {
            machineCache.put(recipeTypeId, computed);
        }
        return computed;
    }

    /** 该配方类型当前选中的机器下标（把玩家选择收敛到候选范围内）。 */
    private int machineIndex(final String recipeTypeId, final List<RecipeTypeMachines.Machine> machines) {
        if (machines.isEmpty()) {
            return 0;
        }
        return Math.floorMod(machineIndexByType.getOrDefault(recipeTypeId, 0), machines.size());
    }

    /** 行文字：优先显示「能执行该配方的机器方块」的名称；无已知机器时回退配方类型名。 */
    private Component displayForRecipeType(final String recipeTypeId) {
        final String label = labelById.get(recipeTypeId);
        if (label != null) {
            return Component.literal(label);
        }
        final List<RecipeTypeMachines.Machine> machines = machinesOf(recipeTypeId);
        if (machines.isEmpty()) {
            return RecipeTypeNames.display(recipeTypeId);
        }
        return machines.get(machineIndex(recipeTypeId, machines)).name();
    }

    /**
     * 重建列表显示文案：<b>按「显示名（机器方块名）」去重</b> —— 多个配方类型落到同一个机器名时
     * （例：{@code create:deploying} 与 {@code create:item_application} 都是「机械手」），
     * 只靠机器名会出现两个看起来完全一样的条目（用户实测问题）。
     * <p>处理方式：<b>同名时给每个条目追加配方类型显示名消歧</b>，例如
     * 「机械手（机械手装配）」「机械手（物品应用）」。之所以不直接删掉重复项，是因为每个配方类型
     * 都是可独立绑定的键（执行仓必须与步骤的配方类型完全相等才能匹配），删掉会导致无法绑定。</p>
     */
    private void rebuildLabels() {
        labelById.clear();
        final List<String> options = recipePicker == null ? List.of() : recipePicker.getOptions();
        final Map<String, Integer> nameCount = new HashMap<>();
        for (final String id : options) {
            final String base = machineNameOf(id);
            nameCount.merge(base, 1, Integer::sum);
        }
        for (final String id : options) {
            final String base = machineNameOf(id);
            labelById.put(id, nameCount.getOrDefault(base, 1) > 1
                ? base + " (" + RecipeTypeNames.of(id) + ")" : base);
        }
    }

    /** 该配方类型的「机器显示名」（无已知机器时回退配方类型名）。 */
    private String machineNameOf(final String recipeTypeId) {
        final List<RecipeTypeMachines.Machine> machines = machinesOf(recipeTypeId);
        if (machines.isEmpty()) {
            return RecipeTypeNames.of(recipeTypeId);
        }
        return machines.get(machineIndex(recipeTypeId, machines)).name().getString();
    }

    /** 当前选中的配方类型 id（无候选返回 null）。 */
    @Nullable
    private String selectedRecipeType() {
        return recipePicker == null ? null : recipePicker.getSelected();
    }

    /** 在候选列表里定位当前绑定值（找不到回退 0）。 */
    private int indexOfOption(final String recipeType) {
        final List<String> options = recipePicker.getOptions();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(recipeType)) {
                return i;
            }
        }
        return 0;
    }

    /** 服务端候选到达后回填控件（保留原选择；候选未到达时控件显示「无可用选项」）。 */
    private void syncRecipeCandidates() {
        if (recipePicker == null) {
            return;
        }
        final List<String> candidates = SyncRecipeTypesPacket.getLastReceived();
        if (candidates.equals(lastCandidates)) {
            return;
        }
        final String previous = recipePicker.getSelected();
        lastCandidates = candidates;
        recipePicker.setOptions(candidates);
        rebuildLabels(); // 候选变了：同名机器消歧文案随之重建
        if (!recipeTouched) {
            // 玩家还没动过控件：优先对齐服务端下发的当前绑定值（避免沿用上一张网络 / 上一次的陈旧选择）
            final int initialIndex = candidates.indexOf(initialRecipeType);
            recipePicker.setState(initialIndex >= 0 ? initialIndex : indexOfOption(previous));
            return;
        }
        recipePicker.setState(previous == null ? 0 : indexOfOption(previous));
    }

    private void confirmAndReturn() {
        if (nameConflict()) {
            return; // 重名：与按钮置灰同一判据，回车路径也拦下（服务端还会再校验一次）
        }
        // 坐标缺失时发占位坐标：服务端会按「玩家当前打开的执行仓菜单」定位目标，因此仍然可以正常提交
        final BlockPos target = pos == null ? SetChamberBindingPacket.NO_POS : pos;
        // 配方类型被锁定时（链上任一台还有单元样板）发送原值，避免客户端与服务端展示不一致；
        // 服务端同样会拒绝任何类型改动（双保险）。
        final String selected = hasUnits
            ? initialRecipeType
            : (recipePicker == null ? null : recipePicker.getSelected());
        PacketDistributor.sendToServer(new SetChamberBindingPacket(target,
            selected == null ? "" : selected, nameBox.getValue().trim()));
        returnToParent();
    }

    // ==================== 渲染 ====================

    /**
     * 子窗口面板：{@link #customPanel()} 返回自绘 PNG（{@code chamber_binding_config.png}，200×190），
     * 基类 {@link ChildConfigScreen#renderPanel} 整图 {@code blit}；PNG 缺失时退回原版九宫格精灵
     * {@code minecraft:recipe_book/overlay_recipe}（圆角 / 黑描边 / 白高光 / 暗边全部来自 MC 自带贴图）。
     */

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX,
                       final int mouseY, final float partialTick) {
        syncRecipeCandidates();
        syncOccupiedNames();
        // 输入名字时即时判定重名：命中就把「确定」置灰（原版 disabled 观感，不改任何贴图），
        // 鼠标悬停由 renderTooltips 给出原因 —— 不必等提交后才被服务端拒回一条聊天消息。
        confirmButton.active = !nameConflict();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 标题 / 三个字段标签（全部无阴影，深色字配浅灰原版面板）
        guiGraphics.drawString(font, title, px + TITLE_X, py + TITLE_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "config.recipe_type"),
            px + TITLE_X, py + RECIPE_LABEL_Y, COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "config.name"),
            px + TITLE_X, py + NAME_LABEL_Y, COLOR_TEXT, false);
        // 链状态行（服务端权威，只读）：链台数 / 链头；独立时明确写「独立」
        guiGraphics.drawString(font, chainStatusLine(),
            px + TITLE_X, py + CHAIN_STATUS_Y, COLOR_GRAY, false);
        // 机器方块图标：紧贴配方类型选择框下方（16×16，与 tooltip / 点击判定范围完全一致）
        final String selected = selectedRecipeType();
        currentMachines = selected == null ? List.of() : machinesOf(selected);
        if (!currentMachines.isEmpty()) {
            guiGraphics.renderItem(currentMachines.get(machineIndex(selected, currentMachines)).icon(),
                px + TITLE_X, py + MACHINE_ICON_Y);
        } else {
            // 空槽位标识（用户要求：「没有东西」的地方要一眼看出来）：这一格是机器预览位，
            // 该配方类型一台可用机器都没有时画一道斜杠（{@link McGui#emptySlotMarker}，全部由 fill 手搓，
            // 不新增任何贴图），而不是留一片空白让人以为界面坏了。
            McGui.emptySlotMarker(guiGraphics, px + TITLE_X, py + MACHINE_ICON_Y);
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 手动渲染 tooltip：判定范围与绘制范围完全一致（左闭右开）。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 配方类型滚轮控件：标题 + 16 行候选窗口（控件自绘，必须手动渲染 tooltip）
        if (recipePicker != null && recipePicker.inBounds(mouseX, mouseY)) {
            if (hasUnits) {
                // 锁定态：只给一条简短原因，不铺开候选列表（用户要求 tooltip 精简）
                guiGraphics.renderTooltip(font,
                    Component.translatable(LANG + "config.recipe_type.locked"), mouseX, mouseY);
                return;
            }
            recipePicker.renderTooltip(guiGraphics, mouseX, mouseY);
            return;
        }
        // 链状态行（只读展示；状态行只有 9px 高，单独判定太难点中，故用整行宽）
        if (isHovering(px + TITLE_X, py + CHAIN_STATUS_Y, CHAIN_STATUS_W, STATUS_ROW_H, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, chainStatusTooltip(), mouseX, mouseY);
            return;
        }
        // 机器方块图标（自绘，必须手动渲染 tooltip；判定范围与绘制范围一致）
        final String selected = selectedRecipeType();
        if (selected != null && !currentMachines.isEmpty()
            && isHovering(px + TITLE_X, py + MACHINE_ICON_Y, MACHINE_ICON_SIZE, MACHINE_ICON_SIZE, mouseX, mouseY)) {
            final int index = machineIndex(selected, currentMachines);
            final List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(currentMachines.get(index).name().copy()
                .withStyle(ChatFormatting.WHITE).getVisualOrderText());
            if (currentMachines.size() > 1) {
                lines.add(Component.translatable(LANG + "config.machine.count",
                        index + 1, currentMachines.size())
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText());
            }
            lines.add(Component.translatable(LANG + "config.machine.tip")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC).getVisualOrderText());
            lines.add(RecipeTypeNames.idLine(selected).getVisualOrderText());
            guiGraphics.renderTooltip(font, lines, mouseX, mouseY);
            return;
        }
        // 机器图标区「空」时的 tooltip（自绘的空标识同样必须手动渲染 tooltip）：
        // 只说明「这个配方类型当前没有可用机器」，不铺开无关信息。
        if (selected != null && currentMachines.isEmpty()
            && isHovering(px + TITLE_X, py + MACHINE_ICON_Y, MACHINE_ICON_SIZE, MACHINE_ICON_SIZE,
                mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable(LANG + "config.machine.none"),
                mouseX, mouseY);
            return;
        }
        // 配方类型标签（只到输入框上沿，避免与控件 tooltip 重叠）
        if (isHovering(px + TITLE_X, py + RECIPE_LABEL_Y, RECIPE_BOX_W,
            RECIPE_BOX_Y - RECIPE_LABEL_Y, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable(LANG + "config.recipe_type.tip"),
                mouseX, mouseY);
            return;
        }
        // 名字
        if (isHovering(px + TITLE_X, py + NAME_LABEL_Y, NAME_BOX_W,
            NAME_BOX_Y + BOX_H - NAME_LABEL_Y, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable(LANG + "config.name.tip"),
                mouseX, mouseY);
            return;
        }
        // 确定 / 取消（重名时按钮是禁用态，悬停给出「已有机器同名」的原因）
        if (isHovering(px + TITLE_X, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable(
                nameConflict() ? LANG + "config.confirm.duplicate" : LANG + "config.confirm.tip"),
                mouseX, mouseY);
            return;
        }
        if (isHovering(px + TITLE_X + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.translatable(LANG + "config.cancel.tip"),
                mouseX, mouseY);
        }
    }

    private static boolean isHovering(final int x, final int y, final int w, final int h,
                                      final double mouseX, final double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        // 机器方块图标：同一个配方类型有多个候选机器时，点一下切换到下一个（轮询）
        if (button == 0 && currentMachines.size() > 1
            && isHovering(px + TITLE_X, py + MACHINE_ICON_Y, MACHINE_ICON_SIZE, MACHINE_ICON_SIZE,
                mouseX, mouseY)) {
            final String selected = selectedRecipeType();
            if (selected != null) {
                machineIndexByType.put(selected, machineIndex(selected, currentMachines) + 1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirmAndReturn();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
