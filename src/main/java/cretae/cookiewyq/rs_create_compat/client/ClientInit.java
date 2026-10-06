package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.common.api.RefinedStorageClientApi;
import com.refinedmods.refinedstorage.common.support.network.item.NetworkItemPropertyFunction;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.foundation.model.ModelSwapper;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.model.CamouflageFrameModel;
import cretae.cookiewyq.rs_create_compat.client.model.CamouflageShellModel;
import cretae.cookiewyq.rs_create_compat.client.screen.AdvancedSchematicLoaderScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.QuantityKeeperScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.RangeChargerScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.SchematicLoaderScreen;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import java.util.Map;

/**
 * 客户端初始化：向 RS 注册通用储存磁盘的 3D 模型，并注册各 GUI 屏幕。
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public class ClientInit {
    /**
     * 旧存档里「真的放进世界的」伪装框架方块（更早的版本由物品放置）的取色器：
     * 与 Create 伪装板完全同源 —— 材质记在方块实体里，交给 Create 自己的
     * {@link CopycatBlock#wrappedColor()}。新玩法（裹在线缆 / 管道 / 传动杆外面）的取色见
     * {@link #camouflageShellColor}，两者共用同一个处理器，按「这一格有没有填充方块」分流。
     */
    private static final BlockColor WRAPPED_COLOR = CopycatBlock.wrappedColor();

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        // 终端快捷键：KeyMapping 本身在 MOD 总线注册（见 TerminalKeybinds），
        // 「按键按下」走游戏总线的 InputEvent.Key —— 与 RS 原版无线终端快捷键同一条链路。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(TerminalKeybinds::onKeyInput);
        // 分隔框架的显示快捷键：同一条链路（KeyMapping 在 MOD 总线注册，见 SeparationFrameKeybinds）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(SeparationFrameKeybinds::onKeyInput);
        // 伪装框架的「隐藏 / 恢复已填充方块」快捷键 K：同一条链路（KeyMapping 在 MOD 总线注册，见 CamouflageKeybinds）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CamouflageKeybinds::onKeyInput);
        // K 键的开关一变，就「只重建受影响的伪装格」（绝不整片重烘 —— 旧实现的 allChanged() 会让
        // 整个界面 / 世界在一瞬间不渲染并强烈卡顿）。见 CamouflageShellDisplay（纯客户端类，
        // 因此「不许在通用类里碰客户端类型」这条约束不被破坏）。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CamouflageShellDisplay::onClientTick);
        // 「这条坐标可能是伪装格」的登记回调：收包路径（双端类）不能引用客户端类型，
        // 因此由这里把客户端实现注入进去（专用服务端上该回调恒为 null，永不被调用）。
        cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket
            .setClientTracker(CamouflageShellDisplay::track);
        // 所有 S2C 载荷的「客户端真身」：载荷类在注册期就会被专用服务端加载并链接，因此 network 包
        // 里不允许出现任何客户端类型。这里把客户端实现注入到唯一的桥接层（服务端上永远是空实现）。
        // 注入前的崩溃根因见 network/ClientPayloadHooks 的类文档。
        cretae.cookiewyq.rs_create_compat.network.ClientPayloadHooks.install(new ClientPayloadSink());
        event.enqueueWork(() -> {
            final ResourceLocation diskModel =
                ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "block/disk/disk");
            for (final var disk : java.util.List.of(
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_1K,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_4K,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_16K,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_64K,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_256K,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_1M,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_4M,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_16M,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_64M,
                RS_Create_Compat.UNIVERSAL_STORAGE_DISK_CREATIVE)) {
                RefinedStorageClientApi.INSTANCE.registerDiskModel(disk.get(), diskModel);
            }
            // 高级远程终端（普通 / 满电）的“绑定 / 未绑定”两态物品属性：
            // 复用 RS 原版 NetworkItemPropertyFunction（判定 AbstractNetworkEnergyItem#isBound，
            // 即物品是否已记录网络位置），物品模型据此在 *_active / *_inactive 之间切换。
            // 创造版永远视为已绑定，无需注册该属性。
            for (final var terminal : java.util.List.of(
                RS_Create_Compat.ADVANCED_REMOTE_TERMINAL,
                RS_Create_Compat.ADVANCED_REMOTE_TERMINAL_CHARGED)) {
                net.minecraft.client.renderer.item.ItemProperties.register(
                    terminal.get(),
                    NetworkItemPropertyFunction.NAME,
                    new NetworkItemPropertyFunction());
            }
        });
    }

    /**
     * 分隔框架的方块实体渲染器：显隐必须按帧判断（区块网格是烘焙缓存，
     * 换手 / 按快捷键不会重烘），所以只有方块实体渲染器这一条路，详见 {@link SeparationFrameRenderer}。
     */
    @SubscribeEvent
    public static void onRegisterRenderers(
        final net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RS_Create_Compat.SEPARATION_FRAME_BLOCK_ENTITY.get(),
            SeparationFrameRenderer::new);
    }

    /**
     * 注册两种样板的自定义 tooltip 图标组件：
     * <ul>
     *     <li>{@link SequenceUnitPatternItem.IconTooltip} → 单元样板（机器 / 输入原料 / 输入流体三行）；</li>
     *     <li>{@link SequenceAssemblyPatternItem.InputsImage} → 总样板（主原料 + 各步输入原料 + 输入流体，
     *     每一条可互换原料一行、图标与名称一起轮播）。</li>
     * </ul>
     * <p>为什么总样板也走图标组件：用户明确要求「禁止把多种原料拼成『A 或 B（或 C）』这种文字」，
     * 一律用轮播表达（原话：「为什么不能吃这里面的文本来进行轮换显示呢」）。</p>
     */
    @SubscribeEvent
    public static void onRegisterTooltipFactories(final RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(cretae.cookiewyq.rs_create_compat.item.SequenceUnitPatternItem.IconTooltip.class,
            tooltip -> new cretae.cookiewyq.rs_create_compat.client.tooltip.UnitPatternTooltipComponent(
                cretae.cookiewyq.rs_create_compat.client.tooltip.RecipeTypeIcons.forRecipeType(
                    tooltip.recipeType(), tooltip.input()),
                tooltip.input(), tooltip.inputFluid(),
                // 输入原料组的全部候选（列车轨道机械手步的「铁粒 或 锌粒」）：
                // 随单元样板 NBT 落盘，因此本组件即使拿不到 Level 也能轮播整组候选。
                tooltip.inputCandidates()));
        event.register(cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem.InputsImage.class,
            image -> new cretae.cookiewyq.rs_create_compat.client.tooltip.AssemblyInputsTooltipComponent(
                image.ingredient(), image.items(), image.fluids()));
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(final RegisterMenuScreensEvent event) {
        event.register(RS_Create_Compat.RANGE_CHARGER_MENU.get(), RangeChargerScreen::new);
        event.register(RS_Create_Compat.QUANTITY_KEEPER_MENU.get(), QuantityKeeperScreen::new);
        event.register(RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.AdvancedQuantityKeeperScreen::new);
        event.register(RS_Create_Compat.SCHEMATIC_LOADER_MENU.get(), SchematicLoaderScreen::new);
        event.register(RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_MENU.get(), AdvancedSchematicLoaderScreen::new);
        event.register(RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.SequencePatternTerminalScreen::new);
        // 执行仓单元样板汇总（真槽位容器菜单；由服务端 openMenu 打开）
        event.register(RS_Create_Compat.CHAMBER_UNITS_SUMMARY_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.ChamberUnitsSummaryScreen::new);
        event.register(RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.SequenceAssemblyExecutorScreen::new);
        event.register(RS_Create_Compat.SEQUENCE_EXECUTION_CHAMBER_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.SequenceExecutionChamberScreen::new);
        event.register(RS_Create_Compat.COLLECTION_CACHE_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.CollectionCacheScreen::new);
        // 中间产物缓存仓（网络内所有执行舱共享的中间产物临时储存点）
        event.register(RS_Create_Compat.INTERMEDIATE_CACHE_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.IntermediateCacheScreen::new);
        // 单元样板管理舱（按执行舱分组的单元样板管理 + 无线终端里的同一页）
        event.register(RS_Create_Compat.UNIT_PATTERN_MANAGER_MENU.get(),
            cretae.cookiewyq.rs_create_compat.client.screen.UnitPatternManagerScreen::new);
    }

    /**
     * 把「外壳族」方块（线缆 / 流体管道 / 传动杆）的全部烘焙模型换成
     * {@link CamouflageShellModel} —— 外壳因此成为<b>这些方块自己模型的一部分</b>：
     * 方块不在网格里，外壳就不在；方块随装置走，外壳就跟着走。
     *
     * <h2>与旧实现（独立渲染器）的差别，一句话</h2>
     * <p>旧实现把外壳交给 {@code client/CamouflageRenderer} 按帧单独画（它按世界坐标回头查伪装记录），
     * 于是方块被搬走后外壳留在原地（残影）、搬走的方块又带不走外壳（不跟随）。
     * 现在外壳由<b>方块自己的模型</b>产出，材质由<b>方块自己的方块实体</b>提供
     * （见 {@link CamouflageShellModel}），因此这两件事结构性不可能发生。</p>
     *
     * <h2>为什么先换「伪装框架」方块自己的模型</h2>
     * <p>它的模型同时是「还没选外观」与「K 键切到只显示框架」两种情形要用的<b>几何来源</b>：
     * 外壳模型在构造时<b>直接拿到刚换好的那一份</b>（同一次烘焙结果里的同一个实例），
     * 因此资源重载后自动跟着换新，不需要任何缓存失效逻辑。</p>
     *
     * <p>与 Create 的伪装板同一条路径：复用 {@link ModelSwapper}，不重新烘焙资源，
     * 只在烘焙结果 map 里就地包装 —— 换外壳材质（甚至 K 键切换）都不会触发任何资源重载。</p>
     */
    @SubscribeEvent
    public static void onModifyBakingResult(final ModelEvent.ModifyBakingResult event) {
        final Map<ModelResourceLocation, BakedModel> models = event.getModels();
        // ① 伪装框架方块自身（旧存档里真的放下的那些，以及本模组取框架几何的来源）
        ModelSwapper.swapModels(models,
            ModelSwapper.getAllBlockStateModelLocations(RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK.get()),
            CamouflageFrameModel::new);
        final BakedModel frameModel = models.get(BlockModelShaper.stateToModelLocation(
            RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK.get().defaultBlockState()));
        if (frameModel == null) {
            return; // 理论上不会发生（缺模型时引擎给的是 missing model）；真发生时宁可不动任何模型
        }
        // ② 「外壳族」的每一个状态：包装成外壳模型（同一份 frameModel 直接复用）
        for (final Block block : CamouflageShellTargets.blocks()) {
            for (final ModelResourceLocation location : ModelSwapper.getAllBlockStateModelLocations(block)) {
                final BakedModel original = models.get(location);
                if (original == null || original instanceof CamouflageShellModel) {
                    continue; // 幂等 + 绝不把 null 传进包装器
                }
                models.put(location, new CamouflageShellModel(original, frameModel));
            }
        }
    }

    /**
     * <b>外壳着色</b>：外壳材质是草方块 / 树叶 / 水这类「有颜色」的方块时，
     * 必须按<b>被裹材质</b>自己的颜色处理器取色（草方块 → 生物群系草色），否则灰度贴图会渲染成白色。
     *
     * <h2>为什么必须挂在「被裹方块」上（而不是像旧版那样挂在伪装框架方块上）</h2>
     * <p>旧版的外壳由独立渲染器画，那句 {@code tesselateBlock} 传的方块状态是<b>伪装框架方块</b>，
     * 所以处理器挂在它身上就够。现在外壳是<b>被裹方块自己模型的一部分</b>，引擎逐四元组取色时传进来的
     * 状态是<b>被裹方块</b>（线缆 / 管道 / 传动杆）—— 因此处理器必须为这些方块注册；
     * 且这一步在<b>区块网格烘焙</b>期同样会发生（那时的 {@code BlockAndTintGetter} 是
     * {@code RenderChunkRegion}，仍然能取到方块实体，见
     * {@link RsccCamouflage#material(net.minecraft.world.level.BlockGetter, net.minecraft.core.BlockPos)}）。</p>
     *
     * <h2>没被裹住时为什么返回 -1 是安全的</h2>
     * <p>{@code -1} 就是「没有颜色处理器」的效果（四元组按纯白处理），
     * 线缆 / 管道 / 传动杆的贴图本来就是上色好的实图、不需要 tint，因此与改动前逐像素一致。</p>
     *
     * <h2>谁在用这个颜色</h2>
     * <p>{@code ModelBlockRenderer#tesselateBlock} 逐四元组查
     * {@code BakedQuad#isTinted()} → {@code BlockColors#getColor(state, level, pos, tintIndex)}。</p>
     */
    @SubscribeEvent
    public static void onRegisterBlockColors(final RegisterColorHandlersEvent.Block event) {
        // 旧存档里真的放下的伪装框架方块（材质在它自己的方块实体里）
        event.register(ClientInit::camouflageShellColor, RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK.get());
        // 外壳族：外壳的几何由它们自己的模型产出，取色也因此落在它们身上
        event.register(ClientInit::camouflageShellColor,
            CamouflageShellTargets.blocks().toArray(new Block[0]));
    }

    /**
     * 外壳着色：这一格有填充方块就按<b>填充方块自己的颜色处理器</b>取色；否则退回
     * Create 那一份（旧存档里的伪装框架方块走它；线缆 / 管道 / 传动杆则拿到 -1 = 不上色）。
     */
    private static int camouflageShellColor(final BlockState state, final BlockAndTintGetter level,
                                            final BlockPos pos, final int tintIndex) {
        if (level == null || pos == null) {
            return GrassColor.get(0.5D, 1.0D); // 与 Create 伪装板同一句兜底
        }
        final BlockState material = RsccCamouflage.material(level, pos);
        if (material != null) {
            // 关键：把「被裹材质自己的颜色处理器」问一遍（草方块 / 树叶 / 水都在这里被正确染色）
            return Minecraft.getInstance().getBlockColors().getColor(material, level, pos, tintIndex);
        }
        return WRAPPED_COLOR.getColor(state, level, pos, tintIndex);
    }

    private ClientInit() {
    }
}
