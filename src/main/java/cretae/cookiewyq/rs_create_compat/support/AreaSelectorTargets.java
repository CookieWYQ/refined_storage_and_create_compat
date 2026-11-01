package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AreaSelectorItem;
import cretae.cookiewyq.rs_create_compat.menu.AdvancedCollectionCacheMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 「区域选定器」写入包<b>共用的落点解析</b>（第 112 轮从 {@code AreaSelectorEditPacket} 抽出来）。
 *
 * <p>第 112 轮新增了第二个写通道 {@code AreaMarkEditPacket}（给单个区域配过滤标记）。两条通道必须
 * <b>逐字同源</b>地判断「这次写的是哪一个物品」，否则会出现「区域编辑走主手、标记编辑走菜单槽」这种
 * 分裂语义 —— 玩家看到的就是「标记配到了另一个选定器上」。因此把判据集中到这里：</p>
 *
 * <ol>
 *     <li>{@code containerId < 0}（世界手势 / 手持编辑）⇒ 只认玩家<b>自己的主手</b>选定器；</li>
 *     <li>{@code containerId >= 0}（菜单路径）⇒ 必须是玩家当前打开的
 *     {@link AdvancedCollectionCacheMenu}、id 一致、{@code stillValid}（方块还在 + 距离），
 *     且槽里确实是选定器；否则<b>丢包</b>（不退回主手，避免陈旧包改到别的物品）；</li>
 *     <li>其余一律空 ⇒ 丢包（防作弊：不能凭空改别人 / 别的槽位的物品）。</li>
 * </ol>
 *
 * <p>返回的是<b>真正在物品栏 / 槽位里的那个 {@link ItemStack} 实例</b>：{@code ItemStack#set(组件)}
 * 改的就是它，所以调用方之后必须显式 {@link #sync}（写回不会触发
 * {@code ItemStackHandler#onContentsChanged}，不通知的话既不落盘、界面也不刷新）。</p>
 */
public final class AreaSelectorTargets {
    private AreaSelectorTargets() {
    }

    /** 解析这次写入的目标选定器（空 = 丢包）。 */
    public static ItemStack resolve(final int containerId, final ServerPlayer player) {
        if (containerId < 0) {
            final ItemStack mainHand = player.getMainHandItem();
            return mainHand.is(RS_Create_Compat.AREA_SELECTOR.get()) ? mainHand : ItemStack.EMPTY;
        }
        final AdvancedCollectionCacheMenu menu = menuOf(containerId, player);
        if (menu == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack inSlot = menu.getAreaSelectorStack();
        return inSlot.is(RS_Create_Compat.AREA_SELECTOR.get()) ? inSlot : ItemStack.EMPTY;
    }

    /** 客户端给的 containerId 对应的<b>已打开</b>高级仓菜单（不是 ⇒ {@code null}）。 */
    @Nullable
    public static AdvancedCollectionCacheMenu menuOf(final int containerId, final ServerPlayer player) {
        if (containerId < 0 || !(player.containerMenu instanceof AdvancedCollectionCacheMenu menu)
            || menu.containerId != containerId || !menu.stillValid(player)) {
            return null;
        }
        return menu;
    }

    /** 目标是不是选定器物品（给「这个物品能不能被这两个包写」的判据用）。 */
    public static boolean isSelector(@Nullable final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof AreaSelectorItem;
    }

    /**
     * 写入后把新数据推回客户端（槽位 / 背包两条路径），并让方块实体标脏 + 让扫描游标失效。
     * <p>与 {@code AreaSelectorEditPacket} 的实现逐字相同 —— 两条写通道必须同一套同步口径。</p>
     */
    public static void sync(final ServerPlayer player, final int containerId) {
        if (containerId >= 0 && player.containerMenu instanceof AdvancedCollectionCacheMenu menu
            && menu.containerId == containerId) {
            // 写回是 `ItemStack#set(组件)`，不会触发 ItemStackHandler#onContentsChanged：
            // 不显式通知的话这次编辑可能不落盘（区块脏标记缺失），扫描游标也会继续用旧数据。
            menu.onAreaSelectorEdited();
            menu.broadcastChanges();
        } else {
            // 主手编辑：屏幕不是容器菜单，玩家的 containerMenu 仍是 inventoryMenu(0)
            player.inventoryMenu.broadcastFullState();
        }
    }
}
