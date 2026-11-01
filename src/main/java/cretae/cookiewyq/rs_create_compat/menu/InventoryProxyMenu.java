package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.ProxyFaceRole;
import net.minecraft.core.Direction;
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

/**
 * 容器代理器界面：代理对象槽 + 六面角色按钮 + 玩家背包 / 快捷栏。
 *
 * <p>第 97 轮起界面不再有常驻说明行：面角色的解释由 {@code InventoryProxyScreen} 在<b>悬停六面按钮</b>
 * 时以 tooltip 画出（{@code face_tip.*}）。因此本菜单只负责槽位几何 —— 玩家背包行首 y=136
 * （行距 18）、快捷栏 y=196，与 {@code InventoryProxyScreen} 的 {@code imageHeight = 222} 和
 * {@code inventory_proxy.png} 的 224×222 是同一套口径。</p>
 *
 * <h2>为什么用原版 {@code clickMenuButton} 而不是自定义网络包</h2>
 * <p>六个按钮都是「把某一面循环到下一档」这一个幂等动作，服务端在
 * {@link #clickMenuButton} 里按容器 id 校验后直接改 BE 即可；客户端用
 * {@code Minecraft#gameMode#handleInventoryButtonClick} 发原版按钮包。少一个 C2S 包、
 * 少一处注册，也就少一个「包没注册 / 端不一致」的故障面。</p>
 *
 * <h2>客户端状态从哪来</h2>
 * <p>服务端 {@link InventoryProxyBlockEntity#getContainerData()} 的数据槽每 tick 由
 * {@code AbstractContainerMenu#broadcastChanges} 同步（0..5 = 六面角色序号，6 = 是否有代理对象，
 * 7 = 内层库存是否就绪）。客户端菜单不持方块实体，所有显示都读数据槽 —— 不在客户端查询能力
 * （设计文档 §3.7.4：客户端拿不到磁盘内容，也不该查）。</p>
 * <p><b>序号只在同版本内自洽</b>：角色枚举第 96 轮多了一档（{@code contents_input}），旧版本的
 * 序号会整体错位 —— 但数据槽只是「服务端写、同版本客户端读」的在线同步，从不落盘；
 * 存档里的面配置一律走 {@link ProxyFaceRole#id()} 字符串，所以加档不破坏旧存档。</p>
 */
public class InventoryProxyMenu extends AbstractContainerMenu {
    /** 按钮 id 0..5 = {@link Direction#values()} 的下标（DOWN/UP/NORTH/SOUTH/WEST/EAST）。 */
    public static final int FACE_BUTTON_BASE = 0;
    /** 代理对象槽在容器里的下标。 */
    public static final int PROXY_SLOT_INDEX = 0;
    private static final int PLAYER_SLOTS = 36;

    @Nullable
    private final InventoryProxyBlockEntity proxy;
    private final ContainerData data;

    /** 客户端重建菜单用（无方块实体；状态由数据槽同步）。 */
    public InventoryProxyMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public InventoryProxyMenu(final int id,
                              final Inventory inventory,
                              @Nullable final InventoryProxyBlockEntity proxy) {
        super(RS_Create_Compat.INVENTORY_PROXY_MENU.get(), id);
        this.proxy = proxy;
        this.data = proxy != null ? proxy.getContainerData() : new SimpleContainerData(InventoryProxyBlockEntity.DATA_SLOTS);
        addDataSlots(data);

        // 代理对象槽：与服务端对象面共用同一个 IItemHandler（客户端用占位 handler，
        // 槽内容仍由原版 ContainerSetSlot 同步；写入只会在服务端真正发生）。
        final IItemHandler objectView = proxy != null ? proxy.getObjectView() : new ItemStackHandler(1);
        addSlot(new ProxySlot(objectView, 0, 10, 22));

        // 玩家主物品栏（3 行 9 列）：第 97 轮整体上移 36px（原 172/190/208），消掉六面按钮与背包之间
        // 那条 46px 空带 —— 用户原话「上面这么大空位，所以说你往上移一点」。
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 26 + col * 18, 136 + row * 18));
            }
        }
        // 快捷栏：y=196（格子底 214），面板底 222 ⇒ 底部留白 8px（原 230 直接压在最底边框上，
        // 看起来像被切掉了一截）。三处坐标（本菜单 / InventoryProxyScreen / 贴图）必须逐字一致。
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 26 + col * 18, 196));
        }
    }

    public static InventoryProxyMenu create(final int id,
                                            final Inventory inventory,
                                            final InventoryProxyBlockEntity proxy) {
        return new InventoryProxyMenu(id, inventory, proxy);
    }

    /**
     * 代理对象槽。
     *
     * <h2>为什么只需要覆写 {@code setChanged}</h2>
     * <p>读写已经由 {@link SlotItemHandler} 转发给 {@code InventoryProxyHandlers.ObjectView}
     * （它实现了 {@code IItemHandlerModifiable}，所以原版的 {@code Slot#set} 不再抛
     * {@code ClassCastException} —— 那正是「背包放进去就没了」的根因）。</p>
     * <p>但原版 {@code Slot#setChanged} 打到的是 {@code SlotItemHandler} 内部那个<b>共享的空容器</b>
     * （{@code static final SimpleContainer emptyInventory}），等于什么都没做。而原版
     * {@code moveItemStackTo} / 快速移动会<b>原地</b>改 {@code getStackInSlot} 返回的 stack
     * （{@code shrink} / {@code grow}），那条路径不经过 {@code setStackInSlot} ⇒ 不打到方块实体上
     * 就不会标脏，这次改动可能不落盘。这里把 {@code setChanged} 直接接到 BE 上。</p>
     */
    private final class ProxySlot extends SlotItemHandler {
        ProxySlot(final IItemHandler handler, final int index, final int x, final int y) {
            super(handler, index, x, y);
        }

        @Override
        public void setChanged() {
            if (proxy != null) {
                proxy.setChanged();
            }
        }
    }

    /** 第 {@code faceIndex} 面（0..5，{@link Direction#values()} 下标）的当前角色（客户端读数据槽）。 */
    public ProxyFaceRole getFace(final int faceIndex) {
        return ProxyFaceRole.byOrdinal(data.get(faceIndex));
    }

    public boolean hasProxyObject() {
        return data.get(6) == 1;
    }

    public boolean hasResolvedContents() {
        return data.get(7) == 1;
    }

    /**
     * 内容物面的四态（第 108 轮：用户第 108 条「只显示流体、物品不显示」）。
     *
     * <p><b>为什么客户端不能自己判</b>：这个状态只能由服务端算 —— 客户端菜单重建后<b>不持有</b>方块实体
     * （设计文档 §3.7.4），也拿不到代理对象的内层 handler。数据槽是唯一入口。</p>
     */
    public InventoryProxyBlockEntity.InnerState innerState() {
        return InventoryProxyBlockEntity.InnerState.byId(data.get(InventoryProxyBlockEntity.DATA_INNER_STATE));
    }

    /** 物品面格数（服务端报的 0..255；用来把「物品 0 格」如实画在界面上）。 */
    public int innerItemSlots() {
        return data.get(InventoryProxyBlockEntity.DATA_ITEM_SLOTS);
    }

    /** 流体面罐数（同上）。 */
    public int innerFluidTanks() {
        return data.get(InventoryProxyBlockEntity.DATA_FLUID_TANKS);
    }

    public static Direction directionOf(final int faceIndex) {
        final Direction[] values = Direction.values();
        return values[Math.max(0, Math.min(faceIndex, values.length - 1))];
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (id >= FACE_BUTTON_BASE && id < FACE_BUTTON_BASE + 6 && proxy != null
            && proxy.getLevel() != null && !proxy.getLevel().isClientSide()) {
            proxy.cycleFace(directionOf(id));
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stackInSlot = slot.getItem();
        final ItemStack result = stackInSlot.copy();
        if (index == PROXY_SLOT_INDEX) {
            if (!moveItemStackTo(stackInSlot, PROXY_SLOT_INDEX + 1, PROXY_SLOT_INDEX + 1 + PLAYER_SLOTS, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stackInSlot, PROXY_SLOT_INDEX, PROXY_SLOT_INDEX + 1, false)) {
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
        // 与服务端菜单一致的校验（客户端菜单 proxy == null ⇒ false，与原版 RangeChargerMenu 同款写法）。
        return proxy != null
            && proxy.getLevel() != null
            && proxy.getLevel().getBlockEntity(proxy.getBlockPos()) == proxy
            && player.distanceToSqr(proxy.getBlockPos().getX() + 0.5,
            proxy.getBlockPos().getY() + 0.5,
            proxy.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
