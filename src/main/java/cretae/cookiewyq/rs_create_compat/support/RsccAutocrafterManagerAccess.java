package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerBlockEntity;

/**
 * 桥接接口：由 Mixin 注入到 RS 的合成仓管理器菜单（AutocrafterManagerContainerMenu）上，
 * 用于读取其私有的 autocrafterManager 字段。
 * <p>
 * 放在 support 包（而非 mixin 包）的原因同上：mixin 包中的类不能被普通代码直接引用。
 */
public interface RsccAutocrafterManagerAccess {
    /** 取该菜单绑定的合成仓管理器方块实体（服务端非空；虚拟方块实体场景同样可拿到）。 */
    AutocrafterManagerBlockEntity rscc$getAutocrafterManager();
}
