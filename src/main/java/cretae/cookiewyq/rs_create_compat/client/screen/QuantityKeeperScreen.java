package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.QuantityKeeperMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 定量保持器界面：标记槽 + 插件槽 + 目标数量（可输入框编辑/加减按钮）+ 销毁开关。
 */
public class QuantityKeeperScreen extends AbstractContainerScreen<QuantityKeeperMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/quantity_keeper.png");

    private EditBox targetBox;

    public QuantityKeeperScreen(final QuantityKeeperMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 210; // 176 主界面 + 34 右侧升级栏（仿 RS）
        this.imageHeight = 166;
        this.inventoryLabelY = 10000; // 玩家背包标签已含在背景中
    }

    @Override
    protected void init() {
        super.init();
        // 目标数量输入框（直接编辑数字，回车应用）
        targetBox = new EditBox(font, leftPos + 100, topPos + 22, 60, 14, Component.literal("target"));
        targetBox.setMaxLength(10);
        targetBox.setValue(Integer.toString(menu.getTargetAmount()));
        targetBox.setResponder(text -> {
            // 实时校验：仅数字
            if (!text.matches("\\d*")) {
                targetBox.setValue(text.replaceAll("[^\\d]", ""));
            }
        });
        addRenderableWidget(targetBox);
        // 目标数量 -/+ 按钮（放在输入框右侧）
        addRenderableWidget(new Button.Builder(Component.literal("-"), btn -> {
            targetBox.setValue(Integer.toString(Math.max(1, menu.getTargetAmount() - 1)));
            sendButton(0);
        }).bounds(leftPos + 162, topPos + 20, 18, 14).build());
        addRenderableWidget(new Button.Builder(Component.literal("+"), btn -> {
            targetBox.setValue(Integer.toString(menu.getTargetAmount() + 1));
            sendButton(1);
        }).bounds(leftPos + 182, topPos + 20, 18, 14).build());
        // 销毁开关按钮
        addRenderableWidget(new Button.Builder(
            Component.translatable("gui.rs_create_compat.quantity_keeper.destroy"),
            btn -> sendButton(2))
            .bounds(leftPos + 100, topPos + 44, 60, 16)
            .build());
    }

    /** 输入框回车：发送数值到服务端。 */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (targetBox != null && targetBox.isFocused()
            && keyCode == 257 /* Enter */ || keyCode == 335 /* Numpad Enter */) {
            applyTarget();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applyTarget() {
        if (targetBox == null) {
            return;
        }
        final int value;
        try {
            value = Math.max(1, Integer.parseInt(targetBox.getValue()));
        } catch (final NumberFormatException e) {
            return;
        }
        targetBox.setValue(Integer.toString(value));
        final net.minecraft.core.BlockPos pos = menu.getKeeperPos();
        if (pos != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetQuantityTargetPacket(pos, value));
        }
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

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        // 标记标签（左侧，与标记槽对齐）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.marker"),
            8, 8, 0xA0A0A0, false);
        // 目标数量标签（输入框上方）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.target_label"),
            100, 6, 0xA0A0A0, false);
        // 升级栏标签（右侧独立栏，仿 RS 原版）
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.quantity_keeper.upgrades"),
            176, 100, 0xA0A0A0, false);
        // 升级信息（销毁按钮下方）
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.quantity_keeper.speed", menu.getSpeedUpgradeCount()),
            100, 66, 0xA0A0A0, false);
        guiGraphics.drawString(font,
            Component.translatable("gui.rs_create_compat.quantity_keeper.autocrafting",
                menu.hasAutocraftingUpgrade() ? "✓" : "✗"),
            100, 78, 0xA0A0A0, false);
    }

    /** 输入框内容变化后同步到 Menu（客户端展示），回车/失焦由父类处理。 */
    @Override
    public void tick() {
        super.tick();
        // 同步服务端目标数量到输入框（外部变化时）
        final int serverTarget = menu.getTargetAmount();
        if (targetBox != null && !targetBox.getValue().equals(Integer.toString(serverTarget))) {
            // 仅在未聚焦时更新，避免打断输入
            if (!targetBox.isFocused()) {
                targetBox.setValue(Integer.toString(serverTarget));
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 空升级槽悬停提示：升级槽（slot 1..6 或 UpgradeSlot 实例）为空时显示可放入的升级种类。 */
    @Override
    protected void renderTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        super.renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredSlot != null && hoveredSlot.getItem().isEmpty()
            && (hoveredSlot instanceof cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot
                || hoveredSlot.index >= 1 && hoveredSlot.index <= 6)) {
            guiGraphics.renderComponentTooltip(font,
                cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.getEmptyTooltip(),
                mouseX, mouseY);
        }
    }
}
