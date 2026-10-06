package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 序列执行仓菜单：54 格单元样板槽（6×9 大型双箱布局）+ 玩家背包。
 * <ul>
 *     <li>单元样板槽只允许放入单元样板；</li>
 *     <li><b>本仓没有磁盘槽</b>（用户决定）：中间产物的磁盘改插在「中间产物缓存仓」
 *     （{@code intermediate_cache}）里，网络内所有执行舱共用那一份池子
 *     （见 {@code support/RsccSharedCache}）。因此面板尺寸 / 几何与本仓引入磁盘槽之前完全一致
 *     （176×222 原版双箱）。</li>
 * </ul>
 */
public class SequenceExecutionChamberMenu extends AbstractContainerMenu {
    public static final int UNIT_START = 0;
    public static final int UNIT_ROWS = 6;
    public static final int UNIT_SLOTS = SequenceExecutionChamberBlockEntity.UNIT_SLOTS;
    public static final int PLAYER_START = UNIT_SLOTS;

    private final SequenceExecutionChamberBlockEntity chamber;

    public SequenceExecutionChamberMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public SequenceExecutionChamberMenu(final int id,
                                        final Inventory inventory,
                                        @Nullable final SequenceExecutionChamberBlockEntity chamber) {
        super(RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_MENU.get(), id);
        this.chamber = chamber;

        final Container inv = chamber != null ? chamber.unitSlots : new net.minecraft.world.SimpleContainer(UNIT_SLOTS);
        // 单元样板 54 格（9 列 × 6 行，双箱布局：首行 y=18）
        for (int row = 0; row < UNIT_ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new ChamberUnitSlot(inv, row * 9 + col, 8 + col * 18, 18 + row * 18, chamber));
            }
        }
        // 玩家主物品栏（双箱：行内偏移 i = (rows-4)*18 = 36）
        final int i = (UNIT_ROWS - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                // y 用 104 / 162：generic_54.png 里玩家背包槽框的「内部」从精灵 y=140 起
                // （槽框原点 139），按硬规则 Menu = 精灵 + 1 应为 140。
                // 旧代码的 103 / 161 会让整个玩家背包与快捷栏比背景槽框高 1px（用户实测问题）。
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 104 + row * 18 + i));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 162 + i));
        }
    }

    public static SequenceExecutionChamberMenu create(final int id,
                                                      final Inventory inventory,
                                                      final SequenceExecutionChamberBlockEntity chamber) {
        return new SequenceExecutionChamberMenu(id, inventory, chamber);
    }

    /** 纯 getter：暴露本菜单持有的执行仓方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public SequenceExecutionChamberBlockEntity getChamber() {
        return chamber;
    }

    /** 首次绑定同步是否已发送（避免每 tick 重发）。 */
    private boolean bindingSynced;

    /**
     * 菜单打开后补发一次绑定快照。
     * <p><b>为什么不用 {@code setSynchronizer}</b>：synchronizer 是 {@code ServerPlayer.initMenu} 内部创建的
     * {@code ContainerSynchronizer} 匿名实现，<b>不是</b> ServerPlayer，故
     * {@code synchronizer instanceof ServerPlayer} 恒为 false —— 这正是界面一直显示「绑定同步中」的根因。
     * 这里改为在首次 {@code broadcastChanges} 时按「containerMenu == this」反查玩家再发送。</p>
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (bindingSynced || chamber == null || chamber.getLevel() == null
            || chamber.getLevel().isClientSide()) {
            return;
        }
        final Player player = findPlayer();
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            bindingSynced = true;
            // 权威快照一次性带上「配方类型 / 名字 / 面模式 / 输出模式 / 链状态」
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket.of(chamber));
        }
    }

    /** 按「containerMenu == this」反查当前打开本菜单的服务端玩家（无则 null）。 */
    @Nullable
    private Player findPlayer() {
        if (chamber == null || chamber.getLevel() == null) {
            return null;
        }
        for (final Player p : chamber.getLevel().players()) {
            if (p.containerMenu == this) {
                return p;
            }
        }
        return null;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < PLAYER_START) {
                // 仓内（单元样板）→ 玩家背包：任何东西都允许拿出来
                if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_START + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 仓内：只有单元样板进样板槽，其余留原处
                // （本仓已无磁盘槽：磁盘改插在「中间产物缓存仓」，见类注释）
                if (!stackInSlot.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())
                    || !moveItemStackTo(stackInSlot, UNIT_START, PLAYER_START, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return stack;
    }

    @Override
    public boolean stillValid(final Player player) {
        if (chamber == null) {
            return true;
        }
        if (chamber.getLevel() == null) {
            return true;
        }
        return chamber.getLevel().getBlockEntity(chamber.getBlockPos()) == chamber
            && player.distanceToSqr(chamber.getBlockPos().getX() + 0.5,
            chamber.getBlockPos().getY() + 0.5,
            chamber.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /** 单元样板槽：只允许放入单元样板。 */
    private static final class ChamberUnitSlot extends Slot {
        /** 所属执行仓（客户端菜单为 null：只做「必须是单元样板」的前置拦截，权威判定在服务端）。 */
        @Nullable
        private final SequenceExecutionChamberBlockEntity chamber;

        private ChamberUnitSlot(final Container container, final int index, final int x, final int y,
                                @Nullable final SequenceExecutionChamberBlockEntity chamber) {
            super(container, index, x, y);
            this.chamber = chamber;
        }

        /**
         * 只允许放入单元样板（用户需求 B13/B14/B15 的硬约束）：
         * <ul>
         *     <li>非单元样板（含 RS 原版样板 / 综合样板 / 任何其它物品）一律放不进去；</li>
         *     <li>本仓尚未设定「配方类型 + 名字」时，任何单元样板也放不进去；</li>
         *     <li>单元样板自带的配方类型必须与本仓绑定的配方类型完全一致
         *     （冲压仓只收冲压样板）。</li>
         * </ul>
         * 客户端（chamber == null）只做第一条，其余交给服务端槽位权威判定，避免超宽校验不一致。
         */
        @Override
        public boolean mayPlace(final ItemStack stack) {
            if (stack.isEmpty() || !stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())) {
                return false;
            }
            return chamber == null || chamber.acceptsUnit(stack);
        }
    }
}
