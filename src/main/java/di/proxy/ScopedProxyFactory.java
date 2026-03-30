package di.proxy;

import di.api.DiContainer;

public interface ScopedProxyFactory {
    Object createThreadScopedProxy(Class<?> iface, String beanName, DiContainer container);
}
