package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity;
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
 * 中间产物缓存仓方块：接入 RS 网络后，本仓的磁盘成为<b>网络内所有序列装配执行舱共享</b>的
 * 中间产物临时储存点（用户决定：磁盘是通用的，只做一个所有这种的临时储存点）。
 *
 * <p>接入方式与其它 RS 机器完全一致（贴线缆即入网，见 {@code IntermediateCacheNetworkNode}），
 * 因此「扳手断开线缆」「分隔框架阻断」照旧由 RS 网络图管；空手右键打开界面放 / 取磁盘。</p>
 *
 * <p><b>内容物去向</b>：本仓的内容物就是那几块磁盘，破坏时交给本模组统一的
 * {@link BlockContentReleaser} 策略结算（默认 DROP = 连盘带内容爆出；选 NETWORK 则磁盘作为物品
 * 进网络；选 BLOCK 则整份 NBT 存进方块物品，重新放下原样恢复）。三种去向都不拆盘、不搬盘内物品 ——
 * 盘里的东西由 RS 存储仓库按磁盘 UUID 指认，永远跟着那块盘走。</p>
 */
public class IntermediateCacheBlock extends Block implements EntityBlock {
    /** RS 的网络节点 ticker（负责 active 状态与网络侧驱动）。 */
    private static final BlockEntityTicker<IntermediateCacheBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.INTERMEDIATE_CACHE_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public IntermediateCacheBlock(final Properties properties) {
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
        return new IntermediateCacheBlockEntity(pos, state);
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

    /** 右键打开界面（27 格盘位，正常放入 / 取出）。 */
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
        if (!(blockEntity instanceof IntermediateCacheBlockEntity cache)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.IntermediateCacheMenu.create(
                id, inventory, cache),
            Component.translatable("block.rs_create_compat.intermediate_cache")
        );
    }

    /**
     * 方块被破坏时：把盘位里的磁盘按全局「内容物去向」策略结算掉。
     * <p>本仓<b>没有</b>自带物品容量（第 7 轮按用户要求改成「一个小箱子大小的磁盘存放空间」），
     * 所以内容物也只有一份：<b>盘位里的磁盘</b>。盘里的物品在 RS 存储仓库里（由磁盘 UUID 指认），
     * 因此把盘交出去就等于把内容交出去 —— <b>盘带内容走</b>，既不会拆出一份「另外算的」物品
     * （那才是复制事故），也绝不会把盘销毁。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof IntermediateCacheBlockEntity cache) {
            BlockContentReleaser.release(level, pos, cache,
                new ItemStack(RS_Create_Compat.INTERMEDIATE_CACHE_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        BlockContentReleaser.collectContainer(cache.diskSlots, out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        // 本方块不含流体存储
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
