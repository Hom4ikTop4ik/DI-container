package di.model;

import java.util.Objects;

/**
 * Описание одного аргумента метода/конструктора.
 * Пока храним только позицию и значение.
 */
public final class MethodArg {
    private final int index;
    private final BeanValue value;

    public MethodArg(int index, BeanValue value) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        this.index = index;
        this.value = Objects.requireNonNull(value, "value");
    }

    public int index() {
        return index;
    }

    public BeanValue value() {
        return value;
    }
}

