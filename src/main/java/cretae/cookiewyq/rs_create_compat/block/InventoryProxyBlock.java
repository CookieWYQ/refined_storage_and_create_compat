package cretae.cookiewyq.rs_create_compat.block;

import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import cretae.cookiewyq.rs_create_compat.menu.InventoryProxyMenu;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 容器代理器方块：{@link InventoryProxyBlockEntity} 的壳。
 *
 * <h2>交互（用户已定）</h2>
 * <ul>
 *     <li><b>空手 / 其它物品右键</b> ⇒ 打开界面（界面里能逐面切换角色，并显示一行「主 / 备内容物面」说明）。</li>
 *     <li><b>手持扳手右键某一面</b> ⇒ 把被点的那一面循环 代理对象 → 内容物（主）→ 内容物（备·仅进）→ 禁用
 *     （主面那一档会跳过「备」，因为归一规则下主面降档会被立刻升回来，见
 *     {@code ProxyFaceRole} 与 {@code InventoryProxyBlockEntity#cycleFace}）。
 *     这一条<b>不在本类里</b>：扳手接管全部走
 *     {@code support/RsccInventoryProxyWrench}（{@code PlayerInteractEvent.RightClickBlock}，
 *     与 support 里的「快速拆卸」挂点同一条事件链）。这样本类的 {@code useWithoutItem} 里
 *     一行「手持物 / 潜行」判断都没有（本工程的自检会把这种写法判负），
 *     也不会抢掉任何物品的 {@code useOn}。</li>
 *     <li><b>潜行 + 扳手</b> 仍由「快速拆卸」挂点拆方块（拆之前不会触发面切换：
 *     那条挂点在同优先级里排在前面，并且会 {@code setCanceled(true)}）。</li>
 * </ul>
 *
 * <h2>为什么不注册 {@code FACING}</h2>
 * <p>六面角色是<b>绝对方向</b>的逐面配置（存 NBT）。若再引入 {@code FACING}，旋转方块就得同步搬移角色、
 * 还要处理「外部缓存仍指向旧面」（设计文档 {@code CONTAINER_PROXY_DESIGN.md} §5.3 / R14）。
 * 不做朝向 ⇒ 不存在这一类错乱，也不需要 {@code rotate}/{@code mirror} 覆写。</p>
 *
 * <h2>掉落</h2>
 * <p>方块本体由战利品表掉落；代理对象（可能价值连城：装满东西的背包 / 磁盘）在
 * {@link #onRemove} 里单独弹出 —— 否则拆掉代理器会把容器里的东西一起吞掉（同文档 R16）。</p>
 */
public class InventoryProxyBlock extends Block implements EntityBlock {
    public InventoryProxyBlock(final Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
        return new InventoryProxyBlockEntity(pos, state);
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

    @Nullable
    @Override
    protected MenuProvider getMenuProvider(final BlockState state,
                                           final Level level,
                                           final BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof InventoryProxyBlockEntity proxy)) {
            return null;
        }
        return new SimpleMenuProvider(
            (id, inventory, player) -> InventoryProxyMenu.create(id, inventory, proxy),
            Component.translatable("block.rs_create_compat.inventory_proxy")
        );
    }

    @Override
    public void onRemove(final BlockState state,
                         final Level level,
                         final BlockPos pos,
                         final BlockState newState,
                         final boolean movedByPiston) {
        if (!state.is(newState.getBlock())
            && !level.isClientSide()
            && level.getBlockEntity(pos) instanceof InventoryProxyBlockEntity proxy) {
            // 兜底队列（PendingEject）先冲掉：BE 马上就要被移除，之后没人再负责弹出。
            proxy.flushPendingEjectBeforeRemoval();
            final ItemStack dropped = proxy.takeProxyStackForDrop();
            if (!dropped.isEmpty()) {
                popResource(level, pos, dropped);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
