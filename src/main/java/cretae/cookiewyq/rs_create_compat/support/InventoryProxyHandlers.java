package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器代理器对外的能力视图：对象面（{@link ObjectView}）、主内容物面
 * （{@link ContentsItemView} / {@link ContentsFluidView}）、备内容物面
 * （{@link ContentsInputItemView} / {@link ContentsInputFluidView}），以及组件式容器
 * （{@link BundleContentsView}，收纳袋的内容物不在 {@code ItemHandler} 里）。
 *
 * <h2>为什么视图实例必须是「稳定字段」而内层是「每次现解析」</h2>
 * <p>RS / 管道会把能力查询的结果<b>缓存起来</b>（RS 的 {@code BlockCapabilityCache} 缓存的是
 * {@code IItemHandler} 实例本身，不是内容）。如果每次 {@code handlerFor(side)} 都新建对象，
 * 那么每次换代理对象都必须 {@code invalidateCapabilities}，否则外部永远拿着旧实例
 * （设计文档 {@code CONTAINER_PROXY_DESIGN.md} §5.3 / R1）。因此：</p>
 * <ul>
 *     <li>外层视图（对象面 / 主内容物物品 / 主内容物流体 / 备内容物物品 / 备内容物流体）在方块实体
 *     生命周期内<b>恒定</b>；</li>
 *     <li>视图内部每次调用都问 {@link InventoryProxyBlockEntity#innerItem()} /
 *     {@link InventoryProxyBlockEntity#innerFluid()}，而这两个方法按
 *     <b>{@code ItemStack} 身份</b>（{@code innerFor != stack}）重解析；</li>
 *     <li>换代理对象只让 BE 丢掉内层缓存，外层实例不变 ⇒ 外部缓存继续有效，且读到的是新内层。</li>
 * </ul>
 *
 * <h2>为什么对象面只收 1 个、且必须先做能力探测</h2>
 * <ul>
 *     <li>容器物品基本都是 {@code stacksTo(1)}；一槽两个容器会产生「内容物归属歧义」
 *     （设计文档 §6 R13 / D5）。</li>
 *     <li>RS 磁盘、Create 工具箱这类物品<b>没有</b> {@code ItemHandler.ITEM}（RS 2.0.9 的磁盘
 *     只有 UUID 组件，见 {@code docs/RS_DISK_ADAPTER_DESIGN.md} §1），若不探测就会「收得进、
 *     内容物永远 0 格」（同文档 R18）。本模块的磁盘适配器会给 {@code StorageContainerItem}
 *     补注册能力，因此这里额外接受该类（未格式化的盘也先收下，界面上提示「未格式化」）。</li>
 *     <li>存入前必须 {@code copyWithCount(1)}：调用方随后可能复用 / 修改自己那个 stack
 *     （{@code ItemStackHandler} 在上游就是直接把引用放进槽里的，见 §6 R19）。</li>
 * </ul>
 */
public final class InventoryProxyHandlers {
    private InventoryProxyHandlers() {
    }

    /**
     * 对象面：只暴露「代理对象本身」，单槽。
     *
     * <h2>为什么它<b>必须</b>实现 {@link IItemHandlerModifiable}（本轮「背包被吞」的根因）</h2>
     * <p>菜单里的代理槽是 {@code SlotItemHandler}（{@code menu/InventoryProxyMenu.java}）。NeoForge
     * 21.1.248 的 {@code SlotItemHandler#set} 是<b>无条件强制转型</b>：</p>
     * <pre>
     * public void set(ItemStack stack) {
     *     ((IItemHandlerModifiable) this.getItemHandler()).setStackInSlot(index, stack);
     *     this.setChanged();
     * }
     * </pre>
     * <p>本类原先只写 {@code implements IItemHandler} ⇒ 玩家每一次「把东西放进代理槽」都会在
     * {@code set} 处抛 {@code ClassCastException}；而原版 {@code Slot#safeInsert} /
     * {@code Slot#tryRemove} 都是<b>先把 stack 从玩家那边扣掉</b>（{@code split} / {@code extractItem}）
     * 才调用 {@code set}，于是异常一抛，背包已经从玩家手里消失、又没有进代理槽 ⇒ 字面意义上的「吞掉」。</p>
     * <p>对象面是「玩家的单槽」而不是「某个底层库存的转发视图」，所以它<b>是</b>可写的（内容物面才必须
     * 只读转发，见 {@link ContentsItemView}）。</p>
     */
    public static final class ObjectView implements IItemHandlerModifiable {
        private final InventoryProxyBlockEntity proxy;

        public ObjectView(final InventoryProxyBlockEntity proxy) {
            this.proxy = proxy;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            return slot == 0 ? proxy.proxyStack() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            if (slot != 0 || stack.isEmpty() || !isItemValid(slot, stack)) {
                return stack;
            }
            if (!proxy.proxyStack().isEmpty()) {
                // 槽已被占用 ⇒ 原样退回，**绝不覆盖**（覆盖就等于把旧的那个背包静默丢掉）。
                return stack;
            }
            if (!simulate) {
                proxy.setProxyStack(stack.copyWithCount(1));
            }
            // 不论入参 count 是多少，只收 1 个：剩下的原样退回（契约要求返回「没插进去的部分」）。
            return stack.getCount() <= 1 ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - 1);
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            if (slot != 0 || amount <= 0 || proxy.proxyStack().isEmpty()) {
                return ItemStack.EMPTY;
            }
            final ItemStack out = proxy.proxyStack().copyWithCount(1);
            if (!simulate) {
                proxy.setProxyStack(ItemStack.EMPTY);
            }
            return out;
        }

        @Override
        public int getSlotLimit(final int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return slot == 0 && InventoryProxyBlockEntity.isProxyable(stack);
        }

        /**
         * 原版 {@code Slot#set} 的落点（{@code SlotItemHandler#set} 转型后调用）。**替换语义**：
         *
         * <ol>
         *     <li>{@code stack} 为空 ⇒ 只可能来自原版「已经取走」的清理调用（{@code tryRemove} 里
         *     {@code remove()} 之后的那一次），清空是对的；</li>
         *     <li>非空 ⇒ 把<b>调用方给的这个 stack</b> 写进槽。绝不写 {@code EMPTY} 再写新值 ——
         *     那正是「写失败就把旧对象吞掉」的写法；</li>
         *     <li>超过单槽容量（{@code >1}）的部分 ⇒ {@link InventoryProxyBlockEntity#ejectStack} 弹到世界，
         *     <b>绝不截断</b>（原版菜单路径被 {@code getSlotLimit()==1} 限死，走不到这里）。</li>
         * </ol>
         *
         * <p>为什么这里不「先把旧 stack 取出来」：原版 {@code Slot} 的契约是<b>调用方</b>已经用
         * {@code getItem()} 拿到旧对象（例如放置不同物品时先 {@code setCarried(旧)}）。本方法再取一次
         * 会得到同一实例 ⇒ 世界上弹出一次、光标上还有一次 = <b>复制</b>。所以这里只替换，
         * 旧对象归属调用方；「换下来的对象不许丢」由 {@code loadAdditional} / {@code onRemove} /
         * {@code ejectStack} 三条路径各自保证。</p>
         */
        @Override
        public void setStackInSlot(final int slot, final ItemStack stack) {
            if (slot != 0) {
                // getSlots() == 1，正常调用不可能到别的槽；真到了就大声失败，绝不静默吞掉。
                throw new IndexOutOfBoundsException("inventory_proxy object slot must be 0, got " + slot);
            }
            if (stack.isEmpty()) {
                proxy.setProxyStack(ItemStack.EMPTY);
                return;
            }
            if (stack.getCount() > 1) {
                // 单槽容量 1：多出来的必须交给世界，不能就地截断。
                proxy.ejectStack(stack.copyWithCount(stack.getCount() - 1));
            }
            proxy.setProxyStack(stack.copyWithCount(1));
        }
    }

    /**
     * 内容物面的物品视图：把被代理物品自己的 {@code ItemHandler.ITEM} 原样转出去。
     * <p>代理槽本身<b>不</b>混进来（否则同一个容器物品既在对象面又在内容物面，RS 会把它计两次，
     * 设计文档 §5.2.2）。代理槽为空 / 该物品没有物品库存时，本视图 {@code getSlots() == 0}，
     * 是一个「空实现」而不是 {@code null}：实例稳定，外部缓存不会悬空。</p>
     */
    public static final class ContentsItemView implements IItemHandler {
        private final InventoryProxyBlockEntity proxy;

        public ContentsItemView(final InventoryProxyBlockEntity proxy) {
            this.proxy = proxy;
        }

        @Nullable
        private IItemHandler inner() {
            return proxy.innerItem();
        }

        @Override
        public int getSlots() {
            final IItemHandler handler = inner();
            return handler == null ? 0 : handler.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            final IItemHandler handler = inner();
            return handler == null || slot < 0 || slot >= handler.getSlots()
                ? ItemStack.EMPTY
                : handler.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            final IItemHandler handler = inner();
            if (handler == null || slot < 0 || slot >= handler.getSlots()) {
                // 安全值：没有内层 / 槽号非法 ⇒ 原样退回。绝不让第三方工具的一个越界下标
                // 把异常抛进服务端 tick（那会整次交互被吞）。
                return stack;
            }
            return handler.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            final IItemHandler handler = inner();
            if (handler == null || slot < 0 || slot >= handler.getSlots() || amount <= 0) {
                return ItemStack.EMPTY; // 安全值：抽不到就是空，不抛异常
            }
            return handler.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(final int slot) {
            final IItemHandler handler = inner();
            return handler == null || slot < 0 || slot >= handler.getSlots() ? 0 : handler.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            final IItemHandler handler = inner();
            return handler != null && slot >= 0 && slot < handler.getSlots() && handler.isItemValid(slot, stack);
        }
    }

    /**
     * <b>备内容物面</b>的物品视图：<b>只进不出</b>（第 96 轮用户第 3 条：两个内容物面 ⇒ 终端数量翻倍）。
     *
     * <h2>为什么只读要假装空（而不是干脆返回 {@code null}）</h2>
     * <p>RS 2.0.9 的每个 RS 外部存储方块都会自建一个 {@code ExternalStorage}，网络把各 storage
     * 的库存<b>求和</b>，且「不按源方块 / handler 实例去重」。而 {@code ExternalStorage} 的库存快照
     * 来自 {@code CapabilityCache#getItemAmountIterator()} —— 它逐个 {@code getStackInSlot(i)} 读数量。
     * 因此：</p>
     * <ul>
     *     <li>若备面返回 {@code null}：漏斗 / 溜槽 / 管道也一起看不见这一面，推不进来（备面就没意义）；</li>
     *     <li>若备面返回主视图：同一个底层库存被数两遍 ⇒ 终端数量翻倍（用户实机见到的现象）；</li>
     *     <li>所以备面返回「写可用、读恒空」的视图：RS 在它上面读到 0 ⇒ 不参与网络求和，
     *     而 {@code insertItem} 真实落到同一个底层库存（守恒）。</li>
     * </ul>
     *
     * <h2>{@code getSlots() == 1} 是刻意的</h2>
     * <p>备面不是「另一套库存」，它只是同一个底层库存的一个写入口。对外只暴露 1 个槽 + 一个写方法，
     * 插入时把物品<b>依次</b>摊到底层 handler 的所有槽里（与主面 {@link ContentsItemView#insertItem}
     * 落到同一个 {@code innerItem()}）。若把内层的真实槽数报出去，某些调用方会按槽号逐槽插入，
     * 而槽号在两次插入之间会因为内层重排（收纳袋）而漂移 —— 报 1 槽 + 内部摊平没有这个问题，
     * 也不会让任何读取方从槽号推出底层布局。</p>
     *
     * <h2>守恒</h2>
     * <ul>
     *     <li>插入返回「真的没插进去的部分」（内层 {@code insertItem} 的返回值逐个扣减），
     *     绝不截断、绝不复制；</li>
     *     <li>{@code simulate} 一路透传，模拟时不写任何东西；</li>
     *     <li>读取（{@code getStackInSlot} / {@code extractItem}）恒为空 —— 备面拿不走任何东西。</li>
     * </ul>
     */
    public static final class ContentsInputItemView implements IItemHandler {
        /** 备面对外恒报 1 个「输入口」。 */
        private static final int INPUT_SLOTS = 1;

        private final InventoryProxyBlockEntity proxy;

        public ContentsInputItemView(final InventoryProxyBlockEntity proxy) {
            this.proxy = proxy;
        }

        @Nullable
        private IItemHandler inner() {
            return proxy.innerItem();
        }

        @Override
        public int getSlots() {
            return INPUT_SLOTS;
        }

        /** 备面<b>读恒空</b>：RS / Jade / 任何读取方都看不到内容物 ⇒ 不参与网络求和。 */
        @Override
        public ItemStack getStackInSlot(final int slot) {
            return ItemStack.EMPTY;
        }

        /**
         * 真实写进底层：从第 0 槽开始依次交给内层，直到插完或内层全部拒绝。
         * <p>槽号参数只做「是不是 0」的校验，落点由内层决定（见类注释「getSlots() == 1 是刻意的」）。</p>
         */
        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            if (slot != 0 || stack.isEmpty()) {
                return stack;
            }
            final IItemHandler handler = inner();
            if (handler == null) {
                // 没有代理对象 / 该物品没有物品库存：原样退回（守恒，绝不吞）。
                return stack;
            }
            ItemStack remaining = stack;
            for (int i = 0; i < handler.getSlots(); i++) {
                remaining = handler.insertItem(i, remaining, simulate);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
            return remaining;
        }

        /** 备面<b>取恒空</b>：内容物只能从主面抽走。 */
        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            return ItemStack.EMPTY;
        }

        /**
         * 报内层各槽里最大的单槽上限（没有内层时按 64）：插入方据此决定一次试多少。
         * <p>故意<b>不</b>报 0 —— 报到 0 的 handler 会被不少管道当成「不可插入」，备面就形同虚设。
         * 真实容量上限由 {@link #insertItem} 的内层调用决定，返回的 remainder 才是权威。</p>
         */
        @Override
        public int getSlotLimit(final int slot) {
            if (slot != 0) {
                return 0;
            }
            final IItemHandler handler = inner();
            if (handler == null) {
                return 64;
            }
            int max = 0;
            for (int i = 0; i < handler.getSlots(); i++) {
                max = Math.max(max, handler.getSlotLimit(i));
            }
            return Math.max(1, max);
        }

        /**
         * 「现在能不能塞进去至少 1 个」——用模拟插入判定，与真实插入同一套内层规则。
         * <p>比逐个 {@code inner.isItemValid} 更准：容器物品的 {@code isItemValid} 常常不判容量。</p>
         */
        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return slot == 0 && !stack.isEmpty()
                && insertItem(0, stack, true).getCount() < stack.getCount();
        }
    }

    /**
     * 内容物面的流体视图：把被代理物品自己的 {@code FluidHandler.ITEM} 原样转出去
     * （例如流体舱物品 / 带流体库存的背包；RS 磁盘没有流体库存，因此自然是空实现）。
     * <p>对象面<b>不</b>暴露流体（对象面语义是「装卸容器物品本身」）。</p>
     */
    public static final class ContentsFluidView implements IFluidHandler {
        private final InventoryProxyBlockEntity proxy;

        public ContentsFluidView(final InventoryProxyBlockEntity proxy) {
            this.proxy = proxy;
        }

        @Nullable
        private IFluidHandler inner() {
            return proxy.innerFluid();
        }

        @Override
        public int getTanks() {
            final IFluidHandler handler = inner();
            return handler == null ? 0 : handler.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            final IFluidHandler handler = inner();
            return handler == null || tank < 0 || tank >= handler.getTanks()
                ? FluidStack.EMPTY
                : handler.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(final int tank) {
            final IFluidHandler handler = inner();
            return handler == null || tank < 0 || tank >= handler.getTanks() ? 0 : handler.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            final IFluidHandler handler = inner();
            return handler != null && tank >= 0 && tank < handler.getTanks()
                && handler.isFluidValid(tank, stack);
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            final IFluidHandler handler = inner();
            return handler == null ? 0 : handler.fill(resource, action);
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            final IFluidHandler handler = inner();
            return handler == null ? FluidStack.EMPTY : handler.drain(resource, action);
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            final IFluidHandler handler = inner();
            return handler == null ? FluidStack.EMPTY : handler.drain(maxDrain, action);
        }
    }

    /**
     * <b>备内容物面</b>的流体视图：{@code fill} 真实写进底层，{@code drain} 恒空，
     * {@code getFluidInTank} 恒空。
     *
     * <h2>为什么 {@code getTanks()} 仍然报内层的罐数</h2>
     * <p>RS 的 {@code CapabilityCache#getFluidAmountIterator()} 会遍历
     * {@code [0, getTanks())} 再逐个 {@code getFluidInTank(tank)} 读数量 —— 罐数为 0 时它同样读出
     * 「没有流体」，所以对「不参与求和」这个目标来说罐数报 0 也行。但<b>管道</b>（Create 流体管道 /
     * 其它模组的 {@code IFluidHandler} 客户端）常常用 {@code getTanks() == 0} 直接判定「对方不是流体容器」
     * 并断开连接 —— 那样备面就再也接不上任何输入。因此这里<b>报内层的真实罐数</b>，只把「读出来的量」
     * 按 0 处理：管道找得到罐、能 {@code fill} 进去，但 {@code drain} 抽不出来、RS 也数不到。</p>
     *
     * <h2>守恒</h2>
     * <p>{@code fill} 直接转发 {@code inner.fill(resource, action)}（{@code action} 一并透传，模拟不写）；
     * 返回值就是真实收下的 mB 数。{@code getTankCapacity} / {@code isFluidValid} 也转发内层，
     * 管道据此算单次传输量 —— 它们不泄露「罐里有多少」。</p>
     */
    public static final class ContentsInputFluidView implements IFluidHandler {
        private final InventoryProxyBlockEntity proxy;

        public ContentsInputFluidView(final InventoryProxyBlockEntity proxy) {
            this.proxy = proxy;
        }

        @Nullable
        private IFluidHandler inner() {
            return proxy.innerFluid();
        }

        @Override
        public int getTanks() {
            final IFluidHandler handler = inner();
            return handler == null ? 0 : handler.getTanks();
        }

        /** 备面<b>读恒空</b>：RS 的流体库存快照由此遍历 ⇒ 备面不参与网络求和。 */
        @Override
        public FluidStack getFluidInTank(final int tank) {
            return FluidStack.EMPTY;
        }

        /** 容量不是「存量」：照内层报，管道才能正确地算一次能灌多少。 */
        @Override
        public int getTankCapacity(final int tank) {
            final IFluidHandler handler = inner();
            return handler == null || tank < 0 || tank >= handler.getTanks()
                ? 0
                : handler.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            final IFluidHandler handler = inner();
            return handler != null && tank >= 0 && tank < handler.getTanks()
                && handler.isFluidValid(tank, stack);
        }

        /** 真实写进底层（{@code action} 透传：SIMULATE 绝不落地）。 */
        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            final IFluidHandler handler = inner();
            return handler == null ? 0 : handler.fill(resource, action);
        }

        /** 备面<b>抽恒空</b>：流体只能从主面抽走。 */
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
     * 内容物面的「组件式容器」视图：{@code minecraft:bundle}（收纳袋）的内容物
     * <b>不在</b> {@code ItemHandler} 里，而在数据组件 {@code minecraft:bundle_contents}
     * （1.21.1 的 {@link BundleContents}）里。
     *
     * <h2>为什么需要这一层（第 95 轮用户第 9 条）</h2>
     * <p>收纳袋没有 {@code Capabilities.ItemHandler.ITEM}，所以 {@code innerItem()} 原来对它是
     * {@code null} ⇒ 代理器把它当成「没有内容物的物品」，既读不到、也取不走。本视图把
     * {@code BundleContents} 包成 {@link IItemHandler}：<b>读</b>（{@code getSlots} /
     * {@code getStackInSlot}）、<b>写</b>（{@code insertItem}，走原版
     * {@link BundleContents.Mutable#tryInsert} 的重量容量规则）、<b>取</b>（{@code extractItem}）
     * 三条路径都真正落到数据组件上，因此「终端里看到的 = 袋子里真有的」。</p>
     *
     * <h2>槽位语义：不是固定槽，而是「一种物品一条」</h2>
     * <p>{@code BundleContents} 的 {@code items} 是按物品类型聚合的列表（原版
     * {@code findStackIndex} 会把同物品同组件合并成一条），没有「第 N 号槽」这个概念。
     * 本视图把列表下标当作槽号：{@code getSlots() == max(1, contents.size())}。</p>
     * <p><b>空袋为什么也要给 1 个虚位</b>：{@code IItemHandler} 的所有插入方
     * （NeoForge {@code ItemHandlerHelper.insertItem}、RS 的 {@code ItemHandlerInsertableStorage}）
     * 都只遍历 {@code [0, getSlots())}。若空袋返回 0 槽，网络就<b>永远插不进第一个物品</b> ——
     * 这正是「收纳袋放得进、但里面东西永远取不进也拿不出」的另一半。虚位不可提取
     * （{@code getStackInSlot(0)} 为空），只用来给插入方一个落点。</p>
     *
     * <h2>守恒（本轮硬约束）</h2>
     * <ul>
     *     <li>写回永远是「读出现有 {@code BundleContents} → 在副本上改 → 整份组件重设」，
     *     旧 stack 只被读，从不原地 {@code shrink} / {@code remove}；</li>
     *     <li>插入只返回「没插进去的部分」，绝不截断、绝不复制（{@code tryInsert} 的返回值就是
     *     真正收下的数量，本视图据此计算 remainder）；</li>
     *     <li>取出时「拿走多少、袋里剩多少」由同一份列表同时决定，模拟（{@code simulate}）
     *     只读不写。</li>
     * </ul>
     *
     * <h2>收纳袋能不能套收纳袋</h2>
     * <p><b>能</b>，而且不是本视图放行的：1.21.1 的 {@code BundleItem} 没有覆写
     * {@code canFitInsideContainerItems()}（默认 {@code true}，只有潜影盒之类才返回 {@code false}），
     * 且 {@code BundleContents} 专门有 {@code BUNDLE_IN_BUNDLE_WEIGHT} 这一档重量。
     * 本视图只用 {@link ItemStack#canFitInsideContainerItems()} 尊重原版规则，不自己加禁令；
     * 所以「把收纳袋放进收纳袋」是否允许完全由原版组件决定 —— 目前是允许的。</p>
     */
    public static final class BundleContentsView implements IItemHandler {
        /** 空收纳袋的虚位数量（理由见类注释「空袋为什么也要给 1 个虚位」）。 */
        private static final int MIN_SLOTS = 1;

        private final InventoryProxyBlockEntity proxy;

        public BundleContentsView(final InventoryProxyBlockEntity proxy) {
            this.proxy = proxy;
        }

        /**
         * 当前代理对象的内容物；代理对象不是收纳袋时返回 {@code null}（本视图不适用）。
         * <p>用 {@code getOrDefault}：1.21.1 里空收纳袋不一定带这个组件（默认值没有注册在
         * {@code DataComponentType} 上）。「没有组件」= 空内容物，不是「没有内容物」。</p>
         */
        @Nullable
        private BundleContents contents() {
            final ItemStack stack = proxy.proxyStack();
            if (stack.isEmpty() || !(stack.getItem() instanceof BundleItem)) {
                return null;
            }
            return stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        }

        /** 把组件里的内容物拷成可变 list（组件本身不可变；写回时要整份重设）。 */
        private static List<ItemStack> copyItems(final BundleContents contents) {
            final List<ItemStack> items = new ArrayList<>();
            for (final ItemStack stack : contents.itemsCopy()) {
                items.add(stack);
            }
            return items;
        }

        /**
         * 写回：整份替换代理槽里收纳袋 stack 的 {@code bundle_contents} 组件。
         * <p>先 {@code copy()} 再改组件再交给 {@link InventoryProxyBlockEntity#setProxyStack}
         * （它自己再存 {@code copyWithCount(1)}）。任何一步失败都不会把旧内容物写没。</p>
         */
        private void write(final BundleContents contents) {
            final ItemStack stack = proxy.proxyStack();
            if (stack.isEmpty()) {
                return;
            }
            final ItemStack updated = stack.copy();
            updated.set(DataComponents.BUNDLE_CONTENTS, contents);
            proxy.setProxyStack(updated);
        }

        @Override
        public int getSlots() {
            final BundleContents contents = contents();
            return contents == null ? 0 : Math.max(MIN_SLOTS, contents.size());
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            final BundleContents contents = contents();
            if (contents == null || slot < 0 || slot >= contents.size()) {
                return ItemStack.EMPTY; // 虚位 / 越界：空，不抛异常
            }
            // 返回副本：IItemHandler 的调用方（RS / 管道）只读，绝不能让它们原地改到袋子里的 stack。
            return contents.getItemUnsafe(slot).copy();
        }

        /**
         * 把一个物品插进收纳袋。
         *
         * <p><b>槽号语义（第 95 轮交付前复核指出的已知限制）</b>：原版
         * {@code BundleContents.Mutable#tryInsert} 是「往袋子最前面塞」（{@code items.add(0, …)}），
         * 所以 {@code slot} 参数只用于**越界校验**，不决定落点；一次插入之后，后面所有元素的
         * 下标都会整体后移。这不影响本模组的单次读写（每次操作都从当前 {@code contents()} 重新取），
         * 但「先读某槽、隔一次插入再按旧下标取」的第三方调用方会取到错的物品。
         * 收纳袋本身是无序堆叠（界面上没有稳定槽位概念），所以这里按原版语义实现，
         * 并把限制写清楚，而不是假装槽号稳定。</p>
         */
        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            final BundleContents contents = contents();
            if (contents == null || stack.isEmpty() || slot < 0 || slot >= getSlots()) {
                return stack; // 越界 / 不是收纳袋：原样退回（IItemHandler 契约）
            }
            if (!stack.canFitInsideContainerItems()) {
                return stack; // 原版规则（潜影盒之类不许进容器物品）
            }
            final BundleContents.Mutable mutable = new BundleContents.Mutable(contents);
            // tryInsert 会原地 shrink / split 传入的 stack ⇒ 必须传副本，否则调用方的 stack 被偷走。
            final int inserted = mutable.tryInsert(stack.copy());
            if (inserted <= 0) {
                return stack; // 重量已满 / 该物品不可放入
            }
            if (!simulate) {
                write(mutable.toImmutable());
            }
            return inserted >= stack.getCount()
                ? ItemStack.EMPTY
                : stack.copyWithCount(stack.getCount() - inserted);
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            final BundleContents contents = contents();
            if (contents == null || amount <= 0 || slot < 0 || slot >= contents.size()) {
                return ItemStack.EMPTY; // 虚位 / 越界 / 空袋：抽不到就是空，不抛异常
            }
            final List<ItemStack> items = copyItems(contents);
            final ItemStack inSlot = items.get(slot);
            if (inSlot.isEmpty()) {
                return ItemStack.EMPTY;
            }
            final int take = Math.min(amount, inSlot.getCount());
            final ItemStack extracted = inSlot.copyWithCount(take);
            if (!simulate) {
                if (take >= inSlot.getCount()) {
                    items.remove(slot);
                } else {
                    // 部分取出：剩下的原样留在同一条目里（不改变「一种物品一条」的布局）。
                    items.set(slot, inSlot.copyWithCount(inSlot.getCount() - take));
                }
                write(new BundleContents(items));
            }
            return extracted;
        }

        @Override
        public int getSlotLimit(final int slot) {
            if (contents() == null || slot < 0 || slot >= getSlots()) {
                return 0;
            }
            final ItemStack inSlot = getStackInSlot(slot);
            return inSlot.isEmpty() ? 64 : inSlot.getMaxStackSize();
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            final BundleContents contents = contents();
            if (contents == null || stack.isEmpty() || slot < 0 || slot >= getSlots()) {
                return false;
            }
            if (!stack.canFitInsideContainerItems()) {
                return false;
            }
            // 用「模拟插入」判容量：重量满了就 false，与真实插入同一套原版规则。
            return new BundleContents.Mutable(contents).tryInsert(stack.copy()) > 0;
        }
    }
}
