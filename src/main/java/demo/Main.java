package demo;

import di.core.SimpleDiContainer;
import di.model.BeanDefinition;
import di.model.LiteralValue;
import di.model.MethodArg;
import di.model.MethodInjection;
import di.model.Scope;

import java.util.List;

public class Main {

    public static final class Foo {
        public Foo() {}
    }

    public static final class Person {
        private final String name;
        private int age;

        public Person(String name) {
            this.name = name;
        }

        public void setAge(int age) {
            this.age = age;
        }

        @Override
        public String toString() {
            return "Person{name='%s', age=%d}".formatted(name, age);
        }
    }

    public static void main(String[] args) throws Exception {
        var container = new SimpleDiContainer(List.of(
                new BeanDefinition("fooSingleton", Foo.class, Scope.SINGLETON),
                new BeanDefinition("fooPrototype", Foo.class, Scope.PROTOTYPE),
                new BeanDefinition("fooThread", Foo.class, Scope.THREAD),
                new BeanDefinition(
                        "person",
                        Person.class,
                        Scope.PROTOTYPE,
                        List.of(
                                new MethodArg(0, new LiteralValue("Alice"))
                        ),
                        List.of(
                                new MethodInjection("setAge",
                                        List.of(new MethodArg(0, new LiteralValue(30))))
                        )
                )
        ));

        Object s1 = container.getBean("fooSingleton");
        Object s2 = container.getBean("fooSingleton");
        System.out.println("singleton same: " + (s1 == s2));

        Object p1 = container.getBean("fooPrototype");
        Object p2 = container.getBean("fooPrototype");
        System.out.println("prototype same: " + (p1 == p2));

        Object t1 = container.getBean("fooThread");
        final Object[] t2 = new Object[1];
        Thread th = new Thread(() -> t2[0] = container.getBean("fooThread"));
        th.start();
        th.join();
        System.out.println("thread same: " + (t1 == t2[0]));

        System.out.println("getBeanCount(\"fooSingleton\")=" + container.getGetBeanCount("fooSingleton"));
        System.out.println("isInstantiatedSingleton(\"fooSingleton\")=" + container.isInstantiatedSingleton("fooSingleton"));

        Person p = container.getBean(Person.class);
        System.out.println("person from container: " + p);
    }
}