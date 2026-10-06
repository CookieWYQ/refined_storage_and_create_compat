package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.QuantityKeeperScreen;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 集成：定量保持器 —— 允许把物品从 JEI 直接拖进标记槽（作为保持数量/销毁的样板）。
 */
@JeiPlugin
public class QuantityKeeperJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "quantity_keeper_jei");
    }

    @Override
    public void registerGuiHandlers(final IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(QuantityKeeperScreen.class, new MarkerGhostHandler());
    }

    /** ghost 放置目标：仅标记槽（index 0）；支持物品与流体（含以流体承载的气体）。 */
    private static final class MarkerGhostHandler
        implements mezz.jei.api.gui.handlers.IGhostIngredientHandler<QuantityKeeperScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(final QuantityKeeperScreen screen,
                                                   final ITypedIngredient<I> typedIngredient,
                                                   final boolean doStart) {
            final I ingredient = typedIngredient.getIngredient();
            // 物品 / 流体（气体同样以流体标识承载）两种 JEI 成分都支持：直接标记
            final boolean isItem = ingredient instanceof ItemStack;
            final boolean isFluid = ingredient instanceof net.neoforged.neoforge.fluids.FluidStack;
            if (!isItem && !isFluid) {
                return List.of();
            }
            final List<Target<I>> targets = new ArrayList<>();
            final QuantityKeeperMenu menu = screen.getMenu();
            final Slot markerSlot = menu.getSlot(0);
            targets.add(new Target<I>() {
                @Override
                public Rect2i getArea() {
                    return new Rect2i(
                        screen.getGuiLeft() + markerSlot.x - 1,
                        screen.getGuiTop() + markerSlot.y - 1,
                        18, 18);
                }

                @Override
                public void accept(final I accepted) {
                    if (accepted instanceof ItemStack stack) {
                        PacketDistributor.sendToServer(
                            new cretae.cookiewyq.rs_create_compat.network.SetQuantityMarkerPacket(
                                menu.containerId, stack));
                    } else if (accepted instanceof net.neoforged.neoforge.fluids.FluidStack fluid
                        && !fluid.isEmpty()) {
                        final net.minecraft.resources.ResourceLocation id =
                            net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid());
                        PacketDistributor.sendToServer(
                            new cretae.cookiewyq.rs_create_compat.network.SetQuantityFluidMarkerPacket(
                                menu.containerId,
                                cretae.cookiewyq.rs_create_compat.block.entity.QuantityKeeperBlockEntity.FORM_FLUID,
                                id,
                                cretae.cookiewyq.rs_create_compat.support.MarkerEntry.encodeComponents(
                                    fluid.getComponentsPatch())));
                    }
                }
            });
            return targets;
        }

        @Override
        public void onComplete() {
            // 无需额外处理
        }
    }
}
