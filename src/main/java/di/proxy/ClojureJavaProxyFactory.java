package di.proxy;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import di.api.DiContainer;

import java.lang.reflect.Constructor;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ClojureJavaProxyFactory implements ScopedProxyFactory {

    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    private static final IFn SYMBOL = Clojure.var("clojure.core", "symbol");

    private final IFn generateSourceFn;
    private final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();
    private final ConcurrentMap<Class<?>, Class<?>> proxyClassCache = new ConcurrentHashMap<>();

    public ClojureJavaProxyFactory() {
        REQUIRE.invoke(SYMBOL.invoke("di.proxy-generator"));
        this.generateSourceFn = Clojure.var("di.proxy-generator", "generate-java-proxy-source");
    }

    @Override
    public Object createThreadScopedProxy(Class<?> iface, String beanName, DiContainer container) {
        Objects.requireNonNull(iface, "iface");
        Objects.requireNonNull(beanName, "beanName");
        Objects.requireNonNull(container, "container");

        if (!iface.isInterface()) {
            throw new IllegalArgumentException("Proxy can only be generated for interfaces: " + iface.getName());
        }

        Class<?> proxyClass = proxyClassCache.computeIfAbsent(iface, this::compileProxyClass);

        try {
            Constructor<?> ctor = proxyClass.getConstructor(DiContainer.class, String.class);
            return ctor.newInstance(container, beanName);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate proxy " + proxyClass.getName()
                    + " for interface " + iface.getName(), e);
        }
    }

    private Class<?> compileProxyClass(Class<?> iface) {
        String proxyFqcn = "di.generated.ThreadScopedProxy__" + sanitize(iface.getName());
        String source = (String) generateSourceFn.invoke(iface, proxyFqcn);

        ClassLoader parent = iface.getClassLoader();
        if (parent == null) {
            parent = ClassLoader.getSystemClassLoader();
        }

        // "Правильнее": собирать CP из classloader + якорей
        String cp = ClasspathBuilder.build(parent, iface, DiContainer.class, ClojureJavaProxyFactory.class);

        return compiler.compile(proxyFqcn, source, parent, cp);
    }

    private static String sanitize(String s) {
        return s.replace('.', '_').replace('$', '_');
    }
}
