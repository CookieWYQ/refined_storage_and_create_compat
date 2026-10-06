package cretae.cookiewyq.rs_create_compat.client;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * 「分隔框架何时可见」的<b>唯一客户端判据</b>（渲染器每帧问一次）。
 *
 * <p><b>为什么单独一个类</b>：显示规则是纯客户端表现（服务端不需要知道玩家在不在看），
 * 而判定条件有三条来源（手持物品、护目镜、快捷键开关）。集中在这里，
 * 渲染器只需读一个布尔，将来加条件也只改一处。</p>
 *
 * <p><b>规则</b>（用户要求：默认不可见，不影响正常观感）：</p>
 * <ol>
 *     <li><b>手持任一种分隔框架</b>（主手或副手）→ 显示。与「拿出物品才看得到」的直觉一致，
 *     也让玩家能确认自己套过哪些管道；</li>
 *     <li>否则，<b>戴护目镜 且 快捷键打开</b> → 显示。护目镜<b>不会</b>让它一直显示
 *     （用户原话：一直显示会怪怪的），必须由快捷键显式打开；</li>
 *     <li>其余情况一律不绘制（默认完全不可见）。</li>
 * </ol>
 *
 * <p><b>「附近」还是「全部」</b>：这里选<b>全部</b> —— 判定不含坐标，渲染范围由渲染器本身
 * 决定（方块实体渲染器只对客户端当前正在渲染的区块被调用，天然就是「视野内的那些」）。
 * 若再叠一层距离判断，反而会出现「走到边界时方块突然闪一下」的观感问题。
 * 与本模组既有做法保持一致：既有的世界内提示（幽灵标记等）同样不做半径筛选，
 * 只由渲染管线决定可见范围。</p>
 *
 * <p><b>护目镜复用</b>：直接用 Create 的 {@link GogglesItem#isWearingGoggles(Player)}
 * （与本模组其它地方一致，不新造护目镜判定，也不新造一套「装备槽检查」）。</p>
 */
public final class SeparationFrameVisibility {
    /** 快捷键开关（纯客户端偏好，不落盘、不进行为）。 */
    private static boolean revealToggled;

    private SeparationFrameVisibility() {
    }

    /** 快捷键切换「护目镜模式下的框架显示」。 */
    public static void toggleReveal() {
        revealToggled = !revealToggled;
    }

    /** 快捷键开关当前状态（用于给玩家一句 actionbar 反馈）。 */
    public static boolean isRevealToggled() {
        return revealToggled;
    }

    /** 本帧是否应绘制分隔框架方块。 */
    public static boolean shouldRender() {
        final Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return holdsFrame(player) || (revealToggled && GogglesItem.isWearingGoggles(player));
    }

    /** 主手 / 副手是否拿着任一种分隔框架。 */
    public static boolean holdsFrame(final Player player) {
        return player.getMainHandItem().getItem() instanceof SeparationFrameItem
            || player.getOffhandItem().getItem() instanceof SeparationFrameItem;
    }
}
