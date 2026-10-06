package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 定量保持器菜单：标记槽 + 6 插件槽 + 玩家背包。
 */
public class QuantityKeeperMenu extends AbstractContainerMenu
    implements cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder {
    /** 按钮 id：0/1 = 目标数量 -/+，2 = 销毁开关切换，3 = 自动合成开关切换，
     *  {@link cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode#BUTTON_ID} = 红石模式循环切换。 */
    private final QuantityKeeperBlockEntity keeper;
    private final ContainerData data;
    @Nullable
    private net.minecraft.server.level.ServerPlayer serverPlayer;

    public QuantityKeeperMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public QuantityKeeperMenu(final int id,
                              final Inventory inventory,
                              @Nullable final QuantityKeeperBlockEntity keeper) {
        super(RS_Create_Compat.QUANTITY_KEEPER_MENU.get(), id);
        this.keeper = keeper;
        this.data = keeper != null ? keeper.getContainerData() : new SimpleContainerData(9);
        addDataSlots(data);

        if (keeper != null) {
            // 标记槽（背景精灵 (8,29) → Menu (9,30)，仿样板终端：点击复制标记、不消耗）
            addSlot(new MarkerSlot(keeper.getInventory(), 0, 9, 30));
            // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 (187,6+i*18) → Menu +1）
            // 计数起点 = 1：容器第 0 格是 ghost 标记槽，不能计入「已插入的升级数」（与高级版同一根因）
            for (int i = 0; i < 6; i++) {
                addSlot(UpgradeSlot.forContainer(keeper.getInventory(), 1 + i, 188, 7 + i * 18, 1));
            }
        } else {
            // 客户端重建：槽位数必须与服务端一致（内容由数据包同步）
            final net.minecraft.world.SimpleContainer empty = new net.minecraft.world.SimpleContainer(7);
            addSlot(new MarkerSlot(empty, 0, 9, 30));
            for (int i = 0; i < 6; i++) {
                addSlot(UpgradeSlot.forContainer(empty, 1 + i, 188, 7 + i * 18, 1));
            }
        }
        // 玩家主物品栏（3 行 9 列，背景精灵 (8,84) → Menu (9,85)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 85 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,142) → Menu (9,143)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 143));
        }
    }

    public static QuantityKeeperMenu create(final int id,
                                            final Inventory inventory,
                                            final QuantityKeeperBlockEntity keeper) {
        return new QuantityKeeperMenu(id, inventory, keeper);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (keeper != null && !keeper.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> keeper.setTargetAmount(keeper.getTargetAmount() - 1);
                case 1 -> keeper.setTargetAmount(keeper.getTargetAmount() + 1);
                case 2 -> keeper.setDestroyOverflow(!keeper.isDestroyOverflow());
                case 3 -> {
                    // 无自动合成升级时服务端忽略切换（客户端已禁用开关）
                    if (keeper.hasAutocraftingUpgrade()) {
                        keeper.setAutoCraftEnabled(!keeper.isAutoCraftEnabled());
                    }
                }
                // 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）
                case cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode.BUTTON_ID ->
                    keeper.setRedstoneMode(keeper.getRedstoneMode().toggle());
                default -> {
                }
            }
        }
        return true;
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public com.refinedmods.refinedstorage.common.support.RedstoneMode rscc$getRedstoneMode() {
        return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings.getRedstoneMode(data.get(8));
    }

    public int getTargetAmount() {
        return data.get(0);
    }

    /** 供客户端发送数值设置包。 */
    @Nullable
    public net.minecraft.core.BlockPos getKeeperPos() {
        return keeper != null ? keeper.getBlockPos() : null;
    }

    /**
     * 服务端设置目标数量绝对值（客户端输入框 / ± 按钮发送）。
     * <p>下限放宽到 0：0 = 「未标记」（该项不参与维持 / 合成 / 销毁 / 缺料统计）。</p>
     */
    public void setTargetValue(final int value) {
        if (keeper != null && !keeper.getLevel().isClientSide()) {
            keeper.setTargetAmount(Math.max(0, value));
        }
    }

    /** 从 JEI 拖入设置标记（服务端）：数量固定 1，清空则传入空栈；同时清除直接流体标记。 */
    public void setMarkerFromJei(final ItemStack stack) {
        if (keeper == null || keeper.getLevel().isClientSide()) {
            return;
        }
        keeper.setItemMarker(stack);
        syncFluidMarker();
    }

    /**
     * 服务端权威入口（任务 2）：直接把流体/气体标记写入方块实体，并把新标记同步回客户端。
     * <p>{@code id == null} 或 {@code form <= 0} 表示清除直接流体标记。</p>
     */
    public void applyFluidMarker(final int form, @Nullable final net.minecraft.resources.ResourceLocation id,
                                 @Nullable final net.minecraft.nbt.CompoundTag nbt) {
        if (keeper == null || keeper.getLevel().isClientSide()) {
            return;
        }
        if (id == null || form <= 0) {
            keeper.clearFluidMarker();
        } else {
            keeper.setFluidMarker(form, id, nbt);
        }
        syncFluidMarker();
    }

    /** 把方块实体当前的直接流体标记推送给客户端（ContainerData 无法承载资源 id）。 */
    public void syncFluidMarker() {
        if (keeper != null && serverPlayer != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                new cretae.cookiewyq.rs_create_compat.network.SyncQuantityFluidMarkerPacket(
                    containerId, keeper.getFluidMarkerId(), keeper.getFluidMarkerNbt()));
        }
    }

    /** 首次流体标记同步是否已发送。 */
    private boolean fluidMarkerSynced;

    /**
     * 菜单打开后补发一次流体标记快照。
     * <p>synchronizer 不是 ServerPlayer，{@code setSynchronizer} 路径不可靠；这里在首次
     * {@code broadcastChanges} 时按「containerMenu == this」反查玩家再发送。</p>
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (fluidMarkerSynced || keeper == null || keeper.getLevel() == null
            || keeper.getLevel().isClientSide()) {
            return;
        }
        for (final net.minecraft.world.entity.player.Player p : keeper.getLevel().players()) {
            if (p.containerMenu == this
                && p instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                fluidMarkerSynced = true;
                this.serverPlayer = serverPlayer;
                syncFluidMarker();
                return;
            }
        }
    }

    // 说明：ghost 标记槽现在有两条路径 ——
    //   1) 物品：手持/Shift/JEI 拖入物品（本菜单的点击与 SetQuantityMarkerPacket）；
    //   2) 流体/气体：JEI 直接拖入流体（SetQuantityFluidMarkerPacket，服务端写入方块实体）。
    // 标记容器物品（桶/化学罐）时按「物品」处理（存水桶、出水桶），不再从中反推流体；
    // 要保持流体必须显式拖入 FluidStack。

    public boolean isDestroyOverflow() {
        return data.get(1) == 1;
    }

    /** 标记形态：0 = 物品（含容器物品），1 = 流体，2 = 气体（仅显式流体/气体标记时非 0）。 */
    public int getMarkerForm() {
        return data.get(4);
    }

    /** 是否处于「堵塞」状态（内部存在与当前标记不匹配的资源，已停止输出但内容可取出）。 */
    public boolean isMarkerBlocked() {
        return data.get(7) == 1;
    }

    public int getSpeedUpgradeCount() {
        return data.get(2);
    }

    public boolean hasAutocraftingUpgrade() {
        return data.get(3) == 1;
    }

    /** 自动合成开关状态（仅在有自动合成升级时可操作）。 */
    public boolean isAutoCraftEnabled() {
        return data.get(5) == 1;
    }

    /** 标记槽点击：手持物品 → 复制为标记（数量1，不消耗手持）；空手持 → 清除标记。 */
    @Override
    public void clicked(final int slotId, final int button, final net.minecraft.world.inventory.ClickType clickType,
                        final Player player) {
        if (slotId == 0 && (clickType == net.minecraft.world.inventory.ClickType.PICKUP
            || clickType == net.minecraft.world.inventory.ClickType.QUICK_MOVE)) {
            final Slot markerSlot = slots.get(0);
            final ItemStack carried = getCarried();
            if (keeper != null && keeper.getLevel() != null && !keeper.getLevel().isClientSide()) {
                // 服务端权威：统一入口同时清除直接流体标记（与物品标记互斥）
                keeper.setItemMarker(carried);
                syncFluidMarker();
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
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < 7) {
                // 标记/插件槽 → 玩家背包
                if (!moveItemStackTo(stackInSlot, 7, 7 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 插件槽（1-6）优先；不是升级物品时，直接把标记槽（0）设为该物品的 ghost 标记
                // （等价于「把物品从容器拖入 ghost 槽」：只标记、不消耗玩家手里的物品）。
                final boolean moved = moveItemStackTo(stackInSlot, 0, 7, false);
                if (!moved) {
                    if (isUpgradeItem(stackInSlot) || slots.get(0).hasItem()) {
                        return ItemStack.EMPTY;
                    }
                    if (keeper != null && keeper.getLevel() != null && !keeper.getLevel().isClientSide()) {
                        keeper.setItemMarker(stackInSlot);
                        syncFluidMarker();
                    } else {
                        slots.get(0).set(stackInSlot.copyWithCount(1));
                        slots.get(0).setChanged();
                    }
                    return stack;
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
