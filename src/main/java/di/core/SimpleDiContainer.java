package di.core;

import di.api.DiContainer;
import di.model.BeanDefinition;
import di.model.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

public final class SimpleDiContainer implements DiContainer {

    private final Map<String, BeanDefinition> defsByName;
    private final Map<Class<?>, List<BeanDefinition>> defsByImplType;
    private final ConcurrentMap<String, Object> singletonCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, Object>> threadCache =
            ThreadLocal.withInitial(HashMap::new);

    private final ConcurrentMap<String, LongAdder> getBeanCounters = new ConcurrentHashMap<>();

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
            throw new NoSuchElementException("Bean not found by name: " + name);
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
            throw new NoSuchElementException("No beans found for type: " + type.getName());
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Ambiguous beans for type %s: %s"
                    .formatted(type.getName(), candidates.stream().map(BeanDefinition::name).toList()));
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
            Object instance = instantiateWithConstructor(def);
            performMethodInjections(def, instance);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate bean: " + def, e);
        }
    }

    private Object instantiateWithConstructor(BeanDefinition def) throws ReflectiveOperationException {
        Constructor<?>[] ctors = def.implClass().getDeclaredConstructors();
        List<MethodArg> ctorArgs = def.constructorArgs();

        if (ctorArgs.isEmpty()) {
            if (ctors.length != 1) {
                throw new IllegalStateException("Class " + def.implClass().getName()
                        + " must have exactly 1 constructor for zero-arg injection (currently: " + ctors.length + ")");
            }
            Constructor<?> ctor = ctors[0];
            if (ctor.getParameterCount() != 0) {
                throw new IllegalStateException("Constructor for " + def.implClass().getName()
                        + " expected to have no parameters");
            }
            ctor.setAccessible(true);
            return ctor.newInstance();
        }

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
                    + def.implClass().getName());
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Ambiguous constructor overload for "
                    + def.implClass().getName() + " with " + paramCount + " parameter(s)");
        }

        Constructor<?> ctor = matches.getFirst();
        ctor.setAccessible(true);

        Object[] args = new Object[paramCount];
        Class<?>[] paramTypes = ctor.getParameterTypes();
        for (MethodArg arg : ctorArgs) {
            int idx = arg.index();
            if (idx >= paramCount) {
                throw new IllegalStateException("Constructor arg index " + idx
                        + " is out of bounds for " + def.implClass().getName());
            }
            args[idx] = resolveValue(arg.value(), paramTypes[idx]);
        }

        return ctor.newInstance(args);
    }

    private void performMethodInjections(BeanDefinition def, Object instance) throws ReflectiveOperationException {
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
                        + clazz.getName());
            }
            if (candidates.size() > 1) {
                throw new IllegalStateException("Ambiguous setter overload for method '" + methodName
                        + "' on " + clazz.getName());
            }

            Method method = candidates.getFirst();
            method.setAccessible(true);

            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] argValues = new Object[args.size()];
            for (MethodArg arg : args) {
                int idx = arg.index();
                if (idx < 0 || idx >= argValues.length) {
                    throw new IllegalStateException("Method arg index " + idx
                            + " is out of bounds for method '" + methodName + "' on " + clazz.getName());
                }
                argValues[idx] = resolveValue(arg.value(), paramTypes[idx]);
            }

            method.invoke(instance, argValues);
        }
    }

    private Object resolveValue(BeanValue value, Class<?> expectedType) {
        if (value instanceof LiteralValue literal) {
            return literal.value();
        }
        if (value instanceof RefValue ref) {
            return getBean(ref.beanName());
        }
        if (value instanceof ListValue list) {
            List<Object> result = new ArrayList<>();
            for (BeanValue v : list.elements()) {
                result.add(resolveValue(v, Object.class));
            }
            return result;
        }
        if (value instanceof MapValue map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, BeanValue> e : map.entries().entrySet()) {
                result.put(e.getKey(), resolveValue(e.getValue(), Object.class));
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported BeanValue: " + value);
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
}