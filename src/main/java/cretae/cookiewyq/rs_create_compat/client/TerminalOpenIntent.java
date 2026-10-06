package cretae.cookiewyq.rs_create_compat.client;

/**
 * 客户端「打开意图」标记：只有<b>由本模组终端主动打开</b>的 RS 界面，才允许叠加右下角那一排模式切换按钮。
 *
 * <p><b>为什么需要它（回归修复）</b>：早先右下角按钮的判定是「当前界面是 RS 界面 <b>且</b> 玩家身上带着终端」。
 * 后来为了让「终端放在背包里也能用」把查找范围从「双手 / Curios」扩到「背包任意格」，
 * 后果变成：<b>只要背包里有终端，打开自动合成管理器等任意 RS 界面都会多出这一排</b>（用户实测回归）。
 * 因此把「是不是终端打开的」从「玩家有没有终端」改成显式的打开意图：
 * <ul>
 *     <li>按快捷键时置位（{@code TerminalKeybinds}）；</li>
 *     <li>手持终端右键使用时置位（{@code AdvancedRemoteTerminalItem#use(Level, ...)} 的客户端分支）；</li>
 *     <li>点击模式切换按钮导致界面重开时置位（{@code TerminalModeTabOverlay} 的按钮回调）。</li>
 * </ul>
 *
 * <p><b>为什么在 client 包却能被 common 侧调用</b>：本类刻意<b>不引用任何客户端专属类型</b>（只用 java.lang），
 * 因此 common 侧的物品 {@code use(...)} 引用它不会造成专用服务端的类缺失（且服务端分支根本不会执行到这里）。</p>
 *
 * <p><b>超时（{@value #TIMEOUT_MS} ms）</b>：服务端收到请求 → 打开菜单 → 客户端收到「打开界面」包再
 * {@code Init.Post}，链路很短，2~3 秒足够；给短超时是为了避免「意图置位了但界面没开成」时标记长期残留，
 * 导致之后随便打开一个 RS 界面又冒出这一排按钮。</p>
 *
 * <p><b>一次性</b>：标记只允许<b>一个</b>界面实例认领；同一实例的重复初始化
 * （窗口缩放会重建控件并再次触发 {@code Init.Post}）仍返回 true，避免缩放一下按钮整排消失。</p>
 */
public final class TerminalOpenIntent {
    /** 标记有效期（毫秒）：够覆盖「服务端开菜单 + 客户端建界面」的往返，又短到不会误留到下一次打开。 */
    private static final long TIMEOUT_MS = 3000L;

    private static long expireAt;
    private static Object claimedBy;

    private TerminalOpenIntent() {
    }

    /** 置位：按键 / 手持右键 / 点击模式按钮导致（新）界面即将打开时调用。 */
    public static void mark() {
        expireAt = System.currentTimeMillis() + TIMEOUT_MS;
        claimedBy = null;
    }

    /**
     * 消费标记：已超时或已被别的界面认领 → {@code false}（一个按钮都不加）；否则认领并返回 {@code true}。
     *
     * @param screen 当前正在初始化的界面实例，只用于识别「同一个界面」
     */
    public static boolean consume(final Object screen) {
        if (System.currentTimeMillis() > expireAt) {
            expireAt = 0L;
            claimedBy = null;
            return false;
        }
        if (claimedBy == null) {
            claimedBy = screen;
            return true;
        }
        return claimedBy == screen;
    }
}
