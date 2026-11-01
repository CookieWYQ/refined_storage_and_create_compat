package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「玩家此刻身上哪一件电动飞行背包<b>生效</b>」的<b>唯一判定处</b>。
 *
 * <h2>生效位置（只有这三处）</h2>
 * <ol>
 *     <li>原版<b>胸甲槽</b>（{@link Player#getItemBySlot(EquipmentSlot)} = {@code CHEST}）；</li>
 *     <li>Curios <b>{@code back}</b> 槽（背饰）；</li>
 *     <li>Curios <b>{@code chest}</b> 槽（胸饰）。</li>
 * </ol>
 * <p>主手 / 副手 / 背包 36 格 / 其它饰品槽（ring、necklace…）里的背包<b>一律不生效</b>：
 * 不给飞行、不扣电、HUD 也不显示。这条口径在物品 tooltip 里也写了一遍
 * （{@code item.rs_create_compat.electric_jetpack.tooltip.slots}），避免「带着它为什么不能飞」的困惑。</p>
 *
 * <h2>多件时的选择顺序</h2>
 * <p>先按<b>槽位优先级</b>（胸甲槽 → back → chest）收集候选，然后：
 * <ul>
 *     <li>优先取<b>电量足够付一次 tick 成本</b>的第一件（保证「优先级高的那件没电了，另外一件还能飞」，
 *     不会出现「明明还有一件满电的却掉下去」）；</li>
 *     <li>都不够时取<b>第一件非空</b>的（让 HUD / 低电提示有东西可显示，而不是一片空白）。</li>
 * </ul>
 * 选择完全确定性（同一 tick 同一状态得到同一结果），HUD 与飞行逻辑共用本方法，不会各算一套。</p>
 *
 * <h2>为什么不缓存</h2>
 * <p>玩家换槽 / 换维度 / 死亡掉落后候选会立刻变；缓存反而要维护失效条件（历史上这类缓存正是
 * 「卸下了还能飞」的来源）。这里每 tick 只做 3 次列表读取 + 少量 {@code instanceof}，开销可忽略。</p>
 */
public final class JetpackEquipped {
    /** Curios 的背饰槽 id（与资源侧 {@code data/curios/tags/item/back.json} 对应）。 */
    public static final String CURIOS_SLOT_BACK = "back";
    /** Curios 的胸饰槽 id（与资源侧 {@code data/curios/tags/item/chest.json} 对应）。 */
    public static final String CURIOS_SLOT_CHEST = "chest";

    private JetpackEquipped() {
    }

    /**
     * 此刻生效的那一件背包（没有则 {@link ItemStack#EMPTY}）。
     *
     * @param cost 一次 tick 的飞行成本（FE）；传 {@code 0} 时退化成「取第一件非空的」
     */
    public static ItemStack active(final Player player, final int cost) {
        final List<ItemStack> candidates = candidates(player);
        if (candidates.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (cost > 0) {
            for (final ItemStack stack : candidates) {
                if (ElectricJetpackItem.getEnergy(stack) >= cost) {
                    return stack;
                }
            }
        }
        return candidates.get(0);
    }

    /**
     * 三个生效位置上的全部背包（按槽位优先级排列，绝不返回 null / 空元素）。
     * <p>Curios 没装 / 没这两个槽 / 反射链路失败时 {@link RsccCuriosTerminalSlot#slotStacks}
     * 返回空表 —— 本方法于是只剩胸甲槽这一档，不会抛异常。</p>
     */
    public static List<ItemStack> candidates(final Player player) {
        final List<ItemStack> found = new ArrayList<>(3);
        addIfJetpack(found, player.getItemBySlot(EquipmentSlot.CHEST));
        addIfJetpack(found, RsccCuriosTerminalSlot.slotStacks(player, CURIOS_SLOT_BACK));
        addIfJetpack(found, RsccCuriosTerminalSlot.slotStacks(player, CURIOS_SLOT_CHEST));
        return found;
    }

    /** 是否至少有一件生效（HUD / 充能判定用；不看电量）。 */
    public static boolean hasAny(final Player player) {
        return !candidates(player).isEmpty();
    }

    private static void addIfJetpack(final List<ItemStack> out, final ItemStack stack) {
        if (ElectricJetpackItem.isJetpack(stack)) {
            out.add(stack);
        }
    }

    private static void addIfJetpack(final List<ItemStack> out, final List<ItemStack> stacks) {
        for (final ItemStack stack : stacks) {
            addIfJetpack(out, stack);
        }
    }
}
