package cretae.cookiewyq.rs_create_compat.menu;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.List;

/**
 * 升级槽：
 * <ol>
 *     <li>仅接受 RS 原版升级物品（refinedstorage:*_upgrade）。</li>
 *     <li>与精致存储原版一致：每个槽仅接受 1 个同类升级。</li>
 *     <li>空槽位时由 Screen 层绘制悬浮提示（"空升级槽位" + 可放入的升级种类）。</li>
 * </ol>
 */
public class UpgradeSlot extends SlotItemHandler {
    public UpgradeSlot(final IItemHandler itemHandler, final int index, final int xPosition, final int yPosition) {
        super(itemHandler, index, xPosition, yPosition);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getMaxStackSize(final ItemStack stack) {
        return 1;
    }

    @Override
    public boolean mayPlace(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        return "refinedstorage".equals(id.getNamespace()) && id.getPath().endsWith("_upgrade");
    }

    /** 同类升级在整个升级容器中仅允许出现一次。 */
    public boolean mayPlaceByPlayer(final ItemStack stack) {
        if (!mayPlace(stack)) {
            return false;
        }
        final IItemHandler handler = getItemHandler();
        for (int i = 0; i < handler.getSlots(); i++) {
            if (i == getSlotIndex()) {
                continue;
            }
            final ItemStack inSlot = handler.getStackInSlot(i);
            if (!inSlot.isEmpty() && ItemStack.isSameItem(inSlot, stack)) {
                return false;
            }
        }
        return true;
    }

    /** 基于 Container（SimpleContainer 等）的升级槽。定量保持器使用原生 Container 存储升级。 */
    public static Slot forContainer(final Container container, final int index,
                                     final int xPosition, final int yPosition) {
        return new Slot(container, index, xPosition, yPosition) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public int getMaxStackSize(final ItemStack stack) {
                return 1;
            }

            @Override
            public boolean mayPlace(final ItemStack stack) {
                if (stack.isEmpty()) return false;
                final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                return id != null
                    && "refinedstorage".equals(id.getNamespace())
                    && id.getPath().endsWith("_upgrade");
            }
        };
    }

    /** 空升级槽的 tooltip 行（与精致存储原版风格相近：第一行紫色"空升级槽位"，第二行可放入的升级）。 */
    public static List<Component> getEmptyTooltip() {
        return List.of(
            Component.literal("§5§n空升级槽位§r"),
            Component.literal("§7可放入:§f 速度升级 §8(×4)§r, §f堆叠升级 §8(×1)§r,"),
            Component.literal("§7       §f范围升级 §8(×4)§r, §f自动合成升级 §8(×1)§r,"),
            Component.literal("§7       §f时运升级 §8(×1)§r, §f精准采集升级 §8(×1)§r")
        );
    }
}
