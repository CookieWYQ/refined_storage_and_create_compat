package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;

import javax.annotation.Nullable;

/**
 * 范围充电器的 RS 网络节点：作为 RS 网络的能量消费者，
 * 每 tick 从网络能量池抽取能量到本地 FE 缓存。
 */
public class RangeChargerNetworkNode extends AbstractNetworkNode {
    @Nullable
    private RangeChargerBlockEntity blockEntity;

    public void setBlockEntity(final RangeChargerBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Nullable
    public com.refinedmods.refinedstorage.api.network.Network getNetworkOrNull() {
        return network;
    }

    @Override
    public long getEnergyUsage() {
        // 网络能量消耗按需从 doWork 抽取，这里不设定固定消耗
        return 0;
    }

    @Override
    public void doWork() {
        if (network == null || !isActive() || blockEntity == null) {
            return;
        }
        final net.neoforged.neoforge.energy.IEnergyStorage storage = blockEntity.getEnergyStorage();
        final long space = (long) storage.getMaxEnergyStored() - storage.getEnergyStored();
        if (space <= 0) {
            return;
        }
        // <b>2026-10-17 修「持续抽干 RS 网络」（纯损耗）</b>：
        // 旧写法是 {@code extract(space)}（space = 容量 − 存量，最大 100 万），抽完再把
        // {@code extracted} 交给 {@code receiveEnergy(..., false)} 并且<b>丢掉它的返回值</b>。
        // 只要接收侧按 maxReceive 截断（旧代码 = Config.rangeChargerMaxTransfer = 5000），
        // 差额就是「已从网络扣掉、却没人收到」的纯损耗（实测可达 99.5 万 FE/tick，每 tick 重复
        // ⇒ 网络被持续抽干）。因此抽取量必须收窄到「本 tick 实际能接收的量」。
        //
        // 先 simulate（不改变任何状态）问清接收侧能吃多少，再按这个量去网络里抽：
        // 抽取请求量本身就是「一定收得下」的量，绝不再按 space 无条件抽满。
        //
        // <b>2026-10-30（第 99 轮）再加一道闸：Config.rangeChargerNetworkPullPerTick</b>。
        // 缓存从 1000 万提到 5000 万后，旧的「缺多少抽多少」口径会把「一个 tick 能抢多少」
        // 从 1000 万放大到 5000 万 —— 空缓存时一次就能把同一张网络里其它机器的电抢光。
        // 抽取速率与缓存容量是两件事：容量决定「能存多少」，本配置决定「一次能抢多少」。
        // 两道夹量同时生效，且仍满足下面的「抽到 == 收到」：canAccept 是 simulate 结果，
        // 请求量 ≤ canAccept，而 canAccept 是接收侧本 tick 真能吃下的量。
        final int budget = (int) Math.min(Math.min(space, Integer.MAX_VALUE),
            (long) Config.rangeChargerNetworkPullPerTick);
        if (budget <= 0) {
            return;
        }
        final int canAccept = storage.receiveEnergy(budget, true);
        if (canAccept <= 0) {
            return;
        }
        // 从 RS 网络能量池抽取并存入本地缓存（RS 能量单位即 FE）
        final long extracted = network.getComponent(EnergyNetworkComponent.class).extract(canAccept);
        if (extracted <= 0) {
            return;
        }
        // 「抽到的 == 收到的」怎么保证（三处夹量，逐条可验证）：
        //   ① EnergyNetworkComponentImpl#extract 的返回恒 ≤ 请求量（逐 provider 累加，累满即停）；
        //   ② 请求量 = 上一行同一实例、同一 tick 的 simulate 结果 canAccept，中间没有任何代码
        //      改动本机缓存（doWork 单线程执行，extract 只遍历 provider、不会回调本节点）；
        //   ③ 接收侧 RestorableEnergyStorage 的 maxReceive = capacity ⇒ receiveEnergy 的夹量只有
        //      「capacity − energy」，而 space 正是按这个式子算出来的、canAccept 又是它的 simulate 值。
        // 于是 receiveEnergy((int) extracted, false) 必然收下 extracted 全部，不留差额。
        storage.receiveEnergy((int) extracted, false);
    }
}
