package di.api;

import di.model.BeanDefinition;

import java.util.List;

public interface DiContainer {
    Object getBean(String name);

    <T> T getBean(Class<T> type);

    <T> T getBean(Class<T> type, String name);

    List<BeanDefinition> listBeanDefinitions();

    boolean isInstantiatedSingleton(String name);

    long getGetBeanCount(String name);
}