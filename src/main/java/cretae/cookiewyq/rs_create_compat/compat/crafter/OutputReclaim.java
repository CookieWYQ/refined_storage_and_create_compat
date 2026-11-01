package cretae.cookiewyq.rs_create_compat.compat.crafter;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 「把 Create 机器的产物收回 RS 网络」的登记表 + 两个回收通道。
 *
 * <h2>为什么需要登记表</h2>
 * <p>Create 的机器不认识 RS，产物也不认识「这是哪台自动合成仓要的」。所以派发时把
 * 「哪台自动合成仓、期望什么产物、还欠几轮」登记下来，产物出现时按登记表核对后写回网络。
 * 登记表是<b>纯服务端、按维度隔离</b>的（{@link WeakHashMap} 以 {@link ServerLevel} 为键），
 * 不会跨存档/跨线程共享。</p>
 *
 * <h2>两条回收通道</h2>
 * <ol>
 *     <li><b>动力合成器</b>：产物在 {@code Phase.CRAFTING} 结束后由 Create 逐件
 *     {@code dropItem(Vec3, ItemStack)}（build/createsrc/.../MechanicalCrafterBlockEntity.java:480）
 *     掉到地上。我们在 {@code dropItem} 的 HEAD 处拦截：与登记表里的期望产物精确匹配
 *     （物品 + 组件）、且 RS 网络能整堆收下 ⇒ 写回网络并取消掉落；否则<b>原样掉落</b>
 *     （等价于今天的 Create 行为，绝不吞）。</li>
 *     <li><b>动力搅拌机/盆</b>：产物落在盆的<b>输出背包 + 输出罐</b>里，由
 *     {@link #tick}（挂在自动合成仓每 tick 的 {@code doWork} 尾部）主动搬运进网络。
 *     只有「最近使用过的那台自动合成仓 → 那个盆」这条登记关系存在时才搬，避免把玩家手动
 *     用盆的产物也吸走。</li>
 * </ol>
 *
 * <h2>诚实说明（风险）</h2>
 * <ul>
 *     <li>动力合成器的回收只覆盖「掉落地面」这条路径。如果 crafter 阵列的链头正对着一台容器，
 *     Create 会把产物直接塞进那台容器（{@code tryInsert()} 分支），本模组不抢——那是 Create
 *     自己的行为，改了反而破坏别家自动化的预期。</li>
 *     <li>匹配窗口内（{@value #CRAFT_TTL} tick）玩家若在<b>同一阵列</b>手动合成出<b>完全相同</b>
 *     的产物，会被误收进网络。已用「精确物品 + 组件」「只认登记过的产物」「pending 计数」把概率
 *     压到极低，但确实存在，如实写在这里。</li>
 * </ul>
 */
public final class OutputReclaim {
    /** 一次派发后等待产物的最长时间（tick）。最慢的 Create crafter（速度 4）单次装配约 500 tick。 */
    private static final int CRAFT_TTL = 1200;
    /** 盆的「活动窗口」：最近一次派发后多久内允许搬产物。 */
    private static final int BASIN_TTL = 400;

    /** 动力合成器：阵列成员坐标 → 登记（同一台阵列的所有成员指向同一个对象）。 */
    private static final Map<ServerLevel, Map<BlockPos, CraftPlan>> CRAFT_PLANS = new WeakHashMap<>();
    /** 搅拌盆：自动合成仓坐标 → 活动窗口到期时刻。 */
    private static final Map<ServerLevel, Map<BlockPos, Long>> BASIN_ACTIVITY = new WeakHashMap<>();

    private OutputReclaim() {
    }

    /** 登记一轮「已派发、等产物」。 */
    public static void registerCraft(final ServerLevel level,
                                     final BlockPos autocrafterPos,
                                     final List<BlockPos> members,
                                     final ItemStack mainResult,
                                     final List<ItemStack> expectedOutputs) {
        final Map<BlockPos, CraftPlan> plans = CRAFT_PLANS.computeIfAbsent(level, key -> new HashMap<>());
        final CraftPlan plan = new CraftPlan(autocrafterPos, mainResult.copy(),
            copyAll(expectedOutputs), level.getGameTime() + CRAFT_TTL);
        // 同一阵列的旧登记直接覆盖（机器一次只可能吃一轮，覆盖等价于「重置待收轮次」）
        plan.pending = 1;
        for (final BlockPos member : members) {
            plans.put(member, plan);
        }
        CreateCraftLog.throttled(level, autocrafterPos, "plan",
            "已登记 Create 产物回收：自动合成仓 %s，阵列 %d 格，主产物 %s",
            autocrafterPos, members.size(), mainResult.getItem());
    }

    /** 登记盆的「活动窗口」（不接管输入，只用于允许采集输出）。 */
    public static void registerBasinActivity(final ServerLevel level, final BlockPos autocrafterPos) {
        BASIN_ACTIVITY.computeIfAbsent(level, key -> new HashMap<>())
            .put(autocrafterPos, level.getGameTime() + BASIN_TTL);
    }

    /**
     * 动力合成器的产物拦截：{@code dropItem} 的 HEAD 调用。
     *
     * @return true = 已写回网络、调用方必须取消本次掉落
     */
    public static boolean tryReclaimCraftResult(final ServerLevel level,
                                                final BlockPos crafterPos,
                                                final ItemStack dropped) {
        if (dropped.isEmpty()) {
            return false;
        }
        final Map<BlockPos, CraftPlan> plans = CRAFT_PLANS.get(level);
        if (plans == null) {
            return false;
        }
        final CraftPlan plan = plans.get(crafterPos);
        if (plan == null || plan.pending <= 0 || level.getGameTime() > plan.expiresAt) {
            return false;
        }
        if (!plan.matches(dropped)) {
            return false;
        }
        final Network network = networkOf(level, plan.autocrafterPos);
        if (!NetworkIo.insertItemFully(network, dropped)) {
            return false; // 网络收不下 ⇒ 原样掉落，不销毁
        }
        if (plan.isMainResult(dropped)) {
            plan.pending--;
            if (plan.pending <= 0) {
                removePlan(level, plan);
            }
        }
        CreateCraftLog.throttled(level, plan.autocrafterPos, "reclaimed",
            "Create 产物已回网：%s x%d（自动合成仓 %s）",
            dropped.getItem(), dropped.getCount(), plan.autocrafterPos);
        return true;
    }

    /** 每 tick 的采集入口（挂在自动合成仓 doWork 尾部，仅服务端）。 */
    public static void tick(final ServerLevel level, final AutocrafterBlockEntity autocrafter) {
        prune(level);
        final Map<BlockPos, Long> activity = BASIN_ACTIVITY.get(level);
        if (activity == null || activity.isEmpty()) {
            return;
        }
        final AutocrafterBlockEntity root = CreateCraftTargets.resolveRoot(level, autocrafter);
        if (root != autocrafter) {
            return; // 只在链头采集，避免同一台机器被多台自动合成仓重复搬
        }
        final Long expiresAt = activity.get(root.getBlockPos());
        if (expiresAt == null) {
            return;
        }
        final long now = level.getGameTime();
        if (now > expiresAt) {
            activity.remove(root.getBlockPos());
            return;
        }
        final BasinBlockEntity basin = CreateCraftTargets.resolveBasin(
            CreateCraftTargets.resolveTarget(level, root));
        if (basin == null) {
            activity.remove(root.getBlockPos());
            return;
        }
        final Network network = networkOf(level, root.getBlockPos());
        if (network == null) {
            return;
        }
        if (BasinTargets.collectOutputs(basin, network)) {
            CreateCraftLog.throttled(level, root.getBlockPos(), "basin",
                "搅拌盆产物已回网（自动合成仓 %s → 盆 %s）",
                root.getBlockPos(), basin.getBlockPos());
        }
    }

    // ------------------------------------------------------------------

    @Nullable
    private static Network networkOf(final ServerLevel level, final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : BlockContentReleaser.networkOf(blockEntity);
    }

    private static List<ItemStack> copyAll(final List<ItemStack> stacks) {
        final List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (final ItemStack stack : stacks) {
            copies.add(stack.copy());
        }
        return copies;
    }

    private static void removePlan(final ServerLevel level, final CraftPlan plan) {
        final Map<BlockPos, CraftPlan> plans = CRAFT_PLANS.get(level);
        if (plans == null) {
            return;
        }
        plans.entrySet().removeIf(entry -> entry.getValue() == plan);
        if (plans.isEmpty()) {
            CRAFT_PLANS.remove(level);
        }
    }

    /** 清掉过期的登记（只在有表时做，代价可忽略）。 */
    private static void prune(final ServerLevel level) {
        final long now = level.getGameTime();
        final Map<BlockPos, CraftPlan> plans = CRAFT_PLANS.get(level);
        if (plans != null && !plans.isEmpty()) {
            plans.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
            if (plans.isEmpty()) {
                CRAFT_PLANS.remove(level);
            }
        }
        final Map<BlockPos, Long> activity = BASIN_ACTIVITY.get(level);
        if (activity != null && !activity.isEmpty()) {
            final Iterator<Map.Entry<BlockPos, Long>> iterator = activity.entrySet().iterator();
            while (iterator.hasNext()) {
                if (now > iterator.next().getValue()) {
                    iterator.remove();
                }
            }
            if (activity.isEmpty()) {
                BASIN_ACTIVITY.remove(level);
            }
        }
    }

    private static final class CraftPlan {
        private final BlockPos autocrafterPos;
        private final ItemStack mainResult;
        private final List<ItemStack> expectedOutputs;
        private final long expiresAt;
        private int pending;

        private CraftPlan(final BlockPos autocrafterPos,
                          final ItemStack mainResult,
                          final List<ItemStack> expectedOutputs,
                          final long expiresAt) {
            this.autocrafterPos = autocrafterPos;
            this.mainResult = mainResult;
            this.expectedOutputs = expectedOutputs;
            this.expiresAt = expiresAt;
        }

        private boolean matches(final ItemStack stack) {
            for (final ItemStack expected : expectedOutputs) {
                if (ItemStack.isSameItemSameComponents(expected, stack)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isMainResult(final ItemStack stack) {
            return ItemStack.isSameItemSameComponents(mainResult, stack);
        }
    }
}
