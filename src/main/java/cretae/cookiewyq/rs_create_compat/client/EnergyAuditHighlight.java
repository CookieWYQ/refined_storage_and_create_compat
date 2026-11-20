package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 「能耗监视」选中某台方块后，在世界里画的那个框（<b>纯客户端渲染，服务端零渲染代码</b>）。
 *
 * <h2>框从哪来（位置只来自 S2C，客户端不查、不读）</h2>
 * <p>本类<b>不做任何查询</b>：不查方块实体、不查能力、不扫世界、不算耗电。它只接受
 * {@link #arm} 喂进来的「维度 + 坐标 + TTL」，把那个坐标交给 {@link AreaOutline}（与范围充电器 /
 * 区域选定器<b>同一套</b>橙 {@code 0xFF9F1C} + 1/16 线宽 + 棋盘面，观感完全一致）。
 * 坐标的唯一来源是服务端快照里<b>校验通过</b>的 {@code selectedDimension / selectedPos}
 * （见 {@code support/NetworkEnergyAudit#snapshot}）。因此客户端连「高亮哪个方块」都不能自己决定。</p>
 *
 * <h2>为什么每 tick 重喂（而不是喂一次等它自己淡出）</h2>
 * <p>{@code Outliner#chaseAABB} 的语义是「一直显示到不再喂」：每客户端 tick 必须重新喂一次，
 * 否则下一 tick 就被 Outliner 自己清掉。这与 {@link ChargerTargetHighlight} 对「本 tick 充了谁」的
 * 处理完全同构（同一个渲染器、同一套生命周期），不新写任何渲染事件。</p>
 *
 * <h2>TTL 与「取消选中」</h2>
 * <p>快照每秒一条，每条都会把 TTL 续到 {@code Config.energyAuditHighlightTtlTicks}（默认 60 tick = 3 秒）。
 * <b>关界面 / 返回终端时不再直接撤框</b>（第 135 轮）：能耗页是整屏 GUI，界面开着看不到世界，
 * 旧行为「选中 → 关界面 → 立刻 clear()」等于高亮永远用不上；现在关界面调 {@link #linger()}，
 * 把到期时刻续到 {@code Config.energyAuditHighlightLingerTicks}（默认 1200 tick = 60 秒），
 * 玩家可以走出去实地看是哪台方块，到期自动灭（掉线 / 换维度同样由 TTL 收尾）。
 * 玩家点「清除高亮」时服务端把回显的选中置空 ⇒ 下一份快照 {@link #clear()} ⇒ 框<b>立刻</b>消失
 * （不靠 TTL 自己灭）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class EnergyAuditHighlight {
    /** Outliner 槽位 key（带模组前缀，避免与 Create 自己的 slot 撞车）。 */
    private static final Object SLOT = AreaOutline.slot("energy_audit");
    /** 框比方块略外扩一点点：与 {@link ChargerTargetHighlight} 同一口径（1/500 格，防 z-fighting）。 */
    private static final double INFLATE = 0.002D;

    /** 被高亮的方块所在维度（null = 没有高亮）。 */
    @Nullable
    private static ResourceLocation dimension;
    /** 被高亮的方块坐标（null = 没有高亮）。 */
    @Nullable
    private static BlockPos pos;
    /** 到期时刻（客户端世界 gameTime；只在 {@link #dimension} 非空时有意义）。 */
    private static long expiresAt;

    private EnergyAuditHighlight() {
    }

    /**
     * 武装 / 续期一个高亮（<b>只由 S2C 快照处理体调用</b>）。
     *
     * @param dimension 方块所在维度（来自服务端快照）
     * @param pos       方块坐标（来自服务端快照）
     */
    public static void arm(final ResourceLocation dimension, final BlockPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        EnergyAuditHighlight.dimension = dimension;
        EnergyAuditHighlight.pos = pos;
        EnergyAuditHighlight.expiresAt = (level == null ? 0L : level.getGameTime())
            + Math.max(5, Config.energyAuditHighlightTtlTicks);
    }

    /** 立刻撤掉高亮（取消选中 / 快照里没有选中 / 关界面）。重复调用是空操作。 */
    public static void clear() {
        dimension = null;
        pos = null;
        expiresAt = 0L;
        AreaOutline.remove(SLOT);
    }

    /**
     * 关界面 / 返回终端时调用：<b>不撤框</b>，只把寿命续到「现在 + 关界面残留时长」。
     *
     * <h2>为什么必须这样（第 135 轮，用户原话）</h2>
     * <p>「<i>你想着我打开这个高亮之后，我是不是关掉这个界面，那关掉界面之后高亮是不是也就关了。
     * 所以说你这完全就用不了。</i>」—— 能耗页是整屏 GUI，界面开着的时候<b>根本看不到世界</b>，
     * 所以「选中 → 立刻关界面 → 撤框」这条旧链路等于永远看不到高亮。</p>
     * <p>现在选中回显照旧每份快照续期；关界面只做一次「续命」，玩家可以走出去实地看是哪台方块，
     * 到期（{@link Config#energyAuditHighlightLingerTicks}，默认 60 秒）自动灭，不留永久残影。
     * 要立刻撤掉就重开监视页点「清除高亮」。</p>
     */
    public static void linger() {
        if (dimension == null || pos == null) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        final long until = level.getGameTime()
            + Math.max(100, Config.energyAuditHighlightLingerTicks);
        expiresAt = Math.max(expiresAt, until);
    }

    /** 当前是否正在高亮（诊断 / 自检用）。 */
    public static boolean isActive() {
        return dimension != null && pos != null;
    }

    /** 每客户端 tick：检查是否过期 / 是否还在同一维度，然后把框重新喂给 Outliner。 */
    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        if (pos == null || dimension == null) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear(); // 退出世界：槽位随客户端 Outliner 一起没了，状态也一并清掉
            return;
        }
        if (level.getGameTime() > expiresAt) {
            clear(); // 快照断流：不留残影
            return;
        }
        if (!level.dimension().location().equals(dimension)) {
            // 选中的方块在另一个维度：这个世界里没有它的位置，不画（也绝不按坐标硬画到当前维度）
            AreaOutline.remove(SLOT);
            return;
        }
        AreaOutline.chaseBox(SLOT, new AABB(pos).inflate(INFLATE));
    }
}
