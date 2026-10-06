package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
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
 * 序列装配样板终端方块：编排序列装配流程、生成序列装配样板/单元样板，
 * 并可与 Create 序列装配配方联动（"+"号导入）。
 */
public class SequencePatternTerminalBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<SequencePatternTerminalBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public SequencePatternTerminalBlock(final Properties properties) {
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
        return new SequencePatternTerminalBlockEntity(pos, state);
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
        if (!(blockEntity instanceof SequencePatternTerminalBlockEntity terminal)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu.create(
                id, inventory, terminal
            ),
            Component.translatable("block.rs_create_compat.sequence_pattern_terminal")
        );
    }

    /**
     * 方块被破坏时：按全局「内容物去向」策略结算全部样板类内容
     * （单元库 / 流程编排 / 样板槽 / 原料产物废料）。
     * <p><b>特例</b>：样板永不回网 —— 全局选「回网」时这里的样板降级为「存方块」，
     * 重新放下原样恢复。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SequencePatternTerminalBlockEntity terminal) {
            BlockContentReleaser.release(level, pos, terminal,
                new ItemStack(RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // 本方块内部全部容器都是样板类内容（见 collectPatterns）
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 只能用「可掉落容器」白名单：输入原料标记 / 产物标记 / 废料标记这三个幽灵容器
                        // 只是界面显示模板（内容由生成逻辑复制进来，玩家从未投入），一旦被结算就是凭空复制。
                        // BlockContentReleaser 的收集入口还会按 GhostContent 再挡一次（双保险）。
                        for (final net.neoforged.neoforge.items.ItemStackHandler handler : terminal.getDroppableHandlers()) {
                            BlockContentReleaser.collectHandler(handler, out);
                        }
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
