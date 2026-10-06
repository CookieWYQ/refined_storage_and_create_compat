package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.menu.SequenceAssemblyExecutorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 序列装配样板库界面：54 格（6×9）大型双箱式布局。
 * 每格放一张总样板，接入网络后每格注册为 EXTERNAL 样板，任意终端可请求其产物。
 */
public class SequenceAssemblyExecutorScreen extends AbstractContainerScreen<SequenceAssemblyExecutorMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    /** 标题颜色：黑色（浅色面板上白字看不清，全模组机器标题统一口径）。 */
    private static final int COLOR_TITLE = 0xFF333333;

    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.RsccRedstoneModeProperty
        rscc$redstoneMode;

    public SequenceAssemblyExecutorScreen(final SequenceAssemblyExecutorMenu menu,
                                          final Inventory inventory,
                                          final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.titleLabelY = 5;
        this.inventoryLabelY = 10000; // 背景已含玩家背包标签
    }

    @Override
    protected void init() {
        super.init();
        // 红石模式（忽略 / 高电平 / 低电平）：复用 RS 原版侧边按钮，挂在面板左侧外缘
        rscc$redstoneMode = cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.attach(this);
    }

    /** 红石模式由服务端权威值驱动显示（控件本体复用 RS 原版，hover 时才手动渲染 tooltip）。 */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (rscc$redstoneMode != null) {
            rscc$redstoneMode.set(menu.rscc$getRedstoneMode().ordinal());
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // AbstractContainerScreen 不自动渲染槽位 tooltip（原版只处理控件登记的 deferred tooltip），
        // 必须手动调用，否则 54 格总样板槽悬停时没有任何物品提示
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
