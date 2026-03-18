package di.model;

/**
 * Литеральное значение (:value ...), например строка/число/boolean.
 */
public record LiteralValue(Object value) implements BeanValue {
}

