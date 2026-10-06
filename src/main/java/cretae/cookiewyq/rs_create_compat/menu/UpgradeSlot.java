package cretae.cookiewyq.rs_create_compat.menu;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 升级槽（按机器限定可放入的升级种类）：
 * <ol>
 *     <li>仅接受 {@code allowed} 中列出的 RS 原版升级物品（速度 / 堆叠 / 范围 / 自动合成），
 *     每台机器只放行它真正生效的升级——例如范围充电器只允许速度/堆叠/范围，不允许自动合成。</li>
 *     <li>每个槽仅 1 个；同一容器内单种升级上限由 {@link #maxPerUpgrade} 决定：
 *     自动合成最多 1 个，其余最多 6 个（与界面右侧 6 个插件槽一致，六个都能放且都生效）。</li>
 *     <li>空槽位时由 Screen 层绘制悬浮提示（列出本机器允许的升级种类与上限，样式照搬 RS 原版）。</li>
 *     <li>上限对拾取点击、Shift 快速移动均生效；方块实体层另有校验，确保约束不落空。</li>
 * </ol>
 */
public class UpgradeSlot extends SlotItemHandler {
    /** 普通升级（速度/堆叠/范围）允许的最大数量：与 6 格插件槽一致，写多少就必须能放多少。 */
    public static final int MAX_PER_UPGRADE = 6;
    /** 自动合成升级：全容器最多 1 个。 */
    private static final int AUTOCRAFTING_MAX = 1;
    private static final String AUTO_PATH = "autocrafting_upgrade";
    private static final String CREATIVE_RANGE_PATH = "creative_range_upgrade";

    /** 默认允许：速度 / 堆叠 / 自动合成（装填器、定量保持器这类会用到自动合成的机器）。 */
    private static final List<String> DEFAULT_ALLOWED =
        List.of("speed_upgrade", "stack_upgrade", "autocrafting_upgrade");

    private final List<String> allowed;
    /**
     * 「单种升级总数」计数的<b>起点下标</b>（跳过 handler 里位于插件槽<b>之前</b>的 ghost 标记槽）。
     *
     * <p><b>为什么需要它（真 bug 根因）</b>：定量保持器把 4 / 1 个 ghost 标记槽与 6 个插件槽放在
     * <b>同一个 {@link Container}</b> 里；{@link InvWrapper} 会把整个容器暴露给本槽，于是
     * {@link #countOfKindInHandler} 连<b>标记槽里的物品</b>也一起数进来。玩家只要在标记槽里标记过
     * 一个「堆叠升级」（例如想常备 64 个），那一格就被误当成「已插入的一个升级」，
     * 同一个方块就<b>只能再插 5 个</b>（该方块类型本身没问题，所以换新方块就好、破坏重放不好——
     * 幽灵标记随整个方块实体 NBT 一起被带走）。</p>
     *
     * <p>把计数限制在真正的插件槽区间后，「标记槽里放什么」与「能插几个升级」彻底解耦。</p>
     */
    private final int countFromIndex;

    public UpgradeSlot(final IItemHandler itemHandler, final int index, final int xPosition, final int yPosition) {
        this(itemHandler, index, xPosition, yPosition, DEFAULT_ALLOWED);
    }

    public UpgradeSlot(final IItemHandler itemHandler,
                       final int index,
                       final int xPosition,
                       final int yPosition,
                       final List<String> allowedUpgrades) {
        this(itemHandler, index, xPosition, yPosition, allowedUpgrades, 0);
    }

    public UpgradeSlot(final IItemHandler itemHandler,
                       final int index,
                       final int xPosition,
                       final int yPosition,
                       final List<String> allowedUpgrades,
                       final int countFromIndex) {
        super(itemHandler, index, xPosition, yPosition);
        this.allowed = List.copyOf(allowedUpgrades);
        // 计数起点夹在 [0, index]：本槽自己（index）永远被 countOfKindInHandler 单独跳过，
        // 起点不可能比它更靠后，因此这里收敛一次即可，不必在下游反复防御。
        this.countFromIndex = Math.max(0, Math.min(index, countFromIndex));
    }

    /** 该槽位允许放入的升级种类（path 集合）。 */
    public List<String> getAllowedPaths() {
        return allowed;
    }

    /** 单种升级的上限：自动合成 / 创造范围各 1 个，其余 6 个（与插件槽数一致）。 */
    public int maxPerUpgrade(final String path) {
        return maxForPath(path);
    }

    /** 按升级 path 判定单种上限（供非 UpgradeSlot 实例（如容器包装槽）复用同一套规则）。 */
    public static int maxForPath(final String path) {
        return (AUTO_PATH.equals(path) || CREATIVE_RANGE_PATH.equals(path)) ? AUTOCRAFTING_MAX : MAX_PER_UPGRADE;
    }

    /** 把升级 path 列表转成物品实例（供 tooltip 渲染图标 + 名称）。 */
    public static List<ItemStack> stacksFor(final List<String> paths) {
        return toStacks(paths);
    }

    /** 该机器允许放入的升级物品实例（供空槽 tooltip 渲染图标+名称）。 */
    public List<ItemStack> allowedStacks() {
        return toStacks(allowed);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getMaxStackSize(final ItemStack stack) {
        return 1;
    }

    @Override
    public boolean mayPlace(final ItemStack stack) {
        final String path = pathOf(stack);
        if (path == null || !allowed.contains(path)) {
            return false;
        }
        // 每槽最多 1 个升级：非空槽不再接受放入（配合 getMaxStackSize=1 构成双保险）
        if (!getItem().isEmpty()) {
            return false;
        }
        return countOfKindInHandler(stack, path) < maxPerUpgrade(path);
    }

    private int countOfKindInHandler(final ItemStack stack, final String path) {
        int count = 0;
        final IItemHandler handler = getItemHandler();
        for (int i = Math.max(0, countFromIndex); i < handler.getSlots(); i++) {
            if (i == getSlotIndex()) {
                continue;
            }
            final ItemStack inSlot = handler.getStackInSlot(i);
            if (!inSlot.isEmpty() && inSlot.is(stack.getItem())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 基于 {@link Container}（SimpleContainer 等）的升级槽。
     * <p><b>为什么用 {@link InvWrapper} 包一层，而不是直接返回匿名 {@code Slot}</b>：
     * 界面层判定「空插件槽要弹升级 tooltip」是靠 {@code hoveredSlot instanceof UpgradeSlot}
     * （全工程统一写法）。此前这里返回的是匿名 {@code Slot}，`instanceof UpgradeSlot` 恒为 false，
     * 于是高级物品定量保持器 / 归流缓存仓的空插件槽**永远不弹升级提示**。
     * 换成真正的 {@link UpgradeSlot} 后，`instanceof` 判定、按机器限定的允许清单
     * （{@link #allowedStacks()}）与单种上限全部自动生效，无需在各界面重复写一遍。
     */
    public static Slot forContainer(final Container container, final int index,
                                    final int xPosition, final int yPosition) {
        return forContainer(container, index, xPosition, yPosition, DEFAULT_ALLOWED);
    }

    /**
     * 基于 {@link Container} 的升级槽，并显式声明「单种升级计数从第几个下标起」
     * （见 {@link #countFromIndex}）：容器里位于插件槽之前的是 ghost 标记槽时传该下标。
     */
    public static Slot forContainer(final Container container, final int index,
                                    final int xPosition, final int yPosition,
                                    final int countFromIndex) {
        return new UpgradeSlot(new InvWrapper(container), index, xPosition, yPosition,
            DEFAULT_ALLOWED, countFromIndex);
    }

    public static Slot forContainer(final Container container,
                                    final int index,
                                    final int xPosition,
                                    final int yPosition,
                                    final List<String> allowedUpgrades) {
        // InvWrapper 把 Container 适配成 IItemHandler；底层仍是同一个容器，读写语义与原来完全一致
        return new UpgradeSlot(new InvWrapper(container), index, xPosition, yPosition, allowedUpgrades);
    }

    public static Slot forContainer(final Container container,
                                    final int index,
                                    final int xPosition,
                                    final int yPosition,
                                    final List<String> allowedUpgrades,
                                    final int countFromIndex) {
        return new UpgradeSlot(new InvWrapper(container), index, xPosition, yPosition,
            allowedUpgrades, countFromIndex);
    }

    /** 解析物品的升级 path（refinedstorage:speed_upgrade → "speed_upgrade"）；非 RS 升级返回 null。 */
    @javax.annotation.Nullable
    private static String pathOf(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return null;
        }
        return id.getPath();
    }

    /** 默认允许的升级物品实例（兼容旧调用）。 */
    public static List<ItemStack> getAllowedUpgradeStacks() {
        return toStacks(DEFAULT_ALLOWED);
    }

    private static List<ItemStack> toStacks(final List<String> paths) {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final String path : paths) {
            final net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("refinedstorage", path));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                stacks.add(new ItemStack(item));
            }
        }
        return stacks;
    }
}
