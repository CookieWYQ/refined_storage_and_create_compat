package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import cretae.cookiewyq.rs_create_compat.support.RsccTerminalLocator;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * C2S：玩家按下「打开高级远程多功能终端」快捷键时发送（<b>空负载</b>，不带任何引用）。
 *
 * <p><b>为什么不复用 RS 原生的 {@code use_slot_referenced_item}</b>：那条链路要求客户端先找出
 * <b>唯一一个</b>引用（{@code CompositeSlotReferenceProvider#findForUse}：0 个报「找不到」、
 * &gt;1 个报「重复」并拒绝），而本模组终端可能同时出现在背包与 Curios 饰品槽里，
 * 一旦多于一台就直接打不开（玩家实测回归：生存版打不开、只有创造版能开）。
 * 这里改成「客户端只喊一声，由服务端按固定优先级自己找」——服务端权威、结果确定、
 * 与 RS 的「唯一性」语义彻底解耦，且<b>多个也不拒绝</b>（取优先级第一台，见 {@link RsccTerminalLocator}）。</p>
 *
 * <h2>第 83 轮：本条链路上补上 ④⑤⑥ 三个观测点</h2>
 * <p>前两轮「按下去没反应」排查的最大成本是<b>不知道断在哪一环</b>：客户端三点在
 * {@code TerminalKeybinds}，服务端这一侧却只有「失败时」才留痕 {@code logOpenFailure}
 * （而它受 5 秒节流、且只覆盖失败）。现在服务端侧也有三点，并且<b>成功也打</b>：</p>
 * <ol>
 *     <li>{@code ④服务端收到} —— 处理体第一句（有它 ⇒ 包到了服务端）；</li>
 *     <li>{@code ⑤开菜单} —— 在 {@link AdvancedRemoteTerminalItem#openModeScreen} 入口（有它 ⇒ 真的在开）；</li>
 *     <li>{@code ⑥打开后校验} —— {@code containerMenu} 是否真的换成了某个容器菜单。</li>
 * </ol>
 * <p><b>不受 {@code devLogs} 控制</b>：一次按键只有一轮、三个点各一条，结构上不可能刷屏；
 * 另外这里仍加了一道 {@value #LOG_WINDOW_MS} 毫秒的全局窗口（防被改过的客户端刷包把日志打爆），
 * 被压掉的条数会附在下一条后面 —— 既不刷屏、也不静默。</p>
 *
 * <h2>打开后<b>必须验一次</b>（把「静默没反应」变成可观测结果）</h2>
 * <p>用户报告的「按下去完全没反应、没有红字」在服务端有两条<b>完全静默</b>的出口：</p>
 * <ol>
 *     <li>原版 {@code ServerPlayer#openMenu}：{@code createMenu} 返回 null 时直接
 *     {@code return OptionalInt.empty()}（只有旁观者才会看到提示文本）；</li>
 *     <li>菜单开出来之后被服务端下一 tick 关掉：{@code ServerPlayer#tick} 里
 *     {@code if (!containerMenu.stillValid(this)) closeContainer();}，而
 *     {@code closeContainer()} → 客户端 {@code LocalPlayer#clientSideCloseContainer()} →
 *     {@code Minecraft.setScreen(null)}，玩家眼里同样是「什么都没发生」。</li>
 * </ol>
 * <p>两条都是「服务端自己知道、玩家不知道」。因此这里在调用打开之后做一次<b>事后校验</b>：
 * {@code player.containerMenu == player.inventoryMenu} 就说明这一次按键没有开出任何界面，
 * 立刻打一条节流 ERROR（带模式号与堆栈，复用 {@code RsccTerminalLocator#logOpenFailure} 的 5 秒节流）
 * 并给玩家一条界面提示。<b>这不是定时刷新</b>：只在本次请求之后判定一次，不会补发打开请求。</p>
 */
public record OpenAdvancedRemoteTerminalPacket() implements CustomPacketPayload {
    /** 空负载包只需一个实例（{@link StreamCodec#unit} 复用同一对象）。 */
    public static final OpenAdvancedRemoteTerminalPacket INSTANCE = new OpenAdvancedRemoteTerminalPacket();
    public static final Type<OpenAdvancedRemoteTerminalPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "open_advanced_remote_terminal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAdvancedRemoteTerminalPacket> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAdvancedRemoteTerminalPacket.class);

    /** 服务端三点日志的全局窗口（毫秒）：正常一次按键一轮，只有刷包才会被压。 */
    private static final long LOG_WINDOW_MS = 200L;

    private static long lastLogAt;
    private static int logSuppressed;

    /**
     * 服务端处理：按固定优先级查找第一台高级远程多功能终端并打开其当前模式界面。
     * <p>一台都没有 → 只回一条红字短提示（在动作栏显示，一次按键只发一条，不会刷屏）。</p>
     */
    public static void handle(final OpenAdvancedRemoteTerminalPacket packet, final ServerPlayer player) {
        // ④ 服务端收到：这是「包真的到了服务端」的唯一凭据。客户端 ③ 有它而没有它 ⇒ 问题在连接 / 注册。
        openLog(LOGGER, "④服务端收到打开请求 player={}", player.getGameProfile().getName());
        final Optional<RsccTerminalLocator.Location> located = RsccTerminalLocator.locate(player);
        if (located.isEmpty() || !(located.get().stack().getItem() instanceof AdvancedRemoteTerminalItem item)) {
            // ④-失败：包到了、但身上一台终端都找不到（RsccTerminalLocator 内部另有节流日志）
            openLog(LOGGER, "④未定位到终端（locate 为空或不是本模组终端）→ 回红字");
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.not_found")
                    .withStyle(ChatFormatting.RED),
                true);
            return;
        }
        // 打开前先记下模式：后面无论成功 / 失败，打的都是「这一次请求本来要开的那个界面」
        final int mode = AdvancedRemoteTerminalItem.getMode(located.get().stack());
        // 服务端权威：物品内部再按引用取一次存活栈并打开当前模式界面（手持右键走的是同一个入口）
        item.useFromShortcut(player, located.get().slotReference());
        // 【第 82 轮｜绝不静默】打开成功与否的唯一判据是「服务端真的把 containerMenu 从玩家背包
        // 换成了某个容器菜单」。原版 `ServerPlayer#openMenu` 在 `createMenu` 返回 null 时是
        // 静默 return（只有旁观者会得到提示文本），而「菜单开出来又被服务端 tick 关掉」
        // 也是静默的 —— 两者在玩家眼里完全一样：**没有界面、没有红字**。
        // 因此这里补一次事后校验：没开出菜单 ⇒ 立刻留一条节流 ERROR（带模式与堆栈）+ 一条界面提示。
        // 这不是「定时刷新」：只在这一次按键请求之后判定一次，**不发第二次打开请求**。
        final boolean opened = player.containerMenu != player.inventoryMenu;
        // ⑥ 打开后校验：成功也打（否则「六点齐全」这件事无法从日志上确认）
        openLog(LOGGER, "⑥打开后校验 mode={} containerMenu={} 结果={}",
            mode, player.containerMenu.getClass().getSimpleName(), opened ? "已打开" : "未打开");
        if (!opened) {
            RsccTerminalLocator.logOpenFailure(player, mode,
                new IllegalStateException("terminal open produced no container menu, mode=" + mode));
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_failed"),
                true);
            return;
        }
        // 成就触发点：这一位玩家确实用快捷键把终端打开了（并补发一次当前模式，见 RsccAdvancements）
        RsccAdvancements.onTerminalOpenedByHotkey(player);
        RsccAdvancements.onTerminalMode(player, mode);
    }

    /**
     * 服务端三点日志（④⑤⑥ 同口径）：不受 {@code devLogs} 控制，但受 {@value #LOG_WINDOW_MS} 毫秒
     * 全局窗口节流，被压掉的条数会在下一条上汇总 —— 正常按键永远看不到那句汇总。
     */
    private static void openLog(final Logger logger, final String format, final Object... args) {
        final long now = System.currentTimeMillis();
        if (now - lastLogAt < LOG_WINDOW_MS) {
            logSuppressed++;
            return;
        }
        lastLogAt = now;
        final String suffix = logSuppressed == 0 ? "" : "（上一窗口压掉 " + logSuppressed + " 条同类）";
        logSuppressed = 0;
        logger.info("[rscc-terminal-open] " + String.format(format, args) + suffix);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
