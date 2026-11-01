package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.ProxyFaceRole;
import cretae.cookiewyq.rs_create_compat.menu.InventoryProxyMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 容器代理器界面：左侧代理对象槽 + 状态折行，右侧六面角色按钮，下方玩家背包与快捷栏。
 *
 * <h2>面说明为什么从「常驻红字」改成「悬停按钮的 tooltip」（第 97 轮用户第 3 条）</h2>
 * <p>第 96 轮把「主内容物面被 RS 读 / 备面仅进 / 对象面唯一」写成界面上<b>一行常驻红字</b>，
 * 为了塞进 224px 宽的面板，超出部分被整行等比缩小（{@code scale = available / width} ≈ 0.4），
 * 于是 2/3 倍 GUI 缩放下就是「一撮蚂蚁字」。用户原话：「字太小了，而且（就算）修复的话，
 * 也不喜欢那行字了。」—— 他要的不是「再放大点」，而是<b>不要这行常驻文字</b>。</p>
 * <p>所以本轮删掉常驻说明，信息挪到<b>悬停六面按钮时</b>的 tooltip
 * （{@code gui.rs_create_compat.inventory_proxy.face_tip.*}，按该面当前角色的 {@code id()} 取键）：
 * 平常界面只剩标题 / 槽位标签 / 状态，需要解释时把鼠标挪到那颗按钮上即可。tooltip 必须在
 * {@code super.render} 之后画，否则会被按钮自己盖住。</p>
 *
 * <h2>几何口径（第 97 轮 B2，Java 与 PNG 必须逐字一致）</h2>
 * <ul>
 *   <li>面板 224 × <b>222</b>（原 248：快捷栏格子底边正好压在最底边框上，看起来像被切掉）；</li>
 *   <li>代理对象槽菜单坐标 (10, 22)，标签 (32, 20) —— 不变；</li>
 *   <li>状态文字 (10, 46)：只能用左侧 98px 窄列，所以必须 {@code font.split} 折行、最多 4 行、行高 9；
 *       <b>任何一行都不得进入 x ≥ 108 的按钮列</b>（否则字会压到按钮上）；</li>
 *   <li>六面按钮 (108, 20)、宽 110、高 16、步长 18（最后一颗底边仍 126）；</li>
 *   <li>玩家背包 3 行菜单槽 y = 136 / 154 / 172，快捷栏 y = 196（背包整体上移 36px，
 *       消掉按钮与背包之间那条 46px 空带；快捷栏底 214，面板底 222 ⇒ 底部留白 8px）。</li>
 * </ul>
 * <p>槽位坐标在 {@link InventoryProxyMenu}，贴图在 {@code assets/rs_create_compat/textures/gui/
 * inventory_proxy.png}（由 {@code tools/gen_inventory_proxy_gui.py} 生成）；三处不一致会被
 * {@code tools/selfcheck_round97_proxy_layout.py} 判负。</p>
 *
 * <h2>为什么必须自己调工具提示（用户第 8 条）</h2>
 * <p>原版 {@code AbstractContainerScreen#render} <b>不会</b>替子类调用
 * {@link AbstractContainerScreen#renderTooltip} —— 它只是给子类留的那一行。不写它，槽位高亮照常出现
 * （{@code super.render} 会画），但<b>悬停不显示物品提示</b>。因此这里覆写 {@code render}：先
 * {@code super.render} 画背景 / 控件 / 槽位高亮，再画面按钮 tooltip，最后<b>无条件</b>调
 * {@code renderTooltip}（不能包在 Shift 里，用户第 4 条说明「按 Shift 显示」是 Jade 的活）。</p>
 *
 * <h2>状态从哪来</h2>
 * <p>全部读菜单数据槽（服务端权威），客户端不查询任何能力；按钮只是发原版
 * {@code ServerboundContainerButtonClickPacket}（{@code clickMenuButton}）。</p>
 */
public class InventoryProxyScreen extends AbstractContainerScreen<InventoryProxyMenu> {
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/inventory_proxy.png");
    private static final String LANG = "gui.rs_create_compat.inventory_proxy.";

    /** 六个面按钮的几何（与 {@link InventoryProxyMenu#directionOf(int)} 的 0..5 一一对应）。 */
    private static final int FACE_X = 108;
    private static final int FACE_Y = 20;
    private static final int FACE_W = 110;
    private static final int FACE_H = 16;
    private static final int FACE_STEP = 18;

    /**
     * 状态文字的左列几何：x=10 起、可用宽 98 ⇒ 最右到 108，正好不越过按钮列（按钮 x=108 起）。
     *
     * <p>为什么这里改成折行（第 97 轮）而不是像旧的常驻说明那样等比缩小：状态句是<b>窄列里</b>的辅助
     * 信息，缩小会重演本轮用户投诉的「蚂蚁字」；折行最多 4 行（36px 高），不会压到 y=136 起的背包。</p>
     */
    private static final int STATUS_X = 10;
    private static final int STATUS_Y = 46;
    private static final int STATUS_WIDTH = 98;
    private static final int STATUS_MAX_LINES = 4;
    private static final int LINE_HEIGHT = 9;

    /**
     * 面 tooltip 的折行宽度。
     *
     * <p>为什么用 {@code font.split} 而不是 {@code renderComponentTooltip(List<Component>)}：
     * 后者按「一个 Component 一行」画，不折行 —— 中文提示约 30 字就是 270px，英文同义句更长，
     * 小窗口下会被挤到屏幕外。这里按 200px 预折行，再用
     * {@code renderTooltip(Font, List<FormattedCharSequence>, ...)} 画，中英文都稳。</p>
     */
    private static final int TIP_WRAP_WIDTH = 200;

    private static final int COLOR_LABEL = 0xFF333333;
    private static final int COLOR_TITLE = 0xFF1F1F1F;

    private final Button[] faceButtons = new Button[6];

    public InventoryProxyScreen(final InventoryProxyMenu menu,
                                final Inventory inventory,
                                final Component title) {
        super(menu, inventory, title);
        this.imageWidth = 224;
        this.imageHeight = 222;
        this.titleLabelY = 6;
        // 玩家背包标签不画（背景里没有对应的窄缝，画出来会压住内容）—— 用超大值把它顶出可见区域。
        this.inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < 6; i++) {
            final int faceIndex = i;
            faceButtons[i] = addRenderableWidget(
                new Button.Builder(faceLabel(i), button -> clickFace(faceIndex))
                    .bounds(leftPos + FACE_X, topPos + FACE_Y + i * FACE_STEP, FACE_W, FACE_H)
                    .build());
        }
    }

    private void clickFace(final int faceIndex) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        // 原版按钮包 → 服务端 InventoryProxyMenu#clickMenuButton；服务端改 BE 后数据槽自动同步回来。
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, InventoryProxyMenu.FACE_BUTTON_BASE + faceIndex);
    }

    private Component faceLabel(final int faceIndex) {
        final ProxyFaceRole role = menu.getFace(faceIndex);
        return Component.translatable(LANG + "face_row",
            Component.translatable(LANG + "direction."
                + InventoryProxyMenu.directionOf(faceIndex).getSerializedName()),
            Component.translatable(LANG + "face." + role.id()));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        for (int i = 0; i < faceButtons.length; i++) {
            if (faceButtons[i] != null) {
                faceButtons[i].setMessage(faceLabel(i));
            }
        }
    }

    /**
     * 原版容器界面的固定三段式：{@code super.render}（背景 + 控件 + 槽位高亮）→ 面按钮 tooltip →
     * <b>手动</b> {@code renderTooltip}（槽位物品提示）。<b>顺序不能颠倒</b>：tooltip 必须画在
     * {@code super.render} 之后，否则会被背景 / 按钮盖住。
     */
    @Override
    public void render(final GuiGraphics guiGraphics,
                       final int mouseX,
                       final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderFaceTip(guiGraphics, mouseX, mouseY);
        // MC 默认不在 render 中调用 renderTooltip，必须手动调用（代理对象槽的物品提示由此触发）。
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics,
                            final float partialTick,
                            final int mouseX,
                            final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, title, 8, titleLabelY, COLOR_TITLE, false);
        guiGraphics.drawString(font, Component.translatable(LANG + "slot"), 32, 20, COLOR_LABEL, false);

        // 状态文字：只能占左侧 98px 窄列（右侧 x≥108 是按钮列），所以折行；最多 4 行。
        // 第 108 轮起不再用一句笼统的「就绪」：用户投诉的是「只显示流体、物品不显示」，
        // 界面必须直说「物品 0 格 / 流体 N 罐」—— 谎报「就绪」正是本界面最不该做的事。
        final Component status = statusLine();
        final List<FormattedCharSequence> lines = font.split(status, STATUS_WIDTH);
        final int drawn = Math.min(lines.size(), STATUS_MAX_LINES);
        for (int line = 0; line < drawn; line++) {
            guiGraphics.drawString(font, lines.get(line), STATUS_X, STATUS_Y + line * LINE_HEIGHT,
                COLOR_LABEL, false);
        }
    }

    /**
     * 状态行：<b>只报服务端算出来的事实</b>（第 108 轮）。
     *
     * <h2>为什么不再用一句「就绪」</h2>
     * <p>用户第 108 条：「把装了储罐升级的那个放进去，用 Jade 查看只显示流体，物品不显示」。
     * 旧界面在这种情况下会画「内容物已就绪」——因为只要<b>任一路</b>可读就算就绪。
     * 于是玩家看到的是「界面说就绪 / Jade 说只有流体」，两句都对、合起来就是谎报。
     * 现在按 {@link cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity.InnerState}
     * 四态分别成句，并且<b>把两路的格数直接写出来</b>：物品 0 格就写 0 格，不让它藏在一个“就绪”里。</p>
     *
     * <h2>为什么「只有流体」要单独一句</h2>
     * <p>「物品 0 格 · 流体 1 罐」本身是事实，但对玩家没有可操作性。<b>只有流体</b>这一态几乎总是
     * 「该容器的物品库存此刻不可读」（反编译核过：精妙背包的物品 provider 以 storage UUID 为门槛，
     * 流体 provider 是懒建 + 缓存 ⇒ 只有流体、没有物品正是这个组合的指纹）。所以这一态额外给一句
     * 提示语，让玩家能对照去处理（把代理对象取出、在背包栏里打开一次）。</p>
     */
    private Component statusLine() {
        if (!menu.hasProxyObject()) {
            return Component.translatable(LANG + "status.empty");
        }
        return switch (menu.innerState()) {
            case BOTH -> Component.translatable(LANG + "status.contents_both",
                menu.innerItemSlots(), menu.innerFluidTanks());
            case ITEM -> Component.translatable(LANG + "status.contents_item_only",
                menu.innerItemSlots());
            case FLUID -> Component.translatable(LANG + "status.contents_fluid_only",
                menu.innerFluidTanks());
            case NONE -> Component.translatable(LANG + "status.no_contents");
        };
    }

    /**
     * 悬停某颗六面按钮时，画该面<b>当前角色</b>的说明（第 97 轮用户第 3 条的替代方案）。
     *
     * <p>为什么按 {@code role.id()} 取键而不是按方向取键：玩家真正想知道的是「这一面现在会做什么」
     * （禁用 / 对象面 / 主内容物面 / 备·仅进面），方向已经在按钮文案里了；同一个方向翻到不同角色，
     * 说明必须跟着变。键集见 {@code ProxyFaceRole}。</p>
     *
     * <p>鼠标坐标是 GUI 空间坐标，按钮 bounds 用的是 {@code leftPos + FACE_X}，所以这里加同一个偏移
     * 判命中 —— 不能直接用控件对象的 {@code isMouseOver}：那需要把 {@code mouseX/mouseY} 再转成
     * 窗口坐标，反而多一层容易错的换算。</p>
     */
    private void renderFaceTip(final GuiGraphics guiGraphics,
                               final int mouseX,
                               final int mouseY) {
        for (int i = 0; i < 6; i++) {
            final int buttonX = leftPos + FACE_X;
            final int buttonY = topPos + FACE_Y + i * FACE_STEP;
            if (mouseX < buttonX || mouseX >= buttonX + FACE_W
                || mouseY < buttonY || mouseY >= buttonY + FACE_H) {
                continue;
            }
            final Component tip = Component.translatable(LANG + "face_tip." + menu.getFace(i).id());
            // 预折行后用 FormattedCharSequence 重载：中英文都不会超出屏幕（见 TIP_WRAP_WIDTH 的注释）。
            guiGraphics.renderTooltip(font, font.split(tip, TIP_WRAP_WIDTH), mouseX, mouseY);
            return;
        }
    }

    /**
     * 代理对象槽里是精妙背包（Sophisticated Backpacks）时，给物品 tooltip 追加一行说明 ——
     * <b>这是「为什么打不开 + 替代路径」，不是功能修复</b>（用户第 11 条）。
     *
     * <h2>为什么这里只能给说明</h2>
     * <p>反编译 SB 1.21.1-3.26.3 的 {@code client/KeybindHandler#sendBackpackOpenOrCloseMessage} 可见：
     * 打开背包的快捷键在<b>客户端</b>先做
     * {@code slotUnderMouse.container instanceof net.minecraft.world.entity.player.Inventory}
     * 判定，只有玩家背包栏 / 副手 / 护甲的槽才继续；代理槽是 {@code SlotItemHandler}（container 是
     * 共享的 {@code SimpleContainer}），直接被跳过。服务端那条路也只按
     * {@code PlayerInventoryHandler} 注册表里的 main / offhand / armor 解析物品 —— 也就是说
     * SB <b>结构上不支持</b>「打开任意容器槽里的背包」。在「不改别人模组、不反射私有字段、
     * 不给 SB 加编译依赖」的约束下，本模组无法替它打开那个菜单；真正的修复需要把 SB 作为
     * 可选编译依赖接入（服务端实现它的 {@code IContextAwareContainer}、客户端发
     * {@code BackpackOpenPayload}），见回报。</p>
     *
     * <p>所以这里只做一件事：让玩家知道「快捷键只认背包栏」以及「内容物面已经能读写袋内物品」，
     * 不必反复试。判据用物品注册名的命名空间（{@code sophisticatedbackpacks}），不 import 对方的类
     * ⇒ SB 不在时本界面照常工作、一条提示都不会出现。</p>
     */
    @Override
    protected List<Component> getTooltipFromContainerItem(final ItemStack stack) {
        final List<Component> lines = super.getTooltipFromContainerItem(stack);
        // 用「菜单槽位对象相等」而不是 hoveredSlot.index：Slot#index 是**容器内**下标，
        // 玩家快捷栏第 1 格的 index 也是 0 —— 用 index 判会把提示错误地挂到快捷栏上
        // （那里按快捷键是能正常打开的）。
        if (hoveredSlot == menu.getSlot(InventoryProxyMenu.PROXY_SLOT_INDEX)
            && isSophisticatedBackpack(stack)) {
            lines.add(Component.translatable(LANG + "hint.sb_hotkey"));
        }
        return lines;
    }

    /** 只按注册命名空间判断（不依赖对方类，SB 缺席时恒为 false）。 */
    private static boolean isSophisticatedBackpack(final ItemStack stack) {
        return !stack.isEmpty()
            && "sophisticatedbackpacks".equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }
}
