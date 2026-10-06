package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccSharedCache;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 中间产物缓存仓菜单：{@value #DISK_COUNT} 格磁盘槽（3 行 × 9 列 = 一个小箱子的槽数）+ 玩家背包。
 * <ul>
 *     <li>盘位<b>只允许</b> RS 的存储磁盘类物品（{@code StorageContainerItem}），判定与服务端<b>同一句</b>
 *     {@link RsccSharedCache#isStorageDisk(ItemStack)}；</li>
 *     <li>本仓不存别的物品：池子是「网络内所有执行舱共享的中间产物临时储存点」，
 *     池子内容全在盘里（见 {@code IntermediateCacheBlockEntity}）。</li>
 * </ul>
 *
 * <p>坐标口径（工程硬规则）：下面所有几何常量都是 <b>Menu 坐标</b>；绘制时按「背景精灵坐标 =
 * Menu 坐标 − 1」换算（见界面类的槽位框绘制）。</p>
 */
public class IntermediateCacheMenu extends AbstractContainerMenu {
    /** 盘位数量（= 方块实体的盘位数 = 27 = 一个小箱子的槽数）。 */
    public static final int DISK_COUNT = IntermediateCacheBlockEntity.DISK_SLOTS;
    public static final int DISK_START = 0;
    /** 盘位每行格数（3 行 × 9 列 = 一个小箱子的 27 格）。 */
    public static final int DISK_COLS = 9;
    /** 玩家背包 / 快捷栏起始下标。 */
    public static final int PLAYER_START = DISK_START + DISK_COUNT;

    /**
     * 面板尺寸（与界面类同名常量同源）。
     * <p>高度 198 = 盘位 3 行（36..88）+ 用量行（93）+ 物品栏标签（104）+ 背包 3 行（116..168）
     * + 快捷栏（174..190）+ 8px 下边距；宽度沿用 176（原版三行式容器宽度，9 列正好排满）。</p>
     */
    public static final int PANEL_W = 176;
    public static final int PANEL_H = 198;
    /** 盘位区：Menu (8,36) 起 3 行 × 9 列（占满面板宽度，与玩家背包同一左沿）。 */
    public static final int DISK_X0 = 8;
    public static final int DISK_Y0 = 36;
    /** 玩家主背包：Menu (8,116) 起 3 行 9 列；快捷栏 y=174。 */
    private static final int INV_X0 = 8;
    private static final int INV_Y0 = 116;
    private static final int HOTBAR_Y = 174;

    @Nullable
    private final IntermediateCacheBlockEntity cache;

    public IntermediateCacheMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public IntermediateCacheMenu(final int id,
                                 final Inventory inventory,
                                 @Nullable final IntermediateCacheBlockEntity cache) {
        super(RS_Create_Compat.INTERMEDIATE_CACHE_MENU.get(), id);
        this.cache = cache;
        // 盘位（3 行 × 9 列）：只接受存储磁盘
        final Container disks = cache != null ? cache.diskSlots : new SimpleContainer(DISK_COUNT);
        for (int i = 0; i < DISK_COUNT; i++) {
            addSlot(new CacheDiskSlot(disks, i,
                DISK_X0 + (i % DISK_COLS) * 18, DISK_Y0 + (i / DISK_COLS) * 18));
        }
        // 玩家主物品栏（3 行 9 列）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INV_X0 + col * 18, INV_Y0 + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INV_X0 + col * 18, HOTBAR_Y));
        }
    }

    public static IntermediateCacheMenu create(final int id,
                                               final Inventory inventory,
                                               final IntermediateCacheBlockEntity cache) {
        return new IntermediateCacheMenu(id, inventory, cache);
    }

    /** 纯 getter：暴露本菜单持有的缓存仓方块实体（客户端重建菜单时为 null）。 */
    @Nullable
    public IntermediateCacheBlockEntity getCache() {
        return cache;
    }

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
        final ItemStack copy = stackInSlot.copy();
        if (index < PLAYER_START) {
            // 盘位 → 玩家背包（任何东西都能拿出来）
            if (!moveItemStackTo(stackInSlot, PLAYER_START, PLAYER_START + 36, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 盘位（只搬存储磁盘，其余留原处）
            if (!moveItemStackTo(stackInSlot, DISK_START, PLAYER_START, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(final Player player) {
        if (cache == null) {
            return true; // 客户端重建菜单：保持打开（有效性由服务端校验）
        }
        if (cache.getLevel() == null) {
            return true;
        }
        return cache.getLevel().getBlockEntity(cache.getBlockPos()) == cache
            && player.distanceToSqr(cache.getBlockPos().getX() + 0.5,
            cache.getBlockPos().getY() + 0.5,
            cache.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /**
     * 盘位槽：<b>只允许放入 RS 的存储磁盘类物品</b>（{@code StorageContainerItem}）。
     * <ul>
     *     <li>其它物品（方块、工具、单元样板……）一律放不进 —— 客户端与服务端用的是同一句静态判定
     *     {@link RsccSharedCache#isStorageDisk(ItemStack)}，因此不存在「客户端看起来能放、服务端拒绝」
     *     的不一致；</li>
     *     <li>槽内容变化一律经 {@code Container#setChanged} 通知缓存仓（见
     *     {@code IntermediateCacheBlockEntity} 构造里注册的监听器），从而作废共享池缓存并让磁盘落盘。</li>
     * </ul>
     */
    private static final class CacheDiskSlot extends Slot {
        private CacheDiskSlot(final Container container, final int index, final int x, final int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(final ItemStack stack) {
            // 只按「是不是存储磁盘」判定：客户端与服务端必须逐字一致（否则会出现预测 / 权威不一致的闪回）
            return RsccSharedCache.isStorageDisk(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1; // 一格一块盘
        }
    }
}
