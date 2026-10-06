package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccNodeContainerAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问器：暴露 RS 网络节点方块实体基类里受保护的 {@code mainNetworkNode} 字段。
 *
 * <p><b>为什么必须挂在「声明类」上</b>：{@code mainNetworkNode} 声明在
 * {@link AbstractNetworkNodeContainerBlockEntity} <b>自身</b>（不在子类里），按本工程硬规则
 * （见 {@code tools/verify_mixin_shadows.py}），{@code @Accessor} 必须挂在声明类上，否则会在
 * 模组加载期抛 {@code InvalidMixinException}；而且挂在这里之后，<b>所有</b> RS 网络节点方块实体
 * （自动合成器 / 线缆 / 控制器 / 本模组自己的样板库）都能被同一次注入覆盖。</p>
 *
 * <p>返回类型按泛型擦除后的上界书写（{@code T extends NetworkNode} → {@link NetworkNode}），
 * 与真实字段描述符一致。</p>
 */
@Mixin(AbstractNetworkNodeContainerBlockEntity.class)
public abstract class AbstractNetworkNodeContainerBlockEntityAccessor implements RsccNodeContainerAccess {
    @Override
    @Accessor("mainNetworkNode")
    public abstract NetworkNode rscc$mainNetworkNode();
}
