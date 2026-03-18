package di.model;

import java.util.Map;

/**
 * Ассоциативная коллекция значений (map в конфиге).
 */
public record MapValue(Map<String, BeanValue> entries) implements BeanValue {
}

