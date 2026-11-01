package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.DecompositionGeneratorBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.DecompositionGeneratorSpec;
import cretae.cookiewyq.rs_create_compat.support.DecompositionTable;
import cretae.cookiewyq.rs_create_compat.support.RsccSpeedStackUpgradeContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 降解室发电机的菜单（<b>第 123 轮彻底重做</b>）：<b>恒定 62 个槽</b>，
 * = 20 机器（16 输入 + 4 副产物输出）+ 6 升级 + 27 主背包 + 9 快捷栏。
 *
 * <h2>为什么是「恒定 62 格」而不是分页</h2>
 * <p>旧版（第 101–122 轮）是把 256 格库存「分页」暴露成 54 格 + 1 个展示槽，共 91 个槽。
 * 分页带来三个代价：① 界面要额外画翻页按钮 / 页码；② 「槽位下标 → 真实库存下标」多一层映射，
 * 一旦两端映射不一致就断线；③ 第 122 轮实机崩溃就是这么来的 ——
 * {@code PLAYER_SLOT_COUNT} 被手写成 {@code 36 + 9 = 45}（把「36 格总数」又加了一遍快捷栏），
 * 常量声明 100、实际只加了 91 个槽，{@code moveItemStackTo} 摸到下标 91 直接
 * {@code IndexOutOfBoundsException: Index 91 out of bounds for length 91}。</p>
 * <p>重做后的库存只有 20 格（{@link DecompositionGeneratorSpec#INVENTORY_SIZE}），
 * 一次全画出来就是 20 格 ⇒ 不再需要分页、展示槽、页码、按钮，槽位与库存下标<b>一一对应</b>。
 * 玩家区常量全部由 {@link DecompositionGeneratorSpec} 的行列推导
 * （{@link DecompositionGeneratorSpec#PLAYER_SLOT_COUNT} = 27 + 9 = 36），任何地方都不再手写数字。</p>
 *
 * <h2>槽位下标布局（{@link #TOTAL_SLOT_COUNT} 必须等于构造器 addSlot 的实际次数）</h2>
 * <pre>
 * 0  .. 15   16 格输入（4 列 × 4 行）
 * 16 .. 19   4 格副产物输出（2 列 × 2 行，只出不进）
 * 20 .. 25   6 格升级（3 列 × 2 行，只收 speed_upgrade / stack_upgrade）
 * 26 .. 52   玩家主背包 3 行 × 9（容器下标 = 9 .. 35，跳过快捷栏）
 * 53 .. 61   玩家快捷栏（容器下标 = 0 .. 8）
 * </pre>
 * <p>这个「62」有自检盯着（界面自检会拿 {@code menu.slots.size()} 与
 * {@link #TOTAL_SLOT_COUNT} 对账）。</p>
 *
 * <h2>两端槽位数量严格一致的实现方式</h2>
 * <ul>
 *     <li><b>服务端</b>：直接包 {@code generator.getInventory()}（20 格）与
 *     {@code generator.getUpgradeContainer()}（6 格 {@link RsccSpeedStackUpgradeContainer}），槽位就是真实库存；</li>
 *     <li><b>客户端</b>：没有方块实体，构造器只拿到 {@link Inventory} ⇒ 自己建一份
 *     <b>20 格镜像 + 6 格镜像</b>，内容由原版槽位同步写入。镜像的 {@code isItemValid} /
 *     {@code getSlotLimit} 与服务端<b>同一判据</b>（{@link DecompositionTable#isDecomposable} +
 *     {@link DecompositionGeneratorSpec#inputSlotLimit(int)}，堆叠升级数取自数据槽），
 *     否则 Shift 点击的客户端预测会与服务端结果分叉（表现为「物品闪回」）。</li>
 * </ul>
 *
 * <h2>数据槽（{@link #DATA_SLOT_COUNT} = 14，全部服务端权威）</h2>
 * <p>界面绝不读本地 {@code Config}、也绝不自己算进度 / 电量：专服上客户端配置可能不同，
 * 各算一遍必然分叉（范围充电器踩过同类问题）。语义逐条见契约文档第 3 节。</p>
 */
public class DecompositionGeneratorMenu extends AbstractContainerMenu {
    /** 数据槽数量（必须与方块实体一致：解除契约第 3 节 = 14）。 */
    public static final int DATA_SLOT_COUNT = DecompositionGeneratorBlockEntity.DATA_SLOT_COUNT;

    // ==================== 数据槽下标（契约第 3 节，界面唯一数据来源） ====================
    /** 本机缓存电量（FE）。 */
    public static final int DATA_ENERGY = 0;
    /** 本机缓存容量（FE）。 */
    public static final int DATA_ENERGY_CAPACITY = 1;
    /** 当前进度（tick）。 */
    public static final int DATA_PROGRESS = 2;
    /** 当前这一件实际需要的 tick 数（0 = 空闲）。 */
    public static final int DATA_PROGRESS_TOTAL = 3;
    /** 当前处理中的输入格下标（-1 = 空闲）。 */
    public static final int DATA_CURRENT_SLOT = 4;
    /** 状态位（见下面的 FLAG_*）。 */
    public static final int DATA_STATUS = 5;
    /** 速度升级个数。 */
    public static final int DATA_SPEED_UPGRADES = 6;
    /** 堆叠升级个数。 */
    public static final int DATA_STACK_UPGRADES = 7;
    /** 上一次完成时的发电量（FE）。 */
    public static final int DATA_LAST_GENERATED = 8;
    /** 相邻推送上限（FE/t，服务端 Config 下发）。 */
    public static final int DATA_PUSH_PER_TICK = 9;
    /** 上一 tick 实际推送的 FE。 */
    public static final int DATA_PUSHED_LAST_TICK = 10;
    /** 非空输入格数。 */
    public static final int DATA_OCCUPIED_INPUTS = 11;
    /** 输入格数（16）。 */
    public static final int DATA_INPUT_COUNT = 12;
    /** 副产物格数（4）。 */
    public static final int DATA_OUTPUT_COUNT = 13;

    /**
     * 状态位：缓存已满（发电暂停）。
     * <p>四个 FLAG_* 的位序与契约第 3 节逐位一致；方块实体按同一份位序写数据槽，
     * 两端只差一个 {@code &}。</p>
     */
    public static final int FLAG_BUFFER_FULL = 1;
    /** 状态位：正在降解。 */
    public static final int FLAG_WORKING = 1 << 1;
    /** 状态位：已接进 RS 网络。 */
    public static final int FLAG_NETWORK = 1 << 2;
    /** 状态位：副产物槽已满（降解暂停）。 */
    public static final int FLAG_OUTPUT_FULL = 1 << 3;

    // ==================== 槽位下标布局（两端共用，改这里等于同时改两端） ====================
    /** 机器槽起点（含）：16 输入 + 4 副产物 = 20 格。 */
    public static final int MACHINE_SLOT_START = 0;
    /** 机器槽数（20）= 库存格数。 */
    public static final int MACHINE_SLOT_COUNT = DecompositionGeneratorSpec.INVENTORY_SIZE;
    /** 机器槽终点（不含）。 */
    public static final int MACHINE_SLOT_END = MACHINE_SLOT_START + MACHINE_SLOT_COUNT;
    /** 升级槽起点（含）。 */
    public static final int UPGRADE_SLOT_START = MACHINE_SLOT_END;
    /** 升级槽终点（不含）。 */
    public static final int UPGRADE_SLOT_END = UPGRADE_SLOT_START + DecompositionGeneratorSpec.UPGRADE_SLOTS;
    /** 玩家主背包槽数（27 = 3 行 × 9）。 */
    public static final int PLAYER_MAIN_SLOT_COUNT = DecompositionGeneratorSpec.PLAYER_MAIN_COUNT;
    /** 快捷栏槽数（9）。 */
    public static final int PLAYER_HOTBAR_SLOT_COUNT = DecompositionGeneratorSpec.PLAYER_HOTBAR_COUNT;
    /**
     * 玩家区槽数：<b>27 + 9 = 36</b>。
     *
     * <h2>这里曾经写成 {@code 36 + 9}（= 45），是第 122 轮实机崩溃的根因</h2>
     * <p>原版 {@link Inventory} 一共只有 <b>36</b> 格（主背包 27 + 快捷栏 9），{@code 36 + 9} 等于
     * 把「36 格总数」又加了一遍快捷栏：常量声明 45 ⇒ 玩家区终点漂到 100，而构造器按 27 + 9 只加了
     * 36 格 ⇒ 菜单实际 {@code slots.size() = 91}。Shift + 左键走 {@link #quickMoveStack} 时把
     * {@code (82, 100)} 传给 {@code moveItemStackTo}，下标 91 直接数组越界。</p>
     * <p>所以这个常量必须<b>由 {@code Spec} 的行列推导</b>，绝不再手写一个「看起来对」的数字。</p>
     */
    public static final int PLAYER_SLOT_COUNT = PLAYER_MAIN_SLOT_COUNT + PLAYER_HOTBAR_SLOT_COUNT;
    /** 玩家槽起点（含）。 */
    public static final int PLAYER_SLOT_START = UPGRADE_SLOT_END;
    /** 玩家槽终点（不含）。 */
    public static final int PLAYER_SLOT_END = PLAYER_SLOT_START + PLAYER_SLOT_COUNT;
    /** 总槽数（62；自检断言用：必须等于两端 addSlot 次数）。 */
    public static final int TOTAL_SLOT_COUNT = PLAYER_SLOT_END;

    /**
     * 本机允许安装的升级（契约第 2 节）：只收速度 / 堆叠。
     * <p>降解放电不会因为别种升级变化，界面就不该让玩家放得进去 ——
     * 放进去不生效比放不进去更让人困惑。</p>
     */
    public static final List<String> ALLOWED_UPGRADES = List.of(
        RsccSpeedStackUpgradeContainer.SPEED_PATH,
        RsccSpeedStackUpgradeContainer.STACK_PATH);

    /** 服务端持有的方块实体；<b>客户端恒为 null</b>（客户端只有数据槽 + 镜像容器）。 */
    @Nullable
    private final DecompositionGeneratorBlockEntity generator;
    private final ContainerData data;
    /** 客户端 20 格库存镜像；服务端为 null。 */
    @Nullable
    private final ItemStackHandler clientInventoryMirror;
    /** 客户端 6 格升级镜像；服务端为 null。 */
    @Nullable
    private final ItemStackHandler clientUpgradeMirror;

    /** 客户端重建菜单用（无方块实体，数据由数据槽同步）。 */
    public DecompositionGeneratorMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public DecompositionGeneratorMenu(final int id, final Inventory inventory,
                                      @Nullable final DecompositionGeneratorBlockEntity generator) {
        super(RS_Create_Compat.DECOMPOSITION_GENERATOR_MENU.get(), id);
        this.generator = generator;
        this.data = generator != null ? generator.getContainerData() : new SimpleContainerData(DATA_SLOT_COUNT);
        addDataSlots(data);

        // ---- ① 20 格机器库存（16 输入 + 4 副产物）----
        // 服务端直接包真库存；客户端用「同判据的镜像」，保证 shift 点击的两端预测一致。
        final IItemHandler machineHandler;
        if (generator != null) {
            machineHandler = generator.getInventory();
            this.clientInventoryMirror = null;
        } else {
            this.clientInventoryMirror = new ClientInventoryMirror(this.data);
            machineHandler = this.clientInventoryMirror;
        }
        for (int index = 0; index < DecompositionGeneratorSpec.INPUT_COUNT; index++) {
            addSlot(new InputSlot(machineHandler, DecompositionGeneratorSpec.inputSlot(index),
                slotX(DecompositionGeneratorSpec.INPUT_X, index, DecompositionGeneratorSpec.INPUT_COLS),
                slotY(DecompositionGeneratorSpec.INPUT_Y, index, DecompositionGeneratorSpec.INPUT_COLS)));
        }
        for (int index = 0; index < DecompositionGeneratorSpec.OUTPUT_COUNT; index++) {
            addSlot(new OutputSlot(machineHandler, DecompositionGeneratorSpec.outputSlot(index),
                slotX(DecompositionGeneratorSpec.OUTPUT_X, index, DecompositionGeneratorSpec.OUTPUT_COLS),
                slotY(DecompositionGeneratorSpec.OUTPUT_Y, index, DecompositionGeneratorSpec.OUTPUT_COLS)));
        }

        // ---- ② 6 格升级（服务端复用方块实体的 RsccSpeedStackUpgradeContainer）----
        final IItemHandler upgradeHandler;
        if (generator != null) {
            upgradeHandler = generator.getUpgradeContainer();
            this.clientUpgradeMirror = null;
        } else {
            this.clientUpgradeMirror = new ItemStackHandler(DecompositionGeneratorSpec.UPGRADE_SLOTS);
            upgradeHandler = this.clientUpgradeMirror;
        }
        for (int index = 0; index < DecompositionGeneratorSpec.UPGRADE_SLOTS; index++) {
            addSlot(new UpgradeSlot(upgradeHandler, index,
                slotX(DecompositionGeneratorSpec.UPGRADE_X, index, DecompositionGeneratorSpec.UPGRADE_COLS),
                slotY(DecompositionGeneratorSpec.UPGRADE_Y, index, DecompositionGeneratorSpec.UPGRADE_COLS),
                ALLOWED_UPGRADES));
        }

        // ---- ③ 玩家背包 + 快捷栏（与原版完全相同的几何；顺序决定下标分界）----
        // 先主背包 27 格（容器下标 9..35），再快捷栏 9 格（容器下标 0..8）。
        for (int row = 0; row < DecompositionGeneratorSpec.PLAYER_INV_ROWS; row++) {
            for (int col = 0; col < DecompositionGeneratorSpec.PLAYER_COLS; col++) {
                addSlot(new Slot(inventory,
                    col + row * DecompositionGeneratorSpec.PLAYER_COLS + PLAYER_HOTBAR_SLOT_COUNT,
                    DecompositionGeneratorSpec.PLAYER_INV_X + col * DecompositionGeneratorSpec.SLOT_PITCH,
                    DecompositionGeneratorSpec.PLAYER_INV_Y + row * DecompositionGeneratorSpec.SLOT_PITCH));
            }
        }
        for (int col = 0; col < PLAYER_HOTBAR_SLOT_COUNT; col++) {
            addSlot(new Slot(inventory, col,
                DecompositionGeneratorSpec.PLAYER_INV_X + col * DecompositionGeneratorSpec.SLOT_PITCH,
                DecompositionGeneratorSpec.HOTBAR_Y));
        }
    }

    /** 网格第 {@code index} 格的 x（列优先，间距 {@link DecompositionGeneratorSpec#SLOT_PITCH}）。 */
    private static int slotX(final int originX, final int index, final int cols) {
        return originX + (index % cols) * DecompositionGeneratorSpec.SLOT_PITCH;
    }

    /** 网格第 {@code index} 格的 y。 */
    private static int slotY(final int originY, final int index, final int cols) {
        return originY + (index / cols) * DecompositionGeneratorSpec.SLOT_PITCH;
    }

    public static DecompositionGeneratorMenu create(final int id, final Inventory inventory,
                                                    final DecompositionGeneratorBlockEntity generator) {
        return new DecompositionGeneratorMenu(id, inventory, generator);
    }

    /** 服务端菜单持有的方块实体；客户端恒为 null。 */
    @Nullable
    public DecompositionGeneratorBlockEntity getGenerator() {
        return generator;
    }

    // ==================== 数据槽读取（客户端界面唯一数据来源） ====================

    /** 本机缓存电量（FE）。 */
    public int getEnergyStored() {
        return data.get(DATA_ENERGY);
    }

    /** 本机缓存容量（FE）。 */
    public int getEnergyCapacity() {
        return data.get(DATA_ENERGY_CAPACITY);
    }

    /** 当前进度（tick）。 */
    public int getProgress() {
        return data.get(DATA_PROGRESS);
    }

    /** 当前这一件实际需要的 tick 数（0 = 空闲）。 */
    public int getProgressTotal() {
        return data.get(DATA_PROGRESS_TOTAL);
    }

    /** 当前处理中的输入格下标（-1 = 空闲）。 */
    public int getCurrentSlot() {
        return data.get(DATA_CURRENT_SLOT);
    }

    /** 速度升级个数。 */
    public int getSpeedCount() {
        return data.get(DATA_SPEED_UPGRADES);
    }

    /** 堆叠升级个数。 */
    public int getStackCount() {
        return data.get(DATA_STACK_UPGRADES);
    }

    /** 上一次完成时的发电量（FE）。 */
    public int getLastGenerated() {
        return data.get(DATA_LAST_GENERATED);
    }

    /** 相邻推送上限（FE/t，服务端下发）。 */
    public int getPushPerTick() {
        return data.get(DATA_PUSH_PER_TICK);
    }

    /** 上一 tick 实际推给邻居的 FE。 */
    public int getPushedLastTick() {
        return data.get(DATA_PUSHED_LAST_TICK);
    }

    /** 非空输入格数。 */
    public int getOccupiedInputCount() {
        return data.get(DATA_OCCUPIED_INPUTS);
    }

    /** 输入格数（16，服务端下发；界面不该自己写死）。 */
    public int getInputSlotCount() {
        return data.get(DATA_INPUT_COUNT);
    }

    /** 副产物格数（4，服务端下发）。 */
    public int getOutputSlotCount() {
        return data.get(DATA_OUTPUT_COUNT);
    }

    /** 缓存已满暂停（此时不消耗物品）。 */
    public boolean isBufferFull() {
        return (data.get(DATA_STATUS) & FLAG_BUFFER_FULL) != 0;
    }

    /** 正在降解。 */
    public boolean isWorking() {
        return (data.get(DATA_STATUS) & FLAG_WORKING) != 0;
    }

    /** 已接进 RS 网络。 */
    public boolean hasNetwork() {
        return (data.get(DATA_STATUS) & FLAG_NETWORK) != 0;
    }

    /** 副产物槽已满暂停（此时不消耗物品）。 */
    public boolean isOutputFull() {
        return (data.get(DATA_STATUS) & FLAG_OUTPUT_FULL) != 0;
    }

    // ==================== 快速移动 ====================

    @Override
    public boolean stillValid(final Player player) {
        return generator != null
            && generator.getLevel() != null
            && generator.getLevel().getBlockEntity(generator.getBlockPos()) == generator
            && player.distanceToSqr(generator.getBlockPos().getX() + 0.5,
            generator.getBlockPos().getY() + 0.5,
            generator.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /**
     * Shift + 左键的快速移动。
     *
     * <h2>四个方向</h2>
     * <ol>
     *     <li><b>机器 → 玩家背包</b>：{@code reverse = true}，先填快捷栏再自下而上回填主背包（同原版箱子）；</li>
     *     <li><b>升级槽 → 玩家背包</b>：同上；</li>
     *     <li><b>玩家 → 机器</b>：<b>全库存扫描</b>（{@code moveItemStackTo} 扫全部 20 格，输入格
     *     {@code mayPlace} 里就是 {@link DecompositionTable#isDecomposable}，副产物格恒 false）；
     *     <b>不可降解物原地不动</b> + actionbar 提示（用户第 118 轮口径：不是「状态栏提示」，
     *     而是根本放不进去）；</li>
     *     <li><b>玩家 → 升级槽</b>：速度 / 堆叠升级优先进 6 个升级槽，放不下再按普通物品处理。</li>
     * </ol>
     *
     * <h2>区间边界（第 122 轮实机崩溃的唯一入口）</h2>
     * <p>原版 {@code AbstractContainerMenu#moveItemStackTo(start, end, ...)} 的 {@code end} 是
     * <b>不含</b>的上界，循环里取的是 {@code slots.get(end - 1)}，所以每个区间都必须满足
     * {@code 0 <= start < end <= slots.size()}。第 122 轮崩溃就是玩家区终点漂到 100 而菜单实际只有
     * 91 个槽（{@code PLAYER_SLOT_COUNT} 被误写成 45）。现在常量由 {@code Spec} 推导，
     * 下面再与 {@code slots.size()} 取 {@code Math.min} 兜底：将来谁再加 / 删槽忘了改常量，
     * 也只会让「这个方向挪不动」，不会把客户端崩在 {@code moveItemStackTo} 里。</p>
     */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        final Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        // 所有区间都与真实槽数取交（兜底，只收不放）：越界只表现为「挪不动」
        final int machineStart = Math.min(MACHINE_SLOT_START, this.slots.size());
        final int machineEnd = Math.min(MACHINE_SLOT_END, this.slots.size());
        final int upgradeStart = Math.min(UPGRADE_SLOT_START, this.slots.size());
        final int upgradeEnd = Math.min(UPGRADE_SLOT_END, this.slots.size());
        final int playerStart = Math.min(PLAYER_SLOT_START, this.slots.size());
        final int playerEnd = Math.min(PLAYER_SLOT_END, this.slots.size());
        // 主背包 / 快捷栏分界：构造器先加主背包 27 格、后加快捷栏 9 格
        final int mainEnd = Math.min(playerStart + PLAYER_MAIN_SLOT_COUNT, playerEnd);
        if (machineStart >= machineEnd || playerStart >= playerEnd) {
            // 槽位根本没加进菜单（构造器被改坏）：宁可不移动，也不越界
            return ItemStack.EMPTY;
        }
        final ItemStack slotStack = slot.getItem();
        final ItemStack original = slotStack.copy();
        if (index >= machineStart && index < machineEnd) {
            // 机器 → 玩家背包（reverse=true：先填快捷栏，再自下而上回填主背包）
            if (!moveItemStackTo(slotStack, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= upgradeStart && index < upgradeEnd) {
            // 升级槽 → 玩家背包（同上）
            if (!moveItemStackTo(slotStack, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= playerStart && index < playerEnd) {
            if (isAllowedUpgrade(slotStack)
                && moveItemStackTo(slotStack, upgradeStart, upgradeEnd, false)
                && slotStack.isEmpty()) {
                // 升级物品整叠进了升级槽，收工
            } else if (DecompositionTable.isDecomposable(slotStack)) {
                // 玩家 → 机器：全库存扫描；机器满了就在玩家背包内互换（主背包 ↔ 快捷栏）
                if (!moveItemStackTo(slotStack, machineStart, machineEnd, false)) {
                    final boolean fromMain = index < mainEnd;
                    final boolean moved = fromMain
                        ? moveItemStackTo(slotStack, mainEnd, playerEnd, false)
                        : moveItemStackTo(slotStack, playerStart, mainEnd, false);
                    if (!moved) {
                        return ItemStack.EMPTY;
                    }
                }
            } else if (!isAllowedUpgrade(slotStack)) {
                // 既不是可降解物、也不是本机接受的升级 ⇒ 原地不动 + 提示
                reject(player);
                return ItemStack.EMPTY;
            }
            // 其余情况 = 本机接受的升级但 6 个升级槽放不下（满了 / 同种上限）：原地不动，
            // 不弹「无法降解」——那句提示会误导玩家以为升级物是不能降解的垃圾。
        } else {
            // 不在任何已知区间（未来若有人在中间插槽而忘了更新区间）：不移动
            return ItemStack.EMPTY;
        }
        if (slotStack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (slotStack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, slotStack);
        return original;
    }

    /** 这个物品是不是本机允许安装的升级（速度 / 堆叠）。 */
    private static boolean isAllowedUpgrade(final ItemStack stack) {
        final String kind = RsccSpeedStackUpgradeContainer.kindOf(stack);
        return kind != null && ALLOWED_UPGRADES.contains(kind);
    }

    /** 不可降解提示（actionbar；只在服务端发，客户端不会重复）。 */
    private static void reject(final Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(
                Component.translatable("gui.rs_create_compat.decomposition_generator.reject"), true);
        }
    }

    // ==================== 槽实现 ====================

    /**
     * 输入槽：只收可降解物（{@link DecompositionTable#isDecomposable}）。
     * <p>方块实体的库存 {@code isItemValid} 已经是同一判据，这里再显式写一次是<b>双保险</b>：
     * 槽位是玩家能接触到的第一道门，判据写在槽上意味着「拖进来就放不下」，
     * 而不是「放进去了下一 tick 被吐出来」。</p>
     */
    public static final class InputSlot extends SlotItemHandler {
        public InputSlot(final IItemHandler handler, final int index, final int x, final int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return DecompositionTable.isDecomposable(stack) && super.mayPlace(stack);
        }
    }

    /** 副产物槽：<b>只出不进</b>（玩家可以拿走，谁也放不进去）。 */
    public static final class OutputSlot extends SlotItemHandler {
        public OutputSlot(final IItemHandler handler, final int index, final int x, final int y) {
            super(handler, index, x, y);
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            return false;
        }
    }

    /**
     * 客户端 20 格库存镜像：<b>与服务端同判据</b>的 {@code isItemValid} / {@code getSlotLimit}。
     *
     * <p><b>为什么不能直接用裸 {@link ItemStackHandler}</b>：它默认所有槽都 {@code isItemValid = true}，
     * 于是客户端会「预测」把物品放进副产物槽 / 放进不可降解的输入槽，服务端拒绝后物品闪回原位 ——
     * 玩家看到的就是「点一下抖一下」。堆叠升级后的单格上限同理，必须读数据槽
     * （{@link #DATA_STACK_UPGRADES}）而不是恒 64。</p>
     */
    private static final class ClientInventoryMirror extends ItemStackHandler {
        private final ContainerData data;

        ClientInventoryMirror(final ContainerData data) {
            super(DecompositionGeneratorSpec.INVENTORY_SIZE);
            this.data = data;
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return DecompositionGeneratorSpec.isInputSlot(slot)
                && DecompositionTable.isDecomposable(stack);
        }

        @Override
        public int getSlotLimit(final int slot) {
            if (DecompositionGeneratorSpec.isInputSlot(slot)) {
                return DecompositionGeneratorSpec.inputSlotLimit(data.get(DATA_STACK_UPGRADES));
            }
            return DecompositionGeneratorSpec.OUTPUT_LIMIT;
        }
    }
}
