package di.model;

import java.util.List;
import java.util.Objects;

public final class BeanDefinition {
    private final String name;
    private final Class<?> implClass;
    private final Scope scope;
    private final List<MethodArg> constructorArgs;
    private final List<MethodInjection> methodInjections;

    public BeanDefinition(String name,
                          Class<?> implClass,
                          Scope scope) {
        this(name, implClass, scope, List.of(), List.of());
    }

    public BeanDefinition(String name,
                          Class<?> implClass,
                          Scope scope,
                          List<MethodArg> constructorArgs,
                          List<MethodInjection> methodInjections) {
        this.name = Objects.requireNonNull(name, "name");
        this.implClass = Objects.requireNonNull(implClass, "implClass");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.constructorArgs = List.copyOf(Objects.requireNonNull(constructorArgs, "constructorArgs"));
        this.methodInjections = List.copyOf(Objects.requireNonNull(methodInjections, "methodInjections"));
    }

    public String name() {
        return name;
    }

    public Class<?> implClass() {
        return implClass;
    }

    public Scope scope() {
        return scope;
    }

    public List<MethodArg> constructorArgs() {
        return constructorArgs;
    }

    public List<MethodInjection> methodInjections() {
        return methodInjections;
    }

    @Override
    public String toString() {
        return "BeanDefinition{name='%s', implClass=%s, scope=%s, ctorArgs=%d, methods=%d}"
                .formatted(name, implClass.getName(), scope, constructorArgs.size(), methodInjections.size());
    }
}