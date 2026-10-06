package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.AdvancedQuantityKeeperScreen;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedQuantityKeeperMenu;
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
 * JEI 集成：高级物品定量保持器 —— 允许把物品/流体从 JEI 直接拖进 4 个 ghost 标记槽之一。
 * <p>每个目标对应一行配置槽：拖入 {@link ItemStack} → 物品标记；拖入 {@code FluidStack} → 流体/气体标记。</p>
 */
@JeiPlugin
public class AdvancedQuantityKeeperJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "advanced_quantity_keeper_jei");
    }

    @Override
    public void registerGuiHandlers(final IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(AdvancedQuantityKeeperScreen.class, new MarkerGhostHandler());
    }

    /** ghost 放置目标：4 个标记槽，每个落到对应行。 */
    private static final class MarkerGhostHandler
        implements mezz.jei.api.gui.handlers.IGhostIngredientHandler<AdvancedQuantityKeeperScreen> {

        @Override
        public <I> List<Target<I>> getTargetsTyped(final AdvancedQuantityKeeperScreen screen,
                                                   final ITypedIngredient<I> typedIngredient,
                                                   final boolean doStart) {
            final I ingredient = typedIngredient.getIngredient();
            final boolean isItem = ingredient instanceof ItemStack;
            final boolean isFluid = ingredient instanceof net.neoforged.neoforge.fluids.FluidStack;
            if (!isItem && !isFluid) {
                return List.of();
            }
            final List<Target<I>> targets = new ArrayList<>();
            final AdvancedQuantityKeeperMenu menu = screen.getMenu();
            for (int row = 0; row < AdvancedQuantityKeeperMenu.MARKER_SLOTS; row++) {
                final Slot markerSlot = menu.getSlot(row);
                final int r = row;
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
                                new cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperMarkerPacket(
                                    menu.containerId, r, stack));
                        } else if (accepted instanceof net.neoforged.neoforge.fluids.FluidStack fluid
                            && !fluid.isEmpty()) {
                            final ResourceLocation id =
                                net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid());
                            PacketDistributor.sendToServer(
                                new cretae.cookiewyq.rs_create_compat.network.SetAdvKeeperFluidMarkerPacket(
                                    menu.containerId, r,
                                    cretae.cookiewyq.rs_create_compat.support.KeeperSlotConfig.FORM_FLUID,
                                    id,
                                    cretae.cookiewyq.rs_create_compat.support.MarkerEntry.encodeComponents(
                                        fluid.getComponentsPatch())));
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
