package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
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

import java.util.List;

/**
 * 网络资源接收器方块：一个<b>带名字的落地点 + 缓存</b>。
 *
 * <h2>形态（用户拍板）</h2>
 * <ul>
 *     <li><b>6 向 {@link #FACING}（照执行舱）</b>：朝向所指的那一面就是「输出面」——
 *     缓存里的东西会被推到那一面相邻的容器；</li>
 *     <li>有内部缓存（物品 + 流体，见方块实体）；</li>
 *     <li><b>必须命名</b>：没有名字就<b>完全不可用</b>（不输出、也不能被请求器解析为投递目标）；</li>
 *     <li>只收<b>速度升级 + 叠加升级</b>，共 6 槽。</li>
 * </ul>
 *
 * <h2>⚠️ 方块状态必须与 blockstate JSON 逐字一致</h2>
 * <p>{@code assets/rs_create_compat/blockstates/network_resource_receiver.json} 的变体键是
 * {@code facing=<north|east|south|west|up|down>} 与 {@code active=<true|false>}，共 <b>12 个变体</b>。
 * 因此本类注册的属性<b>恰好</b>是 {@link BlockStateProperties#FACING}（6 向，序列化名 {@code facing}）
 * 与 {@link ModBlockStateProperties#ACTIVE}（名 {@code active}），且都不是 {@code HORIZONTAL_FACING}
 * —— 后者只有 4 个水平值，装不下 {@code up} / {@code down}，那 4 个变体会永远取不到模型。
 * 改这里的属性就必须同步改 JSON（{@code tools/selfcheck_round67_new_blocks_skeleton.py} 会断言这一点）。</p>
 *
 * <h2>朝向的取值规则</h2>
 * <p>与执行舱同一口径（非 Shift 时用玩家<b>水平</b>朝向；Shift 时用<b>视线方向的反方向</b>，含俯仰）。
 * 但这里<b>刻意不</b>抄执行舱那条「非 Shift + 视线明显朝上/朝下 ⇒ 补出 UP / DOWN」的补丁：
 * 本方块的玩家可见语义是「朝向所指的容器会被输出」，水平四向覆盖绝大多数摆法，
 * 而想精确指定上/下只需按 Shift 看向它 —— 少一条隐式规则就少一处「明明没按 Shift 却朝上了」的意外。</p>
 */
public class NetworkResourceReceiverBlock extends Block implements EntityBlock {
    /** 输出面朝向（6 向；取值规则见类注释与 {@link #getStateForPlacement}）。 */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    /**
     * RS 的网络节点 ticker：按节点 {@code active} 状态切换 {@code active} 属性
     * （未接入网络 = 灰贴图，已接入 = 亮贴图）。
     * <p>与其它机器逐字同构，因此「接进网络才亮」这件事不需要自己写 tick。</p>
     */
    private static final BlockEntityTicker<NetworkResourceReceiverBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public NetworkResourceReceiverBlock(final Properties properties) {
        super(properties);
        // 默认朝北且未接入网络：朝北 = 原版放置前的安全默认值（玩家放置时会被 getStateForPlacement 覆盖）
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(ModBlockStateProperties.ACTIVE, false));
    }

    /** 注册 {@code facing} + {@code active}：必须与 blockstate JSON 的 12 个变体键完全对应。 */
    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    /**
     * 放置时朝向 = 「输出面」。
     * <ul>
     *     <li><b>按下 Shift</b>：视线方向的反方向（含俯仰）—— 想让它朝上 / 朝下就低头 / 抬头看向那一侧，
     *     与执行舱的 Shift 分支<b>逐字相同</b>，玩家不必学第二套；</li>
     *     <li><b>不按 Shift</b>：玩家水平朝向（只看 yaw）。</li>
     * </ul>
     * <p>{@code player == null}（指令 / 结构方块放置）时 {@code getNearestLookingDirection()} 会 NPE，
     * 因此先退回水平朝向（与执行舱同一处防御）。</p>
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        final Direction horizontal = context.getHorizontalDirection();
        final Player player = context.getPlayer();
        if (player == null) {
            return defaultBlockState().setValue(FACING, horizontal);
        }
        if (player.isShiftKeyDown()) {
            return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
        }
        return defaultBlockState().setValue(FACING, horizontal);
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
        return new NetworkResourceReceiverBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level,
                                                                  final BlockState state,
                                                                  final BlockEntityType<T> type) {
        // 只在服务端 tick：网络节点驱动（抽电 / active 判定）是服务端权威行为
        if (level.isClientSide()) {
            return null;
        }
        return (BlockEntityTicker<T>) TICKER;
    }

    /** 右键打开界面（名字输入 + 6 个升级槽）。 */
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
        if (!(level.getBlockEntity(pos) instanceof NetworkResourceReceiverBlockEntity receiver)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.NetworkResourceReceiverMenu
                .create(id, inventory, receiver),
            Component.translatable("block.rs_create_compat.network_resource_receiver"));
    }

    /**
     * 方块被破坏：按全局「内容物去向」策略结算<b>升级槽 + 缓存（物品 / 流体）</b>。
     * <p>缓存里的东西是玩家投递进来的资源，<b>绝不能销毁</b>（守恒铁律）；
     * 走 {@link BlockContentReleaser} 就自动尊重 {@code /rs_create_compat blockcontent} 的三档策略
     * （回网 / 爆出 / 存方块），与其它机器完全一致。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof NetworkResourceReceiverBlockEntity receiver) {
            BlockContentReleaser.release(level, pos, receiver,
                new ItemStack(RS_Create_Compat.NETWORK_RESOURCE_RECEIVER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // 升级物品 + 缓存物品一起结算（两者都是真实物品，都不能丢）
                        BlockContentReleaser.collectHandler(receiver.getUpgradeContainer(), out);
                        BlockContentReleaser.collectHandler(receiver.getItemCache(), out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        BlockContentReleaser.collectFluidStorage(receiver.getFluidCache(), out);
                    }

                    @Override
                    public List<FluidStack> receiveFluids(final List<FluidStack> stacks) {
                        return BlockContentReleaser.receiveFluidStorage(receiver.getFluidCache(), stacks);
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
}
