package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.api.autocrafting.PatternOutputRenderingScreen;
import cretae.cookiewyq.rs_create_compat.menu.SequenceAssemblyExecutorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 序列装配样板库界面：54 格（6×9）大型双箱式布局。
 * 每格放一张总样板，接入网络后每格注册为 EXTERNAL 样板，任意终端可请求其产物。
 *
 * <h2>总样板在本界面里「一直显示它合成什么」（用户第 11 项 / 任务 B）</h2>
 * <p>用户原话：<i>「位于序列（装配）样板库的这些总的样板应该时刻保持按下 shift 时候的样子，
 * 就是和那个精致存储它的自动合成仓一样 —— 那里面的那些样板默认都是显示他们具体（合成）什么物品，
 * 也就是按下 shift 之后的状态。」</i></p>
 *
 * <p><b>这条 Shift 分支根本不在 tooltip 里，而在物品图标的渲染里</b>（实证，全部在 RS 自己的代码上）：</p>
 * <ul>
 *     <li>{@code com.refinedmods.refinedstorage.common.autocrafting.PatternRendering#canDisplayOutput}
 *     （sources 第 19~30 行）＝ <b>{@code Screen.hasShiftDown()}</b> 为真，<b>或者</b>当前界面实现了
 *     {@link PatternOutputRenderingScreen} 且它自己的 {@code canDisplayOutput} 返回真；</li>
 *     <li>RS 的 {@code neoforge/mixin/AbstractGuiGraphicsMixin#renderItem}（第 26~32 行）在
 *     {@code GuiGraphics#renderItem} 的 HEAD 处拦一刀：能把产物显示出来时，
 *     就<b>用产物物品把这一格画出来并取消原绘制</b> —— 于是「样板格显示的是它合成的东西」；</li>
 *     <li>RS 的自动合成仓 {@code AutocrafterScreen#canDisplayOutput} 恒返回
 *     {@code getMenu().containsPattern(stack)}（第 272 行）⇒ <b>不需要按 Shift 也常显产物</b>；
 *     自动合成仓管理器与样板编码器同样这么做。</li>
 * </ul>
 * <p>因此本界面要做的只有一件事：<b>实现同一个官方接口</b>（这正是 RS 留给「我这个界面也要常显产物」
 * 的扩展点），判定委托给菜单的 {@code containsPattern} —— 与 RS 的
 * {@code AutocrafterContainerMenu#containsPattern} 逐字同口径（<b>按实例</b>比对，只认本库 54 格里的
 * 那一件）。</p>
 *
 * <h2>为什么其它地方（手上 / JEI / 创造模式栏 / 背包格）一个字都不会变</h2>
 * <p>{@code PatternRendering} 的第二个分支依赖「<b>当前打开的界面</b>是不是
 * {@code PatternOutputRenderingScreen}」：本模组只有<b>本类</b>实现它，且
 * {@code canDisplayOutput} 只对「本菜单 54 格里的那一个实例」返回真 ——
 * 玩家背包里、手上、JEI 里的总样板都是<b>别的实例</b>，判定为假，图标与提示保持原样。
 * 界面关掉之后那个分支更是整个不成立（没有界面 ⇒ 只剩 Shift 那一支）。</p>
 *
 * <h2>排版</h2>
 * <p>本类<b>不新增任何绘制</b>：产物图标由 RS 画在<b>同一个 16×16 槽位矩形</b>里
 * （同 x / y / seed / guiOffset，见 RS 钩子那一行），而数量角标 / 耐久条仍由原版按
 * <b>槽里那张样板</b>（每格最多 1 张）绘制 —— 因此<b>连数字都不会出现</b>，
 * 不存在行数 / 宽度溢出的可能（自检 {@code tools/selfcheck_round55_library_shift_state.py}
 * 锚定「本界面绘制调用与改动前逐字相同」这一点）。</p>
 */
public class SequenceAssemblyExecutorScreen extends AbstractContainerScreen<SequenceAssemblyExecutorMenu>
    implements PatternOutputRenderingScreen {
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

    /**
     * <b>本界面里这一件物品要不要「显示它合成什么」</b>（RS 官方接口
     * {@link PatternOutputRenderingScreen}；判据见类注释）。
     *
     * <p>只认<b>本库这 54 格里的那一个实例</b>（{@link SequenceAssemblyExecutorMenu#containsPattern}，
     * 与 RS {@code AutocrafterContainerMenu#containsPattern} 同一份判据）：玩家背包 / 手上 / JEI 里
     * 的总样板是别的实例，返回 {@code false}，图标保持原样 —— 这就是「只在样板库这一处生效」的落点。</p>
     */
    @Override
    public boolean canDisplayOutput(final ItemStack stack) {
        return menu != null && menu.containsPattern(stack);
    }
}
