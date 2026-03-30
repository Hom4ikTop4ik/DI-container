package di.api;

import di.model.BeanDefinition;

import java.util.List;
import java.util.Map;

public interface DiContainer {
    Object getBean(String name);

    <T> T getBean(Class<T> type);

    <T> T getBean(Class<T> type, String name);

    List<BeanDefinition> listBeanDefinitions();

    boolean isInstantiatedSingleton(String name);

    long getGetBeanCount(String name);

    /**
     * Граф зависимостей: "имя бина" -> список "имён бинов, от которых он зависит".
     * Реализация не обязана строить граф с полной достоверностью в случаях неоднозначности,
     * но должна быть полезной для диагностики.
     */
    Map<String, List<String>> getDependencyGraph();
}