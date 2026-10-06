package cretae.cookiewyq.rs_create_compat.client.widget;

import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.support.RedstoneMode;
import com.refinedmods.refinedstorage.common.support.containermenu.ClientProperty;
import com.refinedmods.refinedstorage.common.support.containermenu.PropertyTypes;
import com.refinedmods.refinedstorage.common.support.widget.RedstoneModeSideButtonWidget;
import cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode;

import java.util.function.IntSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.ScreenAccessor;

/**
 * 本模组机器界面共用的「红石模式」侧边按钮。
 *
 * <p><b>实现要点</b>：控件本体、贴图（{@code widget/side_button/redstone_mode/*}）、
 * 三态文案（{@code gui.refinedstorage.redstone_mode.*}）与 tooltip 文案全部<b>复用 RS 原版</b>
 * （直接继承 {@link RedstoneModeSideButtonWidget}），不自己发明控件。</p>
 *
 * <p><b>唯一的差异</b>：RS 的侧边按钮在 hover 时把 tooltip 交给
 * {@code AbstractBaseScreen#setDeferredTooltip} 渲染，而本模组界面基于原版
 * {@code AbstractContainerScreen}，父类那一步不会生效。因此这里在绘制之后用
 * {@code Platform.INSTANCE.renderTooltip} 把<b>同一份</b> tooltip 手动渲染出来
 * ——hover 判定与绘制范围同源（都是 {@code Button#isMouseOver}），同一格只渲染一份。</p>
 */
public final class RsccRedstoneModeButton extends RedstoneModeSideButtonWidget {
    private RsccRedstoneModeButton(final ClientProperty<RedstoneMode> property) {
        super(property);
    }

    /**
     * 把红石模式按钮挂到界面右侧外缘（左侧外缘，与 RS 原版 side button 位置一致），
     * 并返回驱动它的属性对象；界面每 tick 用 {@code property.set(ordinal)} 刷新显示值。
     *
     * @param screen      目标界面（内部经 {@code ScreenAccessor} 调用受保护的 {@code addRenderableWidget}）
     * @param containerId 菜单 id，点击时经 {@code handleInventoryButtonClick} 回到服务端
     * @param x           按钮左上角 x（屏幕绝对坐标）
     * @param y           按钮左上角 y（屏幕绝对坐标）
     */
    public static RsccRedstoneModeProperty attach(final Screen screen,
                                                  final IntSupplier containerId,
                                                  final int x,
                                                  final int y) {
        final RsccRedstoneModeProperty property = new RsccRedstoneModeProperty(containerId);
        final RsccRedstoneModeButton button = new RsccRedstoneModeButton(property);
        button.setX(x);
        button.setY(y);
        ((ScreenAccessor) (Object) screen).rscc$addRenderableWidget(button);
        return property;
    }

    /** 默认位置：面板左侧外缘顶端（RS 原版 {@code getSideButtonX/Y} 的口径）。 */
    public static RsccRedstoneModeProperty attach(final AbstractContainerScreen<? extends AbstractContainerMenu> screen) {
        return attach(screen, () -> screen.getMenu().containerId,
            screen.getGuiLeft() - SIZE - 2, screen.getGuiTop() + 6);
    }

    @Override
    public void renderWidget(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        if (this.isHovered) {
            Platform.INSTANCE.renderTooltip(graphics, buildTooltip(), mouseX, mouseY);
        }
    }

    /**
     * 客户端属性：显示值由界面每 tick 从菜单同步数据注入（服务端权威），
     * 点击切换走原版菜单按钮通道（{@code clickMenuButton}）而不是 RS 的属性变更包
     * —— 本模组菜单不是 RS 的 {@code AbstractBaseContainerMenu}，那条包会被丢弃。
     */
    public static final class RsccRedstoneModeProperty extends ClientProperty<RedstoneMode> {
        @Nullable
        private final IntSupplier containerId;

        private RsccRedstoneModeProperty(final IntSupplier containerId) {
            super(PropertyTypes.REDSTONE_MODE, RedstoneMode.IGNORE);
            this.containerId = containerId;
        }

        @Override
        public void setValue(final RedstoneMode value) {
            final MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
            if (gameMode != null && containerId != null) {
                gameMode.handleInventoryButtonClick(containerId.getAsInt(), RsccRedstoneMode.BUTTON_ID);
            }
        }
    }
}
