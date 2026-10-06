package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.StorageInfo;
import com.refinedmods.refinedstorage.common.util.IdentifierUtil;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.McGui;
import cretae.cookiewyq.rs_create_compat.menu.IntermediateCacheMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccSharedCache;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 中间产物缓存仓界面：{@value cretae.cookiewyq.rs_create_compat.block.entity.IntermediateCacheBlockEntity#DISK_SLOTS}
 * 格盘位（3 行 × 9 列 = 一个小箱子的槽数）+ 盘位说明 / 用量两行文字 + 玩家背包。
 *
 * <h2>它是什么（第 7 轮语义更正）</h2>
 * <p>本仓<b>不是</b>「自带一个小箱子容量的存储」，而是「有一个<b>小箱子大小的磁盘存放空间</b>」：
 * 容量完全由插进来的磁盘提供，没插盘时共享池就是空的。界面文案与 tooltip 必须说清这一点，
 * 绝不能再写出「自带 1728 件」这种数字（本轮已全部改写）。</p>
 *
 * <h2>不新画背景贴图（工程硬规则）</h2>
 * 面板用工程既有的原版九宫格绘制（{@link McGui#panel}，取自 MC 自带精灵
 * {@code minecraft:recipe_book/overlay_recipe}），槽位框用 {@link McGui#slotFrame}（原版观感，Java 绘制）。
 * 盘位从 4 格扩到 27 格后，面板只<b>按九宫格拉伸</b>（176×198），不新增任何 PNG。
 *
 * <h2>文字与 tooltip</h2>
 * 所有 {@code drawString} 一律传 {@code false}（无阴影）；自绘元素（盘位说明 / 用量两行）的 tooltip
 * <b>手动渲染</b>（{@code AbstractContainerScreen} 不会自动渲染），hover 判定与绘制范围严格同源。
 * 槽位内的物品（磁盘）由原版渲染其自身 tooltip —— RS 的磁盘 tooltip 本来就带「已用 / 总量」。
 */
public class IntermediateCacheScreen extends AbstractContainerScreen<IntermediateCacheMenu> {
    private static final String LANG = "gui.rs_create_compat.intermediate_cache.";

    /** 标题颜色：黑色（浅色面板上白字看不清，全模组机器标题统一口径）。 */
    private static final int COLOR_TITLE = 0xFF333333;
    /** 文案颜色（与原版标签带同款深灰）。 */
    private static final int COLOR_TEXT = 0xFF404040;

    /** 盘位说明行（Menu 坐标）：在盘位区上方，行高 9px，不与任何槽位重叠。 */
    private static final int LABEL_X = 8;
    private static final int LABEL_Y = 21;
    /** 盘位用量行（Menu 坐标）：在盘位区下方、物品栏标签（y=104）之上。 */
    private static final int STATUS_X = 8;
    private static final int STATUS_Y = 93;
    /** 两行文字的 hover 判定宽度 / 行高（判定与绘制同源）。 */
    private static final int TEXT_W = 160;
    private static final int TEXT_H = 9;

    public IntermediateCacheScreen(final IntermediateCacheMenu menu,
                                   final Inventory inventory,
                                   final Component title) {
        super(menu, inventory, title);
        this.imageWidth = IntermediateCacheMenu.PANEL_W;
        this.imageHeight = IntermediateCacheMenu.PANEL_H;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        // 「物品栏」标签：在用量行之下的留白带里（与 Menu 的玩家背包 y=116 保持 12px 常规间距）
        this.inventoryLabelY = 104;
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        McGui.panel(guiGraphics, leftPos, topPos, imageWidth, imageHeight);
        // 槽位框：与 Menu 槽位坐标严格同源（背景精灵坐标 = Menu 坐标 − 1）
        for (final Slot slot : menu.slots) {
            McGui.slotFrame(guiGraphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, COLOR_TITLE, false);
        // 玩家背包标签：原版键 container.inventory（本页是原版三行式面板，保留原版观感）
        guiGraphics.drawString(font, Component.translatable("container.inventory"),
            inventoryLabelX, inventoryLabelY, COLOR_TEXT, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "disk.label"),
            LABEL_X, LABEL_Y, COLOR_TITLE, false);
        guiGraphics.drawString(font, diskStatusLine(), STATUS_X, STATUS_Y, COLOR_TEXT, false);
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 槽位 tooltip（磁盘自己的已用 / 总量）：AbstractContainerScreen 不自动渲染，必须手动调用
        renderTooltip(guiGraphics, mouseX, mouseY);
        renderCacheTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 自绘元素 tooltip（盘位说明行 / 用量行；判定范围与绘制范围严格一致）。 */
    private void renderCacheTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 判定用原版 AbstractContainerScreen#isHovering（内部把鼠标坐标换算成菜单坐标），
        // 因此与 renderLabels 里按菜单坐标绘制的那两行严格同源，不会自己再算一遍左上角。
        final boolean onText = isHovering(LABEL_X, LABEL_Y, TEXT_W, TEXT_H, mouseX, mouseY)
            || isHovering(STATUS_X, STATUS_Y, TEXT_W, TEXT_H, mouseX, mouseY);
        if (!onText) {
            return;
        }
        final List<Component> always = new ArrayList<>(2);
        final List<RsccTooltipLayers.Line> layered = new ArrayList<>(6);
        always.add(Component.translatable(LANG + "disk.label"));
        // 用量/状态 = 数值信息（Ctrl 层）；容量关系与使用说明 = 机制（Shift 层）。
        layered.add(RsccTooltipLayers.ctrl(diskStatusLine()));
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tip.capacity")));
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tip.share")));
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tip.slots")));
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tip.carry")));
        layered.add(RsccTooltipLayers.shift(Component.translatable(LANG + "tip.accept")));
        RsccTooltipLayers.render(guiGraphics, font, always, layered, mouseX, mouseY);
    }

    /**
     * 盘位用量文案（客户端只读镜像，与 RS 自己的磁盘 tooltip 同一数据源：
     * {@code StorageContainerItemHelper#getInfo + ClientStorageRepository}，请求自带 1 次/秒限频）。
     * <p>四种状态各自说清楚：一块盘都没放（= 共享池为空）/ 有盘但存储尚未初始化（同步中）/
     * 已用 + 总量 / 无限盘给 ∞。多块盘时按「已用相加、容量相加」，只要有一块无限盘整体就报「无限」
     * （那块盘本来就装得下一切）。</p>
     * <p><b>没有「自带容量」这一项</b>（第 7 轮把容量语义改回用户口径）：本仓只是「一个小箱子大小的
     * 磁盘存放空间」，容量全部来自这些盘，因此文案里只有盘的数字。</p>
     */
    private Component diskStatusLine() {
        int disks = 0;
        int pending = 0;
        long stored = 0L;
        long capacity = 0L;
        boolean unlimited = false;
        for (int index = IntermediateCacheMenu.DISK_START; index < IntermediateCacheMenu.PLAYER_START
            && index < menu.slots.size(); index++) {
            final ItemStack disk = menu.getSlot(index).getItem();
            if (disk.isEmpty()) {
                continue;
            }
            disks++;
            final Optional<StorageInfo> info = RsccSharedCache.isStorageDisk(disk)
                ? RefinedStorageApi.INSTANCE.getStorageContainerItemHelper()
                    .getInfo(RefinedStorageApi.INSTANCE.getClientStorageRepository(), disk)
                : Optional.empty();
            if (info.isEmpty() || (info.get().stored() == 0 && info.get().capacity() == 0)) {
                pending++;
                continue;
            }
            stored += Math.max(0L, info.get().stored());
            if (info.get().capacity() <= 0) {
                unlimited = true;
            } else {
                capacity += info.get().capacity();
            }
        }
        if (disks == 0) {
            return Component.translatable(LANG + "disk.none");
        }
        if (pending > 0) {
            return Component.translatable(LANG + "disk.pending");
        }
        // 文案只有盘的数字：共享池容量 = 所有盘容量之和（本仓不自带容量）
        return unlimited
            ? Component.translatable(LANG + "disk.usage.unlimited", IdentifierUtil.format(stored))
            : Component.translatable(LANG + "disk.usage",
                IdentifierUtil.format(stored), IdentifierUtil.format(capacity));
    }
}
