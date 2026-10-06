package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 「扳手分离线缆」这一次右键<b>到底要切哪一道缝</b>的唯一选缝入口
 * （服务端交互 {@code RsccWrenchCableInteraction} 与客户端预览 {@code CableCutPreviewOverlay} 共用）。
 *
 * <h2>为什么不能直接用准星命中的那个面（用户实测 bug 的根因）</h2>
 * <p>RS 线缆的碰撞 / 选中外形是「一根 4/16 粗细的芯 + 若干连接臂」
 * （{@code CableShapes}），<b>不是整格</b>。玩家朝正东看一条东西走向的线缆时，视线打在线缆
 * <b>朝上 / 朝北的那一小块表面</b>上，射线命中面于是成了 {@code UP} / {@code NORTH}，
 * 而玩家心里要切的是<b>正对着他的那一道缝</b>（朝东那一侧）——两者不是同一件事。
 * 于是出现用户报的「朝正东看却切了北/南那道缝」：改到了别的缝上，看起来就是
 * 「中间那几道缝怎么都断不了」。</p>
 *
 * <h2>选缝规则（先视线、再几何、最后固定顺序）</h2>
 * <ol>
 *     <li><b>候选集合 = 当下真的能切的那几道缝</b>：只收
 *     {@link RsccCableCuts#canToggleSeam}（没被断过 + 有连接臂或两侧都是线缆族）为真的方向。
 *     这一步与「服务端结算 {@code toggleSeam} 会不会回 {@code INVALID}」是<b>同一句判定</b>
 *     （就是 {@link RsccCableCuts} 里那一份），所以永远选不到一个切不动的方向；</li>
 *     <li><b>优先级①：与玩家视线方向的夹角</b> —— {@code dot(缝法线, 视线)} 越大越优先，
 *     也就是「玩家正对着的那一道缝优先」。朝正东看就选东边那道，朝上就看上面那道；</li>
 *     <li><b>优先级②：准星命中点到该缝平面的距离</b> —— 夹角打平时（例如上下俯视一条直线，
 *     东西两侧的点积都是 0），取准星真正落点更靠近的那一侧，符合「我瞄的就是这儿」；</li>
 *     <li><b>兜底：{@link Direction#values()} 的固定顺序</b> —— 前两级都分不出胜负时按固定顺序取第一个，
 *     保证结果<b>确定</b>（同样的输入永远给同样的缝，不会因迭代顺序或浮点抖动而变）。</li>
 * </ol>
 *
 * <h2>双端同源</h2>
 * <p>服务端用的是 {@code player.getLookAngle()}（由客户端上报的朝向算出）+ 事件的命中点，
 * 客户端用的是本地玩家同一组数据，两端跑的是<b>本类的同一个方法</b>，
 * 因此预览画出来的缝必然就是真正被切的那一道 —— 不会出现「预览在这道缝、结果断的是另一道」。</p>
 */
public final class RsccSeamPick {
    /** 浮点比较容差：点积 / 距离都只用它来判「是否打平」，避免浮点抖动改变选缝结果。 */
    private static final double EPSILON = 1.0E-6D;

    private RsccSeamPick() {
    }

    /**
     * 选出这一次右键要切的那一道缝。
     *
     * @param level       世界（服务端权威判定 / 客户端只读镜像都走同一句准入）
     * @param pos         玩家真正点到的那一格（准星命中的方块）
     * @param look        玩家视线方向（一般传 {@code player.getLookAngle()}；为 null 时退化成固定顺序）
     * @param hitLocation 准星命中点（一般传 {@code BlockHitResult#getLocation()}；为 null 时只按视线与固定顺序）
     * @return 选中的缝方向；{@code null} = 这一格<b>没有任何</b>可切的缝（调用方据此不接管 / 不画预览）
     */
    @Nullable
    public static Direction pick(final Level level, final BlockPos pos, @Nullable final Vec3 look,
                                 @Nullable final Vec3 hitLocation) {
        Direction best = null;
        double bestDot = 0.0D;
        double bestDistance = 0.0D;
        // 按 Direction.values() 固定顺序遍历：这就是「最后一级兜底」——只有严格更优才替换，
        // 因此打平时天然保留先遇到的那一个（结果确定，不依赖任何哈希 / 集合顺序）。
        for (final Direction direction : Direction.values()) {
            if (!RsccCableCuts.canToggleSeam(level, pos, direction)) {
                continue;
            }
            final double dot = look == null ? 0.0D : dot(look, direction);
            final double distance = seamDistance(hitLocation, pos, direction);
            if (best == null || isBetter(dot, distance, bestDot, bestDistance)) {
                best = direction;
                bestDot = dot;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 视线与缝法线的点积（越大 = 越正对）。 */
    private static double dot(final Vec3 look, final Direction direction) {
        return look.x * direction.getStepX() + look.y * direction.getStepY() + look.z * direction.getStepZ();
    }

    /** 是否严格优于当前最好：先比夹角（大者优先），夹角打平再比「到缝平面的距离」（小者优先）。 */
    private static boolean isBetter(final double dot, final double distance,
                                    final double bestDot, final double bestDistance) {
        if (dot > bestDot + EPSILON) {
            return true;
        }
        if (dot < bestDot - EPSILON) {
            return false;
        }
        return distance < bestDistance - EPSILON;
    }

    /**
     * 准星命中点到「这一格朝 {@code direction} 那道缝的平面」的距离（格）。
     *
     * <p>缝平面就在那一格的 {@code direction} 边界上（正向面在 1.0，负向面在 0.0），
     * 因此只要取命中点在该轴上的本地坐标与边界的差值即可 —— 一步算术，没有任何形状查询。
     * 命中点为空（理论上不会发生）时返回 0，等价于「全部打平，交给固定顺序兜底」。</p>
     */
    private static double seamDistance(@Nullable final Vec3 hitLocation, final BlockPos pos,
                                      final Direction direction) {
        if (hitLocation == null) {
            return 0.0D;
        }
        final double local = switch (direction.getAxis()) {
            case X -> hitLocation.x - pos.getX();
            case Y -> hitLocation.y - pos.getY();
            case Z -> hitLocation.z - pos.getZ();
        };
        final double plane = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0D : 0.0D;
        return Math.abs(local - plane);
    }
}
