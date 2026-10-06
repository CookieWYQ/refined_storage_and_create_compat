package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.SequenceUnitPatternItem;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「执行仓单元样板汇总」菜单（<b>照精致存储（Refined Storage）自动合成管理器的设计理念重做</b>）。
 *
 * <p><b>参考的 RS 类</b>（理念来源，见 {@code local_src/external/RefinedStorage/refinedstorage-common/}）：</p>
 * <ul>
 *     <li>{@code common/autocrafting/monitor/AutocraftingMonitorScreen.java}：<b>可滚动条目列表 + 选中项 +
 *     底部「全部处理」按钮</b>的整体骨架（条目区给 {@code enableScissor}、选中项高亮、
 *     底部按钮只对当前选中项生效、所有提示手动 {@code setDeferredTooltip}/{@code renderTooltip}）。</li>
 *     <li>{@code common/autocrafting/monitor/AbstractAutocraftingMonitorContainerMenu.java}：<b>容器菜单持有
 *     动态数据 + 数据变化即回推</b>（{@code taskStatusChanged} / {@code removed} 解绑监听）。</li>
 *     <li>{@code common/autocrafting/patterngrid/PatternGridScreen.java} 与
 *     {@code common/grid/screen/AbstractGridScreen.java}：<b>真 {@link Slot} 网格</b> +
 *     点击取整叠 / Shift 快速移动 / 拖拽放置 + 手动 tooltip（{@code renderTooltip} 里逐个
 *     {@code Platform.renderTooltip}）的槽位交互范式。</li>
 * </ul>
 *
 * <p><b>本菜单如何把理念落到「跨方块的单元样板」上</b>：每台执行仓占一行，行内 4 个<b>真槽位</b>是
 * 该仓 54 格单元样板槽的一个<b>滑动窗口</b>（样板多于 4 张时按 {@link #POLL_TICKS} 轮询前移）；
 * 分页切换只是把各行的目标执行仓重新指过去，槽位下标保持不变，因此原版槽位同步（
 * {@code broadcastChanges} 的逐格 diff）会自动把新内容推给客户端，无需重发菜单。</p>
 *
 * <p><b>服务端权威</b>：单元格的「放」统一走
 * {@link SequenceExecutionChamberBlockEntity#acceptsUnit(ItemStack)}（必须是单元样板 && 执行仓已绑定 &&
 * 配方类型匹配），与执行仓界面 / 手持右键放入<b>完全同一判定，不存在旁路</b>；
 * 批量「取回 / 存入」仍走既有的 {@code pullUnitsFrom} / {@code pushUnitsTo}。</p>
 */
public class ChamberUnitsSummaryMenu extends AbstractContainerMenu {
    // ==================== 几何（Menu 坐标；背景精灵坐标 = Menu 坐标 - 1） ====================
    /** 面板尺寸（同时作为界面 {@code imageWidth/Height}）。 */
    public static final int PANEL_W = 244;
    public static final int PANEL_H = 250;

    /** 行高 / 首行 y / 可见行数 / 每行格数。 */
    public static final int ROW_H = 22;
    public static final int ROW_Y0 = 20;
    public static final int VISIBLE_ROWS = 5;
    public static final int ROW_COLS = 4;
    /** 行内单元样板格：x 起点、相对行顶的 y 偏移、步距。 */
    public static final int ROW_SLOT_X = 150;
    public static final int ROW_SLOT_DY = 2;
    public static final int ROW_SLOT_GAP = 18;
    /** 行内机器图标 / 名字 / 配方 id 的 x 与相对行顶的 y 偏移。 */
    public static final int ROW_ICON_X = 8;
    public static final int ROW_ICON_DY = 2;
    public static final int ROW_TEXT_X = 28;
    public static final int ROW_NAME_DY = 2;
    public static final int ROW_ID_DY = 12;
    /** 行选中高亮带（整行，画在格子之下）。 */
    public static final int ROW_BAND_X = 6;
    public static final int ROW_BAND_W = 232;
    public static final int ROW_BAND_H = 20;

    /** 玩家背包 / 快捷栏（Menu 坐标）。 */
    public static final int PLAYER_SLOT_X = 41;
    public static final int PLAYER_SLOT_Y = 144;
    public static final int HOTBAR_SLOT_X = 41;
    public static final int HOTBAR_SLOT_Y = 204;

    /** 窗口格数（= 可见行 × 每行格数）；也是玩家背包段的起始下标。 */
    public static final int WINDOW_SLOTS = VISIBLE_ROWS * ROW_COLS;
    public static final int PLAYER_START = WINDOW_SLOTS;
    public static final int PLAYER_END = PLAYER_START + 36;

    /** 底部操作条（Menu 坐标）：「取回 / 存入」（作用于选中行，走既有 C2S 包）、翻页、关闭。 */
    public static final int BTN_PULL_X = 8;
    public static final int BTN_PUSH_X = 56;
    public static final int BTN_W = 46;
    public static final int BTN_Y = 228;
    public static final int BTN_H = 16;
    public static final int PAGE_PREV_X = 110;
    public static final int PAGE_NEXT_X = 130;
    public static final int PAGE_BTN_W = 18;
    public static final int PAGE_TEXT_X = 152;
    public static final int PAGE_TEXT_Y = 232;
    public static final int CLOSE_X = 188;
    public static final int CLOSE_W = 48;

    /** 提示行（网络为空 / 轮询说明 / 选中断言）的 y。 */
    public static final int HINT_Y = 132;

    /** 轮询周期（服务端 tick）：每 20 tick 把每行的样板窗口前移一格。 */
    public static final int POLL_TICKS = 20;

    /** 界面标题语言键（菜单提供者与界面共用）。 */
    public static final String TITLE_KEY = "gui.rs_create_compat.sequence_pattern_terminal.summary.title";

    // ==================== 状态 ====================
    /** 服务端持有的终端方块实体；客户端重建菜单时为 null（内容由原版槽位同步写入本地镜像）。 */
    @Nullable
    private final SequencePatternTerminalBlockEntity terminal;
    /** 本页首台执行仓的全局下标 = {@code page * VISIBLE_ROWS}（<b>仅在服务端有意义</b>）。 */
    private int page;
    /** 轮询计数。 */
    private int pollCounter;
    /** 每行滑动窗口起点（服务端权威）。 */
    private final int[] rowStart = new int[VISIBLE_ROWS];
    /** 每行「非空单元样板槽」的压缩下标表与条目数（服务端每 tick 重建，映射真实槽位）。 */
    private final int[][] rowSlotMap = new int[VISIBLE_ROWS][SequenceExecutionChamberBlockEntity.UNIT_SLOTS];
    private final int[] rowSlotCount = new int[VISIBLE_ROWS];
    /** 20 个窗口格共用的容器（服务端解析到真实执行仓，客户端落到本地镜像）。 */
    private final WindowContainer windowContainer;
    /**
     * 网络内执行仓的<b>缓存快照</b>（服务端每 tick 重建）。
     * <p>读取路径（{@code getItem} / {@code isEmpty}）每 tick 会被调用几十次，不能每次都遍历 RS 网络节点表，
     * 因此只在 {@link #rebuildWindow()}（构造 / 每 tick / 每次写入后）刷新。</p>
     */
    private List<SequenceExecutionChamberBlockEntity> chamberCache = List.of();

    public ChamberUnitsSummaryMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public ChamberUnitsSummaryMenu(final int id, final Inventory inventory,
                                   @Nullable final SequencePatternTerminalBlockEntity terminal) {
        super(RS_Create_Compat.CHAMBER_UNITS_SUMMARY_MENU.get(), id);
        this.terminal = terminal;
        this.windowContainer = new WindowContainer();

        // ① 执行仓单元样板窗口：5 行 × 4 格，全是真槽位（可点击取 / Shift 快速移动 / 拖拽放置）
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < ROW_COLS; col++) {
                final int indexInWindow = row * ROW_COLS + col;
                addSlot(new WindowSlot(windowContainer, indexInWindow,
                    ROW_SLOT_X + col * ROW_SLOT_GAP, ROW_Y0 + row * ROW_H + ROW_SLOT_DY));
            }
        }
        // ② 玩家背包 3×9 + 快捷栏 1×9
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9,
                    PLAYER_SLOT_X + col * 18, PLAYER_SLOT_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, HOTBAR_SLOT_X + col * 18, HOTBAR_SLOT_Y));
        }
        if (terminal != null) {
            rebuildWindow();
        }
    }

    public static ChamberUnitsSummaryMenu create(final int id, final Inventory inventory,
                                                 final SequencePatternTerminalBlockEntity terminal) {
        return new ChamberUnitsSummaryMenu(id, inventory, terminal);
    }

    /** 纯 getter：暴露本菜单持有的终端方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public SequencePatternTerminalBlockEntity getTerminal() {
        return terminal;
    }

    /**
     * 从玩家当前打开的容器解析「汇总所依附的终端」：终端菜单与汇总菜单都支持（其余返回 null）。
     * <p>汇总子界面是<b>独立容器</b>，打开后 {@code player.containerMenu} 已不再是终端菜单，
     * 因此既有的请求 / 取回 / 存入包必须经此统一解析，避免出现「子界面里所有操作都失效」。</p>
     */
    @Nullable
    public static SequencePatternTerminalBlockEntity terminalOf(@Nullable final AbstractContainerMenu menu) {
        if (menu instanceof SequencePatternTerminalMenu terminalMenu) {
            return terminalMenu.getTerminal();
        }
        if (menu instanceof ChamberUnitsSummaryMenu summaryMenu) {
            return summaryMenu.getTerminal();
        }
        return null;
    }

    /** 当前容器的分页（非汇总菜单返回 0）——供 S2C 回包带上权威页码。 */
    public static int pageOf(@Nullable final AbstractContainerMenu menu) {
        return menu instanceof ChamberUnitsSummaryMenu summaryMenu ? summaryMenu.getPage() : 0;
    }

    /** 当前页（服务端权威；客户端请用 S2C 回包里的页码）。 */
    public int getPage() {
        return Math.max(0, Math.min(page, maxPage()));
    }

    /** 服务端：切换分页（越界自动收敛）。 */
    public void setPage(final int newPage) {
        page = Math.max(0, Math.min(newPage, maxPage()));
        rebuildWindow();
    }

    /** 最大页下标（按网络内执行仓台数计算；客户端 chambers 为空因此恒为 0，不会被使用）。 */
    public int maxPage() {
        return Math.max(0, (chambers().size() - 1) / VISIBLE_ROWS);
    }

    /** 网络内全部执行仓（服务端缓存的快照，见 {@link #refreshChambers()}；客户端为空列表）。 */
    public List<SequenceExecutionChamberBlockEntity> chambers() {
        return chamberCache;
    }

    /** 第 row 行对应的执行仓（越界 / 客户端返回 null）。 */
    @Nullable
    SequenceExecutionChamberBlockEntity rowChamber(final int row) {
        if (row < 0 || row >= VISIBLE_ROWS) {
            return null;
        }
        final List<SequenceExecutionChamberBlockEntity> chambers = chambers();
        final int index = getPage() * VISIBLE_ROWS + row;
        return index >= 0 && index < chambers.size() ? chambers.get(index) : null;
    }

    /**
     * 第 row 行第 col 格映射到的<b>真实单元样板槽下标</b>；
     * 无仓 / 该仓一张样板都没有时返回 -1（表示该格为空）。
     * <p>窗口只压缩「有样板的槽」，因此样板稀疏（例如只用了第 1、第 30 格）轮询才有意义。</p>
     */
    int mapToUnitSlot(final int row, final int col) {
        if (row < 0 || row >= VISIBLE_ROWS || col < 0 || col >= ROW_COLS) {
            return -1;
        }
        final int count = rowSlotCount[row];
        if (count <= 0) {
            return -1;
        }
        return rowSlotMap[row][Math.floorMod(rowStart[row] + col, count)];
    }

    /** 该执行仓单元样板槽的第一个空格（全满返回 -1）。 */
    static int firstEmptyUnitSlot(final SequenceExecutionChamberBlockEntity chamber) {
        for (int i = 0; i < chamber.unitSlots.getContainerSize(); i++) {
            if (chamber.unitSlots.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 刷新执行仓缓存快照（客户端 / 未接入网络 → 空列表）。 */
    private void refreshChambers() {
        chamberCache = terminal == null ? List.of() : terminal.networkChambers();
    }

    /** 重建每行的压缩窗口映射（服务端：每 tick + 任何写入后调用，外部改动最多落后 1 tick）。 */
    void rebuildWindow() {
        refreshChambers();
        final List<SequenceExecutionChamberBlockEntity> chambers = chamberCache;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            rowSlotCount[row] = 0;
            final int index = getPage() * VISIBLE_ROWS + row;
            if (index < 0 || index >= chambers.size()) {
                continue;
            }
            final Container unitSlots = chambers.get(index).unitSlots;
            final int[] map = rowSlotMap[row];
            int count = 0;
            for (int i = 0; i < unitSlots.getContainerSize() && count < map.length; i++) {
                // 只把「单元样板」放进窗口：序列装配总样板不属于任何单元样板界面
                // （与「单元样板管理舱不显示总样板」同一口径、同一类型判定；旧存档里可能有，读档不做校验）。
                // 窗口是每 tick 由服务端重建的派生视图（映射表而非固定下标契约），因此过滤不涉及两端下标对应问题。
                if (SequenceUnitPatternItem.isUnitPattern(unitSlots.getItem(i))) {
                    map[count++] = i;
                }
            }
            rowSlotCount[row] = count;
        }
    }

    /**
     * 服务端每 tick 的槽位同步（原版机制调用）：<b>先轮询前移窗口、再重建映射、最后交父类做逐格 diff</b>。
     * <p>轮询在玩家手上还拿着东西时暂停，避免「看着 A 却点到 B」的错位。</p>
     */
    @Override
    public void broadcastChanges() {
        if (terminal != null && getCarried().isEmpty()
            && ++pollCounter >= POLL_TICKS) {
            pollCounter = 0;
            for (int row = 0; row < VISIBLE_ROWS; row++) {
                if (rowSlotCount[row] > 1) {
                    rowStart[row]++;
                }
            }
        }
        if (terminal != null) {
            rebuildWindow();
        }
        super.broadcastChanges();
    }

    /** Shift 快速移动：窗口 → 玩家背包；玩家背包 → 各执行仓窗口（放不放得下由槽位权威判定）。 */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stackInSlot = slot.getItem();
        final ItemStack result = stackInSlot.copy();
        if (index < PLAYER_START) {
            if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stackInSlot, 0, PLAYER_START, false)) {
            // 放不进任何执行仓（不是单元样板 / 配方类型不匹配 / 全满）：保持原样
            return ItemStack.EMPTY;
        }
        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public boolean stillValid(final Player player) {
        if (terminal == null || terminal.getLevel() == null) {
            return true;
        }
        // 与终端菜单同一套有效性判定（含「高级远程终端」的虚拟方块实体特例）
        final boolean isVirtual = terminal.getBlockPos().equals(net.minecraft.core.BlockPos.ZERO)
            && terminal.getLevel().getBlockEntity(net.minecraft.core.BlockPos.ZERO) != terminal;
        if (isVirtual) {
            return true;
        }
        return terminal.getLevel().getBlockEntity(terminal.getBlockPos()) == terminal
            && player.distanceToSqr(terminal.getBlockPos().getX() + 0.5,
            terminal.getBlockPos().getY() + 0.5,
            terminal.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    // ==================== 窗口容器（服务端解析真实仓 / 客户端本地镜像） ====================

    /**
     * 20 个窗口格共用的容器。
     * <p><b>为什么用「真 {@link Container}」而不是逐槽覆写</b>：原版 {@link Slot} 对容器的写入只有
     * {@code container.setItem(index, stack)} 一条路径（{@code Slot#set} / {@code safeInsert} /
     * 客户端同步都汇聚到它），把映射放在容器里就<b>天然覆盖点击、Shift、拖拽、同步</b>四种路径，
     * 不会因为某个覆写点漏掉而丢内容。</p>
     */
    private final class WindowContainer implements Container {
        /** 客户端镜像（客户端菜单没有方块实体引用，内容由原版槽位同步写入）。 */
        private final SimpleContainer mirror = new SimpleContainer(WINDOW_SLOTS);

        private boolean isMirror() {
            return terminal == null;
        }

        @Override
        public int getContainerSize() {
            return WINDOW_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < WINDOW_SLOTS; i++) {
                if (!getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(final int index) {
            if (isMirror()) {
                return mirror.getItem(index);
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            final int unitSlot = mapToUnitSlot(index / ROW_COLS, index % ROW_COLS);
            return chamber == null || unitSlot < 0 ? ItemStack.EMPTY : chamber.unitSlots.getItem(unitSlot);
        }

        @Override
        public ItemStack removeItem(final int index, final int amount) {
            if (isMirror()) {
                return mirror.removeItem(index, amount);
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            final int unitSlot = mapToUnitSlot(index / ROW_COLS, index % ROW_COLS);
            if (chamber == null || unitSlot < 0) {
                return ItemStack.EMPTY;
            }
            final ItemStack removed = chamber.unitSlots.removeItem(unitSlot, amount);
            if (!removed.isEmpty()) {
                chamber.setChanged();
                rebuildWindow();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(final int index) {
            return removeItem(index, Integer.MAX_VALUE);
        }

        @Override
        public void setItem(final int index, final ItemStack stack) {
            final ItemStack value = stack == null ? ItemStack.EMPTY : stack;
            if (isMirror()) {
                mirror.setItem(index, value);
                return;
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            if (chamber == null) {
                return;
            }
            int unitSlot = mapToUnitSlot(index / ROW_COLS, index % ROW_COLS);
            if (unitSlot < 0) {
                if (value.isEmpty()) {
                    return;
                }
                // 空格子（该行样板不足 4 张时的空位）：放进该仓第一个空样板槽
                unitSlot = firstEmptyUnitSlot(chamber);
                if (unitSlot < 0) {
                    return; // 仓满：绝不覆盖已有样板
                }
            }
            chamber.unitSlots.setItem(unitSlot, value);
            chamber.setChanged();
            rebuildWindow();
        }

        /** 内容变化由 {@link #setItem} / {@link #removeItem} 自行落盘并重建映射，这里无需再做。 */
        @Override
        public void setChanged() {
        }

        /**
         * <b>刻意实现为空操作</b>：{@link Container#clearContent()} 是「清空容器」的通用路径
         * （例如玩家死亡掉落物品时会对容器逐个调用）。若照做，会把执行仓里的单元样板直接抹掉 ——
         * 违反本工程「绝不允许销毁玩家资源」的硬规则。真正的内容移除只允许经
         * {@link #removeItem}（返回被取出的物品，由原版交还玩家 / 光标）发生。
         */
        @Override
        public void clearContent() {
        }

        @Override
        public boolean stillValid(final Player player) {
            return true;
        }

        /** 单元样板一格一张（窗口格一律不叠加）。 */
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        /**
         * 能不能放进第 index 格 —— <b>统一硬约束</b>：必须是本模组的单元样板，且该仓
         * {@link SequenceExecutionChamberBlockEntity#acceptsUnit(ItemStack)} 通过。
         * <p>客户端没有方块实体引用，只做「必须是单元样板」的前置拦截，权威判定始终在服务端。</p>
         */
        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            // 类型判定走「单元样板」这一条共享口径（不看名字 / 文案；总样板在这里同样进不来）
            if (!SequenceUnitPatternItem.isUnitPattern(stack)) {
                return false;
            }
            if (isMirror()) {
                return true;
            }
            final SequenceExecutionChamberBlockEntity chamber = rowChamber(index / ROW_COLS);
            return chamber != null && chamber.acceptsUnit(stack);
        }
    }

    /** 窗口格的 {@link Slot}：只收紧「可放 / 可拿 / 是否有效 / 叠加上限」，取放逻辑全部落在 {@link WindowContainer}。 */
    private final class WindowSlot extends Slot {
        private WindowSlot(final Container container, final int indexInWindow, final int x, final int y) {
            super(container, indexInWindow, x, y);
        }

        /** 服务端：本行没有执行仓时该格不可交互（不可悬停 / 不可点）；客户端一律可见（内容由同步写入）。 */
        @Override
        public boolean isActive() {
            return terminal == null || rowChamber(getContainerSlot() / ROW_COLS) != null;
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return this.container.canPlaceItem(this.getContainerSlot(), stack);
        }

        @Override
        public boolean mayPickup(final Player player) {
            return true;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(final ItemStack stack) {
            return 1;
        }
    }
}
