package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.external.ExternalStorageProvider;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.externalstorage.ExternalStorageProviderFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 「把<b>任意</b> {@link ResourceKey} 写进某个方块」的通用通道 —— <b>气体目标容器就在这里找</b>。
 *
 * <h2>为什么这是通用做法（而不是做不到）</h2>
 * <p>RS 2.0 自己就有一套「按资源类型扩展」的方块写入抽象：
 * {@link RefinedStorageApi#getExternalStorageProviderFactories()} 里登记的是
 * {@link ExternalStorageProviderFactory}，每个工厂负责一种平台（物品 / 流体 / 联动模组自己的类型）。
 * RS 原版的<b>外部存储方块</b>就是这样找到相邻容器的（{@code AbstractExternalStorageBlockEntity#loadStorage}：
 * 对目标格 = {@code 本格.relative(朝向)}、能力侧 = {@code 朝向.getOpposite()} 逐个工厂建 provider 再合并）。
 * 本类复刻同一条判据，因此：</p>
 * <ul>
 *     <li><b>没装任何第三方联动</b>：工厂链里只有物品 / 流体，第三方键根本不存在 ⇒ 行为与现在逐字一致；</li>
 *     <li><b>装了官方 Refined Storage - Mekanism Integration</b>：它在初始化时
 *     {@code addExternalStorageProviderFactory(new ChemicalPlatformExternalStorageProviderFactory())}，
 *     于是气体键能在这里自动找到 Mekanism 的化学品容器 —— <b>我们不认识气体，也不需要认识</b>；</li>
 *     <li><b>本类没有一行 {@code mekanism} 引用</b>：只依赖 RS 的公开 API，
 *     所以未装联动时不会 {@code NoClassDefFoundError}（可选依赖的正确写法）。</li>
 * </ul>
 *
 * <h2>诚实边界</h2>
 * <p>如果目标格没有任何 provider 愿意收这条键（比如气体键对着一个普通箱子，或联动没装），
 * {@link #insert} 返回 0 —— 调用方据此判「没有可用通道」，明确提示而不是假装成功。
 * 我们<b>不会</b>去猜「某个模组的容器长什么样」：那是能力系统与联动插件的职责。</p>
 *
 * <p><b>性能</b>：每次 {@link #resolve} 都会new出各工厂的 provider（它们内部持有
 * {@code BlockCapabilityCache}），因此调用方<b>必须缓存</b>结果（见
 * {@code NetworkResourceReceiverBlockEntity#genericTarget()}：按目标格 / 目标方块状态 / 能力侧失效），
 * 绝不能每 tick 重建。</p>
 */
public final class RsccGenericResourceTarget {
    private static final Logger LOGGER = LoggerFactory.getLogger(RsccGenericResourceTarget.class);

    private RsccGenericResourceTarget() {
    }

    /**
     * 某一个方块上的「全部已注册外部存储提供器」。
     * <p>{@link #insert} 与 RS 自己的合并 provider 同口径：按登记顺序试，<b>第一个真正收下的</b>胜出
     * （不做跨 provider 的拼凑 —— 否则同一份资源会被拆进两个不相关的容器，账目无法对）。</p>
     */
    public static final class Target {
        private final List<ExternalStorageProvider> providers;

        private Target(final List<ExternalStorageProvider> providers) {
            this.providers = providers;
        }

        /** 是否一个 provider 都没有（连尝试的必要都没有）。 */
        public boolean isEmpty() {
            return providers.isEmpty();
        }

        /**
         * 往本目标写入（或模拟）{@code amount} 个 {@code resource}，返回<b>真正受理</b>的数量。
         * <p>某个 provider 抛异常只影响它自己（第三方联动的 bug 不该炸我们的机器），继续试下一个。</p>
         */
        public long insert(final ResourceKey resource, final long amount, final Action action) {
            if (resource == null || amount <= 0L) {
                return 0L;
            }
            for (int index = 0; index < providers.size(); index++) {
                try {
                    final long accepted = providers.get(index)
                        .insert(resource, amount, action, Actor.EMPTY);
                    if (accepted > 0L) {
                        return accepted;
                    }
                } catch (RuntimeException exception) {
                    // 开发诊断：走唯一判据（开关关掉时一句都不打；本路径可能每个写入轮次都走到）。
                    if (RsccAssemblyDebug.isEnabled()) {
                        LOGGER.debug("[rs_create_compat] 外部存储提供器拒绝了资源 {}（已跳过该提供器）",
                            resource, exception);
                    }
                }
            }
            return 0L;
        }
    }

    /**
     * 解析某个方块在 {@code side} 一侧的通用写入通道；一个 provider 都建不出来时返回 {@code null}。
     *
     * @param level 服务端世界（能力查询只能在服务端做）
     * @param pos   目标方块坐标
     * @param side  目标方块朝向调用方的那一面（与 RS 外部存储方块同口径）
     */
    @Nullable
    public static Target resolve(final ServerLevel level, final BlockPos pos, final Direction side) {
        final Collection<ExternalStorageProviderFactory> factories =
            RefinedStorageApi.INSTANCE.getExternalStorageProviderFactories();
        if (factories == null || factories.isEmpty()) {
            return null;
        }
        final List<ExternalStorageProvider> providers = new ArrayList<>(factories.size());
        for (final ExternalStorageProviderFactory factory : factories) {
            if (factory == null) {
                continue;
            }
            try {
                final ExternalStorageProvider provider = factory.create(level, pos, side);
                if (provider != null) {
                    providers.add(provider);
                }
            } catch (RuntimeException exception) {
                // 开发诊断：走唯一判据（开关关掉时一句都不打；解析失败可能反复发生）。
                if (RsccAssemblyDebug.isEnabled()) {
                    LOGGER.debug("[rs_create_compat] 外部存储提供器工厂 {} 在 {} 处创建失败（已跳过）",
                        factory, pos, exception);
                }
            }
        }
        return providers.isEmpty() ? null : new Target(List.copyOf(providers));
    }
}
