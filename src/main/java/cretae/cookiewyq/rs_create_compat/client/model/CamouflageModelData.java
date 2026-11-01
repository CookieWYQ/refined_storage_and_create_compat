package cretae.cookiewyq.rs_create_compat.client.model;

import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * 「伪装外壳」在<b>模型数据通道</b>里的唯一属性 / 唯一桥接。
 *
 * <h2>为什么需要它（本轮修「手动逐个套上也看不到外观」的根因）</h2>
 * <p>外壳的几何是<b>烘焙产物</b>，它只能从 {@code BakedModel#getQuads(..., ModelData, RenderType)}
 * 的 {@code ModelData} 里拿到「这一格裹着什么」。在此之前，本模组<b>唯一</b>的取数点是
 * {@code CamouflageShellModel#gatherModelData}（它自己去问 {@code BlockAndTintGetter#getBlockEntity}）。
 * 而 {@code gatherModelData} 只会在引擎<b>主动调用</b>
 * {@code BakedModel#getModelData(world, pos, state, 方块实体模型数据)} 的那条路径上被调用：</p>
 * <ul>
 *     <li>区块网格（{@code SectionCompiler}）—— 会调用 ✔；</li>
 *     <li>Create 装置 / 虚拟世界烘焙（{@code ContractionEntityRenderer} → {@code getModelData}）—— 会调用 ✔；</li>
 *     <li><b>物理化模组（Sable）的「单格」渲染分支</b>—— <b>不调用</b>。它把
 *     {@code ClientLevel#getModelData(pos)} 直接塞给 {@code ModelBlockRenderer#tesselateWithoutAO}，
 *     并用 {@code SingleBlockSubLevelWrapper#getModelData(pos)}（默认实现 = {@link ModelData#EMPTY}）
 *     去问 {@code BakedModel#getRenderTypes}。于是那条路径上：
 *     {@code getQuads} 的 {@code ModelData} 里没有伪装字段 → <b>零个外壳四元组</b>；
 *     {@code getRenderTypes} 也报不出外壳要用的渲染层 → 连重烘都白搭。</li>
 * </ul>
 * <p>结论：<b>「外壳数据」必须走「方块实体模型数据」这条通道</b>，而不是只有「被引擎回调」时才现问方块实体。
 * 这也正是 Create 伪装板的做法（材质写在 {@code CopycatBlockEntity#getModelData()} 里，
 * 任何渲染路径只要拿到模型数据就画得出来）。本类就是那条通道的载体：</p>
 * <ol>
 *     <li>{@code mixin/client/CamouflageClientLevelModelDataMixin}：给
 *     {@code ClientLevel#getModelData(pos)} 的返回值并上本属性（物理化单格路径读的就是它）；</li>
 *     <li>{@code mixin/client/CamouflageGetterModelDataMixin}：给没有自己覆写
 *     {@code getModelData} 的 {@code BlockAndTintGetter}（第三方包装 / 物理化单格 wrapper）
 *     补一份默认实现 —— 直接问 {@code getBlockEntity(pos)} 的附件（物理化单格问渲染层时读的就是它）；</li>
 *     <li>{@code CamouflageShellModel}：模型数据里已经有本属性时<b>直接用它</b>，没有才回落到
 *     「现问方块实体」那条老路径（区块 / 装置烘焙的实际来源，保持逐字不变）。</li>
 * </ol>
 *
 * <h2>同源判据（自检断言的就是这一句）</h2>
 * <p>本属性的值<b>只有一份来源</b>：{@code RsccCamouflageAttachment.CAMOUFLAGE} 那份数据附件
 * （服务端写、NeoForge 同步给客户端）。上面三个挂点都只是「把同一份附件搬到模型数据里」，
 * 谁都没有自己的第二份状态 —— 因此不存在「服务端写了一份、客户端读的是另一份」的可能。</p>
 *
 * <h2>线程</h2>
 * <p>{@link ModelData} 的值会被区块烘焙线程读取，因此这里只放不可变的 {@link RsccCamouflage.Camo}
 * （record；其中的 {@code BlockState} 不可变，{@code ItemStack} 只被读、从不被改）。</p>
 */
public final class CamouflageModelData {
    /**
     * 模型数据里的「这一格裹着什么」。
     *
     * <p><b>为什么允许为 {@code null} 值</b>：{@code ModelData} 的 {@code ModelProperty} 只允许放
     * 非空值（空值 = 没有这个属性），因此「没裹住」一律表现为<b>没有这个键</b>，
     * 绝不放入一个 {@code null} 占位。</p>
     */
    public static final ModelProperty<RsccCamouflage.Camo> CAMOUFLAGE = new ModelProperty<>();

    private CamouflageModelData() {
    }

    /** 只带伪装记录的一份模型数据（方块实体通道 / 缺省 getter 的返回值都用它）。 */
    public static ModelData of(final RsccCamouflage.Camo camo) {
        return ModelData.of(CAMOUFLAGE, camo);
    }

    /** 读模型数据里的伪装记录；没有（= 这一格没裹住）返回 {@code null}。 */
    @Nullable
    public static RsccCamouflage.Camo camoOf(@Nullable final ModelData data) {
        return data == null ? null : data.get(CAMOUFLAGE);
    }

    /**
     * 把伪装记录<b>并进</b>一份已有的模型数据（保留别的模组 / 别的属性的全部键值）。
     *
     * <p>用 {@link ModelData#derive()} 复制：{@code ModelData} 是不可变的，
     * 就地改原对象既不可能、也会污染同一格别的属性（例如 RS 线缆的 {@code CABLE_CONNECTIONS}）。
     * 已经带着本属性时原样返回（幂等：同一条路径被调用多次不会反复复制）。</p>
     */
    public static ModelData withCamo(@Nullable final ModelData base,
                                     final RsccCamouflage.Camo camo) {
        if (base == null || base == ModelData.EMPTY) {
            return of(camo);
        }
        if (base.has(CAMOUFLAGE)) {
            return base;
        }
        return base.derive().with(CAMOUFLAGE, camo).build();
    }
}
