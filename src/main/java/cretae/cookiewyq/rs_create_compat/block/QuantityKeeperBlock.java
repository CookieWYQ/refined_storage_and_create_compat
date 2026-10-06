package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity;
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
import org.jetbrains.annotations.Nullable;
import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;

import java.util.ArrayList;
import java.util.List;

/**
 * 定量保持器方块：接入 RS 网络，标记对象并保持其数量稳定。
 */
public class QuantityKeeperBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<QuantityKeeperBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public QuantityKeeperBlock(final Properties properties) {
        super(properties);
        // 默认未接入网络：使用灰色(inactive)贴图
        registerDefaultState(defaultBlockState().setValue(ModBlockStateProperties.ACTIVE, false));
    }

    /** 注册 active 属性，用于未接入(灰)/已接入(亮)贴图切换。 */
    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new QuantityKeeperBlockEntity(pos, state);
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
        if (!(blockEntity instanceof QuantityKeeperBlockEntity keeper)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu.create(
                id, inventory, keeper
            ),
            Component.translatable("block.rs_create_compat.quantity_keeper")
        );
    }

    /** 方块被破坏时：按全局「内容物去向」策略结算内部物品与流体（回网 / 爆出 / 存方块）。 */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof QuantityKeeperBlockEntity keeper) {
            BlockContentReleaser.release(level, pos, keeper,
                new ItemStack(RS_Create_Compat.QUANTITY_KEEPER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // 第 0 格是 <b>ghost 标记槽</b>（点击复制标记、不消耗手持），里面的物品不是玩家投入的
                        // 真实资源，绝不能当掉落物结算（否则等于凭空复制）；只结算插件槽 1..6 与真实的同类存储。
                        BlockContentReleaser.collectContainerRange(keeper.getInventory(), 1,
                            keeper.getInventory().getContainerSize(), out);
                        BlockContentReleaser.collectHandler(keeper.getItemStorage(), out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        BlockContentReleaser.collectFluidCache(
                            keeper.getFluidStorage(), level.registryAccess(), out);
                    }

                    @Override
                    public List<FluidStack> receiveFluids(final List<FluidStack> stacks) {
                        return BlockContentReleaser.receiveFluidCache(
                            keeper.getFluidStorage(), level.registryAccess(), stacks);
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

    /** 放置时把掉落物 NBT 里的标记与流体同类存储回填到新方块实体（零损耗）。 */
    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state,
                            final net.minecraft.world.entity.LivingEntity placer, final ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
            && level.getBlockEntity(pos) instanceof QuantityKeeperBlockEntity keeper) {
            final net.minecraft.world.item.component.CustomData custom =
                stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            final CompoundTag data = custom == null ? null : custom.copyTag();
            if (data != null && !data.isEmpty()) {
                keeper.loadPlacedState(data, level.registryAccess());
            }
        }
    }
}

