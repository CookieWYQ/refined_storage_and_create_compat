package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import org.jetbrains.annotations.Nullable;

/**
 * 桥接接口：由 Mixin 注入到 RS 的「自动合成管理器」菜单
 * （{@code AbstractAutocraftingMonitorContainerMenu}）上，用于读出「当前选中的任务 id」。
 *
 * <p>放在 support 包（而非 mixin 包）的原因与 {@link RsccAutocrafterManagerAccess} 一致：
 * mixin 包中的类不能被普通代码直接引用（否则 IllegalClassLoadError）；普通代码只引用本接口。</p>
 *
 * <p>为什么要这个 id：管理器的处置按钮（继续 / 取消 / 更换机器）作用于「当前选中的那条任务」，
 * 而 RS 把这个 id 藏在菜单的私有字段里（包级可见，外部无法直接取）。</p>
 */
public interface RsccAssemblyMonitorBridge {
    /** 当前选中的自动合成任务 id（没有选中任何任务时为 {@code null}）。 */
    @Nullable
    TaskId rscc$currentTaskId();
}
