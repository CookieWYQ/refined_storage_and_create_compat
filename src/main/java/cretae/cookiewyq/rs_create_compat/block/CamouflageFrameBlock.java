package cretae.cookiewyq.rs_create_compat.block;

import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 伪装框架的<b>方块形态</b>：它现在<b>不再由物品放置</b>（见下），只保留三个用途 ——
 * 物品模型 / 物品图标的外观来源、Create 伪装板「哪个方块能当外壳」这条规则的实现载体、
 * 以及世界渲染时那层外壳的模型来源。
 *
 * <h2>2026-09-25 语义变更：伪装框架改为「裹在那一格外面」，不再是旁边的方块</h2>
 * <p>用户第 3 次反馈「仍然套不上去」，并给了两条线索：「他<b>可以放在</b>这一个管道或线缆<b>上面</b>」
 * 与「那个框架到底该怎么收回？好像没有设计吧」。前者的意思是：上一版把交互交给
 * {@code BlockItem#place}，方块落到了<b>被点线缆旁边那一格空气</b>里（线缆不可被替换，
 * 原版放置目标恒为「命中格 + 命中面」），线缆本身分毫未变 —— 玩家看到的是「在线缆上放了个方块」，
 * 而不是「把线缆裹起来」；后者说明「放上去的那个方块」并不像线缆的一部分，玩家根本不知道怎么取下来。</p>
 * <p>因此本轮把伪装框架做成与<b>分隔框架完全一致</b>的做法（用户原话「伪装框也是一样的」）：
 * 右键线缆 / 管道 → 那一格的方块实体被挂上伪装附件（{@code support/RsccCamouflage}）→
 * 那里仍然是原来那根线缆，只是<b>方块自己的模型</b>会多产出一层「外壳材质的几何」
 * （{@code client/model/CamouflageShellModel}，随方块 / 装置网格一起被绘制）。
 * 本方块于是<b>不再由物品产生</b>，但注册项保留（旧存档 / 指令放出来的仍是同一个方块，不会成为「未知方块」），
 * 且它继承 {@code CopycatBlock} 这件事<b>仍然在用</b>：外壳材质的准入规则直接复用
 * {@link CopycatBlock#getAcceptedBlockState}（{@link cretae.cookiewyq.rs_create_compat.support.RsccCamouflageInteraction}
 * 调用的就是本方块实例上的那一份），所以「伪装板收什么，伪装框架就收什么」这一条一字未改。</p>
 *
 * <h2>为什么继续继承 {@link CopycatBlock}</h2>
 * <p>用户要求「具体规则参考机械动力自带的伪装板、内部逻辑一致」，而伪装板的全部规则都写在
 * {@code CopycatBlock} + {@code CopycatBlockEntity} 里：只接受<b>完整方块</b>
 * （create:copycat_allow / create:copycat_deny 标签 → 方块实体方块 → 楼梯 → 形状与碰撞恰好整格）、
 * 材质与消耗物品一起持久化、被邻块遮住的面剔除、发光材质处理…… 继承之后这些规则只有一份代码，
 * Create 升级自动跟随。本类只做两件 Create 没做的事：① 指定自己的方块实体类型；
 * ② 声明本方块不参与连接纹理。</p>
 *
 * <h2>为什么伪装不影响线缆 / 管道</h2>
 * <p>无论「外壳」还是「被裹住的那一格」，都不接入任何连接判定：本方块不实现 RS 的网络节点容器、
 * 也不实现 Create 的管道 / 应力接口；被裹住的格里放的仍然是原来那根线缆，
 * 它的网络节点与流体一个字节都没变（{@link cretae.cookiewyq.rs_create_compat.support.RsccCamouflage}
 * 只存「这一格被裹住 + 外壳是什么」）。因此相邻的 RS 线缆 / Create 管道看它 = 看一块石头，
 * 连接与网络图分毫不动 —— 与「分隔框架」（刻意冻结连接）语义相反，两者互不干扰。</p>
 */
public class CamouflageFrameBlock extends CopycatBlock {

    /** 材质被拒时的提示（形状不满整格）。 */
    private static final Component HINT_FULL_BLOCK_ONLY =
        Component.translatable("block.rs_create_compat.camouflage_frame.hint.full_only");
    /** 已经装了外壳、又拿了另一种方块来时的提示。 */
    private static final Component HINT_ALREADY_SHEATHED =
        Component.translatable("block.rs_create_compat.camouflage_frame.hint.occupied");

    public CamouflageFrameBlock(final Properties properties) {
        super(properties);
    }

    /**
     * 本方块的方块实体就是 Create 的 {@link CopycatBlockEntity}（原样复用，不另写子类）：
     * 它的任务只有「存材质 + 存被消耗的物品 + 按材质刷新模型与光源」，没有任何需要本模组定制的部分。
     */
    @Override
    public BlockEntityType<? extends CopycatBlockEntity> getBlockEntityType() {
        return RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK_ENTITY.get();
    }

    /**
     * 在 Create 的套壳流程外面补一层「被拒绝」的反馈。
     *
     * <p><b>现在什么时候会走到这里</b>：正常玩法下本方块已经不由物品产生，因此这条路径只服务
     * <b>旧存档里已经放下的伪装框架方块</b>（上一版物品真的会放置它）—— 玩家对着那些方块右键完整方块时，
     * 仍然按 Create 的伪装板流程装上 / 换外壳，规则一字未改。新玩法下（裹住线缆）的外壳判定与提示
     * 走 {@code support/RsccCamouflageInteraction}，与该路径共用 Create 的同一句
     * {@link CopycatBlock#getAcceptedBlockState}。</p>
     *
     * <p>Create 的写法是：材质不被接受时直接 {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}，
     * 结果玩家手上的方块会被贴到相邻面 —— 看起来像「刚才那下没生效」，但分不清是「这里不支持台阶」
     * 还是「我点歪了」。这里只<b>读</b>判定结果，不做任何改动。</p>
     */
    @Override
    protected ItemInteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level,
                                              final BlockPos pos, final Player player, final InteractionHand hand,
                                              final BlockHitResult hitResult) {
        final ItemInteractionResult result = super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        // 只在服务端发提示（displayClientMessage 走服务端下发到该玩家的动作栏），避免双端各说一次。
        if (!level.isClientSide() && result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            notifyRejected(level, pos, player, stack, hitResult);
        }
        return result;
    }

    /**
     * 判断「这次右键为什么没成功」并给出对应提示；只读方块状态与方块实体，绝不改变任何逻辑。
     * <p>不是手持方块物品（空手 / 扳手 / 其他工具）时直接不提示：那不是「想套外壳」的意图。</p>
     */
    private void notifyRejected(final Level level, final BlockPos pos, final Player player, final ItemStack stack,
                                final BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof final BlockItem blockItem)) {
            return;
        }
        if (getAcceptedBlockState(level, pos, stack, hitResult.getDirection()) == null) {
            player.displayClientMessage(HINT_FULL_BLOCK_ONLY, true);
            return;
        }
        // 材质本身合法，走到这里说明「已经有外壳了」：同材质的换朝向失败不算（那只是没有可切换的属性）。
        final BlockState existing = getMaterial(level, pos);
        if (!existing.is(blockItem.getBlock())) {
            player.displayClientMessage(HINT_ALREADY_SHEATHED, true);
        }
    }

    /**
     * 本方块不做「连接纹理」：外观 100% 来自外壳材质自己的模型（见
     * {@code client/model/CamouflageFrameModel}），不存在伪装板那种「把相邻同类方块的纹理接续起来」
     * 的需求，所以直接告诉 Create 的模型层「别把我的外观换成邻块材质」。
     */
    @Override
    public boolean isIgnoredConnectivitySide(final BlockAndTintGetter reader, final BlockState state,
                                             final Direction face, @Nullable final BlockPos fromPos,
                                             @Nullable final BlockPos toPos) {
        return true;
    }

    /** 与 {@link #isIgnoredConnectivitySide} 配套：任何方向都不接续纹理。 */
    @Override
    public boolean canConnectTexturesToward(final BlockAndTintGetter reader, final BlockPos fromPos,
                                            final BlockPos toPos, final BlockState state) {
        return false;
    }

    /**
     * 允许 Create 的模型层剔除被邻块完全盖住的面。
     * <p>伪装板只在「背面」这么做（因为它是几像素厚的薄板），而本方块是整格实心外壳：
     * 被邻块盖住的那一面无论画外壳材质还是画底模都不可见，剔掉纯赚。</p>
     */
    @Override
    public boolean canFaceBeOccluded(final BlockState state, final Direction face) {
        return true;
    }
}
