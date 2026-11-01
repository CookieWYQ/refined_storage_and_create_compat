package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * <b>「只看不动」的物流能力</b>：给「鼠标指上去要能看见内部存储」这一类需求用的唯一包装。
 *
 * <h2>用户定下的硬规则</h2>
 * <p>用户原话：「<i>以后所有「储存物品 / 带缓存」的方块或设备，都必须对外暴露 Inventory</i>」，
 * 并明确要求「例如资源接收器：鼠标指针指向它时，应能显示其内部存储」。</p>
 *
 * <h2>为什么默认是<b>只读</b>（而不是 RS 自动合成仓那种「只出不进」）</h2>
 * <ul>
 *     <li><b>不引入第二条出料路径</b>：接收器的缓存由请求器写入、由它自己朝 {@code FACING} 输出
 *     （见 {@code NetworkResourceReceiverBlockEntity} 类注释）。一旦 {@code extractItem} /
 *     {@code drain} 放行，自动化就能把「已经投递成功」的资源重新抽走，
 *     守恒账与「请求器负责投递、接收器负责落地」的口径立刻出现第二套；</li>
 *     <li><b>中间缓存仓更危险</b>：它本身已经是 RS 网络的 {@code StorageProvider}，同一批磁盘已经
 *     被网络看见一次。若再允许外部按 IItemHandler 抽出 / 插入，就会出现「同一份资源两条读写路径」，
 *     甚至与 {@code RsccCacheExposedStorage} 的优先级 / 复合语义互相打架；</li>
 *     <li><b>Jade / 信息类模组只需要读</b>：Jade 官方通用存储 provider 取的是
 *     {@code getSlots()} + {@code getStackInSlot()}（服务端采集、随 Jade 通道下发），
 *     完全不需要写入能力。只读包装因此足够满足「看得见」，且不改变任何自动化语义。</li>
 * </ul>
 *
 * <h2>两种视图</h2>
 * <ul>
 *     <li>{@link #item(IItemHandler)} / {@link #fluid(IFluidHandler)}：<b>委托读</b>真实存储。
 *     用于接收器缓存这种本来就是 {@code IItemHandler} / {@code IFluidHandler} 的对象；</li>
 *     <li>{@link #itemOf(Supplier)} / {@link #fluidOf(Supplier)}：把 RS 的
 *     {@link ResourceAmount} 快照（{@code Storage#getAll()} 的返回值）转成只读槽位。
 *     用于中间缓存仓（盘内存储）与请求器回滚缓冲这类「内容不是一个现成 IItemHandler」的地方。</li>
 * </ul>
 *
 * <h2>第 96 轮追加的第三种视图：<b>可写包装</b>（默认只进不出）</h2>
 * <p>用户第 96 轮实测原话：「<i>网络资源接收器还是不能跟溜槽之类的进行交互</i>」。
 * 原因不是能力没暴露，而是暴露出来的 {@link Item#insertItem} / {@link Fluid#fill} 一律原样退回 ——
 * 相邻的 Create 溜槽（{@code ChuteBlockEntity#grabCapability} 用
 * {@code side.getOpposite()} 的<b>非空</b>侧别查询，再 {@code ItemHandlerHelper.insertItemStacked}
 * 先 SIMULATE 后 EXECUTE）问一次就被「原样退回」劝退，于是玩家看到的是「根本不交互」。</p>
 * <p>因此新增 {@link #writableItem(IItemHandler, ItemInsertSink)} /
 * {@link #writableFluid(IFluidHandler, FluidInsertSink)}：<b>读照样委托真实存储</b>
 * （Jade / 相邻扫描看得见内容）、<b>写入交给 sink</b>（由方块实体把它接到「缓存唯一写入入口」
 * ——与请求器投递同一个 {@code applyResource}，因此容量上限与守恒只有一套账）。</p>
 *
 * <h2>第 101 轮：可写包装补上「<b>外抽落点</b>」这一半（用户第二次实测仍不交互）</h2>
 * <p>用户第 101 轮原话：「<i>网络资源接收器还是无法正常跟溜槽交互；与溜槽交互的时候它是无视方向的
 * —— 你就是写错了</i>」。第 96 轮只答对了「推入」那一半，而 <b>Create 溜槽对上方容器根本不推</b>：
 * 空的普通溜槽每 tick 在 {@code ChuteBlockEntity#tick} 里走 {@code handleInputFromAbove()} →
 * {@code grabCapability(Direction.UP)} → {@code ItemHelper.extract(inv, canAccept, UPTO, 16, false)},
 * 也就是<b>从上方容器里抽</b>。它唯一会「推」进相邻容器的路径是
 * {@code handleDownwardOutput}（溜槽自己手里已经有东西时往下倒）。
 * 于是「把溜槽放在接收器下面 / 让溜槽从接收器里取料」这个最自然的摆法
 * <b>只会用到 {@code extractItem}</b>，而第 96 轮把 {@code extractItem} 写死成恒空 ⇒
 * 无论溜槽摆在哪个方向都<b>一点反应都没有</b>（用户说的「无视方向」就是这个现象的直译）。</p>
 * <p>修正：把「只进不出」降级为<b>默认</b>，新增
 * {@link WritableItem#withExtraction(ItemExtractSink)} /
 * {@link WritableFluid#withDrain(FluidDrainSink)} 两个显式入口。
 * 接收器（且<b>只有</b>接收器）在 {@code registerCapabilities} 里接上真实抽出口，
 * 于是同一份缓存既能被推入（溜槽手上有货时往下倒）也能被抽出（空溜槽从上方吸），
 * 两条路都落在缓存唯一账上（{@code simulate} 只回答、{@code execute} 才写 + {@code setChanged()}）。
 * 其它方块（中间缓存仓 / 执行舱 / 请求器…）<b>不接落点</b>，语义与改前逐字相同。</p>
 * <p><b>既有只读语义一字未改</b>：{@link Item} / {@link Fluid} / {@link SnapshotItem} /
 * {@link SnapshotFluid} 的 {@code insertItem(…)} / {@code fill(…)} 仍然原样退回 / 返回 0，
 * 仍被中间缓存仓等方块使用（它们<b>不</b>允许外部写入）。</p>
 *
 * <h2>⚠️ 与「侧别」的关系（防止 RS 把自己的网络节点当外部存储）</h2>
 * <p>「朝哪些面暴露」由各 {@code registerCapabilities} 决定（本类的包装与侧别无关）。当前工程里
 * <b>有两种</b>口径，而不是一种：</p>
 * <ul>
 *     <li><b>默认口径（除接收器外全部沿用）：只在 {@code direction == null}（无侧别查询）时暴露。</b>
 *     Jade 的 {@code CommonProxy#findItemHandler} 正是用 {@code side = null} 查询；而 RS 外部存储方块
 *     （{@code AbstractExternalStorageBlockEntity#loadStorage}）用 {@code incomingDirection = facing.getOpposite()}
 *     （<b>非空</b>）创建 {@code CapabilityCacheImpl}。因此这些只读视图<b>不会被 RS 外部存储方块看见</b>，
 *     也就不可能出现「网络节点把自己的内容再当外部存储收一遍」的双重计数。
 *     例：中间缓存仓（{@code IntermediateCacheBlockEntity}）—— 它自己是 RS 存储源，必须守住这条。
 *     逐条证据见自检 {@code tools/selfcheck_round88_storage_inventory_exposure.py}。</li>
 *     <li><b>接收器例外（用户点名要求）：{@code direction == null} 与<b>任意</b> {@code Direction}
 *     都返回同一个视图。</b> 用户原话：「<i>网络资源接收器在作为一个 inventory 提供者时不应该有方向限制，
 *     并且方向只针对于成链机制</i>」。因此 {@code NetworkResourceReceiverBlockEntity} 的注册
 *     <b>故意</b>不判方向（漏斗 / 管道 / 相邻扫描器按面查询都能看见缓存内容）。
 *     <b>这份视图是「可写 + 可抽」的两向包装</b>（第 101 轮，见上一节）：Create 溜槽就是按
 *     {@code side.getOpposite()} 这种<b>非空</b>侧别查询能力的，若只在 {@code direction == null} 暴露，
 *     溜槽<b>连看都看不见</b>接收器 —— 那是用户第 96 轮「还是不能跟溜槽之类的进行交互」的一半原因；
 *     另一半是「只进不出」，见上一节。</li>
 * </ul>
 * <p><b>接收器例外的代价（如实记录）</b>：RS 外部存储方块指向接收器时，会把这份视图当成外部存储
 * 「看见」——终端里的数量是<b>真实可取</b>的（第 101 轮起 {@code extractItem} 不再恒空），
 * 操作员在终端里取货＝从接收器缓存取货，语义等价、不会复制；代价只是「接收器缓存同时算进网络存储」
 * 这一条展示口径。写入 / 抽出永远走缓存唯一入口、受容量上限约束，
 * 因此<b>不可能</b>写坏 / 复制资源。</p>
 */
public final class ViewOnlyHandlers {
    private ViewOnlyHandlers() {
    }

    // ==================== 委托式（真实存储已经是 handler） ====================

    /** 物品：读委托真实存储；插入原样退回、抽取恒空。 */
    public static IItemHandler item(final IItemHandler delegate) {
        return new Item(delegate);
    }

    /** 流体：读委托真实存储；{@code fill} 恒 0、{@code drain} 恒空。 */
    public static IFluidHandler fluid(final IFluidHandler delegate) {
        return new Fluid(delegate);
    }

    // ==================== 可写包装（只进不出；第 96 轮：溜槽 / 漏斗 / 管道能往接收器里推） ====================

    /**
     * 物品：读委托真实存储；<b>插入交给 {@code sink}</b>（返回它真正受理的件数）；
     * 抽取默认恒空。
     *
     * <p><b>返回类型是 {@link WritableItem} 而不是 {@code IItemHandler}</b>：调用方按需再用
     * {@link WritableItem#withExtraction} 显式接上「外抽落点」（第 101 轮：接收器需要它 ——
     * Create 溜槽对上方容器**只抽不推**，见 {@link WritableItem#extractItem} 的注释）。
     * 不接落点时行为与改前逐字相同（抽取恒空），因此别的方块（一律不接）语义零变化。</p>
     *
     * @param sink 唯一的写入落点。实现方必须满足 {@code IItemHandler} 的契约：
     *             {@code simulate} 时只回答、不改任何状态；返回值 ∈ {@code [0, stack.getCount()]}。
     */
    public static WritableItem writableItem(final IItemHandler delegate, final ItemInsertSink sink) {
        return new WritableItem(delegate, sink, null);
    }

    /** 流体：读委托真实存储；<b>{@code fill} 交给 {@code sink}</b>；{@code drain} 默认恒空（同物品的说明）。 */
    public static WritableFluid writableFluid(final IFluidHandler delegate,
                                              final FluidInsertSink sink) {
        return new WritableFluid(delegate, sink, null);
    }

    /**
     * 物品写入落点的唯一抽象。
     * <p><b>为什么是「返回受理件数」而不是「返回剩余栈」</b>：接收器侧的真实入口是
     * {@code NetworkResourceReceiverBlockEntity#applyResource(ResourceKey, long, boolean)}，
     * 它本来就按「实际受理量」返回（请求器投递用的同一个方法）。用「件数」做抽象就不需要
     * 在两层之间来回拼 {@link ItemStack}，受理量也不可能在传递中丢失。</p>
     */
    @FunctionalInterface
    public interface ItemInsertSink {
        /**
         * @param stack    要写入的栈（<b>调用方不得依赖它被修改</b>；实现方也不得改它）
         * @param simulate {@code true} = 只回答、绝不改任何状态
         * @return 真正受理的件数（0 = 一件都不收）
         */
        int insert(ItemStack stack, boolean simulate);
    }

    /** 流体写入落点的唯一抽象（语义与 {@link ItemInsertSink} 完全相同，单位 mB）。 */
    @FunctionalInterface
    public interface FluidInsertSink {
        int fill(FluidStack stack, boolean simulate);
    }

    /**
     * 物品<b>外抽落点</b>的唯一抽象（第 101 轮新增，只有「接收器」这一类方块会接上）。
     *
     * <h2>为什么必须有它（Create 溜槽的真实行为）</h2>
     * <p>Create 6.0.10 的 {@code ChuteBlockEntity} 对<b>上方</b>的容器是<b>抽</b>而不是「被推」：
     * 空的普通溜槽每 tick 走 {@code handleInputFromAbove()} → {@code grabCapability(Direction.UP)}
     * → {@code ItemHelper.extract(inv, canAccept, UPTO, 16, false)}。也就是说
     * 「把溜槽放在容器下面」这个最自然的摆法，靠的是 {@code IItemHandler#extractItem}；
     * 容器若把 {@code extractItem} 写死成空，溜槽<b>永远吸不到东西</b>，
     * 玩家看到的就是「溜槽跟这台机器完全不交互」。</p>
     * <p>所以「只进不出」只能作为<b>默认</b>，不能作为唯一形态：需要用传送带 / 溜槽 / 漏斗把东西
     * <b>搬出去</b>的方块必须显式接一个真实的抽出口，让「抽」也落到同一份缓存账上。</p>
     *
     * <p><b>契约（与 {@link ItemInsertSink} 对称）</b>：{@code simulate} 时只回答、绝不改任何状态；
     * 返回的栈件数 ∈ {@code [0, amount]}；实现方负责在真正抽取后把方块标记为已改动（落盘）。</p>
     */
    @FunctionalInterface
    public interface ItemExtractSink {
        /**
         * @param slot    被抽的槽位下标（调用方按 {@code getSlots()} 遍历，必须照实使用）
         * @param amount  最多抽多少件（&gt; 0）
         * @param simulate {@code true} = 只回答、绝不改任何状态
         * @return 真正（或将会）被抽出的栈；空栈 = 一点都抽不出
         */
        ItemStack extract(int slot, int amount, boolean simulate);
    }

    /**
     * 流体<b>外排落点</b>的唯一抽象（与 {@link ItemExtractSink} 对称，单位 mB）。
     * <p>{@code resource} 为空栈表示调用方走的是 {@code drain(int maxDrain, ...)} 那一支
     * （「随便什么流体，抽这么多就行」）；非空表示要按流体 + 组件精确匹配。</p>
     */
    @FunctionalInterface
    public interface FluidDrainSink {
        FluidStack drain(FluidStack resource, int maxDrain, boolean simulate);
    }

    // ==================== 快照式（内容来自 RS ResourceAmount 列表） ====================

    /**
     * 物品：每次访问都从 {@code source} 现取一份 RS 资源清单，只呈现 {@link ItemResource}。
     * <p><b>为什么每次现取</b>：盘内存储 / 回滚缓冲的内容随时会变；缓存在 handler 里只会显示过期数据。
     * 槽位数因此也可能变化 —— 这是只读视图，调用方（Jade）按当前 {@code getSlots()} 遍历，
     * 不存在「槽位下标写死」的契约。</p>
     */
    public static IItemHandler itemOf(final Supplier<List<ResourceAmount>> source) {
        return new SnapshotItem(source);
    }

    /** 流体：每次访问都从 {@code source} 现取一份 RS 资源清单，只呈现 {@link FluidResource}。 */
    public static IFluidHandler fluidOf(final Supplier<List<ResourceAmount>> source) {
        return new SnapshotFluid(source);
    }

    // ==================== 实现 ====================

    /** 委托式物品视图。 */
    public static final class Item implements IItemHandler {
        private final IItemHandler delegate;

        public Item(final IItemHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getSlots() {
            return delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            return delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            return stack; // 只读：原样退回（绝不吞物品）
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            return ItemStack.EMPTY; // 只读：不允许任何外部抽取
        }

        @Override
        public int getSlotLimit(final int slot) {
            return delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return false;
        }
    }

    /** 委托式流体视图。 */
    public static final class Fluid implements IFluidHandler {
        private final IFluidHandler delegate;

        public Fluid(final IFluidHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getTanks() {
            return delegate.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            return delegate.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(final int tank) {
            return delegate.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return false;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            return 0; // 只读：不接受任何外部注入
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            return FluidStack.EMPTY; // 只读：不允许任何外部抽取
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    /** 快照式物品视图：一个槽位 = 一种物品，数量按 RS 存量显示。 */
    public static final class SnapshotItem implements IItemHandler {
        private final Supplier<List<ResourceAmount>> source;

        SnapshotItem(final Supplier<List<ResourceAmount>> source) {
            this.source = source;
        }

        /** 当前快照里的物品条目（顺序 = 传入清单顺序，保证同一 tick 内稳定）。 */
        private List<ResourceAmount> entries() {
            final List<ResourceAmount> out = new ArrayList<>();
            final List<ResourceAmount> all = source.get();
            if (all == null) {
                return out;
            }
            for (final ResourceAmount each : all) {
                if (each != null && each.amount() > 0L && each.resource() instanceof ItemResource) {
                    out.add(each);
                }
            }
            return out;
        }

        @Nullable
        private ResourceAmount entry(final int slot) {
            final List<ResourceAmount> entries = entries();
            return slot >= 0 && slot < entries.size() ? entries.get(slot) : null;
        }

        @Override
        public int getSlots() {
            return entries().size();
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            final ResourceAmount entry = entry(slot);
            if (entry == null) {
                return ItemStack.EMPTY;
            }
            final ItemResource item = (ItemResource) entry.resource();
            return item.toItemStack((int) Math.min(entry.amount(), Integer.MAX_VALUE));
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            return ItemStack.EMPTY;
        }

        /** 快照视图的「每槽上限」没有真实容量语义：报 int 上限，避免调用方把大存量截断成 64。 */
        @Override
        public int getSlotLimit(final int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return false;
        }
    }

    /** 快照式流体视图：一个罐 = 一种流体，数量按 RS 存量显示。 */
    public static final class SnapshotFluid implements IFluidHandler {
        private final Supplier<List<ResourceAmount>> source;

        SnapshotFluid(final Supplier<List<ResourceAmount>> source) {
            this.source = source;
        }

        private List<ResourceAmount> entries() {
            final List<ResourceAmount> out = new ArrayList<>();
            final List<ResourceAmount> all = source.get();
            if (all == null) {
                return out;
            }
            for (final ResourceAmount each : all) {
                if (each != null && each.amount() > 0L && each.resource() instanceof FluidResource) {
                    out.add(each);
                }
            }
            return out;
        }

        @Nullable
        private ResourceAmount entry(final int tank) {
            final List<ResourceAmount> entries = entries();
            return tank >= 0 && tank < entries.size() ? entries.get(tank) : null;
        }

        @Override
        public int getTanks() {
            return entries().size();
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            final ResourceAmount entry = entry(tank);
            if (entry == null) {
                return FluidStack.EMPTY;
            }
            final FluidResource fluid = (FluidResource) entry.resource();
            return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(fluid.fluid()),
                (int) Math.min(entry.amount(), Integer.MAX_VALUE), fluid.components());
        }

        @Override
        public int getTankCapacity(final int tank) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return false;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    /**
     * <b>可写包装（只进不出）</b>：读委托真实存储，插入落到 {@link ItemInsertSink}，抽取恒空。
     *
     * <h2>为什么 {@code extractItem} 仍然恒空</h2>
     * <p>用户第 96 轮的诉求只有「<b>推入</b>」（溜槽 / 漏斗 / 管道能写进接收器）。一旦允许外抽，
     * 自动化就能把「请求器刚投递成功」的资源立刻搬走：接收器既是一个「落地缓存」又是一个「取料源」，
     * 守恒账与 {@code doWork()} 朝 {@code FACING} 的输出立刻出现两套口径（谁先抽到算谁的）。
     * 因此本包装对「自动化」是<b>只写</b>的；把资源取出来只有两条既有路径 ——
     * 玩家在界面里取（{@code ReceiverCacheActionPacket}）与接收器朝 {@code FACING} 输出。</p>
     *
     * <h2>约定（原版 / NeoForge / Create 都会按这个用）</h2>
     * <ul>
     *     <li>{@code insertItem(slot, stack, simulate)}：<b>忽略 {@code slot}</b>（与
     *     {@code RsccUnboundedItemStorage#insertItem} 的「任意位置」语义一致），返回<b>没被收下</b>的余量；
     *     {@code simulate} 与 {@code execute} 必须给出<b>同一个</b>受理量 —— Create 溜槽正是先
     *     SIMULATE（{@code ItemHandlerHelper.insertItemStacked(..., true)}）判断「能不能交出去」，
     *     再 EXECUTE 真正移交（{@code ChuteBlockEntity#handleDownwardOutput}）；两次答案不一致就会
     *     「仿真说能收、真写却退回」，玩家看到的是物品卡在溜槽里；</li>
     *     <li>{@code isItemValid}：只回答「此刻还写得进去吗」，因此对满仓报 {@code false}
     *     （调用方据此不再尝试），对能收的报 {@code true}；</li>
     *     <li>{@code getStackInSlot} / {@code getSlotLimit}：<b>照实委托</b>真实缓存
     *     （Jade / 相邻扫描靠它显示内容，这也是用户「必须对外暴露 Inventory」的既有要求）。</li>
     * </ul>
     */
    public static final class WritableItem implements IItemHandler {
        private final IItemHandler delegate;
        private final ItemInsertSink sink;
        /** 外抽落点；{@code null} = 只进不出（默认，与改前逐字相同）。 */
        @Nullable
        private final ItemExtractSink extractSink;

        WritableItem(final IItemHandler delegate, final ItemInsertSink sink) {
            this(delegate, sink, null);
        }

        WritableItem(final IItemHandler delegate, final ItemInsertSink sink,
                     @Nullable final ItemExtractSink extractSink) {
            this.delegate = delegate;
            this.sink = sink;
            this.extractSink = extractSink;
        }

        /**
         * 接上「外抽落点」，返回<b>另一个</b>视图（本类与既有视图都不可变，绝不就地改语义）。
         *
         * <p><b>为什么按需接、而不是所有方块默认放行</b>：外抽会让自动化把「刚投递成功」的资源
         * 立刻搬走，对「只作为落地缓存 / 只作为网络存储源」的方块会开出第二套出料口径。
         * 因此默认仍然恒空；只有<b>确实需要被传送带 / 溜槽 / 漏斗搬走</b>的方块
         * （接收器）在注册处显式接上，语义边界一眼可见。</p>
         *
         * @param extractSink 真实抽出落点（必须遵守 {@link ItemExtractSink} 的 simulate 契约）
         */
        public WritableItem withExtraction(final ItemExtractSink extractSink) {
            return new WritableItem(delegate, sink, extractSink);
        }

        @Override
        public int getSlots() {
            return delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            return delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            if (stack == null || stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            final int accepted = Math.max(0, Math.min(stack.getCount(), sink.insert(stack, simulate)));
            final int left = stack.getCount() - accepted;
            return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
        }

        /**
         * 抽取：<b>没接落点 ⇒ 恒空</b>（默认，见类注释）；接了落点 ⇒ 交给它，并按
         * {@code IItemHandler} 契约夹住返回件数（不能超过调用方要的量）。
         * <p>Create 的溜槽正是用「先 {@code simulate=true} 问、再 {@code simulate=false} 真抽」
         * 两次调用（{@code ItemHelper#extract}），所以落点必须在 simulate 时不改任何状态。</p>
         */
        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            if (extractSink == null || amount <= 0) {
                return ItemStack.EMPTY;
            }
            final ItemStack extracted = extractSink.extract(slot, amount, simulate);
            if (extracted == null || extracted.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return extracted.getCount() > amount ? extracted.copyWithCount(amount) : extracted;
        }

        @Override
        public int getSlotLimit(final int slot) {
            return delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return stack != null && !stack.isEmpty() && sink.insert(stack, true) > 0;
        }
    }

    /** <b>可写包装（默认只进不出）</b>：读委托真实存储，{@code fill} 落到 {@link FluidInsertSink}，
     * {@code drain} 默认恒空；接上 {@link #withDrain} 后按 {@link FluidDrainSink} 真排。 */
    public static final class WritableFluid implements IFluidHandler {
        private final IFluidHandler delegate;
        private final FluidInsertSink sink;
        /** 外排落点；{@code null} = 只进不出（默认）。 */
        @Nullable
        private final FluidDrainSink drainSink;

        WritableFluid(final IFluidHandler delegate, final FluidInsertSink sink) {
            this(delegate, sink, null);
        }

        WritableFluid(final IFluidHandler delegate, final FluidInsertSink sink,
                      @Nullable final FluidDrainSink drainSink) {
            this.delegate = delegate;
            this.sink = sink;
            this.drainSink = drainSink;
        }

        /** 接上「外排落点」，返回另一个视图（语义与 {@link WritableItem#withExtraction} 完全对称）。 */
        public WritableFluid withDrain(final FluidDrainSink drainSink) {
            return new WritableFluid(delegate, sink, drainSink);
        }

        @Override
        public int getTanks() {
            return delegate.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            return delegate.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(final int tank) {
            return delegate.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return stack != null && !stack.isEmpty() && sink.fill(stack, true) > 0;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            if (resource == null || resource.isEmpty()) {
                return 0;
            }
            return Math.max(0, Math.min(resource.getAmount(),
                sink.fill(resource, action == FluidAction.SIMULATE)));
        }

        /** 精确匹配一支：没接落点 ⇒ 恒空；接了 ⇒ 交给落点（simulate 时不改状态）。 */
        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            if (drainSink == null || resource == null || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }
            final FluidStack drained = drainSink.drain(resource, resource.getAmount(),
                action == FluidAction.SIMULATE);
            return clampDrain(drained, resource.getAmount());
        }

        /** 「随便什么流体，抽这么多」分支：落点收到的是空栈（见 {@link FluidDrainSink} 的说明）。 */
        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            if (drainSink == null || maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            final FluidStack drained = drainSink.drain(FluidStack.EMPTY, maxDrain,
                action == FluidAction.SIMULATE);
            return clampDrain(drained, maxDrain);
        }

        /** 按 {@code IFluidHandler} 契约夹住返回量（不能超过调用方要的量）。 */
        private static FluidStack clampDrain(@Nullable final FluidStack drained,
                                             final int maxDrain) {
            if (drained == null || drained.isEmpty()) {
                return FluidStack.EMPTY;
            }
            return drained.getAmount() > maxDrain
                ? drained.copyWithAmount(maxDrain)
                : drained;
        }
    }
}
