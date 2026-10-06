package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器：暴露 RS 网络节点容器方块实体的受保护字段 {@code mainNetworkNode}。
 * <p><b>为什么必须挂在「声明类」上</b>：该字段声明在泛型祖父类
 * {@code AbstractNetworkNodeContainerBlockEntity<T extends NetworkNode>} 里
 * （{@code protected final T mainNetworkNode;}，泛型擦除后描述符为
 * {@code Lcom/refinedmods/refinedstorage/api/network/node/NetworkNode;}）。
 * {@code @Shadow} 无法定位「继承自父类」的字段——在子类（如
 * {@code com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity}
 * 或 {@code com.refinedmods.refinedstorage.common.exporter.AbstractExporterBlockEntity}）上写
 * {@code @Shadow} 会在<b>模组加载期</b>抛
 * {@code InvalidMixinException: @Shadow field mainNetworkNode was not located in the target class}
 * 并直接崩游戏（见 2026-09-13 09:37 崩溃报告）。
 * <p>改用 {@link Accessor} 后，Mixin 按「名字 + 描述符」在
 * {@link AbstractNetworkNodeContainerBlockEntity} <b>自身</b>查找该字段，必然命中；
 * 子类 Mixin 只需把 {@code this} 强转为本接口即可读取节点（子类一定拥有该父类字段）。
 */
@Mixin(AbstractNetworkNodeContainerBlockEntity.class)
public interface MainNetworkNodeAccessor {
    /** 读取 mainNetworkNode（返回泛型擦除后的上界接口 NetworkNode）。 */
    @Accessor("mainNetworkNode")
    NetworkNode rscc$mainNetworkNode();
}
