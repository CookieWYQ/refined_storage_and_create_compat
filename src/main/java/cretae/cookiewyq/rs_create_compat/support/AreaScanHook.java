package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 「只按区域吸收」的扫描接口（<b>基础版与高级版之间唯一的耦合面</b>）。
 *
 * <h2>为什么用接口而不是在基础版里写 if (advanced)</h2>
 * <p>基础版 {@code CollectionCacheBlockEntity} 的三条扫描路径（掉落物 / 经验球 / 流体源）在
 * 用户拍板后要支持「高级版：区域取代 XYZ」。把区域逻辑塞进基础版会让基础版被迫加载区域类型；
 * 反过来在高级版里整体重写三条扫描又会复制几千行（而且和「照基础版的结构、别另起一套」相反）。
 * 这里的做法是：基础版只多<b>两个钩子</b>（{@code areaScanHook} / {@code areaAbsorptionBlocked}），
 * 扫描路径在钩子非 null 时改用本接口提供的几何；高级版实现本接口。</p>
 *
 * <h2>性能契约（高级版实现必须满足）</h2>
 * <ul>
 *     <li>{@link #entityQueryAreas()}：<b>按区域簇分组，每簇一个 AABB、每簇一次实体查询</b>，
 *     再按 {@link #contains(BlockPos)} / {@link #containsEntity(Entity)} 过滤 —— 绝不每个区域查一次实体表；
 *     单簇（区域全相邻）时必须退化成 1 个盒，与第 121 轮之前的行为逐字一致；</li>
 *     <li>{@link #scanFluidWindow(Level, int, Predicate)}：<b>跨 tick 游标 + 每 tick 查询上限</b>，
 *     同一 tick 的重复调用复用同一批结果；</li>
 *     <li>这些扫描入口都<b>只在服务端</b>被调用（基础版的扫描本身就在服务端）。</li>
 * </ul>
 */
public interface AreaScanHook {
    /**
     * 所有区域 AABB 的<b>总并集</b>（{@code null} = 没有任何区域 ⇒ 不查）。
     *
     * <p><b>第 121 轮 C-7 起不再直接用于实体查询</b>：跨度大的并集（例如两块相隔 1000 格区域）
     * 会让 {@code getEntitiesOfClass} 遍历中间所有空区块。实体查询请用
     * {@link #entityQueryAreas()}（按簇拆分）；本方法保留为「总范围」的诊断 / 兼容入口。</p>
     */
    @Nullable
    AABB entityArea();

    /** 该坐标是否落在<b>任一</b>区域内（绝对坐标）。 */
    boolean contains(BlockPos pos);

    /**
     * <b>实体查询用</b>包围盒：在 {@link #entityArea()} 的基础上<b>向上扩 1 格</b>。
     *
     * <p><b>为什么必须向下扩（第 101 轮修掉的 P0 根因）</b>：{@code getEntitiesOfClass} 用的是
     * 「实体 AABB 与查询盒是否相交」，而 {@code Area#toAabb()} 的上界是 {@code maxY + 1}
     * （闭区间格的惯例）。一件掉落物<b>落在区域底板上</b>时，其实体 y 正好等于底板顶面
     * （底板 y=63 ⇒ 实体 AABB 下界 = 64.0，查询盒上界 = 63 + 1 = 64.0）—— 两者<b>极限相切不相交</b>，
     * 于是整片地板上的掉落物在<b>查询阶段就被丢光了</b>，后面的标记匹配判定根本没被执行。
     * 「玩家把两个角都点在地上的 1 格高区域」是最常见的画法，现象就是「区域画得好好的，
     * 流体能吸、掉落物一个都吸不进来」。<b>向上</b>扩 1 格后，底板上的实体落在查询盒内，
     * 再由 {@link #containsEntity(Entity)} 做精确过滤。</p>
     * <p><b>方向必须是向上</b>（这里踩过一次）：实体站在方块顶面时 {@code position.y = 方块顶面}，
     * 它的 AABB 落在地板方块 AABB 的<b>上方</b>；向下扩只会把下界推得更低，上界不变，
     * 依旧与实体 AABB 相切不相交。只扩 1 格、只向上，避免把「区域外的地板」也吸进来。</p>
     */
    @Nullable
    default AABB entityQueryArea() {
        final AABB union = entityArea();
        return union == null ? null : union.expandTowards(0.0D, 1.0D, 0.0D);
    }

    /**
     * <b>实体查询用的全部包围盒</b>（第 121 轮 C-7：按区域簇拆分，每簇一个盒）。
     *
     * <p><b>为什么不再用「总并集」一次查</b>：{@code getEntitiesOfClass} 的代价主要在遍历盒内
     * <b>所有区块段</b>的实体表。两块区域相隔 1000 格时总并集跨度 1000+ 格，中间 99% 是空区块，
     * 每 tick 两次实体查询（掉落物 + 经验球）就要白白扫过上千个区块段；按簇拆开后，
     * 代价只与「玩家实际画出来的地」成正比，而不是与「两块地之间的距离」成正比。</p>
     *
     * <p><b>实现契约</b>：默认实现 = 单盒（等价于 {@link #entityQueryArea()}），旧实现 / 只有一个区域
     * 时行为逐字不变；高级版 {@code AreaCursor} 覆写成每簇一个盒（区域全相邻 ⇒ 仍然 1 个盒）。
     * <b>盒之间可以相交</b>（贪心聚类不保证簇盒互斥）⇒ 调用方必须用集合去重后再判重 / 吸收。
     * 返回空列表 = 没有任何区域 ⇒ 不查询。</p>
     */
    default List<AABB> entityQueryAreas() {
        final AABB single = entityQueryArea();
        return single == null ? List.of() : List.of(single);
    }

    /**
     * 实体是否算「在区域内」：它<b>占用的格</b> {@code blockPosition()}、它<b>站的格</b>
     * {@code below()}、以及它被卡时所在的上方格 {@code above()} 任一命中即算。
     *
     * <p>为什么不是只看 {@code blockPosition()}：掉落物 / 经验球静止时实体坐标就在方块顶面，
     * 整数格坐标会落到<b>底板上一格</b>；只比整数格会把「明明在范围内」的实体判到范围外
     * （第 101 轮 P0 根因之二）。向上只给 1 格，避免把「范围外的地板」也吸进来。</p>
     */
    default boolean containsEntity(final Entity entity) {
        if (entity == null) {
            return false;
        }
        final BlockPos pos = entity.blockPosition();
        return contains(pos) || contains(pos.below()) || contains(pos.above());
    }

    /**
     * 从游标处继续扫描，最多查询 {@code budget} 个方块位次，返回其中的流体源坐标。
     * @param level    服务端世界
     * @param budget   本 tick 剩余预算（调用方给上限；实现还会按自己的每 tick 硬上限再夹一次）
     * @param isSource 该坐标是否为「可抽取的流体源」（由基础版提供，避免判定逻辑写两份）
     */
    List<BlockPos> scanFluidWindow(Level level, int budget, Predicate<BlockPos> isSource);

    /** 当前参与扫描的区域个数（界面 / 诊断用）。 */
    int areaCount();

    /** 当前参与扫描的区域总体积（格，界面 / 诊断用）。 */
    long totalVolume();
}
