package cretae.cookiewyq.rs_create_compat.client.model;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatModel;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * 伪装框架的渲染模型：把「外壳材质方块自己的模型」原样画出来；<b>没装材质时画自己的不透明兜底立方体</b>。
 *
 * <h2>为什么继承 Create 的 {@link CopycatModel}</h2>
 * <p>伪装框架与伪装板的<b>唯一</b>渲染差别是几何：伪装板是几像素厚的薄板，所以
 * {@code CopycatPanelModel} 要把材质的立方体四元组裁成 1~3 像素的薄片；而伪装框架是
 * <b>整格实心外壳</b>，材质既然也是完整方块，只要原样画材质的立方体就严丝合缝。
 * 除了几何之外的公共部分——外壳材质从哪来（方块实体的模型数据）、
 * 被邻块遮住的面怎么剔、发光材质怎么处理（{@code IS_EMISSIVE_PROPERTY}）、
 * 材质自身的模型数据怎么转发（{@code WRAPPED_DATA_PROPERTY}，连接纹理方块靠它）——
 * 全部继承自 {@link CopycatModel}，与本模组的代码无关，Create 改了我们自动跟随。</p>
 *
 * <p><b>取的是「方块状态」而不是「方块」</b>：材质是 {@link BlockState}，所以外壳会连同它的
 * 朝向、开关等状态一起被渲染（例如朝上的漏斗状方块、点亮的灯），并且 {@code cycleMaterial()}
 * 换朝向时画出来的东西跟着变；</p>
 *
 * <h2>没装材质时的兜底（修「穿模 / 透视」，用户第 7 条）</h2>
 * <p>Create 里「没有材质」用的是占位状态 {@code create:copycat_base}，而那张
 * {@code create:block/copycat_base} 贴图本身是<b>透明</b>的（伪装板 / 伪装阶梯没材质时本来就该看不见）。
 * 伪装框架照抄这条规则就会出现用户描述的问题：<b>里面什么都不画、能直接看穿到底下的方块</b>。</p>
 * <p>修法：判定「没有自定义材质」时，改画<b>本方块自己的模型</b>
 * （{@code models/block/camouflage_frame.json}），于是任何情况下都有一层属于本方块的外观。
 * 兜底模型直接取 {@code originalModel}（本方块状态下烘焙出来的那个模型，也就是方块状态里指向的
 * {@code rs_create_compat:block/camouflage_frame}），因此不需要任何按资源路径的二次查表，
 * 也不可能与「交给 {@link CopycatModel} 处理材质」的那条路径互相递归。</p>
 *
 * <h3>2026-09-25：兜底外观从「实心灰板」改成「框架族镂空框架」（用户第 5 条）</h3>
 * <p>用户原话：「把伪装框架中间那块『未加工黑曜石板』改成『框架本体』的贴图 / 外观，
 * 然后让它顺理成章地也具备断联功能。该不该有断点？」。原来那份兜底模型是一个不透明的整格立方体，
 * 把被裹的线缆<b>完全盖住</b>：断开某一道缝之后玩家在外面什么都看不见，也就无从判断「到底断没断」。
 * 现在把 {@code camouflage_frame} 自己的模型换成与分隔框架同族的<b>1 像素镂空框架</b>：
 * 未选外观时看到的是「框架本体套在线缆上」，线缆的连接臂 / 断开状态从框架的开口里直接可见，
 * 于是「把那条缝断开后，伪装外壳也随之显示断开态」这件事不需要额外画任何东西。</p>
 *
 * <h2>客户端如何拿到材质</h2>
 * <p>材质存在方块实体的 NBT 里，服务端改动时用 Create 的
 * {@code SyncedBlockEntity#sendData()} 下发（原版 {@code ClientboundBlockEntityDataPacket}），
 * 客户端 {@code CopycatBlockEntity#read(..., clientPacket=true)} 收到后
 * {@code requestModelDataUpdate()} 触发本模型重新取数——即「服务端权威、客户端只负责画」。</p>
 */
public class CamouflageFrameModel extends CopycatModel {

    public CamouflageFrameModel(final BakedModel originalModel) {
        super(originalModel);
    }

    /**
     * 「没有自定义材质」的判定：Create 用的是占位状态 {@code create:copycat_base}（那张贴图是透明的），
     * 空状态（理论上不会出现，但存档损坏时可能）一并按没有材质处理。
     */
    private static boolean hasNoMaterial(final BlockState material) {
        return AllBlocks.COPYCAT_BASE.has(material) || material.isAir();
    }

    /**
     * 完整方块外壳套完整方块材质：不裁切、不位移，直接返回材质模型的四元组即可。
     * <p>没有自定义材质时改返回<b>本方块自己的兜底立方体</b>（不透明），确保不透视。</p>
     * <p>{@code wrappedData} 是 Create 已经按「能不能跟邻居接续纹理」筛过的材质模型数据，
     * 必须原样转发，否则玻璃 / 连接纹理类材质会画错；兜底立方体与「材质」无关，因此用 EMPTY。</p>
     */
    @Override
    protected List<BakedQuad> getCroppedQuads(final BlockState state, final Direction side, final RandomSource rand,
                                              final BlockState material, final ModelData wrappedData,
                                              final RenderType renderType) {
        if (hasNoMaterial(material)) {
            // 兜底：画本方块自己的不透明立方体（originalModel 就是它，不经过 CopycatModel 的材质逻辑）
            return originalModel.getQuads(state, side, rand, ModelData.EMPTY, renderType);
        }
        return getModelOf(material).getQuads(material, side, rand, wrappedData, renderType);
    }

    /**
     * 渲染层（solid / cutout / cutoutMipped / translucent）跟着外壳材质走；没有材质时就是兜底立方体的 solid。
     * <p>必须显式给出：Create 的伪装板是靠注册表把方块登记进多个渲染层实现的，本方块没有走那条路，
     * 而父类 {@link CopycatModel#getQuads} 有一道保险——「请求的渲染层不在材质的渲染层集合里就退化成
     * 底模」。默认渲染层只有 solid，于是外壳是玻璃 / 树叶这类材质时会整块消失；
     * 这里直接返回材质自己的渲染层集合，请求与画出的东西就永远一致。</p>
     */
    @Override
    public ChunkRenderTypeSet getRenderTypes(final BlockState state, final RandomSource rand, final ModelData data) {
        final BlockState material = CopycatModel.getMaterial(data);
        if (hasNoMaterial(material)) {
            // 兜底立方体的渲染层（solid）：与 getCroppedQuads 的兜底分支严格对应
            return originalModel.getRenderTypes(state, rand, ModelData.EMPTY);
        }
        return getModelOf(material).getRenderTypes(material, rand, data);
    }

    /**
     * 没有材质时粒子图标取兜底立方体自己的（否则会取到那张透明的 copycat_base 贴图，
     * 挖掉伪装框架时飘出的粒子会是全透明的）。
     */
    @Override
    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon(final ModelData data) {
        final BlockState material = CopycatModel.getMaterial(data);
        if (hasNoMaterial(material)) {
            return originalModel.getParticleIcon(ModelData.EMPTY);
        }
        return super.getParticleIcon(data);
    }
}
