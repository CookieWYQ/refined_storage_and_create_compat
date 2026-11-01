package cretae.cookiewyq.rs_create_compat.item;

import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * <b>区域选定器</b>（{@code rs_create_compat:area_selector}）：把「世界里的若干方块区域」存成物品数据，
 * 插进「高级归流缓存仓」的专用槽后，那台机器的吸收范围就只剩这些区域。
 *
 * <h2>交互（与蓝图框选 1:1 的肌肉记忆，实现在 {@code client/AreaSelectorHandler}）</h2>
 * <ul>
 *     <li><b>右键方块</b>：第一次 = 记下 A 角；第二次 = 记下 B 角并<b>提交一个新区域</b>（自动选中它）；</li>
 *     <li><b>潜行 + 右键方块</b>：放弃当前未完成的 A 角（蓝图潜行右键 = 放弃整个框）；</li>
 *     <li><b>潜行 + 右键空气</b>：打开「区域列表」界面（也可以从高级缓存仓界面的「区域」按钮打开）；</li>
 *     <li><b>Ctrl + 滚轮</b>：有未完成的 A 角时推 / 拉正在框的盒子；否则调整<b>当前选中区域</b>的大小
 *     （Shift ×5，与蓝图 / 范围充电器同一套步进）。</li>
 * </ul>
 *
 * <h2>为什么不可堆叠</h2>
 * <p>数据组件里存的是「这一份选定器的区域表」，两份区域不同的选定器就是两个不同的物品实例
 * （同 {@code RAW_MATERIAL} 先例：同标记才堆叠）。这里强制 {@code stacksTo(1)}，
 * 免得玩家以为「堆叠了却只生效一份」是坏了。</p>
 */
public class AreaSelectorItem extends Item {
    private static final String LANG = "item.rs_create_compat.area_selector.";

    public AreaSelectorItem(final Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(final ItemStack stack, final TooltipContext context,
                                final List<Component> tooltip, final TooltipFlag flag) {
        final AreaSelection selection = AreaSelection.get(stack);
        final ResourceLocation dimension = selection.dimension();
        tooltip.add(Component.translatable(LANG + "dimension",
                dimension == null
                    ? Component.translatable(LANG + "unbound")
                    : Component.literal(dimension.toString()))
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(LANG + "count",
                selection.areaCount(), AreaSelection.maxAreas()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(LANG + "volume", selection.totalVolume())
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(LANG + "help").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(LANG + "tip.corner").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(LANG + "tip.scroll").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(LANG + "tip.sneak").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable(LANG + "tip.list").withStyle(ChatFormatting.DARK_GRAY));
        // 第 112 轮：每区域过滤标记存在选定器上 ⇒ 拿着就能配，不必插进机器
        // 第 121 轮 C-5：计数<b>必须</b>走带 {@code registries} 的重载。两参版（registries = null）
        // 在 CollectionCacheBlockEntity#decodeMarkerTag 里会跳过旧档的「整份 ItemStack（Item 键）」标记，
        // 于是同一份物品在服务端（带 player.registryAccess()）和这里数出来的区域数不一样 —— 显示偏少。
        // 拿不到注册表（例如 TooltipContext.EMPTY / 无 level）时保守按 0 计，<b>绝不回退到两参版</b>：
        // 回退就是重新造一个「偏少但看起来可信」的数字，正是这条 bug 本身。
        // 待办（本轮不做）：若要「按内容指纹删除」，需要改 StreamCodec 线格式 —— 旧客户端会整包错位。
        final net.minecraft.world.level.Level tooltipLevel = context.level();
        final int marked = tooltipLevel == null
            ? 0 : AreaMarks.all(stack, tooltipLevel.registryAccess()).size();
        tooltip.add(Component.translatable(LANG + "tip.marks", marked).withStyle(
            marked > 0 ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(final ItemStack stack) {
        // 已经存了区域的选定器带附魔光泽：一眼区分「空白的」与「已画好的」
        return !AreaSelection.get(stack).isEmpty();
    }
}
