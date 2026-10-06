package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu;
import cretae.cookiewyq.rs_create_compat.support.RsccAutocrafterManagerAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器：暴露 RS 合成仓管理器菜单私有的 autocrafterManager 字段。
 * <p>
 * 服务端侧该字段一定是非空（真实管理器方块实体，或高级远程终端反射构造的虚拟方块实体），
 * 通过它可以拿到管理器所在网络中的所有自动合成仓，实现"按钮作用于本管理器网络内全部自动合成仓"。
 * <p>
 * 实现 {@link RsccAutocrafterManagerAccess}（普通包中的接口），这样普通代码只引用该接口，
 * 不会直接引用 mixin 包中的类（否则会触发 IllegalClassLoadError）。
 */
@Mixin(AutocrafterManagerContainerMenu.class)
public abstract class AutocrafterManagerMenuAccessor implements RsccAutocrafterManagerAccess {
    @Accessor("autocrafterManager")
    public abstract AutocrafterManagerBlockEntity rscc$getAutocrafterManager();
}
