package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCableCuts;
import cretae.cookiewyq.rs_create_compat.support.RsccSeamPick;
import cretae.cookiewyq.rs_create_compat.support.RsccWireBlocks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * 「机械动力扳手分离 RS 线缆」的<b>连接 / 断开预览叠加层</b>（纯客户端，服务端权威）。
 *
 * <p><b>为什么需要它（用户要求）</b>：扳手切连接是「右键即生效」，玩家在按下去之前无从知道
 * 这一下会作用在<b>哪一处</b>、是「断开」还是「恢复」。这里在准星指向的目标上叠一层半透明色块，
 * 把「将会发生什么」提前画出来：<b>绿色 = 这一次会把连接恢复（连上）；红色 = 这一次会把连接断开</b>。</p>
 *
 * <p><b>复用了什么、没造什么新东西</b>：</p>
 * <ul>
 *     <li>判定完全不重写：档位取 {@link RsccCableCuts#modeFor(Level)}，该处当前是否已断开取
 *     {@link RsccCableCuts#isDisconnected(Level, BlockPos, Direction)} —— 与
 *     {@code RsccWrenchCableInteraction} 的服务端结算<b>同一个入口</b>，因此预览与服务端结果必然一致
 *     （客户端只读镜像，绝不改状态）；</li>
 *     <li>渲染不新增任何贴图：用 Java 画一个半透明的立方体（{@code RenderType#debugFilledBox()}，
 *     顶点格式 {@code POSITION_COLOR} + 半透明混合），<b>不碰任何 GUI 背景贴图</b>；</li>
 *     <li>几何位置：{@code SEAM} 档位把色块画成跨在「两个方块之间」的一条连接束（接缝处），
 *     {@code FACE} 档位把色块画成贴在「被点击的那个面」上的一块薄板 —— 一眼就能看出作用于哪一处。</li>
 * </ul>
 *
 * <p><b>什么时候不画</b>：档位为 {@code off} / 没拿扳手 / 潜行（这些情况下服务端根本不接管）/
 * 目标不是 RS 线缆类方块 / 该处右键本来就什么都不会发生（{@code SEAM} 档位且当前既没连接也没被断开，
 * 服务端会回 {@code INVALID}）。</p>
 *
 * <p><b>「状态落定后消失」怎么实现</b>：叠加层是「按下这一下会怎样」的实时预览；一旦玩家真的按下去
 * （客户端同一次右键事件），状态就已经落定，此处立刻把预览隐去 {@value #HIDE_MILLIS} 毫秒 ——
 * 于是不会残留一个「已经作废的预告」，之后仍悬停会重新画出<b>下一步</b>（颜色/位置自然翻转）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class CableCutPreviewOverlay {
    /** 半透明叠加层的不透明度。 */
    private static final float ALPHA = 0.35F;
    /** 「将连接（恢复）」的叠加色：绿。 */
    private static final float[] CONNECT_COLOR = {0.20F, 0.95F, 0.35F};
    /** 「将断开」的叠加色：红。 */
    private static final float[] CUT_COLOR = {0.95F, 0.20F, 0.20F};
    /**
     * 接缝束沿法线<b>各伸进两侧方块</b>的半长（格）。
     * <p>取 0.35 而不是「整格」：断开点就是两根线缆之间那一段接缝，束体应当骑在缝上、
     * 一半落在本方格里、一半落在邻方格里 —— 一眼就能看出「断的是这两格之间这一处」，
     * 而不是「这一整块要被拆掉」（用户反馈旧预览「很诡异」的根因：束体 1.7 格长 + 整格外框）。
     */
    private static final double SEAM_HALF = 0.35D;
    /** 状态落定后预览隐藏的时长（毫秒）。 */
    private static final long HIDE_MILLIS = 300L;

    /** 本次右键（状态落定）之后到该时刻为止不画预览（{@link System#nanoTime()} 基准）。 */
    private static volatile long hiddenUntilNanos = Long.MIN_VALUE;

    private CableCutPreviewOverlay() {
    }

    /**
     * 玩家按下右键（真正会触发切换的那一次）→ 状态即将落定，先收起当前预览。
     * <p>挂在与 {@code RsccWrenchCableInteraction} 同一个事件上（该事件双端都会触发）；
     * 这里只记录时间戳，双端一致地不动任何状态。</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() && takeover(event.getLevel(), event.getEntity(),
            event.getPos(), event.getItemStack())) {
            hiddenUntilNanos = System.nanoTime() + HIDE_MILLIS * 1_000_000L;
        }
    }

    /** 世界渲染阶段（半透明方块之后）画预览叠加层。 */
    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (System.nanoTime() < hiddenUntilNanos) {
            return; // 刚按过：状态已落定，预览收起
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Level level = minecraft.level;
        final Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        final RsccCableCuts.Mode mode = RsccCableCuts.modeFor(level);
        if (mode == RsccCableCuts.Mode.OFF) {
            return;
        }
        if (!(minecraft.hitResult instanceof final BlockHitResult hit)) {
            return;
        }
        final BlockPos pos = hit.getBlockPos();
        if (!RsccWireBlocks.isWire(level.getBlockState(pos))
            || !takeover(level, player, pos, player.getMainHandItem())) {
            return;
        }
        // 要切的缝与「会切还是恢复」都必须与服务端一致：
        // 选缝走同一个 RsccSeamPick（用户报的「朝正东看却选了北/南那道缝」就是这里不同源导致的），
        // 准入走同一个 RsccCableCuts#canToggleSeam / #canCutSeam。
        final Direction target = mode == RsccCableCuts.Mode.SEAM
            ? RsccSeamPick.pick(level, pos, player.getLookAngle(), hit.getLocation())
            : hit.getDirection();
        if (target == null) {
            return; // 没有任何可切的缝：服务端也只会给一句提示，这里不画误导性的预览
        }
        final Boolean willCut = predictWillCut(level, pos, target, mode);
        if (willCut == null) {
            return; // 该处右键什么都不会发生（服务端会判 INVALID）：不画误导性的预览
        }
        if (mode == RsccCableCuts.Mode.SEAM) {
            // 接缝档位：细束骑在「两根线缆之间那道缝」上，外框也贴着这道束（不再画整格外框）
            draw(minecraft, event, pos, seamShape(target), willCut ? CUT_COLOR : CONNECT_COLOR, false);
            return;
        }
        draw(minecraft, event, pos, faceShape(target), willCut ? CUT_COLOR : CONNECT_COLOR, true);
    }

    /**
     * 这次右键是否会被「扳手分离线缆」接管（与服务端 {@code RsccWrenchCableInteraction} 同一套前置条件）。
     */
    private static boolean takeover(final Level level, final Player player, final BlockPos pos,
                                    final net.minecraft.world.item.ItemStack stack) {
        if (RsccCableCuts.modeFor(level) == RsccCableCuts.Mode.OFF) {
            return false;
        }
        if (!player.mayBuild() || player.isShiftKeyDown()) {
            return false;
        }
        if (!stack.is(Tags.Items.TOOLS_WRENCH) && !player.getOffhandItem().is(Tags.Items.TOOLS_WRENCH)) {
            return false;
        }
        return RsccWireBlocks.isWire(level.getBlockState(pos));
    }

    /**
     * 预判这一次右键的结果：{@code true} = 将断开，{@code false} = 将恢复，{@code null} = 不会发生任何事。
     *
     * <p><b>与服务端完全同源</b>：已经断开的按下去就是恢复（{@code RsccCableCuts#isDisconnected}），
     * {@code FACE} 档位恒可切换（先关面，邻块再放上来也不自动连），{@code SEAM} 档位则直接问
     * {@link RsccCableCuts#canCutSeam} —— 那正是服务端 {@code toggleSeam} 用的同一句准入
     * （两条准入：有连接臂、或两侧都是线缆族）。此处不再自己抄一份「有没有臂 / 是不是线缆对」，
     * 因此「预览说能断、服务端回 INVALID」这种漂移在结构上不可能发生。</p>
     */
    @org.jetbrains.annotations.Nullable
    private static Boolean predictWillCut(final Level level, final BlockPos pos, final Direction face,
                                         final RsccCableCuts.Mode mode) {
        if (RsccCableCuts.isDisconnected(level, pos, face)) {
            return Boolean.FALSE;
        }
        if (mode == RsccCableCuts.Mode.FACE) {
            return Boolean.TRUE;
        }
        return RsccCableCuts.canCutSeam(level, pos, face) ? Boolean.TRUE : null;
    }

    /**
     * {@code SEAM} 档位的几何：<b>骑在接缝上的一条细束</b>。
     * <p>断开点 = 两根线缆之间那一段接缝（两个方块中间那个平面），因此沿命中面法线<b>各伸进两侧方块
     * {@value #SEAM_HALF} 格</b>（合计 0.7 格），另外两轴取 0.30~0.70 —— 束体一半落在本方格、一半落在
     * 邻方格里，几何中心正好压在缝的平面上，看起来就是「夹在两根线中间」。</p>
     */
    private static VoxelShape seamShape(final Direction face) {
        final int axis = face.getAxis().ordinal();
        final boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        final double[] min = {0.30D, 0.30D, 0.30D};
        final double[] max = {0.70D, 0.70D, 0.70D};
        // 接缝平面的本地坐标：UP/EAST/SOUTH 命中面在 1.0，DOWN/WEST/NORTH 在 0.0
        min[axis] = (positive ? 1.0D : 0.0D) - SEAM_HALF;
        max[axis] = (positive ? 1.0D : 0.0D) + SEAM_HALF;
        return Shapes.box(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /**
     * {@code FACE} 档位的几何：在被点击的那个面上贴一块薄板（法线方向 0.06 格厚），
     * 其余两轴留出边框 —— 明确指「就是这个面」。
     */
    private static VoxelShape faceShape(final Direction face) {
        final int axis = face.getAxis().ordinal();
        final boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        final double[] min = {0.10D, 0.10D, 0.10D};
        final double[] max = {0.90D, 0.90D, 0.90D};
        min[axis] = positive ? 0.98D : -0.06D;
        max[axis] = positive ? 1.06D : 0.02D;
        return Shapes.box(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /**
     * 画一层半透明叠加层：用 {@code debug_filled_box}（顶点格式 {@code POSITION_COLOR}、半透明混合、
     * 无剔除）逐面填出立方体；顶点按三角形带的「之」字形顺序发出，因此任意缠绕方向都能铺满该面。
     */
    private static void draw(final Minecraft minecraft, final RenderLevelStageEvent event,
                             final BlockPos pos, final VoxelShape shape, final float[] color,
                             final boolean outlineWholeCell) {
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        drawBoxes(event.getPoseStack(), buffers, event.getCamera().getPosition(),
            List.of(pos), shape, color, ALPHA, outlineWholeCell);
    }

    /**
     * <b>半透明方块叠加层的公共绘制入口</b>（本类与批 3 的「可达区域」叠加层共用这唯一一份实现）：
     * 对每个坐标按给定形状画一层半透明立方体 + 一层 {@code LevelRenderer#renderLineBox} 描边。
     *
     * <p>为什么抽成 public 静态方法：用户要求「可达区域」复用既有的半透明渲染，而不是再写一套；
     * 顶点顺序、渲染类型、描边方式这些细节一旦抄第二遍就会慢慢漂移。</p>
     *
     * <h2>为什么必须「取一个渲染类型 → 写完 → 立刻 endBatch」而不能同时持有两个</h2>
     * <p>{@code RenderType.debugFilledBox()} 与 {@code RenderType.lines()} <b>都不在</b>
     * {@code RenderBuffers} 登记的那批「固定缓冲」渲染类型里，因此两者共用同一个
     * {@code ByteBufferBuilder}（共享缓冲）。而 {@code MultiBufferSource.BufferSource#getBuffer} 的写法是：
     * 当请求的渲染类型与当前 {@code lastSharedType} 不同时，<b>先默默把 lastSharedType 那一个 end 掉</b>
     * （见 {@code BufferSource#getBuffer} 里 {@code this.endBatch(this.lastSharedType)} 那一段）。
     * <p>于是「先取 debugFilledBox 的 consumer、再取 lines 的 consumer、然后才往 debugFilledBox 里写顶点」
     * 这个顺序必然崩溃：取 lines 的那一刻已经把 debugFilledBox 的 {@code BufferBuilder} 结束掉了
     * （{@code building = false}），之后 {@code addVertex} 直接抛
     * {@code IllegalStateException: Not building!}（实机崩溃报告 crash-2026-09-25_00.45.22 即此）。</p>
     * <p>修法：把两类渲染彻底串行化，并且用 {@code try/finally} 保证<b>即使中途抛异常也收尾</b>
     * —— 漏掉收尾会把「半成品 builder」留在共享缓冲里污染下一帧，所以异常路径必须一并覆盖。</p>
     *
     * @param camera 相机世界坐标（顶点需要相机相对坐标）
     * @param cells  要标记的方块坐标（空表则什么都不画）
     * @param shape  每个方块内的相对形状（单位立方体 / 贴合某面的薄板等）
     * @param color  RGB（0..1）
     * @param alpha  不透明度（0..1）
     * @param outlineWholeCell 描边是「整格的立方体外框」（可达区域等整块标记用）还是
     *        「给定形状自己的外框」（接缝细束用 —— 必须贴着那道缝，不能画成整格）
     */
    public static void drawBoxes(final PoseStack poseStack, final MultiBufferSource.BufferSource buffers,
                                final Vec3 camera, final java.util.Collection<BlockPos> cells,
                                final VoxelShape shape, final float[] color, final float alpha,
                                final boolean outlineWholeCell) {
        if (cells == null || cells.isEmpty() || shape == null) {
            return;
        }
        // 先把「相机相对坐标」的盒子算好（两类渲染各要遍历一遍，坐标只算一次）。
        final List<net.minecraft.world.phys.AABB> filled = new java.util.ArrayList<>(cells.size());
        final List<net.minecraft.world.phys.AABB> outlines = new java.util.ArrayList<>(cells.size());
        for (final BlockPos pos : cells) {
            if (pos == null) {
                continue;
            }
            final double x = pos.getX() - camera.x;
            final double y = pos.getY() - camera.y;
            final double z = pos.getZ() - camera.z;
            for (final net.minecraft.world.phys.AABB box : shape.toAabbs()) {
                filled.add(box.move(x, y, z));
                // 外框：整块标记恒取整格；接缝细束取束体自己（否则会画出一格与束体错位的框）
                outlines.add(outlineWholeCell
                    ? new net.minecraft.world.phys.AABB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)
                    : box.move(x, y, z));
            }
        }
        final PoseStack.Pose pose = poseStack.last();
        // ① 半透明填充（debug_filled_box，走共享缓冲）：写完立刻收尾，绝不跨到下一类渲染之后再写
        try {
            final VertexConsumer consumer = buffers.getBuffer(RenderType.debugFilledBox());
            for (final net.minecraft.world.phys.AABB box : filled) {
                box(consumer, pose, box, color, alpha);
            }
        } finally {
            buffers.endBatch(RenderType.debugFilledBox());
        }
        // ② 描边（lines，同样走共享缓冲）：同样独立取、独立收尾
        try {
            final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (final net.minecraft.world.phys.AABB box : outlines) {
                LevelRenderer.renderLineBox(poseStack, lines, box,
                    color[0], color[1], color[2], Math.min(1.0F, alpha + 0.5F));
            }
        } finally {
            buffers.endBatch(RenderType.lines());
        }
    }

    /**
     * 整格标记的便捷重载（外框画整格立方体）：可达区域 / 总线干扰等「标出一整块」的叠加层用它。
     * <p>保留这个签名是为了让既有调用点一行都不用改（接缝细束自己走带 {@code outlineWholeCell}
     * 的重载，传 {@code false}）。</p>
     */
    public static void drawBoxes(final PoseStack poseStack, final MultiBufferSource.BufferSource buffers,
                                final Vec3 camera, final java.util.Collection<BlockPos> cells,
                                final VoxelShape shape, final float[] color, final float alpha) {
        drawBoxes(poseStack, buffers, camera, cells, shape, color, alpha, true);
    }

    /** 单位立方体形状（整块方块；供叠加层标出「这个方块被涉及」）。 */
    public static VoxelShape unitShape() {
        return Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    }

    /**
     * 把一个 AABB 的六个面按三角形带顺序喂给顶点消费者（顶点顺序与缠绕无关，因为该渲染类型已关闭剔除）。
     * <p>传入的 {@code box} 已是<b>相机相对坐标</b>（调用方先算好），因此这里不再有任何坐标偏移。</p>
     */
    private static void box(final VertexConsumer consumer, final PoseStack.Pose pose,
                            final net.minecraft.world.phys.AABB box, final float[] color,
                            final float alpha) {
        final float x0 = (float) box.minX;
        final float x1 = (float) box.maxX;
        final float y0 = (float) box.minY;
        final float y1 = (float) box.maxY;
        final float z0 = (float) box.minZ;
        final float z1 = (float) box.maxZ;
        // 注意：debug_filled_box 是 TRIANGLE_STRIP，必须一次调用内 4 顶点成一条带（两个三角形）
        quad(consumer, pose, color, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z0, x0, y1, z1); // 西面
        quad(consumer, pose, color, alpha, x1, y0, z0, x1, y0, z1, x1, y1, z0, x1, y1, z1); // 东面
        quad(consumer, pose, color, alpha, x0, y0, z0, x1, y0, z0, x0, y0, z1, x1, y0, z1); // 下面
        quad(consumer, pose, color, alpha, x0, y1, z0, x1, y1, z0, x0, y1, z1, x1, y1, z1); // 上面
        quad(consumer, pose, color, alpha, x0, y0, z0, x1, y0, z0, x0, y1, z0, x1, y1, z0); // 北面
        quad(consumer, pose, color, alpha, x0, y0, z1, x1, y0, z1, x0, y1, z1, x1, y1, z1); // 南面
    }

    /** 一条三角形带 = 4 个顶点（2 个三角形）铺满一个矩形面。 */
    private static void quad(final VertexConsumer consumer, final PoseStack.Pose pose, final float[] color,
                             final float alpha,
                             final float ax, final float ay, final float az,
                             final float bx, final float by, final float bz,
                             final float cx, final float cy, final float cz,
                             final float dx, final float dy, final float dz) {
        vertex(consumer, pose, color, alpha, ax, ay, az);
        vertex(consumer, pose, color, alpha, bx, by, bz);
        vertex(consumer, pose, color, alpha, cx, cy, cz);
        vertex(consumer, pose, color, alpha, dx, dy, dz);
    }

    private static void vertex(final VertexConsumer consumer, final PoseStack.Pose pose, final float[] color,
                               final float alpha, final float x, final float y, final float z) {
        consumer.addVertex(pose, x, y, z).setColor(color[0], color[1], color[2], alpha);
    }
}
