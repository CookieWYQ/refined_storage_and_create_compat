package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.AdvancedCollectionCacheScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.CollectionCacheScreen;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerConfigPacket;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 集成：归流缓存仓「匹配区」ghost 槽支持把 JEI 里的<b>物品 / 流体 / 气体</b>直接拖入标记。
 * <p>与序列装配样板终端 / 定量保持器同款写法（{@code IGhostIngredientHandler}）。拖入的条目通过
 * {@link SetCollectionMarkerConfigPacket} 交给服务端权威写入（服务端负责去重与 NBT 归一化），
 * 未指定数量时按拖入堆叠数量（流体按 mB）标记，匹配规则默认两项都关。</p>
 *
 * <h2>与「区域条」的关系（第 130 轮收敛）</h2>
 * <p><b>JEI 给侧栏让位</b>：高级仓界面右边有一条常驻区域条，它必须被算进 GUI 的占用区，
 * 否则 JEI 的物品列表会压在上面。这里用 {@link IGuiContainerHandler#getGuiExtraAreas} 把
 * {@link AreaStripBounds#get()} 原样报给 JEI，由 JEI <b>自己</b>挪开 —— 我们一行「手动挪 JEI」
 * 的代码都没有，那种写法必然与 JEI 自己的布局打架。注意返回的是<b>屏幕坐标</b>
 * （JEI 原话：{@code "the space that the gui takes up besides the normal rectangle"}）；
 * 侧栏不可见时返回空表（矩形为空 ⇒ 不给 JEI 任何额外占用）。</p>
 * <p><b>区域过滤只在匹配区 48 格里编辑</b>：第 130 轮把区域条里那张「标记网格」整页删掉了 ——
 * 用户原话「我在匹配器中匹配就应该是可以直接拖进去，而不是说在这一个界面中单独出现一个标记什么的东西」。
 * 服务端 {@code AdvancedCollectionCacheMenu#applyMarkerConfig} 会按 {@code editingAreaIndex}
 * 把写入落到<b>当前选中区域</b>的 {@code AreaMarks} 上，所以本类的 48 格 ghost 目标天然就是
 * 「给这个区域加过滤」，不需要第二套拖放目标。侧栏不再有标记网格。</p>
 */
@JeiPlugin
public class CollectionCacheJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "collection_cache_jei");
    }

    @Override
    public void registerGuiHandlers(final IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(CollectionCacheScreen.class, new MarkerGhostHandler());
        // 区域条（GUI 右边的常驻侧栏）也要算进 GUI 占用区 ⇒ JEI 自动让位。
        // 注册用 addGuiContainerHandler(Class<? extends T>, IGuiContainerHandler<T>)；
        // 传子类 Class 即可命中基类注册表（JEI 内部按 isAssignableFrom 查找）。
        registration.addGuiContainerHandler(AdvancedCollectionCacheScreen.class,
            new IGuiContainerHandler<AdvancedCollectionCacheScreen>() {
                @Override
                public List<Rect2i> getGuiExtraAreas(final AdvancedCollectionCacheScreen screen) {
                    final Rect2i strip = AreaStripBounds.get();
                    // null = 侧栏不可见（收起 / 槽空 / 屏幕太窄）⇒ 没有额外占用，返回空表
                    return strip == null ? List.of() : List.of(strip);
                }
            });
    }

    /** ghost 放置目标：匹配区当前可见窗口的 12×4 格（下标经 {@code globalIndex} 映射到全局格）。 */
    private static final class MarkerGhostHandler
        implements mezz.jei.api.gui.handlers.IGhostIngredientHandler<CollectionCacheScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(final CollectionCacheScreen screen,
                                                   final ITypedIngredient<I> typedIngredient,
                                                   final boolean doStart) {
            final I ingredient = typedIngredient.getIngredient();
            if (!(ingredient instanceof ItemStack) && !(ingredient instanceof FluidStack)) {
                return List.of();
            }
            // 唯一的一套拖放目标 = 匹配区那 48 格（第 130 轮：侧栏的标记网格已删，不再有第二个分支）。
            // 高级版下写入按 editingAreaIndex 落到「当前选中区域」，所以「选一行 + 从这里拖入」
            // 就是「给这个区域加过滤」；基础版下它写的是全局匹配区（与改动前逐字一致）。
            final CollectionCacheMenu menu = screen.getMenu();
            final List<Target<I>> targets = new ArrayList<>(CollectionCacheMenu.MATCH_WINDOW);
            for (int i = 0; i < CollectionCacheMenu.MATCH_WINDOW; i++) {
                final Slot slot = menu.getSlot(i);
                final int globalIndex = menu.globalIndex(i);
                targets.add(new Target<I>() {
                    @Override
                    public Rect2i getArea() {
                        return new Rect2i(
                            screen.getGuiLeft() + slot.x - 1,
                            screen.getGuiTop() + slot.y - 1,
                            18, 18);
                    }

                    @Override
                    public void accept(final I accepted) {
                        if (accepted instanceof ItemStack stack && !stack.isEmpty()) {
                            PacketDistributor.sendToServer(new SetCollectionMarkerConfigPacket(
                                menu.containerId, globalIndex, false,
                                BuiltInRegistries.ITEM.getKey(stack.getItem()),
                                MarkerEntry.encodeComponents(stack.getComponentsPatch()),
                                Math.max(1, stack.getCount()), false, List.of()));
                        } else if (accepted instanceof FluidStack fluid && !fluid.isEmpty()) {
                            // 流体/气体标记不带数据组件（世界流体方块亦然），与 MarkerEntry.fluidEntry 一致
                            PacketDistributor.sendToServer(new SetCollectionMarkerConfigPacket(
                                menu.containerId, globalIndex, true,
                                BuiltInRegistries.FLUID.getKey(fluid.getFluid()),
                                new CompoundTag(), Math.max(1, fluid.getAmount()), false, List.of()));
                        }
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
            // 无需额外处理
        }
    }
}
