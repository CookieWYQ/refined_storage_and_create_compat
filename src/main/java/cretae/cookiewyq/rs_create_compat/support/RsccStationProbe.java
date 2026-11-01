package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * <b>只读</b>：把「本总线朝向的那一格」解析成一台<b>加工机器</b>（第 104 轮 B 方案的地基层）。
 *
 * <h2>为什么需要它（用户硬目标：零配置即正确）</h2>
 * <p>「这一件该去哪个工位」在今天<b>从来没有被表达成一个数据</b>：目的地只由<b>总线朝向哪一格</b>
 * 这条物理事实决定（{@code AbstractExporterBlockEntityMixin#rscc$supplyTargetPos()}），
 * 判定层只有 (配方, 步序, 物品) 三种身份。B 方案不发明新概念，只把这条<b>已经存在</b>的隐式绑定
 * 显式化：先回答「这一格是哪台机器」，再回答「这台机器能做哪些步」。
 *
 * <h2>几何口径（全部取自 Create 6.0.10 自己的源码，<b>不写死任何「放置习惯」</b>）</h2>
 * <ol>
 *     <li><b>机器本身就是容器</b>（机械手 / 动力锯…）：总线朝向的格子就是那台机器，
 *     {@link #detect} 直接命中它；</li>
 *     <li><b>置物台 / 传送带 / 盆</b>（承载被加工件的那一格）：Create 的加工机恒在其
 *     <b>正上方 2 格</b> —— {@code BeltProcessingBehaviour} 类注释「Currently only supports placement
 *     location 2 spaces above the belt block」、{@code DepotBehaviour:130}
 *     {@code BlockEntityBehaviour.get(world, pos.above(2), BeltProcessingBehaviour.TYPE)}、
 *     {@code BasinBlockEntity:267/497} 同样读 {@code above(2)}；</li>
 *     <li><b>机械手</b>：它只作用于<b>朝向的 2 格外</b>（{@code DeployerBlockEntity#shouldActivate}
 *     / {@code activate} 恒用 {@code worldPosition.relative(facing, 2)}）⇒ 若那一格正是本总线的目标，
 *     这台机械手就是这个工位的处理器（它是「手位」机器：投入物要放进它的手里）。</li>
 * </ol>
 * <p>三条都<b>只读方块状态</b>，不做任何猜测性搜索：找不到 ⇒ 返回 {@code null}，
 * 调用方据此退回今天的宽松行为（判不出就<b>不收紧</b>）。</p>
 *
 * <h2>为什么不写「方块 → 配方类型」映射表</h2>
 * <p>「这台机器能做哪一步」由 Create 的 {@code IAssemblyRecipe#addRequiredMachines} 给出
 * （冲压 → 动力冲压机、注液 → 注液器、装配 → 机械手、切割 → 动力锯），
 * 与配方类型 id 一一对应；本类只负责把<b>方块</b>对上这份机器清单，
 * 因此附属模组新增装配步时不需要改这里（{@code SequenceExecutionChamberBlockEntity}
 * 那侧同样只读 {@code addRequiredMachines}）。</p>
 *
 * <p>只读：不搬运 / 不修改任何方块或容器。</p>
 */
public final class RsccStationProbe {
    private RsccStationProbe() {
    }

    /**
     * 本总线朝向那一格解析出来的「工位处理器」。
     *
     * @param item 机器方块物品（用于与步骤的 {@code addRequiredMachines} 比对）
     * @param pos  机器自己的坐标（日志 / 界面显示用）
     * @param hand <b>手位机器</b>（机械手）：投入物必须放进它<b>自己的</b>手位，
     *             过渡件则放在它作用的那一格；两种摆放的默认类别集因此不同
     */
    public record StationMachine(Item item, BlockPos pos, boolean hand) {
    }

    /**
     * 解析工位处理器：{@code target}（= 总线朝向那一格）本身是候选机器 ⇒ 它；
     * 否则看它<b>正上方 2 格</b>（置物台 / 传送带 / 盆上的加工机）；
     * 再看四周 2 格外「正作用着这一格」的机械手。判不出 ⇒ {@code null}。
     *
     * @param candidates 本链各步 {@code addRequiredMachines} 给出的机器物品全集
     *                   （不在这个集合里的方块一律不算机器 —— 于是箱子 / 缓存仓等
     *                   「只当容器用」的布局不会误判，直接走「判不出 ⇒ 宽松」）
     */
    @Nullable
    public static StationMachine detect(final Level level, @Nullable final BlockPos target,
                                        final Set<Item> candidates) {
        if (level == null || target == null || candidates == null || candidates.isEmpty()
            || !level.isLoaded(target)) {
            return null;
        }
        final BlockState state = level.getBlockState(target);
        final Item own = itemOf(state);
        if (own != null && candidates.contains(own)) {
            // 机器本身就是容器：总线朝向它 = 直接喂它。机械手是唯一「手位」语义的机器。
            return new StationMachine(own, target, isDeployer(level, target));
        }
        // 承载件（置物台 / 传送带 / 盆）：加工机在正上方 2 格（见类注释的三处 Create 源码证据）
        final BlockPos above = target.above(2);
        if (level.isLoaded(above)) {
            final Item aboveItem = itemOf(level.getBlockState(above));
            if (aboveItem != null && candidates.contains(aboveItem)) {
                return new StationMachine(aboveItem, above, false);
            }
        }
        // 机械手：朝向的 2 格外就是本目标 ⇒ 这台机械手在加工这一格上的件
        for (final Direction direction : Direction.values()) {
            final BlockPos machine = target.relative(direction, 2);
            if (!level.isLoaded(machine)) {
                continue;
            }
            final BlockState machineState = level.getBlockState(machine);
            final Item machineItem = itemOf(machineState);
            if (machineItem == null || !candidates.contains(machineItem)) {
                continue;
            }
            if (target.equals(operatingPosOf(level, machine))) {
                return new StationMachine(machineItem, machine, false);
            }
        }
        return null;
    }

    /** 该方块的物品形态（非方块 / 取不到 ⇒ {@code null}）。 */
    @Nullable
    public static Item itemOf(@Nullable final BlockState state) {
        if (state == null) {
            return null;
        }
        // 方块自己的物品形态（空气方块没有物品 ⇒ asItem() 返回 AIR，调用方按「不是机器」处理）
        final Item item = state.getBlock().asItem();
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    /** 该坐标方块的注册 id 字符串（未加载 / 取不到 ⇒ 空串）；供界面显示与诊断使用。 */
    public static String blockId(final Level level, @Nullable final BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return "";
        }
        final BlockState state = level.getBlockState(pos);
        final ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "" : key.toString();
    }

    /**
     * 只读：该坐标上是不是机械手（{@code create:deploying} 的机器，按 Create 自己的方块类型判定）。
     * <p>判据本体仍只有一份：{@link cretae.cookiewyq.rs_create_compat.block.entity
     * .SequenceExecutionChamberBlockEntity#operatingPosOf}（位移量「2」的 Create 源码依据也记在那里）。
     * 本类只转发，工位推导因此与既有「哪一格是机械手正在加工的那一格」完全同源。</p>
     */
    public static boolean isDeployer(final Level level, @Nullable final BlockPos target) {
        return target != null && operatingPosOf(level, target) != null;
    }

    /**
     * 只读：该方块是机械手时返回它<b>实际作用的那一格</b>（Create {@code DeployerBlockEntity}
     * 朝向的 2 格外）；不是机械手 ⇒ {@code null}。
     * <p>唯一实现见 {@link cretae.cookiewyq.rs_create_compat.block.entity
     * .SequenceExecutionChamberBlockEntity#operatingPosOf}。</p>
     */
    @Nullable
    public static BlockPos operatingPosOf(final Level level, @Nullable final BlockPos machine) {
        return cretae.cookiewyq.rs_create_compat.block.entity
            .SequenceExecutionChamberBlockEntity.operatingPosOf(level, machine);
    }
}
