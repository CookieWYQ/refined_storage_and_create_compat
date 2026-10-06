package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.importer.AbstractImporterBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 桥接接口：由 Mixin（注入 RS {@code ImporterContainerMenu}）实现，
 * 把服务端侧持有的输入总线方块实体暴露出来。
 * <p>用途：输入总线界面需要把「是否处于延长型输入模式 / 当前收回类别」同步给客户端
 * （客户端菜单拿不到方块实体），以及接收客户端发来的类别切换请求。客户端侧的菜单实例
 * 没有方块实体，返回 null。{@link RsccExporterMenuBridge} 的对称版。</p>
 */
public interface RsccImporterMenuBridge {
    /** 服务端：本菜单对应的输入总线方块实体；客户端返回 null。 */
    @Nullable
    AbstractImporterBlockEntity rscc$getImporter();

    /**
     * 按「变化时」把「是否延长模式 + 类别快照」推给打开本菜单的玩家（服务端权威）。
     * <p>由 {@code AbstractResourceContainerMenuMixin} 在 RS 的 {@code broadcastChanges} 末尾调用
     * ——{@code ImporterContainerMenu} 自身没有声明该方法，无法直接注入，只能从声明处回调。</p>
     */
    void rscc$pushImporterSync();
}
