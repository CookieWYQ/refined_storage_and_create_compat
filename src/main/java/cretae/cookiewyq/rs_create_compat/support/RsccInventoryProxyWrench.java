package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.block.InventoryProxyBlock;
import cretae.cookiewyq.rs_create_compat.block.ProxyFaceRole;
import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 「手持扳手右键容器代理器的某一面 → 切换那一面的角色」。
 *
 * <h2>为什么挂在事件上，而不是方块类的 {@code useItemOn}</h2>
 * <p>本工程有一条既有硬规则（{@code tools/selfcheck_round53_wrench_dismantle.py} 会断言的）：
 * <b>方块类里不写任何扳手代码</b>，所有扳手接管都走
 * {@code PlayerInteractEvent.RightClickBlock}。原因不只是风格：事件在
 * {@code ServerPlayerGameMode#useItemOn} 的 346/347 行先发、被取消后整条方块交互链
 * （{@code BlockState#useItemOn} → {@code useWithoutItem} → {@code ItemStack#useOn}）一句都不执行，
 * 因此「扳手接管」与「右键开界面」在结构上互不影响，也不会抢掉任何物品的 {@code useOn}。</p>
 *
 * <h2>顺序（同优先级内按注册顺序）</h2>
 * <p>主类里的注册顺序是：伪装外壳 → 分隔框架 → 扳手断缝 + 快速拆卸（{@link RsccWrenchDismantle}）
 * → 本类。于是：</p>
 * <ul>
 *     <li><b>潜行 + 扳手</b>：{@link RsccWrenchDismantle} 先 {@code setCanceled(true)} 并拆掉方块，
 *     本类在 {@code event.isCanceled()} 处直接返回（不会拆完又改配置）；</li>
 *     <li><b>不潜行 + 扳手</b>：快速拆卸什么都不做 ⇒ 本类切换命中面的角色并消费本次右键；
 *     RS / Create 的扳手 {@code useOn}（默认优先级 / 物品路径）不会被执行；</li>
 *     <li><b>不是扳手</b>：本类一次都不取消 ⇒ {@code useWithoutItem} 照常开界面。</li>
 * </ul>
 */
public final class RsccInventoryProxyWrench {
    private static final String LANG = "gui.rs_create_compat.inventory_proxy.";

    private RsccInventoryProxyWrench() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccInventoryProxyWrench());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            // 伪装外壳 / 分隔框架 / 快速拆卸已经接管：一次右键只结算一层。
            return;
        }
        if (event.getEntity().isSpectator()) {
            return;
        }
        final ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(Tags.Items.TOOLS_WRENCH)) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof InventoryProxyBlock)) {
            return;
        }
        final Player player = event.getEntity();
        if (player.isCrouching()) {
            // 潜行 + 扳手 = 拆方块，归 RsccWrenchDismantle（它在同优先级里先注册、通常已经取消掉了）。
            return;
        }
        final Level level = event.getLevel();
        final Direction face = event.getFace() != null ? event.getFace() : Direction.UP;
        if (!level.isClientSide()
            && level.getBlockEntity(event.getPos()) instanceof InventoryProxyBlockEntity proxy) {
            final ProxyFaceRole role = proxy.cycleFace(face);
            player.displayClientMessage(Component.translatable(
                "message.rs_create_compat.inventory_proxy.face_set",
                Component.translatable(LANG + "direction." + face.getSerializedName()),
                Component.translatable(LANG + "face." + role.id())
            ), true);
            level.playSound(null, event.getPos(), SoundEvents.ITEM_FRAME_ROTATE_ITEM,
                SoundSource.BLOCKS, 0.7F, 1.0F);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
    }
}
