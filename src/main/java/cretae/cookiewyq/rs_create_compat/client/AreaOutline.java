package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.Area;
import com.simibubi.create.AllSpecialTextures;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * <b>区域选定器 / 高级归流缓存仓「查看范围」共用的范围描边出口</b>（橙，统一一个专属颜色）。
 *
 * <p><b>注意（第 95 轮交付前复核修注释）</b>：本类<b>不是</b>全模组唯一的「范围显示」出口 ——
 * 充电器的「显示范围」框在 {@code client/screen/RangeChargerScreen} 里自己维护一份（它需要
 * 「每 tick 重喂 + 关界面按剩余 TTL 重新武装」的生命周期，与这里的「按区域列表喂」不同）。
 * 两处的色值 / 线宽 / 面贴图刻意保持一致：{@code 0xFF9F1C} + {@code 1/16} 线宽 + 棋盘面；
 * 若将来改其中一处，另一处必须同步（两边都写了这条提醒）。
 *
 * <h2>颜色（用户拍板「所有范围显示统一一个专属颜色」）</h2>
 * <p>{@link #COLOR} = {@code 0xFF9F1C}（橙），<b>刻意避开蓝图框选的 {@code 0x6886c5}（蓝紫）</b> ——
 * 橙色与蓝紫在色相上几乎互补，一眼能区分「这是本模组的范围」与「这是 Create 的蓝图框」。
 * 注意 {@code OutlineParams#colored(int)} 只吃 RGB（{@code new Color(color, false)}），
 * 不能带 alpha；透明度由 Outliner 的淡出与面贴图决定，因此这里的常量是 {@code 0xFF9F1C}
 * 但传参时只用到低 24 位（写 {@code 0x9F1C} 或 {@code 0xFF9F1C} 结果相同）。</p>
 *
 * <h2>显示样式（本轮统一，与范围充电器同一套）</h2>
 * <p>范围框 = <b>橙色 + 棋盘面贴图 + 1/16 线宽</b>
 * （{@code withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)}）。
 * 只有线没有面时，隔着世界看几乎不可见（用户第 5 条抱怨的「范围显示没正常工作」）；<b>三个入口
 * （{@link #showForView} / {@link #chase} / {@link #chaseBox}）必须完全一样</b>，否则同一个功能
 * 在不同界面里长得不一样。面贴图只在「玩家主动点了一次查看 / 正在框选」时出现，不是常驻渲染。</p>
 *
 * <h2>为什么不用自己挂渲染事件</h2>
 * <p>Ponder 已经在 {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES} 渲染全局 {@link Outliner}、
 * 并在 {@code ClientTickEvent.Pre} 里 {@code tickOutlines()}；Create 硬依赖 Ponder，
 * 因此我们只负责「喂一个 AABB」，不用写任何渲染器（{@code docs/AREA_SELECTOR_AND_CHARGE_RANGE.md} §2.2）。</p>
 *
 * <h2>降级</h2>
 * <p>Outliner 在 Ponder 被禁用 / 替换时会静默不显示。所有调用都包一层 {@code try/catch(Throwable)}：
 * 第一次失败后 {@link #disabled} 置真并<b>不再重试</b>（避免每帧刷异常日志），
 * 表现是「框不出现」，其余功能一切照常。</p>
 */
public final class AreaOutline {
    /** 统一范围色（橙）。蓝图蓝 = 0x6886c5，两者不同（自检脚本断言）。 */
    public static final int COLOR = 0xFF9F1C;
    /**
     * 蓝图同款线宽（1/16 格）。<b>必须显式给</b>：Outliner 的默认线宽是 1/32，实机隔着世界看几乎不可见
     * （用户第 5 条「显示范围的……都没有正常的工作」的一部分原因就是这个）。
     */
    public static final float LINE_WIDTH = 1 / 16f;
    /** 「查看范围」按钮的显示时长（tick）：100 tick = 5 秒，之后由 Outliner 自己淡出 8 tick。 */
    public static final int VIEW_TTL = 100;

    private static boolean disabled;

    private AreaOutline() {
    }

    /**
     * 一次「显示 5 秒」的框（范围按钮用；重复调用 = 重新计时）。
     * <p>只给「查看范围」这一条路径用；区域选定器的实时框选走 {@link #chase} / {@link #chaseBox}
     * （每 tick 重喂，语义是「一直显示到不再喂」）。</p>
     */
    public static void showForView(final Object slot, final Area area) {
        showForView(slot, area, VIEW_TTL);
    }

    /**
     * 同上，但显式指定 TTL。
     * <p><b>为什么要这个重载</b>：界面 {@code removed()}（关 GUI）时要按<b>剩余</b> TTL 重新武装一次，
     * 让框在关掉界面后继续画完、再由 Outliner 自己淡出 —— 否则玩家点「查看范围」再关界面就什么都看不到
     * （范围充电器本轮修掉的是同一个生命周期 bug，见 {@code RangeChargerScreen#removed()}）。</p>
     */
    public static void showForView(final Object slot, final Area area, final int ttl) {
        if (area == null) {
            return;
        }
        try {
            Outliner.getInstance()
                .showAABB(slot, boxOf(area), Math.max(1, ttl))
                .colored(COLOR)
                .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                .lineWidth(LINE_WIDTH);
        } catch (final Throwable failure) {
            disabled = true;
        }
    }

    /** 持续显示的框（世界框选预览 / 「显示全部区域」用；每 tick 喂一次才保持）。 */
    public static void chase(final Object slot, final Area area) {
        if (area == null) {
            return;
        }
        try {
            Outliner.getInstance()
                .chaseAABB(slot, boxOf(area))
                .colored(COLOR)
                .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                .lineWidth(LINE_WIDTH);
        } catch (final Throwable failure) {
            disabled = true;
        }
    }

    /** 直接喂一个原始 AABB（区域预览在提交前还不是 {@link Area}）。 */
    public static void chaseBox(final Object slot, final AABB box) {
        if (box == null) {
            return;
        }
        try {
            Outliner.getInstance()
                .chaseAABB(slot, box)
                .colored(COLOR)
                .withFaceTextures(AllSpecialTextures.CHECKERED, AllSpecialTextures.HIGHLIGHT_CHECKERED)
                .lineWidth(LINE_WIDTH);
        } catch (final Throwable failure) {
            disabled = true;
        }
    }

    /** 移除某个 slot 的框（对不存在的 slot 是空操作）。 */
    public static void remove(@Nullable final Object slot) {
        if (slot == null) {
            return;
        }
        try {
            Outliner.getInstance().remove(slot);
        } catch (final Throwable failure) {
            disabled = true;
        }
    }

    /** 区域 → 渲染包围盒（两角各占一格 ⇒ 上界 +1，与蓝图 {@code expandTowards(1,1,1)} 同口径）。 */
    public static AABB boxOf(final Area area) {
        return area.toAabb();
    }

    /** 两点预览框（未提交的框选）：与蓝图逐字同构。 */
    public static AABB previewBox(final net.minecraft.core.BlockPos first,
                                  final net.minecraft.core.BlockPos second) {
        return new AABB(Vec3.atLowerCornerOf(first), Vec3.atLowerCornerOf(second)).expandTowards(1, 1, 1);
    }

    /** 本次进程内 Outliner 是否已被判定不可用（诊断 / 自检可见）。 */
    public static boolean isDisabled() {
        return disabled;
    }

    /** Outliner 槽位 key（带模组前缀，避免和 Create 自己的 slot 撞车）。 */
    public static Object slot(final String name) {
        return RS_Create_Compat.MODID + ":area/" + name;
    }
}
