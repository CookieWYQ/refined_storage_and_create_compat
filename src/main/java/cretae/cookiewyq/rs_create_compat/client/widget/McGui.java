package cretae.cookiewyq.rs_create_compat.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * MC 原生观感的 GUI 绘制工具（全工程统一入口，避免各界面各写一套「手搓边框」）。
 * <ul>
 *     <li>{@link #panel}：用原版九宫格精灵 {@code minecraft:recipe_book/overlay_recipe}
 *     （其 {@code .mcmeta} 声明 {@code nine_slice border = 4}）拼出<b>任意尺寸</b>的面板 ——
 *     圆角、1px 黑描边、白色高光、右下暗边、{@code #C6C6C6} 底全部来自 MC 自带贴图，
 *     这就是玩家要求的「MC 自带的那种背景」。自绘 PNG 交付后把对应 {@code blit} 换掉即可。</li>
 *     <li>{@link #slotFrame}：用户原始素材那种槽位框（上 / 左各 1px 暗灰 + 下 / 右各 1px 白高光 +
 *     {@code #8B8B8B} 槽底，占地 18×18、内容区 16×16）。只在「没有交付背景贴图、又需要显示槽位」的主窗口使用。</li>
 * </ul>
 */
public final class McGui {
    /** 原版九宫格面板精灵（适用于任意尺寸面板）。 */
    public static final ResourceLocation VANILLA_PANEL =
        ResourceLocation.withDefaultNamespace("recipe_book/overlay_recipe");

    /** 一格槽位的内容区边长（16；槽框连同边框是 18）。 */
    public static final int SLOT_SIZE = 16;

    /**
     * 「被阻塞」槽位装饰环的外扩量（像素）。
     * <p><b>为什么是 2</b>：内容区是 16×16，槽位间距是 18px —— 内容区之外横向 / 纵向各只剩 1px 的槽框带，
     * 再往外 1px 才是邻居的槽框。取 2 时红环的两条内缘落在 {@code x-1 / x+16}（内容区外 1px），
     * 外缘落在 {@code x-2 / x+17}：这样既不覆盖内容像素，也不会压到邻居的内容区
     * （邻居内容从 {@code x+18} 起）。</p>
     */
    public static final int BLOCKED_MARK_OUTSET = 2;

    /** 「被阻塞」装饰环 / 角标的颜色（高饱和红；内容之下的淡红底另见各界面自己的常量）。 */
    private static final int COLOR_BLOCKED_RING = 0xFFFF3B3B;
    /** 四角角标颜色（比环更亮的红，让四个角「一眼可辨」）。 */
    private static final int COLOR_BLOCKED_CORNER = 0xFFFFB0B0;

    /**
     * 「空 / 无」标识的颜色：半透明深灰。
     * <p>比真实物品图标弱、比槽底（{@code #8B8B8B}）强，一眼能看出「这里是空的」而不抢注意力。</p>
     */
    private static final int COLOR_EMPTY_MARK = 0x99555555;

    private McGui() {
    }

    /** 画一块 MC 原生风格面板（圆角 + 黑描边 + 高光 + 暗边，全部来自原版精灵）。 */
    public static void panel(final GuiGraphics guiGraphics, final int x, final int y,
                            final int width, final int height) {
        guiGraphics.blitSprite(VANILLA_PANEL, x, y, width, height);
    }

    /** 槽位框素材（{@code pic/slot.png} 左上角）的边长；内容区 = 它内缩 1px（16×16）。 */
    public static final int SLOT_FRAME_SIZE = 18;

    /**
     * 画一个槽位框 —— <b>与用户原始素材 {@code pic/slot.png} 左上逐像素一致</b>。
     *
     * <h2>为什么必须改回来（用户原话）</h2>
     * <p>「不要用你别的什么素材的那个槽位背景，现在你这个槽位背景明显<b>粗了一圈</b>……我原本的素材是
     * 完全不会这样子的」。上一版这里先在整块 18×18 上铺了一圈深灰外描边，再往里又画了一遍上 / 左深灰
     * —— 于是上 / 左变成 <b>2px 深灰</b>、下 / 右多出一条深灰外圈（白高光被夹在中间），里外各粗了一圈。</p>
     *
     * <h2>原素材的真实像素结构（逐像素扫描 {@code pic/slot.png} 左上 18×18）</h2>
     * <pre>
     *   DDDDDDDDDDDDDDDDD.     D = #373737 暗边   . = #8B8B8B 槽底   W = #FFFFFF 高光
     *   D................W     * 上 / 左各 1px 暗边，长 17（覆盖 x..x+16 / y..y+16）
     *   D................W     * 下 / 右各 1px 高光，长 17（覆盖 x+1..x+17 / y+1..y+17）
     *   ...（中间 15 行同）...  * 中间 16×16 就是物品内容区（= Menu 槽位坐标，见「精灵 +1」口径）
     *   D................W
     *   .WWWWWWWWWWWWWWWWW
     * </pre>
     * <p>因此四条边各只画 <b>1px</b>，且暗边与高光<b>错开一格</b>（这正是原素材的样子）；
     * 不再有任何「外圈 + 内圈」的双描边。</p>
     *
     * <h2>为什么用 fill 而不是 PNG</h2>
     * <p>原素材在 {@code pic/}（美术源，不随包发布），发布资源里没有这张槽框图；因此这里用原素材取到的
     * 三个结构色逐像素复刻，观感与原素材一致，且不新增任何 GUI 贴图（符合「不新画 GUI 背景贴图」的硬规则）。</p>
     *
     * @param x 槽框左上角 x（= 内容区 {@code Slot#x} − 1）
     * @param y 槽框左上角 y（= 内容区 {@code Slot#y} − 1）
     */
    public static void slotFrame(final GuiGraphics guiGraphics, final int x, final int y) {
        final int size = SLOT_FRAME_SIZE;
        final int last = size - 1;
        guiGraphics.fill(x, y, x + size, y + size, 0xFF8B8B8B);                // 槽底（内部中灰 139）
        guiGraphics.fill(x, y, x + last, y + 1, 0xFF373737);                   // 上暗边（1px × 17）
        guiGraphics.fill(x, y, x + 1, y + last, 0xFF373737);                   // 左暗边（1px × 17）
        guiGraphics.fill(x + 1, y + last, x + size, y + size, 0xFFFFFFFF);     // 下亮边（1px × 17）
        guiGraphics.fill(x + last, y + 1, x + size, y + size, 0xFFFFFFFF);     // 右亮边（1px × 17）
    }

    /**
     * 「被阻塞」槽位装饰层：一圈 {@value #BLOCKED_MARK_OUTSET}px 厚的高饱和红环 + 四角角标。
     *
     * <h2>为什么要独立成一层（用户的真实反馈）</h2>
     * <p>用户原话：「流体还不明显，物品更不明显、更淡了」。原因是此前唯一「明显」的标识
     * （内容层上的 1px 细红边）<b>只给流体 / 气体画</b>，而画在内容之下的淡红底（alpha 0x40）
     * 是用户明确要求保留的「原本那种」底色，不能加深 —— 于是物品格看起来几乎没有标识。
     * 这里换成<b>内容之外</b>的一层装饰：物品与流体<b>完全同一套</b>视觉语言、同一强度，
     * 且底色深浅一个字没改。</p>
     *
     * <h2>几何（硬规则：绝不覆盖内容像素层）</h2>
     * <p>设内容区（物品 / 流体图标所在的 16×16）左上角为 {@code (x, y)}、外扩量为 {@code o}
     * （= {@value #BLOCKED_MARK_OUTSET}），则本方法只画在两块带里：</p>
     * <ul>
     *     <li>红环：{@code [x-o, x)} ∪ {@code [x+16, x+16+o)} 两条竖带 +
     *     {@code [y-o, y)} ∪ {@code [y+16, y+16+o)} 两条横带；</li>
     *     <li>四角角标：上述带内靠四角的 {@code o×2} 小段；其中右上角是一块实心方角，
     *     作为「禁止 / 无法接收」的角标。</li>
     * </ul>
     * <p>两条内缘恰好是 {@code x-1 / x+16}、{@code y-1 / y+16}，即<b>始终在 16×16 内容区之外</b>：
     * 物品图标、流体贴图、数量小字一个像素都不会被压住（自检脚本用几何计算断言这一点）。</p>
     *
     * @param x 槽位内容区左上角 x（= {@code Slot#x}）
     * @param y 槽位内容区左上角 y（= {@code Slot#y}）
     */
    public static void blockedSlotMark(final GuiGraphics guiGraphics, final int x, final int y) {
        final int outset = BLOCKED_MARK_OUTSET;
        final int x0 = x - outset;
        final int y0 = y - outset;
        final int x1 = x + SLOT_SIZE + outset;
        final int y1 = y + SLOT_SIZE + outset;
        // 一圈 o px 厚红环（内缘在内容区之外，外缘不越进邻居内容区）
        guiGraphics.fill(x0, y0, x1, y0 + outset, COLOR_BLOCKED_RING);
        guiGraphics.fill(x0, y1 - outset, x1, y1, COLOR_BLOCKED_RING);
        guiGraphics.fill(x0, y0 + outset, x0 + outset, y1 - outset, COLOR_BLOCKED_RING);
        guiGraphics.fill(x1 - outset, y0 + outset, x1, y1 - outset, COLOR_BLOCKED_RING);
        // 四角角标（右上角加宽成实心方角 = 「禁止 / 无法接收」）
        guiGraphics.fill(x1 - 2 * outset, y0, x1, y0 + outset, COLOR_BLOCKED_CORNER);
        guiGraphics.fill(x0, y0, x0 + outset, y0 + outset, COLOR_BLOCKED_CORNER);
        guiGraphics.fill(x0, y1 - outset, x0 + outset, y1, COLOR_BLOCKED_CORNER);
        guiGraphics.fill(x1 - outset, y1 - outset, x1, y1, COLOR_BLOCKED_CORNER);
    }

    /**
     * 全工程<b>统一的开／关控件文案</b>：勾 = 绿色 ✓、叉 = 红色 ✗。
     * <p>任何界面都不允许再出现「合成 开 / 关」这类文字状态表达（用户明确要求统一）。</p>
     */
    public static Component toggleLabel(final boolean on) {
        return Component.literal(on ? "§a✓" : "§c✗");
    }

    /**
     * 全工程<b>统一的开／关按钮</b>：一行文本 + 旁边一个 ✓/✗ 按钮（勾绿、叉红，尺寸由调用方给，
     * 尽量小）。点击时把「取反后的状态」通过 {@code onSet} 交回（服务端权威逻辑由调用方决定）。
     *
     * @param state 当前状态（由调用方每帧 {@link #refreshToggle} 回写消息，保证跟随服务端值）
     */
    public static net.minecraft.client.gui.components.Button toggle(
        final int x, final int y, final int width, final int height,
        final java.util.function.BooleanSupplier state,
        final java.util.function.Consumer<Boolean> onSet) {
        return new net.minecraft.client.gui.components.Button.Builder(
            toggleLabel(state.getAsBoolean()), button -> onSet.accept(!state.getAsBoolean()))
            .bounds(x, y, width, height).build();
    }

    /** 把服务端权威状态回写到统一的 ✓/✗ 按钮上。 */
    public static void refreshToggle(final net.minecraft.client.gui.components.Button button, final boolean on) {
        if (button != null) {
            button.setMessage(toggleLabel(on));
        }
    }

    /**
     * 「空 / 无」标识：在一格槽位（16×16 内容区）里画一道<b>斜杠</b>，
     * 用来明确表示「这一格是空的 —— 这里就是没有东西」，而不是「还没加载出来」。
     *
     * <h2>为什么需要它</h2>
     * <p>用户原话：「像动力冲压器这种<b>没有输入 / 产物</b>的地方，标上一个类似『静止 / 空』的符号，
     * 空的时候感觉怪，要能突出显示『这里没有东西』」。序列装配里有的步骤天生就没有物品输入
     * （例如冲压），它的预览格永远是空的 —— 玩家分不清「这一步没有输入」还是「界面坏了」。</p>
     *
     * <h2>画法（不新增任何贴图）</h2>
     * <p>全部用 {@link GuiGraphics#fill} 逐像素堆出一道 1px 宽的阶梯斜线（左下 → 右上），
     * 与 {@link #slotFrame} 同一套「手搓结构色」的做法；<b>不外扩、不用新 PNG</b>，
     * 因此对现有背景与布局校验零影响。颜色取半透明深灰：在浅灰槽底上清晰可见，
     * 又明显弱于真实物品图标（不会被误认成物品）。</p>
     *
     * <p><b>边界</b>：斜线内缩 3px 绘制（x+3..x+13），而槽框（17×17）画在 (x-1, y-1) 起 ——
     * 因此斜线绝不压到任何一边的槽位边框；空槽位本来就没有物品 / 堆叠数 / 概率小字，
     * 也不存在遮挡其它控件的问题。</p>
     *
     * @param x 槽位内容区左上角的 x（= {@code Slot#x}，与物品图标同一坐标口径）
     * @param y 槽位内容区左上角的 y（= {@code Slot#y}）
     */
    public static void emptySlotMarker(final GuiGraphics guiGraphics, final int x, final int y) {
        final int inset = 3;
        for (int i = 0; i <= SLOT_SIZE - 2 * inset; i++) {
            final int px = x + inset + i;
            final int py = y + SLOT_SIZE - inset - i;
            // 每级 1×2 像素：既保证 1px 细线，又让阶梯在缩放后仍然连成一条斜线
            guiGraphics.fill(px, py - 1, px + 1, py + 1, COLOR_EMPTY_MARK);
        }
    }

    /**
     * 该贴图是否存在、且尺寸<b>恰好</b>等于给定值。
     *
     * <p><b>为什么需要它</b>：子窗口的面板 PNG 是「按技术文档尺寸交付」的整图，
     * 一旦代码里的面板尺寸比交付贴图大（例如 176×150 的旧图遇上 176×166 的新布局），
     * 直接 {@code blit} 会把贴图纵向拉伸、边框错位。这里先用 24 字节 PNG 头（IHDR）确认尺寸，
     * 不匹配时回退原版九宫格面板 —— 于是「新尺寸 PNG 一放进去就自动生效，无需改代码」，
     * 且在新图到位前也不会出现拉伸 / 错位。</p>
     *
     * <p>只读 PNG 头，代价极小；不做缓存，因此资源重载（F3+T）后立即生效。</p>
     */
    public static boolean textureMatches(final ResourceLocation texture, final int width, final int height) {
        if (texture == null) {
            return false;
        }
        final net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return false;
        }
        try {
            final var found = minecraft.getResourceManager().getResource(texture);
            if (found.isEmpty()) {
                return false;
            }
            try (java.io.InputStream stream = found.get().open()) {
                final byte[] header = new byte[24];
                int read = 0;
                while (read < header.length) {
                    final int count = stream.read(header, read, header.length - read);
                    if (count < 0) {
                        break;
                    }
                    read += count;
                }
                if (read < header.length) {
                    return false;
                }
                // PNG：8 字节签名 + 4 字节块长 + "IHDR" + 宽(4) + 高(4)
                return readInt(header, 16) == width && readInt(header, 20) == height;
            }
        } catch (Exception exception) {
            return false; // 读不到 / 非法 PNG：安全回退原版九宫格
        }
    }

    /** 大端 4 字节整数。 */
    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
