package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.AdvancedCollectionCacheScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.AreaStripPanel;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 集成：归流缓存仓「匹配区」ghost 槽支持把 JEI 里的<b>物品 / 流体 / 气体</b>直接拖入标记。
 * <p>与序列装配样板终端 / 定量保持器同款写法（{@code IGhostIngredientHandler}）。拖入的条目通过
 * {@link SetCollectionMarkerConfigPacket} 交给服务端权威写入（服务端负责去重与 NBT 归一化），
 * 未指定数量时按拖入堆叠数量（流体按 mB）标记，匹配规则默认两项都关。</p>
 *
 * <h2>第 125 轮加的两件事（用户「其次呢没有 JEI」的正解）</h2>
 * <ol>
 *     <li><b>JEI 给侧栏让位</b>（契约 §3.3）：高级仓界面右边多了一条常驻「区域条」，
 *     它必须被算进 GUI 的占用区，否则 JEI 的物品列表会压在上面。这里用
 *     {@link IGuiContainerHandler#getGuiExtraAreas} 把 {@link AreaStripBounds#get()} 原样报给 JEI，
 *     由 JEI <b>自己</b>挪开 —— 我们一行「手动挪 JEI」的代码都没有，那种写法必然与 JEI 自己的布局打架。
 *     注意返回的是<b>屏幕坐标</b>（JEI 原话：{@code "the space that the gui takes up besides the normal
 *     rectangle"}）；侧栏不可见时 {@code getGuiExtraAreas} 返回空表（验收反例：矩形为空不注册）。</li>
 *     <li><b>拖动目标跟着侧栏走</b>（契约 §4.5）：侧栏切到「标记网格」时，拖入目标换成那 12 个格子，
 *     {@code accept()} 直接由 {@link AreaStripPanel#acceptGhostIngredient} 写到「这个区域」的标记表上
 *     （背包里的物品走既有 5 参 {@code ACTION_ADD}；JEI 的幽灵栈走第 125 轮新包
 *     {@code AreaMarkAddItemPacket}，绝不允许「不在背包就提示一句然后什么也不做」）。</li>
 * </ol>
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
        // 第 125 轮：区域条（GUI 右边的常驻侧栏）也要算进 GUI 占用区 ⇒ JEI 自动让位。
        // 注册用 addGuiContainerHandler(Class<? extends T>, IGuiContainerHandler<T>)；
        // 传子类 Class 即可命中基类注册表（JEI 内部按 isAssignableFrom 查找，契约 §3.3）。
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
            // 第 125 轮（契约 §4.5）：侧栏的「标记网格」开着时，目标换成那 12 个格子。
            // 这是**同一个** handler 的分支，不是第二个 handler —— JEI 对同一界面只取一个
            // ghost handler，再注册一个会覆盖掉匹配区那 48 格（那种「修一个坏一个」的改法被契约否掉了）。
            final AreaStripPanel strip = stripOf(screen);
            if (strip != null && strip.isMarkGridOpen()) {
                final List<Rect2i> cells = strip.markGridCellAreas();
                final List<Target<I>> gridTargets = new ArrayList<>(cells.size());
                for (final Rect2i cell : cells) {
                    gridTargets.add(new Target<I>() {
                        @Override
                        public Rect2i getArea() {
                            return cell; // 已是屏幕坐标（与侧栏绘制同源）
                        }

                        @Override
                        public void accept(final I accepted) {
                            if (accepted instanceof ItemStack stack && !stack.isEmpty()) {
                                strip.acceptGhostIngredient(stack);
                            } else if (accepted instanceof FluidStack fluid && !fluid.isEmpty()) {
                                strip.acceptGhostFluid(fluid);
                            }
                        }
                    });
                }
                return gridTargets;
            }
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

        /**
         * 这个界面是不是「高级归流缓存仓」、且侧栏已经建好（{@code init()} 之后才会有实例）。
         * <p>写成独立方法是为了让 {@code getTargetsTyped} 的主干保持可读（它本来就已经很长）。</p>
         */
        @Nullable
        private static AreaStripPanel stripOf(@Nullable final CollectionCacheScreen screen) {
            if (!(screen instanceof AdvancedCollectionCacheScreen advanced)) {
                return null;
            }
            return advanced.strip();
        }

        @Override
        public void onComplete() {
            // 无需额外处理
        }
    }
}
