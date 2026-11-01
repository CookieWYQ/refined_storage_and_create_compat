package cretae.cookiewyq.rs_create_compat.client;

import net.minecraft.client.renderer.Rect2i;
import org.jetbrains.annotations.Nullable;

/**
 * 「区域条」（{@link cretae.cookiewyq.rs_create_compat.client.screen.AreaStripPanel}）在<b>屏幕坐标</b>里
 * 占的那块矩形 —— 给 JEI 看的<b>唯一出口</b>（第 125 轮，契约 §3.1）。
 *
 * <h2>为什么需要这一层</h2>
 * <p>用户原话：「你要考虑这一个显示的区域的问题，应该放在哪里？你要知道 JEI 是占据了屏幕的左右两侧的。
 * 要是乱放的话，会跟 JEI 重叠。」</p>
 * <p>JEI 的物品列表（{@code IngredientListOverlay}）会按「界面本体 + 额外占用区」算总占用，
 * 然后自己挪开。额外占用区就是通过 {@code IGuiContainerHandler#getGuiExtraAreas} 报给 JEI 的
 * <b>屏幕坐标</b>矩形。屏幕 {@code render} 每帧把本帧侧栏矩形写进这里，
 * {@code CollectionCacheJeiPlugin} 再原样返回给 JEI —— 侧栏一开一关、窗口一缩一放，
 * JEI 都跟着让位，<b>不存在任何「我们手动挪 JEI」的代码</b>（那样必然与 JEI 自己的布局打架）。</p>
 *
 * <h2>线程</h2>
 * <p>写入在客户端主线程（{@code AdvancedCollectionCacheScreen#render} 顶部）与
 * JEI 的 GUI handler 回调之间发生；两者在 1.21.1 里都是客户端主线程，但 JEI 可能在自己的
 * 布局计算里提前读一次，因此字段声明为 {@code volatile}，语义是「看到的是最近一次写入的那个矩形」。
 * 侧栏每帧 {@code new Rect2i(...)} 写一次、旧对象不再复用（{@link Rect2i} 本身有 setter，
 * 复用同一个实例会让 JEI 读到「正在被改」的坐标，这里刻意每帧换新对象）。</p>
 *
 * <h2>为什么是 {@code null} 而不是零矩形</h2>
 * <p>「侧栏不可见」必须与「侧栏在 (0,0) 处 0×0」区分开：后者会让 JEI 把一块无意义的矩形算进占用，
 * 前者才是「没有额外占用」。{@link #get()} 返回 {@code null} 时，
 * {@code CollectionCacheJeiPlugin} 返回 {@code List.of()}（契约 §3.3 / 验收反例「JEI 矩形为空不注册」）。</p>
 */
public final class AreaStripBounds {
    /** 最近一帧侧栏的屏幕坐标矩形；{@code null} = 侧栏不可见。 */
    private static volatile Rect2i area;

    private AreaStripBounds() {
    }

    /** 写入本帧侧栏矩形（屏幕坐标）。{@code null} = 侧栏不可见（JEI 不该为它让位）。 */
    public static void set(@Nullable final Rect2i value) {
        area = value;
    }

    /** 最近一帧侧栏矩形（屏幕坐标）；{@code null} = 侧栏不可见。 */
    @Nullable
    public static Rect2i get() {
        return area;
    }

    /** 关界面 / 收起侧栏时清空（{@code AdvancedCollectionCacheScreen#removed()} 必须调它）。 */
    public static void clear() {
        area = null;
    }
}
