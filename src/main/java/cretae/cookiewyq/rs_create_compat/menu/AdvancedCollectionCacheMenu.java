package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedCollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 高级归流缓存仓菜单：<b>完全继承基础版</b>的 138 个槽位与全部数据槽
 * （匹配区 48 + 缓存区 48 + 升级 6 + 玩家背包 36），只追加两样东西：
 *
 * <ol>
 *     <li><b>1 个「区域选定器」专用槽</b>（槽位下标 {@link #AREA_SLOT_INDEX} = 138，排在玩家槽位之后）：
 *     普通 {@link SlotItemHandler}（<b>不是</b> {@code UpgradeSlot} —— 后者只收 RS 原版升级物品），
 *     只放行 {@link RS_Create_Compat#AREA_SELECTOR}；</li>
 *     <li><b>6 个额外数据槽</b>（{@link #DATA_AREA_COUNT} 起）：区域数量、总体积、状态（
 *     没装 / 生效 / 空 / 维度不符）、方块坐标 x/y/z。
 *     <p>为什么另开一份 {@link ContainerData}：Base 菜单的数据槽编号是私有契约
 *     （0..28），直接改会牵动基础版界面。{@code addDataSlots} 的槽位编号在
 *     {@code AbstractContainerMenu} 里是<b>按加入顺序连续</b>分配的，所以这里再 add 一份
 *     就自然落在 29..34，服务端 / 客户端照常同步，互不影响。</p></li>
 * </ol>
 */
public class AdvancedCollectionCacheMenu extends CollectionCacheMenu {
    /** 基础版槽位总数 = 匹配 48 + 缓存 48 + 升级 6 + 玩家 36。 */
    public static final int BASE_SLOT_COUNT = MATCH_WINDOW + CACHE_WINDOW
        + UPGRADE_COUNT + 9 * 4;
    /** 区域选定器槽在菜单里的下标（排在所有基础槽位之后）。 */
    public static final int AREA_SLOT_INDEX = BASE_SLOT_COUNT;
    /** 槽位内容区坐标（Menu 坐标 = 精灵坐标 + 1；与升级槽同一行，左侧留白区）。 */
    public static final int AREA_SLOT_X = 10;
    public static final int AREA_SLOT_Y = 219;

    /** 额外数据槽（相对本菜单自己那份 ContainerData 的下标）。 */
    public static final int DATA_AREA_COUNT = 0;
    public static final int DATA_AREA_VOLUME = 1;
    public static final int DATA_AREA_STATUS = 2;
    public static final int DATA_AREA_POS_X = 3;
    public static final int DATA_AREA_POS_Y = 4;
    public static final int DATA_AREA_POS_Z = 5;
    /**
     * 第 101 轮 P1：6 = 当前「正在编辑过滤器的区域」序号（-1 = 没选区域，ghost 窗口为空）。
     * <p>7 起逐区域一个「有效过滤条目数」（最多 {@link #AREA_LIST_SLOT_MAX} 个）——
     * 界面据此画出「区域 1：只吸 3 种 / 区域 2：不吸任何东西」。
     * 区域几何（尺寸 / 名字 / 坐标）仍在选定器物品上，客户端直接读物品组件，不占数据槽。</p>
     */
    public static final int DATA_AREA_EDITING = 6;
    public static final int DATA_AREA_FILTER_BASE = 7;
    /**
     * 数据槽里为逐区域计数预留的最大区域数。
     * <p><b>第 121 轮 C-4</b>：这里必须等于 {@code AreaSelection.ABSOLUTE_MAX_AREAS}（= 64）这个
     * <b>硬上限</b>，而不是 {@code Config.areaSelectorMaxAreas} 的默认值 16。原先写 16 时，
     * 管理员把配置调到 17..64 后，第 17 块及以后区域的「有效过滤条目数」永远从数据槽读回 0
     * （过滤本身照常生效），界面是在说谎。数据槽是固定 {int} 数组，多 48 个 INT 的代价可忽略：
     * 原版容器数据同步是「每槽一个 {@code ClientboundContainerSetDataPacket}（1 个 int）」，
     * 不存在把 64 槽塞进一个包导致超限的问题。
     */
    public static final int AREA_LIST_SLOT_MAX = 64;
    /** 本菜单自己的数据槽总数（6 个区域面板 + 1 个编辑上下文 + 64 个逐区域计数）。 */
    public static final int AREA_DATA_COUNT =
        AdvancedCollectionCacheBlockEntity.areaPanelDataCount() + 1 + AREA_LIST_SLOT_MAX;

    @Nullable
    private final AdvancedCollectionCacheBlockEntity advanced;
    /** 客户端：服务端同步过来的区域数据槽。 */
    private final int[] areaClientData = new int[AREA_DATA_COUNT];
    /** 客户端占位槽（服务端才有真实 handler；客户端只显示同步过来的内容）。 */
    private final ItemStackHandler clientAreaSlot = new ItemStackHandler(1);
    /**
     * 「正在编辑哪个区域的过滤器」（服务端权威；-1 = 没选区域）。
     * <p>这是<b>菜单级</b>状态而不是方块实体级：两个玩家同时开同一台机器，各自可以编辑不同区域，
     * 互不干扰；它只影响「ghost 窗口显示谁的条目」，不参与吸收判定。</p>
     */
    private int editingAreaIndex = -1;

    // ==================== 第 106 轮：区域列表权威快照（S2C） ====================

    /** 上一次发出去的快照对应的「方块实体版本号」（{@code Integer.MIN_VALUE} = 还没发过）。 */
    private int lastSnapshotVersion = Integer.MIN_VALUE;
    /** 上一次发出去的快照里的「正在编辑过滤的区域」（编辑上下文切换也要让客户端重画）。 */
    private int lastSnapshotEditing = Integer.MIN_VALUE;
    /** 服务端对最近一次区域写入动作的拒绝原因（见 {@code AreaSelectorEditPacket.REJECT_*}）。 */
    private int areaRejectCode;
    /** 拒绝原因的单调序号（客户端只在序号变大时提示一次，不刷屏）。 */
    private int areaRejectSeq;
    /** 上一次发出去的快照里的拒绝序号。 */
    private int lastSnapshotRejectSeq = -1;

    public AdvancedCollectionCacheMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public AdvancedCollectionCacheMenu(final int id, final Inventory inventory,
                                       @Nullable final AdvancedCollectionCacheBlockEntity block) {
        // 必须显式传高级版 MenuType：沿用基础版类型会让客户端建成基础版菜单
        // （138 槽 vs 服务端 139 槽），首次全量同步即 initializeContents 越界 ⇒ 客户端断线。
        super(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_MENU.get(), id, inventory, block);
        this.advanced = block;
        // 第 131 轮（用户第 4 条「打开的时候记住上一次选择的那个条目，然后自动切换」）：
        // 打开界面就直接回到上次编辑的区域 —— 记忆值存在方块实体 NBT（LastEditingArea），
        // 这里只做「读回来 + 越界收敛成 -1」（换过选定器 / 删过区域后序号可能失效）。
        // 放在构造器而不是界面 init：这是服务端权威状态（客户端那份从数据槽 DATA_AREA_EDITING 同步），
        // 且 setSynchronizer() 建屏后会立刻 syncMarkers()，那一刻匹配区窗口就已经是这个区域的过滤。
        if (block != null) {
            this.editingAreaIndex = clampEditingArea(block.getLastEditingArea());
        }
        final ItemStackHandler areaSlot = block != null ? block.getAreaSlot() : clientAreaSlot;
        addSlot(new SlotItemHandler(areaSlot, 0, AREA_SLOT_X, AREA_SLOT_Y) {
            @Override
            public boolean mayPlace(final ItemStack stack) {
                return stack.isEmpty() || stack.is(RS_Create_Compat.AREA_SELECTOR.get());
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addDataSlots(new ContainerData() {
            @Override
            public int get(final int index) {
                if (index == DATA_AREA_EDITING) {
                    if (advanced != null) {
                        return editingAreaIndex;
                    }
                    return areaClientData[DATA_AREA_EDITING];
                }
                if (index >= DATA_AREA_FILTER_BASE) {
                    final int areaIndex = index - DATA_AREA_FILTER_BASE;
                    if (areaIndex < 0 || areaIndex >= AREA_LIST_SLOT_MAX) {
                        return 0;
                    }
                    if (advanced != null) {
                        return advanced.getAreaFilterCount(areaIndex);
                    }
                    return areaClientData[index];
                }
                if (advanced != null) {
                    return advanced.areaPanelData(index);
                }
                return index >= 0 && index < AREA_DATA_COUNT ? areaClientData[index] : 0;
            }

            @Override
            public void set(final int index, final int value) {
                if (advanced == null && index >= 0 && index < AREA_DATA_COUNT) {
                    areaClientData[index] = value;
                }
            }

            @Override
            public int getCount() {
                return AREA_DATA_COUNT;
            }
        });
    }

    public static AdvancedCollectionCacheMenu create(final int id, final Inventory inventory,
                                                     final AdvancedCollectionCacheBlockEntity block) {
        return new AdvancedCollectionCacheMenu(id, inventory, block);
    }

    // ==================== 区域选定器槽 ====================

    /** 槽里的选定器（服务端 = 真槽；客户端 = 同步过来的镜像槽）。 */
    public ItemStack getAreaSelectorStack() {
        if (advanced != null) {
            return advanced.getAreaSelectorStack();
        }
        return slots.size() > AREA_SLOT_INDEX ? slots.get(AREA_SLOT_INDEX).getItem() : ItemStack.EMPTY;
    }

    public int getAreaCount() {
        return dataOrZero(DATA_AREA_COUNT);
    }

    public long getAreaVolume() {
        return Math.max(0, dataOrZero(DATA_AREA_VOLUME));
    }

    /** 0 没装 / 1 生效 / 2 区域为空 / 3 维度不符（与方块实体常量一致）。 */
    public int getAreaStatus() {
        return dataOrZero(DATA_AREA_STATUS);
    }

    /** 方块坐标（数据槽 3/4/5；当前界面用不到 —— 区域是绝对坐标，画框直接用物品里的区域数据）。
     *  保留是因为数据槽编号是 139/35 这套口径的一部分（自检 round91 钉住），且诊断 / 未来子界面会用到。 */
    public BlockPos getAreaBlockPos() {
        return new BlockPos(dataOrZero(DATA_AREA_POS_X), dataOrZero(DATA_AREA_POS_Y),
            dataOrZero(DATA_AREA_POS_Z));
    }

    /**
     * 区域选定器的数据被服务端写回后调用（由 {@link cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket}
     * 在写入同一 tick 内通知）。
     * <p>为什么必须显式通知：写回是 {@code ItemStack#set(数据组件)}，它<b>不会</b>触发
     * {@code ItemStackHandler#onContentsChanged}，所以方块实体既不会被标脏（这次编辑可能不落盘），
     * 也可能继续拿本 tick 早先缓存的区域集合 / 旧扫描游标去扫描。</p>
     */
    public void onAreaSelectorEdited() {
        if (advanced != null) {
            advanced.areaSelectorEdited();
        }
    }

    // ==================== 区域列表权威快照（第 106 轮） ====================

    /**
     * 记下「服务端刚拒绝了一次区域写入」。
     * <p>由 {@link cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket} 在每条校验失败
     * 分支调用 —— 旧实现这些分支全是静默 return，玩家点了一个「服务端不认」的区域完全没反馈
     * （用户第 5 条：「选了根本不存在的区域……我还以为是坏了」）。原因随快照下发，
     * 区域列表界面据此给一句明确的话。</p>
     */
    public void noteAreaReject(final int reason) {
        if (reason == cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket.REJECT_NONE) {
            return;
        }
        areaRejectCode = reason;
        areaRejectSeq++;
    }

    /** 服务端：最近一次拒绝原因（客户端读的是快照里的值，不是这个方法）。 */
    public int getAreaRejectCode() {
        return areaRejectCode;
    }

    /**
     * 每 tick 由原版机制调用：服务端在「区域数据 / 编辑上下文 / 拒绝序号」任一变化时补发一份
     * {@code AdvancedAreaSnapshotPacket}（区域列表 + 每区域过滤图标 + 拒绝原因）。
     * <p>只在变化时发：区域数据是低频变化的，每 tick 全量发等于白刷流量；而「变化才发」也保证
     * 玩家在区域列表里编辑完，下一 tick 界面就是服务端的权威结果（自动刷新，不需要手动刷新按钮）。</p>
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (advanced == null) {
            return; // 客户端：没有服务端状态可推
        }
        final net.minecraft.server.level.ServerPlayer player = rscc$getServerPlayer();
        if (player == null) {
            return;
        }
        final int version = advanced.getAreaSnapshotVersion();
        if (version == lastSnapshotVersion && editingAreaIndex == lastSnapshotEditing
            && areaRejectSeq == lastSnapshotRejectSeq) {
            return;
        }
        lastSnapshotVersion = version;
        lastSnapshotEditing = editingAreaIndex;
        lastSnapshotRejectSeq = areaRejectSeq;
        final cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket base =
            advanced.buildAreaSnapshot(containerId);
        // 编辑上下文用「可用区域位次」（= 快照 entries 的下标），快照对外给的是原始下标 ——
        // 两个口径都要给客户端，否则「点行 = 编辑哪个区域的过滤」会错位。
        int editingRawIndex = -1;
        if (editingAreaIndex >= 0 && editingAreaIndex < base.entries().size()) {
            editingRawIndex = base.entries().get(editingAreaIndex).rawIndex();
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new cretae.cookiewyq.rs_create_compat.network.AdvancedAreaSnapshotPacket(
                containerId, base.status(), base.dimension(), base.entries(),
                base.selectedRawIndex(), editingRawIndex, base.unusableCount(),
                areaRejectCode, areaRejectSeq));
    }

    // ==================== 每区域过滤器：编辑上下文 + 数据槽（第 101 轮 P1） ====================

    /** 当前正在编辑过滤器的区域序号（-1 = 没选区域）。客户端从数据槽读。 */
    public int getEditingAreaIndex() {
        if (advanced != null) {
            return editingAreaIndex;
        }
        return areaClientData[DATA_AREA_EDITING];
    }

    /** 某区域的有效过滤条目数（客户端从数据槽读，服务端读方块实体真值）。 */
    public int getAreaFilterCount(final int areaIndex) {
        if (areaIndex < 0 || areaIndex >= AREA_LIST_SLOT_MAX) {
            return 0;
        }
        if (advanced != null) {
            return advanced.getAreaFilterCount(areaIndex);
        }
        return areaClientData[DATA_AREA_FILTER_BASE + areaIndex];
    }

    /**
     * 切换「正在编辑过滤器的区域」（服务端；由 {@code SetAreaFilterContextPacket} 调用）。
     * <p>切换后立刻重发匹配区窗口：客户端那 48 个 ghost 格显示的就是该区域的过滤条目
     * （没有第二套槽位机制，编辑手感与编辑全局匹配区完全一致，标记配置子界面也直接复用）。</p>
     * <p><b>第 131 轮</b>：这里是「记住上次编辑的区域」的<b>唯一持久化写入点</b>（用户第 4 条）——
     * 玩家每切一次区域，就把收敛后的序号写进方块实体 NBT（键 {@code LastEditingArea}），
     * 关界面再打开时构造器读回来直接生效。</p>
     *
     * @param areaIndex {@code -1} = 退出编辑（窗口清空）；越界值收敛到 -1
     */
    public void setEditingArea(final int areaIndex) {
        final int clamped = clampEditingArea(areaIndex);
        // 先落盘再判「值没变就 return」：序号相同也要保证记忆是当前值（例如读档回退成 -1 后
        // 玩家又切回同一区域），否则关界面重开仍会回到旧记忆。
        if (advanced != null) {
            advanced.setLastEditingArea(clamped);
        }
        if (editingAreaIndex == clamped) {
            return;
        }
        editingAreaIndex = clamped;
        setMatchOffset(0);
        syncMarkers();
    }

    /**
     * 把区域序号收敛到当前有效范围（{@code -1} = 没有）。
     * <p>构造器（读记忆）与 {@link #setEditingArea}（切上下文）<b>必须</b>用同一个判据 ——
     * 否则会出现「记忆里是第 5 区、实际只有 3 个区域」这种指向不存在行的上下文，
     * 匹配区标题带会写「区域 6 · 区域 6」却一格都写不进去。</p>
     */
    private int clampEditingArea(final int areaIndex) {
        return areaIndex >= 0 && areaIndex < getAreaCount() ? areaIndex : -1;
    }

    /** 服务端：读方块实体上某区域的过滤条目（供「复制 / 清空」等动作，界面只读）。 */
    public net.minecraft.world.item.ItemStack getAreaFilterStack(final int areaIndex, final int markerIndex) {
        if (advanced == null) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        return advanced.getAreaMarker(areaIndex, markerIndex).displayStack();
    }

    /** 清空某区域的过滤集合（= 该区域从此不吸任何东西）。 */
    public void clearAreaFilter(final int areaIndex) {
        if (advanced == null || advanced.getLevel() == null || advanced.getLevel().isClientSide()) {
            return;
        }
        advanced.clearAreaFilter(areaIndex);
        syncMarkers();
    }

    // ---------- 覆写「当前编辑上下文」钩子：编辑区域时 ghost 窗口 / 写入 / 配置全部落到该区域 ----------

    @Override
    protected java.util.List<cretae.cookiewyq.rs_create_compat.support.MarkerEntry> markerWindowEntries(
        final int start, final int count) {
        if (advanced == null || editingAreaIndex < 0) {
            return java.util.List.of(); // 没选区域：窗口空（高级版不用全局匹配区，见方块实体注释）
        }
        final java.util.List<cretae.cookiewyq.rs_create_compat.support.MarkerEntry> out =
            new java.util.ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            out.add(advanced.getAreaMarker(editingAreaIndex, start + i));
        }
        return out;
    }

    @Override
    protected cretae.cookiewyq.rs_create_compat.support.MarkerEntry markerEntryAt(final int markerIndex) {
        if (advanced == null || editingAreaIndex < 0) {
            return cretae.cookiewyq.rs_create_compat.support.MarkerEntry.EMPTY;
        }
        return advanced.getAreaMarker(editingAreaIndex, markerIndex);
    }

    @Override
    protected void writeMarkerAt(final int markerIndex, final net.minecraft.world.item.ItemStack carried) {
        if (advanced == null || editingAreaIndex < 0) {
            return; // 没选区域：不写（界面会提示先选区域）
        }
        if (carried.isEmpty()) {
            advanced.removeAreaMarker(editingAreaIndex, markerIndex);
        } else {
            advanced.addAreaMarker(editingAreaIndex, carried);
        }
    }

    @Override
    protected CollectionCacheBlockEntity.MarkerAddResult addMarkerFromUi(final net.minecraft.world.item.ItemStack stack) {
        if (advanced == null || editingAreaIndex < 0) {
            return CollectionCacheBlockEntity.MarkerAddResult.INVALID;
        }
        return advanced.addAreaMarker(editingAreaIndex, stack);
    }

    /**
     * <b>第 114 轮复核 M3 修复</b>：界面里「数量门槛 / 严格 NBT / 标签集合」这条路径走的是
     * <b>5 参包</b>（{@code SetCollectionMarkerConfigPacket}）→ 基类 6 参 {@code applyMarkerConfig}，
     * 而本类之前只覆写了 3 参版（{@link #applyMarkerConfig(int, long, boolean, java.util.List)}）。
     * 后果：在高级仓里编辑区域标记的规则时，写入落到了**高级版根本不读的全局匹配区**（{@code markers}）——
     * 界面看起来保存了，吸收行为一个字节都没变，还在存档里留下没人用的垃圾。
     * <p>这里把它转发到 {@code setAreaMarkerConfig}（保留条目自身的 nbt / id，只更新规则），
     * 与 3 参覆写口径一致。</p>
     */
    @Override
    protected void applyMarkerConfig(final int markerIndex, final boolean fluid,
                                     @Nullable final ResourceLocation id,
                                     @Nullable final net.minecraft.nbt.CompoundTag nbt,
                                     final long amount, final boolean matchNbt,
                                     final java.util.List<ResourceLocation> tags) {
        if (advanced == null || editingAreaIndex < 0) {
            // 没有区域上下文（纯基础版行为 / 区域列表没加载）：交给基类写全局匹配区
            super.applyMarkerConfig(markerIndex, fluid, id, nbt, amount, matchNbt, tags);
            return;
        }
        if (fluid) {
            // 每区域过滤器目前只支持物品标记（流体标记走全局匹配区），保持基类语义避免静默吞掉
            super.applyMarkerConfig(markerIndex, fluid, id, nbt, amount, matchNbt, tags);
            return;
        }
        final cretae.cookiewyq.rs_create_compat.support.MarkerEntry existing =
            advanced.getAreaMarker(editingAreaIndex, markerIndex);
        if (existing == null || existing.isEmpty()) {
            // 第 130 轮（用户原话：「我在匹配器中匹配就应该是可以直接拖进去」）：
            // **空格子 + JEI 拖入 / 粘贴** 这条路径以前是**静默丢包** —— 下面那行只做 update，
            // setAreaMarkerConfig 找不到条目就 return false，于是玩家把物品从 JEI 拖到空格子上
            // 什么都不会发生（最坏的一种坏）。这里改成真的新增一条（去重 / 容量 / 体积预算在
            // addAreaMarkerEntry 内部，与 Shift+点击那条路同一口径），再把数量 / 严格 NBT / 标签套上去。
            if (id == null) {
                return; // 既没有条目也没有 id：没有可写的东西（不编造一条空标记）
            }
            final cretae.cookiewyq.rs_create_compat.support.MarkerEntry marker =
                new cretae.cookiewyq.rs_create_compat.support.MarkerEntry(false, id,
                    nbt == null ? new net.minecraft.nbt.CompoundTag() : nbt,
                    Math.max(1L, amount), matchNbt,
                    tags == null ? java.util.List.of() : tags);
            final CollectionCacheBlockEntity.MarkerAddResult result =
                advanced.addAreaMarkerEntry(editingAreaIndex, marker);
            notifyMarkerResult(result);
            syncMarkers();
            return;
        }
        advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,
            tags != null ? tags : existing.tags());
        syncMarkers();
    }

    /**
     * 新增标记失败时给玩家一句话（与基础版 Shift+点击那套文案用<b>同一组语言键</b>，
     * 见 {@code CollectionCacheMenu} 的 {@code LANG_MARK}）。成功不提示：匹配区里当场就看得见。
     */
    private void notifyMarkerResult(final CollectionCacheBlockEntity.MarkerAddResult result) {
        final net.minecraft.server.level.ServerPlayer player = rscc$getServerPlayer();
        if (player == null) {
            return;
        }
        final String lang = "gui.rs_create_compat.collection_cache.";
        switch (result) {
            case DUPLICATE -> player.displayClientMessage(
                Component.translatable(lang + "duplicate"), true);
            case FULL -> player.displayClientMessage(
                Component.translatable(lang + "shift_mark.full",
                    CollectionCacheMenu.MATCH_CAPACITY), true);
            case TOO_COMPLEX -> player.displayClientMessage(
                Component.translatable(lang + "shift_mark.too_complex",
                    cretae.cookiewyq.rs_create_compat.support.MarkerBudget.MAX_TOTAL_WIRE_BYTES / 1024),
                true);
            case INVALID -> player.displayClientMessage(
                Component.translatable(lang + "shift_mark.invalid"), true);
            default -> {
                // OK / COVERED_BY_TAG：成功不需要一句话（匹配区里当场就看得见）
            }
        }
    }

    @Override
    protected void applyMarkerConfig(final int markerIndex, final long amount, final boolean matchNbt,
                                     @Nullable final java.util.List<ResourceLocation> tags) {
        if (advanced == null || editingAreaIndex < 0) {
            return;
        }
        final cretae.cookiewyq.rs_create_compat.support.MarkerEntry existing =
            advanced.getAreaMarker(editingAreaIndex, markerIndex);
        advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex, amount, matchNbt,
            tags != null ? tags : existing.tags());
    }

    @Override
    protected boolean applyMarkerTags(final int markerIndex, final java.util.List<ResourceLocation> tags) {
        if (advanced == null || editingAreaIndex < 0) {
            return false;
        }
        final cretae.cookiewyq.rs_create_compat.support.MarkerEntry existing =
            advanced.getAreaMarker(editingAreaIndex, markerIndex);
        return advanced.setAreaMarkerConfig(editingAreaIndex, markerIndex,
            existing.amount(), existing.matchNbt(), tags);
    }

    @Override
    public int editingContextMarkerCount() {
        if (advanced == null || editingAreaIndex < 0) {
            return 0;
        }
        return advanced.getAreaFilter(editingAreaIndex).size();
    }

    private int dataOrZero(final int index) {
        return index >= 0 && index < AREA_DATA_COUNT ? areaClientDataOrServer(index) : 0;
    }

    private int areaClientDataOrServer(final int index) {
        // 服务端与客户端共用同一个读取入口：服务端读方块实体，客户端读同步值
        return advanced != null ? advanced.areaPanelData(index) : areaClientData[index];
    }

    /**
     * Shift 点击：让专用槽与背包之间的搬运可用，其余路径逐字交给基础版。
     * <p>必须覆写：基础版 {@code quickMoveStack} 把「任何 >= PLAYER_START 的下标」都当成玩家背包，
     * 于是 Shift 点击区域槽会被当成「写标记 / 塞缓存」—— 都不对。
     * 这里先把专用槽这一条截住：槽 → 背包；背包里的选定器 → 优先放进专用槽。</p>
     * <p><b>2026-10-25（第 95 轮第 13 条）</b>：基础版的「背包 → 匹配标记」新默认行为在这里必须被
     * <b>区域选定器提前截住</b>：选定器不是「要吸的东西」，把它当成标记写进匹配区是纯粹的噪声；
     * 专用槽被占时就<b>什么都不做</b>（返回空），而不是落到 super 去写标记。</p>
     */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (index == AREA_SLOT_INDEX) {
            final ItemStack copy = slot.getItem().copy();
            if (!moveItemStackTo(slot.getItem(), PLAYER_START, PLAYER_START + 36, true)) {
                return ItemStack.EMPTY;
            }
            if (slot.getItem().isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            return copy;
        }
        if (index >= PLAYER_START && slot.getItem().is(RS_Create_Compat.AREA_SELECTOR.get())) {
            final ItemStack copy = slot.getItem().copy();
            if (moveItemStackTo(slot.getItem(), AREA_SLOT_INDEX, AREA_SLOT_INDEX + 1, false)) {
                if (slot.getItem().isEmpty()) {
                    slot.setByPlayer(ItemStack.EMPTY);
                } else {
                    slot.setChanged();
                }
                return copy;
            }
            // 专用槽已被占用 ⇒ 什么都不做（既不是标记，也不硬塞缓存）
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player, index);
    }
}
