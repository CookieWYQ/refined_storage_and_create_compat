package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.exporter.AbstractExporterBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 桥接接口：由 Mixin（注入 RS {@code ExporterContainerMenu}）实现，
 * 把服务端侧持有的输出总线方块实体暴露出来。
 * <p>用途：输出总线界面需要把「是否处于延长型输出模式 / 当前导出类别」同步给客户端
 * （客户端菜单拿不到方块实体），以及接收客户端发来的类别切换请求。客户端侧的菜单实例
 * 没有方块实体，返回 null。</p>
 */
public interface RsccExporterMenuBridge {
    /** 服务端：本菜单对应的输出总线方块实体；客户端返回 null。 */
    @Nullable
    AbstractExporterBlockEntity rscc$getExporter();
}
