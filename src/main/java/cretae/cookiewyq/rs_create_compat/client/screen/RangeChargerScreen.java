package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.RangeChargerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 范围充电器界面：能量条 + 三轴范围（输入框编辑 / 加减按钮），无玩家背包。
 */
public class RangeChargerScreen extends AbstractContainerScreen<RangeChargerMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/range_charger.png");

    private final EditBox[] rangeBoxes = new EditBox[3];

    public RangeChargerScreen(final RangeChargerMenu menu, final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 96;
        this.inventoryLabelY = 10000; // 无背包，隐藏默认玩家标签
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // 三行范围：标签 + 输入框 + [-] [+]
        addRangeRow(16, 0);
        addRangeRow(33, 1);
        addRangeRow(50, 2);
    }

    private void addRangeRow(final int y, final int axis) {
        final EditBox box = new EditBox(font, leftPos + 63, topPos + y + 1, 44, 12, Component.literal("range"));
        box.setMaxLength(3);
        box.setValue(Integer.toString(rangeValue(axis)));
        box.setResponder(text -> {
            if (!text.matches("\\d*")) {
                box.setValue(text.replaceAll("[^\\d]", ""));
            }
        });
        rangeBoxes[axis] = box;
        addRenderableWidget(box);
        addRenderableWidget(new Button.Builder(Component.literal("-"), btn -> {
            final int v = Math.max(1, rangeValue(axis) - 1);
            rangeBoxes[axis].setValue(Integer.toString(v));
            sendButton(axis * 2);
        }).bounds(leftPos + 110, topPos + y, 20, 14).build());
        addRenderableWidget(new Button.Builder(Component.literal("+"), btn -> {
            final int v = Math.min(100, rangeValue(axis) + 1);
            rangeBoxes[axis].setValue(Integer.toString(v));
            sendButton(axis * 2 + 1);
        }).bounds(leftPos + 132, topPos + y, 20, 14).build());
    }

    private int rangeValue(final int axis) {
        return switch (axis) {
            case 0 -> menu.getRangeX();
            case 1 -> menu.getRangeY();
            default -> menu.getRangeZ();
        };
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter
            for (int axis = 0; axis < 3; axis++) {
                if (rangeBoxes[axis] != null && rangeBoxes[axis].isFocused()) {
                    applyRange(axis);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applyRange(final int axis) {
        final EditBox box = rangeBoxes[axis];
        if (box == null) {
            return;
        }
        final int value;
        try {
            value = Math.max(1, Math.min(100, Integer.parseInt(box.getValue())));
        } catch (final NumberFormatException e) {
            return;
        }
        box.setValue(Integer.toString(value));
        final net.minecraft.core.BlockPos pos = menu.getChargerPos();
        if (pos != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new cretae.cookiewyq.rs_create_compat.network.SetRangePacket(pos, axis, value));
        }
    }

    private void sendButton(final int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick, final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 176, 96);
        // 能量条（宽 14，高 40）
        final int energy = menu.getEnergy();
        final int maxEnergy = Math.max(1, menu.getMaxEnergy());
        final int barHeight = (int) (40.0 * energy / maxEnergy);
        guiGraphics.fill(leftPos + 8, topPos + 20 + (40 - barHeight), leftPos + 22, topPos + 20 + 40, 0xFFD03000);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        drawLabel(guiGraphics, 16, "X");
        drawLabel(guiGraphics, 33, "Y");
        drawLabel(guiGraphics, 50, "Z");
        // 能量数值与说明（底部，不超界面：96 高，放 78/86）
        guiGraphics.drawString(font, Component.literal(menu.getEnergy() + " / " + menu.getMaxEnergy() + " FE"),
            30, 78, 0xA0A0A0, false);
        guiGraphics.drawString(font, Component.translatable("gui.rs_create_compat.range_charger.hint"),
            30, 88, 0x808080, false);
    }

    private void drawLabel(final GuiGraphics guiGraphics, final int y, final String text) {
        guiGraphics.drawString(font, Component.literal(text), 30, y + 3, 0xFFFFFF, false);
    }

    /** 输入框与服务端值同步（未聚焦时）。 */
    private void syncRangeBoxes() {
        final int[] values = {menu.getRangeX(), menu.getRangeY(), menu.getRangeZ()};
        for (int axis = 0; axis < 3; axis++) {
            final EditBox box = rangeBoxes[axis];
            if (box != null && !box.isFocused() && !box.getValue().equals(Integer.toString(values[axis]))) {
                box.setValue(Integer.toString(values[axis]));
            }
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float partialTick) {
        syncRangeBoxes();
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
