package di.model;

/**
 * Ссылка на другой бин по имени (:ref "beanName").
 */
public record RefValue(String beanName) implements BeanValue {
}

