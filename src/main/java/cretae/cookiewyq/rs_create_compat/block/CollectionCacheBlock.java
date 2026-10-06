package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 归流缓存仓方块：接入 RS 网络后，把周围散落的掉落物按 ghost 标记规则凑够数量后收集并写入网络。
 */
public class CollectionCacheBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<CollectionCacheBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.COLLECTION_CACHE_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public CollectionCacheBlock(final Properties properties) {
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
        return new CollectionCacheBlockEntity(pos, state);
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
        if (!(blockEntity instanceof CollectionCacheBlockEntity cache)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu.create(
                id, inventory, cache),
            Component.translatable("block.rs_create_compat.collection_cache")
        );
    }

    /**
     * 方块被破坏时：按全局「内容物去向」策略结算缓存区、插件槽与流体缓存
     * （回网 / 爆出 / 存方块；匹配区是 ghost 标记，不产生掉落）。
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof CollectionCacheBlockEntity cache) {
            BlockContentReleaser.release(level, pos, cache,
                new ItemStack(RS_Create_Compat.COLLECTION_CACHE_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        BlockContentReleaser.collectContainer(cache.getCache(), out);
                        BlockContentReleaser.collectContainer(cache.getUpgradeContainer(), out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        BlockContentReleaser.collectFluidCache(
                            cache.getFluidCache(), level.registryAccess(), out);
                    }

                    @Override
                    public List<FluidStack> receiveFluids(final List<FluidStack> stacks) {
                        return BlockContentReleaser.receiveFluidCache(
                            cache.getFluidCache(), level.registryAccess(), stacks);
                    }
                });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * 需要「存方块」时抑制原版掉落，改由 {@link BlockContentReleaser#release} 补掉带 NBT 的方块物品
     * （流体余量与标记配置因此零损耗）。
     */
    @Override
    public List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params) {
        final BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        return BlockContentReleaser.filterLoot(params.getLevel(), blockEntity,
            new ArrayList<>(super.getDrops(state, params)));
    }

    /** 放置时把掉落物 NBT 里的流体/匹配区状态回填到新方块实体（零损耗）。 */
    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state,
                            final net.minecraft.world.entity.LivingEntity placer, final ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
            && level.getBlockEntity(pos) instanceof CollectionCacheBlockEntity cache) {
            final net.minecraft.world.item.component.CustomData custom =
                stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            final CompoundTag data = custom == null ? null : custom.copyTag();
            if (data != null && !data.isEmpty()) {
                cache.loadPlacedState(data, level.registryAccess());
            }
        }
    }
}
