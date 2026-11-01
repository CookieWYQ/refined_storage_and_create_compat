package cretae.cookiewyq.rs_create_compat.block;

import com.refinedmods.refinedstorage.common.support.network.NetworkNodeBlockEntityTicker;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity;
import cretae.cookiewyq.rs_create_compat.network.FEEnergyCubeNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.EnergyCubeCluster;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * FE 能量立方方块：单块 5000 万 FE 的备用电池，六面 FE 输入输出，右键打开电量条界面。
 *
 * <h2>方块状态</h2>
 * <p><b>没有任何 BlockState 属性</b>（没有 active / lit / facing）：立方的外观不随接入状态变化，
 * 因此 ticker 用不带属性参数的 {@link NetworkNodeBlockEntityTicker} 构造。
 *
 * <h2>破坏时的电量（「各自实际电量保留」的落地方式）</h2>
 * <p>每块电量只记在自己身上，所以「拆一块」不需要任何内容移交（见 {@link EnergyCubeCluster}）。
 * 被拆那一块的电量由 {@link #getDrops} 写进<b>掉落的方块物品</b>的 {@code BLOCK_ENTITY_DATA}：
 * 用的是原版通用接口 {@link BlockEntity#saveToItem(ItemStack, net.minecraft.core.HolderLookup.Provider)}
 * （内部 = {@code saveAdditional} 的 NBT + {@code BlockItem.setBlockEntityData} 补 {@code id} +
 * 自定义组件），放置时由原版 {@code BlockItem.updateCustomBlockEntityTag} →
 * {@code BlockEntity.loadWithComponents} → {@code loadAdditional} 自动还原。
 * <p><b>如实说明（不假装不丢）</b>：这条还原路径无法在专服探针里真机验证「放置后电量回来」，
 * 自检只做源码级断言（{@code saveToItem} / {@code TAG_ENERGY} 两侧配对）；另外<b>若用非镐工具破坏</b>
 * （掉落表按工具过滤为空）⇒ 没有物品可承载电量，这一份电量随方块消失 —— 这是原版掉落表语义，
 * 不是本方块的特殊行为。
 */
public class FEEnergyCubeBlock extends Block implements EntityBlock {
    private static final BlockEntityTicker<FEEnergyCubeBlockEntity> TICKER =
        new NetworkNodeBlockEntityTicker<FEEnergyCubeNetworkNode, FEEnergyCubeBlockEntity>(
            () -> RS_Create_Compat.FE_ENERGY_CUBE_BLOCK_ENTITY.get());

    public FEEnergyCubeBlock(final Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new FEEnergyCubeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final Level level,
                                                                  final BlockState state,
                                                                  final BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null; // 客户端不 tick：集群解析与所有写操作都只在服务端
        }
        return (BlockEntityTicker<T>) TICKER;
    }

    @Override
    protected InteractionResult useWithoutItem(final BlockState state,
                                               final Level level,
                                               final BlockPos pos,
                                               final Player player,
                                               final BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            player.openMenu(state.getMenuProvider(level, pos), pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    // ==================== 集群失效（放置 / 破坏 / 邻块变化） ====================

    /**
     * 放置：新块可能刚贴上旧集群（容量要立刻变大）。
     * <p>只作废缓存，不做任何解析 —— 下一次任何成员 / 界面 / 网络节点读值时自然重建。
     */
    @Override
    protected void onPlace(final BlockState state, final Level level, final BlockPos pos,
                           final BlockState oldState, final boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        EnergyCubeCluster.invalidateAround(level, pos);
    }

    /**
     * 邻块变化：贴着放 / 拆掉一块立方时，<b>幸存者</b>不会重新 onPlace，但这里会被通知到
     * （方块更新由放置 / 移除触发）—— 集群容量必须在同一 tick 就变成新块数，不能等 20 tick 周期校验。
     */
    @Override
    protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos,
                                   final Block neighborBlock, final BlockPos neighborPos,
                                   final boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        EnergyCubeCluster.invalidateAround(level, pos);
    }

    /** 破坏：成员离场，邻居缓存立即作废（方块实体自身的 {@code setRemoved} 还会再作废一次，幂等）。 */
    @Override
    protected void onRemove(final BlockState state, final Level level, final BlockPos pos,
                            final BlockState newState, final boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            EnergyCubeCluster.invalidateAround(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ==================== 掉落（电量随方块物品保留） ====================

    /**
     * 掉落物品：把本块电量写进方块物品的 {@code BLOCK_ENTITY_DATA}（见类注释的「破坏时的电量」）。
     *
     * <p>做法是原版通用接口 {@link BlockEntity#saveToItem}：
     * <ol>
     *     <li>{@code saveCustomOnly} = 一个只含 {@code saveAdditional} 产物的 tag（里面就有
     *     {@link FEEnergyCubeBlockEntity#TAG_ENERGY}）；</li>
     *     <li>{@code BlockItem.setBlockEntityData} 补上 {@code id}（= 方块实体类型 id）并写入
     *     {@code DataComponents.BLOCK_ENTITY_DATA}；</li>
     *     <li>放置时 {@code BlockItem.updateCustomBlockEntityTag} 校验 {@code id} 后
     *     {@code loadWithComponents} → {@code loadAdditional} 还原电量。</li>
     * </ol>
     * 因此「电量在物品里」不是本模组私有协议，而是 1.21.1 原版方块物品的标准还原路径。
     */
    @Override
    protected List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params) {
        final List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        if (drops.isEmpty()) {
            // 掉落表为空（工具不对 / 被规则取消）：没有物品可承载电量 ⇒ 这一份电量随方块消失。
            return drops;
        }
        if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
            instanceof FEEnergyCubeBlockEntity cube)) {
            return drops;
        }
        if (cube.getOwnEnergyStored() <= 0) {
            return drops; // 空电池：没必要给物品写一个空 tag
        }
        // 只把「本块」的电量交给掉落物；同一集群里其它块的电量一格不动（不做平均、不迁移）。
        for (final ItemStack stack : drops) {
            if (stack.is(RS_Create_Compat.FE_ENERGY_CUBE_ITEM.get())) {
                cube.saveToItem(stack, params.getLevel().registryAccess());
                break;
            }
        }
        return drops;
    }

    // ==================== 界面 ====================

    @Nullable
    @Override
    protected MenuProvider getMenuProvider(final BlockState state, final Level level, final BlockPos pos) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof FEEnergyCubeBlockEntity cube)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> cretae.cookiewyq.rs_create_compat.menu.FEEnergyCubeMenu.create(
                id, inventory, cube),
            Component.translatable("block.rs_create_compat.fe_energy_cube"));
    }
}
