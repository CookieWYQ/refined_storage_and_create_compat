package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.common.support.widget.AbstractSideButtonWidget;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.content.Blocks;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.network.SwitchTerminalModePacket;
import cretae.cookiewyq.rs_create_compat.support.RsccTerminalLocator;
import cretae.cookiewyq.rs_create_compat.support.TerminalNetworkPower;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 高级远程多功能终端：在打开的 RS 原版界面（合成终端 / 样板终端 / 合成仓管理 / 合成仓监视 / 序列装配 / 单元样板管理）
 * 右下角叠加方块图标模式切换 Tab（参照 Universal-Grid 的方块 Tab 设计）。
 * 点击 Tab → 发送 C2S 包 → 服务端把模式写回物品并重开对应界面。
 * <p>
 * <b>只有「由本模组终端打开的界面」才有这一排</b>：判据是 {@link TerminalOpenIntent} 的打开意图标记
 * （按键 / 手持右键 / 点击模式按钮重开界面时置位），而<b>不是</b>「玩家身上带着终端」——
 * 后者会让「背包里有终端」时打开任意 RS 界面都多出一排（用户实测回归）。
 *
 * <h2>第 100 轮：绑定网络没电 ⇒ 整排 Tab 统一禁用（用户实机反馈）</h2>
 * <p>「网络完全没电了，样板终端还是能点、只是里面物品不可见」—— 现在<b>所有</b>模式 Tab 一律置灰、
 * 不可点（用户拍板：不分模式）；悬停给「绑定的网络没有电（存量 / 容量 FE）—— 恢复供电后才能切换」。
 * 判据只有一条：服务端权威快照 {@link TerminalPowerState#isLocked()}（由
 * {@code support/TerminalNetworkPower} 在界面打开时立刻发一条、之后每 20 tick 一条；<b>未知 / 过期
 * 时不锁</b>，保证有电时行为与今天完全一致）。终端物品<b>自身</b>电量<b>不</b>参与判定
 * （RS 原版的「物品没电禁用」是另一条逻辑）。点击在这里被拦下（不发
 * {@code SwitchTerminalModePacket}，避免服务端重开界面把玩家界面关掉），服务端处理体还会再拒一次。</p>
 *
 * <h2>本轮修的「切模式后控件/框错位」（用户实机反馈）</h2>
 * <p>症状：点「合成终端」后界面确实换过去了，但<b>高亮框还停在「样板终端」那颗 Tab 上</b>；
 * 有时一排按钮又出现在另一个位置。三条成因，全部是<b>状态/几何不一致</b>，不是绘制问题：</p>
 * <ol>
 *     <li><b>本地模式写到了「已脱离背包的 ItemStack」上</b>（旧实现把 {@code Init.Post} 那一刻的
 *     {@code ItemStack} 引用烤进按钮回调）。Minecraft 打开一个容器时服务端在开屏包之后紧接着发
 *     {@code ClientboundContainerSetContentPacket}（{@code AbstractContainerMenu#initializeContents}），
 *     它对<b>每一个槽位</b>调 {@code Slot#set} → {@code Inventory#setItem} → {@code NonNullList#set}，
 *     <b>把背包里的 ItemStack 换成新实例</b>。因此界面初始化之后哪怕只过了一个 tick，旧引用就已经不在背包里了，
 *     往它身上写模式 = 写进一个没人读的对象。⇒ 新界面的 {@code Init.Post} 读到的仍是<b>旧模式</b>，
 *     高亮自然画在旧 Tab 上。</li>
 *     <li><b>高亮是「构造期快照」而不是「物品状态的函数」</b>（旧实现把 {@code currentMode == mode} 算成
 *     {@code selected} 存进控件）。服务端稍后把新模式同步下来时，没有任何人重算高亮 ⇒ 框一直停在旧 Tab，
 *     直到某次 {@code rebuildWidgets()}（改窗口大小 / 进子界面再返回）才偶然恢复 —— 用户说的「有的时候」。</li>
 *     <li><b>一排按钮的几何只在 {@code Init.Post} 里按「那一个界面」的面板算一次，且没有夹进屏幕矩形</b>。
 *     各模式面板尺寸并不同（网格 176 / 样板与管理器 193 / 监视器 254 / 序列样板终端 256 宽；
 *     序列样板终端固定 324 高），所以一排 Tab 在不同模式里本来就落在不同位置；更糟的是当面板比屏幕还高时
 *     （序列样板终端 324px &gt; 常见 GUI 高度），最下面两个 Tab 会被画到<b>屏幕外</b> —— 看得见一半、点不到。</li>
 * </ol>
 * <p><b>修法（状态同步，不是重绘 hack）</b>：</p>
 * <ul>
 *     <li>模式<b>只从存活栈读、只往存活栈写</b>：{@link TabRowState#liveStack()} 每次按
 *     {@link SlotReference} 现取（与 {@code SwitchTerminalModePacket#handle} 服务端那一侧同一套解引用），
 *     点击时写的也是它；</li>
 *     <li>高亮<b>每帧</b>由 {@link #onScreenRenderPre} 重读一次权威值（服务端迟到的同步下一帧就纠正）；</li>
 *     <li>几何在 {@code Init.Post} 按该界面面板算一次并<b>整体夹进屏幕矩形</b>（x / y 都夹）；
 *     <b>绘制与命中仍共用控件自身的 {@code getX()/getY()/getWidth()/getHeight()}</b>，不存在第二套坐标；</li>
 *     <li>旧控件绝不留：本类记住「本界面那一排」的控件，同一界面再次初始化时先
 *     {@code removeListener} 摘掉再重建（即使别的模组取消了 {@code Init.Pre} 导致原版没有
 *     {@code clearWidgets()}，也不会出现两排）；</li>
 *     <li>同一界面的<b>重复初始化</b>（窗口缩放 / 从子界面返回：原版走
 *     {@code repositionElements()} → {@code rebuildWidgets()}，控件被清空后重新 {@code init()}）
 *     不再受 {@link TerminalOpenIntent} 的 3 秒时限影响 —— 用「界面身份」而非「时间」判断是不是同一排。</li>
 * </ul>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
@SuppressWarnings({"deprecation"})
public final class TerminalModeTabOverlay {
    private static final int TAB_W = 24;
    private static final int TAB_H = 22;
    /** 一排 Tab 与屏幕边缘之间保留的安全距离（夹取用；只在不夹就会画到屏幕外时才生效）。 */
    private static final int SCREEN_MARGIN = 2;
    private static final String[] MODE_KEYS = {
        "gui.rs_create_compat.advanced_remote_terminal.mode.grid",
        "gui.rs_create_compat.advanced_remote_terminal.mode.patterns",
        "gui.rs_create_compat.advanced_remote_terminal.mode.manager",
        "gui.rs_create_compat.advanced_remote_terminal.mode.monitor",
        "gui.rs_create_compat.advanced_remote_terminal.mode.sequence",
        "gui.rs_create_compat.advanced_remote_terminal.mode.unit_manager",
        "gui.rs_create_compat.advanced_remote_terminal.mode.energy",
        // 第 131 轮第 8 档：发送（与请求器方块同一个网格界面）。数组下标必须 = 模式号，
        // 长度必须 = MODE_COUNT —— 少了这一颗，Tab 会拿不到语言键（Tooltip 变成裸键名）。
        "gui.rs_create_compat.advanced_remote_terminal.mode.send"
    };

    /**
     * 当前挂着这一排 Tab 的界面<b>身份</b>（弱引用：界面被替换后不需要手工清理，也不会拖住界面）。
     * <p>它只用来回答一件事：「这次 {@code Init.Post} 是同一个界面的重复初始化吗」。
     * 是 ⇒ 直接重建这一排（不受 3 秒意图时限影响）；不是 ⇒ 走正常的意图判据。</p>
     */
    private static WeakReference<Screen> rowOwner = new WeakReference<>(null);
    /** 上一次为 {@link #rowOwner} 建出来的那一排控件（同一界面再次初始化时先摘掉，保证绝不留旧控件）。 */
    private static final List<TerminalModeTabButton> ROW_WIDGETS = new ArrayList<>();
    /** 这一排控件共用的存活状态（模式每帧从物品真实数据重读一次）。 */
    private static WeakReference<TabRowState> rowState = new WeakReference<>(null);

    private TerminalModeTabOverlay() {
    }

    @SubscribeEvent
    public static void onScreenInit(final ScreenEvent.Init.Post event) {
        final Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        // 仅当打开的是我们终端对应的 RS 原版界面时叠加 Tab。
        // 注意：不能调用 getMenu().getType() —— RS 的 mixin 会让它在非 RS 菜单（如原版背包）上抛异常。
        // 改为直接 instanceof RS 的 Screen 类型。
        if (!isRsScreen(screen)) {
            return;
        }
        // 回归修复：只有「由本模组终端主动打开」的界面才叠加这一排。之前的判定是「玩家身上带着终端」，
        // 自从查找范围扩到「背包任意格」，只要背包里有终端，打开自动合成管理器等任意 RS 界面都会多出这一排。
        // 意图标记未置位（超时 / 已被别的界面消费）→ 一个按钮都不加。
        // 但「同一个界面的重复初始化」不算新打开：窗口缩放 / 从子界面返回都会走到这里，
        // 若也按 3 秒时限判，界面开着超过 3 秒后随便缩放一下这一排就会整排消失（用户会认为「按钮跑丢了」）。
        final boolean sameScreen = rowOwner.get() == screen;
        if (!sameScreen && !TerminalOpenIntent.consume(screen)) {
            return;
        }
        final Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 与服务端权威查找共用同一套优先级（主手 → 副手 → 背包 0..35 → Curios），
        // 保证本地读写模式的栈就是服务端打开的那一台；缺一不可（拿不到栈就没法本地读写模式，Tab 一个都不渲染）。
        final Optional<RsccTerminalLocator.Location> located = RsccTerminalLocator.locate(player);
        if (located.isEmpty()) {
            return;
        }
        // 旧控件不得残留：先把上一排（可能是同一界面被 clearWidgets() 清掉之前的实例，
        // 也可能是别的模组取消了 Init.Pre 而原版没清空的实例）从本界面摘掉，再建新的一排。
        removeRow(event);
        // 【第 100 轮】换了一个界面（新打开 / 切模式重开）⇒ 上一台终端的「没电」快照不得决定
        // 新界面的 Tab：清回「未知」（= 不置灰），等本界面自己的权威快照（服务端在打开后立刻补发，
        // 见 support/TerminalNetworkPower#start）。同一界面的重复初始化（缩放 / 子界面返回）不清，
        // 否则整排 Tab 会闪一下。
        if (!sameScreen) {
            TerminalPowerState.reset();
        }

        final SlotReference slotReference = located.get().slotReference();
        final TabRowState state = new TabRowState(slotReference, located.get().stack());
        rowOwner = new WeakReference<>(screen);
        rowState = new WeakReference<>(state);

        final int count = AdvancedRemoteTerminalItem.MODE_COUNT;
        // imageWidth / imageHeight 为 AbstractContainerScreen 的 protected 字段，外部无法直接访问，用反射取值
        final int guiW = guiSize(containerScreen, "imageWidth", 176);
        final int guiH = guiSize(containerScreen, "imageHeight", 166);
        // 位置：GUI 面板右下角外侧（贴 RS 自己的「侧按钮列」口径 leftPos + imageWidth + 2，不遮挡界面内容），
        // 然后整体夹进屏幕矩形 —— 面板比屏幕高时（序列样板终端固定 324px）旧写法会把最下面两颗 Tab
        // 画到屏幕外：看得到一半、点不到，用户体感就是「按钮跑到别的地方去了」。
        //
        // 【第 132 轮：发送档的 Tab 列再左移，避开 RS 侧键】RS 的侧键由 AbstractBaseScreen 画在
        // x ∈ [leftPos - AbstractSideButtonWidget.SIZE - 2, leftPos - 2]（18×18，从 topPos+6 每 20px 向下）。
        // 旧写法 x = leftPos - TAB_W - 2 的右缘 = leftPos - 2 ⇒ 正好压在侧键上（用户截图：页签和侧键重叠）。
        // 新公式：x = leftPos - TAB_W - AbstractSideButtonWidget.SIZE - 4（= leftPos-46），
        // 页签右缘 = leftPos-22，与侧键左缘 leftPos-20 留 2px；x<2（窄屏 / 小缩放）时退回 2。
        // 纵向仍贴 GUI 底部向上排列（columnTop 的计算不动）。
        final boolean sendMode =
            containerScreen instanceof cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerScreen;
        final int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        final int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        final int columnH = TAB_H * count;
        // 两条分支分别处理：发送档按「RS 侧键左侧」公式并夹到屏幕左边 ≥2；其它模式保持原样
        // （右侧 + 屏幕矩形夹取），一个字节都不改。
        final int x = sendMode
            ? Math.max(SCREEN_MARGIN,
                containerScreen.getGuiLeft() - TAB_W - AbstractSideButtonWidget.SIZE - 4)
            : clamp(containerScreen.getGuiLeft() + guiW + SCREEN_MARGIN,
                SCREEN_MARGIN, screenW - TAB_W - SCREEN_MARGIN);
        final int columnTop = clamp(containerScreen.getGuiTop() + guiH - columnH,
            SCREEN_MARGIN, screenH - columnH - SCREEN_MARGIN);
        for (int i = 0; i < count; i++) {
            final int mode = i;
            final int y = columnTop + (TAB_H * i);
            final boolean supported = AdvancedRemoteTerminalItem.isModeSupported(mode);
            final TerminalModeTabButton button = new TerminalModeTabButton(
                x, y, state, mode, supported,
                Component.translatable(MODE_KEYS[mode]),
                () -> onTabClicked(state, mode, supported));
            ROW_WIDGETS.add(button);
            event.addListener(button);
        }
    }

    /**
     * 每帧一次：把「这一排 Tab 该高亮谁」重新对齐到物品上的权威模式。
     * <p>{@code ScreenEvent.Render.Pre} 由 NeoForge 在 {@code ClientHooks#drawScreenInternal} 里、
     * <b>整屏渲染之前</b>触发（{@code screen.renderWithTooltip} 之前），因此它对
     * {@link AbstractContainerScreen} 同样成立（不像 {@code Screen#render} 的 mixin 那样会被
     * 容器界面「就地复制渲染流程」绕过），并且早于任何控件的 {@code renderWidget} ——
     * 本帧画出来的高亮一定用的是本帧读到的模式。</p>
     * <p>为什么必须每帧读、而不是初始化时读一次：模式是<b>服务端权威值</b>，客户端只是镜像 ——
     * 点击 Tab 之后服务端会重开界面，新界面的 {@code Init.Post} 发生在「新菜单的初始内容包」<b>之前</b>
     * （服务端 {@code openMenu} 先发开屏包、再 {@code setSynchronizer} 发内容包），
     * 所以初始化那一刻读到的必然可能是上一帧的旧值；迟到的内容包到达后必须能把它纠正回来。</p>
     */
    @SubscribeEvent
    public static void onScreenRenderPre(final ScreenEvent.Render.Pre event) {
        final TabRowState state = rowState.get();
        if (state == null || rowOwner.get() != event.getScreen()) {
            return;
        }
        state.refresh();
    }

    /**
     * 点一颗 Tab：可用性 → 模式是否真的变了 → 写存活栈 + 记打开意图 + 发包。
     * <p>这里<b>不缓存 Player</b>（和 {@code ItemStack} 同理）：点击时现取
     * {@code Minecraft.getInstance().player}，避免跨存档 / 换维度后对着旧对象发消息。</p>
     */
    private static void onTabClicked(final TabRowState state, final int mode, final boolean supported) {
        final Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 【第 100 轮｜绑定网络没电 ⇒ 统一禁用所有模式 Tab】用户拍板：不区分模式，全灰全不可点。
        // 点击<b>什么都不发生</b>：绝不发 SwitchTerminalModePacket —— 服务端会重开对应界面，
        // 那会把玩家正开着的界面关掉（旧版本就是这样，用户看到「能点、但物品不可见」）。
        // 判据 = 服务端权威快照（TerminalPowerState，来自 support/TerminalNetworkPower），
        // 与终端物品自身电量无关（用户明确：终端本身有电也不行）；未知状态下不锁（行为与今天一致）。
        if (TerminalPowerState.isLocked()) {
            return;
        }
        if (!supported) {
            // 不可用模式：本地提示即可，不发 packet（避免界面重开/鼠标跳中心）
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_unavailable"),
                true);
            return;
        }
        if (mode == state.mode()) {
            return;
        }
        // 本地立即更新物品模式，保证界面重开后 Tab 高亮正确（服务端物品同步有往返时延）。
        // 关键：必须写「存活栈」——背包里的 ItemStack 会被服务端内容包换成新实例，
        // Init.Post 时捕获的那一份早已脱离背包（写上去等于没写，这正是「框停在旧 Tab」的根因）。
        AdvancedRemoteTerminalItem.setMode(state.liveStack(), mode);
        state.refresh();
        // 切换后界面会由服务端重开：重新置位打开意图，否则新界面拿不到这一排按钮
        TerminalOpenIntent.mark();
        // 仿照 Universal-Grid：读取鼠标原生窗口坐标随切换包发送，
        // 服务端重开界面后回发坐标恢复鼠标（避免跳回屏幕中心）
        final long window = Minecraft.getInstance().getWindow().getWindow();
        final double[] cursorX = new double[1];
        final double[] cursorY = new double[1];
        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, cursorX, cursorY);
        sendSwitch(player, state.slotReference(), mode,
            (int) Math.round(cursorX[0]), (int) Math.round(cursorY[0]));
    }

    /** 把上一排控件从本界面摘掉（对已被 {@code clearWidgets()} 清空的列表是空操作）。 */
    private static void removeRow(final ScreenEvent.Init.Post event) {
        for (final TerminalModeTabButton button : ROW_WIDGETS) {
            event.removeListener(button);
        }
        ROW_WIDGETS.clear();
        rowState = new WeakReference<>(null);
    }

    /** 夹取到 [min, max]；{@code max < min} 时取 {@code min}（例如屏幕比一排 Tab 还矮）。 */
    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    /** 判断是否为 RS 的网格 / 样板 / 合成仓管理 / 合成仓监视 / 本模组序列样板终端界面（仅客户端存在这些类）。 */
    private static boolean isRsScreen(final Screen screen) {
        return screen instanceof com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen<?>
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridScreen
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerScreen
            || screen instanceof com.refinedmods.refinedstorage.common.autocrafting.monitor.AutocraftingMonitorScreen
            || screen instanceof cretae.cookiewyq.rs_create_compat.client.screen.SequencePatternTerminalScreen
            || screen instanceof cretae.cookiewyq.rs_create_compat.client.screen.UnitPatternManagerScreen;
    }

    /** 反射读取 AbstractContainerScreen 的 protected 尺寸字段，失败时返回默认值。 */
    private static int guiSize(final Object screen, final String fieldName, final int fallback) {
        try {
            final java.lang.reflect.Field field =
                net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class
                    .getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(screen);
        } catch (final ReflectiveOperationException e) {
            return fallback;
        }
    }

    /** 把模式切换请求（含鼠标原生窗口坐标）发给服务端；服务端写回模式并重开界面。 */
    private static void sendSwitch(final Player player, final SlotReference slotReference,
                                   final int mode, final int cursorX, final int cursorY) {
        if (!player.level().isClientSide()) {
            return;
        }
        player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
            SoundEvents.UI_BUTTON_CLICK, SoundSource.MASTER, 0.5F, 1.0F);
        PacketDistributor.sendToServer(new SwitchTerminalModePacket(slotReference, mode, cursorX, cursorY));
    }

    /**
     * 这一排 Tab 的<b>存活状态</b>：只存「怎么找到那台终端」，不存「模式是几」以外的任何快照。
     *
     * <p><b>为什么不缓存 ItemStack</b>：Minecraft 打开容器时，服务端在开屏包之后紧接着发
     * {@code ClientboundContainerSetContentPacket}，客户端对每个槽位调 {@code Slot#set} →
     * {@code Inventory#setItem}，<b>把背包里的 ItemStack 换成新解码出来的实例</b>。
     * 也就是说「初始化那一刻拿到的栈」在几毫秒后就已经不在背包里了。因此这里只保留
     * {@link SlotReference}（它只存下标 / 手位），每次要读写都<b>现取</b>存活栈；
     * 捕获到的那一份仅作为「引用彻底失效（物品被搬走 / 被销毁）」时的兜底。</p>
     *
     * <p><b>不持有 Player</b>：通过 {@code Minecraft.getInstance().player} 现取，避免静态字段
     * 把玩家 / 世界对象拖过退出存档。</p>
     */
    private static final class TabRowState {
        private final SlotReference slotReference;
        private final ItemStack fallbackStack;
        /** 当前模式（每帧由 {@link #refresh()} 重读；只作为「本帧的高亮依据」与「点击去重」用）。 */
        private int mode;

        TabRowState(final SlotReference slotReference, final ItemStack fallbackStack) {
            this.slotReference = slotReference;
            this.fallbackStack = fallbackStack;
            this.mode = readMode();
        }

        SlotReference slotReference() {
            return slotReference;
        }

        /** 此处即物品模式的<b>唯一读取口径</b>：优先按引用现取存活栈，引用失效才用打开界面时的那一份。 */
        ItemStack liveStack() {
            final Player player = Minecraft.getInstance().player;
            if (player != null) {
                final Optional<ItemStack> resolved = slotReference.resolve(player);
                if (resolved.isPresent() && !resolved.get().isEmpty()) {
                    return resolved.get();
                }
            }
            return fallbackStack;
        }

        int mode() {
            return mode;
        }

        /** 重读权威模式（每帧一次 + 点击后立刻一次）。 */
        void refresh() {
            mode = readMode();
        }

        private int readMode() {
            return AdvancedRemoteTerminalItem.getMode(liveStack());
        }
    }

    /** 方块图标 Tab 按钮：选中高亮（每帧按物品真实模式算）、悬停显示模式名；不可用模式置灰。 */
    private static final class TerminalModeTabButton extends AbstractWidget {
        /** 一排 Tab 共用的存活状态（模式从这里读，不从构造参数读）。 */
        private final TabRowState state;
        private final int mode;
        private final boolean supported;
        private final Runnable onClick;

        TerminalModeTabButton(final int x,
                              final int y,
                              final TabRowState state,
                              final int mode,
                              final boolean supported,
                              final Component tooltip,
                              final Runnable onClick) {
            super(x, y, TAB_W, TAB_H, tooltip);
            this.state = state;
            this.mode = mode;
            this.supported = supported;
            this.onClick = onClick;
        }

        @Override
        public void onClick(final double mouseX, final double mouseY) {
            onClick.run();
        }

        /** 懒加载图标物品（客户端注册完成后才安全）。 */
        private static ItemStack iconFor(final int mode) {
            return switch (mode) {
                case 0 -> new ItemStack(Items.CHEST);                 // 合成终端
                case 1 -> new ItemStack(Items.CRAFTING_TABLE);        // 样板终端
                case 2 -> new ItemStack(Blocks.INSTANCE.getAutocrafterManager().getDefault().asItem());
                case 3 -> new ItemStack(Blocks.INSTANCE.getAutocraftingMonitor().getDefault().asItem());
                case 4 -> new ItemStack(cretae.cookiewyq.rs_create_compat.RS_Create_Compat
                    .SEQUENCE_PATTERN_TERMINAL_BLOCK.get().asItem()); // 序列装配终端方块
                case 5 -> new ItemStack(cretae.cookiewyq.rs_create_compat.RS_Create_Compat
                    .UNIT_PATTERN_MANAGER_BLOCK.get().asItem()); // 单元样板管理舱方块
                // 第 101 轮「能耗监视」：图标用 RS 控制器方块（网络供电核心，语义最贴「耗电」）。
                // 用户原话「图标我暂时没想好」⇒ 先用语义最准的原版方块，之后要换只改这一行。
                case 6 -> new ItemStack(Blocks.INSTANCE.getController().getDefault().asItem());
                // 第 131 轮第 8 档「发送」：用原版漏斗（「把东西送出去」的原版符号，且不依赖任何
                // 本模组方块的注册顺序 —— 这颗图标必须恒非空，空图标 = 玩家看到一颗看不见的 Tab）。
                case 7 -> new ItemStack(Items.HOPPER);
                // 兜底给一个非空图标：未知模式号也不画空 Tab（数组长度与 MODE_COUNT 同步，正常到不了）
                default -> new ItemStack(Items.CHEST);
            };
        }

        /**
         * 图标物品的<b>会话级缓存</b>（下标 = 模式号）。
         * <p><b>为什么必须缓存</b>：{@link #renderWidget} 每帧对每个 Tab 都要一次图标，而
         * {@link #iconFor} 每次都会新建 {@link ItemStack} 并对模式 2/3/4/5/6 走一遍
         * {@code Blocks.INSTANCE.getX().getDefault()}（方块状态查询）。一排 7 个 Tab × 60 fps
         * 就是每秒几百次纯垃圾分配 —— 与「打开慢」无关，但属于同一类「每次都用就重建」的写法。
         * 图标只是常量展示品，不参与任何判定，缓存它没有语义代价。</p>
         * <p>数组长度<b>直接取</b> {@link AdvancedRemoteTerminalItem#MODE_COUNT}：
         * 再加模式时不需要在这里改第二个魔法数字（长度不一致会导致新 Tab 图标恒空）。</p>
         */
        private static final ItemStack[] ICONS = new ItemStack[AdvancedRemoteTerminalItem.MODE_COUNT];

        /** 取该模式要画的图标（首次使用时建好，之后直接复用同一份常量栈）。 */
        private static ItemStack iconCached(final int mode) {
            if (mode < 0 || mode >= ICONS.length) {
                return ItemStack.EMPTY;
            }
            final ItemStack cached = ICONS[mode];
            if (cached != null) {
                return cached;
            }
            final ItemStack created = iconFor(mode);
            ICONS[mode] = created;
            return created;
        }

        @Override
        protected void renderWidget(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
            // 【第 100 轮】绑定网络没电 ⇒ 这一排<b>所有</b> Tab 一律画成禁用态（不区分模式枚举）：
            // 置灰 + 不画选中白框 + 悬停换成「网络没电」说明。判据只有一条（TerminalPowerState），
            // 因此「只灰某几个模式」这种分叉在结构上不存在。
            final boolean locked = TerminalPowerState.isLocked();
            // 高亮 = 物品当前模式的函数（不是构造期快照）：服务端迟到的同步下一帧就纠正
            final boolean selected = !locked && supported && state.mode() == mode;
            final int x = getX();
            final int y = getY();
            // 背景：不可用 / 网络没电 = 深灰；选中 = 白色边框 + 亮底；未选中 = 深色底
            if (locked || !supported) {
                graphics.fill(x, y, x + TAB_W, y + TAB_H, 0x40202020);
            } else {
                graphics.fill(x, y, x + TAB_W, y + TAB_H, selected ? 0x80303030 : 0x60101010);
            }
            if (selected) {
                graphics.fill(x, y, x + TAB_W, y + 1, 0xFFFFFFFF);
                graphics.fill(x, y + TAB_H - 1, x + TAB_W, y + TAB_H, 0xFFFFFFFF);
                graphics.fill(x, y, x + 1, y + TAB_H, 0xFFFFFFFF);
                graphics.fill(x + TAB_W - 1, y, x + TAB_W, y + TAB_H, 0xFFFFFFFF);
            }
            // 方块图标（居中 16x16）：走会话级缓存，不再每帧新建 ItemStack
            final ItemStack icon = iconCached(mode);
            if (!icon.isEmpty()) {
                graphics.renderItem(icon, x + (TAB_W - 16) / 2, y + (TAB_H - 16) / 2);
            }
            // 悬停提示：没电时给「存量 / 容量 + 恢复供电后才能切换」，有电时仍是模式名
            if (isHoveredOrFocused()) {
                graphics.renderTooltip(Minecraft.getInstance().font,
                    locked ? lockedTooltip() : getMessage(), mouseX, mouseY);
            }
        }

        /** 没电时所有 Tab 共用的说明（存量 / 容量来自服务端快照；与服务端拒绝切换的提示同一句）。 */
        private static Component lockedTooltip() {
            final TerminalPowerState.Snapshot snapshot = TerminalPowerState.snapshot();
            return TerminalNetworkPower.noPowerText(snapshot.stored(), snapshot.capacity());
        }

        @Override
        protected void updateWidgetNarration(final NarrationElementOutput narrationElementOutput) {
        }
    }
}
