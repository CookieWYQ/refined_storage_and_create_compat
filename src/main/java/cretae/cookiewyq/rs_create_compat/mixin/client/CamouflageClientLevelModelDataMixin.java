package cretae.cookiewyq.rs_create_compat.mixin.client;

import cretae.cookiewyq.rs_create_compat.client.model.CamouflageModelData;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflageAttachment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「这一格裹着什么」并进 {@link ClientLevel#getModelData(BlockPos)} 的返回值 ——
 * 让<b>所有</b>用「客户端模型数据」驱动渲染的路径都能看到伪装外壳。
 *
 * <h2>为什么必须补这一处（本轮修「手动逐个套上也看不到外观」的根因）</h2>
 * <p>外壳的几何只能从 {@code BakedModel#getQuads(..., ModelData, RenderType)} 的模型数据里取。
 * 区块网格（{@code SectionCompiler}）会先调用
 * {@code BakedModel#getModelData(world, pos, state, 方块实体模型数据)}，因此
 * {@code CamouflageShellModel#gatherModelData} 有机会现问方块实体 —— 那一支是通的。</p>
 * <p>但<b>物理化模组（Sable）的「单格」渲染分支不走那条路</b>：它把
 * {@code ClientLevel#getModelData(pos)} 的返回值<b>直接</b>交给
 * {@code ModelBlockRenderer#tesselateWithoutAO}，<b>从不调用</b> {@code BakedModel#getModelData}。
 * 于是模型数据里没有伪装字段 → {@code getQuads} 产出<b>零个</b>外壳四元组 → 表现就是
 * 「套上去了，但外观不渲染」。实测证据（{@code javap -c} 于
 * {@code sable-neoforge-1.21.1-2.0.5.jar} 的
 * {@code dev/ryanhcode/sable/neoforge/platform/SableSubLevelRenderPlatformImpl}）：</p>
 * <pre>
 *   invokevirtual  ClientLevel.getModelData(BlockPos)ModelData;
 *   invokevirtual  ModelBlockRenderer.tesselateWithoutAO(..., ModelData, RenderType)V
 * </pre>
 * <p>因此这里在 {@code ClientLevel#getModelData} 的返回处把<b>同一份数据附件</b>
 * （{@link RsccCamouflageAttachment#CAMOUFLAGE}）并进模型数据。数据来源仍然只有一条
 * （服务端写、NeoForge 同步的附件），本钩子不保存任何状态 —— 与
 * {@code CamouflageShellModel} 里那次「现问方块实体」逐字同源。</p>
 *
 * <h2>为什么是 {@code @Inject(RETURN)} 而不是重写整个方法</h2>
 * <p>原方法读的是 NeoForge 的 {@code ModelDataManager} 缓存（RS 线缆的
 * {@code CABLE_CONNECTIONS} 等别的属性都在里面），必须<b>原样保留</b>；
 * 这里只做「并上一项」，用 {@link CamouflageModelData#withCamo} 复制，
 * 绝不覆盖、绝不清空别人的属性。</p>
 *
 * <p><b>只挂在 client 段</b>（{@code rs_create_compat.mixins.json}）：专用服务端不加载本类，
 * 因此不会因为引用 {@code net.minecraft.client.*} 而在服务端启动时炸掉。</p>
 */
@Mixin(ClientLevel.class)
public abstract class CamouflageClientLevelModelDataMixin {
    /**
     * 客户端模型数据 = 原来的那份 ∪ 本格的伪装记录。
     *
     * <p>没被裹住的格（绝大多数）在这里只多一次 {@code getExistingDataOrNull}：读不到就原样返回，
     * 连一次 {@code ModelData} 复制都不做。</p>
     */
    @Inject(method = "getModelData(Lnet/minecraft/core/BlockPos;)"
        + "Lnet/neoforged/neoforge/client/model/data/ModelData;", at = @At("RETURN"), cancellable = true)
    private void rscc$appendCamouflageModelData(final BlockPos pos,
                                                 final CallbackInfoReturnable<ModelData> cir) {
        final BlockEntity be = ((ClientLevel) (Object) this).getBlockEntity(pos);
        if (be == null) {
            return;
        }
        // 取数一律走 getExistingDataOrNull：不为了渲染去给每一根线缆创建一份空附件
        final RsccCamouflage.Camo camo =
            be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
        if (camo == null) {
            return;
        }
        cir.setReturnValue(CamouflageModelData.withCamo(cir.getReturnValue(), camo));
    }
}
