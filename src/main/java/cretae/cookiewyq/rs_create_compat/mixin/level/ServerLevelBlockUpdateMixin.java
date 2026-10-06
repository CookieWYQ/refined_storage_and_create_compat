package cretae.cookiewyq.rs_create_compat.mixin.level;

import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「方块状态被广播出去」之后，<b>把这一格裹着的伪装补一次给客户端</b>
 * —— 修「物理化后拆解回来时外壳不恢复 / 不刷新」的那一段。
 *
 * <h2>为什么需要这一刀</h2>
 * <p>材质到达客户端只有两条路：① 我们主动 {@code setData}（换壳 / 取壳，已覆盖）；
 * ② {@code ChunkWatchEvent.Sent}（区块首次发给玩家）。而「方块实体被<b>重新落地</b>」这条路径
 * 两条都不占：Sable 的子关卡拆解走的是
 * {@code SubLevelAssemblyHelper#moveBlocks}（{@code LevelChunk#setBlockState} +
 * {@code BlockEntity#loadWithComponents}），既不经过我们、也不经过原版 {@code ChunkMap}。
 * 拆解那一刻，原坐标的方块实体其实在物理化时就被移除过，客户端手上只有一个
 * <b>刚建出来的空方块实体</b> —— 附件没了，外壳自然回不来。</p>
 *
 * <h2>为什么挂在 {@link ServerLevel#sendBlockUpdated} 上（时机是关键）</h2>
 * <p>拆解的最后一步 {@code SubLevelAssemblyHelper#markAndNotifyBlock} 会调用
 * {@code Level#sendBlockUpdated} 广播方块状态（实测字节码：
 * {@code invokevirtual net/minecraft/world/level/Level.sendBlockUpdated}）。
 * 挂在这里补发附件有两个正好对上的性质：</p>
 * <ol>
 *     <li><b>顺序正确</b>：方块更新包先发出去，客户端这时才第一次认识这一格、
 *     才会为它建出方块实体；我们的附件包随后到达，客户端一定找得到目标
 *     （反过来先发附件会被客户端丢弃 —— 1.21.1 的附件包处理只在方块实体存在时才落值）；</li>
 *     <li><b>覆盖一切「重新落地」的路径</b>：任何模组只要用原版方块更新把一个带附件的方块
 *     放回世界，就自动被补上，不需要逐个模组接钩子。</li>
 * </ol>
 *
 * <h2>为什么用 {@code @At("RETURN")} 而不是 {@code TAIL}</h2>
 * <p>{@code sendBlockUpdated} 里有提前 return（附近没有玩家时直接返回），
 * 而 Mixin 的 {@code TAIL} 只认字节码里<b>最后</b>那一条 RETURN，落点可能是那一条提前返回；
 * {@code RETURN} 会匹配方法内<b>所有</b> RETURN，因此无论走哪条分支都会被补上
 * （这正是我们要的语义：只看「这一格有没有伪装」，与附近有没有玩家无关）。</p>
 *
 * <h2>开销</h2>
 * <p>第一句话就是既有的「廉价方块族判定」（纯 {@code instanceof}，不查世界、不查方块实体）：
 * 世界里的绝大多数方块更新（机器、容器、作物……）在这里就返回；
 * 只有线缆 / 管道 / 传动杆的更新才会去读一次方块实体附件，没有材质则什么都不做。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockUpdateMixin {

    /**
     * 方块状态广播之后：这一格是外壳族且真的裹着时，重推材质 + 请客户端重烘。
     *
     * <p>新旧状态<b>各判一次</b>：拆解落地时「旧状态 = 空气、新状态 = 线缆」，
     * 而某些路径也可能是「旧状态 = 线缆、新状态 = 同类」（旋转 / 连接变化），
     * 两次判定合起来才覆盖全，代价只是两次 {@code instanceof}。</p>
     */
    @Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/world/level/block/state/BlockState;I)V", at = @At("RETURN"))
    private void rscc$repushCamouflage(final BlockPos pos, final BlockState oldState, final BlockState newState,
                                       final int flags, final CallbackInfo callback) {
        if (!SeparationFrameGuard.isSheatheableFamily(newState)
            && !SeparationFrameGuard.isSheatheableFamily(oldState)) {
            return;
        }
        RsccCamouflage.repushIfCamouflaged((ServerLevel) (Object) this, pos);
    }
}
