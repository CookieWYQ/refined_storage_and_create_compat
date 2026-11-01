package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.network.AssemblyMachineCandidatesPacket;
import cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket;
import cretae.cookiewyq.rs_create_compat.network.ChargerHighlightPacket;
import cretae.cookiewyq.rs_create_compat.network.ClientPayloadHooks;
import cretae.cookiewyq.rs_create_compat.network.CollectionBlockedSnapshot;
import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import cretae.cookiewyq.rs_create_compat.network.RestoreCursorPacket;
import cretae.cookiewyq.rs_create_compat.network.ShowChargerRangePacket;
import cretae.cookiewyq.rs_create_compat.network.ShowCollectionRangePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncAdvKeeperConfigPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncBusInterferencePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberBindingPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberUnitsPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChargerFilterPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncCollectionMarkersPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncIntermediateReusePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncQuantityFluidMarkerPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncReceiverConfigPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncRequesterConfigPacket;
import cretae.cookiewyq.rs_create_compat.network.SyncShortageModePacket;
import cretae.cookiewyq.rs_create_compat.network.TerminalPowerPacket;
import cretae.cookiewyq.rs_create_compat.client.screen.AdvancedQuantityKeeperScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.ChamberFaceConfigScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.ChamberUnitsSummaryScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.CollectionCacheScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.CollectionMarkerConfigScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.NetworkResourceReceiverScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.NetworkResourceRequesterScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.QuantityKeeperScreen;
import cretae.cookiewyq.rs_create_compat.client.screen.RangeChargerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * {@link ClientPayloadHooks.Sink} 的<b>客户端实现</b>：本模组所有「S2C 包到达客户端之后要做的界面 /
 * 渲染 / 窗口动作」都集中在这里。
 *
 * <h2>为什么要把这些处理体从 {@code network} 包搬过来</h2>
 * <p>{@code network} 包里的载荷类在<b>注册期</b>就会被专用服务端加载并链接（见
 * {@link ClientPayloadHooks} 的类文档）：只要那些处理体里出现客户端类型，服务端就可能因为
 * 链接期校验去加载 {@code net.minecraft.client.*} 而抛 {@code NoClassDefFoundError}。
 * 本类位于 {@code client} 包，<b>只有客户端会加载它</b>（{@code ClientInit} 在
 * {@code Dist.CLIENT} 的初始化事件里挂上），因此这里可以自由使用客户端类型。</p>
 *
 * <h2>线程</h2>
 * <p>全部方法都由载荷类在 {@code ctx.enqueueWork(...)} 里调用 —— 即<b>客户端主线程</b>，
 * 与搬迁之前逐字一致（原来那些处理体也都写在 {@code enqueueWork} 的 lambda 里）。</p>
 */
public final class ClientPayloadSink implements ClientPayloadHooks.Sink {
    @Override
    public void restoreCursor(final RestoreCursorPacket packet) {
        // 仿 Universal-Grid 的 SetCursorPosWindowPacket：直接写 GLFW 原生窗口坐标
        GLFW.glfwSetCursorPos(Minecraft.getInstance().getWindow().getWindow(),
            packet.cursorX(), packet.cursorY());
    }

    @Override
    public void openMachineSelect(final AssemblyMachineCandidatesPacket packet) {
        AssemblyAlertsClient.openMachineSelect(packet.taskId(), packet.stepIndex(), packet.recipeType(),
            packet.currentMachineName(), packet.candidates());
    }

    @Override
    public void openPatternRebind(final AssemblyPatternRebindOpenPacket packet) {
        AssemblyPatternRebindClient.open(packet);
    }

    @Override
    public void showCompletionBanner(final CompletionBannerPayload payload) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Minecraft.getInstance().getToasts().addToast(new CompatCompletionToast(payload.rows()));
    }

    @Override
    public void syncAdvKeeperConfig(final SyncAdvKeeperConfigPacket packet) {
        if (Minecraft.getInstance().screen
            instanceof AdvancedQuantityKeeperScreen screen
            && screen.getMenu().containerId == packet.containerId()) {
            screen.setSyncedConfigs(packet.configs());
        }
    }

    @Override
    public void syncAssemblyAlerts(final SyncAssemblyAlertsPacket packet) {
        AssemblyAlertsClient.setAlerts(packet.alerts());
    }

    @Override
    public void syncBusInterference(final SyncBusInterferencePacket packet) {
        BusInterferenceOverlay.apply(packet.containerId(), packet.disabled(), packet.truncated(),
            packet.reachableCount(), packet.cluster(), packet.chambers());
    }

    @Override
    public void syncChamberBinding(final SyncChamberBindingPacket packet) {
        // 面配置子界面打开时，用它把六个面的权威模式 + 输出模式刷新回界面
        if (Minecraft.getInstance().screen instanceof ChamberFaceConfigScreen screen) {
            screen.onServerSync(packet.faceModes(), packet.outputMode());
        }
    }

    @Override
    public void syncChamberUnits(final SyncChamberUnitsPacket packet) {
        // 汇总子界面打开时，把最新快照（载荷类刚写进只读镜像的那一份）写回界面展示
        if (Minecraft.getInstance().screen instanceof ChamberUnitsSummaryScreen screen) {
            screen.onServerSync(SyncChamberUnitsPacket.getLastReceived(),
                SyncChamberUnitsPacket.getLastReceivedPage());
        }
    }

    @Override
    public void syncCollectionMarkers(final SyncCollectionMarkersPacket packet) {
        final CollectionBlockedSnapshot blocked =
            packet.blocked() == null ? CollectionBlockedSnapshot.EMPTY : packet.blocked();
        final Screen current = Minecraft.getInstance().screen;
        if (current instanceof CollectionCacheScreen screen) {
            screen.setMarkerEntries(packet.windowStart(), packet.entries(), packet.destroyFlags());
            screen.setCacheFluidEntries(packet.fluidCache());
            screen.setBlocked(blocked.items(), blocked.fluids(), blocked.itemTags(), blocked.fluidTags());
        } else if (current instanceof CollectionMarkerConfigScreen child) {
            // 「匹配条目配置」子窗口开在最前时，主界面收不到包 —— 路由给它做三件事：
            // ① 刷新子窗口自己的「阻塞」开关为服务端权威值；② 转交父界面，主界面红色标识一起同步；
            // ③ 把匹配窗口（含「直接销毁」标志）也转交父界面，关闭子窗口后主界面显示不落后。
            child.applyBlockedSync(blocked.items(), blocked.fluids(),
                blocked.itemTags(), blocked.fluidTags());
            child.applyMarkerWindowSync(packet.windowStart(), packet.entries(), packet.destroyFlags());
        }
    }

    @Override
    public void syncIntermediateReuse(final SyncIntermediateReusePacket packet) {
        IntermediateReuseClient.set(packet.enabled());
    }

    @Override
    public void syncQuantityFluidMarker(final SyncQuantityFluidMarkerPacket packet) {
        if (Minecraft.getInstance().screen
            instanceof QuantityKeeperScreen screen
            && screen.getMenu().containerId == packet.containerId()) {
            screen.setFluidMarker(packet.id(), packet.nbt());
        }
    }

    @Override
    public void syncShortageMode(final SyncShortageModePacket packet) {
        ShortageModeClient.set(packet.mode());
    }

    @Override
    public void syncNetworkResourceReceiver(final SyncReceiverConfigPacket packet) {
        // 只写回「正开着、且菜单 id 对得上」的那一个界面：别的界面 / 已关闭的界面一律不动
        if (Minecraft.getInstance().screen
            instanceof NetworkResourceReceiverScreen screen
            && screen.getMenu().containerId == packet.containerId()) {
            screen.onServerSync(packet);
        }
    }

    @Override
    public void syncNetworkResourceRequester(final SyncRequesterConfigPacket packet) {
        if (Minecraft.getInstance().screen
            instanceof NetworkResourceRequesterScreen screen
            && screen.getMenu().containerId == packet.containerId()) {
            screen.onServerSync(packet);
        }
    }

    @Override
    public void showChargerRange(final ShowChargerRangePacket packet) {
        // 只画「正开着、且菜单 id 对得上」的那一个界面请求的框：别的界面 / 已关闭的界面一律不动。
        if (Minecraft.getInstance().screen
            instanceof RangeChargerScreen screen
            && screen.getMenu().containerId == packet.containerId()) {
            screen.showChargerRange(packet);
        }
    }

    @Override
    public void showCollectionRange(final ShowCollectionRangePacket packet) {
        // 基础版与高级版界面（高级版继承基础版）共用同一个落点：只画「正开着、且菜单 id 对得上」
        // 的那一个界面请求的框。几何全部来自本包（服务端算好），界面不做任何半径换算。
        if (Minecraft.getInstance().screen
            instanceof CollectionCacheScreen screen
            && screen.getMenu().containerId == packet.containerId()) {
            screen.showCollectionRange(packet);
        }
    }

    @Override
    public void syncChargerHighlight(final ChargerHighlightPacket packet) {
        // 充电目标高亮的全部状态都在客户端（纯渲染）；集合变化才重建，见 ChargerTargetHighlight。
        ChargerTargetHighlight.accept(packet);
    }

    @Override
    public void openChargerFilter(final SyncChargerFilterPacket packet) {
        // 过滤界面自己决定「就地刷新」还是「新建」：已完成一次打开请求 → 服务端回快照这条链路，
        // 而 setScreen 只能在客户端主线程做（本方法已被包处理体 enqueueWork 到主线程）。
        cretae.cookiewyq.rs_create_compat.client.screen.ChargerFilterScreen.openOrRefresh(packet);
    }

    @Override
    public void updateTerminalPower(final TerminalPowerPacket packet) {
        // 高级远程多功能终端：绑定网络电量快照 → 只写只读镜像；
        // Tab 行（TerminalModeTabOverlay）每帧读它决定「全灰不可点 / 正常」。
        // 客户端不推算、不看终端自身电量（服务端已把 bound / powered 折叠成权威值）。
        TerminalPowerState.accept(packet);
    }

    @Override
    public void syncDecompositionDisplay(final cretae.cookiewyq.rs_create_compat.network
                                             .SyncDecompositionDisplayPacket packet) {
        // 降解室发电机（第 129 轮）：JEI 的「降解发电」分类页只读这份镜像。
        // 只写数据、不碰任何界面 —— JEI 在它自己的重载时机重新读（进服时本包先到，赶得上同一次重建）。
        cretae.cookiewyq.rs_create_compat.support.DecompositionDisplayCache.accept(packet.entries());
    }
}
