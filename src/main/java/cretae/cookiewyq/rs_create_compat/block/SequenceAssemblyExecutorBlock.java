package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceAssemblyExecutorBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列装配执行器方块：接入 RS 网络，按“份”向输出面相邻容器投入序列装配样板的主原料，
 * 产线产物经输入总线回到网络，攒够目标数量后弹出完成横幅。
 */
public class SequenceAssemblyExecutorBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<SequenceAssemblyExecutorBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public SequenceAssemblyExecutorBlock(final Properties properties) {
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
        return new SequenceAssemblyExecutorBlockEntity(pos, state);
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
        if (!(blockEntity instanceof SequenceAssemblyExecutorBlockEntity executor)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.SequenceAssemblyExecutorMenu.create(
                id, inventory, executor),
            Component.translatable("block.rs_create_compat.sequence_assembly_executor")
        );
    }

    /** 方块被破坏时：按全局「内容物去向」策略结算样板（执行器库存全部为样板；永不回网）。 */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof SequenceAssemblyExecutorBlockEntity executor) {
            BlockContentReleaser.release(level, pos, executor,
                new ItemStack(RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // 执行器库存全部是样板类内容（见 collectPatterns）
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        BlockContentReleaser.collectContainer(executor.getInventory(), out);
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        // 本方块不含流体
                    }
                });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 需要「存方块」时抑制原版掉落，改由 {@link BlockContentReleaser#release} 补掉带 NBT 的方块物品。 */
    @Override
    public List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params) {
        final BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        return BlockContentReleaser.filterLoot(params.getLevel(), blockEntity,
            new ArrayList<>(super.getDrops(state, params)));
    }
}
