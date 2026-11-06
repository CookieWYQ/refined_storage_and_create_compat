package cretae.cookiewyq.rs_create_compat.client.grid;

import com.refinedmods.refinedstorage.common.support.amount.AbstractAmountScreen;
import com.refinedmods.refinedstorage.common.support.amount.AmountScreenConfiguration;
import com.refinedmods.refinedstorage.common.support.amount.LongAmountOperations;

import java.util.function.LongConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/**
 * 发送器的<b>订单数量子界面</b> —— 直接用 RS 自己的
 * {@link AbstractAmountScreen}（第 132 轮用户原话：要 RS 那种点数量弹出来的子界面，
 * 不要自己手写输入框）。
 *
 * <p><b>关于 dummy 菜单类型</b>：契约原本要求写成
 * {@code AbstractAmountScreen<AbstractAmountScreen.DefaultDummyContainerMenu, Long>}，
 * 但该嵌套类是 {@code protected}，跨包在<b>类声明的类型参数</b>里无法用限定名 / 简单名引用
 * （javac：{@code DefaultDummyContainerMenu 在 AbstractAmountScreen 中是 protected 访问控制}）。
 * 因此这里用它的最小子类 {@link DummyMenu} 作为类型参数（仍复用 RS 的 dummy 菜单，
 * 不新增菜单类型 / 槽位），其余行为与契约一致。</p>
 *
 * <h2>为什么是 {@code Long} 而不是 RS 原版的 {@code Double}</h2>
 * <p>订单数量的权威单位是 {@code long}（物品个数 / 流体 mB），{@code RequesterOrderPacket}
 * 最终按 {@code int} 发送。这里用 {@link LongAmountOperations}，与 RS 的
 * {@code ResourceAmountScreen}（{@code DoubleAmountOperations}）同一套交互：
 * 顶部 +1 / +10 / +64、底部 −1 / −10 / −64、滚轮 ±1、Set / Reset / Cancel。
 * 数量上限由父界面按「网络可用量」传入；确认值与 int 夹取都在父界面里做，
 * 本类只负责弹窗与回传。</p>
 *
 * <h2>确认后去哪儿</h2>
 * <p>{@link #confirm(Long)} 回调 {@link ResourcePickerScreen}，再由它发
 * {@code RequesterOrderPacket(ACTION_SET_AMOUNT)}；随后返回 {@code true}，
 * {@code AbstractAmountScreen} 会自动把界面切回父界面（{@code parent}）。
 * 取消 / Esc 走 RS 自己的 {@code close()}，同样回到父界面，<b>不发任何包</b>。</p>
 */
public class OrderAmountScreen extends AbstractAmountScreen<OrderAmountScreen.DummyMenu, Long> {

    /**
     * {@link AbstractAmountScreen.DefaultDummyContainerMenu} 的构造器是 {@code protected}，
     * 跨包时只能在子类里用 {@code super()} 调 —— 因此这里放一个最小子类，屏幕本身仍复用
     * RS 的 dummy 菜单（不新增任何菜单类型 / 槽位）。
     */
    static final class DummyMenu extends DefaultDummyContainerMenu {
        private DummyMenu() {
            super();
        }
    }

    /** 与 RS 原版数量子界面同一张贴图（180×99）。 */
    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "textures/gui/resource_amount.png");

    /** 子界面标题：订单数量。 */
    public static final String TITLE_KEY = "gui.rs_create_compat.resource_picker.order.amount";

    private final LongConsumer onConfirm;

    /**
     * @param parent    发送器父界面（确认 / 取消后 RS 自动切回它）
     * @param initial   当前订单数量（至少 1）
     * @param maxAmount 数量上限（网络可用量；拿不到时父界面传 {@link Integer#MAX_VALUE}）
     * @param onConfirm 确认回调（参数 = 已通过 RS 校验的数量）
     */
    public OrderAmountScreen(final ResourcePickerScreen parent,
                             final long initial,
                             final long maxAmount,
                             final LongConsumer onConfirm) {
        super(
            new DummyMenu(),
            parent,
            Minecraft.getInstance().player.getInventory(),
            Component.translatable(TITLE_KEY),
            buildConfiguration(initial, maxAmount),
            LongAmountOperations.INSTANCE
        );
        this.onConfirm = onConfirm;
        this.imageWidth = 180;
        this.imageHeight = 99;
    }

    /**
     * 配置逐项照抄 RS 的 {@code ResourceAmountScreen}（只把 {@code Double} 换成 {@code Long}）：
     * 增量按钮 1 / 10 / 64，底部 −1 / −10 / −64，数量框位置 (9,51)，操作按钮 (114,22)，
     * 最小 1，最大 = 父界面给的上限，Reset = 1。
     */
    private static AmountScreenConfiguration<Long> buildConfiguration(final long initial,
                                                                      final long maxAmount) {
        final long max = Math.max(1L, Math.min((long) Integer.MAX_VALUE, maxAmount));
        final long start = Math.max(1L, Math.min(initial, max));
        return AmountScreenConfiguration.AmountScreenConfigurationBuilder.<Long>create()
            .withInitialAmount(start)
            .withIncrementsTop(1, 10, 64)
            .withIncrementsBottom(-1, -10, -64)
            .withIncrementsBottomStartPosition(new Vector3f(7, 72, 0))
            .withAmountFieldPosition(new Vector3f(9, 51, 0))
            .withActionButtonsStartPosition(new Vector3f(114, 22, 0))
            .withMinAmount(() -> 1L)
            .withMaxAmount(max)
            .withResetAmount(1L)
            .build();
    }

    /** 确认：回传数量并让 RS 自动关回父界面（{@code parent}）。 */
    @Override
    protected boolean confirm(final Long amount) {
        onConfirm.accept(amount);
        return true;
    }

    @Override
    protected ResourceLocation getTexture() {
        return TEXTURE;
    }
}
