package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
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
 * 序列执行仓方块：接入 RS 网络的“分布式序列装配执行”节点，无 GUI。
 * <p>
 * 朝向 property 表示机器侧（朝向所指方向），方块贴靠在目标 Create 机旁放置：
 * 手持单元样板右键放入样板槽，空手右键取出（见方块实体交互）；
 * 接入网络后引擎自动认领本机步骤的过渡件/原料并喂给机器侧相邻容器。
 */
public class SequenceExecutionChamberBlock extends Block implements EntityBlock {
    /**
     * 机器侧朝向（取值规则见 {@link #getStateForPlacement}）。
     * <p><b>为什么是 6 向而不是 {@code HORIZONTAL_FACING}</b>：两条放置分支都可能给出 UP / DOWN ——
     * Shift 分支要「玩家朝向的反方向」（含俯仰：低头看脚下 → 朝上 UP；抬头看天 → 朝下 DOWN），
     * 非 Shift 分支在视线明显朝下 / 朝上时也要给出 UP / DOWN。
     * {@code HORIZONTAL_FACING} 只有 4 个水平值，装不下 UP / DOWN —— 这正是旧版本的缺陷，
     * 表现为玩家（尤其是不按 Shift 时）根本放不出朝上 / 朝下的执行仓。
     * <p>换成 {@link BlockStateProperties#FACING} 不影响老存档：两者的 property 名都是 {@code facing}，
     * 旧值 {@code north/south/east/west} 在新 property 里依旧合法，因此老档按原朝向直接加载，无需数据迁移。</p>
     */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    /** RS 的网络节点 ticker（负责 active 状态与网络侧驱动）。 */
    private static final BlockEntityTicker<SequenceExecutionChamberBlockEntity> NETWORK_TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    /**
     * 服务端 ticker：①先结算「旧存档迁移出来的磁盘」（磁盘槽已移除，老档那块盘必须退回世界，
     * 见 {@link SequenceExecutionChamberBlockEntity#resolveLegacyDiskServer()}）；②做一次链自愈
     * （把旧存档里「留在非链首身上的配置」归位到由方块朝向推导出的链首，
     * 见 {@link SequenceExecutionChamberBlockEntity#sanitizeRayServer()}），再交给 RS 的网络节点 ticker。
     * <p>两条都限频 / 一次即止（迁移落地后立刻置空）且只在链首缺配置时才落写入，因此这里几乎零开销；
     * 放在 ticker 最前面是为了<b>不依赖网络</b>：没接入 RS 网络的执行舱同样会迁移、同样会自愈。</p>
     */
    private static final BlockEntityTicker<SequenceExecutionChamberBlockEntity> TICKER =
        (level, pos, state, chamber) -> {
            chamber.resolveLegacyDiskServer();
            chamber.sanitizeRayServer();
            NETWORK_TICKER.tick(level, pos, state, chamber);
        };

    public SequenceExecutionChamberBlock(final Properties properties) {
        super(properties);
        // 默认朝北且未接入网络：使用灰色(inactive)贴图
        registerDefaultState(this.stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(ModBlockStateProperties.ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        // 追加 active 属性，用于未接入(灰)/已接入(亮)贴图切换
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    /**
     * 「明显朝下 / 朝上」的俯仰阈值（单位：度）。
     * <p><b>为什么取 45</b>：Shift 分支的 {@link BlockPlaceContext#getNearestLookingDirection()} 底层是
     * {@link Direction#orderedByNearest}，它按俯仰 / yaw 把视线量化到最近的 6 向；在「正对南北东西看」时，
     * 俯仰恰好 45° 处视线的竖直分量与水平分量等长（|sin| = |cos| = √2/2），再陡一点 UP / DOWN 才会压过水平方向。
     * 取同一个数量级，非 Shift 分支「陡到一定程度就算垂直」的手感就与 Shift 分支（原版量化）对齐，
     * 不会出现「明明在往下看却还是水平」的突兀感。
     * <p><b>为什么用严格大于（且带符号比较）</b>：恰好 45°（两者等长，本就没有「明显」之分）仍按水平处理，
     * 边界不会随着 0.1° 的手抖来回跳；<b>不等于</b>恰好 45° 时给 UP —— 那会让「45° 微微抬头」也朝上，方向反了。
     * <p><b>保守性</b>：原版量化在斜向（yaw 落在 45° 附近）时会提前到约 30° 就切 UP / DOWN，
     * 这里统一 45° 只会比原版<b>更晚</b>切垂直、绝不会更早，
     * 因此绝不会额外侵占用户已认可的水平四向行为。
     */
    private static final float STEEP_PITCH_DEGREES = 45.0F;

    /**
     * 放置时朝向（{@link #FACING} 语义 = 「机器侧」：朝向所指的那一侧就是它对接的机器 / 链的下游，
     * 见 {@code tmp_textures/verify_execchain_facing_chain.py} 的链推演）。
     *
     * <h2>规则（用户明确要求）</h2>
     * <ul>
     *     <li><b>不按 Shift、且视线不够陡</b>（俯仰 |pitch| &le; {@link #STEEP_PITCH_DEGREES}）：
     *     朝向 = <b>玩家水平朝向</b>（{@link BlockPlaceContext#getHorizontalDirection()}
     *     = {@code player.getDirection()}，只由 yaw 决定、忽略俯仰，例如玩家朝西 → 朝西）
     *     —— 这一支与旧版本<b>逐字相同</b>；</li>
     *     <li><b>不按 Shift、但视线明显朝下 / 朝上</b>（本次补齐的垂直那一维）：
     *     视线朝下（俯仰 &gt; 45°）→ 朝上 {@link Direction#UP}；
     *     视线朝上（俯仰 &lt; -45°）→ 朝下 {@link Direction#DOWN}；</li>
     *     <li><b>按下 Shift</b>：朝向 = <b>玩家视线方向的反方向</b>（<b>含俯仰</b>）：
     *     玩家朝西 → 朝东；低头看脚下（俯仰为正）→ 朝上 {@link Direction#UP}；
     *     抬头看天（俯仰为负）→ 朝下 {@link Direction#DOWN}。</li>
     * </ul>
     *
     * <h2>为什么非 Shift 分支也要补上垂直维（本次修复）</h2>
     * <p>用户实测：飞在空中、四周一根线缆都没有的地方放执行舱时并不按 Shift，而这一支此前只调
     * {@link BlockPlaceContext#getHorizontalDirection()}（只看 yaw）⇒ <b>朝上 / 朝下永远出不来</b>，
     * 手感上「明明在往下看，方块还是朝着东南西北」。原版同类 6 向机器（end_rod / lightning_rod）都是俯仰说了算。
     * 于是按<b>方案 B</b>：<b>水平四向的取值一字不改，只在视线足够陡时把垂直那一维补上</b>。
     *
     * <h2>俯仰符号约定（避免搞反）</h2>
     * <p>Minecraft 的 pitch 即 {@code getViewXRot}，<b>正值 = 朝下看</b>、负值 = 朝上看（正下方 +90、正上方 -90）；
     * 这与视线向量 {@code (x, y, z) = (-sin(yaw)·cos(pitch), -sin(pitch), cos(yaw)·cos(pitch))}
     * 中 {@code y = -sin(pitch)} 完全一致（pitch = +90 → y = -1，朝下）。因此判据写死为：
     * <ul>
     *     <li>{@code pitch >  STEEP_PITCH_DEGREES}（视线朝下）⇒ {@code FACING = } {@link Direction#UP}；</li>
     *     <li>{@code pitch < -STEEP_PITCH_DEGREES}（视线朝上）⇒ {@code FACING = } {@link Direction#DOWN}；</li>
     *     <li>其余 ⇒ 水平（旧语义）。</li>
     * </ul>
     * <p>角度源用 {@code player.getViewXRot(1.0F)}（partialTick = 1 的插值视线）而不是 {@code getXRot()}：
     * Shift 分支的 {@link Direction#orderedByNearest} 内部取的也是 {@code getViewXRot(1.0F)}，
     * 两条分支共用同一个角度源，临界处不会出现「一条算陡、一条算平」的打架。
     *
     * <h2>为什么 Shift 分支要用视线方向（上一轮修复）</h2>
     * <p>旧实现 Shift 分支写的是 {@code getHorizontalDirection().getOpposite()}，而
     * {@link BlockPlaceContext#getHorizontalDirection()} 就是 {@code player.getDirection()}
     * （只看 yaw 的<b>水平</b>朝向）⇒ <b>永远取不到 UP / DOWN</b>，玩家无法放置朝上 / 朝下的执行仓
     * —— 用户反馈的正是这一点。现在 Shift 分支改用 {@link BlockPlaceContext#getNearestLookingDirection()}：
     * 它等于 {@code Direction.orderedByNearest(player)[0]}，原版按 {@code getViewXRot(1.0F)}（俯仰）
     * 与 {@code getViewYRot(1.0F)}（水平）把<b>视线方向</b>量化到最近的一个方向（含 UP / DOWN），
     * 再 {@link Direction#getOpposite()} 取反，语义与用户要求逐字一致。
     * <p>于是 {@link #FACING} 也必须从 {@code HORIZONTAL_FACING}（4 个水平值）换成
     * {@link BlockStateProperties#FACING}（6 向）才能装下 UP / DOWN；两者 property 名同为 {@code facing}，
     * 旧值依旧合法 ⇒ 老存档原样加载。
     * <p><b>为什么不用复数版</b> {@link BlockPlaceContext#getNearestLookingDirections()}：它会把
     * 「被点击面的反方向」提到数组首位（stairs / slabs 那种用法），朝向会被点击面带偏；
     * 单数版才是纯粹的视线方向。
     * <p><b>为什么不用</b> {@code player.getViewVector(1.0F)}：两者对同一条视线等价，但用
     * {@link BlockPlaceContext} 的 API 不必依赖具体实体实现，且原版就用它做「玩家看向哪一面」的判定。
     * <p><b>玩家为 null</b>（指令 / 结构方块放置）时 {@code getNearestLookingDirection()} 会 NPE、
     * 也读不到俯仰，因此判据仍是 {@code player != null}（与改动前同一个判据），
     * 此时退回「不按 Shift 且视线水平」的水平朝向。</p>
     *
     * <p><b>与工程内其它机器的口径</b>：不按 Shift 且视线不够陡时，本方块仍与 {@code UnitPatternManagerBlock}
     * 用同一个原语（玩家水平朝向）；Shift 分支只加在<b>本方块</b>上，两类取值在视线水平时都与改动前完全相同
     * （视线水平时 {@code getNearestLookingDirection()} 给出同一个水平方向），
     * 因此所有依赖 {@code FACING} 的既有逻辑（链推导 {@code chainFace()}、机器绑定、面模式）逐字未变。</p>
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        // 非 Shift 的水平回退：这个表达式与改动前逐字相同（getHorizontalDirection 只看 yaw）。
        final Direction facing = context.getHorizontalDirection();
        final Player player = context.getPlayer();
        // player == null（指令 / 结构方块放置）时既读不到俯仰、getNearestLookingDirection() 还会 NPE
        // ⇒ 保持既有回退行为：按「不按 Shift 且视线水平」处理。
        if (player == null) {
            return defaultBlockState().setValue(FACING, facing);
        }
        // Shift 分支（上一轮已实现，本次不动）：视线方向（含俯仰）的反方向。
        if (player.isShiftKeyDown()) {
            return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
        }
        // 非 Shift：只在视线「明显朝下 / 朝上」时补出垂直那一维（符号约定见上方 javadoc）。
        final float pitch = player.getViewXRot(1.0F);
        if (pitch > STEEP_PITCH_DEGREES) {
            return defaultBlockState().setValue(FACING, Direction.UP);
        }
        if (pitch < -STEEP_PITCH_DEGREES) {
            return defaultBlockState().setValue(FACING, Direction.DOWN);
        }
        return defaultBlockState().setValue(FACING, facing);
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
        return new SequenceExecutionChamberBlockEntity(pos, state);
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

    /** 右键打开界面（3×2 单元样板槽，正常放入/取出）。 */
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
    protected net.minecraft.world.MenuProvider getMenuProvider(final BlockState state,
                                                              final Level level,
                                                              final BlockPos pos) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SequenceExecutionChamberBlockEntity chamber)) {
            return null;
        }
        return new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu.create(
                id, inventory, chamber),
            state.getBlock().getName()
        );
    }

    /**
     * 方块被破坏：按全局「内容物去向」策略结算单元样板（样板）、内部输出物品与流体
     * （总线输出模式下刚搬进来、还没被输出总线取走的物料也必须一并结算 —— 拆方块绝不丢资源）。
     * <p>顺带做一次<b>链移交</b>：本台若是链首（链级共享的名字 / 配方类型只存在它这一份上），
     * 先把配置交给「指向本台」的执行仓，免得拆掉链首就等于整条链失忆。
     * 这里是唯一可靠的时机 —— 世界与相邻方块实体都已加载，且区块卸载不会走 {@code onRemove}。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof SequenceExecutionChamberBlockEntity chamber) {
            if (!level.isClientSide()) {
                chamber.handOverChainOnRemoval();
            }
            BlockContentReleaser.release(level, pos, chamber,
                new ItemStack(RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        BlockContentReleaser.collectHandler(chamber.outputStorage, out);
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        BlockContentReleaser.collectContainer(chamber.unitSlots, out);
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        BlockContentReleaser.collectFluidStorage(chamber.outputTank, out);
                    }

                    @Override
                    public List<FluidStack> receiveFluids(final List<FluidStack> stacks) {
                        return BlockContentReleaser.receiveFluidStorage(chamber.outputTank, stacks);
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
