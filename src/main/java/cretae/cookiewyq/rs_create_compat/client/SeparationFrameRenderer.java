package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import cretae.cookiewyq.rs_create_compat.block.SeparationFrameBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.SeparationFrameBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 分隔框架的客户端渲染器：<b>每帧</b>问一次 {@link SeparationFrameVisibility}，可见才画。
 *
 * <p><b>为什么用方块实体渲染器而不是方块模型</b>：方块自己在区块网格里是
 * {@code RenderShape.INVISIBLE}（不烘焙、零开销）。显隐取决于「玩家此刻手里拿着什么 /
 * 有没有开护目镜显示」，而区块网格是烘焙后缓存的，改一次要全区块重烘；
 * 方块实体渲染器是逐帧调用的，只有它能做到「换手即显 / 按下快捷键即显」。</p>
 *
 * <p><b>画的是谁的模型</b>：直接把该方块状态的烘焙模型按 1:1 画在方块原点
 * （渲染器被调用时姿态已经平移到方块坐标）。因此用户自行制作的
 * {@code blockstates/*.json → models/block/*.json} 会被原样使用，改模型不需要改代码。</p>
 *
 * <p><b>无限版的附魔光泽</b>：同一份模型再叠一层 {@link RenderType#glint()}
 * （物品附魔用的同一条渲染类型 / 同一张 glint 贴图），于是方块表面有创造级物品那种
 * 流光。选这条而不是「给物品 isFoil」：用户要的是<b>方块</b>上有光泽，
 * 而 isFoil 只作用于物品栏 / 手持的物品图标，放下去的方块不会有任何变化。</p>
 *
 * <p><b>主体用 {@link RenderType#translucent()}（半透明）</b>：用户自绘的
 * {@code textures/block/separation_frame.png} 是 16×16 的 <b>ARGB 半透明</b>贴图，
 * 不能用 {@code cutout}（cutout 会把带 alpha 的像素直接裁成「完全不透明 / 完全透明」，
 * 玻璃般的半透明质感会整体丢失）。半透明渲染由游戏自己按“从远到近”排序透明面，
 * 且该渲染类型<b>不写深度</b>，因此不会出现「半透明面互相遮挡破面」的观感问题；
 * 它与任务 2 的「不透视」也不冲突 —— 透视指的是「里面不渲染、能看穿到底下的方块」，
 * 而这里每一帧都把模型完完整整画了出来，只是像素带 alpha 而已。</p>
 */
public class SeparationFrameRenderer implements BlockEntityRenderer<SeparationFrameBlockEntity> {
    public SeparationFrameRenderer(final BlockEntityRendererProvider.Context context) {
        // 无需任何上下文资源：模型从方块状态直接取
    }

    @Override
    public void render(final SeparationFrameBlockEntity blockEntity, final float partialTick,
                       final PoseStack poseStack, final MultiBufferSource bufferSource,
                       final int packedLight, final int packedOverlay) {
        if (!SeparationFrameVisibility.shouldRender()) {
            return;
        }
        final BlockState state = blockEntity.getBlockState();
        final BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        final BakedModel model = dispatcher.getBlockModel(state);
        final ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        renderer.renderModel(poseStack.last(), bufferSource.getBuffer(RenderType.translucent()), state, model,
            1.0F, 1.0F, 1.0F, packedLight, packedOverlay);
        if (state.getBlock() instanceof SeparationFrameBlock frame && frame.isInfinite()) {
            renderer.renderModel(poseStack.last(), bufferSource.getBuffer(RenderType.glint()), state, model,
                1.0F, 1.0F, 1.0F, packedLight, packedOverlay);
        }
    }
}
