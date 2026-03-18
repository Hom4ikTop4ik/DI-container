package di.model;

import java.util.List;
import java.util.Objects;

/**
 * Описание вызова метода (обычно сеттера) при инициализации бина.
 */
public final class MethodInjection {
    private final String methodName;
    private final List<MethodArg> arguments;

    public MethodInjection(String methodName, List<MethodArg> arguments) {
        this.methodName = Objects.requireNonNull(methodName, "methodName");
        this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public String methodName() {
        return methodName;
    }

    public List<MethodArg> arguments() {
        return arguments;
    }
}

