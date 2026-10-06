package cretae.cookiewyq.rs_create_compat.mixin.create;

import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import cretae.cookiewyq.rs_create_compat.support.RsccSheaths;
import cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「分隔框架」对 <b>Create 流体管道</b>的接管点。
 *
 * <p><b>为什么挂这里</b>：{@code FluidPipeBlock#canConnectTo} 是 Create 判定
 * 「这一侧要不要连」的<b>唯一</b>入口（已逐条核对 Create 6.0.10 源码）：</p>
 * <ul>
 *     <li>管口开放面 blockstate：{@code updateBlockState} 对每个方向调用它，
 *     结果写进 {@code PROPERTY_BY_DIRECTION}（外形与碰撞都读这些属性）；</li>
 *     <li>两侧网络遍历：{@code FluidPropagator.getPipeConnections} 只看
 *     {@code FluidPipeBlock#isOpenAt(state, dir)}，也就是上面那份 blockstate；</li>
 *     <li>外形判定：{@code shouldDrawRim} 同样用它。</li>
 * </ul>
 * <p>因此在这一处返回 false，等于「管口关闭」—— 视觉与逻辑同时断开，不会出现
 * 「看着连着但仍然通液」。</p>
 *
 * <p><b>方向语义</b>：按 {@code updateBlockState}（{@code canConnectTo(world, pos.relative(d), ..., d)}）
 * 与 {@code shouldDrawRim}（{@code canConnectTo(world, offsetPos, ..., direction)}）的调用方式，
 * {@code direction} 由<b>本管道</b>指向 {@code neighbourPos}，故本管道坐标 =
 * {@code neighbourPos.relative(direction.getOpposite())}。</p>
 *
 * <p><b>副作用为零的边界</b>：只在「接缝两侧都是流体管道（{@link SeparationFrameGuard#isFluidPipe}）
 * 且任一侧被套壳、且这一侧不在该套壳格的连接快照里」时才返回 false（判定见 {@link SeparationFrameGuard}）；
 * 其余情况直接放行，与未安装本模组时完全一致。非 {@code Level} 上下文（极少见）一律不介入。</p>
 *
 * <p><b>客户端 / 服务端一致</b>：与 RS 侧一样只读「存档里的套壳记录（含快照）+ 世界方块状态」，
 * 两侧跑同一段判定（客户端读 S2C 同步下来的只读镜像），因此管口开放面在双端一致。</p>
 *
 * <h2>第二个接管点：{@code updateBlockState} 返回前的兜底清理（修「渲染断层」）</h2>
 * <p>只有 {@code canConnectTo} 一处还不够：{@code updateBlockState} 在算完六向后还有一条
 * <b>会绕过 {@code canConnectTo} 直接开面</b>的兜底 —— 「一格都没有连上时，按放置朝向硬开一对管口」
 * （Create 想让孤管看起来像一根直管）。于是「刚刚放在被套壳管道旁边的那一节」会带着一条
 * 朝向被套壳管道、实际上并不连接的接缝臂，而被套壳那节不理它 → 用户看到的就是
 * 「旁边那节想连过来、这里却断开」的渲染断层。</p>
 * <p>因此在 {@code updateBlockState} 的每个 {@code return} 之前再过一遍：
 * <b>凡是这次要开的面里，被套壳快照判为不通的，一律改回关闭</b>。
 * 判据仍是同一句 {@link SeparationFrameGuard#blocksFluidPipeConnection}（不另立口径），
 * 并且把「正在计算的状态」当自己传进去（此刻这一格可能还没进世界，
 * 见 {@code blocksFluidPipeConnection(BlockState, Level, BlockPos, BlockPos)} 的注释）——
 * 所以「放置瞬间」也算得对，不只是运行中才对。</p>
 *
 * <h2>2026-09-25 补：这一步<b>只对「没被套壳」的那一格</b>执行（修「开口丢失」渲染 bug）</h2>
 * <p>用户实测：一条直的流体管道<b>全部套上</b>分隔框架后，再在旁边放一根连不上的管道，
 * 于是「管道只有一边有开口、另一边变成空的，中间出现一段空洞的渲染区域」。</p>
 * <p><b>根因就在这一步</b>：快照记的是「套上那一刻<b>同族连接</b>到哪几侧」，而 Create 的
 * {@code updateBlockState} 末尾还有一条「只连上一处时补对面」的兜底（让直管的两端都是开口）。
 * 一条直管的<b>末端</b>那一面因此是「朝空气的死端开口」，快照里当然没有它（对面是空气，不同族）；
 * 一旦在那一面外侧新放一根管道，上面这段清理就把这个<b>本来就存在的开口</b>当成「不通接缝」关掉了 ——
 * 被套壳那一格自己的管口凭空少了一面，模型上的那一段接缝臂随之消失，两节管芯之间就出现空洞。</p>
 * <p>因此本步<b>跳过「自己就是套壳格」的那一格</b>：被套住的那一格外观必须与套上之前逐面一致
 * （Create 算出来是什么就是什么）——这就是用户第 1 条的「外壳不得改变被裹方块的连接渲染与开口」。
 * 而<b>新放的那一格</b>（未套壳、紧贴套壳格）仍然要清：它朝被套壳管道那一面是「想连却连不上」的
 * 假接缝臂，清理掉之后「逻辑不连 = 外形不连」，两侧不再出现单边的接缝臂。</p>
 * <p>逻辑连接由 {@code canConnectTo} 那一处接管点保证（快照说了算），与被套壳格自己的管口外形无关：
 * 新管道朝套壳格的面仍然关闭（<b>双边必须都开才通液</b>，见 {@code FluidPropagator}），
 * 因此「开口照旧、但接缝不通」不会变成「看着连着却不通」。</p>
 */
@Mixin(FluidPipeBlock.class)
public abstract class FluidPipeBlockMixin {
    /** 被套壳快照判为不通的流体管道口，一律不接受连接（管口开放面 + 网络遍历 + 外形同步关闭）。 */
    @Inject(method = "canConnectTo", at = @At("HEAD"), cancellable = true)
    private static void rscc$denyFramedPipeConnection(final BlockAndTintGetter world,
                                                      final BlockPos neighbourPos,
                                                      final BlockState neighbour,
                                                      final Direction direction,
                                                      final CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof final Level level)) {
            return;
        }
        final BlockPos selfPipePos = neighbourPos.relative(direction.getOpposite());
        if (SeparationFrameGuard.blocksFluidPipeConnection(level, selfPipePos, neighbourPos)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code updateBlockState} 的<b>每个 return 之前</b>兜底清理：
     * 「这次算出来要开的管口面」里，凡是被套壳快照判为不通的，一律改回关闭。
     *
     * <p>为什么挂 {@code RETURN}：该方法有多条 {@code return}（逐向算完之后依次是
     * 「有多个连接 → 直接返回」「只有一个连接 → 补对面」「之前就是一对直管 → 原样返回」
     * 「孤管兜底 → 硬开一对」），Create 自己的语义不适合被某一条分支改写；
     * 统一在返回前清理，等价于给「最终状态」加一条<b>与渲染同源</b>的不变量。</p>
     *
     * <p><b>为什么用带状态的重载</b>：放置瞬间这一格还没进世界（{@code level} 里还是空气），
     * 用世界里的方块状态取「自己」会判成「不是管道」而放行；把正在计算的 {@code state} 传进去，
     * 判定才与真正落地的状态一致（见 {@link SeparationFrameGuard#blocksFluidPipeConnection}
     * 的重载注释）。</p>
     *
     * <p><b>幂等 / 无循环</b>：只读套壳记录与方块状态，不写任何东西；清理后的状态再算一次结论相同，
     * 因此不会与邻块更新互相激发。状态没被改动时不做任何事（原样返回 Create 的结果）。</p>
     *
     * <p><b>为什么先排除「自己就是套壳格」</b>：被套住的那一格管口外形必须与套上之前完全一致
     * （Create 算出来的结果原样保留，包括「只连一处时补对面」的兜底开口）。少了这道排除，
     * 套壳格末端那个朝空气的死端开口会在一根新管道贴上来时被当成「不通接缝」关掉 ——
     * 那正是用户报的「只有一边有开口、另一边变成空的」。完整论证见类注释。</p>
     */
    @Inject(method = "updateBlockState", at = @At("RETURN"), cancellable = true)
    private void rscc$closeFramedFaces(final BlockState state,
                                       final Direction preferredDirection,
                                       final Direction ignore,
                                       final BlockAndTintGetter world,
                                       final BlockPos pos,
                                       final CallbackInfoReturnable<BlockState> cir) {
        if (!(world instanceof final Level level)) {
            return;
        }
        if (RsccSheaths.isSheathed(level, pos)) {
            return; // 被套住的那一格：外观完全交给 Create（本功能绝不改写它自己的管口面）
        }
        final BlockState computed = cir.getReturnValue();
        BlockState fixed = computed;
        for (final Direction direction : Direction.values()) {
            if (!Boolean.TRUE.equals(fixed.getValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(direction)))) {
                continue; // 这一面本来就没开：Create 自己认为不该连，无需处理
            }
            if (SeparationFrameGuard.blocksFluidPipeConnection(fixed, level, pos, pos.relative(direction))) {
                fixed = fixed.setValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(direction), false);
            }
        }
        if (fixed != computed) {
            cir.setReturnValue(fixed);
        }
    }
}
