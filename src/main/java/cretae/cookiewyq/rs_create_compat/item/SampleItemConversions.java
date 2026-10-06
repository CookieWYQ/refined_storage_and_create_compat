package cretae.cookiewyq.rs_create_compat.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 样板还原小工具（行为对齐 RS 原版、无提示）：
 * 手持单元样板 / 总样板时按住 Shift + 右键（空气或任意方块），把这张样板
 * “还原”成一张空白 RS 样板（refinedstorage:pattern），即去除其中写下的配方数据。
 * 仅服务端改写，客户端静默；非 Shift 保持默认行为。
 */
public final class SampleItemConversions {
    private SampleItemConversions() {
    }

    /** Item.use（右键空气）。 */
    public static InteractionResultHolder<ItemStack> use(final Level level,
                                                         final Player player,
                                                         final InteractionHand hand) {
        final ItemStack held = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(held);
        }
        if (!level.isClientSide()) {
            revert(held, player, hand);
        }
        return InteractionResultHolder.sidedSuccess(held, !level.isClientSide());
    }

    /** Item.useOn（Shift + 右键方块）。 */
    public static InteractionResult useOn(final Level level,
                                          final Player player,
                                          final InteractionHand hand) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            revert(player.getItemInHand(hand), player, hand);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    private static void revert(final ItemStack held, final Player player, final InteractionHand hand) {
        if (held.isEmpty()) {
            return;
        }
        final net.minecraft.world.item.Item pattern = BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("refinedstorage", "pattern"));
        if (pattern == null || pattern == Items.AIR) {
            return; // RS 未加载（正常不会发生）
        }
        player.setItemInHand(hand, new ItemStack(pattern, 1));
    }
}
