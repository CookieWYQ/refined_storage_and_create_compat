package cretae.cookiewyq.rs_create_compat.mixin.block;

import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「被裹的方块被拆掉 / 被替换 / 被炸掉」时<b>把框架本体与外壳方块还回世界</b>的挂点。
 *
 * <h2>为什么挂在 {@link BlockBehaviour#onRemove} 上（与 Create 伪装板同一个挂点）</h2>
 * <p>Create 的 {@code CopycatBlock#onRemove} 就在这个位置把材质物品掉回去；本模组跟着用同一个
 * 生命周期钩子，因为它同时满足三件旧实现做不到 / 做错了的事：</p>
 * <ol>
 *     <li><b>覆盖全部破坏方式</b>：玩家挖掉、被放置的方块替换、被爆炸炸掉，最终都会走到
 *     {@code BlockBehaviour#onRemove}（{@code LevelChunk#setBlockState} 在服务端调用它），
 *     因此不需要再挂三个事件（旧实现挂了 {@code BreakEvent} / {@code EntityPlaceEvent} /
 *     {@code ExplosionEvent.Detonate}）；</li>
 *     <li><b>时机正确</b>：它在<b>默认实现移除方块实体之前</b>调用 —— 而伪装材质就存在方块实体的附件里，
 *     因此这里读得到。旧实现用 {@code EntityPlaceEvent} 处理「被替换」，而那一刻方块实体<b>已经没了</b>
 *     （方块实体随方块一起被移除），于是记录读不到、物品就被<b>凭空销毁</b>；</li>
 *     <li><b>区分「搬运」</b>：{@code isMoving} 参数直接告诉我们这次移除是不是搬运引起的
 *     （活塞推方块、Create 装置装配用的是 {@code Block.UPDATE_MOVE_BY_PISTON} 标志）。
 *     搬运时必须<b>什么都不做</b>：材质要留在方块实体的 NBT 里跟着方块走 —— 这正是用户要的「跟随」。</li>
 * </ol>
 *
 * <h2>为什么只挂 {@link BlockBehaviour} 一处</h2>
 * <p>与 {@code CamouflagePropertyMixin} 同一个理由：被裹的方块跨 RS / Create 两个模组、十几个方块类，
 * 逐一 Mixin 等于写十几份重复实现。{@code onRemove} 声明在 {@link BlockBehaviour} 上、
 * {@code Block} 自身没有覆写，因此这里挂一次就覆盖了绝大多数方块。</p>
 *
 * <p><b>兜底</b>：万一某个方块类覆写了 {@code onRemove} 又没有调 {@code super}，
 * 本注入不会执行 —— 因此 {@code RsccCamouflageInteraction} 里的 {@code BreakEvent}（玩家破坏）
 * 挂点<b>刻意保留</b>作为兜底。两条链路都是「读一次附件 → 删一次 → 掉一次」，
 * 谁先执行另一个就读不到了，因此绝不可能出现掉落两份。</p>
 */
@Mixin(BlockBehaviour.class)
public abstract class CamouflageRemovalMixin {

    /**
     * 方块即将被移除：把这一格的伪装记录读出来、删掉，并把框架本体与外壳方块掉回世界。
     *
     * <p>三步廉价短路都放在最前面（方块移除是高频操作，绝大多数方块在第一句就返回）：
     * ①非搬运；②这一格的方块状态<b>本来就没有方块实体</b>（线缆 / 管道 / 传动杆一定有）
     * → 不可能是被裹格；③只在服务端做（客户端不产出物品）。</p>
     */
    @Inject(method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
        at = @At("HEAD"))
    private void rscc$dropCamouflageReturns(final BlockState state, final Level level, final BlockPos pos,
                                            final BlockState newState, final boolean isMoving,
                                            final CallbackInfo callback) {
        if (isMoving || !state.hasBlockEntity() || !(level instanceof final ServerLevel serverLevel)) {
            return;
        }
        RsccCamouflage.onBlockRemoved(serverLevel, pos, false);
    }
}
