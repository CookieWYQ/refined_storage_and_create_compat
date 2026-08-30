package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;

/**
 * 蓝图加农炮装填器（基础版）方块。
 */
public class SchematicLoaderBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<SchematicLoaderBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(() -> RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get());

    public SchematicLoaderBlock(final Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new SchematicLoaderBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level,
                                                                  final BlockState state,
                                                                  final BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (BlockEntityTicker<T>) TICKER;
    }

    @Override
    protected InteractionResult useWithoutItem(final BlockState state,
                                               final Level level,
                                               final BlockPos pos,
                                               final Player player,
                                               final BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            player.openMenu(state.getMenuProvider(level, pos), pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Nullable
    @Override
    protected MenuProvider getMenuProvider(final BlockState state,
                                           final Level level,
                                           final BlockPos pos) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SchematicLoaderBlockEntity loader)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu.create(
                id, inventory, loader
            ),
            Component.translatable("block.rs_create_compat.schematic_loader")
        );
    }

    /** 方块被破坏时：优先把所有物品回流到 RS 网络，剩余部分掉落世界。 */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SchematicLoaderBlockEntity loader) {
            final var network = loader.getNode().getNetworkOrNull();
            // 遍历全部容器：inventory / blueprintSlot / upgradeContainer / queue
            pushOrDrop(level, pos, network, loader.getInventory());
            pushOrDrop(level, pos, network, loader.getBlueprintSlot());
            pushOrDrop(level, pos, network, loader.getUpgradeContainer());
            pushOrDrop(level, pos, network, loader.getQueue());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void pushOrDrop(final Level level,
                                   final BlockPos pos,
                                   @Nullable final com.refinedmods.refinedstorage.api.network.Network network,
                                   final ItemStackHandler handler) {
        final StorageNetworkComponent storage = network != null
            ? network.getComponent(StorageNetworkComponent.class)
            : null;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i).copy();
            if (stack.isEmpty()) {
                continue;
            }
            handler.setStackInSlot(i, ItemStack.EMPTY);
            if (storage != null) {
                final ItemResource key = new ItemResource(stack.getItem(), DataComponentPatch.EMPTY);
                final long inserted = storage.insert(
                    key, stack.getCount(), Action.EXECUTE, Actor.EMPTY);
                if (inserted >= stack.getCount()) {
                    continue;
                }
                stack = new ItemStack(stack.getItem(), (int) (stack.getCount() - inserted));
            }
            Block.popResource(level, pos, stack);
        }
    }
}

