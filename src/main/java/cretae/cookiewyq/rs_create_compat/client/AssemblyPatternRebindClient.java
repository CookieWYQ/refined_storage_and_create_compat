package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.client.screen.AssemblyPatternRebindScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.SequenceExecutionChamberScreen;
import cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 「总样板改绑机器」界面的客户端入口（S2C 处理体的唯一落点）。
 *
 * <p><b>三种情形，三条不同的处置</b>：</p>
 * <ol>
 *     <li>改绑界面已经开着 ⇒ <b>就地刷新</b>。玩家的「选择机器」子界面可能正开在它上面，
 *     重开一次父界面会把子界面顶掉（写一步、界面一跳，连改多步就没法用了）；</li>
 *     <li>玩家当前没有任何界面（正常右键空气触发的情形）⇒ 打开改绑界面，Esc 回世界；</li>
 *     <li>玩家正开着执行舱界面（历史上曾靠「右键执行舱」触发，客户端预测会先开方块界面；
 *     现在入口是右键空气，正常不会再撞上，这条保留为防御）⇒ 把执行舱界面当<b>父界面</b>打开改绑界面，
 *     Esc 回到执行舱界面，服务端那份容器菜单因此不会被架空；</li>
 * </ol>
 * <p>其它界面（终端 / 监视器等）一律<b>不</b>强行切屏 —— 玩家已经在做别的事，弹窗会打断操作。</p>
 */
public final class AssemblyPatternRebindClient {
    private AssemblyPatternRebindClient() {
    }

    /** S2C 处理体：按当前界面决定「刷新」还是「打开」。 */
    public static void open(final AssemblyPatternRebindOpenPacket packet) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        final Screen current = minecraft.screen;
        if (current instanceof AssemblyPatternRebindScreen screen) {
            screen.update(packet);
            return;
        }
        if (current != null && !(current instanceof SequenceExecutionChamberScreen)) {
            return; // 已在别的界面里：不强行切屏
        }
        minecraft.setScreen(new AssemblyPatternRebindScreen(packet, current));
    }
}
