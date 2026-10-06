package cretae.cookiewyq.rs_create_compat.mixin.block;

import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import cretae.cookiewyq.rs_create_compat.support.RsccShapeQueryGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「伪装框架」的<b>碰撞 / 选中形状</b>接管点：外壳在时，那一格按<b>完整方块</b>参与碰撞与选中。
 *
 * <h2>用户第 3 条：套上框架后应该有完整方块的碰撞体积</h2>
 * <p>用户原话：「需要给线缆 / 流体管道提供额外的碰撞体积：套上伪装框架后，该格应有完整方块的
 * 碰撞体积（现在似乎没有 / 是细管形状）」。这是必然的：被裹的那一格放<b>仍然是 RS 的线缆 / Create 的
 * 流体管道</b>，它们的形状是「4/16 的细芯 + 若干连接臂」，而外面那层外壳是一整格不透明立方体 ——
 * 玩家看到的是一个方块，却撞不上去、准星也常常打不到 —— 因此这里直接把形状换成整格
 * （选中框随之由原版按这一格的形状画出，不再需要任何自定义的选中框渲染）。</p>
 *
 * <h2>为什么挂在 {@code BlockBehaviour.BlockStateBase} 上（唯一闸门）</h2>
 * <p>要覆盖的是「所有可能被裹的方块族」：RS 的线缆 / 输入总线 / 输出总线（{@code CableBlock} /
 * {@code ImporterBlock} / {@code ExporterBlock}，各自都有自己的一份 {@code getShape}），
 * 以及 Create 的流体管道族（{@code FluidPipeBlock} / {@code EncasedPipeBlock} /
 * {@code AxisPipeBlock} / {@code SmartFluidPipeBlock}，四个类各写各的）。逐类写 Mixin 就是五份以上
 * 重复实现，将来任何一族新增变体都会漏；而<b>所有</b>方块状态形状查询最终都要经过
 * {@link BlockBehaviour.BlockStateBase} 的这三个方法（{@code getShape} 决定选中框与准星命中，
 * {@code getCollisionShape} 决定实体碰撞，{@code getVisualShape} 决定视觉形状），
 * 因此这里只挂一处，口径天然唯一。</p>
 *
 * <h2>2026-09-26 崩溃修复：可重入保护（本次唯一的结构性改动）</h2>
 * <p>修复前的 real 崩溃（{@code run/crash-reports/crash-2026-09-26_19.40.30-client.txt}，
 * {@code Description: Bootstrap}，{@code StackOverflowError}）是一条精确的环，四个帧一一对应：</p>
 * <ol>
 *     <li>{@code BlockStateBase#getCollisionShape(BlockGetter, BlockPos)}（两参重载）= 本类
 *     {@code rscc$fullCollisionWhenCamouflagedCached} 的注入点；</li>
 *     <li>→ {@link RsccCamouflage#overridesShape}（当时的第 159 行）；
 *     → {@code SeparationFrameGuard.isSheathable}（当时的第 155 行
 *     {@code return state.isCollisionShapeFullBlock(level, pos);}）；</li>
 *     <li>→ {@code BlockStateBase#isCollisionShapeFullBlock}：原版在
 *     {@code cache == null}（<b>方块状态烘焙期</b>，或动态外形方块）时转交
 *     {@code BlockBehaviour.isCollisionShapeFullBlock(state, level, pos)}，其实现是
 *     {@code Block.isShapeFullBlock(state.getCollisionShape(level, pos))}；</li>
 *     <li>→ 又回到第 1 帧。每绕一圈压一层栈 → 启动阶段（{@code Bootstrap} 会对每个方块状态执行
 *     {@code BlockStateBase#initCache}，其中 {@code Cache} 构造会读一次碰撞形状，而那一刻
 *     {@code cache} 字段尚未赋值）必然在第一个「非空气 / 非流体 / 可替换、且不是细管族」的方块上炸掉。</li>
 * </ol>
 * <p>因此本类四条注入路径<b>全部</b>加 {@link RsccShapeQueryGuard} 可重入保护：
 * 重入时<b>不设返回值</b>（HEAD 注入 + {@code cancellable} 时不调 {@code cir.setReturnValue}
 * 就等同放行原版），于是环在最内层被切断；复位一律放在 {@code finally}（形状查询会在烘焙、
 * 多线程区块网格等任何路径上执行，漏复位会把整条线程永久锁死）。</p>
 * <p><b>同时</b>我们消除了这条环的根源：{@link RsccCamouflage#overridesShape} 现在只用
 * 「线缆 / 管道族 + 坐标记录」两步廉价短路，<b>不再</b>调用会触发形状查询的
 * {@code SeparationFrameGuard.isSheathable} —— 可重入保护只是兜底，正常路径已经不再重入。</p>
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class CamouflageShapeMixin {
    /** 整格形状（与普通完整方块一致）。 */
    private static final VoxelShape RSCC_FULL_BLOCK = Shapes.block();

    /**
     * {@code getShape} = 选中框 / 准星命中用的形状（{@code ClipContext.Block.OUTLINE}）。
     * <p>取 {@code HEAD} 而不是 {@code RETURN}：命中时直接短路，不必先把线缆的细芯形状算一遍再丢掉。</p>
     */
    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void rscc$fullShapeWhenCamouflaged(final BlockGetter level, final BlockPos pos,
                                               final CollisionContext context,
                                               final CallbackInfoReturnable<VoxelShape> cir) {
        if (!RsccShapeQueryGuard.begin()) {
            return; // 重入：不 cancel，放行原版实现（见类注释「可重入保护」）
        }
        try {
            if (RsccCamouflage.overridesShape(level, pos, (BlockState) (Object) this)) {
                cir.setReturnValue(RSCC_FULL_BLOCK);
            }
        } finally {
            RsccShapeQueryGuard.end();
        }
    }

    /**
     * {@code getCollisionShape} = 实体碰撞用的形状（带 {@link CollisionContext} 的那一份）。
     * <p>实体移动 / 挤压 / 站在上面都走这里，因此「完整方块的碰撞体积」由它保证。</p>
     */
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void rscc$fullCollisionWhenCamouflaged(final BlockGetter level, final BlockPos pos,
                                                   final CollisionContext context,
                                                   final CallbackInfoReturnable<VoxelShape> cir) {
        if (!RsccShapeQueryGuard.begin()) {
            return; // 重入：不 cancel，放行原版实现
        }
        try {
            if (RsccCamouflage.overridesShape(level, pos, (BlockState) (Object) this)) {
                cir.setReturnValue(RSCC_FULL_BLOCK);
            }
        } finally {
            RsccShapeQueryGuard.end();
        }
    }

    /**
     * {@code getCollisionShape} 的两参重载：它走「方块状态自带缓存」那一支
     * （缓存是烘焙期算好的线缆细芯形状，会在三参重载之前直接返回），
     * 因此必须单独接管一次，否则「不传 {@link CollisionContext} 的碰撞查询」仍会得到细管形状。
     *
     * <p><b>它就是 2026-09-26 崩溃的第一帧</b>：方块状态烘焙期 {@code cache} 仍为 {@code null}，
     * 于是原版转三参重载，而三参重载也被我们注入 —— 这里必须同样有可重入保护。</p>
     */
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;"
        + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void rscc$fullCollisionWhenCamouflagedCached(final BlockGetter level, final BlockPos pos,
                                                         final CallbackInfoReturnable<VoxelShape> cir) {
        if (!RsccShapeQueryGuard.begin()) {
            return; // 重入：不 cancel，放行原版实现
        }
        try {
            if (RsccCamouflage.overridesShape(level, pos, (BlockState) (Object) this)) {
                cir.setReturnValue(RSCC_FULL_BLOCK);
            }
        } finally {
            RsccShapeQueryGuard.end();
        }
    }

    /**
     * {@code getVisualShape} = 视觉形状（遮挡剔除 / 光照相关的查询会读它）。
     * <p>它与 {@code getShape} 是两条独立方法（{@code Block#getVisualShape} 默认只是转发到
     * {@code getShape}，但 RS / Create 的线缆类各自覆写了 {@code getShape}，转发目标不是本 Mixin 的
     * 入口），因此这里显式再挂一次，保证三个形状口径完全一致。</p>
     */
    @Inject(method = "getVisualShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void rscc$fullVisualWhenCamouflaged(final BlockGetter level, final BlockPos pos,
                                                final CollisionContext context,
                                                final CallbackInfoReturnable<VoxelShape> cir) {
        if (!RsccShapeQueryGuard.begin()) {
            return; // 重入：不 cancel，放行原版实现
        }
        try {
            if (RsccCamouflage.overridesShape(level, pos, (BlockState) (Object) this)) {
                cir.setReturnValue(RSCC_FULL_BLOCK);
            }
        } finally {
            RsccShapeQueryGuard.end();
        }
    }
}
