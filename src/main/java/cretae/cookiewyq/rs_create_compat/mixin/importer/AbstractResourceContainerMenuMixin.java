package cretae.cookiewyq.rs_create_compat.mixin.importer;

import com.refinedmods.refinedstorage.common.support.containermenu.AbstractResourceContainerMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccImporterMenuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给「延长型输入」界面提供与服务端同节奏的同步时机。
 *
 * <p><b>为什么必须注入到这里</b>：输出总线那边的菜单（{@code ExporterContainerMenu}）<b>自己声明了</b>
 * {@code broadcastChanges}，所以那边能直接注入；而 {@code ImporterContainerMenu} <b>没有声明</b>它
 * ——该方法声明在祖父类 {@link AbstractResourceContainerMenu} 上。本项目硬规则是「{@code @Inject}
 * 只能定位目标类自身声明的方法」，因此只能在声明处这一个注入点挂钩，再按接口回调给输入总线菜单。</p>
 *
 * <p><b>影响面</b>：所有 RS 过滤器类菜单每 tick 多一次 {@code instanceof} 判定，未实现
 * {@link RsccImporterMenuBridge} 的菜单（= 除输入总线外的全部菜单）立即返回，开销可忽略。</p>
 */
@Mixin(AbstractResourceContainerMenu.class)
public abstract class AbstractResourceContainerMenuMixin {
    /** {@code broadcastChanges} 末尾：若是输入总线菜单则按变化推送「延长型输入」状态。 */
    @Inject(method = "broadcastChanges", at = @At("TAIL"))
    private void rscc$pushImporterMode(final CallbackInfo ci) {
        if ((Object) this instanceof RsccImporterMenuBridge bridge) {
            bridge.rscc$pushImporterSync();
        }
    }
}
