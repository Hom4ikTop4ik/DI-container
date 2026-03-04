package di.model;

import java.util.Objects;

public final class BeanDefinition {
    private final String name;
    private final Class<?> implClass;
    private final Scope scope;

    public BeanDefinition(String name, Class<?> implClass, Scope scope) {
        this.name = Objects.requireNonNull(name, "name");
        this.implClass = Objects.requireNonNull(implClass, "implClass");
        this.scope = Objects.requireNonNull(scope, "scope");
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

    @Override
    public String toString() {
        return "BeanDefinition{name='%s', implClass=%s, scope=%s}"
                .formatted(name, implClass.getName(), scope);
    }
}