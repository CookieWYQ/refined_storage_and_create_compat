package cretae.cookiewyq.rs_create_compat.client;

import com.refinedmods.refinedstorage.common.networking.CableBlock;
import com.simibubi.create.content.fluids.pipes.AxisPipeBlock;
import com.simibubi.create.content.fluids.pipes.EncasedPipeBlock;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>哪些方块需要「外壳模型」</b>的唯一清单 —— 与「哪些方块可被裹住」共用同一份口径。
 *
 * <h2>为什么是「枚举注册表 + 类别判定」而不是一份方块 id 清单</h2>
 * <p>可被裹住的方块跨两个模组、几十个注册项（RS 的 16 色线缆共用 {@code CableBlock} 一个类、
 * 输入 / 输出总线各一个类；Create 的流体管道族四个类、传动杆一个类）。若在这里手写 id 清单，
 * 任何一个新增变体（别的模组的子类、Create 新增的同族方块）都会静默漏掉 —— 表现就是
 * 「能裹上去，但看不见外壳」这种最难排查的问题。</p>
 * <p>因此本类反过来做：<b>遍历方块注册表</b>，用与
 * {@link SeparationFrameGuard#isFluidPipe(net.minecraft.world.level.block.state.BlockState)}
 * / {@link cretae.cookiewyq.rs_create_compat.support.RsccWireBlocks#isWire}
 * <b>同源的类别判定</b>挑出目标族。判定谓词只有一处定义（本方法），
 * 因此「能裹什么」与「给什么画外壳」永远不会漂移。</p>
 *
 * <p><b>为什么是客户端专用</b>：这两件事（替换烘焙模型、注册方块颜色处理器）都只发生在客户端，
 * 服务端不需要知道任何一族的类名，因此本类只被 {@link ClientInit} 引用，专用服务端不会加载它。</p>
 */
public final class CamouflageShellTargets {
    private CamouflageShellTargets() {
    }

    /**
     * 需要外壳模型 / 材质着色的方块（每次调用遍历一次注册表）。
     *
     * <p>调用时机只有两个 —— 模型烘焙完成、注册颜色处理器 —— 都是「每次资源重载一次」的级别，
     * 因此这里不做任何缓存（不缓存就不会有「注册表变了但缓存没变」的问题）。</p>
     */
    public static List<Block> blocks() {
        final List<Block> targets = new ArrayList<>();
        for (final Block block : BuiltInRegistries.BLOCK) {
            if (isShellTarget(block)) {
                targets.add(block);
            }
        }
        return targets;
    }

    /**
     * 单一判定：这个方块是不是「外壳族」。
     *
     * <p>与 {@link SeparationFrameGuard#isSheatheableFamily} 同源（同一个类层次口径），
     * 但这里按 <b>Block 类</b>判定（那里按 BlockState 判定）—— 两者覆盖的族必须一致：
     * 线缆族（{@code CableBlock} / 输入总线 / 输出总线 / <b>外部存储总线</b>）、流体管道族
     * （普通 / 装壳 / 玻璃直管 / 智能）、传动杆族（{@code ShaftBlock}）。</p>
     *
     * <p><b>2026-10-10（用户第 3 条）</b>：外部存储总线并进「可套壳族」后，这里必须同步并进来，
     * 否则就会出现最难受的一种表现 —— 伪装能裹上去，但那一格<b>不显示外壳</b>
     * （模型没被换成 {@code CamouflageShellModel}），也就是「裹了个寂寞」。
     * 两处是同一份口径的两面：{@code isSheatheableFamily} 管「能不能裹」，
     * 本方法管「给谁画外壳」，任一漏一处都会漂移。</p>
     */
    public static boolean isShellTarget(final Block block) {
        return block instanceof CableBlock
            || block instanceof com.refinedmods.refinedstorage.common.importer.ImporterBlock
            || block instanceof com.refinedmods.refinedstorage.common.exporter.ExporterBlock
            || block instanceof com.refinedmods.refinedstorage.common.storage.externalstorage.ExternalStorageBlock
            || block instanceof FluidPipeBlock
            || block instanceof EncasedPipeBlock
            || block instanceof AxisPipeBlock
            || block instanceof SmartFluidPipeBlock
            || block instanceof ShaftBlock;
    }
}
