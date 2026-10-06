package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.simibubi.create.content.schematics.SchematicExport;
import cretae.cookiewyq.rs_create_compat.client.CompatCompletionToast;
import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import cretae.cookiewyq.rs_create_compat.support.RsccCableCuts;
import cretae.cookiewyq.rs_create_compat.support.RsccSheaths;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 蓝图导出时的「本区有一部分内容不会跟着蓝图走」提示（缺口 1 的最小可行修复）。
 *
 * <h2>缺口是什么</h2>
 * <p>玩家用「分隔框架」+「扳手断开」把线缆隔开，好让执行仓总线的归属唯一 —— 但这两样东西都只存在本模组的
 * {@code SavedData}（{@code rscc_sheaths.dat} / {@code rscc_cable_cuts.dat}）里，<b>不占方块</b>；
 * 而 Create 的蓝图只写 {@code StructureTemplate}（方块 + 方块实体）。于是「导出 → 粘贴」之后框架与断开
 * <b>全部丢失</b>：两条线缆重新连通，总线归属变成「≥2 台可达」而被停用。玩家看不到任何解释，
 * 只觉得「我明明放了框架，它却说不行」。</p>
 *
 * <h2>为什么不做「把记录一并写进蓝图」（方案 a）</h2>
 * <ol>
 *     <li><b>Create 没有给模组任何扩展点</b>：一枚蓝图物品只是「文件名 + 锚点 + 旋转 / 镜像」
 *     （{@code SchematicItem} 的 data components），真正的实体是一份 {@code .nbt} 文件；
 *     {@code StructureTemplate#save/load} 没有任何挂载未知数据的钩子，也没有
 *     「这张蓝图已经打印完了」的回调。想随蓝图走，只能往 Create 拥有的那份 .nbt 里塞自定义键
 *     （{@code StructureTemplate#load} 确实会忽略未知键，所以塞得进去），但那是<b>往别人的文件格式里
 *     打补丁</b>：这份文件同样会被蓝图桌上传 / 被其它结构工具读写，任何一次经过
 *     {@code StructureTemplate#save} 的重新序列化都会把我们的键丢掉 —— 也就是说，
 *     「带上」这件事本身没有可长期依赖的保证；</li>
 *     <li><b>就算带上了，也没有安全的落点</b>：{@code SchematicPrinter#loadSchematic} 在
 *     <b>一块方块都还没放</b>的时候就执行（此时就把断开记录写下去，等于即使打印被玩家取消，
 *     也会切断目标区域里现存的线缆）；打印本身是逐格推进的（{@code advanceCurrentPos} 一次一格），
 *     Create 不提供「本次打印完成」的出口。写回的位置由坐标 + 旋转 / 镜像换算而来，
 *     一旦有偏差，就是把<b>错误的断开记录永久写进玩家存档</b> —— 比今天的「记录丢失」更难发现、
 *     更难恢复。</li>
 * </ol>
 * <p>因此这里选<b>方案 b</b>：<b>在导出那一刻，按边沿把「这一次会丢掉多少处」当面算清并告诉玩家</b>
 * （数量取自权威真值，见 {@link RsccSheaths#countIn} / {@link RsccCableCuts#countCutsIn}），
 * 让玩家知道粘贴后要自己补回来。它不改动任何存档、不碰任何方块，因此零风险。</p>
 *
 * <h2>挂点与「只提示一次」</h2>
 * <p>挂点是 Create 导出蓝图的<b>唯一出口</b> {@code SchematicExport#saveSchematic}：无论玩家是
 * 「保存到文件」还是「立即转换」（后者会在服务端再导出一次），客户端都会先走这里一次。三条收口保证
 * 不会重复、也不会误报：</p>
 * <ol>
 *     <li><b>导出失败不提示</b>（返回 {@code null}）：那时 Create 自己已经报了失败原因；</li>
 *     <li><b>只在客户端那次调用提示</b>（{@code level.isClientSide()}）：单人存档里「立即转换」会让
 *     同一份代码在集成服务端再跑一遍，不挡住就会同一次操作弹两条；</li>
 *     <li><b>两种记录都为 0 时一个字都不弹</b>：普通蓝图（本区没有框架 / 没有断开）完全零打扰。</li>
 * </ol>
 * <p>导出是玩家的一次离散动作，因此「一次动作 = 至多一条提示」，不存在反复弹的可能。</p>
 */
@Mixin(SchematicExport.class)
public abstract class SchematicExportNoticeMixin {
    /** 语言键前缀（横幅文本按语言键下发，客户端按玩家语言解析，与既有完成横幅同一套做法）。 */
    private static final String RSCC_NOTICE_LANG = "gui.rs_create_compat.schematic_notice.";

    /** 行色：标题暖橙 / 事实浅灰 / 建议淡蓝（与总线横幅同一套配色习惯）。 */
    private static final int RSCC_NOTICE_COLOR_TITLE = 0xFFFFAA00;
    private static final int RSCC_NOTICE_COLOR_TEXT = 0xFFE6E6E6;
    private static final int RSCC_NOTICE_COLOR_HINT = 0xFF9EC7FF;

    /**
     * 导出成功后（返回值非 {@code null}）当面提示「本区有多少处框架 / 断开不会随蓝图复制」。
     *
     * @param first 选择区第一个角（含）
     * @param second 选择区第二个角（含）
     */
    @Inject(method = "saveSchematic", at = @At("RETURN"))
    private static void rscc$notifyCutsAndSheathsNotCopied(
        final Path dir, final String fileName, final boolean overwrite, final Level level,
        final BlockPos first, final BlockPos second,
        final CallbackInfoReturnable<SchematicExport.SchematicExportResult> cir) {
        if (cir.getReturnValue() == null || level == null || first == null || second == null) {
            return; // 导出失败 / 没有选择区：Create 自己已经报过失败原因，这里不再补话
        }
        if (!level.isClientSide()) {
            return; // 单人存档里服务端那次「立即转换」导出：同一次玩家操作只提示一次
        }
        final BoundingBox box = BoundingBox.fromCorners(first, second);
        final int sheaths = RsccSheaths.countIn(level, box);
        final int cuts = RsccCableCuts.countCutsIn(level, box);
        if (sheaths <= 0 && cuts <= 0) {
            return; // 这张蓝图没有会丢的东西：一个字都不弹（普通蓝图零打扰）
        }
        final List<CompletionBannerPayload.Row> rows = new ArrayList<>(4);
        rows.add(CompletionBannerPayload.Row.text(RSCC_NOTICE_COLOR_TITLE,
            CompletionBannerPayload.localized(RSCC_NOTICE_LANG + "title")));
        if (sheaths > 0) {
            rows.add(CompletionBannerPayload.Row.text(RSCC_NOTICE_COLOR_TEXT,
                CompletionBannerPayload.localized(RSCC_NOTICE_LANG + "sheaths", sheaths)));
        }
        if (cuts > 0) {
            rows.add(CompletionBannerPayload.Row.text(RSCC_NOTICE_COLOR_TEXT,
                CompletionBannerPayload.localized(RSCC_NOTICE_LANG + "cuts", cuts)));
        }
        rows.add(CompletionBannerPayload.Row.text(RSCC_NOTICE_COLOR_HINT,
            CompletionBannerPayload.localized(RSCC_NOTICE_LANG + "hint")));
        // 直接借既有完成横幅的 Toast 呈现（与其它横幅长得一样，玩家不需要学一套新提示）
        Minecraft.getInstance().getToasts().addToast(new CompatCompletionToast(rows));
    }
}
