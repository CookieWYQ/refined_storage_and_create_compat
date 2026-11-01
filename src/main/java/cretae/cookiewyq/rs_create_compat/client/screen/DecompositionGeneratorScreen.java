package cretae.cookiewyq.rs_create_compat.client.screen;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccEnergyBar;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccNumberField;
import cretae.cookiewyq.rs_create_compat.client.widget.RsccTextFit;
import cretae.cookiewyq.rs_create_compat.menu.DecompositionGeneratorMenu;
import cretae.cookiewyq.rs_create_compat.support.DecompositionGeneratorSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 降解室发电机界面（<b>第 123 轮彻底重做</b>）：九宫格贴图背景 + <b>原版熔炉箭头</b> + <b>唯一一条</b>能量条。
 *
 * <h2>用户在骂什么（第 122 轮原话）</h2>
 * <p>「一个字重叠、非常丑、两个条状物、区域太小」。旧界面是纯代码 {@code fill} 画的 210×240 面板：
 * 每帧几十个 {@code fill} 拼出 9×6 槽框，右侧还并排两条竖直条（绿进度条 + RS 能量条），
 * 文字挤在窄条里叠成一团。本轮按契约第 7 节逐条改：</p>
 * <ol>
 *     <li><b>背景</b>：{@code textures/gui/decomposition_generator.png}（182×212，由
 *     {@code tools/make_gui_bg.py} 拿 {@code pic/bg.png} 九宫格拼 + {@code pic/slot.png} 贴槽），
 *     {@code renderBg} 里<b>一次 blit</b> 画完 —— 不再有任何 {@code fill} 面板，也不再逐槽画框；</li>
 *     <li><b>进度</b>：竖直绿条<b>整个删除</b>，改用<b>原版熔炉精灵</b>（{@code minecraft:textures/gui/
 *     container/furnace.png}）：底图 {@code (79,34)} 起 24×16，亮起部分 {@code (176,14)} 起按比例截取。
 *     这是玩家在熔炉里看了十几年的那个箭头，一眼就知道是「加工进度」；</li>
 *     <li><b>能量</b>：<b>整个界面只剩这一条</b> —— {@link RsccEnergyBar}（RS 原版
 *     {@code ProgressWidget} + 同款 1px 黑框），位置 (158, 18) 16×51，与范围充电器 / FE 能量立方同源；</li>
 *     <li><b>文字</b>（<b>第 126 轮按用户原话再删</b>）：用户原话「待机那一行、缓存那一行、
 *     插件提示那一行，这三行文本全部删除，因为这三行都是废话」——因此本界面
 *     <b>不再画任何固定读数行</b>（状态 / 缓存 / 升级全部删除，只剩标题和箭头上方的进度数字）。
 *     保留下来的文字仍走 {@link RsccTextFit#drawFitLine}（放不下整体缩字号，<b>绝不补省略号</b>，
 *     第 104 轮口径，{@code selfcheck_no_ellipsis} 会盯）。</li>
 * </ol>
 *
 * <h2>几何唯一来源：{@link DecompositionGeneratorSpec}</h2>
 * <p>本类<b>不出现任何裸数字坐标</b>：槽位网格、箭头、能量条、文字区全部引用
 * {@code Spec} 的常量（贴图生成脚本的 {@code --slots} 参数与 Menu 的 {@code addSlot} 坐标也是同一份），
 * 于是「背景画在 A 处、槽落在 B 处」这类历史缺陷在结构上不可能再发生。</p>
 *
 * <h2>数据全部来自服务端</h2>
 * <p>电量、进度、升级数、上次发电量全部读 {@link DecompositionGeneratorMenu} 的数据槽
 * （服务端权威）。界面<b>绝不</b>读本地 {@code Config}、也绝不自己算进度 ——
 * 专服上客户端配置可能不同，各算一遍必然分叉（范围充电器踩过同类问题）。</p>
 */
public class DecompositionGeneratorScreen extends AbstractContainerScreen<DecompositionGeneratorMenu> {
    /** 本界面背景贴图（182×212，与 {@link DecompositionGeneratorSpec#PANEL_WIDTH/HEIGHT} 逐像素一致）。 */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        RS_Create_Compat.MODID, "textures/gui/decomposition_generator.png");

    /**
     * 原版熔炉 GUI 贴图（<b>不新增任何贴图</b>：直接引用 vanilla 资源）。
     * <p>精灵坐标也照抄原版 {@code AbstractFurnaceScreen#renderBg}：空箭头 {@code (79,34)}、
     * 亮起箭头 {@code (176,14)}，两张都是 24×16；原版贴图尺寸 256×256。</p>
     */
    private static final ResourceLocation FURNACE_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/container/furnace.png");
    /** 原版 {@code furnace.png} 的画布尺寸（blit 的纹理宽高参数）。 */
    private static final int FURNACE_TEXTURE_SIZE = 256;
    /** 空箭头精灵左上角（原版口径）。 */
    private static final int ARROW_EMPTY_U = 79;
    private static final int ARROW_EMPTY_V = 34;
    /** 亮起箭头精灵左上角（原版口径）。 */
    private static final int ARROW_LIT_U = 176;
    private static final int ARROW_LIT_V = 14;
    /** 进度数字相对箭头顶部的纵向偏移（画在箭头上方一行）。 */
    private static final int ARROW_TEXT_OFFSET_Y = 11;
    /** 进度数字的可用宽度：箭头宽 + 两侧各 4px（数字比箭头窄时居中，宽时缩字号）。 */
    private static final int ARROW_TEXT_MAX_WIDTH = DecompositionGeneratorSpec.ARROW_WIDTH + 8;

    // ==================== 配色（贴图是原版浅灰面板，字用深色） ====================
    /**
     * 次要读数（现在只剩箭头上的进度数字）。
     * <p>第 126 轮删掉三行文本后，原来的 {@code COLOR_TEXT} / {@code COLOR_GOOD} /
     * {@code COLOR_WARN}（状态行的绿 / 橙）一并删除 —— 没有状态行，也就没有它们的用处，
     * 留着就是死常量（{@code selfcheck_round126_decomp_text_removed.py} 会判负）。</p>
     */
    private static final int COLOR_TEXT_DIM = 0xFF555555;
    /** 标题色（与原版容器标题同色）。 */
    private static final int COLOR_TITLE = 0xFF404040;

    public DecompositionGeneratorScreen(final DecompositionGeneratorMenu menu, final Inventory inventory,
                                        final Component title) {
        super(menu, inventory, title);
        this.imageWidth = DecompositionGeneratorSpec.PANEL_WIDTH;
        this.imageHeight = DecompositionGeneratorSpec.PANEL_HEIGHT;
        // 标题位置用原版默认（titleLabelX/titleLabelY = 8/6），不在这里手写数字：
        // 本界面所有几何的唯一来源是 DecompositionGeneratorSpec。
    }

    @Override
    protected void init() {
        super.init();
        // 整个界面唯一的一条「条」：RS 原版 ProgressWidget（1px 黑框 + #2C2C2C 槽底由 renderBg 的
        // drawTrack 画，亮起部分由 widget 自己在 renderWidget 阶段画）。
        // 悬浮数值句用本机既有 lang 键「缓存：x / y FE」，数字走 grouped 千分位。
        addRenderableWidget(RsccEnergyBar.create(leftPos + DecompositionGeneratorSpec.ENERGY_X,
            topPos + DecompositionGeneratorSpec.ENERGY_Y,
            DecompositionGeneratorSpec.ENERGY_HEIGHT,
            () -> menu.getEnergyStored(), () -> menu.getEnergyCapacity(),
            () -> List.of(Component.translatable(
                "gui.rs_create_compat.decomposition_generator.energy",
                RsccNumberField.grouped(menu.getEnergyStored()),
                RsccNumberField.grouped(menu.getEnergyCapacity())))));
    }

    // ==================== 背景：一次 blit + 一条能量条 + 熔炉箭头 ====================

    /**
     * 背景 = <b>整幅贴图一次 blit</b>（面板 / 边框 / 62 个槽框全在 PNG 里，逐像素由
     * {@code tools/make_gui_bg.py} 与 {@code Spec} 对齐），再叠上能量条的槽底与熔炉箭头。
     *
     * <p><b>这里一条 {@code fill} 都没有</b>：旧版把面板、立体边框、每个槽的底框都用 {@code fill}
     * 每帧画一遍，既费 draw call 又是「字重叠 / 丑」的来源（槽框与文字区互不避让）。</p>
     */
    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0,
            DecompositionGeneratorSpec.PANEL_WIDTH, DecompositionGeneratorSpec.PANEL_HEIGHT,
            DecompositionGeneratorSpec.PANEL_WIDTH, DecompositionGeneratorSpec.PANEL_HEIGHT);
        // 能量条的 1px 黑框 + #2C2C2C 槽底（亮起部分由 widget 自己画；顺序必须在贴图之后）
        RsccEnergyBar.drawTrack(guiGraphics, leftPos + DecompositionGeneratorSpec.ENERGY_X,
            topPos + DecompositionGeneratorSpec.ENERGY_Y, DecompositionGeneratorSpec.ENERGY_HEIGHT);
        drawArrow(guiGraphics);
    }

    /**
     * 熔炉式进度箭头：先画<b>空箭头</b>底图，再按比例画<b>亮起</b>部分（与原版
     * {@code AbstractFurnaceScreen#renderBg} 同口径）。
     *
     * <p>{@code filledWidth = 1 + round(fraction * (ARROW_WIDTH - 1))}：进度 0 时也画 1px
     * （原版 {@code getBurnProgress() + 1} 的写法），这样「一个像素都没亮」与「没画箭头」在视觉上
     * 是两种状态；进度 100% 时恰好 24px 铺满，不会溢出到箭头外面。</p>
     */
    private void drawArrow(final GuiGraphics guiGraphics) {
        final int x = leftPos + DecompositionGeneratorSpec.ARROW_X;
        final int y = topPos + DecompositionGeneratorSpec.ARROW_Y;
        guiGraphics.blit(FURNACE_TEXTURE, x, y, ARROW_EMPTY_U, ARROW_EMPTY_V,
            DecompositionGeneratorSpec.ARROW_WIDTH, DecompositionGeneratorSpec.ARROW_HEIGHT,
            FURNACE_TEXTURE_SIZE, FURNACE_TEXTURE_SIZE);
        final int filledWidth = 1 + (int) Math.round(
            progressFraction() * (DecompositionGeneratorSpec.ARROW_WIDTH - 1));
        guiGraphics.blit(FURNACE_TEXTURE, x, y, ARROW_LIT_U, ARROW_LIT_V,
            filledWidth, DecompositionGeneratorSpec.ARROW_HEIGHT,
            FURNACE_TEXTURE_SIZE, FURNACE_TEXTURE_SIZE);
    }

    // ==================== 前景 / 文字 ====================

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawProgressReadout(guiGraphics);
        drawArrowTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 标题：同样走「放不下缩字号」，绝不截断（宽度 = 面板宽 - 两侧留白）。 */
    @Override
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        // 只画标题：本界面没有「玩家背包」小标题（贴图里也没有那道横向分隔槽）
        RsccTextFit.drawFitLine(guiGraphics, font, title, titleLabelX, titleLabelY,
            DecompositionGeneratorSpec.PANEL_WIDTH - DecompositionGeneratorSpec.CONTENT_INSET * 2,
            COLOR_TITLE);
    }

    /*
     * 固定 3 行文字<b>已按用户第 126 轮口径整段删除</b>：
     * 用户原话「待机那一行、缓存那一行、插件提示那一行，这三行文本全部删除，因为这三行都是废话」。
     * 删除后本界面只剩<b>标题</b>（renderLabels）与<b>箭头上方的进度数字</b>
     * （drawProgressReadout，完整句另见 drawArrowTooltip 的 hover tooltip）。
     * tools/selfcheck_round123_decomp_redesign.py 与本轮新增的
     * tools/selfcheck_gui_slot_alignment.py / tools/selfcheck_round126_gui_text_tooltip.py 会盯住这次删除，
     * 防止以后有人「好心」把它们加回来。详见 docs/ROUND126_DECOMP_GENERATOR_CLEANUP.md。
     */

    /**
     * 箭头上方的进度数字（{@code 12 / 20 刻}）：进度条被箭头取代后，精确读数就落在这里。
     * <p>空闲（{@code progressTotal <= 0}）时不画 —— 没有正在处理的这一件，写 {@code 0 / 0}
     * 只会让人以为机器坏了。完整句子仍可在悬停箭头时看到（见 {@link #drawArrowTooltip}）。</p>
     */
    private void drawProgressReadout(final GuiGraphics guiGraphics) {
        if (menu.getProgressTotal() <= 0) {
            return;
        }
        final Component text = Component.translatable(
            "gui.rs_create_compat.decomposition_generator.progress_short",
            menu.getProgress(), menu.getProgressTotal());
        final int centerX = leftPos + DecompositionGeneratorSpec.ARROW_X
            + DecompositionGeneratorSpec.ARROW_WIDTH / 2;
        final int x = centerX - font.width(text) / 2;
        RsccTextFit.drawFitLine(guiGraphics, font, text, x,
            topPos + DecompositionGeneratorSpec.ARROW_Y - ARROW_TEXT_OFFSET_Y,
            ARROW_TEXT_MAX_WIDTH, COLOR_TEXT_DIM);
    }

    /**
     * 悬停熔炉箭头时的 tooltip：<b>进度 + 点进 JEI 的提示</b>（第 129 轮扩充）。
     *
     * <p>第 126 轮这里只在「正在降解」时给一句进度；第 129 轮把箭头接成了 JEI 的可点区域
     * （见 {@code client/DecompositionGeneratorJeiPlugin}），因此空闲时也必须能悬停出提示，
     * 否则玩家不知道这里能点。文案分两行：</p>
     * <ul>
     *     <li>有进度 → {@code progress_short}（只在工作时出现，见 {@link #drawProgressReadout}）；</li>
     *     <li>装了 JEI → {@code jei_hint}（没装 JEI 就不提示 —— 点了什么都不会发生，提示反而误导）。</li>
     * </ul>
     * <p><b>JEI 那侧的默认 tooltip 被显式关掉了</b>（可点区域的 {@code isTooltipEnabled() = false}）：
     * 否则 JEI 会在同一个位置再画一个「点击查看配方」框，与本界面这两个字叠在一起。</p>
     */
    private void drawArrowTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final int x = leftPos + DecompositionGeneratorSpec.ARROW_X;
        final int y = topPos + DecompositionGeneratorSpec.ARROW_Y;
        if (mouseX < x || mouseX >= x + DecompositionGeneratorSpec.ARROW_WIDTH
            || mouseY < y || mouseY >= y + DecompositionGeneratorSpec.ARROW_HEIGHT) {
            return;
        }
        final java.util.List<Component> lines = new java.util.ArrayList<>(2);
        if (menu.getProgressTotal() > 0) {
            lines.add(Component.translatable(
                "gui.rs_create_compat.decomposition_generator.progress_short",
                menu.getProgress(), menu.getProgressTotal()));
        }
        if (jeiPresent()) {
            lines.add(Component.translatable("gui.rs_create_compat.decomposition_generator.jei_hint"));
        }
        if (lines.isEmpty()) {
            return;
        }
        guiGraphics.renderTooltip(font, lines, java.util.Optional.empty(), mouseX, mouseY);
    }

    /**
     * JEI 是否在场（<b>只问加载器</b>，不引用任何 JEI 类型 —— JEI 是本工程可选依赖，
     * 界面在没装 JEI 的客户端也必须能正常打开）。
     */
    private static boolean jeiPresent() {
        return net.neoforged.fml.ModList.get() != null
            && net.neoforged.fml.ModList.get().isLoaded("jei");
    }

    /** 当前这一件的进度比例（{@code 0 <= fraction <= 1}；{@code progressTotal <= 0} 视为 0）。 */
    private double progressFraction() {
        final int total = menu.getProgressTotal();
        if (total <= 0) {
            return 0.0D;
        }
        final double fraction = (double) menu.getProgress() / (double) total;
        return Math.max(0.0D, Math.min(1.0D, fraction));
    }

    /** 调试 / 自检用：界面槽数与菜单声明必须一致。 */
    public static int expectedSlotCount() {
        return DecompositionGeneratorMenu.TOTAL_SLOT_COUNT;
    }
}
