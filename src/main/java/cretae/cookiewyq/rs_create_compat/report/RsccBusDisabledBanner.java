package cretae.cookiewyq.rs_create_compat.report;

import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 「总线延长型已停用（归属未确定）」的横幅出口 —— <b>完全复用既有的完成横幅系统</b>
 * （{@link CompatCompletionSender} + {@link CompletionBannerPayload} + 客户端 {@code CompatCompletionToast}），
 * 与蓝图装填器 / 装配看门狗用的是同一套，不新造任何横幅 / 点击回调（横幅本身是纯展示）。</p>
 *
 * <h2>为什么要有它（用户要求）</h2>
 * <i>「记得要加横幅提示：接上之后一旦被干扰就有提示。」</i> —— 玩家把线缆接上、或放下第二台执行舱的那一刻，
 * 归属判定就从「唯一」翻成「未确定」，延长型也随之停用。界面上有红条，但玩家当时多半没开着界面，
 * 因此这里再弹一条横幅当面提醒：<b>是这条总线停了，为什么停，怎么恢复</b>。
 *
 * <h2>四行文案（全部走语言键，客户端按玩家语言解析）</h2>
 * <ol>
 *     <li>{@code bus_interference.banner.toast.title}：「总线延长型已停用」；</li>
 *     <li>{@code bus_interference.banner.toast.exporter|importer}：「输出 / 输入总线 @ (x, y, z)」；</li>
 *     <li>{@code bus_interference.reason}：「归属未确定：线缆可达 N 台执行舱」（与界面 tooltip 同一句）；</li>
 *     <li>{@code bus_interference.hint}：「请用分隔框架隔离线缆，或把执行舱分开」（与界面 tooltip 同一句）。</li>
 * </ol>
 *
 * <p><b>节流归调用方</b>：本类每次都发；「同一总线同一状态只弹一次」的节流在方块实体侧
 * （状态翻转才调用本类），因此不会每 tick 刷屏。</p>
 */
public final class RsccBusDisabledBanner {
    /** 横幅文案的语言键前缀（与界面 tooltip 共用一套键，保证两处说法永远一致）。 */
    private static final String LANG = "gui.rs_create_compat.bus_interference.";
    /** 广播半径（格）：与装配看门狗的横幅同一量级，够覆盖一座基地。 */
    private static final int RADIUS = 96;
    private static final int RADIUS_SQ = RADIUS * RADIUS;
    /** 行色：标题暖橙 / 正文浅灰 / 原因淡红 / 建议淡蓝（与既有横幅同一套配色习惯）。 */
    private static final int COLOR_TITLE = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_REASON = 0xFFFF8A8A;
    private static final int COLOR_HINT = 0xFF9EC7FF;

    private RsccBusDisabledBanner() {
    }

    /**
     * 给 {@code busPos} 附近（半径 {@value #RADIUS} 格）的玩家发一条四行横幅。
     *
     * @param exporter       true = 输出总线（延长型输出），false = 输入总线（延长型输入）
     * @param reachableCount 线缆可达的执行舱台数（界面与横幅都显示这个 N）
     */
    public static void send(final ServerLevel level, final BlockPos busPos, final boolean exporter,
                            final int reachableCount) {
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(4);
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE,
            CompletionBannerPayload.localized(LANG + "banner.toast.title")));
        rows.add(CompletionBannerPayload.Row.text(COLOR_TEXT,
            CompletionBannerPayload.localized(
                LANG + (exporter ? "banner.toast.exporter" : "banner.toast.importer"),
                busPos.getX(), busPos.getY(), busPos.getZ())));
        rows.add(CompletionBannerPayload.Row.text(COLOR_REASON,
            CompletionBannerPayload.localized(LANG + "reason", reachableCount)));
        rows.add(CompletionBannerPayload.Row.text(COLOR_HINT,
            CompletionBannerPayload.localized(LANG + "hint")));
        CompatCompletionSender.sendToNearby(level, busPos, RADIUS_SQ, rows);
        // 诊断（只读）：记录一次「延长型被停用」横幅，便于统计「接上后是否真的提示过」。
        cretae.cookiewyq.rs_create_compat.support.RsccDiag.recordBanner(
            exporter ? "bus_disabled_exporter" : "bus_disabled_importer",
            busPos.getX() + "," + busPos.getY() + "," + busPos.getZ());
    }
}
