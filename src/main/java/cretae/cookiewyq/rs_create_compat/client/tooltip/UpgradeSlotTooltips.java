package cretae.cookiewyq.rs_create_compat.client.tooltip;

import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 空升级槽的 tooltip（渲染方式照搬 RS 原版）：
 * 第一行为"&lt;空升级槽位&gt;"标题，随后每行一个<b>该机器允许放入</b>的升级图标 + 名称（含单种上限）。
 * RS 原版（AbstractBaseScreen.getUpgradeTooltip）：仅当手上未携带物品且槽内为空时显示。
 * 背景与文字由 MC 原生 TooltipRenderUtil + ClientTooltipComponent 手动绘制
 * （GuiGraphics.renderTooltipInternal 为 private，无法直接调用，故照搬其绘制流程）。
 */
public final class UpgradeSlotTooltips {
    /** RS 堆叠升级的物品 path。 */
    private static final String STACK_UPGRADE_PATH = "stack_upgrade";
    /** 装填器家族「本机堆叠升级」效果行（先 %s = 本机升级个数，后 %s = 本机每格上限）。 */
    public static final String LOADER_STACK_EFFECT_KEY =
        "gui.rs_create_compat.schematic_loader.upgrade.stack.effect";

    private UpgradeSlotTooltips() {
    }

    /**
     * 构建空升级槽 tooltip：<b>常显</b>（不需要按 Shift）。
     *
     * <h2>为什么取消 Shift 分层（2026-10-05 用户要求）</h2>
     * <p>用户原话：<i>「我这个模组添加的所有机器它的升级槽就是插件槽那里不要使按下 shift 查看详情，
     * 全部模仿精致存储原版那样子的长显就是一直显示 tooltip 来告诉玩家这里可以放入什么，不然太割裂了」</i>。</p>
     * <p>已反编译核对 <b>RS 原版</b>（{@code AbstractBaseScreen#getUpgradeTooltip}）：它是
     * 「空槽标题 + 逐个允许的升级（图标 + 名称 + 单种上限）」，<b>完全不带任何按键修饰</b>。
     * 因此这里照搬原版：一律常显。这也符合用户另一条硬要求「原版已有的东西照搬，不要割裂」。</p>
     */
    public static List<ClientTooltipComponent> build(final UpgradeSlot slot) {
        final List<ClientTooltipComponent> lines = new ArrayList<>();
        // 标题行：<空升级槽位>，样式照搬 RS createTranslationAsHeading（DARK_GRAY）
        lines.add(ClientTooltipComponent.create(
            Component.literal("<")
                .append(Component.translatable("gui.rs_create_compat.upgrade_slot.empty"))
                .append(">")
                .withStyle(ChatFormatting.DARK_GRAY)
                .getVisualOrderText()));
        for (final ItemStack stack : slot.allowedStacks()) {
            final int max = slot.maxPerUpgrade(pathOf(stack));
            lines.add(new UpgradeItemTooltipComponent(stack, max));
        }
        return lines;
    }

    /** 兼容旧调用：使用默认允许列表（全部三种升级）；同样常显。 */
    public static List<ClientTooltipComponent> build() {
        final List<ClientTooltipComponent> lines = new ArrayList<>();
        lines.add(ClientTooltipComponent.create(
            Component.literal("<")
                .append(Component.translatable("gui.rs_create_compat.upgrade_slot.empty"))
                .append(">")
                .withStyle(ChatFormatting.DARK_GRAY)
                .getVisualOrderText()));
        for (final ItemStack stack : UpgradeSlot.getAllowedUpgradeStacks()) {
            lines.add(new UpgradeItemTooltipComponent(stack, maxFor(stack)));
        }
        return lines;
    }

    /**
     * 按「允许的升级 path 列表」构建空槽提示。
     * <p>给那些以原生 {@link net.minecraft.world.inventory.Slot} 包装容器（而非 {@link UpgradeSlot}
     * 实例）的机器使用 —— 判定与绘制都走同一份允许列表，保证「所有可放插件的位置」都有提示。</p>
     */
    public static List<ClientTooltipComponent> build(final List<String> allowedPaths) {
        final List<ClientTooltipComponent> lines = new ArrayList<>();
        lines.add(ClientTooltipComponent.create(Component.literal("<")
            .append(Component.translatable("gui.rs_create_compat.upgrade_slot.empty"))
            .append(">")
            .withStyle(ChatFormatting.DARK_GRAY)
            .getVisualOrderText()));
        for (final ItemStack stack : UpgradeSlot.stacksFor(allowedPaths)) {
            lines.add(new UpgradeItemTooltipComponent(stack, UpgradeSlot.maxForPath(pathOf(stack))));
        }
        return lines;
    }

    /** 在鼠标旁渲染空升级槽 tooltip（按该槽位所属机器的允许列表）。 */
    public static void render(final UpgradeSlot slot,
                              final GuiGraphics graphics,
                              final Font font,
                              final int mouseX,
                              final int mouseY) {
        renderBody(build(slot), graphics, font, mouseX, mouseY);
    }

    /** 在鼠标旁渲染空升级槽 tooltip（按「允许的升级 path 列表」；供容器包装槽使用）。 */
    public static void render(final List<String> allowedPaths,
                              final GuiGraphics graphics,
                              final Font font,
                              final int mouseX,
                              final int mouseY) {
        renderBody(build(allowedPaths), graphics, font, mouseX, mouseY);
    }

    /** 兼容旧调用：默认允许列表。 */
    public static void render(final GuiGraphics graphics, final Font font, final int mouseX, final int mouseY) {
        renderBody(build(), graphics, font, mouseX, mouseY);
    }

    /**
     * 升级槽里已放入<b>堆叠升级</b>时：渲染「物品自身 tooltip + 本机实际作用」并返回 true。
     * <p><b>为什么要补这一行</b>：升级物品自身的 tooltip（RS 原版）只描述通用语义，玩家真正要知道的是
     * 「插在<b>本机</b>之后每格能到多少」；与本工程其它机器同一口径
     * （见 {@code CollectionCacheScreen#upgradeEffectLine}）。
     * <p><b>必须由 Screen 的 {@code renderTooltip} 手动调用并提前 return</b>：本模组 GUI 不会自动渲染
     * tooltip；若先让 super 画一遍物品 tooltip 再画这一份，同一格会出现两份叠在一起。
     *
     * @param stackUpgradeCount 本机插件槽里的堆叠升级个数（由菜单按本机统计，服务端 / 客户端一致）
     * @param slotCapacity      本机每格上限（与方块实体同一公式）
     * @return 是否已渲染（false = 该槽不是堆叠升级，调用方继续走默认 tooltip）
     */
    public static boolean renderFilledStackUpgradeEffect(final net.minecraft.world.inventory.Slot slot,
                                                        final int stackUpgradeCount,
                                                        final int slotCapacity,
                                                        final GuiGraphics graphics,
                                                        final Font font,
                                                        final int mouseX,
                                                        final int mouseY) {
        final ItemStack stack = slot.getItem();
        // 只对「升级槽」里的堆叠升级生效：资源库存格里恰好躺着一个升级物品时不该显示本机效果
        if (!(slot instanceof UpgradeSlot) || stack.isEmpty() || !STACK_UPGRADE_PATH.equals(pathOf(stack))) {
            return false;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        final List<Component> lines = new ArrayList<>(stack.getTooltipLines(
            net.minecraft.world.item.Item.TooltipContext.of(minecraft.level),
            minecraft.player,
            minecraft.options.advancedItemTooltips
                ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                : net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        // 「本机实际作用」是数值型附加信息 → CTRL 层（默认隐藏，按 Ctrl 才出现）
        RsccTooltipLayers.append(lines, RsccTooltipLayers.ctrl(
            Component.translatable(LOADER_STACK_EFFECT_KEY, stackUpgradeCount, slotCapacity)));
        graphics.renderTooltip(font, lines, stack.getTooltipImage(), mouseX, mouseY);
        return true;
    }

    private static void renderBody(final List<ClientTooltipComponent> components,
                                   final GuiGraphics graphics,
                                   final Font font,
                                   final int mouseX,
                                   final int mouseY) {
        if (components.isEmpty()) {
            return;
        }
        int width = 0;
        int height = 0;
        for (final ClientTooltipComponent component : components) {
            width = Math.max(width, component.getWidth(font));
            height += component.getHeight();
        }
        final int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        final int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + width > screenWidth) {
            x = mouseX - 12 - width;
        }
        if (y + height > screenHeight) {
            y = mouseY - 12 - height;
        }
        // 背景画在 z=400；文字必须同样画在 z=400，否则会被背景盖住（照搬 MC renderTooltip 的 z 提升）
        TooltipRenderUtil.renderTooltipBackground(graphics, x, y, width, height, 400);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, 400.0D);
        final MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        final Matrix4f matrix = graphics.pose().last().pose();
        int lineY = y;
        for (final ClientTooltipComponent component : components) {
            component.renderText(font, x, lineY, matrix, bufferSource);
            component.renderImage(font, x, lineY, graphics);
            lineY += component.getHeight();
        }
        bufferSource.endBatch();
        graphics.pose().popPose();
    }

    /** 自动合成上限 1，其余 4（默认列表专用）。 */
    private static int maxFor(final ItemStack stack) {
        final String path = pathOf(stack);
        return "autocrafting_upgrade".equals(path) ? 1 : UpgradeSlot.MAX_PER_UPGRADE;
    }

    private static String pathOf(final ItemStack stack) {
        final net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }
}
