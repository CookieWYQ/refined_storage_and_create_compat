package cretae.cookiewyq.rs_create_compat.mixin.block;

import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「伪装之后，除战利品表外<b>所有属性继承填充方块</b>」的接管点（用户第 3 条）。
 *
 * <h2>为什么挂在 {@link BlockBehaviour} 上（而不是逐一给线缆 / 管道 / 传动杆写）</h2>
 * <p>被裹住的那一格放的仍然是<b>原来那根线缆 / 管道 / 传动杆</b>（见 {@link RsccCamouflage}
 * 的类注释），它的方块状态、方块实体、能力一个字节都没变 —— 所以「属性继承」只能发生在
 * <b>每次查询属性的那一瞬间</b>。所有方块的这两类查询最终都要经过 {@link BlockBehaviour}：</p>
 * <ol>
 *     <li><b>挖掘进度</b>：{@code BlockStateBase#getDestroyProgress(Player, BlockGetter, BlockPos)}
 *     → {@code getBlock().getDestroyProgress(state, player, level, pos)}（就是下面注入的这一条，
 *     也就是 NeoForge 的位置敏感重载）；</li>
 *     <li><b>抗爆</b>：{@code BlockState#getExplosionResistance(level, pos, explosion)}
 *     的默认实现 → {@code getBlock().getExplosionResistance(state, level, pos, explosion)}。
 *     该方法在 NeoForge 里是 {@code IBlockExtension} 的<b>接口默认方法</b>，而 {@code IBlockExtension}
 *     由 {@code Block} 实现 —— {@link BlockBehaviour} 自己既没声明也没实现它，
 *     因此<b>本类提供的那个方法对 Mixin 来说是「新增方法」</b>（不是覆盖目标类已有方法，
 *     不存在覆盖冲突），合并进 {@code BlockBehaviour} 之后：所有方块都从父类继承了它，
 *     按 Java 规则「类的方法优先于接口默认方法」，于是它<b>稳定地接管</b>了那次查询。</li>
 * </ol>
 *
 * <h2>为什么不用「接口 Mixin」</h2>
 * <p>直接 {@code @Mixin(IBlockExtension.class)} 会踩 Mixin 的硬限制：
 * {@code InvalidMixinException: @Mixin target type mismatch: ... is an interface}
 * （目标类必须是接口时，Mixin 类本身也得写成接口，而接口 Mixin 对注入器的支持另有约束）。
 * 挂 {@code BlockBehaviour} 既避开了这条限制，又天然做到「所有方块只挂一处」。</p>
 *
 * <h2>回退值为什么写 {@code state.getBlock().getExplosionResistance()}</h2>
 * <p>那就是「没有伪装时这个方块自己的抗爆值」的<b>同一个来源</b>
 * （{@code IBlockExtension} 的默认实现正是 {@code self().getExplosionResistance()}）。
 * 这样写而不是调用 {@code super}：{@code BlockBehaviour} 自身没有这个方法，
 * 而 {@code Block} 有 —— {@code super} 在 Mixin 里会指向一个不存在的方法体；
 * 从方块状态取回方块再读一次，语义逐字等价且没有任何歧义。</p>
 *
 * <h2>继承的判定与重入保护都在 {@link RsccCamouflage#materialProperty}</h2>
 * <p>本类只负责「把这次查询交给材质」（两处各三行），所有口径 —— 廉价短路、双端一致的材质来源、
 * 重入旗标 —— 都收敛在那一个方法里。被裹的方块即使与材质指向同一格，也不会递归：
 * 材质自己的属性查询再次进来时会被旗标挡下（见 {@code RsccCamouflage.RESOLVING_MATERIAL}）。</p>
 *
 * <h2>刻意<b>不</b>继承的东西：战利品表</h2>
 * <p>用户明确要求「不继承战利品表」：世界里那一格仍然是原部件，掉落由<b>原部件自己的战利品表</b>
 * 给出（填充钻石块后挖掉不会掉出钻石块）；被消耗掉的那 1 个填充方块按记录原样归还，
 * 一进一出严格相抵（见 {@code RsccCamouflage#onBlockChanged}）。本类不碰任何掉落逻辑。</p>
 */
@Mixin(BlockBehaviour.class)
public abstract class CamouflagePropertyMixin {

    /**
     * <b>抗爆继承</b>：填充黑曜石后这一格就具备黑曜石的抗爆能力（用户第 3 条的例子）。
     *
     * <p>它是「新增」到 {@link BlockBehaviour} 的方法，签名与 NeoForge {@code IBlockExtension}
     * 的默认方法逐字一致 —— 合并后所有方块都会用它替代那份默认实现。
     * 没有伪装（或还没填充方块）时返回方块自己原本的抗爆值，行为与改动前完全相同。</p>
     */
    public float getExplosionResistance(final BlockState state, final BlockGetter level, final BlockPos pos,
                                        final Explosion explosion) {
        final Float inherited = RsccCamouflage.materialProperty(level, pos, state,
            material -> material.getExplosionResistance(level, pos, explosion));
        return inherited != null ? inherited : state.getBlock().getExplosionResistance();
    }

    /**
     * <b>挖掘速度 / 硬度继承</b>：填充石头的这一格挖起来就像石头（用填充方块自己的
     * {@code getDestroyProgress}，含它的硬度、工具需求与效率加成）。
     *
     * <p>注入的是 NeoForge 的位置敏感重载（原版 {@code BlockBehaviour#getDestroyProgress} 只有
     * 「方块状态」没有坐标，正因为算它需要坐标与玩家，NeoForge 才补了这一个重载）。
     * 找不到填充方块时什么都不做（不 cancel），放行原来的实现。</p>
     */
    @Inject(method = "getDestroyProgress(Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;"
        + "Lnet/minecraft/core/BlockPos;)F", at = @At("HEAD"), cancellable = true)
    private void rscc$materialDestroyProgress(final BlockState state, final Player player, final BlockGetter level,
                                              final BlockPos pos, final CallbackInfoReturnable<Float> cir) {
        final Float inherited = RsccCamouflage.materialProperty(level, pos, state,
            material -> material.getDestroyProgress(player, level, pos));
        if (inherited != null) {
            cir.setReturnValue(inherited);
        }
    }
}
