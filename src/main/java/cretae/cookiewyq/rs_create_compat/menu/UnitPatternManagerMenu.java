package cretae.cookiewyq.rs_create_compat.menu;

import com.refinedmods.refinedstorage.common.support.AbstractBaseContainerMenu;
import com.refinedmods.refinedstorage.common.support.stretching.ScreenSizeListener;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem;
import cretae.cookiewyq.rs_create_compat.network.UnitPatternManagerData;
import cretae.cookiewyq.rs_create_compat.support.UnitManagerSources;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「单元样板管理舱」容器菜单 —— <b>布局/风格完全照抄 RS 的自动合成管理器</b>
 * （{@code AutocrafterManagerContainerMenu} + {@code AbstractStretchingScreen} 的拉伸骨架）。
 *
 * <p><b>照抄了什么、改了什么</b>：</p>
 * <ul>
 *     <li>照抄：面板宽 193、标题行 19px、槽位格 18px、每行 9 列、分组标题带 162×18、
 *     槽位从 {@code 7+1=8} 起、分组行高 = (槽位行数 + 1) × 18、玩家背包 8/…、
 *     拉伸界面由 {@code ScreenSizeListener#resized} 重排槽位（RS 同款机制）。</li>
 *     <li>改了：<b>分组维度换成「一台执行舱 = 一组」</b>（RS 是按自动合成仓分组），
 *     并在末尾追加一个<b>跨仓内存合并视图</b>分组（只出不进）。</li>
 *     <li>裁掉了 RS 管理器的搜索框 / 视图类型 / 搜索模式侧边按钮（它们绑定 RS 自己的配置对象，
 *     与本舱语义无关），以保持「操作说明短、不堆控件」。</li>
 * </ul>
 *
 * <p><b>列表里不显示「序列装配总样板」</b>：本界面只管<b>单元样板</b>，那张「总的」总样板归 RS 原版
 * 「自动合成管理舱」。判据按<b>物品类型</b>（{@link SequenceAssemblyPatternItem#isAssemblyPattern}），
 * <b>不看名字 / 文案 / 语言键</b>；隐藏只发生在<b>显示层</b>（把槽位挪出可见网格、下标原位不动），
 * 容器里的真实数据、分组结构与两端槽位下标全部保持原样 ——
 * 「界面看不见」不等于把玩家已经存下的东西抹掉（旧存档里确实可能有这类样板：读档不做类型校验）。</p>
 *
 * <p><b>隐藏下标由服务端下发，不靠客户端自己看内容（上一版的教训）</b>：客户端的镜像容器要等原版槽位同步
 * 包到达才有内容，而槽位是在菜单构造 / 界面 {@code init} 那一刻按「本组多少格」建的 —— 那一刻容器是空的，
 * 因此「客户端按内容判断」在首帧会把总样板照常画出来（旧存档里的总样板就是这么漏出来的）。
 * 现在服务端在开界面时扫真实容器，把下标放进 {@link UnitPatternManagerData.Section#hiddenSlots()}，
 * 两端一开始就隐藏<b>同一批下标</b>；{@link #isDisplayable} 保留为第二道网（内容变了也仍然不显示）。</p>
 *
 * <p><b>客户端如何重建槽位</b>：槽位数随网络里执行舱台数变化，客户端无法自行推导，
 * 因此 {@link #read(int, Inventory, FriendlyByteBuf)} 按服务端下发的
 * {@link UnitPatternManagerData} 建立<b>同下标顺序</b>的镜像槽位（内容由原版槽位同步写入）。
 * 两端只可能在下发数据相同的前提下重建，所以下标永远一致。</p>
 */
public class UnitPatternManagerMenu extends AbstractBaseContainerMenu implements ScreenSizeListener {
    // ==================== 几何（Menu 坐标；背景精灵坐标 = Menu 坐标 - 1，见硬规则「精灵 = Menu - 1」） ====================
    /** 面板宽（与 RS 管理器一致）。 */
    public static final int PANEL_W = 193;
    /** 槽位格 / 行高。 */
    public static final int ROW_SIZE = 18;
    /** 标题行高（RS 拉伸界面的 TOP_HEIGHT）。 */
    public static final int TOP_HEIGHT = 19;
    /** 每行槽位数。 */
    public static final int COLUMNS = 9;
    /** 被搜索过滤掉的槽位统一挪到裁剪区上方（不可见、不可悬浮，但下标仍在原位）。 */
    private static final int HIDDEN_SLOT_Y = -1000;
    /** 分组槽位起始 x（RS：{@code 7 + 1}）。 */
    public static final int GROUP_SLOT_X = 8;
    /** 玩家背包起始 x。 */
    public static final int PLAYER_INV_X = 8;
    /** 标题文本可用宽度（给搜索框腾位置；超出由 TextMarquee 滚动）。 */
    public static final int TITLE_WIDTH = 50;
    /**
     * 搜索框（照抄 RS 自动合成管理器：放大镜精灵 + 凹槽式输入框，Menu 坐标）。
     * <p><b>坐标必须与 RS 的 {@code AutocrafterManagerScreen} 完全一致</b>（用户要求「移到和自动合成管理器
     * 一样的位置」）：RS 那边是 {@code searchField = (leftPos + 94 + 1, topPos + 6 + 1, 73 - 6)}、
     * {@code SearchIconWidget = (leftPos + 79, topPos + 5)}，即 Menu 坐标
     * <b>输入框 (95,7) 宽 67、放大镜 (79,5) 12×12</b>（{@code +1} 那一项正是本工程的
     * 「精灵 = Menu − 1」硬规则）。凹槽由背景贴图烘焙，位置见 {@code tools/gen_unit_manager_gui_bg.py}。</p>
     */
    public static final int SEARCH_ICON_X = 79;
    public static final int SEARCH_ICON_Y = 5;
    public static final int SEARCH_FIELD_X = 95;
    public static final int SEARCH_FIELD_Y = 7;
    public static final int SEARCH_FIELD_W = 67;

    /** 界面标题语言键。 */
    public static final String TITLE_KEY = "gui.rs_create_compat.unit_pattern_manager";

    // ==================== 状态 ====================
    private final Inventory playerInventory;
    /** 分组视图（客户端为镜像容器，服务端为真实容器；顺序两端一致）。 */
    private final List<SectionView> sections = new ArrayList<>();
    /** 服务端持有的方块实体（客户端为 null）。 */
    @Nullable
    private final UnitPatternManagerBlockEntity manager;
    /** 是否已接入（未接入时不下发任何分组槽位，界面显示灰色空状态，与 RS 管理器一致）。 */
    private final boolean active;
    /** 分组槽位总数（玩家背包起始下标）。 */
    private int sectionSlots;
    /** 上一次重排时被「不显示总样板」规则隐藏掉的槽位数（界面据此选空状态文案，见 {@link #hasHiddenMasterPatternSlots()}）。 */
    private int hiddenMasterPatternSlots;
    /** 裁剪区上/下界（Menu 坐标；客户端由 {@code resized(...)} 给出，服务端取全开区间）。 */
    private int frameTopY;
    private int frameBottomY = Integer.MAX_VALUE;
    /** 玩家背包起始 y（Menu 坐标；搜索后重排槽位时沿用同一值）。 */
    private int playerInventoryY;
    /**
     * 搜索过滤谓词（<b>仅客户端</b>）：返回 false 的槽位会被移出可见网格（不绘制、不可悬浮/点击）。
     * <p>槽位<b>下标在两端的顺序恒定不变</b>（始终按「分组顺序 + 组内下标」全量 addSlot），
     * 过滤只改变<b>位置与可见性</b>——这正是 RS 自动合成管理器搜索框的做法，
     * 因此原版槽位同步的两端下标始终一致。</p>
     */
    @Nullable
    private java.util.function.Predicate<ItemStack> slotFilter;

    // ==================== 构造 ====================

    /** 服务端构造：真实容器（内容此刻就在，因此隐藏下标可直接对真实内容扫出来）。 */
    private UnitPatternManagerMenu(final int id,
                                   final Inventory playerInventory,
                                   final UnitPatternManagerBlockEntity manager,
                                   final List<UnitPatternManagerBlockEntity.Entry> entries) {
        super(RS_Create_Compat.UNIT_PATTERN_MANAGER_MENU.get(), id);
        this.playerInventory = playerInventory;
        this.manager = manager;
        this.active = manager.isNodeActive();
        for (final UnitPatternManagerBlockEntity.Entry entry : entries) {
            // 与下发给客户端的那份数据用同一个扫描函数（同一容器、同一时刻 ⇒ 结果必然一致）
            sections.add(new SectionView(entry.name(), entry.nameIsKey(), entry.recipeType(),
                entry.container(), entry.extractOnly(),
                UnitManagerSources.hiddenMasterPatternSlots(entry.container())));
        }
        // 服务端槽位坐标不参与渲染（客户端会用 resized 重排），沿用 RS 的占位做法
        rebuildSlots(0, TOP_HEIGHT);
    }

    /** 客户端构造：镜像容器（内容由原版槽位同步写入）；隐藏下标<b>由服务端下发</b>，不依赖内容是否已到。 */
    private UnitPatternManagerMenu(final int id,
                                   final Inventory playerInventory,
                                   final UnitPatternManagerData data) {
        super(RS_Create_Compat.UNIT_PATTERN_MANAGER_MENU.get(), id);
        this.playerInventory = playerInventory;
        this.manager = null;
        this.active = data.active();
        for (final UnitPatternManagerData.Section section : data.sections()) {
            sections.add(new SectionView(section.name(), section.nameIsKey(), section.recipeType(),
                new SimpleContainer(section.slotCount()), section.extractOnly(), section.hiddenSlots()));
        }
        rebuildSlots(0, 0);
    }

    /** 服务端：按本条打开请求建菜单（菜单类型工厂不使用本方法，仅 openMenu 时调用）。 */
    public static UnitPatternManagerMenu create(final int id,
                                               final Inventory playerInventory,
                                               final UnitPatternManagerBlockEntity manager,
                                               final List<UnitPatternManagerBlockEntity.Entry> entries) {
        return new UnitPatternManagerMenu(id, playerInventory, manager, entries);
    }

    /** 客户端：读额外数据重建菜单（菜单类型工厂入口）。 */
    public static UnitPatternManagerMenu read(final int id,
                                              final Inventory playerInventory,
                                              final FriendlyByteBuf buf) {
        return new UnitPatternManagerMenu(id, playerInventory, UnitPatternManagerData.read(buf));
    }

    /**
     * 统一的开界面入口（方块右键 / 无线终端模式共用）：
     * 先在服务端建好分组模型（各执行舱 + 终端旧库），再把「分组结构」写进额外数据一起下发。
     */
    public static void open(final ServerPlayer player,
                            final UnitPatternManagerBlockEntity manager,
                            final Component title) {
        final List<UnitPatternManagerBlockEntity.Entry> entries = manager.openEntries();
        player.openMenu(
            new SimpleMenuProvider(
                (id, inventory, ignored) -> create(id, inventory, manager, entries),
                title),
            buf -> manager.getMenuData(entries).write(buf));
    }

    // ==================== 槽位重排（RS 拉伸界面同款机制） ====================

    @Override
    public void resized(final int playerInventoryY, final int topYStart, final int topYEnd) {
        this.playerInventoryY = playerInventoryY;
        this.frameTopY = topYStart;
        this.frameBottomY = topYEnd;
        rebuildSlots(playerInventoryY, topYStart);
    }

    /**
     * 重建全部槽位（下标顺序两端严格一致）：
     * 各分组槽位（按分组顺序、组内按槽位下标升序）→ 玩家背包 36 格。
     *
     * <p><b>两组隐藏规则同一套机制（位置与可见性）</b>：①「序列装配总样板所在格」由服务端下发的下标表决定
     * （见 {@link SectionView#isHidden}），②搜索过滤由 {@code slotFilter} 决定。被隐藏的槽位仍按下标顺序
     * addSlot（保证两端下标一致），只是被挪到裁剪区之外并标记为不可见；可见的槽位在组内<b>紧凑排列</b>
     * （与 RS 自动合成管理器同一套做法）。</p>
     */
    private void rebuildSlots(final int playerInventoryY, final int topYStart) {
        resetSlots();
        int slotCount = 0;
        this.hiddenMasterPatternSlots = 0;
        if (active) {
            int rowY = topYStart;
            for (final SectionView section : sections) {
                final int size = section.container.getContainerSize();
                int visible = 0;
                for (int i = 0; i < size; i++) {
                    // 每个分组的网格都从本组第 0 格起排（与 RS 的「每组 9 列」一致，组间不串列）
                    final ItemStack stack = section.container.getItem(i);
                    // 序列装配总样板一律不显示：①服务端下发的下标表（首帧就生效，不依赖内容是否已同步到客户端）
                    // ②按物品类型的第二道网（内容后到 / 中途变化也不会漏）。两者都只看类型，不看名字 / 文案。
                    // 注意：隐藏 ≠ 移除 —— 下面照样按下标 addSlot，所以两端槽位下标、容器内容全都不变。
                    final boolean displayable = !section.isHidden(i) && isDisplayable(stack);
                    if (!displayable) {
                        hiddenMasterPatternSlots++;
                    }
                    final boolean shown = displayable
                        && (slotFilter == null || slotFilter.test(stack));
                    final int slotX = shown
                        ? GROUP_SLOT_X + ((visible % COLUMNS) * ROW_SIZE) : GROUP_SLOT_X;
                    final int slotY = shown
                        ? rowY + ROW_SIZE + ((visible / COLUMNS) * ROW_SIZE) : HIDDEN_SLOT_Y;
                    if (shown) {
                        visible++;
                    }
                    addSlot(new SectionSlot(section.container, i, slotX, slotY, section.extractOnly,
                        shown, frameTopY, frameBottomY));
                    slotCount++;
                }
                section.visibleSlots = visible;
                if (visible > 0) {
                    rowY += (section.getVisibleRows() + 1) * ROW_SIZE;
                }
            }
        } else {
            for (final SectionView section : sections) {
                section.visibleSlots = 0;
            }
        }
        this.sectionSlots = slotCount;
        addPlayerInventory(playerInventory, PLAYER_INV_X, playerInventoryY);
    }

    // ==================== 对外只读视图 ====================

    public List<SectionView> getSections() {
        return sections;
    }

    public boolean isActive() {
        return active;
    }

    @Nullable
    public UnitPatternManagerBlockEntity getManager() {
        return manager;
    }

    /** 分组槽位总数（玩家背包起始下标）。 */
    public int getSectionSlots() {
        return sectionSlots;
    }

    /** 搜索后<b>仍可见</b>的分组槽位数（= 界面上真正有网格内容的格子数）。 */
    public int getVisibleSectionSlots() {
        int total = 0;
        for (final SectionView section : sections) {
            total += section.getVisibleSlots();
        }
        return total;
    }

    /** 设置搜索过滤谓词（仅客户端调用）；调用方随后需触发一次 {@code resized(...)} 重排。 */
    public void setSlotFilter(@Nullable final java.util.function.Predicate<ItemStack> filter) {
        this.slotFilter = filter;
    }

    /** 当前是否有搜索过滤（界面据此选择「无执行舱」/「无匹配」两种空状态文案）。 */
    public boolean hasFilter() {
        return slotFilter != null;
    }

    /** 上一次重排时是否真的隐藏掉了总样板（界面据此把空状态文案从「没有执行舱」换成「没有匹配的单元样板」）。 */
    public boolean hasHiddenMasterPatternSlots() {
        return hiddenMasterPatternSlots > 0;
    }

    /**
     * 该物品堆是否允许出现在本界面的列表里 —— <b>按物品类型判定</b>：
     * 序列装配<b>总样板</b>（{@link SequenceAssemblyPatternItem}）不属于本界面（它由 RS 原版
     * 「自动合成管理舱」管理），因此即使它躺在某台执行舱的单元槽 / 终端旧库里，也一律不显示。
     *
     * <p><b>为什么会躺在那里</b>：读档路径不做物品类型校验（执行舱 {@code UnitSlots} 走
     * {@code RsccSlotNbt#read} → {@code ItemStack.parse}，终端 {@code UnitLibrary} 走
     * {@code ItemStackHandler} 反序列化），旧版本允许放进去的样板会被原样读回；
     * 本界面呈现的就是容器原内容，所以必须在显示层按类型挡掉。</p>
     *
     * <p><b>为什么不用名字 / 文案 / 语言键做启发式</b>：那些会随语言、改名与词条编辑漂移，
     * 而物品类型是唯一稳定且不误伤的判据 —— 单元样板（{@code SequenceUnitPatternItem}）照常显示。</p>
     *
     * <p>与 {@link SectionView#isHidden} 的关系：后者是服务端下发的下标表（负责<b>首帧</b>就藏住，
     * 不依赖内容是否已同步）；本方法是按当前内容的第二道网。两者都是「藏格子」，不删数据。</p>
     */
    private static boolean isDisplayable(final ItemStack stack) {
        return !SequenceAssemblyPatternItem.isAssemblyPattern(stack);
    }

    // ==================== 交互 ====================

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
        final int playerStart = sectionSlots;
        final int playerEnd = sectionSlots + 36;
        if (index < playerStart) {
            // 分组槽位 → 玩家背包（合并视图也走这条：取出即从代表仓移除）
            if (!moveItemStackTo(stackInSlot, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stackInSlot, 0, playerStart, false)) {
            // 玩家背包 → 分组槽位（放不放得下由容器权威判定：执行舱 acceptsUnit / 合并视图只出不进）
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
        if (manager == null || manager.getLevel() == null) {
            return true;
        }
        // 与高级远程终端一致：位置为原点且世界中不存在该方块实体 = 虚拟方块实体（无线终端场景），恒有效
        final boolean virtual = manager.getBlockPos().equals(BlockPos.ZERO)
            && manager.getLevel().getBlockEntity(BlockPos.ZERO) != manager;
        if (virtual) {
            return true;
        }
        final BlockPos pos = manager.getBlockPos();
        return manager.getLevel().getBlockEntity(pos) == manager
            && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    // ==================== 内部类型 ====================

    /** 一个分组在界面上的视图（标题 + 槽位容器 + 可见槽位数）。 */
    public static final class SectionView {
        private final String name;
        private final boolean nameIsKey;
        /** 本组对应的配方类型 id（如 {@code create:deploying}）；界面据此显示<b>配方名称</b>与提示。 */
        private final String recipeType;
        private final Container container;
        private final boolean extractOnly;
        /**
         * 本组里<b>不显示</b>的槽位下标（服务端按物品类型算出后随界面下发；客户端直接采用）。
         * <p>用 {@code boolean[]} 而不是集合：查一次是 O(1)，且槽位数固定不变（开界面时冻结）。</p>
         */
        private final boolean[] hidden;
        private int visibleSlots;

        private SectionView(final String name, final boolean nameIsKey, final String recipeType,
                            final Container container, final boolean extractOnly,
                            final List<Integer> hiddenSlots) {
            this.name = name;
            this.nameIsKey = nameIsKey;
            this.recipeType = recipeType == null ? "" : recipeType;
            this.container = container;
            this.extractOnly = extractOnly;
            // 掩码只认「落在本组槽位范围内」的下标（越界 / 重复由 UnitPatternManagerData.Section 归一化）
            this.hidden = new boolean[Math.max(0, container.getContainerSize())];
            if (hiddenSlots != null) {
                for (final Integer index : hiddenSlots) {
                    if (index != null && index >= 0 && index < this.hidden.length) {
                        this.hidden[index] = true;
                    }
                }
            }
        }

        /** 该下标是否被「总样板不显示」规则藏起来（两端同源，见 {@link UnitPatternManagerMenu#rebuildSlots}）。 */
        public boolean isHidden(final int index) {
            return index >= 0 && index < hidden.length && hidden[index];
        }

        /** 标题文本：翻译键交给客户端翻译，字面量原样使用（服务端没有语言表）。 */
        public net.minecraft.network.chat.Component getTitle() {
            return nameIsKey
                ? net.minecraft.network.chat.Component.translatable(name)
                : net.minecraft.network.chat.Component.literal(name);
        }

        /** 分组的<b>执行舱名</b>（不含配方类型）：标题与提示统一从这里取。 */
        public String getName() {
            return nameIsKey ? "" : name;
        }

        /** 本组的配方类型 id（空串 = 未绑定 / 合并视图）。 */
        public String getRecipeType() {
            return recipeType;
        }

        public boolean isExtractOnly() {
            return extractOnly;
        }

        public int getVisibleSlots() {
            return visibleSlots;
        }

        public int getVisibleRows() {
            return visibleSlots <= 0 ? 0 : ((visibleSlots - 1) / COLUMNS) + 1;
        }

        public boolean isVisible() {
            return visibleSlots > 0;
        }
    }

    /**
     * 分组槽位：只收紧「能放什么 / 一格一张 / 是否只出不进」，读写全部落在容器上。
     * <p>记住原始 y 是给界面用的：滚动条变化时按「原始 y - 偏移」重设 {@code slot.y}
     * （与 RS 的 {@code AutocrafterManagerSlot#getOriginalY} 同一机制）。</p>
     * <p>{@link #isActive()} 同时收紧到<b>裁剪区</b>与<b>搜索过滤</b>：
     * 与 RS 管理器一样，滚动到框外 / 被搜索隐藏的槽位既不绘制也不可悬浮点击，
     * 因此「悬停判定」与「绘制范围」天然同源，滚动后也不会错位。</p>
     */
    public static final class SectionSlot extends Slot {
        private final boolean extractOnly;
        private final int originalY;
        /** 裁剪区上/下界（Menu 坐标，由 {@code resized(...)} 给出）。 */
        private final int frameTopY;
        private final int frameBottomY;
        /** 是否通过搜索过滤（false = 被移出网格，绝不可交互）。 */
        private final boolean shown;

        public SectionSlot(final Container container, final int index, final int x, final int y,
                           final boolean extractOnly, final boolean shown,
                           final int frameTopY, final int frameBottomY) {
            super(container, index, x, y);
            this.extractOnly = extractOnly;
            this.originalY = y;
            this.shown = shown;
            this.frameTopY = frameTopY;
            this.frameBottomY = frameBottomY;
        }

        public int getOriginalY() {
            return originalY;
        }

        /** 是否在裁剪区内且未被搜索过滤（服务端区间全开 = 恒 true，判定只作用于客户端）。 */
        @Override
        public boolean isActive() {
            return shown && y >= frameTopY && y < frameBottomY;
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return !extractOnly && this.container.canPlaceItem(this.getContainerSlot(), stack);
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
