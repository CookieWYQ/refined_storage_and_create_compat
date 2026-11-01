package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.item.FEEnergyCubeItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * FE 能量立方的<b>物品级 NeoForge FE 能力</b>（{@code Capabilities.EnergyStorage.ITEM}）。
 *
 * <h2>数据来源：和方块同一份 NBT，不另开一套</h2>
 * <p>电量存在方块物品自带的 {@code DataComponents.BLOCK_ENTITY_DATA} 里的
 * {@code FEEnergyCubeBlockEntity.TAG_ENERGY} 键上（读写全部委托
 * {@link FEEnergyCubeItem#getEnergy} / {@link FEEnergyCubeItem#setEnergy}）：
 * <ul>
 *     <li>方块被挖下来时 {@code BlockEntity#saveToItem} 就写这个键；</li>
 *     <li>物品被放下时原版 {@code BlockItem#updateCustomBlockEntityTag → loadAdditional} 读这个键；</li>
 *     <li>本类在手上充 / 放，改的也是这个键。</li>
 * </ul>
 * 所以「手上充进去的电，放下方块后就是方块的电」；<b>不存在</b>第二份物品电量，
 * 也就不存在「充了半天放下就丢」。</p>
 *
 * <h2>为什么每个 {@code getCapability} 调用都 new 一个</h2>
 * <p>与飞行背包 {@link JetpackEnergyStorage}、RS 原版 {@code EnergyStorageAdapter} 同一做法：
 * 能力实例只是「一个绑定到某份 {@link ItemStack} 的视图」，读写每次都落到<b>当前那份存活栈</b>上；
 * 缓存实例会在物品被复制 / 换槽 / 拆堆时指向旧栈，写出「明明充了电却没了」的幽灵数据。</p>
 *
 * <h2>堆叠守卫：一份组件 = 一整堆的电量，所以多块一堆时一律拒绝充放</h2>
 * <p>这是本类唯一的「门控」，而且它与电源无关、纯粹是<b>数据正确性</b>：
 * 电量存在 {@code BLOCK_ENTITY_DATA} 组件上，而组件是<b>整堆共享一份</b>的。
 * 一旦允许一堆（count &gt; 1）充放电，立刻出现两条复制路径：
 * <ul>
 *     <li><b>拆堆</b>：{@code ItemStack#split} 用 {@code copyWithCount} 复制组件 ⇒ 一堆 64 块带 100,000 FE
 *     拆成两堆就变成 2 × 100,000 FE（凭空多一份）；</li>
 *     <li><b>放置</b>：{@code BlockItem#updateCustomBlockEntityTag} 读的是<b>整堆</b>那份组件，
 *     放一块之后 {@code shrink(1)} 并不清组件 ⇒ 一堆 64 块带 100,000 FE 能放下 64 个各 100,000 FE 的方块，
 *     等于把 100,000 FE 复制 64 份。</li>
 * </ul>
 * <p>因此 {@link #canReceive()} / {@link #canExtract()} 在 {@code count > 1} 时返回 {@code false}
 * （让充电器 / 其它模组的机器<b>根本不来问</b>），两个读写口也各自再挡一次
 * （不依赖调用方先查 {@code canReceive}）。玩家把堆拆成单块就能正常充放 ——
 * 方块物品本身照旧可以 64 个一堆地携带 / 摆放，<b>没有</b>为了这个守卫去改堆叠上限
 * （那是既有玩法，不在本轮范围内）。</p>
 *
 * <h2>门控：物品形态<b>没有</b>电源相关的门控（刻意如此）</h2>
 * <p>方块形态有三条门控，物品形态全部不适用，因此这里也不做任何仿制：
 * <ul>
 *     <li><b>集群 / 相邻共享</b>：物品旁边没有方块邻居，只有「它自己这一块」，容量 = 单块容量；</li>
 *     <li><b>「从 RS 网络抽电」的控制器阈值 / 上涨闸</b>：那是{@code FEEnergyCubeBlockEntity#onNetworkTick}
 *     里「网络能量池 → 立方」这一条路径的限制。物品形态没有网络连接、不参与网络能量池，
 *     它只是「一罐电」，被充 / 被抽都应当无条件成立 —— 与方块形态
 *     「{@code receiveEnergy} / {@code extractEnergy} <b>永不看门控</b>」的结论完全一致；</li>
 *     <li><b>充满 / 空电量</b>：那是容量夹量（{@code space} / {@code stored}），不是门控。</li>
 * </ul>
 * <p>所以 {@code canReceive()} / {@code canExtract()} 恒为 {@code true}：范围充电器给玩家物品栏里的
 * 能量立方充电、别的模组的机器从它抽电，都是正常且允许的。</p>
 *
 * <h2>线程</h2>
 * <p>能力只在服务端逻辑里被调用（充电器扫描、别的模组的物品充能）；客户端<b>读</b>电量走的是
 * {@link FEEnergyCubeItem#getEnergy} 直读组件（tooltip / 物品栏渲染），不会通过本类去改物品
 * （客户端改物品组件本身不可信）。每次读写只有几次 NBT 读，没有额外分配，不构成性能问题。</p>
 */
public final class EnergyCubeItemEnergy implements IEnergyStorage {
    /** 这份视图绑定的存活栈（唯一数据来源，绝不另存一份电量）。 */
    private final ItemStack stack;

    public EnergyCubeItemEnergy(final ItemStack stack) {
        this.stack = stack;
    }

    /**
     * 接受充能：同时夹在「物品容量」与 {@link FEEnergyCubeItem#transfer()} 之下。
     * <p>{@code simulate = true} 时一个字节都不写（NeoForge 的充电器 / 其它模组会先模拟再执行）。
     */
    @Override
    public int receiveEnergy(final int toReceive, final boolean simulate) {
        if (toReceive <= 0 || stack.isEmpty() || isStacked()) {
            // isStacked()：组件是整堆共享一份，多块一堆时写电量 = 拆堆 / 放置复制（见类注释「堆叠守卫」）。
            return 0;
        }
        final int capacity = FEEnergyCubeItem.capacity();
        final int stored = FEEnergyCubeItem.getEnergy(stack);
        final int space = Math.max(0, capacity - stored);
        // 速率上限来自配置（默认 100,000 FE/tick）：容量 5000 万，即便一次充满也不会溢出 int。
        final int budget = Math.min(toReceive, FEEnergyCubeItem.transfer());
        final int accepted = Math.min(budget, space);
        if (accepted > 0 && !simulate) {
            FEEnergyCubeItem.setEnergy(stack, stored + accepted);
        }
        return Math.max(0, accepted);
    }

    /** 抽取电能：同样夹到配置速率；抽空后存量停在 0（绝不出现负电量）。 */
    @Override
    public int extractEnergy(final int toExtract, final boolean simulate) {
        if (toExtract <= 0 || stack.isEmpty() || isStacked()) {
            // 抽取同样挡堆叠：一堆里的电量就是整堆那一份，抽出来再拆堆同样是复制。
            return 0;
        }
        final int stored = FEEnergyCubeItem.getEnergy(stack);
        final int budget = Math.min(toExtract, FEEnergyCubeItem.transfer());
        final int extracted = Math.min(budget, stored);
        if (extracted > 0 && !simulate) {
            FEEnergyCubeItem.setEnergy(stack, stored - extracted);
        }
        return Math.max(0, extracted);
    }

    @Override
    public int getEnergyStored() {
        return stack.isEmpty() ? 0 : FEEnergyCubeItem.getEnergy(stack);
    }

    @Override
    public int getMaxEnergyStored() {
        return FEEnergyCubeItem.capacity();
    }

    /**
     * 多块堆在一起时拒绝充放（原因见类注释「堆叠守卫」：组件整堆共享一份，允许就是复制漏洞）。
     * <p>单块（{@code count == 1}）恒允许 —— 物品只是一罐电，与方块形态「被抽 / 被注永不看门控」同一口径。</p>
     */
    @Override
    public boolean canExtract() {
        return !isStacked();
    }

    /** 允许被注入；多块一堆时同样拒绝（同上）。 */
    @Override
    public boolean canReceive() {
        return !isStacked();
    }

    /** 这一份视图指向的是不是「多块堆叠」（只有 count == 1 的电量才有意义）。 */
    private boolean isStacked() {
        return stack.getCount() > 1;
    }
}
