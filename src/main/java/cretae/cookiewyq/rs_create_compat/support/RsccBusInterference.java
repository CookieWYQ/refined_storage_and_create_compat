package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import cretae.cookiewyq.rs_create_compat.report.CompatCompletionSender;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 「延长型总线归属未确定 / 被停用」的<b>唯一判定入口</b>。
 *
 * <h2>语义（用户新决定，取代上一轮的「只提示不停用」）</h2>
 * <i>「等距 / 多台可达时默认不打太极：直接标为「归属未确定」并停用这条延长型，界面写清
 * 「请指定归属或用分隔框架隔离」—— 这时候停用反而是保护，因为玩家明确知道它没在工作。
 * 因为我想做的就是尽量减少这样子的选择，尤其是这种总线都用线缆接了还选择什么；
 * 记得要加横幅提示：接上之后一旦被干扰就有提示。」</i>
 *
 * <p>把这句话翻译成可判定的形式，并落到 {@link RsccWireLinkSearch.LinkWalk} 上：</p>
 * <ol>
 *     <li>取这条总线的<b>线缆簇</b>（从总线自身出发沿「可穿行集合」逐层展开，<b>不穿过执行舱</b>）；
 *     被扳手断开的接缝与被分隔框架套住的一侧都算「这里没有线」—— 与 RS 网络图连边、连接臂外形
 *     共用同一判定（见 {@link RsccWireLinkSearch} 里的两道闸门）；</li>
 *     <li>数一数这条簇<b>一共够得到几个「总线输出」执行舱</b>（旧实现只记「最近的那台」，
 *     现在同一趟展开把碰到的<b>全部</b>都记下来），以及探查<b>是否穷尽</b>；
 *     <b>同一趟展开里属于同一条链的若干台会在 {@link RsccWireLinkSearch} 里先合并成一个逻辑执行仓</b>
 *     （见那里的 {@code collapseByChain}）—— 所以第 2 步数出来的其实是「够得到几条<b>不同的链</b>」，
 *     这正是「4 台成链 = 一台」该有的口径；</li>
 *     <li><b>判定规则</b>：
 *     <ul>
 *         <li>可达<b>恰好 1 条链</b>且探查穷尽 → 归属成立，延长型照常工作；</li>
 *         <li>可达 <b>≥2 条不同的链</b>（<b>不论距离是否相等</b>，等距只是其中一种）→ 归属未确定；</li>
 *         <li>可达恰好 1 条链但<b>探查未能穷尽</b>（走满步数上限 / 格子数到顶）→ 同样按归属未确定处理
 *         （宁可保护：再远的地方可能还有一条链）。</li>
 *     </ul>
 *     输出总线与输入总线<b>用同一条规则</b>（两侧共用本判定）。</li>
 * </ol>
 *
 * <p><b>为什么「另一个网络的线缆簇」不需要单独判定</b>：RS 的网络就是「连通的线缆簇」，
 * 所以两个线缆簇之间若不相连，本来就不是同一个网络；而「相邻但被切断」必然来自扳手断开或分隔框架
 * （没有第三条路），那两种情况在第 1 步里已算作「这里没有线」，是玩家<b>显式</b>做出的隔离，
 * 因此刻意<b>不</b>判为未确定 —— 否则「装了分隔框架断开」也会报停用，与用户要求（用框架隔离即可）
 * 正好相反。</p>
 *
 * <p><b>为什么「簇里有另一条总线的归属与本机不同」不需要单独判定</b>：线缆连通是双向的，
 * 簇里若还有一条总线，它够得到的执行舱必然也是本簇够得到的 —— 只要那台与本机的归属不同，
 * 「可达链数」就已经 ≥2，第 2 步自然会判为未确定。因此那条判定是第 2 步的<b>子集</b>，
 * 保留它只会多跑一遍搜链（旧实现正是这样）而不会改变结论。</p>
 *
 * <p><b>「被干扰」这个名字保留下来</b>：它是这块功能在工程里的历史命名（包名 / 类名 / 语言键前缀），
 * 语义已整体改写为「归属未确定 → 停用 + 提示」，两者指的是同一件事。</p>
 *
 * <p><b>只读、可缓存、状态变化即时生效</b>：本类不写任何状态，只读方块状态与既有连接判定；
 * 调用方按 tick 缓存结果（方块实体的 20 tick 缓存，见两个方块实体 Mixin），邻块变化由
 * {@code support/RsccBusLinkInvalidation} 立刻作废缓存 → 玩家接线 / 拆线 / 再放一台执行舱后
 * <b>≤1 秒</b>内状态翻转（通常下一个 tick）。判定结果是<b>服务端权威</b>，客户端只镜像 S2C 包。</p>
 */
public final class RsccBusInterference {
    /** 上报给界面的「可达执行舱」坐标上限（只影响提示文案长度，不影响判定；判定看的是完整链数）。 */
    public static final int MAX_LISTED_CHAMBERS = 12;

    private RsccBusInterference() {
    }

    /**
     * 一次判定的结果。
     *
     * @param disabled       归属是否<b>未确定</b>（= 该延长型必须停用、退回普通总线 + 界面提示 + 横幅）。
     *                       {@code false} 时延长型照常工作（唯一归属 / 根本够不到执行舱）
     * @param truncated      探查是否<b>未能穷尽</b>（走满步数上限 / 格子数到顶）—— 这时
     *                       {@code chambers} 可能不全，界面需要单独说明「可能还有更多可达执行舱」，
     *                       且只要够到过执行舱就已经被 {@code disabled} 收口
     * @param reachableCount 线缆簇<b>一共</b>够得到的<b>链（= 逻辑执行仓）数</b>（界面文案里的 N）：
     *                       同一条链上够到多台也只算一个（去重在 {@link RsccWireLinkSearch} 里完成，
     *                       见 {@code collapseByChain}），因此「4 台成链 = 一台」不会被自己误判成未确定
     * @param cluster        线缆簇（含总线自身；按 BFS 顺序，供半透明叠加层标出「延长段」）
     * @param chambers       可达执行舱坐标（<b>每条链一台</b>：该链上最近的成员；按「先距离近、再坐标字典序」；
     *                       最多 {@value #MAX_LISTED_CHAMBERS} 个，完整数量见 {@code reachableCount}；
     *                       供界面文案 + 叠加层标红）
     */
    public record Report(boolean disabled, boolean truncated, int reachableCount,
                         List<BlockPos> cluster, List<BlockPos> chambers) {
        /** 「没有延长型问题」的唯一实例（空表，可安全复用）。 */
        public static final Report CLEAR = new Report(false, false, 0, List.of(), List.of());
    }

    /**
     * 判定一条总线是否「归属未确定」（= 必须停用延长型）。
     *
     * @param busPos 总线坐标（输出总线 / 输入总线；服务端只读判定，客户端一律 {@link Report#CLEAR}）
     */
    public static Report inspect(final Level level, final BlockPos busPos) {
        if (level == null || level.isClientSide() || !level.isLoaded(busPos)) {
            return Report.CLEAR;
        }
        if (RsccSearchGuard.isSearching()) {
            // 正在做线缆搜索（说明本判定是被搜索间接触发的）：不嵌套，直接按「没问题」返回
            return Report.CLEAR;
        }
        final RsccWireLinkSearch.LinkWalk walk = RsccWireLinkSearch.searchChamberLink(level, busPos);
        if (!walk.linked()) {
            // 一台执行舱都够不到：本来就没有归属可谈，总线只是一条普通总线（界面也不显示延长条）
            return Report.CLEAR;
        }
        final List<BlockPos> reachable = walk.reachable();
        final List<BlockPos> listed = reachable.size() <= MAX_LISTED_CHAMBERS
            ? reachable : List.copyOf(reachable.subList(0, MAX_LISTED_CHAMBERS));
        return new Report(walk.ambiguous(), !walk.exhaustive(), reachable.size(),
            walk.cluster(), listed);
    }

    // ------------------------------------------------------------------
    // 「曾经连到执行舱 → 现在一台都够不到」的边沿裁决与提示出口
    // ------------------------------------------------------------------

    /**
     * 全部巴士提示共用的语言键前缀（与 {@code report/RsccBusDisabledBanner} 同一段：
     * 两处提示的键集合不会分裂，界面 / 横幅 / 日志说的是同一批说法）。
     */
    private static final String LANG = "gui.rs_create_compat.bus_interference.";

    /** 「延长型已断开」横幅的广播半径（格）与平方值：与既有「已停用」横幅同一量级。 */
    private static final int LOST_RADIUS = 96;
    private static final int LOST_RADIUS_SQ = LOST_RADIUS * LOST_RADIUS;

    /** 行色：标题暖橙 / 主体浅灰 / 原因淡红 / 建议淡蓝（与既有横幅同一套配色习惯）。 */
    private static final int COLOR_TITLE = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_REASON = 0xFFFF8A8A;
    private static final int COLOR_HINT = 0xFF9EC7FF;

    /**
     * 「曾经连到执行舱 → 现在<b>一台都够不到</b>」的<b>边沿检测器</b>（输出总线 / 输入总线各持一份）。
     *
     * <h2>为什么需要它（缺口 2：{@code reachable=0} 原本完全静默）</h2>
     * <p>玩家用扳手断开接缝 / 用分隔框架把线缆隔开，本来是「让总线归属唯一」的手段；
     * 一旦断错地方（断在总线与执行舱之间），线缆就<b>一台执行舱都够不到</b>。此时
     * {@link #inspect} 按既有语义只能返回 {@link Report#CLEAR} —— 这恰好也是「这条总线本来就不是
     * 给执行仓用的」的同一个返回值，于是玩家<b>什么提示都收不到</b>，只看到总线莫名其妙退回普通界面
     * （实证日志：{@code reason=no_chamber reachable=0}）。判定的原始语义不能动（否则任何接了大线网
     * 却还没接执行舱的总线都会亮红条），所以这里补的不是判定，而是<b>「这台总线自己曾经连到过执行舱」
     * 这个历史事实</b>。</p>
     *
     * <h2>不误报（唯一的判据 = 历史事实）</h2>
     * <p>只有「本实例<b>亲眼见过</b>可达链数 ≥ 1」之后，才可能进入提示；一台从落地起就没接过执行舱的
     * 总线<b>永远不会</b>提示（{@code everLinked} 恒为 false）。首个解析只记基线：载入世界 / 放置方块
     * 那一刻本来就够不到执行舱的总线，不会被当成「刚断开」而弹一次。</p>
     *
     * <h2>不重复弹（严格的边沿语义）</h2>
     * <p>提示只在 {@code ≥1 → 0} 的<b>那一次翻转</b>上报一次（{@code announced} 立刻置位）；
     * 状态持续为 0 时一个包都不发。恢复为可达（{@code announced} 复位）之后若再次掉到 0，
     * 会再提示一次 —— 「进入提示一次、恢复消失、再断再提示一次」。</p>
     *
     * <p><b>为什么这个状态不落盘</b>：落盘就会让「上次会话断的、这次进服还是断的」在每次登录时重弹一次，
     * 正是用户最反感的重复提示；而「这次会话里它工作过、现在掉下来了」这条历史事实本来就只有本次会话知道。
     * 重进世界后仍处于断开状态的总线，界面与行为与断开时完全一致，玩家已经在上一次被提示过了。</p>
     */
    public static final class LinkLostLatch {
        /** 是否已经吃过第一个样本（首个样本只作基线，不算「翻转」）。 */
        private boolean baseline;
        /** 本实例是否<b>亲眼见过</b>可达链数 ≥ 1（= 「曾经有归属 / 曾经工作过」的历史事实）。 */
        private boolean everLinked;
        /** 当前这轮「够不到」是否已经提示过（去重位：状态不变时不再提示）。 */
        private boolean announced;

        /**
         * 喂入一次归属判定的结果。
         *
         * @param reachableCount 本次判定算出的可达<b>链（逻辑执行仓）数</b>（0 = 一台都够不到）。
         *                       本闸门只关心「0 还是 ≥1」这一条边沿，因此按链去重
         *                       （{@code RsccWireLinkSearch} 的 {@code collapseByChain}）不会改变它的结论
         * @return {@code true} = 此刻<b>正好</b>发生「曾经连到执行舱 → 现在够不到」的翻转，
         *     调用方据此发一次提示；其余一切情况都返回 {@code false}（一个包都不该发）
         */
        public boolean onResolve(final int reachableCount) {
            final boolean linked = reachableCount >= 1;
            if (!baseline) {
                baseline = true;
                everLinked = linked;
                announced = !linked; // 首个样本就是「够不到」：视为已经提示过（普通总线载入时不弹）
                return false;
            }
            if (linked) {
                everLinked = true;
                announced = false; // 恢复：下一次再断可以再提示一次
                return false;
            }
            if (!everLinked || announced) {
                return false; // 从没连过（不误报）/ 这一轮已经提示过（不重复弹）
            }
            announced = true;
            return true;
        }
    }

    /**
     * 发一条「总线延长型已断开（线缆够不到执行舱）」的四行横幅 —— 与既有「已停用（归属未确定）」
     * 横幅共用同一套完成横幅系统、同一段语言键前缀与同一套行色，玩家看到的是同一种报告。
     *
     * <p><b>节流归调用方</b>：本方法每次都发；「同一状态只提示一次」由 {@link LinkLostLatch} 保证。</p>
     *
     * @param exporter true = 输出总线，false = 输入总线（只影响第二行的称呼，与既有横幅共用同一个键）
     */
    public static void notifyLinkLost(final ServerLevel level, final BlockPos busPos, final boolean exporter) {
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(4);
        rows.add(CompletionBannerPayload.Row.text(COLOR_TITLE,
            CompletionBannerPayload.localized(LANG + "lost.title")));
        rows.add(CompletionBannerPayload.Row.text(COLOR_TEXT,
            CompletionBannerPayload.localized(
                LANG + (exporter ? "banner.toast.exporter" : "banner.toast.importer"),
                busPos.getX(), busPos.getY(), busPos.getZ())));
        rows.add(CompletionBannerPayload.Row.text(COLOR_REASON,
            CompletionBannerPayload.localized(LANG + "lost.reason")));
        rows.add(CompletionBannerPayload.Row.text(COLOR_HINT,
            CompletionBannerPayload.localized(LANG + "lost.hint")));
        CompatCompletionSender.sendToNearby(level, busPos, LOST_RADIUS_SQ, rows);
        // 诊断（只读）：记录一次「延长型已断开」横幅，便于事后核对「这次掉线到底有没有提示过」
        RsccDiag.recordBanner(exporter ? "bus_link_lost_exporter" : "bus_link_lost_importer",
            busPos.getX() + "," + busPos.getY() + "," + busPos.getZ());
    }
}
