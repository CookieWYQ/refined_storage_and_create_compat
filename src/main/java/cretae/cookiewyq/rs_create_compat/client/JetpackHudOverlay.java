package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.ElectricJetpackItem;
import cretae.cookiewyq.rs_create_compat.support.JetpackHudState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 电动飞行背包的 <b>HUD</b>：屏幕左上角一条自绘能量条 + 「当前 / 总 FE」+ 充能状态。
 *
 * <h2>画的是什么</h2>
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ [████████░░░░] 2,000,000 / 50,000,000 FE │
 * │ 范围内 · 充电中（仅在有状态时出现）          │
 * │ 剩余可飞约 208 分钟（低于阈值时变红）        │
 * └──────────────────────────────────────┘
 * </pre>
 * <ul>
 *     <li><b>能量条</b>：{@link GuiGraphics#fill} 纯色矩形（不需要任何贴图），底色深、填充色青蓝
 *     （与物品栏里那条「耐久样式」能量条 {@code ElectricJetpackItem#getBarColor()} 同色，
 *     两处看到的是同一个电量）；</li>
 *     <li><b>当前 / 总 FE</b>：{@code 2,000,000 / 50,000,000 FE}，<b>精确千分位</b>，由
 *     {@link ElectricJetpackItem#formatFeExact(int)} 统一给出（Tooltip 用同一函数，不会两处不一致）；
 *     第 109 轮用户口径：「要能显示准确数字，不要只显示 2M」——旧的 {@code %.1fM} 缩写已删除；</li>
 *     <li><b>剩余可飞分钟</b>：{@link ElectricJetpackItem#remainingFlightMinutes(int)}（与 tooltip /
 *     低电副标题同一个函数），低于「剩余可飞分钟」阈值时变红——低电阈值就是按分钟定义的，
 *     把分钟数直接画出来，玩家一眼能看懂为什么提示 / 为什么不提示；</li>
 *     <li><b>状态</b>：服务端下发的两个<b>互不相同</b>的标记 ——
 *     在范围内（几何判定）与正在被充能（本 tick 能量变大）。
 *     两个都为真时显示「范围内 · 充电中」，只有范围内显示「范围内」，都没有就不画这一行。</li>
 * </ul>
 *
 * <h2>数据来源</h2>
 * <p>只读 {@link JetpackHudState}（{@code network/JetpackStatusPacket} 的客户端镜像，服务端每 5 tick
 * 刷新）：<b>不</b>在这里读物品 NBT、<b>不</b>查方块实体、<b>不</b>推算电量。
 * 快照过期（掉线 / 切维度间隙）时整条 HUD 自动隐藏（见 {@link JetpackHudState#STALE_MS}），
 * 不会留一个冻结的假面板。</p>
 *
 * <h2>什么时候不画</h2>
 * <ul>
 *     <li>{@code F1} 隐藏界面时（{@code Minecraft#options#hideGui}）；</li>
 *     <li>没有进入世界 / 没有玩家时；</li>
 *     <li>打开了任意界面（{@code Minecraft#screen != null}）—— 避免 HUD 从半透明的背包 / 设置界面
 *     后面透出来，观感上更干净；</li>
 *     <li>服务端说「没装备」时（{@code equipped == false}）。</li>
 * </ul>
 *
 * <h2>线程 / 专用服务端</h2>
 * <p>本类只存在于客户端（{@code @EventBusSubscriber(value = Dist.CLIENT)}），专用服务端不会加载它，
 * 因此可以自由使用 {@code net.minecraft.client.*}。绘制发生在渲染线程，读取的是不可变快照。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class JetpackHudOverlay {
    /** 「范围内 · 充电中」（同时满足几何在范围内 + 本 tick 能量真的变大）。 */
    public static final String LANG_HUD_CHARGING = "item.rs_create_compat.electric_jetpack.hud.charging";
    /** 「范围内」（只在充电器的几何范围内，尚未发生能量转移）。 */
    public static final String LANG_HUD_IN_RANGE = "item.rs_create_compat.electric_jetpack.hud.in_range";
    /** 第 109 轮：「剩余可飞约 %s 分钟」（低电阈值按分钟定义，HUD 直接把分钟数画出来）。 */
    public static final String LANG_HUD_FLIGHT_LEFT =
        "item.rs_create_compat.electric_jetpack.hud.flight_left";

    /** 布局常量（左上角；全部按「像素」写死，不随 GUI 缩放漂移）。 */
    private static final int BAR_X = 8;
    private static final int BAR_Y = 8;
    private static final int BAR_WIDTH = 96;
    private static final int BAR_HEIGHT = 8;
    /** 能量条与右侧「当前 / 总 FE」文本的间距。 */
    private static final int TEXT_GAP = 5;
    /** 面板内边距。 */
    private static final int PANEL_PAD = 3;

    private static final int COLOR_PANEL = 0xB4101010;
    private static final int COLOR_BORDER = 0xFF3A3A3A;
    private static final int COLOR_BAR_BACKGROUND = 0xFF0E1A1F;
    private static final int COLOR_BAR_FILL = 0xFF4FC3F7;
    private static final int COLOR_TEXT = 0xFFE8F6FF;
    private static final int COLOR_CHARGING = 0xFF7BE37B;
    private static final int COLOR_IN_RANGE = 0xFF9AD6FF;
    /** 剩余可飞分钟（正常）。 */
    private static final int COLOR_FLIGHT_LEFT = 0xFFA8C7D8;
    /** 剩余可飞分钟（低于低电阈值：与低电 title 呼应）。 */
    private static final int COLOR_LOW_POWER = 0xFFFF7B72;

    private JetpackHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        final JetpackHudState.Snapshot snapshot = JetpackHudState.snapshot();
        if (!snapshot.equipped()) {
            return;
        }
        draw(event.getGuiGraphics(), minecraft.font, snapshot);
    }

    /**
     * 断开连接时把镜像清空。
     * <p>服务端的快照心跳会在我「离开这个世界」的那一刻停止；如果不清，切到另一个服务器 / 单人存档的
     * 加载界面上会短暂残留上一个世界的电量条（虽然 {@link JetpackHudState#STALE_MS} 会兜底隐藏，
     * 但那要等 5 秒）。这里主动清一次，语义上也更准确：镜像只属于「当前这个连接」。</p>
     */
    @SubscribeEvent
    public static void onLoggingOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        JetpackHudState.clear();
    }

    private static void draw(final GuiGraphics graphics, final Font font,
                             final JetpackHudState.Snapshot snapshot) {
        // 第 109 轮：精确千分位（不再 2.0M 缩写）；剩余可飞分钟与 tooltip / 低电副标题同一函数。
        final String energyText = ElectricJetpackItem.formatFeExact(snapshot.energy())
            + " / " + ElectricJetpackItem.formatFeExact(snapshot.capacity()) + " FE";
        final int flightMinutes = ElectricJetpackItem.remainingFlightMinutes(snapshot.energy());
        final Component flightLeft = Component.translatable(LANG_HUD_FLIGHT_LEFT, flightMinutes);
        final boolean lowPower = snapshot.energy() < ElectricJetpackItem.lowPowerThresholdFe();

        // 状态行：两个标记是两件事（见类注释），先判「正在充能」再判「在范围内」。
        final String statusKey = snapshot.charging() ? LANG_HUD_CHARGING
            : snapshot.inRange() ? LANG_HUD_IN_RANGE : null;
        final Component status = statusKey == null ? null : Component.translatable(statusKey);
        final int statusWidth = status == null ? 0 : font.width(status);
        final int statusColor = snapshot.charging() ? COLOR_CHARGING : COLOR_IN_RANGE;

        final int energyTextX = BAR_X + BAR_WIDTH + TEXT_GAP;
        // 面板宽度按三行里最宽的一行算（电量文本 / 状态行 / 剩余分钟行）。
        final int contentRight = Math.max(energyTextX + font.width(energyText),
            Math.max(BAR_X + statusWidth, BAR_X + font.width(flightLeft)));
        // 面板高度：能量条一行 + （状态行）+ 剩余分钟行。
        final int contentBottom = BAR_Y + BAR_HEIGHT
            + (status == null ? 0 : 3 + font.lineHeight)
            + 3 + font.lineHeight;

        // 面板背景 + 1px 边框（纯色 fill，不依赖任何贴图）
        graphics.fill(BAR_X - PANEL_PAD, BAR_Y - PANEL_PAD,
            contentRight + PANEL_PAD, contentBottom + PANEL_PAD - 1, COLOR_PANEL);
        graphics.fill(BAR_X - PANEL_PAD, BAR_Y - PANEL_PAD,
            contentRight + PANEL_PAD, BAR_Y - PANEL_PAD + 1, COLOR_BORDER);
        graphics.fill(BAR_X - PANEL_PAD, contentBottom + PANEL_PAD - 2,
            contentRight + PANEL_PAD, contentBottom + PANEL_PAD - 1, COLOR_BORDER);
        graphics.fill(BAR_X - PANEL_PAD, BAR_Y - PANEL_PAD,
            BAR_X - PANEL_PAD + 1, contentBottom + PANEL_PAD - 1, COLOR_BORDER);
        graphics.fill(contentRight + PANEL_PAD - 1, BAR_Y - PANEL_PAD,
            contentRight + PANEL_PAD, contentBottom + PANEL_PAD - 1, COLOR_BORDER);

        // 能量条：底色 + 按比例填充
        graphics.fill(BAR_X, BAR_Y, BAR_X + BAR_WIDTH, BAR_Y + BAR_HEIGHT, COLOR_BAR_BACKGROUND);
        final int filled = (int) Math.round(BAR_WIDTH * snapshot.fillRatio());
        if (filled > 0) {
            graphics.fill(BAR_X, BAR_Y, BAR_X + filled, BAR_Y + BAR_HEIGHT, COLOR_BAR_FILL);
        }

        // 文本：电量数值与能量条同一行（垂直居中于 8px 条内），状态行 / 剩余分钟行在下方
        graphics.drawString(font, energyText, energyTextX, BAR_Y - 1, COLOR_TEXT, true);
        int textY = BAR_Y + BAR_HEIGHT + 3;
        if (status != null) {
            graphics.drawString(font, status, BAR_X, textY, statusColor, true);
            textY += font.lineHeight + 3;
        }
        graphics.drawString(font, flightLeft, BAR_X, textY,
            lowPower ? COLOR_LOW_POWER : COLOR_FLIGHT_LEFT, true);
    }
}
