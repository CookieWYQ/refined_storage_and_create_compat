package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.UnitPatternManagerMenu;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「单元样板管理舱」方块：接入 RS 网络后管理网络内各执行舱的单元样板。
 *
 * <p>与 RS 的「自动合成管理舱」（Autocrafter Manager，管自动合成样板）区分：
 * 本方块只呈现<b>执行舱的单元样板</b>，界面布局照抄前者（同一张贴图与几何），
 * 但分组维度是「一台执行舱 = 一组」。</p>
 */
public class UnitPatternManagerBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final BlockEntityTicker<UnitPatternManagerBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.UNIT_PATTERN_MANAGER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public UnitPatternManagerBlock(final Properties properties) {
        super(properties);
        // 默认朝北且未接入网络：使用灰色(inactive)贴图，与其它机器一致
        registerDefaultState(this.stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(ModBlockStateProperties.ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(final BlockState state, final Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(final BlockState state, final Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new UnitPatternManagerBlockEntity(pos, state);
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

    /** 右键打开管理界面（与无线终端的「单元样板管理」模式是同一个菜单）。 */
    @Override
    protected InteractionResult useWithoutItem(final BlockState state,
                                               final Level level,
                                               final BlockPos pos,
                                               final Player player,
                                               final BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof UnitPatternManagerBlockEntity manager
            && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            UnitPatternManagerMenu.open(serverPlayer, manager,
                Component.translatable(UnitPatternManagerMenu.TITLE_KEY));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Nullable
    @Override
    protected MenuProvider getMenuProvider(final BlockState state, final Level level, final BlockPos pos) {
        return null; // 界面经 UnitPatternManagerMenu#open 打开（要携带分组结构额外数据）
    }

    /**
     * 方块被破坏：按全局「内容物去向」策略结算本方块自带的内容。
     * <p><b>刻意不碰执行舱的单元样板</b>：那些样板属于其它方块的存储，本方块只是「视图」，
     * 破坏本方块绝不能动别人的东西。</p>
     * <p>这里回收的是<b>历史遗留</b>的那 1 格「额外槽位」（界面已移除、新内容再也放不进去，
     * 但旧存档里可能还留着物品）—— 破坏方块即原样掉落，旧数据不会凭空消失。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof UnitPatternManagerBlockEntity manager) {
            BlockContentReleaser.release(level, pos, manager,
                new ItemStack(RS_Create_Compat.UNIT_PATTERN_MANAGER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        BlockContentReleaser.collectContainer(manager.extraSlot(), out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不持有样板：执行舱的样板属于各执行舱自身
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        // 本方块不持有流体
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
