package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SchematicLoaderBlockEntity;
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
import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;

import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图加农炮装填器（基础版）方块。
 */
public class SchematicLoaderBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<SchematicLoaderBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public SchematicLoaderBlock(final Properties properties) {
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
        // 基础装填器始终打开自己的菜单（不因同集群存在高级装填器而转发成高级界面）
        // 开界面前先做一次蓝图槽对齐：把旧档遗留的本机副本并入加农炮，界面里看到的即是加农炮那一份
        loader.adoptLocalBlueprintOnDemand();
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.SchematicLoaderMenu.create(
                id, inventory, loader
            ),
            Component.translatable("block.rs_create_compat.schematic_loader")
        );
    }

    /** 方块被破坏时：按全局「内容物去向」策略结算库存 / 蓝图槽 / 插件槽 / 队列。 */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SchematicLoaderBlockEntity loader) {
            BlockContentReleaser.release(level, pos, loader,
                new ItemStack(RS_Create_Compat.SCHEMATIC_LOADER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        BlockContentReleaser.collectHandler(loader.getInventory(), out);
                        BlockContentReleaser.collectHandler(loader.getBlueprintSlot(), out);
                        BlockContentReleaser.collectHandler(loader.getUpgradeContainer(), out);
                        BlockContentReleaser.collectHandler(loader.getQueue(), out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
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

