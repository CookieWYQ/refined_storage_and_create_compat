package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.api.autocrafting.status.TaskStatus;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.common.autocrafting.monitor.AutocraftingMonitorScreen;
import cretae.cookiewyq.rs_create_compat.client.AssemblyAlertsClient;
import cretae.cookiewyq.rs_create_compat.client.ShortageModeClient;
import cretae.cookiewyq.rs_create_compat.mixin.accessor.ScreenAccessor;
import cretae.cookiewyq.rs_create_compat.network.AssemblyTaskActionPacket;
import cretae.cookiewyq.rs_create_compat.network.RequestAssemblyAlertsPacket;
import cretae.cookiewyq.rs_create_compat.network.RequestShortageModePacket;
import cretae.cookiewyq.rs_create_compat.network.SetShortageModePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyMonitorBridge;
import cretae.cookiewyq.rs_create_compat.support.RsccShortagePolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「自动合成监视器」（RS 原版合成仓监视器界面）里，为自动合成任务补一排处置按钮
 * 与一行挂起原因（原版 RS 任务与序列装配任务共用）。
 *
 * <p><b>点击发生在监视器里、横幅只是展示</b>：横幅（{@code CompatCompletionToast}）不注册任何点击回调；
 * 真正的处置动作是这里的按钮 ——</p>
 * <ul>
 *     <li><b>挂起</b>：任务正在跑时出现，点它把这条任务主动让出来（{@code ACTION_SUSPEND}）——
 *     不再占用执行器 / 不阻塞后续任务，已加工中间件原地冻结，等玩家点「继续」才恢复；</li>
 *     <li><b>继续</b>：任务已被挂起时出现（与「挂起」互斥），发 {@link AssemblyTaskActionPacket}
 *     （继续动作）→ 服务端清挂起 + 重新计时；</li>
 *     <li><b>更换机器</b>：只有「执行器掉线且服务端指明了是哪一步」时才可用，请求候选后<b>复用既有的机器
 *     选择子界面</b>（{@code StepMachineSelectScreen}）弹出选择器。</li>
 * </ul>
 * <p>「挂起」与「继续」<b>互斥且共用同一槽位</b>：服务端在 RUNNING 时只给「挂起」位、SUSPENDED 时只给
 * 「继续」位，因此同一时刻只会渲染其中一个，不增加横向压力（几何推导见 {@link #rscc$layoutButtons}）。</p>
 * <p><b>不再自带「取消」</b>：RS 监视器原生就有「取消 / 取消全部」两个按钮（走
 * {@code C2SPackets.sendAutocraftingMonitorCancel} → {@code AutocraftingMonitor#cancel}），
 * 我们自己再放一个取消就是<b>第二条取消路径</b>，已于本轮删除。</p>
 *
 * <p><b>为什么注入这三个方法</b>：{@code init} / {@code render} / {@code currentTaskChanged}
 * 都<b>声明在 {@link AutocraftingMonitorScreen} 自身</b>（本工程硬规则：只能注入目标类自身声明的方法）。</p>
 *
 * <h2>本轮修的三件事</h2>
 * <ol>
 *     <li><b>挂起原因那行字挪进面板标题行右侧</b>（{@link #rscc$renderMarker}）。旧实现画在原生按钮行
 *     <b>下方</b>（相对 y=226、行底 235 &gt; 面板高 231）：文字下半截落在面板外的深色背景上，而且在
 *     「缩放后界面高 &lt; 231（玩家手动指定了较大 GUI 缩放）」时 topPos 为负 —— 面板连同这行一起被
 *     屏幕下沿裁掉，就是实机看到的「超出屏幕边界」。现在与原生标题<b>同一行</b>、右对齐到面板右内缘，
 *     并再夹进屏幕矩形，任何缩放下都不越界（推导见 {@link #rscc$renderMarker}）。</li>
 *     <li><b>按钮一次点击即生效、且不会莫名消失</b>：按钮的可见性<b>只</b>来自服务端快照里的动作位集
 *     （{@link SyncAssemblyAlertsPacket.Alert#offers}），客户端不再按 {@code reason / offlineSteps}
 *     自行推断；点击当刻把该动作记为「在途」（按钮立刻变灰 = 即时反馈）并在收到新快照前不再重复发包
 *     （{@link AssemblyAlertsClient#beginAction}）。在途状态<b>只影响可用性、绝不影响可见性</b>，
 *     所以「点一下按钮就没了」结构上不可能发生。</li>
 *     <li><b>零副作用</b>：打开 / 关闭 / 悬停 / 每帧渲染都不发任何会改服务端状态的包；全模组只有
 *     「继续」与「更换机器」两条显式点击路径会发 C2S 动作包。</li>
 * </ol>
 *
 * <p><b>tooltip 必须手动渲染</b>（本工程硬规则：GUI 不会自动渲染 tooltip）—— 在 {@code render}
 * 末尾统一处理，hover 判定与按钮绘制范围同源（都用按钮自身的矩形）。</p>
 *
 * <p><b>打开界面即拉一次快照</b>：服务端只在「内容变化」时广播告警，玩家如果不是在告警产生的那一刻
 * 开着监视器，客户端手里就没有任何快照 —— 这时按钮永远不渲染（用户实测现象）。因此 {@code init}
 * 末尾发一次 {@link RequestAssemblyAlertsPacket}，让服务端按自身权威数据补发一份（该包只读、
 * 不改任何服务端状态）。</p>
 */
@Mixin(AutocraftingMonitorScreen.class)
public abstract class AutocraftingMonitorScreenMixin {
    private static final String RSCC_LANG = "gui.rs_create_compat.assembly.monitor.";

    /** 我们的按钮与原生「取消全部」右缘之间的间距。 */
    private static final int RSCC_BUTTON_GAP = 4;
    /** 按钮文本两侧内边距（与 RS 原生按钮一致：{@code font.width(text) + 14}）。 */
    private static final int RSCC_BUTTON_PAD = 14;
    /** 面板内侧右留白（与 RS 左侧的 7px 对称，保证不压到面板边框）。 */
    private static final int RSCC_RIGHT_MARGIN = 7;
    /**
     * 挂起原因那一行的行 y（相对 topPos）：<b>与原生标题同一行</b>。
     * RS 的 {@code AbstractBaseScreen} 把 {@code titleLabelY} 固定为 7，这一行整行都空着（右侧没有
     * 任何原生控件），所以这里既不需要新造横条，也不会压到物品列表（列表从相对 y=20 起）。
     */
    private static final int RSCC_MARKER_Y = 7;
    /** 原生标题的左内边距（RS {@code AbstractBaseScreen} 的 {@code titleLabelX = 7}）。 */
    private static final int RSCC_TITLE_X = 7;
    /** 挂起原因与原生标题之间的最小间距（保证两段文字永不重叠）。 */
    private static final int RSCC_MARKER_GAP = 4;
    /** 挂起原因与面板右内缘的距离（与 RS 的 7px 左边距对称）。 */
    private static final int RSCC_MARKER_RIGHT = 7;
    /** 可用宽度小于这个值就整行不画（只留按钮 tooltip）—— 宁可少说，也绝不越界 / 压标题。 */
    private static final int RSCC_MARKER_MIN_WIDTH = 16;
    /** 文案装不下时的截断符号。 */
    private static final String RSCC_ELLIPSIS = "…";
    private static final int RSCC_COLOR_OFFLINE = 0xFFFF6666;
    private static final int RSCC_COLOR_WAITING = 0xFFFFAA00;

    // ---------- 「缺料处置」开关（用户第 4 条；本轮新增） ----------
    /**
     * 开关按钮画在<b>标题行</b>的右端：相对 y = {@value #RSCC_SHORTAGE_Y}、高 {@value #RSCC_SHORTAGE_H}。
     *
     * <p><b>为什么占这一行</b>：面板里其余横带都被占了 —— 物品列表区 {@code y 20..199}、
     * 原生按钮行 {@code y 204..224}（右端只剩 4px）、左侧是任务按钮列、右侧滚动条 x≥235。
     * 标题行右侧是唯一「整段空闲」的位置（RS 自己只在那儿画标题，见 {@code titleLabelX/Y = 7}），
     * 而且一个<b>全局策略开关</b>放在标题行也符合直觉。</p>
     */
    private static final int RSCC_SHORTAGE_Y = 4;
    private static final int RSCC_SHORTAGE_H = 14;
    /** 按钮文本两侧内边距（与 RS 原生按钮一致：{@code font.width(text) + 14}）。 */
    private static final int RSCC_SHORTAGE_PAD = 14;
    /**
     * 状态标签（挂起原因）与开关按钮之间的最小间距。
     * <p>两者同在标题行，因此状态标签的右界必须<b>让出</b>按钮那一段（见 {@link #rscc$renderMarker}）；</p>
     */
    private static final int RSCC_SHORTAGE_GAP = 4;

    /** RS 原生的「取消」按钮（声明在目标类自身，可直接 shadow 取它的行高 / 行 y）。 */
    @Shadow
    @Nullable
    private Button cancelButton;
    /** RS 原生的「取消全部」按钮（我们的按钮紧贴它的右侧排布）。 */
    @Shadow
    @Nullable
    private Button cancelAllButton;

    @Unique
    @Nullable
    private Button rscc$resumeButton;
    @Unique
    @Nullable
    private Button rscc$suspendButton;
    @Unique
    @Nullable
    private Button rscc$machineButton;
    /** 「缺料处置」开关（挂起 / 等待；<b>常驻</b>，与任务状态无关）。 */
    @Unique
    @Nullable
    private Button rscc$shortageButton;
    /** 开关按钮的宽度（= 两种档位文案里较宽的那个 + 内边距）；状态标签的右界据此让位。 */
    @Unique
    private int rscc$shortageWidth;

    /**
     * 「主按钮槽位放得下」标志：{@code 挂起}与{@code 继续}<b>互斥</b>（服务端同一时刻只给其中一位），
     * 因此两者共用同一个槽位（宽度取两者较宽的那个）→ 不增加横向压力；放不下时两个都收起。
     */
    @Unique
    private boolean rscc$primaryFits;
    /** 「更换机器」是否放得下（跟随主槽位右侧排布）。 */
    @Unique
    private boolean rscc$machineFits;

    /** 当前选中任务的告警（每次 refresh 时更新；按钮可见性与挂起原因都读它）。 */
    @Unique
    @Nullable
    private SyncAssemblyAlertsPacket.Alert rscc$alert;
    /** 上面这条告警属于哪条任务（点击时要用同一个 id，避免「刷新与点击取到不同任务」）。 */
    @Unique
    @Nullable
    private UUID rscc$taskId;

    /**
     * 界面初始化末尾：挂上两个处置按钮（此时 leftPos / topPos 与原生按钮都已就位），
     * 并主动向服务端拉一次告警快照。
     *
     * <p><b>为什么只在这里建按钮</b>：{@code Screen.init} 在窗口尺寸变化 / 从子界面返回时会被调用，
     * 每次都走一遍「clearWidgets → 建原生控件 → 我们再建两个」的固定顺序，因此控件集合永远是
     * 「一套」而不是逐帧累积；{@code render} 里只改可见性，从不重建控件。</p>
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void rscc$addAssemblyActionButtons(final CallbackInfo ci) {
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        final Font font = Minecraft.getInstance().font;
        // 行 y / 行高直接取原生「取消」按钮：同一个 y 就不会有垂直方向的重叠或错位，
        // 而那一行在 MC 允许的任何 GUI 缩放下都在屏幕内（推导见 rscc$layoutButtons）。
        final int rowY = cancelButton != null ? cancelButton.getY() : screen.getGuiTop() + 204;
        final int rowH = cancelButton != null ? cancelButton.getHeight() : 20;
        final Component resume = Component.translatable(RSCC_LANG + "resume");
        final Component suspend = Component.translatable(RSCC_LANG + "suspend");
        final Component machine = Component.translatable(RSCC_LANG + "change_machine");
        rscc$resumeButton = Button.builder(resume, button -> rscc$resume())
            .size(font.width(resume) + RSCC_BUTTON_PAD, rowH).build();
        rscc$suspendButton = Button.builder(suspend, button -> rscc$suspend())
            .size(font.width(suspend) + RSCC_BUTTON_PAD, rowH).build();
        rscc$machineButton = Button.builder(machine, button -> rscc$changeMachine())
            .size(font.width(machine) + RSCC_BUTTON_PAD, rowH).build();
        rscc$layoutButtons(screen, rowY);
        ((ScreenAccessor) (Object) this).rscc$addRenderableWidget(rscc$resumeButton);
        ((ScreenAccessor) (Object) this).rscc$addRenderableWidget(rscc$suspendButton);
        ((ScreenAccessor) (Object) this).rscc$addRenderableWidget(rscc$machineButton);

        // 「缺料处置」开关（挂起 / 等待）：画在标题行右端，宽度取两种档位文案里较宽的那个
        // （固定宽度 ⇒ 切换档位时按钮不会跳动，状态标签的右界也就只需要让一次位）。
        final Component hold = Component.translatable(RSCC_LANG + "shortage."
            + RsccShortagePolicy.Mode.SUSPEND.id());
        final Component wait = Component.translatable(RSCC_LANG + "shortage."
            + RsccShortagePolicy.Mode.WAIT.id());
        rscc$shortageWidth = Math.max(font.width(hold), font.width(wait)) + RSCC_SHORTAGE_PAD;
        rscc$shortageButton = Button.builder(hold, button -> rscc$switchShortage())
            .size(rscc$shortageWidth, RSCC_SHORTAGE_H).build();
        rscc$shortageButton.setPosition(
            screen.getGuiLeft() + screen.getXSize() - RSCC_RIGHT_MARGIN - rscc$shortageWidth,
            screen.getGuiTop() + RSCC_SHORTAGE_Y);
        ((ScreenAccessor) (Object) this).rscc$addRenderableWidget(rscc$shortageButton);
        rscc$refreshShortageButton();

        // 告警是「内容变化才广播」的：玩家若不在告警产生那一刻开着监视器，本地快照就是空的。
        // 这里主动拉一次，服务端按自身权威数据回一份（只读，不改任何服务端状态）。
        PacketDistributor.sendToServer(new RequestAssemblyAlertsPacket());
        // 缺料处置策略同理：它只在「被改动」时回发，界面一打开也要主动拉一次权威值。
        PacketDistributor.sendToServer(new RequestShortageModePacket());
        rscc$refreshButtons();
    }

    /**
     * 把「缺料处置」开关的文案与「当前档位」对齐（每帧一次；纯读取，无副作用、不发包）。
     * <p>常驻可见：它是一条<b>全网策略</b>，与「当前选中哪条任务」无关。</p>
     */
    @Unique
    private void rscc$refreshShortageButton() {
        if (rscc$shortageButton == null) {
            return;
        }
        rscc$shortageButton.setMessage(Component.translatable(
            RSCC_LANG + "shortage." + ShortageModeClient.mode().id()));
        rscc$shortageButton.visible = true;
        rscc$shortageButton.active = true;
    }

    /**
     * 点一下 = 在「挂起 / 等待」之间切换（<b>服务端权威</b>）。
     *
     * <p>客户端只发「我想要另一档」，自己不改任何状态；服务端写入主世界的
     * {@link RsccShortagePolicy} 并立刻单播回权威值（{@code SyncShortageModePacket}），
     * 界面下一次 {@code render} 就是新样子。因此「点了没生效」在结构上不可能发生
     * （要么按钮变了、要么服务端拒绝了包，绝不会出现本地假象）。</p>
     */
    @Unique
    private void rscc$switchShortage() {
        final RsccShortagePolicy.Mode next =
            ShortageModeClient.mode() == RsccShortagePolicy.Mode.SUSPEND
                ? RsccShortagePolicy.Mode.WAIT : RsccShortagePolicy.Mode.SUSPEND;
        PacketDistributor.sendToServer(new SetShortageModePacket(next.ordinal()));
    }

    /**
     * 把处置按钮排到原生「取消 / 全部取消」的<b>右侧、并排</b>，并保证：
     * <ol>
     *     <li><b>绝不与原生按钮重叠</b>：起点 = 原生那一排的右缘 + {@link #RSCC_BUTTON_GAP}；</li>
     *     <li><b>绝不越出面板 / 屏幕</b>：右界 = {@code leftPos + imageWidth - 7}（面板内侧右留白），
     *     放不下的按钮从后往前<b>收起</b>（{@code visible} 恒为 false，不绘制、不占位）；</li>
     *     <li><b>纵向永远在屏幕内</b>：与原生按钮同 y、同高，行底 = {@code topPos + 224}。
     *     由 {@code topPos = (height - 231) / 2} 可得「行底 ≤ height ⟺ 缩放后高度 ≥ 217」，
     *     而 Minecraft 保证缩放后高度 ≥ 240 ⇒ 该行在任何合法缩放 / 窗口尺寸下都可见。</li>
     * </ol>
     *
     * <p><b>「挂起」与「继续」共用同一槽位</b>：服务端保证两者互斥（RUNNING 时给「挂起」位、SUSPENDED 时
     * 给「继续」位），因此槽位宽度取两者<b>较宽</b>的那一个即可 —— 横向占用不随任务状态变化，
     * 也就不会因为多了一个按钮而挤压「更换机器」或越出面板。</p>
     *
     * <p><b>位置只在 init 里算一次</b>：之后无论按钮显示 / 隐藏，{@code setPosition} 都不再被调用 ——
     * 位置固定 = 命中框与绘制框天然同源，也不会有「按钮集合变化把另一个按钮挤走」的位移抖动。</p>
     */
    @Unique
    private void rscc$layoutButtons(final AbstractContainerScreen<?> screen, final int rowY) {
        final int maxRight = screen.getGuiLeft() + screen.getXSize() - RSCC_RIGHT_MARGIN;
        int x = rscc$nativeRowRight() + RSCC_BUTTON_GAP;
        final int slotWidth = Math.max(rscc$widthOf(rscc$resumeButton), rscc$widthOf(rscc$suspendButton));
        rscc$primaryFits = x + slotWidth <= maxRight;
        if (rscc$primaryFits) {
            rscc$setPosition(rscc$resumeButton, x, rowY);
            rscc$setPosition(rscc$suspendButton, x, rowY);
            x += slotWidth + RSCC_BUTTON_GAP;
        }
        rscc$machineFits = rscc$place(rscc$machineButton, x, rowY, maxRight);
    }

    /** 按钮宽度（null 防御；挂起 / 继续共槽时取较宽的那个）。 */
    @Unique
    private static int rscc$widthOf(@Nullable final Button button) {
        return button == null ? 0 : button.getWidth();
    }

    /** 落位（null 防御）。 */
    @Unique
    private static void rscc$setPosition(@Nullable final Button button, final int x, final int y) {
        if (button != null) {
            button.setPosition(x, y);
        }
    }

    /** 放得下才落位；返回是否放得下（放不下 = 收起，不进任何渲染 / 命中路径）。 */
    @Unique
    private boolean rscc$place(@Nullable final Button button, final int x, final int y, final int maxRight) {
        if (button == null || x + button.getWidth() > maxRight) {
            return false;
        }
        button.setPosition(x, y);
        return true;
    }

    /** 原生「取消 / 全部取消」那一排的右缘（按钮还没建出来时按 RS 的 7px 左边距兜底）。 */
    @Unique
    private int rscc$nativeRowRight() {
        if (cancelAllButton != null) {
            return cancelAllButton.getX() + cancelAllButton.getWidth();
        }
        if (cancelButton != null) {
            return cancelButton.getX() + cancelButton.getWidth();
        }
        return ((AbstractContainerScreen<?>) (Object) this).getGuiLeft() + 7;
    }

    /** 选中任务变化时刷新按钮可用性（挂起任务才可处置）。 */
    @Inject(method = "currentTaskChanged", at = @At("TAIL"))
    private void rscc$onCurrentTaskChanged(@Nullable final TaskStatus status, final CallbackInfo ci) {
        rscc$refreshButtons();
    }

    /** 渲染末尾：手动渲染挂起原因与按钮 tooltip（本工程硬规则：GUI 不会自动渲染 tooltip）。 */
    @Inject(method = "render", at = @At("TAIL"))
    private void rscc$renderAssemblyTooltips(final GuiGraphics graphics, final int mouseX, final int mouseY,
                                             final float partialTicks, final CallbackInfo ci) {
        // 每帧刷新一次：告警是**异步**到达的（SyncAssemblyAlertsPacket），界面开着的时候才收到告警
        // 不会触发 currentTaskChanged。这里只是把「服务端快照 + 在途标记」重新折成按钮状态，
        // 纯函数、无副作用、不发包、也不重建控件。
        rscc$refreshButtons();
        // 缺料处置开关：与当前档位对齐（异步的 S2C 可能在本帧之前刚到）
        rscc$refreshShortageButton();
        final Font font = Minecraft.getInstance().font;
        rscc$renderMarker(graphics, font, mouseX, mouseY);
        rscc$tooltip(graphics, font, rscc$resumeButton,
            RSCC_LANG + "resume.tip", SyncAssemblyAlertsPacket.ACTION_BIT_RESUME, mouseX, mouseY);
        rscc$tooltip(graphics, font, rscc$suspendButton,
            RSCC_LANG + "suspend.tip", SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND, mouseX, mouseY);
        rscc$tooltip(graphics, font, rscc$machineButton,
            RSCC_LANG + "change_machine.tip", SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE,
            mouseX, mouseY);
        rscc$shortageTooltip(graphics, font, mouseX, mouseY);
    }

    /**
     * 「缺料处置」开关的手动 tooltip（本工程硬规则：GUI 不会自动渲染 tooltip）。
     *
     * <p>三行把话说全：① 它是什么；② <b>当前档位的服务端原话</b>（直接复用指令那条反馈文案，
     * 因此界面与 {@code /rs_create_compat shortagemode} 说的永远是同一句）；③ 点一下会切到哪一档。</p>
     */
    @Unique
    private void rscc$shortageTooltip(final GuiGraphics graphics, final Font font,
                                      final int mouseX, final int mouseY) {
        final Button button = rscc$shortageButton;
        if (button == null || !button.visible) {
            return;
        }
        if (mouseX < button.getX() || mouseX >= button.getX() + button.getWidth()
            || mouseY < button.getY() || mouseY >= button.getY() + button.getHeight()) {
            return;
        }
        final RsccShortagePolicy.Mode current = ShortageModeClient.mode();
        final RsccShortagePolicy.Mode next = current == RsccShortagePolicy.Mode.SUSPEND
            ? RsccShortagePolicy.Mode.WAIT : RsccShortagePolicy.Mode.SUSPEND;
        final List<FormattedCharSequence> lines = new ArrayList<>(3);
        lines.add(Component.translatable(RSCC_LANG + "shortage.title")
            .withStyle(net.minecraft.ChatFormatting.WHITE).getVisualOrderText());
        lines.add(Component.translatable(current.langKey())
            .withStyle(net.minecraft.ChatFormatting.AQUA).getVisualOrderText());
        lines.add(Component.translatable(RSCC_LANG + "shortage.tip",
            Component.translatable(next.langKey())).getVisualOrderText());
        graphics.renderTooltip(font, lines, mouseX, mouseY);
    }

    /**
     * <b>挂起原因</b>：当前选中的任务处于挂起态时，在<b>原生标题行右侧</b>画一行原因文字
     * （右对齐到面板右内缘），hover 时再用手动 tooltip 说明如何恢复。
     *
     * <p><b>为什么是这一行</b>（几何推演）：</p>
     * <ul>
     *     <li><b>纵向</b>：行 y = {@code topPos + 7}（与 RS 原生标题同基线，{@code titleLabelY = 7}），
     *     行高 9 ⇒ 占 {@code topPos + 7 .. topPos + 16}。面板高 231 ⇒ 整行都在面板内，
     *     也不会碰物品列表（列表从相对 y = 20 起）。旧实现放在 {@code topPos + 226}（按钮行下方），
     *     行底 235 &gt; 231 —— 文字下半截画在面板外的深色背景上；而且缩放后界面高 &lt; 231
     *     （玩家手动指定较大 GUI 缩放时）topPos 为负，该行会被屏幕下沿整行裁掉。</li>
     *     <li><b>横向</b>：右界 = {@code guiLeft + 254 - 7}，左界 = {@code guiLeft + 7 + 标题宽 + 4}，
     *     文案在这一段里<b>右对齐并截断</b>（装不下时补「…」）；可用宽度不足
     *     {@link #RSCC_MARKER_MIN_WIDTH} 就整行不画。于是「文字永不越出面板 / 永不压标题」是
     *     由构造保证的，与语言、GUI 缩放、窗口尺寸都无关。</li>
     *     <li><b>兜底夹取</b>：极端缩放下（面板比屏幕还高）再把整行夹进屏幕矩形
     *     {@code [0, screenHeight - lineHeight]} —— 宁可贴着屏幕边，也绝不画出屏幕。</li>
     * </ul>
     *
     * <p><b>为什么不与原生按钮 / 滚动条重叠</b>：按钮行在 {@code topPos + 204..224}、物品列表区在
     * {@code topPos + 20..199} 且右侧滚动条 x ≥ 235，本行在 {@code topPos + 7..16} —— 三者纵向不相交。</p>
     */
    @Unique
    private void rscc$renderMarker(final GuiGraphics graphics, final Font font,
                                   final int mouseX, final int mouseY) {
        final SyncAssemblyAlertsPacket.Alert alert = rscc$alert;
        final String key = alert == null ? null : rscc$suspendedKey(alert.reason());
        if (key == null) {
            return; // 没有挂起标记：不绘制、也不占 hover
        }
        final AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        final var window = Minecraft.getInstance().getWindow();
        final int lineHeight = font.lineHeight;
        final int y = rscc$clamp(screen.getGuiTop() + RSCC_MARKER_Y, 0,
            window.getGuiScaledHeight() - lineHeight);
        // 右界要让出标题行右端的「缺料处置」开关（用户第 4 条）：两者同在标题行，
        // 因此这里减去「开关宽度 + 间距」，保证这段状态文字永不压到那颗按钮上。
        final int right = rscc$clamp(screen.getGuiLeft() + screen.getXSize() - RSCC_MARKER_RIGHT
            - rscc$shortageWidth - RSCC_SHORTAGE_GAP, 0, window.getGuiScaledWidth());
        final int bandLeft = Math.max(
            screen.getGuiLeft() + RSCC_TITLE_X + font.width(screen.getTitle()) + RSCC_MARKER_GAP, 0);
        final int available = right - bandLeft;
        if (available < RSCC_MARKER_MIN_WIDTH) {
            return; // 标题太长 / 屏幕太窄：宁可不画，也绝不越界、绝不压标题
        }
        final String shown = rscc$fit(font, Component.translatable(key).getString(), available);
        if (shown.isEmpty()) {
            return;
        }
        final int textWidth = font.width(shown);
        graphics.drawString(font, shown, right - textWidth, y, rscc$markerColor(alert.reason()), false);
        // hover 判定 = 这块「状态标签带」本身（与绘制同源：都是上面算出来的 bandLeft .. right）
        if (mouseX >= bandLeft && mouseX < right && mouseY >= y && mouseY < y + lineHeight) {
            // <b>两行</b>：第一行是「已挂起 + 如何恢复」，第二行<b>解释上面物品行里那些「处理中：N」</b>。
            //
            // <p><b>为什么必须补第二行（用户上一轮的疑问）</b>：金板（{@code create:golden_sheet}）是这条
            // 序列装配配方的<b>主原料</b>，因此 RS 会在它的物品行上画「处理中：N」——
            // 该数字来自 RS 自己的 {@code ExternalTaskPattern#appendStatus}
            // （{@code iterationsProcessing = iterationsSentToSink - iterationsReceived}），
            // 含义是「<b>已交给产线、还没产出回来的件数</b>」。挂起时它<b>不会变</b>（我们不再推进那条任务，
            // 所以既没新发出、也没新收回），于是玩家看到「明明已经不动了，却还写着 7 个金板正在处理」，
            // 误以为还在跑 / 料被吞。<b>那是 RS 的真实在途计数，正确且不该被改写</b>（改它就是伪造账目），
            // 所以这里只把语义讲清楚：它是在途件数、挂起期间冻结、点「继续」后才推进并归零。</p>
            final List<FormattedCharSequence> lines = new ArrayList<>(2);
            lines.add(Component.translatable(RSCC_LANG + "suspended.tip").getVisualOrderText());
            lines.add(Component.translatable(RSCC_LANG + "suspended.inflight_tip").getVisualOrderText());
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** 装不下就在保留「…」的前提下截断（保证绘制宽度 ≤ maxWidth）。 */
    @Unique
    private static String rscc$fit(final Font font, final String text, final int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        final int budget = maxWidth - font.width(RSCC_ELLIPSIS);
        return budget <= 0 ? "" : font.plainSubstrByWidth(text, budget) + RSCC_ELLIPSIS;
    }

    /** 夹取到 [min, max]（max &lt; min 时取 min，避免出现负坐标）。 */
    @Unique
    private static int rscc$clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** 告警原因 → 挂起标记的语言键（未知原因不画标记；未挂起的任务快照原因恒为 NONE，因此也不会画）。 */
    @Unique
    @Nullable
    private String rscc$suspendedKey(final int reason) {
        return switch (reason) {
            case SyncAssemblyAlertsPacket.REASON_EXECUTOR_OFFLINE -> RSCC_LANG + "suspended.offline";
            case SyncAssemblyAlertsPacket.REASON_MISSING_MATERIAL -> RSCC_LANG + "suspended.missing";
            case SyncAssemblyAlertsPacket.REASON_NO_PROGRESS -> RSCC_LANG + "suspended.noprog";
            case SyncAssemblyAlertsPacket.REASON_MANUAL -> RSCC_LANG + "suspended.manual";
            // 2026-10-05：下游机器满 / 不接受（机器在线）与「设备掉线」分开显示 —— 修的是
            // 「横幅说掉线、而机械手与置物台都是空的」这条误导。
            case SyncAssemblyAlertsPacket.REASON_OUTPUT_BLOCKED -> RSCC_LANG + "suspended.output_blocked";
            default -> null;
        };
    }

    /** 挂起标记的颜色：掉线用红、输出阻塞 / 等待（缺料 / 无进展）用橙。 */
    @Unique
    private int rscc$markerColor(final int reason) {
        return reason == SyncAssemblyAlertsPacket.REASON_EXECUTOR_OFFLINE
            ? RSCC_COLOR_OFFLINE : RSCC_COLOR_WAITING;
    }

    /** 手动渲染按钮 tooltip；在途（已点击、等服务端答复）时追加一行「正在处理…」。 */
    @Unique
    private void rscc$tooltip(final GuiGraphics graphics, final Font font, @Nullable final Button button,
                              final String key, final int actionBit, final int mouseX, final int mouseY) {
        if (button == null || !button.visible) {
            return;
        }
        if (mouseX < button.getX() || mouseX >= button.getX() + button.getWidth()
            || mouseY < button.getY() || mouseY >= button.getY() + button.getHeight()) {
            return;
        }
        if (!AssemblyAlertsClient.isPending(rscc$taskId, actionBit)) {
            graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);
            return;
        }
        final List<FormattedCharSequence> lines = new ArrayList<>(2);
        lines.add(Component.translatable(key).getVisualOrderText());
        lines.add(Component.translatable(RSCC_LANG + "pending").getVisualOrderText());
        graphics.renderTooltip(font, lines, mouseX, mouseY);
    }

    /** 当前选中的任务 id（借由 Mixin 注入到菜单上的桥接接口读取）。 */
    @Unique
    @Nullable
    private UUID rscc$currentTaskId() {
        final AbstractContainerMenu menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();
        if (menu instanceof RsccAssemblyMonitorBridge bridge) {
            final TaskId taskId = bridge.rscc$currentTaskId();
            return taskId == null ? null : taskId.id();
        }
        return null;
    }

    /**
     * <b>按钮状态的唯一刷新点</b>：把「服务端快照里的动作位集」折成三个按钮的
     * {@code visible / active}。
     *
     * <ul>
     *     <li>{@code visible} <b>只</b>看服务端给的动作位（{@link AssemblyAlertsClient#actionView}）
     *     与「横向放得下」这一条<b>静态</b>几何条件；</li>
     *     <li>{@code active} = {@code visible && 该动作不在途} —— 在途只影响可用性，绝不影响可见性。</li>
     * </ul>
     *
     * <p>因此「点击某个按钮」这件事<b>不可能</b>让任何按钮消失：按钮集合只随服务端快照变化。
     * 「挂起」与「继续」互斥（服务端 RUNNING 给 SUSPEND、SUSPENDED 给 RESUME），同一时刻只会渲染其中一个。
     * 「更换机器」单独消失（而「继续」还在）也只可能是服务端收回了这一位 —— 例如执行仓回到了网络里，
     * 那一步不再掉线，这个动作确实已经不适用。</p>
     */
    @Unique
    private void rscc$refreshButtons() {
        final UUID taskId = rscc$currentTaskId();
        rscc$taskId = taskId;
        final SyncAssemblyAlertsPacket.Alert alert = AssemblyAlertsClient.alertOf(taskId);
        rscc$alert = alert;
        rscc$apply(rscc$resumeButton, rscc$primaryFits,
            AssemblyAlertsClient.actionView(alert, taskId, SyncAssemblyAlertsPacket.ACTION_BIT_RESUME));
        rscc$apply(rscc$suspendButton, rscc$primaryFits,
            AssemblyAlertsClient.actionView(alert, taskId, SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND));
        rscc$apply(rscc$machineButton, rscc$machineFits,
            AssemblyAlertsClient.actionView(alert, taskId, SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE));
    }

    /** 写回单个按钮的状态（不可见即完全不参与渲染与鼠标命中，不占位）。 */
    @Unique
    private void rscc$apply(@Nullable final Button button, final boolean fits,
                            final AssemblyAlertsClient.ActionView view) {
        if (button == null) {
            return;
        }
        button.visible = view.visible() && fits;
        button.active = button.visible && view.enabled();
    }

    /**
     * 「继续」：发一次 C2S 动作包（服务端会做存在性校验，任务已消失时安全失败并回提示）。
     *
     * <p>在途闸门保证「一次点击 = 一次发包」；点击当刻按钮立刻变灰（即时反馈），
     * 服务端快照（≤ 1 个扫描周期）或 2 秒超时后自动解锁。</p>
     */
    @Unique
    private void rscc$resume() {
        final UUID taskId = rscc$taskId;
        if (!AssemblyAlertsClient.beginAction(taskId, SyncAssemblyAlertsPacket.ACTION_BIT_RESUME)
            || taskId == null) {
            return;
        }
        PacketDistributor.sendToServer(new AssemblyTaskActionPacket(taskId, AssemblyTaskActionPacket.ACTION_RESUME));
        rscc$refreshButtons(); // 立刻把「在途」反映到按钮上（不必等下一帧）
    }

    /**
     * 「挂起」：把当前正在跑的任务交给服务端挂起（不再占用执行器 / 不阻塞后续任务，中间件原地冻结），
     * 让玩家「先挂起、让出位置，让别人先做」。
     *
     * <p>与「继续」同一套在途闸门：一次点击 = 一个包；点击当刻按钮立刻变灰 + tooltip 显示「正在处理…」，
     * 服务端快照（≤ 1 个扫描周期）或 2 秒超时后自动解锁。服务端会做存在性 / 状态校验
     * （任务存在、且处于 RUNNING），失败时回一条极简提示。</p>
     */
    @Unique
    private void rscc$suspend() {
        final UUID taskId = rscc$taskId;
        if (!AssemblyAlertsClient.beginAction(taskId, SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND)
            || taskId == null) {
            return;
        }
        PacketDistributor.sendToServer(new AssemblyTaskActionPacket(taskId, AssemblyTaskActionPacket.ACTION_SUSPEND));
        rscc$refreshButtons(); // 立刻把「在途」反映到按钮上（不必等下一帧）
    }

    /** 请求候选机器（服务端回包后由 AssemblyAlertsClient 弹出既有的机器选择子界面）。 */
    @Unique
    private void rscc$changeMachine() {
        final UUID taskId = rscc$taskId;
        if (taskId == null) {
            return;
        }
        // 掉线步骤下标是「数据」（服务端在快照里给出的第一条掉线步骤），不是可见性判据 ——
        // 能不能点由快照的动作位决定，这里只负责把请求发出去。
        final int stepIndex = AssemblyAlertsClient.firstOfflineStep(rscc$alert);
        if (stepIndex < 0) {
            return;
        }
        if (!AssemblyAlertsClient.beginAction(taskId, SyncAssemblyAlertsPacket.ACTION_BIT_CHANGE_MACHINE)) {
            return;
        }
        AssemblyAlertsClient.requestMachines(taskId, stepIndex);
        rscc$refreshButtons();
    }
}
