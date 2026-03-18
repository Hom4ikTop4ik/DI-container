package di.model;

/**
 * Абстрактное представление значения в метаданных бина.
 * Может быть ссылкой на другой бин, литералом или коллекцией.
 */
public sealed interface BeanValue
        permits RefValue, LiteralValue, ListValue, MapValue {
}

