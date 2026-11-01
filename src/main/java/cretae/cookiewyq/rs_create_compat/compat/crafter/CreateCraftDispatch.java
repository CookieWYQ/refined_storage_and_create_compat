package cretae.cookiewyq.rs_create_compat.compat.crafter;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * RS 自动合成仓 → Create 机器 的<b>派发入口</b>（服务端限定）。
 *
 * <h2>为什么接管点在网络节点的 {@code accept}，而不是自动合成仓自己的 {@code accept}</h2>
 * <ol>
 *     <li><b>不能走公开 sink 工厂</b>：工厂产物会被塞进
 *     {@code CompositePatternProviderExternalPatternSink}，它对<b>每个</b> sink 传<b>完整</b>资源集
 *     （build/rssrc/.../common/autocrafting/CompositePatternProviderExternalPatternSink.java:35-47），
 *     而通用 item sink 对任何 {@code ItemResource} 都 {@code applies = true}
 *     （build/rssrc/.../neoforge/autocrafting/ItemHandlerExternalPatternProviderSink.java:110-113）。
 *     两边都会插 ⇒ 必然双插；且 composite 遇到任一 REJECTED 立刻返回（同文件 :37-42），
 *     RS 的 {@code ExternalTaskPattern} 会为每次迭代打一条 warn（.../task/ExternalTaskPattern.java:243-245）
 *     ⇒ 刷屏。这条结论写在这里，防止下一个人再「优化」回工厂路线。</li>
 *     <li><b>不能接管在自动合成仓的 {@code accept}</b>：本模组既有的内部存储 mixin
 *     （{@code mixin/AutocrafterStorageMixin} 的 {@code rscc$storeInInternalStorage}）也注入在同一个
 *     方法 HEAD，且默认配置下是开启的。两处 HEAD 注入的执行顺序由 mixin 注册顺序决定，
 *     依赖它就会「换个加载顺序行为就变」。把接管点提到更外层的
 *     {@code PatternProviderNetworkNode#accept}（.../impl/node/patternprovider/PatternProviderNetworkNode.java:154-162）
 *     后，顺序问题从根上消失：本类按「正对的机器类型」直接决定走哪条路，
 *     根本不进入那个 HEAD 注入点（见下节）。</li>
 * </ol>
 *
 * <h2>与「自动合成仓内部存储」的分工（本轮的定向放行决策）</h2>
 * <p>当自动合成仓<b>正对着</b>的是 Create 动力合成器 / 搅拌盆（或其搅拌机）时，本模组<b>无视内部存储</b>，
 * 直接把输入按位置直发进机器，并打一条节流日志说明「本次绕过内部存储」。理由：</p>
 * <ol>
 *     <li>{@code Config.autocrafterStorageEnabled} 默认就是 {@code true}，而且<b>已有玩家的配置文件里这个键
 *     早已写成 true</b>（NeoForge 只在键缺失时写默认值）⇒ 改默认值对存量用户完全无效，等于交付一个
 *     默认不生效的功能。</li>
 *     <li>让用户自己去敲 {@code /rs_create_compat autocrafter storage off} 不现实；而且内部存储是
 *     「把输入吞进仓里存起来」的语义，与「喂给正对着的机器」在物理上互斥——一旦吞进去，这台仓
 *     就再也不会把料推到正对的那台机器上。</li>
 *     <li>所以按「正对的机器类型」做<b>定向放行</b>是最小且可解释的语义：内部存储是留给
 *     「不直接喂机器」的用法的优化，正对 Create 机器时它没有意义。</li>
 * </ol>
 * <p>反之，正对着的<b>不是</b> Create 机器（或压根没连机器）时，本类立刻 {@code return null} 完全放行，
 * 内部存储 mixin 按今天的语义接管——<b>除「正对 Create 机器」这一种情况外，既有行为一字不改</b>。</p>
 *
 * <h2>锁模式（如实说明，不静默）</h2>
 * <p>Create 特化路径上，RS 原版 {@code AutocrafterBlockEntity#accept} 里的
 * {@code updateLockedAfterAccept(...)} / {@code updateLocked()}（.../AutocrafterBlockEntity.java:577-600）
 * 不会执行，因为那是 private 方法、本模组不反射也不越权访问。因此：</p>
 * <ul>
 *     <li>{@code locked} 已经是 true 时（红石高低电平锁等由原逻辑每 tick 计算）<b>会被尊重</b>：
 *     本类直接返回 {@code LOCKED}，既不喂料也不吞料；</li>
 *     <li>但「接管成功后置位」不会发生 ⇒ 在 Create 特化路径上，
 *     {@code LOCK_UNTIL_REDSTONE_PULSE_RECEIVED} / {@code LOCK_UNTIL_ALL_OUTPUTS_ARE_RECEIVED} /
 *     {@code LOCK_UNTIL_CONNECTED_MACHINE_IS_EMPTY} 三种锁模式<b>不会生效（等价 NEVER）</b>。
 *     实践中「机器忙」仍由本类的 SIMULATE 把关：动力合成器在 {@code phase != IDLE} 时
 *     {@code Inventory#insertItem} 会拒绝（build/createsrc/.../MechanicalCrafterBlockEntity.java:76-86），
 *     因此不会往正在装配的阵列里塞料。</li>
 * </ul>
 *
 * <h2>失败路径</h2>
 * <p>返回 {@code null} = 「本模组不适用，请走原逻辑」，调用方（mixin）<b>不设置返回值</b>，
 * 于是 RS 原版 sink 链照常执行——不吞料、不复制。返回 {@code REJECTED} 只用于
 * 「确认是 Create 目标、配方也对得上，但机器现在收不下」的情形，语义等同原版机器忙。</p>
 */
public final class CreateCraftDispatch {
    private CreateCraftDispatch() {
    }

    /**
     * @return {@code null} = 不适用（交回原逻辑）；否则就是本次 {@code accept} 的结果
     */
    @Nullable
    public static ExternalPatternSink.Result tryAccept(final ServerLevel level,
                                                       final AutocrafterBlockEntity binding,
                                                       final Pattern pattern,
                                                       final Collection<ResourceAmount> resources,
                                                       final Action action) {
        if (resources.isEmpty()) {
            return null;
        }
        if (action != Action.SIMULATE && action != Action.EXECUTE) {
            return null;
        }
        // 只处理「链头」：链上其它自动合成仓的 accept 在原逻辑里会被转发到链头，
        // 本模组在更外层接管后必须自己复刻这条转发规则，否则同一批料会被从链中间派发。
        final AutocrafterBlockEntity root = CreateCraftTargets.resolveRoot(level, binding);
        final BlockEntity target = CreateCraftTargets.resolveTarget(level, root);
        if (target == null) {
            return null;
        }
        final boolean mechanicalCrafter = target instanceof MechanicalCrafterBlockEntity;
        final BasinBlockEntity basin = mechanicalCrafter ? null : CreateCraftTargets.resolveBasin(target);
        if (!mechanicalCrafter && basin == null) {
            // 不是 Create 机器：立刻完全放行（return null ⇒ 原逻辑照常跑，内部存储 mixin 按今天的
            // 语义接管）。这一条保证「定向放行」只影响正对 Create 机器的那一种情况。
            return null;
        }
        if (root.getMenuData().locked()) {
            // 已锁定：既不喂料也不吞料，如实告诉 RS「等解锁」（不返回 null —— 那会让内部存储
            // 在锁定期间把料吞进仓里，等于违反了「正对 Create 机器不吞料」）。
            return ExternalPatternSink.Result.LOCKED;
        }
        // 定向放行（本轮拍板的方案 c）：正对 Create 机器时**无视内部存储**。
        // 这里刻意不读 RsccAutocrafterStorage / Config.autocrafterStorageEnabled —— 否则一旦
        // 「内部存储开启」就又会把料吞进仓、永远推不到机器上（见类 javadoc 的三条理由）。
        final ExternalPatternSink.Result result = target instanceof MechanicalCrafterBlockEntity crafter
            ? dispatchMechanicalCrafter(level, root, crafter, pattern, resources, action)
            : dispatchBasin(level, root, basin, resources, action);
        if (result != null) {
            // 只有真的由本模组接住这次派发时才记这条，避免「日志说绕过、实际却回落到内部存储」的误导。
            // 机器名用**注册名**而不是 getBlock().getName().getString()：日志里不需要本地化文本，
            // 也避免「服务端把玩家可见文本提前冻结成字符串」这类本地化审计问题。
            CreateCraftLog.throttled(level, root.getBlockPos(), "bypass-storage",
                "自动合成仓 %s 正对 %s，本次绕过内部存储按位置直发",
                root.getBlockPos(),
                BuiltInRegistries.BLOCK.getKey(target.getBlockState().getBlock()));
        }
        return result;
    }

    @Nullable
    private static ExternalPatternSink.Result dispatchMechanicalCrafter(
        final ServerLevel level,
        final AutocrafterBlockEntity root,
        final MechanicalCrafterBlockEntity crafter,
        @Nullable final Pattern pattern,
        final Collection<ResourceAmount> resources,
        final Action action) {
        final MechanicalCraftPlan plan = MechanicalCraftPlan.find(level, crafter, resources, pattern);
        if (plan == null) {
            return null; // 不是可规划的机械合成（含流体的样板 / 匹配不到图案）⇒ 原语义
        }
        // 先完整 SIMULATE（只读），任何一格不行就拒绝，绝不在 simulate 阶段改机器状态
        if (!plan.canApply()) {
            return ExternalPatternSink.Result.REJECTED;
        }
        if (action == Action.SIMULATE) {
            return ExternalPatternSink.Result.ACCEPTED;
        }
        if (!plan.apply()) {
            return ExternalPatternSink.Result.REJECTED;
        }
        OutputReclaim.registerCraft(level, root.getBlockPos(), plan.members(),
            plan.mainResult(), plan.expectedOutputs());
        return ExternalPatternSink.Result.ACCEPTED;
    }

    @Nullable
    private static ExternalPatternSink.Result dispatchBasin(
        final ServerLevel level,
        final AutocrafterBlockEntity root,
        final BasinBlockEntity basin,
        final Collection<ResourceAmount> resources,
        final Action action) {
        if (!BasinTargets.isApplicable(resources)) {
            return null; // 未知资源类型 ⇒ 原语义
        }
        if (!BasinTargets.canAcceptAll(basin, resources)) {
            return ExternalPatternSink.Result.REJECTED;
        }
        if (action == Action.SIMULATE) {
            return ExternalPatternSink.Result.ACCEPTED;
        }
        if (!BasinTargets.acceptAll(basin, resources)) {
            return ExternalPatternSink.Result.REJECTED;
        }
        OutputReclaim.registerBasinActivity(level, root.getBlockPos());
        return ExternalPatternSink.Result.ACCEPTED;
    }
}
