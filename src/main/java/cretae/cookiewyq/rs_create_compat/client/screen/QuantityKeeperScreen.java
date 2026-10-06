package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.GhostMarkerRenderer;
import cretae.cookiewyq.rs_create_compat.client.widget.RepeatButton;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * 定量保持器界面：标记槽（物品/流体样板，不消耗物品）+ 插件槽 + 目标数量（[-]输入框[+]，shift 加速）
 * + 销毁过量开关 + 自动合成开关（未装自动合成升级时禁用）。
 */
public class QuantityKeeperScreen extends AbstractContainerScreen<QuantityKeeperMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/quantity_keeper.png");

    // 文字颜色：本界面背景是浅色（bg.png 铺底），白色字看不清 → 统一用工程深色常量
    // （与高级定量保持器界面 AdvancedQuantityKeeperScreen 完全同一套取值）
    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;

    private EditBox targetBox;
    private Button destroyButton;
    private Button autoCraftButton;
    private RepeatButton minusButton;
    private RepeatButton plusButton;
    /** 红石模式控件（复用 RS 原版侧边按钮；显示值每 tick 由菜单同步数据刷新）。 */
    private cretae.cookiewyq.rs_create_compat.client.widget.RsccRedstoneModeButton.RsccRedstoneModeProperty
        rscc$redstoneMode;
    /** 上次本地修改目标数量的 tick（快速点击时防止服务端同步值覆盖输入框，造成"反应慢"）。 */
    private long lastLocalEditTick = -1000;
    /** S2C：直接标记的流体/气体注册名（null = 无直接流体标记）。 */
    @Nullable
    private ResourceLocation syncedFluidMarkerId;
    /** S2C：直接标记的流体/气体数据组件 NBT。 */
    private net.minecraft.nbt.CompoundTag syncedFluidMarkerNbt = new net.minecraft.nbt.CompoundTag();

    public QuantityKeeperScreen(final QuantityKeeperMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 166;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
        this.titleLabelY = 4; // 标题在顶部，与下方"标记物品/流体"标签分离
    }

    /** S2C：接收「直接标记」的流体/气体（供 ghost 槽绘制流体图标 + tooltip）。 */
    public void setFluidMarker(@Nullable final ResourceLocation id, @Nullable final net.minecraft.nbt.CompoundTag nbt) {
        this.syncedFluidMarkerId = id;
        this.syncedFluidMarkerNbt = nbt == null ? new net.minecraft.nbt.CompoundTag() : nbt;
    }

    @Override
    protected void init() {
        super.init();
        // 目标数量：[-] 输入框 [+] 一字排开（输入框居中于 ±）。整列统一上移：目标 y20 / 销毁 y40 / 自动合成 y60，
        // 勾叉按钮与其文字同排对齐（文字 y = 按钮 y + 4），避免与下方玩家背包(起点 y85)重叠。
        final EditBox box = new EditBox(font, leftPos + 96, topPos + 21, 42, 12, Component.literal("target"));
        box.setMaxLength(10);
        box.setValue(Integer.toString(menu.getTargetAmount()));
        box.setResponder(text -> {
            if (!text.matches("\\d*")) {
                box.setValue(text.replaceAll("\\D", ""));
            }
            // 仅用户聚焦输入时才标记"本地编辑"并应用；初始化/同步 setValue 不标记，保证服务端值能回填
            if (box.isFocused()) {
                lastLocalEditTick = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0;
                applyTargetIfValid(); // 输入即应用
            }
        });
        targetBox = box;
        addRenderableWidget(box);
        // ± 按钮点击立即生效（shift ±5），按住持续重复（setValue → responder → SetQuantityTargetPacket）
        minusButton = new RepeatButton(leftPos + 76, topPos + 19, 18, 14, Component.literal("-"), btn -> {
            final int step = hasShiftDown() ? 5 : 1;
            markLocalEdit();
            targetBox.setValue(Integer.toString(Math.max(0, targetValue() - step)));
            applyTargetIfValid();
        });
        addRenderableWidget(minusButton);
        plusButton = new RepeatButton(leftPos + 140, topPos + 19, 18, 14, Component.literal("+"), btn -> {
            final int step = hasShiftDown() ? 5 : 1;
            markLocalEdit();
            targetBox.setValue(Integer.toString(Math.max(0, targetValue() + step)));
            applyTargetIfValid();
        });
        addRenderableWidget(plusButton);
        // 销毁过量开关（勾 = 开/绿，叉 = 关/红），与标签同排
        destroyButton = new Button.Builder(Component.literal("§a✓"), btn -> sendButton(2))
            .bounds(leftPos + 76, topPos + 39, 16, 14)
            .build();
        addRenderableWidget(destroyButton);
        // 自动合成开关：未装自动合成升级时禁用（灰色 ✗），装上后才可切换
        autoCraftButton = new Button.Builder(Component.literal("§a✓"), btn -> {
            if (menu.hasAutocraftingUpgrade()) {
                sendButton(3);
            }
        }).bounds(leftPos + 76, topPos + 59, 16, 14).build();
        addRenderableWidget(autoCraftButton);
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

    /** 目标数量当前值（优先输入框，其次服务端同步值）。 */
    private int targetValue() {
        if (targetBox != null) {
            final String text = targetBox.getValue();
            if (text.matches("\\d+")) {
                return Integer.parseInt(text);
            }
        }
        return menu.getTargetAmount();
    }

    /** 输入框回车：应用数值。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (targetBox != null && targetBox.isFocused()
            && (keyCode == 257 || keyCode == 335)) { // Enter / Numpad Enter
            applyTargetIfValid();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 标记一次本地编辑（防止服务端同步值覆盖输入框）。 */
    private void markLocalEdit() {
        lastLocalEditTick = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0;
    }

    /** 输入框内容合法时立即发送到服务端应用。 */
    private void applyTargetIfValid() {
        if (targetBox == null || !targetBox.getValue().matches("\\d+")) {
            return;
        }
        final int value;
        try {
            value = Integer.parseInt(targetBox.getValue());
        } catch (final NumberFormatException e) {
            return;
        }
        if (value < 0) {
            return;
        }
        // 允许发送 0：0 = 「未标记」（服务端同样按 0 = 未标记处理）
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new cretae.cookiewyq.rs_create_compat.network.SetQuantityTargetPacket(menu.containerId, value));
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        // 背景已包含全部槽位（由 make_gui_bg.py 生成，与 Menu 槽位一一对应）
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 210, 166);
    }

    /**
     * 标记槽（ghost，index 0）按标记形态绘制：物品标记（含水桶等容器物品）一律画物品图标；
     * 只有<b>显式</b>流体/气体标记（JEI 拖入流体）才画流体贴图。
     */
    @Override
    protected void renderSlot(final GuiGraphics guiGraphics, final Slot slot) {
        if (slot.index == 0 && menu.getMarkerForm() >= 1) {
            final FluidStack fluid = syncedFluidMarkerId == null
                ? FluidStack.EMPTY
                : GhostMarkerRenderer.toFluidStack(syncedFluidMarkerId, 1L);
            if (!fluid.isEmpty()) {
                GhostMarkerRenderer.renderFluid(guiGraphics, slot.x, slot.y, fluid);
                return;
            }
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 标题 / 标签统一深色（浅色背景上才看得清）；shadow 一律 false
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 目标数量标签：右端对齐到 [-] 按钮左边缘(x=76)，随实际字宽动态左移，避免与标记槽/按钮重叠
        final net.minecraft.network.chat.Component targetLabel = Component.translatable(
            "gui.rs_create_compat.quantity_keeper.target_label");
        guiGraphics.drawString(font, targetLabel, Math.max(0, 76 - font.width(targetLabel)), 21, COLOR_TEXT, false);
        // 目标数量单位：物品 = 个，流体/气体 = mB（跟随标记形态，放在 [+] 右侧、不碰插件栏）
        guiGraphics.drawString(font,
            Component.translatable(menu.getMarkerForm() >= 1
                ? "gui.rs_create_compat.quantity_keeper.unit_mb"
                : "gui.rs_create_compat.quantity_keeper.unit_item"),
            162, 22, COLOR_TEXT, false);
        // 堵塞提示（内部存在与当前标记不匹配的资源：已停止输出，但内容仍可被取出）
        if (menu.isMarkerBlocked()) {
            guiGraphics.drawString(font,
                Component.translatable("gui.rs_create_compat.quantity_keeper.blocked"),
                96, 74, 0xFF5555, false);
        }
        // 销毁过量开关标签（与勾叉按钮 y39 同排，文字位于按钮右侧）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.destroy_label"),
            96, 42, COLOR_TEXT, false);
        // 自动合成开关标签（与勾叉按钮 y59 同排）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.autocraft_label"),
            96, 62, COLOR_TEXT, false);
    }

    /** 输入框内容变化后同步到 Menu（客户端展示），在 render 中处理（tick 为 final 不可覆写）。 */
    private void syncTargetBox() {
        final int serverTarget = menu.getTargetAmount();
        if (targetBox != null && !targetBox.getValue().equals(Integer.toString(serverTarget))) {
            // 仅在未聚焦且非快速本地点击期间更新，避免打断输入/覆盖按钮连续调整
            if (!targetBox.isFocused()
                && (minecraft == null || minecraft.level == null
                    || minecraft.level.getGameTime() - lastLocalEditTick >= 10)) {
                targetBox.setValue(Integer.toString(serverTarget));
            }
        }
        if (destroyButton != null) {
            destroyButton.setMessage(Component.literal(menu.isDestroyOverflow() ? "§a✓" : "§c✗"));
        }
        if (autoCraftButton != null) {
            final boolean hasUpgrade = menu.hasAutocraftingUpgrade();
            autoCraftButton.active = hasUpgrade; // 无自动合成升级 → 禁用
            if (hasUpgrade) {
                autoCraftButton.setMessage(Component.literal(menu.isAutoCraftEnabled() ? "§a✓" : "§c✗"));
            } else {
                autoCraftButton.setMessage(Component.literal("§7✗"));
            }
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        // 按住 ± 持续触发（tick 为 final 不可覆写，改由渲染帧驱动）
        if (minusButton != null) {
            minusButton.tickRepeat();
        }
        if (plusButton != null) {
            plusButton.tickRepeat();
        }
        syncTargetBox();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // ± 按钮 shift 加速提示：hover 判定直接使用按钮自身的命中范围（与绘制范围严格一致）
        // 以下本模组附加的控制说明统一走唯一实现 RsccTooltipLayers：默认收起，按住 Shift 才展开。
        if (minusButton != null && minusButton.isMouseOver(mouseX, mouseY)
            || plusButton != null && plusButton.isMouseOver(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font,
                Component.translatable("gui.rs_create_compat.quantity_keeper.step"), mouseX, mouseY);
            return;
        }
        // 自动合成开关禁用提示：未装自动合成升级时悬停说明原因（同样以按钮自身命中范围为准）
        if (autoCraftButton != null && !autoCraftButton.active
            && autoCraftButton.isMouseOver(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    "gui.rs_create_compat.quantity_keeper.autocraft_disabled_tooltip"), mouseX, mouseY);
            return;
        }
        // 「销毁过量」开关 tooltip（本模组 GUI 不会自动渲染 tooltip，必须手动调用）：
        // 明确「开 = 只销毁超出目标的那部分 / 关 = 一个都不销毁」，并说明同资源多台时的仲裁规则。
        // 三行分两层：开关语义 = Shift（机制）；当前状态 + 多台仲裁 = Ctrl（参数 / 规则细节）。
        if (destroyButton != null && destroyButton.isMouseOver(mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.render(
                guiGraphics, font, java.util.List.of(),
                java.util.List.of(
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.shift(
                        Component.translatable(
                            "gui.rs_create_compat.quantity_keeper.destroy_label")),
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.ctrl(
                        Component.translatable(menu.isDestroyOverflow()
                            ? "gui.rs_create_compat.quantity_keeper.destroy_on.tip"
                            : "gui.rs_create_compat.quantity_keeper.destroy_off.tip")),
                    cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.ctrl(
                        Component.translatable(
                            "gui.rs_create_compat.quantity_keeper.destroy_shared.tip"))),
                mouseX, mouseY);
            return;
        }
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 标记槽用法说明 + 空升级槽悬停提示 + 堵塞说明。 */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        // 堵塞提示（命中范围与绘制的文字行严格一致）
        if (menu.isMarkerBlocked() && isHovering(96, 74, 60, 9, mouseX, mouseY)) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    "gui.rs_create_compat.quantity_keeper.blocked.tooltip"), mouseX, mouseY);
            return;
        }
        if (hoveredSlot == null) {
            return;
        }
        // 标记槽（index 0，空槽）：直接标记的流体/气体优先显示流体 tooltip；否则说明标记的设置方式
        if (hoveredSlot.index == 0 && hoveredSlot.getItem().isEmpty()) {
            if (menu.getMarkerForm() >= 1 && syncedFluidMarkerId != null) {
                GhostMarkerRenderer.renderFluidTooltip(guiGraphics, font, syncedFluidMarkerId, 0L,
                    false, null, mouseX, mouseY);
                return;
            }
            cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers.renderAttached(
                guiGraphics, font, Component.translatable(
                    "gui.rs_create_compat.quantity_keeper.marker_tooltip"), mouseX, mouseY);
            return;
        }
        // 空升级槽（index 1..6）：列出本机可放入的升级与上限（仅手持为空时，样式照搬 RS 原版）
        if (hoveredSlot.getItem().isEmpty()
            && hoveredSlot.index >= 1 && hoveredSlot.index <= 6
            && menu.getCarried().isEmpty()) {
            cretae.cookiewyq.rs_create_compat.client.tooltip.UpgradeSlotTooltips.render(
                guiGraphics, font, mouseX, mouseY);
        }
    }
}
