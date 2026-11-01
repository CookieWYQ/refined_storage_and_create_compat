package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.client.widget.ScrollSelectWidget;
import cretae.cookiewyq.rs_create_compat.network.SetRequesterTargetPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 第 131 轮 C 组：**目标接收器选择子界面**（可搜索）。
 *
 * <h2>用户原话</h2>
 * <p>「这一个接收器的选择界面它必须：点击之后会打开一个<b>子界面</b>，那个子界面可以选择、
 * <b>可以搜索</b>，并不是像现在这样子，就一个按钮在点来点去。」</p>
 *
 * <h2>与 {@link StepMachineSelectScreen} 同源</h2>
 * <p>外观（{@link ChildConfigScreen} 的原版九宫格面板）、搜索逻辑（不区分大小写的子串过滤）、
 * 滚轮控件 {@link ScrollSelectWidget} 全部照抄机器选择器 —— 同一个工程里「从列表里挑一个」
 * 只该有一种手感，玩家不必在两处学两种交互。</p>
 *
 * <h2>为什么只带「名字 + 坐标」</h2>
 * <p>候选来自服务端 S2C 快照（{@code SyncRequesterConfigPacket.ReceiverView}），
 * 提交时也只把<b>名字</b>发回服务端（{@link SetRequesterTargetPacket}）。存储层一个字都没改
 * （发送源里存的就是目标名字），服务端仍然按名字在本网络里现解析 ——
 * 因此「候选过期 / 客户端自说自话」都不会把资源投到一台没被选中的机器上。</p>
 */
public class SenderTargetSelectScreen extends ChildConfigScreen {
    private static final String LANG = "gui.rs_create_compat.sender_target_select.";

    /** 面板尺寸（比机器选择器矮一点：没有「步骤 / 配方类型」两行）。 */
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 118;
    private static final int PAD = 10;
    private static final int BOX_H = 16;
    private static final int TITLE_Y = 10;
    /** 当前目标一行（「目标：xxx」）。 */
    private static final int CURRENT_Y = 26;
    /** 搜索框行。 */
    private static final int SEARCH_Y = 44;
    /** 滚轮控件行。 */
    private static final int WIDGET_Y = 66;
    private static final int WIDGET_W = PANEL_W - 2 * PAD;
    /** 两个按钮行。 */
    private static final int ACTION_Y = 90;
    private static final int ACTION_W = 88;

    private static final int COLOR_TITLE = 0xFF333333;
    private static final int COLOR_TEXT = 0xFF404040;
    private static final int COLOR_CURRENT = 0xFF2E5C8A;
    private static final int COLOR_EMPTY = 0xFF9B2020;

    /** 发起请求的那张发送界面菜单 id（提交时随包带回，服务端据此校验归属）。 */
    private final int containerId;
    /** 未经搜索过滤的完整候选（按名字 + 坐标稳定排序，由服务端给出）。 */
    private final List<SyncRequesterConfigPacket.ReceiverView> candidates;
    /** 当前目标名字（用于初始高亮；空串 = 未设置）。 */
    private final String currentTarget;

    private ScrollSelectWidget<SyncRequesterConfigPacket.ReceiverView> picker;
    /** 搜索框：按接收器名做不区分大小写的子串过滤（空查询 = 全部）。 */
    private EditBox searchBox;

    public SenderTargetSelectScreen(final Screen parent, final int containerId,
                                    final List<SyncRequesterConfigPacket.ReceiverView> candidates,
                                    final String currentTarget) {
        super(Component.translatable(LANG + "title"), parent, PANEL_W, PANEL_H);
        this.containerId = containerId;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        this.currentTarget = currentTarget == null ? "" : currentTarget;
    }

    @Override
    protected void init() {
        super.init();
        // 先建候选控件（含完整候选），再建搜索框并立刻套一次过滤（初始查询为空 → 显示全部）
        picker = new ScrollSelectWidget<SyncRequesterConfigPacket.ReceiverView>(font,
            px + PAD, py + WIDGET_Y, WIDGET_W, BOX_H)
            .forOptions(candidates)
            .titled(Component.translatable(LANG + "title"))
            .displaying(view -> Component.literal(view.name()))
            .hinting(Component.translatable(LANG + "hint"))
            .selectable(!candidates.isEmpty());
        picker.setState(indexOfCurrent(candidates));
        addRenderableWidget(picker);

        searchBox = new EditBox(font, px + PAD, py + SEARCH_Y, WIDGET_W, BOX_H,
            Component.translatable(LANG + "search_hint"));
        searchBox.setMaxLength(64);
        searchBox.setTextShadow(false);
        searchBox.setResponder(text -> applyFilter());
        addRenderableWidget(searchBox);
        applyFilter();

        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "confirm"),
            button -> confirmAndReturn())
            .bounds(px + PAD, py + ACTION_Y, ACTION_W, BOX_H).build());
        addRenderableWidget(new Button.Builder(Component.translatable(LANG + "cancel"),
            button -> returnToParent())
            .bounds(px + PAD + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H).build());
    }

    /**
     * 按搜索框内容重算候选（不区分大小写的子串匹配；空查询 = 全部）。
     * <p>过滤后仍尽量保持「当前目标那台」被选中；它被过滤掉时收敛到第一条匹配
     * （玩家搜的就是想选的那台）。候选为空 ⇒ 控件灰显，确定不会写回一个假选项。</p>
     */
    private void applyFilter() {
        if (picker == null) {
            return;
        }
        final String query = searchBox == null
            ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        final List<SyncRequesterConfigPacket.ReceiverView> filtered = new ArrayList<>();
        for (final SyncRequesterConfigPacket.ReceiverView view : candidates) {
            if (query.isEmpty() || view.name().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(view);
            }
        }
        picker.setOptions(filtered);
        picker.setState(indexOfCurrent(filtered));
        picker.selectable(!filtered.isEmpty());
    }

    /** 当前目标在候选里的位置（找不到回退 0：与机器选择器同一口径）。 */
    private int indexOfCurrent(final List<SyncRequesterConfigPacket.ReceiverView> options) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).name().equals(currentTarget)) {
                return i;
            }
        }
        return 0;
    }

    /** 确定：把名字提交给服务端（服务端校验存在性 / 唯一性），然后返回发送界面。 */
    private void confirmAndReturn() {
        final SyncRequesterConfigPacket.ReceiverView selected =
            picker == null ? null : picker.getSelected();
        if (selected != null) {
            PacketDistributor.sendToServer(new SetRequesterTargetPacket(containerId, selected.name()));
        }
        returnToParent();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawString(font, title, px + PAD, py + TITLE_Y, COLOR_TITLE, false);
        // 当前目标：没有序号可翻，只显示「现在投给谁」（未设置时红字，玩家一眼看到要先选）
        final Component current = currentTarget.isEmpty()
            ? Component.translatable(LANG + "current.none")
            : Component.translatable(LANG + "current", currentTarget);
        guiGraphics.drawString(font, current, px + PAD, py + CURRENT_Y,
            currentTarget.isEmpty() ? COLOR_EMPTY : COLOR_CURRENT, false);
        // 第 126 轮：这一行是运行时拼出来的（「目标：xxx」/「仍未选择」），名字可能很长而画布只有
        // 一行 ⇒ 悬停时用**同一个 current** 原样再显示一遍。不在 tooltip 里另拼一份名字：
        // 那样「未设置」的两种分支口径会分叉。
        if (isHovering(px + PAD, py + CURRENT_Y, PANEL_W - 2 * PAD, 12, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, current, mouseX, mouseY);
        }
        // 候选为空：必须给一句「为什么空」（否则一个灰掉的滚轮控件像坏了）
        if (candidates.isEmpty()) {
            guiGraphics.drawString(font, Component.translatable(LANG + "empty"),
                px + PAD, py + WIDGET_Y + 4, COLOR_EMPTY, false);
        }
        renderTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 手动渲染 tooltip：判定范围与控件绘制范围完全一致（子界面里 MC 不会替我们调）。 */
    private void renderTooltips(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        if (searchBox != null && isHovering(searchBox.getX(), searchBox.getY(),
            searchBox.getWidth(), searchBox.getHeight(), mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font, Component.translatable(
                LANG + "search.tip", picker == null ? 0 : picker.getOptions().size(),
                candidates.size()), mouseX, mouseY);
            return;
        }
        if (picker != null && picker.inBounds(mouseX, mouseY)) {
            picker.renderTooltip(guiGraphics, mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD, py + ACTION_Y, ACTION_W, BOX_H, mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "confirm.tip"), mouseX, mouseY);
            return;
        }
        if (isHovering(px + PAD + ACTION_W + 4, py + ACTION_Y, ACTION_W, BOX_H,
            mouseX, mouseY)) {
            RsccTooltipLayers.renderAttached(guiGraphics, font,
                Component.translatable(LANG + "cancel.tip"), mouseX, mouseY);
        }
    }

    private static boolean isHovering(final int x, final int y, final int w, final int h,
                                      final double mouseX, final double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirmAndReturn();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
