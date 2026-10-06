package cretae.cookiewyq.rs_create_compat.mixin.network;

import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccClusterable;
import cretae.cookiewyq.rs_create_compat.support.RsccExporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterExecutorMode;
import cretae.cookiewyq.rs_create_compat.support.RsccMachineCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「网络节点容器方块实体」逐 tick 入口的统一钩子。
 *
 * <p><b>为什么需要它</b>：输出总线的「延长模式取料策略」不能在 {@code initialize}（方块实体初始化阶段）
 * 里安装 —— 那个阶段探测邻块会强制初始化邻块并形成 A→B→A 无限递归（StackOverflowError）。
 * 需要一个<b>安全的、在方块实体完全就绪之后才运行</b>的时机来做懒安装。RS 的方块实体 tick 由
 * {@code AbstractBaseNetworkNodeContainerBlockEntity#doWork()} 驱动，而该方法<b>只声明在它自己身上</b>
 * （{@code AbstractExporterBlockEntity} 没有覆写），因此直接在基类上注入，再按类型转交给输出总线。</p>
 *
 * <p><b>代价</b>：每个 RS 节点方块实体每 tick 一次 {@code instanceof} 判定；命中输出总线后才调用
 * {@link RsccExporterExecutorMode#rscc$serverTick()}，而后者在策略装好后立即返回，开销可忽略。</p>
 */
@Mixin(AbstractBaseNetworkNodeContainerBlockEntity.class)
public abstract class AbstractBaseNetworkNodeContainerBlockEntityMixin {
    /** 每个节点方块实体 tick 开始时，若是输出 / 输入总线则驱动其「懒安装」；若是可成集群的机器则解析集群。 */
    @Inject(method = "doWork", at = @At("HEAD"))
    private void rscc$tickExporterExecutorMode(final CallbackInfo ci) {
        if (this instanceof RsccExporterExecutorMode exporter) {
            exporter.rscc$serverTick();
        }
        // 延长型输入（对称）：策略装好后立即返回，未绑定时走 20 tick 缓存的廉价重试
        if (this instanceof RsccImporterExecutorMode importer) {
            importer.rscc$serverTick();
        }
        // 机器集群：缓存命中时只花一次 Map 查询（重算由「邻块变化 / 加载 / 卸载」的事件驱动失效触发）
        if (this instanceof RsccClusterable member) {
            RsccMachineCluster.ensure(member);
        }
    }
}
