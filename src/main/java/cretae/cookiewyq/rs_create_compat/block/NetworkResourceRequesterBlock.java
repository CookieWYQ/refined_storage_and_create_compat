package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceRequesterBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.NetworkResourceRequesterMenu;
import cretae.cookiewyq.rs_create_compat.support.BlockContentReleaser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 网络资源请求器方块：「脉冲触发的搬运工」，被<b>两种触发</b>驱动 ——
 * 红石上升沿，或界面里的手动按钮。
 *
 * <h2>形态（用户拍板）</h2>
 * <ul>
 *     <li><b>没有朝向</b>：它不输出到任何相邻容器，只把资源投递到「按名字解析到的接收器」，
 *     因此没有任何面需要玩家指定；</li>
 *     <li>配置（目标名 / 资源 / 数量）存在<b>自己的方块实体 NBT</b> 里；</li>
 *     <li>升级槽有 6 个（只收速度 + 叠加，见设计文档 §1.4 第 2 条）：
 *     速度升级 = 每 tick 几批、叠加升级 = 每批多少，两者都经统一节流工具
 *     {@code support/RsccRateLimiter} 换算（绝不自己写公式、也不用 RS 的 ticker）。</li>
 * </ul>
 *
 * <h2>⚠️ 方块状态必须与 blockstate JSON 逐字一致</h2>
 * <p>{@code assets/rs_create_compat/blockstates/network_resource_requester.json} 的变体键
 * <b>只有</b> {@code active=<true|false>}（2 个变体），<b>没有 facing</b>。
 * 因此本类注册的属性<b>只有</b> {@link ModBlockStateProperties#ACTIVE}。
 * 一旦这里多注册一个 {@code facing}，那两个变体会全部匹配不到 ⇒ 方块渲染成紫黑缺失模型
 * （{@code tools/selfcheck_round67_new_blocks_skeleton.py} 会断言「请求器无 facing」）。</p>
 *
 * <h2>红石：上升沿触发一次真实投递</h2>
 * <p>{@link #neighborChanged} 里<b>照搬 Create 红石请求器</b>的边沿语义
 * （{@code RedstoneRequesterBlock.java:150-157} → {@code RedstoneRequesterBlockEntity.java:77-88}）：
 * 方块只负责把「这一格现在有没有红石信号」通知给方块实体，由方块实体判<b>上升沿</b>
 * （{@code false → true} 才触发一次）。真正的「解析目标 → 取料 → 写入接收器缓存」在
 * {@code NetworkResourceRequesterBlockEntity#triggerRequest()} 里（上升沿与界面按钮同一个入口），
 * 节拍 / 批数全部由升级容器给出。</p>
 *
 * <h2>为什么<b>不</b>用方块状态记录红石</h2>
 * <p>第一反应是照 Create 那样加一个 {@code POWERED} 方块状态。本方块的 blockstate JSON 里
 * <b>只有 active 两个变体</b>（用户已定的资源），而方块状态的每一个合法组合都必须有对应变体，
 * 否则那一组状态会渲染成紫黑缺失模型 —— 加 {@code POWERED} 等于让 2 个变体变成 4 个，
 * 与既有 JSON 冲突。因此红石状态<b>只</b>存在方块实体的 {@code redstonePowered} 字段里
 * （服务端权威；升沿判定本来也不需要双端同步）。后续若要做界面上的红石指示器，
 * 走既有的容器数据同步通道（{@code ContainerData}）而不是方块状态。</p>
 */
public class NetworkResourceRequesterBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<NetworkResourceRequesterBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<>(
            () -> RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_BLOCK_ENTITY.get(),
            ModBlockStateProperties.ACTIVE);

    public NetworkResourceRequesterBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(ModBlockStateProperties.ACTIVE, false));
    }

    /** 注册 {@code active}（网络已接入）。注意：<b>没有</b> facing，也<b>没有</b> powered。 */
    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ModBlockStateProperties.ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new NetworkResourceRequesterBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level,
                                                                  final BlockState state,
                                                                  final BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (BlockEntityTicker<T>) TICKER;
    }

    /**
     * 邻块 / 红石变化：把「这一格现在有没有红石信号」交给方块实体判<b>上升沿</b>。
     * <p><b>为什么客户端直接 return</b>：红石信号的权威判定在服务端；客户端若也跑一遍，
     * 只会得到「本地推测 → 服务端纠正」的抖动（Create 也是这么做的）。</p>
     */
    @Override
    protected void neighborChanged(final BlockState state,
                                   final Level level,
                                   final BlockPos pos,
                                   final Block neighborBlock,
                                   final BlockPos neighborPos,
                                   final boolean movedByPiston) {
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof NetworkResourceRequesterBlockEntity requester) {
            requester.onRedstonePowerChanged(level.hasNeighborSignal(pos));
        }
    }

    /**
     * 右键：<b>直接打开网格界面</b>（第 96 轮用户原话：「反正一打开就是网格界面」）。
     *
     * <h2>主入口 = 网格（{@code ResourcePickerMenu}）</h2>
     * <p>选资源、订单清单（搜索 / 删除 / 改数量）、立即发送、清空订单、切换目标接收器全在网格里，
     * 不再先开一个只有 8 个订单格的小面板再点「网格」。打开动作只能由<b>服务端</b>
     * {@code openMenu} 发出（客户端开哪个界面只看服务端给的 {@code MenuType}），
     * 因此这里只调 {@link NetworkResourceRequesterMenu#openPickerFor}。</p>
     *
     * <h2>打不开网格时一定有一句话（绝不「点了没反应」）</h2>
     * <p>失败有两种，必须分开说：</p>
     * <ol>
     *     <li><b>没接在可用网络上</b>（{@code ResourcePickerMenu#openFor} 的连通性判据）：
     *     actionbar 说明原因，<b>并退回设置界面</b> —— 否则一台刚放下、还没接网络的机器
     *     永远没法命名目标（死锁）；</li>
     *     <li><b>网络没问题但 {@code openMenu} 没成</b>（1.21.1 里只有 {@code createMenu}
     *     返回 {@code null} 会这样；玩家已有界面时原版会先 {@code closeContainer()} 再开，
     *     不是静默拒绝）：actionbar 给「界面没能打开，请关掉其它界面再试」，不再叠一次开界面。</li>
     * </ol>
     */
    @Override
    protected InteractionResult useWithoutItem(final BlockState state,
                                               final Level level,
                                               final BlockPos pos,
                                               final Player player,
                                               final BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
            && level.getBlockEntity(pos) instanceof NetworkResourceRequesterBlockEntity requester) {
            if (!NetworkResourceRequesterMenu.openPickerFor(serverPlayer, requester)) {
                if (NetworkResourceRequesterMenu.isPickerConnected(requester)) {
                    // 网络没问题 ⇒ 只是这次 openMenu 没成：给一句原因
                    serverPlayer.displayClientMessage(Component.translatable(
                        NetworkResourceRequesterMenu.MSG_PICKER_OPEN_FAILED), true);
                } else {
                    // 没接网络 ⇒ 网格打不开是必然的：只说明原因。
                    // 第 131 轮：请求器「设置界面」已整页删除，因此**没有可退回的界面**了
                    //（`getMenuProvider` 的注释也写明「第 131 轮起菜单永远不会被打开」）。
                    // 这里原本还有一条 `player.openMenu(...)` 兜底，现在它是死路径，已删除。
                    serverPlayer.displayClientMessage(Component.translatable(
                        NetworkResourceRequesterMenu.MSG_PICKER_NO_NETWORK), true);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Nullable
    @Override
    protected MenuProvider getMenuProvider(final BlockState state,
                                           final Level level,
                                           final BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof NetworkResourceRequesterBlockEntity requester)) {
            return null;
        }
        // 标题与「从网格切回设置」共用菜单里的同一个 provider 工厂（标题是语言键，只有一份）
        return NetworkResourceRequesterMenu.providerFor(requester);
    }

    /**
     * 方块被破坏：升级槽 + <b>回滚暂存（returnBuffer）</b>按全局「内容物去向」策略结算。
     *
     * <p>配置（目标名 / 订单 / 待投计划）不是可掉的物品：它们随方块实体 NBT 走「存方块」策略
     * （重新放下原样恢复，待投计划也接着投）。<b>但 {@code returnBuffer} 装的是真实资源</b>
     * （已经从网络取出、回滚失败的那部分），必须交给 {@link BlockContentReleaser} 结算 ——
     * 回网 / 爆出，或在「存方块」档随 NBT 带走，<b>绝不销毁</b>。</p>
     */
    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof NetworkResourceRequesterBlockEntity requester) {
            BlockContentReleaser.release(level, pos, requester,
                new ItemStack(RS_Create_Compat.NETWORK_RESOURCE_REQUESTER_ITEM.get()),
                new BlockContentReleaser.Host() {
                    @Override
                    public void collectItems(final List<ItemStack> out) {
                        // 第 131 轮：请求器的 6 个升级槽已删除（用户拍板「跟插件槽没关系」），
                        // 因此这里只剩回滚暂存里的真实物品必须结算 —— 不能丢任何一件。
                        out.addAll(requester.releaseReturnBufferItems());
                    }

                    @Override
                    public void collectPatterns(final List<ItemStack> out) {
                        // 本方块不含样板
                    }

                    @Override
                    public void collectFluids(final List<FluidStack> out) {
                        // 回滚暂存里的流体：能回网就回网，否则存方块（流体永不爆出，见 BlockContentReleaser）
                        out.addAll(requester.releaseReturnBufferFluids());
                    }
                });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params) {
        final BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        return BlockContentReleaser.filterLoot(params.getLevel(), blockEntity,
            new java.util.ArrayList<>(super.getDrops(state, params)));
    }
}
