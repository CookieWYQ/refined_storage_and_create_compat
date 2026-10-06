package cretae.cookiewyq.rs_create_compat.mixin.network;

import com.refinedmods.refinedstorage.common.support.network.InWorldNetworkNodeContainerImpl;
import cretae.cookiewyq.rs_create_compat.support.RsccCableCuts;
import cretae.cookiewyq.rs_create_compat.support.RsccWireBlocks;
import cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「机械动力扳手分离 RS 线缆」的<b>唯一接管点</b>。
 *
 * <p><b>为什么挂这里</b>：RS 2.0 的「连接」有两个消费者，但共用同一个闸门：</p>
 * <ol>
 *     <li><b>网络图连边</b>：{@code ConnectionProviderImpl#getConnections} 对每个候选坐标要求
 *     目标方的 {@code InWorldNetworkNodeContainer#canAcceptIncomingConnection(dir, 来源状态)} 为 true；</li>
 *     <li><b>连接臂外形</b>：{@code AbstractCableLikeBlockEntity#hasVisualConnection} 用<b>同一句</b>
 *     调用决定某方向是否长臂，结果存进 {@code CableConnections} 供 {@code CableBlock#getShape}
 *     与 {@code CableBakedModel} 渲染。</li>
 * </ol>
 * <p>而线缆 / 输入总线 / 输出总线 / 检测器 / 自动合成仓等全部经由
 * {@code InWorldNetworkNodeContainerBuilder} 构造成 {@link InWorldNetworkNodeContainerImpl}，
 * 它<b>自己声明</b>了 {@code canAcceptIncomingConnection}（不是继承来的）——满足本项目
 * 「Mixin 只能定位目标类自身声明的成员」的硬规则。</p>
 *
 * <p>本类同时承载两条「不该连」的判据，二者<b>并列</b>、互不影响：
 * 「机械动力扳手分离 RS 线缆」（{@link RsccCableCuts}，状态存在存档里、随 S2C 快照同步）
 * 与「分隔框架」（{@link SeparationFrameGuard}，读存档里的套壳记录 + 套上那一刻的连接快照）。</p>
 *
 * <p><b>副作用为零的边界</b>：</p>
 * <ul>
 *     <li>非线缆场景直接返回：只有「本条容器所在方块」或「发起连接的那一方」是可穿行集合成员
 *     （RS 线缆 / 输出总线 / 输入总线，见 {@link RsccWireBlocks#isWire}）时才查表，
 *     因此不会影响 RS 内部其它机器的连接；</li>
 *     <li>查表在档位为 {@code off}、或记录为空、或客户端尚未收到快照时立刻返回 false，
 *     与原版行为完全一致；</li>
 *     <li>不写任何字段、不改方块状态、不动方块实体数据 —— 只把返回值改成 false。</li>
 * </ul>
 */
@Mixin(InWorldNetworkNodeContainerImpl.class)
public abstract class InWorldNetworkNodeContainerImplMixin {
    /** 容器所属的方块实体（字段声明在 {@link InWorldNetworkNodeContainerImpl} 自身，可安全 @Shadow）。 */
    @Shadow
    private BlockEntity blockEntity;

    /** 扳手断开过的连接 + 被套壳快照判为不通的连接，一律不接受（网络图不连、外形不长臂）。 */
    @Inject(method = "canAcceptIncomingConnection", at = @At("HEAD"), cancellable = true)
    private void rscc$denyWrenchedConnection(final Direction incomingDirection,
                                             final BlockState connectingState,
                                             final CallbackInfoReturnable<Boolean> cir) {
        final Level level = this.blockEntity.getLevel();
        if (level == null) {
            return;
        }
        if (!RsccWireBlocks.isWire(connectingState) && !RsccWireBlocks.isWire(this.blockEntity.getBlockState())) {
            return;
        }
        if (RsccCableCuts.isDisconnected(level, this.blockEntity.getBlockPos(), incomingDirection)) {
            cir.setReturnValue(false);
            return;
        }
        // 分隔框架：接缝任一侧的线缆被套壳，就由那一格的「套上那一刻的连接快照」说了算 ——
        // 套上前就有的连接照旧连，套上后新放的邻居一律不连（不是「套住即断开」，见 SeparationFrameGuard）。
        // 判定只读存档记录 + 方块状态，服务端与客户端跑同一段代码，结论必然一致。
        if (SeparationFrameGuard.blocksConnection(level, this.blockEntity.getBlockPos(), incomingDirection)) {
            cir.setReturnValue(false);
        }
    }
}
