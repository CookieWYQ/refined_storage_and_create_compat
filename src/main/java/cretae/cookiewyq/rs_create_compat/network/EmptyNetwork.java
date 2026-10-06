package cretae.cookiewyq.rs_create_compat.network;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.NetworkComponent;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空网络实现：终端未绑定 / 网络不可达时，给虚拟方块实体绑定此网络，
 * 使样板终端 / 自动合成仓管理器界面能正常打开并显示灰色空状态（仿 RS 原版无线网格"未连接"行为）。
 * <p>
 * 所有网络组件通过动态代理返回空数据（空列表 / Optional.empty / 0），
 * 界面查询与构造不会崩溃，操作（插入/提取）自然被拒绝。
 */
public final class EmptyNetwork implements Network {
    public static final EmptyNetwork INSTANCE = new EmptyNetwork();

    private final Map<Class<?>, Object> proxies = new ConcurrentHashMap<>();

    private EmptyNetwork() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I extends NetworkComponent> I getComponent(final Class<I> componentType) {
        return (I) proxies.computeIfAbsent(componentType, type -> Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> defaultValue(method.getReturnType())));
    }

    private static Object defaultValue(final Class<?> returnType) {
        if (returnType == long.class || returnType == int.class || returnType == double.class
            || returnType == float.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == List.class || returnType == Collection.class || returnType == Iterable.class) {
            return List.of();
        }
        if (returnType == Set.class) {
            return Set.of();
        }
        if (returnType == Map.class) {
            return Map.of();
        }
        if (Optional.class.isAssignableFrom(returnType)) {
            return Optional.empty();
        }
        return null;
    }

    @Override
    public void addContainer(final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer container) {
        // no-op
    }

    @Override
    public void removeContainer(final com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer container) {
        // no-op
    }

    @Override
    public void remove() {
        // no-op
    }

    @Override
    public void split(final Set<Network> networks) {
        // no-op
    }

    @Override
    public void merge(final Network network) {
        // no-op
    }
}
