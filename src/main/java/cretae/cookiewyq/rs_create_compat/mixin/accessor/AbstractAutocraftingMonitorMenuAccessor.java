package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.common.autocrafting.monitor.AbstractAutocraftingMonitorContainerMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyMonitorBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器：暴露 RS 自动合成管理器菜单私有的 {@code currentTaskId} 字段。
 *
 * <p>该字段<b>声明在 {@link AbstractAutocraftingMonitorContainerMenu} 自身</b>（不是父类），
 * 因此 {@code @Accessor} 挂在这里必然命中；方块版与无线版两个子类都会继承到这个 Mixin，
 * 因此两处界面都能取到「当前选中的任务 id」。</p>
 */
@Mixin(AbstractAutocraftingMonitorContainerMenu.class)
public abstract class AbstractAutocraftingMonitorMenuAccessor implements RsccAssemblyMonitorBridge {
    @Override
    @Accessor("currentTaskId")
    public abstract TaskId rscc$currentTaskId();
}
