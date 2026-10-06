package cretae.cookiewyq.rs_create_compat.client.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Ghost（标记）槽的「物品 / 流体 / 气体」绘制工具。
 * <p>本模组的 ghost 标记既可能是物品，也可能是流体（含气体，Mekanism 化学品同样以流体标识承载），
 * 因此槽内不能只处理 {@link net.minecraft.world.item.ItemStack}。这里把「流体贴图 + 着色」的绘制
 * 抽出来，供归流缓存仓、标记配置界面、定量保持器等所有 ghost 槽统一复用。</p>
 * <p>流体贴图绘制照搬 RS 原版 {@code AbstractFluidRenderer}（{@code POSITION_TEX_COLOR} 顶点色着色），
 * 保证与 RS 网格里的流体图标观感一致；所有文字均以无阴影方式绘制。</p>
 * <p>此外还承载「标记条目展示什么」的<b>唯一数据源</b>（{@link #displayItem} /
 * {@link #tagMatchedItems}）：主界面与各个子窗口都从这里取，避免同一份标记在不同界面显示不一致。</p>
 */
public final class GhostMarkerRenderer {
    /** ghost 槽内流体图标边长（与物品图标一致）。 */
    public static final int ICON = 16;

    /** 标签过滤器「能命中的物品」列表缓存（键 = 标签集合文本）：避免每帧重扫物品注册表。 */
    private static final java.util.Map<String, List<ItemStack>> TAG_MATCHED_ITEMS = new java.util.HashMap<>();
    /** 标签过滤器展示物的轮播周期（tick）：每 20 tick 换一个命中物。 */
    private static final int CYCLE_TICKS = 20;

    private GhostMarkerRenderer() {
    }

    /** 由流体 id 构造用于绘制的 {@link FluidStack}（数量仅用于展示，绘制不依赖它）。 */
    public static FluidStack toFluidStack(final ResourceLocation id, final long amount) {
        if (id == null) {
            return FluidStack.EMPTY;
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        final int clamped = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, amount));
        return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid), clamped, DataComponentPatch.EMPTY);
    }

    /** 流体显示名（气体同样走此路径；未注册的 id 退化为原始 id 文本）。 */
    public static Component fluidName(final ResourceLocation id) {
        if (id == null) {
            return Component.empty();
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return Component.literal(id.toString());
        }
        return fluid.getFluidType().getDescription();
    }

    /** 流体数量格式化：>=1B 用 B（保留 1 位小数），否则用 mB。 */
    public static String fluidAmount(final long mB) {
        if (mB >= 1000L) {
            final long whole = mB / 1000L;
            final long frac = (mB % 1000L) / 100L;
            return frac == 0 ? whole + "B" : whole + "." + frac + "B";
        }
        return mB + "mB";
    }

    // ==================== 标记条目的展示数据源（界面共用） ====================

    /**
     * 能同时带上条目<b>全部</b>匹配标签的物品列表（客户端本地按物品标签注册表计算，无需网络包）。
     * <p><b>为什么统一放在这里</b>：归流缓存仓<b>主界面</b>与「匹配设置」<b>子窗口</b>都要展示标签过滤器
     * 到底命中了什么，而两者此前各用各的数据源（主界面轮播命中集合、子窗口只取静态代表物
     * {@link MarkerEntry#displayStack()}）—— 同一份标记在两个界面显示不一致（用户实测：标签模式下
     * 主界面正常轮播、子窗口右上角图标显示不出来）。现在两个界面共用本方法，<b>结构上不可能再不一致</b>。</p>
     * <p>结果按标签集合缓存（{@link #TAG_MATCHED_ITEMS}），避免每帧重扫物品注册表。</p>
     *
     * @param entry 匹配条目；{@code null} / 非标签过滤器 → 空表
     */
    public static List<ItemStack> tagMatchedItems(final MarkerEntry entry) {
        if (entry == null || !entry.isTagFilter()) {
            return List.of();
        }
        final String key = entry.tags().toString();
        final List<ItemStack> cached = TAG_MATCHED_ITEMS.get(key);
        if (cached != null) {
            return cached;
        }
        final List<TagKey<Item>> keys = new ArrayList<>(entry.tags().size());
        for (final ResourceLocation tag : entry.tags()) {
            keys.add(TagKey.create(Registries.ITEM, tag));
        }
        final List<ItemStack> matched = new ArrayList<>();
        for (final Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            boolean all = true;
            for (final TagKey<Item> tag : keys) {
                if (!item.builtInRegistryHolder().is(tag)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matched.add(new ItemStack(item));
            }
        }
        TAG_MATCHED_ITEMS.put(key, matched);
        return matched;
    }

    /**
     * 条目当前应展示的<b>物品</b>（主界面 ghost 槽与「匹配设置」子窗口右上角图标共用的唯一入口）。
     * <ul>
     *     <li>标签过滤器：在 {@link #tagMatchedItems} 给出的全部命中物之间轮播（每 {@link #CYCLE_TICKS} tick
     *     换一个），让玩家直观看到这一条到底会匹配到什么；</li>
     *     <li>普通条目 / 命中集合为空：退回 {@link MarkerEntry#displayStack()}（被标记的示例物本身）；</li>
     *     <li>流体 / 气体条目：返回空栈（由 {@link #renderFluid} 画流体贴图）。</li>
     * </ul>
     * <p>只要命中集合非空就一定有物品可画，因此不会出现「主界面看得到、另一个界面空白」的情况。</p>
     */
    public static ItemStack displayItem(final MarkerEntry entry) {
        if (entry == null || entry.fluid()) {
            return ItemStack.EMPTY;
        }
        if (entry.isTagFilter()) {
            final List<ItemStack> matched = tagMatchedItems(entry);
            if (!matched.isEmpty()) {
                final net.minecraft.world.level.Level level = Minecraft.getInstance().level;
                final long tick = level == null ? 0L : level.getGameTime();
                return matched.get((int) (Math.floorDiv(tick, CYCLE_TICKS) % matched.size()));
            }
        }
        return entry.displayStack();
    }

    // ==================== 「标签 / 多选输入」的候选物循环显示（界面共用） ====================

    /**
     * 由一组 {@link Ingredient} 收集<b>全部</b>可选物品（标签型 ingredient 会展开成多件，
     * 例如「石头台阶标签」→ 石头台阶 / 平滑石台阶 / 安山岩台阶）。
     * <p>去重口径与 {@code SequencedRecipeProbe#stepInputItems} 一致：按<b>物品种类</b>去重
     * （忽略数据组件），每件只取 1 个；顺序保持 ingredient 顺序，保证轮播稳定。
     * 这是「多选输入」在界面上循环显示同一份候选的唯一数据源（顶部输入原料槽与流程编排行共用）。</p>
     */
    public static List<ItemStack> candidateItems(final List<Ingredient> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return List.of();
        }
        final List<ItemStack> result = new ArrayList<>();
        for (final Ingredient ingredient : ingredients) {
            if (ingredient == null) {
                continue;
            }
            final ItemStack[] items = ingredient.getItems();
            for (final ItemStack item : items) {
                if (item == null || item.isEmpty() || item.getItem() == Items.AIR) {
                    continue;
                }
                boolean duplicate = false;
                for (final ItemStack existing : result) {
                    if (existing.is(item.getItem())) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    result.add(item.copyWithCount(1));
                }
            }
        }
        return result;
    }

    /**
     * 「候选物列表」的定时轮播：与标签过滤器展示物同一套 tick 取模（{@link #CYCLE_TICKS}），
     * 因此同一个多选格在不同界面 / tooltip 之间的当前项一致。
     * <p>单选或空表时退化为「就是它 / 空栈」，不引入任何额外状态。</p>
     */
    public static ItemStack cycleCandidate(final List<ItemStack> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        final net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        final long tick = level == null ? 0L : level.getGameTime();
        return candidates.get((int) (Math.floorDiv(tick, CYCLE_TICKS) % candidates.size()));
    }

    /**
     * 在 (x,y) 处绘制 16×16 的流体图标（贴图 + 顶点着色）。
     * <p>调用方需保证当前 PoseStack 已平移到 GUI 原点（即坐标为 GUI 内相对坐标）。</p>
     */
    public static void renderFluid(final GuiGraphics guiGraphics, final int x, final int y,
                                   final FluidStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        final IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(stack.getFluid());
        final ResourceLocation still = ext.getStillTexture(stack);
        if (still == null) {
            return;
        }
        final TextureAtlasSprite sprite =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
        final int packedRgb = ext.getTintColor(stack);
        int alpha = packedRgb >>> 24;
        if (alpha == 0) {
            alpha = 255; // 未指定 alpha 的 tint 视为不透明
        }
        final int r = packedRgb >> 16 & 255;
        final int g = packedRgb >> 8 & 255;
        final int b = packedRgb & 255;

        RenderSystem.setShaderTexture(0, sprite.atlasLocation());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        final Matrix4f matrix = guiGraphics.pose().last().pose();
        final BufferBuilder buffer = Tesselator.getInstance().begin(
            VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(matrix, x, y + ICON, 0)
            .setUv(sprite.getU0(), sprite.getV1()).setColor(r, g, b, alpha);
        buffer.addVertex(matrix, x + ICON, y + ICON, 0)
            .setUv(sprite.getU1(), sprite.getV1()).setColor(r, g, b, alpha);
        buffer.addVertex(matrix, x + ICON, y, 0)
            .setUv(sprite.getU1(), sprite.getV0()).setColor(r, g, b, alpha);
        buffer.addVertex(matrix, x, y, 0)
            .setUv(sprite.getU0(), sprite.getV0()).setColor(r, g, b, alpha);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** 流体 tooltip：名称 + 数量 + 可选匹配规则（自绘元素必须手动渲染 tooltip）。 */
    public static void renderFluidTooltip(final GuiGraphics guiGraphics, final Font font,
                                          final ResourceLocation id, final long amount,
                                          final boolean matchNbt, final String tagFilter,
                                          final int mouseX, final int mouseY) {
        final List<Component> lines = new java.util.ArrayList<>(3);
        lines.add(fluidName(id));
        lines.add(Component.translatable("gui.rs_create_compat.marker.amount",
            fluidAmount(amount)));
        if (matchNbt) {
            lines.add(Component.translatable("gui.rs_create_compat.marker.match_nbt"));
        }
        if (tagFilter != null && !tagFilter.isEmpty()) {
            lines.add(Component.translatable("gui.rs_create_compat.marker.tag_filter", tagFilter));
        }
        renderLines(guiGraphics, font, lines, mouseX, mouseY);
    }

    /** 手动渲染多行 tooltip（本模组 GUI 不会自动渲染 tooltip）。 */
    public static void renderLines(final GuiGraphics guiGraphics, final Font font,
                                   final List<Component> lines, final int mouseX, final int mouseY) {
        final List<net.minecraft.util.FormattedCharSequence> wrapped =
            new java.util.ArrayList<>(lines.size());
        for (final Component line : lines) {
            wrapped.add(line.getVisualOrderText());
        }
        guiGraphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }
}
