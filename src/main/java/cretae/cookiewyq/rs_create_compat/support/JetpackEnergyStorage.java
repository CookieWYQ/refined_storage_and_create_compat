package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 电动飞行背包的 <b>NeoForge FE 能量能力</b>（{@code Capabilities.EnergyStorage.ITEM}）。
 *
 * <h2>电量存在哪里 / 为什么要落盘</h2>
 * <p>电量写在<b>物品自己的 {@code CustomData}</b> 上（键见
 * {@link ElectricJetpackItem#TAG_ENERGY}），随物品一起进存档、进放下 / 拾取 / 死亡掉落，
 * <b>不做任何「服务端另算一份、客户端自己猜」的实现</b>：
 * <ul>
 *     <li>物品栏 tooltip / 耐久样式能量条读的是<b>物品组件本身</b>的真实值（客户端同步过来的那份也是真实值）；</li>
 *     <li>HUD 的能量条读的是服务端每 5 tick 下发的快照（见 {@code network/JetpackStatusPacket}），
 *     同样是真实值，不是客户端推算；</li>
 *     <li>范围充电器充它、别的模组的充电源充它，走的都是本类 —— 因此「有没有真的在充」这件事
 *     服务端是能观测到的（见 {@link JetpackFlightHandler} 的「能量变大」判据）。</li>
 * </ul>
 *
 * <h2>为什么每个 {@code getCapability} 调用都 new 一个</h2>
 * <p>与 RS 原版的 {@code EnergyStorageAdapter} 同一做法：能力实例只是「一个绑定到某份
 * {@link ItemStack} 的视图」，读写每次都落到<b>当前那份存活栈</b>上；缓存实例会在物品被复制 /
 * 换槽 / 拆堆时指向旧栈，写出「明明充了电却没了」的幽灵数据。
 *
 * <h2>线程</h2>
 * <p>能力只在服务端逻辑里被调用（充电器扫描、别的模组的物品充能）；客户端不会拿
 * {@code Capabilities.EnergyStorage.ITEM} 去改物品（客户端改物品组件本身就不可信）。
 * 每次读写只有几次 NBT 读，没有额外分配，不构成性能问题。
 */
public final class JetpackEnergyStorage implements IEnergyStorage {
    /** 这份视图绑定的存活栈（唯一数据来源，绝不另存一份电量）。 */
    private final ItemStack stack;

    public JetpackEnergyStorage(final ItemStack stack) {
        this.stack = stack;
    }

    /**
     * 接受充能：同时夹在「物品容量」与 {@link ElectricJetpackItem#transfer()} 之下。
     * <p>{@code simulate = true} 时一个字节都不写（NeoForge 的充电器 / 其它模组会先模拟再执行）。
     */
    @Override
    public int receiveEnergy(final int toReceive, final boolean simulate) {
        if (toReceive <= 0 || stack.isEmpty()) {
            return 0;
        }
        final int capacity = ElectricJetpackItem.capacity();
        final int stored = ElectricJetpackItem.getEnergy(stack);
        final int space = Math.max(0, capacity - stored);
        // 速率上限来自配置（默认 100,000 FE/tick）：容量 5000 万，即便一次充满也不会溢出 int。
        final int budget = Math.min(toReceive, ElectricJetpackItem.transfer());
        final int accepted = Math.min(budget, space);
        if (accepted > 0 && !simulate) {
            ElectricJetpackItem.setEnergy(stack, stored + accepted);
        }
        return Math.max(0, accepted);
    }

    /** 抽取电能：同样夹到配置速率；飞行耗电<b>不</b>走这里（见 {@link ElectricJetpackItem#consume}）。 */
    @Override
    public int extractEnergy(final int toExtract, final boolean simulate) {
        if (toExtract <= 0 || stack.isEmpty()) {
            return 0;
        }
        final int stored = ElectricJetpackItem.getEnergy(stack);
        final int budget = Math.min(toExtract, ElectricJetpackItem.transfer());
        final int extracted = Math.min(budget, stored);
        if (extracted > 0 && !simulate) {
            ElectricJetpackItem.setEnergy(stack, stored - extracted);
        }
        return Math.max(0, extracted);
    }

    @Override
    public int getEnergyStored() {
        return stack.isEmpty() ? 0 : ElectricJetpackItem.getEnergy(stack);
    }

    @Override
    public int getMaxEnergyStored() {
        return ElectricJetpackItem.capacity();
    }

    @Override
    public boolean canExtract() {
        // 允许被抽取（配置口径是「输入/输出速率」都受 electricJetpackMaxTransfer 限制）。
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
