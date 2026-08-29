package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;

import java.util.List;

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

    @Override
    public void appendHoverText(final ItemStack stack,
                                final Item.TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        tooltip.add(Component.translatable("block.rs_create_compat.schematic_loader.tooltip.1"));
        tooltip.add(Component.translatable("block.rs_create_compat.schematic_loader.tooltip.2"));
    }
}
