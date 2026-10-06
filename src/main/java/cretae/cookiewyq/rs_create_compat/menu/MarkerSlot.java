package cretae.cookiewyq.rs_create_compat.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 标记槽（定量保持器）：仿 RS 样板终端的样板格子。
 * <ul>
 *     <li>只能通过"手持物品点击"设置标记（由菜单 doClick 处理，复制不消耗手持）；</li>
 *     <li>禁止拿起（mayPickup=false），标记不会被误取走；</li>
 *     <li>禁止常规放入（mayPlace=false），shift 快捷放入不会触发（标记应通过点击设置）。</li>
 * </ul>
 */
public class MarkerSlot extends Slot {
    public MarkerSlot(final Container container, final int index, final int x, final int y) {
        super(container, index, x, y);
    }

    @Override
    public void set(final ItemStack stack) {
        if (!stack.isEmpty()) {
            stack.setCount(1); // 标记仅关心物品类型，数量固定 1
        }
        super.set(stack);
    }

    @Override
    public boolean mayPlace(final ItemStack stack) {
        return false; // 禁止常规放入（快速转移）
    }

    @Override
    public boolean mayPickup(final Player player) {
        return false; // 禁止拿起标记（清除通过"空手持点击"）
    }
}
