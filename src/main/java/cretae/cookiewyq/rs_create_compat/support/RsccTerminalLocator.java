package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定位玩家身上「第一个」高级远程多功能终端，返回「用于打开界面的 {@link SlotReference}」+「存活栈」。
 *
 * <p><b>为什么要有这个类</b>：快捷键此前走 RS 的 {@code RefinedStorageApi#useSlotReferencedItem}
 * →{@code CompositeSlotReferenceProvider#findForUse}，它要求<b>只找到唯一一个</b>引用：
 * 找到 0 个报「找不到」，找到 <b>&gt;1 个直接报「重复」并拒绝打开</b>；而提示文案恒用第一个候选
 * 物品（普通版）的名字，于是玩家体感是「生存版打不开、创造版能开」。本类把查找收归自有：
 * 客户端（决定是否叠加模式切换按钮）与服务端（快捷键权威打开）<b>共用同一套优先级</b>，
 * 并且<b>多个也只取第一个、绝不拒绝</b>。</p>
 *
 * <p><b>固定优先级</b>（双端一致，保证客户端读写的模式与服务端打开的是同一台）：
 * 主手 → 副手 → 背包/快捷栏下标 {@code 0..35} → Curios 饰品槽。</p>
 *
 * <p><b>只看类型不看型号</b>：判定是 {@code stack.getItem() instanceof AdvancedRemoteTerminalItem}
 * <b>或</b>物品注册 id 属于 {@link #TERMINAL_IDS}（普通 / 满电 / 创造三件的 id 双保险），
 * 因此三个型号走<b>完全相同</b>的路径，不存在「某个型号能开、另一个不能」的分叉。</p>
 *
 * <p>本类不引用任何客户端专属类型，双端均可调用。</p>
 */
public final class RsccTerminalLocator {
    /** 快捷栏 + 主背包的格数（0..35）。指数 36..40 是盔甲与副手，副手已由上面单独处理，不重复扫描。 */
    private static final int INVENTORY_END = 36;

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccTerminalLocator.class);
    /** 已提示过「身上有多台终端」的玩家（每名玩家只记一次，避免每次按键都刷日志）。 */
    private static final Set<UUID> MULTIPLE_LOGGED = ConcurrentHashMap.newKeySet();
    /** 已提示过「一台都没找到」的玩家（每名玩家只记一次，避免每次按键都刷日志）。 */
    private static final Set<UUID> NOT_FOUND_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * 本模组三件终端的物品 id（普通 / 满电 / 创造）。
     * <p>与 {@link #isTerminal} 的类判定组成<b>双保险</b>：三件终端都由本模组注册、id 恒定，
     * 因此即使将来新增型号、或某个型号换成别的实现类，也不会出现「某一台按快捷键提示找不到」。</p>
     */
    private static final Set<ResourceLocation> TERMINAL_IDS = Set.of(
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "advanced_remote_terminal"),
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "advanced_remote_terminal_charged"),
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "creative_advanced_remote_terminal")
    );

    private RsccTerminalLocator() {
    }

    /** 找到的终端位置：服务端打开界面用的引用 + 双端读写模式 NBT 用的存活栈。 */
    public record Location(SlotReference slotReference, ItemStack stack) {
    }

    /**
     * 按固定优先级列出玩家身上所有高级远程多功能终端（顺序即优先级）。
     * <p>返回全部而非短路取第一个，是为了在「多台」时能给出一次日志说明（玩家反馈「打不开」时便于定位），
     * 而选择逻辑本身只取第一个。</p>
     */
    public static List<Location> findAll(final Player player) {
        final List<Location> found = new ArrayList<>(2);
        addHand(found, player, InteractionHand.MAIN_HAND);
        addHand(found, player, InteractionHand.OFF_HAND);
        addInventory(found, player);
        addCurios(found, player);
        return found;
    }

    /**
     * 找第一台终端：一个都没有 → {@link Optional#empty()}；有多个 → 按优先级取第一个（<b>不拒绝</b>），
     * 并在服务端打一条一次性日志说明。
     */
    public static Optional<Location> locate(final Player player) {
        final List<Location> found = findAll(player);
        if (found.isEmpty()) {
            logNothingFound(player);
            return Optional.empty();
        }
        final Location chosen = found.getFirst();
        if (found.size() > 1 && !player.level().isClientSide()) {
            logMultipleOnce(player, found.size(), chosen.stack());
        }
        return Optional.of(chosen);
    }

    /** 主手 / 副手：用 RS 自带的引用（主手 = 快捷栏当前选中格，副手 = 副手格）。 */
    private static void addHand(final List<Location> found, final Player player, final InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (isTerminal(stack)) {
            found.add(new Location(RefinedStorageApi.INSTANCE.createInventorySlotReference(player, hand), stack));
        }
    }

    /** 背包 / 快捷栏：跳过主手所在下标，避免「拿在手上」被同时算成两台（只影响计数日志，位置本身也更直观）。 */
    private static void addInventory(final List<Location> found, final Player player) {
        final int mainHandIndex = player.getInventory().selected;
        for (int index = 0; index < INVENTORY_END; index++) {
            if (index == mainHandIndex) {
                continue;
            }
            final ItemStack stack = player.getInventory().getItem(index);
            if (isTerminal(stack)) {
                found.add(new Location(RsccTerminalReferences.createSlotReference(index), stack));
            }
        }
    }

    /** Curios 饰品槽：Curios 缺失 / 没插终端 → 什么都不加（反射链路失败也不会抛异常）。 */
    private static void addCurios(final List<Location> found, final Player player) {
        for (final SlotReference reference : RsccCuriosTerminalSlot.findAllTerminalReferences(player)) {
            final Optional<ItemStack> stack = reference.resolve(player);
            if (stack.isPresent() && isTerminal(stack.get())) {
                found.add(new Location(reference, stack.get()));
            }
        }
    }

    /** <b>只看类型</b>（不看 {@code Type}）：三版终端一视同仁；类判定之外再用物品 id 兜一层底。 */
    private static boolean isTerminal(final ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof AdvancedRemoteTerminalItem) {
            return true;
        }
        return TERMINAL_IDS.contains(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * 一台都没找到时打一条节流日志（只服务端、每名玩家一次）：
     * 记清「翻过哪几处、Curios 是否可用」，玩家反馈「某台终端打不开」时可以直接对着日志定位。
     * <p>不带刷屏风险：同一名玩家只记一次；成功找到时完全不产生日志。</p>
     */
    private static void logNothingFound(final Player player) {
        if (player.level().isClientSide() || !NOT_FOUND_LOGGED.add(player.getUUID())) {
            return;
        }
        LOGGER.info("No advanced remote terminal found for {}: scanned hands + inventory 0..{} "
            + "(Curios loaded={}, {} terminal(s) visible in Curios)",
            player.getName().getString(), INVENTORY_END - 1, OptionalDeps.isCuriosLoaded(),
            RsccCuriosTerminalSlot.findAllTerminalReferences(player).size());
    }

    /** 「身上有多台终端 → 用优先级第一台」的一次性说明（每名玩家只记一次）。 */
    private static void logMultipleOnce(final Player player, final int count, final ItemStack chosen) {
        if (MULTIPLE_LOGGED.add(player.getUUID())) {
            LOGGER.info("Player {} carries {} advanced remote terminals; shortcut opens the first by priority "
                + "(main hand > off hand > inventory 0..{} > Curios): {}", player.getName().getString(), count,
                INVENTORY_END - 1, chosen.getItem());
        }
    }
}
