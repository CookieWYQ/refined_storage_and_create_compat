package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccSheaths;
import cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Set;

/**
 * 「套壳」的客户端表现：<b>把用户自绘的框架模型套在被套住的那一格管道外面</b>
 * ＋ <b>把选中框画成整格</b>。
 *
 * <h2>为什么画在这里而不是做成一个方块</h2>
 * <p>那一格里放的<b>仍然是原来那根线缆 / 管道</b>（{@link RsccSheaths} 的坐标标记，见该类注释），
 * 所以本方块的模型不能进区块网格（区块网格是烘焙缓存的，套 / 拆必须立刻生效）。
 * 由本类在渲染阶段按帧读取客户端镜像，直接对那一格画一层外套模型 —— 与
 * {@link SeparationFrameRenderer} 走同一条「方块模型 1:1 画在方块原点」的路数，
 * 因此用户改自己的 {@code models/block/separation_frame.json} 不需要改任何代码。</p>
 *
 * <h2>渲染类型：半透明（用户要求）</h2>
 * <p>外套用 {@link RenderType#translucent()}：用户的框架贴图是 ARGB 半透明贴图，
 * {@code cutout} 会把带 alpha 的像素裁成「完全不透明 / 完全透明」，玻璃质感会丢。
 * <b>不透视的保证</b>：这里每一帧都把模型完完整整画出来（不是「不渲染」），
 * 半透明只是像素带 alpha 的表现效果；被包裹的管道照旧由它自己的渲染层（RS 线缆 / Create 管道的
 * solid / cutout）正常画出，我们既不改它的渲染类型，也不覆盖它那一格。</p>
 * <p><b>排序与深度</b>：{@code translucent()} 本身由游戏在建 mesh 时按「从远到近」排序，
 * 并且<b>不写深度</b>；我们只负责把顶点喂进它的缓冲，不碰 {@code blend} 状态、不动深度掩码，
 * 所以不会出现「半透明面互相遮挡导致破面」的观感问题。</p>
 * <p><b>坐标口径</b>：本阶段（{@code AFTER_TRANSLUCENT_BLOCKS}）事件给的姿态栈是<b>恒等</b>的
 * （NeoForge 在该阶段传 null，事件内部换成一个新的 {@code PoseStack}），
 * 而着色器用的 model-view 只有相机旋转，所以世界几何一律以「相机相对坐标」提交 ——
 * 这与原版 {@code LevelRenderer#renderHitOutline} 自己减相机坐标的做法完全一致，
 * 也与 {@link CableCutPreviewOverlay} 同一套口径。</p>
 *
 * <h2>选中框整格（用户要求「框和正常方块一样大」）</h2>
 * <p>直接挂 {@link RenderHighlightEvent.Block}：准星指着被套住的格子时，取消原版那个
 * 「贴着管道形状」的小框，改成整格 16×16×16 的框。注意这<b>只改外观</b> ——
 * 被套住的仍然是管道本身，碰撞体也仍然是管道自己的（不额外添加碰撞）。</p>
 *
 * <h2>性能</h2>
 * <p>只在「手持框架 / 护目镜 + 快捷键」时才画壳（与 {@link SeparationFrameVisibility} 同一套显示规则，
 * 与「分隔框架方块」保持一致的观感）。遍历时先按距离粗筛（{@value #MAX_DISTANCE} 格内），
 * 再校验那一格确实还是管道 / 线缆，最后最多画 {@value #MAX_CELLS} 个，防止极端情况下拖慢帧率。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class SheathRenderer {
    /** 单帧最多绘制的套壳数（超出部分本帧不画，下一帧从集合开头重新遍历，不会长期漏画）。 */
    private static final int MAX_CELLS = 128;
    /** 超过这个距离（格）的套壳不画：近似「视野内才画」，避免远处大量格子白跑一遍。 */
    private static final double MAX_DISTANCE = 64.0D;

    private SheathRenderer() {
    }

    /** 世界渲染阶段（半透明方块之后）画外壳模型。 */
    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // 与「分隔框架方块」同一套显示规则：默认不显示，手持框架或护目镜 + 快捷键才显示
        if (!SeparationFrameVisibility.shouldRender()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Level level = minecraft.level;
        if (level == null) {
            return;
        }
        final Set<BlockPos> cells = RsccSheaths.mirroredPositions();
        if (cells.isEmpty()) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        final ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        final PoseStack poseStack = event.getPoseStack();
        final BakedModel model = dispatcher.getBlockModel(frameState());
        int drawn = 0;
        try {
            for (final BlockPos pos : cells) {
                if (drawn >= MAX_CELLS) {
                    break;
                }
                if (camera.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE * MAX_DISTANCE) {
                    continue;
                }
                if (!level.isLoaded(pos)) {
                    continue;
                }
                // 防御：只有那一格确实还是「可套壳方块」时才画（镜像短暂过期时也不会在空气外画壳）
                final BlockState hostState = level.getBlockState(pos);
                if (!SeparationFrameGuard.isSheathable(level, pos, hostState)) {
                    continue;
                }
                drawShell(level, renderer, buffers, poseStack, camera, pos, model);
                drawn++;
            }
        } finally {
            // 立刻收尾：绝不让「取出来的渲染类型缓冲」跨到别的渲染类型之后（会抛 Not building!），
            // 异常路径也要收，否则半成品 builder 会污染下一帧。
            buffers.endBatch(RenderType.translucent());
            buffers.endBatch(RenderType.glint());
        }
    }

    /** 把外套模型按 1:1 画在方块原点（无限版再叠一层附魔光泽）。 */
    private static void drawShell(final Level level, final ModelBlockRenderer renderer,
                                  final MultiBufferSource.BufferSource buffers, final PoseStack poseStack,
                                  final Vec3 camera, final BlockPos pos, final BakedModel model) {
        final BlockState frameState = frameState();
        final int light = LevelRenderer.getLightColor(level, pos);
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        // 主体：半透明（用户贴图是 ARGB 半透明）
        renderer.renderModel(poseStack.last(), buffers.getBuffer(RenderType.translucent()), frameState, model,
            1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
        if (RsccSheaths.mirroredInfinite(pos)) {
            // 无限版：同一份模型再叠一层 glint（与分隔框架方块那一层光泽完全一致的做法）
            renderer.renderModel(poseStack.last(), buffers.getBuffer(RenderType.glint()), frameState, model,
                1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }

    /**
     * 外套用的方块状态：取普通版即可 —— 无限版的方块模型本来就是
     * {@code {"parent": "rs_create_compat:block/separation_frame"}}，两者外观完全相同，
     * 区别只在「要不要再叠一层 glint」（见 {@link #drawShell}）。
     */
    private static BlockState frameState() {
        return RS_Create_Compat.SEPARATION_FRAME_BLOCK.get().defaultBlockState();
    }

    /**
     * 选中框：准星指着被套住的格子时，把原版那个「贴着管道形状」的小框换成<b>整格</b>的框
     * （用户要求「那个框应该和正常方块一样大」）。
     *
     * <p>实现上取消原版绘制后自己画一个整格线框：坐标口径与 {@code LevelRenderer#renderHitOutline}
     * 一致（方块坐标减去相机坐标、姿态栈恒等），因此框的位置与原来的框完全重合，
     * 只是尺寸从「管道外形」变成「整格」。线条渲染沿用
     * {@code LevelRenderer#renderLineBox} 与原版选中框的黑色 0.4 透明度。</p>
     */
    @SubscribeEvent
    public static void onHighlight(final RenderHighlightEvent.Block event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Level level = minecraft.level;
        if (level == null) {
            return;
        }
        final BlockPos pos = event.getTarget().getBlockPos();
        if (!RsccSheaths.isSheathed(level, pos)) {
            return;
        }
        event.setCanceled(true);
        final Vec3 camera = event.getCamera().getPosition();
        final AABB box = new AABB(pos).move(-camera.x, -camera.y, -camera.z).inflate(0.002D);
        final VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(event.getPoseStack(), lines, box, 0.0F, 0.0F, 0.0F, 0.4F);
        if (event.getMultiBufferSource() instanceof final MultiBufferSource.BufferSource buffers) {
            // lines 属于共享缓冲：取出来画完立刻收尾，避免与后续渲染类型互相踩（见 CableCutPreviewOverlay）
            buffers.endBatch(RenderType.lines());
        }
    }
}
