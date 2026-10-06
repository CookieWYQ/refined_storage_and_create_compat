package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.CollectionCacheScreen;
import cretae.cookiewyq.rs_create_compat.menu.CollectionCacheMenu;
import cretae.cookiewyq.rs_create_compat.network.SetCollectionMarkerConfigPacket;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
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
