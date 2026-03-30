package di.core;

import di.api.DiContainer;
import di.model.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

public final class SimpleDiContainer implements DiContainer {

    private final Map<String, BeanDefinition> defsByName;
    private final Map<Class<?>, List<BeanDefinition>> defsByImplType;
    private final ConcurrentMap<String, Object> singletonCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, Object>> threadCache =
            ThreadLocal.withInitial(HashMap::new);

    private final ConcurrentMap<String, LongAdder> getBeanCounters = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<String>> creationStack = ThreadLocal.withInitial(ArrayDeque::new);

    public SimpleDiContainer(List<BeanDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        this.defsByName = indexAndValidate(definitions);
        this.defsByImplType = buildTypeIndex(defsByName.values());
    }

    private static Map<String, BeanDefinition> indexAndValidate(List<BeanDefinition> definitions) {
        Map<String, BeanDefinition> map = new LinkedHashMap<>();
        for (BeanDefinition def : definitions) {
            if (map.containsKey(def.name())) {
                throw new IllegalArgumentException("Duplicate bean name: " + def.name());
            }
            map.put(def.name(), def);
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<Class<?>, List<BeanDefinition>> buildTypeIndex(Collection<BeanDefinition> definitions) {
        Map<Class<?>, List<BeanDefinition>> byType = new LinkedHashMap<>();
        for (BeanDefinition def : definitions) {
            byType.computeIfAbsent(def.implClass(), k -> new ArrayList<>()).add(def);
        }
        byType.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(byType);
    }

    @Override
    public Object getBean(String name) {
        countGetBean(name);
        BeanDefinition def = defsByName.get(name);
        if (def == null) {
            throw new NoSuchElementException("Bean not found by name: " + name + resolutionPathSuffix());
        }
        return switch (def.scope()) {
            case SINGLETON -> singletonCache.computeIfAbsent(def.name(), n -> createNew(def));
            case PROTOTYPE -> createNew(def);
            case THREAD -> threadCache.get().computeIfAbsent(def.name(), n -> createNew(def));
        };
    }

    @Override
    public <T> T getBean(Class<T> type) {
        Objects.requireNonNull(type, "type");
        List<BeanDefinition> candidates = findCandidates(type);

        if (candidates.isEmpty()) {
            throw new NoSuchElementException("No beans found for type: " + type.getName() + resolutionPathSuffix());
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Ambiguous beans for type %s: %s"
                    .formatted(type.getName(), candidates.stream().map(BeanDefinition::name).toList())
                    + resolutionPathSuffix());
        }
        return type.cast(getBean(candidates.getFirst().name()));
    }

    @Override
    public <T> T getBean(Class<T> type, String name) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Object bean = getBean(name);
        if (!type.isInstance(bean)) {
            throw new ClassCastException("Bean '%s' is %s, not assignable to %s"
                    .formatted(name, bean.getClass().getName(), type.getName()));
        }
        return type.cast(bean);
    }

    private List<BeanDefinition> findCandidates(Class<?> type) {
        List<BeanDefinition> result = new ArrayList<>();

        List<BeanDefinition> exactMatches = defsByImplType.get(type);
        if (exactMatches != null) {
            result.addAll(exactMatches);
        }

        for (BeanDefinition def : defsByName.values()) {
            if (def.implClass() != type && type.isAssignableFrom(def.implClass())) {
                result.add(def);
            }
        }

        return result;
    }

    private Object createNew(BeanDefinition def) {
        try {
            Deque<String> stack = creationStack.get();
            if (stack.contains(def.name())) {
                throw new IllegalStateException("Cyclic dependency detected: " + formatCycle(stack, def.name()));
            // Note: resolution path is already encoded into the cycle string above.
            }
            stack.addLast(def.name());

            Object instance = instantiate(def);
            performConfigMethodInjections(def, instance);
            performInjectFieldInjections(def, instance);
            performInjectMethodInjections(def, instance);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate bean: " + def + resolutionPathSuffix(), e);
        } finally {
            Deque<String> stack = creationStack.get();
            if (!stack.isEmpty() && Objects.equals(stack.peekLast(), def.name())) {
                stack.removeLast();
            }
        }
    }

    private Object instantiate(BeanDefinition def) throws ReflectiveOperationException {
        if (!def.constructorArgs().isEmpty()) {
            return instantiateFromConfig(def);
        }
        return instantiateFromInject(def);
    }

    private Object instantiateFromConfig(BeanDefinition def) throws ReflectiveOperationException {
        Constructor<?>[] ctors = def.implClass().getDeclaredConstructors();
        List<MethodArg> ctorArgs = def.constructorArgs();

        int maxIndex = ctorArgs.stream().mapToInt(MethodArg::index).max().orElse(-1);
        int paramCount = maxIndex + 1;

        List<Constructor<?>> matches = new ArrayList<>();
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterCount() == paramCount) {
                matches.add(ctor);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("No constructor with " + paramCount + " parameter(s) for "
                    + def.implClass().getName() + resolutionPathSuffix());
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous constructor overload for "
                    + def.implClass().getName() + " with " + paramCount + " parameter(s)" + resolutionPathSuffix());
        }

        Constructor<?> ctor = matches.getFirst();
        ctor.setAccessible(true);

        Object[] args = new Object[paramCount];
        Class<?>[] paramTypes = ctor.getParameterTypes();
        for (MethodArg arg : ctorArgs) {
            int idx = arg.index();
            if (idx >= paramCount) {
                throw new IllegalStateException("Constructor arg index " + idx
                        + " is out of bounds for " + def.implClass().getName() + resolutionPathSuffix());
            }
            args[idx] = resolveConfigValue(def, arg.value(), paramTypes[idx]);
        }

        return ctor.newInstance(args);
    }

    private Object instantiateFromInject(BeanDefinition def) throws ReflectiveOperationException {
        Constructor<?> ctor = selectInjectConstructor(def.implClass());
        ctor.setAccessible(true);

        Parameter[] params = ctor.getParameters();
        Type[] genericTypes = ctor.getGenericParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveInjectionPoint(def, params[i].getType(), genericTypes[i], params[i].getAnnotation(Named.class));
        }

        return ctor.newInstance(args);
    }

    private Constructor<?> selectInjectConstructor(Class<?> implClass) {
        Constructor<?>[] ctors = implClass.getDeclaredConstructors();
        List<Constructor<?>> injectCtors = new ArrayList<>();
        for (Constructor<?> c : ctors) {
            if (c.isAnnotationPresent(Inject.class)) {
                injectCtors.add(c);
            }
        }

        if (injectCtors.size() == 1) {
            return injectCtors.getFirst();
        }
        if (injectCtors.size() > 1) {
            throw new IllegalStateException("Multiple @Inject constructors found in " + implClass.getName() + resolutionPathSuffix());
        }

        if (ctors.length == 1) {
            return ctors[0];
        }
        throw new IllegalStateException("No @Inject constructor and multiple constructors found in "
                + implClass.getName() + resolutionPathSuffix());
    }

    private void performConfigMethodInjections(BeanDefinition def, Object instance) throws ReflectiveOperationException {
        Class<?> clazz = def.implClass();
        for (MethodInjection injection : def.methodInjections()) {
            String methodName = injection.methodName();
            List<MethodArg> args = injection.arguments();

            List<Method> candidates = new ArrayList<>();
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.size()) {
                    candidates.add(m);
                }
            }

            if (candidates.isEmpty()) {
                throw new IllegalStateException("No suitable method '" + methodName + "' found on "
                        + clazz.getName() + resolutionPathSuffix());
            }
            if (candidates.size() > 1) {
                throw new IllegalStateException("Ambiguous setter overload for method '" + methodName
                        + "' on " + clazz.getName() + resolutionPathSuffix());
            }

            Method method = candidates.getFirst();
            method.setAccessible(true);

            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] argValues = new Object[args.size()];
            for (MethodArg arg : args) {
                int idx = arg.index();
                if (idx < 0 || idx >= argValues.length) {
                    throw new IllegalStateException("Method arg index " + idx
                            + " is out of bounds for method '" + methodName + "' on " + clazz.getName()
                            + resolutionPathSuffix());
                }
                argValues[idx] = resolveConfigValue(def, arg.value(), paramTypes[idx]);
            }

            method.invoke(instance, argValues);
        }
    }

    private void performInjectFieldInjections(BeanDefinition current, Object instance) throws ReflectiveOperationException {
        for (Class<?> c = current.implClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.isAnnotationPresent(Inject.class)) {
                    continue;
                }
                f.setAccessible(true);
                Object dep = resolveInjectionPoint(current, f.getType(), f.getGenericType(), f.getAnnotation(Named.class));
                f.set(instance, dep);
            }
        }
    }

    private void performInjectMethodInjections(BeanDefinition current, Object instance) throws ReflectiveOperationException {
        Class<?> clazz = current.implClass();

        Set<String> configInjectedMethods = new HashSet<>();
        for (MethodInjection mi : current.methodInjections()) {
            configInjectedMethods.add(mi.methodName());
        }

        for (Method m : clazz.getMethods()) {
            if (!m.isAnnotationPresent(Inject.class)) {
                continue;
            }
            if (configInjectedMethods.contains(m.getName())) {
                throw new IllegalStateException("Double injection source for method '" + m.getName()
                        + "' on " + clazz.getName() + ": both config and @Inject" + resolutionPathSuffix());
            }

            m.setAccessible(true);
            Parameter[] params = m.getParameters();
            Type[] genericTypes = m.getGenericParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = resolveInjectionPoint(current, params[i].getType(), genericTypes[i], params[i].getAnnotation(Named.class));
            }
            m.invoke(instance, args);
        }
    }

    private Object resolveConfigValue(BeanDefinition current, BeanValue value, Class<?> expectedType) {
        if (value instanceof LiteralValue literal) {
            return ValueConverter.convert(literal.value(), expectedType);
        }
        if (value instanceof RefValue ref) {
            BeanDefinition dep = resolveBeanDefinition(Object.class, ref.beanName());
            return resolveScopedDependency(current, dep, Object.class, null);
        }
        if (value instanceof ListValue list) {
            List<Object> result = new ArrayList<>();
            for (BeanValue v : list.elements()) {
                result.add(resolveConfigValue(current, v, Object.class));
            }
            return result;
        }
        if (value instanceof MapValue map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, BeanValue> e : map.entries().entrySet()) {
                result.put(e.getKey(), resolveConfigValue(current, e.getValue(), Object.class));
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported BeanValue: " + value);
    }

    private Object resolveInjectionPoint(BeanDefinition current,
                                        Class<?> rawType,
                                        Type genericType,
                                        Named named) {
        String name = named == null ? null : named.value();

        if (Provider.class.equals(rawType)) {
            Class<?> providedRaw = extractProviderType(genericType);
            return (Provider<?>) () -> {
                if (name != null) {
                    return getBean(providedRaw, name);
                }
                return getBean(providedRaw);
            };
        }

        BeanDefinition dep = resolveBeanDefinition(rawType, name);
        return resolveScopedDependency(current, dep, rawType, name);
    }

    private Class<?> extractProviderType(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                Type t = args[0];
                if (t instanceof Class<?> c) {
                    return c;
                }
            }
        }
        throw new IllegalStateException("Provider<T> must have concrete generic type parameter" + resolutionPathSuffix());
    }

    private BeanDefinition resolveBeanDefinition(Class<?> type, String name) {
        if (name != null) {
            BeanDefinition def = defsByName.get(name);
            if (def == null) {
                throw new NoSuchElementException("Bean not found by name: " + name + resolutionPathSuffix());
            }
            if (!type.isAssignableFrom(def.implClass()) && type != Object.class) {
                throw new IllegalStateException("Bean '" + name + "' is " + def.implClass().getName()
                        + ", not assignable to " + type.getName() + resolutionPathSuffix());
            }
            return def;
        }

        List<BeanDefinition> candidates = findCandidates(type);
        if (candidates.isEmpty()) {
            throw new NoSuchElementException("No beans found for type: " + type.getName() + resolutionPathSuffix());
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Ambiguous beans for type %s: %s"
                    .formatted(type.getName(), candidates.stream().map(BeanDefinition::name).toList())
                    + resolutionPathSuffix());
        }
        return candidates.getFirst();
    }

    private Object resolveScopedDependency(BeanDefinition current,
                                          BeanDefinition dependency,
                                          Class<?> injectionType,
                                          String explicitName) {
        if (current.scope() == Scope.SINGLETON && dependency.scope() == Scope.THREAD) {
            if (!injectionType.isInterface()) {
                throw new IllegalStateException("thread-scoped dependency '" + dependency.name()
                        + "' injected into singleton '" + current.name()
                        + "' requires proxy, but injection type is not an interface: " + injectionType.getName()
                        + resolutionPathSuffix());
            }
            return createThreadScopedProxy(injectionType, dependency.name());
        }
        return getBean(dependency.name());
    }

    private Object createThreadScopedProxy(Class<?> iface, String beanName) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object target = getBean(beanName);
            return method.invoke(target, args);
        };
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static String formatCycle(Deque<String> stack, String repeatedName) {
        List<String> list = new ArrayList<>(stack);
        int idx = list.indexOf(repeatedName);
        if (idx < 0) {
            list.add(repeatedName);
            return String.join(" -> ", list);
        }
        List<String> cycle = new ArrayList<>(list.subList(idx, list.size()));
        cycle.add(repeatedName);
        return String.join(" -> ", cycle);
    }

    private void countGetBean(String name) {
        getBeanCounters.computeIfAbsent(name, n -> new LongAdder()).increment();
    }

    @Override
    public List<BeanDefinition> listBeanDefinitions() {
        return new ArrayList<>(defsByName.values());
    }

    @Override
    public boolean isInstantiatedSingleton(String name) {
        return singletonCache.containsKey(name);
    }

    @Override
    public long getGetBeanCount(String name) {
        LongAdder adder = getBeanCounters.get(name);
        return adder == null ? 0L : adder.sum();
    }

    @Override
    public Map<String, List<String>> getDependencyGraph() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (BeanDefinition def : defsByName.values()) {
            edges.put(def.name(), new LinkedHashSet<>());
        }

        // 1) Конфиг-ссылки (RefValue) из constructorArgs и methodInjections
        for (BeanDefinition def : defsByName.values()) {
            Set<String> deps = edges.get(def.name());
            for (MethodArg arg : def.constructorArgs()) {
                collectRefs(arg.value(), deps);
            }
            for (MethodInjection mi : def.methodInjections()) {
                for (MethodArg arg : mi.arguments()) {
                    collectRefs(arg.value(), deps);
                }
            }
        }

        // 2) Зависимости из точек @Inject/@Named (best-effort, без создания объектов)
        for (BeanDefinition def : defsByName.values()) {
            Set<String> deps = edges.get(def.name());
            addInjectDependenciesFromConstructors(def, deps);
            addInjectDependenciesFromFields(def, deps);
            addInjectDependenciesFromMethods(def, deps);
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
            result.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return result;
    }

    private void addInjectDependenciesFromConstructors(BeanDefinition def, Set<String> deps) {
        for (Constructor<?> ctor : def.implClass().getDeclaredConstructors()) {
            if (!ctor.isAnnotationPresent(Inject.class)) {
                continue;
            }
            Parameter[] params = ctor.getParameters();
            Type[] genericTypes = ctor.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                Named named = params[i].getAnnotation(Named.class);
                addInjectDependencyForType(genericTypes[i], params[i].getType(), named, deps);
            }
        }
    }

    private void addInjectDependenciesFromFields(BeanDefinition def, Set<String> deps) {
        for (Class<?> c = def.implClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!f.isAnnotationPresent(Inject.class)) {
                    continue;
                }
                Named named = f.getAnnotation(Named.class);
                addInjectDependencyForType(f.getGenericType(), f.getType(), named, deps);
            }
        }
    }

    private void addInjectDependenciesFromMethods(BeanDefinition def, Set<String> deps) {
        for (Method m : def.implClass().getMethods()) {
            if (!m.isAnnotationPresent(Inject.class)) {
                continue;
            }
            Parameter[] params = m.getParameters();
            Type[] genericTypes = m.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                Named named = params[i].getAnnotation(Named.class);
                addInjectDependencyForType(genericTypes[i], params[i].getType(), named, deps);
            }
        }
    }

    private void addInjectDependencyForType(Type genericType,
                                              Class<?> rawType,
                                              Named named,
                                              Set<String> deps) {
        if (Provider.class.equals(rawType)) {
            Class<?> provided;
            try {
                provided = extractProviderType(genericType);
            } catch (RuntimeException e) {
                // getDependencyGraph — best-effort мониторинг, не должен падать из-за некорректных сигнатур.
                return;
            }
            if (named != null) {
                deps.add(named.value());
                return;
            }
            for (BeanDefinition cand : findCandidates(provided)) {
                deps.add(cand.name());
            }
            return;
        }

        if (named != null) {
            deps.add(named.value());
            return;
        }

        for (BeanDefinition cand : findCandidates(rawType)) {
            deps.add(cand.name());
        }
    }

    private void collectRefs(BeanValue value, Set<String> out) {
        if (value instanceof RefValue ref) {
            out.add(ref.beanName());
            return;
        }
        if (value instanceof ListValue list) {
            for (BeanValue v : list.elements()) {
                collectRefs(v, out);
            }
            return;
        }
        if (value instanceof MapValue map) {
            for (BeanValue v : map.entries().values()) {
                collectRefs(v, out);
            }
            return;
        }
    }

    private String resolutionPathSuffix() {
        Deque<String> stack = creationStack.get();
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return " Resolution path: " + String.join(" -> ", stack);
    }
}