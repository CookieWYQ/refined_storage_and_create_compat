package cretae.cookiewyq.rs_create_compat.client.model;

import com.simibubi.create.foundation.model.BakedModelWrapperWithData;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「伪装外壳」的渲染模型：<b>把外壳画成被裹方块自己模型的一部分</b>
 * —— 即「材质随方块一起被画」的那条链路。
 *
 * <h2>与旧架构的根本差别（为什么这就解决了残影与「不跟随」）</h2>
 * <p>旧实现是一个独立的 {@code CamouflageRenderer}：它按帧遍历「坐标 → 材质」的表，
 * 再用 {@code tesselateBlock} 在方块原点<b>另外画一层</b>。那条链路与方块本身<b>没有任何关系</b>：
 * 方块被搬走 / 被抹掉时它照样在画 → <b>残影</b>；方块被搬到别处时它又不知道搬去哪了 → <b>不跟随</b>。</p>
 * <p>本类是 Create 伪装板那条路（{@code CopycatModel}）的同口径实现：外壳的几何由
 * <b>被裹方块自己的烘焙模型</b>产出，并且是从<b>方块实体</b>读材质
 * （{@link com.simibubi.create.content.decoration.copycat.CopycatBlockEntity#getModelData}
 * 对应的正是本类的 {@link #gatherModelData}）。于是：</p>
 * <ul>
 *     <li>外壳是<b>区块网格 / 装置网格</b>的一部分：方块不在，网格里就没有它 → 残影<b>不可能</b>；</li>
 *     <li>方块被装置搬走时，它的方块实体（连同附件里的材质）一起被抄进装置，
 *     装置的烘焙走的是同一句 {@link #gatherModelData}（{@code VirtualRenderWorld} 提供方块实体）
 *     → 外壳<b>跟着装置一起被画出来</b>。</li>
 * </ul>
 *
 * <h2>取数只有一条来源：方块实体附件</h2>
 * <p>{@link #gatherModelData} 里唯一的输入是
 * {@link RsccCamouflage#get(net.minecraft.world.level.BlockGetter, BlockPos)}
 * —— 它读的是<b>被裹方块自己的方块实体</b>上的附件，没有任何「坐标 → 全局表」的反查。</p>
 *
 * <h2>几何：被裹方块自己的 + 外壳</h2>
 * <p>两者都要画：外壳是整格（完整显示模式下会把下面的线缆盖住），但「只显示框架」时
 * 线缆的连接臂必须从框架开口里看得见（否则玩家无从判断某一道缝断没断）。
 * 因此 {@link #getQuads} 返回的是<b>并集</b>。</p>
 *
 * <h2>模型数据：一次算齐，四元组阶段只读</h2>
 * <p>四样东西都在模型数据阶段定好（与 Create 的 {@code CopycatModel} 同一分工）：</p>
 * <ul>
 *     <li>{@link #BASE_DATA_PROPERTY}：被裹方块<b>自己</b>的模型数据（原样转发给它的模型，
 *     免得覆盖别的模组依赖的模型数据）；</li>
 *     <li>{@link #CAMOUFLAGE_PROPERTY}：这一格的伪装记录（材质 + 「框架是否真的扣过」）；</li>
 *     <li>{@link #MATERIAL_DATA_PROPERTY}：<b>材质方块自己的</b>模型数据
 *     （连接纹理类材质靠它；对应 Create 的 {@code WRAPPED_DATA_PROPERTY}）；</li>
 *     <li>{@link #EMISSIVE_PROPERTY}：材质是否发光（对应 Create 的 {@code IS_EMISSIVE_PROPERTY}）。</li>
 * </ul>
 *
 * <h2>渲染层</h2>
 * <p>必须显式给出「并集」：引擎只会为 {@code getRenderTypes} 里出现过的渲染层调用
 * {@link #getQuads}，漏掉一层就等于那部分几何永远不会被画（玻璃外壳整块消失就是这个原因）。</p>
 *
 * <h2>线程</h2>
 * <p>本类的两个阶段都可能跑在<b>区块烘焙的工作线程</b>上，因此这里只做纯函数式的读取：
 * 读方块实体、读静态的方块颜色表、读一个 client-only 的布尔开关 —— 都不写任何共享状态。</p>
 */
public class CamouflageShellModel extends BakedModelWrapperWithData {
    /** 这一格的伪装记录；<b>没有</b>这个字段 = 这一格没被裹住（四元组阶段一个外壳都不产出）。 */
    public static final ModelProperty<RsccCamouflage.Camo> CAMOUFLAGE_PROPERTY = new ModelProperty<>();
    /** 被裹方块自己的模型数据（原样转发）。 */
    private static final ModelProperty<ModelData> BASE_DATA_PROPERTY = new ModelProperty<>();
    /** 材质方块自己的模型数据（转发给材质的模型）。 */
    private static final ModelProperty<ModelData> MATERIAL_DATA_PROPERTY = new ModelProperty<>();
    /** 材质是否发光。 */
    private static final ModelProperty<Boolean> EMISSIVE_PROPERTY = new ModelProperty<>();
    /**
     * <b>外壳这一帧该画什么</b>（{@code true} = 只画框架本体 / 还没选外观）。
     *
     * <p><b>为什么把 K 键那一态的判定也钉进模型数据</b>：本类的三个入口
     * （{@link #gatherModelData} / {@link #getQuads} / {@link #getRenderTypes}）是<b>三次独立调用</b>，
     * 而 {@link RsccCamouflage#isMaterialHidden()} 是一个随时可能被 K 键翻转的全局位。
     * 若三处各自现读一次，就可能出现「层集合按材质算、四元组按框架画」（或反过来）——
     * 表现正是最难排查的那种「外壳静默消失 / 只剩一半」。钉进模型数据后，<b>一次烘焙内三处必然同源</b>；
     * K 键翻转会由 {@code client/CamouflageShellDisplay} 触发整片重烘，因此不需要在四元组阶段再看实时值。</p>
     */
    private static final ModelProperty<Boolean> MATERIAL_HIDDEN_PROPERTY = new ModelProperty<>();
    /**
     * <b>外壳这一次烘焙要参与哪些渲染层</b>（在模型数据阶段只算一次）。
     *
     * <p><b>为什么必须只算一次</b>：引擎的渲染流程是「先问 {@code getRenderTypes}，再为其中每一层调
     * {@code getQuads}」。若这两处各自去问一次材质模型的层集合，任何一份「带状态 / 每次调用给不同答案」
     * 的材质模型（第三方模型、连接纹理方块、资源包自定义渲染层）都会让外壳在某一层上被静默丢掉。
     * 同一份集合在两处共用后，「报过的层」与「画出来的层」恒等 —— 这是外壳「必然产出」的一半。</p>
     *
     * <p>另一半（另一半在 {@link #getRenderTypes}）：并集<b>永不为空</b>，否则引擎根本不会为这一格调用
     * {@link #getQuads}，外壳就一个四元组都产不出来。</p>
     */
    private static final ModelProperty<ChunkRenderTypeSet> SHELL_LAYERS_PROPERTY = new ModelProperty<>();

    /**
     * 「还没选外观」/「只显示框架」时用的几何 = 伪装框架方块自己的模型
     * （16 根中的 12 根 1/16 格实体边条，与「直接包裹」时看到的框架完全是同一份）。
     *
     * <p><b>为什么在构造时就把这个模型传进来</b>：它来自<b>同一次</b>烘焙结果
     * （见 {@code ClientInit#onModifyBakingResult}），因此资源重载后自动跟着换新，
     * 不需要任何缓存失效逻辑，也不可能拿到上一个资源包的残留模型。</p>
     */
    private final BakedModel frameModel;

    public CamouflageShellModel(final BakedModel originalModel, final BakedModel frameModel) {
        super(originalModel);
        this.frameModel = frameModel;
    }

    /** 框架模型的方块状态（只用来取几何，与世界上有没有这个方块无关）。 */
    private static BlockState frameState() {
        return RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK.get().defaultBlockState();
    }

    /** 材质方块状态的烘焙模型（与 Create 的 {@code CopycatModel#getModelOf} 同一句）。 */
    private static BakedModel modelOf(final BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    @Override
    protected ModelData.Builder gatherModelData(final ModelData.Builder builder, final BlockAndTintGetter world,
                                                final BlockPos pos, final BlockState state,
                                                final ModelData blockEntityData) {
        builder.with(BASE_DATA_PROPERTY, blockEntityData);
        // 唯一取数来源：被裹方块自己的方块实体（两种烘焙路径 —— RenderChunkRegion / VirtualRenderWorld
        // —— 都实现了 getBlockEntity，因此区块网格与装置网格走的是同一句话）
        final RsccCamouflage.Camo camo = RsccCamouflage.get(world, pos);
        if (camo == null) {
            return builder; // 没裹住：连伪装字段都不放 → getQuads 不会产出任何外壳四元组
        }
        builder.with(CAMOUFLAGE_PROPERTY, camo);
        final BlockState material = camo.material();
        // 「这一格格子该画框架还是画材质」+「外壳参与哪些层」都在这里一次定死：
        // 引擎随后会分两次回问本类（getRenderTypes / getQuads），三处同源才不会出现
        // 「报了 A 层、却只在 B 层画」这种外壳静默丢失（见两个属性的注释）。
        // 种子与引擎自己用的那一份一致（SectionCompiler: rand.setSeed(state.getSeed(pos))）。
        final RandomSource seedRand = RandomSource.create(state.getSeed(pos));
        if (material == null || RsccCamouflage.isMaterialHidden()) {
            builder.with(MATERIAL_HIDDEN_PROPERTY, Boolean.TRUE);
            builder.with(SHELL_LAYERS_PROPERTY,
                frameModel.getRenderTypes(frameState(), seedRand, ModelData.EMPTY));
            return builder;
        }
        builder.with(MATERIAL_HIDDEN_PROPERTY, Boolean.FALSE);
        final ModelData materialData = modelOf(material).getModelData(world, pos, material, ModelData.EMPTY);
        builder.with(MATERIAL_DATA_PROPERTY, materialData);
        builder.with(SHELL_LAYERS_PROPERTY,
            modelOf(material).getRenderTypes(material, seedRand, materialData));
        builder.with(EMISSIVE_PROPERTY, material.emissiveRendering(world, pos));
        return builder;
    }

    /**
     * 并集：<b>被裹方块自己的四元组</b> ＋ <b>外壳的四元组</b>。
     *
     * <p>返回的永远是<b>本方法自己新建的列表</b>：引擎允许调用方随意使用返回值，
     * 而下面两处来源（对方模型的内部列表）绝不能被就地改动。</p>
     *
     * <h2>外壳的产出与被裹方块<b>无关</b>（孤立单格必然出外壳）</h2>
     * <p>外壳分支的唯一输入是模型数据里的伪装记录（{@link #CAMOUFLAGE_PROPERTY}），
     * 因此它<b>不看</b>：被裹方块自己的模型返回了几个四元组（孤立线缆只返回一根芯柱，一根都不返回也一样）、
     * 它的连接状态（{@code CableConnections} / 管道的六向臂）、以及这一格周围有没有邻居。
     * 下面这一句 {@code originalModel.getQuads(...)} 是<b>并列叠加</b>而不是外壳的输入 ——
     * 它只负责「断开的连接臂要能从框架开口里看见」。</p>
     */
    @Override
    public List<BakedQuad> getQuads(@Nullable final BlockState state, @Nullable final Direction side,
                                    final RandomSource rand, @Nullable final ModelData data,
                                    @Nullable final RenderType renderType) {
        final ModelData base = data == null ? null : data.get(BASE_DATA_PROPERTY);
        final List<BakedQuad> quads = new ArrayList<>(
            originalModel.getQuads(state, side, rand, base == null ? ModelData.EMPTY : base, renderType));
        final RsccCamouflage.Camo camo = data == null ? null : data.get(CAMOUFLAGE_PROPERTY);
        if (camo != null) {
            quads.addAll(shellQuads(state, side, rand, data, camo, renderType));
        }
        return quads;
    }

    /**
     * 外壳的四元组。
     *
     * <p><b>两条分支共用同一套几何口径</b>（都在方块原点 1:1，没有缩放 / 偏移）：</p>
     * <ol>
     *     <li><b>完整显示</b>：画材质方块自己的模型（与 Create 伪装板同一句
     *     {@code getModelOf(material).getQuads(material, ...)}）；</li>
     *     <li><b>还没选外观</b> 或 <b>K 键切到「只显示框架」</b>：画框架本体模型 ——
     *     两条分支的差别只有「材质参数」，因此切换时不会出现粗细 / 透明度跳变。</li>
     * </ol>
     */
    private List<BakedQuad> shellQuads(@Nullable final BlockState state, @Nullable final Direction side,
                                       final RandomSource rand, final ModelData data,
                                       final RsccCamouflage.Camo camo, @Nullable final RenderType renderType) {
        // 「该画框架还是画材质」用模型数据阶段钉下来的那一份；缺字段才退回实时开关（保证三处同源）
        final Boolean pinned = data.get(MATERIAL_HIDDEN_PROPERTY);
        final BlockState material = camo.material();
        if (material == null || (pinned != null ? pinned : RsccCamouflage.isMaterialHidden())) {
            return frameModel.getQuads(frameState(), side, rand, ModelData.EMPTY, renderType);
        }
        final BakedModel model = modelOf(material);
        final ModelData wrapped = wrappedData(data);
        // 层门禁用「模型数据阶段算好的那一份」，不再现问一次材质模型：
        // 两次独立查询只要答案不一致，外壳就会在这一层被静默丢掉（孤立单格尤其容易被误判成「没有外壳」）。
        final ChunkRenderTypeSet layers = data.get(SHELL_LAYERS_PROPERTY);
        if (renderType != null && layers != null && !layers.contains(renderType)) {
            return List.of(); // 材质自己不在这个渲染层产出几何：不画（与 CopycatModel 同一条保险）
        }
        final List<BakedQuad> quads = model.getQuads(material, side, rand, wrapped, renderType);
        if (!Boolean.TRUE.equals(data.get(EMISSIVE_PROPERTY))) {
            return quads;
        }
        // 发光材质：整个外壳按最大发光度后处理（同样的四元组后处理 Create 也有一份）
        final List<BakedQuad> emissive = new ArrayList<>(quads);
        QuadTransformers.settingMaxEmissivity().processInPlace(emissive);
        return emissive;
    }

    /** 材质方块自己的模型数据（拿不到时按 EMPTY 处理，让材质的模型走默认分支）。 */
    private static ModelData wrappedData(final ModelData data) {
        final ModelData wrapped = data.get(MATERIAL_DATA_PROPERTY);
        return wrapped == null ? ModelData.EMPTY : wrapped;
    }

    /**
     * 渲染层 = <b>并集</b>（被裹方块自己的 ∪ 外壳的）。漏任何一层，那一层的几何就永远不会被画。
     *
     * <p><b>外壳那一半取自模型数据阶段算好的 {@link #SHELL_LAYERS_PROPERTY}</b>：
     * 与 {@link #getQuads} 里那道层门禁读的是<b>同一份集合</b>，因此不可能出现
     * 「这里报了某层、那里却把它判掉」的静默丢失。</p>
     *
     * <p><b>并集永不为空</b>：引擎只会为这里出现过的层调用 {@code getQuads}。被裹方块自己的模型
     * 若报了空集合（孤立态 / 第三方模型 / 资源包把它整段改掉），外壳就会连被问一次的机会都没有 ——
     * 因此空集合一律兜底成 {@code solid}（那一层画不出东西时引擎自己会丢弃空网格，没有副作用）。</p>
     */
    @Override
    public ChunkRenderTypeSet getRenderTypes(final BlockState state, final RandomSource rand,
                                             final ModelData data) {
        final ModelData base = data == null ? null : data.get(BASE_DATA_PROPERTY);
        final Set<RenderType> types = new LinkedHashSet<>(4);
        originalModel.getRenderTypes(state, rand, base == null ? ModelData.EMPTY : base).forEach(types::add);
        final RsccCamouflage.Camo camo = data == null ? null : data.get(CAMOUFLAGE_PROPERTY);
        if (camo != null) {
            final ChunkRenderTypeSet layers = data.get(SHELL_LAYERS_PROPERTY);
            if (layers != null) {
                layers.forEach(types::add);
            } else {
                // 不变量兜底（gatherModelData 永远与伪装记录成对写入，此处只防模型数据被别处裁剪）：
                // 外壳至少要报出一个层，否则引擎不会调用 getQuads
                frameModel.getRenderTypes(frameState(), rand, ModelData.EMPTY).forEach(types::add);
            }
        }
        return types.isEmpty() ? ChunkRenderTypeSet.of(RenderType.solid()) : ChunkRenderTypeSet.of(types);
    }

    /**
     * 粒子图标跟被裹方块自己走（外壳是「额外一层」，挖掉时的粒子仍应是这一格原本的方块 ——
     * 与「破坏只掉原部件」这条语义一致）。
     */
    @Override
    public TextureAtlasSprite getParticleIcon(final ModelData data) {
        final ModelData base = data == null ? null : data.get(BASE_DATA_PROPERTY);
        return originalModel.getParticleIcon(base == null ? ModelData.EMPTY : base);
    }
}
