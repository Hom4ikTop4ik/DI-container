package di.core;

import di.api.DiContainer;
import di.model.BeanDefinition;
import di.model.Scope;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

public final class SimpleDiContainer implements DiContainer {

    private final Map<String, BeanDefinition> defsByName;
    private final ConcurrentMap<String, Object> singletonCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, Object>> threadCache =
            ThreadLocal.withInitial(HashMap::new);

    private final ConcurrentMap<String, LongAdder> getBeanCounters = new ConcurrentHashMap<>();

    public SimpleDiContainer(List<BeanDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        this.defsByName = indexAndValidate(definitions);
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
        ArrayList<BeanDefinition> result = new ArrayList<>();
        for (BeanDefinition def : defsByName.values()) {
            if (type.isAssignableFrom(def.implClass())) {
                result.add(def);
            }
        }
        return result;
    }

    private Object createNew(BeanDefinition def) {
        try {
            Constructor<?>[] ctors = def.implClass().getDeclaredConstructors();
            if (ctors.length != 1) {
                throw new IllegalStateException("Class " + def.implClass().getName()
                        + " must have exactly 1 constructor for MVP-0 (currently: " + ctors.length + ")");
            }
            Constructor<?> ctor = ctors[0];
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate bean: " + def, e);
        }
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