package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.DecompositionGeneratorBlockEntity;
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

import java.util.List;

/**
 * 「降解室发电机」方块：接入 RS 网络、右键打开界面。
 *
 * <ul>
 *     <li>{@link ModBlockStateProperties#ACTIVE}：<b>正在降解时亮</b>（idle 变体 / active 变体两张贴图，
 *     由 {@link DecompositionGeneratorBlockEntity#calculateActive()} 决定）；</li>
 *     <li>方块实体 ticker 用 RS 的 {@link NetworkNodeBlockEntityTicker}（与其它机器同一套：
 *     它负责「更新激活状态 + 驱动网络节点 doWork」）；</li>
 *     <li>被拆时按 {@link BlockContentReleaser} 结算 <b>20 格物品库存（16 输入 + 4 副产物）+
 *     6 格升级容器</b>（回网 / 爆出 / 存进方块物品三档），<b>不丢</b>；
 *     {@link #getDrops} 与它配套（需要「存方块」时抑制原版战利品）。</li>
 * </ul>
 */
public class DecompositionGeneratorBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<DecompositionGeneratorBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.DECOMPOSITION_GENERATOR_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public DecompositionGeneratorBlock(final Properties properties) {
        super(properties);
        // 默认未工作：使用 idle 贴图（没有这一行 = 缺 active 属性变体 ⇒ 紫黑方块）
        registerDefaultState(defaultBlockState().setValue(ModBlockStateProperties.ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new DecompositionGeneratorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level,
                                                                  final BlockState state,
                                                                  final BlockEntityType<T> type) {
        // 只在服务端 tick：客户端不需要跑机器逻辑（数据和状态位都由菜单/方块状态同步）
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

    /**
     * 被破坏时结算 <b>20 格物品库存</b>与 <b>6 格升级容器</b>（三档口径见 {@link BlockContentReleaser}）。
     * <p><b>两处都要收</b>：升级容器不在物品库存里（它是单独的 6 格 handler），只收库存会漏掉
     * 速度 / 堆叠升级；两处都走同一个 {@link BlockContentReleaser#collectHandler}（取出并清空，
     * 绝不销毁），因此也不会出现「同一件东西既进掉落列表又留在 NBT 里」的重复。</p>
     * <p>「当前正在降解的物品」展示槽已在第 123 轮删除（16 格输入全部可见），因此没有第三处要收。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof DecompositionGeneratorBlockEntity generator) {
            BlockContentReleaser.release(level, pos, generator,
                new ItemStack(RS_Create_Compat.DECOMPOSITION_GENERATOR_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // ① 20 格物品库存（16 输入 + 4 副产物）
                        BlockContentReleaser.collectHandler(generator.getInventory(), out);
                        // ② 6 格升级容器（第 123 轮新增：不结算它 = 拆方块吞掉玩家的速度 / 堆叠升级）
                        BlockContentReleaser.collectHandler(generator.getUpgradeContainer(), out);
                        // ③ 旧存档迁移「待掉出」的余量（不在上面两个容器里；不收它 = 真丢物品）
                        generator.drainPendingDump(out);
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
            new java.util.ArrayList<>(super.getDrops(state, params)));
    }

    @Nullable
    @Override
    protected MenuProvider getMenuProvider(final BlockState state,
                                           final Level level,
                                           final BlockPos pos) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof DecompositionGeneratorBlockEntity generator)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.DecompositionGeneratorMenu.create(
                id, inventory, generator
            ),
            Component.translatable("block.rs_create_compat.decomposition_generator")
        );
    }
}
