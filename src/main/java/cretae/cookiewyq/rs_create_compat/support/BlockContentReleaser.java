package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemTargetBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 「拆方块时内容物去向」的<b>共用结算器</b>：所有带内部存储的方块破坏时都调用它，
 * 从而保证「三档策略 + 流体硬规则 + 样板特例 + 无网络兜底」是唯一一份实现。
 *
 * <h2>结算规则（三档语义的落地）</h2>
 * <ol>
 *     <li><b>物品</b>：按全局档位。若选 {@link BlockContentMode#NETWORK} 但方块<b>没接网络</b>
 *     （例：离线破坏自动合成仓），自动降级为 {@link BlockContentMode#BLOCK}——<b>不爆出</b>。</li>
 *     <li><b>流体（硬规则）</b>：永远不使用 {@link BlockContentMode#DROP}。能连上网络就
 *     {@code NETWORK}（写回网络），连不上就 {@code BLOCK}（整份存进方块物品 NBT）。
 *     网络写不下的余量会放回方块内部并一并存入 NBT，绝不把流体「喷一地」。</li>
 *     <li><b>样板特例</b>：样板永不回网；全局为 {@link BlockContentMode#NETWORK} 时样板
 *     <b>降级为 {@link BlockContentMode#BLOCK}}</b>（最安全，重新放下原样恢复），
 *     其余档位照旧（DROP / BLOCK）。</li>
 * </ol>
 *
 * <h2>{@code BLOCK} 怎么实现</h2>
 * 直接调用 {@link BlockEntity#saveAdditional} 得到<b>整份</b>方块实体 NBT，再用
 * {@link BlockItem#setBlockEntityData} 写进方块物品；放置时由原版 {@code BlockItem} 的
 * {@code BLOCK_ENTITY_DATA} 机制自动写回，<b>无需每个方块实体单独实现读回逻辑</b>。
 * 与之配套：这些方块在 {@code BLOCK} 档下的 {@code getDrops} 必须返回空表（由
 * {@link #suppressesLoot} 判定），改由本类在 {@code onRemove} 里补掉同一个带 NBT 的方块物品，
 * 避免「掉落物 + 我们补掉」重复产生两个方块物品。
 *
 * <h2>绝不销毁</h2>
 * 任何分支都有明确归宿：进网络 / 爆出 / 存方块（含网络写不下的余量回填方块）。
 */
public final class BlockContentReleaser {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 「已跳过幽灵容器」的节流标记：只打一条日志，说明这次为什么没有结算某个容器。 */
    private static final java.util.concurrent.atomic.AtomicBoolean GHOST_SKIP_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean();

    private BlockContentReleaser() {
    }

    /** 内容提供者：由方块在破坏时把「各分类内容」取出并清空内部存储。 */
    public interface Host {
        /** 取出并清空「普通物品」（升级 / 缓冲 / 蓝图等实实在在的物品）。 */
        void collectItems(List<ItemStack> out);

        /** 取出并清空「样板」（永不回网；样板终端 / 执行仓 / 执行器等的样板槽）。 */
        void collectPatterns(List<ItemStack> out);

        /** 取出并清空「流体」（能回网就回网，否则存方块）。 */
        void collectFluids(List<FluidStack> out);

        /**
         * 网络写不下时把剩余流体放回方块内部（随后会被整份存入方块 NBT）。
         *
         * @return 仍然没接受的余量（调用方会按桶掉落，保证不丢）；默认全部拒收。
         */
        default List<FluidStack> receiveFluids(final List<FluidStack> stacks) {
            return stacks;
        }
    }

    /** 解析后的三档（各分类的最终去向）。 */
    public record Plan(BlockContentMode items, BlockContentMode patterns, BlockContentMode fluids) {
        /** 是否有任一分类需要「存进方块物品 NBT」。 */
        public boolean needsBlockSave() {
            return items == BlockContentMode.BLOCK
                || patterns == BlockContentMode.BLOCK
                || fluids == BlockContentMode.BLOCK;
        }
    }

    /** 读取方块当前接入的 RS 网络（未接入返回 null）。使用 RS 公开 API，不依赖任何内部字段。 */
    @Nullable
    public static Network networkOf(final BlockEntity be) {
        if (be instanceof RsccAutocrafterStorage storage) {
            final Network net = storage.rscc$getNetwork();
            if (net != null) {
                return net;
            }
        }
        if (be instanceof NetworkItemTargetBlockEntity target) {
            return target.getNetworkForItem();
        }
        return null;
    }

    /** 计算三档去向（含无网络兜底、流体硬规则、样板特例）。 */
    public static Plan plan(final Level level, final BlockEntity be) {
        final BlockContentMode policy = BlockContentPolicy.mode(level);
        final boolean connected = networkOf(be) != null;
        // 物品：选了回网但没接网络 → 存方块（不爆出）
        final BlockContentMode items = policy == BlockContentMode.NETWORK && !connected
            ? BlockContentMode.BLOCK
            : policy;
        // 样板：永不回网；全局选回网时降级为存方块
        final BlockContentMode patterns = policy == BlockContentMode.NETWORK
            ? BlockContentMode.BLOCK
            : policy;
        // 流体硬规则：能回网就回网，否则存方块
        final BlockContentMode fluids = connected ? BlockContentMode.NETWORK : BlockContentMode.BLOCK;
        return new Plan(items, patterns, fluids);
    }

    /**
     * 该方块破坏时是否需要「抑制原版战利品表掉落」。
     * <p>为 true 时方块必须在 {@code getDrops} 里返回空表，并由 {@link #release} 统一补掉带 NBT
     * 的方块物品；否则会出现两个方块物品。</p>
     */
    public static boolean suppressesLoot(final Level level, final BlockEntity be) {
        return plan(level, be).needsBlockSave();
    }

    /**
     * 方块的 {@code getDrops} 统一入口：需要存方块 NBT 时返回空表，否则返回原版掉落。
     *
     * @param vanillaDrops 原版 {@code super.getDrops(...)} 的结果
     */
    public static List<ItemStack> filterLoot(final Level level, @Nullable final BlockEntity be,
                                             final List<ItemStack> vanillaDrops) {
        if (be != null && suppressesLoot(level, be)) {
            return new ArrayList<>();
        }
        return vanillaDrops;
    }

    /**
     * 结算并释放方块内容物（在方块的 {@code onRemove} 里调用）。
     *
     * @param blockItem 本方块对应的“全新的”方块物品（仅 BLOCK 档会用到）
     */
    public static void release(final Level level, final BlockPos pos, final BlockEntity be,
                               final ItemStack blockItem, final Host host) {
        // 只在服务端结算：客户端也会走 onRemove，若在这里掉落会产生幽灵实体
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        // 机器集群：破坏成员方块时，先把「整个集群共用的那一份内容」移交给仍在加载的幸存集群。
        // 必须在结算之前做：破坏走「先 getDrops → 再 onRemove」的顺序，不先交出去的话，
        // 整份集群内容会被写进方块物品 NBT，重新放下时再并入一次 = 资源翻倍。
        if (be instanceof RsccClusterable member) {
            RsccMachineCluster.handOverToSurvivors(level, member);
        }
        final Plan plan = plan(level, be);
        final Network network = networkOf(be);

        final List<ItemStack> items = new ArrayList<>();
        final List<ItemStack> patterns = new ArrayList<>();
        final List<FluidStack> fluids = new ArrayList<>();
        // 只有「不存方块」的分类才需要取出；存方块的分类保持内部原样，由整份 NBT 带走
        if (plan.items() != BlockContentMode.BLOCK) {
            host.collectItems(items);
        }
        if (plan.patterns() != BlockContentMode.BLOCK) {
            host.collectPatterns(patterns);
        }
        if (plan.fluids() != BlockContentMode.BLOCK) {
            host.collectFluids(fluids);
        }

        releaseItems(server, pos, plan.items(), items, network);
        releaseItems(server, pos, plan.patterns(), patterns, network);

        boolean needSave = plan.needsBlockSave();
        if (plan.fluids() == BlockContentMode.NETWORK) {
            final List<FluidStack> leftover = insertFluids(network, fluids);
            if (!leftover.isEmpty()) {
                // 网络写不下的余量：放回方块内部并强制存 NBT（绝不喷一地）
                final List<FluidStack> rejected = host.receiveFluids(leftover);
                if (rejected.size() < leftover.size()) {
                    needSave = true;
                }
                dropFluidsAsBuckets(server, pos, rejected);
            }
        }

        if (needSave) {
            saveIntoBlockItem(server, pos, be, blockItem);
        }
    }

    /** 处理某一分类的物品（NETWORK 写入网络；DROP 爆出；BLOCK 不在此处理）。 */
    private static void releaseItems(final Level level, final BlockPos pos, final BlockContentMode mode,
                                     final List<ItemStack> stacks, @Nullable final Network network) {
        if (stacks.isEmpty() || mode == BlockContentMode.BLOCK) {
            return;
        }
        if (mode == BlockContentMode.NETWORK) {
            dropItems(level, pos, insertItems(network, stacks));
            return;
        }
        dropItems(level, pos, stacks);
    }

    /** 把「未被取走的内部状态」整份写进方块物品 NBT 并掉落。 */
    private static void saveIntoBlockItem(final ServerLevel level, final BlockPos pos,
                                          final BlockEntity be, final ItemStack blockItem) {
        // saveWithoutMetadata 公开可用，等价于调用受保护的 saveAdditional（不含 id / x / y / z）
        final CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        BlockItem.setBlockEntityData(blockItem, be.getType(), tag);
        Block.popResource(level, pos, blockItem);
    }

    /** 物品 → 网络；返回没写进去的余量（网络不可用 / 空间不足）。 */
    public static List<ItemStack> insertItems(@Nullable final Network network, final List<ItemStack> stacks) {
        final List<ItemStack> leftover = new ArrayList<>();
        final StorageNetworkComponent storage = storageOf(network);
        if (storage == null) {
            leftover.addAll(stacks);
            return leftover;
        }
        for (final ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            final long inserted = storage.insert(
                ItemResource.ofItemStack(stack), stack.getCount(), Action.EXECUTE, Actor.EMPTY);
            if (inserted < stack.getCount()) {
                final ItemStack rest = stack.copyWithCount((int) (stack.getCount() - inserted));
                if (!rest.isEmpty()) {
                    leftover.add(rest);
                }
            }
        }
        return leftover;
    }

    /** 流体 → 网络；返回没写进去的余量。 */
    public static List<FluidStack> insertFluids(@Nullable final Network network, final List<FluidStack> stacks) {
        final List<FluidStack> leftover = new ArrayList<>();
        final StorageNetworkComponent storage = storageOf(network);
        if (storage == null) {
            leftover.addAll(stacks);
            return leftover;
        }
        for (final FluidStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            final FluidResource resource = new FluidResource(stack.getFluid(), stack.getComponentsPatch());
            final long inserted = storage.insert(resource, stack.getAmount(), Action.EXECUTE, Actor.EMPTY);
            if (inserted < stack.getAmount()) {
                final FluidStack rest = stack.copyWithAmount((int) (stack.getAmount() - inserted));
                if (!rest.isEmpty()) {
                    leftover.add(rest);
                }
            }
        }
        return leftover;
    }

    @Nullable
    private static StorageNetworkComponent storageOf(@Nullable final Network network) {
        return network == null ? null : network.getComponent(StorageNetworkComponent.class);
    }

    private static void dropItems(final Level level, final BlockPos pos, final List<ItemStack> stacks) {
        for (final ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                Block.popResource(level, pos, stack);
            }
        }
    }

    /** 流体余量按桶（含空桶）掉落，作为最后的保底归宿。 */
    private static void dropFluidsAsBuckets(final Level level, final BlockPos pos, final List<FluidStack> stacks) {
        for (final ItemStack bucket : fluidsAsBuckets(stacks)) {
            Block.popResource(level, pos, bucket);
        }
    }

    /**
     * 把流体余量转成「装好流体的桶」物品列表（作为无法回网 / 无法存方块时的保底归宿）。
     * 无法装桶的流体（无桶对应的气体等）会被跳过并记警告。
     */
    public static List<ItemStack> fluidsAsBuckets(final List<FluidStack> stacks) {
        final List<ItemStack> out = new ArrayList<>();
        for (final FluidStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            final ItemStack bucket = FluidUtil.getFilledBucket(stack);
            if (bucket.isEmpty()) {
                LOGGER.warn("Block content fluid could not be bucketed and was not returned to network: {} x{}",
                    stack.getFluid(), stack.getAmount());
                continue;
            }
            out.add(bucket);
        }
        return out;
    }

    // ===================== 供各方块直接复用的收集 / 回填工具 =====================

    /**
     * 取出并清空一个 {@link Container} 的全部物品。
     * <p><b>幽灵容器防线</b>：实现了 {@link GhostContent} 的容器直接跳过（见该接口的说明）——
     * 从那以后任何「往收集清单里塞了幽灵容器」的写法都不会再造成凭空复制。</p>
     */
    public static void collectContainer(final Container container, final List<ItemStack> out) {
        if (skipGhost(container)) {
            return;
        }
        collectContainerRange(container, 0, container.getContainerSize(), out);
    }

    /**
     * 取出并清空 {@link Container} 的 {@code [from, toExclusive)} 槽位。
     * <p>用于「同一个容器里既有真实槽位、又有幽灵标记槽位」的机器（例：定量保持器把 ghost 标记槽
     * 与插件槽放在同一个 {@code SimpleContainer} 里）：调用方显式圈定真实槽位区间即可，
     * 不必改动容器本身的类型。</p>
     */
    public static void collectContainerRange(final Container container, final int from, final int toExclusive,
                                             final List<ItemStack> out) {
        final int start = Math.max(0, from);
        final int end = Math.min(container.getContainerSize(), toExclusive);
        for (int i = start; i < end; i++) {
            final ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            container.setItem(i, ItemStack.EMPTY);
            out.add(stack);
        }
    }

    /**
     * 取出并清空一个 {@link ItemStackHandler} 的全部物品。
     * <p><b>幽灵容器防线</b>：实现了 {@link GhostContent} 的 handler 直接跳过（见该接口的说明）。
     * 这是「收集入口」这一层的兜底，与各机器自己的可掉落白名单互为双保险。</p>
     */
    public static void collectHandler(final ItemStackHandler handler, final List<ItemStack> out) {
        if (skipGhost(handler)) {
            return;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            final ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            handler.setStackInSlot(i, ItemStack.EMPTY);
            out.add(stack);
        }
    }

    /** 幽灵容器的统一判定 + 一次性节流日志（只在首次命中时打一条，避免刷屏）。 */
    private static boolean skipGhost(final Object container) {
        if (!(container instanceof GhostContent)) {
            return false;
        }
        if (GHOST_SKIP_LOGGED.compareAndSet(false, true)) {
            LOGGER.debug("Block content release skipped a ghost/marker container ({}): its contents are UI"
                + " templates and must never be dropped / returned to the network", container.getClass().getName());
        }
        return true;
    }

    /** 取出并清空一个 {@link MultiFluidCache} 的全部流体（无效资源直接丢弃）。 */
    public static void collectFluidCache(final MultiFluidCache cache,
                                         @Nullable final HolderLookup.Provider registries,
                                         final List<FluidStack> out) {
        for (final MultiFluidCache.Entry entry : cache.entries()) {
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                continue;
            }
            out.add(new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid),
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.amount())),
                MarkerEntry.decodeComponents(entry.nbt(), registries)));
        }
        cache.clear();
    }

    /** 把流体放回 {@link MultiFluidCache}，返回仍放不下的余量。 */
    public static List<FluidStack> receiveFluidCache(final MultiFluidCache cache,
                                                     @Nullable final HolderLookup.Provider registries,
                                                     final List<FluidStack> stacks) {
        final List<FluidStack> rejected = new ArrayList<>();
        for (final FluidStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            final ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
            final CompoundTag nbt = MarkerEntry.encodeComponents(stack.getComponentsPatch(), registries);
            final long accepted = cache.insert(id, nbt, stack.getAmount());
            if (accepted < stack.getAmount()) {
                rejected.add(stack.copyWithAmount((int) (stack.getAmount() - accepted)));
            }
        }
        return rejected;
    }

    /** 取出并清空 {@link RsccUnboundedFluidStorage}（多罐）的全部流体。 */
    public static void collectFluidStorage(final RsccUnboundedFluidStorage storage,
                                           final List<FluidStack> out) {
        out.addAll(storage.getTanksSnapshot());
        storage.setFluid(FluidStack.EMPTY);
    }

    /** 把流体放回 {@link RsccUnboundedFluidStorage}，返回仍放不下的余量。 */
    public static List<FluidStack> receiveFluidStorage(final RsccUnboundedFluidStorage storage,
                                                       final List<FluidStack> stacks) {
        final List<FluidStack> rejected = new ArrayList<>();
        for (final FluidStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            final int accepted = storage.fill(stack,
                net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            if (accepted < stack.getAmount()) {
                rejected.add(stack.copyWithAmount(stack.getAmount() - accepted));
            }
        }
        return rejected;
    }
}
