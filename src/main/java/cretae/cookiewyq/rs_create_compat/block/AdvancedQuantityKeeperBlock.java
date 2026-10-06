package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedQuantityKeeperBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 高级物品定量保持器方块：把 4 个定量保持器融合进一个方块（4 个独立配置槽）。
 * 破坏 / 区块重载零损耗（配置与流体存储写入掉落物 NBT，物品按实体掉落）。
 */
public class AdvancedQuantityKeeperBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<AdvancedQuantityKeeperBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public AdvancedQuantityKeeperBlock(final Properties properties) {
        super(properties);
        // 默认未接入网络：使用灰色(inactive)贴图
        registerDefaultState(defaultBlockState().setValue(ModBlockStateProperties.ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new AdvancedQuantityKeeperBlockEntity(pos, state);
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
        if (!(blockEntity instanceof AdvancedQuantityKeeperBlockEntity keeper)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu.create(
                id, inventory, keeper
            ),
            Component.translatable("block.rs_create_compat.advanced_quantity_keeper")
        );
    }

    /** 破坏时：按全局「内容物去向」策略结算标记/插件槽、4 份物品同类存储与 4 份流体存储。 */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof AdvancedQuantityKeeperBlockEntity keeper) {
            BlockContentReleaser.release(level, pos, keeper,
                new ItemStack(RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // 0..SLOT_COUNT-1 是 <b>ghost 标记槽</b>（点击/拖入只复制标记、不消耗），内容不是玩家的
                        // 真实资源，绝不能当掉落物结算（否则等于凭空复制）；只结算其后的插件槽与真实的同类存储。
                        BlockContentReleaser.collectContainerRange(keeper.getInventory(),
                            AdvancedQuantityKeeperBlockEntity.SLOT_COUNT,
                            keeper.getInventory().getContainerSize(), out);
                        for (int slot = 0; slot < AdvancedQuantityKeeperBlockEntity.SLOT_COUNT; slot++) {
                            BlockContentReleaser.collectHandler(keeper.getItemStorage(slot), out);
                        }
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        for (int slot = 0; slot < AdvancedQuantityKeeperBlockEntity.SLOT_COUNT; slot++) {
                            BlockContentReleaser.collectFluidCache(
                                keeper.getFluidStorage(slot), level.registryAccess(), out);
                        }
                    }

                    @Override
                    public List<FluidStack> receiveFluids(final List<FluidStack> stacks) {
                        List<FluidStack> remaining = stacks;
                        for (int slot = 0; slot < AdvancedQuantityKeeperBlockEntity.SLOT_COUNT
                            && !remaining.isEmpty(); slot++) {
                            remaining = BlockContentReleaser.receiveFluidCache(
                                keeper.getFluidStorage(slot), level.registryAccess(), remaining);
                        }
                        return remaining;
                    }
                });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 需要「存方块」时抑制原版掉落，改由 {@link BlockContentReleaser#release} 补掉带 NBT 的方块物品。 */
    @Override
    public List<ItemStack> getDrops(final BlockState state,
                                    final net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        final BlockEntity blockEntity = params.getOptionalParameter(
            net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        return BlockContentReleaser.filterLoot(params.getLevel(), blockEntity,
            new ArrayList<>(super.getDrops(state, params)));
    }

    /** 放置时把掉落物 NBT 里的配置与流体同类存储回填到新方块实体（零损耗）。 */
    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state,
                            final net.minecraft.world.entity.LivingEntity placer, final ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
            && level.getBlockEntity(pos) instanceof AdvancedQuantityKeeperBlockEntity keeper) {
            final net.minecraft.world.item.component.CustomData custom =
                stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            final CompoundTag data = custom == null ? null : custom.copyTag();
            if (data != null && !data.isEmpty()) {
                keeper.loadPlacedState(data, level.registryAccess());
            }
        }
    }
}
