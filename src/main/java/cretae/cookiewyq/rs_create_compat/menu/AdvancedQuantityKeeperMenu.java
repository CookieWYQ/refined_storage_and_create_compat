package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.KeeperSlotConfig;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 高级资源定量保持器菜单：4 个 ghost 配置槽（每行一个）+ 6 插件槽 + 玩家背包。
 * <p>槽位坐标按 UI 规格：精灵坐标 + 1 = Menu 坐标。</p>
 */
public class AdvancedQuantityKeeperMenu extends AbstractContainerMenu
    implements cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder {
    /** 4 个 ghost 标记槽对应的行号（= Menu 槽位 id 0..3）。 */
    public static final int MARKER_SLOTS = AdvancedQuantityKeeperBlockEntity.SLOT_COUNT;
    /** 插件槽起始 Menu 槽位 id。 */
    public static final int UPGRADE_START = MARKER_SLOTS;
    /** 容器（标记 + 插件）槽位总数。 */
    public static final int CONTAINER_SLOTS = MARKER_SLOTS + 6;

    /** 每行配置的精灵坐标（Menu = 精灵 + 1）。 */
    private static final int[] ROW_Y = {22, 46, 70, 94};

    private final AdvancedQuantityKeeperBlockEntity keeper;
    private final ContainerData data;
    @Nullable
    private net.minecraft.server.level.ServerPlayer serverPlayer;

    public AdvancedQuantityKeeperMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public AdvancedQuantityKeeperMenu(final int id,
                                      final Inventory inventory,
                                      @Nullable final AdvancedQuantityKeeperBlockEntity keeper) {
        super(RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_MENU.get(), id);
        this.keeper = keeper;
        this.data = keeper != null
            ? keeper.getContainerData()
            : new SimpleContainerData(AdvancedQuantityKeeperBlockEntity.DATA_COUNT);
        addDataSlots(data);

        final Container container = keeper != null
            ? keeper.getInventory()
            : new net.minecraft.world.SimpleContainer(CONTAINER_SLOTS);

        // 4 个 ghost 标记槽：精灵 (8, y+2) → Menu (9, y+3)
        for (int i = 0; i < MARKER_SLOTS; i++) {
            addSlot(new MarkerSlot(container, i, 9, ROW_Y[i] + 3));
        }
        // 插件槽 6 格竖排：精灵 (187, 6+i*18) → Menu (188, 7+i*18)
        // <b>计数起点 = MARKER_SLOTS</b>：同一个容器前 4 格是 ghost 标记槽，绝不能把「标记槽里的
        // 物品」当成「已插入的升级」—— 否则标记过一个堆叠升级就会让本机永远只能插 5 个（真 bug 根因）。
        for (int i = 0; i < 6; i++) {
            addSlot(UpgradeSlot.forContainer(container, MARKER_SLOTS + i, 188, 7 + i * 18, MARKER_SLOTS));
        }
        // 玩家主物品栏（3 行 9 列）：精灵 (8,132) → Menu (9,133)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 133 + row * 18));
            }
        }
        // 快捷栏：精灵 (8,189) → Menu (9,190)
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 190));
        }
    }

    public static AdvancedQuantityKeeperMenu create(final int id,
                                                    final Inventory inventory,
                                                    final AdvancedQuantityKeeperBlockEntity keeper) {
        return new AdvancedQuantityKeeperMenu(id, inventory, keeper);
    }

    /** 该 Menu 槽位是否为第 {@code row} 行的 ghost 标记槽。 */
    public boolean isMarkerRow(final Slot slot, final int row) {
        return row >= 0 && row < MARKER_SLOTS && getSlot(row) == slot;
    }

    /** 从 Menu 槽位对象反查行号（非标记槽返回 -1）。 */
    public int markerRowOf(final Slot slot) {
        for (int i = 0; i < MARKER_SLOTS; i++) {
            if (getSlot(i) == slot) {
                return i;
            }
        }
        return -1;
    }

    // ==================== ContainerData 读取 ====================

    public int getTarget(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_TARGET);
    }

    /** 标记形态：0 物品 / 1 流体 / 2 气体（空槽按 0 处理）。 */
    public int getMarkerForm(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_FORM);
    }

    public boolean isAutoCraft(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_AUTOCRAFT) == 1;
    }

    public boolean isBlocked(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_BLOCKED) == 1;
    }

    /** 该行是否开启「过量销毁」（每槽独立开关）。 */
    public boolean isDestroyOverflow(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_DESTROY_OVERFLOW) == 1;
    }

    public boolean hasMarker(final int row) {
        return data.get(row * AdvancedQuantityKeeperBlockEntity.DATA_PER_SLOT
            + AdvancedQuantityKeeperBlockEntity.DATA_SLOT_HAS_MARKER) == 1;
    }

    public int getSpeedUpgradeCount() {
        return data.get(AdvancedQuantityKeeperBlockEntity.DATA_SPEED_UPGRADES);
    }

    public boolean hasAutocraftingUpgrade() {
        return data.get(AdvancedQuantityKeeperBlockEntity.DATA_HAS_AUTOCRAFT_UPGRADE) == 1;
    }

    // ==================== 服务端权威入口 ====================

    /** 服务端设置某行目标数量绝对值（客户端输入框发送）。 */
    public void setTargetValue(final int row, final int value) {
        if (keeper != null && !keeper.getLevel().isClientSide()) {
            keeper.setTarget(row, value);
        }
    }

    /** 服务端切换某行自动合成开关（无升级时忽略）。 */
    public void setAutoCraft(final int row, final boolean enabled) {
        if (keeper != null && !keeper.getLevel().isClientSide()) {
            keeper.setAutoCraft(row, enabled);
            syncConfigs();
        }
    }

    /** 服务端切换某行「过量销毁」开关（每槽独立，与全局自动合成升级无关）。 */
    public void setDestroyOverflow(final int row, final boolean enabled) {
        if (keeper != null && !keeper.getLevel().isClientSide()) {
            keeper.setDestroyOverflow(row, enabled);
        }
    }

    /** 从 JEI 拖入设置某行标记（服务端）：数量固定 1；空栈则清除该行标记。 */
    public void setMarkerFromJei(final int row, final ItemStack stack) {
        if (keeper == null || keeper.getLevel().isClientSide()) {
            return;
        }
        keeper.setItemMarker(row, stack);
        syncConfigs();
    }

    /** 服务端权威入口：直接写入某行流体/气体标记；{@code id == null} 或 {@code form <= 0} 表示清除。 */
    public void applyFluidMarker(final int row, final int form,
                                 @Nullable final net.minecraft.resources.ResourceLocation id,
                                 @Nullable final net.minecraft.nbt.CompoundTag nbt) {
        if (keeper == null || keeper.getLevel().isClientSide()) {
            return;
        }
        if (id == null || form <= 0) {
            keeper.clearSlot(row);
        } else {
            keeper.setFluidMarker(row, form, id, nbt);
        }
        syncConfigs();
    }

    /** 把当前 4 槽配置快照推送给客户端（ContainerData 传不了资源 id/NBT）。 */
    public void syncConfigs() {
        if (keeper == null || serverPlayer == null) {
            return;
        }
        final List<KeeperSlotConfig> configs = new ArrayList<>(MARKER_SLOTS);
        for (int i = 0; i < MARKER_SLOTS; i++) {
            configs.add(keeper.getSlotConfig(i));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
            new cretae.cookiewyq.rs_create_compat.network.SyncAdvKeeperConfigPacket(containerId, configs));
    }

    /** 首次配置同步是否已发送。 */
    private boolean configsSynced;

    /**
     * 菜单打开后补发一次 4 槽配置快照。
     * <p><b>为什么不用 {@code setSynchronizer}</b>：synchronizer 不是 ServerPlayer，
     * {@code instanceof ServerPlayer} 恒为 false，同步不会发生。这里在首次 {@code broadcastChanges}
     * 时按「containerMenu == this」反查玩家再发送。</p>
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (configsSynced || keeper == null || keeper.getLevel() == null
            || keeper.getLevel().isClientSide()) {
            return;
        }
        final net.minecraft.server.level.ServerPlayer player = findPlayer();
        if (player != null) {
            configsSynced = true;
            this.serverPlayer = player;
            syncConfigs();
        }
    }

    /** 按「containerMenu == this」反查当前打开本菜单的服务端玩家（无则 null）。 */
    private net.minecraft.server.level.ServerPlayer findPlayer() {
        if (keeper == null || keeper.getLevel() == null) {
            return null;
        }
        for (final Player p : keeper.getLevel().players()) {
            if (p.containerMenu == this
                && p instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                return serverPlayer;
            }
        }
        return null;
    }

    // ==================== 交互 ====================

    /** ghost 标记槽点击：手持物品 → 复制为标记（数量 1，不消耗手持）；空手持 → 清除该行标记。 */
    @Override
    public void clicked(final int slotId, final int button, final net.minecraft.world.inventory.ClickType clickType,
                        final Player player) {
        if (slotId >= 0 && slotId < MARKER_SLOTS
            && (clickType == net.minecraft.world.inventory.ClickType.PICKUP
            || clickType == net.minecraft.world.inventory.ClickType.QUICK_MOVE)) {
            final Slot markerSlot = slots.get(slotId);
            final ItemStack carried = getCarried();
            if (keeper != null && keeper.getLevel() != null && !keeper.getLevel().isClientSide()) {
                keeper.setItemMarker(slotId, carried);
                syncConfigs();
            } else if (carried.isEmpty()) {
                markerSlot.set(ItemStack.EMPTY); // 空手持点击：清除标记
            } else {
                markerSlot.set(carried.copyWithCount(1)); // 复制标记，不消耗手持物品
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (id == cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode.BUTTON_ID) {
            // 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）
            if (keeper != null && !keeper.getLevel().isClientSide()) {
                keeper.setRedstoneMode(keeper.getRedstoneMode().toggle());
            }
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public com.refinedmods.refinedstorage.common.support.RedstoneMode rscc$getRedstoneMode() {
        return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings.getRedstoneMode(
            data.get(AdvancedQuantityKeeperBlockEntity.DATA_REDSTONE_MODE));
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return stack;
        }
        final ItemStack stackInSlot = slot.getItem();
        stack = stackInSlot.copy();
        if (index < CONTAINER_SLOTS) {
            // 标记 / 插件槽 → 玩家背包
            if (!moveItemStackTo(stackInSlot, CONTAINER_SLOTS, CONTAINER_SLOTS + 36, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 插件槽：只交给 vanilla 的 moveItemStackTo（<b>不自己写任何「整叠搬运」代码</b>）。
            // <b>为什么撤销上一轮的 distributeUpgrades</b>：用户明确不要「自动均匀分配」，而且任何
            // 「先清空/改写原槽再写入目标槽」的自研搬运都可能引入中间态丢物。vanilla 的搬运是
            // Slot#setByPlayer 的 1:1 转移（split 走同一份物品栈），且插件槽每格上限 1、非空槽被
            // mayPlace 拒收，因此这里既不会丢物、也不会把「已经一格多个」的状态拆分或搬走。
            // 非升级物品 → 把第一个空标记行设为该物品的 ghost 标记（既有行为不变）。
            final boolean moved = moveItemStackTo(stackInSlot, UPGRADE_START, CONTAINER_SLOTS, false);
            if (!moved) {
                if (isUpgradeItem(stackInSlot)) {
                    // 一个都放不进（槽位全满 / 该种已达上限）：源物品完整留在原处，绝不弹出
                    return ItemStack.EMPTY;
                }
                final int emptyRow = firstEmptyMarkerRow();
                if (emptyRow < 0) {
                    return ItemStack.EMPTY;
                }
                if (keeper != null && keeper.getLevel() != null && !keeper.getLevel().isClientSide()) {
                    keeper.setItemMarker(emptyRow, stackInSlot);
                    syncConfigs();
                } else {
                    slots.get(emptyRow).set(stackInSlot.copyWithCount(1));
                    slots.get(emptyRow).setChanged();
                }
                // ghost 标记不消耗源物品；返回 EMPTY 终止 vanilla QUICK_MOVE 的 while 循环
                // （见 AbstractContainerMenu#doClick：返回非空且与源槽同物品时会反复调用本方法，
                //  导致一次 Shift+点击把所有空标记槽都写成同一个物品、甚至覆盖流体标记）。
                return ItemStack.EMPTY;
            }
        }
        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return stack;
    }

    /**
     * 第一个<b>真正空</b>的标记行（既无物品标记也无流体 / 气体标记）。
     *
     * <p><b>为什么不能只看 {@code slots.get(i).getItem().isEmpty()}</b>：流体 / 气体标记
     * 不存进 inventory（存于 {@code fluidMarkerIds[]}），所以流体槽的 inventory 物品也是 EMPTY。
     * 若只判空 inventory，会把「已标岩浆的槽」误判为空槽，进而用物品标记覆盖掉流体标记。
     * 这里改为读同步过来的 {@code DATA_SLOT_HAS_MARKER}（物品 / 流体 / 气体标记统一为 1），
     * 客户端 / 服务端口径一致。</p>
     */
    private int firstEmptyMarkerRow() {
        for (int i = 0; i < MARKER_SLOTS; i++) {
            if (!hasMarker(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 是否为可放入插件槽的 RS 升级物品（速度 / 堆叠 / 自动合成）。 */
    private static boolean isUpgradeItem(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return false;
        }
        return switch (id.getPath()) {
            case "speed_upgrade", "stack_upgrade", "autocrafting_upgrade" -> true;
            default -> false;
        };
    }

    @Override
    public boolean stillValid(final Player player) {
        return keeper != null
            && keeper.getLevel().getBlockEntity(keeper.getBlockPos()) == keeper
            && player.distanceToSqr(keeper.getBlockPos().getX() + 0.5,
            keeper.getBlockPos().getY() + 0.5,
            keeper.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
