package cretae.cookiewyq.rs_create_compat.client.screen;

import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.support.Sprites;
import com.refinedmods.refinedstorage.common.support.stretching.AbstractStretchingScreen;
import com.refinedmods.refinedstorage.common.support.widget.History;
import com.refinedmods.refinedstorage.common.support.widget.SearchFieldWidget;
import com.refinedmods.refinedstorage.common.support.widget.SearchIconWidget;
import com.refinedmods.refinedstorage.common.support.widget.TextMarquee;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.menu.UnitPatternManagerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「单元样板管理舱」界面（含无线终端里的同一页）。
 *
 * <p><b>布局/风格照抄 RS 的自动合成管理器</b>：复用 RS 的拉伸骨架、分组标题带精灵与槽位框精灵 ——</p>
 * <ul>
 *     <li>背景贴图 {@code rs_create_compat:textures/gui/unit_pattern_manager.png}
 *     （<b>本界面专属</b>，见 {@code tools/gen_unit_manager_gui_bg.py}；拉伸式：顶 19px + 若干 18px 行 +
 *     底部 99px，几何与 RS 管理器完全一致，但配色是本模组的暖色调，不再与任何方块贴图共用）；</li>
 *     <li>分组标题带精灵 {@code refinedstorage:autocrafter_manager/autocrafter_name}（162×18）、
 *     槽位框精灵 {@link Sprites#SLOT}（18×18）；</li>
 *     <li>搜索框：RS 的 {@link SearchFieldWidget} + {@link SearchIconWidget}（凹槽烘焙在背景贴图里，
 *     与本类的 {@code SEARCH_*} 常量同源），行为与 RS 自动合成管理器一致 —— 按
 *     <b>配方名称 / 单元样板名 / 输入物名与 id</b> 过滤单元样板；</li>
 *     <li>骨架 {@link AbstractStretchingScreen}（可见行数、滚动条、裁剪、把滚动偏移经
 *     {@code scrollbarChanged} 交给子类）。</li>
 *     <li><b>列表里不显示序列装配总样板</b>（用户要求：这里只管单元样板，那张「总的」归自动合成管理舱）：
 *     判据按<b>物品类型</b>而非名字 / 文案，且由菜单在<b>客户端显示层</b>隐藏
 *     （{@code UnitPatternManagerMenu#isDisplayable}）——本类因此只按菜单给出的「可见槽位数」绘制与计数，
 *     不做第二份过滤，避免「画的行」与「算出的行数」两套口径。</li>
 * </ul>
 *
 * <p><b>坐标口径</b>：本类常量全为 Menu 坐标；精灵坐标 = Menu 坐标 - 1。槽位框必须画在
 * {@code (slot.x - 1, slot.y - 1)}，否则物品与框会错开 1px（此前槽框画在 {@code slot.x} 上，
 * 正是「槽位与背景对不上」的根因）。</p>
 *
 * <p><b>硬规则落实</b>：所有 {@code drawString} 传 {@code false}（无阴影）；槽位物品的 tooltip 由
 * 原版按 {@code hoveredSlot} 渲染（一份），分组标题带的说明由本类<b>手动渲染</b>，
 * 且 hover 判定与绘制范围同源（标题带命中判定与 {@link #renderSections} 共用同一批坐标）。</p>
 *
 * <p><b>本界面不再有「生成时自动跳过重复样板」全局开关（用户要求删除）</b>：该开关与序列装配终端里
 * <b>「每一步一个跳过重复开关」</b>重复 —— 真正绑定机器、逐台执行的是流程编排的每一步，因此
 * 「这一步要不要跳过重复」天然是<b>步骤</b>的属性，挂在管理舱上既冗余又会让玩家误以为它能逐个步骤生效。
 * 新步骤的默认档固定为「开」（见 {@code SequencePatternTerminalBlockEntity#defaultStepSkipDuplicate()}），
 * 旧存档里每一步已存的开关值照旧按 NBT 原样读回，行为不受本改动影响。</p>
 */
public class UnitPatternManagerScreen extends AbstractStretchingScreen<UnitPatternManagerMenu> {
    /** 本界面<b>专属</b>背景（不再引用 RS 的方块/界面贴图）。 */
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "textures/gui/unit_pattern_manager.png");
    private static final ResourceLocation SECTION_BAND =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafter_manager/autocrafter_name");
    /** 未接入网络时铺在槽位区的灰色蒙版（与 RS 管理器同色）。 */
    private static final int INACTIVE_COLOR = 0xFF5B5B5B;
    /** 分组标题带宽度（RS 同款 162）。 */
    private static final int BAND_WIDTH = 162;
    /** 分组标题带左边界（Menu 坐标，= 槽位起始 x - 1，与 RS 的 {@code x + 7} 同源）。 */
    private static final int BAND_X = UnitPatternManagerMenu.GROUP_SLOT_X - 1;
    /** RS 搜索框的历史记录（跨界面实例共享，与 RS 管理器同款体验）。 */
    private static final List<String> SEARCH_FIELD_HISTORY = new ArrayList<>();

    private static final String LANG = "gui.rs_create_compat.unit_pattern_manager.";
    private static final String KEY_INACTIVE = LANG + "hint.inactive";
    private static final String KEY_EMPTY = LANG + "hint.empty";
    private static final String KEY_NO_MATCH = LANG + "hint.no_match";
    private static final String KEY_SEARCH_TIP = LANG + "search.tip";
    private static final String KEY_GROUP_NAME = LANG + "group.name";
    private static final String KEY_GROUP_RECIPE = LANG + "group.recipe";
    private static final String KEY_GROUP_NO_RECIPE = LANG + "group.no_recipe";
    private static final String KEY_GROUP_READ_ONLY = LANG + "group.extract_only";
    private static final String KEY_GROUP_READ_WRITE = LANG + "group.read_write";

    /** RS 同款搜索框（无边框，凹槽由背景贴图烘焙；见 {@code gen_unit_manager_gui_bg.py}）。 */
    private SearchFieldWidget searchField;
    /** 上一次已应用的搜索词（变化时才重排槽位，避免每帧重建）。 */
    private String lastQuery = "";

    public UnitPatternManagerScreen(final UnitPatternManagerMenu menu,
                                    final Inventory playerInventory,
                                    final Component title) {
        // 标题颜色走 TextMarquee 的显式构造：黑色（浅色面板上默认灰字偏淡，与全模组机器标题统一口径）
        super(menu, playerInventory,
            new TextMarquee(title, UnitPatternManagerMenu.TITLE_WIDTH, 0xFF333333, false, false));
        this.imageWidth = UnitPatternManagerMenu.PANEL_W;
        this.imageHeight = 176;
    }

    @Override
    protected void init(final int rows) {
        super.init(rows);
        // 搜索框：与 RS 自动合成管理器同一控件与样式（凹槽烘焙在背景贴图里，位置与 SRC/Menu 常量同源）
        if (searchField == null) {
            searchField = new SearchFieldWidget(font,
                leftPos + UnitPatternManagerMenu.SEARCH_FIELD_X,
                topPos + UnitPatternManagerMenu.SEARCH_FIELD_Y,
                UnitPatternManagerMenu.SEARCH_FIELD_W,
                new History(SEARCH_FIELD_HISTORY));
            searchField.setResponder(value -> applySearch());
        } else {
            searchField.setX(leftPos + UnitPatternManagerMenu.SEARCH_FIELD_X);
            searchField.setY(topPos + UnitPatternManagerMenu.SEARCH_FIELD_Y);
        }
        addWidget(searchField);
        addRenderableWidget(new SearchIconWidget(
            leftPos + UnitPatternManagerMenu.SEARCH_ICON_X,
            topPos + UnitPatternManagerMenu.SEARCH_ICON_Y,
            () -> Component.translatable(KEY_SEARCH_TIP),
            searchField));

        // 滚动条必须知道「内容总行数」，否则 maxOffset 恒为 0 —— 这就是此前「滚不动」的原因
        updateScrollbar(totalRows());
        scrollbarChanged(rows);
    }

    // ==================== 搜索（过滤单元样板） ====================

    /** 把当前搜索词应用到菜单（只影响位置与可见性，下标两端不变），随后重排槽位与滚动条。 */
    private void applySearch() {
        final String query = searchField == null
            ? "" : searchField.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.equals(lastQuery)) {
            return;
        }
        lastQuery = query;
        menu.setSlotFilter(query.isEmpty() ? null : stack -> matchesQuery(query, stack));
        resize();
        updateScrollbar(totalRows());
        scrollbarChanged(0);
    }

    /** 单元样板是否命中搜索词：配方名称 / 单元样板名 / 输入物名与物品 id（大小写不敏感）。 */
    private boolean matchesQuery(final String query, final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        final RegistryAccess registries = registryAccess();
        SequencePatternData.UnitData unit = SequencePatternData.readUnit(stack, registries);
        String recipeType;
        String displayName;
        ItemStack input;
        if (unit == null) {
            final SequencePatternData.UnitMeta meta = SequencePatternData.readUnitNew(stack, registries);
            recipeType = meta.recipeType();
            displayName = meta.displayName();
            input = ItemStack.EMPTY;
        } else {
            recipeType = unit.recipeType();
            displayName = unit.displayName();
            input = unit.input() == null ? ItemStack.EMPTY : unit.input();
        }
        if (recipeType != null && !recipeType.isEmpty()
            && RecipeTypeNames.of(recipeType).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (definitionContains(recipeType, query)) {
            return true;
        }
        if (displayName != null && displayName.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (!input.isEmpty()) {
            if (input.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
            if (BuiltInRegistries.ITEM.getKey(input.getItem()).toString().contains(query)) {
                return true;
            }
        }
        return false;
    }

    /** 配方类型的原始 id 也参与匹配（如输入 {@code deploying} 能命中 {@code create:deploying}）。 */
    private static boolean definitionContains(final String recipeType, final String query) {
        return recipeType != null && !recipeType.isEmpty()
            && recipeType.toLowerCase(Locale.ROOT).contains(query);
    }

    private RegistryAccess registryAccess() {
        return minecraft == null || minecraft.level == null
            ? RegistryAccess.EMPTY : minecraft.level.registryAccess();
    }

    /** 内容总行数（每个可见分组 = 标题行 1 + 槽位行 N），与 RS 管理器同一算法。 */
    private int totalRows() {
        int total = 0;
        for (final UnitPatternManagerMenu.SectionView section : menu.getSections()) {
            total += section.isVisible() ? section.getVisibleRows() + 1 : 0;
        }
        return total;
    }

    /** 裁剪区内的可见行数（由面板高反推，供标题带悬停判定与绘制同源）。 */
    private int visibleRowCount() {
        return (imageHeight - TOP_HEIGHT - getBottomHeight()) / ROW_SIZE;
    }

    // ==================== 滚动：把分组槽位整体随滚动条平移 ====================

    @Override
    protected void scrollbarChanged(final int rows) {
        super.scrollbarChanged(rows);
        final int offset = getScrollbarOffset();
        for (final Slot slot : menu.slots) {
            if (slot instanceof UnitPatternManagerMenu.SectionSlot sectionSlot) {
                Platform.INSTANCE.setSlotY(sectionSlot, sectionSlot.getOriginalY() - offset);
            }
        }
    }

    /** 分组槽位由本类在裁剪区内手动绘制（避免原版槽位循环把它们画到裁剪区外）。 */
    @Override
    protected void renderSlot(final GuiGraphics graphics, final Slot slot) {
        if (slot.index < menu.getSectionSlots()) {
            return;
        }
        super.renderSlot(graphics, slot);
    }

    @Override
    protected void renderRows(final GuiGraphics graphics,
                              final int x,
                              final int y,
                              final int topHeight,
                              final int rows,
                              final int mouseX,
                              final int mouseY) {
        if (!menu.isActive()) {
            graphics.fill(RenderType.guiOverlay(),
                x + UnitPatternManagerMenu.GROUP_SLOT_X,
                y + TOP_HEIGHT + 1,
                x + UnitPatternManagerMenu.GROUP_SLOT_X + (ROW_SIZE * UnitPatternManagerMenu.COLUMNS) - 2,
                y + TOP_HEIGHT + 1 + (ROW_SIZE * rows) - 2,
                INACTIVE_COLOR);
            renderHint(graphics, x, y, rows, KEY_INACTIVE);
            return;
        }
        renderSections(graphics, x, y, topHeight, rows);
        renderSectionSlotContents(graphics, mouseX, mouseY, y, topHeight, rows);
        if (menu.getVisibleSectionSlots() == 0) {
            // 空状态文案必须与「过滤后的列表」一致：搜索词过滤掉全部内容、或内容全是总样板（被显示层挡掉）
            // 都算「有东西但没显示」，只有「确实没有执行舱」才说没有执行舱。
            renderHint(graphics, x, y, rows,
                menu.hasFilter() || menu.hasHiddenMasterPatternSlots() ? KEY_NO_MATCH : KEY_EMPTY);
        }
    }

    /** 分组标题带 + 槽位框（都在裁剪区内，随滚动偏移移动）。 */
    private void renderSections(final GuiGraphics graphics,
                                final int x,
                                final int y,
                                final int topHeight,
                                final int rows) {
        final int bandX = x + BAND_X;
        int rowY = y + topHeight - getScrollbarOffset();
        for (final UnitPatternManagerMenu.SectionView section : menu.getSections()) {
            if (!section.isVisible()) {
                continue;
            }
            if (!isOutOfFrame(y, topHeight, rows, rowY)) {
                graphics.blitSprite(SECTION_BAND, bandX, rowY, BAND_WIDTH, ROW_SIZE);
                graphics.drawString(font, bandTitle(section), bandX + 4, rowY + 6, 4210752, false);
            }
            for (int i = 0; i < section.getVisibleSlots(); i++) {
                final int slotY = rowY + ROW_SIZE + ((i / UnitPatternManagerMenu.COLUMNS) * ROW_SIZE);
                if (isOutOfFrame(y, topHeight, rows, slotY)) {
                    continue;
                }
                // 槽框素材 18×18 的左上角 = 槽位坐标 - 1（与物品、悬停高亮同源）
                final int slotX = bandX + ((i % UnitPatternManagerMenu.COLUMNS) * ROW_SIZE);
                graphics.blitSprite(Sprites.SLOT, slotX, slotY, 18, 18);
            }
            rowY += (section.getVisibleRows() + 1) * ROW_SIZE;
        }
    }

    /** 分组标题 = 执行舱名 · <b>配方名称</b>（不再是原始配方 id）。 */
    private static Component bandTitle(final UnitPatternManagerMenu.SectionView section) {
        final String name = section.getTitle().getString();
        if (section.getRecipeType().isEmpty()) {
            return Component.literal(name);
        }
        return Component.literal(name + " · " + RecipeTypeNames.of(section.getRecipeType()));
    }

    /** 手动绘制分组槽位里的物品 + 悬停高亮（与 RS 管理器同一做法：只画裁剪区内的）。 */
    private void renderSectionSlotContents(final GuiGraphics graphics,
                                           final int mouseX,
                                           final int mouseY,
                                           final int y,
                                           final int topHeight,
                                           final int rows) {
        graphics.pose().pushPose();
        graphics.pose().translate(leftPos, topPos, 0);
        for (int i = 0; i < menu.getSectionSlots(); i++) {
            final Slot slot = menu.slots.get(i);
            if (!slot.isActive() || isOutOfFrame(y, topHeight, rows, topPos + slot.y)) {
                continue;
            }
            super.renderSlot(graphics, slot);
            if (isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                renderSlotHighlight(graphics, slot.x, slot.y, 0);
            }
        }
        graphics.pose().popPose();
    }

    /** 灰色空状态 / 无匹配时的一句短提示（居中于槽位区，不与任何槽位重叠）。 */
    private void renderHint(final GuiGraphics graphics,
                            final int x,
                            final int y,
                            final int rows,
                            final String key) {
        final Component text = Component.translatable(key);
        final int centerX = x + UnitPatternManagerMenu.GROUP_SLOT_X
            + (ROW_SIZE * UnitPatternManagerMenu.COLUMNS) / 2;
        final int centerY = y + TOP_HEIGHT + (ROW_SIZE * rows) / 2;
        graphics.drawString(font, text, centerX - font.width(text) / 2, centerY, 0xFFFFFFFF, false);
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX,
                       final int mouseY, final float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        // 搜索框是 addWidget 进来的（不参与自动渲染），这里手动渲染一次
        if (searchField != null) {
            searchField.render(graphics, 0, 0, 0);
        }
        renderCustomTooltips(graphics, mouseX, mouseY);
    }

    @Override
    public boolean charTyped(final char codePoint, final int modifiers) {
        return (searchField != null && searchField.charTyped(codePoint, modifiers))
            || super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (searchField != null && searchField.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 手动渲染的提示（槽位内物品的 tooltip 仍由原版按 {@code hoveredSlot} 渲染一份，不重复）：
     * 分组标题带：该组的执行舱名 + <b>配方名称</b> + 原始配方 id（配方信息）+ 是否只出不进。
     */
    private void renderCustomTooltips(final GuiGraphics graphics, final int mouseX, final int mouseY) {
        // 槽位里有物品：原版已经渲染了一份物品提示（含单元样板的「输入原料」标注），不再叠加
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            return;
        }
        final int band = sectionBandAt(mouseX, mouseY);
        if (band >= 0) {
            renderLines(graphics, sectionTooltipLines(menu.getSections().get(band)), mouseX, mouseY);
        }
    }

    /** 分组标题带的提示内容：执行舱名 + 配方名称 + 原始配方 id + 该组的读写语义。 */
    private static List<Component> sectionTooltipLines(final UnitPatternManagerMenu.SectionView section) {
        final List<Component> lines = new ArrayList<>(4);
        final String name = section.getName();
        if (!name.isEmpty()) {
            lines.add(Component.translatable(KEY_GROUP_NAME, Component.literal(name)));
        }
        if (section.getRecipeType().isEmpty()) {
            lines.add(Component.translatable(KEY_GROUP_NO_RECIPE));
        } else {
            lines.add(Component.translatable(KEY_GROUP_RECIPE,
                Component.literal(RecipeTypeNames.of(section.getRecipeType()))));
            lines.add(RecipeTypeNames.idLine(section.getRecipeType()));
        }
        lines.add(Component.translatable(section.isExtractOnly() ? KEY_GROUP_READ_ONLY : KEY_GROUP_READ_WRITE));
        return lines;
    }

    /** 鼠标所在的分组标题带（判定与 {@link #renderSections} 共用同一批坐标，含滚动偏移）。 */
    private int sectionBandAt(final double mouseX, final double mouseY) {
        if (mouseX < leftPos + BAND_X || mouseX >= leftPos + BAND_X + BAND_WIDTH) {
            return -1;
        }
        final int rows = visibleRowCount();
        int rowY = topPos + TOP_HEIGHT - getScrollbarOffset();
        for (int index = 0; index < menu.getSections().size(); index++) {
            final UnitPatternManagerMenu.SectionView section = menu.getSections().get(index);
            if (!section.isVisible()) {
                continue;
            }
            if (!isOutOfFrame(topPos, TOP_HEIGHT, rows, rowY)
                && mouseY >= rowY && mouseY < rowY + ROW_SIZE) {
                return index;
            }
            rowY += (section.getVisibleRows() + 1) * ROW_SIZE;
        }
        return -1;
    }

    /** 手动渲染多行 tooltip（本模组的 GUI 不会自动渲染 tooltip）。 */
    private void renderLines(final GuiGraphics graphics, final List<Component> lines,
                             final int mouseX, final int mouseY) {
        final List<FormattedCharSequence> wrapped = new ArrayList<>(lines.size());
        for (final Component line : lines) {
            wrapped.add(line.getVisualOrderText());
        }
        graphics.renderTooltip(font, wrapped, mouseX, mouseY);
    }

    /** 该行 y 是否落在裁剪区之外（与 RS 的 {@code isOutOfFrame} 同一判定）。 */
    private static boolean isOutOfFrame(final int y, final int topHeight, final int rows, final int rowY) {
        return (rowY < y + topHeight - ROW_SIZE)
            || (rowY > y + topHeight + (ROW_SIZE * rows));
    }

    // ==================== 拉伸背景（照抄 RS：顶行 19 / 中间行 37 / 末行 55） ====================

    @Override
    protected void renderStretchingBackground(final GuiGraphics graphics,
                                              final int x,
                                              final int y,
                                              final int rows) {
        for (int row = 0; row < rows; ++row) {
            int textureY = 37;
            if (row == 0) {
                textureY = 19;
            } else if (row == rows - 1) {
                textureY = 55;
            }
            graphics.blit(getTexture(), x, y + (ROW_SIZE * row), 0, textureY, imageWidth, ROW_SIZE);
        }
    }

    @Override
    protected int getBottomHeight() {
        return 99;
    }

    @Override
    protected int getBottomV() {
        return 73;
    }

    @Override
    protected ResourceLocation getTexture() {
        return TEXTURE;
    }
}
