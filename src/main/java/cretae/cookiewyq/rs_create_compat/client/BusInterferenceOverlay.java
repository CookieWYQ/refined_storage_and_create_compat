package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * 「延长型总线归属未确定（已停用）」的半透明叠加层（纯客户端表现）。
 *
 * <h2>它做什么</h2>
 * 按下总线界面里的「显示可达区域」按钮后，把<b>这条总线的延长段与它够得到的执行舱</b>在世界里标出来：
 * <ul>
 *     <li><b>红色</b>（较重）= 线缆<b>可达的执行舱</b>（可能有多台）—— 服务端判定给出的 {@code chambers}，
 *     看一眼就知道「是哪几台在抢这条总线」；</li>
 *     <li><b>黄色</b>（较轻）= 这条总线的「延长段」本体（线缆簇，含总线自身）——
 *     让玩家看出该往哪里加分隔框架 / 该把哪几台分开。</li>
 * </ul>
 *
 * <h2>为什么这样实现</h2>
 * <ul>
 *     <li><b>判定完全服务端权威</b>：本类只缓存 {@code SyncBusInterferencePacket} 发来的坐标，
 *     客户端不做任何推断（也不读世界去猜），因此界面显示与真实判定必然一致；</li>
 *     <li><b>渲染复用既有实现</b>：调用 {@link CableCutPreviewOverlay#drawBoxes} 那一套
 *     （{@code RenderType#debugFilledBox()} 半透明立方体 + {@code LevelRenderer#renderLineBox} 描边），
 *     不新增任何 GUI / 世界贴图；</li>
 *     <li><b>只在按下按钮后渲染</b>：{@code shown} 默认 false，只有界面里的按钮会打开它；
 *     <b>它是「手动切换」的全局开关（本轮修正，用户第 ③ 条）</b> —— 打开后<b>不随界面关闭而消失</b>，
 *     一直标到玩家再按一次关掉为止（用户原话：「那个显示只能短暂显示，一关 GUI 就取消显示 ——
 *     你这个也没任何用。要么持续显示一段时间，要么手动切换状态」）。</li>
 * </ul>
 *
 * <h2>状态归属（本轮修正）</h2>
 * 本类持有的是<b>唯一一份</b>客户端镜像。{@code shown} 是<b>全局的手动开关</b>：
 * <ul>
 *     <li>不随「换了一个容器 id」而复位 —— 关掉界面再打开同一条总线，按钮仍是「开」的状态；</li>
 *     <li>不随「界面被关闭」而复位 —— 关掉界面后叠加层继续标在世界里，直到玩家手动关掉，
 *     或那条总线不再处于「归属未确定」状态（{@link #apply} 里收口，避免标一层已失效的坐标）；</li>
 *     <li>坐标始终来自服务端 {@code SyncBusInterferencePacket}，客户端不推断。</li>
 * </ul>
 *
 * <p><b>与既有 K 键（伪装显形 {@code CamouflageKeybinds}）的关系</b>：两者<b>完全无关</b> ——
 * K 键是「伪装套壳」的全局显形开关（作用于伪装框架 / 套壳），本开关只作用于
 * 「延长型总线归属未确定」这一条提示，作用域是「当前这条总线的线缆簇 + 可达执行舱」。
 * 因此不存在两套语义打架：一个是物品伪装，一个是线缆归属。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class BusInterferenceOverlay {
    /** 可达执行舱（红）的不透明度。 */
    private static final float CHAMBER_ALPHA = 0.32F;
    private static final float[] CHAMBER_COLOR = {0.95F, 0.20F, 0.20F};
    /** 延长段（黄）的不透明度：更淡，避免挡住方块本身的观感。 */
    private static final float CLUSTER_ALPHA = 0.14F;
    private static final float[] CLUSTER_COLOR = {1.00F, 0.82F, 0.15F};
    /** 单帧最多渲染的格子数（防极端情况下一次画上千个立方体拖慢帧率）。 */
    private static final int MAX_RENDER_CELLS = 200;

    /** 当前镜像所属的容器 id（-1 = 尚未收到任何同步）。 */
    private static volatile int containerId = -1;
    private static volatile boolean disabled;
    private static volatile boolean truncated;
    private static volatile int reachableCount;
    private static volatile List<BlockPos> cluster = List.of();
    private static volatile List<BlockPos> chambers = List.of();
    /** 叠加层是否处于「玩家按下了按钮」的显示状态（<b>全局手动开关</b>：见类注释的「状态归属」）。 */
    private static volatile boolean shown;

    private BusInterferenceOverlay() {
    }

    // ==================== 数据入口（S2C 同步） ====================

    /** 整份替换客户端镜像（由 {@code SyncBusInterferencePacket} 的处理线程调用）。 */
    public static void apply(final int newContainerId, final boolean newDisabled, final boolean newTruncated,
                             final int newReachableCount, final List<BlockPos> newCluster,
                             final List<BlockPos> newChambers) {
        // 刻意<b>不</b>在换容器 id 时复位 shown：玩家关掉界面再打开同一条总线时，
        // 「显示」应当还是开着的（用户第 ③ 条：手动切换，不要一关 GUI 就没了）。
        containerId = newContainerId;
        disabled = newDisabled;
        truncated = newTruncated;
        reachableCount = newReachableCount;
        cluster = newCluster == null ? List.of() : List.copyOf(newCluster);
        chambers = newChambers == null ? List.of() : List.copyOf(newChambers);
        if (!newDisabled) {
            shown = false; // 停用状态解除：叠加层随之关闭，不留残影
        }
    }

    // ==================== 供界面（类别条控件）查询 ====================

    /** 该容器对应的总线当前是否因「归属未确定」而停用。 */
    public static boolean isDisabled(final int queryContainerId) {
        return queryContainerId >= 0 && queryContainerId == containerId && disabled;
    }

    /** 该容器的探查是否未能穷尽（可达执行舱可能不全）。 */
    public static boolean isTruncated(final int queryContainerId) {
        return queryContainerId >= 0 && queryContainerId == containerId && truncated;
    }

    /** 该容器对应的总线线缆一共可达几台执行舱（不属于该容器时 0）。 */
    public static int reachableCount(final int queryContainerId) {
        return queryContainerId >= 0 && queryContainerId == containerId ? reachableCount : 0;
    }

    /** 该容器的可达执行舱坐标（不属于该容器时为空表）。 */
    public static List<BlockPos> chambers(final int queryContainerId) {
        return queryContainerId >= 0 && queryContainerId == containerId ? chambers : List.of();
    }

    /** 该容器的线缆簇（不属于该容器时为空表）。 */
    public static List<BlockPos> cluster(final int queryContainerId) {
        return queryContainerId >= 0 && queryContainerId == containerId ? cluster : List.of();
    }

    /** 叠加层此刻是否显示（供按钮文案 / 高亮使用）。 */
    public static boolean isShown(final int queryContainerId) {
        return queryContainerId >= 0 && queryContainerId == containerId && shown;
    }

    /** 按钮：切换叠加层显隐（没处于停用状态时不允许打开，避免「按了没反应」）。 */
    public static void toggle(final int queryContainerId) {
        if (!isDisabled(queryContainerId)) {
            return;
        }
        shown = !shown;
    }

    // ==================== 渲染 ====================

    /** 世界渲染阶段（半透明方块之后）画叠加层；只在「按钮打开 + 当前界面仍是这条总线」时。 */
    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!shown || !disabled) {
            return;
        }
        // <b>不再「界面一关就自动收口」</b>（用户第 ③ 条：那个显示一关 GUI 就取消，没有任何用）。
        // 现在它是手动开关：界面开着或关着都继续标，直到玩家再按一次关掉；
        // 坐标仍是上一次服务端同步下来的那份（服务端权威，客户端不推断）。
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        // 内容按服务端下发的坐标原样标记：红 = 可达的执行舱，黄 = 本总线的延长段
        CableCutPreviewOverlay.drawBoxes(event.getPoseStack(), minecraft.renderBuffers().bufferSource(),
            event.getCamera().getPosition(), capped(chambers), CableCutPreviewOverlay.unitShape(),
            CHAMBER_COLOR, CHAMBER_ALPHA);
        CableCutPreviewOverlay.drawBoxes(event.getPoseStack(), minecraft.renderBuffers().bufferSource(),
            event.getCamera().getPosition(), capped(cluster), CableCutPreviewOverlay.unitShape(),
            CLUSTER_COLOR, CLUSTER_ALPHA);
    }

    /** 裁剪到单帧上限（只取前 N 个，避免一次画上千个立方体）。 */
    private static List<BlockPos> capped(final List<BlockPos> cells) {
        return cells.size() <= MAX_RENDER_CELLS ? cells : cells.subList(0, MAX_RENDER_CELLS);
    }
}
