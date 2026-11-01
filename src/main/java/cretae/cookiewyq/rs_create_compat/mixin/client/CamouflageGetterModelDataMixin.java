package cretae.cookiewyq.rs_create_compat.mixin.client;

import cretae.cookiewyq.rs_create_compat.client.model.CamouflageModelData;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflageAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 给「没有自己覆写 {@code getModelData(BlockPos)}」的 {@link BlockAndTintGetter} 补一份默认实现：
 * <b>直接问它自己的 {@code getBlockEntity(pos)} 有没有伪装附件</b>。
 *
 * <h2>为什么需要它（本轮修「手动逐个套上也看不到外观」的另一半）</h2>
 * <p>物理化模组（Sable）的「单格」渲染分支在问渲染层时，传给
 * {@code BakedModel#getRenderTypes(state, rand, ModelData)} 的是
 * {@code SingleBlockSubLevelWrapper#getModelData(pos)}。那个 wrapper 是
 * {@link BlockAndTintGetter} 的实现，但<b>没有覆写</b> {@code getModelData} ——
 * 于是走 NeoForge 的默认实现 {@code ModelData.EMPTY}（实测：{@code javap} 里
 * {@code SingleBlockSubLevelWrapper} 只有 {@code getBlockEntity}，没有 {@code getModelData}）。</p>
 * <p>结果：引擎拿不到「这一格裹着什么」，{@code CamouflageShellModel#getRenderTypes} 只报出
 * 线缆自己的渲染层 —— 外壳所在的层（框架的 cutout、玻璃材质的 translucent 等）
 * 一次都不会被请求，{@code getQuads} 自然不会产出外壳四元组。表现与「模型数据里没有伪装字段」等价。</p>
 *
 * <h2>为什么是「新增一个 default 方法」而不是 {@code @Inject}</h2>
 * <p>{@code getModelData} 的方法体<b>不在</b> {@link BlockAndTintGetter} 里，而是从
 * {@code IBlockGetterExtension} 继承来的默认方法；Mixin 只能注入到<b>目标类自己声明</b>的方法上。
 * 因此这里用「给目标接口新增一个同名同签名的 default 方法」：
 * 合并后它就是 {@link BlockAndTintGetter} 自己声明的方法，按 Java 规则优先于父接口的默认实现，
 * 而所有<b>已经自己覆写</b>了 {@code getModelData} 的实现
 * （{@code ClientLevel} / {@code RenderChunkRegion} / Create 的 {@code VirtualRenderWorld} …）
 * 仍走它们自己的版本，一行都不受影响。</p>
 *
 * <h2>取数仍然只有一条来源</h2>
 * <p>本方法不保存任何状态：它只是把 {@link RsccCamouflageAttachment#CAMOUFLAGE} 这份附件
 * 搬进模型数据。与服务端写入、{@code CamouflageShellModel} 的取数是<b>同一份数据</b>
 * （同源判据见 {@link CamouflageModelData} 的类注释）。</p>
 *
 * <p><b>只挂在 client 段</b>（{@code rs_create_compat.mixins.json}）：专用服务端不加载本类。</p>
 */
@Mixin(BlockAndTintGetter.class)
public interface CamouflageGetterModelDataMixin {
    /**
     * 默认模型数据 = 这一格方块实体上的伪装记录（没有就是 {@link ModelData#EMPTY}）。
     *
     * <p>用 {@code getExistingDataOrNull}：不为了渲染去创建空附件；绝大多数格在这里只多一次
     * 「读一个 map」的开销。取不到方块实体（例如烘焙期的空区块）时同样安全返回 {@code EMPTY}。</p>
     */
    default ModelData getModelData(final BlockPos pos) {
        final BlockEntity be = ((BlockAndTintGetter) this).getBlockEntity(pos);
        if (be == null) {
            return ModelData.EMPTY;
        }
        final RsccCamouflage.Camo camo =
            be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
        return camo == null ? ModelData.EMPTY : CamouflageModelData.of(camo);
    }
}
