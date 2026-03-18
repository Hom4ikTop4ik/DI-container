package di.model;

import java.util.List;

/**
 * Коллекция значений (вектор/список в конфиге).
 */
public record ListValue(List<BeanValue> elements) implements BeanValue {
}

