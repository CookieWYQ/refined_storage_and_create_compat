package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.EnergyAuditScreen;
import cretae.cookiewyq.rs_create_compat.network.EnergyAuditClientBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 能耗监视的<b>客户端接线</b>（自注册，<b>不改 {@code ClientInit}</b>）。
 *
 * <h2>为什么单独一个类</h2>
 * <p>{@code ClientInit.java} 是本轮多条并行线的公共写域（电量 Tab / 界面注册 / 模型…），
 * 往里加一行 {@code install} 会互相覆盖。这个类挂在同一个 MOD 总线、同一个
 * {@link FMLClientSetupEvent} 上，只做一件事：把客户端实现挂到
 * {@link EnergyAuditClientBridge}（专服安全的间接层）。</p>
 *
 * <h2>为什么这里可以安全 import 客户端类</h2>
 * <p>本类标了 {@code Dist.CLIENT} ⇒ 专用服务端<b>永远不会加载</b>这个类（NeoForge 按 dist 过滤
 * {@code @EventBusSubscriber}），因此这里出现 {@link EnergyAuditScreen} 是安全的；
 * 而载荷类 {@code SyncEnergyAuditPacket} 里只有对 {@link EnergyAuditClientBridge} 的
 * {@code default} 空实现的调用，链接期不会触碰任何 {@code net.minecraft.client.*} 类型。</p>
 *
 * <h2>为什么用内部实现类而不是方法引用</h2>
 * <p>{@link EnergyAuditClientBridge.Sink} 的方法都是 {@code default} 空实现 —— 这是「服务端调用也安全」
 * 的前提，但这样的接口<b>不是函数式接口</b>（没有抽象方法），因此不能写成 {@code Screen::openOrRefresh}
 * 那种方法引用。这里用与 {@code ClientInit} 中 {@code new ClientPayloadSink()} 完全同款的内部实现类；
 * 方法体里出现 {@link EnergyAuditScreen} 是安全的，因为本类只会在客户端被加载。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@SuppressWarnings({"deprecation", "removal"}) // EventBusSubscriber.bus 在 1.21.1 弃用，但 1.21.1 尚未支持自动推断 bus
public final class EnergyAuditClientSetup {
    private EnergyAuditClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        EnergyAuditClientBridge.install(new EnergyAuditClientSink());
    }

    /** 客户端实现：快照 → 开界面 / 就地刷新（真正的界面代码在 {@link EnergyAuditScreen}）。 */
    private static final class EnergyAuditClientSink implements EnergyAuditClientBridge.Sink {
        @Override
        public void onSnapshot(final cretae.cookiewyq.rs_create_compat.network.SyncEnergyAuditPacket packet) {
            EnergyAuditScreen.openOrRefresh(packet);
        }
    }
}
