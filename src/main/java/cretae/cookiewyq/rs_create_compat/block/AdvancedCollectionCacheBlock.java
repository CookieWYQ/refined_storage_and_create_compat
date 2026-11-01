package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.AdvancedCollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
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
 * <b>高级归流缓存仓</b>方块：外观 / 交互 / 掉落结算与基础版 {@link CollectionCacheBlock} 同构，
 * 只是方块实体换成 {@link AdvancedCollectionCacheBlockEntity}（更大的缓存 + 区域选定器槽）。
 *
 * <p><b>为什么是独立方块类而不是继承 {@code CollectionCacheBlock}</b>（照
 * {@code AdvancedSchematicLoaderBlock} 的先例）：</p>
 * <ul>
 *     <li>ticker / 菜单提供者 / 方块实体类型都必须指向高级版自己的类型；</li>
 *     <li>{@code RsccMachineCluster.isClusterBlock} 用 {@code instanceof CollectionCacheBlock} 判定
 *     「同族可成集群」。若高级版是它的子类，基础版（256 格）与高级版（512 格）会相邻合并，
 *     集群容量按主控的单机规格决定 —— 高级版的容量会时大时小，玩家根本猜不到。
 *     独立类让它<b>自成一档</b>（不参与基础版集群，单机即满载 512 格）。</li>
 * </ul>
 */
public class AdvancedCollectionCacheBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<AdvancedCollectionCacheBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.ADVANCED_COLLECTION_CACHE_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public AdvancedCollectionCacheBlock(final Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ModBlockStateProperties.ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new AdvancedCollectionCacheBlockEntity(pos, state);
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
        if (!(blockEntity instanceof AdvancedCollectionCacheBlockEntity cache)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu.create(
                id, inventory, cache),
            Component.translatable("block.rs_create_compat.advanced_collection_cache")
        );
    }

    /** 破坏：缓存区 / 插件槽 / **区域选定器槽** / 流体缓存按全局「内容物去向」结算。 */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof AdvancedCollectionCacheBlockEntity cache) {
            BlockContentReleaser.release(level, pos, cache,
                new ItemStack(RS_Create_Compat.ADVANCED_COLLECTION_CACHE_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        BlockContentReleaser.collectContainer(cache.getCache(), out);
                        BlockContentReleaser.collectContainer(cache.getUpgradeContainer(), out);
                        // 区域选定器是真物品：不写进方块 NBT，按内容物策略回网 / 爆出 / 存方块
                        BlockContentReleaser.collectHandler(cache.getAreaSlot(), out);
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

    /** 需要「存方块」时抑制原版掉落，统一由 {@link BlockContentReleaser#release} 补掉带 NBT 的方块物品。 */
    @Override
    public List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params) {
        final BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        return BlockContentReleaser.filterLoot(params.getLevel(), blockEntity,
            new ArrayList<>(super.getDrops(state, params)));
    }

    /** 放置时回填基础版那部分「非实体掉落」状态（匹配区 / 流体余量 / 半径 / 阻塞名单…）。 */
    @Override
    public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state,
                            final LivingEntity placer, final ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()
            && level.getBlockEntity(pos) instanceof AdvancedCollectionCacheBlockEntity cache) {
            final net.minecraft.world.item.component.CustomData custom =
                stack.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            final CompoundTag data = custom == null ? null : custom.copyTag();
            if (data != null && !data.isEmpty()) {
                cache.loadPlacedState(data, level.registryAccess());
            }
        }
    }
}
