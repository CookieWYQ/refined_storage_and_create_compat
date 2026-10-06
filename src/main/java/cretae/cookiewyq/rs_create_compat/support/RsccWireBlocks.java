package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.exporter.ExporterBlock;
import com.refinedmods.refinedstorage.common.importer.ImporterBlock;
import com.refinedmods.refinedstorage.common.networking.CableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「可作为导线穿行」的方块集合（输出总线 / 序列执行仓两侧的连接检测共用同一口径）。
 *
 * <p><b>为什么需要它</b>：早期实现里 BFS 只允许穿过 RS 线缆方块 {@link CableBlock}，
 * 于是「输出总线紧贴另一台输出总线 / 输入总线」时判不出连接（必须再补一段线缆才行）。
 * 用户明确要求：<b>凡是从序列执行舱分支出来的路径都要能被识别</b>，输出总线 / 输入总线本身
 * 也是这条路径的一部分。</p>
 *
 * <p><b>最终定义</b>（真实 RS 方块类名，已逐一核对 <code>local_src/rs_src</code> 与本工程依赖的 RS jar）：</p>
 * <ul>
 *     <li>RS 线缆：方块类 {@code com.refinedmods.refinedstorage.common.networking.CableBlock}
 *     （所有颜色都是同一个类）；</li>
 *     <li>输出总线：方块类 {@code com.refinedmods.refinedstorage.common.exporter.ExporterBlock}
 *     （其方块实体才是 {@code AbstractExporterBlockEntity}）；</li>
 *     <li>输入总线：方块类 {@code com.refinedmods.refinedstorage.common.importer.ImporterBlock}。</li>
 * </ul>
 *
 * <p><b>刻意排除</b>：外部存储总线（{@code ExternalStorageBlock}）、构造器 / 破坏器
 * （{@code AbstractConstructorDestructorBlock}）虽然同样继承 RS 的 {@code AbstractDirectionalCableBlock}
 * 且是「线缆外形」，但它们直接与容器 / 世界交互，语义上是<b>机器</b>；按用户「不允许穿行机器 / 容器」
 * 的硬约束，一律<b>不可穿行</b>。序列执行仓、自动合成仓等非线缆方块同样不可穿行。</p>
 *
 * <p><b>2026-09-13 变更（递归崩溃修复）</b>：判定从「取方块实体看类型」改为<b>纯方块状态判定</b>
 * （{@code BlockState#getBlock} 的 {@code instanceof}）。原因：{@code Level#getBlockEntity} 会强制加载 /
 * 初始化相邻方块实体，搜索阶段调用它会触发邻块初始化并形成无限递归（StackOverflowError）。
 * 方块状态与方块实体类型在本集合里是一一对应的（每个方块类只创建一种方块实体），
 * 因此判定结果完全等价，却没有任何副作用。</p>
 */
public final class RsccWireBlocks {
    private RsccWireBlocks() {
    }

    /**
     * 依据方块状态判定是否可穿行（同时也是「伪装框架 / 分隔框架能不能套在这一格上」的线缆族判据，
     * 见 {@link SeparationFrameGuard#isSheathable}）。
     * <p><b>绝不调用 {@code getBlockEntity}</b>：只读方块状态 —— 这是避免「探测邻块时强制初始化邻块」
     * 递归崩溃的关键。坐标未加载时调用方应先自行 {@code isLoaded} 判定（本方法只认状态）。
     *
     * <p><b>口径 = 类别判定（用户要求的修正，不是方块 id 清单）</b>：判据全部落在 RS 的<b>方块类</b>上，
     * 因此同一族的全部变体自动覆盖，且不需要一个一个列方块：</p>
     * <ul>
     *     <li>{@code CableBlock}：<b>16 种颜色的线缆共用一个类</b>（颜色只是方块注册项，不是子类），
     *     所以「各颜色线缆」一次全覆盖；</li>
     *     <li>{@code ExporterBlock}：RS 输出总线（其方块实体为 {@code AbstractExporterBlockEntity}）；</li>
     *     <li>{@code ImporterBlock}：RS 输入总线。</li>
     * </ul>
     * <p><b>可扩展</b>：RS / 其它模组将来新增同族方块时，只需在这里补一条 {@code instanceof}
     * （同时说明它是否也属于「可穿行」），<b>不需要</b>维护任何方块 id / ResourceLocation 清单。</p>
     */
    public static boolean isWire(final BlockState state) {
        if (state == null) {
            return false;
        }
        final Block block = state.getBlock();
        return block instanceof CableBlock || block instanceof ExporterBlock || block instanceof ImporterBlock;
    }

    /**
     * 该方块状态是否是「输出总线」方块（本模组延长模式的宿主）。
     * <p>只有它对应的方块实体才实现 {@link RsccExporterExecutorMode}；执行舱侧 BFS 用它做门控，
     * 从而<b>只对输出总线取方块实体</b>，不对无关的机器 / 容器 / 执行舱触发方块实体加载。
     */
    public static boolean isExporterBus(final BlockState state) {
        return state != null && state.getBlock() instanceof ExporterBlock;
    }

    /**
     * 该方块状态是否是「输入总线」方块（本模组「延长型输入」的宿主）。
     * <p>只有它对应的方块实体才实现 {@link RsccImporterExecutorMode}；邻块失效监听器用它做门控，
     * 从而<b>只对输入总线取方块实体</b>，不对无关的机器 / 容器 / 执行舱触发方块实体加载 ——
     * 与 {@link #isExporterBus} 同一个理由、同一套做法。</p>
     */
    public static boolean isImporterBus(final BlockState state) {
        return state != null && state.getBlock() instanceof ImporterBlock;
    }
}
