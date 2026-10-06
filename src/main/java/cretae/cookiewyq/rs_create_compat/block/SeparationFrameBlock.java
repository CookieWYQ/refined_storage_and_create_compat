package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.block.entity.SeparationFrameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 分隔框架（普通版 {@code separation_frame} / 无限版 {@code infinite_separation_frame}）的<b>方块形态</b>。
 *
 * <p><b>2026-09-25 语义变更（批 4b）：本方块不再是「放在管道旁边的那个方块」</b></p>
 * <p>用户明确要求「不要做一个单独的方块存在，而是真正的把它套在里面」。由于一个方块格里只能有一个方块，
 * 「套壳」在实现上落成了 {@code support/RsccSheaths} 的<b>坐标标记</b>：
 * 手持框架右键一段线缆 / 管道 → 那一格被标记为「已套壳」→ 那里仍是原来那根管道，
 * 只是在它外面多画一层壳（外套模型）、并切断它与邻居的连接。</p>
 * <p>因此：</p>
 * <ul>
 *     <li><b>物品不再放置本方块</b>：{@code item/SeparationFrameItem#useOn} 全面接管，只对
 *     {@link cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard#isSheathable}
 *     为真的方块生效（强制约束：用在别处不放置、只给提示）；连带的后果是本方块在正常玩法里
 *     <b>不再由物品产生</b>（旧存档 / 指令 / 调试放置出来的仍是同一个注册 ID，因此注册表与既有存档
 *     完全兼容，不会出现「未知方块」）；</li>
 *     <li><b>本方块不再影响任何连接</b>：连接阻断的唯一判据已改成「那一格自己被套住」
 *     （见 {@link cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard}），
 *     所以「在旁边放一个框架方块」不会再断开任何管道 —— 这正是用户抱怨的那条 bug；</li>
 *     <li>渲染仍然沿用原来的规则（默认不可见；手持框架或护目镜 + 快捷键才显形），
 *     由 {@code client/SeparationFrameRenderer} 按帧判断；真正套在管道上的壳由
 *     {@code client/SheathRenderer} 绘制。</li>
 * </ul>
 *
 * <p><b>为什么六面不可见还要留着真方块</b>：方块自己的渲染形状一律返回
 * {@link RenderShape#INVISIBLE}（区块网格阶段直接跳过，零渲染开销），
 * 真正绘制交给客户端方块实体渲染器按帧判断条件后再画模型 —— 这是唯一能做到
 * 「随玩家手持物品变化而即时显隐」的做法（区块网格是烘焙缓存的，无法按帧变化）。</p>
 *
 * <p><b>碰撞与选中框</b>：{@code noCollission()}，不添加任何碰撞体系（用户要求「碰不到」）；
 * 但选中框取<b>整格</b>（{@code 0..16}，与普通方块一样大）—— 用户要求
 * 「那个框应该和正常方块一样大」，框是整格的、但依旧撞不上去。</p>
 */
public class SeparationFrameBlock extends Block implements EntityBlock {
    /** 选中框：整格（0 → 16），与普通方块一致；碰撞体仍然为空（noCollission）。 */
    private static final VoxelShape SHAPE = Shapes.block();

    /** 无限版：放置不消耗物品、被拆下不掉落物品、渲染时叠加附魔光泽。 */
    private final boolean infinite;

    public SeparationFrameBlock(final boolean infinite, final Properties properties) {
        super(properties);
        this.infinite = infinite;
    }

    /** 无限版标记（渲染器据此叠加附魔光泽 glint）。 */
    public boolean isInfinite() {
        return infinite;
    }

    /** 六面一律不参与区块网格烘焙：显隐条件由客户端方块实体渲染器按帧决定。 */
    @Override
    public RenderShape getRenderShape(final BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /**
     * 选中框 = 整格（{@code 0..16}）。
     * <p>由于方块注册为 {@code noCollission()}，这里的形状只影响<b>选中框</b>与视觉效果，
     * 不会带来任何碰撞体积 —— 与用户要求「框和正常方块一样大、但碰不到」完全对齐。
     * 视觉形状显式取同一份，避免不同调用方（{@code getShape} 与 {@code getVisualShape}）
     * 将来各自漂移。</p>
     */
    @Override
    public VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos,
                               final CollisionContext context) {
        return SHAPE;
    }

    /** 与 {@link #getShape} 同一份整格形状（某些路径读的是视觉形状）。 */
    @Override
    public VoxelShape getVisualShape(final BlockState state, final BlockGetter level, final BlockPos pos,
                                     final CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new SeparationFrameBlockEntity(pos, state);
    }
}
