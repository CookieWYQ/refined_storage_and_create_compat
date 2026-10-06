package cretae.cookiewyq.rs_create_compat.block;

import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** 全局共享的“已接入网络/激活”方块状态属性，用于未连接(灰)/已连接(亮)双贴图切换。 */
public final class ModBlockStateProperties {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private ModBlockStateProperties() {
    }
}
