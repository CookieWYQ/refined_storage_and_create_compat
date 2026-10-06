package cretae.cookiewyq.rs_create_compat.block.entity;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 分隔框架方块实体：<b>只</b>为客户端方块实体渲染器提供一个宿主，<b>不存任何数据、不 tick</b>。
 *
 * <p><b>为什么需要一个方块实体</b>：框架默认不可见、手持框架 / 护目镜+快捷键才显示，
 * 而区块网格是烘焙缓存的，无法按帧改变显隐；唯一可行的做法是把绘制交给方块实体渲染器
 * （{@code client.SeparationFrameRenderer}），而方块实体渲染器必须挂在方块实体上。
 * 因此这里刻意做到最轻：没有 ticker、没有 NBT、没有能力注册，
 * 大量放置（无限框架可以刷满一整条管道）时也只是一堆空对象。</p>
 */
public class SeparationFrameBlockEntity extends BlockEntity {
    public SeparationFrameBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.SEPARATION_FRAME_BLOCK_ENTITY.get(), pos, state);
    }
}
