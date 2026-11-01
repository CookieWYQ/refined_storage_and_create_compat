package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProviderExternalPatternSink;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.autocrafting.Autocrafter;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.network.ColoredConnectionStrategy;
import com.refinedmods.refinedstorage.common.support.network.InWorldNetworkNodeContainerImpl;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem;
import cretae.cookiewyq.rs_create_compat.network.SequenceAssemblyExecutorNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.MultiFluidCache;
import cretae.cookiewyq.rs_create_compat.support.ViewOnlyHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 序列装配样板库（原“序列装配总控室”）：职责收敛为——
 * <ul>
 *     <li>槽 0 只存放一张「总样板」（序列装配样板）；</li>
 *     <li>方块实体作为网络里的“类自动合成仓”容器：节点是
 *     {@link com.refinedmods.refinedstorage.api.network.impl.node.patternprovider.PatternProviderNetworkNode}，
 *     总样板自动注册为 EXTERNAL 样板；</li>
 *     <li>从任意终端（样板终端 / 无线终端 / 合成网格）对总样板产物发起的就是原生自动合成，
 *     每轮迭代的主原料经 {@link #accept} 投给相邻 Create 产线入口（方块无任何“启动/停止”按钮）；</li>
 *     <li>每轮输入先落在本机的<b>有限上游缓冲</b>里（{@code accept(EXECUTE)} 只写缓冲、恒 ACCEPTED；
 *     {@link #doWork()} 每 tick 写回网络）—— 这样 RS 的「REJECTED 不重试、直接当作成功」永远吞不到
 *     玩家资源，容量与形状见 {@link #BUFFER_ITEM_SLOTS}。</li>
 * </ul>
 */
public class SequenceAssemblyExecutorBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<SequenceAssemblyExecutorNetworkNode>
    implements PatternProviderExternalPatternSink {
    /** 样板库格数（大型双箱规格 6×9）：存放多张总样板，每格一张，全部注册为 EXTERNAL 样板。 */
    public static final int PATTERN_SLOTS = 54;

    /**
     * 上游缓冲的形状与容量（第 121 轮 F2 真修法）。<b>所有容量只在这里定义</b>，代码里不许再出现裸数字。
     *
     * <h2>为什么必须有它（RS 2.0.9 的真实语义）</h2>
     * <p>RS 的 {@code ExternalTaskPattern#acceptsIterationInputs} 先把本轮输入从任务内部暂存
     * {@code extractAll(..., Action.EXECUTE)} <b>扣掉</b>，再调本机 sink 的 EXECUTE；后者只要不是
     * {@code ACCEPTED}，RS <b>只打一条 warn 就当作成功</b>（源码注释原文 {@code "we lie ... potentially
     * void the extracted resources"}）。第 120 轮的「整批 SIMULATE 闸」堵住了「一条都没插」时的半批落地，
     * 但堵不住「不同资源合计超容」与「SIMULATE→EXECUTE 之间空间被抢」——这两种边仍是「插一半再
     * REJECTED」= 后半批被 RS 吞掉。要让 RS 永远吞不到，唯一办法是 <b>EXECUTE 恒 ACCEPTED</b>，
     * 而恒 ACCEPTED 的前提是「输入先落到我们自己手里」：这就是这个有限缓冲。</p>
     *
     * <h2>容量怎么定（都是「桥接 1~2 tick」的暂存，不是第二份存储）</h2>
     * <ul>
     *     <li><b>物品 9 格 × 每格至多 64 = 576 件</b>：RS 每个 task 每 tick 只送<b>一次迭代</b>
     *     （{@code ExternalTaskPattern#step} 里 {@code iterationsToSendToSink--}），一次迭代的主原料
     *     通常就是个位数到一两组；9 格沿用玩家 3×3 合成格的直觉，576 件足够覆盖多轮迭代。
     *     真正的储存在 RS 网络里，缓冲只在「网络暂时写不下」的 tick 里短暂占用 —— 定得越大就越像
     *     一个隐形箱子（玩家会用它当存储），所以刻意压到「一两个 tick 能清空」的量级。</li>
     *     <li><b>流体 1 罐 64,000 mB（= 一桶 × 64）</b>：一次迭代的流体输入在 Create 配方里通常是
     *     250~1000 mB，64 桶足够覆盖多轮迭代；同样远小于任何真实储罐，避免变成隐形流体存储。</li>
     *     <li><b>兜底段与正常缓冲等大</b>（再 9 格 / 再 64,000 mB）：EXECUTE 阶段理论上不可能放不下
     *     （SIMULATE 与 EXECUTE 是同一 tick 背靠背的两次调用，中间没有任何第三方写缓冲），但既然
     *     「EXECUTE 非 ACCEPTED = 资源被 RS 吞掉」，这里不允许有理论漏洞：写入前先就地 drain 一次，
     *     仍放不下才退到兜底段；兜底段也满（只可能是推演不到的竞态）时把余量落到世界并记 error。</li>
     *     <li><b>总量硬上限</b>：物品 ≤ {@link #BUFFER_ITEM_HARD_CAPACITY}、流体 ≤
     *     {@link #BUFFER_FLUID_HARD_CAPACITY_MB}，<b>任何路径都不允许突破</b> —— 缓冲不会无限增长。</li>
     * </ul>
     *
     * <h2>为什么形状是「格」而不是「资源 → 长数量」表</h2>
     * <p>拆方块结算的出口是 {@code BlockContentReleaser.Host#collectItems(List<ItemStack>)}：
     * 逐格存真实 {@link ItemStack} 就能<b>原样</b>交给那套唯一结算路径（回网 / 爆出 / 存方块 NBT 三档
     * 由它统一决定），不必在我们这里再写一遍「长数量拆堆 / 合并」；落盘也直接复用
     * {@code RsccSlotNbt}（带 "Slot" 下标，不会像原版 {@code SimpleContainer#fromTag} 那样把多格挤成一格）。</p>
     */
    public static final int BUFFER_ITEM_SLOTS = 9;
    /** 缓冲单格的最大堆叠（真实可放还受物品自身 {@code maxStackSize} 约束，见 {@link #insertIntoItemBuffer}）。 */
    public static final int BUFFER_ITEM_STACK_LIMIT = 64;
    /** 正常缓冲的物品总量上限（件）。 */
    public static final long BUFFER_ITEM_CAPACITY = (long) BUFFER_ITEM_SLOTS * BUFFER_ITEM_STACK_LIMIT;
    /** EXECUTE 兜底段的物品格数（与正常缓冲等大，见类内容量说明）。 */
    public static final int BUFFER_ITEM_OVERFLOW_SLOTS = 9;
    /** 物品缓冲的物理硬上限（件）= 正常 + 兜底；任何时刻都不允许超过。 */
    public static final long BUFFER_ITEM_HARD_CAPACITY =
        BUFFER_ITEM_CAPACITY + (long) BUFFER_ITEM_OVERFLOW_SLOTS * BUFFER_ITEM_STACK_LIMIT;
    /** 正常缓冲的流体总量上限（mB）= 64 桶。 */
    public static final long BUFFER_FLUID_CAPACITY_MB = 64L * 1000L;
    /** 流体缓冲的物理硬上限（mB）= 正常 + 兜底。 */
    public static final long BUFFER_FLUID_HARD_CAPACITY_MB = BUFFER_FLUID_CAPACITY_MB * 2L;
    /** 缓冲物品的落盘键（与样板库的 "Inventory" 分开，互不干扰）。 */
    private static final String TAG_BUFFER_ITEMS = "BufferItems";
    /** 缓冲流体的落盘键。 */
    private static final String TAG_BUFFER_FLUIDS = "BufferFluids";
    private static final Logger LOGGER = LoggerFactory.getLogger(SequenceAssemblyExecutorBlockEntity.class);

    /**
     * 样板槽唯一判定：只收本模组的<b>序列装配总样板</b>（按物品类型，不看名字 / 文案）。
     *
     * <p><b>为什么它是 static 且必须被所有路径共用</b>：这个库是<b>总样板专用容器</b>
     * （总样板绑定机器 / 逐个步骤认领，必须能唯一定位「哪张是哪台机器的」），
     * 而单元样板是<b>不绑定执行仓</b>、想放哪就放哪的，只在这里被挡。
     * 三条路径全部经本判定收口：①界面真槽位（{@code SequenceAssemblyExecutorMenu.PatternSlot#mayPlace}）、
     * ②客户端镜像容器（同一判定的本地镜像，避免「客户端假装收下、点击又被服务端退回」）、
     * ③物流（漏斗 / 管道走 {@code canPlaceItem}）。</p>
     */
    public static boolean acceptsPattern(final ItemStack stack) {
        return SequenceAssemblyPatternItem.isAssemblyPattern(stack);
    }

    /** 槽 = 总样板（真实物品存放，一格一张）。同时作为 Autocrafter 管理器绑定与破坏掉落共用容器。 */
    private final SimpleContainer inventory = new SimpleContainer(PATTERN_SLOTS) {
        @Override
        public void setItem(final int index, final ItemStack stack) {
            super.setItem(index, stack);
            onPatternSlotChanged(index);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            // 物流路径（InvWrapper / VanillaContainerWrapper）的唯一闸门
            return acceptsPattern(stack);
        }
    };
    private boolean loadingInventory;

    /**
     * 上游缓冲的<b>物品</b>部分：物理 18 格（前 9 格 = 正常缓冲，后 9 格 = EXECUTE 兜底段）。
     * <p>逐格存真实 {@link ItemStack}（理由见 {@link #BUFFER_ITEM_SLOTS} 的容量说明）；写入顺序由
     * {@link #insertIntoItemBuffer} 统一收口，正常路径只会落在前 {@link #BUFFER_ITEM_SLOTS} 格。</p>
     */
    private final SimpleContainer bufferItems =
        new SimpleContainer(BUFFER_ITEM_SLOTS + BUFFER_ITEM_OVERFLOW_SLOTS);

    /**
     * 上游缓冲的<b>流体</b>部分：容量守恒的多流体缓存（物理上限
     * {@link #BUFFER_FLUID_HARD_CAPACITY_MB}，正常路径只用到前 {@link #BUFFER_FLUID_CAPACITY_MB}）。
     */
    private final MultiFluidCache bufferFluids = new MultiFluidCache(BUFFER_FLUID_HARD_CAPACITY_MB);

    public SequenceAssemblyExecutorBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY.get(), pos, state,
            new SequenceAssemblyExecutorNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        this.mainNetworkNode.setSink(this);
        inventory.addListener(container -> {
            if (level != null && !level.isClientSide() && !loadingInventory) {
                refreshPatterns();
            }
        });
    }

    public SequenceAssemblyExecutorNetworkNode getNode() {
        return mainNetworkNode;
    }

    public Container getInventory() {
        return inventory;
    }

    public ItemStack getSample() {
        return inventory.getItem(0);
    }

    public boolean hasSample() {
        return !getSample().isEmpty();
    }

    // ========== 总样板 → 网络样板注册 ==========

    /** 每格总样板解析为 RS Pattern 并注册到对应节点样板位（null 则注销该位）。 */
    private void refreshPatterns() {
        for (int i = 0; i < PATTERN_SLOTS; i++) {
            final ItemStack stack = inventory.getItem(i);
            final Pattern pattern = stack.isEmpty() || level == null
                ? null
                : RefinedStorageApi.INSTANCE.getPattern(stack, level).orElse(null);
            mainNetworkNode.setPattern(i, pattern);
        }
    }

    private void onPatternSlotChanged(final int index) {
        setChanged();
        if (level != null && !level.isClientSide() && !loadingInventory) {
            final ItemStack stack = inventory.getItem(index);
            final Pattern pattern = stack.isEmpty()
                ? null
                : RefinedStorageApi.INSTANCE.getPattern(stack, level).orElse(null);
            mainNetworkNode.setPattern(index, pattern);
            if (pattern != null) {
                // 成就触发点：放进的这张总样板真的解析成了 RS Pattern（服务端；重复 fire 由原版去重）
                cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onAssemblyPatternLoaded(this);
            }
        }
    }

    @Override
    public void setLevel(final Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            refreshPatterns();
        }
    }

    // ========== 自动合成任务 sink + 有限上游缓冲（第 121 轮 F2 真修法） ==========

    /**
     * 自动合成任务 sink：把每一轮输入收进<b>本机自己的有限上游缓冲</b>，再由
     * {@link #drainBufferedResources()} 每 tick 写回网络（容量与形状见 {@link #BUFFER_ITEM_SLOTS}）。
     *
     * <h2>为什么不能再「先插网络、失败就 REJECTED」</h2>
     * <p>RS 2.0.9 的 {@code ExternalTaskPattern#acceptsIterationInputs} 会先
     * {@code extractAll(iterationInputs, internalStorage, Action.EXECUTE)} 把这些输入从任务内部暂存
     * <b>扣掉</b>，然后才调用本方法的 EXECUTE；只要返回值不是 {@code ACCEPTED}，它<b>只打一条 warn
     * 就当作成功</b>（源码注释原文：{@code "we lie, do as if the insertion was successful, and
     * potentially void the extracted resources"}）。也就是说 <b>REJECTED 不会重试</b>，没写进去的
     * 部分被 RS 自己吞掉。第 120 轮的「整批 SIMULATE 闸」只堵住了「一条都没插」时整批拒绝，
     * 堵不住 ①不同资源合计超过剩余容量 ②SIMULATE 与 EXECUTE 之间空间被抢 —— 这两条边仍然是
     * 「插一半再 REJECTED」= 后半批被 RS 销毁。要让 RS 永远吞不到东西，只能让 <b>EXECUTE 恒
     * ACCEPTED</b>；而恒 ACCEPTED 的前提是「输入先落到我们自己手里」：这就是上游缓冲。</p>
     *
     * <h2>三条路径（本方法只做前两条）</h2>
     * <ol>
     *     <li><b>SIMULATE 只判缓冲空间</b>：按 {@link ResourceKey} 聚合总量后<b>整批复演</b>
     *     （{@link #canBufferForIteration}）。放不下 → {@link ExternalPatternSink.Result#REJECTED}。
     *     这一步安全：RS 在 SIMULATE 阶段只是用它挑 sink，非 {@code ACCEPTED} 意味着本 tick
     *     <b>不扣输入、不调 EXECUTE</b>，缓冲 drain 之后下一 tick 再来（不会 void）。</li>
     *     <li><b>EXECUTE 只写缓冲、<u>恒</u> ACCEPTED</b>：{@link #writeIterationToBuffer}，
     *     绝不碰网络，也绝不返回 {@code REJECTED} —— 返回非 ACCEPTED 就等于把资源还给 RS 让它吞。
     *     SIMULATE 与 EXECUTE 是同一 tick 里背靠背的两次调用（RS 先
     *     {@code getSinkThatIsAcceptingResources} 再 {@code accept(EXECUTE)}），中间没有任何第三方
     *     写缓冲，所以「复演通过 ⇒ 写入必成功」；万一出现推演不到的竞态，写入前会先就地 drain 一次，
     *     再退到兜底段，最坏只是把余量落到世界并记 error，<b>返回值仍然是 ACCEPTED</b>。</li>
     *     <li><b>drain（每 tick）</b>：{@link #doWork()} 把缓冲写回 {@code StorageNetworkComponent}，
     *     部分写入就留剩余、下一 tick 继续 —— 「网络真的放不下」时唯一的等待点在这里，
     *     而不是「让 RS 把资源吞掉」。</li>
     * </ol>
     *
     * <h2>既有语义原样保留</h2>
     * <ul>
     *     <li>先按 {@link ResourceKey} 聚合总量（同批重复出现的同种资源合并 —— 逐条判定会各自看到
     *     「未变的缓冲状态」而全部放行）；</li>
     *     <li>{@code amount <= 0} 一律跳过（旧实现的 {@code Math.max(1, …)} 会凭空插入 1 件）；</li>
     *     <li>只收 {@link ItemResource} / {@link FluidResource}，其它资源类型继续 {@code SKIPPED}。</li>
     * </ul>
     */
    @Override
    public ExternalPatternSink.Result accept(final Collection<ResourceAmount> resources, final Action action) {
        // ① 按 resource 聚合总量：同批多次出现同一资源时，逐条判会各自看到「未变的缓冲状态」。
        //    用 LinkedHashMap 保持样板原始顺序（写入顺序 = 复演顺序，见 canBufferForIteration）。
        final Map<ResourceKey, Long> totals = new LinkedHashMap<>();
        for (final ResourceAmount amount : resources) {
            final ResourceKey resource = amount.resource();
            if (!(resource instanceof ItemResource) && !(resource instanceof FluidResource)) {
                continue; // 其它资源类型不适用本机
            }
            final long count = amount.amount();
            if (count <= 0L) {
                continue; // 0 数量一律不收（旧实现的 Math.max(1, …) 会凭空插入 1 件）
            }
            totals.merge(resource, count, Long::sum);
        }
        if (totals.isEmpty()) {
            return ExternalPatternSink.Result.SKIPPED; // 没有本机适用的资源（与旧语义一致）
        }
        if (!canBufferForIteration(totals)) {
            if (action == Action.SIMULATE) {
                // 唯一允许 REJECTED 的地方：SIMULATE 阶段 RS 不会扣输入，本 tick 换下一个 sink，
                // 全部 sink 都不收时任务保持内部暂存、下一 tick 重试 —— 不会 void。
                return ExternalPatternSink.Result.REJECTED;
            }
            // EXECUTE 兜底（理论上不可达）：先就地 drain 一次把缓冲腾空，再写；绝不把资源还给 RS。
            drainBufferedResources();
        }
        if (action == Action.SIMULATE) {
            return ExternalPatternSink.Result.ACCEPTED; // 整批复演通过：sink 可接受本批
        }
        // ③ EXECUTE：只写缓冲，恒 ACCEPTED（见本方法 javadoc 与 BUFFER_ITEM_SLOTS 的容量说明）。
        writeIterationToBuffer(totals);
        return ExternalPatternSink.Result.ACCEPTED;
    }

    /**
     * SIMULATE 用的<b>整批复演</b>（只读，绝不改缓冲状态）：正常缓冲能否<b>完整</b>放下这一批。
     * <p>为什么必须「复演」而不是「算剩余空间」：物品槽是按堆叠上限装的（例如某件物品只能堆 1），
     * 用「空格数 × 64」估出来的剩余空间会在「多个不可堆叠物品」时高估，于是 SIMULATE 通过、
     * EXECUTE 却写不满 ⇒ 又回到「先扣后失败」。这里用<b>与真实写入完全相同的插入顺序</b>在副本上
     * 跑一遍，通过 ⇒ 真实写入必然放得下（{@link #insertIntoItemBuffer} 是纯函数，两边共用）。</p>
     */
    private boolean canBufferForIteration(final Map<ResourceKey, Long> totals) {
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        long fluidTotal = 0L;
        for (final Map.Entry<ResourceKey, Long> entry : totals.entrySet()) {
            if (!(entry.getKey() instanceof FluidResource fluid)) {
                continue;
            }
            final ResourceLocation id = fluidId(fluid);
            if (id == null) {
                return false; // 拿不到注册名：既不能落盘也不能 drain，收进来只会变成幽灵条目
            }
            // 数据组件必须「编码 → 解码」可往返：不可往返意味着落盘后再读回来已经变成另一个
            // ResourceKey（drain 时写回去的资源就变了 = 悄悄改玩家东西），宁可在这里拒（SIMULATE 安全）。
            final CompoundTag encoded = MarkerEntry.encodeComponents(fluid.components(), registries);
            if (!MarkerEntry.decodeComponents(encoded, registries).equals(fluid.components())) {
                return false;
            }
            fluidTotal += entry.getValue();
        }
        if (bufferFluids.getStored() + fluidTotal > BUFFER_FLUID_CAPACITY_MB) {
            return false; // 正常缓冲的流体上限（兜底段只在 EXECUTE 兜底时才会被用到）
        }
        long itemUnits = 0L;
        for (final Map.Entry<ResourceKey, Long> entry : totals.entrySet()) {
            if (entry.getKey() instanceof ItemResource) {
                itemUnits += entry.getValue();
            }
        }
        if (itemUnits > BUFFER_ITEM_CAPACITY) {
            return false; // 单批就超过正常缓冲总量上限：留下不丢，靠 drain 逐 tick 消化（诚实边界见类注释）
        }
        // 在「前 BUFFER_ITEM_SLOTS 格」的副本上按真实顺序复演（兜底段不参与 SIMULATE 判定）。
        final NonNullList<ItemStack> preview = NonNullList.withSize(BUFFER_ITEM_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < BUFFER_ITEM_SLOTS; i++) {
            preview.set(i, bufferItems.getItem(i).copy());
        }
        for (final Map.Entry<ResourceKey, Long> entry : totals.entrySet()) {
            if (entry.getKey() instanceof ItemResource item
                && insertIntoItemBuffer(preview, item, entry.getValue()) > 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把整批输入写进缓冲（<b>EXECUTE 的唯一落点</b>：不碰网络、恒不失败、绝不返回 Result）。
     * <p>先按正常缓冲写，放不下的余量退到兜底段（物理上限见 {@link #BUFFER_ITEM_HARD_CAPACITY} /
     * {@link #BUFFER_FLUID_HARD_CAPACITY_MB}）。兜底段也满时（只可能是推演不到的竞态）把余量落到
     * 方块上方并记 error —— <b>宁可变成世界里的掉落物，也不交还给 RS</b>（交还 = 非 ACCEPTED = 被吞）。</p>
     */
    private void writeIterationToBuffer(final Map<ResourceKey, Long> totals) {
        final NonNullList<ItemStack> slots = bufferItems.getItems();
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        for (final Map.Entry<ResourceKey, Long> entry : totals.entrySet()) {
            if (!(entry.getKey() instanceof ItemResource item)) {
                continue;
            }
            final long leftover = insertIntoItemBuffer(slots, item, entry.getValue());
            if (leftover > 0L) {
                LOGGER.error("[rscc] executor buffer item hard limit hit on EXECUTE: {} x{} could not be"
                    + " buffered; spilling to the world instead of handing it back to RS", item, leftover);
                spillItemsToWorld(item, leftover);
            }
        }
        for (final Map.Entry<ResourceKey, Long> entry : totals.entrySet()) {
            if (!(entry.getKey() instanceof FluidResource fluid)) {
                continue;
            }
            final ResourceLocation id = fluidId(fluid);
            if (id == null) {
                spillFluidsToWorld(fluid, entry.getValue());
                continue;
            }
            final CompoundTag encoded = MarkerEntry.encodeComponents(fluid.components(), registries);
            if (!MarkerEntry.decodeComponents(encoded, registries).equals(fluid.components())) {
                spillFluidsToWorld(fluid, entry.getValue()); // 不可往返 ⇒ 不能落盘，只能当场落地
                continue;
            }
            final long accepted = bufferFluids.insert(id, encoded, entry.getValue());
            final long leftover = entry.getValue() - accepted;
            if (leftover > 0L) {
                LOGGER.error("[rscc] executor buffer fluid hard limit hit on EXECUTE: {} x{} mB could not be"
                    + " buffered; spilling to the world instead of handing it back to RS", fluid, leftover);
                spillFluidsToWorld(fluid, leftover);
            }
        }
        setChanged();
    }

    /**
     * 把一个物品资源按「先并入同类堆、再占空格」的顺序写进给定槽位；返回没放进去的余量（0 = 全放下）。
     * <p><b>为什么复演与写入共用本方法</b>：两边是同一个纯函数、同一批、同一顺序 ⇒ SIMULATE 的
     * 结论对 EXECUTE 严格成立。单格容量取 {@code min(物品自身 maxStack, BUFFER_ITEM_STACK_LIMIT)}，
     * 于是「格数 × 64」就是物理硬上限（有些物品只能堆 1 时自然更小，复演会如实算出来）。</p>
     *
     * @param slots 真实缓冲的 {@code NonNullList}，或它的副本（复演）
     */
    private static long insertIntoItemBuffer(final NonNullList<ItemStack> slots,
                                             final ItemResource resource, final long amount) {
        long remaining = amount;
        // ① 先补已有的同类堆（资源 = 物品 + 数据组件，逐项相等才算同一资源）。
        for (int i = 0; i < slots.size() && remaining > 0L; i++) {
            final ItemStack stack = slots.get(i);
            if (stack.isEmpty() || !resource.equals(ItemResource.ofItemStack(stack))) {
                continue;
            }
            final int room = Math.min(stack.getMaxStackSize(), BUFFER_ITEM_STACK_LIMIT) - stack.getCount();
            if (room <= 0) {
                continue;
            }
            final int moved = (int) Math.min((long) room, remaining);
            slots.set(i, stack.copyWithCount(stack.getCount() + moved));
            remaining -= moved;
        }
        // ② 再占用空格。
        for (int i = 0; i < slots.size() && remaining > 0L; i++) {
            if (!slots.get(i).isEmpty()) {
                continue;
            }
            final int room = Math.min(resource.toItemStack(1).getMaxStackSize(), BUFFER_ITEM_STACK_LIMIT);
            if (room <= 0) {
                continue;
            }
            final int moved = (int) Math.min((long) room, remaining);
            slots.set(i, resource.toItemStack(moved));
            remaining -= moved;
        }
        return remaining;
    }

    /** 极端兜底：物品余量按堆叠上限拆成多份落在方块上方（不做任何静默丢弃）。 */
    private void spillItemsToWorld(final ItemResource resource, final long amount) {
        if (level == null) {
            return;
        }
        long remaining = amount;
        while (remaining > 0L) {
            final int room = Math.max(1, Math.min(resource.toItemStack(1).getMaxStackSize(),
                BUFFER_ITEM_STACK_LIMIT));
            final int chunk = (int) Math.min((long) room, remaining);
            Block.popResource(level, worldPosition.above(), resource.toItemStack(chunk));
            remaining -= chunk;
        }
    }

    /** 极端兜底：流体余量按桶落在方块上方（与 {@code BlockContentReleaser} 的流体硬规则同一归宿）。 */
    private void spillFluidsToWorld(final FluidResource resource, final long amount) {
        if (level == null || amount <= 0L) {
            return;
        }
        final FluidStack stack = new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(resource.fluid()),
            (int) Math.min(Integer.MAX_VALUE, amount), resource.components());
        for (final ItemStack bucket : cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser
            .fluidsAsBuckets(List.of(stack))) {
            Block.popResource(level, worldPosition.above(), bucket);
        }
    }

    /** 流体资源 → 注册名（落盘 / 缓存键 / drain 共用；拿不到注册名返回 null）。 */
    private static ResourceLocation fluidId(final FluidResource resource) {
        return BuiltInRegistries.FLUID.getKey(resource.fluid());
    }

    // ---------- drain：每 tick 把缓冲写回网络（部分写入留剩余） ----------

    /**
     * 每 server tick 把上游缓冲写回 RS 网络：能写多少写多少，剩下的留在缓冲里下 tick 继续。
     * <p>由 {@link #doWork()} 在 RS 节点步进<b>之前</b>调用（先腾空缓冲，本 tick 的
     * {@code accept(SIMULATE)} 才更容易通过）。没有网络 / 网络写不下时只保留缓冲，绝不丢。</p>
     * <p><b>守恒</b>：物品严格按 {@code storage.insert(...)} 的返回值扣减（多扣一件 = 凭空消失，
     * 少扣一件 = 双计）；流体同样只 {@link MultiFluidCache#extract} 「真的写进去了的量」。</p>
     */
    private void drainBufferedResources() {
        if (level == null || level.isClientSide()) {
            return;
        }
        final Network network = mainNetworkNode.getNetworkOrNull();
        if (network == null) {
            return; // 没接网络：缓冲原样保留（落盘 + 拆方块结算都还在）
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        boolean changed = drainItemBuffer(storage);
        changed |= drainFluidBuffer(storage);
        if (changed) {
            setChanged();
        }
    }

    /** 物品缓冲 → 网络；返回是否有内容真的写进去了。 */
    private boolean drainItemBuffer(final StorageNetworkComponent storage) {
        boolean changed = false;
        final NonNullList<ItemStack> slots = bufferItems.getItems();
        for (int i = 0; i < slots.size(); i++) {
            final ItemStack stack = slots.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            final long inserted = storage.insert(ItemResource.ofItemStack(stack), stack.getCount(),
                Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0L) {
                continue;
            }
            changed = true;
            final long remaining = stack.getCount() - Math.min(inserted, stack.getCount());
            slots.set(i, remaining <= 0L ? ItemStack.EMPTY : stack.copyWithCount((int) remaining));
        }
        return changed;
    }

    /** 流体缓冲 → 网络；返回是否有内容真的写进去了。读不出来的条目留给拆方块结算记警告。 */
    private boolean drainFluidBuffer(final StorageNetworkComponent storage) {
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        boolean changed = false;
        for (final MultiFluidCache.Entry entry : bufferFluids.entries()) {
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                continue;
            }
            final FluidResource resource = new FluidResource(fluid,
                MarkerEntry.decodeComponents(entry.nbt(), registries));
            final long inserted = storage.insert(resource, entry.amount(), Action.EXECUTE, Actor.EMPTY);
            if (inserted <= 0L) {
                continue;
            }
            bufferFluids.extract(entry.id(), entry.nbt(), inserted);
            changed = true;
        }
        return changed;
    }

    /**
     * 每 server tick 的节拍（{@code NetworkNodeBlockEntityTicker} → {@code updateActiveness} → 本方法）：
     * <b>先把缓冲写回网络，再让 RS 节点照常步进自动合成任务</b>。
     * <p>顺序为什么是「先 drain 再 {@code super.doWork()}」：drain 腾空缓冲后，本 tick 的任务步进在
     * {@code accept(SIMULATE)} 处更可能通过；反过来先步进，刚腾出的空间就要晚一 tick 才用上。</p>
     */
    @Override
    public void doWork() {
        drainBufferedResources();
        super.doWork();
    }

    /**
     * 拆方块结算：把缓冲里剩下的物品<b>原样</b>交给
     * {@link cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser} 的
     * {@code Host#collectItems}（接线在 {@code SequenceAssemblyExecutorBlock#onRemove}）。
     * <p>为什么必须走那条既有路径：回网 / 爆出 / 存方块 NBT 三档策略、以及「方块物品只掉一次」的
     * 抑制逻辑都收口在 {@code BlockContentReleaser}；这里再另起一条 {@code popResource} 就是第二套
     * 结算，迟早和它互相踩（重复掉落 / 绕过策略）。</p>
     */
    public void collectBufferedItems(final List<ItemStack> out) {
        final NonNullList<ItemStack> slots = bufferItems.getItems();
        for (int i = 0; i < slots.size(); i++) {
            final ItemStack stack = slots.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            slots.set(i, ItemStack.EMPTY);
            out.add(stack);
        }
        setChanged();
    }

    /**
     * 拆方块结算：把缓冲里剩下的流体交给 {@code Host#collectFluids}（同上，唯一结算路径）。
     * <p>读不出注册名的条目（流体所属模组被卸掉）既不能进网络也不能装桶，记一条警告后丢弃 ——
     * 留在缓冲里只会永久占容量。</p>
     */
    public void collectBufferedFluids(final List<FluidStack> out) {
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        for (final MultiFluidCache.Entry entry : bufferFluids.entries()) {
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                LOGGER.warn("[rscc] buffered fluid entry {} is unreadable anymore; dropping it", entry.id());
                continue;
            }
            out.add(new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid),
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.amount())),
                MarkerEntry.decodeComponents(entry.nbt(), registries)));
        }
        bufferFluids.clear();
        setChanged();
    }

    /**
     * 拆方块结算的流体<b>余量回填</b>（{@code BlockContentReleaser.Host#receiveFluids} 的对接点）：
     * 结算器把缓冲流体写回网络时，网络写不下的余量先放回本机缓冲（受
     * {@link #BUFFER_FLUID_HARD_CAPACITY_MB} 约束），放不下的部分原样返回给结算器（它会按桶落地）。
     * <p>为什么需要这一步：本方块没有自己的流体储罐，Host 的 {@code receiveFluids} 默认「全拒」，
     * 于是余量只能按桶落地 —— <b>装不成桶的流体（气体类）会在那条路上被丢弃</b>，与本轮「上游缓冲 =
     * 绝不丢」直接矛盾。放回缓冲后 {@code BlockContentReleaser} 会把 {@code needSave} 置真，
     * 余量随整份方块实体 NBT 存进方块物品，重新放下原样恢复。</p>
     *
     * @return 仍然没放回缓冲的余量（调用方会按桶掉落，保证不吞掉任何东西）
     */
    public List<FluidStack> receiveBufferedFluids(final List<FluidStack> stacks) {
        final List<FluidStack> rejected = new ArrayList<>();
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        for (final FluidStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            final FluidResource resource = new FluidResource(stack.getFluid(), stack.getComponentsPatch());
            final ResourceLocation id = fluidId(resource);
            if (id == null) {
                rejected.add(stack); // 读不出注册名：不能落盘，交回结算器（它会记警告并按桶兜底）
                continue;
            }
            final CompoundTag encoded = MarkerEntry.encodeComponents(resource.components(), registries);
            if (!MarkerEntry.decodeComponents(encoded, registries).equals(resource.components())) {
                rejected.add(stack); // 数据组件不可往返：同上，不能落盘
                continue;
            }
            final long accepted = bufferFluids.insert(id, encoded, stack.getAmount());
            if (accepted < stack.getAmount()) {
                final FluidStack rest = stack.copyWithAmount((int) (stack.getAmount() - accepted));
                if (!rest.isEmpty()) {
                    rejected.add(rest);
                }
            }
        }
        setChanged();
        return rejected;
    }

    // ========== 使方块成为“类自动合成仓”（管理器可列出） ==========

    @Override
    protected InWorldNetworkNodeContainer createMainContainer(final SequenceAssemblyExecutorNetworkNode networkNode) {
        return new MasterPatternContainer(this, networkNode);
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /**
     * 本机在 RS「自动合成仓管理器」里显示的名字所用的语言键（中英各一条，见语言文件）。
     *
     * <p><b>为什么不直接给 {@code Component.translatable}</b>：RS 的
     * {@code AutocrafterManagerBlockEntity#getGroups()} 会在<b>服务端</b>执行
     * {@code a.getAutocrafterName().getString()} 把名字压成 {@code String}，
     * 再以 {@code ByteBufCodecs.STRING_UTF8} 发到客户端
     * （{@code AutocrafterManagerData.Group#name}），客户端
     * {@code AutocrafterManagerScreen#renderGroups} 只画这串死字符串。
     * 专用服务端的语言表里只有 {@code en_us}（客户端选中语言只注入客户端），
     * 于是「可翻译的组件」在这里必然被固化成英文名 —— 这正是玩家在管理器里看到英文名的根因。
     * 现在改为只送语言键（{@link cretae.cookiewyq.rs_create_compat.support.RsccClientText}），
     * 由客户端按自己的语言解析（解析点见 {@code mixin/client/AutocrafterManagerGroupNameMixin}）。</p>
     */
    public static final String AUTOCRAFTER_NAME_KEY = "block.rs_create_compat.sequence_assembly_executor";

    /** 本机在 RS 自动合成仓管理器里的名字：只送语言键，客户端解析（原因见 {@link #AUTOCRAFTER_NAME_KEY}）。 */
    public static Component autocrafterNameComponent() {
        return Component.literal(cretae.cookiewyq.rs_create_compat.support.RsccClientText
            .key(AUTOCRAFTER_NAME_KEY));
    }

    /** 供菜单同步：0 = 红石模式（0/1/2，与 RS 原版机器同一套编码，服务端权威）。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                if (index == 0) {
                    return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                }
                return 0;
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 1;
            }
        };
    }

    // ========== 持久化 ==========

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 逐格带 "Slot"（原版 SimpleContainer#createTag/fromTag 会丢槽位、把多张样板挤成一格）
        tag.put("Inventory", cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(inventory, registries));
        // 上游缓冲必须落盘：这里存的是「RS 已经当作消费成功交给本机、但还没写回网络」的玩家资源，
        // 区块卸载 / 服务器重启时丢了就是真丢（RS 那边已经把这批输入从任务暂存里扣掉了）。
        // 键与样板库的 "Inventory" 分开，避免读档时把缓冲物品误当样板槽。
        tag.put(TAG_BUFFER_ITEMS, cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(
            bufferItems, registries));
        bufferFluids.save(tag, TAG_BUFFER_FLUIDS);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        loadingInventory = true;
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                tag.getList("Inventory", Tag.TAG_COMPOUND), inventory, registries);
        }
        if (tag.contains(TAG_BUFFER_ITEMS)) {
            cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                tag.getList(TAG_BUFFER_ITEMS, Tag.TAG_COMPOUND), bufferItems, registries);
        }
        // load 内部先清空再按容量截断（旧档没有这个键 ⇒ 空缓冲，不会凭空多出东西）
        bufferFluids.load(tag, TAG_BUFFER_FLUIDS);
        loadingInventory = false;
        refreshPatterns();
    }

    /** 注册方块能力：RS 网络节点容器。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        // 样板库（54 格真实物品）：用户硬规则要求「所有储存物品的方块都对外暴露 Inventory」。
        // 只读 + 只在无侧别查询（Jade）时暴露：样板是自动合成语义的一部分，任何外部插入 / 抽取
        // 都会绕开 refreshPatterns() 与「一格一张」的准入判据；RS 外部存储方块用非空朝向查询，
        // 因此也看不到它，不可能把样板再当外部存储收一遍。
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> direction == null
                ? ViewOnlyHandlers.item(new InvWrapper(blockEntity.getInventory()))
                : null
        );
    }

    /** “类自动合成仓”容器：使样板库出现在原版/虚拟自动合成仓管理器中，可被任意终端发现。 */
    private static final class MasterPatternContainer extends InWorldNetworkNodeContainerImpl implements Autocrafter {
        private final SequenceAssemblyExecutorBlockEntity blockEntity;

        private MasterPatternContainer(final SequenceAssemblyExecutorBlockEntity blockEntity,
                                       final com.refinedmods.refinedstorage.api.network.node.NetworkNode node) {
            super(blockEntity, node, "main", 0,
                new ColoredConnectionStrategy(blockEntity::getBlockState, blockEntity.getBlockPos()), null);
            this.blockEntity = blockEntity;
        }

        @Override
        public Component getAutocrafterName() {
            // 只送语言键（RS 会在服务端把它压成 String 再发给客户端，见 AUTOCRAFTER_NAME_KEY 的说明）
            return autocrafterNameComponent();
        }

        @Override
        public Container getPatternContainer() {
            return blockEntity.inventory;
        }

        @Override
        public boolean isVisibleToTheAutocrafterManager() {
            return true;
        }
    }
}
