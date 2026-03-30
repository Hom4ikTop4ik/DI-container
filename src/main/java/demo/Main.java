package demo;

import di.core.SimpleDiContainer;
import di.model.BeanDefinition;
import di.model.LiteralValue;
import di.model.MethodArg;
import di.model.MethodInjection;
import di.model.Scope;

import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

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

    public interface Greeter {
        String greet(String name);
    }

    public static final class EnglishGreeter implements Greeter {
        @Override
        public String greet(String name) {
            return "Hello, " + name;
        }
    }

    public static final class RussianGreeter implements Greeter {
        @Override
        public String greet(String name) {
            return "Привет, " + name;
        }
    }

    public interface RequestIdProvider {
        String currentRequestId();
    }

    public static final class ThreadRequestIdProvider implements RequestIdProvider {
        private final String id = UUID.randomUUID().toString();

        @Override
        public String currentRequestId() {
            return id;
        }
    }

    public static final class WelcomeService {
        @Inject
        @Named("ruGreeter")
        private Greeter greeter;

        @Inject
        private RequestIdProvider requestIdProvider;

        public void sayHello(String user) {
            System.out.println("[" + requestIdProvider.currentRequestId() + "] " + greeter.greet(user));
        }
    }

    public static final class PrototypeMessage {
        private final String id = UUID.randomUUID().toString();

        @Override
        public String toString() {
            return "PrototypeMessage{id='%s'}".formatted(id);
        }
    }

    public static final class MessagePrinter {
        private final Provider<PrototypeMessage> messageProvider;

        @Inject
        public MessagePrinter(Provider<PrototypeMessage> messageProvider) {
            this.messageProvider = messageProvider;
        }

        public void printTwice() {
            System.out.println("msg1: " + messageProvider.get());
            System.out.println("msg2: " + messageProvider.get());
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
                ,
                new BeanDefinition("enGreeter", EnglishGreeter.class, Scope.SINGLETON),
                new BeanDefinition("ruGreeter", RussianGreeter.class, Scope.SINGLETON),
                new BeanDefinition("threadRequestId", ThreadRequestIdProvider.class, Scope.THREAD),
                new BeanDefinition("welcomeService", WelcomeService.class, Scope.SINGLETON),
                new BeanDefinition("prototypeMessage", PrototypeMessage.class, Scope.PROTOTYPE),
                new BeanDefinition("messagePrinter", MessagePrinter.class, Scope.SINGLETON)
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

        WelcomeService welcome = container.getBean(WelcomeService.class);
        welcome.sayHello("Nikita");

        Thread t = new Thread(() -> {
            WelcomeService ws = container.getBean(WelcomeService.class);
            ws.sayHello("Nikita");
        });
        t.start();
        t.join();

        MessagePrinter printer = container.getBean(MessagePrinter.class);
        printer.printTwice();
    }
}