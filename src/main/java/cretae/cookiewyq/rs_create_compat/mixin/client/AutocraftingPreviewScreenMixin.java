package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.preview.TreePreview;
import com.refinedmods.refinedstorage.common.autocrafting.preview.AutocraftingPreviewContainerMenu;
import com.refinedmods.refinedstorage.common.autocrafting.preview.AutocraftingPreviewScreen;
import com.refinedmods.refinedstorage.common.autocrafting.preview.AutocraftingRequest;
import cretae.cookiewyq.rs_create_compat.client.IntermediateReuseClient;
import cretae.cookiewyq.rs_create_compat.client.PreviewIntermediateHint;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.AutocraftingPreviewMenuAccessor;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.AutocraftingRequestAccessor;
import cretae.cookiewyq.rs_create_compat.network.RequestIntermediateReusePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RS「自动合成预览」界面（下单前那张确认表）上的<b>纯显示层</b>补充：
 * 当「优先使用中间产物」开关为<b>开</b>、且这次预览对应的确实是本模组的序列装配配方时，
 * 在标题行右端多画一枚<b>过渡件（中间产物）图标 + 一行短文案</b>。
 *
 * <h2>用户要解决的问题（误导）</h2>
 * <p>那张表列的「所需物」= RS 样板登记的 ingredient，也就是本模组的<b>起步原料</b>
 * （例如黑曜石粉）。开关打开后，执行仓其实是<b>优先拿网络里已有的中间产物当起点</b>的，
 * 于是「只显示起步原料」会让玩家以为还缺起步原料。</p>
 *
 * <h2>为什么不把中间产物做成样板的必需 ingredient（红线）</h2>
 * <p>用户已明确拍板：语义是「<b>有中间产物就优先用；没有就从头合成</b>」。若把它登记成必需 ingredient，
 * 没有中间产物时那份预览会变成缺料、<b>「开始」按钮直接置灰</b>，把回退路径打断。
 * 所以这里一个字节都不碰样板构成，只在界面上<b>多说一句</b>。</p>
 *
 * <h2>为什么是这两个注入点</h2>
 * <ul>
 *     <li>{@code init} / {@code render} 都<b>声明在 {@link AutocraftingPreviewScreen} 自身</b>
 *     （本工程硬规则：只能注入目标类自身声明的方法）。</li>
 *     <li>{@code init} 末尾拉一次开关快照：那个界面<b>没有服务端菜单</b>
 *     （{@code ClientPlatformUtil.openCraftingPreview} 是纯客户端开界面），
 *     所以开关只能靠一条只读 C2S 包现拉（见 {@link RequestIntermediateReusePacket}）。</li>
 *     <li>{@code render} 末尾画提示：这与 RS 的列表 / 树渲染完全无关，
 *     只是在标题行这块<b>唯一整段空闲</b>的区域上多画 16×16 图标 + 一行小字。</li>
 * </ul>
 *
 * <h2>几何（为什么不会压到任何东西）</h2>
 * <p>面板 254×249（{@code imageWidth/imageHeight}），标题行相对 y = 7（RS {@code titleLabelY = 7}）。
 * 该行右侧<b>没有任何原生控件</b>：增量按钮在相对 y = 20（x 从 80 起）、数量输入框在 y = 51、
 * Max 按钮在 (184,48)、缩放 / 切换样式按钮在 y = 81、预览区在 y = 97、动作按钮在 y = 222
 * （逐项核对过 RS 的 {@code AbstractAmountScreen} 与 {@code AutocraftingPreviewScreen#init}）。
 * 图标画在相对 y = 4（占 4..19，正好落在增量按钮上方），文字画在 y = 7（占 7..16）。
 * 横向从右往左排：右界 = 面板右内缘 − 7，左界 = 标题文字右缘 + 4，<b>装不下就截断</b>
 * （补「…」），连图标都放不下就整行不画 —— 于是「永不压标题、永不越出面板」由构造保证。</p>
 *
 * <h2>开关为关时</h2>
 * <p>第一句就 {@code return}：不画任何像素、不算任何东西，界面<b>逐字不变</b>
 * （与变更前完全一致，也不做多余的配方查询）。</p>
 */
@Mixin(AutocraftingPreviewScreen.class)
public abstract class AutocraftingPreviewScreenMixin {
    /** 提示文案（本模组键；开关为关时根本不取它）。 */
    private static final String RSCC_LANG = "gui.rs_create_compat.autocrafting_preview.intermediate_reuse";
    /** 图标边长（原版物品图标固定 16×16）。 */
    private static final int RSCC_ICON = 16;
    /** 图标与文字之间、以及图标列与标题文字之间的间距。 */
    private static final int RSCC_GAP = 2;
    /** 标题左内边距（RS {@code AbstractBaseScreen#titleLabelX = 7}）。 */
    private static final int RSCC_TITLE_X = 7;
    /** 提示与标题文字之间至少留的间距（保证两段文字永不粘连 / 重叠）。 */
    private static final int RSCC_TITLE_GAP = 4;
    /** 提示与面板右内缘的距离（与 RS 的 7px 左边距对称）。 */
    private static final int RSCC_RIGHT = 7;
    /** 图标相对面板顶的 y（占 4..19，紧贴增量按钮行 y=20 之上）。 */
    private static final int RSCC_ICON_Y = 4;
    /** 文字相对面板顶的 y（与 RS 标题同基线 7）。 */
    private static final int RSCC_TEXT_Y = 7;
    /** 可用宽度不足「图标 + 间距 + 这么多像素的文字」时整行不画（宁可少说，也绝不越界）。 */
    private static final int RSCC_MIN_TEXT = 24;
    /** 文字颜色：面板底色是浅灰（198,198,198），用深绿以区别于标题的深灰、并暗示「省料 / 复用」。 */
    private static final int RSCC_COLOR = 0x2E7D32;
    /** 文案装不下时的截断符号。 */
    private static final String RSCC_ELLIPSIS = "…";

    /**
     * 界面初始化末尾：把「优先复用中间产物」开关的权威值拉过来（只读）。
     *
     * <p>为什么不用 ContainerData：这个界面的菜单在主路径上是<b>纯客户端</b>造的
     * （没有服务端菜单、没有同步 id），搭不上那条通道。</p>
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void rscc$requestIntermediateReuse(final CallbackInfo ci) {
        PacketDistributor.sendToServer(new RequestIntermediateReusePacket());
    }

    /**
     * 渲染末尾：开关为开、且这条预览确实是本模组的序列装配配方时，画「过渡件图标 + 一行短文案」。
     *
     * <p>放在 {@code render} 的 TAIL（而不是 {@code renderBg}）：{@code renderLabels} 已经把标题画完，
     * 这里画的是标题行右侧那块空白，永远不会盖住标题（横向左界按标题实际宽度算出来）。</p>
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void rscc$renderIntermediateHint(final GuiGraphics graphics, final int mouseX, final int mouseY,
                                             final float partialTicks, final CallbackInfo ci) {
        if (!IntermediateReuseClient.enabled()) {
            return; // 开关为关：一个像素都不画，界面逐字不变
        }
        final ItemStack transitional = rscc$transitional();
        if (transitional.isEmpty()) {
            return; // 不是本模组的配方 / 分不清是哪一条：不画
        }
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        final Font font = Minecraft.getInstance().font;
        final int panelLeft = screen.getGuiLeft();
        final int panelTop = screen.getGuiTop();
        final int right = panelLeft + screen.getXSize() - RSCC_RIGHT;
        final int left = panelLeft + RSCC_TITLE_X + font.width(screen.getTitle()) + RSCC_TITLE_GAP;
        final int available = right - left;
        if (available < RSCC_ICON + RSCC_GAP + RSCC_MIN_TEXT) {
            return; // 标题太长 / 屏幕太窄：宁可不画，也绝不压标题、绝不越出面板
        }
        final String shown = rscc$fit(font, Component.translatable(RSCC_LANG).getString(),
            available - RSCC_ICON - RSCC_GAP);
        if (shown.isEmpty()) {
            return;
        }
        final int textX = right - font.width(shown);
        graphics.renderItem(transitional, textX - RSCC_GAP - RSCC_ICON, panelTop + RSCC_ICON_Y);
        graphics.drawString(font, shown, textX, panelTop + RSCC_TEXT_Y, RSCC_COLOR, false);
    }

    /**
     * 当前预览对应的过渡件；不属于本模组 / 预览还没算好 / 处于报错态 ⇒ 空栈。
     *
     * <p><b>只在「真的列出了所需物」的两种状态下画</b>（{@code SUCCESS} / {@code MISSING_RESOURCES}）：
     * 报错态（循环、请求过大、已取消、不可用）界面里根本没有所需物列表，也就不存在「只显示起步原料」
     * 这个误导 —— 那几种状态保持原样，不加噪音。</p>
     */
    @Unique
    private ItemStack rscc$transitional() {
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        final AbstractContainerMenu menu = screen.getMenu();
        if (!(menu instanceof AutocraftingPreviewContainerMenu previewMenu)) {
            return ItemStack.EMPTY;
        }
        final AutocraftingRequest request =
            ((AutocraftingPreviewMenuAccessor) previewMenu).rscc$getCurrentRequest();
        if (request == null) {
            return ItemStack.EMPTY;
        }
        final AutocraftingRequestAccessor accessor = (AutocraftingRequestAccessor) (Object) request;
        final Preview preview = accessor.rscc$getPreview();
        final TreePreview treePreview = accessor.rscc$getTreePreview();
        final PreviewType type = preview != null
            ? preview.type()
            : (treePreview == null ? null : treePreview.type());
        if (type != PreviewType.SUCCESS && type != PreviewType.MISSING_RESOURCES) {
            return ItemStack.EMPTY;
        }
        return PreviewIntermediateHint.transitionalFor(Minecraft.getInstance().level, accessor.rscc$getResource());
    }

    /** 装不下就在保留「…」的前提下截断（保证绘制宽度 ≤ maxWidth）。 */
    @Unique
    private static String rscc$fit(final Font font, final String text, final int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        final int budget = maxWidth - font.width(RSCC_ELLIPSIS);
        return budget <= 0 ? "" : font.plainSubstrByWidth(text, budget) + RSCC_ELLIPSIS;
    }
}
